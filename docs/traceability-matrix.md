# CardDemo Traceability Matrix (COBOL &rarr; Java)

> **Bidirectional, 100%-coverage mapping** between the legacy AWS CardDemo COBOL
> application and its Java 25 / Spring Boot 3.x migration target
> (package root `com.carddemo`). This document is the authoritative
> **Explainability** artifact required by the migration rules and is a
> **precondition of Gate&nbsp;8 integration sign-off**.

## Purpose

This matrix guarantees that **every COBOL paragraph and construct** across all
**28 source programs** is accounted for in the Java implementation, and that
**every Java class** can be traced **back** to its COBOL origin. It exists so
that reviewers, auditors, and future maintainers can verify behavioural parity
paragraph-by-paragraph without reading both code bases side by side.

## Source-of-truth &amp; SHA reference rule (read first)

- The COBOL source is **NOT copied** into this repository. It is referenced
  **only** by the original CardDemo repository at commit SHA
  **`27d6c6f`** (full **`7756d895ffeb65f7ea72aaa609e356d9899afcec`**).
- Every `COBOL Program` / `COBOL Paragraph` cell below denotes a construct in
  that frozen source tree (`app/cbl/*.cbl` at `27d6c6f`). Paragraph names are
  reproduced verbatim from the source; **no COBOL source code is embedded** here
  beyond short paragraph identifiers.
- The five largest programs by lines of code &mdash; `COACTUPC` (4,236),
  `COCRDUPC` (1,560), `COCRDLIC` (1,459), `COACTVWC` (941), and `CBSTM03A` (924)
  &mdash; therefore carry the most rows.

## How to read this matrix

- **Section&nbsp;1 &mdash; Forward Matrix (COBOL &rarr; Java):** one table per program.
  Columns are exactly `COBOL Program | COBOL Paragraph / Construct | Java Class |
  Java Method | Notes`. **Every** paragraph of **every** program appears.
- Numbered "`*-EXIT`" labels are `PERFORM ... THRU` range terminators; in
  structured Java they collapse into the **end of the corresponding method** and
  are mapped to that method with an explicit boundary note (no separate Java code).
- Repeated helper paragraphs that the migration merged into a single method share
  that method; the paragraph name still appears on its own row (coverage is
  per-paragraph, never elided).
- **Section&nbsp;2 &mdash; Construct Mappings:** non-paragraph constructs
  (`FILE STATUS`, `SYNCPOINT ROLLBACK`, `SEND`/`RECEIVE MAP`, `CALL`, DFSORT,
  `COPY`, JCL, GDG, TDQ, CEEDAYS).
- **Section&nbsp;3 &mdash; Copybook &rarr; Entity/DTO mapping.**
- **Section&nbsp;4 &mdash; Reverse Index (Java &rarr; COBOL):** Part&nbsp;A maps
  paragraph-derived classes back to their paragraphs; Part&nbsp;B maps the
  remaining classes (entities, jobs, config, observability, DTOs, enums) back to
  their originating construct/copybook &mdash; together satisfying the
  **bidirectionality** requirement.
- **Section&nbsp;5 &mdash; Coverage Summary:** per-program paragraph counts and the
  100% assertion.

Decision IDs cited in the `Notes` column (for example `D-002`, `D-004`) are
defined in the [decision log](./decision-log.md). The architectural context is
in the [architecture overview](./architecture/overview.md).

## Scope summary

| Metric | Value |
|---|---|
| COBOL programs mapped | **28** (17 online CICS + 11 batch/utility) |
| COBOL paragraphs mapped (forward) | **527** (100%) |
| Record-layout copybooks &rarr; JPA entities | **11 &rarr; 11** |
| Java classes traced back (reverse) | **126** (Part&nbsp;A 49 + Part&nbsp;B 77) |
| Source reference | commit `27d6c6f` (`7756d895ffeb65f7ea72aaa609e356d9899afcec`) &mdash; not copied |

> **Ground-truth note.** Program tiering and counts are derived by direct
> inspection of the source at `27d6c6f` (presence of `EXEC CICS` &rarr; online).
> This yields **17 online + 11 batch = 28**. Where narrative documentation
> elsewhere says "18 online", the ground-truth **17** is authoritative here.

## 1. Forward Matrix (COBOL &rarr; Java)

### 1.1 Online (CICS) programs &mdash; 17

#### COSGN00C.cbl — Signon (Txn `CC00`)

_Paragraph count: **6** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COSGN00C.cbl` | `MAIN-PARA` | `AuthController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COSGN00C.cbl` | `PROCESS-ENTER-KEY` | `SignonService` | `authenticate()` | ENTER key handler → primary use-case method: Validate credentials; BCrypt verify replaces plaintext compare (D-002); JWT issued via JwtService |
| `COSGN00C.cbl` | `SEND-SIGNON-SCREEN` | `AuthController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COSGN00C.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COSGN00C.cbl` | `POPULATE-HEADER-INFO` | `SignonService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COSGN00C.cbl` | `READ-USER-SEC-FILE` | `UserSecurityRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |

#### COMEN01C.cbl — Main Menu (Txn `CM00`)

_Paragraph count: **7** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COMEN01C.cbl` | `MAIN-PARA` | `MenuController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COMEN01C.cbl` | `PROCESS-ENTER-KEY` | `MenuService` | `routeMainMenuSelection()` | ENTER key handler → primary use-case method: Menu option selection → stateless route; XCTL to target program → client-driven navigation |
| `COMEN01C.cbl` | `RETURN-TO-SIGNON-SCREEN` | `AuthController` | (401 / redirect) | XCTL to COSGN00C → client redirected to signon (session invalid) |
| `COMEN01C.cbl` | `SEND-MENU-SCREEN` | `MenuController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COMEN01C.cbl` | `RECEIVE-MENU-SCREEN` | `MenuController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COMEN01C.cbl` | `POPULATE-HEADER-INFO` | `MenuService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COMEN01C.cbl` | `BUILD-MENU-OPTIONS` | `MenuService` | `buildMenuOptions()` | Menu-option array build → List&lt;MenuOption&gt; (role-filtered) |

#### COADM01C.cbl — Admin Menu (Txn `CA00`)

_Paragraph count: **7** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COADM01C.cbl` | `MAIN-PARA` | `MenuController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COADM01C.cbl` | `PROCESS-ENTER-KEY` | `MenuService` | `routeAdminMenuSelection()` | ENTER key handler → primary use-case method: Admin menu option selection → stateless route (admin-only options) |
| `COADM01C.cbl` | `RETURN-TO-SIGNON-SCREEN` | `AuthController` | (401 / redirect) | XCTL to COSGN00C → client redirected to signon (session invalid) |
| `COADM01C.cbl` | `SEND-MENU-SCREEN` | `MenuController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COADM01C.cbl` | `RECEIVE-MENU-SCREEN` | `MenuController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COADM01C.cbl` | `POPULATE-HEADER-INFO` | `MenuService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COADM01C.cbl` | `BUILD-MENU-OPTIONS` | `MenuService` | `buildMenuOptions()` | Menu-option array build → List&lt;MenuOption&gt; (role-filtered) |

#### COACTVWC.cbl — Account View (Txn `CAVW`)

_Paragraph count: **34** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTVWC.cbl` | `0000-MAIN` | `AccountController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COACTVWC.cbl` | `COMMON-RETURN` | `AccountController` | (HTTP response) | EXEC CICS RETURN (COMMAREA/TRANSID) → HTTP response; conversational state externalised to JWT |
| `COACTVWC.cbl` | `0000-MAIN-EXIT` | `AccountController` | (request dispatch) | PERFORM ... THRU 0000-MAIN-EXIT range terminator → end of (request dispatch) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `1000-SEND-MAP` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTVWC.cbl` | `1000-SEND-MAP-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 1000-SEND-MAP-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `1100-SCREEN-INIT` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTVWC.cbl` | `1100-SCREEN-INIT-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 1100-SCREEN-INIT-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `1200-SETUP-SCREEN-VARS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTVWC.cbl` | `1200-SETUP-SCREEN-VARS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 1200-SETUP-SCREEN-VARS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTVWC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 1300-SETUP-SCREEN-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `1400-SEND-SCREEN` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTVWC.cbl` | `1400-SEND-SCREEN-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 1400-SEND-SCREEN-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `2000-PROCESS-INPUTS` | `AccountViewService` | `processInputs()` | Receive+edit+decide orchestration for the request |
| `COACTVWC.cbl` | `2000-PROCESS-INPUTS-EXIT` | `AccountViewService` | `processInputs()` | PERFORM ... THRU 2000-PROCESS-INPUTS-EXIT range terminator → end of processInputs() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `2100-RECEIVE-MAP` | `AccountController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COACTVWC.cbl` | `2100-RECEIVE-MAP-EXIT` | `AccountController` | (request binding) | PERFORM ... THRU 2100-RECEIVE-MAP-EXIT range terminator → end of (request binding) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS` | `AccountViewService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS-EXIT` | `AccountViewService` | `validate()` | PERFORM ... THRU 2200-EDIT-MAP-INPUTS-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `2210-EDIT-ACCOUNT` | `AccountViewService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTVWC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `AccountViewService` | `validate()` | PERFORM ... THRU 2210-EDIT-ACCOUNT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `9000-READ-ACCT` | `AccountRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTVWC.cbl` | `9000-READ-ACCT-EXIT` | `AccountRepository` | `findById()` | PERFORM ... THRU 9000-READ-ACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT` | `CardXrefRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT-EXIT` | `CardXrefRepository` | `findById()` | PERFORM ... THRU 9200-GETCARDXREF-BYACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `9300-GETACCTDATA-BYACCT` | `AccountRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTVWC.cbl` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountRepository` | `findById()` | PERFORM ... THRU 9300-GETACCTDATA-BYACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST` | `CustomerRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST-EXIT` | `CustomerRepository` | `findById()` | PERFORM ... THRU 9400-GETCUSTDATA-BYCUST-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COACTVWC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `GlobalExceptionHandler` | (error response) | PERFORM ... THRU SEND-PLAIN-TEXT-EXIT range terminator → end of (error response) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `SEND-LONG-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COACTVWC.cbl` | `SEND-LONG-TEXT-EXIT` | `GlobalExceptionHandler` | (error response) | PERFORM ... THRU SEND-LONG-TEXT-EXIT range terminator → end of (error response) (structured method boundary; no separate Java code) |
| `COACTVWC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | (exception mapping) | ABEND-ROUTINE → RuntimeException mapped to ErrorResponse + structured log (MDC correlationId) |

#### COACTUPC.cbl — Account Update (Txn `CAUP`)

_Paragraph count: **85** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTUPC.cbl` | `0000-MAIN` | `AccountController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COACTUPC.cbl` | `COMMON-RETURN` | `AccountController` | (HTTP response) | EXEC CICS RETURN (COMMAREA/TRANSID) → HTTP response; conversational state externalised to JWT |
| `COACTUPC.cbl` | `0000-MAIN-EXIT` | `AccountController` | (request dispatch) | PERFORM ... THRU 0000-MAIN-EXIT range terminator → end of (request dispatch) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1000-PROCESS-INPUTS` | `AccountUpdateService` | `processInputs()` | Receive+edit+decide orchestration for the request |
| `COACTUPC.cbl` | `1000-PROCESS-INPUTS-EXIT` | `AccountUpdateService` | `processInputs()` | PERFORM ... THRU 1000-PROCESS-INPUTS-EXIT range terminator → end of processInputs() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1100-RECEIVE-MAP` | `AccountController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COACTUPC.cbl` | `1100-RECEIVE-MAP-EXIT` | `AccountController` | (request binding) | PERFORM ... THRU 1100-RECEIVE-MAP-EXIT range terminator → end of (request binding) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1200-EDIT-MAP-INPUTS-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW` | `AccountUpdateService` | `detectChanges()` | Compare screen values vs fetched record → change detection feeding the @Version optimistic-lock update |
| `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW-EXIT` | `AccountUpdateService` | `detectChanges()` | PERFORM ... THRU 1205-COMPARE-OLD-NEW-EXIT range terminator → end of detectChanges() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1210-EDIT-ACCOUNT` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1210-EDIT-ACCOUNT-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1210-EDIT-ACCOUNT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1215-EDIT-MANDATORY` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1215-EDIT-MANDATORY-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1215-EDIT-MANDATORY-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1220-EDIT-YESNO` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1220-EDIT-YESNO-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1220-EDIT-YESNO-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1225-EDIT-ALPHA-REQD` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1225-EDIT-ALPHA-REQD-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1225-EDIT-ALPHA-REQD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1230-EDIT-ALPHANUM-REQD` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1230-EDIT-ALPHANUM-REQD-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1230-EDIT-ALPHANUM-REQD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1235-EDIT-ALPHA-OPT` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1235-EDIT-ALPHA-OPT-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1235-EDIT-ALPHA-OPT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1240-EDIT-ALPHANUM-OPT` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1240-EDIT-ALPHANUM-OPT-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1240-EDIT-ALPHANUM-OPT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1245-EDIT-NUM-REQD` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1245-EDIT-NUM-REQD-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1245-EDIT-NUM-REQD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1250-EDIT-SIGNED-9V2` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1250-EDIT-SIGNED-9V2-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1250-EDIT-SIGNED-9V2-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1260-EDIT-US-PHONE-NUM` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `EDIT-AREA-CODE` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `EDIT-US-PHONE-PREFIX` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `EDIT-US-PHONE-LINENUM` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `EDIT-US-PHONE-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU EDIT-US-PHONE-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1260-EDIT-US-PHONE-NUM-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1260-EDIT-US-PHONE-NUM-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1265-EDIT-US-SSN` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1265-EDIT-US-SSN-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1265-EDIT-US-SSN-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1270-EDIT-US-STATE-CD` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1270-EDIT-US-STATE-CD-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1270-EDIT-US-STATE-CD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1275-EDIT-FICO-SCORE` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1275-EDIT-FICO-SCORE-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1275-EDIT-FICO-SCORE-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `1280-EDIT-US-STATE-ZIP-CD` | `AccountUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COACTUPC.cbl` | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `AccountUpdateService` | `validate()` | PERFORM ... THRU 1280-EDIT-US-STATE-ZIP-CD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `2000-DECIDE-ACTION` | `AccountUpdateService` | `updateAccount()` | EVALUATE action dispatch (view/edit/confirm/update) → service branch; branch order preserved |
| `COACTUPC.cbl` | `2000-DECIDE-ACTION-EXIT` | `AccountUpdateService` | `updateAccount()` | PERFORM ... THRU 2000-DECIDE-ACTION-EXIT range terminator → end of updateAccount() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3000-SEND-MAP` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3000-SEND-MAP-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3000-SEND-MAP-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3100-SCREEN-INIT` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3100-SCREEN-INIT-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3100-SCREEN-INIT-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3200-SETUP-SCREEN-VARS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3200-SETUP-SCREEN-VARS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3200-SETUP-SCREEN-VARS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3201-SHOW-INITIAL-VALUES` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3201-SHOW-INITIAL-VALUES-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3201-SHOW-INITIAL-VALUES-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3202-SHOW-ORIGINAL-VALUES` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3202-SHOW-ORIGINAL-VALUES-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3203-SHOW-UPDATED-VALUES` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3203-SHOW-UPDATED-VALUES-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3203-SHOW-UPDATED-VALUES-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3250-SETUP-INFOMSG` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3250-SETUP-INFOMSG-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3250-SETUP-INFOMSG-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3300-SETUP-SCREEN-ATTRS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3300-SETUP-SCREEN-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3310-PROTECT-ALL-ATTRS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3310-PROTECT-ALL-ATTRS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3310-PROTECT-ALL-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3320-UNPROTECT-FEW-ATTRS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3320-UNPROTECT-FEW-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3390-SETUP-INFOMSG-ATTRS` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3390-SETUP-INFOMSG-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `3400-SEND-SCREEN` | `AccountController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COACTUPC.cbl` | `3400-SEND-SCREEN-EXIT` | `AccountController` | (response assembly) | PERFORM ... THRU 3400-SEND-SCREEN-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9000-READ-ACCT` | `AccountRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTUPC.cbl` | `9000-READ-ACCT-EXIT` | `AccountRepository` | `findById()` | PERFORM ... THRU 9000-READ-ACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9200-GETCARDXREF-BYACCT` | `CardXrefRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTUPC.cbl` | `9200-GETCARDXREF-BYACCT-EXIT` | `CardXrefRepository` | `findById()` | PERFORM ... THRU 9200-GETCARDXREF-BYACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9300-GETACCTDATA-BYACCT` | `AccountRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTUPC.cbl` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountRepository` | `findById()` | PERFORM ... THRU 9300-GETACCTDATA-BYACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9400-GETCUSTDATA-BYCUST` | `CustomerRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COACTUPC.cbl` | `9400-GETCUSTDATA-BYCUST-EXIT` | `CustomerRepository` | `findById()` | PERFORM ... THRU 9400-GETCUSTDATA-BYCUST-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA` | `AccountUpdateService` | `populateModel()` | Move fetched account/customer/xref rows into the working/response model |
| `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA-EXIT` | `AccountUpdateService` | `populateModel()` | PERFORM ... THRU 9500-STORE-FETCHED-DATA-EXIT range terminator → end of populateModel() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9600-WRITE-PROCESSING` | `AccountRepository` | `save()` | Read-verify-then-REWRITE → save() guarded by @Version optimistic lock |
| `COACTUPC.cbl` | `9600-WRITE-PROCESSING-EXIT` | `AccountRepository` | `save()` | PERFORM ... THRU 9600-WRITE-PROCESSING-EXIT range terminator → end of save() (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC` | `OptimisticLockConflictException` | (guard) | Re-read compare (record changed?) → @Version optimistic-lock check; 409 on conflict |
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC-EXIT` | `OptimisticLockConflictException` | (guard) | PERFORM ... THRU 9700-CHECK-CHANGE-IN-REC-EXIT range terminator → end of (guard) (structured method boundary; no separate Java code) |
| `COACTUPC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | (exception mapping) | ABEND-ROUTINE → RuntimeException mapped to ErrorResponse + structured log (MDC correlationId) |
| `COACTUPC.cbl` | `ABEND-ROUTINE-EXIT` | `GlobalExceptionHandler` | (exception mapping) | PERFORM ... THRU ABEND-ROUTINE-EXIT range terminator → end of (exception mapping) (structured method boundary; no separate Java code) |

#### COCRDLIC.cbl — Card List (Txn `CCLI`)

_Paragraph count: **39** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDLIC.cbl` | `0000-MAIN` | `CardController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COCRDLIC.cbl` | `COMMON-RETURN` | `CardController` | (HTTP response) | EXEC CICS RETURN (COMMAREA/TRANSID) → HTTP response; conversational state externalised to JWT |
| `COCRDLIC.cbl` | `0000-MAIN-EXIT` | `CardController` | (request dispatch) | PERFORM ... THRU 0000-MAIN-EXIT range terminator → end of (request dispatch) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1000-SEND-MAP` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1000-SEND-MAP-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1000-SEND-MAP-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1100-SCREEN-INIT` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1100-SCREEN-INIT-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1100-SCREEN-INIT-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1200-SCREEN-ARRAY-INIT` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1200-SCREEN-ARRAY-INIT-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1200-SCREEN-ARRAY-INIT-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1250-SETUP-ARRAY-ATTRIBS` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1250-SETUP-ARRAY-ATTRIBS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1300-SETUP-SCREEN-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1400-SETUP-MESSAGE` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1400-SETUP-MESSAGE-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1400-SETUP-MESSAGE-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `1500-SEND-SCREEN` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDLIC.cbl` | `1500-SEND-SCREEN-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1500-SEND-SCREEN-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `2000-RECEIVE-MAP` | `CardController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COCRDLIC.cbl` | `2000-RECEIVE-MAP-EXIT` | `CardController` | (request binding) | PERFORM ... THRU 2000-RECEIVE-MAP-EXIT range terminator → end of (request binding) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `2100-RECEIVE-SCREEN` | `CardController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COCRDLIC.cbl` | `2100-RECEIVE-SCREEN-EXIT` | `CardController` | (request binding) | PERFORM ... THRU 2100-RECEIVE-SCREEN-EXIT range terminator → end of (request binding) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `2200-EDIT-INPUTS` | `CardListService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDLIC.cbl` | `2200-EDIT-INPUTS-EXIT` | `CardListService` | `validate()` | PERFORM ... THRU 2200-EDIT-INPUTS-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `2210-EDIT-ACCOUNT` | `CardListService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDLIC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `CardListService` | `validate()` | PERFORM ... THRU 2210-EDIT-ACCOUNT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `2220-EDIT-CARD` | `CardListService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDLIC.cbl` | `2220-EDIT-CARD-EXIT` | `CardListService` | `validate()` | PERFORM ... THRU 2220-EDIT-CARD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `2250-EDIT-ARRAY` | `CardListService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDLIC.cbl` | `2250-EDIT-ARRAY-EXIT` | `CardListService` | `validate()` | PERFORM ... THRU 2250-EDIT-ARRAY-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `9000-READ-FORWARD` | `TransactionRepository` | `findNextPage()` | STARTBR+READNEXT keyset browse → ascending Pageable/keyset query |
| `COCRDLIC.cbl` | `9000-READ-FORWARD-EXIT` | `TransactionRepository` | `findNextPage()` | PERFORM ... THRU 9000-READ-FORWARD-EXIT range terminator → end of findNextPage() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `9100-READ-BACKWARDS` | `TransactionRepository` | `findPreviousPage()` | STARTBR+READPREV keyset browse → descending Pageable/keyset query |
| `COCRDLIC.cbl` | `9100-READ-BACKWARDS-EXIT` | `TransactionRepository` | `findPreviousPage()` | PERFORM ... THRU 9100-READ-BACKWARDS-EXIT range terminator → end of findPreviousPage() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `9500-FILTER-RECORDS` | `CardListService` | `applyFilters()` | Client-side record filtering → repository predicate/derived query |
| `COCRDLIC.cbl` | `9500-FILTER-RECORDS-EXIT` | `CardListService` | `applyFilters()` | PERFORM ... THRU 9500-FILTER-RECORDS-EXIT range terminator → end of applyFilters() (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COCRDLIC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `GlobalExceptionHandler` | (error response) | PERFORM ... THRU SEND-PLAIN-TEXT-EXIT range terminator → end of (error response) (structured method boundary; no separate Java code) |
| `COCRDLIC.cbl` | `SEND-LONG-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COCRDLIC.cbl` | `SEND-LONG-TEXT-EXIT` | `GlobalExceptionHandler` | (error response) | PERFORM ... THRU SEND-LONG-TEXT-EXIT range terminator → end of (error response) (structured method boundary; no separate Java code) |

#### COCRDSLC.cbl — Card View (Txn `CCDL`)

_Paragraph count: **34** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDSLC.cbl` | `0000-MAIN` | `CardController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COCRDSLC.cbl` | `COMMON-RETURN` | `CardController` | (HTTP response) | EXEC CICS RETURN (COMMAREA/TRANSID) → HTTP response; conversational state externalised to JWT |
| `COCRDSLC.cbl` | `0000-MAIN-EXIT` | `CardController` | (request dispatch) | PERFORM ... THRU 0000-MAIN-EXIT range terminator → end of (request dispatch) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `1000-SEND-MAP` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDSLC.cbl` | `1000-SEND-MAP-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1000-SEND-MAP-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `1100-SCREEN-INIT` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDSLC.cbl` | `1100-SCREEN-INIT-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1100-SCREEN-INIT-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `1200-SETUP-SCREEN-VARS` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDSLC.cbl` | `1200-SETUP-SCREEN-VARS-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1200-SETUP-SCREEN-VARS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDSLC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1300-SETUP-SCREEN-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `1400-SEND-SCREEN` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDSLC.cbl` | `1400-SEND-SCREEN-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 1400-SEND-SCREEN-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `2000-PROCESS-INPUTS` | `CardViewService` | `processInputs()` | Receive+edit+decide orchestration for the request |
| `COCRDSLC.cbl` | `2000-PROCESS-INPUTS-EXIT` | `CardViewService` | `processInputs()` | PERFORM ... THRU 2000-PROCESS-INPUTS-EXIT range terminator → end of processInputs() (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `2100-RECEIVE-MAP` | `CardController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COCRDSLC.cbl` | `2100-RECEIVE-MAP-EXIT` | `CardController` | (request binding) | PERFORM ... THRU 2100-RECEIVE-MAP-EXIT range terminator → end of (request binding) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS` | `CardViewService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS-EXIT` | `CardViewService` | `validate()` | PERFORM ... THRU 2200-EDIT-MAP-INPUTS-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `2210-EDIT-ACCOUNT` | `CardViewService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDSLC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `CardViewService` | `validate()` | PERFORM ... THRU 2210-EDIT-ACCOUNT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `2220-EDIT-CARD` | `CardViewService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDSLC.cbl` | `2220-EDIT-CARD-EXIT` | `CardViewService` | `validate()` | PERFORM ... THRU 2220-EDIT-CARD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `9000-READ-DATA` | `CardViewService` | (helper) | Supporting paragraph folded into CardViewService business logic |
| `COCRDSLC.cbl` | `9000-READ-DATA-EXIT` | `CardViewService` | (helper) | PERFORM ... THRU 9000-READ-DATA-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `9100-GETCARD-BYACCTCARD` | `CardRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COCRDSLC.cbl` | `9100-GETCARD-BYACCTCARD-EXIT` | `CardRepository` | `findById()` | PERFORM ... THRU 9100-GETCARD-BYACCTCARD-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `9150-GETCARD-BYACCT` | `CardRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COCRDSLC.cbl` | `9150-GETCARD-BYACCT-EXIT` | `CardRepository` | `findById()` | PERFORM ... THRU 9150-GETCARD-BYACCT-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `SEND-LONG-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COCRDSLC.cbl` | `SEND-LONG-TEXT-EXIT` | `GlobalExceptionHandler` | (error response) | PERFORM ... THRU SEND-LONG-TEXT-EXIT range terminator → end of (error response) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | (error response) | CICS SEND TEXT (abend/error page) → structured error JSON (ErrorResponse) |
| `COCRDSLC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `GlobalExceptionHandler` | (error response) | PERFORM ... THRU SEND-PLAIN-TEXT-EXIT range terminator → end of (error response) (structured method boundary; no separate Java code) |
| `COCRDSLC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | (exception mapping) | ABEND-ROUTINE → RuntimeException mapped to ErrorResponse + structured log (MDC correlationId) |

#### COCRDUPC.cbl — Card Update (Txn `CCUP`)

_Paragraph count: **45** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDUPC.cbl` | `0000-MAIN` | `CardController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COCRDUPC.cbl` | `COMMON-RETURN` | `CardController` | (HTTP response) | EXEC CICS RETURN (COMMAREA/TRANSID) → HTTP response; conversational state externalised to JWT |
| `COCRDUPC.cbl` | `0000-MAIN-EXIT` | `CardController` | (request dispatch) | PERFORM ... THRU 0000-MAIN-EXIT range terminator → end of (request dispatch) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1000-PROCESS-INPUTS` | `CardUpdateService` | `processInputs()` | Receive+edit+decide orchestration for the request |
| `COCRDUPC.cbl` | `1000-PROCESS-INPUTS-EXIT` | `CardUpdateService` | `processInputs()` | PERFORM ... THRU 1000-PROCESS-INPUTS-EXIT range terminator → end of processInputs() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1100-RECEIVE-MAP` | `CardController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COCRDUPC.cbl` | `1100-RECEIVE-MAP-EXIT` | `CardController` | (request binding) | PERFORM ... THRU 1100-RECEIVE-MAP-EXIT range terminator → end of (request binding) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS` | `CardUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS-EXIT` | `CardUpdateService` | `validate()` | PERFORM ... THRU 1200-EDIT-MAP-INPUTS-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1210-EDIT-ACCOUNT` | `CardUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDUPC.cbl` | `1210-EDIT-ACCOUNT-EXIT` | `CardUpdateService` | `validate()` | PERFORM ... THRU 1210-EDIT-ACCOUNT-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1220-EDIT-CARD` | `CardUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDUPC.cbl` | `1220-EDIT-CARD-EXIT` | `CardUpdateService` | `validate()` | PERFORM ... THRU 1220-EDIT-CARD-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1230-EDIT-NAME` | `CardUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDUPC.cbl` | `1230-EDIT-NAME-EXIT` | `CardUpdateService` | `validate()` | PERFORM ... THRU 1230-EDIT-NAME-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1240-EDIT-CARDSTATUS` | `CardUpdateService` | `validate()` | Field edit rule (mandatory/format/range) → Jakarta Bean Validation + service validation; branch order preserved |
| `COCRDUPC.cbl` | `1240-EDIT-CARDSTATUS-EXIT` | `CardUpdateService` | `validate()` | PERFORM ... THRU 1240-EDIT-CARDSTATUS-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON` | `DateValidationService` | `validate()` | Date/expiry field edit → LocalDate validation (CSUTLDTC equivalent) |
| `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON-EXIT` | `DateValidationService` | `validate()` | PERFORM ... THRU 1250-EDIT-EXPIRY-MON-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `1260-EDIT-EXPIRY-YEAR` | `DateValidationService` | `validate()` | Date/expiry field edit → LocalDate validation (CSUTLDTC equivalent) |
| `COCRDUPC.cbl` | `1260-EDIT-EXPIRY-YEAR-EXIT` | `DateValidationService` | `validate()` | PERFORM ... THRU 1260-EDIT-EXPIRY-YEAR-EXIT range terminator → end of validate() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `2000-DECIDE-ACTION` | `CardUpdateService` | `updateCard()` | EVALUATE action dispatch (view/edit/confirm/update) → service branch; branch order preserved |
| `COCRDUPC.cbl` | `2000-DECIDE-ACTION-EXIT` | `CardUpdateService` | `updateCard()` | PERFORM ... THRU 2000-DECIDE-ACTION-EXIT range terminator → end of updateCard() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `3000-SEND-MAP` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDUPC.cbl` | `3000-SEND-MAP-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 3000-SEND-MAP-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `3100-SCREEN-INIT` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDUPC.cbl` | `3100-SCREEN-INIT-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 3100-SCREEN-INIT-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `3200-SETUP-SCREEN-VARS` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDUPC.cbl` | `3200-SETUP-SCREEN-VARS-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 3200-SETUP-SCREEN-VARS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `3250-SETUP-INFOMSG` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDUPC.cbl` | `3250-SETUP-INFOMSG-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 3250-SETUP-INFOMSG-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `3300-SETUP-SCREEN-ATTRS` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDUPC.cbl` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 3300-SETUP-SCREEN-ATTRS-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `3400-SEND-SCREEN` | `CardController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COCRDUPC.cbl` | `3400-SEND-SCREEN-EXIT` | `CardController` | (response assembly) | PERFORM ... THRU 3400-SEND-SCREEN-EXIT range terminator → end of (response assembly) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `9000-READ-DATA` | `CardUpdateService` | (helper) | Supporting paragraph folded into CardUpdateService business logic |
| `COCRDUPC.cbl` | `9000-READ-DATA-EXIT` | `CardUpdateService` | (helper) | PERFORM ... THRU 9000-READ-DATA-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD` | `CardRepository` | `findById()` | VSAM READ / GET → JpaRepository query (find by key/alternate index) |
| `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD-EXIT` | `CardRepository` | `findById()` | PERFORM ... THRU 9100-GETCARD-BYACCTCARD-EXIT range terminator → end of findById() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `9200-WRITE-PROCESSING` | `AccountRepository` | `save()` | Read-verify-then-REWRITE → save() guarded by @Version optimistic lock |
| `COCRDUPC.cbl` | `9200-WRITE-PROCESSING-EXIT` | `AccountRepository` | `save()` | PERFORM ... THRU 9200-WRITE-PROCESSING-EXIT range terminator → end of save() (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `9300-CHECK-CHANGE-IN-REC` | `OptimisticLockConflictException` | (guard) | Re-read compare (record changed?) → @Version optimistic-lock check; 409 on conflict |
| `COCRDUPC.cbl` | `9300-CHECK-CHANGE-IN-REC-EXIT` | `OptimisticLockConflictException` | (guard) | PERFORM ... THRU 9300-CHECK-CHANGE-IN-REC-EXIT range terminator → end of (guard) (structured method boundary; no separate Java code) |
| `COCRDUPC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | (exception mapping) | ABEND-ROUTINE → RuntimeException mapped to ErrorResponse + structured log (MDC correlationId) |
| `COCRDUPC.cbl` | `ABEND-ROUTINE-EXIT` | `GlobalExceptionHandler` | (exception mapping) | PERFORM ... THRU ABEND-ROUTINE-EXIT range terminator → end of (exception mapping) (structured method boundary; no separate Java code) |

#### COTRN00C.cbl — Transaction List (Txn `CT00`)

_Paragraph count: **16** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN00C.cbl` | `MAIN-PARA` | `TransactionController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COTRN00C.cbl` | `PROCESS-ENTER-KEY` | `TransactionListService` | `listTransactions()` | ENTER key handler → primary use-case method: Paginated transaction list (forward/backward browse) |
| `COTRN00C.cbl` | `PROCESS-PF7-KEY` | `TransactionListService` | `previousPage()` | PF7 (page up) → previous page request |
| `COTRN00C.cbl` | `PROCESS-PF8-KEY` | `TransactionListService` | `nextPage()` | PF8 (page down) → next page request |
| `COTRN00C.cbl` | `PROCESS-PAGE-FORWARD` | `TransactionListService` | `nextPage()` | PF8 (page down) → next page request |
| `COTRN00C.cbl` | `PROCESS-PAGE-BACKWARD` | `TransactionListService` | `previousPage()` | PF7 (page up) → previous page request |
| `COTRN00C.cbl` | `POPULATE-TRAN-DATA` | `TransactionListService` | `toListItem()` | Screen-array row population → DTO list item mapping |
| `COTRN00C.cbl` | `INITIALIZE-TRAN-DATA` | `TransactionListService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COTRN00C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COTRN00C.cbl` | `SEND-TRNLST-SCREEN` | `TransactionController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COTRN00C.cbl` | `RECEIVE-TRNLST-SCREEN` | `TransactionController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COTRN00C.cbl` | `POPULATE-HEADER-INFO` | `TransactionListService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COTRN00C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | (browse start) | VSAM STARTBR → begin keyset/Pageable scan |
| `COTRN00C.cbl` | `READNEXT-TRANSACT-FILE` | `TransactionRepository` | `findNextPage()` | STARTBR+READNEXT keyset browse → ascending Pageable/keyset query |
| `COTRN00C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionRepository` | `findPreviousPage()` | STARTBR+READPREV keyset browse → descending Pageable/keyset query |
| `COTRN00C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | (browse end) | VSAM ENDBR → cursor/scan close (no-op in JPA paging) |

#### COTRN01C.cbl — Transaction View (Txn `CT01`)

_Paragraph count: **9** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN01C.cbl` | `MAIN-PARA` | `TransactionController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COTRN01C.cbl` | `PROCESS-ENTER-KEY` | `TransactionViewService` | `getTransaction()` | ENTER key handler → primary use-case method: Transaction detail view by transaction id |
| `COTRN01C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COTRN01C.cbl` | `SEND-TRNVIEW-SCREEN` | `TransactionController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COTRN01C.cbl` | `RECEIVE-TRNVIEW-SCREEN` | `TransactionController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COTRN01C.cbl` | `POPULATE-HEADER-INFO` | `TransactionViewService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COTRN01C.cbl` | `READ-TRANSACT-FILE` | `TransactionRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COTRN01C.cbl` | `CLEAR-CURRENT-SCREEN` | `TransactionViewService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COTRN01C.cbl` | `INITIALIZE-ALL-FIELDS` | `TransactionViewService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

#### COTRN02C.cbl — Transaction Add (Txn `CT02`)

_Paragraph count: **18** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN02C.cbl` | `MAIN-PARA` | `TransactionController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COTRN02C.cbl` | `PROCESS-ENTER-KEY` | `TransactionAddService` | `addTransaction()` | ENTER key handler → primary use-case method: Add transaction; auto-generated id + confirmation flow |
| `COTRN02C.cbl` | `VALIDATE-INPUT-KEY-FIELDS` | `TransactionAddService` | `validateKeyFields()` | Key-field (acct/card) validation |
| `COTRN02C.cbl` | `VALIDATE-INPUT-DATA-FIELDS` | `TransactionAddService` | `validateDataFields()` | Amount/date/type validation (BigDecimal, LocalDate) |
| `COTRN02C.cbl` | `ADD-TRANSACTION` | `TransactionAddService` | `addTransaction()` | Persist new transaction (auto id) |
| `COTRN02C.cbl` | `COPY-LAST-TRAN-DATA` | `TransactionAddService` | `prefillFromLast()` | Copy previous transaction values into the add form |
| `COTRN02C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COTRN02C.cbl` | `SEND-TRNADD-SCREEN` | `TransactionController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COTRN02C.cbl` | `RECEIVE-TRNADD-SCREEN` | `TransactionController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COTRN02C.cbl` | `POPULATE-HEADER-INFO` | `TransactionAddService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COTRN02C.cbl` | `READ-CXACAIX-FILE` | `CardXrefRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COTRN02C.cbl` | `READ-CCXREF-FILE` | `CardXrefRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COTRN02C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | (browse start) | VSAM STARTBR → begin keyset/Pageable scan |
| `COTRN02C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionRepository` | `findPreviousPage()` | STARTBR+READPREV keyset browse → descending Pageable/keyset query |
| `COTRN02C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | (browse end) | VSAM ENDBR → cursor/scan close (no-op in JPA paging) |
| `COTRN02C.cbl` | `WRITE-TRANSACT-FILE` | `TransactionRepository` | `save()` | VSAM WRITE (insert) → JpaRepository.save() |
| `COTRN02C.cbl` | `CLEAR-CURRENT-SCREEN` | `TransactionAddService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COTRN02C.cbl` | `INITIALIZE-ALL-FIELDS` | `TransactionAddService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

#### CORPT00C.cbl — Transaction Reports (Txn `CR00`)

_Paragraph count: **10** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CORPT00C.cbl` | `MAIN-PARA` | `ReportController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `CORPT00C.cbl` | `PROCESS-ENTER-KEY` | `ReportService` | `requestReport()` | ENTER key handler → primary use-case method: Report request; TDQ→JES bridge becomes SQS FIFO-triggered Spring Batch launch (D-004) |
| `CORPT00C.cbl` | `SUBMIT-JOB-TO-INTRDR` | `ReportJobLauncher` | `enqueueReportJob()` | INTRDR job submit + TDQ WRITEQ → SQS FIFO message → Spring Batch launch (D-004) |
| `CORPT00C.cbl` | `WIRTE-JOBSUB-TDQ` | `ReportJobLauncher` | `enqueueReportJob()` | INTRDR job submit + TDQ WRITEQ → SQS FIFO message → Spring Batch launch (D-004) |
| `CORPT00C.cbl` | `RETURN-TO-PREV-SCREEN` | `ReportController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `CORPT00C.cbl` | `SEND-TRNRPT-SCREEN` | `ReportController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `CORPT00C.cbl` | `RETURN-TO-CICS` | `ReportController` | (HTTP response) | EXEC CICS RETURN → controller returns ResponseEntity |
| `CORPT00C.cbl` | `RECEIVE-TRNRPT-SCREEN` | `ReportController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `CORPT00C.cbl` | `POPULATE-HEADER-INFO` | `ReportService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `CORPT00C.cbl` | `INITIALIZE-ALL-FIELDS` | `ReportService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

#### COBIL00C.cbl — Bill Payment (Txn `CB00`)

_Paragraph count: **16** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COBIL00C.cbl` | `MAIN-PARA` | `BillPaymentController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COBIL00C.cbl` | `PROCESS-ENTER-KEY` | `BillPaymentService` | `processBillPayment()` | ENTER key handler → primary use-case method: Bill payment: balance update + payment transaction under @Transactional |
| `COBIL00C.cbl` | `GET-CURRENT-TIMESTAMP` | `BillPaymentService` | `currentTimestamp()` | EIBTIME/FUNCTION CURRENT-DATE → java.time.LocalDateTime |
| `COBIL00C.cbl` | `RETURN-TO-PREV-SCREEN` | `BillPaymentController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COBIL00C.cbl` | `SEND-BILLPAY-SCREEN` | `BillPaymentController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COBIL00C.cbl` | `RECEIVE-BILLPAY-SCREEN` | `BillPaymentController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COBIL00C.cbl` | `POPULATE-HEADER-INFO` | `BillPaymentService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COBIL00C.cbl` | `READ-ACCTDAT-FILE` | `AccountRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COBIL00C.cbl` | `UPDATE-ACCTDAT-FILE` | `AccountRepository` | `save()` | VSAM REWRITE (update) → JpaRepository.save() (dirty entity) |
| `COBIL00C.cbl` | `READ-CXACAIX-FILE` | `CardXrefRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COBIL00C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | (browse start) | VSAM STARTBR → begin keyset/Pageable scan |
| `COBIL00C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionRepository` | `findPreviousPage()` | STARTBR+READPREV keyset browse → descending Pageable/keyset query |
| `COBIL00C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | (browse end) | VSAM ENDBR → cursor/scan close (no-op in JPA paging) |
| `COBIL00C.cbl` | `WRITE-TRANSACT-FILE` | `TransactionRepository` | `save()` | VSAM WRITE (insert) → JpaRepository.save() |
| `COBIL00C.cbl` | `CLEAR-CURRENT-SCREEN` | `BillPaymentService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COBIL00C.cbl` | `INITIALIZE-ALL-FIELDS` | `BillPaymentService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

#### COUSR00C.cbl — List Users (Txn `CU00`)

_Paragraph count: **16** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR00C.cbl` | `MAIN-PARA` | `UserController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COUSR00C.cbl` | `PROCESS-ENTER-KEY` | `UserService` | `listUsers()` | ENTER key handler → primary use-case method: Paginated user list (USRSEC browse) |
| `COUSR00C.cbl` | `PROCESS-PF7-KEY` | `UserService` | `previousPage()` | PF7 (page up) → previous page request |
| `COUSR00C.cbl` | `PROCESS-PF8-KEY` | `UserService` | `nextPage()` | PF8 (page down) → next page request |
| `COUSR00C.cbl` | `PROCESS-PAGE-FORWARD` | `UserService` | `nextPage()` | PF8 (page down) → next page request |
| `COUSR00C.cbl` | `PROCESS-PAGE-BACKWARD` | `UserService` | `previousPage()` | PF7 (page up) → previous page request |
| `COUSR00C.cbl` | `POPULATE-USER-DATA` | `UserService` | `toListItem()` | Screen-array row population → DTO list item mapping |
| `COUSR00C.cbl` | `INITIALIZE-USER-DATA` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COUSR00C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COUSR00C.cbl` | `SEND-USRLST-SCREEN` | `UserController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COUSR00C.cbl` | `RECEIVE-USRLST-SCREEN` | `UserController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COUSR00C.cbl` | `POPULATE-HEADER-INFO` | `UserService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COUSR00C.cbl` | `STARTBR-USER-SEC-FILE` | `UserSecurityRepository` | (browse start) | VSAM STARTBR → begin keyset/Pageable scan |
| `COUSR00C.cbl` | `READNEXT-USER-SEC-FILE` | `UserSecurityRepository` | `findNextPage()` | STARTBR+READNEXT keyset browse → ascending Pageable/keyset query |
| `COUSR00C.cbl` | `READPREV-USER-SEC-FILE` | `UserSecurityRepository` | `findPreviousPage()` | STARTBR+READPREV keyset browse → descending Pageable/keyset query |
| `COUSR00C.cbl` | `ENDBR-USER-SEC-FILE` | `UserSecurityRepository` | (browse end) | VSAM ENDBR → cursor/scan close (no-op in JPA paging) |

#### COUSR01C.cbl — Add User (Txn `CU01`)

_Paragraph count: **9** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR01C.cbl` | `MAIN-PARA` | `UserController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COUSR01C.cbl` | `PROCESS-ENTER-KEY` | `UserService` | `createUser()` | ENTER key handler → primary use-case method: Add user; BCrypt hash applied on create (D-002) |
| `COUSR01C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COUSR01C.cbl` | `SEND-USRADD-SCREEN` | `UserController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COUSR01C.cbl` | `RECEIVE-USRADD-SCREEN` | `UserController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COUSR01C.cbl` | `POPULATE-HEADER-INFO` | `UserService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COUSR01C.cbl` | `WRITE-USER-SEC-FILE` | `UserSecurityRepository` | `save()` | VSAM WRITE (insert) → JpaRepository.save() |
| `COUSR01C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COUSR01C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

#### COUSR02C.cbl — Update User (Txn `CU02`)

_Paragraph count: **11** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR02C.cbl` | `MAIN-PARA` | `UserController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COUSR02C.cbl` | `PROCESS-ENTER-KEY` | `UserService` | `updateUser()` | ENTER key handler → primary use-case method: Update user attributes |
| `COUSR02C.cbl` | `UPDATE-USER-INFO` | `UserService` | `updateUser()` | Apply user field changes prior to persist |
| `COUSR02C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COUSR02C.cbl` | `SEND-USRUPD-SCREEN` | `UserController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COUSR02C.cbl` | `RECEIVE-USRUPD-SCREEN` | `UserController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COUSR02C.cbl` | `POPULATE-HEADER-INFO` | `UserService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COUSR02C.cbl` | `READ-USER-SEC-FILE` | `UserSecurityRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COUSR02C.cbl` | `UPDATE-USER-SEC-FILE` | `UserSecurityRepository` | `save()` | VSAM REWRITE (update) → JpaRepository.save() (dirty entity) |
| `COUSR02C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COUSR02C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

#### COUSR03C.cbl — Delete User (Txn `CU03`)

_Paragraph count: **11** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR03C.cbl` | `MAIN-PARA` | `UserController` | (request dispatch) | CICS pseudo-conversational entry (EIBCALEN/EIBAID handling) → stateless controller dispatch backed by JWT session state |
| `COUSR03C.cbl` | `PROCESS-ENTER-KEY` | `UserService` | `deleteUser()` | ENTER key handler → primary use-case method: Delete user |
| `COUSR03C.cbl` | `DELETE-USER-INFO` | `UserService` | `deleteUser()` | Confirm+delete user |
| `COUSR03C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | (navigation) | CICS XCTL/RETURN to previous program → client-driven navigation (stateless) |
| `COUSR03C.cbl` | `SEND-USRDEL-SCREEN` | `UserController` | (response assembly) | BMS SEND MAP / screen build &amp; 3270 attributes → JSON response DTO fields (no terminal attributes retained) |
| `COUSR03C.cbl` | `RECEIVE-USRDEL-SCREEN` | `UserController` | (request binding) | BMS RECEIVE MAP → @RequestBody/@RequestParam bound to request DTO (Jakarta Validation) |
| `COUSR03C.cbl` | `POPULATE-HEADER-INFO` | `UserService` | `buildHeader()` | Common header (title/tran-id/program/date/time) → response header fields; date/time via shared util |
| `COUSR03C.cbl` | `READ-USER-SEC-FILE` | `UserSecurityRepository` | `findById()` | VSAM READ (keyed) → JpaRepository.findById() |
| `COUSR03C.cbl` | `DELETE-USER-SEC-FILE` | `UserSecurityRepository` | `deleteById()` | VSAM DELETE → JpaRepository.deleteById() |
| `COUSR03C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |
| `COUSR03C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserService` | `resetForm()` | Clear/initialise screen fields → reset request/response model |

### 1.2 Batch / utility programs &mdash; 11

#### CBTRN02C.cbl — Post Daily Transactions (Job `POSTTRAN`)

_Paragraph count: **26** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN02C.cbl` | `0000-DALYTRAN-OPEN` | `DailyTransactionItemReader` | `open()` | OPEN INPUT/OUTPUT (DailyTransaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN02C.cbl` | `0100-TRANFILE-OPEN` | `DailyTransactionItemReader` | `open()` | OPEN INPUT/OUTPUT (Transaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN02C.cbl` | `0200-XREFFILE-OPEN` | `DailyTransactionItemReader` | `open()` | OPEN INPUT/OUTPUT (CardXref) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN02C.cbl` | `0300-DALYREJS-OPEN` | `DailyTransactionItemReader` | `open()` | OPEN INPUT/OUTPUT (DailyTransaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN02C.cbl` | `0400-ACCTFILE-OPEN` | `DailyTransactionItemReader` | `open()` | OPEN INPUT/OUTPUT (Account) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN02C.cbl` | `0500-TCATBALF-OPEN` | `DailyTransactionItemReader` | `open()` | OPEN INPUT/OUTPUT (TransactionCategoryBalance) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN02C.cbl` | `1000-DALYTRAN-GET-NEXT` | `DailyTransactionItemReader` | `read()` | READ NEXT (DailyTransaction) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBTRN02C.cbl` | `1500-VALIDATE-TRAN` | `PostTransactionProcessor` | `validate()` | 4-stage validation cascade; reject codes preserved via RejectReason enum |
| `CBTRN02C.cbl` | `1500-A-LOOKUP-XREF` | `PostTransactionProcessor` | `lookupXref()` | Card→account xref lookup (CardXrefRepository); reject reason on miss |
| `CBTRN02C.cbl` | `1500-B-LOOKUP-ACCT` | `PostTransactionProcessor` | `lookupAccount()` | Account lookup (AccountRepository); reject reason on miss |
| `CBTRN02C.cbl` | `2000-POST-TRANSACTION` | `PostTransactionProcessor` | `process()` | Post a valid daily transaction (orchestrates category-balance + account-balance updates) |
| `CBTRN02C.cbl` | `2500-WRITE-REJECT-REC` | `PostTransactionItemWriter` | `writeReject()` | Rejected record → reject output; RejectReason + FileStatusCode |
| `CBTRN02C.cbl` | `2700-UPDATE-TCATBAL` | `PostTransactionProcessor` | `upsertCategoryBalance()` | Upsert transaction-category balance (create or update) |
| `CBTRN02C.cbl` | `2700-A-CREATE-TCATBAL-REC` | `TransactionCategoryBalanceRepository` | `save()` | Create category-balance row (insert) |
| `CBTRN02C.cbl` | `2700-B-UPDATE-TCATBAL-REC` | `TransactionCategoryBalanceRepository` | `save()` | Update category-balance row (BigDecimal add) |
| `CBTRN02C.cbl` | `2800-UPDATE-ACCOUNT-REC` | `AccountRepository` | `save()` | Update account balances (BigDecimal, scale 2) |
| `CBTRN02C.cbl` | `2900-WRITE-TRANSACTION-FILE` | `PostTransactionItemWriter` | `write()` | Posted transaction → Transaction table (ItemWriter chunk) |
| `CBTRN02C.cbl` | `9000-DALYTRAN-CLOSE` | `DailyTransactionItemReader` | `close()` | CLOSE (DailyTransaction) → ItemStream.close() |
| `CBTRN02C.cbl` | `9100-TRANFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | CLOSE (Transaction) → ItemStream.close() |
| `CBTRN02C.cbl` | `9200-XREFFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | CLOSE (CardXref) → ItemStream.close() |
| `CBTRN02C.cbl` | `9300-DALYREJS-CLOSE` | `DailyTransactionItemReader` | `close()` | CLOSE (DailyTransaction) → ItemStream.close() |
| `CBTRN02C.cbl` | `9400-ACCTFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | CLOSE (Account) → ItemStream.close() |
| `CBTRN02C.cbl` | `9500-TCATBALF-CLOSE` | `DailyTransactionItemReader` | `close()` | CLOSE (TransactionCategoryBalance) → ItemStream.close() |
| `CBTRN02C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `PostTransactionProcessor` | `formatTimestamp()` | DB2-format timestamp build → LocalDateTime formatting |
| `CBTRN02C.cbl` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBTRN02C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBACT04C.cbl — Interest Calculation (Job `INTCALC`)

_Paragraph count: **22** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT04C.cbl` | `0000-TCATBALF-OPEN` | `InterestAccountItemReader` | `open()` | OPEN INPUT/OUTPUT (TransactionCategoryBalance) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT04C.cbl` | `0100-XREFFILE-OPEN` | `InterestAccountItemReader` | `open()` | OPEN INPUT/OUTPUT (CardXref) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT04C.cbl` | `0200-DISCGRP-OPEN` | `InterestAccountItemReader` | `open()` | OPEN INPUT/OUTPUT (DisclosureGroup) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT04C.cbl` | `0300-ACCTFILE-OPEN` | `InterestAccountItemReader` | `open()` | OPEN INPUT/OUTPUT (Account) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT04C.cbl` | `0400-TRANFILE-OPEN` | `InterestAccountItemReader` | `open()` | OPEN INPUT/OUTPUT (Transaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT04C.cbl` | `1000-TCATBALF-GET-NEXT` | `InterestAccountItemReader` | `read()` | READ NEXT (TransactionCategoryBalance) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBACT04C.cbl` | `1050-UPDATE-ACCOUNT` | `AccountRepository` | `save()` | Persist updated account after interest posting |
| `CBACT04C.cbl` | `1100-GET-ACCT-DATA` | `AccountRepository` | `findById()` | Random account read for the current category balance |
| `CBACT04C.cbl` | `1110-GET-XREF-DATA` | `CardXrefRepository` | `findByXrefAcctId()` | Xref read to resolve card/customer for the account |
| `CBACT04C.cbl` | `1200-GET-INTEREST-RATE` | `InterestCalculationService` | `resolveInterestRate()` | Disclosure-group interest-rate lookup (DisclosureGroupRepository) |
| `CBACT04C.cbl` | `1200-A-GET-DEFAULT-INT-RATE` | `InterestCalculationService` | `resolveInterestRate()` | Default disclosure-group interest-rate fallback (default-group branch within resolveInterestRate) |
| `CBACT04C.cbl` | `1300-COMPUTE-INTEREST` | `InterestCalculationService` | `calculateMonthlyInterest()` | Monthly interest = balance*rate/1200; BigDecimal setScale(2, HALF_UP) |
| `CBACT04C.cbl` | `1300-B-WRITE-TX` | `PostTransactionItemWriter` | `write()` | Interest transaction → Transaction table |
| `CBACT04C.cbl` | `1400-COMPUTE-FEES` | `InterestCalculationService` | `applyInterestToAccount()` | Fee/interest application to the account; BigDecimal scale 2 |
| `CBACT04C.cbl` | `9000-TCATBALF-CLOSE` | `InterestAccountItemReader` | `close()` | CLOSE (TransactionCategoryBalance) → ItemStream.close() |
| `CBACT04C.cbl` | `9100-XREFFILE-CLOSE` | `InterestAccountItemReader` | `close()` | CLOSE (CardXref) → ItemStream.close() |
| `CBACT04C.cbl` | `9200-DISCGRP-CLOSE` | `InterestAccountItemReader` | `close()` | CLOSE (DisclosureGroup) → ItemStream.close() |
| `CBACT04C.cbl` | `9300-ACCTFILE-CLOSE` | `InterestAccountItemReader` | `close()` | CLOSE (Account) → ItemStream.close() |
| `CBACT04C.cbl` | `9400-TRANFILE-CLOSE` | `InterestAccountItemReader` | `close()` | CLOSE (Transaction) → ItemStream.close() |
| `CBACT04C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `InterestCalculationService` | (helper) | Supporting paragraph folded into InterestCalculationService logic |
| `CBACT04C.cbl` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBACT04C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBTRN03C.cbl — Transaction Detail Report (Job `TRANREPT`)

_Paragraph count: **26** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN03C.cbl` | `0550-DATEPARM-READ` | `TransactionReportItemReader` | `readDateParams()` | Read date-range parameter record → job parameters / reader bounds |
| `CBTRN03C.cbl` | `1000-TRANFILE-GET-NEXT` | `TransactionReportItemReader` | `read()` | READ NEXT (Transaction) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBTRN03C.cbl` | `1100-WRITE-TRANSACTION-REPORT` | `TransactionReportItemWriter` | `write()` | Emit report detail lines for a transaction |
| `CBTRN03C.cbl` | `1110-WRITE-PAGE-TOTALS` | `TransactionReportProcessor` | `accumulatePageTotals()` | Page subtotal accumulation (BigDecimal) |
| `CBTRN03C.cbl` | `1120-WRITE-ACCOUNT-TOTALS` | `TransactionReportProcessor` | `accumulateAccountTotals()` | Per-account total accumulation (control break) |
| `CBTRN03C.cbl` | `1110-WRITE-GRAND-TOTALS` | `TransactionReportItemWriter` | `writeGrandTotals()` | Grand-total trailer line |
| `CBTRN03C.cbl` | `1120-WRITE-HEADERS` | `TransactionReportItemWriter` | `writeHeader()` | Report header/banner line |
| `CBTRN03C.cbl` | `1111-WRITE-REPORT-REC` | `TransactionReportItemWriter` | `writeLine()` | Write a formatted report record (ReportDetailLine) |
| `CBTRN03C.cbl` | `1120-WRITE-DETAIL` | `TransactionReportItemWriter` | `writeDetail()` | Write transaction detail line (ReportDetailLine) |
| `CBTRN03C.cbl` | `0000-TRANFILE-OPEN` | `TransactionReportItemReader` | `open()` | OPEN INPUT/OUTPUT (Transaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN03C.cbl` | `0100-REPTFILE-OPEN` | `TransactionReportItemReader` | `open()` | OPEN INPUT/OUTPUT (Transaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN03C.cbl` | `0200-CARDXREF-OPEN` | `TransactionReportItemReader` | `open()` | OPEN INPUT/OUTPUT (CardXref) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN03C.cbl` | `0300-TRANTYPE-OPEN` | `TransactionReportItemReader` | `open()` | OPEN INPUT/OUTPUT (TransactionType) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN03C.cbl` | `0400-TRANCATG-OPEN` | `TransactionReportItemReader` | `open()` | OPEN INPUT/OUTPUT (TransactionCategoryType) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN03C.cbl` | `0500-DATEPARM-OPEN` | `TransactionReportItemReader` | `open()` | OPEN INPUT/OUTPUT (file) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN03C.cbl` | `1500-A-LOOKUP-XREF` | `TransactionReportProcessor` | `lookupXref()` | Xref lookup for report line (CardXrefRepository) |
| `CBTRN03C.cbl` | `1500-B-LOOKUP-TRANTYPE` | `TransactionReportProcessor` | `lookupTransactionType()` | Transaction-type description lookup (TransactionTypeRepository) |
| `CBTRN03C.cbl` | `1500-C-LOOKUP-TRANCATG` | `TransactionReportProcessor` | `lookupTransactionCategory()` | Transaction-category lookup (TransactionCategoryTypeRepository) |
| `CBTRN03C.cbl` | `9000-TRANFILE-CLOSE` | `TransactionReportItemReader` | `close()` | CLOSE (Transaction) → ItemStream.close() |
| `CBTRN03C.cbl` | `9100-REPTFILE-CLOSE` | `TransactionReportItemReader` | `close()` | CLOSE (Transaction) → ItemStream.close() |
| `CBTRN03C.cbl` | `9200-CARDXREF-CLOSE` | `TransactionReportItemReader` | `close()` | CLOSE (CardXref) → ItemStream.close() |
| `CBTRN03C.cbl` | `9300-TRANTYPE-CLOSE` | `TransactionReportItemReader` | `close()` | CLOSE (TransactionType) → ItemStream.close() |
| `CBTRN03C.cbl` | `9400-TRANCATG-CLOSE` | `TransactionReportItemReader` | `close()` | CLOSE (TransactionCategoryType) → ItemStream.close() |
| `CBTRN03C.cbl` | `9500-DATEPARM-CLOSE` | `TransactionReportItemReader` | `close()` | CLOSE (file) → ItemStream.close() |
| `CBTRN03C.cbl` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBTRN03C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBSTM03A.CBL — Statement Generation (driver) (Job `CREASTMT`)

_Paragraph count: **25** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03A.CBL` | `0000-START` | `StatementCardXrefItemReader` | `open()` | Statement driver start: open the CardXref stream and prime the first xref — RepositoryItemReader.open() |
| `CBSTM03A.CBL` | `1000-MAINLINE` | `StatementProcessor` | `process()` | Per-account statement mainline → chunk processor |
| `CBSTM03A.CBL` | `9999-GOBACK` | `StatementJob` | (step complete) | GOBACK → step/job completion |
| `CBSTM03A.CBL` | `1000-XREFFILE-GET-NEXT` | `StatementCardXrefItemReader` | `read()` | Sequential xref read → ItemReader.read() |
| `CBSTM03A.CBL` | `2000-CUSTFILE-GET` | `StatementFileService` | `getCustomer()` | Random customer read via CBSTM03B CALL → StatementFileService |
| `CBSTM03A.CBL` | `3000-ACCTFILE-GET` | `StatementFileService` | `getAccount()` | Random account read via CBSTM03B CALL → StatementFileService |
| `CBSTM03A.CBL` | `4000-TRNXFILE-GET` | `StatementFileService` | `getTransactionsForCard()` | Keyed transaction read via CBSTM03B CALL → StatementFileService |
| `CBSTM03A.CBL` | `5000-CREATE-STATEMENT` | `StatementProcessor` | `process()` | Assemble StatementDocument (header + transactions + totals) |
| `CBSTM03A.CBL` | `5100-WRITE-HTML-HEADER` | `StatementItemWriter` | `write()` | Statement HTML header render |
| `CBSTM03A.CBL` | `5100-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 5100-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03A.CBL` | `5200-WRITE-HTML-NMADBS` | `StatementItemWriter` | `write()` | Customer name/address HTML block render |
| `CBSTM03A.CBL` | `5200-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 5200-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03A.CBL` | `6000-WRITE-TRANS` | `StatementItemWriter` | `write()` | Transaction rows → statement (HTML + plaintext) |
| `CBSTM03A.CBL` | `8100-FILE-OPEN` | `StatementCardXrefItemReader` | `open()` | Open the driving CardXref stream; random account/customer/transaction reads need no explicit OPEN (JPA-managed) |
| `CBSTM03A.CBL` | `8100-TRNXFILE-OPEN` | `StatementFileService` | (n/a — JPA-managed) | CALL CBSTM03B op=O (OPEN); random read needs no explicit OPEN (Spring Data/JPA manages the connection) |
| `CBSTM03A.CBL` | `8200-XREFFILE-OPEN` | `StatementCardXrefItemReader` | `open()` | CALL CBSTM03B op=O (OPEN) — RepositoryItemReader.open() (sequential xref stream) |
| `CBSTM03A.CBL` | `8300-CUSTFILE-OPEN` | `StatementFileService` | (n/a — JPA-managed) | CALL CBSTM03B op=O (OPEN); random read needs no explicit OPEN (Spring Data/JPA manages the connection) |
| `CBSTM03A.CBL` | `8400-ACCTFILE-OPEN` | `StatementFileService` | (n/a — JPA-managed) | CALL CBSTM03B op=O (OPEN); random read needs no explicit OPEN (Spring Data/JPA manages the connection) |
| `CBSTM03A.CBL` | `8500-READTRNX-READ` | `StatementFileService` | `getTransactionsForCard()` | Keyed transaction read (op=R/K) via CBSTM03B |
| `CBSTM03A.CBL` | `8599-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 8599-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03A.CBL` | `9100-TRNXFILE-CLOSE` | `StatementFileService` | (n/a — JPA-managed) | CALL CBSTM03B op=C (CLOSE); random read needs no explicit CLOSE (Spring Data/JPA manages the connection) |
| `CBSTM03A.CBL` | `9200-XREFFILE-CLOSE` | `StatementCardXrefItemReader` | `close()` | CALL CBSTM03B op=C (CLOSE) — RepositoryItemReader.close() (sequential xref stream) |
| `CBSTM03A.CBL` | `9300-CUSTFILE-CLOSE` | `StatementFileService` | (n/a — JPA-managed) | CALL CBSTM03B op=C (CLOSE); random read needs no explicit CLOSE (Spring Data/JPA manages the connection) |
| `CBSTM03A.CBL` | `9400-ACCTFILE-CLOSE` | `StatementFileService` | (n/a — JPA-managed) | CALL CBSTM03B op=C (CLOSE); random read needs no explicit CLOSE (Spring Data/JPA manages the connection) |
| `CBSTM03A.CBL` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |

#### CBSTM03B.CBL — Statement File I/O subprogram (Job `CREASTMT`)

_Paragraph count: **14** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03B.CBL` | `0000-START` | `StatementFileService` | (operation dispatch) | Operation dispatch on WS-M03B-OPER (O/C/R/K/W/Z) — typed reads getAccount/getCustomer/getCard/getTransactionsForCard/getCardXrefsForAccount |
| `CBSTM03B.CBL` | `9999-GOBACK` | `StatementFileService` | (return) | GOBACK → return WS-M03B-RC (file status) to caller |
| `CBSTM03B.CBL` | `1000-TRNXFILE-PROC` | `StatementFileService` | `getTransactionsForCard()` | Transaction file read/read-key dispatch (open/close folded — JPA-managed) |
| `CBSTM03B.CBL` | `1900-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 1900-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `1999-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 1999-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `2000-XREFFILE-PROC` | `StatementFileService` | `getCardXrefsForAccount()` | Xref file read dispatch (open/close folded — JPA-managed) |
| `CBSTM03B.CBL` | `2900-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 2900-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `2999-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 2999-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `3000-CUSTFILE-PROC` | `StatementFileService` | `getCustomer()` | Customer file read dispatch (open/close folded — JPA-managed) |
| `CBSTM03B.CBL` | `3900-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 3900-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `3999-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 3999-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `4000-ACCTFILE-PROC` | `StatementFileService` | `getAccount()` | Account file read dispatch (open/close folded — JPA-managed) |
| `CBSTM03B.CBL` | `4900-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 4900-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |
| `CBSTM03B.CBL` | `4999-EXIT` | `StatementProcessor` | (helper) | PERFORM ... THRU 4999-EXIT range terminator → end of (helper) (structured method boundary; no separate Java code) |

#### CBACT01C.cbl — Account Master Print (Job `PRTACCT`)

_Paragraph count: **6** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT01C.cbl` | `1000-ACCTFILE-GET-NEXT` | `PrintReferenceJobs` | `read()` | READ NEXT (Account) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBACT01C.cbl` | `1100-DISPLAY-ACCT-RECORD` | `PrintReferenceJobs` | `printAccount()` | Format+print one account master record |
| `CBACT01C.cbl` | `0000-ACCTFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Account) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT01C.cbl` | `9000-ACCTFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Account) → ItemStream.close() |
| `CBACT01C.cbl` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBACT01C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBACT02C.cbl — Card Master Print (Job `PRTCARD`)

_Paragraph count: **5** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT02C.cbl` | `1000-CARDFILE-GET-NEXT` | `PrintReferenceJobs` | `read()` | READ NEXT (Card) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBACT02C.cbl` | `0000-CARDFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Card) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT02C.cbl` | `9000-CARDFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Card) → ItemStream.close() |
| `CBACT02C.cbl` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBACT02C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBACT03C.cbl — Card Xref Print (Job `PRTXREF`)

_Paragraph count: **5** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT03C.cbl` | `1000-XREFFILE-GET-NEXT` | `PrintReferenceJobs` | `read()` | READ NEXT (CardXref) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBACT03C.cbl` | `0000-XREFFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (CardXref) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBACT03C.cbl` | `9000-XREFFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (CardXref) → ItemStream.close() |
| `CBACT03C.cbl` | `9999-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBACT03C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBCUS01C.cbl — Customer Master Print (Job `PRTCUST`)

_Paragraph count: **5** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBCUS01C.cbl` | `1000-CUSTFILE-GET-NEXT` | `PrintReferenceJobs` | `read()` | READ NEXT (Customer) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBCUS01C.cbl` | `0000-CUSTFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Customer) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBCUS01C.cbl` | `9000-CUSTFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Customer) → ItemStream.close() |
| `CBCUS01C.cbl` | `Z-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBCUS01C.cbl` | `Z-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CBTRN01C.cbl — Daily Transaction Validation Print (Job `PRTTRAN`)

_Paragraph count: **18** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN01C.cbl` | `MAIN-PARA` | `PrintReferenceJobs` | `printDailyTransactions()` | Daily-transaction validation-print mainline (reader→lookup→print) |
| `CBTRN01C.cbl` | `1000-DALYTRAN-GET-NEXT` | `PrintReferenceJobs` | `read()` | READ NEXT (DailyTransaction) → ItemReader.read(); EOF ('10') returns null (normal step termination) |
| `CBTRN01C.cbl` | `2000-LOOKUP-XREF` | `CardXrefRepository` | `findById()` | Card→xref lookup by card number (VSAM READ KEY IS FD-XREF-CARD-NUM → JpaRepository.findById); account-keyed xref navigation lives in `CrossReferenceService` |
| `CBTRN01C.cbl` | `3000-READ-ACCOUNT` | `AccountRepository` | `findById()` | Account read for the daily transaction |
| `CBTRN01C.cbl` | `0000-DALYTRAN-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (DailyTransaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN01C.cbl` | `0100-CUSTFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Customer) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN01C.cbl` | `0200-XREFFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (CardXref) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN01C.cbl` | `0300-CARDFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Card) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN01C.cbl` | `0400-ACCTFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Account) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN01C.cbl` | `0500-TRANFILE-OPEN` | `PrintReferenceJobs` | `open()` | OPEN INPUT/OUTPUT (Transaction) → ItemStreamReader/Writer.open(ExecutionContext) |
| `CBTRN01C.cbl` | `9000-DALYTRAN-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (DailyTransaction) → ItemStream.close() |
| `CBTRN01C.cbl` | `9100-CUSTFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Customer) → ItemStream.close() |
| `CBTRN01C.cbl` | `9200-XREFFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (CardXref) → ItemStream.close() |
| `CBTRN01C.cbl` | `9300-CARDFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Card) → ItemStream.close() |
| `CBTRN01C.cbl` | `9400-ACCTFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Account) → ItemStream.close() |
| `CBTRN01C.cbl` | `9500-TRANFILE-CLOSE` | `PrintReferenceJobs` | `close()` | CLOSE (Transaction) → ItemStream.close() |
| `CBTRN01C.cbl` | `Z-ABEND-PROGRAM` | `FileProcessingException` | (throw) | ABEND → throw FileProcessingException (runtime) + structured log; step fails |
| `CBTRN01C.cbl` | `Z-DISPLAY-IO-STATUS` | `FileStatusCode` | `from()` | Decode FILE STATUS and structured-log the I/O error |

#### CSUTLDTC.cbl — Date Validation Utility (CEEDAYS) (Job `(called)`)

_Paragraph count: **2** — all mapped._

| COBOL Program | COBOL Paragraph / Construct | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `validateDate()` | CEEDAYS validation → LocalDate parse + FeedbackCode→message mapping (DateValidationException on invalid) |
| `CSUTLDTC.cbl` | `A000-MAIN-EXIT` | `DateValidationService` | `validateDate()` | PERFORM ... THRU A000-MAIN-EXIT range terminator → end of validateDate() (structured method boundary; no separate Java code) |

## 2. Construct Mappings (non-paragraph)

These cross-cutting COBOL/CICS/JCL constructs are preserved by the following Java
idioms. They complement the per-paragraph rows in Section&nbsp;1.

| COBOL / CICS / JCL Construct | Java / Spring Target | Preservation Notes |
|---|---|---|
| `SELECT ... FILE STATUS` (e.g. CBTRN02C: `DALYTRAN`, `TRANSACT`, `XREF`, `DALYREJS`, `ACCOUNT`, `TCATBAL`) | `FileStatusCode` (enum) + `FileProcessingException` | Each 2-char status decoded to `FileStatusCode`; I/O errors raise `FileProcessingException` with structured logging. |
| `FILE STATUS = '10'` (end-of-file) | `ItemReader.read()` returns `null` / `Optional.empty()` | EOF is **normal step termination**, never an exception. |
| `FILE STATUS = '23'` / `'13'` (not found) | `ResourceNotFoundException` &rarr; HTTP 404 | Keyed read miss on online inquiry. |
| `FILE STATUS = '22'` (duplicate key) | `DuplicateResourceException` &rarr; HTTP 409 | Add-user / add-transaction insert conflict. |
| Reject codes `WS-VALIDATION-FAIL-REASON` (CBTRN02C `1500-VALIDATE-TRAN`) | `RejectReason` (enum) | Numeric reject reasons preserved 1:1 as enum constants. |
| `EXEC CICS SYNCPOINT ROLLBACK` (COACTUPC) | `@Transactional(rollbackFor = ...)` | Service-boundary rollback semantics. |
| Read-then-rewrite optimistic concurrency (COACTUPC, COCRDUPC) | JPA `@Version` + `OptimisticLockConflictException` | "Record changed" -> 409; matches CICS re-read compare. |
| `EXEC CICS SEND MAP` / screen build | `@RestController` response + response DTO | 3270 BMS map &rarr; JSON response; terminal attributes dropped. |
| `EXEC CICS RECEIVE MAP` | `@RequestBody` / `@RequestParam` + Jakarta Validation | Map input fields &rarr; validated request DTO. |
| `EXEC CICS RETURN` / `XCTL` + `COMMAREA` (COCOM01Y) | Stateless REST + **JWT** (`JwtService`) | Pseudo-conversational state externalised; no server session. |
| `CALL 'CBSTM03B' USING WS-M03B-AREA` (CBSTM03A, 13 sites) | Constructor-injected `StatementFileService` bean | Inter-program CALL &rarr; Spring DI; op code (`O/C/R/K/W/Z`) preserved. |
| DFSORT `SORT FIELDS` (COMBTRAN step) | `TransactionIdComparator` (`java.util.Comparator`) | Key order/direction preserved; stable sort keeps duplicates. |
| IDCAMS `REPRO` / file load | Flyway `V3__seed_data.sql` + `JpaRepository.saveAll()` | ASCII fixtures seed the database. |
| JCL `EXEC PGM` + `DD` | Spring Batch `Job`/`Step` beans (`BatchConfig`) | One job per JCL job; DD &rarr; reader/writer resources. |
| JCL `COND` codes | `JobExecutionDecider` + `FlowBuilder` | Step sequencing / condition logic preserved. |
| GDG generations | Versioned **S3** objects (`AwsConfig`) | `carddemo-batch-input`/`-output`/`-statements`. |
| CICS TDQ `WRITEQ` / INTRDR bridge (CORPT00C) | **SQS FIFO** &rarr; `ReportJobLauncher` (D-004) | `carddemo-report-jobs.fifo` triggers a Spring Batch launch. |
| LE `CEEDAYS` date validation (CSUTLDTC) | `DateValidationService` (`java.time.LocalDate`) | Feedback codes &rarr; validation messages / `DateValidationException`. |
| `COPY` record-layout copybook (`CV*`, `CSUSR01Y`) | `import com.carddemo.entity.*` | See Section&nbsp;3. |
| `COPY` logic copybook (`CSMSG01Y/02Y`, `CSLKPCDY`, `CSUTLDPY`) | Shared `@Component` (`MessageService`, `LookupService`) | Message text / lookup tables centralised. |
| `COPY DFHAID` / `DFHBMSCA` / `DFHATTR` | *(none &mdash; CICS/BMS framework)* | 3270 attribute constants have no REST equivalent. |
| `COPY` symbolic map (`app/cpy-bms/*.CPY`) | `com.carddemo.dto.*` request/response records | Map field groups &rarr; DTO fields. |

## 3. Copybook &rarr; Entity / DTO Mapping

The **11 record-layout copybooks** define the persistent schema; their fixed byte
lengths are preserved for byte-equivalent I/O (Gate&nbsp;1 / Gate&nbsp;5).

| Copybook (`@ 27d6c6f`) | Domain Record | Length (bytes) | Java Entity | Repository |
|---|---|---|---|---|
| `CVACT01Y.cpy` | Account | 300 | `Account` | `AccountRepository` |
| `CVACT02Y.cpy` | Card | 150 | `Card` | `CardRepository` |
| `CVCUS01Y.cpy` | Customer | 500 | `Customer` | `CustomerRepository` |
| `CVACT03Y.cpy` | Card Cross-Reference | 50 | `CardXref` | `CardXrefRepository` |
| `CVTRA05Y.cpy` | Transaction (KSDS) | 350 | `Transaction` | `TransactionRepository` |
| `CVTRA06Y.cpy` | Daily Transaction | 350 | `DailyTransaction` | `DailyTransactionRepository` |
| `CVTRA02Y.cpy` | Disclosure Group | 50 | `DisclosureGroup` | `DisclosureGroupRepository` |
| `CVTRA03Y.cpy` | Transaction Type | 60 | `TransactionType` | `TransactionTypeRepository` |
| `CVTRA04Y.cpy` | Transaction Category Type | 60 | `TransactionCategoryType` | `TransactionCategoryTypeRepository` |
| `CVTRA01Y.cpy` | Transaction Category Balance | 50 | `TransactionCategoryBalance` | `TransactionCategoryBalanceRepository` |
| `CSUSR01Y.cpy` | User Security | 80 | `UserSecurity` | `UserSecurityRepository` |

**Logic / infrastructure copybooks** (not persisted): `COCOM01Y` (COMMAREA
&rarr; JWT session state), `CSMSG01Y` / `CSMSG02Y` (messages &rarr;
`MessageService`), `CSDAT01Y` / `CSUTLDPY` / `CSUTLDWY` (dates &rarr;
`DateValidationService` / `java.time`), `CSLKPCDY` (code lookups &rarr;
`LookupService`), `COTTL01Y` (titles &rarr; response header fields),
`CSSETATY` / `CSSTRPFY` (BMS attributes &mdash; no REST equivalent), and the
CICS-supplied `DFHAID` / `DFHBMSCA` / `DFHATTR` (framework only).

## 4. Reverse Index (Java &rarr; COBOL)

This section establishes **bidirectionality**: every Java class traces back to a
COBOL origin. **Part&nbsp;A** covers classes derived directly from paragraphs
(Section&nbsp;1). **Part&nbsp;B** covers the remaining classes whose origin is a
construct, JCL job, copybook, or a net-new cross-cutting concern.

### 4.A Paragraph-derived classes (Java method &rarr; COBOL paragraphs)

| Java Class | Java Method | COBOL Program | COBOL Paragraph(s) |
|---|---|---|---|
| `AccountController` | (HTTP response) | `COACTUPC.cbl`, `COACTVWC.cbl` | `COMMON-RETURN` |
| `AccountController` | (request binding) | `COACTUPC.cbl`, `COACTVWC.cbl` | `1100-RECEIVE-MAP`, `1100-RECEIVE-MAP-EXIT`, `2100-RECEIVE-MAP`, `2100-RECEIVE-MAP-EXIT` |
| `AccountController` | (request dispatch) | `COACTUPC.cbl`, `COACTVWC.cbl` | `0000-MAIN`, `0000-MAIN-EXIT` |
| `AccountController` | (response assembly) | `COACTUPC.cbl`, `COACTVWC.cbl` | `3000-SEND-MAP`, `3000-SEND-MAP-EXIT`, `3100-SCREEN-INIT`, `3100-SCREEN-INIT-EXIT`, `3200-SETUP-SCREEN-VARS`, `3200-SETUP-SCREEN-VARS-EXIT`, `3201-SHOW-INITIAL-VALUES`, `3201-SHOW-INITIAL-VALUES-EXIT`, `3202-SHOW-ORIGINAL-VALUES`, `3202-SHOW-ORIGINAL-VALUES-EXIT`, `3203-SHOW-UPDATED-VALUES`, `3203-SHOW-UPDATED-VALUES-EXIT`, `3250-SETUP-INFOMSG`, `3250-SETUP-INFOMSG-EXIT`, `3300-SETUP-SCREEN-ATTRS`, `3300-SETUP-SCREEN-ATTRS-EXIT`, `3310-PROTECT-ALL-ATTRS`, `3310-PROTECT-ALL-ATTRS-EXIT`, `3320-UNPROTECT-FEW-ATTRS`, `3320-UNPROTECT-FEW-ATTRS-EXIT`, `3390-SETUP-INFOMSG-ATTRS`, `3390-SETUP-INFOMSG-ATTRS-EXIT`, `3400-SEND-SCREEN`, `3400-SEND-SCREEN-EXIT`, `1000-SEND-MAP`, `1000-SEND-MAP-EXIT`, `1100-SCREEN-INIT`, `1100-SCREEN-INIT-EXIT`, `1200-SETUP-SCREEN-VARS`, `1200-SETUP-SCREEN-VARS-EXIT`, `1300-SETUP-SCREEN-ATTRS`, `1300-SETUP-SCREEN-ATTRS-EXIT`, `1400-SEND-SCREEN`, `1400-SEND-SCREEN-EXIT` |
| `AuthController` | (401 / redirect) | `COADM01C.cbl`, `COMEN01C.cbl` | `RETURN-TO-SIGNON-SCREEN` |
| `AuthController` | (request dispatch) | `COSGN00C.cbl` | `MAIN-PARA` |
| `AuthController` | (response assembly) | `COSGN00C.cbl` | `SEND-SIGNON-SCREEN` |
| `BillPaymentController` | (navigation) | `COBIL00C.cbl` | `RETURN-TO-PREV-SCREEN` |
| `BillPaymentController` | (request binding) | `COBIL00C.cbl` | `RECEIVE-BILLPAY-SCREEN` |
| `BillPaymentController` | (request dispatch) | `COBIL00C.cbl` | `MAIN-PARA` |
| `BillPaymentController` | (response assembly) | `COBIL00C.cbl` | `SEND-BILLPAY-SCREEN` |
| `CardController` | (HTTP response) | `COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `COMMON-RETURN` |
| `CardController` | (request binding) | `COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `2000-RECEIVE-MAP`, `2000-RECEIVE-MAP-EXIT`, `2100-RECEIVE-SCREEN`, `2100-RECEIVE-SCREEN-EXIT`, `2100-RECEIVE-MAP`, `2100-RECEIVE-MAP-EXIT`, `1100-RECEIVE-MAP`, `1100-RECEIVE-MAP-EXIT` |
| `CardController` | (request dispatch) | `COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `0000-MAIN`, `0000-MAIN-EXIT` |
| `CardController` | (response assembly) | `COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `1000-SEND-MAP`, `1000-SEND-MAP-EXIT`, `1100-SCREEN-INIT`, `1100-SCREEN-INIT-EXIT`, `1200-SCREEN-ARRAY-INIT`, `1200-SCREEN-ARRAY-INIT-EXIT`, `1250-SETUP-ARRAY-ATTRIBS`, `1250-SETUP-ARRAY-ATTRIBS-EXIT`, `1300-SETUP-SCREEN-ATTRS`, `1300-SETUP-SCREEN-ATTRS-EXIT`, `1400-SETUP-MESSAGE`, `1400-SETUP-MESSAGE-EXIT`, `1500-SEND-SCREEN`, `1500-SEND-SCREEN-EXIT`, `1200-SETUP-SCREEN-VARS`, `1200-SETUP-SCREEN-VARS-EXIT`, `1400-SEND-SCREEN`, `1400-SEND-SCREEN-EXIT`, `3000-SEND-MAP`, `3000-SEND-MAP-EXIT`, `3100-SCREEN-INIT`, `3100-SCREEN-INIT-EXIT`, `3200-SETUP-SCREEN-VARS`, `3200-SETUP-SCREEN-VARS-EXIT`, `3250-SETUP-INFOMSG`, `3250-SETUP-INFOMSG-EXIT`, `3300-SETUP-SCREEN-ATTRS`, `3300-SETUP-SCREEN-ATTRS-EXIT`, `3400-SEND-SCREEN`, `3400-SEND-SCREEN-EXIT` |
| `GlobalExceptionHandler` | (error response) | `COACTVWC.cbl`, `COCRDLIC.cbl`, `COCRDSLC.cbl`, `COSGN00C.cbl` | `SEND-PLAIN-TEXT`, `SEND-PLAIN-TEXT-EXIT`, `SEND-LONG-TEXT`, `SEND-LONG-TEXT-EXIT` |
| `GlobalExceptionHandler` | (exception mapping) | `COACTUPC.cbl`, `COACTVWC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `ABEND-ROUTINE`, `ABEND-ROUTINE-EXIT` |
| `MenuController` | (request binding) | `COADM01C.cbl`, `COMEN01C.cbl` | `RECEIVE-MENU-SCREEN` |
| `MenuController` | (request dispatch) | `COADM01C.cbl`, `COMEN01C.cbl` | `MAIN-PARA` |
| `MenuController` | (response assembly) | `COADM01C.cbl`, `COMEN01C.cbl` | `SEND-MENU-SCREEN` |
| `ReportController` | (HTTP response) | `CORPT00C.cbl` | `RETURN-TO-CICS` |
| `ReportController` | (navigation) | `CORPT00C.cbl` | `RETURN-TO-PREV-SCREEN` |
| `ReportController` | (request binding) | `CORPT00C.cbl` | `RECEIVE-TRNRPT-SCREEN` |
| `ReportController` | (request dispatch) | `CORPT00C.cbl` | `MAIN-PARA` |
| `ReportController` | (response assembly) | `CORPT00C.cbl` | `SEND-TRNRPT-SCREEN` |
| `TransactionController` | (navigation) | `COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` | `RETURN-TO-PREV-SCREEN` |
| `TransactionController` | (request binding) | `COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` | `RECEIVE-TRNLST-SCREEN`, `RECEIVE-TRNVIEW-SCREEN`, `RECEIVE-TRNADD-SCREEN` |
| `TransactionController` | (request dispatch) | `COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` | `MAIN-PARA` |
| `TransactionController` | (response assembly) | `COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` | `SEND-TRNLST-SCREEN`, `SEND-TRNVIEW-SCREEN`, `SEND-TRNADD-SCREEN` |
| `UserController` | (navigation) | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `RETURN-TO-PREV-SCREEN` |
| `UserController` | (request binding) | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `RECEIVE-USRLST-SCREEN`, `RECEIVE-USRADD-SCREEN`, `RECEIVE-USRUPD-SCREEN`, `RECEIVE-USRDEL-SCREEN` |
| `UserController` | (request dispatch) | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `MAIN-PARA` |
| `UserController` | (response assembly) | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `SEND-USRLST-SCREEN`, `SEND-USRADD-SCREEN`, `SEND-USRUPD-SCREEN`, `SEND-USRDEL-SCREEN` |
| `AccountUpdateService` | `detectChanges()` | `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW`, `1205-COMPARE-OLD-NEW-EXIT` |
| `AccountUpdateService` | `populateModel()` | `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA`, `9500-STORE-FETCHED-DATA-EXIT` |
| `AccountUpdateService` | `processInputs()` | `COACTUPC.cbl` | `1000-PROCESS-INPUTS`, `1000-PROCESS-INPUTS-EXIT` |
| `AccountUpdateService` | `updateAccount()` | `COACTUPC.cbl` | `2000-DECIDE-ACTION`, `2000-DECIDE-ACTION-EXIT` |
| `AccountUpdateService` | `validate()` | `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS`, `1200-EDIT-MAP-INPUTS-EXIT`, `1210-EDIT-ACCOUNT`, `1210-EDIT-ACCOUNT-EXIT`, `1215-EDIT-MANDATORY`, `1215-EDIT-MANDATORY-EXIT`, `1220-EDIT-YESNO`, `1220-EDIT-YESNO-EXIT`, `1225-EDIT-ALPHA-REQD`, `1225-EDIT-ALPHA-REQD-EXIT`, `1230-EDIT-ALPHANUM-REQD`, `1230-EDIT-ALPHANUM-REQD-EXIT`, `1235-EDIT-ALPHA-OPT`, `1235-EDIT-ALPHA-OPT-EXIT`, `1240-EDIT-ALPHANUM-OPT`, `1240-EDIT-ALPHANUM-OPT-EXIT`, `1245-EDIT-NUM-REQD`, `1245-EDIT-NUM-REQD-EXIT`, `1250-EDIT-SIGNED-9V2`, `1250-EDIT-SIGNED-9V2-EXIT`, `1260-EDIT-US-PHONE-NUM`, `EDIT-AREA-CODE`, `EDIT-US-PHONE-PREFIX`, `EDIT-US-PHONE-LINENUM`, `EDIT-US-PHONE-EXIT`, `1260-EDIT-US-PHONE-NUM-EXIT`, `1265-EDIT-US-SSN`, `1265-EDIT-US-SSN-EXIT`, `1270-EDIT-US-STATE-CD`, `1270-EDIT-US-STATE-CD-EXIT`, `1275-EDIT-FICO-SCORE`, `1275-EDIT-FICO-SCORE-EXIT`, `1280-EDIT-US-STATE-ZIP-CD`, `1280-EDIT-US-STATE-ZIP-CD-EXIT` |
| `AccountViewService` | `processInputs()` | `COACTVWC.cbl` | `2000-PROCESS-INPUTS`, `2000-PROCESS-INPUTS-EXIT` |
| `AccountViewService` | `validate()` | `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS`, `2200-EDIT-MAP-INPUTS-EXIT`, `2210-EDIT-ACCOUNT`, `2210-EDIT-ACCOUNT-EXIT` |
| `BillPaymentService` | `buildHeader()` | `COBIL00C.cbl` | `POPULATE-HEADER-INFO` |
| `BillPaymentService` | `currentTimestamp()` | `COBIL00C.cbl` | `GET-CURRENT-TIMESTAMP` |
| `BillPaymentService` | `processBillPayment()` | `COBIL00C.cbl` | `PROCESS-ENTER-KEY` |
| `BillPaymentService` | `resetForm()` | `COBIL00C.cbl` | `CLEAR-CURRENT-SCREEN`, `INITIALIZE-ALL-FIELDS` |
| `CardListService` | `applyFilters()` | `COCRDLIC.cbl` | `9500-FILTER-RECORDS`, `9500-FILTER-RECORDS-EXIT` |
| `CardListService` | `validate()` | `COCRDLIC.cbl` | `2200-EDIT-INPUTS`, `2200-EDIT-INPUTS-EXIT`, `2210-EDIT-ACCOUNT`, `2210-EDIT-ACCOUNT-EXIT`, `2220-EDIT-CARD`, `2220-EDIT-CARD-EXIT`, `2250-EDIT-ARRAY`, `2250-EDIT-ARRAY-EXIT` |
| `CardUpdateService` | (helper) | `COCRDUPC.cbl` | `9000-READ-DATA`, `9000-READ-DATA-EXIT` |
| `CardUpdateService` | `processInputs()` | `COCRDUPC.cbl` | `1000-PROCESS-INPUTS`, `1000-PROCESS-INPUTS-EXIT` |
| `CardUpdateService` | `updateCard()` | `COCRDUPC.cbl` | `2000-DECIDE-ACTION`, `2000-DECIDE-ACTION-EXIT` |
| `CardUpdateService` | `validate()` | `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS`, `1200-EDIT-MAP-INPUTS-EXIT`, `1210-EDIT-ACCOUNT`, `1210-EDIT-ACCOUNT-EXIT`, `1220-EDIT-CARD`, `1220-EDIT-CARD-EXIT`, `1230-EDIT-NAME`, `1230-EDIT-NAME-EXIT`, `1240-EDIT-CARDSTATUS`, `1240-EDIT-CARDSTATUS-EXIT` |
| `CardViewService` | (helper) | `COCRDSLC.cbl` | `9000-READ-DATA`, `9000-READ-DATA-EXIT` |
| `CardViewService` | `processInputs()` | `COCRDSLC.cbl` | `2000-PROCESS-INPUTS`, `2000-PROCESS-INPUTS-EXIT` |
| `CardViewService` | `validate()` | `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS`, `2200-EDIT-MAP-INPUTS-EXIT`, `2210-EDIT-ACCOUNT`, `2210-EDIT-ACCOUNT-EXIT`, `2220-EDIT-CARD`, `2220-EDIT-CARD-EXIT` |
| `CrossReferenceService` | `findByAccount()` | `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT`, `9200-GETCARDXREF-BYACCT-EXIT` |
| `CrossReferenceService` | `generateNextTransactionId()` | `COTRN02C.cbl`, `COBIL00C.cbl` | `ADD-TRANSACTION` |
| `CrossReferenceService` | `resolveCustomerId()` | `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT` |
| `CrossReferenceService` | `resolvePrimaryCardNumber()` | `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT` |
| `DateValidationService` | `validate()` | `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON`, `1250-EDIT-EXPIRY-MON-EXIT`, `1260-EDIT-EXPIRY-YEAR`, `1260-EDIT-EXPIRY-YEAR-EXIT` |
| `DateValidationService` | `validateDate()` | `CSUTLDTC.cbl` | `A000-MAIN`, `A000-MAIN-EXIT` |
| `InterestCalculationService` | `applyInterestToAccount()` | `CBACT04C.cbl` | `1400-COMPUTE-FEES` |
| `InterestCalculationService` | `calculateMonthlyInterest()` | `CBACT04C.cbl` | `1300-COMPUTE-INTEREST` |
| `InterestCalculationService` | `resolveInterestRate()` | `CBACT04C.cbl` | `1200-GET-INTEREST-RATE`, `1200-A-GET-DEFAULT-INT-RATE` |
| `MenuService` | `buildHeader()` | `COADM01C.cbl`, `COMEN01C.cbl` | `POPULATE-HEADER-INFO` |
| `MenuService` | `buildMenuOptions()` | `COADM01C.cbl`, `COMEN01C.cbl` | `BUILD-MENU-OPTIONS` |
| `MenuService` | `routeAdminMenuSelection()` | `COADM01C.cbl` | `PROCESS-ENTER-KEY` |
| `MenuService` | `routeMainMenuSelection()` | `COMEN01C.cbl` | `PROCESS-ENTER-KEY` |
| `ReportService` | `buildHeader()` | `CORPT00C.cbl` | `POPULATE-HEADER-INFO` |
| `ReportService` | `requestReport()` | `CORPT00C.cbl` | `PROCESS-ENTER-KEY` |
| `ReportService` | `resetForm()` | `CORPT00C.cbl` | `INITIALIZE-ALL-FIELDS` |
| `SignonService` | `authenticate()` | `COSGN00C.cbl` | `PROCESS-ENTER-KEY` |
| `SignonService` | `buildHeader()` | `COSGN00C.cbl` | `POPULATE-HEADER-INFO` |
| `StatementFileService` | (n/a — JPA-managed) | `CBSTM03A.CBL` | `8100-TRNXFILE-OPEN`, `8300-CUSTFILE-OPEN`, `8400-ACCTFILE-OPEN`, `9100-TRNXFILE-CLOSE`, `9300-CUSTFILE-CLOSE`, `9400-ACCTFILE-CLOSE` |
| `StatementFileService` | (operation dispatch) | `CBSTM03B.CBL` | `0000-START` |
| `StatementFileService` | (return) | `CBSTM03B.CBL` | `9999-GOBACK` |
| `StatementFileService` | `getAccount()` | `CBSTM03A.CBL`, `CBSTM03B.CBL` | `3000-ACCTFILE-GET`, `4000-ACCTFILE-PROC` |
| `StatementFileService` | `getCardXrefsForAccount()` | `CBSTM03B.CBL` | `2000-XREFFILE-PROC` |
| `StatementFileService` | `getCustomer()` | `CBSTM03A.CBL`, `CBSTM03B.CBL` | `2000-CUSTFILE-GET`, `3000-CUSTFILE-PROC` |
| `StatementFileService` | `getTransactionsForCard()` | `CBSTM03A.CBL`, `CBSTM03B.CBL` | `4000-TRNXFILE-GET`, `8500-READTRNX-READ`, `1000-TRNXFILE-PROC` |
| `TransactionAddService` | `addTransaction()` | `COTRN02C.cbl` | `PROCESS-ENTER-KEY`, `ADD-TRANSACTION` |
| `TransactionAddService` | `buildHeader()` | `COTRN02C.cbl` | `POPULATE-HEADER-INFO` |
| `TransactionAddService` | `prefillFromLast()` | `COTRN02C.cbl` | `COPY-LAST-TRAN-DATA` |
| `TransactionAddService` | `resetForm()` | `COTRN02C.cbl` | `CLEAR-CURRENT-SCREEN`, `INITIALIZE-ALL-FIELDS` |
| `TransactionAddService` | `validateDataFields()` | `COTRN02C.cbl` | `VALIDATE-INPUT-DATA-FIELDS` |
| `TransactionAddService` | `validateKeyFields()` | `COTRN02C.cbl` | `VALIDATE-INPUT-KEY-FIELDS` |
| `TransactionListService` | `buildHeader()` | `COTRN00C.cbl` | `POPULATE-HEADER-INFO` |
| `TransactionListService` | `listTransactions()` | `COTRN00C.cbl` | `PROCESS-ENTER-KEY` |
| `TransactionListService` | `nextPage()` | `COTRN00C.cbl` | `PROCESS-PF8-KEY`, `PROCESS-PAGE-FORWARD` |
| `TransactionListService` | `previousPage()` | `COTRN00C.cbl` | `PROCESS-PF7-KEY`, `PROCESS-PAGE-BACKWARD` |
| `TransactionListService` | `resetForm()` | `COTRN00C.cbl` | `INITIALIZE-TRAN-DATA` |
| `TransactionListService` | `toListItem()` | `COTRN00C.cbl` | `POPULATE-TRAN-DATA` |
| `TransactionViewService` | `buildHeader()` | `COTRN01C.cbl` | `POPULATE-HEADER-INFO` |
| `TransactionViewService` | `getTransaction()` | `COTRN01C.cbl` | `PROCESS-ENTER-KEY` |
| `TransactionViewService` | `resetForm()` | `COTRN01C.cbl` | `CLEAR-CURRENT-SCREEN`, `INITIALIZE-ALL-FIELDS` |
| `UserService` | `buildHeader()` | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `POPULATE-HEADER-INFO` |
| `UserService` | `createUser()` | `COUSR01C.cbl` | `PROCESS-ENTER-KEY` |
| `UserService` | `deleteUser()` | `COUSR03C.cbl` | `PROCESS-ENTER-KEY`, `DELETE-USER-INFO` |
| `UserService` | `listUsers()` | `COUSR00C.cbl` | `PROCESS-ENTER-KEY` |
| `UserService` | `nextPage()` | `COUSR00C.cbl` | `PROCESS-PF8-KEY`, `PROCESS-PAGE-FORWARD` |
| `UserService` | `previousPage()` | `COUSR00C.cbl` | `PROCESS-PF7-KEY`, `PROCESS-PAGE-BACKWARD` |
| `UserService` | `resetForm()` | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `INITIALIZE-USER-DATA`, `CLEAR-CURRENT-SCREEN`, `INITIALIZE-ALL-FIELDS` |
| `UserService` | `toListItem()` | `COUSR00C.cbl` | `POPULATE-USER-DATA` |
| `UserService` | `updateUser()` | `COUSR02C.cbl` | `PROCESS-ENTER-KEY`, `UPDATE-USER-INFO` |
| `AccountRepository` | `findById()` | `CBACT04C.cbl`, `CBTRN01C.cbl`, `COACTUPC.cbl`, `COACTVWC.cbl`, `COBIL00C.cbl` | `1100-GET-ACCT-DATA`, `3000-READ-ACCOUNT`, `9000-READ-ACCT`, `9000-READ-ACCT-EXIT`, `9300-GETACCTDATA-BYACCT`, `9300-GETACCTDATA-BYACCT-EXIT`, `READ-ACCTDAT-FILE` |
| `AccountRepository` | `save()` | `CBACT04C.cbl`, `CBTRN02C.cbl`, `COACTUPC.cbl`, `COBIL00C.cbl`, `COCRDUPC.cbl` | `1050-UPDATE-ACCOUNT`, `2800-UPDATE-ACCOUNT-REC`, `9600-WRITE-PROCESSING`, `9600-WRITE-PROCESSING-EXIT`, `UPDATE-ACCTDAT-FILE`, `9200-WRITE-PROCESSING`, `9200-WRITE-PROCESSING-EXIT` |
| `CardRepository` | `findById()` | `COCRDSLC.cbl`, `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD`, `9100-GETCARD-BYACCTCARD-EXIT`, `9150-GETCARD-BYACCT`, `9150-GETCARD-BYACCT-EXIT` |
| `CardXrefRepository` | `findByXrefAcctId()` | `CBACT04C.cbl` | `1110-GET-XREF-DATA` |
| `CardXrefRepository` | `findById()` | `COACTUPC.cbl`, `COACTVWC.cbl`, `COBIL00C.cbl`, `COTRN02C.cbl` | `9200-GETCARDXREF-BYACCT`, `9200-GETCARDXREF-BYACCT-EXIT`, `READ-CXACAIX-FILE`, `READ-CCXREF-FILE` |
| `CustomerRepository` | `findById()` | `COACTUPC.cbl`, `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST`, `9400-GETCUSTDATA-BYCUST-EXIT` |
| `TransactionCategoryBalanceRepository` | `save()` | `CBTRN02C.cbl` | `2700-A-CREATE-TCATBAL-REC`, `2700-B-UPDATE-TCATBAL-REC` |
| `TransactionRepository` | (browse end) | `COBIL00C.cbl`, `COTRN00C.cbl`, `COTRN02C.cbl` | `ENDBR-TRANSACT-FILE` |
| `TransactionRepository` | (browse start) | `COBIL00C.cbl`, `COTRN00C.cbl`, `COTRN02C.cbl` | `STARTBR-TRANSACT-FILE` |
| `TransactionRepository` | `findById()` | `COTRN01C.cbl` | `READ-TRANSACT-FILE` |
| `TransactionRepository` | `findNextPage()` | `COCRDLIC.cbl`, `COTRN00C.cbl` | `9000-READ-FORWARD`, `9000-READ-FORWARD-EXIT`, `READNEXT-TRANSACT-FILE` |
| `TransactionRepository` | `findPreviousPage()` | `COBIL00C.cbl`, `COCRDLIC.cbl`, `COTRN00C.cbl`, `COTRN02C.cbl` | `READPREV-TRANSACT-FILE`, `9100-READ-BACKWARDS`, `9100-READ-BACKWARDS-EXIT` |
| `TransactionRepository` | `save()` | `COBIL00C.cbl`, `COTRN02C.cbl` | `WRITE-TRANSACT-FILE` |
| `UserSecurityRepository` | (browse end) | `COUSR00C.cbl` | `ENDBR-USER-SEC-FILE` |
| `UserSecurityRepository` | (browse start) | `COUSR00C.cbl` | `STARTBR-USER-SEC-FILE` |
| `UserSecurityRepository` | `deleteById()` | `COUSR03C.cbl` | `DELETE-USER-SEC-FILE` |
| `UserSecurityRepository` | `findById()` | `COSGN00C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `READ-USER-SEC-FILE` |
| `UserSecurityRepository` | `findNextPage()` | `COUSR00C.cbl` | `READNEXT-USER-SEC-FILE` |
| `UserSecurityRepository` | `findPreviousPage()` | `COUSR00C.cbl` | `READPREV-USER-SEC-FILE` |
| `UserSecurityRepository` | `save()` | `COUSR01C.cbl`, `COUSR02C.cbl` | `WRITE-USER-SEC-FILE`, `UPDATE-USER-SEC-FILE` |
| `DailyTransactionItemReader` | `close()` | `CBTRN02C.cbl` | `9000-DALYTRAN-CLOSE`, `9100-TRANFILE-CLOSE`, `9200-XREFFILE-CLOSE`, `9300-DALYREJS-CLOSE`, `9400-ACCTFILE-CLOSE`, `9500-TCATBALF-CLOSE` |
| `DailyTransactionItemReader` | `open()` | `CBTRN02C.cbl` | `0000-DALYTRAN-OPEN`, `0100-TRANFILE-OPEN`, `0200-XREFFILE-OPEN`, `0300-DALYREJS-OPEN`, `0400-ACCTFILE-OPEN`, `0500-TCATBALF-OPEN` |
| `DailyTransactionItemReader` | `read()` | `CBTRN02C.cbl` | `1000-DALYTRAN-GET-NEXT` |
| `InterestAccountItemReader` | `close()` | `CBACT04C.cbl` | `9000-TCATBALF-CLOSE`, `9100-XREFFILE-CLOSE`, `9200-DISCGRP-CLOSE`, `9300-ACCTFILE-CLOSE`, `9400-TRANFILE-CLOSE` |
| `InterestAccountItemReader` | `open()` | `CBACT04C.cbl` | `0000-TCATBALF-OPEN`, `0100-XREFFILE-OPEN`, `0200-DISCGRP-OPEN`, `0300-ACCTFILE-OPEN`, `0400-TRANFILE-OPEN` |
| `InterestAccountItemReader` | `read()` | `CBACT04C.cbl` | `1000-TCATBALF-GET-NEXT` |
| `InterestCalculationService` | (helper) | `CBACT04C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` |
| `PostTransactionItemWriter` | `write()` | `CBACT04C.cbl`, `CBTRN02C.cbl` | `1300-B-WRITE-TX`, `2900-WRITE-TRANSACTION-FILE` |
| `PostTransactionItemWriter` | `writeReject()` | `CBTRN02C.cbl` | `2500-WRITE-REJECT-REC` |
| `PostTransactionProcessor` | `formatTimestamp()` | `CBTRN02C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` |
| `PostTransactionProcessor` | `lookupAccount()` | `CBTRN02C.cbl` | `1500-B-LOOKUP-ACCT` |
| `PostTransactionProcessor` | `lookupXref()` | `CBTRN02C.cbl` | `1500-A-LOOKUP-XREF` |
| `PostTransactionProcessor` | `process()` | `CBTRN02C.cbl` | `2000-POST-TRANSACTION` |
| `PostTransactionProcessor` | `upsertCategoryBalance()` | `CBTRN02C.cbl` | `2700-UPDATE-TCATBAL` |
| `PostTransactionProcessor` | `validate()` | `CBTRN02C.cbl` | `1500-VALIDATE-TRAN` |
| `PrintReferenceJobs` | `close()` | `CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBCUS01C.cbl`, `CBTRN01C.cbl` | `9000-ACCTFILE-CLOSE`, `9000-CARDFILE-CLOSE`, `9000-XREFFILE-CLOSE`, `9000-CUSTFILE-CLOSE`, `9000-DALYTRAN-CLOSE`, `9100-CUSTFILE-CLOSE`, `9200-XREFFILE-CLOSE`, `9300-CARDFILE-CLOSE`, `9400-ACCTFILE-CLOSE`, `9500-TRANFILE-CLOSE` |
| `PrintReferenceJobs` | `open()` | `CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBCUS01C.cbl`, `CBTRN01C.cbl` | `0000-ACCTFILE-OPEN`, `0000-CARDFILE-OPEN`, `0000-XREFFILE-OPEN`, `0000-CUSTFILE-OPEN`, `0000-DALYTRAN-OPEN`, `0100-CUSTFILE-OPEN`, `0200-XREFFILE-OPEN`, `0300-CARDFILE-OPEN`, `0400-ACCTFILE-OPEN`, `0500-TRANFILE-OPEN` |
| `PrintReferenceJobs` | `printAccount()` | `CBACT01C.cbl` | `1100-DISPLAY-ACCT-RECORD` |
| `PrintReferenceJobs` | `printDailyTransactions()` | `CBTRN01C.cbl` | `MAIN-PARA` |
| `PrintReferenceJobs` | `read()` | `CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBCUS01C.cbl`, `CBTRN01C.cbl` | `1000-ACCTFILE-GET-NEXT`, `1000-CARDFILE-GET-NEXT`, `1000-XREFFILE-GET-NEXT`, `1000-CUSTFILE-GET-NEXT`, `1000-DALYTRAN-GET-NEXT` |
| `ReportJobLauncher` | `enqueueReportJob()` | `CORPT00C.cbl` | `SUBMIT-JOB-TO-INTRDR`, `WIRTE-JOBSUB-TDQ` |
| `StatementCardXrefItemReader` | `close()` | `CBSTM03A.CBL` | `9200-XREFFILE-CLOSE` |
| `StatementCardXrefItemReader` | `open()` | `CBSTM03A.CBL` | `0000-START`, `8100-FILE-OPEN`, `8200-XREFFILE-OPEN` |
| `StatementCardXrefItemReader` | `read()` | `CBSTM03A.CBL` | `1000-XREFFILE-GET-NEXT` |
| `StatementItemWriter` | `write()` | `CBSTM03A.CBL` | `5100-WRITE-HTML-HEADER`, `5200-WRITE-HTML-NMADBS`, `6000-WRITE-TRANS` |
| `StatementJob` | (step complete) | `CBSTM03A.CBL` | `9999-GOBACK` |
| `StatementProcessor` | (helper) | `CBSTM03A.CBL`, `CBSTM03B.CBL` | `5100-EXIT`, `5200-EXIT`, `8599-EXIT`, `1900-EXIT`, `1999-EXIT`, `2900-EXIT`, `2999-EXIT`, `3900-EXIT`, `3999-EXIT`, `4900-EXIT`, `4999-EXIT` |
| `StatementProcessor` | `process()` | `CBSTM03A.CBL` | `1000-MAINLINE`, `5000-CREATE-STATEMENT` |
| `TransactionReportItemReader` | `close()` | `CBTRN03C.cbl` | `9000-TRANFILE-CLOSE`, `9100-REPTFILE-CLOSE`, `9200-CARDXREF-CLOSE`, `9300-TRANTYPE-CLOSE`, `9400-TRANCATG-CLOSE`, `9500-DATEPARM-CLOSE` |
| `TransactionReportItemReader` | `open()` | `CBTRN03C.cbl` | `0000-TRANFILE-OPEN`, `0100-REPTFILE-OPEN`, `0200-CARDXREF-OPEN`, `0300-TRANTYPE-OPEN`, `0400-TRANCATG-OPEN`, `0500-DATEPARM-OPEN` |
| `TransactionReportItemReader` | `read()` | `CBTRN03C.cbl` | `1000-TRANFILE-GET-NEXT` |
| `TransactionReportItemReader` | `readDateParams()` | `CBTRN03C.cbl` | `0550-DATEPARM-READ` |
| `TransactionReportItemWriter` | `write()` | `CBTRN03C.cbl` | `1100-WRITE-TRANSACTION-REPORT` |
| `TransactionReportItemWriter` | `writeDetail()` | `CBTRN03C.cbl` | `1120-WRITE-DETAIL` |
| `TransactionReportItemWriter` | `writeGrandTotals()` | `CBTRN03C.cbl` | `1110-WRITE-GRAND-TOTALS` |
| `TransactionReportItemWriter` | `writeHeader()` | `CBTRN03C.cbl` | `1120-WRITE-HEADERS` |
| `TransactionReportItemWriter` | `writeLine()` | `CBTRN03C.cbl` | `1111-WRITE-REPORT-REC` |
| `TransactionReportProcessor` | `accumulateAccountTotals()` | `CBTRN03C.cbl` | `1120-WRITE-ACCOUNT-TOTALS` |
| `TransactionReportProcessor` | `accumulatePageTotals()` | `CBTRN03C.cbl` | `1110-WRITE-PAGE-TOTALS` |
| `TransactionReportProcessor` | `lookupTransactionCategory()` | `CBTRN03C.cbl` | `1500-C-LOOKUP-TRANCATG` |
| `TransactionReportProcessor` | `lookupTransactionType()` | `CBTRN03C.cbl` | `1500-B-LOOKUP-TRANTYPE` |
| `TransactionReportProcessor` | `lookupXref()` | `CBTRN03C.cbl` | `1500-A-LOOKUP-XREF` |
| `FileProcessingException` | (throw) | `CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBACT04C.cbl`, `CBCUS01C.cbl`, `CBSTM03A.CBL`, `CBTRN01C.cbl`, `CBTRN02C.cbl`, `CBTRN03C.cbl` | `9999-ABEND-PROGRAM`, `Z-ABEND-PROGRAM` |
| `FileStatusCode` | `from()` | `CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBACT04C.cbl`, `CBCUS01C.cbl`, `CBTRN01C.cbl`, `CBTRN02C.cbl`, `CBTRN03C.cbl` | `9910-DISPLAY-IO-STATUS`, `Z-DISPLAY-IO-STATUS` |
| `OptimisticLockConflictException` | (guard) | `COACTUPC.cbl`, `COCRDUPC.cbl` | `9700-CHECK-CHANGE-IN-REC`, `9700-CHECK-CHANGE-IN-REC-EXIT`, `9300-CHECK-CHANGE-IN-REC`, `9300-CHECK-CHANGE-IN-REC-EXIT` |

### 4.B Construct / infrastructure classes (Java class &rarr; COBOL construct)

Entities, repositories without a direct paragraph verb, Spring Batch job
definitions, DFSORT comparators, batch models, exception/enum types, `config`,
`observability`, and `dto` classes trace back to the construct, JCL job, or
copybook that motivates them. Net-new observability classes (required by the
Observability rule) are marked accordingly.

| Java Class | Java Method / Kind | COBOL Program / Construct | COBOL Origin (paragraph / construct / copybook) |
|---|---|---|---|
| `CardDemoApplication` | `main()` | app/jcl/*.jcl + CARDDEMO.CSD | CICS region + JCL runtime → @SpringBootApplication bootstrap |
| `JwtService` | `issueToken() / validateToken()` | COSGN00C.cbl | DFHCOMMAREA pseudo-conversational state (COCOM01Y) → stateless JWT session |
| `LookupService` | `lookup()` | app/cpy/CSLKPCDY, CSUTLDPY | Lookup-table / date copybooks → reference-data lookups |
| `MessageService` | `resolve()` | app/cpy/CSMSG01Y, CSMSG02Y | Message-text copybooks → centralised message catalogue |
| `DailyTransactionRepository` | `saveAll() / findAll()` | CBTRN02C.cbl, CBTRN01C.cbl | DALYTRAN sequential file (CVTRA06Y) → staging JPA repository |
| `DisclosureGroupRepository` | `findById()` | CBACT04C.cbl | DISCGRP KSDS (CVTRA02Y) → interest-rate lookup repository |
| `TransactionCategoryTypeRepository` | `findById()` | CBTRN03C.cbl | TRANCATG KSDS (CVTRA04Y) → reference repository |
| `TransactionTypeRepository` | `findById()` | CBTRN03C.cbl | TRANTYPE KSDS (CVTRA03Y) → reference repository |
| `Account` | (entity) | app/cpy/CVACT01Y.cpy | 300-byte ACCOUNT-RECORD; PIC S9(10)V99 → BigDecimal(scale 2); @Version |
| `Card` | (entity) | app/cpy/CVACT02Y.cpy | 150-byte CARD-RECORD → @Entity; @Version |
| `CardXref` | (entity) | app/cpy/CVACT03Y.cpy | 50-byte card/account/customer cross-reference |
| `Customer` | (entity) | app/cpy/CVCUS01Y.cpy | 500-byte CUSTOMER-RECORD → @Entity |
| `DailyTransaction` | (entity) | app/cpy/CVTRA06Y.cpy | 350-byte daily-transaction staging record |
| `DisclosureGroup` | (entity) | app/cpy/CVTRA02Y.cpy | 50-byte disclosure-group record (composite key) |
| `DisclosureGroupId` | (embeddable id) | app/cpy/CVTRA02Y.cpy | Composite key (group + type + category) → @Embeddable |
| `Transaction` | (entity) | app/cpy/CVTRA05Y.cpy | 350-byte TRAN-RECORD; TRAN-AMT PIC S9(09)V99 → BigDecimal(scale 2) |
| `TransactionCategoryBalance` | (entity) | app/cpy/CVTRA01Y.cpy | 50-byte category-balance record (composite key) |
| `TransactionCategoryBalanceId` | (embeddable id) | app/cpy/CVTRA01Y.cpy | Composite key (acct-id + type + category) → @Embeddable |
| `TransactionCategoryType` | (entity) | app/cpy/CVTRA04Y.cpy | 60-byte transaction-category-type reference (composite key) |
| `TransactionCategoryTypeId` | (embeddable id) | app/cpy/CVTRA04Y.cpy | Composite key (type + category) → @Embeddable |
| `TransactionType` | (entity) | app/cpy/CVTRA03Y.cpy | 60-byte transaction-type reference |
| `UserSecurity` | (entity) | app/cpy/CSUSR01Y.cpy | 80-byte user-security record; password → BCrypt hash (D-002) |
| `BatchCorrelationIdListener` | `beforeJob() / afterJob()` | — (net-new: Observability) | MDC correlationId for batch jobs (JobExecutionListener) |
| `CombineTransactionItemReader` | `read()` | app/jcl COMBTRAN (SORTIN) | SORTIN concatenation → merge reader over transaction sources |
| `CombineTransactionJob` | `combineTransactionJob()` | app/jcl COMBTRAN (SORT) | JCL DFSORT step (no COBOL program) → Job/Step bean |
| `CombineTransactionProcessor` | `process()` | app/jcl COMBTRAN (SORT) | SORT record pass-through / key projection |
| `InterestCalculationJob` | `interestCalculationJob()` | CBACT04C.cbl + app/jcl/INTCALC | JCL EXEC PGM → Job/Step bean |
| `PostTransactionJob` | `postTransactionJob()` | CBTRN02C.cbl + app/jcl/POSTTRAN | JCL EXEC PGM + DD → Spring Batch Job/Step (BatchConfig) |
| `PostingResult` | (record) | CBTRN02C.cbl | Posting outcome value object (posted / rejected + RejectReason) |
| `ReportDetailLine` | (record) | CBTRN03C.cbl | Report detail / total line model (1111-WRITE-REPORT-REC, 1120-WRITE-DETAIL) |
| `StatementDocument` | (record) | CBSTM03A.CBL | Statement content model (header + transactions + totals) |
| `TransactionIdComparator` | `compare()` | app/jcl COMBTRAN (SORT FIELDS) | DFSORT SORT FIELDS (tran-id ASC) → stable Comparator; duplicates preserved |
| `TransactionReportJob` | `transactionReportJob()` | CBTRN03C.cbl + app/jcl/TRANREPT | JCL EXEC PGM + PROC → Job/Step bean |
| `CardDemoException` | (base) | all programs (FILE STATUS / ABEND) | Base runtime exception for the typed error hierarchy |
| `DateValidationException` | (thrown) | CSUTLDTC.cbl | CEEDAYS invalid-date feedback → HTTP 400 |
| `DuplicateResourceException` | (thrown) | COUSR01C.cbl, COTRN02C.cbl | Duplicate-key FILE STATUS '22' → HTTP 409 |
| `RejectReason` | `values()` | CBTRN02C.cbl | WS-VALIDATION-FAIL-REASON codes (1500-VALIDATE-TRAN) → enum |
| `ResourceNotFoundException` | (thrown) | COACTVWC/COCRDSLC/COTRN01C etc. | Record-not-found FILE STATUS '23'/'13' → HTTP 404 |
| `ValidationException` | (thrown) | all *-EDIT-* / VALIDATE-* paragraphs | Field-edit failures → HTTP 400 (Jakarta Validation) |
| `AwsConfig` | (clients) | GDG datasets + CICS TDQ | GDG → versioned S3; TDQ → SQS FIFO; SNS clients (LocalStack) |
| `BatchConfig` | (job/step beans) | app/jcl/*.jcl, app/ctl/REPROCT.ctl | JCL EXEC PGM/DD/COND → Job/Step/Flow; JobExecutionDecider + FlowBuilder |
| `JpaConfig` | (beans) | app/catlg/LISTCAT.txt | VSAM KSDS/AIX catalog → JPA/Hibernate config + auditing |
| `MetricsConfig` | (beans) | — (net-new: Observability) | Prometheus registry customisation / common tags |
| `ObservabilityConfig` | (beans) | — (net-new: Observability) | Micrometer tracing/OTel + metrics wiring |
| `SecurityConfig` | (beans) | COSGN00C.cbl + USRSEC | RACF-less USRSEC auth → Spring Security (BCrypt encoder, JWT filter) |
| `WebConfig` | (beans) | CICS SEND/RECEIVE MAP | REST/MVC config (JSON converters, CORS, pagination) |
| `CorrelationIdFilter` | `doFilterInternal()` | — (net-new: Observability) | MDC correlationId per REST request (trace stitching) |
| `HealthIndicators` | `health()` | — (net-new: Observability) | Readiness/liveness (DB, S3, SQS) at /actuator/health |
| `AccountUpdateRequest` | (request DTO) | COACTUPC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `AccountUpdateResponse` | (response DTO) | COACTUPC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `AccountViewResponse` | (response DTO) | COACTVWC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `BillPaymentRequest` | (request DTO) | COBIL00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `BillPaymentResponse` | (response DTO) | COBIL00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `CardListItem` | (response DTO) | COCRDLIC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `CardListResponse` | (response DTO) | COCRDLIC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `CardUpdateRequest` | (request DTO) | COCRDUPC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `CardUpdateResponse` | (response DTO) | COCRDUPC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `CardViewResponse` | (response DTO) | COCRDSLC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `ErrorResponse` | (response DTO) | COSGN00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `MenuOption` | (response DTO) | COMEN01C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `MenuResponse` | (response DTO) | COMEN01C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `PageResponse` | (response DTO) | COCRDLIC.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `ReportRequest` | (request DTO) | CORPT00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `ReportResponse` | (response DTO) | CORPT00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `SignonRequest` | (request DTO) | COSGN00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `SignonResponse` | (response DTO) | COSGN00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `StatementTransactionDto` | (response DTO) | CBSTM03A.CBL | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `TransactionAddRequest` | (request DTO) | COTRN02C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `TransactionAddResponse` | (response DTO) | COTRN02C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `TransactionListItem` | (response DTO) | COTRN00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `TransactionListResponse` | (response DTO) | COTRN00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `TransactionViewResponse` | (response DTO) | COTRN01C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `UserCreateRequest` | (request DTO) | COUSR01C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |
| `UserListItem` | (response DTO) | COUSR00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `UserListResponse` | (response DTO) | COUSR00C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `UserResponse` | (response DTO) | COUSR02C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → response DTO |
| `UserUpdateRequest` | (request DTO) | COUSR02C.cbl | BMS symbolic-map copybook (app/cpy-bms) + record fields → request DTO |

## 5. Coverage Summary

**Assertion: 100% forward coverage.** Every paragraph of every one of the 28
programs at commit `27d6c6f` appears exactly once in Section&nbsp;1
(**527** paragraph rows total). **Assertion: full reverse coverage.**
All **126** classes in the target inventory (`com.carddemo.**`) appear in the
Section&nbsp;4 reverse index (Part&nbsp;A: 49; Part&nbsp;B: 77).

| # | COBOL Program | Tier | Txn / Job | Paragraphs | Status |
|---|---|---|---|---|---|
| 1 | `COSGN00C.cbl` | Online (CICS) | `CC00` | 6 | All mapped |
| 2 | `COMEN01C.cbl` | Online (CICS) | `CM00` | 7 | All mapped |
| 3 | `COADM01C.cbl` | Online (CICS) | `CA00` | 7 | All mapped |
| 4 | `COACTVWC.cbl` | Online (CICS) | `CAVW` | 34 | All mapped |
| 5 | `COACTUPC.cbl` | Online (CICS) | `CAUP` | 85 | All mapped |
| 6 | `COCRDLIC.cbl` | Online (CICS) | `CCLI` | 39 | All mapped |
| 7 | `COCRDSLC.cbl` | Online (CICS) | `CCDL` | 34 | All mapped |
| 8 | `COCRDUPC.cbl` | Online (CICS) | `CCUP` | 45 | All mapped |
| 9 | `COTRN00C.cbl` | Online (CICS) | `CT00` | 16 | All mapped |
| 10 | `COTRN01C.cbl` | Online (CICS) | `CT01` | 9 | All mapped |
| 11 | `COTRN02C.cbl` | Online (CICS) | `CT02` | 18 | All mapped |
| 12 | `CORPT00C.cbl` | Online (CICS) | `CR00` | 10 | All mapped |
| 13 | `COBIL00C.cbl` | Online (CICS) | `CB00` | 16 | All mapped |
| 14 | `COUSR00C.cbl` | Online (CICS) | `CU00` | 16 | All mapped |
| 15 | `COUSR01C.cbl` | Online (CICS) | `CU01` | 9 | All mapped |
| 16 | `COUSR02C.cbl` | Online (CICS) | `CU02` | 11 | All mapped |
| 17 | `COUSR03C.cbl` | Online (CICS) | `CU03` | 11 | All mapped |
| 18 | `CBTRN02C.cbl` | Batch / Utility | `POSTTRAN` | 26 | All mapped |
| 19 | `CBACT04C.cbl` | Batch / Utility | `INTCALC` | 22 | All mapped |
| 20 | `CBTRN03C.cbl` | Batch / Utility | `TRANREPT` | 26 | All mapped |
| 21 | `CBSTM03A.CBL` | Batch / Utility | `CREASTMT` | 25 | All mapped |
| 22 | `CBSTM03B.CBL` | Batch / Utility | `CREASTMT` | 14 | All mapped |
| 23 | `CBACT01C.cbl` | Batch / Utility | `PRTACCT` | 6 | All mapped |
| 24 | `CBACT02C.cbl` | Batch / Utility | `PRTCARD` | 5 | All mapped |
| 25 | `CBACT03C.cbl` | Batch / Utility | `PRTXREF` | 5 | All mapped |
| 26 | `CBCUS01C.cbl` | Batch / Utility | `PRTCUST` | 5 | All mapped |
| 27 | `CBTRN01C.cbl` | Batch / Utility | `PRTTRAN` | 18 | All mapped |
| 28 | `CSUTLDTC.cbl` | Batch / Utility | (called) | 2 | All mapped |
| — | **TOTAL** | **17 online + 11 batch** | — | **527** | **100%** |

### Verification method

Paragraph enumeration is mechanical: Area&nbsp;A labels ending in `.` within the
`PROCEDURE DIVISION` (both the numbered batch convention, e.g.
`1500-VALIDATE-TRAN`, and the named online convention, e.g. `PROCESS-ENTER-KEY`)
were extracted from each source at `27d6c6f` and reconciled 1:1 against the rows
above. No paragraph name is guessed; illustrative examples from narrative
documentation that do not exist in the source (e.g. `9100-GETACCT-REQUEST`,
`2000-VALIDATE-TXN`) were replaced by the true paragraph names
(`9000-READ-ACCT` / `9300-GETACCTDATA-BYACCT`, `1500-VALIDATE-TRAN` /
`2000-POST-TRANSACTION`).

---

_Generated for the CardDemo COBOL&rarr;Java migration. Source referenced by commit
`27d6c6f` (`7756d895ffeb65f7ea72aaa609e356d9899afcec`); COBOL is **not** copied into this repository. See the
[decision log](./decision-log.md) and [architecture overview](./architecture/overview.md)._
