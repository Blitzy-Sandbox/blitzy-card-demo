# CBTRN02C (Full Posting Engine) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is intentionally documentation-only and contains NO fixture data files. CBTRN02C is the full posting engine batch program translated to Java `com.blitzy.carddemo.application.transaction.CbTrn02C` per AAP §0.4.1. The COBOL source resides at `app/cbl/CBTRN02C.cbl` (731 lines; `PROGRAM-ID. CBTRN02C.` at `app/cbl/CBTRN02C.cbl:L23`; `AUTHOR. AWS.` at `app/cbl/CBTRN02C.cbl:L24`) and is driven by JCL `app/jcl/POSTTRAN.jcl` (45 lines) at STEP15 `EXEC PGM=CBTRN02C` at `app/jcl/POSTTRAN.jcl:L23`. CBTRN02C is THE MOST CRITICAL parity gate per AAP §0.6.11 because it produces FIVE outputs spanning a sequential write, two indexed I-O REWRITEs (with mixed INSERT/UPDATE on TCATBAL), a sequential reject write with an 80-byte trailer, and DISPLAY capture. All four conceptual input fixtures (DALYTRAN, XREFFILE, ACCTFILE, TCATBALF) are REFERENCED via classpath from `app/data/ASCII/` and are NEVER COPIED into this folder per AAP §0.4.1.

## Why this folder is documentation-only

1. CBTRN02C is a **batch-only program** invoked by JCL `app/jcl/POSTTRAN.jcl` (45 lines). Its four input files are bound to JCL DD statements at `app/jcl/POSTTRAN.jcl:L28-L42`: DALYTRAN (sequential, `app/jcl/POSTTRAN.jcl:L30-L31`), XREFFILE (indexed, `app/jcl/POSTTRAN.jcl:L32-L33`), ACCTFILE (indexed I-O, `app/jcl/POSTTRAN.jcl:L39-L40`), and TCATBALF (indexed I-O, `app/jcl/POSTTRAN.jcl:L41-L42`). The five outputs are documented in the sibling `../expected/README.md`.

2. The four ASCII input fixtures are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED into this folder per AAP §0.4.1. Copying them would (a) double storage footprint, (b) introduce drift risk between the COBOL reference implementation and the Java parity harness, and (c) violate the AAP §0.2.2 immutability mandate for the `app/` tree.

3. The consuming Java test class `com.blitzy.carddemo.tests.golden.CbTrn02CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) resolves all expected-output paths through the base class helper `resolveExpectedOutputPath("cbtrn02c", fileName)` which routes to `src/test/resources/golden/cbtrn02c/expected/`. Input fixture paths route to `app/data/ASCII/` via classpath reference. Therefore NO fixture data file lives in this `input/` folder.

4. The harness convention from AAP §0.3.1 and §0.6.11 mandates the `<program>/input/` + `<program>/expected/` pairing per program for discoverability and parallel structure across all 28 program test folders. Removing the `input/` folder would break this symmetry; keeping it empty with only this README preserves the convention while making the absence of data files self-documenting.

5. The capture procedure for the five `../expected/*.txt` files is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until those expected fixtures are captured and committed, `CbTrn02CGoldenTest` is `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11.

Cross-references in the sibling `../expected/` folder:

- `../expected/README.md` — Authoritative fixture contract documenting all 5 outputs.
- `../expected/transact.txt` — 350-byte fixed-width TRAN-RECORD entries written sequentially by `2900-WRITE-TRANSACTION-FILE` (CAPTURE PLACEHOLDER).
- `../expected/acctdata.txt` — 300-byte fixed-width ACCOUNT-RECORD entries with updated balances; REWRITE side effect of `2800-UPDATE-ACCOUNT-REC` (CAPTURE PLACEHOLDER).
- `../expected/tcatbal.txt` — 50-byte fixed-width TRAN-CAT-BAL-RECORD entries; mixed update + insert side effect of `2700-A-CREATE-TCATBAL-REC` and `2700-B-UPDATE-TCATBAL-REC` (CAPTURE PLACEHOLDER).
- `../expected/dalyrejs.txt` — 430-byte fixed-width reject records (350-byte original DALYTRAN payload + 80-byte trailer of 4-byte zero-padded reason code + 76-byte space-padded description) written by `2500-WRITE-REJECT-REC` (CAPTURE PLACEHOLDER).
- `../expected/stdout.txt` — Captured DISPLAY output sequence (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data referenced from app/data/ASCII/ and ../expected/)

CBTRN02C consumes four conceptual input files bound to JCL DD statements and produces five conceptual outputs. The four inputs are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED into this folder per AAP §0.4.1. The five outputs live in the sibling `../expected/` folder. This section documents the conceptual contract; data location is documented in subsection 3.4.

### JCL DD statements + FD record layouts

The four input files bound by `app/jcl/POSTTRAN.jcl:L28-L42` are described below; all FD declarations come from `app/cbl/CBTRN02C.cbl:L66-L97` and SELECT clauses come from `app/cbl/CBTRN02C.cbl:L29-L61`.

| DD Name | FD Record Length | ORG / ACCESS | Key Field | ASCII Fixture (REFERENCED only) | JCL Line |
|---|---|---|---|---|---|
| DALYTRAN | 350 bytes (FD-TRAN-ID PIC X(16) + FD-CUST-DATA PIC X(334)) | SEQUENTIAL | (none) | `app/data/ASCII/dailytran.txt` (105,300 bytes) | L30-L31 |
| XREFFILE | 50 bytes (FD-XREF-CARD-NUM PIC X(16) + FD-XREF-DATA PIC X(34)) | INDEXED RANDOM | FD-XREF-CARD-NUM (16 bytes) | `app/data/ASCII/cardxref.txt` (1,850 bytes) | L32-L33 |
| ACCTFILE | 300 bytes (FD-ACCT-ID PIC 9(11) + FD-ACCT-DATA PIC X(289)) | INDEXED RANDOM (I-O at `app/cbl/CBTRN02C.cbl:L311`) | FD-ACCT-ID (11 bytes) | `app/data/ASCII/acctdata.txt` (15,050 bytes) | L39-L40 |
| TCATBALF | 50 bytes (FD-TRAN-CAT-KEY 17 bytes + FD-FD-TRAN-CAT-DATA PIC X(33)) | INDEXED RANDOM (I-O at `app/cbl/CBTRN02C.cbl:L329`) | FD-TRAN-CAT-KEY (FD-TRANCAT-ACCT-ID PIC 9(11) + FD-TRANCAT-TYPE-CD PIC X(02) + FD-TRANCAT-CD PIC 9(04)) | `app/data/ASCII/tcatbal.txt` (2,550 bytes) | L41-L42 |

Copybook lineage:

- DALYTRAN -> `app/cpy/CVTRA06Y.cpy` (DALYTRAN-RECORD); COPY at `app/cbl/CBTRN02C.cbl:L102`.
- XREFFILE -> `app/cpy/CVACT03Y.cpy` (CARD-XREF-RECORD); COPY at `app/cbl/CBTRN02C.cbl:L112`.
- ACCTFILE -> `app/cpy/CVACT01Y.cpy` (ACCOUNT-RECORD); COPY at `app/cbl/CBTRN02C.cbl:L121`.
- TCATBALF -> `app/cpy/CVTRA01Y.cpy` (TRAN-CAT-BAL-RECORD); COPY at `app/cbl/CBTRN02C.cbl:L126`.
- Output TRAN-RECORD (written to `../expected/transact.txt`) -> `app/cpy/CVTRA05Y.cpy` (TRAN-RECORD); COPY at `app/cbl/CBTRN02C.cbl:L107`.

### Posting flow paragraphs (25+ paragraphs)

The PROCEDURE DIVISION begins at `app/cbl/CBTRN02C.cbl:L193`; the main loop runs at `app/cbl/CBTRN02C.cbl:L202-L219` (`PERFORM UNTIL END-OF-FILE = 'Y'`).

**File-open paragraphs (six, executed before the loop):**

- `0000-DALYTRAN-OPEN` at `app/cbl/CBTRN02C.cbl:L236` — OPEN INPUT DALYTRAN-FILE
- `0100-TRANFILE-OPEN` at `app/cbl/CBTRN02C.cbl:L254` — OPEN OUTPUT TRANSACT-FILE
- `0200-XREFFILE-OPEN` at `app/cbl/CBTRN02C.cbl:L273` — OPEN INPUT XREF-FILE
- `0300-DALYREJS-OPEN` at `app/cbl/CBTRN02C.cbl:L291` — OPEN OUTPUT REJECTS-FILE
- `0400-ACCTFILE-OPEN` at `app/cbl/CBTRN02C.cbl:L309` — OPEN I-O ACCOUNT-FILE (I-O mode set at `app/cbl/CBTRN02C.cbl:L311`)
- `0500-TCATBALF-OPEN` at `app/cbl/CBTRN02C.cbl:L327` — OPEN I-O TCATBAL-FILE (I-O mode set at `app/cbl/CBTRN02C.cbl:L329`)

**Per-record paragraphs (executed inside the loop):**

- `1000-DALYTRAN-GET-NEXT` at `app/cbl/CBTRN02C.cbl:L345` — READ DALYTRAN-FILE; AT END set END-OF-FILE='Y'
- `1500-VALIDATE-TRAN` at `app/cbl/CBTRN02C.cbl:L370` — PERFORM `1500-A-LOOKUP-XREF`; if WS-VALIDATION-FAIL-REASON = 0 PERFORM `1500-B-LOOKUP-ACCT`
- `1500-A-LOOKUP-XREF` at `app/cbl/CBTRN02C.cbl:L380` — READ XREF-FILE by DALYTRAN-CARD-NUM -> FD-XREF-CARD-NUM; INVALID KEY -> reject code 100
- `1500-B-LOOKUP-ACCT` at `app/cbl/CBTRN02C.cbl:L393` — READ ACCOUNT-FILE by XREF-ACCT-ID -> FD-ACCT-ID; INVALID KEY -> reject code 101; overlimit check -> reject code 102; expiration check -> reject code 103
- `2000-POST-TRANSACTION` at `app/cbl/CBTRN02C.cbl:L424` — runs only if WS-VALIDATION-FAIL-REASON = 0; MOVE DALYTRAN fields to TRAN-RECORD; PERFORM `Z-GET-DB2-FORMAT-TIMESTAMP`; PERFORM `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, `2900-WRITE-TRANSACTION-FILE` in that order
- `2500-WRITE-REJECT-REC` at `app/cbl/CBTRN02C.cbl:L446` — WRITE REJECT-RECORD (350-byte DALYTRAN payload + 80-byte trailer)
- `2700-UPDATE-TCATBAL` at `app/cbl/CBTRN02C.cbl:L467` — assemble 17-byte composite key (XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD); READ TCATBAL-FILE; INVALID KEY sets WS-CREATE-TRANCAT-REC='Y'; dispatches to CREATE or UPDATE
- `2700-A-CREATE-TCATBAL-REC` at `app/cbl/CBTRN02C.cbl:L503` — INITIALIZE + WRITE new TRAN-CAT-BAL-RECORD
- `2700-B-UPDATE-TCATBAL-REC` at `app/cbl/CBTRN02C.cbl:L526` — ADD DALYTRAN-AMT to TRAN-CAT-BAL; REWRITE
- `2800-UPDATE-ACCOUNT-REC` at `app/cbl/CBTRN02C.cbl:L545` — ADD DALYTRAN-AMT to ACCT-CURR-BAL; REWRITE ACCOUNT-RECORD; REWRITE INVALID KEY -> reject code 109
- `2900-WRITE-TRANSACTION-FILE` at `app/cbl/CBTRN02C.cbl:L562` — WRITE TRAN-RECORD to TRANSACT-FILE

**File-close paragraphs (six, executed after the loop):**

- `9000-DALYTRAN-CLOSE` at `app/cbl/CBTRN02C.cbl:L582`
- `9100-TRANFILE-CLOSE` at `app/cbl/CBTRN02C.cbl:L600`
- `9200-XREFFILE-CLOSE` at `app/cbl/CBTRN02C.cbl:L619`
- `9300-DALYREJS-CLOSE` at `app/cbl/CBTRN02C.cbl:L637`
- `9400-ACCTFILE-CLOSE` at `app/cbl/CBTRN02C.cbl:L655`
- `9500-TCATBALF-CLOSE` at `app/cbl/CBTRN02C.cbl:L674`

**Utility paragraphs:**

- `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBTRN02C.cbl:L692` — calls FUNCTION CURRENT-DATE and formats to 26-byte `YYYY-MM-DD-HH.MM.SS.MM0000` for TRAN-PROC-TS; note the paragraph LACKS the 4-digit numeric prefix used by other paragraphs (idiosyncrasy preserved per AAP §0.7.1).
- `9999-ABEND-PROGRAM` at `app/cbl/CBTRN02C.cbl:L707` — DISPLAY `'ABENDING PROGRAM'` at `app/cbl/CBTRN02C.cbl:L708`; EXEC CICS ABEND ABCODE('TRN2').
- `9910-DISPLAY-IO-STATUS` at `app/cbl/CBTRN02C.cbl:L714` — DISPLAY `'FILE STATUS IS: NNNN'` IO-STATUS-04 helper.

Update order matters per AAP §0.7.1: TCATBAL update happens BEFORE account update BEFORE transaction file write. The Java translation MUST preserve this ordering exactly; reordering changes observable file states under partial failure scenarios.

### Validation reasons (5 codes)

The five reject codes emitted into the 80-byte trailer of each DALYREJS record consist of a 4-byte zero-padded reason code followed by a 76-byte description with trailing spaces. Codes and exact descriptions from the COBOL source (preserved verbatim per AAP §0.7.1):

| Reason Code | Description (EXACT) | Source Paragraph | Source Line | Trigger |
|---|---|---|---|---|
| `0100` | `INVALID CARD NUMBER FOUND` | 1500-A-LOOKUP-XREF | L385-L386 | XREF-FILE READ INVALID KEY (DALYTRAN-CARD-NUM not in cross-reference) |
| `0101` | `ACCOUNT RECORD NOT FOUND` | 1500-B-LOOKUP-ACCT | L397-L398 | ACCOUNT-FILE READ INVALID KEY (XREF-ACCT-ID not in account master) |
| `0102` | `OVERLIMIT TRANSACTION` | 1500-B-LOOKUP-ACCT | L410-L411 | ACCT-CREDIT-LIMIT < WS-TEMP-BAL where WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT (computed at L403-L405) |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | 1500-B-LOOKUP-ACCT | L417-L418 | ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10) at L414 |
| `0109` | `ACCOUNT RECORD NOT FOUND` | 2800-UPDATE-ACCOUNT-REC | L556-L557 | ACCOUNT-FILE REWRITE INVALID KEY (intentional duplicate of code 101 description; PRESERVE per AAP §0.7.1) |

Critical idiosyncrasies preserved verbatim:

- The COBOL field name `ACCT-EXPIRAION-DATE` at `app/cbl/CBTRN02C.cbl:L414` is misspelled (should be `EXPIRATION`); this spelling is intentional in the source and is preserved per AAP §0.7.1. Do NOT rename in Java.
- Codes 102 and 103 are checked INDEPENDENTLY in series at `app/cbl/CBTRN02C.cbl:L407-L420`. Both can be triggered on the same transaction; the later check overwrites the earlier reason code if it also triggers. The Java translation MUST preserve this ordering exactly per AAP §0.7.1.
- Codes 101 (at `app/cbl/CBTRN02C.cbl:L398`) and 109 (at `app/cbl/CBTRN02C.cbl:L557`) share the identical description `ACCOUNT RECORD NOT FOUND`; this duplicate is intentional in the source and MUST be preserved exactly per AAP §0.7.1.
- The TCATBAL missing-record DISPLAY message at `app/cbl/CBTRN02C.cbl:L476-L477` reads `'TCATBAL record not found for key : '` FD-TRAN-CAT-KEY `'.. Creating.'` — preserve EXACTLY with TWO PERIODS before `Creating` and a SINGLE PERIOD after.

### Conceptual fixture data (locations and source-of-truth)

The four conceptual inputs and their locations:

| Conceptual fixture | Actual location | Role |
|---|---|---|
| DALYTRAN sequential | `app/data/ASCII/dailytran.txt` (105,300 bytes; REFERENCED via classpath) | Primary input — transaction records to post |
| XREFFILE indexed | `app/data/ASCII/cardxref.txt` (1,850 bytes; REFERENCED via classpath) | Card-to-account cross-reference (READ only) |
| ACCTFILE indexed I-O | `app/data/ASCII/acctdata.txt` (15,050 bytes; REFERENCED via classpath) | Account master (READ and REWRITE) |
| TCATBALF indexed I-O | `app/data/ASCII/tcatbal.txt` (2,550 bytes; REFERENCED via classpath) | Transaction-category balance (READ and REWRITE or WRITE) |

The five conceptual outputs all live in `../expected/`:

| Output | Sibling location | Role |
|---|---|---|
| TRANSACT sequential output | `../expected/transact.txt` | 350-byte fixed-width TRAN-RECORD entries written by `2900-WRITE-TRANSACTION-FILE` (CAPTURE PLACEHOLDER) |
| ACCOUNT master after | `../expected/acctdata.txt` | 300-byte fixed-width ACCOUNT-RECORD entries with updated balances (CAPTURE PLACEHOLDER) |
| TCATBAL after | `../expected/tcatbal.txt` | 50-byte fixed-width records; mixed update + insert (CAPTURE PLACEHOLDER) |
| Rejects sequential | `../expected/dalyrejs.txt` | 430-byte fixed-width records (350 + 80 trailer); LRECL=430 set at `app/jcl/POSTTRAN.jcl:L36` (CAPTURE PLACEHOLDER) |
| Captured DISPLAY | `../expected/stdout.txt` | Sequence of COBOL DISPLAY outputs (CAPTURE PLACEHOLDER) |

Per AAP §0.4.1, no copy of any of these nine files is created in this `input/` folder.

## Cross-reference to the Java test class

The Java FQCN under test is `com.blitzy.carddemo.application.transaction.CbTrn02C` (per AAP §0.4.1, `transaction` subpackage). The Java test class FQCN is `com.blitzy.carddemo.tests.golden.CbTrn02CGoldenTest`; it extends the base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test source path is `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbTrn02CGoldenTest.java`.

Per AAP §0.3.2 hexagonal architecture, `CbTrn02C` accepts six constructor-injected collaborators (five repository ports plus one reject sink):

- `DailyTransactionRepository` — sequential reader bound to `app/data/ASCII/dailytran.txt`
- `CardXrefRepository` — indexed reader bound to `app/data/ASCII/cardxref.txt`
- `AccountRepository` — indexed I-O port bound to `app/data/ASCII/acctdata.txt` (READ and REWRITE)
- `TransactionCategoryBalanceRepository` — indexed I-O port bound to `app/data/ASCII/tcatbal.txt` (READ and REWRITE or WRITE)
- `TransactionRepository` — sequential writer producing `transact.txt`
- A reject sink producing `dalyrejs.txt`

Override summary for `CbTrn02CGoldenTest`:

- `programClass()` returns `com.blitzy.carddemo.application.transaction.CbTrn02C.class`.
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cbtrn02c", "stdout.txt")` — routes to `../expected/stdout.txt`.
- `expectedOutputs()` returns ExpectedOutput entries for `transact.txt`, `acctdata.txt`, `tcatbal.txt`, `dalyrejs.txt`, and `stdout.txt` (all under `../expected/`).
- Input fixture paths are obtained via `resolveAppFixturePath("dailytran.txt")`, `resolveAppFixturePath("cardxref.txt")`, `resolveAppFixturePath("acctdata.txt")`, and `resolveAppFixturePath("tcatbal.txt")` which route to `app/data/ASCII/`.

All input fixtures route to `app/data/ASCII/`; all expected fixtures route to `../expected/`. **None route to this `input/` folder.** This is the architectural reason the folder is empty.

The test MUST be annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the five `../expected/*.txt` files are captured per AAP §0.6.11. A deterministic `Clock` MUST be injected via `ScopedValue<Clock>` per AAP §0.6.6 so that `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBTRN02C.cbl:L692-L705` produces a fixed `YYYY-MM-DD-HH.MM.SS.MM0000` value for byte-identical TRAN-PROC-TS in `../expected/transact.txt`. Without a fixed clock, the captured fixture would drift on every run.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CBTRN02C) for the COBOL build/run path used to capture all five `../expected/*.txt` files. Per AAP §0.7.5, the procedure is documented in `MIGRATION_NOTES.md` rather than inline in this README to keep the per-program documentation focused on the conceptual contract.

The capture procedure MUST run the COBOL CBTRN02C program against the same four `app/data/ASCII/*.txt` fixtures that the Java test will reference. The capture environment MUST inject a fixed clock (matching the Java `ScopedValue<Clock>` value) so the `TRAN-PROC-TS` column produced by `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBTRN02C.cbl:L692` is deterministic and reproducible. Until the capture is performed and committed, the five `../expected/*.txt` files hold placeholder content and `CbTrn02CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **Byte-for-byte parity is the contract** (AAP §0.1.1, §0.6.11): every byte of every output file produced by Java MUST equal the corresponding byte of the captured COBOL output. CBTRN02C is THE MOST CRITICAL parity gate per AAP §0.6.11 because it produces five outputs spanning sequential write, indexed REWRITE, indexed mixed insert/update, sequential write with trailer, and DISPLAY capture.
- **Sequential execution is mandated** (AAP §0.6.6): virtual-thread fan-out is FORBIDDEN inside CBTRN02C because per-record work mutates SHARED state — the account balance via REWRITE at `app/cbl/CBTRN02C.cbl:L554` and the transaction-category balance via REWRITE at `app/cbl/CBTRN02C.cbl:L528` or WRITE at `app/cbl/CBTRN02C.cbl:L510`. Any reordering changes observable output. The Java translation MUST execute the main loop serially in the same DALYTRAN read order.
- **I-O mode on ACCTFILE and TCATBALF** at `app/cbl/CBTRN02C.cbl:L311` (OPEN I-O ACCOUNT-FILE) and `app/cbl/CBTRN02C.cbl:L329` (OPEN I-O TCATBAL-FILE): both files are read AND rewritten in place. The Java port interfaces MUST expose both READ-by-key and REWRITE operations; `TransactionCategoryBalanceRepository` additionally exposes WRITE for the create-new-record branch at `app/cbl/CBTRN02C.cbl:L503-L520`.
- **Update order is fixed** per `2000-POST-TRANSACTION` at `app/cbl/CBTRN02C.cbl:L440-L442`: TCATBAL update -> account update -> transaction file write. The Java translation MUST preserve this order; reordering changes observable file state under partial failure.
- **Five reject codes preserved verbatim** (AAP §0.7.1): codes `0100`, `0101`, `0102`, `0103`, `0109` with the exact descriptions listed in subsection 3.3. The duplicate description shared by codes 101 and 109 (`ACCOUNT RECORD NOT FOUND`) MUST be preserved exactly.
- **`ACCT-EXPIRAION-DATE` misspelling preserved**: at `app/cbl/CBTRN02C.cbl:L414` the field name is `ACCT-EXPIRAION-DATE` (missing the second `T` of `EXPIRATION`). This is intentional in the source per AAP §0.7.1; do NOT rename in Java.
- **Reject trailer is 80 bytes**: 4-byte zero-padded reason code + 76-byte description with trailing spaces. Total reject record length is 430 bytes (350-byte original DALYTRAN payload + 80-byte trailer); this matches `app/jcl/POSTTRAN.jcl:L36` which specifies `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)` for the DALYREJS GDG output.
- **TCATBAL composite key construction** at `app/cbl/CBTRN02C.cbl:L469-L471`: FD-TRANCAT-ACCT-ID := XREF-ACCT-ID; FD-TRANCAT-TYPE-CD := DALYTRAN-TYPE-CD; FD-TRANCAT-CD := DALYTRAN-CAT-CD. The 17-byte key (11 + 2 + 4) is used for READ at `app/cbl/CBTRN02C.cbl:L474`; INVALID KEY at `app/cbl/CBTRN02C.cbl:L475-L479` routes to the create branch.
- **TCATBAL create-vs-update branch** at `app/cbl/CBTRN02C.cbl:L495-L499`: WS-CREATE-TRANCAT-REC = `'Y'` (missing-record path) PERFORMs `2700-A-CREATE-TCATBAL-REC` (INITIALIZE + WRITE); `'N'` (existing-record path) PERFORMs `2700-B-UPDATE-TCATBAL-REC` (ADD + REWRITE).
- **TCATBAL missing-record DISPLAY message** at `app/cbl/CBTRN02C.cbl:L476-L477`: `'TCATBAL record not found for key : '` FD-TRAN-CAT-KEY `'.. Creating.'` — preserve EXACTLY with TWO PERIODS before `Creating` and a SINGLE PERIOD after.
- **DALY vs DAILY spelling inconsistency**: `'ERROR OPENING DALY REJECTS FILE'` at `app/cbl/CBTRN02C.cbl:L302` vs `'ERROR CLOSING DAILY REJECTS FILE'` at `app/cbl/CBTRN02C.cbl:L648`. Both spellings are intentional in the source; preserve verbatim per AAP §0.7.1.
- **1-space vs 2-space alignment** at `app/cbl/CBTRN02C.cbl:L227` (`'TRANSACTIONS PROCESSED :'` — 1 space before `:`) and `app/cbl/CBTRN02C.cbl:L228` (`'TRANSACTIONS REJECTED  :'` — 2 spaces before `:`). Preserve both alignments exactly per AAP §0.7.1.
- **RETURN-CODE 4 on rejects** at `app/cbl/CBTRN02C.cbl:L229-L231`: `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE`; otherwise RETURN-CODE remains 0.
- **`BigDecimal` arithmetic only** (AAP §0.6.1, §0.7.4): every monetary value in CBTRN02C is PIC S9(09)V99 or PIC S9(10)V99 (DALYTRAN-AMT, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, TRAN-CAT-BAL). The Java translation MUST use `java.math.BigDecimal` with explicit `MathContext.DECIMAL128` and `RoundingMode` selection via the `Decimals` utility per AAP §0.6.1. Trailing zeros MUST be preserved via `setScale(2, ...)`.
- **`java.time` only for dates and timestamps** (AAP §0.6.4): `ACCT-EXPIRAION-DATE` (PIC X(10), YYYY-MM-DD) -> `LocalDate`; `DALYTRAN-ORIG-TS` and `TRAN-PROC-TS` (PIC X(26), `YYYY-MM-DD-HH.MM.SS.MM0000`) -> `LocalDateTime` with a deterministic `Clock`. No `java.util.Date`, `java.util.Calendar`, or `java.text.SimpleDateFormat` in the Java translation.
- **`java.nio.file` only for file I/O** (AAP §0.6.5): no `java.io.File`. The four input fixtures and five output fixtures are accessed via `Files.newByteChannel`, `Files.readAllBytes`, or `Files.write`.
- **EBCDIC default with per-file codepage override** (AAP §0.6.5): the default codepage is `Charset.forName("IBM-1047")`; the four ASCII fixtures in `app/data/ASCII/*.txt` are pre-transcoded for test convenience but per-file codepage overrides MUST be configurable via `application.properties`.
- **`ScopedValue` replaces `ThreadLocal`** (AAP §0.6.6): batch run context (run ID, processing date, tenant, deterministic Clock) flows through `ScopedValue.where(...).run(...)`. No `ThreadLocal` in new code.
- **`@CobolProgram("CBTRN02C")` annotation required** (AAP §0.7.1): the Java class `CbTrn02C` MUST carry the `@CobolProgram` Javadoc-style annotation citing the original PROGRAM-ID `CBTRN02C`, source path `app/cbl/CBTRN02C.cbl`, and translation date.
- **No Unicode ellipsis anywhere** — only 3 ASCII periods `...` per AAP §0.7.4 forbidden-content guidance.
- **No card PAN logged in full** (AAP §0.7.2): production logging MUST mask all but the last 4 digits of DALYTRAN-CARD-NUM and XREF-CARD-NUM. Test fixtures, being captured records, preserve the COBOL representation verbatim — production logging masks; fixtures do not.
- **DB2 timestamp 26-byte format** at `app/cbl/CBTRN02C.cbl:L149-L174` and `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBTRN02C.cbl:L692-L705`: `YYYY-MM-DD-HH.MM.SS.MM0000` byte layout — 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26 bytes (year, dash, month, dash, day, dash, hour, dot, minute, dot, second, dot, hundredths, four trailing zeros). The Java translation MUST format `LocalDateTime` to exactly this 26-byte pattern.
- **Pattern-matching switch exhaustiveness** (AAP §0.7.4): the TCATBAL create-vs-update dispatch at `app/cbl/CBTRN02C.cbl:L495-L499` and any reject-code dispatch translate to Java pattern-matching switches with sealed-type permits; NO fallthrough branch is permitted.

## Source lineage

All source files in this section are REFERENCE-only and remain UNCHANGED per AAP §0.1.1 and §0.2.2:

- `app/cbl/CBTRN02C.cbl` (731 lines) — Full posting engine batch program. PROGRAM-ID `CBTRN02C` at `app/cbl/CBTRN02C.cbl:L23`, AUTHOR `AWS` at `app/cbl/CBTRN02C.cbl:L24`. SELECT clauses at `app/cbl/CBTRN02C.cbl:L29-L61`. FD declarations at `app/cbl/CBTRN02C.cbl:L66-L97`. PROCEDURE DIVISION at `app/cbl/CBTRN02C.cbl:L193`. Main loop at `app/cbl/CBTRN02C.cbl:L202-L219`. Twenty-five+ paragraphs enumerated in subsection 3.2.
- `app/jcl/POSTTRAN.jcl` (45 lines) — JCL driver. Job at `app/jcl/POSTTRAN.jcl:L1`. STEP15 `EXEC PGM=CBTRN02C` at `app/jcl/POSTTRAN.jcl:L23`. DD bindings at `app/jcl/POSTTRAN.jcl:L28-L42`. DALYREJS GDG output `AWS.M2.CARDDEMO.DALYREJS(+1)` at `app/jcl/POSTTRAN.jcl:L38` with `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)` at `app/jcl/POSTTRAN.jcl:L36`.
- `app/cpy/CVTRA06Y.cpy` — DALYTRAN-RECORD 350-byte layout; COPY at `app/cbl/CBTRN02C.cbl:L102`.
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD 350-byte layout (output); COPY at `app/cbl/CBTRN02C.cbl:L107`.
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD 50-byte layout; COPY at `app/cbl/CBTRN02C.cbl:L112`.
- `app/cpy/CVACT01Y.cpy` — ACCOUNT-RECORD 300-byte layout; COPY at `app/cbl/CBTRN02C.cbl:L121`.
- `app/cpy/CVTRA01Y.cpy` — TRAN-CAT-BAL-RECORD 50-byte layout with 17-byte composite key; COPY at `app/cbl/CBTRN02C.cbl:L126`.
- `app/data/ASCII/dailytran.txt` (105,300 bytes) — DALYTRAN sequential transaction fixture; REFERENCE-only per AAP §0.4.1.
- `app/data/ASCII/cardxref.txt` (1,850 bytes) — XREFFILE indexed cross-reference fixture; REFERENCE-only per AAP §0.4.1.
- `app/data/ASCII/acctdata.txt` (15,050 bytes) — ACCTFILE indexed account-master fixture; REFERENCE-only per AAP §0.4.1.
- `app/data/ASCII/tcatbal.txt` (2,550 bytes) — TCATBALF indexed transaction-category-balance fixture; REFERENCE-only per AAP §0.4.1.

## Authority references

- AAP §0.1.1 (refactoring objective — preserve byte-for-byte fidelity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope: `golden/cbtrn02c/` directory tree under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference implementation)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.3.2 (hexagonal architecture; constructor-injected ports)
- AAP §0.4.1 (transformation plan — CBTRN02C -> CbTrn02C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCED via classpath and NEVER COPIED)
- AAP §0.6.1 (decimal arithmetic fidelity — `BigDecimal`, `MathContext.DECIMAL128`, banker's rounding for ROUNDED, default truncation; scale preservation)
- AAP §0.6.4 (date semantics — `java.time` only; never legacy date APIs)
- AAP §0.6.5 (file I/O exactness — `java.nio.file` only; EBCDIC IBM-1047 default; per-file codepage configurable)
- AAP §0.6.6 (batch throughput — sequential execution preserved for CBTRN02C because per-record work mutates shared state; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.11 (golden-record harness PR gate; CBTRN02C is THE MOST CRITICAL parity gate)
- AAP §0.7.1 (Minimal Change Clause; preserve duplicate code 109 description, `EXPIRAION` misspelling, codes-102-then-103 ordering, two-periods-before-Creating message, DALY/DAILY inconsistency)
- AAP §0.7.2 (no card PAN in production logs; test fixtures preserve COBOL behavior verbatim)
- AAP §0.7.4 (forbidden features — no preview JEPs, no fallthrough branches in exhaustive switches)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline `[<path>:<locator>]`)
- Sibling pattern reference: `golden/cbtrn03c/input/README.md` (same 8-H2 + 4-H3 documentation-only marker pattern adapted for posting-engine semantics; CBTRN02C has FIVE outputs vs CBTRN03C's TWO outputs; FOUR file ports vs FIVE for CBTRN03C; FIVE reject codes; sequential execution mandated)

## DO NOT add files here

This folder MUST contain ONLY the single `README.md` file. Do NOT add:

- Fixture data files (`.bin`, `.txt` copies of `app/data/ASCII/*` files, or any captured input snapshots)
- `.gitkeep` placeholders
- Subfolders
- Captured input snapshots

All four conceptual input fixtures (DALYTRAN, XREFFILE, ACCTFILE, TCATBALF) are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED per AAP §0.4.1. All five expected output fixtures (`transact.txt`, `acctdata.txt`, `tcatbal.txt`, `dalyrejs.txt`, `stdout.txt`) live in the sibling `../expected/` folder. Adding files to this `input/` folder would create duplicate, stale, or unreachable fixtures and would break the byte-for-byte parity guarantee that is the entire point of the golden-record harness.

If a future test scenario requires additional inputs, source them from `app/data/ASCII/` and extend the appropriate `resolveAppFixturePath(...)` call in `CbTrn02CGoldenTest.java` — not here.
