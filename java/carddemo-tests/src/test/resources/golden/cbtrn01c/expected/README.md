# Golden-Record Contract — CBTRN01C (Daily Transaction Validator; No JCL Driver)

This document is the authoritative byte-for-byte contract for the Java translation of COBOL program CBTRN01C, a read-only daily-transaction validator that exercises the DALYTRAN sequential read path and two random-access lookups (XREF and ACCOUNT) without performing any posting, writes, or rewrites.

The companion JUnit 5 test class `com.blitzy.carddemo.tests.golden.CbTrn01CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and consumes the single captured fixture file documented below.

The test class is `@Disabled("Awaiting COBOL CBTRN01C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the double-DISPLAY pattern on validation failures: DALYTRAN-RECORD is emitted twice (once at the error site, once at the diagnostic display).")` per AAP §0.6.11 until `stdout.txt` is present in this folder with content captured from a COBOL reference run and committed.

Every claim in this README about COBOL behavior cites a specific line range in the form `[<path>:Lnnn]` or `[<path>:Lnnn-Lmmm]` per AAP §0.8.1 citation discipline.

CBTRN01C is a **validation-only program** with **NO posting**, **NO writes**, **NO rewrites**, and **NO JCL driver** in `app/jcl/` (verified absence via `grep -l "CBTRN01C" app/jcl/*.jcl app/jcl/*.JCL` returning empty). Its sole observable output is captured stdout, making this fixture the simplest of the batch parity tests but still essential for verifying the DALYTRAN read path, XREF/ACCT random-access lookup, opened-but-unused file handling, and verbatim DISPLAY message preservation.

The binding cascade flows down through 19 AAP sections enumerated in Phase 0 below.

## Phase 0: Authority and Source-of-Truth Cascade

The following 19 AAP sections govern every byte and every assertion described in this README:

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS; no behavior changes
- AAP §0.2.1 — Golden-record fixtures are in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE; never modified by the Java refactor
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` (documentation marker) + `<program>/expected/` (this folder)
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports define the Java translation shape
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521)
- AAP §0.3.6 — Hexagonal architecture; no Spring container, no application framework
- AAP §0.4.1 — CBTRN01C -> CbTrn01C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCE only, NEVER copied into this folder
- AAP §0.6.1 — `Decimals` utility centralizes monetary arithmetic with `MathContext.DECIMAL128`; BigDecimal scale 2 for monetary field shapes (DALYTRAN-AMT parsed via `Decimals` even though no arithmetic occurs in CBTRN01C)
- AAP §0.6.2 — Sealed-type pattern for closed value sets and lookup outcomes
- AAP §0.6.4 — `java.time` only for date and time; never `java.util.Date` or `Calendar`
- AAP §0.6.5 — `java.nio.file` for all file I/O; EBCDIC IBM-1047 default codepage with per-file override
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely in new code; sequential execution preserved for ordered output (NO virtual threads in CBTRN01C path)
- AAP §0.6.11 — Golden-record harness is the PR gate; `@Disabled` until COBOL captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve L372-L373 copy-paste bug, opened-but-unused CUSTFILE/CARDFILE/TRANFILE boilerplate, DAILY vs DALYTRAN spelling inconsistency, exact DISPLAY whitespace including colon-placement variants
- AAP §0.7.2 — No PAN in production logs (mask all but last 4 digits); test driver uses unmasked sink for parity assertion
- AAP §0.7.4 — No preview features (JEP 502 Stable Values, JEP 505 Structured Concurrency, JEP 507 Primitive Patterns, JEP 512 Compact Source Files in production code); no `default` branches that hide cases in pattern-matching switch
- AAP §0.7.5 — Capture procedure for golden-record fixtures is documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]` for every claim about COBOL behavior

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBTRN01C` | `[app/cbl/CBTRN01C.cbl:L23]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBTRN01C.cbl:L24]` |
| Source line count | 491 | `app/cbl/CBTRN01C.cbl` (per `wc -l`) |
| JCL driver | NONE (verified absence in `app/jcl/`) | `grep -l "CBTRN01C" app/jcl/*.jcl` returns empty |
| FILE-CONTROL SELECTs | 6 files (DALYTRAN, CUSTOMER, XREF, CARD, ACCOUNT, TRANSACT) | `[app/cbl/CBTRN01C.cbl:L28-L62]` |
| FD declarations | 6 records totaling DALYTRAN (350) + CUSTOMER (500) + XREF (50) + CARD (150) + ACCOUNT (300) + TRANSACT (350) | `[app/cbl/CBTRN01C.cbl:L66-L94]` |
| Files actively read | 3 (DALYTRAN, XREF, ACCOUNT) | Main loop at `[app/cbl/CBTRN01C.cbl:L164-L186]` |
| Files opened-but-unused | 3 (CUSTOMER, CARD, TRANSACT) | Opens at L271/L307/L343; closes at L379/L415/L451 |
| Java FQCN under test | `com.blitzy.carddemo.application.transaction.CbTrn01C` | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbTrn01CGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |

The `@CobolProgram("CBTRN01C")` Javadoc-style annotation declared on the `CbTrn01C` class MUST cite the original PROGRAM-ID literal value, the source path `app/cbl/CBTRN01C.cbl`, and the date of translation per AAP §0.7.1.

Each translated COBOL paragraph MUST carry a `@CobolParagraph("<NUMERIC-PREFIX-NAME>")` Javadoc annotation that cites the original COBOL paragraph name verbatim, including its 4-digit numeric prefix or `Z-` letter prefix (for example `@CobolParagraph("2000-LOOKUP-XREF")`, `@CobolParagraph("3000-READ-ACCOUNT")`, `@CobolParagraph("9000-DALYTRAN-CLOSE")`, `@CobolParagraph("Z-ABEND-PROGRAM")`, `@CobolParagraph("Z-DISPLAY-IO-STATUS")`).

The `@Disabled` mandate enforced by AAP §0.6.11 requires that `CbTrn01CGoldenTest` remain disabled until `stdout.txt` is present in this folder with captured (not placeholder) content; the test is unblocked only after the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 has been executed and the resulting captured output committed.

## Phase 2: Files in This Folder

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBTRN01C golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbTrn01CGoldenTest` once `stdout.txt` is captured per AAP §0.7.5. The file is the SOLE direct child of this folder until the COBOL capture step deposits the single expected-output file.

### `stdout.txt` — Captured DISPLAY Output (CAPTURE PLACEHOLDER)

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in the platform's newline (LF on a captured Linux run).
- **Per-iteration body** (executes once per DALYTRAN record at `[app/cbl/CBTRN01C.cbl:L164-L186]`):
  - L168: `DISPLAY DALYTRAN-RECORD` — emits the entire 350-byte fixed-width DALYTRAN-RECORD as one line, followed by newline. Contains DALYTRAN-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD, DALYTRAN-SOURCE, DALYTRAN-DESC, DALYTRAN-AMT (BigDecimal scale 2 shape per AAP §0.6.1), DALYTRAN-MERCHANT-ID, DALYTRAN-MERCHANT-NAME, DALYTRAN-MERCHANT-CITY, DALYTRAN-MERCHANT-ZIP, DALYTRAN-CARD-NUM (16-byte PAN — unmasked in captured fixture), DALYTRAN-ORIG-TS, DALYTRAN-PROC-TS, FILLER per CVTRA06Y.
  - On XREF success + ACCT success path: 5 additional DISPLAY lines from 2000-LOOKUP-XREF success (L235, L236, L237, L238) and 3000-READ-ACCOUNT success (L249)
  - On XREF failure (DALYTRAN-CARD-NUM not in cardxref.txt):
    - L232: `'INVALID CARD NUMBER FOR XREF'`
    - L181-L183: SINGLE COBOL DISPLAY statement producing ONE output line: `'CARD NUMBER '` DALYTRAN-CARD-NUM `' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'` DALYTRAN-ID
  - On XREF success + ACCT failure (XREF-ACCT-ID not in acctdata.txt):
    - L235-L238: XREF success lines emitted first
    - L246: `'INVALID ACCOUNT NUMBER FOUND'`
    - L178: `'ACCOUNT '` ACCT-ID `' NOT FOUND'`
- **Unconditional banners**:
  - First line: L156 `'START OF EXECUTION OF PROGRAM CBTRN01C'`
  - Last line: L195 `'END OF EXECUTION OF PROGRAM CBTRN01C'`
- **DALYTRAN-RECORD double-DISPLAY pattern**: when XREF or ACCT lookup fails, the offending DALYTRAN-RECORD is shown TWICE on stdout — once at L168 (unconditional per-iteration), and again indirectly via the L181-L183 diagnostic which includes DALYTRAN-CARD-NUM and DALYTRAN-ID substrings (XREF-failure path) or via the L178 diagnostic which includes ACCT-ID (ACCT-failure path). This is the marquee behavior to verify when the capture is committed.
- **Byte-for-byte parity rule**: The Java test driver's captured stdout MUST equal this baseline BYTE-for-BYTE. Trailing whitespace and padding direction preserved exactly. Line breaks preserved exactly (LF).
- **PAN-masking dichotomy**: The captured `stdout.txt` fixture intentionally preserves the COBOL unmasked PAN behavior (L168 dumps the full 350-byte record including 16-byte DALYTRAN-CARD-NUM) for parity assertion. Per AAP §0.7.2, production logs MUST mask all but the last 4 digits via a separate logger sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- **No deterministic Clock required**: Unlike CBTRN02C which calls FUNCTION CURRENT-DATE in Z-GET-DB2-FORMAT-TIMESTAMP, CBTRN01C performs NO clock-reading. NO injected Clock is required for reproducible captures.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT exist yet; `CbTrn01CGoldenTest` is `@Disabled` until captured content is committed

## Phase 3: Conceptual Input Universe

The five ASCII inputs that together produce the captured baseline are:

- DALYTRAN: `app/data/ASCII/dailytran.txt` (105,300 bytes; 300 sequential 350-byte records per CVTRA06Y, each followed by LF) — PRIMARY input, drives the main loop at `[app/cbl/CBTRN01C.cbl:L164-L186]`
- CUSTOMER: `app/data/ASCII/custdata.txt` (25,050 bytes; 500-byte records per CVCUS01Y) — auxiliary; opened at L271 but never read in the main loop
- XREFFILE: `app/data/ASCII/cardxref.txt` (1,850 bytes; 50-byte records per CVACT03Y) — auxiliary; active in main loop via random READ at L227-L239
- CARDFILE: `app/data/ASCII/carddata.txt` (7,550 bytes; 150-byte records per CVACT02Y) — auxiliary; opened at L307 but never read in the main loop
- ACCTFILE: `app/data/ASCII/acctdata.txt` (15,050 bytes; 300-byte records per CVACT01Y) — auxiliary; active in main loop via random READ at L241-L250

The sixth opened file (TRANSACT-FILE) has NO ASCII fixture in `app/data/ASCII/` because CBTRN01C never reads it. The Java port adapter for `TransactionRepository` MUST tolerate an empty or absent backing file during this validation-only program.

All five ASCII fixtures are NEVER copied to this folder per AAP §0.4.1 — they are referenced via classpath relative path from `app/data/ASCII/`. The Java test class resolves the classpath via the `resolveAppDataPath(...)` helper from the `GoldenRecordTest` base class. The base class handles both classpath resolution (for normal Maven Surefire execution) and direct filesystem resolution (for IDE-driven runs); in both modes the underlying bytes are read from the immutable `app/data/ASCII/` tree.

Conceptual input contracts per fixture:

- `dailytran.txt` is parsed by the sequential reader behind `DailyTransactionRepository.streamSequential()`. Each 351-byte input line is split into a 350-byte CVTRA06Y record plus a trailing LF; the Java fixed-width reader strips the LF and presents `DalyTranRecord` instances in input order. The 14 fields of CVTRA06Y are described in Phase 7's L168 record-dump details below.
- `cardxref.txt` is parsed by the random-access reader behind `CardXrefRepository.findByCardNum(String cardNum)`. Each 50-byte record holds a 16-byte XREF-CARD-NUM key plus a 9-byte XREF-CUST-ID, an 11-byte XREF-ACCT-ID, and 14 bytes of trailing FILLER per CVACT03Y.
- `acctdata.txt` is parsed by the random-access reader behind `AccountRepository.findById(long acctId)`. Each 300-byte record is the CVACT01Y ACCOUNT-RECORD layout (5 BigDecimal monetary fields scale 2, 3 LocalDate fields, 178-byte FILLER preserved verbatim).
- `custdata.txt` is parsed by `CustomerRepository.findById(long custId)` only if any client invokes that port; in CBTRN01C the port is constructor-injected but never invoked, so the underlying file is opened but never read.
- `carddata.txt` is parsed by `CardRepository.findByCardNum(String cardNum)` only if any client invokes that port; in CBTRN01C the port is constructor-injected but never invoked.

CBTRN01C performs NO writes and NO rewrites; all inputs are read-only and the original `app/data/ASCII/` files remain UNCHANGED after the test run per AAP §0.2.2. The test harness MUST verify this invariant by comparing each `app/data/ASCII/*.txt` file's SHA-256 before and after the test execution; any divergence indicates a bug in the Java translation or in the port adapter implementation.

## Phase 4: Validation Flow (15 paragraphs)

The PROCEDURE DIVISION begins at `[app/cbl/CBTRN01C.cbl:L154]` with `MAIN-PARA` immediately at L155. The MAIN-PARA driver invokes 12 file open/close paragraphs plus the main validation loop, then terminates via GOBACK.

Driver paragraph (MAIN-PARA):

- PROCEDURE DIVISION entry at `[app/cbl/CBTRN01C.cbl:L154]`
- MAIN-PARA start at `[app/cbl/CBTRN01C.cbl:L155]`
- Start banner DISPLAY at `[app/cbl/CBTRN01C.cbl:L156]` — `'START OF EXECUTION OF PROGRAM CBTRN01C'`
- Open paragraphs invocation at `[app/cbl/CBTRN01C.cbl:L157-L162]` (PERFORM 0000, 0100, 0200, 0300, 0400, 0500)
- Main loop at `[app/cbl/CBTRN01C.cbl:L164-L186]` — `PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'`
- Per-iteration body:
  - L166: `PERFORM 1000-DALYTRAN-GET-NEXT`
  - L167-L169: inner IF — `IF END-OF-DAILY-TRANS-FILE = 'N' DISPLAY DALYTRAN-RECORD END-IF` (the L168 DISPLAY executes only when the most recent READ returned a record)
  - L170: `MOVE 0 TO WS-XREF-READ-STATUS`
  - L171: `MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM`
  - L172: `PERFORM 2000-LOOKUP-XREF`
  - L173-L184: nested IF on `WS-XREF-READ-STATUS = 0`: on success MOVE 0 to WS-ACCT-READ-STATUS, MOVE XREF-ACCT-ID to ACCT-ID, PERFORM 3000-READ-ACCOUNT, then conditional ACCT diagnostic at L178 if WS-ACCT-READ-STATUS NOT = 0; on XREF failure the ELSE branch at L180 emits the L181-L183 single combined DISPLAY statement
- Close paragraphs invocation at `[app/cbl/CBTRN01C.cbl:L188-L193]` (PERFORM 9000, 9100, 9200, 9300, 9400, 9500)
- End banner DISPLAY at `[app/cbl/CBTRN01C.cbl:L195]` — `'END OF EXECUTION OF PROGRAM CBTRN01C'`
- GOBACK at `[app/cbl/CBTRN01C.cbl:L197]`

File-open paragraphs (six total):

- 0000-DALYTRAN-OPEN at `[app/cbl/CBTRN01C.cbl:L252]` (OPEN INPUT DALYTRAN-FILE at L254; error DISPLAY at L263)
- 0100-CUSTFILE-OPEN at `[app/cbl/CBTRN01C.cbl:L271]` (OPEN INPUT CUSTOMER-FILE at L273; error DISPLAY at L282)
- 0200-XREFFILE-OPEN at `[app/cbl/CBTRN01C.cbl:L289]` (OPEN INPUT XREF-FILE at L291; error DISPLAY at L300)
- 0300-CARDFILE-OPEN at `[app/cbl/CBTRN01C.cbl:L307]` (OPEN INPUT CARD-FILE at L309; error DISPLAY at L318)
- 0400-ACCTFILE-OPEN at `[app/cbl/CBTRN01C.cbl:L325]` (OPEN INPUT ACCOUNT-FILE at L327; error DISPLAY at L336)
- 0500-TRANFILE-OPEN at `[app/cbl/CBTRN01C.cbl:L343]` (OPEN INPUT TRANSACT-FILE at L345; error DISPLAY at L354)

Read paragraphs (three total):

- 1000-DALYTRAN-GET-NEXT at `[app/cbl/CBTRN01C.cbl:L202]` — sequential `READ DALYTRAN-FILE INTO DALYTRAN-RECORD` at L203; `DALYTRAN-STATUS = '10'` (EOF) at L207 sets `MOVE 'Y' TO END-OF-DAILY-TRANS-FILE` at L217; non-EOF non-zero status emits `'ERROR READING DAILY TRANSACTION FILE'` at L219, then PERFORM Z-DISPLAY-IO-STATUS at L221, then PERFORM Z-ABEND-PROGRAM at L222
- 2000-LOOKUP-XREF at `[app/cbl/CBTRN01C.cbl:L227]` — `MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM` at L228; random READ with INVALID KEY phrase at L229-L239; INVALID KEY emits `'INVALID CARD NUMBER FOR XREF'` at L232 then `MOVE 4 TO WS-XREF-READ-STATUS` at L233; NOT INVALID KEY emits the four success lines at L235, L236, L237, L238
- 3000-READ-ACCOUNT at `[app/cbl/CBTRN01C.cbl:L241]` — `MOVE ACCT-ID TO FD-ACCT-ID` at L242; random READ with INVALID KEY phrase at L243-L250; INVALID KEY emits `'INVALID ACCOUNT NUMBER FOUND'` at L246 then `MOVE 4 TO WS-ACCT-READ-STATUS` at L247; NOT INVALID KEY emits `'SUCCESSFUL READ OF ACCOUNT FILE'` at L249

File-close paragraphs (six total):

- 9000-DALYTRAN-CLOSE at `[app/cbl/CBTRN01C.cbl:L361]` — CLOSE DALYTRAN-FILE at L363; **CONTAINS COPY-PASTE BUG at L372-L373** (see Phase 9 #1)
- 9100-CUSTFILE-CLOSE at `[app/cbl/CBTRN01C.cbl:L379]` — CLOSE CUSTOMER-FILE at L381; error DISPLAY at L390 (correct usage)
- 9200-XREFFILE-CLOSE at `[app/cbl/CBTRN01C.cbl:L397]` — CLOSE XREF-FILE at L399; error DISPLAY at L408
- 9300-CARDFILE-CLOSE at `[app/cbl/CBTRN01C.cbl:L415]` — CLOSE CARD-FILE at L417; error DISPLAY at L426
- 9400-ACCTFILE-CLOSE at `[app/cbl/CBTRN01C.cbl:L433]` — CLOSE ACCOUNT-FILE at L435; error DISPLAY at L444
- 9500-TRANFILE-CLOSE at `[app/cbl/CBTRN01C.cbl:L451]` — CLOSE TRANSACT-FILE at L453; error DISPLAY at L462

Utility paragraphs (lack 4-digit numeric prefix — preserve `Z-` naming per AAP §0.7.1):

- Z-ABEND-PROGRAM at `[app/cbl/CBTRN01C.cbl:L469]` — DISPLAY `'ABENDING PROGRAM'` at L470; MOVE 0 TO TIMING at L471; MOVE 999 TO ABCODE at L472; CALL `'CEE3ABD'` at L473. NOTE: COBOL sets ABCODE=999 then calls CEE3ABD; the OS-level return code propagated to JES is platform-dependent and is documented faithfully here without inventing a specific RETURN-CODE value.
- Z-DISPLAY-IO-STATUS at `[app/cbl/CBTRN01C.cbl:L476]` — IF/ELSE branch at L477 selects between numeric-or-9 vs default formatting; both branches emit `'FILE STATUS IS: NNNN'` followed by IO-STATUS-04 at L483 (IF branch) and L487 (ELSE branch)

CBTRN01C performs NO posting (no 2000-POST-TRANSACTION paragraph exists), NO writes, and NO rewrites. The Java translation MUST faithfully open and close CUSTFILE, CARDFILE, and TRANFILE even though they are never accessed in the main loop.

## Phase 5: Verbatim COBOL DISPLAY Message Catalog

The following 27 entries enumerate EVERY DISPLAY statement in CBTRN01C. Each row preserves the exact bytes (including embedded spaces, embedded colons, and trailing literals) that the Java translation MUST reproduce byte-for-byte.

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `'START OF EXECUTION OF PROGRAM CBTRN01C'` | L156 | Unconditional start banner |
| 2 | `DISPLAY DALYTRAN-RECORD` (full 350-byte record) | L168 | Per-iteration unconditional record dump in MAIN-PARA loop |
| 3 | `'ACCOUNT '` ACCT-ID `' NOT FOUND'` | L178 | ACCT lookup failure path in MAIN-PARA |
| 4 | `'CARD NUMBER '` DALYTRAN-CARD-NUM `' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'` DALYTRAN-ID | L181-L183 | XREF lookup failure path in MAIN-PARA (single multi-source-line DISPLAY statement) |
| 5 | `'END OF EXECUTION OF PROGRAM CBTRN01C'` | L195 | Unconditional end banner |
| 6 | `'ERROR READING DAILY TRANSACTION FILE'` | L219 | 1000-DALYTRAN-GET-NEXT non-EOF error |
| 7 | `'INVALID CARD NUMBER FOR XREF'` | L232 | 2000-LOOKUP-XREF INVALID KEY |
| 8 | `'SUCCESSFUL READ OF XREF'` | L235 | 2000-LOOKUP-XREF NOT INVALID KEY |
| 9 | `'CARD NUMBER: '` XREF-CARD-NUM | L236 | 2000-LOOKUP-XREF success detail (HAS colon — different from L181) |
| 10 | `'ACCOUNT ID : '` XREF-ACCT-ID | L237 | 2000-LOOKUP-XREF success detail (SPACE BEFORE colon) |
| 11 | `'CUSTOMER ID: '` XREF-CUST-ID | L238 | 2000-LOOKUP-XREF success detail (NO space before colon — inconsistency) |
| 12 | `'INVALID ACCOUNT NUMBER FOUND'` | L246 | 3000-READ-ACCOUNT INVALID KEY |
| 13 | `'SUCCESSFUL READ OF ACCOUNT FILE'` | L249 | 3000-READ-ACCOUNT NOT INVALID KEY |
| 14 | `'ERROR OPENING DAILY TRANSACTION FILE'` | L263 | 0000-DALYTRAN-OPEN failure (note DAILY vs DALYTRAN — preserve per AAP §0.7.1) |
| 15 | `'ERROR OPENING CUSTOMER FILE'` | L282 | 0100-CUSTFILE-OPEN failure |
| 16 | `'ERROR OPENING CROSS REF FILE'` | L300 | 0200-XREFFILE-OPEN failure |
| 17 | `'ERROR OPENING CARD FILE'` | L318 | 0300-CARDFILE-OPEN failure |
| 18 | `'ERROR OPENING ACCOUNT FILE'` | L336 | 0400-ACCTFILE-OPEN failure |
| 19 | `'ERROR OPENING TRANSACTION FILE'` | L354 | 0500-TRANFILE-OPEN failure |
| 20 | `'ERROR CLOSING CUSTOMER FILE'` | L372 | **COPY-PASTE BUG**: paragraph 9000-DALYTRAN-CLOSE emits wrong file name; also MOVE CUSTFILE-STATUS at L373 instead of DALYTRAN-STATUS — PRESERVE per AAP §0.7.1 |
| 21 | `'ERROR CLOSING CUSTOMER FILE'` | L390 | 9100-CUSTFILE-CLOSE failure (correct usage) |
| 22 | `'ERROR CLOSING CROSS REF FILE'` | L408 | 9200-XREFFILE-CLOSE failure |
| 23 | `'ERROR CLOSING CARD FILE'` | L426 | 9300-CARDFILE-CLOSE failure |
| 24 | `'ERROR CLOSING ACCOUNT FILE'` | L444 | 9400-ACCTFILE-CLOSE failure |
| 25 | `'ERROR CLOSING TRANSACTION FILE'` | L462 | 9500-TRANFILE-CLOSE failure |
| 26 | `'ABENDING PROGRAM'` | L470 | Z-ABEND-PROGRAM before CALL `'CEE3ABD'` |
| 27 | `'FILE STATUS IS: NNNN'` IO-STATUS-04 | L483, L487 | Z-DISPLAY-IO-STATUS — both branches of IF/ELSE emit identical literal |

ALL messages preserve EXACT bytes including embedded SPACES, COLONS, and the spelling inconsistencies (DAILY at L263 vs DALYTRAN file SELECT name). NO Unicode characters appear in any message. The Java translation MUST produce byte-identical strings for each of the 27 catalogued messages.

## Phase 6: Validation Diagnostics (NO Reject Codes)

Unlike CBTRN02C which emits structured 80-byte reject trailers with 5 numeric reject codes (100, 101, 102, 103, 109) into a dedicated DALYREJS reject file, CBTRN01C emits ONLY plain-text DISPLAY diagnostics to stdout. There is no reject file, no reject record layout, no validation trailer record, no zero-padded reason code, no description with trailing spaces. There is no per-record reject sink; failed validations are emitted directly to stdout via DISPLAY statements.

Validation diagnostic flow:

- XREF lookup branch: on success emits 4 lines (L235, L236, L237, L238); on failure emits 1 line (L232) plus the MAIN-PARA conditional ELSE branch which emits L181-L183 as a single combined DISPLAY line
- ACCT lookup branch (only reached if XREF succeeded): on success emits 1 line (L249); on failure emits 1 line (L246) plus the MAIN-PARA conditional inner IF which emits L178
- WS-XREF-READ-STATUS and WS-ACCT-READ-STATUS (working-storage at `[app/cbl/CBTRN01C.cbl:L149-L151]`) are integer flags (`PIC 9(04)`) set to 0 (success) or 4 (failure)

The MAIN-PARA conditional at `[app/cbl/CBTRN01C.cbl:L173-L184]` evaluates `WS-XREF-READ-STATUS` first. If XREF failed (status = 4), the PERFORM 3000-READ-ACCOUNT at L176 is SKIPPED entirely and ONLY the L181-L183 `'CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'` message is emitted via the ELSE branch at L180. If XREF succeeded but ACCT failed (`WS-ACCT-READ-STATUS = 4` at L177), the L178 `'ACCOUNT ... NOT FOUND'` message is emitted from inside the IF branch. The two failure paths are MUTUALLY EXCLUSIVE per record.

There is no RETURN-CODE setting on the success or failure paths. RETURN-CODE is only affected indirectly via Z-ABEND-PROGRAM which sets ABCODE to 999 at L472 and calls CEE3ABD at L473 (catastrophic file I/O failure). On normal completion, RETURN-CODE remains at its default value (0). This differs from CBTRN02C which sets RETURN-CODE = 4 when any rejects occur during posting.

Diagnostic-line ordering invariants (these MUST be preserved by the Java translation):

- For the XREF-success path inside 2000-LOOKUP-XREF, the four success lines L235, L236, L237, L238 are emitted in that exact source order. The Java translation MUST emit them in this same order via the same DISPLAY-equivalent sink
- For the XREF-failure path, L232 is emitted INSIDE 2000-LOOKUP-XREF (inside the INVALID KEY phrase at L231-L233), THEN control returns to MAIN-PARA where the ELSE branch at L180 emits the L181-L183 combined line. The captured `stdout.txt` therefore shows L232 BEFORE L181-L183 for any given XREF-failed record
- For the XREF-success / ACCT-failure path, the four XREF success lines are emitted first (still inside 2000-LOOKUP-XREF), THEN control returns to MAIN-PARA, which invokes 3000-READ-ACCOUNT which emits L246 (inside its INVALID KEY phrase), THEN control returns to MAIN-PARA which emits L178. The captured `stdout.txt` therefore shows the 4 XREF success lines, THEN L246, THEN L178 for any given ACCT-failed record
- The L168 record-dump line is emitted BEFORE the lookup paragraphs are entered (the L168 DISPLAY at line 168 fires before the L170-L172 moves and the L172 PERFORM 2000-LOOKUP-XREF). The captured `stdout.txt` therefore shows the full DALYTRAN-RECORD line BEFORE any lookup diagnostics for that record

The MAIN-PARA loop body is structurally:

1. Sequential READ DALYTRAN (paragraph 1000)
2. If a record was read (not EOF): emit L168 DALYTRAN-RECORD dump
3. Set WS-XREF-READ-STATUS = 0 (L170)
4. Move DALYTRAN-CARD-NUM to XREF-CARD-NUM (L171)
5. Perform 2000-LOOKUP-XREF (L172) — emits L235-L238 on success OR L232 on failure
6. If WS-XREF-READ-STATUS = 0 (L173): set WS-ACCT-READ-STATUS = 0, move XREF-ACCT-ID to ACCT-ID, perform 3000-READ-ACCOUNT — emits L249 on success OR L246 on failure
   - If WS-ACCT-READ-STATUS NOT = 0 (L177): emit L178
7. Else (L180; XREF failed): emit L181-L183 combined

The Java translation MUST preserve this exact control flow and ordering. Any optimization that changes the ordering (for example, batching lookups across records) is FORBIDDEN per AAP §0.6.6.

## Phase 7: Single-Output stdout.txt Byte Contract

This program produces exactly ONE captured output file: `stdout.txt`. The byte contract is defined as follows:

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in LF (Unix-style newline on a captured Linux run).
- **First line**: L156 banner `'START OF EXECUTION OF PROGRAM CBTRN01C'`
- **Last line**: L195 banner `'END OF EXECUTION OF PROGRAM CBTRN01C'`
- **Per DALYTRAN record body** (executes once per input record):
  - 1 line: L168 unconditional `DISPLAY DALYTRAN-RECORD` (350-byte record verbatim)
  - On XREF success + ACCT success: 5 additional lines (L235, L236, L237, L238, L249)
  - On XREF success + ACCT failure: 5 lines (L235, L236, L237, L238, L246) + 1 line L178 from MAIN-PARA
  - On XREF failure: 1 line (L232) + 1 combined line L181-L183 from MAIN-PARA (single COBOL DISPLAY statement spanning 3 source lines produces ONE output line concatenating the three operands and two literals)
- **Total expected line count**: 2 banner lines plus the per-record body for each of N DALYTRAN records in `app/data/ASCII/dailytran.txt`. Currently N = 300 records (verified by `awk 'END{print NR}' app/data/ASCII/dailytran.txt`); the expected count is `2 + (N x body-line-count)` where body-line-count varies per-record by validation outcome. The symbolic expression is used because the value of N could change if the fixture is regenerated.
- **Byte-for-byte parity rule**: `CbTrn01C.run(...)` MUST produce, for the deterministic input set, a captured stdout byte sequence equal to this captured baseline BYTE-for-BYTE.
- **Trailing whitespace**: COBOL DISPLAY may emit trailing spaces or trim them depending on environment; the captured `stdout.txt` is the reference. The Java translation MUST match byte-by-byte regardless of how the underlying runtime handles trailing whitespace.
- **PAN exposure**: L168's full-record dump includes DALYTRAN-CARD-NUM (16-byte PAN) unmasked. The test-only sink writes the unmasked bytes for parity assertion; production logger sinks apply masking per AAP §0.7.2.
- **No deterministic Clock needed**: CBTRN01C does NOT invoke FUNCTION CURRENT-DATE; no timestamp generation occurs; the captured output is naturally deterministic given fixed inputs.

L168 record-dump field layout (CVTRA06Y, 350 bytes total, fields concatenated left-to-right with no separator):

- DALYTRAN-ID PIC X(16) — 16 bytes
- DALYTRAN-TYPE-CD PIC X(02) — 2 bytes
- DALYTRAN-CAT-CD PIC 9(04) — 4 bytes (zoned-decimal in ASCII fixture)
- DALYTRAN-SOURCE PIC X(10) — 10 bytes
- DALYTRAN-DESC PIC X(100) — 100 bytes (space-padded right)
- DALYTRAN-AMT PIC S9(09)V99 — 11 bytes signed packed-decimal shape in the on-disk fixture, displayed as 11 ASCII digit characters with embedded sign by L168; carries BigDecimal scale 2 per AAP §0.6.1
- DALYTRAN-MERCHANT-ID PIC 9(09) — 9 bytes
- DALYTRAN-MERCHANT-NAME PIC X(50) — 50 bytes (space-padded right)
- DALYTRAN-MERCHANT-CITY PIC X(50) — 50 bytes (space-padded right)
- DALYTRAN-MERCHANT-ZIP PIC X(10) — 10 bytes
- DALYTRAN-CARD-NUM PIC X(16) — 16 bytes (16-byte PAN; unmasked in captured fixture)
- DALYTRAN-ORIG-TS PIC X(26) — 26 bytes (timestamp string; not parsed by CBTRN01C)
- DALYTRAN-PROC-TS PIC X(26) — 26 bytes (timestamp string; not parsed by CBTRN01C)
- FILLER PIC X(20) — 20 bytes (trailing FILLER preserved verbatim including any trailing spaces)

The sum of the above field widths is 350 bytes exactly. The Java translation MUST emit the same 350 bytes (followed by LF) when DISPLAY DALYTRAN-RECORD fires at L168.

## Phase 8: Opened-But-Unused Files Contract

CBTRN01C opens SIX files but reads only THREE in the main loop. The remaining THREE files (CUSTOMER-FILE, CARD-FILE, TRANSACT-FILE) are opened and closed without being read.

| File | Open | Close | Read in main loop? | Per AAP §0.7.1 |
|---|---|---|---|---|
| DALYTRAN-FILE | L252 (0000-DALYTRAN-OPEN) | L361 (9000-DALYTRAN-CLOSE) | YES (sequential READ at L202) | Active |
| CUSTOMER-FILE | L271 (0100-CUSTFILE-OPEN) | L379 (9100-CUSTFILE-CLOSE) | NO | Preserved boilerplate |
| XREF-FILE | L289 (0200-XREFFILE-OPEN) | L397 (9200-XREFFILE-CLOSE) | YES (random READ at L227-L239) | Active |
| CARD-FILE | L307 (0300-CARDFILE-OPEN) | L415 (9300-CARDFILE-CLOSE) | NO | Preserved boilerplate |
| ACCOUNT-FILE | L325 (0400-ACCTFILE-OPEN) | L433 (9400-ACCTFILE-CLOSE) | YES (random READ at L241-L250) | Active |
| TRANSACT-FILE | L343 (0500-TRANFILE-OPEN) | L451 (9500-TRANFILE-CLOSE) | NO | Preserved boilerplate (no ASCII fixture in app/data/ASCII/) |

Per AAP §0.7.1 Minimal Change Clause, the Java translation MUST faithfully open and close CUSTOMER-FILE, CARD-FILE, and TRANSACT-FILE even though they are never read in the main loop. Skipping the open/close calls would silently change observable behavior under failure conditions (for example, open failure on CUSTFILE would emit `'ERROR OPENING CUSTOMER FILE'` at L282 and abend through Z-ABEND-PROGRAM; skipping the open hides this potential code path and changes the abend semantics for the rare case where the auxiliary file is missing).

TRANSACT-FILE has no ASCII fixture in `app/data/ASCII/transact.txt` because CBTRN01C never reads it. The Java port adapter for `TransactionRepository` MUST tolerate an empty or absent backing file during this validation-only program; the test harness MUST provide either an empty file or a tolerant adapter implementation so the open call at L345 succeeds.

Implementation notes for the opened-but-unused files:

- The CUSTOMER, CARD, and TRANSACT port interfaces in `com.blitzy.carddemo.domain.port` are unchanged from the contracts defined for sibling programs (CBTRN02C, CBACT04C, COCRDUPC, etc.); CBTRN01C re-uses the same port shapes and does NOT define alternate "read-nothing" variants
- The file-backed adapter implementations in `com.blitzy.carddemo.adapter.file` open each file lazily on first invocation; for CBTRN01C the first invocation never happens, so the underlying file channel is opened but never read past the file header. The Java translation MUST emulate this by opening the channel in the constructor of the adapter (or first lookup call) so that file-open failures surface at the same point as in COBOL
- The error handler in the adapter MUST emit DISPLAY-style messages corresponding to L282 (CUSTOMER), L318 (CARD), and L354 (TRANSACT) when the open call fails, then propagate an abend equivalent (translated from PERFORM Z-ABEND-PROGRAM). Skipping the open call to "optimize away" the unused file would change observable abend behavior under file-missing conditions.

## Phase 9: Preserved Behaviors and Idiosyncrasies (DO NOT FIX)

The following ten preserved-as-is COBOL quirks each have explicit citation. All are PRESERVED per AAP §0.7.1 Minimal Change Clause:

1. **COPY-PASTE BUG at L372-L373** in paragraph 9000-DALYTRAN-CLOSE (L361): the DISPLAY at L372 emits `'ERROR CLOSING CUSTOMER FILE'` (wrong file name — should say DAILY TRANSACTION) and the subsequent MOVE at L373 references CUSTFILE-STATUS (wrong status variable — should be DALYTRAN-STATUS). Both are bugs from copy-pasting paragraph 9100-CUSTFILE-CLOSE. PRESERVED EXACTLY per AAP §0.7.1. The Java translation MUST emit the same misleading message and use the same wrong status variable on DALYTRAN close failure. Do NOT "fix" this bug.

2. **Misleading PROGRAM-ID purpose**: The Apache license block header at L1-L21 (NOT reproduced in this README) hints at posting semantics ("Post the records from daily transaction file"), but the actual implementation only validates and looks up — it never posts, writes, or rewrites. PRESERVED per AAP §0.7.1.

3. **Three opened-but-unused files**: CUSTOMER-FILE, CARD-FILE, and TRANSACT-FILE are opened (L271, L307, L343) and closed (L379, L415, L451) without being read in the main loop. PRESERVED per AAP §0.7.1; the Java translation MUST faithfully open and close them.

4. **FD-CUST-DATA name reuse across two FDs**: Both DALYTRAN-FILE FD (L67-L69) and CUSTOMER-FILE FD (L72-L74) declare a field named `FD-CUST-DATA`. PRESERVED per AAP §0.7.1; the Java translation's domain records MUST keep both spellings distinct via their parent record namespaces.

5. **DAILY vs DALYTRAN spelling inconsistency**: L263 emits `'ERROR OPENING DAILY TRANSACTION FILE'` (uses DAILY), but the FILE-CONTROL SELECT name is DALYTRAN-FILE and the COPY book name is CVTRA06Y (DALYTRAN-RECORD). The spelling inconsistency is exclusive to L263 and the L219 read-error message; all other messages reference CROSS REF, CUSTOMER, CARD, ACCOUNT, or TRANSACTION rather than DAILY. PRESERVED per AAP §0.7.1.

6. **`'CARD NUMBER '` vs `'CARD NUMBER: '` (no colon at L181 vs colon at L236)**: L181 emits `'CARD NUMBER '` (NO colon, single trailing space), while L236 emits `'CARD NUMBER: '` (HAS colon, trailing space). PRESERVED EXACTLY per AAP §0.7.1; the Java translation MUST emit the two variants in their respective contexts.

7. **`'ACCOUNT ID : '` with space-before-colon at L237**: literal text has a SPACE BEFORE the colon. PRESERVED EXACTLY per AAP §0.7.1.

8. **`'CUSTOMER ID: '` with no space-before-colon at L238**: literal text has NO SPACE before the colon — inconsistent with L237's spacing. PRESERVED EXACTLY per AAP §0.7.1.

9. **Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS paragraphs lack 4-digit numeric prefix**: Other paragraphs use prefixes 0000-, 0100-, 1000-, 9000-, etc. The Z-prefix utility paragraphs at L469 and L476 are unusual but consistent with z/OS conventions. PRESERVED per AAP §0.7.1; the `@CobolParagraph` annotations on their Java counterparts MUST cite the `Z-` prefix verbatim.

10. **DALYTRAN-RECORD double-DISPLAY on errors**: When XREF or ACCT lookup fails, the offending DALYTRAN-RECORD is shown TWICE on stdout — once at L168 (unconditional per-iteration), and again indirectly via the L181-L183 diagnostic which includes DALYTRAN-CARD-NUM and DALYTRAN-ID substrings (XREF-failure path) or via the L178 diagnostic which includes ACCT-ID (ACCT-failure path). PRESERVED per AAP §0.7.1; this is the marquee behavior to verify in the byte-for-byte parity assertion.

NONE of these idiosyncrasies are bugs to be "fixed". They are observable behaviors that downstream consumers (operators monitoring logs, parsers reading FILE STATUS codes) may depend on. Per AAP §0.7.1 Minimal Change Clause, ALL of these are preserved verbatim by the Java translation and by every captured fixture.

## Phase 10: Java Mapping Invariants

The following invariants govern the Java translation `com.blitzy.carddemo.application.transaction.CbTrn01C` and the broader Java module:

- **One class per COBOL PROGRAM-ID**: `CBTRN01C` -> `CbTrn01C` in `com.blitzy.carddemo.application.transaction` per AAP §0.4.1
- **`@CobolProgram("CBTRN01C")` annotation** citing original PROGRAM-ID, source path `app/cbl/CBTRN01C.cbl`, and translation date per AAP §0.7.1
- **`@CobolParagraph` annotations** on each private method translated from a COBOL paragraph (e.g., `@CobolParagraph("2000-LOOKUP-XREF")`, `@CobolParagraph("3000-READ-ACCOUNT")`, `@CobolParagraph("9000-DALYTRAN-CLOSE")`, `@CobolParagraph("Z-ABEND-PROGRAM")`, `@CobolParagraph("Z-DISPLAY-IO-STATUS")`)
- **Constructor injection** of repository ports (NO Spring, NO container): 6 dependencies — `DailyTransactionRepository`, `CustomerRepository`, `CardXrefRepository`, `CardRepository`, `AccountRepository`, `TransactionRepository` — even though only 3 are used at runtime; ports defined in `com.blitzy.carddemo.domain.port` per AAP §0.3.2 and §0.3.6
- **NO `BigDecimal` arithmetic site** in CbTrn01C (CBTRN01C performs no math); DALYTRAN-AMT is parsed and encoded via the `Decimals` utility for the L168 record dump but never added, subtracted, multiplied, or divided
- **`String` for all 26-byte timestamps**: DALYTRAN-ORIG-TS and DALYTRAN-PROC-TS are stored as String (no parsing performed in CBTRN01C since no date comparison or arithmetic occurs)
- **NO injected `Clock`** required for CBTRN01C (no FUNCTION CURRENT-DATE invocation in the source); the captured output is naturally deterministic given fixed inputs
- **NO `java.util.Date` and NO `java.util.Calendar` and NO `java.text.SimpleDateFormat`** per AAP §0.6.4
- **NO `java.io.File`** — use `java.nio.file.Path`, `java.nio.file.Files.newByteChannel`, and `java.nio.channels.SeekableByteChannel` per AAP §0.6.5
- **NO `ThreadLocal`** — use `ScopedValue` per AAP §0.6.6. CBTRN01C is sequential single-threaded; NO virtual-thread fan-out is permitted in the CBTRN01C path because DISPLAY ordering of `DISPLAY DALYTRAN-RECORD` emissions and XREF/ACCT diagnostic lines is observable and would be broken by reordering
- **EBCDIC IBM-1047 default codepage** per AAP §0.6.5; per-file override via `application.properties` keys (for example, `carddemo.file.dalytran.charset`). ASCII fixtures use `Charset.forName("US-ASCII")` for test runs
- **Sealed-type pattern** (per AAP §0.6.2) for: WS-XREF-READ-STATUS and WS-ACCT-READ-STATUS modeled as `LookupOutcome { Success, NotFound }` sealed hierarchy; FILE STATUS code hierarchy modeled as a sealed type with discriminator
- **Pattern-matching switch** for case-discrimination on FILE STATUS codes and `LookupOutcome` — NO `default` branch per AAP §0.7.4; exhaustiveness enforced by the Java compiler
- **Records, not POJOs**: `DalyTranRecord` (from CVTRA06Y), `CustomerRecord` (from CVCUS01Y), `CardXrefRecord` (from CVACT03Y), `CardRecord` (from CVACT02Y), `AccountRecord` (from CVACT01Y), `TranRecord` (from CVTRA05Y) ALL declared as Java `record` per AAP §0.3.2
- **`parse(byte[])` and `encode()`** static factory plus instance method pair on each record per AAP §0.3.2 byte-level contract; round-trip invariant `parse(buf).encode() == buf` for every valid buffer
- **NO Spring, NO Hibernate, NO Spring Batch** — plain Java with constructor injection per AAP §0.3.6
- **Sequential execution mandated** — NO virtual threads per AAP §0.6.6 because DISPLAY ordering is observable and would be broken by parallel reordering
- **JVM flags**: `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4
- **NO `--enable-preview`** per AAP §0.7.4
- **Test-only sink for unmasked PAN**: production logger sinks apply PAN masking per AAP §0.7.2; the test driver uses a separate sink that captures unmasked bytes for byte-for-byte parity
- **L372-L373 copy-paste bug preserved**: the Java method translating 9000-DALYTRAN-CLOSE MUST emit `"ERROR CLOSING CUSTOMER FILE"` (wrong text — preserved) and reference the equivalent of CUSTFILE-STATUS (wrong variable — preserved) on DALYTRAN close failure
- **`@CobolFileStatus` (or equivalent) annotation** on the FILE STATUS sealed hierarchy, citing the COBOL FILE STATUS code (e.g., `'00'`, `'10'`, `'23'`) and the originating SELECT clause path; this makes the byte-level FILE STATUS contract searchable in code review
- **`@CobolFD` annotation** on each domain record's parse/encode pair, citing the FD declaration line range (e.g., `[app/cbl/CBTRN01C.cbl:L66-L69]` for FD DALYTRAN-FILE; `[app/cbl/CBTRN01C.cbl:L71-L74]` for FD CUSTOMER-FILE)
- **Method-name preservation**: paragraphs map to private methods whose names mirror the original COBOL paragraph name in camelCase with the numeric prefix retained as part of the Javadoc tag (e.g., COBOL `2000-LOOKUP-XREF` -> Java `private LookupOutcome lookupXref(String cardNum)` with `@CobolParagraph("2000-LOOKUP-XREF")` on the method)
- **Public entry point** named `run()` returning a result record that carries the captured stdout bytes (test sink) and the abend disposition; the test harness verifies the result-carried bytes against `stdout.txt` for byte-for-byte equality

## Phase 11: Test Class Override Map

The `CbTrn01CGoldenTest` class extends `GoldenRecordTest` and overrides four protected hook methods to bind the test to CBTRN01C-specific resources. No additional override is required for `expectedOutputs()` because CBTRN01C produces only a single expected file (`stdout.txt`); the default single-element behavior on the base class suffices. No override of `runProgram(...)` is required because the default base-class hook performs the 6-port constructor wiring used by the validation-only application class.

```java
// com.blitzy.carddemo.tests.golden.CbTrn01CGoldenTest
@Override
protected Class<?> programClass() {
    return com.blitzy.carddemo.application.transaction.CbTrn01C.class;
}

@Override
protected Path inputFile() {
    return resolveAppDataPath("dailytran.txt");
}

@Override
protected List<Path> auxiliaryInputs() {
    return List.of(
        resolveAppDataPath("cardxref.txt"),
        resolveAppDataPath("acctdata.txt"),
        resolveAppDataPath("custdata.txt"),
        resolveAppDataPath("carddata.txt")
    );
}

@Override
protected Path expectedOutputFile() {
    return resolveExpectedOutputPath("cbtrn01c", "stdout.txt");
}

// NO expectedOutputs() override needed; single-element default from GoldenRecordTest base class suffices
// NO runProgram(...) override needed; default base-class hook handles 6-port constructor wiring
```

When the `@Disabled` flag is removed and the test is unblocked by a committed `stdout.txt`, the test MUST verify each of the following twelve points:

1. Classpath fixture resolver returns non-null for each of the 5 ASCII inputs (`dailytran.txt`, `cardxref.txt`, `acctdata.txt`, `custdata.txt`, `carddata.txt`)
2. Expected `stdout.txt` exists and is non-empty (captured, not placeholder)
3. Expected output was committed via the capture procedure in `java/MIGRATION_NOTES.md` §1.6, NOT hand-edited
4. Original `app/data/ASCII/` files remain BYTE-IDENTICAL after the test run (the test harness MUST NOT mutate the source files per AAP §0.2.2)
5. No production dependency references `System.out` (DISPLAY translations use SLF4J in production; the test driver uses an unmasked test-only sink for byte-for-byte parity)
6. `@CobolProgram("CBTRN01C")` annotation is present on the `CbTrn01C` class
7. `@CobolParagraph` annotations are present on each translated paragraph method, citing the full original COBOL paragraph name (e.g., `2000-LOOKUP-XREF`, `9000-DALYTRAN-CLOSE`, `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS`)
8. No `--enable-preview` appears in the test runner's JVM args; no preview JEP 502, 505, 507, or 512 usage in production source code
9. The `-XX:+UseCompactObjectHeaders` flag (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) are documented in the test runner's JVM args per AAP §0.3.4
10. **NO virtual threads** are used in the CbTrn01C execution path; sequential DALYTRAN read order preserved exactly per AAP §0.6.6
11. The L372-L373 copy-paste bug is preserved in the Java translation: 9000-DALYTRAN-CLOSE emits `"ERROR CLOSING CUSTOMER FILE"` (wrong text) and references the CUSTFILE-STATUS equivalent (wrong variable) on close failure
12. DALYTRAN-RECORD double-DISPLAY pattern verified: on XREF or ACCT failure paths the offending record appears twice in stdout (once at L168, once via L181-L183 diagnostic for XREF failures, or via L178 diagnostic for ACCT failures)

## Phase 12: Capture Procedure Cross-Reference

The canonical COBOL build/run path used to capture `stdout.txt` is documented in `java/MIGRATION_NOTES.md` §1.6. This procedure exists because the user prompt left the regeneration path as a TODO marker per AAP §0.7.5; the capture procedure has since been documented in MIGRATION_NOTES.

Determinism considerations:

- NO Clock injection required — CBTRN01C does NOT invoke FUNCTION CURRENT-DATE anywhere in its 491-line source
- All 5 ASCII inputs are read-only references and are not mutated by the program
- The Java translation MUST preserve the sequential read order of DALYTRAN per AAP §0.6.6

Capture command shape (illustrative; canonical path documented in `java/MIGRATION_NOTES.md` §1.6): compile CBTRN01C with GnuCOBOL or z/OS COBOL, execute with FILE-CONTROL ASSIGN clauses pointing to the 5 ASCII fixtures (TRANSACT-FILE may be an empty file since it is never read), capture stdout, and commit the captured bytes as `stdout.txt` in this folder.

PAN exposure:

- COBOL CBTRN01C DOES DISPLAY DALYTRAN-RECORD at L168 on every iteration, which includes DALYTRAN-CARD-NUM (16-byte PAN) unmasked
- The L181-L183 diagnostic also emits DALYTRAN-CARD-NUM unmasked as part of the SKIPPING message
- Per AAP §0.7.2, production Java logs MUST mask all but the last 4 digits; the captured `stdout.txt` fixture preserves COBOL unmasked behavior for parity assertion via a test-only sink
- The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact

Until the capture is performed, `CbTrn01CGoldenTest` is `@Disabled("Awaiting COBOL CBTRN01C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the double-DISPLAY pattern on validation failures: DALYTRAN-RECORD is emitted twice (once at the error site, once at the diagnostic display).")`; the parity assertion is unreachable in that state.

Re-capture triggers: if the ASCII fixtures change OR the COBOL source changes, this fixture MUST be re-captured. Do NOT ad-hoc edit `stdout.txt`; always re-run the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 to refresh the baseline. Specifically:

- Changes to `app/cbl/CBTRN01C.cbl` of ANY kind (whitespace included) require re-capture because COBOL DISPLAY semantics are sensitive to source-level literal byte layouts
- Changes to `app/cpy/CVTRA06Y.cpy` (the DALYTRAN record layout) require re-capture because L168 dumps the full 350-byte CVTRA06Y record
- Changes to `app/cpy/CVACT03Y.cpy` (XREF) require re-capture because L236-L238 emit XREF field values
- Changes to `app/cpy/CVACT01Y.cpy` (ACCOUNT) DO NOT require re-capture in CBTRN01C because no ACCOUNT field appears in any L168/L178/L235-L238/L246/L249 DISPLAY in CBTRN01C; the L178 DISPLAY emits only ACCT-ID (the key), not any ACCOUNT-RECORD field
- Changes to `app/data/ASCII/dailytran.txt` always require re-capture because each record produces a unique L168 dump
- Changes to `app/data/ASCII/cardxref.txt` always require re-capture because cardxref membership determines which records take the XREF-failure path
- Changes to `app/data/ASCII/acctdata.txt` always require re-capture because acctdata membership determines which records take the ACCT-failure path
- Changes to `app/data/ASCII/custdata.txt` DO NOT require re-capture (file is opened but never read; the open-success path emits no DISPLAY)
- Changes to `app/data/ASCII/carddata.txt` DO NOT require re-capture (file is opened but never read)

The capture procedure produces a single `stdout.txt` file; no other expected-output files are required for CBTRN01C.

## Phase 13: Scenario Inventory

The captured `stdout.txt` fixture MUST exercise the following 15 scenarios. Each scenario corresponds to an observable behavior path in CBTRN01C and is verified by the byte-for-byte parity assertion:

1. **Happy path full sequence** — start banner -> per-record `DISPLAY DALYTRAN-RECORD` with success diagnostics (L235-L238 for XREF, L249 for ACCT) -> end banner
2. **XREF success + ACCT success** — full diagnostic chain emits 6 lines per record (L168 + L235 + L236 + L237 + L238 + L249)
3. **XREF success + ACCT failure** — L235-L238 emitted then L246 + L178; double-DISPLAY of the offending record verified indirectly (L168 carries the full DALYTRAN-RECORD; L178 carries ACCT-ID)
4. **XREF failure** — L232 emitted; L181-L183 from MAIN-PARA emitted as a single combined output line; PERFORM 3000-READ-ACCOUNT at L176 SKIPPED; ACCT diagnostics NOT emitted; double-DISPLAY of DALYTRAN-CARD-NUM verified (in L168 and again in the L181-L183 combined line)
5. **Sequential DALYTRAN ordering preserved** — records appear in stdout in the exact order they appear in `app/data/ASCII/dailytran.txt`; NO virtual threads used per AAP §0.6.6
6. **EOF detection** — final DALYTRAN read returns FILE STATUS '10'; END-OF-DAILY-TRANS-FILE set to 'Y' at L217; main loop terminates; end banner emitted at L195
7. **Opened-but-unused files** — CUSTOMER-FILE, CARD-FILE, TRANSACT-FILE successfully opened and closed (open success suppresses error DISPLAYs at L282/L318/L354 and close success suppresses error DISPLAYs at L390/L426/L462)
8. **L168 DALYTRAN-RECORD dump format** — the 350-byte record displayed as a single line, with fields concatenated in CVTRA06Y order; trailing FILLER preserved as trailing spaces
9. **Colon-placement preservation** — L236 emits `'CARD NUMBER: '` (HAS colon), L181 emits `'CARD NUMBER '` (NO colon), L237 emits `'ACCOUNT ID : '` (SPACE before colon), L238 emits `'CUSTOMER ID: '` (NO space before colon) — all four variants verified byte-by-byte
10. **DAILY vs DALYTRAN spelling** — L263 error message uses DAILY (`'ERROR OPENING DAILY TRANSACTION FILE'`) but the file SELECT name is DALYTRAN-FILE; preserved
11. **Z-prefix paragraph naming** — Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS lack the 4-digit numeric prefix used by other paragraphs; preserved
12. **L372-L373 copy-paste bug** — on DALYTRAN close failure, paragraph 9000-DALYTRAN-CLOSE emits `'ERROR CLOSING CUSTOMER FILE'` (wrong text) and references CUSTFILE-STATUS (wrong variable); preserved verbatim per AAP §0.7.1
13. **No RETURN-CODE setting on normal completion** — unlike CBTRN02C which sets RETURN-CODE = 4 on rejects, CBTRN01C leaves RETURN-CODE at default 0; only Z-ABEND-PROGRAM (via CEE3ABD at L473 with ABCODE = 999) affects the abend exit code
14. **No deterministic Clock required** — CBTRN01C performs NO FUNCTION CURRENT-DATE call; captured stdout is naturally reproducible given fixed inputs without any Clock injection
15. **Sequential execution mandate** — multiple test runs with the same inputs produce byte-identical stdout; NO non-deterministic reordering due to virtual threads or parallelism

Scenario sequencing notes for the fixture preparation team:

- The DALYTRAN fixture in `app/data/ASCII/dailytran.txt` (300 records) MUST exercise at least one instance each of scenarios 2 (success+success), 3 (XREF success / ACCT failure), and 4 (XREF failure). Verify by scanning the captured `stdout.txt` for at least one occurrence of L249 success line AND at least one occurrence of L246 INVALID ACCOUNT line AND at least one occurrence of L232 INVALID CARD line
- The XREF fixture in `app/data/ASCII/cardxref.txt` (about 37 records) is intentionally a SUBSET of DALYTRAN-CARD-NUM values appearing in `dailytran.txt`, so some DALYTRAN records WILL trigger the XREF-failure path
- The ACCT fixture in `app/data/ASCII/acctdata.txt` (about 50 records) is intentionally a SUBSET of XREF-ACCT-ID values appearing in `cardxref.txt`, so some XREF-success paths WILL trigger the ACCT-failure path
- The captured `stdout.txt` therefore contains a deterministic interleaving of all three scenarios; the byte-for-byte parity assertion verifies the EXACT interleaving order

## Contrast Matrix — CBTRN01C vs. sibling programs

The following table contrasts CBTRN01C with three sibling batch programs (CBTRN02C, CBTRN03C, CBACT04C) to highlight CBTRN01C's validation-only, no-arithmetic, no-clock character:

| Aspect | CBTRN01C (this) | CBTRN02C | CBTRN03C | CBACT04C |
|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CB (batch) | CB (batch) | CB (batch) |
| Role | Daily Transaction Validator (READ-ONLY) | FULL POSTING ENGINE | Paginated report writer | Interest calculator |
| Source line count | 491 lines | 731 lines | 649 lines | varies |
| Output file count | 1 (stdout.txt) | 5 (transact, acctdata, tcatbal, dalyrejs, stdout) | 2 (reptfile, stdout) | varies |
| JCL driver | NONE (verified absent) | POSTTRAN.jcl | TRANREPT.jcl | INTCALC.jcl |
| File ports | 6 (3 active + 3 unused) | 4 (all active) | 5 | varies |
| I-O mode files | 0 (all OPEN INPUT) | 2 (ACCTFILE + TCATBALF) | 0 | YES |
| WRITE / REWRITE | NONE | YES (TRANFILE, ACCTFILE, TCATBALF, DALYREJS) | NO (sequential output) | YES |
| Reject mechanism | NONE (DISPLAY only) | 430-byte DALYREJS records | N/A | N/A |
| Reject codes | 0 | 5 (100, 101, 102, 103, 109) | 999 abend only | N/A |
| BigDecimal arithmetic | NO (no math) | YES (heavy) | NO | YES |
| Clock dependency | NO | YES (Z-GET-DB2-FORMAT-TIMESTAMP) | NO | YES |
| Virtual threads allowed | NO (sequential mandated) | NO (sequential mandated) | NO | NO |
| Test fixture pattern | input/README + expected/README + 1 placeholder data file | input/README + expected/README + 5 placeholder data files | input/README + expected/README + 2 placeholder data files | input/README + expected/README |
| Parity criticality | secondary | THE MOST CRITICAL per AAP §0.6.11 | secondary | secondary |

CBTRN01C's single-output, no-arithmetic, no-clock, validation-only nature makes it the simplest of the four batch parity tests but still essential for verifying the DALYTRAN read path, XREF/ACCT random-access lookup, opened-but-unused file handling, and DISPLAY message verbatim preservation. The L372-L373 copy-paste bug is the marquee idiosyncrasy unique to CBTRN01C and absent from all sibling programs.

## Verbatim Message Catalog Cross-Reference

The 27 messages enumerated in Phase 5 are the COMPLETE catalog for CBTRN01C. Cross-references in `java/MIGRATION_NOTES.md` will record any message-string changes (NONE are expected per AAP §0.7.1). The messages preserve idiosyncrasies including the DAILY versus DALYTRAN inconsistency at L263, the L181 versus L236 colon variants, the L237 space-before-colon, the L238 no-space-before-colon, and the TWO-FAULT L372-L373 copy-paste bug. The Java translation MUST produce byte-identical strings for each of the 27 catalogued messages.

Catalog completeness verification: an automated test scans `app/cbl/CBTRN01C.cbl` for the substring `DISPLAY ` (with a trailing space) and counts occurrences. The expected count is 27 (matching the Phase 5 catalog). Any future modification to the COBOL source that adds or removes a DISPLAY statement MUST update both this README's Phase 5 table AND the captured `stdout.txt` fixture. The catalog ordering in Phase 5 is by source line number ascending; the Java translation MUST emit messages in the same source-line ordering whenever the corresponding paragraph's control flow executes.

Per-message Java sink mapping summary:

- Messages 1, 5 (start and end banners): emitted from the `run()` public entry point, before and after the main loop
- Message 2 (L168 DALYTRAN-RECORD dump): emitted from the main-loop body, once per record
- Messages 3, 4 (L178 and L181-L183 diagnostics): emitted from the main-loop body, conditionally based on lookup outcomes
- Messages 6, 14 (L219, L263 DALYTRAN errors): emitted from `dalyTranGetNext()` and `dalyTranOpen()` respectively
- Messages 7, 8, 9, 10, 11 (L232 and L235-L238 XREF lines): emitted from `lookupXref(String)`
- Messages 12, 13 (L246, L249 ACCT lines): emitted from `readAccount(long)`
- Messages 15, 16, 17, 18, 19 (L282, L300, L318, L336, L354 open errors): emitted from `custFileOpen()`, `xrefFileOpen()`, `cardFileOpen()`, `acctFileOpen()`, `tranFileOpen()` respectively
- Messages 20, 21, 22, 23, 24, 25 (L372, L390, L408, L426, L444, L462 close errors): emitted from the six close methods; note that L372 in `dalyTranClose()` carries the wrong-text copy-paste bug
- Message 26 (L470 abend banner): emitted from `zAbendProgram()` immediately before the CEE3ABD-equivalent abend
- Message 27 (L483/L487 file status line): emitted from `zDisplayIoStatus()` in both branches of its IF/ELSE

## Source Lineage

The following source files are referenced by this fixture. All files are UNCHANGED per AAP §0.1.1, §0.2.2, and §0.7.1:

- `app/cbl/CBTRN01C.cbl` (491 lines) — Primary COBOL source; PROGRAM-ID `CBTRN01C` at L23; AUTHOR `AWS` at L24; SELECT clauses at L28-L62; FD declarations at L66-L94; PROCEDURE DIVISION at L154; main loop at L164-L186; 18 paragraphs total (MAIN-PARA, 0000-DALYTRAN-OPEN, 0100-CUSTFILE-OPEN, 0200-XREFFILE-OPEN, 0300-CARDFILE-OPEN, 0400-ACCTFILE-OPEN, 0500-TRANFILE-OPEN, 1000-DALYTRAN-GET-NEXT, 2000-LOOKUP-XREF, 3000-READ-ACCOUNT, 9000-DALYTRAN-CLOSE, 9100-CUSTFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-CARDFILE-CLOSE, 9400-ACCTFILE-CLOSE, 9500-TRANFILE-CLOSE, Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS). The phase heading "Phase 4: Validation Flow (15 paragraphs)" is a stable identifier counting the validation-flow paragraphs and excluding the MAIN-PARA driver and two Z-utility paragraphs from the count.
- `app/cpy/CVTRA06Y.cpy` — DALYTRAN-RECORD layout (350 bytes; 14 fields including DALYTRAN-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD, DALYTRAN-SOURCE, DALYTRAN-DESC, DALYTRAN-AMT, DALYTRAN-MERCHANT-ID, DALYTRAN-MERCHANT-NAME, DALYTRAN-MERCHANT-CITY, DALYTRAN-MERCHANT-ZIP, DALYTRAN-CARD-NUM, DALYTRAN-ORIG-TS, DALYTRAN-PROC-TS, FILLER)
- `app/cpy/CVCUS01Y.cpy` — CUSTOMER-RECORD layout (500 bytes — used only for FD record-length declaration; never parsed in CBTRN01C since the file is opened but not read)
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD layout (50 bytes; XREF-CARD-NUM PIC X(16) key, XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11), FILLER PIC X(14))
- `app/cpy/CVACT02Y.cpy` — CARD-RECORD layout (150 bytes — used only for FD record-length declaration; never parsed in CBTRN01C since the file is opened but not read)
- `app/cpy/CVACT01Y.cpy` — ACCOUNT-RECORD layout (300 bytes)
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD layout (350 bytes — used only for FD record-length declaration; never parsed in CBTRN01C since the file is opened but not read)
- `app/data/ASCII/dailytran.txt` (105,300 bytes; 300 records) — DALYTRAN input fixture (REFERENCE; read-only; sequential 350-byte records each followed by LF)
- `app/data/ASCII/cardxref.txt` (1,850 bytes) — XREFFILE input fixture (REFERENCE; read-only; random access keyed on XREF-CARD-NUM)
- `app/data/ASCII/acctdata.txt` (15,050 bytes) — ACCTFILE input fixture (REFERENCE; read-only; random access keyed on FD-ACCT-ID)
- `app/data/ASCII/custdata.txt` (25,050 bytes) — CUSTFILE input fixture (REFERENCE; opened but never read)
- `app/data/ASCII/carddata.txt` (7,550 bytes) — CARDFILE input fixture (REFERENCE; opened but never read)

NO JCL driver exists for CBTRN01C in `app/jcl/`. Verified absence via `grep -l 'CBTRN01C' app/jcl/*.jcl` returning empty. The program is callable via direct method invocation by `CbTrn01CGoldenTest` rather than being scheduled by z/OS JCL.

## Cross-References

Test infrastructure:

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder (input files are referenced from `app/data/ASCII/`, not copied)
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbTrn01CGoldenTest.java` — JUnit 5 test class consuming this fixture (`@Disabled` until capture committed)
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class with byte-for-byte parity assertion and `resolveAppDataPath`/`resolveExpectedOutputPath` classpath resolvers

Application class under test:

- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CbTrn01C.java` — Class under test; carries `@CobolProgram("CBTRN01C")` Javadoc annotation

Domain records consumed by the application class:

- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/DalyTranRecord.java` — DALYTRAN-RECORD from CVTRA06Y (350 bytes; 14 fields; PRIMARY input record type for CBTRN01C)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CustomerRecord.java` — CUSTOMER-RECORD from CVCUS01Y (500 bytes; injected but never read in CBTRN01C path)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` — CARD-XREF-RECORD from CVACT03Y (50 bytes; lookup target for 2000-LOOKUP-XREF)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardRecord.java` — CARD-RECORD from CVACT02Y (150 bytes; injected but never read in CBTRN01C path)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java` — ACCOUNT-RECORD from CVACT01Y (300 bytes; lookup target for 3000-READ-ACCOUNT)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java` — TRAN-RECORD from CVTRA05Y (350 bytes; injected but never read in CBTRN01C path)

Domain ports (interfaces in `com.blitzy.carddemo.domain.port`):

- `DailyTransactionRepository` — sequential read API behind DALYTRAN
- `CustomerRepository` — random-access read API behind CUSTFILE (opened, never used)
- `CardXrefRepository` — random-access read API behind XREFFILE
- `CardRepository` — random-access read API behind CARDFILE (opened, never used)
- `AccountRepository` — random-access read API behind ACCTFILE
- `TransactionRepository` — random-access read API behind TRANFILE (opened, never used)

Project documentation:

- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation
- `java/README.md` — Build/run instructions for the Java module
- `java/application.properties.example` — 12-factor configuration template (codepages, file paths)

Sibling golden-record fixtures:

- `java/carddemo-tests/src/test/resources/golden/cbtrn02c/expected/README.md` (FULL POSTING ENGINE — THE MOST CRITICAL parity gate; structurally larger 14-phase contract with 5 outputs versus CBTRN01C's 1)

## Authority References

This README cites the following AAP sections; each section is the binding authority for the assertions it governs:

- AAP §0.1.1 — refactoring objective (byte-for-byte parity COBOL -> Java 25 LTS)
- AAP §0.2.1 — in-scope golden-record fixture path
- AAP §0.2.2 — `app/` tree IMMUTABLE
- AAP §0.3.1 — harness directory layout convention (`<program>/input/` and `<program>/expected/`)
- AAP §0.3.2 — records pattern, sealed-type pattern, repository ports
- AAP §0.3.4 — JVM flags (compact object headers and Shenandoah generational)
- AAP §0.3.6 — hexagonal architecture; no Spring
- AAP §0.4.1 — CBTRN01C -> CbTrn01C; ASCII fixtures REFERENCE only and NEVER copied
- AAP §0.6.1 — `Decimals` utility and BigDecimal scale 2 for monetary field shapes (DALYTRAN-AMT even though no arithmetic occurs in CBTRN01C)
- AAP §0.6.2 — sealed-type pattern
- AAP §0.6.4 — `java.time` only
- AAP §0.6.5 — `java.nio.file`; EBCDIC IBM-1047 default codepage
- AAP §0.6.6 — sequential execution preserved; `ScopedValue` replaces `ThreadLocal`
- AAP §0.6.11 — golden-record harness PR gate; `@Disabled` until captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve idiosyncrasies verbatim
- AAP §0.7.2 — no PAN in production logs
- AAP §0.7.4 — forbidden preview features and no `default` branches that hide cases
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — citation discipline `[<path>:Lnnn]`

## DO NOT Modify the Fixture Data Without Re-Capture

The `stdout.txt` file (once captured) is COUPLED with the read-only ASCII fixtures under `app/data/ASCII/` and with the Java translation's faithful sequential execution. If any input changes (ASCII fixture content) or the COBOL source changes, this fixture MUST be re-captured per `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits to `stdout.txt` without re-capture WILL break byte-for-byte parity and cause the harness to fail in a way that masks the true source of divergence.

Specifically, do NOT make any of the following modifications:

- Do NOT "fix" the COPY-PASTE BUG at L372-L373 — PRESERVED per AAP §0.7.1
- Do NOT "fix" the colon placement inconsistencies at L181, L236, L237, L238 — PRESERVED per AAP §0.7.1
- Do NOT "fix" the DAILY vs DALYTRAN spelling inconsistency at L263 — PRESERVED per AAP §0.7.1
- Do NOT "fix" the opened-but-unused CUSTOMER, CARD, and TRANSACT file boilerplate — PRESERVED per AAP §0.7.1
- Do NOT mask the PAN in captured `stdout.txt` — the test driver uses an unmasked sink, while production logs apply masking via a separate sink per AAP §0.7.2
- Do NOT introduce virtual threads — sequential execution is mandated per AAP §0.6.6 because DISPLAY ordering is observable
- Do NOT rename `CbTrn01C` to a more "descriptive" Java class name — the one-class-per-PROGRAM-ID rule per AAP §0.4.1 mandates preserving the COBOL name in PascalCase
- Do NOT split `CbTrn01C` into multiple Java classes (for example, a separate `XrefLookupService`) — the one-class-per-PROGRAM-ID rule forbids consolidation or fragmentation
- Do NOT remove the `@CobolProgram` or `@CobolParagraph` Javadoc annotations — they are mandatory traceability markers per AAP §0.7.1
- Do NOT introduce Spring, Hibernate, Spring Batch, Spring Security, or any application container — the hexagonal architecture per AAP §0.3.6 mandates plain Java with constructor injection only
- Do NOT use `java.util.Date`, `java.util.Calendar`, or `java.text.SimpleDateFormat` anywhere in the Java translation — `java.time` is mandatory per AAP §0.6.4
- Do NOT use `java.io.File`, `java.io.FileReader`, or `java.io.FileInputStream` — `java.nio.file` is mandatory per AAP §0.6.5
- Do NOT use `ThreadLocal` — `ScopedValue` is mandatory per AAP §0.6.6 in any place where cross-method context propagation is needed
- Do NOT use `double` or `float` for any monetary value — BigDecimal scale 2 via `Decimals` is mandatory per AAP §0.6.1
- Do NOT use `--enable-preview` JVM flag — finalized Java 25 features only per AAP §0.7.4
- Do NOT use `default` clauses in pattern-matching switch over sealed types — exhaustiveness checking is the safety guarantee per AAP §0.7.4

Any deviation from the above MUST be documented in `java/MIGRATION_NOTES.md` with a citation to the offending COBOL construct and the rationale; deviations that lack documentation will be reverted in code review without exception.
