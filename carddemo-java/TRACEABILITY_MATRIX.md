# CardDemo — Bidirectional COBOL ↔ Java Traceability Matrix

> **Source baseline:** AWS CardDemo `CardDemo_v1.0-15-g27d6c6f-68` — original repository commit SHA **`27d6c6f`**.
> The COBOL / copybook / JCL sources are **not** copied into `carddemo-java/`; this matrix is the
> authoritative link back to the originals (AAP §0.8.1). Every Java target named below corresponds to a
> planned file under `src/main/java/com/carddemo/**`, `src/main/resources/**`, or
> `src/test/java/com/carddemo/**` per AAP §0.4.1 / §0.5.1 — **no feature expansion** (AAP §0.3.2).

## 1. Purpose & Methodology

This document satisfies the **Explainability rule** and **Gate 8** of the validation framework
(AAP §0.7.2, §0.7.3, §0.8.6). It provides a **bidirectional** mapping:

- **Forward (COBOL → Java)** — every paragraph/section of all **28** COBOL programs is mapped to the
  Java class and method that carries its behaviour (§4).
- **Reverse (Java → COBOL)** — every major Java class/method cites the originating COBOL paragraph(s),
  enabling round-trip verification (§7).

**Methodology.** Paragraph/section names were enumerated directly from the `PROCEDURE DIVISION` of each
program under `app/cbl/` (read-only reference). The migration preserves behaviour at the *semantic*
(not syntactic) level using the binding COBOL→Java transformation rules in AAP §0.8.3–§0.8.5:

| COBOL construct | Java realisation |
|---|---|
| `PERFORM A THRU Z` | Ordered sequence of method calls (execution order preserved) |
| Paragraph fall-through | Explicit invocation chains — Java methods do **not** fall through |
| `XXXX-EXIT` paragraph | Structured return point of the mapped method (no separate Java method) |
| `EVALUATE TRUE` / nested `IF` | `switch` expression / chained `if-else` (evaluation order preserved) |
| `READ` / `STARTBR` / `READNEXT` / `READPREV` | Spring Data JPA repository read / `Pageable` cursor |
| `WRITE` / `REWRITE` / `DELETE` | Repository `save()` / `deleteById()` |
| `SYNCPOINT` (+ `ROLLBACK`) | `@Transactional` with rollback-on-exception (AAP §0.8.4) |
| `READ UPDATE` before/after image | JPA `@Version` optimistic locking |
| `FILE STATUS` codes | `FileStatus` enum + `CardDemoException` hierarchy |
| `CALL 'subprogram'` | `@Autowired` Spring bean / method invocation |
| BMS `SEND` / `RECEIVE MAP` | Response / request DTO assembly in a REST controller |
| `WRITEQ TD` (TDQ) | SQS FIFO publish (online-to-batch bridge) |
| JCL `EXEC PGM` / `COND` | Spring Batch job/step + `JobExecutionDecider` |

**Coverage.** 527 paragraphs/sections across 28 programs are individually mapped in §4
(**100%** forward coverage). The reverse index (§7) closes the loop for round-trip verification.

## 2. Representative End-to-End Mappings (AAP §0.7.3)

The three canonical mappings highlighted in the AAP, cross-referenced to the **verified** source
paragraph(s). Where the AAP cites a conceptual label, the verified `app/cbl/` paragraph is also shown
so the mapping is auditable end-to-end (both forms are indexed below and in §4).

| AAP-cited mapping | Verified source paragraph(s) | Java target | Mechanism |
|---|---|---|---|
| `COSGN00C.PROCESS-ENTER-KEY` → `AuthenticationService.authenticate()` | `COSGN00C.PROCESS-ENTER-KEY` (verified — exists) + `READ-USER-SEC-FILE` | `AuthenticationService.authenticate()` | USRSEC lookup → BCrypt verify → JWT issuance |
| `COACTUPC.PROCESS-UPDATE-ACCT` → `AccountUpdateService.updateAccount()` | `COACTUPC.9600-WRITE-PROCESSING` (dual ACCTDAT+CUSTDAT `REWRITE`; `SYNCPOINT ROLLBACK` at L4100) + `2000-DECIDE-ACTION` | `AccountUpdateService.updateAccount()` | `@Transactional` + rollback-on-exception + `@Version` |
| `CBTRN02C.2000-VALIDATE-TXN` → `TransactionPostingProcessor.validate()` | `CBTRN02C.1500-VALIDATE-TRAN` (+ `1500-A-LOOKUP-XREF`, `1500-B-LOOKUP-ACCT`); reject codes 100–109 | `TransactionPostingProcessor.process()` (as-built; the AAP's conceptual `validate()` label) | 4-stage validation cascade → `RejectCode` enum; pure compute, persisted once by `TransactionWriter` (D-018) |

> **Note on labels.** `PROCESS-UPDATE-ACCT` and `2000-VALIDATE-TXN` are the AAP's *conceptual* labels;
> the verified source paragraphs are `9600-WRITE-PROCESSING` and `1500-VALIDATE-TRAN` respectively. Both
> the conceptual labels (this section) and the verified paragraph names (§4) are present so a spot-check
> against either form succeeds.

## 3. Program Inventory (28 programs · 19,254 lines)

Verified line counts (`wc -l`) and migration roles per AAP §0.2.1. Paragraph counts are the de-duplicated `PROCEDURE DIVISION` paragraph/section totals enumerated from each program.

### 3.1 Online CICS programs (17 · 14,693 lines)

| # | Program | Lines | Paragraphs | Migration Role | Primary Java Target(s) |
|---|---|---|---|---|---|
| 1 | `COACTUPC` | 4,236 | 85 | Account update - SYNCPOINT ROLLBACK, optimistic concurrency | `AccountUpdateService` / `AccountController` |
| 2 | `COCRDUPC` | 1,560 | 45 | Card update - optimistic concurrency | `CardUpdateService` / `CardController` |
| 3 | `COCRDLIC` | 1,459 | 39 | Card list - paginated browse (7 rows/page) | `CardListService` / `CardController` |
| 4 | `COACTVWC` | 941 | 34 | Account view - multi-dataset read | `AccountViewService` / `AccountController` |
| 5 | `COCRDSLC` | 887 | 34 | Card detail - single keyed read | `CardDetailService` / `CardController` |
| 6 | `COTRN02C` | 783 | 18 | Transaction add - auto-ID, confirmation flow | `TransactionAddService` / `TransactionController` |
| 7 | `COTRN00C` | 699 | 16 | Transaction list - paginated browse (10 rows/page) | `TransactionListService` / `TransactionController` |
| 8 | `COUSR00C` | 695 | 16 | User list | `UserListService` / `UserAdminController` |
| 9 | `CORPT00C` | 649 | 10 | Report submission - TDQ WRITEQ (online-to-batch bridge) | `ReportSubmissionService` / `ReportController` |
| 10 | `COBIL00C` | 572 | 16 | Bill payment | `BillPaymentService` / `BillingController` |
| 11 | `COUSR02C` | 414 | 11 | User update | `UserUpdateService` / `UserAdminController` |
| 12 | `COUSR03C` | 359 | 11 | User delete | `UserDeleteService` / `UserAdminController` |
| 13 | `COTRN01C` | 330 | 9 | Transaction detail | `TransactionDetailService` / `TransactionController` |
| 14 | `COUSR01C` | 299 | 9 | User add | `UserAddService` / `UserAdminController` |
| 15 | `COMEN01C` | 282 | 7 | Main menu - 10-option routing | `MainMenuService` / `MenuController` |
| 16 | `COADM01C` | 268 | 7 | Admin menu - 4-option routing | `AdminMenuService` / `MenuController` |
| 17 | `COSGN00C` | 260 | 6 | Sign-on / authentication entry point | `AuthenticationService` / `AuthController` |

### 3.2 Batch & utility programs (11 · 4,561 lines)

| # | Program | Lines | Paragraphs | Migration Role | Primary Java Target(s) |
|---|---|---|---|---|---|
| 1 | `CBSTM03A` | 924 | 25 | Batch: statement generation main | `StatementGenerationJob` / `StatementGenerationProcessor` |
| 2 | `CBTRN02C` | 731 | 26 | Batch: daily transaction posting - 4-stage validation cascade | `DailyTransactionPostingJob` / `TransactionPostingProcessor` |
| 3 | `CBACT04C` | 652 | 22 | Batch: interest calculation | `InterestCalculationJob` / `InterestCalculationProcessor` |
| 4 | `CBTRN03C` | 649 | 26 | Batch: transaction report | `TransactionReportJob` / `TransactionReportProcessor` |
| 5 | `CBTRN01C` | 491 | 18 | Batch: daily transaction reader | `DailyTransactionPostingJob` / `DailyTransactionReader` |
| 6 | `CBSTM03B` | 230 | 14 | Batch: statement generation sub (file service) | `StatementGenerationJob` / `StatementGenerationProcessor` |
| 7 | `CBACT01C` | 193 | 6 | Batch: account file reader | `CombineTransactionsJob` / `AccountItemReader` |
| 8 | `CBACT02C` | 178 | 5 | Batch: card file reader | `CombineTransactionsJob` / `CardItemReader` |
| 9 | `CBACT03C` | 178 | 5 | Batch: cross-reference file reader | `CombineTransactionsJob` / `CardCrossReferenceItemReader` |
| 10 | `CBCUS01C` | 178 | 5 | Batch: customer file reader | `CombineTransactionsJob` / `CustomerItemReader` |
| 11 | `CSUTLDTC` | 157 | 2 | Date validation subprogram (CEEDAYS) - called by online + batch | `DateValidationService` |

## 4. Forward Matrix — COBOL → Java (100% paragraph coverage)

Every paragraph/section of every program is listed. `XXXX-EXIT` rows are the structured return points of their base paragraph (AAP §0.8.3); they are retained so the coverage is literally exhaustive (no paragraph omitted).

### 4.1 Online CICS programs

#### `COACTUPC` — Account update - SYNCPOINT ROLLBACK, optimistic concurrency  
_4,236 lines · 85 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTUPC` | `0000-MAIN` | `AccountUpdateService` | `updateAccount()` | Program entry; CICS pseudo-conversational dispatch → AccountController delegates to AccountUpdateService.updateAccount() |
| `COACTUPC` | `COMMON-RETURN` | `AccountUpdateService` | `(common return)` | EXEC CICS RETURN / common exit → HTTP response return (stateless); no fall-through (AAP §0.8.3) |
| `COACTUPC` | `0000-MAIN-EXIT` | `AccountUpdateService` | `updateAccount() [return]` | PERFORM THRU exit target for 0000-MAIN → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1000-PROCESS-INPUTS` | `AccountUpdateService` | `processInputs()` | Input orchestration (receive + edit) → service request handling |
| `COACTUPC` | `1000-PROCESS-INPUTS-EXIT` | `AccountUpdateService` | `processInputs() [return]` | PERFORM THRU exit target for 1000-PROCESS-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1100-RECEIVE-MAP` | `AccountController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COACTUPC` | `1100-RECEIVE-MAP-EXIT` | `AccountController` | `bindRequest() [return]` | PERFORM THRU exit target for 1100-RECEIVE-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1200-EDIT-MAP-INPUTS` | `AccountUpdateService` | `editMapInputs()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1200-EDIT-MAP-INPUTS-EXIT` | `AccountUpdateService` | `editMapInputs() [return]` | PERFORM THRU exit target for 1200-EDIT-MAP-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1205-COMPARE-OLD-NEW` | `AccountUpdateService` | `detectConcurrentChange()` | Before/after record-image compare → JPA @Version optimistic lock; OptimisticLockException (AAP §0.8.4) |
| `COACTUPC` | `1205-COMPARE-OLD-NEW-EXIT` | `AccountUpdateService` | `detectConcurrentChange() [return]` | PERFORM THRU exit target for 1205-COMPARE-OLD-NEW → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1210-EDIT-ACCOUNT` | `AccountUpdateService` | `editAccount()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1210-EDIT-ACCOUNT-EXIT` | `AccountUpdateService` | `editAccount() [return]` | PERFORM THRU exit target for 1210-EDIT-ACCOUNT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1215-EDIT-MANDATORY` | `AccountUpdateService` | `editMandatory()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1215-EDIT-MANDATORY-EXIT` | `AccountUpdateService` | `editMandatory() [return]` | PERFORM THRU exit target for 1215-EDIT-MANDATORY → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1220-EDIT-YESNO` | `AccountUpdateService` | `editYesno()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1220-EDIT-YESNO-EXIT` | `AccountUpdateService` | `editYesno() [return]` | PERFORM THRU exit target for 1220-EDIT-YESNO → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1225-EDIT-ALPHA-REQD` | `AccountUpdateService` | `editAlphaReqd()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1225-EDIT-ALPHA-REQD-EXIT` | `AccountUpdateService` | `editAlphaReqd() [return]` | PERFORM THRU exit target for 1225-EDIT-ALPHA-REQD → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1230-EDIT-ALPHANUM-REQD` | `AccountUpdateService` | `editAlphanumReqd()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1230-EDIT-ALPHANUM-REQD-EXIT` | `AccountUpdateService` | `editAlphanumReqd() [return]` | PERFORM THRU exit target for 1230-EDIT-ALPHANUM-REQD → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1235-EDIT-ALPHA-OPT` | `AccountUpdateService` | `editAlphaOpt()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1235-EDIT-ALPHA-OPT-EXIT` | `AccountUpdateService` | `editAlphaOpt() [return]` | PERFORM THRU exit target for 1235-EDIT-ALPHA-OPT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1240-EDIT-ALPHANUM-OPT` | `AccountUpdateService` | `editAlphanumOpt()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1240-EDIT-ALPHANUM-OPT-EXIT` | `AccountUpdateService` | `editAlphanumOpt() [return]` | PERFORM THRU exit target for 1240-EDIT-ALPHANUM-OPT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1245-EDIT-NUM-REQD` | `AccountUpdateService` | `editNumReqd()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1245-EDIT-NUM-REQD-EXIT` | `AccountUpdateService` | `editNumReqd() [return]` | PERFORM THRU exit target for 1245-EDIT-NUM-REQD → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1250-EDIT-SIGNED-9V2` | `AccountUpdateService` | `editSigned9v2()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTUPC` | `1250-EDIT-SIGNED-9V2-EXIT` | `AccountUpdateService` | `editSigned9v2() [return]` | PERFORM THRU exit target for 1250-EDIT-SIGNED-9V2 → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1260-EDIT-US-PHONE-NUM` | `ValidationLookupService` | `isValidAreaCode()` | US phone/NANPA area-code edit → ValidationLookupService (nanpa-area-codes.json) |
| `COACTUPC` | `EDIT-AREA-CODE` | `ValidationLookupService` | `isValidAreaCode()` | US phone/NANPA area-code edit → ValidationLookupService (nanpa-area-codes.json) |
| `COACTUPC` | `EDIT-US-PHONE-PREFIX` | `ValidationLookupService` | `isValidAreaCode()` | US phone/NANPA area-code edit → ValidationLookupService (nanpa-area-codes.json) |
| `COACTUPC` | `EDIT-US-PHONE-LINENUM` | `ValidationLookupService` | `isValidAreaCode()` | US phone/NANPA area-code edit → ValidationLookupService (nanpa-area-codes.json) |
| `COACTUPC` | `EDIT-US-PHONE-EXIT` | `ValidationLookupService` | `isValidAreaCode() [return]` | PERFORM THRU exit target for EDIT-US-PHONE → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1260-EDIT-US-PHONE-NUM-EXIT` | `ValidationLookupService` | `isValidAreaCode() [return]` | PERFORM THRU exit target for 1260-EDIT-US-PHONE-NUM → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1265-EDIT-US-SSN` | `AccountUpdateService` | `validateSsn()` | US SSN edit → service field validation (@Valid / Bean Validation) |
| `COACTUPC` | `1265-EDIT-US-SSN-EXIT` | `AccountUpdateService` | `validateSsn() [return]` | PERFORM THRU exit target for 1265-EDIT-US-SSN → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1270-EDIT-US-STATE-CD` | `ValidationLookupService` | `isValidStateCode()` | US state-code edit → ValidationLookupService (us-state-codes.json) |
| `COACTUPC` | `1270-EDIT-US-STATE-CD-EXIT` | `ValidationLookupService` | `isValidStateCode() [return]` | PERFORM THRU exit target for 1270-EDIT-US-STATE-CD → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1275-EDIT-FICO-SCORE` | `AccountUpdateService` | `validateFicoScore()` | FICO range edit (300-850) → service field validation |
| `COACTUPC` | `1275-EDIT-FICO-SCORE-EXIT` | `AccountUpdateService` | `validateFicoScore() [return]` | PERFORM THRU exit target for 1275-EDIT-FICO-SCORE → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `1280-EDIT-US-STATE-ZIP-CD` | `ValidationLookupService` | `isValidStateZip()` | State+ZIP prefix edit → ValidationLookupService (state-zip-prefixes.json) |
| `COACTUPC` | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `ValidationLookupService` | `isValidStateZip() [return]` | PERFORM THRU exit target for 1280-EDIT-US-STATE-ZIP-CD → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `2000-DECIDE-ACTION` | `AccountUpdateService` | `decideAction()` | EVALUATE TRUE action dispatch → switch expression (add/update/confirm), AAP §0.8.3 |
| `COACTUPC` | `2000-DECIDE-ACTION-EXIT` | `AccountUpdateService` | `decideAction() [return]` | PERFORM THRU exit target for 2000-DECIDE-ACTION → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3000-SEND-MAP` | `AccountController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COACTUPC` | `3000-SEND-MAP-EXIT` | `AccountController` | `buildResponse() [return]` | PERFORM THRU exit target for 3000-SEND-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3100-SCREEN-INIT` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3100-SCREEN-INIT-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3100-SCREEN-INIT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3200-SETUP-SCREEN-VARS` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3200-SETUP-SCREEN-VARS-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3200-SETUP-SCREEN-VARS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3201-SHOW-INITIAL-VALUES` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3201-SHOW-INITIAL-VALUES-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3201-SHOW-INITIAL-VALUES → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3202-SHOW-ORIGINAL-VALUES` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3202-SHOW-ORIGINAL-VALUES → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3203-SHOW-UPDATED-VALUES` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3203-SHOW-UPDATED-VALUES-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3203-SHOW-UPDATED-VALUES → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3250-SETUP-INFOMSG` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3250-SETUP-INFOMSG-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3250-SETUP-INFOMSG → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3300-SETUP-SCREEN-ATTRS` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3300-SETUP-SCREEN-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3310-PROTECT-ALL-ATTRS` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3310-PROTECT-ALL-ATTRS-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3310-PROTECT-ALL-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3320-UNPROTECT-FEW-ATTRS` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3320-UNPROTECT-FEW-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3390-SETUP-INFOMSG-ATTRS` | `AccountUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTUPC` | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `AccountUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3390-SETUP-INFOMSG-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `3400-SEND-SCREEN` | `AccountController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COACTUPC` | `3400-SEND-SCREEN-EXIT` | `AccountController` | `buildResponse() [return]` | PERFORM THRU exit target for 3400-SEND-SCREEN → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9000-READ-ACCT` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COACTUPC` | `9000-READ-ACCT-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9000-READ-ACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9200-GETCARDXREF-BYACCT` | `CardCrossReferenceRepository` | `findById()` | Keyed read (VSAM READ) → CardCrossReferenceRepository.findById() (Spring Data) |
| `COACTUPC` | `9200-GETCARDXREF-BYACCT-EXIT` | `CardCrossReferenceRepository` | `findById() [return]` | PERFORM THRU exit target for 9200-GETCARDXREF-BYACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9300-GETACCTDATA-BYACCT` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COACTUPC` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9300-GETACCTDATA-BYACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9400-GETCUSTDATA-BYCUST` | `CustomerRepository` | `findById()` | Keyed read (VSAM READ) → CustomerRepository.findById() (Spring Data) |
| `COACTUPC` | `9400-GETCUSTDATA-BYCUST-EXIT` | `CustomerRepository` | `findById() [return]` | PERFORM THRU exit target for 9400-GETCUSTDATA-BYCUST → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9500-STORE-FETCHED-DATA` | `AccountUpdateService` | `captureSnapshot()` | Store fetched record image → entity snapshot for @Version concurrency check |
| `COACTUPC` | `9500-STORE-FETCHED-DATA-EXIT` | `AccountUpdateService` | `captureSnapshot() [return]` | PERFORM THRU exit target for 9500-STORE-FETCHED-DATA → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9600-WRITE-PROCESSING` | `AccountUpdateService` | `updateAccount()` | Dual ACCTDAT+CUSTDAT REWRITE under SYNCPOINT ROLLBACK → @Transactional + rollback-on-exception + @Version (AAP §0.8.4) |
| `COACTUPC` | `9600-WRITE-PROCESSING-EXIT` | `AccountUpdateService` | `updateAccount() [return]` | PERFORM THRU exit target for 9600-WRITE-PROCESSING → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `9700-CHECK-CHANGE-IN-REC` | `AccountUpdateService` | `detectConcurrentChange()` | Before/after record-image compare → JPA @Version optimistic lock; OptimisticLockException (AAP §0.8.4) |
| `COACTUPC` | `9700-CHECK-CHANGE-IN-REC-EXIT` | `AccountUpdateService` | `detectConcurrentChange() [return]` | PERFORM THRU exit target for 9700-CHECK-CHANGE-IN-REC → structured method return (no fall-through), AAP §0.8.3 |
| `COACTUPC` | `ABEND-ROUTINE` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `COACTUPC` | `ABEND-ROUTINE-EXIT` | `CardDemoException` | `throw` | PERFORM THRU exit target for ABEND-ROUTINE → structured method return (no fall-through), AAP §0.8.3 |

#### `COCRDUPC` — Card update - optimistic concurrency  
_1,560 lines · 45 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDUPC` | `0000-MAIN` | `CardUpdateService` | `updateCard()` | Program entry; CICS pseudo-conversational dispatch → CardController delegates to CardUpdateService.updateCard() |
| `COCRDUPC` | `COMMON-RETURN` | `CardUpdateService` | `(common return)` | EXEC CICS RETURN / common exit → HTTP response return (stateless); no fall-through (AAP §0.8.3) |
| `COCRDUPC` | `0000-MAIN-EXIT` | `CardUpdateService` | `updateCard() [return]` | PERFORM THRU exit target for 0000-MAIN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1000-PROCESS-INPUTS` | `CardUpdateService` | `processInputs()` | Input orchestration (receive + edit) → service request handling |
| `COCRDUPC` | `1000-PROCESS-INPUTS-EXIT` | `CardUpdateService` | `processInputs() [return]` | PERFORM THRU exit target for 1000-PROCESS-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1100-RECEIVE-MAP` | `CardController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COCRDUPC` | `1100-RECEIVE-MAP-EXIT` | `CardController` | `bindRequest() [return]` | PERFORM THRU exit target for 1100-RECEIVE-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1200-EDIT-MAP-INPUTS` | `CardUpdateService` | `editMapInputs()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1200-EDIT-MAP-INPUTS-EXIT` | `CardUpdateService` | `editMapInputs() [return]` | PERFORM THRU exit target for 1200-EDIT-MAP-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1210-EDIT-ACCOUNT` | `CardUpdateService` | `editAccount()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1210-EDIT-ACCOUNT-EXIT` | `CardUpdateService` | `editAccount() [return]` | PERFORM THRU exit target for 1210-EDIT-ACCOUNT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1220-EDIT-CARD` | `CardUpdateService` | `editCard()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1220-EDIT-CARD-EXIT` | `CardUpdateService` | `editCard() [return]` | PERFORM THRU exit target for 1220-EDIT-CARD → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1230-EDIT-NAME` | `CardUpdateService` | `editName()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1230-EDIT-NAME-EXIT` | `CardUpdateService` | `editName() [return]` | PERFORM THRU exit target for 1230-EDIT-NAME → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1240-EDIT-CARDSTATUS` | `CardUpdateService` | `editCardstatus()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1240-EDIT-CARDSTATUS-EXIT` | `CardUpdateService` | `editCardstatus() [return]` | PERFORM THRU exit target for 1240-EDIT-CARDSTATUS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1250-EDIT-EXPIRY-MON` | `CardUpdateService` | `editExpiryMon()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1250-EDIT-EXPIRY-MON-EXIT` | `CardUpdateService` | `editExpiryMon() [return]` | PERFORM THRU exit target for 1250-EDIT-EXPIRY-MON → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `1260-EDIT-EXPIRY-YEAR` | `CardUpdateService` | `editExpiryYear()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDUPC` | `1260-EDIT-EXPIRY-YEAR-EXIT` | `CardUpdateService` | `editExpiryYear() [return]` | PERFORM THRU exit target for 1260-EDIT-EXPIRY-YEAR → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `2000-DECIDE-ACTION` | `CardUpdateService` | `decideAction()` | EVALUATE TRUE action dispatch → switch expression (add/update/confirm), AAP §0.8.3 |
| `COCRDUPC` | `2000-DECIDE-ACTION-EXIT` | `CardUpdateService` | `decideAction() [return]` | PERFORM THRU exit target for 2000-DECIDE-ACTION → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `3000-SEND-MAP` | `CardController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COCRDUPC` | `3000-SEND-MAP-EXIT` | `CardController` | `buildResponse() [return]` | PERFORM THRU exit target for 3000-SEND-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `3100-SCREEN-INIT` | `CardUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDUPC` | `3100-SCREEN-INIT-EXIT` | `CardUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3100-SCREEN-INIT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `3200-SETUP-SCREEN-VARS` | `CardUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDUPC` | `3200-SETUP-SCREEN-VARS-EXIT` | `CardUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3200-SETUP-SCREEN-VARS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `3250-SETUP-INFOMSG` | `CardUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDUPC` | `3250-SETUP-INFOMSG-EXIT` | `CardUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3250-SETUP-INFOMSG → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `3300-SETUP-SCREEN-ATTRS` | `CardUpdateService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDUPC` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `CardUpdateService` | `prepareView() [return]` | PERFORM THRU exit target for 3300-SETUP-SCREEN-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `3400-SEND-SCREEN` | `CardController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COCRDUPC` | `3400-SEND-SCREEN-EXIT` | `CardController` | `buildResponse() [return]` | PERFORM THRU exit target for 3400-SEND-SCREEN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `9000-READ-DATA` | `CardRepository` | `findById()` | Keyed read (VSAM READ) → CardRepository.findById() (Spring Data) |
| `COCRDUPC` | `9000-READ-DATA-EXIT` | `CardRepository` | `findById() [return]` | PERFORM THRU exit target for 9000-READ-DATA → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `9100-GETCARD-BYACCTCARD` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COCRDUPC` | `9100-GETCARD-BYACCTCARD-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9100-GETCARD-BYACCTCARD → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `9200-WRITE-PROCESSING` | `CardUpdateService` | `updateCard()` | CARDDAT REWRITE under READ-UPDATE → @Transactional + @Version optimistic update (AAP §0.8.4) |
| `COCRDUPC` | `9200-WRITE-PROCESSING-EXIT` | `CardUpdateService` | `updateCard() [return]` | PERFORM THRU exit target for 9200-WRITE-PROCESSING → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `9300-CHECK-CHANGE-IN-REC` | `CardUpdateService` | `detectConcurrentChange()` | Before/after record-image compare → JPA @Version optimistic lock; OptimisticLockException (AAP §0.8.4) |
| `COCRDUPC` | `9300-CHECK-CHANGE-IN-REC-EXIT` | `CardUpdateService` | `detectConcurrentChange() [return]` | PERFORM THRU exit target for 9300-CHECK-CHANGE-IN-REC → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDUPC` | `ABEND-ROUTINE` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `COCRDUPC` | `ABEND-ROUTINE-EXIT` | `CardDemoException` | `throw` | PERFORM THRU exit target for ABEND-ROUTINE → structured method return (no fall-through), AAP §0.8.3 |

#### `COCRDLIC` — Card list - paginated browse (7 rows/page)  
_1,459 lines · 39 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDLIC` | `0000-MAIN` | `CardListService` | `getCardList()` | Program entry; CICS pseudo-conversational dispatch → CardController delegates to CardListService.getCardList() |
| `COCRDLIC` | `COMMON-RETURN` | `CardListService` | `(common return)` | EXEC CICS RETURN / common exit → HTTP response return (stateless); no fall-through (AAP §0.8.3) |
| `COCRDLIC` | `0000-MAIN-EXIT` | `CardListService` | `getCardList() [return]` | PERFORM THRU exit target for 0000-MAIN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1000-SEND-MAP` | `CardController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COCRDLIC` | `1000-SEND-MAP-EXIT` | `CardController` | `buildResponse() [return]` | PERFORM THRU exit target for 1000-SEND-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1100-SCREEN-INIT` | `CardListService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDLIC` | `1100-SCREEN-INIT-EXIT` | `CardListService` | `prepareView() [return]` | PERFORM THRU exit target for 1100-SCREEN-INIT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1200-SCREEN-ARRAY-INIT` | `CardListService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDLIC` | `1200-SCREEN-ARRAY-INIT-EXIT` | `CardListService` | `prepareView() [return]` | PERFORM THRU exit target for 1200-SCREEN-ARRAY-INIT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1250-SETUP-ARRAY-ATTRIBS` | `CardListService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDLIC` | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `CardListService` | `prepareView() [return]` | PERFORM THRU exit target for 1250-SETUP-ARRAY-ATTRIBS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1300-SETUP-SCREEN-ATTRS` | `CardListService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDLIC` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardListService` | `prepareView() [return]` | PERFORM THRU exit target for 1300-SETUP-SCREEN-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1400-SETUP-MESSAGE` | `CardListService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDLIC` | `1400-SETUP-MESSAGE-EXIT` | `CardListService` | `prepareView() [return]` | PERFORM THRU exit target for 1400-SETUP-MESSAGE → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `1500-SEND-SCREEN` | `CardController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COCRDLIC` | `1500-SEND-SCREEN-EXIT` | `CardController` | `buildResponse() [return]` | PERFORM THRU exit target for 1500-SEND-SCREEN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `2000-RECEIVE-MAP` | `CardController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COCRDLIC` | `2000-RECEIVE-MAP-EXIT` | `CardController` | `bindRequest() [return]` | PERFORM THRU exit target for 2000-RECEIVE-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `2100-RECEIVE-SCREEN` | `CardController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COCRDLIC` | `2100-RECEIVE-SCREEN-EXIT` | `CardController` | `bindRequest() [return]` | PERFORM THRU exit target for 2100-RECEIVE-SCREEN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `2200-EDIT-INPUTS` | `CardListService` | `editInputs()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDLIC` | `2200-EDIT-INPUTS-EXIT` | `CardListService` | `editInputs() [return]` | PERFORM THRU exit target for 2200-EDIT-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `2210-EDIT-ACCOUNT` | `CardListService` | `editAccount()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDLIC` | `2210-EDIT-ACCOUNT-EXIT` | `CardListService` | `editAccount() [return]` | PERFORM THRU exit target for 2210-EDIT-ACCOUNT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `2220-EDIT-CARD` | `CardListService` | `editCard()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDLIC` | `2220-EDIT-CARD-EXIT` | `CardListService` | `editCard() [return]` | PERFORM THRU exit target for 2220-EDIT-CARD → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `2250-EDIT-ARRAY` | `CardListService` | `editArray()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDLIC` | `2250-EDIT-ARRAY-EXIT` | `CardListService` | `editArray() [return]` | PERFORM THRU exit target for 2250-EDIT-ARRAY → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `9000-READ-FORWARD` | `CardListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COCRDLIC` | `9000-READ-FORWARD-EXIT` | `CardListService` | `pageNext() [return]` | PERFORM THRU exit target for 9000-READ-FORWARD → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `9100-READ-BACKWARDS` | `CardListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COCRDLIC` | `9100-READ-BACKWARDS-EXIT` | `CardListService` | `pagePrevious() [return]` | PERFORM THRU exit target for 9100-READ-BACKWARDS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `9500-FILTER-RECORDS` | `CardListService` | `applyFilter()` | Browse filter predicate → repository query / Stream filter |
| `COCRDLIC` | `9500-FILTER-RECORDS-EXIT` | `CardListService` | `applyFilter() [return]` | PERFORM THRU exit target for 9500-FILTER-RECORDS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `SEND-PLAIN-TEXT` | `CardController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COCRDLIC` | `SEND-PLAIN-TEXT-EXIT` | `CardController` | `sendText() [return]` | PERFORM THRU exit target for SEND-PLAIN-TEXT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDLIC` | `SEND-LONG-TEXT` | `CardController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COCRDLIC` | `SEND-LONG-TEXT-EXIT` | `CardController` | `sendText() [return]` | PERFORM THRU exit target for SEND-LONG-TEXT → structured method return (no fall-through), AAP §0.8.3 |

#### `COACTVWC` — Account view - multi-dataset read  
_941 lines · 34 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COACTVWC` | `0000-MAIN` | `AccountViewService` | `getAccountView()` | Program entry; CICS pseudo-conversational dispatch → AccountController delegates to AccountViewService.getAccountView() |
| `COACTVWC` | `COMMON-RETURN` | `AccountViewService` | `(common return)` | EXEC CICS RETURN / common exit → HTTP response return (stateless); no fall-through (AAP §0.8.3) |
| `COACTVWC` | `0000-MAIN-EXIT` | `AccountViewService` | `getAccountView() [return]` | PERFORM THRU exit target for 0000-MAIN → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `1000-SEND-MAP` | `AccountController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COACTVWC` | `1000-SEND-MAP-EXIT` | `AccountController` | `buildResponse() [return]` | PERFORM THRU exit target for 1000-SEND-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `1100-SCREEN-INIT` | `AccountViewService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTVWC` | `1100-SCREEN-INIT-EXIT` | `AccountViewService` | `prepareView() [return]` | PERFORM THRU exit target for 1100-SCREEN-INIT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `1200-SETUP-SCREEN-VARS` | `AccountViewService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTVWC` | `1200-SETUP-SCREEN-VARS-EXIT` | `AccountViewService` | `prepareView() [return]` | PERFORM THRU exit target for 1200-SETUP-SCREEN-VARS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `1300-SETUP-SCREEN-ATTRS` | `AccountViewService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COACTVWC` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `AccountViewService` | `prepareView() [return]` | PERFORM THRU exit target for 1300-SETUP-SCREEN-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `1400-SEND-SCREEN` | `AccountController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COACTVWC` | `1400-SEND-SCREEN-EXIT` | `AccountController` | `buildResponse() [return]` | PERFORM THRU exit target for 1400-SEND-SCREEN → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `2000-PROCESS-INPUTS` | `AccountViewService` | `processInputs()` | Input orchestration (receive + edit) → service request handling |
| `COACTVWC` | `2000-PROCESS-INPUTS-EXIT` | `AccountViewService` | `processInputs() [return]` | PERFORM THRU exit target for 2000-PROCESS-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `2100-RECEIVE-MAP` | `AccountController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COACTVWC` | `2100-RECEIVE-MAP-EXIT` | `AccountController` | `bindRequest() [return]` | PERFORM THRU exit target for 2100-RECEIVE-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `2200-EDIT-MAP-INPUTS` | `AccountViewService` | `editMapInputs()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTVWC` | `2200-EDIT-MAP-INPUTS-EXIT` | `AccountViewService` | `editMapInputs() [return]` | PERFORM THRU exit target for 2200-EDIT-MAP-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `2210-EDIT-ACCOUNT` | `AccountViewService` | `editAccount()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COACTVWC` | `2210-EDIT-ACCOUNT-EXIT` | `AccountViewService` | `editAccount() [return]` | PERFORM THRU exit target for 2210-EDIT-ACCOUNT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `9000-READ-ACCT` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COACTVWC` | `9000-READ-ACCT-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9000-READ-ACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `9200-GETCARDXREF-BYACCT` | `CardCrossReferenceRepository` | `findById()` | Keyed read (VSAM READ) → CardCrossReferenceRepository.findById() (Spring Data) |
| `COACTVWC` | `9200-GETCARDXREF-BYACCT-EXIT` | `CardCrossReferenceRepository` | `findById() [return]` | PERFORM THRU exit target for 9200-GETCARDXREF-BYACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `9300-GETACCTDATA-BYACCT` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COACTVWC` | `9300-GETACCTDATA-BYACCT-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9300-GETACCTDATA-BYACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `9400-GETCUSTDATA-BYCUST` | `CustomerRepository` | `findById()` | Keyed read (VSAM READ) → CustomerRepository.findById() (Spring Data) |
| `COACTVWC` | `9400-GETCUSTDATA-BYCUST-EXIT` | `CustomerRepository` | `findById() [return]` | PERFORM THRU exit target for 9400-GETCUSTDATA-BYCUST → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `SEND-PLAIN-TEXT` | `AccountController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COACTVWC` | `SEND-PLAIN-TEXT-EXIT` | `AccountController` | `sendText() [return]` | PERFORM THRU exit target for SEND-PLAIN-TEXT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `SEND-LONG-TEXT` | `AccountController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COACTVWC` | `SEND-LONG-TEXT-EXIT` | `AccountController` | `sendText() [return]` | PERFORM THRU exit target for SEND-LONG-TEXT → structured method return (no fall-through), AAP §0.8.3 |
| `COACTVWC` | `ABEND-ROUTINE` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |

#### `COCRDSLC` — Card detail - single keyed read  
_887 lines · 34 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COCRDSLC` | `0000-MAIN` | `CardDetailService` | `getCardDetail()` | Program entry; CICS pseudo-conversational dispatch → CardController delegates to CardDetailService.getCardDetail() |
| `COCRDSLC` | `COMMON-RETURN` | `CardDetailService` | `(common return)` | EXEC CICS RETURN / common exit → HTTP response return (stateless); no fall-through (AAP §0.8.3) |
| `COCRDSLC` | `0000-MAIN-EXIT` | `CardDetailService` | `getCardDetail() [return]` | PERFORM THRU exit target for 0000-MAIN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `1000-SEND-MAP` | `CardController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COCRDSLC` | `1000-SEND-MAP-EXIT` | `CardController` | `buildResponse() [return]` | PERFORM THRU exit target for 1000-SEND-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `1100-SCREEN-INIT` | `CardDetailService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDSLC` | `1100-SCREEN-INIT-EXIT` | `CardDetailService` | `prepareView() [return]` | PERFORM THRU exit target for 1100-SCREEN-INIT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `1200-SETUP-SCREEN-VARS` | `CardDetailService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDSLC` | `1200-SETUP-SCREEN-VARS-EXIT` | `CardDetailService` | `prepareView() [return]` | PERFORM THRU exit target for 1200-SETUP-SCREEN-VARS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `1300-SETUP-SCREEN-ATTRS` | `CardDetailService` | `prepareView()` | Screen field/attribute init (BMS attrs) → response DTO field defaults + display flags |
| `COCRDSLC` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `CardDetailService` | `prepareView() [return]` | PERFORM THRU exit target for 1300-SETUP-SCREEN-ATTRS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `1400-SEND-SCREEN` | `CardController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COCRDSLC` | `1400-SEND-SCREEN-EXIT` | `CardController` | `buildResponse() [return]` | PERFORM THRU exit target for 1400-SEND-SCREEN → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `2000-PROCESS-INPUTS` | `CardDetailService` | `processInputs()` | Input orchestration (receive + edit) → service request handling |
| `COCRDSLC` | `2000-PROCESS-INPUTS-EXIT` | `CardDetailService` | `processInputs() [return]` | PERFORM THRU exit target for 2000-PROCESS-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `2100-RECEIVE-MAP` | `CardController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COCRDSLC` | `2100-RECEIVE-MAP-EXIT` | `CardController` | `bindRequest() [return]` | PERFORM THRU exit target for 2100-RECEIVE-MAP → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `2200-EDIT-MAP-INPUTS` | `CardDetailService` | `editMapInputs()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDSLC` | `2200-EDIT-MAP-INPUTS-EXIT` | `CardDetailService` | `editMapInputs() [return]` | PERFORM THRU exit target for 2200-EDIT-MAP-INPUTS → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `2210-EDIT-ACCOUNT` | `CardDetailService` | `editAccount()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDSLC` | `2210-EDIT-ACCOUNT-EXIT` | `CardDetailService` | `editAccount() [return]` | PERFORM THRU exit target for 2210-EDIT-ACCOUNT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `2220-EDIT-CARD` | `CardDetailService` | `editCard()` | Field edit/validation → @Valid + service validation; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COCRDSLC` | `2220-EDIT-CARD-EXIT` | `CardDetailService` | `editCard() [return]` | PERFORM THRU exit target for 2220-EDIT-CARD → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `9000-READ-DATA` | `CardRepository` | `findById()` | Keyed read (VSAM READ) → CardRepository.findById() (Spring Data) |
| `COCRDSLC` | `9000-READ-DATA-EXIT` | `CardRepository` | `findById() [return]` | PERFORM THRU exit target for 9000-READ-DATA → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `9100-GETCARD-BYACCTCARD` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COCRDSLC` | `9100-GETCARD-BYACCTCARD-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9100-GETCARD-BYACCTCARD → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `9150-GETCARD-BYACCT` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COCRDSLC` | `9150-GETCARD-BYACCT-EXIT` | `AccountRepository` | `findById() [return]` | PERFORM THRU exit target for 9150-GETCARD-BYACCT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `SEND-LONG-TEXT` | `CardController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COCRDSLC` | `SEND-LONG-TEXT-EXIT` | `CardController` | `sendText() [return]` | PERFORM THRU exit target for SEND-LONG-TEXT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `SEND-PLAIN-TEXT` | `CardController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COCRDSLC` | `SEND-PLAIN-TEXT-EXIT` | `CardController` | `sendText() [return]` | PERFORM THRU exit target for SEND-PLAIN-TEXT → structured method return (no fall-through), AAP §0.8.3 |
| `COCRDSLC` | `ABEND-ROUTINE` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |

#### `COTRN02C` — Transaction add - auto-ID, confirmation flow  
_783 lines · 18 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN02C` | `MAIN-PARA` | `TransactionAddService` | `addTransaction()` | Program entry; CICS pseudo-conversational dispatch → TransactionController delegates to TransactionAddService.addTransaction() |
| `COTRN02C` | `PROCESS-ENTER-KEY` | `TransactionAddService` | `addTransaction()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COTRN02C` | `VALIDATE-INPUT-KEY-FIELDS` | `TransactionAddService` | `validateAndResolveKeyFields()` | Key-field edit + cross-reference resolution, run **before** data-field validation; `EVALUATE TRUE` account-id-first precedence; resolves account→card (`findByXrefAcctId`) or card→account (`findById`) and persists the resolved pairing; not-found → `RecordNotFoundException` (DECISION_LOG **D-020**) |
| `COTRN02C` | `VALIDATE-INPUT-DATA-FIELDS` | `TransactionAddService` | `validateDataFields()` | Field edit/validation → @Valid + service validation, run after key resolution; EVALUATE/IF order preserved (AAP §0.8.3) |
| `COTRN02C` | `ADD-TRANSACTION` | `TransactionAddService` | `addTransaction()` | Add transaction with auto-generated ID → Factory ID sequence (browse-to-end + increment, AAP §0.4.3) |
| `COTRN02C` | `COPY-LAST-TRAN-DATA` | `TransactionAddService` | `copyLastTransaction()` | Pre-fill from last transaction → service helper |
| `COTRN02C` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COTRN02C` | `SEND-TRNADD-SCREEN` | `TransactionController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COTRN02C` | `RECEIVE-TRNADD-SCREEN` | `TransactionController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COTRN02C` | `POPULATE-HEADER-INFO` | `TransactionController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COTRN02C` | `READ-CXACAIX-FILE` | `CardCrossReferenceRepository` | `findByXrefAcctId()` | Account-keyed read via the `CXACAIX` alternate index → CardCrossReferenceRepository.findByXrefAcctId() (account→card resolution, D-020) |
| `COTRN02C` | `READ-CCXREF-FILE` | `CardCrossReferenceRepository` | `findById()` | Card-keyed read (VSAM READ) → CardCrossReferenceRepository.findById() (card→account resolution, D-020) |
| `COTRN02C` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | `openCursor()` | STARTBR → open keyed cursor / Pageable start (Spring Data) |
| `COTRN02C` | `READPREV-TRANSACT-FILE` | `TransactionAddService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COTRN02C` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | `closeCursor()` | ENDBR → release cursor (no-op under Spring Data pagination) |
| `COTRN02C` | `WRITE-TRANSACT-FILE` | `TransactionRepository` | `save()` | Persist the new transaction → TransactionAddService.addTransaction() persists the **resolved** card/account pairing via TransactionRepository.save() (D-020) |
| `COTRN02C` | `CLEAR-CURRENT-SCREEN` | `TransactionAddService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |
| `COTRN02C` | `INITIALIZE-ALL-FIELDS` | `TransactionAddService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COTRN00C` — Transaction list - paginated browse (10 rows/page)  
_699 lines · 16 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN00C` | `MAIN-PARA` | `TransactionListService` | `listTransactions()` | Program entry; CICS pseudo-conversational dispatch → TransactionController delegates to TransactionListService.listTransactions() |
| `COTRN00C` | `PROCESS-ENTER-KEY` | `TransactionListService` | `listTransactions()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COTRN00C` | `PROCESS-PF7-KEY` | `TransactionListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COTRN00C` | `PROCESS-PF8-KEY` | `TransactionListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COTRN00C` | `PROCESS-PAGE-FORWARD` | `TransactionListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COTRN00C` | `PROCESS-PAGE-BACKWARD` | `TransactionListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COTRN00C` | `POPULATE-TRAN-DATA` | `TransactionListService` | `mapPageRows()` | Populate browse rows → map entity page to response DTO list |
| `COTRN00C` | `INITIALIZE-TRAN-DATA` | `TransactionListService` | `clearPageRows()` | Clear browse rows → empty response DTO list |
| `COTRN00C` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COTRN00C` | `SEND-TRNLST-SCREEN` | `TransactionController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COTRN00C` | `RECEIVE-TRNLST-SCREEN` | `TransactionController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COTRN00C` | `POPULATE-HEADER-INFO` | `TransactionController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COTRN00C` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | `openCursor()` | STARTBR → open keyed cursor / Pageable start (Spring Data) |
| `COTRN00C` | `READNEXT-TRANSACT-FILE` | `TransactionListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COTRN00C` | `READPREV-TRANSACT-FILE` | `TransactionListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COTRN00C` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | `closeCursor()` | ENDBR → release cursor (no-op under Spring Data pagination) |

#### `COUSR00C` — User list  
_695 lines · 16 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR00C` | `MAIN-PARA` | `UserListService` | `listUsers()` | Program entry; CICS pseudo-conversational dispatch → UserAdminController delegates to UserListService.listUsers() |
| `COUSR00C` | `PROCESS-ENTER-KEY` | `UserListService` | `listUsers()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COUSR00C` | `PROCESS-PF7-KEY` | `UserListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COUSR00C` | `PROCESS-PF8-KEY` | `UserListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COUSR00C` | `PROCESS-PAGE-FORWARD` | `UserListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COUSR00C` | `PROCESS-PAGE-BACKWARD` | `UserListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COUSR00C` | `POPULATE-USER-DATA` | `UserListService` | `mapPageRows()` | Populate browse rows → map entity page to response DTO list |
| `COUSR00C` | `INITIALIZE-USER-DATA` | `UserListService` | `clearPageRows()` | Clear browse rows → empty response DTO list |
| `COUSR00C` | `RETURN-TO-PREV-SCREEN` | `UserAdminController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COUSR00C` | `SEND-USRLST-SCREEN` | `UserAdminController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COUSR00C` | `RECEIVE-USRLST-SCREEN` | `UserAdminController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COUSR00C` | `POPULATE-HEADER-INFO` | `UserAdminController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COUSR00C` | `STARTBR-USER-SEC-FILE` | `UserSecurityRepository` | `openCursor()` | STARTBR → open keyed cursor / Pageable start (Spring Data) |
| `COUSR00C` | `READNEXT-USER-SEC-FILE` | `UserListService` | `pageNext()` | Forward browse (PF8/READNEXT) → Pageable next page / forward cursor |
| `COUSR00C` | `READPREV-USER-SEC-FILE` | `UserListService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COUSR00C` | `ENDBR-USER-SEC-FILE` | `UserSecurityRepository` | `closeCursor()` | ENDBR → release cursor (no-op under Spring Data pagination) |

#### `CORPT00C` — Report submission - TDQ WRITEQ (online-to-batch bridge)  
_649 lines · 10 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CORPT00C` | `MAIN-PARA` | `ReportSubmissionService` | `submitReport()` | Program entry; CICS pseudo-conversational dispatch → ReportController delegates to ReportSubmissionService.submitReport() |
| `CORPT00C` | `PROCESS-ENTER-KEY` | `ReportSubmissionService` | `submitReport()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `CORPT00C` | `SUBMIT-JOB-TO-INTRDR` | `ReportSubmissionService` | `submitReport()` | WRITEQ TD / INTRDR job submit → SQS FIFO publish triggering Spring Batch (Observer, AAP §0.4.3) |
| `CORPT00C` | `WIRTE-JOBSUB-TDQ` | `ReportSubmissionService` | `submitReport()` | WRITEQ TD / INTRDR job submit → SQS FIFO publish triggering Spring Batch (Observer, AAP §0.4.3) |
| `CORPT00C` | `RETURN-TO-PREV-SCREEN` | `ReportController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `CORPT00C` | `SEND-TRNRPT-SCREEN` | `ReportController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `CORPT00C` | `RETURN-TO-CICS` | `ReportSubmissionService` | `(return)` | RETURN TO CICS → controller returns ResponseEntity; stateless |
| `CORPT00C` | `RECEIVE-TRNRPT-SCREEN` | `ReportController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `CORPT00C` | `POPULATE-HEADER-INFO` | `ReportController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `CORPT00C` | `INITIALIZE-ALL-FIELDS` | `ReportSubmissionService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COBIL00C` — Bill payment  
_572 lines · 16 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COBIL00C` | `MAIN-PARA` | `BillPaymentService` | `pay()` | Program entry; CICS pseudo-conversational dispatch → BillingController delegates to BillPaymentService.pay() |
| `COBIL00C` | `PROCESS-ENTER-KEY` | `BillPaymentService` | `pay()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COBIL00C` | `GET-CURRENT-TIMESTAMP` | `BillPaymentService` | `currentTimestamp()` | Timestamp fetch → java.time.LocalDateTime / Instant |
| `COBIL00C` | `RETURN-TO-PREV-SCREEN` | `BillingController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COBIL00C` | `SEND-BILLPAY-SCREEN` | `BillingController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COBIL00C` | `RECEIVE-BILLPAY-SCREEN` | `BillingController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COBIL00C` | `POPULATE-HEADER-INFO` | `BillingController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COBIL00C` | `READ-ACCTDAT-FILE` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `COBIL00C` | `UPDATE-ACCTDAT-FILE` | `AccountRepository` | `save()` | REWRITE record → AccountRepository.save() (VSAM REWRITE → JPA update) |
| `COBIL00C` | `READ-CXACAIX-FILE` | `CardCrossReferenceRepository` | `findById()` | Keyed read (VSAM READ) → CardCrossReferenceRepository.findById() (Spring Data) |
| `COBIL00C` | `STARTBR-TRANSACT-FILE` | `TransactionRepository` | `openCursor()` | STARTBR → open keyed cursor / Pageable start (Spring Data) |
| `COBIL00C` | `READPREV-TRANSACT-FILE` | `BillPaymentService` | `pagePrevious()` | Backward browse (PF7/READPREV) → Pageable previous page / reverse cursor |
| `COBIL00C` | `ENDBR-TRANSACT-FILE` | `TransactionRepository` | `closeCursor()` | ENDBR → release cursor (no-op under Spring Data pagination) |
| `COBIL00C` | `WRITE-TRANSACT-FILE` | `StatementWriter` | `write()` | Statement text/HTML emit → StatementWriter (S3 objects), Template Method (AAP §0.4.3) |
| `COBIL00C` | `CLEAR-CURRENT-SCREEN` | `BillPaymentService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |
| `COBIL00C` | `INITIALIZE-ALL-FIELDS` | `BillPaymentService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COUSR02C` — User update  
_414 lines · 11 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR02C` | `MAIN-PARA` | `UserUpdateService` | `updateUser()` | Program entry; CICS pseudo-conversational dispatch → UserAdminController delegates to UserUpdateService.updateUser() |
| `COUSR02C` | `PROCESS-ENTER-KEY` | `UserUpdateService` | `updateUser()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COUSR02C` | `UPDATE-USER-INFO` | `UserSecurityRepository` | `save()` | REWRITE record → UserSecurityRepository.save() (VSAM REWRITE → JPA update) |
| `COUSR02C` | `RETURN-TO-PREV-SCREEN` | `UserAdminController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COUSR02C` | `SEND-USRUPD-SCREEN` | `UserAdminController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COUSR02C` | `RECEIVE-USRUPD-SCREEN` | `UserAdminController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COUSR02C` | `POPULATE-HEADER-INFO` | `UserAdminController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COUSR02C` | `READ-USER-SEC-FILE` | `UserSecurityRepository` | `findById()` | Keyed read (VSAM READ) → UserSecurityRepository.findById() (Spring Data) |
| `COUSR02C` | `UPDATE-USER-SEC-FILE` | `UserSecurityRepository` | `save()` | REWRITE record → UserSecurityRepository.save() (VSAM REWRITE → JPA update) |
| `COUSR02C` | `CLEAR-CURRENT-SCREEN` | `UserUpdateService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |
| `COUSR02C` | `INITIALIZE-ALL-FIELDS` | `UserUpdateService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COUSR03C` — User delete  
_359 lines · 11 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR03C` | `MAIN-PARA` | `UserDeleteService` | `deleteUser()` | Program entry; CICS pseudo-conversational dispatch → UserAdminController delegates to UserDeleteService.deleteUser() |
| `COUSR03C` | `PROCESS-ENTER-KEY` | `UserDeleteService` | `deleteUser()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COUSR03C` | `DELETE-USER-INFO` | `UserSecurityRepository` | `deleteById()` | DELETE record → UserSecurityRepository.deleteById() (VSAM DELETE) |
| `COUSR03C` | `RETURN-TO-PREV-SCREEN` | `UserAdminController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COUSR03C` | `SEND-USRDEL-SCREEN` | `UserAdminController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COUSR03C` | `RECEIVE-USRDEL-SCREEN` | `UserAdminController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COUSR03C` | `POPULATE-HEADER-INFO` | `UserAdminController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COUSR03C` | `READ-USER-SEC-FILE` | `UserSecurityRepository` | `findById()` | Keyed read (VSAM READ) → UserSecurityRepository.findById() (Spring Data) |
| `COUSR03C` | `DELETE-USER-SEC-FILE` | `UserSecurityRepository` | `deleteById()` | DELETE record → UserSecurityRepository.deleteById() (VSAM DELETE) |
| `COUSR03C` | `CLEAR-CURRENT-SCREEN` | `UserDeleteService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |
| `COUSR03C` | `INITIALIZE-ALL-FIELDS` | `UserDeleteService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COTRN01C` — Transaction detail  
_330 lines · 9 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COTRN01C` | `MAIN-PARA` | `TransactionDetailService` | `getTransaction()` | Program entry; CICS pseudo-conversational dispatch → TransactionController delegates to TransactionDetailService.getTransaction() |
| `COTRN01C` | `PROCESS-ENTER-KEY` | `TransactionDetailService` | `getTransaction()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COTRN01C` | `RETURN-TO-PREV-SCREEN` | `TransactionController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COTRN01C` | `SEND-TRNVIEW-SCREEN` | `TransactionController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COTRN01C` | `RECEIVE-TRNVIEW-SCREEN` | `TransactionController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COTRN01C` | `POPULATE-HEADER-INFO` | `TransactionController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COTRN01C` | `READ-TRANSACT-FILE` | `TransactionRepository` | `findById()` | Keyed read (VSAM READ) → TransactionRepository.findById() (Spring Data) |
| `COTRN01C` | `CLEAR-CURRENT-SCREEN` | `TransactionDetailService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |
| `COTRN01C` | `INITIALIZE-ALL-FIELDS` | `TransactionDetailService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COUSR01C` — User add  
_299 lines · 9 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COUSR01C` | `MAIN-PARA` | `UserAddService` | `addUser()` | Program entry; CICS pseudo-conversational dispatch → UserAdminController delegates to UserAddService.addUser() |
| `COUSR01C` | `PROCESS-ENTER-KEY` | `UserAddService` | `addUser()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COUSR01C` | `RETURN-TO-PREV-SCREEN` | `UserAdminController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COUSR01C` | `SEND-USRADD-SCREEN` | `UserAdminController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COUSR01C` | `RECEIVE-USRADD-SCREEN` | `UserAdminController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COUSR01C` | `POPULATE-HEADER-INFO` | `UserAdminController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COUSR01C` | `WRITE-USER-SEC-FILE` | `UserSecurityRepository` | `save()` | WRITE record → UserSecurityRepository.save() (VSAM WRITE → JPA insert) |
| `COUSR01C` | `CLEAR-CURRENT-SCREEN` | `UserAddService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |
| `COUSR01C` | `INITIALIZE-ALL-FIELDS` | `UserAddService` | `resetState()` | INITIALIZE / clear screen → reset request/response DTO to defaults (stateless) |

#### `COMEN01C` — Main menu - 10-option routing  
_282 lines · 7 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COMEN01C` | `MAIN-PARA` | `MainMenuService` | `getMenuOptions()` | Program entry; CICS pseudo-conversational dispatch → MenuController delegates to MainMenuService.getMenuOptions() |
| `COMEN01C` | `PROCESS-ENTER-KEY` | `MainMenuService` | `getMenuOptions()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COMEN01C` | `RETURN-TO-SIGNON-SCREEN` | `MenuController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COMEN01C` | `SEND-MENU-SCREEN` | `MenuController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COMEN01C` | `RECEIVE-MENU-SCREEN` | `MenuController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COMEN01C` | `POPULATE-HEADER-INFO` | `MenuController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COMEN01C` | `BUILD-MENU-OPTIONS` | `MainMenuService` | `getMenuOptions()` | Build menu option table (COMEN02Y/COADM02Y) → List<MenuOption> DTO |

#### `COADM01C` — Admin menu - 4-option routing  
_268 lines · 7 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COADM01C` | `MAIN-PARA` | `AdminMenuService` | `getMenuOptions()` | Program entry; CICS pseudo-conversational dispatch → MenuController delegates to AdminMenuService.getMenuOptions() |
| `COADM01C` | `PROCESS-ENTER-KEY` | `AdminMenuService` | `getMenuOptions()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COADM01C` | `RETURN-TO-SIGNON-SCREEN` | `MenuController` | `navigate()` | CICS XCTL/RETURN screen navigation → REST routing (caller selects next endpoint); stateless |
| `COADM01C` | `SEND-MENU-SCREEN` | `MenuController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COADM01C` | `RECEIVE-MENU-SCREEN` | `MenuController` | `bindRequest()` | RECEIVE MAP (BMS 3270 input) → request DTO binding (@RequestBody / @Valid) |
| `COADM01C` | `POPULATE-HEADER-INFO` | `MenuController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COADM01C` | `BUILD-MENU-OPTIONS` | `AdminMenuService` | `getMenuOptions()` | Build menu option table (COMEN02Y/COADM02Y) → List<MenuOption> DTO |

#### `COSGN00C` — Sign-on / authentication entry point  
_260 lines · 6 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `COSGN00C` | `MAIN-PARA` | `AuthenticationService` | `authenticate()` | Program entry; CICS pseudo-conversational dispatch → AuthController delegates to AuthenticationService.authenticate() |
| `COSGN00C` | `PROCESS-ENTER-KEY` | `AuthenticationService` | `authenticate()` | ENTER-key business action → primary service method (PF-key/AID routing → REST endpoint) |
| `COSGN00C` | `SEND-SIGNON-SCREEN` | `AuthController` | `buildResponse()` | SEND MAP (BMS 3270 output) → response DTO assembly returned by controller (DTO pattern) |
| `COSGN00C` | `SEND-PLAIN-TEXT` | `AuthController` | `sendText()` | SEND TEXT (terminal message) → plain response body / error-message DTO |
| `COSGN00C` | `POPULATE-HEADER-INFO` | `AuthController` | `populateHeader()` | Standard screen header (title/date/time) → common response header DTO (COTTL01Y/CSDAT01Y) |
| `COSGN00C` | `READ-USER-SEC-FILE` | `UserSecurityRepository` | `findById()` | Keyed read (VSAM READ) → UserSecurityRepository.findById() (Spring Data) |

### 4.2 Batch & utility programs

#### `CBSTM03A` — Batch: statement generation main  
_924 lines · 25 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03A` | `0000-START` | `StatementGenerationProcessor` | `generateStatements()` | Batch driver entry → Spring Batch chunk-oriented step (reader/processor/writer) |
| `CBSTM03A` | `1000-MAINLINE` | `StatementGenerationProcessor` | `generateStatements()` | Batch driver entry → Spring Batch chunk-oriented step (reader/processor/writer) |
| `CBSTM03A` | `9999-GOBACK` | `StatementGenerationProcessor` | `(return)` | GOBACK → batch step/chunk completion (ExitStatus) |
| `CBSTM03A` | `1000-XREFFILE-GET-NEXT` | `CardCrossReferenceRepository` | `streamAll()` | Sequential GET-NEXT → CardCrossReferenceRepository paged/stream read |
| `CBSTM03A` | `2000-CUSTFILE-GET` | `CustomerRepository` | `findById()` | Keyed read (VSAM READ) → CustomerRepository.findById() (Spring Data) |
| `CBSTM03A` | `3000-ACCTFILE-GET` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `CBSTM03A` | `4000-TRNXFILE-GET` | `TransactionRepository` | `findById()` | Keyed read (VSAM READ) → TransactionRepository.findById() (Spring Data) |
| `CBSTM03A` | `5000-CREATE-STATEMENT` | `StatementWriter` | `write()` | Statement text/HTML emit → StatementWriter (S3 objects), Template Method (AAP §0.4.3) |
| `CBSTM03A` | `5100-WRITE-HTML-HEADER` | `StatementWriter` | `write()` | Statement text/HTML emit → StatementWriter (S3 objects), Template Method (AAP §0.4.3) |
| `CBSTM03A` | `5100-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03A` | `5200-WRITE-HTML-NMADBS` | `StatementWriter` | `write()` | Statement text/HTML emit → StatementWriter (S3 objects), Template Method (AAP §0.4.3) |
| `CBSTM03A` | `5200-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03A` | `6000-WRITE-TRANS` | `StatementWriter` | `write()` | Statement text/HTML emit → StatementWriter (S3 objects), Template Method (AAP §0.4.3) |
| `CBSTM03A` | `8100-FILE-OPEN` | `StatementGenerationJob` | `openStep()` | OPEN (8100-FILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBSTM03A` | `8100-TRNXFILE-OPEN` | `StatementGenerationJob` | `openStep()` | OPEN (8100-TRNXFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBSTM03A` | `8200-XREFFILE-OPEN` | `StatementGenerationJob` | `openStep()` | OPEN (8200-XREFFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBSTM03A` | `8300-CUSTFILE-OPEN` | `StatementGenerationJob` | `openStep()` | OPEN (8300-CUSTFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CustomerRepository |
| `CBSTM03A` | `8400-ACCTFILE-OPEN` | `StatementGenerationJob` | `openStep()` | OPEN (8400-ACCTFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via AccountRepository |
| `CBSTM03A` | `8500-READTRNX-READ` | `TransactionRepository` | `findById()` | Keyed read (VSAM READ) → TransactionRepository.findById() (Spring Data) |
| `CBSTM03A` | `8599-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03A` | `9100-TRNXFILE-CLOSE` | `StatementGenerationJob` | `closeStep()` | CLOSE (9100-TRNXFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBSTM03A` | `9200-XREFFILE-CLOSE` | `StatementGenerationJob` | `closeStep()` | CLOSE (9200-XREFFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBSTM03A` | `9300-CUSTFILE-CLOSE` | `StatementGenerationJob` | `closeStep()` | CLOSE (9300-CUSTFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CustomerRepository |
| `CBSTM03A` | `9400-ACCTFILE-CLOSE` | `StatementGenerationJob` | `closeStep()` | CLOSE (9400-ACCTFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via AccountRepository |
| `CBSTM03A` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |

#### `CBTRN02C` — Batch: daily transaction posting - 4-stage validation cascade  
_731 lines · 26 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN02C` | `0000-DALYTRAN-OPEN` | `DailyTransactionReader` | `open()` | OPEN daily-transaction input → ItemReader ItemStream lifecycle |
| `CBTRN02C` | `0100-TRANFILE-OPEN` | `DailyTransactionPostingJob` | `openStep()` | OPEN (0100-TRANFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBTRN02C` | `0200-XREFFILE-OPEN` | `DailyTransactionPostingJob` | `openStep()` | OPEN (0200-XREFFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBTRN02C` | `0300-DALYREJS-OPEN` | `RejectWriter` | `open()` | OPEN reject dataset → RejectWriter ItemStream (S3 rejection file) |
| `CBTRN02C` | `0400-ACCTFILE-OPEN` | `DailyTransactionPostingJob` | `openStep()` | OPEN (0400-ACCTFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via AccountRepository |
| `CBTRN02C` | `0500-TCATBALF-OPEN` | `DailyTransactionPostingJob` | `openStep()` | OPEN (0500-TCATBALF-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionCategoryBalanceRepository |
| `CBTRN02C` | `1000-DALYTRAN-GET-NEXT` | `DailyTransactionReader` | `read()` | Daily-transaction sequential read → DailyTransactionReader.read() |
| `CBTRN02C` | `1500-VALIDATE-TRAN` | `TransactionPostingProcessor` | `process()` | 4-stage validation cascade in the pure processor; reject codes `{100,101,102,103,109}` → RejectCode enum; `103` overwrites `102` when both fail (Strategy, AAP §0.4.3; DECISION_LOG **D-016**) |
| `CBTRN02C` | `1500-A-LOOKUP-XREF` | `CardCrossReferenceRepository` | `findById()` | Keyed read (VSAM READ) → CardCrossReferenceRepository.findById() (Spring Data) |
| `CBTRN02C` | `1500-B-LOOKUP-ACCT` | `AccountRepository` | `findById()` | Keyed read (VSAM READ) → AccountRepository.findById() (Spring Data) |
| `CBTRN02C` | `2000-POST-TRANSACTION` | `TransactionPostingProcessor` / `TransactionWriter` | `process()` / `post()` | Posted state (transaction + balances) is **computed** by the pure processor `process()` and **persisted exactly once** by `TransactionWriter.post()` — no re-read/recompute (single posting contract, DECISION_LOG **D-018**) |
| `CBTRN02C` | `2500-WRITE-REJECT-REC` | `RejectWriter` | `write()` | Write rejected record → RejectWriter (S3 rejection file), reject codes 100-109 |
| `CBTRN02C` | `2700-UPDATE-TCATBAL` | `TransactionCategoryBalanceRepository` | `save()` | Create/update category balance → TransactionCategoryBalanceRepository.save() (composite key), invoked once by `TransactionWriter.post()` (D-018) |
| `CBTRN02C` | `2700-A-CREATE-TCATBAL-REC` | `TransactionCategoryBalanceRepository` | `save()` | Create/update category balance → TransactionCategoryBalanceRepository.save() (composite key) |
| `CBTRN02C` | `2700-B-UPDATE-TCATBAL-REC` | `TransactionCategoryBalanceRepository` | `save()` | Create/update category balance → TransactionCategoryBalanceRepository.save() (composite key) |
| `CBTRN02C` | `2800-UPDATE-ACCOUNT-REC` | `AccountRepository` | `saveAndFlush()` | Update account balance → AccountRepository.saveAndFlush() once via `TransactionWriter.post()`; optimistic-lock conflict → canonical concurrency message (REWRITE invalid-key path = reject `109`; D-018) |
| `CBTRN02C` | `2900-WRITE-TRANSACTION-FILE` | `TransactionWriter` | `post()` | Persist the posted transaction record → TransactionWriter.post() saves the precomputed `Transaction` exactly once (optional byte-exact 350-byte S3 staging) (D-018) |
| `CBTRN02C` | `9000-DALYTRAN-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE daily-transaction input → ItemReader ItemStream lifecycle |
| `CBTRN02C` | `9100-TRANFILE-CLOSE` | `DailyTransactionPostingJob` | `closeStep()` | CLOSE (9100-TRANFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBTRN02C` | `9200-XREFFILE-CLOSE` | `DailyTransactionPostingJob` | `closeStep()` | CLOSE (9200-XREFFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBTRN02C` | `9300-DALYREJS-CLOSE` | `RejectWriter` | `close()` | CLOSE reject dataset → RejectWriter ItemStream (S3 rejection file) |
| `CBTRN02C` | `9400-ACCTFILE-CLOSE` | `DailyTransactionPostingJob` | `closeStep()` | CLOSE (9400-ACCTFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via AccountRepository |
| `CBTRN02C` | `9500-TCATBALF-CLOSE` | `DailyTransactionPostingJob` | `closeStep()` | CLOSE (9500-TCATBALF-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionCategoryBalanceRepository |
| `CBTRN02C` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `TransactionPostingProcessor` | `currentTimestamp()` | Timestamp fetch → java.time.LocalDateTime / Instant |
| `CBTRN02C` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBTRN02C` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBACT04C` — Batch: interest calculation  
_652 lines · 22 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT04C` | `0000-TCATBALF-OPEN` | `InterestCalculationJob` | `openStep()` | OPEN (0000-TCATBALF-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionCategoryBalanceRepository |
| `CBACT04C` | `0100-XREFFILE-OPEN` | `InterestCalculationJob` | `openStep()` | OPEN (0100-XREFFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBACT04C` | `0200-DISCGRP-OPEN` | `InterestCalculationJob` | `openStep()` | OPEN (0200-DISCGRP-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via DisclosureGroupRepository |
| `CBACT04C` | `0300-ACCTFILE-OPEN` | `InterestCalculationJob` | `openStep()` | OPEN (0300-ACCTFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via AccountRepository |
| `CBACT04C` | `0400-TRANFILE-OPEN` | `InterestCalculationJob` | `openStep()` | OPEN (0400-TRANFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBACT04C` | `1000-TCATBALF-GET-NEXT` | `TransactionCategoryBalanceRepository` | `streamAll()` | Sequential GET-NEXT → TransactionCategoryBalanceRepository paged/stream read |
| `CBACT04C` | `1050-UPDATE-ACCOUNT` | `AccountRepository` | `save()` | Apply computed interest to account → AccountRepository.save() |
| `CBACT04C` | `1100-GET-ACCT-DATA` | `AccountRepository` | `findById()` | Account/XREF keyed lookup → repository read |
| `CBACT04C` | `1110-GET-XREF-DATA` | `CardCrossReferenceRepository` | `findById()` | Account/XREF keyed lookup → repository read |
| `CBACT04C` | `1200-GET-INTEREST-RATE` | `DisclosureGroupRepository` | `findById()` | Disclosure-group rate lookup with DEFAULT fallback → DisclosureGroupRepository (composite key) |
| `CBACT04C` | `1200-A-GET-DEFAULT-INT-RATE` | `DisclosureGroupRepository` | `findById()` | Disclosure-group rate lookup with DEFAULT fallback → DisclosureGroupRepository (composite key) |
| `CBACT04C` | `1300-COMPUTE-INTEREST` | `InterestCalculationProcessor` | `process()` | COMPUTE WS-MONTHLY-INT=(TRAN-CAT-BAL*DIS-INT-RATE)/1200 → BigDecimal.divide(HALF_EVEN) (AAP §0.8.2) |
| `CBACT04C` | `1300-B-WRITE-TX` | `TransactionWriter` | `write()` | Write interest transaction → TransactionWriter |
| `CBACT04C` | `1400-COMPUTE-FEES` | `InterestCalculationProcessor` | `computeFees()` | Fee computation → BigDecimal arithmetic (scale preserved) |
| `CBACT04C` | `9000-TCATBALF-CLOSE` | `InterestCalculationJob` | `closeStep()` | CLOSE (9000-TCATBALF-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionCategoryBalanceRepository |
| `CBACT04C` | `9100-XREFFILE-CLOSE` | `InterestCalculationJob` | `closeStep()` | CLOSE (9100-XREFFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBACT04C` | `9200-DISCGRP-CLOSE` | `InterestCalculationJob` | `closeStep()` | CLOSE (9200-DISCGRP-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via DisclosureGroupRepository |
| `CBACT04C` | `9300-ACCTFILE-CLOSE` | `InterestCalculationJob` | `closeStep()` | CLOSE (9300-ACCTFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via AccountRepository |
| `CBACT04C` | `9400-TRANFILE-CLOSE` | `InterestCalculationJob` | `closeStep()` | CLOSE (9400-TRANFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBACT04C` | `Z-GET-DB2-FORMAT-TIMESTAMP` | `InterestCalculationProcessor` | `currentTimestamp()` | Timestamp fetch → java.time.LocalDateTime / Instant |
| `CBACT04C` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBACT04C` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBTRN03C` — Batch: transaction report  
_649 lines · 26 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN03C` | `0550-DATEPARM-READ` | `TransactionReportProcessor` | `readDateParams()` | Date-range parameter file → Spring Batch JobParameters (report date filter) |
| `CBTRN03C` | `1000-TRANFILE-GET-NEXT` | `TransactionRepository` | `streamAll()` | Sequential GET-NEXT → TransactionRepository paged/stream read |
| `CBTRN03C` | `1100-WRITE-TRANSACTION-REPORT` | `StatementWriter` | `write()` | Statement text/HTML emit → StatementWriter (S3 objects), Template Method (AAP §0.4.3) |
| `CBTRN03C` | `1110-WRITE-PAGE-TOTALS` | `TransactionReportProcessor` | `writeReportLine()` | Report detail/total/header emit → TransactionReportProcessor → S3 report object |
| `CBTRN03C` | `1120-WRITE-ACCOUNT-TOTALS` | `TransactionReportProcessor` | `writeReportLine()` | Report detail/total/header emit → TransactionReportProcessor → S3 report object |
| `CBTRN03C` | `1110-WRITE-GRAND-TOTALS` | `TransactionReportProcessor` | `writeReportLine()` | Report detail/total/header emit → TransactionReportProcessor → S3 report object |
| `CBTRN03C` | `1120-WRITE-HEADERS` | `TransactionReportProcessor` | `writeReportLine()` | Report detail/total/header emit → TransactionReportProcessor → S3 report object |
| `CBTRN03C` | `1111-WRITE-REPORT-REC` | `TransactionReportProcessor` | `writeReportLine()` | Report detail/total/header emit → TransactionReportProcessor → S3 report object |
| `CBTRN03C` | `1120-WRITE-DETAIL` | `TransactionReportProcessor` | `writeReportLine()` | Report detail/total/header emit → TransactionReportProcessor → S3 report object |
| `CBTRN03C` | `0000-TRANFILE-OPEN` | `TransactionReportJob` | `openStep()` | OPEN (0000-TRANFILE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBTRN03C` | `0100-REPTFILE-OPEN` | `TransactionReportProcessor` | `openOutput()` | OPEN report output → S3 report object (TransactionReportJob) |
| `CBTRN03C` | `0200-CARDXREF-OPEN` | `TransactionReportJob` | `openStep()` | OPEN (0200-CARDXREF-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBTRN03C` | `0300-TRANTYPE-OPEN` | `TransactionReportJob` | `openStep()` | OPEN (0300-TRANTYPE-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionTypeRepository |
| `CBTRN03C` | `0400-TRANCATG-OPEN` | `TransactionReportJob` | `openStep()` | OPEN (0400-TRANCATG-OPEN) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionCategoryRepository |
| `CBTRN03C` | `0500-DATEPARM-OPEN` | `TransactionReportProcessor` | `readDateParams()` | Date-range parameter file → Spring Batch JobParameters (report date filter) |
| `CBTRN03C` | `1500-A-LOOKUP-XREF` | `CardCrossReferenceRepository` / `TransactionReportProcessor` | `findById()` / `lookupAccountId()` | Keyed read → CardCrossReferenceRepository.findById(); `INVALID KEY` → `9999-ABEND-PROGRAM` reproduced as a **fatal** `RecordNotFoundException` (FILE STATUS `23`) with the card number **masked to last 4** (DECISION_LOG **D-019**) |
| `CBTRN03C` | `1500-B-LOOKUP-TRANTYPE` | `TransactionTypeRepository` / `TransactionReportProcessor` | `findById()` / `lookupTypeDescription()` | Keyed read → TransactionTypeRepository.findById(); `INVALID KEY` → fatal `RecordNotFoundException` (type code shown verbatim, non-sensitive) (D-019) |
| `CBTRN03C` | `1500-C-LOOKUP-TRANCATG` | `TransactionCategoryRepository` / `TransactionReportProcessor` | `findById()` / `lookupCategoryDescription()` | Keyed read → TransactionCategoryRepository.findById(); `INVALID KEY` → fatal `RecordNotFoundException` (type/category codes shown verbatim) (D-019) |
| `CBTRN03C` | `9000-TRANFILE-CLOSE` | `TransactionReportJob` | `closeStep()` | CLOSE (9000-TRANFILE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionRepository |
| `CBTRN03C` | `9100-REPTFILE-CLOSE` | `TransactionReportProcessor` | `closeOutput()` | CLOSE report output → S3 report object (TransactionReportJob) |
| `CBTRN03C` | `9200-CARDXREF-CLOSE` | `TransactionReportJob` | `closeStep()` | CLOSE (9200-CARDXREF-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via CardCrossReferenceRepository |
| `CBTRN03C` | `9300-TRANTYPE-CLOSE` | `TransactionReportJob` | `closeStep()` | CLOSE (9300-TRANTYPE-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionTypeRepository |
| `CBTRN03C` | `9400-TRANCATG-CLOSE` | `TransactionReportJob` | `closeStep()` | CLOSE (9400-TRANCATG-CLOSE) → Spring Batch step ItemStream lifecycle (BatchConfig); dataset via TransactionCategoryRepository |
| `CBTRN03C` | `9500-DATEPARM-CLOSE` | `TransactionReportProcessor` | `readDateParams()` | Date-range parameter file → Spring Batch JobParameters (report date filter) |
| `CBTRN03C` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBTRN03C` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBTRN01C` — Batch: daily transaction reader  
_491 lines · 18 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBTRN01C` | `MAIN-PARA` | `DailyTransactionReader` | `read()` | Batch driver entry → Spring Batch ItemReader stream open + read loop |
| `CBTRN01C` | `1000-DALYTRAN-GET-NEXT` | `DailyTransactionReader` | `read()` | Sequential GET-NEXT → ItemReader.read() backed by DailyTransactionRepository |
| `CBTRN01C` | `2000-LOOKUP-XREF` | `CardCrossReferenceRepository` | `findById()` | Keyed read (VSAM READ) → CardCrossReferenceRepository.findById() (Spring Data) |
| `CBTRN01C` | `3000-READ-ACCOUNT` | `DailyTransactionRepository` | `findById()` | Keyed read (VSAM READ) → DailyTransactionRepository.findById() (Spring Data) |
| `CBTRN01C` | `0000-DALYTRAN-OPEN` | `DailyTransactionReader` | `open()` | OPEN daily-transaction input → ItemReader ItemStream lifecycle |
| `CBTRN01C` | `0100-CUSTFILE-OPEN` | `DailyTransactionReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via CustomerRepository |
| `CBTRN01C` | `0200-XREFFILE-OPEN` | `DailyTransactionReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via CardCrossReferenceRepository |
| `CBTRN01C` | `0300-CARDFILE-OPEN` | `DailyTransactionReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via CardRepository |
| `CBTRN01C` | `0400-ACCTFILE-OPEN` | `DailyTransactionReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via AccountRepository |
| `CBTRN01C` | `0500-TRANFILE-OPEN` | `DailyTransactionReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via TransactionRepository |
| `CBTRN01C` | `9000-DALYTRAN-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE daily-transaction input → ItemReader ItemStream lifecycle |
| `CBTRN01C` | `9100-CUSTFILE-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via CustomerRepository |
| `CBTRN01C` | `9200-XREFFILE-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via CardCrossReferenceRepository |
| `CBTRN01C` | `9300-CARDFILE-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via CardRepository |
| `CBTRN01C` | `9400-ACCTFILE-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via AccountRepository |
| `CBTRN01C` | `9500-TRANFILE-CLOSE` | `DailyTransactionReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via TransactionRepository |
| `CBTRN01C` | `Z-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBTRN01C` | `Z-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBSTM03B` — Batch: statement generation sub (file service)  
_230 lines · 14 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBSTM03B` | `0000-START` | `StatementGenerationProcessor` | `readDomainData()` | Batch driver entry → Spring Batch chunk-oriented step (reader/processor/writer) |
| `CBSTM03B` | `9999-GOBACK` | `StatementGenerationProcessor` | `(return)` | GOBACK → batch step/chunk completion (ExitStatus) |
| `CBSTM03B` | `1000-TRNXFILE-PROC` | `TransactionRepository` | `find()` | CBSTM03B file-service (open/read/close by function) → TransactionRepository access via StatementGenerationProcessor |
| `CBSTM03B` | `1900-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `1999-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `2000-XREFFILE-PROC` | `CardCrossReferenceRepository` | `find()` | CBSTM03B file-service (open/read/close by function) → CardCrossReferenceRepository access via StatementGenerationProcessor |
| `CBSTM03B` | `2900-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `2999-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `3000-CUSTFILE-PROC` | `CustomerRepository` | `find()` | CBSTM03B file-service (open/read/close by function) → CustomerRepository access via StatementGenerationProcessor |
| `CBSTM03B` | `3900-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `3999-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `4000-ACCTFILE-PROC` | `AccountRepository` | `find()` | CBSTM03B file-service (open/read/close by function) → AccountRepository access via StatementGenerationProcessor |
| `CBSTM03B` | `4900-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |
| `CBSTM03B` | `4999-EXIT` | `StatementGenerationProcessor` | `(return)` | PERFORM THRU exit / paragraph return point (no fall-through), AAP §0.8.3 |

#### `CBACT01C` — Batch: account file reader  
_193 lines · 6 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT01C` | `1000-ACCTFILE-GET-NEXT` | `AccountItemReader` | `read()` | Sequential GET-NEXT → ItemReader.read() backed by AccountRepository |
| `CBACT01C` | `1100-DISPLAY-ACCT-RECORD` | `AccountItemReader` | `displayAcctRecord()` | Business helper → method on the mapped component (control flow preserved, AAP §0.8.3) |
| `CBACT01C` | `0000-ACCTFILE-OPEN` | `AccountItemReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via AccountRepository |
| `CBACT01C` | `9000-ACCTFILE-CLOSE` | `AccountItemReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via AccountRepository |
| `CBACT01C` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBACT01C` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBACT02C` — Batch: card file reader  
_178 lines · 5 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT02C` | `1000-CARDFILE-GET-NEXT` | `CardItemReader` | `read()` | Sequential GET-NEXT → ItemReader.read() backed by CardRepository |
| `CBACT02C` | `0000-CARDFILE-OPEN` | `CardItemReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via CardRepository |
| `CBACT02C` | `9000-CARDFILE-CLOSE` | `CardItemReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via CardRepository |
| `CBACT02C` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBACT02C` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBACT03C` — Batch: cross-reference file reader  
_178 lines · 5 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBACT03C` | `1000-XREFFILE-GET-NEXT` | `CardCrossReferenceItemReader` | `read()` | Sequential GET-NEXT → ItemReader.read() backed by CardCrossReferenceRepository |
| `CBACT03C` | `0000-XREFFILE-OPEN` | `CardCrossReferenceItemReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via CardCrossReferenceRepository |
| `CBACT03C` | `9000-XREFFILE-CLOSE` | `CardCrossReferenceItemReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via CardCrossReferenceRepository |
| `CBACT03C` | `9999-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBACT03C` | `9910-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CBCUS01C` — Batch: customer file reader  
_178 lines · 5 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CBCUS01C` | `1000-CUSTFILE-GET-NEXT` | `CustomerItemReader` | `read()` | Sequential GET-NEXT → ItemReader.read() backed by CustomerRepository |
| `CBCUS01C` | `0000-CUSTFILE-OPEN` | `CustomerItemReader` | `open()` | OPEN file → ItemReader.open() (Spring Batch ItemStream); dataset via CustomerRepository |
| `CBCUS01C` | `9000-CUSTFILE-CLOSE` | `CustomerItemReader` | `close()` | CLOSE file → ItemReader.close() (Spring Batch ItemStream); dataset via CustomerRepository |
| `CBCUS01C` | `Z-ABEND-PROGRAM` | `CardDemoException` | `throw` | ABEND routine → throw CardDemoException (HTTP 5xx / batch ExitStatus.FAILED) |
| `CBCUS01C` | `Z-DISPLAY-IO-STATUS` | `FileStatusMapper` | `toException()` | DISPLAY-IO-STATUS → FileStatusMapper maps FILE STATUS code to exception/log (AAP §0.8.4) |

#### `CSUTLDTC` — Date validation subprogram (CEEDAYS) - called by online + batch  
_157 lines · 2 paragraphs/sections · 100% mapped_

| COBOL Program | Paragraph/Section | Java Class | Java Method | Notes |
|---|---|---|---|---|
| `CSUTLDTC` | `A000-MAIN` | `DateValidationService` | `validateDate()` | Batch / subprogram entry point |
| `CSUTLDTC` | `A000-MAIN-EXIT` | `DateValidationService` | `validateDate() [return]` | PERFORM THRU exit target for A000-MAIN → structured method return (no fall-through), AAP §0.8.3 |

## 5. Copybook → Java Type Matrix (28 copybooks)

Record layouts, the COMMAREA, lookup tables and helper structures from `app/cpy/` mapped to JPA entities, DTOs, enums and composite-key classes. Field-level PIC / COMP-3 semantics follow AAP §0.8.2 (`BigDecimal`, scale preserved).

| Copybook | Bytes / Purpose | Java Type | Java Package | Notes |
|---|---|---|---|---|
| `CVACT01Y.cpy` | Account record (300B); 5× S9(10)V99 | `Account` | `model.entity` | BigDecimal balances (scale 2) + `@Version` optimistic lock |
| `CVACT02Y.cpy` | Card record (150B) | `Card` | `model.entity` | FK to Account; `@Version` |
| `CVACT03Y.cpy` | Card cross-reference (50B) | `CardCrossReference` | `model.entity` | Card↔Account↔Customer linkage (← CXACAIX AIX) |
| `CVCUS01Y.cpy` | Customer record (500B) | `Customer` | `model.entity` | SSN handling; primary customer layout |
| `CUSTREC.cpy` | Alternative customer record | `Customer` | `model.entity` | Alternate layout folded into `Customer` |
| `CVTRA01Y.cpy` | Transaction category balance (composite key) | `TransactionCategoryBalance` | `model.entity` | `@EmbeddedId TransactionCategoryBalanceId` |
| `CVTRA02Y.cpy` | Disclosure group (composite key, interest rate) | `DisclosureGroup` | `model.entity` | `@EmbeddedId DisclosureGroupId` + BigDecimal rate |
| `CVTRA03Y.cpy` | Transaction type (60B) | `TransactionType` | `model.entity` | Reference lookup table |
| `CVTRA04Y.cpy` | Transaction category (composite key) | `TransactionCategory` | `model.entity` | `@EmbeddedId TransactionCategoryId` |
| `CVTRA05Y.cpy` | Transaction record (350B) | `Transaction` | `model.entity` | BigDecimal `TRAN-AMT` (scale 2) |
| `CVTRA06Y.cpy` | Daily transaction staging (350B) | `DailyTransaction` | `model.entity` | Staging entity for the posting job |
| `CVTRA07Y.cpy` | Transaction report record | `TransactionReportProcessor` | `batch.processors` | Report-line layout → processor output rows (no persisted entity) |
| `CVCRD01Y.cpy` | Card record descriptor | `Card` | `model.entity` | Descriptor folded into `Card` entity |
| `COCOM01Y.cpy` | `CARDDEMO-COMMAREA` central session state | `CommArea` | `model.dto` | Stateless request/response DTO + JWT claims (AAP §0.1.1) |
| `CSUSR01Y.cpy` | User security record (80B); `SEC-USR-PWD X(08)` plaintext | `UserSecurity` | `model.entity` | Plaintext password → **BCrypt** (constraint C-003, AAP §0.8.1) |
| `COMEN02Y.cpy` | Main menu option table (10 entries) | `MenuOption` | `model.dto` | Consumed by `MainMenuService.getMenuOptions()` |
| `COADM02Y.cpy` | Admin menu option table (4 entries) | `MenuOption` | `model.dto` | Consumed by `AdminMenuService.getMenuOptions()` |
| `CSLKPCDY.cpy` | Validation lookup: NANPA area codes, state codes, ZIP prefixes | `ValidationLookupService` | `service.shared` | Backed by `validation/*.json` reference data |
| `CSUTLDPY.cpy` | Date validation parameters | `DateValidationService` | `service.shared` | Parameter structure for date validation |
| `CSUTLDWY.cpy` | Date validation work area | `DateValidationService` | `service.shared` | Work area → method-local state |
| `CSDAT01Y.cpy` | Date structure definitions | `CommArea` | `model.dto` | Header date/time fields → response header DTO (java.time) |
| `CSMSG01Y.cpy` | Standard message / abend structures | `CardDemoException` | `exception` | Message/abend structures → exception hierarchy + error DTO |
| `CSMSG02Y.cpy` | Additional message structures | `CardDemoException` | `exception` | Additional message structures → error DTO fields |
| `CSSTRPFY.cpy` | String / PF-key processing helpers | `WebConfig` | `config` | PF-key/AID handling → REST endpoint routing (no direct equivalent) |
| `CSSETATY.cpy` | Screen attribute setting helpers | `WebConfig` | `config` | BMS attribute helpers → `@Valid` constraints / serialization |
| `COTTL01Y.cpy` | Report title line definitions | `CommArea` | `model.dto` | Title lines → response header DTO / report headers |
| `COSTM01.CPY` | Statement output format | `StatementWriter` | `batch.writers` | Statement layout → text/HTML S3 output (Template Method) |
| `UNUSED1Y.cpy` | Unused copybook (carried for completeness) | `— (none)` | `—` | Not referenced by any program; no Java target (documented for completeness) |

**Enums (← COBOL 88-levels / status codes):** `UserType` (← `CSUSR01Y` / `COCOM01Y` `CDEMO-USRTYP-ADMIN`/`-USER` = `A`/`U`), `FileStatus` (← `CBTRN02C` FILE STATUS clauses), `RejectCode` (← `CBTRN02C` reject codes 100–109), `TransactionSource` (← `dailytran.txt` source classification).

**Composite-key classes (`model.key`):** `TransactionCategoryBalanceId` (← `CVTRA01Y`), `DisclosureGroupId` (← `CVTRA02Y`), `TransactionCategoryId` (← `CVTRA04Y`).

## 6. JCL → Spring Batch / Schema Matrix (29 jobs)

Job streams from `app/jcl/` mapped to Spring Batch jobs, Flyway migrations, or repository / configuration artifacts. Condition-code logic → `ExitStatus` + `JobExecutionDecider` (AAP §0.8.5).

| JCL Job | Type | Java / Resource Target | Notes |
|---|---|---|---|
| `POSTTRAN.jcl` | Batch business | `DailyTransactionPostingJob` | ← CBTRN02C; 4-stage validation + condition codes |
| `INTCALC.jcl` | Batch business | `InterestCalculationJob` | ← CBACT04C; PARM mapping + interest formula |
| `COMBTRAN.jcl` | Batch business | `CombineTransactionsJob` / `CombineTransactionsProcessor` | DFSORT + IDCAMS REPRO → `Comparator` + bulk JPA insert (AAP §0.8.5) |
| `CREASTMT.JCL` | Batch business | `StatementGenerationJob` | ← CBSTM03A/B; text + HTML to S3 |
| `TRANREPT.jcl` | Batch business | `TransactionReportJob` | ← CBTRN03C; date-filtered report to S3 |
| `ACCTFILE.jcl` | VSAM define | `V1__create_schema.sql` + `AccountRepository` | DEFINE CLUSTER ACCTDAT → `account` table |
| `CARDFILE.jcl` | VSAM define | `V1__create_schema.sql` + `CardRepository` | DEFINE CLUSTER CARDDAT → `card` table |
| `CUSTFILE.jcl` | VSAM define | `V1__create_schema.sql` + `CustomerRepository` | DEFINE CLUSTER CUSTDAT → `customer` table |
| `XREFFILE.jcl` | VSAM define | `V1`/`V2` + `CardCrossReferenceRepository` | DEFINE CLUSTER + CXACAIX AIX → `card_xref` + alt index |
| `TRANFILE.jcl` | VSAM define | `V1`/`V2` + `TransactionRepository` | DEFINE CLUSTER TRANSACT + AIX → `transaction` + index |
| `DUSRSECJ.jcl` | VSAM define | `V1__create_schema.sql` + `UserSecurityRepository` | DEFINE CLUSTER USRSEC → `user_security` table |
| `TCATBALF.jcl` | VSAM define | `V1` + `TransactionCategoryBalanceRepository` | DEFINE CLUSTER TCATBAL → `tran_cat_balance` (composite key) |
| `DISCGRP.jcl` | VSAM define | `V1` + `DisclosureGroupRepository` | DEFINE CLUSTER DISCGRP → `disclosure_group` (composite key) |
| `TRANCATG.jcl` | VSAM define | `V1` + `TransactionCategoryRepository` | DEFINE CLUSTER TRANCATG → `tran_category` (composite key) |
| `TRANTYPE.jcl` | VSAM define | `V1` + `TransactionTypeRepository` | DEFINE CLUSTER TRANTYPE → `tran_type` table |
| `REPTFILE.jcl` | VSAM define | `AwsConfig` (S3) / report output | Report output dataset → S3 object (no relational table) |
| `DALYREJS.jcl` | VSAM define | `RejectWriter` (S3) | Daily rejects dataset → S3 rejection file |
| `DEFGDGB.jcl` | GDG define | `AwsConfig` + `localstack-init/init-aws.sh` | GDG → S3 versioned objects (buckets created on init) |
| `TRANIDX.jcl` | VSAM index | `V2__create_indexes.sql` | BLDINDEX TRANSACT → alternate-index DDL |
| `TRANBKP.jcl` | Utility | `CombineTransactionsJob` (backup step) | Transaction backup REPRO → bulk copy step |
| `OPENFIL.jcl` | Utility / admin | `BatchConfig` (step lifecycle) | File-open admin → ItemStream open (no business logic) |
| `CLOSEFIL.jcl` | Utility / admin | `BatchConfig` (step lifecycle) | File-close admin → ItemStream close |
| `DEFCUST.jcl` | Utility define | `V1__create_schema.sql` | Customer define helper → schema DDL |
| `PRTCATBL.jcl` | Utility | `TransactionCategoryBalanceRepository` | Print category balances → read/report query |
| `READACCT.jcl` | Utility read | `AccountItemReader` (← CBACT01C) | Sequential account-read driver |
| `READCARD.jcl` | Utility read | `CardItemReader` (← CBACT02C) | Sequential card-read driver |
| `READCUST.jcl` | Utility read | `CustomerItemReader` (← CBCUS01C) | Sequential customer-read driver |
| `READXREF.jcl` | Utility read | `CardCrossReferenceItemReader` (← CBACT03C) | Sequential XREF-read driver |
| `CBADMCDJ.jcl` | Utility / admin | `BatchConfig` + `UserAdminController` | Admin card job → admin batch/config wiring |

**PROCs (`app/proc/`):** `REPROC.prc`, `TRANREPT.prc` → batch-orchestration reference consumed by `BatchPipelineOrchestrator` (5-stage flow: POSTTRAN → INTCALC → COMBTRAN → {CREASTMT ‖ TRANREPT}).

## 7. Reverse Index — Java → COBOL (round-trip verification)

For each major Java component, the originating COBOL program/paragraph(s). This closes the loop: every Java unit traces back to a verified source paragraph or a mandated cross-cutting concern (AAP §0.3.2 — no orphan Java target).

### 7.1 Services

| Java Class.Method | Originating COBOL | Notes |
|---|---|---|
| `AuthenticationService.authenticate()` | `COSGN00C.PROCESS-ENTER-KEY / READ-USER-SEC-FILE` | USRSEC lookup + BCrypt + JWT; upper-cases both id and password (seed-hash dependency, D-017); records `carddemo.auth.attempts` |
| `AccountViewService.getAccountView()` | `COACTVWC.9000-READ-ACCT / 9300-GETACCTDATA-BYACCT / 9400-GETCUSTDATA-BYCUST` | ACCTDAT+CUSTDAT+CXACAIX join |
| `AccountUpdateService.updateAccount()` | `COACTUPC.9600-WRITE-PROCESSING / 2000-DECIDE-ACTION / 9700-CHECK-CHANGE-IN-REC` | `@Transactional` + rollback + `@Version` |
| `CardListService.getCardList()` | `COCRDLIC.9000-READ-FORWARD / 9100-READ-BACKWARDS / 9500-FILTER-RECORDS` | 7 rows/page browse |
| `CardDetailService.getCardDetail()` | `COCRDSLC.9000-READ-DATA / 9100-GETCARD-BYACCTCARD` | Single keyed read |
| `CardUpdateService.updateCard()` | `COCRDUPC.9200-WRITE-PROCESSING / 9300-CHECK-CHANGE-IN-REC` | `@Version` optimistic update |
| `TransactionListService.listTransactions()` | `COTRN00C.PROCESS-PAGE-FORWARD / PROCESS-PAGE-BACKWARD` | 10 rows/page browse |
| `TransactionDetailService.getTransaction()` | `COTRN01C.READ-TRANSACT-FILE` | Keyed detail read |
| `TransactionAddService.addTransaction()` | `COTRN02C.ADD-TRANSACTION / WRITE-TRANSACT-FILE` | Auto-ID generation (Factory) |
| `BillPaymentService.pay()` | `COBIL00C.PROCESS-ENTER-KEY / UPDATE-ACCTDAT-FILE / WRITE-TRANSACT-FILE` | Balance payment posting |
| `ReportSubmissionService.submitReport()` | `CORPT00C.SUBMIT-JOB-TO-INTRDR / WIRTE-JOBSUB-TDQ` | SQS FIFO publish (TDQ bridge) |
| `UserListService.listUsers()` | `COUSR00C.PROCESS-PAGE-FORWARD / READNEXT-USER-SEC-FILE` | User browse |
| `UserAddService.addUser()` | `COUSR01C.WRITE-USER-SEC-FILE` | User create |
| `UserUpdateService.updateUser()` | `COUSR02C.UPDATE-USER-INFO / UPDATE-USER-SEC-FILE` | User update |
| `UserDeleteService.deleteUser()` | `COUSR03C.DELETE-USER-INFO / DELETE-USER-SEC-FILE` | User delete |
| `MainMenuService.getMenuOptions()` | `COMEN01C.PROCESS-ENTER-KEY / BUILD-MENU-OPTIONS (COMEN02Y)` | 10-option routing |
| `AdminMenuService.getMenuOptions()` | `COADM01C.PROCESS-ENTER-KEY / BUILD-MENU-OPTIONS (COADM02Y)` | 4-option routing |
| `DateValidationService.validateDate()` | `CSUTLDTC.A000-MAIN (CEEDAYS)` | `java.time.LocalDate` validation |
| `ValidationLookupService.isValidStateCode()/isValidAreaCode()/isValidStateZip()` | `CSLKPCDY copybook + COACTUPC 1260/1270/1280 edits` | NANPA / state / ZIP JSON lookups |
| `FileStatusMapper.toException()` | `CBTRN02C FILE STATUS clauses + 9910-DISPLAY-IO-STATUS` | FILE STATUS → exception |

### 7.2 Controllers (← BMS mapsets)

| Java Controller | Originating COBOL / BMS | Endpoints |
|---|---|---|
| `AuthController` | `COSGN00C / COSGN00.bms` | `POST /api/auth/signin` |
| `AccountController` | `COACTVWC + COACTUPC / COACTVW.bms + COACTUP.bms` | `GET`/`PUT /api/accounts/*` |
| `CardController` | `COCRDLIC + COCRDSLC + COCRDUPC / COCRDLI/SL/UP.bms` | `GET`/`PUT /api/cards/*` |
| `TransactionController` | `COTRN00C + COTRN01C + COTRN02C / COTRN00/01/02.bms` | `GET`/`POST /api/transactions/*` |
| `BillingController` | `COBIL00C / COBIL00.bms` | `POST /api/billing/pay` |
| `ReportController` | `CORPT00C / CORPT00.bms` | `POST /api/reports/submit` |
| `UserAdminController` | `COUSR00C–03C / COUSR00–03.bms` | CRUD `/api/admin/users/*` |
| `MenuController` | `COMEN01C + COADM01C / COMEN01.bms + COADM01.bms` | `GET /api/menu/*` |

### 7.3 Batch components

| Java Class | Originating COBOL / JCL | Notes |
|---|---|---|
| `DailyTransactionPostingJob` | `POSTTRAN.jcl + CBTRN02C` | 4-stage validation + condition codes |
| `TransactionPostingProcessor.process()` | `CBTRN02C.1500-VALIDATE-TRAN / 2000-POST-TRANSACTION (compute)` | Pure compute → `PostingResult`; reject set `{100,101,102,103,109}`; `103` overwrites `102` (Strategy; D-016, D-018) |
| `InterestCalculationJob` | `INTCALC.jcl + CBACT04C` | Interest formula + DEFAULT group fallback |
| `InterestCalculationProcessor.process()` | `CBACT04C.1300-COMPUTE-INTEREST` | `BigDecimal.divide(…,HALF_EVEN)`; (TRAN-CAT-BAL×rate)/1200 |
| `CombineTransactionsJob / CombineTransactionsProcessor` | `COMBTRAN.jcl (+ CBACT01–03C, CBCUS01C readers)` | DFSORT+REPRO → `Comparator` + bulk insert |
| `StatementGenerationJob / StatementGenerationProcessor` | `CREASTMT.JCL + CBSTM03A + CBSTM03B` | Template Method; text+HTML → S3 |
| `TransactionReportJob / TransactionReportProcessor` | `TRANREPT.jcl + CBTRN03C` | Date-filtered report → S3 |
| `BatchPipelineOrchestrator` | `POSTTRAN→INTCALC→COMBTRAN→{CREASTMT‖TRANREPT}` | 5-stage flow + condition-code deciders (`FlowBuilder.split()`) |
| `DailyTransactionReader` | `CBTRN01C` | Daily-transaction ItemReader |
| `AccountItemReader / CardItemReader / CardCrossReferenceItemReader / CustomerItemReader` | `CBACT01C / CBACT02C / CBACT03C / CBCUS01C` | Sequential file readers → ItemReader |
| `TransactionWriter / RejectWriter / StatementWriter` | `CBTRN02C (2900 / 2500) / CBSTM03A` | `TransactionWriter` consumes `PostingResult` and persists posted state **exactly once** (no recompute) + routes rejects to `RejectWriter`; DB+S3 / S3 rejects / S3 statements (D-018) |

### 7.4 Entities & repositories

| Java Entity | Repository | Originating Copybook / JCL |
|---|---|---|
| `Account` | `AccountRepository` | `CVACT01Y.cpy / ACCTFILE.jcl` |
| `Card` | `CardRepository` | `CVACT02Y.cpy / CARDFILE.jcl` |
| `Customer` | `CustomerRepository` | `CVCUS01Y.cpy + CUSTREC.cpy / CUSTFILE.jcl` |
| `CardCrossReference` | `CardCrossReferenceRepository` | `CVACT03Y.cpy / XREFFILE.jcl (CXACAIX)` |
| `Transaction` | `TransactionRepository` | `CVTRA05Y.cpy / TRANFILE.jcl` |
| `UserSecurity` | `UserSecurityRepository` | `CSUSR01Y.cpy / DUSRSECJ.jcl` |
| `TransactionCategoryBalance` | `TransactionCategoryBalanceRepository` | `CVTRA01Y.cpy / TCATBALF.jcl` |
| `DisclosureGroup` | `DisclosureGroupRepository` | `CVTRA02Y.cpy / DISCGRP.jcl` |
| `TransactionType` | `TransactionTypeRepository` | `CVTRA03Y.cpy / TRANTYPE.jcl` |
| `TransactionCategory` | `TransactionCategoryRepository` | `CVTRA04Y.cpy / TRANCATG.jcl` |
| `DailyTransaction` | `DailyTransactionRepository` | `CVTRA06Y.cpy / POSTTRAN.jcl` |

### 7.5 Cross-cutting concerns (mandated — no single COBOL origin)

These exist to satisfy user-specified rules (AAP §0.7) and have **no** 1:1 COBOL paragraph; they are intentionally new and in scope (not feature expansion).

| Java Component | Mandating Rule | Notes |
|---|---|---|
| `CardDemoException + 6 subclasses` | FILE STATUS mapping (AAP §0.8.4) | RecordNotFound / DuplicateRecord / FileAccess / Validation / ConcurrentUpdate / BatchProcessing |
| `observability.CorrelationIdFilter` | Observability rule | MDC correlation ID per request / batch |
| `observability.MetricsConfig` | Observability rule | Micrometer custom metrics (records.processed/rejected, auth.attempts) |
| `observability.HealthIndicators` | Observability rule | Composite health: PostgreSQL, S3, SQS |
| `config.SecurityConfig` | BCrypt / C-003 (AAP §0.8.1) | Spring Security filter chain + BCrypt (← COSGN00C + CSUSR01Y) |
| `config.BatchConfig` | Batch pipeline (AAP §0.8.5) | Job/step registry, tx manager (← POSTTRAN/INTCALC/COMBTRAN) |
| `config.AwsConfig` | LocalStack Verification rule | S3/SQS/SNS beans (← DEFGDGB.jcl) |
| `config.JpaConfig / config.ObservabilityConfig / config.WebConfig` | Cross-cutting | Entity scanning/auditing; tracing/metrics; CORS/serialization/error handling |
| `CardDemoApplication` | Bootstrap (← COSGN00C entry context) | Spring Boot main class |

## 8. Coverage Attestation (Gate 8)

| Metric | Value |
|---|---|
| COBOL programs covered | **28 / 28 (100%)** |
| Paragraphs / sections mapped (forward) | **527 / 527 (100%)** |
| Copybooks mapped | **28 / 28** |
| JCL jobs mapped | **29 / 29** |
| Bidirectional (forward + reverse) | **Yes** (§4 forward, §7 reverse) |
| Source commit referenced | **`27d6c6f`** |
| COBOL copied into target | **No** (AAP §0.8.1) |
| Feature expansion | **None** — every Java target maps to a verified paragraph or a mandated cross-cutting concern (AAP §0.3.2) |

_This matrix is generated from the verified `PROCEDURE DIVISION` paragraph inventory of all 28 programs
under `app/cbl/` at commit `27d6c6f`. It is the binding artifact for the Explainability rule and Gate 8
integration sign-off (AAP §0.7.2, §0.7.3, §0.8.6)._

