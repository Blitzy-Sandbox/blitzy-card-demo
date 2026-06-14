# CardDemo — New-Developer Onboarding Guide (Clean Machine → Running App)

**Authoritative onboarding guide for the AWS CardDemo COBOL → Java 25 + Spring Boot 3.x migration.**

This guide takes a brand-new developer from a **clean machine to a running application without
asking a single question**. Follow it top-to-bottom: by the end you will have built the project,
started the local infrastructure, launched the application, and confirmed a healthy
`/actuator/health` response. It then explains the business domain, the data model, the layered
architecture, the batch pipeline, the pitfalls that bite newcomers, and recipes for extending the
system — plus a short list of suggested next tasks that are explicitly **out of scope** for the
migration itself.

**The two "front doors."** This repository has two entry-point documents. The
[`README.md`](../README.md) is the **overview** — what the project is and a quick-start summary.
**This guide is the deep onboarding** — the step-by-step, copy-paste path to a running app plus the
context a new contributor needs. Every command, port, profile name, and service name in this guide
is kept **exactly consistent** with [`../pom.xml`](../pom.xml), [`../docker-compose.yml`](../docker-compose.yml),
and [`../README.md`](../README.md); if any of those change, update this guide in the same commit.

**Traceability.** The legacy baseline is the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS application at
commit SHA **`27d6c6f`**. Per the migration's preservation rules, **no COBOL, copybook, BMS, or JCL
source is copied into this greenfield repository**; legacy constructs are referenced by name only.
Paragraph-level COBOL → Java mapping lives in [`TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md),
and decision rationale lives in [`DECISION_LOG.md`](../DECISION_LOG.md).

**Minimal Change Clause.** The migration reproduces the COBOL behavior **exactly** — there are no
business-rule "improvements" and no feature expansion beyond the 22 documented features
(F-001–F-022). The **single intentional behavioral change** in the whole migration is the upgrade of
plaintext `USRSEC` password storage to **BCrypt** verification (constraint C-003); every other flow
preserves the behavior at `27d6c6f`.

---

## Table of contents

- [1. Prerequisites](#1-prerequisites)
  - [1.1 Required tooling](#11-required-tooling)
  - [1.2 Verify your toolchain](#12-verify-your-toolchain)
  - [1.3 Environment variables](#13-environment-variables)
- [2. Clean machine → running app](#2-clean-machine--running-app)
  - [2.1 Step 1 — Clone and build](#21-step-1--clone-and-build)
  - [2.2 Step 2 — Start the local stack](#22-step-2--start-the-local-stack)
  - [2.3 Step 3 — Run the application](#23-step-3--run-the-application)
  - [2.4 Step 4 — Verify health](#24-step-4--verify-health)
  - [2.5 Step 5 — Run the full integration / E2E suite](#25-step-5--run-the-full-integration--e2e-suite)
  - [2.6 Service and port reference](#26-service-and-port-reference)
  - [2.7 Smoke-test the API](#27-smoke-test-the-api)
  - [2.8 Shutting down](#28-shutting-down)
- [3. Domain context](#3-domain-context)
  - [3.1 Business overview](#31-business-overview)
  - [3.2 Functional inventory](#32-functional-inventory)
  - [3.3 The data model — 11 entities](#33-the-data-model--11-entities)
  - [3.4 Layered architecture](#34-layered-architecture)
  - [3.5 The batch pipeline](#35-the-batch-pipeline)
- [4. Common pitfalls](#4-common-pitfalls)
  - [4.1 BigDecimal precision traps](#41-bigdecimal-precision-traps)
  - [4.2 LocalStack S3 path-style access](#42-localstack-s3-path-style-access)
  - [4.3 Testcontainers Docker socket permissions](#43-testcontainers-docker-socket-permissions)
  - [4.4 Flyway migration ordering](#44-flyway-migration-ordering)
- [5. How to extend the system](#5-how-to-extend-the-system)
  - [5.1 Add a new entity](#51-add-a-new-entity)
  - [5.2 Add a batch job](#52-add-a-batch-job)
  - [5.3 Add an API endpoint](#53-add-an-api-endpoint)
- [6. Suggested next tasks (out of scope)](#6-suggested-next-tasks-out-of-scope)
- [7. Related documentation](#7-related-documentation)

---

## 1. Prerequisites

You need the following installed **once** on a clean machine. Everything else (PostgreSQL,
LocalStack, the observability stack) runs in Docker and is provisioned for you by
`docker compose` — you do **not** install those locally.

### 1.1 Required tooling

| Tool | Version | Why it is needed | Install check |
|---|---|---|---|
| **JDK** | **25** (OpenJDK / Eclipse Temurin **25.0.2**) | Compiles and runs the application. Java 25 LTS emits class-file major version 69; the build is pinned to it. | `java -version` → `openjdk version "25.x.x"` |
| **Maven** | **3.9.9+** — or just use the bundled wrapper **`./mvnw`** | Build automation. The wrapper pins Maven 3.9.9 so you do not have to install Maven at all. | `./mvnw -version` |
| **Docker** | **28.x+** | Container runtime for PostgreSQL, LocalStack, and the observability stack. | `docker version` |
| **Docker Compose** | **v2** (the `docker compose` plugin) | Orchestrates the six local services in one command. | `docker compose version` |
| **Git** | **2.x+** | Clone the repository. | `git --version` |
| **AWS CLI** | **optional** (v2) | Convenience for inspecting LocalStack S3/SQS from the shell. The app does **not** require it. | `aws --version` |

> **LocalStack Pro.** The local stack uses `localstack/localstack-pro` to emulate S3, SQS, and SNS.
> Pro features require a **`LOCALSTACK_AUTH_TOKEN`** (see [§1.3](#13-environment-variables)). There is
> **zero dependency on live AWS** — every AWS interaction runs against LocalStack on
> `http://localhost:4566`.

If `java -version` does not report 25, point `JAVA_HOME` at your JDK 25 installation (the exact path
varies by OS and distribution):

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
java -version   # re-check: openjdk version "25.x.x"
```

### 1.2 Verify your toolchain

Run these four checks before going further. All four must succeed on a clean machine:

```bash
java -version              # openjdk version "25.x.x"
docker version             # Client & Server both report; Server is the daemon
docker compose version     # Docker Compose v2.x
git --version              # git version 2.x
```

If `docker version` does not print a **Server** section, the Docker daemon is not running — start
Docker Desktop (macOS/Windows) or `sudo systemctl start docker` (Linux) before continuing. The
Docker daemon is also required by the Testcontainers-based integration tests (see
[§4.3](#43-testcontainers-docker-socket-permissions)).

### 1.3 Environment variables

The AWS-related variables below are read by `docker compose` (for LocalStack) and by the
application's `local` profile. For local development the AWS credentials are **fake placeholders** —
LocalStack accepts any value, so the conventional `test` / `test` pair is fine.

| Variable | Required | Local value | Purpose |
|---|---|---|---|
| `JAVA_HOME` | If JDK 25 is not the default | Path to JDK 25 | Selects the JDK used by `./mvnw`. |
| `LOCALSTACK_AUTH_TOKEN` | Yes (local dev) | *your LocalStack Pro token* | Activates LocalStack Pro (S3/SQS/SNS). **Secret — never commit.** |
| `AWS_ACCESS_KEY_ID` | No | `test` | AWS SDK credential id (LocalStack ignores the value). Non-secret. |
| `AWS_SECRET_ACCESS_KEY` | No | `test` | AWS SDK secret. **Secret — never commit** (placeholder only for LocalStack). |
| `AWS_DEFAULT_REGION` | No | `us-east-1` | Region for the S3/SQS/SNS clients. Non-secret. |
| `POSTGRES_DB` | No | `carddemo` | Database name created by the PostgreSQL container. |
| `POSTGRES_USER` | No | `carddemo` | PostgreSQL username. |
| `POSTGRES_PASSWORD` | No | `carddemo` | PostgreSQL password. |
| `SERVER_PORT` | No | `8080` | Application HTTP port. |
| `SPRING_PROFILES_ACTIVE` | No | `local` | Active Spring profile (`local` or `test`). |

Export the minimum set for a local run:

```bash
# Required for LocalStack Pro — keep this out of source control.
export LOCALSTACK_AUTH_TOKEN=<your-localstack-pro-token>

# Fake AWS credentials accepted by LocalStack (do NOT use real keys here).
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
```

> **Secrets discipline.** `LOCALSTACK_AUTH_TOKEN` and `AWS_SECRET_ACCESS_KEY` are secrets. Supply
> them via your shell environment or a secret manager — **never** hard-code them in the repository,
> `application*.yml`, or this guide. No credential material is stored in this repository.

---

## 2. Clean machine → running app

Five steps take you from nothing to a running, health-checked application. Run them in order.
Each command is copy-paste runnable and matches [`../pom.xml`](../pom.xml),
[`../docker-compose.yml`](../docker-compose.yml), and [`../README.md`](../README.md) exactly.

### 2.1 Step 1 — Clone and build

```bash
git clone <repository-url>
cd carddemo-java
./mvnw clean verify
```

`./mvnw clean verify` compiles every source file, runs the **unit** test suite, and produces the
JaCoCo coverage report (the build enforces **≥ 80 % line coverage** at the bundle level). It does
**not** run the Docker-dependent integration tests by default — those are gated behind the
`integration` profile (see [§2.5](#25-step-5--run-the-full-integration--e2e-suite)), so this first
build succeeds **without** Docker.

> **JDK 25 reminder.** If your default JDK is not 25, prefix the command (or export `JAVA_HOME`
> first, per [§1.1](#11-required-tooling)):
> ```bash
> JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw clean verify
> ```

The repackaged executable JAR is written to `target/carddemo-java.jar`.

### 2.2 Step 2 — Start the local stack

```bash
docker compose up -d
```

This brings up **six** services in the background:

- **PostgreSQL 16** on port **5432** — the relational store that replaces the VSAM datasets.
- **LocalStack** on port **4566** — emulates **S3, SQS, and SNS** with zero live AWS dependency.
- **Jaeger** UI on port **16686** — distributed tracing.
- **Prometheus** on port **9090** — metrics scraping.
- **Grafana** on port **3000** — dashboards (default login `admin` / `admin`).

On startup LocalStack runs the init hook **`localstack-init/init-aws.sh`**, which provisions the AWS
resources the application expects:

- **S3 buckets:** `carddemo-batch-input`, `carddemo-batch-output`, `carddemo-statements`
  (batch file staging, report output, and statement output — the GDG replacement).
- **SQS FIFO queue:** `carddemo-report-jobs.fifo`
  (the report-submission bridge that replaces the CICS Transient Data Queue `JOBS`).

Confirm the infrastructure is healthy before starting the app:

```bash
# PostgreSQL is accepting connections
docker compose exec postgres pg_isready -U carddemo
# Expected: accepting connections

# LocalStack reports s3/sqs/sns available
curl -s http://localhost:4566/_localstack/health | python3 -m json.tool
# Expected: {"services": {"s3": "available", "sqs": "available", "sns": "available"}}
```

### 2.3 Step 3 — Run the application

The primary way to launch the app from source uses the Spring Boot Maven plugin and its
`spring-boot.run.profiles` argument:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The **`local`** profile points the datasource at the Docker PostgreSQL container and the AWS clients
at LocalStack (`http://localhost:4566`).

> **Profile flag forms — pick the one that matches how you launch.**
> The flag above (`-Dspring-boot.run.profiles=local`) is the **plugin** form and is correct for
> `spring-boot:run`. If you instead run the packaged JAR with `java -jar`, the profile is selected
> by the **JVM-arg** form or an **environment variable** — both are equivalent:
> ```bash
> # Run the executable JAR with the JVM-arg form:
> java -jar target/carddemo-java.jar --spring.profiles.active=local
> #   …or the classic JVM system property:
> java -Dspring.profiles.active=local -jar target/carddemo-java.jar
> #   …or via the environment:
> SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java.jar
> ```
> Do **not** pass `-Dspring.profiles.active=local` to `spring-boot:run` expecting it to forward to
> the app — the plugin needs `-Dspring-boot.run.profiles=local`.

On startup, **Flyway** runs the schema migrations (`V1` → `V2` → `V3`) against PostgreSQL before any
service or batch job is reachable (see [§4.4](#44-flyway-migration-ordering)). The application
listens on port **8080**.

### 2.4 Step 4 — Verify health

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

A healthy system returns `"status": "UP"` with a **composite** of indicators for the database, S3,
and SQS:

```json
{
  "status": "UP",
  "components": {
    "db":  { "status": "UP" },
    "s3":  { "status": "UP" },
    "sqs": { "status": "UP" }
  }
}
```

Two more observability endpoints are worth knowing immediately:

- **Metrics:** `http://localhost:8080/actuator/prometheus` exposes the Micrometer/Prometheus scrape
  endpoint (custom business metrics: auth attempts, transactions, batch records).
- **Dashboards:** Grafana at `http://localhost:3000` (login `admin` / `admin`) loads the dashboard
  template shipped at [`grafana-dashboard.json`](grafana-dashboard.json).

### 2.5 Step 5 — Run the full integration / E2E suite

```bash
./mvnw verify -Pintegration
```

The **`integration`** profile flips the Failsafe plugin on so the `*IT`, `integration/`, and `e2e/`
test classes execute. These tests use **Testcontainers** to spin up **PostgreSQL** and **LocalStack**
automatically, so the **Docker daemon must be running** (see
[§4.3](#43-testcontainers-docker-socket-permissions)). You do **not** need `docker compose up`
running for this — Testcontainers manages its own throwaway containers. Coverage from the unit run
and this integration run is combined into the single JaCoCo figure that the build gates on.

### 2.6 Service and port reference

| Service | URL / Host | Port | Notes |
|---|---|---|---|
| CardDemo application | `http://localhost:8080` | **8080** | REST API + `/actuator/*` |
| PostgreSQL 16 | `localhost` | **5432** | user/db `carddemo` |
| LocalStack (S3, SQS, SNS) | `http://localhost:4566` | **4566** | AWS emulation |
| Jaeger UI | `http://localhost:16686` | **16686** | distributed tracing |
| Prometheus | `http://localhost:9090` | **9090** | metrics server |
| Grafana | `http://localhost:3000` | **3000** | dashboards (`admin`/`admin`) |

> Jaeger also exposes the OTLP collector on **4317** (gRPC) and **4318** (HTTP); the application
> exports traces to it via the Micrometer → OpenTelemetry bridge.

### 2.7 Smoke-test the API

The legacy sign-on credentials are preserved. Two seeded users exist (passwords are now BCrypt-hashed
at rest, but the login flow and the literal password are unchanged):

- **Admin:** user `ADMIN001`, password `PASSWORD` — admin functions (user administration).
- **Regular:** user `USER0001`, password `PASSWORD` — back-office/account functions.

```bash
# Sign in — returns a token used as the Bearer credential on subsequent calls.
curl -s -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}' | python3 -m json.tool

# Account view (substitute a real 11-digit account id and the token from sign-in):
curl -s http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer <token>" | python3 -m json.tool

# Main-menu options (mirrors the COBOL CM00 menu routing; substitute the token from sign-in).
# /api/menu/main is protected by SecurityConfig, so the Bearer token is required (401 without it):
curl -s http://localhost:8080/api/menu/main \
  -H "Authorization: Bearer <token>" | python3 -m json.tool
```

The complete endpoint catalogue — every route, request/response field, and validation rule, mapped
from the BMS screen contracts — is in [`api-contracts.md`](api-contracts.md).

### 2.8 Shutting down

```bash
docker compose down -v     # stop all services and remove the data volumes
```

Omit `-v` to keep the PostgreSQL data volume between runs.

---

## 3. Domain context

### 3.1 Business overview

CardDemo is a **credit-card management application**. It lets users manage **accounts, credit cards,
customers, transactions, bill payments, reporting, and user administration**. It originated as a
COBOL/CICS/VSAM/JCL mainframe application and has been migrated to Java 25 + Spring Boot 3.x with
**100 % behavioral parity** across all 22 features (F-001–F-022).

There are **two kinds of user**, exactly as in the COBOL baseline:

- **Regular User** — performs the back-office/business functions: viewing and updating accounts,
  listing/viewing/updating cards, listing/viewing/adding transactions, bill payment, and submitting
  reports.
- **Admin User** — performs administrative functions only: the user-management CRUD (list, add,
  update, delete users).

The migration spans **two execution paradigms**: **18 interactive online programs** (originally
pseudo-conversational 3270 terminal screens, now stateless REST endpoints) and **10 batch programs**
(originally JES-scheduled JCL jobs, now Spring Batch jobs), converging on a shared data layer
(originally 11 VSAM/PS datasets, now 11 PostgreSQL tables).

### 3.2 Functional inventory

The legacy online and batch inventories — the CICS transaction IDs, BMS maps, and COBOL program
names — are catalogued in the root [`README.md`](../README.md). In the Java target the 18 online
transactions are consolidated by domain into **8 REST controllers**:

| Controller | Domain | Legacy transactions (examples) |
|---|---|---|
| `AuthController` | Sign-on / authentication | `CC00` |
| `MenuController` | Main + admin menu routing | `CM00`, `CA00` |
| `AccountController` | Account view / update | `CAVW`, `CAUP` |
| `CardController` | Card list / view / update | `CCLI`, `CCDL`, `CCUP` |
| `TransactionController` | Transaction list / view / add | `CT00`, `CT01`, `CT02` |
| `BillingController` | Bill payment | `CB00` |
| `ReportController` | Report submission | `CR00` |
| `UserAdminController` | User CRUD (admin) | `CU00`–`CU03` |

The exact routes, request/response DTOs, field lengths, and validation rules are specified in
[`api-contracts.md`](api-contracts.md); the before/after system views are in
[`architecture-before-after.md`](architecture-before-after.md).

### 3.3 The data model — 11 entities

The 11 VSAM/PS datasets map one-to-one to **11 JPA `@Entity` classes**, each backed by a
`JpaRepository`. All monetary and numeric fields use `java.math.BigDecimal` (never `float`/`double`);
see [§4.1](#41-bigdecimal-precision-traps).

| Entity | Represents | Notable mapping |
|---|---|---|
| `Account` | Account master | `BigDecimal` balances; `@Version` optimistic locking |
| `Card` | Credit-card master | FK to `Account`; active-status enum; `@Version` |
| `Customer` | Customer master | 500-byte field layout |
| `CardCrossReference` | Card ↔ account ↔ customer cross-reference | alternate-index lookup (`CXACAIX`) |
| `Transaction` | Posted transactions | `BigDecimal` amount; timestamps |
| `UserSecurity` | Sign-on users | **BCrypt** password hash; role enum |
| `TransactionCategoryBalance` | Per-category balances | composite key (`@EmbeddedId`) |
| `DisclosureGroup` | Interest disclosure groups | composite key; `BigDecimal` interest rate |
| `TransactionType` | Transaction type reference | 2-byte type-code key |
| `TransactionCategory` | Transaction category reference | composite key (type + category) |
| `DailyTransaction` | Daily-transaction staging | batch staging entity |

### 3.4 Layered architecture

The procedural COBOL "one program per screen" model is replaced by a conventional **layered
architecture**. Requests flow strictly downward through the layers:

```
HTTP request
   │
   ▼
Controller   (@RestController)   ── request/response DTOs, Jakarta Validation
   │
   ▼
Service      (@Service)          ── business logic translated from COBOL paragraphs
   │
   ▼
Repository   (JpaRepository)     ── keyed + alternate-index access (replaces VSAM I/O)
   │
   ▼
Entity       (@Entity)           ── PostgreSQL tables (replaces VSAM/PS datasets)
```

Cross-cutting concerns sit alongside these layers: a configuration layer (`SecurityConfig`,
`BatchConfig`, `AwsConfig`, `JpaConfig`, `ObservabilityConfig`, `WebConfig`), a custom exception
hierarchy (COBOL `FILE STATUS` codes → typed exceptions), and an observability layer
(`CorrelationIdFilter`, metrics, health indicators). The package root is **`com.cardemo`**.

### 3.5 The batch pipeline

The JCL batch suite becomes a **Spring Batch** pipeline. The core business flow is a **5-stage**
sequential pipeline; each stage starts only after its predecessor completes successfully, and JCL
`COND`-code logic is reproduced with Spring Batch `ExitStatus` + `JobExecutionDecider`:

```
POSTTRAN  →  INTCALC  →  COMBTRAN  →  ┬─ CREASTMT   (Stage 4a)
(post)       (interest)  (sort/merge) └─ TRANREPT   (Stage 4b)
```

- **POSTTRAN** — daily transaction posting (4-stage validation cascade with reject codes).
- **INTCALC** — interest calculation. The formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` is
  preserved without algebraic rearrangement, with a `DEFAULT` disclosure-group fallback (see
  [§4.1](#41-bigdecimal-precision-traps)).
- **COMBTRAN** — combine/sort transactions. The legacy DFSORT + IDCAMS `REPRO` step (no COBOL
  program) becomes a Java `Comparator` sort plus a bulk JPA insert.
- **CREASTMT** (4a) — statement generation (text + HTML), written to S3.
- **TRANREPT** (4b) — date-filtered transaction reporting, written to S3.

Stages 4a and 4b may run **in parallel** after COMBTRAN via a Spring Batch `FlowBuilder.split()`.
The single online → batch bridge is report submission: `ReportController` publishes to the
`carddemo-report-jobs.fifo` SQS queue (replacing the CICS TDQ `JOBS`), which triggers the report
batch job.

---

## 4. Common pitfalls

These four issues account for the overwhelming majority of newcomer mistakes. Read them **before**
you write or change code.

### 4.1 BigDecimal precision traps

Money is **never** represented with `float` or `double`. Every field that originates from a COBOL
`PIC` clause with decimal positions (every `COMP-3` packed and `COMP` binary field) maps to
`java.math.BigDecimal`. The traps:

- **Never substitute floating point.** `float`/`double` introduce binary rounding error and break
  parity. Use `BigDecimal` for all amounts, balances, rates, and counters derived from a `PIC`
  clause.
- **Compare with `compareTo()`, not `equals()`.** `BigDecimal.equals()` is **scale-sensitive**:
  `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false`. Use
  `a.compareTo(b) == 0` for numeric equality.
- **Preserve the PIC scale.** `PIC S9(7)V99` → scale **2**. Set the scale explicitly so persisted and
  computed values match the COBOL field width.
- **Use the documented rounding for the interest formula.** Interest is
  `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`, computed with `BigDecimal.divide(...)` using
  **`RoundingMode.HALF_EVEN`** (banker's rounding). Do not rearrange the formula algebraically.

```java
// Correct: scale preserved, banker's rounding, compareTo for equality.
BigDecimal interest = tranCatBal
        .multiply(disIntRate)
        .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_EVEN);

if (balance.compareTo(BigDecimal.ZERO) == 0) { /* zero balance */ }
```

### 4.2 LocalStack S3 path-style access

When the S3 client talks to LocalStack it **must enable path-style access**. The default
**virtual-hosted** addressing turns `carddemo-batch-input` into the hostname
`carddemo-batch-input.localhost`, which does not resolve against LocalStack — uploads and downloads
fail with confusing DNS/connection errors. The `local` and `test` profiles already point the S3
endpoint at `http://localhost:4566` and enable path-style addressing; if you add a new S3 client or
override its configuration, **keep path-style access on**:

```yaml
# application-local.yml — S3 against LocalStack
spring:
  cloud:
    aws:
      s3:
        endpoint: http://localhost:4566
        path-style-access-enabled: true   # REQUIRED for LocalStack bucket addressing
```

Symptom to recognize: `UnknownHostException: <bucket>.localhost` or a hanging S3 call against an
otherwise-healthy LocalStack.

### 4.3 Testcontainers Docker socket permissions

The integration/E2E suite (`./mvnw verify -Pintegration`) uses **Testcontainers**, which talks to the
Docker daemon to start throwaway PostgreSQL and LocalStack containers. Two requirements:

- **The Docker daemon must be running and reachable.** `docker version` must print a **Server**
  section. If it does not, start Docker first.
- **Your user must have access to the Docker socket.** A `Could not find a valid Docker environment`
  or "permission denied on `/var/run/docker.sock`" error means the socket is not accessible. On Linux,
  add your user to the `docker` group (`sudo usermod -aG docker $USER`, then re-login). With
  **rootless Docker** or a non-default socket, point Testcontainers at it explicitly:

```bash
export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=false   # leave Ryuk on unless your environment forbids it
```

These tests manage their own containers — you do **not** need `docker compose up` running for
`-Pintegration`.

### 4.4 Flyway migration ordering

Flyway runs the schema migrations **on application startup, in version order, before any service or
batch job executes**. The three scripts under `src/main/resources/db/migration/` must run in
sequence:

1. **`V1__create_schema.sql`** — creates the 11 tables (from the VSAM `DEFINE CLUSTER` specs).
2. **`V2__create_indexes.sql`** — creates the alternate indexes (e.g. `CXACAIX`, the transaction AIX).
3. **`V3__seed_data.sql`** — seeds the rows from the nine ASCII fixtures.

Rules that keep this safe:

- **Never reorder or renumber an already-applied migration.** Flyway records a checksum per applied
  version; editing an applied script makes startup fail with a checksum/validation error.
- **Add changes as new, higher-numbered scripts** (`V4__...`, `V5__...`) — never edit `V1`–`V3` once
  they have run anywhere.
- If a migration fails mid-way during local development, fix the script and reset the **local**
  database (`docker compose down -v` to drop the volume, then `docker compose up -d`) so Flyway
  re-applies from a clean state.

---

## 5. How to extend the system

These recipes follow the established layering and the **Minimal Change Clause** — isolate new work in
dedicated classes, match existing patterns, and document any technology-substitution point at the
point of change. Honor the compile-time dependency order: **entity → repository → service →
controller / batch**.

### 5.1 Add a new entity

1. **Entity** — create `model/entity/MyThing.java` annotated with `@Entity`. Use `BigDecimal` for
   every decimal field and preserve the source scale; add `@Version` if the record is updated under
   optimistic concurrency. Use `@EmbeddedId` (with a class under `model/key/`) for composite keys.
2. **Repository** — create `repository/MyThingRepository.java extends JpaRepository<MyThing, IdType>`.
   Add derived queries or `@Query` methods for any alternate-index access patterns.
3. **Migration** — add a **new** `db/migration/V4__add_my_thing.sql` (never edit applied scripts; see
   [§4.4](#44-flyway-migration-ordering)) creating the table and any indexes.
4. **Service** — put business logic in `service/.../MyThingService.java`; keep I/O concerns in the
   repository.
5. **Controller + DTOs** — expose it (if needed) per [§5.3](#53-add-an-api-endpoint).
6. **Tests** — add a repository integration test (Testcontainers PostgreSQL) and a service unit test.

### 5.2 Add a batch job

1. **Reader / Processor / Writer** — implement an `ItemReader` under `batch/readers/`, an
   `ItemProcessor` under `batch/processors/` (business/validation logic), and an `ItemWriter` under
   `batch/writers/` (DB and/or S3 output).
2. **Job / Step configuration** — define the `Job` and `Step` beans in a `@Configuration` class under
   `batch/jobs/`, wiring the reader → processor → writer chunk.
3. **Orchestration** — if the job belongs to the sequential pipeline, wire it into the
   `BatchPipelineOrchestrator` flow, preserving stage ordering and any `COND`-code decision
   (`JobExecutionDecider`).
4. **Tests** — add a `spring-batch-test` job test asserting step transitions and `ExitStatus`.

### 5.3 Add an API endpoint

1. **DTOs** — add request/response records under `model/dto/`, annotated with Jakarta Validation
   constraints (`@NotNull`, `@Size`, `@Pattern`, …) that mirror the original field lengths and rules.
2. **Controller method** — add the `@GetMapping`/`@PostMapping`/`@PutMapping` method to the
   appropriate controller, taking `@Valid` DTOs.
3. **Service method** — implement the behavior in the matching `@Service`, returning the response DTO.
4. **Contract** — document the new route and field contract in [`api-contracts.md`](api-contracts.md).
5. **Tests** — add a controller/web-layer test and, where it crosses the persistence or AWS boundary,
   an integration/E2E test.

---

## 6. Suggested next tasks (out of scope)

The following enhancements were identified during the migration but are **explicitly out of scope** —
they are **not** implemented, because the Minimal Change Clause forbids feature expansion beyond the
22 documented features. They are recorded here as a backlog for continued development:

1. **Swagger / OpenAPI documentation generation** — auto-generate interactive API docs from the
   controllers and DTOs.
2. **Cursor-based pagination API** — the migrated browse endpoints preserve the COBOL **offset-based**
   paging; a cursor-based API would scale better for large result sets.
3. **Redis caching for reference data** — cache the rarely-changing reference tables (transaction
   types and categories) to cut database round-trips.
4. **Async batch job submission via REST** — expose an endpoint to trigger and poll batch jobs
   asynchronously, complementing the SQS-triggered report flow.
5. **Database connection-pooling tuning documentation** — document and tune HikariCP pool sizing for
   production-like load.

---

## 7. Related documentation

| Document | What it covers |
|---|---|
| [`README.md`](../README.md) | Project overview and quick start (the other "front door"). |
| [`api-contracts.md`](api-contracts.md) | Every REST route, request/response field, and validation rule. |
| [`architecture-before-after.md`](architecture-before-after.md) | Before/after architecture diagrams (z/OS → Java/AWS). |
| [`validation-gates.md`](validation-gates.md) | The eight validation gates and the parity/quality evidence. |
| [`DECISION_LOG.md`](../DECISION_LOG.md) | Rationale for every non-trivial migration decision. |
| [`TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) | Paragraph-level COBOL → Java mapping. |
| [`grafana-dashboard.json`](grafana-dashboard.json) | Grafana dashboard template (loaded at `http://localhost:3000`). |
| [`executive-presentation.html`](executive-presentation.html) | Leadership-facing reveal.js summary deck. |

---

*Legacy baseline: AWS CardDemo COBOL application at commit SHA `27d6c6f`. COBOL sources are not copied
into this repository; all traceability references the original repository by that SHA. This guide is
kept consistent with [`../pom.xml`](../pom.xml), [`../docker-compose.yml`](../docker-compose.yml), and
[`../README.md`](../README.md) — update it in the same commit whenever those change.*
