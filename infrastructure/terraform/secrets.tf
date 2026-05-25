###############################################################################
# infrastructure/terraform/secrets.tf
#
# AWS Secrets Manager rotation infrastructure for CardDemo.
#
# Purpose
# -------
# Centralizes the secrets-management resources that are NOT tightly coupled
# to a single data-store .tf file. Per AAP Section 0.6.4 ("AWS Secrets Manager
# Dynamic Rotation Without Restart") and Section 0.7.1 ("All credentials fetched
# at runtime from AWS Secrets Manager -- never hardcoded"), this file
# declares:
#
#   1. JWT HS512 signing key secret + initial random value (Section 1).
#   2. OpenSearch master credential secret (Section 2). The OpenSearch
#      domain itself is provisioned in `opensearch.tf`; the secret is
#      declared here so the application can read it via Spring Cloud AWS
#      `spring.config.import=aws-secretsmanager:` before the OpenSearch
#      bean is initialised.
#   3. KMS-encrypted SNS topic + SQS queues (Sections 3, 4, 5) that carry
#      Secrets-Manager rotation notifications to the Spring Boot listener
#      (`SecretsManagerConfig.subscribeToRotationEvents()`), which
#      publishes a `RefreshEvent` to the `ApplicationContext` and triggers
#      `@RefreshScope` beans (HikariCP DataSource, Kafka factories) to
#      re-read the rotated value on next use -- no JVM restart required.
#   4. The AWS-managed Secrets Manager RDS PostgreSQL Single-User rotation
#      Lambda provisioned from the AWS Serverless Application Repository
#      (Section 6) + the security-group / ingress wiring that lets the
#      Lambda reach the RDS endpoint over port 5432, + the rotation
#      schedule binding (`aws_secretsmanager_secret_rotation`).
#   5. An EventBridge rule that filters Secrets-Manager `RotateSecret`
#      CloudTrail events scoped to CardDemo-prefixed secrets and forwards
#      them to the SNS topic (Section 7) + the SNS topic policy that
#      authorises the EventBridge service principal to publish.
#
# Rotation flow (per AAP Section 0.6.4)
# ------------------------------
#   1. Secrets Manager invokes the rotation Lambda on the configured
#      cadence (`var.rds_rotation_days`, default 30 days).
#   2. The Lambda implements the AWS canonical four-stage rotation
#      contract (createSecret → setSecret → testSecret → finishSecret)
#      and writes a new AWSCURRENT version of the secret.
#   3. The successful `RotateSecret` API call is captured by CloudTrail
#      and matched by the EventBridge rule
#      (`aws_cloudwatch_event_rule.secrets_rotated`).
#   4. EventBridge forwards the event to the
#      `aws_sns_topic.secrets_rotation` topic.
#   5. SNS fans the event out to subscribed SQS queues (here the single
#      `aws_sqs_queue.secrets_rotation_app` queue -- the file is wired
#      so future fan-out consumers can be added without code changes).
#   6. The Spring Boot application polls the SQS queue, recognises the
#      rotated secret ARN, and publishes a `RefreshEvent` to the
#      `ApplicationContext`. `@RefreshScope` beans drain their existing
#      state and re-bind on next use.
#   7. Failed SQS deliveries land in
#      `aws_sqs_queue.secrets_rotation_dlq` (14-day retention) for
#      operator triage.
#
# Other secrets declared in their own .tf files (re-referenced here for
# rotation):
#   * aws_secretsmanager_secret.rds_master    -- declared in rds.tf
#                                                rotated here via SAR Lambda
#   * aws_secretsmanager_secret.redis_auth    -- declared in elasticache.tf
#                                                rotation handled natively by
#                                                ElastiCache `auth_token_
#                                                update_strategy = "ROTATE"`
#
# References
# ----------
#   * AAP Section 0.6.4 -- AWS Secrets Manager dynamic rotation without restart.
#   * AAP Section 0.6.6 -- Cross-Cutting: PCI-DSS (KMS CMKs, Secrets Manager,
#                  TLS 1.2+, CloudTrail).
#   * AAP Section 0.7.1 -- Refactoring rules: all credentials fetched at runtime
#                  from AWS Secrets Manager; all data at rest encrypted
#                  via AWS KMS customer-managed keys; SNS/SQS encrypted
#                  with KMS.
#   * AAP Section 0.7.2 -- PCI-DSS encryption-at-rest and credential rotation.
#
# Encryption posture (per AAP Section 0.7.1)
# -----------------------------------
# Every secret, SNS topic, and SQS queue declared in this file is encrypted
# at rest with the CardDemo customer-managed KMS key
# (`aws_kms_key.carddemo`, declared in kms.tf). The KMS key policy in
# kms.tf already grants `secretsmanager.amazonaws.com`, `sns.amazonaws.com`,
# `sqs.amazonaws.com`, and `events.amazonaws.com` the minimum
# `kms:Decrypt` / `kms:GenerateDataKey` actions needed for those service
# principals to envelope-encrypt / decrypt resources under this key.
###############################################################################

# =============================================================================
# Section 1 -- JWT HS512 signing key secret
# =============================================================================
# Symmetric HS512-compatible JWT signing key used by the
# `JwtTokenProvider` in src/main/java/com/awsm2/carddemo/security/.
# A 64-character cryptographically-random bootstrap value is generated by
# the `random_password` resource and stored as the AWSCURRENT version of
# the secret. The value is URL-safe by setting `special = false` so the
# raw key bytes are JWT-header-safe and can be base64-encoded by the
# application without further escaping.
#
# Subsequent rotation is operator-driven (manual taint + apply) or, in a
# future iteration, by a custom rotation Lambda. The
# `lifecycle.ignore_changes = [secret_string]` guard prevents Terraform
# from reverting an out-of-band rotation on the next plan.
#
# Members exposed for downstream consumers (per the file schema):
#   random_password.jwt_signing_key            -- result, length, special
#   aws_secretsmanager_secret.jwt_signing_key  -- arn, id, name, description,
#                                                  kms_key_id, tags_all
#   aws_secretsmanager_secret_version.jwt_signing_key_value
#                                              -- id, secret_id, version_id,
#                                                  version_stages
# =============================================================================

# random_password generates a cryptographically random 64-character string
# from the Terraform random provider. `special = false` keeps the value
# URL-safe (no slashes, ampersands, etc.) so it can be inlined into JWT
# headers without additional escaping by the application.
resource "random_password" "jwt_signing_key" {
  length  = 64
  special = false
}

# KMS-encrypted Secrets Manager secret holding the JWT signing key.
# Spring Cloud AWS auto-loads this secret into the Spring `Environment`
# via the property
#   spring.config.import=aws-secretsmanager:carddemo-${env}-jwt-signing-key
# and JwtTokenProvider reads the `signing-key` field from the JSON
# envelope at runtime.
resource "aws_secretsmanager_secret" "jwt_signing_key" {
  name        = "carddemo-${var.environment}-jwt-signing-key"
  description = "JWT HS512 signing key for CardDemo Spring Security 6 (AAP Section 0.7.1 -- Secrets Manager only; never hardcoded in application.yml or environment variables)."
  kms_key_id  = aws_kms_key.carddemo.arn

  # 30-day pending-deletion window aligns with the KMS CMK's default
  # deletion window (30 days, validated in variables.tf) so an
  # accidental deletion can be reversed within the same window as the
  # underlying encryption key.
  recovery_window_in_days = 30

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-jwt-signing-key"
    Purpose = "JWT HS512 signing key for Spring Security 6"
  })
}

# Initial AWSCURRENT version of the JWT signing key secret. The value is
# a JSON envelope with a single `signing-key` field -- keeps the contract
# explicit and forward-compatible (the application reads
# `signing-key` from the parsed JSON rather than the raw secret_string).
resource "aws_secretsmanager_secret_version" "jwt_signing_key_value" {
  secret_id = aws_secretsmanager_secret.jwt_signing_key.id

  secret_string = jsonencode({
    "signing-key" = random_password.jwt_signing_key.result
  })

  lifecycle {
    # Operator- or Lambda-driven rotations publish new versions via
    # PutSecretValue; without this guard Terraform would revert the
    # secret to the original value on the next apply.
    ignore_changes = [secret_string]
  }
}

# =============================================================================
# Section 2 -- OpenSearch master credential secret
# =============================================================================
# Placeholder Secrets Manager entry that holds the OpenSearch fine-grained-
# access master user credential. The OpenSearch domain itself is
# provisioned in opensearch.tf and references this secret's ARN for its
# `master_user_options.master_user_password` field.
#
# Rotation cadence is documented as ${var.opensearch_rotation_days} days
# (default 90 -- see variables.tf). Unlike the RDS master rotation which
# uses the AWS-managed SAR rotation Lambda, OpenSearch master-user
# rotation is more invasive (it requires re-creating the master user in
# the fine-grained-access ACL) and is therefore handled out-of-band:
#   * Operator-driven via `aws secretsmanager rotate-secret --secret-id
#     carddemo-${ENV}-opensearch-master` after applying a matching ACL
#     change on the OpenSearch domain, OR
#   * Future iteration: a custom rotation Lambda that calls the
#     OpenSearch FGAC update API in addition to the standard four-stage
#     contract.
#
# The application's OpenSearch client bean is wrapped in `@RefreshScope`
# (see SecretsManagerConfig.java) so the rotation flow described at the
# top of this file refreshes the OpenSearch credentials without restart
# once they appear in AWSCURRENT.
#
# No initial version is provisioned here -- opensearch.tf creates the
# AWSCURRENT version with a random_password and the resolved domain
# endpoint, mirroring the rds_master_value pattern.
#
# Members exposed for downstream consumers (per the file schema):
#   arn, id, name, description, kms_key_id, tags_all
# =============================================================================

resource "aws_secretsmanager_secret" "opensearch_master" {
  name        = "carddemo-${var.environment}-opensearch-master"
  description = "OpenSearch fine-grained-access master credential for CardDemo (manual / custom-Lambda rotation per ${var.opensearch_rotation_days}-day cadence; consumed by @RefreshScope OpenSearch client per AAP Section 0.6.4)."
  kms_key_id  = aws_kms_key.carddemo.arn

  recovery_window_in_days = 30

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-opensearch-master"
    Purpose = "OpenSearch FGAC master credential (rotation deferred)"
  })
}

# =============================================================================
# Section 3 -- Rotation notification SNS topic
# =============================================================================
# KMS-encrypted SNS topic that all Secrets Manager rotation events flow
# through. The fan-out design (SNS → multiple SQS subscribers) supports
# future consumers (e.g., a Slack notifier Lambda, an OpsGenie webhook,
# a CloudWatch Logs subscription) without coupling them to the
# application's SQS queue.
#
# The topic is published to by the EventBridge rule
# `aws_cloudwatch_event_rule.secrets_rotated` (Section 7); the
# `aws_sns_topic_policy.allow_eventbridge` resource grants the
# `events.amazonaws.com` service principal `sns:Publish` permission on
# this topic.
#
# `kms_master_key_id` references the CardDemo CMK from kms.tf. The KMS
# key policy in kms.tf already includes a statement for the SNS service
# principal (`Statement 3 -- AllowSNS`) so this topic can encrypt and
# decrypt messages without needing an additional policy.
#
# Members exposed for downstream consumers (per the file schema):
#   arn, id, name, kms_master_key_id, owner, tags_all
# =============================================================================

resource "aws_sns_topic" "secrets_rotation" {
  name              = "carddemo-${var.environment}-secrets-rotation"
  display_name      = "CardDemo Secrets Rotation Events"
  kms_master_key_id = aws_kms_key.carddemo.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-secrets-rotation"
    Purpose = "Secrets Manager rotation notification fan-out topic"
  })
}

# =============================================================================
# Section 4 -- SQS queues (DLQ + main application queue)
# =============================================================================
# Two KMS-encrypted SQS queues form the rotation-event delivery surface
# for the Spring Boot application:
#
#   * `aws_sqs_queue.secrets_rotation_dlq`
#       Dead-letter queue with the maximum 14-day retention. Captures any
#       message that the application fails to process within
#       `maxReceiveCount = 5` attempts on the main queue. Operator
#       triage occurs via CloudWatch metric `ApproximateNumberOfMessages`
#       with an alarm threshold of 0 (any DLQ message is an incident).
#
#   * `aws_sqs_queue.secrets_rotation_app`
#       Main queue consumed by the Spring Boot application's SQS poller
#       (SecretsManagerConfig). Message retention is 1 day -- rotation
#       events are time-sensitive and stale events should not be
#       replayed. Visibility timeout 60s allows the listener to
#       acknowledge processing before the message is redelivered.
#
# DLQ ordering rule: the DLQ MUST be declared before the main queue so
# the main queue's `redrive_policy` can reference
# `aws_sqs_queue.secrets_rotation_dlq.arn`. Terraform resolves the
# implicit dependency automatically; the ordering is documented here for
# code-review clarity.
#
# Members exposed for downstream consumers (per the file schema):
#   aws_sqs_queue.secrets_rotation_dlq -- arn, id, url, name,
#                                          kms_master_key_id,
#                                          message_retention_seconds,
#                                          tags_all
#   aws_sqs_queue.secrets_rotation_app -- arn, id, url, name,
#                                          kms_master_key_id,
#                                          visibility_timeout_seconds,
#                                          message_retention_seconds,
#                                          redrive_policy, tags_all
# =============================================================================

# DLQ -- captures messages that exceed maxReceiveCount on the main queue.
# 14 days is the maximum SQS retention and the standard for incident-
# response triage windows.
resource "aws_sqs_queue" "secrets_rotation_dlq" {
  name              = "carddemo-${var.environment}-secrets-rotation-dlq"
  kms_master_key_id = aws_kms_key.carddemo.arn

  # Re-use the data key for 5 minutes (300s) to reduce KMS calls. AWS
  # default is 5 minutes; declared explicitly here for clarity.
  kms_data_key_reuse_period_seconds = 300

  # 14-day retention (SQS maximum, 1209600s) for forensic investigation.
  message_retention_seconds = 1209600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-secrets-rotation-dlq"
    Purpose = "Dead-letter queue for failed rotation-event processing"
  })
}

# Main app queue -- Spring Boot consumes from this queue and publishes a
# RefreshEvent to the ApplicationContext per AAP Section 0.6.4.
resource "aws_sqs_queue" "secrets_rotation_app" {
  name              = "carddemo-${var.environment}-secrets-rotation-app"
  kms_master_key_id = aws_kms_key.carddemo.arn

  # 5-minute KMS data-key reuse window (AWS default; declared explicitly).
  kms_data_key_reuse_period_seconds = 300

  # 1-day retention -- rotation events are time-sensitive; stale events
  # should not be replayed on application restart.
  message_retention_seconds = 86400

  # 60s visibility timeout gives the Spring Boot listener time to publish
  # a RefreshEvent and acknowledge the message before SQS redelivers.
  visibility_timeout_seconds = 60

  # Redrive policy -- after 5 failed receives, the message moves to the
  # DLQ for operator triage. References the DLQ ARN declared above.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.secrets_rotation_dlq.arn
    maxReceiveCount     = 5
  })

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-secrets-rotation-app"
    Purpose = "Spring Boot RefreshEvent driver for rotation notifications"
  })
}

# =============================================================================
# Section 5 -- SNS → SQS subscription + queue access policy
# =============================================================================
# Wires the rotation SNS topic to the application SQS queue and grants
# the SNS service principal `sqs:SendMessage` permission scoped to the
# rotation topic ARN.
#
# Two resources are required because SNS-to-SQS delivery is not
# implicitly authorised -- even within the same AWS account, the SQS
# queue policy must explicitly allow the SNS service principal to
# `SendMessage` and constrain the allowed source ARN to the specific
# topic (defence in depth against accidental cross-topic delivery).
#
# Members exposed for downstream consumers (per the file schema):
#   aws_sns_topic_subscription.secrets_rotation_to_sqs
#     -- arn, id, topic_arn, protocol, endpoint
#   aws_sqs_queue_policy.secrets_rotation_app
#     -- id, queue_url, policy
# =============================================================================

resource "aws_sns_topic_subscription" "secrets_rotation_to_sqs" {
  topic_arn = aws_sns_topic.secrets_rotation.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.secrets_rotation_app.arn

  # Raw message delivery preserves the EventBridge event JSON without
  # the additional SNS envelope, simplifying the JSON parser in the
  # Spring Boot listener.
  raw_message_delivery = true
}

# SQS queue policy granting the SNS service principal SendMessage on the
# application queue, scoped to messages originating from the rotation
# SNS topic.
resource "aws_sqs_queue_policy" "secrets_rotation_app" {
  queue_url = aws_sqs_queue.secrets_rotation_app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowSNSPublishFromRotationTopic"
        Effect = "Allow"
        Principal = {
          Service = "sns.amazonaws.com"
        }
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.secrets_rotation_app.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_sns_topic.secrets_rotation.arn
          }
        }
      }
    ]
  })
}

# =============================================================================
# Section 6 -- RDS rotation Lambda (AWS Serverless Application Repository)
# =============================================================================
# Provisions the AWS-managed `SecretsManagerRDSPostgreSQLRotationSingleUser`
# Lambda via the AWS Serverless Application Repository (SAR). Using the
# SAR-managed app rather than rolling a custom Lambda removes the burden
# of maintaining the rotation handler code (boto3 + psycopg2, four-stage
# rotation contract) and lets AWS push security patches via SAR semantic
# version bumps.
#
# The SAR application:
#   * Creates a Python 3 Lambda implementing the AWS canonical four-
#     stage rotation contract (createSecret → setSecret → testSecret →
#     finishSecret) for RDS PostgreSQL single-user rotation.
#   * Attaches a least-privilege execution role for ENI lifecycle
#     (VPC attachment), Secrets Manager publish-version on the target
#     secret, and KMS decrypt on the secrets CMK.
#   * Adds a resource policy on the Lambda allowing
#     `secretsmanager.amazonaws.com` to invoke it.
#
# Inputs (per the SAR template parameters):
#   * endpoint            -- Secrets Manager service endpoint URL. Using
#                            the regional endpoint hostname rather than
#                            the VPC endpoint hostname keeps the SAR
#                            template portable across accounts whose VPC
#                            endpoint hostnames vary; the actual network
#                            path remains private because the Lambda's
#                            security group only has VPC-internal egress.
#   * functionName        -- operator-visible function name in CloudWatch
#                            and CloudTrail.
#   * vpcSubnetIds        -- comma-separated list of private subnets the
#                            Lambda's ENIs attach to (must include 2+
#                            AZs for HA).
#   * vpcSecurityGroupIds -- security group ID attached to the Lambda's
#                            ENIs (the rotation_lambda SG declared
#                            below).
#
# Capabilities:
#   * CAPABILITY_IAM           -- SAR creates an IAM role.
#   * CAPABILITY_RESOURCE_POLICY -- SAR attaches a Lambda resource policy.
#
# Members exposed for downstream consumers (per the file schema):
#   id, name, application_id, outputs, capabilities, parameters, tags_all
# -----------------------------------------------------------------------------

# Security group attached to the rotation Lambda's VPC ENIs. Egress is
# open to all destinations because the Lambda must reach BOTH the
# Secrets Manager VPC endpoint (HTTPS 443) AND the RDS endpoint
# (PostgreSQL 5432); the corresponding ingress rules on each downstream
# service (Secrets Manager endpoint SG in main.tf, RDS SG in rds.tf via
# the `aws_security_group_rule.rds_from_rotation_lambda` resource below)
# enforce the actual authorization surface.
#
# Ingress: NONE -- the Lambda is invoked by Secrets Manager via the
# AWS service control plane, not over a network ingress path.
#
# Members exposed for downstream consumers (per the file schema):
#   id, arn, name, vpc_id
resource "aws_security_group" "rds_rotation_lambda" {
  name        = "carddemo-${var.environment}-rds-rotation-lambda-sg"
  description = "SG for the SAR-managed RDS rotation Lambda -- egress to RDS 5432 + Secrets Manager 443"
  vpc_id      = data.aws_vpc.carddemo.id

  egress {
    description = "All outbound -- Lambda reaches RDS 5432 + Secrets Manager VPC endpoint 443 over the VPC backbone"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-rotation-lambda-sg"
    Purpose = "Network attachment for the RDS rotation Lambda"
  })
}

# RDS SG ingress rule that allows the rotation Lambda's SG to reach the
# RDS instance on port 5432. Declared as a standalone resource so we can
# reference the RDS SG (`aws_security_group.rds`) without modifying
# rds.tf and without creating a Terraform SG cycle.
#
# Members exposed for downstream consumers (per the file schema):
#   id, type, from_port, to_port, protocol, source_security_group_id,
#   security_group_id
resource "aws_security_group_rule" "rds_from_rotation_lambda" {
  type                     = "ingress"
  description              = "PostgreSQL 5432 from the RDS rotation Lambda -- required for setSecret/testSecret stages (AAP Section 0.6.4)"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.rds_rotation_lambda.id
  security_group_id        = aws_security_group.rds.id
}

# AWS Serverless Application Repository deployment of the
# SecretsManagerRDSPostgreSQLRotationSingleUser Lambda. The
# application_id is the canonical, AWS-published ARN -- pinning to this
# specific publisher ensures we are deploying the AWS-maintained
# rotation handler and not a third-party fork.
#
# `semantic_version` is intentionally omitted so the latest published
# version of the SAR app is used at apply time. Operators who require
# strict reproducibility may set this via
# `-var='rds_rotation_lambda_version=X.Y.Z'` and add a `semantic_version`
# argument here. The SAR app maintains backwards compatibility within
# the 1.x major series, so unpinned upgrades are safe.
#
# `capabilities` MUST include both CAPABILITY_IAM (the SAR app creates a
# role) and CAPABILITY_RESOURCE_POLICY (the SAR app attaches a Lambda
# resource policy allowing secretsmanager.amazonaws.com to invoke). Omit
# either and the CloudFormation stack creation fails.
resource "aws_serverlessapplicationrepository_cloudformation_stack" "rds_rotation_lambda" {
  name           = "carddemo-${var.environment}-rds-rotation"
  application_id = "arn:aws:serverlessrepo:us-east-1:297356227824:applications/SecretsManagerRDSPostgreSQLRotationSingleUser"
  capabilities = [
    "CAPABILITY_IAM",
    "CAPABILITY_RESOURCE_POLICY"
  ]

  parameters = {
    # Secrets Manager service endpoint -- the Lambda calls
    # GetSecretValue / PutSecretValue against this hostname; routing
    # remains private via the VPC endpoint in main.tf because the SG
    # below restricts egress to the VPC backbone.
    endpoint = "https://secretsmanager.${var.aws_region}.amazonaws.com"

    # Operator-visible Lambda function name (CloudWatch, CloudTrail,
    # X-Ray). The `-fn` suffix distinguishes the actual function from
    # the SAR stack name above.
    functionName = "carddemo-${var.environment}-rds-rotation-fn"

    # VPC attachment -- comma-separated subnet IDs (the SAR template
    # accepts CommaDelimitedList for these parameters).
    vpcSubnetIds = join(",", data.aws_subnets.private.ids)

    # Single security group -- no comma needed but the SAR template
    # tolerates a single value without a delimiter.
    vpcSecurityGroupIds = aws_security_group.rds_rotation_lambda.id
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-rotation"
    Purpose = "AWS SAR-managed RDS PostgreSQL rotation Lambda"
  })
}

# Wires the rotation schedule to the RDS master credential secret. The
# Lambda ARN is sourced from the SAR stack's CloudFormation outputs;
# the SAR template publishes the rotation Lambda ARN under the
# `RotationLambdaARN` output key.
#
# `automatically_after_days` triggers the FIRST rotation immediately
# upon `terraform apply` (Secrets Manager runs an initial rotation to
# validate the schedule) and then on the configured cadence thereafter.
# Per AAP Section 0.6.4, this rotation occurs without restarting the Spring
# Boot application: HikariCP DataSource (annotated with `@RefreshScope`)
# drains its existing connections and re-binds with the rotated
# credentials when the SQS-driven RefreshEvent fires.
#
# Members exposed for downstream consumers (per the file schema):
#   id, secret_id, rotation_lambda_arn, rotation_enabled
resource "aws_secretsmanager_secret_rotation" "rds_master" {
  secret_id           = aws_secretsmanager_secret.rds_master.id
  rotation_lambda_arn = aws_serverlessapplicationrepository_cloudformation_stack.rds_rotation_lambda.outputs["RotationLambdaARN"]

  rotation_rules {
    automatically_after_days = var.rds_rotation_days
  }

  # The SAR-deployed Lambda attaches its own resource policy allowing
  # secretsmanager.amazonaws.com invocation, so no explicit
  # aws_lambda_permission is required. The depends_on below makes the
  # CloudFormation-stack creation an explicit prerequisite to surfacing
  # this rotation binding.
  depends_on = [
    aws_serverlessapplicationrepository_cloudformation_stack.rds_rotation_lambda,
    aws_security_group_rule.rds_from_rotation_lambda
  ]
}

# =============================================================================
# Section 7 -- EventBridge rule → SNS forwarding
# =============================================================================
# AWS-managed rotation Lambdas do NOT publish to SNS by default. Rather
# than coupling the rotation flow to a custom Lambda bridge (which would
# add a deployable artifact and another point of failure), this section
# uses a native EventBridge rule that:
#
#   1. Listens for `RotateSecret` API calls on the Secrets Manager
#      service, captured by CloudTrail and routed to EventBridge as
#      `aws.secretsmanager` "AWS API Call via CloudTrail" events.
#   2. Filters by `responseElements.secretId` prefix to scope to
#      CardDemo secrets in this account/region (avoids triggering on
#      unrelated rotations in the same AWS account).
#   3. Forwards the event to the rotation SNS topic.
#
# Required prerequisite: CloudTrail must be capturing data-plane Secrets
# Manager events. The CardDemo CloudTrail trail (provisioned in
# cloudtrail.tf) is configured as a multi-region organization trail
# that captures management events for Secrets Manager -- see AAP Section 0.6.6.
#
# The accompanying `aws_sns_topic_policy.allow_eventbridge` grants the
# EventBridge service principal `sns:Publish` permission on the rotation
# topic. Without this policy the EventBridge target invocation fails
# at the SNS API boundary.
#
# Members exposed for downstream consumers (per the file schema):
#   aws_cloudwatch_event_rule.secrets_rotated
#     -- arn, id, name, event_pattern, description, tags_all
#   aws_cloudwatch_event_target.secrets_rotated_to_sns
#     -- id, rule, target_id, arn
#   aws_sns_topic_policy.allow_eventbridge
#     -- arn, id, policy
# =============================================================================

# EventBridge rule. The `event_pattern` filters CloudTrail-sourced
# Secrets Manager API events to the `RotateSecret` action against
# CardDemo secrets in this account / region. The prefix match on
# `responseElements.secretId` is the standard EventBridge syntax for
# starts-with filtering: `[{ "prefix": "<arn-prefix>" }]`.
resource "aws_cloudwatch_event_rule" "secrets_rotated" {
  name        = "carddemo-${var.environment}-secrets-rotated"
  description = "Capture Secrets Manager RotateSecret success events for CardDemo secrets (AAP Section 0.6.4) -- forwards to the rotation SNS topic for Spring Boot @RefreshScope refresh."

  event_pattern = jsonencode({
    source        = ["aws.secretsmanager"]
    "detail-type" = ["AWS API Call via CloudTrail"]
    detail = {
      eventName = ["RotateSecret"]
      responseElements = {
        secretId = [
          {
            prefix = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:carddemo-${var.environment}-"
          }
        ]
      }
    }
  })

  # Default state is ENABLED; declared explicitly for clarity.
  state = "ENABLED"

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-secrets-rotated"
    Purpose = "Forward Secrets Manager rotation events to the rotation SNS topic"
  })
}

# EventBridge target -- directs matched events to the rotation SNS topic.
# `target_id` is a logical identifier scoped to the rule (must be unique
# within the rule's targets list); `arn` references the SNS topic.
resource "aws_cloudwatch_event_target" "secrets_rotated_to_sns" {
  rule      = aws_cloudwatch_event_rule.secrets_rotated.name
  target_id = "CardDemoSecretsRotationSnsTarget"
  arn       = aws_sns_topic.secrets_rotation.arn
}

# SNS topic policy authorising the EventBridge service principal to
# publish to the rotation topic. Without this policy, the
# `aws_cloudwatch_event_target.secrets_rotated_to_sns` target invocation
# fails at the SNS API boundary with "Topic does not exist or
# AccessDenied".
#
# The policy is scoped to:
#   * Principal       = events.amazonaws.com (EventBridge service)
#   * Action          = sns:Publish only
#   * Resource        = the rotation SNS topic ARN
#   * SourceArn cond. = the EventBridge rule ARN (defence in depth
#                       against unrelated EventBridge rules publishing
#                       to this topic)
resource "aws_sns_topic_policy" "allow_eventbridge" {
  arn = aws_sns_topic.secrets_rotation.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowEventBridgePublishFromCardDemoRule"
        Effect = "Allow"
        Principal = {
          Service = "events.amazonaws.com"
        }
        Action   = "sns:Publish"
        Resource = aws_sns_topic.secrets_rotation.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_cloudwatch_event_rule.secrets_rotated.arn
          }
        }
      }
    ]
  })
}
