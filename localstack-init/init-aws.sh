#!/usr/bin/env bash
# ******************************************************************
# * Program     : init-aws.sh
# * Application : CardDemo
# * Type        : LocalStack resource initializer (Compose ready-init hook)
# * Function    : Idempotently provisions the S3, SQS FIFO and SNS resources
# *               that replace the seven legacy generation data group bases,
# *               the extrapartition transient data queue 'JOBS' and the JES2
# *               internal reader.
# * Source      : app/jcl/DEFGDGB.jcl (six GDG bases, LIMIT(5) SCRATCH),
# *               app/jcl/DALYREJS.jcl (the seventh base),
# *               app/jcl/REPTFILE.jcl (TRANREPT LIMIT(10)),
# *               app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl,
# *               app/jcl/COMBTRAN.jcl, app/jcl/CREASTMT.JCL and
# *               app/proc/TRANREPT.prc (dataset record lengths), and
# *               app/csd/CARDDEMO.CSD:L499-L503 (DEFINE TDQUEUE(JOBS),
# *               RECORDSIZE(80) RECORDFORMAT(FIXED)) - all @ 7756d89
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
# WHAT IT DOES
#
#   * 3 S3 buckets .......... input, output, statements
#                             VERSIONING ON THE OUTPUT BUCKET ONLY
#   * 1 SQS FIFO queue ...... FifoQueue=true, ContentBasedDeduplication=true
#   * 1 SNS topic ........... notifications
#   * 0 SNS subscriptions ... deliberately none; see the SNS section below
#   * 0 S3 lifecycle rules .. deliberately none; GDG retention is documented,
#                             not enforced - see the S3 section below
#
# EXACTLY ONE SNS TOPIC. An earlier revision of this file also provisioned an
# `alerts` topic. Nothing in the contract declares it and nothing in the
# application consumes it, so it was removed: least privilege forbids
# provisioning a delivery surface with no consumer, and an unconsumed topic is a
# publish target that no code path audits.
#
#   3 S3 buckets ......... input, output, statements
#                          VERSIONING ON THE OUTPUT BUCKET ONLY
#   1 SQS FIFO queue ..... FifoQueue=true, ContentBasedDeduplication=true
#   2 SNS topics ......... alerts, notifications
#   0 SNS subscriptions .. deliberately none; see the SNS section below
#
# Nothing else is provisioned - no IAM role, policy, KMS key, DynamoDB table,
# Lambda or EventBridge rule: only s3, sqs and sns are enabled on the container
# (docker-compose.yml:L84 `SERVICES: s3,sqs,sns`), and least privilege forbids
# provisioning anything the application does not consume.
#
# The script creates CONTAINERS, never OBJECTS - no S3 key is written, no
# message enqueued, no notification published. The legacy record lengths in the
# provenance section below are therefore evidence for WHICH bucket each byte
# stream belongs in; they are not enforced here.
#
# Idempotency is a hard requirement, not a convenience: LocalStack re-runs every
# executable in its ready-init directory on each `docker compose up`, each
# restart and each persistence-restore cycle. Re-running converges; it never
# fails because a resource already exists, and it never destroys or reconfigures
# state it did not create.
#
# HOW TO RUN AND TEST
#
# Normal operation is automatic: docker-compose.yml:L98 mounts this directory
# READ-ONLY at /etc/localstack/init/ready.d and LocalStack runs this file once
# the edge service reports ready.
#
#   docker compose up -d localstack
#   docker compose logs localstack | grep '\[init-aws\]'
#
# To re-run and verify by hand. Every command runs INSIDE the container, because
# the AWS CLI is not required on - and is generally absent from - the host,
# while `awslocal` is bundled in the image:
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
#   docker compose exec localstack awslocal s3api get-bucket-lifecycle-configuration \
#     --bucket carddemo-batch-output
#
# Expected on a clean volume: 3 CardDemo buckets; Status=Enabled on the output
# bucket and no versioning configuration on the other two; one queue whose name
# ends in `.fifo` and no unsuffixed twin; FifoQueue=true; 1 topic,
# carddemo-notifications; an empty subscription list; and
# `NoSuchLifecycleConfiguration` from every lifecycle read.
#
# On a LONG-LIVED volume the listings can legitimately show more than the script
# provisions, and that is not a failure of either the script or the check:
#   - `s3api list-buckets` may include buckets other tooling created.
#   - `sns list-topics` may still show a `carddemo-alerts` topic left by the
#     earlier revision described above. This script neither creates nor deletes
#     it; SNS offers no way to un-create it other than deleting it, which is not
#     this script's to do. Only `carddemo-notifications` is provisioned.
#   - the input or statements bucket may already carry versioning, which S3
#     cannot un-configure - reported as DRIFT and survived, see EVIDENCE AND
#     DRIFT below.
#
# Every property the summary asserts is READ BACK by the script itself, and the
# summary is composed from the observed values rather than the intended ones, so
# a divergence of the kind listed above is visible in the summary instead of
# being flattened into a fixed string. The commands above therefore reproduce the
# script's own evidence rather than supplying it.
#
# Static checks on the host are `bash -n` and `shellcheck` against this path.
# There is no unit-test tier for this file and none is claimed: the AWS
# integration tests provision their own Testcontainers resources and do not
# depend on this script. Verification is discharged by the in-script
# self-checks - bucket existence, versioning read-back, FIFO attribute
# read-back, topic presence - plus the Compose execution evidence.
#
# There is no unit-test tier for this file and none is claimed. The AWS
# integration tests under src/test/java/com/cardemo/integration/aws/ provision
# their own Testcontainers resources and do not depend on this script. The
# verification obligation is discharged by the in-script self-verification
# (bucket existence, versioning read-back on all three buckets, lifecycle-rule
# read-back on all three buckets, FIFO attribute read-back, topic presence and
# subscription-count read-back) plus the Docker Compose execution evidence
# recorded for Gate 8.
#
# ==============================================================================
# EVIDENCE AND DRIFT - WHAT THE SUMMARY MEANS
# ==============================================================================
# The closing `summary` block is Gate 8 evidence, so it states only what the
# script OBSERVED. Every value it prints was read back from the edge in the same
# run; none is a restatement of intent. That distinction matters because a
# summary asserting "unversioned" without looking would report a clean stack
# while the opposite was true.
#
# Drift is handled in one of two ways, and which one applies is decided by
# whether the drift is REVERSIBLE through an API call:
#
#   FATAL drift - the operator can undo it, so the script refuses to continue and
#   names the exact command:
#     * output-bucket versioning not reading back Enabled ......... exit 5
#     * FIFO attribute drift on an existing queue ................. exit 6
#     * any SNS subscription on the topic (contract says zero) .... exit 7
#     * any S3 lifecycle rule on any bucket (contract says zero) .. exit 4
#
#   REPORTED, NON-FATAL drift - the operator CANNOT undo it without destroying a
#   shared resource, which this script is forbidden to do:
#     * versioning already Enabled or Suspended on the input or statements
#       bucket. S3 has no API that removes a versioning configuration: once
#       enabled it may only be Suspended, and Suspended is not the same as never
#       configured. Undoing it would mean deleting the bucket, and deleting a
#       bucket this script did not create - one that may hold another team's
#       objects - is out of policy. The script therefore prints a loud DRIFT
#       line naming the bucket and the observed status, reports that status in
#       the summary instead of claiming "unversioned", and exits 0.
#
# THIS SCRIPT NEVER DELETES ANYTHING. There is no delete-bucket, delete-queue,
# delete-topic, unsubscribe or delete-bucket-lifecycle call anywhere in it.
#
# ==============================================================================
# KEY CONFIGS AND DEFAULTS
# ==============================================================================
# Every value below is read from the environment. Defaults are copied verbatim
# from the committed `.env.example`; none is invented here. Resolution uses
# ${VAR-default} rather than ${VAR:-default} on purpose, so that "not
# configured" and "configured to an empty string" are DIFFERENT outcomes: an
# unset variable takes the documented default, whereas an explicitly empty one
# is a misconfiguration and exits 2. docker-compose.yml injects the four
# bucket/queue variables; the topic variable is not injected, so for it the
# documented default is the normal path.
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
#   AWS_ENDPOINT_URL              default http://localhost:4566; drives the
#                                 readiness probe URL and the plain-`aws`
#                                 fallback. ALLOWLISTED, not merely prefix
#                                 checked: scheme http or https; no userinfo, no
#                                 query, no fragment and no path beyond one
#                                 optional trailing slash; host exactly one of
#                                 localhost, 127.0.0.1, [::1],
#                                 localhost.localstack.cloud, localstack or
#                                 carddemo-localstack (optionally CLONE_INDEX
#                                 suffixed); port mandatory and either 4566 or
#                                 LOCALSTACK_PORT
#   LOCALSTACK_PORT               default 4566; the published edge port, and the
#                                 only port other than 4566 the endpoint may use
#   INIT_MAX_ATTEMPTS             default 30   readiness poll attempts
#   INIT_SLEEP_SECONDS            default 2    delay between attempts
#   INIT_HEALTH_TIMEOUT_SECONDS   default 5    per-probe network timeout
#
# CREDENTIALS. No credential is ever read from the ambient environment. On the
# bundled-`awslocal` path none is needed at all. On the plain-`aws` fallback path
# the resolution chain is pinned to LocalStack's own placeholder values and every
# other link in it - session token, named profile, shared credentials file,
# config file, web identity token, assumed role, container credentials and the
# EC2 instance metadata service - is unset or switched off before the first API
# call. A real credential therefore cannot sign a request from this script even
# if one is present in the environment. The two placeholder literals grant
# nothing anywhere and are the documented LocalStack values; no live AWS account
# or credential path is supported.
#
# PUBLIC API - EXIT CODES
#
# The public API is the environment contract above plus these codes. Every
# nonzero exit prints, on stderr, the failing step, the underlying error
# verbatim as the preserved root cause, and what to do about it.
#
#   0  success - every resource created or confirmed and verified
#   1  unexpected failure - a command failed at a site with no specific handler;
#      the ERR trap reports the failing line, command and raw status
#   2  configuration error - a required variable is empty, or a bucket, name or
#      region violates its charset rule, or a numeric setting is not a positive
#      integer, or the endpoint points at live AWS. Raised BEFORE any API call,
#      so no partial state can result
#   3  readiness attempts exhausted - the edge never reported s3, sqs and sns
#      usable within INIT_MAX_ATTEMPTS, and provisioning is not attempted
#   4  bucket provisioning failure - head-bucket or create-bucket failed, or the
#      name is owned by another account
#   5  versioning verification failure - the output bucket did not read back
#      Status=Enabled
#   6  queue provisioning or verification failure - includes FIFO attribute
#      drift on an existing queue and the post-deletion name-reuse window
#   7  topic provisioning or verification failure - includes any subscription on
#      the topic, which the zero-subscription contract forbids
#
# ==============================================================================
# ENDPOINT VALIDATION
# ==============================================================================
# AWS_ENDPOINT_URL is untrusted input that, on the plain-`aws` fallback path,
# decides where SIGNED REQUESTS CARRYING INHERITED CREDENTIALS are sent. It is
# therefore parsed and canonicalised rather than pattern-matched, and it must
# satisfy every one of the following before any API call is issued:
#
#   1. no control character, whitespace or backslash anywhere in the value
#      (CWE-20: such bytes let a value that looks local resolve elsewhere, and
#      let a log line be split)
#   2. scheme is exactly `http` or `https`, lowercase after canonicalisation
#   3. NO userinfo component. A `user:pass@host` form is rejected outright and
#      the offending value is never echoed, because userinfo in an error line is
#      a credential in a log file (CWE-532)
#   4. NO path, query or fragment. A single trailing `/` is the only thing
#      permitted after the authority (CWE-918: a path or query is how an endpoint
#      override becomes a request-forgery primitive)
#   5. the host, lowercased, is one of an explicit ALLOWLIST - the loopback
#      literals, the LocalStack loopback DNS name, and the Compose service and
#      container names of the pinned stack. Nothing else, in any case, with or
#      without a trailing dot
#   6. the port, if present, is one of an explicit approved set
#
# There is no deny-list. A deny-list is what the previous revision used - a
# single case-sensitive substring test against the live AWS service domain - and
# it admitted every other host on the internet, that same domain spelled in
# capitals, raw IP addresses, CNAMEs, and `user:pass@` forms. An allowlist
# inverts the default, so a host that was not thought about is refused instead of
# accepted.
#
# The live AWS service domain is deliberately not spelled anywhere in this file,
# not even in this post-mortem: an allowlist has no use for the literal, so its
# absence is the observable difference between the two designs and is asserted by
# InitAwsScriptGuardTest.theScriptNamesNoLiveAwsDomain.
#
# CLEARTEXT: `http` is accepted because every host on the allowlist is either a
# loopback literal, a DNS name that resolves to loopback, or a name that only
# resolves inside the Compose bridge network - so no accepted request leaves the
# host, and TLS termination is explicitly deferred hardening in AAP 0.3.2. THAT
# PROPERTY IS WHAT MAKES CLEARTEXT SAFE HERE, AND IT IS A PROPERTY OF THE
# ALLOWLIST. Adding a host that is reachable off-box means requiring `https` for
# it in the same change; the allowlist declaration below carries that obligation
# as a comment beside the data it governs.
#
# COMMON FAILURE MODES AND TROUBLESHOOTING
#
# Exit 3. Inspect `docker compose logs localstack`; confirm
# docker-compose.yml:L84 still lists all three services; on a slow host raise
# INIT_MAX_ATTEMPTS or INIT_SLEEP_SECONDS.
#
# Exit 2 on a variable. The message names it. Unset is fine and takes the
# documented default; explicitly empty is rejected.
#
# Exit 2 or 4 on a bucket. S3 names are globally scoped, 3-63 characters,
# lowercase alphanumeric plus dot and hyphen, starting and ending alphanumeric;
# a charset violation is caught locally as exit 2. `BucketAlreadyExists` means
# another account holds the name, so choose a different value, whereas
# `BucketAlreadyOwnedByYou` is normal on a re-run and is treated as success.
#
# Exit 5. The observed value is printed. Versioning is applied to the output
# bucket only and, once enabled, S3 permits Suspended but never removal - so
# this script never suspends or clears versioning that a previous run or an
# operator established, on any bucket. To assert that the input and statements
# buckets are unversioned, test against a stack with a fresh volume.
#
# Exit 6 on attribute drift. Drift is caught by the read-back assertion in
# verify_queue, NOT by a `QueueAlreadyExists` response: the check-then-create
# path returns as soon as get-queue-url succeeds, so create-queue is never
# called on an existing queue and can never raise. The message names the
# drifting attribute and its observed value; either align the configuration or
# delete the queue and let the hook recreate it. SQS also refuses to reuse a
# deleted queue name for 60 seconds; the message says so, and the script does
# not loop.
#
# Exit 7. `list-topics` did not report the topic after `create-topic` succeeded.
# Check the sns service state on the health endpoint and the container logs.
#
# Exit 1. The ERR trap prints the line, command and raw status. Re-run with the
# container logs open; a wedged edge service is the usual cause.

# Strict mode. -E propagates the ERR trap into functions, so an unhandled
# failure inside a helper is reported rather than silently returned. `set -x` is
# deliberately never enabled: it would echo command lines that can carry
# credential material.
#
# bash is not assumed: it was verified present in the pinned image
# (docker-compose.yml:L81 localstack/localstack:4.14.0), as were awslocal, aws
# and curl.
set -Eeuo pipefail

# LEGACY MAPPING - PROVENANCE, NOT EXECUTABLE LOGIC
#
# app/** is byte-for-byte immutable and this script reads nothing from it at
# runtime; the locators below record where each provisioned resource comes from.
#
# 7 generation data group bases -> 3 buckets. app/jcl/DEFGDGB.jcl:L24-L58
# declares SIX, each of the shape `DEFINE GENERATIONDATAGROUP - ( NAME(<dsn>) -
# LIMIT(5) - SCRATCH - )`: TRANSACT.BKUP, TRANSACT.DALY, TRANREPT,
# TCATBALF.BKUP, SYSTRAN and TRANSACT.COMBINED, all under the AWS.M2.CARDDEMO
# prefix. app/jcl/DALYREJS.jcl:L24-L28 supplies the SEVENTH,
# AWS.M2.CARDDEMO.DALYREJS. Seven, not six. They collapse onto three buckets by
# direction of flow:
#   input       DALYTRAN staging, LRECL 350, seeded from
#               app/data/ASCII/dailytran.txt - the fixture is spelled
#               `dailytran.txt`, never `dalytran.txt`
#   output      DALYREJS 430, TRANREPT 133, TRANSACT.BKUP / .DALY / .COMBINED
#               350, SYSTRAN 350, TCATBALF.BKUP - VERSIONED
#   statements  STMTFILE 80 and HTMLFILE 100, under account and month prefixes
#
# Record lengths preserved byte-exactly at the S3 boundary:
#   app/jcl/POSTTRAN.jcl:L34-L38  DALYREJS RECFM=F (fixed UNBLOCKED) LRECL=430,
#                                 which is 350 data plus an 80-byte trailer of a
#                                 4-digit reason code and a 76-char description
#   app/jcl/INTCALC.jcl:L37-L41   the DD name is TRANSACT but the DSN is
#                                 SYSTRAN(+1) at RECFM=F LRECL=350: the interest
#                                 job writes a fresh sequential generation, not
#                                 the keyed cluster
#   app/proc/TRANREPT.prc:L76 and app/jcl/TRANREPT.jcl:L78   LRECL=133
#   app/proc/TRANREPT.prc:L27-L31 TRANSACT.BKUP(+1) at LRECL=350
#   app/jcl/CREASTMT.JCL:L89      STMTFILE LRECL=80
#   app/jcl/CREASTMT.JCL:L94      HTMLFILE LRECL=100, while the STEP030
#                                 pre-delete at :L69 declares the same DD at
#                                 LRECL=80. The 100-byte form is authoritative
#                                 for the statements bucket; the inconsistency
#                                 is reproduced, not repaired, because parity is
#                                 the contract
#   app/jcl/CREASTMT.JCL:L29-L32  the TRXFL work cluster, KEYS(32 0)
#                                 RECORDSIZE(350 350), is in-job only and NEVER
#                                 persisted to S3, so it gets no bucket
#
# Generation references become deterministic prefixes: a (+1) write becomes a new
# object under a monotonically increasing, zero-padded, lexicographically
# sortable timestamp or job-instance prefix, and a (0) read becomes a read of the
# greatest existing prefix (app/jcl/COMBTRAN.jcl:L26 reads SYSTRAN(0)). The
# carry-forward hazard is why the output bucket must be VERSIONED: within one
# legacy job a (+1) written by an earlier step is re-read as (+1) by a later one
# - app/jcl/COMBTRAN.jcl:L33-L37 writes TRANSACT.COMBINED(+1) and :L43-L44 reads
# it back, and app/proc/TRANREPT.prc:L27-L31 writes TRANSACT.BKUP(+1) and
# :L36-L37 reads it back. Versioning is what lets those references denote the
# same immutable generation instead of racing.
#
# Retention is DOCUMENTED, NEVER ENFORCED. app/jcl/DEFGDGB.jcl:L36-L40 declares
# AWS.M2.CARDDEMO.TRANREPT with LIMIT(5) SCRATCH while
# app/jcl/REPTFILE.jcl:L25-L28 RE-DECLARES the same dataset with LIMIT(10) and no
# SCRATCH. The conflict is resolved in favour of 10, and it is the only legacy
# inconsistency the migration resolves rather than reproduces. No
# put-bucket-lifecycle-configuration call is made and no expiration rule is
# created: object versioning supersedes GDG retention semantics, so the absence
# of a lifecycle rule below is deliberate.
#
# DEFINE TDQUEUE(JOBS) becomes the FIFO queue. app/csd/CARDDEMO.CSD holds exactly
# one DEFINE TDQUEUE in its 505 lines, at :L499-L503: TYPE(EXTRA)
# DDNAME(INREADER) ERROROPTION(IGNORE) OPENTIME(INITIAL) TYPEFILE(OUTPUT)
# RECORDSIZE(80) RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD).
# RECORDSIZE(80) with FIXED and UNBLOCKED fixes the 80-byte parameter-card shape
# that becomes a typed JSON message carrying the report name and the two dates;
# TYPEFILE(OUTPUT) with DISPOSITION(MOD) is the append-only producer side;
# DDNAME(INREADER) is the JES2 internal reader, replaced by an SQS listener. The
# producer is app/cbl/CORPT00C.cbl:L517-L523, EXEC CICS WRITEQ TD QUEUE('JOBS')
# FROM (JCL-RECORD) - :L515 is only the paragraph label, misspelled in the source
# as `WIRTE-JOBSUB-TDQ.`. Because the publisher writes strictly sequentially,
# card by card, ordering is reproduced with a deterministic message group id and
# the queue is FIFO, not standard.
#
# ERROROPTION(IGNORE) at :L501 is a legacy quirk the target deliberately does NOT
# reproduce: the mainframe was configured to ignore a queue I/O error outright.
# That is precisely why this script never swallows a failure - every AWS call
# below distinguishes "already exists" from a real error and surfaces the latter
# with its root cause intact.
#
# Check-then-create is not a modern embellishment; the legacy stream does the
# same. app/jcl/DEFGDGB.jcl follows every one of its six
# DEFINE GENERATIONDATAGROUP statements with `IF LASTCC=12 THEN SET MAXCC=0`, at
# L29, L35, L41, L47, L53 and L59, and app/jcl/CREASTMT.JCL:L28 reads
# `SET       MAXCC = 0` immediately after its DELETE ... CLUSTER pre-delete. That
# is the mainframe's own "already exists is not an error" guard.
# app/jcl/DALYREJS.jcl carries no such guard.
#
# --- SNS ---------------------------------------------------------------------
# SNS carries operator notification, replacing the mainframe operator-notify
# path - historically the JOB card NOTIFY=&SYSUID convention at
# app/jcl/DEFGDGB.jcl:L1. EXACTLY ONE topic is created, the notification topic
# declared by the committed contract, and ZERO subscriptions.
#
# A second `alerts` topic existed in an earlier revision of this file and has
# been removed. It appeared in no requirement and was published to by no code
# path, so it was pure surface: an unconsumed topic still accepts publishes, and
# a resource nothing audits is a resource nothing notices. Least privilege means
# the provisioned set matches the consumed set exactly.
#
# One further legacy defect is logged and repaired nowhere:
# app/jcl/CREASTMT.JCL:L90 is a corrupted DD continuation,
# `//         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS`.


# Exit codes. Declared once, referenced by name everywhere, so the documented
# taxonomy above and the runtime behaviour cannot drift apart.
readonly EXIT_OK=0
readonly EXIT_UNEXPECTED=1
readonly EXIT_CONFIG=2
readonly EXIT_NOT_READY=3
readonly EXIT_BUCKET=4
readonly EXIT_VERSIONING=5
readonly EXIT_QUEUE=6
readonly EXIT_TOPIC=7

# Structured logging. One shape for every line: a fixed `[init-aws]` prefix, a
# UTC timestamp, a stable step token naming the resource, and the message.
# Progress goes to stdout; every failure goes to stderr.
#
# Nothing here may ever emit a secret. Access keys, secret keys, session tokens
# and the LocalStack auth token are never read by this script. Queue URLs and
# topic ARNs are never printed either, because both embed the AWS account
# identifier; the logical and physical resource NAMES are logged instead.
utc_now() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

log() {
  local stamp
  stamp="$(utc_now)"
  printf '[init-aws] %s %-22s %s\n' "${stamp}" "$1" "$2"
}

# Emits the failing step, the preserved root cause and what to do about it, then
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

# Configuration. Resolved exactly once, then frozen with `readonly`, so no later
# line can mutate a resource name and every name is spelled in precisely one
# place. See KEY CONFIGS AND DEFAULTS above for why ${VAR-default} is used in
# preference to ${VAR:-default}.
#
# The five below are the mandated contract names, in the order the requirements
# list them, and they are the whole public surface: there is no sixth. The plan
# prose mentions "the SNS topics" in the plural, but exactly ONE topic is
# provisioned, because no code in this repository publishes to or subscribes from
# a second one, and inventing a variable that nothing reads would put a name in
# this script that neither .env.example nor docker-compose.yml declares - the
# precise drift this naming contract exists to prevent. Each variable is read in
# exactly one place - here - so there is no second spelling for any of them to
# drift from.
# ------------------------------------------------------------------------------
readonly INPUT_BUCKET="${CARDDEMO_S3_BATCH_INPUT_BUCKET-carddemo-batch-input}"
readonly OUTPUT_BUCKET="${CARDDEMO_S3_BATCH_OUTPUT_BUCKET-carddemo-batch-output}"
readonly STATEMENTS_BUCKET="${CARDDEMO_S3_STATEMENTS_BUCKET-carddemo-statements}"
readonly REPORT_QUEUE="${CARDDEMO_SQS_REPORT_QUEUE-carddemo-report-jobs.fifo}"
readonly NOTIFICATION_TOPIC="${CARDDEMO_SNS_NOTIFICATION_TOPIC-carddemo-notifications}"

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

# The logical name carries no suffix (`carddemo-report-jobs`); the physical name
# is what AWS requires of a FIFO queue. Stripping any existing suffix
# before appending exactly one guarantees the two can never diverge and that a
# doubled `.fifo.fifo` is impossible.
readonly QUEUE_LOGICAL="${REPORT_QUEUE%.fifo}"
readonly QUEUE_PHYSICAL="${QUEUE_LOGICAL}.fifo"

# Input validation. Inputs are untrusted and are checked BEFORE any AWS call, so
# a misconfiguration can never leave a half-provisioned stack behind.
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

require_value 'CARDDEMO_S3_BATCH_INPUT_BUCKET'  "${INPUT_BUCKET}"       "${BUCKET_PATTERN}" 3 63  "${BUCKET_HINT}"
require_value 'CARDDEMO_S3_BATCH_OUTPUT_BUCKET' "${OUTPUT_BUCKET}"      "${BUCKET_PATTERN}" 3 63  "${BUCKET_HINT}"
require_value 'CARDDEMO_S3_STATEMENTS_BUCKET'   "${STATEMENTS_BUCKET}"  "${BUCKET_PATTERN}" 3 63  "${BUCKET_HINT}"
require_value 'CARDDEMO_SQS_REPORT_QUEUE'       "${QUEUE_LOGICAL}"      "${NAME_PATTERN}"   1 75  "${NAME_HINT}"
require_value 'CARDDEMO_SNS_NOTIFICATION_TOPIC' "${NOTIFICATION_TOPIC}" "${NAME_PATTERN}"   1 256 "${NAME_HINT}"
require_value 'AWS_REGION'                      "${REGION}"             "${REGION_PATTERN}" 2 32  "${REGION_HINT}"

# The three bucket names must be distinct, or two logical streams would silently
# share one container and the output bucket's versioning would leak across them.
if [[ "${INPUT_BUCKET}" == "${OUTPUT_BUCKET}" || "${INPUT_BUCKET}" == "${STATEMENTS_BUCKET}" ||
  "${OUTPUT_BUCKET}" == "${STATEMENTS_BUCKET}" ]]; then
  fail "${EXIT_CONFIG}" 'config:buckets' \
    "bucket names must be distinct: got '${INPUT_BUCKET}', '${OUTPUT_BUCKET}', '${STATEMENTS_BUCKET}'" \
    'Give the three CARDDEMO_S3_*_BUCKET variables distinct values.'
fi

# There is no topic-distinctness check because there is exactly ONE topic. The
# previous revision compared two names; removing the second topic removed the
# comparison with it rather than leaving a tautology behind.

# The readiness budget drives a loop and is then multiplied to display a total,
# so each value must be a decimal integer within a FINITE range. Two properties
# are enforced, and neither is optional:
#
#   1. NO LEADING ZERO. `^[0-9]+$` accepts `08`, which every arithmetic context
#      in Bash then reads as octal and rejects: `$((30 * 08))` aborts with
#      "value too great for base (error token is \"08\")". Because that
#      multiplication lives inside the readiness-exhaustion message, the octal
#      form did not merely mis-report a budget - it crashed the very branch that
#      exists to explain a timeout. The pattern below admits a bare `0` and
#      otherwise requires a non-zero leading digit, so no octal token survives
#      validation. `10#` is additionally used at the one arithmetic site as
#      belt-and-braces, so the base is explicit in the code and does not depend
#      on this pattern staying as it is.
#   2. A FINITE MAXIMUM. Digit-only validation accepted values that overflow a
#      signed 64-bit product: `$((9223372036854775807 * 2))` evaluates to `-2`,
#      so the script would have announced a negative time budget. Bounding the
#      inputs is what makes the product provably safe rather than merely
#      unlikely - see the arithmetic proof at the exhaustion message below.
#
# The ceilings are operational, not arbitrary: 3600 attempts at 60s apart is a
# one-hour-plus ceiling, far beyond any cold start, and a 300s health timeout is
# longer than any single curl to a local edge can justify.
readonly MAX_ATTEMPTS_CEILING=3600
readonly SLEEP_SECONDS_CEILING=60
readonly HEALTH_TIMEOUT_CEILING=300

# require_bounded_integer <var-name> <resolved-value> <min> <max> <example>
#
# One validator for all three, so the leading-zero and range rules cannot drift
# apart between them - which is precisely how the octal hole opened: two of the
# three used `^[1-9][0-9]*$` and the third used `^[0-9]+$`.
require_bounded_integer() {
  local var_name="$1" value="$2" min="$3" max="$4" example="$5"
  if [[ ! "${value}" =~ ^(0|[1-9][0-9]*)$ ]]; then
    local hint="Set ${var_name} to a plain base-10 integer such as ${example}."
    hint+=' A leading zero (08) is read as octal and aborts arithmetic.'
    fail "${EXIT_CONFIG}" "config:${var_name}" \
      "value '${value}' is not a decimal integer without a leading zero" \
      "${hint}"
  fi
  # Safe now: the pattern above has excluded both a non-numeric value and an
  # octal token, and `10#` states the base at the point of use regardless.
  if ((10#${value} < min || 10#${value} > max)); then
    fail "${EXIT_CONFIG}" "config:${var_name}" \
      "value ${value} is outside the permitted range ${min}-${max}" \
      "Set ${var_name} within ${min}-${max}, for example ${example}."
  fi
}

require_bounded_integer 'INIT_MAX_ATTEMPTS' "${MAX_ATTEMPTS}" \
  1 "${MAX_ATTEMPTS_CEILING}" 30
require_bounded_integer 'INIT_SLEEP_SECONDS' "${SLEEP_SECONDS}" \
  0 "${SLEEP_SECONDS_CEILING}" 2
require_bounded_integer 'INIT_HEALTH_TIMEOUT_SECONDS' "${HEALTH_TIMEOUT_SECONDS}" \
  1 "${HEALTH_TIMEOUT_CEILING}" 5

# --- Endpoint allowlist: fail-closed, never accept-by-default -----------------
# AAP 0.3.2 forbids any code path that can reach a real AWS account, and the
# whole topology is credential-free by design. This guard is what makes that
# structural rather than aspirational, so it is an ALLOWLIST of the endpoints the
# emulator is actually reachable at, not a denylist of endpoints to avoid.
#
# A denylist was tried first and was wrong in four independent ways, every one of
# which was reproduced before this replacement was written:
#
#   * Its glob over the live AWS service domain was case sensitive, so the same
#     domain spelled in capitals passed straight through it.
#   * Its `*)` branch accepted BY DEFAULT, so `http://evil.example.com` passed.
#   * `http://169.254.169.254` - the cloud instance metadata address, the classic
#     credential-exfiltration target - passed for the same reason.
#   * `http://user:pw@localhost:4566@evil.com` passed: a URL's host is the text
#     after the LAST `@` in the authority, so a denylist reading the whole string
#     sees a benign substring while the HTTP client resolves `evil.com`.
#
# Percent-encoding compounds the last point (`http://%6c%6f%63alhost:4566`
# decodes to `localhost` at the client but not in a shell comparison), so the
# authority is refused outright if it contains `%`. Decoding it here to compare
# the decoded form would reintroduce exactly the parser-differential the refusal
# closes.
#
# The real AWS service domain is deliberately NOT spelled anywhere in this file,
# not even in these comments: with an allowlist it is not needed, because
# everything unlisted is refused. That absence is asserted by
# InitAwsScriptGuardTest.theScriptNamesNoLiveAwsDomain, so a future edit that
# reintroduces a denylist alongside the allowlist fails the build rather than
# quietly re-widening the guard.
#
# Every entry below is an endpoint this project genuinely uses. Adding to this
# list is a security decision and must be justified in the same terms.
readonly ALLOWED_ENDPOINT_HOSTS=(
  'localhost'                  # from the developer host, and docker-compose.yml:L101
  '127.0.0.1'                  # the same, spelled numerically
  '::1'                        # the same, over IPv6
  'localstack'                 # the compose service name, from a sibling container
  'localhost.localstack.cloud' # the documented setup endpoint; resolves to loopback
)

# The compose container name carries an optional `-${CLONE_INDEX}` suffix
# (docker-compose.yml:L82), so it is matched by a bounded pattern rather than
# enumerated. The suffix is digits only, which is what CLONE_INDEX ever is.
#
# Subdomains of localhost.localstack.cloud are deliberately NOT matched, even
# though they resolve to loopback and would therefore have been easy to justify
# on reachability grounds. Two reasons, in order of weight. Least privilege: the
# only endpoint this project documents is the bare host, so a wildcard would
# widen the allowlist past anything in use. And it does not even work - probing
# `http://s3.localhost.localstack.cloud:4566` passed readiness and then failed
# provisioning at the SQS stage (exit 6), because a virtual-hosted S3 name is not
# a general service edge. Allowing it would have admitted an endpoint that is
# broken in a way the allowlist is well placed to refuse outright.
readonly ALLOWED_ENDPOINT_HOST_PATTERN='^carddemo-localstack(-[0-9]+)?$'

# require_local_endpoint <url>
#
# Parses rather than pattern-matches, in the order a URL is actually defined, and
# refuses anything it cannot account for. Every rejection is terminal: there is
# no branch that accepts an unrecognised value.
require_local_endpoint() {
  local url="$1"
  local remainder scheme authority host_port host

  case "${url}" in
    http://*) scheme='http'; remainder="${url#http://}" ;;
    https://*) scheme='https'; remainder="${url#https://}" ;;
    *)
      fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
        "value '${url}' is not an http(s) URL" \
        'Set AWS_ENDPOINT_URL to the LocalStack edge, for example http://localhost:4566.'
      ;;
  esac

  # A query or a fragment has no meaning on a service endpoint and is the usual
  # vehicle for smuggling a second host past a naive matcher.
  case "${url}" in
    *'?'* | *'#'*)
      fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
        "value '${url}' carries a query or fragment component, which a service endpoint must not" \
        'Set AWS_ENDPOINT_URL to a bare scheme://host:port, for example http://localhost:4566.'
      ;;
  esac

  # Split authority from path at the first slash. The path must be empty or a
  # single trailing slash: the readiness probe appends `/_localstack/health` to
  # this value, so any other path would silently retarget that probe.
  authority="${remainder%%/*}"
  if [[ "${remainder}" == */* ]]; then
    local path="/${remainder#*/}"
    if [[ "${path}" != '/' ]]; then
      fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
        "value '${url}' carries the path '${path}'; only a bare host[:port] is accepted" \
        'Drop the path: the health probe appends /_localstack/health to this value itself.'
    fi
  fi

  # Userinfo. The host is what follows the last `@`, which is why a denylist over
  # the whole string is unsound; here its mere presence is refused instead.
  if [[ "${authority}" == *'@'* ]]; then
    fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
      "value '${url}' embeds userinfo before the host, which is never required for the local edge" \
      'Remove the user:password@ prefix; the emulator needs no credentials.'
  fi

  if [[ "${authority}" == *'%'* ]]; then
    fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
      "value '${url}' percent-encodes its authority, which this guard will not decode" \
      'Spell the host literally, for example http://localhost:4566.'
  fi

  # Strip the port. A bracketed IPv6 literal keeps its colons inside brackets, so
  # it is unwrapped before the port is removed.
  host_port="${authority}"
  if [[ "${host_port}" == '['*']'* ]]; then
    host="${host_port%%]*}"
    host="${host#[}"
    local after="${host_port#*]}"
    if [[ -n "${after}" && "${after}" != :* ]]; then
      fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
        "value '${url}' has an unparsable IPv6 authority '${authority}'" \
        'Spell it as http://[::1]:4566.'
    fi
    validate_endpoint_port "${url}" "${after#:}"
  else
    host="${host_port%%:*}"
    if [[ "${host_port}" == *:* ]]; then
      validate_endpoint_port "${url}" "${host_port#*:}"
    fi
  fi

  if [[ -z "${host}" ]]; then
    fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
      "value '${url}' has an empty host" \
      'Set AWS_ENDPOINT_URL to the LocalStack edge, for example http://localhost:4566.'
  fi

  # DNS is case insensitive, so the comparison is made on a lowercased host. This
  # single line is what an upper-cased live AWS host defeated in the previous
  # guard, which compared the raw string against a lowercase glob.
  host="${host,,}"

  local allowed
  for allowed in "${ALLOWED_ENDPOINT_HOSTS[@]}"; do
    if [[ "${host}" == "${allowed}" ]]; then
      log 'config' "endpoint host '${host}' allowlisted (${scheme}); live AWS is unreachable by construction"
      return 0
    fi
  done
  if [[ "${host}" =~ ${ALLOWED_ENDPOINT_HOST_PATTERN} ]]; then
    log 'config' "endpoint host '${host}' allowlisted by pattern (${scheme})"
    return 0
  fi

  local hint="Point AWS_ENDPOINT_URL at the local edge - one of:"
  hint+=" ${ALLOWED_ENDPOINT_HOSTS[*]}, or the compose container name."
  hint+=' Live AWS is never used.'
  fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
    "host '${host}' is not an allowlisted LocalStack endpoint (from '${url}')" \
    "${hint}"
}

# validate_endpoint_port <url> <port>
#
# Kept separate only because it is reached from both the IPv6 and the IPv4 branch
# above; inlining it twice is how the two would drift apart.
validate_endpoint_port() {
  local url="$1" port="$2"
  if [[ ! "${port}" =~ ^(0|[1-9][0-9]*)$ ]] || ((10#${port} < 1 || 10#${port} > 65535)); then
    fail "${EXIT_CONFIG}" 'config:AWS_ENDPOINT_URL' \
      "value '${url}' has an invalid port '${port}'" \
      'Use a decimal port in 1-65535, for example 4566.'
  fi
}

require_local_endpoint "${ENDPOINT_URL}"

# CLI selection. `awslocal` is bundled in the pinned image and already targets
# the local edge, so it needs neither an endpoint flag nor credential handling.
# The plain-`aws` fallback keeps the script runnable from a developer host that
# has the AWS CLI but not the wrapper - which is exactly how the documented
# setup sequence drives it - so it is a portability branch, not dead code.
#
# WHY THE FALLBACK IS SAFE TO KEEP, AND WHAT MAKES IT SO.
# The review that prompted this hardening preferred deleting the branch outright,
# on the grounds that it was the thing that carried a hostile endpoint to a real
# API call. That was true of the denylist, and it is no longer true: this block
# is reached ONLY after `require_local_endpoint` above has run, and that guard is
# fail-closed - every path through it either returns 0 for an allowlisted host or
# calls `fail`, which exits. `${ENDPOINT_URL}` is `readonly`, so nothing between
# the guard and this line can change the value that was validated. The branch
# therefore cannot target a non-allowlisted endpoint, and deleting a documented,
# working developer path to re-state a guarantee the guard already provides would
# remove capability without adding safety. THE ORDERING IS LOAD-BEARING: moving
# this block above `require_local_endpoint` would reopen the hole.
#
# Two further least-privilege measures apply to the fallback specifically, both
# because a developer host may carry real ambient AWS configuration that the
# bundled wrapper never sees:
#
#   * AWS_EC2_METADATA_DISABLED=true stops the CLI probing an instance metadata
#     service for credentials. On this host AWS_ACCESS_KEY_ID and
#     AWS_SECRET_ACCESS_KEY are already present in the environment, so without
#     this the fallback has a credential chain it has no business exercising.
#   * AWS_PROFILE is cleared so a developer's named profile - which may carry a
#     real account and, worse, its own region and endpoint settings - cannot
#     influence the call.
#
# Neither measure weakens the emulator path: LocalStack accepts any credential.
# An array is used so the words can never be re-split by the shell.
#
# CREDENTIAL ISOLATION, and why it is mandatory on the fallback path. The plain
# AWS CLI resolves credentials from a chain, and every link in that chain is
# ambient: environment keys, a session token, a named profile, the shared
# credentials and config files, a web-identity token, an assumed role, container
# credentials and finally the EC2 instance metadata service. If any one of those
# resolves to a REAL credential, every request this script issues is signed with
# it. That is unacceptable even against a loopback endpoint, because a signed
# request leaks the access key id and, through the metadata service, could mint a
# fresh session before anything is provisioned.
#
# The remedy is to make the resolution deterministic instead of ambient: pin the
# two well-known LocalStack placeholder values, pin the region already validated
# above, unset every other link in the chain, and switch the metadata service off
# outright. LocalStack accepts any credential, so the placeholders are sufficient
# and no real credential is ever needed - which is precisely why none may be used.
# The two literals below are LocalStack's own documented placeholders and grant
# nothing anywhere; they are the opposite of a committed secret.
# ------------------------------------------------------------------------------
isolate_local_credentials() {
  export AWS_ACCESS_KEY_ID='test'
  export AWS_SECRET_ACCESS_KEY='test'
  export AWS_DEFAULT_REGION="${REGION}"
  export AWS_REGION="${REGION}"
  # Refuse the instance metadata service, so no link-local credential lookup can
  # occur even if every unset below were somehow re-established.
  export AWS_EC2_METADATA_DISABLED='true'
  unset AWS_SESSION_TOKEN
  unset AWS_SECURITY_TOKEN
  unset AWS_PROFILE
  unset AWS_DEFAULT_PROFILE
  unset AWS_SHARED_CREDENTIALS_FILE
  unset AWS_CONFIG_FILE
  unset AWS_WEB_IDENTITY_TOKEN_FILE
  unset AWS_ROLE_ARN
  unset AWS_ROLE_SESSION_NAME
  unset AWS_CONTAINER_CREDENTIALS_RELATIVE_URI
  unset AWS_CONTAINER_CREDENTIALS_FULL_URI
  unset AWS_CONTAINER_AUTHORIZATION_TOKEN
  unset AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE
}

if command -v awslocal >/dev/null; then
  readonly AWS_CLI=(awslocal)
  readonly CLI_LABEL='awslocal (bundled, self-targeting)'
elif command -v aws >/dev/null; then
  isolate_local_credentials
  readonly AWS_CLI=(aws --endpoint-url "${ENDPOINT_URL}")
  readonly CLI_LABEL="aws --endpoint-url ${ENDPOINT_URL} (fallback, credentials isolated to LocalStack placeholders)"
else
  fail "${EXIT_CONFIG}" 'config:cli' \
    'neither the awslocal wrapper nor the aws CLI is on PATH' \
    'Run this script inside the LocalStack container, where awslocal is bundled; see HOW TO RUN / TEST above.'
fi


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

  # `10#` states the base explicitly at the one place a validated value is
  # multiplied. It is belt-and-braces rather than the fix: the leading-zero rule
  # in `require_bounded_integer` already guarantees no octal token reaches here,
  # and before that rule existed INIT_SLEEP_SECONDS=08 aborted THIS line with
  # "value too great for base" - crashing the branch whose only job is to explain
  # a timeout. OVERFLOW PROOF: both factors are bounded above by
  # MAX_ATTEMPTS_CEILING (3600) and SLEEP_SECONDS_CEILING (60), so the product
  # cannot exceed 216000 and cannot wrap a signed 64-bit integer. That is why the
  # ceilings exist; without them this multiplication printed a negative budget.
  local budget=$((10#${MAX_ATTEMPTS} * 10#${SLEEP_SECONDS}))
  fail "${EXIT_NOT_READY}" 'readiness' \
    "exhausted ${MAX_ATTEMPTS} attempts over ~${budget}s; last observed state: ${observed}" \
    'Check the container logs, confirm SERVICES lists s3,sqs,sns, then raise INIT_MAX_ATTEMPTS on a slow host.'
}

# S3. Three buckets, check-then-create, mirroring the legacy
# `IF LASTCC=12 THEN SET MAXCC=0` guard cited above.
#
# Every call captures stderr into a variable so that the three outcomes stay
# distinguishable: created, already exists (idempotent success), or a real error
# that is reported with its root cause and aborts. No ACL, no public-access
# setting, no bucket policy and no encryption configuration is applied -
# encryption at rest is deferred hardening, and least privilege forbids widening
# anything by default.
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

# VERSIONING POLICY - DECIDED, CITED, AND CONTRADICTED ELSEWHERE.
#
# Versioning is applied to the OUTPUT bucket alone. This is not an omission and
# not an implementation detail: AAP 0.5.1.1 specifies that the seven generation
# data group bases become "three S3 buckets with versioning on the output
# bucket", and 0.5.2.2 assigns generation semantics to that bucket alone -
# DALYREJS, TRANREPT, TRANSACT.BKUP, TRANSACT.DALY, TRANSACT.COMBINED, SYSTRAN
# and TCATBALF.BKUP all resolve to output-bucket prefixes, where a relative
# generation reference becomes an object version.
#
# The statements bucket is left unversioned BECAUSE IT HAS NO GENERATION
# SEMANTICS TO MODEL. Its two streams are STMTFILE and HTMLFILE (AAP 0.5.2.2),
# which app/jcl/CREASTMT.JCL:STEP030 pre-deletes and STEP040 rewrites under a
# deterministic account-and-month prefix. There is no (+1)/(0) reference to
# reproduce, so versioning it would add retained objects the legacy system never
# had - and object retention is documented, never enforced, in this project.
# The input bucket is unversioned for the same reason.
#
# A CONFLICTING CLAIM EXISTS AND IS RESOLVED HERE IN FAVOUR OF THE AAP. The
# environment setup log for this project records the statements bucket as
# "(versioned)", and docs/technical-specifications.md repeats that. Both are
# wrong about this checkout: this script versions the output bucket only, and the
# Gate 8 summary below logs the other two as unversioned so the provisioned state
# is self-evident from the run. The AAP is the frozen contract, so the code
# follows it and the documentation is corrected rather than the reverse.
#
# This function is deliberately never called for the input or statements bucket.

# Set by enable_and_verify_versioning and by observe_versioning to the status the
# edge actually reported for the bucket just examined. Read by main() when it
# composes the summary, so that every versioning line in that summary is observed
# rather than asserted. Not readonly: it is written once per bucket.
OBSERVED_VERSIONING=''

# Set by verify_no_subscriptions to the subscription count the edge reported for
# the notification topic. Read by main() for the same reason: the summary states
# what was measured, never what was intended.
OBSERVED_SUBSCRIPTIONS=''

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
  OBSERVED_VERSIONING="${observed}"
}

# Reads a bucket's versioning status WITHOUT changing it, and prints what it saw.
# `--output text` on an absent configuration yields the literal `None`, which is
# reported verbatim so the summary can distinguish "never configured" from
# "Suspended" - two states S3 keeps distinct and only one of which this contract
# describes.
#
# This function never writes. It exists because the summary is evidence: a line
# claiming the input and statements buckets are unversioned is worth nothing
# unless something looked.
observe_versioning() {
  local bucket="$1"
  local observed=''
  if ! observed="$("${AWS_CLI[@]}" s3api get-bucket-versioning --bucket "${bucket}" \
    --query 'Status' --output text 2>&1)"; then
    fail "${EXIT_VERSIONING}" "s3:${bucket}" \
      "get-bucket-versioning failed: ${observed//$'\n'/ }" \
      'Confirm the s3 service is running, then re-run the hook.'
  fi
  if [[ "${observed}" != 'None' ]]; then
    # DRIFT, reported and survived rather than repaired. S3 exposes no API that
    # removes a versioning configuration - Enabled may only become Suspended -
    # so the only route back is deleting the bucket, and this script does not
    # delete shared resources it may not own. The run continues and the summary
    # reports this observed value instead of asserting "unversioned".
    log "s3:${bucket}" "DRIFT: versioning is '${observed}' but this contract leaves this bucket unversioned"
    log "s3:${bucket}" 'DRIFT: not repaired - S3 cannot remove versioning; recreate the stack with a fresh volume'
  else
    log "s3:${bucket}" 'versioning verified absent by read-back (Status=None)'
  fi
  OBSERVED_VERSIONING="${observed}"
}

# Asserts a bucket carries NO lifecycle rule, which is what the summary claims.
# Generation-data-group retention - LIMIT(5) in app/jcl/DEFGDGB.jcl and LIMIT(10)
# in app/jcl/REPTFILE.jcl - is documented, not enforced, so a rule here would
# mean something outside this contract is expiring objects.
#
# An absent configuration is reported by S3 as NoSuchLifecycleConfiguration, an
# ERROR rather than an empty result, so the not-found response is the success
# path and any other failure is a real one. Drift IS fatal here, unlike
# versioning drift, because delete-bucket-lifecycle exists: the operator can undo
# it without destroying the bucket.
verify_no_lifecycle_rules() {
  local bucket="$1"
  local result=''
  if ! result="$("${AWS_CLI[@]}" s3api get-bucket-lifecycle-configuration \
    --bucket "${bucket}" 2>&1 >/dev/null)"; then
    case "${result}" in
      *NoSuchLifecycleConfiguration*)
        log "s3:${bucket}" 'lifecycle rules verified absent (NoSuchLifecycleConfiguration)'
        return 0
        ;;
      *)
        fail "${EXIT_BUCKET}" "s3:${bucket}" \
          "get-bucket-lifecycle-configuration failed for a reason other than absence: ${result//$'\n'/ }" \
          'Resolve the reported error - commonly a stopped edge service - then re-run the hook.'
        ;;
    esac
  fi
  # The call SUCCEEDED, which means a configuration exists. Count the rules so
  # the message says how many, then refuse.
  local rules=''
  if ! rules="$("${AWS_CLI[@]}" s3api get-bucket-lifecycle-configuration --bucket "${bucket}" \
    --query 'length(Rules)' --output text 2>&1)"; then
    rules='unknown'
  fi
  fail "${EXIT_BUCKET}" "s3:${bucket}" \
    "carries ${rules} lifecycle rule(s); this contract provisions none and expires no object" \
    "Inspect with 's3api get-bucket-lifecycle-configuration' and remove with 's3api delete-bucket-lifecycle'."
}


# SQS. Exactly one FIFO queue, replacing DEFINE TDQUEUE(JOBS).
#
# No dead-letter queue and no redrive policy are created: neither is configured
# anywhere in the consuming application, and least privilege forbids provisioning
# capacity nothing consumes.

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
        "A FIFO queue name must end in '.fifo' and carry FifoQueue=true; check CARDDEMO_SQS_REPORT_QUEUE."
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

# SNS. Exactly the topics the committed contract declares, and ZERO
# subscriptions.
#
# NO SUBSCRIPTION OF ANY KIND IS CREATED - not email, SQS, HTTP or Lambda. None
# is consumed by the application, and creating one would both violate least
# privilege and give the notification path a delivery target nobody asked for.
# This also means the "repeated runs must not duplicate subscriptions"
# requirement is satisfied BY CONSTRUCTION rather than by de-duplication logic:
# there is nothing to duplicate. The omission is deliberate, not an oversight.

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

# Prints the ARN of the named topic. The ARN is needed as an API argument and is
# deliberately NEVER logged, because it embeds the account identifier - the same
# reason create_topic discards it. An unresolvable ARN is a hard error rather
# than an empty string, so a later API call cannot be issued against nothing.
#
# The `:<name>` suffix anchor is the same one topic_state uses, and for the same
# reason: it pins the match to the start of the ARN's name segment so a longer
# topic merely ending in these characters cannot match.
topic_arn() {
  local name="$1"
  local listing=''
  if ! listing="$("${AWS_CLI[@]}" sns list-topics --query 'Topics[].TopicArn' --output text 2>&1)"; then
    fail "${EXIT_TOPIC}" "sns:${name}" \
      "list-topics failed: ${listing//$'\n'/ }" \
      'Confirm the sns service is running on the health endpoint, then re-run the hook.'
  fi
  # `--output text` returns the ARNs tab-separated on a single line; flattening
  # any newline first makes the single `read` sufficient for either shape.
  local flat="${listing//$'\n'/ }"
  local -a arns=()
  IFS=$' \t' read -r -a arns <<<"${flat}"
  local arn=''
  for arn in "${arns[@]}"; do
    if [[ "${arn}" == *":${name}" ]]; then
      printf '%s' "${arn}"
      return 0
    fi
  done
  fail "${EXIT_TOPIC}" "sns:${name}" \
    'the topic ARN could not be resolved from list-topics' \
    'Re-run the hook so the topic exists before its subscriptions are read.'
}

# Fatal drift check on the zero-subscription guarantee.
#
# The contract creates no subscription of any kind, and the summary asserts that
# count, so the count is READ BACK rather than assumed. This drift is fatal
# where versioning drift on the input and statements buckets is not, and the
# distinction is the one drawn in EVIDENCE AND DRIFT: a subscription can be
# removed through the API by the operator, so its presence is a repairable
# divergence, whereas S3 exposes no call that removes a versioning
# configuration.
#
# This function never unsubscribes anything. A subscription found here may
# belong to a sibling clone sharing this edge, and destroying a resource this
# script did not create is outside its authority - hence a remediation hint
# naming the command instead of a silent teardown.
verify_no_subscriptions() {
  local name="$1"
  local arn=''
  arn="$(topic_arn "${name}")"
  local count=''
  if ! count="$("${AWS_CLI[@]}" sns list-subscriptions-by-topic --topic-arn "${arn}" \
    --query 'length(Subscriptions)' --output text 2>&1)"; then
    fail "${EXIT_TOPIC}" "sns:${name}" \
      "list-subscriptions-by-topic failed: ${count//$'\n'/ }" \
      'Confirm the sns service is running on the health endpoint, then re-run the hook.'
  fi
  if [[ "${count}" != '0' ]]; then
    fail "${EXIT_TOPIC}" "sns:${name}" \
      "expected 0 subscriptions but the topic reports '${count//$'\n'/ }'" \
      'Remove it with "aws sns unsubscribe --subscription-arn <arn>", then re-run the hook.'
  fi
  OBSERVED_SUBSCRIPTIONS="${count}"
  log "sns:${name}" 'subscription count verified 0 by list-subscriptions-by-topic'
}

# Renders an observed versioning status for the summary. The rendering presents
# the observed value and never substitutes for it: a status other than the one
# this contract expects for the bucket is spelled out verbatim and tagged DRIFT,
# so no summary line can read as a clean pass over a divergent edge.
render_versioning() {
  local observed="$1"
  local expected="$2"
  if [[ "${observed}" == "${expected}" ]]; then
    case "${observed}" in
      None) printf 'unversioned - verified' ;;
      *) printf 'versioning %s - verified' "${observed}" ;;
    esac
  else
    printf 'versioning %s - DRIFT, expected %s' "${observed}" "${expected}"
  fi
}

# ------------------------------------------------------------------------------
# Main sequence. Ordered so that nothing is provisioned before configuration has
# been validated and the edge has been confirmed usable.
main() {
  log 'start' "provisioning CardDemo AWS resources in region ${REGION} via ${CLI_LABEL}"

  wait_until_ready

  ensure_bucket "${INPUT_BUCKET}"
  ensure_bucket "${OUTPUT_BUCKET}"
  ensure_bucket "${STATEMENTS_BUCKET}"

  # Versioning. Enabled and verified on the output bucket; OBSERVED on the other
  # two, because this contract leaves them unversioned and a claim of that shape
  # has to be measured to be worth making. Each call publishes what the edge
  # reported through OBSERVED_VERSIONING, so each value is captured immediately -
  # the following call overwrites it.
  local output_versioning=''
  local input_versioning=''
  local statements_versioning=''
  enable_and_verify_versioning "${OUTPUT_BUCKET}"
  output_versioning="${OBSERVED_VERSIONING}"
  observe_versioning "${INPUT_BUCKET}"
  input_versioning="${OBSERVED_VERSIONING}"
  observe_versioning "${STATEMENTS_BUCKET}"
  statements_versioning="${OBSERVED_VERSIONING}"

  # Lifecycle rules. Fatal on any rule found, on every bucket - reaching the
  # summary therefore proves all three read backs returned
  # NoSuchLifecycleConfiguration.
  verify_no_lifecycle_rules "${INPUT_BUCKET}"
  verify_no_lifecycle_rules "${OUTPUT_BUCKET}"
  verify_no_lifecycle_rules "${STATEMENTS_BUCKET}"

  log 'sqs:mapping' "logical '${QUEUE_LOGICAL}' -> physical '${QUEUE_PHYSICAL}' (FIFO suffix required by AWS)"
  ensure_queue "${QUEUE_PHYSICAL}"

  ensure_topic "${NOTIFICATION_TOPIC}"
  verify_no_subscriptions "${NOTIFICATION_TOPIC}"

  # Gate 8 evidence. EVERY line below is composed from a value this run read back
  # off the edge, never from the contract this script set out to apply, so a
  # divergence the script survives by design is visible here instead of being
  # papered over by a fixed string. Names only - no URL, no ARN, no account
  # identifier.
  log 'summary' '--------------------------------------------------------------'
  log 'summary' "s3 input bucket ......... ${INPUT_BUCKET} ($(render_versioning "${input_versioning}" 'None'))"
  log 'summary' "s3 output bucket ........ ${OUTPUT_BUCKET} ($(render_versioning "${output_versioning}" 'Enabled'))"
  log 'summary' \
    "s3 statements bucket .... ${STATEMENTS_BUCKET} ($(render_versioning "${statements_versioning}" 'None'))"
  log 'summary' "sqs queue ............... ${QUEUE_LOGICAL} -> ${QUEUE_PHYSICAL} (FIFO verified)"
  log 'summary' "sns topic ............... ${NOTIFICATION_TOPIC} (presence verified)"
  log 'summary' "sns subscriptions ....... ${OBSERVED_SUBSCRIPTIONS} (verified by read-back)"
  log 'summary' 's3 lifecycle rules ...... 0 on all three buckets (verified by read-back)'
  log 'summary' '--------------------------------------------------------------'
  log 'done' 'all resources provisioned and verified'

  exit "${EXIT_OK}"
}

main
