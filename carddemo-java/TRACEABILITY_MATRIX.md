# CardDemo COBOL → Java Traceability Matrix

> **Audit artifact — Explainability gate.** This document provides a **bidirectional, 100%-coverage mapping** of every COBOL paragraph/section across all **28 programs** of the legacy AWS CardDemo application to its idiomatic Java 25 + Spring Boot 3.x replacement. Together with `DECISION_LOG.md` (rationale) it satisfies the Explainability requirement of the migration blueprint (`docs/technical-specifications.md` §0.7, §0.8.6).

It is the evidence base for the **100% behavioral-parity** guarantee: each COBOL paragraph is traced to the **Java class** that reproduces its observable behavior — the authoritative, verified anchor of this matrix — together with a **representative method label** showing where within that class the behavior lives, and each technology substitution is documented in the **Notes** column. Because idiomatic Java intentionally **consolidates several COBOL paragraphs into a single method**, the *Java Method* column is a **logical/representative grouping label**, not in every row a literal one-to-one method identifier (see the *Java Method column* convention in §1). Coverage is therefore **100% at paragraph → class granularity**; behavioral parity itself is proven by the characterization/parity test suite, not by a per-paragraph method name.

## 1. Provenance & Conventions

- **Source baseline (not copied into this repository).** The COBOL members live in the original AWS CardDemo repository at commit SHA **`27d6c6f`**; per the Minimal Change Clause and the "COBOL sources not copied" preservation rule, only this SHA is referenced for traceability. Paragraph names below are extracted verbatim from those members.
- **Base package.** All Java types use the base package **`com.cardemo`** (confirmed by `pom.xml` `<groupId>` and `DECISION_LOG.md` D-006, which explicitly rejected `com.carddemo`). Fully-qualified names in §2 are rooted at `com.cardemo.*`; the per-program tables use the simple class name for readability (the §2 summary and each program heading give the package-qualified artifact).
- **Table columns.** `COBOL Program` (member with its original `.cbl`/`.CBL` extension) · `COBOL Paragraph` (verbatim paragraph/section label) · `Java Class` (the type that hosts the translated behavior — **authoritative and verified literal**) · `Java Method` (a **representative method label**, or `(end)` marker — see below) · `Notes` (behavior preserved and the specific technology substitution applied).
- **`Java Method` column — representative, not always literal.** Idiomatic Java intentionally **consolidates many COBOL paragraphs into a single method** (procedural `PERFORM`/`GO TO`/`EVALUATE` paragraph fall-through is restructured into a few cohesive methods plus private helpers — §0.7.4 of the blueprint). Consequently the **`Java Class` column is the authoritative anchor**: every class named below has been verified to exist at the stated `com.cardemo.*` path. The **`Java Method` column names the logical/representative method that hosts each paragraph's behavior**; it is **not guaranteed to be a distinct, identically-named Java method for every paragraph**, and several paragraphs in a program frequently map to the *same* consolidating method. For example: the 34 paragraphs of `COACTVWC` are realized by `AccountViewService.viewAccount(…)` with `validateAccountKey(…)`/`assembleDto(…)` helpers; the sign-on paragraphs of `COSGN00C` by `AuthenticationService.signOn(…)`; the add-transaction paragraphs of `COTRN02C` by `TransactionAddService.addTransaction(…)`/`validateDataFields(…)`; and the menu paragraphs of `COMEN01C` by `MainMenuService.getMenuOptions(…)`/`selectOption(…)`. The matrix therefore guarantees **100% paragraph → class coverage**; the method column documents the behavioral home and the *kind* of method that consolidates each paragraph, rather than asserting a literal one-to-one method identifier in every row.
- **`PERFORM … THRU` terminators.** COBOL `*-EXIT` paragraphs are empty fall-through targets of `PERFORM THRU` ranges. They carry no logic of their own, so they map to the **end of the parent method** (shown as `parentMethod() (end)`) rather than to a separate Java method — Java methods do not fall through. They are listed for 100% coverage completeness.
- **Verbatim source labels.** Original source spellings are preserved exactly, including the misspelled `WIRTE-JOBSUB-TDQ` paragraph in `CORPT00C.cbl` and the re-used numeric prefixes (`1110-`/`1120-` appear twice) in `CBTRN03C.cbl`.
- **Deduplication.** `COACTVWC.cbl` defines the label `0000-MAIN-EXIT` twice (a benign source artifact); it is represented once. This is the only intra-program duplicate across the 28 members (528 raw labels → **527 unique**).

## 2. Coverage Summary — 100% of all 28 programs

**Coverage claim:** every paragraph and section of all **28** COBOL programs at commit `27d6c6f` is mapped below — **527 unique paragraphs**, zero omissions. Counts are reproducible directly from the source members (Area-A column-8 detection in the `PROCEDURE DIVISION`, CRLF-tolerant).

| # | COBOL Program | Type | Primary Java Artifact(s) | Paragraphs |
|---|---|---|---|---|
| 1 | `COSGN00C.cbl` | Online | `com.cardemo.service.auth.AuthenticationService` · `com.cardemo.controller.AuthController` | 6 |
| 2 | `COMEN01C.cbl` | Online | `com.cardemo.service.menu.MainMenuService` · `com.cardemo.controller.MenuController` | 7 |
| 3 | `COADM01C.cbl` | Online | `com.cardemo.service.menu.AdminMenuService` · `com.cardemo.controller.MenuController` | 7 |
| 4 | `COACTVWC.cbl` | Online | `com.cardemo.service.account.AccountViewService` · `com.cardemo.controller.AccountController` | 34 |
| 5 | `COACTUPC.cbl` | Online | `com.cardemo.service.account.AccountUpdateService` · `com.cardemo.controller.AccountController` | 85 |
| 6 | `COCRDLIC.cbl` | Online | `com.cardemo.service.card.CardListService` · `com.cardemo.controller.CardController` | 39 |
| 7 | `COCRDSLC.cbl` | Online | `com.cardemo.service.card.CardDetailService` · `com.cardemo.controller.CardController` | 34 |
| 8 | `COCRDUPC.cbl` | Online | `com.cardemo.service.card.CardUpdateService` · `com.cardemo.controller.CardController` | 45 |
| 9 | `COTRN00C.cbl` | Online | `com.cardemo.service.transaction.TransactionListService` · `com.cardemo.controller.TransactionController` | 16 |
| 10 | `COTRN01C.cbl` | Online | `com.cardemo.service.transaction.TransactionDetailService` · `com.cardemo.controller.TransactionController` | 9 |
| 11 | `COTRN02C.cbl` | Online | `com.cardemo.service.transaction.TransactionAddService` · `com.cardemo.controller.TransactionController` | 18 |
| 12 | `COBIL00C.cbl` | Online | `com.cardemo.service.billing.BillPaymentService` · `com.cardemo.controller.BillingController` | 16 |
| 13 | `CORPT00C.cbl` | Online | `com.cardemo.service.report.ReportSubmissionService` · `com.cardemo.controller.ReportController` | 10 |
| 14 | `COUSR00C.cbl` | Online | `com.cardemo.service.admin.UserListService` · `com.cardemo.controller.UserAdminController` | 16 |
| 15 | `COUSR01C.cbl` | Online | `com.cardemo.service.admin.UserAddService` · `com.cardemo.controller.UserAdminController` | 9 |
| 16 | `COUSR02C.cbl` | Online | `com.cardemo.service.admin.UserUpdateService` · `com.cardemo.controller.UserAdminController` | 11 |
| 17 | `COUSR03C.cbl` | Online | `com.cardemo.service.admin.UserDeleteService` · `com.cardemo.controller.UserAdminController` | 11 |
| 18 | `CSUTLDTC.cbl` | Shared Utility | `com.cardemo.service.shared.DateValidationService` | 2 |
| 19 | `CBACT01C.cbl` | Batch | `com.cardemo.batch.readers.AccountFileReader` · `com.cardemo.service.shared.FileStatusMapper` | 6 |
| 20 | `CBACT02C.cbl` | Batch | `com.cardemo.batch.readers.CardFileReader` · `com.cardemo.service.shared.FileStatusMapper` | 5 |
| 21 | `CBACT03C.cbl` | Batch | `com.cardemo.batch.readers.CrossReferenceFileReader` · `com.cardemo.service.shared.FileStatusMapper` | 5 |
| 22 | `CBACT04C.cbl` | Batch | `com.cardemo.batch.jobs.InterestCalculationJob` · `com.cardemo.batch.processors.InterestCalculationProcessor` · `com.cardemo.service.shared.FileStatusMapper` | 22 |
| 23 | `CBCUS01C.cbl` | Batch | `com.cardemo.batch.readers.CustomerFileReader` · `com.cardemo.service.shared.FileStatusMapper` | 5 |
| 24 | `CBTRN01C.cbl` | Batch | `com.cardemo.batch.readers.DailyTransactionReader` · `com.cardemo.service.shared.FileStatusMapper` | 18 |
| 25 | `CBTRN02C.cbl` | Batch | `com.cardemo.batch.jobs.DailyTransactionPostingJob` · `com.cardemo.batch.processors.TransactionPostingProcessor` · `com.cardemo.batch.writers.TransactionWriter` · `com.cardemo.batch.writers.RejectWriter` · `com.cardemo.service.shared.FileStatusMapper` | 26 |
| 26 | `CBTRN03C.cbl` | Batch | `com.cardemo.batch.jobs.TransactionReportJob` · `com.cardemo.batch.processors.TransactionReportProcessor` · `com.cardemo.service.shared.FileStatusMapper` | 26 |
| 27 | `CBSTM03A.CBL` | Batch | `com.cardemo.batch.jobs.StatementGenerationJob` · `com.cardemo.batch.processors.StatementProcessor` · `com.cardemo.batch.writers.StatementWriter` | 25 |
| 28 | `CBSTM03B.CBL` | Batch | `com.cardemo.batch.processors.StatementProcessor` · `com.cardemo.batch.readers.CrossReferenceFileReader` | 14 |
| | **Online subtotal (17 programs)** | | | **373** |
| | **Shared utility subtotal (1 program)** | | | **2** |
| | **Batch subtotal (10 programs)** | | | **152** |
| | **GRAND TOTAL (28 programs)** | | | **527** |

## 3. Technology Substitution Legend

Recurring substitutions referenced in the **Notes** column (per the deterministic transformation contract, `docs/technical-specifications.md` §0.1.2):

| Legacy construct | Java / Spring replacement |
|---|---|
| BMS `SEND MAP` (3270 screen) | JSON response DTO built by the controller |
| BMS `RECEIVE MAP` | `@RequestBody` request DTO + Jakarta Validation |
| VSAM `READ` (keyed) | Spring Data JPA `findById()` / derived query |
| VSAM `READ` via AIX/PATH (e.g. `CXACAIX`) | Repository derived query on the alternate-index field |
| VSAM `WRITE` / `REWRITE` / `DELETE` | `JpaRepository.save()` / `delete()` |
| VSAM `STARTBR` / `READNEXT` / `READPREV` / `ENDBR` | Spring Data `Pageable` / `Slice` browse |
| CICS `RETURN TRANSID COMMAREA` (pseudo-conversational) | Stateless REST response + token/context state |
| CICS `SYNCPOINT ROLLBACK` | `@Transactional(rollbackFor = …)` |
| Before/after record-image compare | JPA `@Version` optimistic locking |
| CICS `WRITEQ TD` (TDQ `JOBS`) | AWS SQS publish to `carddemo-report-jobs.fifo` |
| GDG generations / sequential output | AWS S3 object keys |
| LE `CEEDAYS` date validation | `java.time.LocalDate` + custom validators |
| `FILE STATUS` code | `FileStatusMapper` → typed exception hierarchy |
| Plaintext `USRSEC` password | BCrypt hash (the single permitted behavioral change, C-003) |
| `COMP-3` / `COMP` / `PIC S9(n)V99` | `java.math.BigDecimal` (scale preserved; `HALF_EVEN` rounding) |
| `PERFORM … THRU` `*-EXIT` terminator | Marks the end of the parent Java method (no separate method) |

## 4. Program-by-Program Matrix

### 4.1 Online CICS Programs (17)

#### `COSGN00C.cbl` — Sign-On / authentication (tran CC00)

*Primary Java artifact(s):* `com.cardemo.service.auth.AuthenticationService` · `com.cardemo.controller.AuthController` · *Paragraphs:* 6

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COSGN00C.cbl` | `MAIN-PARA` | `AuthenticationService` | `handleRequest()` | Pseudo-conversational entry (tran CC00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COSGN00C.cbl` | `PROCESS-ENTER-KEY` | `AuthenticationService` | `authenticate()` | BCrypt verification replaces plaintext compare; USRSEC read via JPA |
| `COSGN00C.cbl` | `SEND-SIGNON-SCREEN` | `AuthController` | `POST /api/auth/signin response` | BMS SEND MAP (Sign-On) → JSON response DTO |
| `COSGN00C.cbl` | `SEND-PLAIN-TEXT` | `AuthController` | `errorResponse()` | CICS SEND TEXT → plain error response body |
| `COSGN00C.cbl` | `POPULATE-HEADER-INFO` | `AuthenticationService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COSGN00C.cbl` | `READ-USER-SEC-FILE` | `AuthenticationService` | `findUser()` | VSAM READ USRSEC → UserSecurityRepository.findById() |

#### `COMEN01C.cbl` — Main menu routing (tran CM00)

*Primary Java artifact(s):* `com.cardemo.service.menu.MainMenuService` · `com.cardemo.controller.MenuController` · *Paragraphs:* 7

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COMEN01C.cbl` | `MAIN-PARA` | `MainMenuService` | `handleRequest()` | Pseudo-conversational entry (tran CM00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COMEN01C.cbl` | `PROCESS-ENTER-KEY` | `MainMenuService` | `routeMenuOption()` | AID/option key → menu routing metadata (no terminal AID) |
| `COMEN01C.cbl` | `RETURN-TO-SIGNON-SCREEN` | `MainMenuService` | `buildResponse()` | XCTL to COSGN00C → response nav target = /api/auth/signin |
| `COMEN01C.cbl` | `SEND-MENU-SCREEN` | `MenuController` | `GET /api/menu/main response` | BMS SEND MAP (Main Menu) → JSON response DTO |
| `COMEN01C.cbl` | `RECEIVE-MENU-SCREEN` | `MenuController` | `GET /api/menu/main @RequestBody` | BMS RECEIVE MAP (Main Menu) → request DTO binding + Jakarta Validation |
| `COMEN01C.cbl` | `POPULATE-HEADER-INFO` | `MainMenuService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COMEN01C.cbl` | `BUILD-MENU-OPTIONS` | `MainMenuService` | `buildMenuOptions()` | COMEN02Y/COADM02Y option table → menu routing metadata |

#### `COADM01C.cbl` — Admin menu routing (tran CA00)

*Primary Java artifact(s):* `com.cardemo.service.menu.AdminMenuService` · `com.cardemo.controller.MenuController` · *Paragraphs:* 7

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COADM01C.cbl` | `MAIN-PARA` | `AdminMenuService` | `handleRequest()` | Pseudo-conversational entry (tran CA00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COADM01C.cbl` | `PROCESS-ENTER-KEY` | `AdminMenuService` | `routeMenuOption()` | AID/option key → admin menu routing metadata |
| `COADM01C.cbl` | `RETURN-TO-SIGNON-SCREEN` | `AdminMenuService` | `buildResponse()` | XCTL to COSGN00C → response nav target = /api/auth/signin |
| `COADM01C.cbl` | `SEND-MENU-SCREEN` | `MenuController` | `GET /api/menu/admin response` | BMS SEND MAP (Admin Menu) → JSON response DTO |
| `COADM01C.cbl` | `RECEIVE-MENU-SCREEN` | `MenuController` | `GET /api/menu/admin @RequestBody` | BMS RECEIVE MAP (Admin Menu) → request DTO binding + Jakarta Validation |
| `COADM01C.cbl` | `POPULATE-HEADER-INFO` | `AdminMenuService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COADM01C.cbl` | `BUILD-MENU-OPTIONS` | `AdminMenuService` | `buildMenuOptions()` | COMEN02Y/COADM02Y option table → menu routing metadata |

#### `COACTVWC.cbl` — Account view — ACCTDAT+CUSTDAT+CXACAIX read (tran CAVW)

*Primary Java artifact(s):* `com.cardemo.service.account.AccountViewService` · `com.cardemo.controller.AccountController` · *Paragraphs:* 34

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTVWC.cbl` | `0000-MAIN` | `AccountViewService` | `handleRequest()` | Pseudo-conversational entry (tran CAVW) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COACTVWC.cbl` | `COMMON-RETURN` | `AccountViewService` | `buildResponse()` | CICS RETURN TRANSID COMMAREA → REST response; pseudo-conversational state externalised to token |
| `COACTVWC.cbl` | `0000-MAIN-EXIT` | `AccountViewService` | `handleRequest() (end)` | PERFORM...THRU terminator → marks end of handleRequest(); no separate Java method |
| `COACTVWC.cbl` | `1000-SEND-MAP` | `AccountController` | `GET /api/accounts/{id} response` | BMS SEND MAP (Account View) → JSON response DTO |
| `COACTVWC.cbl` | `1000-SEND-MAP-EXIT` | `AccountController` | `GET /api/accounts/{id} response (end)` | PERFORM...THRU terminator → marks end of GET /api/accounts/{id} response; no separate Java method |
| `COACTVWC.cbl` | `1100-SCREEN-INIT` | `AccountViewService` | `initResponse()` | Map/array initialisation → response DTO initialisation |
| `COACTVWC.cbl` | `1100-SCREEN-INIT-EXIT` | `AccountViewService` | `initResponse() (end)` | PERFORM...THRU terminator → marks end of initResponse(); no separate Java method |
| `COACTVWC.cbl` | `1200-SETUP-SCREEN-VARS` | `AccountViewService` | `populateResponse()` | Map symbolic vars → response DTO field population |
| `COACTVWC.cbl` | `1200-SETUP-SCREEN-VARS-EXIT` | `AccountViewService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COACTVWC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `AccountViewService` | `applyFieldState()` | BMS field attributes (colour/protect) → response field-state flags |
| `COACTVWC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `AccountViewService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COACTVWC.cbl` | `1400-SEND-SCREEN` | `AccountController` | `GET /api/accounts/{id} response` | BMS SEND MAP (Account View) → JSON response DTO |
| `COACTVWC.cbl` | `1400-SEND-SCREEN-EXIT` | `AccountController` | `GET /api/accounts/{id} response (end)` | PERFORM...THRU terminator → marks end of GET /api/accounts/{id} response; no separate Java method |
| `COACTVWC.cbl` | `2000-PROCESS-INPUTS` | `AccountViewService` | `processInputs()` | Input orchestration paragraph → service input handling |
| `COACTVWC.cbl` | `2000-PROCESS-INPUTS-EXIT` | `AccountViewService` | `processInputs() (end)` | PERFORM...THRU terminator → marks end of processInputs(); no separate Java method |
| `COACTVWC.cbl` | `2100-RECEIVE-MAP` | `AccountController` | `GET /api/accounts/{id} @RequestBody` | BMS RECEIVE MAP (Account View) → request DTO binding + Jakarta Validation |
| `COACTVWC.cbl` | `2100-RECEIVE-MAP-EXIT` | `AccountController` | `GET /api/accounts/{id} @RequestBody (end)` | PERFORM...THRU terminator → marks end of GET /api/accounts/{id} @RequestBody; no separate Java method |
| `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS` | `AccountViewService` | `validateInputs()` | Field-edit cascade entry → Jakarta Validation + custom validators |
| `COACTVWC.cbl` | `2200-EDIT-MAP-INPUTS-EXIT` | `AccountViewService` | `validateInputs() (end)` | PERFORM...THRU terminator → marks end of validateInputs(); no separate Java method |
| `COACTVWC.cbl` | `2210-EDIT-ACCOUNT` | `AccountViewService` | `validateAccountId()` | Account-id edit → @Pattern/@Size validation |
| `COACTVWC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `AccountViewService` | `validateAccountId() (end)` | PERFORM...THRU terminator → marks end of validateAccountId(); no separate Java method |
| `COACTVWC.cbl` | `9000-READ-ACCT` | `AccountViewService` | `readAccount()` | VSAM READ ACCTDAT → AccountRepository.findById() orchestration |
| `COACTVWC.cbl` | `9000-READ-ACCT-EXIT` | `AccountViewService` | `readAccount() (end)` | PERFORM...THRU terminator → marks end of readAccount(); no separate Java method |
| `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT` | `AccountViewService` | `findXrefByAccount()` | VSAM READ CXACAIX by account → CardCrossReferenceRepository derived query |
| `COACTVWC.cbl` | `9200-GETCARDXREF-BYACCT-EXIT` | `AccountViewService` | `findXrefByAccount() (end)` | PERFORM...THRU terminator → marks end of findXrefByAccount(); no separate Java method |
| `COACTVWC.cbl` | `9300-GETACCTDATA-BYACCT` | `AccountViewService` | `findAccountById()` | VSAM READ ACCTDAT → AccountRepository.findById() |
| `COACTVWC.cbl` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountViewService` | `findAccountById() (end)` | PERFORM...THRU terminator → marks end of findAccountById(); no separate Java method |
| `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST` | `AccountViewService` | `findCustomerById()` | VSAM READ CUSTDAT → CustomerRepository.findById() |
| `COACTVWC.cbl` | `9400-GETCUSTDATA-BYCUST-EXIT` | `AccountViewService` | `findCustomerById() (end)` | PERFORM...THRU terminator → marks end of findCustomerById(); no separate Java method |
| `COACTVWC.cbl` | `SEND-PLAIN-TEXT` | `AccountController` | `errorResponse()` | CICS SEND TEXT → plain error response body |
| `COACTVWC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `AccountController` | `errorResponse() (end)` | PERFORM...THRU terminator → marks end of errorResponse(); no separate Java method |
| `COACTVWC.cbl` | `SEND-LONG-TEXT` | `AccountController` | `errorResponse()` | CICS SEND TEXT (long) → error response body |
| `COACTVWC.cbl` | `SEND-LONG-TEXT-EXIT` | `AccountController` | `errorResponse() (end)` | PERFORM...THRU terminator → marks end of errorResponse(); no separate Java method |
| `COACTVWC.cbl` | `ABEND-ROUTINE` | `AccountViewService` | `handleAbend()` | CICS HANDLE ABEND → @ExceptionHandler / unchecked exception |

#### `COACTUPC.cbl` — Account update — dual-file update, SYNCPOINT ROLLBACK + optimistic lock (tran CAUP)

*Primary Java artifact(s):* `com.cardemo.service.account.AccountUpdateService` · `com.cardemo.controller.AccountController` · *Paragraphs:* 85

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTUPC.cbl` | `0000-MAIN` | `AccountUpdateService` | `handleRequest()` | Pseudo-conversational entry (tran CAUP) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COACTUPC.cbl` | `COMMON-RETURN` | `AccountUpdateService` | `buildResponse()` | CICS RETURN TRANSID COMMAREA → REST response; pseudo-conversational state externalised to token |
| `COACTUPC.cbl` | `0000-MAIN-EXIT` | `AccountUpdateService` | `handleRequest() (end)` | PERFORM...THRU terminator → marks end of handleRequest(); no separate Java method |
| `COACTUPC.cbl` | `1000-PROCESS-INPUTS` | `AccountUpdateService` | `processInputs()` | Input orchestration paragraph → service input handling |
| `COACTUPC.cbl` | `1000-PROCESS-INPUTS-EXIT` | `AccountUpdateService` | `processInputs() (end)` | PERFORM...THRU terminator → marks end of processInputs(); no separate Java method |
| `COACTUPC.cbl` | `1100-RECEIVE-MAP` | `AccountController` | `PUT /api/accounts/{id} @RequestBody` | BMS RECEIVE MAP (Account Update) → request DTO binding + Jakarta Validation |
| `COACTUPC.cbl` | `1100-RECEIVE-MAP-EXIT` | `AccountController` | `PUT /api/accounts/{id} @RequestBody (end)` | PERFORM...THRU terminator → marks end of PUT /api/accounts/{id} @RequestBody; no separate Java method |
| `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS` | `AccountUpdateService` | `validateInputs()` | Field-edit cascade entry → Jakarta Validation + custom validators |
| `COACTUPC.cbl` | `1200-EDIT-MAP-INPUTS-EXIT` | `AccountUpdateService` | `validateInputs() (end)` | PERFORM...THRU terminator → marks end of validateInputs(); no separate Java method |
| `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW` | `AccountUpdateService` | `compareOldAndNew()` | Old/new field compare → change detection feeding @Version check |
| `COACTUPC.cbl` | `1205-COMPARE-OLD-NEW-EXIT` | `AccountUpdateService` | `compareOldAndNew() (end)` | PERFORM...THRU terminator → marks end of compareOldAndNew(); no separate Java method |
| `COACTUPC.cbl` | `1210-EDIT-ACCOUNT` | `AccountUpdateService` | `validateAccountId()` | Account-id edit → @Pattern/@Size validation |
| `COACTUPC.cbl` | `1210-EDIT-ACCOUNT-EXIT` | `AccountUpdateService` | `validateAccountId() (end)` | PERFORM...THRU terminator → marks end of validateAccountId(); no separate Java method |
| `COACTUPC.cbl` | `1215-EDIT-MANDATORY` | `AccountUpdateService` | `validateMandatory()` | Mandatory-field edit → @NotBlank validation |
| `COACTUPC.cbl` | `1215-EDIT-MANDATORY-EXIT` | `AccountUpdateService` | `validateMandatory() (end)` | PERFORM...THRU terminator → marks end of validateMandatory(); no separate Java method |
| `COACTUPC.cbl` | `1220-EDIT-YESNO` | `AccountUpdateService` | `validateYesNo()` | Y/N edit → enum/@Pattern validation |
| `COACTUPC.cbl` | `1220-EDIT-YESNO-EXIT` | `AccountUpdateService` | `validateYesNo() (end)` | PERFORM...THRU terminator → marks end of validateYesNo(); no separate Java method |
| `COACTUPC.cbl` | `1225-EDIT-ALPHA-REQD` | `AccountUpdateService` | `validateAlphaRequired()` | Required alpha edit → @Pattern(alpha) validation |
| `COACTUPC.cbl` | `1225-EDIT-ALPHA-REQD-EXIT` | `AccountUpdateService` | `validateAlphaRequired() (end)` | PERFORM...THRU terminator → marks end of validateAlphaRequired(); no separate Java method |
| `COACTUPC.cbl` | `1230-EDIT-ALPHANUM-REQD` | `AccountUpdateService` | `validateAlphanumRequired()` | Required alphanumeric edit → @Pattern validation |
| `COACTUPC.cbl` | `1230-EDIT-ALPHANUM-REQD-EXIT` | `AccountUpdateService` | `validateAlphanumRequired() (end)` | PERFORM...THRU terminator → marks end of validateAlphanumRequired(); no separate Java method |
| `COACTUPC.cbl` | `1235-EDIT-ALPHA-OPT` | `AccountUpdateService` | `validateAlphaOptional()` | Optional alpha edit → conditional @Pattern validation |
| `COACTUPC.cbl` | `1235-EDIT-ALPHA-OPT-EXIT` | `AccountUpdateService` | `validateAlphaOptional() (end)` | PERFORM...THRU terminator → marks end of validateAlphaOptional(); no separate Java method |
| `COACTUPC.cbl` | `1240-EDIT-ALPHANUM-OPT` | `AccountUpdateService` | `validateAlphanumOptional()` | Optional alphanumeric edit → conditional @Pattern validation |
| `COACTUPC.cbl` | `1240-EDIT-ALPHANUM-OPT-EXIT` | `AccountUpdateService` | `validateAlphanumOptional() (end)` | PERFORM...THRU terminator → marks end of validateAlphanumOptional(); no separate Java method |
| `COACTUPC.cbl` | `1245-EDIT-NUM-REQD` | `AccountUpdateService` | `validateNumericRequired()` | Required numeric edit → @Digits validation |
| `COACTUPC.cbl` | `1245-EDIT-NUM-REQD-EXIT` | `AccountUpdateService` | `validateNumericRequired() (end)` | PERFORM...THRU terminator → marks end of validateNumericRequired(); no separate Java method |
| `COACTUPC.cbl` | `1250-EDIT-SIGNED-9V2` | `AccountUpdateService` | `validateSignedDecimal()` | Signed S9(n)V99 edit → BigDecimal @Digits(fraction=2) validation |
| `COACTUPC.cbl` | `1250-EDIT-SIGNED-9V2-EXIT` | `AccountUpdateService` | `validateSignedDecimal() (end)` | PERFORM...THRU terminator → marks end of validateSignedDecimal(); no separate Java method |
| `COACTUPC.cbl` | `1260-EDIT-US-PHONE-NUM` | `AccountUpdateService` | `validateUsPhone()` | US phone edit → NANPA lookup via ValidationLookupService |
| `COACTUPC.cbl` | `EDIT-AREA-CODE` | `AccountUpdateService` | `validateAreaCode()` | NANPA area-code edit → ValidationLookupService lookup |
| `COACTUPC.cbl` | `EDIT-US-PHONE-PREFIX` | `AccountUpdateService` | `validatePhonePrefix()` | Phone prefix edit → numeric/range validation |
| `COACTUPC.cbl` | `EDIT-US-PHONE-LINENUM` | `AccountUpdateService` | `validatePhoneLineNumber()` | Phone line-number edit → numeric validation |
| `COACTUPC.cbl` | `EDIT-US-PHONE-EXIT` | `AccountUpdateService` | `validateUsPhone() (end)` | PERFORM...THRU terminator → marks end of validateUsPhone(); no separate Java method |
| `COACTUPC.cbl` | `1260-EDIT-US-PHONE-NUM-EXIT` | `AccountUpdateService` | `validateUsPhone() (end)` | PERFORM...THRU terminator → marks end of validateUsPhone(); no separate Java method |
| `COACTUPC.cbl` | `1265-EDIT-US-SSN` | `AccountUpdateService` | `validateSsn()` | SSN edit → @Pattern + area-range validation |
| `COACTUPC.cbl` | `1265-EDIT-US-SSN-EXIT` | `AccountUpdateService` | `validateSsn() (end)` | PERFORM...THRU terminator → marks end of validateSsn(); no separate Java method |
| `COACTUPC.cbl` | `1270-EDIT-US-STATE-CD` | `AccountUpdateService` | `validateStateCode()` | State-code edit → us-state-codes lookup via ValidationLookupService |
| `COACTUPC.cbl` | `1270-EDIT-US-STATE-CD-EXIT` | `AccountUpdateService` | `validateStateCode() (end)` | PERFORM...THRU terminator → marks end of validateStateCode(); no separate Java method |
| `COACTUPC.cbl` | `1275-EDIT-FICO-SCORE` | `AccountUpdateService` | `validateFicoScore()` | FICO edit → @Min(300)/@Max(850) range validation |
| `COACTUPC.cbl` | `1275-EDIT-FICO-SCORE-EXIT` | `AccountUpdateService` | `validateFicoScore() (end)` | PERFORM...THRU terminator → marks end of validateFicoScore(); no separate Java method |
| `COACTUPC.cbl` | `1280-EDIT-US-STATE-ZIP-CD` | `AccountUpdateService` | `validateStateZip()` | State+ZIP edit → state-zip-prefix lookup via ValidationLookupService |
| `COACTUPC.cbl` | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `AccountUpdateService` | `validateStateZip() (end)` | PERFORM...THRU terminator → marks end of validateStateZip(); no separate Java method |
| `COACTUPC.cbl` | `2000-DECIDE-ACTION` | `AccountUpdateService` | `decideAction()` | EVALUATE action selector → switch on request action |
| `COACTUPC.cbl` | `2000-DECIDE-ACTION-EXIT` | `AccountUpdateService` | `decideAction() (end)` | PERFORM...THRU terminator → marks end of decideAction(); no separate Java method |
| `COACTUPC.cbl` | `3000-SEND-MAP` | `AccountController` | `PUT /api/accounts/{id} response` | BMS SEND MAP (Account Update) → JSON response DTO |
| `COACTUPC.cbl` | `3000-SEND-MAP-EXIT` | `AccountController` | `PUT /api/accounts/{id} response (end)` | PERFORM...THRU terminator → marks end of PUT /api/accounts/{id} response; no separate Java method |
| `COACTUPC.cbl` | `3100-SCREEN-INIT` | `AccountUpdateService` | `initResponse()` | Map/array initialisation → response DTO initialisation |
| `COACTUPC.cbl` | `3100-SCREEN-INIT-EXIT` | `AccountUpdateService` | `initResponse() (end)` | PERFORM...THRU terminator → marks end of initResponse(); no separate Java method |
| `COACTUPC.cbl` | `3200-SETUP-SCREEN-VARS` | `AccountUpdateService` | `populateResponse()` | Map symbolic vars → response DTO field population |
| `COACTUPC.cbl` | `3200-SETUP-SCREEN-VARS-EXIT` | `AccountUpdateService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COACTUPC.cbl` | `3201-SHOW-INITIAL-VALUES` | `AccountUpdateService` | `populateResponse()` | Show initial values → response DTO (initial state) |
| `COACTUPC.cbl` | `3201-SHOW-INITIAL-VALUES-EXIT` | `AccountUpdateService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COACTUPC.cbl` | `3202-SHOW-ORIGINAL-VALUES` | `AccountUpdateService` | `populateResponse()` | Show original values → response DTO (pre-edit snapshot) |
| `COACTUPC.cbl` | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `AccountUpdateService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COACTUPC.cbl` | `3203-SHOW-UPDATED-VALUES` | `AccountUpdateService` | `populateResponse()` | Show updated values → response DTO (post-edit state) |
| `COACTUPC.cbl` | `3203-SHOW-UPDATED-VALUES-EXIT` | `AccountUpdateService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COACTUPC.cbl` | `3250-SETUP-INFOMSG` | `AccountUpdateService` | `buildInfoMessage()` | CSMSG01Y info text → response message field |
| `COACTUPC.cbl` | `3250-SETUP-INFOMSG-EXIT` | `AccountUpdateService` | `buildInfoMessage() (end)` | PERFORM...THRU terminator → marks end of buildInfoMessage(); no separate Java method |
| `COACTUPC.cbl` | `3300-SETUP-SCREEN-ATTRS` | `AccountUpdateService` | `applyFieldState()` | BMS field attributes (colour/protect) → response field-state flags |
| `COACTUPC.cbl` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `AccountUpdateService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COACTUPC.cbl` | `3310-PROTECT-ALL-ATTRS` | `AccountUpdateService` | `applyFieldState()` | BMS protect-all attributes → read-only field-state flags |
| `COACTUPC.cbl` | `3310-PROTECT-ALL-ATTRS-EXIT` | `AccountUpdateService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COACTUPC.cbl` | `3320-UNPROTECT-FEW-ATTRS` | `AccountUpdateService` | `applyFieldState()` | BMS selective unprotect → editable field-state flags |
| `COACTUPC.cbl` | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `AccountUpdateService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COACTUPC.cbl` | `3390-SETUP-INFOMSG-ATTRS` | `AccountUpdateService` | `applyMessageState()` | Info-message attribute setup → message field state |
| `COACTUPC.cbl` | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `AccountUpdateService` | `applyMessageState() (end)` | PERFORM...THRU terminator → marks end of applyMessageState(); no separate Java method |
| `COACTUPC.cbl` | `3400-SEND-SCREEN` | `AccountController` | `PUT /api/accounts/{id} response` | BMS SEND MAP (Account Update) → JSON response DTO |
| `COACTUPC.cbl` | `3400-SEND-SCREEN-EXIT` | `AccountController` | `PUT /api/accounts/{id} response (end)` | PERFORM...THRU terminator → marks end of PUT /api/accounts/{id} response; no separate Java method |
| `COACTUPC.cbl` | `9000-READ-ACCT` | `AccountUpdateService` | `readAccount()` | VSAM READ ACCTDAT → AccountRepository.findById() orchestration |
| `COACTUPC.cbl` | `9000-READ-ACCT-EXIT` | `AccountUpdateService` | `readAccount() (end)` | PERFORM...THRU terminator → marks end of readAccount(); no separate Java method |
| `COACTUPC.cbl` | `9200-GETCARDXREF-BYACCT` | `AccountUpdateService` | `findXrefByAccount()` | VSAM READ CXACAIX by account → CardCrossReferenceRepository derived query |
| `COACTUPC.cbl` | `9200-GETCARDXREF-BYACCT-EXIT` | `AccountUpdateService` | `findXrefByAccount() (end)` | PERFORM...THRU terminator → marks end of findXrefByAccount(); no separate Java method |
| `COACTUPC.cbl` | `9300-GETACCTDATA-BYACCT` | `AccountUpdateService` | `findAccountById()` | VSAM READ ACCTDAT → AccountRepository.findById() |
| `COACTUPC.cbl` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountUpdateService` | `findAccountById() (end)` | PERFORM...THRU terminator → marks end of findAccountById(); no separate Java method |
| `COACTUPC.cbl` | `9400-GETCUSTDATA-BYCUST` | `AccountUpdateService` | `findCustomerById()` | VSAM READ CUSTDAT → CustomerRepository.findById() |
| `COACTUPC.cbl` | `9400-GETCUSTDATA-BYCUST-EXIT` | `AccountUpdateService` | `findCustomerById() (end)` | PERFORM...THRU terminator → marks end of findCustomerById(); no separate Java method |
| `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA` | `AccountUpdateService` | `snapshotForUpdate()` | Cache read image in COMMAREA → JPA-managed entity snapshot |
| `COACTUPC.cbl` | `9500-STORE-FETCHED-DATA-EXIT` | `AccountUpdateService` | `snapshotForUpdate() (end)` | PERFORM...THRU terminator → marks end of snapshotForUpdate(); no separate Java method |
| `COACTUPC.cbl` | `9600-WRITE-PROCESSING` | `AccountUpdateService` | `persistChanges()` | Dual ACCTDAT+CUSTDAT REWRITE → repository.save() within @Transactional(rollbackFor=...) (sole SYNCPOINT ROLLBACK site) + @Version optimistic-lock check |
| `COACTUPC.cbl` | `9600-WRITE-PROCESSING-EXIT` | `AccountUpdateService` | `persistChanges() (end)` | PERFORM...THRU terminator → marks end of persistChanges(); no separate Java method |
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC` | `AccountUpdateService` | `verifyNoConcurrentChange()` | Before/after record-image compare → JPA @Version optimistic lock |
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC-EXIT` | `AccountUpdateService` | `verifyNoConcurrentChange() (end)` | PERFORM...THRU terminator → marks end of verifyNoConcurrentChange(); no separate Java method |
| `COACTUPC.cbl` | `ABEND-ROUTINE` | `AccountUpdateService` | `handleAbend()` | CICS HANDLE ABEND → @ExceptionHandler / unchecked exception |
| `COACTUPC.cbl` | `ABEND-ROUTINE-EXIT` | `AccountUpdateService` | `handleAbend() (end)` | PERFORM...THRU terminator → marks end of handleAbend(); no separate Java method |

#### `COCRDLIC.cbl` — Card list — paginated browse, 7 rows/page (tran CCLI)

*Primary Java artifact(s):* `com.cardemo.service.card.CardListService` · `com.cardemo.controller.CardController` · *Paragraphs:* 39

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDLIC.cbl` | `0000-MAIN` | `CardListService` | `handleRequest()` | Pseudo-conversational entry (tran CCLI) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COCRDLIC.cbl` | `COMMON-RETURN` | `CardListService` | `buildResponse()` | CICS RETURN TRANSID COMMAREA → REST response; pseudo-conversational state externalised to token |
| `COCRDLIC.cbl` | `0000-MAIN-EXIT` | `CardListService` | `handleRequest() (end)` | PERFORM...THRU terminator → marks end of handleRequest(); no separate Java method |
| `COCRDLIC.cbl` | `1000-SEND-MAP` | `CardController` | `GET /api/cards response` | BMS SEND MAP (Card List) → JSON response DTO |
| `COCRDLIC.cbl` | `1000-SEND-MAP-EXIT` | `CardController` | `GET /api/cards response (end)` | PERFORM...THRU terminator → marks end of GET /api/cards response; no separate Java method |
| `COCRDLIC.cbl` | `1100-SCREEN-INIT` | `CardListService` | `initResponse()` | Map/array initialisation → response DTO initialisation |
| `COCRDLIC.cbl` | `1100-SCREEN-INIT-EXIT` | `CardListService` | `initResponse() (end)` | PERFORM...THRU terminator → marks end of initResponse(); no separate Java method |
| `COCRDLIC.cbl` | `1200-SCREEN-ARRAY-INIT` | `CardListService` | `initResponse()` | Map/array initialisation → response DTO initialisation |
| `COCRDLIC.cbl` | `1200-SCREEN-ARRAY-INIT-EXIT` | `CardListService` | `initResponse() (end)` | PERFORM...THRU terminator → marks end of initResponse(); no separate Java method |
| `COCRDLIC.cbl` | `1250-SETUP-ARRAY-ATTRIBS` | `CardListService` | `initRowState()` | BMS array attribute setup → per-row response field state |
| `COCRDLIC.cbl` | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `CardListService` | `initRowState() (end)` | PERFORM...THRU terminator → marks end of initRowState(); no separate Java method |
| `COCRDLIC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `CardListService` | `applyFieldState()` | BMS field attributes (colour/protect) → response field-state flags |
| `COCRDLIC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardListService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COCRDLIC.cbl` | `1400-SETUP-MESSAGE` | `CardListService` | `buildInfoMessage()` | Message setup → response message field |
| `COCRDLIC.cbl` | `1400-SETUP-MESSAGE-EXIT` | `CardListService` | `buildInfoMessage() (end)` | PERFORM...THRU terminator → marks end of buildInfoMessage(); no separate Java method |
| `COCRDLIC.cbl` | `1500-SEND-SCREEN` | `CardController` | `GET /api/cards response` | BMS SEND MAP (Card List) → JSON response DTO |
| `COCRDLIC.cbl` | `1500-SEND-SCREEN-EXIT` | `CardController` | `GET /api/cards response (end)` | PERFORM...THRU terminator → marks end of GET /api/cards response; no separate Java method |
| `COCRDLIC.cbl` | `2000-RECEIVE-MAP` | `CardController` | `GET /api/cards @RequestBody` | BMS RECEIVE MAP (Card List) → request DTO binding + Jakarta Validation |
| `COCRDLIC.cbl` | `2000-RECEIVE-MAP-EXIT` | `CardController` | `GET /api/cards @RequestBody (end)` | PERFORM...THRU terminator → marks end of GET /api/cards @RequestBody; no separate Java method |
| `COCRDLIC.cbl` | `2100-RECEIVE-SCREEN` | `CardController` | `GET /api/cards @RequestBody` | BMS RECEIVE MAP (Card List) → request DTO binding + Jakarta Validation |
| `COCRDLIC.cbl` | `2100-RECEIVE-SCREEN-EXIT` | `CardController` | `GET /api/cards @RequestBody (end)` | PERFORM...THRU terminator → marks end of GET /api/cards @RequestBody; no separate Java method |
| `COCRDLIC.cbl` | `2200-EDIT-INPUTS` | `CardListService` | `validateInputs()` | Edit-inputs entry → Jakarta Validation cascade |
| `COCRDLIC.cbl` | `2200-EDIT-INPUTS-EXIT` | `CardListService` | `validateInputs() (end)` | PERFORM...THRU terminator → marks end of validateInputs(); no separate Java method |
| `COCRDLIC.cbl` | `2210-EDIT-ACCOUNT` | `CardListService` | `validateAccountId()` | Account-id edit → @Pattern/@Size validation |
| `COCRDLIC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `CardListService` | `validateAccountId() (end)` | PERFORM...THRU terminator → marks end of validateAccountId(); no separate Java method |
| `COCRDLIC.cbl` | `2220-EDIT-CARD` | `CardListService` | `validateCardNumber()` | Card-number edit → Luhn/@Pattern validation |
| `COCRDLIC.cbl` | `2220-EDIT-CARD-EXIT` | `CardListService` | `validateCardNumber() (end)` | PERFORM...THRU terminator → marks end of validateCardNumber(); no separate Java method |
| `COCRDLIC.cbl` | `2250-EDIT-ARRAY` | `CardListService` | `validateRows()` | Array edit → per-row request DTO validation |
| `COCRDLIC.cbl` | `2250-EDIT-ARRAY-EXIT` | `CardListService` | `validateRows() (end)` | PERFORM...THRU terminator → marks end of validateRows(); no separate Java method |
| `COCRDLIC.cbl` | `9000-READ-FORWARD` | `CardListService` | `readForward()` | VSAM STARTBR+READNEXT → forward page query (Pageable) |
| `COCRDLIC.cbl` | `9000-READ-FORWARD-EXIT` | `CardListService` | `readForward() (end)` | PERFORM...THRU terminator → marks end of readForward(); no separate Java method |
| `COCRDLIC.cbl` | `9100-READ-BACKWARDS` | `CardListService` | `readBackward()` | VSAM READPREV → backward page query (Pageable) |
| `COCRDLIC.cbl` | `9100-READ-BACKWARDS-EXIT` | `CardListService` | `readBackward() (end)` | PERFORM...THRU terminator → marks end of readBackward(); no separate Java method |
| `COCRDLIC.cbl` | `9500-FILTER-RECORDS` | `CardListService` | `filterRecords()` | In-loop record filter → repository predicate / Specification |
| `COCRDLIC.cbl` | `9500-FILTER-RECORDS-EXIT` | `CardListService` | `filterRecords() (end)` | PERFORM...THRU terminator → marks end of filterRecords(); no separate Java method |
| `COCRDLIC.cbl` | `SEND-PLAIN-TEXT` | `CardController` | `errorResponse()` | CICS SEND TEXT → plain error response body |
| `COCRDLIC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `CardController` | `errorResponse() (end)` | PERFORM...THRU terminator → marks end of errorResponse(); no separate Java method |
| `COCRDLIC.cbl` | `SEND-LONG-TEXT` | `CardController` | `errorResponse()` | CICS SEND TEXT (long) → error response body |
| `COCRDLIC.cbl` | `SEND-LONG-TEXT-EXIT` | `CardController` | `errorResponse() (end)` | PERFORM...THRU terminator → marks end of errorResponse(); no separate Java method |

#### `COCRDSLC.cbl` — Card detail — single keyed read (tran CCDL)

*Primary Java artifact(s):* `com.cardemo.service.card.CardDetailService` · `com.cardemo.controller.CardController` · *Paragraphs:* 34

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDSLC.cbl` | `0000-MAIN` | `CardDetailService` | `handleRequest()` | Pseudo-conversational entry (tran CCDL) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COCRDSLC.cbl` | `COMMON-RETURN` | `CardDetailService` | `buildResponse()` | CICS RETURN TRANSID COMMAREA → REST response; pseudo-conversational state externalised to token |
| `COCRDSLC.cbl` | `0000-MAIN-EXIT` | `CardDetailService` | `handleRequest() (end)` | PERFORM...THRU terminator → marks end of handleRequest(); no separate Java method |
| `COCRDSLC.cbl` | `1000-SEND-MAP` | `CardController` | `GET /api/cards/{id} response` | BMS SEND MAP (Card Detail) → JSON response DTO |
| `COCRDSLC.cbl` | `1000-SEND-MAP-EXIT` | `CardController` | `GET /api/cards/{id} response (end)` | PERFORM...THRU terminator → marks end of GET /api/cards/{id} response; no separate Java method |
| `COCRDSLC.cbl` | `1100-SCREEN-INIT` | `CardDetailService` | `initResponse()` | Map/array initialisation → response DTO initialisation |
| `COCRDSLC.cbl` | `1100-SCREEN-INIT-EXIT` | `CardDetailService` | `initResponse() (end)` | PERFORM...THRU terminator → marks end of initResponse(); no separate Java method |
| `COCRDSLC.cbl` | `1200-SETUP-SCREEN-VARS` | `CardDetailService` | `populateResponse()` | Map symbolic vars → response DTO field population |
| `COCRDSLC.cbl` | `1200-SETUP-SCREEN-VARS-EXIT` | `CardDetailService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COCRDSLC.cbl` | `1300-SETUP-SCREEN-ATTRS` | `CardDetailService` | `applyFieldState()` | BMS field attributes (colour/protect) → response field-state flags |
| `COCRDSLC.cbl` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardDetailService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COCRDSLC.cbl` | `1400-SEND-SCREEN` | `CardController` | `GET /api/cards/{id} response` | BMS SEND MAP (Card Detail) → JSON response DTO |
| `COCRDSLC.cbl` | `1400-SEND-SCREEN-EXIT` | `CardController` | `GET /api/cards/{id} response (end)` | PERFORM...THRU terminator → marks end of GET /api/cards/{id} response; no separate Java method |
| `COCRDSLC.cbl` | `2000-PROCESS-INPUTS` | `CardDetailService` | `processInputs()` | Input orchestration paragraph → service input handling |
| `COCRDSLC.cbl` | `2000-PROCESS-INPUTS-EXIT` | `CardDetailService` | `processInputs() (end)` | PERFORM...THRU terminator → marks end of processInputs(); no separate Java method |
| `COCRDSLC.cbl` | `2100-RECEIVE-MAP` | `CardController` | `GET /api/cards/{id} @RequestBody` | BMS RECEIVE MAP (Card Detail) → request DTO binding + Jakarta Validation |
| `COCRDSLC.cbl` | `2100-RECEIVE-MAP-EXIT` | `CardController` | `GET /api/cards/{id} @RequestBody (end)` | PERFORM...THRU terminator → marks end of GET /api/cards/{id} @RequestBody; no separate Java method |
| `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS` | `CardDetailService` | `validateInputs()` | Field-edit cascade entry → Jakarta Validation + custom validators |
| `COCRDSLC.cbl` | `2200-EDIT-MAP-INPUTS-EXIT` | `CardDetailService` | `validateInputs() (end)` | PERFORM...THRU terminator → marks end of validateInputs(); no separate Java method |
| `COCRDSLC.cbl` | `2210-EDIT-ACCOUNT` | `CardDetailService` | `validateAccountId()` | Account-id edit → @Pattern/@Size validation |
| `COCRDSLC.cbl` | `2210-EDIT-ACCOUNT-EXIT` | `CardDetailService` | `validateAccountId() (end)` | PERFORM...THRU terminator → marks end of validateAccountId(); no separate Java method |
| `COCRDSLC.cbl` | `2220-EDIT-CARD` | `CardDetailService` | `validateCardNumber()` | Card-number edit → Luhn/@Pattern validation |
| `COCRDSLC.cbl` | `2220-EDIT-CARD-EXIT` | `CardDetailService` | `validateCardNumber() (end)` | PERFORM...THRU terminator → marks end of validateCardNumber(); no separate Java method |
| `COCRDSLC.cbl` | `9000-READ-DATA` | `CardDetailService` | `readData()` | VSAM READ → JPA read orchestration |
| `COCRDSLC.cbl` | `9000-READ-DATA-EXIT` | `CardDetailService` | `readData() (end)` | PERFORM...THRU terminator → marks end of readData(); no separate Java method |
| `COCRDSLC.cbl` | `9100-GETCARD-BYACCTCARD` | `CardDetailService` | `findCardByAcctAndCard()` | VSAM READ CARDDAT by acct+card → CardRepository composite query |
| `COCRDSLC.cbl` | `9100-GETCARD-BYACCTCARD-EXIT` | `CardDetailService` | `findCardByAcctAndCard() (end)` | PERFORM...THRU terminator → marks end of findCardByAcctAndCard(); no separate Java method |
| `COCRDSLC.cbl` | `9150-GETCARD-BYACCT` | `CardDetailService` | `findCardByAccount()` | VSAM READ CARDDAT by account → CardRepository derived query |
| `COCRDSLC.cbl` | `9150-GETCARD-BYACCT-EXIT` | `CardDetailService` | `findCardByAccount() (end)` | PERFORM...THRU terminator → marks end of findCardByAccount(); no separate Java method |
| `COCRDSLC.cbl` | `SEND-LONG-TEXT` | `CardController` | `errorResponse()` | CICS SEND TEXT (long) → error response body |
| `COCRDSLC.cbl` | `SEND-LONG-TEXT-EXIT` | `CardController` | `errorResponse() (end)` | PERFORM...THRU terminator → marks end of errorResponse(); no separate Java method |
| `COCRDSLC.cbl` | `SEND-PLAIN-TEXT` | `CardController` | `errorResponse()` | CICS SEND TEXT → plain error response body |
| `COCRDSLC.cbl` | `SEND-PLAIN-TEXT-EXIT` | `CardController` | `errorResponse() (end)` | PERFORM...THRU terminator → marks end of errorResponse(); no separate Java method |
| `COCRDSLC.cbl` | `ABEND-ROUTINE` | `CardDetailService` | `handleAbend()` | CICS HANDLE ABEND → @ExceptionHandler / unchecked exception |

#### `COCRDUPC.cbl` — Card update — optimistic concurrency (tran CCUP)

*Primary Java artifact(s):* `com.cardemo.service.card.CardUpdateService` · `com.cardemo.controller.CardController` · *Paragraphs:* 45

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDUPC.cbl` | `0000-MAIN` | `CardUpdateService` | `handleRequest()` | Pseudo-conversational entry (tran CCUP) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COCRDUPC.cbl` | `COMMON-RETURN` | `CardUpdateService` | `buildResponse()` | CICS RETURN TRANSID COMMAREA → REST response; pseudo-conversational state externalised to token |
| `COCRDUPC.cbl` | `0000-MAIN-EXIT` | `CardUpdateService` | `handleRequest() (end)` | PERFORM...THRU terminator → marks end of handleRequest(); no separate Java method |
| `COCRDUPC.cbl` | `1000-PROCESS-INPUTS` | `CardUpdateService` | `processInputs()` | Input orchestration paragraph → service input handling |
| `COCRDUPC.cbl` | `1000-PROCESS-INPUTS-EXIT` | `CardUpdateService` | `processInputs() (end)` | PERFORM...THRU terminator → marks end of processInputs(); no separate Java method |
| `COCRDUPC.cbl` | `1100-RECEIVE-MAP` | `CardController` | `PUT /api/cards/{id} @RequestBody` | BMS RECEIVE MAP (Card Update) → request DTO binding + Jakarta Validation |
| `COCRDUPC.cbl` | `1100-RECEIVE-MAP-EXIT` | `CardController` | `PUT /api/cards/{id} @RequestBody (end)` | PERFORM...THRU terminator → marks end of PUT /api/cards/{id} @RequestBody; no separate Java method |
| `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS` | `CardUpdateService` | `validateInputs()` | Field-edit cascade entry → Jakarta Validation + custom validators |
| `COCRDUPC.cbl` | `1200-EDIT-MAP-INPUTS-EXIT` | `CardUpdateService` | `validateInputs() (end)` | PERFORM...THRU terminator → marks end of validateInputs(); no separate Java method |
| `COCRDUPC.cbl` | `1210-EDIT-ACCOUNT` | `CardUpdateService` | `validateAccountId()` | Account-id edit → @Pattern/@Size validation |
| `COCRDUPC.cbl` | `1210-EDIT-ACCOUNT-EXIT` | `CardUpdateService` | `validateAccountId() (end)` | PERFORM...THRU terminator → marks end of validateAccountId(); no separate Java method |
| `COCRDUPC.cbl` | `1220-EDIT-CARD` | `CardUpdateService` | `validateCardNumber()` | Card-number edit → Luhn/@Pattern validation |
| `COCRDUPC.cbl` | `1220-EDIT-CARD-EXIT` | `CardUpdateService` | `validateCardNumber() (end)` | PERFORM...THRU terminator → marks end of validateCardNumber(); no separate Java method |
| `COCRDUPC.cbl` | `1230-EDIT-NAME` | `CardUpdateService` | `validateName()` | Name edit → @Pattern/@Size validation |
| `COCRDUPC.cbl` | `1230-EDIT-NAME-EXIT` | `CardUpdateService` | `validateName() (end)` | PERFORM...THRU terminator → marks end of validateName(); no separate Java method |
| `COCRDUPC.cbl` | `1240-EDIT-CARDSTATUS` | `CardUpdateService` | `validateCardStatus()` | Card-status edit → active-status enum validation |
| `COCRDUPC.cbl` | `1240-EDIT-CARDSTATUS-EXIT` | `CardUpdateService` | `validateCardStatus() (end)` | PERFORM...THRU terminator → marks end of validateCardStatus(); no separate Java method |
| `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON` | `CardUpdateService` | `validateExpiryMonth()` | Expiry-month edit → 1..12 range validation |
| `COCRDUPC.cbl` | `1250-EDIT-EXPIRY-MON-EXIT` | `CardUpdateService` | `validateExpiryMonth() (end)` | PERFORM...THRU terminator → marks end of validateExpiryMonth(); no separate Java method |
| `COCRDUPC.cbl` | `1260-EDIT-EXPIRY-YEAR` | `CardUpdateService` | `validateExpiryYear()` | Expiry-year edit → range validation |
| `COCRDUPC.cbl` | `1260-EDIT-EXPIRY-YEAR-EXIT` | `CardUpdateService` | `validateExpiryYear() (end)` | PERFORM...THRU terminator → marks end of validateExpiryYear(); no separate Java method |
| `COCRDUPC.cbl` | `2000-DECIDE-ACTION` | `CardUpdateService` | `decideAction()` | EVALUATE action selector → switch on request action |
| `COCRDUPC.cbl` | `2000-DECIDE-ACTION-EXIT` | `CardUpdateService` | `decideAction() (end)` | PERFORM...THRU terminator → marks end of decideAction(); no separate Java method |
| `COCRDUPC.cbl` | `3000-SEND-MAP` | `CardController` | `PUT /api/cards/{id} response` | BMS SEND MAP (Card Update) → JSON response DTO |
| `COCRDUPC.cbl` | `3000-SEND-MAP-EXIT` | `CardController` | `PUT /api/cards/{id} response (end)` | PERFORM...THRU terminator → marks end of PUT /api/cards/{id} response; no separate Java method |
| `COCRDUPC.cbl` | `3100-SCREEN-INIT` | `CardUpdateService` | `initResponse()` | Map/array initialisation → response DTO initialisation |
| `COCRDUPC.cbl` | `3100-SCREEN-INIT-EXIT` | `CardUpdateService` | `initResponse() (end)` | PERFORM...THRU terminator → marks end of initResponse(); no separate Java method |
| `COCRDUPC.cbl` | `3200-SETUP-SCREEN-VARS` | `CardUpdateService` | `populateResponse()` | Map symbolic vars → response DTO field population |
| `COCRDUPC.cbl` | `3200-SETUP-SCREEN-VARS-EXIT` | `CardUpdateService` | `populateResponse() (end)` | PERFORM...THRU terminator → marks end of populateResponse(); no separate Java method |
| `COCRDUPC.cbl` | `3250-SETUP-INFOMSG` | `CardUpdateService` | `buildInfoMessage()` | CSMSG01Y info text → response message field |
| `COCRDUPC.cbl` | `3250-SETUP-INFOMSG-EXIT` | `CardUpdateService` | `buildInfoMessage() (end)` | PERFORM...THRU terminator → marks end of buildInfoMessage(); no separate Java method |
| `COCRDUPC.cbl` | `3300-SETUP-SCREEN-ATTRS` | `CardUpdateService` | `applyFieldState()` | BMS field attributes (colour/protect) → response field-state flags |
| `COCRDUPC.cbl` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `CardUpdateService` | `applyFieldState() (end)` | PERFORM...THRU terminator → marks end of applyFieldState(); no separate Java method |
| `COCRDUPC.cbl` | `3400-SEND-SCREEN` | `CardController` | `PUT /api/cards/{id} response` | BMS SEND MAP (Card Update) → JSON response DTO |
| `COCRDUPC.cbl` | `3400-SEND-SCREEN-EXIT` | `CardController` | `PUT /api/cards/{id} response (end)` | PERFORM...THRU terminator → marks end of PUT /api/cards/{id} response; no separate Java method |
| `COCRDUPC.cbl` | `9000-READ-DATA` | `CardUpdateService` | `readData()` | VSAM READ → JPA read orchestration |
| `COCRDUPC.cbl` | `9000-READ-DATA-EXIT` | `CardUpdateService` | `readData() (end)` | PERFORM...THRU terminator → marks end of readData(); no separate Java method |
| `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD` | `CardUpdateService` | `findCardByAcctAndCard()` | VSAM READ CARDDAT by acct+card → CardRepository composite query |
| `COCRDUPC.cbl` | `9100-GETCARD-BYACCTCARD-EXIT` | `CardUpdateService` | `findCardByAcctAndCard() (end)` | PERFORM...THRU terminator → marks end of findCardByAcctAndCard(); no separate Java method |
| `COCRDUPC.cbl` | `9200-WRITE-PROCESSING` | `CardUpdateService` | `persistChanges()` | VSAM REWRITE → repository.save() with @Version optimistic-lock check |
| `COCRDUPC.cbl` | `9200-WRITE-PROCESSING-EXIT` | `CardUpdateService` | `persistChanges() (end)` | PERFORM...THRU terminator → marks end of persistChanges(); no separate Java method |
| `COCRDUPC.cbl` | `9300-CHECK-CHANGE-IN-REC` | `CardUpdateService` | `verifyNoConcurrentChange()` | Before/after record-image compare → JPA @Version optimistic lock |
| `COCRDUPC.cbl` | `9300-CHECK-CHANGE-IN-REC-EXIT` | `CardUpdateService` | `verifyNoConcurrentChange() (end)` | PERFORM...THRU terminator → marks end of verifyNoConcurrentChange(); no separate Java method |
| `COCRDUPC.cbl` | `ABEND-ROUTINE` | `CardUpdateService` | `handleAbend()` | CICS HANDLE ABEND → @ExceptionHandler / unchecked exception |
| `COCRDUPC.cbl` | `ABEND-ROUTINE-EXIT` | `CardUpdateService` | `handleAbend() (end)` | PERFORM...THRU terminator → marks end of handleAbend(); no separate Java method |

#### `COTRN00C.cbl` — Transaction list — paginated browse (tran CT00)

*Primary Java artifact(s):* `com.cardemo.service.transaction.TransactionListService` · `com.cardemo.controller.TransactionController` · *Paragraphs:* 16

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN00C.cbl` | `MAIN-PARA` | `TransactionListService` | `handleRequest()` | Pseudo-conversational entry (tran CT00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COTRN00C.cbl` | `PROCESS-ENTER-KEY` | `TransactionListService` | `listTransactions()` | ENTER → paginated transaction browse |
| `COTRN00C.cbl` | `PROCESS-PF7-KEY` | `TransactionListService` | `pageBackward()` | PF7 → previous page; VSAM STARTBR/READPREV → Pageable (page-1) |
| `COTRN00C.cbl` | `PROCESS-PF8-KEY` | `TransactionListService` | `pageForward()` | PF8 → next page; VSAM READNEXT → Pageable (page+1) |
| `COTRN00C.cbl` | `PROCESS-PAGE-FORWARD` | `TransactionListService` | `pageForward()` | Forward paging; VSAM browse → Spring Data Pageable/Slice |
| `COTRN00C.cbl` | `PROCESS-PAGE-BACKWARD` | `TransactionListService` | `pageBackward()` | Backward paging; VSAM browse → Spring Data Pageable/Slice |
| `COTRN00C.cbl` | `POPULATE-TRAN-DATA` | `TransactionListService` | `mapTransactionRow()` | Map transaction record → list-row DTO |
| `COTRN00C.cbl` | `INITIALIZE-TRAN-DATA` | `TransactionListService` | `initTransactionRows()` | Initialise transaction list rows → empty list DTO |
| `COTRN00C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionListService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COTRN00C.cbl` | `SEND-TRNLST-SCREEN` | `TransactionController` | `GET /api/transactions response` | BMS SEND MAP (Transaction List) → JSON response DTO |
| `COTRN00C.cbl` | `RECEIVE-TRNLST-SCREEN` | `TransactionController` | `GET /api/transactions @RequestBody` | BMS RECEIVE MAP (Transaction List) → request DTO binding + Jakarta Validation |
| `COTRN00C.cbl` | `POPULATE-HEADER-INFO` | `TransactionListService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COTRN00C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionListService` | `openTransactionBrowse()` | VSAM STARTBR TRANSACT → begin Pageable browse |
| `COTRN00C.cbl` | `READNEXT-TRANSACT-FILE` | `TransactionListService` | `readNextTransaction()` | VSAM READNEXT → next page element |
| `COTRN00C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionListService` | `readPrevTransaction()` | VSAM READPREV → previous page element |
| `COTRN00C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionListService` | `closeTransactionBrowse()` | VSAM ENDBR → end browse (no-op under JPA paging) |

#### `COTRN01C.cbl` — Transaction view — single keyed read (tran CT01)

*Primary Java artifact(s):* `com.cardemo.service.transaction.TransactionDetailService` · `com.cardemo.controller.TransactionController` · *Paragraphs:* 9

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN01C.cbl` | `MAIN-PARA` | `TransactionDetailService` | `handleRequest()` | Pseudo-conversational entry (tran CT01) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COTRN01C.cbl` | `PROCESS-ENTER-KEY` | `TransactionDetailService` | `getTransaction()` | ENTER → single transaction lookup |
| `COTRN01C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionDetailService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COTRN01C.cbl` | `SEND-TRNVIEW-SCREEN` | `TransactionController` | `GET /api/transactions/{id} response` | BMS SEND MAP (Transaction View) → JSON response DTO |
| `COTRN01C.cbl` | `RECEIVE-TRNVIEW-SCREEN` | `TransactionController` | `GET /api/transactions/{id} @RequestBody` | BMS RECEIVE MAP (Transaction View) → request DTO binding + Jakarta Validation |
| `COTRN01C.cbl` | `POPULATE-HEADER-INFO` | `TransactionDetailService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COTRN01C.cbl` | `READ-TRANSACT-FILE` | `TransactionDetailService` | `findTransaction()` | VSAM READ TRANSACT → TransactionRepository.findById() |
| `COTRN01C.cbl` | `CLEAR-CURRENT-SCREEN` | `TransactionDetailService` | `clearForm()` | Clear map fields → reset response DTO |
| `COTRN01C.cbl` | `INITIALIZE-ALL-FIELDS` | `TransactionDetailService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

#### `COTRN02C.cbl` — Transaction add — auto-ID + xref resolution (tran CT02)

*Primary Java artifact(s):* `com.cardemo.service.transaction.TransactionAddService` · `com.cardemo.controller.TransactionController` · *Paragraphs:* 18

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN02C.cbl` | `MAIN-PARA` | `TransactionAddService` | `handleRequest()` | Pseudo-conversational entry (tran CT02) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COTRN02C.cbl` | `PROCESS-ENTER-KEY` | `TransactionAddService` | `addTransaction()` | ENTER → add-transaction orchestration |
| `COTRN02C.cbl` | `VALIDATE-INPUT-KEY-FIELDS` | `TransactionAddService` | `validateKeyFields()` | Key-field edit → Jakarta @NotNull/@Pattern on request DTO |
| `COTRN02C.cbl` | `VALIDATE-INPUT-DATA-FIELDS` | `TransactionAddService` | `validateDataFields()` | Data-field edit → Jakarta Validation constraints |
| `COTRN02C.cbl` | `ADD-TRANSACTION` | `TransactionAddService` | `persistTransaction()` | Auto-ID + VSAM WRITE TRANSACT → TransactionRepository.save() |
| `COTRN02C.cbl` | `COPY-LAST-TRAN-DATA` | `TransactionAddService` | `copyPriorTransaction()` | Pre-fill from last transaction → DTO defaulting |
| `COTRN02C.cbl` | `RETURN-TO-PREV-SCREEN` | `TransactionAddService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COTRN02C.cbl` | `SEND-TRNADD-SCREEN` | `TransactionController` | `POST /api/transactions response` | BMS SEND MAP (Transaction Add) → JSON response DTO |
| `COTRN02C.cbl` | `RECEIVE-TRNADD-SCREEN` | `TransactionController` | `POST /api/transactions @RequestBody` | BMS RECEIVE MAP (Transaction Add) → request DTO binding + Jakarta Validation |
| `COTRN02C.cbl` | `POPULATE-HEADER-INFO` | `TransactionAddService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COTRN02C.cbl` | `READ-CXACAIX-FILE` | `TransactionAddService` | `findXrefByCard()` | VSAM READ CXACAIX (alt-index) → CardCrossReferenceRepository derived query |
| `COTRN02C.cbl` | `READ-CCXREF-FILE` | `TransactionAddService` | `findXref()` | VSAM READ CARDXREF → CardCrossReferenceRepository.findById() |
| `COTRN02C.cbl` | `STARTBR-TRANSACT-FILE` | `TransactionAddService` | `openTransactionBrowse()` | VSAM STARTBR TRANSACT → begin Pageable browse |
| `COTRN02C.cbl` | `READPREV-TRANSACT-FILE` | `TransactionAddService` | `readPrevTransaction()` | VSAM READPREV → previous page element |
| `COTRN02C.cbl` | `ENDBR-TRANSACT-FILE` | `TransactionAddService` | `closeTransactionBrowse()` | VSAM ENDBR → end browse (no-op under JPA paging) |
| `COTRN02C.cbl` | `WRITE-TRANSACT-FILE` | `TransactionAddService` | `saveTransaction()` | VSAM WRITE TRANSACT → TransactionRepository.save() |
| `COTRN02C.cbl` | `CLEAR-CURRENT-SCREEN` | `TransactionAddService` | `clearForm()` | Clear map fields → reset response DTO |
| `COTRN02C.cbl` | `INITIALIZE-ALL-FIELDS` | `TransactionAddService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

#### `COBIL00C.cbl` — Bill payment — balance update + tx create in one transaction (tran CB00)

*Primary Java artifact(s):* `com.cardemo.service.billing.BillPaymentService` · `com.cardemo.controller.BillingController` · *Paragraphs:* 16

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COBIL00C.cbl` | `MAIN-PARA` | `BillPaymentService` | `handleRequest()` | Pseudo-conversational entry (tran CB00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COBIL00C.cbl` | `PROCESS-ENTER-KEY` | `BillPaymentService` | `processPayment()` | ENTER → bill-payment orchestration; balance update + tx create in one @Transactional |
| `COBIL00C.cbl` | `GET-CURRENT-TIMESTAMP` | `BillPaymentService` | `currentTimestamp()` | CICS ASKTIME/FORMATTIME → java.time Instant/LocalDateTime |
| `COBIL00C.cbl` | `RETURN-TO-PREV-SCREEN` | `BillPaymentService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COBIL00C.cbl` | `SEND-BILLPAY-SCREEN` | `BillingController` | `POST /api/billing/pay response` | BMS SEND MAP (Bill Pay) → JSON response DTO |
| `COBIL00C.cbl` | `RECEIVE-BILLPAY-SCREEN` | `BillingController` | `POST /api/billing/pay @RequestBody` | BMS RECEIVE MAP (Bill Pay) → request DTO binding + Jakarta Validation |
| `COBIL00C.cbl` | `POPULATE-HEADER-INFO` | `BillPaymentService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COBIL00C.cbl` | `READ-ACCTDAT-FILE` | `BillPaymentService` | `findAccount()` | VSAM READ ACCTDAT → AccountRepository.findById() |
| `COBIL00C.cbl` | `UPDATE-ACCTDAT-FILE` | `BillPaymentService` | `updateAccount()` | VSAM REWRITE ACCTDAT → AccountRepository.save() |
| `COBIL00C.cbl` | `READ-CXACAIX-FILE` | `BillPaymentService` | `findXrefByCard()` | VSAM READ CXACAIX (alt-index) → CardCrossReferenceRepository derived query |
| `COBIL00C.cbl` | `STARTBR-TRANSACT-FILE` | `BillPaymentService` | `openTransactionBrowse()` | VSAM STARTBR TRANSACT → begin Pageable browse |
| `COBIL00C.cbl` | `READPREV-TRANSACT-FILE` | `BillPaymentService` | `readPrevTransaction()` | VSAM READPREV → previous page element |
| `COBIL00C.cbl` | `ENDBR-TRANSACT-FILE` | `BillPaymentService` | `closeTransactionBrowse()` | VSAM ENDBR → end browse (no-op under JPA paging) |
| `COBIL00C.cbl` | `WRITE-TRANSACT-FILE` | `BillPaymentService` | `saveTransaction()` | VSAM WRITE TRANSACT → TransactionRepository.save() |
| `COBIL00C.cbl` | `CLEAR-CURRENT-SCREEN` | `BillPaymentService` | `clearForm()` | Clear map fields → reset response DTO |
| `COBIL00C.cbl` | `INITIALIZE-ALL-FIELDS` | `BillPaymentService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

#### `CORPT00C.cbl` — Report submission — online→batch bridge via TDQ→SQS (tran CR00)

*Primary Java artifact(s):* `com.cardemo.service.report.ReportSubmissionService` · `com.cardemo.controller.ReportController` · *Paragraphs:* 10

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CORPT00C.cbl` | `MAIN-PARA` | `ReportSubmissionService` | `handleRequest()` | Pseudo-conversational entry (tran CR00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `CORPT00C.cbl` | `PROCESS-ENTER-KEY` | `ReportSubmissionService` | `submitReport()` | ENTER → report-submission orchestration |
| `CORPT00C.cbl` | `SUBMIT-JOB-TO-INTRDR` | `ReportSubmissionService` | `publishReportRequest()` | Submit job to internal reader → SQS publish (TDQ→SQS FIFO) |
| `CORPT00C.cbl` | `WIRTE-JOBSUB-TDQ` | `ReportSubmissionService` | `publishToQueue()` | WRITEQ TD 'JOBS' → SQS send to carddemo-report-jobs.fifo (CICS TDQ → SQS); source label spelling preserved |
| `CORPT00C.cbl` | `RETURN-TO-PREV-SCREEN` | `ReportSubmissionService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `CORPT00C.cbl` | `SEND-TRNRPT-SCREEN` | `ReportController` | `POST /api/reports/submit response` | BMS SEND MAP (Report Submission) → JSON response DTO |
| `CORPT00C.cbl` | `RETURN-TO-CICS` | `ReportSubmissionService` | `buildResponse()` | CICS RETURN → terminate request, emit REST response |
| `CORPT00C.cbl` | `RECEIVE-TRNRPT-SCREEN` | `ReportController` | `POST /api/reports/submit @RequestBody` | BMS RECEIVE MAP (Report Submission) → request DTO binding + Jakarta Validation |
| `CORPT00C.cbl` | `POPULATE-HEADER-INFO` | `ReportSubmissionService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `CORPT00C.cbl` | `INITIALIZE-ALL-FIELDS` | `ReportSubmissionService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

#### `COUSR00C.cbl` — User list — paginated browse (tran CU00)

*Primary Java artifact(s):* `com.cardemo.service.admin.UserListService` · `com.cardemo.controller.UserAdminController` · *Paragraphs:* 16

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR00C.cbl` | `MAIN-PARA` | `UserListService` | `handleRequest()` | Pseudo-conversational entry (tran CU00) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COUSR00C.cbl` | `PROCESS-ENTER-KEY` | `UserListService` | `listUsers()` | ENTER → paginated user browse |
| `COUSR00C.cbl` | `PROCESS-PF7-KEY` | `UserListService` | `pageBackward()` | PF7 → previous page; VSAM STARTBR/READPREV → Pageable (page-1) |
| `COUSR00C.cbl` | `PROCESS-PF8-KEY` | `UserListService` | `pageForward()` | PF8 → next page; VSAM READNEXT → Pageable (page+1) |
| `COUSR00C.cbl` | `PROCESS-PAGE-FORWARD` | `UserListService` | `pageForward()` | Forward paging; VSAM browse → Spring Data Pageable/Slice |
| `COUSR00C.cbl` | `PROCESS-PAGE-BACKWARD` | `UserListService` | `pageBackward()` | Backward paging; VSAM browse → Spring Data Pageable/Slice |
| `COUSR00C.cbl` | `POPULATE-USER-DATA` | `UserListService` | `mapUserRow()` | Map USRSEC record → list-row DTO |
| `COUSR00C.cbl` | `INITIALIZE-USER-DATA` | `UserListService` | `initUserRows()` | Initialise user list rows → empty list DTO |
| `COUSR00C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserListService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COUSR00C.cbl` | `SEND-USRLST-SCREEN` | `UserAdminController` | `GET /api/admin/users response` | BMS SEND MAP (User List) → JSON response DTO |
| `COUSR00C.cbl` | `RECEIVE-USRLST-SCREEN` | `UserAdminController` | `GET /api/admin/users @RequestBody` | BMS RECEIVE MAP (User List) → request DTO binding + Jakarta Validation |
| `COUSR00C.cbl` | `POPULATE-HEADER-INFO` | `UserListService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COUSR00C.cbl` | `STARTBR-USER-SEC-FILE` | `UserListService` | `openUserBrowse()` | VSAM STARTBR USRSEC → begin Pageable browse |
| `COUSR00C.cbl` | `READNEXT-USER-SEC-FILE` | `UserListService` | `readNextUser()` | VSAM READNEXT → next page element |
| `COUSR00C.cbl` | `READPREV-USER-SEC-FILE` | `UserListService` | `readPrevUser()` | VSAM READPREV → previous page element |
| `COUSR00C.cbl` | `ENDBR-USER-SEC-FILE` | `UserListService` | `closeUserBrowse()` | VSAM ENDBR → end browse (no-op under JPA paging) |

#### `COUSR01C.cbl` — User add — BCrypt hash on create (tran CU01)

*Primary Java artifact(s):* `com.cardemo.service.admin.UserAddService` · `com.cardemo.controller.UserAdminController` · *Paragraphs:* 9

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR01C.cbl` | `MAIN-PARA` | `UserAddService` | `handleRequest()` | Pseudo-conversational entry (tran CU01) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COUSR01C.cbl` | `PROCESS-ENTER-KEY` | `UserAddService` | `addUser()` | ENTER → add-user orchestration; BCrypt hash on create |
| `COUSR01C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserAddService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COUSR01C.cbl` | `SEND-USRADD-SCREEN` | `UserAdminController` | `POST /api/admin/users response` | BMS SEND MAP (User Add) → JSON response DTO |
| `COUSR01C.cbl` | `RECEIVE-USRADD-SCREEN` | `UserAdminController` | `POST /api/admin/users @RequestBody` | BMS RECEIVE MAP (User Add) → request DTO binding + Jakarta Validation |
| `COUSR01C.cbl` | `POPULATE-HEADER-INFO` | `UserAddService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COUSR01C.cbl` | `WRITE-USER-SEC-FILE` | `UserAddService` | `saveUser()` | VSAM WRITE USRSEC → UserSecurityRepository.save(); BCrypt hash stored |
| `COUSR01C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserAddService` | `clearForm()` | Clear map fields → reset response DTO |
| `COUSR01C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserAddService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

#### `COUSR02C.cbl` — User update (tran CU02)

*Primary Java artifact(s):* `com.cardemo.service.admin.UserUpdateService` · `com.cardemo.controller.UserAdminController` · *Paragraphs:* 11

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR02C.cbl` | `MAIN-PARA` | `UserUpdateService` | `handleRequest()` | Pseudo-conversational entry (tran CU02) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COUSR02C.cbl` | `PROCESS-ENTER-KEY` | `UserUpdateService` | `updateUser()` | ENTER → update-user orchestration |
| `COUSR02C.cbl` | `UPDATE-USER-INFO` | `UserUpdateService` | `applyUserUpdate()` | Apply field changes to USRSEC record before REWRITE |
| `COUSR02C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserUpdateService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COUSR02C.cbl` | `SEND-USRUPD-SCREEN` | `UserAdminController` | `PUT /api/admin/users/{id} response` | BMS SEND MAP (User Update) → JSON response DTO |
| `COUSR02C.cbl` | `RECEIVE-USRUPD-SCREEN` | `UserAdminController` | `PUT /api/admin/users/{id} @RequestBody` | BMS RECEIVE MAP (User Update) → request DTO binding + Jakarta Validation |
| `COUSR02C.cbl` | `POPULATE-HEADER-INFO` | `UserUpdateService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COUSR02C.cbl` | `READ-USER-SEC-FILE` | `UserUpdateService` | `findUser()` | VSAM READ USRSEC → UserSecurityRepository.findById() |
| `COUSR02C.cbl` | `UPDATE-USER-SEC-FILE` | `UserUpdateService` | `saveUser()` | VSAM REWRITE USRSEC → UserSecurityRepository.save() |
| `COUSR02C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserUpdateService` | `clearForm()` | Clear map fields → reset response DTO |
| `COUSR02C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserUpdateService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

#### `COUSR03C.cbl` — User delete (tran CU03)

*Primary Java artifact(s):* `com.cardemo.service.admin.UserDeleteService` · `com.cardemo.controller.UserAdminController` · *Paragraphs:* 11

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR03C.cbl` | `MAIN-PARA` | `UserDeleteService` | `handleRequest()` | Pseudo-conversational entry (tran CU03) → stateless REST dispatch; CICS COMMAREA state → request context/token |
| `COUSR03C.cbl` | `PROCESS-ENTER-KEY` | `UserDeleteService` | `deleteUser()` | ENTER → delete-user orchestration |
| `COUSR03C.cbl` | `DELETE-USER-INFO` | `UserDeleteService` | `applyUserDelete()` | Confirm + delete USRSEC record |
| `COUSR03C.cbl` | `RETURN-TO-PREV-SCREEN` | `UserDeleteService` | `buildResponse()` | XCTL to caller → response nav target from request context |
| `COUSR03C.cbl` | `SEND-USRDEL-SCREEN` | `UserAdminController` | `DELETE /api/admin/users/{id} response` | BMS SEND MAP (User Delete) → JSON response DTO |
| `COUSR03C.cbl` | `RECEIVE-USRDEL-SCREEN` | `UserAdminController` | `DELETE /api/admin/users/{id} @RequestBody` | BMS RECEIVE MAP (User Delete) → request DTO binding + Jakarta Validation |
| `COUSR03C.cbl` | `POPULATE-HEADER-INFO` | `UserDeleteService` | `buildHeader()` | COTTL01Y/CSDAT01Y title+date+time → response header metadata |
| `COUSR03C.cbl` | `READ-USER-SEC-FILE` | `UserDeleteService` | `findUser()` | VSAM READ USRSEC → UserSecurityRepository.findById() |
| `COUSR03C.cbl` | `DELETE-USER-SEC-FILE` | `UserDeleteService` | `deleteUser()` | VSAM DELETE USRSEC → UserSecurityRepository.delete() |
| `COUSR03C.cbl` | `CLEAR-CURRENT-SCREEN` | `UserDeleteService` | `clearForm()` | Clear map fields → reset response DTO |
| `COUSR03C.cbl` | `INITIALIZE-ALL-FIELDS` | `UserDeleteService` | `initFields()` | INITIALIZE map → default-initialise response DTO |

### 4.2 Shared Utility (1)

#### `CSUTLDTC.cbl` — Date validation via LE CEEDAYS — invoked by online and batch contexts

*Primary Java artifact(s):* `com.cardemo.service.shared.DateValidationService` · *Paragraphs:* 2

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CSUTLDTC.cbl` | `A000-MAIN` | `DateValidationService` | `validateDate()` | CALL 'CEEDAYS' date validation → java.time.LocalDate parse/validate with format string |
| `CSUTLDTC.cbl` | `A000-MAIN-EXIT` | `DateValidationService` | `validateDate() (end)` | PERFORM...THRU terminator → marks end of validateDate(); no separate Java method |

### 4.3 Batch Programs (10)

#### `CBACT01C.cbl` — Account master sequential reader / diagnostic print

*Primary Java artifact(s):* `com.cardemo.batch.readers.AccountFileReader` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 6

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT01C.cbl` | `1000-ACCTFILE-GET-NEXT` | `AccountFileReader` | `read()` | Sequential READ NEXT ACCTFILE → ItemReader.read() (cursor over Account) |
| `CBACT01C.cbl` | `1100-DISPLAY-ACCT-RECORD` | `AccountFileReader` | `logRecord()` | DISPLAY record → structured log line |
| `CBACT01C.cbl` | `0000-ACCTFILE-OPEN` | `AccountFileReader` | `open()` | VSAM OPEN ACCTFILE → AccountRepository / ItemStreamReader.open() |
| `CBACT01C.cbl` | `9000-ACCTFILE-CLOSE` | `AccountFileReader` | `close()` | VSAM CLOSE ACCTFILE → reader close / resource release |
| `CBACT01C.cbl` | `9999-ABEND-PROGRAM` | `AccountFileReader` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBACT01C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBACT02C.cbl` — Card master sequential reader / diagnostic print

*Primary Java artifact(s):* `com.cardemo.batch.readers.CardFileReader` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 5

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT02C.cbl` | `1000-CARDFILE-GET-NEXT` | `CardFileReader` | `read()` | Sequential READ NEXT CARDFILE → ItemReader.read() (cursor over Card) |
| `CBACT02C.cbl` | `0000-CARDFILE-OPEN` | `CardFileReader` | `open()` | VSAM OPEN CARDFILE → CardRepository / ItemStreamReader.open() |
| `CBACT02C.cbl` | `9000-CARDFILE-CLOSE` | `CardFileReader` | `close()` | VSAM CLOSE CARDFILE → reader close / resource release |
| `CBACT02C.cbl` | `9999-ABEND-PROGRAM` | `CardFileReader` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBACT02C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBACT03C.cbl` — Card cross-reference sequential reader / diagnostic print

*Primary Java artifact(s):* `com.cardemo.batch.readers.CrossReferenceFileReader` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 5

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT03C.cbl` | `1000-XREFFILE-GET-NEXT` | `CrossReferenceFileReader` | `read()` | Sequential READ NEXT XREFFILE → ItemReader.read() (cursor over CardCrossReference) |
| `CBACT03C.cbl` | `0000-XREFFILE-OPEN` | `CrossReferenceFileReader` | `open()` | VSAM OPEN XREFFILE → CardCrossReferenceRepository / ItemStreamReader.open() |
| `CBACT03C.cbl` | `9000-XREFFILE-CLOSE` | `CrossReferenceFileReader` | `close()` | VSAM CLOSE XREFFILE → reader close / resource release |
| `CBACT03C.cbl` | `9999-ABEND-PROGRAM` | `CrossReferenceFileReader` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBACT03C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBACT04C.cbl` — Interest calculation — (TRAN-CAT-BAL × DIS-INT-RATE)/1200 with DEFAULT-group fallback

*Primary Java artifact(s):* `com.cardemo.batch.jobs.InterestCalculationJob` · `com.cardemo.batch.processors.InterestCalculationProcessor` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 22

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT04C.cbl` | `0000-TCATBALF-OPEN` | `InterestCalculationProcessor` | `open()` | VSAM OPEN TCATBALF → TransactionCategoryBalanceRepository / ItemStreamReader.open() |
| `CBACT04C.cbl` | `0100-XREFFILE-OPEN` | `InterestCalculationProcessor` | `open()` | VSAM OPEN XREFFILE → CardCrossReferenceRepository / ItemStreamReader.open() |
| `CBACT04C.cbl` | `0200-DISCGRP-OPEN` | `InterestCalculationProcessor` | `open()` | VSAM OPEN DISCGRP → DisclosureGroupRepository / ItemStreamReader.open() |
| `CBACT04C.cbl` | `0300-ACCTFILE-OPEN` | `InterestCalculationProcessor` | `open()` | VSAM OPEN ACCTFILE → AccountRepository / ItemStreamReader.open() |
| `CBACT04C.cbl` | `0400-TRANFILE-OPEN` | `InterestCalculationProcessor` | `open()` | VSAM OPEN TRANFILE → TransactionRepository / ItemStreamReader.open() |
| `CBACT04C.cbl` | `1000-TCATBALF-GET-NEXT` | `InterestCalculationProcessor` | `read()` | Sequential READ NEXT TCATBALF → ItemReader.read() (cursor over TransactionCategoryBalance) |
| `CBACT04C.cbl` | `1050-UPDATE-ACCOUNT` | `InterestCalculationProcessor` | `updateAccount()` | Update account after interest → AccountRepository.save() |
| `CBACT04C.cbl` | `1100-GET-ACCT-DATA` | `InterestCalculationProcessor` | `loadAccount()` | READ ACCTDAT → AccountRepository.findById() |
| `CBACT04C.cbl` | `1110-GET-XREF-DATA` | `InterestCalculationProcessor` | `loadXref()` | READ CARDXREF → CardCrossReferenceRepository lookup |
| `CBACT04C.cbl` | `1200-GET-INTEREST-RATE` | `InterestCalculationProcessor` | `resolveInterestRate()` | READ DISCGRP by group+type+cat → DisclosureGroupRepository |
| `CBACT04C.cbl` | `1200-A-GET-DEFAULT-INT-RATE` | `InterestCalculationProcessor` | `resolveDefaultInterestRate()` | DEFAULT-group fallback rate → DisclosureGroupRepository (group='DEFAULT') |
| `CBACT04C.cbl` | `1300-COMPUTE-INTEREST` | `InterestCalculationProcessor` | `computeInterest()` | (TRAN-CAT-BAL × DIS-INT-RATE)/1200 → BigDecimal.divide(1200, HALF_EVEN) |
| `CBACT04C.cbl` | `1300-B-WRITE-TX` | `InterestCalculationProcessor` | `writeInterestTransaction()` | WRITE interest transaction → TransactionRepository.save() |
| `CBACT04C.cbl` | `1400-COMPUTE-FEES` | `InterestCalculationProcessor` | `computeFees()` | Fee computation → BigDecimal arithmetic (scale preserved) |
| `CBACT04C.cbl` | `9000-TCATBALF-CLOSE` | `InterestCalculationProcessor` | `close()` | VSAM CLOSE TCATBALF → reader close / resource release |
| `CBACT04C.cbl` | `9100-XREFFILE-CLOSE` | `InterestCalculationProcessor` | `close()` | VSAM CLOSE XREFFILE → reader close / resource release |
| `CBACT04C.cbl` | `9200-DISCGRP-CLOSE` | `InterestCalculationProcessor` | `close()` | VSAM CLOSE DISCGRP → reader close / resource release |
| `CBACT04C.cbl` | `9300-ACCTFILE-CLOSE` | `InterestCalculationProcessor` | `close()` | VSAM CLOSE ACCTFILE → reader close / resource release |
| `CBACT04C.cbl` | `9400-TRANFILE-CLOSE` | `InterestCalculationProcessor` | `close()` | VSAM CLOSE TRANFILE → reader close / resource release |
| `CBACT04C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `InterestCalculationProcessor` | `formatTimestamp()` | DB2-format timestamp → java.time formatting |
| `CBACT04C.cbl` | `9999-ABEND-PROGRAM` | `InterestCalculationProcessor` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBACT04C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBCUS01C.cbl` — Customer master sequential reader / diagnostic print

*Primary Java artifact(s):* `com.cardemo.batch.readers.CustomerFileReader` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 5

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBCUS01C.cbl` | `1000-CUSTFILE-GET-NEXT` | `CustomerFileReader` | `read()` | Sequential READ NEXT CUSTFILE → ItemReader.read() (cursor over Customer) |
| `CBCUS01C.cbl` | `0000-CUSTFILE-OPEN` | `CustomerFileReader` | `open()` | VSAM OPEN CUSTFILE → CustomerRepository / ItemStreamReader.open() |
| `CBCUS01C.cbl` | `9000-CUSTFILE-CLOSE` | `CustomerFileReader` | `close()` | VSAM CLOSE CUSTFILE → reader close / resource release |
| `CBCUS01C.cbl` | `Z-ABEND-PROGRAM` | `CustomerFileReader` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBCUS01C.cbl` | `Z-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBTRN01C.cbl` — Daily transaction reader — xref+account validation pre-pass

*Primary Java artifact(s):* `com.cardemo.batch.readers.DailyTransactionReader` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 18

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN01C.cbl` | `MAIN-PARA` | `DailyTransactionReader` | `run()` | Batch mainline → Spring Batch step driver |
| `CBTRN01C.cbl` | `1000-DALYTRAN-GET-NEXT` | `DailyTransactionReader` | `read()` | Sequential READ NEXT DALYTRAN → ItemReader.read() (cursor over DailyTransaction) |
| `CBTRN01C.cbl` | `2000-LOOKUP-XREF` | `DailyTransactionReader` | `lookupCardXref()` | READ CARDXREF → CardCrossReferenceRepository lookup |
| `CBTRN01C.cbl` | `3000-READ-ACCOUNT` | `DailyTransactionReader` | `lookupAccount()` | READ ACCTDAT → AccountRepository lookup |
| `CBTRN01C.cbl` | `0000-DALYTRAN-OPEN` | `DailyTransactionReader` | `open()` | VSAM OPEN DALYTRAN → DailyTransactionRepository / ItemStreamReader.open() |
| `CBTRN01C.cbl` | `0100-CUSTFILE-OPEN` | `DailyTransactionReader` | `open()` | VSAM OPEN CUSTFILE → CustomerRepository / ItemStreamReader.open() |
| `CBTRN01C.cbl` | `0200-XREFFILE-OPEN` | `DailyTransactionReader` | `open()` | VSAM OPEN XREFFILE → CardCrossReferenceRepository / ItemStreamReader.open() |
| `CBTRN01C.cbl` | `0300-CARDFILE-OPEN` | `DailyTransactionReader` | `open()` | VSAM OPEN CARDFILE → CardRepository / ItemStreamReader.open() |
| `CBTRN01C.cbl` | `0400-ACCTFILE-OPEN` | `DailyTransactionReader` | `open()` | VSAM OPEN ACCTFILE → AccountRepository / ItemStreamReader.open() |
| `CBTRN01C.cbl` | `0500-TRANFILE-OPEN` | `DailyTransactionReader` | `open()` | VSAM OPEN TRANFILE → TransactionRepository / ItemStreamReader.open() |
| `CBTRN01C.cbl` | `9000-DALYTRAN-CLOSE` | `DailyTransactionReader` | `close()` | VSAM CLOSE DALYTRAN → reader close / resource release |
| `CBTRN01C.cbl` | `9100-CUSTFILE-CLOSE` | `DailyTransactionReader` | `close()` | VSAM CLOSE CUSTFILE → reader close / resource release |
| `CBTRN01C.cbl` | `9200-XREFFILE-CLOSE` | `DailyTransactionReader` | `close()` | VSAM CLOSE XREFFILE → reader close / resource release |
| `CBTRN01C.cbl` | `9300-CARDFILE-CLOSE` | `DailyTransactionReader` | `close()` | VSAM CLOSE CARDFILE → reader close / resource release |
| `CBTRN01C.cbl` | `9400-ACCTFILE-CLOSE` | `DailyTransactionReader` | `close()` | VSAM CLOSE ACCTFILE → reader close / resource release |
| `CBTRN01C.cbl` | `9500-TRANFILE-CLOSE` | `DailyTransactionReader` | `close()` | VSAM CLOSE TRANFILE → reader close / resource release |
| `CBTRN01C.cbl` | `Z-ABEND-PROGRAM` | `DailyTransactionReader` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBTRN01C.cbl` | `Z-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBTRN02C.cbl` — Daily transaction posting — 4-stage validation cascade, reject codes 100–109

*Primary Java artifact(s):* `com.cardemo.batch.jobs.DailyTransactionPostingJob` · `com.cardemo.batch.processors.TransactionPostingProcessor` · `com.cardemo.batch.writers.TransactionWriter` · `com.cardemo.batch.writers.RejectWriter` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 26

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN02C.cbl` | `0000-DALYTRAN-OPEN` | `TransactionPostingProcessor` | `open()` | VSAM OPEN DALYTRAN → DailyTransactionRepository / ItemStreamReader.open() |
| `CBTRN02C.cbl` | `0100-TRANFILE-OPEN` | `TransactionPostingProcessor` | `open()` | VSAM OPEN TRANFILE → TransactionRepository / ItemStreamReader.open() |
| `CBTRN02C.cbl` | `0200-XREFFILE-OPEN` | `TransactionPostingProcessor` | `open()` | VSAM OPEN XREFFILE → CardCrossReferenceRepository / ItemStreamReader.open() |
| `CBTRN02C.cbl` | `0300-DALYREJS-OPEN` | `TransactionPostingProcessor` | `open()` | VSAM OPEN DALYREJS → DailyTransactionRepository / ItemStreamReader.open() |
| `CBTRN02C.cbl` | `0400-ACCTFILE-OPEN` | `TransactionPostingProcessor` | `open()` | VSAM OPEN ACCTFILE → AccountRepository / ItemStreamReader.open() |
| `CBTRN02C.cbl` | `0500-TCATBALF-OPEN` | `TransactionPostingProcessor` | `open()` | VSAM OPEN TCATBALF → TransactionCategoryBalanceRepository / ItemStreamReader.open() |
| `CBTRN02C.cbl` | `1000-DALYTRAN-GET-NEXT` | `TransactionPostingProcessor` | `read()` | Sequential READ NEXT DALYTRAN → ItemReader.read() (cursor over DailyTransaction) |
| `CBTRN02C.cbl` | `1500-VALIDATE-TRAN` | `TransactionPostingProcessor` | `validate()` | 4-stage validation cascade (reject codes 100-109) → ItemProcessor validation |
| `CBTRN02C.cbl` | `1500-A-LOOKUP-XREF` | `TransactionPostingProcessor` | `lookupCardXref()` | READ CXACAIX → CardCrossReferenceRepository (reject 100 if missing) |
| `CBTRN02C.cbl` | `1500-B-LOOKUP-ACCT` | `TransactionPostingProcessor` | `lookupAccount()` | READ ACCTDAT → AccountRepository (reject 101 if missing) |
| `CBTRN02C.cbl` | `2000-POST-TRANSACTION` | `TransactionPostingProcessor` | `process()` | Post validated transaction → ItemProcessor main step |
| `CBTRN02C.cbl` | `2500-WRITE-REJECT-REC` | `RejectWriter` | `write()` | WRITE DALYREJS reject record → RejectWriter (S3 reject file) |
| `CBTRN02C.cbl` | `2700-UPDATE-TCATBAL` | `TransactionPostingProcessor` | `updateCategoryBalance()` | Update TCATBAL → TransactionCategoryBalanceRepository upsert |
| `CBTRN02C.cbl` | `2700-A-CREATE-TCATBAL-REC` | `TransactionPostingProcessor` | `createCategoryBalance()` | WRITE new TCATBAL → repository.save() (new @EmbeddedId) |
| `CBTRN02C.cbl` | `2700-B-UPDATE-TCATBAL-REC` | `TransactionPostingProcessor` | `incrementCategoryBalance()` | REWRITE TCATBAL → repository.save() (BigDecimal accumulate) |
| `CBTRN02C.cbl` | `2800-UPDATE-ACCOUNT-REC` | `TransactionPostingProcessor` | `updateAccountBalance()` | REWRITE ACCTDAT → AccountRepository.save() (BigDecimal balance) |
| `CBTRN02C.cbl` | `2900-WRITE-TRANSACTION-FILE` | `TransactionWriter` | `write()` | WRITE TRANSACT → TransactionWriter (DB insert + S3 output) |
| `CBTRN02C.cbl` | `9000-DALYTRAN-CLOSE` | `TransactionPostingProcessor` | `close()` | VSAM CLOSE DALYTRAN → reader close / resource release |
| `CBTRN02C.cbl` | `9100-TRANFILE-CLOSE` | `TransactionPostingProcessor` | `close()` | VSAM CLOSE TRANFILE → reader close / resource release |
| `CBTRN02C.cbl` | `9200-XREFFILE-CLOSE` | `TransactionPostingProcessor` | `close()` | VSAM CLOSE XREFFILE → reader close / resource release |
| `CBTRN02C.cbl` | `9300-DALYREJS-CLOSE` | `TransactionPostingProcessor` | `close()` | VSAM CLOSE DALYREJS → reader close / resource release |
| `CBTRN02C.cbl` | `9400-ACCTFILE-CLOSE` | `TransactionPostingProcessor` | `close()` | VSAM CLOSE ACCTFILE → reader close / resource release |
| `CBTRN02C.cbl` | `9500-TCATBALF-CLOSE` | `TransactionPostingProcessor` | `close()` | VSAM CLOSE TCATBALF → reader close / resource release |
| `CBTRN02C.cbl` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `TransactionPostingProcessor` | `formatTimestamp()` | DB2-format timestamp → java.time formatting |
| `CBTRN02C.cbl` | `9999-ABEND-PROGRAM` | `TransactionPostingProcessor` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBTRN02C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBTRN03C.cbl` — Transaction detail report — date-filtered, page/account/grand totals

*Primary Java artifact(s):* `com.cardemo.batch.jobs.TransactionReportJob` · `com.cardemo.batch.processors.TransactionReportProcessor` · `com.cardemo.service.shared.FileStatusMapper` · *Paragraphs:* 26

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN03C.cbl` | `0550-DATEPARM-READ` | `TransactionReportProcessor` | `readDateParameters()` | Read date-range PARM → JobParameters (start/end LocalDate) |
| `CBTRN03C.cbl` | `1000-TRANFILE-GET-NEXT` | `TransactionReportProcessor` | `read()` | Sequential READ NEXT TRANFILE → ItemReader.read() (cursor over Transaction) |
| `CBTRN03C.cbl` | `1100-WRITE-TRANSACTION-REPORT` | `TransactionReportProcessor` | `writeReport()` | Write report body → report ItemWriter (S3 output) |
| `CBTRN03C.cbl` | `1110-WRITE-PAGE-TOTALS` | `TransactionReportProcessor` | `writePageTotals()` | Page totals → BigDecimal subtotal accumulation |
| `CBTRN03C.cbl` | `1120-WRITE-ACCOUNT-TOTALS` | `TransactionReportProcessor` | `writeAccountTotals()` | Per-account totals → BigDecimal group accumulation |
| `CBTRN03C.cbl` | `1110-WRITE-GRAND-TOTALS` | `TransactionReportProcessor` | `writeGrandTotals()` | Grand totals → BigDecimal final accumulation |
| `CBTRN03C.cbl` | `1120-WRITE-HEADERS` | `TransactionReportProcessor` | `writeHeaders()` | Report headers → report layout header lines |
| `CBTRN03C.cbl` | `1111-WRITE-REPORT-REC` | `TransactionReportProcessor` | `writeReportRecord()` | Write a report record → formatted output line |
| `CBTRN03C.cbl` | `1120-WRITE-DETAIL` | `TransactionReportProcessor` | `writeDetailLine()` | Detail line → formatted output line |
| `CBTRN03C.cbl` | `0000-TRANFILE-OPEN` | `TransactionReportProcessor` | `open()` | VSAM OPEN TRANFILE → TransactionRepository / ItemStreamReader.open() |
| `CBTRN03C.cbl` | `0100-REPTFILE-OPEN` | `TransactionReportProcessor` | `open()` | VSAM OPEN REPTFILE → TransactionRepository / ItemStreamReader.open() |
| `CBTRN03C.cbl` | `0200-CARDXREF-OPEN` | `TransactionReportProcessor` | `open()` | VSAM OPEN CARDXREF → CardCrossReferenceRepository / ItemStreamReader.open() |
| `CBTRN03C.cbl` | `0300-TRANTYPE-OPEN` | `TransactionReportProcessor` | `open()` | VSAM OPEN TRANTYPE → TransactionTypeRepository / ItemStreamReader.open() |
| `CBTRN03C.cbl` | `0400-TRANCATG-OPEN` | `TransactionReportProcessor` | `open()` | VSAM OPEN TRANCATG → TransactionCategoryRepository / ItemStreamReader.open() |
| `CBTRN03C.cbl` | `0500-DATEPARM-OPEN` | `TransactionReportProcessor` | `open()` | VSAM OPEN DATEPARM → TransactionRepository / ItemStreamReader.open() |
| `CBTRN03C.cbl` | `1500-A-LOOKUP-XREF` | `TransactionReportProcessor` | `lookupCardXref()` | READ CARDXREF → CardCrossReferenceRepository enrichment |
| `CBTRN03C.cbl` | `1500-B-LOOKUP-TRANTYPE` | `TransactionReportProcessor` | `lookupTransactionType()` | READ TRANTYPE → TransactionTypeRepository enrichment |
| `CBTRN03C.cbl` | `1500-C-LOOKUP-TRANCATG` | `TransactionReportProcessor` | `lookupTransactionCategory()` | READ TRANCATG → TransactionCategoryRepository enrichment |
| `CBTRN03C.cbl` | `9000-TRANFILE-CLOSE` | `TransactionReportProcessor` | `close()` | VSAM CLOSE TRANFILE → reader close / resource release |
| `CBTRN03C.cbl` | `9100-REPTFILE-CLOSE` | `TransactionReportProcessor` | `close()` | VSAM CLOSE REPTFILE → reader close / resource release |
| `CBTRN03C.cbl` | `9200-CARDXREF-CLOSE` | `TransactionReportProcessor` | `close()` | VSAM CLOSE CARDXREF → reader close / resource release |
| `CBTRN03C.cbl` | `9300-TRANTYPE-CLOSE` | `TransactionReportProcessor` | `close()` | VSAM CLOSE TRANTYPE → reader close / resource release |
| `CBTRN03C.cbl` | `9400-TRANCATG-CLOSE` | `TransactionReportProcessor` | `close()` | VSAM CLOSE TRANCATG → reader close / resource release |
| `CBTRN03C.cbl` | `9500-DATEPARM-CLOSE` | `TransactionReportProcessor` | `close()` | VSAM CLOSE DATEPARM → reader close / resource release |
| `CBTRN03C.cbl` | `9999-ABEND-PROGRAM` | `TransactionReportProcessor` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |
| `CBTRN03C.cbl` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | FILE STATUS display → FileStatusMapper (status code → typed exception) |

#### `CBSTM03A.CBL` — Statement generation driver — text + HTML statements

*Primary Java artifact(s):* `com.cardemo.batch.jobs.StatementGenerationJob` · `com.cardemo.batch.processors.StatementProcessor` · `com.cardemo.batch.writers.StatementWriter` · *Paragraphs:* 25

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03A.CBL` | `0000-START` | `StatementProcessor` | `start()` | Mainline entry → Spring Batch step/tasklet entry |
| `CBSTM03A.CBL` | `1000-MAINLINE` | `StatementProcessor` | `process()` | Statement mainline loop → ItemProcessor per account |
| `CBSTM03A.CBL` | `9999-GOBACK` | `StatementProcessor` | `finish()` | GOBACK → step completion / return |
| `CBSTM03A.CBL` | `1000-XREFFILE-GET-NEXT` | `StatementProcessor` | `read()` | Sequential READ NEXT XREFFILE → ItemReader.read() (cursor over CardCrossReference) |
| `CBSTM03A.CBL` | `2000-CUSTFILE-GET` | `StatementProcessor` | `readCustomer()` | VSAM READ CUSTFILE → CustomerRepository lookup |
| `CBSTM03A.CBL` | `3000-ACCTFILE-GET` | `StatementProcessor` | `readAccount()` | VSAM READ ACCTFILE → AccountRepository lookup |
| `CBSTM03A.CBL` | `4000-TRNXFILE-GET` | `StatementProcessor` | `readTransaction()` | VSAM READ TRNXFILE → TransactionRepository lookup |
| `CBSTM03A.CBL` | `5000-CREATE-STATEMENT` | `StatementProcessor` | `createStatement()` | Build statement (text + HTML) → StatementProcessor |
| `CBSTM03A.CBL` | `5100-WRITE-HTML-HEADER` | `StatementProcessor` | `writeHtmlHeader()` | Emit HTML header → StatementWriter (HTML output) |
| `CBSTM03A.CBL` | `5100-EXIT` | `StatementProcessor` | `writeHtmlHeader() (end)` | PERFORM...THRU terminator → marks end of writeHtmlHeader(); no separate Java method |
| `CBSTM03A.CBL` | `5200-WRITE-HTML-NMADBS` | `StatementProcessor` | `writeHtmlNameAddress()` | Emit HTML name/address block → StatementWriter |
| `CBSTM03A.CBL` | `5200-EXIT` | `StatementProcessor` | `writeHtmlNameAddress() (end)` | PERFORM...THRU terminator → marks end of writeHtmlNameAddress(); no separate Java method |
| `CBSTM03A.CBL` | `6000-WRITE-TRANS` | `StatementProcessor` | `writeTransactionLines()` | Emit transaction lines → StatementWriter |
| `CBSTM03A.CBL` | `8100-FILE-OPEN` | `StatementProcessor` | `open()` | VSAM OPEN FILE → reader open |
| `CBSTM03A.CBL` | `8100-TRNXFILE-OPEN` | `StatementProcessor` | `open()` | VSAM OPEN TRNXFILE → TransactionRepository / ItemStreamReader.open() |
| `CBSTM03A.CBL` | `8200-XREFFILE-OPEN` | `StatementProcessor` | `open()` | VSAM OPEN XREFFILE → CardCrossReferenceRepository / ItemStreamReader.open() |
| `CBSTM03A.CBL` | `8300-CUSTFILE-OPEN` | `StatementProcessor` | `open()` | VSAM OPEN CUSTFILE → CustomerRepository / ItemStreamReader.open() |
| `CBSTM03A.CBL` | `8400-ACCTFILE-OPEN` | `StatementProcessor` | `open()` | VSAM OPEN ACCTFILE → AccountRepository / ItemStreamReader.open() |
| `CBSTM03A.CBL` | `8500-READTRNX-READ` | `StatementProcessor` | `readTransaction()` | Random READ of transaction file via CBSTM03B → repository lookup |
| `CBSTM03A.CBL` | `8599-EXIT` | `StatementProcessor` | `readTransaction() (end)` | PERFORM...THRU terminator → marks end of readTransaction(); no separate Java method |
| `CBSTM03A.CBL` | `9100-TRNXFILE-CLOSE` | `StatementProcessor` | `close()` | VSAM CLOSE TRNXFILE → reader close / resource release |
| `CBSTM03A.CBL` | `9200-XREFFILE-CLOSE` | `StatementProcessor` | `close()` | VSAM CLOSE XREFFILE → reader close / resource release |
| `CBSTM03A.CBL` | `9300-CUSTFILE-CLOSE` | `StatementProcessor` | `close()` | VSAM CLOSE CUSTFILE → reader close / resource release |
| `CBSTM03A.CBL` | `9400-ACCTFILE-CLOSE` | `StatementProcessor` | `close()` | VSAM CLOSE ACCTFILE → reader close / resource release |
| `CBSTM03A.CBL` | `9999-ABEND-PROGRAM` | `StatementProcessor` | `abend()` | ABEND → throw CardDemoException (fatal batch error) |

#### `CBSTM03B.CBL` — Statement file-access subroutine (CALLed by CBSTM03A) → consolidated into the statement-generation pipeline

*Primary Java artifact(s):* `com.cardemo.batch.processors.StatementProcessor` · `com.cardemo.batch.readers.CrossReferenceFileReader` · *Paragraphs:* 14

> **Consolidation note:** `CBSTM03B` was a standalone COBOL file-I/O subroutine invoked via `CALL 'CBSTM03B'`. Idiomatic Java has no standalone "file service" equivalent; its per-file access is consolidated into `StatementProcessor` (CUSTFILE/ACCTFILE/TRNXFILE reads via the injected `CustomerRepository`, `AccountRepository`, and `TransactionRepository`) and `CrossReferenceFileReader` (XREFFILE input that drives the processor). Consistent with the §1 column convention, the *Java Class* column below names the **actual** consolidating class for each access path, and the *Java Method* column gives a representative label.

| COBOL Program | COBOL Paragraph | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03B.CBL` | `0000-START` | `StatementProcessor` | `start()` | Mainline entry → Spring Batch step/tasklet entry |
| `CBSTM03B.CBL` | `9999-GOBACK` | `StatementProcessor` | `finish()` | GOBACK → step completion / return |
| `CBSTM03B.CBL` | `1000-TRNXFILE-PROC` | `StatementProcessor` | `accessTransaction()` | CALL 'CBSTM03B' file op on TRNXFILE → injected TransactionRepository access |
| `CBSTM03B.CBL` | `1900-EXIT` | `StatementProcessor` | `accessTransaction() (end)` | PERFORM...THRU terminator → marks end of accessTransaction(); no separate Java method |
| `CBSTM03B.CBL` | `1999-EXIT` | `StatementProcessor` | `accessTransaction() (end)` | PERFORM...THRU terminator → marks end of accessTransaction(); no separate Java method |
| `CBSTM03B.CBL` | `2000-XREFFILE-PROC` | `CrossReferenceFileReader` | `accessCardCrossReference()` | CALL 'CBSTM03B' file op on XREFFILE → CrossReferenceFileReader supplies xref items (CardCrossReferenceRepository) |
| `CBSTM03B.CBL` | `2900-EXIT` | `CrossReferenceFileReader` | `accessCardCrossReference() (end)` | PERFORM...THRU terminator → marks end of accessCardCrossReference(); no separate Java method |
| `CBSTM03B.CBL` | `2999-EXIT` | `CrossReferenceFileReader` | `accessCardCrossReference() (end)` | PERFORM...THRU terminator → marks end of accessCardCrossReference(); no separate Java method |
| `CBSTM03B.CBL` | `3000-CUSTFILE-PROC` | `StatementProcessor` | `accessCustomer()` | CALL 'CBSTM03B' file op on CUSTFILE → injected CustomerRepository access |
| `CBSTM03B.CBL` | `3900-EXIT` | `StatementProcessor` | `accessCustomer() (end)` | PERFORM...THRU terminator → marks end of accessCustomer(); no separate Java method |
| `CBSTM03B.CBL` | `3999-EXIT` | `StatementProcessor` | `accessCustomer() (end)` | PERFORM...THRU terminator → marks end of accessCustomer(); no separate Java method |
| `CBSTM03B.CBL` | `4000-ACCTFILE-PROC` | `StatementProcessor` | `accessAccount()` | CALL 'CBSTM03B' file op on ACCTFILE → injected AccountRepository access |
| `CBSTM03B.CBL` | `4900-EXIT` | `StatementProcessor` | `accessAccount() (end)` | PERFORM...THRU terminator → marks end of accessAccount(); no separate Java method |
| `CBSTM03B.CBL` | `4999-EXIT` | `StatementProcessor` | `accessAccount() (end)` | PERFORM...THRU terminator → marks end of accessAccount(); no separate Java method |

## 5. Reproducibility

The paragraph inventory is mechanically reproducible from the source members at commit `27d6c6f`: each `PROCEDURE DIVISION` is scanned for Area-A labels (fixed-format column 8) ending in a period, tolerating CRLF line endings and skipping comment (`*`/`/`) lines. The extraction yields 528 raw labels; removing the single `COACTVWC` `0000-MAIN-EXIT` duplicate yields the **527** unique rows tabulated above — a one-to-one accounting of every COBOL paragraph against its Java counterpart, with no gaps.

