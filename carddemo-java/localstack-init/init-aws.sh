#!/usr/bin/env bash
#
# ============================================================================
# CardDemo - LocalStack AWS resource bootstrap
# ============================================================================
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# A copy of the License is located at http://www.apache.org/licenses/LICENSE-2.0
#
# ----------------------------------------------------------------------------
# Purpose
#   Provisions the AWS resources required by the CardDemo Java migration inside
#   the LocalStack container. LocalStack executes every script placed in
#   /etc/localstack/init/ready.d/ once the emulator reports "ready", so this
#   script is the canonical, self-provisioning declaration of the local AWS
#   stack (LocalStack Verification rule).
#
#   Creates (idempotently):
#     * 3 S3 buckets (versioning enabled + a GDG LIMIT(5) noncurrent-version
#       retention lifecycle policy)
#     * 1 SQS FIFO queue (content-based deduplication) plus a companion FIFO
#       dead-letter queue, with a RedrivePolicy (maxReceiveCount=5) on the main
#       queue so poison/failed messages are isolated rather than looping
#     * 1 SNS topic
#
# Traceability (source reference only - COBOL/JCL is translated, NOT copied)
#   Derived from app/jcl/DEFGDGB.jcl @ CardDemo commit SHA 27d6c6f.
#   DEFGDGB.jcl defines 6 generation-data-group (GDG) bases, each LIMIT(5) +
#   SCRATCH. The mainframe GDG generation/retention model re-platforms to S3
#   versioned objects (each GDG generation becomes an S3 object version - this
#   is why bucket versioning is enabled below), and the LIMIT(5) retention is
#   re-platformed to an S3 lifecycle policy that keeps the current version plus
#   the 4 most-recent noncurrent versions (the last 5 generations). GDG base ->
#   S3 bucket mapping:
#
#     AWS.M2.CARDDEMO.TRANSACT.DALY      -> carddemo-batch-input   (input staging)
#     AWS.M2.CARDDEMO.TRANSACT.BKUP      -> carddemo-batch-output  (batch backup)
#     AWS.M2.CARDDEMO.TRANSACT.COMBINED  -> carddemo-batch-output  (batch output)
#     AWS.M2.CARDDEMO.SYSTRAN            -> carddemo-batch-output  (batch output)
#     AWS.M2.CARDDEMO.TRANREPT           -> carddemo-batch-output  (report output)
#     AWS.M2.CARDDEMO.TCATBALF.BKUP      -> carddemo-batch-output  (batch backup)
#     Statement output (CREASTMT.JCL / CBSTM03A) -> carddemo-statements
#
#   The SQS FIFO queue re-platforms the CICS TDQ "WRITEQ TD" report-submission
#   bridge (app/cbl/CORPT00C.cbl); FIFO preserves point-to-point ordering.
#   The SNS topic provides notification fan-out.
#
#   Re-runnable: each create is guarded so re-execution is a no-op, mirroring
#   the JCL "IF LASTCC=12 THEN SET MAXCC=0" idempotency pattern in DEFGDGB.jcl.
# ----------------------------------------------------------------------------

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# LocalStack edge endpoint. This script runs INSIDE the LocalStack container,
# so the in-container address localhost:4566 is correct (never a real AWS URL).
AWS_ENDPOINT_URL="http://localhost:4566"

# LocalStack ignores credential values but the AWS CLI still requires them to
# be present. These are LocalStack's well-known dummy placeholders (NOT real
# secrets); honour any values already exported by docker-compose, otherwise
# default to "test". No real access keys or auth tokens are embedded in this
# file - any sensitive value arrives only via the environment.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

# Resource names - binding contract shared verbatim with docker-compose.yml,
# config/AwsConfig.java, application-local.yml and application-test.yml.
S3_BUCKETS=(
  "carddemo-batch-input"
  "carddemo-batch-output"
  "carddemo-statements"
)
SQS_FIFO_QUEUE="carddemo-report-jobs.fifo"
# Companion FIFO dead-letter queue + the number of receives a message may incur
# on the main queue before SQS moves it to the DLQ (RedrivePolicy).
SQS_FIFO_DLQ="carddemo-report-jobs-dlq.fifo"
SQS_MAX_RECEIVE_COUNT="5"
SNS_TOPIC="carddemo-notifications"

# ---------------------------------------------------------------------------
# Helper: invoke the AWS CLI against the LocalStack endpoint.
# ---------------------------------------------------------------------------
aws_cmd() {
  aws --endpoint-url="$AWS_ENDPOINT_URL" "$@"
}

echo "=============================================================="
echo " Initializing CardDemo LocalStack AWS resources..."
echo " Endpoint : $AWS_ENDPOINT_URL"
echo " Region   : $AWS_DEFAULT_REGION"
echo "=============================================================="

# ---------------------------------------------------------------------------
# S3 buckets (with versioning + GDG LIMIT(5) retention lifecycle) - GDG
# re-platform target.
#
# The lifecycle policy expires noncurrent versions beyond the 4 most-recent
# (NewerNoncurrentVersions=4), so each object retains its current version plus
# 4 noncurrent versions = the last 5 generations, matching the DEFGDGB.jcl
# LIMIT(5) retention. NoncurrentDays=1 is the S3 lifecycle minimum grace before
# expiration (GDG rolls off by count alone; this is the closest faithful S3
# equivalent). The rule body is identical for every bucket, so it is written
# once to a temp file and applied in the loop.
# ---------------------------------------------------------------------------
LIFECYCLE_FILE="$(mktemp)"
cat > "$LIFECYCLE_FILE" <<'EOF'
{"Rules":[{"ID":"carddemo-gdg-retention","Filter":{"Prefix":""},"Status":"Enabled","NoncurrentVersionExpiration":{"NoncurrentDays":1,"NewerNoncurrentVersions":4}}]}
EOF

echo "[S3] Provisioning buckets..."
for BUCKET in "${S3_BUCKETS[@]}"; do
  # Idempotent create: head-bucket succeeds only when the bucket already exists.
  if aws_cmd s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
    echo "  [exists]  $BUCKET"
  else
    # Region is us-east-1, so the bucket is created with no explicit location
    # constraint (passing one would be rejected by the S3 API in this region).
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null
    echo "  [created] $BUCKET"
  fi

  # Enable versioning (GDG generation/retention -> S3 object versions).
  # Re-applying Status=Enabled is a no-op, so this is safe on re-runs.
  aws_cmd s3api put-bucket-versioning \
    --bucket "$BUCKET" \
    --versioning-configuration Status=Enabled >/dev/null
  echo "            versioning: Enabled"

  # Apply (idempotently) the GDG LIMIT(5) noncurrent-version retention lifecycle.
  # Re-applying the same configuration replaces it in place, so this is safe on
  # re-runs.
  aws_cmd s3api put-bucket-lifecycle-configuration \
    --bucket "$BUCKET" \
    --lifecycle-configuration "file://$LIFECYCLE_FILE" >/dev/null
  echo "            lifecycle: last-5-generation retention (NewerNoncurrentVersions=4)"
done
rm -f "$LIFECYCLE_FILE"

# ---------------------------------------------------------------------------
# SQS FIFO queue - CICS TDQ (WRITEQ TD) report-submission bridge replacement.
# FIFO + content-based deduplication preserves the queue's point-to-point
# ordering guarantee. The ".fifo" suffix is mandatory for FIFO queues.
#
# A companion FIFO dead-letter queue is provisioned first so the main queue can
# carry a RedrivePolicy that targets it: after $SQS_MAX_RECEIVE_COUNT failed
# receives a message is moved to the DLQ instead of looping indefinitely (poison
# message) or being silently dropped on a launch failure (DECISION_LOG D-029).
# ---------------------------------------------------------------------------
echo "[SQS] Provisioning FIFO dead-letter queue..."
if aws_cmd sqs get-queue-url --queue-name "$SQS_FIFO_DLQ" >/dev/null 2>&1; then
  echo "  [exists]  $SQS_FIFO_DLQ"
else
  aws_cmd sqs create-queue \
    --queue-name "$SQS_FIFO_DLQ" \
    --attributes FifoQueue=true,ContentBasedDeduplication=true >/dev/null
  echo "  [created] $SQS_FIFO_DLQ (FifoQueue=true, ContentBasedDeduplication=true)"
fi

# Resolve the DLQ ARN so the main queue's RedrivePolicy can reference it.
DLQ_URL="$(aws_cmd sqs get-queue-url --queue-name "$SQS_FIFO_DLQ" --output text --query 'QueueUrl')"
DLQ_ARN="$(aws_cmd sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --output text --query 'Attributes.QueueArn')"

echo "[SQS] Provisioning FIFO queue..."
if aws_cmd sqs get-queue-url --queue-name "$SQS_FIFO_QUEUE" >/dev/null 2>&1; then
  echo "  [exists]  $SQS_FIFO_QUEUE"
else
  aws_cmd sqs create-queue \
    --queue-name "$SQS_FIFO_QUEUE" \
    --attributes FifoQueue=true,ContentBasedDeduplication=true >/dev/null
  echo "  [created] $SQS_FIFO_QUEUE (FifoQueue=true, ContentBasedDeduplication=true)"
fi

# Attach (or re-apply, idempotently) the RedrivePolicy targeting the DLQ. The
# RedrivePolicy attribute value is itself a JSON-encoded string, so it is
# written to a temp file and passed via file:// to avoid shell quoting and
# comma-parsing pitfalls in the --attributes shorthand.
REDRIVE_FILE="$(mktemp)"
cat > "$REDRIVE_FILE" <<EOF
{"RedrivePolicy":"{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"$SQS_MAX_RECEIVE_COUNT\"}"}
EOF
MAIN_URL="$(aws_cmd sqs get-queue-url --queue-name "$SQS_FIFO_QUEUE" --output text --query 'QueueUrl')"
aws_cmd sqs set-queue-attributes \
  --queue-url "$MAIN_URL" \
  --attributes "file://$REDRIVE_FILE" >/dev/null
rm -f "$REDRIVE_FILE"
echo "            RedrivePolicy -> $SQS_FIFO_DLQ (maxReceiveCount=$SQS_MAX_RECEIVE_COUNT)"

# ---------------------------------------------------------------------------
# SNS topic - notification fan-out. create-topic is naturally idempotent and
# returns the existing TopicArn when the topic already exists.
# ---------------------------------------------------------------------------
echo "[SNS] Provisioning topic..."
TOPIC_ARN="$(aws_cmd sns create-topic --name "$SNS_TOPIC" --output text --query 'TopicArn')"
echo "  [ready]   $SNS_TOPIC"
echo "            ARN: $TOPIC_ARN"

# ---------------------------------------------------------------------------
# Verification listing.
# ---------------------------------------------------------------------------
echo "--------------------------------------------------------------"
echo "[verify] S3 buckets:"
aws_cmd s3 ls
echo "[verify] S3 lifecycle (GDG LIMIT(5) retention) on ${S3_BUCKETS[0]}:"
aws_cmd s3api get-bucket-lifecycle-configuration \
  --bucket "${S3_BUCKETS[0]}" \
  --query 'Rules[0].NoncurrentVersionExpiration' --output json
echo "[verify] SQS queues:"
aws_cmd sqs list-queues
echo "[verify] SQS RedrivePolicy on $SQS_FIFO_QUEUE:"
aws_cmd sqs get-queue-attributes \
  --queue-url "$MAIN_URL" \
  --attribute-names RedrivePolicy \
  --output text --query 'Attributes.RedrivePolicy'
echo "[verify] SNS topics:"
aws_cmd sns list-topics

echo "=============================================================="
echo " CardDemo LocalStack AWS resources ready."
echo "=============================================================="

exit 0
