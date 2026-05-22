#!/bin/sh
# shellcheck shell=sh disable=SC2086,SC2317
# =============================================================================
# CardDemo — LocalStack Initialization Script
# =============================================================================
#
# Purpose
# -------
# POSIX shell script that LocalStack Pro 4.14.0 automatically executes after
# the container reaches the "ready" state via its init lifecycle hook. This
# script is the single source of truth for local AWS resource provisioning
# inside the LocalStack emulator and provisions every local AWS resource that
# the CardDemo Spring Boot 3.x application (profile `local`) expects from
# `src/main/resources/application-local.yml`, plus the matching set of local
# AWS resources that mirror the production `infrastructure/terraform/*.tf`
# definitions for parity testing.
#
# Activation Mechanism
# --------------------
# LocalStack scans `/etc/localstack/init/ready.d/*.sh` after reporting `READY`
# on `/_localstack/health` and runs every executable script it finds. The
# project's `docker-compose.yml` bind-mounts `./localstack/init` to that path
# read-only so this file is discovered and executed once per container start.
#
# Resources Provisioned (in order)
# --------------------------------
#   [1/8] KMS Customer Master Key (CMK) + alias `alias/carddemo-local`
#         — mirrors `infrastructure/terraform/kms.tf` (AAP §0.6.6 PCI-DSS:
#         data-at-rest encryption via KMS CMK).
#
#   [2/8] 6 S3 buckets with versioning enabled (mirror
#         `infrastructure/terraform/s3.tf`):
#           * carddemo-output-local      — default catch-all (S3_OUTPUT_BUCKET)
#           * carddemo-rejects-local     — replaces DALYREJS GDG
#                                           (app/jcl/POSTTRAN.jcl DALYREJS DD)
#           * carddemo-reports-local     — replaces TRANREPT GDG
#                                           (app/jcl/TRANREPT.jcl)
#           * carddemo-statements-local  — replaces STMTFILE GDG
#                                           (app/jcl/CREASTMT.JCL)
#           * carddemo-backup-local      — replaces TRANSACT.BKUP GDG
#                                           (app/jcl/TRANBKP.jcl)
#           * carddemo-systran-local     — replaces SYSTRAN GDG
#                                           (app/jcl/INTCALC.jcl)
#         — Per AAP §0.6.2 each bucket has versioning enabled so the GDG
#         (+1)/(0) generation semantics survive as S3 object versions.
#
#   [3/8] 4 Secrets Manager secrets loaded from secrets.json (mirror
#         `infrastructure/terraform/secrets.tf`):
#           * carddemo-rds-credentials
#           * carddemo/local/jwt-signing-key
#           * carddemo/local/msk-credentials
#           * carddemo/local/opensearch-credentials
#         — Per AAP §0.7.1 every value is a known local-development placeholder;
#         no real credentials are stored in the repository.
#
#   [4/8] MSK / Kafka topic discovery (4 topics from AAP §0.6.5:
#         transaction.posted, account.updated, ledger.balanced,
#         report.requested). LocalStack Community editions do not emulate the
#         MSK admin API; the local Kafka broker handles topic auto-creation
#         via Spring Kafka's KafkaAdmin bean at application startup, so this
#         phase logs the topic inventory and degrades gracefully.
#
#   [5/8] 2 Step Functions state machines (mirror
#         `infrastructure/terraform/stepfunctions.tf`):
#           * eod-batch-pipeline — POSTTRAN → INTCALC → COMBTRAN →
#                                  Parallel{CREASTMT, TRANREPT}
#             (app/jcl/POSTTRAN.jcl, INTCALC.jcl, COMBTRAN.jcl, CREASTMT.JCL,
#             TRANREPT.jcl, TRANBKP.jcl)
#           * file-provisioning  — Flyway migrations + parallel Glue bulk loads
#         — Per AAP §0.6.3 each state machine replaces the equivalent JCL job
#         chain. When the production ASL definitions are mounted at
#         /etc/localstack/init/ready.d/asl/<name>.asl.json they are loaded
#         verbatim; otherwise a Pass-state stub keeps the state-machine ARN
#         available to the Spring application without invoking real
#         transitions.
#
#   [6/8] 1 OpenSearch domain `carddemo-audit-local` (mirrors
#         `infrastructure/terraform/opensearch.tf`)
#         — Per AAP §0.6.6 OpenSearch indexes transaction logs and CloudTrail
#         events for fraud investigation and regulatory queries.
#
#   [7/8] 3 IAM roles (mirror `infrastructure/terraform/iam.tf`):
#           * carddemo-local-ecs-task-role
#           * carddemo-local-batch-job-role
#           * carddemo-local-step-functions-execution
#         — LocalStack does not enforce IAM in dev mode but the roles must
#         exist so ECS/Batch/Step Functions API calls can reference them by
#         name without raising NoSuchEntity errors.
#
#   [8/8] 1 SNS topic + 1 SQS queue with SQS subscribed to the SNS (mirror
#         `infrastructure/terraform/secrets.tf` rotation flow):
#           * SNS topic: carddemo-local-secrets-rotation
#           * SQS queue: carddemo-local-secrets-rotation-app
#         — Per AAP §0.6.4 this SNS/SQS pair simulates the production
#         EventBridge → SNS → SQS chain that triggers @RefreshScope bean
#         refresh in the Spring application after Secrets Manager rotation.
#
# Environment Contract
# --------------------
#   AWS_REGION              defaults to us-east-1
#   AWS_ACCESS_KEY_ID       defaults to "test" (LocalStack convention)
#   AWS_SECRET_ACCESS_KEY   defaults to "test" (LocalStack convention)
#   ENDPOINT_URL            defaults to http://localhost:4566 (LocalStack edge)
#   SECRETS_FILE            defaults to /etc/localstack/init/ready.d/secrets.json
#
# File-Level Requirements
# -----------------------
#   * Shebang `#!/bin/sh` for POSIX/dash/BusyBox compatibility (LocalStack
#     base image is Alpine/Debian variants).
#   * Mode 0755 (rwxr-xr-x) so LocalStack can exec it without sourcing.
#   * LF line endings (Unix), UTF-8 encoding without BOM, trailing newline.
#   * Idempotent on restart: every `create-*` invocation is wrapped via
#     `safe_run` so the script re-runs cleanly after `docker compose restart`.
#
# References
# ----------
#   AAP §0.6.2 — VSAM → RDS + S3 versioned objects replacing GDG generations
#   AAP §0.6.3 — JCL job streams → Step Functions state machines
#   AAP §0.6.4 — Secrets Manager dynamic rotation via SNS → SQS notification
#   AAP §0.6.5 — MSK topics: partition-by-account ordering guarantee
#   AAP §0.6.6 — PCI-DSS observability via KMS + OpenSearch + CloudTrail
#   AAP §0.7.1 — No plaintext credentials (the secrets.json placeholder pattern)
#   AAP §0.7.2 — LocalStack 4.14.0 environment instructions
# =============================================================================

# -----------------------------------------------------------------------------
# Strict-mode shell options.
# `set -e` and `set -u` are universally supported by every POSIX shell.
# `set -o pipefail` is available in dash, bash, busybox sh, and ash since
# 2017+; the separate line keeps the script tolerant of any future shell
# regression that does not advertise pipefail (the script will still abort
# on the first errored simple command via -e).
# -----------------------------------------------------------------------------
set -eu
# shellcheck disable=SC3040
if (set -o pipefail 2>/dev/null); then
  set -o pipefail
fi

# =============================================================================
# Phase 2 — Environment and helper variables
# =============================================================================

AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="${AWS_REGION}"
export AWS_REGION
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"

# LocalStack edge endpoint. Overridable for cross-host / cross-network setups
# (e.g., when the script runs from a sidecar container that addresses
# LocalStack via the Compose service name `localstack:4566`).
ENDPOINT_URL="${ENDPOINT_URL:-http://localhost:4566}"

# Single quoted variable expansion preserves the multi-word command so it can
# be invoked unquoted later. We intentionally rely on word-splitting of
# $AWSLOCAL when used in commands — this is a deliberate idiom and the
# directive in the file header (SC2086 disable) acknowledges it.
AWSLOCAL="aws --endpoint-url=${ENDPOINT_URL} --region ${AWS_REGION}"

# Path to the JSON seed file holding placeholder values for the 4 Secrets
# Manager secrets. The default matches the LocalStack init-hook mount
# destination from docker-compose.yml; an override is supported for ad-hoc
# host-side execution against a running LocalStack container.
SECRETS_FILE="${SECRETS_FILE:-/etc/localstack/init/ready.d/secrets.json}"

# Directory containing optional ASL definitions for Step Functions state
# machines. When mounted from src/main/resources/stepfunctions/ via Compose,
# these files override the in-script Pass-state stub for production-grade
# parity testing.
ASL_DIR="${ASL_DIR:-/etc/localstack/init/ready.d/asl}"

# -----------------------------------------------------------------------------
# log — print a status line with a consistent `[init-aws.sh]` prefix so
# operators can filter container logs via
#     docker compose logs localstack | grep '\[init-aws.sh\]'
# -----------------------------------------------------------------------------
log() {
  echo "[init-aws.sh] $*"
}

# -----------------------------------------------------------------------------
# safe_run — invoke a command, swallow any non-zero exit so the script does
# not abort on `AlreadyExists` / `ResourceInUseException` / similar idempotent
# errors. LocalStack 3.x/4.x is restart-safe and re-runs this script every
# container start. The "(already exists or non-fatal error — continuing)"
# notice is the documented signal that an idempotent create call was a no-op
# on second run.
# -----------------------------------------------------------------------------
safe_run() {
  if "$@" >/dev/null 2>&1; then
    return 0
  fi
  log "  (already exists or non-fatal error — continuing)"
  return 0
}

# =============================================================================
# Phase 3 — Tooling availability check
# =============================================================================

if ! command -v aws >/dev/null 2>&1; then
  log "ERROR: aws CLI not found in PATH — cannot provision LocalStack"
  log "       Install via 'pip install awscli' per AAP §0.7.2"
  exit 1
fi

# jq is preferred for JSON parsing of secrets.json; we fall back to a
# POSIX sed/grep extractor if jq is absent (LocalStack base images vary
# in tool inventory).
HAS_JQ=0
if command -v jq >/dev/null 2>&1; then
  HAS_JQ=1
fi

log "=========================================================="
log " CardDemo LocalStack initialization starting"
log "=========================================================="
log " Endpoint:      ${ENDPOINT_URL}"
log " Region:        ${AWS_REGION}"
log " Secrets file:  ${SECRETS_FILE}"
log " ASL directory: ${ASL_DIR}"
log " jq available:  $( [ "${HAS_JQ}" -eq 1 ] && echo yes || echo "no — using POSIX fallback" )"
log "=========================================================="

# =============================================================================
# Phase 4 — KMS Customer Master Key (CMK) + alias
# =============================================================================

log "[1/8] Creating KMS Customer Master Key..."

# Try to create the CMK. The KMS create-key call is not naturally idempotent
# (each invocation generates a fresh KeyId), so we first check whether the
# alias already resolves to an existing key — if it does, we reuse it.
EXISTING_KEY_ID="$( $AWSLOCAL kms list-aliases \
    --query "Aliases[?AliasName=='alias/carddemo-local'].TargetKeyId | [0]" \
    --output text 2>/dev/null || echo "" )"

if [ -n "${EXISTING_KEY_ID}" ] && [ "${EXISTING_KEY_ID}" != "None" ]; then
  KMS_KEY_ID="${EXISTING_KEY_ID}"
  log "  KMS alias alias/carddemo-local already exists (KeyId=${KMS_KEY_ID})"
else
  KMS_KEY_ID="$( $AWSLOCAL kms create-key \
      --description "CardDemo local CMK (mirrors infrastructure/terraform/kms.tf)" \
      --key-usage ENCRYPT_DECRYPT \
      --customer-master-key-spec SYMMETRIC_DEFAULT \
      --tags TagKey=Project,TagValue=CardDemo TagKey=Environment,TagValue=local \
      --query KeyMetadata.KeyId --output text 2>/dev/null || echo "" )"
  if [ -z "${KMS_KEY_ID}" ]; then
    log "  WARNING: KMS create-key returned no KeyId — KMS service may be unavailable"
  else
    log "  KMS Key ID: ${KMS_KEY_ID}"
  fi
fi

if [ -n "${KMS_KEY_ID}" ] && [ "${KMS_KEY_ID}" != "None" ]; then
  log "  Creating KMS alias alias/carddemo-local..."
  safe_run $AWSLOCAL kms create-alias \
      --alias-name alias/carddemo-local \
      --target-key-id "${KMS_KEY_ID}"
fi

# =============================================================================
# Phase 5 — S3 buckets (6 buckets with versioning enabled)
# =============================================================================
#
# Each bucket below corresponds to a legacy mainframe GDG (Generation Data
# Group) output dataset documented in app/jcl/. With AAP §0.6.2's S3-replaces-
# GDG mapping, the GDG's (+1)/(0) generation semantics are preserved as
# S3 object versions enabled via put-bucket-versioning. Production parity:
# infrastructure/terraform/s3.tf creates equivalent buckets with SSE-KMS and
# lifecycle policies.
# =============================================================================

log "[2/8] Creating S3 buckets..."

# POSIX `for ... in` loop with backslash continuation works in dash, busybox
# sh, ash, and bash. Each bucket name matches the value consumed by
# src/main/resources/application-local.yml (output-bucket: carddemo-output-local).
for BUCKET in \
    carddemo-output-local \
    carddemo-rejects-local \
    carddemo-reports-local \
    carddemo-statements-local \
    carddemo-backup-local \
    carddemo-systran-local; do
  log "  Creating s3://${BUCKET}..."
  # Bucket creation is rejected with BucketAlreadyOwnedByYou on restart; we
  # rely on safe_run to absorb that case.
  safe_run $AWSLOCAL s3api create-bucket --bucket "${BUCKET}"

  # Enable versioning so deleting an object keeps a delete-marker plus all
  # prior versions, mirroring the mainframe GDG ROLLED-OFF generation
  # retention behaviour. put-bucket-versioning is idempotent on identical
  # input, so we don't need safe_run, but we still apply it defensively
  # in case the bucket itself failed to create on this run.
  safe_run $AWSLOCAL s3api put-bucket-versioning \
      --bucket "${BUCKET}" \
      --versioning-configuration Status=Enabled
done

# =============================================================================
# Phase 6 — Secrets Manager (4 secrets loaded from secrets.json)
# =============================================================================
#
# secrets.json is a flat top-level object mapping secret name to a JSON
# value object (see localstack/init/secrets.json). The script reads each
# secret using jq when available, falling back to a POSIX sed/grep extractor
# that works for the known shape of the file. Both paths produce a compact
# JSON string suitable for `aws secretsmanager create-secret --secret-string`.
# Per AAP §0.7.1 every value in secrets.json is a known local-development
# placeholder — no real credentials live in the repository.
# =============================================================================

# -----------------------------------------------------------------------------
# read_secret_json — extract the JSON value associated with a key in
# secrets.json. Uses jq if available, otherwise a POSIX-only extractor that
# starts at the opening `{` after the key and ends at the matching closing
# `}`. The fallback assumes a single level of nesting (matches the actual
# secrets.json schema documented in the file's coordination notes).
# -----------------------------------------------------------------------------
read_secret_json() {
  SECRET_KEY="$1"
  if [ "${HAS_JQ}" -eq 1 ]; then
    # `jq -c` produces compact JSON; `-r` would unwrap strings and break
    # objects. The `--arg` flag is the safest way to look up a key that
    # itself contains slashes (e.g., carddemo/local/jwt-signing-key).
    jq -c --arg k "${SECRET_KEY}" '.[$k]' "${SECRETS_FILE}"
  else
    # POSIX fallback (awk + sed):
    #   1. Locate the line containing the key followed by `: {`.
    #   2. From that line, capture content through the closing `}` whose
    #      depth returns to zero relative to the opening `{`.
    #   3. Strip everything up to (and including) the opening brace on the
    #      first emitted line so the output starts with `{`.
    #   4. Strip the trailing `,` (if any) ON THE CLOSING-BRACE LINE ONLY —
    #      i.e., the comma that the outer JSON object placed AFTER our
    #      value's closing brace. Inner separator commas between fields
    #      MUST be preserved or the JSON becomes invalid.
    awk -v key="${SECRET_KEY}" '
      BEGIN { depth=0; found=0; }
      {
        if (!found) {
          # Match a line like:   "<key>": {
          if (index($0, "\"" key "\"") > 0 && index($0, "{") > 0) {
            sub(/^[^{]*/, "");
            found=1;
          }
        }
        if (found) {
          # Track brace depth so nested objects do not terminate too early.
          n = gsub(/\{/, "{");
          m = gsub(/\}/, "}");
          depth += n;
          depth -= m;
          if (depth <= 0) {
            # Closing-brace line: strip a trailing `,` if present
            # (POSIX-compatible sub).
            sub(/\},[[:space:]]*$/, "}");
            print;
            exit;
          }
          print;
        }
      }
    ' "${SECRETS_FILE}"
  fi
}

log "[3/8] Creating Secrets Manager secrets..."

if [ ! -f "${SECRETS_FILE}" ]; then
  log "  WARNING: ${SECRETS_FILE} not found — skipping Secrets Manager seeding"
else
  for SECRET_NAME in \
      "carddemo-rds-credentials" \
      "carddemo/local/jwt-signing-key" \
      "carddemo/local/msk-credentials" \
      "carddemo/local/opensearch-credentials"; do
    log "  Creating secret: ${SECRET_NAME}"
    SECRET_VALUE="$( read_secret_json "${SECRET_NAME}" )"
    # `jq` returns the literal string `null` for missing keys; the POSIX
    # fallback returns an empty string. Both cases mean "skip".
    if [ -z "${SECRET_VALUE}" ] || [ "${SECRET_VALUE}" = "null" ]; then
      log "    WARNING: ${SECRET_NAME} not found in ${SECRETS_FILE}; skipping"
      continue
    fi
    # Probe-then-write idiom: if the secret already exists, write a new
    # version via put-secret-value (idempotent for identical content);
    # otherwise create-secret. This avoids the InvalidRequestException that
    # `create-secret` raises on duplicate names.
    if $AWSLOCAL secretsmanager describe-secret \
          --secret-id "${SECRET_NAME}" >/dev/null 2>&1; then
      safe_run $AWSLOCAL secretsmanager put-secret-value \
          --secret-id "${SECRET_NAME}" \
          --secret-string "${SECRET_VALUE}"
    else
      safe_run $AWSLOCAL secretsmanager create-secret \
          --name "${SECRET_NAME}" \
          --description "CardDemo local-dev placeholder secret (per AAP §0.7.1)" \
          --secret-string "${SECRET_VALUE}"
    fi
  done
fi

# =============================================================================
# Phase 7 — MSK / Kafka topics (4 topics)
# =============================================================================
#
# LocalStack Community does NOT emulate the AWS MSK admin API; LocalStack Pro
# 4.14.0 provides partial coverage but topic management still routes through
# a backing Kafka broker. The CardDemo Compose stack runs a dedicated
# bitnami/kafka container that handles real Kafka operations on port 9092;
# Spring Kafka's KafkaAdmin bean (configured by KafkaConfig.java) auto-
# creates the 4 topics at application startup. This phase therefore probes
# the LocalStack MSK API and logs the topic inventory for operator
# verification — the actual provisioning happens at app start.
#
# Per AAP §0.6.5:
#   * Topics use 12 partitions in local (production: 12+, RF=3)
#   * Partition key = ACCT-ID (or USER-ID for report.requested)
#   * acks=all + enable.idempotence=true guarantees per-account ordering
# =============================================================================

log "[4/8] Creating MSK/Kafka topics..."

# Probe whether the MSK control-plane API is responsive on this LocalStack
# instance. The kafka list-clusters call returns exit 0 on responsive (even
# with empty body) and non-zero on InternalFailure for community edition.
MSK_AVAILABLE=0
if $AWSLOCAL kafka list-clusters >/dev/null 2>&1; then
  MSK_AVAILABLE=1
  log "  LocalStack MSK control-plane responsive."
else
  log "  LocalStack MSK control-plane NOT available (community edition or"
  log "    'kafka' service not in LocalStack SERVICES env var). Topic"
  log "    creation will be performed by the bitnami/kafka broker (auto-"
  log "    create via Spring KafkaAdmin at application startup)."
fi

for TOPIC in \
    "transaction.posted" \
    "account.updated" \
    "ledger.balanced" \
    "report.requested"; do
  if [ "${MSK_AVAILABLE}" -eq 1 ]; then
    log "    Topic ${TOPIC} — will be provisioned via Spring KafkaAdmin at startup"
  else
    log "    Topic ${TOPIC} — will auto-create on first publish via Spring KafkaAdmin"
  fi
done

# =============================================================================
# Phase 8 — Step Functions state machines (2 state machines)
# =============================================================================
#
# Per AAP §0.6.3 the two LocalStack state machines mirror the production
# Step Functions state machines defined under src/main/resources/stepfunctions/.
# At runtime LocalStack init may receive either:
#   (a) the full production ASL JSON mounted at
#       /etc/localstack/init/ready.d/asl/<name>.asl.json (preferred); or
#   (b) no ASL mount, in which case a stub Pass-state definition is used
#       so that the Spring application can still resolve the state machine
#       ARN and exercise the SDK code path end-to-end.
#
# The production ASL definitions contain `${BATCH_JOB_QUEUE_ARN}` and
# `${POSTTRAN_JOB_DEFINITION_ARN}` style placeholders that would need
# Terraform substitution before LocalStack accepts them — that substitution
# is the responsibility of the deployment toolchain, not this init script.
# When no substitution has been performed (i.e., the file still contains
# literal `${VAR}` strings), we fall back to the stub to avoid a
# `InvalidDefinition` error from the StepFunctions emulator.
# =============================================================================

log "[5/8] Creating Step Functions state machines..."

# Compact ASL stub: a single Pass state that ends immediately. Valid against
# the AWS Step Functions Amazon States Language spec.
STUB_ASL='{"Comment":"CardDemo local stub","StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}'

# Step Functions execution role; the actual IAM role is created in Phase 10.
STEPFN_ROLE_ARN="arn:aws:iam::000000000000:role/carddemo-local-step-functions-execution"

for STATE_MACHINE in eod-batch-pipeline file-provisioning; do
  log "  State machine: ${STATE_MACHINE}"
  ASL_FILE="${ASL_DIR}/${STATE_MACHINE}.asl.json"
  if [ -f "${ASL_FILE}" ]; then
    # Reject ASL with unsubstituted Terraform placeholders. The presence of
    # literal `${VAR}` in the definition indicates the file was mounted
    # straight from src/main/resources/stepfunctions/ without templating.
    # shellcheck disable=SC2016
    if grep -q '\${' "${ASL_FILE}" 2>/dev/null; then
      log "    ASL file contains unsubstituted placeholders — falling back to stub"
      DEFINITION="${STUB_ASL}"
    else
      DEFINITION="$( cat "${ASL_FILE}" )"
      log "    Using production ASL definition from ${ASL_FILE}"
    fi
  else
    DEFINITION="${STUB_ASL}"
    log "    No ASL file at ${ASL_FILE} — using Pass-state stub"
  fi
  # create-state-machine is not idempotent; probe-then-update.
  if $AWSLOCAL stepfunctions list-state-machines \
        --query "stateMachines[?name=='${STATE_MACHINE}'].stateMachineArn | [0]" \
        --output text 2>/dev/null | grep -q "^arn:"; then
    log "    State machine ${STATE_MACHINE} already exists — leaving definition unchanged"
  else
    safe_run $AWSLOCAL stepfunctions create-state-machine \
        --name "${STATE_MACHINE}" \
        --definition "${DEFINITION}" \
        --role-arn "${STEPFN_ROLE_ARN}"
  fi
done


# =============================================================================
# Phase 9 — OpenSearch domain
# =============================================================================
#
# Per AAP §0.6.6 OpenSearch indexes:
#   * Transaction audit events from AuditLogService
#   * CloudTrail events replicated for searchable retention
#
# Domain name `carddemo-audit-local` mirrors the production domain (see
# infrastructure/terraform/opensearch.tf). LocalStack runs a single-node
# t3.small.search-class emulation; production deploys a multi-AZ cluster
# with KMS-encrypted storage.
# =============================================================================

log "[6/8] Creating OpenSearch domain carddemo-audit-local..."

# Probe whether the domain already exists; create-domain raises
# ResourceAlreadyExistsException on duplicates and aborts the script.
if $AWSLOCAL opensearch describe-domain \
      --domain-name carddemo-audit-local >/dev/null 2>&1; then
  log "  OpenSearch domain carddemo-audit-local already exists"
else
  safe_run $AWSLOCAL opensearch create-domain \
      --domain-name carddemo-audit-local \
      --engine-version "OpenSearch_2.11" \
      --cluster-config "InstanceType=t3.small.search,InstanceCount=1" \
      --ebs-options "EBSEnabled=true,VolumeType=gp2,VolumeSize=10"
fi

# =============================================================================
# Phase 10 — IAM roles (3 roles)
# =============================================================================
#
# LocalStack does not enforce IAM permissions in dev mode but the roles MUST
# exist by name so that other API calls (Step Functions create-state-machine,
# Batch register-job-definition, ECS task definition) can reference them
# without raising NoSuchEntity. Production parity:
# infrastructure/terraform/iam.tf creates equivalents with the same names
# and trust policies plus inline + managed policies for least-privilege
# access (suppressed locally because LocalStack doesn't enforce them).
# =============================================================================

log "[7/8] Creating IAM roles..."

# Trust policies expressed as compact single-line JSON (newlines would
# require POSIX-portable continuation escaping and add nothing). Each policy
# allows exactly one AWS service principal to assume the role via sts:AssumeRole.
TRUST_POLICY_ECS='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
TRUST_POLICY_BATCH='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"batch.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
TRUST_POLICY_SFN='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

# Pair each role name with its trust policy. The `:` separator works because
# the role names themselves never contain a colon (project naming convention).
# POSIX parameter expansion `${VAR%%:*}` and `${VAR#*:}` extract the parts.
create_iam_role() {
  ROLE_NAME="$1"
  TRUST_POLICY="$2"
  log "  Role: ${ROLE_NAME}"
  if $AWSLOCAL iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
    log "    Role ${ROLE_NAME} already exists"
  else
    safe_run $AWSLOCAL iam create-role \
        --role-name "${ROLE_NAME}" \
        --description "CardDemo local-dev IAM role (mirrors infrastructure/terraform/iam.tf)" \
        --assume-role-policy-document "${TRUST_POLICY}"
  fi
}

create_iam_role "carddemo-local-ecs-task-role" "${TRUST_POLICY_ECS}"
create_iam_role "carddemo-local-batch-job-role" "${TRUST_POLICY_BATCH}"
create_iam_role "carddemo-local-step-functions-execution" "${TRUST_POLICY_SFN}"

# =============================================================================
# Phase 11 — SNS topic + SQS queue (Secrets Manager rotation event flow)
# =============================================================================
#
# Per AAP §0.6.4 the production rotation flow chains:
#   AWS Secrets Manager rotation Lambda
#     → EventBridge rule on Successful rotation event
#       → SNS topic carddemo-secrets-rotation
#         → SQS queue carddemo-secrets-rotation-app
#           → Spring application consumer triggers @RefreshScope refresh
#
# Locally we replicate the SNS + SQS half of that chain so the Spring
# application's rotation listener can be exercised end-to-end. The actual
# rotation Lambda is not required in dev (Secrets Manager auto-rotation is
# disabled in application-local.yml).
# =============================================================================

log "[8/8] Creating SNS topic + SQS queue for secrets rotation..."

# Create SNS topic; sns create-topic is naturally idempotent and returns the
# ARN of the existing topic if the name matches.
TOPIC_ARN="$( $AWSLOCAL sns create-topic \
    --name carddemo-local-secrets-rotation \
    --query TopicArn --output text 2>/dev/null || echo "" )"
if [ -z "${TOPIC_ARN}" ] || [ "${TOPIC_ARN}" = "None" ]; then
  # Fallback: assume LocalStack's default account ID and synthesize the ARN
  # for downstream subscribe call. LocalStack uses 000000000000.
  TOPIC_ARN="arn:aws:sns:${AWS_REGION}:000000000000:carddemo-local-secrets-rotation"
  log "  SNS create-topic returned no ARN — falling back to: ${TOPIC_ARN}"
else
  log "  SNS topic ARN: ${TOPIC_ARN}"
fi

# Create SQS queue; sqs create-queue is idempotent on identical attributes.
QUEUE_URL="$( $AWSLOCAL sqs create-queue \
    --queue-name carddemo-local-secrets-rotation-app \
    --query QueueUrl --output text 2>/dev/null || echo "" )"
if [ -z "${QUEUE_URL}" ] || [ "${QUEUE_URL}" = "None" ]; then
  QUEUE_URL="${ENDPOINT_URL}/000000000000/carddemo-local-secrets-rotation-app"
  log "  SQS create-queue returned no URL — falling back to: ${QUEUE_URL}"
else
  log "  SQS queue URL: ${QUEUE_URL}"
fi

# Resolve queue ARN for the SNS subscription. get-queue-attributes returns
# Attributes.QueueArn even if the queue was created by a prior init run.
QUEUE_ARN="$( $AWSLOCAL sqs get-queue-attributes \
    --queue-url "${QUEUE_URL}" \
    --attribute-names QueueArn \
    --query Attributes.QueueArn --output text 2>/dev/null || echo "" )"
if [ -z "${QUEUE_ARN}" ] || [ "${QUEUE_ARN}" = "None" ]; then
  QUEUE_ARN="arn:aws:sqs:${AWS_REGION}:000000000000:carddemo-local-secrets-rotation-app"
fi
log "  SQS queue ARN: ${QUEUE_ARN}"

# Subscribe SQS to SNS. sns subscribe is idempotent: re-subscribing with
# the same protocol+endpoint pair returns the existing subscription ARN.
safe_run $AWSLOCAL sns subscribe \
    --topic-arn "${TOPIC_ARN}" \
    --protocol sqs \
    --notification-endpoint "${QUEUE_ARN}"

# =============================================================================
# Phase 12 — Completion log
# =============================================================================

log ""
log "=========================================================="
log "  CardDemo LocalStack initialization COMPLETE"
log "=========================================================="
log "  Verify with:"
log "    aws --endpoint-url=${ENDPOINT_URL} s3 ls"
log "    aws --endpoint-url=${ENDPOINT_URL} kms list-aliases"
log "    aws --endpoint-url=${ENDPOINT_URL} secretsmanager list-secrets"
log "    aws --endpoint-url=${ENDPOINT_URL} stepfunctions list-state-machines"
log "    aws --endpoint-url=${ENDPOINT_URL} opensearch list-domain-names"
log "    aws --endpoint-url=${ENDPOINT_URL} iam list-roles"
log "    aws --endpoint-url=${ENDPOINT_URL} sns list-topics"
log "    aws --endpoint-url=${ENDPOINT_URL} sqs list-queues"
log "=========================================================="

exit 0

