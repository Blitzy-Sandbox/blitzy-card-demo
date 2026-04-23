# CardDemo COBOL-to-Python Migration — Blitzy Project Guide

<!--
Project guide for the CardDemo COBOL-to-Python modernization project.

Target stack per AAP §0.1.1:
  - Online workload: FastAPI (REST + Strawberry GraphQL) on AWS ECS Fargate
  - Batch workload: PySpark on AWS Glue 5.1 (Spark 3.5.6, Python 3.11)
  - Database:       AWS Aurora PostgreSQL (Aurora-compatible PostgreSQL 16 locally)
  - Supporting:     AWS S3 (GDG replacement), AWS SQS FIFO (TDQ replacement),
                    AWS Secrets Manager, AWS CloudWatch, AWS Step Functions

This document describes the **current checkpoint state** of the migration.
It deliberately distinguishes between what is already implemented in the
repository at this checkpoint and what remains planned per AAP §0.5.1.
-->

---

## 1. Executive Summary

### 1.1 Project Overview

The CardDemo COBOL-to-Python migration is an in-progress modernization that
takes the mainframe credit-card management application (28 COBOL programs,
28 copybooks, 17 BMS mapsets, 17 symbolic map copybooks, 29 JCL job members,
9 ASCII fixture files) and re-platforms it onto a Python / AWS cloud-native
stack. The target architecture, documented in
[`docs/architecture.md`](architecture.md), splits the original monolithic
CICS/JCL workload into two independent workload types:

- **Online workload** — FastAPI (Uvicorn / Python 3.11) serving REST + GraphQL
  endpoints from a Docker container on **AWS ECS Fargate**, replacing the 18
  online CICS COBOL programs and the CICS COMMAREA session with stateless
  JWT authentication.
- **Batch workload** — PySpark scripts executed as serverless **AWS Glue 5.1**
  jobs (Spark 3.5.6, Python 3.11), replacing the 10 batch COBOL programs
  and the JCL `COND` chaining with an **AWS Step Functions** state machine.

Both workloads share a single **Aurora PostgreSQL** database (11 tables
replacing 10 VSAM KSDS datasets and 3 alternate-index paths) and a common
Python domain model under `src/shared/` (SQLAlchemy 2.x async ORM models,
Pydantic v2 schemas, constants, utilities, configuration).

### 1.2 Implementation Status at This Checkpoint

The migration is being delivered in iterative checkpoints. The table below
reflects the **verified filesystem state at this checkpoint** — each row was
confirmed against the repository by counting files on disk rather than by
copy-pasting from prior narratives.

| Workstream | Status | Evidence on Disk |
|------------|--------|------------------|
| Shared domain models (SQLAlchemy ORM) | ✅ Implemented | 11 files under `src/shared/models/` (account, card, card_cross_reference, customer, daily_transaction, disclosure_group, transaction, transaction_category, transaction_category_balance, transaction_type, user_security) |
| Shared Pydantic v2 request/response schemas | ✅ Implemented | 8 files under `src/shared/schemas/` (account, auth, bill, card, customer, report, transaction, user) |
| Shared constants (from copybooks) | ✅ Implemented | 3 files under `src/shared/constants/` (lookup_codes, menu_options, messages) |
| Shared utilities | ✅ Implemented | 3 files under `src/shared/utils/` (date_utils, decimal_utils, string_utils) |
| Shared configuration | ✅ Implemented | 2 files under `src/shared/config/` (settings, aws_config) |
| Aurora PostgreSQL SQL migrations | ✅ Implemented | `db/migrations/V1__schema.sql` (11 CREATE TABLE), `V2__indexes.sql` (3 indexes), `V3__seed_data.sql` (636 rows) |
| AWS Glue job configurations | ✅ Implemented | 5 files under `infra/glue-job-configs/` (posttran, intcalc, combtran, creastmt, tranrept) — all pinned at `GlueVersion 5.1` |
| AWS ECS task definition | ✅ Implemented | `infra/ecs-task-definition.json` |
| CloudWatch dashboard template | ✅ Implemented | `infra/cloudwatch/dashboard.json` |
| Step Functions state machine definition | ✅ Implemented | `src/batch/pipeline/step_functions_definition.json` (Stage 1 → 2 → 3 → Parallel(4a, 4b), with `PipelineFailed` error routing) |
| GitHub Actions workflows | ✅ Implemented | `.github/workflows/ci.yml`, `deploy-api.yml`, `deploy-glue.yml` |
| Dockerfile (API container) | ✅ Implemented | Single-stage `python:3.11-slim` image with FastAPI/Uvicorn entry-point |
| Docker Compose stack (local dev) | ✅ Implemented | `docker-compose.yml` (api + postgres:16-alpine + localstack) |
| Root project metadata | ✅ Implemented | `pyproject.toml`, `requirements.txt`, `requirements-api.txt`, `requirements-glue.txt`, `requirements-dev.txt` |
| FastAPI application entry point (`src/api/main.py`) | 🏗 Planned | Only `src/api/__init__.py` exists at this checkpoint |
| FastAPI routers (`src/api/routers/*.py`) | 🏗 Planned | Directory not yet created |
| FastAPI services (`src/api/services/*.py`) | 🏗 Planned | Directory not yet created |
| FastAPI middleware (`src/api/middleware/*.py`) | 🏗 Planned | Directory not yet created |
| FastAPI dependencies / database session (`src/api/dependencies.py`, `database.py`) | 🏗 Planned | Files not yet created |
| Strawberry GraphQL schema (`src/api/graphql/**`) | 🏗 Planned | Only `src/api/graphql/__init__.py` exists |
| PySpark Glue job scripts (`src/batch/jobs/*.py`) | 🏗 Planned | Only `src/batch/jobs/__init__.py` exists; no job scripts yet |
| Batch common helpers (`src/batch/common/*.py`) | 🏗 Planned | Directory not yet created |
| Automated test suite (`tests/**`) | 🏗 Planned | Only `__init__.py` shell files; no `conftest.py`, no test modules, no fixtures |

All rows marked 🏗 Planned correspond to target files listed in
AAP §0.5.1 and are described in
[`docs/architecture.md`](architecture.md) §8 (Project Structure Reference)
and §9 (Feature Coverage Summary).

### 1.3 Accomplishments at This Checkpoint

The accomplishments below are scoped to work actually present in the
repository at this checkpoint:

- **All 28 COBOL copybooks required by the AAP** were translated into the
  shared Python domain model under `src/shared/`. Each Python source file
  carries a header comment referencing its originating COBOL copybook (see
  AAP §0.7.3 and [`docs/architecture.md`](architecture.md) §10 on
  traceability).
- **11 SQLAlchemy ORM models** were created from the VSAM record layouts
  (`app/cpy/CVACT01Y.cpy`, `CVACT02Y.cpy`, `CVACT03Y.cpy`, `CVCUS01Y.cpy`,
  `CVTRA01Y.cpy`, `CVTRA02Y.cpy`, `CVTRA03Y.cpy`, `CVTRA04Y.cpy`,
  `CVTRA05Y.cpy`, `CVTRA06Y.cpy`, `CSUSR01Y.cpy`). Monetary fields map
  Python `decimal.Decimal` to DDL `NUMERIC(p, 2)` columns whose precision
  matches the originating COBOL `PIC S9(n)V99` clause exactly
  (`NUMERIC(12, 2)` for `PIC S9(10)V99` account balance/limit fields,
  `NUMERIC(11, 2)` for `PIC S9(09)V99` transaction amounts, `NUMERIC(6, 2)`
  for the `PIC S9(04)V99` disclosure interest rate). The ORM widens most
  monetary attributes to `Numeric(15, 2)` for arithmetic headroom and
  mirrors the DDL `Numeric(6, 2)` for `disclosure_group.int_rate`. See
  [`docs/architecture.md`](architecture.md) §3.3 for the full per-field
  precision table.
- **Composite primary keys** (TransactionCategoryBalance, DisclosureGroup,
  TransactionCategory) are declared on the SQLAlchemy models exactly as in
  the COBOL copybooks.
- **8 Pydantic v2 schemas** translate the BMS symbolic map copybooks
  (`app/cpy-bms/*.CPY`) into REST/GraphQL request/response contracts.
- **Shared constants** translate message copybooks (`CSMSG01Y.cpy`,
  `CSMSG02Y.cpy`, `COTTL01Y.cpy`), lookup-code copybook (`CSLKPCDY.cpy`),
  and menu-option copybooks (`COMEN02Y.cpy`, `COADM02Y.cpy`) into Python
  modules.
- **Shared utilities** translate COBOL date (`CSUTLDTC.cbl`, `CSDAT01Y.cpy`,
  `CSUTLDWY.cpy`, `CSUTLDPY.cpy`) and string (`CSSTRPFY.cpy`) helpers into
  Python modules; a `decimal_utils` helper centralizes `Decimal`
  quantization semantics.
- **Database migration scripts** provision the 11 Aurora PostgreSQL tables
  (V1), the 3 B-tree indexes (V2) that replace the VSAM alternate indexes
  (`card.acct_id`, `card_cross_reference.acct_id`, `transaction.proc_ts`),
  and 636 seed rows (V3).
- **AWS infrastructure templates** (ECS task definition, 5 Glue job configs
  pinned at Glue 5.1, CloudWatch dashboard) and the 3 GitHub Actions
  workflows are in place.
- **Step Functions state machine** enforces the 5-stage batch pipeline
  ordering (`POSTTRAN → INTCALC → COMBTRAN → Parallel(CREASTMT, TRANREPT)`)
  with a `PipelineFailed` error-routing state that preserves the
  `COND=(0,NE)` semantics of the original JCL.
- **Local-development stack** (Docker Compose) starts Aurora-compatible
  PostgreSQL 16, LocalStack 3 (S3 + SQS + Secrets Manager), and the API
  container behind port 8000 (container port 80). The API container cannot
  serve requests yet because `src/api/main.py` is planned but not
  implemented.

### 1.4 Known Gaps at This Checkpoint

The following items are tracked as planned work per AAP §0.5.1 and are
described in [`docs/architecture.md`](architecture.md):

1. **FastAPI service layer is not yet implemented.** `src/api/` contains
   only `__init__.py` files. Starting the API container or running
   `uvicorn src.api.main:app` will fail with `ModuleNotFoundError`. All
   router/service/middleware/database modules listed in AAP §0.5.1 are
   pending.
2. **PySpark Glue job scripts are not yet implemented.** `src/batch/jobs/`
   contains only `__init__.py`. The Step Functions state-machine
   definition and Glue job configs assume these scripts will be uploaded
   to S3 by the `deploy-glue.yml` workflow; until the scripts exist, the
   deployment workflow will fail at the upload step.
3. **Automated test suite is not yet implemented.** `tests/` contains
   only empty package-init files. Running `pytest` collects zero tests;
   the `--cov-fail-under=80` gate in `pyproject.toml` cannot be exercised
   until tests are added.
4. **Supplementary documentation is not yet written.** `DECISION_LOG.md`,
   `TRACEABILITY_MATRIX.md`, `docs/api-contracts.md`,
   `docs/onboarding-guide.md`, and `docs/validation-gates.md` are planned
   artifacts that do not exist at this checkpoint. Only `README.md`,
   `docs/index.md`, `docs/architecture.md`, `docs/project-guide.md`
   (this file), and `docs/technical-specifications.md` are present.

### 1.5 Recommended Next Steps

1. Implement the `src/api/` tree (main, dependencies, database, middleware,
   routers, services, GraphQL schema) starting with the `/health` endpoint
   used by the Dockerfile `HEALTHCHECK` and the `src/api/middleware/auth.py`
   JWT validator required by every other router.
2. Implement the `src/batch/common/` helpers (GlueContext factory, JDBC
   connector via Secrets Manager, S3 utilities) and the 5 core Glue job
   scripts (`posttran_job.py`, `intcalc_job.py`, `combtran_job.py`,
   `creastmt_job.py`, `tranrept_job.py`) referenced by the Step Functions
   state machine.
3. Create `tests/conftest.py` with the shared fixtures (PostgreSQL
   testcontainer, FastAPI `TestClient`, moto AWS mocks) and populate
   `tests/unit/`, `tests/integration/`, and `tests/e2e/` so the
   `--cov-fail-under=80` gate can be exercised.
4. Re-validate the three GitHub Actions workflows end-to-end once the
   source tree and tests are in place.
5. Externalize `JWT_SECRET_KEY` and database credentials via AWS Secrets
   Manager using `src/shared/config/aws_config.py`.

---

## 2. Scope Alignment With the AAP

| AAP Section | Expectation | Status at Checkpoint |
|-------------|-------------|----------------------|
| §0.1.1 Batch COBOL → PySpark on AWS Glue | 10 batch programs become PySpark scripts | 🏗 Planned — Glue job configs and Step Functions definition present; PySpark scripts pending |
| §0.1.1 Online CICS COBOL → REST/GraphQL on AWS ECS | 18 online programs become FastAPI endpoints | 🏗 Planned — FastAPI scaffolding pending |
| §0.1.1 Database: AWS Aurora PostgreSQL | 10 VSAM + 3 AIX → 11 tables + 3 B-tree indexes | ✅ Implemented via `db/migrations/V1__schema.sql` and `V2__indexes.sql` |
| §0.1.1 Deployment: GitHub Actions | CI + two deployment workflows | ✅ 3 workflows implemented; full execution depends on `src/api/` and `src/batch/jobs/` being completed |
| §0.4.4 AWS Glue 5.1 | All Glue jobs run on Glue 5.1 (Spark 3.5.6, Python 3.11) | ✅ All 5 `infra/glue-job-configs/*.json` pin `GlueVersion 5.1` |
| §0.5.1 `docs/index.md` UPDATE | Landing page describes Python/AWS target | ✅ Rewritten for Python/AWS stack |
| §0.5.1 `docs/architecture.md` CREATE | New architecture document | ✅ Present; status annotations mark planned modules |
| §0.5.1 `docs/project-guide.md` UPDATE | Python/AWS-focused project guide | ✅ This document |
| §0.5.1 `README.md` UPDATE | Setup, run, deploy, inventory | ✅ Updated to describe the Python/AWS stack; planned modules clearly annotated |
| §0.7.1 Minimal change clause | Preserve existing COBOL source tree | ✅ `app/` retained unchanged |
| §0.7.2 Security — Secrets Manager, IAM, BCrypt, JWT | Security configuration and dependencies in place | ✅ `requirements-api.txt` pins `passlib[bcrypt]` and `python-jose[cryptography]>=3.4.0,<4.0`; planned modules will wire them up |
| §0.7.2 Financial precision (`decimal.Decimal` / `NUMERIC(p, 2)` matching COBOL `PIC S9(n)V99`) | No float substitution anywhere; DDL precision matches COBOL PIC | ✅ Verified in `src/shared/models/*.py` (Python `decimal.Decimal`, `Numeric(15, 2)` for most monetary fields, `Numeric(6, 2)` for interest rate) and `db/migrations/V1__schema.sql` (`NUMERIC(12, 2)` for `PIC S9(10)V99`, `NUMERIC(11, 2)` for `PIC S9(09)V99`, `NUMERIC(6, 2)` for `PIC S9(04)V99`) |
| §0.7.2 Automated testing as much as possible | pytest framework configured | 🏗 Pending — `pyproject.toml` configured; test modules not yet written |

---

## 3. Test Coverage Status

At this checkpoint, `tests/` contains only empty `__init__.py` package-init
files under `tests/`, `tests/unit/`, `tests/integration/`, `tests/e2e/`,
`tests/unit/test_models/`, `tests/unit/test_services/`,
`tests/unit/test_routers/`, and `tests/unit/test_batch/`. No
`tests/conftest.py` exists, and **no test modules have been written**.

Running `pytest` at this checkpoint collects **0 items** and therefore:

- The `--cov-fail-under=80` gate configured in `pyproject.toml` cannot yet
  be exercised.
- The `pytest-cov` HTML coverage report cannot be generated until at least
  one test file exists.

Once the planned `src/api/` and `src/batch/` modules are delivered, the
corresponding unit, integration, and end-to-end tests described in
AAP §0.5.1 (Test Suite) will be added under `tests/unit/`,
`tests/integration/`, and `tests/e2e/`. The CI workflow
(`.github/workflows/ci.yml`) is already wired to invoke
`pytest --cov=src --cov-report=term-missing --cov-fail-under=80`, so test
results and coverage will be enforced automatically once the test suite
is in place.

---

## 4. Runtime Validation Status

The runtime entry points documented below require the planned
`src/api/main.py` and its routers (see §1.2). They are **not currently
reachable** because those modules have not been implemented yet.

| Endpoint | Source Program (COBOL) | Status | Notes |
|----------|------------------------|--------|-------|
| `POST /auth/login` | `app/cbl/COSGN00C.cbl` | 🏗 Planned | Will live in `src/api/routers/auth_router.py` with `src/api/services/auth_service.py` invoking `passlib[bcrypt]` |
| `GET /accounts/{id}` | `app/cbl/COACTVWC.cbl` | 🏗 Planned | Will perform the 3-entity join (`Account` × `Customer` × `CardCrossReference`) |
| `PUT /accounts/{id}` | `app/cbl/COACTUPC.cbl` | 🏗 Planned | Will preserve the dual-write + `async with session.begin()` SYNCPOINT rollback semantics |
| `GET /cards` | `app/cbl/COCRDLIC.cbl` | 🏗 Planned | Paginated list (7 rows/page) matching COCRDLIC browse semantics |
| `GET /cards/{id}` | `app/cbl/COCRDSLC.cbl` | 🏗 Planned | Card detail view |
| `PUT /cards/{id}` | `app/cbl/COCRDUPC.cbl` | 🏗 Planned | Optimistic concurrency via SQLAlchemy `version_id_col` |
| `GET /transactions` | `app/cbl/COTRN00C.cbl` | 🏗 Planned | Paginated list (10 rows/page) |
| `GET /transactions/{id}` | `app/cbl/COTRN01C.cbl` | 🏗 Planned | Transaction detail |
| `POST /transactions` | `app/cbl/COTRN02C.cbl` | 🏗 Planned | Auto-ID generation and card cross-reference resolution |
| `POST /bills/pay` | `app/cbl/COBIL00C.cbl` | 🏗 Planned | Dual-write (Transaction INSERT + Account balance UPDATE) |
| `POST /reports/submit` | `app/cbl/CORPT00C.cbl` | 🏗 Planned | Publishes to SQS FIFO queue (TDQ replacement) |
| `GET /users`, `POST /users`, `PUT /users/{id}`, `DELETE /users/{id}` | `app/cbl/COUSR0{0..3}C.cbl` | 🏗 Planned | Full CRUD with BCrypt hashing |
| `GET /menu` | `app/cbl/COMEN01C.cbl`, `app/cbl/COADM01C.cbl` | 🏗 Planned | Main/admin menu navigation; exact path will be finalized in the router implementation |
| `GET /health` | n/a | 🏗 Planned | Used by the `Dockerfile` `HEALTHCHECK` and by Docker Compose |
| `GET /docs`, `GET /redoc`, `POST /graphql` | FastAPI built-ins, Strawberry | 🏗 Planned | Available automatically once `src/api/main.py` mounts the routers and the Strawberry schema |

### Database Migrations

Database migrations (`db/migrations/V1-V3`) are in place and are
auto-applied by the PostgreSQL 16 container on first startup via the
`/docker-entrypoint-initdb.d/` mount declared in `docker-compose.yml`.
Counts verified against the SQL source files:

| Migration | Artifact | Count |
|-----------|----------|-------|
| V1 | `CREATE TABLE` statements | 11 |
| V2 | `CREATE INDEX` statements | 3 |
| V3 | Seed rows inserted | 636 |

### Batch Pipeline Verification

The Step Functions state machine in
`src/batch/pipeline/step_functions_definition.json` defines the 5-stage
pipeline ordering:

```
Stage1_PostTran → Stage2_IntCalc → Stage3_CombTran → Stage4_Parallel(Stage4a_CreAStmt, Stage4b_TranRept) → PipelineComplete
```

Each stage's failure path routes to `PipelineFailed`, preserving the
`COND=(0,NE)` semantics of the original JCL. End-to-end execution
requires the planned PySpark scripts under `src/batch/jobs/` to be
deployed to S3 by the `deploy-glue.yml` workflow.

---

## 5. Compliance Snapshot

| AAP Requirement | Status at Checkpoint | Evidence |
|-----------------|----------------------|----------|
| 11 Aurora PostgreSQL tables replacing 10 VSAM + 3 AIX | ✅ | `db/migrations/V1__schema.sql` defines all 11 tables; `V2__indexes.sql` defines 3 B-tree indexes |
| 636 seed rows loaded from 9 ASCII fixture files | ✅ | `db/migrations/V3__seed_data.sql` — 50 accounts, 50 cards, 50 customers, 50 card_cross_references, 50 transaction_category_balances, 51 disclosure_groups, 18 transaction_categories, 7 transaction_types, 10 user_security, 300 daily_transactions |
| `decimal.Decimal` across all monetary fields (no `float`) | ✅ | Verified in 11 SQLAlchemy models under `src/shared/models/` and 8 Pydantic schemas under `src/shared/schemas/`; database columns use `NUMERIC(12, 2)` for account fields (from `PIC S9(10)V99`), `NUMERIC(11, 2)` for transaction amounts (from `PIC S9(09)V99`), and `NUMERIC(6, 2)` for the disclosure interest rate (from `PIC S9(04)V99`) — matching each COBOL `PIC S9(n)V99` clause exactly |
| SQLAlchemy composite primary keys on `TransactionCategoryBalance`, `DisclosureGroup`, `TransactionCategory` | ✅ | Verified in `src/shared/models/transaction_category_balance.py`, `disclosure_group.py`, `transaction_category.py` |
| SQLAlchemy `version_id_col` optimistic concurrency on `Account`, `Card` (COACTUPC, COCRDUPC semantics) | ✅ | Declared in the respective model classes; the planned service modules will exercise these on update |
| BCrypt password hashing (C-003) | ⏳ Dependency installed | `passlib[bcrypt]==1.7.4` and `bcrypt>=4.2,<5.0` pinned in `requirements-api.txt`; the auth service that uses them is planned |
| JWT stateless authentication (replaces CICS COMMAREA) | ⏳ Dependency installed | `python-jose[cryptography]>=3.4.0,<4.0` pinned in `requirements-api.txt` (`>=3.4.0` required to avoid CVE-2024-33663 and CVE-2024-33664); the middleware that uses it is planned |
| AWS SQS FIFO for TDQ replacement | ⏳ Configured | `Settings.SQS_QUEUE_URL` exposed; `boto3` SQS client constructed in `src/shared/config/aws_config.py`; the report service that publishes to it is planned |
| AWS S3 for GDG replacement | ⏳ Configured | `Settings.S3_BUCKET_NAME` default `carddemo-data`; `boto3` S3 client constructed in `src/shared/config/aws_config.py`; the PySpark jobs that read/write S3 are planned |
| AWS Secrets Manager for database credentials | ⏳ Configured | `Settings.DB_SECRET_NAME` default `carddemo/aurora-credentials`; resolution wired in `aws_config.py` |
| AWS Step Functions preserving JCL COND semantics | ✅ | `src/batch/pipeline/step_functions_definition.json` — `PipelineFailed` error state on every stage |
| AWS Glue 5.1 (Spark 3.5.6, Python 3.11) | ✅ | All 5 `infra/glue-job-configs/*.json` pin `"GlueVersion": "5.1"` |
| AWS ECS Fargate container | ✅ Scaffolded | `Dockerfile` (single-stage `python:3.11-slim`, port 80), `infra/ecs-task-definition.json` |
| GitHub Actions CI/CD | ✅ Scaffolded | `.github/workflows/ci.yml`, `deploy-api.yml`, `deploy-glue.yml` |
| Ruff / Mypy static checks | ✅ Configured | `ruff check src/` and `mypy --strict src/` pass with zero violations against the 27 implemented source files under `src/shared/` |
| pytest test framework | ✅ Configured | `pyproject.toml` sets `--cov=src --cov-fail-under=80`; test modules are planned (see §1.4) |
| FastAPI OpenAPI docs (`/docs`, `/redoc`) | 🏗 Planned | Enabled automatically by FastAPI once `src/api/main.py` is implemented |
| Strawberry GraphQL | 🏗 Planned | Dependency pinned in `requirements-api.txt`; schema implementation pending |
| CloudWatch dashboard template | ✅ | `infra/cloudwatch/dashboard.json` |
| Structured JSON logging with correlation IDs | 🏗 Planned | Logging configuration will be wired in `src/api/main.py` |

Legend: ✅ implemented / ⏳ dependency or configuration present but consuming
module is planned / 🏗 planned per AAP §0.5.1 but not yet implemented.

---

## 6. Risk Register

| Risk | Category | Severity | Mitigation |
|------|----------|----------|------------|
| `src/api/` not yet implemented — API container cannot serve requests | Functional | High | Deliver `src/api/main.py`, routers, services, middleware, and `database.py` per AAP §0.5.1; wire `/health` endpoint before other routers so Docker Compose `HEALTHCHECK` passes |
| `src/batch/jobs/*.py` not yet implemented — batch pipeline cannot execute end-to-end | Functional | High | Deliver the 5 core PySpark jobs (`posttran_job`, `intcalc_job`, `combtran_job`, `creastmt_job`, `tranrept_job`) and the `src/batch/common/` helpers; `deploy-glue.yml` uploads them to S3 and updates the Glue job definitions |
| `tests/` contains no test modules — `--cov-fail-under=80` gate cannot yet be enforced | Quality | High | Create `tests/conftest.py` with the PostgreSQL testcontainer, FastAPI `TestClient`, and moto AWS mock fixtures; add `tests/unit/`, `tests/integration/`, `tests/e2e/` modules alongside each delivered service |
| `JWT_SECRET_KEY` provided as an environment variable in `docker-compose.yml` | Security | High | Source `JWT_SECRET_KEY` and database credentials via AWS Secrets Manager in production; `src/shared/config/aws_config.py` already exposes the Secrets Manager client |
| `python-jose` must remain at `>=3.4.0` to avoid CVE-2024-33663 and CVE-2024-33664 | Security | High | `requirements-api.txt` pins `>=3.4.0,<4.0`; verify periodically with `pip-audit` or `safety check` |
| Production TLS termination not yet configured | Security | Medium | Terminate TLS at the ALB in front of ECS Fargate; ECS task exposes only HTTP on container port 80 |
| Rate limiting not yet applied at the API edge | Security | Medium | Add a FastAPI middleware (e.g. `slowapi`) in `src/api/middleware/` once the service layer is in place |
| LocalStack-only AWS testing | Integration | Medium | Add end-to-end tests against a real AWS staging environment after `src/api/` and `src/batch/jobs/` are implemented |
| Production data migration from EBCDIC | Operational | Medium | Use AWS DMS (or a PySpark ingestion job) to move production EBCDIC data into Aurora PostgreSQL with validation before cutover |
| Database connection pooling defaults (`DB_POOL_SIZE=10`, `DB_MAX_OVERFLOW=20`) may require tuning | Technical | Low | Adjust via environment variables once real-traffic profiles are collected in staging |
| FastAPI OpenAPI docs not yet published | Documentation | Low | FastAPI exposes `/docs` and `/redoc` automatically once `src/api/main.py` is in place |

---

## 7. Project Outlook

The migration is being delivered iteratively. Future checkpoints are
expected to address the planned work in this order:

1. **API service core** — `src/api/main.py`, `dependencies.py`, `database.py`,
   middleware, `/health`, `/auth/login`.
2. **Domain routers/services** — account, card, transaction, bill, report,
   user, menu routers and corresponding service modules.
3. **GraphQL mount** — Strawberry schema, types, queries, mutations under
   `src/api/graphql/`.
4. **Batch common helpers** — `src/batch/common/glue_context.py`,
   `db_connector.py`, `s3_utils.py`.
5. **Core batch jobs** — `posttran_job.py`, `intcalc_job.py`,
   `combtran_job.py`, `creastmt_job.py`, `tranrept_job.py`.
6. **Diagnostic and driver batch jobs** — `daily_tran_driver_job.py`,
   `prtcatbl_job.py`, `read_account_job.py`, `read_card_job.py`,
   `read_customer_job.py`, `read_xref_job.py`.
7. **Automated tests** — `tests/conftest.py` plus unit, integration, and
   end-to-end test modules covering every delivered service/job.
8. **Supplementary documentation** — decision log, traceability matrix,
   API contracts, onboarding guide, validation-gate evidence.

---

## 8. Summary

At this checkpoint the CardDemo migration has delivered the shared domain
model, the Aurora PostgreSQL schema and seed data, the AWS infrastructure
configuration, the Step Functions pipeline definition, the local Docker
Compose stack, and the GitHub Actions scaffolding. The FastAPI service
layer, the PySpark Glue job scripts, and the automated test suite are
planned per AAP §0.5.1 and have not yet been implemented. The
[`docs/architecture.md`](architecture.md) document specifies the exact
target module layout, AWS service topology, and design patterns for the
remaining work.

---

## 9. Development Guide

### 9.1 System Prerequisites

| Software | Version | Purpose |
|----------|---------|---------|
| Python | 3.11 (CPython) | Application runtime (aligned with AWS Glue 5.1) |
| Docker | 20.x+ | Container runtime for PostgreSQL, LocalStack, and the API service |
| Docker Compose | v2.x+ (Docker Compose v2 plugin) | Multi-service orchestration |
| Git | 2.x+ | Version control |
| AWS CLI | v2 | Glue job registration, ECS service updates, Secrets Manager interaction |

### 9.2 Environment Setup

**1. Clone the repository and enter the project directory:**
```bash
git clone <repository-url>
cd carddemo
```

**2. Verify Python 3.11:**
```bash
python3 --version
# Expected: Python 3.11.x
```

If Python 3.11 is not the default, install it via `pyenv` or the system
package manager, then create and activate a virtual environment:
```bash
python3.11 -m venv venv
source venv/bin/activate
export PYTHONPATH=$PWD
```

**3. Start local infrastructure:**
```bash
# LocalStack auth token (required only for Pro features)
export LOCALSTACK_AUTH_TOKEN=<your-token>

# Start the API container, PostgreSQL, and LocalStack from docker-compose.yml
docker compose up -d
```

Verify services are healthy:
```bash
# PostgreSQL
docker compose exec postgres pg_isready -U carddemo
# Expected: accepting connections

# LocalStack
curl -s http://localhost:4566/_localstack/health | python3 -m json.tool
# Expected: services shown with "available" for s3, sqs, secretsmanager
```

Note that the `api` service will start but its readiness depends on
`src/api/main.py` being implemented (planned — see §1.4). The
`postgres` and `localstack` services are fully functional at this
checkpoint.

### 9.3 Dependency Installation

```bash
# Install core + API + dev dependencies into the active venv
pip install -r requirements.txt -r requirements-api.txt -r requirements-dev.txt

# Batch layer dependencies (PySpark, pg8000) — install only if you need
# to run PySpark locally; these are heavier and not required for API work
pip install -r requirements-glue.txt
```

### 9.4 Static Checks

At this checkpoint, `ruff` and `mypy` run against the 27 source files
under `src/shared/`:

```bash
ruff check src/
mypy --strict src/
```

Both checks are clean at this checkpoint. The same commands will run
over the full `src/` tree as additional modules are delivered.

### 9.5 Application Startup

Starting the API server requires the planned `src/api/main.py` (see
§1.4). Once it is in place:

```bash
uvicorn src.api.main:app --host 0.0.0.0 --port 8000 --reload
```

Planned verification endpoints (once `src/api/main.py` exists):

```bash
# Health check (used by Dockerfile HEALTHCHECK)
curl -s http://localhost:8000/health

# Authentication (default local credentials — see docker-compose seed data)
curl -s -X POST http://localhost:8000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}'

# OpenAPI UI
open http://localhost:8000/docs

# ReDoc
open http://localhost:8000/redoc

# GraphQL endpoint
open http://localhost:8000/graphql
```

### 9.6 Observability Access

| Service | URL | Credentials |
|---------|-----|-------------|
| Application Health | `http://localhost:8000/health` (planned) | N/A |
| FastAPI OpenAPI Docs | `http://localhost:8000/docs` (planned) | N/A |
| FastAPI ReDoc | `http://localhost:8000/redoc` (planned) | N/A |
| LocalStack Health | `http://localhost:4566/_localstack/health` | N/A |
| CloudWatch Dashboard (prod) | AWS Console → CloudWatch → Dashboards → `CardDemo` (defined in `infra/cloudwatch/dashboard.json`) | IAM |

### 9.7 Troubleshooting

| Issue | Resolution |
|-------|-----------|
| `ModuleNotFoundError: No module named 'src.api.main'` | Expected at this checkpoint — `src/api/main.py` is planned but not yet implemented (see §1.4). |
| `python: command not found` or wrong version | Install Python 3.11 via `pyenv install 3.11` or the system package manager; activate the venv: `source venv/bin/activate`. |
| Docker Compose port conflicts | Check for existing services: `lsof -i :5432`, `lsof -i :4566`, `lsof -i :8000`. |
| LocalStack initialization fails | Verify `LOCALSTACK_AUTH_TOKEN` is set (optional for community edition) and the LocalStack container health check passes. |
| SQL migration fails on PostgreSQL startup | Ensure PostgreSQL is healthy: `docker compose exec postgres pg_isready -U carddemo` and that the migration files are mounted into `/docker-entrypoint-initdb.d/`. |
| Testcontainers connection refused | Ensure the Docker daemon is running and your user has access to the Docker socket. |
| `ModuleNotFoundError: src.*` when running scripts directly | Set `PYTHONPATH`: `export PYTHONPATH=$PWD` from the project root. |

---

## 10. Appendices

### Appendix A. Command Reference

| Command | Purpose | Availability at This Checkpoint |
|---------|---------|----------------------------------|
| `ruff check src/` | Lint all Python source files | ✅ Works against `src/shared/` |
| `mypy --strict src/` | Strict static type-check | ✅ Works against `src/shared/` |
| `pytest` / `pytest --cov=src` | Run tests with coverage | 🏗 Collects 0 items until `tests/` is populated |
| `uvicorn src.api.main:app --reload` | Start FastAPI application | 🏗 Requires planned `src/api/main.py` |
| `docker compose up -d` | Start all infrastructure services | ✅ Starts PostgreSQL + LocalStack; API container starts but fails to serve until `src/api/main.py` exists |
| `docker compose down -v` | Stop services and remove volumes | ✅ |
| `docker compose logs -f postgres` | Tail PostgreSQL logs | ✅ |
| `docker compose exec postgres psql -U carddemo -d carddemo` | Connect to the local PostgreSQL instance | ✅ |
| `spark-submit src/batch/jobs/<job>.py` | Run a PySpark Glue job locally | 🏗 Requires planned job scripts |

### Appendix B. Port Reference

| Port | Service | Protocol |
|------|---------|----------|
| 5432 | Aurora-compatible PostgreSQL 16 (Docker) | TCP |
| 4566 | LocalStack (S3, SQS, Secrets Manager) | HTTP |
| 8000 → 80 | CardDemo FastAPI API (host → container) | HTTP |

### Appendix C. Key File Locations

| File / Directory | Purpose | Status |
|------------------|---------|--------|
| `pyproject.toml` | Python project metadata; ruff / mypy / pytest config | ✅ |
| `requirements.txt` | Core shared dependencies (boto3, pydantic, python-dotenv) | ✅ |
| `requirements-api.txt` | FastAPI layer dependencies (FastAPI, SQLAlchemy, Strawberry, python-jose ≥3.4.0, passlib, asyncpg) | ✅ |
| `requirements-glue.txt` | PySpark batch layer dependencies (pyspark 3.5.6, pg8000) | ✅ |
| `requirements-dev.txt` | Development and testing dependencies (pytest, moto, testcontainers, ruff, mypy) | ✅ |
| `src/shared/config/settings.py` | Pydantic v2 `BaseSettings` — central configuration | ✅ |
| `.env` (local only, not committed) | Local development environment variable overrides | ⚪ Optional |
| `db/migrations/V1__schema.sql` | Aurora PostgreSQL schema (11 tables) | ✅ |
| `db/migrations/V2__indexes.sql` | B-tree indexes (replacing VSAM AIX paths) | ✅ |
| `db/migrations/V3__seed_data.sql` | Seed data (636 rows) from COBOL fixtures | ✅ |
| `docker-compose.yml` | Local infrastructure (API, PostgreSQL 16, LocalStack 3) | ✅ |
| `Dockerfile` | API service container (single-stage `python:3.11-slim`) | ✅ |
| `.github/workflows/ci.yml` | CI pipeline: lint → type-check → unit → integration → coverage gate | ✅ |
| `.github/workflows/deploy-api.yml` | API deployment: build → ECR → ECS service update | ✅ |
| `.github/workflows/deploy-glue.yml` | Glue deployment: upload PySpark to S3 → update Glue job definitions | ✅ |
| `infra/ecs-task-definition.json` | ECS Fargate task definition | ✅ |
| `infra/glue-job-configs/*.json` | 5 Glue job configurations (posttran, intcalc, combtran, creastmt, tranrept) | ✅ |
| `infra/cloudwatch/dashboard.json` | CloudWatch unified monitoring dashboard | ✅ |
| `src/batch/pipeline/step_functions_definition.json` | AWS Step Functions state machine for the 5-stage pipeline | ✅ |
| `docs/index.md` | Documentation landing page | ✅ |
| `docs/architecture.md` | Architecture guide (Mermaid diagrams, project structure, feature coverage) | ✅ |
| `docs/project-guide.md` | This document | ✅ |
| `docs/technical-specifications.md` | Feature catalog (F-001 through F-022), business rules carried forward from the mainframe baseline | ✅ |
| `app/` | Original COBOL / BMS / JCL / copybook / fixture sources (retained unchanged per AAP §0.7.1) | ✅ |
| `tests/conftest.py` | Shared pytest fixtures (PostgreSQL testcontainer, FastAPI TestClient, moto AWS mocks) | 🏗 Planned |
| `src/api/main.py` and all `routers/`, `services/`, `middleware/`, `graphql/` modules | FastAPI application and service layer | 🏗 Planned |
| `src/batch/jobs/*.py`, `src/batch/common/*.py` | PySpark Glue job scripts and common helpers | 🏗 Planned |

### Appendix D. Technology Versions

| Technology | Version | Notes |
|-----------|---------|-------|
| Python (CPython) | 3.11 | Aligned with AWS Glue 5.1 runtime |
| FastAPI | 0.115.x | Web framework (REST + GraphQL via Strawberry) |
| SQLAlchemy | 2.0.x | Async ORM for Aurora PostgreSQL |
| asyncpg | 0.30.x | Async PostgreSQL driver for SQLAlchemy |
| psycopg2-binary | 2.9.x | Sync PostgreSQL driver for migrations and seed scripts |
| Alembic | 1.14.x | Alternative migration tool (SQL scripts used in this checkpoint) |
| Strawberry GraphQL | 0.254.x | GraphQL schema for FastAPI |
| PySpark (on AWS Glue 5.1) | 3.5.6 | Spark 3.5.6, Scala 2.12.18, Python 3.11 runtime |
| passlib[bcrypt] | 1.7.4 | BCrypt password hashing |
| bcrypt | 4.2.x | Pure Python BCrypt backend for passlib |
| python-jose[cryptography] | ≥3.4.0,<4.0 | JWT encoding / decoding. `>=3.4.0` required to pick up the fixes for CVE-2024-33663 and CVE-2024-33664. |
| pydantic | 2.10.x | Data validation (Pydantic v2 with Rust-backed core) |
| pydantic-settings | 2.7.x | Environment-variable management for `BaseSettings` |
| PostgreSQL | 16 (Alpine) — Aurora-compatible | Aurora PostgreSQL in production; PostgreSQL 16 in Docker locally |
| SQL migration scripts | `db/migrations/V1-V3` | Auto-applied on PostgreSQL 16 container start via `/docker-entrypoint-initdb.d/` |
| boto3 | 1.35.x | AWS SDK (Secrets Manager, SQS, S3) |
| testcontainers[postgres] | 4.8.x | PostgreSQL container fixtures for integration tests |
| moto[all] | 5.0.x | AWS service mocks (S3, SQS, Secrets Manager, Glue) |
| pytest | 8.3.x | Test framework |
| pytest-asyncio | 0.24.x | Async test support for FastAPI |
| pytest-cov | 6.0.x | Code coverage reporting |
| Ruff | 0.8.x | Linter and formatter |
| mypy | 1.13.x | Static type checking |
| Docker | 20.x+ | Container runtime |
| Docker Compose | v2.x+ | Multi-service orchestration |
| LocalStack (community or Pro) | 3.x | AWS service emulation for local development |

### Appendix E. Environment Variable Reference

The variables below correspond to the fields declared on
`src.shared.config.settings.Settings` and the environment variables set in
`docker-compose.yml`. `Settings` uses `extra="ignore"`, so any unrecognized
environment variable is silently dropped.

| Variable | Recognized by `Settings` | Default | Purpose |
|----------|:------------------------:|---------|---------|
| `DATABASE_URL` | ✅ | None (required) | Async PostgreSQL connection string, e.g. `postgresql+asyncpg://carddemo:carddemo@postgres:5432/carddemo` |
| `DATABASE_URL_SYNC` | ✅ | None (required) | Sync PostgreSQL connection string, e.g. `postgresql+psycopg2://carddemo:carddemo@postgres:5432/carddemo` |
| `DB_SECRET_NAME` | ✅ | `carddemo/aurora-credentials` | AWS Secrets Manager secret identifier for Aurora credentials in production |
| `DB_POOL_SIZE` | ✅ | `10` | SQLAlchemy connection-pool size |
| `DB_MAX_OVERFLOW` | ✅ | `20` | SQLAlchemy connection-pool overflow limit |
| `JWT_SECRET_KEY` | ✅ | None (required) | Signing key for JWT tokens (replaces CICS COMMAREA). Use AWS Secrets Manager in production. |
| `JWT_ALGORITHM` | ✅ | `HS256` | JWT signing algorithm |
| `JWT_ACCESS_TOKEN_EXPIRE_MINUTES` | ✅ | `30` | JWT access-token lifetime in minutes. `docker-compose.yml` overrides this to `60` for local development. |
| `AWS_REGION` (aliased from `AWS_DEFAULT_REGION`) | ✅ | `us-east-1` | AWS region for all service clients |
| `S3_BUCKET_NAME` | ✅ | `carddemo-data` | S3 bucket (statement / report / reject output; GDG replacement) |
| `SQS_QUEUE_URL` | ✅ | `` (empty) | SQS FIFO queue URL (report submission; TDQ replacement) |
| `GLUE_JOB_ROLE_ARN` | ✅ | `` (empty) | IAM role ARN used by AWS Glue jobs |
| `AWS_ENDPOINT_URL` | ✅ | `` (empty) | LocalStack endpoint for local development (e.g. `http://localstack:4566`); unset in production to use real AWS |
| `LOG_LEVEL` | ✅ | `INFO` | Python logging level (`DEBUG`, `INFO`, `WARNING`, `ERROR`) |
| `APP_NAME` | ✅ | `carddemo` | Application name used in logs and metrics |
| `APP_VERSION` | ✅ | `1.0.0` | Application version stamped onto logs and metrics |
| `DEBUG` | ✅ | `False` | Debug mode; when `True` FastAPI exposes stack traces and SQLAlchemy echoes SQL. **Must be `False` in production.** |
| `AWS_ACCESS_KEY_ID` | ⚪ Consumed by `boto3` directly | `test` (local) | AWS access key (LocalStack: any value; production: IAM role–based) |
| `AWS_SECRET_ACCESS_KEY` | ⚪ Consumed by `boto3` directly | `test` (local) | AWS secret key (LocalStack: any value; production: IAM role–based) |
| `POSTGRES_DB` | ⚪ Consumed by the Postgres container, not by `Settings` | `carddemo` | PostgreSQL database name inside the container |
| `POSTGRES_USER` | ⚪ Consumed by the Postgres container, not by `Settings` | `carddemo` | PostgreSQL username inside the container |
| `POSTGRES_PASSWORD` | ⚪ Consumed by the Postgres container, not by `Settings` | `carddemo` | PostgreSQL password inside the container |
| `LOCALSTACK_AUTH_TOKEN` | ⚪ Consumed by LocalStack Pro | None | LocalStack Pro authentication |
| `ENVIRONMENT` | ❌ Not a `Settings` field (silently ignored) | `development` in `docker-compose.yml` | Deployment marker used only by external tooling / CI at this checkpoint |

### Appendix F. Developer Tools Guide

Once the planned test suite exists:

**Run a specific test module:**
```bash
pytest tests/unit/test_services/test_account_service.py -v
```

**Run integration tests only:**
```bash
pytest tests/integration/ -v
```

**Generate an HTML coverage report:**
```bash
pytest --cov=src --cov-report=html
# Report at: htmlcov/index.html
```

Independently of the test suite:

**Build the API Docker image:**
```bash
docker build -t carddemo:latest .
```

**Inspect the Glue job configuration for a stage:**
```bash
cat infra/glue-job-configs/posttran.json
```

**Inspect the Step Functions state-machine definition:**
```bash
python3 -m json.tool src/batch/pipeline/step_functions_definition.json
```

### Appendix G. Glossary

| Term | Definition |
|------|-----------|
| VSAM KSDS | Virtual Storage Access Method — Key-Sequenced Data Set (COBOL file type → Aurora PostgreSQL table) |
| BMS | Basic Mapping Support — CICS 3270 screen definitions (→ REST API contracts / Pydantic schemas) |
| COMMAREA | Communication Area — CICS inter-program data passing (→ JWT token state) |
| TDQ | Transient Data Queue — CICS message queue (→ AWS SQS) |
| GDG | Generation Data Group — Versioned dataset generations (→ S3 versioned objects) |
| COMP-3 | Packed decimal storage — COBOL numeric type (→ Python `decimal.Decimal`) |
| SYNCPOINT | CICS transaction commit/rollback point (→ SQLAlchemy session context managers with rollback on exception) |
| FILE STATUS | COBOL I/O result code (→ Python exception hierarchy) |
| POSTTRAN | Daily transaction posting batch job (Stage 1 of the 5-stage pipeline) |
| INTCALC | Interest calculation batch job (Stage 2) |
| COMBTRAN | Transaction combine/sort batch job (Stage 3 — DFSORT replacement) |
| CREASTMT | Statement generation batch job (Stage 4a) |
| TRANREPT | Transaction report batch job (Stage 4b — runs in parallel with 4a) |
| AIX | Alternate Index — VSAM secondary-key path (→ PostgreSQL B-tree index) |
| CICS | Customer Information Control System — the transaction server that hosted the 18 online COBOL programs (→ AWS ECS Fargate + FastAPI) |
| JCL | Job Control Language — the z/OS batch job descriptor (→ AWS Step Functions + Glue job configs) |
