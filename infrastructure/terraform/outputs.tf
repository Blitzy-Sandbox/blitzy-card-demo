###############################################################################
# infrastructure/terraform/outputs.tf
#
# Purpose
# -------
# Terraform outputs file — the PUBLIC CONTRACT of the CardDemo infrastructure
# module. Every value below is consumed by at least one downstream artefact:
#
#   1. ECS Fargate task-definition environment variables (per AAP §0.7.2 the
#      mandatory list is AWS_REGION, ECS_CLUSTER_NAME, RDS_SECRET_ARN,
#      KMS_KEY_ARN, MSK_BOOTSTRAP_SERVERS, S3_OUTPUT_BUCKET,
#      OPENSEARCH_ENDPOINT). The `ecs_task_environment` map output at the
#      bottom of this file bundles the full env-var set for direct injection
#      by the ECS task definition rendered in ecs.tf.
#
#   2. Spring Boot application configuration — `src/main/resources/
#      application-prod.yml` and `application-dev.yml` reference these as
#      `${VAR}` placeholders (RDS_HOST, RDS_PORT, RDS_DATABASE,
#      ELASTICACHE_HOST, ELASTICACHE_PORT, EOD_STATE_MACHINE_ARN,
#      PROV_STATE_MACHINE_ARN, CLOUDTRAIL_TRAIL_ARN, etc.).
#
#   3. GitHub Actions CI/CD workflows — `.github/workflows/docker-build.yml`
#      reads `ecr_repository_url` to push images; `.github/workflows/
#      deploy.yml` reads `ecs_cluster_name`, `ecs_service_name`, and
#      `ecs_task_definition_family` to render new task-definition revisions.
#
#   4. Operators running `terraform output -json > outputs.json` to verify
#      the deployed environment after `terraform apply`.
#
# Security
# --------
# This file emits ARNs and DNS endpoints only. It NEVER exposes the resolved
# value of any secret. Spring Boot resolves Secrets Manager values at startup
# via Spring Cloud AWS (`spring.config.import=aws-secretsmanager:...`) per
# AAP §0.6.4 — only the secret ARN passes through this file. Sensitive
# outputs are accordingly marked `sensitive = false` (i.e. ARN is safe to
# print) and the absence of any secret-value output is intentional.
#
# MSK authentication design note (AAP §0.6.5)
# -------------------------------------------
# Amazon MSK clusters in this module use IAM authentication via SASL_SSL
# (`bootstrap_brokers_sasl_iam` on `aws_msk_cluster.carddemo`). There is
# DELIBERATELY NO `msk_credentials_secret_arn` output because no SCRAM
# secret exists — IAM auth is configured at the producer / consumer level
# via AWS SDK credential chain. See msk.tf for the cluster client_authentication
# block. The `aws-msk-iam-auth` library handles SASL/IAM handshake at the
# Spring Kafka producer / consumer factories (KafkaConfig.java).
#
# OpenSearch master credential — naming reconciliation
# -----------------------------------------------------
# The output `opensearch_credentials_secret_arn` references the underlying
# resource `aws_secretsmanager_secret.opensearch_master` (secrets.tf line
# 199). The output name uses the historical `credentials` term for consumer
# compatibility while the resource name reflects the AWS Secrets Manager
# convention of `<service>_master` for the master-user credential.
#
# AAP references
# --------------
#   * §0.3.4 — REST API endpoints exposed via ALB (alb_dns_name).
#   * §0.4.1 — Infrastructure file inventory; this file is a CREATE target.
#   * §0.6.4 — Dynamic Secrets Manager rotation without Spring Boot restart;
#              jwt_signing_key_secret_arn and opensearch_credentials_secret_arn
#              are imported by Spring at startup AND re-imported on rotation.
#   * §0.6.5 — MSK topic ordering; msk_topics map documents the four topic
#              names (transaction.posted, account.updated, ledger.balanced,
#              report.requested) configured in msk.tf.
#   * §0.7.2 — Mandatory environment variable list (verbatim from user
#              prompt). Every variable in the list maps to an output here
#              AND is included in the `ecs_task_environment` bundle.
#
# Resource-attribute accuracy
# ---------------------------
# Every `value` expression below references an `aws_*.<name>.<attribute>`
# resource declared in one of the sibling .tf files (verified in Phase 1
# of the agent's discovery cycle). No string literals are used except for:
#   * the `s3_tfstate_bucket` naming convention (matches the backend
#     bootstrap procedure in main.tf);
#   * the four MSK topic-name constants in `msk_topics` (these mirror the
#     `kafka_topic.<name>.name` attributes defined in msk.tf but are
#     also referenced as compile-time constants by Spring `@KafkaListener`
#     annotations, so the literal form is the source of truth);
#   * the `https://` scheme prefix on OpenSearch endpoints (required so
#     the Spring `RestClient` can construct a valid URL without further
#     transformation).
###############################################################################


# =============================================================================
# Section 1 — AWS region and account context
# =============================================================================
# Foundation outputs consumed by:
#   * AWS_REGION env var (mandatory per AAP §0.7.2). Source: var.aws_region
#     from variables.tf (default "us-east-1", validated by region regex).
#   * Debugging / operator tooling — aws_account_id surfaces the executing
#     account so operators can confirm they applied to the right account.
# =============================================================================

output "aws_region" {
  description = "AWS region this deployment targets (consumed by AWS_REGION env var per AAP §0.7.2 mandatory env var list)."
  value       = var.aws_region
}

output "aws_account_id" {
  description = "AWS account ID hosting this deployment (data.aws_caller_identity.current.account_id from main.tf). Used by operators for sanity checks and by ARN constructions in downstream modules."
  value       = data.aws_caller_identity.current.account_id
}


# =============================================================================
# Section 2 — KMS outputs (from kms.tf)
# =============================================================================
# Customer-managed KMS Customer Master Key (CMK) — used for envelope
# encryption across every CardDemo data store per AAP §0.7.1:
#   * RDS PostgreSQL data at rest
#   * S3 batch-outputs and CloudTrail-logs buckets
#   * ElastiCache Redis encryption at rest
#   * CloudWatch Logs encryption
#   * MSK Kafka data-at-rest encryption
#   * Secrets Manager envelope keys
#
# The KMS_KEY_ARN env var (mandatory per AAP §0.7.2) is sourced from
# `kms_key_arn` below.
# =============================================================================

output "kms_key_arn" {
  description = "Primary CardDemo KMS CMK ARN (consumed by KMS_KEY_ARN env var per AAP §0.7.2 mandatory env var list). Used for envelope encryption across RDS, S3, ElastiCache, CloudWatch Logs, MSK, Secrets Manager."
  value       = aws_kms_key.carddemo.arn
}

output "kms_key_id" {
  description = "Primary CardDemo KMS CMK ID (UUID form). Used by AWS SDK clients that require key-ID rather than ARN (e.g., kms:GenerateDataKey API)."
  value       = aws_kms_key.carddemo.key_id
}

output "kms_alias_arn" {
  description = "Alias ARN for the primary CardDemo KMS CMK (alias/carddemo-<env>). Provides a stable reference that survives key rotation."
  value       = aws_kms_alias.carddemo.arn
}


# =============================================================================
# Section 3 — RDS PostgreSQL outputs (from rds.tf)
# =============================================================================
# RDS Multi-AZ PostgreSQL replaces the source VSAM KSDS clusters per AAP
# §0.6.2. The Spring Boot DataSource bean (JpaConfig) reads:
#   * RDS_HOST  — JDBC connection host (rds_host)
#   * RDS_PORT  — JDBC connection port (rds_port)
#   * RDS_DATABASE — initial database name (rds_database)
# The master credential is rotated via Secrets Manager + Spring Cloud AWS
# (@RefreshScope on the HikariDataSource per AAP §0.6.4); the secret ARN
# is the RDS_SECRET_ARN env var.
# =============================================================================

output "rds_host" {
  description = "RDS PostgreSQL endpoint hostname (consumed by RDS_HOST env var in application-prod.yml / application-dev.yml). JDBC url: jdbc:postgresql://$${rds_host}:$${rds_port}/$${rds_database}."
  value       = aws_db_instance.carddemo.address
}

output "rds_port" {
  description = "RDS PostgreSQL listener port (consumed by RDS_PORT env var). Typically 5432."
  value       = aws_db_instance.carddemo.port
}

output "rds_database" {
  description = "RDS PostgreSQL initial database name (consumed by RDS_DATABASE env var; defined by var.rds_db_name)."
  value       = aws_db_instance.carddemo.db_name
}

output "rds_endpoint" {
  description = "Full RDS endpoint in host:port form. Used by Spring Boot HikariCP JDBC URL construction when the application prefers a single property over host + port separation."
  value       = aws_db_instance.carddemo.endpoint
}

output "rds_secret_arn" {
  description = "Secrets Manager secret ARN holding the RDS master username / password JSON (consumed by RDS_SECRET_ARN env var per AAP §0.7.2 mandatory env var list and by spring.config.import=aws-secretsmanager:$${rds_secret_arn}). Rotated automatically via Secrets Manager + RDS rotation Lambda; Spring picks up the rotated credential via @RefreshScope on the HikariDataSource bean."
  value       = aws_secretsmanager_secret.rds_master.arn
}

output "rds_resource_id" {
  description = "RDS resource ID (DbiResourceId) — required by IAM database-authentication policies and CloudWatch enhanced-monitoring metric filters."
  value       = aws_db_instance.carddemo.resource_id
}


# =============================================================================
# Section 4 — ElastiCache (Redis) outputs (from elasticache.tf)
# =============================================================================
# ElastiCache Redis backs the cache-aside pattern for high-frequency account
# balance lookups per AAP §0.7.1 ("ElastiCache (Redis) used for account
# balance caching — cache-aside pattern with TTL aligned to transaction
# frequency"). Spring Boot LettuceConnectionFactory (RedisConfig) reads:
#   * ELASTICACHE_HOST — primary endpoint (writes)
#   * ELASTICACHE_PORT — listener port
#   * elasticache_reader_endpoint — reader endpoint (reads, optional)
# =============================================================================

output "elasticache_host" {
  description = "ElastiCache Redis primary endpoint hostname (consumed by ELASTICACHE_HOST env var in application-prod.yml). Use for write operations and read operations that require strong consistency."
  value       = aws_elasticache_replication_group.carddemo.primary_endpoint_address
}

output "elasticache_port" {
  description = "ElastiCache Redis listener port (consumed by ELASTICACHE_PORT env var). Typically 6379."
  value       = aws_elasticache_replication_group.carddemo.port
}

output "elasticache_reader_endpoint" {
  description = "ElastiCache Redis reader endpoint hostname. Used by Lettuce ReadFrom.REPLICA_PREFERRED to distribute read traffic across replicas."
  value       = aws_elasticache_replication_group.carddemo.reader_endpoint_address
}


# =============================================================================
# Section 5 — MSK (Kafka) outputs (from msk.tf)
# =============================================================================
# Amazon MSK Kafka cluster — backbone of the event-driven transaction
# pipeline per AAP §0.6.5. Bootstrap brokers use SASL_SSL + IAM auth (the
# `bootstrap_brokers_sasl_iam` attribute) so no SCRAM secret is needed.
# Spring Boot KafkaProducerFactory / KafkaConsumerFactory (KafkaConfig) reads:
#   * MSK_BOOTSTRAP_SERVERS env var — bootstrap.servers property
#
# Topic ordering invariant per AAP §0.6.5:
#   * Partition key = account ID (zero-padded 11-digit string).
#   * All events for a given account land on a single partition.
#   * Producer acks=all, enable.idempotence=true.
#   * Consumer enable.auto.commit=false, manual offset commit after success.
# =============================================================================

output "msk_bootstrap_servers" {
  description = "MSK cluster bootstrap brokers using SASL_SSL + IAM authentication (consumed by MSK_BOOTSTRAP_SERVERS env var per AAP §0.7.2 mandatory env var list). Format: broker1:9098,broker2:9098,broker3:9098. The aws-msk-iam-auth library handles the SASL/IAM handshake; no SCRAM secret is required."
  value       = aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam
}

output "msk_cluster_arn" {
  description = "MSK cluster ARN — referenced by IAM policies that grant `kafka-cluster:*` actions to the ECS task role and Batch job role (see iam.tf)."
  value       = aws_msk_cluster.carddemo.arn
}

output "msk_topics" {
  description = "Map of MSK Kafka topic names provisioned in msk.tf — used by the Spring Boot KafkaEventPublisher / KafkaEventConsumer adapter classes per AAP §0.6.5. Topic names are stable contract; partition key is account ID for per-account ordering."
  value = {
    transaction_posted = "transaction.posted"
    account_updated    = "account.updated"
    ledger_balanced    = "ledger.balanced"
    report_requested   = "report.requested"
  }
}


# =============================================================================
# Section 6 — S3 outputs (from s3.tf and cloudtrail.tf)
# =============================================================================
# S3 buckets per AAP §0.7.1 (SSE-KMS, block-public-access, HTTPS-only):
#   * batch_outputs — DALYREJS, SYSTRAN, TRANREPT, STMTFILE outputs (replaces
#     the source GDG generation sequences ((+1), (0)) with S3 object
#     versioning + lifecycle policies per AAP §0.6.2).
#   * cloudtrail_logs — Immutable, tamper-evident audit log per AAP §0.6.6.
#   * tfstate (out-of-band) — naming-convention reference only; provisioned
#     by the operator pre-bootstrap (see main.tf backend block).
# =============================================================================

output "s3_output_bucket" {
  description = "Primary S3 bucket for Spring Batch outputs (consumed by S3_OUTPUT_BUCKET env var per AAP §0.7.2 mandatory env var list). Holds DALYREJS rejection feeds, SYSTRAN interest-calc transaction feeds, TRANREPT transaction reports, STMTFILE statements. Versioned with SSE-KMS and lifecycle policies (Standard → Glacier)."
  value       = aws_s3_bucket.batch_outputs.bucket
}

output "s3_output_bucket_arn" {
  description = "S3 batch outputs bucket ARN — used by IAM policies (iam.tf) that grant the ECS task role and Batch job role `s3:PutObject` / `s3:GetObject` on this bucket only."
  value       = aws_s3_bucket.batch_outputs.arn
}

output "s3_cloudtrail_bucket" {
  description = "S3 bucket holding CloudTrail logs (separate from batch_outputs to enforce least-privilege access). KMS-encrypted, versioning enabled, log-file integrity validation enabled."
  value       = aws_s3_bucket.cloudtrail_logs.bucket
}

output "s3_tfstate_bucket" {
  description = "S3 bucket holding Terraform remote state (informational only — out-of-band bootstrap). Naming convention: carddemo-tfstate-<account_id>-<region>. Referenced by the main.tf backend block at terraform init via -backend-config=bucket=..."
  value       = "carddemo-tfstate-${data.aws_caller_identity.current.account_id}-${var.aws_region}"
}


# =============================================================================
# Section 7 — ECS Fargate outputs (from ecs.tf and iam.tf)
# =============================================================================
# ECS Fargate cluster + service + task definition per AAP §0.3.1. The
# GitHub Actions deploy workflow (.github/workflows/deploy.yml) reads:
#   * ecs_cluster_name           — `aws ecs update-service --cluster ...`
#   * ecs_service_name           — `aws ecs update-service --service ...`
#   * ecs_task_definition_family — `aws ecs register-task-definition --family ...`
# The Spring Boot task definition references:
#   * ecs_task_execution_role_arn — pulls images from ECR, fetches secrets
#   * ecs_task_role_arn           — application-runtime IAM identity
# =============================================================================

output "ecs_cluster_name" {
  description = "ECS Fargate cluster name (consumed by ECS_CLUSTER_NAME env var per AAP §0.7.2 mandatory env var list and by .github/workflows/deploy.yml `aws ecs update-service --cluster ...`)."
  value       = aws_ecs_cluster.carddemo.name
}

output "ecs_cluster_arn" {
  description = "ECS Fargate cluster ARN — used by IAM policies, Service Connect configuration, and EventBridge rules that scope to this cluster."
  value       = aws_ecs_cluster.carddemo.arn
}

output "ecs_service_name" {
  description = "ECS service name (consumed by .github/workflows/deploy.yml for `aws ecs update-service --service ...` and by Application Auto Scaling target ARN construction)."
  value       = aws_ecs_service.carddemo.name
}

output "ecs_task_definition_family" {
  description = "ECS task definition family name (consumed by .github/workflows/deploy.yml for `aws ecs register-task-definition --family ...` when rolling forward a new image revision)."
  value       = aws_ecs_task_definition.carddemo.family
}

output "ecs_task_execution_role_arn" {
  description = "IAM role ARN assumed by the ECS agent (Fargate platform) to pull the Spring Boot image from ECR and to inject secrets from Secrets Manager + Parameter Store into the container environment. See iam.tf for the role and managed-policy attachments."
  value       = aws_iam_role.ecs_task_execution.arn
}

output "ecs_task_role_arn" {
  description = "IAM role ARN assumed by the Spring Boot application code at runtime — grants kafka-cluster:*, s3:PutObject/GetObject, kms:Decrypt, secretsmanager:GetSecretValue, sfn:StartExecution, etc. NOTE: the underlying resource is named `aws_iam_role.ecs_task_role` in iam.tf (not `aws_iam_role.ecs_task`)."
  value       = aws_iam_role.ecs_task_role.arn
}


# =============================================================================
# Section 8 — ALB outputs (from alb.tf)
# =============================================================================
# Application Load Balancer — public-facing HTTPS endpoint for the REST API
# per AAP §0.3.4. Outputs feed:
#   * alb_dns_name  — Route53 Alias target (CNAME-equivalent) for the
#                     CardDemo public domain (e.g., api.carddemo.example.com).
#   * alb_zone_id   — required to construct the Route53 alias record.
#   * alb_arn       — used by WAF Web ACL association (waf.tf) and Shield
#                     Advanced subscription (shield.tf) per AAP §0.6.6.
# =============================================================================

output "alb_dns_name" {
  description = "Application Load Balancer DNS name (public endpoint for the REST API). Source for the Route53 Alias record that maps the CardDemo public-facing domain to the ALB."
  value       = aws_lb.carddemo.dns_name
}

output "alb_zone_id" {
  description = "Application Load Balancer hosted zone ID. Required by Route53 Alias records (aws_route53_record.alias.zone_id) to point a DNS name at the ALB."
  value       = aws_lb.carddemo.zone_id
}

output "alb_arn" {
  description = "Application Load Balancer ARN. Consumed by waf.tf for the `aws_wafv2_web_acl_association` resource and by shield.tf for the `aws_shield_protection` resource per AAP §0.6.6."
  value       = aws_lb.carddemo.arn
}


# =============================================================================
# Section 9 — ECR outputs (from ecr.tf)
# =============================================================================
# Elastic Container Registry — where the Spring Boot Docker image is pushed
# by CI/CD before the ECS deploy step.
# =============================================================================

output "ecr_repository_url" {
  description = "ECR repository URL where the Spring Boot Docker image is pushed (consumed by .github/workflows/docker-build.yml as `docker tag $IMAGE_URI $ECR_URL:$TAG && docker push $ECR_URL:$TAG`). Format: <account>.dkr.ecr.<region>.amazonaws.com/<repo>."
  value       = aws_ecr_repository.carddemo.repository_url
}

output "ecr_repository_arn" {
  description = "ECR repository ARN — used by IAM policies that grant `ecr:GetDownloadUrlForLayer`, `ecr:BatchGetImage`, `ecr:BatchCheckLayerAvailability` to the ECS task execution role and the GitHub Actions deploy role."
  value       = aws_ecr_repository.carddemo.arn
}


# =============================================================================
# Section 10 — Step Functions outputs (from stepfunctions.tf)
# =============================================================================
# AWS Step Functions state machines per AAP §0.6.3 — replace the JCL job
# stream sequencing. The Spring Boot StepFunctionsOrchestrator adapter
# starts executions via the AWS SDK v2 SfnClient.
#
# eod_batch_pipeline — POSTTRAN → INTCALC → COMBTRAN → Parallel{CREASTMT,
#                       TRANREPT}, replacing the EOD batch chain documented
#                       in app/jcl/POSTTRAN.jcl + INTCALC.jcl + COMBTRAN.jcl
#                       + CREASTMT.JCL + TRANREPT.jcl.
# file_provisioning   — Glue ETL + Flyway migrations for ASCII → RDS
#                       bulk loads (replaces app/jcl/ACCTFILE.jcl + CARDFILE.jcl
#                       + CUSTFILE.jcl + XREFFILE.jcl + TRANFILE.jcl + etc.).
# =============================================================================

output "eod_state_machine_arn" {
  description = "Step Functions ARN for the end-of-day batch pipeline (consumed by EOD_STATE_MACHINE_ARN env var in application-prod.yml). State machine: POSTTRAN → INTCALC → COMBTRAN → Parallel{CREASTMT, TRANREPT}. Replaces app/jcl/POSTTRAN.jcl + INTCALC.jcl + COMBTRAN.jcl + CREASTMT.JCL + TRANREPT.jcl per AAP §0.6.3."
  value       = aws_sfn_state_machine.eod_batch_pipeline.arn
}

output "prov_state_machine_arn" {
  description = "Step Functions ARN for the file-provisioning pipeline (consumed by PROV_STATE_MACHINE_ARN env var). Loads reference data via Flyway migrations + Glue ETL S3 → RDS bulk loads. Replaces app/jcl/ACCTFILE.jcl + CARDFILE.jcl + CUSTFILE.jcl + XREFFILE.jcl + TRANFILE.jcl + DISCGRP.jcl + TCATBALF.jcl + TRANCATG.jcl + TRANTYPE.jcl + DUSRSECJ.jcl + DEFCUST.jcl per AAP §0.6.3."
  value       = aws_sfn_state_machine.file_provisioning.arn
}


# =============================================================================
# Section 11 — AWS Batch outputs (from batch.tf)
# =============================================================================
# AWS Batch on Fargate per AAP §0.6.3 — Step Functions Task states invoke
# `arn:aws:states:::batch:submitJob.sync` targeting the job queue below.
# Each Spring Batch job is wrapped by an AWS Batch job definition that
# launches the same Spring Boot container as ECS, with a different
# `BATCH_JOB_NAME` env var that drives the Spring Batch job runner.
# =============================================================================

output "batch_job_queue_arn" {
  description = "AWS Batch job queue ARN where Spring Batch jobs are submitted. Referenced by Step Functions state-machine Task states (`Parameters.JobQueue`) and by the Spring Boot StepFunctionsOrchestrator adapter."
  value       = aws_batch_job_queue.carddemo.arn
}

output "batch_compute_environment_arn" {
  description = "AWS Batch Fargate compute environment ARN. Attached to the job queue above with priority 1. Scales to zero when idle (per AAP §0.7.2 cost-efficiency NFR)."
  value       = aws_batch_compute_environment.carddemo.arn
}

output "batch_job_definitions" {
  description = "Map of AWS Batch job definition ARNs by logical name — one per migrated COBOL batch program per AAP §0.4.1. Used by the Spring Boot batch-orchestration code and by Step Functions Task states (`Parameters.JobDefinition`)."
  value = {
    daily_transaction_posting = aws_batch_job_definition.daily_transaction_posting.arn # ← app/cbl/CBTRN02C.cbl + app/jcl/POSTTRAN.jcl
    interest_calculation      = aws_batch_job_definition.interest_calculation.arn      # ← app/cbl/CBACT04C.cbl + app/jcl/INTCALC.jcl
    combine_transactions      = aws_batch_job_definition.combine_transactions.arn      # ← app/jcl/COMBTRAN.jcl (utility job)
    statement_generation      = aws_batch_job_definition.statement_generation.arn      # ← app/cbl/CBSTM03A.CBL + CBSTM03B.CBL + app/jcl/CREASTMT.JCL
    transaction_report        = aws_batch_job_definition.transaction_report.arn        # ← app/cbl/CBTRN03C.cbl + app/jcl/TRANREPT.jcl
    print_category_balance    = aws_batch_job_definition.print_category_balance.arn    # ← app/jcl/PRTCATBL.jcl (category-balance print)
  }
}


# =============================================================================
# Section 12 — OpenSearch outputs (from opensearch.tf)
# =============================================================================
# Amazon OpenSearch indexes (a) CloudTrail events and (b) application audit
# logs from the AuditLogService adapter per AAP §0.6.6. The OPENSEARCH_ENDPOINT
# env var (mandatory per AAP §0.7.2) is constructed by prefixing the
# `endpoint` attribute with the `https://` scheme so Spring's RestClient
# can build a valid URL.
# =============================================================================

output "opensearch_endpoint" {
  description = "OpenSearch domain endpoint URL with https:// scheme (consumed by OPENSEARCH_ENDPOINT env var per AAP §0.7.2 mandatory env var list). Used by the AuditLogService adapter (writes audit events) and OpenSearchIndexer adapter (writes transaction logs)."
  value       = "https://${aws_opensearch_domain.carddemo.endpoint}"
}

output "opensearch_dashboard_endpoint" {
  description = "OpenSearch Dashboards URL for operators (UI for transaction-log search and CloudTrail event review per AAP §0.6.6). Authenticated via the opensearch_master credential stored in Secrets Manager (see opensearch_credentials_secret_arn)."
  value       = "https://${aws_opensearch_domain.carddemo.dashboard_endpoint}"
}

output "opensearch_domain_arn" {
  description = "OpenSearch domain ARN — used by IAM policies that grant `es:ESHttpPost`, `es:ESHttpPut`, `es:ESHttpGet` to the ECS task role for index / search operations."
  value       = aws_opensearch_domain.carddemo.arn
}


# =============================================================================
# Section 13 — CloudTrail outputs (from cloudtrail.tf)
# =============================================================================
# AWS CloudTrail organization-level audit trail per AAP §0.6.6 — captures
# every AWS API call (ECS deploys, RDS modifications, S3 object accesses,
# KMS decrypts). Log-file integrity validation is enabled.
# =============================================================================

output "cloudtrail_trail_arn" {
  description = "CloudTrail trail ARN for the organization-level audit trail (consumed by CLOUDTRAIL_TRAIL_ARN env var in application-prod.yml). Referenced by the AuditLogService adapter to cross-reference application audit events with infrastructure audit events."
  value       = aws_cloudtrail.carddemo.arn
}

output "cloudtrail_trail_name" {
  description = "CloudTrail trail name — used by AWS CLI / SDK lookups that prefer name over ARN."
  value       = aws_cloudtrail.carddemo.name
}


# =============================================================================
# Section 14 — AWS Glue outputs (from glue.tf)
# =============================================================================
# AWS Glue Spark ETL jobs per AAP §0.4.1 — replace the COBOL ASCII fixture
# → VSAM load JCLs (ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl, XREFFILE.jcl,
# TRANFILE.jcl). Each Glue job reads an ASCII fixture from S3 and bulk-loads
# the parsed records into the corresponding RDS table via the Glue
# PostgreSQL connection (aws_glue_connection.rds).
# =============================================================================

output "glue_etl_job_names" {
  description = "Map of AWS Glue ETL job names (replacing GDG-driven flat-file pipelines per AAP §0.4.1). Each job reads an ASCII fixture from S3 and bulk-loads parsed records into RDS via the Glue PostgreSQL connection."
  value = {
    ascii_to_rds_account     = aws_glue_job.ascii_to_rds_account.name     # ← app/data/ASCII/acctdata.txt + app/jcl/ACCTFILE.jcl
    ascii_to_rds_card        = aws_glue_job.ascii_to_rds_card.name        # ← app/data/ASCII/carddata.txt + app/jcl/CARDFILE.jcl
    ascii_to_rds_customer    = aws_glue_job.ascii_to_rds_customer.name    # ← app/data/ASCII/custdata.txt + app/jcl/CUSTFILE.jcl
    ascii_to_rds_xref        = aws_glue_job.ascii_to_rds_xref.name        # ← app/data/ASCII/cardxref.txt + app/jcl/XREFFILE.jcl
    ascii_to_rds_transaction = aws_glue_job.ascii_to_rds_transaction.name # ← app/data/ASCII/dailytran.txt + app/jcl/TRANFILE.jcl
  }
}


# =============================================================================
# Section 15 — Secrets Manager outputs (from secrets.tf)
# =============================================================================
# Secret ARNs ONLY — never secret values. Spring Boot resolves these at
# startup via Spring Cloud AWS:
#   spring.config.import=aws-secretsmanager:${jwt_signing_key_secret_arn}
# and at rotation events via @RefreshScope (AAP §0.6.4).
#
# Note: there is NO `msk_credentials_secret_arn` output. MSK uses IAM
# authentication via SASL_SSL (`bootstrap_brokers_sasl_iam` attribute on
# aws_msk_cluster.carddemo). The aws-msk-iam-auth library negotiates the
# SASL/IAM handshake at the producer / consumer level using the ECS task
# role's IAM credentials. No SCRAM secret exists in secrets.tf; the absence
# of this output is intentional per AAP §0.6.5.
# =============================================================================

output "jwt_signing_key_secret_arn" {
  description = "Secrets Manager ARN holding the JWT HS256 signing key (consumed by Spring Cloud AWS via spring.config.import=aws-secretsmanager:$${jwt_signing_key_secret_arn} in application-prod.yml). The signing key is used by JwtTokenProvider to sign access tokens issued by SignonService. Rotated by Secrets Manager + Lambda; the JwtTokenProvider bean is in @RefreshScope so it picks up the new key without a restart per AAP §0.6.4."
  value       = aws_secretsmanager_secret.jwt_signing_key.arn
  sensitive   = false
}

output "opensearch_credentials_secret_arn" {
  description = "Secrets Manager ARN for the OpenSearch master-user credentials. Consumer-compatible output name; the underlying resource is `aws_secretsmanager_secret.opensearch_master` in secrets.tf (line 199). Used by the OpenSearchIndexer adapter to authenticate against the OpenSearch domain when fine-grained access control is enabled."
  value       = aws_secretsmanager_secret.opensearch_master.arn
  sensitive   = false
}


# =============================================================================
# Section 16 — CloudWatch outputs (from cloudwatch.tf)
# =============================================================================
# CloudWatch log groups per AAP §0.6.6 — all encrypted with the primary
# CardDemo KMS CMK. Structured JSON logging (logback-spring.xml + 
# logstash-logback-encoder) flows from each compute component to its
# dedicated log group.
# =============================================================================

output "cloudwatch_log_groups" {
  description = "Map of CloudWatch log group names by purpose. All groups are KMS-encrypted with the primary CardDemo CMK and have retention configured via var.cloudwatch_log_retention_days. Application code uses these log group names via the aws-logs-group container log-driver option in the ECS task definition and the LogConfiguration block on Batch / Glue job definitions."
  value = {
    ecs_app        = aws_cloudwatch_log_group.ecs_app.name        # Spring Boot stdout/stderr
    batch_jobs     = aws_cloudwatch_log_group.batch_jobs.name     # AWS Batch container logs
    step_functions = aws_cloudwatch_log_group.step_functions.name # Step Functions execution history
    glue_jobs      = aws_cloudwatch_log_group.glue_jobs.name      # Glue Spark driver / executor logs
  }
}


# =============================================================================
# Section 17 — Networking outputs (from main.tf data sources)
# =============================================================================
# VPC + subnet IDs sourced from the bring-your-own-VPC data sources in
# main.tf. Consumed by integration tests, by Terraform modules nested under
# this one (none currently — but reserved for cross-module composition),
# and by operators verifying the deployed topology.
# =============================================================================

output "vpc_id" {
  description = "VPC ID hosting all CardDemo resources (data.aws_vpc.carddemo.id from main.tf, sourced from var.vpc_id)."
  value       = data.aws_vpc.carddemo.id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (data.aws_subnets.private.ids from main.tf). Host ECS tasks, RDS, ElastiCache, MSK, OpenSearch, AWS Batch, and AWS Glue connections. Never internet-reachable; egress via NAT or VPC endpoints only."
  value       = data.aws_subnets.private.ids
}

output "public_subnet_ids" {
  description = "Public subnet IDs (data.aws_subnets.public.ids from main.tf). Host the Application Load Balancer only. Internet gateway is in the operator-managed VPC."
  value       = data.aws_subnets.public.ids
}


# =============================================================================
# Section 18 — ECS task-definition environment-variable bundle
# =============================================================================
# Single-call convenience output that bundles every environment variable the
# ECS task definition injects into the Spring Boot container. The ECS task
# definition (ecs.tf) renders this map as a list of
# `{ name = "...", value = "..." }` objects in the container definition's
# `environment` array — preferred to per-key references because adding a
# new env var requires only a single edit here.
#
# AAP §0.7.2 mandatory keys (verbatim from user prompt):
#   * AWS_REGION
#   * ECS_CLUSTER_NAME
#   * RDS_SECRET_ARN
#   * KMS_KEY_ARN
#   * MSK_BOOTSTRAP_SERVERS
#   * S3_OUTPUT_BUCKET
#   * OPENSEARCH_ENDPOINT
#
# Additional keys required by application-prod.yml (AAP §0.4.1):
#   * RDS_HOST, RDS_PORT, RDS_DATABASE
#   * ELASTICACHE_HOST, ELASTICACHE_PORT
#   * EOD_STATE_MACHINE_ARN, PROV_STATE_MACHINE_ARN
#   * CLOUDTRAIL_TRAIL_ARN
#
# Note on type conversions: ECS env-var values must be strings, so numeric
# port attributes (rds_port, elasticache_port) are coerced with tostring().
# =============================================================================

output "ecs_task_environment" {
  description = "Bundle of every environment variable that the ECS task definition (ecs.tf) injects into the Spring Boot container. Includes the 7 AAP §0.7.2 mandatory env vars plus the 8 application-prod.yml extension vars. ECS task definitions reference this via `dynamic \"environment\" { for_each = ... }`."
  value = {
    # ---- AAP §0.7.2 — mandatory env vars (verbatim from user prompt) ----
    AWS_REGION            = var.aws_region
    ECS_CLUSTER_NAME      = aws_ecs_cluster.carddemo.name
    RDS_SECRET_ARN        = aws_secretsmanager_secret.rds_master.arn
    KMS_KEY_ARN           = aws_kms_key.carddemo.arn
    MSK_BOOTSTRAP_SERVERS = aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam
    S3_OUTPUT_BUCKET      = aws_s3_bucket.batch_outputs.bucket
    OPENSEARCH_ENDPOINT   = "https://${aws_opensearch_domain.carddemo.endpoint}"

    # ---- Additional required by application-prod.yml (AAP §0.4.1) ----
    RDS_HOST               = aws_db_instance.carddemo.address
    RDS_PORT               = tostring(aws_db_instance.carddemo.port)
    RDS_DATABASE           = aws_db_instance.carddemo.db_name
    ELASTICACHE_HOST       = aws_elasticache_replication_group.carddemo.primary_endpoint_address
    ELASTICACHE_PORT       = tostring(aws_elasticache_replication_group.carddemo.port)
    EOD_STATE_MACHINE_ARN  = aws_sfn_state_machine.eod_batch_pipeline.arn
    PROV_STATE_MACHINE_ARN = aws_sfn_state_machine.file_provisioning.arn
    CLOUDTRAIL_TRAIL_ARN   = aws_cloudtrail.carddemo.arn
  }
}
