# CardDemo Migration — Decision Log

This file is the **single source of truth** for every non-trivial decision made while
migrating the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS application to Java 25 LTS +
Spring Boot 3.x. It is mandated by the **Explainability** requirement of the
authoritative migration blueprint (`docs/technical-specifications.md`, §0.7.3 "Decision
Log and Traceability Requirements" and §0.8.6 cross-cutting requirements).

**How this log is used:**

- **Rationale lives here, not in code.** Per the Explainability rule, the reasoning behind
  a decision is recorded in this table and is *not* duplicated into source-code comments.
  Code comments document *what/how* at the point of a technology substitution
  (VSAM→JPA, CICS TDQ→SQS, GDG→S3, `CEEDAYS`→`java.time`, plaintext `USRSEC`→BCrypt);
  this log documents *why*.
- **Append-only.** Entries are never deleted or renumbered. Decision identifiers (`D-NNN`)
  are assigned monotonically. As implementation proceeds, **every** additional non-trivial
  decision discovered is appended here (blueprint §0.7.3: "The complete log will contain
  entries for all non-trivial decisions discovered during implementation.").
- **Traceability anchor.** The COBOL source is *not* copied into this greenfield repository;
  all traceability references the original COBOL repository by commit SHA **`27d6c6f`**
  (blueprint §0.7.2). Paragraph-level COBOL→Java mapping lives in `TRACEABILITY_MATRIX.md`;
  decision rationale lives here.

The five seed entries below (`D-001`–`D-005`) reproduce the blueprint's documented
decisions verbatim (`docs/technical-specifications.md` L911–L915). Entries `D-006` and
`D-007` record the two blueprint inconsistencies that **this** migration resolved, and the
resolved values are applied directly in `pom.xml` and the `src/` package tree. Entries
`D-008`–`D-011` record the non-trivial design decisions settled during implementation
(stateless bearer-token state, `@EmbeddedId` composite keys, `HALF_EVEN` interest rounding,
and the `ACCT-ID` → `Long`/`BIGINT` primary-key mapping). Entries `D-012`–`D-014` record the
resolutions of the QA checkpoint **FINAL 4** (AWS integration mechanics & online→batch bridge)
findings: the deliberately publish-only report bridge (`D-012`), the explicit AWS api-call
timeout customizers (`D-013`), and the provisioned-but-reserved SNS notification topic (`D-014`).

## Resolved Decisions

| # | Decision | Alternatives Considered | Rationale | Risks |
|---|---|---|---|---|
| D-001 | Use `BigDecimal` for all COMP-3/COMP fields | `double`, `long` (fixed-point) | COBOL PIC clauses define exact decimal precision; `BigDecimal` guarantees identical precision semantics | Performance overhead vs. primitives; mitigated by limiting `BigDecimal` to financial fields |
| D-002 | BCrypt for password hashing | Plaintext (preserve COBOL), Argon2, PBKDF2 | COBOL uses plaintext (C-003 constraint); BCrypt provides a secure default while maintaining login semantics — the single permitted behavioral change | Existing passwords must be migrated with a hash-on-first-login pattern |
| D-003 | S3 versioned objects for GDG | Local filesystem with rotation, PostgreSQL LOB | GDG semantics require generation numbering and retention; S3 versioning provides the native equivalent | Requires LocalStack for testing; S3 versioning costs |
| D-004 | SQS for TDQ replacement | In-memory queue, Kafka, RabbitMQ | CICS TDQ is point-to-point with sequential read; SQS FIFO provides an identical ordering guarantee | SQS FIFO has a 300 msg/sec throughput limit (sufficient for this workload) |
| D-005 | Spring Batch for JCL pipeline | Custom scheduler, Quartz, Temporal | JCL jobs are sequential batch with condition codes; Spring Batch provides native step sequencing and condition evaluation | Learning curve for Step/Job/Flow abstractions |
| D-006 | Base package `com.cardemo` (not `com.carddemo`) | `com.carddemo` (used by the blueprint's COBOL→Java import table, tech-spec L783–L799) | The authoritative target-structure diagrams (tech-spec L334 `src/main/java/com/cardemo/` and L476 `src/test/java/com/cardemo/`) use `com.cardemo`; the blueprint is internally inconsistent and a single base package must be applied uniformly across `src/`. `com.cardemo` is adopted as the `pom.xml` `<groupId>` and the root of every package. | Import-table references to `com.carddemo` (tech-spec L783–L799) must be reconciled to `com.cardemo` wherever they are consumed during code generation |
| D-007 | JaCoCo Maven plugin pinned to `0.8.14` (not `0.8.12`) | `0.8.12` (cited by the blueprint dependency table, tech-spec L766); leaving both versions unresolved | Java 25 emits class-file major version 69; JaCoCo gained **official** Java 25 support in `0.8.14` (`0.8.13` was experimental only), and versions `< 0.8.13` fail instrumentation with "Unsupported class file major version 69". The project-guide technology appendix (L520) already cites `0.8.14`. A single version is pinned in `pom.xml` as `<jacoco.version>0.8.14</jacoco.version>` so `mvn clean verify` runs JaCoCo under Java 25 without error. | None at the pinned version; `0.8.14` is the current stable release with Java 25 support |
| D-008 | **JWT-style stateless bearer token** for the pseudo-conversational state carried by the CICS `COMMAREA` | Server-side session-scoped state; sticky-session affinity | The target is stateless (no CICS pseudo-conversational region), so per-request context is carried in a compact HS256-signed bearer token rather than server memory. Implemented in `security/TokenService.java` (`HmacSHA256`, `{"alg":"HS256","typ":"JWT"}` header) and enforced statelessly in `config/SecurityConfig.java`. Resolves the §0.1.2 rule "CICS `RETURN TRANSID COMMAREA` → stateless REST with context propagation". | Signing secret must be a runtime secret ≥ 256 bits (no default; see `.env.example`); token theft risk is mitigated by short TTL and HTTPS at the edge |
| D-009 | **`@EmbeddedId`** (over `@IdClass`) for the composite-key entities `TCATBAL`, `DISCGRP`, `TRANCATG` | JPA `@IdClass` | `@EmbeddedId` keeps each composite key as one cohesive, reusable value type and reads naturally in derived queries. Implemented as `@Embeddable` key classes `key/TransactionCategoryBalanceId.java`, `key/DisclosureGroupId.java`, `key/TransactionCategoryId.java`, referenced via `@EmbeddedId` on the corresponding entities. Resolves the §0.3.3 "Composite key → `@EmbeddedId` / `@IdClass`" choice. | `@EmbeddedId` requires the key type to implement `equals`/`hashCode` and be `Serializable` (done); slightly more verbose access to individual key parts |
| D-010 | **`RoundingMode.HALF_EVEN`** (banker's rounding) for the interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` | `HALF_UP`, `DOWN`, unrounded `BigDecimal.divide` (throws on non-terminating) | HALF_EVEN reproduces the COBOL `COMPUTE … ROUNDED` half-even default and avoids systematic rounding bias across many postings. Implemented at `batch/processors/InterestCalculationProcessor.java:559` (`.divide(INTEREST_DIVISOR, MONTHLY_INTEREST_SCALE, RoundingMode.HALF_EVEN)`), preserving the formula without algebraic rearrangement (§0.7.6). Resolves the §0.7.3 decimal-precision rounding rule. | If a specific dataset proves the legacy host used a different rounding, the single constant must be revisited; covered by interest-calculation parity tests |
| D-011 | **`Long` (BIGINT)** for the `ACCT-ID` primary key (`Account.acctId`) | `String` (preserve fixed 11-digit width / leading zeros), `BigInteger`, `Integer` (too narrow) | `ACCT-ID` is `PIC 9(11)` — a purely numeric key whose maximum (99,999,999,999) fits comfortably in a 64-bit `Long` (and PostgreSQL `BIGINT`), preserving numeric ordering and keyed-access semantics. As an integer identifier it is NOT a COMP-3/COMP decimal amount, so the `BigDecimal` rule (§0.7.3) does not apply. Implemented as `@Id private Long acctId;` in `model/entity/Account.java`. | Any contract needing the zero-padded 11-digit display form must reapply left-padding at the API/DTO boundary (handled by the account DTO formatting) |
| D-012 | **Publish-only** online→batch report bridge: `ReportSubmissionService` publishes exactly one SQS FIFO message to `carddemo-report-jobs.fifo`; there is deliberately **no** in-repo `@SqsListener`/`JobLauncher` consumer closing publish→consume→launch | Add an in-repo `@SqsListener` that calls `JobLauncher.run(transactionReportJob, …)` to fully automate end-to-end; a scheduled SQS poller that launches the job | Faithful to `CORPT00C` (SHA `27d6c6f`), which issued only `EXEC CICS WRITEQ TD QUEUE('JOBS')`; the actual job pickup happened **out-of-band in JES**, never inside the COBOL program (§0.6.3). The SQS publish is the documented migrated deliverable (§0.4.1; see `D-004`). Adding a consumer that launches the job introduces behaviour beyond the technology transition — feature expansion barred by §0.7.2 and the Minimal Change Clause §0.7.1. The publish path is verified at runtime by `ReportSubmissionSqsIT` (publish→receive). Resolves QA checkpoint FINAL 4, Finding #1. | End-to-end report automation is intentionally **not** closed within this repository: an out-of-band / JES-equivalent trigger (or a future, separately-scoped consumer) must launch `transactionReportJob`. The boundary is documented here and in the `TransactionReportJob`/`AwsConfig`/`CardDemoApplication` Javadoc; the job bean remains independently launchable (orchestrator stage 4b + tests exercise it). |
| D-013 | Pin **explicit, auditable AWS SDK api-call timeouts** (`apiCallTimeout` / `apiCallAttemptTimeout`) on every auto-configured client via property-driven `AwsSyncClientCustomizer` + `AwsAsyncClientCustomizer` beans in `config/AwsConfig`, bound from `carddemo.aws.timeout.*` | Rely on AWS SDK v2 implicit HTTP-client defaults only (status quo); set timeouts via `spring.cloud.aws.*` (no such keys exist in Spring Cloud AWS 3.3.0 — `AwsProperties` exposes no timeout fields); hand-build each client bean with an override config (would duplicate auto-config and risk endpoint/region literals) | Gives the integration tier an explicit, auditable upper bound so outbound AWS calls fast-fail within a known ceiling rather than depending on implicit defaults (checkpoint FINAL 4 Phase 3 — "outbound AWS calls have timeouts configured … no indefinite hang"). These are the **only** beans in `AwsConfig`: each customizer reads the existing auto-configured `ClientOverrideConfiguration` (framework user-agent), `toBuilder()`s it, additively sets the two timeouts, and writes it back — **preserving** the user-agent. All inputs come from externalized properties (no literals), honouring §0.7.1 / §0.7.2 / §0.7.7. Non-functional resilience hardening that changes no migrated business logic. Resolves QA checkpoint FINAL 4, Finding #2. | Defaults (60s overall / 20s per-attempt) are deliberately generous so LocalStack-backed integration tests never trip them; a production workload needing tighter bounds overrides `carddemo.aws.timeout.*` per environment. |
| D-014 | Treat the `carddemo-notifications` SNS topic as **provisioned-but-reserved future infrastructure** — no production code path publishes to it | Add an `SnsTemplate` publish at a batch/service boundary (e.g. a statement-ready event); remove the topic from provisioning entirely | No in-scope COBOL program sends notifications and no documented feature (F-001–F-022) maps a COBOL→SNS publish; adding a publisher would be feature expansion barred by §0.7.2 / §0.7.1. The topic and the publish **mechanism** are nonetheless provisioned and verified at runtime by `SnsNotificationIT` (publish→subscriber fan-out) per the checkpoint scope. Removing the topic would contradict the documented target tree (`AwsConfig` SNS binder + `localstack-init/init-aws.sh`). Resolves QA checkpoint FINAL 4, Finding #3. | Net-new infra with no current producer; if notifications become a requirement they must be added under a properly-scoped feature, with the topic already in place. |

## Forthcoming Decisions

_None outstanding._ All previously-forthcoming decision points (`D-008` state propagation,
`D-009` composite-key strategy, `D-010` interest rounding mode) have been implemented and
promoted into **Resolved Decisions** above with full rationale and risks. `D-011` (the
`ACCT-ID` → `Long`/`BIGINT` primary-key mapping) was recorded at the same time. When a new
non-trivial decision point arises, add it here as *Forthcoming*, then promote it under the next
free `D-NNN` once the implementing change lands (see *Maintaining This Log*).

## Maintaining This Log

1. Assign the next free `D-NNN` identifier (never reuse or renumber existing ones).
2. Add a row to **Resolved Decisions** with all five columns populated
   (`# | Decision | Alternatives Considered | Rationale | Risks`).
3. When a *Forthcoming* decision point is settled, move its row into **Resolved Decisions**
   with the chosen option, full rationale, and concrete risks.
4. Keep rationale out of source-code comments — link to the relevant `D-NNN` instead.
