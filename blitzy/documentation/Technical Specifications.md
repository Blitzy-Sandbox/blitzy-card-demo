# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification


### 0.1.1 Core Refactoring Objective

Based on the prompt, the Blitzy platform understands that the refactoring objective is to perform a **tech stack migration — mainframe-to-cloud modernization** of the AWS CardDemo application (`blitzy-card-demo`). The migration splits the existing COBOL/CICS/VSAM/JCL/BMS mainframe application into two distinct cloud-native workload types:

- **Batch COBOL programs → PySpark jobs on AWS Glue** — All 10 batch programs (file readers, transaction posting, interest calculation, statement generation, transaction reporting) will be converted to PySpark ETL scripts that execute as serverless Spark jobs within AWS Glue.
- **Online CICS COBOL programs → REST/GraphQL APIs on AWS ECS** — All 18 interactive CICS transaction programs (authentication, navigation, account/card/transaction CRUD, bill payment, reporting, user administration) will be converted to Python-based REST and GraphQL API endpoints, containerized and deployed on AWS Elastic Container Service (ECS) with Fargate.
- **Database: AWS Aurora-PostgreSQL** — All 10 VSAM KSDS datasets and their alternate indexes will be migrated to AWS Aurora PostgreSQL-Compatible Edition as the single relational persistence layer.
- **Deployment: GitHub and GitHub Actions** — CI/CD pipelines will be implemented using GitHub Actions for automated testing, building container images, and deploying to AWS Glue and ECS.

Refactoring type: **Tech stack migration (COBOL/CICS/VSAM/JCL → Python/PySpark/FastAPI/Aurora PostgreSQL/AWS)**

Target repository: **Same repository** (in-place modernization)

**Implicit Requirements Surfaced:**
- Maintain full behavioral parity with all 22 features (F-001 through F-022) documented in the existing specification
- Preserve all existing business logic including financial precision (COBOL `PIC S9(n)V99` → Python `Decimal`)
- Preserve the 5-stage batch pipeline sequential execution (POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥ TRANREPT)
- Maintain VSAM keyed access patterns through Aurora PostgreSQL primary keys and indexes
- Maintain API compatibility for all data contracts defined by COBOL copybooks
- Secure credentials via AWS Secrets Manager; enforce IAM-based access control for all AWS services
- Enable automated monitoring via CloudWatch for Glue jobs and ECS services

### 0.1.2 Technical Interpretation

This refactoring translates to the following technical transformation strategy:

**Current Architecture:** Monolithic z/OS mainframe application with 28 COBOL programs, 28 copybooks, 17 BMS mapsets, 17 symbolic map copybooks, 29 JCL jobs, and 9 data fixture files — all executing in a tightly coupled CICS/VSAM/JES environment on a single mainframe.

**Target Architecture:** Dual-workload cloud-native application split across two AWS compute services:

| Source Construct | Target Pattern | AWS Service |
|---|---|---|
| Batch COBOL programs (10) | PySpark ETL scripts | AWS Glue 5.1 (Spark 3.5.6, Python 3.11) |
| Online CICS programs (18) | FastAPI REST/GraphQL endpoints | AWS ECS Fargate |
| VSAM KSDS datasets (10) + AIX (3) | PostgreSQL tables (11) + B-tree indexes | AWS Aurora PostgreSQL |
| JCL batch jobs (29) | AWS Glue Job definitions + Step Functions | AWS Glue / Step Functions |
| BMS mapsets (17) | JSON request/response schemas | FastAPI Pydantic models |
| CICS COMMAREA | JWT token (stateless sessions) | FastAPI + python-jose |
| CICS TDQ (WRITEQ JOBS) | SQS FIFO queue | AWS SQS |
| GDG generations (6) | Versioned S3 objects | AWS S3 |
| COBOL copybooks (28) | Python dataclass/Pydantic models | Shared models module |
| CICS SEND/RECEIVE MAP | REST/GraphQL endpoints | FastAPI + Strawberry |

**Transformation Rules:**
- Each batch COBOL program maps to exactly one PySpark Glue job script
- Each online CICS program maps to exactly one FastAPI service module
- Each COBOL copybook record layout maps to one Python Pydantic model or dataclass
- VSAM file I/O (READ, WRITE, REWRITE, DELETE) maps to SQLAlchemy ORM operations
- COBOL `PIC S9(n)V99` fields map to Python `decimal.Decimal` with explicit scale
- CICS SYNCPOINT ROLLBACK maps to SQLAlchemy transactional context managers
- BMS field definitions map to Pydantic request/response schemas with validation
- JCL COND parameter semantics map to AWS Glue job return codes and Step Functions


## 0.2 Source Analysis


### 0.2.1 Comprehensive Source File Discovery

The source repository (`blitzy-card-demo`) contains **149 total source artifacts** organized across 12 directories. The following enumerates every source file discovered through exhaustive repository inspection.

**Current Structure Mapping:**

```
blitzy-card-demo/
├── README.md
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── LICENSE
├── mkdocs.yml
├── catalog-info.yaml
├── docs/
│   ├── index.md
│   ├── project-guide.md
│   └── technical-specifications.md
├── app/
│   ├── cbl/                          # 28 COBOL programs
│   │   ├── CBACT01C.cbl             # Batch: Account file reader
│   │   ├── CBACT02C.cbl             # Batch: Card file reader
│   │   ├── CBACT03C.cbl             # Batch: Cross-reference file reader
│   │   ├── CBACT04C.cbl             # Batch: Interest posting (INTCALC)
│   │   ├── CBCUS01C.cbl             # Batch: Customer file reader
│   │   ├── CBTRN01C.cbl             # Batch: Daily transaction driver
│   │   ├── CBTRN02C.cbl             # Batch: Transaction posting engine (POSTTRAN)
│   │   ├── CBTRN03C.cbl             # Batch: Transaction reporting (TRANREPT)
│   │   ├── CBSTM03A.CBL             # Batch: Statement generation driver (CREASTMT)
│   │   ├── CBSTM03B.CBL             # Batch: File service subroutine for CREASTMT
│   │   ├── COSGN00C.cbl             # Online: Sign-on/authentication
│   │   ├── COMEN01C.cbl             # Online: Main menu (10 options)
│   │   ├── COADM01C.cbl             # Online: Admin menu (4 options)
│   │   ├── COACTVWC.cbl             # Online: Account view
│   │   ├── COACTUPC.cbl             # Online: Account update (4,236 lines, SYNCPOINT ROLLBACK)
│   │   ├── COCRDLIC.cbl             # Online: Card list (7 rows/page)
│   │   ├── COCRDSLC.cbl             # Online: Card detail view
│   │   ├── COCRDUPC.cbl             # Online: Card update (optimistic concurrency)
│   │   ├── COTRN00C.cbl             # Online: Transaction list (10 rows/page)
│   │   ├── COTRN01C.cbl             # Online: Transaction detail view
│   │   ├── COTRN02C.cbl             # Online: Transaction add
│   │   ├── COBIL00C.cbl             # Online: Bill payment (dual-write)
│   │   ├── CORPT00C.cbl             # Online: Report submission (TDQ bridge)
│   │   ├── COUSR00C.cbl             # Online: User list
│   │   ├── COUSR01C.cbl             # Online: User add (BCrypt)
│   │   ├── COUSR02C.cbl             # Online: User update
│   │   ├── COUSR03C.cbl             # Online: User delete
│   │   └── CSUTLDTC.cbl             # Online: Date validation utility
│   ├── cpy/                          # 28 copybooks
│   │   ├── CVACT01Y.cpy             # Account record layout (300B)
│   │   ├── CVACT02Y.cpy             # Card record layout (150B)
│   │   ├── CVACT03Y.cpy             # Card cross-reference layout (50B)
│   │   ├── CVCUS01Y.cpy             # Customer record layout (500B)
│   │   ├── CVCRD01Y.cpy             # Card work area
│   │   ├── CVTRA01Y.cpy             # Transaction category balance (50B)
│   │   ├── CVTRA02Y.cpy             # Disclosure group layout (50B)
│   │   ├── CVTRA03Y.cpy             # Transaction type layout (60B)
│   │   ├── CVTRA04Y.cpy             # Transaction category layout (60B)
│   │   ├── CVTRA05Y.cpy             # Transaction record (350B)
│   │   ├── CVTRA06Y.cpy             # Daily transaction staging (350B)
│   │   ├── CVTRA07Y.cpy             # Transaction index
│   │   ├── CUSTREC.cpy              # Customer record (alternate view)
│   │   ├── COSTM01.cpy              # Statement record
│   │   ├── CSUSR01Y.cpy             # User security record (80B)
│   │   ├── COCOM01Y.cpy             # COMMAREA communication block
│   │   ├── COMEN02Y.cpy             # Main menu options
│   │   ├── COADM02Y.cpy             # Admin menu options
│   │   ├── COTTL01Y.cpy             # Screen title text
│   │   ├── CSMSG01Y.cpy             # System messages (set 1)
│   │   ├── CSMSG02Y.cpy             # System messages (set 2)
│   │   ├── CSDAT01Y.cpy             # Date formats
│   │   ├── CSUTLDWY.cpy             # Date utility work area
│   │   ├── CSUTLDPY.cpy             # Date utility parameters
│   │   ├── CSLKPCDY.cpy             # Lookup codes
│   │   ├── CSSETATY.cpy             # CICS SET ATTRIBUTE helper
│   │   ├── CSSTRPFY.cpy             # String processing functions
│   │   └── UNUSED1Y.cpy             # Unused copybook
│   ├── bms/                          # 17 BMS mapsets
│   │   ├── COSGN00.bms              # Sign-on screen map
│   │   ├── COMEN01.bms              # Main menu map
│   │   ├── COADM01.bms              # Admin menu map
│   │   ├── COACTVW.bms              # Account view map
│   │   ├── COACTUP.bms              # Account update map
│   │   ├── COCRDLI.bms              # Card list map
│   │   ├── COCRDSL.bms              # Card detail map
│   │   ├── COCRDUP.bms              # Card update map
│   │   ├── COTRN00.bms              # Transaction list map
│   │   ├── COTRN01.bms              # Transaction detail map
│   │   ├── COTRN02.bms              # Transaction add map
│   │   ├── COBIL00.bms              # Bill payment map
│   │   ├── CORPT00.bms              # Report submission map
│   │   ├── COUSR00.bms              # User list map
│   │   ├── COUSR01.bms              # User add map
│   │   ├── COUSR02.bms              # User update map
│   │   └── COUSR03.bms              # User delete map
│   ├── cpy-bms/                      # 17 symbolic map copybooks
│   │   ├── COSGN00.CPY              # Sign-on symbolic map (AI/AO)
│   │   ├── COMEN01.CPY              # Main menu symbolic map
│   │   ├── COADM01.CPY              # Admin menu symbolic map
│   │   ├── COACTVW.CPY              # Account view symbolic map
│   │   ├── COACTUP.CPY              # Account update symbolic map
│   │   ├── COCRDLI.CPY              # Card list symbolic map
│   │   ├── COCRDSL.CPY              # Card detail symbolic map
│   │   ├── COCRDUP.CPY              # Card update symbolic map
│   │   ├── COTRN00.CPY              # Transaction list symbolic map
│   │   ├── COTRN01.CPY              # Transaction detail symbolic map
│   │   ├── COTRN02.CPY              # Transaction add symbolic map
│   │   ├── COBIL00.CPY              # Bill payment symbolic map
│   │   ├── CORPT00.CPY              # Report submission symbolic map
│   │   ├── COUSR00.CPY              # User list symbolic map
│   │   ├── COUSR01.CPY              # User add symbolic map
│   │   ├── COUSR02.CPY              # User update symbolic map
│   │   └── COUSR03.CPY              # User delete symbolic map
│   ├── jcl/                          # 29 JCL job members
│   │   ├── ACCTFILE.jcl             # Account VSAM provisioning
│   │   ├── CARDFILE.jcl             # Card VSAM provisioning
│   │   ├── CUSTFILE.jcl             # Customer VSAM provisioning
│   │   ├── TRANFILE.jcl             # Transaction VSAM provisioning
│   │   ├── XREFFILE.jcl             # Cross-reference VSAM provisioning
│   │   ├── TCATBALF.jcl             # Category balance VSAM provisioning
│   │   ├── TRANCATG.jcl             # Transaction category load
│   │   ├── TRANTYPE.jcl             # Transaction type load
│   │   ├── DISCGRP.jcl              # Disclosure group load
│   │   ├── DUSRSECJ.jcl             # User security load
│   │   ├── DEFCUST.jcl              # Customer definition
│   │   ├── TRANIDX.jcl              # Transaction index provisioning
│   │   ├── DEFGDGB.jcl              # GDG base definitions
│   │   ├── REPTFILE.jcl             # Report GDG provisioning
│   │   ├── DALYREJS.jcl             # Daily reject GDG provisioning
│   │   ├── POSTTRAN.jcl             # Stage 1: Transaction posting
│   │   ├── INTCALC.jcl              # Stage 2: Interest calculation
│   │   ├── COMBTRAN.jcl             # Stage 3: DFSORT merge
│   │   ├── TRANREPT.jcl             # Stage 4b: Transaction report
│   │   ├── CREASTMT.jcl             # Stage 4a: Statement creation
│   │   ├── PRTCATBL.jcl             # Print category balance
│   │   ├── CBADMCDJ.jcl             # CICS admin batch utility
│   │   ├── CLOSEFIL.jcl             # CICS file close utility
│   │   ├── OPENFIL.jcl              # CICS file open utility
│   │   ├── TRANBKP.jcl              # Transaction backup
│   │   ├── READACCT.jcl             # Read account utility
│   │   ├── READCARD.jcl             # Read card utility
│   │   ├── READCUST.jcl             # Read customer utility
│   │   └── READXREF.jcl             # Read cross-reference utility
│   ├── data/ASCII/                   # 9 fixture data files
│   │   ├── acctdata.txt              # 50 accounts (300-byte records)
│   │   ├── carddata.txt              # 50 cards (150-byte records)
│   │   ├── custdata.txt              # 50 customers (500-byte records)
│   │   ├── cardxref.txt              # 50 cross-references (36-char)
│   │   ├── dailytran.txt             # Daily transactions
│   │   ├── tcatbal.txt               # 50 category balances
│   │   ├── discgrp.txt               # 51 disclosure group records (3 blocks)
│   │   ├── trancatg.txt              # 18 category mappings
│   │   └── trantype.txt              # 7 transaction type mappings
│   └── catlg/
│       └── LISTCAT.txt               # IDCAMS catalog report (209 entries)
└── samples/
    └── jcl/                          # 3 sample JCL
        ├── BATCMP.jcl                # Batch COBOL compile
        ├── BMSCMP.jcl                # BMS map compile + CICS NEWCOPY
        └── CICCMP.jcl                # CICS COBOL compile + NEWCOPY
```

### 0.2.2 Batch Program Classification (10 Programs → PySpark on AWS Glue)

| Source File | Lines (approx) | Function | Batch Pipeline Stage | Key Datasets |
|---|---|---|---|---|
| `app/cbl/CBTRN02C.cbl` | ~800 | Transaction posting engine | Stage 1 (POSTTRAN) | TRANFILE, XREFFILE, ACCTFILE, TCATBALF |
| `app/cbl/CBACT04C.cbl` | ~400 | Interest calculation | Stage 2 (INTCALC) | ACCTFILE, TCATBALF, DISCGRP |
| `app/cbl/CBSTM03A.CBL` | ~600 | Statement generation driver | Stage 4a (CREASTMT) | ACCTFILE, CUSTFILE, TRANFILE, XREFFILE |
| `app/cbl/CBSTM03B.CBL` | ~300 | File service subroutine for statement generation | Stage 4a (CREASTMT) | GDG output files |
| `app/cbl/CBTRN03C.cbl` | ~500 | Transaction reporting with 3-level totals | Stage 4b (TRANREPT) | TRANFILE, ACCTFILE, XREFFILE |
| `app/cbl/CBTRN01C.cbl` | ~300 | Daily transaction driver | Pre-pipeline | DAILYTRAN |
| `app/cbl/CBACT01C.cbl` | ~200 | Account file reader/display | Utility | ACCTFILE |
| `app/cbl/CBACT02C.cbl` | ~200 | Card file reader/display | Utility | CARDFILE |
| `app/cbl/CBACT03C.cbl` | ~200 | Cross-reference file reader | Utility | XREFFILE |
| `app/cbl/CBCUS01C.cbl` | ~200 | Customer file reader/display | Utility | CUSTFILE |

### 0.2.3 Online CICS Program Classification (18 Programs → REST/GraphQL APIs on AWS ECS)

| Source File | Lines (approx) | Function | Feature ID(s) | Key Entities |
|---|---|---|---|---|
| `app/cbl/COSGN00C.cbl` | ~400 | Sign-on/authentication | F-001 | UserSecurity |
| `app/cbl/COMEN01C.cbl` | ~300 | Main menu (10 options) | F-002 | Navigation state |
| `app/cbl/COADM01C.cbl` | ~250 | Admin menu (4 options) | F-003 | Navigation state |
| `app/cbl/COACTVWC.cbl` | ~600 | Account view (3-entity join) | F-004 | Account, Customer, CardCrossReference |
| `app/cbl/COACTUPC.cbl` | ~4,236 | Account update (SYNCPOINT ROLLBACK) | F-005 | Account, Customer |
| `app/cbl/COCRDLIC.cbl` | ~500 | Card list (7 rows/page) | F-006 | Card, CardCrossReference |
| `app/cbl/COCRDSLC.cbl` | ~400 | Card detail view | F-007 | Card |
| `app/cbl/COCRDUPC.cbl` | ~500 | Card update (optimistic concurrency) | F-008 | Card |
| `app/cbl/COTRN00C.cbl` | ~500 | Transaction list (10 rows/page) | F-009 | Transaction |
| `app/cbl/COTRN01C.cbl` | ~400 | Transaction detail view | F-010 | Transaction |
| `app/cbl/COTRN02C.cbl` | ~600 | Transaction add (auto-ID, xref resolution) | F-011 | Transaction, CardCrossReference |
| `app/cbl/COBIL00C.cbl` | ~500 | Bill payment (dual-write) | F-012 | Transaction, Account |
| `app/cbl/CORPT00C.cbl` | ~400 | Report submission (TDQ→SQS bridge) | F-022 | TDQ→SQS FIFO |
| `app/cbl/COUSR00C.cbl` | ~400 | User list | F-018 | UserSecurity |
| `app/cbl/COUSR01C.cbl` | ~400 | User add (BCrypt) | F-019 | UserSecurity |
| `app/cbl/COUSR02C.cbl` | ~400 | User update | F-020 | UserSecurity |
| `app/cbl/COUSR03C.cbl` | ~400 | User delete | F-021 | UserSecurity |
| `app/cbl/CSUTLDTC.cbl` | ~300 | Date validation utility | Shared | Date formats |

### 0.2.4 Supporting Artifacts

- **28 Copybooks** (`app/cpy/*.cpy`): Record layouts defining data structures for all VSAM files, COMMAREA, screen text, date formatting, and CICS helpers. Each must be translated to a Python Pydantic model or dataclass.
- **17 BMS Mapsets** (`app/bms/*.bms`): Screen layout definitions replaced by JSON-based REST/GraphQL schemas.
- **17 Symbolic Map Copybooks** (`app/cpy-bms/*.CPY`): Generated input (AI) / output (AO) layouts mapping BMS fields — provide the exact field names, positions, and attributes that define the API request/response contracts.
- **29 JCL Job Members** (`app/jcl/*.jcl`): Define VSAM provisioning, GDG setup, batch pipeline orchestration, CICS file administration, and data utilities. These translate to Aurora PostgreSQL DDL, S3 bucket policies, AWS Glue job configurations, and Step Functions.
- **9 ASCII Fixture Data Files** (`app/data/ASCII/*.txt`): Seed data for initial database loading into Aurora PostgreSQL — 50 accounts, 50 cards, 50 customers, 50 cross-references, daily transactions, category balances, disclosure groups, category mappings, type mappings.
- **1 IDCAMS Catalog Report** (`app/catlg/LISTCAT.txt`): Documents all 209 VSAM catalog entries (160 NONVSAM, 10 CLUSTER, 13 DATA, 13 INDEX, 7 GDG, 3 AIX, 3 PATH).


## 0.3 Scope Boundaries


### 0.3.1 Exhaustively In Scope

**Source Transformations (COBOL → Python/PySpark):**
- `app/cbl/CB*.cbl` — All 10 batch COBOL programs → PySpark Glue job scripts
- `app/cbl/CB*.CBL` — Statement generation programs (CBSTM03A.CBL, CBSTM03B.CBL) → PySpark Glue job scripts
- `app/cbl/CO*.cbl` — All 18 online CICS COBOL programs → FastAPI REST/GraphQL endpoint modules
- `app/cbl/CS*.cbl` — Utility programs (CSUTLDTC.cbl) → Python shared utility modules

**Data Model Transformations (Copybooks → Python Models):**
- `app/cpy/CV*.cpy` — All VSAM record layout copybooks → SQLAlchemy ORM models + Pydantic schemas
- `app/cpy/CSUSR01Y.cpy` — User security record → SQLAlchemy model + Pydantic schema
- `app/cpy/COCOM01Y.cpy` — COMMAREA communication block → JWT token payload schema
- `app/cpy/CUSTREC.cpy` — Customer record alternate view → Pydantic model
- `app/cpy/COSTM01.cpy` — Statement record → PySpark output schema
- `app/cpy/COMEN02Y.cpy` — Main menu options → API menu/navigation configuration
- `app/cpy/COADM02Y.cpy` — Admin menu options → API admin route configuration
- `app/cpy/COTTL01Y.cpy` — Screen title text → API response metadata constants
- `app/cpy/CSMSG01Y.cpy` — System messages (set 1) → API error/success message constants
- `app/cpy/CSMSG02Y.cpy` — System messages (set 2) → API error/success message constants
- `app/cpy/CSDAT01Y.cpy` — Date formats → Python date formatting utility
- `app/cpy/CSUTLDWY.cpy` — Date utility work area → Date utility helper module
- `app/cpy/CSUTLDPY.cpy` — Date utility parameters → Date utility configuration
- `app/cpy/CSLKPCDY.cpy` — Lookup codes → API lookup/reference constants
- `app/cpy/CSSETATY.cpy` — CICS SET ATTRIBUTE helper → Not applicable (no CICS in target)
- `app/cpy/CSSTRPFY.cpy` — String processing → Python string utilities
- `app/cpy/CVCRD01Y.cpy` — Card work area → Pydantic intermediate model
- `app/cpy/UNUSED1Y.cpy` — Unused copybook → Excluded from migration

**API Contract Definitions (BMS/Symbolic Maps → JSON Schemas):**
- `app/bms/*.bms` — All 17 BMS mapsets → FastAPI Pydantic request/response models
- `app/cpy-bms/*.CPY` — All 17 symbolic map copybooks → Field-level API contract specifications

**Batch Job Orchestration (JCL → AWS Glue/Step Functions):**
- `app/jcl/POSTTRAN.jcl` — Stage 1 orchestration → AWS Glue job + Step Functions
- `app/jcl/INTCALC.jcl` — Stage 2 orchestration → AWS Glue job + Step Functions
- `app/jcl/COMBTRAN.jcl` — Stage 3 (DFSORT+REPRO) → AWS Glue job (pure PySpark)
- `app/jcl/CREASTMT.jcl` — Stage 4a orchestration → AWS Glue job + Step Functions
- `app/jcl/TRANREPT.jcl` — Stage 4b orchestration → AWS Glue job + Step Functions
- `app/jcl/PRTCATBL.jcl` — Category balance print → AWS Glue job
- `app/jcl/ACCTFILE.jcl` — VSAM account provisioning → Aurora PostgreSQL DDL + Flyway
- `app/jcl/CARDFILE.jcl` — VSAM card provisioning → Aurora PostgreSQL DDL + Flyway
- `app/jcl/CUSTFILE.jcl` — VSAM customer provisioning → Aurora PostgreSQL DDL + Flyway
- `app/jcl/TRANFILE.jcl` — VSAM transaction provisioning → Aurora PostgreSQL DDL + Flyway
- `app/jcl/XREFFILE.jcl` — VSAM cross-reference provisioning → Aurora PostgreSQL DDL + Flyway
- `app/jcl/TCATBALF.jcl` — Category balance VSAM → Aurora PostgreSQL DDL + Flyway
- `app/jcl/TRANCATG.jcl` — Category mapping load → Flyway seed data
- `app/jcl/TRANTYPE.jcl` — Type mapping load → Flyway seed data
- `app/jcl/DISCGRP.jcl` — Disclosure group load → Flyway seed data
- `app/jcl/DUSRSECJ.jcl` — User security load → Flyway seed data
- `app/jcl/DEFCUST.jcl` — Customer definitions → Flyway seed data
- `app/jcl/TRANIDX.jcl` — Transaction index → Aurora PostgreSQL B-tree index DDL
- `app/jcl/DEFGDGB.jcl` — GDG definitions → S3 bucket versioning configuration
- `app/jcl/REPTFILE.jcl` — Report GDG → S3 versioned path
- `app/jcl/DALYREJS.jcl` — Daily reject GDG → S3 versioned path
- `app/jcl/TRANBKP.jcl` — Transaction backup → S3 versioned snapshot
- `app/jcl/CBADMCDJ.jcl` — CICS admin utility → Not applicable (no CICS in target)
- `app/jcl/CLOSEFIL.jcl` — CICS file close → Not applicable (no CICS in target)
- `app/jcl/OPENFIL.jcl` — CICS file open → Not applicable (no CICS in target)
- `app/jcl/READACCT.jcl` — Read account utility → PySpark diagnostic job
- `app/jcl/READCARD.jcl` — Read card utility → PySpark diagnostic job
- `app/jcl/READCUST.jcl` — Read customer utility → PySpark diagnostic job
- `app/jcl/READXREF.jcl` — Read cross-reference utility → PySpark diagnostic job

**Data Fixture Migration:**
- `app/data/ASCII/*.txt` — All 9 fixture files → PostgreSQL seed SQL scripts (Flyway V3)

**Infrastructure and Deployment:**
- `Dockerfile` — New container definition for FastAPI service on ECS
- `docker-compose.yml` — New local development configuration
- `.github/workflows/*.yml` — New CI/CD pipelines for GitHub Actions
- Database migration scripts (Flyway or Alembic) for Aurora PostgreSQL schema

**Test Suite (New):**
- Unit tests for all PySpark transformation functions
- Unit tests for all FastAPI endpoints
- Integration tests for database operations
- End-to-end pipeline tests for batch workflow

**Documentation Updates:**
- `README.md` — Update with new architecture, setup, and deployment instructions
- `docs/*.md` — Update all documentation to reflect new Python/AWS architecture

### 0.3.2 Explicitly Out of Scope

- **CICS-specific infrastructure artifacts** — BMS compile JCL (`samples/jcl/BMSCMP.jcl`, `samples/jcl/CICCMP.jcl`), CICS NEWCOPY commands, and CICS file open/close operations have no equivalent in the target architecture
- **UNUSED1Y.cpy** — Confirmed unused copybook; excluded from migration
- **CSSETATY.cpy** — CICS SET ATTRIBUTE helper; CICS-specific, no target equivalent
- **IDCAMS catalog report** (`app/catlg/LISTCAT.txt`) — Historical z/OS artifact; not migrated, retained for traceability
- **Mainframe compile/link JCL** (`samples/jcl/BATCMP.jcl`) — z/OS-specific build process replaced by GitHub Actions
- **BMS mapset rendering** — BMS screen painting is replaced entirely by REST/GraphQL API contracts; no UI rendering layer is being built (APIs are headless)
- **Any existing Java/Spring Boot migration code** — The existing tech spec references a Java 25/Spring Boot 3.5.11 migration target; this is a distinct migration path not part of the current scope. The user's target is explicitly Python/PySpark/FastAPI
- **Backstage catalog** (`catalog-info.yaml`) — Service catalog metadata; may be updated but not functionally migrated
- **MkDocs configuration** (`mkdocs.yml`) — Documentation tooling; updated as needed but not a migration target


## 0.4 Target Design


### 0.4.1 Refactored Structure Planning

The target architecture organizes the application into a clean Python-based project with clear separation between the two workload types (batch PySpark and online API), shared models, database migrations, infrastructure configuration, and tests.

**Target Architecture:**

```
carddemo/
├── README.md
├── LICENSE
├── pyproject.toml
├── requirements.txt
├── requirements-api.txt
├── requirements-glue.txt
├── requirements-dev.txt
├── Dockerfile
├── docker-compose.yml
├── .github/
│   └── workflows/
│       ├── ci.yml
│       ├── deploy-api.yml
│       └── deploy-glue.yml
├── docs/
│   ├── index.md
│   ├── project-guide.md
│   ├── technical-specifications.md
│   └── architecture.md
├── db/
│   └── migrations/
│       ├── V1__schema.sql
│       ├── V2__indexes.sql
│       └── V3__seed_data.sql
├── src/
│   ├── shared/
│   │   ├── __init__.py
│   │   ├── models/
│   │   │   ├── __init__.py
│   │   │   ├── account.py
│   │   │   ├── card.py
│   │   │   ├── customer.py
│   │   │   ├── card_cross_reference.py
│   │   │   ├── transaction.py
│   │   │   ├── transaction_category_balance.py
│   │   │   ├── daily_transaction.py
│   │   │   ├── disclosure_group.py
│   │   │   ├── transaction_type.py
│   │   │   ├── transaction_category.py
│   │   │   └── user_security.py
│   │   ├── schemas/
│   │   │   ├── __init__.py
│   │   │   ├── account_schema.py
│   │   │   ├── card_schema.py
│   │   │   ├── customer_schema.py
│   │   │   ├── transaction_schema.py
│   │   │   ├── user_schema.py
│   │   │   ├── bill_schema.py
│   │   │   ├── report_schema.py
│   │   │   └── auth_schema.py
│   │   ├── constants/
│   │   │   ├── __init__.py
│   │   │   ├── messages.py
│   │   │   ├── lookup_codes.py
│   │   │   └── menu_options.py
│   │   ├── utils/
│   │   │   ├── __init__.py
│   │   │   ├── date_utils.py
│   │   │   ├── string_utils.py
│   │   │   └── decimal_utils.py
│   │   └── config/
│   │       ├── __init__.py
│   │       ├── settings.py
│   │       └── aws_config.py
│   ├── api/
│   │   ├── __init__.py
│   │   ├── main.py
│   │   ├── dependencies.py
│   │   ├── database.py
│   │   ├── middleware/
│   │   │   ├── __init__.py
│   │   │   ├── auth.py
│   │   │   └── error_handler.py
│   │   ├── routers/
│   │   │   ├── __init__.py
│   │   │   ├── auth_router.py
│   │   │   ├── account_router.py
│   │   │   ├── card_router.py
│   │   │   ├── transaction_router.py
│   │   │   ├── bill_router.py
│   │   │   ├── report_router.py
│   │   │   ├── user_router.py
│   │   │   └── admin_router.py
│   │   ├── services/
│   │   │   ├── __init__.py
│   │   │   ├── auth_service.py
│   │   │   ├── account_service.py
│   │   │   ├── card_service.py
│   │   │   ├── transaction_service.py
│   │   │   ├── bill_service.py
│   │   │   ├── report_service.py
│   │   │   └── user_service.py
│   │   └── graphql/
│   │       ├── __init__.py
│   │       ├── schema.py
│   │       ├── types/
│   │       │   ├── __init__.py
│   │       │   ├── account_type.py
│   │       │   ├── card_type.py
│   │       │   ├── transaction_type.py
│   │       │   └── user_type.py
│   │       ├── queries.py
│   │       └── mutations.py
│   └── batch/
│       ├── __init__.py
│       ├── common/
│       │   ├── __init__.py
│       │   ├── glue_context.py
│       │   ├── db_connector.py
│       │   └── s3_utils.py
│       ├── jobs/
│       │   ├── __init__.py
│       │   ├── posttran_job.py
│       │   ├── intcalc_job.py
│       │   ├── combtran_job.py
│       │   ├── creastmt_job.py
│       │   ├── tranrept_job.py
│       │   ├── prtcatbl_job.py
│       │   ├── daily_tran_driver_job.py
│       │   ├── read_account_job.py
│       │   ├── read_card_job.py
│       │   ├── read_customer_job.py
│       │   └── read_xref_job.py
│       └── pipeline/
│           ├── __init__.py
│           └── step_functions_definition.json
├── tests/
│   ├── __init__.py
│   ├── conftest.py
│   ├── unit/
│   │   ├── __init__.py
│   │   ├── test_models/
│   │   │   └── ...
│   │   ├── test_services/
│   │   │   └── ...
│   │   ├── test_routers/
│   │   │   └── ...
│   │   └── test_batch/
│   │       └── ...
│   ├── integration/
│   │   ├── __init__.py
│   │   ├── test_database.py
│   │   └── test_api_endpoints.py
│   └── e2e/
│       ├── __init__.py
│       └── test_batch_pipeline.py
├── infra/
│   ├── ecs-task-definition.json
│   ├── glue-job-configs/
│   │   ├── posttran.json
│   │   ├── intcalc.json
│   │   ├── combtran.json
│   │   ├── creastmt.json
│   │   └── tranrept.json
│   └── cloudwatch/
│       └── dashboard.json
└── app/                              # Retained for traceability
    ├── cbl/
    ├── cpy/
    ├── bms/
    ├── cpy-bms/
    ├── jcl/
    ├── data/
    └── catlg/
```

### 0.4.2 Web Search Research Conducted

Research was conducted on the following topics to inform the target architecture:

- **AWS Glue 5.1** — The latest generally available version, upgrading to Apache Spark 3.5.6, Python 3.11, and Scala 2.12.18 with improved performance and security. Supports native JDBC connections to Aurora PostgreSQL and S3 output in Parquet format.
- **PySpark on AWS Glue best practices** — Use `DynamicFrame` for schema flexibility; leverage `push_down_predicate` for partition pruning; output in columnar Parquet format; use job bookmarks for incremental processing; G.1X workers for standard loads, G.2X for memory-intensive operations.
- **COBOL to Python migration patterns** — Functional equivalence over one-to-one rewriting; COBOL paragraph structure maps to Python functions; `PIC S9(n)V99` maps to Python `Decimal`; COBOL sequential file I/O maps to DataFrame read/write operations; copybook record layouts map to dataclasses.
- **FastAPI on AWS ECS** — Containerize with Docker; deploy behind ALB on ECS Fargate; use Uvicorn as ASGI server; integrate with Aurora PostgreSQL via SQLAlchemy async ORM; add Strawberry for GraphQL support alongside REST. Python 3.11+ recommended.
- **Aurora PostgreSQL with Python** — Use `psycopg2-binary` or `psycopg` driver with SQLAlchemy ORM; use AWS Secrets Manager for credential retrieval via `boto3`; IAM authentication supported for zero-password connections.

### 0.4.3 Design Pattern Applications

| Pattern | Application | Source Construct Replaced |
|---|---|---|
| **Repository Pattern** | SQLAlchemy ORM models encapsulate all data access, replacing VSAM READ/WRITE/REWRITE/DELETE | Direct VSAM file I/O |
| **Service Layer** | Service classes encapsulate business logic, separating it from API routing | COBOL PROCEDURE DIVISION paragraphs |
| **Dependency Injection** | FastAPI's `Depends()` for database sessions, auth tokens, and service instances | CICS COMMAREA passing |
| **Factory Pattern** | Pydantic model factories for constructing response objects from database entities | COBOL MOVE statements building screen maps |
| **Pipeline Pattern** | AWS Step Functions orchestrating sequential/parallel Glue jobs | JCL COND parameter chaining |
| **Transactional Outbox** | SQLAlchemy session context managers with rollback-on-exception | CICS SYNCPOINT ROLLBACK |
| **Optimistic Concurrency** | SQLAlchemy `@version` column on Card and Account models | CICS READ UPDATE / REWRITE |
| **Stateless Authentication** | JWT tokens replacing CICS COMMAREA session state | CICS RETURN TRANSID COMMAREA |

### 0.4.4 Key Architectural Decisions

**Batch Layer (AWS Glue):**
- Each batch COBOL program becomes one PySpark script in `src/batch/jobs/`
- AWS Glue 5.1 (Spark 3.5.6, Python 3.11) as the runtime engine
- Aurora PostgreSQL JDBC connectivity for reading/writing data directly
- S3 for statement output files (replacing GDG) and reject logs
- Step Functions for pipeline orchestration (replacing JCL job sequencing)
- The COMBTRAN stage (Stage 3) — which uses DFSORT+REPRO with no COBOL program — translates to a pure PySpark merge/sort job

**API Layer (AWS ECS):**
- FastAPI as the primary web framework, supporting both REST and GraphQL (via Strawberry)
- Deployed as a Docker container on ECS Fargate
- SQLAlchemy 2.x as async ORM connecting to Aurora PostgreSQL
- JWT-based authentication (replacing CICS COMMAREA session)
- BCrypt password hashing (preserving existing security behavior)
- Pydantic v2 for all request/response validation
- AWS SQS FIFO for report submission queue (replacing CICS TDQ WRITEQ)

**Database Layer (Aurora PostgreSQL):**
- 11 tables mapping the 10 VSAM clusters + 3 AIX to normalized relational tables
- Flyway-style SQL migration scripts for schema, indexes, and seed data
- All monetary fields stored as `NUMERIC(15,2)` preserving COBOL decimal precision
- Composite primary keys for TransactionCategoryBalance, DisclosureGroup, TransactionCategory

**Deployment (GitHub Actions):**
- CI pipeline: lint → type-check → unit tests → integration tests
- API deployment: build Docker image → push to ECR → update ECS service
- Glue deployment: upload PySpark scripts to S3 → update Glue job definitions


## 0.5 Transformation Mapping


### 0.5.1 File-by-File Transformation Plan

The entire refactor will be executed by Blitzy in **ONE phase**. Every target file is mapped to its source file(s). Transformation modes:
- **CREATE** — New file created from scratch or by converting a COBOL source
- **UPDATE** — Existing file modified in place
- **REFERENCE** — Source used as a pattern or example

**Project Configuration and Infrastructure Files:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `pyproject.toml` | CREATE | `app/cbl/*.cbl` (overall project context) | Python project metadata, dependency groups, build configuration |
| `requirements.txt` | CREATE | (none — new) | Core shared dependencies |
| `requirements-api.txt` | CREATE | (none — new) | FastAPI, SQLAlchemy, Pydantic, Uvicorn, Strawberry, etc. |
| `requirements-glue.txt` | CREATE | (none — new) | PySpark, aws-glue-libs, boto3 |
| `requirements-dev.txt` | CREATE | (none — new) | pytest, pytest-asyncio, httpx, moto, etc. |
| `Dockerfile` | CREATE | (none — new) | Python 3.11-slim, FastAPI + Uvicorn, port 80 |
| `docker-compose.yml` | CREATE | (none — new) | Local dev: API service + PostgreSQL + LocalStack |
| `.github/workflows/ci.yml` | CREATE | (none — new) | Lint, type-check, test (pytest), coverage |
| `.github/workflows/deploy-api.yml` | CREATE | (none — new) | Build Docker → ECR → update ECS service |
| `.github/workflows/deploy-glue.yml` | CREATE | (none — new) | Upload PySpark scripts to S3 → update Glue jobs |
| `README.md` | UPDATE | `README.md` | Update architecture, setup, run, and deploy instructions |

**Database Migration Scripts:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `db/migrations/V1__schema.sql` | CREATE | `app/jcl/ACCTFILE.jcl`, `app/jcl/CARDFILE.jcl`, `app/jcl/CUSTFILE.jcl`, `app/jcl/TRANFILE.jcl`, `app/jcl/XREFFILE.jcl`, `app/jcl/TCATBALF.jcl`, `app/jcl/DUSRSECJ.jcl` | CREATE TABLE for all 11 entities from VSAM DEFINE CLUSTER |
| `db/migrations/V2__indexes.sql` | CREATE | `app/jcl/TRANIDX.jcl`, `app/catlg/LISTCAT.txt` | B-tree indexes for 3 AIX paths: card.acct_id, card_cross_reference.acct_id, transaction.proc_ts |
| `db/migrations/V3__seed_data.sql` | CREATE | `app/data/ASCII/acctdata.txt`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/custdata.txt`, `app/data/ASCII/cardxref.txt`, `app/data/ASCII/tcatbal.txt`, `app/data/ASCII/discgrp.txt`, `app/data/ASCII/trancatg.txt`, `app/data/ASCII/trantype.txt` | INSERT statements for 50 accounts, 50 cards, 50 customers, 50 xrefs, reference data |

**Shared Models (Copybook → SQLAlchemy ORM):**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/shared/models/__init__.py` | CREATE | (none — new) | Module init, Base declarative class |
| `src/shared/models/account.py` | CREATE | `app/cpy/CVACT01Y.cpy` | Account entity: 11-digit PK, NUMERIC(15,2) balance fields, @Version |
| `src/shared/models/card.py` | CREATE | `app/cpy/CVACT02Y.cpy` | Card entity: 16-char PK, expiry date, status, @Version |
| `src/shared/models/customer.py` | CREATE | `app/cpy/CVCUS01Y.cpy` | Customer entity: 9-digit PK, encrypted SSN field |
| `src/shared/models/card_cross_reference.py` | CREATE | `app/cpy/CVACT03Y.cpy` | CardCrossReference: 16-char PK linking card↔account |
| `src/shared/models/transaction.py` | CREATE | `app/cpy/CVTRA05Y.cpy` | Transaction: sequence PK, NUMERIC(15,2) amount |
| `src/shared/models/transaction_category_balance.py` | CREATE | `app/cpy/CVTRA01Y.cpy` | 3-part composite PK: acct_id + type_code + cat_code |
| `src/shared/models/daily_transaction.py` | CREATE | `app/cpy/CVTRA06Y.cpy` | DailyTransaction staging entity |
| `src/shared/models/disclosure_group.py` | CREATE | `app/cpy/CVTRA02Y.cpy` | 3-part composite PK, DEFAULT/ZEROAPR groups |
| `src/shared/models/transaction_type.py` | CREATE | `app/cpy/CVTRA03Y.cpy` | 2-char PK, description |
| `src/shared/models/transaction_category.py` | CREATE | `app/cpy/CVTRA04Y.cpy` | 2-part composite PK |
| `src/shared/models/user_security.py` | CREATE | `app/cpy/CSUSR01Y.cpy` | 8-char PK, BCrypt hashed password |

**Shared Pydantic Schemas (BMS Symbolic Maps → API Contracts):**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/shared/schemas/__init__.py` | CREATE | (none — new) | Module init |
| `src/shared/schemas/account_schema.py` | CREATE | `app/cpy-bms/COACTVW.CPY`, `app/cpy-bms/COACTUP.CPY` | AccountView, AccountUpdate request/response Pydantic models |
| `src/shared/schemas/card_schema.py` | CREATE | `app/cpy-bms/COCRDLI.CPY`, `app/cpy-bms/COCRDSL.CPY`, `app/cpy-bms/COCRDUP.CPY` | CardList, CardDetail, CardUpdate schemas |
| `src/shared/schemas/transaction_schema.py` | CREATE | `app/cpy-bms/COTRN00.CPY`, `app/cpy-bms/COTRN01.CPY`, `app/cpy-bms/COTRN02.CPY` | TransactionList, TransactionDetail, TransactionAdd schemas |
| `src/shared/schemas/user_schema.py` | CREATE | `app/cpy-bms/COUSR00.CPY`, `app/cpy-bms/COUSR01.CPY`, `app/cpy-bms/COUSR02.CPY`, `app/cpy-bms/COUSR03.CPY` | UserList, UserCreate, UserUpdate, UserDelete schemas |
| `src/shared/schemas/bill_schema.py` | CREATE | `app/cpy-bms/COBIL00.CPY` | BillPayment request/response schema |
| `src/shared/schemas/report_schema.py` | CREATE | `app/cpy-bms/CORPT00.CPY` | ReportSubmission request/response schema |
| `src/shared/schemas/auth_schema.py` | CREATE | `app/cpy-bms/COSGN00.CPY`, `app/cpy/COCOM01Y.cpy` | SignOn request, JWT token response |

**Shared Constants and Utilities:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/shared/constants/__init__.py` | CREATE | (none — new) | Module init |
| `src/shared/constants/messages.py` | CREATE | `app/cpy/CSMSG01Y.cpy`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/COTTL01Y.cpy` | Error/success message constants |
| `src/shared/constants/lookup_codes.py` | CREATE | `app/cpy/CSLKPCDY.cpy` | Lookup code constants |
| `src/shared/constants/menu_options.py` | CREATE | `app/cpy/COMEN02Y.cpy`, `app/cpy/COADM02Y.cpy` | Menu/navigation configuration |
| `src/shared/utils/__init__.py` | CREATE | (none — new) | Module init |
| `src/shared/utils/date_utils.py` | CREATE | `app/cbl/CSUTLDTC.cbl`, `app/cpy/CSDAT01Y.cpy`, `app/cpy/CSUTLDWY.cpy`, `app/cpy/CSUTLDPY.cpy` | Date validation, formatting |
| `src/shared/utils/string_utils.py` | CREATE | `app/cpy/CSSTRPFY.cpy` | String processing functions |
| `src/shared/utils/decimal_utils.py` | CREATE | `app/cpy/CVTRA01Y.cpy` (financial fields) | COBOL-compatible decimal arithmetic |
| `src/shared/config/__init__.py` | CREATE | (none — new) | Module init |
| `src/shared/config/settings.py` | CREATE | (none — new) | Pydantic BaseSettings for env vars |
| `src/shared/config/aws_config.py` | CREATE | (none — new) | AWS service client factories, Secrets Manager |

**API Layer — Core:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/api/__init__.py` | CREATE | (none — new) | Module init |
| `src/api/main.py` | CREATE | `app/cbl/COMEN01C.cbl`, `app/cbl/COADM01C.cbl` | FastAPI app, router includes, Strawberry mount, startup/shutdown |
| `src/api/dependencies.py` | CREATE | `app/cpy/COCOM01Y.cpy` | DB session, current user, auth dependencies |
| `src/api/database.py` | CREATE | `app/jcl/ACCTFILE.jcl` (connection context) | SQLAlchemy async engine, session factory for Aurora PostgreSQL |

**API Layer — Middleware:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/api/middleware/__init__.py` | CREATE | (none — new) | Module init |
| `src/api/middleware/auth.py` | CREATE | `app/cbl/COSGN00C.cbl`, `app/cpy/COCOM01Y.cpy` | JWT validation, user extraction from token |
| `src/api/middleware/error_handler.py` | CREATE | `app/cpy/CSMSG01Y.cpy`, `app/cpy/CSMSG02Y.cpy` | Global exception handler, COBOL-equivalent error codes |

**API Layer — Routers (Online CICS → REST Endpoints):**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/api/routers/__init__.py` | CREATE | (none — new) | Module init |
| `src/api/routers/auth_router.py` | CREATE | `app/cbl/COSGN00C.cbl` | POST /auth/login, POST /auth/logout |
| `src/api/routers/account_router.py` | CREATE | `app/cbl/COACTVWC.cbl`, `app/cbl/COACTUPC.cbl` | GET /accounts/{id}, PUT /accounts/{id} |
| `src/api/routers/card_router.py` | CREATE | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | GET /cards, GET /cards/{id}, PUT /cards/{id} |
| `src/api/routers/transaction_router.py` | CREATE | `app/cbl/COTRN00C.cbl`, `app/cbl/COTRN01C.cbl`, `app/cbl/COTRN02C.cbl` | GET /transactions, GET /transactions/{id}, POST /transactions |
| `src/api/routers/bill_router.py` | CREATE | `app/cbl/COBIL00C.cbl` | POST /bills/pay |
| `src/api/routers/report_router.py` | CREATE | `app/cbl/CORPT00C.cbl` | POST /reports/submit |
| `src/api/routers/user_router.py` | CREATE | `app/cbl/COUSR00C.cbl`, `app/cbl/COUSR01C.cbl`, `app/cbl/COUSR02C.cbl`, `app/cbl/COUSR03C.cbl` | GET /users, POST /users, PUT /users/{id}, DELETE /users/{id} |
| `src/api/routers/admin_router.py` | CREATE | `app/cbl/COADM01C.cbl` | Admin-only endpoints |

**API Layer — Services (Business Logic):**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/api/services/__init__.py` | CREATE | (none — new) | Module init |
| `src/api/services/auth_service.py` | CREATE | `app/cbl/COSGN00C.cbl` | BCrypt verification, JWT generation |
| `src/api/services/account_service.py` | CREATE | `app/cbl/COACTVWC.cbl`, `app/cbl/COACTUPC.cbl` | 3-entity join view, dual-write update with transactional rollback |
| `src/api/services/card_service.py` | CREATE | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | Paginated list (7/page), detail, optimistic concurrency update |
| `src/api/services/transaction_service.py` | CREATE | `app/cbl/COTRN00C.cbl`, `app/cbl/COTRN01C.cbl`, `app/cbl/COTRN02C.cbl` | Paginated list (10/page), detail, auto-ID + xref add |
| `src/api/services/bill_service.py` | CREATE | `app/cbl/COBIL00C.cbl` | Dual-write: Transaction INSERT + Account balance UPDATE |
| `src/api/services/report_service.py` | CREATE | `app/cbl/CORPT00C.cbl` | SQS FIFO message publish (replacing TDQ WRITEQ JOBS) |
| `src/api/services/user_service.py` | CREATE | `app/cbl/COUSR00C.cbl`, `app/cbl/COUSR01C.cbl`, `app/cbl/COUSR02C.cbl`, `app/cbl/COUSR03C.cbl` | Full CRUD with BCrypt password hashing |

**API Layer — GraphQL:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/api/graphql/__init__.py` | CREATE | (none — new) | Module init |
| `src/api/graphql/schema.py` | CREATE | `app/cbl/CO*.cbl` (all online programs) | Strawberry schema stitching all types |
| `src/api/graphql/types/__init__.py` | CREATE | (none — new) | Module init |
| `src/api/graphql/types/account_type.py` | CREATE | `app/cpy/CVACT01Y.cpy`, `app/cpy-bms/COACTVW.CPY` | Strawberry Account GraphQL type |
| `src/api/graphql/types/card_type.py` | CREATE | `app/cpy/CVACT02Y.cpy`, `app/cpy-bms/COCRDSL.CPY` | Strawberry Card GraphQL type |
| `src/api/graphql/types/transaction_type.py` | CREATE | `app/cpy/CVTRA05Y.cpy`, `app/cpy-bms/COTRN01.CPY` | Strawberry Transaction GraphQL type |
| `src/api/graphql/types/user_type.py` | CREATE | `app/cpy/CSUSR01Y.cpy`, `app/cpy-bms/COUSR00.CPY` | Strawberry User GraphQL type |
| `src/api/graphql/queries.py` | CREATE | `app/cbl/COACTVWC.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COTRN00C.cbl`, `app/cbl/COUSR00C.cbl` | GraphQL query resolvers |
| `src/api/graphql/mutations.py` | CREATE | `app/cbl/COACTUPC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/COTRN02C.cbl`, `app/cbl/COBIL00C.cbl` | GraphQL mutation resolvers |

**Batch Layer — PySpark Glue Jobs:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `src/batch/__init__.py` | CREATE | (none — new) | Module init |
| `src/batch/common/__init__.py` | CREATE | (none — new) | Module init |
| `src/batch/common/glue_context.py` | CREATE | (none — new) | GlueContext + SparkSession factory, logging setup |
| `src/batch/common/db_connector.py` | CREATE | (none — new) | JDBC connection to Aurora PostgreSQL via Secrets Manager |
| `src/batch/common/s3_utils.py` | CREATE | (none — new) | S3 read/write helpers for GDG-equivalent output |
| `src/batch/jobs/__init__.py` | CREATE | (none — new) | Module init |
| `src/batch/jobs/posttran_job.py` | CREATE | `app/cbl/CBTRN02C.cbl`, `app/jcl/POSTTRAN.jcl` | Stage 1: 4-stage validation cascade, reject codes 100-109 |
| `src/batch/jobs/intcalc_job.py` | CREATE | `app/cbl/CBACT04C.cbl`, `app/jcl/INTCALC.jcl` | Stage 2: (TRAN-CAT-BAL × DIS-INT-RATE) / 1200, DEFAULT fallback |
| `src/batch/jobs/combtran_job.py` | CREATE | `app/jcl/COMBTRAN.jcl` | Stage 3: PySpark merge/sort (replaces DFSORT+REPRO) |
| `src/batch/jobs/creastmt_job.py` | CREATE | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/jcl/CREASTMT.jcl` | Stage 4a: Statement generation (text+HTML), 4-entity join |
| `src/batch/jobs/tranrept_job.py` | CREATE | `app/cbl/CBTRN03C.cbl`, `app/jcl/TRANREPT.jcl` | Stage 4b: Date-filtered reports, 3-level totals |
| `src/batch/jobs/prtcatbl_job.py` | CREATE | `app/jcl/PRTCATBL.jcl` | Print category balance utility |
| `src/batch/jobs/daily_tran_driver_job.py` | CREATE | `app/cbl/CBTRN01C.cbl` | Daily transaction driver |
| `src/batch/jobs/read_account_job.py` | CREATE | `app/cbl/CBACT01C.cbl`, `app/jcl/READACCT.jcl` | Account diagnostic reader |
| `src/batch/jobs/read_card_job.py` | CREATE | `app/cbl/CBACT02C.cbl`, `app/jcl/READCARD.jcl` | Card diagnostic reader |
| `src/batch/jobs/read_customer_job.py` | CREATE | `app/cbl/CBCUS01C.cbl`, `app/jcl/READCUST.jcl` | Customer diagnostic reader |
| `src/batch/jobs/read_xref_job.py` | CREATE | `app/cbl/CBACT03C.cbl`, `app/jcl/READXREF.jcl` | Cross-reference diagnostic reader |
| `src/batch/pipeline/__init__.py` | CREATE | (none — new) | Module init |
| `src/batch/pipeline/step_functions_definition.json` | CREATE | `app/jcl/POSTTRAN.jcl`, `app/jcl/INTCALC.jcl`, `app/jcl/COMBTRAN.jcl`, `app/jcl/CREASTMT.jcl`, `app/jcl/TRANREPT.jcl` | Step Functions state machine: S1→S2→S3→Parallel(S4a,S4b) |

**Infrastructure Configuration:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `infra/ecs-task-definition.json` | CREATE | (none — new) | ECS Fargate task def: Python 3.11, 0.5 vCPU, 1GB RAM |
| `infra/glue-job-configs/posttran.json` | CREATE | `app/jcl/POSTTRAN.jcl` | Glue 5.1, G.1X, 2 workers |
| `infra/glue-job-configs/intcalc.json` | CREATE | `app/jcl/INTCALC.jcl` | Glue 5.1, G.1X, 2 workers |
| `infra/glue-job-configs/combtran.json` | CREATE | `app/jcl/COMBTRAN.jcl` | Glue 5.1, G.1X, 2 workers |
| `infra/glue-job-configs/creastmt.json` | CREATE | `app/jcl/CREASTMT.jcl` | Glue 5.1, G.1X, 2 workers |
| `infra/glue-job-configs/tranrept.json` | CREATE | `app/jcl/TRANREPT.jcl` | Glue 5.1, G.1X, 2 workers |
| `infra/cloudwatch/dashboard.json` | CREATE | (none — new) | Monitoring dashboard for Glue + ECS |

**Documentation:**

| Target File | Transformation | Source File(s) | Key Changes |
|---|---|---|---|
| `docs/architecture.md` | CREATE | (none — new) | New architecture documentation |
| `docs/index.md` | UPDATE | `docs/index.md` | Update for Python/AWS target |
| `docs/project-guide.md` | UPDATE | `docs/project-guide.md` | Update status/metrics for new stack |

### 0.5.2 Cross-File Dependencies

**Import Statement Updates:**

All COBOL `COPY` statements are replaced by Python imports. The transformation rule is:
- FROM (COBOL): `COPY CVACT01Y.` in DATA DIVISION
- TO (Python): `from src.shared.models.account import Account`

Since this is a greenfield Python project (no existing Python imports to update), all imports are established fresh during creation.

**Key Cross-Module Dependencies:**
- All router modules import their corresponding service module
- All service modules import shared models and database session
- All PySpark jobs import `src/batch/common/glue_context.py` and `src/batch/common/db_connector.py`
- Both API and batch layers share `src/shared/models/` and `src/shared/utils/`
- `src/shared/config/settings.py` is imported by both API and batch configuration modules

### 0.5.3 One-Phase Execution

The entire refactor will be executed by Blitzy in **ONE phase**. All files listed above (approximately 110+ new Python files, 3 SQL migration files, 5 infrastructure configuration files, 3 GitHub Actions workflows, and 3 updated documentation files) will be created or updated in a single comprehensive pass. No splitting into phases.


## 0.6 Dependency Inventory


### 0.6.1 Key Public Packages

The following packages are required for the target implementation, organized by workload layer:

**Core / Shared Dependencies (`requirements.txt`):**

| Registry | Package | Version | Purpose |
|---|---|---|---|
| PyPI | `python` | 3.11 | Runtime — aligned with AWS Glue 5.1 and FastAPI recommendation |
| PyPI | `boto3` | 1.35.x | AWS SDK — Secrets Manager, SQS, S3 access |
| PyPI | `botocore` | 1.35.x | Core AWS SDK underlying boto3 |
| PyPI | `pydantic` | 2.10.x | Data validation, Pydantic v2 with Rust-backed core |
| PyPI | `pydantic-settings` | 2.7.x | Environment variable management via BaseSettings |
| PyPI | `python-dotenv` | 1.0.1 | Local .env file loading for development |

**API Layer Dependencies (`requirements-api.txt`):**

| Registry | Package | Version | Purpose |
|---|---|---|---|
| PyPI | `fastapi` | 0.115.x | Web framework — REST and GraphQL (with Strawberry) |
| PyPI | `uvicorn[standard]` | 0.34.x | ASGI server for FastAPI |
| PyPI | `sqlalchemy[asyncio]` | 2.0.x | Async ORM for Aurora PostgreSQL |
| PyPI | `asyncpg` | 0.30.x | Async PostgreSQL driver for SQLAlchemy async |
| PyPI | `psycopg2-binary` | 2.9.x | Sync PostgreSQL driver (migration scripts, fallback) |
| PyPI | `alembic` | 1.14.x | Database migration tool (alternative to raw SQL migrations) |
| PyPI | `strawberry-graphql[fastapi]` | 0.254.x | GraphQL integration for FastAPI via Strawberry |
| PyPI | `python-jose[cryptography]` | 3.3.0 | JWT token encoding/decoding |
| PyPI | `passlib[bcrypt]` | 1.7.4 | BCrypt password hashing (preserves COBOL-era security) |
| PyPI | `bcrypt` | 4.2.x | BCrypt backend for passlib |
| PyPI | `python-multipart` | 0.0.18 | Form data parsing for FastAPI |
| PyPI | `httpx` | 0.28.x | HTTP client for internal/external calls |

**Batch Layer Dependencies (`requirements-glue.txt`):**

| Registry | Package | Version | Purpose |
|---|---|---|---|
| PyPI | `pyspark` | 3.5.6 | Apache Spark — aligned with AWS Glue 5.1 |
| PyPI | `aws-glue-libs` | (Glue-provided) | AWS Glue DynamicFrame, GlueContext, Job |
| PyPI | `pg8000` | 1.31.x | Pure-Python PostgreSQL driver for Glue JDBC fallback |

**Development and Testing Dependencies (`requirements-dev.txt`):**

| Registry | Package | Version | Purpose |
|---|---|---|---|
| PyPI | `pytest` | 8.3.x | Test framework |
| PyPI | `pytest-asyncio` | 0.24.x | Async test support for FastAPI |
| PyPI | `pytest-cov` | 6.0.x | Coverage reporting |
| PyPI | `httpx` | 0.28.x | Test client for FastAPI (via `TestClient`) |
| PyPI | `moto[all]` | 5.0.x | AWS service mocking (S3, SQS, Secrets Manager, Glue) |
| PyPI | `factory-boy` | 3.3.x | Test data factories |
| PyPI | `ruff` | 0.8.x | Linter and formatter |
| PyPI | `mypy` | 1.13.x | Static type checking |
| PyPI | `testcontainers[postgres]` | 4.8.x | PostgreSQL container for integration tests |

### 0.6.2 AWS Service Dependencies

| AWS Service | Purpose | COBOL Equivalent Replaced |
|---|---|---|
| AWS Glue 5.1 | Serverless PySpark job execution (Spark 3.5.6, Python 3.11) | JES2/JCL batch execution |
| AWS ECS Fargate | Container orchestration for FastAPI API service | CICS region |
| AWS ECR | Docker image registry for API container | N/A |
| AWS Aurora PostgreSQL | Relational database (PostgreSQL-compatible) | VSAM KSDS datasets |
| AWS S3 | Statement/report output storage, Glue script storage | GDG generations, PS datasets |
| AWS SQS FIFO | Report submission queue (replacing TDQ) | CICS TDQ (WRITEQ JOBS) |
| AWS Secrets Manager | Database credentials, API keys | z/OS RACF credentials |
| AWS IAM | Service-to-service authentication and authorization | RACF access control |
| AWS CloudWatch | Logging and monitoring for Glue jobs and ECS | z/OS SMF/SYSLOG |
| AWS Step Functions | Batch pipeline orchestration (S1→S2→S3→S4a∥S4b) | JCL COND parameter chaining |
| GitHub Actions | CI/CD pipelines | z/OS build/deploy JCL |

### 0.6.3 Dependency Updates — Import Refactoring

Since this is a greenfield Python codebase (no existing Python imports to refactor), import patterns will be established fresh. The key import conventions to be applied across all new Python files:

**API Layer Import Pattern:**
```python
from src.shared.models.account import Account
from src.shared.schemas.account_schema import AccountView
```

**Batch Layer Import Pattern:**
```python
from src.batch.common.glue_context import init_glue
from src.batch.common.db_connector import get_jdbc_url
```

### 0.6.4 External Reference Updates

| File Pattern | Update Type | Details |
|---|---|---|
| `.github/workflows/*.yml` | CREATE | GitHub Actions CI/CD pipeline definitions |
| `Dockerfile` | CREATE | Python 3.11-slim base, FastAPI application container |
| `docker-compose.yml` | CREATE | Local development: API + PostgreSQL 16 + LocalStack |
| `pyproject.toml` | CREATE | Python project metadata and tool configuration |
| `infra/*.json` | CREATE | ECS task definitions, Glue job configs, CloudWatch dashboards |
| `db/migrations/*.sql` | CREATE | Flyway-style schema, index, and seed data migrations |
| `README.md` | UPDATE | Architecture, setup, deployment instructions |
| `docs/*.md` | UPDATE | Technical documentation for new stack |


## 0.7 Refactoring Rules


### 0.7.1 Refactoring-Specific Rules

The following rules are derived from the user's explicit instructions and must be strictly observed throughout the migration:

- **Preserve all existing functionality exactly as-is** — Every one of the 22 features (F-001 through F-022) must be functionally equivalent in the target implementation. No feature may be dropped, combined, or altered in behavior.
- **Maintain existing business logic without modification** — The COBOL business rules, validation cascades, calculation formulas, and data flow patterns must be faithfully translated to Python/PySpark without simplification or optimization. This specifically includes:
  - The interest calculation formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` must not be algebraically simplified
  - The 4-stage transaction validation cascade in POSTTRAN (reject codes 100-109) must be preserved exactly
  - The DEFAULT/ZEROAPR disclosure group fallback logic must be preserved
  - The dual-write patterns in Account Update (F-005) and Bill Payment (F-012) must remain atomic
  - The optimistic concurrency check in Card Update (F-008) must be maintained
- **Minimal change clause** — Make only the changes absolutely necessary for the technology transition. Do not enhance, optimize, or refactor code beyond what is directly required for COBOL→Python/PySpark/FastAPI conversion.
- **Do not modify code not directly impacted by the technology transition** — The original COBOL source files (`app/`) must be retained for traceability and must not be modified or deleted.
- **Isolate new implementations in dedicated files/modules** — All new Python code goes under `src/`, `tests/`, `db/`, `infra/`, and `.github/` — completely separate from the original `app/` directory.
- **Document all technology-specific changes with clear comments** — Each Python module should include a header comment referencing its COBOL source file(s) and the transformation applied.

### 0.7.2 Special Instructions and Constraints

**Financial Precision:**
- All monetary values must use Python `decimal.Decimal` with explicit two-decimal-place precision, matching COBOL `PIC S9(n)V99` semantics
- Banker's rounding (`ROUND_HALF_EVEN`) must be used where COBOL uses ROUNDED
- No floating-point arithmetic is permitted for any financial calculation

**Security Requirements (User-Specified):**
- AWS services must have required access via IAM roles and policies (not access keys)
- Use AWS Secrets Manager for all database credentials and sensitive configuration
- BCrypt password hashing must be maintained for user authentication (matching existing COBOL behavior)
- JWT tokens for stateless session management (replacing CICS COMMAREA)

**Monitoring Requirements (User-Specified):**
- The system should be easy to monitor
- CloudWatch integration for AWS Glue job metrics (duration, DPU usage, error rates)
- CloudWatch integration for ECS service metrics (CPU, memory, request count, error rate)
- Structured JSON logging from both API and batch components
- CloudWatch dashboard for unified observability

**Testing Requirements (User-Specified):**
- Automated testing as much as possible
- pytest as the primary test framework
- Unit tests for all business logic (services and PySpark transformations)
- Integration tests with real PostgreSQL (via Testcontainers)
- API endpoint tests using FastAPI TestClient
- Mock AWS services using moto library
- Target coverage: parity with the documented 81.5% from the existing specification

**Batch Pipeline Sequencing:**
- The 5-stage batch pipeline must execute in the correct order: POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT)
- Stages 4a and 4b must run in parallel (as in the original JCL architecture)
- Inter-stage data dependencies must be maintained through Aurora PostgreSQL tables (replacing shared VSAM datasets)
- Stage failure must halt downstream stages (matching JCL COND parameter behavior)

### 0.7.3 User-Specified Implementation Rules

The user provided the following discipline guidelines that must be followed:

- Make only the minimal necessary changes to implement the refactor
- Preserve existing functionality and behavior exactly as-is
- Do not modify code that is not directly impacted by the technology transition
- Do not enhance or optimize code beyond the requirements of the migration
- Isolate new implementations in dedicated files/modules when possible
- Document all technology-specific changes with clear comments


## 0.8 References


### 0.8.1 Repository Files and Folders Searched

The following files and folders were comprehensively searched across the codebase to derive the conclusions in this Agent Action Plan:

**Root-Level Files:**
- `README.md` — Project overview, Apache 2.0 license, Backstage catalog reference
- `CONTRIBUTING.md` — Contribution guidelines
- `CODE_OF_CONDUCT.md` — Code of conduct
- `LICENSE` — Apache License 2.0
- `mkdocs.yml` — MkDocs configuration with techdocs-core and mermaid2 plugins
- `catalog-info.yaml` — Backstage service catalog entry (blitzy-card-demo)

**COBOL Source Programs (`app/cbl/`):**
- All 28 COBOL programs: CBACT01C.cbl, CBACT02C.cbl, CBACT03C.cbl, CBACT04C.cbl, CBCUS01C.cbl, CBTRN01C.cbl, CBTRN02C.cbl, CBTRN03C.cbl, CBSTM03A.CBL, CBSTM03B.CBL, COSGN00C.cbl, COMEN01C.cbl, COADM01C.cbl, COACTVWC.cbl, COACTUPC.cbl, COCRDLIC.cbl, COCRDSLC.cbl, COCRDUPC.cbl, COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl, COBIL00C.cbl, CORPT00C.cbl, COUSR00C.cbl, COUSR01C.cbl, COUSR02C.cbl, COUSR03C.cbl, CSUTLDTC.cbl

**Copybooks (`app/cpy/`):**
- All 28 copybooks: CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy, CVCUS01Y.cpy, CVCRD01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy, CVTRA03Y.cpy, CVTRA04Y.cpy, CVTRA05Y.cpy, CVTRA06Y.cpy, CVTRA07Y.cpy, CUSTREC.cpy, COSTM01.cpy, CSUSR01Y.cpy, COCOM01Y.cpy, COMEN02Y.cpy, COADM02Y.cpy, COTTL01Y.cpy, CSMSG01Y.cpy, CSMSG02Y.cpy, CSDAT01Y.cpy, CSUTLDWY.cpy, CSUTLDPY.cpy, CSLKPCDY.cpy, CSSETATY.cpy, CSSTRPFY.cpy, UNUSED1Y.cpy

**BMS Mapsets (`app/bms/`):**
- All 17 mapsets: COSGN00.bms, COMEN01.bms, COADM01.bms, COACTVW.bms, COACTUP.bms, COCRDLI.bms, COCRDSL.bms, COCRDUP.bms, COTRN00.bms, COTRN01.bms, COTRN02.bms, COBIL00.bms, CORPT00.bms, COUSR00.bms, COUSR01.bms, COUSR02.bms, COUSR03.bms

**Symbolic Map Copybooks (`app/cpy-bms/`):**
- All 17 symbolic maps: COSGN00.CPY, COMEN01.CPY, COADM01.CPY, COACTVW.CPY, COACTUP.CPY, COCRDLI.CPY, COCRDSL.CPY, COCRDUP.CPY, COTRN00.CPY, COTRN01.CPY, COTRN02.CPY, COBIL00.CPY, CORPT00.CPY, COUSR00.CPY, COUSR01.CPY, COUSR02.CPY, COUSR03.CPY

**JCL Jobs (`app/jcl/`):**
- All 29 JCL members: ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl, TCATBALF.jcl, TRANCATG.jcl, TRANTYPE.jcl, DISCGRP.jcl, DUSRSECJ.jcl, DEFCUST.jcl, TRANIDX.jcl, DEFGDGB.jcl, REPTFILE.jcl, DALYREJS.jcl, POSTTRAN.jcl, INTCALC.jcl, COMBTRAN.jcl, TRANREPT.jcl, CREASTMT.jcl, PRTCATBL.jcl, CBADMCDJ.jcl, CLOSEFIL.jcl, OPENFIL.jcl, TRANBKP.jcl, READACCT.jcl, READCARD.jcl, READCUST.jcl, READXREF.jcl

**Data Fixtures (`app/data/ASCII/`):**
- All 9 fixture files: acctdata.txt, carddata.txt, custdata.txt, cardxref.txt, dailytran.txt, tcatbal.txt, discgrp.txt, trancatg.txt, trantype.txt

**Catalog (`app/catlg/`):**
- LISTCAT.txt — IDCAMS catalog report (209 entries)

**Sample JCL (`samples/jcl/`):**
- BATCMP.jcl, BMSCMP.jcl, CICCMP.jcl

**Documentation (`docs/`):**
- index.md, project-guide.md, technical-specifications.md

### 0.8.2 Technical Specification Sections Retrieved

The following sections were retrieved from the existing technical specification document using the `get_tech_spec_section` tool:

| Section | Key Information Extracted |
|---|---|
| **1.1 Executive Summary** | 149 total source artifacts, 22 features, 5-stage batch pipeline, default credentials, 888 tests at 81.5% coverage |
| **2.1 Feature Catalog** | All 22 features (F-001 through F-022) with COBOL program mappings, key behaviors, and cross-feature dependencies |
| **3.1 Programming Languages** | Existing Java 25 LTS target (103 files, 34,021 lines), COBOL baseline (19,254 lines), SQL via PostgreSQL 16 |
| **3.2 Frameworks & Libraries** | Existing Spring Boot 3.5.11 target stack — Spring MVC, Spring Data JPA, Spring Batch, Spring Security, Spring Cloud AWS |
| **5.1 High-Level Architecture** | Layered monolithic architecture, transformation rules (VSAM→JPA, BMS→REST, JCL→Spring Batch), integration ports |
| **6.2 Database Design** | 11 JPA entities, VSAM-to-PostgreSQL mapping, composite keys, Flyway migrations, transactional patterns, dual-write patterns |

**Critical Note:** The existing technical specification describes a migration target of Java 25 / Spring Boot 3.5.11 / PostgreSQL 16 — which is a fundamentally different target from the user's requested Python / PySpark / FastAPI / Aurora PostgreSQL stack. The domain knowledge (features, data models, business rules, pipeline structure) was extracted from the spec, but all technology decisions in this Agent Action Plan reflect the user's stated target.

### 0.8.3 Web Research Conducted

| Search Topic | Key Findings |
|---|---|
| **AWS Glue 5.1 / PySpark** | Glue 5.1 GA (Nov 2025): Spark 3.5.6, Python 3.11, Scala 2.12.18; G.1X/G.2X worker types; DynamicFrame for schema flexibility; Parquet output; push-down predicates for partition pruning |
| **COBOL to Python migration patterns** | Functional equivalence over line-by-line rewrite; COBOL paragraph → Python function; PIC fields → Decimal; sequential I/O → DataFrame operations; copybooks → dataclasses; Cobrix library for Spark/COBOL integration |
| **FastAPI on AWS ECS** | Containerize with Docker (Python 3.11-slim), Uvicorn ASGI server, ECS Fargate deployment; Strawberry for GraphQL alongside REST; ECR for image registry; ALB for load balancing |
| **Aurora PostgreSQL with Python** | psycopg2-binary for sync driver, asyncpg for async; SQLAlchemy 2.x ORM; AWS Secrets Manager for credentials; IAM database authentication supported |

### 0.8.4 Attachments

No attachments were provided for this project. No Figma URLs were specified.


