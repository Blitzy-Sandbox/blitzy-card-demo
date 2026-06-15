# CardDemo — Validation Gates (8-Gate Evidence)

> **Source baseline:** AWS CardDemo `CardDemo_v1.0-15-g27d6c6f-68` — original repository commit
> SHA **`27d6c6f`**. The COBOL / copybook / JCL sources are **not** copied into `carddemo-java/`;
> they are consulted read-only and traced back by commit SHA (AAP §0.8.1).
>
> This document is the **evidence register for the eight validation gates** mandated by AAP §0.7.2
> and consolidated by **Gate 8**. It records, for every gate, the *objective*, the *deliverable /
> evidence*, the *pass criteria*, and a *status*. The migration target is **Java 25 LTS + Spring
> Boot 3.x on AWS, exercised locally against LocalStack** — there is **no live AWS dependency** and
> **no Google Cloud Platform (Environment 2) content** (out of scope per AAP §0.3.2 / §0.7.8).
> The gates validate only the migrated **closed feature set F-001 … F-022** — **no feature
> expansion** (AAP §0.3.2).

| Field | Value |
|---|---|
| **Document** | `docs/validation-gates.md` |
| **Owner** | Migration engineering |
| **Mandate** | AAP §0.7.2 (8 gates), §0.8.7 (build & quality rules) |
| **Quality baseline** | Java **25**, Spring Boot **3.5.15**, JaCoCo **0.8.14** (≥ 80 % line), OWASP `dependency-check-maven` **12.1.0** (fail on critical/high), Surefire/Failsafe **3.5.2**, Testcontainers **2.0.3** |
| **Companion docs** | [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) · [`../DECISION_LOG.md`](../DECISION_LOG.md) · [`api-contracts.md`](api-contracts.md) |

---

## 1. Purpose

The eight gates are the migration's definition of done. They convert the user-stated quality bar into
**objective, repeatable checks** — most of them enforced automatically by the build (`./mvnw clean
verify`) or by the integration suite (`./mvnw verify -Pintegration`), the remainder evidenced by named
integration / end-to-end tests and generated reports. This document is the single index that maps each
gate to *what proves it* and *where that proof is recorded*.

The gates and thresholds restated here are **binding** and must match the build configuration in
[`../pom.xml`](../pom.xml) exactly; any drift between this document and `pom.xml` is itself a gate
failure.

### 1.1 How to read a gate entry

Each gate below is documented with four fields:

- **Objective** — the behaviour or quality property the gate protects.
- **Deliverable / Evidence** — the concrete artifact (report, test, table) that demonstrates the gate.
- **Pass criteria** — the exact, measurable condition that must hold.
- **Status** — the lifecycle state of the gate (legend below).

### 1.2 Status legend

| Status | Meaning |
|---|---|
| **Defined** | Gate objective, evidence, and pass criteria are specified in this document (this deliverable). |
| **Automated** | Pass/fail is enforced automatically by the Maven build or a test in the suite. |
| **Evidence on run** | The concrete numeric/report artifact is produced when the build or integration suite executes in CI or locally. |

> The Java sources, Spring Batch jobs, and tests that *produce* the runtime evidence are created by
> sibling migration tasks. This document defines each gate's criteria and indexes **where** the
> evidence is recorded; it does not itself execute the build.

---

## 2. Gate Overview

| # | Gate | Pass criteria (summary) | Primary evidence | Status |
|---|---|---|---|---|
| 1 | End-to-end boundary | `dailytran.txt` (300 records) flows file → validate → PostgreSQL + S3 rejects with **real** infra (no mocks); byte-equivalence vs COBOL baseline | `BatchPipelineE2ETest`, byte-equivalence report | Defined · Automated |
| 2 | Zero-warning build | `./mvnw clean verify` compiles with `-Xlint:all -Werror` and **zero** warnings; suppressions only for framework-generated code | Maven reactor log | Defined · Automated |
| 3 | Performance baseline | Throughput, peak memory, records/second captured for the posting job; the Java run **establishes** the reference baseline | Benchmark table / metrics | Defined · Evidence on run |
| 4 | Named real-world artifacts | All **9** ASCII fixtures loaded via Flyway `V3__seed_data.sql` and processed through the pipeline | Fixture table (§ Gate 4), `V3__seed_data.sql` | Defined · Automated |
| 5 | Contract verification | Fixed-width parsing, SQS schema, S3 layouts, REST API each verified against the **real** contract | Integration tests, [`api-contracts.md`](api-contracts.md) | Defined · Automated |
| 6 | Unsafe / low-level audit | Counts of raw SQL concat, `Runtime.exec`, reflection, unchecked casts, suppressed warnings; **any count > 50 needs per-site justification** | Counts table (§ Gate 6) | Defined · Evidence on run |
| 7 | Scope matching (Extended) | Extended classification justified by multi-subsystem batch, file I/O, inter-program calls, 29 JCL → Spring Batch, S3+SQS+SNS | Source-scale table (§ Gate 7) | Defined |
| 8 | Integration sign-off | Gate 1/3/5/6 evidence **plus** ≥ 80 % JaCoCo line coverage, OWASP zero critical/high, 100 % paragraph traceability | `GateVerificationTest`, JaCoCo + OWASP reports, [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) | Defined · Automated |

---

## Gate 1 — End-to-End Boundary

**Objective.** Prove the migrated daily-transaction posting pipeline reproduces the COBOL program
`CBTRN02C` (job `POSTTRAN`) end-to-end at the system boundary: a real input file is read, every record
is validated, valid records are posted to PostgreSQL, and rejected records are written out — with the
**same outcomes** the mainframe produced for the same input.

**Boundary input.** `app/data/ASCII/dailytran.txt` — **300** fixed-width records of **350 bytes**
(layout `CVTRA06Y`, daily-transaction staging). This is the canonical boundary artifact: the file
enters `DailyTransactionPostingJob` and traverses the full pipeline.

**Pipeline under test.** `file → validate → PostgreSQL (posted) + S3 (rejections)`:

| Stage | COBOL origin (`CBTRN02C`) | Java realisation | Persistence |
|---|---|---|---|
| Read | `1000-DALYTRAN-GET-NEXT` (sequential read, `DALYTRAN-STATUS`) | `DailyTransactionReader` | — (S3 / file input) |
| Validate | `1500-VALIDATE-TRAN` (4-stage cascade; XREF + ACCT lookup) | `TransactionPostingProcessor.validate()` | — |
| Post | `2700-UPDATE-TCATBAL` / transaction write | `TransactionWriter` | **PostgreSQL** |
| Reject | `2500-WRITE-REJECT-REC` (`DALYREJS`) | `RejectWriter` | **S3** rejection object |

**Reject-reason fidelity.** The rejection records carry the exact COBOL validation fail-reasons
emitted by `CBTRN02C`. The source defines reason codes **100, 101, 102, 103, 109** (codes 104–108 are
**not present** in the source and are intentionally not represented):

| Code | COBOL fail-reason text | Trigger |
|---|---|---|
| `100` | `INVALID CARD NUMBER FOUND` | Card number not found in the cross-reference (XREF) lookup |
| `101` | `ACCOUNT RECORD NOT FOUND` | Account not found during account lookup |
| `102` | `OVERLIMIT TRANSACTION` | Transaction amount exceeds the account credit limit |
| `103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | Transaction dated after account expiry |
| `109` | `ACCOUNT RECORD NOT FOUND` | Account not found during the balance-update path |

The six COBOL `FILE STATUS` clauses of `CBTRN02C` (`DALYTRAN`, `TRANSACT`, `XREF`, `DALYREJS`,
`ACCTFILE`, `TCATBAL`) map to the `FileStatus` enum plus the `CardDemoException` hierarchy (see
[`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md)).

**Deliverable / Evidence.**

- An end-to-end test (`src/test/java/com/carddemo/e2e/BatchPipelineE2ETest.java`) that runs the full
  `DailyTransactionPostingJob` over `dailytran.txt`.
- A **byte-equivalence comparison report** of the Java pipeline output against the COBOL baseline
  output (posted rows + rejection records), demonstrating identical record content and counts.

**Pass criteria.**

- All 300 records are consumed; posted-row count + rejected-record count = 300.
- Posted rows in PostgreSQL and rejection objects in S3 are **byte-equivalent** to the COBOL baseline.
- The test exercises **real infrastructure**: a real PostgreSQL instance via **Testcontainers** and a
  real S3 endpoint via **LocalStack**. **Mocked I/O is insufficient** and does not satisfy this gate.

**Status.** Defined · Automated (enforced by `BatchPipelineE2ETest`; run via `./mvnw verify -Pintegration`).

---

## Gate 2 — Zero-Warning Build

**Objective.** The project must compile clean under the strictest practical Java lint settings, so that
no latent type, deprecation, or unchecked-operation warning is normalised away.

**Mechanism.** The `maven-compiler-plugin` is configured (in [`../pom.xml`](../pom.xml)) to target
`--release 25` and to pass `-Xlint:all -Werror`:

- `-Xlint:all` surfaces every lint category the compiler supports.
- `-Werror` promotes any surfaced warning to a **compilation error**, so a warning fails the build.

**Deliverable / Evidence.** The Maven reactor log of `./mvnw clean verify` showing `BUILD SUCCESS` with
zero compiler warnings.

**Pass criteria.**

- `./mvnw clean verify` completes with **zero** compiler warnings and `BUILD SUCCESS`.
- The **only** permitted suppressions (`@SuppressWarnings`) are for framework-generated code; each such
  suppression is counted under **Gate 6** and justified per-site.

**Status.** Defined · Automated (enforced by the compiler configuration; any warning aborts the build).

---


## Gate 3 — Performance Baseline

**Objective.** Establish quantified performance characteristics for the daily-transaction posting job
so that future changes can be measured against a known reference.

**Important framing.** The COBOL source contains **no published SLA or throughput figures** — there is
no mainframe performance number to match. Therefore the Java run **establishes the reference baseline**;
the figures below are recorded as the *baseline of record*, **not** as a pass/fail regression target.
Future runs compare against this baseline.

**Deliverable / Evidence.** A benchmark of `DailyTransactionPostingJob` over `dailytran.txt`
(300 records), reported as a metrics table and corroborated by the Micrometer counters
`carddemo.batch.records.processed` and `carddemo.batch.records.rejected` exposed at
`/actuator/prometheus`.

| Metric | Definition | Baseline (recorded on run) |
|---|---|---|
| Throughput | Records posted + rejected per wall-clock second | _captured on run_ |
| Records/second | End-to-end pipeline rate over the 300-record input | _captured on run_ |
| Peak heap | Maximum JVM heap during the job (from JVM/GC metrics) | _captured on run_ |
| Wall-clock duration | Job start → completion for the 300-record input | _captured on run_ |

**Pass criteria.** The benchmark executes successfully over the full 300-record input and the four
metrics above are recorded as the baseline. (No regression threshold applies on the first run because
no prior baseline exists.)

**Status.** Defined · Evidence on run.

---

## Gate 4 — Named Real-World Artifacts

**Objective.** Prove the migration is exercised against **real, named** production-shaped data — not
synthetic stubs — by loading every ASCII fixture from the source and driving it through the pipeline.

**Deliverable / Evidence.** All **9** ASCII fixtures under `app/data/ASCII/` are seeded into PostgreSQL
by Flyway migration `src/main/resources/db/migration/V3__seed_data.sql` on startup, and are processed
through the online/batch flows. Each fixture, its measured record count, its target table/entity, and
its role:

| # | Fixture (`app/data/ASCII/`) | Records | Target table (entity) | Role |
|---|---|---|---|---|
| 1 | `acctdata.txt` | 50 | `account` (`Account`) | Account master seed (300-byte `CVACT01Y` layout) |
| 2 | `carddata.txt` | 50 | `card` (`Card`) | Card master seed (150-byte `CVACT02Y` layout) |
| 3 | `custdata.txt` | 50 | `customer` (`Customer`) | Customer master seed (500-byte `CVCUS01Y` layout) |
| 4 | `cardxref.txt` | 50 | `card_xref` (`CardCrossReference`) | Card ↔ account ↔ customer linkage (`CXACAIX` AIX) |
| 5 | `dailytran.txt` | 300 | `daily_transaction` (`DailyTransaction`) | **Gate 1 boundary input** to `DailyTransactionPostingJob` (350-byte `CVTRA06Y`) |
| 6 | `discgrp.txt` | 51 | `disclosure_group` (`DisclosureGroup`) | Interest rate by disclosure group (incl. `DEFAULT` fallback) |
| 7 | `tcatbal.txt` | 50 | `transaction_category_balance` (`TransactionCategoryBalance`) | Composite-key category balances |
| 8 | `trancatg.txt` | 18 | `transaction_category` (`TransactionCategory`) | Transaction-category reference |
| 9 | `trantype.txt` | 7 | `transaction_type` (`TransactionType`) | Transaction-type reference |
| | **Total** | **626** | 9 tables | All loaded by `V3__seed_data.sql` |

**Pass criteria.**

- All 9 fixtures are present and loaded by Flyway `V3__seed_data.sql`; row counts in the target tables
  equal the fixture record counts above.
- `dailytran.txt` is additionally processed end-to-end through the posting pipeline (overlaps Gate 1).

**Status.** Defined · Automated (Flyway seed verified by repository integration tests; fixture names and
counts match `app/data/ASCII/`).

---


## Gate 5 — Contract Verification

**Objective.** Every **external interface contract** — fixed-width record layouts, the SQS message
schema, S3 object layouts, and the REST API — must be verified by an integration test that exercises
the **real** contract, not a mock of it. The authoritative description of these contracts lives in
[`api-contracts.md`](api-contracts.md); this gate proves the implementation honours them.

**Contracts and their verification.**

| Contract | Definition (`api-contracts.md`) | Verification | Real dependency |
|---|---|---|---|
| Fixed-width record parsing | Record layouts / lengths (`CVACT01Y`, `CVTRA06Y`, …) | Reader parses fixtures and round-trips field values | Source fixtures + PostgreSQL |
| SQS message schema | §3 SQS Contract — Report Submission Queue | `ReportSubmissionService` publishes; consumer asserts schema | **LocalStack** SQS FIFO `carddemo-report-jobs.fifo` |
| S3 object layouts | §4 S3 Contract — staging, statements, rejects | Writers upload; test asserts key + body layout | **LocalStack** S3 (`carddemo-batch-*`, `carddemo-statements`) |
| REST API contracts | §2 REST API Contracts (8 controllers) | MockMvc / WebTestClient assert request/response DTOs, status codes | Spring context (+ PostgreSQL for stateful flows) |
| FILE STATUS → HTTP | §1.7 FILE STATUS → HTTP status mapping | Error-path tests assert mapped HTTP status | Spring context |
| Batch reject codes | §1.9 Batch reject reason codes (`100–103`, `109`) | Posting test asserts reject reason in S3 object | PostgreSQL + LocalStack S3 |

**Deliverable / Evidence.** Integration tests under `src/test/java/com/carddemo/integration/`
(`repository/`, `batch/`, `aws/`) plus the contract reference in [`api-contracts.md`](api-contracts.md).

**Pass criteria.** Each contract above has at least one integration test that exercises the real
contract end-to-end (against Testcontainers PostgreSQL and/or LocalStack AWS) and the test passes under
`./mvnw verify -Pintegration`.

**Status.** Defined · Automated.

---

## Gate 6 — Unsafe / Low-Level Code Audit

**Objective.** Keep the migrated code free of unsafe or low-level constructs that undermine the
type-safety, security, and maintainability goals of the modernization. The architecture deliberately
favours **Spring Data** for all queries and **dependency injection** for all instantiation, so these
counts target **near-zero**.

**Threshold rule.** Each construct below is counted across the codebase. **Any count above 50 requires
per-site written justification**; counts at or below the architectural target need no justification.

| Construct audited | What is counted | Architectural target | Threshold (per-site justification above) |
|---|---|---|---|
| Raw SQL concatenation | String-built SQL outside Spring Data / parameter binding | 0 | 50 |
| `Runtime.exec` / `ProcessBuilder` | Native process invocation | 0 | 50 |
| Reflection | `java.lang.reflect` use in application code (excl. framework) | 0 | 50 |
| Unchecked casts | Casts producing `unchecked` warnings | 0 | 50 |
| Suppressed warnings | `@SuppressWarnings` annotations (Gate 2 carry-over) | near-0 (framework-generated only) | 50 |

**Deliverable / Evidence.** A counts table (the row values populated by a static scan of
`src/main/java/**`) recorded on each build run; any individual count exceeding **50** is accompanied by
a per-site justification list.

**Pass criteria.** Every audited count is at or below the architectural target, or — if any count
exceeds **50** — each occurrence above the threshold carries a documented per-site justification. No
unjustified count above 50 is permitted.

**Status.** Defined · Evidence on run.

---


## Gate 7 — Scope Matching (Extended)

**Objective.** Confirm the migration is classified **Extended** — i.e. a genuine multi-subsystem
re-platforming — and that the delivered scope matches the source's true breadth. The justification is
the verified source inventory; **no feature is added** beyond the closed set F-001 … F-022.

**Extended-classification justification.**

- **Multi-subsystem batch** — a 5-stage processing pipeline (`POSTTRAN → INTCALC → COMBTRAN →
  CREASTMT / TRANREPT`) re-hosted as Spring Batch jobs with condition-code deciders.
- **File I/O breadth** — input/output across **9** fixtures and **3** formats (fixed-width ASCII,
  relational rows, S3 objects).
- **Inter-program control transfer** — COBOL `CALL` / `XCTL` between programs becomes Spring bean
  injection and method invocation.
- **Job-control surface** — **29** JCL jobs re-expressed as Spring Batch jobs/steps plus configuration.
- **Cloud integration** — **S3 + SQS + SNS** integration, verified locally against LocalStack.

**Source-scale table (verified inventory, AAP §0.2).**

| Dimension | Count | Notes |
|---|---|---|
| COBOL programs | **28** | 17 online + 11 batch/utility |
| COBOL source lines | **19,254** | online 14,693 + batch 4,561 |
| Copybooks | **28** | record layouts, COMMAREA, lookup tables, helpers |
| BMS mapsets | **17** | 3270 screen definitions |
| BMS symbolic maps | **17** | one per mapset → REST DTO field contracts |
| JCL jobs | **29** | VSAM provisioning, GDG, batch pipeline, condition codes |
| VSAM datasets | **11** | re-platformed to PostgreSQL tables |
| Features | **22** | closed set F-001 … F-022 (no expansion) |

**Deliverable / Evidence.** This justification plus the source-scale table; the bidirectional mapping in
[`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) demonstrates that all 28 programs are accounted
for.

**Pass criteria.** The delivered Java/Spring artifacts cover the full source inventory above with no
out-of-scope additions; the Extended classification is justified by the five dimensions listed.

**Status.** Defined.

---

## Gate 8 — Integration Sign-Off

**Objective.** The final consolidating gate. It rolls up the evidence from Gates 1, 3, 5, and 6 and adds
three repository-wide quality bars that must all hold simultaneously for the migration to be signed off.

**Consolidated requirements.**

| Requirement | Source | Pass criteria | Enforcement |
|---|---|---|---|
| End-to-end boundary | Gate 1 | `dailytran.txt` byte-equivalent through real PostgreSQL + S3 | `BatchPipelineE2ETest` |
| Performance baseline | Gate 3 | Baseline metrics recorded for the posting job | Benchmark / Micrometer |
| Contract verification | Gate 5 | All external contracts verified against real dependencies | `integration/**` tests |
| Unsafe-code audit | Gate 6 | All counts ≤ target, or > 50 justified per-site | Static scan counts table |
| **Line coverage** | §0.8.7 | **≥ 80 %** JaCoCo line coverage across all packages | **JaCoCo 0.8.14** (`LINE COVEREDRATIO ≥ 0.80`) |
| **Dependency CVEs** | §0.8.7 | **Zero critical/high** CVEs (direct + transitive) | **OWASP `dependency-check-maven` 12.1.0** (`failBuildOnCVSS = 7`) |
| **Traceability** | §0.7.3 | **100 %** COBOL paragraph → Java coverage | [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) (527 paragraphs, 28 programs) |

**Deliverable / Evidence.**

- `src/test/java/com/carddemo/e2e/GateVerificationTest.java` — asserts the consolidated gate conditions.
- **JaCoCo** coverage report at `target/site/jacoco/index.html` (≥ 80 % line — build fails below).
- **OWASP** dependency-check report at `target/dependency-check-report.{html,json}` (zero critical/high).
- [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) — bidirectional COBOL ↔ Java mapping at
  100 % paragraph coverage, traced to source SHA `27d6c6f`.
- [`../DECISION_LOG.md`](../DECISION_LOG.md) — rationale, alternatives, and risks for every non-trivial
  migration decision (Explainability rule).

**Pass criteria.** All seven consolidated requirements above hold simultaneously: Gates 1/3/5/6 pass,
JaCoCo line coverage ≥ 80 %, OWASP reports zero critical/high CVEs, and the traceability matrix shows
100 % paragraph coverage.

**Status.** Defined · Automated (coverage and CVE bars enforced by the build; consolidated assertions in
`GateVerificationTest`).

---


## 3. Evidence Index

The runtime evidence for the gates is produced by the test suite and the build's reporting plugins.
The tests and reports below are created and produced by sibling migration tasks; this document indexes
**which evidence proves which gate** and **where it is recorded**.

| Evidence artifact | Gate(s) | Location / producer |
|---|---|---|
| `BatchPipelineE2ETest` | 1, 8 | `src/test/java/com/carddemo/e2e/BatchPipelineE2ETest.java` — full posting pipeline over `dailytran.txt` |
| `OnlineTransactionE2ETest` | 5 | `src/test/java/com/carddemo/e2e/OnlineTransactionE2ETest.java` — REST contract flows |
| `GateVerificationTest` | 8 | `src/test/java/com/carddemo/e2e/GateVerificationTest.java` — consolidated gate assertions |
| Repository integration tests | 4, 5 | `src/test/java/com/carddemo/integration/repository/**` — Flyway seed + keyed/paginated reads (Testcontainers PostgreSQL) |
| Batch integration tests | 1, 3, 5 | `src/test/java/com/carddemo/integration/batch/**` — step/job execution + condition codes |
| AWS integration tests | 5 | `src/test/java/com/carddemo/integration/aws/**` — S3/SQS/SNS against **LocalStack** |
| Maven reactor log | 2 | `./mvnw clean verify` console output (`-Xlint:all -Werror`, `BUILD SUCCESS`) |
| JaCoCo coverage report | 8 | `target/site/jacoco/index.html` (≥ 80 % line; build fails below) |
| OWASP dependency-check report | 8 | `target/dependency-check-report.{html,json}` (zero critical/high) |
| Surefire / Failsafe reports | 1, 5, 8 | `target/surefire-reports/`, `target/failsafe-reports/` |
| Byte-equivalence comparison report | 1 | Produced by the Gate 1 E2E run (Java output vs COBOL baseline) |
| Benchmark / Micrometer metrics | 3 | `/actuator/prometheus` counters (`carddemo.batch.records.processed`, `…rejected`) |
| Traceability matrix | 8 | [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) (100 % paragraph coverage) |
| Decision log | 8 | [`../DECISION_LOG.md`](../DECISION_LOG.md) (Explainability rule) |

---

## 4. Tooling & Thresholds Reference

The exact tool versions and thresholds that the gates depend on. These values are the **single source of
truth shared with [`../pom.xml`](../pom.xml)** and must remain in lockstep with it.

| Tool / setting | Version / value | Gate(s) | Configured in |
|---|---|---|---|
| Java (LTS) | **25** (`--release 25`) | 2 | `pom.xml` `maven-compiler-plugin` |
| Spring Boot | **3.5.15** | all | `pom.xml` parent |
| Compiler lint | `-Xlint:all -Werror` | 2 | `pom.xml` `maven-compiler-plugin` |
| JaCoCo | **0.8.14**, `LINE COVEREDRATIO ≥ 0.80` | 8 | `pom.xml` `jacoco-maven-plugin` |
| OWASP dependency-check | **12.1.0**, `failBuildOnCVSS = 7` | 8 | `pom.xml` `dependency-check-maven` |
| Surefire (unit) | **3.5.2** | 2, 6, 8 | `pom.xml` `maven-surefire-plugin` |
| Failsafe (integration) | **3.5.2** (profile `integration`, `**/*IT.java`) | 1, 5, 8 | `pom.xml` `maven-failsafe-plugin` |
| Testcontainers | **2.0.3** (PostgreSQL + LocalStack) | 1, 4, 5 | `pom.xml` `testcontainers-bom` |
| PostgreSQL | **16** | 1, 4, 5 | `docker-compose.yml` / Testcontainers |
| LocalStack | S3 + SQS + SNS | 1, 5 | `docker-compose.yml` / Testcontainers |

**Build commands (match [`../README.md`](../README.md)).**

- Zero-warning build + unit tests + coverage + OWASP (Gates 2, 6, 8):

  ```
  ./mvnw clean verify
  ```

- Integration / end-to-end tests against Testcontainers + LocalStack (Gates 1, 4, 5, 8):

  ```
  ./mvnw verify -Pintegration
  ```

> No command in this document embeds credentials. AWS access for local runs is satisfied by LocalStack
> and environment-provided variables; secrets are never hardcoded (AAP §0.8.1).

---

## 5. References

- **AAP §0.7.2** — the eight validation gates (binding source for this document).
- **AAP §0.8.7** — build & quality rules: zero-warning build, ≥ 80 % line coverage, OWASP zero
  critical/high, Gate-6 unsafe-code threshold (50, per-site justification above).
- **AAP §0.2** — verified source inventory (Gate 7 source-scale table).
- **AAP §0.7.3** — Explainability: decision log + 100 % bidirectional traceability (Gate 8).
- **AAP §0.3.2 / §0.7.8** — scope boundaries: AWS-via-LocalStack only; **GCP / Environment 2 is out of
  scope**; no feature expansion beyond F-001 … F-022.
- **Source baseline** — AWS CardDemo `CardDemo_v1.0-15-g27d6c6f-68`, commit SHA **`27d6c6f`**; COBOL
  sources are referenced, **not** copied.
- **Companion documents** — [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md),
  [`../DECISION_LOG.md`](../DECISION_LOG.md), [`api-contracts.md`](api-contracts.md),
  [`../README.md`](../README.md).

