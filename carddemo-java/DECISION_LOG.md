# Decision Log — CardDemo COBOL→Java Migration

> **Purpose.** This file is the **single source of truth** for every non-trivial
> engineering decision made while migrating the AWS CardDemo mainframe application
> (COBOL / CICS / VSAM / JCL / BMS) to a Java 25 + Spring Boot 3.x service. Per the
> project **Explainability rule**, decision *rationale lives here* — it is **not**
> duplicated into source-code comments. Code references this log by decision ID
> (for example, `D-001`) rather than restating the reasoning inline.

## Scope and provenance

- **Subject system:** AWS CardDemo credit-card management reference workload.
- **Migration target:** the standalone greenfield Java project under `carddemo-java/`.
- **Source of record:** the original COBOL repository at commit SHA **`27d6c6f`**
  (version string `CardDemo_v1.0-15-g27d6c6f-68`). COBOL sources are **not** copied
  into the Java target; traceability is preserved by referencing this SHA. Every
  decision below is grounded in the behaviour of that exact source revision.
- **Closed feature set:** only features **F-001 through F-022** are migrated.
  No decision in this log introduces new business features, endpoints, entities,
  or rules beyond that closed set (no-feature-expansion mandate).
- **Binding constraints referenced:** **C-003** (the plaintext `SEC-USR-PWD`
  field in `CSUSR01Y.cpy` must be hardened without breaking the login flow).

## How to read this log

- Each decision has a stable identifier `D-NNN` that is permanent once assigned.
- The registry table uses exactly five columns: **#**, **Decision**,
  **Alternatives**, **Rationale**, and **Risks**.
- Decisions **D-001** through **D-005** are the canonical, platform-defining choices.
  **D-006** onward record the cross-cutting decisions that flow from them.
- Where a decision needs more than a table cell allows (dependency pinning and the
  attached-environment resolution), an expanded subsection follows the table.
- COBOL→Java member-level mappings live in
  [`TRACEABILITY_MATRIX.md`](./TRACEABILITY_MATRIX.md); validation evidence lives in
  [`docs/validation-gates.md`](./docs/validation-gates.md). This log links to both
  rather than duplicating their content.

## Decision registry

| # | Decision | Alternatives | Rationale | Risks |
|---|----------|--------------|-----------|-------|
| **D-001** | Use `java.math.BigDecimal` for all `COMP-3` / `COMP` numeric fields originating from a PIC clause with decimal positions. | `double`; `float`; `long` scaled fixed-point. | Preserves exact COBOL PIC decimal precision with zero floating-point substitution. Scale matches the PIC clause (`PIC S9(10)V99` → scale 2); the interest computation `( TRAN-CAT-BAL * DIS-INT-RATE) / 1200` is reproduced with `BigDecimal.divide()` and `RoundingMode.HALF_EVEN`; numeric comparisons use `compareTo()`, never `equals()`. | Arithmetic overhead and verbosity versus primitives; mitigated by bounding `BigDecimal` to financial/decimal fields only. |
| **D-002** | Hash credentials with **BCrypt** (Spring Security `BCryptPasswordEncoder`). | Keep plaintext (as in source); Argon2; PBKDF2. | Provides a secure, well-supported default that preserves the existing sign-on flow while eliminating the plaintext `SEC-USR-PWD` field (constraint **C-003**). | Existing seeded credentials are plaintext; mitigated by a one-time **hash-on-first-login** migration so the login contract is unchanged. |
| **D-003** | Stage generation datasets (GDG) and batch output as **versioned objects in Amazon S3**. | Local filesystem generation rotation; database LOB columns. | Object versioning is the native cloud equivalent of z/OS GDG generation/retention semantics, and matches the file-staging role of the batch jobs. | Requires LocalStack to exercise S3 in local and integration tests (no live AWS); mitigated by the LocalStack Verification workflow. |
| **D-004** | Replace the CICS transient-data queue (TDQ `WRITEQ TD`) with an **SQS FIFO queue** for the report-submission online-to-batch bridge. | In-memory queue; Apache Kafka; RabbitMQ. | A FIFO queue gives the same point-to-point, ordered, exactly-once delivery guarantee as the TDQ contract, with a managed-service operational model. | FIFO throughput is capped (~300 msg/s without batching); accepted because report-submission volume is far below the cap. |
| **D-005** | Re-host JCL job streams as **Spring Batch** jobs and steps. | Quartz scheduler; Temporal workflows; a bespoke scheduler. | Spring Batch natively expresses step sequencing, restartability, chunk-oriented processing, and JCL `COND` condition-code logic (via `ExitStatus` and `JobExecutionDecider`) for the 5-stage pipeline. | Step/Job/Flow model has a learning curve for maintainers; mitigated by the onboarding guide and consistent job structure. |
| **D-006** | Apply `@Transactional` with rollback-on-exception for the dual `ACCTDAT` + `CUSTDAT` update. | Manual JDBC commit/rollback; programmatic `TransactionTemplate`. | Declaratively reproduces the system's sole `SYNCPOINT ROLLBACK` (in `COACTUPC`, the account-update program) so both records commit or neither does. | Spring rolls back only on unchecked exceptions by default; mitigated by declaring `rollbackFor` for the mapped checked exceptions. |
| **D-007** | Use JPA **`@Version`** optimistic locking on `Account` and `Card`. | Pessimistic DB locks (`SELECT … FOR UPDATE`); no concurrency control. | Reproduces the before/after record-image comparison performed by `COACTUPC` and `COCRDUPC` without holding database locks across a stateless request. | Concurrent edits raise `OptimisticLockException`; mitigated by translating it to a deterministic user-facing "record changed, retry" response in the service layer. |
| **D-008** | Model the 11 VSAM datasets as a **normalized PostgreSQL relational schema** provisioned by **Flyway** (`V1` schema → `V2` indexes → `V3` seed). | A 1:1 flat VSAM-style copy; a NoSQL document store. | A relational model gives referential integrity, indexed/keyed access, and pagination idiomatically via Spring Data JPA; ordered Flyway migrations make provisioning and the 9-fixture seed deterministic and repeatable. | Layout drift between VSAM records and normalized tables must stay traceable; mitigated by recording every field mapping in `TRACEABILITY_MATRIX.md`. |
| **D-009** | Replace `CARDDEMO-COMMAREA` conversational state with **stateless JWT claims plus request/response DTOs**. | Server-side HTTP session; emulating COMMAREA with sticky sessions. | Removes server-held conversational state so the service scales horizontally; user identity and type travel as JWT claims, and BMS symbolic-map fields become explicit DTO contracts. | Token lifecycle (expiry, refresh, revocation) is a new concern absent from the pseudo-conversational model; mitigated by standard resource-server validation and short-lived tokens. |
| **D-010** | Replace `DFSORT` SORT + IDCAMS `REPRO` with an in-JVM **`Comparator`** followed by **bulk JPA insert**. | Retain a DFSORT/native sort invocation; shell out to an external sort utility. | A `Comparator` matching the JCL `SORT FIELDS` spec (key order, ascending/descending, duplicate handling) preserves sort semantics in-process, and bulk insert replaces the `REPRO` copy step. | In-memory sort is sensitive to daily transaction volume; mitigated by monitoring heap and falling back to chunked/external sort if volumes grow. |
| **D-011** | Provide a **`java.time.LocalDate`** validation service in place of Language Environment `CEEDAYS`. | Retain `CEEDAYS` via JNI/native bridge; adopt a third-party date library. | A JDK-native service (replacing `CSUTLDTC`) removes the native runtime dependency while preserving the date-validation contract used by online and batch flows. | Edge-case parity (e.g., Lilian day numbering, era boundaries) must be proven; mitigated by targeted unit tests against the original behaviour. |
| **D-012** | **Pin** all build/runtime dependency versions rather than using floating ranges. | Floating version ranges; always-latest resolution. | Reproducible, audited builds against verified-compatible releases (see *Dependency version pinning* below) keep the zero-warning build and OWASP gates stable. | The 3.5 line reaches end-of-OSS-support 2026-06-30 and Spring Boot 4.0.x is GA — a documented forward-looking upgrade risk that is **out of scope** for this migration. |
| **D-013** | Treat **Environment 1 (AWS via LocalStack) as authoritative** and exclude **Environment 2 (GCP via `localgcp`)**. | Adopt the GCP emulator stack; attempt dual-cloud support. | The migration target is Java/AWS; the GCP stack (Node.js `@google-cloud/storage` v7, Pub/Sub, Firestore) is technology-incompatible, and adopting it would constitute feature expansion in violation of the no-expansion mandate. | None for this migration; the GCP path is recorded for traceability only (see *Attached environment resolution* below). |
| **D-014** | Map the three composite-keyed VSAM records to JPA **`@EmbeddedId`** composite-key classes: `transaction_category_balance` (`CVTRA01Y` → `TransactionCategoryBalanceId`, 3 columns), `disclosure_group` (`CVTRA02Y` → `DisclosureGroupId`, 3 columns), and `transaction_category` (`CVTRA04Y` → `TransactionCategoryId`, 2 columns). | JPA `@IdClass`; a synthetic single-column surrogate primary key. | `@EmbeddedId` keeps each multi-column key as one cohesive, reusable value object that mirrors the COBOL key group exactly and gives type-safe `findById(keyObject)` access, without repeating every key field across both the entity and a separate id class as `@IdClass` requires. No surrogate key is introduced, so the original VSAM key contract (and its natural ordering) is preserved. | Embedded-key classes must implement `equals`/`hashCode` over all key columns or JPA identity breaks; mitigated by dedicated unit tests (`TransactionCategoryBalanceIdTest`, `DisclosureGroupIdTest`, `TransactionCategoryIdTest`). |
| **D-015** | For the report-submission bridge (extends **D-004**), publish a typed JSON `ReportJobMessage` `{reportType, startDate, endDate}` to the SQS FIFO queue using a **single constant message-group id** (`carddemo-reports`) and a **random per-message deduplication id** (`UUID.randomUUID()`). | Per-report-type or per-user message-group ids; content-based deduplication; an untyped/free-text payload. | A single constant group id places every submission in one FIFO ordering group, reproducing the strict total ordering of the single CICS TDQ (reports are consumed in submission order). A random deduplication id makes every submission distinct so legitimate repeat submissions (same type and date range) are not silently dropped inside FIFO's 5-minute dedup window — faithful to the TDQ, which never collapses duplicate `WRITEQ TD` writes. The typed JSON body is an explicit, versionable contract for the batch consumer. | A single group id serializes report processing (no cross-submission parallelism); accepted because submission volume is low and ordered processing matches the source. Content-based dedup is intentionally **not** enabled so repeats are preserved. |

## Dependency version pinning (expands D-012)

Because `carddemo-java/` is greenfield, every dependency is a net-new, deliberately
pinned addition. Versions are resolved from the Spring Boot BOM where applicable and
otherwise pinned to verified, current releases.

| Component | Pinned version | Notes |
|-----------|----------------|-------|
| Spring Boot (BOM) | **3.5.15** | Latest stable 3.x at planning time; governs the web/data-jpa/batch/security/validation/actuator starters. |
| Spring Cloud AWS (`io.awspring.cloud`) | **3.3.0** | S3 / SQS / SNS starters compatible with Spring Boot 3.5.x. |
| Testcontainers (BOM) | **2.0.3** | PostgreSQL + LocalStack containers; 2.x renames modules with a `testcontainers-` prefix. |
| JaCoCo Maven plugin | **0.8.14** | Enforces the ≥80% line-coverage quality gate. |
| OWASP `dependency-check-maven` | **12.1.0** | Enforces the zero critical/high CVE quality gate. |

**Forward-looking risk (out of scope).** Spring Boot 4.0.x is generally available and
the 3.5 line reaches end-of-OSS-support on **2026-06-30**; Spring Cloud AWS `4.0.0-M1`
and Testcontainers 2.0.5 also exist. Upgrading to those lines is intentionally deferred
so this migration stays on a single verified-compatible baseline — it is recorded here
as a known future task, not a deliverable.

## Attached environment resolution (expands D-013)

The project attaches two execution environments whose technologies diverge; this is the
explicit resolution.

- **Environment 1 — AWS via LocalStack (authoritative, in scope).** Matches the
  migration's S3 / SQS / SNS target and the LocalStack Verification rule. All cloud
  interactions are exercised here with **zero live AWS dependency**; tests provision and
  tear down their own buckets, queues, and topics.
- **Environment 2 — GCP via `localgcp` (out of scope).** Relies on the Node.js
  `@google-cloud/storage` v7 SDK (with a `STORAGE_EMULATOR_HOST` derivation workaround),
  Pub/Sub, and Firestore. It is technology-incompatible with the Java/AWS target
  (different language runtime and cloud provider), and adopting it would expand scope
  beyond the COBOL source's behaviour. It is therefore **excluded**; this row preserves
  the rationale for traceability.

## Traceability and cross-references

- **Source commit:** all decisions trace back to COBOL revision **`27d6c6f`**.
- **Member-level mapping:** see [`TRACEABILITY_MATRIX.md`](./TRACEABILITY_MATRIX.md)
  for the bidirectional COBOL paragraph ↔ Java class/method mapping.
- **Validation evidence:** see [`docs/validation-gates.md`](./docs/validation-gates.md)
  for the gate-by-gate evidence that exercises these decisions (for example, the
  end-to-end posting run, the contract tests, and the coverage/CVE gates).
- **Representative links between decisions and gates:** D-001 (decimal fidelity) and
  D-006/D-007 (transaction + concurrency) are exercised by the end-to-end and contract
  gates; D-003/D-004/D-013 (S3/SQS, AWS-only) and D-015 (the SQS FIFO report-message
  contract) are exercised by the LocalStack integration tests; D-014 (composite-key
  classes) is exercised by the entity↔`V1` schema validation and the composite-key unit
  tests; D-012 (pinned versions) underpins the zero-warning build and OWASP gates.

## License

This document is part of the CardDemo Java migration and is distributed under the
**Apache License 2.0**, consistent with the upstream project
(Copyright Amazon.com, Inc. or its affiliates). No credentials, tokens, or secrets are
recorded in this log.
