# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Documentation Objective

Based on the provided requirements, the Blitzy platform understands that the documentation objective is to **deliver comprehensive module-level README documentation and enhanced inline code comments across the entire AWS CardDemo mainframe COBOL/CICS application repository — encompassing all 28 COBOL programs, 28 copybooks, 17 BMS mapsets, 17 symbolic map copybooks, 29 JCL jobs, 9 ASCII data fixture files, 3 sample JCL wrappers, and 1 catalog inventory report — without modifying any production code logic or behavior.**

- **Documentation Category:** Create new documentation + Fix documentation gaps + Improve documentation coverage
- **Documentation Type:** Module-level README files (Markdown), enhanced inline code comments (COBOL comments, JCL comments, BMS comments), and architectural overview documentation
- **Repository:** `blitzy-card-demo` — AWS CardDemo COBOL mainframe application, source commit SHA `27d6c6f` from `aws-samples/aws-mainframe-modernization-carddemo`, currently used as the reference codebase for a Java 25 + Spring Boot 3.x migration project
- **Minimal Change Clause:** All changes are strictly additive documentation — no refactoring, no optimization, no interface changes, no logic modifications

**Documentation Requirements (Enhanced Clarity):**

- Create README.md files for every module directory under `app/` (cbl, cpy, bms, cpy-bms, jcl, data, data/ASCII, catlg), the `samples/jcl/` directory, and the root `app/` directory itself
- Each module README must cover: module purpose, key components inventory with brief descriptions, architectural fit within the CardDemo system, dependencies and cross-references to other modules, use cases and data flows, design patterns observed, configuration context, and known limitations
- Add inline comments to all COBOL programs (`app/cbl/*.cbl`), copybooks (`app/cpy/*.cpy`), BMS maps (`app/bms/*.bms`), JCL jobs (`app/jcl/*.jcl`), and sample JCL (`samples/jcl/*.jcl`) explaining what the code does, why specific design choices were made, and how components interact
- Follow COBOL inline comment conventions (column 7 asterisk for full-line comments) for all `.cbl` and `.cpy` files
- Follow JCL comment conventions (`//*` prefix) for all `.jcl` files
- Follow BMS comment conventions (asterisk in column 1) for all `.bms` files
- Preserve existing Apache 2.0 license headers exactly as-is in all files
- Update the root `README.md` only if documentation gaps are identified (current README already has substantial content)
- Update `mkdocs.yml` navigation if new documentation pages are added to `docs/`

**Implicit Documentation Needs Surfaced:**

- The COBOL programs currently have minimal header comments (program name, application, type, function) but lack explanatory inline comments for business logic, CICS interaction patterns, VSAM I/O operations, and error handling flows
- The copybooks have no documentation beyond license headers — they need explanations of record layouts, field semantics, byte-level structure, and cross-program usage patterns
- The BMS maps lack documentation of screen layouts, field geometries, validation rules, and CICS map attributes
- The JCL jobs have inconsistent commentary — some have descriptive headers (POSTTRAN, CREASTMT) while others have none
- No module-level README files exist anywhere in the `app/` or `samples/` directory trees
- The `docs/` folder lacks a module-level README explaining its role as the documentation hub and its relationship to the MkDocs/Backstage TechDocs pipeline
- The `app/data/` and `app/data/ASCII/` directories lack READMEs documenting the file formats, record structures, and usage context of the fixture data

### 0.1.2 Special Instructions and Constraints

- **Minimal Change Clause:** "Make only the changes absolutely necessary to implement comprehensive code documentation. Add comments and documentation without modifying production code logic or behavior. Do not refactor, optimize, or change existing interfaces. Document existing code as-is."
- **Existing Style Adherence:** Follow existing COBOL comment conventions observed in the repository — asterisks in column 7 for comment lines, inline comments after code on the same line using continuation patterns
- **License Preservation:** All existing Apache 2.0 license headers must remain intact; new comments are added below or after license blocks
- **Markdown Format:** All README files use standard GitHub-flavored Markdown with Mermaid diagram support (consistent with `mkdocs.yml` configuration)
- **No Code Duplication:** Documentation should reference source files and line numbers rather than duplicating large code blocks
- **Implementation Rule — Build Verify:** Per the "Build Verify" global standard, every module/component must have a README or docstring explaining what it does, how to run/build/test, key configs and defaults, and common failure modes and troubleshooting

### 0.1.3 Technical Interpretation

These documentation requirements translate to the following technical documentation strategy:

- To **document the COBOL programs module**, we will create `app/cbl/README.md` providing a comprehensive catalog of all 28 programs (18 online + 10 batch), their CICS transaction mappings, BMS map dependencies, VSAM file access patterns, and inter-program call chains — then add inline COBOL comments to each `.cbl` file explaining paragraph-level logic, CICS API calls, VSAM I/O operations, and error handling paths
- To **document the copybook module**, we will create `app/cpy/README.md` cataloging all 28 copybooks by function (record layouts, navigation contracts, screen text, validation tables, CICS helpers, report formats) — then add inline comments to each `.cpy` file explaining field semantics, byte offsets, 88-level conditions, and cross-program usage
- To **document the BMS maps module**, we will create `app/bms/README.md` mapping all 17 screen definitions to their functional domains (auth, menus, accounts, cards, transactions, billing, reporting, user admin) — then add inline comments to each `.bms` file explaining field purposes, attribute settings, and screen layout structure
- To **document the symbolic map copybooks module**, we will create `app/cpy-bms/README.md` explaining the input/output buffer structure pattern (AI/AO suffixes) and mapping each symbolic map to its corresponding BMS source and COBOL program
- To **document the JCL operations module**, we will create `app/jcl/README.md` categorizing all 29 jobs by function (provisioning, CICS administration, business batch processing, dataset utilities) with execution order dependencies — then add inline comments to each `.jcl` file explaining job steps, DD allocations, and VSAM dataset relationships
- To **document the data fixtures module**, we will create `app/data/README.md` and `app/data/ASCII/README.md` describing all 9 ASCII fixture files, their record formats, field layouts, record counts, and relationship to COBOL copybook definitions
- To **document the catalog inventory**, we will create `app/catlg/README.md` explaining the IDCAMS LISTCAT report contents and its use for environment verification
- To **document the samples module**, we will create `samples/jcl/README.md` describing the three build wrapper patterns (batch COBOL, BMS map, CICS COBOL) and their customization requirements
- To **document the application root**, we will create `app/README.md` providing an architectural overview of the entire CardDemo application structure and module relationships

### 0.1.4 Inferred Documentation Needs

Based on repository analysis:

- **Undocumented inter-program dependencies:** COBOL programs use `EXEC CICS XCTL` and `EXEC CICS LINK` to call each other, but these call chains are not documented — the `app/cbl/README.md` must include a program dependency graph
- **Undocumented VSAM-to-program mappings:** Each COBOL program accesses specific VSAM datasets, but the file-to-program relationships are scattered across FILE-CONTROL sections — consolidation is needed in the `app/cbl/README.md`
- **Undocumented copybook consumption:** Copybooks are `COPY`-included across multiple programs, but no cross-reference exists — the `app/cpy/README.md` must include a usage matrix
- **Undocumented batch execution order:** JCL jobs must be run in a specific sequence for environment setup and batch processing, but this is only partially documented in the root `README.md` — the `app/jcl/README.md` must formalize the execution dependency chain
- **Undocumented data format specifications:** The ASCII fixture files use fixed-width positional formats, but the byte offsets, field types, and record structures are not documented outside the COBOL copybook source — the `app/data/ASCII/README.md` must bridge this gap

## 0.2 Documentation Discovery and Analysis

### 0.2.1 Existing Documentation Infrastructure Assessment

Repository analysis reveals a **partially documented mainframe application** with extensive root-level documentation but zero module-level READMEs and minimal inline code comments beyond standard headers.

**Documentation Framework:**

- **Documentation Generator:** MkDocs (configured in `mkdocs.yml`)
- **Site Name:** `blitzy-card-demo`
- **Plugins:** `techdocs-core` (Backstage TechDocs integration), `mermaid2` (Mermaid diagram rendering)
- **Navigation Structure:** `index.md` → `project-guide.md` → `technical-specifications.md`
- **Service Catalog:** Backstage component entity registered via `catalog-info.yaml` with `backstage.io/techdocs-ref: dir:.`
- **Diagram Tools:** Mermaid.js (via `mermaid2` plugin in `mkdocs.yml`), used extensively in `docs/project-guide.md` and `docs/technical-specifications.md`
- **Documentation Hosting:** Backstage TechDocs (inferred from `catalog-info.yaml` annotations and `techdocs-core` plugin)

**Existing Documentation Inventory:**

| File | Location | Content | Status |
|------|----------|---------|--------|
| `README.md` | Root | Application overview, technologies, installation, application inventory, screen screenshots, batch execution order | Comprehensive but mainframe-focused |
| `CONTRIBUTING.md` | Root | Contributor guidelines, PR process, security disclosure | Complete |
| `CODE_OF_CONDUCT.md` | Root | Amazon Open Source Code of Conduct reference | Complete |
| `LICENSE` | Root | Apache License 2.0 full text | Complete |
| `docs/index.md` | docs/ | Single-line project description | Minimal — landing page only |
| `docs/project-guide.md` | docs/ | Migration status, test results, compliance review, dev guide, appendices | Comprehensive (581 lines) |
| `docs/technical-specifications.md` | docs/ | Full technical migration blueprint | Comprehensive |
| `mkdocs.yml` | Root | MkDocs site configuration with nav and plugins | Complete |
| `catalog-info.yaml` | Root | Backstage service catalog entity descriptor | Complete |

**Module-Level Documentation Status:**

| Module Directory | README Exists | Inline Comments | Assessment |
|------------------|---------------|-----------------|------------|
| `app/` | ❌ No | N/A (directory only) | No architectural overview |
| `app/cbl/` | ❌ No | Minimal (header blocks only) | 28 programs undocumented at module level |
| `app/cpy/` | ❌ No | License headers only | 28 copybooks undocumented |
| `app/bms/` | ❌ No | License headers only | 17 mapsets undocumented |
| `app/cpy-bms/` | ❌ No | None | 17 symbolic maps undocumented |
| `app/jcl/` | ❌ No | Inconsistent (some descriptive) | 29 jobs undocumented at module level |
| `app/data/` | ❌ No | N/A | No format documentation |
| `app/data/ASCII/` | ❌ No | N/A (plain text data) | 9 fixture files undocumented |
| `app/catlg/` | ❌ No | N/A | LISTCAT report undocumented |
| `samples/jcl/` | ❌ No | Apache headers + inline guidance | 3 sample JCLs partially documented |
| `docs/` | ❌ No | N/A | Documentation hub undocumented |

### 0.2.2 Repository Code Analysis for Documentation

**COBOL Programs (`app/cbl/`) — Inline Comment Analysis:**

- All 28 programs have a standard 6-line header block: Program, Application, Type, Function
- License headers (Apache 2.0) are present in all files
- Section-divider comments (`*----------------------------------------------------------------*`) exist for major DIVISION boundaries
- Business logic paragraphs typically have no explanatory comments
- CICS API calls (EXEC CICS SEND MAP, EXEC CICS RECEIVE MAP, EXEC CICS READ, EXEC CICS XCTL, etc.) lack contextual explanations
- Error handling patterns (FILE STATUS checks, CICS RESP code evaluation) are uncommented
- Working-Storage variable declarations have no field-purpose documentation
- The coding style is explicitly non-uniform across the application (stated in root `README.md`: "the coding style is not uniform across the application")

**Copybooks (`app/cpy/`) — Inline Comment Analysis:**

- 1-line purpose comments exist in some copybooks (e.g., `COCOM01Y.cpy`: "Communication area for CardDemo application programs")
- License headers are present in all files
- Field definitions (01/05/10/88 levels) have no semantic documentation
- Record layouts lack byte-offset annotations or field-purpose descriptions
- Cross-program usage is not documented within the copybook files themselves

**BMS Maps (`app/bms/`) — Inline Comment Analysis:**

- 1-line screen title comments exist (e.g., `COSGN00.bms`: "CardDemo - Login Screen")
- License headers are present
- DFHMSD/DFHMDI/DFHMDF macros have no inline explanations of attribute choices
- Field geometries (LINE, COLUMN, LENGTH) are uncommented
- Color/attribute/validation settings lack rationale documentation

**JCL Jobs (`app/jcl/`) — Inline Comment Analysis:**

- License headers are present in all files
- Some jobs have 2-3 line descriptive comments (e.g., POSTTRAN: "Process and load daily transaction file and create transaction category balance and update transaction master vsam")
- DD statement allocations generally lack explanatory comments
- IDCAMS DEFINE CLUSTER parameters (key length, record size, SHAREOPTIONS) are uncommented
- Step sequencing rationale is not documented
- Conditional execution logic (COND parameters) lacks explanation

**Sample JCL (`samples/jcl/`) — Inline Comment Analysis:**

- These are the best-documented files in the repository — they include inline guidance for customization, placeholder instructions, and usage context
- Apache 2.0 headers are present
- SET symbol explanations exist
- Post-build CICS NEWCOPY refresh steps are partially commented

### 0.2.3 Web Search Research Conducted

- **MkDocs techdocs-core plugin:** Latest version 1.6.1 (December 2025), wraps multiple MkDocs plugins and Python Markdown extensions for Backstage TechDocs integration
- **MkDocs mermaid2 plugin:** Latest version 1.2.3, supports Mermaid.js versions >= 10 and lower, renders diagrams from fenced code blocks in Markdown
- **COBOL inline documentation best practices:** Standard conventions include column 7 asterisks for full-line comments, structured paragraph-level documentation, and section dividers for logical groupings
- **Mainframe JCL documentation conventions:** Comment lines use `//*` prefix, with descriptive headers before job steps and DD allocations explaining dataset purposes and processing logic

## 0.3 Documentation Scope Analysis

### 0.3.1 Code-to-Documentation Mapping

**Module: `app/cbl/` — COBOL Programs (28 files)**

- Public Programs (Online — 18):
  - COSGN00C.cbl — Sign-on controller (transaction CC00, reads USRSEC)
  - COMEN01C.cbl — Main menu controller (10 options, XCTL navigation)
  - COADM01C.cbl — Admin menu controller (4 options, XCTL navigation)
  - COACTVWC.cbl — Account view (reads ACCTDAT, CUSTDAT, CXACAIX)
  - COACTUPC.cbl — Account update (dual-record write with SYNCPOINT)
  - COCRDLIC.cbl — Card list browse (paged display, 7 rows)
  - COCRDSLC.cbl — Card detail view (keyed read)
  - COCRDUPC.cbl — Card update (optimistic concurrency via before/after image)
  - COTRN00C.cbl — Transaction list browse (10 rows, paged)
  - COTRN01C.cbl — Transaction detail view
  - COTRN02C.cbl — Transaction add (next-ID generation)
  - COBIL00C.cbl — Bill payment (balance update + transaction write)
  - CORPT00C.cbl — Report submission (TDQ write to JOBS queue)
  - COUSR00C.cbl — User list browse (admin function)
  - COUSR01C.cbl — User add (admin function)
  - COUSR02C.cbl — User update (admin function)
  - COUSR03C.cbl — User delete (admin function)
  - CSUTLDTC.cbl — Date validation subprogram (LE CEEDAYS wrapper)
- Public Programs (Batch — 10):
  - CBACT01C.cbl — Account file read/display utility
  - CBACT02C.cbl — Card file read/display utility
  - CBACT03C.cbl — Cross-reference file read/display utility
  - CBACT04C.cbl — Interest calculation/posting
  - CBCUS01C.cbl — Customer file read/display utility
  - CBTRN01C.cbl — Daily transaction driver (validation/lookup)
  - CBTRN02C.cbl — Transaction posting engine (reject processing)
  - CBTRN03C.cbl — Transaction reporting (date-filtered, paginated)
  - CBSTM03A.CBL — Statement generation (text + HTML output)
  - CBSTM03B.CBL — File service subroutine (callable by CBSTM03A)
- Current Documentation: Header blocks only (program name, type, function)
- Documentation Needed: Module README, comprehensive inline comments for every paragraph-level block, CICS API explanations, VSAM I/O documentation, error handling annotations, cross-reference to copybooks and BMS maps

**Module: `app/cpy/` — Shared Copybooks (28 files)**

- Record Layout Copybooks (15): CVACT01Y, CVACT02Y, CVACT03Y, CVTRA01Y–CVTRA07Y, CVCUS01Y, CUSTREC, CSUSR01Y, COSTM01, UNUSED1Y
- Navigation/Contract Copybooks (3): COCOM01Y, COMEN02Y, COADM02Y
- Screen/UI Copybooks (3): COTTL01Y, CSMSG01Y, CSMSG02Y
- Utility/Validation Copybooks (4): CSDAT01Y, CSUTLDWY, CSUTLDPY, CSLKPCDY
- CICS/BMS Helper Copybooks (2): CSSETATY, CSSTRPFY
- Work Area Copybooks (1): CVCRD01Y
- Current Documentation: License headers + occasional 1-line purpose comments
- Documentation Needed: Module README with usage matrix, field-level inline comments with byte offsets, semantic annotations for 88-level conditions, cross-program consumption references

**Module: `app/bms/` — BMS Map Definitions (17 files)**

- Authentication: COSGN00.bms
- Menus: COMEN01.bms, COADM01.bms
- Account Management: COACTVW.bms, COACTUP.bms
- Card Management: COCRDLI.bms, COCRDSL.bms, COCRDUP.bms
- Transaction Processing: COTRN00.bms, COTRN01.bms, COTRN02.bms
- Billing: COBIL00.bms
- Reporting: CORPT00.bms
- User Administration: COUSR00.bms, COUSR01.bms, COUSR02.bms, COUSR03.bms
- Current Documentation: License headers + 1-line screen title
- Documentation Needed: Module README with screen-to-program mapping, field-level inline comments explaining attributes, layout explanations, validation rules

**Module: `app/cpy-bms/` — Symbolic Map Copybooks (17 files)**

- Generated/maintained BMS symbolic maps matching every `app/bms/*.bms` file
- Pattern: Each `.CPY` file defines paired input (AI suffix) and output (AO suffix) layouts
- Current Documentation: None beyond file content
- Documentation Needed: Module README explaining the AI/AO pattern, inline comments documenting field-to-screen mappings

**Module: `app/jcl/` — JCL Jobs (29 files)**

- Provisioning/Rebuild (12): ACCTFILE, CARDFILE, CUSTFILE, XREFFILE, TRANFILE, TCATBALF, TRANCATG, TRANTYPE, DISCGRP, DUSRSECJ, DEFCUST, TRANIDX
- GDG Setup (3): DEFGDGB, REPTFILE, DALYREJS
- CICS Administration (3): CBADMCDJ, CLOSEFIL, OPENFIL
- Business Batch Processing (5): POSTTRAN, INTCALC, COMBTRAN, TRANREPT, CREASTMT
- Dataset Utilities (3): TRANBKP, PRTCATBL, COMBTRAN
- Diagnostic Read Utilities (4): READACCT, READCARD, READCUST, READXREF
- Current Documentation: Inconsistent — some have descriptive comments, many lack step-level explanations
- Documentation Needed: Module README with execution order, job dependency chain, DD allocation reference; inline comments for every step and DD statement

**Module: `app/data/` and `app/data/ASCII/` — Data Fixtures (9 files)**

- Entity/Master Datasets: acctdata.txt (50 records, 300-byte), carddata.txt (50, 150-byte), custdata.txt (50, 500-byte)
- Cross-Reference/Balance: cardxref.txt (50, 50-byte), tcatbal.txt (50, 50-byte)
- Transaction Data: dailytran.txt (variable count, 350-byte)
- Lookup/Reference: discgrp.txt (51 records), trancatg.txt (18 records), trantype.txt (7 records)
- Current Documentation: None
- Documentation Needed: Module README with file format specifications, record layout references to copybooks, field offset tables, record counts

**Module: `app/catlg/` — Catalog Inventory (1 file)**

- LISTCAT.txt — IDCAMS catalog report for AWS.M2.CARDDEMO (209 entries, 86 pages)
- Current Documentation: None
- Documentation Needed: README explaining report purpose, contents, and usage for environment verification

**Module: `samples/jcl/` — Sample JCL (3 files)**

- BATCMP.jcl — Batch COBOL compile wrapper
- BMSCMP.jcl — BMS map compile wrapper with CICS NEWCOPY
- CICCMP.jcl — CICS COBOL compile wrapper with CICS NEWCOPY
- Current Documentation: Good inline guidance (Apache headers, customization instructions)
- Documentation Needed: Module README consolidating usage instructions, customization parameters, prerequisites

### 0.3.2 Documentation Gap Analysis

Given the requirements and repository analysis, documentation gaps include:

**Zero Module-Level READMEs:**
- None of the 11 target directories (`app/`, `app/cbl/`, `app/cpy/`, `app/bms/`, `app/cpy-bms/`, `app/jcl/`, `app/data/`, `app/data/ASCII/`, `app/catlg/`, `samples/jcl/`, `docs/`) contain a README.md file

**Minimal Inline Code Comments:**
- 28 COBOL programs: Header blocks only — zero paragraph-level documentation, zero CICS API explanations, zero error handling annotations
- 28 copybooks: License headers only — zero field semantic documentation, zero byte-offset annotations
- 17 BMS maps: License headers only — zero field-purpose documentation, zero attribute explanations
- 17 symbolic maps: Zero documentation of any kind beyond code structure
- 29 JCL jobs: Inconsistent — some have 2-3 line descriptions, most lack step-level explanations, DD allocations are uncommented
- 3 sample JCLs: Best-documented files — have inline customization guidance

**Missing Cross-Reference Documentation:**
- No program-to-copybook usage matrix
- No program-to-BMS-map dependency mapping (documented in root README inventory table but not at module level)
- No VSAM-dataset-to-program access pattern catalog
- No JCL job execution dependency chain
- No data-fixture-to-copybook field mapping

## 0.4 Documentation Implementation Design

### 0.4.1 Documentation Structure Planning

The documentation structure adds module-level README files at every significant directory level while preserving the existing `docs/` MkDocs-based documentation pipeline:

```
Repository Root
├── README.md (EXISTING — update only if needed)
├── CONTRIBUTING.md (EXISTING — no changes)
├── CODE_OF_CONDUCT.md (EXISTING — no changes)
├── mkdocs.yml (UPDATE — add new docs if applicable)
├── docs/
│   ├── README.md (CREATE — documentation hub overview)
│   ├── index.md (EXISTING — no changes)
│   ├── project-guide.md (EXISTING — no changes)
│   └── technical-specifications.md (EXISTING — no changes)
├── app/
│   ├── README.md (CREATE — application root overview)
│   ├── cbl/
│   │   └── README.md (CREATE — COBOL programs catalog)
│   ├── cpy/
│   │   └── README.md (CREATE — copybook library reference)
│   ├── bms/
│   │   └── README.md (CREATE — BMS screen definitions catalog)
│   ├── cpy-bms/
│   │   └── README.md (CREATE — symbolic map reference)
│   ├── jcl/
│   │   └── README.md (CREATE — JCL operations guide)
│   ├── data/
│   │   ├── README.md (CREATE — data fixtures overview)
│   │   └── ASCII/
│   │       └── README.md (CREATE — ASCII file format specs)
│   └── catlg/
│       └── README.md (CREATE — catalog inventory reference)
└── samples/
    └── jcl/
        └── README.md (CREATE — build sample guide)
```

### 0.4.2 Content Generation Strategy

**Information Extraction Approach:**

- Extract program metadata (PROGRAM-ID, AUTHOR, function) from `IDENTIFICATION DIVISION` headers in `app/cbl/*.cbl`
- Extract CICS transaction IDs and program mappings from `EXEC CICS` statements and the root `README.md` inventory table
- Extract VSAM file assignments from `FILE-CONTROL` sections in `ENVIRONMENT DIVISION`
- Extract copybook dependencies from `COPY` statements in `DATA DIVISION` and `PROCEDURE DIVISION`
- Extract record layouts (field names, PIC clauses, REDEFINES, OCCURS, 88-level conditions) from `app/cpy/*.cpy`
- Extract screen field definitions (DFHMDF macros with LINE, COLUMN, LENGTH, ATTRB, COLOR) from `app/bms/*.bms`
- Extract DD allocations and dataset references from `app/jcl/*.jcl` step definitions
- Derive file format specifications from copybook layouts mapped to `app/data/ASCII/*.txt` records
- Cross-reference all inter-module dependencies to build comprehensive relationship diagrams

**Template Application:**

- Apply a consistent README template to each module directory containing: Title, Overview, Components Table, Architecture Fit, Dependencies, Usage/Workflows, Design Patterns, Known Limitations
- Apply a consistent inline comment pattern to each file type:
  - COBOL programs: Section-level dividers + paragraph-purpose comments + CICS/VSAM operation explanations
  - Copybooks: Record-level purpose + field-level semantics + byte-offset annotations for key structures
  - BMS maps: Screen-level purpose + field-group explanations + attribute rationale
  - JCL jobs: Job-level purpose + step-level explanations + DD allocation documentation

**Documentation Standards:**

- Markdown formatting with proper headers (`# ## ###`) for README files
- Mermaid diagram integration using fenced code blocks for architecture and flow diagrams
- Tables for component inventories, field mappings, and parameter descriptions
- Source citations as inline references: `Source: app/cbl/COSGN00C.cbl` or `Layout: app/cpy/CVACT01Y.cpy`
- Consistent terminology aligned with the existing `docs/project-guide.md` glossary (VSAM KSDS, BMS, COMMAREA, TDQ, GDG, COMP-3, SYNCPOINT, FILE STATUS)

### 0.4.3 Diagram and Visual Strategy

**Mermaid Diagrams to Create:**

- **Application Architecture Diagram** (`app/README.md`): Flowchart showing relationships between cbl, cpy, bms, cpy-bms, jcl, data, and catlg modules
- **Online Program Flow** (`app/cbl/README.md`): Flowchart showing CICS transaction routing from sign-on through menu navigation to functional screens
- **Batch Processing Pipeline** (`app/cbl/README.md`): Sequential diagram of the 5-stage batch flow (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT)
- **VSAM Dataset Access Map** (`app/cbl/README.md`): Diagram showing which programs access which VSAM datasets
- **Copybook Dependency Graph** (`app/cpy/README.md`): Diagram showing copybook-to-program consumption relationships
- **JCL Execution Dependency Chain** (`app/jcl/README.md`): Flowchart showing required job execution order for environment setup and batch processing
- **Data Flow Diagram** (`app/data/README.md`): Diagram showing how ASCII fixture files flow through JCL jobs into VSAM datasets

All diagrams use Mermaid syntax consistent with the `mermaid2` plugin configured in `mkdocs.yml`.

## 0.5 Documentation File Transformation Mapping

### 0.5.1 File-by-File Documentation Plan

**Documentation Transformation Modes:**
- **CREATE** — Create a new documentation file (README or enhanced inline comments)
- **UPDATE** — Update an existing file with additional inline comments
- **REFERENCE** — Use as a documentation style or content reference

| Target Documentation File | Transformation | Source Code/Docs | Content/Changes |
|---------------------------|----------------|------------------|-----------------|
| `app/README.md` | CREATE | `app/cbl/`, `app/cpy/`, `app/bms/`, `app/jcl/`, `app/data/` | Application root architectural overview, module directory descriptions, inter-module relationship diagram, technology stack summary |
| `app/cbl/README.md` | CREATE | All 28 `app/cbl/*.cbl` files, `README.md` inventory tables | Complete COBOL program catalog (online + batch), transaction-to-program mapping, VSAM access patterns, inter-program call chains, batch pipeline overview |
| `app/cpy/README.md` | CREATE | All 28 `app/cpy/*.cpy` files | Copybook library reference by category, record layout summaries, field semantic tables, cross-program usage matrix |
| `app/bms/README.md` | CREATE | All 17 `app/bms/*.bms` files | BMS screen definitions catalog, screen-to-program mapping, field group descriptions, common attribute patterns |
| `app/cpy-bms/README.md` | CREATE | All 17 `app/cpy-bms/*.CPY` files | Symbolic map reference, AI/AO pattern explanation, map-to-BMS-to-program traceability |
| `app/jcl/README.md` | CREATE | All 29 `app/jcl/*.jcl` files | JCL operations guide, job categorization, execution order dependencies, DD allocation reference, VSAM dataset management |
| `app/data/README.md` | CREATE | `app/data/ASCII/`, `app/cpy/CV*.cpy` | Data fixtures overview, file format specifications, copybook-to-file mapping |
| `app/data/ASCII/README.md` | CREATE | All 9 `app/data/ASCII/*.txt` files, corresponding copybooks | Detailed ASCII file format specs, record counts, field offset tables, usage context |
| `app/catlg/README.md` | CREATE | `app/catlg/LISTCAT.txt` | Catalog inventory reference, report structure description, usage for verification |
| `samples/jcl/README.md` | CREATE | All 3 `samples/jcl/*.jcl` files | Build sample guide, compile wrapper patterns, customization parameters, prerequisites |
| `docs/README.md` | CREATE | `mkdocs.yml`, `catalog-info.yaml`, `docs/*.md` | Documentation hub overview, MkDocs pipeline description, content map |
| `app/cbl/COSGN00C.cbl` | UPDATE | `app/cbl/COSGN00C.cbl` | Add inline COBOL comments: paragraph-level logic, CICS SEND/RECEIVE MAP, USRSEC READ, authentication flow, error handling |
| `app/cbl/COMEN01C.cbl` | UPDATE | `app/cbl/COMEN01C.cbl` | Add inline comments: menu option routing, XCTL navigation, COMMAREA handling |
| `app/cbl/COADM01C.cbl` | UPDATE | `app/cbl/COADM01C.cbl` | Add inline comments: admin menu logic, user-type validation, navigation routing |
| `app/cbl/COACTVWC.cbl` | UPDATE | `app/cbl/COACTVWC.cbl` | Add inline comments: 3-entity join (Account+Customer+CrossRef), VSAM READ patterns, screen population |
| `app/cbl/COACTUPC.cbl` | UPDATE | `app/cbl/COACTUPC.cbl` | Add inline comments: dual-record update, SYNCPOINT ROLLBACK, field validation, optimistic concurrency |
| `app/cbl/COCRDLIC.cbl` | UPDATE | `app/cbl/COCRDLIC.cbl` | Add inline comments: card browse pagination, STARTBR/READNEXT/ENDBR pattern, screen row assembly |
| `app/cbl/COCRDSLC.cbl` | UPDATE | `app/cbl/COCRDSLC.cbl` | Add inline comments: card detail keyed read, screen field mapping |
| `app/cbl/COCRDUPC.cbl` | UPDATE | `app/cbl/COCRDUPC.cbl` | Add inline comments: card update logic, before/after concurrency check, REWRITE operation |
| `app/cbl/COTRN00C.cbl` | UPDATE | `app/cbl/COTRN00C.cbl` | Add inline comments: transaction browse, pagination mechanics, filter logic |
| `app/cbl/COTRN01C.cbl` | UPDATE | `app/cbl/COTRN01C.cbl` | Add inline comments: transaction detail read, cross-reference resolution, field display |
| `app/cbl/COTRN02C.cbl` | UPDATE | `app/cbl/COTRN02C.cbl` | Add inline comments: transaction add, next-ID generation (browse-to-end), confirmation handling |
| `app/cbl/COBIL00C.cbl` | UPDATE | `app/cbl/COBIL00C.cbl` | Add inline comments: bill payment flow, account balance update, transaction record creation |
| `app/cbl/CORPT00C.cbl` | UPDATE | `app/cbl/CORPT00C.cbl` | Add inline comments: report criteria collection, TDQ WRITEQ submission, JCL deck generation |
| `app/cbl/COUSR00C.cbl` | UPDATE | `app/cbl/COUSR00C.cbl` | Add inline comments: user list browse, USRSEC READ, screen row population |
| `app/cbl/COUSR01C.cbl` | UPDATE | `app/cbl/COUSR01C.cbl` | Add inline comments: user add, USRSEC WRITE, input validation |
| `app/cbl/COUSR02C.cbl` | UPDATE | `app/cbl/COUSR02C.cbl` | Add inline comments: user update, USRSEC READ UPDATE/REWRITE |
| `app/cbl/COUSR03C.cbl` | UPDATE | `app/cbl/COUSR03C.cbl` | Add inline comments: user delete, USRSEC DELETE, confirmation display |
| `app/cbl/CSUTLDTC.cbl` | UPDATE | `app/cbl/CSUTLDTC.cbl` | Add inline comments: date validation wrapper, CEEDAYS call, severity code mapping |
| `app/cbl/CBACT01C.cbl` | UPDATE | `app/cbl/CBACT01C.cbl` | Add inline comments: account file sequential read, display logic, EOF handling |
| `app/cbl/CBACT02C.cbl` | UPDATE | `app/cbl/CBACT02C.cbl` | Add inline comments: card file sequential read utility |
| `app/cbl/CBACT03C.cbl` | UPDATE | `app/cbl/CBACT03C.cbl` | Add inline comments: cross-reference file read utility |
| `app/cbl/CBACT04C.cbl` | UPDATE | `app/cbl/CBACT04C.cbl` | Add inline comments: interest calculation, rate lookup, balance update, generated transaction output |
| `app/cbl/CBCUS01C.cbl` | UPDATE | `app/cbl/CBCUS01C.cbl` | Add inline comments: customer file read utility |
| `app/cbl/CBTRN01C.cbl` | UPDATE | `app/cbl/CBTRN01C.cbl` | Add inline comments: daily transaction driver, file open/validation flow |
| `app/cbl/CBTRN02C.cbl` | UPDATE | `app/cbl/CBTRN02C.cbl` | Add inline comments: transaction posting engine, 4-stage validation cascade, reject processing, balance update |
| `app/cbl/CBTRN03C.cbl` | UPDATE | `app/cbl/CBTRN03C.cbl` | Add inline comments: transaction reporting, date filtering, 3-level totals, page formatting |
| `app/cbl/CBSTM03A.CBL` | UPDATE | `app/cbl/CBSTM03A.CBL` | Add inline comments: statement generation, memory buffering, text/HTML output, 4-entity join |
| `app/cbl/CBSTM03B.CBL` | UPDATE | `app/cbl/CBSTM03B.CBL` | Add inline comments: file service subroutine, OPEN/READ/CLOSE abstraction |
| `app/cpy/COCOM01Y.cpy` | UPDATE | `app/cpy/COCOM01Y.cpy` | Add field-level comments: COMMAREA structure, routing fields, user identity, 88-level conditions |
| `app/cpy/COMEN02Y.cpy` | UPDATE | `app/cpy/COMEN02Y.cpy` | Add comments: menu option table structure, program routing fields |
| `app/cpy/COADM02Y.cpy` | UPDATE | `app/cpy/COADM02Y.cpy` | Add comments: admin menu 4-entry table, target program mappings |
| `app/cpy/CVACT01Y.cpy` | UPDATE | `app/cpy/CVACT01Y.cpy` | Add comments: 300-byte account record layout, field semantics, byte offsets |
| `app/cpy/CVACT02Y.cpy` | UPDATE | `app/cpy/CVACT02Y.cpy` | Add comments: 150-byte card record layout, field semantics |
| `app/cpy/CVACT03Y.cpy` | UPDATE | `app/cpy/CVACT03Y.cpy` | Add comments: 50-byte cross-reference record, linking semantics |
| `app/cpy/CVTRA01Y.cpy` | UPDATE | `app/cpy/CVTRA01Y.cpy` | Add comments: transaction category balance record, composite key structure |
| `app/cpy/CVTRA02Y.cpy` | UPDATE | `app/cpy/CVTRA02Y.cpy` | Add comments: disclosure group record structure |
| `app/cpy/CVTRA03Y.cpy` | UPDATE | `app/cpy/CVTRA03Y.cpy` | Add comments: transaction type record (2-byte code families) |
| `app/cpy/CVTRA04Y.cpy` | UPDATE | `app/cpy/CVTRA04Y.cpy` | Add comments: transaction category record structure |
| `app/cpy/CVTRA05Y.cpy` | UPDATE | `app/cpy/CVTRA05Y.cpy` | Add comments: 350-byte transaction record, field semantics |
| `app/cpy/CVTRA06Y.cpy` | UPDATE | `app/cpy/CVTRA06Y.cpy` | Add comments: 350-byte daily transaction staging record |
| `app/cpy/CVTRA07Y.cpy` | UPDATE | `app/cpy/CVTRA07Y.cpy` | Add comments: report line formats, header/detail/total structures |
| `app/cpy/CVCUS01Y.cpy` | UPDATE | `app/cpy/CVCUS01Y.cpy` | Add comments: 500-byte customer record, demographic fields |
| `app/cpy/CUSTREC.cpy` | UPDATE | `app/cpy/CUSTREC.cpy` | Add comments: alternate customer record layout |
| `app/cpy/CSUSR01Y.cpy` | UPDATE | `app/cpy/CSUSR01Y.cpy` | Add comments: 80-byte user security record |
| `app/cpy/COTTL01Y.cpy` | UPDATE | `app/cpy/COTTL01Y.cpy` | Add comments: application title/banner text fields |
| `app/cpy/CSMSG01Y.cpy` | UPDATE | `app/cpy/CSMSG01Y.cpy` | Add comments: common user message definitions |
| `app/cpy/CSMSG02Y.cpy` | UPDATE | `app/cpy/CSMSG02Y.cpy` | Add comments: abend data work area fields |
| `app/cpy/CVCRD01Y.cpy` | UPDATE | `app/cpy/CVCRD01Y.cpy` | Add comments: card work area, AID/PF-key flags, routing fields |
| `app/cpy/CSDAT01Y.cpy` | UPDATE | `app/cpy/CSDAT01Y.cpy` | Add comments: date/time working storage, component views |
| `app/cpy/CSUTLDWY.cpy` | UPDATE | `app/cpy/CSUTLDWY.cpy` | Add comments: date-edit working storage, validity flags |
| `app/cpy/CSUTLDPY.cpy` | UPDATE | `app/cpy/CSUTLDPY.cpy` | Add comments: date validation paragraphs, leap year checks, LE integration |
| `app/cpy/CSLKPCDY.cpy` | UPDATE | `app/cpy/CSLKPCDY.cpy` | Add comments: NANPA area codes, US state codes, ZIP-prefix validation sets |
| `app/cpy/CSSETATY.cpy` | UPDATE | `app/cpy/CSSETATY.cpy` | Add comments: BMS field attribute setting logic, COPY REPLACING pattern |
| `app/cpy/CSSTRPFY.cpy` | UPDATE | `app/cpy/CSSTRPFY.cpy` | Add comments: EIBAID-to-AID condition mapping, PF key folding logic |
| `app/cpy/COSTM01.CPY` | UPDATE | `app/cpy/COSTM01.CPY` | Add comments: reporting transaction layout fields |
| `app/cpy/UNUSED1Y.cpy` | UPDATE | `app/cpy/UNUSED1Y.cpy` | Add comments: reserved/unused 80-byte layout purpose |
| `app/bms/COSGN00.bms` | UPDATE | `app/bms/COSGN00.bms` | Add inline comments: sign-on screen field groups, credential input fields, branding area |
| `app/bms/COMEN01.bms` | UPDATE | `app/bms/COMEN01.bms` | Add inline comments: main menu option lines, selection input, error area |
| `app/bms/COADM01.bms` | UPDATE | `app/bms/COADM01.bms` | Add inline comments: admin menu layout, option fields |
| `app/bms/COACTVW.bms` | UPDATE | `app/bms/COACTVW.bms` | Add inline comments: account view field groups, protected display areas |
| `app/bms/COACTUP.bms` | UPDATE | `app/bms/COACTUP.bms` | Add inline comments: account update editable fields, save/cancel actions |
| `app/bms/COCRDLI.bms` | UPDATE | `app/bms/COCRDLI.bms` | Add inline comments: card list selectable rows, paging controls |
| `app/bms/COCRDSL.bms` | UPDATE | `app/bms/COCRDSL.bms` | Add inline comments: card detail display fields |
| `app/bms/COCRDUP.bms` | UPDATE | `app/bms/COCRDUP.bms` | Add inline comments: card update editable fields |
| `app/bms/COTRN00.bms` | UPDATE | `app/bms/COTRN00.bms` | Add inline comments: transaction list rows, filter and paging |
| `app/bms/COTRN01.bms` | UPDATE | `app/bms/COTRN01.bms` | Add inline comments: transaction detail view fields |
| `app/bms/COTRN02.bms` | UPDATE | `app/bms/COTRN02.bms` | Add inline comments: transaction add form fields, confirmation |
| `app/bms/COBIL00.bms` | UPDATE | `app/bms/COBIL00.bms` | Add inline comments: bill payment confirmation fields |
| `app/bms/CORPT00.bms` | UPDATE | `app/bms/CORPT00.bms` | Add inline comments: report criteria selection fields |
| `app/bms/COUSR00.bms` | UPDATE | `app/bms/COUSR00.bms` | Add inline comments: user list selectable rows |
| `app/bms/COUSR01.bms` | UPDATE | `app/bms/COUSR01.bms` | Add inline comments: user add form fields |
| `app/bms/COUSR02.bms` | UPDATE | `app/bms/COUSR02.bms` | Add inline comments: user update form fields |
| `app/bms/COUSR03.bms` | UPDATE | `app/bms/COUSR03.bms` | Add inline comments: user delete confirmation display |
| `app/jcl/POSTTRAN.jcl` | UPDATE | `app/jcl/POSTTRAN.jcl` | Add step-level and DD-level comments: transaction posting, file allocations |
| `app/jcl/INTCALC.jcl` | UPDATE | `app/jcl/INTCALC.jcl` | Add comments: interest calculation step, PARM explanation, file allocations |
| `app/jcl/COMBTRAN.jcl` | UPDATE | `app/jcl/COMBTRAN.jcl` | Add comments: transaction combine, SORT control, concatenation logic |
| `app/jcl/TRANREPT.jcl` | UPDATE | `app/jcl/TRANREPT.jcl` | Add comments: report generation, date parameter filtering, REPROC usage |
| `app/jcl/CREASTMT.JCL` | UPDATE | `app/jcl/CREASTMT.JCL` | Add comments: statement generation, work file setup, output dataset allocation |
| `app/jcl/ACCTFILE.jcl` | UPDATE | `app/jcl/ACCTFILE.jcl` | Add comments: VSAM KSDS define/load for account data |
| `app/jcl/CARDFILE.jcl` | UPDATE | `app/jcl/CARDFILE.jcl` | Add comments: card dataset provisioning with AIX |
| `app/jcl/CUSTFILE.jcl` | UPDATE | `app/jcl/CUSTFILE.jcl` | Add comments: customer dataset provisioning |
| `app/jcl/XREFFILE.jcl` | UPDATE | `app/jcl/XREFFILE.jcl` | Add comments: cross-reference dataset with AIX/PATH |
| `app/jcl/TRANFILE.jcl` | UPDATE | `app/jcl/TRANFILE.jcl` | Add comments: transaction dataset provisioning with AIX/PATH |
| `app/jcl/TCATBALF.jcl` | UPDATE | `app/jcl/TCATBALF.jcl` | Add comments: transaction category balance provisioning |
| `app/jcl/TRANCATG.jcl` | UPDATE | `app/jcl/TRANCATG.jcl` | Add comments: transaction category type provisioning |
| `app/jcl/TRANTYPE.jcl` | UPDATE | `app/jcl/TRANTYPE.jcl` | Add comments: transaction type provisioning |
| `app/jcl/DISCGRP.jcl` | UPDATE | `app/jcl/DISCGRP.jcl` | Add comments: disclosure group provisioning |
| `app/jcl/DUSRSECJ.jcl` | UPDATE | `app/jcl/DUSRSECJ.jcl` | Add comments: user security file seeding, inline data |
| `app/jcl/DEFGDGB.jcl` | UPDATE | `app/jcl/DEFGDGB.jcl` | Add comments: GDG base definitions, LIMIT/SCRATCH settings |
| `app/jcl/REPTFILE.jcl` | UPDATE | `app/jcl/REPTFILE.jcl` | Add comments: report GDG base definition |
| `app/jcl/DALYREJS.jcl` | UPDATE | `app/jcl/DALYREJS.jcl` | Add comments: rejection GDG base definition |
| `app/jcl/CBADMCDJ.jcl` | UPDATE | `app/jcl/CBADMCDJ.jcl` | Add comments: CICS CSD resource definitions, GROUP creation |
| `app/jcl/CLOSEFIL.jcl` | UPDATE | `app/jcl/CLOSEFIL.jcl` | Add comments: CICS file close operations via SDSF |
| `app/jcl/OPENFIL.jcl` | UPDATE | `app/jcl/OPENFIL.jcl` | Add comments: CICS file open operations via SDSF |
| `app/jcl/TRANBKP.jcl` | UPDATE | `app/jcl/TRANBKP.jcl` | Add comments: transaction backup and rebuild |
| `app/jcl/TRANIDX.jcl` | UPDATE | `app/jcl/TRANIDX.jcl` | Add comments: AIX/PATH definition for existing base |
| `app/jcl/PRTCATBL.jcl` | UPDATE | `app/jcl/PRTCATBL.jcl` | Add comments: TCATBAL unload and formatted print |
| `app/jcl/DEFCUST.jcl` | UPDATE | `app/jcl/DEFCUST.jcl` | Add comments: primitive customer define (note dataset name mismatch) |
| `app/jcl/READACCT.jcl` | UPDATE | `app/jcl/READACCT.jcl` | Add comments: account file read utility wrapper |
| `app/jcl/READCARD.jcl` | UPDATE | `app/jcl/READCARD.jcl` | Add comments: card file read utility wrapper |
| `app/jcl/READCUST.jcl` | UPDATE | `app/jcl/READCUST.jcl` | Add comments: customer file read utility wrapper |
| `app/jcl/READXREF.jcl` | UPDATE | `app/jcl/READXREF.jcl` | Add comments: cross-reference file read utility wrapper |
| `samples/jcl/BATCMP.jcl` | UPDATE | `samples/jcl/BATCMP.jcl` | Enhance existing inline comments: clarify BUILDBAT procedure, HLQ customization |
| `samples/jcl/BMSCMP.jcl` | UPDATE | `samples/jcl/BMSCMP.jcl` | Enhance inline comments: clarify BUILDBMS procedure, NEWCOPY step |
| `samples/jcl/CICCMP.jcl` | UPDATE | `samples/jcl/CICCMP.jcl` | Enhance inline comments: clarify BUILDONL procedure, COND logic, NEWCOPY step |
| `README.md` | REFERENCE | Root README | Use as reference for application inventory tables, technology list, and documentation tone |
| `docs/project-guide.md` | REFERENCE | docs/ | Use as reference for glossary terms, technology versions, and migration context |

### 0.5.2 New Documentation Files Detail

**File: `app/README.md`**
- Type: Architectural Overview
- Source: All `app/` subdirectories
- Sections:
  - Overview (CardDemo application purpose and mainframe context)
  - Module Directory Structure (table of all 7 subdirectories with descriptions)
  - Architecture Diagram (Mermaid flowchart showing module relationships)
  - Technology Stack (COBOL, CICS, VSAM, JCL, BMS, RACF)
  - Module Interaction Patterns (how programs, copybooks, maps, and data interrelate)
  - Getting Started (cross-references to root README for installation)
- Key Citations: `README.md`, all `app/*/` folder summaries

**File: `app/cbl/README.md`**
- Type: Program Catalog and Reference
- Source: All 28 `app/cbl/*.cbl` files
- Sections:
  - Overview (28 COBOL programs: 18 online + 10 batch)
  - Online Programs Inventory (table: transaction ID, BMS map, program, function)
  - Batch Programs Inventory (table: JCL job, program, function)
  - Program Navigation Flow (Mermaid diagram of CICS XCTL routing)
  - VSAM File Access Matrix (table: program × dataset × access mode)
  - Copybook Dependencies (table: program × consumed copybooks)
  - Batch Pipeline Flow (Mermaid sequence diagram of 5-stage pipeline)
  - Common Patterns (pseudo-conversational model, COMMAREA, error handling)
  - Known Limitations (non-uniform coding style, legacy constructs)
- Key Citations: All `app/cbl/*.cbl`, `app/cpy/COCOM01Y.cpy`, `README.md` inventory

**File: `app/cpy/README.md`**
- Type: Copybook Library Reference
- Source: All 28 `app/cpy/*.cpy` files
- Sections:
  - Overview (28 shared copybooks: record layouts, contracts, utilities)
  - Categorized Inventory (table by category: record layouts, navigation, screen text, validation, CICS helpers, report formats)
  - Record Layout Summary (table: copybook, record name, size, key structure)
  - Cross-Program Usage Matrix (table: copybook × consuming programs)
  - Dependency Graph (Mermaid diagram)
  - Field Naming Conventions (prefix patterns, PIC clause standards)
- Key Citations: All `app/cpy/*.cpy`, `app/cbl/*.cbl` COPY statements

**File: `app/bms/README.md`**
- Type: Screen Definitions Catalog
- Source: All 17 `app/bms/*.bms` files
- Sections:
  - Overview (17 CICS BMS mapset definitions for 3270 terminal screens)
  - Screen Inventory by Domain (table: mapset, screen, program, function)
  - Common BMS Attributes (LANG, MODE, STORAGE, TIOAPFX, EXTATT)
  - Field Patterns (headers, error messages, function key legends, input/display fields)
  - Screen-to-Program Mapping (Mermaid diagram)
- Key Citations: All `app/bms/*.bms`, `README.md` inventory

**File: `app/cpy-bms/README.md`**
- Type: Symbolic Map Technical Reference
- Source: All 17 `app/cpy-bms/*.CPY` files
- Sections:
  - Overview (17 compile-time symbolic map copybooks)
  - AI/AO Pattern Explanation (input vs output buffer layouts)
  - Field Structure Convention (L/F/A/I/C/P/H/V/O suffix meanings)
  - Map-to-BMS-to-Program Traceability Table
  - Compile-Time Dependencies
- Key Citations: All `app/cpy-bms/*.CPY`, corresponding `app/bms/*.bms`

**File: `app/jcl/README.md`**
- Type: JCL Operations Guide
- Source: All 29 `app/jcl/*.jcl` files
- Sections:
  - Overview (29 z/OS JCL job members)
  - Job Categorization Table (provisioning, GDG setup, CICS admin, batch processing, utilities, diagnostics)
  - Environment Setup Execution Order (numbered sequence with dependencies)
  - Batch Processing Execution Order
  - VSAM Dataset Reference (table: dataset name, cluster type, key, record size)
  - Execution Dependency Diagram (Mermaid flowchart)
  - Common JCL Patterns (IDCAMS, DFSORT, SDSF, REPROC)
  - Known Issues (DEFCUST dataset name mismatch)
- Key Citations: All `app/jcl/*.jcl`, `README.md` batch tables

**File: `app/data/README.md`**
- Type: Data Fixtures Overview
- Source: `app/data/ASCII/`
- Sections:
  - Overview (file-based data fixture area for CardDemo)
  - Directory Structure
  - Data Flow Diagram (ASCII → JCL → VSAM, Mermaid)
  - Cross-Reference to COBOL Copybooks
- Key Citations: `app/data/ASCII/`, `app/cpy/CV*.cpy`

**File: `app/data/ASCII/README.md`**
- Type: File Format Specification
- Source: All 9 `app/data/ASCII/*.txt` files, corresponding `app/cpy/CV*.cpy` copybooks
- Sections:
  - Overview (9 fixed-format ASCII plain-text data files)
  - File Inventory Table (file, record count, record size, copybook layout, description)
  - Detailed Record Layouts (field offset tables for each file mapped from copybook PIC clauses)
  - Data Relationships (entity cross-references)
  - Usage Context (batch ingestion, test data, demo scenarios)
  - Handling Warnings (byte-accurate sensitivity, trailing space significance)
- Key Citations: All `app/data/ASCII/*.txt`, `app/cpy/CVACT01Y.cpy`, `CVACT02Y.cpy`, `CVACT03Y.cpy`, `CVCUS01Y.cpy`, `CVTRA01Y-06Y.cpy`

**File: `app/catlg/README.md`**
- Type: Catalog Inventory Reference
- Source: `app/catlg/LISTCAT.txt`
- Sections:
  - Overview (IDCAMS LISTCAT report for AWS.M2.CARDDEMO)
  - Report Contents Summary (209 entries by type)
  - Usage for Environment Verification
  - Key Metadata Captured (VSAM attributes, GDG lifecycle, allocation details)
- Key Citations: `app/catlg/LISTCAT.txt`

**File: `samples/jcl/README.md`**
- Type: Build Sample Guide
- Source: All 3 `samples/jcl/*.jcl` files
- Sections:
  - Overview (3 sample build wrapper JCLs)
  - Compile Patterns Table (batch, BMS, CICS COBOL)
  - Customization Parameters (HLQ, MEMNAME, procedure library)
  - Prerequisites (cataloged procedures, CICS region)
  - Post-Build NEWCOPY Process
- Key Citations: `samples/jcl/BATCMP.jcl`, `BMSCMP.jcl`, `CICCMP.jcl`

**File: `docs/README.md`**
- Type: Documentation Hub Overview
- Source: `mkdocs.yml`, `catalog-info.yaml`, `docs/*.md`
- Sections:
  - Overview (documentation hub for blitzy-card-demo)
  - Content Map (navigation structure from mkdocs.yml)
  - Documentation Pipeline (MkDocs + TechDocs + Backstage integration)
  - Building Documentation Locally
  - Contributing Documentation
- Key Citations: `mkdocs.yml`, `catalog-info.yaml`

### 0.5.3 Documentation Configuration Updates

- **`mkdocs.yml`** — Potentially add new documentation pages to the `nav` section if any new `docs/*.md` files are created beyond the existing three pages. The `docs/README.md` itself does not need to be in the MkDocs nav since it serves as a GitHub-rendered file rather than a TechDocs page.

### 0.5.4 Cross-Documentation Dependencies

- All module READMEs cross-reference each other via relative Markdown links (e.g., `app/cbl/README.md` links to `../cpy/README.md` for copybook details)
- The `app/README.md` serves as the hub, linking to all 7 child module READMEs
- The `app/data/ASCII/README.md` references copybook definitions from `app/cpy/` for field layout specifications
- The `app/cbl/README.md` references BMS maps from `app/bms/` and symbolic maps from `app/cpy-bms/`
- The `app/jcl/README.md` references COBOL programs from `app/cbl/` for batch execution context
- The root `README.md` already contains the primary application inventory and is referenced by module READMEs for high-level context

## 0.6 Dependency Inventory

### 0.6.1 Documentation Dependencies

The following documentation tools and packages are relevant to this documentation exercise. Versions are verified from `mkdocs.yml` configuration, PyPI registry searches, and the Backstage TechDocs ecosystem documentation.

| Registry | Package Name | Version | Purpose |
|----------|--------------|---------|---------|
| pip | mkdocs | 1.6.1 (compatible) | Static site generator for documentation, configured in `mkdocs.yml` |
| pip | mkdocs-techdocs-core | 1.6.1 | Backstage TechDocs wrapper plugin providing Material theme, monorepo support, admonitions, and Python Markdown extensions |
| pip | mkdocs-mermaid2-plugin | 1.2.3 | Mermaid.js diagram rendering plugin for embedding flowcharts, sequence diagrams, and state diagrams in Markdown |
| pip | mkdocs-material | (bundled via techdocs-core) | Material Design theme for MkDocs, included transitively by techdocs-core |
| N/A | Mermaid.js | 10.4.0 (default) | JavaScript diagramming library rendered client-side by mermaid2 plugin |

**Notes:**
- No `requirements.txt`, `pyproject.toml`, or other Python dependency manifest exists in the repository root for documentation tooling — `mkdocs.yml` references plugins by name, and the build environment is expected to have `mkdocs-techdocs-core` and `mkdocs-mermaid2-plugin` pre-installed (typically via the Backstage TechDocs Docker container)
- The repository has no `package.json`, `pom.xml`, or other application-level dependency manifest — the codebase consists entirely of COBOL source, BMS maps, JCL, copybooks, and data fixtures without a buildable artifact at the repository level
- All new documentation files are plain Markdown (`.md`) and COBOL/JCL/BMS inline comments — no additional tooling dependencies are required for the documentation deliverables themselves

### 0.6.2 Documentation Reference Updates

No link transformation is required for this documentation effort since all files are new creations or inline comment additions. However, the following internal link conventions apply:

- Module READMEs use relative paths for cross-references: `[COBOL Programs](cbl/README.md)` from `app/README.md`
- References to root-level files from module READMEs use upward traversal: `[Main README](../../README.md)` from `app/cbl/README.md`
- All Mermaid diagrams use inline fenced code blocks rather than external image files, ensuring they render in both GitHub and MkDocs/TechDocs contexts
- Source citations in documentation use the convention: `Source: app/cbl/COSGN00C.cbl` with relative paths from the repository root

## 0.7 Coverage and Quality Targets

### 0.7.1 Documentation Coverage Metrics

**Current Coverage Analysis:**

| Coverage Dimension | Current | Target | Gap |
|--------------------|---------|--------|-----|
| Module directories with READMEs | 0/11 (0%) | 11/11 (100%) | 11 READMEs to create |
| COBOL programs with inline comments | 0/28 (0%) | 28/28 (100%) | All 28 programs need paragraph-level comments |
| Copybooks with field documentation | 0/28 (0%) | 28/28 (100%) | All 28 copybooks need field-level comments |
| BMS maps with field documentation | 0/17 (0%) | 17/17 (100%) | All 17 maps need inline explanations |
| Symbolic maps with documentation | 0/17 (0%) | 17/17 (100%) | All 17 symbolic maps need comments |
| JCL jobs with step documentation | ~5/29 (17%) | 29/29 (100%) | 24 jobs need enhanced comments |
| Sample JCLs with documentation | 3/3 (100%) | 3/3 (100%) | Enhancement only — already documented |
| Root-level documentation files | 6/6 (100%) | 6/6 (100%) | No changes needed to existing root docs |
| `docs/` hub documentation | 3/3 (100%) | 3/3 (100%) | Existing docs are comprehensive |

**Target Coverage:** 100% of all module directories have README files; 100% of all source files have meaningful inline comments explaining purpose, logic, and dependencies.

**Coverage Gaps to Address:**

- `app/cbl/`: Currently 0% paragraph-level documented — target 100% with inline comments for every functional section, CICS call, VSAM operation, and error handling path
- `app/cpy/`: Currently 0% field-documented — target 100% with semantic annotations for every record layout field, 88-level condition, and cross-program relationship
- `app/bms/`: Currently 0% field-documented — target 100% with inline explanations for every screen field group, attribute setting, and layout decision
- `app/cpy-bms/`: Currently 0% documented — target 100% with AI/AO pattern explanations and field-to-screen mappings
- `app/jcl/`: Currently ~17% documented — target 100% with step-level and DD-level comments for every job
- `app/data/ASCII/`: Currently 0% documented — target 100% with format specifications and field layout tables

### 0.7.2 Documentation Quality Criteria

**Completeness Requirements:**

- Every COBOL program has a purpose summary in the header block beyond the current 1-line function description
- Every COBOL `PROCEDURE DIVISION` paragraph has at minimum a 1-line comment explaining its purpose
- Every CICS API call (`EXEC CICS`) has an inline comment explaining what the call does and why
- Every VSAM file I/O operation has an inline comment documenting the access pattern (READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, ENDBR)
- Every FILE STATUS check has an inline comment explaining the status codes being evaluated
- Every copybook field group (01-level) has a purpose annotation
- Every BMS DFHMDI map has an inline comment documenting the screen purpose and layout
- Every JCL EXEC step has an inline comment explaining the program/utility and its function
- Every JCL DD statement for VSAM datasets has an inline comment documenting the dataset and access intent
- Every module README includes a component inventory table, an architecture fit section, and a dependency list

**Accuracy Validation:**

- Inline comments must accurately reflect the code behavior as-is — no aspirational or corrective documentation
- VSAM dataset names in documentation must match the actual DD allocations and DEFINE CLUSTER statements
- Program-to-program call chains documented in READMEs must match actual `EXEC CICS XCTL` and `EXEC CICS LINK` statements in source code
- Copybook-to-program usage matrices must match actual `COPY` statements in the COBOL programs
- Record sizes and key structures documented in data file READMEs must match the corresponding copybook PIC clause definitions

**Clarity Standards:**

- Technical accuracy with accessible language — avoid unexplained acronyms on first use (define VSAM, KSDS, CICS, BMS, COMMAREA, etc. in module READMEs)
- Progressive disclosure — module READMEs start with high-level overview, then provide detailed component tables, then cross-references
- Consistent terminology aligned with the existing glossary in `docs/project-guide.md` (Appendix G)
- Inline comments written in present tense, active voice ("Reads the account record from ACCTDAT" rather than "The account record is read")

**Maintainability:**

- Source citations in READMEs reference specific files for traceability (e.g., "Source: `app/cbl/COSGN00C.cbl`")
- Module READMEs structured with consistent heading hierarchy for predictable navigation
- Inline comments placed adjacent to the code they describe — not in separate blocks disconnected from the logic

### 0.7.3 Example and Diagram Requirements

- Minimum 1 Mermaid diagram per module README (architecture, flow, or dependency graph)
- Minimum 1 inventory table per module README (component catalog)
- All Mermaid diagrams render correctly in both GitHub Markdown preview and MkDocs with the mermaid2 plugin
- No external image files are required — all visuals are generated from Mermaid source
- Code examples in READMEs are limited to 2-3 line illustrative snippets showing key patterns (e.g., `EXEC CICS XCTL PROGRAM('COMEN01C')` to illustrate program navigation)

## 0.8 Scope Boundaries

### 0.8.1 Exhaustively In Scope

**New Documentation Files (11 README.md files):**
- `app/README.md` — Application root architectural overview
- `app/cbl/README.md` — COBOL programs catalog and reference
- `app/cpy/README.md` — Copybook library reference
- `app/bms/README.md` — BMS screen definitions catalog
- `app/cpy-bms/README.md` — Symbolic map reference
- `app/jcl/README.md` — JCL operations guide
- `app/data/README.md` — Data fixtures overview
- `app/data/ASCII/README.md` — ASCII file format specifications
- `app/catlg/README.md` — Catalog inventory reference
- `samples/jcl/README.md` — Build sample guide
- `docs/README.md` — Documentation hub overview

**Inline Comment Updates (123 source files):**
- `app/cbl/*.cbl` — 28 COBOL programs (enhanced paragraph-level, CICS, VSAM comments)
- `app/cpy/*.cpy` — 28 copybooks (field-level semantic annotations)
- `app/bms/*.bms` — 17 BMS map definitions (field-group and attribute comments)
- `app/cpy-bms/*.CPY` — 17 symbolic map copybooks (AI/AO pattern and field comments)
- `app/jcl/*.jcl` — 29 JCL jobs (step-level and DD-level comments)
- `samples/jcl/*.jcl` — 3 sample JCL files (enhanced customization guidance)
- `app/cpy/CSUTLDPY.cpy` — Executable paragraphs (validation logic annotations)

**Documentation Configuration (potential updates):**
- `mkdocs.yml` — Navigation update if new `docs/*.md` pages are added

**Documentation Assets:**
- Mermaid diagrams embedded inline in README files (no separate image files)

### 0.8.2 Explicitly Out of Scope

- **Source code logic modifications:** No COBOL program logic, copybook definitions, BMS map layouts, JCL job steps, or data file contents are modified. All changes are strictly additive comments and new documentation files.
- **Root README.md rewrite:** The existing root `README.md` is comprehensive and serves as a reference; it is not rewritten or significantly modified.
- **docs/index.md, docs/project-guide.md, docs/technical-specifications.md:** These existing documentation files are comprehensive and are not modified.
- **CONTRIBUTING.md, CODE_OF_CONDUCT.md, LICENSE:** These governance files are not modified.
- **catalog-info.yaml:** The Backstage service catalog descriptor is not modified.
- **Test file creation or modification:** No test files exist in this COBOL repository; none are created.
- **Feature additions or code refactoring:** Per the Minimal Change Clause, no production code behavior is modified.
- **Deployment configuration changes:** No Docker, CI/CD, or infrastructure files are created or modified (those exist only in the migration target, not this source repository).
- **EBCDIC data files:** Only ASCII data fixtures in `app/data/ASCII/` are documented; any EBCDIC files (referenced in README but not present in this repository snapshot) are not in scope.
- **Generated symbolic map code changes:** The `app/cpy-bms/*.CPY` files are generated/maintained artifacts; inline comments are added but the generated field structures are not modified.
- **External documentation updates:** No updates to external systems (Backstage, GitHub Wiki, external documentation sites).

## 0.9 Execution Parameters

### 0.9.1 Documentation-Specific Instructions

- **Documentation Build Command:** `pip install mkdocs-techdocs-core mkdocs-mermaid2-plugin && mkdocs build` (from repository root where `mkdocs.yml` resides)
- **Documentation Preview Command:** `mkdocs serve` (local preview at `http://localhost:8000`)
- **Diagram Generation:** No separate generation step required — Mermaid diagrams are rendered client-side by the `mermaid2` plugin via fenced code blocks in Markdown
- **Documentation Deployment:** Backstage TechDocs pipeline (auto-renders from `mkdocs.yml` with `backstage.io/techdocs-ref: dir:.` annotation in `catalog-info.yaml`)
- **Default Format:** Markdown (`.md`) with Mermaid diagrams for README files; COBOL column-7 asterisk comments (`*`) for `.cbl` and `.cpy` files; BMS column-1 asterisk comments (`*`) for `.bms` files; JCL inline comments (`//*`) for `.jcl` files
- **Citation Requirement:** Every module README must reference source files by path; inline comments reference related artifacts (e.g., "See COCOM01Y.cpy for COMMAREA layout")
- **Style Guide:** Follow existing repository conventions — COBOL comments in columns 7-72, JCL comments with `//*` prefix, Markdown using GitHub-flavored syntax with ATX-style headings
- **Documentation Validation:** Visual inspection via `mkdocs serve` for README rendering; manual review of inline comments for accuracy against source code behavior
- **Line Length Conventions:** COBOL comments must not exceed column 72 (standard COBOL source format); JCL comments must not exceed column 71 (JCL continuation rules); Markdown has no line-length constraint but should wrap naturally for readability

## 0.10 Rules for Documentation

### 0.10.1 User-Specified Rules

The following documentation rules are derived from the user's explicit requirements and the "Build Verify" implementation rule:

- **Minimal Change Clause:** "Make only the changes absolutely necessary to implement comprehensive code documentation. Add comments and documentation without modifying production code logic or behavior. Do not refactor, optimize, or change existing interfaces. Document existing code as-is."
- **Build Verify — Documentation Standards (Rule E):** "Every module/component must have a short README or docstring explaining: What it does, How to run/build/test, Key configs and defaults, Common failure modes and troubleshooting."
- **Build Verify — Evidence-Based (Rule F):** "Be evidence-based: cite file paths, symbols, and examples."
- **Build Verify — Repository Hygiene (Rule C):** "Follow repository conventions (formatters/linters/tests) if present; never fight existing style."
- **Document existing code as-is:** Inline comments describe current behavior, including any quirks, non-uniform coding patterns, or known edge cases — they do not suggest improvements or corrections.
- **Preserve existing Apache 2.0 license headers exactly:** All license header blocks in every file remain untouched; new comments are added after the license block.
- **Follow existing inline comment conventions:** COBOL programs use column 7 asterisks; JCL jobs use `//*` prefix; BMS maps use column 1 asterisks. No new comment syntax or conventions are introduced.
- **Use consistent terminology from existing glossary:** All documentation uses the same terminology defined in `docs/project-guide.md` Appendix G (VSAM KSDS, BMS, COMMAREA, TDQ, GDG, COMP-3, SYNCPOINT, FILE STATUS, etc.).
- **Include Mermaid diagrams for architecture and flow documentation:** Module READMEs use Mermaid fenced code blocks for visual diagrams, consistent with the `mermaid2` plugin configured in `mkdocs.yml`.
- **Add source code citations for all technical details:** Every factual claim in a README must be traceable to a specific source file (e.g., "Source: `app/cbl/COSGN00C.cbl`").
- **No dead code, no unused imports, no TODOs without owners:** Per Build Verify Rule B, any TODO comments added must include a tracking reference or owner. However, since this is a documentation-only task, no TODOs should be necessary.
- **No secrets in code, logs, tests, or config:** Per Build Verify Rule D, no documentation should expose credentials, tokens, or sensitive configuration values. The existing demo credentials documented in the root README (`ADMIN001/PASSWORD`, `USER0001/PASSWORD`) are synthetic demo data and are acceptable to reference.

## 0.11 References

### 0.11.1 Repository Files and Folders Searched

**Root-Level Files Examined:**

| File | Purpose | Key Findings |
|------|---------|--------------|
| `README.md` | Application overview and installation guide | Comprehensive mainframe documentation with inventory tables, batch execution order, screen screenshots; serves as primary content reference |
| `CONTRIBUTING.md` | Contributor guidelines | Complete; out of scope for modification |
| `CODE_OF_CONDUCT.md` | Behavioral policy | Complete; out of scope for modification |
| `LICENSE` | Apache License 2.0 text | Legal framework; all files inherit this license |
| `mkdocs.yml` | MkDocs site configuration | Site name: `blitzy-card-demo`; plugins: `techdocs-core`, `mermaid2`; nav: 3 pages |
| `catalog-info.yaml` | Backstage catalog entity descriptor | Component metadata, TechDocs reference, GitHub annotations |

**Documentation Files Examined:**

| File | Purpose | Key Findings |
|------|---------|--------------|
| `docs/index.md` | Documentation landing page | Single-line description only — minimal |
| `docs/project-guide.md` | Project guide and migration status | 581 lines covering completion status, test results, dev guide, appendices with glossary and tech versions |
| `docs/technical-specifications.md` | Technical migration blueprint | Comprehensive technical spec; contains existing Agent Action Plan from prior migration work |

**Application Module Folders Explored:**

| Folder | Children Count | Type | Assessment |
|--------|---------------|------|------------|
| `app/` | 7 subfolders | Application root | No README; needs architectural overview |
| `app/cbl/` | 28 .cbl files | COBOL programs | No README; minimal inline comments (headers only) |
| `app/cpy/` | 28 .cpy files | Shared copybooks | No README; license headers only |
| `app/bms/` | 17 .bms files | BMS map definitions | No README; 1-line screen titles only |
| `app/cpy-bms/` | 17 .CPY files | Symbolic map copybooks | No README; zero documentation |
| `app/jcl/` | 29 .jcl files | JCL jobs | No README; inconsistent inline comments |
| `app/data/` | 1 subfolder (ASCII) | Data fixture container | No README; no format documentation |
| `app/data/ASCII/` | 9 .txt files | ASCII fixture data | No README; no field specifications |
| `app/catlg/` | 1 .txt file | Catalog inventory | No README; raw IDCAMS output |
| `samples/jcl/` | 3 .jcl files | Sample build JCL | No README; good inline guidance in files |
| `docs/` | 3 .md files | Documentation hub | No README; content is comprehensive |

**Source Files Sampled for Inline Comment Analysis:**

| File | Lines Examined | Comment Pattern Observed |
|------|----------------|--------------------------|
| `app/cbl/COSGN00C.cbl` | 1-40 | Standard header block (6 lines: Program, Application, Type, Function) + Apache license + section dividers |
| `app/cbl/CBTRN02C.cbl` | 1-40 | Standard header block + Apache license + FILE-CONTROL section (no inline comments on field assignments) |
| `app/cpy/COCOM01Y.cpy` | 1-30 | 1-line purpose comment + Apache license + field definitions (no field-level comments) |
| `app/bms/COSGN00.bms` | 1-30 | 1-line screen title + Apache license + DFHMSD/DFHMDI/DFHMDF macros (no inline explanations) |
| `app/jcl/POSTTRAN.jcl` | 1-30 | JOB card + Apache license + 2-line descriptive comment + DD statements (no DD-level comments) |

### 0.11.2 Technical Specification Sections Retrieved

| Section | Key Information Extracted |
|---------|--------------------------|
| 1.1 Executive Summary | Project overview, migration scope (28 programs, 149 artifacts), stakeholder groups, business impact |
| 1.2 System Overview | Source/target technology stacks, system capabilities, core technical approach, design patterns |
| 1.3 Scope | In-scope features (F-001 through F-022), source artifact coverage, target deliverables, out-of-scope items |
| 3.1 Programming Languages | Java 25 LTS target, COBOL source reference, SQL for PostgreSQL |
| 3.2 Frameworks and Libraries | Spring Boot 3.5.11 BOM, Spring MVC/JPA/Batch/Security, Spring Cloud AWS 3.3.0, testing libraries |
| 3.6 Development and Deployment | Build tools (Maven 3.9.9), Docker Compose 6-service configuration, Spring profiles, environment variables |
| 5.2 Component Details | REST API layer (8 controllers), service layer (1:1 COBOL mapping), batch processing (5-stage pipeline), data persistence (11 JPA entities), security layer, observability stack, AWS integration |

### 0.11.3 Web Sources Consulted

| Source | Information Retrieved |
|--------|----------------------|
| PyPI — mkdocs-techdocs-core | Latest version: 1.6.1 (December 2025); Backstage TechDocs wrapper plugin packaging Material theme, monorepo plugin, and Python Markdown extensions |
| PyPI — mkdocs-mermaid2-plugin | Latest version: 1.2.3; supports Mermaid.js >= 10; renders diagrams from fenced code blocks in Markdown |
| GitHub — backstage/mkdocs-techdocs-core | Plugin documentation, included extensions list, configuration patterns |
| Read the Docs — mkdocs-mermaid2 | Plugin usage, Material theme integration, diagram configuration options |

### 0.11.4 Attachments and External Metadata

- **User Attachments:** None provided (0 attachments)
- **Figma URLs:** None specified
- **Environment Files:** None provided in `/tmp/environments_files`
- **Setup Instructions:** None provided by user
- **Environment Variables:** None configured
- **Secrets:** None configured
- **Implementation Rules Applied:** "Build Verify" — Global coding and design standards covering engineering principles, code quality, repository hygiene, security, documentation standards, and output requirements

