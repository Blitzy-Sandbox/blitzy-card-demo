# CardDemo Java Migration — Decision Log

> **Status:** Living document (updated as decisions are made or revised)
> **Scope:** Migration of the AWS **CardDemo** COBOL/CICS/VSAM/JCL mainframe application to a Java 25 LTS + Spring Boot 3.x cloud-native application (`carddemo-java/`).
> **Source baseline:** original COBOL repository commit **`27d6c6f`** (version string `CardDemo_v1.0-15-g27d6c6f-68`).
> **License:** Apache License 2.0, inherited from the upstream CardDemo project.

## 1. Purpose and Scope

This Decision Log is the **single source of truth** for every non-trivial engineering decision taken while migrating CardDemo from the mainframe (COBOL programs, copybooks, VSAM datasets, JCL job streams, BMS screens, CICS) to the Java/Spring/PostgreSQL/AWS target.

It exists to satisfy the project's **Explainability rule**: decision rationale is recorded **here**, in one auditable place, and is deliberately **not** duplicated into source-code comments. When a reader asks "why was it built this way?", the answer lives in this file (and, for the COBOL-to-Java mapping itself, in [`TRACEABILITY_MATRIX.md`](./TRACEABILITY_MATRIX.md)).

The migration is governed by four standing invariants that constrain every decision below:

1. **100% behavioral parity** — each COBOL paragraph produces identical output for identical input; zero behavioral regression.
2. **External interface contracts are byte-preserved** — file layouts, record lengths, delimiters, message schemas, and batch triggers are unchanged.
3. **No hardcoded credentials** — all secrets resolve from environment variables or a vault; the legacy plaintext password is hardened (see D-002).
4. **No feature expansion** — only the closed set of features **F-001 … F-022** is migrated; no new endpoints, entities, or business rules are introduced.

## 2. How to Use This Log

- Every decision has a stable identifier **`D-NNN`** that may be referenced from pull requests, the traceability matrix, and the validation gates.
- Decisions D-001 … D-005 are the canonical decisions called out in the migration plan; D-006 onward record the remaining cross-cutting choices.
- All decisions are **Accepted** unless a later revision explicitly supersedes them; superseding entries reference the identifier they replace.
- Each row carries concrete **Alternatives** that were considered and the **Risks** retained after the decision — never placeholders.

## 3. Decision Register

| # | Decision | Alternatives | Rationale | Risks |
|---|---|---|---|---|
| D-001 | Use `java.math.BigDecimal` for every `COMP-3`/`COMP` field derived from a PIC clause with decimal positions (e.g., the five `PIC S9(10)V99` balances in `CVACT01Y`). | `double`/`float` floating-point; a scaled `long` holding integer cents. | Preserves exact COBOL fixed-point precision and scale with zero floating-point drift; `BigDecimal` scale mirrors the PIC clause (scale 2 for `V99`). | Object allocation and arithmetic overhead versus primitives; bounded by restricting `BigDecimal` to monetary/financial fields only. |
| D-002 | Hash the `USRSEC` password (`SEC-USR-PWD`) with **BCrypt** via Spring Security. | Retain plaintext (status quo, constraint C-003); Argon2id; PBKDF2. | Adaptive, salted, widely vetted default that remediates the plaintext-password weakness while preserving the existing sign-on flow. | Legacy stored passwords are plaintext and are migrated via hash-on-first-successful-login; BCrypt's 72-byte input limit easily accommodates the `PIC X(08)` password. |
| D-003 | Replace JCL generation data groups (GDG) with **AWS S3 versioned objects**. | Local filesystem generation rotation; database LOB columns. | S3 object versioning is the native cloud equivalent of GDG generation/retention semantics and unifies batch staging plus statement/report output. | Any test touching storage requires LocalStack (or real S3); LocalStack needs path-style access configured. |
| D-004 | Replace the CICS transient-data-queue (TDQ) report-submission bridge (`WRITEQ TD` in `CORPT00C`) with an **SQS FIFO queue**. The FIFO message-grouping and deduplication strategy is recorded separately in **D-020**. | In-memory queue; Apache Kafka; RabbitMQ. | FIFO provides the identical point-to-point, ordered delivery guarantee of a TDQ with the smallest operational surface. | SQS FIFO throughput cap (~300 msg/s without batching) — sufficient for CardDemo's online-to-batch report volume. |
| D-005 | Re-host the JCL job streams (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) on **Spring Batch**. | Quartz scheduler; Temporal workflows; a bespoke runner. | Native Step/Job/Flow sequencing plus `ExitStatus`/`JobExecutionDecider` map directly onto JCL step ordering and `COND` condition-code logic. | Step/Job/Flow programming-model learning curve for COBOL/JCL-oriented maintainers. |
| D-006 | Wrap the dual ACCTDAT+CUSTDAT update in `@Transactional` with **rollback-on-exception**. | Manual JDBC commit/rollback; programmatic `TransactionTemplate`. | Declarative equivalent of the system's sole `SYNCPOINT ROLLBACK` (in `COACTUPC`); guarantees the two-record update is atomic. | Spring rolls back only on unchecked exceptions by default — checked domain exceptions must be declared via `rollbackFor` to match COBOL abort semantics. |
| D-007 | Apply JPA `@Version` **optimistic locking** on `Account` and `Card`. | Pessimistic `SELECT ... FOR UPDATE`; no concurrency control. | Reproduces the COBOL before/after record-image comparison (READ-for-UPDATE) in `COACTUPC` and `COCRDUPC` without holding database locks. | Concurrent edits raise `OptimisticLockException`, which must be translated into the COBOL "record changed, please re-read" user message. |
| D-008 | Model a normalised **PostgreSQL** relational schema (11 tables) rather than a 1:1 VSAM byte-copy; provision and seed it with **Flyway** migrations `V1`→`V2`→`V3`. | A 1:1 flat VSAM-layout copy; a NoSQL document store. | A relational schema enables indexed/keyed access, referential integrity, and SQL tooling while preserving every field and record length; versioned Flyway migrations are deterministic and ordered. | Schema drift between SQL migrations and JPA entities — mitigated by Flyway validation on startup and repository integration tests. |
| D-009 | Replace the `CARDDEMO-COMMAREA` conversational state with **stateless HTTP + JWT claims + request/response DTOs**. | Server-side `HttpSession`; sticky-session conversational state. | Removes server-held pseudo-conversational state, enabling horizontal scaling; DTOs mirror the BMS symbolic-map fields as the REST contract. | COMMAREA fields that implicitly carried flow state must be re-expressed as explicit request parameters or JWT claims; token expiry/refresh is handled client-side. |
| D-010 | Map every COBOL **FILE STATUS** code to a custom exception hierarchy plus status enums. | Return-code integers threaded through signatures; a single generic `RuntimeException`. | Idiomatic, type-safe error handling — e.g., `23`→`RecordNotFoundException`, `22`→`DuplicateRecordException`, `00`→success — preserving the six FILE STATUS clauses of `CBTRN02C`. | COBOL implicit paragraph fall-through after a file error must be re-expressed as explicit catch/branch chains so no error path is silently skipped. |
| D-011 | Use `RoundingMode.HALF_EVEN` for decimal arithmetic and `compareTo()` (never `equals()`) for decimal comparison. | `HALF_UP` or other rounding modes; `BigDecimal.equals()`. | HALF_EVEN ("banker's rounding") reproduces the interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`; `compareTo()` is scale-insensitive whereas `equals()` is not. | Calling `equals()` on `BigDecimal` (e.g., `2.0` vs `2.00`) yields false negatives — guarded by code review and unit tests. |
| D-012 | Replace `COMBTRAN`'s `DFSORT` SORT + IDCAMS `REPRO` with a Java `Comparator` sort followed by **bulk JPA insert**. | Database `ORDER BY` staging; an external sort library. | A `Comparator` matching the SORT FIELDS spec preserves key order, ascending/descending, and duplicate handling; bulk insert replaces the REPRO copy. | Very large daily volumes could exceed heap if sorted fully in memory — chunked/streamed sorting is the documented mitigation if data grows. |
| D-013 | Pin the runtime baseline to **Java 25 LTS** + **Spring Boot 3.5.15** (full pinned stack in §5). | Spring Boot 4.0.x (now GA); an unpinned "latest" range. | 3.5.x is the verified, BOM-coherent stack compatible with Spring Cloud AWS 3.3.0 and the project's as-built baseline. | Spring Boot 3.5 OSS support ends 2026-06-30 and 4.0.x is GA — recorded as a forward-looking upgrade risk, explicitly out of scope for this migration. |
| D-014 | Validate stateless JWTs with **Spring Security OAuth2 Resource Server**. | A custom servlet filter; an opaque session-token store. | Standards-based JWT validation pairs with the stateless design (D-009) and the BCrypt-backed sign-on (D-002). | Signing-key management and rotation must be configured via environment/vault, never hardcoded. |
| D-015 | Ship **observability** with the initial build: structured JSON logging (`logstash-logback-encoder`) with correlation IDs, Micrometer Tracing + OpenTelemetry → Jaeger, and Micrometer → Prometheus metrics. | Plain-text logging; no tracing; ad-hoc metrics; deferring observability until after launch. | The Observability rule requires logging, tracing, metrics, and health checks to ship together and be verifiable locally; the COBOL source provided none. | Tracing and metrics add minor runtime overhead and require the Jaeger/Prometheus/Grafana services in `docker-compose.yml` for local verification. |
| D-016 | Exercise all AWS (S3/SQS/SNS) interactions against **LocalStack** in dev and test (Testcontainers + Docker Compose) with **zero live-AWS dependency**. | A real AWS account; mocking the AWS SDK clients only. | The LocalStack Verification rule mandates self-provisioning AWS tests without live credentials; mocks alone cannot verify the real wire contracts (Gate 5). | LocalStack PRO licensing — the plan references `localstack-pro:latest` with `LOCALSTACK_AUTH_TOKEN`; where a PRO token is unavailable, the Community image (e.g., `localstack:3.8`) covers S3/SQS/SNS and is the documented fallback. |
| D-017 | Do **not** copy COBOL/JCL/copybook sources into the target; establish traceability by referencing source commit `27d6c6f`. | Vendoring the COBOL alongside the Java; embedding COBOL as comments. | The user preservation mandate calls for a clean greenfield Java project; traceability is provided by [`TRACEABILITY_MATRIX.md`](./TRACEABILITY_MATRIX.md) keyed to the commit SHA. | Maintainers must consult the original repository at `27d6c6f` for source detail — mitigated by the bidirectional traceability matrix. |
| D-018 | Express composite keys with JPA `@EmbeddedId` (and `@IdClass` where more ergonomic) for the composite-keyed records `CVTRA01Y`/`CVTRA02Y`/`CVTRA04Y`. | Surrogate auto-increment primary keys; concatenated string keys. | Preserves the natural composite keys (category-balance, disclosure-group, transaction-category) exactly as in VSAM. | Composite-key `equals`/`hashCode` must be correct or JPA identity and caching break — covered by entity unit tests. |
| D-019 | Treat **Environment 2 (GCP via `localgcp`)** as out of scope; **Environment 1 (AWS via LocalStack)** is authoritative. | Adopting GCP emulators (Cloud Storage/Pub-Sub/Firestore); dual-cloud support. | The GCP stack is technology-incompatible (Node.js `@google-cloud/storage` v7, Pub/Sub, Firestore) with the Java/AWS target, and adopting it would violate the no-feature-expansion mandate. | None for this migration; the exclusion is recorded here so the second attached environment is not mistaken for in-scope work. |
| D-020 | On the SQS FIFO report-submission queue (see D-004), publish each report-job message with a **constant message group id** (`carddemo-reports`) and a **per-message random UUID deduplication id**, leaving content-based deduplication **disabled**. Implemented in `ReportSubmissionService.publishReportJob`. | Enable content-based deduplication (SHA-256 of the body); a deterministic dedup id derived from the report type + date range; multiple message group ids (one per report type). | The source TDQ (`CORPT00C` `WRITEQ TD QUEUE('JOBS')`) is a single, totally-ordered, point-to-point stream, so **one** constant group id reproduces that exact ordering. A **random** dedup id makes every submission unique so that legitimately repeated requests — e.g., the same user re-running the same Monthly report inside the 5-minute FIFO dedup window — are each enqueued and processed, exactly as every COBOL `WRITEQ TD` enqueued a distinct job. Content-based or deterministic dedup would silently collapse such repeats and drop a valid job, breaking 100% behavioral parity (invariant 1). This **refines D-004**: FIFO's exactly-once *ordering* is retained, but exactly-once *deduplication* is deliberately not used. | Effectively at-least-once within the dedup window: an SDK/client retry of the same publish call yields two jobs — this matches the source (`WRITEQ TD` had no dedup), and downstream report output is keyed by deterministic S3 object names so a duplicate run overwrites rather than corrupts. The single group id serializes all report submissions (no parallel FIFO ordering), bounded by CardDemo's low online-to-batch report volume (see D-004 throughput note). |

## 4. Environment Resolution

Two execution environments were attached to this project. Their technologies diverge, so this log records the resolution explicitly (see D-019).

- **Environment 1 — AWS via LocalStack (authoritative).** Provisions the AWS CLI and LocalStack, authenticates with `LOCALSTACK_AUTH_TOKEN`, starts LocalStack, and validates S3 (`aws s3 mb …`). This environment directly matches the migration's AWS target and the LocalStack Verification rule, and is used for all S3/SQS/SNS testing. No real AWS credentials are required.
- **Environment 2 — GCP via `localgcp` (out of scope).** Provisions the `localgcp` emulator and relies on the Node.js `@google-cloud/storage` v7 SDK (with a `STORAGE_EMULATOR_HOST` derivation workaround), plus Pub/Sub and Firestore emulators. This stack uses a different language runtime and cloud provider than the Java/AWS migration target; adopting it would expand scope beyond the COBOL source's behavior. It is therefore excluded, and this entry is its traceable record.

## 5. Version Pinning and Forward-Looking Risk

The dependency stack is pinned to a verified, BOM-coherent set (see D-013). These are the authoritative versions for the migration:

| Component | Pinned version | Note |
|---|---|---|
| Java (LTS) | 25 | Target runtime. |
| Spring Boot | 3.5.15 | BOM anchor; as-built baseline 3.5.11. |
| Spring Cloud AWS (`io.awspring.cloud`) | 3.3.0 | S3/SQS/SNS starters compatible with Spring Boot 3.5.x. |
| PostgreSQL | 16.x | Relational target for the 11 VSAM datasets. |
| Flyway | 11.x | Schema/seed migrations `V1`→`V2`→`V3`. |
| Testcontainers | 2.0.3 | 2.x renames modules with a `testcontainers-` prefix; BOM-managed. |
| JaCoCo | 0.8.14 | ≥80% line-coverage enforcement (Gate 8). |
| OWASP `dependency-check-maven` | 12.1.0 | Zero critical/high CVE gate (Gate 8). |

**Forward-looking risk (documented, out of scope):** Spring Boot 3.5's OSS support window ends **2026-06-30** and Spring Boot **4.0.x** is now generally available; Spring Cloud AWS `4.0.0-M1` and Testcontainers 2.0.5 likewise target the newer line. A future upgrade to the 4.x stack is anticipated but is **not** part of this migration's closed scope and must be planned as separate work.

## 6. Cross-References

- [`TRACEABILITY_MATRIX.md`](./TRACEABILITY_MATRIX.md) — bidirectional COBOL paragraph ↔ Java class/method mapping with 100% coverage, keyed to commit `27d6c6f`. Decisions here explain *why*; the matrix records *what maps to what*.
- [`docs/validation-gates.md`](./docs/validation-gates.md) — evidence for validation Gates 1–8. Several decisions are validated there: D-001/D-011 (decimal fidelity, Gate 1/5), D-013/D-016 (Gate 8 dependency and CVE checks), D-005/D-012 (batch pipeline, Gate 1/3).
- [`docs/architecture-before-after.md`](./docs/architecture-before-after.md) — Mermaid before/after diagrams visualising D-003, D-004, D-008, and D-009.
- [`README.md`](./README.md) and [`docs/onboarding-guide.md`](./docs/onboarding-guide.md) — build/run/onboarding context for the decisions above.

## 7. References

- **Source repository commit:** `27d6c6f` (`CardDemo_v1.0-15-g27d6c6f-68`) — the COBOL baseline against which behavioral parity is measured. COBOL sources are referenced, not copied (D-017).
- **Migration plan sections:** decision rationale derives from §0.7.3 (decision log and traceability), §0.7.8 (environment resolution), and the binding rules in §0.8 (preservation, decimal precision, control flow, transactions/concurrency, batch pipeline, build/quality).
- **Feature scope:** the closed set **F-001 … F-022**; no feature expansion beyond it.
- **Licensing:** this document and the `carddemo-java/` project are released under the **Apache License 2.0**, consistent with the upstream CardDemo project. No secrets, tokens, or credentials are recorded in this log.
