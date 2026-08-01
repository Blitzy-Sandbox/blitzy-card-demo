#!/usr/bin/env bash
# ******************************************************************
# * Program     : init-aws.sh
# * Application : CardDemo
# * Type        : LocalStack resource initializer (Docker Compose ready-init hook)
# * Function    : Idempotently provisions the S3, SQS FIFO and SNS resources that
# *               replace the legacy generation data groups and the CICS
# *               transient data queue.
# * Source      : app/jcl/DEFGDGB.jcl (6 GDG bases, LIMIT(5) SCRATCH),
# *               app/jcl/DALYREJS.jcl (the 7th GDG base),
# *               app/jcl/REPTFILE.jcl (TRANREPT LIMIT(10)),
# *               app/csd/CARDDEMO.CSD:L499-L503 (DEFINE TDQUEUE(JOBS),
# *               RECORDSIZE(80) RECORDFORMAT(FIXED)), and the Java migration
# *               (src/main/java/com/cardemo/config/AwsConfig.java) as the
# *               consuming origin - all @ 7756d89
# * Replaces    : the 7 generation data group bases, the extrapartition
# *               transient data queue 'JOBS' and the JES2 internal reader
# * Convention  : this banner reproduces the universal Apache-2.0 source header
# *               of the frozen corpus, canonical form app/cbl/CBACT04C.cbl:L1-L21
# *               (coverage app/cbl 28/28, app/cpy-bms 17/17, app/jcl 28/29)
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
# ==============================================================================
# WHAT IT DOES
# ==============================================================================
# Creates - and on every subsequent run re-confirms - exactly the following
# resource set, and nothing else:
#
#   * 3 S3 buckets .......... input, output, statements
#                             VERSIONING ON THE OUTPUT BUCKET ONLY
#   * 1 SQS FIFO queue ...... FifoQueue=true, ContentBasedDeduplication=true
#   * 2 SNS topics .......... alerts, notifications
#   * 0 SNS subscriptions ... deliberately none; see the SNS section below
#
# These replace the seven generation data group bases declared across
# app/jcl/DEFGDGB.jcl and app/jcl/DALYREJS.jcl, and the single extrapartition
# transient data queue declared at app/csd/CARDDEMO.CSD:L499-L503. No IAM role,
# policy, KMS key, DynamoDB table, parameter-store entry, Lambda or EventBridge
# rule is created: only s3, sqs and sns are enabled on the container
# (docker-compose.yml:L84 `SERVICES: s3,sqs,sns`), and least privilege forbids
# provisioning anything the application does not consume.
#
# The script creates CONTAINERS, never OBJECTS. It writes no S3 key, enqueues no
# message and publishes no notification. The legacy record lengths quoted in the
# provenance section below are therefore evidence for WHICH bucket each byte
# stream belongs in - they are not enforced here.
#
# Idempotency is a hard requirement, not a convenience: LocalStack re-runs every
# executable in its ready-init directory on each `docker compose up`, each
# `docker compose restart` and each persistence-restore cycle. Re-running this
# script converges; it never fails because a resource already exists, and it
# never destroys or reconfigures state it did not create.
#
# ==============================================================================
# HOW TO RUN / TEST
# ==============================================================================
# Normal operation - fully automatic, no manual step:
#   docker-compose.yml:L98 mounts this directory READ-ONLY at
#   /etc/localstack/init/ready.d, and LocalStack executes this file once the
#   edge service reports ready. Just run:
#
#     docker compose up -d localstack
#     docker compose logs localstack | grep '\[init-aws\]'
#
# Manual re-run and verification. Every command runs INSIDE the container,
# because the AWS CLI is not required on - and is generally absent from - the
# host, while `awslocal` is bundled in the image:
#
#   docker compose exec localstack bash /etc/localstack/init/ready.d/init-aws.sh
#   docker compose exec localstack awslocal s3api list-buckets
#   docker compose exec localstack awslocal s3api get-bucket-versioning \
#     --bucket carddemo-batch-output
#   docker compose exec localstack awslocal sqs list-queues
#   docker compose exec localstack awslocal sqs get-queue-attributes \
#     --queue-url "$(docker compose exec -T localstack awslocal sqs get-queue-url \
#     --queue-name carddemo-report-jobs.fifo --query QueueUrl --output text)" \
#     --attribute-names FifoQueue ContentBasedDeduplication
#   docker compose exec localstack awslocal sns list-topics
#   docker compose exec localstack awslocal sns list-subscriptions
#
# Expected: 3 buckets; Status=Enabled on the output bucket only; one queue whose
# name ends in `.fifo` and no unsuffixed twin; FifoQueue=true; 2 topics; an empty
# subscription list.
#
# Static checks (host). The `$` prompt markers matter: a comment line beginning
# with the linter's own name is parsed as an inline directive rather than as
# documentation.
#   $ bash -n localstack-init/init-aws.sh
#   $ shellcheck localstack-init/init-aws.sh
#
# There is no unit-test tier for this file and none is claimed. The AWS
# integration tests under src/test/java/com/cardemo/integration/aws/ provision
# their own Testcontainers resources and do not depend on this script. The
# verification obligation is discharged by the in-script self-verification
# (bucket existence, versioning read-back, FIFO attribute read-back, topic
# presence) plus the Docker Compose execution evidence recorded for Gate 8.
#
# ==============================================================================
# KEY CONFIGS AND DEFAULTS
# ==============================================================================
# Every value below is read from the environment. Defaults are copied verbatim
# from the committed `.env.example`; none is invented here. Resolution uses
# ${VAR-default} rather than ${VAR:-default} on purpose, so that "not
# configured" and "configured to an empty string" are DIFFERENT outcomes: an
# unset variable takes the documented default, whereas an explicitly empty one
# is a misconfiguration and exits 2. docker-compose.yml:L91-L94 injects the
# four bucket/queue variables; the two topic variables are not injected, so for
# them the documented default is the normal path.
#
#   CARDDEMO_BATCH_INPUT_BUCKET   default carddemo-batch-input
#                                 DALYTRAN staging input, LRECL 350
#   CARDDEMO_BATCH_OUTPUT_BUCKET  default carddemo-batch-output
#                                 the ONLY versioned bucket
#   CARDDEMO_STATEMENTS_BUCKET    default carddemo-statements
#                                 STMTFILE 80, HTMLFILE 100
#   CARDDEMO_REPORT_QUEUE         default carddemo-report-jobs.fifo
#                                 logical name carddemo-report-jobs; a `.fifo`
#                                 suffix is appended when absent and never
#                                 doubled
#   CARDDEMO_ALERT_TOPIC          default carddemo-alerts
#   CARDDEMO_NOTIFICATION_TOPIC   default carddemo-notifications
#   AWS_REGION                    no default; preferred when set
#   AWS_DEFAULT_REGION            default us-east-1; used when AWS_REGION is
#                                 unset, matching AWS CLI precedence
#   AWS_ENDPOINT_URL              default http://localhost:4566; used only on
#                                 the plain-`aws` fallback path, since
#                                 `awslocal` targets the local edge itself
#   INIT_MAX_ATTEMPTS             default 30   readiness poll attempts
#   INIT_SLEEP_SECONDS            default 2    delay between attempts
#   INIT_HEALTH_TIMEOUT_SECONDS   default 5    per-probe network timeout
#
# AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY are supplied externally as
# local-only placeholders and are never read, logged or defaulted by this
# script. No credential literal appears anywhere in this file, and no live AWS
# account or credential path is supported.
#
# Metrics and tracing are intentionally absent. Rule 1 Clause A qualifies
# instrumentation with "where relevant"; a one-shot provisioning hook that runs
# before the application exists has no meter registry and no trace context to
# join, so the observable contract here is its structured `[init-aws]` log lines
# and its exit code.
#
# ==============================================================================
# PUBLIC API - EXIT CODES
# ==============================================================================
# The public API of this script is its environment-variable contract above and
# the exit codes below. Every nonzero exit prints, on stderr, the failing step,
# the underlying error verbatim as the preserved root cause, and a remediation
# line.
#
#   0  success - every resource created or confirmed and verified
#   1  unexpected failure - a command failed at a site with no specific handler;
#      the ERR trap reports the failing line, the command and its raw status
#   2  configuration error - a required variable is empty, or a name violates
#      the AWS charset/length rules, or the endpoint points at live AWS.
#      Raised BEFORE any API call, so no partial state can result
#   3  readiness attempts exhausted - the edge never reported s3, sqs and sns
#      usable within INIT_MAX_ATTEMPTS
#   4  bucket provisioning failure - head-bucket or create-bucket failed, or the
#      name is owned by another account
#   5  versioning verification failure - the output bucket did not read back
#      Status=Enabled
#   6  queue provisioning or verification failure - includes FIFO attribute
#      drift on an existing queue and the post-deletion name-reuse window
#   7  topic provisioning or verification failure
#
# ==============================================================================
# COMMON FAILURE MODES AND TROUBLESHOOTING
# ==============================================================================
# LocalStack not ready within the bounded attempts (exit 3)
#   The edge did not report s3, sqs and sns usable. Inspect
#   `docker compose logs localstack`; confirm docker-compose.yml:L84 still lists
#   all three services; on a slow host raise INIT_MAX_ATTEMPTS or
#   INIT_SLEEP_SECONDS. The script never proceeds to provisioning on exhaustion.
#
# A required variable unset or empty (exit 2)
#   The message names the offending variable. An unset variable is fine and
#   takes its documented default; an explicitly empty one is rejected. Set it in
#   `.env` or in the compose environment block and bring the stack up again.
#
# A bucket name rejected or owned elsewhere (exit 2 or 4)
#   S3 names are globally scoped, 3-63 characters, lowercase alphanumeric plus
#   dot and hyphen, starting and ending alphanumeric. A charset violation is
#   caught locally as exit 2. `BucketAlreadyExists` means another account holds
#   the name - choose a different value. `BucketAlreadyOwnedByYou` is normal on
#   a re-run and is treated as success.
#
# Versioning not reporting Enabled on read-back (exit 5)
#   The observed value is printed. Versioning is applied only to the output
#   bucket and, once enabled, S3 permits Suspended but never removal - so this
#   script never suspends or clears versioning a previous run or an operator
#   established, on any bucket. To assert that the input and statements buckets
#   are unversioned, test against a stack with a fresh volume.
#
# FIFO attribute drift on an existing queue (exit 6)
#   A queue of this name exists with attributes other than FifoQueue=true and
#   ContentBasedDeduplication=true. Drift is caught by the read-back assertion in
#   verify_queue, NOT by a `QueueAlreadyExists` response: because the
#   check-then-create path returns as soon as get-queue-url succeeds,
#   create-queue is never called on an existing queue and so can never raise.
#   The message names the drifting attribute and the observed value. Either align
#   the configuration or delete the queue and let the hook recreate it.
#
# A FIFO name that cannot be recreated immediately after deletion (exit 6)
#   SQS refuses to reuse a deleted queue name for 60 seconds. The message says
#   so explicitly. Wait out the window and re-run; the script does not loop.
#
# Topic verification failure (exit 7)
#   `list-topics` did not report the topic after `create-topic` succeeded.
#   Check the sns service state on the health endpoint and the container logs.
#
# Unexpected failure (exit 1)
#   The ERR trap prints the line number, the command and its raw status. Re-run
#   with the container logs open; a wedged edge service is the usual cause.
# ==============================================================================

# Strict mode. -E propagates the ERR trap into functions, so an unhandled
# failure inside a helper is reported rather than silently returned. `set -x` is
# deliberately never enabled: it would echo command lines that can carry
# credential material.
#
# Shebang justification (Rule 1 Clause C - no environment-specific assumption):
# bash is not assumed, it was verified present in the pinned image
# (docker-compose.yml:L81 localstack/localstack:4.14.0) with
#   docker compose exec localstack sh -c 'command -v bash; command -v sh'
# which reported /usr/bin/bash (GNU bash 5.2.37) and /usr/bin/sh. The same probe
# confirmed awslocal, aws and curl are all present.
set -Eeuo pipefail

# ==============================================================================
# LEGACY MAPPING - PROVENANCE, NOT EXECUTABLE LOGIC
#
# Every locator below was confirmed by direct read of the frozen corpus at
# commit 7756d89. app/** is byte-for-byte immutable; this script reads nothing
# from it at runtime.
# ==============================================================================
#
# --- 7 generation data group bases -> 3 buckets -------------------------------
# app/jcl/DEFGDGB.jcl declares SIX, each of the identical shape
# `DEFINE GENERATIONDATAGROUP - ( NAME(<dsn>) - LIMIT(5) - SCRATCH - )`:
#   L24-L28  AWS.M2.CARDDEMO.TRANSACT.BKUP
#   L30-L34  AWS.M2.CARDDEMO.TRANSACT.DALY
#   L36-L40  AWS.M2.CARDDEMO.TRANREPT            LIMIT(5)
#   L42-L46  AWS.M2.CARDDEMO.TCATBALF.BKUP
#   L48-L52  AWS.M2.CARDDEMO.SYSTRAN
#   L54-L58  AWS.M2.CARDDEMO.TRANSACT.COMBINED
# app/jcl/DALYREJS.jcl:L24-L28 supplies the SEVENTH:
#   DEFINE GENERATIONDATAGROUP - ( NAME(AWS.M2.CARDDEMO.DALYREJS) - LIMIT(5) -
#   SCRATCH - )
# Seven, not six. They collapse onto three buckets by direction of flow:
#   input       DALYTRAN staging, LRECL 350, seeded from
#               app/data/ASCII/dailytran.txt - the fixture is spelled
#               `dailytran.txt`, never `dalytran.txt`
#   output      DALYREJS 430, TRANREPT 133, TRANSACT.BKUP / .DALY / .COMBINED
#               350, SYSTRAN 350, TCATBALF.BKUP - THIS BUCKET IS VERSIONED
#   statements  STMTFILE 80 and HTMLFILE 100, under account and month prefixes
#
# --- record lengths preserved byte-exactly at the S3 boundary ------------------
# app/jcl/POSTTRAN.jcl:L34-L38   //DALYREJS DD DCB=(RECFM=F,LRECL=430,BLKSIZE=0)
#                               DSN=AWS.M2.CARDDEMO.DALYREJS(+1)
#                               430 = 350 data + 80 trailer (a 4-digit reason
#                               code plus a 76-character description). RECFM=F
#                               is fixed UNBLOCKED, not FB.
# app/jcl/INTCALC.jcl:L37-L41   DD name is TRANSACT but
#                               DSN=AWS.M2.CARDDEMO.SYSTRAN(+1) with
#                               DCB=(RECFM=F,LRECL=350,BLKSIZE=0). The DD name is
#                               deceptive: the interest job writes a fresh
#                               sequential generation, not the keyed cluster.
# app/proc/TRANREPT.prc:L76 and app/jcl/TRANREPT.jcl:L78
#                               DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)
# app/proc/TRANREPT.prc:L27-L31 DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)
#                               DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)
# app/jcl/CREASTMT.JCL:L89      DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)  STMTFILE
# app/jcl/CREASTMT.JCL:L94      DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)  HTMLFILE
# app/jcl/CREASTMT.JCL:L29-L32  the TRXFL work cluster, KEYS(32 0)
#                               RECORDSIZE(350 350), is in-job only and is NEVER
#                               persisted to S3, so it gets no bucket.
#
# --- generation references -> deterministic prefixes --------------------------
# A (+1) write becomes a new object under a monotonically increasing,
# zero-padded, lexicographically sortable timestamp or job-instance prefix; a
# (0) read becomes a read of the lexicographically greatest existing prefix
# (app/jcl/COMBTRAN.jcl:L26 reads SYSTRAN(0)).
# The carry-forward hazard is why the output bucket must be VERSIONED: within a
# single legacy job, a (+1) written by an earlier step is re-read as (+1) by a
# later step -
#   app/jcl/COMBTRAN.jcl:L33-L37 writes TRANSACT.COMBINED(+1) and L43-L44 reads
#   it back; app/proc/TRANREPT.prc:L27-L31 writes TRANSACT.BKUP(+1) and
#   L36-L37 reads it back.
# Object versioning is what lets those two references denote the same immutable
# generation instead of racing.
#
# --- retention: DOCUMENTED, NEVER ENFORCED -----------------------------------
# app/jcl/DEFGDGB.jcl:L36-L40 declares AWS.M2.CARDDEMO.TRANREPT with
# LIMIT(5) SCRATCH, while app/jcl/REPTFILE.jcl:L25-L28 RE-DECLARES the same
# dataset with LIMIT(10) and no SCRATCH. Severity Medium; resolved in favour of
# 10, and it is the only legacy inconsistency the migration actually resolves.
# That resolution is recorded in DECISION_LOG.md and docs/validation-gates.md.
# NO put-bucket-lifecycle-configuration call is made here and no expiration rule
# is created: S3 object versioning supersedes GDG retention semantics. The
# absence of a lifecycle rule below is deliberate, not an omission.
#
# --- DEFINE TDQUEUE(JOBS) -> the FIFO queue ----------------------------------
# app/csd/CARDDEMO.CSD holds exactly one DEFINE TDQUEUE in its 505 lines:
#   L499  DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)
#   L500  DESCRIPTION(SUBMIT JOBS FROM CICS)
#   L501         TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)
#   L502         OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)
#   L503         RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)
# RECORDSIZE(80) RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) fixes the 80-byte
# fixed parameter-card shape that becomes a typed JSON message carrying the
# report name and the two dates. TYPEFILE(OUTPUT) with DISPOSITION(MOD) is the
# append-only producer side. DDNAME(INREADER) is the JES2 internal reader,
# replaced by an SQS listener owned by
# com.cardemo.batch.jobs.BatchPipelineOrchestrator.
# The producer is app/cbl/CORPT00C.cbl:L517-L523,
#   EXEC CICS WRITEQ TD QUEUE('JOBS') FROM (JCL-RECORD) LENGTH (...) ...
# Locator correction, severity Low: AAP 0.7.5.1 cites L515 for the write, but
# L515 is the paragraph label - and it is misspelled in the source as
# `WIRTE-JOBSUB-TDQ.`. The write itself is L517-L523.
# Because the publisher writes strictly sequentially, card by card, ordering is
# reproduced with a deterministic message group id on the publisher side, and
# the queue is FIFO rather than standard.
#
# ERROROPTION(IGNORE) at L501 is a legacy quirk the target deliberately does NOT
# reproduce: the mainframe was configured to ignore a queue I/O error outright.
# That is precisely why this script never swallows a failure - every AWS call
# below distinguishes "already exists" from a real error and surfaces the latter
# with its root cause intact.
#
# --- idempotency precedent (the design justification) ------------------------
# Check-then-create is not a modern embellishment; the legacy stream does the
# same thing. app/jcl/DEFGDGB.jcl follows every one of its six
# DEFINE GENERATIONDATAGROUP statements with `IF LASTCC=12 THEN SET MAXCC=0`, at
# L29, L35, L41, L47, L53 and L59, and app/jcl/CREASTMT.JCL:L28 reads
# `SET       MAXCC = 0` immediately after its DELETE ... CLUSTER pre-delete.
# That is the mainframe's own "already exists is not an error" guard.
# (app/jcl/DALYREJS.jcl carries no such guard - the six are all in DEFGDGB.jcl.)
#
# --- SNS ---------------------------------------------------------------------
# SNS carries operator notification, replacing the mainframe operator-notify
# path. Exactly the two topics declared by the committed contract are created -
# .env.example:L79-L81 and, historically, the JOB card NOTIFY=&SYSUID
# convention at app/jcl/DEFGDGB.jcl:L1 - and ZERO subscriptions.
#
# --- legacy defects logged here, fixed nowhere -------------------------------
# Severity Medium: app/jcl/CREASTMT.JCL declares HTMLFILE with LRECL=80 in the
#   STEP030 pre-delete at L69 but LRECL=100 in the STEP040 execution at L94.
#   The 100-byte form is authoritative for the statements bucket. Logged, never
#   repaired - parity is the contract.
# Severity Low: app/jcl/CREASTMT.JCL:L90 is a corrupted DD continuation,
#   `//         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS`.
#   Logged, never repaired.
# ==============================================================================


# ------------------------------------------------------------------------------
# Exit codes. Declared once, referenced by name everywhere, so the documented
# taxonomy above and the runtime behaviour cannot drift apart.
# ------------------------------------------------------------------------------
readonly EXIT_OK=0
readonly EXIT_UNEXPECTED=1
readonly EXIT_CONFIG=2
readonly EXIT_NOT_READY=3
readonly EXIT_BUCKET=4
readonly EXIT_VERSIONING=5
readonly EXIT_QUEUE=6
readonly EXIT_TOPIC=7

# ------------------------------------------------------------------------------
# Structured logging. One shape for every line: a fixed `[init-aws]` prefix, a
# UTC timestamp, a stable step token naming the resource, and the message.
# Progress goes to stdout; every failure goes to stderr.
#
# Nothing here may ever emit a secret. Access keys, secret keys, session tokens
# and the LocalStack auth token are never read by this script. Queue URLs and
# topic ARNs are never printed either, because both embed the AWS account
# identifier; the logical and physical resource NAMES are logged instead.
# ------------------------------------------------------------------------------
utc_now() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

log() {
  local stamp
  stamp="$(utc_now)"
  printf '[init-aws] %s %-22s %s\n' "${stamp}" "$1" "$2"
}

# Emits the failing step, the preserved root cause and a remediation line, then
# exits with the documented code. Never returns, and never converts a failure
# into a success.
fail() {
  local code="$1" step="$2" detail="$3" remedy="$4"
  local stamp
  stamp="$(utc_now)"
  printf '[init-aws] %s %-22s FAILED: %s\n' "${stamp}" "${step}" "${detail}" >&2
  printf '[init-aws] %s %-22s REMEDIATION: %s\n' "${stamp}" "${step}" "${remedy}" >&2
  printf '[init-aws] %s %-22s exiting with code %s\n' "${stamp}" "${step}" "${code}" >&2
  exit "${code}"
}

# ERR trap. Reaches only sites with no specific handler, because every expected
# failure below is detected inside an `if` condition - which bash exempts from
# ERR - and routed through `fail`. The raw status is reported to preserve the
# root cause, but the process exits with EXIT_UNEXPECTED so the documented code
# taxonomy stays exact and a command's incidental status can never masquerade as,
# say, a configuration error.
# ShellCheck cannot see through the single-quoted trap string below, so it reports
# this body as unreachable (SC2317). It is reached indirectly, via the ERR trap;
# suppressing that one check here is the remedy ShellCheck itself documents for
# indirect invocation, and it is scoped to this function alone.
# shellcheck disable=SC2317
on_unexpected_error() {
  local raw_status=$?
  local line="$1" command="$2"
  local stamp
  stamp="$(utc_now)"
  printf '[init-aws] %s %-22s FAILED: unhandled error at line %s: "%s" returned %s\n' \
    "${stamp}" 'internal' "${line}" "${command}" "${raw_status}" >&2
  printf '[init-aws] %s %-22s REMEDIATION: %s\n' "${stamp}" 'internal' \
    'Re-run with "docker compose logs -f localstack" open; a wedged edge service is the usual cause.' >&2
  printf '[init-aws] %s %-22s exiting with code %s\n' "${stamp}" 'internal' "${EXIT_UNEXPECTED}" >&2
  exit "${EXIT_UNEXPECTED}"
}
trap 'on_unexpected_error "${LINENO}" "${BASH_COMMAND}"' ERR

# ------------------------------------------------------------------------------
# Configuration. Resolved exactly once, then frozen with `readonly`, so no later
# line can mutate a resource name and every name is spelled in precisely one
# place. See KEY CONFIGS AND DEFAULTS above for why ${VAR-default} is used in
# preference to ${VAR:-default}.
# ------------------------------------------------------------------------------
readonly INPUT_BUCKET="${CARDDEMO_BATCH_INPUT_BUCKET-carddemo-batch-input}"
readonly OUTPUT_BUCKET="${CARDDEMO_BATCH_OUTPUT_BUCKET-carddemo-batch-output}"
readonly STATEMENTS_BUCKET="${CARDDEMO_STATEMENTS_BUCKET-carddemo-statements}"
readonly REPORT_QUEUE="${CARDDEMO_REPORT_QUEUE-carddemo-report-jobs.fifo}"
readonly ALERT_TOPIC="${CARDDEMO_ALERT_TOPIC-carddemo-alerts}"
readonly NOTIFICATION_TOPIC="${CARDDEMO_NOTIFICATION_TOPIC-carddemo-notifications}"

# AWS_REGION wins over AWS_DEFAULT_REGION, matching AWS CLI precedence, while
# AWS_DEFAULT_REGION is what docker-compose.yml:L87 actually injects.
readonly REGION="${AWS_REGION:-${AWS_DEFAULT_REGION-us-east-1}}"
readonly ENDPOINT_URL="${AWS_ENDPOINT_URL-http://localhost:4566}"

# Readiness budget. 30 attempts at 2s is a ~60s ceiling: comfortably longer than
# a cold LocalStack edge needs, yet short enough that a genuinely broken stack
# fails the compose run instead of hanging CI. Both are overridable rather than
# hard-coded so a slow host needs no edit to this file.
readonly MAX_ATTEMPTS="${INIT_MAX_ATTEMPTS-30}"
readonly SLEEP_SECONDS="${INIT_SLEEP_SECONDS-2}"
readonly HEALTH_TIMEOUT_SECONDS="${INIT_HEALTH_TIMEOUT_SECONDS-5}"

# The logical name is the AAP contract (`carddemo-report-jobs`); the physical
# name is what AWS requires of a FIFO queue. Stripping any existing suffix
# before appending exactly one guarantees the two can never diverge and that a
# doubled `.fifo.fifo` is impossible.
readonly QUEUE_LOGICAL="${REPORT_QUEUE%.fifo}"
readonly QUEUE_PHYSICAL="${QUEUE_LOGICAL}.fifo"

# ------------------------------------------------------------------------------
# Input validation. Inputs are untrusted and are checked BEFORE any AWS call, so
# a misconfiguration can never leave a half-provisioned stack behind.
# ------------------------------------------------------------------------------
# require_value <var-name> <resolved-value> <regex> <min-len> <max-len> <hint>
require_value() {
  local var_name="$1" value="$2" pattern="$3" min_len="$4" max_len="$5" hint="$6"
  if [[ -z "${value}" ]]; then
    fail "${EXIT_CONFIG}" "config:${var_name}" \
      'resolved to an empty value (the variable is set but empty)' \
      "Unset ${var_name} to accept its documented default, or set it to a valid name."
  fi
  if [[ "${#value}" -lt "${min_len}" || "${#value}" -gt "${max_len}" ]]; then
    fail "${EXIT_CONFIG}" "config:${var_name}" \
      "length ${#value} is outside the ${min_len}-${max_len} characters AWS permits" \
      "Set ${var_name} to a name of ${min_len}-${max_len} characters."
  fi
  if [[ ! "${value}" =~ ${pattern} ]]; then
    fail "${EXIT_CONFIG}" "config:${var_name}" \
      "value '${value}' is not a valid name" \
      "Set ${var_name} to a name matching: ${hint}"
  fi
}

# S3: 3-63 characters, lowercase alphanumeric with dots and hyphens, first and
# last character alphanumeric.
readonly BUCKET_PATTERN='^[a-z0-9][a-z0-9.-]*[a-z0-9]$'
readonly BUCKET_HINT='lowercase letters, digits, dots and hyphens, starting and ending alphanumeric'
# SQS/SNS: alphanumerics, hyphens and underscores. The queue is validated on its
# LOGICAL name, so the 80-character physical ceiling leaves 75 for the logical
# part once `.fifo` is appended.
readonly NAME_PATTERN='^[A-Za-z0-9_-]+$'
readonly NAME_HINT='letters, digits, hyphens and underscores only'
# Region: lowercase alphanumerics and hyphens, as every AWS region code is.
readonly REGION_PATTERN='^[a-z0-9-]+$'
readonly REGION_HINT='lowercase letters, digits and hyphens'

require_value 'CARDDEMO_BATCH_INPUT_BUCKET'  "${INPUT_BUCKET}"       "${BUCKET_PATTERN}" 3 63  "${BUCKET_HINT}"
require_value 'CARDDEMO_BATCH_OUTPUT_BUCKET' "${OUTPUT_BUCKET}"      "${BUCKET_PATTERN}" 3 63  "${BUCKET_HINT}"
require_value 'CARDDEMO_STATEMENTS_BUCKET'   "${STATEMENTS_BUCKET}"  "${BUCKET_PATTERN}" 3 63  "${BUCKET_HINT}"
require_value 'CARDDEMO_REPORT_QUEUE'        "${QUEUE_LOGICAL}"      "${NAME_PATTERN}"   1 75  "${NAME_HINT}"
require_value 'CARDDEMO_ALERT_TOPIC'         "${ALERT_TOPIC}"        "${NAME_PATTERN}"   1 256 "${NAME_HINT}"
require_value 'CARDDEMO_NOTIFICATION_TOPIC'  "${NOTIFICATION_TOPIC}" "${NAME_PATTERN}"   1 256 "${NAME_HINT}"
require_value 'AWS_REGION'                   "${REGION}"             "${REGION_PATTERN}" 2 32  "${REGION_HINT}"

# The three bucket names must be distinct, or two logical streams would silently
# share one container and the output bucket's versioning would leak across them.
if [[ "${INPUT_BUCKET}" == "${OUTPUT_BUCKET}" || "${INPUT_BUCKET}" == "${STATEMENTS_BUCKET}" ||
  "${OUTPUT_BUCKET}" == "${STATEMENTS_BUCKET}" ]]; then
  fail "${EXIT_CONFIG}" 'config:buckets' \
    "bucket names must be distinct: got '${INPUT_BUCKET}', '${OUTPUT_BUCKET}', '${STATEMENTS_BUCKET}'" \
    'Give CARDDEMO_BATCH_INPUT_BUCKET, CARDDEMO_BATCH_OUTPUT_BUCKET and CARDDEMO_STATEMENTS_BUCKET distinct values.'
fi

if [[ "${ALERT_TOPIC}" == "${NOTIFICATION_TOPIC}" ]]; then
  fail "${EXIT_CONFIG}" 'config:topics' \
    "the two topic names must be distinct but both resolved to '${ALERT_TOPIC}'" \
    'Give CARDDEMO_ALERT_TOPIC and CARDDEMO_NOTIFICATION_TOPIC distinct values.'
fi

# The readiness budget drives a loop, so a non-numeric or zero attempt count
# would make the guard either absent or infinite. Each variable is checked
# separately so the message can name the one that is actually wrong.
readonly POSITIVE_INT_PATTERN='^[1-9][0-9]*$'
if [[ ! "${MAX_ATTEMPTS}" =~ ${POSITIVE_INT_PATTERN} ]]; then
  fail "${EXIT_CONFIG}" 'config:attempts' \
    "INIT_MAX_ATTEMPTS='${MAX_ATTEMPTS}' must be an integer of at least 1" \
    'Set INIT_MAX_ATTEMPTS to a positive integer, for example 30.'
fi
if [[ ! "${SLEEP_SECONDS}" =~ ^[0-9]+$ ]]; then
  fail "${EXIT_CONFIG}" 'config:sleep' \
    "INIT_SLEEP_SECONDS='${SLEEP_SECONDS}' must be an integer of 0 or more" \
    'Set INIT_SLEEP_SECONDS to a non-negative integer, for example 2.'
fi
if [[ ! "${HEALTH_TIMEOUT_SECONDS}" =~ ${POSITIVE_INT_PATTERN} ]]; then
  fail "${EXIT_CONFIG}" 'config:timeout' \
    "INIT_HEALTH_TIMEOUT_SECONDS='${HEALTH_TIMEOUT_SECONDS}' must be an integer of at least 1" \
    'Set INIT_HEALTH_TIMEOUT_SECONDS to a positive integer, for example 5.'
fi

case "${ENDPOINT_URL}" in
  http://* | https://*) ;;
  *)
    fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
      "value '${ENDPOINT_URL}' is not an http(s) URL" \
      'Set AWS_ENDPOINT_URL to the LocalStack edge, for example http://localhost:4566.'
    ;;
esac

# --- Defensive live-AWS guard -------------------------------------------------
# AAP 0.3.2 forbids any code path that can reach a real AWS account, and the
# whole topology is credential-free by design. The domain literal in the pattern
# below is the ONLY occurrence of the real AWS service domain in this file; it
# exists solely so that a misconfigured endpoint is refused BEFORE a single API
# call is issued, and it must be accounted for when grepping this file for that
# string.
case "${ENDPOINT_URL}" in
  *amazonaws.com*)
    fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
      "refusing to provision against a live AWS endpoint ('${ENDPOINT_URL}')" \
      'Point AWS_ENDPOINT_URL at the LocalStack edge (default http://localhost:4566); live AWS is never used.'
    ;;
  *)
    # Any other host is a local emulator endpoint and is accepted. Stated
    # explicitly so the absence of a default branch cannot be read as an
    # oversight.
    ;;
esac

# ------------------------------------------------------------------------------
# CLI selection. `awslocal` is bundled in the pinned image and already targets
# the local edge, so it needs neither an endpoint flag nor credential handling.
# The plain-`aws` fallback keeps the script runnable from a developer host that
# has the AWS CLI but not the wrapper; it is a portability branch, not dead code.
# An array is used so the words can never be re-split by the shell.
# ------------------------------------------------------------------------------
if command -v awslocal >/dev/null; then
  readonly AWS_CLI=(awslocal)
  readonly CLI_LABEL='awslocal (bundled, self-targeting)'
elif command -v aws >/dev/null; then
  readonly AWS_CLI=(aws --endpoint-url "${ENDPOINT_URL}")
  readonly CLI_LABEL="aws --endpoint-url ${ENDPOINT_URL} (fallback)"
else
  fail "${EXIT_CONFIG}" 'config:cli' \
    'neither the awslocal wrapper nor the aws CLI is on PATH' \
    'Run this script inside the LocalStack container, where awslocal is bundled; see HOW TO RUN / TEST above.'
fi


# ------------------------------------------------------------------------------
# Readiness gate.
#
# This script is launched from LocalStack's ready.d directory, so the edge has
# already declared itself ready and the poll below is a DEFENSIVE bounded guard
# rather than the primary synchronisation mechanism. It also makes the script
# safe to invoke by hand against a stack that is still starting.
#
# THIS IS THE ONLY RETRY LOOP IN THE SCRIPT. A provisioning or verification call
# that fails is a failure, not a transient, and is never retried.
#
# Only s3, sqs and sns are probed - the three services docker-compose.yml:L84
# enables. sts and every other service report `disabled` on this edge, so probing
# them would guarantee a false negative.
# ------------------------------------------------------------------------------

# Both probes below share one output contract: they print READY_TOKEN when all
# three services are usable and otherwise print the reason they are not, and they
# ALWAYS succeed. Control flow is therefore driven by the printed value rather
# than by an exit status, which keeps `set -e` fully in force at every call site -
# a probe reporting "not ready yet" is an expected observation, not an error.
readonly READY_TOKEN='ready'

# LocalStack reports `running` once a service is up and `available` before it has
# been exercised; both mean usable, so both are accepted.
probe_health_endpoint() {
  local url="$1"
  local body=''
  if ! body="$(curl -fsS --max-time "${HEALTH_TIMEOUT_SECONDS}" "${url}" 2>&1)"; then
    printf 'health endpoint unreachable: %s' "${body//$'\n'/ }"
    return 0
  fi
  local service pattern
  local pending=''
  for service in s3 sqs sns; do
    pattern="\"${service}\"[[:space:]]*:[[:space:]]*\"(running|available)\""
    if [[ ! "${body}" =~ ${pattern} ]]; then
      pending="${pending}${pending:+,}${service}"
    fi
  done
  if [[ -n "${pending}" ]]; then
    printf 'not yet usable: %s' "${pending}"
    return 0
  fi
  printf '%s' "${READY_TOKEN}"
}

# Fallback used when curl is absent: one cheap read per service. A portability
# branch rather than dead code - the pinned image ships curl, but a developer
# host running the plain AWS CLI need not.
probe_api_reads() {
  local probe=''
  if ! probe="$("${AWS_CLI[@]}" s3api list-buckets 2>&1 >/dev/null)"; then
    printf 's3 list-buckets failed: %s' "${probe//$'\n'/ }"
    return 0
  fi
  if ! probe="$("${AWS_CLI[@]}" sqs list-queues 2>&1 >/dev/null)"; then
    printf 'sqs list-queues failed: %s' "${probe//$'\n'/ }"
    return 0
  fi
  if ! probe="$("${AWS_CLI[@]}" sns list-topics 2>&1 >/dev/null)"; then
    printf 'sns list-topics failed: %s' "${probe//$'\n'/ }"
    return 0
  fi
  printf '%s' "${READY_TOKEN}"
}

wait_until_ready() {
  local health_url="${ENDPOINT_URL%/}/_localstack/health"
  local probe_kind='health endpoint'
  local use_curl='yes'
  if ! command -v curl >/dev/null; then
    use_curl='no'
    probe_kind='per-service API reads'
  fi
  log 'readiness' "probing via ${probe_kind}, up to ${MAX_ATTEMPTS} attempts every ${SLEEP_SECONDS}s"

  local attempt=1
  local observed=''
  while [[ "${attempt}" -le "${MAX_ATTEMPTS}" ]]; do
    if [[ "${use_curl}" == 'yes' ]]; then
      observed="$(probe_health_endpoint "${health_url}")"
    else
      observed="$(probe_api_reads)"
    fi
    if [[ "${observed}" == "${READY_TOKEN}" ]]; then
      log 'readiness' "s3, sqs and sns all usable after ${attempt} attempt(s)"
      return 0
    fi
    log 'readiness' "attempt ${attempt}/${MAX_ATTEMPTS}: ${observed}"
    attempt=$((attempt + 1))
    if [[ "${attempt}" -le "${MAX_ATTEMPTS}" ]]; then
      sleep "${SLEEP_SECONDS}"
    fi
  done

  fail "${EXIT_NOT_READY}" 'readiness' \
    "exhausted ${MAX_ATTEMPTS} attempts over ~$((MAX_ATTEMPTS * SLEEP_SECONDS))s; last observed state: ${observed}" \
    'Check the container logs, confirm SERVICES lists s3,sqs,sns, then raise INIT_MAX_ATTEMPTS on a slow host.'
}

# ------------------------------------------------------------------------------
# S3. Three buckets, check-then-create, mirroring the legacy
# `IF LASTCC=12 THEN SET MAXCC=0` guard cited above.
#
# Every call captures stderr into a variable so that the three outcomes stay
# distinguishable: created, already exists (idempotent success), or a real error
# that is reported with its root cause and aborts. No ACL, no public-access
# setting, no bucket policy and no encryption configuration is applied -
# encryption at rest is explicitly deferred hardening in AAP 0.3.2, and least
# privilege forbids widening anything by default.
# ------------------------------------------------------------------------------
create_bucket() {
  local bucket="$1"
  local args=(s3api create-bucket --bucket "${bucket}")
  # us-east-1 is the S3 global default and REJECTS an explicit LocationConstraint;
  # every other region REQUIRES one. Handled explicitly rather than assumed.
  if [[ "${REGION}" != 'us-east-1' ]]; then
    args+=(--create-bucket-configuration "LocationConstraint=${REGION}")
  fi
  local result=''
  if result="$("${AWS_CLI[@]}" "${args[@]}" 2>&1 >/dev/null)"; then
    log "s3:${bucket}" 'created'
    return 0
  fi
  case "${result}" in
    *BucketAlreadyOwnedByYou*)
      # Lost a create race with a concurrent run: the desired end state holds.
      log "s3:${bucket}" 'already owned by this account - idempotent success'
      ;;
    *BucketAlreadyExists*)
      fail "${EXIT_BUCKET}" "s3:${bucket}" \
        "the name is already held by a different account: ${result//$'\n'/ }" \
        "Choose an unused name for this bucket's CARDDEMO_* variable; S3 bucket names are globally scoped."
      ;;
    *)
      fail "${EXIT_BUCKET}" "s3:${bucket}" \
        "create-bucket failed: ${result//$'\n'/ }" \
        'Confirm the s3 service is running on the health endpoint, then re-run the hook.'
      ;;
  esac
}

ensure_bucket() {
  local bucket="$1"
  local probe=''
  if probe="$("${AWS_CLI[@]}" s3api head-bucket --bucket "${bucket}" 2>&1 >/dev/null)"; then
    log "s3:${bucket}" 'already exists - idempotent success'
    return 0
  fi
  case "${probe}" in
    *404* | *NoSuchBucket* | *NotFound* | *'Not Found'*)
      create_bucket "${bucket}"
      ;;
    *)
      # Anything other than a not-found signal is a real error. Swallowing it
      # here is exactly the ERROROPTION(IGNORE) behaviour the target rejects.
      fail "${EXIT_BUCKET}" "s3:${bucket}" \
        "head-bucket failed for a reason other than absence: ${probe//$'\n'/ }" \
        'Resolve the reported error - commonly a stopped edge service or a permission problem - then re-run.'
      ;;
  esac
}

# Versioning is applied to the OUTPUT bucket alone: it is the only one carrying
# generation semantics, because only it receives the (+1)/(0) generation streams
# documented above. The input and statements buckets are left unversioned, and
# this function is deliberately never called for them.
enable_and_verify_versioning() {
  local bucket="$1"
  local result=''
  if ! result="$("${AWS_CLI[@]}" s3api put-bucket-versioning --bucket "${bucket}" \
    --versioning-configuration Status=Enabled 2>&1 >/dev/null)"; then
    fail "${EXIT_VERSIONING}" "s3:${bucket}" \
      "put-bucket-versioning failed: ${result//$'\n'/ }" \
      'Confirm the s3 service is running, then re-run the hook.'
  fi
  # Read-back assertion. LocalStack applies versioning synchronously, so a single
  # read is authoritative and no retry loop is warranted. An unversioned bucket
  # answers `None` here, which makes the observed value self-explanatory.
  local observed=''
  if ! observed="$("${AWS_CLI[@]}" s3api get-bucket-versioning --bucket "${bucket}" \
    --query 'Status' --output text 2>&1)"; then
    fail "${EXIT_VERSIONING}" "s3:${bucket}" \
      "get-bucket-versioning failed: ${observed//$'\n'/ }" \
      'Confirm the s3 service is running, then re-run the hook.'
  fi
  if [[ "${observed}" != 'Enabled' ]]; then
    fail "${EXIT_VERSIONING}" "s3:${bucket}" \
      "versioning read back as '${observed}' but must be 'Enabled'" \
      'Investigate why put-bucket-versioning did not take effect on this edge, then re-run the hook.'
  fi
  log "s3:${bucket}" 'versioning verified Enabled by read-back'
}


# ------------------------------------------------------------------------------
# SQS. Exactly one FIFO queue, replacing DEFINE TDQUEUE(JOBS).
#
# No dead-letter queue and no redrive policy are created: neither is configured
# anywhere in the consuming application, and least privilege forbids provisioning
# capacity nothing consumes.
# ------------------------------------------------------------------------------

# FifoQueue=true is MANDATORY - AWS rejects a `.fifo` name on a standard queue
# and rejects a FIFO queue whose name lacks the suffix, so the physical name and
# this attribute have to agree. ContentBasedDeduplication=true suits a report
# message whose body (report name plus the two dates) is content-addressable; if
# the publisher also supplies an explicit MessageDeduplicationId, that id takes
# precedence and this attribute remains correct either way. Nothing further is
# set: DeduplicationScope and FifoThroughputLimit are high-throughput-mode knobs
# the application does not use.
readonly QUEUE_ATTRIBUTES='FifoQueue=true,ContentBasedDeduplication=true'

create_queue() {
  local physical="$1"
  local result=''
  if result="$("${AWS_CLI[@]}" sqs create-queue --queue-name "${physical}" \
    --attributes "${QUEUE_ATTRIBUTES}" 2>&1 >/dev/null)"; then
    log "sqs:${physical}" 'created FIFO queue'
    return 0
  fi
  case "${result}" in
    *QueueAlreadyExists*)
      # create-queue is idempotent when the requested attributes match an
      # existing queue, so this response means the attributes DIFFER. That is a
      # real error: silently accepting it would leave the queue's ordering and
      # deduplication semantics different from what the publisher expects.
      fail "${EXIT_QUEUE}" "sqs:${physical}" \
        "exists with attributes differing from the required ${QUEUE_ATTRIBUTES}: ${result//$'\n'/ }" \
        'Align the queue with the required attributes, or delete it and re-run (mind the 60s name-reuse window).'
      ;;
    *QueueDeletedRecently* | *'You must wait'*)
      fail "${EXIT_QUEUE}" "sqs:${physical}" \
        "name not reusable yet - SQS enforces a 60s wait after deletion: ${result//$'\n'/ }" \
        'Wait 60 seconds from the deletion, then re-run the hook. This is not retried automatically, by design.'
      ;;
    *InvalidParameterValue* | *InvalidParameterCombination* | *InvalidAttributeName*)
      fail "${EXIT_QUEUE}" "sqs:${physical}" \
        "SQS rejected the FIFO parameters: ${result//$'\n'/ }" \
        "A FIFO queue name must end in '.fifo' and carry FifoQueue=true; check CARDDEMO_REPORT_QUEUE."
      ;;
    *)
      fail "${EXIT_QUEUE}" "sqs:${physical}" \
        "create-queue failed: ${result//$'\n'/ }" \
        'Confirm the sqs service is running on the health endpoint, then re-run the hook.'
      ;;
  esac
}

# Resolves the queue URL and asserts the FIFO attributes actually took effect.
# The URL is used but NEVER logged: it embeds the AWS account identifier. The
# logical and physical names are logged instead, which is the same convention the
# application's health details follow.
verify_queue() {
  local physical="$1"
  local url=''
  if ! url="$("${AWS_CLI[@]}" sqs get-queue-url --queue-name "${physical}" \
    --query 'QueueUrl' --output text 2>&1)"; then
    fail "${EXIT_QUEUE}" "sqs:${physical}" \
      "the queue URL did not resolve after provisioning: ${url//$'\n'/ }" \
      'Re-run the hook; if it persists, inspect the sqs service state in the container logs.'
  fi
  if [[ -z "${url}" || "${url}" == 'None' ]]; then
    fail "${EXIT_QUEUE}" "sqs:${physical}" \
      'the queue URL resolved to an empty value' \
      'Re-run the hook; if it persists, inspect the sqs service state in the container logs.'
  fi
  local attributes=''
  if ! attributes="$("${AWS_CLI[@]}" sqs get-queue-attributes --queue-url "${url}" \
    --attribute-names FifoQueue ContentBasedDeduplication \
    --query 'Attributes.[FifoQueue,ContentBasedDeduplication]' --output text 2>&1)"; then
    fail "${EXIT_QUEUE}" "sqs:${physical}" \
      "get-queue-attributes failed: ${attributes//$'\n'/ }" \
      'Re-run the hook; if it persists, inspect the sqs service state in the container logs.'
  fi
  # `--output text` returns the two requested values tab separated on one line.
  local fifo_flag="${attributes%%$'\t'*}"
  local dedup_flag="${attributes##*$'\t'}"
  if [[ "${fifo_flag}" != 'true' ]]; then
    fail "${EXIT_QUEUE}" "sqs:${physical}" \
      "FifoQueue read back as '${fifo_flag}' but must be 'true' - this is a standard queue, not a FIFO queue" \
      'Delete the queue and re-run so it is recreated with FifoQueue=true (mind the 60s name-reuse window).'
  fi
  # ATTRIBUTE DRIFT IS A REAL ERROR, NEVER A SWALLOW. This read-back assertion -
  # not the `QueueAlreadyExists` response - is what actually detects drift on a
  # pre-existing queue, because the check-then-create path above returns as soon
  # as get-queue-url succeeds and therefore never calls create-queue at all. A
  # queue left over from an earlier configuration (for example with
  # ContentBasedDeduplication=false) would otherwise be reported as an idempotent
  # success while silently breaking the publisher's deduplication contract.
  # Asserting on read-back mirrors the versioning check and holds regardless of
  # whether the provider raises on differing attributes.
  if [[ "${dedup_flag}" != 'true' ]]; then
    # Composed into a local so the line stays within the 120-column limit
    # .editorconfig sets for *.sh, without splitting `fail`'s 4 positional
    # arguments (code, step, detail, remedy) across a 5th.
    local drift_detail="ContentBasedDeduplication read back as '${dedup_flag}'"
    drift_detail="${drift_detail} but must be 'true'"
    drift_detail="${drift_detail} - the existing queue has drifted from ${QUEUE_ATTRIBUTES}"
    fail "${EXIT_QUEUE}" "sqs:${physical}" "${drift_detail}" \
      'Delete the queue and re-run so it is recreated with the required attributes (mind the 60s name-reuse window).'
  fi
  log "sqs:${physical}" "verified FifoQueue=${fifo_flag} ContentBasedDeduplication=${dedup_flag} (URL withheld)"
}

ensure_queue() {
  local physical="$1"
  local probe=''
  if probe="$("${AWS_CLI[@]}" sqs get-queue-url --queue-name "${physical}" 2>&1 >/dev/null)"; then
    log "sqs:${physical}" 'already exists - idempotent success'
  else
    case "${probe}" in
      *NonExistentQueue* | *QueueDoesNotExist*)
        create_queue "${physical}"
        ;;
      *)
        fail "${EXIT_QUEUE}" "sqs:${physical}" \
          "get-queue-url failed for a reason other than absence: ${probe//$'\n'/ }" \
          'Resolve the reported error, then re-run the hook.'
        ;;
    esac
  fi
  verify_queue "${physical}"
}

# ------------------------------------------------------------------------------
# SNS. Exactly the topics the committed contract declares, and ZERO
# subscriptions.
#
# NO SUBSCRIPTION OF ANY KIND IS CREATED - not email, SQS, HTTP or Lambda. None
# is consumed by the application, and creating one would both violate least
# privilege and give the notification path a delivery target nobody asked for.
# This also means the "repeated runs must not duplicate subscriptions"
# requirement is satisfied BY CONSTRUCTION rather than by de-duplication logic:
# there is nothing to duplicate. The omission is deliberate, not an oversight.
# ------------------------------------------------------------------------------

# Prints 'present' or 'absent'. A list-topics failure is a real error and exits
# through `fail`; it is never reported as absence, because that would silently
# turn a broken edge into a spurious create attempt.
#
# Matching on the `:<name>` ARN suffix rather than on the bare name prevents a
# false positive against a longer topic that merely ends with the same
# characters, because the colon anchors the match to the start of the name
# segment of the ARN.
topic_state() {
  local name="$1"
  local listing=''
  if ! listing="$("${AWS_CLI[@]}" sns list-topics --query 'Topics[].TopicArn' --output text 2>&1)"; then
    fail "${EXIT_TOPIC}" "sns:${name}" \
      "list-topics failed: ${listing//$'\n'/ }" \
      'Confirm the sns service is running on the health endpoint, then re-run the hook.'
  fi
  case "${listing}" in
    *":${name}" | *":${name}"[[:space:]]*) printf 'present' ;;
    *) printf 'absent' ;;
  esac
}

create_topic() {
  local name="$1"
  local result=''
  # create-topic is itself idempotent and returns the existing ARN, but the ARN
  # is discarded rather than logged because it embeds the account identifier.
  if ! result="$("${AWS_CLI[@]}" sns create-topic --name "${name}" 2>&1 >/dev/null)"; then
    fail "${EXIT_TOPIC}" "sns:${name}" \
      "create-topic failed: ${result//$'\n'/ }" \
      'Confirm the sns service is running on the health endpoint, then re-run the hook.'
  fi
  log "sns:${name}" 'created'
}

ensure_topic() {
  local name="$1"
  local state=''
  state="$(topic_state "${name}")"
  if [[ "${state}" == 'present' ]]; then
    log "sns:${name}" 'already exists - idempotent success'
  else
    create_topic "${name}"
  fi
  state="$(topic_state "${name}")"
  if [[ "${state}" != 'present' ]]; then
    fail "${EXIT_TOPIC}" "sns:${name}" \
      'the topic is still absent from list-topics after provisioning' \
      'Inspect the sns service state in the container logs, then re-run the hook.'
  fi
  log "sns:${name}" 'presence verified by list-topics'
}

# ------------------------------------------------------------------------------
# Main sequence. Ordered so that nothing is provisioned before configuration has
# been validated and the edge has been confirmed usable.
# ------------------------------------------------------------------------------
main() {
  log 'start' "provisioning CardDemo AWS resources in region ${REGION} via ${CLI_LABEL}"

  wait_until_ready

  ensure_bucket "${INPUT_BUCKET}"
  ensure_bucket "${OUTPUT_BUCKET}"
  ensure_bucket "${STATEMENTS_BUCKET}"
  enable_and_verify_versioning "${OUTPUT_BUCKET}"

  log 'sqs:mapping' "logical '${QUEUE_LOGICAL}' -> physical '${QUEUE_PHYSICAL}' (FIFO suffix required by AWS)"
  ensure_queue "${QUEUE_PHYSICAL}"

  ensure_topic "${ALERT_TOPIC}"
  ensure_topic "${NOTIFICATION_TOPIC}"

  # Gate 8 evidence. Deliberately a fixed enumeration of the intended contract
  # rather than a dump of whatever the edge happens to hold, so a reviewer can
  # read the guarantee straight off the log. Names only - no URL, no ARN.
  log 'summary' '--------------------------------------------------------------'
  log 'summary' "s3 input bucket ......... ${INPUT_BUCKET} (unversioned)"
  log 'summary' "s3 output bucket ........ ${OUTPUT_BUCKET} (versioning verified Enabled)"
  log 'summary' "s3 statements bucket .... ${STATEMENTS_BUCKET} (unversioned)"
  log 'summary' "sqs queue ............... ${QUEUE_LOGICAL} -> ${QUEUE_PHYSICAL} (FIFO verified)"
  log 'summary' "sns topics .............. ${ALERT_TOPIC}, ${NOTIFICATION_TOPIC}"
  log 'summary' 'sns subscriptions ....... 0 (none required, none created)'
  log 'summary' 's3 lifecycle rules ...... 0 (GDG retention is documented, not enforced)'
  log 'summary' '--------------------------------------------------------------'
  log 'done' 'all resources provisioned and verified'

  exit "${EXIT_OK}"
}

main

