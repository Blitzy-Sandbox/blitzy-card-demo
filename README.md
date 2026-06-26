# CardDemo — Java / Spring Boot Edition

> The modernized migration of the **AWS CardDemo** mainframe credit-card management
> application — re-platformed from **COBOL / CICS / VSAM / JCL** to a cloud-native
> **Java 25 LTS + Spring Boot 3.5.11** service targeting **100% behavioral parity**.

CardDemo is a credit-card management system covering **Account**, **Card**,
**Transaction**, **Billing**, **Reporting**, and **User Administration**. This
repository is the greenfield Java implementation produced by migrating the legacy
mainframe application across all **22 features (F-001 – F-022)** with no feature
expansion. The migration translates the CICS pseudo-conversational online programs
into stateless REST endpoints, the JCL batch pipeline into Spring Batch jobs, and
the VSAM datasets into a PostgreSQL relational schema — while preserving the exact
business behavior, record layouts, and decimal precision of the original system.

The legacy COBOL/CICS/JCL corpus is retained **only as a frozen reference** under
[`app/`](app/) and is never modified or copied; traceability back to the source is
maintained exclusively through commit SHA **`27d6c6f`** (see
[`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md)). The target system is **headless —
it exposes a REST API only and has no web or browser UI**.

---

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Architecture Overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Build, Test & Quality Gates](#build-test--quality-gates)
- [Configuration & Profiles](#configuration--profiles)
- [REST API Overview](#rest-api-overview)
- [Batch Pipeline & Data](#batch-pipeline--data)
- [Observability](#observability)
- [Project Structure](#project-structure)
- [Migration Context](#migration-context)
- [Documentation](#documentation)
- [Common Pitfalls & Troubleshooting](#common-pitfalls--troubleshooting)
- [Contributing](#contributing)
- [License](#license)
- [Project Status](#project-status)

---

## Overview

The application serves the same credit-card management domain as the original
mainframe system, with two classes of user:

- **Regular users** perform back-office account, card, transaction, billing, and
  reporting functions.
- **Admin users** additionally manage the user registry (list / add / update /
  delete users).

Each legacy CICS transaction and BMS screen is preserved as an equivalent REST
operation, and each batch JCL job is preserved as a Spring Batch step, so that the
observable inputs and outputs match the COBOL baseline byte-for-byte. Key
modernization principles applied throughout the migration:

- **Decimal exactness** — every COBOL `COMP-3` / `PIC S9(n)V99` monetary field maps
  to `java.math.BigDecimal` (scale 2, `RoundingMode.HALF_EVEN`); `double` / `float`
  are never used for money.
- **Stateless sessions** — the CICS COMMAREA navigation model is replaced by
  stateless, JWT-secured REST requests with no server-side session.
- **Atomic units of work** — CICS `SYNCPOINT` and implicit batch commits become
  declarative `@Transactional` boundaries.
- **Optimistic concurrency** — the COBOL re-read-and-compare guard becomes JPA
  `@Version` optimistic locking.
- **Idiomatic Java** — clean, layered, dependency-injected object-oriented code,
  never a line-for-line transliteration of COBOL.

---

## Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Language / Runtime | Java (OpenJDK / Eclipse Temurin) | **25.0.2 LTS** |
| Application framework | Spring Boot (Web, Data JPA, Batch, Security, Validation, Actuator) | **3.5.11** |
| Persistence | PostgreSQL | **16** |
| Schema migration | Flyway (`flyway-core` + `flyway-database-postgresql`) | **11.x** (Boot-managed) |
| ORM / JPA provider | Hibernate (via Spring Data JPA) | **6.x** |
| Cloud integration | Spring Cloud AWS (S3 / SQS / SNS starters) | **3.3.0** |
| AWS emulation (local) | LocalStack (S3, SQS FIFO, SNS) | community `3.8.1` / Pro |
| Security | Spring Security 6 + BCrypt + JWT (`io.jsonwebtoken:jjwt`) | **6.x** |
| Tracing | Micrometer Tracing → OpenTelemetry (OTLP) → Jaeger | **1.6.x** (Boot-managed) |
| Metrics | Micrometer + Prometheus registry | (Boot-managed) |
| Logging | Logback structured JSON (`logstash-logback-encoder`) | 8.x |
| Testing | JUnit 5, Mockito, Testcontainers (PostgreSQL + LocalStack) | Testcontainers **2.0.3** |
| Coverage | JaCoCo (≥ 80% line coverage gate) | **0.8.14** |
| Security scan | OWASP `dependency-check-maven` (zero critical/high target) | **12.1.0** |
| Build | Apache Maven (via `./mvnw` wrapper) | **3.9.9** |
| Containers | Docker / Docker Compose | **28.x** |

> All versions are pinned in [`pom.xml`](pom.xml); see also the technology table in
> [`docs/project-guide.md`](docs/project-guide.md) (Appendix D).

---

## Architecture Overview

The application follows a conventional **layered architecture**, isolating the REST
presentation surface from business logic and persistence:

```
REST Client
     │  (HTTPS + JWT Bearer)
     ▼
┌──────────────────┐     ┌──────────────────┐     ┌────────────────────┐     ┌──────────────┐
│  8 REST          │ ──► │  ~20 Service     │ ──► │  11 Spring Data    │ ──► │  11 JPA      │
│  Controllers     │     │  classes         │     │  JPA Repositories  │     │  Entities    │
│  (+ 9 DTOs)      │ ◄── │  (business logic)│ ◄── │  (data access)     │ ◄── │  PostgreSQL  │
└──────────────────┘     └──────────────────┘     └────────────────────┘     └──────────────┘
```

- **Controllers** (`controller/`) — 8 REST controllers replace the 17 CICS/BMS online
  screens; request/response bodies are carried by 9 DTOs derived from the BMS symbolic
  maps so the field contracts match the legacy layouts exactly.
- **Services** (`service/`) — ~20 service classes encapsulate the business logic of each
  online program family plus shared utilities (`DateValidationService`,
  `ValidationLookupService`, `FileStatusMapper`).
- **Repositories** (`repository/`) — 11 Spring Data JPA repositories abstract all data
  access, replacing the VSAM `READ` / `REWRITE` / `STARTBR` mechanics.
- **Entities** (`entity/`) — 11 JPA entities (plus 3 composite-key classes and 1 attribute
  converter) map the VSAM record layouts onto 11 PostgreSQL tables.
- **Cross-cutting** — `config/` (Security, Batch, AWS, JPA, Observability, Web),
  `observability/` (correlation-id filter, metrics, health indicators), and `exception/`
  (7 typed exceptions + a `@ControllerAdvice` `GlobalExceptionHandler`).

### Batch Pipeline

The five JCL batch pipelines are replaced by a **5-stage Spring Batch pipeline** of
chunk-oriented `ItemReader → ItemProcessor → ItemWriter` steps, orchestrated with
condition-code logic equivalent to the JCL `COND` handling:

```
Stage 1            Stage 2            Stage 3            Stage 4a / 4b (parallel)
POSTTRAN    ──►    INTCALC     ──►    COMBTRAN    ──►    CREASTMT  (statement)
(post txns)        (interest)         (combine/sort)     TRANREPT  (date-window report)
```

| Stage | Job | Purpose |
|-------|-----|---------|
| 1 | **POSTTRAN** | Daily transaction posting with the 4-stage validation cascade |
| 2 | **INTCALC** | Interest calculation and posting |
| 3 | **COMBTRAN** | Combine + sort transactions (DFSORT replacement) |
| 4a | **CREASTMT** | Statement generation (text + HTML) |
| 4b | **TRANREPT** | Date-window transaction report (parallel with 4a) |

### Key Modernization Mappings

| Legacy (mainframe) | Target (Java / Spring) |
|--------------------|------------------------|
| CICS online screens (BMS) | REST endpoints (`@RestController`) |
| VSAM KSDS datasets | PostgreSQL tables (Spring Data JPA) |
| COMMAREA cross-screen state | Stateless JWT claims + request/response DTOs |
| `COMP-3` / packed decimal | `java.math.BigDecimal` (scale 2, `HALF_EVEN`) |
| CICS TDQ `JOBS` (report trigger) | AWS SQS FIFO `carddemo-report-jobs.fifo` |
| GDG generation datasets | AWS S3 versioned objects (3 buckets) |
| File-based `USRSEC` (plaintext) | Spring Security 6 + BCrypt + role-based access |
| JCL `EXEC PGM` / `DD` / `COND` | Spring Batch jobs / steps / flow conditions |
| CICS `SYNCPOINT` | `@Transactional(rollbackFor = Exception.class)` |
| COBOL `FILE STATUS` codes | Typed exception hierarchy + status enums |

### Visual Architecture Diagrams

The full before/after architecture is documented with Mermaid diagrams in
[`docs/architecture-before-after.md`](docs/architecture-before-after.md):

- **Diagram 1 — BEFORE: Legacy Mainframe** (`app/`, frozen reference @ `27d6c6f`)
- **Diagram 2 — AFTER: Java 25 + Spring Boot 3.5.11** (greenfield)
- **Diagram 3 — Contract-Preservation Linkages** (Legacy ↔ Target)
- **Diagram 4 — Report Submission Bridge & 4-Stage Posting Validation**


---

## Prerequisites

| Software | Version | Notes |
|----------|---------|-------|
| **JDK** | 25 (OpenJDK or Eclipse Temurin) | Set `JAVA_HOME`, e.g. `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` |
| **Docker** | 28.x+ | Container runtime for PostgreSQL, LocalStack, and the observability stack |
| **Docker Compose** | v2 plugin | Invoked as `docker compose` (not the legacy `docker-compose` script) |
| **Git** | 2.x+ | Version control |
| `LOCALSTACK_AUTH_TOKEN` | optional | Only needed if you switch the `localstack` service to a **Pro** image; the default community image needs no token |
| AWS CLI | optional | Only needed for manual S3/SQS/SNS inspection against LocalStack |

> **Maven is not required separately** — use the bundled wrapper `./mvnw`, which pins
> Maven **3.9.9**. The wrapper requires `JAVA_HOME` to point at JDK 25.

---

## Quick Start

From a clean machine to a running, queryable application:

**1. Clone the repository**

```bash
git clone <repository-url>
cd carddemo
```

**2. Verify Java 25**

```bash
java -version
# Expected: openjdk version "25.x.x"
# If JDK 25 is not the default:
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
```

**3. Start local infrastructure** (PostgreSQL, LocalStack, Jaeger, Prometheus, Grafana)

First create your local `.env` from the template. The Compose services **fail fast** if the
required secrets are missing, so this must happen *before* `docker compose up`:

```bash
cp .env.example .env
```

Edit `.env` and set the three required secrets:

- `POSTGRES_PASSWORD` — any local database password
- `JWT_SECRET` — a Base64 value of at least 32 bytes (e.g. `openssl rand -base64 48`)
- `GF_SECURITY_ADMIN_PASSWORD` — the local Grafana admin password

> `LOCALSTACK_AUTH_TOKEN` is **optional** and left empty by default. The Compose file uses
> the community image `localstack/localstack:3.8.1`, which needs no token. Only set a token
> if you switch the `localstack` service to a Pro image for Pro-only features.

Then start the stack:

```bash
docker compose up -d
```

On startup, [`localstack-init/init-aws.sh`](localstack-init/init-aws.sh) provisions the
AWS resources the application expects:

- **S3 buckets:** `carddemo-batch-input`, `carddemo-batch-output`, `carddemo-statements`
- **SQS FIFO queue:** `carddemo-report-jobs.fifo`
- **SNS topic:** `carddemo-notifications`

Verify the infrastructure is healthy:

```bash
# PostgreSQL
docker compose exec postgres pg_isready -U carddemo
# Expected: accepting connections

# LocalStack (S3 / SQS / SNS)
curl -s http://localhost:4566/_localstack/health | python3 -m json.tool
# Expected: {"services": {"s3": "available", "sqs": "available", "sns": "available"}}
```

**4. Run the application** (`local` profile, connecting to the Docker Compose services)

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local -B
```

Flyway applies the schema and seed migrations on first startup.

**5. Verify the application is up**

The application serves business endpoints on port `8080`, while Spring Boot Actuator
(health, metrics) is served on the separate **management port `9091`** (see `application.yml`
and `DECISION_LOG.md` D-033). When you run the app on the host via `./mvnw` (step 4), query
the management port directly:

```bash
curl -s http://localhost:9091/actuator/health | python3 -m json.tool
# Expected: {"status": "UP", "components": {"db": {"status": "UP"}, ...}}
```

> If you instead run the application inside Docker Compose, port `9091` is intentionally
> **not published** to the host. Query it from within the container network instead:
> `docker compose exec app curl -fsS http://localhost:9091/actuator/health`

### Seed Credentials

The seed data ([`V3__seed_data.sql`](src/main/resources/db/migration/V3__seed_data.sql))
loads two users for trying the API. Their original COBOL plaintext passwords are stored
**BCrypt-hashed** (security upgrade, constraint C-003):

| User ID | Role | Initial password |
|---------|------|------------------|
| `ADMIN001` | Admin | `PASSWORD` |
| `USER0001` | Regular | `PASSWORD` |

The legacy `CC00` sign-on transaction maps to `POST /api/auth/signin`, which returns a
JWT to present on subsequent requests:

```bash
curl -s -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}' | python3 -m json.tool
# Expected: 200 OK with a JWT token
```


---

## Build, Test & Quality Gates

All build commands use the Maven wrapper (`./mvnw`) and run in batch mode (`-B`). Prefix
with `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` if JDK 25 is not your default JVM.

**Compile** (zero-warning build, compiled with `-Xlint:all`):

```bash
./mvnw clean compile -B
```

**Run unit tests** (729 unit tests):

```bash
./mvnw test -B
```

**Run the full verification suite** — unit + integration + E2E + coverage (888 tests):

```bash
./mvnw verify -B
```

> Integration and E2E tests are **self-contained**: they spin up disposable
> **Testcontainers** for PostgreSQL and LocalStack and provision/clean up their own AWS
> resources, so **no live AWS account or credentials are required**.

**Run a single test class:**

```bash
./mvnw test -Dtest=AccountUpdateServiceTest -B
```

### Quality Gates

| Gate | Tool | Command | Target |
|------|------|---------|--------|
| **Coverage** | JaCoCo | `./mvnw verify -B` → `target/site/jacoco/index.html` | **≥ 80%** line coverage |
| **Dependency CVEs** | OWASP dependency-check | `./mvnw org.owasp:dependency-check-maven:check` | **0** critical/high (CVSS ≥ 7) |
| **Build warnings** | `-Xlint:all` compiler | `./mvnw clean compile -B` | **0** warnings (except framework-generated) |

The coverage report is written to `target/site/jacoco/index.html`. The build is configured
to fail on critical/high CVEs and on compiler warnings, enforcing the zero-warning and
coverage requirements.

### Build a Docker Image

A multi-stage [`Dockerfile`](Dockerfile) produces a runnable image:

```bash
docker build --network=host -t carddemo:latest .
```

The `docker compose` `app` service uses the `carddemo:latest` tag.


---

## Configuration & Profiles

Configuration is externalized through Spring profiles and YAML files under
[`src/main/resources/`](src/main/resources/):

| File | Purpose |
|------|---------|
| [`application.yml`](src/main/resources/application.yml) | Base configuration shared by all profiles |
| [`application-local.yml`](src/main/resources/application-local.yml) | `local` profile — local PostgreSQL + LocalStack endpoint `http://localhost:4566` |
| [`application-test.yml`](src/main/resources/application-test.yml) | `test` profile — Testcontainers PostgreSQL + LocalStack |

| Profile | When | Backing services |
|---------|------|------------------|
| **`local`** | Local development against `docker compose` | Local PostgreSQL (`:5432`) + LocalStack (`:4566`) |
| **`test`** | Automated test runs | Disposable Testcontainers (PostgreSQL + LocalStack) |

Activate a profile with `-Dspring-boot.run.profiles=<profile>` (for `spring-boot:run`) or
the `SPRING_PROFILES_ACTIVE` environment variable.

### Environment Variable Reference

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `JAVA_HOME` | Yes | System default | Path to the JDK 25 installation |
| `LOCALSTACK_AUTH_TOKEN` | No | _(empty)_ | Only needed for a LocalStack **Pro** image; the default community image needs no token |
| `POSTGRES_DB` | No | `carddemo` | PostgreSQL database name |
| `POSTGRES_USER` | No | `carddemo` | PostgreSQL username |
| `POSTGRES_PASSWORD` | Yes (local dev) | _(none — fail-fast)_ | PostgreSQL password; required secret, no committed default |
| `JWT_SECRET` | Yes (local dev) | _(none — fail-fast)_ | JWT signing secret; Base64, **≥ 32 bytes** (e.g. `openssl rand -base64 48`) |
| `GF_SECURITY_ADMIN_PASSWORD` | Yes (local dev) | _(none — fail-fast)_ | Grafana admin password for the observability stack |
| `SERVER_PORT` | No | `8080` | Application server port |
| `MANAGEMENT_SERVER_PORT` | No | `9091` | Actuator (health/metrics) management port |
| `SPRING_PROFILES_ACTIVE` | No | `default` | Active Spring profile (`local`, `test`) |
| `AWS_ACCESS_KEY_ID` | No | `test` (local) | AWS access key (LocalStack) |
| `AWS_SECRET_ACCESS_KEY` | No | `test` (local) | AWS secret key (LocalStack) |
| `AWS_DEFAULT_REGION` | No | `us-east-1` | AWS region |

> **No production profile is shipped** — production configuration and deployment are out
> of scope for this migration. The **JWT signing secret is externalized via an environment
> variable** and is never hardcoded; no credentials are committed to the repository.


---

## REST API Overview

The 17 CICS online screens are exposed as REST endpoints across 8 controllers. All
endpoints except sign-in require an `Authorization: Bearer <token>` header; admin endpoints
additionally require the admin role.

| Legacy Txn | Method & Path | Purpose |
|------------|---------------|---------|
| `CC00` | `POST /api/auth/signin` | Sign on; validates credentials and returns a JWT |
| `CM00` | `GET /api/menu/main` | Main menu options (regular user) |
| `CA00` | `GET /api/menu/admin` | Admin menu options |
| `CAVW` | `GET /api/accounts/{id}` | View account (account + customer join) |
| `CAUP` | `PUT /api/accounts/{id}` | Update account (dual-record atomic update, `@Version`) |
| `CCLI` | `GET /api/cards` | List cards (paginated; PF7/PF8 → page params) |
| `CCDL` | `GET /api/cards/{cardNum}` | View card detail |
| `CCUP` | `PUT /api/cards/{cardNum}` | Update card (`@Version` optimistic lock) |
| `CT00` | `GET /api/transactions` | List transactions |
| `CT01` | `GET /api/transactions/{id}` | View transaction detail |
| `CT02` | `POST /api/transactions` | Add transaction (auto-ID generation + confirmation) |
| `CB00` | `POST /api/billing/pay` | Bill payment |
| `CR00` | `POST /api/reports/submit` | Submit report request → SQS FIFO `carddemo-report-jobs.fifo` |
| `CU00`–`CU03` | `GET/POST/PUT/DELETE /api/admin/users` | User administration CRUD (admin only) |

### Example Requests

First obtain a token (see [Seed Credentials](#seed-credentials)) and capture it:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
```

**View an account:**

```bash
curl -s http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer ${TOKEN}" | python3 -m json.tool
```

**Add a transaction:**

```bash
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"accountId": "00000000001", "cardNumber": "4111111111111111", "typeCode": "01", "categoryCode": "0001", "description": "GROCERY STORE PURCHASE", "amount": "125.50"}' \
  | python3 -m json.tool
# Expected: 201 Created with the persisted transaction and server-generated id
```

**Submit a report** (asynchronous SQS FIFO bridge):

```bash
curl -s -X POST http://localhost:8080/api/reports/submit \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"reportType": "CUSTOM", "startDate": "2025-01-01", "endDate": "2025-01-31"}' \
  | python3 -m json.tool
# Expected: 202 Accepted; the request is queued to carddemo-report-jobs.fifo
```

> The full request/response schemas, validation rules, role model, PF-key mappings, and
> preserved file/messaging contracts are documented in
> [`docs/api-contracts.md`](docs/api-contracts.md).


---

## Batch Pipeline & Data

The legacy JCL batch jobs are reimplemented as a Spring Batch pipeline. Each stage is a
chunk-oriented step that reads, validates/processes, and writes records, preserving the
COBOL business logic exactly:

- **POSTTRAN — Transaction posting.** Applies the **4-stage validation cascade** in the
  original short-circuit order — *Card-exists → Account-exists → Credit-Limit →
  Expiration* — routing each failure to the rejects output with its specific reject reason
  code. Posting runs inside a `@Transactional(rollbackFor = Exception.class)` boundary.
- **INTCALC — Interest calculation.** Computes monthly interest as
  `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` using `BigDecimal` at scale 2 with
  `RoundingMode.HALF_EVEN`. The legacy `CBACT04C` `COMPUTE` is **not** `ROUNDED`; the
  migration deliberately applies banker's rounding (`HALF_EVEN`) as a documented
  modernization of the non-`ROUNDED` COBOL arithmetic — see `DECISION_LOG.md`.
- **COMBTRAN — Combine & sort.** Concatenates and sorts transactions by transaction id
  (the DFSORT replacement), preserving the legacy sort-key ordering and duplicate handling.
- **CREASTMT — Statement generation.** Produces account statements in both text and HTML
  form.
- **TRANREPT — Transaction report.** Produces a date-window transaction report with
  cross-reference / type / category enrichment and header/detail/total formatting.

Stages 4a (`CREASTMT`) and 4b (`TRANREPT`) are independent and may run in parallel. Batch
inputs and outputs are staged as S3 objects (`carddemo-batch-input`,
`carddemo-batch-output`, `carddemo-statements`), and the `CR00` report-submission bridge
delivers requests via the `carddemo-report-jobs.fifo` SQS FIFO queue.

### Database

The application persists to **PostgreSQL 16** through Spring Data JPA. The schema is
managed by **Flyway** migrations under
[`src/main/resources/db/migration/`](src/main/resources/db/migration/):

| Migration | Contents |
|-----------|----------|
| `V1__create_schema.sql` | 11 tables mapping the VSAM record layouts (keys and lengths preserved) |
| `V2__create_indexes.sql` | Secondary indexes (the VSAM alternate-index / PATH equivalents) |
| `V3__seed_data.sql` | Seed data loaded from the 9 authoritative ASCII fixtures |

The 11 tables correspond to the legacy datasets: accounts, cards, card cross-reference,
customers, transactions, daily (staging) transactions, transaction-category balances,
disclosure groups, transaction types, transaction categories, and users.

Seed data is derived from the 9 fixed-width ASCII fixtures under
[`app/data/ASCII/`](app/data/ASCII/) — `acctdata.txt`, `carddata.txt`, `cardxref.txt`,
`custdata.txt`, `discgrp.txt`, `tcatbal.txt`, `trancatg.txt`, `trantype.txt`, and
`dailytran.txt` (the batch posting input) — with column offsets and lengths preserved
exactly.


---

## Observability

The full observability stack ships with the application and is exercised locally through
`docker compose`. Structured logging, metrics, distributed tracing, and health checks are
all enabled out of the box.

### Endpoints & UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Application health | `http://localhost:9091/actuator/health` (host run) — internal `app:9091` under Compose | — |
| Prometheus metrics (scrape) | `http://app:9091/actuator/prometheus` (in-network) | — |
| Jaeger tracing UI | http://localhost:16686 | — |
| Prometheus server | http://localhost:9090 | — |
| Grafana dashboards | http://localhost:3000 | `admin` / your `GF_SECURITY_ADMIN_PASSWORD` from `.env` (no default — see `.env.example`) |

### Ports

| Port | Service | Protocol |
|------|---------|----------|
| 5432 | PostgreSQL 16 | TCP |
| 4566 | LocalStack (S3, SQS, SNS) | HTTP |
| 8080 | CardDemo application | HTTP |
| 16686 | Jaeger UI | HTTP |
| 4317 | Jaeger OTLP gRPC | gRPC |
| 4318 | Jaeger OTLP HTTP | HTTP |
| 9090 | Prometheus | HTTP |
| 3000 | Grafana | HTTP |

### What's instrumented

- **Structured JSON logging** — Logback (`logstash-logback-encoder`, configured in
  [`logback-spring.xml`](src/main/resources/logback-spring.xml)) emits JSON log lines that
  include `traceId`, `spanId`, and `correlationId` fields for end-to-end request
  correlation. A `CorrelationIdFilter` assigns/propagates the correlation id per request.
- **Distributed tracing** — Micrometer Tracing bridges to OpenTelemetry and exports spans
  via OTLP to **Jaeger**, so requests can be traced across service boundaries.
- **Metrics** — Micrometer publishes JVM, HTTP, and custom business metrics in Prometheus
  format at `/actuator/prometheus`, scraped by the bundled Prometheus server
  ([`prometheus.yml`](prometheus.yml)).
- **Dashboards** — a ready-to-import Grafana dashboard template is provided at
  [`docs/grafana-dashboard.json`](docs/grafana-dashboard.json).
- **Health & readiness** — Spring Boot Actuator exposes `/actuator/health` with custom
  health indicators for PostgreSQL, S3, and SQS.


---

## Project Structure

```
.
├── pom.xml                          # Maven build (Spring Boot 3.5.11, Java 25, all deps)
├── mvnw, mvnw.cmd, .mvn/            # Maven wrapper (pins Maven 3.9.9)
├── Dockerfile                       # Multi-stage build → runnable image
├── docker-compose.yml               # 6 services: postgres, localstack, app, jaeger, prometheus, grafana
├── prometheus.yml                   # Prometheus scrape config
├── localstack-init/
│   └── init-aws.sh                  # Provisions S3 buckets + SQS FIFO + SNS topic
├── DECISION_LOG.md                  # 55 architectural decisions (rationale + alternatives)
├── TRACEABILITY_MATRIX.md           # Bidirectional COBOL ↔ Java mapping (100% paragraphs)
├── README.md                        # This file
├── app/                             # Frozen COBOL reference @ 27d6c6f (cbl, cpy, cpy-bms, bms, jcl, data)
├── docs/                            # Project documentation (see Documentation section)
├── src/main/java/com/carddemo/
│   ├── CardDemoApplication.java     # Spring Boot entry point
│   ├── controller/                  # 8 REST controllers
│   ├── service/                     # ~20 service classes
│   ├── repository/                  # 11 Spring Data JPA repositories
│   ├── entity/                      # 11 JPA entities (+ composite keys, converter)
│   ├── dto/                         # 9 DTOs (from BMS symbolic maps)
│   ├── enums/                       # Transaction/file-status/source enums
│   ├── batch/                       # job/ reader/ processor/ writer/ (5-stage pipeline)
│   ├── config/                      # Security, Batch, Aws, Jpa, Observability, Web
│   ├── observability/               # CorrelationIdFilter, MetricsConfig, HealthIndicators
│   └── exception/                   # 7 typed exceptions + GlobalExceptionHandler
├── src/main/resources/
│   ├── application.yml              # Base config (+ application-local.yml, application-test.yml)
│   ├── logback-spring.xml           # Structured JSON logging
│   ├── db/migration/                # Flyway V1 schema / V2 indexes / V3 seed
│   └── validation/                  # NANPA / state / ZIP lookup tables (JSON)
└── src/test/java/com/carddemo/
    ├── unit/                        # Service / processor / model unit tests (Mockito)
    ├── integration/                 # Testcontainers PostgreSQL + LocalStack ITs
    └── gates/                       # GateVerificationTest (Gates 1–8)
```

---

## Migration Context

This repository is the greenfield Java target of a tech-stack migration from the AWS
CardDemo mainframe application. The legacy COBOL/CICS/JCL corpus is retained under
[`app/`](app/) **only as a frozen reference** — it is never modified or copied, and
traceability to the source is anchored exclusively to commit SHA **`27d6c6f`**.

| Legacy artifact | Count | Migrated to |
|-----------------|-------|-------------|
| COBOL programs | 28 (18 online + 10 batch) | 8 REST controllers, ~20 services, 5-stage Spring Batch pipeline |
| Copybooks | 28 | 11 JPA entities, 9 DTOs, enums, composite keys |
| BMS mapsets / symbolic maps | 17 / 17 | 9 DTO field contracts |
| JCL jobs | 29 | Spring Batch jobs, Flyway migrations, LocalStack provisioning |
| ASCII data fixtures | 9 | Flyway `V3` seed data |

The migration translated the 28 COBOL programs into **103 Java source files (~34,021
lines)** with full business-logic preservation.

- **[`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md)** maps **100% of COBOL paragraphs**
  to their Java methods, with the single intentionally-unmapped reserved copybook
  documented as a known gap.
- **[`DECISION_LOG.md`](DECISION_LOG.md)** records the **55 architectural decisions** taken
  during the migration (decision, alternatives, rationale, risks).

---

## Documentation

| Document | Description |
|----------|-------------|
| [`docs/onboarding-guide.md`](docs/onboarding-guide.md) | New-developer quickstart, domain context, and suggested next tasks |
| [`docs/api-contracts.md`](docs/api-contracts.md) | Full REST endpoint specifications, validation, and preserved file/messaging contracts |
| [`docs/validation-gates.md`](docs/validation-gates.md) | Gate 1–8 validation evidence |
| [`docs/architecture-before-after.md`](docs/architecture-before-after.md) | Mermaid before/after architecture diagrams |
| [`docs/executive-presentation.html`](docs/executive-presentation.html) | reveal.js executive summary deck |
| [`docs/project-guide.md`](docs/project-guide.md) | Project status, command/port/version reference, key file locations |
| [`docs/technical-specifications.md`](docs/technical-specifications.md) | Authoritative migration blueprint (architecture, scope, constraints) |
| [`DECISION_LOG.md`](DECISION_LOG.md) | Architectural decision log |
| [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) | Bidirectional COBOL ↔ Java traceability matrix |

---

## Common Pitfalls & Troubleshooting

| Pitfall | Guidance |
|---------|----------|
| **JDK 25 not active** | `java: error: release version 25 not supported` means `JAVA_HOME` points at an older JVM — set `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`. |
| **BigDecimal precision** | All monetary values are `BigDecimal` (scale 2, `HALF_EVEN`). Never introduce `double`/`float` for money — it breaks byte-equivalent parity with the COBOL baseline. |
| **LocalStack S3 path-style access** | The AWS clients are configured for **path-style** access against LocalStack (`http://localhost:4566`); ensure your profile keeps path-style enabled rather than virtual-host addressing. |
| **Testcontainers Docker socket** | Integration tests need access to the Docker daemon. Ensure Docker is running and your user can reach the Docker socket; otherwise tests fail with "connection refused". |
| **Flyway migration ordering** | Migrations apply in `V1 → V2 → V3` order. Never edit an already-applied migration; add a new `V{n}__*.sql` instead, or reset the dev database with `docker compose down -v`. |
| **Port conflicts** | If `docker compose up` fails, check for processes already bound to `5432`, `4566`, `8080`, `16686`, `9090`, or `3000` (e.g., `lsof -i :5432`). |
| **`localstack-init` did not provision resources** | The init script creates the S3/SQS/SNS resources on startup; ensure `localstack-init/init-aws.sh` is executable. The default community image needs no `LOCALSTACK_AUTH_TOKEN` — only set one if you switched the `localstack` service to a Pro image. |
| **Maven wrapper permission denied** | Run `chmod +x mvnw`. |


---

## Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) for the
contribution process (bug reports, feature requests, and pull requests), and review the
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) before participating. The
[onboarding guide](docs/onboarding-guide.md) describes the domain, local setup, and
suggested next tasks for new contributors.

---

## License

This project is licensed under the **Apache License 2.0** — see [`LICENSE`](LICENSE) for
the full text and [`NOTICE`](NOTICE) for attribution.

> Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.

---

## Project Status

**CP6 milestone — complete.** This checkpoint assembles the Spring Batch jobs and the master
pipeline orchestrator, adds the Transaction and Billing REST controllers (completing all eight
online controllers), and proves the system end-to-end with Testcontainers integration tests on
real PostgreSQL and LocalStack (S3 / SQS / SNS). The unit and integration suites pass and the
build is clean under `-Xlint:all -Werror`. Only the final consolidated validation-gate harness
remains before sign-off.

Delivered in this milestone:

- **Batch job assembly** — `PostTransactionJobConfig` (POSTTRAN), `InterestCalculationJobConfig`
  (INTCALC), `CombineTransactionsJobConfig` (COMBTRAN), `TransactionReportJobConfig` (TRANREPT),
  and `StatementJobConfig` (CREASTMT) wire the processors, readers, and writers into runnable
  Spring Batch 5 jobs, each with launch-time job-parameter validation.
- **Pipeline orchestrator** — `BatchPipelineOrchestrator` sequences the jobs in JCL order with
  the `TRANBKP STEP10 COND=(4,LT)` posting-return-code gate (`PostingReturnCodeDecider` →
  `categoryBalanceBackupStep`) and the parallel report/statement split, producing the PRTCATBL
  40-byte print line and the 50-byte signed-zoned-overpunch backup record.
- **End-to-end tests** — batch integration tests (posting, interest, combine, report, statement,
  and the full pipeline) and an online-flow integration test exercise the real datastore and AWS
  emulation, including a log-safe Gate 3 performance capture (elapsed time, records/sec,
  throughput, peak memory) for the pipeline run.

Work remaining (final sign-off):

- **Consolidated validation-gate harness** — the single `GateVerificationTest` consolidating the
  Gate 1 / Gate 4 byte-equivalent evidence against the named ASCII fixtures, the Gate 3 baseline,
  Gate 8 ≥80% line coverage, and the OWASP dependency-check zero critical/high CVE gate.
- **Path-to-production** — CI/CD pipeline, production Spring profile, deployment manifests,
  and security hardening (explicitly out of scope for the migration architecture).

For the detailed status, completion metrics, success criteria, and the list of remaining
work items, see [`docs/project-guide.md`](docs/project-guide.md).

