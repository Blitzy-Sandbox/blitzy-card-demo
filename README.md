# CardDemo — Credit-Card Management (Java 25 / Spring Boot 3.x)

**CardDemo** is a credit-card management system for managing accounts, cards, transactions, and
bill payments. This repository hosts the **modernized Java 25 LTS / Spring Boot 3.x**
implementation, migrated from the original AWS CardDemo mainframe workload
(COBOL / CICS / VSAM / JCL / BMS).

The frozen COBOL source is retained **read-only** under [`app/`](./app) as the authoritative
migration reference — it is *not* built or run as part of the Java target. It is referenced by
commit SHA `27d6c6f` (full `7756d895ffeb65f7ea72aaa609e356d9899afcec`); the
[traceability matrix](./docs/traceability-matrix.md) maps every COBOL paragraph to its Java
counterpart against that SHA.

> **New here?** Jump to the [Quick Start (Local Development)](#quick-start-local-development)
> to go from a clean machine to a running app. For the full, standalone walkthrough
> (domain deep-dive, extension guide, suggested next tasks), read
> [`docs/onboarding.md`](./docs/onboarding.md).

<br/>

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Quick Start (Local Development)](#quick-start-local-development)
- [Architecture](#architecture)
- [Application Details](#application-details)
  - [User Roles](#user-roles)
  - [Application Inventory](#application-inventory)
    - [Online Transactions (REST)](#online-transactions-rest)
    - [Batch Pipeline](#batch-pipeline)
  - [Application Screens (Historical 3270)](#application-screens-historical-3270)
- [Common Pitfalls](#common-pitfalls)
- [How to Extend](#how-to-extend)
- [Documentation](#documentation)
- [Legacy Mainframe Reference](#legacy-mainframe-reference)
- [Suggested Next Tasks](#suggested-next-tasks)
- [Support](#support)
- [Contributing](#contributing)
- [License](#license)

<br/>

## Overview

CardDemo was originally published by AWS as a mainframe reference workload for exercising
discovery, migration, and modernization tooling — its COBOL coding style is intentionally
non-uniform because it was authored to stress-test that tooling.

This project is a **complete tech-stack migration** of that workload to an idiomatic, layered
Spring Boot service, targeting **100% behavioral parity** with the frozen COBOL source. The
migration changes:

- **Language:** COBOL → Java 25 LTS
- **Platform:** CICS online + JCL batch monolith → Spring Boot 3.x modular service
- **Persistence:** VSAM KSDS / sequential files → PostgreSQL 16 via Spring Data JPA (Flyway migrations)
- **Orchestration:** JCL job steps → Spring Batch jobs/steps (SQS-triggered launches)
- **I/O staging:** GDG datasets → AWS S3 versioned objects (via LocalStack)
- **Presentation:** 3270 BMS terminal screens → **headless REST controllers** (JSON APIs)

The service is **headless (REST-only)** — there is no browser UI and no design system. Decimal
fidelity, control-flow semantics, and external interface contracts are preserved exactly (see
[Common Pitfalls](#common-pitfalls) and the [decision log](./docs/decision-log.md)).

<br/>

## Technology Stack

| Area | Technology | Version |
| :--- | :--------- | :------ |
| Language / Runtime | Java (LTS) | 25 |
| Framework | Spring Boot | 3.5.11 |
| Build | Apache Maven | 3.9.9 |
| Database | PostgreSQL | 16 |
| Persistence | Spring Data JPA / Hibernate | (Boot-managed) |
| Batch | Spring Batch | 5.x (Boot-managed) |
| Security | Spring Security (BCrypt + JWT) | (Boot-managed) / jjwt 0.12.6 |
| Schema migrations | Flyway | 11.x |
| AWS integration | Spring Cloud AWS (S3 / SQS / SNS) | 3.3.0 |
| Tracing | Micrometer Tracing + OpenTelemetry → Jaeger | (Boot-managed) |
| Metrics / Dashboards | Prometheus + Grafana | (see `docker-compose.yml`) |
| Local infrastructure | Docker Compose | — |
| Testing | JUnit 5, Mockito, Testcontainers + LocalStack | Testcontainers 2.0.3 |

> **Zero live AWS:** every AWS interaction (S3, SQS, SNS) runs against **LocalStack** only —
> no live AWS credentials in any test, local, or CI path.

**Legacy technologies (source, reference-only under [`app/`](./app)):** COBOL, CICS, VSAM, JCL,
BMS. RACF is *not* used; the file-based `USRSEC` authentication model is preserved, with
plaintext passwords upgraded to BCrypt hashes (see decision **D-002**).

<br/>

## Quick Start (Local Development)

These steps take you from a clean machine to a running application. Every command is
copy-paste runnable from the repository root.

### Prerequisites

- **JDK 25** (LTS) — `java -version` should report 25
- **Maven 3.9.9** — `mvn -v`
- **Docker + Docker Compose** — `docker compose version`
- *(Optional)* **`LOCALSTACK_AUTH_TOKEN`** — the bundled LocalStack **community** image
  (`localstack/localstack:4`) requires **no token**. Only export this variable if you opt into
  LocalStack Pro. **Never hardcode it** — supply it via your shell/environment:

  ```bash
  export LOCALSTACK_AUTH_TOKEN="<your-token>"   # optional; Pro only
  ```

### Step 1 — Start the local stack

```bash
docker compose up -d
```

This starts exactly five services (host ports match [`docker-compose.yml`](./docker-compose.yml)):

| Service | Purpose | Host Port(s) |
| :------ | :------ | :----------- |
| **PostgreSQL 16** | System of record (replaces VSAM) | `5432` |
| **LocalStack** | AWS S3 / SQS / SNS emulator (zero live AWS) | `4566` |
| **Jaeger** | Distributed-tracing UI + OTLP collector | `16686` (UI), `4317` (OTLP gRPC), `4318` (OTLP HTTP) |
| **Prometheus** | Scrapes the app's metrics endpoint | `9090` |
| **Grafana** | Dashboards (dev login `admin` / `admin`) | `3000` |

> The application itself is **not** a Compose service — you run it on the host (Step 3).
> Prometheus scrapes the host-bound app at `host.docker.internal:8080`.

### Step 2 — Build and test

```bash
mvn clean verify
```

This compiles the project, runs the JUnit 5 unit tests and the Testcontainers/LocalStack
integration tests, and enforces **JaCoCo line coverage ≥ 80%** (Gate 8).

### Step 3 — Run the app (local profile)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

On startup, **Flyway** applies the migrations automatically — `V1__schema.sql` (11 tables),
`V2__indexes.sql`, and `V3__seed_data.sql` (which seeds the database from the ASCII fixtures
under [`app/data/ASCII/`](./app/data/ASCII)). The app listens on `http://localhost:8080` and
connects to the PostgreSQL database `carddemo` on `5432` using the dev-only defaults defined in
`docker-compose.yml` (production uses environment variables / a vault — never hardcoded).

### Step 4 — Verify observability

```bash
curl -s http://localhost:8080/actuator/health       # liveness / readiness (expect {"status":"UP"})
curl -s http://localhost:8080/actuator/prometheus    # Prometheus-format metrics
```

- **Jaeger UI:** <http://localhost:16686> (traces across REST → service → repository → AWS)
- **Prometheus:** <http://localhost:9090>
- **Grafana:** <http://localhost:3000> (dev login `admin` / `admin`)

> **LocalStack endpoint:** point AWS clients at `http://localhost:4566` (never real AWS). The
> authoritative local S3 reachability check (per the setup environment) is
> `http://localhost.localstack.cloud:4566`.

For the full standalone onboarding guide — domain deep-dive, an end-to-end extension
walkthrough, and suggested next tasks — see **[`docs/onboarding.md`](./docs/onboarding.md)**.

<br/>

## Architecture

The monolith is replaced by a layered Spring service. The 17 BMS terminal screens collapse into
**8 REST controllers**; the JCL pipeline becomes a 5-stage Spring Batch flow.

| Layer | Package | Count | Replaces |
| :---- | :------ | :---- | :------- |
| Entities | `com.carddemo.entity` | 11 `@Entity` | VSAM record layouts (copybooks) |
| Repositories | `com.carddemo.repository` | 11 `JpaRepository` | VSAM keyed access / AIX paths |
| Services | `com.carddemo.service` | 20 `@Service` | COBOL paragraphs / business logic |
| Controllers | `com.carddemo.controller` | 8 `@RestController` | 17 CICS BMS screens |
| Batch | `com.carddemo.batch` | 5-stage pipeline | JCL job steps |
| DTOs | `com.carddemo.dto` | request/response | BMS symbolic maps + record fields |
| Exceptions | `com.carddemo.exception` | 7 + status enums | `FILE STATUS` codes / ABEND paragraphs |
| Config | `com.carddemo.config` | 6 `@Configuration` | JCL PARM/SYSIN, security, AWS, JPA |
| Observability | `com.carddemo.observability` | correlation / metrics / health | (net-new) |

Persistence is **PostgreSQL 16** managed by Flyway (V1 schema → V2 indexes → V3 seed data). AWS
S3/SQS/SNS integration runs through **LocalStack**. Observability is built in from day one:
structured JSON logging with MDC correlation IDs, Micrometer tracing exported over OTLP to
Jaeger, Prometheus metrics, a Grafana dashboard, and Actuator health/readiness endpoints.

See the before/after and detailed diagrams under
[`docs/architecture/`](./docs/architecture/): [overview](./docs/architecture/overview.md),
[component interactions](./docs/architecture/component-interactions.md), and
[data flow](./docs/architecture/data-flow.md).

<br/>

## Application Details

CardDemo lets users manage accounts, credit cards, transactions, and bill payments across a
documented feature set of **22 features (F-001 through F-022)**.

### User Roles

There are two types of users:

- **Regular User** — performs the user (back-office) functions.
- **Admin User** — performs the administrative functions (user management).

Regular users can perform the user functions; Admin users can perform the admin functions.

![User Flow](./diagrams/Application-Flow-User.png?raw=true "User Flow")

![Admin Flow](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

### Application Inventory

#### Online Transactions (REST)

The 17 legacy CICS transactions (BMS screens) are now exposed as JSON endpoints across
**8 REST controllers** (headless — no terminal, no browser UI):

| Txn  | BMS Map | COBOL Program | Function             | REST Controller       |
| :--- | :------ | :------------ | :------------------- | :-------------------- |
| CC00 | COSGN00 | COSGN00C      | Signon               | AuthController        |
| CM00 | COMEN01 | COMEN01C      | Main Menu            | MenuController        |
| CAVW | COACTVW | COACTVWC      | Account View         | AccountController     |
| CAUP | COACTUP | COACTUPC      | Account Update       | AccountController     |
| CCLI | COCRDLI | COCRDLIC      | Credit Card List     | CardController        |
| CCDL | COCRDSL | COCRDSLC      | Credit Card View     | CardController        |
| CCUP | COCRDUP | COCRDUPC      | Credit Card Update   | CardController        |
| CT00 | COTRN00 | COTRN00C      | Transaction List     | TransactionController |
| CT01 | COTRN01 | COTRN01C      | Transaction View     | TransactionController |
| CT02 | COTRN02 | COTRN02C      | Transaction Add      | TransactionController |
| CR00 | CORPT00 | CORPT00C      | Transaction Reports  | ReportController      |
| CB00 | COBIL00 | COBIL00C      | Bill Payment         | BillPaymentController |
| CA00 | COADM01 | COADM01C      | Admin Menu           | MenuController        |
| CU00 | COUSR00 | COUSR00C      | List Users           | UserController        |
| CU01 | COUSR01 | COUSR01C      | Add User             | UserController        |
| CU02 | COUSR02 | COUSR02C      | Update User          | UserController        |
| CU03 | COUSR03 | COUSR03C      | Delete User          | UserController        |

Pseudo-conversational CICS `COMMAREA` state is replaced by stateless REST + JWT; the read-then-
rewrite optimistic-concurrency pattern (Account/Card update) is preserved via JPA `@Version`.

#### Batch Pipeline

The JCL batch monolith becomes a five-stage Spring Batch pipeline; execution order is preserved:

| Stage | Legacy Job | COBOL Program        | Function                              | Spring Batch Job        |
| :---- | :--------- | :------------------- | :------------------------------------ | :---------------------- |
| 1     | POSTTRAN   | CBTRN02C             | Transaction posting (validate/post/reject) | PostTransactionJob      |
| 2     | INTCALC    | CBACT04C             | Interest calculation                  | InterestCalculationJob  |
| 3     | COMBTRAN   | SORT                 | Combine system + daily transactions   | CombineTransactionJob   |
| 4     | CREASTMT   | CBSTM03A (+ CBSTM03B)| Statement generation                  | StatementJob            |
| 5     | TRANREPT   | CBTRN03C             | Transaction detail report             | TransactionReportJob    |

Supporting print/reference programs — CBACT01C (account), CBACT02C (card), CBACT03C (xref),
CBCUS01C (customer), and CBTRN01C (transaction) — become batch print steps, and CSUTLDTC
(LE `CEEDAYS` date validation) becomes `DateValidationService` (`java.time.LocalDate`).
JCL `COND` codes map to `JobExecutionDecider` + `FlowBuilder`; GDG generations map to versioned
S3 objects; the CORPT00C CICS TDQ → JES report bridge maps to an SQS FIFO-triggered launch.

### Application Screens (Historical 3270)

The following are the **historical 3270 terminal screens** from the mainframe system, retained
for domain context. In the migrated system these interactions are served as REST/JSON APIs
(see the [online transaction table](#online-transactions-rest)); there is **no** terminal or
browser UI.

#### Signon Screen

![Signon Screen](./diagrams/Signon-Screen.png?raw=true "Signon Screen")

#### Main Menu

![Main Menu](./diagrams/Main-Menu.png?raw=true "Main Menu")

#### Admin Menu

![Admin Menu](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

<br/>

## Common Pitfalls

1. **Decimal fidelity (money is never `float`/`double`).** Every monetary/financial field is a
   `java.math.BigDecimal` with a scale that matches the COBOL picture — e.g. account balances and
   limits (`PIC S9(10)V99`) and transaction amounts (`TRAN-AMT PIC S9(09)V99`) map to
   `BigDecimal` **scale 2**, persisted as `NUMERIC(p,s)`. Arithmetic applies an explicit
   `RoundingMode` (default `HALF_UP`). Using `float`/`double` for money is prohibited.

2. **EBCDIC vs ASCII fixtures.** The **9 ASCII fixtures** under
   [`app/data/ASCII/`](./app/data/ASCII) drive the migration and seed the database (Flyway
   `V3__seed_data.sql`). The **13 EBCDIC files** under [`app/data/EBCDIC/`](./app/data/EBCDIC)
   are **reference-only** — do not use them to seed the Java system.

3. **LocalStack endpoint configuration.** Point AWS SDK/Spring Cloud AWS clients at the
   LocalStack endpoint (`http://localhost:4566`) — **never real AWS**. Integration tests
   **self-provision** their own S3 buckets and SQS queues (via the Testcontainers LocalStack
   module) and tear them down afterward; they never depend on pre-existing LocalStack state.

<br/>

## How to Extend

The codebase follows a layered package layout under `com.carddemo`:

```
com.carddemo.{entity, repository, service, controller, dto, batch, config, exception, observability}
```

- **Add a REST endpoint:** create/extend a `@RestController` → delegate to a `@Service` → add a
  `JpaRepository`/`@Entity` if new persistence is needed → define request/response **DTOs** →
  add unit + integration tests.
- **Add a batch step:** add the job `@Configuration` and its `ItemReader` / `ItemProcessor` /
  `ItemWriter`, wiring it into the pipeline flow.
- **Trace behavior back to COBOL:** consult [`docs/traceability-matrix.md`](./docs/traceability-matrix.md)
  to locate the COBOL paragraph behind any behavior, and [`docs/decision-log.md`](./docs/decision-log.md)
  for the rationale behind non-trivial decisions.

<br/>

## Documentation

- **[`docs/onboarding.md`](./docs/onboarding.md)** — full clean-machine-to-running-app guide,
  domain deep-dive, extension walkthrough, and suggested next tasks.
- **[`docs/decision-log.md`](./docs/decision-log.md)** — decisions, alternatives, rationale, and risks.
- **[`docs/traceability-matrix.md`](./docs/traceability-matrix.md)** — 100% bidirectional
  COBOL-paragraph ↔ Java-method mapping (source referenced by SHA `27d6c6f`).
- **[`docs/architecture/`](./docs/architecture/)** — before/after and detailed Mermaid diagrams
  ([overview](./docs/architecture/overview.md),
  [component interactions](./docs/architecture/component-interactions.md),
  [data flow](./docs/architecture/data-flow.md)).
- **[`docs/project-guide.md`](./docs/project-guide.md)** and
  **[`docs/technical-specifications.md`](./docs/technical-specifications.md)** — deeper technical detail.

<br/>

## Legacy Mainframe Reference

The original COBOL/CICS/VSAM/JCL source is preserved **read-only** under [`app/`](./app) as the
migration's source of truth (commit SHA `27d6c6f`). It is **not** copied into the Java target and
is **not** compiled or executed by this project — it exists purely for traceability and reference.

The legacy surface comprises **28 COBOL programs** ([`app/cbl/`](./app/cbl)),
**28 shared copybooks** ([`app/cpy/`](./app/cpy)), **17 BMS mapsets** ([`app/bms/`](./app/bms)),
**17 symbolic map copybooks** ([`app/cpy-bms/`](./app/cpy-bms)), **29 JCL jobs**
([`app/jcl/`](./app/jcl)), and CICS/batch configuration under
[`app/csd/`](./app/csd), [`app/ctl/`](./app/ctl), and [`app/proc/`](./app/proc). Sample build
JCL lives under [`samples/jcl/`](./samples/jcl). Instructions for installing and running the
original application on a mainframe are preserved in the Git history of this file.

The 11 record-layout copybooks define the target database schema; their fixed byte lengths are
preserved for byte-equivalent I/O:

| Copybook | Domain Record | Length (bytes) | Target Entity / Table       |
| :------- | :------------ | -------------: | :-------------------------- |
| CSUSR01Y | User Security | 80  | UserSecurity                |
| CVACT01Y | Account | 300 | Account                     |
| CVACT02Y | Card | 150 | Card                        |
| CVCUS01Y | Customer | 500 | Customer                    |
| CVACT03Y | Card Cross-Reference | 50  | CardXref                    |
| CVTRA06Y | Daily Transaction | 350 | DailyTransaction            |
| CVTRA05Y | Transaction (KSDS) | 350 | Transaction                 |
| CVTRA02Y | Disclosure Group | 50  | DisclosureGroup             |
| CVTRA04Y | Transaction Category Type | 60  | TransactionCategoryType     |
| CVTRA03Y | Transaction Type | 60  | TransactionType             |
| CVTRA01Y | Transaction Category Balance | 50  | TransactionCategoryBalance  |

<br/>

## Suggested Next Tasks

Discovered during the migration and tracked as open follow-ups (all High-severity per the
project guide):

1. **Spring Boot 3.5 → 4.x upgrade.** The 3.5 line reached OSS end-of-life on **2026-06-30**
   (final OSS patch **3.5.16**). The target intentionally stays on Spring Boot **3.x** per the
   explicit migration mandate; the upgrade to 4.x (on Spring Framework 7) is a planned follow-up.
2. **Run the OWASP dependency-check** (`org.owasp:dependency-check-maven` 12.1.0) and remediate
   any critical/high CVEs (Gate 8, zero critical/high).
3. **Add a production Spring profile** (`application-prod.yml`) with externalized DB and AWS
   configuration.
4. **Externalize the JWT secret** via an environment variable / vault — no hardcoded secrets.

**Deferred (Constraint C-001):** additional database types (Db2 relational, IMS hierarchical) and
messaging/integration expansion (FTP/SFTP, message-queue integration, distributed-application
transaction exposure) remain out of scope for this migration.

<br/>

## Support

If you have questions or requests for improvement, please raise an issue in the repository.

<br/>

## Contributing

Contributions and enhancements are welcome. Please feel free to raise issues, create code, and
open merge requests — see [`CONTRIBUTING.md`](./CONTRIBUTING.md) for details.

<br/>

## License

This is a community resource released under the **Apache 2.0** license. See
[`LICENSE`](./LICENSE) and [`NOTICE`](./NOTICE) for details.

<br/>
