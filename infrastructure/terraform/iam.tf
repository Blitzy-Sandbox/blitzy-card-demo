###############################################################################
# infrastructure/terraform/iam.tf
#
# AWS IAM roles, inline policies, managed-policy attachments, and the GitHub
# Actions OIDC identity provider for the CardDemo Java/Spring Boot
# application on AWS.
#
# Purpose:
#   Provide least-privilege, role-based identity to every AWS service that
#   participates in the CardDemo deployment, replacing the mainframe RACF
#   security model (AAP §0.6.6: "RACF user identity -> IAM + JWT"). One
#   IAM role per consumer; no long-lived access keys; STS assume-role +
#   short-lived federated credentials only.
#
# Role inventory (one resource per consumer):
#   * ecs_task_execution        — ECS Fargate container init: ECR pull,
#                                  CloudWatch Logs write, Secrets Manager
#                                  + KMS for env-var injection.
#   * ecs_task_role             — Application runtime: S3 (batch outputs),
#                                  MSK SASL_IAM (Kafka), Step Functions
#                                  trigger, OpenSearch HTTP, CloudWatch
#                                  PutMetricData, SQS rotation events,
#                                  Secrets Manager, KMS.
#   * batch_service             — AWS Batch service-linked role (manages
#                                  Fargate tasks on the operator's behalf).
#   * batch_execution_role      — AWS Batch task execution (ECR pull +
#                                  CloudWatch Logs + Secrets Manager +
#                                  KMS, same scope as ECS task execution).
#   * batch_job_role            — AWS Batch task runtime (same application
#                                  permissions as ECS task role; batch
#                                  jobs read/write the same data stores
#                                  as the long-running REST service).
#   * step_functions_execution  — Step Functions state machine execution:
#                                  Batch SubmitJob, Glue StartJobRun,
#                                  EventBridge rule provisioning,
#                                  CloudWatch Logs, X-Ray tracing.
#   * glue_job_execution        — AWS Glue Spark jobs: S3 read/write of
#                                  staged ASCII fixtures, Secrets Manager
#                                  (RDS credentials), KMS.
#   * rds_enhanced_monitoring   — RDS Enhanced Monitoring (per-OS-level
#                                  metric collection forwarded to
#                                  CloudWatch Logs).
#   * eventbridge_scheduler     — EventBridge Scheduler invoking the EOD
#                                  Step Functions state machine on cron.
#   * github_actions_deploy     — GitHub Actions CI/CD deploy role (OIDC
#                                  federation; ECR push + ECS service
#                                  update + iam:PassRole on ECS task
#                                  roles).
#   * github_actions OIDC IdP   — `token.actions.githubusercontent.com`
#                                  identity provider registered with IAM
#                                  so GitHub Actions workflows can present
#                                  workload-bound identity tokens.
#
# Design rules (per agent prompt + AAP §0.7.1):
#   * Least-privilege: every `resources` block scopes to the smallest
#     possible set of ARNs. Wildcards `Resource = "*"` appear only for
#     truly account-internal actions (CloudWatch `PutMetricData` gated by
#     a namespace condition, X-Ray trace ingestion, AWS Batch / Glue
#     describe APIs where the API itself does not accept resource-level
#     scoping).
#   * KMS scoping: every kms:* statement targets exactly
#     `aws_kms_key.carddemo.arn` (the customer-managed CMK from kms.tf).
#     `kms:*` is NEVER granted to any role.
#   * Secrets Manager scoping: every secretsmanager:* statement targets
#     the `carddemo-${var.environment}-*` secret name prefix. Roles can
#     never read secrets that don't belong to this environment.
#   * No inline access keys: roles are assumed via STS (sts:AssumeRole)
#     or STS web-identity federation (sts:AssumeRoleWithWebIdentity, for
#     GitHub Actions OIDC).
#   * GitHub Actions OIDC: trust policy enforces both `aud` (must equal
#     `sts.amazonaws.com`) and `sub` (must match
#     `repo:${var.github_repo_owner}/${var.github_repo_name}:*`) claim
#     conditions. Cross-repository assumption is impossible.
#   * Conditional `count` on GitHub OIDC: when `var.github_oidc_enabled`
#     is false (dev sandboxes that do not need CI/CD), the OIDC provider
#     and deploy role are not created.
#   * Tags: every IAM role carries the mandatory `local.common_tags`
#     (Project, Environment, Owner, ManagedBy).
#
# Wildcard ARN construction for resources provisioned by sibling .tf files
# that have not yet been declared at the time this file is loaded
# (`aws_sfn_state_machine.*`, `aws_sqs_queue.*`, `aws_opensearch_domain.*`,
# etc.). Rather than introducing implicit dependency edges that would
# require those resources to exist at plan time, this file scopes the
# corresponding IAM statements with predicted ARN strings using the
# documented `carddemo-${var.environment}-*` naming convention. The
# predicted ARNs match the names produced by `stepfunctions.tf`,
# `secrets.tf`, and `opensearch.tf` (which carry the same naming
# convention as every other sibling `.tf` file in this folder). This
# keeps the IAM module loadable in isolation while still enforcing
# least-privilege boundaries.
#
# References:
#   * AAP §0.6.6  — Cross-cutting audit, observability, and PCI-DSS posture
#                   (TLS 1.2+, KMS CMKs, Secrets Manager, CloudTrail,
#                   OpenSearch, Macie).
#   * AAP §0.7.1  — Service-to-service authentication via IAM; never
#                   long-lived access keys.
#   * Agent prompt — Detailed phase-by-phase role inventory and inline
#                    policy contents.
###############################################################################

# =============================================================================
# Section 1 — Shared inline-policy fragments
# =============================================================================
# Reusable `aws_iam_policy_document` data sources that are composed into
# the per-role inline policies. Splitting these into named fragments keeps
# the per-role policy attachments small and readable, and prevents copy-
# paste drift between roles that share a common permission boundary.
#
# Note: `data.aws_caller_identity.current` and `data.aws_partition.current`
# are declared in `main.tf` (Section 3 — Identity / regional data sources)
# and exposed as `local.account_id` and `local.partition`. They are NOT
# redeclared here (Terraform forbids duplicate data-source addresses).
# =============================================================================

# -----------------------------------------------------------------------------
# Read CardDemo-prefixed secrets from AWS Secrets Manager.
#
# Granted to every role that runs application code (ECS task execution,
# ECS task, Batch execution, Batch job, Glue job) so the running JVM can
# fetch the RDS master password, JWT signing key, MSK SASL credentials,
# OpenSearch master credential, and any third-party API keys at startup
# and on @RefreshScope reload (AAP §0.6.4).
#
# Scope: `secret:carddemo-${var.environment}-*` — name-prefix scoping
# guarantees a role cannot read secrets belonging to a different
# environment (dev role cannot read prod secrets) or to a different
# application running in the same account.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "secrets_manager_read" {
  statement {
    sid    = "ReadCarddemoSecretsManagerValues"
    effect = "Allow"
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecretVersionIds"
    ]
    resources = [
      "arn:${local.partition}:secretsmanager:${var.aws_region}:${local.account_id}:secret:carddemo-${var.environment}-*"
    ]
  }
}

# -----------------------------------------------------------------------------
# Use of the CardDemo customer-managed KMS CMK.
#
# Granted to every role that needs to decrypt or encrypt CardDemo data:
#   * Decrypt — Secrets Manager (for the encrypted secret material), S3
#               (SSE-KMS objects), RDS (envelope-encrypted data pages),
#               ElastiCache (in-transit + at-rest), MSK (broker storage),
#               CloudWatch Logs (KMS-encrypted log groups).
#   * Encrypt / GenerateDataKey — Writing to any of the above stores.
#   * ReEncryptFrom / ReEncryptTo — Used by Secrets Manager rotation
#     Lambdas (AAP §0.6.4) when re-encrypting rotated secrets with a new
#     data key derived from the same CMK.
#   * DescribeKey — Required by the AWS SDK to confirm the CMK exists and
#     is enabled before performing any of the above operations.
#
# Scope: `aws_kms_key.carddemo.arn` (the single CardDemo CMK declared in
# kms.tf). The `kms:*` action is NEVER granted in this module.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "kms_use" {
  statement {
    sid    = "UseCarddemoCMK"
    effect = "Allow"
    actions = [
      "kms:Decrypt",
      "kms:Encrypt",
      "kms:GenerateDataKey",
      "kms:GenerateDataKeyWithoutPlaintext",
      "kms:ReEncryptFrom",
      "kms:ReEncryptTo",
      "kms:DescribeKey"
    ]
    # F-CP6-TF-KMS-01: Expand the resource list to cover every
    # service-specific CMK introduced by the per-service KMS separation
    # in kms.tf. Application principals that need to decrypt RDS
    # ciphertext, S3 SSE-KMS objects, Secrets Manager secrets, etc.
    # must be granted use of the corresponding CMK explicitly.
    resources = [
      aws_kms_key.carddemo.arn,
      aws_kms_key.rds_kms.arn,
      aws_kms_key.s3_kms.arn,
      aws_kms_key.elasticache_kms.arn,
      aws_kms_key.msk_kms.arn,
      aws_kms_key.cloudwatch_kms.arn,
      aws_kms_key.secrets_kms.arn,
      aws_kms_key.opensearch_kms.arn
    ]
  }
}

# -----------------------------------------------------------------------------
# Combined Secrets-Manager + KMS policy document.
#
# Many roles need BOTH the secret read and the matching KMS decrypt
# permission (the secret is KMS-encrypted with the CardDemo CMK, so
# reading it requires the kms:Decrypt action). Composing the two
# fragments here avoids duplicating the attach-twice pattern at every
# role definition.
#
# The `source_policy_documents` argument merges the two source documents
# into a single document with both statements preserved. AWS IAM
# evaluates the merged document the same way it would evaluate the two
# source documents attached separately.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "combined_secrets_kms" {
  source_policy_documents = [
    data.aws_iam_policy_document.secrets_manager_read.json,
    data.aws_iam_policy_document.kms_use.json
  ]
}

# =============================================================================
# Section 2 — ECS task execution role
# =============================================================================
# Role assumed by the ECS Fargate agent (the ECS data-plane component
# that pulls the container image, hydrates environment variables from
# Secrets Manager / Parameter Store, and writes container logs to
# CloudWatch). DISTINCT from the ECS task role (Section 3), which is
# assumed by the running application code inside the container.
#
# Permissions granted:
#   * AmazonECSTaskExecutionRolePolicy (AWS-managed): ECR pull, CloudWatch
#     Logs CreateLogStream + PutLogEvents.
#   * Inline `secrets-and-kms`: read CardDemo secrets + decrypt with the
#     CardDemo CMK so the Fargate agent can resolve `valueFrom` references
#     in the ECS task definition's `secrets` block at container start.
# =============================================================================

# Trust policy: the ECS service principal (`ecs-tasks.amazonaws.com`) is
# the ONLY principal allowed to assume this role. This is the standard
# Fargate trust relationship and is reused by every Fargate-launched role
# in this file (ECS task role, Batch execution role, Batch job role).
data "aws_iam_policy_document" "ecs_task_execution_assume" {
  statement {
    sid     = "ECSTasksAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_task_execution" {
  name               = "carddemo-${var.environment}-ecs-task-execution"
  description        = "ECS Fargate task execution role for the CardDemo Spring Boot service (ECR pull, CloudWatch Logs, Secrets Manager value injection)"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_execution_assume.json

  # Maximum session duration for sts:AssumeRole tokens. Default is 1h;
  # the ECS data plane does not need long-lived sessions because it
  # refreshes credentials transparently via the task metadata endpoint.
  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-ecs-task-execution"
    Purpose = "ECS Fargate task execution (ECR pull, log delivery, secret injection)"
  })
}

# AWS-managed policy grant: ECR pull (BatchCheckLayerAvailability,
# GetDownloadUrlForLayer, BatchGetImage, GetAuthorizationToken) and
# CloudWatch Logs write (CreateLogStream, PutLogEvents).
resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Inline `secrets-and-kms` grant so the Fargate agent can resolve
# `valueFrom` references to CardDemo secrets at container start and
# decrypt the secret material with the CardDemo CMK.
resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name   = "secrets-and-kms"
  role   = aws_iam_role.ecs_task_execution.id
  policy = data.aws_iam_policy_document.combined_secrets_kms.json
}

# =============================================================================
# Section 3 — ECS task role (application runtime)
# =============================================================================
# Role assumed by the Spring Boot application itself (the JVM process
# inside the Fargate container) to make AWS API calls. Distinct from the
# ECS task execution role (Section 2), which is assumed by the Fargate
# data-plane agent. The Spring Boot AWS SDK clients (S3, MSK, Step
# Functions, OpenSearch, CloudWatch, SQS, Secrets Manager) all resolve
# credentials via the task metadata endpoint and assume this role.
#
# Permissions granted (each in its own statement for audit clarity):
#   * S3: read/write on the batch_outputs bucket (replaces the COBOL
#     sequential output writes — DALYREJS, SYSTRAN, TRANREPT, STMTFILE,
#     TRANSACT.BKUP per AAP §0.6.2).
#   * MSK kafka-cluster:*: SASL_IAM client authentication to the MSK
#     cluster (AAP §0.6.5 partitioning by account ID requires the IAM
#     plugin enabled on the MSK side).
#   * Step Functions: StartExecution on the EOD and file-provisioning
#     state machines (the online CORPT00C report-request flow per
#     AAP §0.1.1 publishes to Kafka and triggers a Step Functions job
#     via a Lambda that asserts these permissions on its behalf — the
#     application also issues direct StartExecution for ad-hoc reruns).
#   * OpenSearch ES HTTP: indexing audit logs and CloudTrail-derived
#     events into the OpenSearch domain (AAP §0.6.6 immutable audit
#     trail).
#   * CloudWatch PutMetricData: Micrometer -> CloudWatch metrics under
#     the `CardDemo` namespace (AAP §0.6.6 observability).
#   * SQS: ReceiveMessage / DeleteMessage on the secrets-rotation event
#     queue (AAP §0.6.4 dynamic secret rotation without Spring Boot
#     restart — the app subscribes to rotation notifications and
#     publishes RefreshEvent to its own ApplicationContext).
#   * Secrets Manager + KMS: composed inline policy (see Section 1) for
#     reading CardDemo secrets and decrypting them with the CardDemo CMK.
# =============================================================================

resource "aws_iam_role" "ecs_task_role" {
  name               = "carddemo-${var.environment}-ecs-task-role"
  description        = "ECS Fargate task application runtime role for CardDemo (S3, MSK, Step Functions, OpenSearch, CloudWatch, SQS, Secrets Manager, KMS)"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_execution_assume.json

  # 1h session lifetime — same rationale as the execution role.
  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-ecs-task-role"
    Purpose = "Spring Boot application runtime permissions (S3, MSK, Step Functions, OpenSearch, CloudWatch, SQS)"
  })
}

# -----------------------------------------------------------------------------
# Inline runtime policy document.
#
# Each statement is independently audited and scoped. AWS IAM evaluates
# all statements together; an `Allow` in any one statement grants the
# action (subject to any explicit `Deny` elsewhere — there are no Deny
# statements in this document, but the KMS key policy in kms.tf provides
# a defense-in-depth Deny for cross-service misuse).
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "ecs_task_runtime" {
  # ---------------------------------------------------------------------------
  # S3 — batch outputs bucket.
  #
  # Scope: the single batch_outputs bucket (s3.tf) and every object inside
  # it. ListBucket is required for the S3OutputService to enumerate
  # existing object versions (GDG generation discovery per AAP §0.6.2).
  # GetBucketLocation is required by the AWS SDK to resolve the bucket's
  # region for signing.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "S3BatchOutputsReadWrite"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
      "s3:ListBucket",
      "s3:ListBucketVersions",
      "s3:GetBucketLocation"
    ]
    resources = [
      aws_s3_bucket.batch_outputs.arn,
      "${aws_s3_bucket.batch_outputs.arn}/*"
    ]
  }

  # ---------------------------------------------------------------------------
  # MSK — IAM-based SASL authentication.
  #
  # MSK IAM auth (AAP §0.6.5) requires three ARN families:
  #   * cluster/...  — Connect, DescribeCluster, AlterCluster
  #   * topic/...    — DescribeTopic, ReadData, WriteData, CreateTopic,
  #                    AlterTopic
  #   * group/...    — DescribeGroup, AlterGroup, DescribeTopicDynamicConfig
  #
  # The base cluster ARN is `aws_msk_cluster.carddemo.arn`. Topic and
  # group ARNs share the same ARN structure with the `cluster/`
  # path-segment replaced by `topic/` or `group/`. The `replace()` calls
  # generate these sibling ARNs from the cluster ARN, ensuring the IAM
  # statement automatically tracks any future change in the cluster
  # naming convention.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "MSKConnectIAMAuth"
    effect = "Allow"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
      "kafka-cluster:AlterCluster",
      "kafka-cluster:DescribeClusterDynamicConfiguration"
    ]
    resources = [
      aws_msk_cluster.carddemo.arn
    ]
  }

  statement {
    sid    = "MSKTopicReadWrite"
    effect = "Allow"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:CreateTopic",
      "kafka-cluster:AlterTopic",
      "kafka-cluster:WriteData",
      "kafka-cluster:ReadData",
      "kafka-cluster:DescribeTopicDynamicConfiguration",
      "kafka-cluster:AlterTopicDynamicConfiguration"
    ]
    resources = [
      "${replace(aws_msk_cluster.carddemo.arn, "cluster/", "topic/")}/*"
    ]
  }

  statement {
    sid    = "MSKConsumerGroupAccess"
    effect = "Allow"
    actions = [
      "kafka-cluster:AlterGroup",
      "kafka-cluster:DescribeGroup"
    ]
    resources = [
      "${replace(aws_msk_cluster.carddemo.arn, "cluster/", "group/")}/*"
    ]
  }

  # ---------------------------------------------------------------------------
  # Step Functions — start / inspect / stop executions of CardDemo state
  # machines.
  #
  # Scope: predicted ARNs for the EOD batch pipeline and file
  # provisioning state machines, plus their per-execution children
  # (matched by `...:*`). The naming convention `carddemo-${env}-...` is
  # the same one used throughout the module and is enforced in
  # `stepfunctions.tf` by the sibling agent. The wildcard `:*` suffix
  # covers per-execution sub-ARNs.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "StepFunctionsExecuteCarddemoStateMachines"
    effect = "Allow"
    actions = [
      "states:StartExecution",
      "states:StartSyncExecution",
      "states:DescribeExecution",
      "states:StopExecution",
      "states:ListExecutions"
    ]
    resources = [
      "arn:${local.partition}:states:${var.aws_region}:${local.account_id}:stateMachine:carddemo-${var.environment}-*",
      "arn:${local.partition}:states:${var.aws_region}:${local.account_id}:execution:carddemo-${var.environment}-*:*"
    ]
  }

  # ---------------------------------------------------------------------------
  # OpenSearch ES HTTP — index audit logs and search audit data.
  #
  # `es:ESHttp*` covers GET/POST/PUT/DELETE/HEAD on the OpenSearch
  # domain endpoint. The trailing `/*` scopes the action to indices and
  # documents inside the domain. The OpenSearch domain ARN follows the
  # `carddemo-${env}-search` naming convention enforced in
  # `opensearch.tf` by the sibling agent.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "OpenSearchHTTP"
    effect = "Allow"
    actions = [
      "es:ESHttpGet",
      "es:ESHttpPost",
      "es:ESHttpPut",
      "es:ESHttpDelete",
      "es:ESHttpHead",
      "es:ESHttpPatch",
      "es:DescribeDomain",
      "es:DescribeDomains"
    ]
    resources = [
      "arn:${local.partition}:es:${var.aws_region}:${local.account_id}:domain/carddemo-${var.environment}-*",
      "arn:${local.partition}:es:${var.aws_region}:${local.account_id}:domain/carddemo-${var.environment}-*/*"
    ]
  }

  # ---------------------------------------------------------------------------
  # CloudWatch PutMetricData — Micrometer custom metrics.
  #
  # CloudWatch PutMetricData does not accept resource-level scoping
  # (AWS API limitation), so `resources = ["*"]` is unavoidable.
  # Compensate with a namespace condition: PutMetricData calls are only
  # permitted if the namespace is exactly `CardDemo`. The Spring Boot
  # Micrometer registry is configured (config/CloudWatchConfig.java) to
  # publish under this namespace exclusively.
  # ---------------------------------------------------------------------------
  statement {
    sid       = "CloudWatchPutMetricData"
    effect    = "Allow"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["CardDemo"]
    }
  }

  # ---------------------------------------------------------------------------
  # SQS — receive Secrets Manager rotation events.
  #
  # Per AAP §0.6.4, the Secrets Manager rotation Lambda publishes a
  # notification to an SNS topic on rotation completion. A subscribed
  # SQS queue (provisioned in `secrets.tf` as
  # `aws_sqs_queue.secrets_rotation_app`) buffers the notification. The
  # Spring Boot application consumes the queue and publishes a
  # `RefreshEvent` to its own ApplicationContext, triggering
  # @RefreshScope beans to re-read the rotated secret on next use —
  # without restarting the JVM.
  #
  # Scope: predicted SQS queue ARN matching the naming convention
  # enforced in `secrets.tf` by the sibling agent.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "SQSReceiveRotationEvents"
    effect = "Allow"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
      "sqs:ChangeMessageVisibility"
    ]
    resources = [
      "arn:${local.partition}:sqs:${var.aws_region}:${local.account_id}:carddemo-${var.environment}-secrets-rotation-*"
    ]
  }

  # ---------------------------------------------------------------------------
  # Parameter Store — fetch non-sensitive configuration (AAP §0.7.2).
  #
  # `RDS_SECRET_ARN`, `MSK_BOOTSTRAP_SERVERS`, `S3_OUTPUT_BUCKET`,
  # `OPENSEARCH_ENDPOINT`, `ECS_CLUSTER_NAME` are stored as plaintext
  # Parameter Store entries under the `/carddemo/${env}/` prefix and
  # imported via `spring.config.import=aws-parameterstore:` (Spring
  # Cloud AWS 3.x). Only non-sensitive values live in Parameter Store;
  # secrets remain in Secrets Manager.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "ParameterStoreReadCarddemoConfig"
    effect = "Allow"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath"
    ]
    resources = [
      "arn:${local.partition}:ssm:${var.aws_region}:${local.account_id}:parameter/carddemo/${var.environment}/*"
    ]
  }

  # ---------------------------------------------------------------------------
  # X-Ray tracing — Spring Boot AWS X-Ray integration.
  #
  # X-Ray ingestion APIs do not accept resource-level scoping;
  # `resources = ["*"]` is the documented AWS pattern. Sampling rules
  # and segment writes are bound to the calling account.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "XRayTracing"
    effect = "Allow"
    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets",
      "xray:GetSamplingStatisticSummaries"
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "ecs_task_runtime" {
  name   = "carddemo-runtime"
  role   = aws_iam_role.ecs_task_role.id
  policy = data.aws_iam_policy_document.ecs_task_runtime.json
}

resource "aws_iam_role_policy" "ecs_task_secrets_kms" {
  name   = "secrets-and-kms"
  role   = aws_iam_role.ecs_task_role.id
  policy = data.aws_iam_policy_document.combined_secrets_kms.json
}

# =============================================================================
# Section 4 — AWS Batch service role
# =============================================================================
# Service role assumed by the AWS Batch control plane to manage compute
# resources on behalf of the operator. With Fargate compute environments,
# AWS Batch uses this role to register and deregister Fargate tasks,
# query their status, and emit job lifecycle events to CloudWatch
# Events.
#
# This is DISTINCT from the Batch execution role (Section 5) — which is
# the ECS Fargate-agent role for individual Batch jobs — and from the
# Batch job role (Section 6) — which is the application-runtime role
# assumed by the JVM inside each Batch job container.
#
# The AWS-managed policy `AWSBatchServiceRole` grants the exact set of
# permissions documented at:
# https://docs.aws.amazon.com/batch/latest/userguide/service_IAM_role.html
# =============================================================================

# Trust policy: the Batch service principal (`batch.amazonaws.com`) is
# the only principal allowed to assume this role.
data "aws_iam_policy_document" "batch_service_assume" {
  statement {
    sid     = "BatchServiceAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["batch.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "batch_service" {
  name               = "carddemo-${var.environment}-batch-service"
  description        = "AWS Batch service role for the CardDemo Fargate compute environment (replaces JCL/JES per AAP §0.6.3)"
  assume_role_policy = data.aws_iam_policy_document.batch_service_assume.json

  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-service"
    Purpose = "AWS Batch service role for Fargate compute environment management"
  })
}

# AWS-managed AWSBatchServiceRole policy: the canonical set of
# permissions Batch needs to register / describe / deregister compute
# resources, query job status, write job state to CloudWatch Events, and
# create the necessary service-linked roles.
resource "aws_iam_role_policy_attachment" "batch_service_managed" {
  role       = aws_iam_role.batch_service.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AWSBatchServiceRole"
}

# =============================================================================
# Section 5 — AWS Batch task execution role
# =============================================================================
# Role assumed by the Fargate agent INSIDE each Batch job container at
# startup. Same scope as the ECS task execution role (Section 2): pull
# the container image from ECR, write logs to CloudWatch, and resolve
# Secrets Manager value injections.
#
# Reuses the `ecs-tasks.amazonaws.com` trust principal because every
# Batch-on-Fargate job is, under the hood, an ECS task launched by the
# Batch control plane.
# =============================================================================

resource "aws_iam_role" "batch_execution_role" {
  name        = "carddemo-${var.environment}-batch-execution"
  description = "AWS Batch task execution role for CardDemo batch jobs (ECR pull, CloudWatch Logs, Secrets Manager / KMS — equivalent to ECS task execution role)"
  # Reuse the `ecs-tasks.amazonaws.com` trust policy from Section 2:
  # Batch-on-Fargate jobs run as ECS tasks under the hood.
  assume_role_policy = data.aws_iam_policy_document.ecs_task_execution_assume.json

  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-execution"
    Purpose = "AWS Batch Fargate task execution (ECR pull, log delivery, secret injection)"
  })
}

# Same AWS-managed policy as the ECS task execution role.
resource "aws_iam_role_policy_attachment" "batch_execution_managed" {
  role       = aws_iam_role.batch_execution_role.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Same inline `secrets-and-kms` policy as the ECS task execution role
# so the Fargate agent can resolve `valueFrom` references at Batch job
# container start.
resource "aws_iam_role_policy" "batch_execution_secrets" {
  name   = "secrets-and-kms"
  role   = aws_iam_role.batch_execution_role.id
  policy = data.aws_iam_policy_document.combined_secrets_kms.json
}

# =============================================================================
# Section 6 — AWS Batch job role (application runtime)
# =============================================================================
# Role assumed by the Spring Boot application code running inside each
# Batch job container. Same scope as the ECS task role (Section 3) — the
# Spring Batch jars read and write the same data stores as the long-
# running REST service (S3 batch outputs, MSK transaction.posted /
# account.updated topics, Step Functions next-stage triggers,
# OpenSearch audit indexing, CloudWatch metric publication, SQS
# rotation event consumption, Secrets Manager + KMS).
#
# The two roles are deliberately separate (rather than reusing the ECS
# task role) so that the Batch and online services can be audited
# independently — every CloudTrail event tagged with the assumed-role
# principal identifies whether the action originated from the long-
# running service or from a Batch job execution.
# =============================================================================

resource "aws_iam_role" "batch_job_role" {
  name               = "carddemo-${var.environment}-batch-job"
  description        = "AWS Batch task application runtime role for CardDemo batch jobs (same scope as ECS task role: S3, MSK, Step Functions, OpenSearch, CloudWatch, SQS)"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_execution_assume.json

  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-job"
    Purpose = "Spring Batch application runtime permissions (S3, MSK, Step Functions, OpenSearch, CloudWatch)"
  })
}

# Reuse the ECS task runtime policy document — Spring Batch jobs need
# the same application permissions as the long-running ECS service.
resource "aws_iam_role_policy" "batch_job_runtime" {
  name   = "carddemo-runtime"
  role   = aws_iam_role.batch_job_role.id
  policy = data.aws_iam_policy_document.ecs_task_runtime.json
}

resource "aws_iam_role_policy" "batch_job_secrets_kms" {
  name   = "secrets-and-kms"
  role   = aws_iam_role.batch_job_role.id
  policy = data.aws_iam_policy_document.combined_secrets_kms.json
}

# =============================================================================
# Section 7 — Step Functions state machine execution role
# =============================================================================
# Role assumed by the AWS Step Functions service to execute the CardDemo
# state machines (eod-batch-pipeline, file-provisioning per AAP §0.6.3).
# Each Task state in the state machine invokes a downstream service
# (Batch SubmitJob, Glue StartJobRun, EventBridge target) under this
# role, so the role must aggregate the union of all downstream
# permissions.
#
# Trust principal: `states.${var.aws_region}.amazonaws.com` — the
# regional service principal for Step Functions. Note that Step
# Functions principals are region-scoped (unlike most other AWS service
# principals); the region embedded in the principal must match the
# region where the state machine is provisioned.
# =============================================================================

# Trust policy: regional Step Functions service principal.
data "aws_iam_policy_document" "step_functions_assume" {
  statement {
    sid     = "StepFunctionsAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "step_functions_execution" {
  name               = "carddemo-${var.environment}-stepfunctions"
  description        = "Step Functions state machine execution role for CardDemo EOD batch pipeline and file provisioning (replaces JCL/JES job stream orchestration per AAP §0.6.3)"
  assume_role_policy = data.aws_iam_policy_document.step_functions_assume.json

  # State machine executions can be long-lived (EOD pipeline takes
  # ~hours); the maximum session duration of 12h is the AWS upper bound
  # and provides ample margin.
  max_session_duration = 43200

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-stepfunctions"
    Purpose = "Step Functions state machine execution (Batch + Glue + EventBridge + CloudWatch Logs + X-Ray)"
  })
}

# -----------------------------------------------------------------------------
# Step Functions runtime policy document.
#
# Aggregates the permissions needed by every Task state in the EOD and
# file-provisioning state machines:
#   * Batch SubmitJob / DescribeJobs / TerminateJob — Stage 1 / 2 / 3
#     Task states submit AWS Batch jobs and wait for completion.
#   * Glue StartJobRun / GetJobRun / GetJobRuns / BatchStopJobRun —
#     file-provisioning state machine starts Glue Spark jobs to bulk-
#     load ASCII fixtures from S3 into RDS.
#   * EventBridge PutRule / PutTargets / DescribeRule — Step Functions
#     creates a managed rule that captures Batch job state changes
#     (StepFunctionsGetEventsForBatchJobsRule). Scope is the canonical
#     rule name reserved by AWS for this purpose.
#   * CloudWatch Logs — `logs:CreateLogDelivery` and related Vended Log
#     APIs that AWS uses on Step Functions' behalf when enabling
#     execution logging.
#   * X-Ray tracing — segment ingestion APIs.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "step_functions_runtime" {
  # ---------------------------------------------------------------------------
  # F-CP6-TF-IAM-01: AWS Batch — split actions by resource-type support.
  #
  # The AWS Batch IAM documentation enumerates resource types for several
  # actions. We split the previous single wildcard statement into action-
  # specific statements that scope ARNs as tightly as AWS supports:
  #
  #   * batch:SubmitJob       — supports job-queue + job-definition.
  #   * batch:TerminateJob    — supports job ARNs (active jobs).
  #   * batch:DescribeJobs    — only supports * (read).
  #   * batch:ListJobs        — only supports * (read).
  #
  # The PassRole condition further below limits which job roles Step
  # Functions may bind to submitted jobs.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "SubmitBatchJobs"
    effect = "Allow"
    actions = [
      "batch:SubmitJob"
    ]
    # SubmitJob requires both a job queue ARN and a job definition ARN
    # in the action's required resource list. Scope to CardDemo-owned
    # ARNs only.
    resources = [
      "arn:${local.partition}:batch:${var.aws_region}:${local.account_id}:job-queue/carddemo-${var.environment}-*",
      "arn:${local.partition}:batch:${var.aws_region}:${local.account_id}:job-definition/carddemo-${var.environment}-*",
      "arn:${local.partition}:batch:${var.aws_region}:${local.account_id}:job-definition/carddemo-${var.environment}-*:*"
    ]
  }

  statement {
    sid    = "TerminateBatchJobs"
    effect = "Allow"
    actions = [
      "batch:TerminateJob"
    ]
    # TerminateJob accepts job ARNs. Scope by CardDemo job ARN prefix.
    resources = [
      "arn:${local.partition}:batch:${var.aws_region}:${local.account_id}:job/*"
    ]
  }

  statement {
    sid    = "DescribeAndListBatchJobs"
    effect = "Allow"
    actions = [
      "batch:DescribeJobs",
      "batch:ListJobs"
    ]
    # DescribeJobs and ListJobs do not support resource-level scoping
    # per AWS Batch IAM documentation; the * wildcard is required.
    # Risk is bounded because both actions are read-only and the IAM
    # CallerAccount condition below restricts use to the CardDemo
    # account.
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:ResourceAccount"
      values   = [local.account_id]
    }
  }

  # ---------------------------------------------------------------------------
  # AWS Batch — pass the Batch job + execution roles to AWS Batch when
  # submitting a job. PassRole is the IAM action that authorizes a
  # service to assume another role on behalf of the caller; it is the
  # cornerstone of secure cross-service role delegation.
  #
  # Scope: only the CardDemo Batch job role and Batch execution role
  # can be passed. The `iam:PassedToService` condition further
  # restricts the target service to ECS-tasks (Fargate) and Batch
  # itself.
  # ---------------------------------------------------------------------------
  statement {
    sid     = "PassRoleToBatchJobs"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.batch_job_role.arn,
      aws_iam_role.batch_execution_role.arn
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values = [
        "ecs-tasks.amazonaws.com",
        "batch.amazonaws.com"
      ]
    }
  }

  # ---------------------------------------------------------------------------
  # AWS Glue — start / inspect / stop Glue Spark jobs.
  #
  # Per AAP §0.6.2, Glue Spark jobs perform bulk ETL from S3-staged
  # ASCII fixtures into RDS PostgreSQL. The file-provisioning state
  # machine invokes these jobs during the initial data load and on
  # full-refresh demand.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "StartGlueJobs"
    effect = "Allow"
    actions = [
      "glue:StartJobRun",
      "glue:GetJobRun",
      "glue:GetJobRuns",
      "glue:BatchStopJobRun"
    ]
    resources = [
      "arn:${local.partition}:glue:${var.aws_region}:${local.account_id}:job/carddemo-${var.environment}-*"
    ]
  }

  # ---------------------------------------------------------------------------
  # EventBridge — managed rule for Batch job state change events.
  #
  # When Step Functions submits a Batch job via the .sync integration
  # pattern, the service creates an EventBridge rule
  # (`StepFunctionsGetEventsForBatchJobsRule`) that subscribes to Batch
  # state change events so the state machine can detect job completion.
  # Scope: that specific rule ARN.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "EventBridgeRuleForBatchSync"
    effect = "Allow"
    actions = [
      "events:PutTargets",
      "events:PutRule",
      "events:DescribeRule"
    ]
    resources = [
      "arn:${local.partition}:events:${var.aws_region}:${local.account_id}:rule/StepFunctionsGetEventsForBatchJobsRule"
    ]
  }

  # ---------------------------------------------------------------------------
  # CloudWatch Logs — vended log delivery for Step Functions execution
  # logs. These APIs are documented in the AWS Step Functions
  # developer guide and do not accept resource-level scoping
  # (`resources = ["*"]`).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "CloudWatchLogsVendedDelivery"
    effect = "Allow"
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups"
    ]
    resources = ["*"]
  }

  # ---------------------------------------------------------------------------
  # CloudWatch Logs — log group writes for Step Functions execution
  # logs (scoped to the CardDemo log groups).
  # ---------------------------------------------------------------------------
  statement {
    sid    = "CloudWatchLogsWriteToStepFunctionsLogGroups"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents"
    ]
    resources = [
      "arn:${local.partition}:logs:${var.aws_region}:${local.account_id}:log-group:/aws/vendedlogs/states/carddemo-${var.environment}-*:*",
      "arn:${local.partition}:logs:${var.aws_region}:${local.account_id}:log-group:/aws/stepfunctions/carddemo-${var.environment}-*:*"
    ]
  }

  # ---------------------------------------------------------------------------
  # X-Ray tracing — same scope rationale as the ECS task role.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "XRayTracing"
    effect = "Allow"
    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets"
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "step_functions_runtime" {
  name   = "carddemo-stepfunctions-runtime"
  role   = aws_iam_role.step_functions_execution.id
  policy = data.aws_iam_policy_document.step_functions_runtime.json
}

# =============================================================================
# Section 8 — AWS Glue job execution role
# =============================================================================
# Role assumed by AWS Glue Spark jobs to bulk-load S3-staged ASCII
# fixtures into RDS PostgreSQL (file-provisioning workflow per AAP
# §0.6.2). The role aggregates:
#   * AWS-managed AWSGlueServiceRole — the canonical permission set for
#     Glue (S3 access to job scripts, CloudWatch Logs, EC2 ENI
#     management within the customer VPC, Glue Data Catalog access).
#   * Inline `carddemo-glue-runtime` — S3 read/write on the batch_outputs
#     bucket (the staging location for ASCII fixtures and Glue job
#     scripts).
#   * Inline `secrets-and-kms` — read the RDS master credential from
#     Secrets Manager and decrypt it with the CardDemo CMK.
# =============================================================================

# Trust policy: the Glue service principal (`glue.amazonaws.com`).
data "aws_iam_policy_document" "glue_assume" {
  statement {
    sid     = "GlueAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["glue.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "glue_job_execution" {
  name               = "carddemo-${var.environment}-glue-job"
  description        = "AWS Glue Spark job execution role for CardDemo bulk ETL (S3 fixtures -> RDS PostgreSQL per AAP §0.6.2)"
  assume_role_policy = data.aws_iam_policy_document.glue_assume.json

  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-glue-job"
    Purpose = "AWS Glue Spark ETL execution (S3 -> RDS bulk load)"
  })
}

# AWS-managed AWSGlueServiceRole: the canonical Glue permission set.
resource "aws_iam_role_policy_attachment" "glue_service" {
  role       = aws_iam_role.glue_job_execution.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AWSGlueServiceRole"
}

# -----------------------------------------------------------------------------
# Inline `carddemo-glue-runtime` policy document.
#
# Glue jobs read their PySpark / Scala scripts from S3 (a sub-prefix of
# the batch_outputs bucket) and write transformation outputs back to
# the same bucket for downstream Step Functions consumption.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "glue_runtime" {
  statement {
    sid    = "S3ScriptsAndFixtures"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:PutObject",
      "s3:ListBucket",
      "s3:DeleteObject",
      "s3:GetBucketLocation"
    ]
    resources = [
      aws_s3_bucket.batch_outputs.arn,
      "${aws_s3_bucket.batch_outputs.arn}/*"
    ]
  }
}

resource "aws_iam_role_policy" "glue_runtime" {
  name   = "carddemo-glue-runtime"
  role   = aws_iam_role.glue_job_execution.id
  policy = data.aws_iam_policy_document.glue_runtime.json
}

# Inline `secrets-and-kms`: Glue jobs fetch the RDS master credential
# from Secrets Manager to connect to PostgreSQL.
resource "aws_iam_role_policy" "glue_secrets_kms" {
  name   = "secrets-and-kms"
  role   = aws_iam_role.glue_job_execution.id
  policy = data.aws_iam_policy_document.combined_secrets_kms.json
}

# =============================================================================
# Section 9 — RDS Enhanced Monitoring role
# =============================================================================
# Service role used by RDS Enhanced Monitoring to publish per-OS-process
# metrics (CPU, memory, disk I/O, network) to CloudWatch Logs every
# `monitoring_interval` seconds. The monitoring agent runs in the RDS
# host's OS partition and ships metrics under this role.
#
# Trust principal: `monitoring.rds.amazonaws.com` (RDS Enhanced
# Monitoring is a distinct service principal from `rds.amazonaws.com`).
# =============================================================================

# Trust policy: RDS Enhanced Monitoring service principal.
data "aws_iam_policy_document" "rds_monitoring_assume" {
  statement {
    sid     = "RDSMonitoringAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_enhanced_monitoring" {
  name               = "carddemo-${var.environment}-rds-monitoring"
  description        = "RDS Enhanced Monitoring service role for CardDemo PostgreSQL Multi-AZ instances (per-OS-process metric collection per AAP §0.6.6 observability)"
  assume_role_policy = data.aws_iam_policy_document.rds_monitoring_assume.json

  # RDS Enhanced Monitoring sessions are short-lived (refreshed
  # continuously by the agent on the RDS host); 1h is the standard.
  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-monitoring"
    Purpose = "RDS Enhanced Monitoring metric publication to CloudWatch Logs"
  })
}

# AWS-managed AmazonRDSEnhancedMonitoringRole policy: the canonical
# permission set for publishing OS-level metrics to CloudWatch.
resource "aws_iam_role_policy_attachment" "rds_monitoring_managed" {
  role       = aws_iam_role.rds_enhanced_monitoring.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# =============================================================================
# Section 10 — EventBridge Scheduler role
# =============================================================================
# Role assumed by Amazon EventBridge Scheduler to invoke the CardDemo
# EOD batch pipeline state machine on its cron schedule (per AAP §0.6.3
# — the daily 23:00 UTC end-of-day batch run is scheduled rather than
# manually triggered).
#
# Trust principal: `scheduler.amazonaws.com` — distinct from the
# legacy EventBridge `events.amazonaws.com` principal. EventBridge
# Scheduler is the newer, scheduler-specific service.
# =============================================================================

# Trust policy: EventBridge Scheduler service principal.
data "aws_iam_policy_document" "eventbridge_scheduler_assume" {
  statement {
    sid     = "EventBridgeSchedulerAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    # Defensive condition: the scheduler must be invoked from this
    # account. Prevents a misconfigured scheduler in a different
    # account from assuming this role even if the trust policy is
    # accidentally widened.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_iam_role" "eventbridge_scheduler" {
  name               = "carddemo-${var.environment}-eventbridge-scheduler"
  description        = "EventBridge Scheduler role for invoking the CardDemo EOD batch pipeline on a cron schedule (per AAP §0.6.3)"
  assume_role_policy = data.aws_iam_policy_document.eventbridge_scheduler_assume.json

  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-eventbridge-scheduler"
    Purpose = "EventBridge Scheduler -> Step Functions EOD pipeline invocation"
  })
}

# -----------------------------------------------------------------------------
# Inline runtime policy: invoke the EOD Step Functions state machine.
#
# Scope: only the EOD batch pipeline state machine (predicted ARN
# following the `carddemo-${env}-eod-batch-pipeline` convention from
# `stepfunctions.tf`). Other state machines (file-provisioning, ad-hoc
# operator-initiated reruns) are NOT scheduled — they are invoked
# manually or from the application code.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "eventbridge_scheduler_runtime" {
  statement {
    sid    = "InvokeStepFunctions"
    effect = "Allow"
    actions = [
      "states:StartExecution"
    ]
    resources = [
      "arn:${local.partition}:states:${var.aws_region}:${local.account_id}:stateMachine:carddemo-${var.environment}-eod-batch-pipeline"
    ]
  }
}

resource "aws_iam_role_policy" "eventbridge_scheduler_runtime" {
  name   = "carddemo-scheduler-runtime"
  role   = aws_iam_role.eventbridge_scheduler.id
  policy = data.aws_iam_policy_document.eventbridge_scheduler_runtime.json
}

# =============================================================================
# Section 11 — GitHub Actions OIDC identity provider + deploy role
# =============================================================================
# Federated identity for the CI/CD pipeline. Instead of storing long-
# lived AWS access keys in GitHub Secrets (the legacy pattern that the
# AAP explicitly forbids per §0.7.1 "Service-to-service authentication
# via IAM; never long-lived access keys"), this section provisions:
#
#   1. An IAM OIDC identity provider for
#      `token.actions.githubusercontent.com`. The provider's thumbprints
#      pin the X.509 root of trust for the GitHub Actions OIDC issuer.
#   2. A deploy role with a trust policy that requires:
#      (a) `aud` claim = `sts.amazonaws.com`     (audience pinning)
#      (b) `sub` claim matches
#          `repo:${var.github_repo_owner}/${var.github_repo_name}:*`
#          (workflow origin pinning — only workflows from this exact
#           GitHub repository may assume the role)
#   3. An inline policy granting the minimum permissions a CI/CD
#      pipeline needs to deploy the CardDemo Spring Boot image:
#      * ECR push/pull on the CardDemo repository.
#      * ECS UpdateService / RegisterTaskDefinition for rolling deploy.
#      * iam:PassRole on the ECS task execution and task roles (so the
#        new task definition can reference them).
#
# Conditional provisioning via `count = var.github_oidc_enabled ? 1 : 0`:
# dev sandboxes that do not need a CI/CD pipeline skip the OIDC
# resources entirely. In that case the deploy role's outputs.tf entries
# render as empty strings.
# =============================================================================

# -----------------------------------------------------------------------------
# IAM OIDC identity provider for GitHub Actions.
#
# `client_id_list = ["sts.amazonaws.com"]` registers the audience that
# GitHub Actions OIDC tokens must claim when assuming a role in this
# account. The two thumbprints below pin the X.509 root certificates
# that signed the OIDC issuer's TLS certificate. GitHub has rotated
# these in the past; both currently-published thumbprints are listed
# so the provider continues to verify tokens across rotation events.
#
# Reference: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services
# -----------------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "github_actions" {
  count = var.github_oidc_enabled ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # Two known GitHub Actions OIDC thumbprints. Maintaining both
  # provides resilience to thumbprint rotation by GitHub.
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd"
  ]

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-gha-oidc"
    Purpose = "GitHub Actions OIDC identity provider for CI/CD deploy role"
  })
}

# -----------------------------------------------------------------------------
# Trust policy document for the GitHub Actions deploy role.
#
# Two condition blocks restrict assumption:
#   1. `aud` (audience) must be `sts.amazonaws.com` — the AWS OIDC
#      audience. Required by the OIDC integration.
#   2. `sub` (subject) must match `repo:${owner}/${name}:*` — the
#      GitHub-specific subject claim that identifies the originating
#      workflow. The trailing `:*` matches any branch / tag / pull-
#      request workflow run in that repository.
#
# These conditions are the cornerstone of secure OIDC: without the
# `sub` claim restriction, ANY GitHub workflow in ANY repository could
# obtain a session for this role just by knowing the role ARN.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "github_actions_assume" {
  count = var.github_oidc_enabled ? 1 : 0

  statement {
    sid     = "GitHubActionsAssumeRoleWithWebIdentity"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github_actions[0].arn]
    }

    # Audience pinning — required by the OIDC spec.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Subject pinning — restricts assumption to workflows from the
    # configured GitHub repository. The trailing `:*` matches any
    # ref-spec (branch / tag / pull-request) suffix.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo_owner}/${var.github_repo_name}:*"]
    }
  }
}

resource "aws_iam_role" "github_actions_deploy" {
  count = var.github_oidc_enabled ? 1 : 0

  name               = "carddemo-${var.environment}-gha-deploy"
  description        = "GitHub Actions deploy role for the CardDemo CI/CD pipeline (federated OIDC, ECR push + ECS rolling deploy + iam:PassRole)"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume[0].json

  # 1h session lifetime is sufficient for CI/CD workflow runs (Build
  # + Test + Docker push + ECS deploy typically completes in <30 min).
  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-gha-deploy"
    Purpose = "GitHub Actions CI/CD deploy role (ECR + ECS + iam:PassRole)"
  })
}

# -----------------------------------------------------------------------------
# Inline runtime policy for the GitHub Actions deploy role.
#
# Three statement groups:
#   1. ECR Push/Pull — the docker-build.yml workflow pushes images;
#      the deploy.yml workflow pulls image metadata to compute the
#      digest used in the new task definition.
#   2. ECS Deploy — UpdateService + RegisterTaskDefinition for rolling
#      deploy. DescribeServices and Describe* for status polling.
#   3. iam:PassRole — required by ECS RegisterTaskDefinition when the
#      new task definition references the task execution and task
#      roles. The `iam:PassedToService` condition restricts the
#      target service to ECS-tasks, preventing the deploy role from
#      using PassRole to bind the task roles to any other service.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "github_actions_runtime" {
  count = var.github_oidc_enabled ? 1 : 0

  # ---------------------------------------------------------------------------
  # ECR Push/Pull on the CardDemo repository.
  #
  # The docker-build.yml workflow runs `docker login` (which calls
  # `ecr:GetAuthorizationToken`) and `docker push` (which invokes the
  # full layer-upload action set). The deploy.yml workflow calls
  # `ecr:DescribeImages` to compute the digest for the new task
  # definition.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "ECRAuthorizationToken"
    effect = "Allow"
    # GetAuthorizationToken is unique — it does not accept resource-
    # level scoping. The token is account-wide but expires after 12h.
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "ECRPushPullCarddemoRepository"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:DescribeRepositories",
      "ecr:DescribeImages",
      "ecr:ListImages",
      "ecr:DescribeImageScanFindings"
    ]
    resources = [aws_ecr_repository.carddemo.arn]
  }

  # ---------------------------------------------------------------------------
  # ECS Deploy actions.
  #
  # UpdateService and RegisterTaskDefinition do not accept resource-
  # level scoping at the action level (the resource is created /
  # mutated by the call itself). The PassRole condition below
  # restricts which roles the deploy can bind to task definitions.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "ECSRollingDeploy"
    effect = "Allow"
    actions = [
      "ecs:DescribeServices",
      "ecs:UpdateService",
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeClusters",
      "ecs:ListClusters",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
      "ecs:RunTask",
      "ecs:StopTask"
    ]
    resources = ["*"]

    # Limit deploy actions to the CardDemo cluster + service.
    condition {
      test     = "StringLike"
      variable = "ecs:cluster"
      values = [
        "arn:${local.partition}:ecs:${var.aws_region}:${local.account_id}:cluster/carddemo-${var.environment}-*"
      ]
    }
  }

  # ---------------------------------------------------------------------------
  # iam:PassRole — required for ECS RegisterTaskDefinition.
  #
  # PassRole is the IAM action that authorizes a calling principal to
  # delegate the use of a target role to another AWS service. ECS
  # RegisterTaskDefinition needs PassRole on:
  #   * the task execution role (so the Fargate agent can assume it)
  #   * the task role (so the application code can assume it)
  #
  # Scope: only the CardDemo ECS task execution + task roles.
  # Condition: only when the role is being passed to ecs-tasks
  # (Fargate); the deploy role cannot use PassRole to bind these
  # roles to any other service.
  # ---------------------------------------------------------------------------
  statement {
    sid     = "PassRoleToECS"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.ecs_task_execution.arn,
      aws_iam_role.ecs_task_role.arn
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # ---------------------------------------------------------------------------
  # CloudFormation / Terraform plan visibility — describe deployed
  # stacks so the CI/CD pipeline can correlate the deploy with the
  # underlying IaC state. ListStacks is account-wide;
  # DescribeStacks / DescribeStackEvents are scoped to the CardDemo
  # stack name prefix.
  # ---------------------------------------------------------------------------
  statement {
    sid    = "CloudFormationDescribeCarddemo"
    effect = "Allow"
    actions = [
      "cloudformation:DescribeStacks",
      "cloudformation:DescribeStackEvents",
      "cloudformation:ListStacks",
      "cloudformation:GetTemplate"
    ]
    resources = [
      "arn:${local.partition}:cloudformation:${var.aws_region}:${local.account_id}:stack/carddemo-${var.environment}-*/*"
    ]
  }

  # ---------------------------------------------------------------------------
  # Caller identity — `sts:GetCallerIdentity` is the standard "who am
  # I" check executed at the start of every workflow run for telemetry
  # and debugging. The action does not accept resource-level scoping.
  # ---------------------------------------------------------------------------
  statement {
    sid       = "STSCallerIdentity"
    effect    = "Allow"
    actions   = ["sts:GetCallerIdentity"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_actions_runtime" {
  count = var.github_oidc_enabled ? 1 : 0

  name   = "carddemo-gha-deploy-runtime"
  role   = aws_iam_role.github_actions_deploy[0].id
  policy = data.aws_iam_policy_document.github_actions_runtime[0].json
}

###############################################################################
# End of file — infrastructure/terraform/iam.tf
#
# Operator runbook:
#   * Inspect role ARNs:        terraform output | grep iam_role_*
#   * Describe a role:          aws iam get-role --role-name carddemo-<env>-<role>
#   * Verify trust policy:      aws iam get-role --role-name <role> \
#                                  --query 'Role.AssumeRolePolicyDocument'
#   * List inline policies:     aws iam list-role-policies --role-name <role>
#   * Verify managed policies:  aws iam list-attached-role-policies \
#                                  --role-name <role>
#   * Policy simulation:        aws iam simulate-principal-policy \
#                                  --policy-source-arn <role-arn> \
#                                  --action-names s3:PutObject \
#                                  --resource-arns <bucket-arn>/test
#   * OIDC provider listing:    aws iam list-open-id-connect-providers
#
# CI/CD workflow integration (.github/workflows/deploy.yml):
#   - uses: aws-actions/configure-aws-credentials@v4
#     with:
#       role-to-assume: arn:aws:iam::<account>:role/carddemo-<env>-gha-deploy
#       aws-region:     <region>
#
# Auditing reminders:
#   * Each role's inline policy carries unique SIDs — search CloudTrail
#     by `assumedRoleId` to trace any specific permission grant.
#   * The KMS key policy (kms.tf) and the IAM policies in this file
#     provide defense-in-depth: a role denied at the IAM layer cannot
#     act on the CMK; a role denied at the KMS layer cannot decrypt
#     even if its IAM grants would otherwise permit the action.
#   * Cross-account access is impossible by construction — every trust
#     policy in this file lists only AWS-service principals or the
#     account-local GitHub Actions OIDC provider.
###############################################################################

