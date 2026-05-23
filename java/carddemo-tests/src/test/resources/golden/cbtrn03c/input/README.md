# CBTRN03C (Paginated Transaction Detail Report) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is **intentionally documentation-only** — it contains NO fixture data files. CBTRN03C is the paginated transaction detail report writer batch program (PROGRAM-ID `CBTRN03C` at `app/cbl/CBTRN03C.cbl:L23`; AUTHOR `AWS` at `app/cbl/CBTRN03C.cbl:L24`), translated to Java `com.blitzy.carddemo.application.transaction.CbTrn03C` per AAP §0.4.1. The program is driven by `app/jcl/TRANREPT.jcl` step `STEP10R EXEC PGM=CBTRN03C` at `app/jcl/TRANREPT.jcl:L59`, which binds five input DDs (TRANFILE, CARDXREF, TRANTYPE, TRANCATG, DATEPARM) at `app/jcl/TRANREPT.jcl:L65-L74` and one output DD (TRANREPT, LRECL=133, RECFM=FB) at `app/jcl/TRANREPT.jcl:L76-L80`. All fixture artifacts (the deterministic DATEPARM block, the expected report `reptfile.txt`, the captured DISPLAY output `stdout.txt`) live in the sibling `../expected/` folder; ASCII data fixtures live under `app/data/ASCII/` (read-only, classpath-referenced, NEVER copied per AAP §0.4.1).

## Why this folder is documentation-only

1. CBTRN03C is a **pure JCL batch program** (PROGRAM-ID prefix `CB` per AAP §0.4.1 batch package convention). Its inputs arrive via five JCL DD statements bound at `app/jcl/TRANREPT.jcl:L65-L74`: TRANFILE (sequential transaction backup), CARDXREF (indexed VSAM KSDS keyed by 16-byte card number), TRANTYPE (indexed VSAM KSDS keyed by 2-byte type code), TRANCATG (indexed VSAM KSDS keyed by 6-byte composite TYPE+CAT key), DATEPARM (sequential parameter file with start/end date pair). The FILE-CONTROL paragraph that declares these SELECT clauses runs `app/cbl/CBTRN03C.cbl:L28-L57`.
2. Inputs are not synthesized fixtures specific to this test folder — they reuse the **read-only ASCII fixtures under `app/data/ASCII/`** (per AAP §0.4.1 the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard lists these as REFERENCE only — they are NOT copied here). Specifically: `app/data/ASCII/dailytran.txt` (105,300 bytes; 350-byte fixed-width transaction records per `app/cpy/CVTRA05Y.cpy`), `app/data/ASCII/cardxref.txt` (1,850 bytes; 50-byte fixed-width card cross-reference records per `app/cpy/CVACT03Y.cpy`), `app/data/ASCII/trantype.txt` (427 bytes; 60-byte fixed-width transaction-type records per `app/cpy/CVTRA03Y.cpy`), `app/data/ASCII/trancatg.txt` (1,098 bytes; 60-byte fixed-width transaction-category records per `app/cpy/CVTRA04Y.cpy`).
3. The DATEPARM block is **test-owned scaffolding** synthesized by the Java test class — a 21-byte payload of the form `YYYY-MM-DD<SPACE>YYYY-MM-DD` (10-byte start date + 1-byte separator + 10-byte end date) per the WS-DATEPARM-RECORD layout at `app/cbl/CBTRN03C.cbl:L122-L125`. Because it varies per test scenario to exercise the date-range filter at `app/cbl/CBTRN03C.cbl:L173-L174`, it is NOT a static fixture; it is injected programmatically by the test class.
4. Per the sibling precedent established by `golden/corpt00c/input/` and `golden/cotrn00c/input/`, the conventional `input/` subdirectory is preserved as a documentation marker even though no fixture data files reside there.
5. The harness convention from AAP §0.3.1 and §0.6.11 mandates the `input/` + `expected/` pairing per program for discoverability and parallel structure across all 28 program test folders. The Java test class `CbTrn03CGoldenTest` resolves all expected-output paths through `resolveExpectedOutputPath("cbtrn03c", ...)` to `src/test/resources/golden/cbtrn03c/expected/`.

Cross-references:

- `../expected/README.md` — Authoritative fixture contract for the CBTRN03C golden-record test
- `../expected/reptfile.txt` — Captured 133-byte fixed-width paginated report output (CAPTURE PLACEHOLDER; created by COBOL reference run per `java/MIGRATION_NOTES.md` §1.6)
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER)
- `app/data/ASCII/dailytran.txt` — Primary input transactions (REFERENCE; read via classpath; NEVER copied)
- `app/data/ASCII/cardxref.txt` — Card cross-reference lookup table (REFERENCE)
- `app/data/ASCII/trantype.txt` — Transaction type lookup table (REFERENCE)
- `app/data/ASCII/trancatg.txt` — Transaction category lookup table (REFERENCE)

## Conceptual input contract (documented; data referenced from app/data/ASCII/ and ../expected/)

### JCL DD statements + FD record layouts

| DD Name | FD Record Length | ORG / ACCESS | Key Field | Source Path | Notes |
|---|---|---|---|---|---|
| TRANFILE | 350 bytes (FD-TRANS-DATA X(304) + FD-TRAN-PROC-TS X(26) + FD-FILLER X(20)) | SEQUENTIAL | (none) | `app/data/ASCII/dailytran.txt` | Primary input transactions; layout per `app/cpy/CVTRA05Y.cpy`; FD declared at `app/cbl/CBTRN03C.cbl:L61-L65` |
| CARDXREF | 50 bytes (FD-XREF-CARD-NUM X(16) + FD-XREF-DATA X(34)) | INDEXED RANDOM | FD-XREF-CARD-NUM (16 bytes) | `app/data/ASCII/cardxref.txt` | Lookup by 16-byte card number; layout per `app/cpy/CVACT03Y.cpy`; FD declared at `app/cbl/CBTRN03C.cbl:L67-L70` |
| TRANTYPE | 60 bytes (FD-TRAN-TYPE X(02) + FD-TRAN-DATA X(58)) | INDEXED RANDOM | FD-TRAN-TYPE (2 bytes) | `app/data/ASCII/trantype.txt` | Lookup by 2-byte type code; layout per `app/cpy/CVTRA03Y.cpy`; FD declared at `app/cbl/CBTRN03C.cbl:L72-L75` |
| TRANCATG | 60 bytes (FD-TRAN-TYPE-CD X(02) + FD-TRAN-CAT-CD 9(04) + FD-TRAN-CAT-DATA X(54)) | INDEXED RANDOM | FD-TRAN-CAT-KEY (6 bytes composite) | `app/data/ASCII/trancatg.txt` | Lookup by 6-byte composite key; layout per `app/cpy/CVTRA04Y.cpy`; FD declared at `app/cbl/CBTRN03C.cbl:L77-L82` |
| DATEPARM | 80 bytes (FD-DATEPARM-REC X(80)) | SEQUENTIAL | (none) | Synthesized by test class | 21-byte payload + 59-byte trailing padding; payload layout per WS-DATEPARM-RECORD at `app/cbl/CBTRN03C.cbl:L122-L125` |
| TRANREPT (output) | 133 bytes (FD-REPTFILE-REC X(133)) | SEQUENTIAL | (none) | `../expected/reptfile.txt` | Fixed-width FB report file; FD declared at `app/cbl/CBTRN03C.cbl:L84-L85` |

### WS-DATEPARM-RECORD layout (21 bytes payload)

```cobol
01 WS-DATEPARM-RECORD.
    05 WS-START-DATE      PIC X(10).
    05 FILLER             PIC X(01).
    05 WS-END-DATE        PIC X(10).
```

WS-START-DATE and WS-END-DATE are each 10-byte ASCII strings in `YYYY-MM-DD` format. The 1-byte FILLER is a single SPACE separator. The 21-byte payload is read from the DATEPARM DD via `0550-DATEPARM-READ` at `app/cbl/CBTRN03C.cbl:L220-L243`. The Java translation MUST mirror this byte layout exactly so any captured DISPLAY of `'Reporting from '` WS-START-DATE `' to '` WS-END-DATE at `app/cbl/CBTRN03C.cbl:L232-L233` is byte-identical to the COBOL baseline.

### Date-range filter + 3 indexed lookups + pagination

1. **File openings**: 0000-TRANFILE-OPEN (`app/cbl/CBTRN03C.cbl:L376-L392`), 0100-REPTFILE-OPEN (L394-L410), 0200-CARDXREF-OPEN (L412-L428), 0300-TRANTYPE-OPEN (L430-L446), 0400-TRANCATG-OPEN (L448-L464), 0500-DATEPARM-OPEN (L466-L482) — open all 6 files in sequence. Any open failure DISPLAYs a specific error message, runs 9910-DISPLAY-IO-STATUS, and PERFORMs 9999-ABEND-PROGRAM.
2. **0550-DATEPARM-READ** at `app/cbl/CBTRN03C.cbl:L220-L243`: reads the 21-byte DATEPARM record into WS-START-DATE / WS-END-DATE; on success DISPLAYs `'Reporting from '` WS-START-DATE `' to '` WS-END-DATE at L232-L233.
3. **Main loop** at `app/cbl/CBTRN03C.cbl:L170-L206` (PERFORM UNTIL END-OF-FILE = 'Y'):
   - PERFORM 1000-TRANFILE-GET-NEXT at L172 (READ TRANSACT-FILE INTO TRAN-RECORD; sets END-OF-FILE='Y' on '10' status)
   - Date-range filter at L173-L174: `IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE` — only in-range records are processed
   - DISPLAY entire 350-byte TRAN-RECORD at L180 (includes unmasked TRAN-CARD-NUM)
   - On card-number change (L181-L188): PERFORM 1120-WRITE-ACCOUNT-TOTALS at L183 (if not first time), then PERFORM 1500-A-LOOKUP-XREF at L187
   - PERFORM 1500-B-LOOKUP-TRANTYPE at L190; INVALID KEY -> ABEND with `'INVALID TRANSACTION TYPE : '`
   - PERFORM 1500-C-LOOKUP-TRANCATG at L195; INVALID KEY -> ABEND with `'INVALID TRAN CATG KEY : '`
   - PERFORM 1100-WRITE-TRANSACTION-REPORT at L196 — page break check + add TRAN-AMT to running totals + emit detail line
4. **At EOF** at `app/cbl/CBTRN03C.cbl:L197-L203`: DISPLAY `'TRAN-AMT '` TRAN-AMT (L198), DISPLAY `'WS-PAGE-TOTAL'`  WS-PAGE-TOTAL (L199), ADD TRAN-AMT TO WS-PAGE-TOTAL / WS-ACCOUNT-TOTAL (L200-L201), PERFORM 1110-WRITE-PAGE-TOTALS (L202), PERFORM 1110-WRITE-GRAND-TOTALS (L203). Two paragraphs share the `1110-` numeric prefix: 1110-WRITE-PAGE-TOTALS at L293 and 1110-WRITE-GRAND-TOTALS at L318. Three paragraphs share the `1120-` numeric prefix: 1120-WRITE-ACCOUNT-TOTALS at L306, 1120-WRITE-HEADERS at L324, and 1120-WRITE-DETAIL at L361. Per AAP §0.7.1 the shared-prefix paragraph names ARE preserved faithfully in the translation; Java uses uniquely-named private methods but cites the original prefix via a Javadoc tag.
5. **Pagination** at `app/cbl/CBTRN03C.cbl:L282-L285`: `IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` triggers 1110-WRITE-PAGE-TOTALS + 1120-WRITE-HEADERS. PAGE_SIZE = 20 lines per page (WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20 at `app/cbl/CBTRN03C.cbl:L131-L132`). 1120-WRITE-HEADERS emits REPORT-NAME-HEADER + blank line (WS-BLANK-LINE 133 SPACES per L133) + TRANSACTION-HEADER-1 + TRANSACTION-HEADER-2 from `app/cpy/CVTRA07Y.cpy`.
6. **9000-9500 close routines** at `app/cbl/CBTRN03C.cbl:L514-L621`: close all six files with respective error DISPLAYs (`'ERROR CLOSING POSTED TRANSACTION FILE'` at L525, `'ERROR CLOSING REPORT FILE'` at L543, `'ERROR CLOSING CROSS REF FILE'` at L562, `'ERROR CLOSING TRANSACTION TYPE FILE'` at L580, `'ERROR CLOSING TRANSACTION CATG FILE'` at L598, `'ERROR CLOSING DATE PARM FILE'` at L616).
7. **GOBACK** at `app/cbl/CBTRN03C.cbl:L217` — return to caller (z/OS initiator). The unconditional end banner `'END OF EXECUTION OF PROGRAM CBTRN03C'` is emitted immediately before GOBACK at L215.

**EOF stale-amount bug**: at `app/cbl/CBTRN03C.cbl:L197-L203` the program adds the LAST TRAN-AMT (still in register from the previous successful read) to WS-PAGE-TOTAL and WS-ACCOUNT-TOTAL AGAIN AFTER the loop has already added it during 1100-WRITE-TRANSACTION-REPORT at L287-L288. This causes the page total and grand total to count the final record twice. Per AAP §0.7.1 this bug is PRESERVED FAITHFULLY in Java translation; it is documented in `java/MIGRATION_NOTES.md` §1.6 as a known COBOL bug.

### Conceptual fixture data (locations and source-of-truth)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|---|---|---|---|
| TRANFILE | `app/data/ASCII/dailytran.txt` (105,300 bytes; 350-byte records) | UNCHANGED reference data | Primary input transactions; filtered by date range |
| CARDXREF | `app/data/ASCII/cardxref.txt` (1,850 bytes; 50-byte records) | UNCHANGED reference data | Card to customer/account lookup |
| TRANTYPE | `app/data/ASCII/trantype.txt` (427 bytes; 60-byte records) | UNCHANGED reference data | Transaction type code to description |
| TRANCATG | `app/data/ASCII/trancatg.txt` (1,098 bytes; 60-byte records) | UNCHANGED reference data | Composite category key to description |
| DATEPARM | Synthesized by test (21-byte payload) | TEST-OWNED scaffolding | Date-range filter parameters |
| reptfile.txt (output) | `../expected/reptfile.txt` | CAPTURE PLACEHOLDER | 133-byte FB report rows |
| stdout.txt (output) | `../expected/stdout.txt` | CAPTURE PLACEHOLDER | DISPLAY statements captured |

## Cross-reference to the Java test class

- Java test class FQN: `com.blitzy.carddemo.tests.golden.CbTrn03CGoldenTest`
- Java test class path: `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbTrn03CGoldenTest.java`
- Extends abstract base class: `com.blitzy.carddemo.tests.golden.GoldenRecordTest`
- Class under test FQN: `com.blitzy.carddemo.application.transaction.CbTrn03C` (transaction subpackage per AAP §0.4.1)

Override summary (all expected-output overrides route to `../expected/`):

- `programClass()` returns `com.blitzy.carddemo.application.transaction.CbTrn03C.class`
- `inputFile()` returns the classpath-resolved path to `app/data/ASCII/dailytran.txt` (primary input — does NOT route through `resolveExpectedOutputPath`)
- `auxiliaryInputs()` returns classpath-resolved paths to `app/data/ASCII/cardxref.txt`, `app/data/ASCII/trantype.txt`, `app/data/ASCII/trancatg.txt`, and the test-synthesized DATEPARM payload
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cbtrn03c", "stdout.txt")` to `../expected/stdout.txt`
- `expectedOutputs()` returns ExpectedOutput entries for `stdout.txt` AND `reptfile.txt` (both under `../expected/`)

`@Disabled` verification list (12 points the enabled test must verify):

1. Opens six files in correct order (TRANFILE -> REPTFILE -> CARDXREF -> TRANTYPE -> TRANCATG -> DATEPARM) per `app/cbl/CBTRN03C.cbl:L161-L166`
2. Reads DATEPARM record, parses 21-byte payload into WS-START-DATE / WS-END-DATE per L220-L243
3. DISPLAYs `'Reporting from '` WS-START-DATE `' to '` WS-END-DATE exactly per L232-L233
4. Date-range filter `TRAN-PROC-TS (1:10) >= start AND <= end` matches COBOL byte-comparison semantics per L173-L174 (ISO-8601 string comparison gives chronological ordering for `YYYY-MM-DD` format)
5. DISPLAYs entire TRAN-RECORD for each in-range transaction per L180
6. CARDXREF lookup by 16-byte FD-XREF-CARD-NUM per L484-L492; INVALID KEY -> ABEND with code 23 moved into IO-STATUS then CEE3ABD with ABCODE 999
7. TRANTYPE lookup by 2-byte FD-TRAN-TYPE per L494-L502; INVALID KEY -> ABEND
8. TRANCATG lookup by 6-byte composite FD-TRAN-CAT-KEY per L504-L512; INVALID KEY -> ABEND
9. Pagination at PAGE_SIZE = 20 lines per page; page totals + headers emitted on break per L282-L285
10. Account totals on card-number change via 1120-WRITE-ACCOUNT-TOTALS per L181-L188 + L306-L316
11. Page totals and grand totals at EOF — **EOF stale-amount bug preserved**: last TRAN-AMT added twice to grand total per L197-L203
12. Shared-prefix paragraph names from COBOL (`1110-*` x2 at L293/L318, `1120-*` x3 at L306/L324/L361) preserved via Javadoc tags on uniquely-named Java methods

## Capture procedure cross-reference

- See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CBTRN03C) for the COBOL build/run path used to capture `../expected/reptfile.txt` and `../expected/stdout.txt`.
- The capture procedure exists because the user prompt left this as a `TODO` marker per AAP §0.7.5.
- The capture must be deterministic — input dates fixed via the synthesized DATEPARM payload (no `FUNCTION CURRENT-DATE` dependency in CBTRN03C itself, confirmed by inspection of `app/cbl/CBTRN03C.cbl` — no CURRENT-DATE reference exists). The only system-dependent call is `CEE3ABD` at `app/cbl/CBTRN03C.cbl:L630`, reached only on file-open / file-read / file-write / file-close errors and INVALID KEY conditions, none of which fire on the captured golden inputs.
- Until capture is performed and `../expected/reptfile.txt` + `../expected/stdout.txt` are committed with non-placeholder content, `CbTrn03CGoldenTest` remains `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **DD-driven file binding** at `app/jcl/TRANREPT.jcl:L65-L80`: five input DDs + one output DD. The Java translation MUST accept these as constructor-injected ports (per AAP §0.3.2 repository pattern); the composition root wires each DD to the appropriate adapter (file-backed for ASCII fixtures, in-memory for synthesized DATEPARM).
- **TRAN-PROC-TS(1:10) date filter** at `app/cbl/CBTRN03C.cbl:L173-L174`: COBOL byte-substring comparison on positions 1-10 of the 26-byte TRAN-PROC-TS field. ISO-8601 string comparison is chronologically correct for `YYYY-MM-DD` format. Java translation uses `tranRecord.tranProcTs().substring(0, 10)` or equivalent lexicographic `String.compareTo` comparison.
- **3 indexed lookups** at `app/cbl/CBTRN03C.cbl:L484-L512`: CARDXREF / TRANTYPE / TRANCATG. INVALID KEY for each abends via 9999-ABEND-PROGRAM (L626-L630) with `MOVE 999 TO ABCODE` and `CALL 'CEE3ABD'`. Java translation throws typed exceptions with `abend_code=999` preserved as a field on the exception per AAP §0.7.1.
- **Composite TRANCATG key** at `app/cbl/CBTRN03C.cbl:L79-L81`: FD-TRAN-CAT-KEY = FD-TRAN-TYPE-CD X(02) + FD-TRAN-CAT-CD 9(04) = 6 bytes total. The Java port for TRANCATG uses a composite key record (compact constructor validates length) per AAP §0.6.2 sealed-type pattern.
- **PAGE_SIZE = 20** at `app/cbl/CBTRN03C.cbl:L131-L132` (WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20). Hardcoded in COBOL source. Java translation declares as `static final int PAGE_SIZE = 20` on `CbTrn03C`.
- **133-byte fixed-width REPORT line** at `app/cbl/CBTRN03C.cbl:L84-L85` (FD-REPTFILE-REC PIC X(133)). REPORT-NAME-HEADER, TRANSACTION-DETAIL-REPORT, TRANSACTION-HEADER-1, TRANSACTION-HEADER-2, REPORT-PAGE-TOTALS, REPORT-ACCOUNT-TOTALS, REPORT-GRAND-TOTALS from `app/cpy/CVTRA07Y.cpy` are each 133 bytes wide. The Java translation MUST emit exactly 133 bytes per line.
- **Blank line** (WS-BLANK-LINE PIC X(133) VALUE SPACES at `app/cbl/CBTRN03C.cbl:L133`): emitted between report name header and column headers via 1120-WRITE-HEADERS at L324-L341.
- **Account totals on card-number change** via 1120-WRITE-ACCOUNT-TOTALS at `app/cbl/CBTRN03C.cbl:L306-L316`: tracked via WS-CURR-CARD-NUM at L137. When the current transaction's card number differs, write account totals and reset.
- **EOF stale-amount bug PRESERVED** at `app/cbl/CBTRN03C.cbl:L197-L203`: at EOF, after the loop, ADD TRAN-AMT to WS-PAGE-TOTAL and WS-ACCOUNT-TOTAL AGAIN even though the last in-range TRAN-AMT was already added during the final iteration's call to 1100-WRITE-TRANSACTION-REPORT at L287-L288. This is a COBOL bug that counts the last transaction twice in the grand total. Per AAP §0.7.1 the bug is PRESERVED FAITHFULLY in Java translation. Documented in `java/MIGRATION_NOTES.md` §1.6.
- **Shared-numeric-prefix paragraph names PRESERVED** at L293/L318 (both start with `1110-`) and at L306/L324/L361 (all start with `1120-`). The full paragraph names are distinct (1110-WRITE-PAGE-TOTALS vs 1110-WRITE-GRAND-TOTALS; 1120-WRITE-ACCOUNT-TOTALS vs 1120-WRITE-HEADERS vs 1120-WRITE-DETAIL); only the numeric prefix is shared, an unusual COBOL convention. Per AAP §0.7.1 the Java translation uses uniquely-named private methods (e.g., `writePageTotals1110()` and `writeGrandTotals1110()`) but cites the original full paragraph name via Javadoc `@CobolParagraph("1110-WRITE-PAGE-TOTALS")` annotation.
- **TRAN-AMT BigDecimal scale 2** per CVTRA05Y (PIC S9(09)V99). Java translation uses `java.math.BigDecimal` with `MathContext.DECIMAL128` and `RoundingMode.DOWN` (truncation) for default arithmetic per AAP §0.6.1 via the `Decimals` utility. Trailing zeros preserved via `setScale(2, RoundingMode.DOWN)`.
- **PIC -ZZZ,ZZZ,ZZZ.ZZ formatting** for TRAN-REPORT-AMT (CVTRA07Y) and `+ZZZ,ZZZ,ZZZ.ZZ` for totals. Java translation preserves the exact COBOL numeric edit pattern (zero-suppression with leading space/sign, comma separators, fixed 2-decimal precision) at byte level.
- **No FUNCTION CURRENT-DATE** in CBTRN03C source. The program does not embed system clock readings; all dates flow from DATEPARM. This eliminates the need for `ScopedValue<Clock>` injection in the Java test (contrast with CORPT00C which does use CURRENT-DATE).
- **EBCDIC vs ASCII input**: production COBOL deck would read EBCDIC (codepage IBM-1047 per AAP §0.6.5). The `app/data/ASCII/` fixtures are pre-transcoded ASCII; Java tests run in ASCII mode via `Charset.forName("US-ASCII")` or default `UTF_8`. Per-file codepage is configurable via `application.properties` per AAP §0.6.5.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CbTrn03C` MUST carry `@CobolProgram("CBTRN03C")` annotation citing the original PROGRAM-ID, source path `app/cbl/CBTRN03C.cbl`, and translation date.
- **PAN-masking dichotomy**: CBTRN03C DISPLAYs the entire 350-byte TRAN-RECORD at L180, which includes the 16-byte TRAN-CARD-NUM field. Per AAP §0.7.2 the Java production logger MUST mask all but the last 4 digits when writing operational logs. However, per AAP §0.7.1 the captured `../expected/stdout.txt` fixture for the parity test PRESERVES the COBOL behavior verbatim (the COBOL DISPLAY emits the full PAN). The Java production logger applies masking; the Java test driver captures unmasked stdout for byte-for-byte parity assertion. The test stdout sink is therefore distinct from the production log sink.
- **VERBATIM DISPLAY messages preserved EXACTLY**. The fixture's `../expected/stdout.txt` MUST capture these strings byte-for-byte with ASCII single quotes and trailing literal text exactly as the COBOL source emits:
  - L160: `'START OF EXECUTION OF PROGRAM CBTRN03C'`
  - L232-L233: `'Reporting from '` WS-START-DATE `' to '` WS-END-DATE (preserve SPACE-`to`-SPACE in the second literal)
  - L180: TRAN-RECORD (entire 350-byte record DISPLAYed; reproduces all CVTRA05Y fields concatenated, including unmasked TRAN-CARD-NUM)
  - L198: `'TRAN-AMT '` TRAN-AMT (preserve 1 trailing SPACE inside the literal)
  - L199: `'WS-PAGE-TOTAL'` WS-PAGE-TOTAL (NO trailing space inside the literal)
  - L215: `'END OF EXECUTION OF PROGRAM CBTRN03C'`
  - L238: `'ERROR READING DATEPARM FILE'`
  - L266: `'ERROR READING TRANSACTION FILE'`
  - L354: `'ERROR WRITING REPTFILE'`
  - L387: `'ERROR OPENING TRANFILE'`
  - L405: `'ERROR OPENING REPTFILE'`
  - L423: `'ERROR OPENING CROSS REF FILE'`
  - L441: `'ERROR OPENING TRANSACTION TYPE FILE'`
  - L459: `'ERROR OPENING TRANSACTION CATG FILE'`
  - L477: `'ERROR OPENING DATE PARM FILE'`
  - L487: `'INVALID CARD NUMBER : '` FD-XREF-CARD-NUM (preserve SPACE-COLON-SPACE inside the literal)
  - L497: `'INVALID TRANSACTION TYPE : '` FD-TRAN-TYPE
  - L507: `'INVALID TRAN CATG KEY : '` FD-TRAN-CAT-KEY
  - L525: `'ERROR CLOSING POSTED TRANSACTION FILE'`
  - L543: `'ERROR CLOSING REPORT FILE'`
  - L562: `'ERROR CLOSING CROSS REF FILE'`
  - L580: `'ERROR CLOSING TRANSACTION TYPE FILE'`
  - L598: `'ERROR CLOSING TRANSACTION CATG FILE'`
  - L616: `'ERROR CLOSING DATE PARM FILE'`
  - L627: `'ABENDING PROGRAM'`
  - L640, L644: `'FILE STATUS IS: NNNN'` IO-STATUS-04 (literal contains the 4-character placeholder text `NNNN`; the actual numeric file-status code follows via IO-STATUS-04 variable)
- **No Unicode ellipsis** anywhere in the fixture — only three ASCII periods `...` per AAP §0.7.4 forbidden content.

## Source lineage

The following source-branch files inform this fixture. All remain UNCHANGED per AAP §0.1.1 / §0.2.2 / §0.7.1.

- `app/cbl/CBTRN03C.cbl` (649 lines) — Paginated transaction detail report writer batch COBOL program. PROGRAM-ID `CBTRN03C` at L23; AUTHOR `AWS` at L24. Key paragraphs: PROCEDURE DIVISION entry (L159), 0550-DATEPARM-READ (L220), 1000-TRANFILE-GET-NEXT (L248), 1100-WRITE-TRANSACTION-REPORT (L274), 1110-WRITE-PAGE-TOTALS (L293), 1120-WRITE-ACCOUNT-TOTALS (L306), 1110-WRITE-GRAND-TOTALS (L318), 1120-WRITE-HEADERS (L324), 1111-WRITE-REPORT-REC (L343), 1120-WRITE-DETAIL (L361), 0000-TRANFILE-OPEN (L376), 0100-REPTFILE-OPEN (L394), 0200-CARDXREF-OPEN (L412), 0300-TRANTYPE-OPEN (L430), 0400-TRANCATG-OPEN (L448), 0500-DATEPARM-OPEN (L466), 1500-A-LOOKUP-XREF (L484), 1500-B-LOOKUP-TRANTYPE (L494), 1500-C-LOOKUP-TRANCATG (L504), 9000-TRANFILE-CLOSE (L514), 9100-REPTFILE-CLOSE (L532), 9200-CARDXREF-CLOSE (L551), 9300-TRANTYPE-CLOSE (L569), 9400-TRANCATG-CLOSE (L587), 9500-DATEPARM-CLOSE (L605), 9999-ABEND-PROGRAM (L626), 9910-DISPLAY-IO-STATUS (L633).
- `app/jcl/TRANREPT.jcl` (84 lines) — JCL job driving CBTRN03C. Job statement at L1. STEP05R (L23) invokes REPROC to unload TRANSACT.VSAM.KSDS into TRANSACT.BKUP. STEP05R (L37) sorts and filters TRANSACT.BKUP into TRANSACT.DALY by date range using DFSORT SYMNAMES at L40-L48. STEP10R at L59-L80 invokes `PGM=CBTRN03C` with the six DD bindings (TRANFILE L65-L66, CARDXREF L67-L68, TRANTYPE L69-L70, TRANCATG L71-L72, DATEPARM L73-L74, TRANREPT L76-L80).
- `app/cpy/CVTRA05Y.cpy` — `01 TRAN-RECORD` 350-byte fixed-width layout. Fields include TRAN-ID, TRAN-TYPE-CD, TRAN-CAT-CD, TRAN-SOURCE, TRAN-DESC, TRAN-AMT (PIC S9(09)V99), TRAN-MERCHANT-ID, TRAN-MERCHANT-NAME, TRAN-MERCHANT-CITY, TRAN-MERCHANT-ZIP, TRAN-CARD-NUM (PIC X(16)), TRAN-ORIG-TS, TRAN-PROC-TS (PIC X(26)), FILLER. TRAN-PROC-TS is the field whose first 10 bytes drive the date-range filter.
- `app/cpy/CVACT03Y.cpy` — `01 CARD-XREF-RECORD` 50-byte layout: XREF-CARD-NUM PIC X(16) (key), XREF-CUST-ID, XREF-ACCT-ID, FILLER.
- `app/cpy/CVTRA03Y.cpy` — `01 TRAN-TYPE-RECORD` 60-byte layout: TRAN-TYPE PIC X(02) (key), TRAN-TYPE-DESC, FILLER.
- `app/cpy/CVTRA04Y.cpy` — `01 TRAN-CAT-RECORD` 60-byte layout with composite TRAN-CAT-KEY (TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04)), TRAN-CAT-TYPE-DESC, FILLER.
- `app/cpy/CVTRA07Y.cpy` — Report header and footer structures: REPORT-NAME-HEADER (133 bytes), TRANSACTION-DETAIL-REPORT (133 bytes with `PIC -ZZZ,ZZZ,ZZZ.ZZ` edit pattern for TRAN-REPORT-AMT), TRANSACTION-HEADER-1, TRANSACTION-HEADER-2, REPORT-PAGE-TOTALS, REPORT-ACCOUNT-TOTALS, REPORT-GRAND-TOTALS with `+ZZZ,ZZZ,ZZZ.ZZ` totals format.
- `app/data/ASCII/dailytran.txt` (105,300 bytes) — Pre-transcoded ASCII transaction records: 300 records of 350 bytes each.
- `app/data/ASCII/cardxref.txt` (1,850 bytes) — Pre-transcoded ASCII card cross-reference: 37 records of 50 bytes each.
- `app/data/ASCII/trantype.txt` (427 bytes) — Pre-transcoded ASCII transaction type lookup.
- `app/data/ASCII/trancatg.txt` (1,098 bytes) — Pre-transcoded ASCII transaction category lookup.

## Authority references

- AAP §0.1.1 (refactoring objective — byte-for-byte fidelity COBOL to Java 25 LTS)
- AAP §0.2.1 (in-scope: `golden/cbtrn03c/` under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED reference implementation)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.3.2 (repository pattern with constructor-injected ports)
- AAP §0.4.1 (CBTRN03C to CbTrn03C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCE only, NEVER copied)
- AAP §0.6.1 (`Decimals` utility; `BigDecimal` scale 2 for monetary values)
- AAP §0.6.2 (sealed-type pattern for composite keys like FD-TRAN-CAT-KEY)
- AAP §0.6.4 (date semantics — `java.time` types only; legacy date/time classes prohibited)
- AAP §0.6.5 (file I/O via `java.nio.file`; EBCDIC IBM-1047 default; per-file codepage configurable)
- AAP §0.6.11 (golden-record harness; `@Disabled` scaffolding pattern)
- AAP §0.7.1 (Minimal Change Clause; preserve EOF stale-amount bug and shared-prefix paragraph names verbatim)
- AAP §0.7.2 (no 16-digit PAN in production logs; test fixture preserves COBOL behavior unmasked)
- AAP §0.7.4 (forbidden features — no preview JEP 502/505/507/512; no `default` branch in pattern-matching switches; legacy date/time classes prohibited)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline: `[<path>:Lnnn]`)
- Sibling pattern reference: `golden/corpt00c/input/README.md`, `golden/cotrn00c/input/README.md`

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep` placeholders, ASCII fixture copies, DATEPARM literal files, or any other content. The `README.md` IS the directory's marker.

All input data is sourced from `app/data/ASCII/` via classpath reference (NEVER copied per AAP §0.4.1) plus the test-synthesized DATEPARM payload constructed at runtime by `CbTrn03CGoldenTest`. All expected outputs live in the sibling `../expected/` folder under captured filenames `reptfile.txt` and `stdout.txt`. If a future test scenario requires additional inputs, add the new file to `../expected/` (test-owned scaffolding) and extend the appropriate override in `CbTrn03CGoldenTest.java` — NOT here.
