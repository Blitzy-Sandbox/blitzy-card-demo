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
resolved values are applied directly in `pom.xml` and the `src/` package tree.

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

## Forthcoming Decisions

The decision points below are **expected** to be resolved as the corresponding components
are implemented. They are recorded here so the log remains the single source of truth, but
they are **not yet decided** — no rationale is asserted until the implementing change is
made, at which point each row is promoted into the *Resolved Decisions* table above with a
monotonic `D-NNN` identifier and full rationale. These rows therefore intentionally carry a
`Status` of *Forthcoming* and list only the options under consideration.

| # | Decision Point | Options Under Consideration | Status | Blueprint Reference |
|---|---|---|---|---|
| D-008 | State propagation for the pseudo-conversational (CICS COMMAREA → stateless REST) flow | JWT bearer token vs. server-side session-scoped token | Forthcoming — to be recorded when the authentication/session layer is implemented | Transformation rule "CICS `RETURN TRANSID COMMAREA` → Stateless REST with context propagation (JWT or session-scoped state)" (tech-spec §0.1.2) |
| D-009 | Composite-key mapping strategy for `TCATBAL`, `DISCGRP`, `TRANCATG` | JPA `@EmbeddedId` vs. `@IdClass` | Forthcoming — to be recorded when the composite-key entities are implemented (the §0.4.1 entity table currently leans toward `@EmbeddedId`) | Design-pattern table "Composite key → `@EmbeddedId` / `@IdClass`" (tech-spec §0.3.3) |
| D-010 | Rounding mode for the interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` | `RoundingMode.HALF_EVEN` (banker's rounding), pending confirmation against the COBOL `COMPUTE`/`ROUNDED` behavior | Forthcoming — to be confirmed when `InterestCalculationJob` is implemented | Decimal-precision rule for the interest formula (tech-spec Decimal Precision Rules) |

## Maintaining This Log

1. Assign the next free `D-NNN` identifier (never reuse or renumber existing ones).
2. Add a row to **Resolved Decisions** with all five columns populated
   (`# | Decision | Alternatives Considered | Rationale | Risks`).
3. When a *Forthcoming* decision point is settled, move its row into **Resolved Decisions**
   with the chosen option, full rationale, and concrete risks.
4. Keep rationale out of source-code comments — link to the relevant `D-NNN` instead.
