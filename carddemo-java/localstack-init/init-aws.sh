#!/usr/bin/env bash
# =============================================================================
# CardDemo - LocalStack AWS resource bootstrap
# =============================================================================
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
# -----------------------------------------------------------------------------
# PURPOSE
#   Provisions the AWS resources the CardDemo Java migration depends on, inside
#   the LocalStack container. LocalStack auto-executes scripts mounted under
#   /etc/localstack/init/ready.d/ once the emulator is ready, so this runs on
#   `docker compose up`. It satisfies the LocalStack Verification requirement
#   (every AWS interaction is verifiable locally with zero live AWS access).
#
#   Created resources (canonical declaration shared with docker-compose.yml,
#   config/AwsConfig.java, application-local.yml and application-test.yml):
#     * S3 buckets (versioning enabled):
#         - carddemo-batch-input
#         - carddemo-batch-output
#         - carddemo-statements
#     * SQS FIFO queue : carddemo-report-jobs.fifo
#     * SNS topic      : carddemo-notifications
#
# GDG -> S3 TRACEABILITY (reference: app/jcl/DEFGDGB.jcl, commit SHA 27d6c6f)
#   DEFGDGB.jcl defines 6 generation-data-group bases, each LIMIT(5) + SCRATCH.
#   Generations re-platform to versioned S3 objects (hence versioning is
#   enabled below); statement output adds a third bucket:
#     AWS.M2.CARDDEMO.TRANSACT.DALY      -> input staging  -> carddemo-batch-input
#     AWS.M2.CARDDEMO.TRANSACT.BKUP      -> output/backup  -> carddemo-batch-output
#     AWS.M2.CARDDEMO.TRANSACT.COMBINED  -> batch output   -> carddemo-batch-output
#     AWS.M2.CARDDEMO.SYSTRAN            -> batch output   -> carddemo-batch-output
#     AWS.M2.CARDDEMO.TRANREPT           -> report output  -> carddemo-batch-output
#     AWS.M2.CARDDEMO.TCATBALF.BKUP      -> batch backup   -> carddemo-batch-output
#     statement generation (CREASTMT.JCL / CBSTM03A) -> carddemo-statements
#
#   The SQS FIFO queue re-platforms the CICS TDQ `WRITEQ TD` report-submission
#   bridge (CORPT00C); the SNS topic carries notification fan-out.
#
#   Every create is idempotent and re-runnable, mirroring the JCL
#   `IF LASTCC=12 THEN SET MAXCC=0` pattern in DEFGDGB.jcl.
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------
# This script executes inside the LocalStack container, so the emulator is
# reachable on localhost:4566. Never target a real AWS endpoint.
AWS_ENDPOINT_URL="http://localhost:4566"

# LocalStack accepts any credentials; "test"/"test" are its well-known dummy
# values. Real values (if already exported by docker-compose) are honored via
# the parameter-expansion fallbacks. No real keys or auth tokens are baked in.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

# Resource names - the binding contract; do not deviate.
S3_BUCKETS=(
  "carddemo-batch-input"
  "carddemo-batch-output"
  "carddemo-statements"
)
SQS_FIFO_QUEUE="carddemo-report-jobs.fifo"
SNS_TOPIC="carddemo-notifications"

# Thin wrapper that pins every AWS CLI call to the LocalStack endpoint.
aws_cmd() {
  aws --endpoint-url="${AWS_ENDPOINT_URL}" "$@"
}

echo "==> Initializing CardDemo LocalStack AWS resources (endpoint: ${AWS_ENDPOINT_URL})..."

# -----------------------------------------------------------------------------
# S3 buckets (+ versioning)
# -----------------------------------------------------------------------------
# Region us-east-1 must NOT pass a LocationConstraint (doing so is an error in
# that region). The head-bucket guard runs inside an `if`, so its non-zero exit
# is consumed by the conditional and does not trip `set -e` on re-runs.
for bucket in "${S3_BUCKETS[@]}"; do
  if aws_cmd s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
    echo "    [S3] bucket already exists: ${bucket}"
  else
    aws_cmd s3api create-bucket --bucket "${bucket}" >/dev/null
    echo "    [S3] created bucket: ${bucket}"
  fi

  # Enable versioning so S3 object versions stand in for GDG generations.
  # Re-applying Status=Enabled is a no-op, so this is safe on every run.
  aws_cmd s3api put-bucket-versioning \
    --bucket "${bucket}" \
    --versioning-configuration Status=Enabled >/dev/null
  echo "    [S3] versioning enabled: ${bucket}"
done

# -----------------------------------------------------------------------------
# SQS FIFO queue (CICS TDQ replacement)
# -----------------------------------------------------------------------------
# FIFO preserves the point-to-point ordering of the original CICS transient
# data queue. The get-queue-url guard keeps the create idempotent under set -e.
if aws_cmd sqs get-queue-url --queue-name "${SQS_FIFO_QUEUE}" >/dev/null 2>&1; then
  echo "    [SQS] FIFO queue already exists: ${SQS_FIFO_QUEUE}"
else
  # ContentBasedDeduplication is DISABLED (DECISION_LOG D-020): the report producer
  # (ReportSubmissionService) supplies a per-message random UUID deduplication id, so
  # legitimately repeated report submissions are each enqueued (preserving the CICS
  # WRITEQ TD repeat-submission semantics) instead of being collapsed by a body hash.
  aws_cmd sqs create-queue \
    --queue-name "${SQS_FIFO_QUEUE}" \
    --attributes FifoQueue=true,ContentBasedDeduplication=false >/dev/null
  echo "    [SQS] created FIFO queue: ${SQS_FIFO_QUEUE}"
fi

# -----------------------------------------------------------------------------
# SNS topic (notification fan-out)
# -----------------------------------------------------------------------------
# create-topic is naturally idempotent: it returns the existing TopicArn when
# the topic already exists, so it is safe to call unconditionally under set -e.
SNS_TOPIC_ARN="$(aws_cmd sns create-topic --name "${SNS_TOPIC}" --output text --query 'TopicArn')"
echo "    [SNS] topic ready: ${SNS_TOPIC} (${SNS_TOPIC_ARN})"

# -----------------------------------------------------------------------------
# Completion - summarize provisioned resources for log visibility
# -----------------------------------------------------------------------------
echo "==> Provisioned resource summary:"
aws_cmd s3 ls
aws_cmd sqs list-queues
aws_cmd sns list-topics
echo "==> CardDemo LocalStack AWS resources ready."

exit 0
