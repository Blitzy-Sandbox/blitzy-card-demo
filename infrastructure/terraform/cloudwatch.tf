###############################################################################
# infrastructure/terraform/cloudwatch.tf
#
# Amazon CloudWatch resources for the CardDemo Spring Boot 3.x application
# running on AWS ECS Fargate. This file is the observability bedrock for the
# deployment and provisions every CloudWatch construct that the platform
# relies upon for log aggregation, security detection, operational alerting,
# and dashboards.
#
# Replaces the COBOL/JCL operator workflow:
#   * SDSF / JES output inspection ........... replaced by CloudWatch Logs
#                                              Insights queries over the
#                                              `/ecs/carddemo-<env>`,
#                                              `/aws/batch/carddemo-<env>`,
#                                              `/aws/stepfunctions/...` log
#                                              groups.
#   * COBOL audit-trail WRITE statements ..... replaced by the AuditLogService
#                                              writing to OpenSearch (slow-log
#                                              + audit log shipped to the
#                                              `/aws/opensearch/carddemo-<env>`
#                                              log group; index documents go
#                                              directly into the OpenSearch
#                                              domain from opensearch.tf).
#   * IDCAMS LISTCAT / SDSF "show jobs" ...... replaced by the CloudWatch
#                                              dashboard `carddemo-<env>` and
#                                              the operational alarms below.
#
# Resource inventory provided by this file (per the file-schema exports):
#
#   Log groups (7) — all KMS-CMK encrypted per AAP §0.6.6 / §0.7.1:
#     1. aws_cloudwatch_log_group.ecs_app
#          /ecs/carddemo-<env>
#          ECS Fargate task stdout (Spring Boot structured JSON via Logback
#          and logstash-logback-encoder; shipped by the awslogs log driver).
#     2. aws_cloudwatch_log_group.batch_jobs
#          /aws/batch/carddemo-<env>
#          AWS Batch container stdout/stderr (Spring Batch jobs launched
#          by the eod-batch-pipeline.asl.json state machine).
#     3. aws_cloudwatch_log_group.step_functions
#          /aws/stepfunctions/carddemo-<env>
#          Step Functions execution history at ALL log level (AAP §0.6.6
#          PCI-DSS audit requirement; one log entry per state transition).
#     4. aws_cloudwatch_log_group.glue_jobs
#          /aws/glue/carddemo-<env>
#          AWS Glue Spark continuous-logging output (bulk-load ETL of
#          ACCTDATA / CARDDATA / CUSTDATA / CARDXREF fixtures into RDS).
#     5. aws_cloudwatch_log_group.opensearch
#          /aws/opensearch/carddemo-<env>
#          OpenSearch slow-log and audit-log output (consumed by
#          opensearch.tf).
#     6. aws_cloudwatch_log_group.cloudtrail
#          /aws/cloudtrail/carddemo-<env>
#          CloudWatch Logs sink for the CloudTrail organization-level
#          trail (consumed by cloudtrail.tf; retention pinned at 400
#          days to comfortably exceed AWS's CloudTrail >=365-day
#          recommendation regardless of var.cloudwatch_log_retention_days).
#     7. aws_cloudwatch_log_group.secrets_rotation_lambda
#          /aws/lambda/carddemo-<env>-secrets-rotation
#          Lambda log group for the Secrets Manager rotation Lambda(s)
#          provisioned by secrets.tf (AAP §0.6.4 dynamic credential
#          rotation without Spring Boot restart).
#
#   Log metric filters (3) — PCI-DSS leakage detection per AAP §0.6.6:
#     1. aws_cloudwatch_log_metric_filter.pan_leakage_detected
#          Detects 13-19 digit sequences in ECS application logs (Visa/MC/
#          Amex/Discover PAN length range). Emits to CardDemo/Security
#          namespace under metric `PanLeakageDetected`.
#     2. aws_cloudwatch_log_metric_filter.acct_id_leakage_detected
#          Detects 11-digit ACCT-ID-shaped sequences (per AAP §0.6.1
#          ACCT-ID is PIC 9(11)). Emits to CardDemo/Security namespace
#          under metric `AcctIdLeakageDetected`.
#     3. aws_cloudwatch_log_metric_filter.error_log_count
#          Counts ERROR-level log events from the Logback structured
#          JSON encoder ($.level = "ERROR"). Emits to CardDemo/App
#          namespace under metric `ErrorLogCount`.
#
#   Metric alarms (7) — combined security + operational alarms:
#     1. aws_cloudwatch_metric_alarm.pan_leakage_alarm     (CRITICAL)
#     2. aws_cloudwatch_metric_alarm.acct_id_leakage_alarm (HIGH)
#     3. aws_cloudwatch_metric_alarm.ecs_cpu_high          (CAPACITY)
#     4. aws_cloudwatch_metric_alarm.ecs_memory_high       (CAPACITY)
#     5. aws_cloudwatch_metric_alarm.rds_connections_high  (CAPACITY)
#     6. aws_cloudwatch_metric_alarm.msk_consumer_lag      (per AAP §0.6.5
#                                                          guards per-account
#                                                          ordering on
#                                                          transaction.posted,
#                                                          account.updated,
#                                                          ledger.balanced,
#                                                          report.requested).
#     7. aws_cloudwatch_metric_alarm.app_error_rate        (RELIABILITY)
#
#   Dashboard (1):
#     1. aws_cloudwatch_dashboard.carddemo
#          Operator visibility dashboard covering ECS CPU/memory, RDS
#          connections, MSK consumer lag, application error rate, the
#          PAN leakage counter, and S3 4xx/5xx error metrics.
#
# Cross-references (all of these are guaranteed by the file-schema
# depends_on_files and validated against the actual resource definitions
# in the listed sibling files):
#   * aws_kms_key.carddemo                     — kms.tf
#   * local.common_tags                        — main.tf
#   * var.environment, var.aws_region,
#     var.cloudwatch_log_retention_days        — variables.tf
#   * aws_db_instance.carddemo                 — rds.tf
#   * aws_msk_cluster.carddemo                 — msk.tf
#
# Cross-references to sibling .tf files that CONSUME these resources
# (those files reference the log groups / alarms declared here; they
# do not redeclare them):
#   * ecs.tf            — awslogs driver -> aws_cloudwatch_log_group.ecs_app
#   * batch.tf          — Batch logConfiguration -> batch_jobs
#   * stepfunctions.tf  — Step Functions LoggingConfiguration -> step_functions
#   * glue.tf           — Glue --continuous-log-logGroup -> glue_jobs
#   * opensearch.tf     — OpenSearch SlowLogs/AuditLogs publish_options
#                         -> opensearch
#   * cloudtrail.tf     — CloudTrail cloud_watch_logs_group_arn -> cloudtrail
#   * secrets.tf        — Secrets Manager rotation Lambda
#                         logConfiguration -> secrets_rotation_lambda
#
# AAP references:
#   * §0.6.5 — MSK topic ordering guarantees (drives msk_consumer_lag alarm).
#   * §0.6.6 — Cross-cutting: Audit, Observability, PCI-DSS (drives KMS-CMK
#              log group encryption, log metric filters, dashboard).
#   * §0.7.1 — "All data at rest encrypted via AWS KMS (CloudWatch Logs)"
#              (drives kms_key_id on every log group).
#   * §0.7.2 — "No plaintext card/account data in logs — enforced via
#              CloudWatch log filters" (drives PAN + ACCT-ID metric filters).
###############################################################################

# =============================================================================
# Section 1 — CloudWatch Log Groups (7)
# =============================================================================
# Every log group sets kms_key_id = aws_kms_key.carddemo.arn so that log
# events are encrypted at rest under the CardDemo primary customer-managed
# CMK. The kms.tf key policy already contains an `AllowCloudWatchLogs`
# statement granting `logs.${var.aws_region}.amazonaws.com` the
# kms:Encrypt*/Decrypt*/ReEncrypt*/GenerateDataKey*/Describe* actions
# (scoped via ArnEquals kms:EncryptionContext:aws:logs:arn), so no
# additional key-policy modifications are required for these log groups.
#
# Retention values:
#   * All application/service log groups use var.cloudwatch_log_retention_days
#     (default 365 days — satisfies PCI-DSS Requirement 10.7's 1-year
#     audit-log retention floor).
#   * The CloudTrail log group uses a literal 400 days so that even if
#     var.cloudwatch_log_retention_days is reduced for non-prod
#     environments, CloudTrail's >=365-day recommendation is upheld.
# =============================================================================

# -----------------------------------------------------------------------------
# 1.1 — ECS Fargate application log group
#
# Consumed by the ECS task definition's awslogs log driver in ecs.tf:
#
#   logConfiguration {
#     logDriver = "awslogs"
#     options = {
#       awslogs-group         = aws_cloudwatch_log_group.ecs_app.name
#       awslogs-region        = var.aws_region
#       awslogs-stream-prefix = "carddemo"
#     }
#   }
#
# Spring Boot emits structured JSON via Logback + logstash-logback-encoder
# (configured in src/main/resources/logback-spring.xml); the awslogs driver
# ships the JSON to this log group. CloudWatch Logs Insights can then
# query structured fields (`$.level`, `$.logger`, `$.traceId`, etc.).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "ecs_app" {
  name              = "/ecs/carddemo-${var.environment}"
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-ecs-app-log"
    Purpose = "Spring Boot ECS Fargate task stdout (structured JSON via Logback)"
  })
}

# -----------------------------------------------------------------------------
# 1.2 — AWS Batch jobs log group
#
# Consumed by AWS Batch job definitions in batch.tf:
#
#   logConfiguration {
#     logDriver = "awslogs"
#     options = {
#       awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
#       awslogs-region        = var.aws_region
#       awslogs-stream-prefix = "<job-definition-name>"
#     }
#   }
#
# Captures Spring Batch job container output for the end-of-day batch
# pipeline (POSTTRAN -> INTCALC -> COMBTRAN -> CREASTMT / TRANREPT)
# launched by the eod-batch-pipeline Step Functions state machine.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "batch_jobs" {
  name              = "/aws/batch/carddemo-${var.environment}"
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-jobs-log"
    Purpose = "AWS Batch container output for Spring Batch end-of-day jobs"
  })
}

# -----------------------------------------------------------------------------
# 1.3 — Step Functions executions log group
#
# Consumed by aws_sfn_state_machine.* in stepfunctions.tf:
#
#   logging_configuration {
#     log_destination        = "${aws_cloudwatch_log_group.step_functions.arn}:*"
#     include_execution_data = true
#     level                  = "ALL"
#   }
#
# Captures every state transition for the eod-batch-pipeline +
# file-provisioning state machines (one CloudWatch log event per
# ExecutionStarted / TaskStarted / TaskSucceeded / TaskFailed / etc.).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "step_functions" {
  name              = "/aws/stepfunctions/carddemo-${var.environment}"
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-stepfunctions-log"
    Purpose = "Step Functions state-machine execution history (PCI-DSS audit)"
  })
}

# -----------------------------------------------------------------------------
# 1.4 — AWS Glue Spark jobs log group
#
# Consumed by aws_glue_job.* in glue.tf via the standard Glue job
# argument `--continuous-log-logGroup`:
#
#   default_arguments = {
#     "--enable-continuous-cloudwatch-log" = "true"
#     "--continuous-log-logGroup"          = aws_cloudwatch_log_group.glue_jobs.name
#     "--continuous-log-logStreamPrefix"   = "carddemo"
#   }
#
# Captures Glue ETL output for bulk-load jobs that move ASCII fixtures
# from S3 (replaces the JCL `IDCAMS REPRO` load step) into the
# carddemo RDS instance.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "glue_jobs" {
  name              = "/aws/glue/carddemo-${var.environment}"
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-jobs-log"
    Purpose = "AWS Glue Spark continuous-logging output (bulk-load ETL)"
  })
}

# -----------------------------------------------------------------------------
# 1.5 — OpenSearch slow-log / audit-log group
#
# Consumed by aws_opensearch_domain.carddemo in opensearch.tf via:
#
#   log_publishing_options {
#     log_type                 = "SEARCH_SLOW_LOGS"  # and INDEX_SLOW_LOGS, AUDIT_LOGS
#     cloudwatch_log_group_arn = aws_cloudwatch_log_group.opensearch.arn
#     enabled                  = true
#   }
#
# Captures OpenSearch slow searches and the fine-grained-access-control
# audit log (every authenticated request). AuditLogService application
# events are indexed directly into the OpenSearch domain, not into this
# CloudWatch group.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "opensearch" {
  name              = "/aws/opensearch/carddemo-${var.environment}"
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-opensearch-log"
    Purpose = "OpenSearch slow-log and FGAC audit-log delivery"
  })
}

# -----------------------------------------------------------------------------
# 1.6 — CloudTrail log group
#
# Consumed by aws_cloudtrail.carddemo in cloudtrail.tf via:
#
#   cloud_watch_logs_group_arn = "${aws_cloudwatch_log_group.cloudtrail.arn}:*"
#   cloud_watch_logs_role_arn  = aws_iam_role.cloudtrail_to_cloudwatch.arn
#
# AAP §0.6.6 mandates an organization-level CloudTrail that streams
# every AWS API call to an immutable S3 bucket AND to CloudWatch Logs
# for searchable retention. Retention here is pinned at 400 days to
# comfortably exceed CloudTrail's >=365-day recommendation regardless
# of var.cloudwatch_log_retention_days.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "cloudtrail" {
  name              = "/aws/cloudtrail/carddemo-${var.environment}"
  retention_in_days = 400
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-cloudtrail-log"
    Purpose = "CloudTrail CloudWatch Logs delivery (PCI-DSS audit)"
  })
}

# -----------------------------------------------------------------------------
# 1.7 — Secrets Manager rotation Lambda log group
#
# Consumed by aws_lambda_function.secrets_rotation_* (or the
# aws_serverlessapplicationrepository_cloudformation_stack
# .rds_rotation_lambda resource) in secrets.tf. AWS-managed rotation
# Lambdas auto-discover their CloudWatch log group at runtime by
# convention `/aws/lambda/<function-name>`; we precreate the group so
# Terraform can enforce KMS encryption and tagging.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "secrets_rotation_lambda" {
  name              = "/aws/lambda/carddemo-${var.environment}-secrets-rotation"
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-secrets-rotation-lambda-log"
    Purpose = "Secrets Manager rotation Lambda execution log"
  })
}

# =============================================================================
# Section 2 — CloudWatch Log Metric Filters (3)
# =============================================================================
# Metric filters are the LAST line of defense against accidental PAN /
# account-ID leakage into application logs. The primary controls remain:
#   * AuditLogService field masking at emission time
#   * Macie continuous S3 scanning (macie.tf)
#   * WAF body inspection on inbound traffic (waf.tf)
# These filters detect any masking bypass and trigger CloudWatch alarms.
#
# Filter syntax notes:
#   * The `%pattern%` form delimits a regular-expression token match
#     inside the CloudWatch Logs filter language (this is the extended
#     pattern syntax that supports the `{n,m}` repetition quantifier).
#   * The `{ $.field = "value" }` form is the JSON-event filter that
#     matches structured Logback output emitted by
#     logstash-logback-encoder.
# =============================================================================

# -----------------------------------------------------------------------------
# 2.1 — PAN (Primary Account Number) leakage metric filter
#
# Matches any 13-to-19 consecutive-digit sequence in ECS application
# logs. This range covers all major card brand PAN lengths:
#   Visa       13 or 16 digits
#   MasterCard 16 digits
#   Amex       15 digits
#   Discover   16 digits
#   JCB        16 digits
#   UnionPay   16-19 digits
# False positives (e.g., 16-digit order numbers) are accepted because
# the corresponding alarm threshold (Section 3.1) is intentionally set
# to zero: any match triggers immediate human investigation.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_metric_filter" "pan_leakage_detected" {
  name           = "carddemo-${var.environment}-pan-leakage"
  log_group_name = aws_cloudwatch_log_group.ecs_app.name
  pattern        = "%[0-9]{13,19}%"

  metric_transformation {
    name          = "PanLeakageDetected"
    namespace     = "CardDemo/Security"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# -----------------------------------------------------------------------------
# 2.2 — ACCT-ID leakage metric filter
#
# Matches any 11-consecutive-digit sequence that is NOT part of a longer
# digit run (the `[^0-9]...[^0-9]` boundary). 11 digits is the COBOL
# ACCT-ID layout per app/cpy/CVACT01Y.cpy `ACCT-ID PIC 9(11)` /
# AAP §0.6.1. This filter is broader than the PAN filter (more false
# positives expected) so its alarm threshold is intentionally higher.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_metric_filter" "acct_id_leakage_detected" {
  name           = "carddemo-${var.environment}-acct-id-leakage"
  log_group_name = aws_cloudwatch_log_group.ecs_app.name
  pattern        = "%[^0-9][0-9]{11}[^0-9]%"

  metric_transformation {
    name          = "AcctIdLeakageDetected"
    namespace     = "CardDemo/Security"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# -----------------------------------------------------------------------------
# 2.3 — ERROR-level log count
#
# Matches Logback structured-JSON events with `$.level = "ERROR"`. The
# Logstash encoder emits `level` in uppercase, so the match is reliable
# regardless of where the ERROR originated (controller advice, service,
# repository, batch step). Feeds the app_error_rate alarm.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_metric_filter" "error_log_count" {
  name           = "carddemo-${var.environment}-error-count"
  log_group_name = aws_cloudwatch_log_group.ecs_app.name
  pattern        = "{ $.level = \"ERROR\" }"

  metric_transformation {
    name          = "ErrorLogCount"
    namespace     = "CardDemo/App"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# =============================================================================
# Section 3 — CloudWatch Metric Alarms (7)
# =============================================================================
# Each alarm is created without `alarm_actions` wired to an SNS topic by
# default. When an SNS topic for operator paging is provisioned (e.g., by
# a future iteration that wires Macie/Shield/CloudTrail findings into a
# unified `carddemo-${var.environment}-ops` topic), update each alarm's
# `alarm_actions` list to publish to that topic.
#
# The alarms still produce ALARM/OK state transitions visible in:
#   * The CloudWatch Console
#   * Amazon EventBridge (via the default event bus)
#   * The CloudWatch dashboard widgets in Section 4
#
# All alarms tag with local.common_tags + an additional Severity tag to
# help operators prioritize at-a-glance triage.
# =============================================================================

# -----------------------------------------------------------------------------
# 3.1 — PAN leakage alarm (CRITICAL, PCI-DSS Req 3.4.1)
#
# Threshold = 0 with evaluation_periods = 1 means ANY single hit on the
# PAN metric filter (Section 2.1) triggers the alarm within one minute.
# This is a zero-tolerance gate per PCI-DSS Requirement 3.4.1 (render
# PAN unreadable anywhere it is stored, including logs).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "pan_leakage_alarm" {
  alarm_name          = "carddemo-${var.environment}-pan-leakage-critical"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "PanLeakageDetected"
  namespace           = "CardDemo/Security"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "CRITICAL PCI-DSS: PAN-like (13-19 digit) sequence detected in application logs. Investigate immediately (Req 3.4.1)."
  treat_missing_data  = "notBreaching"
  alarm_actions       = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-pan-leakage-critical"
    Severity = "critical"
    PciDss   = "Req-3.4.1"
  })
}

# -----------------------------------------------------------------------------
# 3.2 — ACCT-ID leakage alarm (HIGH)
#
# Higher threshold (>10 hits over 5 minutes) to absorb the larger
# false-positive surface of an 11-digit pattern. Still flagged for
# investigation; account IDs are sensitive but lower-risk than PANs.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "acct_id_leakage_alarm" {
  alarm_name          = "carddemo-${var.environment}-acct-id-leakage"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "AcctIdLeakageDetected"
  namespace           = "CardDemo/Security"
  period              = 60
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "Possible account-ID leakage: >10 ACCT-ID-shaped (11-digit) sequences in application logs within 5 minutes."
  treat_missing_data  = "notBreaching"
  alarm_actions       = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-acct-id-leakage"
    Severity = "high"
    PciDss   = "Req-3.4.1"
  })
}

# -----------------------------------------------------------------------------
# 3.3 — ECS service CPU utilization alarm
#
# Watches the AWS/ECS CPUUtilization metric for the carddemo Spring Boot
# service. Sustained >75% over 3 minutes signals that auto-scaling
# should respond (or has not yet responded). Threshold deliberately
# below the 80% auto-scaling target alarm to give operators a heads-up
# before scaling kicks in.
#
# Dimensions use literal carddemo-${var.environment}-* names matching
# the naming convention used by sibling .tf files in this module
# (carddemo-<env>-rds, carddemo-<env>-msk, etc.). The future ecs.tf
# is expected to name the cluster `carddemo-<env>-ecs` and the service
# `carddemo-<env>-app` per the same convention.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "ecs_cpu_high" {
  alarm_name          = "carddemo-${var.environment}-ecs-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ECS"
  period              = 60
  statistic           = "Average"
  threshold           = 75
  dimensions = {
    ClusterName = "carddemo-${var.environment}-ecs"
    ServiceName = "carddemo-${var.environment}-app"
  }
  alarm_description = "ECS service CPU exceeded 75% over 3 minutes. Auto-scaling target (80%) should respond shortly."
  alarm_actions     = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-ecs-cpu-high"
    Severity = "medium"
  })
}

# -----------------------------------------------------------------------------
# 3.4 — ECS service memory utilization alarm
#
# Watches the AWS/ECS MemoryUtilization metric. Spring Boot + JPA + Kafka
# memory pressure is generally a leading indicator of GC pauses and p99
# latency degradation. Threshold 80% over 3 minutes.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "ecs_memory_high" {
  alarm_name          = "carddemo-${var.environment}-ecs-memory-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "MemoryUtilization"
  namespace           = "AWS/ECS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  dimensions = {
    ClusterName = "carddemo-${var.environment}-ecs"
    ServiceName = "carddemo-${var.environment}-app"
  }
  alarm_description = "ECS service memory exceeded 80% over 3 minutes. Inspect heap usage and GC behaviour."
  alarm_actions     = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-ecs-memory-high"
    Severity = "medium"
  })
}

# -----------------------------------------------------------------------------
# 3.5 — RDS DatabaseConnections alarm
#
# HikariCP per-task connection pool default = 20 (configured in
# application-prod.yml). With var.ecs_desired_count tasks the total
# RDS connection count rises linearly. Threshold 80 connections over 3
# minutes signals approaching saturation of the db.r6g.large default
# max_connections (or a connection-leak regression).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "rds_connections_high" {
  alarm_name          = "carddemo-${var.environment}-rds-connections-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "DatabaseConnections"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.carddemo.id
  }
  alarm_description = "RDS DatabaseConnections >80 over 3 minutes. Check HikariCP pool size and active ECS task count for saturation or connection-leak regressions."
  alarm_actions     = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-rds-connections-high"
    Severity = "medium"
  })
}

# -----------------------------------------------------------------------------
# 3.6 — MSK consumer lag alarm (AAP §0.6.5 per-account ordering guard)
#
# Protects the per-account ordering invariant on transaction.posted,
# account.updated, ledger.balanced, and report.requested topics. When
# any partition's MaxOffsetLag exceeds 1000 messages for 5 minutes,
# consumer scaling has not kept up with producers and end-of-day
# reconciliation SLAs are at risk.
#
# Dimension key MUST be the literal `"Cluster Name"` (with a space)
# because that is the AWS/Kafka CloudWatch dimension name published by
# the MSK open-monitoring exporter.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "msk_consumer_lag" {
  alarm_name          = "carddemo-${var.environment}-msk-consumer-lag"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "MaxOffsetLag"
  namespace           = "AWS/Kafka"
  period              = 60
  statistic           = "Maximum"
  threshold           = 1000
  dimensions = {
    "Cluster Name" = aws_msk_cluster.carddemo.cluster_name
  }
  alarm_description = "MSK consumer lag >1000 messages for 5 minutes. Per-account ordering at risk (AAP §0.6.5). Scale ECS tasks or investigate consumer failures."
  alarm_actions     = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-msk-consumer-lag"
    Severity = "high"
  })
}

# -----------------------------------------------------------------------------
# 3.7 — Application ERROR rate alarm
#
# Watches the ErrorLogCount metric driven by the JSON metric filter in
# Section 2.3. Threshold 20 ERROR/min over 3 minutes catches sustained
# error storms (e.g., a downstream MSK outage causing Kafka send
# failures, or a malformed reference-data upload triggering validation
# failures in CBTRN02C-derived TransactionPostingService).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "app_error_rate" {
  alarm_name          = "carddemo-${var.environment}-app-error-rate"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "ErrorLogCount"
  namespace           = "CardDemo/App"
  period              = 60
  statistic           = "Sum"
  threshold           = 20
  alarm_description   = "Application ERROR rate exceeded 20/min for 3 minutes. Inspect CloudWatch Logs Insights / OpenSearch for the offending traceId."
  treat_missing_data  = "notBreaching"
  alarm_actions       = []

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-app-error-rate"
    Severity = "high"
  })
}

# =============================================================================
# Section 4 — CloudWatch Dashboard
# =============================================================================
# Operator visibility dashboard surfacing the most important runtime
# signals at a glance. Widget layout (24-column grid):
#
#   Row 1 (y=0,  height=6): ECS CPU / Memory                (x=0,  w=12)
#                            RDS DatabaseConnections        (x=12, w=12)
#   Row 2 (y=6,  height=6): MSK MaxOffsetLag                (x=0,  w=12)
#                            Application ERROR rate         (x=12, w=12)
#   Row 3 (y=12, height=6): PAN / ACCT-ID leakage counters  (x=0,  w=12)
#                            S3 4xx / 5xx error rate        (x=12, w=12)
#
# `aws_msk_cluster.carddemo.cluster_name` and `aws_db_instance.carddemo.id`
# are interpolated as string literals into the JSON dashboard body via
# jsonencode().
# =============================================================================
resource "aws_cloudwatch_dashboard" "carddemo" {
  dashboard_name = "carddemo-${var.environment}"

  dashboard_body = jsonencode({
    widgets = [
      # -----------------------------------------------------------------
      # Widget 1 — ECS CPU & Memory utilisation (line chart, dual metric)
      # -----------------------------------------------------------------
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title   = "ECS CPU & Memory"
          region  = var.aws_region
          stat    = "Average"
          period  = 60
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", "carddemo-${var.environment}-ecs", "ServiceName", "carddemo-${var.environment}-app"],
            [".", "MemoryUtilization", ".", ".", ".", "."]
          ]
          yAxis = {
            left = {
              min = 0
              max = 100
            }
          }
        }
      },

      # -----------------------------------------------------------------
      # Widget 2 — RDS DatabaseConnections
      # -----------------------------------------------------------------
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title   = "RDS DatabaseConnections"
          region  = var.aws_region
          stat    = "Average"
          period  = 60
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", aws_db_instance.carddemo.id]
          ]
          annotations = {
            horizontal = [
              {
                value = 80
                label = "rds_connections_high threshold"
                color = "#d62728"
              }
            ]
          }
        }
      },

      # -----------------------------------------------------------------
      # Widget 3 — MSK MaxOffsetLag (consumer lag)
      # -----------------------------------------------------------------
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title   = "MSK Consumer Lag (MaxOffsetLag)"
          region  = var.aws_region
          stat    = "Maximum"
          period  = 60
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["AWS/Kafka", "MaxOffsetLag", "Cluster Name", aws_msk_cluster.carddemo.cluster_name]
          ]
          annotations = {
            horizontal = [
              {
                value = 1000
                label = "msk_consumer_lag threshold"
                color = "#d62728"
              }
            ]
          }
        }
      },

      # -----------------------------------------------------------------
      # Widget 4 — Application ERROR rate (per minute)
      # -----------------------------------------------------------------
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title   = "Application ERROR rate (per minute)"
          region  = var.aws_region
          stat    = "Sum"
          period  = 60
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["CardDemo/App", "ErrorLogCount"]
          ]
          annotations = {
            horizontal = [
              {
                value = 20
                label = "app_error_rate threshold"
                color = "#d62728"
              }
            ]
          }
        }
      },

      # -----------------------------------------------------------------
      # Widget 5 — PAN / ACCT-ID leakage counters (number widget)
      # -----------------------------------------------------------------
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 12
        height = 6
        properties = {
          title   = "PCI-DSS leakage counters (PAN / ACCT-ID)"
          region  = var.aws_region
          stat    = "Sum"
          period  = 300
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["CardDemo/Security", "PanLeakageDetected"],
            [".", "AcctIdLeakageDetected"]
          ]
          annotations = {
            horizontal = [
              {
                value = 0
                label = "pan_leakage_alarm threshold (zero tolerance)"
                color = "#d62728"
              }
            ]
          }
        }
      },

      # -----------------------------------------------------------------
      # Widget 6 — S3 4xx / 5xx error rates (across batch_outputs bucket)
      # -----------------------------------------------------------------
      {
        type   = "metric"
        x      = 12
        y      = 12
        width  = 12
        height = 6
        properties = {
          title   = "S3 4xx / 5xx errors"
          region  = var.aws_region
          stat    = "Sum"
          period  = 60
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["AWS/S3", "4xxErrors", "BucketName", "carddemo-${var.environment}-batch-outputs", "FilterId", "EntireBucket"],
            [".", "5xxErrors", ".", ".", ".", "."]
          ]
        }
      }
    ]
  })
}
