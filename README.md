## CardDemo — Cloud-Native Credit Card Management Application

> **In-progress modernization** from a z/OS mainframe stack (COBOL / CICS / VSAM / JCL) to a Python / AWS cloud-native stack (Python 3.11 / FastAPI / PySpark on AWS Glue 5.1 / Aurora PostgreSQL / AWS ECS Fargate / AWS Step Functions).
> The original COBOL source tree under `app/` is retained unchanged for traceability.

- [CardDemo — Cloud-Native Credit Card Management Application](#carddemo--cloud-native-credit-card-management-application)
- [Description](#description)
- [Implementation Status](#implementation-status)
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

This repository hosts the in-progress modernization of CardDemo from its original mainframe stack (COBOL / CICS / VSAM / JCL) to a Python-based cloud-native architecture. The target design — documented in detail in [`docs/architecture.md`](./docs/architecture.md) — splits the application into two cloud-native workload types (FastAPI on AWS ECS Fargate for the online features, and PySpark on AWS Glue 5.1 for the batch pipeline) sharing a single Aurora PostgreSQL database.

At this checkpoint, the **shared foundation layer has been delivered** (SQLAlchemy ORM models, Pydantic schemas, shared constants, utilities, configuration settings, database migrations, infrastructure templates, CI/CD workflows, container configuration, and the Step Functions state machine). The **FastAPI service layer and PySpark batch jobs are planned** and are scheduled to be delivered by subsequent implementation passes — see [Implementation Status](#implementation-status) below for the authoritative checkpoint-level breakdown.

Note that the original mainframe coding style is intentionally non-uniform (it was designed to exercise analysis, transformation and migration tooling). The modernized Python code normalizes style via `ruff` and `mypy` while preserving the underlying business logic verbatim where it has been translated.

<br/>

## Implementation Status

The table below is the authoritative checkpoint-level breakdown of the migration status. It is derived directly from the on-disk source tree and the Agent Action Plan (AAP §0.5.1). Planned items are not present on disk at this checkpoint.

| # | Workstream                                                                   | Status         | Notes                                                                                                                                                 |
|---|------------------------------------------------------------------------------|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | SQLAlchemy ORM models ([`src/shared/models/`](./src/shared/models/))         | ✅ Implemented | 11 models translated from COBOL copybooks (`CVACT01Y`, `CVACT02Y`, `CVACT03Y`, `CVCUS01Y`, `CVTRA01Y` through `CVTRA06Y`, `CSUSR01Y`)                 |
| 2 | Pydantic request/response schemas ([`src/shared/schemas/`](./src/shared/schemas/)) | ✅ Implemented | 8 schema modules translated from BMS symbolic maps (account, auth, bill, card, customer, report, transaction, user)                                  |
| 3 | Shared constants ([`src/shared/constants/`](./src/shared/constants/))        | ✅ Implemented | `messages.py`, `lookup_codes.py`, `menu_options.py` — translated from `CSMSG01Y`, `CSMSG02Y`, `CSLKPCDY`, `COMEN02Y`, `COADM02Y`, `COTTL01Y`          |
| 4 | Shared utilities ([`src/shared/utils/`](./src/shared/utils/))                | ✅ Implemented | `date_utils.py`, `decimal_utils.py`, `string_utils.py` — translated from `CSUTLDTC`, `CSDAT01Y`, `CSUTLDWY`, `CSUTLDPY`, `CSSTRPFY`                   |
| 5 | Settings / AWS config ([`src/shared/config/`](./src/shared/config/))         | ✅ Implemented | Pydantic `BaseSettings`, AWS client factories with Secrets Manager support                                                                            |
| 6 | Database schema + seed migrations ([`db/migrations/`](./db/migrations/))     | ✅ Implemented | V1 (11 tables), V2 (3 indexes), V3 (636 seed rows) — applied cleanly to PostgreSQL 16                                                                 |
| 7 | Step Functions state machine ([`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json)) | ✅ Implemented | 5-stage orchestration: `POSTTRAN → INTCALC → COMBTRAN → parallel(CREASTMT, TRANREPT)` with failure-halts-downstream error routing                    |
| 8 | AWS Glue job definitions ([`infra/glue-job-configs/`](./infra/glue-job-configs/)) | ✅ Implemented | 5 configs: `posttran.json`, `intcalc.json`, `combtran.json`, `creastmt.json`, `tranrept.json` (Glue 5.1, G.1X, 2 workers)                              |
| 9 | ECS task definition ([`infra/ecs-task-definition.json`](./infra/ecs-task-definition.json)) | ✅ Implemented | Fargate template for FastAPI service                                                                                                                  |
| 10 | CloudWatch dashboard template ([`infra/cloudwatch/dashboard.json`](./infra/cloudwatch/dashboard.json)) | ✅ Implemented | Unified Glue + ECS metrics                                                                                                                            |
| 11 | GitHub Actions workflows ([`.github/workflows/`](./.github/workflows/))      | ✅ Implemented | `ci.yml`, `deploy-api.yml`, `deploy-glue.yml` — note: `ci.yml` depends on tests (see row 15) and `deploy-glue.yml` iterates over 11 jobs (see row 14) |
| 12 | Container / local-dev configuration                                          | ✅ Implemented | [`Dockerfile`](./Dockerfile) (Python 3.11-slim single-stage), [`docker-compose.yml`](./docker-compose.yml) (API + PostgreSQL 16 + LocalStack)         |
| 13 | FastAPI service layer (`src/api/main.py`, `database.py`, `dependencies.py`, `middleware/`, `routers/`, `services/`, full `graphql/`) | 🏗 Planned      | Only package `__init__.py` files are present on disk. Translation of the 18 online CICS programs (`COSGN00C`, `COMEN01C`, `COADM01C`, `COACTVWC`, `COACTUPC`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COTRN00C`, `COTRN01C`, `COTRN02C`, `COBIL00C`, `CORPT00C`, `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C`, `CSUTLDTC`) is pending |
| 14 | PySpark batch jobs (`src/batch/jobs/*_job.py`, `src/batch/common/`)          | 🏗 Planned      | Only `src/batch/__init__.py` and `src/batch/jobs/__init__.py` exist on disk. Translation of the 10 batch COBOL programs (`CBTRN01C`, `CBTRN02C`, `CBTRN03C`, `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBACT04C`, `CBCUS01C`, `CBSTM03A`, `CBSTM03B`) plus `COMBTRAN` PySpark merge/sort is pending |
| 15 | Test suite (`tests/unit/`, `tests/integration/`, `tests/e2e/`, `tests/conftest.py`) | 🏗 Planned      | Only empty package `__init__.py` files are present. `pytest --collect-only` reports zero items at this checkpoint.                                    |
| 16 | Supplementary documentation (`DECISION_LOG.md`, `TRACEABILITY_MATRIX.md`, `docs/api-contracts.md`, `docs/onboarding-guide.md`, `docs/validation-gates.md`) | 🏗 Planned      | Not present on disk. Required AAP documentation deliverables ([`docs/architecture.md`](./docs/architecture.md), [`docs/project-guide.md`](./docs/project-guide.md), [`docs/index.md`](./docs/index.md), and this README) are current. |

When reading the sections that follow, assume any `src/api/**` or `src/batch/jobs/**` path is aspirational — the file path is shown so developers can see the AAP-mapped target location where the module will be created by the next implementation pass. Paths under `src/shared/**`, `db/migrations/`, `infra/`, `.github/workflows/`, and the top-level container files are all present and usable today.

<br/>

## Technologies used

The target stack is fully Python/AWS cloud-native:

1. **Python 3.11** — Runtime aligned with AWS Glue 5.1 and FastAPI recommendation
2. **FastAPI** — Web framework exposing both REST and GraphQL endpoints (GraphQL via `strawberry-graphql`) — 🏗 service layer planned
3. **PySpark on AWS Glue 5.1** — Serverless Spark 3.5.6 for all batch ETL jobs — 🏗 job scripts planned; Glue configs provisioned
4. **Aurora PostgreSQL** — PostgreSQL-compatible relational database (replaces all 10 VSAM KSDS datasets and 3 AIX paths) — ✅ schema delivered
5. **AWS ECS Fargate** — Container orchestration for the FastAPI service (replaces the CICS region) — ✅ task template delivered
6. **AWS Step Functions** — Pipeline orchestration for the 5-stage batch workflow (replaces JCL `COND` chaining) — ✅ state machine delivered
7. **GitHub Actions** — CI/CD pipelines (build, lint, test, container push, Glue script upload, deploy) — ✅ workflows delivered

Supporting services: **AWS S3** (statement / report / reject output — replaces GDG generations), **AWS SQS FIFO** (report submission queue — replaces CICS TDQ `WRITEQ JOBS`), **AWS Secrets Manager** (database credentials and JWT signing key), **AWS CloudWatch** (logs, metrics, dashboards), **AWS ECR** (container image registry), **AWS IAM** (service-to-service authentication — replaces RACF).

Dependencies are declared in:

- [`pyproject.toml`](./pyproject.toml) — Python project metadata and tool configuration (ruff, mypy, pytest)
- [`requirements.txt`](./requirements.txt) — Core shared dependencies (boto3, pydantic, pydantic-settings)
- [`requirements-api.txt`](./requirements-api.txt) — FastAPI stack (fastapi, uvicorn, sqlalchemy, asyncpg, strawberry-graphql, `python-jose[cryptography]>=3.4.0,<4.0`, passlib)
- [`requirements-glue.txt`](./requirements-glue.txt) — PySpark batch stack (pyspark 3.5.6, pg8000)
- [`requirements-dev.txt`](./requirements-dev.txt) — Testing & quality (pytest, pytest-asyncio, pytest-cov, moto, ruff, mypy, testcontainers)

> **Security note:** `python-jose[cryptography]` is pinned at `>=3.4.0,<4.0` to include the fixes for CVE-2024-33663 (algorithm confusion with OpenSSH ECDSA keys) and CVE-2024-33664 (DoS via compressed JWE tokens). Both CVEs affect 3.3.0 and earlier. Do not downgrade.

<br/>

## Architecture

The application is split into two cloud-native workload types sharing a single Aurora PostgreSQL database and a common Python domain model.

- **API Layer (online workload)** — `src/api/` (🏗 planned): FastAPI application deployed as a Docker container on AWS ECS Fargate. The 18 online CICS COBOL programs are planned to be translated to REST endpoints in `src/api/routers/` with business logic in `src/api/services/`. A Strawberry GraphQL schema will be mounted alongside the REST endpoints (under `src/api/graphql/`). The CICS COMMAREA session will be replaced by a stateless JWT issued by the planned `src/api/services/auth_service.py` and validated by the planned `src/api/middleware/auth.py`.
- **Batch Layer (batch workload)** — `src/batch/` (jobs 🏗 planned, orchestration ✅ delivered): PySpark scripts will execute as AWS Glue 5.1 jobs. Each of the 10 batch COBOL programs maps to exactly one planned PySpark job under `src/batch/jobs/`. Orchestration is already in place via AWS Step Functions — definition at [`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json) — replacing JCL `COND` parameter chaining.
- **Database Layer** — Aurora PostgreSQL (✅ schema delivered). The 10 VSAM KSDS datasets and 3 alternate-index paths have been normalized to 11 relational tables. Schema, indexes, and seed data are managed via Flyway-style SQL scripts under [`db/migrations/`](./db/migrations/). All monetary columns are stored as `NUMERIC(15,2)` to preserve COBOL `PIC S9(13)V99` precision.
- **Shared Models & Schemas** — [`src/shared/`](./src/shared/) (✅ delivered): SQLAlchemy 2.x ORM models (translated from COBOL copybook record layouts), Pydantic v2 request/response schemas (translated from BMS symbolic maps), and utility modules for date handling, string processing, and decimal arithmetic.
- **Pipeline** — AWS Step Functions (✅ delivered) orchestrates the 5-stage batch pipeline in the exact sequence required by the original JCL: `POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT)`. Stages 4a and 4b run in parallel, mirroring the mainframe architecture. Stage failure halts downstream stages (matching JCL `COND` semantics). The PySpark job scripts that the state machine invokes are 🏗 planned.

For a full architectural deep-dive including sequence diagrams, data flow, and transformation mappings, see [`docs/architecture.md`](./docs/architecture.md).

The original data-model diagram is retained at [`diagrams/CARDDEMO-DataModel.drawio`](./diagrams/CARDDEMO-DataModel.drawio).

<br/>

## Project Structure

The tree below reflects the **target** project layout (per AAP §0.4.1). Items marked 🏗 are planned and not yet present on disk; items marked ✅ are implemented. Items without an annotation are implemented.

```
blitzy-card-demo/
├── src/
│   ├── shared/              ✅ Shared models, schemas, constants, utils, config
│   │   ├── models/          ✅ 11 SQLAlchemy ORM modules (from COBOL copybooks: CVACT01Y, CVACT02Y, …)
│   │   ├── schemas/         ✅ 8 Pydantic request/response modules (from BMS symbolic maps)
│   │   ├── constants/       ✅ messages.py, lookup_codes.py, menu_options.py
│   │   ├── utils/           ✅ date_utils.py, decimal_utils.py, string_utils.py
│   │   └── config/          ✅ settings.py (Pydantic BaseSettings), aws_config.py (AWS client factories)
│   ├── api/                 🏗 FastAPI REST + GraphQL — 18 online CICS programs — service layer planned
│   │   ├── routers/         🏗 REST endpoints (auth, account, card, transaction, bill, report, user, admin)
│   │   ├── services/        🏗 Business logic (from COBOL PROCEDURE DIVISION paragraphs)
│   │   ├── graphql/         🏗 Strawberry GraphQL schema (types, queries, mutations)
│   │   ├── middleware/      🏗 JWT auth, global error handler
│   │   ├── database.py      🏗 SQLAlchemy async engine + session factory
│   │   ├── dependencies.py  🏗 FastAPI dependency-injection helpers
│   │   └── main.py          🏗 FastAPI app entry point (uvicorn target)
│   └── batch/               🏗 PySpark on AWS Glue — 10 batch COBOL programs — job scripts planned
│       ├── common/          🏗 GlueContext factory, JDBC connector, S3 utils
│       ├── jobs/            🏗 posttran_job, intcalc_job, combtran_job, creastmt_job, tranrept_job, prtcatbl_job, daily_tran_driver_job, read_account_job, read_card_job, read_customer_job, read_xref_job
│       └── pipeline/        ✅ step_functions_definition.json — Step Functions state machine
├── db/
│   └── migrations/          ✅ Flyway-style SQL: V1__schema.sql, V2__indexes.sql, V3__seed_data.sql
├── tests/                   🏗 pytest suite (unit, integration, end-to-end) — not yet populated
│   ├── unit/                🏗
│   ├── integration/         🏗
│   └── e2e/                 🏗
├── infra/                   ✅ AWS infrastructure configuration
│   ├── ecs-task-definition.json          ✅
│   ├── glue-job-configs/                 ✅ 5 Glue 5.1 job configs (G.1X, 2 workers)
│   └── cloudwatch/dashboard.json         ✅
├── .github/
│   └── workflows/           ✅ CI/CD: ci.yml, deploy-api.yml, deploy-glue.yml
├── docs/                    ✅ MkDocs site content (index.md, project-guide.md, architecture.md, technical-specifications.md)
├── diagrams/                ✅ Original flow and screen diagrams (retained)
├── samples/                 ✅ Legacy sample JCL (retained for traceability)
├── app/                     ✅ ORIGINAL COBOL / CICS / VSAM / JCL source — retained unchanged for traceability
│   ├── cbl/                 ✅ 28 COBOL programs (10 batch + 18 online)
│   ├── cpy/                 ✅ 28 copybooks
│   ├── bms/                 ✅ 17 BMS mapsets
│   ├── cpy-bms/             ✅ 17 symbolic-map copybooks
│   ├── jcl/                 ✅ 29 JCL job members
│   ├── data/ASCII/          ✅ 9 fixture data files
│   └── catlg/               ✅ IDCAMS catalog report
├── Dockerfile               ✅ Container image for the FastAPI service on ECS Fargate (single-stage, Python 3.11-slim)
├── docker-compose.yml       ✅ Local dev stack (API + PostgreSQL 16 + LocalStack)
├── pyproject.toml           ✅ Python project metadata, ruff, mypy, pytest config
├── requirements*.txt        ✅ Python dependency lists (core / api / glue / dev)
├── mkdocs.yml               ✅ MkDocs documentation tooling
├── catalog-info.yaml        ✅ Backstage catalog descriptor
├── LICENSE                  ✅ Apache License 2.0
├── CONTRIBUTING.md          ✅
├── CODE_OF_CONDUCT.md       ✅
└── README.md                ✅ You are here
```

<br/>

## Prerequisites

To run CardDemo locally or deploy to AWS you need:

- **Python 3.11** (the exact version used by AWS Glue 5.1)
- **Docker** and **Docker Compose v2.x+** (for the local stack)
- **AWS CLI v2** (configured with credentials for any cloud deployment)
- **Git** (for cloning and for GitHub Actions-driven deployment)

No mainframe tooling (HLQ, IDCAMS, DFHCSDUP, CEDA, CEMT) is required — the modernized stack runs entirely on commodity Linux/macOS/Windows hosts and AWS-managed services.

<br/>

## Local development setup

The project ships with a local stack (FastAPI + PostgreSQL 16 + LocalStack) defined in [`docker-compose.yml`](./docker-compose.yml). No real AWS account is required for local development.

> **Checkpoint note:** At this checkpoint the `api` service in `docker-compose.yml` depends on the planned `src/api/main.py` entry point (see [Implementation Status](#implementation-status), row 13) and will therefore fail to start until the FastAPI service layer is delivered. The `postgres` and `localstack` services are fully functional today, which is sufficient for running the database migrations and exercising the shared models via a Python REPL.

```shell
# 1. Clone the repository
git clone <this-repo-url>
cd blitzy-card-demo

# 2. (Optional) Create a Python virtual environment for IDE tooling / local test runs
python3.11 -m venv venv
source venv/bin/activate
pip install -r requirements.txt -r requirements-api.txt -r requirements-dev.txt

# 3. Start the local data services (PostgreSQL + LocalStack)
docker compose up -d postgres localstack

# 4. (When the FastAPI service is delivered) start the full stack
# docker compose up --build
```

`docker compose up` will:

- Start **PostgreSQL 16** with database `carddemo` (user/password `carddemo`/`carddemo`) and apply `db/migrations/V*.sql` via `docker-entrypoint-initdb.d`
- Start **LocalStack** exposing S3, SQS and Secrets Manager on `http://localhost:4566` (AWS service simulators used by the future batch layer and report submission flow)
- Build and start the **FastAPI** application on `http://localhost:8000` *(once the `src/api/main.py` entry point is delivered — see [Implementation Status](#implementation-status))*

<br/>

## Database migrations and seed data

All schema, indexes, and seed data live under [`db/migrations/`](./db/migrations/) and follow a Flyway-style naming convention. Migrations are mounted into PostgreSQL at container-init time via `docker-entrypoint-initdb.d` (see [`docker-compose.yml`](./docker-compose.yml)). For Aurora PostgreSQL they should be executed by Flyway or an equivalent migration runner in your CI/CD pipeline.

| Script                               | Source                                                                                                    | Purpose                                                                                                                                    |
| :----------------------------------- | :-------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------- |
| `db/migrations/V1__schema.sql`       | `app/jcl/ACCTFILE.jcl`, `CARDFILE.jcl`, `CUSTFILE.jcl`, `TRANFILE.jcl`, `XREFFILE.jcl`, `TCATBALF.jcl`, `DUSRSECJ.jcl` | `CREATE TABLE` for all 11 entities (`accounts`, `cards`, `customers`, `card_cross_references`, `transactions`, `transaction_category_balances`, `daily_transactions`, `disclosure_groups`, `transaction_types`, `transaction_categories`, `user_security`) |
| `db/migrations/V2__indexes.sql`      | `app/jcl/TRANIDX.jcl`, `app/catlg/LISTCAT.txt`                                                            | 3 B-tree indexes replacing the 3 VSAM AIX paths (on `cards.acct_id`, `card_cross_references.acct_id`, `transactions.proc_ts`)             |
| `db/migrations/V3__seed_data.sql`    | `app/data/ASCII/*.txt`                                                                                    | Reference and fixture data — **636 rows total** (see breakdown below)                                                                      |

**V3 seed data breakdown (636 rows total):**

| Table                              | Row count |
| :--------------------------------- | --------: |
| `accounts`                         |        50 |
| `cards`                            |        50 |
| `customers`                        |        50 |
| `card_cross_references`            |        50 |
| `transaction_category_balances`    |        50 |
| `disclosure_groups`                |        51 |
| `transaction_categories`           |        18 |
| `transaction_types`                |         7 |
| `user_security`                    |        10 |
| `daily_transactions`               |       300 |
| **Total**                          |   **636** |

To run migrations manually against an existing database:

```shell
psql "$DATABASE_URL_SYNC" -f db/migrations/V1__schema.sql
psql "$DATABASE_URL_SYNC" -f db/migrations/V2__indexes.sql
psql "$DATABASE_URL_SYNC" -f db/migrations/V3__seed_data.sql
```

Seed data preserves the original mainframe fixtures exactly — the default application credentials remain `ADMIN001 / PASSWORD` for admin users and `USER0001 / PASSWORD` for regular users (BCrypt-hashed in the `user_security` table).

<br/>

## Running the API locally

> **Checkpoint note:** The FastAPI application entry point (`src/api/main.py`) is 🏗 planned and is not yet present on disk. The commands below describe the target invocation pattern; they will succeed only after the planned `src/api/main.py` module (per AAP §0.5.1) is delivered by a subsequent implementation pass. Until then, running `uvicorn src.api.main:app` will produce `ModuleNotFoundError: No module named 'src.api.main'`, and `curl http://localhost:8000/health` will refuse the connection.

Once the FastAPI service layer is delivered, the local stack will expose:

- REST API:       http://localhost:8000
- OpenAPI docs:   http://localhost:8000/docs  (auto-generated by FastAPI)
- ReDoc:          http://localhost:8000/redoc
- GraphQL UI:     http://localhost:8000/graphql

To run the API outside of Docker (e.g. against a local PostgreSQL or an Aurora instance):

```shell
source venv/bin/activate
export DATABASE_URL="postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo"
export JWT_SECRET_KEY="dev-secret-key"
uvicorn src.api.main:app --reload --host 0.0.0.0 --port 8000   # requires src/api/main.py (planned)
```

Once delivered, sign on via `POST /auth/login` with `{"userId": "USER0001", "password": "PASSWORD"}` (or `ADMIN001` for admin functions). The response will contain a JWT that must be supplied as `Authorization: Bearer <token>` on subsequent requests — this replaces the CICS COMMAREA session state established by `COSGN00C`.

<br/>

## Running the batch pipeline

The 5-stage batch pipeline is already orchestrated by AWS Step Functions — the state machine (✅ delivered) preserves the exact sequencing of the original JCL architecture:

```
                  ┌───────────────┐
POSTTRAN ──► INTCALC ──► COMBTRAN ──►│  CREASTMT     │  (stage 4a)
                                     │      ∥        │
                                     │  TRANREPT     │  (stage 4b, parallel)
                                     └───────────────┘
```

The individual PySpark scripts that the state machine invokes are 🏗 planned. The table below documents the intended target location of each script and the corresponding COBOL / JCL source that the script will translate.

| Stage | Glue Job                                                  | PySpark Script (🏗 planned)                 | Replaces JCL                 | Replaces COBOL                                      |
| :---- | :-------------------------------------------------------- | :------------------------------------------ | :--------------------------- | :-------------------------------------------------- |
| 1     | Transaction posting (4-stage validation cascade)          | `src/batch/jobs/posttran_job.py`            | `app/jcl/POSTTRAN.jcl`       | `app/cbl/CBTRN02C.cbl`                              |
| 2     | Interest calculation `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` | `src/batch/jobs/intcalc_job.py`             | `app/jcl/INTCALC.jcl`        | `app/cbl/CBACT04C.cbl`                              |
| 3     | Transaction merge/sort (replaces DFSORT + REPRO)          | `src/batch/jobs/combtran_job.py`            | `app/jcl/COMBTRAN.jcl`       | *(pure sort — no COBOL source)*                     |
| 4a    | Statement generation (text + HTML, 4-entity join)         | `src/batch/jobs/creastmt_job.py`            | `app/jcl/CREASTMT.jcl`       | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`      |
| 4b    | Transaction report with 3-level totals                    | `src/batch/jobs/tranrept_job.py`            | `app/jcl/TRANREPT.jcl`       | `app/cbl/CBTRN03C.cbl`                              |

Orchestration is defined in [`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json). A stage failure routes the execution to the `PipelineFailed` state and halts all downstream stages, matching the JCL `COND=(0,NE)` semantics of the original architecture.

Once the PySpark jobs are delivered, an individual job can be executed locally (for development / debugging against your local PostgreSQL) as follows:

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

In production the jobs will be executed as AWS Glue 5.1 jobs (G.1X workers, configured in [`infra/glue-job-configs/`](./infra/glue-job-configs/)) and triggered by the Step Functions state machine.

<br/>

## Deployment

CI/CD is automated through GitHub Actions. Three workflows cover the entire delivery pipeline:

| Workflow                                                            | Trigger                                     | Purpose                                                                                      |
| :------------------------------------------------------------------ | :------------------------------------------ | :------------------------------------------------------------------------------------------- |
| [`.github/workflows/ci.yml`](./.github/workflows/ci.yml)            | Every push / PR                             | Lint (`ruff`), type-check (`mypy`), unit + integration tests (`pytest`), coverage reporting  |
| [`.github/workflows/deploy-api.yml`](./.github/workflows/deploy-api.yml) | Push to `main` / manual dispatch            | Build Docker image → push to **ECR** → update **ECS Fargate** service                        |
| [`.github/workflows/deploy-glue.yml`](./.github/workflows/deploy-glue.yml) | Push to `main` / manual dispatch            | Upload PySpark scripts to **S3** → update **AWS Glue** job definitions → update **Step Functions** state machine |

> **Checkpoint note:** `ci.yml` invokes `pytest` across `tests/unit/`, `tests/integration/`, and `tests/e2e/` with a coverage gate of `--cov-fail-under=80`. Because no test files exist at this checkpoint (see [Implementation Status](#implementation-status), row 15), a green CI run depends on the pytest suite being delivered by a subsequent implementation pass. Similarly, `deploy-glue.yml` iterates over 11 Glue job names; five Glue-job configurations already exist in [`infra/glue-job-configs/`](./infra/glue-job-configs/), but the matching PySpark scripts under `src/batch/jobs/` are 🏗 planned.

**API deployment flow:** `docker build` → `docker push <account>.dkr.ecr.<region>.amazonaws.com/carddemo-api` → `aws ecs update-service --force-new-deployment`. The ECS task definition template lives at [`infra/ecs-task-definition.json`](./infra/ecs-task-definition.json).

**Batch deployment flow:** `aws s3 sync src/batch/ s3://<glue-script-bucket>/carddemo/batch/` → `aws glue update-job` for each of the 5 pipeline jobs (configs under [`infra/glue-job-configs/`](./infra/glue-job-configs/)) → `aws stepfunctions update-state-machine` using [`src/batch/pipeline/step_functions_definition.json`](./src/batch/pipeline/step_functions_definition.json).

**Database deployment flow:** Run Flyway migrations (`db/migrations/V*.sql`) against the target Aurora PostgreSQL cluster. Credentials are retrieved at runtime from **AWS Secrets Manager** — no database passwords are stored in the codebase or in task definitions.

A unified CloudWatch dashboard template is provided at [`infra/cloudwatch/dashboard.json`](./infra/cloudwatch/dashboard.json) covering Glue job duration/DPU/error-rate metrics and ECS CPU/memory/request-count/error-rate metrics.

<br/>

## Environment configuration

Runtime configuration is driven by environment variables loaded via Pydantic `BaseSettings` — see [`src/shared/config/settings.py`](./src/shared/config/settings.py) for the authoritative definitions. The table below enumerates every field declared on the `Settings` class (17 total) with its default value and purpose.

| Variable                                  | Default (Settings class)                  | Purpose                                                                   | Example (local dev)                                              |
| :---------------------------------------- | :---------------------------------------- | :------------------------------------------------------------------------ | :--------------------------------------------------------------- |
| `DATABASE_URL`                            | *(required — no default)*                 | Async SQLAlchemy URL used by FastAPI                                      | `postgresql+asyncpg://carddemo:carddemo@postgres:5432/carddemo`  |
| `DATABASE_URL_SYNC`                       | *(required — no default)*                 | Sync SQLAlchemy URL for Alembic / migrations                              | `postgresql+psycopg2://carddemo:carddemo@postgres:5432/carddemo` |
| `DB_SECRET_NAME`                          | `carddemo/aurora-credentials`             | AWS Secrets Manager secret ID for Aurora credentials                      | `carddemo/aurora-credentials`                                    |
| `DB_POOL_SIZE`                            | `10`                                      | SQLAlchemy engine pool size                                               | `10`                                                             |
| `DB_MAX_OVERFLOW`                         | `20`                                      | SQLAlchemy engine max pool overflow                                       | `20`                                                             |
| `JWT_SECRET_KEY`                          | *(required — no default)*                 | HS256 signing key for JWT tokens                                          | retrieved from Secrets Manager in production                     |
| `JWT_ALGORITHM`                           | `HS256`                                   | Token algorithm                                                           | `HS256`                                                          |
| `JWT_ACCESS_TOKEN_EXPIRE_MINUTES`         | `30`                                      | Access-token TTL. `docker-compose.yml` overrides to `60` for local dev.   | `30` (production) / `60` (compose override)                      |
| `AWS_REGION` (aliased from `AWS_DEFAULT_REGION`) | `us-east-1`                         | AWS region for SQS, S3, Secrets Manager                                   | `us-east-1`                                                      |
| `S3_BUCKET_NAME`                          | `carddemo-data`                           | S3 bucket for statement, report, reject output                            | `carddemo-data`                                                  |
| `SQS_QUEUE_URL`                           | `""`                                      | URL of the report submission SQS FIFO queue                               | `https://sqs.us-east-1.amazonaws.com/.../carddemo-report-queue.fifo` |
| `GLUE_JOB_ROLE_ARN`                       | `""`                                      | IAM role assumed by Glue jobs                                             | `arn:aws:iam::...:role/carddemo-glue-role`                       |
| `AWS_ENDPOINT_URL`                        | `""`                                      | **Local dev only** — LocalStack endpoint                                  | `http://localstack:4566`                                         |
| `LOG_LEVEL`                               | `INFO`                                    | Structured JSON log level                                                 | `INFO`                                                           |
| `APP_NAME`                                | `carddemo`                                | Application name used in logs / tracing                                   | `carddemo`                                                       |
| `APP_VERSION`                             | `1.0.0`                                   | Application version string                                                | `1.0.0`                                                          |
| `DEBUG`                                   | `False`                                   | Enable debug mode                                                         | `False`                                                          |

> **Note on `ENVIRONMENT`:** `docker-compose.yml` sets an `ENVIRONMENT=development` variable for convenience, but `ENVIRONMENT` is **not** a field on the `Settings` class — Pydantic's `extra="ignore"` policy silently drops it. It is therefore available in the container's environment (and usable by shell scripts or third-party tooling) but does not affect any Python-level behaviour. If you need environment-conditional code, either add the field to `Settings` or read `os.environ["ENVIRONMENT"]` directly.

In production the ECS task should obtain database credentials and the JWT signing key via **AWS Secrets Manager** (retrieved using the ECS task IAM role — no AWS access keys on disk) and the AWS region from the task metadata.

<br/>

## Testing

> **Checkpoint note:** The `tests/` directory is scaffolded with empty package `__init__.py` files but contains zero test modules at this checkpoint. `pytest --collect-only` reports `collected 0 items`, and the `--cov-fail-under=80` gate configured in [`pyproject.toml`](./pyproject.toml) will therefore fail until tests are authored by a subsequent implementation pass (see [Implementation Status](#implementation-status), row 15). The commands below describe the target invocation pattern once the tests are delivered.

The test suite will use **pytest** with async support and live under [`tests/`](./tests/):

- `tests/unit/` — fast unit tests for models, services, routers, and PySpark transformations
- `tests/integration/` — database-backed tests using `testcontainers[postgres]` and API tests using `httpx.AsyncClient` against a running FastAPI app
- `tests/e2e/` — end-to-end batch pipeline tests exercising the full POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT) flow

Run the full suite (after tests are authored):

```shell
source venv/bin/activate
pytest                                  # full suite with coverage (fails below 80%)
pytest tests/unit/                      # unit tests only
pytest tests/unit/test_services/        # a targeted subset
pytest -m "not slow"                    # skip long-running tests
```

AWS services are mocked via **moto** (S3, SQS, Secrets Manager, Glue). Lint and type-check run today against the shared foundation layer:

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

Regular users can only perform user functions and admin users can only perform admin functions. All 22 features (`F-001` through `F-022`) are documented in the AAP and are targeted for the modernized implementation; the shared domain foundation is in place today and the API / batch service layers are 🏗 planned (see [Implementation Status](#implementation-status)).

<br/>

### User Functions

![User Flow](./diagrams/Application-Flow-User.png?raw=true "User Flow")

*Diagram depicts the original CICS transaction flow; the modernized application will expose the same flow as a REST/GraphQL API once the service layer is delivered.*

<br/>

### Admin Functions

![Admin Flow](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

*Diagram depicts the original CICS transaction flow; the modernized application will expose the same flow as a REST/GraphQL API once the service layer is delivered.*

<br/>

### Application Inventory

#### **Online (REST / GraphQL APIs)**

The 18 online CICS COBOL programs are targeted for translation to REST endpoints (plus equivalent GraphQL queries/mutations). The table below cross-references each legacy transaction/program to its **planned** modern REST endpoint and target Python module. Paths marked 🏗 do not exist on disk at this checkpoint.

| Legacy Trans | Legacy Program | REST Endpoint (🏗 planned)                   | Target Python Module (🏗 planned)                      | Feature |
| :----------- | :------------- | :------------------------------------------- | :----------------------------------------------------- | :------ |
| CC00         | COSGN00C       | `POST /auth/login`, `POST /auth/logout`      | `src/api/routers/auth_router.py`                       | F-001   |
| CM00         | COMEN01C       | `GET /menu`                                  | `src/api/main.py` — menu constants already live in [`src/shared/constants/menu_options.py`](./src/shared/constants/menu_options.py) | F-002   |
| CA00         | COADM01C       | `GET /admin/menu`                            | `src/api/routers/admin_router.py`                      | F-003   |
| CAVW         | COACTVWC       | `GET /accounts/{id}`                         | `src/api/routers/account_router.py`                    | F-004   |
| CAUP         | COACTUPC       | `PUT /accounts/{id}`                         | `src/api/routers/account_router.py`                    | F-005   |
| CCLI         | COCRDLIC       | `GET /cards`                                 | `src/api/routers/card_router.py`                       | F-006   |
| CCDL         | COCRDSLC       | `GET /cards/{id}`                            | `src/api/routers/card_router.py`                       | F-007   |
| CCUP         | COCRDUPC       | `PUT /cards/{id}`                            | `src/api/routers/card_router.py`                       | F-008   |
| CT00         | COTRN00C       | `GET /transactions`                          | `src/api/routers/transaction_router.py`                | F-009   |
| CT01         | COTRN01C       | `GET /transactions/{id}`                     | `src/api/routers/transaction_router.py`                | F-010   |
| CT02         | COTRN02C       | `POST /transactions`                         | `src/api/routers/transaction_router.py`                | F-011   |
| CB00         | COBIL00C       | `POST /bills/pay`                            | `src/api/routers/bill_router.py`                       | F-012   |
| CR00         | CORPT00C       | `POST /reports/submit`                       | `src/api/routers/report_router.py`                     | F-022   |
| CU00         | COUSR00C       | `GET /users`                                 | `src/api/routers/user_router.py`                       | F-018   |
| CU01         | COUSR01C       | `POST /users`                                | `src/api/routers/user_router.py`                       | F-019   |
| CU02         | COUSR02C       | `PUT /users/{id}`                            | `src/api/routers/user_router.py`                       | F-020   |
| CU03         | COUSR03C       | `DELETE /users/{id}`                         | `src/api/routers/user_router.py`                       | F-021   |
| *(shared)*   | CSUTLDTC       | *(used internally for date validation)*      | [`src/shared/utils/date_utils.py`](./src/shared/utils/date_utils.py) ✅ | shared  |

All endpoints are planned to also be exposed as GraphQL queries/mutations via Strawberry — target module `src/api/graphql/schema.py` (🏗 planned).

#### **Batch (PySpark on AWS Glue)**

The 10 batch COBOL programs are targeted for translation to PySpark jobs. The original file-provisioning JCL (`ACCTFILE`, `CARDFILE`, etc.) has been replaced by the `db/migrations/V*.sql` scripts against Aurora PostgreSQL (✅ delivered); the original CICS file-management JCL (`OPENFIL`, `CLOSEFIL`) has no equivalent in the target architecture.

| Legacy JCL              | Legacy Program     | Glue Job                              | PySpark Script (🏗 planned)                           | Function                                                        |
| :---------------------- | :----------------- | :------------------------------------ | :---------------------------------------------------- | :-------------------------------------------------------------- |
| `app/jcl/POSTTRAN.jcl`  | `CBTRN02C`         | `carddemo-posttran`                   | `src/batch/jobs/posttran_job.py`                      | Stage 1 — Transaction posting (4-stage validation cascade)      |
| `app/jcl/INTCALC.jcl`   | `CBACT04C`         | `carddemo-intcalc`                    | `src/batch/jobs/intcalc_job.py`                       | Stage 2 — Interest calculation                                  |
| `app/jcl/COMBTRAN.jcl`  | *(DFSORT+REPRO)*   | `carddemo-combtran`                   | `src/batch/jobs/combtran_job.py`                      | Stage 3 — Transaction merge/sort                                |
| `app/jcl/CREASTMT.jcl`  | `CBSTM03A/B`       | `carddemo-creastmt`                   | `src/batch/jobs/creastmt_job.py`                      | Stage 4a — Statement generation                                 |
| `app/jcl/TRANREPT.jcl`  | `CBTRN03C`         | `carddemo-tranrept`                   | `src/batch/jobs/tranrept_job.py`                      | Stage 4b — Transaction report (3-level totals)                  |
| `app/jcl/PRTCATBL.jcl`  | *(util)*           | `carddemo-prtcatbl`                   | `src/batch/jobs/prtcatbl_job.py`                      | Print transaction category balance                              |
| *(daily driver)*        | `CBTRN01C`         | `carddemo-daily-tran-driver`          | `src/batch/jobs/daily_tran_driver_job.py`             | Daily transaction ingest driver                                 |
| `app/jcl/READACCT.jcl`  | `CBACT01C`         | `carddemo-read-account`               | `src/batch/jobs/read_account_job.py`                  | Account diagnostic reader                                       |
| `app/jcl/READCARD.jcl`  | `CBACT02C`         | `carddemo-read-card`                  | `src/batch/jobs/read_card_job.py`                     | Card diagnostic reader                                          |
| `app/jcl/READXREF.jcl`  | `CBACT03C`         | `carddemo-read-xref`                  | `src/batch/jobs/read_xref_job.py`                     | Cross-reference diagnostic reader                               |
| `app/jcl/READCUST.jcl`  | `CBCUS01C`         | `carddemo-read-customer`              | `src/batch/jobs/read_customer_job.py`                 | Customer diagnostic reader                                      |

Legacy file-provisioning JCL (`ACCTFILE`, `CARDFILE`, `CUSTFILE`, `TRANFILE`, `XREFFILE`, `TCATBALF`, `DISCGRP`, `TRANCATG`, `TRANTYPE`, `DUSRSECJ`, `DEFCUST`, `TRANIDX`, `DEFGDGB`, `REPTFILE`, `DALYREJS`, `TRANBKP`) is replaced by [`db/migrations/V1__schema.sql`](./db/migrations/V1__schema.sql), [`db/migrations/V2__indexes.sql`](./db/migrations/V2__indexes.sql), [`db/migrations/V3__seed_data.sql`](./db/migrations/V3__seed_data.sql), and AWS S3 bucket configuration for GDG-equivalent output.

Legacy CICS file-management JCL (`OPENFIL.jcl`, `CLOSEFIL.jcl`, `CBADMCDJ.jcl`) has **no equivalent** in the cloud-native target — database connections are pooled by SQLAlchemy on demand and no CICS region exists.

<br/>

### Application Screens (historical reference)

The modernized application is **headless** — it will expose only REST and GraphQL APIs and does not render BMS screens. The diagrams below depict the **original CICS screens** for historical reference. The REST endpoints that are targeted to replace each screen are documented in the [Online inventory table](#online-rest--graphql-apis) above; once delivered, interactive API documentation will be auto-generated at `/docs` (Swagger UI), `/redoc` (ReDoc), and `/graphql` (GraphQL playground).

#### **Signon Screen**

![Signon Screen](./diagrams/Signon-Screen.png?raw=true "Signon Screen")

*Targeted replacement:* `POST /auth/login` *in `src/api/routers/auth_router.py` (🏗 planned)*

#### **Main Menu**

![Main Menu](./diagrams/Main-Menu.png?raw=true "Main Menu")

*Targeted replacement:* `GET /menu` *— menu options are already defined in [`src/shared/constants/menu_options.py`](./src/shared/constants/menu_options.py); endpoint handler is 🏗 planned in `src/api/main.py`*

#### **Admin Menu**

![Admin Menu](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

*Targeted replacement:* `GET /admin/menu` *in `src/api/routers/admin_router.py` (🏗 planned)*

<br/>

## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The modernization is an **in-progress** effort. The immediate delivery backlog is:

1. **FastAPI service layer** — `src/api/main.py`, `database.py`, `dependencies.py`, the `middleware/` package (JWT auth + error handler), the 8 routers, the 7 service modules, and the `graphql/` package. Targets the 18 online CICS programs. See [Implementation Status](#implementation-status), row 13.
2. **PySpark batch jobs** — 11 scripts under `src/batch/jobs/` (posttran, intcalc, combtran, creastmt, tranrept, prtcatbl, daily-tran-driver, 4 diagnostic readers) plus `src/batch/common/` helpers (GlueContext factory, JDBC connector, S3 utils). See [Implementation Status](#implementation-status), row 14.
3. **pytest suite** — unit, integration, and e2e coverage targeting parity with the original COBOL test scenarios; `conftest.py` with shared fixtures. See [Implementation Status](#implementation-status), row 15.
4. **Supplementary documentation** — `DECISION_LOG.md`, `TRACEABILITY_MATRIX.md`, `docs/api-contracts.md`, `docs/onboarding-guide.md`, `docs/validation-gates.md`. See [Implementation Status](#implementation-status), row 16.

Longer-term enhancements (planned beyond the core modernization):

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

We welcome contributions to the modernized codebase. Whether you're implementing the planned FastAPI service layer, authoring PySpark job scripts, adding test coverage, refining documentation, or exploring new cloud-native patterns, please feel free to raise issues and pull requests. See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for the contributor workflow, testing expectations, and commit-message conventions.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license. See [`LICENSE`](./LICENSE) for the full text.

<br/>

## Project status

**Migration in progress.** The shared domain foundation (SQLAlchemy models, Pydantic schemas, constants, utilities, Pydantic `BaseSettings` + AWS client factories), the Aurora PostgreSQL schema and seed data (V1–V3 migrations, 11 tables, 3 indexes, 636 rows), the AWS infrastructure scaffolding (ECS task template, 5 Glue 5.1 job configs, CloudWatch dashboard), the Step Functions state machine, the local-dev container stack, and the three GitHub Actions workflows are in place. The FastAPI online service layer (`src/api/**`), the PySpark batch jobs (`src/batch/jobs/**`), and the pytest test suite (`tests/**`) are 🏗 planned — see [Implementation Status](#implementation-status) for the authoritative checkpoint-level breakdown and [`docs/project-guide.md`](./docs/project-guide.md) for the delivery plan.

The original mainframe source tree under `app/` is retained unchanged for traceability and remains available as a reference for the ongoing translation work.

<br/>
