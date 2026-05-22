#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# LocalStack initialization script (runs at ready.d when LocalStack starts).
# Provisions the local AWS resources required by the CardDemo Spring Boot
# application: S3 buckets, KMS keys, Secrets Manager entries, SQS queues, etc.
#
# Per AAP §0.3.1: "localstack/init/init-aws.sh - LocalStack initialization
# scripts (create local S3 buckets, MSK topics, Secrets Manager entries,
# KMS keys)"
# ---------------------------------------------------------------------------
set -euo pipefail

LS_ENDPOINT="${LS_ENDPOINT:-http://localhost:4566}"
AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ACCT="000000000000"

aws_cli() {
    aws --endpoint-url "${LS_ENDPOINT}" --region "${AWS_DEFAULT_REGION}" "$@"
}

echo "[init-aws] Provisioning LocalStack resources at ${LS_ENDPOINT}"

# --- KMS customer-managed keys (per PCI-DSS encryption-at-rest requirement) ---
echo "[init-aws] Creating KMS customer-managed keys"
aws_cli kms create-key \
    --description "CardDemo CMK for RDS / S3 / ElastiCache / CloudWatch" \
    --tags TagKey=Project,TagValue=CardDemo > /tmp/cmk.json || true

# --- S3 buckets (replaces GDG generations) ---
echo "[init-aws] Creating S3 buckets"
for BUCKET in carddemo-output carddemo-statements carddemo-reports carddemo-rejects carddemo-backup; do
    aws_cli s3 mb "s3://${BUCKET}" || true
    aws_cli s3api put-bucket-versioning \
        --bucket "${BUCKET}" \
        --versioning-configuration Status=Enabled || true
done

# --- Secrets Manager entries (placeholder seeds; Spring Cloud AWS imports at boot) ---
echo "[init-aws] Seeding Secrets Manager entries"
aws_cli secretsmanager create-secret \
    --name "carddemo/rds" \
    --secret-string '{"username":"carddemo","password":"carddemo","host":"postgres","port":5432,"database":"carddemo"}' \
    --description "RDS PostgreSQL credentials (placeholder)" \
    > /dev/null 2>&1 || true

aws_cli secretsmanager create-secret \
    --name "carddemo/jwt-signing-key" \
    --secret-string '{"key":"DEVELOPMENT_ONLY_JWT_SIGNING_KEY_MIN_256_BITS_FOR_HS256_ALGORITHM"}' \
    --description "JWT signing key (placeholder)" \
    > /dev/null 2>&1 || true

# --- SQS queues (used by Secrets Manager rotation listener etc.) ---
echo "[init-aws] Creating SQS queues"
aws_cli sqs create-queue --queue-name carddemo-secret-rotations > /dev/null 2>&1 || true
aws_cli sqs create-queue --queue-name carddemo-dead-letter > /dev/null 2>&1 || true

# --- SNS topics ---
echo "[init-aws] Creating SNS topics"
aws_cli sns create-topic --name carddemo-secret-rotation-events > /dev/null 2>&1 || true
aws_cli sns create-topic --name carddemo-macie-findings > /dev/null 2>&1 || true

# --- Parameter Store entries (non-sensitive configuration) ---
echo "[init-aws] Seeding SSM Parameter Store"
aws_cli ssm put-parameter \
    --name "/carddemo/config/region" \
    --value "${AWS_DEFAULT_REGION}" \
    --type String \
    --overwrite > /dev/null 2>&1 || true

aws_cli ssm put-parameter \
    --name "/carddemo/config/output-bucket" \
    --value "carddemo-output" \
    --type String \
    --overwrite > /dev/null 2>&1 || true

echo "[init-aws] LocalStack initialization complete"
