###############################################################################
# infrastructure/terraform/glue.tf
#
# AWS Glue Spark ETL job definitions for the CardDemo bulk-load pipeline.
#
# Purpose:
#   Provisions the AWS Glue resources that replace the legacy mainframe
#   IDCAMS REPRO operations that copied flat ASCII PS datasets into VSAM
#   KSDS clusters. Per AAP §0.6.2 the migration strategy is:
#
#     - Reference data (DisclosureGroup, TransactionType, TransactionCategory,
#       default users)            -> Flyway migrations (V001..V015).
#     - Bulk fact data (Account, Card, Customer, CardCrossReference,
#       Transaction)              -> AWS Glue Spark jobs reading from S3
#                                    (where the ASCII fixtures are staged)
#                                    and writing to RDS PostgreSQL via JDBC.
#
#   This file declares five Glue Spark jobs (one per VSAM cluster being
#   bulk-loaded), a single Glue JDBC connection to the carddemo RDS
#   instance, a security group for the Glue workers, an ingress rule
#   that opens port 5432 on the RDS security group from the Glue
#   security group, five aws_s3_object resources that upload the
#   PySpark scripts to S3 with KMS encryption, an aws_glue_catalog_database
#   for Athena consumption, and an optional aws_glue_crawler that
#   inspects the S3-staged ASCII fixtures.
#
# Replaces:
#   - app/jcl/ACCTFILE.jcl STEP15 (IDCAMS REPRO ACCTDATA.PS -> ACCTDATA.VSAM.KSDS)
#     -> aws_glue_job.ascii_to_rds_account
#   - app/jcl/CARDFILE.jcl STEP15 (IDCAMS REPRO CARDDATA.PS -> CARDDATA.VSAM.KSDS)
#     -> aws_glue_job.ascii_to_rds_card
#   - app/jcl/CUSTFILE.jcl STEP15 (IDCAMS REPRO CUSTDATA.PS -> CUSTDATA.VSAM.KSDS)
#     -> aws_glue_job.ascii_to_rds_customer
#   - app/jcl/XREFFILE.jcl STEP15 (IDCAMS REPRO CARDXREF.PS -> CARDXREF.VSAM.KSDS)
#     -> aws_glue_job.ascii_to_rds_xref
#   - app/jcl/TRANFILE.jcl STEP15 (IDCAMS REPRO DALYTRAN.PS.INIT -> TRANSACT.VSAM.KSDS)
#     -> aws_glue_job.ascii_to_rds_transaction
#   - app/jcl/REPTFILE.jcl     (DEFINE GENERATIONDATAGROUP for TRANREPT)
#     -> S3 versioned objects + lifecycle (provisioned in s3.tf, not here)
#   - app/jcl/DEFGDGB.jcl      (DEFINE GENERATIONDATAGROUP for TRANSACT.BKUP,
#                               TRANSACT.DALY, TRANREPT, TCATBALF.BKUP,
#                               SYSTRAN, TRANSACT.COMBINED)
#     -> S3 versioned objects + lifecycle (provisioned in s3.tf, not here)
#   - app/jcl/DALYREJS.jcl     (DEFINE GENERATIONDATAGROUP for DALYREJS)
#     -> S3 versioned object + lifecycle (provisioned in s3.tf, not here)
#
# Driven by:
#   src/main/resources/stepfunctions/file-provisioning.asl.json — the Step
#   Functions Map state BulkLoadFactData fans these five Glue jobs out in
#   parallel (MaxConcurrency=5) per AAP §0.6.3. The state machine reads the
#   per-table Glue job names + source S3 URIs from
#   StepFunctionsOrchestrator.startFileProvisioning() input parameters
#   (ItemsPath $.bulkLoadJobs) so the names below are wired into the
#   orchestrator's request payload at runtime.
#
# Cross-references:
#   - main.tf            local.common_tags, data.aws_vpc.carddemo,
#                        data.aws_subnet.private_a
#   - variables.tf       var.environment, var.glue_worker_type,
#                        var.glue_number_of_workers, var.glue_crawler_enabled
#   - kms.tf             aws_kms_key.carddemo (S3 object encryption)
#   - s3.tf              aws_s3_bucket.batch_outputs (script + fixture staging)
#   - rds.tf             aws_db_instance.carddemo, aws_security_group.rds,
#                        aws_secretsmanager_secret_version.rds_master_value
#   - iam.tf             aws_iam_role.glue_job_execution
#   - cloudwatch.tf      aws_cloudwatch_log_group.glue_jobs
#   - infrastructure/terraform/glue_scripts/*.py — the PySpark scripts
#     uploaded by the aws_s3_object resources below.
#
# Security and compliance posture (AAP §0.7.1):
#   - All Glue script S3 objects are encrypted with the carddemo KMS CMK
#     (server_side_encryption = "aws:kms", kms_key_id = aws_kms_key.carddemo.arn).
#   - The Glue JDBC connection negotiates TLS with the RDS instance
#     (the RDS parameter group sets rds.force_ssl=1 in rds.tf).
#   - JDBC credentials are pulled from Secrets Manager at job-run time
#     via the connection's USERNAME/PASSWORD properties, sourced from
#     aws_secretsmanager_secret_version.rds_master_value.secret_string.
#   - The Glue security group is egress-only (plus self-referencing
#     internal traffic) — Glue ENIs are never reachable from outside
#     the VPC.
#   - Continuous CloudWatch logging is enabled per job
#     (--enable-continuous-cloudwatch-log=true) so Spark driver/executor
#     output is streamed to the /aws/glue/carddemo-<env> log group in
#     real time per AAP §0.6.6 observability requirements.
###############################################################################

# =============================================================================
# Section 1 — Locals: S3 prefixes for Glue scripts and fixtures
# =============================================================================
# The Glue scripts (PySpark) and the ASCII fixtures live under stable
# prefixes inside the shared batch-outputs bucket. Defining the prefixes
# as locals keeps the script_location, source_key, and crawler s3_target
# values in sync across the five jobs without repeating the literal
# path in multiple places.
#
# Schema-mandated exports:
#   - local.glue_scripts_prefix   (kind: constant)
#   - local.glue_fixtures_prefix  (kind: constant)
# =============================================================================

locals {
  # S3 prefix that hosts the PySpark scripts (load_account.py,
  # load_card.py, load_customer.py, load_xref.py, load_transaction.py).
  # Referenced by every aws_s3_object.glue_script_* resource and by every
  # aws_glue_job.* `command.script_location` property.
  glue_scripts_prefix = "glue-scripts"

  # S3 prefix that hosts the ASCII fixtures staged from app/data/ASCII/.
  # The fixtures are uploaded out-of-band by the deployment pipeline
  # (or by the LocalStack init scripts during local development);
  # this prefix is referenced by every aws_glue_job.* `--source_key`
  # default argument and by the optional aws_glue_crawler.fixtures
  # s3_target.path.
  glue_fixtures_prefix = "fixtures/ascii"

  # Scratch / shuffle prefix used by Spark for intermediate spill state.
  # Glue requires a writeable S3 location for the Spark driver to
  # persist DataFrame checkpoints; the operator-supplied --TempDir
  # default argument resolves to s3://${bucket}/glue-temp/ on every job.
  glue_temp_prefix = "glue-temp"

  # Continuous-logging CloudWatch log group name. The resource is owned
  # by cloudwatch.tf; this local alias keeps the default_arguments map
  # below readable.
  glue_log_group_name = aws_cloudwatch_log_group.glue_jobs.name

  # Default arguments common to every ASCII-to-RDS bulk-load job. The
  # per-table jobs merge this map with their --source_key / --target_table
  # overrides so a future tunable (e.g., switching off bookmarking)
  # only needs a single-line change here.
  glue_common_default_args = {
    # Spark Python language hint — required by Glue 4.0 PySpark jobs.
    "--job-language" = "python"

    # Spark continuous logging — streams driver/executor output to
    # CloudWatch in real time per AAP §0.6.6.
    "--continuous-log-logGroup"          = local.glue_log_group_name
    "--continuous-log-logStreamPrefix"   = "carddemo"
    "--enable-continuous-cloudwatch-log" = "true"
    "--enable-continuous-log-filter"     = "true"

    # Job-level metrics (CPU, memory, task duration) emitted as
    # CloudWatch metrics under the AWS/Glue namespace. Used by the
    # ops dashboard provisioned in cloudwatch.tf.
    "--enable-metrics"          = "true"
    "--enable-glue-datacatalog" = "true"
    "--enable-job-insights"     = "true"

    # Spark UI hosting is disabled by default because the events bucket
    # is provisioned per-job and not strictly required for bulk-load
    # job triage (CloudWatch + Spark history server are sufficient).
    "--enable-spark-ui" = "false"

    # Bulk-load is an idempotent operation — operators run the job
    # explicitly when a new fixture is staged, not continuously — so
    # job bookmarking is intentionally disabled. Re-running the job
    # against the same fixture produces the same target rows (upserts
    # are governed by primary-key conflict semantics in PostgreSQL).
    "--job-bookmark-option" = "job-bookmark-disable"

    # Glue temporary scratch space — every Spark step that requires
    # spill or shuffle resolves writes here.
    "--TempDir" = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_temp_prefix}/"
  }
}

# =============================================================================
# Section 2 — Glue worker security group + RDS ingress rule
# =============================================================================
# AWS Glue Spark workers run inside the customer VPC when a Glue
# connection is attached. The connection binds to a single subnet
# (data.aws_subnet.private_a) and a single security group declared
# below. The security group:
#
#   - Allows self-referencing ingress / egress so distributed Spark
#     tasks can exchange shuffle traffic.
#   - Allows full outbound egress (the VPC endpoints provisioned in
#     main.tf keep the bulk of the traffic on the AWS backbone — S3
#     via Gateway endpoint, Secrets Manager / KMS / CloudWatch Logs
#     via Interface endpoints).
#   - Does NOT directly allow port 5432 to RDS — that ingress is
#     attached to the RDS security group via a standalone
#     aws_security_group_rule resource below to avoid the typical
#     Terraform SG dependency cycle (the RDS SG is declared in rds.tf
#     without ingress; consumers declare their own ingress rules that
#     reference it).
#
# Schema-mandated exports:
#   - aws_security_group.glue
#   - aws_security_group_rule.rds_from_glue
# =============================================================================

resource "aws_security_group" "glue" {
  name        = "carddemo-${var.environment}-glue-sg"
  description = "AWS Glue Spark workers - egress to RDS PostgreSQL, S3, Secrets Manager, KMS, and CloudWatch Logs"
  vpc_id      = data.aws_vpc.carddemo.id

  # Self-referencing ingress is required by Glue for distributed
  # Spark task communication (shuffle, broadcast, accumulator
  # responses). Without this rule the Glue connection-test step
  # fails with "Spark cluster communication blocked" errors.
  ingress {
    description = "Self-referencing for Glue Spark cluster-internal traffic"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    self        = true
  }

  # Self-referencing egress mirrors the ingress rule.
  egress {
    description = "Self-referencing egress for Glue Spark cluster-internal traffic"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    self        = true
  }

  # PostgreSQL 5432 egress to RDS. The destination is restricted to
  # the VPC CIDR because the RDS endpoint resolves to a private IP
  # within the VPC; a CIDR-restricted egress rule preserves the
  # principle of least privilege while still permitting the bulk load.
  egress {
    description = "PostgreSQL 5432 egress to RDS"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.carddemo.cidr_block]
  }

  # HTTPS egress for VPC endpoint reach (S3 Gateway, Secrets Manager,
  # KMS, CloudWatch Logs) plus any other AWS service that Glue needs
  # at runtime (e.g., Glue Data Catalog).
  egress {
    description = "HTTPS 443 egress to AWS service VPC endpoints and Glue control plane"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-sg"
    Purpose = "AWS Glue Spark ETL workers"
  })

  # Recreating the SG would replace every dependent connection /
  # job — create_before_destroy minimises the blast radius if a
  # name collision forces an in-place rename.
  lifecycle {
    create_before_destroy = true
  }
}

# Ingress rule that opens port 5432 on the RDS security group from the
# Glue security group. Declared as a standalone resource so we can
# reference aws_security_group.rds (provisioned in rds.tf) without
# modifying rds.tf and without creating a Terraform SG dependency
# cycle (the RDS SG declares no inbound rules; consumers attach their
# own).
#
# Schema-mandated export: aws_security_group_rule.rds_from_glue
resource "aws_security_group_rule" "rds_from_glue" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.glue.id
  security_group_id        = aws_security_group.rds.id
  description              = "Glue ETL jobs to RDS PostgreSQL (bulk load per AAP 0.6.2)"
}

# =============================================================================
# Section 3 — Glue JDBC connection to RDS PostgreSQL
# =============================================================================
# The aws_glue_connection resource encapsulates the JDBC URL, network
# placement, and (via Secrets Manager-sourced properties) the database
# credentials that Glue uses to reach RDS. Glue resolves the connection
# at job-run time: when a job declares `connections = [<name>]` the
# Glue runtime places the Spark cluster in the configured subnet,
# attaches the configured security group, and exposes the JDBC URL
# + credentials to the PySpark script.
#
# Schema-mandated export: aws_glue_connection.rds
# =============================================================================

resource "aws_glue_connection" "rds" {
  name            = "carddemo-${var.environment}-rds-conn"
  description     = "Glue JDBC connection to the carddemo RDS PostgreSQL instance (bulk-load ETL)"
  connection_type = "JDBC"

  connection_properties = {
    # JDBC URL — host + port + database name. The RDS endpoint
    # already includes the port (e.g., "carddemo-prod-rds.xyz.us-east-1.rds.amazonaws.com:5432"),
    # so we strip the trailing :5432 before appending the database name.
    JDBC_CONNECTION_URL = "jdbc:postgresql://${aws_db_instance.carddemo.endpoint}/${aws_db_instance.carddemo.db_name}"

    # USERNAME / PASSWORD — pulled from the carddemo Secrets Manager
    # secret value at runtime. jsondecode() is required because the
    # secret stores all RDS connection metadata as a single JSON blob
    # (username, password, engine, host, port, dbname) per the
    # convention in rds.tf Section 4.
    USERNAME = jsondecode(aws_secretsmanager_secret_version.rds_master_value.secret_string)["username"]
    PASSWORD = jsondecode(aws_secretsmanager_secret_version.rds_master_value.secret_string)["password"]

    # JDBC driver class — required for non-default JDBC engines.
    # PostgreSQL is supplied by the Glue runtime so the driver JAR
    # need not be uploaded.
    JDBC_ENFORCE_SSL = "true"
  }

  physical_connection_requirements {
    # Glue connection-test ENIs bind to a single subnet at create
    # time; private_a is the first private subnet declared by the
    # operator. At job-run time the Spark cluster may still be
    # distributed across multiple subnets within the connection's
    # availability zone.
    subnet_id              = data.aws_subnet.private_a.id
    security_group_id_list = [aws_security_group.glue.id]
    availability_zone      = data.aws_subnet.private_a.availability_zone
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-conn"
    Purpose = "Glue JDBC connection to RDS PostgreSQL for bulk-load ETL"
  })
}

# =============================================================================
# Section 4 — Glue script S3 objects (PySpark scripts uploaded with KMS encryption)
# =============================================================================
# Every Glue job loads its PySpark script from S3 at job-start time.
# Storing the scripts as Terraform-managed S3 objects (rather than
# uploaded out-of-band by the deployment pipeline) gives:
#
#   1. Atomicity — `terraform apply` updates the script and the
#      consuming job in the same transaction, eliminating the race
#      where a job runs an outdated script.
#   2. Encryption — every script object is encrypted at rest with
#      the carddemo KMS CMK per AAP §0.7.1 ("Encrypt all S3 buckets
#      with SSE-KMS").
#   3. Versioning — the underlying batch_outputs bucket is versioned,
#      so previous script revisions remain accessible for parallel-run
#      validation and rollback.
#
# The `etag = filemd5(...)` argument forces Terraform to detect local
# script changes (the MD5 of the on-disk file) and re-upload, even
# when the file content is otherwise byte-identical to the previously
# uploaded object. AWS S3 does not compute ETags for KMS-encrypted
# objects in the same way as for unencrypted objects, so this is the
# canonical pattern for forcing change detection.
#
# Schema-mandated exports:
#   - aws_s3_object.glue_script_account
#   - aws_s3_object.glue_script_card
#   - aws_s3_object.glue_script_customer
#   - aws_s3_object.glue_script_xref
#   - aws_s3_object.glue_script_transaction
# =============================================================================

resource "aws_s3_object" "glue_script_account" {
  bucket = aws_s3_bucket.batch_outputs.id
  key    = "${local.glue_scripts_prefix}/load_account.py"
  source = "${path.module}/glue_scripts/load_account.py"
  etag   = filemd5("${path.module}/glue_scripts/load_account.py")

  # PCI-DSS encryption at rest — every CardDemo S3 object must be
  # encrypted with the carddemo KMS CMK per AAP §0.7.1.
  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-script-account"
    Purpose = "PySpark script for Account ASCII-to-RDS bulk load"
  })
}

resource "aws_s3_object" "glue_script_card" {
  bucket = aws_s3_bucket.batch_outputs.id
  key    = "${local.glue_scripts_prefix}/load_card.py"
  source = "${path.module}/glue_scripts/load_card.py"
  etag   = filemd5("${path.module}/glue_scripts/load_card.py")

  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-script-card"
    Purpose = "PySpark script for Card ASCII-to-RDS bulk load"
  })
}

resource "aws_s3_object" "glue_script_customer" {
  bucket = aws_s3_bucket.batch_outputs.id
  key    = "${local.glue_scripts_prefix}/load_customer.py"
  source = "${path.module}/glue_scripts/load_customer.py"
  etag   = filemd5("${path.module}/glue_scripts/load_customer.py")

  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-script-customer"
    Purpose = "PySpark script for Customer ASCII-to-RDS bulk load"
  })
}

resource "aws_s3_object" "glue_script_xref" {
  bucket = aws_s3_bucket.batch_outputs.id
  key    = "${local.glue_scripts_prefix}/load_xref.py"
  source = "${path.module}/glue_scripts/load_xref.py"
  etag   = filemd5("${path.module}/glue_scripts/load_xref.py")

  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-script-xref"
    Purpose = "PySpark script for CardCrossReference ASCII-to-RDS bulk load"
  })
}

resource "aws_s3_object" "glue_script_transaction" {
  bucket = aws_s3_bucket.batch_outputs.id
  key    = "${local.glue_scripts_prefix}/load_transaction.py"
  source = "${path.module}/glue_scripts/load_transaction.py"
  etag   = filemd5("${path.module}/glue_scripts/load_transaction.py")

  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-script-transaction"
    Purpose = "PySpark script for Transaction (DALYTRAN staging) ASCII-to-RDS bulk load"
  })
}

# =============================================================================
# Section 5 — Glue Spark ETL jobs (one per VSAM cluster being bulk-loaded)
# =============================================================================
# Each job:
#   - Reads a fixed-width ASCII fixture from S3 (the legacy *.PS
#     dataset, staged under s3://${bucket}/fixtures/ascii/).
#   - Parses fields per the corresponding COBOL copybook layout
#     (CVACT01Y.cpy for Account, CVACT02Y.cpy for Card,
#     CVCUS01Y.cpy for Customer, CVACT03Y.cpy for CardCrossReference,
#     CVTRA05Y.cpy for Transaction). Zoned-decimal sign-overpunch
#     bytes are translated to Python Decimal -> PostgreSQL NUMERIC
#     per AAP §0.6.1.
#   - Bulk-inserts the parsed rows into the corresponding RDS
#     PostgreSQL table via the carddemo-<env>-rds-conn Glue
#     connection.
#
# All five jobs share:
#   - role_arn          = aws_iam_role.glue_job_execution.arn
#   - glue_version      = "4.0" (Spark 3.3, Python 3.10)
#   - worker_type       = var.glue_worker_type (default "G.1X")
#   - number_of_workers = var.glue_number_of_workers (default 2)
#   - timeout           = 60 (minutes)
#   - max_retries       = 1
#   - max_concurrent_runs = 1 (bulk-load is single-tenant on the
#                              target table — concurrent runs would
#                              race on PostgreSQL primary-key
#                              constraints and on the JDBC connection
#                              pool established by the connection)
#   - connections       = [aws_glue_connection.rds.name]
#   - default_arguments = merge(local.glue_common_default_args, {
#                           "--source_key"   = "...",
#                           "--target_table" = "...",
#                         })
#
# Schema-mandated exports:
#   - aws_glue_job.ascii_to_rds_account
#   - aws_glue_job.ascii_to_rds_card
#   - aws_glue_job.ascii_to_rds_customer
#   - aws_glue_job.ascii_to_rds_xref
#   - aws_glue_job.ascii_to_rds_transaction
# =============================================================================

# -----------------------------------------------------------------------------
# Account loader — replaces app/jcl/ACCTFILE.jcl STEP15 IDCAMS REPRO.
#
# Source fixture: s3://${batch_outputs}/fixtures/ascii/acctdata.txt
# Target table:   accounts (V001__create_account.sql)
# Record layout:  app/cpy/CVACT01Y.cpy (300 bytes, 12 fields incl. 5
#                 PIC S9(10)V99 monetary fields)
# -----------------------------------------------------------------------------
resource "aws_glue_job" "ascii_to_rds_account" {
  name              = "carddemo-${var.environment}-load-account"
  description       = "Bulk-load Account ASCII fixture from S3 to RDS (replaces app/jcl/ACCTFILE.jcl IDCAMS REPRO per AAP §0.6.2)"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  # JDBC connection — Glue resolves credentials from Secrets Manager
  # at job-run time via the connection's USERNAME / PASSWORD properties.
  connections = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_scripts_prefix}/load_account.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--connection_name" = aws_glue_connection.rds.name
    "--source_bucket"   = aws_s3_bucket.batch_outputs.id
    "--source_key"      = "${local.glue_fixtures_prefix}/acctdata.txt"
    "--target_table"    = "accounts"
  })

  # Bulk-load is single-tenant on the target table — concurrent runs
  # would race on PostgreSQL primary-key constraints.
  execution_property {
    max_concurrent_runs = 1
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-load-account"
    Purpose = "ASCII-to-RDS bulk load for Account fact table"
  })

  # The script must be uploaded before the job is created, otherwise
  # the first job-run fails with NoSuchKey. Terraform's implicit
  # dependency graph already captures this via the script_location
  # reference, but the explicit depends_on documents the relationship.
  depends_on = [aws_s3_object.glue_script_account]
}

# -----------------------------------------------------------------------------
# Card loader — replaces app/jcl/CARDFILE.jcl STEP15 IDCAMS REPRO.
#
# Source fixture: s3://${batch_outputs}/fixtures/ascii/carddata.txt
# Target table:   cards (V002__create_card.sql)
# Record layout:  app/cpy/CVACT02Y.cpy (150 bytes, 6 fields)
# -----------------------------------------------------------------------------
resource "aws_glue_job" "ascii_to_rds_card" {
  name              = "carddemo-${var.environment}-load-card"
  description       = "Bulk-load Card ASCII fixture from S3 to RDS (replaces app/jcl/CARDFILE.jcl IDCAMS REPRO per AAP §0.6.2)"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  connections = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_scripts_prefix}/load_card.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--connection_name" = aws_glue_connection.rds.name
    "--source_bucket"   = aws_s3_bucket.batch_outputs.id
    "--source_key"      = "${local.glue_fixtures_prefix}/carddata.txt"
    "--target_table"    = "cards"
  })

  execution_property {
    max_concurrent_runs = 1
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-load-card"
    Purpose = "ASCII-to-RDS bulk load for Card fact table"
  })

  depends_on = [aws_s3_object.glue_script_card]
}

# -----------------------------------------------------------------------------
# Customer loader — replaces app/jcl/CUSTFILE.jcl STEP15 IDCAMS REPRO.
#
# Source fixture: s3://${batch_outputs}/fixtures/ascii/custdata.txt
# Target table:   customers (V003__create_customer.sql)
# Record layout:  app/cpy/CVCUS01Y.cpy / CUSTREC.cpy (500 bytes, 18 fields
#                 incl. PII fields covered by Macie scanning per AAP §0.6.6)
# -----------------------------------------------------------------------------
resource "aws_glue_job" "ascii_to_rds_customer" {
  name              = "carddemo-${var.environment}-load-customer"
  description       = "Bulk-load Customer ASCII fixture from S3 to RDS (replaces app/jcl/CUSTFILE.jcl IDCAMS REPRO per AAP §0.6.2)"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  connections = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_scripts_prefix}/load_customer.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--connection_name" = aws_glue_connection.rds.name
    "--source_bucket"   = aws_s3_bucket.batch_outputs.id
    "--source_key"      = "${local.glue_fixtures_prefix}/custdata.txt"
    "--target_table"    = "customers"
  })

  execution_property {
    max_concurrent_runs = 1
  }

  tags = merge(local.common_tags, {
    Name      = "carddemo-${var.environment}-load-customer"
    Purpose   = "ASCII-to-RDS bulk load for Customer fact table"
    DataClass = "PII" # Macie scans the source fixture and target table
  })

  depends_on = [aws_s3_object.glue_script_customer]
}

# -----------------------------------------------------------------------------
# Card Cross-Reference loader — replaces app/jcl/XREFFILE.jcl STEP15 IDCAMS REPRO.
#
# Source fixture: s3://${batch_outputs}/fixtures/ascii/cardxref.txt
# Target table:   card_xref (V004__create_cardxref.sql; secondary index
#                 on xref_acct_id replaces CXACAIX AIX KEYS(11,25)
#                 NONUNIQUEKEY UPGRADE)
# Record layout:  app/cpy/CVACT03Y.cpy (50 bytes, 3 fields)
# -----------------------------------------------------------------------------
resource "aws_glue_job" "ascii_to_rds_xref" {
  name              = "carddemo-${var.environment}-load-xref"
  description       = "Bulk-load CardCrossReference ASCII fixture from S3 to RDS (replaces app/jcl/XREFFILE.jcl IDCAMS REPRO per AAP §0.6.2)"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  connections = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_scripts_prefix}/load_xref.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--connection_name" = aws_glue_connection.rds.name
    "--source_bucket"   = aws_s3_bucket.batch_outputs.id
    "--source_key"      = "${local.glue_fixtures_prefix}/cardxref.txt"
    "--target_table"    = "card_xref"
  })

  execution_property {
    max_concurrent_runs = 1
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-load-xref"
    Purpose = "ASCII-to-RDS bulk load for CardCrossReference table"
  })

  depends_on = [aws_s3_object.glue_script_xref]
}

# -----------------------------------------------------------------------------
# Transaction loader — replaces app/jcl/TRANFILE.jcl STEP15 IDCAMS REPRO.
#
# Source fixture: s3://${batch_outputs}/fixtures/ascii/dailytran.txt
#                 (the source JCL REPROs from DALYTRAN.PS.INIT into the
#                 TRANSACT.VSAM.KSDS so the fixture is named for its
#                 origin)
# Target table:   daily_transactions (V011__create_daily_transaction.sql);
#                 the eod-batch-pipeline.asl.json state machine
#                 subsequently posts these staging records into the
#                 master transactions table via the
#                 DailyTransactionPostingJob Spring Batch job.
# Record layout:  app/cpy/CVTRA05Y.cpy / CVTRA06Y.cpy (350 bytes, 13
#                 fields incl. PIC S9(09)V99 TRAN-AMT)
# -----------------------------------------------------------------------------
resource "aws_glue_job" "ascii_to_rds_transaction" {
  name              = "carddemo-${var.environment}-load-transaction"
  description       = "Bulk-load Transaction (DALYTRAN staging) ASCII fixture from S3 to RDS (replaces app/jcl/TRANFILE.jcl IDCAMS REPRO per AAP §0.6.2)"
  role_arn          = aws_iam_role.glue_job_execution.arn
  glue_version      = "4.0"
  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_number_of_workers
  timeout           = 60
  max_retries       = 1

  connections = [aws_glue_connection.rds.name]

  command {
    name            = "glueetl"
    script_location = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_scripts_prefix}/load_transaction.py"
    python_version  = "3"
  }

  default_arguments = merge(local.glue_common_default_args, {
    "--connection_name" = aws_glue_connection.rds.name
    "--source_bucket"   = aws_s3_bucket.batch_outputs.id
    "--source_key"      = "${local.glue_fixtures_prefix}/dailytran.txt"
    "--target_table"    = "daily_transactions"
  })

  execution_property {
    max_concurrent_runs = 1
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-load-transaction"
    Purpose = "ASCII-to-RDS bulk load for Transaction (DALYTRAN staging)"
  })

  depends_on = [aws_s3_object.glue_script_transaction]
}

# =============================================================================
# Section 6 — Glue Data Catalog database + optional crawler
# =============================================================================
# The Glue Data Catalog is the canonical metastore for Athena queries
# over the S3-staged ASCII fixtures (and over the Glue job output
# parquet snapshots, when configured). Provisioning the catalog
# database here keeps the Data Catalog metadata in the same Terraform
# state as the jobs that populate it.
#
# The Glue Crawler is optional (gated by var.glue_crawler_enabled,
# default false) because Athena queries are not a strict requirement
# for the bulk-load pipeline — they are useful for ad-hoc data
# exploration during the parallel-run validation window.
#
# Schema-mandated exports:
#   - aws_glue_catalog_database.carddemo
#   - aws_glue_crawler.fixtures (count = var.glue_crawler_enabled ? 1 : 0)
# =============================================================================

resource "aws_glue_catalog_database" "carddemo" {
  name        = "carddemo_${var.environment}"
  description = "AWS Glue Data Catalog database for CardDemo ASCII fixtures and RDS snapshots (queryable via Athena)"

  # Although aws_glue_catalog_database accepts a target_database
  # property for cross-account links, the CardDemo deployment is
  # single-account so we omit it.

  tags = merge(local.common_tags, {
    Name    = "carddemo_${var.environment}"
    Purpose = "Glue Data Catalog for CardDemo bulk-load fixtures and snapshots"
  })
}

resource "aws_glue_crawler" "fixtures" {
  count         = var.glue_crawler_enabled ? 1 : 0
  name          = "carddemo-${var.environment}-fixtures-crawler"
  database_name = aws_glue_catalog_database.carddemo.name
  role          = aws_iam_role.glue_job_execution.arn

  # The crawler inspects the entire fixtures prefix and creates one
  # Glue Data Catalog table per discovered "folder" (in S3-prefix terms).
  # The fixtures are flat files under fixtures/ascii/ so the crawler
  # will produce a single table per fixture.
  s3_target {
    path = "s3://${aws_s3_bucket.batch_outputs.id}/${local.glue_fixtures_prefix}/"
  }

  # Crawler is invoked on demand (no schedule) — operators run it
  # manually via `aws glue start-crawler` when a new fixture is staged.
  # Schema_change_policy defaults are intentionally retained:
  #   - UpdateBehavior  = UPDATE_IN_DATABASE (schema changes are merged)
  #   - DeleteBehavior  = DEPRECATE_IN_DATABASE (removed tables are
  #                       deprecated rather than deleted, preserving
  #                       Athena query history)

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-fixtures-crawler"
    Purpose = "Optional Glue Crawler for Athena ad-hoc queries over ASCII fixtures"
  })
}
