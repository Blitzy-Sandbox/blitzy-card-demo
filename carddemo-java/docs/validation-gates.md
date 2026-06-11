# CardDemo Validation Gates — Methodology & Checkpoint Status (Gates 1–8)

**Validation-gate methodology and checkpoint-status report for the AWS CardDemo COBOL → Java 25 + Spring Boot 3.x migration.**

This document defines the **verification method** and the **deliverable evidence artifact** for each of the
**eight validation gates** in the migration blueprint (`docs/technical-specifications.md` §0.7.2), together
with the cross-cutting quality bars the blueprint and the project guide require: **≥ 80 % line coverage**,
**OWASP zero critical/high CVEs**, **LocalStack verification with zero live AWS dependencies**, and
**behavioral parity against the nine ASCII fixtures**. It is the single source of truth for *how each gate
is checked* and *what artifact will satisfy it*, and it records the **current checkpoint status** of each
gate honestly.

> **Status framing (read this first).** This is a **methodology + status** report, **not** an
> evidence-of-completion report. The verification methods below are the agreed contract for each gate; the
> **evidence figures (test counts, coverage percentages, parity comparison results, throughput numbers) are
> produced by actual build and test runs against the owning code, and are reported here only once that code
> exists and those runs have executed.** At the current checkpoint, **CP1 (Foundation, Schema & Domain
> Model)**, the runtime/test gates are **pending** because the services, batch jobs, controllers, AWS
> integration, seed data, and test suites that produce their evidence are delivered in later checkpoints
> (CP2 → FINAL). No gate result is asserted from numbers that a build/test run has not yet produced. See
> §4 for the precise CP1 status.

**Traceability.** The legacy baseline is the AWS CardDemo COBOL application at commit SHA **`27d6c6f`**.
Per the Minimal Change Clause and the "COBOL sources not copied" preservation rule, **no COBOL,
copybook, BMS, or JCL source is reproduced here** — legacy constructs are referenced by name only, and
the paragraph-level COBOL → Java mapping that underpins Gate 8 lives in
`TRACEABILITY_MATRIX.md` (repository root). Decision rationale lives in
`DECISION_LOG.md`; the REST contracts referenced by Gate 5 are specified in
[`api-contracts.md`](api-contracts.md); and the before/after system views are in
[`architecture-before-after.md`](architecture-before-after.md).

**Minimal Change Clause.** This report documents **only the eight blueprint-defined gates** — no gate is
invented, removed, or "enhanced." The single intentional behavioral change in the whole migration is the
upgrade of plaintext `USRSEC` password storage to **BCrypt** verification (constraint C-003); every other
flow preserves the COBOL behavior at `27d6c6f` exactly.

---

## 1. The eight-gate model and the parity philosophy

The migration is validated through **characterization-test-driven parity**: the observable behavior of
each COBOL program is captured against real input — principally the **nine ASCII fixtures** under
`app/data/ASCII/` — and the Java target is required to reproduce that behavior **bit-for-bit on decimal
values and field-for-field on record contracts**. The nine ASCII fixtures are the **canonical parity
ground truth**; the 13 EBCDIC binaries under `app/data/EBCDIC/` are byte-level reference only and are
**not loaded** by the application (blueprint §0.6.5).

Each gate below answers two questions explicitly:

1. **Verification method** — *how* the gate is checked (command, test, audit, or measurement). This is
   defined now and is the same regardless of checkpoint.
2. **Deliverable evidence** — *what artifact* will prove the gate (a comparison table, a build-log excerpt,
   a throughput table, a per-file report, a contract-test matrix, an audit table, a scope matrix, or a
   consolidated sign-off), and the **checkpoint at which that artifact is produced**.

The gates are exercised in part by a dedicated end-to-end suite, `GateVerificationTest`, planned to supply
programmatic evidence for Gates 1–8. That suite — like the services, jobs, and controllers it drives — is
a later-checkpoint deliverable; it does not exist at CP1.

### 1.1 Status legend

| Symbol | Meaning |
|:---:|---|
| ✅ **Verified** | Evidence has been produced and asserted by an actual build/test run or measured artifact at this checkpoint. |
| 🟡 **Partial (CP1)** | Partially demonstrable now from the CP1 foundation (e.g., the foundation compiles warning-free), with the full gate pending the owning code. |
| ⏳ **Pending** | Method defined; evidence not yet produced because the owning code (services / jobs / controllers / AWS / tests / seed) is delivered in a later checkpoint (CP2 → FINAL). Reported honestly; **not** claimed as a pass. |
| ❌ **Not started** | Not yet implemented (e.g., CI/CD pipeline). |

> **Accuracy over optimism.** A gate is marked ✅ **only** when an actual run has produced its evidence at
> the stated checkpoint. Anything depending on code not yet present is ⏳ **Pending** (or 🟡 partial), never
> ✅. This preserves the document's audit value: the figures here will always trace to a real build/test
> run, not to anticipation of one.

### 1.2 Gate index — CP1 status

| Gate | Title | Verification method | Primary evidence artifact | CP1 status |
|---|---|---|---|:---:|
| [Gate 1](#gate-1) | End-to-End Boundary Verification | Run `DailyTransactionPostingJob` on `dailytran.txt`; compare to COBOL baseline | Comparison table | ⏳ Pending (batch job — CP2+) |
| [Gate 2](#gate-2) | Zero-Warning Build | `mvn clean verify` with `-Xlint:all` (`-Werror` intent) | Build-log excerpt | 🟡 Partial (CP1 foundation compiles warning-free; full `verify` pending) |
| [Gate 3](#gate-3) | Performance Baseline | Benchmark batch throughput (elapsed, peak memory via JMX, rec/s) | Throughput table | ⏳ Pending (batch job — CP2+) |
| [Gate 4](#gate-4) | Named Real-World Validation Artifacts | Load all 9 ASCII fixtures via Flyway `V3`; exercise the pipeline | Per-file processing report | ⏳ Pending (`V3__seed_data.sql` + pipeline — CP2+) |
| [Gate 5](#gate-5) | API/Interface Contract Verification | Integration tests over file, SQS, S3, and REST contracts | Per-interface evidence | ⏳ Pending (controllers/AWS/integration tests — CP3+) |
| [Gate 6](#gate-6) | Unsafe/Low-Level Code Audit | Static audit of SQL concat, `Runtime.exec`, reflection, casts, suppressions | Audit table | 🟡 Partial (CP1 code audited clean; full-tree audit pending) |
| [Gate 7](#gate-7) | Scope Matching (Extended) | Map every migrated subsystem to its evidence | Scope-evidence matrix | ⏳ Pending (subsystems delivered CP2 → FINAL) |
| [Gate 8](#gate-8) | Integration Sign-Off Checklist | Consolidate Gates 1/3/5/6 + coverage + OWASP + traceability | Consolidated sign-off | ⏳ Pending (master gate — FINAL) |

---

<a id="gate-1"></a>

## Gate 1 — End-to-End Boundary Verification

**Verification method.** Drive the production-representative daily-transaction file end to end through
the batch boundary and compare the result against the COBOL-baseline-derived expected output.

- **Input artifact:** `app/data/ASCII/dailytran.txt` — the production-representative daily transaction
  file (**20 records**, fixed-width `RECLN = 350`, layout `CVTRA06Y` / `DALYTRAN-RECORD`).
- **Processing path (target design):** `DailyTransactionPostingJob` → `DailyTransactionReader` (fixed-width
  parse) → `TransactionPostingProcessor` (4-stage validation cascade, reject codes **100–109**, derived
  from `CBTRN02C.cbl` paragraph `2000-VALIDATE-TXN`) → valid rows posted to **PostgreSQL** (`transaction`
  table) and invalid rows written to **S3** (`carddemo-batch-output`) by `RejectWriter` with reason
  trailers.
- **Oracle:** the COBOL behavior at commit `27d6c6f`. Decimal aggregates use `BigDecimal`
  (scale 2, `RoundingMode.HALF_EVEN`) so totals match the packed-decimal arithmetic exactly.

The `DALYTRAN-RECORD` field contract that the boundary must carry (no field added, dropped, or reordered)
— a static specification derived from copybook `CVTRA06Y`:

| Field | PIC (COBOL) | Width | Java mapping |
|---|---|---:|---|
| `DALYTRAN-ID` | `X(16)` | 16 | `String` transaction id |
| `DALYTRAN-TYPE-CD` | `X(02)` | 2 | `String` type code → `TransactionType` |
| `DALYTRAN-CAT-CD` | `9(04)` | 4 | `Integer` category code → `TransactionCategory` |
| `DALYTRAN-SOURCE` | `X(10)` | 10 | `String` source |
| `DALYTRAN-DESC` | `X(100)` | 100 | `String` description |
| `DALYTRAN-AMT` | `S9(09)V99` | 11 | **`BigDecimal`** (scale 2) — never `double` |
| `DALYTRAN-MERCHANT-ID` | `9(09)` | 9 | `Long` merchant id |
| `DALYTRAN-MERCHANT-NAME` | `X(50)` | 50 | `String` |
| `DALYTRAN-MERCHANT-CITY` | `X(50)` | 50 | `String` |
| `DALYTRAN-MERCHANT-ZIP` | `X(10)` | 10 | `String` |
| `DALYTRAN-CARD-NUM` | `X(16)` | 16 | `String` card number (xref key) |
| `DALYTRAN-ORIG-TS` | `X(26)` | 26 | `LocalDateTime` origin timestamp (external 26-byte rendering at the boundary) |
| `DALYTRAN-PROC-TS` | `X(26)` | 26 | `LocalDateTime` processing timestamp (external 26-byte rendering at the boundary) |
| `FILLER` | `X(20)` | 20 | (reserved — preserved, unused) |

**Deliverable evidence — boundary comparison table (to be produced when the batch job exists).** The
comparison is expressed by verification dimension so that each row will be an independently checkable
parity assertion. The exact per-record golden comparison is asserted programmatically by
`GateVerificationTest` once that suite is delivered.

| # | Verification dimension | Expected (COBOL baseline @ `27d6c6f`) | Java (`DailyTransactionPostingJob`) |
|---|---|---|---|
| 1 | Records read from `dailytran.txt` | 20 | (to be measured) |
| 2 | Fixed-width decode (350-byte `RECLN`, `CVTRA06Y`) | 20 / 20 records, all fields decoded | (to be measured) |
| 3 | 4-stage validation cascade applied | per `CBTRN02C` `2000-VALIDATE-TXN` | per `TransactionPostingProcessor.validate()` |
| 4 | Valid → posted to PostgreSQL `transaction` | = COBOL valid count | (to be measured) |
| 5 | Invalid → rejected to S3 (reason trailer, codes 100–109) | = COBOL reject count | (to be measured) |
| 6 | Posted-amount aggregate (`BigDecimal`, scale 2, `HALF_EVEN`) | Σ `DALYTRAN-AMT` | (to be measured) |
| 7 | Transaction-ID assignment (browse-to-end + increment) | sequential per COBOL | sequential per ID-sequence factory |
| 8 | Category-balance side effects (`TCATBAL` upsert) | per COBOL update | (to be measured) |

**CP1 status:** ⏳ **Pending.** `DailyTransactionPostingJob`, its reader/processor/writers, and
`GateVerificationTest` are batch deliverables for a later checkpoint (CP2+). At CP1 the **target field
contract** above is fixed (the `DailyTransaction` entity maps `CVTRA06Y` field-for-field with `BigDecimal`
amount and `LocalDateTime` timestamps), but no end-to-end run has been executed.

---

<a id="gate-2"></a>

## Gate 2 — Zero-Warning Build

**Verification method.** Compile and verify the whole project with all lint diagnostics enabled, treating
the build as clean only when **zero warnings** are emitted.

- **Command:** `mvn clean verify` with the compiler configured for
  `<compilerArgs><arg>-Xlint:all</arg></compilerArgs>` (and `-Werror` intent — warnings are not
  tolerated).
- **Allowed suppressions:** only framework-generated code may be suppressed — the **JPA static
  metamodel** and **MapStruct**-generated mappers. No hand-written source carries an unjustified
  `@SuppressWarnings`.
- **Tie-in:** blueprint Build & Quality rules — *Zero-Warning Build* (§0.7.8).

**Deliverable evidence — build-log excerpt.** The full-project `mvn clean verify` excerpt (compiled source
count, `BUILD SUCCESS`, `Total warnings: 0`) is produced at FINAL when the full source tree and test suites
exist. What can be shown **now** at CP1 is the foundation build:

```text
$ cd carddemo-java && mvn -B -ntp clean compile
[INFO] --- maven-compiler-plugin:compile (default-compile) ---
[INFO] Compiling <CP1 foundation source files> with javac [debug release 25] to target/classes
[INFO] BUILD SUCCESS
```

**CP1 status:** 🟡 **Partial.** The CP1 foundation (entities, embedded keys, enums, exceptions,
configuration, observability, shared services, DTOs) **compiles cleanly with `mvn clean compile`** and
emits no warnings on those files. The full `mvn clean verify` zero-warning gate — which also runs the
test phases over the complete source tree — is **pending** until the remaining code and tests exist
(FINAL). The only permissible suppressions remain framework-generated (JPA metamodel / MapStruct).

---


<a id="gate-3"></a>

## Gate 3 — Performance Baseline

**Verification method.** Run `DailyTransactionPostingJob` against the full dataset and measure elapsed
time, peak heap (via JMX / `MemoryMXBean`), and throughput in records/second.

- **COBOL baseline availability:** the repository contains **no SLA or performance documentation** for
  the legacy programs (no timing artifacts at `27d6c6f`). A COBOL-vs-Java comparison is therefore not
  possible; instead, the **Java run establishes the reference baseline** for future regression
  comparison, exactly as the blueprint prescribes.
- **Measurement harness:** Spring Batch `JobExecution` start/end instants for elapsed time; a JMX
  `MemoryMXBean` heap snapshot at job end for peak memory; records/second computed as
  `recordsRead / elapsedSeconds`.

**Deliverable evidence — throughput table (to be produced when the batch job exists).** The Java reference
baseline is recorded from a local Docker Compose run (PostgreSQL 16 + LocalStack) once
`DailyTransactionPostingJob` is implemented:

| Metric | `DailyTransactionPostingJob` (Java reference) | COBOL baseline |
|---|---|---|
| Input file | `dailytran.txt` (`RECLN = 350`) | — |
| Records read | 20 (fixture size) | n/a (no SLA docs) |
| Elapsed time (chunk-oriented step) | (to be measured) | **unavailable** |
| Peak heap (JMX `MemoryMXBean`) | (to be measured) | **unavailable** |
| Throughput (records/second) | (to be measured) | **unavailable** |
| Chunk size | configured in `BatchConfig` | — |

> **Interpretation.** Because no COBOL timing exists in the repository, Gate 3 is satisfied by
> *establishing* and *documenting* the Java baseline (methodology + measured run), not by a delta against
> the mainframe. Future changes are regression-checked against this baseline once it is recorded.

**CP1 status:** ⏳ **Pending.** No batch job exists at CP1, so no baseline has been measured; the
measurement method above is fixed and will be exercised at the owning checkpoint (CP2+).

---

<a id="gate-4"></a>

## Gate 4 — Named Real-World Validation Artifacts

**Verification method.** Load **all nine ASCII fixtures** through the Flyway **`V3__seed_data.sql`**
migration into PostgreSQL and exercise them through the batch pipeline and the JPA repositories, so the
parity ground truth is real, named, production-representative data — not synthetic mocks.

The 13 EBCDIC binaries under `app/data/EBCDIC/` are **byte-level reference only and are not loaded**
(blueprint §0.6.5); only the nine ASCII fixtures drive the seed and the parity gates.

**Deliverable evidence — per-file processing report (target mapping; exercised when `V3` + pipeline exist):**

| # | Fixture (`app/data/ASCII/`) | Domain content | Seeded entity (Flyway `V3`) | Exercised by |
|---|---|---|---|---|
| 1 | `acctdata.txt` | **9 account records** | `Account` (`ACCTDAT`) | `AccountViewService`, `CBACT01C`/`CBACT04C` flows, interest job |
| 2 | `carddata.txt` | card records | `Card` (`CARDDAT`) | card list/detail/update services, `CBACT02C` flow |
| 3 | `custdata.txt` | customer records | `Customer` (`CUSTDAT`) | account view, statement generation |
| 4 | `cardxref.txt` | cross-reference records | `CardCrossReference` (`CARDXREF` + `CXACAIX`) | xref-keyed account/card resolution |
| 5 | `dailytran.txt` | daily transaction records | `DailyTransaction` (`DALYTRAN` staging) | `DailyTransactionPostingJob` (Gate 1) |
| 6 | `discgrp.txt` | disclosure-group rates | `DisclosureGroup` (`DISCGRP`) | interest calculation (`DEFAULT`-group fallback) |
| 7 | `tcatbal.txt` | transaction category balances | `TransactionCategoryBalance` (`TCATBAL`) | posting side effects, interest accrual |
| 8 | `trancatg.txt` | transaction categories | `TransactionCategory` (`TRANCATG`) | reference-data lookups |
| 9 | `trantype.txt` | transaction types | `TransactionType` (`TRANTYPE`) | reference-data lookups |

The design loads all nine files deterministically on application startup (Flyway runs `V1` schema → `V2`
indexes → `V3` seed before any service or batch job executes); repository integration tests will assert
the seeded row counts against the fixtures.

**CP1 status:** ⏳ **Pending.** `V1__create_schema.sql` and `V2__create_indexes.sql` are present at CP1,
but `V3__seed_data.sql` and the repositories/integration tests that exercise the seeded data are
later-checkpoint deliverables (CP2+). The fixture-to-entity mapping above is fixed; no seed/exercise run
has been executed.

---


<a id="gate-5"></a>

## Gate 5 — API/Interface Contract Verification

**Verification method.** Verify that every external interface contract preserved from the mainframe is
honored by the Java target, using integration tests that exercise the real Spring context (and
LocalStack for AWS). Four interface families are checked.

**Deliverable evidence — per-interface contract evidence (verifying components delivered CP3+):**

| Interface | Preserved contract | Verifying component | Evidence (when delivered) |
|---|---|---|---|
| **File (fixed-width)** | `DALYTRAN-RECORD` 350-byte layout (`CVTRA06Y`), exact field offsets/widths | `DailyTransactionReader` parsing | Reader unit/integration tests decode all fixture records field-for-field (Gate 1 row 2) |
| **SQS message** | Report-submission message schema replacing CICS TDQ `WRITEQ` | `ReportSubmissionService` → `carddemo-report-jobs.fifo` | LocalStack integration test publishes via the service and asserts message receipt + body schema |
| **S3 object format** | Batch output layouts — statements, reports, **rejection files (reason trailers)** | `RejectWriter`, statement/report writers → `carddemo-batch-output`, `carddemo-statements` | LocalStack integration tests upload/verify output objects, then delete in `@AfterAll` |
| **REST API** | Field names/lengths/validation from the 17 BMS mapsets → 8 controllers | `@RestController` layer + Jakarta Validation | E2E online-transaction tests exercise every endpoint against a real Spring Boot context |

The full REST surface — paths, methods, request/response field contracts, and the CICS-transaction-id →
route mapping (for example `CR00` / `CORPT00C` → `POST /api/reports/submit`) — is specified in
[`api-contracts.md`](api-contracts.md), which this gate treats as the contract of record. Each endpoint
documented there will be covered by an integration or E2E test as the controllers are delivered.

**CP1 status:** ⏳ **Pending.** The DTOs that carry the REST field contracts exist at CP1, but the
controllers, AWS integration, and the integration/E2E tests that assert the file/SQS/S3/REST contracts are
later-checkpoint deliverables (CP3+). No contract test has been executed.

---

<a id="gate-6"></a>

## Gate 6 — Unsafe/Low-Level Code Audit

**Verification method.** Statically audit the source tree for unsafe or low-level constructs and count
each category. Any count above zero requires a **per-site justification** (blueprint §0.7.8); the
blueprint records the expected bounds below.

**Deliverable evidence — audit table (bounds are the blueprint targets; the full-tree count is produced at FINAL):**

| # | Audit category | Bound (blueprint) | Rationale / per-site justification |
|---|---|:---:|---|
| 1 | Raw SQL string concatenation | **0** | All persistence goes through Spring Data JPA derived queries or `@Query`; no string-built SQL, so no injection surface. |
| 2 | `Runtime.exec` / process spawning | **0** | No shell-outs; batch and online flows are pure JVM + JDBC + AWS SDK. |
| 3 | Reflection usage (application code) | **0** | Spring DI performs all wiring/instantiation; application code does not call the reflection API directly. |
| 4 | Unchecked casts | **≤ 5** | Confined to Spring Batch `ItemProcessor`/`ItemReader` generic boundaries where type erasure forces a cast; each is localized and guarded by the configured chunk types. |
| 5 | Suppressed warnings (`@SuppressWarnings`) | **≤ 3** | JPA static-metamodel interactions only; framework-generated category, consistent with the Gate 2 suppression allow-list. |

**Per-site justification policy.** Categories 1–3 are expected at **zero**, so no justification is
required. Categories 4–5 are bounded (`≤ 5`, `≤ 3`) and are justified collectively above as
framework-boundary necessities; if any concrete site is added later, it must carry an inline comment
explaining why the cast/suppression is safe, and the count here must be updated.

**CP1 status:** 🟡 **Partial.** A static audit of the **CP1 source set** (entities, embedded keys, enums,
exceptions, configuration, observability, shared services, DTOs) finds **zero** raw-SQL concatenation,
**zero** `Runtime.exec`, **zero** application-code reflection, and no unjustified `@SuppressWarnings`.
The full-tree audit (categories 4–5 in particular, which live at batch generic boundaries) is **pending**
the remaining code (FINAL).

---


<a id="gate-7"></a>

## Gate 7 — Scope Matching (Extended)

**Verification method.** Demonstrate that every migrated subsystem class — batch processing, file I/O,
inter-program linkage, JCL orchestration, and AWS integration — is present in the target with concrete
evidence, justifying the extended scope of the migration.

**Deliverable evidence — scope-evidence matrix (target realization; evidence accrues CP2 → FINAL):**

| # | Subsystem | Legacy form | Java realization | Evidence (when delivered) |
|---|---|---|---|---|
| 1 | **Batch processing** | 5-stage JCL pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT / TRANREPT) | 6 Spring Batch `Job` beans + `BatchPipelineOrchestrator` with `JobExecutionDecider` | Integration — Batch Pipeline tests; E2E — Batch Pipeline tests |
| 2 | **File I/O** | Sequential PS + VSAM reads/writes | 9 fixtures × 3 formats (fixed-width in, DB rows, S3 objects out) | Gate 4 per-file report; Gate 1 boundary table |
| 3 | **Inter-program calls** | `CALL` / `XCTL` between programs | Spring **bean injection** (`@Autowired` services) | Service-layer unit tests; compile-time wiring |
| 4 | **JCL orchestration** | 29 JCL jobs + condition codes | Spring Batch `Job`/`Step`/`Flow` + `ExitStatus` deciders | `BatchPipelineOrchestrator`; pipeline integration tests |
| 5 | **AWS integration** | CICS TDQ, GDG generations | **S3 + SQS + SNS** via Spring Cloud AWS | LocalStack integration tests (S3/SQS/SNS) |

**CP1 status:** ⏳ **Pending.** The subsystems above (batch, file I/O writers, service linkage, JCL
orchestration, AWS integration) are delivered across later checkpoints (CP2 → FINAL). At CP1 the shared
foundation they will build on (entities, repositories' target schema, shared validation services,
configuration) is present; the per-subsystem evidence is not yet produced.

---

<a id="gate-8"></a>

## Gate 8 — Integration Sign-Off Checklist

**Verification method.** Consolidate the per-gate evidence with the project-wide quality bars into a
single sign-off. Gate 8 is the **master gate**: it passes only when its constituent checks pass, and it
is the **final** gate — it cannot close before the gates and bars it consolidates have produced their
evidence.

**Deliverable evidence — consolidated sign-off table (closes at FINAL):**

| # | Sign-off item | Source of evidence | Threshold / target | CP1 status |
|---|---|---|---|:---:|
| 1 | End-to-end boundary verification | [Gate 1](#gate-1) | Java output = COBOL baseline | ⏳ Pending (CP2+) |
| 2 | Interface contract verification | [Gate 5](#gate-5) | File/SQS/S3/REST contracts honored | ⏳ Pending (CP3+) |
| 3 | Performance baseline | [Gate 3](#gate-3) | Java reference baseline established | ⏳ Pending (CP2+) |
| 4 | Unsafe-code audit | [Gate 6](#gate-6) | Within blueprint bounds, justified | 🟡 Partial (CP1 clean; full-tree pending) |
| 5 | **Line coverage (JaCoCo)** | `jacoco-maven-plugin` rule `<minimum>0.80</minimum>` | ≥ 80 % | ⏳ Pending (no tests yet — FINAL) |
| 6 | **OWASP dependency-check** | `dependency-check-maven` (`failBuildOnCVSS` = 7) | Zero critical/high CVEs | ⏳ Pending (scan run — FINAL) |
| 7 | **Traceability matrix** | `TRACEABILITY_MATRIX.md` (repository root) | 100 % COBOL-paragraph coverage | ⏳ Pending verification (mapping planned; verified as code lands) |
| 8 | Test suite | `mvn verify` (Surefire + Failsafe) | 100 % pass | ⏳ Pending (no tests at CP1 — FINAL) |

**Configuration note.** The thresholds above are already pinned in the build configuration: JaCoCo
`<minimum>0.80</minimum>`; OWASP `failBuildOnCVSS` of **7** (CVSS ≥ 7.0 = high/critical). The
**measured** values that satisfy these thresholds are produced by the gated build at FINAL and are not
asserted here before that run.

**CP1 status:** ⏳ **Pending.** As the master gate, Gate 8 closes only at FINAL, once every constituent
gate and quality bar has produced its evidence from an actual run.

---


## 2. Cross-cutting quality bars

Beyond the eight gates, the blueprint's Build & Quality rules (§0.7.8) and the project guide define four
project-wide bars. These are summarized here with their **target** and **CP1 status**. The measured
figures (coverage percentage, CVE counts) are produced by the gated build at FINAL and are not asserted
before that run.

| # | Quality bar | Mechanism | Target | CP1 status |
|---|---|---|---|:---:|
| 1 | **Zero-warning build** | `mvn clean verify`, `-Xlint:all` (`-Werror` intent) | 0 warnings (framework suppressions only) | 🟡 Foundation `compile` warning-free; full `verify` pending |
| 2 | **Line coverage** | JaCoCo `jacoco-maven-plugin` 0.8.14, rule `<minimum>0.80</minimum>` | ≥ 80 % | ⏳ Pending (no tests at CP1) |
| 3 | **OWASP — zero critical/high CVEs** | `dependency-check-maven` 12.1.0, `failBuildOnCVSS` = 7 | 0 critical/high | ⏳ Pending (scan run — FINAL) |
| 4 | **Unsafe-code audit** | Static audit (see [Gate 6](#gate-6)) | 0 / 0 / 0 / ≤ 5 / ≤ 3 | 🟡 CP1 code within bounds; full-tree pending |

### 2.1 Test suite and coverage

The verification suite (Maven Surefire for unit tests, Failsafe for integration/E2E, with **Testcontainers**
PostgreSQL 16 and **LocalStack** for S3/SQS/SNS, plus the end-to-end `GateVerificationTest`) is a
later-checkpoint deliverable. **At CP1 there are no committed tests** — `mvn clean test` runs **0 tests** —
so no coverage figure exists yet.

JaCoCo coverage is **gated on the line dimension** (`<minimum>0.80</minimum>`). The measured line/branch/
method/instruction percentages will be reported here once the test suites exist and the gated build runs
(FINAL); they are intentionally **not** stated before then so that every number in this report traces to a
real JaCoCo run.

---

## 3. LocalStack verification — zero live AWS

Per the blueprint's LocalStack rule (§0.7.7), **every AWS interaction is verifiable against LocalStack
with zero live AWS dependencies**, and no test, local-dev workflow, or build step requires real AWS
credentials. The methodology below is fixed now; the AWS integration code and its LocalStack tests are
later-checkpoint deliverables (CP4+).

### 3.1 Integration points

| AWS service | CardDemo usage (legacy → Java) | LocalStack test strategy |
|---|---|---|
| **S3** | GDG generations / sequential staging → batch input, statement, report, and rejection objects | Create buckets in `@BeforeAll`, upload fixtures, verify output objects, delete in `@AfterAll` |
| **SQS** | CICS TDQ `JOBS` → report-submission FIFO queue | Create FIFO queue, publish via `ReportSubmissionService`, assert message receipt |
| **SNS** | Alert/notification publishing | Create topic, subscribe an SQS endpoint, publish, verify fan-out |

### 3.2 Resource lifecycle and configuration (target design)

- **Self-contained resources.** Every AWS-touching integration test **creates** its buckets/queues/topics
  in `@BeforeAll` and **deletes** them in `@AfterAll`, so no test depends on pre-existing LocalStack
  state.
- **Endpoint override.** `application-local.yml` and `application-test.yml` point the AWS SDK at
  `http://localhost:4566` (LocalStack). No live endpoint is ever contacted.
- **Provisioning.** `docker-compose.yml` runs `localstack/localstack-pro` with `SERVICES=s3,sqs,sns`;
  `localstack-init/init-aws.sh` creates the S3 buckets (`carddemo-batch-input`, `carddemo-batch-output`,
  `carddemo-statements`) and the SQS queue (`carddemo-report-jobs.fifo`).
- **Credentials.** `LOCALSTACK_AUTH_TOKEN` activates LocalStack Pro where applicable; AWS SDK
  credentials are non-secret placeholders for the local profile — **no production credentials exist in
  this repository.**

**CP1 status:** ⏳ **Pending.** `application-local.yml`/`application-test.yml`, `docker-compose.yml`,
`localstack-init/init-aws.sh`, the AWS integration code, and the LocalStack tests are later-checkpoint
deliverables. The zero-live-AWS methodology above is the agreed contract; no LocalStack run has been
executed at CP1.

---

## 4. CP1 status summary (Foundation, Schema & Domain Model)

This section states honestly what is **present and verified now** at CP1 versus what is **pending** in
later checkpoints, so the report's audit value is preserved.

**Present and verified at CP1 (genuinely current facts):**

| Item | Status | Note |
|---|:---:|---|
| Foundation compiles | ✅ Verified | `mvn -B -ntp clean compile` → `BUILD SUCCESS`, zero warnings on the CP1 source set |
| Flyway `V1__create_schema.sql` | ✅ Present | 11 tables defined from the VSAM `DEFINE CLUSTER` specs |
| Flyway `V2__create_indexes.sql` | ✅ Present | `CXACAIX` + `TRANSACT` AIX indexes; no unsupported optimization indexes |
| 11 JPA entities + 3 embedded keys | ✅ Present | Mapped field-for-field to V1; `BigDecimal` for all decimals; `@Version` on `Account`/`Card` |
| Entity ↔ schema parity | ✅ Verified (static) | Entity `@Table`/`@Column` names and types align with V1 (Hibernate `ddl-auto=validate` contract) |
| Enums, exception hierarchy, shared services | ✅ Present | `UserType`/`FileStatus`/`RejectCode`/`TransactionSource`; `CardDemoException` tree; `DateValidationService`, `ValidationLookupService` |
| DTOs + Jakarta Validation | ✅ Present | REST field contracts with width/format constraints; operation-scoped validation groups where the COBOL edits differ by operation |
| Security/observability/config foundation | ✅ Present | BCrypt `PasswordEncoder`, stateless filter chain, correlation-id filter, Micrometer wiring, structured logging |
| CP1 unsafe-code audit | ✅ Verified (CP1 scope) | No raw SQL concat, no `Runtime.exec`, no application reflection, no unjustified suppressions in CP1 code |

**Pending in later checkpoints (CP2 → FINAL):**

| Item | Status | Owning checkpoint |
|---|:---:|---|
| Gates 1, 3 — batch boundary + performance | ⏳ Pending | CP2+ (`DailyTransactionPostingJob`) |
| Gate 4 — `V3__seed_data.sql` + fixture exercise | ⏳ Pending | CP2+ |
| Gate 5 — controllers + AWS + integration/E2E contracts | ⏳ Pending | CP3+ |
| Gate 7 — full subsystem scope evidence | ⏳ Pending | CP2 → FINAL |
| Gate 8 — master integration sign-off | ⏳ Pending | FINAL |
| Line coverage ≥ 80 % (JaCoCo) | ⏳ Pending | FINAL (no tests at CP1) |
| Full test suite (Surefire + Failsafe) | ⏳ Pending | FINAL (`mvn test` runs 0 tests at CP1) |
| `GateVerificationTest` (Gates 1–8 evidence) | ⏳ Pending | FINAL |
| OWASP zero critical/high CVE scan | ⏳ Pending | FINAL (plugin configured; scan not yet run) |
| Traceability-matrix verification (100 % paragraphs) | ⏳ Pending | mapping planned in `TRACEABILITY_MATRIX.md`; verified as code lands |
| LocalStack AWS verification | ⏳ Pending | CP4+ |
| CI/CD pipeline | ❌ Not started | No `.github/workflows/*.yml`; builds are currently manual |

> The figures that satisfy the quality bars (test counts, coverage percentages, CVE counts, parity
> comparison results, throughput) are **produced by actual build/test runs against the owning code** and
> will be recorded here at the checkpoint that produces them — never asserted in advance.

---

## 5. Traceability and provenance

- **Legacy baseline:** AWS CardDemo COBOL application at commit SHA **`27d6c6f`**. COBOL, copybook, BMS,
  and JCL sources are **not** copied into this repository; only the SHA is referenced for traceability
  (Minimal Change Clause; blueprint §0.7.2).
- **Gate definitions:** `docs/technical-specifications.md` §0.7.2; Build & Quality rules §0.7.8;
  LocalStack rule §0.7.7.
- **Related deliverables:** `TRACEABILITY_MATRIX.md` (paragraph-level
  mapping) and `DECISION_LOG.md` (decision rationale) at the repository root, plus
  [`api-contracts.md`](api-contracts.md) (REST contracts) and
  [`architecture-before-after.md`](architecture-before-after.md) (before/after views) in this folder.

*This validation-gate report documents the methodology for the eight blueprint-defined gates and the
cross-cutting quality bars for the AWS CardDemo COBOL → Java 25 + Spring Boot 3.x migration, derived from
the frozen COBOL baseline at commit `27d6c6f`, together with the honest checkpoint status of each. No
COBOL source is reproduced; no gate is invented beyond the documented eight; and no evidence figure is
asserted before the build/test run that produces it.*
