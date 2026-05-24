###############################################################################
# infrastructure/terraform/glue.tf
#
# AWS Glue Spark ETL Job Definitions.
#
# Purpose:
#   Provisions the AWS Glue jobs that GlueETLConfig.java references via
#   @Value defaults. The jobs implement S3 -> RDS bulk-load + S3 -> S3
#   transformation pipelines for CardDemo:
#     1. carddemo-transaction-report-etl     -> REPTFILE.jcl replacement
#     2. carddemo-dalyrejs-etl               -> DALYREJS.jcl replacement
#     3. carddemo-transact-backup-etl        -> TRANBKP.jcl replacement
#     4. carddemo-transact-daily-etl         -> DALYTRAN load pipeline
#     5. carddemo-tcatbalf-backup-etl        -> TCATBALF backup pipeline
#     6. carddemo-systran-etl                -> SYSTRAN.jcl (INTCALC out)
#     7. carddemo-transact-combined-etl      -> COMBTRAN.jcl downstream
#     8. carddemo-bulk-load-etl              -> Initial seed (ACCT/CARD/CUST)
#
#   Each Glue job:
#     * Reads PySpark/Scala scripts from S3 (`s3://<batch_outputs>/glue-scripts/`)
#     * Uses the carddemo-<env>-glue-job IAM role (provisioned in iam.tf)
#     * Writes CloudWatch logs to `/aws-glue/jobs/carddemo-<env>` (the
#       canonical AWS Glue log group)
#     * Retries up to 1 time on failure (AWS default; tunable per job)
#     * Has a 60-minute timeout (tunable via var.glue_timeout_minutes)
#     * Runs on Glue 4.0 Spark runtime
#     * Uses G.1X workers (default) — tunable via var.glue_worker_type
#
# F-CP6-TF-Glue-01:
#   This file resolves the CP6 MAJOR review finding that glue.tf was
#   missing while GlueETLConfig.java referenced Terraform-managed Glue
#   jobs. The Glue job DEFINITIONS now exist as Terraform resources;
#   the actual PySpark scripts (under s3://<batch_outputs>/glue-scripts/)
#   are uploaded by the deployment pipeline and are out of Terraform scope.
#
# Cross-references:
#   * iam.tf            — aws_iam_role.glue_job_execution
#   * kms.tf            — aws_kms_key.cloudwatch_kms (Glue log encryption)
#   * s3.tf             — aws_s3_bucket.batch_outputs (script + data location)
#   * rds.tf            — Glue jobs connect via aws_glue_connection.rds
#   * variables.tf      — glue_worker_type, glue_number_of_workers,
#                         cloudwatch_log_retention_days
#   * GlueETLConfig.java — runtime client wrapping these definitions
###############################################################################

# =============================================================================
# Section 1 — Locals: shared job arguments + naming
# =============================================================================
# All Glue jobs share a common set of default arguments (logging,
# metrics, bookmarks, continuous logging). Defining them as a local map
# avoids duplication across 8 jobs and makes future changes a single
# diff.
# =============================================================================

locals {
  glue_log_group_name = "/aws-glue/jobs/carddemo-${var.environment}"
  glue_script_prefix  = "s3://${aws_s3_bucket.batch_outputs.bucket}/glue-scripts"
  glue_output_prefix  = "s3://${aws_s3_bucket.batch_outputs.bucket}/glue-output"

  # Per-job concurrency cap. Matches the GlueETLConfig @Value default
  # (`carddemo.glue.max-concurrent-runs:5`). For bulk-load (the only
  # single-tenant job), this is overridden per-resource below.
  glue_max_concurrent_runs = 5

  # Default arguments applied to every job. Per-job arguments may
  # override these.
  glue_common_default_args = {
    # Spark continuous logging into CloudWatch.
    "--continuous-log-logGroup"          = local.glue_log_group_name
    "--continuous-log-logStreamPrefix"   = "carddemo"
    "--enable-continuous-cloudwatch-log" = "true"
    "--enable-continuous-log-filter"     = "true"

    # CloudWatch metrics for Spark job observability.
    "--enable-metrics"          = "true"
    "--enable-spark-ui"         = "false"
    "--enable-glue-datacatalog" = "true"

    # Job bookmarks: incremental ETL (only new S3 objects since last run).
    "--job-bookmark-option" = "job-bookmark-enable"

    # Boto3 default Python version (Glue 4.0 supports 3.10).
    "--enable-job-insights" = "true"
  }
}

# =============================================================================
# Section 2 — Glue CloudWatch log group
# =============================================================================
# Encrypted log group for Glue Spark continuous-logging output. The Glue
# service principal is granted Encrypt/Decrypt on the cloudwatch_kms key
# in kms.tf via the AllowCloudWatchLogs key-policy statement.
# =============================================================================

resource "aws_cloudwatch_log_group" "glue_jobs" {
  name              = local.glue_log_group_name
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-log"
    Purpose = "AWS Glue Spark continuous-logging output"
  })
}

# =============================================================================
# Section 3 — Glue connection to RDS PostgreSQL
# =============================================================================
# JDBC connection used by Glue jobs that bulk-load fact data into the
# CardDemo RDS instance. The connection encapsulates VPC + subnet +
# security-group placement so Glue jobs can reach the private RDS
# endpoint. The JDBC URL is constructed from the RDS instance's
# endpoint + port + database name (no credentials in the connection —
# the Glue job pulls them from Secrets Manager at runtime).
# =============================================================================

resource "aws_glue_connection" "rds" {
  name = "carddemo-${var.environment}-rds"
  # Description has no description argument in aws_glue_connection;
  # consumers identify the connection by name + tags.

  connection_properties = {
    JDBC_CONNECTION_URL = "jdbc:postgresql://${aws_db_instance.carddemo.endpoint}/${aws_db_instance.carddemo.db_name}"
    # Username + password are pulled by the Glue job from Secrets
    # Manager at runtime (the glue_secrets_kms IAM policy attachment
    # in iam.tf grants the required secretsmanager:GetSecretValue +
    # kms:Decrypt actions). USERNAME / PASSWORD are still required as
    # connection properties by the AWS Glue API; they are populated
    # with sentinel placeholders here and overridden at runtime.
    USERNAME = "glue_runtime_override"
    PASSWORD = "glue_runtime_override"
  }

  physical_connection_requirements {
    # Glue executes its connection-test ENIs into ONE private subnet of
    # the RDS instance subnet group. Use the first private subnet for
    # determinism; Glue's distributed job scheduler may still spread
    # tasks across multiple subnets at job-run time.
    subnet_id              = var.private_subnet_ids[0]
    security_group_id_list = [aws_security_group.glue_rds.id]

    # The connection availability zone must match the subnet's AZ.
    availability_zone = data.aws_subnet.private_a.availability_zone
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-rds"
    Purpose = "Glue JDBC connection to RDS PostgreSQL for bulk loads"
  })
}

# Security group for Glue Spark task ENIs. Allows egress to RDS:5432
# and to AWS service endpoints via the VPC endpoints in main.tf.
resource "aws_security_group" "glue_rds" {
  name        = "carddemo-${var.environment}-glue-rds"
  description = "Egress-only SG for AWS Glue ENIs (PostgreSQL 5432 to RDS + HTTPS to S3/Secrets/KMS endpoints)"
  vpc_id      = var.vpc_id

  # Glue requires self-referencing ingress for cluster-internal traffic.
  ingress {
    description = "Self-referencing for Glue cluster-internal traffic"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    self        = true
  }

  egress {
    description = "PostgreSQL 5432 to RDS"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.carddemo.cidr_block]
  }

  egress {
    description = "HTTPS to S3/Secrets/KMS endpoints + AWS APIs"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.carddemo.cidr_block]
  }

  # Self-referencing egress for cluster-internal traffic.
  egress {
    description = "Self-referencing egress for Glue cluster-internal traffic"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    self        = true
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-rds-sg"
    Purpose = "AWS Glue Spark ENI security group"
  })
}

# =============================================================================
# Section 4 — Glue job definitions (8 total)
# =============================================================================
# All 8 job definitions follow the same pattern:
#   * name              = canonical job name matching the GlueETLConfig
#                          @Value default
#   * role_arn          = aws_iam_role.glue_job_execution.arn (iam.tf)
#   * glue_version      = "4.0" (Spark 3.3, Python 3.10)
#   * worker_type       = var.glue_worker_type (default "G.1X")
#   * number_of_workers = var.glue_number_of_workers (default 2)
#   * timeout           = 60 minutes (per GlueETLConfig default)
#   * max_retries       = 1
#   * connections       = ["carddemo-<env>-rds"] when the job touches RDS
#   * default_arguments = merge(local.glue_common_default_args, { ... })
#   * command           = pyspark script in s3://<batch_outputs>/glue-scripts/
# =============================================================================

# -----------------------------------------------------------------------------
# Job 1 — Transaction Report ETL (REPTFILE.jcl replacement).
#
# Reads transaction-report flat files from the legacy mainframe drop,
# transforms to the CardDemo report schema, writes to S3 for downstream
# Step Functions consumption.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "transaction_report_etl" {
  name              = "carddemo-transaction-report-etl"
  description       = "Transaction-report ETL (replaces app/jcl/REPTFILE.jcl) per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/transaction_report_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--output_prefix" = "${local.glue_output_prefix}/transaction-report/"
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-transaction-report-etl"
    Purpose = "Transaction report ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 2 — Daily Rejections ETL (DALYREJS.jcl replacement).
#
# Reads the daily-rejections S3 output of TransactionPostingService and
# transforms to the operations dashboard schema.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "daily_rejections_etl" {
  name              = "carddemo-dalyrejs-etl"
  description       = "Daily rejections ETL (replaces app/jcl/DALYREJS.jcl) per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/daily_rejections_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--output_prefix" = "${local.glue_output_prefix}/daily-rejections/"
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-dalyrejs-etl"
    Purpose = "Daily rejections ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 3 — TRANSACT backup ETL (TRANBKP.jcl replacement).
#
# Copies the TRANSACT.VSAM.KSDS daily backup from S3 (CombineTransactionsJob
# S3 archive output) to a long-term archive prefix with checksum
# verification.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "transact_backup_etl" {
  name              = "carddemo-transact-backup-etl"
  description       = "TRANSACT backup ETL (replaces app/jcl/TRANBKP.jcl) per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/transact_backup_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--output_prefix" = "${local.glue_output_prefix}/transact-backup/"
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-transact-backup-etl"
    Purpose = "TRANSACT backup ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 4 — TRANSACT daily ETL (DALYTRAN load pipeline).
#
# Loads the legacy DALYTRAN flat file into the DailyTransaction staging
# table via Glue's DataSource/DataSink writer + the carddemo-<env>-rds
# Glue connection.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "transact_daily_etl" {
  name              = "carddemo-transact-daily-etl"
  description       = "TRANSACT daily ETL — loads DALYTRAN flat file to DailyTransaction staging table per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1
  connections       = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/transact_daily_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--rds_secret_arn" = aws_secretsmanager_secret.rds_master.arn
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-transact-daily-etl"
    Purpose = "TRANSACT daily ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 5 — TCATBALF backup ETL.
#
# Copies the TransactionCategoryBalance table to S3 for nightly archival.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "tcatbalf_backup_etl" {
  name              = "carddemo-tcatbalf-backup-etl"
  description       = "TCATBALF backup ETL — archives TransactionCategoryBalance to S3 per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1
  connections       = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/tcatbalf_backup_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--rds_secret_arn" = aws_secretsmanager_secret.rds_master.arn
    "--output_prefix"  = "${local.glue_output_prefix}/tcatbalf-backup/"
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-tcatbalf-backup-etl"
    Purpose = "TCATBALF backup ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 6 — SYSTRAN ETL (INTCALC SYSTRAN DD replacement).
#
# Reads the InterestCalculationJob SYSTRAN output and transforms to the
# downstream consumption format.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "systran_etl" {
  name              = "carddemo-systran-etl"
  description       = "SYSTRAN ETL (replaces SYSTRAN DD in app/jcl/INTCALC.jcl) per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/systran_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--output_prefix" = "${local.glue_output_prefix}/systran/"
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-systran-etl"
    Purpose = "SYSTRAN ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 7 — TRANSACT combined ETL (COMBTRAN downstream).
#
# Reads the CombineTransactionsJob S3 archive output and joins with
# reference data for downstream reporting.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "transact_combined_etl" {
  name              = "carddemo-transact-combined-etl"
  description       = "TRANSACT combined ETL — joins COMBTRAN output with reference data per AAP §0.4.1"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1
  connections       = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/transact_combined_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--rds_secret_arn" = aws_secretsmanager_secret.rds_master.arn
    "--output_prefix"  = "${local.glue_output_prefix}/transact-combined/"
  })

  execution_property {
    max_concurrent_runs = local.glue_max_concurrent_runs
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-transact-combined-etl"
    Purpose = "TRANSACT combined ETL"
  })
}

# -----------------------------------------------------------------------------
# Job 8 — Bulk load ETL (initial seed of ACCT/CARD/CUST/XREF).
#
# One-time + on-demand seed loader that reads the legacy ASCII fixtures
# from S3 and bulk-loads them into Account, Card, Customer, and
# CardCrossReference tables via the carddemo-<env>-rds Glue connection.
# Replaces the JCL provisioning chain (ACCTFILE.jcl, CARDFILE.jcl,
# CUSTFILE.jcl, XREFFILE.jcl) for non-reference data per AAP §0.6.2.
# -----------------------------------------------------------------------------
resource "aws_glue_job" "bulk_load_etl" {
  name              = "carddemo-bulk-load-etl"
  description       = "Bulk load ETL — replaces app/jcl/ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl, XREFFILE.jcl for fact data per AAP §0.6.2"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1
  connections       = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "${local.glue_script_prefix}/bulk_load_etl.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--rds_secret_arn" = aws_secretsmanager_secret.rds_master.arn
    # No job bookmarking for bulk-load — operator runs explicitly.
    "--job-bookmark-option" = "job-bookmark-disable"
  })

  execution_property {
    # Bulk-load is single-tenant; concurrent runs would race on the
    # target tables.
    max_concurrent_runs = 1
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-bulk-load-etl"
    Purpose = "Initial seed bulk loader for ACCT/CARD/CUST/XREF tables"
  })
}

# =============================================================================
# Section 5 — Glue trigger schedules (optional / opt-in)
# =============================================================================
# AWS Glue triggers can fire jobs on a CRON schedule or in response to
# job-completion events. For CardDemo, batch jobs are orchestrated by
# AWS Step Functions (see eod-batch-pipeline.asl.json) and Step
# Functions invokes the Glue jobs directly via the
# `arn:aws:states:::glue:startJobRun.sync` task type — NO Glue trigger
# is required.
#
# This section is intentionally empty; the Step Functions state machine
# in src/main/resources/stepfunctions/ owns the schedule.
# =============================================================================

# =============================================================================
# Section 6 — Outputs (consumed by Step Functions definition + Java code)
# =============================================================================
# Glue job names are exposed for cross-resource references in Step
# Functions ASL JSON and for static asserts in the Java integration
# test layer.
# =============================================================================

output "glue_job_transaction_report" {
  description = "Glue job name for the transaction-report ETL"
  value       = aws_glue_job.transaction_report_etl.name
}

output "glue_job_daily_rejections" {
  description = "Glue job name for the daily-rejections ETL"
  value       = aws_glue_job.daily_rejections_etl.name
}

output "glue_job_transact_backup" {
  description = "Glue job name for the TRANSACT backup ETL"
  value       = aws_glue_job.transact_backup_etl.name
}

output "glue_job_transact_daily" {
  description = "Glue job name for the TRANSACT daily ETL"
  value       = aws_glue_job.transact_daily_etl.name
}

output "glue_job_tcatbalf_backup" {
  description = "Glue job name for the TCATBALF backup ETL"
  value       = aws_glue_job.tcatbalf_backup_etl.name
}

output "glue_job_systran" {
  description = "Glue job name for the SYSTRAN ETL"
  value       = aws_glue_job.systran_etl.name
}

output "glue_job_transact_combined" {
  description = "Glue job name for the TRANSACT combined ETL"
  value       = aws_glue_job.transact_combined_etl.name
}

output "glue_job_bulk_load" {
  description = "Glue job name for the bulk-load ETL (initial seed)"
  value       = aws_glue_job.bulk_load_etl.name
}

output "glue_connection_rds_name" {
  description = "Glue JDBC connection name for RDS PostgreSQL"
  value       = aws_glue_connection.rds.name
}

output "glue_log_group_name" {
  description = "Glue CloudWatch log group name"
  value       = aws_cloudwatch_log_group.glue_jobs.name
}
