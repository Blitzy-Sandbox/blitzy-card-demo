<!-- markdownlint-disable MD013 MD060 -->
<!-- Rationale: MD013 (line-length) and MD060 (table-pipe-style) are disabled for this file only — a wide 5-column, 500+ row reference matrix where 80-character line limits are infeasible and uniform pipe-padding adds no rendered value. All other rules remain enabled and satisfied. -->

# CardDemo Migration — Traceability Matrix

> **Bidirectional COBOL → Java traceability with 100% paragraph coverage.**
> This is the authoritative **Gate 8** traceability artifact for the AWS CardDemo
> mainframe-to-cloud migration, satisfying the user-specified **Explainability** rule.

## 1. Purpose and Scope

This matrix maps **every COBOL paragraph** across **all 28 COBOL programs** (17 CICS online
programs + the `CSUTLDTC` date-validation utility = 18 "online", plus 10 batch programs) to the
**Java class and method** that implements its behavior in the greenfield Spring Boot target. It
additionally maps every **copybook** (28), **BMS mapset + symbolic map** (17 + 17), and **JCL job**
(29 application + 3 build) to its Java target, and records the single **intentionally unmapped**
artifact with justification.

- **COBOL baseline:** the frozen reference corpus at commit SHA **`27d6c6f`** (tech-spec version
  tag `v1.0-15-g27d6c6f-68`). COBOL source is **never copied** into this repository; artifacts are
  referenced by **name and SHA** only, consistent with the migration contract.
- **Java base package:** `com.carddemo` (all class references below are within this package).
- **Coverage:** **527 paragraphs** across 28 programs are mapped with **no gaps**. The only
  artifact without a Java mapping is `app/cpy/UNUSED1Y.cpy` — a reserved/unused copybook with no
  behavior to migrate (see §8).
- **Bidirectionality:** the COBOL → Java direction is the per-program tables in §3–§4; the reverse
  Java → COBOL direction is summarized in §9 and is fully derivable from the forward tables (each row
  carries both endpoints).

This matrix is cited as evidence by `docs/validation-gates.md#gate-8` and is consistent with the
architectural decisions recorded in `DECISION_LOG.md`.

## 2. Legend and Methodology

### 2.1 Column definitions

| Column | Meaning |
|---|---|
| **COBOL Program** | Source program filename in `app/cbl/` at SHA `27d6c6f` (extension case preserved as on disk). |
| **COBOL Paragraph** | The paragraph or `SECTION` label **read verbatim** from the program's `PROCEDURE DIVISION`. Names are authoritative — none are invented. |
| **Java Class** | The target class (simple name; base package `com.carddemo`) that carries the behavior. |
| **Java Method** | The method (or REST endpoint, for controller request/response handlers) implementing the paragraph. |
| **Notes** | The transformation applied (COBOL construct → Java idiom) and any parity-critical detail. |

### 2.2 Target package layout (base package `com.carddemo`)

| Sub-package | Contents |
|---|---|
| `entity` | 11 JPA entities (`Account`, `Card`, `Customer`, `CardXref`, `Transaction`, `DailyTransaction`, `TransactionCategoryBalance`, `DisclosureGroup`, `TransactionType`, `TransactionCategory`, `User`). |
| `dto` | Request/response DTOs derived from BMS symbolic maps + `CommArea`, `MenuOption`, `StatementDto`. |
| `enums` | `FileStatusCode`, `RejectReasonCode`, `TransactionSource`, `TransactionTypeCode`. |
| `entity` | `@Embeddable` composite keys (`TransactionCategoryBalanceId`, `DisclosureGroupId`, `TransactionCategoryId`). |
| `repository` | 11 Spring Data JPA repositories (replace VSAM keyed/browse access). |
| `service.{auth,account,card,transaction,billing,report,admin,menu,shared}` | 20 service classes (online program business logic + shared utilities) + `ReportJobConsumer`, the SQS FIFO bridge listener that completes the F-011 submit-then-process semantic (D-069). |
| `controller` | 8 REST controllers (replace the 17 BMS online screens). |
| `batch.{jobs,processors,readers,writers}` | Spring Batch pipeline (replaces the JCL batch jobs). |
| `config`, `observability`, `exception` | `SecurityConfig`/`BatchConfig`/`AwsConfig`/`JpaConfig`/`ObservabilityConfig`/`WebConfig`; `CorrelationIdFilter`/`MetricsConfig`/`HealthIndicators`; 7 custom exceptions + `GlobalExceptionHandler`. |

### 2.3 Method-name derivation rules

Because the migration is an idiomatic re-architecture (not a line-for-line "Jobol" transliteration), a
COBOL paragraph maps to the Java member that carries its **behavior**, not a mechanically renamed
clone. The following deterministic conventions were applied and are reflected consistently in §3–§4:

- **Sequence prefixes are dropped.** COBOL ordering prefixes such as `1000-`, `9100-`, `1500-A-`,
  `Z-`, and `A000-` are removed; the remaining hyphenated label is rendered in `camelCase`
  and named for the behaviour it carries (e.g., `1500-A-LOOKUP-XREF` → `lookupAccountId()` on
  `TransactionReportProcessor`, which resolves the card cross-reference to its owning account).
- **`PERFORM THRU` exit labels** (paragraphs ending in `-EXIT`, or bare `nnnn-EXIT`) do not become
  separate Java methods. They are mapped to the **structured `return` boundary** of their enclosing
  method and annotated as such, so every label is represented without inventing a method.
- **Screen I/O → REST.** `SEND-*`/`*-SEND-MAP` → controller response building (BMS map → JSON DTO);
  `RECEIVE-*`/`*-RECEIVE-MAP` → controller request binding (`@RequestBody`/`@Valid`). BMS attribute
  paragraphs (protect/colour/highlight) have **no terminal equivalent** in REST and map to response
  shaping only.
- **VSAM access → repositories.** `READ ... KEY` → JPA finders; `STARTBR`/`READNEXT`/`READPREV`/`ENDBR`
  → `Pageable` queries; `REWRITE`/`WRITE` → `save()` inside `@Transactional` boundaries.
- **Concurrency & transactions.** Re-read-and-compare guards (`*-CHECK-CHANGE-IN-REC`) → JPA
  `@Version` optimistic locking; `SYNCPOINT`/implicit batch commits → `@Transactional(rollbackFor =
  Exception.class)`.
- **Cross-cutting.** `*-ABEND-*`/`ABEND-ROUTINE` → `GlobalExceptionHandler`; `*-DISPLAY-IO-STATUS` →
  `FileStatusMapper` (FILE STATUS → typed exception + structured log); timestamp paragraphs →
  `java.time`.
- **Batch loops.** Program mainlines → chunk-oriented `Step`s; `*-OPEN`/`*-CLOSE` → `ItemStream`
  lifecycle; `*-GET-NEXT` → `ItemReader.read()`; reject/posted writes → the corresponding `ItemWriter`.

> **Note on illustrative labels.** The tech-spec example rows in §0.7.3 used a few *illustrative*
> paragraph labels (`9100-GETACCT-REQUEST`, `PROCESS-UPDATE-ACCT`, `2000-VALIDATE-TXN`). This matrix
> uses the **authoritative source labels** observed at SHA `27d6c6f` — i.e. `9000-READ-ACCT`,
> `9600-WRITE-PROCESSING`, `9700-CHECK-CHANGE-IN-REC` (COACTUPC) and `1500-VALIDATE-TRAN` /
> `1500-A-LOOKUP-XREF` / `1500-B-LOOKUP-ACCT` (CBTRN02C) — mapped to the same Java targets the spec
> intended (`AccountUpdateService.loadAccount()/updateAccount()/persist()`, `TransactionPostingProcessor.validate()`).

### 2.4 Coverage summary

| Program | Type | Paragraphs mapped |
|---|---|---|
| `COSGN00C.cbl` | online | 6 |
| `COMEN01C.cbl` | online | 7 |
| `COADM01C.cbl` | online | 7 |
| `COACTVWC.cbl` | online | 34 |
| `COACTUPC.cbl` | online | 85 |
| `COCRDLIC.cbl` | online | 39 |
| `COCRDSLC.cbl` | online | 34 |
| `COCRDUPC.cbl` | online | 45 |
| `COTRN00C.cbl` | online | 16 |
| `COTRN01C.cbl` | online | 9 |
| `COTRN02C.cbl` | online | 18 |
| `COBIL00C.cbl` | online | 16 |
| `CORPT00C.cbl` | online | 10 |
| `COUSR00C.cbl` | online | 16 |
| `COUSR01C.cbl` | online | 9 |
| `COUSR02C.cbl` | online | 11 |
| `COUSR03C.cbl` | online | 11 |
| `CSUTLDTC.cbl` | online | 2 |
| `CBTRN01C.cbl` | batch | 18 |
| `CBTRN02C.cbl` | batch | 26 |
| `CBTRN03C.cbl` | batch | 26 |
| `CBACT01C.cbl` | batch | 6 |
| `CBACT02C.cbl` | batch | 5 |
| `CBACT03C.cbl` | batch | 5 |
| `CBACT04C.cbl` | batch | 22 |
| `CBCUS01C.cbl` | batch | 5 |
| `CBSTM03A.CBL` | batch | 25 |
| `CBSTM03B.CBL` | batch | 14 |
| **Total (28 programs)** | — | **527** |

Online/utility subtotal: **375** paragraphs across 18 programs. Batch subtotal:
**152** paragraphs across 10 programs. Plus 28 copybooks (§5), 34 BMS/symbolic-map artifacts
(§6), and 32 JCL jobs (§7) — **all mapped**, with the sole documented exception in §8.

## 3. Online & Utility Programs — Paragraph → Java Mapping

The 17 CICS online programs and the `CSUTLDTC` date-validation utility. Pseudo-conversational COMMAREA
navigation (`COCOM01Y`) is replaced by **stateless JWT-secured** request/response flows; there is no
server-side session.

### COSGN00C.cbl — Sign-on screen (txn `CC00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COSGN00C.cbl` | `MAIN-PARA` | `AuthController` | `signin()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless AuthController [POST /api/auth/signin] |
| `COSGN00C.cbl` | `PROCESS-ENTER-KEY` | `AuthService` | `signin()` | BCrypt verification replaces plaintext password compare; issues JWT on success |
| `COSGN00C.cbl` | `SEND-SIGNON-SCREEN` | `AuthController` | `signin()` | BMS sign-on map (COSGN00) -> `ResponseEntity<AuthDto.SigninResponse>` JSON payload |
| `COSGN00C.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | `handleAuthenticationFailed()` | SEND TEXT (error/long message) -> centralized structured error response (`ProblemDetail`) |
| `COSGN00C.cbl` | `POPULATE-HEADER-INFO` | `AuthService` | `signin()` | Screen header (title/program/date/time) -> `AuthDto.SigninResponse` fields |
| `COSGN00C.cbl` | `READ-USER-SEC-FILE` | `UserRepository` | `findById()` | VSAM READ USRSEC -> JPA finder on User |

### COMEN01C.cbl — Main menu (txn `CM00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COMEN01C.cbl` | `MAIN-PARA` | `MenuController` | `getMainMenu()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless MenuController [GET /api/menu/main] |
| `COMEN01C.cbl` | `PROCESS-ENTER-KEY` | `MenuService` | `getMainMenu()` | AID=ENTER primary action -> service business method |
| `COMEN01C.cbl` | `RETURN-TO-SIGNON-SCREEN` | `MenuController` | `getMainMenu()` | Session-expiry routing -> HTTP 401 / re-auth response |
| `COMEN01C.cbl` | `SEND-MENU-SCREEN` | `MenuController` | `getMainMenu()` | BMS SEND MAP -> JSON response DTO |
| `COMEN01C.cbl` | `RECEIVE-MENU-SCREEN` | `MenuController` | `getMainMenu()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COMEN01C.cbl` | `POPULATE-HEADER-INFO` | `MenuService` | `getMainMenu()` | Screen header (title/program/date/time) -> response metadata fields |
| `COMEN01C.cbl` | `BUILD-MENU-OPTIONS` | `MenuService` | `getMainMenu()` | Menu option table (COMEN02Y/COADM02Y) -> `List<MenuOption>` with role filtering |

### COADM01C.cbl — Admin menu (txn `CA00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COADM01C.cbl` | `MAIN-PARA` | `MenuController` | `getAdminMenu()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless MenuController [GET /api/menu/admin] |
| `COADM01C.cbl` | `PROCESS-ENTER-KEY` | `MenuService` | `getAdminMenu()` | AID=ENTER primary action -> service business method |
| `COADM01C.cbl` | `RETURN-TO-SIGNON-SCREEN` | `MenuController` | `getAdminMenu()` | Session-expiry routing -> HTTP 401 / re-auth response |
| `COADM01C.cbl` | `SEND-MENU-SCREEN` | `MenuController` | `getAdminMenu()` | BMS SEND MAP -> JSON response DTO |
| `COADM01C.cbl` | `RECEIVE-MENU-SCREEN` | `MenuController` | `getAdminMenu()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COADM01C.cbl` | `POPULATE-HEADER-INFO` | `MenuService` | `getAdminMenu()` | Screen header (title/program/date/time) -> response metadata fields |
| `COADM01C.cbl` | `BUILD-MENU-OPTIONS` | `MenuService` | `getAdminMenu()` | Menu option table (COMEN02Y/COADM02Y) -> `List<MenuOption>` with role filtering |

### COACTVWC.cbl — Account view (txn `CAVW`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTVWC.cbl` | `0000-MAIN` | `AccountController` | `getAccount()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless AccountController [GET /api/accounts/{id}] |
| `COACTVWC.cbl` | `COMMON-RETURN` | `AccountController` | `getAccount()` | CICS RETURN -> stateless HTTP response (no COMMAREA) |
| `COACTVWC.cbl` | `0000-MAIN-EXIT` | `AccountController` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `1000-SEND-MAP` | `AccountController` | `getAccount()` | BMS SEND MAP -> JSON response DTO |
| `COACTVWC.cbl` | `1000-SEND-MAP-EXIT` | `AccountController` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `1100-SCREEN-INIT` | `AccountViewService` | `toViewResponse()` | Screen/array initialization -> response DTO defaults |
| `COACTVWC.cbl` | `1100-SCREEN-INIT-EXIT` | `AccountViewService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTVWC.cbl` | `1200-SETUP-SCREEN-VARS` | `AccountViewService` | `toViewResponse()` | Screen variable setup -> response DTO field population |
| `COACTVWC.cbl` | `1200-SETUP-SCREEN-VARS-EXIT` | `AccountViewService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTVWC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `AccountController` | `getAccount()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COACTVWC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `AccountController` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `1400-SEND-SCREEN` | `AccountController` | `getAccount()` | BMS SEND MAP -> JSON response DTO |
| `COACTVWC.cbl` | `1400-SEND-SCREEN-EXIT` | `AccountController` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `2000-PROCESS-INPUTS` | `AccountViewService` | `getAccount()` | Input processing orchestration -> service method |
| `COACTVWC.cbl` | `2000-PROCESS-INPUTS-EXIT` | `AccountViewService` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `2100-RECEIVE-MAP` | `AccountController` | `getAccount()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COACTVWC.cbl` | `2100-RECEIVE-MAP-EXIT` | `AccountController` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS` | `AccountViewService` | `getAccount()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS-EXIT` | `AccountViewService` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `2210-EDIT-ACCOUNT` | `AccountViewService` | `getAccount()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTVWC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `AccountViewService` | `getAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getAccount()` |
| `COACTVWC.cbl` | `9000-READ-ACCT` | `AccountRepository` | `findById()` | VSAM READ -> JPA finder |
| `COACTVWC.cbl` | `9000-READ-ACCT-EXIT` | `AccountRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT` | `CardXrefRepository` | `findByXrefAcctId()` | XREF-by-account read -> JPA finder |
| `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT-EXIT` | `CardXrefRepository` | `findByXrefAcctId()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findByXrefAcctId()` |
| `COACTVWC.cbl` | `9300-GETACCTDATA-BYACCT` | `AccountRepository` | `findById()` | ACCTDAT keyed read -> JPA finder |
| `COACTVWC.cbl` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST` | `CustomerRepository` | `findById()` | CUSTDAT keyed read -> JPA finder |
| `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST-EXIT` | `CustomerRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COACTVWC.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | `handleUnexpected()` | SEND TEXT (error/long message) -> structured error response body |
| `COACTVWC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |
| `COACTVWC.cbl` | `SEND-LONG-TEXT` | `GlobalExceptionHandler` | `handleUnexpected()` | SEND TEXT (error/long message) -> structured error response body |
| `COACTVWC.cbl` | `SEND-LONG-TEXT-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |
| `COACTVWC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |

### COACTUPC.cbl — Account update (txn `CAUP`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTUPC.cbl` | `0000-MAIN` | `AccountController` | `updateAccount()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless AccountController [PUT /api/accounts/{id}] |
| `COACTUPC.cbl` | `COMMON-RETURN` | `AccountController` | `updateAccount()` | CICS RETURN -> stateless HTTP response (no COMMAREA) |
| `COACTUPC.cbl` | `0000-MAIN-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `1000-PROCESS-INPUTS` | `AccountUpdateService` | `updateAccount()` | Input processing orchestration -> service method |
| `COACTUPC.cbl` | `1000-PROCESS-INPUTS-EXIT` | `AccountUpdateService` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `1100-RECEIVE-MAP` | `AccountController` | `updateAccount()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COACTUPC.cbl` | `1100-RECEIVE-MAP-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS` | `AccountUpdateService` | `validateFields()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS-EXIT` | `AccountUpdateService` | `validateFields()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateFields()` |
| `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW` | `AccountUpdateService` | `applyAccountChanges()` | Old/new field comparison -> change detection prior to @Version-guarded update |
| `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW-EXIT` | `AccountUpdateService` | `applyAccountChanges()` | `PERFORM THRU` exit label — structured early-`return` boundary of `applyAccountChanges()` |
| `COACTUPC.cbl` | `1210-EDIT-ACCOUNT` | `AccountUpdateService` | `validateFields()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1210-EDIT-ACCOUNT-EXIT` | `AccountUpdateService` | `validateFields()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateFields()` |
| `COACTUPC.cbl` | `1215-EDIT-MANDATORY` | `AccountUpdateService` | `editMandatory()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1215-EDIT-MANDATORY-EXIT` | `AccountUpdateService` | `editMandatory()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editMandatory()` |
| `COACTUPC.cbl` | `1220-EDIT-YESNO` | `AccountUpdateService` | `editYesNo()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1220-EDIT-YESNO-EXIT` | `AccountUpdateService` | `editYesNo()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editYesNo()` |
| `COACTUPC.cbl` | `1225-EDIT-ALPHA-REQD` | `AccountUpdateService` | `editAlphaRequired()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1225-EDIT-ALPHA-REQD-EXIT` | `AccountUpdateService` | `editAlphaRequired()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editAlphaRequired()` |
| `COACTUPC.cbl` | `1230-EDIT-ALPHANUM-REQD` | `AccountUpdateService` | `editAlphaRequired()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1230-EDIT-ALPHANUM-REQD-EXIT` | `AccountUpdateService` | `editAlphaRequired()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editAlphaRequired()` |
| `COACTUPC.cbl` | `1235-EDIT-ALPHA-OPT` | `AccountUpdateService` | `editAlphaOptional()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1235-EDIT-ALPHA-OPT-EXIT` | `AccountUpdateService` | `editAlphaOptional()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editAlphaOptional()` |
| `COACTUPC.cbl` | `1240-EDIT-ALPHANUM-OPT` | `AccountUpdateService` | `editAlphaOptional()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1240-EDIT-ALPHANUM-OPT-EXIT` | `AccountUpdateService` | `editAlphaOptional()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editAlphaOptional()` |
| `COACTUPC.cbl` | `1245-EDIT-NUM-REQD` | `AccountUpdateService` | `editNumeric()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1245-EDIT-NUM-REQD-EXIT` | `AccountUpdateService` | `editNumeric()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editNumeric()` |
| `COACTUPC.cbl` | `1250-EDIT-SIGNED-9V2` | `AccountUpdateService` | `editMoney()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1250-EDIT-SIGNED-9V2-EXIT` | `AccountUpdateService` | `editMoney()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editMoney()` |
| `COACTUPC.cbl` | `1260-EDIT-US-PHONE-NUM` | `AccountUpdateService` | `editPhone()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `EDIT-AREA-CODE` | `AccountUpdateService` | `editPhoneArea()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `EDIT-US-PHONE-PREFIX` | `AccountUpdateService` | `editPhonePrefix()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `EDIT-US-PHONE-LINENUM` | `AccountUpdateService` | `editPhoneLine()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `EDIT-US-PHONE-EXIT` | `AccountUpdateService` | `editPhoneLine()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editPhoneLine()` |
| `COACTUPC.cbl` | `1260-EDIT-US-PHONE-NUM-EXIT` | `AccountUpdateService` | `editPhoneLine()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editPhoneLine()` |
| `COACTUPC.cbl` | `1265-EDIT-US-SSN` | `AccountUpdateService` | `editSsn()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1265-EDIT-US-SSN-EXIT` | `AccountUpdateService` | `editSsn()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editSsn()` |
| `COACTUPC.cbl` | `1270-EDIT-US-STATE-CD` | `AccountUpdateService` | `editState()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1270-EDIT-US-STATE-CD-EXIT` | `AccountUpdateService` | `editState()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editState()` |
| `COACTUPC.cbl` | `1275-EDIT-FICO-SCORE` | `AccountUpdateService` | `editFico()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1275-EDIT-FICO-SCORE-EXIT` | `AccountUpdateService` | `editFico()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editFico()` |
| `COACTUPC.cbl` | `1280-EDIT-US-STATE-ZIP-CD` | `AccountUpdateService` | `editStateZip()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COACTUPC.cbl` | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `AccountUpdateService` | `editStateZip()` | `PERFORM THRU` exit label — structured early-`return` boundary of `editStateZip()` |
| `COACTUPC.cbl` | `2000-DECIDE-ACTION` | `AccountUpdateService` | `updateAccount()` | EVALUATE action dispatch -> switch expression (WHEN order preserved) |
| `COACTUPC.cbl` | `2000-DECIDE-ACTION-EXIT` | `AccountUpdateService` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `3000-SEND-MAP` | `AccountController` | `updateAccount()` | BMS SEND MAP -> JSON response DTO |
| `COACTUPC.cbl` | `3000-SEND-MAP-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `3100-SCREEN-INIT` | `AccountUpdateService` | `toViewResponse()` | Screen/array initialization -> response DTO defaults |
| `COACTUPC.cbl` | `3100-SCREEN-INIT-EXIT` | `AccountUpdateService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTUPC.cbl` | `3200-SETUP-SCREEN-VARS` | `AccountUpdateService` | `toViewResponse()` | Screen variable setup -> response DTO field population |
| `COACTUPC.cbl` | `3200-SETUP-SCREEN-VARS-EXIT` | `AccountUpdateService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTUPC.cbl` | `3201-SHOW-INITIAL-VALUES` | `AccountUpdateService` | `toViewResponse()` | Initial field values -> response DTO (initial state) |
| `COACTUPC.cbl` | `3201-SHOW-INITIAL-VALUES-EXIT` | `AccountUpdateService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTUPC.cbl` | `3202-SHOW-ORIGINAL-VALUES` | `AccountUpdateService` | `toViewResponse()` | Original field values -> response DTO (before-image) |
| `COACTUPC.cbl` | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `AccountUpdateService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTUPC.cbl` | `3203-SHOW-UPDATED-VALUES` | `AccountUpdateService` | `toViewResponse()` | Updated field values -> response DTO (after-image) |
| `COACTUPC.cbl` | `3203-SHOW-UPDATED-VALUES-EXIT` | `AccountUpdateService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTUPC.cbl` | `3250-SETUP-INFOMSG` | `AccountUpdateService` | `toViewResponse()` | Info-message setup -> response message field |
| `COACTUPC.cbl` | `3250-SETUP-INFOMSG-EXIT` | `AccountUpdateService` | `toViewResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toViewResponse()` |
| `COACTUPC.cbl` | `3300-SETUP-SCREEN-ATTRS` | `AccountController` | `updateAccount()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COACTUPC.cbl` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `3310-PROTECT-ALL-ATTRS` | `AccountController` | `updateAccount()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COACTUPC.cbl` | `3310-PROTECT-ALL-ATTRS-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `3320-UNPROTECT-FEW-ATTRS` | `AccountController` | `updateAccount()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COACTUPC.cbl` | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `3390-SETUP-INFOMSG-ATTRS` | `AccountController` | `updateAccount()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COACTUPC.cbl` | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `3400-SEND-SCREEN` | `AccountController` | `updateAccount()` | BMS SEND MAP -> JSON response DTO |
| `COACTUPC.cbl` | `3400-SEND-SCREEN-EXIT` | `AccountController` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `9000-READ-ACCT` | `AccountUpdateService` | `loadAccount()` | VSAM READ ACCTDAT -> JPA findById (illustrative spec label was 9100-GETACCT-REQUEST) |
| `COACTUPC.cbl` | `9000-READ-ACCT-EXIT` | `AccountUpdateService` | `loadAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `loadAccount()` |
| `COACTUPC.cbl` | `9200-GETCARDXREF-BYACCT` | `CardXrefRepository` | `findByXrefAcctId()` | XREF-by-account read -> JPA finder |
| `COACTUPC.cbl` | `9200-GETCARDXREF-BYACCT-EXIT` | `CardXrefRepository` | `findByXrefAcctId()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findByXrefAcctId()` |
| `COACTUPC.cbl` | `9300-GETACCTDATA-BYACCT` | `AccountRepository` | `findById()` | ACCTDAT keyed read -> JPA finder |
| `COACTUPC.cbl` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COACTUPC.cbl` | `9400-GETCUSTDATA-BYCUST` | `CustomerRepository` | `findById()` | CUSTDAT keyed read -> JPA finder |
| `COACTUPC.cbl` | `9400-GETCUSTDATA-BYCUST-EXIT` | `CustomerRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA` | `AccountUpdateService` | `loadAccount()` | Stash before-image of fetched records for later change-compare |
| `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA-EXIT` | `AccountUpdateService` | `loadAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `loadAccount()` |
| `COACTUPC.cbl` | `9600-WRITE-PROCESSING` | `AccountUpdateService` | `updateAccount()` | @Transactional(rollbackFor=Exception) dual-record (ACCOUNT+CUSTOMER) atomic rewrite; @Version (spec label PROCESS-UPDATE-ACCT) |
| `COACTUPC.cbl` | `9600-WRITE-PROCESSING-EXIT` | `AccountUpdateService` | `updateAccount()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateAccount()` |
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC` | `AccountUpdateService` | `persist()` | Re-read-and-compare concurrency guard -> JPA @Version optimistic lock (OptimisticLockException) |
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC-EXIT` | `AccountUpdateService` | `persist()` | `PERFORM THRU` exit label — structured early-`return` boundary of `persist()` |
| `COACTUPC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `COACTUPC.cbl` | `ABEND-ROUTINE-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |

### COCRDLIC.cbl — Card list (txn `CCLI`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDLIC.cbl` | `0000-MAIN` | `CardController` | `listCards()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless CardController [GET /api/cards] |
| `COCRDLIC.cbl` | `COMMON-RETURN` | `CardController` | `listCards()` | CICS RETURN -> stateless HTTP response (no COMMAREA) |
| `COCRDLIC.cbl` | `0000-MAIN-EXIT` | `CardController` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `1000-SEND-MAP` | `CardController` | `listCards()` | BMS SEND MAP -> JSON response DTO |
| `COCRDLIC.cbl` | `1000-SEND-MAP-EXIT` | `CardController` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `1100-SCREEN-INIT` | `CardListService` | `buildResponse()` | Screen/array initialization -> response DTO defaults |
| `COCRDLIC.cbl` | `1100-SCREEN-INIT-EXIT` | `CardListService` | `buildResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `buildResponse()` |
| `COCRDLIC.cbl` | `1200-SCREEN-ARRAY-INIT` | `CardListService` | `buildResponse()` | Screen/array initialization -> response DTO defaults |
| `COCRDLIC.cbl` | `1200-SCREEN-ARRAY-INIT-EXIT` | `CardListService` | `buildResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `buildResponse()` |
| `COCRDLIC.cbl` | `1250-SETUP-ARRAY-ATTRIBS` | `CardListService` | `buildResponse()` | Screen variable setup -> response DTO field population |
| `COCRDLIC.cbl` | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `CardListService` | `buildResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `buildResponse()` |
| `COCRDLIC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `CardController` | `listCards()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COCRDLIC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardController` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `1400-SETUP-MESSAGE` | `CardListService` | `buildResponse()` | Screen variable setup -> response DTO field population |
| `COCRDLIC.cbl` | `1400-SETUP-MESSAGE-EXIT` | `CardListService` | `buildResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `buildResponse()` |
| `COCRDLIC.cbl` | `1500-SEND-SCREEN` | `CardController` | `listCards()` | BMS SEND MAP -> JSON response DTO |
| `COCRDLIC.cbl` | `1500-SEND-SCREEN-EXIT` | `CardController` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `2000-RECEIVE-MAP` | `CardController` | `listCards()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COCRDLIC.cbl` | `2000-RECEIVE-MAP-EXIT` | `CardController` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `2100-RECEIVE-SCREEN` | `CardController` | `listCards()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COCRDLIC.cbl` | `2100-RECEIVE-SCREEN-EXIT` | `CardController` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `2200-EDIT-INPUTS` | `CardListService` | `listCards()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDLIC.cbl` | `2200-EDIT-INPUTS-EXIT` | `CardListService` | `listCards()` | `PERFORM THRU` exit label — structured early-`return` boundary of `listCards()` |
| `COCRDLIC.cbl` | `2210-EDIT-ACCOUNT` | `CardListService` | `normalizeCardFilter()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDLIC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `CardListService` | `normalizeCardFilter()` | `PERFORM THRU` exit label — structured early-`return` boundary of `normalizeCardFilter()` |
| `COCRDLIC.cbl` | `2220-EDIT-CARD` | `CardListService` | `normalizeCardFilter()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDLIC.cbl` | `2220-EDIT-CARD-EXIT` | `CardListService` | `normalizeCardFilter()` | `PERFORM THRU` exit label — structured early-`return` boundary of `normalizeCardFilter()` |
| `COCRDLIC.cbl` | `2250-EDIT-ARRAY` | `CardListService` | `buildResponse()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDLIC.cbl` | `2250-EDIT-ARRAY-EXIT` | `CardListService` | `buildResponse()` | `PERFORM THRU` exit label — structured early-`return` boundary of `buildResponse()` |
| `COCRDLIC.cbl` | `9000-READ-FORWARD` | `CardRepository` | `findByCardAcctId()` | STARTBR + READNEXT forward browse -> Pageable ascending query |
| `COCRDLIC.cbl` | `9000-READ-FORWARD-EXIT` | `CardRepository` | `findByCardAcctId()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findByCardAcctId()` |
| `COCRDLIC.cbl` | `9100-READ-BACKWARDS` | `CardRepository` | `findByCardAcctId()` | READPREV backward browse -> Pageable descending query |
| `COCRDLIC.cbl` | `9100-READ-BACKWARDS-EXIT` | `CardRepository` | `findByCardAcctId()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findByCardAcctId()` |
| `COCRDLIC.cbl` | `9500-FILTER-RECORDS` | `CardListService` | `resolveSpecificCard()` | In-memory record filter -> repository query predicate / Specification |
| `COCRDLIC.cbl` | `9500-FILTER-RECORDS-EXIT` | `CardListService` | `resolveSpecificCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `resolveSpecificCard()` |
| `COCRDLIC.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | `handleUnexpected()` | SEND TEXT (error/long message) -> structured error response body |
| `COCRDLIC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |
| `COCRDLIC.cbl` | `SEND-LONG-TEXT` | `GlobalExceptionHandler` | `handleUnexpected()` | SEND TEXT (error/long message) -> structured error response body |
| `COCRDLIC.cbl` | `SEND-LONG-TEXT-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |

### COCRDSLC.cbl — Card detail (txn `CCDL`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDSLC.cbl` | `0000-MAIN` | `CardController` | `getCard()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless CardController [GET /api/cards/{cardNum}] |
| `COCRDSLC.cbl` | `COMMON-RETURN` | `CardController` | `getCard()` | CICS RETURN -> stateless HTTP response (no COMMAREA) |
| `COCRDSLC.cbl` | `0000-MAIN-EXIT` | `CardController` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `1000-SEND-MAP` | `CardController` | `getCard()` | BMS SEND MAP -> JSON response DTO |
| `COCRDSLC.cbl` | `1000-SEND-MAP-EXIT` | `CardController` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `1100-SCREEN-INIT` | `CardDetailService` | `toDetail()` | Screen/array initialization -> response DTO defaults |
| `COCRDSLC.cbl` | `1100-SCREEN-INIT-EXIT` | `CardDetailService` | `toDetail()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toDetail()` |
| `COCRDSLC.cbl` | `1200-SETUP-SCREEN-VARS` | `CardDetailService` | `toDetail()` | Screen variable setup -> response DTO field population |
| `COCRDSLC.cbl` | `1200-SETUP-SCREEN-VARS-EXIT` | `CardDetailService` | `toDetail()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toDetail()` |
| `COCRDSLC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `CardController` | `getCard()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COCRDSLC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardController` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `1400-SEND-SCREEN` | `CardController` | `getCard()` | BMS SEND MAP -> JSON response DTO |
| `COCRDSLC.cbl` | `1400-SEND-SCREEN-EXIT` | `CardController` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `2000-PROCESS-INPUTS` | `CardDetailService` | `getCard()` | Input processing orchestration -> service method |
| `COCRDSLC.cbl` | `2000-PROCESS-INPUTS-EXIT` | `CardDetailService` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `2100-RECEIVE-MAP` | `CardController` | `getCard()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COCRDSLC.cbl` | `2100-RECEIVE-MAP-EXIT` | `CardController` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS` | `CardDetailService` | `getCard()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS-EXIT` | `CardDetailService` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `2210-EDIT-ACCOUNT` | `CardDetailService` | `getCard()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDSLC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `CardDetailService` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `2220-EDIT-CARD` | `CardDetailService` | `getCard()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDSLC.cbl` | `2220-EDIT-CARD-EXIT` | `CardDetailService` | `getCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `getCard()` |
| `COCRDSLC.cbl` | `9000-READ-DATA` | `CardRepository` | `findById()` | VSAM READ -> JPA finder |
| `COCRDSLC.cbl` | `9000-READ-DATA-EXIT` | `CardRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COCRDSLC.cbl` | `9100-GETCARD-BYACCTCARD` | `CardRepository` | `findById()` | CARDDAT keyed read by `CARD-NUM` (account-id cross-checked) -> JPA finder |
| `COCRDSLC.cbl` | `9100-GETCARD-BYACCTCARD-EXIT` | `CardRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COCRDSLC.cbl` | `9150-GETCARD-BYACCT` | `CardRepository` | `findByCardAcctId()` | CARDDAT by-account read -> JPA finder |
| `COCRDSLC.cbl` | `9150-GETCARD-BYACCT-EXIT` | `CardRepository` | `findByCardAcctId()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findByCardAcctId()` |
| `COCRDSLC.cbl` | `SEND-LONG-TEXT` | `GlobalExceptionHandler` | `handleUnexpected()` | SEND TEXT (error/long message) -> structured error response body |
| `COCRDSLC.cbl` | `SEND-LONG-TEXT-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |
| `COCRDSLC.cbl` | `SEND-PLAIN-TEXT` | `GlobalExceptionHandler` | `handleUnexpected()` | SEND TEXT (error/long message) -> structured error response body |
| `COCRDSLC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |
| `COCRDSLC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |

### COCRDUPC.cbl — Card update (txn `CCUP`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDUPC.cbl` | `0000-MAIN` | `CardController` | `updateCard()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless CardController [PUT /api/cards/{cardNum}] |
| `COCRDUPC.cbl` | `COMMON-RETURN` | `CardController` | `updateCard()` | CICS RETURN -> stateless HTTP response (no COMMAREA) |
| `COCRDUPC.cbl` | `0000-MAIN-EXIT` | `CardController` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `1000-PROCESS-INPUTS` | `CardUpdateService` | `updateCard()` | Input processing orchestration -> service method |
| `COCRDUPC.cbl` | `1000-PROCESS-INPUTS-EXIT` | `CardUpdateService` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `1100-RECEIVE-MAP` | `CardController` | `updateCard()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COCRDUPC.cbl` | `1100-RECEIVE-MAP-EXIT` | `CardController` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS` | `CardUpdateService` | `validateInputs()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS-EXIT` | `CardUpdateService` | `validateInputs()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateInputs()` |
| `COCRDUPC.cbl` | `1210-EDIT-ACCOUNT` | `CardUpdateService` | `validateInputs()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1210-EDIT-ACCOUNT-EXIT` | `CardUpdateService` | `validateInputs()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateInputs()` |
| `COCRDUPC.cbl` | `1220-EDIT-CARD` | `CardUpdateService` | `validateInputs()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1220-EDIT-CARD-EXIT` | `CardUpdateService` | `validateInputs()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateInputs()` |
| `COCRDUPC.cbl` | `1230-EDIT-NAME` | `CardUpdateService` | `validateName()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1230-EDIT-NAME-EXIT` | `CardUpdateService` | `validateName()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateName()` |
| `COCRDUPC.cbl` | `1240-EDIT-CARDSTATUS` | `CardUpdateService` | `validateStatus()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1240-EDIT-CARDSTATUS-EXIT` | `CardUpdateService` | `validateStatus()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateStatus()` |
| `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON` | `CardUpdateService` | `validateExpiryMonth()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON-EXIT` | `CardUpdateService` | `validateExpiryMonth()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateExpiryMonth()` |
| `COCRDUPC.cbl` | `1260-EDIT-EXPIRY-YEAR` | `CardUpdateService` | `validateExpiryYear()` | Field edit/validation -> Jakarta Bean Validation constraint / service validation |
| `COCRDUPC.cbl` | `1260-EDIT-EXPIRY-YEAR-EXIT` | `CardUpdateService` | `validateExpiryYear()` | `PERFORM THRU` exit label — structured early-`return` boundary of `validateExpiryYear()` |
| `COCRDUPC.cbl` | `2000-DECIDE-ACTION` | `CardUpdateService` | `updateCard()` | EVALUATE action dispatch -> switch expression (WHEN order preserved) |
| `COCRDUPC.cbl` | `2000-DECIDE-ACTION-EXIT` | `CardUpdateService` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `3000-SEND-MAP` | `CardController` | `updateCard()` | BMS SEND MAP -> JSON response DTO |
| `COCRDUPC.cbl` | `3000-SEND-MAP-EXIT` | `CardController` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `3100-SCREEN-INIT` | `CardUpdateService` | `toDetail()` | Screen/array initialization -> response DTO defaults |
| `COCRDUPC.cbl` | `3100-SCREEN-INIT-EXIT` | `CardUpdateService` | `toDetail()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toDetail()` |
| `COCRDUPC.cbl` | `3200-SETUP-SCREEN-VARS` | `CardUpdateService` | `toDetail()` | Screen variable setup -> response DTO field population |
| `COCRDUPC.cbl` | `3200-SETUP-SCREEN-VARS-EXIT` | `CardUpdateService` | `toDetail()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toDetail()` |
| `COCRDUPC.cbl` | `3250-SETUP-INFOMSG` | `CardUpdateService` | `toDetail()` | Info-message setup -> response message field |
| `COCRDUPC.cbl` | `3250-SETUP-INFOMSG-EXIT` | `CardUpdateService` | `toDetail()` | `PERFORM THRU` exit label — structured early-`return` boundary of `toDetail()` |
| `COCRDUPC.cbl` | `3300-SETUP-SCREEN-ATTRS` | `CardController` | `updateCard()` | BMS field attributes (protect/colour) -> not applicable in REST; response shaping only |
| `COCRDUPC.cbl` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `CardController` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `3400-SEND-SCREEN` | `CardController` | `updateCard()` | BMS SEND MAP -> JSON response DTO |
| `COCRDUPC.cbl` | `3400-SEND-SCREEN-EXIT` | `CardController` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `9000-READ-DATA` | `CardRepository` | `findById()` | VSAM READ -> JPA finder |
| `COCRDUPC.cbl` | `9000-READ-DATA-EXIT` | `CardRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD` | `CardRepository` | `findById()` | CARDDAT keyed read by `CARD-NUM` (account-id cross-checked) -> JPA finder |
| `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD-EXIT` | `CardRepository` | `findById()` | `PERFORM THRU` exit label — structured early-`return` boundary of `findById()` |
| `COCRDUPC.cbl` | `9200-WRITE-PROCESSING` | `CardUpdateService` | `updateCard()` | @Transactional REWRITE CARDDAT -> repository save; @Version optimistic lock |
| `COCRDUPC.cbl` | `9200-WRITE-PROCESSING-EXIT` | `CardUpdateService` | `updateCard()` | `PERFORM THRU` exit label — structured early-`return` boundary of `updateCard()` |
| `COCRDUPC.cbl` | `9300-CHECK-CHANGE-IN-REC` | `CardUpdateService` | `persist()` | Re-read-and-compare concurrency guard -> JPA @Version optimistic lock (OptimisticLockException) |
| `COCRDUPC.cbl` | `9300-CHECK-CHANGE-IN-REC-EXIT` | `CardUpdateService` | `persist()` | `PERFORM THRU` exit label — structured early-`return` boundary of `persist()` |
| `COCRDUPC.cbl` | `ABEND-ROUTINE` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `COCRDUPC.cbl` | `ABEND-ROUTINE-EXIT` | `GlobalExceptionHandler` | `handleUnexpected()` | `PERFORM THRU` exit label — structured early-`return` boundary of `handleUnexpected()` |

### COTRN00C.cbl — Transaction list (txn `CT00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN00C.cbl` | `MAIN-PARA` | `TransactionController` | `listTransactions()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless TransactionController [GET /api/transactions] |
| `COTRN00C.cbl` | `PROCESS-ENTER-KEY` | `TransactionListService` | `listTransactions()` | AID=ENTER primary action -> service business method. `TRNIDINI` filter edit (D-048): blank -> `findAll(Pageable)` browse from start (`MOVE LOW-VALUES TO TRAN-ID`); numeric -> positioned `findByTranIdGreaterThanEqual` (`STARTBR ... GTEQ`); non-numeric -> `ValidationException("Tran ID must be Numeric ...")` (line 214) -> HTTP 400. The `SEL00nnI` row-select edit (`'Invalid selection. Valid value is S'`, line 199) is re-homed to `GET /api/transactions/{id}` (`COTRN01C`), so the list contract carries no row-select field |
| `COTRN00C.cbl` | `PROCESS-PF7-KEY` | `TransactionListService` | `listTransactions()` | PF7 page-up -> Pageable previous page query |
| `COTRN00C.cbl` | `PROCESS-PF8-KEY` | `TransactionListService` | `listTransactions()` | PF8 page-down -> Pageable next page query |
| `COTRN00C.cbl` | `PROCESS-PAGE-FORWARD` | `TransactionListService` | `listTransactions()` | PF8 page-down -> Pageable next page query |
| `COTRN00C.cbl` | `PROCESS-PAGE-BACKWARD` | `TransactionListService` | `listTransactions()` | PF7 page-up -> Pageable previous page query |
| `COTRN00C.cbl` | `POPULATE-TRAN-DATA` | `TransactionListService` | `toSummary()` | Transaction row -> TransactionDto list element |
| `COTRN00C.cbl` | `INITIALIZE-TRAN-DATA` | `TransactionListService` | `listTransactions()` | Working-storage / screen reset -> new response DTO instance (stateless) |
| `COTRN00C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | `listTransactions()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COTRN00C.cbl` | `SEND-TRNLST-SCREEN` | `TransactionController` | `listTransactions()` | BMS SEND MAP -> JSON response DTO |
| `COTRN00C.cbl` | `RECEIVE-TRNLST-SCREEN` | `TransactionController` | `listTransactions()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COTRN00C.cbl` | `POPULATE-HEADER-INFO` | `TransactionListService` | `buildResponse()` | Screen header (title/program/date/time) -> response metadata fields |
| `COTRN00C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` / `findByTranIdGreaterThanEqual(String, Pageable)` | VSAM STARTBR -> open Pageable browse: an unfiltered browse positions at the start via `findAll(Pageable)`; a numeric `TRNIDINI` filter positions at the key via `findByTranIdGreaterThanEqual` (`STARTBR ... GTEQ`, D-048) |
| `COTRN00C.cbl` | `READNEXT-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` / `findByTranIdGreaterThanEqual(String, Pageable)` | VSAM READNEXT -> next page element (forward read from the positioned key, caller `Sort.by(ASC,"tranId")`) |
| `COTRN00C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM READPREV -> previous page element |
| `COTRN00C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM ENDBR -> close browse cursor |

### COTRN01C.cbl — Transaction detail (txn `CT01`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN01C.cbl` | `MAIN-PARA` | `TransactionController` | `getTransaction()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless TransactionController [GET /api/transactions/{id}] |
| `COTRN01C.cbl` | `PROCESS-ENTER-KEY` | `TransactionDetailService` | `getTransaction()` | AID=ENTER primary action -> service business method |
| `COTRN01C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | `getTransaction()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COTRN01C.cbl` | `SEND-TRNVIEW-SCREEN` | `TransactionController` | `getTransaction()` | BMS SEND MAP -> JSON response DTO |
| `COTRN01C.cbl` | `RECEIVE-TRNVIEW-SCREEN` | `TransactionController` | `getTransaction()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COTRN01C.cbl` | `POPULATE-HEADER-INFO` | `TransactionDetailService` | `toDetail()` | Screen header (title/program/date/time) -> response metadata fields |
| `COTRN01C.cbl` | `READ-TRANSACT-FILE` | `TransactionRepository` | `findById()` | READ TRANSACT -> JPA finder |
| `COTRN01C.cbl` | `CLEAR-CURRENT-SCREEN` | `TransactionDetailService` | `getTransaction()` | Working-storage / screen reset -> new response DTO instance (stateless) |
| `COTRN01C.cbl` | `INITIALIZE-ALL-FIELDS` | `TransactionDetailService` | `getTransaction()` | Working-storage / screen reset -> new response DTO instance (stateless) |

### COTRN02C.cbl — Transaction add (txn `CT02`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN02C.cbl` | `MAIN-PARA` | `TransactionController` | `addTransaction()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless TransactionController [POST /api/transactions] |
| `COTRN02C.cbl` | `PROCESS-ENTER-KEY` | `TransactionAddService` | `addTransaction()` | AID=ENTER primary action -> service business method |
| `COTRN02C.cbl` | `VALIDATE-INPUT-KEY-FIELDS` | `TransactionAddService` | `validateAndResolveKeyFields()` | Key-field numeric/presence checks + CXACAIX/CCXREF cross-reference resolution (account-id -> card via `resolveByAccountId`/`findByXrefAcctId`; card -> account via `resolveByCardNumber`/`findById`); runs before data-field validation |
| `COTRN02C.cbl` | `VALIDATE-INPUT-DATA-FIELDS` | `TransactionAddService` | `validateDataFields()` | Input validation -> @Valid DTO constraints + service checks |
| `COTRN02C.cbl` | `ADD-TRANSACTION` | `TransactionAddService` | `addTransaction()` | @Transactional WRITE -> repository save (auto-ID generation) |
| `COTRN02C.cbl` | `COPY-LAST-TRAN-DATA` | `TransactionAddService` | `buildTransaction()` | Copy prior transaction -> pre-populate add-request defaults |
| `COTRN02C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | `addTransaction()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COTRN02C.cbl` | `SEND-TRNADD-SCREEN` | `TransactionController` | `addTransaction()` | BMS SEND MAP -> JSON response DTO |
| `COTRN02C.cbl` | `RECEIVE-TRNADD-SCREEN` | `TransactionController` | `addTransaction()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COTRN02C.cbl` | `POPULATE-HEADER-INFO` | `TransactionAddService` | `toDetail()` | Screen header (title/program/date/time) -> response metadata fields |
| `COTRN02C.cbl` | `READ-CXACAIX-FILE` | `CardXrefRepository` | `findByXrefAcctId()` | READ CXACAIX/CCXREF -> JPA finder (alternate index) |
| `COTRN02C.cbl` | `READ-CCXREF-FILE` | `CardXrefRepository` | `findById()` | READ CCXREF by primary key (`XREF-CARD-NUM`) -> inherited `findById` primary-key lookup (validates the card, derives the account) |
| `COTRN02C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM STARTBR -> open Pageable browse |
| `COTRN02C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM READPREV -> previous page element |
| `COTRN02C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM ENDBR -> close browse cursor |
| `COTRN02C.cbl` | `WRITE-TRANSACT-FILE` | `TransactionRepository` | `save()` | WRITE TRANSACT -> JPA insert |
| `COTRN02C.cbl` | `CLEAR-CURRENT-SCREEN` | `TransactionAddService` | `addTransaction()` | Working-storage / screen reset -> new response DTO instance (stateless) |
| `COTRN02C.cbl` | `INITIALIZE-ALL-FIELDS` | `TransactionAddService` | `addTransaction()` | Working-storage / screen reset -> new response DTO instance (stateless) |

### COBIL00C.cbl — Bill payment (txn `CB00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COBIL00C.cbl` | `MAIN-PARA` | `BillingController` | `payBill()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless BillingController [POST /api/billing/pay] |
| `COBIL00C.cbl` | `PROCESS-ENTER-KEY` | `BillingService` | `payBill()` | AID=ENTER primary action -> service business method |
| `COBIL00C.cbl` | `GET-CURRENT-TIMESTAMP` | `BillingService` | `executePaymentWithRetry()` | EXEC CICS ASKTIME / DB2 timestamp format -> java.time.LocalDateTime / Instant |
| `COBIL00C.cbl` | `RETURN-TO-PREV-SCREEN` | `BillingController` | `payBill()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COBIL00C.cbl` | `SEND-BILLPAY-SCREEN` | `BillingController` | `payBill()` | BMS SEND MAP -> JSON response DTO |
| `COBIL00C.cbl` | `RECEIVE-BILLPAY-SCREEN` | `BillingController` | `payBill()` | BMS RECEIVE MAP -> @RequestBody/@Valid request DTO binding |
| `COBIL00C.cbl` | `POPULATE-HEADER-INFO` | `BillingService` | `payBill()` | Screen header (title/program/date/time) -> response metadata fields |
| `COBIL00C.cbl` | `READ-ACCTDAT-FILE` | `AccountRepository` | `findById()` | READ ACCTDAT -> JPA finder |
| `COBIL00C.cbl` | `UPDATE-ACCTDAT-FILE` | `BillingService` | `executePaymentWithRetry()` | @Transactional REWRITE -> repository save |
| `COBIL00C.cbl` | `READ-CXACAIX-FILE` | `CardXrefRepository` | `findByXrefAcctId()` | READ CXACAIX/CCXREF -> JPA finder (alternate index) |
| `COBIL00C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM STARTBR -> open Pageable browse |
| `COBIL00C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM READPREV -> previous page element |
| `COBIL00C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | `findAll(Pageable)` | VSAM ENDBR -> close browse cursor |
| `COBIL00C.cbl` | `WRITE-TRANSACT-FILE` | `TransactionRepository` | `save()` | WRITE TRANSACT -> JPA insert |
| `COBIL00C.cbl` | `CLEAR-CURRENT-SCREEN` | `BillingService` | `payBill()` | Working-storage / screen reset -> new response DTO instance (stateless) |
| `COBIL00C.cbl` | `INITIALIZE-ALL-FIELDS` | `BillingService` | `payBill()` | Working-storage / screen reset -> new response DTO instance (stateless) |

### CORPT00C.cbl — Report submit (txn `CR00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CORPT00C.cbl` | `MAIN-PARA` | `ReportController` | `submitReport()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless ReportController [POST /api/reports/submit] |
| `CORPT00C.cbl` | `PROCESS-ENTER-KEY` | `ReportService` | `submitReport()` | AID=ENTER primary action -> service business method |
| `CORPT00C.cbl` | `SUBMIT-JOB-TO-INTRDR` | `ReportService` | `publishReportRequest()` | CICS internal reader job submit -> publish to SQS FIFO carddemo-report-jobs.fifo |
| `CORPT00C.cbl` | `WIRTE-JOBSUB-TDQ` | `ReportService` | `publishReportRequest()` | TDQ JOBS WRITEQ (source spelling preserved) -> SQS FIFO message publish |
| `CORPT00C.cbl` | `SUBMIT-JOB-TO-INTRDR` | `ReportJobConsumer` | `onReportRequest()` | CICS internal-reader asynchronous job PICKUP/RUN half of the bridge -> `@SqsListener` consumes the FIFO message and launches `transactionReportJob` via `JobLauncher.run` (submit-then-process, D-004/D-069) |
| `CORPT00C.cbl` | `RETURN-TO-PREV-SCREEN` | `ReportController` | `submitReport()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `CORPT00C.cbl` | `SEND-TRNRPT-SCREEN` | `ReportController` | `submitReport()` | BMS SEND MAP -> `ResponseEntity<Void>` 202 Accepted |
| `CORPT00C.cbl` | `RETURN-TO-CICS` | `ReportController` | `submitReport()` | CICS RETURN -> stateless HTTP response (no COMMAREA) |
| `CORPT00C.cbl` | `RECEIVE-TRNRPT-SCREEN` | `ReportController` | `submitReport(SubmitRequest)` | BMS RECEIVE MAP -> `@Valid @RequestBody ReportDto.SubmitRequest` binding |
| `CORPT00C.cbl` | `POPULATE-HEADER-INFO` | `ReportService` | `submitReport()` | Screen header (title/program/date/time) -> service response assembly |
| `CORPT00C.cbl` | `INITIALIZE-ALL-FIELDS` | `ReportService` | `submitReport()` | Working-storage / screen reset -> stateless per-request handling |

### COUSR00C.cbl — User list (txn `CU00`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR00C.cbl` | `MAIN-PARA` | `UserController` | `listUsers()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless UserController [GET /api/admin/users] |
| `COUSR00C.cbl` | `PROCESS-ENTER-KEY` | `UserListService` | `listUsers()` | AID=ENTER primary action -> service business method |
| `COUSR00C.cbl` | `PROCESS-PF7-KEY` | `UserListService` | `listUsers()` | PF7 page-up -> Pageable previous page query |
| `COUSR00C.cbl` | `PROCESS-PF8-KEY` | `UserListService` | `listUsers()` | PF8 page-down -> Pageable next page query |
| `COUSR00C.cbl` | `PROCESS-PAGE-FORWARD` | `UserListService` | `listUsers()` | PF8 page-down -> Pageable next page query |
| `COUSR00C.cbl` | `PROCESS-PAGE-BACKWARD` | `UserListService` | `listUsers()` | PF7 page-up -> Pageable previous page query |
| `COUSR00C.cbl` | `POPULATE-USER-DATA` | `UserListService` | `toSummary()` | User row -> UserDto list element |
| `COUSR00C.cbl` | `INITIALIZE-USER-DATA` | `UserListService` | `listUsers()` | Working-storage / screen reset -> new response DTO instance (stateless) |
| `COUSR00C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | `listUsers()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COUSR00C.cbl` | `SEND-USRLST-SCREEN` | `UserController` | `listUsers()` | BMS SEND MAP -> `ResponseEntity<UserDto.ListResponse>` |
| `COUSR00C.cbl` | `RECEIVE-USRLST-SCREEN` | `UserController` | `listUsers()` | BMS RECEIVE MAP -> `@RequestParam` query binding (userId filter, page) |
| `COUSR00C.cbl` | `POPULATE-HEADER-INFO` | `UserListService` | `listUsers()` | Screen header (title/program/date/time) -> response metadata fields |
| `COUSR00C.cbl` | `STARTBR-USER-SEC-FILE` | `UserRepository` | `findAll(Pageable)` | VSAM STARTBR -> open Pageable browse |
| `COUSR00C.cbl` | `READNEXT-USER-SEC-FILE` | `UserRepository` | `findAll(Pageable)` | VSAM READNEXT -> next page element |
| `COUSR00C.cbl` | `READPREV-USER-SEC-FILE` | `UserRepository` | `findAll(Pageable)` | VSAM READPREV -> previous page element |
| `COUSR00C.cbl` | `ENDBR-USER-SEC-FILE` | `UserRepository` | `findAll(Pageable)` | VSAM ENDBR -> close browse cursor |

### COUSR01C.cbl — User add (txn `CU01`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR01C.cbl` | `MAIN-PARA` | `UserController` | `addUser()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless UserController [POST /api/admin/users] |
| `COUSR01C.cbl` | `PROCESS-ENTER-KEY` | `UserAddService` | `addUser()` | AID=ENTER primary action -> service business method |
| `COUSR01C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | `addUser()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COUSR01C.cbl` | `SEND-USRADD-SCREEN` | `UserController` | `addUser()` | BMS SEND MAP -> `ResponseEntity<UserDto.UserSummary>` (201 Created) |
| `COUSR01C.cbl` | `RECEIVE-USRADD-SCREEN` | `UserController` | `addUser(CreateRequest)` | BMS RECEIVE MAP -> `@Valid @RequestBody UserDto.CreateRequest` binding |
| `COUSR01C.cbl` | `POPULATE-HEADER-INFO` | `UserAddService` | `addUser()` | Screen header (title/program/date/time) -> `UserDto.UserSummary` assembly |
| `COUSR01C.cbl` | `WRITE-USER-SEC-FILE` | `UserRepository` | `save()` | WRITE USRSEC -> JPA insert (BCrypt-hashed password) |
| `COUSR01C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserAddService` | `addUser()` | Working-storage / screen reset -> stateless per-request handling |
| `COUSR01C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserAddService` | `addUser()` | Working-storage / screen reset -> stateless per-request handling |

### COUSR02C.cbl — User update (txn `CU02`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR02C.cbl` | `MAIN-PARA` | `UserController` | `updateUser()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless UserController [PUT /api/admin/users/{userId}] |
| `COUSR02C.cbl` | `PROCESS-ENTER-KEY` | `UserUpdateService` | `updateUser()` | AID=ENTER primary action -> service business method |
| `COUSR02C.cbl` | `UPDATE-USER-INFO` | `UserUpdateService` | `updateUser()` | @Transactional REWRITE -> repository save |
| `COUSR02C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | `updateUser()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COUSR02C.cbl` | `SEND-USRUPD-SCREEN` | `UserController` | `updateUser()` | BMS SEND MAP -> `ResponseEntity<UserDto.UserSummary>` (200 OK) |
| `COUSR02C.cbl` | `RECEIVE-USRUPD-SCREEN` | `UserController` | `updateUser(UpdateRequest)` | BMS RECEIVE MAP -> `@Valid @RequestBody UserDto.UpdateRequest` binding |
| `COUSR02C.cbl` | `POPULATE-HEADER-INFO` | `UserUpdateService` | `updateUser()` | Screen header (title/program/date/time) -> `UserDto.UserSummary` assembly |
| `COUSR02C.cbl` | `READ-USER-SEC-FILE` | `UserRepository` | `findById()` | READ USRSEC -> JPA finder |
| `COUSR02C.cbl` | `UPDATE-USER-SEC-FILE` | `UserUpdateService` | `updateUser()` | @Transactional REWRITE -> repository save |
| `COUSR02C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserUpdateService` | `updateUser()` | Working-storage / screen reset -> stateless per-request handling |
| `COUSR02C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserUpdateService` | `updateUser()` | Working-storage / screen reset -> stateless per-request handling |

### COUSR03C.cbl — User delete (txn `CU03`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR03C.cbl` | `MAIN-PARA` | `UserController` | `deleteUser()` | CICS pseudo-conversational entry (EIBCALEN dispatch) -> stateless UserController [DELETE /api/admin/users/{userId}] |
| `COUSR03C.cbl` | `PROCESS-ENTER-KEY` | `UserDeleteService` | `deleteUser()` | AID=ENTER primary action -> service business method |
| `COUSR03C.cbl` | `DELETE-USER-INFO` | `UserDeleteService` | `deleteUser()` | @Transactional DELETE -> repository delete |
| `COUSR03C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserController` | `deleteUser()` | Pseudo-conversational back-nav -> HTTP response (client-driven navigation) |
| `COUSR03C.cbl` | `SEND-USRDEL-SCREEN` | `UserController` | `deleteUser()` | BMS SEND MAP -> `ResponseEntity<UserDto.DeleteResponse>` (200 OK) |
| `COUSR03C.cbl` | `RECEIVE-USRDEL-SCREEN` | `UserController` | `deleteUser(userId)` | BMS RECEIVE MAP -> `@PathVariable userId` (no request body) |
| `COUSR03C.cbl` | `POPULATE-HEADER-INFO` | `UserDeleteService` | `deleteUser()` | Screen header (title/program/date/time) -> response metadata fields |
| `COUSR03C.cbl` | `READ-USER-SEC-FILE` | `UserRepository` | `findById()` | READ USRSEC -> JPA finder |
| `COUSR03C.cbl` | `DELETE-USER-SEC-FILE` | `UserDeleteService` | `deleteUser()` | @Transactional DELETE -> repository delete |
| `COUSR03C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserDeleteService` | `deleteUser()` | Working-storage / screen reset -> new response DTO instance (stateless) |
| `COUSR03C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserDeleteService` | `deleteUser()` | Working-storage / screen reset -> new response DTO instance (stateless) |

### CSUTLDTC.cbl — Date validation utility (LE CEEDAYS wrapper) (txn `-`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `validateCcyymmdd()` | Date-validation mainline for an 8-char `CCYYMMDD` string -> `LocalDate` parse/validate (replaces LE CEEDAYS); enforces exact length 8 and all-digit content before parsing |
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `validateDateParts()` | Same validation entered from separate `YYYY`/`MM`/`DD` segments (assembles then validates); used where callers supply discrete date fields |
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `isValidDate()` | Non-throwing boolean predicate variant of the `CCYYMMDD` date-validation routine |
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `parseIsoDate()` | Strict hyphenated `uuuu-MM-dd` parse used where a date arrives in ISO form |
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `validateDateOfBirth()` | Date-of-birth validation (not-null, strictly before current date); overloads for segment and `LocalDate` inputs |
| `CSUTLDTC.cbl` | `A000-MAIN-EXIT` | `DateValidationService` | `validateCcyymmdd()` | `PERFORM THRU` exit label — structured early-`return` boundary of the date-validation routine |

## 4. Batch Programs — Paragraph → Java Mapping

The 10 batch programs. JCL step sequencing and condition codes are preserved by the Spring Batch
`Job`/`Step`/`Flow` model (see §7); COMP-3 arithmetic is reproduced with `BigDecimal` scale-2 and
`RoundingMode.HALF_EVEN`.

### CBTRN01C.cbl — Daily transaction validation driver (job `READACCT (daily-txn validation driver)`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN01C.cbl` | `MAIN-PARA` | `PostTransactionJobConfig` | `postTransactionJob()` | Batch mainline -> Spring Batch Job (postTransactionJob) sequencing the validation/posting step |
| `CBTRN01C.cbl` | `1000-DALYTRAN-GET-NEXT` | `DailyTransactionItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBTRN01C.cbl` | `1000-DALYTRAN-GET-NEXT` | `FileStatusMapper` | `isEndOfFile()` / `isSuccess()` | AT END (FILE STATUS `10`) and success (`00`) evaluation within the read loop -> typed status predicates |
| `CBTRN01C.cbl` | `2000-LOOKUP-XREF` | `CardXrefRepository` | `findById()` | XREF keyed lookup -> CardXrefRepository |
| `CBTRN01C.cbl` | `3000-READ-ACCOUNT` | `AccountRepository` | `findById()` | ACCTFILE keyed read -> JPA finder |
| `CBTRN01C.cbl` | `0000-DALYTRAN-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN01C.cbl` | `0100-CUSTFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN01C.cbl` | `0200-XREFFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN01C.cbl` | `0300-CARDFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN01C.cbl` | `0400-ACCTFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN01C.cbl` | `0500-TRANFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN01C.cbl` | `9000-DALYTRAN-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN01C.cbl` | `9100-CUSTFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN01C.cbl` | `9200-XREFFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN01C.cbl` | `9300-CARDFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN01C.cbl` | `9400-ACCTFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN01C.cbl` | `9500-TRANFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN01C.cbl` | `Z-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBTRN01C.cbl` | `Z-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBTRN02C.cbl — Transaction posting engine (job `POSTTRAN`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN02C.cbl` | `0000-DALYTRAN-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN02C.cbl` | `0100-TRANFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN02C.cbl` | `0200-XREFFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN02C.cbl` | `0300-DALYREJS-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN02C.cbl` | `0400-ACCTFILE-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN02C.cbl` | `0500-TCATBALF-OPEN` | `DailyTransactionItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN02C.cbl` | `1000-DALYTRAN-GET-NEXT` | `DailyTransactionItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBTRN02C.cbl` | `1500-VALIDATE-TRAN` | `TransactionPostingProcessor` | `validate()` | 4-stage validation cascade preserved (card -> account -> credit-limit -> expiration); reject codes 100-109 |
| `CBTRN02C.cbl` | `1500-A-LOOKUP-XREF` | `TransactionPostingProcessor` | `validate()` | Stage 1: card/xref existence; reject code 100 (INVALID CARD NUMBER) |
| `CBTRN02C.cbl` | `1500-B-LOOKUP-ACCT` | `TransactionPostingProcessor` | `validate()` | Stages 2-4: account existence (101), credit-limit/overlimit (102), after-expiration (103) |
| `CBTRN02C.cbl` | `2000-POST-TRANSACTION` | `TransactionPostingProcessor` | `process()` | Posting orchestration -> ItemProcessor.process() (valid txns) |
| `CBTRN02C.cbl` | `2500-WRITE-REJECT-REC` | `RejectTransactionWriter` | `write()` | Rejected record -> S3 rejects file with reason trailer (DALYREJS equivalent) |
| `CBTRN02C.cbl` | `2700-UPDATE-TCATBAL` | `TransactionPostingProcessor` | `updateCategoryBalance()` | Category-balance upsert dispatch (create-or-update branch) |
| `CBTRN02C.cbl` | `2700-UPDATE-TCATBAL` | `TransactionCategoryBalanceRepository` | `findById()` | Keyed READ TCATBAL on full composite key {acctId,typeCd,catCd} -> JPA findById (FILE STATUS `23` not-found -> create branch) |
| `CBTRN02C.cbl` | `2700-A-CREATE-TCATBAL-REC` | `TransactionCategoryBalanceRepository` | `save()` | New category-balance row -> JPA insert |
| `CBTRN02C.cbl` | `2700-B-UPDATE-TCATBAL-REC` | `TransactionCategoryBalanceRepository` | `save()` | Category-balance accumulation -> JPA update |
| `CBTRN02C.cbl` | `2800-UPDATE-ACCOUNT-REC` | `AccountRepository` | `save()` | Account balance rewrite -> JPA save within @Transactional chunk |
| `CBTRN02C.cbl` | `2900-WRITE-TRANSACTION-FILE` | `PostedTransactionWriter` | `write()` | Posted transaction -> DB insert + S3 backup |
| `CBTRN02C.cbl` | `9000-DALYTRAN-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN02C.cbl` | `9100-TRANFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN02C.cbl` | `9200-XREFFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN02C.cbl` | `9300-DALYREJS-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN02C.cbl` | `9400-ACCTFILE-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN02C.cbl` | `9500-TCATBALF-CLOSE` | `DailyTransactionItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN02C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `TransactionPostingProcessor` | `buildPostedTransaction()` | EXEC CICS ASKTIME / DB2 timestamp format -> java.time.LocalDateTime / Instant |
| `CBTRN02C.cbl` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBTRN02C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBTRN03C.cbl — Transaction detail report (job `TRANREPT`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN03C.cbl` | `0550-DATEPARM-READ` | `TransactionReportJobConfig` | `parseReportBound()` | DATEPARM PS read -> Spring Batch JobParameters (start/end date) |
| `CBTRN03C.cbl` | `1000-TRANFILE-GET-NEXT` | `TransactionReportJobConfig` | `transactionReportReader()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBTRN03C.cbl` | `1100-WRITE-TRANSACTION-REPORT` | `TransactionReportProcessor` | `buildDetailLine()` | Report detail line rendering (CVTRA07Y layout) |
| `CBTRN03C.cbl` | `1110-WRITE-PAGE-TOTALS` | `TransactionReportProcessor` | `emitPageTotals()` | Page-level subtotal accumulation |
| `CBTRN03C.cbl` | `1120-WRITE-ACCOUNT-TOTALS` | `TransactionReportProcessor` | `emitAccountTotals()` | Account-level total accumulation |
| `CBTRN03C.cbl` | `1110-WRITE-GRAND-TOTALS` | `TransactionReportProcessor` | `emitGrandTotals()` | Report grand-total accumulation |
| `CBTRN03C.cbl` | `1120-WRITE-HEADERS` | `TransactionReportProcessor` | `emitHeaders()` | Report header lines (CVTRA07Y layout) |
| `CBTRN03C.cbl` | `1111-WRITE-REPORT-REC` | `TransactionReportProcessor` | `buildDetailLine()` | Report detail line (CVTRA07Y layout) |
| `CBTRN03C.cbl` | `1120-WRITE-DETAIL` | `TransactionReportProcessor` | `buildDetailLine()` | Report detail line (CVTRA07Y layout) |
| `CBTRN03C.cbl` | `0000-TRANFILE-OPEN` | `TransactionReportJobConfig` | `transactionReportReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN03C.cbl` | `0100-REPTFILE-OPEN` | `TransactionReportJobConfig` | `transactionReportReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN03C.cbl` | `0200-CARDXREF-OPEN` | `TransactionReportJobConfig` | `transactionReportReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN03C.cbl` | `0300-TRANTYPE-OPEN` | `TransactionReportJobConfig` | `transactionReportReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN03C.cbl` | `0400-TRANCATG-OPEN` | `TransactionReportJobConfig` | `transactionReportReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN03C.cbl` | `0500-DATEPARM-OPEN` | `TransactionReportJobConfig` | `transactionReportReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBTRN03C.cbl` | `1500-A-LOOKUP-XREF` | `TransactionReportProcessor` | `lookupAccountId()` | XREF keyed lookup -> CardXrefRepository |
| `CBTRN03C.cbl` | `1500-B-LOOKUP-TRANTYPE` | `TransactionReportProcessor` | `lookupTypeDescription()` | TRANTYPE lookup -> TransactionTypeRepository (enrichment) |
| `CBTRN03C.cbl` | `1500-C-LOOKUP-TRANCATG` | `TransactionReportProcessor` | `lookupCategoryDescription()` | TRANCATG lookup -> TransactionCategoryRepository (enrichment) |
| `CBTRN03C.cbl` | `9000-TRANFILE-CLOSE` | `TransactionReportJobConfig` | `transactionReportReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN03C.cbl` | `9100-REPTFILE-CLOSE` | `TransactionReportJobConfig` | `transactionReportReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN03C.cbl` | `9200-CARDXREF-CLOSE` | `TransactionReportJobConfig` | `transactionReportReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN03C.cbl` | `9300-TRANTYPE-CLOSE` | `TransactionReportJobConfig` | `transactionReportReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN03C.cbl` | `9400-TRANCATG-CLOSE` | `TransactionReportJobConfig` | `transactionReportReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN03C.cbl` | `9500-DATEPARM-CLOSE` | `TransactionReportJobConfig` | `transactionReportReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBTRN03C.cbl` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBTRN03C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBACT01C.cbl — Account master file reader (job `READACCT`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT01C.cbl` | `1000-ACCTFILE-GET-NEXT` | `AccountItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBACT01C.cbl` | `1000-ACCTFILE-GET-NEXT` | `FileStatusMapper` | `isEndOfFile()` / `isSuccess()` | AT END (FILE STATUS `10`) and success (`00`) evaluation within the read loop -> typed status predicates |
| `CBACT01C.cbl` | `1100-DISPLAY-ACCT-RECORD` | `AccountItemReader` | `read()` | DISPLAY record -> structured debug log of mapped entity |
| `CBACT01C.cbl` | `0000-ACCTFILE-OPEN` | `AccountItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT01C.cbl` | `9000-ACCTFILE-CLOSE` | `AccountItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT01C.cbl` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBACT01C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBACT02C.cbl — Card master file reader (job `READCARD`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT02C.cbl` | `1000-CARDFILE-GET-NEXT` | `CardItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBACT02C.cbl` | `1000-CARDFILE-GET-NEXT` | `FileStatusMapper` | `isEndOfFile()` / `isSuccess()` | AT END (FILE STATUS `10`) and success (`00`) evaluation within the read loop -> typed status predicates |
| `CBACT02C.cbl` | `0000-CARDFILE-OPEN` | `CardItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT02C.cbl` | `9000-CARDFILE-CLOSE` | `CardItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT02C.cbl` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBACT02C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBACT03C.cbl — Card cross-reference file reader (job `READXREF`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT03C.cbl` | `1000-XREFFILE-GET-NEXT` | `CardXrefItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBACT03C.cbl` | `1000-XREFFILE-GET-NEXT` | `FileStatusMapper` | `isEndOfFile()` / `isSuccess()` | AT END (FILE STATUS `10`) and success (`00`) evaluation within the read loop -> typed status predicates |
| `CBACT03C.cbl` | `0000-XREFFILE-OPEN` | `CardXrefItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT03C.cbl` | `9000-XREFFILE-CLOSE` | `CardXrefItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT03C.cbl` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBACT03C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBACT04C.cbl — Interest calculation (job `INTCALC`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT04C.cbl` | `0000-TCATBALF-OPEN` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT04C.cbl` | `0100-XREFFILE-OPEN` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT04C.cbl` | `0200-DISCGRP-OPEN` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT04C.cbl` | `0300-ACCTFILE-OPEN` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT04C.cbl` | `0400-TRANFILE-OPEN` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBACT04C.cbl` | `1000-TCATBALF-GET-NEXT` | `InterestCalculationJobConfig` | `interestCalculationReader()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBACT04C.cbl` | `1000-TCATBALF-GET-NEXT` | `TransactionCategoryBalanceRepository` | `findAll()` | Sequential TCATBAL scan (ACCESS MODE SEQUENTIAL) -> JPA findAll() / findAll(Pageable) backing the chunk reader |
| `CBACT04C.cbl` | `1050-UPDATE-ACCOUNT` | `AccountRepository` | `save()` | Account balance rewrite -> JPA save within @Transactional chunk |
| `CBACT04C.cbl` | `1100-GET-ACCT-DATA` | `AccountRepository` | `findById()` | ACCTFILE keyed read -> JPA finder |
| `CBACT04C.cbl` | `1110-GET-XREF-DATA` | `CardXrefRepository` | `findByXrefAcctId()` | XREF keyed read -> JPA finder |
| `CBACT04C.cbl` | `1200-GET-INTEREST-RATE` | `InterestProcessor` | `resolveInterestRate()` | DISCGRP keyed lookup -> DisclosureGroupRepository |
| `CBACT04C.cbl` | `1200-A-GET-DEFAULT-INT-RATE` | `InterestProcessor` | `resolveInterestRate()` | DEFAULT disclosure-group fallback, absorbed into `resolveInterestRate` when the specific group key is not found |
| `CBACT04C.cbl` | `1300-COMPUTE-INTEREST` | `InterestProcessor` | `computeMonthlyInterest()` | Formula (TRAN-CAT-BAL * DIS-INT-RATE)/1200 in BigDecimal, RoundingMode.HALF_EVEN |
| `CBACT04C.cbl` | `1300-B-WRITE-TX` | `PostedTransactionWriter` | `write()` | Interest/fee transaction -> DB insert + S3 backup |
| `CBACT04C.cbl` | `1400-COMPUTE-FEES` | `InterestProcessor` | `—` | Intentionally empty in COBOL (`1400-COMPUTE-FEES` is a `* To be implemented` stub containing only `EXIT.`); no fee logic exists, so no Java method — documented no-op |
| `CBACT04C.cbl` | `9000-TCATBALF-CLOSE` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT04C.cbl` | `9100-XREFFILE-CLOSE` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT04C.cbl` | `9200-DISCGRP-CLOSE` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT04C.cbl` | `9300-ACCTFILE-CLOSE` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT04C.cbl` | `9400-TRANFILE-CLOSE` | `InterestCalculationJobConfig` | `interestCalculationReader()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBACT04C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `InterestProcessor` | `buildInterestTransaction()` | EXEC CICS ASKTIME / DB2 timestamp format -> java.time.LocalDateTime / Instant |
| `CBACT04C.cbl` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBACT04C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBCUS01C.cbl — Customer master file reader (job `READCUST`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBCUS01C.cbl` | `1000-CUSTFILE-GET-NEXT` | `CustomerItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBCUS01C.cbl` | `1000-CUSTFILE-GET-NEXT` | `FileStatusMapper` | `isEndOfFile()` / `isSuccess()` | AT END (FILE STATUS `10`) and success (`00`) evaluation within the read loop -> typed status predicates |
| `CBCUS01C.cbl` | `0000-CUSTFILE-OPEN` | `CustomerItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBCUS01C.cbl` | `9000-CUSTFILE-CLOSE` | `CustomerItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBCUS01C.cbl` | `Z-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |
| `CBCUS01C.cbl` | `Z-DISPLAY-IO-STATUS` | `FileStatusMapper` | `check()` | FILE STATUS display -> status-code-to-exception mapping + structured log |

### CBSTM03A.CBL — Statement generation (text + HTML) (job `CREASTMT`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03A.CBL` | `0000-START` | `StatementProcessor` | `process()` | Batch mainline -> chunk-oriented Step (reader/processor/writer) driven by CREASTMT |
| `CBSTM03A.CBL` | `1000-MAINLINE` | `StatementProcessor` | `process()` | Batch mainline -> chunk-oriented Step (reader/processor/writer) driven by CREASTMT |
| `CBSTM03A.CBL` | `9999-GOBACK` | `StatementProcessor` | `process()` | GOBACK / step return -> ItemProcessor completion |
| `CBSTM03A.CBL` | `1000-XREFFILE-GET-NEXT` | `CardXrefItemReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (null at EOF) |
| `CBSTM03A.CBL` | `2000-CUSTFILE-GET` | `CustomerRepository` | `findById()` | CUSTFILE keyed read -> JPA finder |
| `CBSTM03A.CBL` | `3000-ACCTFILE-GET` | `AccountRepository` | `findById()` | ACCTFILE keyed read -> JPA finder |
| `CBSTM03A.CBL` | `4000-TRNXFILE-GET` | `TransactionRepository` | `findByCardNumOrderByTranIdAsc()` | TRNXFILE per-card read -> JPA finder (ordered by tran id) |
| `CBSTM03A.CBL` | `5000-CREATE-STATEMENT` | `StatementProcessor` | `process()` | Per-customer statement assembly (text + HTML) |
| `CBSTM03A.CBL` | `5100-WRITE-HTML-HEADER` | `StatementProcessor` | `renderHtml()` | HTML statement header rendering |
| `CBSTM03A.CBL` | `5100-EXIT` | `StatementProcessor` | `renderHtml()` | `PERFORM THRU` exit label — structured early-`return` boundary of `renderHtml()` |
| `CBSTM03A.CBL` | `5200-WRITE-HTML-NMADBS` | `StatementProcessor` | `renderHtml()` | HTML name/address/balance block rendering |
| `CBSTM03A.CBL` | `5200-EXIT` | `StatementProcessor` | `renderHtml()` | `PERFORM THRU` exit label — structured early-`return` boundary of `renderHtml()` |
| `CBSTM03A.CBL` | `6000-WRITE-TRANS` | `StatementProcessor` | `renderText()` | Statement transaction detail rendering |
| `CBSTM03A.CBL` | `8100-FILE-OPEN` | `CardXrefItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBSTM03A.CBL` | `8100-TRNXFILE-OPEN` | `CardXrefItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBSTM03A.CBL` | `8200-XREFFILE-OPEN` | `CardXrefItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBSTM03A.CBL` | `8300-CUSTFILE-OPEN` | `CardXrefItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBSTM03A.CBL` | `8400-ACCTFILE-OPEN` | `CardXrefItemReader` | `open()` | FD OPEN -> Spring Batch ItemStream.open() / reader init (JPA datasource) |
| `CBSTM03A.CBL` | `8500-READTRNX-READ` | `TransactionRepository` | `findByCardNumOrderByTranIdAsc()` | Keyed READ of TRNXFILE (READ-K) -> JPA finder by card (ordered by tran id) |
| `CBSTM03A.CBL` | `8599-EXIT` | `TransactionRepository` | `findByCardNumOrderByTranIdAsc()` | `PERFORM THRU` exit label of the keyed per-card transaction read |
| `CBSTM03A.CBL` | `9100-TRNXFILE-CLOSE` | `CardXrefItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBSTM03A.CBL` | `9200-XREFFILE-CLOSE` | `CardXrefItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBSTM03A.CBL` | `9300-CUSTFILE-CLOSE` | `CardXrefItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBSTM03A.CBL` | `9400-ACCTFILE-CLOSE` | `CardXrefItemReader` | `close()` | FD CLOSE -> Spring Batch ItemStream.close() / reader teardown |
| `CBSTM03A.CBL` | `9999-ABEND-PROGRAM` | `GlobalExceptionHandler` | `handleUnexpected()` | ABEND routine -> @ControllerAdvice exception translation (catch-all Exception → HTTP 500) |

### CBSTM03B.CBL — Callable file-service subroutine (job `CREASTMT (file-service subroutine)`)

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03B.CBL` | `0000-START` | `FileServiceReader` | `execute()` | Batch mainline -> chunk-oriented Step (reader/processor/writer) driven by CREASTMT (file-service subroutine) |
| `CBSTM03B.CBL` | `9999-GOBACK` | `FileServiceReader` | `execute()` | GOBACK / step return -> ItemProcessor completion |
| `CBSTM03B.CBL` | `1000-TRNXFILE-PROC` | `FileServiceReader` | `readTransaction()` | File-service request op (OPEN/READ/READ-K/CLOSE) -> injected reader bean method |
| `CBSTM03B.CBL` | `1900-EXIT` | `FileServiceReader` | `readTransaction()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readTransaction()` |
| `CBSTM03B.CBL` | `1999-EXIT` | `FileServiceReader` | `readTransaction()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readTransaction()` |
| `CBSTM03B.CBL` | `2000-XREFFILE-PROC` | `FileServiceReader` | `readCardXref()` | File-service request op (OPEN/READ/READ-K/CLOSE) -> injected reader bean method |
| `CBSTM03B.CBL` | `2900-EXIT` | `FileServiceReader` | `readCardXref()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readCardXref()` |
| `CBSTM03B.CBL` | `2999-EXIT` | `FileServiceReader` | `readCardXref()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readCardXref()` |
| `CBSTM03B.CBL` | `3000-CUSTFILE-PROC` | `FileServiceReader` | `readCustomerByKey()` | File-service request op (OPEN/READ/READ-K/CLOSE) -> injected reader bean method |
| `CBSTM03B.CBL` | `3900-EXIT` | `FileServiceReader` | `readCustomerByKey()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readCustomerByKey()` |
| `CBSTM03B.CBL` | `3999-EXIT` | `FileServiceReader` | `readCustomerByKey()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readCustomerByKey()` |
| `CBSTM03B.CBL` | `4000-ACCTFILE-PROC` | `FileServiceReader` | `readAccountByKey()` | File-service request op (OPEN/READ/READ-K/CLOSE) -> injected reader bean method |
| `CBSTM03B.CBL` | `4900-EXIT` | `FileServiceReader` | `readAccountByKey()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readAccountByKey()` |
| `CBSTM03B.CBL` | `4999-EXIT` | `FileServiceReader` | `readAccountByKey()` | `PERFORM THRU` exit label — structured early-`return` boundary of `readAccountByKey()` |

## 5. Copybooks → Entities / DTOs / Enums / Services

All 28 copybooks under `app/cpy/`. Record layouts (byte lengths preserved) become JPA entities;
COMMAREA/work areas become stateless request context; lookup/validation copybooks become shared
services and externalized JSON resources.

| Copybook | Record / Purpose | Java Target | Notes |
|---|---|---|---|
| `CVACT01Y.cpy` | ACCOUNT-RECORD (300B) | `entity.Account` (+ `repository.AccountRepository`) | COMP-3 `ACCT-CURR-BAL`/`ACCT-CREDIT-LIMIT` → `BigDecimal(2)`; `@Version` optimistic lock |
| `CVACT02Y.cpy` | CARD-RECORD (150B) | `entity.Card` (+ `repository.CardRepository`) | FK to `Account`; active-status enum; `@Version` |
| `CVACT03Y.cpy` | CARD-XREF-RECORD (50B) | `entity.CardXref` (+ `repository.CardXrefRepository`) | XREFFILE base+AIX READ @ `27d6c6f`: base key `XREF-CARD-NUM` READ → inherited `findById()`; CXACAIX alternate index (`XREF-ACCT-ID`, `KEYS(11,25) NONUNIQUEKEY`) → `findByXrefAcctId()`; `CHAR(16)` PK mapped via `@JdbcTypeCode(SqlTypes.CHAR)`; FILLER X(14) not persisted |
| `CVCUS01Y.cpy` | CUSTOMER-RECORD (500B) | `entity.Customer` (+ `repository.CustomerRepository`) | `@Version`; 500-byte field mapping; SSN handling |
| `CUSTREC.cpy` | CUSTOMER-RECORD (shared copy) | `entity.Customer` | Same 500B layout as `CVCUS01Y` (shared record definition) |
| `CVTRA05Y.cpy` | TRAN-RECORD (350B) | `entity.Transaction` (+ `repository.TransactionRepository`) | `BigDecimal` `TRAN-AMT` (scale 2); `CHAR(26)` text timestamps; `TRAN-TYPE-CD` → `TransactionTypeCode` via `@Convert`; FILLER X(20) not persisted; field-level mapping in §5.1; @ `27d6c6f` |
| `CVTRA06Y.cpy` | DALYTRAN-RECORD (350B) | `entity.DailyTransaction` (+ `repository.DailyTransactionRepository`) | Batch staging table; mirrors `Transaction` layout |
| `CVTRA01Y.cpy` | TRAN-CAT-BAL-RECORD | `entity.TransactionCategoryBalance` + `entity.TransactionCategoryBalanceId` | `@EmbeddedId` (acctId + typeCode + catCode) |
| `CVTRA02Y.cpy` | DIS-GROUP-RECORD (50B) | `com.carddemo.entity.DisclosureGroup` + `com.carddemo.entity.DisclosureGroupId` | `@EmbeddedId` (`DisclosureGroupId`); `DIS-INT-RATE S9(04)V99` → `BigDecimal` `dis_int_rate NUMERIC(6,2)` (precision=6, scale=2); feeds interest formula `(TRAN-CAT-BAL × DIS-INT-RATE)/1200` (CBACT04C → `InterestProcessor`, HALF_EVEN); no `@Version`; FILLER X(28) not persisted; @ `27d6c6f` |
| `CVTRA03Y.cpy` | TRAN-TYPE-RECORD | `entity.TransactionType` | 2-byte type-code PK; read-only reference data |
| `CVTRA03Y.cpy` | TRAN-TYPE → TransactionTypeConverter (enum bridge) | `entity.TransactionTypeConverter` | JPA `@Converter(autoApply=false)`: `enums.TransactionTypeCode` ↔ `transactions.tran_type_cd CHAR(2)`; null/blank-safe decode; @ `27d6c6f` |
| `CVTRA04Y.cpy` | TRAN-CAT-RECORD | `entity.TransactionCategory` + `entity.TransactionCategoryId` | `@EmbeddedId`; `TRAN-CAT-KEY` group → `TransactionCategoryId` (`tranTypeCd` + `tranCatCd`) @ `27d6c6f` |
| `CSUSR01Y.cpy` | SEC-USER-DATA (80B) | `com.carddemo.entity.User` | Plaintext password → BCrypt column `VARCHAR(60)` (constraint C-003); `user_type` kept as `CHAR(1)` String (no enum) |
| `COSTM01.CPY` | Statement TRNX reporting layout | `dto.StatementDto` | Statement layout consumed by `StatementProcessor` (CBSTM03A) |
| `COMEN02Y.cpy` | Main-menu option table (10) | `service.MenuService` + `dto.MenuOption` | 10-option routing metadata |
| `COADM02Y.cpy` | Admin-menu option table (4) | `service.MenuService` + `dto.MenuOption` | 4-option admin routing metadata |
| `COCOM01Y.cpy` | CARDDEMO-COMMAREA | JWT claims (`JwtTokenService`) + per-request DTOs (e.g. `AuthDto.SigninResponse`) | Cross-screen state → stateless JWT + DTO; no server session |
| `CVCRD01Y.cpy` | CC-WORK-AREAS (card work) | Request-context work fields (transient) | Card scratch/work fields → per-request context |
| `CSDAT01Y.cpy` | Date working storage | `service.DateValidationService` | Date WS fields → `java.time.LocalDate` |
| `CSUTLDWY.cpy` | Date-validation work fields | `service.DateValidationService` | CCYYMMDD / leap-year / range work area |
| `CSUTLDPY.cpy` | Date-parameter passing area | `service.DateValidationService` | Date parameter structure for validation calls |
| `CSLKPCDY.cpy` | NANPA / US-state / ZIP lookup tables | `service.ValidationLookupService` + `resources/validation/*.json` | Externalized to `nanpa-area-codes.json`, `us-state-codes.json`, `state-zip-prefixes.json` |
| `CSMSG01Y.cpy` | User-message constants | Message constants / `enums` | Response message catalog |
| `CSMSG02Y.cpy` | ABEND-DATA message structure | Exception message constants (`GlobalExceptionHandler`) | Abend messages → structured error payloads |
| `COTTL01Y.cpy` | Banner / title constants | Application title constants | Header/title metadata in responses |
| `CSSETATY.cpy` | Invalid-field highlight attributes | Controller bean-validation error mapping | Field highlight → structured `400` field-error responses |
| `CSSTRPFY.cpy` | EIBAID / PF-key equates | Controller request params / resource paths | PF3/PF7/PF8 (exit/page) → REST params & paths |
| `CVTRA07Y.cpy` | Daily-txn report header/detail/total lines | `batch.processor.TransactionReportProcessor` + report DTO | Report header/detail/total formatting layout |
| `UNUSED1Y.cpy` | Reserved / unused (80B) | **— (none)** | **Intentionally unmapped — reserved/unused; no behavior to migrate** (see §8) |

### 5.1 Field-Level Mapping: `CVTRA05Y` → `entity.Transaction` (posted `transactions`)

Field-by-field mapping of `TRAN-RECORD` (copybook `app/cpy/CVTRA05Y.cpy`, RECLN 350) @ `27d6c6f` to
the `com.carddemo.entity.Transaction` JPA entity and the `transactions` table defined in
`V1__create_schema.sql`. Decimal exactness (`BigDecimal` scale 2), fixed-width `CHAR` alignment
(`@JdbcTypeCode(SqlTypes.CHAR)` + `columnDefinition`), and byte-for-byte text timestamps are preserved
per §0.6.1 / §0.6.4. The trailing `FILLER` is reserved padding and is intentionally not persisted.

| COBOL field (`CVTRA05Y`) | PIC | Java field | Java type | `transactions` column (V1) |
|---|---|---|---|---|
| `TRAN-ID` | `X(16)` | `tranId` | `String` | `tran_id CHAR(16)` — `@Id`, `@JdbcTypeCode(CHAR)` |
| `TRAN-TYPE-CD` | `X(02)` | `transactionType` | `enums.TransactionTypeCode` | `tran_type_cd CHAR(2)` — `@Convert(TransactionTypeConverter.class)`, `@JdbcTypeCode(CHAR)` |
| `TRAN-CAT-CD` | `9(04)` | `tranCatCd` | `Integer` | `tran_cat_cd INTEGER` |
| `TRAN-SOURCE` | `X(10)` | `tranSource` | `String` | `tran_source VARCHAR(10)` |
| `TRAN-DESC` | `X(100)` | `tranDesc` | `String` | `tran_desc VARCHAR(100)` |
| `TRAN-AMT` | `S9(09)V99` | `tranAmt` | `BigDecimal` | `tran_amt NUMERIC(11,2)` — scale 2, `HALF_EVEN` |
| `TRAN-MERCHANT-ID` | `9(09)` | `merchantId` | `Long` | `merchant_id BIGINT` |
| `TRAN-MERCHANT-NAME` | `X(50)` | `merchantName` | `String` | `merchant_name VARCHAR(50)` |
| `TRAN-MERCHANT-CITY` | `X(50)` | `merchantCity` | `String` | `merchant_city VARCHAR(50)` |
| `TRAN-MERCHANT-ZIP` | `X(10)` | `merchantZip` | `String` | `merchant_zip VARCHAR(10)` |
| `TRAN-CARD-NUM` | `X(16)` | `cardNum` | `String` | `card_num CHAR(16)` — `@JdbcTypeCode(CHAR)` |
| `TRAN-ORIG-TS` | `X(26)` | `origTs` | `String` | `orig_ts CHAR(26)` — text timestamp `YYYY-MM-DD HH:MM:SS.mmmmmm`, verbatim |
| `TRAN-PROC-TS` | `X(26)` | `procTs` | `String` | `proc_ts CHAR(26)` — text timestamp, verbatim |
| `FILLER` | `X(20)` | — | — | not persisted (reserved padding; intentionally unmapped) |

Entity-identity (`equals`/`hashCode`) keys on `tranId` only. The entity carries **no `@Version`**
(the `transactions` table has no version column) and **no `@GeneratedValue`** (transaction-ID
generation is owned by `TransactionAddService`). Hibernate `ddl-auto=validate` verified against a real
PostgreSQL 16 instance with the V1 schema; converter round-trip confirmed for seeded codes `01`–`07`.

## 6. BMS Mapsets + Symbolic Maps → DTO Field Contracts (REFERENCE)

The 17 BMS mapsets (`app/bms/*.bms`) and their 17 symbolic map copybooks (`app/cpy-bms/*.CPY`) are
**REFERENCE** specifications: they generate no Java file of their own but define the **byte-accurate
field contracts** the REST DTOs preserve (field names, `PIC` lengths, validation semantics, AID/PF-key
behavior).

| BMS Mapset | Symbolic Map | Java DTO(s) | Controller | Notes |
|---|---|---|---|---|
| `COSGN00.bms` | `COSGN00.CPY` | `AuthDto.SigninRequest`, `AuthDto.SigninResponse` | `AuthController` | Sign-on field layout → auth request/response |
| `COMEN01.bms` | `COMEN01.CPY` | `MenuOption` (response) | `MenuController` | Main-menu field layout |
| `COADM01.bms` | `COADM01.CPY` | `MenuOption` (response) | `MenuController` | Admin-menu field layout |
| `COACTVW.bms` | `COACTVW.CPY` | `AccountDto` (view) | `AccountController` | Account view field lengths |
| `COACTUP.bms` | `COACTUP.CPY` | `AccountDto` (update) | `AccountController` | Account update field lengths + validation |
| `COCRDLI.bms` | `COCRDLI.CPY` | `CardDto` (list) | `CardController` | Card-list field contract (7 rows/page) |
| `COCRDSL.bms` | `COCRDSL.CPY` | `CardDto` (detail) | `CardController` | Card-detail field contract |
| `COCRDUP.bms` | `COCRDUP.CPY` | `CardDto` (update) | `CardController` | Card-update field contract |
| `COTRN00.bms` | `COTRN00.CPY` | `TransactionDto` (list) | `TransactionController` | Txn-list field contract (10 rows/page) |
| `COTRN01.bms` | `COTRN01.CPY` | `TransactionDto` (detail) | `TransactionController` | Txn-detail field contract |
| `COTRN02.bms` | `COTRN02.CPY` | `TransactionDto` (add) | `TransactionController` | Txn-add field contract |
| `COBIL00.bms` | `COBIL00.CPY` | `BillPaymentRequest` | `BillingController` | Bill-pay field contract |
| `CORPT00.bms` | `CORPT00.CPY` | `ReportDto.SubmitRequest` | `ReportController` | Report-criteria fields (monthly/yearly/custom) |
| `COUSR00.bms` | `COUSR00.CPY` | `UserDto.ListResponse` / `UserDto.UserSummary` | `UserController` | User-list field contract |
| `COUSR01.bms` | `COUSR01.CPY` | `UserDto.CreateRequest` / `UserDto.UserSummary` | `UserController` | User-add field contract |
| `COUSR02.bms` | `COUSR02.CPY` | `UserDto.UpdateRequest` / `UserDto.UserSummary` | `UserController` | User-update field contract |
| `COUSR03.bms` | `COUSR03.CPY` | `UserDto.DeleteResponse` | `UserController` | User-delete field contract |

## 7. JCL → Spring Batch / Schema / Storage / Lifecycle

All 29 application JCL jobs (`app/jcl/*`) plus the 3 build/compile procedures (`samples/jcl/*`). VSAM
`DEFINE CLUSTER` jobs become Flyway DDL; batch pipeline JCL becomes Spring Batch jobs; GDG/report
provisioning becomes LocalStack S3/SQS; CICS region lifecycle JCL maps to Spring Boot lifecycle
(REFERENCE, no direct target file).

### 7.1 VSAM / index provisioning → Flyway migrations

| JCL Job | Role | Java / SQL Target | Notes |
|---|---|---|---|
| `ACCTFILE.jcl` | IDCAMS DEFINE ACCT KSDS | `db/migration/V1__create_schema.sql` | `accounts` table (300B layout, PK = acct id) |
| `CARDFILE.jcl` | IDCAMS DEFINE CARD KSDS | `V1__create_schema.sql` | `cards` table (150B, PK = card number) |
| `CUSTFILE.jcl` | IDCAMS DEFINE CUST KSDS | `V1__create_schema.sql` | `customers` table (500B, PK = customer id) |
| `XREFFILE.jcl` | IDCAMS DEFINE XREF KSDS + AIX | `V1__create_schema.sql` (+ `V2__create_indexes.sql`) | `card_cross_reference` table + CXACAIX → secondary index |
| `TRANFILE.jcl` | IDCAMS DEFINE TRAN KSDS + AIX | `V1__create_schema.sql` (+ `V2__create_indexes.sql`) | `transactions` table (350B) + alternate index |
| `TCATBALF.jcl` | IDCAMS DEFINE TCATBAL KSDS | `V1__create_schema.sql` | `transaction_category_balance` table (composite key) |
| `TRANCATG.jcl` | IDCAMS DEFINE TRANCATG KSDS | `V1__create_schema.sql` | `transaction_category` table (composite key) |
| `TRANTYPE.jcl` | IDCAMS DEFINE TRANTYPE KSDS | `V1__create_schema.sql` | `transaction_type` table (2-byte PK) |
| `DISCGRP.jcl` | IDCAMS DEFINE DISCGRP KSDS | `V1__create_schema.sql` | `disclosure_group` table (composite key) |
| `DUSRSECJ.jcl` | IDCAMS DEFINE USRSEC KSDS | `V1__create_schema.sql` | `user_security` table (80B; BCrypt password column) |
| `DEFCUST.jcl` | IDCAMS DEFINE customer (alt) | `V1__create_schema.sql` | Customer cluster define (consolidated into `customers`) |
| `TRANIDX.jcl` | IDCAMS BLDINDEX | `V2__create_indexes.sql` | Transaction secondary index build |

#### 7.1.1 Card VSAM access paths → `CardRepository` methods

| VSAM File / Index (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `CARDDAT` / `CARDAIX` (`CVACT02Y.cpy`) | `READ` / `REWRITE` / `STARTBR`+`READNEXT`/`READPREV` browse @ `27d6c6f` | `repository.CardRepository` | `findById` (keyed `READ` by `CARD-NUM`) · `save` (`REWRITE`/`WRITE`, `@Version` optimistic lock) · `findByCardAcctId` and `findByCardAcctId(Pageable)` (`CARDAIX` `NONUNIQUEKEY` by `CARD-ACCT-ID`) · `findAll(Pageable)` (unfiltered card-number browse) |

#### 7.1.2 Customer VSAM access paths → `CustomerRepository` methods

| VSAM File (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `CUSTFILE` (`CVCUS01Y.cpy` / `CUSTREC.cpy`, RECLN 500, key `CUST-ID PIC 9(09)`) | keyed `READ` / `REWRITE` (`COACTVWC`, `COACTUPC`) · sequential key-order `READ` (`CBCUS01C`) @ `27d6c6f` | `repository.CustomerRepository` | `findById` (keyed `READ` by `CUST-ID`) · `save` (`REWRITE`, `COACTUPC` dual-record ACCOUNT+CUSTOMER atomic update, `@Version` optimistic lock) · `findAll(Sort)` / `findAll()` (`CBCUS01C` sequential master read in key order, consumed by `batch.reader.CustomerItemReader`) |

#### 7.1.3 USRSEC VSAM access paths → `UserRepository` methods

| VSAM File (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `USRSEC` (`CSUSR01Y.cpy`, 80B, key `SEC-USR-ID`) | keyed `READ` / `WRITE` / `REWRITE` / `DELETE` / `STARTBR`+`READNEXT`/`READPREV` browse @ `27d6c6f` | `repository.UserRepository` | `findById` (keyed `READ` by `SEC-USR-ID` — sign-on `COSGN00C`, read-before-update `COUSR02C`, read-before-delete `COUSR03C`) · `findAll(Pageable)` with `Sort.by("userId")` (`STARTBR`/`READNEXT`/`READPREV` user-list browse `COUSR00C`, PF7/PF8 → page params) · `save` (`WRITE` add `COUSR01C` / `REWRITE` update `COUSR02C`) · `deleteById` (`DELETE` `COUSR03C`). No custom finders — all access is by the primary key; plaintext→BCrypt credential verification is an `AuthService` concern (C-003), never in the repository. |

#### 7.1.4 DALYTRAN staging access path → `DailyTransactionRepository` methods

| Staging File (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `DALYTRAN` (`CVTRA06Y.cpy`) | sequential `READ` (CBTRN02C `1000-DALYTRAN-GET-NEXT`, `OPEN INPUT`) / keyed `READ` / `WRITE`+`REWRITE` of the daily feed @ `27d6c6f` | `repository.DailyTransactionRepository` | `findAll` / `findAll(Pageable)` (sequential staging scan in key order via the chunk-oriented posting reader) · `findById` (keyed `READ` by `DALYTRAN-ID`) · `save` (`WRITE`/`REWRITE` of the staging feed); no custom finders — no non-key access path |

#### 7.1.5 Transaction-type VSAM access path → `TransactionTypeRepository` method

| VSAM File / Index (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `TRANTYPE` (`CVTRA03Y.cpy`) | keyed `READ` for report enrichment (`CBTRN03C` `1500-B-LOOKUP-TRANTYPE`) @ `27d6c6f` | `repository.TransactionTypeRepository` | TRANTYPE (CVTRA03Y): keyed `READ` → `findById` (by `TRAN-TYPE`, `CHAR(2)` `String` key) · full load → `findAll` (enrichment caching) · `WRITE`/`REWRITE` → `save`. No custom finders; the composite-keyed `TRANCATG` table is served separately by `TransactionCategoryRepository`. |
#### 7.1.6 Transaction-category VSAM access path → `TransactionCategoryRepository` method

| VSAM File / Index (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `TRANCATG` (`CVTRA04Y.cpy`) | keyed `READ` for report enrichment (`CBTRN03C` `1500-C-LOOKUP-TRANCATG`) @ `27d6c6f` | `repository.TransactionCategoryRepository` | TRANCATG (CVTRA04Y): keyed `READ` → `findById` (composite `{tranTypeCd, tranCatCd}` = `TransactionCategoryId`) · full load → `findAll` (enrichment caching) · `WRITE`/`REWRITE` → `save`. No custom finders — no non-key access path. |

#### 7.1.7 Disclosure-group VSAM access path → `DisclosureGroupRepository` method

| VSAM File (Copybook) | Access Pattern | Java Target | Repository Methods |
|---|---|---|---|
| `DISCGRP` (`CVTRA02Y.cpy`, RECLN 50, key `DIS-GROUP-KEY` = `DIS-ACCT-GROUP-ID X(10)` + `DIS-TRAN-TYPE-CD X(02)` + `DIS-TRAN-CAT-CD 9(04)`) | keyed `READ` for the interest rate (`CBACT04C` `1200-GET-INTEREST-RATE`; not-found `'23'` → DEFAULT fallback `1200-A-GET-DEFAULT-INT-RATE`) @ `27d6c6f` | `repository.DisclosureGroupRepository` | DISCGRP (CVTRA02Y): keyed `READ` → `findById` (composite `{acctGroupId, tranTypeCd, tranCatCd}`) · `WRITE` → `save` · DEFAULT fallback handled in service. `DIS-INT-RATE` kept as `BigDecimal` (exact precision) feeding `(TRAN-CAT-BAL × DIS-INT-RATE)/1200`. No custom finders — the only access path is the full composite key. |

### 7.2 Batch pipeline → Spring Batch jobs

| JCL Job | COBOL Driver | Java Target | Notes |
|---|---|---|---|
| `POSTTRAN.jcl` | `CBTRN02C.cbl` | `batch.job.PostTransactionJobConfig` (+ `batch.processor.TransactionPostingProcessor`, `batch.writer.PostingResultWriter` → `PostedTransactionWriter` / `RejectTransactionWriter`) | 4-stage validation cascade; `ExitStatus("COMPLETED_WITH_REJECTS")` for RC=4 parity; condition codes → `JobExecutionDecider` |
| `INTCALC.jcl` | `CBACT04C.cbl` | `batch.job.InterestCalculationJobConfig` (+ `batch.processor.InterestProcessor`) | PARM date `2022071800` (`yyyyMMddHH`) → validated `JobParameters` (`InterestJobParametersValidator`); `(bal×rate)/1200` `BigDecimal` HALF_EVEN |
| `COMBTRAN.jcl` | (DFSORT/REPRO) | `batch.job.CombineTransactionsJobConfig` (+ `batch.processor.TransactionCombineComparator`) | DFSORT concat + sort by txn-id → Java `Comparator` (identical key/duplicate semantics) |
| `TRANREPT.jcl` | `CBTRN03C.cbl` | `batch.job.TransactionReportJobConfig` (+ `batch.processor.TransactionReportProcessor`) | Date-window report (validated `reportStartDate`/`reportEndDate`, `ReportJobParametersValidator`); XREF/TRANTYPE/TRANCATG enrichment; S3 output |
| `CREASTMT.JCL` | `CBSTM03A.CBL`, `CBSTM03B.CBL` | `batch.job.StatementJobConfig` (+ `batch.processor.StatementProcessor`, composite `ItemStreamWriter` → two `batch.writer.FixedWidthS3ItemWriter`) | Text (80B) + HTML (100B) statements → S3 |
| `PRTCATBL.jcl` | (category-balance print) | `batch.job.BatchPipelineOrchestrator` | Category-balance 40B print + 50B signed-zoned backup; overall JCL-order sequencing + `COND` deciders |

### 7.3 Master-file reader verification jobs → reader beans

| JCL Job | COBOL Driver | Java Target | Notes |
|---|---|---|---|
| `READACCT.jcl` | `CBACT01C.cbl` | `batch.reader.AccountItemReader` | Account master read/verify step |
| `READCARD.jcl` | `CBACT02C.cbl` | `batch.reader.CardItemReader` | Card master read/verify step |
| `READXREF.jcl` | `CBACT03C.cbl` | `batch.reader.CardXrefItemReader` | Xref read/verify step |
| `READCUST.jcl` | `CBCUS01C.cbl` | `batch.reader.CustomerItemReader` | Customer master read/verify step |

### 7.4 GDG / report storage provisioning → LocalStack S3 + SQS

| JCL Job | Role | Java Target | Notes |
|---|---|---|---|
| `DEFGDGB.jcl` | Define GDG base | `localstack-init/init-aws.sh` | GDG generations → versioned S3 objects (3 buckets) |
| `REPTFILE.jcl` | Report dataset (GDG) | `localstack-init/init-aws.sh` | `carddemo-batch-output` bucket |
| `DALYREJS.jcl` | Daily rejects dataset (GDG) | `localstack-init/init-aws.sh` (+ `batch.writer.RejectTransactionWriter`) | Rejects object with reason trailers |

### 7.5 CICS region / lifecycle / backup JCL → Spring Boot lifecycle (REFERENCE)

| JCL Job | Role | Java Target | Notes |
|---|---|---|---|
| `CBADMCDJ.jcl` | CICS CSD admin | Spring Boot lifecycle (REFERENCE) | No direct target — bean/endpoint registration replaces CSD |
| `OPENFIL.jcl` | Open CICS files | Application startup (REFERENCE) | Datasource/bean init replaces explicit file open |
| `CLOSEFIL.jcl` | Close CICS files | Application shutdown (REFERENCE) | Graceful shutdown replaces explicit file close |
| `TRANBKP.jcl` | Transaction backup (`STEP10 COND=(4,LT)`) | `batch.job.BatchPipelineOrchestrator.PostingReturnCodeDecider` → `categoryBalanceBackupStep` | `STEP10 COND=(4,LT)` gate: posting RC &lt; 4 runs the gated category-balance backup step (50B signed-zoned unload to S3); RC ≥ 4 stops the pipeline |

### 7.6 Build / compile procedures → Maven (REFERENCE)

| Build JCL | Role | Java Target | Notes |
|---|---|---|---|
| `samples/jcl/BATCMP.jcl` | Batch COBOL compile/link | Maven build (`pom.xml`) (REFERENCE) | `mvn compile` replaces batch compile proc |
| `samples/jcl/BMSCMP.jcl` | BMS map compile | (no UI; REST-only) (REFERENCE) | BMS generation obsolete — DTOs replace maps |
| `samples/jcl/CICCMP.jcl` | CICS translate + compile/link | Maven build (`pom.xml`) (REFERENCE) | Spring Boot packaging replaces CICS translate |

## 8. Intentionally Unmapped Artifact (Documented Gap)

| Artifact | Size | Status | Justification |
|---|---|---|---|
| `app/cpy/UNUSED1Y.cpy` | 80 bytes (reserved) | **Intentionally unmapped** | Reserved/unused copybook carrying **no procedural behavior and no referenced data layout** in any of the 28 programs. There is nothing to translate, so it has **no Java target by design**. Recording it here keeps paragraph/artifact coverage **complete-with-justification** rather than silently omitted. |

This is the **only** artifact in the corpus without a Java mapping. Every COBOL **paragraph** (across all
28 programs) is mapped to a Java class+method; there are **no empty mappings** anywhere else in this
matrix.

## 9. Reverse Direction — Java → COBOL Origin (Bidirectional Index)

The forward tables (§3–§4) are the authoritative bidirectional record (each row names both endpoints).
This index summarizes the reverse lookup at the class level for quick navigation.

| Java Class | Originating COBOL Program(s) / Copybook(s) |
|---|---|
| `service.AuthService` / `controller.AuthController` | `COSGN00C.cbl` (+ `COSGN00` BMS, `COCOM01Y`, `CSUSR01Y`) |
| `service.MenuService` / `controller.MenuController` | `COMEN01C.cbl`, `COADM01C.cbl` (+ `COMEN02Y`, `COADM02Y`) |
| `service.AccountViewService` / `controller.AccountController` | `COACTVWC.cbl` (+ `COACTVW` BMS) |
| `service.AccountUpdateService` | `COACTUPC.cbl` (+ `COACTUP` BMS) |
| `service.CardListService` / `controller.CardController` | `COCRDLIC.cbl` (+ `COCRDLI` BMS) |
| `service.CardDetailService` | `COCRDSLC.cbl` (+ `COCRDSL` BMS) |
| `service.CardUpdateService` | `COCRDUPC.cbl` (+ `COCRDUP` BMS) |
| `service.transaction.TransactionListService` / `controller.TransactionController` | `COTRN00C.cbl` (+ `COTRN00` BMS) |
| `service.transaction.TransactionDetailService` | `COTRN01C.cbl` (+ `COTRN01` BMS) |
| `service.transaction.TransactionAddService` | `COTRN02C.cbl` (+ `COTRN02` BMS) |
| `service.billing.BillingService` / `controller.BillingController` | `COBIL00C.cbl` (+ `COBIL00` BMS) |
| `service.ReportService` / `service.ReportJobConsumer` / `controller.ReportController` | `CORPT00C.cbl` (+ `CORPT00` BMS) |
| `service.UserListService` / `UserAddService` / `UserUpdateService` / `UserDeleteService` / `controller.UserController` | `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` (+ `COUSR00`–`COUSR03` BMS) |
| `service.DateValidationService` | `CSUTLDTC.cbl` (+ `CSDAT01Y`, `CSUTLDWY`, `CSUTLDPY`) |
| `service.ValidationLookupService` | `CSLKPCDY` (+ `resources/validation/*.json`) |
| `service.FileStatusMapper` | FILE STATUS handling across all programs (`CBTRN02C.cbl` reference) |
| `batch.job.PostTransactionJobConfig` / `batch.processor.TransactionPostingProcessor` / `batch.writer.{PostingResultWriter,PostedTransactionWriter,RejectTransactionWriter}` | `CBTRN02C.cbl` (+ `POSTTRAN` JCL) |
| `batch.job.InterestCalculationJobConfig` / `batch.processor.InterestProcessor` | `CBACT04C.cbl` (+ `INTCALC` JCL) |
| `batch.job.CombineTransactionsJobConfig` / `batch.processor.TransactionCombineComparator` | `COMBTRAN` JCL |
| `batch.job.TransactionReportJobConfig` / `batch.processor.TransactionReportProcessor` | `CBTRN03C.cbl` (+ `TRANREPT` JCL, `CVTRA07Y`) |
| `batch.job.StatementJobConfig` / `batch.processor.StatementProcessor` / `batch.reader.FileServiceReader` / `batch.writer.FixedWidthS3ItemWriter` | `CBSTM03A.CBL`, `CBSTM03B.CBL` (+ `CREASTMT` JCL, `COSTM01`) |
| `batch.reader.{AccountItemReader,CardItemReader,CardXrefItemReader,CustomerItemReader,DailyTransactionItemReader}` | `CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBCUS01C.cbl`, `CBTRN01C.cbl` |
| `batch.job.BatchPipelineOrchestrator` | `PRTCATBL` JCL + overall pipeline sequencing (+ `TRANBKP STEP10 COND=(4,LT)` gate) |
| `repository.TransactionRepository` | `TRANSACT` (`CVTRA05Y`) @ `27d6c6f`: keyed READ → `findById` (`COTRN01C`); WRITE → `save` (`COTRN02C`); unfiltered STARTBR/READNEXT browse → `findAll(Pageable)` (`COTRN00C`); positioned STARTBR-at-`TRAN-ID` browse for a numeric `TRNIDINI` filter → `findByTranIdGreaterThanEqual(String, Pageable)` (`COTRN00C`, D-048); READPREV-to-end → `findTopByOrderByTranIdDesc` (`COTRN02C`); date window → `findByProcessingDateWindow` (`CBTRN03C`); by-card → `findByCardNumOrderByTranIdAsc` (`CBSTM03A`) |
| `entity.*` (11 entities) | `CVACT01Y`, `CVACT02Y`, `CVACT03Y`, `CVCUS01Y`/`CUSTREC`, `CVTRA05Y`, `CVTRA06Y`, `CVTRA01Y`, `CVTRA02Y`, `CVTRA03Y`, `CVTRA04Y`, `CSUSR01Y` |
| `exception.*` + `GlobalExceptionHandler` | FILE STATUS codes + ABEND routines across all programs (`CSMSG02Y`) |

## 10. Gate 8 Coverage Attestation

| Criterion | Status |
|---|---|
| 100% of COBOL paragraphs (all 28 programs) mapped to a Java class + method | ✅ 527/527 paragraphs mapped; every cited `(class, method)` verified to exist in `src/main/java` (Spring Data `JpaRepository` finders and `RepositoryItemReader`-inherited `open`/`read`/`close` counted as defensible inherited members) |
| No empty Java mappings except the documented reserved copybook | ✅ Only `UNUSED1Y.cpy` is unmapped (justified, §8) |
| Bidirectional (COBOL → Java forward tables + Java → COBOL reverse index) | ✅ §3–§4 forward; §9 reverse |
| 28 copybooks mapped | ✅ §5 (incl. justified `UNUSED1Y`) |
| 17 BMS mapsets + 17 symbolic maps mapped to DTO contracts | ✅ §6 |
| 29 application JCL + 3 build JCL mapped | ✅ §7 |
| COBOL referenced by SHA `27d6c6f` only; **no COBOL source copied** | ✅ §1 |
| Consistent with `DECISION_LOG.md` and cited by `docs/validation-gates.md#gate-8` | ✅ §1 |

**Conclusion:** the migration achieves **100% paragraph traceability — with every forward-table
`(class, method)` cell verified at the method level against the delivered `src/main/java` sources —
and a single, justified, intentionally-unmapped reserved copybook**, satisfying the Explainability
rule and **Gate 8**. Method-level verification was performed by parsing the Java sources and
confirming each cited method exists on its named class (treating Spring Data `JpaRepository`
finders and Spring Batch `RepositoryItemReader`-inherited `open`/`read`/`close` as inherited
members); the only intentional no-op is `CBACT04C`'s `1400-COMPUTE-FEES` stub (a COBOL
`* To be implemented` paragraph containing only `EXIT.`), recorded as a documented `—` mapping.

---

*Generated as the Gate 8 traceability deliverable for the CardDemo COBOL → Java (Spring Boot 3.5.11 /
Java 25) migration. COBOL baseline: SHA `27d6c6f` (`v1.0-15-g27d6c6f-68`). Java base package:
`com.carddemo`.*
