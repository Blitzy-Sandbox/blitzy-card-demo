# Golden-Record Contract — CBCUS01C (Customer File Sequential Reader; JCL Driver: READCUST.jcl)

This document is the authoritative byte-for-byte contract for the Java translation of COBOL program CBCUS01C, a read-only sequential display utility that opens a single VSAM KSDS customer file, walks every record in key order, and emits each successful record to stdout TWICE per iteration (once at `[app/cbl/CBCUS01C.cbl:L96]` inside `1000-CUSTFILE-GET-NEXT`, once at `[app/cbl/CBCUS01C.cbl:L78]` in the main loop after `PERFORM` returns) — the marquee **DOUBLE-DISPLAY pattern** that unifies the CBCUS01C / CBACT01C / CBACT02C / CBACT03C family of simple sequential readers.

The companion JUnit 5 test class `com.blitzy.carddemo.tests.golden.CbCus01CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and consumes the single captured fixture file documented below.

The test class is `@Disabled("Awaiting COBOL CBCUS01C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the DOUBLE-DISPLAY pattern: each of 50 input records MUST emit twice (L96 then L78), producing 102 total lines including START and END banners.")` per AAP §0.6.11 until `stdout.txt` is present in this folder with content captured from a COBOL reference run and committed.

Every claim in this README about COBOL behavior cites a specific line range in the form `[<path>:Lnnn]` or `[<path>:Lnnn-Lmmm]` per AAP §0.8.1 citation discipline.

Unlike CBTRN01C (which has no JCL driver), CBCUS01C has a verified existing JCL driver at `app/jcl/READCUST.jcl` that invokes `EXEC PGM=CBCUS01C` at STEP05 with CUSTFILE DD pointing to `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`. The Java equivalent main class lives at `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCustomerDumpApp.java` per AAP §0.4.1; the golden-record test class, however, bypasses the main class and invokes `CbCus01C` directly via constructor-injected `CustomerRepository` port for harness simplicity.

The binding cascade flows down through 20 AAP sections enumerated in Phase 0 below.

## Phase 0: Authority and Source-of-Truth Cascade

The following 20 AAP sections govern every byte and every assertion described in this README:

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS; no behavior changes
- AAP §0.2.1 — Golden-record fixtures are in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE; never modified by the Java refactor
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` (documentation marker) + `<program>/expected/` (this folder)
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports define the Java translation shape
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521)
- AAP §0.3.6 — Hexagonal architecture; no Spring container, no application framework
- AAP §0.4.1 — CBCUS01C -> CbCus01C in `com.blitzy.carddemo.application.customer` (NOTE: `customer/` subpackage, NOT `account/`); ASCII fixtures REFERENCE only, NEVER copied into this folder
- AAP §0.6.1 — `Decimals` utility centralizes monetary arithmetic with `MathContext.DECIMAL128`; NOT applicable to CBCUS01C because the program performs no arithmetic
- AAP §0.6.2 — Sealed-type pattern for closed value sets and lookup outcomes (used here for APPL-RESULT 88-level conditions, FILE STATUS codes, and the TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES)
- AAP §0.6.4 — `java.time` only for date and time; never `java.util.Date` or `Calendar`
- AAP §0.6.5 — `java.nio.file` for all file I/O; EBCDIC IBM-1047 default codepage with per-file override
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely in new code; sequential execution preserved for ordered output (NO virtual threads in CBCUS01C path — DOUBLE-DISPLAY ordering must be deterministic)
- AAP §0.6.8 — Program-by-program COBOL-to-Java mapping checklist confirms CBCUS01C -> CbCus01C
- AAP §0.6.11 — Golden-record harness is the PR gate; `@Disabled` until COBOL captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve the DOUBLE-DISPLAY pattern, the L129 `'CUSTFILE'` (no space) literal versus L110 / L147 `'CUSTOMER FILE'` (with space) inconsistency, the Z-prefix paragraph naming for Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS, and the identical literal in both branches of Z-DISPLAY-IO-STATUS at L168 / L172
- AAP §0.7.2 — No PAN in production logs (mask all but last 4 digits); for CBCUS01C the sensitive PII fields are CUST-SSN (PIC 9(09)) and CUST-GOVT-ISSUED-ID (PIC X(20)); test driver uses unmasked sink for parity assertion, production sinks apply masking via a separate path
- AAP §0.7.4 — No preview features (JEP 502 Stable Values, JEP 505 Structured Concurrency, JEP 507 Primitive Patterns, JEP 512 Compact Source Files in production code); no `default` branches that hide cases in pattern-matching switch
- AAP §0.7.5 — Capture procedure for golden-record fixtures is documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]` for every claim about COBOL behavior

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBCUS01C` | `[app/cbl/CBCUS01C.cbl:L23]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBCUS01C.cbl:L24]` |
| Source line count | 178 | `app/cbl/CBCUS01C.cbl` (per `wc -l`) |
| JCL driver | `app/jcl/READCUST.jcl` (EXISTS — verified) | `grep -l "CBCUS01C" app/jcl/*.jcl` returns `app/jcl/READCUST.jcl` |
| FILE-CONTROL SELECTs | 1 file (CUSTFILE) | `[app/cbl/CBCUS01C.cbl:L28-L33]` |
| FD declaration | 500-byte CUSTFILE-FILE (FD-CUST-ID PIC 9(09) + FD-CUST-DATA PIC X(491)) | `[app/cbl/CBCUS01C.cbl:L37-L40]` |
| Files actively read | 1 (CUSTFILE) | Main loop at `[app/cbl/CBCUS01C.cbl:L74-L81]` |
| Files opened-but-unused | 0 | Single-port profile; CONTRAST with CBTRN01C's 3 opened-but-unused |
| Java FQCN under test | `com.blitzy.carddemo.application.customer.CbCus01C` (NOTE: `customer/` subpackage, NOT `account/`) | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbCus01CGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |

The `@CobolProgram("CBCUS01C")` Javadoc-style annotation MUST cite the original PROGRAM-ID, the source path `app/cbl/CBCUS01C.cbl`, and the translation date per AAP §0.7.1. The annotation is the only durable traceability link between the Java class and its COBOL origin once the source-tree drift over time makes side-by-side reading harder.

Each translated COBOL paragraph MUST carry a `@CobolParagraph("<NUMERIC-PREFIX-NAME>")` Javadoc annotation citing the original paragraph name verbatim. For CBCUS01C the five required annotations are `@CobolParagraph("1000-CUSTFILE-GET-NEXT")`, `@CobolParagraph("0000-CUSTFILE-OPEN")`, `@CobolParagraph("9000-CUSTFILE-CLOSE")`, `@CobolParagraph("Z-ABEND-PROGRAM")`, and `@CobolParagraph("Z-DISPLAY-IO-STATUS")` — the Z-prefix on the last two is PRESERVED per AAP §0.7.1 even though it deviates from the 4-digit numeric prefix pattern used by the other three paragraphs.

The `@Disabled` mandate: the test class remains `@Disabled` until `stdout.txt` is present with captured (not placeholder) content per AAP §0.6.11. Removing the `@Disabled` annotation prematurely will cause the test to fail because the expected output file does not exist in this initial commit.

## Phase 2: Files in This Folder

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBCUS01C golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbCus01CGoldenTest` once `stdout.txt` is captured per AAP §0.7.5.

### `stdout.txt` — Captured DISPLAY Output (CAPTURE PLACEHOLDER)

The single captured output of a reference COBOL run of CBCUS01C against `app/data/ASCII/custdata.txt`.

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in the platform's newline (LF on captured run from Linux).
- **First line**: L71 banner `` `'START OF EXECUTION OF PROGRAM CBCUS01C'` ``
- **Last line**: L85 banner `` `'END OF EXECUTION OF PROGRAM CBCUS01C'` ``
- **Per-record body** (executes once per CUSTFILE record): TWO lines per record:
  - L96: `DISPLAY CUSTOMER-RECORD` (inside `1000-CUSTFILE-GET-NEXT` on `CUSTFILE-STATUS = '00'` success path — FIRST half of DOUBLE-DISPLAY pattern; 500-byte fixed-width record emitted as one line followed by LF)
  - L78: `DISPLAY CUSTOMER-RECORD` (in main loop after `PERFORM 1000-CUSTFILE-GET-NEXT` returns with `END-OF-FILE = 'N'` — SECOND half of DOUBLE-DISPLAY pattern; same 500-byte record emitted again)
- **Total expected line count**: `2 + (2N)` where N is the number of CUSTFILE records. For the current `app/data/ASCII/custdata.txt` fixture (N = 50, verified via `awk 'END{print NR}'`), expected = `2 + 100 = 102` lines.
- **Byte-for-byte parity rule**: Java captured stdout MUST equal the captured baseline BYTE-for-BYTE. Trailing whitespace and padding direction preserved exactly. Line breaks preserved exactly (LF).
- **PII exposure dichotomy**: CBCUS01C does NOT process card PANs (no DALYTRAN-CARD-NUM); CUSTOMER-RECORD does contain CUST-SSN PIC 9(09) (9-byte numeric SSN) and CUST-GOVT-ISSUED-ID PIC X(20) (20-byte government-issued ID) — both are sensitive PII. The test-only sink writes UNMASKED bytes for parity assertion per AAP §0.7.1 (preserve COBOL behavior exactly); production logger sinks apply SSN and government-ID masking per AAP §0.7.2 via a separate sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- **No deterministic Clock required**: CBCUS01C does NOT invoke `FUNCTION CURRENT-DATE`; captured output is naturally deterministic given fixed input. No `java.time.Clock` injection point exists in `CbCus01C`.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT exist yet; `CbCus01CGoldenTest` is `@Disabled` until captured content is committed via the procedure in `java/MIGRATION_NOTES.md` §1.6.

## Phase 3: Conceptual Input Universe

The single ASCII input that produces the captured baseline is:

- CUSTFILE: `app/data/ASCII/custdata.txt` (25,050 bytes; 50 sequential 500-byte records per `app/cpy/CVCUS01Y.cpy`, each followed by LF) — PRIMARY input

Unlike CBTRN01C's 6-file ensemble (DALYTRAN, CUSTOMER, XREF, CARD, ACCOUNT, TRANSACT) or CBTRN02C's 4-file ensemble (DALYTRAN, XREF, ACCOUNT, TCATBAL plus reject and transact outputs), CBCUS01C reads ONE file only. There are NO auxiliary inputs, NO lookup files, NO update files, NO reject outputs.

The fixture is NEVER copied into this folder per AAP §0.4.1 — it is referenced via classpath relative path from `app/data/ASCII/` to honor the AAP §0.2.2 immutability mandate. The Java test class resolves the classpath via `resolveAppDataPath("custdata.txt")` helper from the `GoldenRecordTest` base class.

CBCUS01C performs NO writes, NO rewrites, and NO updates; all inputs are read-only and the original `app/data/ASCII/custdata.txt` remains BYTE-IDENTICAL after the test run per AAP §0.2.2.

The fixture contains 50 records with sequential 9-digit customer IDs `000000001` through `000000050` followed by realistic varied first / middle / last names, three 50-char address lines, state / country / ZIP, two phone numbers, SSN, government ID, DOB, EFT account, primary cardholder indicator, FICO score, and trailing 168-byte FILLER. Per the CUSTOMER-RECORD layout in `app/cpy/CVCUS01Y.cpy` the 18 named fields plus the FILLER sum to exactly 500 bytes (9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3 + 168 = 500).

Byte offsets within each 500-byte CUSTOMER-RECORD (0-indexed; cumulative offset = sum of preceding lengths):

- bytes 0-8 (9): CUST-ID PIC 9(09)
- bytes 9-33 (25): CUST-FIRST-NAME PIC X(25)
- bytes 34-58 (25): CUST-MIDDLE-NAME PIC X(25)
- bytes 59-83 (25): CUST-LAST-NAME PIC X(25)
- bytes 84-133 (50): CUST-ADDR-LINE-1 PIC X(50)
- bytes 134-183 (50): CUST-ADDR-LINE-2 PIC X(50)
- bytes 184-233 (50): CUST-ADDR-LINE-3 PIC X(50)
- bytes 234-235 (2): CUST-ADDR-STATE-CD PIC X(02)
- bytes 236-238 (3): CUST-ADDR-COUNTRY-CD PIC X(03)
- bytes 239-248 (10): CUST-ADDR-ZIP PIC X(10)
- bytes 249-263 (15): CUST-PHONE-NUM-1 PIC X(15)
- bytes 264-278 (15): CUST-PHONE-NUM-2 PIC X(15)
- bytes 279-287 (9): CUST-SSN PIC 9(09) — SENSITIVE PII (test sink unmasked; production sink masks all but last 4)
- bytes 288-307 (20): CUST-GOVT-ISSUED-ID PIC X(20) — SENSITIVE PII (test sink unmasked; production sink masks all but last 4)
- bytes 308-317 (10): CUST-DOB-YYYY-MM-DD PIC X(10) — parsed as `java.time.LocalDate` per AAP §0.6.4
- bytes 318-327 (10): CUST-EFT-ACCOUNT-ID PIC X(10)
- byte 328 (1): CUST-PRI-CARD-HOLDER-IND PIC X(01)
- bytes 329-331 (3): CUST-FICO-CREDIT-SCORE PIC 9(03)
- bytes 332-499 (168): FILLER PIC X(168) — preserved as `byte[]` component on the Java `record` to maintain round-trip parity

The Java `CustomerRecord` class therefore has 19 components: 18 named fields plus the FILLER byte array. The `parse(byte[])` factory consumes the 500-byte buffer and binds each field by slicing the appropriate offset range; the `encode()` instance method re-emits the 500-byte buffer by concatenating field encodings in declaration order. The round-trip invariant `parse(buf).encode() == buf` holds for every valid 500-byte buffer per AAP §0.3.2.

## Phase 4: Sequential Read Flow (5 paragraphs)

The PROCEDURE DIVISION at `[app/cbl/CBCUS01C.cbl:L70]` is the driver. Its full flow:

- Start banner DISPLAY at `[app/cbl/CBCUS01C.cbl:L71]` — unconditional first stdout line `'START OF EXECUTION OF PROGRAM CBCUS01C'`
- `PERFORM 0000-CUSTFILE-OPEN.` at `[app/cbl/CBCUS01C.cbl:L72]`
- Main loop at `[app/cbl/CBCUS01C.cbl:L74-L81]` — `PERFORM UNTIL END-OF-FILE = 'Y'`
- Per-iteration body:
  - L75-L80 nested IF / PERFORM / IF / DISPLAY block
  - L76: `PERFORM 1000-CUSTFILE-GET-NEXT`
  - L78: `DISPLAY CUSTOMER-RECORD` — main-loop record dump (SECOND half of DOUBLE-DISPLAY pattern) emitted after the PERFORM returns and after the inner IF confirms `END-OF-FILE = 'N'`
- `PERFORM 9000-CUSTFILE-CLOSE.` at `[app/cbl/CBCUS01C.cbl:L83]`
- End banner DISPLAY at `[app/cbl/CBCUS01C.cbl:L85]` — unconditional last stdout line `'END OF EXECUTION OF PROGRAM CBCUS01C'`
- `GOBACK.` at `[app/cbl/CBCUS01C.cbl:L87]`

Paragraph `1000-CUSTFILE-GET-NEXT` at `[app/cbl/CBCUS01C.cbl:L92]`:

- L93: `READ CUSTFILE-FILE INTO CUSTOMER-RECORD.`
- L94: `IF CUSTFILE-STATUS = '00'`
- L95: `MOVE 0 TO APPL-RESULT`
- L96: `DISPLAY CUSTOMER-RECORD` — FIRST half of DOUBLE-DISPLAY pattern
- L98: `IF CUSTFILE-STATUS = '10' MOVE 16 TO APPL-RESULT`
- L101: other status -> `MOVE 12 TO APPL-RESULT`
- L104-L114: APPL-AOK / APPL-EOF / error decision tree
- L107-L108: APPL-EOF -> `MOVE 'Y' TO END-OF-FILE`
- L110: non-EOF error path emits `'ERROR READING CUSTOMER FILE'`
- L111-L113: `MOVE CUSTFILE-STATUS TO IO-STATUS` -> `PERFORM Z-DISPLAY-IO-STATUS` -> `PERFORM Z-ABEND-PROGRAM`
- L116: `EXIT`

Paragraph `0000-CUSTFILE-OPEN` at `[app/cbl/CBCUS01C.cbl:L118]`:

- L119: `MOVE 8 TO APPL-RESULT`
- L120: `OPEN INPUT CUSTFILE-FILE`
- L121: `IF CUSTFILE-STATUS = '00'` -> `MOVE 0 TO APPL-RESULT`
- L123: ELSE -> `MOVE 12 TO APPL-RESULT`
- L126-L133: success / failure decision tree
- L129: `DISPLAY 'ERROR OPENING CUSTFILE'` — NOTE: literal uses `'CUSTFILE'` with NO SPACE, distinct from L110 / L147
- L130-L132: MOVE / PERFORM error chain
- L134: `EXIT`

Paragraph `9000-CUSTFILE-CLOSE` at `[app/cbl/CBCUS01C.cbl:L136]`:

- L137: `ADD 8 TO ZERO GIVING APPL-RESULT.`
- L138: `CLOSE CUSTFILE-FILE`
- L139: `IF CUSTFILE-STATUS = '00'`
- L140: `SUBTRACT APPL-RESULT FROM APPL-RESULT` — sets APPL-RESULT to 0 (idiomatic COBOL zero-set)
- L142: ELSE -> `ADD 12 TO ZERO GIVING APPL-RESULT`
- L144-L151: success / failure decision tree
- L147: `DISPLAY 'ERROR CLOSING CUSTOMER FILE'`
- L148-L150: MOVE / PERFORM error chain
- L152: `EXIT`

Paragraph `Z-ABEND-PROGRAM` at `[app/cbl/CBCUS01C.cbl:L154]`:

- L155: `DISPLAY 'ABENDING PROGRAM'`
- L156: `MOVE 0 TO TIMING`
- L157: `MOVE 999 TO ABCODE`
- L158: `CALL 'CEE3ABD'.`

Paragraph `Z-DISPLAY-IO-STATUS` at `[app/cbl/CBCUS01C.cbl:L161]`:

- L162-L168: IF branch (`IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'`): extract `IO-STAT1` into `IO-STATUS-04(1:1)`, zero `TWO-BYTES-BINARY`, MOVE `IO-STAT2` to `TWO-BYTES-RIGHT`, MOVE `TWO-BYTES-BINARY` to `IO-STATUS-0403`, then `DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04`
- L169-L172: ELSE branch (status numeric and not starting with `'9'`): MOVE `'0000'` to `IO-STATUS-04`, MOVE `IO-STATUS` into `IO-STATUS-04(3:2)`, then `DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04` — IDENTICAL literal to L168
- L174: `EXIT`

NO writes, NO rewrites, NO updates. The Java translation MUST faithfully open / close CUSTFILE-FILE and produce DOUBLE-DISPLAY for each successful read. The five paragraphs map to five private methods on `CbCus01C`:

- `cobolMain()` — the PROCEDURE DIVISION entry point (L70-L87): emit start banner, open the file, run the read-loop, close the file, emit end banner
- `custfileGetNext()` — the `1000-CUSTFILE-GET-NEXT` body (L92-L116)
- `custfileOpen()` — the `0000-CUSTFILE-OPEN` body (L118-L134)
- `custfileClose()` — the `9000-CUSTFILE-CLOSE` body (L136-L152)
- `zAbendProgram()` — the `Z-ABEND-PROGRAM` body (L154-L158): throws an `AbendException` carrying `abcode = 999`
- `zDisplayIoStatus()` — the `Z-DISPLAY-IO-STATUS` body (L161-L174)

The split is one-to-one; the names follow `lowerCamelCase` per Java conventions but each carries the original `@CobolParagraph` annotation preserving the verbatim COBOL paragraph name.

## Phase 5: Verbatim DISPLAY Message Catalog (8 entries, verified)

Every distinct DISPLAY content in CBCUS01C, verified by direct reading of `app/cbl/CBCUS01C.cbl`. The L168 / L172 pair counts as one catalog entry because both branches emit an identical literal.

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBCUS01C'` `` | L71 | Unconditional start banner |
| 2 | `DISPLAY CUSTOMER-RECORD` (full 500-byte record) | L78 | Main-loop record dump (after `PERFORM 1000-CUSTFILE-GET-NEXT` returns with `END-OF-FILE = 'N'`) — SECOND half of DOUBLE-DISPLAY pattern |
| 3 | `` `'END OF EXECUTION OF PROGRAM CBCUS01C'` `` | L85 | Unconditional end banner |
| 4 | `DISPLAY CUSTOMER-RECORD` (full 500-byte record) | L96 | Inside `1000-CUSTFILE-GET-NEXT` on `CUSTFILE-STATUS = '00'` success path — FIRST half of DOUBLE-DISPLAY pattern |
| 5 | `` `'ERROR READING CUSTOMER FILE'` `` | L110 | `1000-CUSTFILE-GET-NEXT` non-EOF error path |
| 6 | `` `'ERROR OPENING CUSTFILE'` `` | L129 | `0000-CUSTFILE-OPEN` failure (NOTE: literal `'CUSTFILE'` — NO SPACE — distinct from L110 / L147 `'CUSTOMER FILE'` — PRESERVED per AAP §0.7.1) |
| 7 | `` `'ERROR CLOSING CUSTOMER FILE'` `` | L147 | `9000-CUSTFILE-CLOSE` failure path |
| 8 | `` `'ABENDING PROGRAM'` `` | L155 | `Z-ABEND-PROGRAM` before `CALL 'CEE3ABD'` |
| 9 | `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 | L168, L172 | `Z-DISPLAY-IO-STATUS` — both IF / ELSE branches emit identical literal |

ALL messages preserve EXACT bytes including embedded SPACES, COLON placement (one colon, one trailing space, then `NNNN`), and the L129 `'CUSTFILE'` (no space) versus L110 / L147 `'CUSTOMER FILE'` (with space) inconsistency. No Unicode characters appear in any of the catalogued literals. The Java translation MUST produce byte-identical strings.

Audit trail observations on the message catalog:

- Banner-class messages (entries 1 and 3) are unconditional — they appear in every successful run regardless of input content or input record count
- Record-dump entries (2 and 4) appear `N` times each in a successful run (`N` = number of input records); they comprise the bulk of the stdout volume (100 of 102 lines for the 50-record fixture, approximately 98% of total stdout)
- Error-class messages (entries 5, 6, 7) appear ZERO times in a successful run; they only appear when CUSTFILE-STATUS yields an unexpected code and the abend chain is triggered
- Abend-class message (entry 8) appears ZERO times in a successful run; it precedes the `CALL 'CEE3ABD'` invocation that ends the process abnormally
- IO-status diagnostic (entry 9) appears ZERO times in a successful run; it is invoked by the error path of the open / read / close paragraphs only when their respective FILE STATUS code is non-zero (and non-`'10'` for the read case)

The deterministic 50-record fixture therefore exercises exactly entries 1, 2, 3, and 4 — entries 5-9 are documented for completeness but are not asserted by the captured baseline. Coverage of entries 5-9 belongs to negative-path unit tests not in this golden-record fixture; those tests inject synthetic FILE STATUS codes via a mocked `CustomerRepository` and assert each error-message branch in isolation. The mocked variants are out of scope for this README and live under `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/application/customer/` per the standard module layout.

## Phase 6: Read-Outcome Branches (NO Reject Codes)

CBCUS01C emits NO reject codes. Unlike CBTRN02C, which classifies failed transactions with 5 reject codes (100 / 101 / 102 / 103 / 109) and writes a reject record per failure, there is no reject file, no reject record layout, and no validation trailer record in CBCUS01C. CBCUS01C is a pure read-and-display utility.

The read outcomes after each `READ CUSTFILE-FILE` at `[app/cbl/CBCUS01C.cbl:L93]`:

- `CUSTFILE-STATUS = '00'` -> success path -> `MOVE 0 TO APPL-RESULT` at L95 -> `DISPLAY CUSTOMER-RECORD` at L96 (FIRST half of DOUBLE-DISPLAY pattern; SECOND half at L78 follows after PERFORM returns)
- `CUSTFILE-STATUS = '10'` -> EOF path -> `MOVE 16 TO APPL-RESULT` at L99 -> APPL-EOF condition true at L107 -> `MOVE 'Y' TO END-OF-FILE` at L108 -> main loop terminates
- Any other status -> error path -> `MOVE 12 TO APPL-RESULT` at L101 -> `DISPLAY 'ERROR READING CUSTOMER FILE'` at L110 -> `MOVE CUSTFILE-STATUS TO IO-STATUS` at L111 -> `PERFORM Z-DISPLAY-IO-STATUS` at L112 -> `PERFORM Z-ABEND-PROGRAM` at L113 (which DISPLAYs `'ABENDING PROGRAM'` at L155, sets ABCODE = 999 at L157, and `CALL 'CEE3ABD'` at L158)

APPL-RESULT 88-level conditions at `[app/cbl/CBCUS01C.cbl:L61-L63]`: APPL-AOK = 0, APPL-EOF = 16. Other values trigger abend via `Z-ABEND-PROGRAM`. The same APPL-RESULT discriminator pattern is used by `0000-CUSTFILE-OPEN` at L121-L133 and `9000-CUSTFILE-CLOSE` at L139-L151. For the deterministic fixture used in this golden-record test, only the success path (`CUSTFILE-STATUS = '00'`) and the final EOF path (`CUSTFILE-STATUS = '10'` after record 50) are exercised; the error path is unreachable given an intact `app/data/ASCII/custdata.txt`.

The COBOL FILE STATUS code values that CBCUS01C distinguishes (a strict subset of the COBOL standard FILE STATUS keys):

- `'00'`: successful operation (read returned a record; open succeeded; close succeeded)
- `'10'`: end-of-file (read past the last record returns this code; the operation does NOT return a record on this status)
- Any other code (e.g., `'23'` record-not-found, `'30'` permanent error, `'35'` file-not-found at open, `'9x'` implementation-defined errors): treated as a fatal error via the L101 / L110 / L113 abend chain

The sealed `FileStatus` hierarchy in `com.blitzy.carddemo.domain` per AAP §0.6.2 has permits covering these COBOL standard codes. Pattern-matching switch sites on the hierarchy MUST be exhaustive (no `default` branch per AAP §0.7.4).

RETURN-CODE: NOT set explicitly on success or failure. On the `Z-ABEND-PROGRAM` path, ABCODE = 999 (set at L157) is passed to `CEE3ABD` via the `CALL` at L158; the OS-level return code is platform-dependent. On normal completion (the only case exercised by the deterministic fixture), RETURN-CODE remains at its default value (0). The Java translation surfaces these two distinct exit modes via the main-class `System.exit(int)` value: 0 on normal completion, 999 on abend (mirroring ABCODE).

## Phase 7: Single-Output stdout.txt Byte Contract

The byte contract for the single output:

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in LF (Unix-style newline on captured Linux run; on z/OS the captured run uses EBCDIC newline conventions but the captured fixture is in ASCII after transcoding per AAP §0.6.5).
- **First line**: L71 banner `` `'START OF EXECUTION OF PROGRAM CBCUS01C'` ``
- **Last line**: L85 banner `` `'END OF EXECUTION OF PROGRAM CBCUS01C'` ``
- **Per CUSTFILE record body** (executes once per input record):
  - 1 line: L96 unconditional `DISPLAY CUSTOMER-RECORD` (500-byte record verbatim) — emitted INSIDE `1000-CUSTFILE-GET-NEXT` after the read succeeds with status `'00'`
  - 1 line: L78 conditional `DISPLAY CUSTOMER-RECORD` (same 500-byte record) — emitted in main loop AFTER PERFORM returns with `END-OF-FILE = 'N'`
- **Total expected line count**: `2 + (2 * N)` where N is the number of CUSTFILE records in `app/data/ASCII/custdata.txt`. Currently N = 50 records (verified via `awk 'END{print NR}' app/data/ASCII/custdata.txt`). For the present fixture: `2 + (2 * 50) = 102` lines.
- **Total expected byte count** (approximate; subject to compiler trailing-space conventions): approximately `40 + (2 * 50 * 500) + 40 + 102 LFs` ~= roughly 50,182 bytes. The exact value is captured in `stdout.txt` and asserted byte-for-byte by the test; this README does NOT pre-commit a byte count because compiler trailing-space conventions vary slightly between GnuCOBOL and z/OS COBOL.
- **Byte-for-byte parity rule**: `CbCus01C.run(...)` MUST produce, for the deterministic input set, a captured stdout byte sequence equal to this captured baseline BYTE-for-BYTE.
- **Trailing whitespace**: COBOL DISPLAY of a fixed-width record emits trailing spaces from the 168-byte FILLER and any non-fully-populated `PIC X(...)` field; the captured `stdout.txt` is the reference. The Java translation MUST match byte-by-byte. The Java sink implementation MUST NOT call `String.trim()` or `String.stripTrailing()` on the record bytes before writing.
- **Line termination**: every line ends with a single LF byte (`0x0A`); there are no CR bytes (`0x0D`); the final line (the END banner) ends with LF. The file therefore ends with `\n` per POSIX convention. The Java sink uses `OutputStream.write(0x0A)` after each DISPLAY equivalent rather than `PrintStream.println` (which would use the platform line separator).
- **PII exposure**: L78's and L96's full-record dumps include CUST-SSN (9-byte numeric) and CUST-GOVT-ISSUED-ID (20-byte alphanumeric) unmasked. The test-only sink writes the unmasked bytes for parity assertion; production logger sinks apply masking per AAP §0.7.2 via a separate sink.
- **No deterministic Clock needed**: CBCUS01C does NOT invoke `FUNCTION CURRENT-DATE`; no timestamp generation occurs; the captured output is naturally deterministic given fixed input.

## Phase 8: NO Opened-But-Unused Files

CBCUS01C opens EXACTLY ONE file (CUSTFILE-FILE) and reads it sequentially. This is in CONTRAST to CBTRN01C, which opens 6 files but reads only 3 (3 opened-but-unused: CUSTOMER, CARD, TRANSACT). The Java translation has a SINGLE `CustomerRepository` port — no auxiliary ports — making CbCus01C the simplest in the application layer.

| File | Open | Close | Active | Status |
|---|---|---|---|---|
| CUSTFILE-FILE | L120 (`0000-CUSTFILE-OPEN`) | L138 (`9000-CUSTFILE-CLOSE`) | YES (sequential READ at L93) | Active — sole port |

No `opened-but-unused` boilerplate exists in CBCUS01C. The single CUSTFILE-FILE port is both opened and actively read. The Java translation's `CustomerRepository` port is therefore the sole constructor-injected dependency of `CbCus01C`.

## Phase 9: Preserved Behaviors and Idiosyncrasies (DO NOT FIX)

The following 10 COBOL idiosyncrasies of CBCUS01C are PRESERVED VERBATIM per AAP §0.7.1 Minimal Change Clause. NONE are bugs to be `fixed`. Each entry below names the construct, cites the source line, and explains why the Java translation must preserve it.

1. **DOUBLE-DISPLAY CUSTOMER-RECORD pattern** at L78 and L96 — the marquee behavior. Two DISPLAY calls emit the SAME successful record TWICE per iteration. Sibling family: CBACT01C, CBACT02C, CBACT03C share this pattern (all simple sequential readers). PRESERVED per AAP §0.7.1. The Java translation MUST faithfully emit the record twice per successful read. Consequence of accidental elimination: stdout shrinks from 102 lines to 52 lines for the 50-record fixture; byte-for-byte parity fails immediately on line count. The L96 emission is INSIDE the read paragraph (the value of `CUSTOMER-RECORD` is the just-read row); the L78 emission is OUTSIDE the read paragraph in the main loop (the value of `CUSTOMER-RECORD` is the SAME just-read row because COBOL's WORKING-STORAGE is a single shared region — the L78 dump does NOT re-read the file). The Java translation must mirror this single-read-double-emit semantic.

2. **L129 `'ERROR OPENING CUSTFILE'`** literal — uses `'CUSTFILE'` (no space) while L110 uses `'CUSTOMER FILE'` (with space) and L147 uses `'CUSTOMER FILE'` (with space). The OPEN-error literal is inconsistent with the READ-error and CLOSE-error literals. PRESERVED verbatim per AAP §0.7.1. Do NOT add a space between `CUSTFILE` in the Java translation. Consequence of accidental fix: downstream log parsers that grep for the literal `'ERROR OPENING CUSTFILE'` (no space) will silently miss every CBCUS01C open failure once a space is added.

3. **Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS paragraphs lack 4-digit numeric prefix**. Other paragraphs use `0000-` / `1000-` / `9000-` prefixes. Z-prefix utility paragraphs are unusual but consistent with z/OS conventions for shared utility routines (Z-prefix typically indicates a `commonly invoked utility paragraph not in the main flow`). PRESERVED per AAP §0.7.1 in the `@CobolParagraph` annotation values. Consequence of accidental renaming: source-to-source traceability between Java and COBOL is broken; AAP §0.7.1 traceability mandate fails.

4. **MOVE 999 TO ABCODE before CALL `'CEE3ABD'`** at L157-L158 — the ABCODE value is 999, not 0 or any RETURN-CODE-style value. PRESERVED per AAP §0.7.1; the Java translation MUST raise an abend exception carrying the equivalent `ABCODE = 999` metadata. The CEE3ABD service is the IBM Language Environment abnormal-termination service; the 999 code is observable in z/OS job logs as a user abend code distinct from system abend codes. Consequence of accidental change: abend telemetry comparing COBOL and Java runs by error code will incorrectly classify identical failures as different incidents.

5. **APPL-RESULT 88-level conditions** at L61-L63 (APPL-AOK = 0, APPL-EOF = 16) — preserve as sealed-type pattern per AAP §0.6.2: a sealed hierarchy `ApplResult { Aok, Eof, Error(int code) }` with `Aok` and `Eof` as singleton instances and `Error` as a record carrying the residual status code. Pattern-matching switch sites on this hierarchy MUST be exhaustive (no `default` branch per AAP §0.7.4). The COBOL value `8` set at L119 and the value `12` set at L101 / L124 / L142 are NOT in the 88-level set; they map to `Error(8)` and `Error(12)` respectively, with the discriminator branch falling through to the error path.

6. **TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES** at L53-L56 — translate to sealed interface per AAP §0.6.2 with `TwoBytesBinary` and `TwoBytesAlpha` permits. This REDEFINES is used inside `Z-DISPLAY-IO-STATUS` at L165-L167 to convert a status nybble to a four-digit decimal display via the byte-level alias trick: zero the binary view, write a character into the low byte via the alpha view, then read back the binary view as a number — the trick depends on big-endian byte ordering of `PIC 9(4) BINARY` and on the alpha view sharing the same memory. The Java translation preserves the semantic via the sealed interface and explicit byte-level access (avoiding `Unsafe` per AAP).

7. **Z-DISPLAY-IO-STATUS emits identical literal in BOTH branches** at L168 and L172 — even though the IF branch (status not numeric or starts with `'9'`) and the ELSE branch (status numeric and not starting with `'9'`) use different value-conversion paths, the displayed literal `'FILE STATUS IS: NNNN'` is identical. The Java translation MUST emit byte-identical strings from both translated branches. PRESERVED per AAP §0.7.1. Consequence of refactoring the two branches into a `default` clause: the compiler-enforced exhaustiveness is lost per AAP §0.7.4.

8. **EXIT statement at the tail of every paragraph (L116, L134, L152, L174)** — paragraphs use `EXIT` as the final statement; control flow relies on PERFORM-return semantics, not fall-through. The Java translation MUST preserve PERFORM-style semantics (each paragraph is a private method called from the appropriate site; no `goto`; no fall-through between paragraphs). PRESERVED per AAP §0.7.1. The Java private methods do not need a literal `// EXIT` comment but they MUST NOT inline content from another paragraph; each paragraph is one method.

9. **Apache 2.0 license block at L1-L21 NOT reproduced** in either this README or the captured stdout.txt. PRESERVED-AS-IS in `app/cbl/CBCUS01C.cbl` per AAP §0.2.2 but NOT propagated into Java source files or test fixtures. The Java module has its own top-level `LICENSE` file (inherited from the repository root); per-file Apache headers in `.java` source files are out of scope for this refactor per AAP §0.7.1 Minimal Change Clause.

10. **Version-stamp comment trailer at L177** — the COBOL source carries a build-system version-and-date comment as its last meaningful line. NOT reproduced in this README or stdout.txt. PRESERVED-AS-IS in `app/cbl/CBCUS01C.cbl` per AAP §0.2.2 but NOT propagated. The version stamp is a build-system artifact that has no analog in the Maven shaded jar's manifest; Java versioning is captured via Maven's `${project.version}` and the `@CobolProgram` annotation's `translation date` attribute.

NONE of these idiosyncrasies are bugs to be `fixed`. They are observable behaviors that downstream consumers (operators monitoring logs, parsers reading FILE STATUS codes, byte-for-byte parity assertions) may depend on. Per AAP §0.7.1 Minimal Change Clause, ALL of these are preserved verbatim.

## Phase 10: Java Mapping Invariants

The following invariants govern the Java translation of CBCUS01C:

- **One class per COBOL PROGRAM-ID**: `CBCUS01C` -> `CbCus01C` in `com.blitzy.carddemo.application.customer` per AAP §0.4.1 (NOTE: `customer/` subpackage, NOT `account/` — distinct from CBACT01C / CBACT02C / CBACT03C which live in `account/`)
- **`@CobolProgram("CBCUS01C")` annotation** citing original PROGRAM-ID, source path `app/cbl/CBCUS01C.cbl`, and translation date per AAP §0.7.1
- **`@CobolParagraph` annotations** on each private method translated from a COBOL paragraph: `@CobolParagraph("1000-CUSTFILE-GET-NEXT")`, `@CobolParagraph("0000-CUSTFILE-OPEN")`, `@CobolParagraph("9000-CUSTFILE-CLOSE")`, `@CobolParagraph("Z-ABEND-PROGRAM")`, `@CobolParagraph("Z-DISPLAY-IO-STATUS")` — Z-prefix preserved per AAP §0.7.1
- **Constructor injection** of single port: `CustomerRepository` from `com.blitzy.carddemo.domain.port` — no Spring, no container per AAP §0.3.6. This is the SOLE constructor parameter — CBCUS01C is the minimum-dependency program in the application layer.
- **NO `BigDecimal` arithmetic site** in `CbCus01C` (CBCUS01C performs no math). The only numeric WORKING-STORAGE fields are APPL-RESULT (PIC S9(9) COMP) used as an enum-like discriminator and ABCODE / TIMING (PIC S9(9) BINARY) used for the CEE3ABD abend signal. CUST-FICO-CREDIT-SCORE (PIC 9(03)) is parsed as `int` in `CustomerRecord`; no `Decimals` utility call.
- **`java.time.LocalDate`** for CUST-DOB-YYYY-MM-DD (PIC X(10)) per AAP §0.6.4 inside `CustomerRecord`; the field appears in the L78 / L96 record dump as raw 10-byte PIC X bytes (not as a parsed `LocalDate`) — the `encode()` side renders `LocalDate` as `yyyy-MM-dd` ISO format which matches the COBOL PIC X(10) format byte-for-byte
- **NO `java.util.Date` / `java.util.Calendar` / `java.text.SimpleDateFormat`** per AAP §0.6.4
- **NO `java.io.File`** — use `java.nio.file.Path`, `Files.newByteChannel`, `SeekableByteChannel` per AAP §0.6.5
- **NO `ThreadLocal`** — use `ScopedValue` per AAP §0.6.6. `CbCus01C` is sequential single-threaded; **NO virtual-thread fan-out** (DISPLAY ordering MUST be preserved per AAP §0.6.6 — DOUBLE-DISPLAY pattern requires deterministic interleaving of L96 and L78 emissions per record)
- **EBCDIC IBM-1047 default codepage** per AAP §0.6.5; per-file override via `application.properties` keys (e.g., `carddemo.file.custdata.charset`). ASCII fixtures use `Charset.forName("US-ASCII")` for test runs
- **Sealed-type pattern** (per AAP §0.6.2) for: APPL-RESULT 88-level conditions as `ApplResult { Aok, Eof, Error(int code) }` sealed hierarchy; FILE STATUS code hierarchy; TWO-BYTES-ALPHA REDEFINES TWO-BYTES-BINARY as sealed `TwoBytes { Binary(short), Alpha(char, char) }`
- **Pattern-matching switch** for case-discrimination on FILE STATUS codes and APPL-RESULT — NO `default` branch per AAP §0.7.4; exhaustiveness enforced by compiler
- **Records, not POJOs**: `CustomerRecord` (from `app/cpy/CVCUS01Y.cpy`) declared as Java `record` per AAP §0.3.2 with 18 named components plus a 168-byte `byte[]` FILLER component
- **`parse(byte[])` and `encode()`** static factory plus instance method pair on `CustomerRecord` per AAP §0.3.2 byte-level contract; round-trip invariant `parse(buf).encode() == buf` for every valid 500-byte buffer
- **NO Spring, NO Hibernate, NO Spring Batch** — plain Java with constructor injection per AAP §0.3.6
- **Sequential execution mandated** — NO virtual threads per AAP §0.6.6 because the DOUBLE-DISPLAY pattern's deterministic ordering of L96 and L78 emissions is observable
- **JVM flags**: `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4
- **NO `--enable-preview`** per AAP §0.7.4
- **Test-only sink for unmasked PII**: production logger sinks apply CUST-SSN and CUST-GOVT-ISSUED-ID masking per AAP §0.7.2 via a separate sink; the test driver uses a separate sink that captures unmasked bytes for byte-for-byte parity
- **DOUBLE-DISPLAY pattern preserved**: the Java method translating `1000-CUSTFILE-GET-NEXT` MUST emit `CUSTOMER-RECORD` via the test sink at the same logical site as L96, AND the main-loop translation MUST also emit at the L78-equivalent site, producing 2N stdout lines for N records. The two emissions MUST be byte-identical and MUST appear in the order (L96 first, then L78) on each iteration.

## Phase 11: Test Class Override Map

`CbCus01CGoldenTest` extends `GoldenRecordTest` and overrides only the protected hook methods that identify the program under test, the input fixture path, the auxiliary inputs list (empty for this single-port reader), and the expected output file path. The base class handles single-port wiring, classpath resolution, byte-for-byte parity assertion, and `@Disabled` honoring.

```java
// com.blitzy.carddemo.tests.golden.CbCus01CGoldenTest
@Override
protected Class<?> programClass() {
    return com.blitzy.carddemo.application.customer.CbCus01C.class;
}

@Override
protected Path inputFile() {
    return resolveAppDataPath("custdata.txt");
}

@Override
protected List<Path> auxiliaryInputs() {
    return List.of(); // CBCUS01C reads ONE file only — no auxiliaries
}

@Override
protected Path expectedOutputFile() {
    return resolveExpectedOutputPath("cbcus01c", "stdout.txt");
}

// NO runProgram(...) override needed; default base-class hook handles single-port wiring
```

The base-class `runProgram(Class<?> programClass, Path inputFile, List<Path> auxiliaryInputs)` hook resolves the single port (`CustomerRepository`) by wiring a `FileCustomerRepository` instance against the supplied `inputFile` path, instantiates `CbCus01C` with that repository, and invokes the canonical entry method while a test-only `Appendable` sink captures stdout. After the run completes, the captured sink contents are written to a temporary file whose bytes are compared against `expectedOutputFile()` byte-for-byte. The test method body itself (`@Test void byteForByteParity()`) is inherited from `GoldenRecordTest`; `CbCus01CGoldenTest` provides only the four override hooks above.

`@Disabled` verification checklist — before removing the `@Disabled` annotation, the following 13 conditions MUST be confirmed:

1. Classpath fixture resolver returns non-null for `custdata.txt` (the sole input)
2. Expected `stdout.txt` exists and is non-empty (captured, not placeholder)
3. Expected output committed via the capture procedure in `java/MIGRATION_NOTES.md` §1.6 — NOT hand-edited
4. Original `app/data/ASCII/custdata.txt` remains BYTE-IDENTICAL after the test run (test harness MUST NOT mutate the source files per AAP §0.2.2)
5. No production dependency references `System.out` (DISPLAY translations use SLF4J in production; test driver uses an unmasked test-only sink for byte-for-byte parity)
6. `@CobolProgram("CBCUS01C")` annotation is present on `CbCus01C` class
7. `@CobolParagraph` annotations are present on each translated paragraph method, citing the full original COBOL paragraph name (e.g., `1000-CUSTFILE-GET-NEXT`, `0000-CUSTFILE-OPEN`, `9000-CUSTFILE-CLOSE`, `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS`)
8. No `--enable-preview` in the test runner's JVM args; no preview JEP 502 / 505 / 507 / 512 usage
9. The `-XX:+UseCompactObjectHeaders` flag (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) are documented in the test runner's JVM args
10. **NO virtual threads** are used in the `CbCus01C` execution path; sequential CUSTFILE read order preserved exactly per AAP §0.6.6
11. **DOUBLE-DISPLAY pattern verified**: 50 records produce 100 record-display lines bracketed by START + END banners = 102 lines total. The L96 emission (inside `1000-CUSTFILE-GET-NEXT`) precedes the L78 emission (in main loop) for each record in stdout order.
12. L129 `'ERROR OPENING CUSTFILE'` (no space between CUSTFILE) is preserved verbatim in the Java translation
13. Z-prefix paragraph naming preserved in `@CobolParagraph` annotation values

## Phase 12: Capture Procedure Cross-Reference

See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build / run path used to capture this fixture's `stdout.txt`. The capture procedure exists because the user prompt left this as a `TODO` marker per AAP §0.7.5.

- **Determinism**:
  - NO Clock injection required (CBCUS01C does NOT invoke `FUNCTION CURRENT-DATE`)
  - The single ASCII input `custdata.txt` is read-only and not mutated
  - The COBOL source contains no random-number generation, no system-time queries, no environment-variable reads beyond the JCL `CUSTFILE` DD assignment
  - Multiple captures from a stable baseline (same compiler version, same locale, same JCL) MUST produce byte-identical output
- **Capture command shape** (illustrative; canonical path documented in `java/MIGRATION_NOTES.md` §1.6): compile CBCUS01C with GnuCOBOL or z/OS COBOL -> execute with CUSTFILE ASSIGN pointing to `app/data/ASCII/custdata.txt` -> capture stdout -> commit as `stdout.txt`
- **GnuCOBOL invocation outline** (one of several valid capture paths; full instructions in `java/MIGRATION_NOTES.md` §1.6):
  - Compile: `cobc -x -free -I app/cpy/ app/cbl/CBCUS01C.cbl -o /tmp/cbcus01c`
  - Configure: the COBOL `SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE` clause maps `CUSTFILE` to a file name via the runtime's external-name resolution (e.g., environment variable `dd_CUSTFILE` or runtime configuration entry)
  - Execute: `dd_CUSTFILE=$(pwd)/app/data/ASCII/custdata.txt /tmp/cbcus01c > java/carddemo-tests/src/test/resources/golden/cbcus01c/expected/stdout.txt`
  - Verify: `wc -l java/carddemo-tests/src/test/resources/golden/cbcus01c/expected/stdout.txt` MUST report 102; first line MUST be `START OF EXECUTION OF PROGRAM CBCUS01C`; last line MUST be `END OF EXECUTION OF PROGRAM CBCUS01C`
  - z/OS COBOL captures will produce equivalent stdout when the JCL `STEP05` of `app/jcl/READCUST.jcl` is run with `SYSOUT` redirected to a dataset, then the dataset is downloaded and transcoded from EBCDIC to ASCII per AAP §0.6.5
- **PII exposure**:
  - COBOL CBCUS01C DOES `DISPLAY CUSTOMER-RECORD` at L78 and L96 on every iteration, which includes CUST-SSN (9-byte numeric SSN) and CUST-GOVT-ISSUED-ID (20-byte alphanumeric) unmasked
  - Per AAP §0.7.2, production Java logs MUST mask all but the last 4 digits of SSN and government IDs; the captured `stdout.txt` fixture preserves COBOL unmasked behavior for parity assertion via a test-only sink
  - The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact (`carddemo-tests` is `test` scope only; the shaded `carddemo-app` jars never include it on the classpath)
  - Code reviewers MUST verify that no `stdout.txt` fixture is accidentally copied into a `main/resources` tree where it would be packaged into a production jar
- **Until capture is performed**: `CbCus01CGoldenTest` is `@Disabled("Awaiting COBOL CBCUS01C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the DOUBLE-DISPLAY pattern: each of 50 input records MUST emit twice (L96 then L78), producing 102 total lines including START and END banners.")`; the parity assertion is unreachable
- **Re-capture trigger**: if the ASCII fixture `custdata.txt` changes OR the COBOL source `app/cbl/CBCUS01C.cbl` changes, this fixture MUST be re-captured. Do NOT ad-hoc edit `stdout.txt`; always re-run the capture procedure

## Phase 13: Scenario Inventory (10 scenarios)

The captured fixture exercises the following 10 scenarios:

1. **Happy path full sequence** — start banner (L71) -> 50 iterations of (L96 record dump from `1000-CUSTFILE-GET-NEXT`, then L78 record dump from main loop) -> end banner (L85) = 102 lines total
2. **Sequential CUSTFILE ordering preserved** — records appear in stdout in the exact order they appear in `app/data/ASCII/custdata.txt` (sequential keys `000000001` through `000000050`); NO virtual threads used (per AAP §0.6.6)
3. **EOF detection** — final CUSTFILE read returns FILE STATUS `'10'`; `MOVE 16 TO APPL-RESULT` at L99 sets APPL-EOF condition; `MOVE 'Y' TO END-OF-FILE` at L108 terminates main loop; end banner emitted at L85
4. **DOUBLE-DISPLAY of every record** — 50 records times 2 displays each = 100 record dumps; the L96 emission (from inside `1000-CUSTFILE-GET-NEXT`) precedes the L78 emission (in main loop) for each record
5. **CUSTOMER-RECORD 500-byte format verbatim** — the L78 / L96 record dumps emit all 18 named fields plus 168-byte FILLER in CVCUS01Y declaration order: CUST-ID (9), CUST-FIRST-NAME (25), CUST-MIDDLE-NAME (25), CUST-LAST-NAME (25), CUST-ADDR-LINE-1 / 2 / 3 (50 each), CUST-ADDR-STATE-CD (2), CUST-ADDR-COUNTRY-CD (3), CUST-ADDR-ZIP (10), CUST-PHONE-NUM-1 / 2 (15 each), CUST-SSN (9), CUST-GOVT-ISSUED-ID (20), CUST-DOB-YYYY-MM-DD (10), CUST-EFT-ACCOUNT-ID (10), CUST-PRI-CARD-HOLDER-IND (1), CUST-FICO-CREDIT-SCORE (3), FILLER (168)
6. **L129 `'CUSTFILE'` (no space) literal preserved** — the open-failure path uses `'CUSTFILE'` without a space, distinct from L110 and L147 which use `'CUSTOMER FILE'` with a space; preserved verbatim per AAP §0.7.1
7. **Z-prefix paragraph naming preserved** — Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS lack 4-digit numeric prefix; the `@CobolParagraph` annotation values preserve the Z-prefix verbatim
8. **No RETURN-CODE setting on normal completion** — unlike CBTRN02C which sets RETURN-CODE = 4 on rejects, CBCUS01C leaves RETURN-CODE at default 0; only `Z-ABEND-PROGRAM` (via CEE3ABD at L158 with ABCODE = 999) affects the abend exit code
9. **Sequential execution mandate** — multiple test runs with the same input produce byte-identical stdout; NO non-deterministic reordering due to virtual threads or parallelism; the DOUBLE-DISPLAY pattern's L96-then-L78 ordering on each iteration is deterministic
10. **CUST-DOB-YYYY-MM-DD LocalDate translation** — CUST-DOB-YYYY-MM-DD (PIC X(10)) is parsed as `java.time.LocalDate` per AAP §0.6.4 inside `CustomerRecord`, but rendered as raw 10-byte PIC X bytes in the L78 / L96 record dump (the `encode()` side serializes `LocalDate` to `yyyy-MM-dd` ISO format which matches the COBOL PIC X(10) format)

## Contrast Matrix — CBCUS01C vs. sibling programs

| Aspect | CBCUS01C (this) | CBACT01C | CBACT02C | CBACT03C | CBTRN01C |
|---|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CB (batch) | CB (batch) | CB (batch) | CB (batch) |
| Role | Customer file sequential reader | Account file sequential reader | Card file sequential reader | Card xref sequential reader | Daily transaction validator |
| Source line count | 178 | (similar) | (similar) | (similar) | 491 |
| File ports | 1 (CUSTFILE only) | 1 (ACCTFILE only) | 1 (CARDFILE only) | 1 (CARDXREF only) | 6 (3 active + 3 unused) |
| Opened-but-unused files | 0 | 0 | 0 | 0 | 3 |
| Output file count | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) |
| JCL driver | `app/jcl/READCUST.jcl` (EXISTS) | `app/jcl/READACCT.jcl` (EXISTS) | `app/jcl/READCARD.jcl` (EXISTS) | `app/jcl/READXREF.jcl` (EXISTS) | NONE (verified absent) |
| Java FQCN subpackage | `customer/` | `account/` | `account/` | `account/` | `transaction/` |
| DOUBLE-DISPLAY pattern | YES (L78 + L96) | YES (sibling pattern) | YES (sibling pattern) | YES (sibling pattern) | NO (single L168 dump) |
| Reject codes | 0 | 0 | 0 | 0 | 0 |
| BigDecimal arithmetic | NO | NO | NO | NO | NO |
| Clock dependency | NO | NO | NO | NO | NO |
| Virtual threads allowed | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) |
| Per-record stdout lines | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 1-6 (variable diagnostic) |
| Test fixture pattern | input/README + expected/README + 1 placeholder data file | (same) | (same) | (same) | (same; but 5 input fixtures referenced) |
| Parity criticality | secondary | secondary | secondary | secondary | secondary |

CBCUS01C's single-port, no-arithmetic, no-clock, single-output, DOUBLE-DISPLAY nature places it firmly in the simple-sequential-reader family alongside CBACT01C, CBACT02C, and CBACT03C. The shared DOUBLE-DISPLAY pattern (DISPLAY inside the read paragraph followed by DISPLAY in the main loop after PERFORM returns) is the unifying behavior of this family. CBTRN01C differs by having SIX file ports, three of which are opened-but-unused, and uses a SINGLE-DISPLAY pattern per iteration instead of DOUBLE-DISPLAY.

## Verbatim Message Catalog Cross-Reference

The 8-message catalog enumerated in Phase 5 (counting the L168 / L172 pair as a single catalog entry because both branches emit an identical literal) is the COMPLETE catalog for CBCUS01C. Cross-references in `java/MIGRATION_NOTES.md` will record any message-string changes (NONE expected per AAP §0.7.1). The messages preserve the L129 `'CUSTFILE'` (no space) inconsistency relative to L110 and L147 `'CUSTOMER FILE'` (with space). The Java translation MUST produce byte-identical strings for each of the 8 catalogued messages.

The Java string-literal sites that emit these messages MUST be string constants (not built up by string concatenation, not formatted via `String.format`, not built via `MessageFormat`). The literal `STR.\\"FILE STATUS IS: NNNN\\"` (illustrative; actual Java syntax avoids the backslash escape) is appended with the four-character `IO-STATUS-04` formatted value via straightforward byte-level concatenation to match the COBOL DISPLAY's space-separated-operand convention exactly. The single space character between the closing apostrophe of the literal and `IO-STATUS-04` in the COBOL source produces a single space in the rendered output; the Java translation MUST match this single-space separator.

## Source Lineage

All source files in the following list are REFERENCE ONLY — these files remain UNCHANGED per AAP §0.1.1 / §0.2.2 / §0.7.1:

- `app/cbl/CBCUS01C.cbl` (178 lines) — Primary COBOL source; PROGRAM-ID at L23; AUTHOR `AWS` at L24; SELECT clause at L29-L33; FD declaration at L37-L40; PROCEDURE DIVISION at L70; main loop at L74-L81; 5 paragraphs total (1 driver paragraph plus 3 sequential read / open / close paragraphs plus 2 Z-utility paragraphs). The 21-line Apache 2.0 license header at L1-L21 and the version trailer at L177 are NOT propagated into Java source or test fixtures per AAP §0.2.2.
- `app/cpy/CVCUS01Y.cpy` (26 lines) — CUSTOMER-RECORD 500-byte layout: 18 named fields (CUST-ID, CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME, CUST-ADDR-LINE-1 / 2 / 3, CUST-ADDR-STATE-CD, CUST-ADDR-COUNTRY-CD, CUST-ADDR-ZIP, CUST-PHONE-NUM-1 / 2, CUST-SSN, CUST-GOVT-ISSUED-ID, CUST-DOB-YYYY-MM-DD, CUST-EFT-ACCOUNT-ID, CUST-PRI-CARD-HOLDER-IND, CUST-FICO-CREDIT-SCORE) plus 168-byte FILLER. Translated to `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CustomerRecord.java` per AAP §0.6.9.
- `app/data/ASCII/custdata.txt` (25,050 bytes; 50 records) — CUSTFILE input fixture (REFERENCE; read-only; sequential 500-byte records each followed by LF). Byte arithmetic verification: 50 records times 500 bytes plus 50 LFs = 25,000 + 50 = 25,050 bytes total, matching `wc -c` exactly.
- `app/jcl/READCUST.jcl` (16 lines) — JCL driver (REFERENCE only; not invoked by test). Job card at L1-L2; comment block at L3-L5; STEP05 `EXEC PGM=CBCUS01C` at L6; STEPLIB DD at L7-L8 pointing to `AWS.M2.CARDDEMO.LOADLIB`; CUSTFILE DD at L9-L10 pointing to `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`; SYSOUT / SYSPRINT at L11-L12. Translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCustomerDumpApp.java` per AAP §0.4.1 — the Java main wires `FileCustomerRepository` from a path resolved via `application.properties` key `carddemo.file.custdata.path`.

## Cross-References

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbCus01CGoldenTest.java` — JUnit 5 test class consuming this fixture (`@Disabled` until capture committed)
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class with byte-for-byte parity assertion
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/customer/CbCus01C.java` — Class under test
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CustomerRecord.java` — CUSTOMER-RECORD from CVCUS01Y (500 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/CustomerRepository.java` — Repository port interface
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileCustomerRepository.java` — File-backed implementation of `CustomerRepository`
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCustomerDumpApp.java` — JCL-derived main class (translated from `app/jcl/READCUST.jcl`)
- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbtrn01c/expected/README.md` (template predecessor; validation-only program with SIX file ports versus CBCUS01C's ONE port; NO JCL driver versus READCUST.jcl)
- Sibling fixture (DOUBLE-DISPLAY family): `java/carddemo-tests/src/test/resources/golden/cbact01c/expected/README.md` (when present; same DOUBLE-DISPLAY pattern for ACCTFILE)

## Authority References

Every AAP section cited in this README, in order of first appearance. Each citation is binding on the Java translation and on the captured fixture; any future modification to the translation or fixture that violates one of these sections is a regression that the byte-for-byte parity assertion is designed to catch.

- AAP §0.1.1 (refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope: golden-record fixtures under `java/carddemo-tests/src/test/resources/golden/**/*`)
- AAP §0.2.2 (`app/` IMMUTABLE)
- AAP §0.3.1 (harness directory convention `<program>/input/` plus `<program>/expected/`)
- AAP §0.3.2 (records, sealed types, ports)
- AAP §0.3.4 (JVM flags `-XX:+UseCompactObjectHeaders` and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational`)
- AAP §0.3.6 (hexagonal architecture; no Spring)
- AAP §0.4.1 (CBCUS01C -> CbCus01C in `com.blitzy.carddemo.application.customer`; ASCII fixtures REFERENCE only, NEVER copied)
- AAP §0.6.1 (`Decimals` utility — NOT applicable to CBCUS01C because the program performs no arithmetic)
- AAP §0.6.2 (sealed-type pattern)
- AAP §0.6.4 (`java.time` only)
- AAP §0.6.5 (`java.nio.file`; EBCDIC IBM-1047 default)
- AAP §0.6.6 (sequential execution preserved; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.8 (program-by-program mapping checklist)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` until captures committed)
- AAP §0.7.1 (Minimal Change Clause; preserve DOUBLE-DISPLAY, L129 spacing, Z-prefix paragraph naming, identical L168 / L172 literals)
- AAP §0.7.2 (no PAN in production logs; applies here to CUST-SSN and CUST-GOVT-ISSUED-ID via separate production sink)
- AAP §0.7.4 (forbidden features: no preview JEPs, no `default` branches that hide cases)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline `[<path>:Lnnn]`)

## DO NOT Modify the Fixture Data Without Re-Capture

The `stdout.txt` file (once captured) is COUPLED with the read-only ASCII fixture `app/data/ASCII/custdata.txt` and the Java translation's faithful sequential execution with the DOUBLE-DISPLAY pattern. The coupling is bidirectional: a single-byte change in any of the 50 input customer records propagates to two changed lines in the expected stdout (the L96 emission and the L78 emission for that record), and the byte-for-byte parity assertion will report the diff immediately. The following maintenance rules apply:

- If `custdata.txt` content changes OR the COBOL source `app/cbl/CBCUS01C.cbl` changes, this fixture MUST be re-captured per `java/MIGRATION_NOTES.md` §1.6
- Ad-hoc edits to `stdout.txt` without re-capture WILL break byte-for-byte parity and indicate a coverage gap, not a successful test
- Do NOT `fix` the L129 `'CUSTFILE'` (no space) inconsistency relative to L110 / L147 `'CUSTOMER FILE'` (with space) — PRESERVED per AAP §0.7.1
- Do NOT `fix` the Z-prefix paragraph naming (Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS) — PRESERVED per AAP §0.7.1 in `@CobolParagraph` annotation values
- Do NOT mask PII (CUST-SSN, CUST-GOVT-ISSUED-ID) in captured `stdout.txt` — the test driver uses an unmasked sink while production logs apply masking via a separate sink per AAP §0.7.2
- Do NOT introduce virtual threads — sequential execution mandated per AAP §0.6.6 because the DOUBLE-DISPLAY pattern's L96-then-L78 ordering on each iteration is observable
- Do NOT eliminate the DOUBLE-DISPLAY pattern — the L96 and L78 emissions MUST both appear per successful read iteration to honor byte-for-byte parity with the COBOL baseline
- Do NOT replace the 168-byte FILLER trailing-space output with a trimmed alternative; the FILLER bytes are part of the 500-byte record contract and the COBOL DISPLAY emits them verbatim
- Do NOT introduce a `BigDecimal` arithmetic site in the translation; CBCUS01C performs no math and the `Decimals` utility (per AAP §0.6.1) is not in scope for this program
- Do NOT introduce a `Clock` injection point; CBCUS01C does not invoke `FUNCTION CURRENT-DATE` and no timestamp-derived field appears in its output

If any of the preserved behaviors must change for a legitimate business reason in a future refactor, document the change in `java/MIGRATION_NOTES.md` (a new section) and re-capture this fixture in the same commit so the audit trail is preserved.
