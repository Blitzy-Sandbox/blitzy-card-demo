# CardDemo Java — Onboarding & Continued Development Guide

> A single, self-contained guide that takes a developer from a **clean machine** to a **running,
> modifiable** CardDemo Java application — with no unanswered questions. It complements the project
> [`README.md`](../README.md) with the operational detail, domain context, common pitfalls, and
> step-by-step extension walkthroughs needed to be productive on day one.

CardDemo Java is a **Java 25 LTS + Spring Boot 3.x** re-platforming of the AWS CardDemo credit-card
management application, migrated from its original COBOL / CICS / VSAM / JCL / BMS mainframe
implementation with **100% behavioral parity** to the source. The COBOL programs, copybooks, and JCL
are **translated, not copied** into this repository; traceability to the original system is anchored to
source commit SHA **`27d6c6f`** (from the version string `CardDemo_v1.0-15-g27d6c6f-68`) and recorded
in [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md).

This guide assumes no prior mainframe knowledge. Everything you need to build, run, test, understand,
and safely extend the application is here or linked from the [Where to Go Next](#where-to-go-next)
table at the end.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Build and Run](#build-and-run)
  - [Local service quick links](#local-service-quick-links)
- [Project Layout Orientation](#project-layout-orientation)
- [Domain Context](#domain-context)
  - [Functional scope (F-001 through F-022)](#functional-scope-f-001-through-f-022)
  - [Entity-relationship overview (11 entities)](#entity-relationship-overview-11-entities)
  - [Seed users and credentials](#seed-users-and-credentials)
- [Batch Pipeline](#batch-pipeline)
- [Common Pitfalls](#common-pitfalls)
- [Extension Guides](#extension-guides)
- [Suggested Next Tasks (Out of Scope)](#suggested-next-tasks-out-of-scope)
- [Where to Go Next](#where-to-go-next)

---

## Prerequisites

A clean development machine needs only the tools below. **Maven itself is optional** — the repository
ships the **Maven Wrapper** (`./mvnw`), which downloads the correct Maven version automatically, so you
can build without a system Maven install.

| Tool               | Minimum version | Purpose                                                          |
| :----------------- | :-------------- | :--------------------------------------------------------------- |
| **JDK**            | 25 (LTS)        | Compile and run the application                                  |
| **Maven**          | 3.9+            | Build tool — or just use the bundled `./mvnw` wrapper            |
| **Docker**         | 24+             | Run PostgreSQL, LocalStack, and the observability stack          |
| **Docker Compose** | v2 plugin       | Orchestrate the local infrastructure (`docker compose ...`)      |
| **AWS CLI**        | 2.x (or 1.x)    | Inspect S3 buckets / SQS queues hosted by LocalStack             |

Verify your toolchain — each command should report at least the minimum version above:

```bash
java -version            # openjdk version "25" ... (25 LTS)
mvn -version             # Apache Maven 3.9.x  (optional — ./mvnw works without it)
docker --version         # Docker version 24.x or newer
docker compose version   # Docker Compose version v2.x
aws --version            # aws-cli/2.x ... (1.x is also accepted)
```

Expected, abbreviated output:

```text
openjdk version "25" 2025-09-16 LTS
Apache Maven 3.9.9
Docker version 28.5.2, build ...
Docker Compose version v2.x
aws-cli/2.x Python/3.x ...
```

> If `java -version` reports anything below 25, set `JAVA_HOME` to a JDK 25 installation and ensure its
> `bin/` directory is on your `PATH` before continuing. The build targets Java 25 bytecode and will not
> compile on an older JDK.

> **Managed stack versions.** The application is built on **Spring Boot 3.5.15** with **Spring Cloud AWS
> 3.3.0** (`io.awspring.cloud` — S3 / SQS / SNS), persists to **PostgreSQL 16**, and uses
> **Testcontainers 2.0.3** for integration tests. These versions are pinned in `pom.xml`; see the
> *Technology Stack* table in [`../README.md`](../README.md) for the complete list. Do not substitute
> other versions.

---

## Build and Run

This section takes you from a freshly cloned repository to a running, healthy application. Every
artifact referenced here — `docker-compose.yml`, `Dockerfile`, `application-local.yml`,
`application-test.yml`, and the Flyway migrations `V1__create_schema.sql`, `V2__create_indexes.sql`,
and `V3__seed_data.sql` — is present in the repository, so all commands below are copy-paste accurate.
The bundled Maven Wrapper (`./mvnw`) is used throughout, so a system Maven install is not required.

### 1. Build and test (no running services required)

```bash
# 1. Clone the repository and enter the project
git clone <repository-url> && cd carddemo-java

# 2. Build the application, run the unit tests, and enforce the quality gates
#    (zero-warning build — Gate 2, ≥80% JaCoCo line coverage, OWASP dependency-check)
./mvnw clean verify

# 3. Run the integration / E2E suite (Testcontainers-managed PostgreSQL + LocalStack)
./mvnw verify -Pintegration
```

1. **Clone** — only the JDK is needed; no running services are required for the unit build.
2. **`./mvnw clean verify`** — compiles, runs unit tests, and enforces the zero-warning build (Gate 2),
   ≥80% **JaCoCo** (`0.8.14`) line coverage, and the **OWASP dependency-check** (`12.1.0`, zero
   critical/high CVEs). The OWASP NVD feed requires network access; in an offline environment append
   `-Ddependency-check.skip=true` (the authoritative CVE scan runs in CI — see
   [`validation-gates.md`](validation-gates.md) Gate 8). A green run means the codebase is sound.
3. **`./mvnw verify -Pintegration`** — runs the Failsafe integration/E2E suite (`**/*IT.java`) on the
   `test` profile (`application-test.yml`). These tests provision and tear down their own ephemeral
   PostgreSQL and LocalStack containers via **Testcontainers**, so they require only a running Docker
   daemon — no manually started services and no live AWS.

### 2. Run the full local stack

```bash
# 1. Provide the required secrets the stack needs (never hardcode them).
#    docker-compose.yml has NO committed secret defaults, so these must be set first:
export POSTGRES_PASSWORD=<choose-a-local-password>
export GRAFANA_ADMIN_PASSWORD=<choose-a-local-password>
export JWT_SECRET=<a-random-string-of-at-least-32-characters>
export LOCALSTACK_AUTH_TOKEN=<your-localstack-token>

# 2. Start local infrastructure: PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana
docker compose up -d

# 3. Run the application with the local profile
./mvnw spring-boot:run -Dspring.profiles.active=local

# 4. Verify the application is healthy (expect "status":"UP")
curl http://localhost:8080/actuator/health
```

1. **`export …` (required secrets)** — `docker-compose.yml` carries **no committed secret defaults**.
   `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `JWT_SECRET` (at least 32 characters), and
   `LOCALSTACK_AUTH_TOKEN` must be supplied via the environment (or a local, git-ignored `.env`) before
   bringing up Compose, or the stack fails fast with an error naming the missing variable. They are
   **never** committed or hardcoded. (The AWS access keys default to LocalStack's documented `test`
   emulator dummies, which are not real secrets.)
2. **`docker compose up -d`** — brings up the datasource and AWS endpoints defined in
   `docker-compose.yml`. On systems that still use the standalone Compose v1 binary, substitute
   `docker-compose up -d`. (If your `LOCALSTACK_AUTH_TOKEN` does not unlock LocalStack Pro, override the
   image with `LOCALSTACK_IMAGE=localstack/localstack:4.5.0` and `ACTIVATE_PRO=0`; the Community image
   fully covers S3/SQS/SNS.)
3. **`./mvnw spring-boot:run -Dspring.profiles.active=local`** — starts the app on the `local` profile
   (`application-local.yml`), pointing the datasource at the Compose PostgreSQL and the AWS clients at
   the LocalStack endpoint (`http://localhost:4566`). Equivalent to `./mvnw spring-boot:run -Plocal`.
   On startup, Flyway applies `V1`→`V2`→`V3` to provision and seed the schema before any flow runs.
4. **`curl http://localhost:8080/actuator/health`** — a healthy response is `UP` with composite
   indicators for **PostgreSQL**, **S3** bucket accessibility, and **SQS** availability.

### Local service quick links

With `docker compose up -d` and the application running, the following endpoints and UIs are available
locally. Ports reflect the services defined in `docker-compose.yml`; adjust there if they collide with
other local processes.

| Service / endpoint        | URL                                            | Provided by                  |
| :------------------------ | :--------------------------------------------- | :--------------------------- |
| Application API           | `http://localhost:8080`                        | Spring Boot (app)            |
| Application health        | `http://localhost:8080/actuator/health`        | Spring Boot Actuator         |
| Prometheus scrape metrics | `http://localhost:8080/actuator/prometheus`    | Micrometer Prometheus        |
| Jaeger UI (tracing)       | `http://localhost:16686`                       | Jaeger (OTLP on 4317 / 4318) |
| Prometheus UI             | `http://localhost:9090`                        | Prometheus (Docker Compose)  |
| Grafana UI                | `http://localhost:3000`                        | Grafana (Docker Compose)     |
| PostgreSQL                | `localhost:5432` (database `carddemo`)         | PostgreSQL 16 (Docker)       |
| LocalStack (AWS emulator) | `http://localhost:4566`                        | LocalStack (Docker)          |

Inspect the LocalStack-hosted AWS resources with the AWS CLI:

```bash
aws --endpoint-url=http://localhost:4566 s3 ls
aws --endpoint-url=http://localhost:4566 sqs list-queues
```

The `localstack-init/init-aws.sh` hook provisions, on startup, the S3 buckets `carddemo-batch-input`,
`carddemo-batch-output`, and `carddemo-statements`, the SQS FIFO queue `carddemo-report-jobs.fifo`, and
the SNS topic `carddemo-notifications`.

---

## Project Layout Orientation

The base Java package is **`com.carddemo`** across all source and test trees. Knowing where each kind
of component lives makes the [extension guides](#extension-guides) below quick to follow.

```text
carddemo-java/
├── pom.xml                       # Maven build (Java 25, Spring Boot 3.x BOM)
├── mvnw, mvnw.cmd, .mvn/         # Maven Wrapper
├── Dockerfile                    # Application container image
├── docker-compose.yml            # PostgreSQL + LocalStack + Jaeger + Prometheus + Grafana + app
├── localstack-init/init-aws.sh   # Creates S3 buckets + SQS FIFO queue on LocalStack startup
├── README.md                     # Project entry point
├── DECISION_LOG.md               # Rationale for every non-trivial decision
├── TRACEABILITY_MATRIX.md        # Bidirectional COBOL ↔ Java mapping (100% coverage)
├── docs/                         # This guide, architecture, API contracts, gates, exec deck
└── src/
    ├── main/java/com/carddemo/
    │   ├── CardDemoApplication.java   # Spring Boot bootstrap
    │   ├── config/                    # Security, Batch, AWS, JPA, Observability, Web
    │   ├── model/                     # entity/ (JPA), dto/, enums/, key/ (composite keys)
    │   ├── repository/                # Spring Data JPA repositories (one per VSAM KSDS)
    │   ├── service/                   # Business logic (one service per online COBOL program)
    │   ├── controller/                # REST controllers (mapped from BMS mapsets)
    │   ├── batch/                     # jobs/, processors/, readers/, writers/
    │   ├── exception/                 # CardDemoException hierarchy (← FILE STATUS codes)
    │   └── observability/             # Correlation-ID filter, metrics, health indicators
    └── main/resources/
        ├── application.yml            # Base configuration
        ├── application-local.yml      # Local profile (Docker Compose infra)
        ├── application-test.yml       # Test profile (Testcontainers)
        ├── db/migration/              # Flyway V1 schema, V2 indexes, V3 seed data
        ├── validation/                # NANPA / state / ZIP reference data (JSON)
        └── logback-spring.xml         # Structured JSON logging
```

The request path follows a strict layering — **Controller → Service → Repository → Database** — with
cross-cutting concerns (security, observability, transactions) applied as Spring infrastructure. The
full topology is illustrated in [`architecture-before-after.md`](architecture-before-after.md); see
*Diagram 5 — Component Interaction: Controller → Service → Repository → Database (Before/After)*.

---

## Domain Context

CardDemo is a **credit-card management** application. Business users manage **accounts**, **credit
cards**, **customers**, and **transactions**, make **bill payments**, submit **reports**, and (for
administrators) perform **user administration**. There are two roles:

- **Regular user** — performs the back-office account, card, transaction, bill-payment, and report
  functions.
- **Admin user** — performs user-administration functions (list, add, update, delete application users).

For the original mainframe context (Description, Technologies, screen flows), consult the upstream
source README at commit `27d6c6f`; this guide describes the **Java 25 + Spring Boot** target only and
does not reproduce the z/OS install steps (datasets, IDCAMS, CEDA, CICS resource definitions).

### Functional scope (F-001 through F-022)

The migration implements exactly the **closed set of capabilities F-001 through F-022** — **17 online
functions + 5 batch processing capabilities**. No new endpoints, entities, or business rules are added
(no feature expansion). Each former CICS transaction and BMS mapset maps to a Spring service and a REST
endpoint; the legacy transaction IDs and program names are retained purely for traceability.

| Legacy Tran | Legacy Program | Function           | Java service               | REST endpoint                  |
| :---------- | :------------- | :----------------- | :------------------------- | :----------------------------- |
| CC00        | COSGN00C       | Sign-on            | `AuthenticationService`    | `POST /api/auth/signin`        |
| CM00        | COMEN01C       | Main menu          | `MainMenuService`          | `GET  /api/menu/main`          |
| CA00        | COADM01C       | Admin menu         | `AdminMenuService`         | `GET  /api/menu/admin`         |
| CAVW        | COACTVWC       | Account view       | `AccountViewService`       | `GET  /api/accounts/{id}`      |
| CAUP        | COACTUPC       | Account update     | `AccountUpdateService`     | `PUT  /api/accounts/{id}`      |
| CCLI        | COCRDLIC       | Card list          | `CardListService`          | `GET  /api/cards`              |
| CCDL        | COCRDSLC       | Card detail        | `CardDetailService`        | `GET  /api/cards/{id}`         |
| CCUP        | COCRDUPC       | Card update        | `CardUpdateService`        | `PUT  /api/cards/{id}`         |
| CT00        | COTRN00C       | Transaction list   | `TransactionListService`   | `GET  /api/transactions`       |
| CT01        | COTRN01C       | Transaction detail | `TransactionDetailService` | `GET  /api/transactions/{id}`  |
| CT02        | COTRN02C       | Transaction add    | `TransactionAddService`    | `POST /api/transactions`       |
| CB00        | COBIL00C       | Bill payment       | `BillPaymentService`       | `POST /api/billing/pay`        |
| CR00        | CORPT00C       | Report submission  | `ReportSubmissionService`  | `POST /api/reports/submit`     |
| CU00        | COUSR00C       | List users         | `UserListService`          | `GET  /api/admin/users`        |
| CU01        | COUSR01C       | Add user           | `UserAddService`           | `POST /api/admin/users`        |
| CU02        | COUSR02C       | Update user        | `UserUpdateService`        | `PUT  /api/admin/users/{id}`   |
| CU03        | COUSR03C       | Delete user        | `UserDeleteService`        | `DELETE /api/admin/users/{id}` |

The remaining capabilities are the **5 batch processing** jobs described in [Batch Pipeline](#batch-pipeline)
below. The complete REST / SQS / S3 contract for every endpoint is documented in
[`api-contracts.md`](api-contracts.md).

### Entity-relationship overview (11 entities)

The 11 VSAM datasets are re-platformed to **11 JPA entities** persisted in PostgreSQL 16. The key
relationships are summarized below (the full topology and the VSAM→PostgreSQL migration flow are
diagrammed in [`architecture-before-after.md`](architecture-before-after.md) — see *Diagram 2 —
After-State Java/AWS Architecture* and *Diagram 4 — Data Migration: VSAM → PostgreSQL via Flyway
(Before/After)*; the diagrams are intentionally **not duplicated** here).

| Entity                         | Role                | Key relationships                                                   |
| :----------------------------- | :------------------ | :------------------------------------------------------------------ |
| `Account`                      | Core                | An account has many `Card`s (1 — *)                                 |
| `Card`                         | Core                | Each card belongs to one `Account`                                  |
| `Customer`                     | Core                | A customer owns one or more `Account`s, linked via `CardCrossReference` |
| `CardCrossReference`           | Linkage             | Joins `Card` ↔ `Account` ↔ `Customer` (← the `CXACAIX` alternate index) |
| `Transaction`                  | Core                | References a `Card` / `Account`; `BigDecimal` amount                |
| `TransactionCategoryBalance`   | Reference / balance | Composite key (account + type + category); rolling category balance |
| `DisclosureGroup`              | Reference           | Composite key; carries the `BigDecimal` interest rate (`DEFAULT` fallback) |
| `TransactionType`              | Reference           | Transaction-type lookup                                             |
| `TransactionCategory`          | Reference           | Composite key; transaction-category lookup                          |
| `UserSecurity`                 | Security            | Application users; password stored **BCrypt-hashed**                |
| `DailyTransaction`             | Staging             | Inbound daily-transaction staging consumed by the posting job       |

Composite-keyed reference entities (`TransactionCategoryBalance`, `DisclosureGroup`,
`TransactionCategory`) use dedicated key classes under `com.carddemo.model.key`.

### Seed users and credentials

The `V3__seed_data.sql` migration provisions **ten** application users (five admins and five regular
users) so you can sign in immediately. The two canonical accounts for day-to-day development are:

| User ID    | Role    | Use for                           |
| :--------- | :------ | :-------------------------------- |
| `ADMIN001` | `ADMIN` | Admin / user-administration flows |
| `USER0001` | `USER`  | Back-office user flows            |

> The full set is `ADMIN001`–`ADMIN005` (role `ADMIN`) and `USER0001`–`USER0005` (role `USER`).

**Password:** every seed user signs in with `PASSWORD`. Passwords are stored **only as BCrypt hashes**
(cost 10) in `V3__seed_data.sql` — never in plaintext, and there is no `SEED_USER_PASSWORD` (or similar)
environment placeholder. The fixed seed hashes exist solely to provide a working local/demo login;
production deployments rotate them through the user-administration endpoints.

Sign-on is **case-insensitive**: `AuthenticationService` upper-cases (and trims) the user id and
upper-cases the password before the BCrypt check, faithfully reproducing the COBOL
`FUNCTION UPPER-CASE` normalization in `COSGN00C` (see `DECISION_LOG.md` D-017). So `admin001` /
`password` is equivalent to `ADMIN001` / `PASSWORD`.

Authenticate via `POST /api/auth/signin`; the response carries a JWT used as a bearer token on
subsequent requests (see [`api-contracts.md`](api-contracts.md) §2.1). The JWT signing secret resolves
from the `${JWT_SECRET}` environment variable and is never committed.

---

## Batch Pipeline

The overnight processing that ran as **JCL job streams** through JES is re-hosted as **Spring Batch**
jobs, preserving the original **5-stage** sequential pipeline and its **condition-code** logic. JCL
`COND` parameters map to Spring Batch `ExitStatus` plus `JobExecutionDecider` deciders, so a
partial-failure return code from an upstream stage still permits downstream stages where the original
condition allowed it. The legacy `DFSORT` SORT + IDCAMS `REPRO` step is replaced by a Java `Comparator`
followed by a bulk JPA insert.

| Stage | Legacy job | Legacy program | Spring Batch job             | Responsibility                                   |
| :---- | :--------- | :------------- | :--------------------------- | :----------------------------------------------- |
| 1     | POSTTRAN   | CBTRN02C       | `DailyTransactionPostingJob` | 4-stage validation cascade; post to DB + S3 rejects |
| 2     | INTCALC    | CBACT04C       | `InterestCalculationJob`     | Interest calculation (`DEFAULT` disclosure-group fallback) |
| 3     | COMBTRAN   | SORT / IDCAMS  | `CombineTransactionsJob`     | Combine system + daily txns (`Comparator` + bulk insert) |
| 4a    | CREASTMT   | CBSTM03A/B     | `StatementGenerationJob`     | Statement generation (text + HTML to S3)         |
| 4b    | TRANREPT   | CBTRN03C       | `TransactionReportJob`       | Date-filtered transaction report to S3           |

Pipeline ordering: **POSTTRAN → INTCALC → COMBTRAN → (CREASTMT 4a ∥ TRANREPT 4b)**. After COMBTRAN
succeeds, stages 4a and 4b may run **concurrently** via a Spring Batch `FlowBuilder.split()`; the full
flow is coordinated by `BatchPipelineOrchestrator`. See *Diagram 3 — Batch Pipeline (Before/After)* in
[`architecture-before-after.md`](architecture-before-after.md).

**Triggering jobs locally.** With the infrastructure up (`docker compose up -d`) and the app running on
the `local` profile, the pipeline can be exercised end-to-end. Spring Batch jobs are registered in
`config/BatchConfig.java` and run within the application context; the `dailytran.txt` fixture (loaded by
the `V3` seed migration) is the canonical end-to-end input for the posting job. The online-to-batch
bridge — the report-submission step (`CORPT00C` → SQS) — is triggered by `POST /api/reports/submit`,
which publishes a message to the `carddemo-report-jobs.fifo` queue that the report job consumes. Batch
progress is observable through the metrics `carddemo.batch.records.processed` and
`carddemo.batch.records.rejected` (reason-tagged) on `/actuator/prometheus`, and end-to-end gate
evidence for processing `dailytran.txt` is recorded in [`validation-gates.md`](validation-gates.md).

---

## Common Pitfalls

These four issues account for the overwhelming majority of onboarding friction. Read them before your
first change.

### 1. `BigDecimal` precision traps

All monetary and decimal fields originate from COBOL `COMP-3` / `PIC` clauses and are mapped to
`java.math.BigDecimal` to preserve exact fixed-point precision. **Never** use `double` or `float` for
money — binary floating point cannot represent decimal currency exactly and breaks parity.

- **Compare with `compareTo()`, never `equals()`.** `BigDecimal.equals()` is *scale-sensitive*
  (`new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false`), whereas `compareTo()` compares
  numeric value (`... .compareTo(...) == 0` is `true`). Use `compareTo()` for all value comparisons.
- **Preserve the scale from the PIC clause.** A `PIC S9(10)V99` field has **scale 2**; construct and
  persist values at that scale (e.g., `value.setScale(2, RoundingMode.HALF_EVEN)`), matching the source
  record layout exactly.
- **Use `HALF_EVEN` rounding for the interest formula.** The interest computation
  `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` is reproduced with
  `monthlyInterest = catBal.multiply(rate).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN)`.
  Always pass an explicit scale and `RoundingMode` to `divide(...)`; an unbounded division can throw
  `ArithmeticException` on a non-terminating decimal.

### 2. LocalStack S3 path-style access

All AWS calls run against **LocalStack** at `http://localhost:4566` — there are **no live AWS
dependencies**. The most common failure is the S3 client defaulting to *virtual-host-style* addressing
(`http://<bucket>.localhost:4566`), which LocalStack cannot resolve.

- **Enable path-style access** on the S3 client so URLs are formed as
  `http://localhost:4566/<bucket>/<key>`. With Spring Cloud AWS this is the
  `spring.cloud.aws.s3.path-style-access-enabled: true` property (set in both `application-test.yml`
  and `application-local.yml`).
- **Use the LocalStack endpoint** `http://localhost:4566`, not a real AWS region endpoint.
- **Use the exact bucket / queue names**: buckets `carddemo-batch-input`, `carddemo-batch-output`,
  `carddemo-statements`; FIFO queue `carddemo-report-jobs.fifo`. These are provisioned by
  `localstack-init/init-aws.sh` and must match the values in `config/AwsConfig.java` and the YAML
  profiles verbatim.
- Credentials for LocalStack are read from the standard environment variables (`AWS_ACCESS_KEY_ID`,
  `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`); any non-empty value is accepted. The
  `${LOCALSTACK_AUTH_TOKEN}` is supplied via environment variable only.

### 3. Testcontainers Docker-socket permissions

The integration suite (`./mvnw verify -Pintegration`) starts ephemeral PostgreSQL and LocalStack
containers via **Testcontainers**. It needs a reachable Docker daemon.

- Ensure the **Docker daemon is running** and your user can access the Docker socket
  (`/var/run/docker.sock`). A `Could not find a valid Docker environment` error almost always means the
  socket is missing or not permitted for the current user.
- Verify with `docker ps` before running the suite; if it fails for your user, add the user to the
  `docker` group (or use a rootless/socket configuration appropriate to your platform) and re-log.
- This project uses **Testcontainers 2.x**, which **renames the modules** with a `testcontainers-`
  prefix (`testcontainers-postgresql`, `testcontainers-localstack`, `testcontainers-junit-jupiter`) and
  relocates the container classes under `org.testcontainers.<module>`. Import from the new locations;
  artifact versions are coordinated by the `testcontainers-bom` in `pom.xml` (pinned to 2.0.3).

### 4. Flyway migration ordering

The PostgreSQL schema is created and seeded by **Flyway** migrations that run automatically on
application startup, **before** any online or batch flow executes. The intended order is strict:

```text
V1__create_schema.sql   →   V2__create_indexes.sql   →   V3__seed_data.sql
```

- `V1` creates the 11 tables (from the VSAM `DEFINE CLUSTER` layouts), `V2` adds the primary and
  alternate indexes (e.g., the `CXACAIX` cross-reference index and the transaction AIX), and `V3` loads
  the 9 ASCII fixtures as seed/reference rows (including the 10 BCrypt-hashed seed users).
- **Never edit a migration that has already been applied.** Flyway records a checksum per migration; a
  changed checksum on an applied version causes a validation failure on the next startup. To change the
  schema, **add a new** `V4__…`-and-higher migration rather than modifying `V1`–`V3`.
- If you need a clean database during development, drop and recreate the `carddemo` schema (or recreate
  the PostgreSQL container) so Flyway re-applies from `V1`.

---

## Extension Guides

Three concrete walkthroughs for the most common changes, referencing the real package paths under
`com.carddemo`. The steps illustrate the project's layering conventions; keep any actual change within
the migrated **F-001 through F-022** scope (no feature expansion). Examples that introduce new domain
shapes are illustrations of *how* to extend the layering, not a mandate to add features.

### (a) Add an entity

1. **Entity** — create the JPA class under `src/main/java/com/carddemo/model/entity/` (e.g.,
   `model/entity/MyRecord.java`). Annotate with `@Entity` / `@Table`, map every column with the correct
   type, and use `java.math.BigDecimal` for any decimal/monetary field (preserving the source scale).
   Add `@Version` if the record needs optimistic locking (as `Account` and `Card` do).
2. **Composite key (if needed)** — if the table has a multi-column primary key, create an
   `@Embeddable` key class under `model/key/` (e.g., `key/MyRecordId.java`) and reference it from the
   entity with `@EmbeddedId`, mirroring `TransactionCategoryBalance` / `DisclosureGroup` /
   `TransactionCategory`.
3. **Repository** — create a Spring Data interface under `repository/` (e.g.,
   `repository/MyRecordRepository.java`) extending `JpaRepository<MyRecord, KeyType>`. Add derived
   query methods (`findBy…`) as needed.
4. **Schema migration** — add a **new** Flyway migration `src/main/resources/db/migration/V4__….sql`
   (never edit an applied migration) creating the table and any indexes. Seed rows belong in a separate
   higher-numbered seed migration if required.
5. **Tests** — add a repository integration test (`…IT.java`) so it runs under `-Pintegration` against
   Testcontainers PostgreSQL.

### (b) Add a batch job

1. **Job** — create the job class under `batch/jobs/` (e.g., `batch/jobs/MyBatchJob.java`) defining the
   `Job` and its `Step`(s).
2. **Processor / reader / writer** — add the chunk components under `batch/processors/`,
   `batch/readers/`, and `batch/writers/` respectively (an `ItemReader` to source records, an
   `ItemProcessor` for per-item logic, and an `ItemWriter` to persist to the database and/or S3),
   following the existing posting/interest/statement components.
3. **Register the job** — wire the `Job`, `Step`, and components as Spring beans in
   `config/BatchConfig.java`, which holds the job/step registry and the batch transaction manager.
4. **Pipeline placement** — if the job participates in the nightly pipeline, add it to the flow in
   `batch/jobs/BatchPipelineOrchestrator.java`, preserving sequential dependencies and condition-code
   (`ExitStatus` / `JobExecutionDecider`) semantics; use `FlowBuilder.split()` only for genuinely
   parallel stages (as with CREASTMT 4a ∥ TRANREPT 4b).
5. **Tests** — add a `spring-batch-test` job-launcher test plus an end-to-end `…IT.java` exercising the
   real input contract.

### (c) Add an API endpoint

1. **DTO** — define request/response records under `model/dto/` (e.g., `model/dto/MyRequest.java`,
   `model/dto/MyResponse.java`). DTOs mirror the BMS symbolic-map field contracts and decouple the REST
   surface from JPA entities. Add `jakarta.validation` constraints (`@NotNull`, `@Size`, …) and validate
   with `@Valid`.
2. **Service** — implement the business logic in a service class under `service/**` (e.g.,
   `service/account/MyService.java`), one cohesive service per capability, consuming repositories and
   shared services (`DateValidationService`, `ValidationLookupService`, `FileStatusMapper`). Annotate
   write operations that must be atomic with `@Transactional` (rollback-on-exception), as
   `AccountUpdateService` does for the dual-record update.
3. **Controller** — add the endpoint method to the appropriate REST controller under `controller/`
   (e.g., `controller/AccountController.java`), delegating to the service and returning the response
   DTO. Keep the URL and verb consistent with the [`api-contracts.md`](api-contracts.md) conventions.
4. **Security** — confirm the route's authorization in `config/SecurityConfig.java` (the Spring
   Security filter chain). Admin-only routes require the `ADMIN` role; all routes are JWT-authenticated
   (stateless) except the sign-in endpoint.
5. **Tests** — add a `@WebMvcTest`/MockMvc unit test for the controller and a service unit test; add an
   `…IT.java` if the endpoint touches the database or AWS.

---

## Suggested Next Tasks (Out of Scope)

The following enhancements were **identified during analysis** but are deliberately **out of scope** for
this migration. They are **not implemented** here and would constitute **feature expansion** beyond the
closed F-001 through F-022 set, so they are recorded as candidate future work only — not commitments:

1. **OpenAPI / Swagger generation** — auto-generate interactive API documentation from the controllers.
2. **Cursor-based pagination** — replace offset/page-number browsing with cursor (keyset) pagination for
   the list endpoints.
3. **Redis caching for reference data** — cache the rarely changing reference tables
   (`DisclosureGroup`, `TransactionType`, `TransactionCategory`) to reduce database round-trips.
4. **Async batch submission via REST** — expose an endpoint to trigger batch jobs asynchronously and
   poll for completion status.
5. **Connection-pool tuning documentation** — document and benchmark HikariCP pool sizing for
   production-like load.

> These items are listed for orientation and planning. Implementing any of them is a separate decision
> outside the parity-focused migration; record the rationale in
> [`../DECISION_LOG.md`](../DECISION_LOG.md) before starting.

---

## Where to Go Next

| Document                                                     | What you'll find there                                                   |
| :----------------------------------------------------------- | :----------------------------------------------------------------------- |
| [`../README.md`](../README.md)                               | Project entry point: overview, technology stack, build/run, quality gates |
| [`architecture-before-after.md`](architecture-before-after.md) | Mermaid before/after architecture diagrams (referenced by name above)  |
| [`api-contracts.md`](api-contracts.md)                       | REST / SQS / S3 external interface contracts                             |
| [`validation-gates.md`](validation-gates.md)                 | Evidence for the 8 validation gates (incl. end-to-end `dailytran.txt`)   |
| [`executive-presentation.html`](executive-presentation.html) | Self-contained reveal.js deck for non-technical leadership               |
| [`grafana-dashboard.json`](grafana-dashboard.json)           | Importable Grafana observability dashboard                               |
| [`../DECISION_LOG.md`](../DECISION_LOG.md)                   | Rationale, alternatives, and risks for every non-trivial decision        |
| [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md)     | Bidirectional COBOL ↔ Java mapping at 100% paragraph coverage            |

**Source traceability.** The COBOL sources are **not copied** into this repository. Every behavior,
record layout, and contract traces back to the original AWS CardDemo application at commit SHA
**`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`); the per-paragraph mapping lives in
[`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md).

---

*This guide is part of the CardDemo Java documentation set. The application is released under the
**Apache License 2.0**, consistent with the upstream AWS CardDemo reference application.*
