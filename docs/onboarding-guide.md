# CardDemo — Onboarding & Continued Development Guide

> **Who this is for:** a developer sitting at a **clean machine** who needs to go from
> `git clone` to a **running, modifiable** application — without asking anyone a single question.
>
> **What you'll achieve:** a locally running **Java 25 LTS + Spring Boot 3.5.11** service that
> reproduces the behavior of the legacy AWS **CardDemo** credit-card management system, backed by
> **PostgreSQL 16** and **AWS S3/SQS/SNS emulated by LocalStack**, with full observability
> (structured logging, distributed tracing, Prometheus metrics, Grafana dashboards).

This application is a **greenfield Java migration** of a mainframe COBOL/CICS/JCL system. The original
COBOL corpus under [`app/`](../app) (and the build JCL under [`samples/`](../samples)) is a **frozen,
read-only REFERENCE specification** anchored to source commit SHA **`27d6c6f`**. You will **never modify
or copy** those files — they exist only to document the exact behavior the Java code must reproduce with
100% parity. All Java code lives at the **repository root** under the base package **`com.carddemo`**.

> **REST-only:** the modernized system exposes a **REST API and has no web/browser UI**. "Using" the app
> means calling its HTTP endpoints (see [Verify it's running](#verify-its-running)).

---

## At a Glance — Stack Versions

| Layer | Technology | Version |
|-------|-----------|---------|
| Language / Runtime | Java (Adoptium/Temurin OpenJDK) — **LTS** | **25.0.2** |
| Application framework | Spring Boot | **3.5.11** |
| Build | Maven (via bundled `./mvnw` wrapper) | **3.9.9** |
| Database | PostgreSQL | **16** |
| Schema migration | Flyway | **11.x** |
| AWS integration | Spring Cloud AWS (S3 / SQS / SNS) | **3.3.0** |
| Security tokens | JJWT (JSON Web Tokens) | **0.12.6** |
| Structured logging | logstash-logback-encoder | **8.0** |
| Testing (containers) | Testcontainers | **2.0.3** |
| Coverage gate | JaCoCo (≥ 80% line coverage) | **0.8.14** |
| Security gate | OWASP dependency-check (zero critical/high CVEs) | **12.1.0** |
| Container runtime | Docker engine | **28.x** |

---

## Prerequisites

Install the following on your clean machine before you begin. Everything else (Spring Boot, PostgreSQL
driver, AWS SDK, test frameworks) is pulled automatically by the Maven build — you do **not** install
those by hand.

| Prerequisite | Version | Notes |
|--------------|---------|-------|
| **JDK** | **25.0.2 LTS** | Adoptium / Eclipse Temurin OpenJDK. Set `JAVA_HOME` to the JDK 25 install if it is not your default JVM. |
| **Docker engine** | **28.x** | Plus the **Docker Compose v2** plugin (`docker compose`, not the legacy `docker-compose`). Runs PostgreSQL, LocalStack, and the observability stack. |
| **Git** | 2.x+ | To clone the repository. |
| **Maven** | **3.9.9** | **No separate install required** — the repository ships the Maven Wrapper (`./mvnw` on macOS/Linux, `mvnw.cmd` on Windows). Always invoke builds through the wrapper so everyone uses the same pinned Maven version. |
| **Local secrets (`.env`)** | — | Copy `.env.example` to `.env` and set three secrets — `POSTGRES_PASSWORD`, `JWT_SECRET` (Base64 key decoding to ≥ 32 bytes), and `GF_SECURITY_ADMIN_PASSWORD`. Docker Compose auto-loads `.env`; these have **no committed defaults** and `docker compose up` fails fast if any is unset (DECISION_LOG D-036). |

> **Why no Maven install?** The bundled wrapper `./mvnw` downloads and runs the exact pinned Maven
> **3.9.9** for you, guaranteeing reproducible builds across machines. Use `./mvnw …` everywhere this
> guide shows a Maven command.

> **LocalStack is the free community image — no token required.** All AWS interactions are
> **LocalStack-only** (zero live-AWS dependencies). Both `docker-compose.yml` and the Testcontainers
> integration suite use the community **`localstack/localstack:3.8.1`** image, which provides S3, SQS,
> and SNS with no license. A `LOCALSTACK_AUTH_TOKEN` is **optional** and needed **only** if you
> deliberately switch the `localstack` service to a Pro image
> (`LOCALSTACK_IMAGE=localstack/localstack-pro:latest`).

---

## Quick Start (Clean Machine → Running)

Run these six steps in order. Every command is copy-paste ready.

**1. Clone the repository and enter it.**

```bash
git clone <repo-url> && cd <repo>
```

**2. Create your local secrets file.** Copy the template and fill in the three required secrets. The
fail-fast Docker Compose setup (DECISION_LOG D-036) ships **no committed defaults**, so a clean
checkout will not start until these are set.

```bash
cp .env.example .env
# Generate a strong Base64 JWT signing key (decodes to >= 32 bytes for HS256):
openssl rand -base64 48
# Then edit .env and set the three secrets:
#   POSTGRES_PASSWORD=<choose a strong local value>
#   JWT_SECRET=<paste the openssl output above>
#   GF_SECURITY_ADMIN_PASSWORD=<choose a strong local value>
```

> Docker Compose auto-loads `.env` from the repository root (step 4). When you instead run the app
> **from source** (step 5), export the same variables into your shell first, e.g.
> `set -a; source .env; set +a`. No `LOCALSTACK_AUTH_TOKEN` is needed for the default community
> LocalStack image. The integration suite (`./mvnw clean verify`, step 3) uses Testcontainers with its
> own ephemeral test secrets and does **not** read `.env`.

**3. Build, test, and run all quality gates** — compiles with zero warnings, runs the unit + integration
+ E2E suites, enforces the JaCoCo coverage gate (≥ 80% line coverage), and runs the OWASP
dependency-check CVE gate.

```bash
./mvnw clean verify
```

**4. Start the local infrastructure and the app** with Docker Compose (six services — see below).

```bash
docker compose up -d
```

**5. (Iterative development) Run the app from source** with the `local` profile, which wires the app to
the Docker Compose PostgreSQL and LocalStack endpoints. Use this for fast edit-run-debug cycles instead
of rebuilding the container image.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**6. Verify the application is healthy.** Actuator endpoints (health, info, metrics, Prometheus) are
served on the **management port `9091`** — separate from the public REST API on `8080`. Compose keeps
`9091` **internal to the Compose network** (only `8080` is published to the host, per DECISION_LOG
D-033), so check health one of two ways:

```bash
# (a) Containerized app (after `docker compose up -d`) — query from inside the Compose network,
#     or simply read the container HEALTHCHECK status reported by `docker compose ps`:
docker compose exec app curl -fsS http://localhost:9091/actuator/health
docker compose ps

# (b) App run from source (step 5) — the management port is on localhost directly:
curl http://localhost:9091/actuator/health
```

### What each Docker Compose service is for

`docker compose up -d` (step 4) starts **six** services defined in `docker-compose.yml`:

| Service | Purpose |
|---------|---------|
| `postgres` | **PostgreSQL 16** — the system of record. Flyway applies `V1` (schema) → `V2` (indexes) → `V3` (seed data) on startup. Replaces the legacy VSAM KSDS datasets. |
| `localstack` | **AWS emulator** — provides S3 (batch file staging / statements / reports), SQS FIFO (report submission), and SNS (notifications) with zero live-AWS dependencies. |
| `app` | The **CardDemo Spring Boot** application. The public REST API is on port `8080` (published to the host); Actuator/management endpoints are on the internal port `9091` (not published, per DECISION_LOG D-033). |
| `jaeger` | **Distributed tracing UI** — collects OpenTelemetry spans exported by the app via the Micrometer tracing bridge. |
| `prometheus` | **Metrics server** — scrapes the app's metrics at `app:9091/actuator/prometheus` over the Compose network (config in `prometheus.yml`). |
| `grafana` | **Dashboards** — visualizes the Prometheus metrics using the template in [`./grafana-dashboard.json`](./grafana-dashboard.json). |

### Verify it's running

**Health check** — should report the application and its dependencies as up. Health lives on the
management port `9091` (see step 6 for why), so use the Compose-network form for the containerized app:

```bash
# Containerized (docker compose up -d): management port 9091 is internal to the Compose network.
docker compose exec app curl -fsS http://localhost:9091/actuator/health
# Expected: {"status":"UP"}

# App run from source: 9091 is on localhost directly.
curl http://localhost:9091/actuator/health
```

**Sign in to obtain a JWT** — the API is stateless and secured with JSON Web Tokens. Authenticate with a
seeded user to receive a bearer token:

```bash
curl -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}'
# Expected: 200 OK with a JSON body containing a JWT token
```

**Call an authenticated endpoint** — copy the token from the sign-in response into the `Authorization`
header and fetch the sample account:

```bash
curl http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer <token-from-signin>"
# Expected: 200 OK with the account record (note BigDecimal money fields)
```

> **Tip:** if `./mvnw` reports `release version 25 not supported`, your shell is not using JDK 25.
> Point `JAVA_HOME` at your JDK 25 installation (for example
> `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`) and retry. If `./mvnw` is not executable,
> run `chmod +x mvnw`.

---

## Services, URLs & Ports

These values **must match `docker-compose.yml`** (and `prometheus.yml` for the scrape target). If you
change a port or credential there, update it here too.

| Service | URL / Port | Credentials / Notes |
|---------|-----------|---------------------|
| **CardDemo application** (public REST API) | `http://localhost:8080` | The REST API base. No UI — call endpoints directly. Published to the host. |
| Actuator health | `http://localhost:9091/actuator/health` | On the **management port `9091`** (separate from the API). In Compose, `9091` is **not published** (D-033) — reach it via `docker compose exec app curl http://localhost:9091/actuator/health`, or directly on `localhost:9091` when running from source. Returns `{"status":"UP"}` with composite indicators for the database, S3, and SQS. No auth. |
| Prometheus scrape endpoint (on app) | `http://app:9091/actuator/prometheus` | Micrometer metrics in Prometheus exposition format, on management port `9091`. Scraped by the Prometheus server over the Compose network (see `prometheus.yml`). |
| **PostgreSQL 16** | `localhost:5432` | Database `carddemo` / user `carddemo` / password = your `POSTGRES_PASSWORD` from `.env` (no default; see `.env.example`). |
| **LocalStack** (S3, SQS, SNS) | `http://localhost:4566` | AWS emulator endpoint, community image `localstack/localstack:3.8.1`. **No auth token required.** |
| **Jaeger UI** | `http://localhost:16686` | Distributed tracing UI. OTLP ingest on gRPC **`4317`** and HTTP **`4318`**. |
| **Prometheus UI** | `http://localhost:9090` | Query metrics and inspect scrape targets. |
| **Grafana** | `http://localhost:3000` | Login `admin` / your `GF_SECURITY_ADMIN_PASSWORD` from `.env` (no default; see `.env.example`). Dashboards visualize the custom CardDemo metrics. |

> **Observability metrics.** The app publishes custom business metrics at `/actuator/prometheus`,
> scraped by Prometheus and visualized in [`./grafana-dashboard.json`](./grafana-dashboard.json):
> `carddemo.batch.records.processed`, `carddemo.batch.records.rejected`, `carddemo.auth.attempts`,
> and `carddemo.transaction.amount.total`.

---

## Domain Context

**CardDemo** is a **credit-card management system**. It manages **accounts**, the **credit cards** issued
against them, the **customers** who own them, the financial **transactions** posted to them, **billing**
(bill payment), **reporting**, and **user administration**. There are two kinds of users:

- **Regular users** perform day-to-day functions: view/update accounts and cards, list/view/add
  transactions, pay bills, and submit transaction reports.
- **Admin users** perform user administration (list/add/update/delete application users).

The system preserves the legacy application's complete behavior across **22 features, `F-001` through
`F-022`**, spanning **18 interactive (CICS online) programs** and **10 batch programs**. The migration is
a strict modernization with **no feature expansion** — exactly those 22 features, no more.

At a high level the feature families are:

- **Authentication & menus** — sign-on, the main (user) menu, and the admin menu.
- **Account management** — view and atomically update an account together with its customer record.
- **Card management** — list cards (paginated), view a card's detail, and update a card.
- **Transaction management** — list transactions, view a transaction's detail, and add a transaction
  (with auto-generated id and confirmation).
- **Billing** — bill payment against an account balance.
- **Reporting** — submit an asynchronous transaction report job.
- **User administration** — full create/read/update/delete of application users (admin only).
- **Batch pipeline** — daily posting, interest calculation, transaction combine/sort, statement
  generation, and transaction reporting.

### COBOL → Java Modernization Model

Each legacy mainframe mechanism maps to a modern Spring equivalent. Observable behavior is preserved;
the mainframe-specific implementation mechanics are discarded.

| Legacy (mainframe) | Modern (Java / Spring) | What it means |
|--------------------|------------------------|---------------|
| **CICS online** transaction programs | **REST controllers** (8 controllers replace 17 screens) | 3270 screen interactions become stateless HTTP request/response endpoints under `/api/**`. |
| **VSAM KSDS** indexed files | **PostgreSQL 16** via **Spring Data JPA** | 11 record layouts become 11 relational tables; `READ … KEY` / `STARTBR` become repository finder methods. |
| **JCL** job streams | **Spring Batch** | The 5-stage batch pipeline becomes chunk-oriented reader → processor → writer jobs with preserved step order and condition-code logic. |
| **CICS TDQ `JOBS`** (Transient Data Queue) | **AWS SQS FIFO** `carddemo-report-jobs.fifo` | Report submission becomes an asynchronous FIFO message that triggers the Spring Batch report job. |
| **GDG** generation datasets | **AWS S3 objects** (3 buckets) | Generation versioning becomes versioned objects in `carddemo-batch-input`, `carddemo-batch-output`, and `carddemo-statements`. |
| Plaintext **`USRSEC`** file | **BCrypt** + **Spring Security 6** roles | Passwords are hashed (never stored in plaintext); access is role-based (`ADMIN` / `USER`). |
| **COMMAREA** pseudo-conversation | **Stateless JWT** | Cross-screen state carried in the COMMAREA becomes JWT claims plus request/response DTOs — no server-side session. |
| **COMP-3 / `PIC S9(n)V99`** packed decimals | **`java.math.BigDecimal`** (scale 2) | Monetary fields keep exact decimal precision — never `double`/`float`. |
| **`SYNCPOINT`** | **`@Transactional`** | Commit/rollback boundaries become declarative transaction scopes. |
| **`FILE STATUS`** codes | Typed **exception hierarchy** + status enums | Each two-character status code maps to a specific exception so downstream branch logic keys off the same conditions. |

> **Seed users.** The legacy plaintext-credential concept is replaced by **BCrypt-hashed seed users**.
> Out of the box you get **`ADMIN001`** (role **`ADMIN`**) and **`USER0001`** (role **`USER`**), both
> with the initial password **`PASSWORD`** (stored BCrypt-hashed in the `V3` seed migration). The seed
> data also includes sample records such as account id **`00000000001`** used by the verification steps
> above. **Change these defaults before any non-local use.**

---

## Project Layout

The Java application lives at the **repository root** (there is **no** `carddemo-java/` subfolder). The
base Java package is **`com.carddemo`**. The frozen COBOL/JCL REFERENCE lives under `app/` and `samples/`.

```text
.                                   # repository ROOT — the Java app is here
├── pom.xml                         # Maven build (Spring Boot 3.5.11, Java 25, all deps)
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper (pins Maven 3.9.9) — use ./mvnw
├── Dockerfile                      # Multi-stage build → runnable image
├── docker-compose.yml              # 6 services: postgres, localstack, app, jaeger, prometheus, grafana
├── prometheus.yml                  # Prometheus scrape config (targets the app)
├── .gitignore
├── DECISION_LOG.md                 # Architectural decisions (decision, alternatives, rationale, risks)
├── TRACEABILITY_MATRIX.md          # Bidirectional COBOL-paragraph → Java-method mapping (100%)
├── README.md                       # Top-level setup-to-running overview
├── localstack-init/
│   └── init-aws.sh                 # Provisions the 3 S3 buckets + SQS FIFO + SNS topic
├── docs/                           # ← you are here
│   ├── onboarding-guide.md         # this guide
│   ├── architecture-before-after.md# Mermaid before/after architecture
│   ├── api-contracts.md            # REST endpoint contracts
│   ├── validation-gates.md         # Gate 1–8 evidence
│   ├── grafana-dashboard.json      # Grafana dashboard template
│   ├── project-guide.md            # Project status & development guide
│   └── technical-specifications.md # Authoritative migration blueprint
├── src/main/java/com/carddemo/
│   ├── CardDemoApplication.java     # Spring Boot entry point
│   ├── entity/                      # JPA entities (record layouts → tables)
│   ├── dto/                         # Request/response DTOs (from BMS field contracts)
│   ├── enums/                       # Typed enums (transaction type, file status, …)
│   ├── repository/                  # Spring Data JPA repositories (VSAM access → finders)
│   ├── service/                     # Business logic (one family per online program)
│   ├── controller/                  # 8 REST controllers (replace 17 screens)
│   ├── batch/                       # Spring Batch jobs / readers / processors / writers
│   ├── config/                      # SecurityConfig, BatchConfig, AwsConfig, JpaConfig, …
│   ├── observability/               # CorrelationIdFilter, MetricsConfig, HealthIndicators
│   └── exception/                   # Custom exceptions + GlobalExceptionHandler
├── src/main/resources/
│   ├── application.yml              # Base config
│   ├── application-local.yml        # `local` profile: Docker Compose PostgreSQL + LocalStack
│   ├── application-test.yml         # `test` profile: Testcontainers
│   ├── logback-spring.xml           # Structured JSON logging + correlation IDs
│   ├── db/migration/                # Flyway V1__create_schema / V2__create_indexes / V3__seed_data
│   └── validation/                  # JSON lookup tables (NANPA area codes, state codes, ZIP prefixes)
├── src/test/java/com/carddemo/
│   ├── unit/                        # Mockito unit tests
│   ├── integration/                 # Testcontainers PostgreSQL + LocalStack
│   └── gates/                       # GateVerificationTest (Gates 1–8)
│
├── app/                            # FROZEN REFERENCE — legacy COBOL (cbl/cpy/cpy-bms/bms/jcl/data) @ 27d6c6f
└── samples/                        # FROZEN REFERENCE — legacy build JCL (BATCMP, BMSCMP, CICCMP)
```

> **`app/` and `samples/` are read-only specification.** They contain the original COBOL programs,
> copybooks, BMS maps, JCL, and data fixtures anchored to commit **`27d6c6f`**. **Never modify or copy
> them into the Java tree** — they exist solely to define the behavior the Java code must reproduce.

---

## Common Pitfalls

Read these before you write code — each one corresponds to a parity- or build-critical rule.

- **Decimal precision (money).** Always use **`java.math.BigDecimal` with scale 2 and
  `RoundingMode.HALF_EVEN`** for monetary fields. **Never** use `double` or `float` — floating-point
  substitution silently diverges from the COBOL result and fails the byte-equivalence gate. This mirrors
  the legacy `COMP-3` / `PIC S9(n)V99` packed-decimal arithmetic (e.g., the interest formula
  `(balance × rate) / 1200`).

- **Optimistic locking.** Account and card updates use JPA **`@Version`** optimistic locking, which
  reproduces the COBOL re-read-and-compare guard (`9300-CHECK-CHANGE-IN-REC`). Expect a **`409 Conflict`**
  when two requests edit the same record concurrently — that is the intended "record changed by another
  user" behavior, not a bug.

- **Flyway migration ordering.** Migrations apply strictly **`V1` → `V2` → `V3`** by version. **Never
  edit a migration that has already been applied** — Flyway validates checksums and will refuse to start.
  To change the schema, **add a new versioned file** `V<n>__<description>.sql`.

- **Missing `.env` secrets (fail-fast).** `docker compose` requires three secrets with **no committed
  defaults** — `POSTGRES_PASSWORD`, `JWT_SECRET`, and `GF_SECURITY_ADMIN_PASSWORD` (DECISION_LOG D-036).
  If any is unset, `docker compose up` **fails fast** at startup. Run `cp .env.example .env` and set all
  three before building or running (see [Prerequisites](#prerequisites) and Quick Start step 2). The
  default LocalStack is the free community image `localstack/localstack:3.8.1` — **no
  `LOCALSTACK_AUTH_TOKEN` is required**; it is needed only if you opt into a LocalStack Pro image.

- **Fixed-width record parsing.** When reading the COBOL data fixtures, honor the **exact column offsets
  and record lengths** — ACCOUNT **300**, CARD **150**, CARD-XREF **50**, CUSTOMER **500**, TRAN **350**,
  SEC-USER **80** bytes. **Do not trim or shift fields**; trimming shifts every subsequent field boundary
  and corrupts the record.

- **Spring profiles.** Use the right profile: **`local`** wires the app to the Docker Compose LocalStack
  and local PostgreSQL (for `docker compose` / `spring-boot:run`), while **`test`** uses **Testcontainers**
  (for the automated test suite). Running the test suite against the `local` profile — or vice versa —
  produces confusing connection errors.

---

## How To…

Short, practical recipes for continued development. All builds go through the `./mvnw` wrapper.

### Run tests, coverage, and the quality gates

```bash
# Full build: compile (zero warnings) + unit/integration/E2E tests
# + JaCoCo coverage gate (>= 80% line) + OWASP dependency-check (zero critical/high CVEs)
./mvnw clean verify
```

- **Coverage report (JaCoCo):** open `target/site/jacoco/index.html` after `verify`.
- **CVE report (OWASP dependency-check):** the generated report is written under `target/`
  (for example `target/dependency-check-report.html`).
- **Run a single test class** while iterating:

  ```bash
  ./mvnw test -Dtest=AccountUpdateServiceTest
  ```

### Add a Flyway database migration

1. Create a **new** versioned file under `src/main/resources/db/migration/` named
   `V<n>__<short_description>.sql` (e.g., `V4__add_loyalty_points.sql`) — pick the next unused version
   number after the highest existing one (`V3`).
2. Write forward-only DDL/DML. **Never edit an already-applied migration** (`V1`/`V2`/`V3`) — Flyway
   validates checksums and will refuse to start if one changed; add a new file instead.
3. Run `./mvnw verify` (or start the app) — Flyway applies the new migration in version order.

### Add a REST endpoint

Follow the layered architecture **Controller → Service → Repository → Entity**:

1. **Repository** — add a Spring Data finder method if you need new data access.
2. **Service** — implement the business logic in a `@Service` class; wrap multi-step writes in
   `@Transactional(rollbackFor = Exception.class)`; use `BigDecimal` for money.
3. **DTO** — add request/response DTOs in `dto/` and annotate fields with Jakarta **bean-validation**
   constraints (`@NotNull`, `@Size`, `@Digits`, …). Keep field lengths faithful to the BMS contract.
4. **Controller** — add the `@RestController` mapping under `/api/**`, delegate to the service, and let
   `GlobalExceptionHandler` translate exceptions to HTTP responses.
5. **Document** the new endpoint in [`./api-contracts.md`](./api-contracts.md) and add tests under
   `src/test/java/com/carddemo/{unit,integration}`.

### Update the traceability matrix

This migration requires **100%** bidirectional COBOL-paragraph → Java-method coverage. Whenever you add
or move logic that corresponds to a COBOL paragraph, update [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md)
so the mapping stays complete and gap-free, and record any non-literal design decision in
[`../DECISION_LOG.md`](../DECISION_LOG.md).

---

## Suggested Next Tasks

The following are natural follow-ups that are **currently out of scope** and intentionally deferred at
authoring time. Treat each as a future effort, not part of the present migration.

- **Add deferred subsystems** — Db2, IMS, IBM MQ, and FTP/SFTP integration are deferred under constraint
  **C-001**. _Currently out of scope._
- **Add a production Spring profile** — there is no `application-prod.yml` yet; production database/AWS
  configuration and externalized secrets are required before any real deployment. _Currently out of
  scope._
- **Build a CI/CD pipeline** — no automated build/test/deploy pipeline exists at authoring time; today's
  verification is the local `./mvnw clean verify`. _Currently out of scope._

> These are flagged here so a new engineer understands the present boundaries of the system. Do not
> assume any of the above exists.

---

## Related Documentation

| Document | What it covers |
|----------|----------------|
| [`./architecture-before-after.md`](./architecture-before-after.md) | Mermaid **before/after** architecture diagrams (legacy mainframe vs. Java/Spring). |
| [`./api-contracts.md`](./api-contracts.md) | REST API endpoint contracts (paths, request/response shapes, status codes). |
| [`./validation-gates.md`](./validation-gates.md) | Evidence for validation **Gates 1–8** (byte-equivalence, zero-warning build, coverage, …). |
| [`./grafana-dashboard.json`](./grafana-dashboard.json) | Importable Grafana dashboard template for the custom CardDemo metrics. |
| [`./technical-specifications.md`](./technical-specifications.md) | Authoritative migration blueprint — architecture, scope, constraints, components. |
| [`./project-guide.md`](./project-guide.md) | Project status, hours, test results, and the detailed development guide & appendices. |
| [`../README.md`](../README.md) | Top-level repository overview and setup-to-running summary. |
| [`../DECISION_LOG.md`](../DECISION_LOG.md) | Architectural decisions with alternatives, rationale, and risks. |
| [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) | Bidirectional COBOL-paragraph → Java-method mapping (100% coverage). |

---

_This guide targets Java **25.0.2 LTS** + Spring Boot **3.5.11**. Legacy COBOL references are anchored to
source commit **`27d6c6f`** and are read-only specification. If any command, port, or credential here ever
drifts from `docker-compose.yml` or `pom.xml`, treat those files as the source of truth and update this
guide to match._
