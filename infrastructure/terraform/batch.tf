###############################################################################
# infrastructure/terraform/batch.tf
#
# AWS Batch Fargate compute environment + job queue + six Spring Batch job
# definitions for the CardDemo end-of-day processing pipeline.
#
# Purpose:
#   Provision the managed batch execution layer that runs the CardDemo
#   Spring Batch jars in serverless Fargate containers. Each job definition
#   wraps one Spring Batch job (per AAP §0.4.1) that the Step Functions
#   `eod-batch-pipeline` state machine submits via the
#   `arn:aws:states:::batch:submitJob.sync` task integration.
#
# Replaces:
#   Legacy mainframe JES batch scheduling (per AAP §0.6.3) — the COBOL/JCL
#   end-of-day job stream
#       POSTTRAN → INTCALC → COMBTRAN → { CREASTMT | TRANREPT } [+ PRTCATBL]
#   is decomposed into six AWS Batch job definitions orchestrated by the
#   AWS Step Functions state machine defined in
#   `src/main/resources/stepfunctions/eod-batch-pipeline.asl.json`.
#
# Submitted by:
#   AWS Step Functions state machines (see `stepfunctions.tf`) — each
#   `Task` state issues a `batch:submitJob.sync` against the
#   `aws_batch_job_queue.carddemo` queue, naming one of the six job
#   definitions in this file. The `.sync` integration blocks the state
#   machine until the AWS Batch job reaches a terminal state, so the
#   state machine's Catch / Retry policies fully model the legacy JCL
#   `COND=` and `IF ... THEN` semantics.
#
# Architecture (per AAP §0.4.1 and the agent prompt):
#   Section 1 — Batch job security group + ingress rules into RDS / MSK /
#               ElastiCache peer security groups so the Fargate ENIs can
#               reach the application's data plane while remaining
#               private-subnet-only (PCI-DSS network segregation).
#   Section 2 — Fargate `MANAGED` compute environment, sized by
#               var.batch_max_vcpus, attached to private subnets only.
#   Section 3 — Priority-1 ENABLED job queue bound to the compute
#               environment (the queue that Step Functions submits to).
#   Section 4 — Common environment variable list assembled from the
#               surrounding Terraform module (RDS endpoint, MSK brokers,
#               KMS CMK ARN, S3 output bucket, OpenSearch endpoint,
#               ElastiCache endpoint, Secrets Manager ARNs).
#   Section 5 — Six Fargate job definitions (one per replaced JCL job):
#                 * daily_transaction_posting  ← app/jcl/POSTTRAN.jcl  (CBTRN02C)
#                 * interest_calculation       ← app/jcl/INTCALC.jcl   (CBACT04C)
#                 * combine_transactions       ← app/jcl/COMBTRAN.jcl  (PGM=SORT + IDCAMS REPRO)
#                 * statement_generation       ← app/jcl/CREASTMT.JCL  (CBSTM03A / CBSTM03B)
#                 * transaction_report         ← app/jcl/TRANREPT.jcl  (CBTRN03C)
#                 * print_category_balance     ← app/jcl/PRTCATBL.jcl  (SORT + report)
#
# Resource naming convention (consistent with the rest of the module):
#   carddemo-<env>-batch-ce           — compute environment
#   carddemo-<env>-batch-queue        — job queue
#   carddemo-<env>-batch-job-sg       — Fargate security group
#   carddemo-<env>-<job-name>         — job definitions (e.g.,
#                                       carddemo-prod-daily-transaction-posting)
#
# References:
#   * AAP §0.4.1 — Batch job definitions list and source-JCL mapping.
#   * AAP §0.6.3 — JCL → Step Functions + AWS Batch orchestration model.
#   * AAP §0.6.6 — PCI-DSS network segregation (private subnets, no public IP).
#   * AAP §0.7.1 — Secrets via AWS Secrets Manager; data-at-rest via KMS CMKs.
#   * AAP §0.7.2 — Required environment variables (AWS_REGION,
#                  ECS_CLUSTER_NAME, RDS_SECRET_ARN, KMS_KEY_ARN,
#                  MSK_BOOTSTRAP_SERVERS, S3_OUTPUT_BUCKET,
#                  OPENSEARCH_ENDPOINT); 2-hour SLA on batch jobs.
#   * app/jcl/POSTTRAN.jcl, INTCALC.jcl, COMBTRAN.jcl, CREASTMT.JCL,
#     TRANREPT.jcl, PRTCATBL.jcl — preserved source JCL streams.
#
# Coordination (other Terraform files in this module):
#   * variables.tf      — environment, aws_region, batch_max_vcpus,
#                         batch_job_vcpu, batch_job_memory
#   * main.tf           — local.common_tags, local.resource_name_prefix,
#                         data.aws_vpc.carddemo, data.aws_subnets.private
#   * ecr.tf            — aws_ecr_repository.carddemo (image source)
#   * iam.tf            — aws_iam_role.batch_service,
#                         aws_iam_role.batch_execution_role,
#                         aws_iam_role.batch_job_role
#   * rds.tf            — aws_db_instance.carddemo,
#                         aws_secretsmanager_secret.rds_master,
#                         aws_security_group.rds
#   * msk.tf            — aws_msk_cluster.carddemo,
#                         aws_security_group.msk
#   * elasticache.tf    — aws_elasticache_replication_group.carddemo,
#                         aws_security_group.elasticache
#   * kms.tf            — aws_kms_key.carddemo (KMS_KEY_ARN env var)
#   * s3.tf             — aws_s3_bucket.batch_outputs (S3_OUTPUT_BUCKET env var)
#   * opensearch.tf     — aws_opensearch_domain.carddemo
#   * cloudwatch.tf     — aws_cloudwatch_log_group.batch_jobs
#   * stepfunctions.tf  — state machines that submit to the queue defined here
#
# Operational notes:
#   * The container image tag is `:latest` for development. In production
#     the CI/CD pipeline (.github/workflows/docker-build.yml) pushes the
#     immutable digest and the job definition revision should be updated
#     to reference that digest instead — documented in
#     infrastructure/README.md.
#   * `retry_strategy.attempts = 1` deliberately disables AWS Batch's
#     built-in retry so that Step Functions remains the single retry
#     authority (its Catch / Retry policies model the legacy JCL
#     `COND=(...,LT)` skip-on-failure and `RESTART=` retry behaviour).
#   * `timeout.attempt_duration_seconds = 7200` enforces the 2-hour SLA
#     mandated by AAP §0.7.2 ("Batch jobs must complete within existing
#     SLA windows"). Jobs that exceed two hours are killed and reported
#     as `FAILED` so the state machine's Catch can route them to a DLQ.
###############################################################################

# =============================================================================
# Section 1 — Batch job security group + cross-SG ingress rules
# =============================================================================
# The Fargate ENIs created by AWS Batch on behalf of each running job are
# attached to this single SG. The SG itself has wide-open egress (RDS, MSK,
# ElastiCache, S3 endpoint, Secrets Manager, KMS, OpenSearch, CloudWatch
# Logs all live on different ports). The peer data-plane services (RDS,
# MSK, ElastiCache) each have their own SGs that DENY all ingress by
# default; the three `aws_security_group_rule.*_from_batch` resources
# below add precise ingress allowances that whitelist this SG as the
# permitted source on the per-service port.
#
# Cross-SG rules (rather than CIDR rules) keep the firewall surface small
# and auditable — a Fargate ENI cannot reach RDS / MSK / Redis unless its
# task is launched via AWS Batch and inherits this SG.
# =============================================================================

resource "aws_security_group" "batch_job" {
  # Resource name follows the module-wide carddemo-<env>-<purpose> convention.
  name        = "carddemo-${var.environment}-batch-job-sg"
  description = "AWS Batch job containers - outbound to RDS, MSK, ElastiCache, S3, Secrets Manager, KMS, OpenSearch (PCI-DSS private-subnet placement per AAP §0.6.6)"
  vpc_id      = data.aws_vpc.carddemo.id

  # Wide-open egress is permitted because:
  #   (a) Every downstream service (RDS, MSK, ElastiCache, S3, Secrets
  #       Manager, KMS, OpenSearch, CloudWatch Logs) is reachable only
  #       over TLS 1.2+ on its dedicated port (5432 / 9098 / 6379 / 443).
  #   (b) Each downstream's own ingress SG limits the source to this
  #       SG (RDS / MSK / ElastiCache) or to the VPC CIDR (the interface
  #       VPC endpoints for Secrets Manager / KMS / CloudWatch Logs).
  #   (c) The Fargate ENI has no public IP (assignPublicIp = DISABLED)
  #       so egress to the public internet is gated by the NAT gateway
  #       on the private route table — which itself can be restricted
  #       in production VPCs.
  egress {
    description = "All outbound (TLS to data-plane services + AWS APIs)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-job-sg"
    Purpose = "AWS Batch Fargate ENIs (Spring Batch jobs)"
  })
}

# -----------------------------------------------------------------------------
# Ingress rule on aws_security_group.rds: allow PostgreSQL traffic from the
# Batch job SG. Without this rule, Spring Batch's HikariCP connection pool
# would receive `connection timeout` errors. PostgreSQL listens on the
# canonical 5432 port (per rds.tf).
# -----------------------------------------------------------------------------
resource "aws_security_group_rule" "rds_from_batch" {
  type                     = "ingress"
  description              = "PostgreSQL from AWS Batch Fargate jobs (HikariCP connection pool)"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.batch_job.id
  security_group_id        = aws_security_group.rds.id
}

# -----------------------------------------------------------------------------
# Ingress rule on aws_security_group.msk: allow MSK IAM authentication
# traffic from the Batch job SG on the SASL_SSL+IAM port 9098. Batch jobs
# publish `transaction.posted`, `account.updated`, and `ledger.balanced`
# events to MSK (per AAP §0.6.5) — without this rule, the Spring Kafka
# producer would fail to connect.
# -----------------------------------------------------------------------------
resource "aws_security_group_rule" "msk_from_batch" {
  type                     = "ingress"
  description              = "MSK IAM SASL_SSL from AWS Batch Fargate jobs (Spring Kafka producer/consumer)"
  from_port                = 9098
  to_port                  = 9098
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.batch_job.id
  security_group_id        = aws_security_group.msk.id
}

# -----------------------------------------------------------------------------
# Ingress rule on aws_security_group.elasticache: allow Redis traffic from
# the Batch job SG on port 6379. Batch jobs use the cache-aside pattern
# (per AAP §0.7.1) for high-frequency account balance reads during the
# end-of-day processing window.
# -----------------------------------------------------------------------------
resource "aws_security_group_rule" "elasticache_from_batch" {
  type                     = "ingress"
  description              = "Redis (cache-aside) from AWS Batch Fargate jobs (Spring Boot CacheService)"
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.batch_job.id
  security_group_id        = aws_security_group.elasticache.id
}

# =============================================================================
# Section 2 — Fargate compute environment
# =============================================================================
# MANAGED + FARGATE = AWS Batch manages every container slot via AWS
# Fargate; no EC2 instance management, no Auto Scaling Group, no ECS
# instance role. Per AAP §0.1.1, the CardDemo target stack is "ECS
# Fargate behind ALB" for online workloads and "AWS Batch + Step
# Functions" for batch workloads — both use Fargate so there is no host
# fleet to patch.
#
# Sizing:
#   compute_resources.max_vcpus = var.batch_max_vcpus
#     - dev / staging: 4 (1 job concurrently at 1 vCPU per job)
#     - prod:           16 (16 jobs concurrently at 1 vCPU per job)
#   No min_vcpus / desired_vcpus block is permitted on FARGATE compute
#   environments — AWS rejects the apply if any of those scaling
#   parameters are set. Fargate scales from zero on demand.
#
# Network placement:
#   subnets            = data.aws_subnets.private.ids   (multi-AZ)
#   security_group_ids = [aws_security_group.batch_job.id]
#   PUBLIC_IP          = DISABLED (set per-job in container_properties.
#                        networkConfiguration; the compute environment
#                        does not have a top-level public-IP toggle).
#
# Lifecycle:
#   create_before_destroy = true — the compute environment cannot be
#   destroyed while a job queue still references it. With
#   create_before_destroy, Terraform creates the new (renamed)
#   compute environment, updates the job queue association, and only
#   then destroys the old compute environment.
# =============================================================================

resource "aws_batch_compute_environment" "carddemo" {
  compute_environment_name = "carddemo-${var.environment}-batch-ce"
  type                     = "MANAGED"
  state                    = "ENABLED"

  # AWS Batch service role — assumed by the AWS Batch control plane to
  # manage Fargate tasks on the operator's behalf (register / describe
  # / deregister jobs, query task status, write task state to
  # CloudWatch Events). Provisioned in iam.tf with the AWS-managed
  # `AWSBatchServiceRole` policy attached.
  service_role = aws_iam_role.batch_service.arn

  compute_resources {
    type      = "FARGATE"
    max_vcpus = var.batch_max_vcpus

    # Private subnets only — PCI-DSS network segregation requirement
    # (AAP §0.6.6). The Fargate ENIs do not get public IPs and reach
    # AWS APIs via the interface VPC endpoints provisioned in main.tf.
    subnets = data.aws_subnets.private.ids

    # The single SG attached to every Fargate ENI launched by this
    # compute environment. See Section 1 above for the egress rule
    # and the cross-SG ingress allowances.
    security_group_ids = [aws_security_group.batch_job.id]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-ce"
    Purpose = "Fargate compute environment for Spring Batch end-of-day jobs (replaces JES batch scheduling per AAP §0.6.3)"
  })

  # Cannot destroy the compute environment while a job queue still
  # references it. create_before_destroy: Terraform stands up the new
  # CE first, the operator manually flips the job queue's
  # `compute_environments` to point at the new CE (or Terraform does
  # it through the queue resource), and only then destroys the old CE.
  lifecycle {
    create_before_destroy = true
  }
}

# =============================================================================
# Section 3 — Batch job queue
# =============================================================================
# The single queue Step Functions submits jobs to. Priority 1 means it is
# the highest-priority queue across the account (lower numbers are higher
# priority in AWS Batch — opposite of CloudWatch Events / SQS / etc.).
# Only one queue is created because the CardDemo workload is a single
# sequenced pipeline; if multi-tenant or multi-priority queueing becomes
# necessary, additional queues can be added later without disrupting the
# existing one.
#
# state = ENABLED makes the queue accept and dispatch submissions
# immediately on `terraform apply`. Setting state = DISABLED would pause
# all submissions and is the recommended pre-destroy step.
# =============================================================================

resource "aws_batch_job_queue" "carddemo" {
  name     = "carddemo-${var.environment}-batch-queue"
  state    = "ENABLED"
  priority = 1

  # Single compute environment — the priority-1 Fargate CE defined
  # in Section 2. Adding additional compute_environments here would
  # define a fallback chain (jobs flow to the second CE when the
  # first is exhausted).
  compute_environments = [aws_batch_compute_environment.carddemo.arn]

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-batch-queue"
    Purpose = "Single queue submitted-to by the eod-batch-pipeline Step Functions state machine"
  })
}

# =============================================================================
# Section 4 — Common environment variable list
# =============================================================================
# Every job definition in Section 5 below injects this identical list of
# environment variables into its container. Centralizing the list in
# `locals.batch_common_environment` guarantees that:
#
#   (a) The seven mandatory env vars required by AAP §0.7.2 (AWS_REGION,
#       ECS_CLUSTER_NAME, RDS_SECRET_ARN, KMS_KEY_ARN,
#       MSK_BOOTSTRAP_SERVERS, S3_OUTPUT_BUCKET, OPENSEARCH_ENDPOINT) are
#       present in every job.
#
#   (b) The optional but operationally-useful env vars (RDS_HOST /
#       RDS_PORT / RDS_DATABASE / ELASTICACHE_HOST / ELASTICACHE_PORT /
#       SPRING_PROFILES_ACTIVE) are identically set across all jobs so
#       the Spring Boot bootstrap behaviour is reproducible whether the
#       application is running on ECS Fargate (long-running service) or
#       on AWS Batch Fargate (one-shot job).
#
#   (c) A single edit to the list propagates to all six job definitions —
#       no copy-paste drift between jobs.
#
# Important typing note:
#   AWS Batch's container_properties.environment is an array of
#   { name = string, value = string } objects. Numeric values (ports)
#   MUST be wrapped in `tostring(...)` because Terraform's HCL2 will
#   otherwise emit them as JSON numbers, which the AWS API rejects.
#
# Sensitive values:
#   NO plaintext secrets are placed here. Database password, JWT
#   signing key, MSK SCRAM secret, etc., are resolved at runtime by
#   Spring Cloud AWS via `spring.config.import=aws-secretsmanager:`
#   using the ARN env vars (RDS_SECRET_ARN) — per AAP §0.6.4 and §0.7.1.
# =============================================================================

locals {
  # Shared environment variables injected into every Batch job container.
  # The structure matches the AWS Batch container_properties.environment
  # schema (array of { name, value } objects).
  batch_common_environment = [
    # ---- Core AWS context (AAP §0.7.2 env-var contract) ----

    # AWS region — consumed by AWS SDK v2 clients (S3, MSK, Secrets
    # Manager, KMS, OpenSearch) for endpoint resolution.
    { name = "AWS_REGION", value = var.aws_region },

    # Spring Boot profile selector — matches the deployment environment
    # so application-<env>.yml is loaded on top of the base
    # application.yml at startup.
    { name = "SPRING_PROFILES_ACTIVE", value = var.environment },

    # ECS cluster name — present for parity with the ECS-Fargate service
    # environment so the Spring Boot app's startup behaviour is identical
    # regardless of launch type. The actual ECS cluster is provisioned by
    # the sibling ecs.tf using the same naming convention; this is a
    # string-level reference to avoid a hard Terraform dependency on a
    # file outside this resource's allowed dependency set.
    { name = "ECS_CLUSTER_NAME", value = "${local.resource_name_prefix}-cluster" },

    # ---- Secrets Manager ARNs (Spring Cloud AWS resolves at runtime) ----

    # RDS master credentials secret. Spring Cloud AWS Secrets Manager
    # auto-config resolves this ARN at startup; HikariCP receives the
    # JDBC URL + username + password from the secret value.
    # @RefreshScope on the DataSource bean lets rotated secrets take
    # effect WITHOUT restart (AAP §0.6.4).
    { name = "RDS_SECRET_ARN", value = aws_secretsmanager_secret.rds_master.arn },

    # ---- Encryption (AAP §0.7.1) ----

    # Customer-managed KMS CMK ARN. Used by S3OutputService (SSE-KMS on
    # PutObject) and the Secrets Manager resolution path (envelope
    # decryption of secret values).
    { name = "KMS_KEY_ARN", value = aws_kms_key.carddemo.arn },

    # ---- Event streaming (AAP §0.6.5) ----

    # Comma-separated list of MSK bootstrap brokers using the IAM
    # SASL_SSL endpoint. The Spring Kafka producer/consumer
    # configuration ingests this exact value (no extra parsing).
    { name = "MSK_BOOTSTRAP_SERVERS", value = aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam },

    # ---- Batch output (AAP §0.6.2) ----

    # S3 bucket holding all batch outputs (DALYREJS / SYSTRAN / TRANREPT
    # / STMTFILE / TRANSACT.BKUP). Versioning + lifecycle replace the
    # mainframe GDG (+1) / (0) generation semantics.
    { name = "S3_OUTPUT_BUCKET", value = aws_s3_bucket.batch_outputs.bucket },

    # ---- Audit indexing (AAP §0.6.6) ----

    # OpenSearch endpoint URL. Constructed as `https://<endpoint>` so
    # the OpenSearch REST high-level client can use the value
    # verbatim. Index target for transaction-posting audit trails,
    # CloudTrail event mirroring, and fraud-investigation queries.
    { name = "OPENSEARCH_ENDPOINT", value = "https://${aws_opensearch_domain.carddemo.endpoint}" },

    # ---- RDS connection metadata (HikariCP convenience) ----
    #
    # Provided in addition to RDS_SECRET_ARN so that operator tooling
    # (Spring Actuator /actuator/health/db endpoint, log messages on
    # connection failure) can surface the host/port/database without
    # parsing the full JDBC URL from the secret.

    { name = "RDS_HOST", value = aws_db_instance.carddemo.address },
    { name = "RDS_PORT", value = tostring(aws_db_instance.carddemo.port) },
    { name = "RDS_DATABASE", value = aws_db_instance.carddemo.db_name },

    # ---- ElastiCache connection metadata (cache-aside pattern, AAP §0.7.1) ----
    #
    # Primary endpoint + port consumed by Spring Data Redis
    # (Lettuce client) for the cache-aside read-through pattern on
    # high-frequency account balance lookups.

    { name = "ELASTICACHE_HOST", value = aws_elasticache_replication_group.carddemo.primary_endpoint_address },
    { name = "ELASTICACHE_PORT", value = tostring(aws_elasticache_replication_group.carddemo.port) },
  ]
}

# =============================================================================
# Section 5 — Batch job definitions (6)
# =============================================================================
# Each job definition is a Fargate container blueprint that AWS Batch
# instantiates one or more times when a job of the corresponding name is
# submitted. The Spring Batch jobs themselves are packaged into a single
# fat-jar built by the Maven build (target/carddemo.jar inside the
# Docker image) and selected at runtime via
# `--spring.batch.job.name=<jobName>` so AWS Batch can reuse a single
# Docker image across all six job definitions ("build once, deploy many").
#
# Shared properties across all six definitions:
#   * type                  = "container"
#   * platform_capabilities = ["FARGATE"]
#   * image                 = ECR repo + :latest tag (production replaces
#                             :latest with the immutable digest pushed
#                             by .github/workflows/docker-build.yml)
#   * jobRoleArn            = aws_iam_role.batch_job_role.arn
#                             (application runtime permissions)
#   * executionRoleArn      = aws_iam_role.batch_execution_role.arn
#                             (ECR pull + CloudWatch logs + Secrets
#                             Manager value injection)
#   * resourceRequirements  = { VCPU = var.batch_job_vcpu,
#                               MEMORY = var.batch_job_memory }
#   * fargatePlatformConfiguration.platformVersion = "LATEST"
#   * networkConfiguration.assignPublicIp = "DISABLED"  (PCI-DSS,
#                             AAP §0.6.6)
#   * environment           = local.batch_common_environment
#   * logConfiguration      = awslogs driver shipping container stdout
#                             to aws_cloudwatch_log_group.batch_jobs
#   * retry_strategy.attempts = 1   (Step Functions handles retries)
#   * timeout.attempt_duration_seconds = 7200   (2-hour SLA per AAP §0.7.2)
#   * propagate_tags = true   (ECS task inherits Batch job tags)
#
# Variables in each definition:
#   * name                  — carddemo-<env>-<job-slug>
#   * container_properties.command — selects the Spring Batch job by name
#   * logConfiguration.options.awslogs-stream-prefix — job-specific prefix
#                             so each job's stdout is grouped in a
#                             distinct CloudWatch log stream.
# =============================================================================

# -----------------------------------------------------------------------------
# 5.1 — Daily Transaction Posting
# Replaces: app/jcl/POSTTRAN.jcl (STEP15 EXEC PGM=CBTRN02C)
# Java impl: src/main/java/com/awsm2/carddemo/batch/DailyTransactionPostingJob.java
#            invokes TransactionPostingService (CBTRN01C/CBTRN02C/CBTRN03C
#            4-stage validation cascade) reading the DALYTRAN staging
#            table and writing rejects to S3 via S3OutputService.
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "daily_transaction_posting" {
  name                  = "carddemo-${var.environment}-daily-transaction-posting"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  # AWS Batch requires container_properties as a JSON document. Using
  # jsonencode(...) lets us keep the property tree as native HCL data
  # (lists / objects) while letting Terraform serialize the resulting
  # JSON at plan time. This avoids brittle heredoc string templates.
  container_properties = jsonencode({
    image            = "${aws_ecr_repository.carddemo.repository_url}:latest"
    command          = ["java", "-jar", "/app/carddemo.jar", "--spring.batch.job.name=dailyTransactionPostingJob"]
    jobRoleArn       = aws_iam_role.batch_job_role.arn
    executionRoleArn = aws_iam_role.batch_execution_role.arn

    resourceRequirements = [
      { type = "VCPU", value = tostring(var.batch_job_vcpu) },
      { type = "MEMORY", value = tostring(var.batch_job_memory) },
    ]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    environment = local.batch_common_environment

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "daily-transaction-posting"
      }
    }
  })

  # AWS Batch retry policy is intentionally disabled (attempts = 1) so
  # the Step Functions Catch / Retry policy is the single source of
  # retry truth. This models the legacy JCL `COND=(...,LT)` skip-
  # on-failure semantic at the orchestrator layer rather than at the
  # job-definition layer.
  retry_strategy {
    attempts = 1
  }

  # 2-hour SLA per AAP §0.7.2 ("Batch jobs must complete within
  # existing SLA windows"). Jobs exceeding 2 hours are killed and
  # reported as FAILED — the Step Functions Catch routes them to a
  # DLQ topic for operator follow-up.
  timeout {
    attempt_duration_seconds = 7200
  }

  # Propagate the job definition's tags onto the ECS task that AWS
  # Batch launches under the hood. Combined with the CloudTrail event
  # captured on submission, this lets operators trace every task back
  # to its Step Functions execution + parent job submission.
  propagate_tags = true

  tags = merge(local.common_tags, {
    Name        = "carddemo-${var.environment}-daily-transaction-posting"
    Purpose     = "Spring Batch DailyTransactionPostingJob (replaces app/jcl/POSTTRAN.jcl)"
    SourceJCL   = "POSTTRAN.jcl"
    SourceCOBOL = "CBTRN02C"
  })
}

# -----------------------------------------------------------------------------
# 5.2 — Interest Calculation
# Replaces: app/jcl/INTCALC.jcl (STEP15 EXEC PGM=CBACT04C,PARM='2022071800')
# Java impl: src/main/java/com/awsm2/carddemo/batch/InterestCalculationJob.java
#            invokes InterestCalculationService which performs the COBOL
#            arithmetic `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` literally
#            using BigDecimal.HALF_EVEN per AAP §0.6.1 (no algebraic
#            simplification, divisor preserved as BigDecimal.valueOf(1200)).
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "interest_calculation" {
  name                  = "carddemo-${var.environment}-interest-calculation"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image            = "${aws_ecr_repository.carddemo.repository_url}:latest"
    command          = ["java", "-jar", "/app/carddemo.jar", "--spring.batch.job.name=interestCalculationJob"]
    jobRoleArn       = aws_iam_role.batch_job_role.arn
    executionRoleArn = aws_iam_role.batch_execution_role.arn

    resourceRequirements = [
      { type = "VCPU", value = tostring(var.batch_job_vcpu) },
      { type = "MEMORY", value = tostring(var.batch_job_memory) },
    ]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    environment = local.batch_common_environment

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "interest-calculation"
      }
    }
  })

  retry_strategy {
    attempts = 1
  }

  timeout {
    attempt_duration_seconds = 7200
  }

  propagate_tags = true

  tags = merge(local.common_tags, {
    Name        = "carddemo-${var.environment}-interest-calculation"
    Purpose     = "Spring Batch InterestCalculationJob (replaces app/jcl/INTCALC.jcl)"
    SourceJCL   = "INTCALC.jcl"
    SourceCOBOL = "CBACT04C"
  })
}

# -----------------------------------------------------------------------------
# 5.3 — Combine Transactions
# Replaces: app/jcl/COMBTRAN.jcl (STEP05R EXEC PGM=SORT;
#                                 STEP10 EXEC PGM=IDCAMS REPRO)
# Java impl: src/main/java/com/awsm2/carddemo/batch/CombineTransactionsJob.java
#            implements Java Comparator-based sort + bulk JPA insert.
#            Pure utility step (no COBOL source); replaces the legacy
#            DFSORT + IDCAMS REPRO chain that sorted the daily
#            TRANSACT.BKUP + SYSTRAN files by TRAN-ID and bulk-loaded
#            them into the TRANSACT VSAM cluster.
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "combine_transactions" {
  name                  = "carddemo-${var.environment}-combine-transactions"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image            = "${aws_ecr_repository.carddemo.repository_url}:latest"
    command          = ["java", "-jar", "/app/carddemo.jar", "--spring.batch.job.name=combineTransactionsJob"]
    jobRoleArn       = aws_iam_role.batch_job_role.arn
    executionRoleArn = aws_iam_role.batch_execution_role.arn

    resourceRequirements = [
      { type = "VCPU", value = tostring(var.batch_job_vcpu) },
      { type = "MEMORY", value = tostring(var.batch_job_memory) },
    ]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    environment = local.batch_common_environment

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "combine-transactions"
      }
    }
  })

  retry_strategy {
    attempts = 1
  }

  timeout {
    attempt_duration_seconds = 7200
  }

  propagate_tags = true

  tags = merge(local.common_tags, {
    Name      = "carddemo-${var.environment}-combine-transactions"
    Purpose   = "Spring Batch CombineTransactionsJob (replaces app/jcl/COMBTRAN.jcl - DFSORT + IDCAMS REPRO)"
    SourceJCL = "COMBTRAN.jcl"
  })
}

# -----------------------------------------------------------------------------
# 5.4 — Statement Generation
# Replaces: app/jcl/CREASTMT.JCL (STEP040 EXEC PGM=CBSTM03A — Template
#                                  Method covers CBSTM03B as text/HTML
#                                  variant subroutine)
# Java impl: src/main/java/com/awsm2/carddemo/batch/StatementGenerationJob.java
#            invokes StatementGenerationService which emits BOTH text
#            (STMTFILE) and HTML (HTMLFILE) outputs to S3 — Template
#            Method pattern preserves the dual-format output of
#            CBSTM03A.CBL without logic duplication (AAP §0.3.3).
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "statement_generation" {
  name                  = "carddemo-${var.environment}-statement-generation"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image            = "${aws_ecr_repository.carddemo.repository_url}:latest"
    command          = ["java", "-jar", "/app/carddemo.jar", "--spring.batch.job.name=statementGenerationJob"]
    jobRoleArn       = aws_iam_role.batch_job_role.arn
    executionRoleArn = aws_iam_role.batch_execution_role.arn

    resourceRequirements = [
      { type = "VCPU", value = tostring(var.batch_job_vcpu) },
      { type = "MEMORY", value = tostring(var.batch_job_memory) },
    ]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    environment = local.batch_common_environment

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "statement-generation"
      }
    }
  })

  retry_strategy {
    attempts = 1
  }

  timeout {
    attempt_duration_seconds = 7200
  }

  propagate_tags = true

  tags = merge(local.common_tags, {
    Name        = "carddemo-${var.environment}-statement-generation"
    Purpose     = "Spring Batch StatementGenerationJob (replaces app/jcl/CREASTMT.JCL)"
    SourceJCL   = "CREASTMT.JCL"
    SourceCOBOL = "CBSTM03A,CBSTM03B"
  })
}

# -----------------------------------------------------------------------------
# 5.5 — Transaction Report
# Replaces: app/jcl/TRANREPT.jcl (STEP10R EXEC PGM=CBTRN03C with
#                                  date-window filter on TRAN-PROC-DT)
# Java impl: src/main/java/com/awsm2/carddemo/batch/TransactionReportJob.java
#            invokes TransactionReportService which applies the
#            date-window filter and joins Transaction × TransactionType
#            × TransactionCategory × CardCrossReference to produce the
#            TRANREPT output to S3 (replaces the GDG TRANREPT(+1)
#            dataset per AAP §0.6.2).
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "transaction_report" {
  name                  = "carddemo-${var.environment}-transaction-report"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image            = "${aws_ecr_repository.carddemo.repository_url}:latest"
    command          = ["java", "-jar", "/app/carddemo.jar", "--spring.batch.job.name=transactionReportJob"]
    jobRoleArn       = aws_iam_role.batch_job_role.arn
    executionRoleArn = aws_iam_role.batch_execution_role.arn

    resourceRequirements = [
      { type = "VCPU", value = tostring(var.batch_job_vcpu) },
      { type = "MEMORY", value = tostring(var.batch_job_memory) },
    ]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    environment = local.batch_common_environment

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "transaction-report"
      }
    }
  })

  retry_strategy {
    attempts = 1
  }

  timeout {
    attempt_duration_seconds = 7200
  }

  propagate_tags = true

  tags = merge(local.common_tags, {
    Name        = "carddemo-${var.environment}-transaction-report"
    Purpose     = "Spring Batch TransactionReportJob (replaces app/jcl/TRANREPT.jcl)"
    SourceJCL   = "TRANREPT.jcl"
    SourceCOBOL = "CBTRN03C"
  })
}

# -----------------------------------------------------------------------------
# 5.6 — Print Category Balance
# Replaces: app/jcl/PRTCATBL.jcl (STEP10R EXEC PGM=SORT — pure utility,
#                                  no COBOL program; sorts TCATBALF by
#                                  ACCT-ID + TYPE-CD + CD and emits a
#                                  formatted dump of the
#                                  TransactionCategoryBalance file).
# Java impl: src/main/java/com/awsm2/carddemo/batch/PrintCategoryBalanceJob.java
#            implements the same sort + emit using a Java Comparator
#            chain on (acctId, typeCd, categoryCd) followed by an
#            EDIT-style numeric format on TRAN-CAT-BAL. Output target
#            is the S3 batch outputs bucket (replaces the
#            TCATBALF.REPT sequential dataset).
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "print_category_balance" {
  name                  = "carddemo-${var.environment}-print-category-balance"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image            = "${aws_ecr_repository.carddemo.repository_url}:latest"
    command          = ["java", "-jar", "/app/carddemo.jar", "--spring.batch.job.name=printCategoryBalanceJob"]
    jobRoleArn       = aws_iam_role.batch_job_role.arn
    executionRoleArn = aws_iam_role.batch_execution_role.arn

    resourceRequirements = [
      { type = "VCPU", value = tostring(var.batch_job_vcpu) },
      { type = "MEMORY", value = tostring(var.batch_job_memory) },
    ]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    environment = local.batch_common_environment

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.batch_jobs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "print-category-balance"
      }
    }
  })

  retry_strategy {
    attempts = 1
  }

  timeout {
    attempt_duration_seconds = 7200
  }

  propagate_tags = true

  tags = merge(local.common_tags, {
    Name      = "carddemo-${var.environment}-print-category-balance"
    Purpose   = "Spring Batch PrintCategoryBalanceJob (replaces app/jcl/PRTCATBL.jcl - DFSORT + report)"
    SourceJCL = "PRTCATBL.jcl"
  })
}
