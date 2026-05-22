###############################################################################
# infrastructure/terraform/main.tf
#
# Terraform module root for the CardDemo AWS infrastructure.
#
# Purpose:
#   Foundational Terraform configuration shared by every other .tf file in
#   this folder. Establishes:
#     1. Terraform version + required_providers + remote backend ("s3" with
#        DynamoDB locking and SSE-KMS state encryption).
#     2. AWS provider configuration with default_tags so every resource is
#        auto-tagged with the mandatory common tag set (Project, Environment,
#        Owner, ManagedBy).
#     3. Identity / regional data sources (aws_caller_identity, aws_partition,
#        aws_region, aws_availability_zones) used by sibling .tf files to
#        construct ARNs and locals.
#     4. Bring-your-own-VPC data sources (aws_vpc, aws_subnets for private and
#        public subnet partitions, per-AZ aws_subnet, aws_route_tables) so the
#        module references operator-provisioned network instead of creating
#        a VPC.
#     5. Centralized locals — common_tags, resource_name_prefix, account_id,
#        partition — referenced by every sibling .tf file.
#     6. VPC endpoints (S3 Gateway + Secrets Manager / KMS / ECR-api /
#        ECR-dkr / CloudWatch Logs Interface endpoints) plus the shared
#        security group that gates HTTPS traffic from the VPC CIDR to the
#        Interface endpoints. Keeps PCI-DSS-relevant traffic inside the VPC
#        and avoids NAT egress for the most common AWS API calls made by
#        ECS Fargate tasks, AWS Batch jobs, and AWS Glue jobs.
#     7. An HCL comment block (at the bottom of this file) documenting the
#        backend bootstrap procedure that must be executed once per AWS
#        account / environment before the first `terraform init`.
#
# References:
#   * AAP §0.3.1 — Target Design / Refactored Structure Planning.
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS controls
#     (TLS 1.2+, KMS CMKs, Secrets Manager, CloudTrail, OpenSearch, Macie).
#   * AAP §0.7.1 — All credentials at runtime from AWS Secrets Manager; all
#     data at rest encrypted via AWS KMS CMKs; mandatory tagging.
#   * ../README.md (folder summary) — backend, provider version, common tags.
#
# Provider pinning:
#   * hashicorp/aws    ~> 5.0  (mandated by the folder summary in ../README.md).
#   * hashicorp/random ~> 3.6  (declared centrally so sibling files
#                               secrets.tf, rds.tf, etc. can use
#                               random_password / random_id without
#                               redeclaring the provider here).
#
# Security posture:
#   * No long-lived AWS credentials anywhere — the provider uses the AWS SDK
#     default credential chain (IAM role, OIDC federation via GitHub Actions,
#     EC2 / ECS task metadata, IMDSv2). See ../.github/workflows/deploy.yml
#     for the OIDC trust configured by iam.tf.
#   * Remote state is encrypted at rest with a customer-managed KMS key
#     ("alias/carddemo-tfstate") and locked through DynamoDB to prevent
#     concurrent operator collisions.
###############################################################################

# =============================================================================
# Section 1 — Terraform settings + required_providers + remote backend
# =============================================================================
# The `terraform` block sets the engine constraints (required_version),
# pins the AWS and Random providers (required_providers), and declares the
# remote state backend (backend "s3"). Note that the backend configuration
# values cannot be interpolated from variables — they must be passed at
# `terraform init` time via `-backend-config=...` flags or a separate
# *.tfbackend file. The commented placeholders below illustrate the values
# expected by the bootstrap procedure documented at the bottom of this file.
# =============================================================================

terraform {
  # Terraform 1.6+ is required for stable use of:
  #   * `terraform test` (used by the validation step in CI/CD).
  #   * Backend `-backend-config` precedence semantics.
  #   * Provider configuration aliases referenced by other .tf files.
  # The upper bound (< 2.0.0) protects against unannounced breaking changes
  # in a major Terraform release.
  required_version = ">= 1.6.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }

    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # ---------------------------------------------------------------------------
  # Remote state in S3 with DynamoDB locking + SSE-KMS encryption.
  #
  # The state bucket, key, region, KMS key, and DynamoDB lock table are
  # provisioned out-of-band by the operator (see the backend bootstrap
  # procedure at the bottom of this file) and supplied to Terraform at
  # `terraform init` time via:
  #
  #   terraform init \
  #     -backend-config="bucket=carddemo-tfstate-${ACCOUNT_ID}" \
  #     -backend-config="key=infrastructure/${env}/terraform.tfstate" \
  #     -backend-config="region=${REGION}" \
  #     -backend-config="encrypt=true" \
  #     -backend-config="kms_key_id=alias/carddemo-tfstate" \
  #     -backend-config="dynamodb_table=carddemo-tfstate-lock"
  #
  # Backend configuration cannot reference variables, locals, or data
  # sources, which is why values are passed via `-backend-config` rather
  # than declared inline here. See infrastructure/README.md for the full
  # operator runbook.
  # ---------------------------------------------------------------------------
  backend "s3" {
    # bucket         = "carddemo-tfstate-${ACCOUNT_ID}"        # set via -backend-config
    # key            = "infrastructure/${env}/terraform.tfstate" # set via -backend-config
    # region         = "us-east-1"                              # set via -backend-config
    # encrypt        = true                                     # SSE-KMS state encryption
    # kms_key_id     = "alias/carddemo-tfstate"                 # customer-managed KMS key
    # dynamodb_table = "carddemo-tfstate-lock"                  # state lock table
  }
}

# =============================================================================
# Section 2 — AWS provider configuration with default_tags
# =============================================================================
# The `default_tags` block applies the mandatory common tag set to every
# resource created with the AWS provider, including resources defined in
# sibling .tf files. Sibling files MAY merge additional resource-specific
# tags via `merge(local.common_tags, { Name = "..." })`, but they MUST NOT
# override Project / Environment / Owner / ManagedBy.
#
# The region is sourced from var.aws_region (default us-east-1; validated
# by variables.tf to be a well-formed AWS region identifier).
#
# Credential resolution follows the AWS SDK default chain — IAM role on
# the executing host, OIDC token from GitHub Actions (see iam.tf for the
# trust relationship), environment variables, or EC2 / ECS task metadata
# (IMDSv2). No static access keys are accepted anywhere in this module.
# =============================================================================

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.common_tags
  }
}

# =============================================================================
# Section 3 — Identity / regional data sources
# =============================================================================
# These data sources are consumed by sibling .tf files (iam.tf, kms.tf,
# secrets.tf, cloudtrail.tf, opensearch.tf, etc.) to:
#   * Construct ARNs that need the account ID (e.g., the KMS key policy's
#     "kms:ViaService" condition keys reference the calling account).
#   * Resolve the AWS partition (aws, aws-us-gov, aws-cn) for ARN prefixes
#     that vary across partitions.
#   * Discover the runtime region (defensive — should match var.aws_region
#     but the provider exposes the resolved value for resources that need
#     to confirm).
#   * Enumerate available AZs for resources that span multiple zones
#     (ECS, RDS, ElastiCache, MSK) when the operator did not supply an
#     explicit AZ list.
#
# These data sources are declared ONCE here and reused — sibling .tf files
# (notably iam.tf) MUST NOT redeclare `data "aws_caller_identity" "current"`
# or `data "aws_partition" "current"` because duplicate declarations cause
# Terraform configuration errors.
# =============================================================================

# Resolves the AWS account ID + IAM identity / role ARN executing Terraform.
# Members exposed: account_id, arn, user_id.
data "aws_caller_identity" "current" {}

# Resolves the AWS partition ("aws", "aws-us-gov", "aws-cn") and the
# corresponding DNS suffix / reverse DNS prefix. Members exposed: partition,
# dns_suffix, reverse_dns_prefix.
data "aws_partition" "current" {}

# Resolves the runtime region, including its description and the API
# endpoint. Members exposed: name, endpoint, description.
data "aws_region" "current" {}

# Lists Availability Zones that are in the "available" state — used by any
# resource that distributes across AZs without an explicit operator-supplied
# AZ list. Members exposed: names, zone_ids.
data "aws_availability_zones" "available" {
  state = "available"
}

# =============================================================================
# Section 4 — Bring-your-own-VPC data sources
# =============================================================================
# CardDemo follows a bring-your-own-VPC pattern (per ../README.md
# "Architecture Overview"): the operator pre-provisions the VPC, public
# subnets, private subnets, NAT gateway(s), and route tables. This module
# references those resources by ID via the data sources below and provisions
# only the workload (security groups, RDS, ElastiCache, MSK, ECS, ALB,
# VPC endpoints).
#
# Subnet-partitioning convention:
#   * private_subnet_ids — workloads not directly reachable from the
#     internet (ECS tasks, RDS, ElastiCache, MSK, Batch, Glue, Lambda).
#   * public_subnet_ids  — only the internet-facing ALB.
# =============================================================================

# The CardDemo VPC. Members exposed: id, cidr_block, default_network_acl_id,
# default_security_group_id, main_route_table_id.
data "aws_vpc" "carddemo" {
  id = var.vpc_id
}

# Resolves the operator-supplied private subnet IDs, double-filtering by
# VPC ID for safety (catches the case where private_subnet_ids accidentally
# includes a subnet from a different VPC). Members exposed: ids.
data "aws_subnets" "private" {
  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }

  filter {
    name   = "subnet-id"
    values = var.private_subnet_ids
  }
}

# Resolves the operator-supplied public subnet IDs, with the same VPC-ID
# safety filter. Members exposed: ids.
data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }

  filter {
    name   = "subnet-id"
    values = var.public_subnet_ids
  }
}

# Per-AZ private subnet reference, primarily used by the AWS Glue connection
# in glue.tf (Glue connections bind to a single subnet / AZ at create time).
# Members exposed: id, cidr_block, availability_zone, availability_zone_id.
data "aws_subnet" "private_a" {
  id = var.private_subnet_ids[0]
}

# Resolves the route tables associated with the operator-supplied private
# subnets, used by the S3 Gateway VPC endpoint to inject the S3 prefix-list
# route into every private subnet's routing table. Members exposed: ids.
data "aws_route_tables" "private" {
  vpc_id = var.vpc_id

  filter {
    name   = "association.subnet-id"
    values = var.private_subnet_ids
  }
}

# =============================================================================
# Section 5 — Centralized locals
# =============================================================================
# `local.common_tags` is the single source of truth for the mandatory tag
# set imposed by the folder summary in ../README.md. It is consumed by:
#   * `provider "aws" { default_tags { tags = local.common_tags } }`
#     above — applies the tags to every resource implicitly.
#   * Sibling .tf files that need to add a resource-specific `Name` tag,
#     which they accomplish via `merge(local.common_tags, { Name = ... })`.
#
# `local.resource_name_prefix` produces the standard "carddemo-<env>"
# prefix used as the leading segment of every resource name (e.g.,
# "carddemo-prod-rds", "carddemo-staging-ecs-cluster").
#
# `local.account_id` and `local.partition` are short, ergonomic aliases
# for the verbose `data.aws_caller_identity.current.account_id` and
# `data.aws_partition.current.partition` references that appear in many
# ARN constructions throughout the module.
# =============================================================================

locals {
  # Short alias for the executing AWS account ID. Used by resources that
  # need to embed the account ID in ARNs, KMS key policies, S3 bucket
  # policies, and IAM trust policies.
  account_id = data.aws_caller_identity.current.account_id

  # Short alias for the AWS partition ("aws" in commercial regions,
  # "aws-us-gov" in GovCloud, "aws-cn" in China). Used to construct
  # partition-aware ARNs.
  partition = data.aws_partition.current.partition

  # Mandatory common tag set per the folder summary in ../README.md.
  # Applied to every resource through the provider's `default_tags` block.
  # Sibling .tf files MAY merge additional tags (typically a per-resource
  # `Name` tag) but MUST NOT override these four keys.
  common_tags = {
    Project     = var.project_name # default "CardDemo" (variables.tf)
    Environment = var.environment  # one of dev | staging | prod
    Owner       = var.owner_tag    # default "blitzy-sandbox" (variables.tf)
    ManagedBy   = "Terraform"
  }

  # Derived resource-name prefix consumed by every sibling .tf file when
  # constructing resource names (e.g.,
  # "${local.resource_name_prefix}-rds-instance"). Centralizing the
  # naming convention here guarantees consistency across the module.
  resource_name_prefix = "carddemo-${var.environment}"
}

# =============================================================================
# Section 6 — VPC endpoints (private-subnet AWS access)
# =============================================================================
# VPC endpoints keep AWS API traffic from the private subnets on the AWS
# backbone — they avoid NAT egress, reduce data-transfer cost, and satisfy
# the PCI-DSS principle of minimizing exposure to the public internet (per
# AAP §0.6.6). Two endpoint types are used:
#
#   * Gateway endpoints (S3 in this module) — free; injected via route
#     table associations into every private subnet's routing table; the
#     S3 prefix list ID is exposed for use in security group rules and
#     bucket policies (see s3.tf).
#
#   * Interface endpoints (Secrets Manager, KMS, ECR-api, ECR-dkr,
#     CloudWatch Logs) — charged per ENI-hour; expose service hostnames
#     via private DNS so existing AWS SDK clients require no code
#     changes; protected by a shared security group that allows HTTPS
#     (443) from the VPC CIDR only.
#
# The endpoint set is selected to cover the AWS services touched by every
# Spring Boot task on cold-start (ECR for image pull, Secrets Manager for
# DB / JWT / Kafka credentials, KMS for envelope-encryption decrypt
# operations, CloudWatch Logs for structured log delivery) and by every
# batch step (S3 reads / writes via the Gateway endpoint).
# =============================================================================

# -----------------------------------------------------------------------------
# Shared security group for all Interface endpoints.
#
# Allows HTTPS (443) ingress from the VPC CIDR (so any task or function
# running in a subnet inside the VPC can reach the endpoint) and unrestricted
# egress (the endpoint itself terminates the connection; egress rules apply
# to the ENI's reply traffic).
#
# Note: tagging is applied explicitly via merge() — provider default_tags
# already cover the four mandatory keys but a per-resource Name tag is
# added for console clarity.
# -----------------------------------------------------------------------------
resource "aws_security_group" "vpc_endpoints" {
  name        = "${local.resource_name_prefix}-vpc-endpoints-sg"
  description = "Interface VPC endpoints - HTTPS (443) from VPC CIDR only"
  vpc_id      = data.aws_vpc.carddemo.id

  ingress {
    description = "HTTPS from within the CardDemo VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.carddemo.cidr_block]
  }

  egress {
    description = "All egress - endpoint terminates the connection at the ENI"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-vpc-endpoints-sg"
  })
}

# -----------------------------------------------------------------------------
# S3 Gateway VPC endpoint.
#
# Gateway endpoints are free and inject the S3 prefix list ID into the
# associated route tables. Every private subnet's route table is associated
# (via `data.aws_route_tables.private`) so any ECS task, Batch job, Glue job,
# or Lambda function in a private subnet reaches S3 over the AWS backbone
# instead of via NAT.
#
# The attached policy permits all S3 actions; per-bucket policies (configured
# in s3.tf) provide the actual authorisation surface. PCI-DSS bucket policies
# in s3.tf already enforce SSL-only access and deny non-KMS encryption.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "s3_gateway" {
  vpc_id            = data.aws_vpc.carddemo.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = data.aws_route_tables.private.ids

  # Permissive endpoint policy — actual bucket-level enforcement is the
  # responsibility of bucket policies provisioned by s3.tf (deny non-TLS,
  # deny non-KMS encryption, deny non-account principals, etc.).
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = "*"
        Action    = ["s3:*"]
        Resource  = ["*"]
      }
    ]
  })

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-s3-gw-endpoint"
  })
}

# -----------------------------------------------------------------------------
# Secrets Manager Interface endpoint.
#
# Resolves secretsmanager.<region>.amazonaws.com to private ENIs in each
# private subnet. Without this endpoint, every Secrets Manager API call
# (e.g., the @RefreshScope reload of the RDS master credential per AAP
# §0.6.4) would traverse the NAT gateway and the public internet.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "secretsmanager" {
  vpc_id              = data.aws_vpc.carddemo.id
  service_name        = "com.amazonaws.${var.aws_region}.secretsmanager"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.private_subnet_ids
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-secretsmanager-endpoint"
  })
}

# -----------------------------------------------------------------------------
# KMS Interface endpoint.
#
# Per AAP §0.6.6 and §0.7.1, all CardDemo data at rest is encrypted with
# customer-managed KMS keys (CMKs). The KMS Decrypt / GenerateDataKey calls
# emitted by RDS, S3, ElastiCache, MSK, OpenSearch, and the Spring Boot
# application itself (envelope encryption of sensitive payloads) should
# never leave the VPC. The KMS Interface endpoint enforces this by exposing
# kms.<region>.amazonaws.com privately.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "kms" {
  vpc_id              = data.aws_vpc.carddemo.id
  service_name        = "com.amazonaws.${var.aws_region}.kms"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.private_subnet_ids
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-kms-endpoint"
  })
}

# -----------------------------------------------------------------------------
# ECR API Interface endpoint.
#
# ECR client APIs (GetAuthorizationToken, DescribeRepositories, BatchGetImage,
# etc.) resolve here. Without this endpoint, ECS Fargate tasks pulling the
# CardDemo container image from ECR on cold-start would require NAT egress.
# Pairs with the ECR DKR endpoint below — both are required for a complete
# private image pull.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "ecr_api" {
  vpc_id              = data.aws_vpc.carddemo.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.api"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.private_subnet_ids
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-ecr-api-endpoint"
  })
}

# -----------------------------------------------------------------------------
# ECR DKR Interface endpoint.
#
# Docker Registry HTTP API traffic (the actual image layer pulls) resolves
# here. The Spring Boot image (built by ../Dockerfile and pushed by
# .github/workflows/docker-build.yml) is pulled over this endpoint by every
# ECS task and AWS Batch job on cold-start.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "ecr_dkr" {
  vpc_id              = data.aws_vpc.carddemo.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.dkr"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.private_subnet_ids
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-ecr-dkr-endpoint"
  })
}

# -----------------------------------------------------------------------------
# CloudWatch Logs Interface endpoint.
#
# Spring Boot's structured JSON logging (via logstash-logback-encoder per
# AAP §0.7.2) flows through this endpoint to the CloudWatch Logs ingestion
# service. AWS Batch jobs, Step Functions executions, and Glue jobs also
# emit logs here, so the endpoint is a hot path on every task instance.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "logs" {
  vpc_id              = data.aws_vpc.carddemo.id
  service_name        = "com.amazonaws.${var.aws_region}.logs"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.private_subnet_ids
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-logs-endpoint"
  })
}

###############################################################################
# Backend bootstrap procedure (one-time setup, before the first
# `terraform init` in this module).
#
# These steps are run out-of-band by the platform engineer (or by a
# dedicated bootstrap workflow in a separate AWS account). They cannot be
# managed by this Terraform module itself because they create the very
# state-storage resources Terraform writes its state to — a chicken-and-egg
# situation that is conventionally resolved by an imperative one-shot.
#
# Variables expected by the procedure:
#   * ${ACCOUNT_ID}    — 12-digit AWS account number.
#   * ${REGION}        — AWS region (e.g., us-east-1).
#   * ${env}           — environment short name (dev | staging | prod).
#
# ---------------------------------------------------------------------------
# Step 1 — Create the SSE-KMS-encrypted state bucket
# ---------------------------------------------------------------------------
#   aws s3api create-bucket \
#     --bucket carddemo-tfstate-${ACCOUNT_ID} \
#     --region ${REGION} \
#     --create-bucket-configuration LocationConstraint=${REGION}
#
#   aws s3api put-bucket-versioning \
#     --bucket carddemo-tfstate-${ACCOUNT_ID} \
#     --versioning-configuration Status=Enabled
#
#   aws s3api put-bucket-encryption \
#     --bucket carddemo-tfstate-${ACCOUNT_ID} \
#     --server-side-encryption-configuration '{
#       "Rules": [
#         {
#           "ApplyServerSideEncryptionByDefault": {
#             "SSEAlgorithm": "aws:kms",
#             "KMSMasterKeyID": "alias/carddemo-tfstate"
#           },
#           "BucketKeyEnabled": true
#         }
#       ]
#     }'
#
#   aws s3api put-public-access-block \
#     --bucket carddemo-tfstate-${ACCOUNT_ID} \
#     --public-access-block-configuration \
#         BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
#
# ---------------------------------------------------------------------------
# Step 2 — Create the DynamoDB lock table
# ---------------------------------------------------------------------------
#   aws dynamodb create-table \
#     --table-name carddemo-tfstate-lock \
#     --attribute-definitions AttributeName=LockID,AttributeType=S \
#     --key-schema AttributeName=LockID,KeyType=HASH \
#     --billing-mode PAY_PER_REQUEST \
#     --region ${REGION}
#
# ---------------------------------------------------------------------------
# Step 3 — Initialize Terraform with the backend configuration
# ---------------------------------------------------------------------------
#   terraform init \
#     -backend-config="bucket=carddemo-tfstate-${ACCOUNT_ID}" \
#     -backend-config="key=infrastructure/${env}/terraform.tfstate" \
#     -backend-config="region=${REGION}" \
#     -backend-config="encrypt=true" \
#     -backend-config="kms_key_id=alias/carddemo-tfstate" \
#     -backend-config="dynamodb_table=carddemo-tfstate-lock"
#
# ---------------------------------------------------------------------------
# Step 4 — Plan / apply with environment-specific tfvars
# ---------------------------------------------------------------------------
#   terraform plan  -var-file="environments/${env}.tfvars" -out=plan.out
#   terraform apply plan.out
#
# See infrastructure/README.md for the full operator runbook, including:
#   * Per-environment tfvars file structure.
#   * CI/CD pipeline integration (../.github/workflows/deploy.yml).
#   * Destroy / rollback procedures.
#   * Drift detection and remediation.
###############################################################################
