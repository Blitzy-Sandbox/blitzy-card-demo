#!/usr/bin/env bash
# ******************************************************************
# * Script       : init-aws.sh
# * Application  : CardDemo
# * Type         : LocalStack ready hook (S3 / SQS / SNS provisioning)
# * Function     : Idempotently creates the AWS resources that replace the
# *                mainframe integration constructs of the frozen COBOL corpus.
# *                The 7 generation data group bases defined by
# *                app/jcl/DEFGDGB.jcl (DALYREJS, SYSTRAN, TCATBALF.BKUP,
# *                TRANREPT, TRANSACT.BKUP, TRANSACT.COMBINED, TRANSACT.DALY)
# *                plus app/jcl/DALYREJS.jcl and app/jcl/REPTFILE.jcl become
# *                three S3 buckets with versioning on the output bucket;
# *                DEFINE TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER)
# *                RECORDSIZE(80) RECORDFORMAT(FIXED) in app/csd/CARDDEMO.CSD
# *                becomes one SQS FIFO queue; operator notification becomes
# *                SNS topics. Re-running converges rather than failing, so
# *                repeated `docker compose up` cycles are safe.
# ******************************************************************
# * Copyright Amazon.com, Inc. or its affiliates.
# * All Rights Reserved.
# *
# * Licensed under the Apache License, Version 2.0 (the "License").
# * You may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# *
# *    http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing,
# * software distributed under the License is distributed on an
# * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# * either express or implied. See the License for the specific
# * language governing permissions and limitations under the License
# ******************************************************************
#
# Runs inside the LocalStack container (where `awslocal` is present) and also
# works from the host when AWS_ENDPOINT_URL is exported and the AWS CLI is on
# PATH.

set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"

BATCH_INPUT_BUCKET="${CARDDEMO_BATCH_INPUT_BUCKET:-carddemo-batch-input}"
BATCH_OUTPUT_BUCKET="${CARDDEMO_BATCH_OUTPUT_BUCKET:-carddemo-batch-output}"
STATEMENTS_BUCKET="${CARDDEMO_STATEMENTS_BUCKET:-carddemo-statements}"
REPORT_QUEUE="${CARDDEMO_REPORT_QUEUE:-carddemo-report-jobs.fifo}"
ALERT_TOPIC="${CARDDEMO_ALERT_TOPIC:-carddemo-alerts}"
NOTIFICATION_TOPIC="${CARDDEMO_NOTIFICATION_TOPIC:-carddemo-notifications}"

# Prefer awslocal inside the container; fall back to the plain AWS CLI plus an
# explicit endpoint so the same script is runnable from a developer host.
if command -v awslocal >/dev/null 2>&1; then
  AWS="awslocal"
else
  AWS="aws --endpoint-url=${AWS_ENDPOINT_URL:-http://localhost:4566}"
fi

log() { printf '[init-aws] %s\n' "$*"; }

# --------------------------------------------------------------------------
# S3 - GDG generation replacement. Relative generation references become keys
# under a monotonically increasing prefix; retention limits are documented
# rather than enforced. The TRANREPT retention conflict between
# app/jcl/DEFGDGB.jcl LIMIT(5) and app/jcl/REPTFILE.jcl LIMIT(10) is resolved
# in favour of 10, superseded here by object versioning.
# --------------------------------------------------------------------------
create_bucket() {
  local bucket="$1" versioned="${2:-false}"
  if ${AWS} s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
    log "bucket ${bucket} already exists"
  else
    if [ "${REGION}" = "us-east-1" ]; then
      ${AWS} s3api create-bucket --bucket "${bucket}" >/dev/null
    else
      ${AWS} s3api create-bucket --bucket "${bucket}" \
        --create-bucket-configuration "LocationConstraint=${REGION}" >/dev/null
    fi
    log "created bucket ${bucket}"
  fi
  if [ "${versioned}" = "true" ]; then
    ${AWS} s3api put-bucket-versioning --bucket "${bucket}" \
      --versioning-configuration Status=Enabled >/dev/null
    log "versioning enabled on ${bucket}"
  fi
}

create_bucket "${BATCH_INPUT_BUCKET}"  false
create_bucket "${BATCH_OUTPUT_BUCKET}" true
create_bucket "${STATEMENTS_BUCKET}"   true

# --------------------------------------------------------------------------
# SQS FIFO - replaces EXEC CICS WRITEQ TD QUEUE('JOBS') in
# app/cbl/CORPT00C.cbl and the JES2 internal reader. The queue's declared
# RECORDSIZE(80) RECORDFORMAT(FIXED) fixes the shape of the parameter record
# carried in the message body.
# --------------------------------------------------------------------------
if ${AWS} sqs get-queue-url --queue-name "${REPORT_QUEUE}" >/dev/null 2>&1; then
  log "queue ${REPORT_QUEUE} already exists"
else
  ${AWS} sqs create-queue \
    --queue-name "${REPORT_QUEUE}" \
    --attributes 'FifoQueue=true,ContentBasedDeduplication=true,VisibilityTimeout=300,MessageRetentionPeriod=1209600' \
    >/dev/null
  log "created FIFO queue ${REPORT_QUEUE}"
fi

# --------------------------------------------------------------------------
# SNS - operator notification replacement. create-topic is idempotent.
# --------------------------------------------------------------------------
for topic in "${ALERT_TOPIC}" "${NOTIFICATION_TOPIC}"; do
  arn=$(${AWS} sns create-topic --name "${topic}" --query TopicArn --output text)
  log "topic ready ${arn}"
done

# --------------------------------------------------------------------------
# Summary
# --------------------------------------------------------------------------
log "buckets:"
${AWS} s3api list-buckets --query 'Buckets[].Name' --output text | tr '\t' '\n' | sed 's/^/[init-aws]   /'
log "queues:"
${AWS} sqs list-queues --query 'QueueUrls' --output text 2>/dev/null | tr '\t' '\n' | sed 's/^/[init-aws]   /'
log "topics:"
${AWS} sns list-topics --query 'Topics[].TopicArn' --output text | tr '\t' '\n' | sed 's/^/[init-aws]   /'
log "provisioning complete"
