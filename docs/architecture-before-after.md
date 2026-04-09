# CardDemo Architecture — Before & After Migration

> **Migration Context:** AWS CardDemo COBOL mainframe application (source commit `27d6c6f`) migrated to Java 25 LTS + Spring Boot 3.x cloud-native architecture with 100% behavioral parity.

This document provides comprehensive visual architecture documentation using Mermaid diagrams. Every modified architectural aspect shows both the **before** (z/OS mainframe) and **after** (Java/AWS cloud) states, per the Visual Architecture Documentation rule.

---

## Table of Contents

| # | Diagram | Description |
|---|---------|-------------|
| 1 | [Before-State z/OS Architecture](#1-before-state-zos-mainframe-architecture) | Complete z/OS mainframe tier diagram with CICS, VSAM, JES, TDQ, and GDG components |
| 2 | [After-State Java/AWS Architecture](#2-after-state-javaaws-cloud-architecture) | Spring Boot services, PostgreSQL, S3, SQS, observability stack, and security |
| 3 | [Batch Pipeline — Before & After](#3-batch-pipeline--before--after) | JCL 5-stage pipeline vs. Spring Batch 5-stage pipeline side by side |
| 4 | [Data Migration Flow](#4-data-migration--vsam-to-postgresql) | VSAM KSDS/AIX/PS to PostgreSQL relational tables via Flyway |
| 5 | [Component Interaction — Layered Architecture](#5-spring-boot-component-interaction--layered-architecture) | Controller → Service → Repository → Database layering with cross-cutting concerns |
| 6 | [Authentication Flow — Before & After](#6-authentication-flow--before--after) | CICS pseudo-conversational sign-on vs. Spring Security HTTP Basic Auth flow |

---

## 1. Before-State z/OS Mainframe Architecture

The original CardDemo application runs entirely on IBM z/OS. It consists of 18 CICS online programs serving 3270 terminal users through BMS screen maps, 10 batch programs executed via JES job scheduling, 10 VSAM KSDS datasets with 2 alternate indexes, 1 sequential PS staging file, 6 GDG bases for generational data management, and CICS Transient Data Queues (TDQ) for inter-program messaging.

```mermaid
graph TB
    %% Title
    title["<b>CardDemo z/OS Mainframe Architecture (Before)</b>"]
    style title fill:none,stroke:none,color:#333,font-size:16px

    %% ── Presentation Tier ──
    subgraph PRES["Presentation Tier — 3270 Terminals"]
        direction LR
        T1["🖥️ 3270 Terminal<br/>TN3270 Protocol"]
        BMS["BMS Map Definitions<br/>(17 screen maps)<br/>COSGN00 · COMEN01 · COADM01<br/>COACTVW · COACTUP · COBIL00<br/>COCRDLI · COCRDSL · COCRDUP<br/>COTRN00 · COTRN01 · COTRN02<br/>CORPT00 · COUSR00–03"]
        T1 -->|"SEND MAP /<br/>RECEIVE MAP"| BMS
    end
    style PRES fill:#E3F2FD,stroke:#1565C0,color:#0D47A1

    %% ── CICS Online Region ──
    subgraph CICS["CICS TS v5.6 Online Region — 18 Programs"]
        direction TB

        subgraph AUTH["Authentication"]
            COSGN00C["COSGN00C<br/>Sign-On"]
        end

        subgraph MENU["Menu Navigation"]
            COMEN01C["COMEN01C<br/>Main Menu<br/>(10 options)"]
            COADM01C["COADM01C<br/>Admin Menu<br/>(4 options)"]
        end

        subgraph ACCT["Account Management"]
            COACTVWC["COACTVWC<br/>Account View"]
            COACTUPC["COACTUPC<br/>Account Update<br/>(SYNCPOINT)"]
        end

        subgraph CARD["Card Management"]
            COCRDLIC["COCRDLIC<br/>Card List<br/>(7 rows/page)"]
            COCRDSLC["COCRDSLC<br/>Card Detail"]
            COCRDUPC["COCRDUPC<br/>Card Update<br/>(Opt. Lock)"]
        end

        subgraph TXN["Transaction Management"]
            COTRN00C["COTRN00C<br/>Txn List<br/>(10 rows/page)"]
            COTRN01C["COTRN01C<br/>Txn Detail"]
            COTRN02C["COTRN02C<br/>Txn Add<br/>(Auto-ID)"]
        end

        subgraph BILL["Billing"]
            COBIL00C["COBIL00C<br/>Bill Payment"]
        end

        subgraph RPT["Reporting"]
            CORPT00C["CORPT00C<br/>Report Submit<br/>(TDQ Bridge)"]
        end

        subgraph ADMIN["User Administration"]
            COUSR00C["COUSR00C<br/>User List"]
            COUSR01C["COUSR01C<br/>User Add"]
            COUSR02C["COUSR02C<br/>User Update"]
            COUSR03C["COUSR03C<br/>User Delete"]
        end

        COSGN00C -->|"XCTL COMMAREA"| COMEN01C
        COSGN00C -->|"XCTL COMMAREA<br/>(Admin)"| COADM01C
    end
    style CICS fill:#FFF3E0,stroke:#E65100,color:#BF360C

    %% ── CICS TDQ ──
    subgraph TDQ["CICS Transient Data Queues"]
        JOBSQ["JOBS Queue<br/>(Report Submission<br/>→ JES)"]
    end
    style TDQ fill:#F3E5F5,stroke:#6A1B9A,color:#4A148C

    %% ── JES Batch Subsystem ──
    subgraph JES["JES Batch Subsystem — 10 Programs"]
        direction TB

        subgraph BREADERS["File Reader Utilities"]
            CBACT01C["CBACT01C<br/>Account Reader"]
            CBACT02C["CBACT02C<br/>Card Reader"]
            CBACT03C["CBACT03C<br/>XRef Reader"]
            CBCUS01C["CBCUS01C<br/>Customer Reader"]
        end

        subgraph BPROCESS["Batch Processing"]
            CBTRN01C_B["CBTRN01C<br/>Daily Txn<br/>Validation"]
            CBTRN02C_B["CBTRN02C<br/>Daily Txn<br/>Posting"]
            CBACT04C["CBACT04C<br/>Interest<br/>Calculation"]
        end

        subgraph BSTMT["Statement & Report"]
            CBSTM03A["CBSTM03A<br/>Statement<br/>Generation"]
            CBSTM03B["CBSTM03B<br/>Statement<br/>File Service"]
            CBTRN03C["CBTRN03C<br/>Transaction<br/>Report"]
        end

        subgraph BUTIL["Shared Utility"]
            CSUTLDTC["CSUTLDTC<br/>Date Validation<br/>(LE CEEDAYS)"]
        end
    end
    style JES fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20

    %% ── VSAM Data Layer ──
    subgraph VSAM["VSAM Data Layer — 10 KSDS + 2 AIX/PATH + 1 PS"]
        direction LR

        subgraph KSDS["VSAM KSDS (Keyed Sequential)"]
            ACCTDAT["ACCTDAT<br/>KEY(11,0) REC(300)"]
            CARDDAT["CARDDAT<br/>KEY(16,0) REC(150)"]
            CUSTDAT["CUSTDAT<br/>KEY(9,0) REC(500)"]
            CARDXREF["CARDXREF<br/>KEY(16,0) REC(50)"]
            TRANSACT["TRANSACT<br/>KEY(16,0) REC(350)"]
            USRSEC["USRSEC<br/>KEY(8,0) REC(80)"]
            TCATBALF["TCATBALF<br/>Composite Key"]
            DISCGRP_D["DISCGRP<br/>Composite Key"]
            TRANTYPE_D["TRANTYPE<br/>REC(60)"]
            TRANCATG_D["TRANCATG<br/>Composite Key"]
        end

        subgraph AIX["Alternate Indexes"]
            CXACAIX["CXACAIX<br/>CARDXREF AIX<br/>KEY(11,25)<br/>→ by Account ID"]
            TRANAIX["TRANSACT AIX<br/>KEY(26,304)<br/>→ by Proc Timestamp"]
        end

        subgraph SEQ["Sequential (PS)"]
            DALYTRAN["DALYTRAN.PS<br/>Daily Txn Staging<br/>REC(350)"]
        end
    end
    style VSAM fill:#FCE4EC,stroke:#C62828,color:#B71C1C

    %% ── GDG Bases ──
    subgraph GDG["Generation Data Groups (6 GDG Bases, LIMIT 5 each)"]
        direction LR
        GBKUP["TRANSACT.BKUP<br/>(Txn Backup)"]
        GSYS["SYSTRAN<br/>(System Txns)"]
        GCOMB["TRANSACT.COMBINED<br/>(Merged Txns)"]
        GRPT["TRANREPT<br/>(Reports)"]
        GTCAT["TCATBALF.BKUP<br/>(Cat Bal Backup)"]
        GDALY["TRANSACT.DALY<br/>(Daily Filtered)"]
    end
    style GDG fill:#FFF9C4,stroke:#F9A825,color:#F57F17

    %% ── Connections ──
    BMS -->|"Screen I/O"| CICS
    CICS -->|"READ/WRITE/<br/>REWRITE/DELETE"| VSAM
    CORPT00C -->|"WRITEQ TD<br/>JOBS Queue"| JOBSQ
    JOBSQ -->|"JES Trigger"| JES
    JES -->|"READ/WRITE"| VSAM
    JES -->|"Generational<br/>Output (+1)"| GDG
    COACTUPC -->|"SYNCPOINT<br/>ROLLBACK"| VSAM
```

**Legend:**

| Color | Component Type |
|-------|---------------|
| 🔵 Blue (`#E3F2FD`) | Presentation tier — 3270 terminals and BMS screen maps |
| 🟠 Orange (`#FFF3E0`) | CICS online region — 18 interactive COBOL programs |
| 🟣 Purple (`#F3E5F5`) | CICS TDQ — Transient data queues for inter-program messaging |
| 🟢 Green (`#E8F5E9`) | JES batch subsystem — 10 batch COBOL programs |
| 🔴 Red (`#FCE4EC`) | VSAM data layer — 10 KSDS datasets, 2 AIX/PATH, 1 PS |
| 🟡 Yellow (`#FFF9C4`) | GDG bases — 6 generation data groups for versioned output |

---

## 2. After-State Java/AWS Cloud Architecture

The migrated CardDemo application is a standalone Spring Boot 3.x service running on Java 25 LTS. It replaces the 3270 terminal interface with REST API endpoints, VSAM datasets with PostgreSQL 16+ tables managed via Spring Data JPA, JCL batch jobs with Spring Batch job definitions, CICS TDQ with AWS SQS, and GDG bases with versioned S3 objects. A full observability stack (Jaeger, Prometheus, Grafana) provides tracing, metrics, and dashboards from day one.

```mermaid
graph TB
    %% Title
    title2["<b>CardDemo Java/AWS Cloud Architecture (After)</b>"]
    style title2 fill:none,stroke:none,color:#333,font-size:16px

    %% ── API Client Tier ──
    subgraph CLIENT["API Client Tier"]
        direction LR
        REST_CLIENT["🌐 REST API Clients<br/>(HTTP/JSON)"]
        BASIC_AUTH["🔑 HTTP Basic Auth<br/>Authorization Header"]
    end
    style CLIENT fill:#E3F2FD,stroke:#1565C0,color:#0D47A1

    %% ── Spring Security ──
    subgraph SECURITY["Spring Security Layer"]
        SEC_FILTER["Security Filter Chain<br/>BCrypt Password Verification<br/>HTTP Basic Authentication<br/>Role-Based Access (ADMIN/USER)"]
    end
    style SECURITY fill:#FFEBEE,stroke:#C62828,color:#B71C1C

    %% ── Controller Layer ──
    subgraph CONTROLLERS["Spring MVC Controllers — 8 REST Endpoints"]
        direction LR
        AUTH_C["AuthController<br/>POST /api/auth/signin"]
        ACCT_C["AccountController<br/>GET/PUT /api/accounts"]
        CARD_C["CardController<br/>GET/PUT /api/cards"]
        TXN_C["TransactionController<br/>GET/POST /api/transactions"]
        BILL_C["BillingController<br/>POST /api/billing/pay"]
        RPT_C["ReportController<br/>POST /api/reports/submit"]
        USER_C["UserAdminController<br/>CRUD /api/admin/users"]
        MENU_C["MenuController<br/>GET /api/menu"]
    end
    style CONTROLLERS fill:#FFF3E0,stroke:#E65100,color:#BF360C

    %% ── Service Layer ──
    subgraph SERVICES["Spring Service Layer — 19 Service Classes"]
        direction TB

        subgraph SVC_AUTH["auth"]
            AuthSvc["AuthenticationService"]
        end
        subgraph SVC_ACCT["account"]
            AcctViewSvc["AccountViewService"]
            AcctUpdSvc["AccountUpdateService<br/>(@Transactional)"]
        end
        subgraph SVC_CARD["card"]
            CardListSvc["CardListService"]
            CardDetSvc["CardDetailService"]
            CardUpdSvc["CardUpdateService<br/>(@Version)"]
        end
        subgraph SVC_TXN["transaction"]
            TxnListSvc["TransactionListService"]
            TxnDetSvc["TransactionDetailService"]
            TxnAddSvc["TransactionAddService"]
        end
        subgraph SVC_BILL["billing"]
            BillPaySvc["BillPaymentService"]
        end
        subgraph SVC_RPT["report"]
            RptSubSvc["ReportSubmissionService<br/>(→ SQS)"]
        end
        subgraph SVC_ADMIN["admin"]
            UserListSvc["UserListService"]
            UserAddSvc["UserAddService"]
            UserUpdSvc["UserUpdateService"]
            UserDelSvc["UserDeleteService"]
        end
        subgraph SVC_MENU["menu"]
            MainMenuSvc["MainMenuService"]
            AdminMenuSvc["AdminMenuService"]
        end
        subgraph SVC_SHARED["shared"]
            DateValSvc["DateValidationService"]
            ValLookupSvc["ValidationLookupService<br/>(NANPA/State/ZIP)"]
            FileStatMap["FileStatusMapper"]
        end
    end
    style SERVICES fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20

    %% ── Repository Layer ──
    subgraph REPOS["Spring Data JPA Repositories — 11 Interfaces"]
        direction LR
        AccountRepo["AccountRepository"]
        CardRepo["CardRepository"]
        CustomerRepo["CustomerRepository"]
        XrefRepo["CardCrossReferenceRepository"]
        TxnRepo["TransactionRepository"]
        UserRepo["UserSecurityRepository"]
        TCatBalRepo["TransactionCategoryBalanceRepository"]
        DiscGrpRepo["DisclosureGroupRepository"]
        TxnTypeRepo["TransactionTypeRepository"]
        TxnCatRepo["TransactionCategoryRepository"]
        DailyTxnRepo["DailyTransactionRepository"]
    end
    style REPOS fill:#F3E5F5,stroke:#6A1B9A,color:#4A148C

    %% ── Database Layer ──
    subgraph DB["PostgreSQL 16+ — 11 Tables"]
        direction LR
        PG["🐘 PostgreSQL<br/>Flyway Managed Schema<br/>V1: DDL · V2: Indexes · V3: Seed Data"]
    end
    style DB fill:#FCE4EC,stroke:#C62828,color:#B71C1C

    %% ── Spring Batch ──
    subgraph BATCH["Spring Batch — 5 Jobs + Orchestrator"]
        direction TB
        ORCH["BatchPipelineOrchestrator<br/>(FlowBuilder Sequencing)"]
        JOB1["DailyTransactionPostingJob<br/>Reader → Processor → Writer"]
        JOB2["InterestCalculationJob<br/>Rate Lookup → Computation"]
        JOB3["CombineTransactionsJob<br/>Comparator Sort → Bulk Insert"]
        JOB4A["StatementGenerationJob<br/>Text + HTML → S3"]
        JOB4B["TransactionReportJob<br/>Date Filter → S3"]
        ORCH --> JOB1 --> JOB2 --> JOB3
        JOB3 --> JOB4A
        JOB3 --> JOB4B
    end
    style BATCH fill:#FFF9C4,stroke:#F9A825,color:#F57F17

    %% ── AWS Services (via LocalStack) ──
    subgraph AWS["AWS Services (via LocalStack)"]
        direction LR
        S3["☁️ AWS S3<br/>carddemo-batch-input<br/>carddemo-batch-output<br/>carddemo-statements<br/>(Versioned Objects<br/>replace GDG)"]
        SQS["📨 AWS SQS<br/>carddemo-report-jobs.fifo<br/>(Replaces CICS TDQ)"]
    end
    style AWS fill:#E8EAF6,stroke:#283593,color:#1A237E

    %% ── Observability Stack ──
    subgraph OBS["Observability Stack"]
        direction LR
        JAEGER["🔍 Jaeger<br/>Distributed Tracing<br/>(Micrometer OTEL)"]
        PROMETHEUS["📊 Prometheus<br/>Metrics Scraping<br/>(/actuator/prometheus)"]
        GRAFANA["📈 Grafana<br/>Dashboards<br/>(Request Rate, Latency,<br/>Batch Throughput)"]
        LOGBACK["📝 Structured Logging<br/>(Logback + JSON<br/>+ Correlation IDs)"]
    end
    style OBS fill:#E0F7FA,stroke:#00695C,color:#004D40

    %% ── Connections ──
    REST_CLIENT -->|"HTTP/JSON"| SECURITY
    SECURITY -->|"Authenticated<br/>Request"| CONTROLLERS
    CONTROLLERS -->|"@Autowired"| SERVICES
    SERVICES -->|"JpaRepository"| REPOS
    REPOS -->|"Hibernate/JDBC"| DB
    SERVICES -->|"S3Client"| S3
    RptSubSvc -->|"SqsTemplate.send()"| SQS
    SQS -->|"@SqsListener"| BATCH
    BATCH -->|"JpaRepository"| REPOS
    BATCH -->|"S3Client<br/>Read/Write"| S3
    CONTROLLERS -.->|"Tracing Spans"| JAEGER
    CONTROLLERS -.->|"Metrics"| PROMETHEUS
    PROMETHEUS -.->|"Data Source"| GRAFANA
```

**Legend:**

| Color | Component Type |
|-------|---------------|
| 🔵 Blue (`#E3F2FD`) | API client tier — REST clients with HTTP Basic Authentication |
| 🔴 Red (`#FFEBEE`) | Spring Security — filter chain, BCrypt, HTTP Basic Authentication |
| 🟠 Orange (`#FFF3E0`) | Controller layer — 8 Spring MVC REST controllers |
| 🟢 Green (`#E8F5E9`) | Service layer — 19 service classes organized by domain |
| 🟣 Purple (`#F3E5F5`) | Repository layer — 11 Spring Data JPA repository interfaces |
| 🔴 Pink (`#FCE4EC`) | Database — PostgreSQL 16+ with Flyway-managed schema |
| 🟡 Yellow (`#FFF9C4`) | Spring Batch — 5 jobs with orchestrator, processors, readers, writers |
| 🔷 Indigo (`#E8EAF6`) | AWS services — S3 (file staging) and SQS (message queue) via LocalStack |
| 🩵 Teal (`#E0F7FA`) | Observability — Jaeger, Prometheus, Grafana, structured logging |

---

## 3. Batch Pipeline — Before & After

The CardDemo batch processing pipeline executes as a 5-stage sequential chain. Each stage depends on the successful completion of its predecessor (controlled by JCL COND codes in the before-state and Spring Batch `ExitStatus` in the after-state). Stages 4a and 4b (statement generation and transaction reporting) can execute in parallel after Stage 3 completes.

### 3a. JCL Batch Pipeline (Before)

The original pipeline uses JCL job steps, DFSORT for sorting, IDCAMS REPRO for bulk data loading, GDG generation numbering (`(+1)` for new, `(0)` for current), and JCL `COND` parameters for conditional execution.

```mermaid
graph TD
    %% Title
    title3a["<b>JCL 5-Stage Batch Pipeline (Before)</b>"]
    style title3a fill:none,stroke:none,color:#333,font-size:16px

    %% ── Stage 1: POSTTRAN ──
    subgraph S1["Stage 1 — POSTTRAN.jcl"]
        S1_PGM["EXEC PGM=CBTRN02C<br/><i>Daily Transaction Posting</i>"]
        S1_IN1["📄 DALYTRAN.PS<br/>(Daily Txn Input)"]
        S1_IN2["📁 CARDXREF.VSAM.KSDS<br/>(Cross-Reference Lookup)"]
        S1_IN3["📁 ACCTDATA.VSAM.KSDS<br/>(Account Validation)"]
        S1_IN4["📁 TCATBALF.VSAM.KSDS<br/>(Category Balance Update)"]
        S1_OUT1["📁 TRANSACT.VSAM.KSDS<br/>(Posted Transactions)"]
        S1_OUT2["📄 DALYREJS(+1)<br/>(Rejected Records<br/>GDG Generation)"]

        S1_IN1 --> S1_PGM
        S1_IN2 --> S1_PGM
        S1_IN3 --> S1_PGM
        S1_IN4 --> S1_PGM
        S1_PGM -->|"Valid Txns"| S1_OUT1
        S1_PGM -->|"Rejected Txns<br/>(Codes 100–109)"| S1_OUT2
    end
    style S1 fill:#E3F2FD,stroke:#1565C0

    %% ── Stage 2: INTCALC ──
    subgraph S2["Stage 2 — INTCALC.jcl"]
        S2_PGM["EXEC PGM=CBACT04C<br/>PARM='2022071800'<br/><i>Interest Calculation</i>"]
        S2_IN1["📁 TCATBALF.VSAM.KSDS<br/>(Category Balances)"]
        S2_IN2["📁 DISCGRP.VSAM.KSDS<br/>(Interest Rates)"]
        S2_IN3["📁 CARDXREF.VSAM.AIX.PATH<br/>(Acct-to-Card Lookup)"]
        S2_IN4["📁 ACCTDATA.VSAM.KSDS<br/>(Account Data)"]
        S2_OUT1["📄 SYSTRAN(+1)<br/>(System-Generated Txns<br/>GDG Generation)"]

        S2_IN1 --> S2_PGM
        S2_IN2 --> S2_PGM
        S2_IN3 --> S2_PGM
        S2_IN4 --> S2_PGM
        S2_PGM -->|"Interest Txns<br/>(bal × rate) / 1200"| S2_OUT1
    end
    style S2 fill:#E8F5E9,stroke:#2E7D32

    %% ── Stage 3: COMBTRAN ──
    subgraph S3["Stage 3 — COMBTRAN.jcl"]
        S3_SORT["EXEC PGM=SORT (DFSORT)<br/>SORT FIELDS=(TRAN-ID,A)<br/><i>Sort + Merge</i>"]
        S3_REPRO["EXEC PGM=IDCAMS<br/>REPRO INFILE → OUTFILE<br/><i>Bulk Load to VSAM</i>"]
        S3_IN1["📄 TRANSACT.BKUP(0)<br/>(Current Txn Backup)"]
        S3_IN2["📄 SYSTRAN(0)<br/>(Current System Txns)"]
        S3_OUT1["📄 TRANSACT.COMBINED(+1)<br/>(Sorted Combined<br/>GDG Generation)"]
        S3_OUT2["📁 TRANSACT.VSAM.KSDS<br/>(Updated Master)"]

        S3_IN1 --> S3_SORT
        S3_IN2 --> S3_SORT
        S3_SORT -->|"Sorted Output"| S3_OUT1
        S3_OUT1 --> S3_REPRO
        S3_REPRO -->|"REPRO Load"| S3_OUT2
    end
    style S3 fill:#FFF3E0,stroke:#E65100

    %% ── Stage 4a: CREASTMT ──
    subgraph S4A["Stage 4a — CREASTMT.JCL (Parallel)"]
        S4A_PGM["EXEC PGM=CBSTM03A<br/><i>Statement Generation</i><br/>(Calls CBSTM03B)"]
        S4A_IN1["📁 TRANSACT.VSAM.KSDS"]
        S4A_IN2["📁 CARDXREF.VSAM.KSDS"]
        S4A_IN3["📁 ACCTDATA.VSAM.KSDS"]
        S4A_IN4["📁 CUSTDATA.VSAM.KSDS"]
        S4A_OUT1["📄 STATEMNT.PS<br/>(Text Statements)"]
        S4A_OUT2["📄 STATEMNT.HTML<br/>(HTML Statements)"]

        S4A_IN1 --> S4A_PGM
        S4A_IN2 --> S4A_PGM
        S4A_IN3 --> S4A_PGM
        S4A_IN4 --> S4A_PGM
        S4A_PGM --> S4A_OUT1
        S4A_PGM --> S4A_OUT2
    end
    style S4A fill:#F3E5F5,stroke:#6A1B9A

    %% ── Stage 4b: TRANREPT ──
    subgraph S4B["Stage 4b — TRANREPT.jcl (Parallel)"]
        S4B_SORT["EXEC PGM=SORT (DFSORT)<br/>SORT + INCLUDE COND<br/><i>Date Filter + Sort</i>"]
        S4B_PGM["EXEC PGM=CBTRN03C<br/><i>Transaction Report</i>"]
        S4B_IN1["📄 TRANSACT.BKUP(+1)<br/>(Fresh Backup)"]
        S4B_IN2["📁 CARDXREF.VSAM.KSDS"]
        S4B_IN3["📁 TRANTYPE.VSAM.KSDS"]
        S4B_IN4["📁 TRANCATG.VSAM.KSDS"]
        S4B_MID["📄 TRANSACT.DALY(+1)<br/>(Date-Filtered)"]
        S4B_OUT1["📄 TRANREPT(+1)<br/>(Formatted Report<br/>GDG Generation)"]

        S4B_IN1 --> S4B_SORT
        S4B_SORT -->|"Date Filter<br/>Start–End"| S4B_MID
        S4B_MID --> S4B_PGM
        S4B_IN2 --> S4B_PGM
        S4B_IN3 --> S4B_PGM
        S4B_IN4 --> S4B_PGM
        S4B_PGM --> S4B_OUT1
    end
    style S4B fill:#FCE4EC,stroke:#C62828

    %% ── Sequential Dependencies ──
    S1 -->|"COND=(0,NE)<br/>Proceed if RC=0"| S2
    S2 -->|"COND=(0,NE)<br/>Proceed if RC=0"| S3
    S3 -->|"COND=(0,NE)<br/>Parallel Fork"| S4A
    S3 -->|"COND=(0,NE)<br/>Parallel Fork"| S4B
```

**Legend (Before Pipeline):**

| Color | Stage | JCL Job | COBOL Program | Key Operation |
|-------|-------|---------|--------------|---------------|
| 🔵 Blue | Stage 1 | POSTTRAN.jcl | CBTRN02C | 4-stage validation, post to VSAM, reject to GDG |
| 🟢 Green | Stage 2 | INTCALC.jcl | CBACT04C | Interest: `(balance × rate) / 1200`, DEFAULT fallback |
| 🟠 Orange | Stage 3 | COMBTRAN.jcl | DFSORT + IDCAMS | Sort by TRAN-ID ascending, REPRO load to VSAM |
| 🟣 Purple | Stage 4a | CREASTMT.JCL | CBSTM03A/B | Per-card text + HTML statement generation |
| 🔴 Red | Stage 4b | TRANREPT.jcl | CBTRN03C + DFSORT | Date-filtered sort, formatted report |

### 3b. Spring Batch Pipeline (After)

The migrated pipeline uses Spring Batch `Job` and `Step` abstractions with `BatchPipelineOrchestrator` controlling sequencing via `FlowBuilder`. S3 versioned objects replace GDG generations. Stages 4a and 4b execute concurrently via `FlowBuilder.split()`.

```mermaid
graph TD
    %% Title
    title3b["<b>Spring Batch 5-Stage Pipeline (After)</b>"]
    style title3b fill:none,stroke:none,color:#333,font-size:16px

    %% ── Orchestrator ──
    ORCH["🎛️ BatchPipelineOrchestrator<br/>Spring Batch FlowBuilder<br/>Sequential Step Chaining<br/>+ split() for Parallel Stages"]
    style ORCH fill:#FFF176,stroke:#F57F17,color:#E65100

    %% ── Stage 1 ──
    subgraph S1B["Stage 1 — DailyTransactionPostingJob"]
        S1B_R["DailyTransactionReader<br/>(S3 File Reader)"]
        S1B_P["TransactionPostingProcessor<br/>4-Stage Validation Cascade:<br/>1. Card Exists? (code 100)<br/>2. Card Active? (code 101)<br/>3. Credit Limit? (code 102)<br/>4. Card Expired? (code 103)"]
        S1B_W1["TransactionWriter<br/>(DB + S3 Backup)"]
        S1B_W2["RejectWriter<br/>(S3 Rejection File)"]

        S1B_R -->|"ItemReader"| S1B_P
        S1B_P -->|"Valid"| S1B_W1
        S1B_P -->|"Rejected"| S1B_W2
    end
    style S1B fill:#E3F2FD,stroke:#1565C0

    %% ── Stage 2 ──
    subgraph S2B["Stage 2 — InterestCalculationJob"]
        S2B_R["JPA Reader<br/>(TransactionCategoryBalance)"]
        S2B_P["InterestCalculationProcessor<br/>Formula: (bal × rate) / 1200<br/>BigDecimal HALF_EVEN<br/>DEFAULT Group Fallback"]
        S2B_W["TransactionWriter<br/>(DB + S3 Output)"]

        S2B_R -->|"ItemReader"| S2B_P
        S2B_P -->|"Interest Txns"| S2B_W
    end
    style S2B fill:#E8F5E9,stroke:#2E7D32

    %% ── Stage 3 ──
    subgraph S3B["Stage 3 — CombineTransactionsJob"]
        S3B_R["JPA + S3 Reader<br/>(Backup + System Txns)"]
        S3B_P["TransactionCombineProcessor<br/>Java Comparator Sort<br/>by Transaction ID (ASC)"]
        S3B_W["Bulk JPA Insert<br/>(saveAll to transactions table)"]

        S3B_R -->|"ItemReader"| S3B_P
        S3B_P -->|"Sorted + Merged"| S3B_W
    end
    style S3B fill:#FFF3E0,stroke:#E65100

    %% ── Stage 4a ──
    subgraph S4AB["Stage 4a — StatementGenerationJob ⚡ Parallel"]
        S4AB_R["JPA Reader<br/>(Transactions by Card)"]
        S4AB_P["StatementProcessor<br/>In-Memory Buffering<br/>Dual-Format Output"]
        S4AB_W["StatementWriter<br/>(S3: Text + HTML)"]

        S4AB_R -->|"ItemReader"| S4AB_P
        S4AB_P -->|"Statements"| S4AB_W
    end
    style S4AB fill:#F3E5F5,stroke:#6A1B9A

    %% ── Stage 4b ──
    subgraph S4BB["Stage 4b — TransactionReportJob ⚡ Parallel"]
        S4BB_R["JPA Reader<br/>(Date-Filtered Txns)"]
        S4BB_P["TransactionReportProcessor<br/>Date Filtering + Enrichment<br/>Page/Account/Grand Totals"]
        S4BB_W["S3 Report Writer<br/>(Formatted Report)"]

        S4BB_R -->|"ItemReader"| S4BB_P
        S4BB_P -->|"Report Lines"| S4BB_W
    end
    style S4BB fill:#FCE4EC,stroke:#C62828

    %% ── External Resources ──
    S3_IN["☁️ AWS S3<br/>carddemo-batch-input<br/>(Versioned Objects)"]
    S3_OUT["☁️ AWS S3<br/>carddemo-batch-output<br/>carddemo-statements<br/>(Versioned Objects)"]
    PG_DB["🐘 PostgreSQL 16+<br/>(11 JPA-Managed Tables)"]

    style S3_IN fill:#E8EAF6,stroke:#283593
    style S3_OUT fill:#E8EAF6,stroke:#283593
    style PG_DB fill:#FCE4EC,stroke:#C62828

    %% ── Sequential Orchestration ──
    ORCH -->|"Step 1"| S1B
    S1B -->|"ExitStatus.COMPLETED"| S2B
    S2B -->|"ExitStatus.COMPLETED"| S3B
    S3B -->|"FlowBuilder.split()<br/>TaskExecutor"| S4AB
    S3B -->|"FlowBuilder.split()<br/>TaskExecutor"| S4BB

    %% ── I/O Connections ──
    S3_IN -.->|"Read Input"| S1B
    S1B -.->|"Write"| PG_DB
    S1B -.->|"Write"| S3_OUT
    S2B -.->|"Read/Write"| PG_DB
    S2B -.->|"Write"| S3_OUT
    S3B -.->|"Read/Write"| PG_DB
    S4AB -.->|"Read"| PG_DB
    S4AB -.->|"Write"| S3_OUT
    S4BB -.->|"Read"| PG_DB
    S4BB -.->|"Write"| S3_OUT
```

**Legend (After Pipeline):**

| Color | Stage | Spring Batch Job | Key Transformation from JCL |
|-------|-------|-----------------|----------------------------|
| 🟡 Yellow | Orchestrator | `BatchPipelineOrchestrator` | JCL COND codes → `ExitStatus` + `FlowBuilder` |
| 🔵 Blue | Stage 1 | `DailyTransactionPostingJob` | S3 reader replaces DALYTRAN.PS; DB + S3 output replaces VSAM + GDG |
| 🟢 Green | Stage 2 | `InterestCalculationJob` | JPA reader replaces VSAM; `BigDecimal` preserves formula precision |
| 🟠 Orange | Stage 3 | `CombineTransactionsJob` | `Comparator` sort replaces DFSORT; `saveAll` replaces IDCAMS REPRO |
| 🟣 Purple | Stage 4a | `StatementGenerationJob` | S3 output replaces sequential PS files; parallel via `split()` |
| 🔴 Red | Stage 4b | `TransactionReportJob` | S3 output replaces GDG `TRANREPT(+1)`; parallel via `split()` |
| 🔷 Indigo | — | AWS S3 | Versioned objects replace GDG generation numbering |
| 🔴 Pink | — | PostgreSQL | JPA-managed tables replace VSAM KSDS |

---

## 4. Data Migration — VSAM to PostgreSQL

All 10 VSAM KSDS datasets, 2 alternate indexes (AIX/PATH), and 1 sequential PS staging file are mapped to 11 PostgreSQL tables managed by Spring Data JPA entities. The Flyway migration pipeline executes three versioned scripts: `V1` creates the schema DDL, `V2` creates secondary indexes (replacing VSAM AIX/PATH definitions), and `V3` seeds data from the 9 ASCII fixture files.

```mermaid
graph LR
    %% Title
    title4["<b>Data Migration — VSAM to PostgreSQL</b>"]
    style title4 fill:none,stroke:none,color:#333,font-size:16px

    %% ── Source: VSAM Datasets ──
    subgraph VSAM_SRC["Source: VSAM Datasets (z/OS)"]
        direction TB
        VA1["ACCTDAT VSAM KSDS<br/>KEY(11,0) REC(300)"]
        VA2["CARDDAT VSAM KSDS<br/>KEY(16,0) REC(150)"]
        VA3["CUSTDAT VSAM KSDS<br/>KEY(9,0) REC(500)"]
        VA4["CARDXREF VSAM KSDS<br/>KEY(16,0) REC(50)<br/>+ AIX: CXACAIX KEY(11,25)"]
        VA5["TRANSACT VSAM KSDS<br/>KEY(16,0) REC(350)<br/>+ AIX: KEY(26,304)"]
        VA6["USRSEC VSAM KSDS<br/>KEY(8,0) REC(80)"]
        VA7["TCATBALF VSAM KSDS<br/>Composite Key"]
        VA8["DISCGRP VSAM KSDS<br/>Composite Key"]
        VA9["TRANTYPE VSAM KSDS<br/>REC(60)"]
        VA10["TRANCATG VSAM KSDS<br/>Composite Key"]
        VA11["DALYTRAN PS<br/>Sequential REC(350)"]
    end
    style VSAM_SRC fill:#FCE4EC,stroke:#C62828,color:#B71C1C

    %% ── Flyway Migration Pipeline ──
    subgraph FLYWAY["Flyway Migration Pipeline"]
        direction TB
        FV1["V1__create_schema.sql<br/>CREATE TABLE (11 tables)<br/>Primary Keys + Constraints"]
        FV2["V2__create_indexes.sql<br/>CREATE INDEX<br/>Alternate Index Equivalents"]
        FV3["V3__seed_data.sql<br/>INSERT INTO<br/>(9 ASCII fixture files)"]
        FV1 --> FV2 --> FV3
    end
    style FLYWAY fill:#FFF9C4,stroke:#F9A825,color:#F57F17

    %% ── Target: PostgreSQL Tables ──
    subgraph PG_TGT["Target: PostgreSQL 16+ Tables"]
        direction TB
        PT1["accounts<br/>PK: acct_id VARCHAR(11)<br/>@Version for optimistic lock<br/>BigDecimal: curr_bal, credit_limit"]
        PT2["cards<br/>PK: card_num VARCHAR(16)<br/>FK → accounts"]
        PT3["customers<br/>PK: cust_id VARCHAR(9)<br/>500-byte field mapping"]
        PT4["card_cross_references<br/>PK: card_num VARCHAR(16)<br/>IDX: xref_acct_id (AIX equiv)"]
        PT5["transactions<br/>PK: tran_id VARCHAR(16)<br/>IDX: proc_timestamp (AIX equiv)<br/>BigDecimal: tran_amt"]
        PT6["user_security<br/>PK: usr_id VARCHAR(8)<br/>BCrypt password hash ⬆️"]
        PT7["transaction_category_balances<br/>@EmbeddedId: acctId+typeCode+catCode"]
        PT8["disclosure_groups<br/>@EmbeddedId: groupId+typeCode+catCode<br/>BigDecimal: int_rate"]
        PT9["transaction_types<br/>PK: type_code VARCHAR(2)"]
        PT10["transaction_categories<br/>@EmbeddedId: typeCode+catCode"]
        PT11["daily_transactions<br/>Staging table (batch input)"]
    end
    style PG_TGT fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20

    %% ── ASCII Fixture Files ──
    subgraph ASCII_SRC["ASCII Fixture Files (9 files)"]
        direction TB
        AF1["acctdata.txt"]
        AF2["carddata.txt"]
        AF3["custdata.txt"]
        AF4["cardxref.txt"]
        AF5["dailytran.txt"]
        AF6["discgrp.txt"]
        AF7["tcatbal.txt"]
        AF8["trancatg.txt"]
        AF9["trantype.txt"]
    end
    style ASCII_SRC fill:#E3F2FD,stroke:#1565C0,color:#0D47A1

    %% ── Mapping Arrows ──
    VA1 -->|"Schema Mapping"| PT1
    VA2 -->|"Schema Mapping"| PT2
    VA3 -->|"Schema Mapping"| PT3
    VA4 -->|"Schema + AIX"| PT4
    VA5 -->|"Schema + AIX"| PT5
    VA6 -->|"Schema + BCrypt ⬆️"| PT6
    VA7 -->|"Composite Key"| PT7
    VA8 -->|"Composite Key"| PT8
    VA9 -->|"Schema Mapping"| PT9
    VA10 -->|"Composite Key"| PT10
    VA11 -->|"Schema Mapping"| PT11

    ASCII_SRC -->|"Parsed →<br/>INSERT INTO"| FV3
    FLYWAY -->|"Auto-Execute<br/>on Startup"| PG_TGT
```

**Legend:**

| Color | Component | Description |
|-------|-----------|-------------|
| 🔴 Red | VSAM Datasets | Source: 10 KSDS + 2 AIX/PATH + 1 PS — keyed sequential and indexed access |
| 🟡 Yellow | Flyway Pipeline | Migration engine: V1 (DDL) → V2 (indexes) → V3 (seed data), auto-executes on Spring Boot startup |
| 🟢 Green | PostgreSQL Tables | Target: 11 relational tables with JPA entities, composite keys via `@EmbeddedId`, `@Version` for optimistic lock |
| 🔵 Blue | ASCII Fixtures | 9 source data files parsed into SQL INSERT statements for V3 migration |
| ⬆️ | Security Upgrade | `USRSEC` plaintext passwords → BCrypt hashed passwords (constraint C-003 upgrade) |

---

## 5. Spring Boot Component Interaction — Layered Architecture

The Java application follows a strict layered architecture: Controllers handle HTTP request/response mapping, Services encapsulate business logic (one per COBOL program), Repositories abstract database access (one per VSAM dataset), and Entities map to PostgreSQL tables. Cross-cutting concerns (observability, security, validation) span all layers.

```mermaid
graph TB
    %% Title
    title5["<b>Spring Boot Component Interaction — Layered Architecture</b>"]
    style title5 fill:none,stroke:none,color:#333,font-size:16px

    %% ── Cross-Cutting Concerns ──
    subgraph XCUT["Cross-Cutting Concerns"]
        direction LR
        OBS_CFG["ObservabilityConfig<br/>• CorrelationIdFilter<br/>• Micrometer Tracing<br/>• Custom Metrics"]
        SEC_CFG["SecurityConfig<br/>• BCrypt Encoder<br/>• HTTP Basic Auth<br/>• Role-Based Access"]
        WEB_CFG["WebConfig<br/>• CORS Config<br/>• Jackson Serialization<br/>• Global Error Handling"]
    end
    style XCUT fill:#E0F7FA,stroke:#00695C,color:#004D40

    %% ── Controller Layer ──
    subgraph CTRL["Controller Layer — Spring MVC @RestController"]
        direction LR
        C1["AuthController<br/>POST /api/auth/signin"]
        C2["AccountController<br/>GET /api/accounts/{id}<br/>PUT /api/accounts/{id}"]
        C3["CardController<br/>GET /api/cards<br/>GET /api/cards/{num}<br/>PUT /api/cards/{num}"]
        C4["TransactionController<br/>GET /api/transactions<br/>GET /api/transactions/{id}<br/>POST /api/transactions"]
        C5["BillingController<br/>POST /api/billing/pay"]
        C6["ReportController<br/>POST /api/reports/submit"]
        C7["UserAdminController<br/>GET/POST/PUT/DELETE<br/>/api/admin/users"]
        C8["MenuController<br/>GET /api/menu/{type}"]
    end
    style CTRL fill:#FFF3E0,stroke:#E65100,color:#BF360C

    %% ── Service Layer ──
    subgraph SVC["Service Layer — Spring @Service Components"]
        direction LR

        subgraph SVC_COL1[" "]
            S_AUTH["AuthenticationService"]
            S_AV["AccountViewService"]
            S_AU["AccountUpdateService<br/>@Transactional"]
            S_CL["CardListService"]
            S_CD["CardDetailService"]
        end

        subgraph SVC_COL2[" "]
            S_CU["CardUpdateService<br/>@Version"]
            S_TL["TransactionListService"]
            S_TD["TransactionDetailService"]
            S_TA["TransactionAddService"]
            S_BP["BillPaymentService"]
        end

        subgraph SVC_COL3[" "]
            S_RS["ReportSubmissionService"]
            S_UL["UserListService"]
            S_UA["UserAddService"]
            S_UU["UserUpdateService"]
            S_UD["UserDeleteService"]
        end

        subgraph SVC_COL4[" "]
            S_MM["MainMenuService"]
            S_AM["AdminMenuService"]
        end
    end
    style SVC fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20

    %% ── Shared Services ──
    subgraph SHARED["Shared Services"]
        direction LR
        SS_DV["DateValidationService<br/>(← CSUTLDTC.cbl)"]
        SS_VL["ValidationLookupService<br/>(NANPA / State / ZIP)"]
        SS_FM["FileStatusMapper<br/>(FILE STATUS → Exception)"]
    end
    style SHARED fill:#FFF9C4,stroke:#F9A825,color:#F57F17

    %% ── Repository Layer ──
    subgraph REPO["Repository Layer — Spring Data JPA @Repository"]
        direction LR
        R1["AccountRepository"]
        R2["CardRepository"]
        R3["CustomerRepository"]
        R4["CardCrossReference-<br/>Repository"]
        R5["TransactionRepository"]
        R6["UserSecurityRepository"]
        R7["TxnCategoryBalance-<br/>Repository"]
        R8["DisclosureGroup-<br/>Repository"]
        R9["TxnTypeRepository"]
        R10["TxnCategoryRepository"]
        R11["DailyTransaction-<br/>Repository"]
    end
    style REPO fill:#F3E5F5,stroke:#6A1B9A,color:#4A148C

    %% ── Database Layer ──
    subgraph DBLAYER["Database Layer"]
        direction LR
        PG_INST["🐘 PostgreSQL 16+<br/>11 Tables · Flyway Managed"]
    end
    style DBLAYER fill:#FCE4EC,stroke:#C62828,color:#B71C1C

    %% ── External Services ──
    subgraph EXT["External Services (via LocalStack)"]
        direction LR
        EXT_S3["☁️ AWS S3<br/>Batch File Staging<br/>Statement Output<br/>Report Output"]
        EXT_SQS["📨 AWS SQS<br/>Report Job Queue<br/>(FIFO)"]
    end
    style EXT fill:#E8EAF6,stroke:#283593,color:#1A237E

    %% ── Dependency Arrows ──
    XCUT -.->|"Spans All Layers"| CTRL
    XCUT -.->|"Spans All Layers"| SVC

    C1 -->|"@Autowired"| S_AUTH
    C2 -->|"@Autowired"| S_AV
    C2 -->|"@Autowired"| S_AU
    C3 -->|"@Autowired"| S_CL
    C3 -->|"@Autowired"| S_CD
    C3 -->|"@Autowired"| S_CU
    C4 -->|"@Autowired"| S_TL
    C4 -->|"@Autowired"| S_TD
    C4 -->|"@Autowired"| S_TA
    C5 -->|"@Autowired"| S_BP
    C6 -->|"@Autowired"| S_RS
    C7 -->|"@Autowired"| S_UL
    C7 -->|"@Autowired"| S_UA
    C7 -->|"@Autowired"| S_UU
    C7 -->|"@Autowired"| S_UD
    C8 -->|"@Autowired"| S_MM
    C8 -->|"@Autowired"| S_AM

    SVC -->|"JpaRepository<br/>Methods"| REPO
    SVC -->|"Validation"| SHARED
    REPO -->|"Hibernate<br/>JDBC"| DBLAYER
    S_RS -->|"SqsTemplate"| EXT_SQS
    SVC -.->|"S3Client"| EXT_S3
```

**Legend:**

| Color | Layer | Description |
|-------|-------|-------------|
| 🩵 Teal | Cross-Cutting | Observability (tracing, metrics, correlation IDs), Security (BCrypt, HTTP Basic Auth), Web (CORS, serialization) |
| 🟠 Orange | Controller | 8 REST controllers — HTTP request routing and response mapping |
| 🟢 Green | Service | 19 service classes — business logic (1:1 mapping from COBOL programs) |
| 🟡 Yellow | Shared Services | 3 shared utilities — date validation, lookup tables, FILE STATUS mapping |
| 🟣 Purple | Repository | 11 JPA repository interfaces — database access abstraction |
| 🔴 Red | Database | PostgreSQL 16+ — 11 Flyway-managed tables |
| 🔷 Indigo | External | AWS S3 (file staging) and SQS (message queue) via LocalStack |

---

## 6. Authentication Flow — Before & After

The authentication flow demonstrates the transformation from CICS pseudo-conversational 3270 terminal interaction to REST API with HTTP Basic Authentication. The key security upgrade is the replacement of plaintext password comparison with BCrypt hash verification.

### 6a. CICS Authentication Flow (Before)

The original sign-on uses BMS screen `COSGN0A`, pseudo-conversational `RETURN TRANSID('CC00') COMMAREA`, plaintext password comparison against the `USRSEC` VSAM file, and `XCTL` program transfer to route users to the appropriate menu based on user type (Admin → `COADM01C`, Regular → `COMEN01C`).

```mermaid
sequenceDiagram
    autonumber
    title CICS Authentication Flow (Before)

    actor User as 👤 3270 Terminal User
    participant BMS as BMS Map<br/>COSGN0A
    participant COSGN as COSGN00C<br/>(CICS Program)
    participant USRSEC as USRSEC<br/>VSAM KSDS<br/>KEY(8,0) REC(80)
    participant COADM as COADM01C<br/>(Admin Menu)
    participant COMEN as COMEN01C<br/>(Main Menu)

    Note over User,COMEN: Initial Screen Display (EIBCALEN = 0)
    COSGN->>BMS: SEND MAP('COSGN0A')<br/>MAPSET('COSGN00') ERASE CURSOR
    BMS->>User: Display sign-on screen<br/>(User ID + Password fields)

    Note over User,COMEN: User Submits Credentials (ENTER key → DFHENTER)
    User->>BMS: Enter USERID + PASSWD
    BMS->>COSGN: RECEIVE MAP('COSGN0A')<br/>MAPSET('COSGN00')

    Note over COSGN: Validate input fields (not empty)
    COSGN->>COSGN: UPPER-CASE(USERIDI)<br/>UPPER-CASE(PASSWDI)

    Note over COSGN,USRSEC: Read user security record
    COSGN->>USRSEC: READ DATASET('USRSEC')<br/>RIDFLD(WS-USER-ID)<br/>INTO(SEC-USER-DATA)

    alt RESP = 0 (Record Found)
        alt SEC-USR-PWD = WS-USER-PWD (Plaintext Match ⚠️)
            COSGN->>COSGN: Set COMMAREA:<br/>CDEMO-USER-ID<br/>CDEMO-USER-TYPE<br/>CDEMO-FROM-TRANID('CC00')

            alt CDEMO-USRTYP-ADMIN (Type = 'A')
                COSGN->>COADM: XCTL PROGRAM('COADM01C')<br/>COMMAREA(CARDDEMO-COMMAREA)
                Note over COADM: Admin menu displayed
            else CDEMO-USRTYP-USER (Type = 'U')
                COSGN->>COMEN: XCTL PROGRAM('COMEN01C')<br/>COMMAREA(CARDDEMO-COMMAREA)
                Note over COMEN: Main menu displayed
            end
        else Password Mismatch
            COSGN->>BMS: SEND MAP with<br/>"Wrong Password. Try again ..."
            BMS->>User: Redisplay sign-on screen
        end
    else RESP = 13 (Record Not Found)
        COSGN->>BMS: SEND MAP with<br/>"User not found. Try again ..."
        BMS->>User: Redisplay sign-on screen
    else RESP = OTHER (I/O Error)
        COSGN->>BMS: SEND MAP with<br/>"Unable to verify the User ..."
        BMS->>User: Redisplay sign-on screen
    end

    Note over COSGN: RETURN TRANSID('CC00')<br/>COMMAREA(CARDDEMO-COMMAREA)<br/>(Pseudo-conversational return)
```

**Legend (Before Authentication):**

| Symbol | Meaning |
|--------|---------|
| ⚠️ Plaintext Match | Password compared as plaintext string — security vulnerability (constraint C-003) |
| `XCTL` | CICS transfer control — loads target program with COMMAREA, original program terminates |
| `RETURN TRANSID` | Pseudo-conversational return — CICS suspends task, resumes on next user input with COMMAREA |
| `RESP = 13` | CICS response code for "record not found" in VSAM |

### 6b. Spring Security Authentication Flow (After)

The migrated authentication uses Spring Security HTTP Basic Authentication with BCrypt password hash verification for all API requests. The sign-in endpoint (`POST /api/auth/signin`) provides initial credential validation and returns a UUID session token for client tracking, while subsequent requests authenticate via HTTP Basic Auth (`Authorization: Basic <base64(userId:password)>`). The security filter chain delegates to `UserSecurityRepository` for credential lookup.

```mermaid
sequenceDiagram
    autonumber
    title Spring Security Authentication Flow (After)

    actor Client as 🌐 REST API Client
    participant Filter as Spring Security<br/>Filter Chain
    participant AuthCtrl as AuthController<br/>POST /api/auth/signin
    participant AuthSvc as AuthenticationService
    participant UserRepo as UserSecurityRepository<br/>(Spring Data JPA)
    participant PG as 🐘 PostgreSQL<br/>user_security table

    Note over Client,PG: Initial Sign-In (Optional — validates credentials and returns session info)
    Client->>Filter: POST /api/auth/signin<br/>{userId: "...", password: "..."}
    Filter->>Filter: Skip auth for /api/auth/** (permitAll)
    Filter->>AuthCtrl: Forward request (no auth required)

    AuthCtrl->>AuthSvc: authenticate(SignOnRequest)

    Note over AuthSvc,PG: Lookup user record
    AuthSvc->>UserRepo: findBySecUsrId(userId)
    UserRepo->>PG: SELECT * FROM user_security<br/>WHERE usr_id = ?
    PG-->>UserRepo: UserSecurity entity
    UserRepo-->>AuthSvc: Optional<UserSecurity>

    alt User Found
        AuthSvc->>AuthSvc: BCrypt.checkpw(password, storedHash) 🔒
        alt Password Matches
            AuthSvc->>AuthSvc: Generate UUID session token<br/>UUID.randomUUID()
            AuthSvc-->>AuthCtrl: SignOnResponse(token, userType,<br/>userId, toTranId, toProgram)
            AuthCtrl-->>Client: 200 OK<br/>{token, userType, userId, toTranId, toProgram}

            Note over Client,PG: Subsequent Authenticated Requests (HTTP Basic Auth)
            Client->>Filter: GET /api/accounts/{id}<br/>Authorization: Basic base64(userId:password)
            Filter->>Filter: Decode Basic credentials<br/>Lookup user via UserDetailsService
            Filter->>PG: SELECT * FROM user_security<br/>WHERE usr_id = ?
            Filter->>Filter: BCrypt.checkpw(password, hash)<br/>Set SecurityContext with authorities
            Note over Filter: Request proceeds to controller<br/>with authenticated principal
        else Password Mismatch
            AuthSvc-->>AuthCtrl: throw ValidationException<br/>("Invalid credentials")
            AuthCtrl-->>Client: 401 Unauthorized<br/>{error: "Invalid credentials"}
        end
    else User Not Found
        AuthSvc-->>AuthCtrl: throw RecordNotFoundException<br/>("User not found")
        AuthCtrl-->>Client: 401 Unauthorized<br/>{error: "User not found"}
    else Database Error
        AuthSvc-->>AuthCtrl: throw CardDemoException<br/>("Unable to verify user")
        AuthCtrl-->>Client: 500 Internal Server Error<br/>{error: "Unable to verify user"}
    end
```

**Legend (After Authentication):**

| Symbol | Meaning |
|--------|---------|
| 🔒 BCrypt | Password verified via BCrypt hash comparison — replaces plaintext (security upgrade) |
| HTTP Basic Auth | HTTP Basic Authentication (`Authorization: Basic base64(userId:password)`) — replaces CICS COMMAREA pseudo-conversational state |
| UUID Token | Session tracking token returned by sign-in — for client-side session management, not for API authentication |
| `permitAll` | Spring Security configuration allows unauthenticated access to sign-in endpoint |
| `SecurityContext` | Spring Security thread-local context — replaces CICS COMMAREA for user identity propagation |
| `@Transactional` | Not shown in auth flow, but `AccountUpdateService` uses it for SYNCPOINT ROLLBACK equivalence |

---

## Cross-Reference: Before → After Mapping Summary

This table summarizes the architectural transformation for quick reference. Each row maps a source z/OS component to its Java/AWS equivalent.

| # | z/OS Component (Before) | Java/AWS Component (After) | Transformation |
|---|------------------------|---------------------------|----------------|
| 1 | 3270 Terminals + BMS Maps (17) | REST API Clients + 8 Spring MVC Controllers | Screen I/O → HTTP/JSON |
| 2 | CICS Region (18 online programs) | 19 Spring Service classes | Pseudo-conversational → Stateless REST |
| 3 | JES Batch (10 programs) | Spring Batch (5 jobs + processors/readers/writers) | JCL steps → Spring Batch steps |
| 4 | VSAM KSDS (10 datasets) | PostgreSQL 16+ (11 tables) | Keyed sequential → Relational |
| 5 | VSAM AIX/PATH (2 alternate indexes) | JPA secondary queries + DB indexes | AIX → `@Query` / derived queries |
| 6 | DALYTRAN PS (sequential) | daily_transactions table (staging) | Sequential → Relational staging |
| 7 | GDG Bases (6, LIMIT 5) | S3 versioned objects (3 buckets) | Generation numbering → S3 versioning |
| 8 | CICS TDQ (JOBS queue) | AWS SQS FIFO queue | Point-to-point → SQS FIFO |
| 9 | DFSORT + IDCAMS REPRO | Java Comparator + `saveAll()` | Utility programs → Java collections |
| 10 | Plaintext passwords (USRSEC) | BCrypt hash (user_security) | Security vulnerability → BCrypt |
| 11 | CICS SYNCPOINT ROLLBACK | `@Transactional(rollbackFor=...)` | Explicit → Declarative |
| 12 | CICS XCTL COMMAREA | HTTP Basic Auth + Spring Security | Pseudo-conversational → Stateless REST |
| 13 | LE CEEDAYS date validation | `java.time.LocalDate` + custom validators | Language Environment → Java API |
| 14 | COBOL COMP-3 / COMP fields | `BigDecimal` (zero floating-point) | Packed decimal → Exact precision |
| 15 | FILE STATUS codes | Custom exception hierarchy + `FileStatus` enum | Status codes → Exceptions |
| 16 | JCL COND parameters | Spring Batch `ExitStatus` + `JobExecutionDecider` | Condition codes → Flow decisions |
| 17 | COBOL copybooks (28) | Java POJOs, DTOs, Entities, Enums | COPY → import |
| 18 | (None — no observability) | Jaeger + Prometheus + Grafana + Structured Logging | New capability added |

---

> **Document Version:** Generated for CardDemo Java migration from COBOL source commit `27d6c6f`.
> See [TRACEABILITY_MATRIX.md](../TRACEABILITY_MATRIX.md) for 100% COBOL paragraph → Java method mapping.
> See [DECISION_LOG.md](../DECISION_LOG.md) for all non-trivial architectural decisions.
