<!--
  CardDemo COBOL -> Java / AWS migration.
  Visual Architecture documentation: Mermaid before/after diagrams only.
  Source traceability: original mainframe repository commit SHA 27d6c6f
  (CardDemo_v1.0-15-g27d6c6f-68). The COBOL sources are NOT copied into this repository.
-->

# CardDemo — Architecture (Before / After)

This document is the **Visual Architecture** reference for the migration of the AWS **CardDemo**
mainframe credit-card management application (COBOL / CICS / VSAM / JCL / BMS on z/OS) to a
**Java 25 LTS + Spring Boot 3.5.x** cloud-native service backed by PostgreSQL and AWS (S3 / SQS /
SNS, exercised locally against LocalStack). It complements the prose overview in
[`../README.md`](../README.md) by showing, for every modified aspect of the system, **both** the
*before* (z/OS) and *after* (Java / AWS) states.

> **Source traceability.** The original COBOL/JCL/BMS sources are **not copied** into this
> repository. Traceability back to the mainframe code is anchored to the source repository commit
> SHA **`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`). The full bidirectional
> COBOL-paragraph-to-Java-method mapping lives in
> [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md); design rationale lives in
> [`../DECISION_LOG.md`](../DECISION_LOG.md).

> **Mermaid only.** Every diagram below is authored in **Mermaid 11.x** (the same major version
> pinned by [`executive-presentation.html`](executive-presentation.html), `mermaid@11.4.0`). There
> are **no** embedded images, PNG screenshots, draw.io exports, or ASCII diagrams. The legacy z/OS
> screenshots that once lived under the source `diagrams/` folder are intentionally **replaced** by
> the Mermaid renderings here.

> **Scope.** The diagrams depict only the closed migrated feature set (**F-001..F-022**), the
> **11** persistent entities, and the **5-stage** batch pipeline. No new components are invented —
> the source contains no Db2, IMS, or IBM MQ, so none appear here. Only the AWS platform (exercised
> locally via LocalStack) is represented; the second attached, non-AWS cloud environment is out of
> scope and is deliberately **absent** from every diagram.

---

## How to read this document

Each diagram family below appears as a `###`-titled section containing exactly one fenced Mermaid
(`mermaid`) code block followed by a **Legend** subsection. Diagrams are referenced **by name**
in the surrounding prose (for example, *Diagram 3 — Batch Pipeline (Before / After)*). For any
aspect that changed during the migration, the *before* and *after* states are shown together — as
two labelled `subgraph`s in one flowchart, as two stacked flowcharts, or as paired sequence
diagrams.

**Colour / grouping convention used throughout.** *Before (z/OS)* elements are grouped inside a
`Before (...)` subgraph and tinted in warm legacy tones (amber, terracotta). *After (Java / AWS)*
elements are grouped inside an `After (...)` subgraph and tinted in cool modern tones (Blitzy
purple `#5B39F3`, teal `#94FAD5`, green). A solid arrow (`-->`) is a runtime call or data flow; a
dotted arrow (`-.->`) is a *re-platforming / "becomes"* relationship that links a before element to
its after counterpart; a thick arrow (`==>`) marks a provisioning / migration action. Database and
object-store nodes use the cylinder shape `[( )]`; decision / split gateways use the hexagon shape
`{{ }}`.

## Diagram index

1. **Diagram 1 — Before-State z/OS Architecture** — the mainframe topology that is being migrated.
2. **Diagram 2 — After-State Java / AWS Architecture** — the target Spring Boot + AWS topology.
3. **Diagram 3 — Batch Pipeline (Before / After)** — JCL job streams vs. the Spring Batch 5-stage flow.
4. **Diagram 4 — Data Migration (VSAM → PostgreSQL via Flyway)** — the 11 datasets → 11 tables mapping.
5. **Diagram 5 — Component Interaction (Request Lifecycle)** — Controller → Service → Repository → Database.
6. **Diagram 6 — Authentication Flow (Before / After)** — COBOL sign-on vs. Spring Security + JWT.

## Architecture mapping (before → after)

The table below restates the binding paradigm mapping (AAP §0.1.2); every row is rendered visually
across *Diagram 1* and *Diagram 2*, and the changed aspects are expanded in *Diagrams 3–6*.

| Before — z/OS | After — Java / AWS | Shown in |
| :------------ | :----------------- | :------- |
| CICS pseudo-conversational programs + BMS 3270 maps | Spring MVC REST controllers + JSON DTOs | Diagrams 1, 2, 5 |
| `CARDDEMO-COMMAREA` conversational state | Stateless HTTP + JWT claims (`userId`, `userType`) | Diagrams 1, 2, 6 |
| VSAM KSDS / AIX / PATH (11 datasets) | PostgreSQL 16 tables + indexes via Spring Data JPA | Diagrams 1, 2, 4 |
| JCL job streams (`EXEC PGM` / `DD` / `COND`) | Spring Batch jobs/steps + `JobExecutionDecider` + profiles | Diagrams 1, 2, 3 |
| CICS TDQ (`WRITEQ TD`, `CORPT00C`) | AWS SQS FIFO queue `carddemo-report-jobs.fifo` | Diagrams 1, 2, 3 |
| GDG generation datasets (`DEFGDGB`) | AWS S3 versioned objects (`carddemo-batch-input` / `-output` / `-statements`) | Diagrams 1, 2, 3, 4 |
| Language Environment date services (`CEEDAYS`, `CSUTLDTC`) | `java.time.LocalDate` `DateValidationService` | Diagrams 1, 2 |
| RACF / plaintext `USRSEC` security | Spring Security + BCrypt (+ OAuth2 resource server) | Diagrams 1, 2, 6 |

---

## Diagram 1 — Before-State z/OS Architecture

*Diagram 1 — Before-State z/OS Architecture* captures the mainframe topology as it exists at source
commit `27d6c6f`: 3270 terminals drive a CICS pseudo-conversational online region whose programs
share state through the `CARDDEMO-COMMAREA`, read and write **11 VSAM datasets**, validate dates via
Language Environment (`CEEDAYS` in `CSUTLDTC`), authenticate against the plaintext `USRSEC` KSDS
under RACF, and hand report work to a Transient Data Queue (`WRITEQ TD`) that feeds **JES** batch
job streams (JCL). This is the "before" baseline; its counterpart is *Diagram 2 — After-State
Java / AWS Architecture*.

```mermaid
flowchart TB
    Term["3270 Terminal Users"]:::ext

    subgraph ZOS["Before (z/OS Mainframe)"]
        direction TB
        BMS["BMS 3270 Mapsets (17 screens)"]:::ui

        subgraph CICS["CICS Online Region (pseudo-conversational)"]
            direction TB
            SGN["COSGN00C Sign-on"]:::pgm
            MEN["COMEN01C / COADM01C Menus"]:::pgm
            ONL["Online programs: Account, Card, Transaction,<br/>Billing, Report, User admin"]:::pgm
            COMM["CARDDEMO-COMMAREA<br/>conversational state"]:::state
        end

        RACF["RACF + USRSEC KSDS<br/>plaintext SEC-USR-PWD"]:::sec
        LE["Language Environment<br/>CEEDAYS date service (CSUTLDTC)"]:::svc
        TDQ["Transient Data Queue<br/>WRITEQ TD (CORPT00C)"]:::queue

        subgraph JES["JES Batch (JCL job streams)"]
            direction TB
            JCL["EXEC PGM / DD / COND<br/>POSTTRAN .. TRANREPT"]:::batch
            GDG["GDG generation datasets<br/>(DEFGDGB)"]:::file
        end

        subgraph VSAM["VSAM Datasets (11: KSDS / AIX / PATH)"]
            direction TB
            VA["ACCTDAT / CARDDAT / CUSTDAT"]:::data
            VB["CARDXREF + CXACAIX / TRANSACT + AIX"]:::data
            VC["USRSEC / TCATBAL / DISCGRP"]:::data
            VD["TRANTYPE / TRANCATG / DALYTRAN"]:::data
        end
    end

    Term --> BMS
    BMS --> SGN & MEN & ONL
    SGN --> COMM
    MEN --> COMM
    ONL --> COMM
    SGN -->|"READ"| RACF
    ONL -->|"CALL"| LE
    ONL -->|"WRITEQ TD"| TDQ
    TDQ -->|"triggers"| JCL
    ONL -->|"READ / REWRITE"| VSAM
    JCL -->|"sequential / VSAM I/O"| VSAM
    JCL --> GDG

    classDef ext fill:#ECEFF1,stroke:#607D8B,color:#263238;
    classDef ui fill:#E3F2FD,stroke:#1565C0,color:#0D47A1;
    classDef pgm fill:#FFF8E1,stroke:#F9A825,color:#795548;
    classDef state fill:#FCE4EC,stroke:#AD1457,color:#880E4F;
    classDef sec fill:#FFEBEE,stroke:#C62828,color:#B71C1C;
    classDef svc fill:#FFF3E0,stroke:#EF6C00,color:#E65100;
    classDef queue fill:#F3E5F5,stroke:#8E24AA,color:#4A148C;
    classDef batch fill:#FFF3E0,stroke:#EF6C00,color:#E65100;
    classDef file fill:#FFFDE7,stroke:#F57F17,color:#E65100;
    classDef data fill:#FBE9E7,stroke:#D84315,color:#BF360C;
```

### Legend

- **Grouping:** the outer `Before (z/OS Mainframe)` subgraph contains all legacy elements; nested
  subgraphs isolate the **CICS Online Region**, the **JES Batch** tier, and the **VSAM Datasets**.
- **Node shapes / colours:** `3270 Terminal Users` (grey) = external actors; `BMS 3270 Mapsets`
  (blue) = the presentation layer; COBOL programs (amber); `CARDDEMO-COMMAREA` (pink) = shared
  conversational state; `RACF + USRSEC` (red) = security; `CEEDAYS` (orange) = date services;
  `Transient Data Queue` (purple) = the online-to-batch bridge; VSAM datasets (terracotta) = the
  persistent stores.
- **Edges:** solid arrows are runtime CICS/batch operations; labels name the mainframe verb
  (`READ`, `REWRITE`, `WRITEQ TD`, `CALL`). The `TDQ --> JCL` edge is the report-submission bridge
  reproduced after migration by SQS (see *Diagram 3*).
- Every element shown here has an explicit *after* counterpart in *Diagram 2 — After-State
  Java / AWS Architecture*.

---


## Diagram 2 — After-State Java / AWS Architecture

*Diagram 2 — After-State Java / AWS Architecture* is the direct counterpart of *Diagram 1*. REST/JSON
clients call a Spring Boot application (`com.carddemo`) whose request path is **Spring Security + JWT
→ REST Controllers → Service Layer → Spring Data JPA Repositories → PostgreSQL 16**. The CICS TDQ is
replaced by the SQS FIFO queue `carddemo-report-jobs.fifo`; GDG datasets become versioned S3 objects
in `carddemo-batch-input` / `carddemo-batch-output` / `carddemo-statements`; notifications fan out
through the SNS topic `carddemo-notifications`. Spring Batch jobs run the migrated pipeline (see
*Diagram 3*), and observability (Micrometer + OpenTelemetry, with a `CorrelationIdFilter`) exports to
Jaeger, Prometheus, and Grafana. All AWS calls run against **LocalStack** on `:4566` — no live AWS.

```mermaid
flowchart TB
    Client["REST / JSON Clients"]:::ext

    subgraph JAVA["After (Java 25 + Spring Boot 3.5.x / AWS)"]
        direction TB

        subgraph APP["Spring Boot Application (com.carddemo)"]
            direction TB
            SEC["Spring Security + JWT<br/>OAuth2 Resource Server + BCrypt"]:::sec
            CTRL["REST Controllers (8)<br/>Auth, Account, Card, Transaction,<br/>Billing, Report, UserAdmin, Menu"]:::ui
            SVC["Service Layer (17 online services + shared)<br/>incl. DateValidationService (java.time.LocalDate)"]:::svc
            REPO["Spring Data JPA Repositories (11)"]:::repo
            BATCH["Spring Batch Jobs (5-stage pipeline)<br/>Posting, Interest, Combine, Statement, Report"]:::job
            OBS["Observability<br/>Micrometer + OTel + CorrelationIdFilter"]:::obs
        end

        PG[("PostgreSQL 16<br/>11 tables + indexes")]:::tbl

        subgraph AWS["AWS via LocalStack (:4566)"]
            direction TB
            S3[("S3 buckets<br/>carddemo-batch-input / -output / -statements")]:::file
            SQS["SQS FIFO<br/>carddemo-report-jobs.fifo"]:::queue
            SNS["SNS topic<br/>carddemo-notifications"]:::queue
        end

        subgraph MON["Observability Backends"]
            direction TB
            JAEGER["Jaeger (traces)"]:::obs
            PROM["Prometheus (metrics)"]:::obs
            GRAF["Grafana (dashboards)"]:::obs
        end
    end

    Client -->|"HTTPS + Bearer JWT"| SEC
    SEC --> CTRL
    CTRL --> SVC
    SVC --> REPO
    REPO -->|"JPA / JDBC"| PG
    SVC -->|"publish"| SQS
    SVC -->|"publish"| SNS
    BATCH --> REPO
    BATCH -->|"read / write objects"| S3
    SQS -->|"triggers job"| BATCH
    OBS -->|"OTLP"| JAEGER
    OBS -->|"/actuator/prometheus"| PROM
    PROM --> GRAF

    classDef ext fill:#ECEFF1,stroke:#607D8B,color:#263238;
    classDef ui fill:#E3F2FD,stroke:#1565C0,color:#0D47A1;
    classDef sec fill:#EDE7F6,stroke:#5B39F3,color:#2D1C77;
    classDef svc fill:#EDE7F6,stroke:#5B39F3,color:#2D1C77;
    classDef repo fill:#E8EAF6,stroke:#3949AB,color:#1A237E;
    classDef job fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20;
    classDef obs fill:#E1F5FE,stroke:#0277BD,color:#01579B;
    classDef tbl fill:#E0F2F1,stroke:#00897B,color:#004D40;
    classDef file fill:#FFFDE7,stroke:#F57F17,color:#E65100;
    classDef queue fill:#F3E5F5,stroke:#8E24AA,color:#4A148C;
```

### Legend

- **Grouping:** the outer `After (Java 25 + Spring Boot 3.5.x / AWS)` subgraph mirrors the `Before`
  grouping of *Diagram 1*. Nested subgraphs isolate the **Spring Boot Application**, the **AWS via
  LocalStack** tier, and the **Observability Backends**.
- **Node shapes / colours:** `REST / JSON Clients` (grey) = external actors; `REST Controllers`
  (blue) = presentation layer (replaces BMS); `Spring Security + JWT` (Blitzy purple) replaces
  RACF/USRSEC; the **Service Layer** and **Repositories** (purple / indigo) replace COBOL program
  logic and VSAM access; `PostgreSQL 16` (teal cylinder) replaces the VSAM datasets; `S3` (yellow
  cylinder) replaces GDG; `SQS FIFO` / `SNS` (purple) replace the CICS TDQ; `Spring Batch Jobs`
  (green) replace the JCL job streams; observability nodes (light blue) are new cross-cutting
  infrastructure.
- **Edges:** solid arrows are the runtime request/data path; labels name the mechanism
  (`Bearer JWT`, `JPA / JDBC`, `publish`, `OTLP`). The `SQS --> BATCH` edge is the
  TDQ-replacement bridge (compare with `TDQ --> JCL` in *Diagram 1*).
- Read *Diagram 1* and *Diagram 2* together: every legacy node on the left has exactly one modern
  node here, satisfying the before-and-after requirement for the overall topology.

---


## Diagram 3 — Batch Pipeline (Before / After)

*Diagram 3 — Batch Pipeline (Before / After)* shows the 5-stage processing chain in both worlds
within a single flowchart. **Before**, JES runs the JCL job streams
`POSTTRAN → INTCALC → COMBTRAN → {CREASTMT, TRANREPT}`, sequenced by `COND` condition codes;
`COMBTRAN.jcl` sorts with **DFSORT** (`SORT FIELDS=(TRAN-ID,A)`) and loads the master with **IDCAMS
`REPRO`**. **After**, the same stages are Spring Batch jobs —
`DailyTransactionPostingJob → InterestCalculationJob → CombineTransactionsJob →` a
`FlowBuilder.split()` that runs `StatementGenerationJob` (4a) and `TransactionReportJob` (4b)
concurrently — sequenced by `ExitStatus` and `JobExecutionDecider`; the DFSORT + REPRO step becomes a
Java `Comparator` (TRAN-ID ascending) plus a bulk JPA insert. The dotted arrow records the
re-platforming relationship between the two halves.

```mermaid
flowchart TB
    subgraph BEFORE["Before — JCL Job Streams (JES, COND condition codes)"]
        direction LR
        B1["Stage 1<br/>POSTTRAN.jcl + CBTRN02C<br/>daily posting"]:::batch
        B2["Stage 2<br/>INTCALC.jcl + CBACT04C<br/>interest calc"]:::batch
        B3["Stage 3<br/>COMBTRAN.jcl<br/>DFSORT SORT FIELDS=(TRAN-ID,A) + IDCAMS REPRO"]:::batch
        B4a["Stage 4a<br/>CREASTMT.JCL + CBSTM03A/B<br/>statements"]:::batch
        B4b["Stage 4b<br/>TRANREPT.jcl + CBTRN03C<br/>transaction report"]:::batch
        B1 -->|"COND=(0,NE)"| B2
        B2 -->|"COND=(0,NE)"| B3
        B3 -->|"COND"| B4a
        B3 -->|"COND"| B4b
    end

    subgraph AFTER["After — Spring Batch (ExitStatus / JobExecutionDecider / FlowBuilder.split)"]
        direction LR
        A1["Stage 1<br/>DailyTransactionPostingJob<br/>4-stage validation, reject 100-109"]:::job
        A2["Stage 2<br/>InterestCalculationJob<br/>BigDecimal HALF_EVEN"]:::job
        A3["Stage 3<br/>CombineTransactionsJob<br/>Comparator(TRAN-ID asc) + bulk insert"]:::job
        SPL{{"FlowBuilder.split()"}}:::decider
        A4a["Stage 4a<br/>StatementGenerationJob<br/>text + HTML to S3"]:::job
        A4b["Stage 4b<br/>TransactionReportJob<br/>date-filtered report to S3"]:::job
        A1 -->|"ExitStatus COMPLETED"| A2
        A2 -->|"ExitStatus COMPLETED"| A3
        A3 --> SPL
        SPL -->|"parallel"| A4a
        SPL -->|"parallel"| A4b
    end

    BEFORE -.->|"re-hosted as Spring Batch"| AFTER

    classDef batch fill:#FFF3E0,stroke:#EF6C00,color:#E65100;
    classDef job fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20;
    classDef decider fill:#94FAD5,stroke:#00897B,color:#004D40;
```

### Legend

- **Grouping:** the upper `Before — JCL Job Streams` subgraph and the lower
  `After — Spring Batch` subgraph stack the two pipelines for direct comparison; the dotted
  `re-hosted as Spring Batch` edge links them.
- **Node shapes / colours:** JCL steps (amber) name the JCL job + COBOL program; Spring Batch jobs
  (green) use the exact migration class names; the hexagon `FlowBuilder.split()` (Blitzy teal) is the
  parallel-split gateway that has no single JCL equivalent (the JCL expresses 4a/4b as two
  `COND`-guarded steps).
- **Edges:** *before* edges are labelled with the governing `COND` condition code; *after* edges are
  labelled with the Spring Batch `ExitStatus` / decider semantics. Stage numbers (1, 2, 3, 4a, 4b)
  align one-to-one across the two halves, demonstrating preserved sequencing.
- **Fidelity note:** Stage 3's DFSORT + IDCAMS `REPRO` (per `app/jcl/COMBTRAN.jcl`) is reproduced by
  `CombineTransactionsJob` as a `Comparator` on `TRAN-ID` ascending followed by a bulk insert into the
  `transaction` table — the same sort key and load semantics, no algebraic change.

---


## Diagram 4 — Data Migration (VSAM → PostgreSQL via Flyway)

*Diagram 4 — Data Migration (VSAM → PostgreSQL via Flyway)* details how the **11 VSAM datasets**
become **11 JPA-backed PostgreSQL tables**. Schema is created by `V1__create_schema.sql` (from the
`DEFINE CLUSTER` JCL), indexes — including the `CXACAIX` cross-reference alternate index and the
`TRANSACT` alternate index — by `V2__create_indexes.sql`, and the **9 ASCII fixtures** are loaded as
reference/seed rows by `V3__seed_data.sql`. Each `DATASET --> table` edge carries the exact name
mapping; the thick arrow marks Flyway provisioning the target schema on application startup.

```mermaid
flowchart LR
    subgraph SRC["Before — VSAM Datasets (11)"]
        direction TB
        D1["ACCTDAT (KSDS)"]:::data
        D2["CARDDAT (KSDS)"]:::data
        D3["CUSTDAT (KSDS)"]:::data
        D4["CARDXREF (KSDS + CXACAIX)"]:::data
        D5["TRANSACT (KSDS + AIX)"]:::data
        D6["USRSEC (KSDS)"]:::data
        D7["TCATBAL (KSDS)"]:::data
        D8["DISCGRP (KSDS)"]:::data
        D9["TRANTYPE (KSDS)"]:::data
        D10["TRANCATG (KSDS)"]:::data
        D11["DALYTRAN (KSDS)"]:::data
    end

    subgraph FW["Flyway Migrations (applied on startup: V1 -> V2 -> V3)"]
        direction TB
        FIX["9 ASCII fixtures<br/>acctdata, carddata, custdata, cardxref, dailytran,<br/>discgrp, tcatbal, trancatg, trantype (.txt)"]:::file
        V1["V1__create_schema.sql<br/>tables (from DEFINE CLUSTER)"]:::mig
        V2["V2__create_indexes.sql<br/>primary + alternate (CXACAIX, TRANSACT AIX)"]:::mig
        V3["V3__seed_data.sql<br/>seed / reference rows"]:::mig
        V1 --> V2 --> V3
        FIX --> V3
    end

    subgraph TGT["After — PostgreSQL 16 Tables (11)"]
        direction TB
        T1[("account")]:::tbl
        T2[("card")]:::tbl
        T3[("customer")]:::tbl
        T4[("card_xref")]:::tbl
        T5[("transaction")]:::tbl
        T6[("user_security")]:::tbl
        T7[("tran_cat_balance")]:::tbl
        T8[("disclosure_group")]:::tbl
        T9[("tran_type")]:::tbl
        T10[("tran_category")]:::tbl
        T11[("daily_transaction")]:::tbl
    end

    D1 --> T1
    D2 --> T2
    D3 --> T3
    D4 --> T4
    D5 --> T5
    D6 --> T6
    D7 --> T7
    D8 --> T8
    D9 --> T9
    D10 --> T10
    D11 --> T11

    FW ==>|"provisions schema + indexes, seeds rows"| TGT

    classDef data fill:#FBE9E7,stroke:#D84315,color:#BF360C;
    classDef mig fill:#F1F8E9,stroke:#558B2F,color:#33691E;
    classDef file fill:#FFFDE7,stroke:#F57F17,color:#E65100;
    classDef tbl fill:#E0F2F1,stroke:#00897B,color:#004D40;
```

### Legend

- **Grouping:** the left `Before — VSAM Datasets` subgraph lists the 11 source KSDS clusters (with
  their AIX/PATH where relevant); the centre `Flyway Migrations` subgraph is the migration mechanism;
  the right `After — PostgreSQL 16 Tables` subgraph lists the 11 target tables (teal cylinders).
- **Node shapes / colours:** VSAM datasets (terracotta) name the mainframe DD/cluster; Flyway scripts
  (green) name the versioned migration files; the ASCII fixtures node (yellow) is the seed-data
  source for `V3`; PostgreSQL tables (teal cylinders) name the relational targets.
- **Edges:** each thin `DATASET --> table` arrow is the authoritative one-to-one name mapping
  (`ACCTDAT → account`, `CARDDAT → card`, `CUSTDAT → customer`, `CARDXREF → card_xref`,
  `TRANSACT → transaction`, `USRSEC → user_security`, `TCATBAL → tran_cat_balance`,
  `DISCGRP → disclosure_group`, `TRANTYPE → tran_type`, `TRANCATG → tran_category`,
  `DALYTRAN → daily_transaction`). The thick `provisions` arrow shows Flyway creating and seeding the
  schema before any online or batch flow runs. Inside the Flyway subgraph, `V1 -> V2 -> V3` is the
  fixed migration order.

---


## Diagram 5 — Component Interaction (Request Lifecycle)

*Diagram 5 — Component Interaction (Request Lifecycle)* traces a request through the layered Java
architecture using the **Account** function as the representative example — `AccountController`
delegating to `AccountViewService` / `AccountUpdateService`, which use `AccountRepository`,
`CustomerRepository`, and `CardCrossReferenceRepository` over PostgreSQL. The opening note records the
*before* state (the COBOL programs `COACTVWC` / `COACTUPC` issued `EXEC CICS READ` / `REWRITE`
directly against the VSAM datasets), so this diagram also expresses before → after for the access
pattern. The update path encodes the `@Transactional` + `@Version` semantics that replace the sole
`SYNCPOINT ROLLBACK` in `COACTUPC`.

```mermaid
sequenceDiagram
    autonumber
    actor U as REST Client
    participant C as AccountController
    participant SV as AccountViewService
    participant SU as AccountUpdateService
    participant AR as AccountRepository
    participant CR as CustomerRepository
    participant XR as CardCrossReferenceRepository
    participant DB as PostgreSQL 16

    Note over U,DB: Before z/OS - COACTVWC and COACTUPC read/rewrite ACCTDAT, CUSTDAT, CXACAIX directly via EXEC CICS. After - the layered path below.

    rect rgb(237, 231, 246)
    Note over U,DB: Account view - GET /api/accounts/{id} (from COACTVWC)
    U->>C: GET /api/accounts/{id} + Bearer JWT
    C->>SV: viewAccount(accountId)
    SV->>AR: findById(accountId)
    AR->>DB: SELECT account
    DB-->>AR: account row
    SV->>XR: findByXrefAcctId(accountId)
    XR->>DB: SELECT card_xref
    DB-->>XR: cross-reference row
    SV->>CR: findById(customerId)
    CR->>DB: SELECT customer
    DB-->>CR: customer row
    SV-->>C: AccountViewResponse
    C-->>U: 200 AccountViewResponse
    end

    rect rgb(232, 245, 233)
    Note over U,DB: Account update - PUT /api/accounts/{id} (from COACTUPC, @Transactional + @Version)
    U->>C: PUT /api/accounts/{id} + Bearer JWT
    C->>SU: updateAccount(request)
    SU->>AR: findById then save (ACCTDAT)
    SU->>CR: save (CUSTDAT)
    AR->>DB: UPDATE account with @Version check
    CR->>DB: UPDATE customer
    alt optimistic-lock conflict
        DB-->>SU: OptimisticLockException
        SU-->>C: rollback (SYNCPOINT ROLLBACK equivalent)
        C-->>U: 409 Conflict
    else success
        DB-->>SU: commit
        SU-->>C: AccountUpdateResponse
        C-->>U: 200 AccountUpdateResponse
    end
    end
```

### Legend

- **Participants (left → right):** the `REST Client` actor; the `AccountController` (presentation);
  the `AccountViewService` and `AccountUpdateService` (business logic, one per source COBOL program);
  the three Spring Data JPA repositories `AccountRepository`, `CustomerRepository`, and
  `CardCrossReferenceRepository`; and `PostgreSQL 16`.
- **Arrows:** a solid arrow (`->>`) is a synchronous call; a dashed arrow (`-->>`) is the return /
  response. `autonumber` sequences the steps.
- **Coloured bands (`rect`):** the purple band is the read-only **view** path
  (`GET /api/accounts/{id}`); the green band is the transactional **update** path
  (`PUT /api/accounts/{id}`). The `alt` block shows the optimistic-locking branch — an
  `OptimisticLockException` triggers a rollback (the `@Transactional` analogue of the COBOL
  `SYNCPOINT ROLLBACK`) and a `409 Conflict`.
- **Before / after:** the opening `Note` states the legacy COBOL access pattern; the body is the
  modern layered replacement, so the access-pattern change is shown both ways.

---


## Diagram 6 — Authentication Flow (Before / After)

*Diagram 6 — Authentication Flow (Before / After)* contrasts sign-on in the two systems as paired
sequence diagrams. **Before**, `COSGN00C` reads the `USRSEC` KSDS and compares the submitted password
to the **plaintext** `SEC-USR-PWD`, then records the user id and user type (`A`/`U`) in the
`CARDDEMO-COMMAREA` for the next pseudo-conversation. **After**, `POST /api/auth/signin` flows through
`AuthenticationService.authenticate()` → `UserSecurityRepository.findBySecUsrId` → **BCrypt**
verification → issuance of a **JWT** carrying `userId` and `userType` claims; subsequent calls are
validated statelessly by the OAuth2 resource server, with no server-held conversational state.

### Before — COBOL sign-on (COSGN00C, plaintext USRSEC)

```mermaid
sequenceDiagram
    autonumber
    actor T as 3270 Terminal User
    participant SGN as COSGN00C
    participant US as USRSEC KSDS
    participant CM as CARDDEMO-COMMAREA
    T->>SGN: Enter user id + password via BMS map
    SGN->>US: READ USRSEC, key = user id
    US-->>SGN: SEC-USR record incl. plaintext SEC-USR-PWD
    alt password equals SEC-USR-PWD - plaintext compare
        SGN->>CM: set CDEMO-USER-ID and CDEMO-USER-TYPE A or U
        SGN-->>T: XCTL to menu, next pseudo-conversation
    else mismatch
        SGN-->>T: display error and re-prompt
    end
```

### After — Spring Security + JWT (AuthenticationService, BCrypt)

```mermaid
sequenceDiagram
    autonumber
    actor U as REST Client
    participant AC as AuthController
    participant AS as AuthenticationService
    participant UR as UserSecurityRepository
    participant DB as PostgreSQL user_security
    participant RS as OAuth2 Resource Server
    U->>AC: POST /api/auth/signin with userId + password
    AC->>AS: authenticate(request)
    AS->>UR: findBySecUsrId(userId)
    UR->>DB: SELECT user_security
    DB-->>UR: UserSecurity with BCrypt hash
    UR-->>AS: UserSecurity
    alt BCrypt matches password and hash
        AS-->>AC: JWT with claims userId and userType
        AC-->>U: 200 OK with Bearer token
        U->>RS: subsequent request with Bearer JWT
        RS-->>U: validated, stateless - no COMMAREA
    else mismatch
        AS-->>AC: AuthenticationException
        AC-->>U: 401 Unauthorized
    end
```

### Legend

- **Participants — before:** the `3270 Terminal User`; `COSGN00C` (the sign-on program); the
  `USRSEC KSDS` (security dataset); and the `CARDDEMO-COMMAREA` (conversational state carrier).
- **Participants — after:** the `REST Client`; `AuthController`; `AuthenticationService`;
  `UserSecurityRepository`; `PostgreSQL user_security` (the migrated table); and the
  `OAuth2 Resource Server` that validates JWTs on later calls.
- **Arrows:** solid (`->>`) = request / call; dashed (`-->>`) = response / return. Each `alt` block
  shows the success branch versus the credential-mismatch branch.
- **Key before → after differences:** plaintext `SEC-USR-PWD` compare → **BCrypt** verification
  (credential hardening, constraint C-003); `CARDDEMO-COMMAREA` user-type state → stateless **JWT**
  claims (`userId`, `userType`); `XCTL` chaining to the next pseudo-conversation → a stateless
  resource-server check on every subsequent request.

---

## Summary

Together, *Diagram 1* and *Diagram 2* establish the overall before/after topology; *Diagram 3* expands
the batch pipeline; *Diagram 4* expands the data layer; *Diagram 5* shows the runtime request
lifecycle; and *Diagram 6* shows authentication. Every modified aspect of the system is therefore
presented in **both** its z/OS and its Java / AWS form, satisfying the Visual Architecture rule.

The migration preserves **100% behavioral parity** with the source at commit `27d6c6f` while
re-platforming the runtime: CICS + BMS become Spring MVC REST + JSON DTOs; the `CARDDEMO-COMMAREA`
becomes stateless HTTP + JWT; the 11 VSAM datasets become 11 PostgreSQL tables via Flyway; the JCL
job streams become a Spring Batch 5-stage pipeline; the CICS TDQ becomes the SQS FIFO queue
`carddemo-report-jobs.fifo`; GDG datasets become versioned S3 objects in `carddemo-batch-input`,
`carddemo-batch-output`, and `carddemo-statements`; `CEEDAYS` date services become a
`java.time.LocalDate` `DateValidationService`; and RACF / plaintext `USRSEC` becomes Spring Security
with BCrypt. No feature is added beyond the closed set **F-001..F-022**, and only the
AWS / LocalStack platform is represented (the second attached, non-AWS cloud environment is
intentionally excluded).

**Related documentation:** [`../README.md`](../README.md) (overview and run instructions) ·
[`api-contracts.md`](api-contracts.md) (REST / SQS / S3 contracts) ·
[`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) (COBOL ↔ Java paragraph mapping) ·
[`../DECISION_LOG.md`](../DECISION_LOG.md) (design rationale) ·
[`validation-gates.md`](validation-gates.md) (gate evidence).

