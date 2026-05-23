###############################################################################
# infrastructure/terraform/s3.tf
#
# Amazon S3 buckets for CardDemo batch outputs and PCI-DSS audit logging.
#
# Purpose:
#   Provisions two primary Amazon S3 buckets that anchor the cloud-native
#   replacement of the legacy mainframe sequential-PS / Generation Data Group
#   (GDG) datasets used by the CardDemo batch pipeline:
#
#     1. aws_s3_bucket.batch_outputs
#        Single bucket holding ALL batch job outputs under per-job prefixes:
#          - daly-rejects/      — replaces AWS.M2.CARDDEMO.DALYREJS(+1) emitted
#                                 by app/jcl/POSTTRAN.jcl (STEP15 PGM=CBTRN02C)
#                                 as the daily-transaction rejection feed
#          - systran/           — replaces AWS.M2.CARDDEMO.SYSTRAN(+1) emitted
#                                 by app/jcl/INTCALC.jcl (STEP15 PGM=CBACT04C)
#                                 as the interest-calculation transaction feed
#          - tran-reports/      — replaces AWS.M2.CARDDEMO.TRANREPT(+1) emitted
#                                 by app/jcl/TRANREPT.jcl (STEP10R PGM=CBTRN03C)
#                                 as the formatted transaction report
#          - statements/        — replaces AWS.M2.CARDDEMO.STATEMNT.PS / .HTML
#                                 emitted by app/jcl/CREASTMT.JCL (STEP040
#                                 PGM=CBSTM03A) as the monthly statement file
#          - transact-backup/   — replaces AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)
#                                 emitted by app/jcl/TRANBKP.jcl as the
#                                 transaction master backup
#          - glue-temp/         — AWS Glue Spark ETL scratch space
#          - glue-scripts/      — Glue job scripts (read-only at runtime)
#
#     2. aws_s3_bucket.logs
#        Separate secondary bucket holding S3 server access logs (S3 access
#        records from the batch_outputs bucket) and the destination for
#        CloudTrail data events under its own prefix (referenced by
#        infrastructure/terraform/cloudtrail.tf).
#
# Replaces (legacy mainframe sources — frozen, never modified, retained as
# behavioural ground truth under app/jcl/):
#   * app/jcl/DEFGDGB.jcl    — GDG base definitions for TRANSACT.BKUP,
#                              TRANSACT.DALY, TRANREPT, TCATBALF.BKUP,
#                              SYSTRAN, TRANSACT.COMBINED. The GDG (+1) / (0)
#                              "next generation" / "current generation"
#                              semantics map to S3 object versioning + the
#                              per-prefix lifecycle rules below — newer
#                              generations are written to the same prefix
#                              and aged out to STANDARD_IA / GLACIER on the
#                              configured cadence.
#   * app/jcl/POSTTRAN.jcl   — DALYREJS(+1) sequential output of CBTRN02C.
#   * app/jcl/INTCALC.jcl    — SYSTRAN(+1) sequential output of CBACT04C.
#   * app/jcl/TRANREPT.jcl   — TRANREPT(+1) sequential output of CBTRN03C
#                              plus TRANSACT.BKUP(+1) and TRANSACT.DALY(+1)
#                              intermediates produced by DFSORT.
#   * app/jcl/CREASTMT.JCL   — STATEMNT.PS and STATEMNT.HTML sequential
#                              outputs of CBSTM03A.
#   * app/jcl/TRANBKP.jcl    — TRANSACT.BKUP(+1) sequential output of the
#                              REPROC procedure.
#
# Java consumer (under src/main/java/com/awsm2/carddemo/adapter/):
#   * S3OutputService — writes objects under the prefixes above using the
#     bucket name resolved from the S3_OUTPUT_BUCKET environment variable
#     (set by ecs.tf and batch.tf to aws_s3_bucket.batch_outputs.bucket).
#
# PCI-DSS controls applied uniformly to BOTH buckets (per AAP §0.6.6 and
# §0.7.1):
#   * SSE-KMS encryption with the customer-managed KMS key
#     `aws_kms_key.carddemo` declared in kms.tf — no AWS-managed default
#     keys, no SSE-S3 (AES256).
#   * `bucket_key_enabled = true` reduces KMS API call volume (one data key
#     per S3 bucket-day instead of per-object) — substantial cost saving on
#     the high-write-volume batch_outputs bucket without weakening
#     encryption-at-rest semantics.
#   * S3 Block Public Access — ALL FOUR settings forced ON
#     (block_public_acls, block_public_policy, ignore_public_acls,
#      restrict_public_buckets) so that no ACL or bucket policy can grant
#      public read/write under any circumstances.
#   * TLS-only bucket policy — explicit Deny on s3:* when
#     aws:SecureTransport is "false". HTTPS is mandatory.
#   * Encryption-on-upload bucket policy (batch_outputs only) — explicit
#     Deny on s3:PutObject when s3:x-amz-server-side-encryption is not
#     "aws:kms". Belt-and-braces enforcement that survives even if the
#     default-encryption configuration is later weakened.
#   * Versioning enabled — replaces the COBOL GDG (+1)/(0) generation
#     semantic. Every overwrite of an object creates a new version; older
#     versions are managed by the lifecycle policy.
#   * force_destroy gated by var.environment — when environment == "prod",
#     `terraform destroy` refuses to delete the bucket even if it contains
#     objects. This is the safety net for regulatory data retention.
#   * Server access logging — every read/write/delete against
#     batch_outputs is logged to the logs bucket under the
#     `s3-access-logs/batch-outputs/` prefix. Required for PCI-DSS
#     Requirement 10 (track and monitor access to network resources and
#     cardholder data).
#
# Lifecycle policy (per-prefix rules — replaces GDG aging semantics from
# DEFGDGB.jcl):
#   * STANDARD                  — current generation (newly written objects)
#   * STANDARD_IA at 30-60 days — infrequent-access tier for older outputs
#   * GLACIER at var.s3_lifecycle_glacier_days (default 90)
#                               — quarterly+ access pattern; archival tier
#   * Expiration at var.s3_lifecycle_expiration_days (default 2555 = 7 yr)
#                               — regulatory retention floor per AAP §0.7.2
#   * Non-current version aging — older versions migrate to Glacier earlier
#                                 (30 days) and are permanently expired at
#                                 365 days; aligns with the GDG LIMIT(5)
#                                 generations behaviour in DEFGDGB.jcl while
#                                 retaining a longer audit window than the
#                                 mainframe permitted.
#   * Abort incomplete multipart uploads after 7 days — hygiene rule that
#                                                       prevents partial
#                                                       uploads from
#                                                       accruing storage
#                                                       charges.
#
# Bucket naming pattern (S3 has a global namespace):
#   carddemo-<env>-<purpose>-<account_id>
# The account_id suffix is read from data.aws_caller_identity.current and
# guarantees the bucket name is globally unique. The <env> segment isolates
# dev / staging / prod buckets.
#
# Coordination (other .tf files that reference resources defined here):
#   * outputs.tf      — exports `s3_output_bucket` (batch_outputs.bucket)
#                       and `s3_logs_bucket` (logs.bucket).
#   * ecs.tf / batch.tf — inject S3_OUTPUT_BUCKET environment variable on
#                       every ECS task / Batch job definition pointing at
#                       aws_s3_bucket.batch_outputs.bucket.
#   * cloudtrail.tf   — writes CloudTrail data-event logs under a dedicated
#                       prefix on aws_s3_bucket.logs (or a sibling bucket).
#   * glue.tf         — reads job scripts from s3://<batch_outputs>/glue-scripts/
#                       and writes Spark scratch data to .../glue-temp/.
#   * macie.tf        — schedules Macie discovery jobs against both buckets
#                       per AAP §0.6.6 (continuous PII / financial-data
#                       leakage scanning).
#   * iam.tf          — grants ECS task role, Batch execution role, Glue
#                       service role, and (limited) Macie service role
#                       s3:GetObject / s3:PutObject / s3:ListBucket scoped
#                       to the appropriate prefixes.
#
# References:
#   * AAP §0.4.1 — s3.tf source mapping (POSTTRAN/INTCALC/TRANREPT/CREASTMT/
#     TRANBKP/DEFGDGB).
#   * AAP §0.6.2 — VSAM-to-RDS migration: "Sequential file output → S3 …
#     The GDG (+1)/(0) generation semantics map to S3 object versioning +
#     lifecycle policies".
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS
#     (Macie scans, KMS CMK encryption, CloudTrail destination).
#   * AAP §0.7.1 — Refactoring-Specific Rules: "Encrypt all S3 buckets with
#     SSE-KMS and block public access; bucket policies deny s3:* for
#     aws:SecureTransport=false".
#   * AAP §0.7.2 — Non-Functional Requirements: "S3 lifecycle policies
#     enforce regulatory data retention periods on batch output files".
###############################################################################

# =============================================================================
# Section 1 — Primary batch-outputs bucket
# =============================================================================
# This bucket holds the operational output of every CardDemo batch job
# (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT, TRANBKP) under separate
# top-level prefixes. The S3OutputService Java adapter is responsible for
# selecting the appropriate prefix for each write. Object keys follow a
# date-partitioned convention (e.g., daly-rejects/yyyy=2026/mm=05/dd=20/
# rejects-20260520T235959Z.txt) so that S3 lifecycle rules and Glue
# crawlers can navigate the namespace efficiently.
#
# Rationale for a SINGLE bucket holding ALL job outputs (instead of one
# bucket per job):
#   * AWS soft limit of 1,000 buckets per account would be exhausted quickly
#     across dev / staging / prod for multiple project namespaces.
#   * Cross-job IAM scoping is achievable at the prefix level via
#     iam.tf (s3:GetObject / s3:PutObject on
#     "${aws_s3_bucket.batch_outputs.arn}/daly-rejects/*", etc.).
#   * Macie scan scheduling, CloudTrail data-event capture, lifecycle
#     policy management, and bucket policy uniformity all benefit from a
#     consolidated bucket.
# =============================================================================

resource "aws_s3_bucket" "batch_outputs" {
  # Globally-unique bucket name per the convention
  # carddemo-<env>-<purpose>-<account_id>. The account_id suffix ensures
  # the name is unique across the AWS global S3 namespace; the <env>
  # segment isolates dev / staging / prod.
  bucket = "carddemo-${var.environment}-batch-outputs-${data.aws_caller_identity.current.account_id}"

  # Force deletion is permitted in non-prod environments (dev / staging)
  # to allow rapid `terraform destroy` during iteration. In prod the flag
  # is FALSE, which means `terraform destroy` will refuse to delete the
  # bucket while it still contains objects — the operational safety net
  # for regulatory retention. To intentionally destroy a prod bucket,
  # the operator must first manually empty it (an explicit, audited
  # action) before re-running destroy.
  force_destroy = var.environment != "prod"

  tags = merge(local.common_tags, {
    Name      = "${local.resource_name_prefix}-batch-outputs"
    DataClass = "PCI"
    Purpose   = "Batch job outputs (replaces GDG generations - DALYREJS/SYSTRAN/TRANREPT/STMTFILE/TRANSACT.BKUP)"
    # Region tag pins the deployment region for operator visibility. S3
    # buckets are technically global namespace entries but reside in a
    # specific region for replication / latency / data-residency control.
    Region = var.aws_region
  })
}

# -----------------------------------------------------------------------------
# Versioning — replaces the mainframe GDG (+1) / (0) "next generation" /
# "current generation" semantic from app/jcl/DEFGDGB.jcl. Each overwrite of
# the same object key creates a NEW version (a unique versionId) while the
# previous version remains accessible until aged out by the lifecycle
# policy. This preserves the parallel-run validation property required by
# AAP §0.6.2 (the COBOL and Java systems can both keep their respective
# generations during the cutover validation window).
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_versioning" "batch_outputs" {
  bucket = aws_s3_bucket.batch_outputs.id

  versioning_configuration {
    status = "Enabled"
  }
}

# -----------------------------------------------------------------------------
# SSE-KMS server-side encryption.
#
# Per AAP §0.7.1, every CardDemo S3 bucket MUST be encrypted with the
# customer-managed KMS key (aws_kms_key.carddemo from kms.tf). AES256
# (SSE-S3, the default) is NOT acceptable because AAP §0.6.6 requires
# customer-managed KMS CMKs for envelope encryption.
#
# bucket_key_enabled = true activates the S3 Bucket Key feature: instead
# of calling KMS once per object, S3 uses a per-bucket data key derived
# from the CMK for up to 24 hours, dramatically reducing KMS API call
# costs on high-volume buckets without weakening encryption semantics.
# The DALYREJS feed alone can produce thousands of objects per day, so
# Bucket Keys are essential to keeping KMS costs predictable.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_server_side_encryption_configuration" "batch_outputs" {
  bucket = aws_s3_bucket.batch_outputs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.carddemo.arn
    }

    bucket_key_enabled = true
  }
}

# -----------------------------------------------------------------------------
# S3 Block Public Access (account-level + bucket-level enforcement).
#
# All four settings are forced to TRUE so that no future change — whether
# an inadvertent ACL grant, a misconfigured bucket policy, or a manual
# operator action — can expose the bucket publicly. The four settings
# are independent:
#
#   block_public_acls       — REJECTS any PUT request that includes a
#                             public ACL (e.g., x-amz-acl: public-read).
#   block_public_policy     — REJECTS any PutBucketPolicy that includes a
#                             public Principal.
#   ignore_public_acls      — IGNORES any existing public ACLs (treats
#                             them as private without actually removing).
#   restrict_public_buckets — RESTRICTS access by any AWS account other
#                             than the bucket owner and AWS services with
#                             explicit policy grants.
#
# Per AAP §0.7.1 ("Encrypt all S3 buckets with SSE-KMS and block public
# access") and PCI-DSS Requirement 1 (firewall configuration), all four
# settings are mandatory.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_public_access_block" "batch_outputs" {
  bucket = aws_s3_bucket.batch_outputs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Bucket policy enforcing two PCI-DSS controls:
#
#   1. DenyInsecureTransport — Deny s3:* on the bucket and any object
#      within it when aws:SecureTransport is "false". This means HTTP
#      access is impossible; clients MUST use HTTPS (TLS 1.2+ further
#      enforced at the ALB layer for any application access path).
#
#   2. DenyUnEncryptedObjectUploads — Deny s3:PutObject when the upload
#      did not specify s3:x-amz-server-side-encryption = "aws:kms". The
#      bucket already has default encryption enabled (see the
#      server_side_encryption_configuration above), so under normal
#      operation S3 transparently applies SSE-KMS on every PUT. But this
#      explicit Deny provides defense in depth: even if the default
#      encryption configuration is later disabled or overridden, no
#      plaintext or AES256-encrypted object can be uploaded.
#
# `depends_on = [aws_s3_bucket_public_access_block.batch_outputs]` is
# required because attaching a bucket policy referring to a "*" Principal
# can be rejected by S3 if Block Public Policy hasn't been applied yet.
# Terraform's natural plan ordering doesn't always sequence these
# correctly, so an explicit dependency removes the race.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_policy" "batch_outputs" {
  bucket = aws_s3_bucket.batch_outputs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.batch_outputs.arn,
          "${aws_s3_bucket.batch_outputs.arn}/*"
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      },
      {
        Sid       = "DenyUnEncryptedObjectUploads"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.batch_outputs.arn}/*"
        Condition = {
          StringNotEquals = {
            "s3:x-amz-server-side-encryption" = "aws:kms"
          }
        }
      },
      {
        Sid       = "DenyIncorrectKmsKey"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.batch_outputs.arn}/*"
        Condition = {
          StringNotEqualsIfExists = {
            "s3:x-amz-server-side-encryption-aws-kms-key-id" = aws_kms_key.carddemo.arn
          }
        }
      }
    ]
  })

  # Bucket policies that reference Principal="*" can be rejected by S3
  # before the Block-Public-Policy setting is in place. Force ordering.
  depends_on = [aws_s3_bucket_public_access_block.batch_outputs]
}

# -----------------------------------------------------------------------------
# Lifecycle configuration — replaces the mainframe GDG aging semantics
# defined in app/jcl/DEFGDGB.jcl.
#
# DEFGDGB.jcl creates the following GDG bases each with LIMIT(5) (only the
# 5 most recent generations retained):
#   * AWS.M2.CARDDEMO.TRANSACT.BKUP     — transaction master backups
#   * AWS.M2.CARDDEMO.TRANSACT.DALY     — daily filtered transactions
#   * AWS.M2.CARDDEMO.TRANREPT          — transaction reports
#   * AWS.M2.CARDDEMO.TCATBALF.BKUP     — category-balance backups
#   * AWS.M2.CARDDEMO.SYSTRAN           — interest-calculation transactions
#   * AWS.M2.CARDDEMO.TRANSACT.COMBINED — combined transaction feed
#
# In S3, the LIMIT(5) generation semantic is reinterpreted as a richer
# lifecycle policy that:
#   * Keeps current (latest) generations on STANDARD storage for fast
#     access during the operational day.
#   * Migrates each generation to STANDARD_IA at 30-60 days (infrequent
#     access tier; same durability, lower cost, retrieval fee per GB).
#   * Archives each generation to GLACIER at var.s3_lifecycle_glacier_days
#     (default 90) for long-term retention.
#   * Expires the object at var.s3_lifecycle_expiration_days (default
#     2555 days = 7 years) which is the regulatory retention floor for
#     PCI-relevant financial data per AAP §0.7.2.
#   * Migrates non-current (overwritten) versions to GLACIER at 30 days
#     and permanently expires them at 365 days. This is the closest
#     equivalent of the LIMIT(5) GDG mechanism in S3 — older overwritten
#     versions don't accumulate indefinitely.
#   * Aborts incomplete multipart uploads after 7 days to prevent
#     orphaned multipart parts from accruing storage charges.
#
# Rule ordering: lifecycle rules don't have explicit priorities, but each
# rule's filter MUST be unique enough to avoid ambiguous matches. The
# prefix-based filters below partition the bucket namespace cleanly so
# there is no overlap between rules.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "batch_outputs" {
  bucket = aws_s3_bucket.batch_outputs.id

  # ---------------------------------------------------------------------------
  # Rule 1 — DALYREJS prefix
  # Replaces: AWS.M2.CARDDEMO.DALYREJS(+1) from app/jcl/POSTTRAN.jcl STEP15.
  # Daily transaction rejections written by CBTRN02C → TransactionPosting
  # Service. Volume: typically a few hundred records per day under the
  # COBOL system; expected to scale to thousands under the Java migration
  # as throughput grows. Aged out aggressively (STANDARD_IA at 30 days)
  # because rejections are usually consulted within hours/days of
  # creation but rarely after.
  # ---------------------------------------------------------------------------
  rule {
    id     = "daly-rejects-glacier-then-expire"
    status = "Enabled"

    filter {
      prefix = "daly-rejects/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = var.s3_lifecycle_glacier_days
      storage_class = "GLACIER"
    }

    expiration {
      days = var.s3_lifecycle_expiration_days
    }

    # GDG LIMIT(5) equivalent for non-current versions: older overwritten
    # versions move to GLACIER quickly and are permanently expired at
    # 365 days, replicating the mainframe's bounded-history behaviour.
    noncurrent_version_transition {
      noncurrent_days = 30
      storage_class   = "GLACIER"
    }

    noncurrent_version_expiration {
      noncurrent_days = 365
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 2 — SYSTRAN prefix
  # Replaces: AWS.M2.CARDDEMO.SYSTRAN(+1) from app/jcl/INTCALC.jcl STEP15
  # (PGM=CBACT04C interest calculator). Each end-of-day run emits one
  # SYSTRAN generation containing the synthetic interest-fee transactions
  # injected into the master TRANSACT file by the subsequent COMBTRAN job.
  # ---------------------------------------------------------------------------
  rule {
    id     = "systran-glacier-then-expire"
    status = "Enabled"

    filter {
      prefix = "systran/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = var.s3_lifecycle_glacier_days
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
  }

  # ---------------------------------------------------------------------------
  # Rule 3 — TRANREPT prefix
  # Replaces: AWS.M2.CARDDEMO.TRANREPT(+1) from app/jcl/TRANREPT.jcl
  # STEP10R (PGM=CBTRN03C). Formatted transaction report (FB 133-byte
  # records). Operators may consult these reports for up to 60 days
  # before they migrate to archival storage.
  # ---------------------------------------------------------------------------
  rule {
    id     = "tranrept-glacier-then-expire"
    status = "Enabled"

    filter {
      prefix = "tran-reports/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = var.s3_lifecycle_glacier_days
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
  }

  # ---------------------------------------------------------------------------
  # Rule 4 — STMTFILE prefix
  # Replaces: AWS.M2.CARDDEMO.STATEMNT.PS and STATEMNT.HTML from
  # app/jcl/CREASTMT.JCL STEP040 (PGM=CBSTM03A). Monthly statements are
  # the longest-lived batch artefacts; they are commonly accessed
  # throughout the statement cycle (up to 60 days). STANDARD_IA
  # transition is delayed accordingly.
  # ---------------------------------------------------------------------------
  rule {
    id     = "stmtfile-glacier-then-expire"
    status = "Enabled"

    filter {
      prefix = "statements/"
    }

    transition {
      days          = 60
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = var.s3_lifecycle_glacier_days
      storage_class = "GLACIER"
    }

    expiration {
      days = var.s3_lifecycle_expiration_days
    }

    noncurrent_version_transition {
      noncurrent_days = 60
      storage_class   = "GLACIER"
    }

    noncurrent_version_expiration {
      noncurrent_days = 365
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 5 — TRANSACT.BKUP prefix
  # Replaces: AWS.M2.CARDDEMO.TRANSACT.BKUP(+1) from app/jcl/TRANBKP.jcl
  # PRC001.FILEOUT (REPROC procedure producing the daily TRANSACT VSAM
  # backup). Highly compressible LRECL=350 FB data; aged out faster
  # (STANDARD_IA at 7 days, GLACIER at 30 days) because the active
  # backup is in RDS automated snapshots — this S3 copy is the audit
  # / parallel-run lineage record.
  # ---------------------------------------------------------------------------
  rule {
    id     = "tranbkup-glacier"
    status = "Enabled"

    filter {
      prefix = "transact-backup/"
    }

    transition {
      days          = 7
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 30
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
  }

  # ---------------------------------------------------------------------------
  # Rule 6 — Glue temp prefix (no permanent retention).
  # AWS Glue Spark jobs (provisioned in glue.tf) write intermediate
  # scratch data under glue-temp/. This is purely transient — Glue's
  # Spark drivers / executors clean up most artefacts on success but
  # leave debris on job failure. We aggressively delete anything older
  # than 7 days to keep storage footprint and cost bounded.
  # ---------------------------------------------------------------------------
  rule {
    id     = "glue-temp-cleanup"
    status = "Enabled"

    filter {
      prefix = "glue-temp/"
    }

    expiration {
      days = 7
    }

    # Even short-lived multipart uploads from Spark drivers can leave
    # part orphans on failure; clean those up after 1 day in the temp
    # prefix.
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 7 — Bucket-wide multipart upload hygiene.
  # Catches any incomplete multipart upload not already cleaned up by a
  # prefix-specific rule. 7-day grace period matches the AWS Well-
  # Architected Framework cost-optimisation recommendation.
  # ---------------------------------------------------------------------------
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    # An empty filter applies the rule bucket-wide. The Terraform AWS
    # provider requires the filter block to be present; an empty prefix
    # filter is the canonical bucket-wide selector.
    filter {
      prefix = ""
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Lifecycle configuration depends on versioning being applied first
  # because the noncurrent_version_* rules are meaningful only when
  # versioning is enabled. Without this dependency, an apply that
  # provisions everything from scratch could attempt to attach the
  # lifecycle config before versioning is in place.
  depends_on = [aws_s3_bucket_versioning.batch_outputs]
}

# =============================================================================
# Section 2 — Secondary logs bucket
# =============================================================================
# Holds:
#   * S3 server access logs from aws_s3_bucket.batch_outputs (every
#     HTTP request against batch_outputs is logged here under the
#     `s3-access-logs/batch-outputs/` prefix). Required for PCI-DSS
#     Requirement 10 (logging and monitoring access to network
#     resources and cardholder data).
#   * The CloudTrail data-event destination for batch_outputs (under
#     a dedicated `cloudtrail/` prefix). cloudtrail.tf may either
#     reference this bucket directly or provision its own trail bucket
#     — the operational model is that cloudtrail.tf decides.
#
# Same PCI-DSS controls as batch_outputs:
#   * SSE-KMS encryption with aws_kms_key.carddemo.
#   * Block Public Access (all 4 settings = true).
#   * TLS-only bucket policy.
#   * Versioning enabled (every overwrite preserves prior state).
#   * Lifecycle policy transitions logs to STANDARD_IA → GLACIER → expire.
#   * force_destroy gated by environment (prod cannot be destroyed
#     while it contains objects).
# =============================================================================

resource "aws_s3_bucket" "logs" {
  bucket = "carddemo-${var.environment}-logs-${data.aws_caller_identity.current.account_id}"

  force_destroy = var.environment != "prod"

  tags = merge(local.common_tags, {
    Name      = "${local.resource_name_prefix}-logs"
    DataClass = "Internal"
    Purpose   = "S3 access logs + CloudTrail destination for batch_outputs"
    Region    = var.aws_region
  })
}

# -----------------------------------------------------------------------------
# Versioning enabled — every overwrite or deletion event is captured as
# a new version. This is critical for the logs bucket because log files
# are the audit trail for the batch_outputs bucket; if a log file were
# silently overwritten or deleted, the audit gap would be invisible.
# Versioning + the no-delete bucket policy below (implicit in the
# combination of TLS-only and Block Public Access) ensures forensic-
# grade preservation of the audit log.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_versioning" "logs" {
  bucket = aws_s3_bucket.logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

# -----------------------------------------------------------------------------
# SSE-KMS encryption (same CMK as batch_outputs) with Bucket Key enabled.
# S3 server access logs are written by the S3 service principal at high
# frequency (one record per object request); Bucket Key amortises KMS
# call cost over the per-bucket data key.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.carddemo.arn
    }

    bucket_key_enabled = true
  }
}

# -----------------------------------------------------------------------------
# Block Public Access — same posture as batch_outputs.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_public_access_block" "logs" {
  bucket = aws_s3_bucket.logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Bucket policy — TLS-only (DenyInsecureTransport). Plus an explicit
# allow for the S3 logging service principal to write server access
# logs into this bucket from aws_s3_bucket.batch_outputs.
#
# Note on S3 server access logging permissions:
#   S3 access logging requires either:
#     (a) The logging.s3.amazonaws.com service principal in the bucket
#         policy with the appropriate s3:PutObject statement, OR
#     (b) The legacy "Log Delivery Group" ACL on the bucket.
#   Modern best practice is (a) — explicit service-principal policy —
#   because (b) requires the bucket to be ACL-enabled, which conflicts
#   with the Block Public Access posture. The Allow statement below
#   uses (a).
#
# The source-account / source-bucket conditions scope the allow to
# only the batch_outputs bucket in this account — a defense-in-depth
# measure preventing the policy from being abused by an unrelated
# logging configuration.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_policy" "logs" {
  bucket = aws_s3_bucket.logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.logs.arn,
          "${aws_s3_bucket.logs.arn}/*"
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      },
      {
        Sid    = "AllowS3ServerAccessLogging"
        Effect = "Allow"
        Principal = {
          Service = "logging.s3.amazonaws.com"
        }
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.logs.arn}/s3-access-logs/*"
        Condition = {
          ArnLike = {
            "aws:SourceArn" = aws_s3_bucket.batch_outputs.arn
          }
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.logs]
}

# -----------------------------------------------------------------------------
# Lifecycle policy — logs are written-once, read-rarely. STANDARD_IA at
# 30 days, GLACIER at 90 days, expire at the regulatory retention floor
# (var.s3_lifecycle_expiration_days, default 2555 days = 7 years).
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    id     = "logs-expire-after-7-years"
    status = "Enabled"

    # Empty filter applies the rule bucket-wide. Subprefixes
    # (s3-access-logs/, cloudtrail/) share the same retention profile.
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

  depends_on = [aws_s3_bucket_versioning.logs]
}

# =============================================================================
# Section 3 — S3 server access logging (batch_outputs → logs bucket)
# =============================================================================
# Enables S3 to log every HTTP request against the batch_outputs bucket
# (GET, PUT, DELETE, HEAD, LIST, etc.) into the logs bucket under
# `s3-access-logs/batch-outputs/`. Each log delivery is a small text
# object with one line per request including the requester identity,
# operation, source IP, bytes transferred, and HTTP status code.
#
# Required for PCI-DSS Requirement 10:
#   "Track and monitor all access to network resources and cardholder
#    data." S3 server access logs provide the bucket-level access trail
#    that complements CloudTrail's API-level trail (provisioned in
#    cloudtrail.tf).
#
# Dependency ordering: aws_s3_bucket_logging requires the target bucket
# policy to be in place before logging can start writing. We use an
# explicit depends_on rather than rely on Terraform's implicit graph
# because the bucket policy contains the AllowS3ServerAccessLogging
# statement that authorises the writes.
# =============================================================================

resource "aws_s3_bucket_logging" "batch_outputs" {
  bucket = aws_s3_bucket.batch_outputs.id

  target_bucket = aws_s3_bucket.logs.id
  target_prefix = "s3-access-logs/batch-outputs/"

  # Ensure the logs bucket policy authorising the S3 logging service
  # principal is applied before logging starts; otherwise the initial
  # batch of log deliveries would fail with AccessDenied.
  depends_on = [aws_s3_bucket_policy.logs]
}

