#!/usr/bin/env bash
# =============================================================================
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.
# =============================================================================
#
# init-aws.sh -- LocalStack AWS resource provisioning hook for the CardDemo
#                Java 25 + Spring Boot 3.x migration.
#
# PURPOSE
#   Provisions every AWS resource the migrated application needs, entirely
#   against LocalStack, with ZERO live AWS dependencies (AAP S0.7.7). This
#   script is the cloud-native replacement for the legacy z/OS dataset / GDG
#   provisioning JCL; it creates the S3 buckets, the SQS FIFO queue, and the
#   SNS topic that the batch and report layers consume.
#
# HOW IT RUNS
#   The sibling carddemo-java/docker-compose.yml defines a `localstack` service
#   (localstack/localstack-pro:latest, SERVICES=s3,sqs,sns) that bind-mounts
#   ./localstack-init to /etc/localstack/init/ready.d. LocalStack automatically
#   executes every script in ready.d *once its runtime reports READY*. This
#   script therefore runs INSIDE the LocalStack container, where the `awslocal`
#   CLI wrapper is available and pre-pointed at http://localhost:4566. A host
#   fallback (plain `aws --endpoint-url ...`) is also supported (see Phase 2).
#
# TRACEABILITY (AAP S0.7.2)
#   This is a brand-new artifact; there is no 1:1 COBOL source. Its provisioning
#   intent is derived from the original COBOL repository at commit SHA 27d6c6f
#   (the COBOL sources are NOT copied into this repository -- only referenced):
#     * app/jcl/DEFGDGB.jcl  -- DEFINE GENERATIONDATAGROUP (6 GDG bases) whose
#                               generation semantics map to S3 object versions.
#     * app/jcl/DUSRSECJ.jcl -- USRSEC seed (CONTEXT ONLY; see "MUST NOT" below).
#
# TECHNOLOGY SUBSTITUTIONS (Minimal Change Clause S0.7.1; documented at point of
# use further below):
#     * GDG generations              -> S3 versioned objects   (decision D-003)
#     * CICS TDQ 'JOBS' (WRITEQ TD)   -> SQS FIFO queue          (decision D-004)
#     * CICS notification messaging   -> SNS topic
#
# WHAT THIS SCRIPT MUST NOT DO (scope discipline -- Minimal Change Clause S0.7.1)
#     * It does NOT provision USRSEC / authentication data. app/jcl/DUSRSECJ.jcl
#       is context only; the 10 seed users are migrated to PostgreSQL by the
#       Flyway V3__seed_data.sql migration, not here.
#     * It does NOT create PostgreSQL schema/tables (Flyway handles that).
#     * It does NOT create resources beyond those listed below.
#     * It does NOT embed real credentials/tokens/ARNs and never echoes
#       LOCALSTACK_AUTH_TOKEN or any secret.
#     * It does NOT point at any live AWS endpoint -- LocalStack only.
#
# RE-RUN SAFETY
#   Every resource creation is existence-guarded and tolerates the "already
#   exists" condition, mirroring the legacy "IF LASTCC=12 THEN SET MAXCC=0"
#   re-run pattern in app/jcl/DEFGDGB.jcl. A second execution exits 0 with no
#   error, satisfying the S0.7.7 "tests create and destroy their own resources"
#   requirement.
# =============================================================================

# -----------------------------------------------------------------------------
# Phase 1 -- Safe shell options
# -----------------------------------------------------------------------------
# `set -u`        : treat unset variables as an error (catches typos early).
# `set -o pipefail`: a pipeline fails if ANY stage fails (not just the last).
#
# A bare `set -e` is deliberately NOT used: it would abort the whole script the
# first time an idempotent create "fails" because the resource already exists.
# Instead, each resource below is handled explicitly (existence guard + tolerate
# already-exists), which keeps the script fully re-run safe.
set -u
set -o pipefail

# -----------------------------------------------------------------------------
# Logging helper -- prefix all output so it is easy to spot in
# `docker compose logs localstack`.
# -----------------------------------------------------------------------------
log() {
  echo "[init-aws] $*"
}

# -----------------------------------------------------------------------------
# Phase 2 -- Environment & AWS CLI selection (NO hardcoded secrets, S0.7.2/S0.8.3)
# -----------------------------------------------------------------------------
# Region and the LocalStack edge endpoint come from the environment with
# non-secret defaults that match carddemo-java/docker-compose.yml.
AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost:4566}"
export AWS_DEFAULT_REGION AWS_ENDPOINT_URL

# NON-SECRET LocalStack placeholders -- these are NOT real credentials. The
# `awslocal` wrapper supplies its own dummy credentials, but the plain `aws`
# fallback's default provider chain requires *some* values to be present, so we
# default the conventional placeholder `test`/`test` ONLY when unset. LocalStack
# ignores the values entirely. Real credentials, if ever supplied, arrive via
# the environment and are never written here.
AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY

# NOTE: LOCALSTACK_AUTH_TOKEN is consumed by the LocalStack container itself
# (set in docker-compose.yml); it is intentionally neither read nor echoed here.

# Resolve which CLI to use exactly once. `awslocal` is preferred inside the
# LocalStack container (it auto-targets the edge port and injects dummy creds).
# Otherwise fall back to the standard AWS CLI pointed at the LocalStack endpoint
# so this script is also runnable from a host that has the AWS CLI installed.
if command -v awslocal >/dev/null 2>&1; then
  AWS_CLI_MODE="awslocal"
else
  AWS_CLI_MODE="aws"
fi
log "Using AWS CLI mode: ${AWS_CLI_MODE} (endpoint: ${AWS_ENDPOINT_URL})"

# Uniform wrapper used by every resource command below.
awscli() {
  if [[ "${AWS_CLI_MODE}" == "awslocal" ]]; then
    awslocal "$@"
  else
    aws --endpoint-url "${AWS_ENDPOINT_URL}" "$@"
  fi
}

# -----------------------------------------------------------------------------
# Resource name variables (single source of truth).
#
# Every name is env-overridable via the SAME variable names used by the Spring
# profiles (src/main/resources/application-test.yml et al.) so docker-compose,
# AwsConfig, and the batch/report services all stay in lock-step. The defaults
# are the contract names from the blueprint (tech-spec L1007) and MUST match
# carddemo-java/docker-compose.yml verbatim.
# -----------------------------------------------------------------------------
S3_BATCH_INPUT_BUCKET="${CARDDEMO_S3_BATCH_INPUT_BUCKET:-carddemo-batch-input}"
S3_BATCH_OUTPUT_BUCKET="${CARDDEMO_S3_BATCH_OUTPUT_BUCKET:-carddemo-batch-output}"
S3_STATEMENTS_BUCKET="${CARDDEMO_S3_STATEMENTS_BUCKET:-carddemo-statements}"

# The three S3 buckets, collected for iteration.
S3_BUCKETS="${S3_BATCH_INPUT_BUCKET} ${S3_BATCH_OUTPUT_BUCKET} ${S3_STATEMENTS_BUCKET}"

# The single SQS FIFO queue. The `.fifo` suffix is MANDATORY for FIFO queues.
REPORT_QUEUE_NAME="${CARDDEMO_SQS_REPORT_JOBS_QUEUE:-carddemo-report-jobs.fifo}"

# The SNS topic (enabled here via SERVICES=s3,sqs,sns).
SNS_TOPIC_NAME="${CARDDEMO_SNS_NOTIFICATIONS_TOPIC:-carddemo-notifications}"

# -----------------------------------------------------------------------------
# Optional, BOUNDED readiness probe.
#
# Not strictly required: the ready.d hook only runs once LocalStack reports
# READY. It is included as a defensive measure for the `aws` host-fallback path.
# It NEVER blocks indefinitely -- at most max_attempts tries, then proceeds.
# -----------------------------------------------------------------------------
wait_for_localstack() {
  command -v curl >/dev/null 2>&1 || return 0
  health_url="${AWS_ENDPOINT_URL}/_localstack/health"
  attempt=1
  max_attempts=30
  while [[ "${attempt}" -le "${max_attempts}" ]]; do
    if curl -sf "${health_url}" >/dev/null 2>&1; then
      log "LocalStack health endpoint responding (${health_url})."
      return 0
    fi
    log "Waiting for LocalStack readiness (attempt ${attempt}/${max_attempts})..."
    sleep 2
    attempt=$((attempt + 1))
  done
  log "Proceeding without a confirmed health response (ready.d guarantees readiness)."
  return 0
}

# -----------------------------------------------------------------------------
# Phase 3 -- S3 buckets (idempotent + versioning; GDG -> S3, decision D-003)
#
# The 6 legacy GDG bases in app/jcl/DEFGDGB.jcl (TRANSACT.BKUP, TRANSACT.DALY,
# TRANREPT, TCATBALF.BKUP, SYSTRAN, TRANSACT.COMBINED) are NOT six buckets: they
# collapse into the THREE buckets below as versioned object key prefixes.
# Generation numbering (DEFGDGB's LIMIT(5)) is realized via S3 object VERSIONING,
# not separate buckets -- hence versioning is enabled on each bucket.
# -----------------------------------------------------------------------------
create_s3_buckets() {
  log "Ensuring S3 buckets (GDG generations -> versioned S3 objects, D-003)..."
  for bucket in ${S3_BUCKETS}; do
    if awscli s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
      log "S3 bucket already exists: ${bucket}"
    else
      log "Creating S3 bucket: ${bucket}"
      # Prefer `aws s3 mb`: it handles the region internally and sidesteps the
      # us-east-1 `LocationConstraint` special case that `s3api create-bucket`
      # requires for non-us-east-1 regions.
      if awscli s3 mb "s3://${bucket}" >/dev/null 2>&1; then
        log "Created S3 bucket: ${bucket}"
      elif awscli s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
        # Tolerate BucketAlreadyOwnedByYou / BucketAlreadyExists (e.g. a race
        # with a concurrent run): re-verify existence and continue.
        log "S3 bucket ensured (already owned): ${bucket}"
      else
        log "ERROR: failed to create S3 bucket: ${bucket}"
        exit 1
      fi
    fi

    # Enable versioning so GDG generation semantics map to S3 object versions
    # (decision D-003). put-bucket-versioning is naturally idempotent.
    if awscli s3api put-bucket-versioning \
         --bucket "${bucket}" \
         --versioning-configuration Status=Enabled >/dev/null 2>&1; then
      log "Versioning enabled on bucket: ${bucket}"
    else
      log "ERROR: failed to enable versioning on bucket: ${bucket}"
      exit 1
    fi
  done
}

# -----------------------------------------------------------------------------
# Phase 4 -- SQS FIFO queue (idempotent; CICS TDQ -> SQS, decision D-004)
#
# This replaces the single online->batch bridge in CORPT00C, which issued
# `WRITEQ TD QUEUE('JOBS')` to hand a report request to JES. The FIFO queue
# preserves the point-to-point, ordered delivery of that transient-data queue.
# -----------------------------------------------------------------------------
create_sqs_queue() {
  log "Ensuring SQS FIFO queue (CICS TDQ 'JOBS' -> SQS FIFO, D-004): ${REPORT_QUEUE_NAME}"
  if awscli sqs get-queue-url --queue-name "${REPORT_QUEUE_NAME}" >/dev/null 2>&1; then
    log "SQS FIFO queue already exists: ${REPORT_QUEUE_NAME}"
  else
    log "Creating SQS FIFO queue: ${REPORT_QUEUE_NAME}"
    # FifoQueue=true REQUIRES the `.fifo` name suffix. ContentBasedDeduplication
    # lets producers omit an explicit dedup id. create-queue with identical
    # name+attributes returns the existing URL, so it is itself idempotent.
    if awscli sqs create-queue \
         --queue-name "${REPORT_QUEUE_NAME}" \
         --attributes FifoQueue=true,ContentBasedDeduplication=true >/dev/null 2>&1; then
      log "Created SQS FIFO queue: ${REPORT_QUEUE_NAME}"
    elif awscli sqs get-queue-url --queue-name "${REPORT_QUEUE_NAME}" >/dev/null 2>&1; then
      log "SQS FIFO queue ensured (already exists): ${REPORT_QUEUE_NAME}"
    else
      log "ERROR: failed to create SQS FIFO queue: ${REPORT_QUEUE_NAME}"
      exit 1
    fi
  fi
}

# -----------------------------------------------------------------------------
# Phase 5 -- SNS topic (REQUIRED, idempotent; CICS notifications -> SNS)
#
# create-topic is naturally idempotent: for an existing name it returns the same
# ARN. SNS is part of the REQUIRED AWS resource contract -- it is enabled here
# via SERVICES=s3,sqs,sns and consumed by the report/notification layers and the
# CP5 AwsConfig -- so a provisioning failure aborts the script with a non-zero
# exit, exactly like the S3 and SQS phases above.
# -----------------------------------------------------------------------------
create_sns_topic() {
  log "Ensuring SNS topic (CICS notifications -> SNS): ${SNS_TOPIC_NAME}"
  # `sns create-topic` is naturally idempotent: for an existing topic name it
  # returns the SAME TopicArn, so this single call both creates-if-absent and
  # ensures-if-present. SNS is a REQUIRED resource, so a non-empty ARN is the
  # success condition and any failure (empty ARN or a non-zero CLI exit) aborts
  # the script with `exit 1` -- mirroring the S3 and SQS phases above rather than
  # swallowing the error and exiting 0.
  if SNS_TOPIC_ARN="$(awscli sns create-topic --name "${SNS_TOPIC_NAME}" \
       --output text --query 'TopicArn' 2>/dev/null)" \
       && [[ -n "${SNS_TOPIC_ARN:-}" ]]; then
    log "SNS topic ensured: ${SNS_TOPIC_NAME} (ARN: ${SNS_TOPIC_ARN})"
  else
    log "ERROR: failed to ensure SNS topic: ${SNS_TOPIC_NAME}"
    exit 1
  fi
}

# -----------------------------------------------------------------------------
# Phase 6 -- Orchestration & completion summary
# -----------------------------------------------------------------------------
main() {
  log "Starting LocalStack AWS resource provisioning for CardDemo..."
  wait_for_localstack
  create_s3_buckets
  create_sqs_queue
  create_sns_topic

  log "============================================================"
  log "LocalStack resource provisioning complete. Resources ensured:"
  log "  S3 buckets (versioning enabled):"
  for bucket in ${S3_BUCKETS}; do
    log "    - ${bucket}"
  done
  log "  SQS FIFO queue:"
  log "    - ${REPORT_QUEUE_NAME}"
  log "  SNS topic:"
  log "    - ${SNS_TOPIC_NAME}"
  log "============================================================"
  exit 0
}

main "$@"
