# CardDemo Java

> A **Java 25 LTS + Spring Boot 3.x** re-platforming of the AWS **CardDemo** mainframe credit-card
> management application — migrated from COBOL / CICS / VSAM / JCL / BMS to a modular, observable,
> cloud-native service with **100% behavioral parity**.

[![Java](https://img.shields.io/badge/Java-25%20LTS-orange)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.15-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](#license)

CardDemo Java is a **greenfield** modernization of the canonical AWS CardDemo reference workload — a
credit-card management system that lets users manage accounts, credit cards, transactions, and bill
payments. The original application is implemented in COBOL/CICS/VSAM/JCL/BMS and versioned as
`CardDemo_v1.0-15-g27d6c6f-68`. This project re-platforms that workload onto Java and AWS while
preserving every business rule, field layout, and batch contract.

> **The COBOL sources are NOT copied into this repository.** Traceability back to the original
> mainframe code is preserved by referencing the source repository commit SHA **`27d6c6f`**. The
> complete, bidirectional COBOL-paragraph-to-Java-method mapping lives in
> [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md).

The migration covers a **closed feature set (F-001 through F-022)** — 17 online functions plus 5
batch capabilities. No new business features, endpoints, entities, or rules are introduced beyond
what the source application already provides.

---

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Architecture Summary](#architecture-summary)
- [Prerequisites](#prerequisites)
- [Build & Run](#build--run)
- [Configuration & Profiles](#configuration--profiles)
- [Project Layout](#project-layout)
- [Application Inventory](#application-inventory)
  - [Online Functions (REST)](#online-functions-rest)
  - [Batch Pipeline](#batch-pipeline)
- [AWS / LocalStack](#aws--localstack)
- [Observability](#observability)
- [Testing](#testing)
- [Quality Gates](#quality-gates)
- [Documentation Index](#documentation-index)
- [Traceability & Parity](#traceability--parity)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

CardDemo Java reproduces the behavior of the source mainframe application using idiomatic Spring
constructs. The two source paradigms — **17 online CICS programs** and **11 batch programs**
(28 COBOL programs / 19,254 source lines in total) — converge on a single PostgreSQL schema that
replaces the **11 VSAM datasets**, and the **29 JCL jobs** are re-hosted as Spring Batch jobs.

What was migrated:

- **28 COBOL programs** → Java service, controller, processor, reader, and writer components.
- **28 copybooks** → JPA entities, API DTOs, enums, and composite-key classes (every field, PIC
  clause, and record length preserved).
- **11 VSAM datasets** → a PostgreSQL 16 relational schema provisioned and seeded by Flyway.
- **29 JCL jobs** → Spring Batch jobs plus a 5-stage orchestration pipeline with condition-code logic.
- **17 BMS 3270 mapsets** → stateless REST/JSON endpoints secured by Spring Security with JWT.
- Mainframe I/O substrates → AWS services: **S3** (GDG / batch staging), **SQS** (CICS transient-data
  queue), and **SNS** (notifications) — all exercised locally against LocalStack with **zero live AWS
  dependencies**.

Design invariants that govern the whole migration: all business-logic semantics are preserved with
zero behavioral regression; external interface contracts (file layouts, message schemas, batch
triggers) remain byte-identical; **no credentials are hardcoded** (secrets resolve from environment
variables); and **no feature expansion** occurs beyond F-001..F-022.

---

## Technology Stack

| Concern | Technology | Version |
| :------ | :--------- | :------ |
| Language / runtime | Java (LTS) | 25 |
| Application framework | Spring Boot | 3.5.15 |
| Persistence | Spring Data JPA + PostgreSQL | 16 |
| Schema migration | Flyway | 11.x |
| Batch processing | Spring Batch | 5.x (Boot-managed) |
| Security | Spring Security + JWT (OAuth2 Resource Server) | 6.x (Boot-managed) |
| Cloud integration | Spring Cloud AWS — S3 / SQS / SNS (via LocalStack) | 3.3.0 |
| Logging | Logback + `logstash-logback-encoder` (structured JSON) | 8.0 |
| Metrics | Micrometer + Prometheus registry | Boot-managed |
| Tracing | Micrometer Tracing + OpenTelemetry (OTLP → Jaeger) | Boot-managed |
| Build | Maven (+ bundled wrapper `./mvnw`) | 3.9+ |
| Testing | JUnit 5, Mockito, AssertJ, Spring Batch Test, Spring Security Test | Boot-managed |
| Integration testing | Testcontainers (PostgreSQL + LocalStack) | 2.0.3 |
| Coverage | JaCoCo | 0.8.14 |
| Dependency CVE scan | OWASP `dependency-check-maven` | 12.1.0 |

All runtime versions are resolved from the Spring Boot 3.5.15 BOM where applicable; non-BOM artifacts
are pinned to verified, current releases.

---

## Architecture Summary

The migration maps each COBOL execution paradigm to an idiomatic Spring construct. Behavior is
preserved at the semantic (not syntactic) level.

| Mainframe (z/OS) — *before* | Java / AWS — *after* |
| :-------------------------- | :------------------- |
| CICS pseudo-conversational programs + BMS 3270 maps | Spring MVC REST controllers + JSON DTOs |
| `CARDDEMO-COMMAREA` conversational state | Stateless HTTP + JWT claims |
| VSAM KSDS / AIX / PATH (11 datasets) | PostgreSQL 16 tables + indexes via Spring Data JPA |
| JCL job streams (`EXEC PGM`, `DD`, `COND`) | Spring Batch jobs/steps + `JobExecutionDecider` |
| CICS TDQ (`WRITEQ TD`) | AWS SQS FIFO queue |
| GDG generation datasets | AWS S3 versioned objects |
| Language Environment date services (`CEEDAYS`) | `java.time.LocalDate` validation service |
| RACF / plaintext `USRSEC` security | Spring Security + BCrypt |

The application follows clean layering: **Controller → Service → Repository → Database**, with Spring
Batch for the JCL-derived pipeline and Spring Cloud AWS for cloud integration. For the full set of
Mermaid before/after diagrams (z/OS topology, target topology, batch pipeline, data migration,
component interaction, and authentication flow) see
[`docs/architecture-before-after.md`](docs/architecture-before-after.md).

---

## Prerequisites

Install the following on a clean machine before building:

| Tool | Minimum version | Notes |
| :--- | :-------------- | :---- |
| JDK | **25** | Java 25 LTS — the target runtime. |
| Maven | 3.9+ | Optional — the bundled wrapper `./mvnw` works without a system Maven. |
| Docker + Docker Compose | current | Runs PostgreSQL, LocalStack, and the observability stack; also used by Testcontainers. |
| AWS CLI | current | Convenient for inspecting LocalStack S3/SQS/SNS resources (points at `http://localhost:4566`). |

---

## Build & Run

All commands are run from the project root (`carddemo-java/`).

```bash
# 1. Clone and enter the project
git clone <repository-url> && cd carddemo-java

# 2. Build and run unit tests (zero-warning build — Gate 2)
./mvnw clean verify

# 3. Start the local infrastructure:
#    PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana
#    The default LocalStack Community image (localstack/localstack:3.8) needs NO auth token.
#    Set a token only when overriding to a LocalStack Pro image (see "Local AWS" below).
docker compose up -d

# 4. Run the application with the local profile
./mvnw spring-boot:run -Dspring.profiles.active=local

# 5. Verify the application is healthy
curl http://localhost:8080/actuator/health

# 6. Run the full integration suite (Testcontainers + LocalStack)
./mvnw verify -Pintegration
```

> **Note on Docker Compose:** this project uses the Docker Compose v2 plugin, invoked as
> `docker compose` (with a space). If your environment still ships the legacy standalone binary, the
> equivalent command is `docker-compose up -d`.

> **Note on credentials:** every example uses environment-variable placeholders. No secret, token, or
> password is ever hardcoded in this repository (see the no-hardcoded-credentials constraint).

---

## Configuration & Profiles

Configuration is profile-driven via Spring Boot YAML files under `src/main/resources/`:

| Profile | File | Purpose |
| :------ | :--- | :------ |
| *(default)* | `application.yml` | Base configuration: JPA, Flyway, actuator exposure, common AWS settings. |
| `local` | `application-local.yml` | Local development against the Docker Compose stack; AWS endpoint points at LocalStack (`http://localhost:4566`). |
| `test` | `application-test.yml` | Integration tests; AWS endpoint and datasource wired to Testcontainers / LocalStack. |

Activate a profile with `-Dspring.profiles.active=<profile>` (for `spring-boot:run`) or
`-Dspring.profiles.active=<profile>` as a JVM argument. Flyway migrations (`V1` → `V2` → `V3`) are
applied automatically on startup to provision and seed the schema before any online or batch flow
executes.

---

## Project Layout

```text
carddemo-java/
├── pom.xml                       # Maven build (Java 25, Spring Boot 3.x BOM)
├── mvnw, mvnw.cmd, .mvn/         # Maven wrapper
├── Dockerfile                    # Application image
├── docker-compose.yml            # PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana + app
├── localstack-init/
│   └── init-aws.sh               # Creates S3 buckets + SQS FIFO queue on LocalStack startup
├── README.md                     # You are here
├── DECISION_LOG.md               # Rationale for every non-trivial migration decision
├── TRACEABILITY_MATRIX.md        # Bidirectional COBOL ↔ Java mapping (100% coverage)
├── docs/                         # Architecture, onboarding, API, gates, presentation
├── .github/workflows/            # CI: build + test + OWASP dependency-check
├── src/main/java/com/carddemo/
│   ├── CardDemoApplication.java  # Spring Boot bootstrap
│   ├── config/                   # SecurityConfig, BatchConfig, AwsConfig, JpaConfig, ObservabilityConfig, WebConfig
│   ├── model/                    # entity/ (11 JPA entities) · dto/ · enums/ · key/ (composite keys)
│   ├── repository/               # 11 Spring Data JPA repositories (one per VSAM dataset)
│   ├── service/                  # auth, account, card, transaction, billing, report, admin, menu, shared
│   ├── controller/               # 8 REST controllers (← BMS mapsets)
│   ├── batch/                    # jobs/ · processors/ · readers/ · writers/ (← JCL + batch COBOL)
│   ├── exception/                # CardDemoException base + subclasses (← FILE STATUS codes)
│   └── observability/            # Correlation-ID filter, metrics, health indicators
├── src/main/resources/
│   ├── application.yml           # + application-local.yml, application-test.yml
│   ├── db/migration/             # V1__create_schema.sql, V2__create_indexes.sql, V3__seed_data.sql
│   ├── validation/               # NANPA / state / ZIP reference data (JSON)
│   └── logback-spring.xml        # Structured JSON logging
└── src/test/java/com/carddemo/
    ├── unit/                     # service, batch, validation unit tests
    ├── integration/              # repository, batch, aws (LocalStack) integration tests
    └── e2e/                      # batch pipeline + online transaction + gate-verification tests
```

The base Java package is **`com.carddemo`** across the main and test source trees.

---

## Application Inventory

The migrated functions map one-to-one to the source application's closed feature set
(F-001..F-022). The tables below echo the source CardDemo inventory while documenting the Java
target.

### Online Functions (REST)

Each online CICS program becomes a service class (encapsulating its business logic) exposed through a
REST controller. BMS symbolic maps become JSON request/response DTOs.

| Source program | Java service | REST endpoint | Function |
| :------------- | :----------- | :------------ | :------- |
| `COSGN00C` | `AuthenticationService` | `POST /api/auth/signin` | Sign-on (BCrypt + JWT issuance) |
| `COMEN01C` | `MainMenuService` | `GET /api/menu/main` | Main menu (10-option routing) |
| `COADM01C` | `AdminMenuService` | `GET /api/menu/admin` | Admin menu (4-option routing) |
| `COACTVWC` | `AccountViewService` | `GET /api/accounts/{id}` | Account view (multi-dataset read) |
| `COACTUPC` | `AccountUpdateService` | `PUT /api/accounts/{id}` | Account update (`@Transactional` + `@Version`) |
| `COCRDLIC` | `CardListService` | `GET /api/cards` | Card list (paginated, 7 rows/page) |
| `COCRDSLC` | `CardDetailService` | `GET /api/cards/{id}` | Card detail (single keyed read) |
| `COCRDUPC` | `CardUpdateService` | `PUT /api/cards/{id}` | Card update (optimistic locking) |
| `COTRN00C` | `TransactionListService` | `GET /api/transactions` | Transaction list (paginated, 10 rows/page) |
| `COTRN01C` | `TransactionDetailService` | `GET /api/transactions/{id}` | Transaction detail |
| `COTRN02C` | `TransactionAddService` | `POST /api/transactions` | Transaction add (auto-ID generation) |
| `CORPT00C` | `ReportSubmissionService` | `POST /api/reports/submit` | Report submission (TDQ → SQS bridge) |
| `COBIL00C` | `BillPaymentService` | `POST /api/billing/pay` | Bill payment |
| `COUSR00C` | `UserListService` | `GET /api/admin/users` | List users |
| `COUSR01C` | `UserAddService` | `POST /api/admin/users` | Add user |
| `COUSR02C` | `UserUpdateService` | `PUT /api/admin/users/{id}` | Update user |
| `COUSR03C` | `UserDeleteService` | `DELETE /api/admin/users/{id}` | Delete user |

Full request/response contracts are documented in [`docs/api-contracts.md`](docs/api-contracts.md).

### Batch Pipeline

The JCL job streams are re-hosted as Spring Batch jobs, orchestrated into a 5-stage pipeline that
preserves predecessor-success ordering and condition-code logic.

| Stage | Source (JCL + COBOL) | Spring Batch job | Function |
| :---- | :------------------- | :--------------- | :------- |
| 1 | `POSTTRAN.jcl` + `CBTRN02C` | `DailyTransactionPostingJob` | Daily posting; 4-stage validation cascade; reject codes 100–109 |
| 2 | `INTCALC.jcl` + `CBACT04C` | `InterestCalculationJob` | Interest calculation (`BigDecimal`, `HALF_EVEN`) |
| 3 | `COMBTRAN.jcl` | `CombineTransactionsJob` | `Comparator` sort + bulk insert (replaces DFSORT + IDCAMS REPRO) |
| 4a | `CREASTMT.JCL` + `CBSTM03A`/`B` | `StatementGenerationJob` | Statement generation (text + HTML → S3) |
| 4b | `TRANREPT.jcl` + `CBTRN03C` | `TransactionReportJob` | Date-filtered transaction report → S3 |

The 5-stage flow runs `POSTTRAN → INTCALC → COMBTRAN → {CREASTMT, TRANREPT}`, where stages 4a and 4b
may run concurrently after `COMBTRAN` via a Spring Batch `FlowBuilder.split()`.

---

## AWS / LocalStack

Every AWS interaction runs against **LocalStack** locally — there are **no live AWS dependencies** and
no real AWS account or credentials are required. The `localstack-init/init-aws.sh` hook provisions the
following resources on startup:

| Resource | Name | Purpose |
| :------- | :--- | :------ |
| S3 bucket | `carddemo-batch-input` | Batch input / staging (GDG equivalent) |
| S3 bucket | `carddemo-batch-output` | Batch output and rejection files |
| S3 bucket | `carddemo-statements` | Generated statements (text + HTML) |
| SQS FIFO queue | `carddemo-report-jobs.fifo` | Report-submission queue (CICS TDQ replacement) |

The LocalStack endpoint is **`http://localhost:4566`**, configured by the `local` and `test` profiles.
The default Compose stack uses the LocalStack **Community** image (`localstack/localstack:3.8`), which
requires **no** auth token — `docker-compose.yml` passes an empty default and the Community image
ignores it. A token is needed **only** when you override to a LocalStack **Pro** image; supply it from
your shell or secret store — it is **never** hardcoded:

```bash
# Optional — only when overriding to the LocalStack Pro image:
export LOCALSTACK_IMAGE=localstack/localstack-pro:latest
export LOCALSTACK_AUTH_TOKEN=<your-localstack-auth-token>
```

Integration tests self-provision and tear down their own buckets, queues, and topics, so the suite is
fully reproducible from a clean state.

---

## Observability

Observability ships with the application rather than as a follow-up. Once `docker compose up -d` is
running, the following are available:

| Capability | Endpoint / Tool | Notes |
| :--------- | :-------------- | :---- |
| Health & readiness | `http://localhost:8080/actuator/health` | Composite indicators for PostgreSQL, S3, and SQS |
| Metrics (Prometheus) | `http://localhost:8080/actuator/prometheus` | Custom metrics: `carddemo.batch.records.processed`, `carddemo.batch.records.rejected`, `carddemo.auth.attempts`, `carddemo.transaction.amount.total` |
| Distributed tracing | Jaeger UI | Micrometer Tracing + OpenTelemetry, exported via OTLP |
| Structured logging | JSON to stdout | `logstash-logback-encoder` with `traceId`, `spanId`, and a custom `correlationId` |
| Dashboard | Grafana | Import the template at [`docs/grafana-dashboard.json`](docs/grafana-dashboard.json) |

A `CorrelationIdFilter` injects a correlation ID into the MDC on each request and propagates it
through the service and batch layers.

---

## Testing

| Suite | Command | Scope |
| :---- | :------ | :---- |
| Unit | `./mvnw test` | Service, batch, and validation logic (Surefire) |
| Build + unit | `./mvnw clean verify` | Compile, unit tests, coverage, and quality checks |
| Integration | `./mvnw verify -Pintegration` | Repository, batch, and AWS tests via Testcontainers + LocalStack (Failsafe) |

Integration tests use Testcontainers to spin up real PostgreSQL and LocalStack containers, so a
running Docker daemon is required.

---

## Quality Gates

The build enforces the project's quality bar (full evidence in
[`docs/validation-gates.md`](docs/validation-gates.md)):

- **Zero-warning build** — `./mvnw clean verify` passes with no warnings or suppressions (Gate 2).
- **Coverage** — JaCoCo enforces **≥ 80% line coverage** across all packages (Gate 8).
- **Security** — OWASP `dependency-check-maven` reports **zero critical/high CVEs** across direct and
  transitive dependencies (Gate 8).
- **8-gate validation** — end-to-end boundary, zero-warning build, performance baseline, named
  real-world artifacts, contract verification, unsafe-code audit, scope matching, and integration
  sign-off. See [`docs/validation-gates.md`](docs/validation-gates.md) for the per-gate evidence.

---

## Documentation Index

| Document | Description |
| :------- | :---------- |
| [`docs/onboarding-guide.md`](docs/onboarding-guide.md) | Clean-machine-to-running guide, domain context, pitfalls, and extension guides |
| [`docs/architecture-before-after.md`](docs/architecture-before-after.md) | Mermaid before/after architecture diagrams |
| [`docs/api-contracts.md`](docs/api-contracts.md) | REST, SQS, and S3 contract documentation |
| [`docs/validation-gates.md`](docs/validation-gates.md) | Evidence for the 8 validation gates |
| [`docs/executive-presentation.html`](docs/executive-presentation.html) | Self-contained reveal.js deck for leadership |
| [`docs/grafana-dashboard.json`](docs/grafana-dashboard.json) | Grafana dashboard template |
| [`DECISION_LOG.md`](DECISION_LOG.md) | Rationale, alternatives, and risks for every non-trivial decision |
| [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) | Bidirectional COBOL ↔ Java mapping with 100% paragraph coverage |

---

## Traceability & Parity

This project achieves **100% behavioral parity** with the source application: every COBOL paragraph
produces identical output for identical input, and external interface contracts (file layouts, message
schemas, batch triggers) are preserved exactly. Because the COBOL sources are not copied here,
traceability is anchored to the original repository commit SHA **`27d6c6f`**
(`CardDemo_v1.0-15-g27d6c6f-68`). [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) maps every
paragraph across all 28 programs to its Java class and method, and back.

Representative mappings: `COSGN00C.PROCESS-ENTER-KEY` → `AuthenticationService.authenticate()`;
`COACTUPC.PROCESS-UPDATE-ACCT` → `AccountUpdateService.updateAccount()`;
`CBTRN02C.2000-VALIDATE-TXN` → `TransactionPostingProcessor.validate()`.

---

## Contributing

Contributions and enhancements are welcome. Please:

1. Read [`docs/onboarding-guide.md`](docs/onboarding-guide.md) to get from a clean machine to a running
   application.
2. Keep changes within the migrated closed feature set (F-001..F-022) unless a new scope is explicitly
   agreed — this project intentionally avoids feature expansion.
3. Ensure `./mvnw clean verify` passes (zero warnings, ≥ 80% coverage) before raising a merge request.
4. Record any non-trivial design decision in [`DECISION_LOG.md`](DECISION_LOG.md) rather than in code
   comments.

---

## License

This project is released under the **Apache License 2.0**, consistent with the source CardDemo
application. See the repository `LICENSE` file for the full text.
