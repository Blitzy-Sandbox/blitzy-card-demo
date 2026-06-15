# CardDemo — Java Edition

> A behavioral-parity migration of the AWS **CardDemo** mainframe application
> (COBOL / CICS / VSAM / JCL / BMS) to an idiomatic **Java 25 LTS + Spring Boot 3.x**
> service. The legacy 3270 online transactions are re-expressed as stateless **REST**
> endpoints and the JCL batch suite as **Spring Batch** jobs, over a **PostgreSQL**
> data layer with **AWS S3/SQS/SNS** integration.

CardDemo is a **credit-card management application**. It lets users manage **accounts**,
**credit cards**, **transactions**, and **bill payments**, and it provides administrative
**user management**. This repository is the modernized Java implementation of that system:
it reproduces the original COBOL behavior exactly (100% behavioral parity), changing only
the underlying technology — not the business rules.

This codebase is a **greenfield project**. The original COBOL/CICS/VSAM/JCL/BMS sources are
**not** copied into this repository; traceability to the frozen legacy baseline is by the
original CardDemo repository commit SHA **`27d6c6f`**. The only intentional behavioral change
is that legacy plaintext passwords are upgraded to **BCrypt** hashes (the login flow itself
is preserved).

---

## Table of contents

- [Overview](#overview)
- [Technology stack](#technology-stack)
- [Architecture overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [Service ports](#service-ports)
- [Environment variables](#environment-variables)
- [Sample credentials](#sample-credentials)
- [Functional inventory](#functional-inventory)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Onboarding and common pitfalls](#onboarding-and-common-pitfalls)
- [Behavioral parity and traceability](#behavioral-parity-and-traceability)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

The application serves two kinds of users, exactly as the mainframe original did:

- **Regular User** — performs the back-office card functions: view/update accounts, list and
  maintain credit cards, browse/add transactions, pay bills, and submit transaction reports.
- **Admin User** — performs user-administration functions: list, add, update, and delete users.

The legacy **Application Inventory** is preserved in full but re-expressed for the target stack:

- The **online** CICS transactions (Sign-On, Main Menu, Admin Menu, and the account / card /
  transaction / billing / report / user-admin screens) become **REST endpoints** under `/api`.
- The **batch** JCL jobs (transaction posting, interest calculation, transaction combine,
  statement generation, and transaction reporting) become **Spring Batch** jobs.

See the [Functional inventory](#functional-inventory) for the full transaction-to-endpoint and
job mapping **and the per-feature implementation status** (all seventeen online endpoints —
including the report-submission endpoint — and the full five-stage business Spring Batch pipeline
are implemented and covered by tests in the current build), and
[`docs/api-contracts.md`](docs/api-contracts.md) for the authoritative, field-by-field REST
contract derived from the original BMS screens.

---

## Technology stack

| Layer | Technology | Version | Replaces (legacy) |
|---|---|---|---|
| Language / runtime | Java (OpenJDK) LTS | 25.0.2 | COBOL |
| Application framework | Spring Boot | 3.5.15 | CICS online region |
| Web / API | Spring MVC (REST) + Jakarta Validation | 3.5.x (BOM) | BMS 3270 maps |
| Persistence | Spring Data JPA / Hibernate | 3.5.x / 6.x | VSAM KSDS + access |
| Batch | Spring Batch | 5.x (BOM) | JCL jobs / JES |
| Security | Spring Security (BCrypt) | 6.x (BOM) | RACF / plaintext `USRSEC` |
| Database | PostgreSQL | 16 | VSAM datasets |
| Schema migration | Flyway | 11.x (BOM) | IDCAMS `DEFINE CLUSTER` |
| Cloud (S3 / SQS / SNS) | Spring Cloud AWS | 3.3.0 | GDG / CICS TDQ |
| Observability | Micrometer + OpenTelemetry | 1.6.x / 1.x | (no legacy peer) |
| AWS emulation (local) | LocalStack Pro | latest | (no legacy peer) |
| Testing | JUnit 5 + Testcontainers | 2.0.3 | manual mainframe test |
| Build | Maven (+ `mvnw` wrapper) | 3.9.9 | `BUILDBAT`/`BUILDBMS`/`BUILDONL` JCL |
| Containerization | Docker / Docker Compose | 28.x | (no legacy peer) |

> The complete, version-pinned dependency closure is the single source of truth in
> [`pom.xml`](pom.xml). All decimal money fields use `java.math.BigDecimal` — never
> `float`/`double` — to preserve COBOL `COMP-3` precision.

---

## Architecture overview

The system follows a classic layered architecture — **Controller → Service → Repository →
Entity** — which replaces the monolithic COBOL program-per-screen model. Each online COBOL
program's business logic becomes a Spring `@Service`; each VSAM dataset becomes a JPA
`@Entity` with a Spring Data `JpaRepository`; each BMS screen contract becomes request/response
**DTOs** validated with Jakarta Validation; and each JCL job becomes a Spring Batch
`Job`/`Step`/`Flow`. The headline technology substitutions are:

- **VSAM KSDS → PostgreSQL** tables (via Spring Data JPA), preserving primary, composite, and
  alternate-index access patterns.
- **CICS TDQ → AWS SQS** for the single online→batch bridge (report submission).
- **GDG generations → AWS S3** versioned objects for batch file staging and statements.
- **BMS 3270 maps → REST DTOs** that preserve the original field names, lengths, and rules.
- **CICS `RETURN TRANSID COMMAREA` → stateless REST** with a signed token carrying context.

For before/after diagrams of every architectural aspect, see
[`docs/architecture-before-after.md`](docs/architecture-before-after.md).

---

## Prerequisites

A clean machine needs only the following to build, run, and test the application:

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 (OpenJDK or Eclipse Temurin) | Compilation and runtime. If Java 25 is not your default, set `JAVA_HOME` (e.g. `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`). |
| Maven | 3.9.9+ | Optional — the bundled wrapper [`./mvnw`](mvnw) downloads and runs Maven 3.9.9 for you. |
| Docker | 28.x+ | Container runtime for PostgreSQL, LocalStack, and the observability stack. |
| Docker Compose | v2 plugin | Invoked as `docker compose` (not the legacy `docker-compose`). |
| AWS CLI | optional | Only for manually inspecting LocalStack S3/SQS/SNS resources. |
| `jq` | optional | Only for the sign-in token-capture snippet in [Sample credentials](#sample-credentials) (which pipes the JSON response through `jq -r '.token'`); any JSON tool works. |

> Integration and end-to-end tests use **Testcontainers**, which requires a running Docker
> daemon and access to the Docker socket.

---

## Quick start

All commands are run from the project directory (`carddemo-java/`).

**1. Clone and enter the project:**

```bash
git clone <repository-url>
cd carddemo-java
```

**2. Build and run the unit tests (no Docker required):**

```bash
./mvnw clean test
```

This compiles the project and runs the **unit**-test suite (no Docker required). It does **not**
run the Docker-dependent integration/E2E tests, and it does **not** enforce the **≥ 80 % JaCoCo
line-coverage gate** or generate the coverage report — both the gate and the report are bound to
the `verify` phase and are exercised by the full build in **step 5** (`./mvnw verify -Pintegration`,
which needs Docker). To produce the runnable executable JAR without Docker, run `./mvnw clean
package` (it also stops short of the coverage gate).

> **Why not `./mvnw clean verify` here?** `verify` runs the JaCoCo coverage **check**, and
> unit-only coverage is below the 80 % gate (the gate is met only once the integration/E2E tests
> contribute their coverage under `-Pintegration`). So the canonical no-Docker command is
> `./mvnw clean test`; the canonical full, coverage-gated verification is
> `./mvnw verify -Pintegration` (step 5).

Then run the application using **one** of the two modes below.

> **Prerequisite for both modes — set the two required `docker compose` variables.** Both modes
> start the `localstack` service, which runs LocalStack **Pro** (`localstack/localstack-pro`) and
> has **no default activation token**; in addition, the Compose file requires
> `CARDDEMO_SECURITY_TOKEN_SECRET` (the JWT signing secret, **≥ 256-bit / 32 bytes**) which also
> has **no default**. Export **both before** any `docker compose` command — without them
> `docker compose config`/`up` fails fast with `required variable … is missing a value`:
>
> ```bash
> export LOCALSTACK_AUTH_TOKEN=<your-localstack-pro-token>
> export CARDDEMO_SECURITY_TOKEN_SECRET=$(openssl rand -base64 48)   # any random ≥256-bit value
> ```
>
> See the [Environment variables](#environment-variables) section and
> [`.env.example`](.env.example) for the complete required set. Building and unit-testing in
> step 2 (`./mvnw clean test`) needs no environment variables.

**3a. Mode A — Fully containerized (simplest first run):**

```bash
# Builds the application image and starts all six services:
# postgres, localstack, jaeger, prometheus, grafana, and the app (port 8080).
docker compose up -d
```

**3b. Mode B — Run the app from source (recommended for active development):**

```bash
# Start only the backing services the app needs to boot — no app container,
# so there is no clash on port 8080 with the command below.
docker compose up -d postgres localstack jaeger

# Run the app on the host with the `local` profile. Its default datasource, AWS,
# and OTLP endpoints point at localhost, matching the published container ports.
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**4. Verify the application is healthy:**

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP", ...}
```

**5. Run the full integration + E2E suite (requires a running Docker daemon):**

```bash
./mvnw verify -Pintegration
```

The `integration` profile flips Failsafe on so the `*IT` integration and `e2e` tests run;
Testcontainers provisions its own throwaway PostgreSQL and LocalStack containers, so this works
whether or not `docker compose` is up.

**Tear down** the local stack (and remove its volumes) when you are done:

```bash
docker compose down -v
```

> **Mode A vs. Mode B.** Prometheus and Grafana scrape/visualize the app over the Docker bridge
> network, so they are part of the fully-containerized **Mode A** stack. In **Mode B** you run
> the app from source for fast iteration while PostgreSQL, LocalStack, and Jaeger run in Docker;
> add `prometheus grafana` to the `docker compose up` list only if you also run the app in a
> container, since those services depend on the `app` service.

---

## Service ports

The local Docker Compose stack exposes the following ports on `localhost`:

| Port | Service | Protocol | URL |
|---|---|---|---|
| 8080 | CardDemo application (REST + Actuator) | HTTP | http://localhost:8080 |
| 5432 | PostgreSQL 16 | TCP | `jdbc:postgresql://localhost:5432/carddemo` |
| 4566 | LocalStack (S3, SQS, SNS) | HTTP | http://localhost:4566 |
| 16686 | Jaeger UI (distributed tracing) | HTTP | http://localhost:16686 |
| 4317 | Jaeger OTLP ingest (gRPC) | gRPC | — |
| 4318 | Jaeger OTLP ingest (HTTP) | HTTP | http://localhost:4318 |
| 9090 | Prometheus (metrics server) | HTTP | http://localhost:9090 |
| 3000 | Grafana (dashboards) | HTTP | http://localhost:3000 |

Operational endpoints on the application: health probe `GET /actuator/health`, Prometheus
scrape `GET /actuator/prometheus`. Grafana's default local credentials are `admin` / `admin`.

---

## Environment variables

No environment variables are required to **build and unit-test** the project
(`./mvnw clean test`) or to run the integration + E2E suite (`./mvnw verify -Pintegration`) —
Testcontainers provisions its own throwaway PostgreSQL and LocalStack containers for the latter.

**Two `docker compose` values are required and have no default.** First, the Compose data plane
runs LocalStack **Pro** (`localstack/localstack-pro`), which has **no default activation token**,
so you must export a valid `LOCALSTACK_AUTH_TOKEN`. Second, the application service requires
`CARDDEMO_SECURITY_TOKEN_SECRET` (the JWT signing secret, **≥ 256-bit**), declared in
`docker-compose.yml` with **no default** (`${CARDDEMO_SECURITY_TOKEN_SECRET:?…}`). Export **both
before** any `docker compose` command — `config`, `up`, or even a partial `up` such as Mode B's
`postgres localstack jaeger` — because Compose interpolates the whole file, so a missing value
fails fast with `required variable … is missing a value`. See [`.env.example`](.env.example) for
the complete required set. Every other Compose value has a clearly non-secret local-dev default.
Secrets are supplied at runtime via the shell or an **uncommitted** `.env` file and are **never**
committed to the repository.

| Variable | Secret? | Default | Purpose |
|---|---|---|---|
| `AWS_ACCESS_KEY_ID` | No | `test` | AWS SDK access key id (LocalStack ignores the value but requires presence). |
| `AWS_DEFAULT_REGION` | No | `us-east-1` | AWS region for the S3 / SQS / SNS clients. |
| `AWS_SECRET_ACCESS_KEY` | **Yes** | `test` (local only) | AWS SDK secret key. Supply a real value only against real AWS. |
| `LOCALSTACK_AUTH_TOKEN` | **Yes** | _(none)_ | LocalStack **Pro** activation token. Required for the LocalStack container; has no default. |
| `CARDDEMO_SECURITY_TOKEN_SECRET` | **Yes** | _(none)_ | JWT signing secret (**≥ 256-bit / 32 bytes**). **Required by `docker compose`** (declared `${…:?}`, no default); any `docker compose` command fails fast without it. The app's `local` profile has an inline dev default, so it is only mandatory on the Compose path. |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | No | `carddemo` | PostgreSQL database name and credentials (Compose + app share these). |
| `SPRING_PROFILES_ACTIVE` | No | _(unset)_ | Active Spring profile; the containerized app sets `local`. |
| `SERVER_PORT` | No | `8080` | Application HTTP port. |

> **Secret policy.** Per the migration's preservation rules, there are no hardcoded credentials
> anywhere in the codebase — secrets are environment-variable or vault references only. The
> inline Docker Compose defaults are obviously non-secret local-dev values.

---

## Sample credentials

The Flyway seed (`db/migration/V3__seed_data.sql`) loads the original CardDemo users. Passwords
were migrated from legacy plaintext to **BCrypt** hashes that verify against the original login
secret, so the credentials below are unchanged from the mainframe demo:

| User ID | Password | Role | Use |
|---|---|---|---|
| `ADMIN001` | `PASSWORD` | Admin User | User-administration functions |
| `USER0001` | `PASSWORD` | Regular User | Back-office card functions |

Authenticate to obtain a token, then pass it as a bearer token on every subsequent call. All
endpoints except `POST /api/auth/signin` and the `/actuator/**` probes are protected by
`SecurityConfig`, so a request to a protected route **without** a valid
`Authorization: Bearer <token>` header returns `401 Unauthorized`:

```bash
# 1. Sign in and capture the bearer token from the JSON response (the `token` field)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId":"USER0001","password":"PASSWORD"}' | jq -r '.token')

# 2. Call a protected endpoint with the bearer token (returns the main-menu JSON)
curl -s http://localhost:8080/api/menu/main \
  -H "Authorization: Bearer ${TOKEN}"
```

---


## Functional inventory

### Online transactions → REST endpoints

Each legacy CICS transaction starts a COBOL program that owns one BMS screen. In the Java target
the CICS transaction registry is replaced by Spring component scanning and the `@RequestMapping`
route table below. All paths are prefixed with `/api` and exchange `application/json`.

| CICS Txn | Legacy program | Function | REST endpoint |
|---|---|---|---|
| `CC00` | `COSGN00C` | Sign-on | `POST /api/auth/signin` |
| `CM00` | `COMEN01C` | Main menu | `GET /api/menu/main` |
| `CA00` | `COADM01C` | Admin menu | `GET /api/menu/admin` |
| `CAVW` | `COACTVWC` | Account view | `GET /api/accounts/{id}` |
| `CAUP` | `COACTUPC` | Account update | `PUT /api/accounts/{id}` |
| `CCLI` | `COCRDLIC` | Credit-card list | `GET /api/cards` |
| `CCDL` | `COCRDSLC` | Credit-card view | `GET /api/cards/{cardNumber}` |
| `CCUP` | `COCRDUPC` | Credit-card update | `PUT /api/cards/{cardNumber}` |
| `CT00` | `COTRN00C` | Transaction list | `GET /api/transactions` |
| `CT01` | `COTRN01C` | Transaction view | `GET /api/transactions/{id}` |
| `CT02` | `COTRN02C` | Transaction add | `POST /api/transactions` |
| `CB00` | `COBIL00C` | Bill payment | `POST /api/billing/pay` |
| `CR00` | `CORPT00C` | Transaction reports | `POST /api/reports/submit` |
| `CU00` | `COUSR00C` | List users | `GET /api/admin/users` |
| `CU01` | `COUSR01C` | Add user | `POST /api/admin/users` |
| `CU02` | `COUSR02C` | Update user | `PUT /api/admin/users/{id}` |
| `CU03` | `COUSR03C` | Delete user | `DELETE /api/admin/users/{id}` |

> **Transaction count.** The frozen baseline at commit `27d6c6f` contains exactly **17** online
> programs, BMS mapsets, and transaction IDs (3 entry points + 10 main-menu functions + 4 admin
> functions) — the authoritative count. The full field-by-field request/response contract for
> every endpoint lives in [`docs/api-contracts.md`](docs/api-contracts.md).

> **Implementation status.** All seventeen endpoints above are implemented and covered by tests.
> The report-submission endpoint `POST /api/reports/submit` (`CR00` / `CORPT00C`) is implemented by
> `ReportController` and `ReportSubmissionService` — the CICS TDQ → SQS bridge — which publishes the
> report request to the `carddemo-report-jobs.fifo` SQS queue and is exercised end-to-end against
> LocalStack.

### Batch jobs → Spring Batch

The legacy data-load JCL (account/card/customer/cross-reference/transaction loads) is replaced by
**Flyway** migrations that create and seed PostgreSQL on startup (`V1` schema → `V2` indexes →
`V3` seed → `V4` `user_type` NOT-NULL enforcement → `V5` Spring Batch metadata). GDG provisioning
becomes S3 buckets and the `USRSEC` load becomes the seeded `user_security` table. This data-load
replacement is implemented in the current build.

> **Implementation status.** The business batch pipeline described below is **implemented and
> covered by tests** in the current build — all five Spring Batch jobs and the
> `BatchPipelineOrchestrator` exist and are exercised by integration and end-to-end tests
> (`BatchPipelineE2ETest`, `BatchPipelineOrchestratorIT`).

The business pipeline runs as a sequential 5-stage Spring Batch flow:

| Stage | Legacy JCL / program | Spring Batch job | Function |
|---|---|---|---|
| 1 | `POSTTRAN` / `CBTRN02C` | `DailyTransactionPostingJob` | Validate and post daily transactions |
| 2 | `INTCALC` / `CBACT04C` | `InterestCalculationJob` | Interest accrual (rate lookup + formula) |
| 3 | `COMBTRAN` / DFSORT | `CombineTransactionsJob` | Merge system + daily transactions (Java sort + bulk insert) |
| 4a | `CREASTMT` / `CBSTM03A`,`CBSTM03B` | `StatementGenerationJob` | Produce account statements (text + HTML → S3) |
| 4b | `TRANREPT` / `CBTRN03C` | `TransactionReportJob` | Date-filtered transaction report → S3 |

Stage ordering and JCL `COND`-code logic are preserved by the `BatchPipelineOrchestrator`
(`POSTTRAN → INTCALC → COMBTRAN → CREASTMT / TRANREPT`); stages 4a and 4b run in parallel after
stage 3. The interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` is reproduced with
`BigDecimal` and banker's rounding, without algebraic rearrangement.

---

## Project layout

```
carddemo-java/
├── pom.xml                     # Maven build — the single source of truth for dependencies
├── mvnw / mvnw.cmd / .mvn/      # Maven wrapper (pins Maven 3.9.9)
├── Dockerfile                  # Multi-stage Java 25 image for the app service
├── docker-compose.yml          # Local stack: postgres, localstack, jaeger, prometheus, grafana, app
├── prometheus.yml              # Prometheus scrape config (targets app:8080)
├── DECISION_LOG.md             # Non-trivial architectural decisions and rationale
├── TRACEABILITY_MATRIX.md      # COBOL paragraph → Java method bidirectional mapping
├── docs/                       # API contracts, architecture diagrams, onboarding, validation gates
└── src/
    ├── main/java/com/cardemo/  # CardDemoApplication + config, model, repository, service,
    │                           #   controller, batch, exception, observability, security
    ├── main/resources/         # application*.yml, db/migration (Flyway V1–V5), validation/, logback
    └── test/java/com/cardemo/  # unit / integration / e2e tests (Testcontainers + LocalStack)
```

---

## Documentation

| Document | Description |
|---|---|
| [`docs/onboarding-guide.md`](docs/onboarding-guide.md) | Clean machine → running app, domain context, how to extend, suggested next tasks. |
| [`docs/api-contracts.md`](docs/api-contracts.md) | Authoritative, field-by-field REST endpoint specifications. |
| [`docs/architecture-before-after.md`](docs/architecture-before-after.md) | Mermaid before/after diagrams for every architectural aspect. |
| [`docs/validation-gates.md`](docs/validation-gates.md) | Quality-gate evidence (build, coverage, OWASP, parity). |
| [`docs/executive-presentation.html`](docs/executive-presentation.html) | reveal.js executive summary of the migration. |
| [`DECISION_LOG.md`](DECISION_LOG.md) | The single source of truth for non-trivial decisions. |
| [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) | 100% COBOL-paragraph coverage, mapped to the Java class that reproduces it (with a representative method label per paragraph). |

---

## Onboarding and common pitfalls

New to the project? Start with [`docs/onboarding-guide.md`](docs/onboarding-guide.md) — it takes
you from a clean machine to a running application and explains how to add a new entity, batch job,
or API endpoint. A few traps that catch newcomers:

- **Decimal precision.** Every money/rate field is a `BigDecimal` with a fixed scale; compare
  values with `compareTo()`, never `equals()` (which is scale-sensitive), and never substitute
  `float`/`double`.
- **LocalStack S3 path-style access.** S3 calls must use path-style URLs
  (`http://localhost:4566/<bucket>/<key>`); this is enabled in configuration — do not switch to
  virtual-host style.
- **Testcontainers Docker socket.** Integration tests need a running Docker daemon and permission
  to access the Docker socket, or they fail with "connection refused".
- **Flyway migration ordering.** Migrations apply in version order (`V1` → `V2` → `V3` → `V4` →
  `V5`) on startup; never edit an already-applied migration — add a new versioned script instead.

---

## Behavioral parity and traceability

This project is governed by a **Minimal Change Clause**: make only the changes necessary for the
technology transition, preserve existing behavior exactly, and do not enhance or optimize beyond
migration requirements. Concretely:

- **100% behavioral parity** — every COBOL paragraph produces identical output for identical
  input; the 9 ASCII data fixtures serve as the golden parity dataset.
- **External interface contracts preserved** — field layouts, lengths, and validation rules are
  carried over unchanged into the REST DTOs.
- **No feature expansion** — only the original documented features are implemented; no new
  endpoints, rules, or entities.
- **COBOL sources are not copied** — traceability to the frozen legacy baseline is by the original
  CardDemo repository commit SHA **`27d6c6f`**.
- **Single permitted behavioral change** — legacy plaintext passwords are upgraded to **BCrypt**
  hashes; the sign-on flow is otherwise preserved.

---

## Contributing

Contributions are welcome. Please open an issue to discuss a change, keep pull requests focused
and aligned with the Minimal Change Clause above, and ensure `./mvnw clean test` (unit tests, no
Docker) and `./mvnw verify -Pintegration` (the full, coverage-gated build; Docker required) pass
before submitting. See
[`CONTRIBUTING.md`](../CONTRIBUTING.md) and [`CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md) in the
repository root.

---

## License

Released under the **Apache License 2.0** — see the repository [`LICENSE`](../LICENSE) file.

