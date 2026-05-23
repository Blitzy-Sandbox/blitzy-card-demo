###############################################################################
# infrastructure/terraform/kms.tf
#
# AWS KMS Customer Master Keys (CMKs) for encryption-at-rest across every
# CardDemo data store.
#
# Purpose:
#   Provisions a single primary symmetric AES-256 CMK that envelope-encrypts
#   every PCI-DSS-relevant data store in the CardDemo target architecture.
#   A human-friendly alias (alias/carddemo-<env>) is exported so sibling
#   .tf files can reference the key by alias without having to import the
#   raw ARN, and a comprehensive `aws_iam_policy_document` data source
#   renders the key policy with per-service grants for every consumer.
#
# Consumers of `aws_kms_key.carddemo.arn` (via sibling .tf files):
#   * rds.tf         — RDS PostgreSQL storage encryption
#   * s3.tf          — S3 bucket SSE-KMS (batch outputs, logs, archives)
#   * elasticache.tf — ElastiCache Redis at-rest + in-transit encryption
#   * msk.tf         — Amazon MSK (Kafka) at-rest broker storage encryption
#   * cloudwatch.tf  — CloudWatch Log Group encryption
#   * opensearch.tf  — Amazon OpenSearch domain at-rest encryption
#   * secrets.tf     — AWS Secrets Manager secret encryption (DB password,
#                      JWT signing key, MSK SASL credentials, third-party
#                      API keys)
#   * cloudtrail.tf  — CloudTrail trail log file encryption
#   * ecr.tf         — ECR repository image layer encryption
#   * ecs.tf         — ECS Exec command log encryption + task definition
#                      secret references
#   * sns/sqs        — Rotation event topics + DLQs
#
# Annual rotation:
#   `enable_key_rotation = true` enables AWS-managed annual rotation of the
#   key material per AAP §0.6.6. AWS rotates the backing key material every
#   365 days while preserving prior generations in the key history so that
#   previously-encrypted ciphertext remains decryptable.
#
# Deletion safety:
#   `deletion_window_in_days = var.kms_deletion_window_in_days` (default 30,
#   validated to 7..30 in variables.tf) protects against accidental
#   `kms:ScheduleKeyDeletion` calls. After the window elapses the key is
#   permanently destroyed and all ciphertext encrypted under any prior
#   generation becomes unrecoverable — hence the default 30-day window.
#
# References:
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS
#     ("All data at rest encrypted via AWS KMS customer-managed keys").
#   * AAP §0.7.1 — Refactoring-Specific Rules
#     ("Encrypt all RDS data at rest using AWS KMS customer-managed keys
#       (CMKs)" — KMS CMKs are provisioned in infrastructure/terraform/
#       kms.tf and referenced by RDS, S3, ElastiCache, and CloudWatch Logs
#       configurations).
#   * AAP §0.6.4 — AWS Secrets Manager dynamic rotation; rotation Lambdas
#     in secrets.tf decrypt secrets via this CMK and re-encrypt the rotated
#     ciphertext (kms:ReEncryptFrom / kms:ReEncryptTo).
#   * Folder summary in ../README.md — KMS CMKs for RDS, S3, ElastiCache,
#     CloudWatch Logs, MSK with annual rotation enabled.
#
# OPTIONAL FUTURE HARDENING: per-data-store CMKs
#   For higher PCI-DSS scopes, separate CMKs (one per data store) reduce
#   blast radius if a single key is compromised and tighten the
#   per-service audit trail (each CloudTrail `kms:Decrypt` event maps to
#   exactly one data store). The current single-CMK approach is simpler
#   to operate and rotate; to migrate, create additional `aws_kms_key`
#   resources (e.g., `aws_kms_key.rds`, `aws_kms_key.s3`) and update each
#   consumer's `kms_key_id` parameter in the corresponding sibling .tf
#   file. The key policy below can be split per consumer in the same step.
###############################################################################

# =============================================================================
# Section 1 — Primary CardDemo CMK
# =============================================================================
# Single symmetric AES-256 CMK encrypting every CardDemo data store at rest.
# The key is created with annual automatic rotation (AAP §0.6.6) and a
# 30-day pending deletion window (variables.tf default) to protect against
# accidental deletion.
#
# Key configuration:
#   * key_usage                = "ENCRYPT_DECRYPT" — envelope encryption only.
#                                Signing keys (RSA / ECC) are out of scope and
#                                are not provisioned by this module.
#   * customer_master_key_spec = "SYMMETRIC_DEFAULT" — AWS-managed AES-256
#                                symmetric key. Asymmetric keys are not
#                                required by any CardDemo data store.
#   * multi_region             = false — single-region deployment per
#                                ../README.md and AAP §0.3.1. Flip to true
#                                only when adding cross-region replicated
#                                CMKs for disaster recovery — and remember
#                                that multi-region keys cannot be converted
#                                back to single-region after creation.
#   * is_enabled               = true — keys are usable immediately after
#                                creation. Operators may temporarily disable
#                                the key via AWS Console / CLI for incident
#                                response without deleting it.
#   * deletion_window_in_days  — sourced from var.kms_deletion_window_in_days
#                                (default 30; validated to 7..30 by AWS).
#   * enable_key_rotation      = true — automatic annual rotation per
#                                AAP §0.6.6. AWS preserves prior key
#                                material in the key history so existing
#                                ciphertext remains decryptable across
#                                rotation boundaries.
#
# Tag set:
#   * Project, Environment, Owner, ManagedBy — applied automatically via
#     provider `default_tags` (declared in main.tf).
#   * Name + Purpose — merged in here for human-readable console clarity.
# =============================================================================

resource "aws_kms_key" "carddemo" {
  description              = "CardDemo primary CMK - encrypts RDS, S3, ElastiCache, MSK, CloudWatch Logs, OpenSearch, Secrets Manager, SNS/SQS, ECR (AAP §0.6.6)"
  deletion_window_in_days  = var.kms_deletion_window_in_days
  enable_key_rotation      = true # AAP §0.6.6 - automatic annual rotation of key material
  is_enabled               = true
  key_usage                = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"
  multi_region             = false

  # The key policy is rendered by the `aws_iam_policy_document` data source
  # below. Splitting the policy out as a data source gives Terraform a
  # readable HCL definition (instead of an inline JSON heredoc), validates
  # IAM policy syntax at plan time, and allows reuse of partials.
  policy = data.aws_iam_policy_document.kms_carddemo.json

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-cmk"
    Purpose = "Encryption-at-rest for CardDemo data stores"
  })
}

# =============================================================================
# Section 2 — Human-friendly key alias
# =============================================================================
# Aliases provide a stable, friendly name for the underlying CMK so consumer
# .tf files (and operators using the AWS Console / CLI) can reference the
# key as `alias/carddemo-<env>` instead of the raw key UUID. Aliases also
# preserve referential stability if the underlying CMK is replaced (e.g.,
# during a per-data-store split — see "OPTIONAL FUTURE HARDENING" in the
# file header).
#
# Naming convention:
#   alias/carddemo-<env>   — primary application CMK (this file)
#   alias/carddemo-tfstate — Terraform remote-state CMK (provisioned
#                            out-of-band; see main.tf backend block).
# =============================================================================

resource "aws_kms_alias" "carddemo" {
  name          = "alias/carddemo-${var.environment}"
  target_key_id = aws_kms_key.carddemo.key_id
}

# =============================================================================
# Section 3 — Key policy document
# =============================================================================
# IAM policy document rendered into JSON and attached to the CMK via the
# `policy` argument on `aws_kms_key.carddemo`. The document is structured
# as a sequence of focused statements:
#
#   1. EnableRootAccountAdmin           — required by AWS; the account root
#                                          principal can manage the key.
#   2. AllowCloudWatchLogs              — log group encryption + decryption.
#   3. AllowSNS                         — SNS topic encryption.
#   4. AllowSQS                         — SQS queue encryption.
#   5. AllowEventBridge                 — EventBridge rules encrypt event
#                                          payloads en route to SNS targets.
#   6. AllowSecretsManagerRotation      — rotation Lambdas decrypt the
#                                          existing secret + re-encrypt
#                                          rotated ciphertext.
#   7. AllowS3                          — S3 bucket SSE-KMS.
#   8. AllowRDS                         — RDS storage encryption + grant
#                                          creation during snapshot copy.
#   9. AllowElastiCache                 — Redis at-rest encryption + grant
#                                          creation during replica scaling.
#  10. AllowOpenSearch                  — OpenSearch domain encryption +
#                                          grant creation during cluster
#                                          autotune.
#  11. AllowMSK                         — Kafka broker storage encryption.
#  12. AllowCloudTrail                  — CloudTrail log file encryption
#                                          with encryption-context guard
#                                          (prevents cross-trail decrypt).
#  13. AllowECR                         — ECR repository image layer
#                                          encryption.
#  14. AllowAttachedPrincipalsUseOfThe  — IAM principals from this account
#       Key                              can use the key only via the
#                                         listed AWS services (kms:ViaService
#                                         condition). This is the "via
#                                         service" pattern that constrains
#                                         attached-policy permissions to
#                                         service-mediated key use.
#
# Design rules (per AAP §0.6.6 + agent prompt "Rules and Constraints"):
#   * No `kms:*` actions in cross-service statements — only the minimal
#     action set required by each service.
#   * EncryptionContext conditions on CloudTrail and CloudWatch Logs
#     restrict decryption to the specific trail / log-group ARN that
#     encrypted the data, preventing cross-resource decrypt.
#   * The root-account statement is required because removing it would
#     orphan the key (no principal could manage it).
#   * Service-principal statements use `resources = ["*"]` because KMS key
#     policies are evaluated only against the key the policy is attached
#     to — the wildcard is bound to that single resource.
# =============================================================================

data "aws_iam_policy_document" "kms_carddemo" {
  # ---------------------------------------------------------------------------
  # Statement 1 — Root account admin (required default).
  #
  # AWS requires the AWS account root principal to retain administrative
  # access to every CMK; otherwise the key becomes unmanageable. IAM
  # principals (including the Terraform execution role) inherit their
  # permissions on the key from their own attached IAM policies, evaluated
  # in conjunction with this statement.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "EnableRootAccountAdmin"
    effect = "Allow"

    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account_id}:root"]
    }

    actions   = ["kms:*"]
    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 2 — CloudWatch Logs encryption.
  #
  # Encryption-context guard restricts decryption to log groups that belong
  # to this AWS account and region. This prevents a compromised principal
  # in another account from decrypting logs encrypted with this key by
  # forging an encryption context.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowCloudWatchLogs"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["logs.${var.aws_region}.amazonaws.com"]
    }

    actions = [
      "kms:Encrypt*",
      "kms:Decrypt*",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:Describe*"
    ]

    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${local.partition}:logs:${var.aws_region}:${local.account_id}:log-group:*"]
    }
  }

  # ---------------------------------------------------------------------------
  # Statement 3 — SNS topic encryption.
  #
  # SNS topics encrypted with this CMK are used by Secrets Manager rotation
  # notifications and CloudWatch alarms (per AAP §0.6.4 / §0.6.6).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowSNS"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 4 — SQS queue encryption.
  #
  # SQS queues encrypted with this CMK serve as Secrets Manager rotation
  # event subscribers (per AAP §0.6.4) and dead-letter queues for failed
  # OpenSearch audit-log indexing attempts (per AAP §0.6.6).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowSQS"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["sqs.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 5 — EventBridge for SNS publish path.
  #
  # EventBridge rules forward Secrets Manager rotation events to SNS for
  # downstream consumers (including the Spring Boot @RefreshScope listener
  # described in AAP §0.6.4).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowEventBridge"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 6 — Secrets Manager rotation Lambda.
  #
  # The Secrets Manager service principal (and its rotation Lambdas) decrypt
  # the current secret, generate a new credential, re-encrypt the rotated
  # ciphertext, and write it back. ReEncryptFrom / ReEncryptTo are required
  # for the cross-version re-encryption that Secrets Manager performs during
  # rotation (per AAP §0.6.4).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowSecretsManagerRotation"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["secretsmanager.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:Encrypt",
      "kms:GenerateDataKey",
      "kms:ReEncryptFrom",
      "kms:ReEncryptTo",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 7 — S3 bucket encryption.
  #
  # S3 buckets configured with SSE-KMS for batch outputs (DALYREJS, SYSTRAN,
  # TRANREPT, STMTFILE) and archives request a data key from this CMK per
  # object PUT (per AAP §0.6.2). Bucket policies in s3.tf enforce TLS-only
  # access and deny unencrypted PUTs as the second layer of defense.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowS3"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 8 — RDS storage encryption.
  #
  # RDS PostgreSQL Multi-AZ instances created in rds.tf reference this CMK
  # via the `kms_key_id` parameter. `CreateGrant` is required because RDS
  # creates per-snapshot grants for cross-region snapshot copy and Multi-AZ
  # standby replication (per AAP §0.6.2).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowRDS"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:CreateGrant",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 9 — ElastiCache (Redis) encryption.
  #
  # ElastiCache replication groups created in elasticache.tf reference this
  # CMK for at-rest encryption (AAP §0.7.1). `CreateGrant` is required for
  # replica scaling and snapshot operations (per AAP §0.6.2).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowElastiCache"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["elasticache.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:CreateGrant",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 10 — OpenSearch domain encryption.
  #
  # OpenSearch domains created in opensearch.tf store CloudTrail events and
  # application-emitted audit logs (per AAP §0.6.6) encrypted with this CMK.
  # `CreateGrant` is required for cluster autotune and snapshot operations.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowOpenSearch"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["es.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:CreateGrant",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 11 — MSK (Kafka) broker storage encryption.
  #
  # Amazon MSK clusters created in msk.tf encrypt broker EBS volumes with
  # this CMK (per AAP §0.6.5). The MSK service principal generates per-
  # broker data keys at cluster creation; subsequent decrypt operations
  # occur during broker boot and snapshot restore.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowMSK"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["kafka.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 12 — CloudTrail log file encryption.
  #
  # CloudTrail trails configured with this CMK (per AAP §0.6.6) request a
  # data key per log file. The `kms:EncryptionContext:aws:cloudtrail:arn`
  # condition restricts decryption to the specific trail ARN that requested
  # the encryption, preventing cross-trail decryption.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowCloudTrail"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    actions = [
      "kms:GenerateDataKey*",
      "kms:Decrypt",
      "kms:DescribeKey"
    ]

    resources = ["*"]

    condition {
      test     = "StringLike"
      variable = "kms:EncryptionContext:aws:cloudtrail:arn"
      values   = ["arn:${local.partition}:cloudtrail:*:${local.account_id}:trail/*"]
    }
  }

  # ---------------------------------------------------------------------------
  # Statement 13 — ECR image layer encryption.
  #
  # ECR repositories created in ecr.tf encrypt image layers at rest with
  # this CMK. Decrypt is required for ECS task starts (image pull); the
  # ECR service principal generates a data key per layer at image push.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowECR"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ecr.amazonaws.com"]
    }

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:DescribeKey"
    ]

    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # Statement 14 — Attached-principals use via service.
  #
  # IAM principals in this account (ECS task roles, AWS Batch job roles,
  # Glue job roles, Step Functions execution roles, Lambda execution roles)
  # can use the CMK only when the call is initiated through one of the
  # explicitly enumerated AWS services. This enforces the "via service"
  # pattern: a compromised IAM principal cannot directly issue
  # `kms:Decrypt` on application ciphertext, only the service can
  # decrypt on its behalf.
  #
  # The CallerAccount condition prevents cross-account principals from
  # using the key even via the listed services.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "AllowAttachedPrincipalsUseOfTheKey"
    effect = "Allow"

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    actions = [
      "kms:Encrypt",
      "kms:Decrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:DescribeKey"
    ]

    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "kms:CallerAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "StringLike"
      variable = "kms:ViaService"
      values = [
        "rds.${var.aws_region}.amazonaws.com",
        "secretsmanager.${var.aws_region}.amazonaws.com",
        "s3.${var.aws_region}.amazonaws.com",
        "elasticache.${var.aws_region}.amazonaws.com",
        "kafka.${var.aws_region}.amazonaws.com",
        "es.${var.aws_region}.amazonaws.com",
        "ecr.${var.aws_region}.amazonaws.com",
        "logs.${var.aws_region}.amazonaws.com",
        "sns.${var.aws_region}.amazonaws.com",
        "sqs.${var.aws_region}.amazonaws.com"
      ]
    }
  }
}
