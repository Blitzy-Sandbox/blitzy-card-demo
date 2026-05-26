###############################################################################
# infrastructure/terraform/stepfunctions.tf
#
# AWS Step Functions state machines that orchestrate the CardDemo batch
# pipelines, replacing the legacy z/OS JCL job streams per AAP §0.6.3 and
# §0.4.1 (JCL → Step Functions Orchestration).
#
# Purpose:
#   Provision the two Standard Step Functions state machines (and the
#   optional EventBridge Scheduler trigger) that act as the cloud-native
#   replacement for the original CardDemo JES/JCL batch orchestration:
#
#     1. `carddemo-${env}-eod-batch-pipeline`
#        End-of-Day (EOD) business-processing pipeline. The state machine
#        chains:
#            PostTransactions → CalculateInterest → CombineTransactions
#                              → Parallel { CreateStatements, RunTransactionReports }
#        Each Task state invokes
#        `arn:aws:states:::batch:submitJob.sync` against the AWS Batch
#        queue defined in `batch.tf`, naming one of the six Spring Batch
#        job definitions. The `.sync` integration blocks the state
#        machine until the Batch job reaches a terminal state, so the
#        state machine's Catch/Retry policies fully model the legacy
#        JCL `COND=` and `IF ... THEN` semantics. Replaces:
#            app/jcl/POSTTRAN.jcl   → daily_transaction_posting
#            app/jcl/INTCALC.jcl    → interest_calculation
#            app/jcl/COMBTRAN.jcl   → combine_transactions
#            app/jcl/CREASTMT.JCL   → statement_generation
#            app/jcl/TRANREPT.jcl   → transaction_report
#            app/jcl/PRTCATBL.jcl   → print_category_balance (defined in
#                                     batch.tf but not yet wired into the
#                                     EOD pipeline; reserved for ad-hoc
#                                     reporting executions).
#            app/jcl/TRANBKP.jcl    → No Batch job definition. The
#                                     foundation-checkpoint scope of the
#                                     eod-batch-pipeline ASL JSON
#                                     deliberately excludes the TRANBKP
#                                     application backup step because
#                                     transaction snapshots are now
#                                     provided by RDS automated backups +
#                                     point-in-time recovery (AAP §0.6.2).
#                                     A predicted ARN is supplied for the
#                                     placeholder reference inside the
#                                     ASL comment block to satisfy
#                                     `templatefile()`'s requirement that
#                                     every referenced variable be
#                                     provided.
#
#     2. `carddemo-${env}-file-provisioning`
#        Data-store provisioning workflow. Runs Flyway V001-V017 schema
#        migrations + parallel Glue Spark bulk-load of ASCII fixture
#        data + post-load row-count validation. Replaces the JCL chain:
#            app/jcl/ACCTFILE.jcl   → IDCAMS DELETE/DEFINE CLUSTER +
#                                     REPRO from PS to VSAM
#            app/jcl/CARDFILE.jcl   → IDCAMS + AIX/PATH/BLDINDEX
#            app/jcl/CUSTFILE.jcl   → IDCAMS
#            app/jcl/XREFFILE.jcl   → IDCAMS + AIX/PATH/BLDINDEX
#            app/jcl/TRANFILE.jcl   → IDCAMS + AIX/PATH/BLDINDEX
#            app/jcl/DISCGRP.jcl    → IDCAMS + REPRO (51 disclosure rules)
#            app/jcl/TCATBALF.jcl   → IDCAMS
#            app/jcl/TRANTYPE.jcl   → IDCAMS + REPRO (7 transaction types)
#            app/jcl/TRANCATG.jcl   → IDCAMS + REPRO (18 categories)
#            app/jcl/DUSRSECJ.jcl   → IEBGENER seed + IDCAMS + REPRO
#            app/jcl/DEFCUST.jcl    → (alternative customer cluster
#                                     define, NOT propagated per AAP
#                                     §0.7.3 Minimal Change Clause)
#
#     3. `carddemo-${env}-eod-batch-daily` (conditional, count-gated)
#        EventBridge Scheduler resource that triggers the EOD pipeline
#        state machine on a daily 23:00 UTC cron schedule. Gated by
#        `var.eod_scheduled_enabled` so that dev / staging environments
#        do not generate noisy nightly executions. Replaces the legacy
#        z/OS scheduler entry that invoked the JCL job stream at the
#        same time of day.
#
# ASL JSON sourcing:
#   The two ASL JSON definitions are stored alongside the application
#   source at `src/main/resources/stepfunctions/*.asl.json` so they ship
#   with the Spring Boot artifact (the `StepFunctionsOrchestrator`
#   adapter loads them at runtime when starting executions). They are
#   loaded into Terraform at apply time via `templatefile(...)` so the
#   real AWS Batch job queue / job definition ARNs (computed by
#   `batch.tf` at apply time) are substituted into the `${VAR}`
#   placeholders in the JSON. The same JSON file is therefore the single
#   source of truth for the state machine definition; Terraform performs
#   no transformation other than literal placeholder substitution.
#
# Path resolution:
#   `${path.module}` resolves to `infrastructure/terraform/` (the module
#   root). The two ASL JSON files live at
#   `src/main/resources/stepfunctions/*.asl.json` relative to the
#   repository root, which is two levels up from `${path.module}` —
#   hence the `${path.module}/../../src/main/resources/stepfunctions/...`
#   form below.
#
# IAM:
#   Both state machines run under `aws_iam_role.step_functions_execution`
#   (defined in `iam.tf`), which grants the minimum permissions required
#   by the Task states:
#     * batch:SubmitJob, batch:DescribeJobs, batch:TerminateJob
#       (Batch task integration)
#     * glue:StartJobRun, glue:GetJobRun (Glue task integration)
#     * events:PutRule, events:PutTargets (managed rule that captures
#       Batch job state changes for the `.sync` integration)
#     * logs:CreateLogDelivery, logs:GetLogDelivery, logs:UpdateLogDelivery,
#       logs:DeleteLogDelivery, logs:ListLogDeliveries,
#       logs:PutResourcePolicy, logs:DescribeResourcePolicies,
#       logs:DescribeLogGroups (Vended Log delivery for state machine
#       execution logging)
#     * xray:PutTraceSegments, xray:PutTelemetryRecords,
#       xray:GetSamplingRules, xray:GetSamplingTargets (X-Ray tracing
#       integration)
#     * sns:Publish (FailureNotification Task in both state machines)
#     * cloudwatch:PutMetricData (success/failure custom metrics)
#
# Observability (per AAP §0.6.6 — Audit, Observability, and PCI-DSS):
#   * Execution event logging to the dedicated CloudWatch Logs group
#     `/aws/stepfunctions/carddemo-${env}` (defined in `cloudwatch.tf`),
#     KMS-encrypted with the CardDemo CMK. Log level is controlled by
#     `var.stepfunctions_logging_enabled` — `ALL` events when true (full
#     audit trail) and `ERROR` only when false (lower-cost / lower-noise
#     setting for dev). `include_execution_data = false` ensures that
#     the input/output payloads of each state transition are NOT logged
#     verbatim — PCI-DSS forbids logging cardholder data in operational
#     logs (sanitised audit records flow separately to OpenSearch via
#     the `AuditLogService` adapter).
#   * AWS X-Ray tracing enabled on both state machines so that an end-
#     to-end trace (HTTP ingress → Spring Boot → MSK → Batch job →
#     Glue job → RDS) can be reconstructed for any execution.
#
# Resource naming:
#   Follows the module-wide `carddemo-${var.environment}-<purpose>`
#   convention (see `main.tf` Section 5).
#
# Tagging:
#   Every resource carries `local.common_tags` merged with a
#   per-resource `Name` and `Purpose` tag for console clarity. The four
#   mandatory keys (Project / Environment / Owner / ManagedBy) are
#   also applied automatically by the `provider "aws" { default_tags }`
#   block in `main.tf` — `merge()` here just guarantees the per-resource
#   `Name` and `Purpose` keys are present.
#
# References:
#   * AAP §0.4.1 — Transformation Mapping (JCL → ASL state machine list).
#   * AAP §0.6.3 — JCL → Step Functions Orchestration.
#   * AAP §0.6.6 — Cross-Cutting Audit, Observability, and PCI-DSS
#                  (encryption, audit logging, X-Ray tracing).
#   * AAP §0.7.1 — Externalised configuration; tagging discipline.
#   * src/main/resources/stepfunctions/eod-batch-pipeline.asl.json —
#     EOD pipeline definition.
#   * src/main/resources/stepfunctions/file-provisioning.asl.json —
#     File-provisioning workflow definition.
#   * infrastructure/terraform/batch.tf — Batch job queue + 6 job
#     definitions invoked by the EOD pipeline Task states.
#   * infrastructure/terraform/glue.tf — Glue jobs invoked dynamically
#     by the file-provisioning Map state via `$.loadJob.glueJobName`.
#   * infrastructure/terraform/iam.tf — execution roles.
#   * infrastructure/terraform/cloudwatch.tf — execution log group.
#   * app/jcl/POSTTRAN.jcl, INTCALC.jcl, COMBTRAN.jcl, CREASTMT.JCL,
#     TRANREPT.jcl, PRTCATBL.jcl — source JCL members preserved as
#     reference (REFERENCE-only; never modified).
###############################################################################

# =============================================================================
# Section 1 — Local values
# =============================================================================
# Predicted ARNs for AWS Batch job definitions that are referenced by the
# ASL JSON placeholders but are not (yet) provisioned in `batch.tf`. Each
# follows the standard `carddemo-${env}-<job-name>` naming convention used
# throughout this Terraform module so that, if and when the corresponding
# job definitions are added in a subsequent checkpoint, the predicted ARN
# will resolve to the real resource without any change to this file or to
# the ASL JSON.
#
# The placeholders below cover:
#
#   * `tranbkp_predicted_arn` — referenced ONLY by the documentation
#     `Comment` block at the top of `eod-batch-pipeline.asl.json`, which
#     explains why the TRANBKP backup step is deliberately excluded from
#     the foundation-checkpoint pipeline (RDS automated backups + PITR
#     replace the application-level backup per AAP §0.6.2). The variable
#     must still be supplied to `templatefile()` because the function
#     errors when a `${VAR}` reference in the template has no matching
#     entry in the `vars` map, regardless of whether the reference is in
#     a Comment field or in an executable State definition.
#
#   * `flyway_migrate` and `validate_rowcounts` — referenced by Task
#     states in `file-provisioning.asl.json` that execute Flyway schema
#     migrations and post-load row-count validation respectively. These
#     two Batch job definitions are NOW provisioned in `batch.tf`
#     Sections 5.7 and 5.8 (per the CP7 remediation that closed the
#     review gap "Terraform substitutes predicted local ARNs for
#     job definitions that do not exist in batch.tf"). The substitution
#     map below therefore references the actual Terraform resource
#     ARNs instead of constructed predicted strings — guaranteeing the
#     ASL JSON is wired to live resources at every apply.
#
# Centralising the predicted ARNs in `locals` keeps the two state
# machine resources below readable and ensures the same convention is
# applied uniformly.
# =============================================================================

locals {
  # Predicted Batch job definition ARN for the (currently absent) TRANBKP
  # backup job. Referenced only by the Comment block in
  # eod-batch-pipeline.asl.json.
  stepfunctions_tranbkp_predicted_arn = "arn:${local.partition}:batch:${var.aws_region}:${local.account_id}:job-definition/carddemo-${var.environment}-transaction-backup"

  # Predicted S3 bucket name for the seed-data fixtures that the
  # file-provisioning state machine's BulkLoadFactData Map state reads
  # from. Follows the `carddemo-${env}-batch-outputs-${account_id}`
  # naming convention used by `s3.tf` (the `batch_outputs` bucket
  # serves a dual purpose: it hosts both the GDG-replacement output
  # objects and the staged ASCII fixtures under `fixtures/ascii/`).
  # This is referenced only by Comment fields in the file-provisioning
  # ASL JSON (the per-execution source-S3 URIs are supplied dynamically
  # via the execution input by `StepFunctionsOrchestrator`), but the
  # variable must still be provided to `templatefile()` because every
  # `${VAR}` placeholder in the template — including those inside
  # Comment fields — must have a matching `vars` entry.
  stepfunctions_seed_data_bucket_predicted_name = "carddemo-${var.environment}-batch-outputs-${local.account_id}"
}

# =============================================================================
# Section 2 — End-of-Day (EOD) batch pipeline state machine
# =============================================================================
# Replaces the JCL business-processing chain
#     POSTTRAN → INTCALC → COMBTRAN → { CREASTMT | TRANREPT }
# with a `STANDARD` (long-running, durable, at-most-once execution)
# Step Functions state machine. `STANDARD` rather than `EXPRESS` because:
#
#   * EXPRESS state machines have a hard 5-minute execution limit; the
#     CardDemo EOD batch can take hours (POSTTRAN alone may process
#     millions of transactions on a high-volume day).
#   * STANDARD provides exactly-once execution semantics, full execution
#     history retention (90 days), per-state IAM-condition support, and
#     `.sync` Task integrations (required for the AWS Batch
#     `batch:submitJob.sync` calls used by every Task state).
#
# The `definition` attribute is produced by `templatefile(...)`, which
# loads the ASL JSON from `src/main/resources/stepfunctions/` and
# substitutes the `${VAR}` placeholders with the real Batch job queue
# and job definition ARNs computed by `batch.tf` at apply time. The
# substitution happens entirely in Terraform — Step Functions itself
# sees a fully-resolved ASL document with no template variables.
# =============================================================================

resource "aws_sfn_state_machine" "eod_batch_pipeline" {
  # ---------------------------------------------------------------------------
  # State machine name — follows the module-wide `carddemo-${env}-...`
  # convention. Referenced by:
  #   * `iam.tf` Section 10 (eventbridge_scheduler runtime policy) — the
  #     EventBridge Scheduler is granted `states:StartExecution` only
  #     against this exact ARN, so the predicted ARN constructed in
  #     `iam.tf` must match the name produced here.
  #   * `outputs.tf` (planned export `eod_state_machine_arn`) — exposed
  #     as an ECS task env var so the Spring Boot
  #     `StepFunctionsOrchestrator` adapter can start executions.
  # ---------------------------------------------------------------------------
  name = "carddemo-${var.environment}-eod-batch-pipeline"

  # ---------------------------------------------------------------------------
  # IAM execution role — `aws_iam_role.step_functions_execution` from
  # `iam.tf` Section 8. Grants batch:SubmitJob, glue:StartJobRun,
  # logs:CreateLogDelivery, xray:PutTraceSegments, sns:Publish, and
  # cloudwatch:PutMetricData against CardDemo-owned resources only.
  # ---------------------------------------------------------------------------
  role_arn = aws_iam_role.step_functions_execution.arn

  # ---------------------------------------------------------------------------
  # `STANDARD` for long-running, durable batch executions. See the
  # rationale above the resource block. EXPRESS is reserved for
  # high-throughput sub-5-minute workflows such as the REST request
  # workflow short-circuits described in AAP §0.6.5 — but those are
  # outside this state machine's scope.
  # ---------------------------------------------------------------------------
  type = "STANDARD"

  # ---------------------------------------------------------------------------
  # ASL JSON definition. `templatefile()` reads the file at apply time
  # and substitutes the `${VAR}` placeholders with the values from the
  # second argument. Every variable referenced by the ASL JSON MUST
  # appear in the `vars` map (otherwise `templatefile()` errors with
  # "vars map does not contain key VAR"); conversely, every key in the
  # `vars` map MUST be referenced by the ASL JSON (otherwise
  # `templatefile()` errors with "vars map contains unused key VAR").
  #
  # The list below is therefore the exact set of placeholders that
  # appear in `eod-batch-pipeline.asl.json`. Adding a new Task state
  # that references a new `${X_JOB_DEFINITION_ARN}` placeholder requires
  # adding the corresponding entry here in lockstep.
  #
  # Variable-to-resource mapping (per AAP §0.4.1):
  #   BATCH_JOB_QUEUE_ARN        ← aws_batch_job_queue.carddemo
  #   POSTTRAN_JOB_DEFINITION_ARN ← aws_batch_job_definition.daily_transaction_posting
  #     (replaces app/jcl/POSTTRAN.jcl STEP15 EXEC PGM=CBTRN02C)
  #   INTCALC_JOB_DEFINITION_ARN  ← aws_batch_job_definition.interest_calculation
  #     (replaces app/jcl/INTCALC.jcl STEP10 EXEC PGM=CBACT04C, PARM='2022071800')
  #   COMBTRAN_JOB_DEFINITION_ARN ← aws_batch_job_definition.combine_transactions
  #     (replaces app/jcl/COMBTRAN.jcl PGM=SORT + IDCAMS REPRO)
  #   CREASTMT_JOB_DEFINITION_ARN ← aws_batch_job_definition.statement_generation
  #     (replaces app/jcl/CREASTMT.JCL PGM=CBSTM03A text + HTML statements)
  #   TRANREPT_JOB_DEFINITION_ARN ← aws_batch_job_definition.transaction_report
  #     (replaces app/jcl/TRANREPT.jcl PGM=CBTRN03C date-filtered report)
  #   TRANBKP_JOB_DEFINITION_ARN  ← local.stepfunctions_tranbkp_predicted_arn
  #     (comment-only reference; see Section 1 above).
  # ---------------------------------------------------------------------------
  definition = templatefile(
    "${path.module}/../../src/main/resources/stepfunctions/eod-batch-pipeline.asl.json",
    {
      BATCH_JOB_QUEUE_ARN         = aws_batch_job_queue.carddemo.arn
      POSTTRAN_JOB_DEFINITION_ARN = aws_batch_job_definition.daily_transaction_posting.arn
      INTCALC_JOB_DEFINITION_ARN  = aws_batch_job_definition.interest_calculation.arn
      COMBTRAN_JOB_DEFINITION_ARN = aws_batch_job_definition.combine_transactions.arn
      CREASTMT_JOB_DEFINITION_ARN = aws_batch_job_definition.statement_generation.arn
      TRANREPT_JOB_DEFINITION_ARN = aws_batch_job_definition.transaction_report.arn
      TRANBKP_JOB_DEFINITION_ARN  = local.stepfunctions_tranbkp_predicted_arn
    }
  )

  # ---------------------------------------------------------------------------
  # Execution event logging — per AAP §0.6.6 (audit trail) and the
  # PCI-DSS audit requirement that every state transition be captured.
  #
  # `log_destination` points at the dedicated CloudWatch Logs group
  # provisioned in `cloudwatch.tf` Section 1.3 — KMS-encrypted with the
  # CardDemo CMK, retention controlled by `var.cloudwatch_log_retention_days`.
  # The `:*` suffix on the ARN signals "all log streams" — Step Functions
  # creates one log stream per execution.
  #
  # `include_execution_data = false` prevents the input/output JSON of
  # each Task state from being written to the log group. PCI-DSS forbids
  # logging cardholder data (PAN, expiry, CVV) verbatim in operational
  # logs; while the CardDemo execution inputs are not expected to
  # contain raw PAN, defence-in-depth means we disable the verbose
  # payload logging unconditionally. Sanitised audit records that DO
  # need to be retained for fraud investigation are written separately
  # to OpenSearch via the `AuditLogService` adapter (per AAP §0.6.6),
  # which performs the PCI-DSS-mandated tokenisation before write.
  #
  # `level = "ALL"` when `var.stepfunctions_logging_enabled` is true:
  # every state transition (ExecutionStarted, TaskStarted, TaskSucceeded,
  # TaskFailed, ChoiceStateExited, ParallelStateStarted, ...) is
  # captured. `level = "ERROR"` reduces ingestion cost in dev / staging
  # by emitting only failure events; production environments MUST run
  # with ALL-level logging to satisfy the AAP §0.6.6 audit requirement.
  # ---------------------------------------------------------------------------
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.step_functions.arn}:*"
    include_execution_data = false
    level                  = var.stepfunctions_logging_enabled ? "ALL" : "ERROR"
  }

  # ---------------------------------------------------------------------------
  # AWS X-Ray tracing — emits one trace segment per Task state and
  # propagates the tracing header to downstream AWS Batch jobs, Glue
  # jobs, and Lambda invocations. The Spring Boot Batch jars
  # (`InterestCalculationJob`, `DailyTransactionPostingJob`, etc.) pick
  # up the X-Ray header via the AWS SDK's automatic instrumentation, so
  # an end-to-end trace (Step Functions → Batch container → JPA writes
  # → MSK publish → OpenSearch index) can be reconstructed in the X-Ray
  # console for any execution.
  # ---------------------------------------------------------------------------
  tracing_configuration {
    enabled = true
  }

  # ---------------------------------------------------------------------------
  # Tags. `local.common_tags` (defined in `main.tf` Section 5) supplies
  # the mandatory four-key set (Project / Environment / Owner /
  # ManagedBy). The `merge()` here adds a per-resource `Name` and a
  # `Purpose` tag describing the state machine's responsibility — used
  # by Cost Explorer drill-downs and incident-response runbooks.
  # ---------------------------------------------------------------------------
  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-eod-batch-pipeline"
    Purpose = "EOD batch pipeline (replaces JCL POSTTRAN -> INTCALC -> COMBTRAN -> {CREASTMT|TRANREPT} per AAP §0.6.3)"
  })
}

# =============================================================================
# Section 3 — File-provisioning state machine
# =============================================================================
# Replaces the JCL data-store provisioning chain
#     ACCTFILE → CARDFILE → XREFFILE → CUSTFILE → DISCGRP → TCATBALF →
#     TRANTYPE → TRANCATG → TRANFILE → DUSRSECJ
# with a one-shot / on-demand Step Functions state machine that:
#
#   1. Applies Flyway V001-V017 schema migrations to the RDS PostgreSQL
#      instance via a Spring Boot Batch job (`flyway_migrate` — predicted
#      ARN; see Section 1).
#   2. Fans out 5 parallel AWS Glue Spark jobs (one per fact table:
#      Account, Customer, Card, CardCrossReference, TransactionCategoryBalance)
#      to bulk-load the ASCII fixtures from S3 to RDS via JDBC.
#      Parallelisation is safe because the target tables have no
#      foreign-key constraints enforced at load time (cross-references
#      are resolved at query time).
#   3. Issues SELECT COUNT(*) against each loaded table via a row-count
#      validation Batch job (`validate_rowcounts` — predicted ARN; see
#      Section 1) to confirm the load succeeded before allowing the EOD
#      batch pipeline to start.
#
# Same `STANDARD` type, `templatefile()`-driven definition, CloudWatch
# Logs destination, and X-Ray configuration as the EOD pipeline; the
# state machine differs only in the ASL JSON it consumes and in the
# `${VAR}` placeholders it substitutes.
# =============================================================================

resource "aws_sfn_state_machine" "file_provisioning" {
  # ---------------------------------------------------------------------------
  # State machine name — follows the module-wide convention. Referenced
  # by `outputs.tf` (planned export `file_provisioning_state_machine_arn`)
  # and by the Spring Boot `StepFunctionsOrchestrator.startFileProvisioning(...)`
  # adapter when an operator triggers the workflow from the admin REST
  # endpoint or from a CI/CD seed-data refresh action.
  # ---------------------------------------------------------------------------
  name = "carddemo-${var.environment}-file-provisioning"

  # ---------------------------------------------------------------------------
  # Shares the same execution role as the EOD pipeline. The role's
  # runtime policy in `iam.tf` Section 8 grants permissions to BOTH
  # Batch and Glue task integrations — file-provisioning needs both
  # (Batch for Flyway + row-count validation; Glue for ASCII bulk-load).
  # ---------------------------------------------------------------------------
  role_arn = aws_iam_role.step_functions_execution.arn

  # ---------------------------------------------------------------------------
  # STANDARD — provisioning is a long-running workflow (Flyway alone
  # can take 10+ minutes on a fresh cluster; the parallel Glue load
  # step adds another 15-30 minutes). EXPRESS would time out.
  # ---------------------------------------------------------------------------
  type = "STANDARD"

  # ---------------------------------------------------------------------------
  # ASL JSON definition. The file-provisioning ASL references four
  # `${VAR}` placeholders:
  #
  #   BATCH_JOB_QUEUE_ARN
  #     ← aws_batch_job_queue.carddemo.arn — same queue as the EOD
  #       pipeline; the ApplyFlywayMigrations and ValidateRowCounts
  #       Task states submit jobs here.
  #
  #   FLYWAY_MIGRATE_JOB_DEFINITION_ARN
  #     ← aws_batch_job_definition.flyway_migrate.arn — provisioned in
  #       `batch.tf` Section 5.7 (CP7 remediation that closed the
  #       review gap "Terraform substitutes predicted local ARNs for
  #       job definitions that do not exist in batch.tf"). Runs the
  #       Spring Boot Flyway-only entry point applying V001..V017
  #       migrations to RDS PostgreSQL.
  #
  #   VALIDATE_ROWCOUNTS_JOB_DEFINITION_ARN
  #     ← aws_batch_job_definition.validate_rowcounts.arn —
  #       provisioned in `batch.tf` Section 5.8 (CP7 remediation).
  #       Runs the Spring Boot row-count validator that verifies the
  #       expected row counts after Flyway + Glue have loaded the
  #       reference and fact data.
  #
  #   S3_SEED_DATA_BUCKET
  #     ← local.stepfunctions_seed_data_bucket_predicted_name —
  #       predicted name of the S3 bucket that hosts the ASCII seed
  #       fixtures (s3://${S3_SEED_DATA_BUCKET}/<table>.ps). Only
  #       referenced in Comment fields of the ASL JSON (the per-item
  #       Glue job source URIs are supplied at execution time, not at
  #       template time), but `templatefile()` still requires the
  #       variable to be defined because every `${VAR}` reference in
  #       the template — including those in Comment fields — must
  #       have a matching entry in the vars map.
  #
  # Note that the per-table Glue job names are NOT substituted by
  # `templatefile()` for this state machine: the BulkLoadFactData Map
  # state reads them dynamically from the execution input
  # ($.bulkLoadJobs[].glueJobName), populated by
  # `StepFunctionsOrchestrator.startFileProvisioning(...)` at start
  # time. The Glue job NAMES (not ARNs) are read from the corresponding
  # `aws_glue_job.ascii_to_rds_*` resources in `glue.tf` and passed in
  # the execution input — keeping the per-table fan-out flexible
  # without requiring the ASL JSON to enumerate every table at template
  # time.
  # ---------------------------------------------------------------------------
  definition = templatefile(
    "${path.module}/../../src/main/resources/stepfunctions/file-provisioning.asl.json",
    {
      BATCH_JOB_QUEUE_ARN                   = aws_batch_job_queue.carddemo.arn
      FLYWAY_MIGRATE_JOB_DEFINITION_ARN     = aws_batch_job_definition.flyway_migrate.arn
      VALIDATE_ROWCOUNTS_JOB_DEFINITION_ARN = aws_batch_job_definition.validate_rowcounts.arn
      S3_SEED_DATA_BUCKET                   = local.stepfunctions_seed_data_bucket_predicted_name
    }
  )

  # ---------------------------------------------------------------------------
  # Execution event logging — same configuration as the EOD pipeline.
  # See the inline comments in Section 2 above for rationale. The same
  # CloudWatch Logs group is shared between the two state machines (one
  # log stream per execution; the stream name includes the state machine
  # name + execution ID, so they are easy to distinguish in the console).
  # ---------------------------------------------------------------------------
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.step_functions.arn}:*"
    include_execution_data = false
    level                  = var.stepfunctions_logging_enabled ? "ALL" : "ERROR"
  }

  # ---------------------------------------------------------------------------
  # AWS X-Ray tracing — same rationale as the EOD pipeline. Critical
  # here because the parallel Glue Spark jobs span multiple AWS services
  # (Glue → S3 read → JDBC write to RDS) and the trace is the only
  # operational view that ties them back to the originating state
  # machine execution.
  # ---------------------------------------------------------------------------
  tracing_configuration {
    enabled = true
  }

  # ---------------------------------------------------------------------------
  # Tags — same pattern as the EOD pipeline.
  # ---------------------------------------------------------------------------
  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-file-provisioning"
    Purpose = "Data-store provisioning workflow (replaces JCL ACCTFILE -> CARDFILE -> XREFFILE -> ... -> DUSRSECJ per AAP §0.6.3)"
  })

  # ---------------------------------------------------------------------------
  # Explicit dependency on the Glue jobs that the state machine fans
  # out over via its BulkLoadFactData Map state. Although Terraform's
  # implicit dependency graph does NOT capture the dynamic references
  # (the Glue job names are passed at execution-input time rather than
  # baked into the ASL JSON), declaring an explicit `depends_on` here
  # ensures the Glue jobs exist before an operator can start an
  # execution of this state machine. Without it, the very first apply
  # could succeed but the first execution would fail with
  # `Glue.EntityNotFoundException`.
  # ---------------------------------------------------------------------------
  depends_on = [
    aws_glue_job.ascii_to_rds_account,
    aws_glue_job.ascii_to_rds_card,
    aws_glue_job.ascii_to_rds_customer,
    aws_glue_job.ascii_to_rds_xref,
    aws_glue_job.ascii_to_rds_transaction,
    aws_batch_job_definition.flyway_migrate,
    aws_batch_job_definition.validate_rowcounts,
  ]
}

# =============================================================================
# Section 3b — Transaction Report state machine (online → batch bridge)
# =============================================================================
# Replaces the COBOL CORPT00C → CICS TDQ JOBS → JES submission bridge per
# AAP §0.1.1 (sole online-to-batch bridge) and §0.6.3 (JCL → Step Functions
# Orchestration). The Spring REST endpoint POST /api/reports/submit accepts
# a report request, the application publishes a `report.requested` Kafka
# event partitioned by user ID (AAP §0.6.5), and KafkaEventConsumer
# .onReportRequested(...) consumes the event and starts an execution of
# this state machine via the AWS SDK v2 SfnClient (wired through
# StepFunctionsOrchestrator).
#
# State machine flow (see report-pipeline.asl.json for the full ASL):
#   InitializeReportPipeline → RouteByReportType
#     ├── MONTHLY / CUSTOM → RunTransactionReport (TRANREPT batch) → ...
#     └── YEARLY → YearlyReportFanOut (Parallel { RunTransactionReport,
#                                                  CreateStatements }) → ...
#
# Same `STANDARD` type, `templatefile()`-driven definition, CloudWatch
# Logs destination, and X-Ray configuration as the EOD pipeline; the
# state machine differs only in the ASL JSON it consumes and in the
# `${VAR}` placeholders it substitutes.
# =============================================================================

resource "aws_sfn_state_machine" "report_pipeline" {
  # ---------------------------------------------------------------------------
  # State machine name — same `carddemo-${env}-...` convention. Surfaced
  # to the Spring Boot application as STATE_MACHINE_REPORT_PIPELINE_ARN
  # (see ecs.tf) and consumed by StepFunctionsOrchestrator
  # .startReportPipeline(...). Operators triggering a report from the
  # admin REST endpoint indirectly start an execution of THIS state
  # machine via the `report.requested` Kafka topic.
  # ---------------------------------------------------------------------------
  name = "carddemo-${var.environment}-report-pipeline"

  # ---------------------------------------------------------------------------
  # Shares the same execution role as the EOD pipeline. The role's
  # runtime policy in `iam.tf` Section 8 grants batch:SubmitJob,
  # batch:DescribeJobs, batch:TerminateJob — exactly the permissions
  # this state machine's RunTransactionReport and CreateStatements
  # Task states need.
  # ---------------------------------------------------------------------------
  role_arn = aws_iam_role.step_functions_execution.arn

  # ---------------------------------------------------------------------------
  # STANDARD — report generation can run for tens of minutes (the
  # CBTRN03C-equivalent date-filtered transaction report processes the
  # full TRANSACT table for the requested date window). EXPRESS would
  # time out at 5 minutes.
  # ---------------------------------------------------------------------------
  type = "STANDARD"

  # ---------------------------------------------------------------------------
  # ASL JSON definition. The report-pipeline ASL references three
  # `${VAR}` placeholders:
  #
  #   BATCH_JOB_QUEUE_ARN
  #     ← aws_batch_job_queue.carddemo.arn — same queue as the EOD
  #       pipeline; the RunTransactionReport and CreateStatements Task
  #       states submit jobs here.
  #
  #   TRANREPT_JOB_DEFINITION_ARN
  #     ← aws_batch_job_definition.transaction_report.arn — the same
  #       AWS Batch job definition that the EOD pipeline uses for the
  #       Stage 4b TRANREPT step. Reusing the definition keeps a single
  #       source of truth for the CBTRN03C-equivalent job container.
  #
  #   CREASTMT_JOB_DEFINITION_ARN
  #     ← aws_batch_job_definition.statement_generation.arn — same
  #       reuse rationale as TRANREPT_JOB_DEFINITION_ARN. Invoked only
  #       on YEARLY reports per the year-end statement chain.
  # ---------------------------------------------------------------------------
  definition = templatefile(
    "${path.module}/../../src/main/resources/stepfunctions/report-pipeline.asl.json",
    {
      BATCH_JOB_QUEUE_ARN         = aws_batch_job_queue.carddemo.arn
      TRANREPT_JOB_DEFINITION_ARN = aws_batch_job_definition.transaction_report.arn
      CREASTMT_JOB_DEFINITION_ARN = aws_batch_job_definition.statement_generation.arn
    }
  )

  # ---------------------------------------------------------------------------
  # Execution event logging — same configuration as the EOD pipeline.
  # See the inline comments in Section 2 above for rationale. The same
  # CloudWatch Logs group is shared between the three state machines;
  # the stream name includes the state machine name + execution ID, so
  # they are easy to distinguish in the console.
  # ---------------------------------------------------------------------------
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.step_functions.arn}:*"
    include_execution_data = false
    level                  = var.stepfunctions_logging_enabled ? "ALL" : "ERROR"
  }

  # ---------------------------------------------------------------------------
  # AWS X-Ray tracing — same rationale as the EOD pipeline. Allows the
  # end-to-end trace (REST request → Kafka publish → consumer →
  # StepFunctions → Batch container → S3 report write) to be
  # reconstructed for any execution.
  # ---------------------------------------------------------------------------
  tracing_configuration {
    enabled = true
  }

  # ---------------------------------------------------------------------------
  # Tags — same pattern as the EOD pipeline. Reuses local.common_tags
  # for the project-wide four-key set and adds the per-resource Name
  # and Purpose tags.
  # ---------------------------------------------------------------------------
  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-report-pipeline"
    Purpose = "Transaction-report pipeline (replaces CORPT00C -> CICS TDQ JOBS -> JES TRANREPT.jcl bridge per AAP §0.1.1 / §0.6.3)"
  })

  # ---------------------------------------------------------------------------
  # Explicit dependency on the Batch job definitions referenced by the
  # ASL JSON. Although Terraform's implicit dependency graph captures
  # these via the `templatefile()` substitution, declaring depends_on
  # makes the relationship explicit and ensures the Batch job
  # definitions reach the ACTIVE state before this state machine is
  # created.
  # ---------------------------------------------------------------------------
  depends_on = [
    aws_batch_job_definition.transaction_report,
    aws_batch_job_definition.statement_generation,
  ]
}

# =============================================================================
# Section 4 — Optional EventBridge Scheduler trigger (daily 23:00 UTC)
# =============================================================================
# Replaces the legacy z/OS scheduler entry that invoked the EOD JCL job
# stream at 23:00 each day. Implemented with AWS EventBridge Scheduler
# (the newer scheduler-specific service, distinct from the legacy
# `events.amazonaws.com` rule-based scheduler — see `iam.tf` Section 10).
#
# Conditional on `var.eod_scheduled_enabled`:
#
#   * `true` (production): provisions the schedule so the EOD pipeline
#     runs nightly without operator intervention.
#   * `false` (dev / staging default): suppresses the schedule entirely.
#     Operators may still start executions manually via the AWS console,
#     the AWS CLI, or the `StepFunctionsOrchestrator.startEodBatch(...)`
#     adapter — the schedule is the only thing the variable controls.
#
# The `count = var.eod_scheduled_enabled ? 1 : 0` pattern is the
# canonical Terraform idiom for a fully optional resource. When count
# is 0 the resource is not created and any cross-reference would need
# to use `aws_scheduler_schedule.eod_batch_daily[0].arn`; the module
# does not currently reference this resource elsewhere, so the
# bracketed-index form is needed only if a future caller wants to
# expose the schedule ARN.
#
# Cron expression `cron(0 23 * * ? *)` decodes to "at 23:00 UTC every
# day" — the standard EOD cutover time used by the legacy CardDemo
# mainframe operations runbook. The `*` in the day-of-week position and
# the `?` in the day-of-month position together select every calendar
# day (EventBridge Scheduler requires exactly one of those two fields
# to be `?` per the cron spec).
#
# `flexible_time_window { mode = "OFF" }` forces the schedule to fire at
# the exact 23:00 UTC mark every day. The alternative `FLEXIBLE` mode
# allows EventBridge to fire any time within a window (useful for cost
# spreading) but is incompatible with the EOD batch contract — the
# pipeline must complete before the next business day's transactions
# begin posting, so the start time is fixed.
#
# `target.input = jsonencode({})` — the EOD state machine's
# `InitializeEodPipeline` Pass state expects an execution input object
# containing `processingDate`, `rdsSecretArn`, `s3OutputBucket`,
# `kmsKeyArn`, `kafkaBootstrapServers`, and `snsAlertTopicArn`. Those
# values are supplied at runtime by the state machine's IAM execution
# role (via Secrets Manager + Parameter Store) — NOT injected here in
# the schedule target. Sending `{}` therefore creates an execution with
# no input, which the InitializeEodPipeline state handles by falling
# back to the role-supplied defaults. (Operators wanting to override
# the processing date for a back-fill run start an execution manually
# rather than waiting for the next scheduled invocation.)
# =============================================================================

resource "aws_scheduler_schedule" "eod_batch_daily" {
  # Conditional provisioning — see the rationale above.
  count = var.eod_scheduled_enabled ? 1 : 0

  # ---------------------------------------------------------------------------
  # Schedule name. Same `carddemo-${env}-...` convention as the state
  # machines above. The `eod-batch-daily` suffix conveys both purpose
  # and cadence.
  # ---------------------------------------------------------------------------
  name = "carddemo-${var.environment}-eod-batch-daily"

  # ---------------------------------------------------------------------------
  # `default` schedule group. EventBridge Scheduler organises schedules
  # into groups (similar to Lambda function namespaces) primarily for
  # IAM scoping and tag-based cost allocation. The `default` group is
  # auto-provisioned by AWS in every account and region, so no
  # `aws_scheduler_schedule_group` resource is required here.
  # ---------------------------------------------------------------------------
  group_name = "default"

  description = "Daily 23:00 UTC trigger for the CardDemo EOD batch pipeline state machine (replaces legacy z/OS scheduler entry per AAP §0.6.3)"

  # ---------------------------------------------------------------------------
  # Cron expression: 23:00 UTC every day. See the Section 4 banner
  # comment above for the cron-syntax breakdown. The
  # `schedule_expression_timezone` field is set explicitly to "UTC" to
  # avoid the AWS default of converting the cron to local time based on
  # the EventBridge Scheduler service region — the EOD pipeline contract
  # is defined in UTC for regulatory traceability.
  # ---------------------------------------------------------------------------
  schedule_expression          = "cron(0 23 * * ? *)"
  schedule_expression_timezone = "UTC"

  # ---------------------------------------------------------------------------
  # `OFF` — fire at exactly the cron time. See the Section 4 banner
  # comment above. EventBridge Scheduler requires this block; omitting
  # it produces a Terraform validation error.
  # ---------------------------------------------------------------------------
  flexible_time_window {
    mode = "OFF"
  }

  # ---------------------------------------------------------------------------
  # Target — invoke the EOD state machine via
  # `arn:aws:scheduler:::aws-sdk:sfn:startExecution`. EventBridge
  # Scheduler resolves the AWS SDK partner integration automatically
  # when `arn` points at a state machine ARN, so an explicit `RoleArn`
  # and `Input` is enough.
  # ---------------------------------------------------------------------------
  target {
    # Target the EOD state machine provisioned in Section 2 above.
    arn = aws_sfn_state_machine.eod_batch_pipeline.arn

    # IAM role assumed by EventBridge Scheduler to call
    # `states:StartExecution` against the EOD state machine. The role
    # is provisioned in `iam.tf` Section 10 with a trust policy that
    # accepts only the `scheduler.amazonaws.com` service principal +
    # `aws:SourceAccount` condition, and an inline runtime policy that
    # grants `states:StartExecution` scoped to the exact EOD state
    # machine ARN (predicted by name pattern in `iam.tf`).
    role_arn = aws_iam_role.eventbridge_scheduler.arn

    # Execution input. Empty object means "let the state machine fall
    # back to role-supplied defaults". See the Section 4 banner comment
    # above. `jsonencode({})` produces the literal string `{}` — the
    # EventBridge Scheduler API requires a JSON STRING here, not a
    # native HCL object, hence the explicit encode call.
    input = jsonencode({})
  }
}
