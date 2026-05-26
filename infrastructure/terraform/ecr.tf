###############################################################################
# infrastructure/terraform/ecr.tf
#
# Amazon ECR (Elastic Container Registry) — single private repository
# hosting the CardDemo Spring Boot Docker image and its lifecycle policy.
#
# Replaces JCL job stream: samples/jcl/CICCMP.jcl (sample mainframe build
# wrapper). The mainframe "compile + bind + NEWCOPY" workflow is replaced
# operationally by:
#     1. ../../Dockerfile             — builds the Spring Boot image.
#     2. .github/workflows/build.yml  — runs `mvn clean install`.
#     3. .github/workflows/docker-build.yml — `docker push` to this ECR repo.
#     4. .github/workflows/deploy.yml — updates the ECS task definition to
#        roll out the new immutable image tag (replaces CEMT SET PROG ...
#        NEWCOPY).
#
# Purpose:
#   * Provision a single private ECR repository named "carddemo-<env>" that
#     stores the Spring Boot Docker image consumed by:
#       - aws_ecs_task_definition.carddemo        (ecs.tf — long-running
#                                                  REST service container)
#       - aws_batch_job_definition.*              (batch.tf — six batch
#                                                  job containers for the
#                                                  EOD pipeline)
#       - .github/workflows/docker-build.yml      (CI push target — reads
#                                                  the `ecr_repository_url`
#                                                  output from outputs.tf)
#       - .github/workflows/deploy.yml            (CD consumer — references
#                                                  the immutable tag from
#                                                  docker-build.yml)
#   * Enforce three PCI-DSS-aligned guarantees on the repository:
#       - IMMUTABLE tag mutability so that a pushed `:<git_sha>` tag can
#         never be silently overwritten (audit trail per AAP §0.6.6).
#       - scan_on_push = true so every image is scanned for CVEs by ECR's
#         Basic Scanning service immediately on push (per the folder
#         summary's "image scanning on push" requirement).
#       - KMS CMK encryption for image layers at rest using the shared
#         `aws_kms_key.carddemo` declared in kms.tf (per AAP §0.7.1
#         "Encrypt all RDS data at rest using AWS KMS customer-managed
#         keys (CMKs)" — applied uniformly to every CardDemo data store).
#   * Apply a three-rule lifecycle policy that:
#       - Retains the last var.ecr_keep_n_images (default 30) prod/v-tagged
#         images (i.e., release images and semver tags `v1.2.3`, `v2.0.0`,
#         ...).
#       - Expires untagged images after var.ecr_untagged_days (default 7)
#         days — catches CI builds that pushed layers but failed before
#         applying a tag.
#       - Retains only the last 10 dev/feature/pr-tagged images so
#         short-lived branch and pull-request images do not accumulate
#         indefinitely.
#
# Consumers (downstream sibling .tf files and outputs):
#   * outputs.tf       — exports `ecr_repository_url`,
#                        `ecr_repository_arn`, `ecr_repository_name`,
#                        consumed by docker-build.yml and deploy.yml.
#   * ecs.tf           — ECS task execution role grants
#                        `ecr:GetDownloadUrlForLayer`,
#                        `ecr:BatchGetImage`, and
#                        `ecr:GetAuthorizationToken` on this repo.
#   * batch.tf         — Batch execution role grants the same ECR pull
#                        permissions on this repo (six job definitions
#                        share the same image).
#   * iam.tf           — GitHub Actions OIDC role grants
#                        `ecr:GetAuthorizationToken`,
#                        `ecr:BatchCheckLayerAvailability`,
#                        `ecr:InitiateLayerUpload`,
#                        `ecr:UploadLayerPart`,
#                        `ecr:CompleteLayerUpload`, and
#                        `ecr:PutImage` for `docker-build.yml`.
#   * cloudwatch.tf    — optional repository-level CloudWatch alarms on
#                        push frequency / scan-finding count.
#   * Dockerfile       — multi-stage build whose final image is pushed
#                        here by docker-build.yml.
#
# Operational guarantees:
#   * Immutable tag deployments — once `:<git_sha>` is pushed, the bits
#     associated with that tag cannot change. ECS task definitions and
#     Batch job definitions that reference `:<git_sha>` therefore have
#     byte-for-byte reproducible behaviour.
#   * Repository name pattern: `carddemo-<env>` (e.g., `carddemo-prod`).
#     The `:tag` suffix is the image-specific version. ECS / Batch always
#     reference `${ecr_repository_url}:<git_sha>` rather than `:latest`.
#   * Force-delete is OFF by default (var.ecr_force_delete = false) so
#     `terraform destroy` cannot destroy compliance-relevant image
#     history in prod. Flip to true only in throw-away dev sandboxes.
#
# References:
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS
#     (encryption at rest with KMS CMKs, audit-friendly tag immutability,
#      CVE scanning).
#   * AAP §0.7.1 — Refactoring-Specific Rules ("Encrypt all RDS data at
#     rest using AWS KMS customer-managed keys (CMKs)" — extended here to
#     ECR image layers).
#   * AAP §0.7.2 — Build and runtime instructions ("For AWS deployment:
#     docker build, push to ECR, deploy via ECS Fargate task definition
#     update").
#   * Folder summary in ../README.md — "Amazon ECR repository for the
#     Spring Boot Docker image, lifecycle policy, image scanning on push".
###############################################################################

# =============================================================================
# Section 1 — Primary ECR repository for the CardDemo Spring Boot image
# =============================================================================
# This is the sole ECR repository created by this Terraform module. Both the
# long-running ECS Fargate service and all six AWS Batch job definitions
# reference the same image (the Spring Boot uber-jar selects its entry
# point at runtime via Spring profiles + command-line args), so a single
# repository is sufficient.
#
# Key configuration:
#   * name                 — "carddemo-<env>" so each environment (dev,
#                            staging, prod) has an isolated repository.
#                            Image tags are the only thing that change
#                            between deploys; the repository name is
#                            environment-scoped.
#   * image_tag_mutability — "IMMUTABLE" prevents a `:<git_sha>` tag from
#                            being silently re-pushed with new bits. A
#                            second push of the same tag fails with
#                            ImageAlreadyExistsException. This is the
#                            cornerstone of immutable deployments and the
#                            audit trail required by AAP §0.6.6.
#   * scan_on_push         — true so every pushed image is scanned for
#                            CVEs by ECR Basic Scanning immediately on
#                            push. Scan findings appear under the AWS
#                            console "Image scan findings" tab and can be
#                            queried via `aws ecr describe-image-scan-
#                            findings`.
#   * encryption_type      — "KMS" (rather than the default "AES256"
#                            AWS-managed encryption) so image layers are
#                            envelope-encrypted with the CardDemo
#                            customer-managed CMK (aws_kms_key.carddemo).
#                            This satisfies AAP §0.7.1's PCI-DSS-aligned
#                            "all data at rest encrypted via AWS KMS
#                            customer-managed keys" requirement.
#   * force_delete         — sourced from var.ecr_force_delete
#                            (default false). When true, `terraform
#                            destroy` deletes the repository even if it
#                            contains images. MUST remain false in prod
#                            to prevent accidental loss of compliance-
#                            relevant image history.
#
# Tag set:
#   The four mandatory tags (Project, Environment, Owner, ManagedBy) are
#   applied automatically by the provider `default_tags` block declared in
#   main.tf. The two additional tags merged in here (`Name`, `Purpose`)
#   provide human-readable identifiers in the AWS Console and in
#   `terraform state list` output.
# =============================================================================

resource "aws_ecr_repository" "carddemo" {
  # Repository name. `local.resource_name_prefix` is "carddemo-<env>" and
  # is defined in main.tf. We intentionally append no resource-type
  # suffix here because the ECR namespace is already scoped to ECR (the
  # repository ARN includes "repository/<name>"), and AWS ECR repository
  # names are constrained to lowercase + digits + hyphens with a max
  # length of 256 — short names keep the image URI readable.
  name = local.resource_name_prefix

  # IMMUTABLE — a pushed tag CANNOT be overwritten. Once a CI build
  # publishes carddemo-prod:abc1234 the bits behind that tag are frozen
  # forever (until the lifecycle policy expires the image). This is the
  # foundation of immutable deployments and supports the audit trail
  # mandated by AAP §0.6.6 ("Immutable, tamper-evident audit log").
  image_tag_mutability = "IMMUTABLE"

  # Run an ECR Basic Scan on every push. Findings are reported in the
  # AWS Console under the image's "Vulnerabilities" tab and through the
  # ecr:DescribeImageScanFindings API. The folder summary requires
  # "image scanning on push"; the AAP §0.6.6 audit/observability section
  # mandates continuous vulnerability monitoring of all CardDemo
  # artefacts.
  image_scanning_configuration {
    scan_on_push = true
  }

  # KMS CMK encryption-at-rest for image layers. The CMK is shared across
  # every CardDemo data store (see kms.tf) — its policy already includes
  # a statement allowing ECR's service principal to use the key on
  # behalf of this account.
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.carddemo.arn
  }

  # force_delete = false (the default in var.ecr_force_delete) preserves
  # compliance-relevant image history through `terraform destroy`. Dev
  # sandboxes that need rapid teardown set this to true via
  # terraform.tfvars.
  force_delete = var.ecr_force_delete

  # Merge resource-specific Name + Purpose tags onto the mandatory
  # common tag set (Project/Environment/Owner/ManagedBy applied via
  # provider default_tags in main.tf).
  tags = merge(local.common_tags, {
    Name    = "${local.resource_name_prefix}-ecr"
    Purpose = "Spring Boot application image registry"
  })
}

# =============================================================================
# Section 2 — Lifecycle policy: retention + expiration rules
# =============================================================================
# ECR lifecycle policies are evaluated in `rulePriority` order (ascending).
# The first rule that matches an image determines whether it is retained
# or expired. ECR evaluates rules continuously (typically within 24 hours
# of a push) and acts asynchronously.
#
# Rule layout (matches the AAP-specified three-tier policy):
#   * Rule 1 (priority 1) — Keep the most-recently-pushed
#     var.ecr_keep_n_images images that carry a "prod-" prefix or a
#     leading "v" (semver release tags). Anything older is expired.
#   * Rule 2 (priority 2) — Expire untagged images older than
#     var.ecr_untagged_days days. Catches abandoned CI builds.
#   * Rule 3 (priority 3) — Keep only the most-recently-pushed 10
#     images that carry a "dev-", "feature-", or "pr-" prefix. Older
#     short-lived branch / PR images are expired so the repository
#     stays small.
#
# Cost / hygiene rationale:
#   ECR charges $0.10/GB-month for storage. The Spring Boot image is
#   ~250 MB; retaining 30 prod images plus 10 short-lived images yields
#   ~10 GB of storage ($1/month per env). The lifecycle policy prevents
#   this number from growing without bound as CI pushes accumulate over
#   months and years.
#
# Tag prefix conventions (defined here and in
# .github/workflows/docker-build.yml):
#   prod-<git_sha>      — production release images
#   v<major>.<minor>.<patch> — semver release images (e.g., v1.2.3)
#   dev-<git_sha>       — dev / staging branch images
#   feature-<branch>-<sha> — short-lived feature branch images
#   pr-<number>-<sha>   — pull-request preview images
#
# Note: terraform `jsonencode` produces compact JSON; ECR accepts both
# compact and pretty-printed forms. We rely on jsonencode for HCL-side
# clarity (the rules array reads naturally as Terraform) and accept the
# compact wire format.
# =============================================================================

resource "aws_ecr_lifecycle_policy" "carddemo" {
  repository = aws_ecr_repository.carddemo.name

  policy = jsonencode({
    rules = [
      # -----------------------------------------------------------------
      # Rule 1 — Retain the last N prod-tagged / semver-tagged images.
      #
      # ECR lifecycle semantics: imageCountMoreThan expires any image
      # beyond the most-recently-pushed `countNumber` images that match
      # the selection. So with countNumber = 30, the 31st-oldest matching
      # image is expired on each evaluation cycle.
      #
      # tagPrefixList matches an image if ANY of its tags starts with
      # ANY of the listed prefixes. Both "prod-" (CI-generated SHA tags)
      # and "v" (semver release tags) trigger retention.
      # -----------------------------------------------------------------
      {
        rulePriority = 1
        description  = "Retain the last ${var.ecr_keep_n_images} prod/v-tagged images (release history)."
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["prod-", "v"]
          countType     = "imageCountMoreThan"
          countNumber   = var.ecr_keep_n_images
        }
        action = {
          type = "expire"
        }
      },

      # -----------------------------------------------------------------
      # Rule 2 — Expire untagged images after N days.
      #
      # Untagged images appear when a CI build pushes layers but fails
      # before pushing the manifest with a tag, or when an immutable tag
      # is removed (since IMMUTABLE prevents overwrite, removing a tag
      # leaves dangling layers). Without this rule untagged layers
      # accumulate indefinitely.
      #
      # sinceImagePushed measures the elapsed days since the image's
      # push timestamp. countUnit must be "days" when countType is
      # sinceImagePushed (ECR validates this at PutLifecyclePolicy
      # time).
      # -----------------------------------------------------------------
      {
        rulePriority = 2
        description  = "Expire untagged images after ${var.ecr_untagged_days} days (sweep abandoned CI builds)."
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.ecr_untagged_days
        }
        action = {
          type = "expire"
        }
      },

      # -----------------------------------------------------------------
      # Rule 3 — Retain only the last 10 dev/feature/pr-tagged images.
      #
      # Short-lived branch and PR images do not need long retention —
      # they are typically referenced only during the PR review window
      # and superseded by a `prod-` build on merge. Capping at 10 keeps
      # storage cost bounded while preserving enough history for active
      # debugging.
      #
      # The countNumber `10` is a deliberate constant (not surfaced as
      # a variable) because (a) it is policy hygiene rather than an
      # operational dial and (b) per-environment overrides have no
      # practical use case here.
      # -----------------------------------------------------------------
      {
        rulePriority = 3
        description  = "Retain the last 10 dev/feature/pr-tagged images (short-lived branch / PR history)."
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["dev-", "feature-", "pr-"]
          countType     = "imageCountMoreThan"
          countNumber   = 10
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

# =============================================================================
# Section 3 — Optional ECR repository policy (cross-account pulls)
# =============================================================================
# By default, only the AWS account that owns the repository can push or
# pull images. This is the correct posture for a single-account
# deployment (the dev/staging/prod environments live in separate accounts
# and each owns its own repository). For multi-account organizations
# where a single `prod-account` builds an image and `audit-account` /
# `dr-account` need to pull it, attach an aws_ecr_repository_policy
# resource granting limited pull-only access to those accounts.
#
# The template below is intentionally commented out — enabling it
# requires declaring a `var.ecr_cross_account_pull_arns` input in
# variables.tf and surfacing the consumer account IDs out-of-band.
#
# resource "aws_ecr_repository_policy" "carddemo" {
#   repository = aws_ecr_repository.carddemo.name
#
#   policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [
#       {
#         Sid    = "AllowCrossAccountPull"
#         Effect = "Allow"
#         Principal = {
#           AWS = var.ecr_cross_account_pull_arns
#         }
#         Action = [
#           "ecr:GetDownloadUrlForLayer",
#           "ecr:BatchGetImage",
#           "ecr:BatchCheckLayerAvailability"
#         ]
#       }
#     ]
#   })
# }

# =============================================================================
# Section 4 — Optional pull-through cache for upstream base images
# =============================================================================
# ECR pull-through cache rules transparently proxy upstream registries
# (Docker Hub, public ECR, Quay, GitHub Container Registry) through a
# private ECR repository. This:
#   * Reduces external bandwidth by caching frequently-pulled base
#     images (e.g., eclipse-temurin:17-jre-alpine — the base layer for
#     the Spring Boot Dockerfile).
#   * Avoids Docker Hub rate limits during CI builds.
#   * Lets the operator scan upstream images with ECR's CVE scanner.
#
# Pull-through cache is optional infrastructure and is gated behind an
# explicit operator decision. Enabling it requires:
#   * A var.ecr_enable_pull_through_cache flag (variables.tf).
#   * Confirming that upstream registry credentials are stored in AWS
#     Secrets Manager (for authenticated registries such as Docker Hub).
#
# The template below is commented out — uncomment and parameterise it
# before enabling.
#
# resource "aws_ecr_pull_through_cache_rule" "public_ecr" {
#   ecr_repository_prefix = "ecr-public"
#   upstream_registry_url = "public.ecr.aws"
# }
#
# resource "aws_ecr_pull_through_cache_rule" "dockerhub" {
#   ecr_repository_prefix = "docker-hub"
#   upstream_registry_url = "registry-1.docker.io"
#   credential_arn        = aws_secretsmanager_secret.dockerhub_credentials.arn
# }
