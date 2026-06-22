<!--
  CardDemo COBOL -> Java / AWS migration.
  Onboarding & Continued Development guide (Onboarding rule, AAP 0.7.6 / 0.8.6).
  Source traceability: original mainframe repository commit SHA 27d6c6f
  (CardDemo_v1.0-15-g27d6c6f-68). The COBOL sources are NOT copied into this repository.
  Platform: AWS exercised locally via LocalStack. No live AWS dependency.
-->

# CardDemo Java — Onboarding & Continued Development Guide

This guide takes you from a **clean machine to a running, modifiable** CardDemo Java application
without leaving any unanswered questions. It is the developer companion to the project
[`../README.md`](../README.md): the README is the high-level entry point, while this document is the
step-by-step path through prerequisites, the build/run flow, the business domain, the batch pipeline,
the pitfalls that bite newcomers, and the concrete recipes for extending the codebase safely.

CardDemo Java is a **Java 25 LTS + Spring Boot 3.5.15** re-platforming of the AWS **CardDemo**
mainframe credit-card management application (originally COBOL / CICS / VSAM / JCL / BMS on z/OS). The
migration targets **100% behavioral parity** with the source — every business rule, field layout, and
batch contract is preserved. The COBOL sources are **not copied** into this repository; traceability
back to the mainframe code is anchored to the source repository commit SHA **`27d6c6f`**
(`CardDemo_v1.0-15-g27d6c6f-68`), with the full bidirectional paragraph-to-method mapping recorded in
[`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md).

> **Scope.** This project migrates a **closed feature set, F-001 through F-022** (17 online functions
> plus 5 batch capabilities). No new endpoints, entities, or business rules are introduced. Anything
> labelled *out of scope* below is intentionally not implemented — see
> [Suggested Next Tasks](#suggested-next-tasks-out-of-scope).

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Build & Run](#build--run)
  - [Quick links (local URLs)](#quick-links-local-urls)
- [Configuration & Profiles](#configuration--profiles)
- [Domain Context](#domain-context)
  - [Entity-relationship overview](#entity-relationship-overview)
- [Batch Pipeline](#batch-pipeline)
- [Common Pitfalls](#common-pitfalls)
- [Extension Guides](#extension-guides)
  - [A. Add an entity](#a-add-an-entity)
  - [B. Add a batch job](#b-add-a-batch-job)
  - [C. Add an API endpoint](#c-add-an-api-endpoint)
- [Suggested Next Tasks (out of scope)](#suggested-next-tasks-out-of-scope)
- [Where to Go Next](#where-to-go-next)

---

## Prerequisites

Install the following on a clean machine **before** building. Minimum versions are pinned to the
project's verified toolchain — do not substitute other versions.

| Tool | Minimum version | Why it is needed |
| :--- | :-------------- | :--------------- |
| **JDK** | **25** (Java 25 LTS) | The target runtime. The build compiles with `--release 25`. |
| **Maven** | **3.9+** | Optional — the bundled wrapper `./mvnw` works without a system Maven and is the recommended invocation. |
| **Docker + Docker Compose** | current | Runs PostgreSQL 16, LocalStack, and the observability stack (Jaeger, Prometheus, Grafana); also used by Testcontainers for integration tests. |
| **AWS CLI** | current | Convenient for inspecting LocalStack S3 / SQS / SNS resources; points at the LocalStack endpoint `http://localhost:4566`. |

### Verify the toolchain

Run each command and confirm the output is at or above the version shown.

```bash
# JDK 25 — confirm the major version reads 25
java -version
# Expected (text varies by vendor):
#   openjdk version "25" 2025-09-16 LTS
#   OpenJDK Runtime Environment ...
#   OpenJDK 64-Bit Server VM ...

# Maven 3.9+ (only if you use a system Maven instead of ./mvnw)
mvn -version
# Expected:
#   Apache Maven 3.9.x ...
#   Java version: 25, vendor: ...

# Docker (daemon must be running for Testcontainers and docker compose)
docker --version
# Expected:
#   Docker version 2x.x.x, build ...

# Docker Compose (v2 plugin)
docker compose version
# Expected:
#   Docker Compose version v2.x.x

# AWS CLI (any recent 1.x or 2.x is fine)
aws --version
# Expected:
#   aws-cli/2.x.x ... (or aws-cli/1.x.x ...)
```

> **No JDK install yet?** Install Java 25 LTS from your distribution or from
> [openjdk.org](https://openjdk.org/projects/jdk/25/), then re-run `java -version`. The Maven wrapper
> `./mvnw` will use whatever JDK `JAVA_HOME` points at, so make sure `JAVA_HOME` resolves to a Java 25
> installation.

> **Pinned toolchain (do not substitute).** The project builds and runs against a fixed set of
> versions: **Java 25 LTS**, **Spring Boot 3.5.15**, **PostgreSQL 16**, **Spring Cloud AWS 3.3.0**,
> **Testcontainers 2.0.3**, **JaCoCo 0.8.14**, and **OWASP dependency-check 12.1.0**. These are the
> verified, current releases the build is validated against — the full Technology Stack table lives in
> [`../README.md`](../README.md). Do not introduce other versions.

---

## Build & Run

All commands are run from the project root (`carddemo-java/`). The commands below are identical to
those in [`../README.md`](../README.md) — copy-paste them in order.

```bash
# 1. Clone and enter the project
git clone <repository-url> && cd carddemo-java

# 2. Build and run unit tests (zero-warning build — Gate 2)
#    Compiles with --release 25, runs Surefire unit tests, JaCoCo 0.8.14 coverage
#    (>= 80% line — Gate 8), and the OWASP dependency-check 12.1.0 CVE scan (Gate 8).
./mvnw clean verify

# 3. Export the runtime secrets/credentials (never hardcoded - resolved from your shell).
#    JWT_SECRET is REQUIRED: it signs the app's HMAC JWTs and must be >= 32 bytes (HS256),
#    otherwise the app fails fast on startup. POSTGRES_PASSWORD must match the database
#    password (docker-compose.yml defaults it to "carddemo" if you skip it).
#    LOCALSTACK_AUTH_TOKEN is OPTIONAL - the bundled Community LocalStack image
#    (localstack:3.8) needs no token; set it only if you swap in a LocalStack PRO image.
export JWT_SECRET=<your-32-byte-or-longer-signing-secret>
export POSTGRES_PASSWORD=carddemo
export LOCALSTACK_AUTH_TOKEN=<optional-localstack-pro-token>

# 4. Start the local infrastructure:
#    PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana
docker compose up -d

# 5. Run the application with the local profile
#    NOTE: spring-boot:run forks a separate application JVM, so the Maven-JVM system property
#    -Dspring.profiles.active is NOT propagated to it. Use the plugin's own
#    -Dspring-boot.run.profiles=<profile>, which the plugin forwards to the forked process.
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 6. Verify the application is healthy (expect status "UP")
curl http://localhost:8080/actuator/health

# 7. Run the full integration suite (Testcontainers + LocalStack)
./mvnw verify -Pintegration
```

A healthy response from step 6 looks like the following — note the **composite indicators** for
PostgreSQL, S3, and SQS that the application registers (see
[`../README.md`](../README.md#observability)):

```json
{
  "status": "UP",
  "components": {
    "db":   { "status": "UP" },
    "s3":   { "status": "UP" },
    "sqs":  { "status": "UP" }
  }
}
```

> **Note on Docker Compose.** This project uses the Docker Compose v2 plugin, invoked as
> `docker compose` (with a space). If your environment still ships the legacy standalone binary, the
> equivalent command is `docker-compose up -d`.

> **Note on credentials.** Every example uses environment-variable placeholders
> (`${LOCALSTACK_AUTH_TOKEN}`, `${POSTGRES_PASSWORD}`, `${JWT_SECRET}`). No secret, token, or password
> is ever hardcoded in this repository — this is a binding migration constraint.

### Quick links (local URLs)

Once `docker compose up -d` and `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` are running,
the following endpoints and UIs are available locally. Ports are fixed by `docker-compose.yml`.

| Component | URL | Notes |
| :-------- | :-- | :---- |
| Application (REST API) | `http://localhost:8080` | Spring Boot app; base path `/api/**`. |
| Health & readiness | `http://localhost:8080/actuator/health` | Composite PostgreSQL / S3 / SQS indicators. |
| Metrics (Prometheus format) | `http://localhost:8080/actuator/prometheus` | Scraped by Prometheus. |
| PostgreSQL | `localhost:5432` | Database `carddemo` (credentials via env vars). |
| LocalStack (AWS) | `http://localhost:4566` | S3 / SQS / SNS endpoint. |
| Jaeger UI (tracing) | `http://localhost:16686` | OTLP ingest on `4317` (gRPC) / `4318` (HTTP). |
| Prometheus | `http://localhost:9090` | Metrics store and query UI. |
| Grafana | `http://localhost:3000` | Dashboards; import [`grafana-dashboard.json`](grafana-dashboard.json). |

---

## Configuration & Profiles

Configuration is profile-driven via Spring Boot YAML files under `src/main/resources/`:

| Profile | File | Purpose |
| :------ | :--- | :------ |
| *(default)* | `application.yml` | Base configuration: JPA, Flyway, actuator exposure, common AWS settings. The HTTP port defaults to `8080` (`${SERVER_PORT:8080}`). |
| `local` | `application-local.yml` | Local development against the Docker Compose stack; the AWS endpoint points at LocalStack (`http://localhost:4566`). Activated by `-Dspring-boot.run.profiles=local` when launching via `./mvnw spring-boot:run` (the plugin forks a separate JVM, so a Maven-JVM `-Dspring.profiles.active` is not propagated); for a packaged jar use `-Dspring.profiles.active=local` or `SPRING_PROFILES_ACTIVE=local`. |
| `test` | `application-test.yml` | Integration tests; the AWS endpoint and datasource are wired to Testcontainers / LocalStack. |

All secrets and connection details resolve from environment variables (for example
`${POSTGRES_PASSWORD}`, `${LOCALSTACK_AUTH_TOKEN}`, `${JWT_SECRET}`). Flyway migrations
(`V1` → `V2` → `V3`) are applied automatically on startup to provision and seed the PostgreSQL schema
**before** any online or batch flow executes.

---

## Domain Context

CardDemo is a **credit-card management** application. It lets users manage **accounts**, **credit
cards**, **customers**, and **transactions**, perform **bill payments**, submit **reports**, and (for
administrators) manage **users**. There are two user roles — a *regular user* who performs the
business functions and an *admin user* who performs user-administration functions — modelled by the
`UserType` enum (`USER` / `ADMIN`). Authentication is handled by Spring Security with BCrypt-hashed
credentials and stateless JWT claims; the original plaintext mainframe password field is upgraded to
BCrypt while preserving the login flow. Seed users are loaded by Flyway with **hashed** passwords —
no plaintext credentials live in the repository or in this guide.

The migrated capabilities form a **closed feature set, F-001 through F-022**: **17 online functions**
(exposed as REST endpoints) plus **5 batch capabilities** (the Spring Batch pipeline). The online
functions map one-to-one from the source CICS programs:

| Domain area | Online functions (REST) | Java services |
| :---------- | :---------------------- | :------------ |
| Authentication & menus | Sign-on, main menu (10-option routing), admin menu (4-option routing) | `AuthenticationService`, `MainMenuService`, `AdminMenuService` |
| Accounts | View, update (`@Transactional` + optimistic `@Version`) | `AccountViewService`, `AccountUpdateService` |
| Cards | List (7 rows/page), detail, update (optimistic locking) | `CardListService`, `CardDetailService`, `CardUpdateService` |
| Transactions | List (10 rows/page), detail, add (auto-ID) | `TransactionListService`, `TransactionDetailService`, `TransactionAddService` |
| Billing & reports | Bill payment, report submission (TDQ → SQS bridge) | `BillPaymentService`, `ReportSubmissionService` |
| User administration | List, add, update, delete users | `UserListService`, `UserAddService`, `UserUpdateService`, `UserDeleteService` |

Full request/response contracts for every endpoint are documented in
[`api-contracts.md`](api-contracts.md); the source-program-to-service mapping is in
[`../README.md`](../README.md#online-functions-rest).

### Entity-relationship overview

The **11 VSAM datasets** of the source application are re-platformed to **11 JPA entities** backed by
a PostgreSQL 16 schema (one table per dataset). The relationships are summarised below; for the
rendered diagrams **do not duplicate them here** — see
*Diagram 4 — Data Migration (VSAM → PostgreSQL via Flyway)* and
*Diagram 5 — Component Interaction (Request Lifecycle)* in
[`architecture-before-after.md`](architecture-before-after.md).

- **`Account`** `1 —— *` **`Card`** — an account has many cards; each card carries a foreign key to
  its owning account.
- **`Customer`** `1 —— *` **`Account`** — a customer holds one or more accounts; the linkage between
  customer, account, and card is resolved through **`CardCrossReference`** (the migrated `CXACAIX`
  alternate-index relationship).
- **`Transaction`** `* —— 1` **`Card`** / **`Account`** — each posted transaction references the card
  and account it applies to; the monetary amount is a `BigDecimal`.
- **Reference data (lookup tables):** **`TransactionType`**, **`TransactionCategory`** (composite
  key), **`TransactionCategoryBalance`** (composite key), and **`DisclosureGroup`** (composite key,
  carries the interest rate with a `DEFAULT` group fallback).
- **`UserSecurity`** — the security record (BCrypt password, `UserType`); the basis for sign-on.
- **`DailyTransaction`** — the staging entity for the daily-posting batch input (the migrated daily
  transaction file).

Composite-keyed entities use JPA `@EmbeddedId` classes under `model/key/` (for example
`TransactionCategoryBalanceId`, `TransactionCategoryId`, `DisclosureGroupId`). All monetary and rate
fields are `BigDecimal` to preserve exact COBOL fixed-point precision (see
[Common Pitfalls](#common-pitfalls)).

---

## Batch Pipeline

The source JCL job streams are re-hosted as **Spring Batch jobs**, orchestrated into a **5-stage
pipeline** that preserves the predecessor-success ordering and the JCL `COND` condition-code logic of
the mainframe. JCL `COND` parameters map to Spring Batch `ExitStatus` values evaluated by a
`JobExecutionDecider`, so a partial-failure return code from posting still allows downstream stages
where the original condition permitted it.

| Stage | Source (JCL + COBOL) | Spring Batch job | What it does |
| :---- | :------------------- | :--------------- | :----------- |
| 1 | `POSTTRAN.jcl` + `CBTRN02C` | `DailyTransactionPostingJob` | Daily posting; 4-stage validation cascade; reject codes 100–109 to S3. |
| 2 | `INTCALC.jcl` + `CBACT04C` | `InterestCalculationJob` | Interest calculation (`BigDecimal`, `RoundingMode.HALF_EVEN`). |
| 3 | `COMBTRAN.jcl` | `CombineTransactionsJob` | `Comparator` sort + bulk JPA insert (replaces DFSORT + IDCAMS `REPRO`). |
| 4a | `CREASTMT.JCL` + `CBSTM03A`/`B` | `StatementGenerationJob` | Statement generation (text + HTML → S3). |
| 4b | `TRANREPT.jcl` + `CBTRN03C` | `TransactionReportJob` | Date-filtered transaction report → S3. |

The flow is `POSTTRAN → INTCALC → COMBTRAN → {CREASTMT, TRANREPT}`. Stages **4a** and **4b** may run
concurrently after `COMBTRAN` via a Spring Batch `FlowBuilder.split()`. The overall sequencing and
the deciders are wired in `config/BatchConfig.java`, and the orchestration job is
`batch/jobs/BatchPipelineOrchestrator.java`. For the visual before/after comparison of the JCL job
stream and the Spring Batch flow, see *Diagram 3 — Batch Pipeline (Before / After)* in
[`architecture-before-after.md`](architecture-before-after.md).

### Triggering jobs locally

Batch jobs run inside the same Spring Boot application context. With the infrastructure up
(`docker compose up -d`) and the seed data loaded by Flyway, you can drive a job in any of these ways:

- **From the integration / end-to-end tests** — the most reproducible path. The end-to-end tests
  (`src/test/java/com/carddemo/e2e/`) launch the posting pipeline against real PostgreSQL and
  LocalStack containers:

  ```bash
  ./mvnw verify -Pintegration
  ```

- **On application startup** — boot-time job execution is **disabled by default**
  (`spring.batch.job.enabled: false` in `application.yml`), so the 5-stage pipeline is normally
  launched explicitly by `BatchPipelineOrchestrator` or the SQS report trigger rather than firing on
  boot. To run a single job at startup you must **both** re-enable the runner and name the job:

  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
      -Dspring-boot.run.arguments="--spring.batch.job.enabled=true --spring.batch.job.name=dailyTransactionPostingJob"
  ```

  The `--spring.batch.job.enabled=true` override is required; without it Spring Boot's
  `JobLauncherApplicationRunner` stays off and `--spring.batch.job.name` alone launches nothing.

- **Inspect inputs/outputs in LocalStack** — batch input is staged in the `carddemo-batch-input` S3
  bucket, rejections and reports land in `carddemo-batch-output`, and statements in
  `carddemo-statements`. List them with the AWS CLI against the LocalStack endpoint:

  ```bash
  aws --endpoint-url=http://localhost:4566 s3 ls s3://carddemo-batch-output/
  ```

> The canonical Gate 1 end-to-end exercise processes the `dailytran.txt` fixture through
> `DailyTransactionPostingJob` (file → validate → PostgreSQL + S3 rejections). See
> [`validation-gates.md`](validation-gates.md) for the gate evidence and the exact fixtures used.

---

## Common Pitfalls

These four pitfalls account for the overwhelming majority of newcomer mistakes on this codebase. Read
them before you touch money math, AWS clients, integration tests, or the database schema.

### 1. `BigDecimal` precision traps

All monetary and rate fields originate from COBOL `PIC` clauses with fixed decimal positions, so they
map to `java.math.BigDecimal` — **never** `double` or `float`. Floating-point types silently lose
precision and break behavioral parity.

- **Compare with `compareTo()`, not `equals()`.** `BigDecimal.equals()` is scale-sensitive
  (`new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false`), whereas `compareTo()` compares
  numeric value. Use `a.compareTo(b) == 0` for equality and `a.compareTo(b) > 0` / `< 0` for ordering.
- **Preserve the scale from the PIC clause.** A field declared `PIC S9(10)V99` has **scale 2**; a rate
  field keeps the scale its PIC clause defines. Set the scale explicitly when constructing or rounding
  values so persisted data matches the source layout exactly.
- **Round exactly like COBOL.** The interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` is
  reproduced with `BigDecimal.divide(divisor, scale, RoundingMode.HALF_EVEN)` — banker's rounding,
  matching the COBOL `COMPUTE`. Do not use the default `divide` (which throws on non-terminating
  decimals) or any other rounding mode.
- **Never use `double`/`float` for money** — not in entities, DTOs, processors, or test assertions.

### 2. LocalStack S3 path-style access

All AWS calls run against **LocalStack** at `http://localhost:4566` — there is no live AWS dependency.
The single most common S3 failure is virtual-host-style addressing, which does not work against
LocalStack.

- **Enable path-style access** on the S3 client (so requests are
  `http://localhost:4566/<bucket>/<key>` rather than `http://<bucket>.localhost:4566/<key>`). The
  `local` and `test` profiles configure this; if you build an S3 client by hand, set path-style
  explicitly.
- **Point the endpoint at** `http://localhost:4566` for both S3 and SQS in local and test runs.
- **Use the exact bucket and queue names** provisioned by `localstack-init/init-aws.sh`: buckets
  `carddemo-batch-input`, `carddemo-batch-output`, `carddemo-statements`, and the FIFO queue
  `carddemo-report-jobs.fifo`.
- **No auth token is required for the default stack.** `docker-compose.yml` defaults to the LocalStack
  **Community** image (`localstack/localstack:3.8`), which needs **no** `LOCALSTACK_AUTH_TOKEN` — the
  Compose file passes an empty default and the Community image ignores it. Only set the token if you
  override to a LocalStack **Pro** image (`export LOCALSTACK_IMAGE=localstack/localstack-pro:latest`
  and `export LOCALSTACK_AUTH_TOKEN=<token>`); when you do, supply it via your shell or secret store
  and never hardcode it.

### 3. Testcontainers Docker-socket permissions

Integration tests use **Testcontainers** to spin up real PostgreSQL and LocalStack containers, so a
working Docker environment is mandatory.

- **The Docker daemon must be running and the socket accessible** to your user. If `docker ps` fails
  or hangs, Testcontainers cannot start containers. On Linux, ensure your user is in the `docker`
  group (or that the daemon socket is readable); confirm with `docker ps` before running
  `./mvnw verify -Pintegration`.
- **Testcontainers 2.x module renames.** This project pins **Testcontainers 2.0.3**. The 2.x line
  renames its Maven modules with a `testcontainers-` prefix (for example `testcontainers-postgresql`,
  `testcontainers-localstack`, `testcontainers-junit-jupiter`) and relocates the container classes to
  `org.testcontainers.<module>` packages. If you copy a snippet from an older (1.x) tutorial, update
  both the artifact IDs and the imports — the bare 1.x artifact names are **not** managed by the
  2.0.3 BOM and will fail to resolve.

### 4. Flyway migration ordering

The PostgreSQL schema is owned entirely by **Flyway**, not Hibernate. Migrations apply on startup in
strict version order **before** any online or batch flow runs:

1. `V1__create_schema.sql` — the 11 tables (from the VSAM `DEFINE CLUSTER` layouts).
2. `V2__create_indexes.sql` — primary and alternate indexes (for example the `CXACAIX` cross-reference
   index and the transaction alternate index).
3. `V3__seed_data.sql` — seed and reference rows loaded from the 9 ASCII fixtures.

- **Never edit a migration that has already been applied.** Flyway records a checksum per migration;
  changing an applied file makes the checksum mismatch and the application will refuse to start. To
  change the schema, add a **new** `V4__...sql` (then `V5`, and so on) — see
  [Add an entity](#a-add-an-entity).
- Hibernate is configured to **validate** the schema, never to create or alter it. If validation
  fails at startup, the JPA entity and the Flyway-provisioned table have drifted — fix the entity or
  add a forward migration, do not let Hibernate auto-DDL.

---

## Extension Guides

Three concrete recipes for the most common changes. Real package paths under `src/main/java/com/carddemo/`
are shown so you can follow them directly.

> **Stay in scope.** The migration implements the closed feature set **F-001..F-022** and introduces
> no new business features. The walkthroughs below are written as **illustrations of the mechanics**;
> apply them when fixing or refactoring an existing in-scope capability, and clearly flag anything
> genuinely new as out of scope (see [Suggested Next Tasks](#suggested-next-tasks-out-of-scope)).
> Record any non-trivial design decision in [`../DECISION_LOG.md`](../DECISION_LOG.md) rather than in
> code comments.

### A. Add an entity

To introduce a new persistent record (for an in-scope dataset):

1. **Entity** — create `model/entity/<Name>.java` annotated with `@Entity` / `@Table`. Use
   `BigDecimal` for any monetary or rate field, and add `@Version` if the record needs optimistic
   locking (as `Account` and `Card` do).
2. **Composite key (if needed)** — if the record has a multi-field key, create
   `model/key/<Name>Id.java` as a `@EmbeddedId` class (follow `TransactionCategoryBalanceId` /
   `DisclosureGroupId`) and reference it with `@EmbeddedId` on the entity.
3. **Repository** — create `repository/<Name>Repository.java` extending a Spring Data interface
   (for example `JpaRepository<<Name>, <KeyType>>`). Add derived query methods as needed (for example
   `findByXrefAcctId`), mirroring the keyed/browse access of the source VSAM dataset.
4. **Flyway migration** — add a **new** forward migration (the next free version, for example
   `src/main/resources/db/migration/V4__add_<name>.sql`) to create the table and any indexes. **Do not
   edit `V1`/`V2`/`V3`** — they are already applied (see [Common Pitfalls](#common-pitfalls)).
5. **Verify** — run `./mvnw clean verify`; Hibernate validates the entity against the
   Flyway-provisioned table at startup, so a mismatch fails fast.

### B. Add a batch job

To add (or restructure) a Spring Batch job in the pipeline:

1. **Job** — create `batch/jobs/<Name>Job.java` defining the `Job` and its `Step`(s).
2. **Chunk components** — add the matching `batch/readers/<Name>Reader.java`,
   `batch/processors/<Name>Processor.java`, and `batch/writers/<Name>Writer.java`. Readers source from
   PostgreSQL or S3; processors hold the per-item business logic (the COBOL paragraph equivalents);
   writers persist to PostgreSQL and/or S3.
3. **Register** — wire the job, its steps, and any `JobExecutionDecider` (for condition-code logic)
   into `config/BatchConfig.java`, and slot it into the orchestration in
   `batch/jobs/BatchPipelineOrchestrator.java` if it belongs in the 5-stage flow.
4. **Metrics** — increment the established counters where relevant
   (`carddemo.batch.records.processed`, `carddemo.batch.records.rejected`) so the job is observable on
   the Grafana dashboard.
5. **Test** — add a unit test under `src/test/java/com/carddemo/unit/batch/` and an integration test
   under `src/test/java/com/carddemo/integration/batch/`; run `./mvnw verify -Pintegration`.

### C. Add an API endpoint

To expose (or adjust) a REST endpoint for an in-scope online function:

1. **DTOs** — define request/response objects in `model/dto/` (these mirror the BMS symbolic-map
   field contracts). Apply `jakarta.validation` constraints (`@Valid`, `@NotNull`, etc.).
2. **Service** — put the business logic in the appropriate `service/**` class (for example
   `service/account/`, `service/transaction/`). One online program → one service, consuming
   repositories and shared services (`service/shared/DateValidationService`,
   `ValidationLookupService`).
3. **Controller** — add the handler method to the matching `controller/<Area>Controller.java`,
   delegating to the service and returning the response DTO. Keep the URL aligned with the documented
   contract in [`api-contracts.md`](api-contracts.md).
4. **Secure it** — confirm the route's authorization in `config/SecurityConfig.java`. Endpoints are
   protected by Spring Security with JWT; admin-only routes require the `ADMIN` `UserType`.
5. **Document & test** — update [`api-contracts.md`](api-contracts.md), add a controller/service test,
   and run `./mvnw clean verify`.

---

## Suggested Next Tasks (out of scope)

The following enhancements were identified during the migration analysis but are **explicitly out of
scope** and **not implemented** — they fall outside the closed feature set F-001..F-022 and would
constitute feature expansion, which this project intentionally avoids. They are listed as a forward
backlog for teams that choose to extend the application beyond strict parity:

1. **OpenAPI / Swagger generation** — auto-generate machine-readable API documentation and an
   interactive UI from the controllers and DTOs.
2. **Cursor-based pagination** — replace the page/offset browse model (7 rows/page for cards, 10
   rows/page for transactions) with cursor-based pagination for large result sets.
3. **Redis caching for reference data** — cache the rarely-changing lookup tables
   (`TransactionType`, `TransactionCategory`, `DisclosureGroup`) to reduce database round-trips.
4. **Async batch submission via REST** — expose an endpoint that enqueues and launches batch jobs
   asynchronously, returning a job-execution handle to poll.
5. **Connection-pool tuning documentation** — document and tune the JDBC connection pool (sizing,
   timeouts) for production-scale load.

> These items are **future / not implemented**. Pursuing any of them is a deliberate scope decision
> that should be agreed and recorded in [`../DECISION_LOG.md`](../DECISION_LOG.md) before work begins.

---

## Where to Go Next

| Document | What you'll find there |
| :------- | :--------------------- |
| [`../README.md`](../README.md) | Project entry point: overview, technology stack, build/run, inventory, quality gates. |
| [`architecture-before-after.md`](architecture-before-after.md) | The six named Mermaid before/after diagrams (z/OS topology, Java/AWS topology, batch pipeline, data migration, component interaction, authentication). |
| [`api-contracts.md`](api-contracts.md) | REST, SQS, and S3 contract documentation for every endpoint and message. |
| [`validation-gates.md`](validation-gates.md) | Evidence for the 8 validation gates (end-to-end boundary, zero-warning build, performance, named artifacts, contracts, unsafe-code audit, scope, sign-off). |
| [`executive-presentation.html`](executive-presentation.html) | Self-contained reveal.js deck for non-technical leadership. |
| [`../DECISION_LOG.md`](../DECISION_LOG.md) | Rationale, alternatives, and risks for every non-trivial migration decision. |
| [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) | Bidirectional COBOL ↔ Java mapping with 100% paragraph coverage. |

For traceability back to the original mainframe code, all documents reference the source repository
commit SHA **`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`). The COBOL sources are **not copied** into
this repository; the [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) is the authoritative map
from each COBOL paragraph to its Java counterpart, and back.

---

*CardDemo Java — Onboarding & Continued Development Guide. Platform: AWS exercised locally via
LocalStack, with zero live AWS dependencies.*
