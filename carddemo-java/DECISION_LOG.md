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
  **D-006** through **D-015** record the cross-cutting decisions that flow from them;
  **D-016** onward record the parity- and quality-fidelity decisions surfaced during the
  checkpoint code-review remediation.
- Where a decision needs more than a table cell allows (dependency pinning, the
  attached-environment resolution, and the most subtle remediation couplings), an expanded
  subsection follows the table.
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
| **D-016** | In the daily-transaction posting validation cascade, evaluate the over-limit check (reject `102`) and the after-expiration check (reject `103`) as **sequential, non-exclusive** checks within one account-lookup step, so that when a transaction fails **both**, the final reject reason is **`103` (after-expiration), which overwrites `102`**. | Make 102/103 mutually exclusive (first-failure-wins); raise a combined/aggregate reject code; reorder the checks. | Preserves the exact behaviour of `CBTRN02C.1500-B-LOOKUP-ACCT`, whose over-limit `IF` and expiration `IF` are two consecutive `IF/ELSE/END-IF` blocks with **no** intervening `EXIT` — so the expiration `MOVE 103` overwrites a previously set `MOVE 102`. The closed reject set is exactly `{100, 101, 102, 103, 109}` (100 = card cross-reference miss, 101 = account miss, 109 = account-rewrite invalid key). | A maintainer might "tidy" this into mutually-exclusive checks and silently change which reject code a doubly-invalid transaction receives; mitigated by a unit test asserting the 103-overwrites-102 ordering and by this entry. |
| **D-017** | At sign-on, **upper-case both the entered user id and the entered password** before the credential lookup and BCrypt verification; consequently, the seed migration that loads `user_security` **must store BCrypt hashes computed over the upper-cased plaintext**. | Upper-case only the user id (case-sensitive password); preserve original password case end-to-end; case-fold at hash time only. | Faithfully reproduces `COSGN00C`, which applies `FUNCTION UPPER-CASE` to **both** `USERIDI` and `PASSWDI` and then compares the stored `SEC-USR-PWD` against the upper-cased input — i.e. the effective password space is upper-case-only. The Java `AuthenticationService` mirrors this (`toUpperCase(Locale.ROOT)` on id and password, then `passwordEncoder.matches(...)`), which fixes the binding rule that the seed hashes must be derived from the **upper-cased** password or every login fails. | The seed data (`V3__seed_data.sql`) is a downstream (CP4) deliverable; if its hashes are computed over the original-case password, all logins break. Mitigated by recording the dependency here and by the authentication unit tests that exercise success/not-found/wrong-password against upper-cased credentials. |
| **D-018** | Define **one authoritative posting contract**: `TransactionPostingProcessor` is a **pure** function emitting a typed `PostingResult` (posted state *or* reject), and `TransactionWriter` consumes that `PostingResult` and **persists the precomputed state exactly once** (no re-read, no recomputation), routing rejects to `RejectWriter`. Split metric ownership so the **writers** own all batch counters. | Have the writer re-read and re-apply balances; keep two divergent record types bridged by an ad-hoc adapter; let both processor and writer emit metrics. | Eliminates the processor/writer type mismatch and the **double-application** risk (the writer no longer re-reads the account/category balance and re-adds the amount). Persisting the processor's `updatedAccount`/`updatedCategoryBalance`/`postedTransaction` once preserves `CBTRN02C` semantics (`2700/2800/2900`) without recomputation. Concurrency conflicts surface the canonical, account-id-free message `Record changed by some one else. Please review` (consistent with `COACTUPC`/`COCRDUPC`, D-007). Writer-owned metrics (`carddemo.batch.records.processed`, `carddemo.transaction.amount.total`) and `RejectWriter`-owned `carddemo.batch.records.rejected` prevent double-counting and divergent tag series. | The job assembly (`DailyTransactionPostingJob`) is a CP4 deliverable; the aligned contract is now wireable but unproven end-to-end until that job exists. Mitigated by a `TransactionWriter` unit test asserting persist-exactly-once, reject routing, and the canonical concurrency message. |
| **D-019** | Treat a missing cross-reference, transaction-type, or transaction-category lookup during transaction-report processing as a **fatal** error (`RecordNotFoundException`, `FILE STATUS '23'`) rather than emitting a report row with null/blank fields. | Emit the row with blank/placeholder descriptions (the prior lenient behaviour); skip the offending record silently; substitute a default code. | Reproduces `CBTRN03C` paragraphs `1500-A/B/C-LOOKUP-*`, each of which on `INVALID KEY` performs `9999-ABEND-PROGRAM` (`CALL 'CEE3ABD'`, abend code 999) — a hard, fatal stop, not a lenient continue. The card number in the cross-reference diagnostic is **masked to its last four digits** so no full PAN reaches a log or message; the non-sensitive type/category reference codes remain visible for diagnosis. | A fatal report path can halt a batch run on dirty reference data; this is intended parity, and the masked diagnostics make the failing key identifiable without leaking a PAN. |
| **D-020** | In online transaction-add, run **key-field validation and cross-reference resolution before data-field validation**, using **account-id-first** precedence: resolve account→card via `findByXrefAcctId` (the `CXACAIX` path) or card→account via `findById` (the `CCXREF` path), then persist only the **resolved** card/account pairing. | Validate data fields first; trust the client-supplied card number without resolution; accept either key without populating its complement. | Reproduces `COTRN02C.VALIDATE-INPUT-KEY-FIELDS` (which runs before `VALIDATE-INPUT-DATA-FIELDS`) and its `EVALUATE TRUE` that prefers the account id, reads the cross-reference, and moves the complementary key. Resolving before persisting prevents creating transactions for non-existent or mismatched account/card pairs; a missing key raises `RecordNotFoundException` with the COBOL-equivalent "Account ID NOT found"/"Card Number NOT found" messages. | Adds a cross-reference repository dependency to the add path; mitigated by unit tests covering account-first resolution, card-only adoption, both-missing, ordering, and not-found cases. |
| **D-021** | Query the transaction date range with **full-timestamp string bounds** — `startDate` as the lower bound and `endDate + "-99.99.99.999999"` as the (asymmetric) upper bound — and add a dedicated `idx_tran_card_num` index for per-card access. | Predicate on `SUBSTRING(tran_proc_ts, 1, 10)`; use symmetric `…-00.00.00.000000`/`…-23.59.59.999999` bounds; scan the full table per card. | A `SUBSTRING(...)` predicate cannot use a plain B-tree index, defeating `idx_tran_proc_ts`; full-value range bounds keep the index usable. The bound is **asymmetric** because the seed/runtime data mixes timestamp formats (dash-dot, space-colon, and date-only), and a symmetric lower bound would wrongly exclude date-only values; the 26-character upper bound matches the `VARCHAR(26)` column. `idx_tran_card_num` backs the targeted per-card query that replaced a full-table `findAll().stream().filter(...)` in statement generation. | The asymmetric bound is subtle; mitigated by this entry and by repository tests. Index maintenance cost is negligible relative to the avoided full scans. |
| **D-022** | **Exclude the transitive `commons-logging:commons-logging` artifact** from all three `io.awspring.cloud` AWS starters (S3/SQS/SNS). | Leave it on the classpath; switch the AWS SDK HTTP transport to avoid Apache HttpClient; add a JVM flag to silence the warning. | The AWS SDK's Apache HTTP client pulls in `commons-logging`, which triggers the Spring "Standard Commons Logging discovery … please remove commons-logging.jar" warning and violates the zero-warning build intent (Gate 2). `spring-jcl` already bridges Jakarta Commons Logging to SLF4J, so removing the JAR loses no logging while clearing the warning. | None observed; logging continues through `spring-jcl`→SLF4J. Verified by a clean `dependency:tree` (no `commons-logging`) and a warning-free test run. |

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

## Checkpoint remediation couplings (expands D-016, D-017, D-018)

Three of the remediation decisions encode couplings subtle enough that a future maintainer
could unknowingly break parity; they are elaborated here.

- **The 103-overwrites-102 reject ordering (D-016).** In `CBTRN02C.1500-B-LOOKUP-ACCT` the
  over-limit test and the after-expiration test are two consecutive `IF/ELSE/END-IF` blocks
  with **no** `EXIT` between them. A transaction that is simultaneously over its credit limit
  **and** received after the account expiration date therefore has its reason set to `102`
  and then immediately **overwritten** to `103`. The Java cascade reproduces this exact order,
  and the closed reject set remains `{100, 101, 102, 103, 109}`. "Tidying" the two checks into a
  mutually-exclusive `else-if` would silently change the reject code for doubly-invalid records.

- **Upper-cased credentials and the seed-hash dependency (D-017).** `COSGN00C` upper-cases
  **both** the user id and the password before lookup and comparison, so the effective password
  alphabet is upper-case-only. The Java `AuthenticationService` upper-cases both inputs and then
  calls `passwordEncoder.matches(...)`. The binding consequence — recorded here because the
  affected file does not yet exist — is that the seed migration `V3__seed_data.sql` (a CP4
  deliverable) **must compute its BCrypt hashes over the upper-cased plaintext**. If the seed
  hashes are generated from the original-case password, `matches(...)` will fail for every user.

- **The single posting contract and metric ownership (D-018).** `TransactionPostingProcessor`
  is deliberately a **pure** function: it computes the posted `Transaction`, `Account`, and
  `TransactionCategoryBalance` (or a reject) and emits **no** metrics. `TransactionWriter` is the
  **sole** persister — it saves that precomputed state exactly once and never re-reads or
  re-applies balances — and routes rejects to `RejectWriter`. Consequently all batch counters are
  owned by the writers (`TransactionWriter` → `carddemo.batch.records.processed` and
  `carddemo.transaction.amount.total`; `RejectWriter` → `carddemo.batch.records.rejected`). Moving
  any persistence or metric back into the processor reintroduces the double-application and
  divergent-tag-series defects this decision removed.

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
  tests; D-012 (pinned versions) and D-022 (the `commons-logging` exclusion) underpin the
  zero-warning build and OWASP gates. The checkpoint-remediation decisions are each backed by
  a unit or integration test: D-016 (the 103-overwrites-102 ordering) and D-018 (single posting
  contract, persist-exactly-once, writer-owned metrics) by the posting processor/writer tests;
  D-017 (upper-cased credentials) by the authentication-outcome tests; D-019 (fail-fast report
  lookups with masked PAN) by the transaction-report-processor tests; D-020 (key-field
  resolution precedence) by the transaction-add tests; and D-021 (full-timestamp range bounds
  plus `idx_tran_card_num`) by the repository and statement-processor tests.

## License

This document is part of the CardDemo Java migration and is distributed under the
**Apache License 2.0**, consistent with the upstream project
(Copyright Amazon.com, Inc. or its affiliates). No credentials, tokens, or secrets are
recorded in this log.
