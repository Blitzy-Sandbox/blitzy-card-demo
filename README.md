## CardDemo — Cloud-Native Credit Card Management Application

> **Modernized from a z/OS mainframe stack (COBOL / CICS / VSAM / JCL) to a Python / AWS cloud-native stack (Python 3.11 / FastAPI / PySpark on AWS Glue / Aurora PostgreSQL / AWS ECS Fargate / AWS Step Functions).**
> The original COBOL source tree under `app/` is retained unchanged for traceability.

- [CardDemo — Cloud-Native Credit Card Management Application](#carddemo--cloud-native-credit-card-management-application)
- [Description](#description)
- [Technologies used](#technologies-used)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Local development setup](#local-development-setup)
- [Database migrations and seed data](#database-migrations-and-seed-data)
- [Running the API locally](#running-the-api-locally)
- [Running the batch pipeline](#running-the-batch-pipeline)
- [Deployment](#deployment)
- [Environment configuration](#environment-configuration)
- [Testing](#testing)
- [Application Details](#application-details)
  - [User Functions](#user-functions)
  - [Admin Functions](#admin-functions)
  - [Application Inventory](#application-inventory)
    - [**Online (REST / GraphQL APIs)**](#online-rest--graphql-apis)
    - [**Batch (PySpark on AWS Glue)**](#batch-pyspark-on-aws-glue)
  - [Application Screens (historical reference)](#application-screens-historical-reference)
    - [**Signon Screen**](#signon-screen)
    - [**Main Menu**](#main-menu)
    - [**Admin Menu**](#admin-menu)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Description

CardDemo is a credit card management application originally designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

The application has now been **modernized end-to-end**: all 28 COBOL programs, 28 copybooks, 17 BMS mapsets, and 29 JCL jobs have been re-platformed to a Python-based cloud-native architecture. All 22 business features (`F-001` through `F-022`) have been preserved with full behavioral parity — including COBOL-equivalent financial precision (`PIC S9(n)V99` → `decimal.Decimal`), the 5-stage batch pipeline execution order, the optimistic concurrency check on card updates, the dual-write patterns for account updates and bill payment, and the BCrypt-based user authentication.

Note that the original mainframe coding style is intentionally non-uniform (it was designed to exercise analysis, transformation and migration tooling). The modernized Python code normalizes style via `ruff` and `mypy` while preserving the underlying business logic exactly.

<br/>

## Technologies used

The target stack is fully Python/AWS cloud-native:

1. **Python 3.11** — Runtime aligned with AWS Glue 5.1 and FastAPI recommendation
2. **FastAPI** — Web framework exposing both REST and GraphQL endpoints (GraphQL via `strawberry-graphql`)
3. **PySpark on AWS Glue 5.1** — Serverless Spark 3.5.6 for all batch ETL jobs
4. **Aurora PostgreSQL** — PostgreSQL-compatible relational database (replaces all 10 VSAM KSDS datasets and 3 AIX paths)
5. **AWS ECS Fargate** — Container orchestration for the FastAPI service (replaces the CICS region)
6. **AWS Step Functions** — Pipeline orchestration for the 5-stage batch workflow (replaces JCL `COND` chaining)
7. **GitHub Actions** — CI/CD pipelines (build, lint, test, container push, Glue script upload, deploy)

Supporting services: **AWS S3** (statement / report / reject output — replaces GDG generations), **AWS SQS FIFO** (report submission queue — replaces CICS TDQ `WRITEQ JOBS`), **AWS Secrets Manager** (database credentials and JWT signing key), **AWS CloudWatch** (logs, metrics, dashboards), **AWS ECR** (container image registry), **AWS IAM** (service-to-service authentication — replaces RACF).

Dependencies are declared in:

- [`pyproject.toml`](./pyproject.toml) — Python project metadata and tool configuration (ruff, mypy, pytest)
- [`requirements.txt`](./requirements.txt) — Core shared dependencies (boto3, pydantic, pydantic-settings)
- [`requirements-api.txt`](./requirements-api.txt) — FastAPI stack (fastapi, uvicorn, sqlalchemy, asyncpg, strawberry-graphql, python-jose, passlib)
- [`requirements-glue.txt`](./requirements-glue.txt) — PySpark batch stack (pyspark 3.5.6, pg8000)
- [`requirements-dev.txt`](./requirements-dev.txt) — Testing & quality (pytest, pytest-asyncio, pytest-cov, moto, ruff, mypy, testcontainers)

<br/>

## Architecture

The application is split into two cloud-native workload types sharing a single Aurora PostgreSQL database and a common Python domain model.

- **API Layer (online workload)** — [`src/api/`](./src/api/): FastAPI application deployed as a Docker container on AWS ECS Fargate. The 18 online CICS COBOL programs have been translated to REST endpoints in [`src/api/routers/`](./src/api/routers/) with business logic in [`src/api/services/`](./src/api/services/). A Strawberry GraphQL schema is mounted alongside the REST endpoints (see [`src/api/graphql/`](./src/api/graphql/)). The CICS COMMAREA session is replaced by a stateless JWT issued by [`src/api/services/auth_service.py`](./src/api/services/auth_service.py) and validated by [`src/api/middleware/auth.py`](./src/api/middleware/auth.py).
- **Batch Layer (batch workload)** — [`src/batch/`](./src/batch/): PySpark scripts that execute as AWS Glue 5.1 jobs. Each of the 10 batch COBOL programs maps to exactly one PySpark job in [`src/batch/jobs/`](./src/batch/jobs/). Job orchestration is handled by AWS Step Functions (definition: [`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json)) replacing JCL `COND` parameter chaining.
- **Database Layer** — Aurora PostgreSQL. The 10 VSAM KSDS datasets and 3 alternate-index paths have been normalized to 11 relational tables. Schema, indexes, and seed data are managed via Flyway-style SQL scripts under [`db/migrations/`](./db/migrations/). All monetary columns are stored as `NUMERIC(15,2)` to preserve COBOL `PIC S9(13)V99` precision.
- **Shared Models & Schemas** — [`src/shared/`](./src/shared/): SQLAlchemy 2.x ORM models (translated from COBOL copybook record layouts), Pydantic v2 request/response schemas (translated from BMS symbolic maps), and utility modules for date handling, string processing, and decimal arithmetic.
- **Pipeline** — AWS Step Functions orchestrates the 5-stage batch pipeline in the exact sequence required by the original JCL: `POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT)`. Stages 4a and 4b run in parallel, mirroring the mainframe architecture. Stage failure halts downstream stages (matching JCL `COND` semantics).

For a full architectural deep-dive including sequence diagrams, data flow, and transformation mappings, see [`docs/architecture.md`](./docs/architecture.md).

The original data-model diagram is retained at [`diagrams/CARDDEMO-DataModel.drawio`](./diagrams/CARDDEMO-DataModel.drawio).

<br/>

## Project Structure

```
blitzy-card-demo/
├── src/
│   ├── shared/          # Shared models, schemas, constants, utils, config
│   │   ├── models/      # SQLAlchemy ORM (from COBOL copybooks: CVACT01Y, CVACT02Y, …)
│   │   ├── schemas/     # Pydantic request/response (from BMS symbolic maps)
│   │   ├── constants/   # Messages, lookup codes, menu options
│   │   ├── utils/       # Date, string, decimal helpers
│   │   └── config/      # Settings (Pydantic BaseSettings), AWS client factories
│   ├── api/             # FastAPI REST + GraphQL — 18 online CICS programs
│   │   ├── routers/     # REST endpoints (auth, account, card, transaction, bill, report, user, admin)
│   │   ├── services/    # Business logic (from COBOL PROCEDURE DIVISION paragraphs)
│   │   ├── graphql/     # Strawberry GraphQL schema (types, queries, mutations)
│   │   ├── middleware/  # JWT auth, global error handler
│   │   ├── database.py  # SQLAlchemy async engine + session factory
│   │   ├── dependencies.py
│   │   └── main.py      # FastAPI app entry point
│   └── batch/           # PySpark on AWS Glue — 10 batch COBOL programs
│       ├── common/      # GlueContext factory, JDBC connector, S3 utils
│       ├── jobs/        # posttran_job, intcalc_job, combtran_job, creastmt_job, tranrept_job, …
│       └── pipeline/    # Step Functions state machine definition
├── db/
│   └── migrations/      # Flyway-style SQL: V1__schema.sql, V2__indexes.sql, V3__seed_data.sql
├── tests/               # pytest suite (unit, integration, end-to-end)
│   ├── unit/
│   ├── integration/
│   └── e2e/
├── infra/               # AWS infrastructure configuration
│   ├── ecs-task-definition.json
│   ├── glue-job-configs/
│   └── cloudwatch/
├── .github/
│   └── workflows/       # CI/CD: ci.yml, deploy-api.yml, deploy-glue.yml
├── docs/                # MkDocs site content (index, project-guide, architecture, tech-specs)
├── diagrams/            # Original flow and screen diagrams (retained)
├── samples/             # Legacy sample JCL (retained for traceability)
├── app/                 # ORIGINAL COBOL / CICS / VSAM / JCL source — retained unchanged for traceability
│   ├── cbl/             #   28 COBOL programs (10 batch + 18 online)
│   ├── cpy/             #   28 copybooks
│   ├── bms/             #   17 BMS mapsets
│   ├── cpy-bms/         #   17 symbolic-map copybooks
│   ├── jcl/             #   29 JCL job members
│   ├── data/ASCII/      #   9 fixture data files
│   └── catlg/           #   IDCAMS catalog report
├── Dockerfile           # Container image for the FastAPI service on ECS Fargate
├── docker-compose.yml   # Local dev stack (API + PostgreSQL 16 + LocalStack)
├── pyproject.toml       # Python project metadata, ruff, mypy, pytest config
├── requirements*.txt    # Python dependency lists (core / api / glue / dev)
├── mkdocs.yml           # MkDocs documentation tooling
├── catalog-info.yaml    # Backstage catalog descriptor
├── LICENSE              # Apache License 2.0
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
└── README.md            # You are here
```

<br/>

## Prerequisites

To run CardDemo locally or deploy to AWS you need:

- **Python 3.11** (the exact version used by AWS Glue 5.1)
- **Docker** and **Docker Compose** (for the local stack)
- **AWS CLI v2** (configured with credentials for any cloud deployment)
- **Git** (for cloning and for GitHub Actions-driven deployment)

No mainframe tooling (HLQ, IDCAMS, DFHCSDUP, CEDA, CEMT) is required — the modernized stack runs entirely on commodity Linux/macOS/Windows hosts and AWS-managed services.

<br/>

## Local development setup

The project ships with a fully self-contained local stack (FastAPI + PostgreSQL 16 + LocalStack) defined in [`docker-compose.yml`](./docker-compose.yml). No real AWS account is required for local development.

```shell
# 1. Clone the repository
git clone <this-repo-url>
cd blitzy-card-demo

# 2. (Optional) Create a Python virtual environment for IDE tooling / local test runs
python3.11 -m venv venv
source venv/bin/activate
pip install -r requirements.txt -r requirements-api.txt -r requirements-dev.txt

# 3. Start the local stack (API + PostgreSQL + LocalStack)
docker-compose up --build
```

`docker-compose up` will:

- Build the API container from [`Dockerfile`](./Dockerfile)
- Start **PostgreSQL 16** with database `carddemo` (user/password `carddemo`/`carddemo`)
- Start **LocalStack** exposing S3, SQS and Secrets Manager on `http://localhost:4566` (AWS service simulators used by the batch layer and report submission flow)
- Start the **FastAPI** application on `http://localhost:8000` with hot-reload enabled

<br/>

## Database migrations and seed data

All schema, indexes, and seed data live under [`db/migrations/`](./db/migrations/) and follow a Flyway-style naming convention. Migrations are mounted into PostgreSQL at container-init time via `docker-entrypoint-initdb.d` (see [`docker-compose.yml`](./docker-compose.yml)). For Aurora PostgreSQL they should be executed by Flyway or an equivalent migration runner in your CI/CD pipeline.

| Script                               | Source                                                                                                    | Purpose                                                                                                                                    |
| :----------------------------------- | :-------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------- |
| `db/migrations/V1__schema.sql`       | `app/jcl/ACCTFILE.jcl`, `CARDFILE.jcl`, `CUSTFILE.jcl`, `TRANFILE.jcl`, `XREFFILE.jcl`, `TCATBALF.jcl`, `DUSRSECJ.jcl` | `CREATE TABLE` for all 11 entities (account, card, customer, card_cross_reference, transaction, transaction_category_balance, daily_transaction, disclosure_group, transaction_type, transaction_category, user_security) |
| `db/migrations/V2__indexes.sql`      | `app/jcl/TRANIDX.jcl`, `app/catlg/LISTCAT.txt`                                                            | B-tree indexes replacing the 3 VSAM AIX paths (`card.acct_id`, `card_cross_reference.acct_id`, `transaction.proc_ts`)                     |
| `db/migrations/V3__seed_data.sql`    | `app/data/ASCII/*.txt`                                                                                    | Reference and fixture data: 50 accounts, 50 cards, 50 customers, 50 cross-references, 51 disclosure groups, 18 categories, 7 types, 50 category balances |

To run migrations manually against an existing database:

```shell
psql "$DATABASE_URL_SYNC" -f db/migrations/V1__schema.sql
psql "$DATABASE_URL_SYNC" -f db/migrations/V2__indexes.sql
psql "$DATABASE_URL_SYNC" -f db/migrations/V3__seed_data.sql
```

Seed data preserves the original mainframe fixtures exactly — the default application credentials remain `ADMIN001 / PASSWORD` for admin users and `USER0001 / PASSWORD` for regular users (now BCrypt-hashed in the `user_security` table).

<br/>

## Running the API locally

With `docker-compose up` running:

- REST API:       http://localhost:8000
- OpenAPI docs:   http://localhost:8000/docs  (auto-generated by FastAPI)
- ReDoc:          http://localhost:8000/redoc
- GraphQL UI:     http://localhost:8000/graphql

To run the API outside of Docker (e.g. against a local PostgreSQL or an Aurora instance):

```shell
source venv/bin/activate
export DATABASE_URL="postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo"
export JWT_SECRET_KEY="dev-secret-key"
uvicorn src.api.main:app --reload --host 0.0.0.0 --port 8000
```

Sign on via `POST /auth/login` with `{"userId": "USER0001", "password": "PASSWORD"}` (or `ADMIN001` for admin functions). The response contains a JWT that must be supplied as `Authorization: Bearer <token>` on subsequent requests — this replaces the CICS COMMAREA session state established by `COSGN00C`.

<br/>

## Running the batch pipeline

The 5-stage batch pipeline preserves the exact sequencing of the original JCL architecture:

```
                  ┌───────────────┐
POSTTRAN ──► INTCALC ──► COMBTRAN ──►│  CREASTMT     │  (stage 4a)
                                     │      ∥        │
                                     │  TRANREPT     │  (stage 4b, parallel)
                                     └───────────────┘
```

| Stage | Glue Job                                                  | PySpark Script                                          | Replaces JCL                 | Replaces COBOL                                      |
| :---- | :-------------------------------------------------------- | :------------------------------------------------------ | :--------------------------- | :-------------------------------------------------- |
| 1     | Transaction posting (4-stage validation cascade)          | [`src/batch/jobs/posttran_job.py`](./src/batch/jobs/posttran_job.py) | `app/jcl/POSTTRAN.jcl`       | `app/cbl/CBTRN02C.cbl`                              |
| 2     | Interest calculation `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` | [`src/batch/jobs/intcalc_job.py`](./src/batch/jobs/intcalc_job.py)   | `app/jcl/INTCALC.jcl`        | `app/cbl/CBACT04C.cbl`                              |
| 3     | Transaction merge/sort (replaces DFSORT + REPRO)          | [`src/batch/jobs/combtran_job.py`](./src/batch/jobs/combtran_job.py) | `app/jcl/COMBTRAN.jcl`       | *(pure sort — no COBOL source)*                     |
| 4a    | Statement generation (text + HTML, 4-entity join)         | [`src/batch/jobs/creastmt_job.py`](./src/batch/jobs/creastmt_job.py) | `app/jcl/CREASTMT.jcl`       | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`      |
| 4b    | Transaction report with 3-level totals                    | [`src/batch/jobs/tranrept_job.py`](./src/batch/jobs/tranrept_job.py) | `app/jcl/TRANREPT.jcl`       | `app/cbl/CBTRN03C.cbl`                              |

Orchestration is defined in [`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json). A stage failure halts all downstream stages, matching the JCL `COND=(0,NE)` semantics of the original architecture.

To execute an individual PySpark job locally (for development/debugging against your local PostgreSQL):

```shell
source venv/bin/activate
pip install -r requirements-glue.txt
spark-submit \
  --packages org.postgresql:postgresql:42.7.3 \
  src/batch/jobs/posttran_job.py \
  --jdbc-url "jdbc:postgresql://localhost:5432/carddemo" \
  --jdbc-user carddemo \
  --jdbc-password carddemo
```

In production the jobs are executed as AWS Glue 5.1 jobs (G.1X workers, configured in [`infra/glue-job-configs/`](./infra/glue-job-configs/)) and triggered by the Step Functions state machine.

<br/>

## Deployment

CI/CD is fully automated through GitHub Actions. Three workflows cover the entire delivery pipeline:

| Workflow                                                            | Trigger                                     | Purpose                                                                                      |
| :------------------------------------------------------------------ | :------------------------------------------ | :------------------------------------------------------------------------------------------- |
| [`.github/workflows/ci.yml`](./.github/workflows/ci.yml)            | Every push / PR                             | Lint (`ruff`), type-check (`mypy`), unit + integration tests (`pytest`), coverage reporting |
| [`.github/workflows/deploy-api.yml`](./.github/workflows/deploy-api.yml) | Push to `main` / manual dispatch            | Build Docker image → push to **ECR** → update **ECS Fargate** service                        |
| [`.github/workflows/deploy-glue.yml`](./.github/workflows/deploy-glue.yml) | Push to `main` / manual dispatch            | Upload PySpark scripts to **S3** → update **AWS Glue** job definitions → update **Step Functions** state machine |

**API deployment flow:** `docker build` → `docker push <account>.dkr.ecr.<region>.amazonaws.com/carddemo-api` → `aws ecs update-service --force-new-deployment`. The ECS task definition template lives at [`infra/ecs-task-definition.json`](./infra/ecs-task-definition.json).

**Batch deployment flow:** `aws s3 sync src/batch/ s3://<glue-script-bucket>/carddemo/batch/` → `aws glue update-job` for each of the 5 pipeline jobs (configs under [`infra/glue-job-configs/`](./infra/glue-job-configs/)) → `aws stepfunctions update-state-machine` using [`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json).

**Database deployment flow:** Run Flyway migrations (`db/migrations/V*.sql`) against the target Aurora PostgreSQL cluster. Credentials are retrieved at runtime from **AWS Secrets Manager** — no database passwords are ever stored in the codebase or in task definitions.

A unified CloudWatch dashboard template is provided at [`infra/cloudwatch/dashboard.json`](./infra/cloudwatch/dashboard.json) covering Glue job duration/DPU/error-rate metrics and ECS CPU/memory/request-count/error-rate metrics.

<br/>

## Environment configuration

Runtime configuration is driven by environment variables loaded via Pydantic `BaseSettings` — see [`src/shared/config/settings.py`](./src/shared/config/settings.py) for the authoritative definitions.

| Variable                                  | Purpose                                                      | Example (local dev)                                              |
| :---------------------------------------- | :----------------------------------------------------------- | :--------------------------------------------------------------- |
| `DATABASE_URL`                            | Async SQLAlchemy URL used by FastAPI                         | `postgresql+asyncpg://carddemo:carddemo@postgres:5432/carddemo`  |
| `DATABASE_URL_SYNC`                       | Sync SQLAlchemy URL for Alembic / migrations                 | `postgresql+psycopg2://carddemo:carddemo@postgres:5432/carddemo` |
| `JWT_SECRET_KEY`                          | HS256 signing key for JWT tokens                             | retrieved from Secrets Manager in production                     |
| `JWT_ALGORITHM`                           | Token algorithm                                              | `HS256`                                                          |
| `JWT_ACCESS_TOKEN_EXPIRE_MINUTES`         | Token TTL                                                    | `60`                                                             |
| `AWS_REGION` / `AWS_DEFAULT_REGION`       | AWS region for SQS, S3, Secrets Manager                      | `us-east-1`                                                      |
| `AWS_ENDPOINT_URL`                        | **Local dev only** — LocalStack endpoint                     | `http://localstack:4566`                                         |
| `ENVIRONMENT`                             | Environment tag                                              | `development` \| `staging` \| `production`                       |
| `LOG_LEVEL`                               | Structured JSON log level                                    | `INFO`                                                           |

In production the ECS task should obtain database credentials and the JWT signing key via **AWS Secrets Manager** (retrieved using the ECS task IAM role — no AWS access keys on disk) and the AWS region from the task metadata.

<br/>

## Testing

The test suite uses **pytest** with async support and lives under [`tests/`](./tests/):

- `tests/unit/` — fast unit tests for models, services, routers, and PySpark transformations
- `tests/integration/` — database-backed tests using `testcontainers[postgres]` and API tests using `httpx.AsyncClient` against a running FastAPI app
- `tests/e2e/` — end-to-end batch pipeline tests exercising the full POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT) flow

Run the full suite:

```shell
source venv/bin/activate
pytest                                  # full suite with coverage (fails below 80%)
pytest tests/unit/                      # unit tests only
pytest tests/unit/test_services/        # a targeted subset
pytest -m "not slow"                    # skip long-running tests
```

AWS services are mocked via **moto** (S3, SQS, Secrets Manager, Glue). Lint and type-check:

```shell
ruff check .
ruff format --check .
mypy src/
```

<br/>

## Application Details

CardDemo is a credit card management application that lets users manage Accounts, Credit Cards, Transactions and Bill Payments. There are two user roles:

- **Regular User** — performs customer-facing user functions
- **Admin User** — performs administrative functions (user CRUD)

Regular users can only perform user functions and admin users can only perform admin functions. All 22 features (`F-001` through `F-022`) are preserved in the modernized implementation with full behavioral parity.

<br/>

### User Functions

![User Flow](./diagrams/Application-Flow-User.png?raw=true "User Flow")

*Diagram depicts the original CICS transaction flow; the modernized application exposes the same flow as a REST/GraphQL API.*

<br/>

### Admin Functions

![Admin Flow](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

*Diagram depicts the original CICS transaction flow; the modernized application exposes the same flow as a REST/GraphQL API.*

<br/>

### Application Inventory

#### **Online (REST / GraphQL APIs)**

The 18 online CICS COBOL programs have been translated to REST endpoints (plus equivalent GraphQL queries/mutations). The table below cross-references each legacy transaction/program to its modern REST endpoint and Python module.

| Legacy Trans | Legacy Program | REST Endpoint                                | Python Module                                                                        | Feature |
| :----------- | :------------- | :------------------------------------------- | :----------------------------------------------------------------------------------- | :------ |
| CC00         | COSGN00C       | `POST /auth/login`, `POST /auth/logout`      | [`src/api/routers/auth_router.py`](./src/api/routers/auth_router.py)                 | F-001   |
| CM00         | COMEN01C       | `GET /menu`                                  | [`src/api/main.py`](./src/api/main.py) (menu is static — see `src/shared/constants/menu_options.py`) | F-002   |
| CA00         | COADM01C       | `GET /admin/menu`                            | [`src/api/routers/admin_router.py`](./src/api/routers/admin_router.py)               | F-003   |
| CAVW         | COACTVWC       | `GET /accounts/{id}`                         | [`src/api/routers/account_router.py`](./src/api/routers/account_router.py)           | F-004   |
| CAUP         | COACTUPC       | `PUT /accounts/{id}`                         | [`src/api/routers/account_router.py`](./src/api/routers/account_router.py)           | F-005   |
| CCLI         | COCRDLIC       | `GET /cards`                                 | [`src/api/routers/card_router.py`](./src/api/routers/card_router.py)                 | F-006   |
| CCDL         | COCRDSLC       | `GET /cards/{id}`                            | [`src/api/routers/card_router.py`](./src/api/routers/card_router.py)                 | F-007   |
| CCUP         | COCRDUPC       | `PUT /cards/{id}`                            | [`src/api/routers/card_router.py`](./src/api/routers/card_router.py)                 | F-008   |
| CT00         | COTRN00C       | `GET /transactions`                          | [`src/api/routers/transaction_router.py`](./src/api/routers/transaction_router.py)   | F-009   |
| CT01         | COTRN01C       | `GET /transactions/{id}`                     | [`src/api/routers/transaction_router.py`](./src/api/routers/transaction_router.py)   | F-010   |
| CT02         | COTRN02C       | `POST /transactions`                         | [`src/api/routers/transaction_router.py`](./src/api/routers/transaction_router.py)   | F-011   |
| CB00         | COBIL00C       | `POST /bills/pay`                            | [`src/api/routers/bill_router.py`](./src/api/routers/bill_router.py)                 | F-012   |
| CR00         | CORPT00C       | `POST /reports/submit`                       | [`src/api/routers/report_router.py`](./src/api/routers/report_router.py)             | F-022   |
| CU00         | COUSR00C       | `GET /users`                                 | [`src/api/routers/user_router.py`](./src/api/routers/user_router.py)                 | F-018   |
| CU01         | COUSR01C       | `POST /users`                                | [`src/api/routers/user_router.py`](./src/api/routers/user_router.py)                 | F-019   |
| CU02         | COUSR02C       | `PUT /users/{id}`                            | [`src/api/routers/user_router.py`](./src/api/routers/user_router.py)                 | F-020   |
| CU03         | COUSR03C       | `DELETE /users/{id}`                         | [`src/api/routers/user_router.py`](./src/api/routers/user_router.py)                 | F-021   |
| *(shared)*   | CSUTLDTC       | *(used internally for date validation)*      | [`src/shared/utils/date_utils.py`](./src/shared/utils/date_utils.py)                 | shared  |

All endpoints are also exposed as GraphQL queries/mutations via Strawberry — see [`src/api/graphql/schema.py`](./src/api/graphql/schema.py).

#### **Batch (PySpark on AWS Glue)**

The 10 batch COBOL programs have been translated to PySpark jobs. The original file-provisioning JCL (`ACCTFILE`, `CARDFILE`, etc.) is replaced by the `db/migrations/V*.sql` scripts against Aurora PostgreSQL; the original CICS file-management JCL (`OPENFIL`, `CLOSEFIL`) has no equivalent in the target architecture.

| Legacy JCL              | Legacy Program     | Glue Job                              | PySpark Script                                                                               | Function                                                        |
| :---------------------- | :----------------- | :------------------------------------ | :------------------------------------------------------------------------------------------- | :-------------------------------------------------------------- |
| `app/jcl/POSTTRAN.jcl`  | `CBTRN02C`         | `carddemo-posttran`                   | [`src/batch/jobs/posttran_job.py`](./src/batch/jobs/posttran_job.py)                         | Stage 1 — Transaction posting (4-stage validation cascade)      |
| `app/jcl/INTCALC.jcl`   | `CBACT04C`         | `carddemo-intcalc`                    | [`src/batch/jobs/intcalc_job.py`](./src/batch/jobs/intcalc_job.py)                           | Stage 2 — Interest calculation                                  |
| `app/jcl/COMBTRAN.jcl`  | *(DFSORT+REPRO)*   | `carddemo-combtran`                   | [`src/batch/jobs/combtran_job.py`](./src/batch/jobs/combtran_job.py)                         | Stage 3 — Transaction merge/sort                                |
| `app/jcl/CREASTMT.jcl`  | `CBSTM03A/B`       | `carddemo-creastmt`                   | [`src/batch/jobs/creastmt_job.py`](./src/batch/jobs/creastmt_job.py)                         | Stage 4a — Statement generation                                 |
| `app/jcl/TRANREPT.jcl`  | `CBTRN03C`         | `carddemo-tranrept`                   | [`src/batch/jobs/tranrept_job.py`](./src/batch/jobs/tranrept_job.py)                         | Stage 4b — Transaction report (3-level totals)                  |
| `app/jcl/PRTCATBL.jcl`  | *(util)*           | `carddemo-prtcatbl`                   | [`src/batch/jobs/prtcatbl_job.py`](./src/batch/jobs/prtcatbl_job.py)                         | Print transaction category balance                              |
| *(daily driver)*        | `CBTRN01C`         | `carddemo-daily-tran-driver`          | [`src/batch/jobs/daily_tran_driver_job.py`](./src/batch/jobs/daily_tran_driver_job.py)       | Daily transaction ingest driver                                 |
| `app/jcl/READACCT.jcl`  | `CBACT01C`         | `carddemo-read-account`               | [`src/batch/jobs/read_account_job.py`](./src/batch/jobs/read_account_job.py)                 | Account diagnostic reader                                       |
| `app/jcl/READCARD.jcl`  | `CBACT02C`         | `carddemo-read-card`                  | [`src/batch/jobs/read_card_job.py`](./src/batch/jobs/read_card_job.py)                       | Card diagnostic reader                                          |
| `app/jcl/READXREF.jcl`  | `CBACT03C`         | `carddemo-read-xref`                  | [`src/batch/jobs/read_xref_job.py`](./src/batch/jobs/read_xref_job.py)                       | Cross-reference diagnostic reader                               |
| `app/jcl/READCUST.jcl`  | `CBCUS01C`         | `carddemo-read-customer`              | [`src/batch/jobs/read_customer_job.py`](./src/batch/jobs/read_customer_job.py)               | Customer diagnostic reader                                      |

Legacy file-provisioning JCL (`ACCTFILE`, `CARDFILE`, `CUSTFILE`, `TRANFILE`, `XREFFILE`, `TCATBALF`, `DISCGRP`, `TRANCATG`, `TRANTYPE`, `DUSRSECJ`, `DEFCUST`, `TRANIDX`, `DEFGDGB`, `REPTFILE`, `DALYREJS`, `TRANBKP`) is replaced by [`db/migrations/V1__schema.sql`](./db/migrations/V1__schema.sql), [`db/migrations/V2__indexes.sql`](./db/migrations/V2__indexes.sql), [`db/migrations/V3__seed_data.sql`](./db/migrations/V3__seed_data.sql), and AWS S3 bucket configuration for GDG-equivalent output.

Legacy CICS file-management JCL (`OPENFIL.jcl`, `CLOSEFIL.jcl`, `CBADMCDJ.jcl`) has **no equivalent** in the cloud-native target — database connections are pooled by SQLAlchemy on demand and no CICS region exists.

<br/>

### Application Screens (historical reference)

The modernized application is **headless** — it exposes only REST and GraphQL APIs and does not render BMS screens. The diagrams below depict the **original CICS screens** for historical reference. The REST endpoints that replace each screen are documented in the [Online inventory table](#online-rest--graphql-apis) above; interactive API documentation is auto-generated at `/docs` (Swagger UI), `/redoc` (ReDoc), and `/graphql` (GraphQL playground).

#### **Signon Screen**

![Signon Screen](./diagrams/Signon-Screen.png?raw=true "Signon Screen")

*Replaced by* `POST /auth/login` *in [`src/api/routers/auth_router.py`](./src/api/routers/auth_router.py)*

#### **Main Menu**

![Main Menu](./diagrams/Main-Menu.png?raw=true "Main Menu")

*Replaced by* `GET /menu` *— menu options are defined in [`src/shared/constants/menu_options.py`](./src/shared/constants/menu_options.py)*

#### **Admin Menu**

![Admin Menu](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

*Replaced by* `GET /admin/menu` *in [`src/api/routers/admin_router.py`](./src/api/routers/admin_router.py)*

<br/>

## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The modernization itself is complete; planned enhancements for upcoming releases focus on extending the cloud-native capabilities established by this migration:

1. **Observability enhancements**
   - OpenTelemetry-based distributed tracing across API → database → batch SQS flows
   - Per-feature business-metric dashboards (transactions/sec, posting latency, reject-code distribution)

2. **Data and integration**
   - Change-data-capture (CDC) from Aurora PostgreSQL to an analytics lake (S3 / Athena)
   - Event-driven integrations via Amazon EventBridge for transaction and account lifecycle events
   - Alternative GraphQL federation for cross-application integration

3. **Operational scaling**
   - Aurora Serverless v2 auto-scaling profile
   - Blue/green ECS deployment automation
   - Additional Glue worker profiles for higher-volume statement generation

4. **Security**
   - IAM database authentication (zero-password Aurora connections)
   - Fine-grained Cognito-based identity federation as an alternative to JWT

<br/>

## Contributing

We welcome contributions and enhancements to the modernized codebase. Whether you're improving the Python/AWS implementation, adding test coverage, refining documentation, or exploring new cloud-native patterns, please feel free to raise issues and pull requests. See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for the contributor workflow, testing expectations, and commit-message conventions.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license. See [`LICENSE`](./LICENSE) for the full text.

<br/>

## Project status

**Migration complete.** The modernization from COBOL / CICS / VSAM / JCL to Python / FastAPI / PySpark / Aurora PostgreSQL / AWS ECS / AWS Glue / AWS Step Functions has been delivered with full behavioral parity across all 22 features (`F-001` through `F-022`). The original mainframe source tree under `app/` is retained unchanged for traceability and remains available as a reference for future migration exercises.

Watch this space for updates on the roadmap items above.

<br/>
