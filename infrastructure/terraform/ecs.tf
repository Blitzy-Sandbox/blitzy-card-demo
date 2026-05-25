###############################################################################
# infrastructure/terraform/ecs.tf
#
# Amazon ECS on AWS Fargate — cluster, capacity providers, task definition,
# service, security group, peer ingress rules, and Application Auto Scaling
# for the CardDemo Spring Boot application runtime.
#
# Replaces:
#   * z/OS mainframe LPAR + CICS region (AAP §0.1.1):
#     "runtime (z/OS mainframe LPAR → ECS Fargate behind ALB)"
#   * CICS Resource Definition (CSD) administration JCL —
#     app/jcl/CBADMCDJ.jcl (DEFINE MAPSET / DEFINE PROGRAM / DEFINE
#     TRANSACTION on GROUP(CARDDEMO)). The mainframe NEWCOPY workflow
#     (DFHCSDUP install + CEMT SET PROG NEWCOPY) is replaced operationally
#     by ECS task definition revision + rolling service update behind the
#     ALB. The Java REST controllers (src/main/java/com/awsm2/carddemo/
#     controller/*.java) and the BMS-derived DTOs (src/main/java/com/awsm2/
#     carddemo/dto/*.java) replace the BMS mapsets defined in CBADMCDJ.jcl.
#
# Purpose:
#   * Provision a single Fargate-only ECS cluster named "carddemo-<env>"
#     with Container Insights enabled (AAP §0.6.6 observability) and
#     ECS Exec command session logs encrypted by the CardDemo CMK and
#     captured by the shared application CloudWatch log group.
#   * Bind the cluster to the FARGATE and FARGATE_SPOT capacity providers
#     with a default FARGATE base strategy so the long-running web service
#     never lands on Spot capacity (Spot is opt-in for batch and dev only).
#   * Define a Fargate task definition that pulls the Spring Boot Docker
#     image from the CardDemo ECR repository (ecr.tf) and:
#       - Runs the JVM as a non-root user (`user = "1000"`) per
#         PCI-DSS hardening (AAP §0.6.6).
#       - Injects the AAP §0.7.2 non-sensitive environment variable
#         contract (AWS_REGION, ECS_CLUSTER_NAME, RDS_SECRET_ARN,
#         KMS_KEY_ARN, MSK_BOOTSTRAP_SERVERS, S3_OUTPUT_BUCKET,
#         OPENSEARCH_ENDPOINT) plus convenience metadata (RDS_HOST,
#         RDS_PORT, RDS_DATABASE, ELASTICACHE_HOST, ELASTICACHE_PORT,
#         EOD_STATE_MACHINE_ARN, PROV_STATE_MACHINE_ARN,
#         CLOUDTRAIL_TRAIL_ARN) at task start.
#       - Resolves secrets at task start via the `secrets[]` block
#         (Spring Cloud AWS reads the JSON envelope at runtime — see
#         AAP §0.6.4 dynamic rotation without restart). Only Secret
#         ARNs ever reach the task; raw secret values stay inside
#         Secrets Manager + KMS.
#       - Ships structured JSON logs (Logback + logstash-logback-encoder
#         per AAP §0.1.1 and §0.7.2) to the shared `/ecs/carddemo-<env>`
#         CloudWatch log group via the awslogs driver.
#       - Reports container liveness via `curl /actuator/health/liveness`
#         matching the Spring Actuator liveness probe (AAP §0.7.2:
#         "Health endpoints via Spring Actuator integrated with ECS
#         health checks and ALB target group health checks").
#   * Run the task definition as an ECS Service in private subnets
#     (`assign_public_ip = false` — PCI-DSS) behind the carddemo ALB
#     target group with the deployment circuit breaker enabled and
#     auto-rollback on failure, ECS Exec disabled by default for
#     production, and `ignore_changes = [desired_count, task_definition]`
#     so the GitHub Actions deploy workflow can update the task
#     definition revision and desired count out-of-band without
#     Terraform reverting the changes on the next apply.
#   * Attach Application Auto Scaling to the service:
#       - CPU-based TargetTrackingScaling at 70% (predefined metric).
#       - Memory-based TargetTrackingScaling at 80% (predefined metric).
#       - MSK consumer lag-based TargetTrackingScaling at 1,000 records
#         (customized AWS/Kafka MaxOffsetLag metric per AAP §0.1.1:
#         "auto-scaling policies based on CPU and MSK consumer lag").
#   * Define an ECS task security group that:
#       - Accepts inbound from the ALB SG only on var.app_port.
#       - Permits all outbound (RDS, MSK, ElastiCache, S3 endpoint,
#         Secrets Manager, KMS).
#     And add three peer ingress rules so RDS / MSK / ElastiCache
#     security groups whitelist the ECS task SG as a source.
#
# Operator workflow (initial provisioning):
#   1. `terraform apply` provisions the cluster + capacity providers +
#      task definition (revision 1) + service + auto-scaling target +
#      three scaling policies.
#   2. CI/CD pipeline (.github/workflows/docker-build.yml +
#      .github/workflows/deploy.yml) pushes a new image to ECR with an
#      immutable tag and calls `aws ecs update-service` to roll out a
#      new task definition revision. The `lifecycle.ignore_changes =
#      [desired_count, task_definition]` block ensures Terraform does
#      not undo this on the next apply.
#   3. Application Auto Scaling adjusts `desired_count` between
#      `var.ecs_min_capacity` (default 2) and `var.ecs_max_capacity`
#      (default 10) based on CPU / memory / MSK lag signals.
#
# Consumers (downstream sibling .tf files):
#   * outputs.tf       — exports `ecs_cluster_name`, `ecs_service_name`,
#                        `ecs_task_definition_family` for the deploy
#                        workflow.
#   * .github/workflows/deploy.yml — issues `aws ecs update-service`
#                        against the cluster + service name exported
#                        above with the freshly-pushed immutable image
#                        digest.
#
# References:
#   * AAP §0.1.1 — ECS Fargate behind ALB; auto-scaling on CPU + MSK lag.
#   * AAP §0.3.1 — ecs.tf scope (ECS cluster + task definition + service).
#   * AAP §0.6.4 — Secrets Manager dynamic rotation without restart.
#   * AAP §0.6.6 — Container Insights, KMS CMK encryption, TLS 1.2+,
#                  PCI-DSS controls.
#   * AAP §0.7.1 — All credentials via Secrets Manager (no plaintext).
#   * AAP §0.7.2 — Non-sensitive env var contract surfaced to the app.
#   * app/jcl/CBADMCDJ.jcl — legacy CICS CSD definitions (DEFINE MAPSET /
#     DEFINE PROGRAM / DEFINE TRANSACTION) replaced by ECS task definition
#     + Spring MVC REST controllers + ALB target group routing.
###############################################################################

# =============================================================================
# Section 1 — ECS cluster
# =============================================================================
# Fargate-only ECS cluster. The cluster itself is a logical grouping of
# tasks; no EC2 capacity is registered against it. Container Insights
# emits per-task CPU / memory / network metrics into CloudWatch under
# `ECS/ContainerInsights`, supplementing the application-level metrics
# published by Micrometer to the `CardDemo` namespace.
#
# ECS Exec configuration is provisioned but the actual Exec capability is
# disabled at the service level (`enable_execute_command = false` in
# Section 6). If an operator temporarily enables Exec for incident
# response, the resulting session logs are encrypted by the CardDemo CMK
# and shipped to the shared ECS application log group (PCI-DSS audit
# requirement per AAP §0.6.6 — every interactive session against
# production must be recorded).
# =============================================================================

resource "aws_ecs_cluster" "carddemo" {
  # Cluster name follows the module-wide carddemo-<env> convention. The
  # name appears in the cluster ARN, in `aws ecs describe-clusters`
  # output, and in the ECS_CLUSTER_NAME environment variable exported
  # to the application container (Section 4 — environment array).
  name = "carddemo-${var.environment}"

  # ---------------------------------------------------------------------------
  # Container Insights (AAP §0.6.6 observability).
  #
  # `enabled` activates per-task CPU, memory, network, and Docker-level
  # metrics in CloudWatch under the `ECS/ContainerInsights` namespace.
  # These metrics are consumed by the dashboards defined in cloudwatch.tf
  # and feed the CPU / memory predefined target-tracking auto-scaling
  # policies in Section 7.
  # ---------------------------------------------------------------------------
  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  # ---------------------------------------------------------------------------
  # ECS Exec configuration (AAP §0.6.6 — PCI-DSS audit).
  #
  # `logging = "OVERRIDE"` forces session logs to be encrypted by the
  # CardDemo CMK and shipped to the named CloudWatch log group; without
  # this override Exec would default to in-cluster ephemeral logs that
  # bypass audit retention.
  #
  # The KMS key reference is `aws_kms_key.carddemo` (kms.tf) — the
  # shared CardDemo CMK that also encrypts the CloudWatch log group
  # itself (cloudwatch.tf `aws_cloudwatch_log_group.ecs_app.kms_key_id`).
  # ---------------------------------------------------------------------------
  configuration {
    execute_command_configuration {
      kms_key_id = aws_kms_key.carddemo.arn
      logging    = "OVERRIDE"

      log_configuration {
        cloud_watch_encryption_enabled = true
        cloud_watch_log_group_name     = aws_cloudwatch_log_group.ecs_app.name
      }
    }
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}"
    Purpose = "ECS Fargate cluster hosting the CardDemo Spring Boot REST service (replaces z/OS LPAR + CICS region per AAP §0.1.1)"
  })
}

# =============================================================================
# Section 2 — Cluster capacity provider associations
# =============================================================================
# Binds the cluster to the AWS-managed Fargate capacity providers. Both
# `FARGATE` and `FARGATE_SPOT` are listed so individual task definitions
# (e.g., batch / dev environments) can opt into Spot pricing on a
# per-launch basis. The default capacity provider strategy pins the
# long-running CardDemo web service to `FARGATE` (on-demand, no
# interruptions), with a base of 1 ensuring at least one task is always
# scheduled on on-demand capacity even during scale-out events.
# =============================================================================

resource "aws_ecs_cluster_capacity_providers" "carddemo" {
  cluster_name = aws_ecs_cluster.carddemo.name

  # Both providers available — the default strategy below favours
  # on-demand `FARGATE`, but explicit per-service capacity-provider
  # strategies (none defined here for the carddemo service) may select
  # `FARGATE_SPOT` for cost-sensitive workloads.
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  # Default strategy applied to services that do not specify their own
  # `capacity_provider_strategy`. `base = 1` keeps at least one task on
  # on-demand capacity; `weight = 100` directs all additional capacity
  # to the same provider. Spot is intentionally excluded from the
  # default to protect the long-running web service from spot
  # interruptions.
  default_capacity_provider_strategy {
    base              = 1
    weight            = 100
    capacity_provider = "FARGATE"
  }
}

# =============================================================================
# Section 3 — ECS task security group + peer ingress rules
# =============================================================================
# The ECS task security group (`aws_security_group.ecs_task`) governs
# network access to the Fargate task ENIs. Its rules are:
#   * Ingress: from the ALB SG (`aws_security_group.alb`) on the
#     application port only. The ALB is the sole entry point into the
#     CardDemo service; no other path reaches the task ENI on any
#     application-level port.
#   * Egress: all outbound. The Spring Boot task initiates outbound
#     connections to RDS (5432), MSK (9098 IAM), ElastiCache (6379),
#     S3 (443 via gateway endpoint), Secrets Manager / KMS (443 via
#     interface endpoints), OpenSearch (443), Step Functions (443),
#     CloudWatch Logs / Metrics (443). Rather than enumerating every
#     destination, the egress is set to `0.0.0.0/0` and the
#     destination-side security groups (rds_sg, msk_sg, elasticache_sg)
#     restrict ingress to this exact SG ID — closing the loop via the
#     three peer rules below.
#
# Peer ingress rules (`aws_security_group_rule.*_from_ecs`):
#   * rds_from_ecs         — RDS PostgreSQL on 5432 from the ECS task SG.
#   * msk_from_ecs         — MSK IAM SASL_SSL on 9098 from the ECS task SG.
#   * elasticache_from_ecs — ElastiCache Redis on 6379 from the ECS task SG.
#
# These rules are declared as standalone `aws_security_group_rule`
# resources rather than embedded ingress blocks on the peer security
# groups so the dependency direction flows from ecs.tf to the peer .tf
# files only — peer SGs (rds.tf, msk.tf, elasticache.tf) do not
# reference `aws_security_group.ecs_task` directly, avoiding a circular
# module dependency.
# =============================================================================

resource "aws_security_group" "ecs_task" {
  name        = "carddemo-${var.environment}-ecs-task-sg"
  description = "CardDemo ECS task: inbound from ALB on var.app_port; outbound to RDS, MSK, ElastiCache, S3 endpoint, Secrets Manager, KMS, OpenSearch, Step Functions, CloudWatch"
  vpc_id      = data.aws_vpc.carddemo.id

  # ---------------------------------------------------------------------------
  # Ingress: ALB → ECS task on the application port (`var.app_port`,
  # default 8080). The ALB terminates TLS on port 443 and forwards
  # plain HTTP to the task inside the VPC; the hop is protected by VPC
  # isolation and the ALB SG → ECS task SG source restriction.
  #
  # AAP §0.6.6: "TLS 1.2+ is enforced on the ALB (HTTPS listener with
  # ACM certificate)." Internal hops within the VPC are out of scope
  # of the PCI-DSS in-transit encryption boundary.
  # ---------------------------------------------------------------------------
  ingress {
    description     = "ALB to ECS task on application port"
    from_port       = var.app_port
    to_port         = var.app_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # ---------------------------------------------------------------------------
  # Egress: all outbound. Destination-side SGs (rds_sg, msk_sg,
  # elasticache_sg) restrict inbound to this exact SG ID, so the
  # effective egress is constrained at the destination perimeter.
  # ---------------------------------------------------------------------------
  egress {
    description = "All outbound (RDS, MSK, ElastiCache, S3 endpoint, Secrets Manager, KMS, OpenSearch, Step Functions, CloudWatch)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-ecs-task-sg"
    Purpose = "ECS Fargate task ENI security group"
  })
}

# Peer ingress rule: RDS PostgreSQL accepts ECS task → 5432.
# Replaces COBOL VSAM file access (no network boundary on the mainframe);
# in the cloud target, every dependency is reached over the network and
# explicitly whitelisted at the perimeter.
resource "aws_security_group_rule" "rds_from_ecs" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_task.id
  security_group_id        = aws_security_group.rds.id
  description              = "CardDemo ECS task to RDS PostgreSQL (replaces VSAM KSDS access per AAP Section 0.6.2)"
}

# Peer ingress rule: MSK accepts ECS task → 9098 (IAM SASL_SSL).
# AAP §0.6.5: "MSK (Kafka) topics used for all inter-service transaction
# events ... partition by account ID for ordering guarantees." Port 9098
# is the AWS-managed MSK IAM auth port (different from 9092 plaintext
# or 9094 SASL_SCRAM — IAM uses 9098 exclusively).
resource "aws_security_group_rule" "msk_from_ecs" {
  type                     = "ingress"
  from_port                = 9098
  to_port                  = 9098
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_task.id
  security_group_id        = aws_security_group.msk.id
  description              = "CardDemo ECS task to MSK (SASL_SSL + IAM, AAP Section 0.6.5)"
}

# Peer ingress rule: ElastiCache Redis accepts ECS task → 6379.
# AAP §0.7.1: "ElastiCache (Redis) used for account balance caching —
# cache-aside pattern with TTL aligned to transaction frequency and
# allkeys-lru eviction policy."
resource "aws_security_group_rule" "elasticache_from_ecs" {
  type                     = "ingress"
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_task.id
  security_group_id        = aws_security_group.elasticache.id
  description              = "CardDemo ECS task to ElastiCache Redis (cache-aside per AAP Section 0.7.1)"
}

# =============================================================================
# Section 4 — ECS task definition
# =============================================================================
# Fargate task definition that AWS ECS instantiates one or more times per
# the service desired count (Section 6). The task is sized via
# `var.ecs_task_cpu` / `var.ecs_task_memory` (defaults: 1024 CPU units /
# 2048 MB — Spring Boot 3.x + Spring Kafka + Spring Batch comfortably fit
# at these defaults per `variables.tf` Section 8 documentation).
#
# `network_mode = "awsvpc"` is mandatory for Fargate; each task receives
# its own ENI in the configured subnets with its own security group
# attachment.
#
# `runtime_platform.cpu_architecture = "X86_64"` matches the Docker
# image base (eclipse-temurin:17-jre-alpine, x86_64). To migrate to
# Graviton (arm64) for cost savings, both the Docker base image and
# this attribute must change together.
#
# Container properties (`container_definitions`):
#   * Single container named `carddemo-app` (the ALB target group
#     load_balancer block in Section 6 binds to this exact name).
#   * Image: `${aws_ecr_repository.carddemo.repository_url}:${var.app_image_tag}`.
#     Production deploys override `var.app_image_tag` with an immutable
#     git-SHA tag (or image digest) via the CI/CD pipeline; the default
#     `"latest"` is intended only for local development convenience.
#   * `essential = true` — task exits if this container exits.
#   * portMappings: a single TCP port (`var.app_port`) named `http` —
#     ECS Service Connect would consume `name` and `appProtocol` if
#     enabled later; we keep them populated for forward compatibility.
#   * environment[]: non-sensitive values (the AAP §0.7.2 contract).
#   * secrets[]: ARNs only — Spring Cloud AWS resolves the JSON
#     envelope at runtime; supports rotation without restart per
#     AAP §0.6.4 (DataSource and Kafka factories are @RefreshScope beans).
#   * logConfiguration: awslogs driver → `/ecs/carddemo-<env>` log group.
#   * healthCheck: `curl /actuator/health/liveness` matches the Spring
#     Boot liveness probe contract.
#   * `user = "1000"`: non-root container user (PCI-DSS hardening per
#     AAP §0.6.6).
#   * `readonlyRootFilesystem = false`: Spring Boot writes to /tmp for
#     log buffering and uploads; making the rootfs read-only would
#     require mounting an emptyDir volume at /tmp, which is more
#     plumbing than the current workload justifies.
#   * `linuxParameters.initProcessEnabled = true`: enables a `tini`-style
#     PID 1 reaper so zombie child processes are reaped — important for
#     Java apps that spawn helper processes (e.g., Flyway, Postgres
#     pg_isready healthchecks).
# =============================================================================

resource "aws_ecs_task_definition" "carddemo" {
  # Task definition family — multiple revisions of the same family
  # accumulate over time. The CI/CD pipeline issues `aws ecs register-
  # task-definition` against this family with the new image digest and
  # updates the service to the resulting revision number.
  family = "carddemo-${var.environment}"

  # Fargate-only: awsvpc is the only supported network mode.
  network_mode = "awsvpc"

  # Limits Fargate to this task definition; EC2 launch is not supported.
  requires_compatibilities = ["FARGATE"]

  # Task CPU + memory (Fargate-validated discrete pairs — see variables.tf
  # Section 8). 1024 / 2048 by default is the smallest pair that
  # comfortably accommodates Spring Boot 3.x + Spring Kafka + Spring
  # Batch warm-up.
  cpu    = var.ecs_task_cpu
  memory = var.ecs_task_memory

  # IAM roles (iam.tf):
  #   * execution_role_arn — assumed by the Fargate agent to pull the
  #     image, resolve secrets, and ship logs.
  #   * task_role_arn      — assumed by the JVM process for S3, MSK,
  #     Step Functions, OpenSearch, CloudWatch, SQS, Secrets Manager
  #     SDK calls.
  execution_role_arn = aws_iam_role.ecs_task_execution.arn
  task_role_arn      = aws_iam_role.ecs_task_role.arn

  # ---------------------------------------------------------------------------
  # Runtime platform — Linux on x86_64. To migrate to arm64 (Graviton)
  # this must change in lockstep with the Docker base image in
  # ../../Dockerfile (eclipse-temurin:17-jre-alpine multi-arch tag).
  # ---------------------------------------------------------------------------
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # ---------------------------------------------------------------------------
  # Container definitions — JSON document encoded from native HCL.
  # Using jsonencode(...) lets us keep the property tree as native HCL
  # data (lists / objects) while Terraform serializes the resulting
  # JSON at plan time. This avoids brittle heredoc string templates and
  # preserves syntax-aware IDE support.
  # ---------------------------------------------------------------------------
  container_definitions = jsonencode([
    {
      # Container name — referenced by the ECS service `load_balancer`
      # block (Section 6) and by the ALB target group as the
      # registration target. MUST match exactly.
      name = "carddemo-app"

      # Container image — ECR repository URL + image tag.
      # `var.app_image_tag` is `"latest"` by default (dev convenience);
      # production deploys override with an immutable git-SHA tag (or
      # ideally the full image digest) via the deploy workflow per
      # AAP §0.6.6 ("Immutable, tamper-evident audit log") and ECR
      # repository's IMMUTABLE tag mutability (ecr.tf).
      image = "${aws_ecr_repository.carddemo.repository_url}:${var.app_image_tag}"

      # Failing this container fails the task — ECS will tear down the
      # task and the service will replace it (subject to deployment
      # circuit breaker thresholds in Section 6).
      essential = true

      # ---- Port mappings ----
      #
      # `var.app_port` (default 8080) is the Spring Boot listener port.
      # `name = "http"` and `appProtocol = "http"` are populated for
      # forward compatibility with ECS Service Connect (which consumes
      # named ports); they are inert under plain ALB target group
      # registration.
      portMappings = [
        {
          containerPort = var.app_port
          protocol      = "tcp"
          name          = "http"
          appProtocol   = "http"
        }
      ]

      # ---- Environment variables ----
      #
      # Only Secret ARNs and non-sensitive values appear here (PCI-DSS:
      # never plaintext secrets in environment variables). The full
      # AAP §0.7.2 contract:
      #
      #   AWS_REGION              — current region (Micrometer dims, AWS SDK)
      #   ECS_CLUSTER_NAME        — operator visibility / log enrichment
      #   RDS_SECRET_ARN          — Spring Cloud AWS resolves at runtime
      #   KMS_KEY_ARN             — application-side envelope encryption
      #   MSK_BOOTSTRAP_SERVERS   — Spring Kafka producer/consumer factory
      #   S3_OUTPUT_BUCKET        — S3OutputService adapter target bucket
      #   OPENSEARCH_ENDPOINT     — OpenSearchIndexer + AuditLogService
      #
      # Convenience values (not strictly required by AAP §0.7.2 but
      # consumed by Spring Boot for connection-string assembly and
      # operator log enrichment):
      #
      #   SPRING_PROFILES_ACTIVE  — profile selection (dev/staging/prod)
      #   RDS_HOST/PORT/DATABASE  — HikariCP JDBC URL components
      #   ELASTICACHE_HOST/PORT   — Lettuce Redis connection
      #   EOD_STATE_MACHINE_ARN   — Step Functions trigger ARN
      #   PROV_STATE_MACHINE_ARN  — provisioning state machine ARN
      #   CLOUDTRAIL_TRAIL_ARN    — audit cross-reference
      environment = [
        # ---- AAP §0.7.2 mandatory contract ----
        { name = "AWS_REGION", value = var.aws_region },
        { name = "ECS_CLUSTER_NAME", value = aws_ecs_cluster.carddemo.name },
        { name = "RDS_SECRET_ARN", value = aws_secretsmanager_secret.rds_master.arn },
        { name = "KMS_KEY_ARN", value = aws_kms_key.carddemo.arn },
        { name = "MSK_BOOTSTRAP_SERVERS", value = aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam },
        { name = "S3_OUTPUT_BUCKET", value = aws_s3_bucket.batch_outputs.bucket },
        { name = "OPENSEARCH_ENDPOINT", value = "https://${aws_opensearch_domain.carddemo.endpoint}" },

        # ---- Spring profile selector ----
        # Maps directly to var.environment (dev | staging | prod) — the
        # Spring Boot application loads application-${env}.yml on top
        # of application.yml.
        { name = "SPRING_PROFILES_ACTIVE", value = var.environment },

        # ---- RDS connection metadata (HikariCP convenience) ----
        # Provided in addition to RDS_SECRET_ARN so that operator
        # tooling (Spring Actuator /actuator/health/db endpoint, log
        # messages on connection failure) can surface the
        # host/port/database without parsing the full JDBC URL from
        # the secret.
        { name = "RDS_HOST", value = aws_db_instance.carddemo.address },
        { name = "RDS_PORT", value = tostring(aws_db_instance.carddemo.port) },
        { name = "RDS_DATABASE", value = aws_db_instance.carddemo.db_name },

        # ---- ElastiCache connection metadata (cache-aside per AAP §0.7.1) ----
        # Primary endpoint + port consumed by Spring Data Redis
        # (Lettuce client) for the cache-aside read-through pattern
        # on high-frequency account balance lookups.
        { name = "ELASTICACHE_HOST", value = aws_elasticache_replication_group.carddemo.primary_endpoint_address },
        { name = "ELASTICACHE_PORT", value = tostring(aws_elasticache_replication_group.carddemo.port) },

        # ---- Step Functions orchestration (AAP §0.6.3) ----
        # End-of-day pipeline ARN consumed by the
        # StepFunctionsOrchestrator adapter when CORPT00C-equivalent
        # report requests arrive on the report.requested Kafka topic.
        { name = "EOD_STATE_MACHINE_ARN", value = aws_sfn_state_machine.eod_batch_pipeline.arn },

        # Provisioning state machine ARN — operator-triggered seed-data
        # refresh + Flyway migration + Glue ETL bulk load workflow.
        { name = "PROV_STATE_MACHINE_ARN", value = aws_sfn_state_machine.file_provisioning.arn },

        # ---- Audit cross-reference (AAP §0.6.6) ----
        # CloudTrail trail ARN — consumed by AuditLogService for
        # cross-referencing AWS API audit events with application-
        # emitted events forwarded to OpenSearch.
        { name = "CLOUDTRAIL_TRAIL_ARN", value = aws_cloudtrail.carddemo.arn },
      ]

      # ---- Secrets injection (AAP §0.6.4) ----
      #
      # `secrets[]` entries are resolved at task start by the Fargate
      # agent (which assumes the ECS task EXECUTION role and calls
      # secretsmanager:GetSecretValue + kms:Decrypt). The resolved
      # value is injected into the container's environment as if it
      # had appeared in `environment[]` — but the value never appears
      # in the ECS task definition body, never in CloudTrail
      # parameters, and never in the Terraform state.
      #
      # The `valueFrom` syntax `secret_arn:json-key::` extracts a
      # specific field from a JSON-encoded secret (Spring Cloud AWS
      # stores JWT signing keys as `{"signing-key": "..."}` and
      # OpenSearch credentials as `{"password": "..."}` — see
      # secrets.tf for the exact envelope shape).
      #
      # The trailing `::` is required syntax (specifying neither a
      # version ID nor a version stage — uses AWSCURRENT).
      #
      # Rotation handling (AAP §0.6.4): the JWT_SIGNING_KEY and
      # OPENSEARCH_PASSWORD values are injected ONCE at task start.
      # Rotation events are delivered via SNS → SQS (queue managed in
      # secrets.tf Section 3) and the application code subscribes via
      # KafkaEventConsumer-style listener to publish a Spring
      # `RefreshEvent`, triggering @RefreshScope beans (JwtTokenProvider,
      # OpenSearchIndexer) to fetch the rotated value through the
      # Spring Cloud AWS Secrets Manager client without restarting
      # the task.
      secrets = [
        {
          name      = "JWT_SIGNING_KEY"
          valueFrom = "${aws_secretsmanager_secret.jwt_signing_key.arn}:signing-key::"
        },
        {
          name      = "OPENSEARCH_PASSWORD"
          valueFrom = "${aws_secretsmanager_secret.opensearch_master.arn}:password::"
        },
      ]

      # ---- Log configuration (AAP §0.1.1, §0.7.2) ----
      #
      # The awslogs driver ships the container stdout/stderr stream
      # verbatim to CloudWatch Logs. Spring Boot is configured (via
      # src/main/resources/logback-spring.xml) to emit structured JSON
      # using logstash-logback-encoder, so every log line is a single
      # JSON document — directly indexable by the OpenSearch Logs
      # subscription consumer.
      #
      # `awslogs-stream-prefix = "carddemo-app"` produces stream names
      # of the form `carddemo-app/<container-name>/<task-id>`,
      # disambiguating logs across concurrent task replicas.
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.ecs_app.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "carddemo-app"
        }
      }

      # ---- Container health check (AAP §0.7.2) ----
      #
      # `curl -fsS http://localhost:<app_port>/actuator/health/liveness`
      # matches the Spring Actuator liveness probe contract:
      #   * `management.endpoint.health.probes.enabled = true` in
      #     application.yml exposes /actuator/health/liveness.
      #   * The endpoint returns 200 OK when the JVM is up; it does
      #     NOT depend on RDS/Kafka/Redis (those failures surface on
      #     /actuator/health/readiness, which is checked by the ALB
      #     target group health check — alb.tf).
      #
      # `-fsS` flags: -f (fail on HTTP errors >=400), -s (silent),
      # -S (show errors). Without -f, curl returns 0 even on 5xx
      # because the HTTP transaction itself succeeded.
      #
      # Threshold tuning:
      #   * interval = 30s        — every 30 seconds (the default).
      #   * timeout = 5s          — fail a single probe if curl
      #                             doesn't return within 5 seconds.
      #   * retries = 3           — three consecutive failures
      #                             mark the container unhealthy
      #                             (90 seconds total grace from
      #                             first failure to ECS replacement).
      #   * startPeriod = 60s     — give Spring Boot 60 seconds to
      #                             boot before any failed health
      #                             check counts (cold start: JVM
      #                             init + Spring context + Flyway
      #                             baseline check + Kafka factory
      #                             warm-up).
      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS http://localhost:${var.app_port}/actuator/health/liveness || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }

      # ---- Linux parameters ----
      #
      # `initProcessEnabled = true` injects a tini-style init process
      # as PID 1 inside the container. This reaps zombie children
      # spawned by the JVM (e.g., Flyway invoking psql for migration
      # repair, Spring Batch tasklets shelling out to AWS CLI), and
      # forwards SIGTERM correctly during ECS deregistration.
      linuxParameters = {
        initProcessEnabled = true
      }

      # ---- Filesystem mode ----
      #
      # Read-only rootfs is the more secure default but requires
      # mounting a writable emptyDir volume at /tmp for the JVM's
      # temporary files, Logback's rolling-file appenders (if used
      # — currently unused; logs ship via stdout to CloudWatch), and
      # any temporary Multipart upload buffers in S3OutputService.
      # The trade-off currently favours simpler operations; revisit
      # if the security posture review demands read-only rootfs.
      readonlyRootFilesystem = false

      # ---- Container user (AAP §0.6.6 PCI-DSS hardening) ----
      #
      # The JVM runs as UID 1000 (a non-root user defined in
      # ../../Dockerfile via `RUN useradd -u 1000 spring`). This
      # prevents privilege escalation from a JVM exploit to the
      # underlying container runtime.
      user = "1000"
    }
  ])

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}"
    Purpose = "ECS Fargate task definition — CardDemo Spring Boot REST service"
  })
}


# =============================================================================
# Section 5 — ECS service
# =============================================================================
# Long-running Fargate service that maintains `var.ecs_desired_count`
# replicas of the task definition (Application Auto Scaling in Section 6
# overrides this at runtime).
#
# Key configuration:
#   * `launch_type = "FARGATE"` — serverless; no EC2 management.
#   * `platform_version = "LATEST"` — newest Fargate platform; AWS
#     transparently rolls forward across minor versions for security
#     patches.
#   * Network configuration:
#       - Private subnets only (AAP §0.6.6 — PCI-DSS).
#       - `assign_public_ip = false` — no public IPv4 on the task ENI;
#         the task reaches the internet (if needed) only via NAT
#         gateways, or stays entirely within the VPC via interface
#         endpoints (Secrets Manager, KMS, ECR API, ECR DKR,
#         CloudWatch Logs — see main.tf).
#   * Load balancer registration:
#       - Target group: `aws_lb_target_group.carddemo` (alb.tf),
#         target_type = "ip" required for Fargate awsvpc network mode.
#       - Container name: must match the container_definitions name
#         exactly ("carddemo-app").
#       - Container port: `var.app_port` (default 8080).
#   * Deployment circuit breaker:
#       - `enable = true` — ECS aborts a deploy after consecutive
#         failed task launches (configurable threshold; default 10).
#       - `rollback = true` — failed deploys auto-revert to the
#         previous task definition revision.
#   * Deployment percentages:
#       - max_percent = 200 — during a deploy, up to 2× desired_count
#         tasks may be running (rolling deploy via new task set
#         alongside old).
#       - min_healthy_percent = 100 — never drop below desired_count
#         healthy tasks; preserves capacity during deploys.
#   * `health_check_grace_period_seconds = 60` — give a new task 60s
#     to pass the ALB target group health check before ECS counts a
#     failure (matches the container healthCheck startPeriod).
#   * `lifecycle.ignore_changes`:
#       - `desired_count` — owned by Application Auto Scaling at
#         runtime (Section 6). Terraform setting this on every apply
#         would clash with the auto-scaler.
#       - `task_definition` — owned by the CI/CD deploy workflow
#         (.github/workflows/deploy.yml issues
#         `aws ecs update-service --task-definition <new-revision>`).
#         Terraform reverting this on the next apply would be
#         catastrophic — it would force every deploy to wait for a
#         Terraform run, defeating the rolling deploy model.
#   * `enable_execute_command = false` — ECS Exec disabled at the
#     service level. Operators must temporarily enable Exec via
#     `aws ecs update-service --enable-execute-command` (audited
#     change) when needed. Audit logs go to the CMK-encrypted log
#     group configured on the cluster (Section 1).
#   * `depends_on = [aws_lb_listener.https]` — explicit dependency on
#     the HTTPS listener so the listener (and therefore the
#     target-group binding) exists before the service attempts to
#     register targets. Without this, Terraform may attempt to
#     register targets before the listener is provisioned, causing
#     `ResourceNotReady` errors.
# =============================================================================

resource "aws_ecs_service" "carddemo" {
  # Service name follows the carddemo-<env>-svc convention. The name
  # appears in `aws ecs describe-services`, in the service ARN
  # consumed by Application Auto Scaling (Section 6 — resource_id), and
  # in CI/CD `aws ecs update-service` calls.
  name = "carddemo-${var.environment}-svc"

  # Cluster ARN/ID — `aws_ecs_cluster.carddemo.id` returns the cluster
  # ARN under the AWS provider 5.x (vs older versions where `id` was
  # the cluster name). Use `.id` for forward compatibility.
  cluster = aws_ecs_cluster.carddemo.id

  # Task definition revision — bound to the latest revision at apply
  # time. Subsequent CI/CD deploys mutate this out-of-band; the
  # lifecycle.ignore_changes below prevents Terraform from clobbering
  # those updates.
  task_definition = aws_ecs_task_definition.carddemo.arn

  # Initial replica count — Application Auto Scaling adjusts this at
  # runtime. Same ignore_changes contract as `task_definition`.
  desired_count = var.ecs_desired_count

  # Fargate-only; no EC2 launch type.
  launch_type = "FARGATE"

  # Latest Fargate platform version — AWS rolls forward minor versions
  # for security patches and runtime upgrades transparently.
  platform_version = "LATEST"

  # ---------------------------------------------------------------------------
  # Network configuration — awsvpc network mode places each task on its
  # own ENI in a private subnet with the ECS task SG attached.
  #
  # AAP §0.6.6 / PCI-DSS: `assign_public_ip = false` ensures the task
  # has no public IPv4 and is unreachable from the internet except
  # through the ALB.
  # ---------------------------------------------------------------------------
  network_configuration {
    subnets          = data.aws_subnets.private.ids
    security_groups  = [aws_security_group.ecs_task.id]
    assign_public_ip = false
  }

  # ---------------------------------------------------------------------------
  # Load balancer registration — binds the service to the ALB target
  # group defined in alb.tf. `target_group_arn` registers the task
  # ENI IP addresses as targets (target_type = "ip" — required for
  # Fargate awsvpc network mode).
  #
  # `container_name` MUST match the name field in the
  # container_definitions JSON above (`carddemo-app`). `container_port`
  # MUST match the portMappings.containerPort value (var.app_port).
  # Any mismatch yields a "container name not found in task definition"
  # error at service creation time.
  # ---------------------------------------------------------------------------
  load_balancer {
    target_group_arn = aws_lb_target_group.carddemo.arn
    container_name   = "carddemo-app"
    container_port   = var.app_port
  }

  # ---------------------------------------------------------------------------
  # Deployment circuit breaker — protects against bad deploys.
  #
  # ECS counts consecutive task launch failures; once the threshold is
  # exceeded the deploy is marked failed. With `rollback = true` ECS
  # automatically reverts the service to the previous task definition
  # revision and restores the previous desired count.
  #
  # This is the primary defence against breaking deploys reaching
  # production — combined with the immutable ECR tags and the
  # `health_check_grace_period_seconds` below, a failing health check
  # causes a clean rollback rather than a partial-outage propagation.
  # ---------------------------------------------------------------------------
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # ---------------------------------------------------------------------------
  # Deployment percentages — control the rolling deploy window.
  #
  # max_percent = 200 lets ECS run up to 2× desired_count tasks during
  # a deploy (new task set alongside the old). min_healthy_percent =
  # 100 ensures that the running task count never drops below
  # desired_count — old tasks are only deregistered after the
  # corresponding new tasks become healthy. Together these settings
  # produce a zero-downtime rolling deploy.
  # ---------------------------------------------------------------------------
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  # ---------------------------------------------------------------------------
  # Health check grace period — 60s window during which the ALB target
  # group health check failures do not cause ECS to restart the task.
  # Matches the container healthCheck.startPeriod above so the JVM
  # has a consistent boot budget across both check layers.
  # ---------------------------------------------------------------------------
  health_check_grace_period_seconds = 60

  # ---------------------------------------------------------------------------
  # Lifecycle — preserve out-of-band updates.
  #
  # `desired_count`    is owned by Application Auto Scaling at runtime.
  # `task_definition`  is owned by the CI/CD deploy workflow.
  #
  # Without these `ignore_changes` entries, every `terraform apply`
  # would attempt to revert auto-scaling decisions and deploy
  # revisions back to the values frozen in Terraform — undoing the
  # very automation we built around the service.
  # ---------------------------------------------------------------------------
  lifecycle {
    ignore_changes = [desired_count, task_definition]
  }

  # ---------------------------------------------------------------------------
  # ECS Exec — disabled at the service level for security posture
  # (AAP §0.6.6 PCI-DSS — no interactive shell access in production
  # by default). Operators temporarily enable Exec via
  # `aws ecs update-service --enable-execute-command` for incident
  # response, and the session logs land in the CMK-encrypted log
  # group configured on the cluster (Section 1).
  # ---------------------------------------------------------------------------
  enable_execute_command = false

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-svc"
    Purpose = "ECS Fargate service hosting the CardDemo Spring Boot REST API behind the ALB"
  })

  # ---------------------------------------------------------------------------
  # Explicit dependency — the HTTPS listener must be provisioned before
  # the service attempts target registration. Without this, the
  # service create call can race ahead of the listener and produce a
  # transient `ResourceNotReady` error.
  # ---------------------------------------------------------------------------
  depends_on = [aws_lb_listener.https]
}

# =============================================================================
# Section 6 — Application Auto Scaling
# =============================================================================
# Three Target Tracking scaling policies adjust the service's
# `desired_count` between `var.ecs_min_capacity` (default 2) and
# `var.ecs_max_capacity` (default 10):
#
#   1. CPU utilization        — predefined ECSServiceAverageCPUUtilization
#                               at 70%.
#   2. Memory utilization     — predefined ECSServiceAverageMemoryUtilization
#                               at 80%.
#   3. MSK consumer lag       — customized AWS/Kafka MaxOffsetLag metric
#                               at 1,000 records (AAP §0.1.1: "auto-scaling
#                               policies based on CPU and MSK consumer
#                               lag").
#
# Target Tracking is preferred over Step Scaling because:
#   * It captures the operator's intent ("keep CPU around 70%")
#     directly without translating to alarm thresholds + scaling
#     actions.
#   * The AWS Auto Scaling backend automatically creates and manages
#     the underlying CloudWatch alarms.
#   * Multiple policies cooperate without policy-vs-policy conflicts
#     — the most aggressive scale-out wins at any moment.
#
# Cooldown tuning:
#   * scale_out_cooldown = 60s   — react quickly to load spikes.
#   * scale_in_cooldown  = 300s  — slower scale-in to avoid flapping
#                                  on transient load dips.
#   * MSK lag specifically has scale_in_cooldown = 600s because
#     consumer lag recovery is naturally slower than CPU/memory and
#     premature scale-in can cause renewed lag accumulation.
# =============================================================================

resource "aws_appautoscaling_target" "ecs" {
  # Capacity bounds — sourced from variables.tf Section 8. Defaults
  # 2 / 10 satisfy the AAP §0.1.1 requirement for HA (≥ 2 across
  # multiple AZs) and the §0.7.2 performance requirement (ECS Fargate
  # auto-scaling handles peak transaction volume without manual
  # intervention).
  min_capacity = var.ecs_min_capacity
  max_capacity = var.ecs_max_capacity

  # Resource ID — `service/<cluster>/<service>` is the canonical
  # Application Auto Scaling identifier for an ECS service. Built
  # from the cluster and service NAMES (not ARNs).
  resource_id = "service/${aws_ecs_cluster.carddemo.name}/${aws_ecs_service.carddemo.name}"

  # Scalable dimension — `ecs:service:DesiredCount` is the only
  # supported dimension for ECS Application Auto Scaling.
  scalable_dimension = "ecs:service:DesiredCount"

  # Service namespace — `ecs` for ECS services. (DynamoDB, RDS,
  # Lambda, AppStream, etc. each have their own namespace; this
  # ECS-specific value is fixed.)
  service_namespace = "ecs"
}

# -----------------------------------------------------------------------------
# 6.1 — CPU-based scaling policy
#
# Predefined metric ECSServiceAverageCPUUtilization at 70%. When
# average CPU across the service's tasks exceeds 70%, Application
# Auto Scaling adds tasks; when it drops well below 70%, tasks are
# removed (subject to the cooldown windows).
#
# 70% leaves ~30% headroom for transient bursts (GC pauses, Spring
# Batch warm-up, request spikes) without aggressive scale-out.
# -----------------------------------------------------------------------------
resource "aws_appautoscaling_policy" "ecs_cpu" {
  name               = "carddemo-${var.environment}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value = 70.0

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    # Slow scale-in (5 min) avoids flapping on transient load dips.
    # Fast scale-out (1 min) reacts quickly to spikes.
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

# -----------------------------------------------------------------------------
# 6.2 — Memory-based scaling policy
#
# Predefined metric ECSServiceAverageMemoryUtilization at 80%. Memory
# pressure is a leading indicator for Java workloads — the JVM holds
# allocated heap until GC runs, so memory utilization climbs steadily
# under sustained throughput.
#
# The 80% threshold is more permissive than CPU (70%) because Java
# heap typically lives in the 60-85% range under normal load (GC
# cycles release back below the watermark periodically).
# -----------------------------------------------------------------------------
resource "aws_appautoscaling_policy" "ecs_memory" {
  name               = "carddemo-${var.environment}-memory-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value = 80.0

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageMemoryUtilization"
    }

    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

# -----------------------------------------------------------------------------
# 6.3 — MSK consumer lag-based scaling policy (AAP §0.1.1)
#
# Customized CloudWatch metric: AWS/Kafka MaxOffsetLag, dimensioned by
# `Cluster Name = <msk_cluster_name>`. The metric represents the
# maximum unconsumed-offset lag across all consumer groups attached
# to the cluster.
#
# Target value of 1,000 records — the threshold at which transaction
# processing latency begins to materially affect the user-perceived
# response time on REST endpoints that wait for downstream Kafka
# event acknowledgment. Higher than the per-partition expectation
# because the metric is the MAX across consumers, so 1,000 across an
# N-partition topic is N × 1,000 effective backlog.
#
# Cooldown tuning:
#   * scale_in_cooldown = 600s — consumer lag recovery is slower than
#     CPU/memory; if we scale in too quickly we cause renewed lag
#     accumulation as the freshly-scaled-in consumers can't keep up.
#   * scale_out_cooldown = 60s — fast scale-out to catch up.
# -----------------------------------------------------------------------------
resource "aws_appautoscaling_policy" "ecs_msk_lag" {
  name               = "carddemo-${var.environment}-msk-lag-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value = 1000.0

    customized_metric_specification {
      metric_name = "MaxOffsetLag"
      namespace   = "AWS/Kafka"
      statistic   = "Maximum"

      # ---------------------------------------------------------------------
      # Dimension: `Cluster Name` (note: with a literal space — this is
      # the dimension name MSK actually emits the metric with, not
      # `ClusterName` without space). The value is the MSK cluster
      # name (not the ARN).
      #
      # The cloudwatch.tf dashboard for MSK references this exact
      # dimension key — keep in sync if the metric naming ever
      # changes.
      # ---------------------------------------------------------------------
      dimensions {
        name  = "Cluster Name"
        value = aws_msk_cluster.carddemo.cluster_name
      }
    }

    # Slow scale-in (10 min) — consumer lag recovery is slower than
    # CPU/memory; aggressive scale-in causes renewed lag.
    scale_in_cooldown = 600

    # Fast scale-out (1 min) — catch up to lag growth quickly.
    scale_out_cooldown = 60
  }
}

