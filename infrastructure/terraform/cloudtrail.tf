###############################################################################
# infrastructure/terraform/cloudtrail.tf
#
# Purpose
#   Provisions the CardDemo organization-level AWS CloudTrail — the
#   immutable, tamper-evident audit log of every AWS API call and
#   infrastructure activity in the CardDemo deployment. Replaces the
#   COBOL audit-trail writes (transaction IDs, timestamps, operator
#   codes) that ran inside the mainframe data path (per AAP §0.6.6 and
#   §0.7.2) with a centralized, immutable, KMS-encrypted record stored
#   in S3 and replicated to CloudWatch Logs for OpenSearch ingestion.
#
# What this file provisions
#   1. aws_s3_bucket.cloudtrail_logs
#        Dedicated logs bucket (separate from the batch_outputs bucket and
#        from the general logs bucket) that receives CloudTrail's
#        write-once, integrity-validated log files. KMS-encrypted with
#        aws_kms_key.carddemo via SSE-KMS + bucket key, versioned, all
#        4 public-access-block settings true, with a 90-day Glacier
#        transition and a 2557-day (7-year) expiration to satisfy the
#        regulatory retention floor for PCI-DSS financial data.
#   2. aws_s3_bucket_policy.cloudtrail_logs
#        Bucket policy granting the cloudtrail.amazonaws.com service
#        principal s3:GetBucketAcl on the bucket itself
#        (AWSCloudTrailAclCheck) and s3:PutObject on the trail's
#        s3_key_prefix (AWSCloudTrailWrite). Both statements are
#        constrained by SourceArn = trail ARN (prevents the bucket from
#        being abused as a destination for an unrelated trail) and the
#        PutObject statement additionally requires
#        s3:x-amz-acl = bucket-owner-full-control. A blanket
#        DenyInsecureTransport TLS-only statement covers every principal.
#   3. aws_iam_role.cloudtrail_to_cloudwatch (+ inline policy)
#        Service role assumed by cloudtrail.amazonaws.com so the trail
#        can deliver events to the CloudWatch Logs log group
#        /aws/cloudtrail/carddemo-<env> defined in cloudwatch.tf.
#        Inline policy is scoped to logs:CreateLogStream + logs:PutLogEvents
#        on that single log group (least privilege).
#   4. aws_cloudtrail.carddemo
#        The trail resource itself. Multi-region, log-file-validated,
#        KMS-encrypted (aws_kms_key.carddemo), with management events,
#        S3 object data events on batch_outputs, Lambda invocation
#        events, and ApiCallRateInsight anomaly detection enabled.
#
# Mandatory PCI-DSS / AAP guarantees baked into the configuration
#   * enable_log_file_validation = true         (AAP §0.6.6)
#     CloudTrail produces hourly digest files that hash each log file
#     for tamper detection. Operators can verify integrity at any time
#     with `aws cloudtrail validate-logs`.
#   * is_multi_region_trail = true              (PCI-DSS)
#     Captures activity in every AWS region. Without this flag, regional
#     activity outside the trail's home region is unaudited — an audit gap.
#   * include_global_service_events = true      (AAP §0.6.6)
#     Captures IAM, STS, and CloudFront events (which are global).
#   * kms_key_id = aws_kms_key.carddemo.arn     (AAP §0.6.6, §0.7.1)
#     Log files are encrypted with the CardDemo CMK using
#     SSE-KMS-with-context (the trail ARN is the encryption context),
#     which prevents cross-trail decryption. The CMK's key policy
#     statement `AllowCloudTrail` (defined in kms.tf) grants the
#     cloudtrail.amazonaws.com service principal kms:GenerateDataKey*,
#     kms:Decrypt, and kms:DescribeKey under the
#     kms:EncryptionContext:aws:cloudtrail:arn condition.
#   * S3 bucket SSE-KMS + Block Public Access + TLS-only            (AAP §0.7.1)
#     The dedicated cloudtrail_logs bucket inherits the same SSE-KMS +
#     BPA + TLS-only posture as the batch_outputs bucket in s3.tf.
#   * 7-year retention                           (PCI-DSS Requirement 10.7)
#     Lifecycle expires objects at var.s3_lifecycle_expiration_days (the
#     default of 2555 days = ~7 years matches the regulatory floor).
#
# Inputs (from sibling .tf files)
#   * main.tf
#       - local.common_tags    : mandatory tag set merged onto every resource
#       - local.account_id     : 12-digit AWS account number for ARN construction
#       - local.partition      : "aws" / "aws-us-gov" / "aws-cn" for ARN prefixes
#       - data.aws_caller_identity.current : alternate access to account_id
#                                            (referenced in the Lambda data_resource
#                                            ARN pattern per the agent prompt)
#   * variables.tf
#       - var.environment      : drives trail name, key prefix, role name
#       - var.aws_region       : Lambda function ARN pattern + region-aware identifiers
#   * kms.tf
#       - aws_kms_key.carddemo : KMS CMK for trail + bucket encryption (AAP §0.6.6)
#                                The kms.tf "AllowCloudTrail" key policy statement
#                                already grants kms:GenerateDataKey* / Decrypt /
#                                DescribeKey to cloudtrail.amazonaws.com under the
#                                kms:EncryptionContext:aws:cloudtrail:arn condition.
#   * s3.tf
#       - aws_s3_bucket.batch_outputs : referenced by event_selector data_resource
#                                       to capture PCI-relevant S3 object events
#                                       (DALYREJS / SYSTRAN / TRANREPT / STMTFILE /
#                                       TRANSACT.BKUP).
#   * cloudwatch.tf
#       - aws_cloudwatch_log_group.cloudtrail : secondary delivery channel for
#                                               metric-filter / alarm / OpenSearch
#                                               ingestion consumers.
#
# Coordination with other .tf files
#   * The CMK key policy in kms.tf must contain the AllowCloudTrail
#     statement (already in place at kms.tf:L500-L521).
#   * cloudwatch.tf provides the cloudtrail log group; this file
#     references it via `${aws_cloudwatch_log_group.cloudtrail.arn}:*`.
#   * opensearch.tf consumes CloudTrail events indirectly via a separate
#     Lambda or Kinesis Firehose subscription configured outside this file.
#   * outputs.tf (if added later) exports `aws_cloudtrail.carddemo.arn`
#     so the Spring Boot application can populate the CLOUDTRAIL_TRAIL_ARN
#     environment variable.
#
# References
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS:
#     CloudTrail organization trail, KMS encryption, log-file integrity
#     validation, OpenSearch replication.
#   * AAP §0.7.2 — AWS CloudTrail provides immutable, tamper-evident
#     audit log of all API and infrastructure activity.
#   * agent prompt — "Rules and Constraints (Critical)": enable_log_file_
#     validation, kms_key_id, is_multi_region_trail, include_global_service_
#     events, enable_logging all mandatory; no data events on cloudtrail_logs
#     itself (would create a recursive billing/event loop).
###############################################################################

# =============================================================================
# Section 1 — Dedicated CloudTrail logs S3 bucket
# =============================================================================
# CloudTrail writes hourly log files (plus integrity-digest files) into a
# dedicated S3 bucket. The bucket is deliberately separated from the
# general logs bucket (aws_s3_bucket.logs in s3.tf) and from the batch
# outputs bucket (aws_s3_bucket.batch_outputs) so that:
#
#   * The bucket policy can be tightened to the CloudTrail-specific
#     AWSCloudTrailAclCheck / AWSCloudTrailWrite statements without
#     leaking those permissions to other writers (S3 server access
#     logging, batch jobs).
#   * Lifecycle, retention, and access reviews can be administered
#     independently — CloudTrail logs are the audit-of-record and must
#     survive even if other log destinations are reconfigured.
#   * Macie scan scheduling, regulatory retention proofs, and
#     log-file integrity validation can be aligned on a single,
#     CloudTrail-only bucket.
#
# Naming convention: carddemo-<env>-cloudtrail-logs-<account_id>. The
# <account_id> suffix guarantees global uniqueness in the S3 namespace.
# =============================================================================

resource "aws_s3_bucket" "cloudtrail_logs" {
  bucket = "carddemo-${var.environment}-cloudtrail-logs-${local.account_id}"

  # Force deletion is permitted in non-prod environments (dev / staging)
  # to allow rapid `terraform destroy` during iteration. In prod the flag
  # is FALSE so destroy will refuse while objects remain — the
  # operational safety net for the audit-of-record. An operator can
  # still manually empty the bucket if intentional destruction is
  # required (an explicit, audited action that itself is captured by
  # CloudTrail until the moment the bucket is emptied).
  #
  # NOTE on Object Lock interaction with force_destroy:
  #   When object_lock_enabled = true and the retention mode is
  #   COMPLIANCE, terraform destroy CANNOT delete objects until their
  #   retention windows have elapsed — not even with
  #   force_destroy = true. This is the intended PCI-DSS audit-of-
  #   record protection. In dev/staging where force_destroy is true,
  #   COMPLIANCE-locked objects must age out naturally (per
  #   var.cloudtrail_s3_object_lock_retention_days) before the bucket
  #   can be destroyed. For rapid iteration in lab environments
  #   consider setting var.cloudtrail_s3_object_lock_mode = "GOVERNANCE"
  #   so a principal with s3:BypassGovernanceRetention can issue a
  #   bypass-versioned delete.
  force_destroy = var.environment != "prod"

  # ---------------------------------------------------------------------------
  # S3 Object Lock — storage-layer WORM (write-once-read-many)
  # immutability for the CloudTrail audit-of-record bucket.
  # ---------------------------------------------------------------------------
  # Added in response to QA Checkpoint 9 Issue 2 (LOW —
  # Defence-in-Depth):
  #
  #   "CloudTrail S3 bucket lacks Object Lock / MFA Delete. The
  #    user-specified checkpoint instructions reference 'MFA delete
  #    or Object Lock' as preferred immutability mechanism. Current
  #    implementation relies on log file integrity validation +
  #    bucket versioning + KMS encryption + restrictive bucket
  #    policy with SourceArn constraint. While these provide
  #    strong tamper-evidence and access control, S3 Object Lock
  #    would provide cryptographic WORM at the storage layer."
  #
  # Object Lock cooperates with the existing controls to form a
  # defence-in-depth stack:
  #
  #   1. CloudTrail log file integrity validation (hash chain) —
  #      tamper detection (existing).
  #   2. S3 versioning — preserves any pre-Object-Lock object
  #      versions and supports the noncurrent_version_* lifecycle
  #      rules (existing).
  #   3. SSE-KMS with the CardDemo CMK — encryption at rest
  #      (existing).
  #   4. Bucket policy SourceArn condition + DenyInsecureTransport
  #      — access control + TLS-only (existing).
  #   5. Object Lock COMPLIANCE retention (NEW) — storage-layer
  #      WORM that prevents ANY principal, including the root
  #      account, from deleting or modifying an object until its
  #      retention period elapses. This is the strongest
  #      immutability guarantee S3 offers and is the PCI-DSS
  #      Requirement 10.5 recommended posture for audit-of-record
  #      buckets.
  #
  # CRITICAL AWS CONSTRAINT — Object Lock can ONLY be enabled at
  # bucket CREATION time. The `object_lock_enabled` attribute is
  # immutable on existing buckets. For deployments where the
  # CloudTrail bucket already exists WITHOUT Object Lock enabled,
  # toggling var.cloudtrail_s3_object_lock_enabled to true forces
  # Terraform to REPLACE the bucket (destroy old, create new). This
  # has two operational consequences:
  #
  #   (a) Existing log objects in the old bucket are NOT migrated
  #       automatically. Before applying the change in production,
  #       an operator must run an S3 Batch Replication job (or
  #       `aws s3 sync`) to copy historical logs into the new
  #       bucket, OR retain the old bucket as a read-only archive
  #       for the remainder of its lifecycle.
  #
  #   (b) The bucket policy referencing the trail's SourceArn must
  #       be re-attached to the new bucket. Terraform handles this
  #       automatically via the existing aws_s3_bucket_policy
  #       resource, but the CloudTrail itself will fail log delivery
  #       for any window between bucket destroy and policy reapply
  #       — schedule the apply during an audit-tolerant maintenance
  #       window and confirm log delivery resumes via
  #       `aws cloudtrail get-trail-status --name <trail>`.
  #
  # The brownfield migration runbook for prod is captured at
  # ../README.md#cloudtrail-object-lock-brownfield-migration
  # (sibling runbook to the MFA Delete procedure already
  # referenced from this file). For greenfield deployments
  # (terraform apply on a fresh account/environment) the
  # configuration is fully automated and no manual steps are
  # required.
  #
  # The variable default is true (secure-by-default), reflecting
  # AAP §0.7.1 ("Encrypt all S3 buckets with SSE-KMS and block
  # public access") and the QA Checkpoint 9 Issue 2 finding's
  # recommendation. Set var.cloudtrail_s3_object_lock_enabled =
  # false ONLY to preserve a legacy pre-Object-Lock bucket
  # without forcing replacement.
  object_lock_enabled = var.cloudtrail_s3_object_lock_enabled

  tags = merge(local.common_tags, {
    Name      = "${local.resource_name_prefix}-cloudtrail-logs"
    DataClass = "Audit"
    Purpose   = "CloudTrail log file delivery (immutable audit-of-record per AAP §0.6.6, Object Lock WORM per QA CP9 Issue 2)"
    Region    = var.aws_region
  })
}

# -----------------------------------------------------------------------------
# Versioning enabled — every overwrite or delete event creates a new
# version. Critical for an audit-of-record bucket: if an object were
# silently overwritten or deleted (e.g., by an attacker who somehow
# acquired write credentials), the prior version remains accessible
# until aged out by the lifecycle policy below. Combined with the
# write-once nature of CloudTrail itself and the TLS-only + BPA posture,
# this delivers forensic-grade preservation.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_versioning" "cloudtrail_logs" {
  bucket = aws_s3_bucket.cloudtrail_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

# -----------------------------------------------------------------------------
# S3 Object Lock configuration — storage-layer WORM (write-once-read-many)
# defence-in-depth on top of the existing log file integrity validation +
# versioning + KMS + bucket policy controls.
#
# Added in QA Checkpoint 9 Issue 2 (LOW — Defence-in-Depth) remediation.
# See the bucket-resource comment block above for the full rationale,
# the AWS bucket-creation-time constraint, and the brownfield migration
# procedure.
#
# Behaviour summary:
#   * The default retention rule applies to every new object written
#     to the bucket (CloudTrail log files and digest files).
#   * Mode COMPLIANCE means objects cannot be deleted or modified
#     before retention elapses, not even by the root account. This
#     is the PCI-DSS Requirement 10.5 audit-trail-integrity posture.
#   * Mode GOVERNANCE means principals with the
#     s3:BypassGovernanceRetention IAM permission can delete during
#     the retention window. Only configure GOVERNANCE when the
#     security review board has explicitly approved emergency-
#     override capability.
#   * Retention period is configurable via
#     var.cloudtrail_s3_object_lock_retention_days. The default
#     2557 days (~7 years) matches var.s3_lifecycle_expiration_days
#     so every object is immutable for its full retained lifetime.
#     The lifecycle rule deletes objects AFTER their retention
#     expires; Object Lock prevents deletion BEFORE that point.
#
# Conditional resource gating:
#   This resource is created ONLY when
#   var.cloudtrail_s3_object_lock_enabled = true. The companion
#   `object_lock_enabled = var.cloudtrail_s3_object_lock_enabled`
#   attribute on the bucket resource above ensures the bucket is
#   created with Object Lock support; the AWS S3 API rejects
#   PutObjectLockConfiguration on buckets that were NOT created
#   with object_lock_enabled = true, so the count gate keeps
#   Terraform's plan consistent when the toggle is set false.
#
# Dependencies:
#   * Object Lock requires bucket versioning (already configured
#     via aws_s3_bucket_versioning.cloudtrail_logs immediately
#     above). The depends_on declaration makes the ordering
#     explicit so Terraform never attempts to apply the lock
#     configuration before versioning is in place.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_object_lock_configuration" "cloudtrail_logs" {
  count = var.cloudtrail_s3_object_lock_enabled ? 1 : 0

  bucket = aws_s3_bucket.cloudtrail_logs.id

  rule {
    default_retention {
      mode = var.cloudtrail_s3_object_lock_mode
      days = var.cloudtrail_s3_object_lock_retention_days
    }
  }

  # Versioning must be Enabled before Object Lock configuration can
  # be applied; this dependency makes the ordering explicit so
  # `terraform apply` cannot attempt the lock configuration first.
  depends_on = [aws_s3_bucket_versioning.cloudtrail_logs]
}

# -----------------------------------------------------------------------------
# SSE-KMS encryption with the CardDemo primary CMK. CloudTrail log files
# are PII / PCI-sensitive (they reveal which principals accessed which
# resources at which times) so encryption at rest is mandatory per
# AAP §0.7.1 ("Encrypt all S3 buckets with SSE-KMS and block public
# access"). bucket_key_enabled = true caches a per-bucket data key from
# the CMK for up to 24 hours, dramatically reducing KMS API call costs
# on the high-volume CloudTrail log write path.
#
# The CMK's key policy `AllowCloudTrail` statement (kms.tf:L500-L521)
# already grants cloudtrail.amazonaws.com kms:GenerateDataKey* /
# kms:Decrypt / kms:DescribeKey under the
# kms:EncryptionContext:aws:cloudtrail:arn condition — no additional
# grant required here.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_server_side_encryption_configuration" "cloudtrail_logs" {
  bucket = aws_s3_bucket.cloudtrail_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.carddemo.arn
    }

    # Bucket Key reduces KMS request volume by amortising the data key
    # across many objects within the bucket. Without this, every log
    # file write would generate a kms:GenerateDataKey call to the CMK,
    # which becomes a measurable cost on a high-volume CloudTrail.
    bucket_key_enabled = true
  }
}

# -----------------------------------------------------------------------------
# Block Public Access — all four settings TRUE. Mandatory per AAP §0.7.1
# ("Encrypt all S3 buckets with SSE-KMS and block public access") and
# PCI-DSS Requirement 1 (firewall configuration). Even an inadvertent
# bucket policy change cannot expose CloudTrail logs publicly with all
# four flags set.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_public_access_block" "cloudtrail_logs" {
  bucket = aws_s3_bucket.cloudtrail_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Bucket policy — three statements:
#
#   1. AWSCloudTrailAclCheck (Allow s3:GetBucketAcl on the bucket itself
#      to the cloudtrail.amazonaws.com service principal, conditioned on
#      aws:SourceArn = trail ARN). CloudTrail calls GetBucketAcl once
#      before each log delivery to confirm the bucket exists and is
#      accessible.
#
#   2. AWSCloudTrailWrite (Allow s3:PutObject on the trail's s3_key_prefix
#      path under AWSLogs/<account_id>/* to the same service principal,
#      additionally requiring the s3:x-amz-acl header = bucket-owner-full-
#      control so written objects are owned by the bucket owner — a
#      best-practice for centralized log buckets receiving writes from
#      AWS services).
#
#   3. DenyInsecureTransport (TLS-only — denies any action against the
#      bucket or its objects when aws:SecureTransport = false).
#
# The SourceArn condition on statements 1 and 2 is the "confused deputy"
# defense recommended by the AWS CloudTrail documentation: it prevents
# the bucket from being abused as a destination for an unrelated
# CloudTrail in a different account or region.
#
# Rendered via aws_iam_policy_document data source for two reasons:
#   * Terraform-side policy syntax validation at plan time.
#   * Cleaner HCL than an inline jsonencode({}) heredoc, which is the
#     standard pattern used across the rest of this module (see
#     iam.tf and kms.tf).
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "cloudtrail_logs_bucket" {
  # ---------------------------------------------------------------------------
  # Statement 1 — AWSCloudTrailAclCheck.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AWSCloudTrailAclCheck"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.cloudtrail_logs.arn]

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:cloudtrail:${var.aws_region}:${local.account_id}:trail/carddemo-${var.environment}-trail"]
    }
  }

  # ---------------------------------------------------------------------------
  # Statement 2 — AWSCloudTrailWrite.
  #
  # The Resource path matches CloudTrail's mandatory write path:
  #   <bucket>/<s3_key_prefix>/AWSLogs/<account_id>/*
  # where s3_key_prefix is "carddemo-<env>" per the trail config below.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AWSCloudTrailWrite"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.cloudtrail_logs.arn}/carddemo-${var.environment}/AWSLogs/${local.account_id}/*"]

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:cloudtrail:${var.aws_region}:${local.account_id}:trail/carddemo-${var.environment}-trail"]
    }
  }

  # ---------------------------------------------------------------------------
  # Statement 3 — DenyInsecureTransport (TLS-only).
  #
  # Denies any access to the bucket or its objects when the request is
  # not made over HTTPS. Applies to ALL principals (including the
  # CloudTrail service principal — though CloudTrail always uses TLS
  # natively, this is defense in depth).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.cloudtrail_logs.arn,
      "${aws_s3_bucket.cloudtrail_logs.arn}/*"
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "cloudtrail_logs" {
  bucket = aws_s3_bucket.cloudtrail_logs.id
  policy = data.aws_iam_policy_document.cloudtrail_logs_bucket.json

  # Bucket policies that reference Principal="*" (the TLS-only Deny)
  # can be rejected by S3 if the Block-Public-Policy setting hasn't
  # been applied yet. Force the ordering so the BPA settings are in
  # place before the policy is attached.
  depends_on = [aws_s3_bucket_public_access_block.cloudtrail_logs]
}

# -----------------------------------------------------------------------------
# Lifecycle configuration — CloudTrail logs follow the same write-once
# read-rarely access pattern as the general logs bucket. The transitions
# match the pattern used by aws_s3_bucket_lifecycle_configuration.logs
# in s3.tf:
#
#   * STANDARD_IA at 30 days   (infrequent-access tier; ~40% cheaper
#                                 storage; small per-GB retrieval fee).
#   * GLACIER at 90 days       (long-term archive; ~80% cheaper storage;
#                                 standard retrieval 3-5 hours).
#   * Expire at the regulatory floor (var.s3_lifecycle_expiration_days,
#                                 default 2555 days = 7 years per the
#                                 PCI-DSS Requirement 10.7 retention
#                                 floor — see AAP §0.7.2).
#
# Non-current versions (created by the versioning configuration on
# overwrite or delete) are migrated to GLACIER at 30 days and expire
# at 365 days, matching the s3.tf pattern.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "cloudtrail_logs" {
  bucket = aws_s3_bucket.cloudtrail_logs.id

  rule {
    id     = "cloudtrail-logs-7-year-retention"
    status = "Enabled"

    # Empty filter applies the rule bucket-wide. CloudTrail writes log
    # files and digest files under the same prefix tree
    # (carddemo-<env>/AWSLogs/<account_id>/CloudTrail/* and
    # carddemo-<env>/AWSLogs/<account_id>/CloudTrail-Digest/*), so the
    # single bucket-wide rule covers both.
    filter {
      prefix = ""
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 90
      storage_class = "GLACIER"
    }

    expiration {
      days = var.s3_lifecycle_expiration_days
    }

    noncurrent_version_transition {
      noncurrent_days = 30
      storage_class   = "GLACIER"
    }

    noncurrent_version_expiration {
      noncurrent_days = 365
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Lifecycle rules with noncurrent_version_* configurations require
  # the versioning configuration to be applied first; this dependency
  # makes the ordering explicit.
  depends_on = [aws_s3_bucket_versioning.cloudtrail_logs]
}

# =============================================================================
# Section 2 — IAM role for CloudTrail -> CloudWatch Logs delivery
# =============================================================================
# CloudTrail can stream every event it captures to a CloudWatch Logs
# log group in real time (in addition to the primary S3 log file
# delivery). This unlocks:
#
#   * CloudWatch metric filters (e.g., alert on "ConsoleLogin without MFA",
#     "DeleteTrail", "UpdateAssumeRolePolicy") with corresponding alarms
#     defined in cloudwatch.tf.
#   * OpenSearch subscription via the AWS-supplied CloudWatch Logs ->
#     OpenSearch subscription filter, indexing CloudTrail events for
#     near-real-time fraud investigation queries (per AAP §0.6.6 —
#     "Amazon OpenSearch indexes transaction logs and CloudTrail events
#     for regulatory queries and fraud investigation").
#   * Real-time downstream consumers (Lambda, Kinesis Firehose) via
#     the log group's subscription filter mechanism.
#
# CloudTrail itself does not directly write to CloudWatch Logs — it
# assumes a service role that has logs:CreateLogStream + logs:PutLogEvents
# on the destination log group. This file provisions:
#
#   1. The trust policy (assume role by cloudtrail.amazonaws.com).
#   2. The IAM role (aws_iam_role.cloudtrail_to_cloudwatch).
#   3. The inline policy granting CreateLogStream + PutLogEvents on the
#      cloudtrail log group only (least privilege — no wildcards).
#
# The cloudtrail log group itself is defined in cloudwatch.tf
# (aws_cloudwatch_log_group.cloudtrail, retention = 400 days,
# encrypted with aws_kms_key.carddemo).
# =============================================================================

# -----------------------------------------------------------------------------
# Trust policy — cloudtrail.amazonaws.com is the ONLY principal allowed
# to assume this role. The SourceArn / SourceAccount conditions are the
# confused-deputy defense: even if another account's CloudTrail somehow
# attempted to assume this role, the StringEquals condition rejects the
# call because the SourceArn must equal this trail's ARN.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "cloudtrail_to_cloudwatch_assume" {
  statement {
    sid     = "CloudTrailAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    # SourceArn must equal this trail's ARN (constructed from the same
    # naming convention used by aws_cloudtrail.carddemo below). Note
    # that we cannot reference aws_cloudtrail.carddemo.arn directly
    # here because that would create a circular dependency
    # (the trail itself references this role via cloud_watch_logs_role_arn).
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:cloudtrail:${var.aws_region}:${local.account_id}:trail/carddemo-${var.environment}-trail"]
    }

    # SourceAccount belt-and-braces: the assuming CloudTrail must be in
    # the same AWS account as this role.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

# -----------------------------------------------------------------------------
# The role itself.
# -----------------------------------------------------------------------------
resource "aws_iam_role" "cloudtrail_to_cloudwatch" {
  name               = "carddemo-${var.environment}-cloudtrail-to-cw"
  description        = "Service role assumed by cloudtrail.amazonaws.com to deliver events to the CloudWatch Logs cloudtrail log group (per AAP §0.6.6)"
  assume_role_policy = data.aws_iam_policy_document.cloudtrail_to_cloudwatch_assume.json

  # Maximum session duration for sts:AssumeRole tokens. Default 1h is
  # adequate; CloudTrail refreshes its credentials transparently.
  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-cloudtrail-to-cw"
    Purpose = "CloudTrail -> CloudWatch Logs delivery role (AAP §0.6.6)"
  })
}

# -----------------------------------------------------------------------------
# Inline policy — logs:CreateLogStream + logs:PutLogEvents on the
# cloudtrail log group (and its sub-streams) ONLY. No wildcard
# Resources; no other log groups; no other actions. This is the
# minimum permission set documented by the CloudTrail integration
# with CloudWatch Logs.
#
# The Resource pattern includes both the log group ARN and the
# wildcard ARN with `:*` suffix so the role can create new log
# streams inside the group (one stream per region per CloudTrail).
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "cloudtrail_to_cloudwatch_write" {
  statement {
    sid    = "WriteCloudTrailEventsToLogGroup"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents"
    ]
    resources = [
      "${aws_cloudwatch_log_group.cloudtrail.arn}:*"
    ]
  }
}

resource "aws_iam_role_policy" "cloudtrail_to_cloudwatch" {
  name   = "cloudtrail-to-cloudwatch-write"
  role   = aws_iam_role.cloudtrail_to_cloudwatch.id
  policy = data.aws_iam_policy_document.cloudtrail_to_cloudwatch_write.json
}

# =============================================================================
# Section 3 — CloudTrail trail
# =============================================================================
# The trail itself. Captures:
#
#   * Management events (read AND write) for every AWS service.
#   * Data events on the batch_outputs S3 bucket — every GetObject,
#     PutObject, DeleteObject against DALYREJS, SYSTRAN, TRANREPT,
#     STMTFILE, and TRANSACT.BKUP outputs. Required by PCI-DSS for
#     audit-of-record over cardholder data accesses.
#   * Data events on every Lambda function in this account (catches
#     invocations of the Secrets Manager rotation Lambdas defined in
#     secrets.tf and any future event-driven Lambdas).
#   * Insight events for the ApiCallRateInsight analytic — automated
#     anomaly detection on unusual API call patterns, surfacing as
#     CloudTrail Insights events in the same destinations.
#
# Notably, data events are NOT captured on the cloudtrail_logs bucket
# itself — that would create a recursive event loop (every CloudTrail
# write to S3 generates a data event, which CloudTrail itself would
# then deliver to S3 as a new event, etc.). This is the constraint
# called out in the agent prompt under "Rules and Constraints".
#
# Mandatory flags per AAP §0.6.6 + agent prompt:
#   * enable_log_file_validation = true    — tamper-evident hash chain
#   * is_multi_region_trail      = true    — captures all regions
#   * include_global_service_events = true — captures IAM / STS / CloudFront
#   * enable_logging             = true    — explicit (default but mandatory)
#   * kms_key_id                 = <CardDemo CMK> — encrypted at rest with KMS
# =============================================================================

resource "aws_cloudtrail" "carddemo" {
  name = "carddemo-${var.environment}-trail"

  # ---------------------------------------------------------------------------
  # Primary S3 destination — dedicated cloudtrail_logs bucket defined
  # above. s3_key_prefix produces a per-environment top-level folder
  # within the bucket so multiple trails (if ever introduced) can share
  # the bucket without colliding.
  # ---------------------------------------------------------------------------
  s3_bucket_name = aws_s3_bucket.cloudtrail_logs.id
  s3_key_prefix  = "carddemo-${var.environment}"

  # ---------------------------------------------------------------------------
  # MANDATORY PCI-DSS / AAP §0.6.6 flags — all explicit even when they
  # match the AWS API default.
  # ---------------------------------------------------------------------------
  # Captures IAM, STS, CloudFront, Route 53, and other globally-scoped
  # services. Without this, those services would be unaudited.
  include_global_service_events = true

  # Captures activity across every AWS region — not just the home region.
  is_multi_region_trail = true

  # Organization-level trail (PCI-DSS hardening — Code Review CP7 fix).
  #
  # When CardDemo is deployed inside an AWS Organizations / Control
  # Tower-enabled account, setting is_organization_trail = true causes
  # the trail to capture API activity from EVERY member account, not
  # just the deployer's. This is the audit posture that PCI-DSS
  # Requirement 10 expects in a multi-account landing zone and the
  # Checkpoint 7 review explicitly called for.
  #
  # The default value of var.cloudtrail_is_organization_trail is false
  # so single-account demo / development environments work without
  # the additional Organizations privileges required to create an
  # org-level trail. Production deployments set the variable to true
  # via the prod tfvars overlay.
  #
  # Operational note — bucket immutability controls (MFA Delete +
  # Object Lock) on the cloudtrail_logs bucket:
  #
  #   (1) S3 Object Lock — ENABLED BY DEFAULT via
  #       var.cloudtrail_s3_object_lock_enabled = true. This adds
  #       storage-layer WORM (write-once-read-many) with COMPLIANCE
  #       retention by default (configurable via
  #       var.cloudtrail_s3_object_lock_mode). See the bucket and
  #       configuration resources above for full detail. This was
  #       added in QA Checkpoint 9 Issue 2 remediation. Object Lock
  #       can ONLY be enabled at bucket creation time; brownfield
  #       enablement requires bucket replacement (procedure
  #       documented at
  #       ../README.md#cloudtrail-object-lock-brownfield-migration).
  #
  #   (2) S3 MFA Delete — STILL MANUAL. MFA Delete cannot be enabled
  #       via Terraform / SDK; it requires an
  #       `aws s3api put-bucket-versioning` call performed by the
  #       root user with an active MFA token. The CardDemo runbook
  #       documents the manual enablement procedure under
  #       ../README.md#mfa-delete-on-cloudtrail-bucket. Until that
  #       manual step is performed, the bucket retains the standard
  #       versioning guarantee from
  #       `aws_s3_bucket_versioning.cloudtrail_logs` above plus the
  #       Object Lock COMPLIANCE retention. Object Lock is the
  #       PRIMARY immutability guarantee; MFA Delete adds a final
  #       layer of defence specifically against root-account
  #       version-deletion attacks. Operators should complete the
  #       MFA Delete step BEFORE production cutover per the Code
  #       Review CP7 audit finding and the QA CP9 Issue 2 finding.
  is_organization_trail = var.cloudtrail_is_organization_trail

  # Hourly digest files with SHA-256 hashes of each log file; operators
  # can verify the integrity of any log file at any point in time with
  # `aws cloudtrail validate-logs --trail-arn <arn> --start-time <t>
  # --end-time <t+1h>`. Mandatory per AAP §0.6.6.
  enable_log_file_validation = true

  # Customer-managed KMS key for log file encryption. The CMK key policy
  # in kms.tf already grants the cloudtrail.amazonaws.com service
  # principal the required kms:GenerateDataKey* / kms:Decrypt /
  # kms:DescribeKey actions under the
  # kms:EncryptionContext:aws:cloudtrail:arn condition.
  kms_key_id = aws_kms_key.carddemo.arn

  # Explicit logging-on. AWS defaults this to true on resource creation,
  # but the agent prompt requires it to be explicit so operators reading
  # this file can see the state at a glance.
  enable_logging = true

  # ---------------------------------------------------------------------------
  # CloudWatch Logs delivery channel — events stream to the cloudtrail
  # log group in real time, enabling metric-filter-based alarms and
  # OpenSearch ingestion. The ":*" suffix on the log group ARN matches
  # all log streams within the group (one stream per region for a
  # multi-region trail).
  # ---------------------------------------------------------------------------
  cloud_watch_logs_group_arn = "${aws_cloudwatch_log_group.cloudtrail.arn}:*"
  cloud_watch_logs_role_arn  = aws_iam_role.cloudtrail_to_cloudwatch.arn

  # ---------------------------------------------------------------------------
  # Event selector 1 — management events.
  #
  # Captures every read AND write management API call across every AWS
  # service. include_management_events MUST be true for this selector
  # to have any effect. Data events are excluded from this selector
  # because data_resource is reserved for the S3 / Lambda selectors
  # below.
  # ---------------------------------------------------------------------------
  event_selector {
    read_write_type           = "All"
    include_management_events = true
  }

  # ---------------------------------------------------------------------------
  # Event selector 2 — S3 object data events on batch_outputs.
  #
  # Captures every object-level read AND write against the batch_outputs
  # bucket. The trailing "/" on the values entry is significant: it
  # scopes the selector to ALL objects within the bucket (the prefix
  # match starts at the bucket root).
  #
  # Deliberately omits the cloudtrail_logs bucket from the values list:
  # capturing data events on that bucket would create a recursive
  # event loop (each CloudTrail log file write generates a PutObject
  # data event, which itself is delivered as a new log file, which
  # generates another PutObject event, ad infinitum). The constraint
  # is called out by the agent prompt under "Rules and Constraints".
  #
  # include_management_events = false because management events are
  # already captured by selector 1; setting both selectors to true
  # would produce duplicate management events.
  # ---------------------------------------------------------------------------
  event_selector {
    read_write_type           = "All"
    include_management_events = false

    data_resource {
      type = "AWS::S3::Object"
      values = [
        "${aws_s3_bucket.batch_outputs.arn}/"
      ]
    }
  }

  # ---------------------------------------------------------------------------
  # Event selector 3 — Lambda function invocation events (account-wide).
  #
  # Captures every Invoke API call against every Lambda function in
  # this account, identified by the account-wide wildcard ARN. This
  # includes the Secrets Manager rotation Lambdas (provisioned by
  # secrets.tf) and any future event-driven Lambdas.
  #
  # The ARN pattern uses data.aws_caller_identity.current.account_id
  # rather than local.account_id intentionally — the agent prompt
  # explicitly specifies this access path, and the literal ARN form
  # makes the intent clear at the call site.
  # ---------------------------------------------------------------------------
  event_selector {
    read_write_type           = "All"
    include_management_events = false

    data_resource {
      type = "AWS::Lambda::Function"
      values = [
        "arn:${local.partition}:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:function:*"
      ]
    }
  }

  # ---------------------------------------------------------------------------
  # Insight selector — ApiCallRateInsight anomaly detection.
  #
  # CloudTrail Insights analyses API call rates across a baseline
  # window (the prior 7 days) and emits Insight events when the
  # current rate deviates from the baseline by a statistically
  # significant margin (more than 3 standard deviations). Surfaces
  # in the trail's S3 + CloudWatch Logs destinations with the same
  # delivery guarantees as standard events. Useful for early
  # detection of denial-of-service, credential abuse, or runaway
  # client retry loops.
  # ---------------------------------------------------------------------------
  insight_selector {
    insight_type = "ApiCallRateInsight"
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-trail"
    Purpose = "Organization-level audit trail (PCI-DSS Requirement 10, AAP §0.6.6, §0.7.2)"
  })

  # ---------------------------------------------------------------------------
  # Explicit dependency on the bucket policy — CloudTrail performs a
  # synchronous s3:GetBucketAcl call against the destination bucket
  # at trail creation time to validate write access. If the bucket
  # policy isn't yet attached, the call fails and `terraform apply`
  # errors out. The implicit dependency through `s3_bucket_name`
  # references only the bucket itself, not the policy, so we add
  # the explicit edge here.
  #
  # The IAM role inline policy is similarly required at creation time
  # because CloudTrail validates the role's permissions on the
  # destination log group.
  # ---------------------------------------------------------------------------
  depends_on = [
    aws_s3_bucket_policy.cloudtrail_logs,
    aws_iam_role_policy.cloudtrail_to_cloudwatch
  ]
}
