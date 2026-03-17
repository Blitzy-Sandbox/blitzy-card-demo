#!/bin/bash
# =============================================================================
# CardDemo — LocalStack AWS Resource Provisioning Script
# =============================================================================
#
# Purpose:
#   Provisions all AWS resources (S3 buckets, SQS queues, SNS topics) required
#   by the CardDemo Java/Spring Boot application in LocalStack.
#
# Execution Context:
#   This script is mounted into the LocalStack container at:
#     /etc/localstack/init/ready.d/init-aws.sh
#   via docker-compose.yml volume mapping:
#     ./localstack-init:/etc/localstack/init/ready.d
#   It runs automatically after all LocalStack services are healthy and ready.
#
# Source Mapping (COBOL/JCL → AWS):
#   - DEFGDGB.jcl   → 6 GDG bases (LIMIT 5, SCRATCH) → S3 buckets with versioning
#     - AWS.M2.CARDDEMO.TRANSACT.BKUP      → s3://carddemo-batch-output
#     - AWS.M2.CARDDEMO.TRANSACT.DALY       → s3://carddemo-batch-output
#     - AWS.M2.CARDDEMO.TRANREPT            → s3://carddemo-batch-output
#     - AWS.M2.CARDDEMO.TCATBALF.BKUP       → s3://carddemo-batch-output
#     - AWS.M2.CARDDEMO.SYSTRAN             → s3://carddemo-batch-output
#     - AWS.M2.CARDDEMO.TRANSACT.COMBINED   → s3://carddemo-batch-output
#   - DALYREJS.jcl   → DALYREJS GDG (LIMIT 5, SCRATCH) → s3://carddemo-batch-output (rejection prefix)
#   - REPTFILE.jcl   → TRANREPT GDG (LIMIT 10) → s3://carddemo-batch-output
#   - POSTTRAN.jcl   → DALYTRAN.PS dataset input → s3://carddemo-batch-input
#   - CREASTMT.JCL   → STMTFILE + HTMLFILE output → s3://carddemo-statements
#   - CORPT00C.cbl   → EXEC CICS WRITEQ TD QUEUE('JOBS') → SQS FIFO carddemo-report-jobs.fifo
#
# Resources Created:
#   S3 Buckets:
#     - carddemo-batch-input    : Daily transaction file staging (replaces DALYTRAN PS)
#     - carddemo-batch-output   : All batch processing output (replaces 6 GDG bases + rejections)
#     - carddemo-statements     : Generated statement files in text and HTML format
#   SQS Queues:
#     - carddemo-report-jobs.fifo : FIFO queue for online-to-batch report submission bridge
#   SNS Topics:
#     - carddemo-alerts         : Application alert and notification publishing
#
# Design Decisions:
#   - D-003: S3 versioning on output buckets replaces GDG generation numbering semantics
#   - D-004: SQS FIFO replaces CICS TDQ for point-to-point ordered message delivery
#   - ContentBasedDeduplication on FIFO queue eliminates need for explicit dedup IDs
#
# Prerequisites:
#   - awslocal CLI wrapper (pre-installed in LocalStack container)
#   - No external AWS credentials required (LocalStack handles all auth internally)
#
# Note: Make this script executable: chmod +x localstack-init/init-aws.sh
# =============================================================================

set -euo pipefail

echo "========================================"
echo "Initializing CardDemo AWS Resources..."
echo "========================================"

# =============================================================================
# Phase 1: S3 Bucket Creation
# =============================================================================
# Create 3 S3 buckets that replace the COBOL/JCL dataset provisioning:
#   - carddemo-batch-input  : Replaces DALYTRAN.PS sequential dataset (POSTTRAN.jcl)
#   - carddemo-batch-output : Replaces 6 GDG bases from DEFGDGB.jcl + DALYREJS GDG
#   - carddemo-statements   : Replaces CREASTMT.JCL STMTFILE + HTMLFILE output
# =============================================================================

echo ""
echo "--- Creating S3 Buckets ---"

# Bucket 1: carddemo-batch-input
# Purpose: Daily transaction file staging area
# Replaces: AWS.M2.CARDDEMO.DALYTRAN.PS dataset from POSTTRAN.jcl (line 30-31)
# Used by: DailyTransactionReader.java to read batch input files
echo "Creating S3 bucket: carddemo-batch-input"
awslocal s3 mb s3://carddemo-batch-input

# Bucket 2: carddemo-batch-output
# Purpose: All batch processing output files
# Replaces: 6 GDG bases from DEFGDGB.jcl:
#   - AWS.M2.CARDDEMO.TRANSACT.BKUP      (transaction backups)
#   - AWS.M2.CARDDEMO.TRANSACT.DALY       (daily transaction archives)
#   - AWS.M2.CARDDEMO.TRANREPT            (transaction reports)
#   - AWS.M2.CARDDEMO.TCATBALF.BKUP       (category balance backups)
#   - AWS.M2.CARDDEMO.SYSTRAN             (system transaction staging)
#   - AWS.M2.CARDDEMO.TRANSACT.COMBINED   (combined transaction files)
# Also replaces: DALYREJS GDG from DALYREJS.jcl (batch rejection files)
# Used by: TransactionWriter.java, RejectWriter.java, batch pipeline jobs
echo "Creating S3 bucket: carddemo-batch-output"
awslocal s3 mb s3://carddemo-batch-output

# Bucket 3: carddemo-statements
# Purpose: Generated statement files in text and HTML format
# Replaces: CREASTMT.JCL output datasets:
#   - AWS.M2.CARDDEMO.STATEMNT.PS   (STMTFILE, LRECL=80, RECFM=FB)
#   - AWS.M2.CARDDEMO.STATEMNT.HTML (HTMLFILE, LRECL=100, RECFM=FB)
# Used by: StatementWriter.java for dual-format statement output
echo "Creating S3 bucket: carddemo-statements"
awslocal s3 mb s3://carddemo-statements

echo "S3 buckets created successfully."

# =============================================================================
# Phase 2: SQS Queue Creation
# =============================================================================
# Create FIFO queue replacing the CICS Transient Data Queue (TDQ)
# The online-to-batch bridge in CORPT00C.cbl uses:
#   EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) (line 517-520)
# SQS FIFO guarantees ordering matching sequential TDQ read semantics
# =============================================================================

echo ""
echo "--- Creating SQS Queues ---"

# Queue: carddemo-report-jobs.fifo
# Purpose: Online-to-batch bridge for report submission
# Replaces: CICS TDQ 'JOBS' queue from CORPT00C.cbl
# Publisher: ReportSubmissionService.java
# Consumer: Batch job trigger (Spring Batch listener)
# FIFO: Preserves sequential ordering matching CICS TDQ READQ TD semantics
# ContentBasedDeduplication: Enabled to simplify publishing (no explicit dedup ID)
echo "Creating SQS FIFO queue: carddemo-report-jobs.fifo"
awslocal sqs create-queue \
    --queue-name carddemo-report-jobs.fifo \
    --attributes '{"FifoQueue":"true","ContentBasedDeduplication":"true"}'

echo "SQS queues created successfully."

# =============================================================================
# Phase 3: SNS Topic Creation
# =============================================================================
# Create SNS topic for application alert and notification fan-out
# =============================================================================

echo ""
echo "--- Creating SNS Topics ---"

# Topic: carddemo-alerts
# Purpose: Application alert and notification publishing
# Used by: Application components for alert fan-out (batch failures, threshold alerts)
echo "Creating SNS topic: carddemo-alerts"
awslocal sns create-topic --name carddemo-alerts

echo "SNS topics created successfully."

# =============================================================================
# Phase 4: Enable S3 Versioning
# =============================================================================
# Per Decision D-003: GDG semantics require generation numbering and retention;
# S3 versioning provides the native equivalent.
# DEFGDGB.jcl defines GDG bases with LIMIT(5) SCRATCH, which means:
#   - Up to 5 generations are retained
#   - Oldest generation is scratched (deleted) when limit exceeded
# S3 versioning preserves all object versions; lifecycle policies can enforce
# retention limits equivalent to GDG LIMIT.
# =============================================================================

echo ""
echo "--- Enabling S3 Versioning ---"

# Enable versioning on carddemo-batch-output
# Reason: Stores generation-equivalent versioned objects for all batch output
# Maps to: GDG LIMIT(5) SCRATCH semantics from DEFGDGB.jcl
echo "Enabling versioning on carddemo-batch-output"
awslocal s3api put-bucket-versioning \
    --bucket carddemo-batch-output \
    --versioning-configuration Status=Enabled

# Enable versioning on carddemo-statements
# Reason: Statement generation creates new versions for each batch run cycle
# Maps to: CREASTMT.JCL creating new STMTFILE/HTMLFILE each execution
echo "Enabling versioning on carddemo-statements"
awslocal s3api put-bucket-versioning \
    --bucket carddemo-statements \
    --versioning-configuration Status=Enabled

echo "S3 versioning enabled successfully."

# =============================================================================
# Phase 5: Verification
# =============================================================================
# List all created resources to confirm successful provisioning
# =============================================================================

echo ""
echo "--- Verifying Created Resources ---"

echo ""
echo "Verifying S3 buckets:"
awslocal s3 ls

echo ""
echo "Verifying SQS queues:"
awslocal sqs list-queues

echo ""
echo "Verifying SNS topics:"
awslocal sns list-topics

# =============================================================================
# Phase 6: Completion
# =============================================================================

echo ""
echo "========================================"
echo "CardDemo AWS Resources initialized successfully!"
echo "  S3 Buckets: carddemo-batch-input, carddemo-batch-output, carddemo-statements"
echo "  SQS Queues: carddemo-report-jobs.fifo"
echo "  SNS Topics: carddemo-alerts"
echo "========================================"
