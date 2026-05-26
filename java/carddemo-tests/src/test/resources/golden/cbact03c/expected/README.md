# Golden-Record Contract — CBACT03C (Card Cross-Reference File Sequential Reader; JCL Driver: READXREF.jcl)

This document is the authoritative byte-for-byte contract for the Java translation of COBOL program CBACT03C, a read-only sequential display utility that opens a single VSAM KSDS card cross-reference file, walks every record in key order, and emits each successful record to stdout TWICE per iteration (once at `[app/cbl/CBACT03C.cbl:L96]` inside `1000-XREFFILE-GET-NEXT`, once at `[app/cbl/CBACT03C.cbl:L78]` in the main loop after `PERFORM` returns) — the marquee **DOUBLE-DISPLAY pattern** that unifies the CBACT01C / CBACT02C / CBACT03C / CBCUS01C family of simple sequential readers.

The companion JUnit 5 test class `com.blitzy.carddemo.tests.golden.CbAct03CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and consumes the single captured fixture file documented below.

The test class is `@Disabled("Awaiting COBOL CBACT03C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the DOUBLE-DISPLAY pattern: each of 50 input records MUST emit twice (L96 then L78), producing 102 total lines including START and END banners.")` per AAP §0.6.11 until `stdout.txt` is present in this folder with content captured from a COBOL reference run and committed.

Every claim in this README about COBOL behavior cites a specific line range in the form `[<path>:Lnnn]` or `[<path>:Lnnn-Lmmm]` per AAP §0.8.1 citation discipline.

Unlike CBTRN01C (which has no JCL driver), CBACT03C has a verified existing JCL driver at `app/jcl/READXREF.jcl` that invokes `EXEC PGM=CBACT03C` at STEP05 with the XREFFILE DD pointing to `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`. The Java equivalent main class lives at `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardXrefDumpApp.java` per AAP §0.4.1; the golden-record test class, however, bypasses the main class and invokes `CbAct03C` directly via constructor-injected `CardXrefRepository` port for harness simplicity. CBACT03C is the SMALLEST member of the DOUBLE-DISPLAY family at 50 bytes per record (versus CBACT01C's 300 bytes, CBACT02C's 150 bytes, and CBCUS01C's 500 bytes).

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
- AAP §0.4.1 — CBACT03C -> CbAct03C in `com.blitzy.carddemo.application.account` (NOTE: `account/` subpackage alongside CBACT01C / CBACT02C, distinct from CBCUS01C's `customer/` subpackage); ASCII fixtures REFERENCE only, NEVER copied into this folder
- AAP §0.6.1 — `Decimals` utility centralizes monetary arithmetic with `MathContext.DECIMAL128`; NOT applicable to CBACT03C because the program performs no arithmetic
- AAP §0.6.2 — Sealed-type pattern for closed value sets and lookup outcomes (used here for APPL-RESULT 88-level conditions, FILE STATUS codes, and the TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES)
- AAP §0.6.4 — `java.time` only for date and time; never `java.util.Date` or `Calendar` — NOTE: CBACT03C has NO date fields, so no `LocalDate` / `LocalDateTime` / `LocalTime` usage anywhere in the translation
- AAP §0.6.5 — `java.nio.file` for all file I/O; EBCDIC IBM-1047 default codepage with per-file override
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely in new code; sequential execution preserved for ordered output (NO virtual threads in CBACT03C path — DOUBLE-DISPLAY ordering must be deterministic)
- AAP §0.6.8 — Program-by-program COBOL-to-Java mapping checklist confirms CBACT03C -> CbAct03C
- AAP §0.6.11 — Golden-record harness is the PR gate; `@Disabled` until COBOL captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve the DOUBLE-DISPLAY pattern, the CONSISTENT `'XREFFILE'` (single word) literal across all three error messages (L110, L129, L147 — UNLIKE CBCUS01C's L129 spacing inconsistency), the NUMERIC 4-digit paragraph prefix naming for 9999-ABEND-PROGRAM and 9910-DISPLAY-IO-STATUS (UNLIKE CBCUS01C's Z-prefix utility paragraphs), and the identical literal in both branches of 9910-DISPLAY-IO-STATUS at L168 / L172
- AAP §0.7.2 — No PAN in production logs (mask all but last 4 digits); for CBACT03C the sensitive PII field is XREF-CARD-NUM (PIC X(16), a 16-byte card-number PAN); test driver uses unmasked sink for parity assertion, production sinks apply masking via a separate path
- AAP §0.7.4 — No preview features (JEP 502 Stable Values, JEP 505 Structured Concurrency, JEP 507 Primitive Patterns, JEP 512 Compact Source Files in production code); no `default` branches that hide cases in pattern-matching switch
- AAP §0.7.5 — Capture procedure for golden-record fixtures is documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]` for every claim about COBOL behavior

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBACT03C` | `[app/cbl/CBACT03C.cbl:L23]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBACT03C.cbl:L24]` |
| Source line count | 178 | `app/cbl/CBACT03C.cbl` (per `wc -l`) |
| JCL driver | `app/jcl/READXREF.jcl` (EXISTS — verified) | `grep -l "CBACT03C" app/jcl/*.jcl` returns `app/jcl/READXREF.jcl` |
| FILE-CONTROL SELECTs | 1 file (XREFFILE) | `[app/cbl/CBACT03C.cbl:L28-L33]` |
| FD declaration | 50-byte XREFFILE-FILE (FD-XREF-CARD-NUM PIC X(16) + FD-XREF-DATA PIC X(34)) | `[app/cbl/CBACT03C.cbl:L37-L40]` |
| Files actively read | 1 (XREFFILE) | Main loop at `[app/cbl/CBACT03C.cbl:L74-L81]` |
| Files opened-but-unused | 0 | Single-port profile; CONTRAST with CBTRN01C's 3 opened-but-unused |
| Java FQCN under test | `com.blitzy.carddemo.application.account.CbAct03C` (NOTE: `account/` subpackage, alongside sibling CBACT01C / CBACT02C — distinct from CBCUS01C which lives in `customer/`) | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbAct03CGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |

The `@CobolProgram("CBACT03C")` Javadoc-style annotation MUST cite the original PROGRAM-ID, the source path `app/cbl/CBACT03C.cbl`, and the translation date per AAP §0.7.1. The annotation is the only durable traceability link between the Java class and its COBOL origin once the source-tree drift over time makes side-by-side reading harder.

Each translated COBOL paragraph MUST carry a `@CobolParagraph("<NUMERIC-PREFIX-NAME>")` Javadoc annotation citing the original paragraph name verbatim. For CBACT03C the five required annotations are `@CobolParagraph("1000-XREFFILE-GET-NEXT")`, `@CobolParagraph("0000-XREFFILE-OPEN")`, `@CobolParagraph("9000-XREFFILE-CLOSE")`, `@CobolParagraph("9999-ABEND-PROGRAM")`, and `@CobolParagraph("9910-DISPLAY-IO-STATUS")` — note that ALL five paragraphs use NUMERIC 4-DIGIT prefixes, distinct from CBCUS01C's Z-prefix utility paragraphs (Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS). The numeric prefixes 9999- and 9910- are PRESERVED per AAP §0.7.1 even though they would benefit from a more semantic Java method name; the original COBOL paragraph names are the canonical traceability anchor.

The `@Disabled` mandate: the test class remains `@Disabled` until `stdout.txt` is present with captured (not placeholder) content per AAP §0.6.11. Removing the `@Disabled` annotation prematurely will cause the test to fail because the expected output file does not exist in this initial commit.

## Phase 2: Files in This Folder

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBACT03C golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbAct03CGoldenTest` once `stdout.txt` is captured per AAP §0.7.5.

### `stdout.txt` — Captured DISPLAY Output (CAPTURE PLACEHOLDER)

The single captured output of a reference COBOL run of CBACT03C against `app/data/ASCII/cardxref.txt`.

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in the platform's newline (LF on captured run from Linux).
- **First line**: L71 banner `` `'START OF EXECUTION OF PROGRAM CBACT03C'` ``
- **Last line**: L85 banner `` `'END OF EXECUTION OF PROGRAM CBACT03C'` ``
- **Per-record body** (executes once per XREFFILE record): TWO lines per record:
  - L96: `DISPLAY CARD-XREF-RECORD` (inside `1000-XREFFILE-GET-NEXT` on `XREFFILE-STATUS = '00'` success path — FIRST half of DOUBLE-DISPLAY pattern; 50-byte fixed-width record emitted as one line followed by LF)
  - L78: `DISPLAY CARD-XREF-RECORD` (in main loop after `PERFORM 1000-XREFFILE-GET-NEXT` returns with `END-OF-FILE = 'N'` — SECOND half of DOUBLE-DISPLAY pattern; same 50-byte record emitted again)
- **Total expected line count**: `2 + (2N)` where N is the number of XREFFILE records. For the current `app/data/ASCII/cardxref.txt` fixture (N = 50, verified via `awk 'END{print NR}'`), expected = `2 + 100 = 102` lines.
- **Byte-for-byte parity rule**: Java captured stdout MUST equal the captured baseline BYTE-for-BYTE. Trailing whitespace and padding direction preserved exactly. Line breaks preserved exactly (LF).
- **PAN exposure dichotomy**: XREF-CARD-NUM PIC X(16) is a 16-byte card-number PAN per AAP §0.7.2 — sensitive data that production logs MUST mask. The test-only sink writes UNMASKED bytes for parity assertion per AAP §0.7.1 (preserve COBOL behavior exactly); production logger sinks apply card-PAN masking (all but the last 4 digits) per AAP §0.7.2 via a separate sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- **No deterministic Clock required**: CBACT03C does NOT invoke `FUNCTION CURRENT-DATE`; captured output is naturally deterministic given fixed input. No `java.time.Clock` injection point exists in `CbAct03C`.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT exist yet; `CbAct03CGoldenTest` is `@Disabled` until captured content is committed via the procedure in `java/MIGRATION_NOTES.md` §1.6.

## Phase 3: Conceptual Input Universe

The single ASCII input that produces the captured baseline is:

- XREFFILE: `app/data/ASCII/cardxref.txt` (1,850 bytes; 50 sequential 36-byte payload records each followed by LF) — PRIMARY input

Unlike CBTRN01C's 6-file ensemble (DALYTRAN, CUSTOMER, XREF, CARD, ACCOUNT, TRANSACT) or CBTRN02C's 4-file ensemble (DALYTRAN, XREF, ACCOUNT, TCATBAL plus reject and transact outputs), CBACT03C reads ONE file only. There are NO auxiliary inputs, NO lookup files, NO update files, NO reject outputs.

The fixture is NEVER copied into this folder per AAP §0.4.1 — it is referenced via classpath relative path from `app/data/ASCII/` to honor the AAP §0.2.2 immutability mandate. The Java test class resolves the classpath via `resolveAppDataPath("cardxref.txt")` helper from the `GoldenRecordTest` base class.

CBACT03C performs NO writes, NO rewrites, and NO updates; all inputs are read-only and the original `app/data/ASCII/cardxref.txt` remains BYTE-IDENTICAL after the test run per AAP §0.2.2.

**ASCII fixture format gap and FILLER reconstitution**: the ASCII fixture on disk is 1,850 bytes total = 50 records times 37 bytes each, where each record line carries a 36-byte payload (XREF-CARD-NUM 16 + XREF-CUST-ID 9 + XREF-ACCT-ID 11 = 36 digit bytes) followed by a single LF terminator. The 14-byte FILLER PIC X(14) declared in `app/cpy/CVACT03Y.cpy:L8` is OMITTED from the on-disk ASCII fixture. The Java `FileCardXrefRepository` adapter MUST account for this format gap: when reading the ASCII fixture, only 36 bytes are present per record on disk; the 14-byte FILLER positions are reconstituted by padding with spaces (ASCII 0x20) so that `CardXrefRecord.parse(byte[])` always receives a full 50-byte buffer. The L78 / L96 DISPLAY output emits all 50 bytes per record (36 digits + 14 spaces); this is consistent with the COBOL behavior where the FD declaration at `[app/cbl/CBACT03C.cbl:L37-L40]` reserves a 50-byte FD-XREFFILE-REC (FD-XREF-CARD-NUM PIC X(16) plus FD-XREF-DATA PIC X(34) — the 34 bytes comprising the 9-digit cust ID, the 11-digit account ID, and the 14-byte FILLER).

The fixture contains 50 records with three named payload fields each, in declaration order from `app/cpy/CVACT03Y.cpy`:

- bytes 0-15 (16): XREF-CARD-NUM PIC X(16) — sensitive 16-digit card-number PAN
- bytes 16-24 (9): XREF-CUST-ID PIC 9(09) — 9-digit customer ID, parsed as `long` in `CardXrefRecord`
- bytes 25-35 (11): XREF-ACCT-ID PIC 9(11) — 11-digit account ID, parsed as `long` in `CardXrefRecord`
- bytes 36-49 (14): FILLER PIC X(14) — preserved as `byte[14]` component on the Java `record` to maintain round-trip parity; reconstituted with spaces when reading the ASCII fixture

The Java `CardXrefRecord` class therefore has 4 components: 3 named fields plus the FILLER byte array. The `parse(byte[])` factory consumes the 50-byte buffer and binds each field by slicing the appropriate offset range; the `encode()` instance method re-emits the 50-byte buffer by concatenating field encodings in declaration order. The round-trip invariant `parse(buf).encode() == buf` holds for every valid 50-byte buffer per AAP §0.3.2.

## Phase 4: Sequential Read Flow (5 paragraphs)

The PROCEDURE DIVISION at `[app/cbl/CBACT03C.cbl:L70]` is the driver. Its full flow:

- Start banner DISPLAY at `[app/cbl/CBACT03C.cbl:L71]` — unconditional first stdout line `'START OF EXECUTION OF PROGRAM CBACT03C'`
- `PERFORM 0000-XREFFILE-OPEN.` at `[app/cbl/CBACT03C.cbl:L72]`
- Main loop at `[app/cbl/CBACT03C.cbl:L74-L81]` — `PERFORM UNTIL END-OF-FILE = 'Y'`
- Per-iteration body:
  - L75-L80 nested IF / PERFORM / IF / DISPLAY block
  - L76: `PERFORM 1000-XREFFILE-GET-NEXT`
  - L78: `DISPLAY CARD-XREF-RECORD` — main-loop record dump (SECOND half of DOUBLE-DISPLAY pattern) emitted after the PERFORM returns and after the inner IF confirms `END-OF-FILE = 'N'`
- `PERFORM 9000-XREFFILE-CLOSE.` at `[app/cbl/CBACT03C.cbl:L83]`
- End banner DISPLAY at `[app/cbl/CBACT03C.cbl:L85]` — unconditional last stdout line `'END OF EXECUTION OF PROGRAM CBACT03C'`
- `GOBACK.` at `[app/cbl/CBACT03C.cbl:L87]`

Paragraph `1000-XREFFILE-GET-NEXT` at `[app/cbl/CBACT03C.cbl:L92]`:

- L93: `READ XREFFILE-FILE INTO CARD-XREF-RECORD.`
- L94: `IF XREFFILE-STATUS = '00'`
- L95: `MOVE 0 TO APPL-RESULT`
- L96: `DISPLAY CARD-XREF-RECORD` — FIRST half of DOUBLE-DISPLAY pattern
- L98: `IF XREFFILE-STATUS = '10' MOVE 16 TO APPL-RESULT`
- L101: other status -> `MOVE 12 TO APPL-RESULT`
- L104-L114: APPL-AOK / APPL-EOF / error decision tree
- L107-L108: APPL-EOF -> `MOVE 'Y' TO END-OF-FILE`
- L110: non-EOF error path emits `'ERROR READING XREFFILE'`
- L111-L113: `MOVE XREFFILE-STATUS TO IO-STATUS` -> `PERFORM 9910-DISPLAY-IO-STATUS` -> `PERFORM 9999-ABEND-PROGRAM`
- L116: `EXIT`

Paragraph `0000-XREFFILE-OPEN` at `[app/cbl/CBACT03C.cbl:L118]`:

- L119: `MOVE 8 TO APPL-RESULT`
- L120: `OPEN INPUT XREFFILE-FILE`
- L121: `IF XREFFILE-STATUS = '00'` -> `MOVE 0 TO APPL-RESULT`
- L123: ELSE -> `MOVE 12 TO APPL-RESULT`
- L126-L133: success / failure decision tree
- L129: `DISPLAY 'ERROR OPENING XREFFILE'` — NOTE: literal uses `'XREFFILE'` (single word) — CONSISTENT with L110 and L147, UNLIKE CBCUS01C where L129 is `'CUSTFILE'` (no space) versus L110 / L147 `'CUSTOMER FILE'` (with space) inconsistency
- L130-L132: MOVE / PERFORM error chain
- L134: `EXIT`

Paragraph `9000-XREFFILE-CLOSE` at `[app/cbl/CBACT03C.cbl:L136]`:

- L137: `ADD 8 TO ZERO GIVING APPL-RESULT.`
- L138: `CLOSE XREFFILE-FILE`
- L139: `IF XREFFILE-STATUS = '00'`
- L140: `SUBTRACT APPL-RESULT FROM APPL-RESULT` — sets APPL-RESULT to 0 (idiomatic COBOL zero-set)
- L142: ELSE -> `ADD 12 TO ZERO GIVING APPL-RESULT`
- L144-L151: success / failure decision tree
- L147: `DISPLAY 'ERROR CLOSING XREFFILE'`
- L148-L150: MOVE / PERFORM error chain
- L152: `EXIT`

Paragraph `9999-ABEND-PROGRAM` at `[app/cbl/CBACT03C.cbl:L154]` (NUMERIC 4-DIGIT PREFIX — distinct from CBCUS01C's Z-ABEND-PROGRAM):

- L155: `DISPLAY 'ABENDING PROGRAM'`
- L156: `MOVE 0 TO TIMING`
- L157: `MOVE 999 TO ABCODE`
- L158: `CALL 'CEE3ABD'.`

Paragraph `9910-DISPLAY-IO-STATUS` at `[app/cbl/CBACT03C.cbl:L161]` (NUMERIC 4-DIGIT PREFIX — distinct from CBCUS01C's Z-DISPLAY-IO-STATUS):

- L162-L168: IF branch (`IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'`): extract `IO-STAT1` into `IO-STATUS-04(1:1)`, zero `TWO-BYTES-BINARY`, MOVE `IO-STAT2` to `TWO-BYTES-RIGHT`, MOVE `TWO-BYTES-BINARY` to `IO-STATUS-0403`, then `DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04`
- L169-L172: ELSE branch (status numeric and not starting with `'9'`): MOVE `'0000'` to `IO-STATUS-04`, MOVE `IO-STATUS` into `IO-STATUS-04(3:2)`, then `DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04` — IDENTICAL literal to L168
- L174: `EXIT`

NO writes, NO rewrites, NO updates. The Java translation MUST faithfully open / close XREFFILE-FILE and produce DOUBLE-DISPLAY for each successful read. The five paragraphs map to five private methods on `CbAct03C`:

- `cobolMain()` — the PROCEDURE DIVISION entry point (L70-L87): emit start banner, open the file, run the read-loop, close the file, emit end banner
- `xreffileGetNext()` — the `1000-XREFFILE-GET-NEXT` body (L92-L116)
- `xreffileOpen()` — the `0000-XREFFILE-OPEN` body (L118-L134)
- `xreffileClose()` — the `9000-XREFFILE-CLOSE` body (L136-L152)
- `abendProgram()` — the `9999-ABEND-PROGRAM` body (L154-L158): throws an `AbendException` carrying `abcode = 999`
- `displayIoStatus()` — the `9910-DISPLAY-IO-STATUS` body (L161-L174)

The split is one-to-one; the names follow `lowerCamelCase` per Java conventions but each carries the original `@CobolParagraph` annotation preserving the verbatim COBOL paragraph name (including the NUMERIC 4-digit prefix for 9999- and 9910-).


## Phase 5: Verbatim DISPLAY Message Catalog (8 entries, verified)

Every distinct DISPLAY content in CBACT03C, verified by direct reading of `app/cbl/CBACT03C.cbl`. The L168 / L172 pair counts as one catalog entry because both branches emit an identical literal. The catalog comprises 9 rows below — 8 distinct messages plus the record-dump entry which is logically one DISPLAY action emitted twice per iteration (counted as two rows for the L78 versus L96 site distinction).

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBACT03C'` `` | L71 | Unconditional start banner |
| 2 | `DISPLAY CARD-XREF-RECORD` (full 50-byte record) | L78 | Main-loop record dump (after `PERFORM 1000-XREFFILE-GET-NEXT` returns with `END-OF-FILE = 'N'`) — SECOND half of DOUBLE-DISPLAY pattern |
| 3 | `` `'END OF EXECUTION OF PROGRAM CBACT03C'` `` | L85 | Unconditional end banner |
| 4 | `DISPLAY CARD-XREF-RECORD` (full 50-byte record) | L96 | Inside `1000-XREFFILE-GET-NEXT` on `XREFFILE-STATUS = '00'` success path — FIRST half of DOUBLE-DISPLAY pattern |
| 5 | `` `'ERROR READING XREFFILE'` `` | L110 | `1000-XREFFILE-GET-NEXT` non-EOF error path |
| 6 | `` `'ERROR OPENING XREFFILE'` `` | L129 | `0000-XREFFILE-OPEN` failure (NOTE: uses `'XREFFILE'` single word — CONSISTENT with L110 and L147 — UNLIKE CBCUS01C's L129 `'CUSTFILE'` no-space versus L110 / L147 `'CUSTOMER FILE'` with-space inconsistency) |
| 7 | `` `'ERROR CLOSING XREFFILE'` `` | L147 | `9000-XREFFILE-CLOSE` failure path |
| 8 | `` `'ABENDING PROGRAM'` `` | L155 | `9999-ABEND-PROGRAM` before `CALL 'CEE3ABD'` |
| 9 | `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 | L168, L172 | `9910-DISPLAY-IO-STATUS` — both IF / ELSE branches emit identical literal |

ALL messages preserve EXACT bytes including embedded SPACES, COLON placement (one colon, one trailing space, then `NNNN`). UNLIKE CBCUS01C, CBACT03C uses the CONSISTENT `'XREFFILE'` (single word) literal across all three error messages (L110, L129, L147) — there is NO L129 versus L110 / L147 spacing inconsistency to preserve. The Java translation MUST produce byte-identical strings for each catalogued message.

Audit trail observations on the message catalog:

- Banner-class messages (entries 1 and 3) are unconditional — they appear in every successful run regardless of input content or input record count
- Record-dump entries (2 and 4) appear `N` times each in a successful run (`N` = number of input records); they comprise the bulk of the stdout volume (100 of 102 lines for the 50-record fixture, approximately 98% of total stdout)
- Error-class messages (entries 5, 6, 7) appear ZERO times in a successful run; they only appear when XREFFILE-STATUS yields an unexpected code and the abend chain is triggered
- Abend-class message (entry 8) appears ZERO times in a successful run; it precedes the `CALL 'CEE3ABD'` invocation that ends the process abnormally
- IO-status diagnostic (entry 9) appears ZERO times in a successful run; it is invoked by the error path of the open / read / close paragraphs only when their respective FILE STATUS code is non-zero (and non-`'10'` for the read case)

The deterministic 50-record fixture therefore exercises exactly entries 1, 2, 3, and 4 — entries 5-9 are documented for completeness but are not asserted by the captured baseline. Coverage of entries 5-9 belongs to negative-path unit tests not in this golden-record fixture; those tests inject synthetic FILE STATUS codes via a mocked `CardXrefRepository` and assert each error-message branch in isolation. The mocked variants are out of scope for this README and live under `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/application/account/` per the standard module layout.

## Phase 6: Read-Outcome Branches (NO Reject Codes)

CBACT03C emits NO reject codes. Unlike CBTRN02C, which classifies failed transactions with 5 reject codes (100 / 101 / 102 / 103 / 109) and writes a reject record per failure, there is no reject file, no reject record layout, and no validation trailer record in CBACT03C. CBACT03C is a pure read-and-display utility.

The read outcomes after each `READ XREFFILE-FILE` at `[app/cbl/CBACT03C.cbl:L93]`:

- `XREFFILE-STATUS = '00'` -> success path -> `MOVE 0 TO APPL-RESULT` at L95 -> `DISPLAY CARD-XREF-RECORD` at L96 (FIRST half of DOUBLE-DISPLAY pattern; SECOND half at L78 follows after PERFORM returns)
- `XREFFILE-STATUS = '10'` -> EOF path -> `MOVE 16 TO APPL-RESULT` at L99 -> APPL-EOF condition true at L107 -> `MOVE 'Y' TO END-OF-FILE` at L108 -> main loop terminates
- Any other status -> error path -> `MOVE 12 TO APPL-RESULT` at L101 -> `DISPLAY 'ERROR READING XREFFILE'` at L110 -> `MOVE XREFFILE-STATUS TO IO-STATUS` at L111 -> `PERFORM 9910-DISPLAY-IO-STATUS` at L112 -> `PERFORM 9999-ABEND-PROGRAM` at L113 (which DISPLAYs `'ABENDING PROGRAM'` at L155, sets ABCODE = 999 at L157, and `CALL 'CEE3ABD'` at L158)

APPL-RESULT 88-level conditions at `[app/cbl/CBACT03C.cbl:L61-L63]`: APPL-AOK = 0, APPL-EOF = 16. Other values trigger abend via `9999-ABEND-PROGRAM`. The same APPL-RESULT discriminator pattern is used by `0000-XREFFILE-OPEN` at L121-L133 and `9000-XREFFILE-CLOSE` at L139-L151. For the deterministic fixture used in this golden-record test, only the success path (`XREFFILE-STATUS = '00'`) and the final EOF path (`XREFFILE-STATUS = '10'` after record 50) are exercised; the error path is unreachable given an intact `app/data/ASCII/cardxref.txt`.

The COBOL FILE STATUS code values that CBACT03C distinguishes (a strict subset of the COBOL standard FILE STATUS keys):

- `'00'`: successful operation (read returned a record; open succeeded; close succeeded)
- `'10'`: end-of-file (read past the last record returns this code; the operation does NOT return a record on this status)
- Any other code (e.g., `'23'` record-not-found, `'30'` permanent error, `'35'` file-not-found at open, `'9x'` implementation-defined errors): treated as a fatal error via the L101 / L110 / L113 abend chain

The sealed `FileStatus` hierarchy in `com.blitzy.carddemo.domain` per AAP §0.6.2 has permits covering these COBOL standard codes. Pattern-matching switch sites on the hierarchy MUST be exhaustive (no `default` branch per AAP §0.7.4).

RETURN-CODE: NOT set explicitly on success or failure. On the `9999-ABEND-PROGRAM` path, ABCODE = 999 (set at L157) is passed to `CEE3ABD` via the `CALL` at L158; the OS-level return code is platform-dependent. On normal completion (the only case exercised by the deterministic fixture), RETURN-CODE remains at its default value (0). The Java translation surfaces these two distinct exit modes via the main-class `System.exit(int)` value: 0 on normal completion, 999 on abend (mirroring ABCODE).

## Phase 7: Single-Output stdout.txt Byte Contract

The byte contract for the single output:

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in LF (Unix-style newline on captured Linux run; on z/OS the captured run uses EBCDIC newline conventions but the captured fixture is in ASCII after transcoding per AAP §0.6.5).
- **First line**: L71 banner `` `'START OF EXECUTION OF PROGRAM CBACT03C'` ``
- **Last line**: L85 banner `` `'END OF EXECUTION OF PROGRAM CBACT03C'` ``
- **Per XREFFILE record body** (executes once per input record):
  - 1 line: L96 unconditional `DISPLAY CARD-XREF-RECORD` (50-byte record verbatim including reconstituted 14-byte FILLER spaces) — emitted INSIDE `1000-XREFFILE-GET-NEXT` after the read succeeds with status `'00'`
  - 1 line: L78 conditional `DISPLAY CARD-XREF-RECORD` (same 50-byte record) — emitted in main loop AFTER PERFORM returns with `END-OF-FILE = 'N'`
- **Total expected line count**: `2 + (2 * N)` where N is the number of XREFFILE records in `app/data/ASCII/cardxref.txt`. Currently N = 50 records (verified via `awk 'END{print NR}' app/data/ASCII/cardxref.txt`). For the present fixture: `2 + (2 * 50) = 102` lines.
- **Total expected byte count** (approximate; subject to compiler trailing-space conventions): approximately `40 + (2 * 50 * 50) + 40 + 102 LFs` ~= roughly 5,182 bytes. The exact value is captured in `stdout.txt` and asserted byte-for-byte by the test; this README does NOT pre-commit a byte count because compiler trailing-space conventions vary slightly between GnuCOBOL and z/OS COBOL.
- **Byte-for-byte parity rule**: `CbAct03C.run(...)` MUST produce, for the deterministic input set, a captured stdout byte sequence equal to this captured baseline BYTE-for-BYTE.
- **Trailing whitespace**: COBOL DISPLAY of a fixed-width record emits trailing spaces from the 14-byte FILLER and any non-fully-populated `PIC X(...)` field; the captured `stdout.txt` is the reference. The Java translation MUST match byte-by-byte. The Java sink implementation MUST NOT call `String.trim()` or `String.stripTrailing()` on the record bytes before writing.
- **Line termination**: every line ends with a single LF byte (`0x0A`); there are no CR bytes (`0x0D`); the final line (the END banner) ends with LF. The file therefore ends with `\n` per POSIX convention. The Java sink uses `OutputStream.write(0x0A)` after each DISPLAY equivalent rather than `PrintStream.println` (which would use the platform line separator).
- **PAN exposure**: L78's and L96's full-record dumps include XREF-CARD-NUM (16-byte alphanumeric card-number PAN) unmasked. The test-only sink writes the unmasked bytes for parity assertion; production logger sinks apply card-PAN masking (all but the last 4 digits) per AAP §0.7.2 via a separate sink.
- **No deterministic Clock needed**: CBACT03C does NOT invoke `FUNCTION CURRENT-DATE`; no timestamp generation occurs; the captured output is naturally deterministic given fixed input.

## Phase 8: NO Opened-But-Unused Files

CBACT03C opens EXACTLY ONE file (XREFFILE-FILE) and reads it sequentially. This is in CONTRAST to CBTRN01C, which opens 6 files but reads only 3 (3 opened-but-unused: CUSTOMER, CARD, TRANSACT). The Java translation has a SINGLE `CardXrefRepository` port — no auxiliary ports — making `CbAct03C` the smallest-port-count member of the application layer.

| File | Open | Close | Active | Status |
|---|---|---|---|---|
| XREFFILE-FILE | L120 (`0000-XREFFILE-OPEN`) | L138 (`9000-XREFFILE-CLOSE`) | YES (sequential READ at L93) | Active — sole port |

No `opened-but-unused` boilerplate exists in CBACT03C. The single XREFFILE-FILE port is both opened and actively read. The Java translation's `CardXrefRepository` port is therefore the sole constructor-injected dependency of `CbAct03C`.


## Phase 9: Preserved Behaviors and Idiosyncrasies (DO NOT FIX)

The following 11 COBOL idiosyncrasies of CBACT03C are PRESERVED VERBATIM per AAP §0.7.1 Minimal Change Clause. NONE are bugs to be `fixed`. Each entry below names the construct, cites the source line, and explains why the Java translation must preserve it.

1. **DOUBLE-DISPLAY CARD-XREF-RECORD pattern** at L78 and L96 — the marquee behavior. Two DISPLAY calls emit the SAME successful record TWICE per iteration. Sibling family: CBACT01C, CBACT02C, CBCUS01C share this pattern (all simple sequential readers). PRESERVED per AAP §0.7.1. The Java translation MUST faithfully emit the record twice per successful read. Consequence of accidental elimination: stdout shrinks from 102 lines to 52 lines for the 50-record fixture; byte-for-byte parity fails immediately on line count. The L96 emission is INSIDE the read paragraph (the value of `CARD-XREF-RECORD` is the just-read row); the L78 emission is OUTSIDE the read paragraph in the main loop (the value of `CARD-XREF-RECORD` is the SAME just-read row because COBOL's WORKING-STORAGE is a single shared region — the L78 dump does NOT re-read the file). The Java translation must mirror this single-read-double-emit semantic.

2. **Consistent `'XREFFILE'` literal across all three error messages** — L110 `'ERROR READING XREFFILE'`, L129 `'ERROR OPENING XREFFILE'`, L147 `'ERROR CLOSING XREFFILE'` all use the single-word `'XREFFILE'`. UNLIKE CBCUS01C which has L129 `'CUSTFILE'` (no space) versus L110 / L147 `'CUSTOMER FILE'` (with space) inconsistency, CBACT03C is CONSISTENT. PRESERVED verbatim per AAP §0.7.1. Do NOT invent an inconsistency in the Java translation by introducing variant literals such as `'XREF FILE'` or `'CARD XREF FILE'`. Consequence of accidental drift: downstream log parsers that grep for the literal `'ERROR OPENING XREFFILE'` will silently miss every CBACT03C open failure once a space is added.

3. **9999-ABEND-PROGRAM and 9910-DISPLAY-IO-STATUS paragraphs use NUMERIC 4-DIGIT PREFIXES** — distinct from CBCUS01C's Z-prefix utility paragraphs (Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS). The numeric prefix scheme is consistent across all five CBACT03C paragraphs (0000-, 1000-, 9000-, 9910-, 9999-). PRESERVED per AAP §0.7.1 in the `@CobolParagraph` annotation values. Consequence of accidental renaming: source-to-source traceability between Java and COBOL is broken; AAP §0.7.1 traceability mandate fails. The Java translation MUST preserve the numeric prefixes exactly: `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`, `0000-XREFFILE-OPEN`, `1000-XREFFILE-GET-NEXT`, `9000-XREFFILE-CLOSE`.

4. **MOVE 999 TO ABCODE before CALL `'CEE3ABD'`** at L157-L158 — the ABCODE value is 999, not 0 or any RETURN-CODE-style value. PRESERVED per AAP §0.7.1; the Java translation MUST raise an abend exception carrying the equivalent `ABCODE = 999` metadata. The CEE3ABD service is the IBM Language Environment abnormal-termination service; the 999 code is observable in z/OS job logs as a user abend code distinct from system abend codes. Consequence of accidental change: abend telemetry comparing COBOL and Java runs by error code will incorrectly classify identical failures as different incidents.

5. **APPL-RESULT 88-level conditions** at L61-L63 (APPL-AOK = 0, APPL-EOF = 16) — preserve as sealed-type pattern per AAP §0.6.2: a sealed hierarchy `ApplResult { Aok, Eof, Error(int code) }` with `Aok` and `Eof` as singleton instances and `Error` as a record carrying the residual status code. Pattern-matching switch sites on this hierarchy MUST be exhaustive (no `default` branch per AAP §0.7.4). The COBOL value `8` set at L119 and the value `12` set at L101 / L124 / L142 are NOT in the 88-level set; they map to `Error(8)` and `Error(12)` respectively, with the discriminator branch falling through to the error path.

6. **TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES** at L53-L56 — translate to sealed interface per AAP §0.6.2 with `TwoBytesBinary` and `TwoBytesAlpha` permits. This REDEFINES is used inside `9910-DISPLAY-IO-STATUS` at L165-L167 to convert a status nybble to a four-digit decimal display via the byte-level alias trick: zero the binary view, write a character into the low byte via the alpha view, then read back the binary view as a number — the trick depends on big-endian byte ordering of `PIC 9(4) BINARY` and on the alpha view sharing the same memory. The Java translation preserves the semantic via the sealed interface and explicit byte-level access (avoiding `Unsafe` per AAP).

7. **9910-DISPLAY-IO-STATUS emits identical literal in BOTH branches** at L168 and L172 — even though the IF branch (status not numeric or starts with `'9'`) and the ELSE branch (status numeric and not starting with `'9'`) use different value-conversion paths, the displayed literal `'FILE STATUS IS: NNNN'` is identical. The Java translation MUST emit byte-identical strings from both translated branches. PRESERVED per AAP §0.7.1. Consequence of refactoring the two branches into a `default` clause: the compiler-enforced exhaustiveness is lost per AAP §0.7.4.

8. **EXIT statement at the tail of every paragraph (L116, L134, L152, L174)** — paragraphs use `EXIT` as the final statement; control flow relies on PERFORM-return semantics, not fall-through. The Java translation MUST preserve PERFORM-style semantics (each paragraph is a private method called from the appropriate site; no `goto`; no fall-through between paragraphs). PRESERVED per AAP §0.7.1. The Java private methods do not need a literal `// EXIT` comment but they MUST NOT inline content from another paragraph; each paragraph is one method.

9. **Apache 2.0 license block at L1-L21 NOT reproduced** in either this README or the captured `stdout.txt`. PRESERVED-AS-IS in `app/cbl/CBACT03C.cbl` per AAP §0.2.2 but NOT propagated into Java source files or test fixtures. The Java module has its own top-level `LICENSE` file (inherited from the repository root); per-file Apache headers in `.java` source files are out of scope for this refactor per AAP §0.7.1 Minimal Change Clause.

10. **Version-stamp comment trailer at L177** — the COBOL source carries a build-system version-and-date comment as its last meaningful line. NOT reproduced in this README or `stdout.txt`. PRESERVED-AS-IS in `app/cbl/CBACT03C.cbl` per AAP §0.2.2 but NOT propagated. The version stamp is a build-system artifact that has no analog in the Maven shaded jar's manifest; Java versioning is captured via Maven's `${project.version}` and the `@CobolProgram` annotation's `translation date` attribute.

11. **14-byte FILLER reconstitution** — the ASCII fixture `app/data/ASCII/cardxref.txt` omits the 14-byte FILLER (records are 36 bytes on disk + 1 LF terminator). The Java `FileCardXrefRepository` adapter MUST reconstitute the full 50-byte record by padding with spaces (ASCII 0x20) in the FILLER positions before passing to `CardXrefRecord.parse(byte[])`. The L78 / L96 DISPLAY output therefore emits all 50 bytes per record (36 digits + 14 spaces). PRESERVED per AAP §0.7.1. Consequence of accidental truncation: the captured `stdout.txt` would have only 36 bytes of record content per line, but the COBOL DISPLAY of CARD-XREF-RECORD always emits the full 50-byte working-storage region including the FILLER. The captured baseline expects all 50 bytes per record dump.

NONE of these idiosyncrasies are bugs to be `fixed`. They are observable behaviors that downstream consumers (operators monitoring logs, parsers reading FILE STATUS codes, byte-for-byte parity assertions) may depend on. Per AAP §0.7.1 Minimal Change Clause, ALL of these are preserved verbatim.

## Phase 10: Java Mapping Invariants

The following invariants govern the Java translation of CBACT03C:

- **One class per COBOL PROGRAM-ID**: `CBACT03C` -> `CbAct03C` in `com.blitzy.carddemo.application.account` per AAP §0.4.1 (NOTE: `account/` subpackage, alongside sibling CBACT01C / CBACT02C — distinct from CBCUS01C which lives in `customer/`)
- **`@CobolProgram("CBACT03C")` annotation** citing original PROGRAM-ID, source path `app/cbl/CBACT03C.cbl`, and translation date per AAP §0.7.1
- **`@CobolParagraph` annotations** on each private method translated from a COBOL paragraph: `@CobolParagraph("1000-XREFFILE-GET-NEXT")`, `@CobolParagraph("0000-XREFFILE-OPEN")`, `@CobolParagraph("9000-XREFFILE-CLOSE")`, `@CobolParagraph("9999-ABEND-PROGRAM")`, `@CobolParagraph("9910-DISPLAY-IO-STATUS")` — NUMERIC 4-DIGIT prefixes preserved per AAP §0.7.1 (distinct from CBCUS01C's Z-prefix utility paragraphs)
- **Constructor injection** of single port: `CardXrefRepository` from `com.blitzy.carddemo.domain.port` — no Spring, no container per AAP §0.3.6. This is the SOLE constructor parameter — CBACT03C is the minimum-dependency program in the application layer.
- **NO `BigDecimal` arithmetic site** in `CbAct03C` (CBACT03C performs no math). The only numeric WORKING-STORAGE fields are APPL-RESULT (PIC S9(9) COMP) used as an enum-like discriminator and ABCODE / TIMING (PIC S9(9) BINARY) used for the CEE3ABD abend signal. XREF-CUST-ID (PIC 9(09)) and XREF-ACCT-ID (PIC 9(11)) are parsed as `long` in `CardXrefRecord`; no `Decimals` utility call is required.
- **NO `java.time` site**: CBACT03C does NOT contain any date or timestamp fields. CARD-XREF-RECORD has no date fields (the three named fields are XREF-CARD-NUM PIC X(16), XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11)). No `LocalDate` / `LocalDateTime` / `LocalTime` usage anywhere in the translation; per AAP §0.6.4 the principle of `java.time` only applies trivially because no temporal data exists.
- **NO `java.util.Date` / `java.util.Calendar` / `java.text.SimpleDateFormat`** per AAP §0.6.4
- **NO `java.io.File`** — use `java.nio.file.Path`, `Files.newByteChannel`, `SeekableByteChannel` per AAP §0.6.5
- **NO `ThreadLocal`** — use `ScopedValue` per AAP §0.6.6. `CbAct03C` is sequential single-threaded; **NO virtual-thread fan-out** (DISPLAY ordering MUST be preserved per AAP §0.6.6 — DOUBLE-DISPLAY pattern requires deterministic interleaving of L96 and L78 emissions per record)
- **EBCDIC IBM-1047 default codepage** per AAP §0.6.5; per-file override via `application.properties` keys (e.g., `carddemo.file.cardxref.charset`). ASCII fixtures use `Charset.forName("US-ASCII")` for test runs
- **Sealed-type pattern** (per AAP §0.6.2) for: APPL-RESULT 88-level conditions as `ApplResult { Aok, Eof, Error(int code) }` sealed hierarchy; FILE STATUS code hierarchy; TWO-BYTES-ALPHA REDEFINES TWO-BYTES-BINARY as sealed `TwoBytes { Binary(short), Alpha(char, char) }`
- **Pattern-matching switch** for case-discrimination on FILE STATUS codes and APPL-RESULT — NO `default` branch per AAP §0.7.4; exhaustiveness enforced by compiler
- **Records, not POJOs**: `CardXrefRecord` (from `app/cpy/CVACT03Y.cpy`) declared as Java `record` per AAP §0.3.2 with 3 named components plus a 14-byte `byte[]` FILLER component
- **`parse(byte[])` and `encode()`** static factory plus instance method pair on `CardXrefRecord` per AAP §0.3.2 byte-level contract; round-trip invariant `parse(buf).encode() == buf` for every valid 50-byte buffer
- **NO Spring, NO Hibernate, NO Spring Batch** — plain Java with constructor injection per AAP §0.3.6
- **Sequential execution mandated** — NO virtual threads per AAP §0.6.6 because the DOUBLE-DISPLAY pattern's deterministic ordering of L96 and L78 emissions is observable
- **JVM flags**: `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4
- **NO `--enable-preview`** per AAP §0.7.4
- **Test-only sink for unmasked PAN**: production logger sinks apply XREF-CARD-NUM masking (all but the last 4 digits of the 16-byte card-number PAN) per AAP §0.7.2 via a separate sink; the test driver uses a separate sink that captures unmasked bytes for byte-for-byte parity
- **DOUBLE-DISPLAY pattern preserved**: the Java method translating `1000-XREFFILE-GET-NEXT` MUST emit `CARD-XREF-RECORD` via the test sink at the same logical site as L96, AND the main-loop translation MUST also emit at the L78-equivalent site, producing 2N stdout lines for N records. The two emissions MUST be byte-identical and MUST appear in the order (L96 first, then L78) on each iteration.


## Phase 11: Test Class Override Map

`CbAct03CGoldenTest` extends `GoldenRecordTest` and overrides only the protected hook methods that identify the program under test, the input fixture path, the auxiliary inputs list (empty for this single-port reader), and the expected output file path. The base class handles single-port wiring, classpath resolution, byte-for-byte parity assertion, and `@Disabled` honoring.

```java
// com.blitzy.carddemo.tests.golden.CbAct03CGoldenTest
@Override
protected Class<?> programClass() {
    return com.blitzy.carddemo.application.account.CbAct03C.class;
}

@Override
protected Path inputFile() {
    return resolveAppDataPath("cardxref.txt");
}

@Override
protected List<Path> auxiliaryInputs() {
    return List.of(); // CBACT03C reads ONE file only — no auxiliaries
}

@Override
protected Path expectedOutputFile() {
    return resolveExpectedOutputPath("cbact03c", "stdout.txt");
}

// NO runProgram(...) override needed; default base-class hook handles single-port wiring
```

The base-class `runProgram(Class<?> programClass, Path inputFile, List<Path> auxiliaryInputs)` hook resolves the single port (`CardXrefRepository`) by wiring a `FileCardXrefRepository` instance against the supplied `inputFile` path, instantiates `CbAct03C` with that repository, and invokes the canonical entry method while a test-only `Appendable` sink captures stdout. After the run completes, the captured sink contents are written to a temporary file whose bytes are compared against `expectedOutputFile()` byte-for-byte. The test method body itself (`@Test void byteForByteParity()`) is inherited from `GoldenRecordTest`; `CbAct03CGoldenTest` provides only the four override hooks above.

`@Disabled` verification checklist — before removing the `@Disabled` annotation, the following 13 conditions MUST be confirmed:

1. Classpath fixture resolver returns non-null for `cardxref.txt` (the sole input)
2. Expected `stdout.txt` exists and is non-empty (captured, not placeholder)
3. Expected output committed via the capture procedure in `java/MIGRATION_NOTES.md` §1.6 — NOT hand-edited
4. Original `app/data/ASCII/cardxref.txt` remains BYTE-IDENTICAL after the test run (test harness MUST NOT mutate the source files per AAP §0.2.2)
5. No production dependency references `System.out` (DISPLAY translations use SLF4J in production; test driver uses an unmasked test-only sink for byte-for-byte parity)
6. `@CobolProgram("CBACT03C")` annotation is present on `CbAct03C` class
7. `@CobolParagraph` annotations are present on each translated paragraph method, citing the full original COBOL paragraph name (e.g., `1000-XREFFILE-GET-NEXT`, `0000-XREFFILE-OPEN`, `9000-XREFFILE-CLOSE`, `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`)
8. No `--enable-preview` in the test runner's JVM args; no preview JEP 502 / 505 / 507 / 512 usage
9. The `-XX:+UseCompactObjectHeaders` flag (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) are documented in the test runner's JVM args
10. **NO virtual threads** are used in the `CbAct03C` execution path; sequential XREFFILE read order preserved exactly per AAP §0.6.6
11. **DOUBLE-DISPLAY pattern verified**: 50 records produce 100 record-display lines bracketed by START + END banners = 102 lines total. The L96 emission (inside `1000-XREFFILE-GET-NEXT`) precedes the L78 emission (in main loop) for each record in stdout order.
12. Consistent `'XREFFILE'` literal preserved verbatim in the Java translation across all three error messages (L110, L129, L147)
13. NUMERIC 4-digit paragraph prefix naming preserved in `@CobolParagraph` annotation values (9999-, 9910-, 0000-, 1000-, 9000-)

## Phase 12: Capture Procedure Cross-Reference

See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build / run path used to capture this fixture's `stdout.txt`. The capture procedure exists because the user prompt left this as a `TODO` marker per AAP §0.7.5.

- **Determinism**:
  - NO Clock injection required (CBACT03C does NOT invoke `FUNCTION CURRENT-DATE`)
  - The single ASCII input `cardxref.txt` is read-only and not mutated
  - The COBOL source contains no random-number generation, no system-time queries, no environment-variable reads beyond the JCL `XREFFILE` DD assignment
  - Multiple captures from a stable baseline (same compiler version, same locale, same JCL) MUST produce byte-identical output
- **Capture command shape** (illustrative; canonical path documented in `java/MIGRATION_NOTES.md` §1.6): compile CBACT03C with GnuCOBOL or z/OS COBOL -> execute with XREFFILE ASSIGN pointing to `app/data/ASCII/cardxref.txt` (after 50-byte record reconstitution by the adapter) -> capture stdout -> commit as `stdout.txt`
- **GnuCOBOL invocation outline** (one of several valid capture paths; full instructions in `java/MIGRATION_NOTES.md` §1.6):
  - Compile: `cobc -x -free -I app/cpy/ app/cbl/CBACT03C.cbl -o /tmp/cbact03c`
  - Configure: the COBOL `SELECT XREFFILE-FILE ASSIGN TO XREFFILE` clause maps `XREFFILE` to a file name via the runtime's external-name resolution (e.g., environment variable `dd_XREFFILE` or runtime configuration entry); the ASCII fixture must be padded to 50 bytes per record by a small pre-processing step or by an indexed-file wrapper that synthesizes the 14-byte FILLER on-the-fly
  - Execute: `dd_XREFFILE=$(pwd)/app/data/ASCII/cardxref.txt /tmp/cbact03c > java/carddemo-tests/src/test/resources/golden/cbact03c/expected/stdout.txt`
  - Verify: `wc -l java/carddemo-tests/src/test/resources/golden/cbact03c/expected/stdout.txt` MUST report 102; first line MUST be `START OF EXECUTION OF PROGRAM CBACT03C`; last line MUST be `END OF EXECUTION OF PROGRAM CBACT03C`
  - z/OS COBOL captures will produce equivalent stdout when the JCL `STEP05` of `app/jcl/READXREF.jcl` is run with `SYSOUT` redirected to a dataset, then the dataset is downloaded and transcoded from EBCDIC to ASCII per AAP §0.6.5
- **PAN exposure**:
  - COBOL CBACT03C DOES `DISPLAY CARD-XREF-RECORD` at L78 and L96 on every iteration, which includes XREF-CARD-NUM (16-byte alphanumeric card-number PAN) unmasked
  - Per AAP §0.7.2, production Java logs MUST mask all but the last 4 digits of card PANs; the captured `stdout.txt` fixture preserves COBOL unmasked behavior for parity assertion via a test-only sink
  - The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact (`carddemo-tests` is `test` scope only; the shaded `carddemo-app` jars never include it on the classpath)
  - Code reviewers MUST verify that no `stdout.txt` fixture is accidentally copied into a `main/resources` tree where it would be packaged into a production jar
- **Until capture is performed**: `CbAct03CGoldenTest` is `@Disabled("Awaiting COBOL CBACT03C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the DOUBLE-DISPLAY pattern: each of 50 input records MUST emit twice (L96 then L78), producing 102 total lines including START and END banners.")`; the parity assertion is unreachable
- **Re-capture trigger**: if the ASCII fixture `cardxref.txt` changes OR the COBOL source `app/cbl/CBACT03C.cbl` changes, this fixture MUST be re-captured. Do NOT ad-hoc edit `stdout.txt`; always re-run the capture procedure

## Phase 13: Scenario Inventory (10 scenarios)

The captured fixture exercises the following 10 scenarios:

1. **Happy path full sequence** — start banner (L71) -> 50 iterations of (L96 record dump from `1000-XREFFILE-GET-NEXT`, then L78 record dump from main loop) -> end banner (L85) = 102 lines total
2. **Sequential XREFFILE ordering preserved** — records appear in stdout in the exact order they appear in `app/data/ASCII/cardxref.txt`; NO virtual threads used (per AAP §0.6.6)
3. **EOF detection** — final XREFFILE read returns FILE STATUS `'10'`; `MOVE 16 TO APPL-RESULT` at L99 sets APPL-EOF condition; `MOVE 'Y' TO END-OF-FILE` at L108 terminates main loop; end banner emitted at L85
4. **DOUBLE-DISPLAY of every record** — 50 records times 2 displays each = 100 record dumps; the L96 emission (from inside `1000-XREFFILE-GET-NEXT`) precedes the L78 emission (in main loop) for each record
5. **CARD-XREF-RECORD 50-byte format verbatim** — the L78 / L96 record dumps emit all 3 named fields plus 14-byte FILLER in CVACT03Y declaration order: XREF-CARD-NUM (16), XREF-CUST-ID (9), XREF-ACCT-ID (11), FILLER (14) — total 50 bytes per record dump
6. **Consistent `'XREFFILE'` literal preserved** — all three error messages (L110, L129, L147) use the single-word `'XREFFILE'`; there is no spacing inconsistency to preserve; preserved verbatim per AAP §0.7.1
7. **NUMERIC 4-digit paragraph prefix preserved** — `0000-XREFFILE-OPEN`, `1000-XREFFILE-GET-NEXT`, `9000-XREFFILE-CLOSE`, `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS` all use 4-digit numeric prefixes (distinct from CBCUS01C's Z-prefix); the `@CobolParagraph` annotation values preserve the numeric prefixes verbatim
8. **No RETURN-CODE setting on normal completion** — unlike CBTRN02C which sets RETURN-CODE = 4 on rejects, CBACT03C leaves RETURN-CODE at default 0; only `9999-ABEND-PROGRAM` (via CEE3ABD at L158 with ABCODE = 999) affects the abend exit code
9. **Sequential execution mandate** — multiple test runs with the same input produce byte-identical stdout; NO non-deterministic reordering due to virtual threads or parallelism; the DOUBLE-DISPLAY pattern's L96-then-L78 ordering on each iteration is deterministic
10. **PAN masking dichotomy** — XREF-CARD-NUM (PIC X(16), 16-byte card-number PAN) is preserved unmasked in the captured `stdout.txt` test fixture via a test-only sink; production logger sinks mask all but the last 4 digits via a separate sink per AAP §0.7.2


## Contrast Matrix — CBACT03C vs. sibling programs

| Aspect | CBACT03C (this) | CBACT01C | CBACT02C | CBCUS01C | CBTRN01C |
|---|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CB (batch) | CB (batch) | CB (batch) | CB (batch) |
| Role | Card xref sequential reader | Account file sequential reader | Card file sequential reader | Customer file sequential reader | Daily transaction validator |
| Source line count | 178 | (similar) | (similar) | 178 | 491 |
| File ports | 1 (XREFFILE only) | 1 (ACCTFILE only) | 1 (CARDFILE only) | 1 (CUSTFILE only) | 6 (3 active + 3 unused) |
| Opened-but-unused files | 0 | 0 | 0 | 0 | 3 |
| Output file count | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) |
| JCL driver | `app/jcl/READXREF.jcl` (EXISTS) | `app/jcl/READACCT.jcl` (EXISTS) | `app/jcl/READCARD.jcl` (EXISTS) | `app/jcl/READCUST.jcl` (EXISTS) | NONE (verified absent) |
| Java FQCN subpackage | `account/` | `account/` | `account/` | `customer/` | `transaction/` |
| Record size (bytes) | 50 (smallest) | 300 | 150 | 500 (largest) | (variable) |
| DOUBLE-DISPLAY pattern | YES (L78 + L96) | YES (sibling pattern) | YES (sibling pattern) | YES (sibling pattern) | NO (single dump) |
| Paragraph prefix style | NUMERIC 4-digit | NUMERIC 4-digit | NUMERIC 4-digit | Z-prefix utility paragraphs | NUMERIC 4-digit |
| Error-message consistency | CONSISTENT `'XREFFILE'` (all three) | (verify in sibling fixture) | (verify in sibling fixture) | INCONSISTENT (L129 `'CUSTFILE'` no space) | (verify) |
| Reject codes | 0 | 0 | 0 | 0 | 0 |
| BigDecimal arithmetic | NO | NO | NO | NO | NO |
| `java.time` fields | NO (no date in xref) | NO (verify) | NO (verify) | YES (CUST-DOB) | (verify) |
| Clock dependency | NO | NO | NO | NO | NO |
| Virtual threads allowed | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) |
| Per-record stdout lines | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 1-6 (variable diagnostic) |
| PII profile | XREF-CARD-NUM (16-digit PAN) | (account ID; not PAN) | (PAN in card record) | CUST-SSN + CUST-GOVT-ISSUED-ID | (varies) |
| Test fixture pattern | input/README + expected/README + 1 placeholder data file | (same) | (same) | (same) | (same; but 5 input fixtures referenced) |
| Parity criticality | secondary | secondary | secondary | secondary | secondary |

CBACT03C's single-port, no-arithmetic, no-clock, no-date, single-output, DOUBLE-DISPLAY nature places it firmly in the simple-sequential-reader family alongside CBACT01C, CBACT02C, and CBCUS01C. The shared DOUBLE-DISPLAY pattern (DISPLAY inside the read paragraph followed by DISPLAY in the main loop after PERFORM returns) is the unifying behavior of this family. CBACT03C is the SMALLEST in the family with a 50-byte record (versus CBACT01C's 300 bytes, CBACT02C's 150 bytes, and CBCUS01C's 500 bytes), and uses CONSISTENT `'XREFFILE'` messaging unlike CBCUS01C's L129 spacing inconsistency. CBTRN01C differs by having SIX file ports, three of which are opened-but-unused, and uses a SINGLE-DISPLAY pattern per iteration instead of DOUBLE-DISPLAY.

## Verbatim Message Catalog Cross-Reference

The 9-row catalog enumerated in Phase 5 (counting the L168 / L172 pair as a single catalog entry because both branches emit an identical literal) is the COMPLETE catalog for CBACT03C. Cross-references in `java/MIGRATION_NOTES.md` will record any message-string changes (NONE expected per AAP §0.7.1). ALL three error messages CONSISTENTLY use `'XREFFILE'` (single word) — UNLIKE CBCUS01C's L129 versus L110 / L147 inconsistency, CBACT03C has NO inconsistency to preserve. The Java translation MUST produce byte-identical strings for each of the 9 catalogued messages.

The Java string-literal sites that emit these messages MUST be string constants (not built up by string concatenation, not formatted via `String.format`, not built via `MessageFormat`). The literal `STR.\"FILE STATUS IS: NNNN\"` (illustrative; actual Java syntax avoids the backslash escape) is appended with the four-character `IO-STATUS-04` formatted value via straightforward byte-level concatenation to match the COBOL DISPLAY's space-separated-operand convention exactly. The single space character between the closing apostrophe of the literal and `IO-STATUS-04` in the COBOL source produces a single space in the rendered output; the Java translation MUST match this single-space separator.

## Source Lineage

All source files in the following list are REFERENCE ONLY — these files remain UNCHANGED per AAP §0.1.1 / §0.2.2 / §0.7.1:

- `app/cbl/CBACT03C.cbl` (178 lines) — Primary COBOL source; PROGRAM-ID at L23; AUTHOR `AWS` at L24; SELECT clause at L29-L33; FD declaration at L37-L40; PROCEDURE DIVISION at L70; main loop at L74-L81; 5 paragraphs total (1 driver paragraph plus 3 sequential read / open / close paragraphs plus 2 numeric-prefix utility paragraphs). The 21-line Apache 2.0 license header at L1-L21 and the version trailer at L177 are NOT propagated into Java source or test fixtures per AAP §0.2.2.
- `app/cpy/CVACT03Y.cpy` (12 lines) — CARD-XREF-RECORD 50-byte layout: 3 named fields (XREF-CARD-NUM PIC X(16), XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11)) plus 14-byte FILLER. Translated to `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` per AAP §0.6.9.
- `app/data/ASCII/cardxref.txt` (1,850 bytes; 50 records) — XREFFILE input fixture (REFERENCE; read-only; sequential 36-byte payloads each followed by LF; 14-byte FILLER omitted on disk and reconstituted by the Java `FileCardXrefRepository` adapter). Byte arithmetic verification: 50 records times 37 bytes (36 payload + 1 LF) = 1,850 bytes total, matching `wc -c` exactly.
- `app/jcl/READXREF.jcl` — JCL driver (REFERENCE only; not invoked by test). Job card at L1-L2; comment block at L3-L18; STEP05 `EXEC PGM=CBACT03C` at L22; STEPLIB DD at L23-L24 pointing to `AWS.M2.CARDDEMO.LOADLIB`; XREFFILE DD at L25-L26 pointing to `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`; SYSOUT / SYSPRINT at L27-L28. Translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardXrefDumpApp.java` per AAP §0.4.1 — the Java main wires `FileCardXrefRepository` from a path resolved via `application.properties` key `carddemo.file.cardxref.path`.

## Cross-References

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbAct03CGoldenTest.java` — JUnit 5 test class consuming this fixture (`@Disabled` until capture committed)
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class with byte-for-byte parity assertion
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CbAct03C.java` — Class under test
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` — CARD-XREF-RECORD from CVACT03Y (50 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/CardXrefRepository.java` — Repository port interface
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileCardXrefRepository.java` — File-backed implementation of `CardXrefRepository`
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardXrefDumpApp.java` — JCL-derived main class (translated from `app/jcl/READXREF.jcl`)
- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbcus01c/expected/README.md` (DOUBLE-DISPLAY pattern predecessor with Z-prefix paragraphs and `'CUSTFILE'` / `'CUSTOMER FILE'` message inconsistency)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbact01c/expected/README.md` (when present; same DOUBLE-DISPLAY pattern for ACCTFILE)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbact02c/expected/README.md` (when present; same DOUBLE-DISPLAY pattern for CARDFILE)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbtrn01c/expected/README.md` (validation-only program template; SIX file ports versus CBACT03C's ONE port; NO JCL driver versus READXREF.jcl)

## Authority References

Every AAP section cited in this README, in order of first appearance. Each citation is binding on the Java translation and on the captured fixture; any future modification to the translation or fixture that violates one of these sections is a regression that the byte-for-byte parity assertion is designed to catch.

- AAP §0.1.1 (refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope: golden-record fixtures under `java/carddemo-tests/src/test/resources/golden/**/*`)
- AAP §0.2.2 (`app/` IMMUTABLE)
- AAP §0.3.1 (harness directory convention `<program>/input/` plus `<program>/expected/`)
- AAP §0.3.2 (records, sealed types, ports)
- AAP §0.3.4 (JVM flags `-XX:+UseCompactObjectHeaders` and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational`)
- AAP §0.3.6 (hexagonal architecture; no Spring)
- AAP §0.4.1 (CBACT03C -> CbAct03C in `com.blitzy.carddemo.application.account`; ASCII fixtures REFERENCE only, NEVER copied)
- AAP §0.6.1 (`Decimals` utility — NOT applicable to CBACT03C because the program performs no arithmetic)
- AAP §0.6.2 (sealed-type pattern)
- AAP §0.6.4 (`java.time` only — NOTE: not used in `CbAct03C`; no date fields exist in CARD-XREF-RECORD)
- AAP §0.6.5 (`java.nio.file`; EBCDIC IBM-1047 default)
- AAP §0.6.6 (sequential execution preserved; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.8 (program-by-program mapping checklist)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` until captures committed)
- AAP §0.7.1 (Minimal Change Clause; preserve DOUBLE-DISPLAY, CONSISTENT `'XREFFILE'` messaging, NUMERIC 4-digit paragraph prefixes, identical L168 / L172 literals)
- AAP §0.7.2 (no PAN in production logs; applies here to XREF-CARD-NUM via separate production sink)
- AAP §0.7.4 (forbidden features: no preview JEPs, no `default` branches that hide cases)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline `[<path>:Lnnn]`)

## DO NOT Modify the Fixture Data Without Re-Capture

The `stdout.txt` file (once captured) is COUPLED with the read-only ASCII fixture `app/data/ASCII/cardxref.txt` and the Java translation's faithful sequential execution with the DOUBLE-DISPLAY pattern. The coupling is bidirectional: a single-byte change in any of the 50 input card-xref records propagates to two changed lines in the expected stdout (the L96 emission and the L78 emission for that record), and the byte-for-byte parity assertion will report the diff immediately. The following maintenance rules apply:

- If `cardxref.txt` content changes OR the COBOL source `app/cbl/CBACT03C.cbl` changes, this fixture MUST be re-captured per `java/MIGRATION_NOTES.md` §1.6
- Ad-hoc edits to `stdout.txt` without re-capture WILL break byte-for-byte parity and indicate a coverage gap, not a successful test
- Do NOT invent a `'CUSTFILE'`-style spacing inconsistency for CBACT03C — all three error messages CONSISTENTLY use `'XREFFILE'` (single word) — PRESERVED per AAP §0.7.1
- Do NOT change paragraph naming from numeric 4-digit prefixes (9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS) to Z-prefix style — PRESERVED per AAP §0.7.1
- Do NOT mask PAN (XREF-CARD-NUM 16-byte card-number) in captured `stdout.txt` — the test driver uses an unmasked sink while production logs apply masking via a separate sink per AAP §0.7.2
- Do NOT introduce virtual threads — sequential execution mandated per AAP §0.6.6 because the DOUBLE-DISPLAY pattern's L96-then-L78 ordering on each iteration is observable
- Do NOT eliminate the DOUBLE-DISPLAY pattern — the L96 and L78 emissions MUST both appear per successful read iteration to honor byte-for-byte parity with the COBOL baseline
- Do NOT replace the 14-byte FILLER trailing-space output with a trimmed alternative; the FILLER bytes are part of the 50-byte record contract and the COBOL DISPLAY emits them verbatim
- Do NOT introduce a `BigDecimal` arithmetic site in the translation; CBACT03C performs no math and the `Decimals` utility (per AAP §0.6.1) is not in scope for this program
- Do NOT introduce a `Clock` injection point; CBACT03C does not invoke `FUNCTION CURRENT-DATE` and no timestamp-derived field appears in its output
- Do NOT introduce any `java.time` usage in `CbAct03C`; CARD-XREF-RECORD has no date or time fields and the principle of `java.time` only (per AAP §0.6.4) applies trivially

If any of the preserved behaviors must change for a legitimate business reason in a future refactor, document the change in `java/MIGRATION_NOTES.md` (a new section) and re-capture this fixture in the same commit so the audit trail is preserved.
