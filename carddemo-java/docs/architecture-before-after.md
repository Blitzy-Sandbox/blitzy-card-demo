# Architecture — Before / After (z/OS → Java / AWS)

> **Visual Architecture documentation** for the **AWS CardDemo** COBOL → Java migration.
> This document satisfies the project's *Visual Architecture* rule: every architectural aspect that
> changes is shown with **both** its **before** (z/OS mainframe) **and after** (Java / AWS) states,
> using **Mermaid diagrams only** — rendered as text, with no embedded raster images, no external
> diagram-tool exports, and no ASCII art.

## About this document

- **Source of truth (reference only):** the original COBOL / CICS / VSAM / JCL / BMS application at
  commit SHA **`27d6c6f`** (from the version string `CardDemo_v1.0-15-g27d6c6f-68`). **The COBOL
  sources are not copied** into the Java project; the diagrams below translate their *behavior,
  layouts, and contracts* and trace each element back to that commit.
- **Scope discipline:** only the migrated, closed feature set (F-001 through F-022), the **11**
  data entities, and the **5-stage** batch pipeline are diagrammed. No components are invented —
  there is no Db2, IMS, or IBM MQ in the source, and none appear here. The Google Cloud emulator
  attached to the project is out of scope and is therefore **not** depicted.
- **Rendering:** the Mermaid blocks target **Mermaid 11.x** (the same major version pinned by
  `docs/executive-presentation.html`, `mermaid@11.4.0`). They render on GitHub, in IDEs with a
  Mermaid plugin, or via the Mermaid Live Editor.
- **Companion reading:** the prose narrative lives in
  [`carddemo-java/README.md`](../README.md) (*Architecture* section); the per-program mapping lives
  in [`TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md); the REST/SQS/S3 contracts live in
  [`docs/api-contracts.md`](api-contracts.md).

## Diagram index

Each diagram is referenced by name throughout this document and in the sibling docs.

| # | Diagram name | Kind | Before / After coverage |
| :- | :----------- | :--- | :---------------------- |
| 1 | *Diagram 1 — Before-State z/OS Architecture* | `flowchart` | Before half of the architecture pair (with *Diagram 2*) |
| 2 | *Diagram 2 — After-State Java/AWS Architecture* | `flowchart` | After half of the architecture pair (with *Diagram 1*) |
| 3 | *Diagram 3 — Batch Pipeline (Before/After)* | `flowchart` | Both, side by side |
| 4 | *Diagram 4 — Data Migration: VSAM → PostgreSQL via Flyway (Before/After)* | `flowchart` | Both, source → target |
| 5 | *Diagram 5 — Component Interaction: Controller → Service → Repository → Database (Before/After)* | `sequenceDiagram` | Both (COBOL inline vs. layered Spring) |
| 6 | *Diagram 6 — Authentication Flow (Before/After)* | `sequenceDiagram` | Both, sign-on path |

> **Global before/after convention** used in every flowchart: nodes inside a **`Before (z/OS)`**
> subgraph are shaded **red/terracotta**; nodes inside an **`After (Java/AWS)`** subgraph are shaded
> **blue**; **migration/Flyway** helpers are shaded **green**; **data stores** (PostgreSQL, S3) use a
> **cylinder** shape in a **purple** tint. Sequence diagrams label their `Before`/`After` halves in
> the heading and notes.

---

## Diagram 1 — Before-State z/OS Architecture

*Diagram 1 — Before-State z/OS Architecture* captures the source system exactly as it runs on z/OS:
**BMS 3270** terminals drive **CICS** pseudo-conversational programs that thread conversational state
through the `CARDDEMO-COMMAREA`; data lives in **VSAM** KSDS/AIX/PATH datasets; security is enforced
by **RACF** over the plaintext `USRSEC` file; date checks call **Language Environment** `CEEDAYS`
(via `CSUTLDTC`); and the online-to-batch handoff is a CICS **TDQ** (`WRITEQ TD` in `CORPT00C`) that
feeds **JES** batch **JCL** job streams writing **GDG** generation datasets. Its after-state
counterpart is *Diagram 2 — After-State Java/AWS Architecture*.

```mermaid
flowchart TB
    subgraph ZOS["Before (z/OS) — CICS / VSAM / JCL Mainframe"]
        direction TB
        TERM["BMS 3270 Terminals<br/>(green-screen end users)"]

        subgraph CICSRGN["CICS Region — Pseudo-conversational Online"]
            direction TB
            SIGN["COSGN00C — Sign-on"]
            MENU["COMEN01C / COADM01C — Menu routing"]
            ONLINE["Online programs:<br/>COACTVWC · COACTUPC<br/>COCRDLIC · COCRDSLC · COCRDUPC<br/>COTRN00C · COTRN01C · COTRN02C<br/>COBIL00C · CORPT00C<br/>COUSR00C..COUSR03C"]
            COMM["CARDDEMO-COMMAREA<br/>conversational state"]
        end

        subgraph SUBSYS["Mainframe Services"]
            direction TB
            RACF["RACF / USRSEC<br/>plaintext SEC-USR-PWD"]
            LEDATE["LE Date Services<br/>CEEDAYS (CSUTLDTC)"]
            TDQ["CICS TDQ<br/>WRITEQ TD (CORPT00C)"]
        end

        subgraph VSAMDS["VSAM Datasets — KSDS / AIX / PATH (11)"]
            direction TB
            VA["ACCTDAT · CARDDAT · CUSTDAT"]
            VB["CARDXREF (CXACAIX) · TRANSACT (AIX)"]
            VC["USRSEC · TCATBAL · DISCGRP"]
            VD["TRANTYPE · TRANCATG · DALYTRAN"]
        end

        subgraph JESBATCH["JES Batch — JCL Job Streams"]
            direction TB
            JCL["POSTTRAN → INTCALC → COMBTRAN<br/>→ CREASTMT / TRANREPT<br/>(COND condition codes)"]
            BATPGM["CBTRN01C/02C/03C · CBACT01C-04C<br/>CBCUS01C · CBSTM03A/03B"]
            GDG["GDG generation datasets<br/>(DEFGDGB: BKUP / DALY / SYSTRAN / COMBINED)"]
        end
    end

    TERM -->|"3270 datastream"| SIGN
    SIGN --> MENU
    MENU --> ONLINE
    ONLINE <-->|"LINK / XCTL via COMMAREA"| COMM
    SIGN -->|"READ USRSEC"| RACF
    ONLINE -->|"CALL CSUTLDTC"| LEDATE
    ONLINE -->|"keyed / browse I/O"| VSAMDS
    ONLINE -->|"WRITEQ TD"| TDQ
    TDQ -->|"triggers nightly run"| JCL
    JCL --> BATPGM
    BATPGM -->|"sequential I/O"| VSAMDS
    BATPGM --> GDG

    classDef before fill:#fbe4e4,stroke:#b23b3b,color:#3a2020
    class TERM,SIGN,MENU,ONLINE,COMM,RACF,LEDATE,TDQ,VA,VB,VC,VD,JCL,BATPGM,GDG before
```

### Legend — Diagram 1

- **Outer subgraph `Before (z/OS)`** — everything here is part of the source mainframe and is
  replaced in *Diagram 2*; all nodes are shaded **red/terracotta** per the global convention.
- **Nodes** — labelled with the originating COBOL program(s), copybook(s), or dataset group(s).
- **Solid arrow `-->`** — a control-flow or I/O relationship; the arrow label names the mechanism
  (e.g., `WRITEQ TD`, `READ USRSEC`, `CALL CSUTLDTC`).
- **Bidirectional arrow `<-->`** — conversational state passed both ways through the COMMAREA on
  each pseudo-conversational turn.
- **Groupings** — `CICS Region` (online), `Mainframe Services` (security/date/TDQ), `VSAM Datasets`
  (the 11 persistent stores), and `JES Batch` (the JCL job streams + batch programs + GDG output).

---

## Diagram 2 — After-State Java/AWS Architecture

*Diagram 2 — After-State Java/AWS Architecture* is the target topology that replaces *Diagram 1*.
Stateless **REST/JSON** clients call **Spring MVC** controllers behind **Spring Security + JWT**
(OAuth2 resource server, BCrypt); controllers delegate to **service** classes (one per online COBOL
program), which use **Spring Data JPA** repositories over **PostgreSQL 16** (provisioned by
**Flyway**). Overnight work runs as **Spring Batch** jobs; the online-to-batch handoff is an **SQS
FIFO** publication; generation/staging datasets move to **S3**; notifications fan out over **SNS**;
and the whole service is instrumented for **observability** (Micrometer + OpenTelemetry → Jaeger,
Prometheus, Grafana). All AWS calls run against **LocalStack** locally.

```mermaid
flowchart TB
    subgraph AFTER["After (Java/AWS) — Java 25 + Spring Boot 3.5.x"]
        direction TB
        CLIENT["REST / JSON Clients<br/>(replaces BMS 3270)"]
        JWT["Spring Security + JWT<br/>OAuth2 Resource Server · BCrypt · stateless"]

        subgraph WEB["Web Layer — Spring MVC Controllers (8)"]
            direction TB
            CTRL["AuthController · AccountController · CardController<br/>TransactionController · BillingController<br/>ReportController · UserAdminController · MenuController"]
        end

        subgraph SVC["Service Layer (one service per online COBOL program)"]
            direction TB
            SVCS["AuthenticationService · AccountViewService · AccountUpdateService<br/>Card*Service · Transaction*Service · BillPaymentService<br/>ReportSubmissionService · User*Service · MainMenuService / AdminMenuService<br/>DateValidationService · ValidationLookupService · FileStatusMapper"]
        end

        subgraph REPO["Spring Data JPA Repositories (11)"]
            direction TB
            REPOS["AccountRepository · CardRepository · CustomerRepository<br/>CardCrossReferenceRepository · TransactionRepository · UserSecurityRepository<br/>TransactionCategoryBalanceRepository · DisclosureGroupRepository<br/>TransactionTypeRepository · TransactionCategoryRepository · DailyTransactionRepository"]
        end

        DB[("PostgreSQL 16<br/>11 tables + indexes")]
        FLYWAY["Flyway V1 / V2 / V3<br/>schema · indexes · seed"]

        subgraph BATCH["Spring Batch Jobs (5-stage pipeline)"]
            direction TB
            JOBS["DailyTransactionPostingJob → InterestCalculationJob<br/>→ CombineTransactionsJob → split: StatementGenerationJob / TransactionReportJob<br/>BatchPipelineOrchestrator (JobExecutionDecider · ExitStatus)"]
        end

        subgraph AWSCLOUD["AWS via Spring Cloud AWS (LocalStack locally)"]
            direction TB
            S3[("S3 buckets<br/>carddemo-batch-input<br/>carddemo-batch-output<br/>carddemo-statements")]
            SQS["SQS FIFO<br/>carddemo-report-jobs.fifo"]
            SNS["SNS topic<br/>carddemo-notifications"]
        end

        subgraph OBS["Observability"]
            direction TB
            OTEL["Micrometer + OpenTelemetry"]
            JAEGER["Jaeger — traces"]
            PROM["Prometheus — metrics"]
            GRAF["Grafana — dashboards"]
        end
    end

    CLIENT -->|"HTTPS REST/JSON"| JWT
    JWT --> CTRL
    CTRL --> SVCS
    SVCS --> REPOS
    REPOS -->|"JPA / JDBC"| DB
    FLYWAY -->|"migrate on startup"| DB
    SVCS -->|"ReportSubmissionService publish"| SQS
    SQS -->|"FIFO trigger"| JOBS
    JOBS --> REPOS
    JOBS -->|"read / write objects"| S3
    JOBS -->|"notify"| SNS
    OTEL --> JAEGER
    OTEL --> PROM
    PROM --> GRAF
    CTRL -.->|"instrumented"| OTEL
    JOBS -.->|"instrumented"| OTEL

    classDef after fill:#dce9f7,stroke:#2f6db0,color:#16314d
    classDef store fill:#e7dcf7,stroke:#6a3fb0,color:#2a1a4d
    class CLIENT,JWT,CTRL,SVCS,REPOS,FLYWAY,JOBS,SQS,SNS,OTEL,JAEGER,PROM,GRAF after
    class DB,S3 store
```

### Legend — Diagram 2

- **Outer subgraph `After (Java/AWS)`** — the target system; all service/controller/repository/job
  nodes are shaded **blue** per the global convention.
- **Cylinder + purple tint** — data stores: **PostgreSQL 16** (`DB`) and **S3** (`S3`).
- **Solid arrow `-->`** — synchronous request/data flow (HTTP, JPA/JDBC, SQS trigger, S3 I/O);
  the label names the mechanism.
- **Dotted arrow `-.->`** — cross-cutting **instrumentation** (controllers and batch jobs emit
  traces/metrics to Micrometer/OpenTelemetry).
- **Before → After correspondence:** REST controllers replace BMS 3270; JWT claims replace the
  COMMAREA; JPA repositories + PostgreSQL replace VSAM; Spring Batch replaces JES/JCL; SQS FIFO
  replaces the CICS TDQ; S3 replaces GDG datasets; `DateValidationService` replaces `CEEDAYS`; and
  Spring Security + BCrypt replace RACF/plaintext `USRSEC` — each detailed in the sections below and
  in *Table — Authoritative Before → After Mapping*.

---

## Diagram 3 — Batch Pipeline (Before/After)

*Diagram 3 — Batch Pipeline (Before/After)* shows the overnight processing stream in both worlds.
**Before**, **JES** runs the JCL 5-stage chain `POSTTRAN → INTCALC → COMBTRAN → CREASTMT / TRANREPT`,
gating each step with `COND` condition codes; stage 3 (`COMBTRAN.jcl`) sorts with **DFSORT**
(`SORT FIELDS=(TRAN-ID,A)`) and loads the master with IDCAMS **`REPRO`**. **After**, the identical
5-stage flow is a **Spring Batch** pipeline — `DailyTransactionPostingJob → InterestCalculationJob →
CombineTransactionsJob → split: StatementGenerationJob (4a) / TransactionReportJob (4b)` — where
`JobExecutionDecider` + `ExitStatus` replace `COND` logic, `FlowBuilder.split()` runs stages 4a/4b in
parallel, and the DFSORT + REPRO step becomes a `Comparator` (by `tranId` ascending) followed by a
bulk JPA insert. The two halves appear side by side so each stage lines up with its successor.

```mermaid
flowchart LR
    subgraph BEFORE["Before (z/OS) — JCL 5-Stage Job Stream (JES)"]
        direction TB
        B1["Stage 1 — POSTTRAN<br/>CBTRN02C posting"]
        B2["Stage 2 — INTCALC<br/>CBACT04C interest"]
        B3["Stage 3 — COMBTRAN<br/>DFSORT SORT FIELDS=(TRAN-ID,A)<br/>+ IDCAMS REPRO"]
        B4A["Stage 4a — CREASTMT<br/>CBSTM03A / CBSTM03B statements"]
        B4B["Stage 4b — TRANREPT<br/>CBTRN03C report"]
        B1 -->|"COND code OK"| B2
        B2 -->|"COND code OK"| B3
        B3 -->|"COND code OK"| B4A
        B3 -->|"COND code OK"| B4B
    end

    subgraph AFTERB["After (Java/AWS) — Spring Batch 5-Stage Pipeline"]
        direction TB
        A1["Stage 1 — DailyTransactionPostingJob<br/>4-stage validation cascade"]
        A2["Stage 2 — InterestCalculationJob<br/>BigDecimal HALF_EVEN"]
        A3["Stage 3 — CombineTransactionsJob<br/>Comparator by tranId ASC<br/>+ bulk JPA insert"]
        A4A["Stage 4a — StatementGenerationJob<br/>text + HTML to S3"]
        A4B["Stage 4b — TransactionReportJob<br/>date-filtered report to S3"]
        A1 -->|"ExitStatus / Decider"| A2
        A2 -->|"ExitStatus / Decider"| A3
        A3 -->|"FlowBuilder.split()"| A4A
        A3 -->|"FlowBuilder.split()"| A4B
    end

    B1 -.->|"migrates to"| A1
    B2 -.->|"migrates to"| A2
    B3 -.->|"migrates to"| A3
    B4A -.->|"migrates to"| A4A
    B4B -.->|"migrates to"| A4B

    classDef before fill:#fbe4e4,stroke:#b23b3b,color:#3a2020
    classDef after fill:#dce9f7,stroke:#2f6db0,color:#16314d
    class B1,B2,B3,B4A,B4B before
    class A1,A2,A3,A4A,A4B after
```

### Legend — Diagram 3

- **Left subgraph `Before (z/OS)`** (red) — the JCL job stream as scheduled under JES; each job is
  annotated with its driving batch COBOL program.
- **Right subgraph `After (Java/AWS)`** (blue) — the equivalent Spring Batch jobs; class names match
  `batch/jobs/*` exactly (`DailyTransactionPostingJob`, `InterestCalculationJob`,
  `CombineTransactionsJob`, `StatementGenerationJob`, `TransactionReportJob`).
- **Solid arrow `-->`** — stage ordering; the **before** label is the JCL `COND` gate, the **after**
  label is the Spring Batch `ExitStatus` / `JobExecutionDecider` (and `FlowBuilder.split()` for the
  parallel 4a/4b fan-out).
- **Dotted arrow `-.->` `migrates to`** — maps each before stage to its after job (1:1).
- **Stage 3 detail** — DFSORT `SORT FIELDS=(TRAN-ID,A)` + IDCAMS `REPRO` (from `COMBTRAN.jcl`)
  become an in-memory `Comparator` (ascending `tranId`) plus a bulk JPA insert, preserving sort-key
  semantics and ascending order.

---

## Diagram 4 — Data Migration: VSAM → PostgreSQL via Flyway (Before/After)

*Diagram 4 — Data Migration: VSAM → PostgreSQL via Flyway (Before/After)* maps each of the **11 VSAM
datasets** (the *before* persistent stores, each defined by its COBOL copybook) to its **PostgreSQL
16** table (the *after* store). The schema is provisioned and seeded entirely by **Flyway** on
startup: `V1__create_schema.sql` creates the 11 tables (from the `DEFINE CLUSTER` JCL),
`V2__create_indexes.sql` creates primary and alternate indexes (notably the `CXACAIX` cross-reference
AIX and the `TRANSACT` AIX), and `V3__seed_data.sql` loads the **9 ASCII fixtures**. Table names match
the implemented JPA entities exactly.

```mermaid
flowchart LR
    subgraph SRC["Before (z/OS) — VSAM Datasets (11)"]
        direction TB
        D1["ACCTDAT (CVACT01Y)"]
        D2["CARDDAT (CVACT02Y)"]
        D3["CUSTDAT (CVCUS01Y)"]
        D4["CARDXREF (CVACT03Y · CXACAIX)"]
        D5["TRANSACT (CVTRA05Y · AIX)"]
        D6["USRSEC (CSUSR01Y)"]
        D7["TCATBAL (CVTRA01Y)"]
        D8["DISCGRP (CVTRA02Y)"]
        D9["TRANTYPE (CVTRA03Y)"]
        D10["TRANCATG (CVTRA04Y)"]
        D11["DALYTRAN (CVTRA06Y)"]
    end

    subgraph FW["Flyway Migrations (applied on startup)"]
        direction TB
        F1["V1__create_schema.sql<br/>tables ← DEFINE CLUSTER"]
        F2["V2__create_indexes.sql<br/>PK + AIX (CXACAIX · TRANSACT AIX)"]
        F3["V3__seed_data.sql<br/>9 ASCII fixtures"]
        ASCII["ASCII fixtures (9):<br/>acctdata · carddata · custdata · cardxref · dailytran<br/>discgrp · tcatbal · trancatg · trantype"]
        ASCII -->|"loaded by"| F3
    end

    subgraph PG["After (Java/AWS) — PostgreSQL 16 Tables (11)"]
        direction TB
        T1[("account")]
        T2[("card")]
        T3[("customer")]
        T4[("card_xref")]
        T5[("transaction")]
        T6[("user_security")]
        T7[("tran_cat_balance")]
        T8[("disclosure_group")]
        T9[("transaction_type")]
        T10[("tran_category")]
        T11[("daily_transaction")]
    end

    D1 -->|"Spring Data JPA"| T1
    D2 -->|"Spring Data JPA"| T2
    D3 -->|"Spring Data JPA"| T3
    D4 -->|"Spring Data JPA"| T4
    D5 -->|"Spring Data JPA"| T5
    D6 -->|"Spring Data JPA"| T6
    D7 -->|"Spring Data JPA"| T7
    D8 -->|"Spring Data JPA"| T8
    D9 -->|"Spring Data JPA"| T9
    D10 -->|"Spring Data JPA"| T10
    D11 -->|"Spring Data JPA"| T11

    F1 -->|"CREATE TABLE"| T1
    F2 -->|"CREATE INDEX"| T4
    F3 -->|"INSERT seed rows"| T5

    classDef before fill:#fbe4e4,stroke:#b23b3b,color:#3a2020
    classDef store fill:#e7dcf7,stroke:#6a3fb0,color:#2a1a4d
    classDef mig fill:#e6f4da,stroke:#5a9e2f,color:#27401a
    class D1,D2,D3,D4,D5,D6,D7,D8,D9,D10,D11 before
    class T1,T2,T3,T4,T5,T6,T7,T8,T9,T10,T11 store
    class F1,F2,F3,ASCII mig
```

### Legend — Diagram 4

- **Left subgraph `Before (z/OS)`** (red) — the 11 VSAM datasets; each label carries the dataset
  name and its defining copybook (and the alternate index where one exists: `CXACAIX` for the card
  cross-reference, plus the `TRANSACT` AIX).
- **Right subgraph `After (Java/AWS)`** (purple cylinders) — the 11 PostgreSQL tables; names match the
  JPA `@Table` mappings: `account`, `card`, `customer`, `card_xref`, `transaction`, `user_security`,
  `tran_cat_balance`, `disclosure_group`, `transaction_type`, `tran_category`, `daily_transaction`.
- **Center subgraph `Flyway Migrations`** (green) — `V1` schema, `V2` indexes, `V3` seed; the
  `ASCII fixtures` node lists all **9** files loaded by `V3`.
- **Solid arrow `-->` `Spring Data JPA`** — the 1:1 dataset → table re-platforming, accessed at
  runtime through Spring Data JPA repositories.
- **Solid arrow `-->` from Flyway** — `CREATE TABLE` (`V1`), `CREATE INDEX` (`V2`, shown to
  `card_xref` as the representative AIX target), and `INSERT seed rows` (`V3`, shown to `transaction`
  as a representative target); these run on application startup before any online or batch flow.

---

## Diagram 5 — Component Interaction: Controller → Service → Repository → Database (Before/After)

*Diagram 5 — Component Interaction: Controller → Service → Repository → Database (Before/After)* shows
the request lifecycle for the account feature. **Before**, a single CICS program (`COACTVWC` for view,
`COACTUPC` for update) performs presentation **and** VSAM I/O inline, with no separation of concerns.
**After**, the request flows through strict layering — `AccountController` → `AccountViewService` /
`AccountUpdateService` → `AccountRepository` / `CustomerRepository` / `CardCrossReferenceRepository` →
**PostgreSQL** — with `@Transactional` (and `@Version` optimistic locking) applied at the service
layer, exactly as the sole `SYNCPOINT ROLLBACK` in `COACTUPC` dictated.

**Before — COBOL / CICS inline (no layering):**

```mermaid
sequenceDiagram
    autonumber
    actor Term as 3270 Terminal
    participant CICS as CICS Program (COACTVWC / COACTUPC)
    participant VSAM as VSAM (ACCTDAT / CUSTDAT / CARDXREF)

    Term->>CICS: AID key + COMMAREA (account id)
    CICS->>VSAM: EXEC CICS READ (keyed, ACCTDAT)
    VSAM-->>CICS: account record
    CICS->>VSAM: EXEC CICS READ (CUSTDAT, CARDXREF)
    VSAM-->>CICS: customer + xref records
    CICS->>VSAM: EXEC CICS REWRITE (update path, ACCTDAT + CUSTDAT)
    VSAM-->>CICS: file status 00
    CICS-->>Term: SEND MAP (formatted screen)
    Note over Term,VSAM: One program mixes presentation and I/O — SYNCPOINT guards the dual update
```

**After — layered Spring (Controller → Service → Repository → Database):**

```mermaid
sequenceDiagram
    autonumber
    actor Client as REST Client
    participant JWT as Spring Security (JWT filter)
    participant Ctrl as AccountController
    participant SvcV as AccountViewService
    participant SvcU as AccountUpdateService
    participant RAcc as AccountRepository
    participant RCust as CustomerRepository
    participant RXref as CardCrossReferenceRepository
    participant DB as PostgreSQL 16

    Client->>JWT: GET /api/accounts/{id} (Bearer JWT)
    JWT->>JWT: validate token, extract userId / userType
    JWT->>Ctrl: forward authenticated request
    Ctrl->>SvcV: viewAccount(accountId)
    SvcV->>RXref: findByXrefAcctId(accountId)
    RXref->>DB: SELECT card_xref
    DB-->>RXref: xref row
    SvcV->>RAcc: findById(accountId)
    RAcc->>DB: SELECT account
    DB-->>RAcc: account row
    SvcV->>RCust: findById(customerId)
    RCust->>DB: SELECT customer
    DB-->>RCust: customer row
    SvcV-->>Ctrl: AccountViewResponse
    Ctrl-->>Client: 200 OK (JSON)

    Note over Client,DB: Update path reuses the same layering with @Transactional + @Version
    Client->>JWT: PUT /api/accounts/{id} (Bearer JWT)
    JWT->>Ctrl: forward authenticated request
    Ctrl->>SvcU: updateAccount(AccountUpdateRequest)
    SvcU->>RAcc: findById + @Version optimistic check
    SvcU->>DB: @Transactional UPDATE account + customer
    DB-->>SvcU: commit (rollback on exception)
    SvcU-->>Ctrl: AccountUpdateResponse
    Ctrl-->>Client: 200 OK (JSON)
```

### Legend — Diagram 5

- **`actor`** — the human/automated initiator (3270 terminal *before*, REST client *after*).
- **`participant`** — a component in the request path; *after* participants are named exactly as the
  implemented classes (`AccountController`, `AccountViewService`, `AccountUpdateService`,
  `AccountRepository`, `CustomerRepository`, `CardCrossReferenceRepository`).
- **Solid arrow `->>`** — a synchronous call/request; **dashed arrow `-->>`** — its return/response.
- **`Note over`** — highlights the key behavioral invariant being preserved (inline I/O + SYNCPOINT
  *before*; layered access with `@Transactional` + `@Version` *after*).
- **Before → After correspondence:** the single CICS program splits into Controller (presentation),
  Service (business logic + transactions), and Repository (data access); `EXEC CICS READ/REWRITE`
  becomes `findById` / JPA persistence; the VSAM datasets become PostgreSQL tables; and the CICS
  `SYNCPOINT` becomes Spring `@Transactional` with rollback-on-exception.

---

## Diagram 6 — Authentication Flow (Before/After)

*Diagram 6 — Authentication Flow (Before/After)* contrasts sign-on in the two systems. **Before**,
`COSGN00C` receives the BMS map, reads the `USRSEC` KSDS by user id, and compares the entered password
against the **plaintext** `SEC-USR-PWD`, then stamps the user type (`A`/`U`) into the COMMAREA and
transfers to the menu. **After**, `POST /api/auth/signin` invokes `AuthenticationService.authenticate()`,
which looks up the user via `UserSecurityRepository.findBySecUsrId`, verifies the password with
**BCrypt**, and issues a **JWT** (claims: `userId`, `userType`); subsequent calls are validated
statelessly by the OAuth2 resource server. The credential-hardening upgrade (plaintext → BCrypt) is the
only intentional behavioral change, and it preserves the login flow.

**Before — COBOL sign-on (`COSGN00C`, plaintext compare):**

```mermaid
sequenceDiagram
    autonumber
    actor User as Terminal User
    participant BMS as BMS 3270 (COSGN00 map)
    participant SGN as COSGN00C (CICS)
    participant USRSEC as USRSEC VSAM KSDS
    participant COMM as CARDDEMO-COMMAREA

    User->>BMS: Enter User ID + Password
    BMS->>SGN: EXEC CICS RECEIVE MAP
    SGN->>USRSEC: READ USRSEC (key = user id)
    USRSEC-->>SGN: SEC-USR record (plaintext SEC-USR-PWD)
    SGN->>SGN: compare entered pwd to SEC-USR-PWD
    alt credentials valid
        SGN->>COMM: set CDEMO-USER-ID, CDEMO-USER-TYPE (A / U)
        SGN->>BMS: XCTL to menu (COMEN01C / COADM01C)
        BMS-->>User: Menu screen
    else invalid
        SGN->>BMS: SEND MAP with error message
        BMS-->>User: Wrong Password / User not found
    end
```

**After — Spring Security JWT (`AuthenticationService`, BCrypt verify):**

```mermaid
sequenceDiagram
    autonumber
    actor Client as REST Client
    participant AC as AuthController
    participant AS as AuthenticationService
    participant UR as UserSecurityRepository
    participant DB as PostgreSQL (user_security)
    participant RS as OAuth2 Resource Server

    Client->>AC: POST /api/auth/signin (userId, password)
    AC->>AS: authenticate(SignOnRequest)
    AS->>UR: findBySecUsrId(userId)
    UR->>DB: SELECT user_security
    DB-->>UR: UserSecurity (BCrypt hash)
    AS->>AS: BCrypt matches(password, hash)
    alt credentials valid
        AS->>AS: issue JWT (claims userId, userType)
        AS-->>AC: token + userType
        AC-->>Client: 200 OK (accessToken)
        Note over Client,RS: subsequent calls carry the Bearer JWT
        Client->>RS: GET /api/... (Authorization Bearer JWT)
        RS->>RS: validate signature + claims (stateless)
        RS-->>Client: authorized response
    else invalid
        AS-->>AC: AuthenticationException
        AC-->>Client: 401 Unauthorized
    end
```

### Legend — Diagram 6

- **`actor`** — the sign-on initiator (terminal user *before*, REST client *after*).
- **`participant`** — sign-on components; *after* names match the implementation exactly
  (`AuthController`, `AuthenticationService`, `UserSecurityRepository`, OAuth2 resource server).
- **Solid arrow `->>`** — a synchronous call; **dashed arrow `-->>`** — its return/response.
- **`alt` / `else`** — the valid vs. invalid credential branches (identical outcomes either side).
- **Before → After correspondence:** `EXEC CICS RECEIVE MAP` → `POST /api/auth/signin`;
  `READ USRSEC` → `UserSecurityRepository.findBySecUsrId`; **plaintext compare** → **BCrypt verify**;
  `CDEMO-USER-TYPE` in the COMMAREA → a `userType` **JWT claim**; and the pseudo-conversational
  `XCTL`/COMMAREA continuation → stateless JWT validation on every subsequent request.

---

## Table — Authoritative Before → After Mapping

The diagrams above collectively encode the canonical paradigm mapping. This table consolidates it for
quick reference; each row is realized in one or more of *Diagram 1* through *Diagram 6*.

| Before (z/OS) | After (Java / AWS) | Shown in |
| :------------ | :----------------- | :------- |
| CICS pseudo-conversational programs + BMS 3270 maps | Spring MVC REST controllers + JSON DTOs | Diagrams 1, 2, 5 |
| `CARDDEMO-COMMAREA` conversational state | Stateless HTTP + JWT claims | Diagrams 1, 2, 6 |
| VSAM KSDS / AIX / PATH (11 datasets) | PostgreSQL 16 tables + indexes via Spring Data JPA | Diagrams 1, 2, 4 |
| JCL job streams (`EXEC PGM`, `DD`, `COND`) | Spring Batch jobs / steps + `JobExecutionDecider` + profiles | Diagrams 1, 2, 3 |
| CICS TDQ (`WRITEQ TD`, `CORPT00C`) | AWS **SQS FIFO** queue (`carddemo-report-jobs.fifo`) | Diagrams 1, 2 |
| GDG generation datasets (`DEFGDGB`) | AWS **S3** versioned objects (`carddemo-batch-input` / `carddemo-batch-output` / `carddemo-statements`) | Diagrams 1, 2 |
| Language Environment date services (`CEEDAYS`, `CSUTLDTC`) | `java.time.LocalDate` `DateValidationService` | Diagrams 1, 2 |
| RACF / plaintext `USRSEC` security | Spring Security + **BCrypt** + JWT | Diagrams 1, 2, 6 |
| DFSORT `SORT FIELDS=(TRAN-ID,A)` + IDCAMS `REPRO` | `Comparator` (ascending `tranId`) + bulk JPA insert | Diagram 3 |
| `SYNCPOINT ROLLBACK` (dual ACCTDAT + CUSTDAT update) | `@Transactional` + `@Version` optimistic locking | Diagram 5 |

> **Traceability note.** Every before element above originates from the COBOL/JCL/copybook sources at
> commit SHA **`27d6c6f`**; the COBOL is **not** copied into this repository. The paragraph-level
> mapping is in [`TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md), the rationale for each
> non-trivial choice is in [`DECISION_LOG.md`](../DECISION_LOG.md), and the runtime contracts are in
> [`docs/api-contracts.md`](api-contracts.md).

## How to view these diagrams

- **GitHub** renders Mermaid in Markdown automatically — open this file in the repository web UI.
- **VS Code / IntelliJ IDEA** render Mermaid with the *Markdown Preview Mermaid* (or built-in)
  plugins.
- **Mermaid Live Editor** (`https://mermaid.live`) — paste any single ```` ```mermaid ```` block to
  edit or export. The blocks here target **Mermaid 11.x**, matching the version pinned in
  `docs/executive-presentation.html`.
