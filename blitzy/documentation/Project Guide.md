# Blitzy Project Guide — CardDemo COBOL-to-Python Cloud-Native Modernization

> **Branch:** `blitzy-66b0591b-4769-4d22-9576-8fe54b2c16ad`
> **Source tree retained:** `app/` (28 COBOL programs, 28 copybooks, 17 BMS mapsets, 17 symbolic maps, 29 JCL jobs, 9 fixture files) — unchanged, for traceability
> **Target stack:** Python 3.11 / FastAPI / PySpark on AWS Glue 5.1 / Aurora PostgreSQL / AWS ECS Fargate / AWS Step Functions / GitHub Actions

---

## 1. Executive Summary

### 1.1 Project Overview

CardDemo is a credit-card management application originally implemented as a tightly-coupled z/OS mainframe workload (28 COBOL programs, CICS transactions, VSAM KSDS datasets, JES2/JCL batch pipeline, BMS screens). This project refactors the entire application into a Python 3.11 cloud-native stack on AWS, splitting it into two distinct workloads — a containerized FastAPI REST+GraphQL service on ECS Fargate for the 18 online CICS programs, and PySpark jobs on AWS Glue 5.1 orchestrated by AWS Step Functions for the 10 batch programs. All 10 VSAM datasets and 3 alternate-index paths migrate to 11 Aurora PostgreSQL tables. The refactor preserves all 22 features (F-001–F-022) with full behavioral parity, including financial precision (`PIC S9(n)V99` → Python `Decimal`), the 4-stage transaction-validation cascade, optimistic concurrency, and dual-write atomicity.

### 1.2 Completion Status

```mermaid
pie title CardDemo Modernization — 90% Complete
    "Completed Work (1,260h)" : 1260
    "Remaining Work (140h)" : 140
```

| Metric | Value |
|---|---|
| **Total Project Hours** | **1,400** |
| **Completed Hours (AI + Manual)** | **1,260** |
| **Remaining Hours** | **140** |
| **Completion Percentage** | **90.0%** |

*Colors: Completed = Dark Blue (#5B39F3); Remaining = White (#FFFFFF). Completion percentage computed as 1,260 ÷ 1,400 × 100 = 90.0% per PA1 hours methodology.*

### 1.3 Key Accomplishments

- [x] **All 28 COBOL programs translated** — 10 batch programs to PySpark Glue jobs + 18 online CICS programs to FastAPI REST/GraphQL endpoints (146 Python files total across `src/`)
- [x] **11 SQLAlchemy ORM models** created from VSAM copybook record layouts (CVACT01Y–CVCUS01Y, CSUSR01Y) with NUMERIC precision matching every COBOL `PIC S9(n)V99` clause
- [x] **9 Pydantic v2 schema modules** derived from BMS symbolic-map copybooks (17 mapsets covered by per-feature schema modules)
- [x] **Aurora PostgreSQL schema delivered** — 3 Flyway-style SQL migrations: V1 (11 tables), V2 (3 B-tree indexes replacing VSAM AIX paths), V3 (636 seed rows from the 9 fixture files)
- [x] **Full 5-stage batch pipeline** orchestrated by an AWS Step Functions state machine (`src/batch/pipeline/step_functions_definition.json`) with `POSTTRAN → INTCALC → COMBTRAN → parallel(CREASTMT, TRANREPT)` and JCL-`COND`-equivalent failure-halts-downstream semantics
- [x] **JWT-based stateless authentication** with BCrypt password hashing (F-001) replacing CICS COMMAREA sessions
- [x] **Strawberry GraphQL schema** with 4 types (Account, Card, Transaction, User), 8 queries, and mutations co-mounted with REST on the same FastAPI app
- [x] **AWS infrastructure templates** — 5 Glue job configs (Glue 5.1, G.1X, 2 workers), 1 ECS Fargate task definition, 1 unified CloudWatch dashboard
- [x] **3 GitHub Actions workflows** — `ci.yml` (lint + type-check + unit + integration tests), `deploy-api.yml` (ECR push → ECS service update → health check), `deploy-glue.yml` (S3 script upload → Glue job update → Step Functions update)
- [x] **Dockerfile optimized** from 501 MB → 270 MB during QA Checkpoint 8 (build-time packages purged in the same `RUN` directive)
- [x] **1,252 automated tests** (1,104 unit + 95 integration + 53 e2e) passing at **81.66% coverage** — above the 80% `--cov-fail-under` gate
- [x] **10+ QA Checkpoint findings resolved** — CP1 (security/config), CP2 (routes), CP3, CP4 (architectural), CP5 (24 findings: 11 CRITICAL + 11 MAJOR + 2 MINOR), CP6 (8 security findings), CP7 (tests + coverage), CP8 (Docker), CP10 (GraphQL concurrency + pageSize cap)
- [x] **Clean static quality** — `ruff check`: all passed; `ruff format --check`: 146 files already formatted; `mypy --strict src/`: no issues in 87 source files

### 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|---|---|---|---|
| Stale `🏗 Planned` markers in README.md (19 occurrences), docs/architecture.md (36 occurrences), docs/project-guide.md (34 occurrences) — docs describe `src/api/`, `src/batch/jobs/`, and `tests/` as unfinished when they are fully delivered | Documentation misleads new developers and stakeholders about current state; does not affect runtime | Human developer | 1 day |
| AWS Infrastructure-as-Code (Terraform/CloudFormation/CDK) not present — only application-layer JSON configs (Glue job, ECS task, CloudWatch dashboard, Step Functions ASL) exist | Cannot deploy to AWS until IaC written to provision ECS cluster, Aurora cluster, IAM roles, VPC, ALB, Secrets Manager, SQS FIFO queue, S3 buckets | DevOps | 1 week |
| Real AWS integration validation pending — all AWS integrations (Secrets Manager, SQS, S3, CloudWatch) exercised only via `moto` mocks | Cannot certify production behavior of AWS SDK calls against live services | DevOps / QA | 2 days |
| Production secrets not yet provisioned in AWS Secrets Manager (DB creds, JWT signing key) | Production deployment will fail without secrets; currently only placeholder env vars | DevOps | 1 day |
| ALB + DNS + SSL/TLS certificate configuration pending | No public HTTPS endpoint available to clients | DevOps | 1 day |

### 1.5 Access Issues

| System/Resource | Type of Access | Issue Description | Resolution Status | Owner |
|---|---|---|---|---|
| AWS Account | IAM / Programmatic | CI/CD GitHub Actions secrets (`AWS_ROLE_ARN` or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`) not yet configured in the GitHub repo settings; `.github/workflows/deploy-api.yml` and `deploy-glue.yml` reference `${{ secrets.AWS_ROLE_ARN }}` which is unset | 🔴 Unresolved — required before first deploy | DevOps |
| Aurora PostgreSQL (prod) | DB connection / Secrets Manager | No production cluster endpoint; local validation uses PostgreSQL 16 at `localhost:5432/carddemo` with `carddemo:carddemo` dev credentials | 🔴 Unresolved — prod cluster + secret must be provisioned | DevOps |
| AWS ECR | Container push | ECR repository for the `carddemo-api` image has not been created; `deploy-api.yml` assumes `$ECR_REGISTRY/carddemo-api:$IMAGE_TAG` | 🔴 Unresolved — create ECR repo | DevOps |
| AWS S3 (script bucket) | Object write | `deploy-glue.yml` uploads PySpark scripts to `s3://$GLUE_SCRIPT_BUCKET/` — bucket not yet created | 🔴 Unresolved — create and bucket-policy | DevOps |
| AWS S3 (output bucket — GDG replacement) | Object write | Statement / report / reject output buckets for CREASTMT, TRANREPT, POSTTRAN not yet created | 🔴 Unresolved — create versioned buckets | DevOps |
| AWS SQS FIFO | Queue URL | Report submission queue (TDQ replacement) not yet created; `src/api/services/report_service.py` references `settings.SQS_REPORT_QUEUE_URL` | 🔴 Unresolved — create `carddemo-reports.fifo` queue | DevOps |

### 1.6 Recommended Next Steps

1. **[High]** Stand up AWS Infrastructure-as-Code (Terraform preferred) — VPC, subnets, ECS cluster, Aurora PostgreSQL cluster, IAM roles, Secrets Manager secrets, SQS FIFO queue, S3 buckets (script + output), ECR repository, ALB, ACM cert, CloudWatch alarms — approximately 32 hours.
2. **[High]** Update stale `🏗 Planned` status markers in `README.md`, `docs/architecture.md`, and `docs/project-guide.md` to reflect that `src/api/`, `src/batch/jobs/`, `tests/`, and all related modules are delivered — approximately 8 hours.
3. **[High]** Configure GitHub Actions OIDC or secrets for AWS (`AWS_ROLE_ARN`, region, cluster name, service name) and run a dry deploy of `deploy-api.yml` and `deploy-glue.yml` — approximately 4 hours.
4. **[High]** Run real AWS integration smoke tests (Secrets Manager retrieval, SQS send/receive, S3 put/get, CloudWatch metric publish) against the provisioned infrastructure — approximately 16 hours.
5. **[Medium]** Add CloudWatch **alarms** (not just the existing dashboard) for Glue job failure rate, ECS service 5xx rate, Aurora connection saturation, SQS message-age — approximately 12 hours.

---

## 2. Project Hours Breakdown

### 2.1 Completed Work Detail

| Component | Hours | Description |
|---|---|---|
| SQLAlchemy ORM Models (11 entities) | 66 | 11 models translated from COBOL copybooks (CVACT01Y, CVACT02Y, CVACT03Y, CVCUS01Y, CVTRA01Y–CVTRA06Y, CSUSR01Y) in `src/shared/models/` (4,838 lines). Composite primary keys for `TransactionCategoryBalance`/`DisclosureGroup`/`TransactionCategory`, `@version` columns for optimistic concurrency on `Account`/`Card`, NUMERIC(p,2) precision matching every COBOL `PIC S9(n)V99` clause |
| Pydantic v2 Request/Response Schemas (9 modules) | 36 | 9 schema modules in `src/shared/schemas/` (7,793 lines) — account, card, customer, transaction, user, bill, report, auth, admin — translated from 17 BMS symbolic maps in `app/cpy-bms/*.CPY`. Validation rules, field aliases (camelCase↔snake_case), and nested DTOs |
| Shared Constants, Utilities & Config (14 modules) | 54 | `src/shared/constants/` (messages, lookup_codes, menu_options — 1,375 lines), `src/shared/utils/` (date_utils, decimal_utils, string_utils — 2,845 lines), `src/shared/config/` (settings, aws_config — 1,358 lines), plus 6 `__init__.py` package markers. COBOL-compatible `ROUND_HALF_EVEN` decimal arithmetic preserved |
| Aurora PostgreSQL Migration Scripts | 36 | 3 Flyway-style SQL files in `db/migrations/` (1,373 lines total): V1 (11 tables, 429 lines), V2 (3 B-tree indexes replacing VSAM AIX paths, 125 lines), V3 (636 seed rows from 9 ASCII fixture files, 819 lines). Applied cleanly to PostgreSQL 16 |
| FastAPI Core Infrastructure | 40 | `main.py` (552 lines with `create_app()` factory, CORS + JWT middleware, lifespan hooks, `/health`, Strawberry GraphQL mount), `database.py` (async SQLAlchemy engine + session factory), `dependencies.py` (DB session, current user, auth deps), 3 middleware modules (`auth.py`, `error_handler.py`, `security_headers.py`, 2,720 lines) |
| REST Routers (8 modules, 18 endpoints) | 48 | 8 routers in `src/api/routers/` (3,082 lines) covering all 18 REST endpoints: auth (login/logout), account (GET/PUT), card (list/detail/update), transaction (list/detail/add), bill (pay), report (submit), user (CRUD×4), admin (menu/status) — translates 18 online CICS programs COSGN00C–COUSR03C |
| Service Layer — Business Logic (7 modules) | 140 | 7 services in `src/api/services/` (10,822 lines) encapsulating COBOL `PROCEDURE DIVISION` business logic — `auth_service` (BCrypt + JWT), `account_service` (3-entity join + dual-write transactional rollback), `card_service` (paginated list 7/page + optimistic concurrency), `transaction_service` (auto-ID + xref resolution), `bill_service` (atomic Transaction INSERT + Account balance UPDATE), `report_service` (SQS FIFO publish replacing TDQ WRITEQ), `user_service` (full CRUD + BCrypt hashing) |
| GraphQL Layer (Strawberry — 9 modules) | 54 | `src/api/graphql/` (6,283 lines) — `schema.py` assembling types+queries+mutations, 4 Strawberry types (account, card, transaction, user), `queries.py` (8 queries), `mutations.py`, package initializers, `get_graphql_router()` factory |
| PySpark Batch Jobs (11 jobs) | 168 | 11 jobs in `src/batch/jobs/` (14,504 lines) — `posttran_job` (Stage 1: 4-stage validation cascade, reject codes 100–109), `intcalc_job` (Stage 2: `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` with DEFAULT/ZEROAPR fallback), `combtran_job` (Stage 3: PySpark merge/sort replacing DFSORT+REPRO), `creastmt_job` (Stage 4a: 4-entity join + text/HTML statement), `tranrept_job` (Stage 4b: 3-level totals), `prtcatbl_job`, `daily_tran_driver_job`, 4 diagnostic readers (`read_account`, `read_card`, `read_customer`, `read_xref`) |
| Batch Common Modules + Step Functions | 32 | `src/batch/common/` (2,826 lines) — `glue_context.py` (GlueContext+SparkSession factory with structured JSON logging for CloudWatch), `db_connector.py` (JDBC Aurora connection via Secrets Manager), `s3_utils.py` (GDG-replacement helpers). `src/batch/pipeline/step_functions_definition.json` (6-state ASL: S1→S2→S3→Parallel(S4a,S4b)) |
| Test Suite — Unit Tests (1,104 tests) | 210 | 379 batch tests + 187 model tests + 124 router tests + 131 service tests + 209 utility tests + 74 schema tests in `tests/unit/` — covering all models, services, routers, utils, schemas, PySpark jobs with extensive parametrized cases |
| Test Suite — Integration Tests (95 tests) | 80 | `tests/integration/test_database.py` + `test_api_endpoints.py` — database-backed tests via Testcontainers PostgreSQL + API tests via `httpx.AsyncClient` against a running FastAPI app |
| Test Suite — E2E Tests (53 tests) | 64 | `tests/e2e/test_batch_pipeline.py` (2,597 lines) — full 5-stage batch pipeline end-to-end with real PostgreSQL and JDBC |
| Infrastructure Configs (7 files) | 28 | `infra/ecs-task-definition.json` (Fargate 0.5 vCPU / 1 GB), 5 Glue job configs (Glue 5.1 / G.1X / 2 workers for POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT), `infra/cloudwatch/dashboard.json` (9 widgets for Glue + ECS + Aurora) |
| CI/CD Workflows (3 GitHub Actions) | 30 | `.github/workflows/ci.yml` (lint + type-check + unit + integration jobs), `deploy-api.yml` (build-and-push + deploy + health-check), `deploy-glue.yml` (upload-scripts + update-glue-jobs + update-pipeline). Replaces samples/jcl/BATCMP.jcl, BMSCMP.jcl, CICCMP.jcl |
| Container & Dev Scaffolding | 16 | `Dockerfile` (Python 3.11-slim single-stage, 270 MB optimized, non-root appuser, HEALTHCHECK), `docker-compose.yml` (API + PostgreSQL 16 + LocalStack), `pyproject.toml` (ruff + mypy + pytest config), 4 requirements files (core / api / glue / dev), `.dockerignore`, `.gitignore` |
| Documentation | 46 | `README.md` (569 lines), `docs/architecture.md` (804 lines), `docs/project-guide.md` (685 lines), `docs/technical-specifications.md` (1,239 lines), `docs/index.md` — describe target architecture, runbook, setup, testing, deployment |
| QA Validation Fixes (10+ checkpoints, 170 commits) | 112 | Resolution of QA findings across 10+ checkpoints: CP1 (aws_config factories, model metadata), CP2 (10 findings: 4 CRITICAL API missing, 2 MAJOR, 4 MINOR), CP3, CP4 (architectural fixes), CP5 (24 findings — 11 CRITICAL + 11 MAJOR + 2 MINOR), CP6 (8 security findings), CP7 (test fixes + coverage closure), CP8 (Docker 501→270 MB), CP10 (2 GraphQL concurrency/pageSize), final ruff-format normalization across 47 files |
| **Total Completed** | **1,260** | **Sums to Section 1.2 "Completed Hours"** |

### 2.2 Remaining Work Detail

| Category | Hours | Priority |
|---|---|---|
| AWS IaC (Terraform/CloudFormation) — ECS cluster, Aurora cluster, IAM roles, VPC, SGs, SQS FIFO queue, S3 buckets, ECR repository | 32 | High |
| Real AWS Integration Testing (Secrets Manager, SQS, S3, CloudWatch) beyond moto mocks | 16 | High |
| Production Secrets / IAM Role / KMS configuration (DB creds, JWT signing key, app secrets) | 12 | High |
| ALB + DNS + SSL/TLS Certificate Configuration (ACM + Route 53) | 12 | High |
| Documentation Consistency Cleanup — remove stale `🏗 Planned` markers in README.md / architecture.md / project-guide.md | 8 | High |
| CI/CD Secrets Configuration in GitHub Actions (`AWS_ROLE_ARN`, cluster/service names, ECR URI, Glue/SF ARNs) | 4 | High |
| Production CloudWatch Alarms & Notification Channels (SNS → PagerDuty/email) — Glue failure, ECS 5xx, Aurora CPU/connections, SQS age | 12 | Medium |
| Load Testing & Performance Validation (k6 or Locust against real AWS) | 12 | Medium |
| Rate Limiting + WAF Rules (AWS WAF ACL) | 8 | Medium |
| Security Hardening Review (CORS origins, CSP, HSTS, `X-Content-Type-Options`, pen test) | 8 | Medium |
| Database Backup & DR Strategy (Aurora snapshots, cross-region replica, restore runbook) | 8 | Medium |
| DevOps Operational Runbook (on-call, rollback, Glue rerun, schema migration) | 4 | Medium |
| GraphQL Naming Convention Audit (camelCase-no-prefix vs REST snake_case — `currBal` vs `acct_curr_bal`) | 3 | Low |
| Mypy/Ruff Configuration Cleanup (unused `factory.*`, `jose.*` wildcard overrides in pyproject.toml) | 1 | Low |
| **Total Remaining** | **140** | **Sums to Section 1.2 "Remaining Hours"** |

### 2.3 Hours Verification

- Section 2.1 Total: **1,260** hours ✓
- Section 2.2 Total: **140** hours ✓
- 2.1 + 2.2 = **1,400** = Section 1.2 Total ✓
- Completion %: 1,260 ÷ 1,400 × 100 = **90.0%** ✓

---

## 3. Test Results

All tests below originate from Blitzy's autonomous validation logs executed against the `blitzy-66b0591b-4769-4d22-9576-8fe54b2c16ad` branch with Python 3.11.15, PySpark 3.5.6, PostgreSQL 16.13, and OpenJDK 17.0.18.

| Test Category | Framework | Total Tests | Passed | Failed | Coverage % | Notes |
|---|---|---|---|---|---|---|
| **Unit — Models (11 ORM entities)** | pytest 8.4.2 | 187 | 187 | 0 | 100% (models/) | Every `__tablename__`, composite PK, `@version`, `NUMERIC(p,2)` precision validated |
| **Unit — Services (7 modules)** | pytest 8.4.2 | 131 | 131 | 0 | 80%+ | Auth (BCrypt+JWT), account (3-entity join), card (optimistic concurrency), transaction (auto-ID), bill (dual-write), report (SQS), user (CRUD) |
| **Unit — Routers (8 modules)** | pytest 8.4.2 + httpx TestClient | 124 | 124 | 0 | High | All 18 REST endpoints (auth, account, card, transaction, bill, report, user, admin) including 404/400/401/403/422/500 envelopes |
| **Unit — Utilities (3 modules)** | pytest 8.4.2 | 209 | 209 | 0 | 96–100% | Date validation, decimal arithmetic (COBOL `ROUND_HALF_EVEN`), string processing — 957 lines of date tests alone |
| **Unit — Schemas** | pytest 8.4.2 | 74 | 74 | 0 | 77–100% | Pydantic v2 request/response validation |
| **Unit — Batch (11 jobs + 3 common)** | pytest 8.4.2 + PySpark | 379 | 379 | 0 | High | All PySpark transformation functions including 4-stage posting cascade, interest formula, DFSORT merge, 3-level report totals |
| **Integration — Database** | pytest 8.4.2 + Testcontainers | 30+ | 30+ | 0 | n/a | Real PostgreSQL 16 via Testcontainers; verifies schema + indexes + seed + transactional rollback |
| **Integration — REST / GraphQL API** | pytest 8.4.2 + httpx.AsyncClient | 60+ | 60+ | 0 | n/a | Running FastAPI app; auth flow, JSON envelopes, GraphQL introspection |
| **E2E — Batch Pipeline** | pytest 8.4.2 + PySpark + PostgreSQL | 53 | 53 | 0 | n/a | Full 5-stage `POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT)` against real PostgreSQL (2,597 lines of test code) |
| **TOTAL** | **pytest 8.4.2** | **1,252** | **1,252** | **0** | **81.66% overall** | Above `--cov-fail-under=80` gate. Runtime 63–85 seconds for full suite |

### Static-Analysis Gates (Blitzy autonomous validation)

| Gate | Tool | Target | Result |
|---|---|---|---|
| Linting | `ruff check src/ tests/ --no-fix` | All checks passed | ✅ 0 issues |
| Formatting | `ruff format --check src/ tests/` | 146 files formatted | ✅ Clean |
| Type Checking (src) | `mypy --strict src/` | 87 files | ✅ Success |
| Type Checking (src+tests) | `mypy --strict src/ tests/` | 146 files | ✅ Success |
| Compilation | `python -m compileall src/ tests/` | 146 files | ✅ Clean |

---

## 4. Runtime Validation & UI Verification

### FastAPI Application (AWS ECS Fargate target) — ✅ Operational

Started via `uvicorn src.api.main:app --host 127.0.0.1 --port 8000`:

| Check | Result | Notes |
|---|---|---|
| `GET /health` | ✅ 200 OK | Returns `{"status":"ok","service":"carddemo","version":"1.0.0","timestamp":"..."}` |
| `GET /openapi.json` | ✅ 200 OK | 15 paths enumerated; title `"CardDemo API"`, version `1.0.0` |
| Route enumeration at startup | ✅ | 27 routes loaded (REST + GraphQL mount + static) |
| `POST /auth/login` with `ADMIN001` / `PASSWORD` (F-001) | ✅ | Returns JWT bearer token; BCrypt verifies the seeded `$2b$12$ocmkU/...` hash |
| `GET /accounts/00000000001` (F-004, requires bearer) | ✅ | Returns 3-entity join (Account + Customer + CardCrossReference) with all 15+ fields including Decimal `credit_limit`, `current_balance` |
| `GET /cards?page=1` (F-006) | ✅ | Paginated list — **7 rows/page** per F-006 specification exactly |
| `GET /admin/menu` (F-003) | ✅ | 4 admin options returned matching `app/cpy/COADM02Y.cpy` — User List / Add / Update / Delete |
| `POST /graphql` schema introspection | ✅ | 8 Query fields: `account`, `accounts`, `card`, `cards`, `transaction`, `transactions`, `user`, `users` |
| `POST /graphql` with `account(acctId:"00000000001")` | ✅ | Returns `{"acctId":"00000000001","currBal":"176.50","creditLimit":"2020.00"}` (Decimal precision preserved) |

### PySpark Batch Jobs (AWS Glue 5.1 target) — ✅ Operational

Executed locally against real PostgreSQL via JDBC (`postgresql-42.7.4.jar` available in `pyspark/jars/`):

| Job | Result | Records | Notes |
|---|---|---|---|
| `read_account_job` (CBACT01C) | ✅ | 50 accounts read | Structured JSON CloudWatch-compatible logging |
| `read_card_job` (CBACT02C) | ✅ | 50 cards read | |
| `read_customer_job` (CBCUS01C) | ✅ | 50 customers read | |
| `read_xref_job` (CBACT03C) | ✅ | 50 cross-references read | |
| All jobs use `init_glue()` / `commit_job()` | ✅ | — | Matches AWS Glue 5.1 contract |

### Database (Aurora PostgreSQL target) — ✅ Operational

Local PostgreSQL 16.13 instance validates the full migration:

| Check | Result | Notes |
|---|---|---|
| Schema (V1) | ✅ | 11 tables — accounts, cards, customers, card_cross_references, transactions, daily_transactions, user_security, disclosure_groups, transaction_category_balances, transaction_categories, transaction_types |
| Indexes (V2) | ✅ | 3 B-tree indexes replacing VSAM AIX paths |
| Seed data (V3) | ✅ | 50 accounts, 50 cards, 50 customers, 50 xrefs, 318 transactions (post-e2e), 300 daily_transactions, 10 users, 51 disclosure groups, 100 transaction_category_balances, 18 categories, 7 types |
| SQLAlchemy model ↔ DDL | ✅ | Plural `__tablename__` values match |

### Infrastructure Configs — ✅ Operational

| Artifact | Result | Notes |
|---|---|---|
| `infra/ecs-task-definition.json` | ✅ | Valid JSON, 1 container definition |
| 5 Glue job configs | ✅ | All `GlueVersion = 5.1` |
| `infra/cloudwatch/dashboard.json` | ✅ | 9 widgets |
| `src/batch/pipeline/step_functions_definition.json` | ✅ | 6 states (S1→S2→S3→Parallel(S4a,S4b)) |
| `docker-compose.yml` | ✅ | Resolves cleanly via `docker compose config` — API + PostgreSQL 16 + LocalStack services |
| 3 GitHub Actions workflows | ✅ | `ci.yml` → jobs [lint, type-check, unit-tests, integration-tests]; `deploy-api.yml` → [build-and-push, deploy, health-check]; `deploy-glue.yml` → [upload-scripts, update-glue-jobs, update-pipeline] |

### API Documentation — ⚠ Partial

- ✅ Swagger UI available at `/docs`, ReDoc at `/redoc`, OpenAPI JSON at `/openapi.json`
- ⚠ `README.md`, `docs/architecture.md`, `docs/project-guide.md` contain stale `🏗 Planned` markers that contradict the current delivered state — documentation refresh required before stakeholder review

---

## 5. Compliance & Quality Review

AAP deliverables are cross-mapped to Blitzy's autonomous validation benchmarks below. "Fix applied during validation" cites the commit that closed the gap.

| AAP Reference | Requirement | Evidence | Status | Fix Applied |
|---|---|---|---|---|
| §0.1.1 Batch COBOL → PySpark on AWS Glue | 10 batch programs become PySpark scripts | `src/batch/jobs/*.py` (11 files, 14,504 lines) | ✅ Delivered | Multiple commits incl. `9a6fa31` (PRTCATBL tests) |
| §0.1.1 Online CICS COBOL → REST/GraphQL on AWS ECS | 18 online programs become FastAPI endpoints | `src/api/routers/` (8 files, 18 endpoints) + `src/api/services/` (7 files) + `src/api/graphql/` (9 files) | ✅ Delivered | CP2 fix: created 4 CRITICAL missing files in `src/api/main.py`, routers, `graphql/schema.py`, `graphql/queries.py` |
| §0.1.1 Database: AWS Aurora PostgreSQL | VSAM→PostgreSQL, 11 tables | `db/migrations/V1__schema.sql`, `V2__indexes.sql`, `V3__seed_data.sql` | ✅ Delivered | — |
| §0.1.1 Deployment: GitHub + GitHub Actions | CI/CD pipelines | `.github/workflows/ci.yml`, `deploy-api.yml`, `deploy-glue.yml` | ✅ Delivered | — |
| §0.1.1 Preserve all 22 features (F-001–F-022) | Behavioral parity with mainframe | All 22 features mapped — F-001/002/003/004/005/006/007/008/009/010/011/012/018/019/020/021/022 in routers+services; F-013/014/015/016/017 in batch jobs | ✅ Delivered | — |
| §0.1.2 Transform COBOL `PIC S9(n)V99` → Python `Decimal` | Financial precision | `src/shared/utils/decimal_utils.py` (86 statements, 100% coverage); all model fields use `NUMERIC(12,2)`/`NUMERIC(11,2)`/`NUMERIC(6,2)` | ✅ Delivered | — |
| §0.1.2 CICS COMMAREA → JWT | Stateless session | `src/api/services/auth_service.py`, `src/api/middleware/auth.py`; `python-jose[cryptography]>=3.4.0,<4.0` (CVE-2024-33663/33664 fix) | ✅ Delivered | CP6 Security fix on JOSE version pin |
| §0.1.2 CICS TDQ WRITEQ JOBS → SQS FIFO | Report submission queue | `src/api/services/report_service.py` (SQS FIFO publish) | ✅ Delivered | — |
| §0.1.2 JCL COND → Step Functions | Batch pipeline orchestration | `src/batch/pipeline/step_functions_definition.json` (6 states, ASL) | ✅ Delivered | — |
| §0.4.4 Testing: parity with 81.5% coverage | ≥80% coverage | **81.66% achieved** on 1,252 tests | ✅ Exceeds | CP7 fix: test fixes + coverage closure |
| §0.5.1 All 110+ target files created | File count | 87 src Python + 59 test Python + 3 SQL + 7 infra + 3 workflows + Dockerfile + docker-compose + pyproject + 4 requirements | ✅ Delivered | — |
| §0.6.1 Dependency versions aligned | Python 3.11, Spark 3.5.6, FastAPI 0.115+, SQLAlchemy 2.x | Python 3.11.15, PySpark 3.5.6, FastAPI 0.136.0, SQLAlchemy 2.0.49 | ✅ Delivered | — |
| §0.7.1 Minimal change / preserve behavior | No algebraic simplification of interest formula; 4-stage cascade preserved | `intcalc_job` uses `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` exactly; `posttran_job` preserves reject codes 100–109 | ✅ Delivered | — |
| §0.7.1 `app/` tree retained unchanged | Original COBOL source retained | `app/cbl/`, `app/cpy/`, `app/bms/`, `app/cpy-bms/`, `app/jcl/`, `app/data/`, `app/catlg/` all present, unmodified | ✅ Delivered | — |
| §0.7.2 Secrets Manager / IAM / BCrypt / JWT | Security stack | `passlib[bcrypt]`, `python-jose[cryptography]>=3.4.0,<4.0`, boto3 Secrets Manager factory in `src/shared/config/aws_config.py` | ✅ Delivered | — |
| §0.7.2 Monitoring | CloudWatch integration, structured JSON logging | Structured JSON logs in `src/api/main.py` and `src/batch/common/glue_context.py`; dashboard at `infra/cloudwatch/dashboard.json` (9 widgets) | ⚠ Partial | Dashboard ✅; alarms ❌ — listed in Remaining Work |
| §0.7.2 Automated testing | pytest + unit + integration + mocks | 1,252 tests at 81.66% coverage | ✅ Delivered | CP7 |
| §0.5.1 Documentation updates | README + architecture.md + project-guide.md | Files exist but contain stale `🏗 Planned` markers | ⚠ Partial | Listed in Remaining Work (8 h) |
| Path-to-production | AWS IaC provisioning | Not in current scope | ❌ Pending | Listed in Remaining Work (32 h) |
| Path-to-production | AWS secrets / IAM / SSL / DNS | Not in current scope | ❌ Pending | Listed in Remaining Work (28 h combined) |

---

## 6. Risk Assessment

| Risk | Category | Severity | Probability | Mitigation | Status |
|---|---|---|---|---|---|
| Stale documentation (`🏗 Planned` markers in README/architecture/project-guide) contradicts delivered code | Technical / Communication | Medium | Certain | Update the three documents to mark `src/api/`, `src/batch/jobs/`, `tests/` as delivered; strike "tests not yet written" note from README Testing section | 🟡 Known — 8 h remaining |
| AWS integrations exercised only via `moto` mocks — real AWS SDK call paths not validated | Integration | Medium | Medium | Run real-AWS smoke tests against a dev account before production cutover | 🟡 Pending |
| AWS infrastructure (ECS cluster, Aurora cluster, IAM, VPC, ALB, Secrets Manager, SQS, S3, ECR) not provisioned | Operational / Deployment | High | Certain | Author Terraform (or CloudFormation/CDK) to stand up all referenced resources; workflows assume they exist | 🔴 Blocking deploy |
| No CloudWatch alarms — only a dashboard | Operational | Medium | Certain | Add alarms for Glue job failure rate, ECS 5xx rate, Aurora CPU/connection count, SQS message age, each wired to SNS → PagerDuty/email | 🟡 Pending |
| `python-jose` CVE-2024-33663 / 33664 if version pin slips below 3.4.0 | Security | High | Low (currently 3.5.0 installed) | Pin `>=3.4.0,<4.0` in `requirements-api.txt`; CP6 fix applied | ✅ Mitigated |
| CORS configured permissively for development | Security | Medium | Medium | Tighten `allow_origins` list in `src/api/main.py` before production; add HSTS, CSP, `X-Content-Type-Options` already present via `security_headers.py` | 🟡 Pending — security hardening (8 h) |
| JWT signing key provisioning | Security | High | Medium | Store production key in AWS Secrets Manager, not env vars; rotate periodically | 🟡 Pending |
| No rate limiting / WAF | Security | Medium | Medium | Add AWS WAF ACL rules on the ALB before production (8 h in Remaining) | 🟡 Pending |
| GraphQL naming divergence from REST (`currBal` vs `acct_curr_bal`) | Technical / API Contract | Low | Certain | Deliberate audit: decide on camelCase-no-prefix (current) vs camelCase-with-prefix (matches REST alias) and document the contract | 🟢 Low-impact |
| Unused `factory.*`, `jose.*` wildcard mypy overrides in `pyproject.toml` | Technical / Config | Low | Certain | Remove unused module overrides to clean informational mypy notes | 🟢 Cosmetic |
| Docker image layer-caching regression if `RUN` directives are split | Technical / Build | Medium | Low | The single-RUN pattern is documented with a comment in `Dockerfile`; reviewers should not split it (CP8 Issue 1 regression-prevention) | ✅ Documented |
| Database backup / DR strategy not defined | Operational | High | Certain | Aurora automated backups + cross-region snapshots + documented restore runbook (8 h in Remaining) | 🟡 Pending |
| GitHub Actions AWS credentials not configured | Deployment | High | Certain | Configure OIDC assume-role (preferred) or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` secrets in the GitHub repo | 🔴 Blocking deploy |

---

## 7. Visual Project Status

### 7.1 Project Hours — Completed vs Remaining

```mermaid
pie title Project Hours Breakdown — 90% Complete
    "Completed Work" : 1260
    "Remaining Work" : 140
```

*Colors (applied by renderer — Completed = Dark Blue `#5B39F3`, Remaining = White `#FFFFFF`)*

### 7.2 Remaining Work by Priority

```mermaid
pie title Remaining Work (140 hours) by Priority
    "High Priority" : 84
    "Medium Priority" : 52
    "Low Priority" : 4
```

### 7.3 Remaining Work Categories (top categories, hours)

```mermaid
pie title Remaining Work Categories
    "AWS IaC (Terraform/CloudFormation)" : 32
    "Real AWS Integration Testing" : 16
    "Prod Secrets + ALB/DNS/SSL" : 24
    "CloudWatch Alarms + Load Testing" : 24
    "Security Hardening + WAF + DR" : 24
    "Docs cleanup + CI/CD secrets + runbook" : 16
    "GraphQL audit + config cleanup" : 4
```

### 7.4 Delivered Artifacts — by Layer

```mermaid
pie title Delivered Python Source Files (87 total)
    "Shared (models, schemas, constants, utils, config)" : 33
    "API (main, routers, services, middleware, graphql)" : 33
    "Batch (jobs, common, pipeline)" : 16
    "Package markers (__init__.py)" : 5
```

---

## 8. Summary & Recommendations

### 8.1 Achievements

The CardDemo modernization project is **90.0% complete** as of branch `blitzy-66b0591b-4769-4d22-9576-8fe54b2c16ad`, with **1,260 of 1,400 AAP-scoped engineering hours delivered**. The migration has successfully replaced the entire z/OS mainframe workload (28 COBOL programs, 28 copybooks, 17 BMS mapsets, 17 symbolic maps, 29 JCL jobs, 9 fixture files) with a Python 3.11 cloud-native stack:

- The **online workload** is a FastAPI application with 8 routers / 18 REST endpoints + a Strawberry GraphQL schema (8 queries + mutations), backed by 7 business-logic services that preserve every CICS validation cascade, optimistic-concurrency check, and dual-write atomic transaction from the COBOL source.
- The **batch workload** is 11 PySpark scripts targeting AWS Glue 5.1, orchestrated by an AWS Step Functions state machine that replicates the original JCL `COND=(0,NE)` chaining for the 5-stage pipeline `POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT)`.
- The **database layer** is Aurora PostgreSQL with 11 tables, 3 B-tree indexes replacing VSAM AIX paths, and 636 seed rows — all preserving COBOL `PIC S9(n)V99` precision via `NUMERIC(p,2)` columns round-tripped as Python `Decimal`.
- **Quality gates all pass**: 1,252 tests at 81.66% coverage (above the 80% `--cov-fail-under` gate), clean `ruff check`/`ruff format --check`, clean `mypy --strict` on 146 source files, and live-verified REST+GraphQL+PySpark runtimes.
- **Security best practices** observed: BCrypt password hashing, JWT signing via `python-jose[cryptography]>=3.4.0,<4.0` (CVE-2024-33663/33664-patched), CORS middleware, security-headers middleware, non-root Docker user, parameterized queries via SQLAlchemy ORM.

The project is a **faithful translation** of the mainframe application — the interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` is preserved without algebraic simplification, the 4-stage transaction-validation cascade with reject codes 100–109 is preserved exactly, the DEFAULT/ZEROAPR disclosure-group fallback is preserved, and the `app/` source tree is retained unchanged for traceability per AAP §0.7.1.

### 8.2 Remaining Gaps (140 hours)

The **remaining 10%** is overwhelmingly **path-to-production infrastructure work**, not application-layer work:

- **High priority (84 h)** — AWS Infrastructure-as-Code (Terraform/CloudFormation to provision ECS cluster, Aurora cluster, IAM roles, VPC, ALB, Secrets Manager secrets, SQS FIFO queue, S3 buckets, ECR repository), real-AWS integration smoke testing, production secrets/IAM/KMS configuration, ALB + DNS + SSL/TLS, GitHub Actions CI/CD secret configuration, and documentation cleanup (stale `🏗 Planned` markers).
- **Medium priority (52 h)** — CloudWatch alarms (beyond the existing dashboard), load/performance testing, rate limiting/WAF, CORS/CSP/HSTS hardening, database backup and DR strategy, DevOps operational runbook.
- **Low priority (4 h)** — GraphQL-vs-REST naming convention audit, cleanup of unused mypy wildcard overrides in `pyproject.toml`.

### 8.3 Critical Path to Production

1. **Update documentation** (`README.md`, `docs/architecture.md`, `docs/project-guide.md`) to reflect delivered state — ~8 hours (High)
2. **Author Terraform/CloudFormation** for all AWS resources referenced by `infra/*.json` and `.github/workflows/deploy-*.yml` — ~32 hours (High)
3. **Provision production AWS account** with secrets, IAM roles, ECR repo, SQS queue, S3 buckets — ~12 hours (High)
4. **Configure GitHub Actions secrets** — ~4 hours (High)
5. **Stand up ALB + Route 53 + ACM certificate** — ~12 hours (High)
6. **Run real-AWS integration smoke tests** — ~16 hours (High)
7. **Configure CloudWatch alarms, run load test, apply security hardening, set up backup/DR** — ~52 hours (Medium)

### 8.4 Success Metrics Delivered

| Metric | Target | Achieved |
|---|---|---|
| Behavioral parity (F-001–F-022) | 22/22 | 22/22 ✅ |
| Test coverage | ≥80% | 81.66% ✅ |
| Test pass rate | 100% | 100% (1,252/1,252) ✅ |
| Compilation | All modules | 146/146 ✅ |
| Lint/format/type check | Clean | Clean ✅ |
| Docker image size | <300 MB | 270 MB ✅ |
| FastAPI routes registered | 18 REST + GraphQL + health | 21 (+docs + redoc + openapi.json + GraphQL mount) ✅ |

### 8.5 Production Readiness Assessment

- **Application code:** ✅ Production-ready. All validation gates passed; behavior matches COBOL source.
- **Database:** ✅ Production-ready schema. Seed data is dev fixtures — production data migration strategy required.
- **Infrastructure code (JSON configs, Dockerfile, workflows):** ✅ Production-ready.
- **Actual AWS infrastructure:** ❌ **Not yet provisioned** — blocks first deploy.
- **Production secrets / IAM / DNS / SSL:** ❌ **Not yet configured** — blocks first deploy.
- **Monitoring / alarms / WAF / DR:** ⚠ Partial — dashboard exists, alarms/WAF/DR strategy pending.

The codebase is **production-ready**; the production **environment** is not yet provisioned. Once the remaining 140 hours (chiefly Terraform + secrets + DNS + monitoring alarms) are completed, the project can be cut over to AWS.

---

## 9. Development Guide

### 9.1 System Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Python | **3.11.15** | Required — aligned with AWS Glue 5.1 runtime. Higher 3.11.x patch levels acceptable |
| OpenJDK | **17.0.18** | Required for PySpark 3.5.6 |
| PostgreSQL | **16.13** | Local dev database (Aurora PostgreSQL is the production target) |
| Docker + Docker Compose | Recent | Required for full local dev stack (API + PostgreSQL + LocalStack) |
| Git | ≥ 2.30 | To clone the repository |
| `curl` | any | Used by HEALTHCHECK and smoke tests |

Operating system: Linux/macOS native or Windows + WSL2. Minimum 8 GB RAM recommended (PySpark is memory-hungry; Glue jobs assume ≥ 4 GB heap per executor).

### 9.2 Environment Setup

Clone the repository and create a Python virtual environment:

```bash
git clone <repository-url> blitzy-card-demo
cd blitzy-card-demo
git checkout blitzy-66b0591b-4769-4d22-9576-8fe54b2c16ad
python3.11 -m venv venv
source venv/bin/activate
pip install --upgrade pip
```

### 9.3 Dependency Installation

Install the four requirement groups (shared, API, Glue batch, and dev):

```bash
pip install -r requirements.txt           # boto3, pydantic, pydantic-settings, python-dotenv
pip install -r requirements-api.txt       # fastapi, uvicorn, sqlalchemy[asyncio], asyncpg, psycopg2-binary, alembic, strawberry-graphql, python-jose, passlib[bcrypt], bcrypt, python-multipart, httpx
pip install -r requirements-glue.txt      # pyspark 3.5.6, pg8000
pip install -r requirements-dev.txt       # pytest, pytest-asyncio, pytest-cov, moto, factory-boy, ruff, mypy, testcontainers[postgres]
```

Verify installation:

```bash
python --version        # Python 3.11.15
pip list | grep -E "fastapi|sqlalchemy|pyspark|pytest"
```

### 9.4 Environment Variables

Set the following environment variables (or create `.env` based on `src/shared/config/settings.py`):

```bash
export PYTHONPATH=$PWD
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # adjust for your OS
export DATABASE_URL="postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo"
export DATABASE_URL_SYNC="postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo"
export JWT_SECRET_KEY="test-secret-key-0123456789abcdef"
export JWT_ALGORITHM="HS256"
export JWT_ACCESS_TOKEN_EXPIRE_MINUTES="30"
export AWS_DEFAULT_REGION="us-east-1"
export AWS_ACCESS_KEY_ID="testing"
export AWS_SECRET_ACCESS_KEY="testing"
```

> For production: replace the JWT secret with a value retrieved from AWS Secrets Manager; replace `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` with an IAM role via instance-profile or OIDC.

### 9.5 Database Setup (Local PostgreSQL)

Create the database and apply the three Flyway-style migrations:

```bash
# Create user and database
sudo -u postgres createuser -P carddemo       # password: carddemo
sudo -u postgres createdb -O carddemo carddemo

# Apply migrations
PGPASSWORD=carddemo psql -h localhost -U carddemo -d carddemo -f db/migrations/V1__schema.sql
PGPASSWORD=carddemo psql -h localhost -U carddemo -d carddemo -f db/migrations/V2__indexes.sql
PGPASSWORD=carddemo psql -h localhost -U carddemo -d carddemo -f db/migrations/V3__seed_data.sql

# Verify
PGPASSWORD=carddemo psql -h localhost -U carddemo -d carddemo -c "SELECT count(*) FROM accounts;"
# Expected: 50
```

Alternatively, use the full local stack via Docker Compose:

```bash
docker compose up -d postgres                  # PostgreSQL 16 only
docker compose up                              # API + PostgreSQL + LocalStack
```

### 9.6 Running the API Locally

Start the FastAPI application via Uvicorn:

```bash
uvicorn src.api.main:app --host 0.0.0.0 --port 8000
# Expected: "Application startup complete" in logs
```

Verify health and documentation:

```bash
curl -s http://127.0.0.1:8000/health
# Expected: {"status":"ok","service":"carddemo","version":"1.0.0",...}

curl -s http://127.0.0.1:8000/openapi.json | python -c "import json, sys; d = json.load(sys.stdin); print('Paths:', len(d['paths']))"
# Expected: Paths: 15
```

Obtain a JWT and test an authenticated endpoint:

```bash
# Login (F-001 — COSGN00C.cbl)
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/auth/login \
    -H "Content-Type: application/json" \
    -d '{"user_id":"ADMIN001","password":"PASSWORD"}' | \
    python -c "import json, sys; print(json.load(sys.stdin)['access_token'])")

# Account View (F-004 — COACTVWC.cbl)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/accounts/00000000001

# Card List paginated at 7 rows/page (F-006 — COCRDLIC.cbl)
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8000/cards?page=1"

# Admin Menu (F-003 — COADM01C.cbl)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/admin/menu

# GraphQL introspection
curl -s -X POST http://127.0.0.1:8000/graphql \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"query":"query { account(acctId: \"00000000001\") { acctId currBal creditLimit } }"}'
```

### 9.7 Running a PySpark Batch Job Locally

```bash
# Example: CBACT01C equivalent — read the 50 accounts
python -c "from src.batch.jobs.read_account_job import main; main()"
```

Expected output: structured JSON log entries (one per row) followed by `END OF EXECUTION OF PROGRAM CBACT01C`.

For a production-style batch pipeline invocation (all 5 stages sequentially), the Step Functions state machine at `src/batch/pipeline/step_functions_definition.json` orchestrates `POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT)`.

### 9.8 Running the Test Suite

```bash
# Full suite with coverage (fails below 80%)
python -m pytest tests/ -q
# Expected: 1252 passed, 81.66% coverage

# Unit only
python -m pytest tests/unit/ -q
# Expected: 1104 passed

# Integration only (requires PostgreSQL running)
python -m pytest tests/integration/ -q
# Expected: 95 passed

# E2E batch pipeline
python -m pytest tests/e2e/ -q
# Expected: 53 passed

# Targeted module
python -m pytest tests/unit/test_services/test_account_service.py -v
```

### 9.9 Lint, Format, and Type Check (CI simulation)

```bash
ruff check src/ tests/ --no-fix            # All checks passed
ruff format --check src/ tests/            # 146 files already formatted
mypy src/                                  # Success: no issues in 87 files
mypy src/ tests/                           # Success: no issues in 146 files
```

### 9.10 Common Errors and Resolutions

| Error | Cause | Resolution |
|---|---|---|
| `ModuleNotFoundError: No module named 'src'` | `PYTHONPATH` not set | `export PYTHONPATH=$PWD` |
| `psycopg2.errors.UndefinedTable` | Migrations not applied | Run V1/V2/V3 migrations against PostgreSQL |
| `FATAL: password authentication failed for user "carddemo"` | Wrong password or missing user | `createuser -P carddemo` with password `carddemo` |
| Connection refused on port 5432 | PostgreSQL not running | Start via `docker compose up postgres` or `systemctl start postgresql` |
| `Java gateway process exited before sending its port number` | `JAVA_HOME` not set or wrong JDK | `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` (adjust path) |
| `401 Unauthorized` on protected endpoints | Missing or expired JWT | Re-login via `POST /auth/login` to obtain fresh token |
| `Required test coverage of 80% not reached` during partial test run | Subset doesn't hit full coverage | Normal — run full suite (`pytest tests/`) for coverage gate |
| GraphQL query returns "Cannot query field 'acctCurrBal'" | GraphQL uses `currBal`, not REST's aliased `acct_curr_bal` | Use GraphQL naming — see Section 6 risk (GraphQL naming audit Low priority) |

---

## 10. Appendices

### A. Command Reference

| Command | Purpose |
|---|---|
| `source venv/bin/activate` | Activate the Python 3.11 virtual environment |
| `pip install -r requirements*.txt` | Install dependencies (run all four) |
| `export PYTHONPATH=$PWD` | Enable absolute imports from `src/` |
| `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` | Enable PySpark |
| `PGPASSWORD=carddemo psql -h localhost -U carddemo -d carddemo -f db/migrations/V1__schema.sql` | Apply schema |
| `uvicorn src.api.main:app --host 0.0.0.0 --port 8000` | Start FastAPI |
| `python -c "from src.batch.jobs.<job_name> import main; main()"` | Run a PySpark Glue job locally |
| `python -m pytest tests/ -q` | Full test suite with coverage |
| `ruff check src/ tests/` | Lint |
| `ruff format --check src/ tests/` | Format check |
| `mypy --strict src/` | Strict type check |
| `docker compose up` | Full local stack (API + PostgreSQL + LocalStack) |
| `docker compose down` | Tear down local stack |
| `git log --oneline main..blitzy-66b0591b-4769-4d22-9576-8fe54b2c16ad` | View all 171 commits on this branch |

### B. Port Reference

| Port | Service | Source |
|---|---|---|
| 8000 | FastAPI (development) | `uvicorn ... --port 8000` |
| 80 | FastAPI (container) | `Dockerfile` + `docker-compose.yml` |
| 5432 | PostgreSQL | docker-compose + local Postgres |
| 4566 | LocalStack (AWS services) | docker-compose LocalStack container |
| 8000/graphql | GraphQL endpoint | Strawberry mount in `src/api/main.py` |
| 8000/docs | Swagger UI | FastAPI default |
| 8000/redoc | ReDoc | FastAPI default |
| 8000/openapi.json | OpenAPI schema | FastAPI default |

### C. Key File Locations

| Path | Purpose |
|---|---|
| `src/api/main.py` | FastAPI app entry point (`uvicorn` target) |
| `src/api/routers/` | 8 REST routers — 18 endpoints |
| `src/api/services/` | 7 business-logic services |
| `src/api/graphql/` | Strawberry GraphQL schema, types, queries, mutations |
| `src/api/middleware/` | JWT auth, error handler, security headers |
| `src/api/database.py` | SQLAlchemy async engine + session factory |
| `src/api/dependencies.py` | DI helpers (DB session, current user) |
| `src/batch/jobs/` | 11 PySpark Glue jobs (posttran, intcalc, combtran, creastmt, tranrept, prtcatbl, daily_tran_driver, 4 readers) |
| `src/batch/common/` | GlueContext factory, JDBC connector, S3 utils |
| `src/batch/pipeline/step_functions_definition.json` | Step Functions ASL |
| `src/shared/models/` | 11 SQLAlchemy ORM models |
| `src/shared/schemas/` | 9 Pydantic schema modules |
| `src/shared/constants/` | Messages, lookup codes, menu options |
| `src/shared/utils/` | Date, decimal, string utilities |
| `src/shared/config/` | Settings (Pydantic BaseSettings), AWS config |
| `db/migrations/V1__schema.sql` | 11-table schema |
| `db/migrations/V2__indexes.sql` | 3 B-tree indexes |
| `db/migrations/V3__seed_data.sql` | 636 seed rows |
| `tests/conftest.py` | 1,646-line pytest fixtures (30 exports) |
| `tests/unit/` | 1,104 unit tests |
| `tests/integration/` | 95 integration tests |
| `tests/e2e/test_batch_pipeline.py` | 53 end-to-end batch pipeline tests |
| `infra/ecs-task-definition.json` | ECS Fargate task definition |
| `infra/glue-job-configs/*.json` | 5 Glue job configs (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) |
| `infra/cloudwatch/dashboard.json` | Unified Glue + ECS dashboard |
| `.github/workflows/ci.yml` | CI pipeline (lint + type-check + unit + integration) |
| `.github/workflows/deploy-api.yml` | Build/push ECR image + update ECS service + health check |
| `.github/workflows/deploy-glue.yml` | Upload scripts to S3 + update Glue jobs + update pipeline |
| `Dockerfile` | FastAPI container image (Python 3.11-slim, 270 MB) |
| `docker-compose.yml` | Local dev stack (API + PostgreSQL 16 + LocalStack) |
| `pyproject.toml` | Project metadata + ruff + mypy + pytest config |
| `requirements*.txt` | 4 requirements files (core / api / glue / dev) |
| `app/` | Original COBOL/CICS/VSAM/JCL source — retained unchanged |

### D. Technology Versions

| Category | Package / Runtime | Version |
|---|---|---|
| Runtime | Python | 3.11.15 |
| Runtime | OpenJDK (for PySpark) | 17.0.18 |
| Database | PostgreSQL | 16.13 (local) / Aurora PostgreSQL-Compatible (prod target) |
| Framework — API | FastAPI | 0.136.0 |
| ASGI server | Uvicorn | 0.44.0 |
| ORM | SQLAlchemy | 2.0.49 |
| DB driver (async) | asyncpg | 0.31.0 |
| DB driver (sync) | psycopg2-binary | 2.9.11 |
| Migrations | alembic (installed) / Flyway-style SQL | 1.18.4 / manual |
| Validation | Pydantic | 2.12.4 |
| Config | pydantic-settings | 2.14.0 |
| GraphQL | strawberry-graphql | 0.314.3 |
| AuthN — JWT | python-jose[cryptography] | ≥ 3.4.0, < 4.0 (CVE-2024-33663/33664 fix) |
| AuthN — BCrypt | passlib[bcrypt] | 1.7.4 |
| AuthN — BCrypt backend | bcrypt | 4.3.0 |
| Batch — Spark | PySpark | 3.5.6 (matches AWS Glue 5.1) |
| Batch — Glue | AWS Glue | 5.1 (production target) |
| Batch — DB driver | pg8000 | 1.31.5 |
| AWS SDK | boto3 | 1.42.92 |
| Testing | pytest | 8.4.2 |
| Testing (async) | pytest-asyncio | 0.26.0 |
| Coverage | pytest-cov | 6.3.0 |
| AWS mocking | moto[all] | 5.1.22 |
| Containers (testing) | testcontainers[postgres] | 4.14.2 |
| Linting/formatting | ruff | 0.15.11 |
| Type checking | mypy | 1.20.1 |
| Factory | factory-boy | 3.3.3 |

### E. Environment Variable Reference

| Variable | Purpose | Example |
|---|---|---|
| `PYTHONPATH` | Enables `src.*` imports | `$PWD` |
| `JAVA_HOME` | Required by PySpark | `/usr/lib/jvm/java-17-openjdk-amd64` |
| `DATABASE_URL` | Async SQLAlchemy connection string | `postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo` |
| `DATABASE_URL_SYNC` | Sync SQLAlchemy connection (used by migrations, batch) | `postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo` |
| `JWT_SECRET_KEY` | JWT signing key — **use AWS Secrets Manager in production** | `test-secret-key-0123456789abcdef` (dev only) |
| `JWT_ALGORITHM` | JWT signing algorithm | `HS256` |
| `JWT_ACCESS_TOKEN_EXPIRE_MINUTES` | Token TTL | `30` |
| `AWS_DEFAULT_REGION` | AWS region for boto3 | `us-east-1` |
| `AWS_ACCESS_KEY_ID` | AWS credentials (use IAM role in production) | `testing` (dev only) |
| `AWS_SECRET_ACCESS_KEY` | AWS credentials (use IAM role in production) | `testing` (dev only) |
| `AWS_ENDPOINT_URL` | LocalStack endpoint for local dev | `http://localstack:4566` (docker-compose) |
| `SQS_REPORT_QUEUE_URL` | SQS FIFO URL (TDQ replacement) — set per env | `https://sqs.us-east-1.amazonaws.com/.../carddemo-reports.fifo` |
| `GLUE_SCRIPT_BUCKET` | S3 bucket for Glue scripts (used by deploy-glue.yml) | `carddemo-glue-scripts-prod` |
| `ENVIRONMENT` | Environment name | `development` / `staging` / `production` |

### F. Developer Tools Guide

| Tool | Purpose | Config location |
|---|---|---|
| **Ruff** — lint + format | Replaces flake8, isort, black, pylint | `[tool.ruff]` in `pyproject.toml` (line-length 120, target Python 3.11, `E`/`F`/`W`/`I`/`N`/`UP`/`B` rule sets) |
| **Mypy** — static types | Strict type checking | `[tool.mypy]` in `pyproject.toml` |
| **Pytest** — test runner | Async, coverage, fixtures | `[tool.pytest.ini_options]` in `pyproject.toml` |
| **Coverage.py** (via pytest-cov) | 80% gate enforced via `--cov-fail-under=80` | `pyproject.toml` |
| **Testcontainers** | Ephemeral PostgreSQL for integration tests | `tests/conftest.py` |
| **moto** | AWS service mocks for unit tests | `tests/conftest.py` |
| **Factory-Boy** | Test-data factories | `tests/conftest.py` |
| **Docker / Docker Compose** | Local dev stack | `Dockerfile`, `docker-compose.yml` |
| **LocalStack** | Local AWS emulation | `docker-compose.yml` |
| **alembic** (installed but not in active use) | Alternative to raw SQL migrations | Not currently configured — V1/V2/V3 use raw SQL |

### G. Glossary

| Term | Definition |
|---|---|
| **AAP** | Agent Action Plan — the specification that drives Blitzy agents (this project's AAP is §0.1–§0.8 in the project prompt) |
| **AIX (VSAM)** | Alternate Index — a secondary access path over a VSAM KSDS. Three in the source: `card.acct_id`, `card_cross_reference.acct_id`, `transaction.proc_ts`. Migrated to PostgreSQL B-tree indexes (V2) |
| **ASL** | Amazon States Language — the JSON DSL for Step Functions state machines |
| **BMS** | Basic Mapping Support — the 3270 terminal screen-definition language used by CICS. Migrated to Pydantic request/response schemas |
| **CICS** | Customer Information Control System — IBM's online transaction manager. Migrated to FastAPI on ECS Fargate |
| **COMBTRAN** | Stage 3 of the batch pipeline — DFSORT+REPRO merge. Migrated to pure-PySpark merge/sort in `combtran_job.py` |
| **COMMAREA** | CICS communication area — the session-state channel passed between CICS transactions. Replaced by JWT tokens |
| **COND (JCL)** | Conditional job-step execution — e.g. `COND=(0,NE,STEP1)` means "run only if STEP1 returned 0". Replaced by Step Functions `Choice` states |
| **CREASTMT** | Stage 4a of the batch pipeline — statement generation. Migrated to `creastmt_job.py` |
| **DALYTRAN** | Daily transaction feed (350-byte records from CVTRA06Y) |
| **DFSORT** | IBM's sort/merge utility. Replaced by PySpark `DataFrame.orderBy()` |
| **GDG** | Generation Data Group — versioned sequential datasets. Replaced by versioned S3 objects |
| **IDCAMS** | VSAM catalog-management utility. LISTCAT output retained at `app/catlg/LISTCAT.txt` for traceability |
| **INTCALC** | Stage 2 — interest calculation `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`. Migrated to `intcalc_job.py` |
| **JCL** | Job Control Language — z/OS batch job definitions. 29 files migrated to Glue job configs + Step Functions |
| **JWT** | JSON Web Token — stateless auth token. Replaces CICS COMMAREA |
| **KSDS** | Key-Sequenced Data Set — VSAM's equivalent of an indexed table. Migrated to PostgreSQL |
| **POSTTRAN** | Stage 1 of the batch pipeline — transaction posting with 4-stage validation cascade (reject codes 100–109). Migrated to `posttran_job.py` |
| **TCATBAL** | Transaction Category Balance (CVTRA01Y) — 3-part composite key |
| **TDQ** | Transient Data Queue — CICS queue for async writes. Replaced by AWS SQS FIFO |
| **TRANREPT** | Stage 4b — transaction reporting with 3-level totals. Migrated to `tranrept_job.py` |
| **VSAM** | Virtual Storage Access Method — IBM's indexed-file system. 10 KSDS datasets migrated to 11 Aurora PostgreSQL tables |
| **Cross-section integrity** | Blitzy Project Guide rule that remaining hours in Sections 1.2, 2.2, and 7 must match; Section 2.1 + 2.2 must equal Section 1.2 Total |
