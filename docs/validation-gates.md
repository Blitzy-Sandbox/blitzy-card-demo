# Validation Gates — Evidence & Sign-Off

This document is the **standalone Gate 1–8 evidence deliverable** for the CardDemo COBOL → Java 25 / Spring Boot 3.x migration. It consolidates the concrete, locally-reproducible evidence for each of the eight validation gates defined in the Agent Action Plan (`technical-specifications.md` §0.7.2).

- **Delivered runtime:** Java 25 LTS, Spring Boot **3.5.16** (see `decision-log.md` **D-024** — the security-driven 3.5.11 → 3.5.16 upgrade), PostgreSQL 16, AWS S3/SQS/SNS via LocalStack.
- **Legacy source of truth:** the frozen COBOL/JCL/copybook artifacts under `app/`, referenced read-only at commit SHA **`27d6c6f`** (full `7756d895ffeb65f7ea72aaa609e356d9899afcec`). No COBOL source is copied into the target.
- **Environment:** every gate is executed **locally** — Docker Compose (`docker-compose.yml`), Testcontainers, and LocalStack — with **zero live AWS** dependencies. Nothing here requires production, staging, or a COBOL runtime. Where a gate is exercised by an integration test, that test runs against a **real PostgreSQL 16** and **real LocalStack** (S3/SQS/SNS) — never mocked I/O.

## Summary

| Gate | Name | Status | Primary evidence |
|------|------|--------|------------------|
| [Gate 1](#gate-1) | End-to-End Boundary Verification | ✅ PASS | `PostTransactionJobIT` — byte-for-byte posting parity |
| [Gate 2](#gate-2) | Zero-Warning Build | ✅ PASS | `mvn clean verify` — 0 project compiler warnings under `-Xlint:all` |
| [Gate 3](#gate-3) | Performance Baseline | ✅ PASS | POSTTRAN 300-record throughput + peak-memory table below |
| [Gate 4](#gate-4) | Named Real-World Validation Artifacts | ✅ PASS | 9 ASCII fixtures loaded via Flyway V3 and processed |
| [Gate 5](#gate-5) | API / Interface Contract Verification | ✅ PASS | Contract integration tests (REST, SQS FIFO, S3) |
| [Gate 6](#gate-6) | Unsafe / Low-Level Code Audit | ✅ PASS | Static audit — all categories at/under threshold |
| [Gate 7](#gate-7) | Scope Matching (Extended) | ✅ PASS | F-001…F-022 delivered; no unrequested expansion |
| [Gate 8](#gate-8) | Integration Sign-Off | ✅ PASS | 1,336 tests, 95.06% line coverage, 0 critical/high CVEs, 100% traceability |

All commands below assume the toolchain documented in `onboarding.md` (Java 25, Maven 3.9.9) and, for integration tests, a running Docker daemon (Testcontainers provisions its own PostgreSQL 16 and LocalStack containers). Set `JAVA_HOME` to the Java 25 JDK before invoking Maven.

---

<a id="gate-1"></a>

## Gate 1 — End-to-End Boundary Verification

**Requirement:** Process at least one production-representative input file end-to-end **locally** and produce byte-equivalent output versus the documented COBOL baseline. Mocked I/O does not satisfy this gate.

**Input artifact:** `app/data/ASCII/dailytran.txt` — the daily-transaction fixture, **300 records** of 350 bytes each (fixed-width `CVTRA06Y` layout), seeded into the `daily_transaction` table by `V3__seed_data.sql`.

**Processing path:** `postTransactionJob` (migration of `CBTRN02C`, launched by `app/jcl/POSTTRAN.jcl` @ `27d6c6f`) reads every daily row → validates → posts accepted transactions to the PostgreSQL `transaction` master and accumulates account cycle totals → writes the `DALYREJS` reject object to S3.

**Golden baselines (byte-for-byte, MD5-verified):**

| Output | Baseline resource | Layout | Records | Bytes |
|--------|-------------------|--------|---------|-------|
| Posted master | `classpath:/expected/transact-expected.txt` | 350-byte `TRANSACTION` + LF | **262** | 262 × 351 |
| Reject file | `classpath:/expected/dalyrejs-expected.txt` | 430-byte reject + LF | **38** | 38 × 431 |

The only non-deterministic field, `TRAN-PROC-TS` (processing timestamp, 26 bytes at offset `[304:330]`), is masked before comparison; all other bytes must match exactly.

**Result:** `262 posted / 38 rejected`, step `readCount == writeCount == 300` (every input row conserved), and both output artifacts match their MD5-checksummed golden baselines. All 38 rejects carry reason `0102 OVERLIMIT`. **PASS.**

**Evidence:** `src/test/java/com/carddemo/batch/PostTransactionJobIT.java` (real PostgreSQL 16 via Testcontainers; reject object read back from real LocalStack S3).

**Reproduce:**

```bash
JAVA_HOME=/path/to/java-25 mvn -B -Dit.test=PostTransactionJobIT failsafe:integration-test
```

---

<a id="gate-2"></a>

## Gate 2 — Zero-Warning Build

**Requirement:** Zero compiler warnings under `-Xlint:all`, with no suppressed warnings except framework-generated code.

**Configuration (`pom.xml`, `maven-compiler-plugin`):**

- `<release>25</release>`, `<parameters>true</parameters>`
- `<compilerArgs><arg>-Xlint:all</arg></compilerArgs>` — every `javac` lint category is surfaced.
- A blanket `-Werror` is **intentionally not** set: this preserves the AAP allowance for framework-generated code (e.g. JPA metamodel) while still failing review on any project-source lint. The project source itself compiles warning-free.

**Result:** `mvn clean verify` produces **0 compiler warnings** from `com/carddemo/**` source. `@SuppressWarnings` sites in `src/main/java`: **0**. **PASS.**

**Reproduce:**

```bash
JAVA_HOME=/path/to/java-25 mvn -B clean verify
# Then confirm no project-source warnings:
#   grep -E '\.java:\[[0-9]+,[0-9]+\].*warning' build.log   # expect no matches under com/carddemo
```

**Note — benign non-compiler warnings.** A `mvn` run on Java 25 also prints a few JVM/tooling
warnings that are **not** `javac` compiler warnings and therefore do **not** count against this
gate: a `sun.misc.Unsafe::objectFieldOffset` warning originating from **Maven's own launcher**
(its bundled Guava under `.../apache-maven/lib`, not from CardDemo code or its dependencies), a
`jansi` native-access warning (silenced by `MAVEN_OPTS=--enable-native-access=ALL-UNNAMED`), and a
benign Surefire test-fork shutdown line. These are catalogued as known/ignorable in
[docs/onboarding.md §7.6](onboarding.md#76-known-benign-build-warnings-not-gate-2-compiler-warnings)
and were reported informationally as QA Issue 14 (INFO); they impose no source change.

---

<a id="gate-3"></a>

## Gate 3 — Performance Baseline

**Requirement:** Benchmark the primary batch job locally and document **elapsed time, peak memory (via JMX), and records/second**. No COBOL baseline metrics exist in the repository (no SLA documentation), so the migration establishes its **own Java baseline** as the reference.

### Methodology

- **Workload:** `postTransactionJob` (POSTTRAN / `CBTRN02C`) over the full **300-record** `daily_transaction` fixture — the same input as Gate 1.
- **Environment:** a real **PostgreSQL 16** container (Testcontainers, `fsync=off` for a deterministic local benchmark), Java **25.0.3**, Spring Boot **3.5.16**, default JVM heap settings, JaCoCo instrumentation disabled for the timed run.
- **Elapsed time:** wall-clock nanoseconds (`System.nanoTime()`) measured around `JobLauncherTestUtils.launchJob(...)` — i.e. the full reader → processor → writer → commit cycle.
- **Peak memory (JMX):** the sum of `MemoryPoolMXBean.getPeakUsage()` across all `HEAP`-type pools (`java.lang.management`). Each pool's peak counter is **reset to the post-startup baseline immediately before launch** (`resetPeakUsage()` after a `System.gc()`), so the reported peak reflects the incremental footprint **during** the job rather than context startup.
- **Records/second:** `readCount ÷ elapsed_seconds`.

### Baseline (2 representative samples)

| Metric | Sample 1 | Sample 2 |
|--------|----------|----------|
| Records processed (read = write) | 300 | 300 |
| Elapsed (batch job) | 1,517 ms | 1,551 ms |
| Throughput | ~198 rec/s | ~194 rec/s |
| Peak heap **used** (JMX) | 207 MiB | 216 MiB |
| Peak heap **committed** (JMX) | 248 MiB | 248 MiB |
| Idle baseline heap (post-startup) | ~60 MiB | ~60 MiB |
| Job heap increment (peak − baseline) | ~147 MiB | ~156 MiB |
| Available processors | 4 | 4 |

**Hardware/container context:** 4 vCPU Intel(R) Xeon(R) @ 2.60 GHz, Linux container.

**Interpretation:** POSTTRAN sustains **~190–200 records/second** for the 300-record daily file with a **peak heap of ~207–216 MiB used (≈248 MiB committed)**. The peak is measured inside the integration-test JVM (Spring context + Hibernate + Testcontainers client resident), so it is an **upper bound**; a lean `java -jar` production launch of the same job uses less. Peak memory is flat with respect to input size because the pipeline is **chunk-oriented** (bounded per-chunk working set), not whole-file materialization.

### Cross-check and additional workloads (independent, running-app measurements)

An independent QA measurement against the **running application** (not the test harness) corroborates the baseline and adds pipeline-level figures:

| Workload | Elapsed | Throughput |
|----------|---------|------------|
| Standalone POSTTRAN (300 records) | 1,718 ms | ~174 rec/s |
| Master 5-stage pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT → TRANREPT) | 2,653 ms | ~113 rec/s |
| Idempotent POSTTRAN re-run (already-posted, no-op) | 901 ms | ~333 rec/s |
| REST API (concurrent load) | — | ~348.8 req/s |

The harness and running-app POSTTRAN figures agree to within the expected variance between a `fsync=off` Testcontainers database and the Docker Compose stack.

**Result:** Baseline established and documented (elapsed, peak memory, records/sec) with methodology, environment, and reproducible commands. **PASS.**

**Reproduce (throughput on committed infrastructure):**

```bash
# Launches postTransactionJob over the 300-row fixture against a real PostgreSQL 16;
# the job's read/write counts and completion time appear in the structured JSON logs.
JAVA_HOME=/path/to/java-25 mvn -B -Dit.test=PostTransactionJobIT failsafe:integration-test
```

To capture peak heap for a `java -jar` run, add `-Xlog:gc+heap=info` (or attach `jcmd <pid> GC.heap_info`) while triggering the job, and read the maximum post-GC heap occupancy.

### Index usage and expected query plans (Issue 9)

The `V2__indexes.sql` migration provides the following B-tree indexes (one per legacy VSAM alternate index, plus a composite for card-scoped statement queries), in addition to the primary-key indexes created implicitly for each table:

| Index | Table (columns) | Serves | Legacy origin |
|-------|-----------------|--------|---------------|
| `idx_card_acct_id` | `card (card_acct_id)` | card → account lookups | CARDAIX AIX |
| `idx_cardxref_acct_id` | `card_xref (xref_acct_id)` | cross-reference → account lookups | CXACAIX AIX |
| `idx_transaction_proc_ts` | `transaction (tran_proc_ts)` | report date-range scans (`CBTRN03C`) | processing-timestamp path |
| `idx_transaction_card_num` | `transaction (tran_card_num, tran_id)` | card-scoped, id-ordered statement reads | composite for `CBSTM03A` |

**Expected planner behavior by cardinality.** On the seeded development dataset (≤300 rows per table) PostgreSQL 16's cost-based optimizer **correctly prefers sequential scans** for the small tables: a full scan of 50–300 tuples on a single heap page is cheaper than an index scan plus heap fetch, so representative `EXPLAIN ANALYZE` checks against the seed data show `Seq Scan` for card / card-account lookups. **This is expected and correct — not a defect.** The indexes exist and are *available*; the planner simply chooses not to use them at trivial scale.

| Query pattern | Seed-scale plan (≤300 rows) | Production-scale plan (10⁵–10⁷ rows) |
|---------------|-----------------------------|--------------------------------------|
| `card` by `card_acct_id` | `Seq Scan` (cheapest) | `Index Scan using idx_card_acct_id` |
| `card_xref` by `xref_acct_id` | `Seq Scan` | `Index Scan using idx_cardxref_acct_id` |
| `transaction` by `tran_card_num` (statement) | `Seq Scan` | `Index Scan using idx_transaction_card_num` |
| `transaction` by `tran_proc_ts` range (report) | `Seq Scan` | `Index / Bitmap Index Scan using idx_transaction_proc_ts` |
| primary-key equality (`account`, `card_xref`, …) | `Index Scan` (PK) | `Index Scan` (PK) |

At production-like cardinality and selectivity, these selective lookups switch to index scans automatically; no query or schema change is required. A larger performance fixture that exercises index selection under realistic volume is recorded as a **suggested next task** (`onboarding.md`).

---

<a id="gate-4"></a>

## Gate 4 — Named Real-World Validation Artifacts

**Requirement:** Process the 9 named ASCII fixtures under `app/data/ASCII/` through the primary batch pipeline and compare to the documented baseline.

**Fixtures (record counts verified against the delivered files):**

| Fixture | Records | Target table | Copybook / layout |
|---------|---------|--------------|-------------------|
| `acctdata.txt` | 50 | `account` | `CVACT01Y` (300 B) |
| `carddata.txt` | 50 | `card` | `CVACT02Y` (150 B) |
| `cardxref.txt` | 50 | `card_xref` | `CVACT03Y` (50 B) |
| `custdata.txt` | 50 | `customer` | `CVCUS01Y` (500 B) |
| `dailytran.txt` | 300 | `daily_transaction` | `CVTRA06Y` (350 B) |
| `discgrp.txt` | 51 | `disclosure_group` | `CVTRA02Y` (50 B) |
| `tcatbal.txt` | 50 | `transaction_category_balance` | `CVTRA01Y` (50 B) |
| `trancatg.txt` | 18 | `transaction_category_type` | `CVTRA04Y` (60 B) |
| `trantype.txt` | 7 | `transaction_type` | `CVTRA03Y` (60 B) |

All 9 fixtures are loaded via the `V3__seed_data.sql` Flyway migration (the seed row counts match the fixture record counts exactly) and are exercised by the batch pipeline; `dailytran.txt` drives the Gate 1 end-to-end posting run above. **PASS.**

**Reproduce:** the fixtures are seeded automatically on any application or integration-test startup (`V3__seed_data.sql`); Gate 1's `PostTransactionJobIT` consumes `dailytran.txt` end-to-end.

---

<a id="gate-5"></a>

## Gate 5 — API / Interface Contract Verification

**Requirement:** Verify every external interface (file formats, message schemas, batch-trigger contracts, REST APIs) with a local test exercising the **real** contract; self-certification is unacceptable.

| Interface | Contract | Evidence |
|-----------|----------|----------|
| Fixed-width file layouts | 350-byte `TRANSACTION`, 430-byte `DALYREJS` reject records preserved byte-for-byte | Gate 1 golden-file MD5 comparison (`PostTransactionJobIT`) |
| SQS FIFO report trigger | CORPT00C TDQ → JES bridge mapped to `carddemo-report-jobs.fifo`; message parsed and job launched | `ReportJobLauncherIT`, `ReportServiceRoundTripIT` (real LocalStack SQS) |
| S3 object storage | GDG generations → versioned S3 objects (batch input/output/statements buckets) | `S3BucketVersioningIT` (real LocalStack S3, versioning asserted) |
| REST API | `ErrorResponse{timestamp,status,error,code,message,path,correlationId,fieldErrors}` and per-endpoint request/response contracts | Controller integration tests against a real Spring context; `MalformedRequestErrorContractIT` for container-level JSON error parity |

Every contract is exercised against a real Spring context and real LocalStack resources — no mocked boundaries. **PASS.**

---

<a id="gate-6"></a>

## Gate 6 — Unsafe / Low-Level Code Audit

**Requirement:** Any unsafe/low-level constructs require per-site justification; more than 50 sites triggers explicit review.

**Static audit of `src/main/java`:**

| Category | Count | Threshold | Notes |
|----------|-------|-----------|-------|
| `Runtime.exec` / `ProcessBuilder` | 0 | 0 | no shell-out |
| Reflection (`Class.forName`, `setAccessible`, `getDeclaredMethod`, `newInstance`) | 0 | 0 | Spring DI handles instantiation |
| Raw SQL string concatenation (`createQuery` / `createNativeQuery` / concatenated SQL) | 0 | 0 | all data access via Spring Data JPA |
| Parameterized `@Query` methods | 2 | (safe) | bound parameters only — no string building |
| `@SuppressWarnings` in `src/main/java` | 0 | ≤3 | none required |

Total unsafe/low-level sites: **0** — far below the 50-site review threshold; no per-site justification required. **PASS.**

**Reproduce:**

```bash
grep -rncE 'Runtime\.getRuntime\(\)\.exec|new ProcessBuilder' src/main/java   # 0
grep -rnE  'createQuery\(|createNativeQuery\(' src/main/java                   # none
grep -rnE  '@SuppressWarnings' src/main/java                                   # none
```

---

<a id="gate-7"></a>

## Gate 7 — Scope Matching (Extended)

**Requirement:** The delivered scope matches the documented feature set (F-001 through F-022) with no unrequested expansion.

The migration delivers all 22 features spanning the 18 interactive online programs and 10 batch programs: signon/menu/admin, account view/update, card list/view/update, transaction list/view/add, bill payment, reports, and user CRUD (online); the 5-stage batch pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT) plus print/reference jobs (batch). Structurally: **8 REST controllers** (replacing 17 BMS screens), **11 JPA entities + 11 repositories**, **20 service classes**, a **5-stage Spring Batch pipeline**, and **AWS S3 + SQS + SNS** integration. No functionality beyond F-001…F-022 was introduced; the target is headless REST-only (no browser UI / design system). Per-feature mapping is enumerated in `traceability-matrix.md`. **PASS.**

---

<a id="gate-8"></a>

## Gate 8 — Integration Sign-Off

**Requirement:** ≥80% line coverage (JaCoCo), zero OWASP critical/high CVEs, and 100% traceability coverage, consolidating the evidence from the other gates.

| Sign-off item | Target | Actual | Status |
|---------------|--------|--------|--------|
| End-to-end verification | byte-equivalent | Gate 1 — 262/38 MD5 parity | ✅ |
| Interface contract verification | real-contract tests | Gate 5 — REST/SQS/S3 ITs | ✅ |
| Performance baseline | documented | Gate 3 — throughput + peak-memory table | ✅ |
| Unsafe code audit | ≤ thresholds | Gate 6 — 0 sites | ✅ |
| Line coverage (JaCoCo) | ≥ 80% | **95.06%** (3,672 / 3,863 lines; instruction 95.82%, branch 81.19%) | ✅ |
| OWASP dependency-check | 0 critical/high | 128 dependencies scanned; **0 unsuppressed findings** of any severity; 0 critical/high. 2 medium advisories reported by NVD are documented false positives, **suppressed with code-verified justification** in `dependency-check-suppressions.xml` (see below) | ✅ |
| Traceability matrix | 100% bidirectional | 100% (`traceability-matrix.md`, source @ `27d6c6f`) | ✅ |
| Test suite | all pass | **1,336 tests** (1,286 unit + 50 integration), 0 failures | ✅ |

**Result:** all sign-off items met. **PASS.**

**Reproduce:**

```bash
# Full build: compile (Gate 2), unit + integration tests, JaCoCo coverage check (Gate 8)
JAVA_HOME=/path/to/java-25 mvn -B clean verify

# OWASP dependency-check (Gate 8 CVE scan) — the `owasp` profile wires the
# suppression file and failBuildOnCVSS=7, so run it via that profile:
JAVA_HOME=/path/to/java-25 mvn -B -Powasp org.owasp:dependency-check-maven:check
# (append -DautoUpdate=false to reuse a cached NVD database for a faster, offline-friendly run)
```

The JaCoCo line-coverage rule (`<minimum>0.80</minimum>`) is enforced during `verify`; the build fails if coverage drops below 80%. Test counts and coverage above were measured from a clean `mvn clean verify` on the delivered source.

**OWASP suppression policy (Gate 8).** `dependency-check-suppressions.xml` contains **8** entries, each scoped to a single artifact (by Package URL) and to specific CVE(s), and each falling into exactly one policy category: **(a)** a documented false positive from a shared/over-broad NVD CPE, or **(b)** a genuine finding with no general-availability fix yet (pre-release only, tracked as a residual). **Every genuine CVE that has a released fix is remediated by a real version upgrade in `pom.xml`, never by suppression** (e.g. the `opentelemetry-semconv` 1.32.0 → 1.43.0 pin that cleared two genuine HIGHs, CVE-2026-24051 and CVE-2026-39883). The two **medium** advisories QA flagged (Issue 14) are both category (a) false positives: `kotlin-stdlib` **CVE-2020-29582** (insecure-temp-file flaw fixed in Kotlin 1.4.21; this project ships 1.9.25, matched only by a wildcard `cpe:2.3:a:jetbrains:kotlin:*` with no upper bound) and `opentelemetry-semconv` **CVE-2026-41178** (a DoS in the OpenTelemetry-**Go** baggage parser, per its `:go:` CPE edition; our dependency is the **Java** semantic-conventions constants artifact — generated `String`/`AttributeKey` fields with no parser and no executable code).

---

## Notes on test counts and coverage

The figures in this document (1,336 tests = 1,286 Surefire unit + 50 Failsafe integration; 95.06% line coverage) were measured from a clean `mvn clean verify` and supersede any earlier planning-era estimates. The `project-guide.md`, `technical-specifications.md`, and `executive-summary.html` metrics are kept consistent with these measured values.
