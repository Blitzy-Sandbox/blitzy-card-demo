# CardDemo (Java 25 LTS) — Operator Runbook

> Refine-PR deliverable Item 4 — operational reference for running
> the CardDemo batch chain in non-development environments
> (STAGING, PROD). This runbook complements `java/README.md`
> (developer-oriented) and `java/MIGRATION_NOTES.md` (translation
> history). It is the single source of truth for production
> operators.

**Authority**

* AAP §0.4.1 — table of 29 JCL jobs → 28 shaded jars (one jar
  covers both `CUSTFILE.jcl` and `DEFCUST.jcl` per the
  `DefineCustomerFileApp` consolidation).
* AAP §0.6.5 — file I/O exactness; SafePathResolver allowed-roots.
* AAP §0.6.11 — JFR performance baseline and 10% regression band.
* AAP §0.7.2 — preserve byte-for-byte fidelity; faithful-COBOL
  ABEND-999 behavior is preserved, not "fixed".
* AAP §0.8.1 — citation discipline applies to every claim about
  the source system.
* Root `README.md` §"Running full batch" — canonical 17-step
  full-batch sequence (CLOSEFIL → ... → OPENFIL).
* `java/README.md` — build/run instructions and quality gates.

---

## Table of Contents

1. [Shaded JAR Inventory (28 jars, 29 JCL jobs)](#1-shaded-jar-inventory)
2. [GDG (Generation Data Group) Version Management](#2-gdg-version-management)
3. [Restart Semantics Per Batch Step](#3-restart-semantics)
4. [The CBTRN03C ABEND-999 Path (Faithful-COBOL Documentation)](#4-cbtrn03c-abend-999-path)
5. [Monitoring Boundaries (Exit Codes, Log Signatures, JFR Triggers)](#5-monitoring-boundaries)
6. [Troubleshooting Table](#6-troubleshooting-table)
7. [Operator Cheatsheet](#7-operator-cheatsheet)

---

## 1. Shaded JAR Inventory

All 28 shaded jars are produced by `java/carddemo-app` via the
`maven-shade-plugin` (one execution per `<finalName>` per AAP §0.3.1
and §0.4.1). The canonical run command is:

```bash
java -XX:+UseCompactObjectHeaders \
     -XX:+UseShenandoahGC \
     -XX:ShenandoahGCMode=generational \
     -jar /opt/carddemo/carddemo-<program>.jar
```

Configuration is supplied via the layered precedence documented in
`java/MIGRATION_NOTES.md` §M18.3 — `CARDDEMO_CONFIG` env var pointing
at `/etc/carddemo/application-<env>.properties`, plus any
`CARDDEMO_<KEY>` overrides exported by the orchestration tier.

The inventory below groups jars by JCL job category to mirror the
canonical full-batch sequence in root `README.md`.

### 1.1 Phase 0 — Pre-load (CICS coordination)

| Shaded JAR                           | JCL Job   | Source COBOL    | Description                              |
|--------------------------------------|-----------|-----------------|------------------------------------------|
| `carddemo-close-file.jar`            | CLOSEFIL  | IEFBR14 no-op   | Closes files opened by CICS. Translates the mainframe no-op step; in the file-based Java port it is a sentinel marker that the load phase may begin. |

Example invocation:

```bash
java -jar /opt/carddemo/carddemo-close-file.jar
```

### 1.2 Phase 1 — Data loading (IDCAMS DEFINE + REPRO equivalents)

| Shaded JAR                                | JCL Job   | Source COBOL              | Description                                                                |
|-------------------------------------------|-----------|---------------------------|----------------------------------------------------------------------------|
| `carddemo-define-gdg.jar`                 | DEFGDGB   | IDCAMS DEFINE GDG         | Creates the GDG (Generation Data Group) base directory layout. See §2.    |
| `carddemo-define-account-file.jar`        | ACCTFILE  | IDCAMS DEFINE + REPRO     | Loads ACCTDATA from `app/data/ASCII/acctdata.txt` (or its EBCDIC counterpart). |
| `carddemo-define-card-file.jar`           | CARDFILE  | IDCAMS DEFINE + REPRO     | Loads CARDDATA.                                                            |
| `carddemo-define-card-xref.jar`           | XREFFILE  | IDCAMS DEFINE + REPRO     | Loads CARDXREF + AIX (the customer ↔ card cross-reference).                |
| `carddemo-define-customer-file.jar`       | CUSTFILE  | IDCAMS DEFINE + REPRO     | Loads CUSTDATA. Also covers `DEFCUST.jcl` (the DELETE/DEFINE re-definition; see AAP §0.4.1 entry that flags the suspected dataset-name mismatch as a faithful-translation deviation). |
| `carddemo-define-transaction-file.jar`    | TRANFILE  | IDCAMS DEFINE + REPRO     | Creates TRANSACT KSDS (Key-Sequenced Data Set). Distinct from TRANBKP (Phase 2 backup step). |
| `carddemo-define-discount-group.jar`      | DISCGRP   | IDCAMS DEFINE + REPRO     | Loads DISCGRP (disclosure group / interest rate lookup).                   |
| `carddemo-define-tcatbal.jar`             | TCATBALF  | IDCAMS DEFINE + REPRO     | Loads TCATBALF (transaction category balance file).                        |
| `carddemo-define-transaction-type.jar`    | TRANTYPE  | IDCAMS DEFINE + REPRO     | Loads TRANTYPE (transaction type taxonomy).                                |
| `carddemo-define-transaction-category.jar`| TRANCATG  | IDCAMS DEFINE + REPRO     | Loads TRANCATG (transaction category taxonomy).                            |
| `carddemo-users-security-seed.jar`        | DUSRSECJ  | IEBGENER copy             | Seeds USRSEC (user security file). Preserves plaintext SEC-USR-PWD per AAP §0.7.2. |

Example invocation:

```bash
# Load the account database from EBCDIC fixture
java -jar /opt/carddemo/carddemo-define-account-file.jar
```

### 1.3 Phase 2 — Daily processing (the critical chain)

| Shaded JAR                          | JCL Job   | Source COBOL    | Description                                                                |
|-------------------------------------|-----------|-----------------|----------------------------------------------------------------------------|
| `carddemo-post-transactions.jar`    | POSTTRAN  | CBTRN02C        | Core posting engine. Reads DAILYTRAN, validates each transaction against ACCTDATA / CARDXREF / TRANTYPE / TRANCATG, writes rejected records to DALYREJS, posts accepted records to TRANSACT, updates TCATBALF and ACCTDATA in I-O mode. See §3.1 for restart semantics. |
| `carddemo-interest-calculation.jar` | INTCALC   | CBACT04C        | Interest-calculation engine. Requires `CARDDEMO_INTCALC_PARM=YYYYMMDDHH` (canonical default `2022071800` for fixture parity). |
| `carddemo-transaction-backup.jar`   | TRANBKP   | IDCAMS REPRO    | Backs up TRANSACT to a versioned generation under `carddemo.gdg.root`. Idempotent: each run creates a new `+0` generation. |
| `carddemo-combine-transactions.jar` | COMBTRAN  | SORT (JCL SORT) | Merges system transactions with daily transactions into a single sorted output. Sort-order preservation is non-negotiable per AAP §0.6.6 (any reorder is a defect). |
| `carddemo-create-statements.jar`    | CREASTMT  | CBSTM03A        | Generates customer statements (HTML + text). Uses `carddemo.work.path` for intermediate artifacts; the directory is wiped at the START and END of every run to avoid PII leakage. |

The canonical Phase 2 sequence is:

```
POSTTRAN → INTCALC → TRANBKP → COMBTRAN → CREASTMT
```

All five steps return one of the exit codes documented in §5.1.
The `FullBatchChainIT` integration test (`carddemo-tests`) wires
the first four steps end-to-end with `@TempDir` and the canonical
`app/data/ASCII/` fixtures per Refine-PR Item 6.

### 1.4 Phase 3 — Indexing

| Shaded JAR                         | JCL Job  | Source COBOL  | Description                                                  |
|------------------------------------|----------|---------------|--------------------------------------------------------------|
| `carddemo-transaction-index.jar`   | TRANIDX  | IDCAMS BLDINDEX | Builds the alternate index over TRANSACT. In the file port this is a no-op marker; the file-based adapter does not maintain a separate AIX file (per AAP §0.6.5 "file-based default"). |

### 1.5 Phase 4 — Reporting

| Shaded JAR                          | JCL Job   | Source COBOL    | Description                                                          |
|-------------------------------------|-----------|-----------------|----------------------------------------------------------------------|
| `carddemo-daily-rejects.jar`        | DALYREJS  | n/a (IDCAMS)    | Exports the daily rejects file produced by POSTTRAN (read-only DD).  |
| `carddemo-print-tcatbal.jar`        | PRTCATBL  | n/a (utility)   | Prints the TCATBALF report.                                          |
| `carddemo-transaction-report.jar`   | TRANREPT  | CBTRN03C        | Paginated transaction-detail report writer. **Triggers ABEND-999 (exit 16) under faithful-COBOL conditions; see §4 for the canonical contract.** Requires `CARDDEMO_TRANREPT_START_DATE` and `CARDDEMO_TRANREPT_END_DATE` (ISO `YYYY-MM-DD`). |
| `carddemo-report-file.jar`          | REPTFILE  | IDCAMS PRINT    | Generic report-file print utility.                                    |
| `carddemo-read-account-dump.jar`    | READACCT  | IDCAMS PRINT    | Pretty-prints ACCTDATA to stdout (debugging aid).                     |
| `carddemo-read-card-dump.jar`       | READCARD  | IDCAMS PRINT    | Pretty-prints CARDDATA to stdout.                                     |
| `carddemo-read-customer-dump.jar`   | READCUST  | IDCAMS PRINT    | Pretty-prints CUSTDATA to stdout.                                     |
| `carddemo-read-card-xref-dump.jar`  | READXREF  | IDCAMS PRINT    | Pretty-prints CARDXREF to stdout.                                     |

### 1.6 Phase 5 — Re-enable (CICS coordination)

| Shaded JAR                  | JCL Job  | Source COBOL  | Description                                                          |
|-----------------------------|----------|---------------|----------------------------------------------------------------------|
| `carddemo-open-file.jar`    | OPENFIL  | IEFBR14 no-op | Re-opens files for CICS. Sentinel marker in the file-based Java port. |

### 1.7 Phase 6 — Admin (out-of-band)

| Shaded JAR                  | JCL Job   | Source COBOL  | Description                                              |
|-----------------------------|-----------|---------------|----------------------------------------------------------|
| `carddemo-admin-code.jar`   | CBADMCDJ  | n/a (admin)   | Standalone admin job (CICS admin code definitions). Not part of the nightly chain. |

### 1.8 Canonical full-batch sequence (per root README.md)

```text
CLOSEFIL   →  ACCTFILE  →  CARDFILE  →  XREFFILE  →  CUSTFILE
   ↓
TRANBKP    →  DISCGRP   →  TCATBALF  →  TRANTYPE  →  DUSRSECJ
   ↓
POSTTRAN   →  INTCALC   →  TRANBKP   →  COMBTRAN  →  CREASTMT
   ↓
TRANIDX    →  OPENFIL
```

(17 steps with TRANBKP appearing twice — once to create, once to
back up, exactly as in the COBOL JCL deck.)

---

## 2. GDG Version Management

The GDG (Generation Data Group) concept is preserved by-name in
the Java port per AAP §0.6.5 ("z/OS GDG → versioned files on a
normal filesystem; same naming conventions preserved"). The
implementation lives in `DefineGdgApp` and is shared by every
batch step that produces a versioned output (TRANBKP, COMBTRAN,
CREASTMT, etc.).

### 2.1 Layout

The GDG root is set per environment by `carddemo.gdg.root`:

* DEV — `./build/dev-data/gdg/`
* STAGING — `/var/carddemo/staging/gdg/`
* PROD — `/var/carddemo/prod/gdg/`

Under the root, each GDG base name has its own subdirectory and
its generations are stored as files with a numeric generation
suffix mirroring the z/OS `+0` / `+1` / `-1` convention:

```
/var/carddemo/prod/gdg/
├── tranbkp/
│   ├── tranbkp.G0001V00          ← oldest retained
│   ├── tranbkp.G0002V00
│   ├── ...
│   ├── tranbkp.G0029V00
│   └── tranbkp.G0030V00          ← newest (the "+0" generation)
└── combtran/
    └── ...
```

### 2.2 Rolling generations

Each batch step that writes a versioned output appends a NEW
generation (`G<next>V00`); it never rewrites an existing one.
This preserves the COBOL invariant that any prior generation can
be re-read by a later step in the same chain (see §3.3 for the
restart consequence).

### 2.3 Retention policy (PROD recommendation)

The PROD retention default is **30 generations** rotated via a
nightly cron job (run AFTER `OPENFIL` completes, outside the
batch chain):

```bash
# /etc/cron.d/carddemo-gdg-prune (mode 0644, owner root)
# Run at 04:00 UTC, after the nightly chain has completed and CICS
# has re-opened the files. Prune anything older than 30 generations
# per GDG base.
0 4 * * * carddemo  /opt/carddemo/bin/gdg-prune.sh --root /var/carddemo/prod/gdg --keep 30
```

`gdg-prune.sh` is a 10-line shell utility (operator-provided; NOT
part of the Java port) that iterates each subdirectory of
`--root`, sorts files lexicographically (the `G<n>V<m>` naming
guarantees lexicographic order matches generation order), and
deletes everything older than `--keep`.

### 2.4 Archival policy (PROD recommendation)

Pruned generations SHOULD be archived to S3-IA or Glacier before
deletion. The archival step is again operator-provided (not part
of the Java port). Recommended naming on S3:

```
s3://<bucket>/carddemo/gdg/<env>/<base>/G<n>V<m>.<env>.<yyyy>-<mm>-<dd>
```

### 2.5 Cleanup verification

Before promoting a new release to PROD, operators MUST verify the
GDG layout is consistent. The verification command is:

```bash
# Lists each GDG base and its generation count
find /var/carddemo/prod/gdg -mindepth 1 -maxdepth 1 -type d -print0 \
  | while IFS= read -r -d '' base; do
      count=$(find "$base" -maxdepth 1 -type f | wc -l)
      printf "%s: %d generation(s)\n" "$(basename "$base")" "$count"
    done
```

If any GDG base has zero generations, the batch chain WILL fail at
the first step that reads from it (with exit code 12, see §5.1).

---

## 3. Restart Semantics

The Refine-PR explicitly requires this section to document
restart behavior **without changing it**. The text below describes
the existing faithful-COBOL semantics; do NOT modify program
behavior to "fix" any non-idempotent step.

### 3.1 POSTTRAN (`CbTrn02C`)

* **Idempotency**: NOT idempotent at the record level. POSTTRAN
  updates ACCTDATA and TCATBALF in-place (per the COBOL `I-O`
  mode) and appends to TRANSACT and DALYREJS.
* **Partial-run recovery**: if POSTTRAN aborts mid-run, the
  recommended recovery is:
  1. Roll back ACCTDATA and TCATBALF from the previous TRANBKP
     generation (the Phase 1 TRANBKP, NOT the Phase 2 backup).
  2. Truncate the partial TRANSACT writes by reverting TRANSACT to
     the prior TRANBKP generation.
  3. Re-run POSTTRAN from the start with the same DAILYTRAN input.
* **Why this matches COBOL**: in the COBOL deck, the operator
  manually re-IPLs from the TRANBKP backup on a partial-run
  failure. The Java port preserves this manual-recovery contract.

### 3.2 INTCALC (`CbAct04C`)

* **Idempotency**: idempotent IF the `carddemo.intcalc.parm`
  (date-anchor) is held constant. Each run produces the same
  interest-calculation outputs given the same input ACCTDATA +
  TCATBALF + DISCGRP.
* **Partial-run recovery**: restart by re-running with the same
  PARM. No prior-step revert is required.

### 3.3 TRANBKP (`TransactionBackupApp`)

* **Idempotency**: idempotent at the GDG-version level. Each run
  appends a NEW generation; prior generations are preserved.
* **Partial-run recovery**: if the partial write produced an
  incomplete generation file, manually delete the incomplete file
  and re-run. The next run will write the same generation number
  (since lexicographic ordering of `G<n>V<m>` reflects existing
  generations).

### 3.4 COMBTRAN (`CombineTransactionsApp`)

* **Idempotency**: idempotent IF the input set (TRANSACT +
  DAILYTRAN) is held constant. Sort-order preservation is
  guaranteed per AAP §0.6.6.
* **Partial-run recovery**: delete the partial output and re-run.

### 3.5 CREASTMT (`CbStm03A` + `CbStm03B`)

* **Idempotency**: idempotent at the customer level. Re-running
  CREASTMT regenerates the SAME statement files for the SAME
  input ACCTDATA + TRANSACT + CARDXREF + CUSTDATA.
* **Partial-run recovery**: the `carddemo.work.path` directory is
  wiped at the START of every run, so a partial run leaves no
  residue. Simply re-run.

### 3.6 TRANIDX, OPENFIL, CLOSEFIL

* All three are no-op markers in the file-based port. Idempotent
  trivially.

### 3.7 General restart guidance

* All steps SHOULD be restarted by re-running the failed step
  with its original inputs.
* Operators MUST NOT skip a failed step (e.g., "POSTTRAN failed,
  let's go ahead to INTCALC anyway"). The downstream programs
  rely on the upstream step's complete output.
* When in doubt, restart the ENTIRE chain from CLOSEFIL after
  rolling back to the most recent TRANBKP generation.

---

## 4. CBTRN03C ABEND-999 Path

The transaction-report program `CBTRN03C` (Java class
`com.blitzy.carddemo.application.transaction.CbTrn03C`) is the
**only** program in the CardDemo deck that emits the canonical
COBOL ABEND-999 code. This section documents the contract **as
inherited from the COBOL source**. Operators MUST NOT treat
ABEND-999 as a transient fault; it is a deliberate, designed
failure mode.

### 4.1 What ABEND-999 means

`CBTRN03C` declares the constants:

```cobol
01  CEE3ABD-ABCODE          PIC S9(9) COMP VALUE +999.
01  APPL-RESULT             PIC S9(9) COMP VALUE +999.
```

These are the COBOL ABEND code and the COBOL application-result
return code. They are emitted via the `Z-ABEND-PROGRAM` paragraph
which calls `CEE3ABD` (Language Environment forced abend).

In the Java port, the equivalent code path raises
`com.blitzy.carddemo.application.AbendException` (annotated
`@CobolProgram(value = "CEE3ABD")`). The
`TransactionReportApp.main(String[])` entry point catches
`AbendException` and returns `RC_SEVERE` (16) via its
pattern-matching `switch` on the integer return code; the JVM
`System.exit(16)` is then issued.

### 4.2 Failure modes that trigger ABEND-999

The COBOL source raises ABEND-999 from `CBTRN03C` in the following
deterministic situations (each mirrored exactly in the Java
port):

1. **File-open failure** — any of TRANSACT, DATEPARM, CARDXREF,
   TRANTYPE, TRANCATG, or REPORT cannot be opened (e.g., the
   file does not exist at the resolved path, the path is outside
   the SafePathResolver allowed-roots, or the OS denies access).
2. **Invalid card number** — the TRANSACT record's
   `TRAN-CARD-NUM` does not match any record in CARDXREF.
3. **Missing TRANTYPE** — the TRANSACT record's `TRAN-TYPE-CD`
   does not match any record in the loaded TRANTYPE lookup.
4. **Missing TRANCATG** — the TRANSACT record's `TRAN-CAT-CD`
   does not match any record in the loaded TRANCATG lookup for
   the given type code.
5. **READ error** — any I/O error reading TRANSACT, DATEPARM, or
   CARDXREF after a successful open (e.g., truncated file, EBCDIC
   transcoding failure on a non-`IBM-1047` byte).
6. **WRITE error** — any I/O error writing REPORT (e.g., disk
   full, permission revoked mid-run).

### 4.3 Pre-run cardxref completeness check (recommended)

Failure mode #2 (invalid card number) is by far the most common
real-world trigger. The recommended pre-run check is:

```bash
# Pre-run sanity: every distinct card number in TRANSACT must be
# present in CARDXREF. Run BEFORE TRANREPT.
java -jar /opt/carddemo/carddemo-read-card-xref-dump.jar \
  > /tmp/cardxref.txt
java -jar /opt/carddemo/carddemo-read-account-dump.jar \
  > /tmp/acctdata.txt

# (Operator-provided 5-line awk to extract card-num columns
# and confirm transact-side set is a subset of cardxref-side set.)
```

If the pre-run check fails, the operator's choice is one of:

* **Faithful-COBOL mode (DEFAULT)** — run TRANREPT, accept that
  it WILL ABEND-999 on the first orphan card number, and exit
  with code 16. This is the contract `CBTRN03C` enforces by
  design.
* **Permissive-operator mode (DOCUMENTED, NOT IMPLEMENTED)** — the
  COBOL source does not provide a permissive mode. Switching to
  permissive behavior would CHANGE program semantics and is
  therefore OUT OF SCOPE per the Refine-PR's hard constraint
  "do NOT change behavior — document only". If a future PR
  introduces a permissive mode, it MUST be a new code path
  guarded by an explicit configuration switch (e.g.,
  `carddemo.tranrept.permissive=true`) and MUST NOT alter the
  default faithful-COBOL behavior.

### 4.4 What operators should DO when ABEND-999 fires

1. Confirm `System.exit(16)` was issued (see §5.1).
2. Inspect the journald / log output for the stack trace from
   `AbendException`. The exception message identifies the
   triggering paragraph (e.g., `1500-A-LOOKUP-XREF`).
3. If the trigger was failure mode #2 (invalid card number),
   inspect CARDXREF for completeness. The root cause is almost
   always an upstream load failure (XREFFILE step did not load
   all expected records).
4. Repair the upstream data (re-run XREFFILE with a fixed
   fixture) and re-run TRANREPT.
5. Do NOT "skip" the abended TRANREPT step. Downstream
   reporting consumers (BI dashboards, audit ledgers) depend on
   TRANREPT's output completeness.

### 4.5 Why we do NOT auto-retry ABEND-999

ABEND-999 is deterministic. Re-running TRANREPT against the SAME
inputs will ABEND-999 in the SAME place. Auto-retry would mask
the root cause (upstream data incompleteness) and is therefore
FORBIDDEN. The exit code 16 is the operator-visible signal that
human investigation is required.

---

## 5. Monitoring Boundaries

### 5.1 Exit codes

All `carddemo-app` main classes share the same return-code
taxonomy, derived from the COBOL `APPL-RESULT` convention and
mapped through a pattern-matching `switch` in each `main`:

| Exit Code | Constant     | Meaning                                                                  |
|-----------|--------------|--------------------------------------------------------------------------|
| 0         | `RC_OK`      | Successful completion. No abnormal conditions detected.                  |
| 4         | `RC_WARN`    | Successful completion with at least one warning (e.g., a record skipped). |
| 8         | `RC_ERROR`   | One or more recoverable errors. Output is partial; manual review needed. |
| 12        | `RC_NO_INPUT`| Required input file missing, empty, or unreadable. Output not produced.  |
| 16        | `RC_SEVERE`  | Unrecoverable error. ABEND-999 or equivalent. Output corrupted or absent. |

Any exit code OUTSIDE `{0, 4, 8, 12, 16}` indicates a JVM-level
failure (e.g., `OutOfMemoryError`, `StackOverflowError`) and MUST
be investigated as a P1 (the JVM either crashed or the program
threw an uncaught `Throwable` that the main-method `catch` did not
clamp). The clamping behavior is part of the contract; report any
out-of-range exit code as a bug.

### 5.2 Log signatures to grep

The Java port uses SLF4J (Logback backend) with structured JSON
output by default in PROD. The following log signatures are the
primary monitoring boundaries:

| Signature                                  | Severity | Action                                                  |
|--------------------------------------------|----------|---------------------------------------------------------|
| `AbendException`                           | ERROR    | Page on-call; correlate with §4 trigger list.           |
| `FileStatusException`                      | ERROR    | Inspect FILE STATUS code; correlate with COBOL §I.O. table. |
| `Failed to open file`                      | ERROR    | Check SafePathResolver allowed-roots and POSIX perms.   |
| `Path escapes allowed root`                | ERROR    | Operator passed a `..`-containing path. Reject the run. |
| `Charset mismatch`                         | ERROR    | The configured `carddemo.file.<x>.charset` does not match the actual file encoding. |
| `Sort order violation`                     | FATAL    | A reordering defect was detected by the harness. Page IMMEDIATELY (AAP §0.6.6 invariant breach). |
| `Decimals: scale mismatch`                 | ERROR    | A `BigDecimal` arithmetic site produced a value with wrong scale. Decimals coverage gate should have caught this; treat as P1. |
| `JFR baseline running in COLLECTION mode`  | INFO     | Expected when the JFR baseline file still contains `PLACEHOLDER`. See `java/SRE.md`. |
| `JFR regression DETECTED`                  | WARN     | A captured Decimals workload exceeded the 10% band. Investigate the host (CPU pinning, thermal throttling). |

A baseline grep recipe for nightly runs:

```bash
journalctl -u 'carddemo-*' --since "yesterday 23:00" --until "today 06:00" \
  | grep -E 'AbendException|FileStatusException|Failed to open|Path escapes|Sort order violation|scale mismatch'
```

Any non-empty output from the above command MUST page on-call.

### 5.3 JFR triggers

The Java Flight Recorder integration is documented in detail in
`java/SRE.md` §3. The summary for operators:

* JFR is enabled by passing `-XX:StartFlightRecording=...` to the
  JVM at launch.
* The PROD recommended trigger captures a 5-minute rolling
  recording into `/var/carddemo/prod/jfr/` whenever:
  * A program runs longer than the documented p95 duration (see
    `java/SRE.md` §2 SLO table — values are `TBD pending
    baseline` per the Refine-PR scope).
  * Heap usage exceeds 80% after a young-gen GC.
  * Any of the §5.2 ERROR signatures fires.
* Captured `.jfr` files are tagged with the
  `CARDDEMO_BATCH_RUN_ID` and uploaded to S3 for offline analysis.

The reference JFR regression test
(`carddemo-tests/src/test/java/com/blitzy/carddemo/tests/perf/JfrBaselineTest.java`)
defines the canonical Decimals workload and the 10% regression
band (AAP §0.6.11).

---

## 6. Troubleshooting Table

This table expands the root `README.md` troubleshooting guidance
with Java-port-specific failure modes. The structure mirrors the
COBOL operator runbook.

| Symptom                                                     | Likely Cause                                                                                              | First Diagnosis Step                                                                                                                       | Resolution                                                                                                                          |
|-------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Exit code 12 from ANY step                                  | Required input missing or empty.                                                                          | `ls -la $(carddemo.data.root)/<file>.dat`                                                                                                  | Re-run the upstream load step (Phase 1) that produces this file. If load step succeeded but file is missing, check disk and quotas. |
| Exit code 16 from TRANREPT                                  | ABEND-999 in `CBTRN03C`. See §4.                                                                          | `journalctl -u carddemo-transaction-report --since '5 min ago' | grep AbendException`                                                      | Identify failure mode (1–6 in §4.2). Repair upstream data; re-run TRANREPT.                                                          |
| Exit code 16 from any other step                            | Unrecoverable I/O error (disk full, permission revoked).                                                  | `df -h $(carddemo.output.root)` and `ls -l $(carddemo.output.root)`                                                                        | Free disk; restore POSIX permissions to 0750 carddemo:carddemo; re-run.                                                              |
| "Path escapes allowed root" in logs                         | Operator-supplied path contained `..` segment.                                                            | `printenv | grep CARDDEMO_FILE_`                                                                                                           | Strip `..` from the env var or the config file. If a legitimate use case requires path widening, raise a PR to update §2 allowed-roots. |
| "Charset mismatch" in logs                                  | File is ASCII but config says IBM-1047 (or vice versa).                                                   | `head -c 16 <file>.dat | xxd`                                                                                                              | Set the per-file override `carddemo.file.<x>.charset=US-ASCII` (DEV/test fixtures) or coordinate with mainframe ops (PROD).         |
| Posting reports a different total than COBOL baseline       | `BigDecimal` scale or rounding-mode mismatch.                                                             | Run the Decimals property tests: `mvn -B -ntp test -Dtest=DecimalsProperties`                                                              | If Decimals tests pass, the bug is in a use-case site that hand-rolled arithmetic. File a P1 — Decimals coverage gate is 100%.       |
| Decimals coverage gate fails in CI                          | Someone modified `Decimals.java` without adding tests.                                                    | `mvn -B -ntp -pl carddemo-domain jacoco:report` and open `target/site/jacoco/index.html`                                                   | Add property tests covering every new branch. Decimals MUST stay at 100% per AAP §0.6.1.                                            |
| `mvn verify` fails with "BannedDependencies"                | A transitive dependency pulled in `org.springframework`, `org.postgresql`, `org.hibernate`, or `com.zaxxer`. | `mvn dependency:tree | grep -E 'springframework|postgresql|hibernate|zaxxer'`                                                              | Exclude the transitive offender or replace the direct dependency. NEVER `--ignoreDependencies` past the enforcer.                   |
| `mvn verify` fails with "condition satisfied" from antrun   | A pom file or workflow YAML referenced the preview-enabling flag.                                         | The antrun output lists the offending file.                                                                                                | Remove the flag reference. If the flag belongs to a documentation file, exclude that file in parent pom `preview-workflow` fileset. |
| Statement HTML output is truncated mid-customer             | CBSTM03A intermediate failure; `carddemo.work.path` ran out of space.                                     | `df -h $(carddemo.work.path)`                                                                                                              | Free disk in the work directory. CREASTMT will regenerate cleanly on re-run.                                                        |
| INTCALC produces zero interest for all accounts             | `CARDDEMO_INTCALC_PARM` is unset or set to a date OUTSIDE the test fixture range.                          | `printenv CARDDEMO_INTCALC_PARM`                                                                                                           | Set `CARDDEMO_INTCALC_PARM=YYYYMMDDHH` matching a date with TCATBALF balances. DEV/STAGING default: `2022071800`.                   |
| GDG layout has zero generations                             | Phase 1 TRANBKP was skipped or failed silently.                                                            | `find $(carddemo.gdg.root) -type f | head`                                                                                                 | Re-run Phase 1 TRANBKP. Confirm exit 0 before proceeding.                                                                            |
| @Disabled golden tests in CI report                         | Expected — those 29 tests require z/OS COBOL captures.                                                    | Confirm with `mvn -B -ntp test | grep 'skipped: 29'`                                                                                       | Do NOT enable them locally; do NOT fabricate `expected/` byte sequences. Wait for the z/OS capture follow-on.                       |
| JFR baseline test reports COLLECTION mode                   | Expected — the baseline file still contains `PLACEHOLDER` per Refine-PR scope.                            | `grep median.nanos carddemo-tests/src/test/resources/perf/baseline.properties`                                                             | Run on a stable host, capture the median, replace PLACEHOLDER per `java/SRE.md` §3.                                                  |
| `java.lang.NoSuchMethodError` at startup                    | Wrong JDK version (JDK ≤ 24 cannot run Java 25 bytecode).                                                  | `java -version`                                                                                                                            | Install JDK 25 LTS; ensure `JAVA_HOME` points at it.                                                                                |
| OOM (`-Xmx` exceeded)                                       | Statement generation on a very large customer file.                                                       | Heap dump from the OOM crash; check `carddemo-create-statements` run.                                                                      | Increase `-Xmx`; consider enabling `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (default JVM flags per AAP §0.6.4).     |

---

## 7. Operator Cheatsheet

### 7.1 Smoke test (DEV)

```bash
cd java
mvn -B -ntp clean package -DskipTests
java -jar carddemo-app/target/carddemo-close-file.jar
echo "Exit code: $?"   # expect 0
java -jar carddemo-app/target/carddemo-open-file.jar
echo "Exit code: $?"   # expect 0
```

### 7.2 Full chain (STAGING)

```bash
# Run from the carddemo deploy host as user `carddemo`
export CARDDEMO_CONFIG=/etc/carddemo/application-staging.properties
for step in carddemo-close-file \
            carddemo-define-account-file carddemo-define-card-file \
            carddemo-define-card-xref carddemo-define-customer-file \
            carddemo-transaction-backup \
            carddemo-define-discount-group carddemo-define-tcatbal \
            carddemo-define-transaction-type carddemo-users-security-seed \
            carddemo-post-transactions carddemo-interest-calculation \
            carddemo-transaction-backup carddemo-combine-transactions \
            carddemo-create-statements \
            carddemo-transaction-index carddemo-open-file; do
    /usr/bin/java -XX:+UseCompactObjectHeaders \
                  -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
                  -jar /opt/carddemo/${step}.jar
    rc=$?
    if [ $rc -ne 0 ]; then
        echo "STEP ${step} FAILED with exit ${rc}"
        exit $rc
    fi
done
echo "Full chain SUCCESS"
```

### 7.3 Single-step diagnosis (PROD)

```bash
# Re-run a single failed step interactively. Pause after each line.
export CARDDEMO_CONFIG=/etc/carddemo/application-prod.properties
export CARDDEMO_BATCH_RUN_ID=manual-$(date +%Y%m%d%H%M%S)
export CARDDEMO_INTCALC_PARM=2022071800

/usr/bin/java -XX:+UseCompactObjectHeaders \
              -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
              -jar /opt/carddemo/carddemo-post-transactions.jar 2>&1 \
  | tee /tmp/posttran-$(date +%s).log
echo "Exit: ${PIPESTATUS[0]}"
```

### 7.4 Configuration sanity check

```bash
# Print the resolved configuration without running anything (operator
# can confirm CARDDEMO_CONFIG, env-var overrides, and SafePathResolver
# allowed-roots before kicking the chain off).
diff /etc/carddemo/application-prod.properties \
     /opt/carddemo/share/application-prod.properties.committed
# Expect: zero diff. Any drift is a config-management bug.
```

### 7.5 Emergency abort

```bash
# Java apps are launched as systemd oneshot services. Aborting the
# running step:
sudo systemctl stop carddemo-post-transactions
# Inspect the abort:
journalctl -u carddemo-post-transactions --since "10 min ago" | tail -50
```

---

_End of RUNBOOK.md. Append additional sections only as new
operational scenarios emerge; treat every new section as a tracked
follow-on PR with its own AAP entry._
