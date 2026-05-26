# Golden-Record Contract — CBTRN03C (Paginated Transaction Detail Report; Batch JCL TRANREPT)

This document is the AUTHORITATIVE byte-for-byte contract for the Java
translation of the COBOL program `app/cbl/CBTRN03C.cbl` (Paginated Transaction
Detail Report; batch program driven by JCL `app/jcl/TRANREPT.jcl`). It captures
every fact a downstream implementation of
`com.blitzy.carddemo.application.transaction.CbTrn03C` must honor to produce
identical observable outputs to the COBOL baseline when driven by the same
input fixtures. The companion test class is
`com.blitzy.carddemo.tests.golden.CbTrn03CGoldenTest`, which extends
`com.blitzy.carddemo.tests.golden.GoldenRecordTest` and is annotated
`@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")`
until the two data files (`reptfile.txt` and `stdout.txt`) below are captured
from a live COBOL execution of CBTRN03C and committed in this folder. All
citations follow the form `[<path>:Lnnn]` or `[<path>:§<section>]` per AAP
§0.8.1, and the binding cascade flows down through the 19 AAP sections
enumerated in Phase 0 below. CBTRN03C is a PURE BATCH program (PROGRAM-ID
prefix `CB`) — it is NOT a CICS online program, NOT a callable subroutine.
The golden-record harness is the NON-NEGOTIABLE PR gate per AAP §0.6.11.

## Phase 0: Authority and Source-of-Truth Cascade

The following 19 AAP sections bind every claim in this document. Any contract
change requires a corresponding AAP update and a fresh golden-record capture
per AAP §0.7.5.

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25
- AAP §0.2.1 — Golden-record fixtures explicitly in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` + `<program>/expected/` per program
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` (JEP 519) and Shenandoah generational (JEP 521)
- AAP §0.3.6 — Hexagonal architecture; no DI container, no application framework
- AAP §0.4.1 — CBTRN03C -> CbTrn03C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCE only, NEVER copied
- AAP §0.6.1 — `Decimals` utility (`MathContext.DECIMAL128`, banker's rounding)
- AAP §0.6.2 — Sealed-type pattern for composite keys and discriminated alternatives
- AAP §0.6.4 — `java.time` only; never `java.util.Date` or `java.util.Calendar`
- AAP §0.6.5 — `java.nio.file` only; EBCDIC IBM-1047 default charset with per-file override
- AAP §0.6.6 — Virtual threads / `ScopedValue` replaces `ThreadLocal` entirely in new code
- AAP §0.6.11 — Golden-record harness is the NON-NEGOTIABLE PR gate; `@Disabled` until COBOL captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve EOF stale-amount bug + shared-prefix paragraph names verbatim
- AAP §0.7.2 — No PAN logged in full in production; mask all but last 4 digits in production sinks
- AAP §0.7.4 — Forbidden features (no preview JEPs in production code); no `default` branches that hide cases
- AAP §0.7.5 — Capture procedure documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]` or `[<path>:§<section>]`

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBTRN03C` | `[app/cbl/CBTRN03C.cbl:L23]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBTRN03C.cbl:L24]` |
| Source line count | 649 | `app/cbl/CBTRN03C.cbl` |
| JCL driver | `STEP10R EXEC PGM=CBTRN03C` | `[app/jcl/TRANREPT.jcl:L59]` |
| JCL job name | `TRANREPT` | `[app/jcl/TRANREPT.jcl:L1]` |
| JCL DDs (input) | TRANFILE, CARDXREF, TRANTYPE, TRANCATG, DATEPARM | `[app/jcl/TRANREPT.jcl:L65-L74]` |
| JCL DD (output) | TRANREPT (LRECL=133, RECFM=FB) | `[app/jcl/TRANREPT.jcl:L76-L80]` |
| Java FQCN under test | `com.blitzy.carddemo.application.transaction.CbTrn03C` | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbTrn03CGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |

The `@CobolProgram("CBTRN03C")` Javadoc-style annotation MUST cite the original
PROGRAM-ID, the source path `app/cbl/CBTRN03C.cbl`, and the translation date
per AAP §0.7.1. The `@Disabled` mandate is strict: the test class remains
`@Disabled` until BOTH `reptfile.txt` and `stdout.txt` are present in this
folder with CAPTURED (not placeholder) content per AAP §0.6.11. CBTRN03C runs
LINEARLY through transactions; there is NO BMS map, NO CICS commarea, NO
EIBAID dispatch, NO XCTL/LINK, NO pseudo-conversational flow — execution is a
straight-line z/OS batch invocation from the JCL driver to the GOBACK
statement.

## Phase 2: Files in This Folder

This is the ONLY phase containing H3 sub-headings (exactly 3 below).

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBTRN03C
golden-record fixture. Created as part of the initial Java module scaffolding
per AAP §0.2.1; consumed by `CbTrn03CGoldenTest` once the two data files below
are captured per AAP §0.7.5.

### `reptfile.txt` — Captured COBOL Report File (CAPTURE PLACEHOLDER)

- **Format**: 133-byte fixed-width records per `[app/cbl/CBTRN03C.cbl:L85]`
  `FD-REPTFILE-REC PIC X(133)` and `[app/jcl/TRANREPT.jcl:L78]`
  `DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)`.
- **Content** (per `app/cpy/CVTRA07Y.cpy` and `app/cbl/CBTRN03C.cbl` write
  paragraphs): REPORT-NAME-HEADER (Daily Transaction Report banner with date
  range), blank line (WS-BLANK-LINE), TRANSACTION-HEADER-1 (column titles:
  Transaction ID, Account ID, Transaction Type, Tran Category, Tran Source,
  Amount), TRANSACTION-HEADER-2 (all `-` divider, 133 chars), N detail rows
  per page (up to PAGE_SIZE=20), REPORT-ACCOUNT-TOTALS on card-number break
  (`'Account Total'` + ALL `'.'` + REPT-ACCOUNT-TOTAL), REPORT-PAGE-TOTALS on
  page break (`'Page Total'` + ALL `'.'` + REPT-PAGE-TOTAL),
  REPORT-GRAND-TOTALS at EOF (`'Grand Total'` + ALL `'.'` + REPT-GRAND-TOTAL).
- **Byte-for-byte parity rule**: `CbTrn03C.run(...)` MUST produce, for the
  deterministic input set under the fixed DATEPARM, a `reptfile.txt` byte
  sequence equal to this captured baseline BYTE-for-BYTE. Trailing spaces,
  field padding, edit-pattern formatting, and the `-` divider line all match
  exactly.
- **Capture procedure**: A COBOL reference run executes CBTRN03C with the
  agreed DATEPARM and writes the TRANREPT DD to a file that becomes this
  fixture. Documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT
  exist yet; `CbTrn03CGoldenTest` is `@Disabled` until captured content is
  committed.

### `stdout.txt` — Captured COBOL DISPLAY Output (CAPTURE PLACEHOLDER)

- **Format**: line-based ASCII text. Each DISPLAY statement produces ONE line
  of output ending in the platform's newline (LF on the captured run).
- **Content**: 26 distinct DISPLAY statements catalogued in Phase 5. The
  TRAN-RECORD DISPLAY at `[app/cbl/CBTRN03C.cbl:L180]` emits one 350-byte line
  per in-range transaction record (the entire record content as concatenated
  bytes, including UNMASKED 16-byte TRAN-CARD-NUM).
- **Byte-for-byte parity rule**: The Java test driver's captured stdout MUST
  equal this baseline BYTE-for-BYTE.
- **PAN-masking dichotomy**: COBOL DISPLAY at L180 emits the entire 350-byte
  TRAN-RECORD INCLUDING the unmasked 16-byte TRAN-CARD-NUM. Per AAP §0.7.2,
  production Java logging masks all but the last 4 digits. The test driver
  bypasses the masking sink (using an unmasked test-only sink) to capture
  parity. Documented explicitly in Phase 12.
- **Status when initially committed**: CAPTURE PLACEHOLDER.

## Phase 3: Conceptual Input Universe

The inputs that produce the captured baseline are:

- **Primary input file**: `app/data/ASCII/dailytran.txt` (105,300 bytes; 300
  records of 350 bytes each per CVTRA05Y). Read via TRANFILE DD per
  `[app/jcl/TRANREPT.jcl:L65-L66]`.
- **Auxiliary input files** (read via INDEXED RANDOM per
  `[app/cbl/CBTRN03C.cbl:L33-L49]`):
  - CARDXREF: `app/data/ASCII/cardxref.txt` (1,850 bytes; 37 records of 50
    bytes each per CVACT03Y); key = 16-byte FD-XREF-CARD-NUM per
    `[app/cbl/CBTRN03C.cbl:L36]`.
  - TRANTYPE: `app/data/ASCII/trantype.txt` (427 bytes; ~7 records of 60 bytes
    each per CVTRA03Y); key = 2-byte FD-TRAN-TYPE per
    `[app/cbl/CBTRN03C.cbl:L42]`.
  - TRANCATG: `app/data/ASCII/trancatg.txt` (1,098 bytes; ~18 records of 60
    bytes each per CVTRA04Y); key = 6-byte composite FD-TRAN-CAT-KEY (2-byte
    FD-TRAN-TYPE-CD + 4-byte FD-TRAN-CAT-CD) per
    `[app/cbl/CBTRN03C.cbl:L48,L79-L81]`.
- **DATEPARM block** (TEST-OWNED scaffolding): a 21-byte payload synthesized
  programmatically by `CbTrn03CGoldenTest`. Layout per WS-DATEPARM-RECORD at
  `[app/cbl/CBTRN03C.cbl:L122-L125]`: WS-START-DATE PIC X(10) + FILLER PIC
  X(01) + WS-END-DATE PIC X(10). Read via DATEPARM DD per
  `[app/jcl/TRANREPT.jcl:L73-L74]`. The default fixture range MUST cover a
  subset of `dailytran.txt`'s TRAN-PROC-TS dates so as to exercise both
  in-range (records pass the L173-L174 filter) and out-of-range (filtered)
  paths.

ASCII fixtures are NEVER copied to this folder per AAP §0.4.1 — they are
referenced via classpath relative path from `app/data/ASCII/`. The Java test
class resolves the classpath via `this.getClass().getResource("/app/data/ASCII/dailytran.txt")`
or an equivalent helper provided by the base `GoldenRecordTest` class.

## Phase 4: Deterministic Date-Range Selection

The WS-DATEPARM-RECORD usage governs which transactions enter the report:

- **Date format**: `YYYY-MM-DD` ASCII string, 10 bytes each (consistent with
  the JCL SORT range at `[app/jcl/TRANREPT.jcl:L43-L44]` PARM-START-DATE and
  PARM-END-DATE SYMNAMES).
- **TRAN-PROC-TS comparison** at `[app/cbl/CBTRN03C.cbl:L173-L174]`:

```cobol
IF TRAN-PROC-TS (1:10) >= WS-START-DATE
   AND TRAN-PROC-TS (1:10) <= WS-END-DATE
   CONTINUE
ELSE
   NEXT SENTENCE
```

COBOL byte-substring on positions 1-10 of the 26-byte TRAN-PROC-TS field
(CVTRA05Y) is compared against WS-START-DATE / WS-END-DATE inclusive on both
endpoints.

- **Java implementation**:
  `tranRecord.tranProcTs().substring(0, 10).compareTo(startDate) >= 0 && tranRecord.tranProcTs().substring(0, 10).compareTo(endDate) <= 0`
  — lexicographic String comparison produces chronologically correct ordering
  for the `YYYY-MM-DD` format because the format is zero-padded and ordered
  left-to-right by significance.
- **No FUNCTION CURRENT-DATE in CBTRN03C** — verified by direct inspection of
  the full 649-line source. Therefore NO `ScopedValue<Clock>` injection is
  required; the fixture's determinism flows entirely from the synthesized
  DATEPARM. (Contrast with sibling CORPT00C, which DOES use CURRENT-DATE and
  requires Clock injection — see the Contrast Matrix below.)

## Phase 5: Verbatim COBOL DISPLAY Message Catalog

The following table catalogues EVERY DISPLAY statement in CBTRN03C. All bytes
are preserved EXACTLY, including embedded spaces, colons, and trailing literal
content. NO Unicode characters appear in any message. The Java translation
MUST produce byte-identical strings.

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBTRN03C'` `` | L160 | Unconditional start banner |
| 2 | `` `'Reporting from '` `` WS-START-DATE `` `' to '` `` WS-END-DATE | L232-L233 | After DATEPARM read; preserve SPACE before/after `' to '` |
| 3 | TRAN-RECORD (entire 350-byte record) | L180 | Each in-range transaction; includes UNMASKED 16-byte TRAN-CARD-NUM |
| 4 | `` `'TRAN-AMT '` `` TRAN-AMT | L198 | At EOF after loop; preserve 1 trailing SPACE after `TRAN-AMT` |
| 5 | `` `'WS-PAGE-TOTAL'` `` WS-PAGE-TOTAL | L199 | At EOF; NO trailing space after `WS-PAGE-TOTAL` (tight concatenation) |
| 6 | `` `'END OF EXECUTION OF PROGRAM CBTRN03C'` `` | L215 | Unconditional end banner |
| 7 | `` `'ERROR READING DATEPARM FILE'` `` | L238 | DATEPARM read failure |
| 8 | `` `'ERROR READING TRANSACTION FILE'` `` | L266 | TRANFILE read failure |
| 9 | `` `'ERROR WRITING REPTFILE'` `` | L354 | REPORT-FILE write failure |
| 10 | `` `'ERROR OPENING TRANFILE'` `` | L387 | TRANFILE open failure |
| 11 | `` `'ERROR OPENING REPTFILE'` `` | L405 | REPORT-FILE open failure |
| 12 | `` `'ERROR OPENING CROSS REF FILE'` `` | L423 | XREF-FILE open failure |
| 13 | `` `'ERROR OPENING TRANSACTION TYPE FILE'` `` | L441 | TRANTYPE-FILE open failure |
| 14 | `` `'ERROR OPENING TRANSACTION CATG FILE'` `` | L459 | TRANCATG-FILE open failure |
| 15 | `` `'ERROR OPENING DATE PARM FILE'` `` | L477 | DATEPARM-FILE open failure |
| 16 | `` `'INVALID CARD NUMBER : '` `` FD-XREF-CARD-NUM | L487 | XREF lookup INVALID KEY; preserve SPACE-COLON-SPACE |
| 17 | `` `'INVALID TRANSACTION TYPE : '` `` FD-TRAN-TYPE | L497 | TRANTYPE lookup INVALID KEY |
| 18 | `` `'INVALID TRAN CATG KEY : '` `` FD-TRAN-CAT-KEY | L507 | TRANCATG lookup INVALID KEY |
| 19 | `` `'ERROR CLOSING POSTED TRANSACTION FILE'` `` | L525 | TRANFILE close failure |
| 20 | `` `'ERROR CLOSING REPORT FILE'` `` | L543 | REPORT-FILE close failure |
| 21 | `` `'ERROR CLOSING CROSS REF FILE'` `` | L562 | XREF-FILE close failure |
| 22 | `` `'ERROR CLOSING TRANSACTION TYPE FILE'` `` | L580 | TRANTYPE-FILE close failure |
| 23 | `` `'ERROR CLOSING TRANSACTION CATG FILE'` `` | L598 | TRANCATG-FILE close failure |
| 24 | `` `'ERROR CLOSING DATE PARM FILE'` `` | L616 | DATEPARM-FILE close failure |
| 25 | `` `'ABENDING PROGRAM'` `` | L627 | Before CALL `'CEE3ABD'` |
| 26 | `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 | L640, L644 | After file errors; NNNN = 4-char numeric status; emitted from both branches of 9910-DISPLAY-IO-STATUS |

ALL messages preserve EXACT bytes including embedded SPACES, COLONS, and
trailing literal text. NO Unicode characters appear in any message. The Java
translation MUST produce byte-identical strings for each of the 26 catalogued
messages.

## Phase 6: 133-Byte Report-Line Edit Pattern Inventory

Each report-line type from `app/cpy/CVTRA07Y.cpy` is documented below. All 8
line types are presented with their verbatim copybook layouts.

**REPORT-NAME-HEADER** at `[app/cpy/CVTRA07Y.cpy:L4-L13]` (sum 115 bytes; padded to 133 by group MOVE to FD-REPTFILE-REC PIC X(133)):

```cobol
01  REPORT-NAME-HEADER.
    05  REPT-SHORT-NAME       PIC X(38) VALUE 'DALYREPT'.
    05  REPT-LONG-NAME        PIC X(41) VALUE 'Daily Transaction Report'.
    05  REPT-DATE-HEADER      PIC X(12) VALUE 'Date Range: '.
    05  REPT-START-DATE       PIC X(10) VALUE SPACES.
    05  FILLER                PIC X(04) VALUE ' to '.
    05  REPT-END-DATE         PIC X(10) VALUE SPACES.
```

**TRANSACTION-DETAIL-REPORT** at `[app/cpy/CVTRA07Y.cpy:L15-L31]` (sum 113 bytes; padded to 133 by group MOVE):

```cobol
01  TRANSACTION-DETAIL-REPORT.
    05  TRAN-REPORT-TRANS-ID  PIC X(16).
    05  FILLER                PIC X(01) VALUE SPACES.
    05  TRAN-REPORT-ACCOUNT-ID PIC X(11).
    05  FILLER                PIC X(01) VALUE SPACES.
    05  TRAN-REPORT-TYPE-CD   PIC X(02).
    05  FILLER                PIC X(01) VALUE '-'.
    05  TRAN-REPORT-TYPE-DESC PIC X(15).
    05  FILLER                PIC X(01) VALUE SPACES.
    05  TRAN-REPORT-CAT-CD    PIC 9(04).
    05  FILLER                PIC X(01) VALUE '-'.
    05  TRAN-REPORT-CAT-DESC  PIC X(29).
    05  FILLER                PIC X(01) VALUE SPACES.
    05  TRAN-REPORT-SOURCE    PIC X(10).
    05  FILLER                PIC X(04) VALUE SPACES.
    05  TRAN-REPORT-AMT       PIC -ZZZ,ZZZ,ZZZ.ZZ.
    05  FILLER                PIC X(02) VALUE SPACES.
```

**TRANSACTION-HEADER-1** at `[app/cpy/CVTRA07Y.cpy:L33-L46]` (sum 114 bytes; padded to 133):

```cobol
01  TRANSACTION-HEADER-1.
    05  FILLER  PIC X(17) VALUE 'Transaction ID'.
    05  FILLER  PIC X(12) VALUE 'Account ID'.
    05  FILLER  PIC X(19) VALUE 'Transaction Type'.
    05  FILLER  PIC X(35) VALUE 'Tran Category'.
    05  FILLER  PIC X(14) VALUE 'Tran Source'.
    05  FILLER  PIC X    VALUE SPACES.
    05  FILLER  PIC X(16) VALUE '        Amount'.
```

**TRANSACTION-HEADER-2** at `[app/cpy/CVTRA07Y.cpy:L48]` (exactly 133 bytes; all 133 are ASCII `-` hyphen):

```cobol
01  TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.
```

**REPORT-PAGE-TOTALS** at `[app/cpy/CVTRA07Y.cpy:L50-L54]` (sum 11 + 86 + 14 = 111 bytes; padded to 133):

```cobol
01  REPORT-PAGE-TOTALS.
    05  FILLER             PIC X(11) VALUE 'Page Total'.
    05  FILLER             PIC X(86) VALUE ALL '.'.
    05  REPT-PAGE-TOTAL    PIC +ZZZ,ZZZ,ZZZ.ZZ.
```

**REPORT-ACCOUNT-TOTALS** at `[app/cpy/CVTRA07Y.cpy:L56-L60]` (sum 13 + 84 + 14 = 111 bytes; padded to 133):

```cobol
01  REPORT-ACCOUNT-TOTALS.
    05  FILLER             PIC X(13) VALUE 'Account Total'.
    05  FILLER             PIC X(84) VALUE ALL '.'.
    05  REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ.
```

**REPORT-GRAND-TOTALS** at `[app/cpy/CVTRA07Y.cpy:L62-L66]` (sum 11 + 86 + 14 = 111 bytes; padded to 133):

```cobol
01  REPORT-GRAND-TOTALS.
    05  FILLER             PIC X(11) VALUE 'Grand Total'.
    05  FILLER             PIC X(86) VALUE ALL '.'.
    05  REPT-GRAND-TOTAL   PIC +ZZZ,ZZZ,ZZZ.ZZ.
```

**WS-BLANK-LINE** at `[app/cbl/CBTRN03C.cbl:L133]` (exactly 133 ASCII spaces):

```cobol
05 WS-BLANK-LINE  PIC X(133) VALUE SPACES.
```

PIC edit semantics:

- **PIC -ZZZ,ZZZ,ZZZ.ZZ** (14 bytes total): zero-suppression with leading
  space (for non-negative) or minus sign (for negative); 3-comma group
  separators; 2-decimal precision.
- **PIC +ZZZ,ZZZ,ZZZ.ZZ** (14 bytes total): always-show-sign with leading `+`
  (for non-negative) or `-` (for negative); zero-suppressed; 3-comma group
  separators.

The Java translation MUST use a custom formatter (or a `DecimalFormat`
configured precisely) to reproduce these patterns. Centralize the formatter in
a utility class to ensure consistency across all 4 amount-bearing line types
(TRAN-REPORT-AMT, REPT-PAGE-TOTAL, REPT-ACCOUNT-TOTAL, REPT-GRAND-TOTAL). Java
MUST NOT use Java's default `%+,.2f` formatting (which would produce wrong
byte positions); the formatter must precisely match COBOL's zero-suppression
and sign-positioning.

## Phase 7: Pagination, Account Totals, and Grand Totals

The pagination and totaling logic is documented below with explicit line
citations:

- **PAGE_SIZE = 20 lines per page** at `[app/cbl/CBTRN03C.cbl:L131-L132]`:

```cobol
05 WS-PAGE-SIZE       PIC 9(03) COMP-3
                                VALUE 20.
```

- **Line counter** at `[app/cbl/CBTRN03C.cbl:L129-L130]`: `WS-LINE-COUNTER PIC
  9(09) COMP-3 VALUE 0` — incremented after each detail line write (paragraphs
  1110/1120/1111 add 1 to WS-LINE-COUNTER on each WRITE).
- **Page break trigger** at `[app/cbl/CBTRN03C.cbl:L274-L290]` (paragraph
  1100-WRITE-TRANSACTION-REPORT): on first-time write (`WS-FIRST-TIME = 'Y'`)
  emit REPORT-NAME-HEADER + WS-BLANK-LINE + TRANSACTION-HEADER-1 +
  TRANSACTION-HEADER-2 via 1120-WRITE-HEADERS; on
  `FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` emit page totals via
  1110-WRITE-PAGE-TOTALS + headers via 1120-WRITE-HEADERS.
- **Account total trigger** at `[app/cbl/CBTRN03C.cbl:L181-L188]` (inside the
  main loop): on card-number change (WS-CURR-CARD-NUM at
  `[app/cbl/CBTRN03C.cbl:L137]` differs from the current TRAN-CARD-NUM),
  perform 1120-WRITE-ACCOUNT-TOTALS (paragraph at
  `[app/cbl/CBTRN03C.cbl:L306-L316]`) and reset WS-ACCOUNT-TOTAL.
- **Grand total emission** at `[app/cbl/CBTRN03C.cbl:L203]` (EOF): PERFORM
  1110-WRITE-GRAND-TOTALS (paragraph at `[app/cbl/CBTRN03C.cbl:L318-L322]`).
- **Total accumulators** at `[app/cbl/CBTRN03C.cbl:L134-L136]`:
  - `WS-PAGE-TOTAL    PIC S9(09)V99 VALUE 0`
  - `WS-ACCOUNT-TOTAL PIC S9(09)V99 VALUE 0`
  - `WS-GRAND-TOTAL   PIC S9(09)V99 VALUE 0`

  All three are `BigDecimal` scale 2 in Java per AAP §0.6.1.
- **Total propagation**: the page-totals write ADDs `WS-PAGE-TOTAL` to
  `WS-GRAND-TOTAL` at `[app/cbl/CBTRN03C.cbl:L297]`; the normal detail-write
  ADDs `TRAN-AMT` to both `WS-PAGE-TOTAL` and `WS-ACCOUNT-TOTAL` at
  `[app/cbl/CBTRN03C.cbl:L287-L288]`.
- **First-time flag**: WS-FIRST-TIME PIC X VALUE 'Y' at
  `[app/cbl/CBTRN03C.cbl:L128]` — toggles to 'N' after the first detail write
  (in 1100-WRITE-TRANSACTION-REPORT at `[app/cbl/CBTRN03C.cbl:L275-L276]`) to
  prevent re-emitting REPORT-NAME-HEADER.

## Phase 8: 3 Indexed Lookups and Abend Semantics

Each of the 3 lookup paragraphs and the abend paragraph is documented below.

- **1500-A-LOOKUP-XREF** at `[app/cbl/CBTRN03C.cbl:L484-L492]`:

```cobol
1500-A-LOOKUP-XREF.
    READ XREF-FILE INTO CARD-XREF-RECORD
       INVALID KEY
          DISPLAY 'INVALID CARD NUMBER : '  FD-XREF-CARD-NUM
          MOVE 23 TO IO-STATUS
          PERFORM 9910-DISPLAY-IO-STATUS
          PERFORM 9999-ABEND-PROGRAM
    END-READ
    EXIT.
```

- **1500-B-LOOKUP-TRANTYPE** at `[app/cbl/CBTRN03C.cbl:L494-L502]`: same
  pattern, message `` `'INVALID TRANSACTION TYPE : '` ``, key `FD-TRAN-TYPE`.
- **1500-C-LOOKUP-TRANCATG** at `[app/cbl/CBTRN03C.cbl:L504-L512]`: same
  pattern, message `` `'INVALID TRAN CATG KEY : '` ``, key
  `FD-TRAN-CAT-KEY`.
- **9999-ABEND-PROGRAM** at `[app/cbl/CBTRN03C.cbl:L626-L630]`:

```cobol
9999-ABEND-PROGRAM.
    DISPLAY 'ABENDING PROGRAM'
    MOVE 0 TO TIMING
    MOVE 999 TO ABCODE
    CALL 'CEE3ABD'.
```

- **9910-DISPLAY-IO-STATUS** at `[app/cbl/CBTRN03C.cbl:L633-L646]`: emits
  `` `'FILE STATUS IS: NNNN'` `` from BOTH the non-numeric / 9-prefix branch
  (L640) and the numeric branch (L644).

The Java translation:

- Each lookup invokes the constructor-injected port (`CardXrefRepository`,
  `TransactionTypeRepository`, `TransactionCategoryRepository`) per AAP §0.4.1
  and AAP §0.3.2.
- A missing key throws a typed exception (e.g., `XrefNotFoundException`,
  `TranTypeNotFoundException`, `TranCatgNotFoundException`) with the
  `abend_code = 999` field preserved.
- The application catches at the top level and propagates as a JVM exit code
  mirroring CEE3ABD semantics; the message and status emission remain
  byte-identical to the COBOL DISPLAY output.
- Sealed-type pattern (per AAP §0.6.2) groups these into a
  `CbTrn03CAbendCause` hierarchy with one permit per cause type
  (CardXrefNotFound, TranTypeNotFound, TranCatgNotFound, FileOpenError,
  FileReadError, FileWriteError, FileCloseError).
- NO `default` branch in any pattern-matching switch (AAP §0.7.4);
  exhaustiveness is enforced by the Java 25 compiler.

## Phase 9: Preserved Bugs and Idiosyncrasies

Each preserved-as-is COBOL quirk is documented below per AAP §0.7.1.

- **EOF stale-amount bug**: at `[app/cbl/CBTRN03C.cbl:L197-L203]`, after the
  main loop's GET-NEXT returns EOF (END-OF-FILE = 'Y'), the ELSE branch
  executes:

```cobol
ELSE
   DISPLAY 'TRAN-AMT ' TRAN-AMT
   DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL
   ADD TRAN-AMT TO WS-PAGE-TOTAL
                   WS-ACCOUNT-TOTAL
   PERFORM 1110-WRITE-PAGE-TOTALS
   PERFORM 1110-WRITE-GRAND-TOTALS
END-IF
```

The `ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL` at
`[app/cbl/CBTRN03C.cbl:L200-L201]` uses the STALE TRAN-AMT value from the LAST
successful read (whose amount was already added during the normal write path
at L287-L288). This causes the grand total to double-count the last in-range
transaction's amount when EOF arrives. **PRESERVED EXACTLY per AAP §0.7.1**.
The Java translation reproduces this bug. Documented in
`java/MIGRATION_NOTES.md` as "double-count of last record at EOF — preserved
for parity".

- **Shared-prefix paragraph names**: COBOL CBTRN03C uses a numeric-prefix
  paragraph-naming convention where multiple distinct paragraphs share the
  SAME numeric prefix. Specifically:
  - **TWO paragraphs with prefix `1110-`**:
    - `1110-WRITE-PAGE-TOTALS` at `[app/cbl/CBTRN03C.cbl:L293]`
    - `1110-WRITE-GRAND-TOTALS` at `[app/cbl/CBTRN03C.cbl:L318]`
  - **THREE paragraphs with prefix `1120-`**:
    - `1120-WRITE-ACCOUNT-TOTALS` at `[app/cbl/CBTRN03C.cbl:L306]`
    - `1120-WRITE-HEADERS` at `[app/cbl/CBTRN03C.cbl:L324]`
    - `1120-WRITE-DETAIL` at `[app/cbl/CBTRN03C.cbl:L361]`

  Each is referenced unambiguously via direct `PERFORM <full-name>`. The Java
  translation uses uniquely-named private methods but cites each paragraph's
  original full COBOL name via a Javadoc `@CobolParagraph("1110-WRITE-PAGE-TOTALS")`
  (or equivalent traceability annotation). **Shared prefix structure
  PRESERVED per AAP §0.7.1** with traceability annotations.

- **350-byte TRAN-RECORD DISPLAY at L180**: emits the entire fixed-width
  record including the 16-byte UNMASKED TRAN-CARD-NUM. Per AAP §0.7.2,
  production logs mask all but the last 4 digits; the test fixture's
  `stdout.txt` captures the COBOL behavior unmasked for parity. The Java
  production logger uses a masking sink; the test driver uses an unmasked
  sink (test-only). Documented as the PAN-masking dichotomy in Phase 12.

- **`-ZZZ,ZZZ,ZZZ.ZZ` numeric edit pattern**: PRESERVED EXACTLY. Negative
  amounts render with a leading `-` after zero-suppression; positive amounts
  render with a leading SPACE in the sign position. Java MUST NOT use Java's
  default `%+,.2f` (which would emit `+` for positive); it must use a custom
  formatter matching COBOL exactly. This formatter is centralized in a
  utility class per Phase 10.

- **Contrast with CORPT00C**: the `WIRTE-JOBSUB-TDQ` paragraph-name typo is
  found in CORPT00C, NOT in CBTRN03C. CBTRN03C has no TDQ writes; it writes
  directly to the TRANREPT DD.

## Phase 10: Java Mapping Invariants

The following Java translation invariants apply to the entire CBTRN03C translation:

- **One class per COBOL PROGRAM-ID**: `CBTRN03C` -> `CbTrn03C` in `com.blitzy.carddemo.application.transaction` per AAP §0.4.1.
- **`@CobolProgram("CBTRN03C")` annotation** citing the original PROGRAM-ID, source path `app/cbl/CBTRN03C.cbl`, and translation date per AAP §0.7.1.
- **`@CobolParagraph` annotations** on each private method translated from a COBOL paragraph, preserving original names INCLUDING shared-prefix family members (the two `1110-*` and three `1120-*` paragraphs documented in Phase 9).
- **Constructor injection** of repository ports (NO DI container, NO application framework); ports are defined in `com.blitzy.carddemo.domain.port` per AAP §0.3.2 and AAP §0.3.6.
- **`BigDecimal` for all monetary values**: TRAN-AMT, WS-PAGE-TOTAL, WS-ACCOUNT-TOTAL, WS-GRAND-TOTAL — all `BigDecimal` scale 2 with `MathContext.DECIMAL128` per AAP §0.6.1. Arithmetic is centralized in `com.blitzy.carddemo.domain.util.Decimals`.
- **`String` for all 26-byte timestamps**: TRAN-PROC-TS, TRAN-ORIG-TS — stored as String to preserve substring(1:10) semantics. If date arithmetic is needed elsewhere, convert to `java.time.LocalDateTime` via `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")` per AAP §0.6.4.
- **NO `java.util.Date` / `java.util.Calendar` / `java.text.SimpleDateFormat`** per AAP §0.6.4.
- **NO `java.io.File`** — use `java.nio.file.Path`, `Files.newByteChannel`, `SeekableByteChannel` per AAP §0.6.5.
- **NO `ThreadLocal`** — use `ScopedValue` if any cross-method context propagation is needed. CBTRN03C is sequential single-threaded and is not expected to use either per AAP §0.6.6.
- **EBCDIC IBM-1047 default codepage** per AAP §0.6.5; per-file override via `application.properties` keys (e.g., `carddemo.file.dailytran.charset`). ASCII fixtures use `Charset.forName("US-ASCII")` for test runs.
- **Sealed-type pattern** (per AAP §0.6.2) for: composite TRANCATG key (record with compact constructor validation), FILE STATUS code hierarchy, `CbTrn03CAbendCause` hierarchy.
- **Pattern-matching `switch`** for any case-discrimination (e.g., FILE STATUS code -> exception type) — NO `default` branch per AAP §0.7.4; exhaustiveness is enforced by the compiler.
- **Records, not POJOs**: `AccountRecord`, `CardRecord`, `CardXrefRecord`, `TranTypeRecord`, `TranCatRecord`, `TranRecord`, `DalyTranRecord` ALL declared as Java `record` per AAP §0.3.2.
- **`parse(byte[])` and `encode()`** static factory + instance method pair on each record per AAP §0.3.2 byte-level contract.
- **NO third-party ORM, NO third-party batch framework** — plain Java with constructor injection per AAP §0.3.6.
- **JVM flags**: `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4.
- **NO `--enable-preview`** per AAP §0.7.4.

## Phase 11: Test Class Override Map

`CbTrn03CGoldenTest` extends `GoldenRecordTest` and provides the canonical
override methods below. The base class is documented in
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
and defines the byte-for-byte parity assertion that this test inherits.

```java
// com.blitzy.carddemo.tests.golden.CbTrn03CGoldenTest
@Override
protected Class<?> programClass() {
    return com.blitzy.carddemo.application.transaction.CbTrn03C.class;
}

@Override
protected Path inputFile() {
    // Classpath-resolved path to app/data/ASCII/dailytran.txt (primary TRANFILE DD)
    return resolveAsciiFixture("dailytran.txt");
}

@Override
protected Map<String, Path> auxiliaryInputs() {
    return Map.of(
        "CARDXREF", resolveAsciiFixture("cardxref.txt"),
        "TRANTYPE", resolveAsciiFixture("trantype.txt"),
        "TRANCATG", resolveAsciiFixture("trancatg.txt"),
        "DATEPARM", synthesizeDateparm("2022-01-01", "2022-07-06")
    );
}

@Override
protected Path expectedOutputFile() {
    return resolveExpectedOutputPath("cbtrn03c", "stdout.txt");
}

@Override
protected List<ExpectedOutput> expectedOutputs() {
    return List.of(
        new ExpectedOutput("stdout.txt", resolveExpectedOutputPath("cbtrn03c", "stdout.txt")),
        new ExpectedOutput("reptfile.txt", resolveExpectedOutputPath("cbtrn03c", "reptfile.txt"))
    );
}
```

The `@Disabled` verification checklist (12 points; all MUST be satisfied before the `@Disabled` annotation is removed):

1. Classpath fixture resolver returns non-null for each of the 4 ASCII inputs.
2. Synthesized DATEPARM is exactly 21 bytes.
3. Expected `reptfile.txt` exists and is non-empty (captured, not placeholder).
4. Expected `stdout.txt` exists and is non-empty (captured, not placeholder).
5. Expected outputs are committed via the capture procedure in `java/MIGRATION_NOTES.md` §1.6 — NOT hand-edited.
6. The DATEPARM range matches the COBOL capture's DATEPARM range exactly.
7. No production code references `java.lang.System.out` directly (DISPLAY translations use SLF4J in production; the test driver uses an unmasked test-only sink).
8. The `Decimals` utility is fully tested at 100% line coverage per AAP §0.6.1.
9. `@CobolProgram("CBTRN03C")` annotation is present on the `CbTrn03C` class.
10. `@CobolParagraph` annotations are present on each translated paragraph method, citing the full original COBOL paragraph name.
11. No `--enable-preview` in the test runner's JVM args.
12. The `-XX:+UseCompactObjectHeaders` flag is documented in the test runner's JVM args.

## Phase 12: Capture Procedure Cross-Reference

The cross-references below document how the captured fixture files are produced and how the PAN-masking dichotomy is reconciled with parity.

- See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build/run path used to capture this fixture's expected files. The capture procedure exists because the user prompt left this as a `[`TODO`]` marker per AAP §0.7.5.
- **Determinism**: the fixture's DATEPARM input is fixed (e.g., `2022-01-01` through `2022-07-06`, matching the JCL SORT range at `[app/jcl/TRANREPT.jcl:L43-L44]` PARM-START-DATE/PARM-END-DATE SYMNAMES); the fixture's TRANFILE/CARDXREF/TRANTYPE/TRANCATG are the read-only `app/data/ASCII/*.txt` snapshots committed to the repository.
- **Capture command shape** (illustrative; canonical path documented in `java/MIGRATION_NOTES.md` §1.6): compile CBTRN03C with GnuCOBOL or z/OS Enterprise COBOL -> execute with DD assignments pointing to the ASCII fixtures plus a synthesized DATEPARM file -> capture stdout AND the TRANREPT output file -> commit as `stdout.txt` and `reptfile.txt`.
- **PAN-masking dichotomy**: the COBOL DISPLAY at `[app/cbl/CBTRN03C.cbl:L180]` emits the unmasked PAN. The captured `stdout.txt` thus contains unmasked PANs. This is INTENTIONAL for parity assertion — the production Java code masks all but the last 4 digits per AAP §0.7.2, but the test driver bypasses masking via an unmasked test-only sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- **Until capture is performed**: `CbTrn03CGoldenTest` is `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")`; the parity assertion is unreachable.
- **Re-capture trigger**: if the ASCII fixtures change OR the date range changes OR the DATEPARM payload changes OR the COBOL source changes, this fixture MUST be re-captured. Do NOT ad-hoc edit `reptfile.txt` or `stdout.txt`; always re-run the capture procedure.

## Phase 13: Scenario Inventory

The fixture MUST exercise at least the following 14 scenarios. They MAY be
implemented as a single end-to-end run against the full
`app/data/ASCII/dailytran.txt` (most natural for parity assertion), OR as
multiple JUnit `@ParameterizedTest` runs each with a constructed input set —
implementer's choice per `CbTrn03CGoldenTest.java`.

1. **Happy path — multiple in-range transactions across multiple cards** ->
   multi-page report with N detail lines, multiple account-total breaks,
   page-total breaks every 20 lines, grand-total at EOF.
2. **Empty in-range** — all transactions filtered out by date range ->
   REPORT-NAME-HEADER + blank line + TRANSACTION-HEADER-1 +
   TRANSACTION-HEADER-2 may or may not be emitted depending on WS-FIRST-TIME
   state; grand total = 0.00 still emitted at EOF.
3. **Single in-range transaction** -> 1 detail line, 1 account total, 1 page
   total = 1 grand total (but observe the EOF stale-amount bug: grand total
   = 2 x amount due to the L200-L201 stale ADD).
4. **Exact PAGE_SIZE=20 in-range transactions, all for 1 card** -> exactly 1
   page with 1 account total = page total; page break trigger at
   WS-LINE-COUNTER mod 20 = 0.
5. **PAGE_SIZE+1 = 21 in-range transactions, all for 1 card** -> 2 pages;
   page totals emitted at the boundary.
6. **Card change at exactly the page boundary** -> account total + page
   total emitted at the same line position.
7. **CARDXREF lookup failure** -> ABEND with
   `` `'INVALID CARD NUMBER : '` `` and CEE3ABD; verify Java throws the
   typed exception.
8. **TRANTYPE lookup failure** -> ABEND with
   `` `'INVALID TRANSACTION TYPE : '` ``.
9. **TRANCATG lookup failure** -> ABEND with
   `` `'INVALID TRAN CATG KEY : '` ``.
10. **EOF stale-amount bug visible** — the grand total includes the last
    record's TRAN-AMT TWICE per the L197-L203 bug; the fixture's
    `reptfile.txt` documents this exact (incorrect-but-preserved) total.
11. **Negative TRAN-AMT** -> `-ZZZ,ZZZ,ZZZ.ZZ` edit pattern renders with a
    leading `-`.
12. **Boundary date — first second of WS-START-DATE** -> record is in-range.
13. **Boundary date — last second of WS-END-DATE** -> record is in-range.
14. **Just-out-of-range date** -> record filtered out, NOT displayed, NOT
    added to totals.

## Contrast Matrix — CBTRN03C vs. sibling programs

The following matrix contrasts CBTRN03C with 3 key siblings to highlight the
batch-only, no-Clock-injection nature of CBTRN03C.

| Aspect | CBTRN03C (this) | CORPT00C (sibling) | CSUTLDTC (sibling) | COSGN00C (sibling) |
|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CO (online) | CS (callable subroutine) | CO (online) |
| JCL driver | `[app/jcl/TRANREPT.jcl:L59]` STEP10R | None (CICS transaction CR00) | None (called by other progs) | None (CICS transaction CC00) |
| Input source | 5 JCL DD files (TRANFILE, CARDXREF, TRANTYPE, TRANCATG, DATEPARM) | BMS map + DFHCOMMAREA | LINKAGE SECTION | BMS map + DFHCOMMAREA |
| EIBAID dispatch | None | DFHENTER + DFHPF3 + WHEN OTHER | None | DFHENTER + DFHPF3 + DFHCLEAR + WHEN OTHER |
| Output | 133-byte FB report file (TRANREPT) + DISPLAY | TDQ JOBS 17-line JCL + BMS SEND | 80-byte LS-RESULT | BMS SEND + XCTL |
| FUNCTION CURRENT-DATE | NO | YES (Monthly/Yearly modes) | NO | YES (header) |
| Clock injection needed | NO | YES (`ScopedValue<Clock>`) | NO | YES |
| Pagination | YES (PAGE_SIZE=20) | NO | NO | NO |
| Preserved bugs | EOF stale-amount + shared-prefix paragraph names | None notable in CBTRN03C-equivalent areas | None | None notable |
| Test fixture pattern | input/README only + expected/README + 2 placeholder data files (reptfile, stdout) | input/README only + expected/README + 4 placeholder data files | input/README only + expected/README + 3 placeholder data files | input/README only + expected/README + 2 placeholder data files |

## Verbatim Message Catalog Cross-Reference

The 26 messages enumerated in Phase 5 constitute the COMPLETE catalog of
DISPLAY statements for CBTRN03C. Cross-references in `java/MIGRATION_NOTES.md`
will record any message-string changes (NONE are expected per AAP §0.7.1).
The Java translation MUST produce byte-identical strings for each of the 26
catalogued messages.

## Source Lineage

The following source files are REFERENCE only — they are UNCHANGED per AAP
§0.1.1, AAP §0.2.2, and AAP §0.7.1:

- `app/cbl/CBTRN03C.cbl` (649 lines) — Primary COBOL source.
- `app/jcl/TRANREPT.jcl` (85 lines) — JCL job driving CBTRN03C; `STEP10R EXEC PGM=CBTRN03C` at L59.
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD layout (350 bytes; 14 elementary items including TRAN-AMT, TRAN-CARD-NUM, TRAN-PROC-TS).
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD layout (50 bytes; 16-byte XREF-CARD-NUM key).
- `app/cpy/CVTRA03Y.cpy` — TRAN-TYPE-RECORD layout (60 bytes; 2-byte TRAN-TYPE key).
- `app/cpy/CVTRA04Y.cpy` — TRAN-CAT-RECORD layout (60 bytes; 6-byte composite TRAN-CAT-KEY = 2-byte type + 4-byte cat).
- `app/cpy/CVTRA07Y.cpy` — Report header/footer structures (REPORT-NAME-HEADER, TRANSACTION-DETAIL-REPORT, TRANSACTION-HEADER-1/2, REPORT-PAGE-TOTALS, REPORT-ACCOUNT-TOTALS, REPORT-GRAND-TOTALS).
- `app/data/ASCII/dailytran.txt` (105,300 bytes; 300 records of 350 bytes each) — TRANFILE input fixture.
- `app/data/ASCII/cardxref.txt` (1,850 bytes; 37 records of 50 bytes each) — CARDXREF input fixture.
- `app/data/ASCII/trantype.txt` (427 bytes; ~7 records of 60 bytes each) — TRANTYPE input fixture.
- `app/data/ASCII/trancatg.txt` (1,098 bytes; ~18 records of 60 bytes each) — TRANCATG input fixture.

## Capture Procedure Cross-Reference

The capture procedure is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP
§0.7.5. CBTRN03C has a deterministic fixed-input nature (no Clock dependency,
no `FUNCTION CURRENT-DATE`), so re-capture is required only when one of the
following triggers occurs: changes to the ASCII fixtures under
`app/data/ASCII/`, changes to the DATEPARM range, or changes to the COBOL
source `app/cbl/CBTRN03C.cbl`. Do NOT ad-hoc edit captured outputs; always
re-run the capture procedure.

## Cross-References

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbTrn03CGoldenTest.java` — JUnit 5 test class consuming this fixture.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class with byte-for-byte parity assertion.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CbTrn03C.java` — Class under test.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java` — TRAN-RECORD record (350 bytes from CVTRA05Y).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` — CARD-XREF-RECORD (50 bytes from CVACT03Y).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranTypeRecord.java` — TRAN-TYPE-RECORD (60 bytes from CVTRA03Y).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranCatRecord.java` — TRAN-CAT-RECORD (60 bytes from CVTRA04Y).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/ReportHeaders.java` — REPORT-NAME-HEADER + TRANSACTION-HEADER-1/2 + REPORT-PAGE/ACCOUNT/GRAND-TOTALS records from CVTRA07Y.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/util/Decimals.java` — `BigDecimal` facade with `MathContext.DECIMAL128`.
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/TransactionReportApp.java` — Main class wiring the use case (per AAP §0.4.1 JCL-to-App mapping for TRANREPT).
- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation.

## Authority References

The following 19 AAP sections are cited in this README:

- AAP §0.1.1
- AAP §0.2.1
- AAP §0.2.2
- AAP §0.3.1
- AAP §0.3.2
- AAP §0.3.4
- AAP §0.3.6
- AAP §0.4.1
- AAP §0.6.1
- AAP §0.6.2
- AAP §0.6.4
- AAP §0.6.5
- AAP §0.6.6
- AAP §0.6.11
- AAP §0.7.1
- AAP §0.7.2
- AAP §0.7.4
- AAP §0.7.5
- AAP §0.8.1

## DO NOT Modify the Fixture Data Without Re-Capture

The two data files referenced in this README (`reptfile.txt`, `stdout.txt`)
are a coupled set with the read-only ASCII fixtures under `app/data/ASCII/`
and the synthesized DATEPARM. If any of those inputs change (ASCII fixture
content, DATEPARM range, or COBOL source), this fixture MUST be re-captured
from the COBOL reference run per `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits
to expected outputs without re-capture WILL break byte-for-byte parity. Do
NOT "fix" the EOF stale-amount bug in the output — it is the COBOL baseline
observable behavior preserved EXACTLY per AAP §0.7.1. Do NOT "fix" the
shared-prefix paragraph names in the Java translation — they are preserved
via Javadoc annotations per AAP §0.7.1. Do NOT mask the PAN in the captured
`stdout.txt` — the test driver uses an unmasked sink for parity assertion;
production logs apply masking via a separate sink per AAP §0.7.2.

