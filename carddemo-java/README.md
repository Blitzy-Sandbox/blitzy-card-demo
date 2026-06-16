# CardDemo Java

[![Java](https://img.shields.io/badge/Java-25%20LTS-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.15-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Coverage](https://img.shields.io/badge/coverage-%E2%89%A580%25%20line-success.svg)](docs/validation-gates.md)

> A Java 25 LTS + Spring Boot 3.x re-platforming of the **AWS CardDemo** credit-card management
> application, migrated from its original COBOL / CICS / VSAM / JCL / BMS mainframe implementation to a
> modular, observable, cloud-native service — with **100% behavioral parity** to the source.

---

## Overview

**CardDemo Java** is a faithful, full-paradigm modernization of the
[AWS CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo) reference workload.
The original is a z/OS transaction-and-batch monolith comprising **28 COBOL programs**
(17 online CICS programs + 11 batch programs), **28 copybooks**, **11 VSAM datasets**, and
**29 JCL jobs**. This project re-platforms that system onto an idiomatic Spring stack while preserving
every business rule, record layout, financial calculation, and batch condition-code path.

The migration is governed by four invariants:

- **Behavioral parity** — every COBOL paragraph produces identical output for identical input; zero
  behavioral regression.
- **Interface-contract fidelity** — fixed-width record layouts, message schemas, and batch triggers
  remain byte-identical to the source.
- **No hardcoded credentials** — all secrets resolve from environment variables (the legacy plaintext
  `USRSEC` password is upgraded to BCrypt while preserving the login flow).
- **No feature expansion** — only the closed set of migrated capabilities (**F-001 through F-022**) is
  implemented; nothing new is introduced.

> **The COBOL sources are NOT copied into this repository.** Traceability to the original system is
> preserved by referencing source commit SHA **`27d6c6f`** (from the version string
> `CardDemo_v1.0-15-g27d6c6f-68`). Every COBOL paragraph is mapped to its Java class/method — and back
> — in [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md), with 100% coverage.

### Paradigm mapping at a glance

| Source (z/OS)                                    | Target (Java / AWS)                                            |
| :----------------------------------------------- | :------------------------------------------------------------- |
| CICS pseudo-conversational programs + BMS 3270   | Spring MVC REST controllers + JSON DTOs                        |
| `CARDDEMO-COMMAREA` conversational state         | Stateless HTTP + JWT claims                                    |
| VSAM KSDS / AIX / PATH (11 datasets)             | PostgreSQL 16 tables + indexes via Spring Data JPA             |
| JCL job streams (`EXEC PGM`, `DD`, `COND`)       | Spring Batch jobs / steps + `JobExecutionDecider` + profiles   |
| CICS TDQ (`WRITEQ TD`)                            | AWS SQS FIFO queue                                             |
| GDG generation datasets                          | AWS S3 versioned objects                                       |
| Language Environment date services (`CEEDAYS`)   | `java.time.LocalDate` validation service                       |
| RACF / plaintext `USRSEC` security               | Spring Security + BCrypt + JWT                                 |

---

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Prerequisites](#prerequisites)
- [Build and Run](#build-and-run)
- [Architecture](#architecture)
- [Project Layout](#project-layout)
- [AWS and LocalStack](#aws-and-localstack)
- [Observability](#observability)
- [Functional Scope](#functional-scope)
- [Documentation](#documentation)
- [Quality Gates](#quality-gates)
- [Testing](#testing)
- [Contributing](#contributing)
- [Support](#support)
- [License](#license)
- [Traceability and Project Status](#traceability-and-project-status)

---

## Technology Stack

| Layer                 | Technology                                                                 | Notes                                                            |
| :-------------------- | :------------------------------------------------------------------------- | :-------------------------------------------------------------- |
| Language / Runtime    | **Java 25 LTS**                                                            | Long-term-support release; target runtime                       |
| Application framework | **Spring Boot 3.5.x** (`3.5.15`)                                           | Auto-configuration, BOM-managed dependencies                    |
| Web / API             | Spring MVC (`spring-boot-starter-web`)                                     | Stateless REST/JSON endpoints (replaces BMS 3270 screens)       |
| Persistence           | **Spring Data JPA** + **PostgreSQL 16**                                    | 11 VSAM datasets re-platformed to a relational schema           |
| Schema migration      | **Flyway** (`flyway-core`, `flyway-database-postgresql`)                   | `V1` schema, `V2` indexes, `V3` seed data applied on startup    |
| Batch processing      | **Spring Batch**                                                           | Re-hosts the 29 JCL jobs as jobs/steps + condition-code logic   |
| Security              | **Spring Security** + **JWT** (OAuth2 resource server)                     | BCrypt password hashing; stateless authentication               |
| Cloud integration     | **Spring Cloud AWS** (`io.awspring.cloud` 3.3.x) — **S3 / SQS / SNS**      | Exercised locally against **LocalStack**; zero live AWS         |
| Decimal arithmetic    | `java.math.BigDecimal` (`RoundingMode.HALF_EVEN`)                          | Exact fixed-point parity with COBOL `COMP-3` / `PIC` clauses    |
| Observability         | **Micrometer** + **OpenTelemetry** + **Prometheus** + **Grafana** + Jaeger | Structured JSON logging, tracing, metrics, health/readiness     |
| Testing               | JUnit 5, Mockito, AssertJ, **Testcontainers** (PostgreSQL + LocalStack)   | Unit, integration, and end-to-end suites                        |
| Build                 | **Maven** (with bundled wrapper `./mvnw`)                                  | Surefire (unit), Failsafe (integration), JaCoCo, OWASP plugins  |

---

## Prerequisites

A clean development machine needs only the following. (Maven itself is optional — the repository ships
the **Maven Wrapper** `./mvnw`, which downloads the correct Maven version automatically.)

| Tool                  | Minimum version | Purpose                                                            |
| :-------------------- | :-------------- | :---------------------------------------------------------------- |
| **JDK**               | 25 (LTS)        | Compile and run the application                                    |
| **Maven**             | 3.9+            | Build tool — or just use the bundled `./mvnw` wrapper             |
| **Docker**            | 24+             | Run PostgreSQL, LocalStack, and the observability stack           |
| **Docker Compose**    | v2 plugin       | Orchestrate the local infrastructure (`docker compose ...`)       |
| **AWS CLI**           | 2.x (or 1.x)    | Inspect S3 buckets / SQS queues hosted by LocalStack              |

Verify your toolchain:

```bash
java -version      # openjdk version "25" ...
docker --version
docker compose version
aws --version
```

---

## Build and Run

All commands below are copy-paste accurate and use the bundled Maven Wrapper.

```bash
# 1. Clone and enter the project
git clone <repository-url> && cd carddemo-java

# 2. Build the application and run unit tests (zero-warning build — Gate 2)
./mvnw clean verify

# 3. Start local infrastructure: PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana.
#    docker-compose.yml has NO committed secret defaults, so export the required secrets first
#    (or place them in a local, git-ignored .env); the stack fails fast naming any missing one.
export POSTGRES_PASSWORD=<choose-a-local-password> \
       GRAFANA_ADMIN_PASSWORD=<choose-a-local-password> \
       JWT_SECRET=<a-random-string-of-at-least-32-characters> \
       LOCALSTACK_AUTH_TOKEN=<your-localstack-token>
docker compose up -d

# 4. Run the application with the local profile
./mvnw spring-boot:run -Dspring.profiles.active=local

# 5. Verify the application is healthy
curl http://localhost:8080/actuator/health

# 6. Run the full integration suite (Testcontainers + LocalStack)
./mvnw verify -Pintegration
```

> **Notes**
> - Steps 1–2 require only the JDK; no running services are needed for the unit build.
> - Step 3 must complete before the local run (step 4) so the datasource and AWS endpoints are
>   reachable. `docker-compose.yml` requires `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`,
>   `JWT_SECRET` (at least 32 characters), and `LOCALSTACK_AUTH_TOKEN` to be set — it carries no
>   committed secret defaults; the AWS keys default to LocalStack's documented `test` emulator
>   dummies. The integration suite (step 6) provisions its own ephemeral containers via
>   Testcontainers and only requires a running Docker daemon.
> - On systems that still use the standalone Compose v1 binary, substitute `docker-compose up -d`.

---

## Architecture

The migration converts a z/OS transaction-and-batch monolith into a cleanly layered Spring service.

**Before (z/OS):** End users drive **CICS** pseudo-conversational programs through **BMS 3270** screens;
conversational state lives in the `CARDDEMO-COMMAREA`; data persists in **VSAM** KSDS/AIX/PATH datasets;
overnight processing runs as **JCL** job streams through **JES**, with the online-to-batch handoff
performed via a CICS **TDQ** (`WRITEQ TD`).

**After (Java / AWS):** Clients call stateless **REST/JSON** endpoints served by **Spring MVC**
controllers, authenticated with **JWT**; business logic lives in service classes (one per online COBOL
program); data persists in **PostgreSQL 16** through **Spring Data JPA** repositories; overnight
processing runs as **Spring Batch** jobs preserving the original 5-stage pipeline and condition-code
logic; the online-to-batch handoff is an **SQS FIFO** publication; generation datasets and batch
staging move to **S3**.

The request path follows a strict layering: **Controller → Service → Repository → Database**, with
cross-cutting concerns (security, observability, transactions) applied as Spring infrastructure.

> See [`docs/architecture-before-after.md`](docs/architecture-before-after.md) for the full set of
> **Mermaid** before/after diagrams (z/OS vs. Java/AWS topology, the 5-stage batch pipeline, the
> VSAM→PostgreSQL data-migration flow, component interaction, and the authentication flow).

---

## Project Layout

```text
carddemo-java/
├── pom.xml                       # Maven build (Java 25, Spring Boot 3.x BOM)
├── mvnw, mvnw.cmd, .mvn/         # Maven Wrapper
├── Dockerfile                    # Application container image
├── docker-compose.yml            # PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana + app
├── localstack-init/
│   └── init-aws.sh               # Creates S3 buckets + SQS FIFO queue on LocalStack startup
├── README.md                     # You are here
├── DECISION_LOG.md               # Rationale for every non-trivial decision
├── TRACEABILITY_MATRIX.md        # Bidirectional COBOL ↔ Java mapping (100% coverage)
├── .github/workflows/            # CI: build + test + OWASP dependency-check
├── docs/                         # Architecture, onboarding, API contracts, gates, exec deck
└── src/
    ├── main/
    │   ├── java/com/carddemo/
    │   │   ├── CardDemoApplication.java   # Spring Boot bootstrap
    │   │   ├── config/                    # Security, Batch, AWS, JPA, Observability, Web
    │   │   ├── model/                     # entity/ (JPA), dto/, enums/, key/ (composite keys)
    │   │   ├── repository/                # Spring Data JPA repositories (one per VSAM KSDS)
    │   │   ├── service/                   # Business logic (one service per online COBOL program)
    │   │   ├── controller/                # REST controllers (mapped from BMS mapsets)
    │   │   ├── batch/                     # jobs/, processors/, readers/, writers/
    │   │   ├── exception/                 # CardDemoException hierarchy (← FILE STATUS codes)
    │   │   └── observability/             # Correlation-ID filter, metrics, health indicators
    │   └── resources/
    │       ├── application.yml            # Base configuration
    │       ├── application-local.yml      # Local profile (Docker Compose infra)
    │       ├── application-test.yml       # Test profile (Testcontainers)
    │       ├── db/migration/              # Flyway V1 schema, V2 indexes, V3 seed data
    │       ├── validation/                # NANPA / state / ZIP reference data (JSON)
    │       └── logback-spring.xml         # Structured JSON logging
    └── test/java/com/carddemo/
        ├── unit/                          # service / batch / validation unit tests
        ├── integration/                   # repository / batch / aws (LocalStack) tests
        └── e2e/                           # batch pipeline + online + gate-verification tests
```

The base Java package is **`com.carddemo`** across all source and test trees.

---

## AWS and LocalStack

Every AWS interaction runs against **LocalStack** — there are **no live AWS dependencies**, no real
account, and no provisioned cloud resources. The mainframe I/O substrates map to AWS services as
follows:

| AWS service | CardDemo usage                                            | Replaces (z/OS)              |
| :---------- | :-------------------------------------------------------- | :--------------------------- |
| **S3**      | Batch staging (GDG), statement & report output, rejects  | GDG generation datasets      |
| **SQS**     | Report-submission queue (point-to-point, ordered)        | CICS TDQ (`WRITEQ TD`)       |
| **SNS**     | Alert / notification fan-out                              | (new operational capability) |

The `localstack-init/init-aws.sh` hook provisions the following resources on startup:

| Resource type | Name                          |
| :------------ | :---------------------------- |
| S3 bucket     | `carddemo-batch-input`        |
| S3 bucket     | `carddemo-batch-output`       |
| S3 bucket     | `carddemo-statements`         |
| SQS queue     | `carddemo-report-jobs.fifo`   |

Configuration (see `application-local.yml` / `application-test.yml`):

- **Endpoint:** `http://localhost:4566`
- **Auth token:** supplied via the `LOCALSTACK_AUTH_TOKEN` environment variable (never hardcoded).
- **Credentials:** AWS credentials are read from the standard environment variables
  (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`); for LocalStack any non-empty
  value is accepted.

Inspect the LocalStack resources with the AWS CLI:

```bash
aws --endpoint-url=http://localhost:4566 s3 ls
aws --endpoint-url=http://localhost:4566 sqs list-queues
```

---

## Observability

Observability ships with the application — not as a follow-up. The COBOL source had no logging
framework, metrics, tracing, or health checks; all of the following are built in and verifiable in the
local Docker Compose environment.

- **Structured logging** — Logback emits JSON carrying `traceId`, `spanId`, and a custom
  `correlationId`; a `CorrelationIdFilter` injects the correlation ID into the MDC for each request and
  propagates it through the service and batch layers.
- **Distributed tracing** — Micrometer Tracing with the OpenTelemetry bridge instruments controllers,
  JPA repositories, S3/SQS operations, and batch steps, exporting via OTLP to **Jaeger**.
- **Metrics** — Spring Boot Actuator + Micrometer + the Prometheus registry expose
  `/actuator/prometheus`, including custom metrics `carddemo.batch.records.processed`,
  `carddemo.batch.records.rejected` (reason-tagged), `carddemo.auth.attempts` (outcome-tagged), and
  `carddemo.transaction.amount.total`.
- **Health / readiness** — `/actuator/health` exposes composite indicators for PostgreSQL, S3 bucket
  accessibility, and SQS availability, plus Kubernetes-style liveness/readiness probes.
- **Dashboard** — import [`docs/grafana-dashboard.json`](docs/grafana-dashboard.json) into Grafana for
  panels covering request/error rates, latency percentiles, batch throughput, and JVM/GC metrics.

| Endpoint / UI            | Default URL                              | Provided by                |
| :----------------------- | :--------------------------------------- | :------------------------- |
| Application health       | `http://localhost:8080/actuator/health`  | Spring Boot Actuator       |
| Prometheus scrape metrics| `http://localhost:8080/actuator/prometheus` | Micrometer Prometheus   |
| Jaeger UI (tracing)      | `http://localhost:16686`                 | Jaeger (Docker Compose)    |
| Prometheus UI            | `http://localhost:9090`                  | Prometheus (Docker Compose)|
| Grafana UI               | `http://localhost:3000`                  | Grafana (Docker Compose)   |

> Default ports reflect the services defined in `docker-compose.yml`; adjust there if they collide with
> other local processes.

---

## Functional Scope

CardDemo is a credit-card management application supporting account, card, transaction, and bill-payment
operations. There are two roles — **Regular users** perform the back-office user functions, and **Admin
users** perform user-administration functions. Exactly the migrated closed set of capabilities
(**F-001 through F-022**: 17 online functions + 5 batch processing capabilities) is implemented; no new
endpoints, entities, or business rules are added.

Two seed users are provisioned by the `V3` seed migration: `ADMIN001` (admin role) and `USER0001`
(regular role). Passwords are stored **BCrypt-hashed**; the initial login credentials are provided
through environment configuration and documented in the
[onboarding guide](docs/onboarding-guide.md) — they are never hardcoded in this repository.

### User Functions

Account inquiry and maintenance, credit-card listing/detail/update, transaction listing/detail/entry,
bill payment, and report submission. Each maps to a dedicated Spring service and REST endpoint.

### Admin Functions

User administration — list, add, update, and delete application users — gated by the `ADMIN` role.

### Online Transaction Inventory

Each former CICS transaction and BMS mapset maps to a Spring service and REST endpoint. The original
transaction IDs and program names are retained here purely for traceability.

| Legacy Tran | Legacy Program | Function            | Java service              | REST endpoint                     |
| :---------- | :------------- | :------------------ | :------------------------ | :-------------------------------- |
| CC00        | COSGN00C       | Sign-on             | `AuthenticationService`   | `POST /api/auth/signin`           |
| CM00        | COMEN01C       | Main menu           | `MainMenuService`         | `GET  /api/menu/main`             |
| CA00        | COADM01C       | Admin menu          | `AdminMenuService`        | `GET  /api/menu/admin`            |
| CAVW        | COACTVWC       | Account view        | `AccountViewService`      | `GET  /api/accounts/{id}`         |
| CAUP        | COACTUPC       | Account update      | `AccountUpdateService`    | `PUT  /api/accounts/{id}`         |
| CCLI        | COCRDLIC       | Card list           | `CardListService`         | `GET  /api/cards`                 |
| CCDL        | COCRDSLC       | Card detail         | `CardDetailService`       | `GET  /api/cards/{id}`            |
| CCUP        | COCRDUPC       | Card update         | `CardUpdateService`       | `PUT  /api/cards/{id}`            |
| CT00        | COTRN00C       | Transaction list    | `TransactionListService`  | `GET  /api/transactions`          |
| CT01        | COTRN01C       | Transaction detail  | `TransactionDetailService`| `GET  /api/transactions/{id}`     |
| CT02        | COTRN02C       | Transaction add     | `TransactionAddService`   | `POST /api/transactions`          |
| CB00        | COBIL00C       | Bill payment        | `BillPaymentService`      | `POST /api/billing/pay`           |
| CR00        | CORPT00C       | Report submission   | `ReportSubmissionService` | `POST /api/reports/submit`        |
| CU00        | COUSR00C       | List users          | `UserListService`         | `GET  /api/admin/users`           |
| CU01        | COUSR01C       | Add user            | `UserAddService`          | `POST /api/admin/users`           |
| CU02        | COUSR02C       | Update user         | `UserUpdateService`       | `PUT  /api/admin/users/{id}`      |
| CU03        | COUSR03C       | Delete user         | `UserDeleteService`       | `DELETE /api/admin/users/{id}`    |

### Batch Job Inventory

The 5-stage JCL pipeline is re-hosted as Spring Batch jobs, preserving sequential dependencies and
condition-code logic. `DFSORT` + IDCAMS `REPRO` is replaced by a Java `Comparator` plus bulk JPA insert.

| Legacy Job | Legacy Program | Function                          | Spring Batch job                |
| :--------- | :------------- | :-------------------------------- | :------------------------------ |
| POSTTRAN   | CBTRN02C       | Daily transaction posting         | `DailyTransactionPostingJob`    |
| INTCALC    | CBACT04C       | Interest calculation              | `InterestCalculationJob`        |
| COMBTRAN   | SORT / IDCAMS  | Combine system + daily txns       | `CombineTransactionsJob`        |
| CREASTMT   | CBSTM03A/B     | Statement generation (text + S3)  | `StatementGenerationJob`        |
| TRANREPT   | CBTRN03C       | Transaction report                | `TransactionReportJob`          |

The full per-paragraph mapping for every program (including the batch file readers `CBACT01C`–`CBACT03C`,
`CBCUS01C`, `CBTRN01C`, and the shared date-validation subprogram `CSUTLDTC`) is recorded in
[`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md). The REST, SQS, and S3 contracts are documented in
[`docs/api-contracts.md`](docs/api-contracts.md).

---

## Documentation

| Document                                                          | Purpose                                                              |
| :--------------------------------------------------------------- | :------------------------------------------------------------------ |
| [`docs/onboarding-guide.md`](docs/onboarding-guide.md)           | Clean-machine-to-running guide, domain context, pitfalls, extensions |
| [`docs/architecture-before-after.md`](docs/architecture-before-after.md) | Mermaid before/after architecture diagrams                  |
| [`docs/api-contracts.md`](docs/api-contracts.md)                 | REST / SQS / S3 contract definitions                                |
| [`docs/validation-gates.md`](docs/validation-gates.md)           | Evidence for the 8 validation gates                                  |
| [`docs/executive-presentation.html`](docs/executive-presentation.html) | Self-contained reveal.js deck for leadership                  |
| [`docs/grafana-dashboard.json`](docs/grafana-dashboard.json)     | Importable Grafana observability dashboard                          |
| [`DECISION_LOG.md`](DECISION_LOG.md)                             | Rationale, alternatives, and risks for every non-trivial decision   |
| [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md)               | Bidirectional COBOL ↔ Java mapping at 100% paragraph coverage       |

---

## Quality Gates

The build enforces an 8-gate validation framework; the headline quality bars are:

- **Zero-warning build** — `./mvnw clean verify` passes with `-Xlint:all` / `-Werror`; the only
  permitted suppressions are for framework-generated code (Gate 2).
- **≥ 80% line coverage** — enforced by **JaCoCo** across all packages (Gate 8).
- **Zero critical/high CVEs** — the **OWASP dependency-check** Maven plugin scans direct and transitive
  dependencies and fails on any critical or high finding (Gate 8).
- **Unsafe-code audit** — raw SQL concatenation, `Runtime.exec`, reflection, unchecked casts, and
  suppressed warnings are counted; any count above 50 requires per-site justification (Gate 6).

Full gate-by-gate evidence — including end-to-end boundary processing, performance baselines, named
real-world artifact runs, and contract verification — is recorded in
[`docs/validation-gates.md`](docs/validation-gates.md).

---

## Testing

| Suite             | Command                          | Scope                                                        |
| :---------------- | :------------------------------- | :----------------------------------------------------------- |
| Unit              | `./mvnw test`                    | Services, processors, validators (Surefire)                  |
| Build + unit      | `./mvnw clean verify`            | Compile, unit tests, coverage, static checks                 |
| Integration / E2E | `./mvnw verify -Pintegration`    | Repository, batch, and AWS tests via Testcontainers + LocalStack (Failsafe) |

Integration tests provision and tear down their own PostgreSQL and LocalStack containers, so they
require only a running Docker daemon — no manually started services and no live AWS.

---

## Contributing

Contributions and enhancements are welcome. Please open an issue to discuss substantial changes, follow
the existing package conventions under `com.carddemo`, keep the zero-warning build and ≥80% coverage
gates green, and record any non-trivial design decision in [`DECISION_LOG.md`](DECISION_LOG.md) rather
than in inline code comments. See [`docs/onboarding-guide.md`](docs/onboarding-guide.md) for extension
guides (adding an entity, a batch job, or an API endpoint).

---

## Support

If you have questions or requests for improvement, please raise an issue in the repository. For domain
background, build troubleshooting, and common pitfalls (BigDecimal precision, LocalStack path-style
access, Testcontainers Docker-socket permissions, Flyway migration ordering), consult the
[onboarding guide](docs/onboarding-guide.md).

---

## License

This project is released under the **Apache License 2.0**, consistent with the upstream AWS CardDemo
reference application. The full license text is available at
<https://www.apache.org/licenses/LICENSE-2.0>.

---

## Traceability and Project Status

This is a **greenfield migration target**: it contains no COBOL, Maven-pre-existing, or Gradle artifacts
from the source — the COBOL programs, copybooks, and JCL are translated, **not copied**. Traceability to
the original system is anchored to source commit SHA **`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`) and
maintained in [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md), which maps every COBOL paragraph across
all 28 programs to its Java class/method with 100% bidirectional coverage.

The migration delivers the closed set of features **F-001 through F-022** with 100% behavioral parity to
the source; no feature expansion is in scope.
