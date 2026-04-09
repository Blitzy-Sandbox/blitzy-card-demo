# CardDemo — Java 25 + Spring Boot 3.x Credit Card Management Application

![Java 25](https://img.shields.io/badge/Java-25_LTS-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.x-brightgreen?logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16+-336791?logo=postgresql)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)

A fully operational credit card management application built with **Java 25 LTS** and **Spring Boot 3.x**, migrated from the [AWS CardDemo COBOL mainframe application](https://github.com/aws-samples/aws-mainframe-modernization-carddemo) (source commit [`27d6c6f`](https://github.com/aws-samples/aws-mainframe-modernization-carddemo/commit/27d6c6f)) with **100% behavioral parity**. All 22 original features — spanning 18 online transactions and 10 batch programs — are preserved with identical business logic semantics.

---

## Table of Contents

- [Technology Stack](#technology-stack)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Application Features](#application-features)
- [REST API Endpoints](#rest-api-endpoints)
- [Database](#database)
- [Configuration](#configuration)
- [Testing](#testing)
- [Observability](#observability)
- [Documentation](#documentation)
- [Migration Context](#migration-context)
- [Contributing](#contributing)
- [License](#license)

---

## Technology Stack

| Technology | Version | Purpose |
|:---|:---|:---|
| Java | 25 LTS (Eclipse Temurin / Oracle) | Runtime platform |
| Spring Boot | 3.5.x | Application framework |
| Spring Data JPA | (managed by Spring Boot BOM) | PostgreSQL data access, entity mapping |
| Spring Batch | (managed by Spring Boot BOM) | Batch pipeline (replaces JCL jobs) |
| Spring Security | (managed by Spring Boot BOM) | Authentication and authorization with BCrypt |
| PostgreSQL | 16+ | Relational database (replaces VSAM KSDS datasets) |
| Flyway | (managed by Spring Boot BOM) | Database schema migration |
| AWS S3 | via Spring Cloud AWS 3.3.0 | Batch file staging (replaces GDG generations) |
| AWS SQS | via Spring Cloud AWS 3.3.0 | Message queue (replaces CICS TDQ) |
| AWS SNS | via Spring Cloud AWS 3.3.0 | Notification publishing |
| LocalStack | Pro (latest) | Local AWS service emulation — zero live AWS dependencies |
| Docker Compose | v2 | Local infrastructure orchestration |
| Micrometer + OpenTelemetry | (managed by Spring Boot BOM) | Distributed tracing and metrics |
| Logback + logstash-logback-encoder | 8.0 | Structured JSON logging with correlation IDs |
| JaCoCo | 0.8.12 | Code coverage reporting (≥80% target) |
| Maven | 3.9+ (wrapper included) | Build and dependency management |

---

## Prerequisites

Before building and running CardDemo, ensure the following tools are installed:

| Tool | Version | Notes |
|:---|:---|:---|
| **JDK** | 25 | [Eclipse Temurin](https://adoptium.net/) or [Oracle JDK](https://www.oracle.com/java/) |
| **Maven** | 3.9+ | Or use the included `./mvnw` wrapper (recommended) |
| **Docker** | 24+ | Docker Desktop or Docker Engine with Compose v2 |
| **Docker Compose** | v2 | Bundled with Docker Desktop; or install separately |
| **Git** | 2.x | For cloning the repository |
| **AWS CLI** | 2.x | *Optional* — for direct LocalStack interaction |

---

## Quick Start

### 1. Clone the Repository

```bash
git clone <YOUR_REPOSITORY_URL>
cd carddemo-java
```

### 2. Build the Application

```bash
./mvnw clean verify
```

This compiles the application, runs unit tests, and packages a deployable JAR. The build is configured for zero warnings with `-Xlint:all`.

### Docker Image Build (Optional)

To build a self-contained Docker image for deployment:

```bash
docker build -t carddemo:latest .
```

> **Networking note:** In environments with restricted Docker networking (Docker-in-Docker, corporate proxies, certain CI/CD runners), the Maven dependency download step may fail with "Connection reset" errors. In these cases, use the host network:
>
> ```bash
> docker build --network=host -t carddemo:latest .
> ```

Run the container:

```bash
docker run -p 8080:8080 --env-file .env carddemo:latest
```

### 3. Start Local Infrastructure

```bash
docker compose up -d
```

This starts the following services:

| Service | Port | Purpose |
|:---|:---|:---|
| **PostgreSQL 16** | `5432` | Application database |
| **LocalStack** | `4566` | S3, SQS, SNS emulation |
| **Jaeger** | `16686` | Distributed tracing UI |
| **Prometheus** | `9090` | Metrics scraping and query |
| **Grafana** | `3000` | Dashboard visualization |

Wait for all services to become healthy:

```bash
docker compose ps
```

### 4. Run the Application

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The application starts on port **8080** with the `local` profile, which connects to the Docker Compose infrastructure.

### 5. Verify Health

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

### 6. Access Observability Dashboards

| Dashboard | URL |
|:---|:---|
| Jaeger (Tracing) | [http://localhost:16686](http://localhost:16686) |
| Prometheus (Metrics) | [http://localhost:9090](http://localhost:9090) |
| Grafana (Dashboards) | [http://localhost:3000](http://localhost:3000) (admin / admin) |

### 7. Default Credentials

The application ships with seed data that includes the following default user accounts:

| User ID | Password | Role | Access |
|:---|:---|:---|:---|
| `ADMIN001` | `PASSWORDA` | Admin | User management (CRUD) |
| `USER0001` | `PASSWORDU` | User | Account, card, transaction, billing operations |

> **Note:** Passwords are stored with BCrypt hashing. The seed data uses pre-hashed values for the default passwords above.

---

## Project Structure

```
carddemo-java/
├── pom.xml                                    # Maven build configuration
├── mvnw / mvnw.cmd                            # Maven wrapper scripts
├── Dockerfile                                 # Multi-stage container build
├── docker-compose.yml                         # Local dev infrastructure
├── README.md                                  # This file
├── DECISION_LOG.md                            # Architectural decision rationale
├── TRACEABILITY_MATRIX.md                     # COBOL → Java bidirectional mapping
├── localstack-init/
│   └── init-aws.sh                            # S3 bucket + SQS queue provisioning
├── docs/
│   ├── executive-presentation.html            # reveal.js executive summary
│   ├── architecture-before-after.md           # Mermaid architecture diagrams
│   ├── onboarding-guide.md                    # New developer quickstart
│   ├── validation-gates.md                    # Gate 1–8 evidence
│   ├── api-contracts.md                       # REST API specifications
│   └── grafana-dashboard.json                 # Grafana dashboard template
├── src/
│   ├── main/
│   │   ├── java/com/cardemo/
│   │   │   ├── CardDemoApplication.java       # Spring Boot entry point
│   │   │   ├── config/                        # Security, Batch, AWS, JPA, Web, Observability
│   │   │   ├── model/
│   │   │   │   ├── entity/                    # JPA entities (11 tables)
│   │   │   │   ├── dto/                       # Request/response DTOs
│   │   │   │   ├── enums/                     # UserType, FileStatus, RejectCode, etc.
│   │   │   │   └── key/                       # Composite primary key classes
│   │   │   ├── repository/                    # Spring Data JPA repositories (11)
│   │   │   ├── service/                       # Business logic services
│   │   │   │   ├── auth/                      # Authentication (← COSGN00C)
│   │   │   │   ├── account/                   # Account view/update (← COACTVWC, COACTUPC)
│   │   │   │   ├── card/                      # Card list/detail/update (← COCRDLIC, COCRDSLC, COCRDUPC)
│   │   │   │   ├── transaction/               # Transaction list/detail/add (← COTRN00C–02C)
│   │   │   │   ├── billing/                   # Bill payment (← COBIL00C)
│   │   │   │   ├── report/                    # Report submission → SQS (← CORPT00C)
│   │   │   │   ├── admin/                     # User CRUD (← COUSR00C–03C)
│   │   │   │   ├── menu/                      # Menu routing (← COMEN01C, COADM01C)
│   │   │   │   └── shared/                    # Date validation, NANPA lookup, FILE STATUS mapping
│   │   │   ├── controller/                    # REST API controllers (8)
│   │   │   ├── batch/
│   │   │   │   ├── jobs/                      # Spring Batch job definitions (5 + orchestrator)
│   │   │   │   ├── processors/                # Item processors (5)
│   │   │   │   ├── readers/                   # Item readers (5)
│   │   │   │   └── writers/                   # Item writers (3)
│   │   │   ├── exception/                     # Custom exception hierarchy
│   │   │   └── observability/                 # Correlation ID filter, metrics, health indicators
│   │   └── resources/
│   │       ├── application.yml                # Main Spring Boot configuration
│   │       ├── application-local.yml          # Local dev profile (LocalStack + PostgreSQL)
│   │       ├── application-test.yml           # Test profile (Testcontainers)
│   │       ├── logback-spring.xml             # Structured JSON logging configuration
│   │       ├── db/migration/
│   │       │   ├── V1__create_schema.sql      # 11 tables DDL
│   │       │   ├── V2__create_indexes.sql     # Primary + alternate indexes
│   │       │   └── V3__seed_data.sql          # Seed data from 9 ASCII fixture files
│   │       └── validation/
│   │           ├── nanpa-area-codes.json       # NANPA area code lookup
│   │           ├── us-state-codes.json         # US state/territory codes
│   │           └── state-zip-prefixes.json     # State/ZIP prefix combinations
│   └── test/
│       └── java/com/cardemo/
│           ├── unit/                           # JUnit 5 unit tests
│           ├── integration/                    # Testcontainers integration tests
│           └── e2e/                            # End-to-end tests (batch pipeline, REST API)
```

### Package Overview

| Package | Responsibility |
|:---|:---|
| `config` | Spring configuration classes — Security (BCrypt), Batch infrastructure, AWS clients, JPA, observability, web/CORS |
| `model.entity` | JPA `@Entity` classes mapped from VSAM record copybooks; `BigDecimal` for all financial fields |
| `model.dto` | Request/response DTOs mapped from BMS symbolic map copybooks |
| `model.enums` | Enumerations — `UserType`, `FileStatus` (COBOL codes), `RejectCode` (batch codes 100–109), `TransactionSource` |
| `model.key` | `@Embeddable` composite primary keys for TCATBALF, DISCGRP, TRANCATG tables |
| `repository` | Spring Data JPA repositories — one per VSAM dataset, with custom queries for alternate index access patterns |
| `service` | Business logic — each COBOL online program maps to one service class |
| `controller` | REST API endpoints — each BMS mapset maps to controller methods |
| `batch` | Spring Batch components — jobs, processors, readers, and writers for the 5-stage batch pipeline |
| `exception` | Custom exception hierarchy mapping COBOL `FILE STATUS` codes to Java exceptions |
| `observability` | Correlation ID filter, custom business metrics, health indicators for DB/S3/SQS |

---

## Application Features

CardDemo implements **22 features** with 100% behavioral parity from the original COBOL application.

### Online Features (17)

| ID | Feature | REST Endpoint | Description |
|:---|:---|:---|:---|
| F-001 | User Sign-On | `POST /api/auth/signin` | Authenticate with user ID and password (BCrypt) |
| F-002 | Main Menu | `GET /api/menu/main` | 10-option navigation for regular users |
| F-003 | Admin Menu | `GET /api/menu/admin` | 4-option navigation for admin users |
| F-004 | Account View | `GET /api/accounts/{id}` | View account details with customer and card data |
| F-005 | Account Update | `PUT /api/accounts/{id}` | Update account with transactional integrity and optimistic locking |
| F-006 | Credit Card List | `GET /api/cards` | Paginated browse (7 cards/page) with account filtering |
| F-007 | Credit Card Detail | `GET /api/cards/{id}` | Single card keyed read |
| F-008 | Credit Card Update | `PUT /api/cards/{id}` | Update card with optimistic concurrency control |
| F-009 | Transaction List | `GET /api/transactions` | Paginated browse (10 transactions/page) |
| F-010 | Transaction Detail | `GET /api/transactions/{id}` | Single transaction keyed read |
| F-011 | Transaction Add | `POST /api/transactions` | Add transaction with auto-ID generation and cross-reference resolution |
| F-012 | Bill Payment | `POST /api/billing/pay` | Process bill payment — balance update + transaction creation |
| F-013 | Report Submission | `POST /api/reports/submit` | Submit report criteria → SQS message triggers batch job |
| F-014 | User List | `GET /api/admin/users` | Paginated user browse (admin only) |
| F-015 | User Add | `POST /api/admin/users` | Create user with BCrypt password hashing (admin only) |
| F-016 | User Update | `PUT /api/admin/users/{id}` | Modify user record (admin only) |
| F-017 | User Delete | `DELETE /api/admin/users/{id}` | Delete user with confirmation (admin only) |

### Batch Features (5)

| ID | Feature | Spring Batch Job | Description |
|:---|:---|:---|:---|
| F-018 | Daily Transaction Posting | `DailyTransactionPostingJob` | 4-stage validation cascade with reject codes 100–109 |
| F-019 | Interest Calculation | `InterestCalculationJob` | `(balance × rate) / 1200` with DEFAULT group fallback |
| F-020 | Combine Transactions | `CombineTransactionsJob` | Sort + merge daily transactions into master file |
| F-021 | Statement Generation | `StatementGenerationJob` | Text + HTML statement output to S3 |
| F-022 | Transaction Report | `TransactionReportJob` | Date-filtered reporting with page/account/grand totals |

### Batch Pipeline

The 5-stage batch pipeline executes in strict sequential order (stages 4a and 4b may run in parallel):

```
POSTTRAN → INTCALC → COMBTRAN → CREASTMT (4a)
                                 TRANREPT (4b)
```

| Stage | Job | Source JCL | Description |
|:---|:---|:---|:---|
| 1 | Daily Transaction Posting | `POSTTRAN.jcl` | Validate and post daily transactions |
| 2 | Interest Calculation | `INTCALC.jcl` | Calculate interest on category balances |
| 3 | Combine Transactions | `COMBTRAN.jcl` | Sort and merge into master transaction file |
| 4a | Statement Generation | `CREASTMT.JCL` | Generate customer statements (parallel with 4b) |
| 4b | Transaction Report | `TRANREPT.jcl` | Generate transaction reports (parallel with 4a) |

---

## REST API Endpoints

| Method | Endpoint | Controller | Description |
|:---|:---|:---|:---|
| `POST` | `/api/auth/signin` | `AuthController` | User authentication |
| `GET` | `/api/accounts/{id}` | `AccountController` | View account details |
| `PUT` | `/api/accounts/{id}` | `AccountController` | Update account |
| `GET` | `/api/cards` | `CardController` | List cards (paginated) |
| `GET` | `/api/cards/account/{acctId}` | `CardController` | List cards by account |
| `GET` | `/api/cards/{cardNum}` | `CardController` | View card details |
| `PUT` | `/api/cards/{cardNum}` | `CardController` | Update card |
| `GET` | `/api/transactions` | `TransactionController` | List transactions (paginated) |
| `GET` | `/api/transactions/{id}` | `TransactionController` | View transaction details |
| `POST` | `/api/transactions` | `TransactionController` | Add new transaction |
| `GET` | `/api/transactions/copy/{sourceId}` | `TransactionController` | Copy transaction for new entry |
| `POST` | `/api/billing/pay` | `BillingController` | Process bill payment |
| `POST` | `/api/reports/submit` | `ReportController` | Submit report request → SQS |
| `GET` | `/api/admin/users` | `UserAdminController` | List users (admin only) |
| `GET` | `/api/admin/users/{id}` | `UserAdminController` | Get user details (admin only) |
| `POST` | `/api/admin/users` | `UserAdminController` | Add user (admin only) |
| `PUT` | `/api/admin/users/{id}` | `UserAdminController` | Update user (admin only) |
| `DELETE` | `/api/admin/users/{id}` | `UserAdminController` | Delete user (admin only) |
| `GET` | `/api/menu/main` | `MenuController` | Main menu options |
| `GET` | `/api/menu/admin` | `MenuController` | Admin menu options |

> For full request/response schemas and examples, see [`docs/api-contracts.md`](docs/api-contracts.md).

---

## Database

### Schema Overview

The PostgreSQL 16+ schema is managed by **Flyway** migrations and maps all 11 VSAM KSDS datasets to relational tables:

| Table | Source VSAM Dataset | Entity Class | Description |
|:---|:---|:---|:---|
| `accounts` | ACCTDAT | `Account` | Account master — balance, credit limit, status |
| `cards` | CARDDAT | `Card` | Credit card records — number, expiry, status |
| `customers` | CUSTDAT | `Customer` | Customer demographics — name, address, SSN, FICO |
| `card_cross_references` | CARDXREF + CXACAIX | `CardCrossReference` | Card ↔ Account ↔ Customer cross-reference |
| `transactions` | TRANSACT + TRANIDX AIX | `Transaction` | Transaction master — amount, type, timestamp |
| `user_security` | USRSEC | `UserSecurity` | User credentials and roles (BCrypt hashed) |
| `transaction_category_balances` | TCATBALF | `TransactionCategoryBalance` | Category-level balance tracking (composite PK) |
| `disclosure_groups` | DISCGRP | `DisclosureGroup` | Interest rate disclosure groups (composite PK) |
| `transaction_types` | TRANTYPE | `TransactionType` | Transaction type reference data |
| `transaction_categories` | TRANCATG | `TransactionCategory` | Transaction category reference data (composite PK) |
| `daily_transactions` | DALYTRAN | `DailyTransaction` | Batch staging table for daily transaction files |

### Flyway Migrations

| Migration | Description |
|:---|:---|
| `V1__create_schema.sql` | Creates all 11 tables with proper column types, constraints, and foreign keys |
| `V2__create_indexes.sql` | Creates primary, alternate, and composite indexes matching VSAM AIX/PATH access patterns |
| `V3__seed_data.sql` | Loads seed data from the 9 original ASCII fixture files (`acctdata.txt`, `carddata.txt`, `custdata.txt`, `cardxref.txt`, `dailytran.txt`, `discgrp.txt`, `tcatbal.txt`, `trancatg.txt`, `trantype.txt`) |

All financial fields use `NUMERIC` / `BigDecimal` — zero floating-point substitution per the decimal precision rules.

---

## Configuration

### Spring Profiles

| Profile | Purpose | Activation |
|:---|:---|:---|
| `default` | Base configuration shared across all environments | Always active |
| `local` | Connects to Docker Compose infrastructure (PostgreSQL, LocalStack, Jaeger) | `-Dspring-boot.run.profiles=local` |
| `test` | Testcontainers-managed ephemeral infrastructure | Auto-activated by test annotations |

### Configuration Files

| File | Description |
|:---|:---|
| `application.yml` | Base Spring Boot configuration — datasource, JPA, actuator, batch, logging |
| `application-local.yml` | Local dev overrides — LocalStack endpoints (`http://localhost:4566`), local PostgreSQL |
| `application-test.yml` | Test overrides — Testcontainers dynamic ports, in-memory job repository |
| `logback-spring.xml` | Structured JSON logging with correlation IDs, trace/span propagation |

### Environment Variables

No credentials are hardcoded. All secrets are supplied via environment variables:

| Variable | Purpose | Default |
|:---|:---|:---|
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5432/carddemo` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `carddemo` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | (none — must be set) |
| `AWS_ACCESS_KEY_ID` | AWS/LocalStack access key | `test` (LocalStack default) |
| `AWS_SECRET_ACCESS_KEY` | AWS/LocalStack secret key | `test` (LocalStack default) |
| `AWS_DEFAULT_REGION` | AWS region | `us-east-1` |
| `LOCALSTACK_AUTH_TOKEN` | LocalStack Pro license token | (required for LocalStack Pro) |

---

## Testing

### Test Execution

```bash
# Unit tests only
./mvnw test

# Unit + integration tests (requires Docker for Testcontainers)
./mvnw verify

# OWASP dependency vulnerability check
./mvnw verify -Powasp
```

### Test Organization

| Directory | Pattern | Description |
|:---|:---|:---|
| `src/test/java/**/unit/` | `*Test.java` | Unit tests — mocked dependencies, fast execution |
| `src/test/java/**/integration/` | `*IT.java` | Integration tests — Testcontainers (PostgreSQL, LocalStack) |
| `src/test/java/**/e2e/` | `*E2E.java` | End-to-end tests — full batch pipeline and REST API flows |

### Quality Targets

| Metric | Target | Tool |
|:---|:---|:---|
| Line Coverage | ≥ 80% | JaCoCo (`jacoco-maven-plugin`) |
| Branch Coverage | Measured | JaCoCo (reported, not enforced) |
| CVE Scan | Zero critical/high | OWASP `dependency-check-maven` |
| Compilation Warnings | Zero | `javac -Xlint:all` |

---

## Observability

Observability is a first-class concern — shipped with the initial implementation, not as a follow-up.

### Structured Logging

- **Framework:** SLF4J + Logback with `logstash-logback-encoder`
- **Format:** JSON with `traceId`, `spanId`, and custom `correlationId` fields
- **Configuration:** `src/main/resources/logback-spring.xml`
- Every HTTP request generates a correlation ID propagated through all service and batch layers via MDC

### Distributed Tracing

- **Framework:** Micrometer Tracing with OpenTelemetry bridge (`micrometer-tracing-bridge-otel`)
- **Exporter:** OTLP → Jaeger (configurable collector endpoint)
- **Coverage:** All Spring MVC endpoints, JPA repository calls, S3/SQS operations, Spring Batch steps
- **Local UI:** [http://localhost:16686](http://localhost:16686) (Jaeger)

### Metrics

- **Endpoint:** `/actuator/prometheus`
- **Registry:** Micrometer with Prometheus exporter
- **Custom metrics:**
  - `carddemo.batch.records.processed` — counter per job
  - `carddemo.batch.records.rejected` — counter with reason tag
  - `carddemo.auth.attempts` — counter with success/failure tag
  - `carddemo.transaction.amount.total` — distribution summary

### Health Checks

| Endpoint | Purpose |
|:---|:---|
| `/actuator/health` | Composite health — DB, disk, S3, SQS |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |

### Dashboard

A Grafana dashboard template is provided at [`docs/grafana-dashboard.json`](docs/grafana-dashboard.json) with panels for:
- Request rate and error rate
- Response latency (p50 / p95 / p99)
- Batch job throughput and rejection rate
- JVM memory and GC metrics

---

## Documentation

| Document | Description |
|:---|:---|
| [`DECISION_LOG.md`](DECISION_LOG.md) | All non-trivial architectural decisions with alternatives, rationale, and risks |
| [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) | Bidirectional COBOL → Java mapping with 100% paragraph coverage |
| [`docs/executive-presentation.html`](docs/executive-presentation.html) | reveal.js executive summary with architecture diagrams |
| [`docs/architecture-before-after.md`](docs/architecture-before-after.md) | Before/after Mermaid architecture diagrams |
| [`docs/onboarding-guide.md`](docs/onboarding-guide.md) | New developer quickstart — prerequisites through running application |
| [`docs/validation-gates.md`](docs/validation-gates.md) | Validation gate 1–8 evidence and reports |
| [`docs/api-contracts.md`](docs/api-contracts.md) | Full REST API specifications with request/response schemas |

---

## Migration Context

This application was migrated from the **AWS CardDemo COBOL mainframe application** — a credit card management system originally built with COBOL, CICS, VSAM, JCL, and BMS on IBM z/OS.

### Source Reference

- **Repository:** [aws-samples/aws-mainframe-modernization-carddemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo)
- **Commit SHA:** [`27d6c6f`](https://github.com/aws-samples/aws-mainframe-modernization-carddemo/tree/27d6c6f)
- **Source inventory:** 28 COBOL programs (19,254 lines), 28 copybooks, 17 BMS mapsets, 29 JCL jobs, 9 ASCII data fixtures

### Migration Summary

| Source (COBOL/z/OS) | Target (Java/Spring Boot) |
|:---|:---|
| 18 CICS online programs | Spring service classes + REST controllers |
| 10 batch programs | Spring Batch jobs, processors, readers, writers |
| 28 shared copybooks | JPA entities, DTOs, validation utilities |
| 17 BMS mapsets / symbolic maps | REST API request/response contracts |
| 29 JCL jobs | Flyway migrations + Spring Batch jobs |
| 10 VSAM KSDS + 2 AIX/PATH + 1 PS | 11 PostgreSQL tables with JPA repositories |
| CICS TDQ (message queue) | AWS SQS via Spring Cloud AWS |
| GDG generations (versioned files) | AWS S3 versioned objects |
| Plaintext passwords | BCrypt password hashing |
| FILE STATUS codes | Custom Java exception hierarchy |

All 22 features (F-001 through F-022) are preserved with identical business logic. For detailed mapping of every COBOL paragraph to its Java equivalent, see [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md). For architectural decision rationale, see [`DECISION_LOG.md`](DECISION_LOG.md).

---

## Contributing

Contributions are welcome! Please see [`CONTRIBUTING.md`](CONTRIBUTING.md) for guidelines on:

- Reporting bugs and requesting features
- Submitting pull requests
- Code style and testing expectations
- Security vulnerability reporting

---

## License

This project is licensed under the **Apache License 2.0** — see the [`LICENSE`](LICENSE) file for details.

Copyright © 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
