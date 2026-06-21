# CardDemo COBOL ↔ Java Traceability Matrix

> **Bidirectional traceability** for the AWS CardDemo mainframe-to-cloud migration.
> Source baseline: **`CardDemo_v1.0-15-g27d6c6f-68`** — original repository commit SHA **`27d6c6f`**.
> Target: greenfield **`carddemo-java/`** (Java 25 LTS + Spring Boot 3.x).

---

## 1. Introduction

This document is the authoritative, **bidirectional** traceability matrix mandated by the project's **Explainability rule** and **Validation Gate 8**. It establishes a verifiable mapping in **both** directions:

- **Forward (COBOL → Java):** every paragraph and section of all **28 COBOL programs** is mapped to the Java class and method that carries its behavior.
- **Reverse (Java → COBOL):** every significant Java component cites the originating COBOL paragraph(s), enabling round-trip verification.

### 1.1 Coverage Statement

This matrix provides **100% coverage of all COBOL paragraphs/sections** across the 28 source programs. A total of **528 paragraph/section headers** were enumerated directly from the PROCEDURE DIVISION of each program under `app/cbl/` and each one appears as a row in the forward matrix (Section 3). The coverage figure is reconciled programmatically in the **Coverage Summary** (Section 7).

### 1.2 Methodology

1. **Enumeration.** Each program's PROCEDURE DIVISION was parsed; every Area-A header (paragraph or `SECTION`) was extracted with its source line number. Comment lines and Area-B statements are excluded by construction.
2. **Mapping.** Each paragraph is mapped to a Java class/method using the COBOL→Java transformation contract (AAP §0.1.2): `PARAGRAPH/SECTION → methods on service/component classes`, `FILE SECTION → Spring Data repositories / I/O services`, `FILE STATUS → exception + status enums`, `SORT/MERGE → Java Comparator`, `SYNCPOINT → @Transactional`, and optimistic concurrency → JPA `@Version`.
3. **Verification.** Every Java class named in this matrix corresponds to a planned source file under `src/main/java/com/carddemo/**` (AAP §0.4.1 / §0.5.1). No Java target is introduced that does not trace back to an existing COBOL paragraph or to a mandated cross-cutting concern — there is **no feature expansion** (AAP §0.3.2).

### 1.3 Important Notes

- **COBOL is not copied.** Per the preservation mandate (AAP §0.8.1), no COBOL source is embedded in `carddemo-java/`. Traceability is preserved exclusively by referencing original commit SHA **`27d6c6f`** and the source line numbers in this matrix.
- **Idealized vs. actual paragraph names.** The AAP (§0.7.3) cites three *representative* rows using idealized paragraph names. Two of those names do not exist verbatim in the verified source; this matrix uses the **real** paragraph names and notes the reconciliation explicitly:

  | AAP idealized name | Verified source paragraph(s) | Java target |
  |---|---|---|
  | `COSGN00C.PROCESS-ENTER-KEY` | `PROCESS-ENTER-KEY` *(exists as-is)* | `AuthenticationService.authenticate()` |
  | `COACTUPC.PROCESS-UPDATE-ACCT` | `2000-DECIDE-ACTION` + `9600-WRITE-PROCESSING` + `9700-CHECK-CHANGE-IN-REC` | `AccountUpdateService.updateAccount()` |
  | `CBTRN02C.2000-VALIDATE-TXN` | `1500-VALIDATE-TRAN` (+ `1500-A-LOOKUP-XREF`, `1500-B-LOOKUP-ACCT`) | `TransactionPostingProcessor.validate()` |

- **Cross-cutting Java components** (configuration, observability, bootstrap, documentation) have **no COBOL paragraph origin**; they exist solely to satisfy the six user-specified implementation rules (Observability, LocalStack Verification, etc.) and are listed as such in the reverse index (Section 6.8).

---

## 2. Program Inventory

The application comprises **28 COBOL programs totaling 19,254 source lines**, partitioned into **17 online CICS programs (14,693 lines)** and **11 batch / utility programs (4,561 lines)**. Line counts are measured with `wc -l` and match AAP §0.2.1.

### 2.1 Online CICS Programs (17)

| # | Program | Lines | Paragraphs | Primary Java Target | Migration Role |
|---|---|---:|---:|---|---|
| 1 | `COACTUPC.cbl` | 4,236 | 85 | `AccountUpdateService` | Account update - SYNCPOINT ROLLBACK + optimistic concurrency |
| 2 | `COCRDUPC.cbl` | 1,560 | 45 | `CardUpdateService` | Card update - optimistic concurrency |
| 3 | `COCRDLIC.cbl` | 1,459 | 39 | `CardListService` | Card list - paginated browse (7 rows/page) |
| 4 | `COACTVWC.cbl` | 941 | 35 | `AccountViewService` | Account view - multi-dataset read |
| 5 | `COCRDSLC.cbl` | 887 | 34 | `CardDetailService` | Card detail - single keyed read |
| 6 | `COTRN02C.cbl` | 783 | 18 | `TransactionAddService` | Transaction add - auto-ID + confirmation flow |
| 7 | `COTRN00C.cbl` | 699 | 16 | `TransactionListService` | Transaction list - paginated browse (10 rows/page) |
| 8 | `COUSR00C.cbl` | 695 | 16 | `UserListService` | User list |
| 9 | `CORPT00C.cbl` | 649 | 10 | `ReportSubmissionService` | Report submission - TDQ WRITEQ (online->batch bridge) |
| 10 | `COBIL00C.cbl` | 572 | 16 | `BillPaymentService` | Bill payment |
| 11 | `COUSR02C.cbl` | 414 | 11 | `UserUpdateService` | User update |
| 12 | `COUSR03C.cbl` | 359 | 11 | `UserDeleteService` | User delete |
| 13 | `COTRN01C.cbl` | 330 | 9 | `TransactionDetailService` | Transaction detail - keyed read |
| 14 | `COUSR01C.cbl` | 299 | 9 | `UserAddService` | User add |
| 15 | `COMEN01C.cbl` | 282 | 7 | `MainMenuService` | Main menu - 10-option routing |
| 16 | `COADM01C.cbl` | 268 | 7 | `AdminMenuService` | Admin menu - 4-option routing |
| 17 | `COSGN00C.cbl` | 260 | 6 | `AuthenticationService` | Sign-on / authentication entry point |
| | **Subtotal** | **14,693** | **374** | | |

### 2.2 Batch and Utility Programs (11)

| # | Program | Lines | Paragraphs | Primary Java Target | Migration Role |
|---|---|---:|---:|---|---|
| 1 | `CBSTM03A.CBL` | 924 | 25 | `StatementGenerationJob` | Batch: statement generation main |
| 2 | `CBTRN02C.cbl` | 731 | 26 | `DailyTransactionPostingJob` | Batch: daily transaction posting - 4-stage validation cascade |
| 3 | `CBACT04C.cbl` | 652 | 22 | `InterestCalculationJob` | Batch: interest calculation |
| 4 | `CBTRN03C.cbl` | 649 | 26 | `TransactionReportJob` | Batch: transaction report |
| 5 | `CBTRN01C.cbl` | 491 | 18 | `DailyTransactionReader` | Batch: daily transaction reader |
| 6 | `CBSTM03B.CBL` | 230 | 14 | `StatementWriter` | Batch: statement generation sub (file service) |
| 7 | `CBACT01C.cbl` | 193 | 6 | `AccountFileReader` | Batch: account file reader |
| 8 | `CBACT02C.cbl` | 178 | 5 | `CardFileReader` | Batch: card file reader |
| 9 | `CBACT03C.cbl` | 178 | 5 | `CardCrossReferenceReader` | Batch: cross-reference file reader |
| 10 | `CBCUS01C.cbl` | 178 | 5 | `CustomerFileReader` | Batch: customer file reader |
| 11 | `CSUTLDTC.cbl` | 157 | 2 | `DateValidationService` | Date validation subprogram (CEEDAYS); called by online + batch |
| | **Subtotal** | **4,561** | **154** | | |

> The two statement-generation programs (`CBSTM03A.CBL`, `CBSTM03B.CBL`) carry the uppercase `.CBL` extension in the source tree; all other programs use `.cbl`.

---

## 3. Forward Traceability Matrix (COBOL → Java)

Every paragraph/section of all 28 programs appears below, grouped by program (the program is identified by each subsection heading). Columns: **Paragraph / Section**, **Type** (PARA or SECTION), **Src Line** (line in the source program at SHA `27d6c6f`), **Java Class**, **Java Method**, and **Mapping Notes**.

### Online CICS Programs

### 3.1 `COACTUPC.cbl` — Account update - SYNCPOINT ROLLBACK + optimistic concurrency

*4,236 lines · 85 paragraphs/sections · primary target `AccountUpdateService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-MAIN` | PARA | 859 | `AccountUpdateService` | `handle()` | CICS pseudo-conversational entry -> AccountController.updateAccount() orchestration |
| `COMMON-RETURN` | PARA | 1007 | `AccountUpdateService` | `commonReturn()` | Direct paragraph → method translation (control flow preserved) |
| `0000-MAIN-EXIT` | PARA | 1021 | `AccountUpdateService` | `main() [return]` | PERFORM-THRU exit boundary -> collapses into return of main() |
| `1000-PROCESS-INPUTS` | PARA | 1025 | `AccountUpdateService` | `processInputs()` | Direct paragraph → method translation (control flow preserved) |
| `1000-PROCESS-INPUTS-EXIT` | PARA | 1036 | `AccountUpdateService` | `processInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of processInputs() |
| `1100-RECEIVE-MAP` | PARA | 1039 | `AccountUpdateService` | `receiveMap()` | Direct paragraph → method translation (control flow preserved) |
| `1100-RECEIVE-MAP-EXIT` | PARA | 1426 | `AccountUpdateService` | `receiveMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of receiveMap() |
| `1200-EDIT-MAP-INPUTS` | PARA | 1429 | `AccountUpdateService` | `editMapInputs()` | Direct paragraph → method translation (control flow preserved) |
| `1200-EDIT-MAP-INPUTS-EXIT` | PARA | 1678 | `AccountUpdateService` | `editMapInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of editMapInputs() |
| `1205-COMPARE-OLD-NEW` | PARA | 1681 | `AccountUpdateService` | `compareOldNew()` | Direct paragraph → method translation (control flow preserved) |
| `1205-COMPARE-OLD-NEW-EXIT` | PARA | 1777 | `AccountUpdateService` | `compareOldNew() [return]` | PERFORM-THRU exit boundary -> collapses into return of compareOldNew() |
| `1210-EDIT-ACCOUNT` | PARA | 1783 | `AccountUpdateService` | `editAccount()` | Direct paragraph → method translation (control flow preserved) |
| `1210-EDIT-ACCOUNT-EXIT` | PARA | 1820 | `AccountUpdateService` | `editAccount() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAccount() |
| `1215-EDIT-MANDATORY` | PARA | 1824 | `AccountUpdateService` | `editMandatory()` | Direct paragraph → method translation (control flow preserved) |
| `1215-EDIT-MANDATORY-EXIT` | PARA | 1852 | `AccountUpdateService` | `editMandatory() [return]` | PERFORM-THRU exit boundary -> collapses into return of editMandatory() |
| `1220-EDIT-YESNO` | PARA | 1856 | `AccountUpdateService` | `editYesno()` | Direct paragraph → method translation (control flow preserved) |
| `1220-EDIT-YESNO-EXIT` | PARA | 1894 | `AccountUpdateService` | `editYesno() [return]` | PERFORM-THRU exit boundary -> collapses into return of editYesno() |
| `1225-EDIT-ALPHA-REQD` | PARA | 1898 | `AccountUpdateService` | `editAlphaReqd()` | Direct paragraph → method translation (control flow preserved) |
| `1225-EDIT-ALPHA-REQD-EXIT` | PARA | 1951 | `AccountUpdateService` | `editAlphaReqd() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAlphaReqd() |
| `1230-EDIT-ALPHANUM-REQD` | PARA | 1955 | `AccountUpdateService` | `editAlphanumReqd()` | Direct paragraph → method translation (control flow preserved) |
| `1230-EDIT-ALPHANUM-REQD-EXIT` | PARA | 2009 | `AccountUpdateService` | `editAlphanumReqd() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAlphanumReqd() |
| `1235-EDIT-ALPHA-OPT` | PARA | 2012 | `AccountUpdateService` | `editAlphaOpt()` | Direct paragraph → method translation (control flow preserved) |
| `1235-EDIT-ALPHA-OPT-EXIT` | PARA | 2057 | `AccountUpdateService` | `editAlphaOpt() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAlphaOpt() |
| `1240-EDIT-ALPHANUM-OPT` | PARA | 2061 | `AccountUpdateService` | `editAlphanumOpt()` | Direct paragraph → method translation (control flow preserved) |
| `1240-EDIT-ALPHANUM-OPT-EXIT` | PARA | 2105 | `AccountUpdateService` | `editAlphanumOpt() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAlphanumOpt() |
| `1245-EDIT-NUM-REQD` | PARA | 2109 | `AccountUpdateService` | `editNumReqd()` | Direct paragraph → method translation (control flow preserved) |
| `1245-EDIT-NUM-REQD-EXIT` | PARA | 2176 | `AccountUpdateService` | `editNumReqd() [return]` | PERFORM-THRU exit boundary -> collapses into return of editNumReqd() |
| `1250-EDIT-SIGNED-9V2` | PARA | 2180 | `AccountUpdateService` | `editSigned9v2()` | Direct paragraph → method translation (control flow preserved) |
| `1250-EDIT-SIGNED-9V2-EXIT` | PARA | 2221 | `AccountUpdateService` | `editSigned9v2() [return]` | PERFORM-THRU exit boundary -> collapses into return of editSigned9v2() |
| `1260-EDIT-US-PHONE-NUM` | PARA | 2225 | `AccountUpdateService` | `editUsPhoneNum()` | Direct paragraph → method translation (control flow preserved) |
| `EDIT-AREA-CODE` | PARA | 2246 | `AccountUpdateService` | `editAreaCode()` | Direct paragraph → method translation (control flow preserved) |
| `EDIT-US-PHONE-PREFIX` | PARA | 2316 | `AccountUpdateService` | `editUsPhonePrefix()` | Direct paragraph → method translation (control flow preserved) |
| `EDIT-US-PHONE-LINENUM` | PARA | 2370 | `AccountUpdateService` | `editUsPhoneLinenum()` | Direct paragraph → method translation (control flow preserved) |
| `EDIT-US-PHONE-EXIT` | PARA | 2424 | `AccountUpdateService` | `editUsPhone() [return]` | PERFORM-THRU exit boundary -> collapses into return of editUsPhone() |
| `1260-EDIT-US-PHONE-NUM-EXIT` | PARA | 2427 | `AccountUpdateService` | `editUsPhoneNum() [return]` | PERFORM-THRU exit boundary -> collapses into return of editUsPhoneNum() |
| `1265-EDIT-US-SSN` | PARA | 2431 | `AccountUpdateService` | `editUsSsn()` | Direct paragraph → method translation (control flow preserved) |
| `1265-EDIT-US-SSN-EXIT` | PARA | 2489 | `AccountUpdateService` | `editUsSsn() [return]` | PERFORM-THRU exit boundary -> collapses into return of editUsSsn() |
| `1270-EDIT-US-STATE-CD` | PARA | 2493 | `AccountUpdateService` | `editUsStateCd()` | Direct paragraph → method translation (control flow preserved) |
| `1270-EDIT-US-STATE-CD-EXIT` | PARA | 2511 | `AccountUpdateService` | `editUsStateCd() [return]` | PERFORM-THRU exit boundary -> collapses into return of editUsStateCd() |
| `1275-EDIT-FICO-SCORE` | PARA | 2514 | `AccountUpdateService` | `editFicoScore()` | Direct paragraph → method translation (control flow preserved) |
| `1275-EDIT-FICO-SCORE-EXIT` | PARA | 2531 | `AccountUpdateService` | `editFicoScore() [return]` | PERFORM-THRU exit boundary -> collapses into return of editFicoScore() |
| `1280-EDIT-US-STATE-ZIP-CD` | PARA | 2536 | `AccountUpdateService` | `editUsStateZipCd()` | Direct paragraph → method translation (control flow preserved) |
| `1280-EDIT-US-STATE-ZIP-CD-EXIT` | PARA | 2558 | `AccountUpdateService` | `editUsStateZipCd() [return]` | PERFORM-THRU exit boundary -> collapses into return of editUsStateZipCd() |
| `2000-DECIDE-ACTION` | PARA | 2562 | `AccountUpdateService` | `updateAccount()` | **Representative row (AAP 0.7.3).** EVALUATE action dispatch; `@Transactional` commit/rollback boundary |
| `2000-DECIDE-ACTION-EXIT` | PARA | 2643 | `AccountUpdateService` | `decideAction() [return]` | PERFORM-THRU exit boundary -> collapses into return of decideAction() |
| `3000-SEND-MAP` | PARA | 2649 | `AccountUpdateService` | `sendMap()` | Direct paragraph → method translation (control flow preserved) |
| `3000-SEND-MAP-EXIT` | PARA | 2664 | `AccountUpdateService` | `sendMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendMap() |
| `3100-SCREEN-INIT` | PARA | 2668 | `AccountUpdateService` | `screenInit()` | Direct paragraph → method translation (control flow preserved) |
| `3100-SCREEN-INIT-EXIT` | PARA | 2694 | `AccountUpdateService` | `screenInit() [return]` | PERFORM-THRU exit boundary -> collapses into return of screenInit() |
| `3200-SETUP-SCREEN-VARS` | PARA | 2698 | `AccountUpdateService` | `setupScreenVars()` | Direct paragraph → method translation (control flow preserved) |
| `3200-SETUP-SCREEN-VARS-EXIT` | PARA | 2727 | `AccountUpdateService` | `setupScreenVars() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenVars() |
| `3201-SHOW-INITIAL-VALUES` | PARA | 2731 | `AccountUpdateService` | `showInitialValues()` | Direct paragraph → method translation (control flow preserved) |
| `3201-SHOW-INITIAL-VALUES-EXIT` | PARA | 2783 | `AccountUpdateService` | `showInitialValues() [return]` | PERFORM-THRU exit boundary -> collapses into return of showInitialValues() |
| `3202-SHOW-ORIGINAL-VALUES` | PARA | 2787 | `AccountUpdateService` | `showOriginalValues()` | Direct paragraph → method translation (control flow preserved) |
| `3202-SHOW-ORIGINAL-VALUES-EXIT` | PARA | 2867 | `AccountUpdateService` | `showOriginalValues() [return]` | PERFORM-THRU exit boundary -> collapses into return of showOriginalValues() |
| `3203-SHOW-UPDATED-VALUES` | PARA | 2870 | `AccountUpdateService` | `showUpdatedValues()` | Direct paragraph → method translation (control flow preserved) |
| `3203-SHOW-UPDATED-VALUES-EXIT` | PARA | 2951 | `AccountUpdateService` | `showUpdatedValues() [return]` | PERFORM-THRU exit boundary -> collapses into return of showUpdatedValues() |
| `3250-SETUP-INFOMSG` | PARA | 2955 | `AccountUpdateService` | `setupInfomsg()` | Direct paragraph → method translation (control flow preserved) |
| `3250-SETUP-INFOMSG-EXIT` | PARA | 2983 | `AccountUpdateService` | `setupInfomsg() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupInfomsg() |
| `3300-SETUP-SCREEN-ATTRS` | PARA | 2986 | `AccountUpdateService` | `setupScreenAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `3300-SETUP-SCREEN-ATTRS-EXIT` | PARA | 3437 | `AccountUpdateService` | `setupScreenAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenAttrs() |
| `3310-PROTECT-ALL-ATTRS` | PARA | 3441 | `AccountUpdateService` | `protectAllAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `3310-PROTECT-ALL-ATTRS-EXIT` | PARA | 3496 | `AccountUpdateService` | `protectAllAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of protectAllAttrs() |
| `3320-UNPROTECT-FEW-ATTRS` | PARA | 3500 | `AccountUpdateService` | `unprotectFewAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `3320-UNPROTECT-FEW-ATTRS-EXIT` | PARA | 3562 | `AccountUpdateService` | `unprotectFewAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of unprotectFewAttrs() |
| `3390-SETUP-INFOMSG-ATTRS` | PARA | 3566 | `AccountUpdateService` | `setupInfomsgAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `3390-SETUP-INFOMSG-ATTRS-EXIT` | PARA | 3584 | `AccountUpdateService` | `setupInfomsgAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupInfomsgAttrs() |
| `3400-SEND-SCREEN` | PARA | 3589 | `AccountUpdateService` | `sendScreen()` | Direct paragraph → method translation (control flow preserved) |
| `3400-SEND-SCREEN-EXIT` | PARA | 3603 | `AccountUpdateService` | `sendScreen() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendScreen() |
| `9000-READ-ACCT` | PARA | 3608 | `AccountUpdateService` | `readAcct()` | Direct paragraph → method translation (control flow preserved) |
| `9000-READ-ACCT-EXIT` | PARA | 3647 | `AccountUpdateService` | `readAcct() [return]` | PERFORM-THRU exit boundary -> collapses into return of readAcct() |
| `9200-GETCARDXREF-BYACCT` | PARA | 3650 | `AccountUpdateService` | `loadXref()` | CXACAIX alternate-index read -> CardCrossReferenceRepository.findByXrefAcctId() |
| `9200-GETCARDXREF-BYACCT-EXIT` | PARA | 3698 | `AccountUpdateService` | `getcardxrefByacct() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcardxrefByacct() |
| `9300-GETACCTDATA-BYACCT` | PARA | 3701 | `AccountUpdateService` | `loadAccount()` | ACCTDAT keyed read -> AccountRepository.findById() |
| `9300-GETACCTDATA-BYACCT-EXIT` | PARA | 3748 | `AccountUpdateService` | `getacctdataByacct() [return]` | PERFORM-THRU exit boundary -> collapses into return of getacctdataByacct() |
| `9400-GETCUSTDATA-BYCUST` | PARA | 3752 | `AccountUpdateService` | `loadCustomer()` | CUSTDAT keyed read -> CustomerRepository.findById() |
| `9400-GETCUSTDATA-BYCUST-EXIT` | PARA | 3797 | `AccountUpdateService` | `getcustdataBycust() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcustdataBycust() |
| `9500-STORE-FETCHED-DATA` | PARA | 3801 | `AccountUpdateService` | `storeFetchedData()` | Direct paragraph → method translation (control flow preserved) |
| `9500-STORE-FETCHED-DATA-EXIT` | PARA | 3885 | `AccountUpdateService` | `storeFetchedData() [return]` | PERFORM-THRU exit boundary -> collapses into return of storeFetchedData() |
| `9600-WRITE-PROCESSING` | PARA | 3888 | `AccountUpdateService` | `updateAccount()` | Dual ACCTDAT+CUSTDAT REWRITE (L4066/L4086); sole `SYNCPOINT ROLLBACK` (L4100) -> `@Transactional` rollback-on-exception |
| `9600-WRITE-PROCESSING-EXIT` | PARA | 4105 | `AccountUpdateService` | `writeProcessing() [return]` | PERFORM-THRU exit boundary -> collapses into return of writeProcessing() |
| `9700-CHECK-CHANGE-IN-REC` | PARA | 4109 | `AccountUpdateService` | `assertNoConcurrentChange()` | Before/after record-image compare -> JPA `@Version` optimistic lock (OptimisticLockException) |
| `9700-CHECK-CHANGE-IN-REC-EXIT` | PARA | 4193 | `AccountUpdateService` | `checkChangeInRec() [return]` | PERFORM-THRU exit boundary -> collapses into return of checkChangeInRec() |
| `ABEND-ROUTINE` | PARA | 4203 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `ABEND-ROUTINE-EXIT` | PARA | 4226 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |

### 3.2 `COCRDUPC.cbl` — Card update - optimistic concurrency

*1,560 lines · 45 paragraphs/sections · primary target `CardUpdateService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-MAIN` | PARA | 367 | `CardUpdateService` | `main()` | Direct paragraph → method translation (control flow preserved) |
| `COMMON-RETURN` | PARA | 546 | `CardUpdateService` | `commonReturn()` | Direct paragraph → method translation (control flow preserved) |
| `0000-MAIN-EXIT` | PARA | 560 | `CardUpdateService` | `main() [return]` | PERFORM-THRU exit boundary -> collapses into return of main() |
| `1000-PROCESS-INPUTS` | PARA | 564 | `CardUpdateService` | `processInputs()` | Direct paragraph → method translation (control flow preserved) |
| `1000-PROCESS-INPUTS-EXIT` | PARA | 575 | `CardUpdateService` | `processInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of processInputs() |
| `1100-RECEIVE-MAP` | PARA | 578 | `CardUpdateService` | `receiveMap()` | Direct paragraph → method translation (control flow preserved) |
| `1100-RECEIVE-MAP-EXIT` | PARA | 638 | `CardUpdateService` | `receiveMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of receiveMap() |
| `1200-EDIT-MAP-INPUTS` | PARA | 641 | `CardUpdateService` | `editMapInputs()` | Direct paragraph → method translation (control flow preserved) |
| `1200-EDIT-MAP-INPUTS-EXIT` | PARA | 717 | `CardUpdateService` | `editMapInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of editMapInputs() |
| `1210-EDIT-ACCOUNT` | PARA | 721 | `CardUpdateService` | `editAccount()` | Direct paragraph → method translation (control flow preserved) |
| `1210-EDIT-ACCOUNT-EXIT` | PARA | 758 | `CardUpdateService` | `editAccount() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAccount() |
| `1220-EDIT-CARD` | PARA | 762 | `CardUpdateService` | `editCard()` | Direct paragraph → method translation (control flow preserved) |
| `1220-EDIT-CARD-EXIT` | PARA | 802 | `CardUpdateService` | `editCard() [return]` | PERFORM-THRU exit boundary -> collapses into return of editCard() |
| `1230-EDIT-NAME` | PARA | 806 | `CardUpdateService` | `editName()` | Direct paragraph → method translation (control flow preserved) |
| `1230-EDIT-NAME-EXIT` | PARA | 841 | `CardUpdateService` | `editName() [return]` | PERFORM-THRU exit boundary -> collapses into return of editName() |
| `1240-EDIT-CARDSTATUS` | PARA | 845 | `CardUpdateService` | `editCardstatus()` | Direct paragraph → method translation (control flow preserved) |
| `1240-EDIT-CARDSTATUS-EXIT` | PARA | 874 | `CardUpdateService` | `editCardstatus() [return]` | PERFORM-THRU exit boundary -> collapses into return of editCardstatus() |
| `1250-EDIT-EXPIRY-MON` | PARA | 877 | `CardUpdateService` | `editExpiryMon()` | Direct paragraph → method translation (control flow preserved) |
| `1250-EDIT-EXPIRY-MON-EXIT` | PARA | 910 | `CardUpdateService` | `editExpiryMon() [return]` | PERFORM-THRU exit boundary -> collapses into return of editExpiryMon() |
| `1260-EDIT-EXPIRY-YEAR` | PARA | 913 | `CardUpdateService` | `editExpiryYear()` | Direct paragraph → method translation (control flow preserved) |
| `1260-EDIT-EXPIRY-YEAR-EXIT` | PARA | 945 | `CardUpdateService` | `editExpiryYear() [return]` | PERFORM-THRU exit boundary -> collapses into return of editExpiryYear() |
| `2000-DECIDE-ACTION` | PARA | 948 | `CardUpdateService` | `decideAction()` | Direct paragraph → method translation (control flow preserved) |
| `2000-DECIDE-ACTION-EXIT` | PARA | 1029 | `CardUpdateService` | `decideAction() [return]` | PERFORM-THRU exit boundary -> collapses into return of decideAction() |
| `3000-SEND-MAP` | PARA | 1035 | `CardUpdateService` | `sendMap()` | Direct paragraph → method translation (control flow preserved) |
| `3000-SEND-MAP-EXIT` | PARA | 1048 | `CardUpdateService` | `sendMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendMap() |
| `3100-SCREEN-INIT` | PARA | 1052 | `CardUpdateService` | `screenInit()` | Direct paragraph → method translation (control flow preserved) |
| `3100-SCREEN-INIT-EXIT` | PARA | 1078 | `CardUpdateService` | `screenInit() [return]` | PERFORM-THRU exit boundary -> collapses into return of screenInit() |
| `3200-SETUP-SCREEN-VARS` | PARA | 1082 | `CardUpdateService` | `setupScreenVars()` | Direct paragraph → method translation (control flow preserved) |
| `3200-SETUP-SCREEN-VARS-EXIT` | PARA | 1135 | `CardUpdateService` | `setupScreenVars() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenVars() |
| `3250-SETUP-INFOMSG` | PARA | 1138 | `CardUpdateService` | `setupInfomsg()` | Direct paragraph → method translation (control flow preserved) |
| `3250-SETUP-INFOMSG-EXIT` | PARA | 1165 | `CardUpdateService` | `setupInfomsg() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupInfomsg() |
| `3300-SETUP-SCREEN-ATTRS` | PARA | 1168 | `CardUpdateService` | `setupScreenAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `3300-SETUP-SCREEN-ATTRS-EXIT` | PARA | 1319 | `CardUpdateService` | `setupScreenAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenAttrs() |
| `3400-SEND-SCREEN` | PARA | 1324 | `CardUpdateService` | `sendScreen()` | Direct paragraph → method translation (control flow preserved) |
| `3400-SEND-SCREEN-EXIT` | PARA | 1338 | `CardUpdateService` | `sendScreen() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendScreen() |
| `9000-READ-DATA` | PARA | 1343 | `CardUpdateService` | `readData()` | Direct paragraph → method translation (control flow preserved) |
| `9000-READ-DATA-EXIT` | PARA | 1372 | `CardUpdateService` | `readData() [return]` | PERFORM-THRU exit boundary -> collapses into return of readData() |
| `9100-GETCARD-BYACCTCARD` | PARA | 1376 | `CardUpdateService` | `getcardByacctcard()` | Direct paragraph → method translation (control flow preserved) |
| `9100-GETCARD-BYACCTCARD-EXIT` | PARA | 1415 | `CardUpdateService` | `getcardByacctcard() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcardByacctcard() |
| `9200-WRITE-PROCESSING` | PARA | 1420 | `CardUpdateService` | `writeProcessing()` | Direct paragraph → method translation (control flow preserved) |
| `9200-WRITE-PROCESSING-EXIT` | PARA | 1494 | `CardUpdateService` | `writeProcessing() [return]` | PERFORM-THRU exit boundary -> collapses into return of writeProcessing() |
| `9300-CHECK-CHANGE-IN-REC` | PARA | 1498 | `CardUpdateService` | `checkChangeInRec()` | Direct paragraph → method translation (control flow preserved) |
| `9300-CHECK-CHANGE-IN-REC-EXIT` | PARA | 1521 | `CardUpdateService` | `checkChangeInRec() [return]` | PERFORM-THRU exit boundary -> collapses into return of checkChangeInRec() |
| `ABEND-ROUTINE` | PARA | 1531 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `ABEND-ROUTINE-EXIT` | PARA | 1554 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |

### 3.3 `COCRDLIC.cbl` — Card list - paginated browse (7 rows/page)

*1,459 lines · 39 paragraphs/sections · primary target `CardListService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-MAIN` | PARA | 298 | `CardListService` | `main()` | Direct paragraph → method translation (control flow preserved) |
| `COMMON-RETURN` | PARA | 604 | `CardListService` | `commonReturn()` | Direct paragraph → method translation (control flow preserved) |
| `0000-MAIN-EXIT` | PARA | 621 | `CardListService` | `main() [return]` | PERFORM-THRU exit boundary -> collapses into return of main() |
| `1000-SEND-MAP` | PARA | 624 | `CardListService` | `sendMap()` | Direct paragraph → method translation (control flow preserved) |
| `1000-SEND-MAP-EXIT` | PARA | 639 | `CardListService` | `sendMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendMap() |
| `1100-SCREEN-INIT` | PARA | 642 | `CardListService` | `screenInit()` | Direct paragraph → method translation (control flow preserved) |
| `1100-SCREEN-INIT-EXIT` | PARA | 674 | `CardListService` | `screenInit() [return]` | PERFORM-THRU exit boundary -> collapses into return of screenInit() |
| `1200-SCREEN-ARRAY-INIT` | PARA | 678 | `CardListService` | `screenArrayInit()` | Direct paragraph → method translation (control flow preserved) |
| `1200-SCREEN-ARRAY-INIT-EXIT` | PARA | 745 | `CardListService` | `screenArrayInit() [return]` | PERFORM-THRU exit boundary -> collapses into return of screenArrayInit() |
| `1250-SETUP-ARRAY-ATTRIBS` | PARA | 748 | `CardListService` | `setupArrayAttribs()` | Direct paragraph → method translation (control flow preserved) |
| `1250-SETUP-ARRAY-ATTRIBS-EXIT` | PARA | 834 | `CardListService` | `setupArrayAttribs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupArrayAttribs() |
| `1300-SETUP-SCREEN-ATTRS` | PARA | 837 | `CardListService` | `setupScreenAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `1300-SETUP-SCREEN-ATTRS-EXIT` | PARA | 890 | `CardListService` | `setupScreenAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenAttrs() |
| `1400-SETUP-MESSAGE` | PARA | 895 | `CardListService` | `setupMessage()` | Direct paragraph → method translation (control flow preserved) |
| `1400-SETUP-MESSAGE-EXIT` | PARA | 933 | `CardListService` | `setupMessage() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupMessage() |
| `1500-SEND-SCREEN` | PARA | 938 | `CardListService` | `sendScreen()` | Direct paragraph → method translation (control flow preserved) |
| `1500-SEND-SCREEN-EXIT` | PARA | 948 | `CardListService` | `sendScreen() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendScreen() |
| `2000-RECEIVE-MAP` | PARA | 951 | `CardListService` | `receiveMap()` | Direct paragraph → method translation (control flow preserved) |
| `2000-RECEIVE-MAP-EXIT` | PARA | 959 | `CardListService` | `receiveMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of receiveMap() |
| `2100-RECEIVE-SCREEN` | PARA | 962 | `CardListService` | `receiveScreen()` | Direct paragraph → method translation (control flow preserved) |
| `2100-RECEIVE-SCREEN-EXIT` | PARA | 981 | `CardListService` | `receiveScreen() [return]` | PERFORM-THRU exit boundary -> collapses into return of receiveScreen() |
| `2200-EDIT-INPUTS` | PARA | 985 | `CardListService` | `editInputs()` | Direct paragraph → method translation (control flow preserved) |
| `2200-EDIT-INPUTS-EXIT` | PARA | 999 | `CardListService` | `editInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of editInputs() |
| `2210-EDIT-ACCOUNT` | PARA | 1003 | `CardListService` | `editAccount()` | Direct paragraph → method translation (control flow preserved) |
| `2210-EDIT-ACCOUNT-EXIT` | PARA | 1032 | `CardListService` | `editAccount() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAccount() |
| `2220-EDIT-CARD` | PARA | 1036 | `CardListService` | `editCard()` | Direct paragraph → method translation (control flow preserved) |
| `2220-EDIT-CARD-EXIT` | PARA | 1069 | `CardListService` | `editCard() [return]` | PERFORM-THRU exit boundary -> collapses into return of editCard() |
| `2250-EDIT-ARRAY` | PARA | 1073 | `CardListService` | `editArray()` | Direct paragraph → method translation (control flow preserved) |
| `2250-EDIT-ARRAY-EXIT` | PARA | 1119 | `CardListService` | `editArray() [return]` | PERFORM-THRU exit boundary -> collapses into return of editArray() |
| `9000-READ-FORWARD` | PARA | 1123 | `CardListService` | `readForward()` | Direct paragraph → method translation (control flow preserved) |
| `9000-READ-FORWARD-EXIT` | PARA | 1261 | `CardListService` | `readForward() [return]` | PERFORM-THRU exit boundary -> collapses into return of readForward() |
| `9100-READ-BACKWARDS` | PARA | 1264 | `CardListService` | `readBackwards()` | Direct paragraph → method translation (control flow preserved) |
| `9100-READ-BACKWARDS-EXIT` | PARA | 1374 | `CardListService` | `readBackwards() [return]` | PERFORM-THRU exit boundary -> collapses into return of readBackwards() |
| `9500-FILTER-RECORDS` | PARA | 1382 | `CardListService` | `filterRecords()` | Direct paragraph → method translation (control flow preserved) |
| `9500-FILTER-RECORDS-EXIT` | PARA | 1409 | `CardListService` | `filterRecords() [return]` | PERFORM-THRU exit boundary -> collapses into return of filterRecords() |
| `SEND-PLAIN-TEXT` | PARA | 1422 | `CardListService` | `sendPlainText()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-PLAIN-TEXT-EXIT` | PARA | 1433 | `CardListService` | `sendPlainText() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendPlainText() |
| `SEND-LONG-TEXT` | PARA | 1441 | `CardListService` | `sendLongText()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-LONG-TEXT-EXIT` | PARA | 1452 | `CardListService` | `sendLongText() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendLongText() |

### 3.4 `COACTVWC.cbl` — Account view - multi-dataset read

*941 lines · 35 paragraphs/sections · primary target `AccountViewService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-MAIN` | PARA | 262 | `AccountViewService` | `main()` | Direct paragraph → method translation (control flow preserved) |
| `COMMON-RETURN` | PARA | 394 | `AccountViewService` | `commonReturn()` | Direct paragraph → method translation (control flow preserved) |
| `0000-MAIN-EXIT` | PARA | 408 | `AccountViewService` | `main() [return]` | PERFORM-THRU exit boundary -> collapses into return of main() |
| `0000-MAIN-EXIT` | PARA | 411 | `AccountViewService` | `main() [return]` | PERFORM-THRU exit boundary -> collapses into return of main() |
| `1000-SEND-MAP` | PARA | 416 | `AccountViewService` | `sendMap()` | Direct paragraph → method translation (control flow preserved) |
| `1000-SEND-MAP-EXIT` | PARA | 427 | `AccountViewService` | `sendMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendMap() |
| `1100-SCREEN-INIT` | PARA | 431 | `AccountViewService` | `screenInit()` | Direct paragraph → method translation (control flow preserved) |
| `1100-SCREEN-INIT-EXIT` | PARA | 457 | `AccountViewService` | `screenInit() [return]` | PERFORM-THRU exit boundary -> collapses into return of screenInit() |
| `1200-SETUP-SCREEN-VARS` | PARA | 460 | `AccountViewService` | `setupScreenVars()` | Direct paragraph → method translation (control flow preserved) |
| `1200-SETUP-SCREEN-VARS-EXIT` | PARA | 537 | `AccountViewService` | `setupScreenVars() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenVars() |
| `1300-SETUP-SCREEN-ATTRS` | PARA | 541 | `AccountViewService` | `setupScreenAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `1300-SETUP-SCREEN-ATTRS-EXIT` | PARA | 574 | `AccountViewService` | `setupScreenAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenAttrs() |
| `1400-SEND-SCREEN` | PARA | 577 | `AccountViewService` | `sendScreen()` | Direct paragraph → method translation (control flow preserved) |
| `1400-SEND-SCREEN-EXIT` | PARA | 592 | `AccountViewService` | `sendScreen() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendScreen() |
| `2000-PROCESS-INPUTS` | PARA | 596 | `AccountViewService` | `processInputs()` | Direct paragraph → method translation (control flow preserved) |
| `2000-PROCESS-INPUTS-EXIT` | PARA | 607 | `AccountViewService` | `processInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of processInputs() |
| `2100-RECEIVE-MAP` | PARA | 610 | `AccountViewService` | `receiveMap()` | Direct paragraph → method translation (control flow preserved) |
| `2100-RECEIVE-MAP-EXIT` | PARA | 619 | `AccountViewService` | `receiveMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of receiveMap() |
| `2200-EDIT-MAP-INPUTS` | PARA | 622 | `AccountViewService` | `editMapInputs()` | Direct paragraph → method translation (control flow preserved) |
| `2200-EDIT-MAP-INPUTS-EXIT` | PARA | 645 | `AccountViewService` | `editMapInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of editMapInputs() |
| `2210-EDIT-ACCOUNT` | PARA | 649 | `AccountViewService` | `editAccount()` | Direct paragraph → method translation (control flow preserved) |
| `2210-EDIT-ACCOUNT-EXIT` | PARA | 683 | `AccountViewService` | `editAccount() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAccount() |
| `9000-READ-ACCT` | PARA | 687 | `AccountViewService` | `readAcct()` | Direct paragraph → method translation (control flow preserved) |
| `9000-READ-ACCT-EXIT` | PARA | 720 | `AccountViewService` | `readAcct() [return]` | PERFORM-THRU exit boundary -> collapses into return of readAcct() |
| `9200-GETCARDXREF-BYACCT` | PARA | 723 | `AccountViewService` | `getcardxrefByacct()` | Direct paragraph → method translation (control flow preserved) |
| `9200-GETCARDXREF-BYACCT-EXIT` | PARA | 771 | `AccountViewService` | `getcardxrefByacct() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcardxrefByacct() |
| `9300-GETACCTDATA-BYACCT` | PARA | 774 | `AccountViewService` | `getacctdataByacct()` | Direct paragraph → method translation (control flow preserved) |
| `9300-GETACCTDATA-BYACCT-EXIT` | PARA | 821 | `AccountViewService` | `getacctdataByacct() [return]` | PERFORM-THRU exit boundary -> collapses into return of getacctdataByacct() |
| `9400-GETCUSTDATA-BYCUST` | PARA | 825 | `AccountViewService` | `getcustdataBycust()` | Direct paragraph → method translation (control flow preserved) |
| `9400-GETCUSTDATA-BYCUST-EXIT` | PARA | 870 | `AccountViewService` | `getcustdataBycust() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcustdataBycust() |
| `SEND-PLAIN-TEXT` | PARA | 877 | `AccountViewService` | `sendPlainText()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-PLAIN-TEXT-EXIT` | PARA | 888 | `AccountViewService` | `sendPlainText() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendPlainText() |
| `SEND-LONG-TEXT` | PARA | 896 | `AccountViewService` | `sendLongText()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-LONG-TEXT-EXIT` | PARA | 907 | `AccountViewService` | `sendLongText() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendLongText() |
| `ABEND-ROUTINE` | PARA | 916 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |

### 3.5 `COCRDSLC.cbl` — Card detail - single keyed read

*887 lines · 34 paragraphs/sections · primary target `CardDetailService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-MAIN` | PARA | 248 | `CardDetailService` | `main()` | Direct paragraph → method translation (control flow preserved) |
| `COMMON-RETURN` | PARA | 394 | `CardDetailService` | `commonReturn()` | Direct paragraph → method translation (control flow preserved) |
| `0000-MAIN-EXIT` | PARA | 408 | `CardDetailService` | `main() [return]` | PERFORM-THRU exit boundary -> collapses into return of main() |
| `1000-SEND-MAP` | PARA | 412 | `CardDetailService` | `sendMap()` | Direct paragraph → method translation (control flow preserved) |
| `1000-SEND-MAP-EXIT` | PARA | 423 | `CardDetailService` | `sendMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendMap() |
| `1100-SCREEN-INIT` | PARA | 427 | `CardDetailService` | `screenInit()` | Direct paragraph → method translation (control flow preserved) |
| `1100-SCREEN-INIT-EXIT` | PARA | 453 | `CardDetailService` | `screenInit() [return]` | PERFORM-THRU exit boundary -> collapses into return of screenInit() |
| `1200-SETUP-SCREEN-VARS` | PARA | 457 | `CardDetailService` | `setupScreenVars()` | Direct paragraph → method translation (control flow preserved) |
| `1200-SETUP-SCREEN-VARS-EXIT` | PARA | 499 | `CardDetailService` | `setupScreenVars() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenVars() |
| `1300-SETUP-SCREEN-ATTRS` | PARA | 502 | `CardDetailService` | `setupScreenAttrs()` | Direct paragraph → method translation (control flow preserved) |
| `1300-SETUP-SCREEN-ATTRS-EXIT` | PARA | 559 | `CardDetailService` | `setupScreenAttrs() [return]` | PERFORM-THRU exit boundary -> collapses into return of setupScreenAttrs() |
| `1400-SEND-SCREEN` | PARA | 563 | `CardDetailService` | `sendScreen()` | Direct paragraph → method translation (control flow preserved) |
| `1400-SEND-SCREEN-EXIT` | PARA | 578 | `CardDetailService` | `sendScreen() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendScreen() |
| `2000-PROCESS-INPUTS` | PARA | 582 | `CardDetailService` | `processInputs()` | Direct paragraph → method translation (control flow preserved) |
| `2000-PROCESS-INPUTS-EXIT` | PARA | 593 | `CardDetailService` | `processInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of processInputs() |
| `2100-RECEIVE-MAP` | PARA | 596 | `CardDetailService` | `receiveMap()` | Direct paragraph → method translation (control flow preserved) |
| `2100-RECEIVE-MAP-EXIT` | PARA | 605 | `CardDetailService` | `receiveMap() [return]` | PERFORM-THRU exit boundary -> collapses into return of receiveMap() |
| `2200-EDIT-MAP-INPUTS` | PARA | 608 | `CardDetailService` | `editMapInputs()` | Direct paragraph → method translation (control flow preserved) |
| `2200-EDIT-MAP-INPUTS-EXIT` | PARA | 643 | `CardDetailService` | `editMapInputs() [return]` | PERFORM-THRU exit boundary -> collapses into return of editMapInputs() |
| `2210-EDIT-ACCOUNT` | PARA | 647 | `CardDetailService` | `editAccount()` | Direct paragraph → method translation (control flow preserved) |
| `2210-EDIT-ACCOUNT-EXIT` | PARA | 681 | `CardDetailService` | `editAccount() [return]` | PERFORM-THRU exit boundary -> collapses into return of editAccount() |
| `2220-EDIT-CARD` | PARA | 685 | `CardDetailService` | `editCard()` | Direct paragraph → method translation (control flow preserved) |
| `2220-EDIT-CARD-EXIT` | PARA | 722 | `CardDetailService` | `editCard() [return]` | PERFORM-THRU exit boundary -> collapses into return of editCard() |
| `9000-READ-DATA` | PARA | 726 | `CardDetailService` | `readData()` | Direct paragraph → method translation (control flow preserved) |
| `9000-READ-DATA-EXIT` | PARA | 732 | `CardDetailService` | `readData() [return]` | PERFORM-THRU exit boundary -> collapses into return of readData() |
| `9100-GETCARD-BYACCTCARD` | PARA | 736 | `CardDetailService` | `getcardByacctcard()` | Direct paragraph → method translation (control flow preserved) |
| `9100-GETCARD-BYACCTCARD-EXIT` | PARA | 775 | `CardDetailService` | `getcardByacctcard() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcardByacctcard() |
| `9150-GETCARD-BYACCT` | PARA | 779 | `CardDetailService` | `getcardByacct()` | Direct paragraph → method translation (control flow preserved) |
| `9150-GETCARD-BYACCT-EXIT` | PARA | 810 | `CardDetailService` | `getcardByacct() [return]` | PERFORM-THRU exit boundary -> collapses into return of getcardByacct() |
| `SEND-LONG-TEXT` | PARA | 820 | `CardDetailService` | `sendLongText()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-LONG-TEXT-EXIT` | PARA | 831 | `CardDetailService` | `sendLongText() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendLongText() |
| `SEND-PLAIN-TEXT` | PARA | 838 | `CardDetailService` | `sendPlainText()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-PLAIN-TEXT-EXIT` | PARA | 849 | `CardDetailService` | `sendPlainText() [return]` | PERFORM-THRU exit boundary -> collapses into return of sendPlainText() |
| `ABEND-ROUTINE` | PARA | 857 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |

### 3.6 `COTRN02C.cbl` — Transaction add - auto-ID + confirmation flow

*783 lines · 18 paragraphs/sections · primary target `TransactionAddService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 107 | `TransactionAddService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 164 | `TransactionAddService` | `addTransaction()` | Validate + confirm add flow -> TransactionController.add() |
| `VALIDATE-INPUT-KEY-FIELDS` | PARA | 193 | `TransactionAddService` | `validateInputKeyFields()` | Direct paragraph → method translation (control flow preserved) |
| `VALIDATE-INPUT-DATA-FIELDS` | PARA | 235 | `TransactionAddService` | `validateInputDataFields()` | Direct paragraph → method translation (control flow preserved) |
| `ADD-TRANSACTION` | PARA | 442 | `TransactionAddService` | `addTransaction()` | Direct paragraph → method translation (control flow preserved) |
| `COPY-LAST-TRAN-DATA` | PARA | 471 | `TransactionAddService` | `copyLastTranData()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 500 | `TransactionAddService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-TRNADD-SCREEN` | PARA | 516 | `TransactionAddService` | `sendTrnaddScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-TRNADD-SCREEN` | PARA | 539 | `TransactionAddService` | `receiveTrnaddScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 552 | `TransactionAddService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `READ-CXACAIX-FILE` | PARA | 576 | `TransactionAddService` | `readCxacaixFile()` | Direct paragraph → method translation (control flow preserved) |
| `READ-CCXREF-FILE` | PARA | 609 | `TransactionAddService` | `readCcxrefFile()` | Direct paragraph → method translation (control flow preserved) |
| `STARTBR-TRANSACT-FILE` | PARA | 642 | `TransactionAddService` | `startbrTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `READPREV-TRANSACT-FILE` | PARA | 673 | `TransactionAddService` | `readprevTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `ENDBR-TRANSACT-FILE` | PARA | 702 | `TransactionAddService` | `endbrTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `WRITE-TRANSACT-FILE` | PARA | 711 | `TransactionAddService` | `writeTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `CLEAR-CURRENT-SCREEN` | PARA | 754 | `TransactionAddService` | `clearCurrentScreen()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 762 | `TransactionAddService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.7 `COTRN00C.cbl` — Transaction list - paginated browse (10 rows/page)

*699 lines · 16 paragraphs/sections · primary target `TransactionListService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 95 | `TransactionListService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 146 | `TransactionListService` | `processEnterKey()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PF7-KEY` | PARA | 234 | `TransactionListService` | `processPf7Key()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PF8-KEY` | PARA | 257 | `TransactionListService` | `processPf8Key()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PAGE-FORWARD` | PARA | 279 | `TransactionListService` | `processPageForward()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PAGE-BACKWARD` | PARA | 333 | `TransactionListService` | `processPageBackward()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-TRAN-DATA` | PARA | 381 | `TransactionListService` | `populateTranData()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-TRAN-DATA` | PARA | 450 | `TransactionListService` | `initializeTranData()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 510 | `TransactionListService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-TRNLST-SCREEN` | PARA | 527 | `TransactionListService` | `sendTrnlstScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-TRNLST-SCREEN` | PARA | 554 | `TransactionListService` | `receiveTrnlstScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 567 | `TransactionListService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `STARTBR-TRANSACT-FILE` | PARA | 591 | `TransactionListService` | `startbrTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `READNEXT-TRANSACT-FILE` | PARA | 624 | `TransactionListService` | `readnextTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `READPREV-TRANSACT-FILE` | PARA | 658 | `TransactionListService` | `readprevTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `ENDBR-TRANSACT-FILE` | PARA | 692 | `TransactionListService` | `endbrTransactFile()` | Direct paragraph → method translation (control flow preserved) |

### 3.8 `COUSR00C.cbl` — User list

*695 lines · 16 paragraphs/sections · primary target `UserListService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 98 | `UserListService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 149 | `UserListService` | `processEnterKey()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PF7-KEY` | PARA | 237 | `UserListService` | `processPf7Key()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PF8-KEY` | PARA | 260 | `UserListService` | `processPf8Key()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PAGE-FORWARD` | PARA | 282 | `UserListService` | `processPageForward()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-PAGE-BACKWARD` | PARA | 336 | `UserListService` | `processPageBackward()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-USER-DATA` | PARA | 384 | `UserListService` | `populateUserData()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-USER-DATA` | PARA | 446 | `UserListService` | `initializeUserData()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 506 | `UserListService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-USRLST-SCREEN` | PARA | 522 | `UserListService` | `sendUsrlstScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-USRLST-SCREEN` | PARA | 549 | `UserListService` | `receiveUsrlstScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 562 | `UserListService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `STARTBR-USER-SEC-FILE` | PARA | 586 | `UserListService` | `startbrUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `READNEXT-USER-SEC-FILE` | PARA | 619 | `UserListService` | `readnextUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `READPREV-USER-SEC-FILE` | PARA | 653 | `UserListService` | `readprevUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `ENDBR-USER-SEC-FILE` | PARA | 687 | `UserListService` | `endbrUserSecFile()` | Direct paragraph → method translation (control flow preserved) |

### 3.9 `CORPT00C.cbl` — Report submission - TDQ WRITEQ (online->batch bridge)

*649 lines · 10 paragraphs/sections · primary target `ReportSubmissionService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 163 | `ReportSubmissionService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 208 | `ReportSubmissionService` | `submitReport()` | Validate date range + dispatch -> ReportController.submit() |
| `SUBMIT-JOB-TO-INTRDR` | PARA | 462 | `ReportSubmissionService` | `submitReport()` | INTRDR job submit -> SQS message triggers TransactionReportJob (Observer / Pub-Sub) |
| `WIRTE-JOBSUB-TDQ` | PARA | 515 | `ReportSubmissionService` | `publishToQueue()` | TDQ WRITEQ (online->batch bridge) -> SQS FIFO send (carddemo-report-jobs.fifo). COBOL paragraph name retains its original (misspelled) spelling. |
| `RETURN-TO-PREV-SCREEN` | PARA | 540 | `ReportSubmissionService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-TRNRPT-SCREEN` | PARA | 556 | `ReportSubmissionService` | `sendTrnrptScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-CICS` | PARA | 585 | `ReportSubmissionService` | `returnToCics()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-TRNRPT-SCREEN` | PARA | 596 | `ReportSubmissionService` | `receiveTrnrptScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 609 | `ReportSubmissionService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 633 | `ReportSubmissionService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.10 `COBIL00C.cbl` — Bill payment

*572 lines · 16 paragraphs/sections · primary target `BillPaymentService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 99 | `BillPaymentService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 154 | `BillPaymentService` | `payBill()` | Bill payment posting -> AccountRepository.save() (`@Transactional`) |
| `GET-CURRENT-TIMESTAMP` | PARA | 249 | `BillPaymentService` | `getCurrentTimestamp()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 273 | `BillPaymentService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-BILLPAY-SCREEN` | PARA | 289 | `BillPaymentService` | `sendBillpayScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-BILLPAY-SCREEN` | PARA | 306 | `BillPaymentService` | `receiveBillpayScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 319 | `BillPaymentService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `READ-ACCTDAT-FILE` | PARA | 343 | `BillPaymentService` | `readAcctdatFile()` | Direct paragraph → method translation (control flow preserved) |
| `UPDATE-ACCTDAT-FILE` | PARA | 377 | `BillPaymentService` | `updateAcctdatFile()` | Direct paragraph → method translation (control flow preserved) |
| `READ-CXACAIX-FILE` | PARA | 408 | `BillPaymentService` | `readCxacaixFile()` | Direct paragraph → method translation (control flow preserved) |
| `STARTBR-TRANSACT-FILE` | PARA | 441 | `BillPaymentService` | `startbrTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `READPREV-TRANSACT-FILE` | PARA | 472 | `BillPaymentService` | `readprevTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `ENDBR-TRANSACT-FILE` | PARA | 501 | `BillPaymentService` | `endbrTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `WRITE-TRANSACT-FILE` | PARA | 510 | `BillPaymentService` | `writeTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `CLEAR-CURRENT-SCREEN` | PARA | 552 | `BillPaymentService` | `clearCurrentScreen()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 560 | `BillPaymentService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.11 `COUSR02C.cbl` — User update

*414 lines · 11 paragraphs/sections · primary target `UserUpdateService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 82 | `UserUpdateService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 143 | `UserUpdateService` | `processEnterKey()` | Direct paragraph → method translation (control flow preserved) |
| `UPDATE-USER-INFO` | PARA | 177 | `UserUpdateService` | `updateUserInfo()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 250 | `UserUpdateService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-USRUPD-SCREEN` | PARA | 266 | `UserUpdateService` | `sendUsrupdScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-USRUPD-SCREEN` | PARA | 283 | `UserUpdateService` | `receiveUsrupdScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 296 | `UserUpdateService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `READ-USER-SEC-FILE` | PARA | 320 | `UserUpdateService` | `readUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `UPDATE-USER-SEC-FILE` | PARA | 358 | `UserUpdateService` | `updateUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `CLEAR-CURRENT-SCREEN` | PARA | 395 | `UserUpdateService` | `clearCurrentScreen()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 403 | `UserUpdateService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.12 `COUSR03C.cbl` — User delete

*359 lines · 11 paragraphs/sections · primary target `UserDeleteService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 82 | `UserDeleteService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 142 | `UserDeleteService` | `processEnterKey()` | Direct paragraph → method translation (control flow preserved) |
| `DELETE-USER-INFO` | PARA | 174 | `UserDeleteService` | `deleteUserInfo()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 197 | `UserDeleteService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-USRDEL-SCREEN` | PARA | 213 | `UserDeleteService` | `sendUsrdelScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-USRDEL-SCREEN` | PARA | 230 | `UserDeleteService` | `receiveUsrdelScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 243 | `UserDeleteService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `READ-USER-SEC-FILE` | PARA | 267 | `UserDeleteService` | `readUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `DELETE-USER-SEC-FILE` | PARA | 305 | `UserDeleteService` | `deleteUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `CLEAR-CURRENT-SCREEN` | PARA | 341 | `UserDeleteService` | `clearCurrentScreen()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 349 | `UserDeleteService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.13 `COTRN01C.cbl` — Transaction detail - keyed read

*330 lines · 9 paragraphs/sections · primary target `TransactionDetailService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 86 | `TransactionDetailService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 144 | `TransactionDetailService` | `processEnterKey()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 197 | `TransactionDetailService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-TRNVIEW-SCREEN` | PARA | 213 | `TransactionDetailService` | `sendTrnviewScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-TRNVIEW-SCREEN` | PARA | 230 | `TransactionDetailService` | `receiveTrnviewScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 243 | `TransactionDetailService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `READ-TRANSACT-FILE` | PARA | 267 | `TransactionDetailService` | `readTransactFile()` | Direct paragraph → method translation (control flow preserved) |
| `CLEAR-CURRENT-SCREEN` | PARA | 301 | `TransactionDetailService` | `clearCurrentScreen()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 309 | `TransactionDetailService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.14 `COUSR01C.cbl` — User add

*299 lines · 9 paragraphs/sections · primary target `UserAddService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 71 | `UserAddService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 115 | `UserAddService` | `processEnterKey()` | Direct paragraph → method translation (control flow preserved) |
| `RETURN-TO-PREV-SCREEN` | PARA | 165 | `UserAddService` | `returnToPrevScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-USRADD-SCREEN` | PARA | 184 | `UserAddService` | `sendUsraddScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-USRADD-SCREEN` | PARA | 201 | `UserAddService` | `receiveUsraddScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 214 | `UserAddService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `WRITE-USER-SEC-FILE` | PARA | 238 | `UserAddService` | `writeUserSecFile()` | Direct paragraph → method translation (control flow preserved) |
| `CLEAR-CURRENT-SCREEN` | PARA | 279 | `UserAddService` | `clearCurrentScreen()` | Direct paragraph → method translation (control flow preserved) |
| `INITIALIZE-ALL-FIELDS` | PARA | 287 | `UserAddService` | `initializeAllFields()` | Direct paragraph → method translation (control flow preserved) |

### 3.15 `COMEN01C.cbl` — Main menu - 10-option routing

*282 lines · 7 paragraphs/sections · primary target `MainMenuService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 75 | `MainMenuService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 115 | `MainMenuService` | `route()` | 10-option routing -> target endpoint selection (COMEN02Y option table) |
| `RETURN-TO-SIGNON-SCREEN` | PARA | 170 | `MainMenuService` | `returnToSignonScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-MENU-SCREEN` | PARA | 182 | `MainMenuService` | `sendMenuScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-MENU-SCREEN` | PARA | 199 | `MainMenuService` | `receiveMenuScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 212 | `MainMenuService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `BUILD-MENU-OPTIONS` | PARA | 236 | `MainMenuService` | `buildMenuOptions()` | Direct paragraph → method translation (control flow preserved) |

### 3.16 `COADM01C.cbl` — Admin menu - 4-option routing

*268 lines · 7 paragraphs/sections · primary target `AdminMenuService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 75 | `AdminMenuService` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `PROCESS-ENTER-KEY` | PARA | 115 | `AdminMenuService` | `route()` | 4-option admin routing -> target endpoint selection (COADM02Y option table) |
| `RETURN-TO-SIGNON-SCREEN` | PARA | 160 | `AdminMenuService` | `returnToSignonScreen()` | Direct paragraph → method translation (control flow preserved) |
| `SEND-MENU-SCREEN` | PARA | 172 | `AdminMenuService` | `sendMenuScreen()` | Direct paragraph → method translation (control flow preserved) |
| `RECEIVE-MENU-SCREEN` | PARA | 189 | `AdminMenuService` | `receiveMenuScreen()` | Direct paragraph → method translation (control flow preserved) |
| `POPULATE-HEADER-INFO` | PARA | 202 | `AdminMenuService` | `populateHeaderInfo()` | Direct paragraph → method translation (control flow preserved) |
| `BUILD-MENU-OPTIONS` | PARA | 226 | `AdminMenuService` | `buildMenuOptions()` | Direct paragraph → method translation (control flow preserved) |

### 3.17 `COSGN00C.cbl` — Sign-on / authentication entry point

*260 lines · 6 paragraphs/sections · primary target `AuthenticationService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 73 | `AuthenticationService` | `authenticate()` | CICS entry orchestration -> AuthController.signIn() delegates to authenticate() |
| `PROCESS-ENTER-KEY` | PARA | 108 | `AuthenticationService` | `authenticate()` | **Representative row (AAP 0.7.3).** USRSEC lookup + BCrypt verify + JWT issuance; replaces RACF / plaintext |
| `SEND-SIGNON-SCREEN` | PARA | 145 | `AuthController` | `signInForm()` | BMS SEND COSGN0A -> 200/401 JSON response (no server screen state) |
| `SEND-PLAIN-TEXT` | PARA | 162 | `AuthController` | `errorResponse()` | CICS SEND TEXT -> plain error body / ProblemDetail |
| `POPULATE-HEADER-INFO` | PARA | 177 | `AuthenticationService` | `buildResponseHeader()` | Screen header fields -> SignOnResponse DTO metadata |
| `READ-USER-SEC-FILE` | PARA | 209 | `AuthenticationService` | `loadUser()` | USRSEC keyed read -> UserSecurityRepository.findBySecUsrId(); BCrypt compare (CSUSR01Y SEC-USR-PWD, C-003) |

### Batch and Utility Programs

### 3.18 `CBSTM03A.CBL` — Batch: statement generation main

*924 lines · 25 paragraphs/sections · primary target `StatementGenerationJob`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-START` | PARA | 296 | `StatementGenerationJob` | `start()` | Direct paragraph → method translation (control flow preserved) |
| `1000-MAINLINE` | PARA | 316 | `StatementGenerationJob` | `mainline()` | Direct paragraph → method translation (control flow preserved) |
| `9999-GOBACK` | PARA | 341 | `StatementGenerationJob` | `goback()` | Direct paragraph → method translation (control flow preserved) |
| `1000-XREFFILE-GET-NEXT` | PARA | 345 | `StatementGenerationJob` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source CardCrossReferenceRepository |
| `2000-CUSTFILE-GET` | PARA | 368 | `StatementGenerationJob` | `custfileGet()` | Direct paragraph → method translation (control flow preserved) |
| `3000-ACCTFILE-GET` | PARA | 392 | `StatementGenerationJob` | `acctfileGet()` | Direct paragraph → method translation (control flow preserved) |
| `4000-TRNXFILE-GET` | PARA | 416 | `StatementGenerationJob` | `trnxfileGet()` | Direct paragraph → method translation (control flow preserved) |
| `5000-CREATE-STATEMENT` | PARA | 458 | `StatementGenerationJob` | `createStatement()` | Direct paragraph → method translation (control flow preserved) |
| `5100-WRITE-HTML-HEADER` | PARA | 506 | `StatementGenerationJob` | `writeHtmlHeader()` | Direct paragraph → method translation (control flow preserved) |
| `5100-EXIT` | PARA | 554 | `StatementGenerationJob` | `5100() [return]` | PERFORM-THRU exit boundary -> collapses into return of 5100() |
| `5200-WRITE-HTML-NMADBS` | PARA | 558 | `StatementGenerationJob` | `writeHtmlNmadbs()` | Direct paragraph → method translation (control flow preserved) |
| `5200-EXIT` | PARA | 671 | `StatementGenerationJob` | `5200() [return]` | PERFORM-THRU exit boundary -> collapses into return of 5200() |
| `6000-WRITE-TRANS` | PARA | 675 | `StatementGenerationJob` | `writeTrans()` | Direct paragraph → method translation (control flow preserved) |
| `8100-FILE-OPEN` | PARA | 726 | `StatementGenerationJob` | `fileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by JPA repository / S3 |
| `8100-TRNXFILE-OPEN` | PARA | 730 | `StatementGenerationJob` | `trnxfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionRepository |
| `8200-XREFFILE-OPEN` | PARA | 765 | `StatementGenerationJob` | `xreffileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardCrossReferenceRepository |
| `8300-CUSTFILE-OPEN` | PARA | 783 | `StatementGenerationJob` | `custfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CustomerRepository |
| `8400-ACCTFILE-OPEN` | PARA | 801 | `StatementGenerationJob` | `acctfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by AccountRepository |
| `8500-READTRNX-READ` | PARA | 818 | `StatementGenerationJob` | `readtrnxRead()` | Direct paragraph → method translation (control flow preserved) |
| `8599-EXIT` | PARA | 849 | `StatementGenerationJob` | `8599() [return]` | PERFORM-THRU exit boundary -> collapses into return of 8599() |
| `9100-TRNXFILE-CLOSE` | PARA | 856 | `StatementGenerationJob` | `trnxfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionRepository |
| `9200-XREFFILE-CLOSE` | PARA | 873 | `StatementGenerationJob` | `xreffileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardCrossReferenceRepository |
| `9300-CUSTFILE-CLOSE` | PARA | 889 | `StatementGenerationJob` | `custfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CustomerRepository |
| `9400-ACCTFILE-CLOSE` | PARA | 905 | `StatementGenerationJob` | `acctfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by AccountRepository |
| `9999-ABEND-PROGRAM` | PARA | 921 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |

### 3.19 `CBTRN02C.cbl` — Batch: daily transaction posting - 4-stage validation cascade

*731 lines · 26 paragraphs/sections · primary target `DailyTransactionPostingJob`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-DALYTRAN-OPEN` | PARA | 236 | `DailyTransactionPostingJob` | `dalytranOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by DailyTransactionRepository |
| `0100-TRANFILE-OPEN` | PARA | 254 | `DailyTransactionPostingJob` | `tranfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionRepository |
| `0200-XREFFILE-OPEN` | PARA | 273 | `DailyTransactionPostingJob` | `xreffileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardCrossReferenceRepository |
| `0300-DALYREJS-OPEN` | PARA | 291 | `DailyTransactionPostingJob` | `dalyrejsOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by RejectWriter |
| `0400-ACCTFILE-OPEN` | PARA | 309 | `DailyTransactionPostingJob` | `acctfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by AccountRepository |
| `0500-TCATBALF-OPEN` | PARA | 327 | `DailyTransactionPostingJob` | `tcatbalfOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionCategoryBalanceRepository |
| `1000-DALYTRAN-GET-NEXT` | PARA | 345 | `DailyTransactionPostingJob` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source DailyTransactionRepository |
| `1500-VALIDATE-TRAN` | PARA | 370 | `TransactionPostingProcessor` | `validate()` | **Representative row (AAP 0.7.3).** 4-stage validation cascade; reject codes 100-109 (Strategy pattern) |
| `1500-A-LOOKUP-XREF` | PARA | 380 | `TransactionPostingProcessor` | `validateXref()` | XREF lookup stage -> reject if card not found; CardCrossReferenceRepository |
| `1500-B-LOOKUP-ACCT` | PARA | 393 | `TransactionPostingProcessor` | `validateAccount()` | ACCT lookup + credit-limit/expiry stage -> reject codes; AccountRepository |
| `2000-POST-TRANSACTION` | PARA | 424 | `TransactionPostingProcessor` | `process()` | Approved posting -> balance + cycle updates; chunk processor output |
| `2500-WRITE-REJECT-REC` | PARA | 446 | `RejectWriter` | `write()` | Rejected record (reason 100-109) -> S3 rejection object (DALYREJS) |
| `2700-UPDATE-TCATBAL` | PARA | 467 | `TransactionPostingProcessor` | `updateCategoryBalance()` | TCATBAL upsert -> TransactionCategoryBalanceRepository (composite key) |
| `2700-A-CREATE-TCATBAL-REC` | PARA | 503 | `TransactionPostingProcessor` | `createCategoryBalance()` | TCATBAL insert branch -> save new TransactionCategoryBalance |
| `2700-B-UPDATE-TCATBAL-REC` | PARA | 526 | `TransactionPostingProcessor` | `incrementCategoryBalance()` | TCATBAL update branch -> BigDecimal add (compareTo semantics) |
| `2800-UPDATE-ACCOUNT-REC` | PARA | 545 | `TransactionPostingProcessor` | `updateAccountBalance()` | ACCTDAT balance/cycle update -> AccountRepository.save() (`@Transactional`) |
| `2900-WRITE-TRANSACTION-FILE` | PARA | 562 | `TransactionWriter` | `write()` | Posted transaction -> TransactionRepository bulk insert + S3 staging |
| `9000-DALYTRAN-CLOSE` | PARA | 582 | `DailyTransactionPostingJob` | `dalytranClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by DailyTransactionRepository |
| `9100-TRANFILE-CLOSE` | PARA | 600 | `DailyTransactionPostingJob` | `tranfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionRepository |
| `9200-XREFFILE-CLOSE` | PARA | 619 | `DailyTransactionPostingJob` | `xreffileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardCrossReferenceRepository |
| `9300-DALYREJS-CLOSE` | PARA | 637 | `DailyTransactionPostingJob` | `dalyrejsClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by RejectWriter |
| `9400-ACCTFILE-CLOSE` | PARA | 655 | `DailyTransactionPostingJob` | `acctfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by AccountRepository |
| `9500-TCATBALF-CLOSE` | PARA | 674 | `DailyTransactionPostingJob` | `tcatbalfClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionCategoryBalanceRepository |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | PARA | 692 | `DailyTransactionPostingJob` | `currentTimestamp()` | DB2 timestamp helper -> java.time formatting (LocalDateTime/OffsetDateTime) |
| `9999-ABEND-PROGRAM` | PARA | 707 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `9910-DISPLAY-IO-STATUS` | PARA | 714 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.20 `CBACT04C.cbl` — Batch: interest calculation

*652 lines · 22 paragraphs/sections · primary target `InterestCalculationJob`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-TCATBALF-OPEN` | PARA | 234 | `InterestCalculationJob` | `tcatbalfOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionCategoryBalanceRepository |
| `0100-XREFFILE-OPEN` | PARA | 252 | `InterestCalculationJob` | `xreffileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardCrossReferenceRepository |
| `0200-DISCGRP-OPEN` | PARA | 270 | `InterestCalculationJob` | `discgrpOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by DisclosureGroupRepository |
| `0300-ACCTFILE-OPEN` | PARA | 289 | `InterestCalculationJob` | `acctfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by AccountRepository |
| `0400-TRANFILE-OPEN` | PARA | 307 | `InterestCalculationJob` | `tranfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionRepository |
| `1000-TCATBALF-GET-NEXT` | PARA | 325 | `InterestCalculationJob` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source TransactionCategoryBalanceRepository |
| `1050-UPDATE-ACCOUNT` | PARA | 350 | `InterestCalculationProcessor` | `applyToAccount()` | Account balance update with computed interest -> AccountRepository.save() |
| `1100-GET-ACCT-DATA` | PARA | 372 | `InterestCalculationJob` | `getAcctData()` | Direct paragraph → method translation (control flow preserved) |
| `1110-GET-XREF-DATA` | PARA | 393 | `InterestCalculationJob` | `getXrefData()` | Direct paragraph → method translation (control flow preserved) |
| `1200-GET-INTEREST-RATE` | PARA | 415 | `InterestCalculationProcessor` | `resolveInterestRate()` | DISCGRP keyed read -> DisclosureGroupRepository (composite key) |
| `1200-A-GET-DEFAULT-INT-RATE` | PARA | 443 | `InterestCalculationProcessor` | `resolveDefaultRate()` | `DEFAULT` disclosure-group fallback preserved (no algebraic simplification) |
| `1300-COMPUTE-INTEREST` | PARA | 462 | `InterestCalculationProcessor` | `computeInterest()` | `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200` -> `BigDecimal.divide(.., 2, RoundingMode.HALF_EVEN)` |
| `1300-B-WRITE-TX` | PARA | 473 | `InterestCalculationJob` | `writeTx()` | Direct paragraph → method translation (control flow preserved) |
| `1400-COMPUTE-FEES` | PARA | 518 | `InterestCalculationJob` | `computeFees()` | Direct paragraph → method translation (control flow preserved) |
| `9000-TCATBALF-CLOSE` | PARA | 522 | `InterestCalculationJob` | `tcatbalfClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionCategoryBalanceRepository |
| `9100-XREFFILE-CLOSE` | PARA | 541 | `InterestCalculationJob` | `xreffileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardCrossReferenceRepository |
| `9200-DISCGRP-CLOSE` | PARA | 559 | `InterestCalculationJob` | `discgrpClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by DisclosureGroupRepository |
| `9300-ACCTFILE-CLOSE` | PARA | 577 | `InterestCalculationJob` | `acctfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by AccountRepository |
| `9400-TRANFILE-CLOSE` | PARA | 595 | `InterestCalculationJob` | `tranfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionRepository |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | PARA | 613 | `InterestCalculationJob` | `currentTimestamp()` | DB2 timestamp helper -> java.time formatting (LocalDateTime/OffsetDateTime) |
| `9999-ABEND-PROGRAM` | PARA | 628 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `9910-DISPLAY-IO-STATUS` | PARA | 635 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.21 `CBTRN03C.cbl` — Batch: transaction report

*649 lines · 26 paragraphs/sections · primary target `TransactionReportJob`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0550-DATEPARM-READ` | PARA | 220 | `TransactionReportJob` | `dateparmRead()` | Direct paragraph → method translation (control flow preserved) |
| `1000-TRANFILE-GET-NEXT` | PARA | 248 | `TransactionReportJob` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source TransactionRepository |
| `1100-WRITE-TRANSACTION-REPORT` | PARA | 274 | `TransactionReportJob` | `writeTransactionReport()` | Direct paragraph → method translation (control flow preserved) |
| `1110-WRITE-PAGE-TOTALS` | PARA | 293 | `TransactionReportJob` | `writePageTotals()` | Direct paragraph → method translation (control flow preserved) |
| `1120-WRITE-ACCOUNT-TOTALS` | PARA | 306 | `TransactionReportJob` | `writeAccountTotals()` | Direct paragraph → method translation (control flow preserved) |
| `1110-WRITE-GRAND-TOTALS` | PARA | 318 | `TransactionReportJob` | `writeGrandTotals()` | Direct paragraph → method translation (control flow preserved) |
| `1120-WRITE-HEADERS` | PARA | 324 | `TransactionReportJob` | `writeHeaders()` | Direct paragraph → method translation (control flow preserved) |
| `1111-WRITE-REPORT-REC` | PARA | 343 | `TransactionReportJob` | `writeReportRec()` | Direct paragraph → method translation (control flow preserved) |
| `1120-WRITE-DETAIL` | PARA | 361 | `TransactionReportJob` | `writeDetail()` | Direct paragraph → method translation (control flow preserved) |
| `0000-TRANFILE-OPEN` | PARA | 376 | `TransactionReportJob` | `tranfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionRepository |
| `0100-REPTFILE-OPEN` | PARA | 394 | `TransactionReportJob` | `reptfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by (S3 report object) |
| `0200-CARDXREF-OPEN` | PARA | 412 | `TransactionReportJob` | `cardxrefOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardCrossReferenceRepository |
| `0300-TRANTYPE-OPEN` | PARA | 430 | `TransactionReportJob` | `trantypeOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionTypeRepository |
| `0400-TRANCATG-OPEN` | PARA | 448 | `TransactionReportJob` | `trancatgOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionCategoryRepository |
| `0500-DATEPARM-OPEN` | PARA | 466 | `TransactionReportJob` | `dateparmOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by JobParameters (date range) |
| `1500-A-LOOKUP-XREF` | PARA | 484 | `TransactionReportJob` | `lookupXref()` | Direct paragraph → method translation (control flow preserved) |
| `1500-B-LOOKUP-TRANTYPE` | PARA | 494 | `TransactionReportJob` | `lookupTrantype()` | Direct paragraph → method translation (control flow preserved) |
| `1500-C-LOOKUP-TRANCATG` | PARA | 504 | `TransactionReportJob` | `lookupTrancatg()` | Direct paragraph → method translation (control flow preserved) |
| `9000-TRANFILE-CLOSE` | PARA | 514 | `TransactionReportJob` | `tranfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionRepository |
| `9100-REPTFILE-CLOSE` | PARA | 532 | `TransactionReportJob` | `reptfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by (S3 report object) |
| `9200-CARDXREF-CLOSE` | PARA | 551 | `TransactionReportJob` | `cardxrefClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardCrossReferenceRepository |
| `9300-TRANTYPE-CLOSE` | PARA | 569 | `TransactionReportJob` | `trantypeClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionTypeRepository |
| `9400-TRANCATG-CLOSE` | PARA | 587 | `TransactionReportJob` | `trancatgClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionCategoryRepository |
| `9500-DATEPARM-CLOSE` | PARA | 605 | `TransactionReportJob` | `dateparmClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by JobParameters (date range) |
| `9999-ABEND-PROGRAM` | PARA | 626 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `9910-DISPLAY-IO-STATUS` | PARA | 633 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.22 `CBTRN01C.cbl` — Batch: daily transaction reader

*491 lines · 18 paragraphs/sections · primary target `DailyTransactionReader`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `MAIN-PARA` | PARA | 155 | `DailyTransactionReader` | `mainPara()` | Direct paragraph → method translation (control flow preserved) |
| `1000-DALYTRAN-GET-NEXT` | PARA | 202 | `DailyTransactionReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source DailyTransactionRepository |
| `2000-LOOKUP-XREF` | PARA | 227 | `DailyTransactionReader` | `lookupXref()` | Direct paragraph → method translation (control flow preserved) |
| `3000-READ-ACCOUNT` | PARA | 241 | `DailyTransactionReader` | `readAccount()` | Direct paragraph → method translation (control flow preserved) |
| `0000-DALYTRAN-OPEN` | PARA | 252 | `DailyTransactionReader` | `dalytranOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by DailyTransactionRepository |
| `0100-CUSTFILE-OPEN` | PARA | 271 | `DailyTransactionReader` | `custfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CustomerRepository |
| `0200-XREFFILE-OPEN` | PARA | 289 | `DailyTransactionReader` | `xreffileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardCrossReferenceRepository |
| `0300-CARDFILE-OPEN` | PARA | 307 | `DailyTransactionReader` | `cardfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardRepository |
| `0400-ACCTFILE-OPEN` | PARA | 325 | `DailyTransactionReader` | `acctfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by AccountRepository |
| `0500-TRANFILE-OPEN` | PARA | 343 | `DailyTransactionReader` | `tranfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by TransactionRepository |
| `9000-DALYTRAN-CLOSE` | PARA | 361 | `DailyTransactionReader` | `dalytranClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by DailyTransactionRepository |
| `9100-CUSTFILE-CLOSE` | PARA | 379 | `DailyTransactionReader` | `custfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CustomerRepository |
| `9200-XREFFILE-CLOSE` | PARA | 397 | `DailyTransactionReader` | `xreffileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardCrossReferenceRepository |
| `9300-CARDFILE-CLOSE` | PARA | 415 | `DailyTransactionReader` | `cardfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardRepository |
| `9400-ACCTFILE-CLOSE` | PARA | 433 | `DailyTransactionReader` | `acctfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by AccountRepository |
| `9500-TRANFILE-CLOSE` | PARA | 451 | `DailyTransactionReader` | `tranfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by TransactionRepository |
| `Z-ABEND-PROGRAM` | PARA | 469 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `Z-DISPLAY-IO-STATUS` | PARA | 476 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.23 `CBSTM03B.CBL` — Batch: statement generation sub (file service)

*230 lines · 14 paragraphs/sections · primary target `StatementWriter`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `0000-START` | PARA | 116 | `StatementWriter` | `start()` | Direct paragraph → method translation (control flow preserved) |
| `9999-GOBACK` | PARA | 130 | `StatementWriter` | `goback()` | Direct paragraph → method translation (control flow preserved) |
| `1000-TRNXFILE-PROC` | PARA | 133 | `StatementWriter` | `trnxfileProc()` | Direct paragraph → method translation (control flow preserved) |
| `1900-EXIT` | PARA | 151 | `StatementWriter` | `1900() [return]` | PERFORM-THRU exit boundary -> collapses into return of 1900() |
| `1999-EXIT` | PARA | 154 | `StatementWriter` | `1999() [return]` | PERFORM-THRU exit boundary -> collapses into return of 1999() |
| `2000-XREFFILE-PROC` | PARA | 157 | `StatementWriter` | `xreffileProc()` | Direct paragraph → method translation (control flow preserved) |
| `2900-EXIT` | PARA | 175 | `StatementWriter` | `2900() [return]` | PERFORM-THRU exit boundary -> collapses into return of 2900() |
| `2999-EXIT` | PARA | 178 | `StatementWriter` | `2999() [return]` | PERFORM-THRU exit boundary -> collapses into return of 2999() |
| `3000-CUSTFILE-PROC` | PARA | 181 | `StatementWriter` | `custfileProc()` | Direct paragraph → method translation (control flow preserved) |
| `3900-EXIT` | PARA | 200 | `StatementWriter` | `3900() [return]` | PERFORM-THRU exit boundary -> collapses into return of 3900() |
| `3999-EXIT` | PARA | 203 | `StatementWriter` | `3999() [return]` | PERFORM-THRU exit boundary -> collapses into return of 3999() |
| `4000-ACCTFILE-PROC` | PARA | 206 | `StatementWriter` | `acctfileProc()` | Direct paragraph → method translation (control flow preserved) |
| `4900-EXIT` | PARA | 225 | `StatementWriter` | `4900() [return]` | PERFORM-THRU exit boundary -> collapses into return of 4900() |
| `4999-EXIT` | PARA | 228 | `StatementWriter` | `4999() [return]` | PERFORM-THRU exit boundary -> collapses into return of 4999() |

### 3.24 `CBACT01C.cbl` — Batch: account file reader

*193 lines · 6 paragraphs/sections · primary target `AccountFileReader`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `1000-ACCTFILE-GET-NEXT` | PARA | 92 | `AccountFileReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source AccountRepository |
| `1100-DISPLAY-ACCT-RECORD` | PARA | 118 | `AccountFileReader` | `displayAcctRecord()` | Direct paragraph → method translation (control flow preserved) |
| `0000-ACCTFILE-OPEN` | PARA | 133 | `AccountFileReader` | `acctfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by AccountRepository |
| `9000-ACCTFILE-CLOSE` | PARA | 151 | `AccountFileReader` | `acctfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by AccountRepository |
| `9999-ABEND-PROGRAM` | PARA | 169 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `9910-DISPLAY-IO-STATUS` | PARA | 176 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.25 `CBACT02C.cbl` — Batch: card file reader

*178 lines · 5 paragraphs/sections · primary target `CardFileReader`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `1000-CARDFILE-GET-NEXT` | PARA | 92 | `CardFileReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source CardRepository |
| `0000-CARDFILE-OPEN` | PARA | 118 | `CardFileReader` | `cardfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardRepository |
| `9000-CARDFILE-CLOSE` | PARA | 136 | `CardFileReader` | `cardfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardRepository |
| `9999-ABEND-PROGRAM` | PARA | 154 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `9910-DISPLAY-IO-STATUS` | PARA | 161 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.26 `CBACT03C.cbl` — Batch: cross-reference file reader

*178 lines · 5 paragraphs/sections · primary target `CardCrossReferenceReader`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `1000-XREFFILE-GET-NEXT` | PARA | 92 | `CardCrossReferenceReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source CardCrossReferenceRepository |
| `0000-XREFFILE-OPEN` | PARA | 118 | `CardCrossReferenceReader` | `xreffileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CardCrossReferenceRepository |
| `9000-XREFFILE-CLOSE` | PARA | 136 | `CardCrossReferenceReader` | `xreffileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CardCrossReferenceRepository |
| `9999-ABEND-PROGRAM` | PARA | 154 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `9910-DISPLAY-IO-STATUS` | PARA | 161 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.27 `CBCUS01C.cbl` — Batch: customer file reader

*178 lines · 5 paragraphs/sections · primary target `CustomerFileReader`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `1000-CUSTFILE-GET-NEXT` | PARA | 92 | `CustomerFileReader` | `read()` | Sequential READ NEXT -> ItemReader.read() (chunk-oriented); source CustomerRepository |
| `0000-CUSTFILE-OPEN` | PARA | 118 | `CustomerFileReader` | `custfileOpen()` | VSAM OPEN -> Spring Batch ItemStream.open(); backed by CustomerRepository |
| `9000-CUSTFILE-CLOSE` | PARA | 136 | `CustomerFileReader` | `custfileClose()` | VSAM CLOSE -> Spring Batch ItemStream.close(); backed by CustomerRepository |
| `Z-ABEND-PROGRAM` | PARA | 154 | `CardDemoException` | `abend()` | COBOL ABEND -> throw CardDemoException; Spring Batch marks step/job FAILED (ExitStatus) |
| `Z-DISPLAY-IO-STATUS` | PARA | 161 | `FileStatusMapper` | `describeStatus()` | FILE STATUS display -> FileStatusMapper.describeStatus() + structured log (Observability) |

### 3.28 `CSUTLDTC.cbl` — Date validation subprogram (CEEDAYS); called by online + batch

*157 lines · 2 paragraphs/sections · primary target `DateValidationService`*

| Paragraph / Section | Type | Src Line | Java Class | Java Method | Mapping Notes |
|---|---|---:|---|---|---|
| `A000-MAIN` | PARA | 103 | `DateValidationService` | `validateDate()` | CALL `CEEDAYS` -> java.time.LocalDate parse/validate; LE severity -> validation result |
| `A000-MAIN-EXIT` | PARA | 152 | `DateValidationService` | `a000Main() [return]` | PERFORM-THRU exit boundary -> collapses into return of a000Main() |

---

## 4. Copybook → Java Type Matrix

All **28 copybooks** under `app/cpy/` map to JPA entities, DTOs, enums, composite-key classes, validation resources, or shared utilities. Record layouts, PIC clauses, and record lengths are preserved per the decimal-precision rules (AAP §0.8.2).

| # | Copybook | Purpose / Layout | Java Target(s) | Kind | Notes |
|---|---|---|---|---|---|
| 1 | `CVACT01Y.cpy` | Account record layout (300B); five PIC S9(10)V99 balance fields | `model/entity/Account.java` | JPA @Entity | `BigDecimal` balances (scale 2) + `@Version` optimistic lock |
| 2 | `CVACT02Y.cpy` | Card record layout (150B) | `model/entity/Card.java` | JPA @Entity | FK to Account; card number key |
| 3 | `CVACT03Y.cpy` | Card cross-reference layout (50B) | `model/entity/CardCrossReference.java` | JPA @Entity | Card↔Account↔Customer linkage; backs CXACAIX alternate index |
| 4 | `CVCUS01Y.cpy` | Customer record layout (500B) | `model/entity/Customer.java` | JPA @Entity | SSN handling; primary customer entity |
| 5 | `CUSTREC.cpy` | Alternative customer record structure | `model/entity/Customer.java` | JPA @Entity (shared) | Same logical entity as CVCUS01Y; alternate field framing |
| 6 | `CVTRA01Y.cpy` | Transaction category balance (composite key) | `model/entity/TransactionCategoryBalance.java + model/key/TransactionCategoryBalanceId.java` | JPA @Entity + @EmbeddedId | Composite key (acct + type + category) |
| 7 | `CVTRA02Y.cpy` | Disclosure group record (composite key, interest rate) | `model/entity/DisclosureGroup.java + model/key/DisclosureGroupId.java` | JPA @Entity + @EmbeddedId | `BigDecimal` interest rate; `DEFAULT` group fallback |
| 8 | `CVTRA03Y.cpy` | Transaction type record (60B) | `model/entity/TransactionType.java` | JPA @Entity | Reference lookup table |
| 9 | `CVTRA04Y.cpy` | Transaction category record (composite key) | `model/entity/TransactionCategory.java + model/key/TransactionCategoryId.java` | JPA @Entity + @EmbeddedId | Composite key (type + category) |
| 10 | `CVTRA05Y.cpy` | Transaction record (350B) | `model/entity/Transaction.java` | JPA @Entity | `BigDecimal` TRAN-AMT (scale 2) |
| 11 | `CVTRA06Y.cpy` | Daily transaction staging record (350B) | `model/entity/DailyTransaction.java` | JPA @Entity | Staging entity for posting pipeline |
| 12 | `CVTRA07Y.cpy` | Transaction report record | `model/dto/TransactionReportRow (report row)` | DTO / report line | Consumed by TransactionReportJob → S3 report object |
| 13 | `CVCRD01Y.cpy` | Card record descriptor | `model/entity/Card.java (descriptor view)` | JPA @Entity (shared) | Descriptor fields fold into the Card entity |
| 14 | `COCOM01Y.cpy` | CARDDEMO-COMMAREA — central session state | `model/dto/CommArea.java` | DTO + JWT claims | Conversational COMMAREA → stateless request/response DTO + JWT claims |
| 15 | `CSUSR01Y.cpy` | User security record (80B); SEC-USR-PWD plaintext | `model/entity/UserSecurity.java + model/enums/UserType.java` | JPA @Entity + enum | Plaintext password (C-003) → **BCrypt**; CDEMO-USRTYP → `UserType` (ADMIN/USER) |
| 16 | `COMEN02Y.cpy` | Main menu option table (10 entries) | `model/dto/MenuOption.java` | DTO (data) | Backs MainMenuService 10-option routing |
| 17 | `COADM02Y.cpy` | Admin menu option table (4 entries) | `model/dto/MenuOption.java` | DTO (data) | Backs AdminMenuService 4-option routing |
| 18 | `CSLKPCDY.cpy` | Validation lookup: NANPA area codes, state codes, ZIP prefixes | `resources/validation/*.json + service/shared/ValidationLookupService.java` | JSON resource + service | nanpa-area-codes / us-state-codes / state-zip-prefixes JSON |
| 19 | `CSUTLDPY.cpy` | Date validation parameters | `service/shared/DateValidationService.java` | Service (params) | Parameter structure for CEEDAYS replacement |
| 20 | `CSUTLDWY.cpy` | Date validation work area | `service/shared/DateValidationService.java` | Service (work area) | Work-area fields → local variables in DateValidationService |
| 21 | `CSDAT01Y.cpy` | Date structure definitions | `model/dto (date structure)` | DTO / value type | `java.time.LocalDate`-based date fields |
| 22 | `CSMSG01Y.cpy` | Standard message / abend structures | `exception/* + ProblemDetail` | Exception / message | Standard messages → exception messages / RFC-7807 ProblemDetail |
| 23 | `CSMSG02Y.cpy` | Additional message structures | `exception/* + ProblemDetail` | Exception / message | Supplementary messages → exception detail |
| 24 | `CSSTRPFY.cpy` | String / PF-key processing helpers | `shared string utilities` | Utility | String helpers → Java `String` utils; PF-key/AID handling → REST endpoint routing |
| 25 | `CSSETATY.cpy` | Screen attribute setting helpers | `jakarta.validation @Valid constraints` | Validation | Field attributes → bean-validation constraints on DTOs |
| 26 | `COTTL01Y.cpy` | Report title line definitions | `report title constants (batch/report)` | Constants / template | Title lines used by TransactionReportJob / StatementGenerationJob |
| 27 | `COSTM01.CPY` | Statement output format | `statement template (StatementGenerationJob)` | Template | Text/HTML statement layout (template method) |
| 28 | `UNUSED1Y.cpy` | Unused copybook (carried for completeness) | `— (no target)` | None | Not referenced by any program; intentionally unmapped |

> **28 copybooks** mapped (27 with Java targets; `UNUSED1Y.cpy` is intentionally unmapped as it is referenced by no program).

---

## 5. JCL → Spring Batch / Flyway Matrix

All **29 JCL jobs** under `app/jcl/` and **2 PROCs** under `app/proc/` map to Spring Batch jobs/steps, Flyway migrations, AWS configuration, or batch lifecycle. The 5-stage pipeline order (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT) and JCL `COND` condition-code logic are preserved via `ExitStatus` + `JobExecutionDecider` (AAP §0.8.5).

### 5.1 JCL Jobs (29)

| # | JCL Job | Category | Java / Target | Notes |
|---|---|---|---|---|
| 1 | `ACCTFILE.jcl` | VSAM provisioning | `V1__create_schema.sql (account) + AccountRepository` | DEFINE CLUSTER (ACCTDAT KSDS) → PostgreSQL table + JPA repository |
| 2 | `CARDFILE.jcl` | VSAM provisioning | `V1__create_schema.sql (card) + CardRepository` | DEFINE CLUSTER (CARDDAT KSDS) → table + repository |
| 3 | `CUSTFILE.jcl` | VSAM provisioning | `V1__create_schema.sql (customer) + CustomerRepository` | DEFINE CLUSTER (CUSTDAT KSDS) → table + repository |
| 4 | `XREFFILE.jcl` | VSAM provisioning | `V1__create_schema.sql (card_xref) + V2__create_indexes.sql + CardCrossReferenceRepository` | KSDS + **CXACAIX** alternate index → table + secondary index |
| 5 | `TRANFILE.jcl` | VSAM provisioning | `V1__create_schema.sql (transaction) + TransactionRepository` | DEFINE CLUSTER (TRANSACT KSDS) → table + repository |
| 6 | `TCATBALF.jcl` | VSAM provisioning | `V1__create_schema.sql (tran_cat_balance) + TransactionCategoryBalanceRepository` | Composite-key KSDS → table + @EmbeddedId repository |
| 7 | `DISCGRP.jcl` | VSAM provisioning | `V1__create_schema.sql (disclosure_group) + DisclosureGroupRepository` | Disclosure-group KSDS → table + repository |
| 8 | `TRANCATG.jcl` | VSAM provisioning | `V1__create_schema.sql (tran_category) + TransactionCategoryRepository` | Reference KSDS → table + repository |
| 9 | `TRANTYPE.jcl` | VSAM provisioning | `V1__create_schema.sql (tran_type) + TransactionTypeRepository` | Reference KSDS → table + repository |
| 10 | `DUSRSECJ.jcl` | VSAM provisioning + seed | `V1__create_schema.sql (user_security) + V3__seed_data.sql + UserSecurityRepository` | USRSEC KSDS define + initial users → table + seed (passwords BCrypt-hashed) |
| 11 | `DEFCUST.jcl` | VSAM provisioning | `V1__create_schema.sql (customer) + CustomerRepository` | Customer dataset define variant → same customer table |
| 12 | `TRANIDX.jcl` | Alternate index | `V2__create_indexes.sql (TRANSACT AIX)` | Transaction alternate index → PostgreSQL secondary index |
| 13 | `DEFGDGB.jcl` | GDG definition | `config/AwsConfig.java + localstack-init/init-aws.sh` | Generation-data-group → **S3** versioned buckets (carddemo-batch-input/-output/-statements) |
| 14 | `POSTTRAN.jcl` | Batch business | `batch/jobs/DailyTransactionPostingJob.java` | Daily posting (runs CBTRN02C) → Spring Batch job; 4-stage validation + condition codes |
| 15 | `INTCALC.jcl` | Batch business | `batch/jobs/InterestCalculationJob.java` | Interest calc (runs CBACT04C) → job; PARM mapping + interest formula |
| 16 | `COMBTRAN.jcl` | Batch business | `batch/jobs/CombineTransactionsJob.java + CombineTransactionsProcessor` | **DFSORT** SORT + IDCAMS **REPRO** → Java `Comparator` + bulk JPA insert (no COBOL program) |
| 17 | `CREASTMT.JCL` | Batch business | `batch/jobs/StatementGenerationJob.java` | Statement creation (runs CBSTM03A/B) → job; text + HTML to S3 (stage 4a) |
| 18 | `TRANREPT.jcl` | Batch business | `batch/jobs/TransactionReportJob.java + TransactionReportProcessor` | Transaction report (runs CBTRN03C) → job; date-filtered report to S3 (stage 4b) |
| 19 | `CBADMCDJ.jcl` | Utility / admin | `batch admin step (BatchPipelineOrchestrator)` | Admin card-data batch → administrative Spring Batch step |
| 20 | `OPENFIL.jcl` | Utility (lifecycle) | `config/BatchConfig.java (ItemStream.open)` | File-open utility → Spring Batch reader/writer `open()` lifecycle |
| 21 | `CLOSEFIL.jcl` | Utility (lifecycle) | `config/BatchConfig.java (ItemStream.close)` | File-close utility → Spring Batch reader/writer `close()` lifecycle |
| 22 | `DALYREJS.jcl` | Utility / staging | `batch/writers/RejectWriter.java` | Daily rejects dataset → S3 rejection objects (DALYREJS) |
| 23 | `REPTFILE.jcl` | Utility / staging | `S3 report object (TransactionReportJob output)` | Report file define → S3 output object |
| 24 | `TRANBKP.jcl` | Utility / backup | `S3 backup object (batch backup step)` | Transaction backup → S3 versioned backup object |
| 25 | `PRTCATBL.jcl` | Utility / report | `report step (TransactionCategoryBalance)` | Print category-balance → reporting step over TransactionCategoryBalance |
| 26 | `READACCT.jcl` | Utility / reader run | `batch/readers/AccountFileReader.java (runs CBACT01C)` | Account file read job → ItemReader execution |
| 27 | `READCARD.jcl` | Utility / reader run | `batch/readers/CardFileReader.java (runs CBACT02C)` | Card file read job → ItemReader execution |
| 28 | `READXREF.jcl` | Utility / reader run | `batch/readers/CardCrossReferenceReader.java (runs CBACT03C)` | Xref file read job → ItemReader execution |
| 29 | `READCUST.jcl` | Utility / reader run | `batch/readers/CustomerFileReader.java (runs CBCUS01C)` | Customer file read job → ItemReader execution |

### 5.2 PROCs (2)

| # | PROC | Kind | Java / Target | Notes |
|---|---|---|---|---|
| 1 | `REPROC.prc` | PROC | `REPRO orchestration reference` | IDCAMS REPRO pattern → bulk copy/load steps |
| 2 | `TRANREPT.prc` | PROC | `TransactionReportJob orchestration reference` | Report PROC → parameterized report step |

### 5.3 Batch Pipeline Stage Order

| Stage | JCL | Spring Batch Job | Dependency |
|---|---|---|---|
| 1 | `POSTTRAN.jcl` | `DailyTransactionPostingJob` | entry stage |
| 2 | `INTCALC.jcl` | `InterestCalculationJob` | after stage 1 success |
| 3 | `COMBTRAN.jcl` | `CombineTransactionsJob` | after stage 2 success |
| 4a | `CREASTMT.JCL` | `StatementGenerationJob` | after stage 3 (parallel split) |
| 4b | `TRANREPT.jcl` | `TransactionReportJob` | after stage 3 (parallel split) |
| — | orchestration | `BatchPipelineOrchestrator` | `FlowBuilder.split()` for 4a/4b; condition-code deciders |

---

## 6. Reverse Traceability Index (Java → COBOL)

For each significant Java component, the originating COBOL paragraph(s) are cited, enabling round-trip verification. Every class below is a planned file under `src/main/java/com/carddemo/**` (AAP §0.4.1).

### 6.1 JPA Entities (11) ← Copybooks
| Java Entity | COBOL Copybook | Notes |
|---|---|---|
| `model/entity/Account` | `CVACT01Y.cpy` (300B) | BigDecimal balances + `@Version` |
| `model/entity/Card` | `CVACT02Y.cpy` / `CVCRD01Y.cpy` (150B) | FK to Account |
| `model/entity/Customer` | `CVCUS01Y.cpy` / `CUSTREC.cpy` (500B) | SSN handling |
| `model/entity/CardCrossReference` | `CVACT03Y.cpy` (50B) | Card↔Account↔Customer; CXACAIX |
| `model/entity/Transaction` | `CVTRA05Y.cpy` (350B) | BigDecimal TRAN-AMT |
| `model/entity/UserSecurity` | `CSUSR01Y.cpy` (80B) | BCrypt password (C-003) |
| `model/entity/TransactionCategoryBalance` | `CVTRA01Y.cpy` | @EmbeddedId composite key |
| `model/entity/DisclosureGroup` | `CVTRA02Y.cpy` | @EmbeddedId + BigDecimal rate |
| `model/entity/TransactionType` | `CVTRA03Y.cpy` (60B) | Reference lookup |
| `model/entity/TransactionCategory` | `CVTRA04Y.cpy` | @EmbeddedId composite key |
| `model/entity/DailyTransaction` | `CVTRA06Y.cpy` | Staging entity |

### 6.2 Spring Data Repositories (11) ← VSAM DEFINE CLUSTER JCL
| Java Repository | COBOL / JCL Origin | Notes |
|---|---|---|
| `repository/AccountRepository` | `ACCTFILE.jcl` (ACCTDAT KSDS) | Keyed read + update |
| `repository/CardRepository` | `CARDFILE.jcl` (CARDDAT KSDS) | `findByCardAcctId` |
| `repository/CustomerRepository` | `CUSTFILE.jcl` / `DEFCUST.jcl` | Keyed read |
| `repository/CardCrossReferenceRepository` | `XREFFILE.jcl` (+ CXACAIX) | `findByXrefAcctId` |
| `repository/TransactionRepository` | `TRANFILE.jcl` / `TRANIDX.jcl` | Pagination, date range, max-ID |
| `repository/UserSecurityRepository` | `DUSRSECJ.jcl` | `findBySecUsrId` |
| `repository/TransactionCategoryBalanceRepository` | `TCATBALF.jcl` | Composite-key access |
| `repository/DisclosureGroupRepository` | `DISCGRP.jcl` | `DEFAULT` group fallback |
| `repository/TransactionTypeRepository` | `TRANTYPE.jcl` | Reference lookup |
| `repository/TransactionCategoryRepository` | `TRANCATG.jcl` | Reference lookup |
| `repository/DailyTransactionRepository` | `POSTTRAN.jcl` (staging) | Staging access |

### 6.3 Service Layer (20) ← Online COBOL Programs
| Java Service.method | COBOL Origin | Notes |
|---|---|---|
| `service/auth/AuthenticationService.authenticate()` | `COSGN00C.PROCESS-ENTER-KEY`, `READ-USER-SEC-FILE` | USRSEC + BCrypt + JWT |
| `service/account/AccountViewService.viewAccount()` | `COACTVWC` (multi-dataset read paragraphs) | ACCTDAT + CUSTDAT + CXACAIX join |
| `service/account/AccountUpdateService.updateAccount()` | `COACTUPC.2000-DECIDE-ACTION`, `9600-WRITE-PROCESSING`, `9700-CHECK-CHANGE-IN-REC` | `@Transactional` + rollback + `@Version` |
| `service/card/CardListService.listCards()` | `COCRDLIC` (browse paragraphs) | 7 rows/page |
| `service/card/CardDetailService.getCard()` | `COCRDSLC` (keyed read) | Single keyed read |
| `service/card/CardUpdateService.updateCard()` | `COCRDUPC` (update paragraphs) | `@Version` optimistic update |
| `service/transaction/TransactionListService.listTransactions()` | `COTRN00C` (browse paragraphs) | 10 rows/page |
| `service/transaction/TransactionDetailService.getTransaction()` | `COTRN01C` (keyed read) | Keyed detail |
| `service/transaction/TransactionAddService.addTransaction()` | `COTRN02C.PROCESS-ENTER-KEY` (+ add paragraphs) | Auto-ID generation (Factory) |
| `service/billing/BillPaymentService.payBill()` | `COBIL00C.PROCESS-ENTER-KEY` (+ update paragraphs) | Balance payment posting |
| `service/report/ReportSubmissionService.submitReport()` | `CORPT00C.WIRTE-JOBSUB-TDQ`, `SUBMIT-JOB-TO-INTRDR` | SQS FIFO publish (TDQ bridge) |
| `service/admin/UserListService.listUsers()` | `COUSR00C` | User browse |
| `service/admin/UserAddService.addUser()` | `COUSR01C` | User create |
| `service/admin/UserUpdateService.updateUser()` | `COUSR02C` | User update |
| `service/admin/UserDeleteService.deleteUser()` | `COUSR03C` | User delete |
| `service/menu/MainMenuService.route()` | `COMEN01C` + `COMEN02Y.cpy` | 10-option routing |
| `service/menu/AdminMenuService.route()` | `COADM01C` + `COADM02Y.cpy` | 4-option routing |
| `service/shared/DateValidationService.validateDate()` | `CSUTLDTC.A000-MAIN` + `CSUTLDPY`/`CSUTLDWY.cpy` | `LocalDate` replaces `CEEDAYS` |
| `service/shared/ValidationLookupService.lookup()` | `CSLKPCDY.cpy` | NANPA / state / ZIP lookups |
| `service/shared/FileStatusMapper.describeStatus()` | `CBTRN02C` FILE STATUS clauses (L32-61) | FILE STATUS → exception/status enum |

### 6.4 REST Controllers (8) ← BMS Mapsets
| Java Controller | COBOL BMS Origin | REST Endpoint(s) |
|---|---|---|
| `controller/AuthController` | `COSGN00.bms` / `.CPY` | `POST /api/auth/signin` |
| `controller/AccountController` | `COACTVW.bms`, `COACTUP.bms` | `GET`/`PUT /api/accounts/*` |
| `controller/CardController` | `COCRDLI`, `COCRDSL`, `COCRDUP.bms` | `GET`/`PUT /api/cards/*` |
| `controller/TransactionController` | `COTRN00`, `COTRN01`, `COTRN02.bms` | `GET`/`POST /api/transactions/*` |
| `controller/BillingController` | `COBIL00.bms` | `POST /api/billing/pay` |
| `controller/ReportController` | `CORPT00.bms` | `POST /api/reports/submit` |
| `controller/UserAdminController` | `COUSR00`–`COUSR03.bms` | CRUD `/api/admin/users/*` |
| `controller/MenuController` | `COMEN01.bms` + `COADM01.bms` | `GET /api/menu/*` |

### 6.5 Spring Batch Jobs (6) ← JCL + Batch COBOL
| Java Batch Job | COBOL / JCL Origin | Notes |
|---|---|---|
| `batch/jobs/DailyTransactionPostingJob` | `POSTTRAN.jcl` + `CBTRN02C` | 4-stage validation + condition codes |
| `batch/jobs/InterestCalculationJob` | `INTCALC.jcl` + `CBACT04C` | PARM mapping + interest formula |
| `batch/jobs/CombineTransactionsJob` | `COMBTRAN.jcl` | `Comparator` + bulk insert (DFSORT+REPRO) |
| `batch/jobs/StatementGenerationJob` | `CREASTMT.JCL` + `CBSTM03A`/`CBSTM03B` | Text + HTML to S3 |
| `batch/jobs/TransactionReportJob` | `TRANREPT.jcl` + `CBTRN03C` | Date-filtered report to S3 |
| `batch/jobs/BatchPipelineOrchestrator` | `POSTTRAN`→`TRANREPT` chain | 5-stage flow + condition-code deciders |

### 6.6 Batch Processors / Readers / Writers (13) ← Batch COBOL
| Java Batch Component | COBOL Origin | Notes |
|---|---|---|
| `batch/processors/TransactionPostingProcessor` | `CBTRN02C.1500-VALIDATE-TRAN`, `2000-POST-TRANSACTION` | Strategy validation; reject 100-109 |
| `batch/processors/InterestCalculationProcessor` | `CBACT04C.1300-COMPUTE-INTEREST` | BigDecimal HALF_EVEN |
| `batch/processors/CombineTransactionsProcessor` | `COMBTRAN.jcl` (DFSORT) | Comparator key semantics |
| `batch/processors/StatementProcessor` | `CBSTM03A` (per-account format) | Template method |
| `batch/processors/TransactionReportProcessor` | `CBTRN03C` (report build) | Date-filtered aggregation |
| `batch/readers/DailyTransactionReader` | `CBTRN01C` | Daily transaction reader |
| `batch/readers/AccountFileReader` | `CBACT01C` | Account file reader |
| `batch/readers/CardFileReader` | `CBACT02C` | Card file reader |
| `batch/readers/CardCrossReferenceReader` | `CBACT03C` | Xref file reader |
| `batch/readers/CustomerFileReader` | `CBCUS01C` | Customer file reader |
| `batch/writers/TransactionWriter` | `CBTRN02C.2900-WRITE-TRANSACTION-FILE` | DB + S3 staging |
| `batch/writers/RejectWriter` | `CBTRN02C.2500-WRITE-REJECT-REC` | S3 rejection objects |
| `batch/writers/StatementWriter` | `CBSTM03A` / `CBSTM03B` | S3 statements (file-service bean) |

### 6.7 Exception Hierarchy (7) ← FILE STATUS Codes (`CBTRN02C` L32-61)
| Java Exception | COBOL FILE STATUS / Origin | Notes |
|---|---|---|
| `exception/CardDemoException` | Base (LE abend / generic FILE STATUS) | Root of hierarchy |
| `exception/RecordNotFoundException` | FILE STATUS `23` (record not found) | Keyed read miss |
| `exception/DuplicateRecordException` | FILE STATUS `22` (duplicate key) | Insert conflict |
| `exception/FileAccessException` | FILE STATUS `30`/`9x` (I/O / hardware) | General access failure |
| `exception/ConcurrencyException` | Before/after image mismatch — `COACTUPC`/`COCRDUPC` `9700-CHECK-CHANGE-IN-REC` (+ the sole `SYNCPOINT ROLLBACK`) | Wraps JPA `OptimisticLockException`/`OptimisticLockingFailureException`; HTTP `409` |
| `exception/ValidationException` | Field-level input edits — `COACTUPC`/`COCRDUPC`/`COTRN02C`/`COSGN00C` field edits and the `CBTRN02C` `1500-*` pre-persistence edits | Data-edit rejection before persistence; HTTP `400` |
| `exception/TransactionPostingException` | Daily-posting reject codes `100`/`101`/`102`/`103`/`109` — `CBTRN02C` `1500-VALIDATE-TRAN` cascade → `2500-WRITE-REJECT-REC` | Posting-time rejection (reason-tagged `carddemo.batch.records.rejected`); HTTP `422` |

### 6.8 Cross-Cutting Components — No COBOL Paragraph Origin

The following Java components have **no originating COBOL paragraph**. They exist solely to satisfy the six user-specified implementation rules and Spring Boot bootstrap requirements (AAP §0.7, §0.8.6) and do **not** constitute feature expansion (AAP §0.3.2).

| Java Component | Purpose | Rationale (no COBOL origin) |
|---|---|---|
| `CardDemoApplication` | Spring Boot bootstrap | Entry context derived from `COSGN00C` sign-on, but no paragraph maps to `main()` |
| `config/SecurityConfig` | Spring Security + BCrypt | Realizes RACF/USRSEC → Spring Security (rule: credential hardening) |
| `config/BatchConfig` | Spring Batch registry | Job/step registry + transaction manager |
| `config/AwsConfig` | S3/SQS/SNS beans | Realizes GDG/TDQ → AWS (LocalStack Verification rule) |
| `config/JpaConfig` | Entity scanning / auditing | Persistence configuration |
| `config/ObservabilityConfig` | Tracing/metrics/logging | Observability rule |
| `config/WebConfig` | CORS/serialization/error handling | REST infrastructure |
| `observability/CorrelationIdFilter` | MDC correlation ID | Observability rule |
| `observability/MetricsConfig` | Micrometer/Prometheus | Observability rule |
| `observability/HealthIndicators` | Health/readiness | Observability rule |
| `logback-spring.xml` | Structured JSON logging | Observability rule |

---

## 7. Coverage Summary

### 7.1 Paragraph Coverage by Program

| Program | Paragraphs | Mapped | Coverage |
|---|---:|---:|---:|
| `COACTUPC.cbl` | 85 | 85 | 100% |
| `COCRDUPC.cbl` | 45 | 45 | 100% |
| `COCRDLIC.cbl` | 39 | 39 | 100% |
| `COACTVWC.cbl` | 35 | 35 | 100% |
| `COCRDSLC.cbl` | 34 | 34 | 100% |
| `COTRN02C.cbl` | 18 | 18 | 100% |
| `COTRN00C.cbl` | 16 | 16 | 100% |
| `COUSR00C.cbl` | 16 | 16 | 100% |
| `CORPT00C.cbl` | 10 | 10 | 100% |
| `COBIL00C.cbl` | 16 | 16 | 100% |
| `COUSR02C.cbl` | 11 | 11 | 100% |
| `COUSR03C.cbl` | 11 | 11 | 100% |
| `COTRN01C.cbl` | 9 | 9 | 100% |
| `COUSR01C.cbl` | 9 | 9 | 100% |
| `COMEN01C.cbl` | 7 | 7 | 100% |
| `COADM01C.cbl` | 7 | 7 | 100% |
| `COSGN00C.cbl` | 6 | 6 | 100% |
| `CBSTM03A.CBL` | 25 | 25 | 100% |
| `CBTRN02C.cbl` | 26 | 26 | 100% |
| `CBACT04C.cbl` | 22 | 22 | 100% |
| `CBTRN03C.cbl` | 26 | 26 | 100% |
| `CBTRN01C.cbl` | 18 | 18 | 100% |
| `CBSTM03B.CBL` | 14 | 14 | 100% |
| `CBACT01C.cbl` | 6 | 6 | 100% |
| `CBACT02C.cbl` | 5 | 5 | 100% |
| `CBACT03C.cbl` | 5 | 5 | 100% |
| `CBCUS01C.cbl` | 5 | 5 | 100% |
| `CSUTLDTC.cbl` | 2 | 2 | 100% |
| **Total (28 programs)** | **528** | **528** | **100%** |

### 7.2 Artifact Coverage

| Artifact Class | Source Count | Mapped | Coverage |
|---|---:|---:|---:|
| COBOL programs | 28 | 28 | 100% |
| COBOL paragraphs/sections | 528 | 528 | 100% |
| Copybooks | 28 | 28 | 100% (27 typed + 1 intentionally unmapped) |
| JCL jobs | 29 | 29 | 100% |
| PROCs | 2 | 2 | 100% |

**Result:** Forward coverage is **528/528 = 100%** of all COBOL paragraphs across all 28 programs, and the reverse index accounts for every major Java component back to its COBOL origin (or marks it as a mandated cross-cutting concern). This satisfies the Explainability rule and **Validation Gate 8** (AAP §0.7.2 / §0.7.3).

### 7.3 Marquee Representative Rows (AAP §0.7.3)

| COBOL | Java | Verified |
|---|---|---|
| `COSGN00C.PROCESS-ENTER-KEY` | `AuthenticationService.authenticate()` | ✓ present (Section 3) |
| `COACTUPC.2000-DECIDE-ACTION` / `9600-WRITE-PROCESSING` | `AccountUpdateService.updateAccount()` (`@Transactional` + `@Version`) | ✓ present (Section 3) |
| `CBTRN02C.1500-VALIDATE-TRAN` | `TransactionPostingProcessor.validate()` (reject codes 100-109) | ✓ present (Section 3) |

---

*Generated for source baseline `CardDemo_v1.0-15-g27d6c6f-68` (commit `27d6c6f`). COBOL source is not copied into this repository; this matrix and the cited source line numbers are the traceability record.*
