###############################################################################
# infrastructure/terraform/secrets.tf
#
# AWS Secrets Manager + DB Rotation Lambda + JWT Signing Key + Outputs.
#
# Purpose:
#   Centralizes Secrets Manager resources for CardDemo that are NOT
#   tightly coupled to a single data-store .tf file. The two data-store
#   secrets that ARE tightly coupled remain co-located:
#     * aws_secretsmanager_secret.rds_master     (rds.tf)
#     * aws_secretsmanager_secret.redis_auth     (elasticache.tf)
#
#   This file declares:
#     1. aws_secretsmanager_secret.jwt_signing_key
#          KMS-encrypted secret carrying the HS256 / RS256 signing key
#          material consumed by JwtTokenProvider at runtime (AAP §0.7.1
#          "All credentials fetched at runtime from AWS Secrets Manager").
#     2. aws_secretsmanager_secret_version.jwt_signing_key_value
#          Initial value (a random 64-byte token) — rotation Lambdas can
#          replace this value without Terraform re-applies via the
#          lifecycle.ignore_changes guard.
#     3. aws_secretsmanager_secret.msk_sasl_credentials
#          KMS-encrypted secret carrying the SASL/SCRAM username +
#          password pair used by Spring Kafka producers / consumers when
#          the MSK cluster is configured for SASL/SCRAM auth (the IAM
#          path used in msk.tf does NOT need this secret, but SCRAM is
#          required for many third-party Kafka tooling integrations and
#          is provisioned here for completeness).
#     4. aws_secretsmanager_secret_version.msk_sasl_credentials_value
#          Initial JSON value (username + random 32-byte password).
#     5. aws_iam_role.rds_rotation_lambda
#          Execution role for the RDS rotation Lambda. Inline policies
#          grant ec2 ENI management (Lambda VPC), Secrets Manager
#          publish-version on the RDS master secret, and KMS decrypt of
#          the secrets CMK.
#     6. aws_security_group.rds_rotation_lambda
#          Network attachment for the rotation Lambda — egresses HTTPS
#          to Secrets Manager VPC endpoint + PostgreSQL TCP 5432 to RDS.
#     7. aws_lambda_function.rds_rotation
#          The actual rotation Lambda. Sources its handler ZIP from a
#          fixed S3 location (`var.lambda_artifacts_bucket` /
#          `rds-rotation-lambda.zip`) that the operator pre-uploads as
#          part of the deployment pipeline. The runtime image / handler
#          / environment variables follow the standard AWS RDS
#          single-user rotation Lambda contract so the function can be
#          replaced by the SAR-managed implementation at any time.
#     8. aws_lambda_permission.allow_secretsmanager_invoke_rds_rotation
#          Permission boundary letting Secrets Manager invoke the
#          rotation function on each rotation event.
#     9. aws_secretsmanager_secret_rotation.rds_master
#          Wires the rotation Lambda to aws_secretsmanager_secret.rds_master
#          with the AAP §0.6.4 rotation cadence (var.rds_rotation_days).
#    10. Output ARNs consumed by Spring Cloud AWS / @RefreshScope wiring.
#
# Why this file exists (vs. embedding in rds.tf):
#   * F-CP6-TF-Secrets-01: The CP6 review explicitly required a dedicated
#     secrets.tf module for the rotation Lambda + JWT/MSK secrets.
#   * Centralizing rotation infrastructure makes the @RefreshScope <-> SNS
#     wiring (AAP §0.6.4) reviewable in a single file.
#   * Future secrets (third-party API keys, ECS Exec audit hashes) land
#     here without growing the per-data-store .tf files.
#
# Cross-references:
#   * kms.tf            — aws_kms_key.secrets_kms (service-specific CMK from
#                         the KMS separation in §0.6 of this fix)
#   * rds.tf            — aws_secretsmanager_secret.rds_master is rotated
#                         by the Lambda declared here
#   * elasticache.tf    — aws_secretsmanager_secret.redis_auth (rotation
#                         path is operator-driven via terraform-apply for
#                         AUTH; future-extension placeholder is below)
#   * iam.tf            — secrets_manager_read + kms_use IAM policy
#                         documents are referenced by Spring application
#                         roles to consume the secrets created here
#   * main.tf           — VPC endpoints (Secrets Manager Interface
#                         endpoint) used by the rotation Lambda for
#                         keep-traffic-in-VPC posture
#
# References:
#   * AAP §0.6.4 — AWS Secrets Manager dynamic rotation without Spring
#                  Boot restart (Spring Cloud AWS + @RefreshScope)
#   * AAP §0.6.6 — Cross-cutting: PCI-DSS controls (KMS, Secrets Manager)
#   * AAP §0.7.1 — All credentials fetched at runtime from AWS Secrets
#                  Manager — never hardcoded or in application.yml
#   * AAP §0.7.2 — PCI-DSS compliance — encryption at rest, automatic
#                  credential rotation
#   * F-CP6-TF-Secrets-01 — CP6 critical review finding: missing secrets.tf
###############################################################################

# =============================================================================
# Section 1 — JWT signing key secret
# =============================================================================
# Symmetric (HS256) JWT signing key for the Spring Security 6 + JWT layer
# defined in src/main/java/com/awsm2/carddemo/security/JwtTokenProvider.java.
# The secret value is generated at Terraform apply time (random_password
# 64 bytes) and may be replaced by an operator-driven manual rotation or by
# a future custom Lambda (out of CP6 scope).
#
# The secret_string is a JSON envelope:
#   {
#     "signingKey": "<base64 key>",
#     "algorithm":  "HS256",
#     "issuedAt":   "<ISO-8601 timestamp>"
#   }
# Spring Cloud AWS auto-loads this secret into
# `carddemo.security.jwt.signingKey` via the property
# `spring.config.import=aws-secretsmanager:carddemo-<env>-jwt-signing-key`.
# =============================================================================

resource "random_password" "jwt_signing_key" {
  length           = 64
  special          = false
  override_special = ""
}

resource "aws_secretsmanager_secret" "jwt_signing_key" {
  name        = "carddemo-${var.environment}-jwt-signing-key"
  description = "JWT HS256 signing key for CardDemo Spring Security 6 (AAP §0.7.1 — Secrets Manager only; never hardcoded). Rotation via operator-driven Terraform re-apply or future custom Lambda."
  kms_key_id  = aws_kms_key.secrets_kms.arn

  # 30-day pending-deletion window protects against accidental deletion.
  # In dev environments the operator may override to 7 days for faster
  # iteration; here we use the conservative default.
  recovery_window_in_days = 30

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-jwt-signing-key"
    Purpose = "JWT HS256 signing key for Spring Security 6"
  })
}

resource "aws_secretsmanager_secret_version" "jwt_signing_key_value" {
  secret_id = aws_secretsmanager_secret.jwt_signing_key.id

  # JSON envelope read by JwtTokenProvider.
  secret_string = jsonencode({
    signingKey = random_password.jwt_signing_key.result
    algorithm  = "HS256"
    issuedAt   = timestamp()
  })

  lifecycle {
    # `timestamp()` returns a new value on every apply, causing spurious
    # drift; ignore_changes pins the initial value while still allowing
    # explicit operator rotation (taint + apply).
    ignore_changes = [secret_string]
  }
}

# =============================================================================
# Section 2 — MSK SASL/SCRAM credentials secret
# =============================================================================
# Username + password pair stored in a JSON envelope. Consumed by Spring
# Kafka producers / consumers ONLY when the MSK cluster is configured for
# SASL/SCRAM auth. The IAM-auth path used by msk.tf (the default) does
# NOT consume this secret, but the secret is provisioned for completeness
# and for third-party tooling integration.
#
# Naming convention required by AWS MSK:
#   AmazonMSK_<arbitrary suffix>
# (MSK only associates secrets that begin with the `AmazonMSK_` prefix.)
# =============================================================================

resource "random_password" "msk_sasl_password" {
  length           = 32
  special          = false
  override_special = ""
}

resource "aws_secretsmanager_secret" "msk_sasl_credentials" {
  # AWS MSK requires the secret name to begin with "AmazonMSK_".
  name        = "AmazonMSK_carddemo-${var.environment}"
  description = "MSK SASL/SCRAM credentials for CardDemo Kafka clients (AAP §0.7.1 — Secrets Manager only; §0.6.5 — MSK ordering guarantees)"
  kms_key_id  = aws_kms_key.secrets_kms.arn

  recovery_window_in_days = 30

  tags = merge(local.common_tags, {
    Name    = "AmazonMSK_carddemo-${var.environment}"
    Purpose = "MSK SASL/SCRAM credentials for Spring Kafka clients"
  })
}

resource "aws_secretsmanager_secret_version" "msk_sasl_credentials_value" {
  secret_id = aws_secretsmanager_secret.msk_sasl_credentials.id

  secret_string = jsonencode({
    username = "carddemo-${var.environment}-msk-user"
    password = random_password.msk_sasl_password.result
  })

  lifecycle {
    # Allow operator-driven rotation without drift.
    ignore_changes = [secret_string]
  }
}

# =============================================================================
# Section 3 — RDS rotation Lambda execution role
# =============================================================================
# The rotation Lambda is invoked by Secrets Manager on the cadence
# configured by aws_secretsmanager_secret_rotation.rds_master (default
# every var.rds_rotation_days days, per AAP §0.6.4). The role is
# distinct from the application's ECS task role and is scoped to the
# minimum permissions required for the AWS RDS single-user rotation
# pattern:
#   * AWSLambdaVPCAccessExecutionRole (managed) — ENI lifecycle for VPC
#     attachment.
#   * Inline `rds-rotation-secrets-publish` — PutSecretValue on the
#     specific RDS master secret + GetSecretValue/DescribeSecret on the
#     same secret (the Lambda implements the four rotation stages:
#     createSecret -> setSecret -> testSecret -> finishSecret).
#   * Inline `rds-rotation-kms-decrypt` — kms:Decrypt + kms:GenerateDataKey
#     on the secrets CMK so the Lambda can read and re-write the secret
#     value.
# =============================================================================

data "aws_iam_policy_document" "rds_rotation_lambda_assume" {
  statement {
    sid     = "LambdaAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_rotation_lambda" {
  name               = "carddemo-${var.environment}-rds-rotation-lambda"
  description        = "Execution role for the RDS Secrets Manager rotation Lambda (AAP §0.6.4)"
  assume_role_policy = data.aws_iam_policy_document.rds_rotation_lambda_assume.json

  max_session_duration = 3600

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-rotation-lambda"
    Purpose = "Secrets Manager RDS rotation Lambda execution role"
  })
}

# AWS-managed AWSLambdaVPCAccessExecutionRole — required because the
# rotation Lambda runs inside the VPC to reach the RDS endpoint (which
# is private-subnet-only) and the Secrets Manager VPC Interface endpoint.
resource "aws_iam_role_policy_attachment" "rds_rotation_lambda_vpc_access" {
  role       = aws_iam_role.rds_rotation_lambda.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# Inline policy: GetSecretValue / PutSecretValue / DescribeSecret /
# UpdateSecretVersionStage on ONLY the RDS master secret.
data "aws_iam_policy_document" "rds_rotation_lambda_secrets" {
  statement {
    sid    = "ManageRdsMasterSecret"
    effect = "Allow"
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:PutSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:UpdateSecretVersionStage"
    ]
    resources = [
      aws_secretsmanager_secret.rds_master.arn
    ]
  }

  # GetRandomPassword is used by the AWS-canonical rotation Lambda to
  # generate the new password value during the createSecret stage.
  statement {
    sid       = "GenerateRotationPassword"
    effect    = "Allow"
    actions   = ["secretsmanager:GetRandomPassword"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "rds_rotation_lambda_secrets" {
  name   = "rds-rotation-secrets-publish"
  role   = aws_iam_role.rds_rotation_lambda.id
  policy = data.aws_iam_policy_document.rds_rotation_lambda_secrets.json
}

# Inline policy: kms:Decrypt + kms:GenerateDataKey on the secrets CMK
# (the secrets the Lambda reads/writes are KMS-encrypted with this key).
data "aws_iam_policy_document" "rds_rotation_lambda_kms" {
  statement {
    sid    = "UseSecretsCmk"
    effect = "Allow"
    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
      "kms:DescribeKey"
    ]
    resources = [aws_kms_key.secrets_kms.arn]
  }
}

resource "aws_iam_role_policy" "rds_rotation_lambda_kms" {
  name   = "rds-rotation-kms-decrypt"
  role   = aws_iam_role.rds_rotation_lambda.id
  policy = data.aws_iam_policy_document.rds_rotation_lambda_kms.json
}

# =============================================================================
# Section 4 — RDS rotation Lambda security group
# =============================================================================
# Attaches to the rotation Lambda's VPC ENIs. Egress rules:
#   * TCP 443 to the Secrets Manager VPC Interface endpoint (within the
#     VPC CIDR — handled by the endpoint's own security group).
#   * TCP 5432 to the RDS instance security group (the RDS SG must
#     ingress this SG; rds.tf may need to reference
#     aws_security_group.rds_rotation_lambda.id explicitly).
# Ingress: NONE — the Lambda is invoked by Secrets Manager via the
# service control plane, not over a network ingress path.
# =============================================================================

resource "aws_security_group" "rds_rotation_lambda" {
  name        = "carddemo-${var.environment}-rds-rotation-lambda"
  description = "Egress-only SG for the RDS rotation Lambda (HTTPS to Secrets Manager + PostgreSQL 5432 to RDS)"
  vpc_id      = var.vpc_id

  egress {
    description = "HTTPS to Secrets Manager VPC endpoint + AWS APIs"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.carddemo.cidr_block]
  }

  egress {
    description = "PostgreSQL 5432 to RDS instance"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.carddemo.cidr_block]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-rotation-lambda-sg"
    Purpose = "Egress security group for RDS rotation Lambda"
  })
}

# =============================================================================
# Section 5 — RDS rotation Lambda function
# =============================================================================
# The handler ZIP is pre-uploaded by the deployment pipeline to the
# CardDemo logs bucket under the prefix `lambda-artifacts/`. The handler
# implements the AWS-canonical four-stage rotation contract:
#   createSecret -> setSecret -> testSecret -> finishSecret
# A reference implementation is published by AWS Labs in the GitHub
# repository `aws-samples/aws-secrets-manager-rotation-lambdas` (path
# `SecretsManagerRDSPostgreSQLRotationSingleUser/`). Production deploys
# either build that handler from source or replace it with the SAR-
# managed function from the AWS Serverless Application Repository.
#
# The Lambda is provisioned with:
#   * runtime  = "python3.11" (AWS-recommended for the SAR-managed
#                rotation Lambdas in 2024+)
#   * timeout  = 30s
#   * memory   = 256 MiB (sufficient for boto3 + psycopg2)
#   * VPC      = private subnets (Multi-AZ via subnets in 2+ AZs)
#   * security group = aws_security_group.rds_rotation_lambda
#   * tracing  = Active (AWS X-Ray)
#   * env vars = SECRETS_MANAGER_ENDPOINT (VPC endpoint URL)
#
# Concurrency:
#   * reserved_concurrent_executions = 5 — bounds the blast radius of an
#     accidental rotation loop and keeps the Lambda from contending with
#     application traffic for ENI / SDK budget.
# =============================================================================

resource "aws_lambda_function" "rds_rotation" {
  function_name = "carddemo-${var.environment}-rds-rotation"
  description   = "Secrets Manager single-user rotation Lambda for the RDS master secret (AAP §0.6.4)"
  role          = aws_iam_role.rds_rotation_lambda.arn

  # The handler ZIP must be uploaded to the CardDemo logs bucket prior
  # to the first terraform apply. See infrastructure/terraform/README.md
  # for the bootstrap procedure.
  s3_bucket = aws_s3_bucket.logs.bucket
  s3_key    = "lambda-artifacts/rds-rotation-lambda.zip"

  handler = "lambda_function.lambda_handler"
  runtime = "python3.11"
  timeout = 30

  memory_size                    = 256
  reserved_concurrent_executions = 5

  # Active tracing routes Lambda invocation telemetry to AWS X-Ray for
  # end-to-end visibility of the rotation pipeline (Secrets Manager ->
  # Lambda -> RDS).
  tracing_config {
    mode = "Active"
  }

  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.rds_rotation_lambda.id]
  }

  environment {
    variables = {
      # AWS-canonical environment variable for the rotation Lambda.
      SECRETS_MANAGER_ENDPOINT = "https://secretsmanager.${var.aws_region}.amazonaws.com"
    }
  }

  # KMS-encrypt the Lambda environment variables at rest with the
  # secrets CMK so plaintext is never visible in CloudTrail.
  kms_key_arn = aws_kms_key.secrets_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-rotation"
    Purpose = "RDS Secrets Manager rotation Lambda"
  })

  # The Lambda depends on its IAM permissions being attached first.
  depends_on = [
    aws_iam_role_policy.rds_rotation_lambda_secrets,
    aws_iam_role_policy.rds_rotation_lambda_kms,
    aws_iam_role_policy_attachment.rds_rotation_lambda_vpc_access
  ]

  lifecycle {
    # The ZIP is updated out-of-band by the deployment pipeline; ignore
    # source_code_hash drift in Terraform so the pipeline's deploy is
    # the source of truth.
    ignore_changes = [s3_key, source_code_hash]
  }
}

# Permission boundary letting Secrets Manager invoke the rotation
# function. Without this resource, Secrets Manager's call to
# `lambda:InvokeFunction` is denied at API boundary.
resource "aws_lambda_permission" "allow_secretsmanager_invoke_rds_rotation" {
  statement_id  = "AllowSecretsManagerInvokeRdsRotation"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.rds_rotation.function_name
  principal     = "secretsmanager.amazonaws.com"

  # Source ARN gates the permission to the specific RDS master secret
  # — Secrets Manager cannot use this Lambda to rotate any other secret.
  source_arn = aws_secretsmanager_secret.rds_master.arn
}

# =============================================================================
# Section 6 — Rotation schedule wiring
# =============================================================================
# Attaches the rotation Lambda to the RDS master secret and configures
# the rotation cadence per AAP §0.6.4. Spring Boot consumes the rotated
# credential via Spring Cloud AWS + @RefreshScope on the HikariCP
# DataSource — see SecretsManagerConfig.java for the listener wiring.
# =============================================================================

resource "aws_secretsmanager_secret_rotation" "rds_master" {
  secret_id           = aws_secretsmanager_secret.rds_master.id
  rotation_lambda_arn = aws_lambda_function.rds_rotation.arn

  rotation_rules {
    automatically_after_days = var.rds_rotation_days
  }

  # The rotation schedule depends on the Lambda permission being
  # attached first; otherwise the first rotation attempt fails.
  depends_on = [
    aws_lambda_permission.allow_secretsmanager_invoke_rds_rotation
  ]
}

# =============================================================================
# Section 7 — Outputs (consumed by application code via @Value / SCAS)
# =============================================================================
# Spring Cloud AWS auto-loads secret values via the
# `spring.config.import=aws-secretsmanager:<name>` property, so the
# application typically references the secret BY NAME rather than ARN.
# The ARNs are exposed as outputs primarily for cross-account / cross-
# module Terraform references and for CI/CD pipelines that need to
# pass the ARN to ECS task definitions' `secrets[].valueFrom`.
# =============================================================================

output "jwt_signing_key_secret_arn" {
  description = "ARN of the JWT HS256 signing key secret (consumed by ECS task definitions via valueFrom)"
  value       = aws_secretsmanager_secret.jwt_signing_key.arn
}

output "jwt_signing_key_secret_name" {
  description = "Name of the JWT HS256 signing key secret (consumed by Spring Cloud AWS via spring.config.import)"
  value       = aws_secretsmanager_secret.jwt_signing_key.name
}

output "msk_sasl_credentials_secret_arn" {
  description = "ARN of the MSK SASL/SCRAM credentials secret (associated to the MSK cluster via aws_msk_scram_secret_association if SCRAM auth is enabled)"
  value       = aws_secretsmanager_secret.msk_sasl_credentials.arn
}

output "msk_sasl_credentials_secret_name" {
  description = "Name of the MSK SASL/SCRAM credentials secret"
  value       = aws_secretsmanager_secret.msk_sasl_credentials.name
}

output "rds_rotation_lambda_arn" {
  description = "ARN of the RDS Secrets Manager rotation Lambda"
  value       = aws_lambda_function.rds_rotation.arn
}

output "rds_rotation_lambda_role_arn" {
  description = "ARN of the IAM role used by the RDS rotation Lambda"
  value       = aws_iam_role.rds_rotation_lambda.arn
}
