#!/usr/bin/env bash
#
# init-aws.sh - LocalStack AWS resource provisioning for the CardDemo
#               Java / Spring Boot migration.
#
# This script runs automatically as a LocalStack initialization hook. The root
# docker-compose.yml mounts it into the LocalStack container at
# /etc/localstack/init/ready.d/init-aws.sh, where LocalStack executes it once
# the emulator reaches the READY state.
#
# It is the cloud-native replacement for the legacy mainframe GDG / dataset
# provisioning JCL (app/jcl/DEFGDGB, app/jcl/REPTFILE, app/jcl/DALYREJS).
# Mapping of legacy provisioning -> AWS resources:
#   * GDG generation data groups (TRANSACT.DALY, TRANREPT, DALYREJS, SYSTRAN,
#     TRANSACT.COMBINED/BKUP, TCATBALF.BKUP, statements) become objects in three
#     versioned S3 buckets that preserve the input / output / statement
#     separation of the batch pipeline. S3 object versioning models the GDG
#     LIMIT(n) generation-retention semantics.
#   * The CICS Transient Data Queue "JOBS" report-submission bridge (CORPT00C)
#     becomes the SQS FIFO queue carddemo-report-jobs.fifo.
#   * An SNS topic provides alert / notification fan-out.
#
# The script is idempotent and safe to re-run, mirroring the legacy JCL idiom
# "IF LASTCC=12 THEN SET MAXCC=0" (define-if-not-exists). It targets LocalStack
# only and never contacts live AWS.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (overridable via environment)
# ---------------------------------------------------------------------------
AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
LOCALSTACK_ENDPOINT="${LOCALSTACK_ENDPOINT:-http://localhost:4566}"

# Exact resource names - MUST match application-local.yml / AwsConfig and the
# Testcontainers integration tests so the app and tests resolve them.
S3_BUCKETS="carddemo-batch-input carddemo-batch-output carddemo-statements"
SQS_FIFO_QUEUE="carddemo-report-jobs.fifo"
SNS_TOPIC="carddemo-notifications"

# ---------------------------------------------------------------------------
# AWS CLI wrapper: prefer `awslocal`; fall back to `aws --endpoint-url=...`
# ---------------------------------------------------------------------------
if command -v awslocal >/dev/null 2>&1; then
  awscli() { awslocal "$@"; }
else
  awscli() { aws --endpoint-url="${LOCALSTACK_ENDPOINT}" --region "${AWS_REGION}" "$@"; }
fi

log() { echo "[init-aws] $*"; }

log "Starting CardDemo AWS resource provisioning (region=${AWS_REGION})"

# ---------------------------------------------------------------------------
# S3 buckets (idempotent) + versioning (models GDG LIMIT(n) generations)
# ---------------------------------------------------------------------------
for bucket in ${S3_BUCKETS}; do
  if awscli s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
    log "S3 bucket already exists: ${bucket}"
  else
    log "Creating S3 bucket: ${bucket}"
    # region us-east-1 is the S3 default - no LocationConstraint required
    awscli s3api create-bucket --bucket "${bucket}" >/dev/null
  fi
  awscli s3api put-bucket-versioning \
    --bucket "${bucket}" \
    --versioning-configuration Status=Enabled >/dev/null
  log "Versioning enabled on S3 bucket: ${bucket}"
done

# ---------------------------------------------------------------------------
# SQS FIFO queue (idempotent) - CICS TDQ JOBS report-submission replacement
# ---------------------------------------------------------------------------
if awscli sqs get-queue-url --queue-name "${SQS_FIFO_QUEUE}" >/dev/null 2>&1; then
  log "SQS FIFO queue already exists: ${SQS_FIFO_QUEUE}"
else
  log "Creating SQS FIFO queue: ${SQS_FIFO_QUEUE}"
  awscli sqs create-queue \
    --queue-name "${SQS_FIFO_QUEUE}" \
    --attributes FifoQueue=true,ContentBasedDeduplication=true >/dev/null
fi

# ---------------------------------------------------------------------------
# SNS topic (idempotent - create-topic returns the existing ARN if present)
# ---------------------------------------------------------------------------
log "Creating SNS topic: ${SNS_TOPIC}"
TOPIC_ARN="$(awscli sns create-topic --name "${SNS_TOPIC}" --output text --query 'TopicArn')"
log "SNS topic ARN: ${TOPIC_ARN}"

# ---------------------------------------------------------------------------
# Verification listing (informational - for log inspection)
# ---------------------------------------------------------------------------
log "Provisioning complete. Current resources:"
log "S3 buckets:";  awscli s3 ls           || true
log "SQS queues:";  awscli sqs list-queues  || true
log "SNS topics:";  awscli sns list-topics  || true

log "CardDemo AWS resource provisioning finished successfully."
