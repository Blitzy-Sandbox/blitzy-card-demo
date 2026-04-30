# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Objective

Based on the provided requirements, the Blitzy platform understands that the objective is to produce a complete Business Rules Extraction (BRE) document for **all the COBOL Batch Jobs (excluding CICS online programs)** present in the AWS CardDemo repository. The deliverable consists of two artifacts that contain identical row data formatted differently:

- A **CSV file** with exactly 20 prescribed columns and a header row, one row per discrete decision point extracted from the batch source code
- A **Markdown (MD) file** that renders the same 20-column tabular content and additionally appends a "Modernization Mapping" section authored in Markdown

Each row in both deliverables represents one atomic business rule discovered in either a JCL step, a COBOL paragraph, an `IF` / `EVALUATE` branch, a DB2 SQL statement, a file I/O verb with status check, an arithmetic `COMPUTE` block, a date or cycle-type determination, an error-handling block, or a record transformation. The Blitzy platform further understands that two decisions in the same paragraph must NOT be merged into a single row, and error-handling paragraphs must NOT be skipped because they carry the highest modernization risk.

The 20 prescribed CSV columns, in order, are:

`Rule_Number, Job_Name, Rule_Execution, Program_Type, Module_Name, Sub_Module, Input_File, Input_File_Layout, Input_File_Length, Output_File, Output_File_Layout, Business_Rule_Category, Linkage_Columns, Detailed_Business_Rule, SQL_Decision_Control_Statements, SQL_Function, Code_Reference, Bounded_Context, DB2_Table_Name, Review_Comments`

Each rule must be assigned a sequential `Rule_Number` formatted as `BR-[JOBNAME]-001`, `BR-[JOBNAME]-002`, etc., zero-padded to three digits, with `Rule_Execution` numeric values gapless and in true execution order. The `Business_Rule_Category` field must use exactly one of twelve fixed enumerations: `Initialization`, `File-IO`, `Data-Validation`, `Date-Time`, `Cycle-Determination`, `Calculation`, `Sorting`, `Reporting`, `Error-Handling`, `FTP-Distribution`, `Finalization`, `Cleanup`. The `Program_Type` field must use exactly one of five fixed enumerations: `JCL`, `COBOL`, `DB2-SQL`, `SORT`, `FTP`.

The Modernization Mapping section appended to the MD file (and provided as plain text after the CSV content) must contain three numbered subsections: (1) AWS Glue mapping for each COBOL program/step, (2) Java ECS batch mapping using Spring Batch primitives, and (3) the top five modernization risks ordered by severity (HIGH/MEDIUM/LOW).

### 0.1.2 Implicit Requirements Surfaced

The Blitzy platform has detected the following implicit requirements that are not stated verbatim but are necessary for correct delivery:

- **Output location convention**: The CardDemo repository uses `docs/` as its single MkDocs documentation root (configured by `mkdocs.yml` with `techdocs-core` and `mermaid2` plugins). The two BRE deliverables must therefore be placed in a new `docs/bre/` subfolder so they are discoverable by the existing documentation toolchain without breaking the existing site build.
- **Field-list formatting consistency**: The user specifies pipe-delimited field lists for `Input_File_Layout` and `Output_File_Layout` columns in the form `FIELD-NAME(PIC X(n))`. This format must be applied uniformly across all rows where a file layout is present, derived strictly from the COBOL `FD`/`SD`/`01` definitions in the source `.cbl` files and copybook `.cpy` files.
- **Record length precision**: For `Input_File_Length`, the Blitzy platform must compute the physical record length in bytes from the PIC clauses of each FD-level entry, accounting for COMP/COMP-3 packed-decimal compression rules (a `S9(09)V99` defined as COMP-3 occupies 6 bytes; a `PIC 9(4) BINARY` occupies 2 bytes; pure alphanumeric `PIC X(n)` occupies n bytes; pure zoned-decimal `PIC 9(n)` without USAGE occupies n bytes).
- **CSV escaping discipline**: Because the `Detailed_Business_Rule` and `SQL_Decision_Control_Statements` columns may contain commas, newlines, double quotes, and the `\n` separator the user specified for multi-line statements, RFC 4180 quoting (every quote-doubled) must be applied uniformly so that the CSV opens cleanly in Excel, LibreOffice Calc, and `csv.reader`.
- **Bounded-context derivation**: The user instructed to derive `Bounded_Context` from program names, JCL step names, and variable names. The Blitzy platform interprets this as requiring distinct domain labels per job: "Daily Transaction Posting" (POSTTRAN/CBTRN02C), "Interest & Fee Calculation" (INTCALC/CBACT04C), "Transaction Consolidation" (COMBTRAN), "Transaction Reporting" (TRANREPT/CBTRN03C), "Statement Generation" (CREASTMT/CBSTM03A/CBSTM03B), "Category Balance Reporting" (PRTCATBL), "Account Master Inspection" (READACCT/CBACT01C), "Card Master Inspection" (READCARD/CBACT02C), "Customer Master Inspection" (READCUST/CBCUS01C), "Card Cross-Reference Inspection" (READXREF/CBACT03C), and "Daily Transaction Validation" (CBTRN01C).
- **Empty-cell convention**: The user specified `"N/A"` for fields that do not apply (input file, output file, layouts, lengths, linkage columns, SQL function, DB2 table). The Blitzy platform must use the literal string `N/A` (not blank, not `null`, not `none`) so column semantics are unambiguous.
- **No DB2 in this codebase**: After complete inspection of all batch programs, the Blitzy platform has confirmed that **no batch COBOL program in this repository contains EXEC SQL statements**. All persistence is via VSAM KSDS files and sequential PS files. The `DB2_Table_Name` column will therefore be `N/A` for every row, and the `SQL_Function` column will use COBOL intrinsic functions (e.g., `FUNCTION CURRENT-DATE`, `FUNCTION MOD`, `FUNCTION LENGTH`) where present, otherwise `N/A`. The `Program_Type` value `DB2-SQL` will not be used in this extraction.
- **JCL utility steps as discrete rules**: The user listed `IDCAMS DELETE`, `IDCAMS DEFINE`, `IDCAMS REPRO`, `DFSORT`, `IEFBR14`, and `IEBGENER` as their own rule rows. The Blitzy platform must therefore extract each `EXEC PGM=` step from each in-scope JCL job as its own rule, not merge them into the COBOL program rule.

### 0.1.3 Task Categorization

- **Primary task type**: Documentation — generation of new analytical artifacts (CSV + MD) from existing legacy source code. No COBOL, JCL, or copybook source is modified.
- **Secondary aspects**: Static code analysis (line-by-line traversal of all `PROCEDURE DIVISION` paragraphs and JCL steps); modernization-risk classification (mapping each rule to AWS Glue / Spring Batch equivalents); cross-file dependency tracing (linking `FD-XREF-CARD-NUM` keys to alternate-index DD names like `XREFFIL1`).
- **Scope classification**: Isolated additive change — two new files in a new `docs/bre/` subfolder. No existing file in the repository is modified, deleted, or refactored. The `mkdocs.yml` navigation may optionally be extended to list the new BRE artifacts, but this is not strictly required because MkDocs auto-discovers Markdown files under `docs/`.

### 0.1.4 Special Instructions and Constraints

The following user directives are captured verbatim and must be enforced:

- **User Example: "Rule_Number — Sequential ID: BR-[JOBNAME]-001, BR-[JOBNAME]-002, ..."** — Rule numbering uses the `BR-[JOBNAME]-NNN` template with three-digit zero-padded suffix.
- **User Example: "within 5 business days", "if quantity exceeds 999"** — Concrete thresholds and tolerances must be written as actual values in plain English in the `Detailed_Business_Rule` column, never as COBOL field names.
- **User Example: "Multi-line statements use \n separator"** — In `SQL_Decision_Control_Statements`, multi-line COBOL conditions are joined with the literal two-character sequence backslash-n.
- **User Example: "Code_Reference — Source location: line number range (e.g., 240-265) OR paragraph name if line numbers are unavailable"** — Use `LINE_RANGE` (e.g., `240-265`) when the analyzer can resolve to specific line numbers; otherwise use the COBOL paragraph name (e.g., `2800-UPDATE-ACCOUNT-REC`) or JCL step label (e.g., `STEP15`).
- **User Example: "DSN names, dates, thresholds"** — Hardcoded DSN names like `DSN1`, hardcoded date literals like `2022-01-01`, and hardcoded thresholds like `WS-PAGE-SIZE VALUE 20` must be flagged in `Review_Comments` as modernization risks.
- **User directive: "Do NOT merge two decisions into one row even if they are in the same paragraph"** — Each `IF`, `EVALUATE WHEN`, `COMPUTE`, `READ ... INVALID KEY` is its own row.
- **User directive: "Do NOT skip error-handling paragraphs"** — Paragraphs `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`, `Z-ABEND-PROGRAM`, and `Z-DISPLAY-IO-STATUS` are extracted as their own rule rows in each program where they appear.
- **User directive: "Replace COBOL field names with business terms where derivable from context"** — `WS-PPO-LEVEL-QTY → "PPO level quantity"` style translation is applied to the natural-language `Detailed_Business_Rule` column. Original COBOL identifiers remain only in the `SQL_Decision_Control_Statements` column.

### 0.1.5 Technical Interpretation

These requirements translate to the following technical implementation strategy:

To produce the BRE deliverables, the Blitzy platform will create one new folder `docs/bre/` and write two new files inside it: `CardDemo_Batch_BRE.csv` (the canonical 20-column CSV) and `CardDemo_Batch_BRE.md` (the Markdown rendering plus appended Modernization Mapping). To extract every business rule, the Blitzy platform will systematically traverse each in-scope JCL job step and each in-scope COBOL `PROCEDURE DIVISION` paragraph in execution order, creating one CSV row per discrete decision point. To derive file layouts, the Blitzy platform will copy each `FD`/`SD`/`01` record definition from the COBOL source and from the `.cpy` copybook library and convert it to the prescribed `FIELD-NAME(PIC X(n))` pipe-delimited form. To classify rule categories, the Blitzy platform will map each paragraph's predominant verb (`OPEN`, `READ`/`WRITE`, `IF` validation, date arithmetic, `COMPUTE`, `SORT`, write-to-report, `INVALID KEY` handling, `CLOSE`, `GOBACK`) to the closest of the twelve enumerated `Business_Rule_Category` values. To produce the Modernization Mapping, the Blitzy platform will map each COBOL program to one Spring Batch `Step` or `Tasklet` and to one AWS Glue construct (DynamicFrame for file I/O, JDBC connection for keyed reads, `DataFrame.orderBy()` for SORT steps, `boto3` for FTP), and rank the five highest-severity risks across all rows.

## 0.2 Repository Scope Discovery

### 0.2.1 Comprehensive File Analysis

The Blitzy platform performed an exhaustive traversal of the AWS CardDemo source tree to enumerate every COBOL Batch Job and its supporting artifacts. The repository contains a flat operational JCL folder, a primary COBOL source folder, a shared copybook folder, and a documentation folder. CICS-only programs (CO-prefix), BMS map sources (`app/bms`), generated symbolic-map copybooks (`app/cpy-bms`), CICS administration JCL (`CBADMCDJ.jcl`, `OPENFIL.jcl`, `CLOSEFIL.jcl`), and online transaction handlers are explicitly excluded per the user's directive "all the COBOL Batch Jobs not CICS".

#### 0.2.1.1 In-Scope Batch COBOL Programs (10 programs)

The following ten programs in `app/cbl/` are CB-prefix (Card Demo Batch) and constitute the complete batch program inventory:

| Program File | Function | LRECL Affinity |
|--------------|----------|----------------|
| `app/cbl/CBACT01C.cbl` | Read and print account master file (ACCTFILE / `ACCTDATA.VSAM.KSDS`) | 300 |
| `app/cbl/CBACT02C.cbl` | Read and print card master file (CARDFILE / `CARDDATA.VSAM.KSDS`) | 150 |
| `app/cbl/CBACT03C.cbl` | Read and print card cross-reference file (XREFFILE / `CARDXREF.VSAM.KSDS`) | 50 |
| `app/cbl/CBACT04C.cbl` | Interest calculator: traverse TCATBALF, compute monthly interest per category, post interest transactions, reset cycle credit/debit | 50 in / 350 out |
| `app/cbl/CBCUS01C.cbl` | Read and print customer master file (CUSTFILE / `CUSTDATA.VSAM.KSDS`) | 500 |
| `app/cbl/CBSTM03A.CBL` | Statement creator: emit text + HTML statements per account using callable subroutine CBSTM03B | 80 (STMT) / 100 (HTML) |
| `app/cbl/CBSTM03B.CBL` | File-service subroutine called by CBSTM03A; supports OPEN/READ/READ-K/CLOSE on TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE | varies |
| `app/cbl/CBTRN01C.cbl` | Daily transaction validation driver: looks up XREF and ACCOUNT, displays diagnostics (no posting writes) | 350 |
| `app/cbl/CBTRN02C.cbl` | Daily transaction posting engine: validates, rejects, updates TCATBAL, updates ACCOUNT cycle balances, writes to TRANSACT | 350 |
| `app/cbl/CBTRN03C.cbl` | Transaction detail report: filters by date window, paginated report with account/page/grand totals | 350 in / 133 out |

#### 0.2.1.2 In-Scope Batch JCL Jobs (10 jobs running CB-prefix programs or supporting batch flows)

| JCL File | Steps | Programs/Utilities Invoked |
|----------|-------|----------------------------|
| `app/jcl/POSTTRAN.jcl` | STEP15 | `EXEC PGM=CBTRN02C` with DD: TRANFILE, DALYTRAN, XREFFILE, DALYREJS(+1), ACCTFILE, TCATBALF |
| `app/jcl/INTCALC.jcl` | STEP15 | `EXEC PGM=CBACT04C,PARM='2022071800'` with DD: TCATBALF, XREFFILE, XREFFIL1 (AIX path), ACCTFILE, DISCGRP, TRANSACT(+1) |
| `app/jcl/COMBTRAN.jcl` | STEP05R, STEP10 | `EXEC PGM=SORT` (TRANSACT.BKUP(0)+SYSTRAN(0) → TRANSACT.COMBINED(+1) by tran-id), `EXEC PGM=IDCAMS` REPRO into TRANSACT.VSAM.KSDS |
| `app/jcl/TRANREPT.jcl` | STEP05R (REPROC), STEP05R (SORT), STEP10R | Unload TRANSACT to BKUP(+1); SORT by date window with `PARM-START-DATE='2022-01-01'`, `PARM-END-DATE='2022-07-06'`; `EXEC PGM=CBTRN03C` to write TRANREPT(+1) |
| `app/jcl/CREASTMT.JCL` | DELDEF01, STEP010, STEP020, STEP030, STEP040 | IDCAMS delete + DEFINE TRXFL.VSAM.KSDS (KEYS(32 0) RECORDSIZE(350 350)); SORT TRANSACT by card+id; IDCAMS REPRO; IEFBR14 cleanup; `EXEC PGM=CBSTM03A` writing STMTFILE.PS + STMTFILE.HTML |
| `app/jcl/PRTCATBL.jcl` | DELDEF, STEP05R (REPROC), STEP10R (SORT) | Delete prior TCATBALF.REPT; unload TCATBALF; SORT with OUTREC `EDIT=(TTTTTTTTT.TT)` by ACCT-ID + TYPE-CD + CAT-CD |
| `app/jcl/READACCT.jcl` | STEP05 | `EXEC PGM=CBACT01C`, ACCTFILE DD = ACCTDATA.VSAM.KSDS |
| `app/jcl/READCARD.jcl` | STEP05 | `EXEC PGM=CBACT02C`, CARDFILE DD = CARDDATA.VSAM.KSDS |
| `app/jcl/READCUST.jcl` | STEP05 | `EXEC PGM=CBCUS01C`, CUSTFILE DD = CUSTDATA.VSAM.KSDS |
| `app/jcl/READXREF.jcl` | STEP05 | `EXEC PGM=CBACT03C`, XREFFILE DD = CARDXREF.VSAM.KSDS |

#### 0.2.1.3 In-Scope Supporting Copybooks (10 copybooks)

These copybooks supply record layouts referenced via `COPY` statements in the in-scope batch programs, and their contents must be inlined into the `Input_File_Layout` and `Output_File_Layout` columns:

| Copybook | Record Name | RECLN | Used By |
|----------|-------------|-------|---------|
| `app/cpy/CVACT01Y.cpy` | ACCOUNT-RECORD | 300 | CBACT01C, CBACT04C, CBTRN01C, CBTRN02C, CBSTM03A |
| `app/cpy/CVACT02Y.cpy` | CARD-RECORD | 150 | CBACT02C, CBTRN01C |
| `app/cpy/CVACT03Y.cpy` | CARD-XREF-RECORD | 50 | CBACT03C, CBACT04C, CBTRN01C, CBTRN02C, CBTRN03C, CBSTM03A |
| `app/cpy/CVCUS01Y.cpy` | CUSTOMER-RECORD | 500 | CBCUS01C, CBTRN01C, CBSTM03A (via CUSTREC) |
| `app/cpy/CVTRA01Y.cpy` | TRAN-CAT-BAL-RECORD | 50 | CBACT04C, CBTRN02C |
| `app/cpy/CVTRA02Y.cpy` | DIS-GROUP-RECORD | 50 | CBACT04C |
| `app/cpy/CVTRA03Y.cpy` | TRAN-TYPE-RECORD | 60 | CBTRN03C |
| `app/cpy/CVTRA04Y.cpy` | TRAN-CAT-RECORD | 60 | CBTRN03C |
| `app/cpy/CVTRA05Y.cpy` | TRAN-RECORD | 350 | CBACT04C, CBTRN01C, CBTRN02C, CBTRN03C |
| `app/cpy/CVTRA06Y.cpy` | DALYTRAN-RECORD | 350 | CBTRN01C, CBTRN02C |
| `app/cpy/CVTRA07Y.cpy` | TRANSACTION-DETAIL-REPORT and friends | 133 | CBTRN03C |
| `app/cpy/COSTM01.CPY` | TRNX-RECORD | 350 | CBSTM03A, CBSTM03B |

#### 0.2.1.4 Out-of-Scope Files (Documented for Boundary Clarity)

The following files exist in the repository but are NOT in scope for the BRE because they implement CICS online programs, BMS screens, provisioning utilities, or non-batch admin tasks:

- 18 CICS-online COBOL programs (CO-prefix in `app/cbl/`): `COACTUPC`, `COACTVWC`, `COADM01C`, `COBIL00C`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COMEN01C`, `COREPT00C`, `COSGN00C`, `COTRN00C`, `COTRN01C`, `COTRN02C`, `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C`, plus user/help screens
- All 17 BMS map sources in `app/bms/`
- All generated symbolic-map copybooks in `app/cpy-bms/`
- 19 provisioning/admin JCL jobs: `ACCTFILE.jcl`, `CARDFILE.jcl`, `CUSTFILE.jcl`, `TRANFILE.jcl`, `TRANIDX.jcl`, `TRANBKP.jcl`, `XREFFILE.jcl`, `TCATBALF.jcl`, `TRANCATG.jcl`, `TRANTYPE.jcl`, `DISCGRP.jcl`, `DUSRSECJ.jcl`, `DEFCUST.jcl`, `DEFGDGB.jcl`, `REPTFILE.jcl`, `DALYREJS.jcl`, `CBADMCDJ.jcl`, `OPENFIL.jcl`, `CLOSEFIL.jcl`
- Authentication/security copybook content (`USRSEC` records)

### 0.2.2 Web Search Research Conducted

The Blitzy platform will perform web research as part of the Modernization Mapping section to validate the AWS Glue and Spring Batch translation patterns. The targeted research topics are:

- AWS Glue DynamicFrame and Spark DataFrame patterns for sequential file ingestion (S3 → DataFrame) and JDBC pushdown predicates against PostgreSQL
- Spring Batch 5.x reader/writer pattern catalog: `FlatFileItemReader`, `FlatFileItemWriter`, `JdbcCursorItemReader`, `JdbcBatchItemWriter`, `Tasklet`, `Step`, `Job`, `SkipPolicy`, `RetryPolicy`
- Java 25 LTS `BigDecimal` arithmetic semantics and rounding rules for replacement of COBOL COMP-3 packed decimal `S9(09)V99` arithmetic
- AWS Transfer Family / DataSync as the supported S3-PUT replacement for legacy FTP steps
- AWS Step Functions as a replacement for multi-program JCL CALL chains, mapping each `EXEC PGM=` to a Step Functions task

### 0.2.3 Existing Infrastructure Assessment

- **Project structure**: The repository follows a flat mainframe layout with `app/cbl`, `app/cpy`, `app/jcl`, `app/bms`, `app/cpy-bms`, `app/data`, `app/catlg`, plus `docs/`, `samples/jcl/`, `mkdocs.yml`, `catalog-info.yaml`, `README.md`. There is no `src/`, `lib/`, `tests/` Java/Spring layout in this repository — that scaffolding lives in the migration target codebase referenced by `docs/technical-specifications.md` but is out of scope for the BRE document itself.
- **Existing patterns and conventions**: Documentation uses Markdown via MkDocs with the `techdocs-core` and `mermaid2` plugins per `mkdocs.yml`. Existing docs (`docs/index.md`, `docs/project-guide.md`, `docs/technical-specifications.md`) use H1/H2/H3 headings with optional Mermaid diagrams.
- **Build and deployment configurations**: The repository's only build artifact is the MkDocs site (no compile step for COBOL is present in this repo). The MkDocs site auto-discovers `.md` files under `docs/`, which means a new `docs/bre/CardDemo_Batch_BRE.md` will appear automatically.
- **Testing infrastructure present**: None applicable to a documentation deliverable. No unit tests exist for documentation rendering; visual review and CSV-parser round-trip are the validation gate.
- **Documentation system in use**: MkDocs with Backstage TechDocs integration (per `catalog-info.yaml`). The `mkdocs.yml` plugin list includes `techdocs-core` and `mermaid2`, so Mermaid diagrams in the MD deliverable will render natively.

## 0.3 Scope Boundaries

### 0.3.1 Exhaustively In Scope

The following files and patterns are within the analysis perimeter for the BRE extraction. All listed paths are absolute repository paths.

**Source files to be analyzed (READ-ONLY, not modified):**

- COBOL batch programs (10 files): `app/cbl/CBACT01C.cbl`, `app/cbl/CBACT02C.cbl`, `app/cbl/CBACT03C.cbl`, `app/cbl/CBACT04C.cbl`, `app/cbl/CBCUS01C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN01C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/CBTRN03C.cbl`
- Batch JCL jobs (10 files): `app/jcl/POSTTRAN.jcl`, `app/jcl/INTCALC.jcl`, `app/jcl/COMBTRAN.jcl`, `app/jcl/TRANREPT.jcl`, `app/jcl/CREASTMT.JCL`, `app/jcl/PRTCATBL.jcl`, `app/jcl/READACCT.jcl`, `app/jcl/READCARD.jcl`, `app/jcl/READCUST.jcl`, `app/jcl/READXREF.jcl`
- Supporting copybooks (12 files): `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA02Y.cpy`, `app/cpy/CVTRA03Y.cpy`, `app/cpy/CVTRA04Y.cpy`, `app/cpy/CVTRA05Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVTRA07Y.cpy`, `app/cpy/COSTM01.CPY`

**Documentation deliverables to be created (NEW files, written by the platform):**

- New folder: `docs/bre/`
- New file: `docs/bre/CardDemo_Batch_BRE.csv` — the canonical BRE deliverable with header row + N data rows, RFC 4180 quoting, UTF-8 encoding, LF line endings
- New file: `docs/bre/CardDemo_Batch_BRE.md` — the Markdown rendering of the same tabular data, plus the appended "Modernization Mapping" section in Markdown format

**Optional documentation surface (low-priority, only if helpful for discoverability):**

- `mkdocs.yml` may receive a new `nav:` entry pointing to `bre/CardDemo_Batch_BRE.md` to make the new artifact reachable from the documentation site sidebar. This update is OPTIONAL because MkDocs auto-discovers Markdown files; the BRE documents render correctly even without an explicit nav entry.

### 0.3.2 Explicitly Out of Scope

The following are NOT in scope for this BRE deliverable and must NOT be modified, analyzed for rules, or referenced beyond the boundary-clarification context:

- **All CICS online COBOL programs** (CO-prefix in `app/cbl/`): The user explicitly directed "all the COBOL Batch Jobs not CICS". Programs `COACTUPC`, `COACTVWC`, `COADM01C`, `COBIL00C`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COMEN01C`, `COREPT00C`, `COSGN00C`, `COTRN00C`, `COTRN01C`, `COTRN02C`, `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C`, and any other CO-prefix programs are excluded.
- **All BMS map source files** in `app/bms/` (17 mapsets) — these are CICS screen definitions, not batch business rules.
- **All BMS symbolic-map copybooks** in `app/cpy-bms/` — generated from BMS sources, only consumed by CICS online programs.
- **CICS administration JCL**: `app/jcl/CBADMCDJ.jcl` (CSD update via DFHCSDUP), `app/jcl/OPENFIL.jcl` (SDSF-driven CICS file open), `app/jcl/CLOSEFIL.jcl` (SDSF-driven CICS file close) — these manage CICS region resources, not batch processing.
- **VSAM provisioning JCL**: `app/jcl/ACCTFILE.jcl`, `app/jcl/CARDFILE.jcl`, `app/jcl/CUSTFILE.jcl`, `app/jcl/TRANFILE.jcl`, `app/jcl/TRANIDX.jcl`, `app/jcl/TRANBKP.jcl`, `app/jcl/XREFFILE.jcl`, `app/jcl/TCATBALF.jcl`, `app/jcl/TRANCATG.jcl`, `app/jcl/TRANTYPE.jcl`, `app/jcl/DISCGRP.jcl`, `app/jcl/DEFCUST.jcl` — these IDCAMS DEFINE/DELETE/REPRO + IEBGENER seed-load utilities are infrastructure provisioning, not business processing.
- **GDG and security setup JCL**: `app/jcl/DEFGDGB.jcl`, `app/jcl/REPTFILE.jcl`, `app/jcl/DALYREJS.jcl`, `app/jcl/DUSRSECJ.jcl` — generation-data-group base definitions and user-security KSDS provisioning.
- **Data fixture files** in `app/data/` — these are flat ASCII test data; they may be referenced for context but their record contents are not extracted as rules.
- **IDCAMS catalog listing** at `app/catlg/` — purely descriptive output of `LISTCAT`, not a rule source.
- **Sample build JCL** in `samples/jcl/` — example/demonstrative JCL not part of the operational batch flow.
- **Existing documentation files** `docs/index.md`, `docs/project-guide.md`, `docs/technical-specifications.md` — must NOT be modified; they describe the migration target and are independent of the BRE deliverable.
- **Project meta-files**: `README.md`, `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `catalog-info.yaml`, `mkdocs.yml` (except for the optional `nav:` entry described in §0.3.1).
- **Refactoring of existing COBOL/JCL/copybook source** — the BRE is a read-only extraction; no `.cbl`, `.cpy`, `.jcl`, or `.JCL` file contents change.
- **Performance optimizations beyond requirements** — the user did not request any runtime/build-time performance work.
- **Future enhancements not part of current request** — no Glue job scaffolding, no Spring Batch project skeleton, no Java code, no PostgreSQL schema migrations are produced. The Modernization Mapping section provides only the conceptual mapping table, not generated Glue/Java code.
- **Tests for the BRE document itself** — no test framework, no validation harness, no CI pipeline is added; the deliverable is a static documentation artifact.

## 0.4 Dependency Inventory

### 0.4.1 Key Private and Public Packages

This BRE deliverable is a static documentation artifact (one CSV file plus one Markdown file) and therefore introduces **no runtime dependencies** into the CardDemo repository. There is no `package.json`, `requirements.txt`, `pyproject.toml`, `pom.xml`, `build.gradle`, or `Gemfile` to update because no executable code is added to the repository.

The Blitzy platform uses Python's standard library exclusively to author the CSV — no third-party Python packages are imported, installed, or required during generation. The MD file is plain UTF-8 Markdown and likewise requires no dependencies.

For completeness, the following table enumerates the **runtime tooling already present in the documentation toolchain** that will render or consume the new artifacts. None of these are added or modified by this deliverable; they are listed only because they govern how the new BRE files appear in downstream environments.

| Registry | Package Name | Version | Purpose |
|----------|--------------|---------|---------|
| Python stdlib | `csv` | Python 3.12.3 (stdlib) | CSV authoring with RFC 4180 quoting during generation by the Blitzy platform |
| Python stdlib | `json` | Python 3.12.3 (stdlib) | Optional JSON-side validation during generation (not required at runtime) |
| pip (existing in MkDocs site) | `mkdocs-techdocs-core` | per `mkdocs.yml` config | Backstage TechDocs theme that renders `.md` files under `docs/` (already configured in the repository) |
| pip (existing in MkDocs site) | `mkdocs-mermaid2-plugin` | per `mkdocs.yml` config | Mermaid diagram rendering inside `.md` files (already configured in the repository) |

The two MkDocs plugins are pre-existing in `mkdocs.yml` and do not change as a result of this deliverable. They are listed here only so reviewers understand which engine renders the BRE Markdown file when the documentation site is built.

### 0.4.2 Dependency Updates

- **New dependencies to add**: None. No new package is added to any manifest in the repository.
- **Dependencies to update**: None. No existing package version changes.
- **Dependencies to remove**: None.
- **Import / Reference Updates**: None. No existing source file's imports, requires, or copybook `COPY` statements are modified.

### 0.4.3 File-Format Specifications (For Generated Deliverables)

The two new files conform to the following specifications, which are documentation-format contracts rather than software dependencies:

| Specification | Applies To | Description |
|---------------|------------|-------------|
| RFC 4180 (CSV) | `docs/bre/CardDemo_Batch_BRE.csv` | Quoting policy: every field is enclosed in double quotes; embedded double quotes are escaped by doubling (`""`); embedded commas, newlines, and `\n` literals remain inside the quoted field |
| UTF-8 (no BOM) | both files | Character encoding |
| LF line endings | both files | POSIX-style line termination, consistent with the rest of `docs/` |
| CommonMark + GitHub-flavored tables | `docs/bre/CardDemo_Batch_BRE.md` | Markdown dialect rendered by `mkdocs-techdocs-core` |
| Mermaid v10 syntax | optional within the MD file | Diagrams in the Modernization Mapping section, rendered by `mkdocs-mermaid2-plugin` |

### 0.4.4 Tooling Available During Generation

The Blitzy platform's local environment provides the following tools for inspecting source and authoring deliverables. None of these become repository dependencies; they exist only in the build environment that produces the BRE document.

| Tool | Version | Use During Generation |
|------|---------|------------------------|
| Python | 3.12.3 | Author CSV via `csv` module, validate row/column counts, compute record-length sums from PIC clauses |
| `git` | system-installed | Trace blame and verify version markers (e.g., `Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19`) embedded as comments in COBOL/copybook sources |
| `grep` / `awk` / `sed` | system-installed | Bulk extraction of `EXEC PGM=`, `PERFORM`, `EVALUATE`, `READ`, `WRITE`, `MOVE` patterns across COBOL and JCL |

## 0.5 Implementation Design

### 0.5.1 Technical Approach

The Blitzy platform achieves the BRE deliverable by performing static analysis of the in-scope COBOL and JCL sources, building an in-memory rule list with the prescribed 20 columns per row, and then serializing that list to two parallel formats. The implementation is a documentation-generation task — no application code, build artifact, or test runs.

#### 0.5.1.1 Logical Implementation Flow

The flow is sequential, NOT a calendar timeline. Each phase produces inputs for the next.

- **First, establish the rule extraction skeleton** by enumerating, in the documented JCL execution order of the operational batch pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT, plus standalone read utilities and PRTCATBL), every JCL step (`EXEC PGM=` / `EXEC PROC=`) and every COBOL paragraph (`PERFORM` target) reachable from each program's PROCEDURE DIVISION entry point. For each enumeration target, allocate a sequential `Rule_Number` of the form `BR-<JOBNAME>-NNN` with three-digit zero padding.
- **Next, populate per-row metadata** by mapping each rule to its `Module_Name` (COBOL `PROGRAM-ID` or JCL `EXEC PGM=` value), `Sub_Module` (COBOL paragraph or SECTION name, JCL step label), `Input_File` and `Output_File` (DD names from JCL or COBOL `SELECT ... ASSIGN TO` clauses), file lengths and layouts (computed from `FD`/`SD` and `01` record definitions per copybook), and the `Bounded_Context` derived from program purpose (e.g., POSTTRAN → "Daily Transaction Posting").
- **Then, transcribe the decision logic** by copying each `IF`, `EVALUATE`, `WHEN`, `READ ... INVALID KEY`, `COMPUTE`, and `MOVE` block verbatim into `SQL_Decision_Control_Statements` (preserving COBOL syntax, with multi-line statements joined by literal `\n`), and authoring a 2–6 sentence plain-business-language explanation in `Detailed_Business_Rule` that uses business terms (PPO level quantity, monthly interest, cycle credit, cycle debit, current balance, expiration date, account group, disclosure group, transaction category) rather than COBOL field names. Each row's `Code_Reference` is set to the line-number range or paragraph name; each row's `SQL_Function` records any COBOL intrinsic function used (`FUNCTION CURRENT-DATE`, `FUNCTION MOD`, `FUNCTION LENGTH`).
- **Then, classify each rule** into exactly one of the twelve fixed `Business_Rule_Category` enumerations: `Initialization` for `OPEN` blocks and counter resets, `File-IO` for `READ`/`WRITE`/`REWRITE`/`CLOSE`, `Data-Validation` for credit-limit / expiration / lookup checks, `Date-Time` for date arithmetic and DB2-format timestamp generation, `Cycle-Determination` for account-group changes detected via `WS-LAST-ACCT-NUM`, `Calculation` for interest formulas and balance updates, `Sorting` for DFSORT steps and record-key composition, `Reporting` for header/detail/total writes, `Error-Handling` for `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`, and `INVALID KEY` paths, `FTP-Distribution` for any FTP step (none present in this codebase but reserved per the user's specification), `Finalization` for `GOBACK` and final return-code propagation, `Cleanup` for IDCAMS DELETE / IEFBR14 dataset cleanup steps in JCL.
- **Then, flag modernization risks** in `Review_Comments` by scanning each row for hardcoded DSN names (e.g., `AWS.M2.CARDDEMO.LOADLIB`), hardcoded date literals (e.g., `'2022071800'` PARM, `'2022-01-01'` to `'2022-07-06'` SORT date window in TRANREPT), `CONTINUE` after non-zero status (silent failures), `GOTO` statements (CBSTM03A uses `GO TO` and `ALTER`), packed-decimal `COMP-3` arithmetic (CBSTM03A `WS-TOTAL-AMT`), SORT utility usage (COMBTRAN, TRANREPT, CREASTMT, PRTCATBL), GDG references (`+1`, `(0)` notations), and multi-program CALL chains (CBSTM03A → CBSTM03B). Rows free of risk receive `"None"`.
- **Then, serialize the rules** by writing the header row plus N data rows to `docs/bre/CardDemo_Batch_BRE.csv` using Python's `csv.writer` with `quoting=csv.QUOTE_ALL` to satisfy RFC 4180, and by re-emitting the same rows as a GitHub-flavored Markdown table inside `docs/bre/CardDemo_Batch_BRE.md`.
- **Finally, append the Modernization Mapping section** to the MD file as three numbered Markdown subsections (AWS Glue mapping, Java ECS batch mapping, Top 5 risks), and emit the same content verbatim as a plain-text trailer after the CSV (per the user's directive that the Modernization Mapping appears "after the CSV in plain text and for MD file produce it in Markdown format").

#### 0.5.1.2 Rule Inventory by Job (Pre-Computed Plan)

The following table enumerates the planned rule rows by job, with execution order and approximate count. The Blitzy platform must produce at minimum the rule rows listed below; additional rows may be created where additional discrete decision points exist within a paragraph.

| Job_Name | Modules Invoked | Approximate Rule Count | Bounded_Context |
|----------|-----------------|------------------------|-----------------|
| POSTTRAN | CBTRN02C | 35–45 rules (1 JCL step + 6 file opens + transaction read loop + XREF lookup + ACCOUNT lookup + 4 validation checks + 5 reject paths + TCATBAL update + ACCOUNT cycle update + TRANSACT write + reject record write + 6 closes + reject summary + abend + status display + EOF) | Daily Transaction Posting |
| INTCALC | CBACT04C | 30–40 rules (1 JCL step including PARM + 5 file opens + TCATBAL sequential read + group-change detection + ACCOUNT lookup + XREF alternate-key lookup + DISCGRP lookup with DEFAULT fallback + interest computation formula + interest transaction generation with hardcoded 01/05/System + ACCOUNT update with cycle reset + 5 closes + abend + status display) | Interest & Fee Calculation |
| COMBTRAN | SORT, IDCAMS REPRO | 4–6 rules (SORT step keyed on TRAN-ID, REPRO step into TRANSACT.VSAM.KSDS, GDG advance) | Transaction Consolidation |
| TRANREPT | REPROC PROC, SORT, CBTRN03C | 35–45 rules (REPROC backup unload + SORT date-window filter with `2022-01-01` to `2022-07-06` literals + 6 file opens + DATEPARM read + transaction read loop + date-window predicate + card-break detection + XREF lookup + TRANTYPE lookup + TRANCATG lookup + page total / account total / grand total writes + page break logic with `MOD(WS-LINE-COUNTER, 20)` + 6 closes) | Transaction Reporting |
| CREASTMT | IDCAMS DELETE/DEFINE, SORT, IDCAMS REPRO, IEFBR14, CBSTM03A→CBSTM03B | 50–60 rules (delete + define TRXFL.VSAM.KSDS + sort by card+id + REPRO + IEFBR14 cleanup + CBSTM03A TIOT inspection + ALTER/GO TO dispatch + buffered transaction table fill + XREFFILE sequential read + CUSTFILE keyed read + ACCTFILE keyed read + statement creation + text + HTML write per transaction + per-card aggregation + total expense + closes + CBSTM03B subroutine OPEN/READ/READ-K/CLOSE for each of TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE) | Statement Generation |
| PRTCATBL | IDCAMS DELETE, REPROC PROC, SORT | 4–6 rules (delete prior REPT + unload TCATBALF + sort with composite key ACCT-ID/TYPE-CD/CAT-CD + EDIT mask `TTTTTTTTT.TT`) | Category Balance Reporting |
| READACCT | CBACT01C | 8–10 rules (1 JCL step + open + sequential read loop + display + close + EOF + abend + status display) | Account Master Inspection |
| READCARD | CBACT02C | 8–10 rules (same pattern as READACCT for CARDFILE/CARD-RECORD) | Card Master Inspection |
| READCUST | CBCUS01C | 8–10 rules (same pattern for CUSTFILE/CUSTOMER-RECORD) | Customer Master Inspection |
| READXREF | CBACT03C | 8–10 rules (same pattern for XREFFILE/CARD-XREF-RECORD) | Card Cross-Reference Inspection |

The pre-extracted business rules below are the **minimum content** the Blitzy platform must transcribe. Additional rules may emerge from secondary decision points inside any paragraph.

##### 0.5.1.2.1 POSTTRAN / CBTRN02C Anchor Rules

Confirmed from line-by-line reading of `app/cbl/CBTRN02C.cbl` and `app/jcl/POSTTRAN.jcl`:

- **REJECT-100 INVALID CARD NUMBER FOUND**: When `READ XREF-FILE` from `1500-A-LOOKUP-XREF` returns `INVALID KEY`, the daily transaction is rejected with reason code `100` and reason description `"INVALID CARD NUMBER FOUND"`. The reject record is written to `DALYREJS` via `2500-WRITE-REJECT-REC`.
- **REJECT-101 ACCOUNT RECORD NOT FOUND**: When `READ ACCOUNT-FILE` from `1500-B-LOOKUP-ACCT` returns `INVALID KEY`, the transaction is rejected with reason code `101` and description `"ACCOUNT RECORD NOT FOUND"`.
- **REJECT-102 OVERLIMIT TRANSACTION**: After computing `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`, if `ACCT-CREDIT-LIMIT < WS-TEMP-BAL`, the transaction is rejected with reason code `102` and description `"OVERLIMIT TRANSACTION"`. In business language: the transaction is rejected when the account's posted credit limit is less than the current cycle's net activity plus the new transaction amount.
- **REJECT-103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION**: If `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)`, the transaction is rejected with reason code `103` and description `"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"`. The first 10 characters of the daily-transaction origination timestamp are compared character-wise against the account expiration date in `YYYY-MM-DD` form.
- **TCATBAL UPDATE (composite key)**: `2700-UPDATE-TCATBAL` builds a composite key from `XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD`. On `READ TCATBAL` with status `'23'` (record not found), the program sets `WS-CREATE-TRANCAT-REC = 'Y'`, INITIALIZEs a new record, populates the keys, adds `DALYTRAN-AMT` to `TRAN-CAT-BAL`, and WRITEs. Otherwise it adds and REWRITEs.
- **ACCOUNT CYCLE UPDATE (signed-amount routing)**: `2800-UPDATE-ACCOUNT-REC` always adds `DALYTRAN-AMT` to `ACCT-CURR-BAL`. When `DALYTRAN-AMT >= 0`, the amount is added to `ACCT-CURR-CYC-CREDIT`; otherwise it is added to `ACCT-CURR-CYC-DEBIT`. The record is then REWRITTEN. An `INVALID KEY` on REWRITE produces reject code `109`.
- **REJECT END-OF-JOB**: If `WS-REJECT-COUNT > 0`, the program sets `RETURN-CODE = 4`. The job exits with non-zero return code so downstream JCL steps can detect the partial-failure condition.
- **TCATBAL STATUS '00' OR '23'**: Read success conditions accept both `00` (record found) and `23` (record not found, expected for new categories). The Blitzy platform classifies this as a controlled silent-fallback that must be flagged in `Review_Comments`.

##### 0.5.1.2.2 INTCALC / CBACT04C Anchor Rules

Confirmed from line-by-line reading of `app/cbl/CBACT04C.cbl` and `app/jcl/INTCALC.jcl`:

- **HARDCODED PARM DATE**: The JCL invokes `EXEC PGM=CBACT04C,PARM='2022071800'`. The 10-character `PARM-DATE` is consumed by the program as a string used to compose interest-transaction IDs. This is a hardcoded date that must be flagged as a modernization risk and replaced with a job parameter or configuration table entry.
- **GROUP-CHANGE DETECTION**: When `TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM` while reading TCATBAL sequentially, the program (a) commits accumulated interest for the prior account by performing `1050-UPDATE-ACCOUNT` (skipped on first iteration via `WS-FIRST-TIME = 'Y'`), (b) resets `WS-TOTAL-INT` to 0, (c) updates `WS-LAST-ACCT-NUM`, (d) reads the ACCOUNT record by ID, (e) reads the XREF record by alternate key `FD-XREF-ACCT-ID`.
- **DISCGRP LOOKUP WITH DEFAULT FALLBACK**: `1200-GET-INTEREST-RATE` reads DISCGRP by composite key (`ACCT-GROUP-ID + TRANCAT-CD + TRANCAT-TYPE-CD`). On `DISCGRP-STATUS = '23'` (not found), the program substitutes `'DEFAULT'` for `FD-DIS-ACCT-GROUP-ID` and re-reads via `1200-A-GET-DEFAULT-INT-RATE`. Status `'00'` and `'23'` both treated as success initially.
- **INTEREST FORMULA**: `WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200`. Interpretation: monthly interest equals the category balance multiplied by the annual disclosure interest rate, divided by 1200 (which is 100 × 12 — converting percentage to fraction and annual to monthly). Computed only IF `DIS-INT-RATE NOT = 0`.
- **INTEREST TRANSACTION GENERATION**: `1300-B-WRITE-TX` increments `WS-TRANID-SUFFIX` (zero-padded 6 digits), STRINGs `PARM-DATE + WS-TRANID-SUFFIX` into `TRAN-ID` (16 chars total). It then assigns the hardcoded values `TRAN-TYPE-CD = '01'`, `TRAN-CAT-CD = '05'`, `TRAN-SOURCE = 'System'`, `TRAN-DESC = 'Int. for a/c ' + ACCT-ID`, `TRAN-AMT = WS-MONTHLY-INT`, `TRAN-CARD-NUM = XREF-CARD-NUM`, and sets `TRAN-ORIG-TS = TRAN-PROC-TS` to the current DB2-format timestamp. WRITEs to TRANSACT (sequential, GDG `SYSTRAN(+1)`).
- **ACCOUNT INTEREST POSTING WITH CYCLE RESET**: `1050-UPDATE-ACCOUNT` adds `WS-TOTAL-INT` to `ACCT-CURR-BAL`, then **MOVE 0 TO ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT** (resets cycle balances after interest posting), then REWRITES. This means after interest run, the account starts a fresh cycle with zero accumulated cycle credit/debit. Must be classified as `Calculation` + `Cycle-Determination`.
- **EMPTY FEES STUB**: `1400-COMPUTE-FEES` is a stub paragraph "To be implemented". This is dead code that must be flagged in `Review_Comments`.

##### 0.5.1.2.3 TRANREPT / CBTRN03C Anchor Rules

Confirmed from line-by-line reading of `app/cbl/CBTRN03C.cbl` and `app/jcl/TRANREPT.jcl`:

- **HARDCODED DATE WINDOW**: TRANREPT.jcl SORT step uses `PARM-START-DATE='2022-01-01'` and `PARM-END-DATE='2022-07-06'`. These literals must be flagged as modernization risks and replaced with job parameters at migration.
- **DATE-WINDOW PREDICATE**: For each transaction, `IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND TRAN-PROC-TS(1:10) <= WS-END-DATE` qualifies the row. Otherwise `NEXT SENTENCE` skips to the next read.
- **CARD-NUMBER BREAK**: `IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM` triggers (a) writing the prior account totals via `1120-WRITE-ACCOUNT-TOTALS` (skipped on first iteration via `WS-FIRST-TIME = 'Y'`), (b) updating `WS-CURR-CARD-NUM`, (c) reading XREF by card number to fetch `XREF-ACCT-ID`.
- **PAGE BREAK LOGIC**: `IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` writes page totals and a fresh page header. Page size is hardcoded to 20 lines (`05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20`).
- **THREE TIERS OF TOTALS**: Page total accumulates `TRAN-AMT` per page (resets on page break), account total accumulates per card (resets on card break), grand total accumulates across all qualifying transactions (writes once at EOF).
- **EOF PAGE/GRAND TOTAL EMISSION**: At end-of-file, the program adds the final `TRAN-AMT` to page and account totals, then writes both `1110-WRITE-PAGE-TOTALS` and `1110-WRITE-GRAND-TOTALS`.

##### 0.5.1.2.4 CREASTMT / CBSTM03A / CBSTM03B Anchor Rules

Confirmed from line-by-line reading of `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, and `app/jcl/CREASTMT.JCL`:

- **TRXFL DEFINE**: STEP DELDEF01 IDCAMS deletes any prior `TRXFL.VSAM.KSDS` and DEFINEs a new KSDS with `KEYS(32 0)` (32-byte key starting at offset 0) and `RECORDSIZE(350 350)` (fixed 350 bytes).
- **TIOT INSPECTION**: CBSTM03A uses control-block addressing (PSA → TCB → TIOT) to display the running JCL job name and step name and to enumerate all DD names with their UCB validity. This is a legacy demonstration feature and must be flagged for modernization (no equivalent in containerized Java).
- **ALTER + GO TO DISPATCH**: The `0000-START` paragraph uses `EVALUATE WS-FL-DD` with `ALTER 8100-FILE-OPEN TO PROCEED TO ...` to dynamically retarget a `GO TO`. This is dead-on-arrival in modern Java; must be flagged as HIGH risk.
- **BUFFERED TRANSACTION TABLE**: CBSTM03A allocates `WS-TRNX-TABLE` as `WS-CARD-TBL OCCURS 51 TIMES` × `WS-TRAN-TBL OCCURS 10 TIMES`, capping at 51 cards × 10 transactions per card. Cards beyond 51 or transactions beyond 10 per card are silently discarded. This array bounds limit must be flagged.
- **CALLABLE FILE SERVICE**: CBSTM03B exposes `LK-M03B-AREA` with operation flags `O`/`C`/`R`/`K`/`W`/`Z` (only O/C/R/K implemented). The four files supported are TRNXFILE (sequential indexed), XREFFILE (sequential indexed), CUSTFILE (random keyed by FD-CUST-ID PIC X(09)), ACCTFILE (random keyed by FD-ACCT-ID PIC 9(11)). Note the type mismatch: CBSTM03B's CUSTFILE FD declares `FD-CUST-ID PIC X(09)` (alphanumeric), whereas CBCUS01C and the CVCUS01Y copybook declare it as `PIC 9(09)` (zoned-decimal). This is a copy-paste defect that must be flagged.
- **STATEMENT TEXT LINES**: 16 statement lines (ST-LINE0 to ST-LINE15) are written for each card with non-empty transactions. ST-LINE14 is the per-transaction detail line (`TRNX-ID`, `TRNX-DESC`, `TRNX-AMT`); ST-LINE14A is the per-card total.
- **HTML LINES**: 80+ HTML 88-level constants (`HTML-L01` … `HTML-L80`) define the static HTML template. Dynamic lines are STRING-assembled for customer name (`L23-NAME`), address (`HTML-ADDR-LN`), basic details (`HTML-BSIC-LN`), and per-transaction cells (`HTML-TRAN-LN`).

##### 0.5.1.2.5 Read Utility Anchor Rules (CBACT01C, CBACT02C, CBACT03C, CBCUS01C)

Each read utility follows an identical 7-paragraph template; the rules differ only in the file name, key name, and copybook used:

- `0000-<DDNAME>-OPEN`: `OPEN INPUT <FILE>`. Status `'00'` → success; otherwise display `ERROR OPENING <FILE>`, perform `9910-DISPLAY-IO-STATUS`, perform `9999-ABEND-PROGRAM`.
- `1000-<DDNAME>-GET-NEXT`: `READ <FILE> INTO <RECORD>`. Status `'00'` → success; `'10'` → set END-OF-FILE = `'Y'`; otherwise abend.
- `1100-<DDNAME>-DISPLAY` (CBACT01C only): emits all account fields to SYSOUT.
- Two redundant DISPLAYs in CBACT02C, CBACT03C, CBCUS01C (record displayed inside `1000-` paragraph AND again in caller loop) — must be flagged as a copy-paste defect.
- `9000-<DDNAME>-CLOSE`: `CLOSE <FILE>`. Same status pattern.
- `9999-ABEND-PROGRAM`: `MOVE 0 TO TIMING; MOVE 999 TO ABCODE; CALL 'CEE3ABD'.` — universal abend with hardcoded ABCODE 999.
- `9910-DISPLAY-IO-STATUS` (or `Z-DISPLAY-IO-STATUS` in CBCUS01C/CBTRN01C): converts numeric / `9x` / non-numeric status into 4-char display form.

### 0.5.2 Component Impact Analysis

#### 0.5.2.1 Direct Modifications Required

- **NEW: `docs/bre/CardDemo_Batch_BRE.csv`** — Create the canonical 20-column CSV. This is the primary deliverable.
- **NEW: `docs/bre/CardDemo_Batch_BRE.md`** — Create the Markdown rendering of the CSV plus the Modernization Mapping appendix.
- **NEW: `docs/bre/`** — Create the parent folder.

There are NO modifications to any existing file in the repository.

#### 0.5.2.2 Indirect Impacts and Dependencies

- **MkDocs site build**: The site auto-discovers Markdown files under `docs/`. After this deliverable lands, the BRE document becomes available at the corresponding rendered URL. No build configuration changes are required.
- **Backstage TechDocs**: Through `catalog-info.yaml`, the BRE document becomes part of the rendered TechDocs surface for the CardDemo entity. No catalog descriptor changes are required.
- **Migration team consumption**: The BRE document is the input contract for the Java/Spring Batch migration team and the AWS Glue migration team. They use the `Detailed_Business_Rule`, `SQL_Decision_Control_Statements`, and `Code_Reference` columns to author Spring Batch Steps and PySpark UDFs that replicate each rule.

#### 0.5.2.3 New Components Introduction

- **`docs/bre/` folder** — Created as a sibling to existing `docs/index.md`, `docs/project-guide.md`, `docs/technical-specifications.md`. Rationale: the BRE artifacts are conceptually distinct from the existing technical-specification narratives; placing them in their own folder keeps the documentation taxonomy clean and allows future expansion (e.g., `docs/bre/CardDemo_Online_BRE.csv` for the eventual CICS extraction).

### 0.5.3 User Interface Design

Not applicable. The BRE deliverable is data-only documentation (CSV + Markdown). There is no graphical user interface to design. Visual presentation is governed by:

- The CSV opens in any spreadsheet application (Excel, LibreOffice Calc, Google Sheets) with column auto-sizing. Stakeholders use spreadsheet filters to slice rules by `Job_Name`, `Business_Rule_Category`, `Bounded_Context`, or non-empty `Review_Comments`.
- The Markdown table renders as a wide GitHub-flavored table when viewed via the MkDocs site, GitHub web view, or any CommonMark renderer. Because 20 columns is wider than typical screens, the table will horizontally scroll; this is the expected behavior and matches the user's prescribed format.

### 0.5.4 User-Provided Examples Integration

The user provided four explicit examples in the prompt that the Blitzy platform must preserve verbatim in the deliverable:

- **User Example: "BR-[JOBNAME]-001, BR-[JOBNAME]-002, ..."** — Implemented as the literal `Rule_Number` template `BR-POSTTRAN-001`, `BR-POSTTRAN-002`, …, `BR-INTCALC-001`, `BR-INTCALC-002`, …, etc., with three-digit zero padding.
- **User Example: "within 5 business days", "if quantity exceeds 999"** — These exact phrasings appear in the user's prompt as the style guide for `Detailed_Business_Rule`. The Blitzy platform applies the same plain-business-language pattern, e.g., `"the transaction is rejected when the credit limit is less than the running cycle balance plus the new transaction amount"` rather than `"IF ACCT-CREDIT-LIMIT < WS-TEMP-BAL"`.
- **User Example: "PPO Leveling, Forecast Reporting, Inventory Cleanup"** — These are illustrative `Bounded_Context` values from the user's prompt. The Blitzy platform applies the same pattern by deriving from the actual program domain: `"Daily Transaction Posting"`, `"Interest & Fee Calculation"`, `"Transaction Reporting"`, `"Statement Generation"`, etc.
- **User Example: "WS-PPO-LEVEL-QTY → 'PPO level quantity'"** — Field-name to business-term translation. Applied as `WS-MONTHLY-INT → "monthly interest amount"`, `ACCT-CURR-CYC-CREDIT → "current cycle credit"`, `ACCT-CURR-CYC-DEBIT → "current cycle debit"`, `WS-TEMP-BAL → "running cycle balance"`, `DALYTRAN-AMT → "daily transaction amount"`, `DIS-INT-RATE → "annual disclosure interest rate"`, `WS-LAST-ACCT-NUM → "previously processed account number"`.

### 0.5.5 Critical Implementation Details

- **Design pattern: Static analyzer + tabular emitter**. The implementation reads each `.cbl`, `.jcl`, and `.cpy` file, walks its parse tree (paragraph by paragraph, step by step), accumulates rule rows in memory, and emits the final CSV/MD in one pass. No incremental updates; the deliverable is regenerated atomically.
- **PIC clause length computation algorithm**:
  - `PIC X(n)` → n bytes
  - `PIC 9(n)` (no USAGE) → n bytes (zoned decimal)
  - `PIC S9(n) COMP` or `BINARY` → 2 bytes if n ≤ 4, 4 bytes if 5 ≤ n ≤ 9, 8 bytes if 10 ≤ n ≤ 18
  - `PIC S9(n)V99 COMP-3` (packed decimal) → ⌈(n+2+1)/2⌉ bytes (one nibble per digit + sign nibble); e.g., `S9(09)V99` (12 digits + sign) packs into 7 bytes; `S9(10)V99` (13 digits + sign) packs into 7 bytes; `S9(04)V99` (7 digits + sign) packs into 4 bytes
  - Total record length is the sum of all 05-level (and lower-level) field lengths
- **CSV authoring discipline**:
  - Use `csv.writer(file, quoting=csv.QUOTE_ALL, lineterminator='\n')` to enforce RFC 4180 quoting on every field
  - Write the header row first, then iterate rules
  - Use the literal two-character escape sequence `\n` (backslash + n) inside `SQL_Decision_Control_Statements` for multi-line statements, NOT a real newline
- **Markdown table authoring**:
  - Use the GitHub-flavored Markdown table syntax with `|` column separators and `|---|` header underline
  - Inside cells, replace `|` with `\|` to avoid breaking the table structure
  - Inside cells, replace newlines and `\n` literals with HTML `<br/>` for visual readability
- **Modernization Mapping in MD**:
  - Three numbered subsections: `### 1. AWS Glue Mapping`, `### 2. Java ECS Batch Mapping`, `### 3. Top 5 Modernization Risks`
  - AWS Glue mapping uses a table per program with columns Program, Glue Construct, S3 Location, Notes
  - Java ECS batch mapping uses a table per program with columns Program, Spring Batch Component, Configuration Notes
  - Top 5 risks ordered HIGH → LOW with severity, description, affected programs, mitigation
- **Error handling and edge cases during generation**:
  - If a COBOL paragraph spans more than 200 lines, the rule row's `Code_Reference` cites the inclusive range (e.g., `205-432`)
  - If a paragraph contains both `IF` validation AND a `READ` operation, two rule rows are emitted, distinguished by `Sub_Module` suffixes (e.g., `2800-UPDATE-ACCOUNT-REC` and `2800-UPDATE-ACCOUNT-REC (REWRITE)`)
  - If `EVALUATE` has multiple `WHEN` branches that change data flow, each branch produces its own row with a parenthesized branch label appended to `Sub_Module`
  - If a JCL step contains an inline `SYSIN DD *` IDCAMS or SORT control statement, the control statement is captured verbatim in `SQL_Decision_Control_Statements`
- **Performance**: The CSV will contain approximately 250–350 rows (10 jobs × 25–35 rules average). At ~500 bytes/row, total CSV size is roughly 150–200 KB. The MD file is comparable in size plus the Modernization Mapping appendix (~30 KB). Both files are well within Git diff and MkDocs rendering limits.
- **Security**: No credentials, secrets, or PII are written to either deliverable. Hardcoded DSN names and dates are reproduced verbatim because the user explicitly requested they appear in `SQL_Decision_Control_Statements` for traceability, but they are flagged in `Review_Comments` so the migration team can replace them with parameter-driven values.

## 0.6 File Transformation Mapping

### 0.6.1 File-by-File Execution Plan

The following table is the complete, exhaustive enumeration of every file that the Blitzy platform creates, updates, deletes, or references for this BRE deliverable. The target file is listed first; the source/reference file is listed second; transformation modes are `CREATE`, `UPDATE`, `DELETE`, or `REFERENCE`.

| Target File | Transformation | Source File / Reference | Purpose / Changes |
|-------------|----------------|--------------------------|-------------------|
| `docs/bre/CardDemo_Batch_BRE.csv` | CREATE | All in-scope COBOL/JCL/copybook files (see REFERENCE rows below) | Canonical BRE deliverable: header row plus N data rows of 20 columns each, RFC 4180 quoting, UTF-8, LF line endings. Contains every business rule extracted from the 10 batch JCL jobs and 10 batch COBOL programs. |
| `docs/bre/CardDemo_Batch_BRE.md` | CREATE | `docs/bre/CardDemo_Batch_BRE.csv` (same row content) and the in-scope source files | Markdown rendering of the same 20-column tabular content as the CSV (GitHub-flavored Markdown table) PLUS the appended "Modernization Mapping" section in Markdown format with three numbered subsections (AWS Glue, Java ECS batch, Top 5 risks). |
| `docs/bre/` | CREATE | (folder) | New folder under `docs/` to host the two BRE artifacts. Created implicitly when the first file inside it is written. |
| `app/cbl/CBACT01C.cbl` | REFERENCE | n/a | Read-only static analysis source for `READACCT` rules (account master inspection). Used to extract `0000-CARDFILE-OPEN` (mis-named in source — actually opens ACCTFILE), `1000-ACCTFILE-GET-NEXT`, `1100-DISPLAY-ACCT-RECORD`, `9000-ACCTFILE-CLOSE`, `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`. |
| `app/cbl/CBACT02C.cbl` | REFERENCE | n/a | Read-only source for `READCARD` rules (card master inspection). Extract sequential CARDFILE traversal paragraphs and double-display defect for `Review_Comments`. |
| `app/cbl/CBACT03C.cbl` | REFERENCE | n/a | Read-only source for `READXREF` rules (card cross-reference inspection). Extract sequential XREFFILE traversal and double-display defect. |
| `app/cbl/CBACT04C.cbl` | REFERENCE | n/a | Read-only source for `INTCALC` rules (interest calculator). Extract group-change detection, DISCGRP DEFAULT fallback, monthly interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`, hardcoded `'01'`/`'05'`/`'System'` interest-transaction values, cycle credit/debit reset on `1050-UPDATE-ACCOUNT`, empty `1400-COMPUTE-FEES` stub. |
| `app/cbl/CBCUS01C.cbl` | REFERENCE | n/a | Read-only source for `READCUST` rules (customer master inspection). Extract sequential CUSTFILE traversal and `Z-DISPLAY-IO-STATUS`/`Z-ABEND-PROGRAM` paragraphs. |
| `app/cbl/CBSTM03A.CBL` | REFERENCE | n/a | Read-only source for `CREASTMT` statement creator rules. Extract TIOT inspection, ALTER+GO TO dispatch, EVALUATE WS-FL-DD branches (TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE), buffered transaction table fill (`51 × 10`), card-break detection, statement text writes, statement HTML writes, total expense aggregation. |
| `app/cbl/CBSTM03B.CBL` | REFERENCE | n/a | Read-only source for `CREASTMT` file-service subroutine rules. Extract `EVALUATE LK-M03B-DD` branches, OPEN/READ/READ-K/CLOSE per file, FD-CUST-ID type mismatch (`PIC X(09)` vs CVCUS01Y's `PIC 9(09)`). |
| `app/cbl/CBTRN01C.cbl` | REFERENCE | n/a | Read-only source for daily transaction validation driver rules. Extract sequential DALYTRAN read, XREF lookup, ACCOUNT lookup, diagnostic display (no posting writes despite "post" in header). |
| `app/cbl/CBTRN02C.cbl` | REFERENCE | n/a | Read-only source for `POSTTRAN` rules (transaction posting engine). Extract REJECT codes 100/101/102/103/109, OVERLIMIT formula `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`, expiration check `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)`, TCATBAL composite key with `'23'` create-on-not-found, ACCOUNT cycle update with signed-amount routing, RETURN-CODE = 4 on rejects. |
| `app/cbl/CBTRN03C.cbl` | REFERENCE | n/a | Read-only source for `TRANREPT` rules (transaction detail report). Extract date-window predicate `TRAN-PROC-TS(1:10) >= WS-START-DATE AND <= WS-END-DATE`, card-break detection, page-break logic `MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` with hardcoded page size 20, three-tier totals (page/account/grand). |
| `app/jcl/POSTTRAN.jcl` | REFERENCE | n/a | Read-only source for STEP15 step rule (`EXEC PGM=CBTRN02C`). Extract DD bindings: TRANFILE, DALYTRAN, XREFFILE, DALYREJS(+1), ACCTFILE, TCATBALF. Hardcoded DSN `AWS.M2.CARDDEMO.*` to flag in `Review_Comments`. |
| `app/jcl/INTCALC.jcl` | REFERENCE | n/a | Read-only source for STEP15 step rule (`EXEC PGM=CBACT04C,PARM='2022071800'`). Extract hardcoded PARM date and DD bindings (TCATBALF, XREFFILE, XREFFIL1 alternate-index path, ACCTFILE, DISCGRP, TRANSACT(+1) GDG output). |
| `app/jcl/COMBTRAN.jcl` | REFERENCE | n/a | Read-only source for STEP05R (SORT) and STEP10 (IDCAMS REPRO) rules. Extract sort key TRAN-ID, REPRO into TRANSACT.VSAM.KSDS. |
| `app/jcl/TRANREPT.jcl` | REFERENCE | n/a | Read-only source for STEP05R (REPROC backup), STEP05R (SORT date-window with hardcoded `'2022-01-01'` and `'2022-07-06'`), STEP10R (`EXEC PGM=CBTRN03C`) rules. |
| `app/jcl/CREASTMT.JCL` | REFERENCE | n/a | Read-only source for DELDEF01 (IDCAMS DELETE+DEFINE TRXFL.VSAM.KSDS), STEP010 (SORT), STEP020 (IDCAMS REPRO), STEP030 (IEFBR14 cleanup), STEP040 (`EXEC PGM=CBSTM03A`) rules. |
| `app/jcl/PRTCATBL.jcl` | REFERENCE | n/a | Read-only source for DELDEF (IDCAMS DELETE), STEP05R (REPROC unload), STEP10R (SORT with composite key and `EDIT=(TTTTTTTTT.TT)` mask) rules. |
| `app/jcl/READACCT.jcl` | REFERENCE | n/a | Read-only source for STEP05 (`EXEC PGM=CBACT01C`) and DD ACCTFILE binding. |
| `app/jcl/READCARD.jcl` | REFERENCE | n/a | Read-only source for STEP05 (`EXEC PGM=CBACT02C`) and DD CARDFILE binding. |
| `app/jcl/READCUST.jcl` | REFERENCE | n/a | Read-only source for STEP05 (`EXEC PGM=CBCUS01C`) and DD CUSTFILE binding. |
| `app/jcl/READXREF.jcl` | REFERENCE | n/a | Read-only source for STEP05 (`EXEC PGM=CBACT03C`) and DD XREFFILE binding. |
| `app/cpy/CVACT01Y.cpy` | REFERENCE | n/a | Read-only source for ACCOUNT-RECORD layout (300 bytes). Pipe-delimited field list: `ACCT-ID(PIC 9(11))\|ACCT-ACTIVE-STATUS(PIC X(01))\|ACCT-CURR-BAL(PIC S9(10)V99)\|ACCT-CREDIT-LIMIT(PIC S9(10)V99)\|ACCT-CASH-CREDIT-LIMIT(PIC S9(10)V99)\|ACCT-OPEN-DATE(PIC X(10))\|ACCT-EXPIRAION-DATE(PIC X(10))\|ACCT-REISSUE-DATE(PIC X(10))\|ACCT-CURR-CYC-CREDIT(PIC S9(10)V99)\|ACCT-CURR-CYC-DEBIT(PIC S9(10)V99)\|ACCT-ADDR-ZIP(PIC X(10))\|ACCT-GROUP-ID(PIC X(10))\|FILLER(PIC X(178))`. |
| `app/cpy/CVACT02Y.cpy` | REFERENCE | n/a | Read-only source for CARD-RECORD layout (150 bytes): `CARD-NUM(PIC X(16))\|CARD-ACCT-ID(PIC 9(11))\|CARD-CVV-CD(PIC 9(03))\|CARD-EMBOSSED-NAME(PIC X(50))\|CARD-EXPIRAION-DATE(PIC X(10))\|CARD-ACTIVE-STATUS(PIC X(01))\|FILLER(PIC X(59))`. |
| `app/cpy/CVACT03Y.cpy` | REFERENCE | n/a | Read-only source for CARD-XREF-RECORD layout (50 bytes): `XREF-CARD-NUM(PIC X(16))\|XREF-CUST-ID(PIC 9(09))\|XREF-ACCT-ID(PIC 9(11))\|FILLER(PIC X(14))`. |
| `app/cpy/CVCUS01Y.cpy` | REFERENCE | n/a | Read-only source for CUSTOMER-RECORD layout (500 bytes): full customer schema with FICO-CREDIT-SCORE, EFT-ACCOUNT-ID, GOVT-ISSUED-ID, etc. |
| `app/cpy/CVTRA01Y.cpy` | REFERENCE | n/a | Read-only source for TRAN-CAT-BAL-RECORD layout (50 bytes): composite key `TRANCAT-ACCT-ID(PIC 9(11))\|TRANCAT-TYPE-CD(PIC X(02))\|TRANCAT-CD(PIC 9(04))` + `TRAN-CAT-BAL(PIC S9(09)V99)\|FILLER(PIC X(22))`. |
| `app/cpy/CVTRA02Y.cpy` | REFERENCE | n/a | Read-only source for DIS-GROUP-RECORD layout (50 bytes): composite key `DIS-ACCT-GROUP-ID(PIC X(10))\|DIS-TRAN-TYPE-CD(PIC X(02))\|DIS-TRAN-CAT-CD(PIC 9(04))` + `DIS-INT-RATE(PIC S9(04)V99)\|FILLER(PIC X(28))`. |
| `app/cpy/CVTRA03Y.cpy` | REFERENCE | n/a | Read-only source for TRAN-TYPE-RECORD layout (60 bytes): `TRAN-TYPE(PIC X(02))\|TRAN-TYPE-DESC(PIC X(50))\|FILLER(PIC X(08))`. |
| `app/cpy/CVTRA04Y.cpy` | REFERENCE | n/a | Read-only source for TRAN-CAT-RECORD layout (60 bytes): composite key `TRAN-TYPE-CD(PIC X(02))\|TRAN-CAT-CD(PIC 9(04))` + `TRAN-CAT-TYPE-DESC(PIC X(50))\|FILLER(PIC X(04))`. |
| `app/cpy/CVTRA05Y.cpy` | REFERENCE | n/a | Read-only source for TRAN-RECORD layout (350 bytes): full transaction schema used by CBACT04C/CBTRN01C/CBTRN02C/CBTRN03C. |
| `app/cpy/CVTRA06Y.cpy` | REFERENCE | n/a | Read-only source for DALYTRAN-RECORD layout (350 bytes): identical structure to TRAN-RECORD with DALYTRAN- prefix. |
| `app/cpy/CVTRA07Y.cpy` | REFERENCE | n/a | Read-only source for TRANSACTION-DETAIL-REPORT layout (133 bytes) and report header/footer constants used by CBTRN03C. |
| `app/cpy/COSTM01.CPY` | REFERENCE | n/a | Read-only source for TRNX-RECORD layout (350 bytes) used by CBSTM03A and CBSTM03B for the statement transaction work file. |

### 0.6.2 New Files Detail

#### 0.6.2.1 `docs/bre/CardDemo_Batch_BRE.csv`

- **Content type**: Comma-separated values, RFC 4180 compliant, UTF-8 encoded, LF line endings.
- **Based on**: Static analysis of all 10 batch COBOL programs, 10 batch JCL jobs, and 12 supporting copybooks listed in §0.6.1.
- **Key sections**:
  - Row 1: Header — exactly the 20 column names from the user's prompt: `Rule_Number,Job_Name,Rule_Execution,Program_Type,Module_Name,Sub_Module,Input_File,Input_File_Layout,Input_File_Length,Output_File,Output_File_Layout,Business_Rule_Category,Linkage_Columns,Detailed_Business_Rule,SQL_Decision_Control_Statements,SQL_Function,Code_Reference,Bounded_Context,DB2_Table_Name,Review_Comments`
  - Rows 2 through ~250–350: Data rows, grouped by job in execution-pipeline order (POSTTRAN → INTCALC → COMBTRAN → TRANREPT → CREASTMT → PRTCATBL → READACCT → READCARD → READCUST → READXREF). Within each job, `Rule_Execution` numbers are gapless and ascending, starting at 1.
- **Key functions** (i.e., what each column-position contributes to consumption):
  - `Rule_Number` provides the unique stable identifier used in cross-document tracing
  - `Job_Name` + `Rule_Execution` provide the natural sort key for spreadsheet review
  - `Module_Name` + `Sub_Module` provide the precise source coordinate
  - `Input_File_Layout` + `Output_File_Layout` provide complete record schemas in one column for migration team consumption
  - `Detailed_Business_Rule` is the human-readable rule text
  - `SQL_Decision_Control_Statements` is the verbatim COBOL/JCL evidence
  - `Review_Comments` flags the modernization-risk hotspots

#### 0.6.2.2 `docs/bre/CardDemo_Batch_BRE.md`

- **Content type**: GitHub-flavored Markdown, UTF-8 encoded, LF line endings, renders via MkDocs `mkdocs-techdocs-core`.
- **Based on**: The same row content as `CardDemo_Batch_BRE.csv`, transformed into Markdown table syntax, plus the appended Modernization Mapping section.
- **Key sections**:
  - `# CardDemo Batch Business Rules Extraction` — top-level title
  - `## 1. Overview` — short prose paragraph identifying the source repository, the 10 batch programs in scope, the 10 batch JCL jobs in scope, and the date of extraction
  - `## 2. Business Rules Catalog` — the 20-column GitHub-flavored Markdown table with all data rows. Inside each cell, `|` is escaped as `\|` and `\n` is replaced with `<br/>` for visual readability
  - `## 3. Modernization Mapping`
    - `### 3.1 AWS Glue Mapping` — per-program table mapping COBOL paragraph → Glue construct (DynamicFrame for file I/O, JDBC connection for keyed reads, `DataFrame.orderBy()` for SORT, `boto3` for FTP, PySpark UDF for arithmetic)
    - `### 3.2 Java ECS Batch (Spring Batch) Mapping` — per-program table mapping COBOL paragraph → Spring Batch component (`Step`, `Tasklet`, `FlatFileItemReader`, `FlatFileItemWriter`, `JdbcCursorItemReader`, `JdbcBatchItemWriter`, `SkipPolicy`, `RetryPolicy`)
    - `### 3.3 Top 5 Modernization Risks` — ordered HIGH → LOW with severity badge, description, affected programs, mitigation strategy
- **Key functions**:
  - The Markdown rendering is the human-readable presentation surface for stakeholders who do not work in spreadsheets
  - The Modernization Mapping section is the bridge between extracted rules and the migration team's implementation backlog

### 0.6.3 Files to Modify Detail

**No files are modified.** The BRE deliverable is purely additive. The Blitzy platform creates two new files inside one new folder; nothing in `app/cbl/`, `app/cpy/`, `app/jcl/`, `app/bms/`, `app/cpy-bms/`, `app/data/`, `app/catlg/`, `samples/`, or any of the existing `docs/*.md` files is touched.

### 0.6.4 Configuration and Documentation Updates

- **Configuration changes**: None required. `mkdocs.yml` does not require a `nav:` entry update because MkDocs auto-discovers Markdown files under `docs/`. If the project owner later prefers an explicit nav entry, it can be added optionally as a follow-up; this BRE deliverable does not depend on it.
- **Documentation updates**: None required to existing files. The two new files are themselves the documentation update; they sit alongside the existing `docs/index.md`, `docs/project-guide.md`, and `docs/technical-specifications.md` without overlapping their content.
- **Cross-references to update**: None mandatory. The `docs/technical-specifications.md` could optionally cite the new BRE artifact in a future revision, but updating it is OUT OF SCOPE for this deliverable.

### 0.6.5 Cross-File Dependencies

- **Import / reference updates required**: None. No COBOL `COPY` statement, JCL `INCLUDE` member, or Java/Python import changes.
- **Configuration sync requirements**: None.
- **Documentation consistency needs**: The two BRE artifacts (CSV and MD) must contain identical row data. The Blitzy platform will author them from a single in-memory rule list to guarantee row-by-row correspondence; row count, `Rule_Number` values, and column values must match exactly between the two files. Only the appended Modernization Mapping section differs (present in MD, replicated as plain-text trailer after the CSV per user direction).

## 0.7 Rules

### 0.7.1 Task-Specific Rules

The following rules are emphasized verbatim by the user in the BRE prompt or are direct technical implications the Blitzy platform must enforce when generating the deliverable. They are listed in priority order and must be honored without exception.

- **Rule R-1: CSV Column Order Is Fixed** — The CSV header row must contain exactly these 20 column names in this exact left-to-right order, with no additional columns, no renamed columns, and no removed columns: `Rule_Number, Job_Name, Rule_Execution, Program_Type, Module_Name, Sub_Module, Input_File, Input_File_Layout, Input_File_Length, Output_File, Output_File_Layout, Business_Rule_Category, Linkage_Columns, Detailed_Business_Rule, SQL_Decision_Control_Statements, SQL_Function, Code_Reference, Bounded_Context, DB2_Table_Name, Review_Comments`.
- **Rule R-2: Rule Number Format Is Strict** — `Rule_Number` MUST follow the pattern `BR-<JOBNAME>-NNN` where `<JOBNAME>` is the JCL job name in uppercase (e.g., `POSTTRAN`, `INTCALC`, `COMBTRAN`, `TRANREPT`, `CREASTMT`, `PRTCATBL`, `READACCT`, `READCARD`, `READCUST`, `READXREF`) and `NNN` is a three-digit zero-padded sequential number starting from `001`. Each job's numbering restarts at `001`.
- **Rule R-3: Rule Execution Numbers Are Gapless and In True Order** — Within each job, `Rule_Execution` is `1, 2, 3, …` with no gaps and reflecting the actual order in which the JCL step or COBOL paragraph would execute at runtime, as determined by JCL `EXEC` order, COBOL `PERFORM` order, and `EVALUATE WHEN` order.
- **Rule R-4: Business_Rule_Category Uses Exactly One of Twelve Values** — Allowed values: `Initialization`, `File-IO`, `Data-Validation`, `Date-Time`, `Cycle-Determination`, `Calculation`, `Sorting`, `Reporting`, `Error-Handling`, `FTP-Distribution`, `Finalization`, `Cleanup`. No other strings are permitted in this column. Use `Initialization` for OPEN paragraphs and counter resets, `File-IO` for READ/WRITE/REWRITE/CLOSE without validation, `Data-Validation` for IF/EVALUATE that determine reject/accept, `Date-Time` for date computations and DB2-format timestamp generation, `Cycle-Determination` for account-group change detection and sequential-key break logic, `Calculation` for COMPUTE arithmetic blocks, `Sorting` for DFSORT and SORT-key composition, `Reporting` for write-to-report-file paragraphs, `Error-Handling` for INVALID KEY paths and abend paragraphs, `FTP-Distribution` for any FTP step, `Finalization` for GOBACK and final return-code logic, `Cleanup` for IDCAMS DELETE and IEFBR14 dataset cleanup.
- **Rule R-5: Program_Type Uses Exactly One of Five Values** — Allowed values: `JCL`, `COBOL`, `DB2-SQL`, `SORT`, `FTP`. JCL is used for JCL job-control rules (EXEC PGM, EXEC PROC, IDCAMS, IEFBR14, IEBGENER); COBOL for paragraphs in `.cbl` / `.CBL` files; DB2-SQL is reserved (no rows of this type are produced because the codebase contains no EXEC SQL); SORT for DFSORT/SORT executions; FTP for FTP utility executions (none in this codebase).
- **Rule R-6: One Rule Per Discrete Decision Point** — Two decisions in the same paragraph MUST NOT be merged into one row even when they are physically adjacent. Each `IF`, `EVALUATE WHEN`, `READ ... INVALID KEY ... NOT INVALID KEY`, `COMPUTE`, `MOVE` that changes data flow, file open/read/write/close that has its own status check, and arithmetic block produces a separate row.
- **Rule R-7: Error-Handling Paragraphs Are NEVER Skipped** — Paragraphs `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`, `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS`, `INIT-*`, `TERMINATION-*`, and any abend or status-display utility paragraph appearing in any in-scope program receives its own rule row in that program's rule list. They carry the highest modernization risk and must be visible in the BRE.
- **Rule R-8: Detailed_Business_Rule Is in Plain Business Language** — 2 to 6 sentences in plain English. Use business terminology (PPO level quantity, monthly interest, cycle credit, current balance, expiration date, account group, disclosure group) NOT COBOL field names. State WHAT the rule does, WHY it matters to the business, and any conditional logic in natural language. Include thresholds, tolerances, and date offsets as actual values (e.g., "transaction is rejected when credit limit is less than the running cycle balance plus the new transaction amount", "interest is computed as category balance times annual disclosure rate divided by 1200", "page header is rewritten every 20 detail lines").
- **Rule R-9: SQL_Decision_Control_Statements Is Verbatim Source** — Copy the exact COBOL condition, COBOL `EVALUATE`/`WHEN` block, COBOL `IF`/`ELSE` block, JCL DD statement, JCL SORT control statement, or IDCAMS control statement directly from the source file. Multi-line statements use the literal two-character separator backslash + lowercase n. Do NOT paraphrase, do NOT abbreviate, do NOT add comments. This column is the audit trail back to the source.
- **Rule R-10: Code_Reference Cites Specific Source Coordinates** — Either a line-number range (e.g., `205-432`) or a paragraph/SECTION/step name (e.g., `2800-UPDATE-ACCOUNT-REC`, `STEP15`, `0550-DATEPARM-READ`). Include the file basename when ambiguity is possible (e.g., `CBTRN02C: 240-265`). Use range format `start-end` with no spaces.
- **Rule R-11: Bounded_Context Is Derived from Domain, Not Code** — Allowed values for this BRE: `Daily Transaction Posting`, `Interest & Fee Calculation`, `Transaction Consolidation`, `Transaction Reporting`, `Statement Generation`, `Category Balance Reporting`, `Account Master Inspection`, `Card Master Inspection`, `Customer Master Inspection`, `Card Cross-Reference Inspection`, `Daily Transaction Validation`. The Blitzy platform may add additional context labels if a JCL step belongs to a clearly distinct domain not yet listed; new labels must be human-readable phrases (not codes).
- **Rule R-12: Review_Comments Always Contains Either "None" or Specific Modernization Risks** — Never blank. Allowable risk callouts include (but are not limited to):
  - `Hardcoded DSN: <name>` (e.g., `AWS.M2.CARDDEMO.LOADLIB`, `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`) — flagged because AWS Glue requires a connection parameter or job-config entry instead
  - `Hardcoded date literal: <value>` (e.g., `'2022071800'` PARM in INTCALC; `'2022-01-01'` / `'2022-07-06'` in TRANREPT SORT) — flagged because dates must come from a job parameter or config table
  - `Silent failure: CONTINUE after non-zero status` — flagged because `'00' OR '23'` accept patterns mask legitimate errors
  - `GO TO statement present` — flagged because Java has no equivalent and control flow must be restructured
  - `ALTER statement present` — flagged because dynamic GO TO retargeting is dead-on-arrival in modern Java; HIGH severity
  - `Packed-decimal COMP-3 arithmetic` — flagged for BigDecimal mapping (Java) or Decimal type (Spark)
  - `SORT utility step` — flagged for DataFrame.orderBy() or Java Comparator mapping
  - `FTP step` — flagged for S3 PUT / AWS DataSync replacement (none present in this codebase)
  - `GDG / TAPE reference: <name>` — flagged for S3-path replacement (e.g., `(+1)`, `(0)`, `SYSTRAN`, `TRANSACT.BKUP`, `DALYREJS`, `TRANREPT`)
  - `Multi-program CALL chain: <chain>` — flagged for microservice or Step Function boundary identification (e.g., CBSTM03A → CBSTM03B)
  - `Copy-paste defect: <description>` — for known double-DISPLAY in CBACT02C/CBACT03C/CBCUS01C, type-mismatch FD-CUST-ID PIC X(09) vs PIC 9(09)
  - `Dead code: <paragraph>` — for empty stubs like `1400-COMPUTE-FEES` in CBACT04C
  - `Hardcoded threshold: <value>` (e.g., `WS-PAGE-SIZE VALUE 20`, `OCCURS 51 TIMES`, `OCCURS 10 TIMES`)
  - `Hardcoded magic value: <value>` (e.g., `TRAN-TYPE-CD = '01'`, `TRAN-CAT-CD = '05'`, `TRAN-SOURCE = 'System'` in CBACT04C interest transactions)
  - `Inconsistent status accept: '00' or '23'` — for TCATBAL and DISCGRP reads that treat record-not-found as success
  - When a rule has no risk, set `Review_Comments` to `None` (capitalized N).
- **Rule R-13: Linkage_Columns Lists Business Keys** — Comma-separated list of business key fields linking input → output records (e.g., `DALYTRAN-CARD-NUM → XREF-CARD-NUM`, `XREF-ACCT-ID → ACCT-ID`, `XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD → TCATBAL composite key`). Use `N/A` when not applicable (e.g., for OPEN/CLOSE paragraphs, abend handlers).
- **Rule R-14: Input_File and Output_File Use DD Names or COBOL SELECT Names** — Use the exact DD name as written in the JCL (e.g., `DALYTRAN`, `TRANFILE`, `XREFFILE`, `ACCTFILE`, `TCATBALF`, `DALYREJS`, `XREFFIL1`, `DISCGRP`, `TRANSACT`, `TRANREPT`, `TRNXFILE`, `STMTFILE`, `HTMLFILE`, `DATEPARM`, `TRANTYPE`, `TRANCATG`, `CARDXREF`) or the COBOL `SELECT` clause's external name. Use `N/A` when no input/output file applies (e.g., for COMPUTE-only paragraphs).
- **Rule R-15: Input_File_Length and Output_File_Length Are in Bytes** — Computed from the FD/SD/01 record definition's PIC clauses, accounting for COMP-3 packed-decimal compression (one nibble per digit + sign nibble, rounded up to whole bytes), COMP/BINARY (2/4/8 bytes per range), and zoned/alphanumeric (n bytes per `PIC X(n)` or `PIC 9(n)`). Use `N/A` when no input/output file applies.
- **Rule R-16: DB2_Table_Name Is N/A for This Codebase** — After complete inspection of all 10 in-scope batch programs, the Blitzy platform has confirmed that no `EXEC SQL` block exists. Every row's `DB2_Table_Name` is `N/A`. Migration consumers must NOT interpret blank or empty values; they must see `N/A` as an explicit signal.
- **Rule R-17: SQL_Function Is the COBOL Intrinsic When Present** — Use values `FUNCTION CURRENT-DATE`, `FUNCTION MOD`, `FUNCTION LENGTH`, `FUNCTION TRIM` when those intrinsics appear in the source. Use `N/A` otherwise.
- **Rule R-18: Validate Completeness Before Output** — Before emitting either deliverable, the Blitzy platform's analyzer must self-check:
  - Every JCL step in every in-scope JCL job has at least one rule row
  - Every COBOL program's PROCEDURE DIVISION has been fully traversed (every paragraph reachable from MAIN-PARA / 0000-START / the program-level entry has at least one rule row)
  - No paragraph is skipped — INIT, TERMINATION, ABEND paragraphs included
  - Every non-trivial IF / EVALUATE has its own row
  - `Rule_Execution` numbers within each job are gapless `1, 2, 3, …`
  - `Rule_Number` is sequential per job with three-digit zero-padding
  - The MD file's table contains the same row count as the CSV
  - The Modernization Mapping section is present at the end of the MD file
- **Rule R-19: Match Existing Code Style and Conventions** — All Markdown headings use sentence-case as in `docs/technical-specifications.md`; all tables use GitHub-flavored Markdown with leading and trailing pipes; all code blocks use triple-backtick fences with language hints (cobol, jcl, mermaid). LF line endings, UTF-8 encoding, no BOM. The artifact is consistent with existing `docs/*.md` style.
- **Rule R-20: Backward Compatibility With Existing Documentation** — The deliverable does not break or alter the existing `docs/index.md`, `docs/project-guide.md`, `docs/technical-specifications.md` files. The MkDocs site continues to build successfully; the new BRE artifact appears as a sibling under `docs/bre/`. The `mkdocs.yml` `nav:` entry is NOT modified by this deliverable; auto-discovery handles the new file.
- **Rule R-21: Do NOT Modify Existing COBOL/JCL/Copybook Sources** — All `.cbl`, `.CBL`, `.cpy`, `.CPY`, `.jcl`, `.JCL` files are read-only inputs. The Blitzy platform must NOT touch them, NOT reformat them, NOT add comments, NOT rename, NOT relocate. The BRE deliverable is purely additive documentation.
- **Rule R-22: Do NOT Generate CICS Online Rules** — The user explicitly stated "all the COBOL Batch Jobs not CICS". Programs with the `CO`-prefix (CICS Online) in `app/cbl/`, all BMS map files in `app/bms/`, and all CICS administration JCL (`CBADMCDJ.jcl`, `OPENFIL.jcl`, `CLOSEFIL.jcl`) are excluded.
- **Rule R-23: Preserve User Examples Verbatim** — Where the user provided example rule formats in the prompt (e.g., `BR-[JOBNAME]-001`, "within 5 business days", "if quantity exceeds 999", `WS-PPO-LEVEL-QTY → "PPO level quantity"`), the patterns are applied without alteration. The literal `BR-` prefix, three-digit zero-padding, and JOBNAME-uppercase convention all flow from the user's example.
- **Rule R-24: Modernization Mapping Section Format Is Mandatory** — The Modernization Mapping section appended to the MD file must contain three numbered subsections in this order: `### 3.1 AWS Glue Mapping`, `### 3.2 Java ECS Batch Mapping`, `### 3.3 Top 5 Modernization Risks`. The mapping must cover every COBOL program in scope and every JCL step type (EXEC PGM, EXEC PROC, IDCAMS, SORT, IEFBR14, IEBGENER). The Top 5 Risks list must be ordered by severity HIGH → LOW with severity badges.
- **Rule R-25: CSV Trailer Mirror of Modernization Mapping** — Per the user's directive "after the CSV produce a short Modernization Mapping section in plain text", the CSV file may contain a plain-text trailer after the last data row, separated by a single blank line, that mirrors the Modernization Mapping content as plain text (no Markdown headings, no tables — paragraphs and bulleted lists only). This trailer is NOT part of the CSV row data and is not parsed by `csv.reader`. The MD file is the canonical home for the Markdown-formatted mapping.

## 0.8 Special Instructions

### 0.8.1 Special Execution Instructions

The following process-specific requirements govern execution. They do not override Rules R-1 through R-25 in §0.7; they supplement them with execution-time guidance.

- **Documentation-only deliverable**: The user has explicitly framed this task as a Business Rules Extraction document. The output is two static documentation files. The Blitzy platform must NOT generate Spring Batch boilerplate, Glue PySpark scripts, Terraform, CloudFormation, Java code, Python code, or any other executable artifact as a side effect. Only the two BRE deliverables (`docs/bre/CardDemo_Batch_BRE.csv` and `docs/bre/CardDemo_Batch_BRE.md`) are produced.
- **No build, no test, no deployment**: This deliverable does not require running a build, executing a test suite, or deploying anything. There is no CI configuration to update, no Dockerfile to author, and no Kubernetes manifest to write. The MkDocs site rebuild that picks up the new files happens automatically when the documentation site is next published; no manual trigger is required from the Blitzy platform.
- **Static analysis only — no execution of legacy code**: The COBOL programs and JCL jobs are NOT compiled or executed during BRE generation. The Blitzy platform reads the source files, parses their structure, and emits the BRE deliverable. No mainframe simulator, COBOL compiler, JES emulator, or VSAM file-system tooling is required.
- **No interactive prompts**: All bash invocations during generation are non-interactive. Where the Blitzy platform shells out to read files or compute byte offsets via `python3` / `grep` / `awk` / `sed`, those invocations complete without user input.
- **Tools and platforms specifically excluded from output generation**:
  - No Spring Boot project skeleton is created (`pom.xml`, `application.yml`, etc.)
  - No PySpark script is created (no `.py` file under `glue/` or `etl/`)
  - No Terraform / CloudFormation file is created
  - No GitHub Actions workflow is created or updated
  - No Dockerfile is created
  - No PostgreSQL DDL file is created
- **Tools and platforms specifically REFERENCED in the Modernization Mapping section** (informational only — not generated):
  - AWS Glue 4.0 / 5.0 with PySpark for batch ETL replacement
  - AWS S3 as the replacement for VSAM KSDS and PS sequential datasets
  - AWS Step Functions for multi-program JCL CALL chain replacement
  - Spring Batch 5.x as the Java ECS batch framework
  - Spring Boot 3.5.11 (per the existing `docs/technical-specifications.md` migration target)
  - Java 25 LTS `java.math.BigDecimal` for COMP-3 packed-decimal arithmetic replacement
  - PostgreSQL 16 as the relational target for VSAM KSDS migration
  - LocalStack for local AWS-service emulation during development
- **Quality requirement: spreadsheet round-trip**: Before declaring the deliverable complete, the generated CSV must be opened by a CSV parser (`csv.reader` in Python's standard library) and the row count and column count per row must be verified. Each row must contain exactly 20 fields. Unbalanced quoting, embedded raw newlines (vs the literal `\n` in `SQL_Decision_Control_Statements`), and unescaped commas in `Detailed_Business_Rule` are forbidden.
- **Quality requirement: Markdown table integrity**: The MD file's table block must have a header row, a separator row of dashes, and N data rows where N matches the CSV's data-row count. Cell pipe-character escaping uses `\|` and embedded newline literals are replaced with `<br/>`.
- **Code review requirements**: The deliverable is documentation, not source code. The reviewer's checklist consists of (a) verifying CSV opens cleanly in a spreadsheet and `csv.reader`, (b) verifying the MD file renders correctly via MkDocs build, (c) sampling ~10 rule rows and cross-checking their `Code_Reference` against the source COBOL/JCL, (d) confirming the Modernization Mapping section covers all 10 programs.
- **Deployment / rollout considerations**: None. The deliverable is committed to the repository like any other documentation file; downstream consumers (migration team, Backstage TechDocs site) automatically pick up the new files on the next build.

### 0.8.2 Constraints and Boundaries

- **Technical constraints specified by the user**:
  - The CSV must have exactly 20 columns in the prescribed order
  - The CSV header row must be the first row
  - `Rule_Number` must follow `BR-[JOBNAME]-NNN` format with three-digit zero-padding
  - `Rule_Execution` must be a numeric sequence starting from 1, gapless, in true execution order
  - `Business_Rule_Category` must use one of the twelve enumerated values
  - `Program_Type` must use one of the five enumerated values (`JCL`, `COBOL`, `DB2-SQL`, `SORT`, `FTP`)
  - `Detailed_Business_Rule` must be 2 to 6 sentences in plain business language
  - `SQL_Decision_Control_Statements` must be verbatim source with `\n` separator for multi-line
  - `Code_Reference` must be a line-number range OR a paragraph name
  - `Bounded_Context` must be a human-readable domain phrase
  - `Review_Comments` must contain either `None` or specific modernization-risk callouts
- **Process constraints (what should be done)**:
  - Inventory every JCL step (`EXEC PGM=`, `EXEC PROC=`) in execution order
  - Note every SECTION and paragraph in every COBOL program invoked
  - Map every DD statement to its COBOL `SELECT`/`ASSIGN` or SQL table reference
  - Extract one rule per discrete decision point: each JCL step (DELETE/ALLOC/SORT/FTP/EXEC) as its own rule, each COBOL paragraph or SECTION containing business logic, each `IF` / `EVALUATE` / `WHEN` branch that changes data flow, each `READ` / `WRITE` / `REWRITE` / `CLOSE` with its validation check, each arithmetic `COMPUTE` or calculation block, each date or cycle-type determination, each error-handling block, each record transformation
  - Derive file layouts from COBOL DATA DIVISION FD/SD/01 entries, formatted as `FIELD-NAME(PIC clause)` with `|` separator
  - Calculate record length from PIC clauses; account for COMP / COMP-3 packed lengths
  - Translate field names to business terms in `Detailed_Business_Rule` only
  - Flag modernization risks in `Review_Comments` per the user's specific risk catalog
  - Validate completeness before output
- **Process constraints (what should NOT be done)**:
  - Do NOT merge two decisions into one row even when in the same paragraph
  - Do NOT skip error-handling paragraphs
  - Do NOT modify any existing COBOL, JCL, copybook, BMS, or documentation file
  - Do NOT create any executable artifact (no Java, Python, PySpark, Terraform, Dockerfile, etc.) as a side effect of BRE generation
  - Do NOT generate rules for CICS online programs (CO-prefix), BMS maps, or CICS admin JCL
  - Do NOT use placeholder versions like `latest` or `1.0.0` — the BRE has no runtime dependencies
  - Do NOT include CICS-specific business logic such as EXEC CICS verbs in the BRE
- **Output constraints (what should be generated)**:
  - Exactly two new files: `docs/bre/CardDemo_Batch_BRE.csv` and `docs/bre/CardDemo_Batch_BRE.md`
  - Exactly one new folder: `docs/bre/`
  - Both files contain identical row data; only the MD file contains the Markdown-formatted Modernization Mapping (the CSV may contain it as a plain-text trailer)
- **Output constraints (what should NOT be generated)**:
  - No additional documentation files beyond the two specified
  - No edits to `mkdocs.yml`, `catalog-info.yaml`, `README.md`, `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, or any other root-level meta-file
  - No edits to `docs/index.md`, `docs/project-guide.md`, or `docs/technical-specifications.md`
- **Timeline or dependency constraints**: The deliverable is a single-pass static analysis output. There is no upstream dependency that must complete first. There is no downstream task that gates on this deliverable's completion within this scope; the migration team's Spring Batch authoring is downstream but proceeds independently after the BRE is delivered.
- **Compatibility requirements**:
  - The generated CSV must be readable by Python `csv.reader`, Microsoft Excel, LibreOffice Calc, and Google Sheets
  - The generated MD file must render correctly via MkDocs `mkdocs-techdocs-core` and via direct GitHub web view
  - File encoding is UTF-8 with no BOM, line endings are LF, and no Windows CRLF or Mac CR is permitted

## 0.9 References

### 0.9.1 Files Searched and Retrieved Across the Codebase

The Blitzy platform conducted exhaustive repository inspection to derive the conclusions in §§0.1 – 0.8. Every file listed below was retrieved using `read_file` or `get_source_folder_contents` (or its `get_file_summary` companion) and analyzed line by line where the file body is required for rule extraction.

#### 0.9.1.1 Repository Folders Inspected (Folder-Level)

| Folder Path | Tool Used | Purpose |
|-------------|-----------|---------|
| `` (repository root) | `get_source_folder_contents` | Top-level inventory; identified `app/`, `docs/`, `samples/`, `mkdocs.yml`, `catalog-info.yaml`, `README.md`, `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` |
| `app` | `get_source_folder_contents` | Application root inventory; identified `app/cbl`, `app/cpy`, `app/jcl`, `app/bms`, `app/cpy-bms`, `app/data`, `app/catlg` |
| `app/cbl` | `get_source_folder_contents` | Listed all 28 COBOL programs; categorized 10 batch (CB-prefix) vs 18 online (CO-prefix) |
| `app/cpy` | `get_source_folder_contents` | Listed all 28 copybooks; identified 12 in-scope copybooks for batch programs |
| `app/jcl` | `get_source_folder_contents` | Listed all 29 JCL jobs; categorized 10 in-scope batch + 19 out-of-scope (provisioning, GDG, CICS admin) |
| `docs` | `get_source_folder_contents` | Listed existing documentation: `index.md`, `project-guide.md`, `technical-specifications.md` |

#### 0.9.1.2 COBOL Batch Programs Read (File-Level, Full or Partial Content)

| File Path | Read Mode | Key Findings |
|-----------|-----------|--------------|
| `app/cbl/CBACT01C.cbl` | Full read (194 lines) | Account master read utility — `0000-CARDFILE-OPEN` (mis-named, opens ACCTFILE), `1000-ACCTFILE-GET-NEXT`, `1100-DISPLAY-ACCT-RECORD`, `9000-ACCTFILE-CLOSE`, `9999-ABEND-PROGRAM` |
| `app/cbl/CBACT02C.cbl` | Summary | Card master read utility — sequential CARDFILE traversal; double-DISPLAY defect (record displayed inside read paragraph AND in caller loop) |
| `app/cbl/CBACT03C.cbl` | Summary | XREF master read utility — sequential XREFFILE traversal; same double-DISPLAY defect |
| `app/cbl/CBACT04C.cbl` | Full read (lines 1-250, 250-600, 600-end) | Interest calculator — group-change detection, DISCGRP DEFAULT fallback, monthly interest formula `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`, hardcoded `'01'`/`'05'`/`'System'`, cycle reset, empty `1400-COMPUTE-FEES` stub, DB2-format timestamp via `Z-GET-DB2-FORMAT-TIMESTAMP` |
| `app/cbl/CBCUS01C.cbl` | Summary | Customer master read utility — sequential CUSTFILE traversal; `Z-DISPLAY-IO-STATUS`/`Z-ABEND-PROGRAM` paragraph naming convention; same double-DISPLAY defect |
| `app/cbl/CBSTM03A.CBL` | Full read (lines 1-250, 250-end) | Statement creator — TIOT inspection, ALTER+GO TO dispatch, `EVALUATE WS-FL-DD`, buffered `WS-TRNX-TABLE OCCURS 51 × 10`, card-break detection, statement text + HTML writes |
| `app/cbl/CBSTM03B.CBL` | Full read (231 lines) | Callable file-service subroutine — `EVALUATE LK-M03B-DD` dispatch for TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE; `FD-CUST-ID PIC X(09)` type mismatch with CVCUS01Y's `PIC 9(09)` |
| `app/cbl/CBTRN01C.cbl` | Full read (492 lines) | Daily transaction validation driver — reads DALYTRAN sequentially, looks up XREF and ACCOUNT, displays diagnostics; despite "post" in header it does NOT post writes |
| `app/cbl/CBTRN02C.cbl` | Full read (732 lines) | Posting engine — reject codes 100/101/102/103/109, OVERLIMIT formula, expiration check, TCATBAL composite-key create-on-not-found, ACCOUNT cycle update with signed-amount routing, RETURN-CODE = 4 on rejects |
| `app/cbl/CBTRN03C.cbl` | Full read (650 lines) | Transaction detail report — date-window predicate using `TRAN-PROC-TS(1:10)`, card-break detection, page-break logic via `FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)`, three-tier totals (page/account/grand) |

#### 0.9.1.3 Batch JCL Jobs Read (File-Level, Full Content)

| File Path | Key Findings |
|-----------|--------------|
| `app/jcl/POSTTRAN.jcl` | STEP15 `EXEC PGM=CBTRN02C`; DD bindings: TRANFILE → `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS`; DALYTRAN → `AWS.M2.CARDDEMO.DALYTRAN.PS`; XREFFILE → `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`; DALYREJS → `AWS.M2.CARDDEMO.DALYREJS(+1)` (NEW,CATLG; LRECL=430); ACCTFILE → `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`; TCATBALF → `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` |
| `app/jcl/INTCALC.jcl` | STEP15 `EXEC PGM=CBACT04C,PARM='2022071800'`; hardcoded PARM date; DD bindings: TCATBALF, XREFFILE, XREFFIL1 (AIX path), ACCTFILE, DISCGRP, TRANSACT(+1) GDG output |
| `app/jcl/COMBTRAN.jcl` | STEP05R `EXEC PGM=SORT` (TRANSACT.BKUP(0)+SYSTRAN(0) → TRANSACT.COMBINED(+1) by TRAN-ID); STEP10 `EXEC PGM=IDCAMS` REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM) into TRANSACT.VSAM.KSDS |
| `app/jcl/TRANREPT.jcl` | 3 steps: STEP05R PROC=REPROC unloads TRANSACT.VSAM.KSDS to TRANSACT.BKUP(+1); STEP05R `EXEC PGM=SORT` filters by `PARM-START-DATE='2022-01-01'` to `PARM-END-DATE='2022-07-06'` (HARDCODED); STEP10R `EXEC PGM=CBTRN03C` produces TRANREPT(+1) with inputs TRANFILE, CARDXREF, TRANTYPE, TRANCATG, DATEPARM |
| `app/jcl/CREASTMT.JCL` | 4 steps: DELDEF01 IDCAMS DELETE+DEFINE TRXFL.VSAM.KSDS (KEYS(32 0) RECORDSIZE(350 350)); STEP010 SORT by card+id; STEP020 IDCAMS REPRO; STEP030 IEFBR14 cleanup; STEP040 `EXEC PGM=CBSTM03A` writing STMTFILE.PS + STMTFILE.HTML |
| `app/jcl/PRTCATBL.jcl` | 3 steps: DELDEF (delete prior REPT), STEP05R REPROC unload, STEP10R SORT by TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD with OUTREC `EDIT=(TTTTTTTTT.TT)` mask |
| `app/jcl/READACCT.jcl` | STEP05 `EXEC PGM=CBACT01C`, ACCTFILE DD = ACCTDATA.VSAM.KSDS |
| `app/jcl/READCARD.jcl` | STEP05 `EXEC PGM=CBACT02C`, CARDFILE DD = CARDDATA.VSAM.KSDS |
| `app/jcl/READCUST.jcl` | STEP05 `EXEC PGM=CBCUS01C`, CUSTFILE DD = CUSTDATA.VSAM.KSDS |
| `app/jcl/READXREF.jcl` | STEP05 `EXEC PGM=CBACT03C`, XREFFILE DD = CARDXREF.VSAM.KSDS |

#### 0.9.1.4 Copybooks Read (File-Level, Full Content)

| File Path | Record Name | Length | Used By |
|-----------|-------------|--------|---------|
| `app/cpy/CVACT01Y.cpy` | ACCOUNT-RECORD | 300 | CBACT01C, CBACT04C, CBTRN01C, CBTRN02C, CBSTM03A |
| `app/cpy/CVACT02Y.cpy` | CARD-RECORD | 150 | CBACT02C, CBTRN01C |
| `app/cpy/CVACT03Y.cpy` | CARD-XREF-RECORD | 50 | CBACT03C, CBACT04C, CBTRN01C, CBTRN02C, CBTRN03C, CBSTM03A |
| `app/cpy/CVCUS01Y.cpy` | CUSTOMER-RECORD | 500 | CBCUS01C, CBTRN01C |
| `app/cpy/CVTRA01Y.cpy` | TRAN-CAT-BAL-RECORD | 50 | CBACT04C, CBTRN02C |
| `app/cpy/CVTRA02Y.cpy` | DIS-GROUP-RECORD | 50 | CBACT04C |
| `app/cpy/CVTRA03Y.cpy` | TRAN-TYPE-RECORD | 60 | CBTRN03C |
| `app/cpy/CVTRA04Y.cpy` | TRAN-CAT-RECORD | 60 | CBTRN03C |
| `app/cpy/CVTRA05Y.cpy` | TRAN-RECORD | 350 | CBACT04C, CBTRN01C, CBTRN02C, CBTRN03C |
| `app/cpy/CVTRA06Y.cpy` | DALYTRAN-RECORD | 350 | CBTRN01C, CBTRN02C |
| `app/cpy/CVTRA07Y.cpy` | TRANSACTION-DETAIL-REPORT and report headers/totals | 133 | CBTRN03C |
| `app/cpy/COSTM01.CPY` | TRNX-RECORD | 350 | CBSTM03A, CBSTM03B |

#### 0.9.1.5 Documentation and Configuration Files Inspected

| File Path | Inspection | Purpose |
|-----------|------------|---------|
| `docs/index.md` | listed via folder inspection | Confirmed Java 25 + Spring Boot 3.x migration framing |
| `docs/project-guide.md` | listed via folder inspection | Confirmed status and operational narrative; not modified |
| `docs/technical-specifications.md` | listed via folder inspection | Confirmed Spring Data JPA, Spring Batch, PostgreSQL 16+, LocalStack technical migration blueprint |
| `mkdocs.yml` (referenced via folder summary) | folder-summary inspection | Confirmed `techdocs-core` and `mermaid2` plugins; auto-discovery applies to new `.md` files |
| `catalog-info.yaml` | folder-summary inspection | Confirmed Backstage catalog entity descriptor |
| `README.md` | folder-summary inspection | Confirmed CardDemo identity as mainframe credit-card sample using COBOL/CICS/VSAM/JCL/RACF |

#### 0.9.1.6 Technical Specification Sections Retrieved

| Section Heading | Tool Used | Purpose |
|-----------------|-----------|---------|
| `1.1 Executive Summary` | `get_tech_spec_section` | Confirmed scope of 28 COBOL programs (10 batch + 18 online), 28 copybooks, 17 BMS mapsets, 29 JCL jobs; 5-stage batch pipeline order POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT |

### 0.9.2 User Attachments

The user did not provide any attachments for this BRE deliverable. The directory `/tmp/environments_files/` was inspected by the Blitzy platform during setup and confirmed to contain no files. The user's prompt is fully self-contained and references only the source code in the AWS CardDemo repository.

| Attachment | Status | Summary |
|------------|--------|---------|
| (none) | n/a | The user provided 0 attachments. All source material is the in-repository COBOL/JCL/copybook code listed in §§0.9.1.2 – 0.9.1.4. |

### 0.9.3 Figma Designs

No Figma designs were referenced in the user's BRE prompt, and no Figma URLs are present in the project context. The BRE deliverable contains only tabular data (CSV) and Markdown text; it does not require visual design assets.

| Figma Frame | Frame URL | Description |
|-------------|-----------|-------------|
| (none) | n/a | No Figma references apply to this Business Rules Extraction deliverable. The deliverable is text-only documentation. |

### 0.9.4 External Documentation Referenced for Modernization Mapping

The Modernization Mapping section appended to the MD deliverable will cite the following well-established public documentation surfaces. These are background references for the migration team; none are required during BRE generation itself:

- AWS Glue developer guide — DynamicFrame API, JDBC connection options, S3 path conventions
- Spring Batch 5.x reference — `Step`, `Tasklet`, `FlatFileItemReader`, `JdbcCursorItemReader`, `SkipPolicy`, `RetryPolicy`
- Java 25 LTS API — `java.math.BigDecimal` with `MathContext` for COMP-3 arithmetic replacement
- AWS Step Functions developer guide — state-machine task transitions for multi-program JCL chain replacement
- AWS Transfer Family / DataSync documentation — S3-PUT replacement for legacy FTP
- PostgreSQL 16 documentation — equivalent indexing for VSAM KSDS primary and alternate keys

The Blitzy platform may use targeted web searches during MD generation to verify the latest stable patterns and embed citations inline within the Modernization Mapping subsections.

