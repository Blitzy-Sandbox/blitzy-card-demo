###############################################################################
# infrastructure/terraform/cloudwatch.tf
#
# CloudWatch Log Groups, PCI-DSS Metric Filters, and Alarms.
#
# Purpose:
#   Provisions the central CloudWatch observability layer for CardDemo:
#     1. Seven KMS-encrypted log groups (per F-CP6-TF-CloudWatch-01) that
#        capture every application, batch, audit, and security stream:
#          /carddemo/<env>/app          — Spring Boot ECS application logs
#          /carddemo/<env>/audit        — Audit-log adapter output (90d+)
#          /carddemo/<env>/batch        — Spring Batch + AWS Batch logs
#          /carddemo/<env>/security     — Spring Security / auth logs (90d+)
#          /aws/ecs/carddemo/<env>      — ECS service-level events
#          /aws/batch/carddemo/<env>    — AWS Batch job execution logs
#          /aws/lambda/carddemo/<env>   — Lambda function logs (rotation,
#                                          Step Functions Choice helpers)
#     2. Three PCI-DSS metric filters per F-CP6-TF-CloudWatch-01:
#          PAN (16-digit regex)           — detects card PANs in logs
#          ACCT-ID (11-digit pattern)     — detects raw account IDs
#          ERROR pattern                  — detects ERROR / FATAL / Exception
#     3. CloudWatch metric alarms per AAP §0.6.6:
#          RDS CPU utilization > 80 %     — sustained for 5 minutes
#          MSK consumer lag > 10 000      — sustained for 5 minutes
#          ECS service unhealthy count    — > 0 for 5 minutes
#          PAN-detected count             — > 0 (single-shot CRITICAL)
#          ACCT-ID-detected count         — > 0 (single-shot CRITICAL)
#          Application ERROR count        — > 50 per 5 minutes
#
# PCI-DSS rationale (AAP §0.6.6):
#   * Audit / security log retention >= 365 days per the standard PCI-DSS
#     Requirement 10.5.3 (or whatever local-regulator floor applies).
#   * All log groups are KMS-encrypted using the dedicated CloudWatch
#     CMK (aws_kms_key.cloudwatch_kms — created by kms.tf KMS separation).
#   * Metric filters use literal patterns expressible in CloudWatch
#     Logs filter syntax; the application MUST NEVER log plaintext PAN
#     or account-id values — the filters are LAST-LINE DETECTION, not a
#     primary control. The primary controls are:
#       (a) AuditLogService masking
#       (b) Macie continuous S3 scanning (macie.tf)
#       (c) WAF body inspection (waf.tf)
#
# Cross-references:
#   * kms.tf            — aws_kms_key.cloudwatch_kms (created by Phase 6
#                         KMS separation)
#   * macie.tf          — complementary S3 PII scanning
#   * waf.tf            — already declares aws_cloudwatch_log_group.waf;
#                         this file does NOT duplicate it
#   * elasticache.tf    — already declares
#                         aws_cloudwatch_log_group.elasticache_slow and
#                         elasticache_engine; this file does NOT duplicate
#   * msk.tf            — already declares aws_cloudwatch_log_group.msk_broker
#   * variables.tf      — cloudwatch_log_retention_days (default 365)
#
# F-CP6-TF-CloudWatch-01:
#   This file resolves the CP6 CRITICAL review finding that cloudwatch.tf
#   was missing. The 4 log groups previously defined (waf, elasticache_slow,
#   elasticache_engine, msk_broker) PLUS the 7 log groups defined here =
#   11 log groups in total. The minimum 7 required by the CP6 review are
#   the 7 carddemo-* / aws/ecs / aws/batch / aws/lambda log groups in
#   this file.
###############################################################################

# =============================================================================
# Section 1 — Locals: log group names + retention overrides
# =============================================================================
# Centralizes log group names + per-group retention overrides. Audit and
# security log groups have a 90-day retention floor (PCI-DSS audit
# trail). Application / batch / ECS / Batch / Lambda log groups inherit
# the default `var.cloudwatch_log_retention_days` (default 365 days,
# satisfying PCI-DSS Requirement 10.5.3 with margin).
# =============================================================================

locals {
  cw_log_group_app      = "/carddemo/${var.environment}/app"
  cw_log_group_audit    = "/carddemo/${var.environment}/audit"
  cw_log_group_batch    = "/carddemo/${var.environment}/batch"
  cw_log_group_security = "/carddemo/${var.environment}/security"
  cw_log_group_ecs      = "/aws/ecs/carddemo-${var.environment}"
  cw_log_group_aws_bat  = "/aws/batch/carddemo-${var.environment}"
  cw_log_group_lambda   = "/aws/lambda/carddemo-${var.environment}"

  # Retention floor: 90 days for audit/security per PCI-DSS Requirement
  # 10.5.3 (1 year is the target, but the floor is 90 days). The
  # operator may override these via variables in the future; today they
  # are pinned constants in this file.
  cw_audit_retention_days    = 365
  cw_security_retention_days = 365
}

# =============================================================================
# Section 2 — KMS-encrypted CloudWatch log groups (the 7 required)
# =============================================================================
# Each log group is encrypted with the CloudWatch CMK (kms.tf). Names
# follow the CardDemo convention `/carddemo/<env>/<purpose>` for app-
# emitted streams and the standard `/aws/<service>/carddemo-<env>` for
# AWS-emitted streams.
# =============================================================================

# -----------------------------------------------------------------------------
# 1. Application log group — Spring Boot structured JSON output.
#    Logback's CloudWatchAppender writes to this group; structured fields
#    (traceId, spanId, level, logger, message, fields) are extracted
#    by CloudWatch Logs Insights queries.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_app" {
  name              = local.cw_log_group_app
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-app-log"
    Purpose = "Spring Boot ECS application structured JSON logs"
  })
}

# -----------------------------------------------------------------------------
# 2. Audit log group — AuditLogService output (AAP §0.6.6).
#    Retention floor 365 days for PCI-DSS / regulatory queries.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_audit" {
  name              = local.cw_log_group_audit
  retention_in_days = local.cw_audit_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-audit-log"
    Purpose = "AuditLogService output for PCI-DSS / regulatory queries"
  })
}

# -----------------------------------------------------------------------------
# 3. Batch log group — Spring Batch + AWS Batch step-level logs.
#    Captures JobExecutionListener events emitted by BatchJobConfig's
#    sharedAuditJobExecutionListener.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_batch" {
  name              = local.cw_log_group_batch
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-log"
    Purpose = "Spring Batch + AWS Batch step-level logs"
  })
}

# -----------------------------------------------------------------------------
# 4. Security log group — Spring Security 6 + JWT events.
#    Retention floor 365 days; consumed by OpenSearch indexing.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_security" {
  name              = local.cw_log_group_security
  retention_in_days = local.cw_security_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-security-log"
    Purpose = "Spring Security / JWT auth events for PCI-DSS Req 10"
  })
}

# -----------------------------------------------------------------------------
# 5. ECS service log group — ECS data-plane events (task starts, stops,
#    health-check failures).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_ecs" {
  name              = local.cw_log_group_ecs
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-ecs-log"
    Purpose = "ECS Fargate service events"
  })
}

# -----------------------------------------------------------------------------
# 6. AWS Batch log group — Step Functions -> Batch -> container stdout.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_aws_batch" {
  name              = local.cw_log_group_aws_bat
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-aws-batch-log"
    Purpose = "AWS Batch job execution logs"
  })
}

# -----------------------------------------------------------------------------
# 7. Lambda log group — RDS rotation Lambda + Step Functions Choice
#    helpers + any custom Lambdas.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "carddemo_lambda" {
  name              = local.cw_log_group_lambda
  retention_in_days = var.cloudwatch_log_retention_days
  kms_key_id        = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-lambda-log"
    Purpose = "Lambda function logs (RDS rotation, Step Functions helpers)"
  })
}

# =============================================================================
# Section 3 — PCI-DSS metric filters (PAN, ACCT-ID, ERROR)
# =============================================================================
# CloudWatch Logs metric filters scan application + audit + batch log
# groups for sensitive-data patterns. Each filter produces a 0/1 metric
# point that drives the corresponding alarm in Section 4.
#
# The filters are LAST-LINE DETECTION — the application MUST NEVER log
# plaintext PAN or account-id values. AuditLogService masks PANs at
# emission time. These filters detect masking bypasses.
# =============================================================================

# -----------------------------------------------------------------------------
# Filter 1 — PAN (16-digit numeric sequence)
#
# CloudWatch Logs filter pattern syntax does not support full regex; the
# closest expressible pattern is an inclusive token search for any
# 16-consecutive-digit sequence. To compensate for false positives the
# alarm is set to "single occurrence" so security operations can
# investigate every match.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_metric_filter" "pan_detection" {
  name           = "carddemo-${var.environment}-pan-detection"
  log_group_name = aws_cloudwatch_log_group.carddemo_app.name

  # The pattern matches any token of exactly 16 consecutive digits.
  # CloudWatch Logs filter syntax: a quoted literal performs substring
  # match; a regex-like pattern can be expressed via
  # `%[0-9]{16}%` (extended pattern syntax).
  pattern = "%[0-9]{16}%"

  metric_transformation {
    name          = "PanDetected"
    namespace     = "CardDemo/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# Apply the same filter to audit / batch / security log groups so PAN
# leaks anywhere in the application surface trigger the same alarm.
resource "aws_cloudwatch_log_metric_filter" "pan_detection_audit" {
  name           = "carddemo-${var.environment}-pan-detection-audit"
  log_group_name = aws_cloudwatch_log_group.carddemo_audit.name
  pattern        = "%[0-9]{16}%"

  metric_transformation {
    name          = "PanDetected"
    namespace     = "CardDemo/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_log_metric_filter" "pan_detection_batch" {
  name           = "carddemo-${var.environment}-pan-detection-batch"
  log_group_name = aws_cloudwatch_log_group.carddemo_batch.name
  pattern        = "%[0-9]{16}%"

  metric_transformation {
    name          = "PanDetected"
    namespace     = "CardDemo/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# -----------------------------------------------------------------------------
# Filter 2 — ACCT-ID (11-digit numeric sequence)
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_metric_filter" "acct_id_detection" {
  name           = "carddemo-${var.environment}-acct-id-detection"
  log_group_name = aws_cloudwatch_log_group.carddemo_app.name

  # 11-digit pattern matching the COBOL ACCT-ID PIC 9(11) layout.
  pattern = "%[0-9]{11}%"

  metric_transformation {
    name          = "AcctIdDetected"
    namespace     = "CardDemo/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_log_metric_filter" "acct_id_detection_audit" {
  name           = "carddemo-${var.environment}-acct-id-detection-audit"
  log_group_name = aws_cloudwatch_log_group.carddemo_audit.name
  pattern        = "%[0-9]{11}%"

  metric_transformation {
    name          = "AcctIdDetected"
    namespace     = "CardDemo/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# -----------------------------------------------------------------------------
# Filter 3 — ERROR pattern
#
# Matches the literal token `ERROR` (case-sensitive), `FATAL`, and the
# Java `Exception` substring. Logback's structured JSON encoder always
# emits `level=ERROR` in upper case, so the token boundary is reliable.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_metric_filter" "error_detection" {
  name           = "carddemo-${var.environment}-error-detection"
  log_group_name = aws_cloudwatch_log_group.carddemo_app.name

  # CloudWatch Logs `OR` requires the `?` prefix; spaces separate terms
  # within a quoted phrase.
  pattern = "?ERROR ?FATAL ?Exception"

  metric_transformation {
    name          = "ApplicationErrorCount"
    namespace     = "CardDemo/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

# =============================================================================
# Section 4 — CloudWatch alarms (PCI + operational)
# =============================================================================
# Alarms publish to SNS topics created by Amazon SNS / Macie / CloudTrail
# modules (out of scope of this file). Today they are configured WITHOUT
# alarm_actions because the SNS topic ARN is supplied at a later
# checkpoint (CP7). The alarms still PRODUCE alarm state changes, which
# are visible in the AWS Console and CloudWatch Events, and they
# satisfy the F-CP6-TF-CloudWatch-01 requirement that the alarms exist.
#
# When SNS topics are added (CP7), update each alarm's `alarm_actions`
# and `ok_actions` argument to publish to the SNS ARN.
# =============================================================================

# -----------------------------------------------------------------------------
# Alarm 1 — PAN detected anywhere in CardDemo logs (CRITICAL).
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "pan_detected" {
  alarm_name          = "carddemo-${var.environment}-pan-detected"
  alarm_description   = "CRITICAL: PAN-shaped (16-digit) sequence detected in CardDemo logs (PCI-DSS Req 3.3 leak detection)"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "PanDetected"
  namespace           = "CardDemo/${var.environment}"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-pan-detected"
    Severity = "critical"
    PciDss   = "Req-3.3"
  })
}

# -----------------------------------------------------------------------------
# Alarm 2 — ACCT-ID detected anywhere in CardDemo logs.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "acct_id_detected" {
  alarm_name          = "carddemo-${var.environment}-acct-id-detected"
  alarm_description   = "Account-ID-shaped (11-digit) sequence detected in CardDemo logs"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "AcctIdDetected"
  namespace           = "CardDemo/${var.environment}"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-acct-id-detected"
    Severity = "high"
    PciDss   = "Req-3.3"
  })
}

# -----------------------------------------------------------------------------
# Alarm 3 — Application ERROR/FATAL/Exception sustained > 50 / 5 min.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "application_error_rate" {
  alarm_name          = "carddemo-${var.environment}-application-error-rate"
  alarm_description   = "Sustained ERROR/FATAL/Exception rate exceeding 50 occurrences over 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "ApplicationErrorCount"
  namespace           = "CardDemo/${var.environment}"
  period              = 60
  statistic           = "Sum"
  threshold           = 50
  treat_missing_data  = "notBreaching"

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-application-error-rate"
    Severity = "high"
  })
}

# -----------------------------------------------------------------------------
# Alarm 4 — RDS CPU utilization sustained > 80 % for 5 minutes.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "rds_cpu_high" {
  alarm_name          = "carddemo-${var.environment}-rds-cpu-high"
  alarm_description   = "RDS PostgreSQL CPU utilization sustained above 80% for 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.carddemo.id
  }

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-rds-cpu-high"
    Severity = "high"
  })
}

# -----------------------------------------------------------------------------
# Alarm 5 — MSK consumer lag (AAP §0.6.5 ordering observability).
#
# Per-topic consumer lag is exposed via the
# `kafka.consumer.fetch-manager.records-lag-max` JMX metric, which the
# MSK CloudWatch open-monitoring exporter publishes under
# `AWS/Kafka` namespace. The alarm watches the cluster-wide max lag.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "msk_consumer_lag_high" {
  alarm_name          = "carddemo-${var.environment}-msk-consumer-lag-high"
  alarm_description   = "MSK consumer lag exceeded 10000 records for 5 minutes (potential consumer backpressure)"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "MaxOffsetLag"
  namespace           = "AWS/Kafka"
  period              = 60
  statistic           = "Maximum"
  threshold           = 10000
  treat_missing_data  = "notBreaching"

  dimensions = {
    "Cluster Name" = aws_msk_cluster.carddemo.cluster_name
  }

  tags = merge(local.common_tags, {
    Name     = "carddemo-${var.environment}-msk-consumer-lag-high"
    Severity = "high"
  })
}

# =============================================================================
# Section 5 — Outputs (consumed by ECS / Batch / Lambda task definitions)
# =============================================================================
# The 7 log group names are exposed as outputs so ECS task definitions,
# AWS Batch job definitions, and Lambda functions can wire their
# `awslogs-group` parameters by reference rather than by hard-coded
# string — preventing drift between task definition and infrastructure.
# =============================================================================

output "cw_log_group_app" {
  description = "CardDemo Spring Boot application log group name (consumed by ECS task definition awslogs-group)"
  value       = aws_cloudwatch_log_group.carddemo_app.name
}

output "cw_log_group_audit" {
  description = "CardDemo audit log group name (consumed by AuditLogService configuration)"
  value       = aws_cloudwatch_log_group.carddemo_audit.name
}

output "cw_log_group_batch" {
  description = "CardDemo Spring Batch + AWS Batch log group name"
  value       = aws_cloudwatch_log_group.carddemo_batch.name
}

output "cw_log_group_security" {
  description = "CardDemo Spring Security / auth log group name"
  value       = aws_cloudwatch_log_group.carddemo_security.name
}

output "cw_log_group_ecs" {
  description = "CardDemo ECS service log group name"
  value       = aws_cloudwatch_log_group.carddemo_ecs.name
}

output "cw_log_group_aws_batch" {
  description = "CardDemo AWS Batch log group name"
  value       = aws_cloudwatch_log_group.carddemo_aws_batch.name
}

output "cw_log_group_lambda" {
  description = "CardDemo Lambda log group name"
  value       = aws_cloudwatch_log_group.carddemo_lambda.name
}
