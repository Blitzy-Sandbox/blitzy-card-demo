# Golden-Record Contract — CBACT02C (Card File Sequential Reader; JCL Driver: READCARD.jcl; SINGLE-DISPLAY Pattern)

This document is the authoritative byte-for-byte contract for the Java translation of COBOL program CBACT02C, a read-only sequential display utility that opens a single VSAM KSDS card master file, walks every record in key order, and emits each successful record to stdout EXACTLY ONCE per iteration — only at `[app/cbl/CBACT02C.cbl:L78]` in the main loop after `PERFORM 1000-CARDFILE-GET-NEXT` returns. The companion `DISPLAY CARD-RECORD` at `[app/cbl/CBACT02C.cbl:L96]` inside `1000-CARDFILE-GET-NEXT` is COMMENTED OUT (the `*` in column 7 makes it a COBOL comment line) and emits nothing — this is the marquee **SINGLE-DISPLAY pattern** that UNIQUELY distinguishes CBACT02C from its sibling DOUBLE-DISPLAY readers (CBACT01C, CBACT03C, CBCUS01C).

The companion JUnit 5 test class `com.blitzy.carddemo.tests.golden.CbAct02CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and consumes the single captured fixture file documented below.

The test class is `@Disabled("Awaiting COBOL CBACT02C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the SINGLE-DISPLAY pattern: each of 50 input records MUST emit ONCE (from L78 only, NOT L96 which is COMMENTED OUT), producing 52 total lines including START and END banners.")` per AAP §0.6.11 until `stdout.txt` is present in this folder with content captured from a COBOL reference run and committed.

Every claim in this README about COBOL behavior cites a specific line range in the form `[<path>:Lnnn]` or `[<path>:Lnnn-Lmmm]` per AAP §0.8.1 citation discipline.

CBACT02C has a verified existing JCL driver at `app/jcl/READCARD.jcl` that invokes `EXEC PGM=CBACT02C` at STEP05 with the CARDFILE DD pointing to `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`. The Java equivalent main class lives at `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardDumpApp.java` per AAP §0.4.1; the golden-record test class, however, bypasses the main class and invokes `CbAct02C` directly via constructor-injected `CardRepository` port for harness simplicity. CBACT02C is the median record size in the simple-sequential-reader family at 150 bytes (versus CBACT01C's 300 bytes, CBACT03C's 50 bytes, and CBCUS01C's 500 bytes).

The binding cascade flows down through 19 AAP sections enumerated in Phase 0 below.

This README is the SOLE marker for the `expected/` directory in this commit — by virtue of its presence, the empty folder is preserved in version control without requiring a `.gitkeep`. The `stdout.txt` data file belongs to a follow-on COBOL-capture commit per `java/MIGRATION_NOTES.md` §1.6 and MUST NOT be introduced in this initial commit. Both files (this README and the future `stdout.txt`) together form the complete `expected/` directory once capture has been performed.

## Phase 0: Authority and Source-of-Truth Cascade

The following 19 AAP sections govern every byte and every assertion described in this README:

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS; no behavior changes
- AAP §0.2.1 — Golden-record fixtures are in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE; never modified by the Java refactor
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` (documentation marker) + `<program>/expected/` (this folder)
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports define the Java translation shape
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521)
- AAP §0.3.6 — Hexagonal architecture; no Spring container, no application framework
- AAP §0.4.1 — CBACT02C -> CbAct02C in `com.blitzy.carddemo.application.account` (NOTE: `account/` subpackage alongside CBACT01C / CBACT03C / CBACT04C, distinct from CBCUS01C's `customer/` subpackage); ASCII fixtures REFERENCE only, NEVER copied into this folder
- AAP §0.6.1 — `Decimals` utility centralizes monetary arithmetic with `MathContext.DECIMAL128`; NOT applicable to CBACT02C because the program performs no arithmetic
- AAP §0.6.2 — Sealed-type pattern for closed value sets and lookup outcomes (used here for APPL-RESULT 88-level conditions, FILE STATUS codes, and the TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES)
- AAP §0.6.4 — `java.time` only for date and time; never `java.util.Date` or `Calendar` — CARD-EXPIRAION-DATE PIC X(10) is the SOLE java.time site in CbAct02C, mapping to `LocalDate` with the COBOL `EXPIRAION` spelling preserved verbatim
- AAP §0.6.5 — `java.nio.file` for all file I/O; EBCDIC IBM-1047 default codepage with per-file override
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely in new code; sequential execution preserved for ordered output (NO virtual threads in CBACT02C path — SINGLE-DISPLAY ordering must be deterministic)
- AAP §0.6.8 — Program-by-program COBOL-to-Java mapping checklist confirms CBACT02C -> CbAct02C
- AAP §0.6.11 — Golden-record harness is the PR gate; `@Disabled` until COBOL captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve the SINGLE-DISPLAY pattern (L96 COMMENTED OUT), the CONSISTENT `'CARDFILE'` (single word) literal across all three error messages (L110, L129, L147), the NUMERIC 4-digit paragraph prefix naming for 9999-ABEND-PROGRAM and 9910-DISPLAY-IO-STATUS, the COBOL `EXPIRAION` spelling on CARD-EXPIRAION-DATE, and the identical literal in both branches of 9910-DISPLAY-IO-STATUS at L168 / L172
- AAP §0.7.2 — No PAN in production logs (mask all but last 4 digits); for CBACT02C the sensitive PII fields are CARD-NUM (PIC X(16), a 16-byte card-number PAN) AND CARD-CVV-CD (PIC 9(03), a 3-digit CVV); test driver uses unmasked sink for parity assertion, production sinks apply masking via a separate path
- AAP §0.7.4 — No preview features (JEP 502 Stable Values, JEP 505 Structured Concurrency, JEP 507 Primitive Patterns, JEP 512 Compact Source Files in production code); no `default` branches that hide cases in pattern-matching switch
- AAP §0.7.5 — Capture procedure for golden-record fixtures is documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]` for every claim about COBOL behavior

The cascade is intentionally narrow: only the 19 sections enumerated above govern CBACT02C's golden-record fixture. Broader AAP sections (such as §0.5 Dependency Inventory or §0.4 Transformation Mapping at large) provide context for the surrounding Java translation but do not directly dictate any byte of this fixture. If a future code-generation agent needs to revise this README, the cascade is the canonical entry point; any new claim added to this README MUST trace back to one of the sections above (or extend the cascade by adding a citation to a previously unreferenced AAP section if a new authority becomes relevant).

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBACT02C` | `[app/cbl/CBACT02C.cbl:L23]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBACT02C.cbl:L24]` |
| Source line count | 178 | `app/cbl/CBACT02C.cbl` (per `wc -l`) |
| JCL driver | `app/jcl/READCARD.jcl` (EXISTS — verified) | `EXEC PGM=CBACT02C` at STEP05 |
| FILE-CONTROL SELECTs | 1 file (CARDFILE) | `[app/cbl/CBACT02C.cbl:L28-L33]` |
| FD declaration | 150-byte CARDFILE-FILE (FD-CARD-NUM PIC X(16) + FD-CARD-DATA PIC X(134)) | `[app/cbl/CBACT02C.cbl:L37-L40]` |
| Files actively read | 1 (CARDFILE) | Main loop at `[app/cbl/CBACT02C.cbl:L74-L81]` |
| Files opened-but-unused | 0 | Single-port profile; CONTRAST with CBTRN01C's 6-port profile |
| Java FQCN under test | `com.blitzy.carddemo.application.account.CbAct02C` (NOTE: `account/` subpackage, alongside sibling CBACT01C / CBACT03C / CBACT04C — distinct from CBCUS01C which lives in `customer/`) | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbAct02CGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |

The `@CobolProgram("CBACT02C")` Javadoc-style annotation MUST cite the original PROGRAM-ID, the source path `app/cbl/CBACT02C.cbl`, and the translation date per AAP §0.7.1. The annotation is the only durable traceability link between the Java class and its COBOL origin once source-tree drift over time makes side-by-side reading harder.

Each translated COBOL paragraph MUST carry a `@CobolParagraph("<NUMERIC-PREFIX-NAME>")` Javadoc annotation citing the original paragraph name verbatim. For CBACT02C the five required annotations are `@CobolParagraph("1000-CARDFILE-GET-NEXT")`, `@CobolParagraph("0000-CARDFILE-OPEN")`, `@CobolParagraph("9000-CARDFILE-CLOSE")`, `@CobolParagraph("9999-ABEND-PROGRAM")`, and `@CobolParagraph("9910-DISPLAY-IO-STATUS")` — note that ALL five paragraphs use NUMERIC 4-DIGIT prefixes, distinct from CBCUS01C's Z-prefix utility paragraphs. The numeric prefixes 9999- and 9910- are PRESERVED per AAP §0.7.1 even though they would benefit from a more semantic Java method name; the original COBOL paragraph names are the canonical traceability anchor.

The `@Disabled` mandate: the test class remains `@Disabled` until `stdout.txt` is present with captured (not placeholder) content per AAP §0.6.11. Removing the `@Disabled` annotation prematurely will cause the test to fail because the expected output file does not exist in this initial commit.

The test class lives in the same `golden` test-resources package as its base class and other sibling golden-record tests so that JUnit Platform discovery picks it up automatically without explicit registration. The `mvn -B clean verify` invocation in CI runs surefire over the `carddemo-tests` module and reports the status of every golden-record test; tests disabled via `@Disabled` are reported as skipped (not failed) so the overall build stays green during the bootstrap window when most baselines are still being captured. Once the capture procedure has been executed and `stdout.txt` is committed, the bootstrap window for CBACT02C ends and the `@Disabled` annotation MUST be removed in the same commit that introduces the captured file so the test joins the active PR gate without delay.

## Phase 2: Files in This Folder

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBACT02C golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbAct02CGoldenTest` once `stdout.txt` is captured per AAP §0.7.5.

### `stdout.txt` — Captured DISPLAY Output (CAPTURE PLACEHOLDER)

The single captured output of a reference COBOL run of CBACT02C against `app/data/ASCII/carddata.txt`.

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in the platform's newline (LF on captured run from Linux).
- **First line**: L71 banner `` `'START OF EXECUTION OF PROGRAM CBACT02C'` ``
- **Last line**: L85 banner `` `'END OF EXECUTION OF PROGRAM CBACT02C'` ``
- **Per-record body** (executes once per CARDFILE record): EXACTLY ONE line per record:
  - L78: `DISPLAY CARD-RECORD` (in main loop after `PERFORM 1000-CARDFILE-GET-NEXT` returns with `END-OF-FILE = 'N'` — SOLE emission per iteration; 150-byte fixed-width record emitted as one line followed by LF)
  - L96 inside `1000-CARDFILE-GET-NEXT` is COMMENTED OUT (`*        DISPLAY CARD-RECORD`) and emits nothing — this is the **SINGLE-DISPLAY signature** distinguishing CBACT02C from sibling DOUBLE-DISPLAY readers (CBACT01C, CBACT03C, CBCUS01C)
- **Total expected line count**: `2 + N` where N is the number of CARDFILE records. For the current `app/data/ASCII/carddata.txt` fixture (N = 50, verified via `wc -l`), expected = `2 + 50 = 52` lines.
- **Byte-for-byte parity rule**: Java captured stdout MUST equal the captured baseline BYTE-for-BYTE. Trailing whitespace and padding direction preserved exactly. Line breaks preserved exactly (LF).
- **PAN+CVV exposure dichotomy**: CARD-NUM PIC X(16) (16-digit card number) AND CARD-CVV-CD PIC 9(03) (3-digit CVV) are sensitive payment-card data per AAP §0.7.2. The test-only sink writes UNMASKED bytes for parity assertion per AAP §0.7.1 (preserve COBOL behavior exactly); production logger sinks apply card-PAN masking (all but the last 4 digits) AND CVV masking (all 3 digits) per AAP §0.7.2 via a separate sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- **No deterministic Clock required**: CBACT02C does NOT invoke FUNCTION CURRENT-DATE anywhere in the program; captured output is naturally deterministic given fixed input.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT exist yet in this folder; `CbAct02CGoldenTest` is `@Disabled` until captured content is committed per `java/MIGRATION_NOTES.md` §1.6.
- **File presence is the gating signal**: the absence of `stdout.txt` in this folder is the canonical signal that capture has not been performed; the `@Disabled` reason string makes this explicit and points the reader at the regeneration procedure. Once the file is present, the `@Disabled` annotation MUST be removed in the same commit that introduces the file so the active PR gate immediately exercises the parity assertion.
- **No other files belong in this folder**: this commit intentionally contains ONLY this README. There are NO `stdout.txt`, NO `.gitkeep`, NO subfolders, NO ancillary diagnostic files. The empty-folder problem is solved by this README's presence; version control retains the folder structure for free. Any subsequent files (notably `stdout.txt`) belong in follow-on commits driven by the capture procedure in `java/MIGRATION_NOTES.md` §1.6.

## Phase 3: Conceptual Input Universe

The single ASCII input that produces the captured baseline:

- CARDFILE: `app/data/ASCII/carddata.txt` (7,550 bytes; 50 sequential 150-byte records each followed by LF) — PRIMARY input

Unlike CBACT03C's `cardxref.txt` (which omits 14 bytes of FILLER from the 50-byte logical record, producing a 36-byte-on-disk payload), CBACT02C's `carddata.txt` INCLUDES the full 59-byte FILLER on disk. Each line is 151 bytes (150-byte CARD-RECORD payload + 1-byte LF terminator), verified via `head -1 app/data/ASCII/carddata.txt | wc -c`. The Java `FileCardRepository` adapter reads the full 150-byte record directly from the fixture without padding reconstitution.

NO auxiliary inputs (unlike CBTRN01C's 5-file ensemble or CBTRN02C's 4-file ensemble). CBACT02C reads ONE file only. There is no DISCGRP, no TRANTYPE, no TRANCATG, no XREFFILE, no ACCTFILE — only CARDFILE.

The fixture is NEVER copied to this folder per AAP §0.4.1 — referenced via classpath relative path from `app/data/ASCII/`. The Java test class resolves the classpath via the `resolveAppDataPath("carddata.txt")` helper from the `GoldenRecordTest` base class.

CBACT02C performs NO writes, NO rewrites, NO updates; all inputs are read-only and the original `app/data/ASCII/carddata.txt` remains UNCHANGED after the test run per AAP §0.2.2. The Java test harness MUST NOT mutate the source files under `app/` for any reason.

The fixture contains 50 records with 150-byte payloads — each comprising a 16-digit card number (CARD-NUM, sensitive PAN), an 11-digit account ID (CARD-ACCT-ID), a 3-digit CVV (CARD-CVV-CD, sensitive), a 50-character embossed name (CARD-EMBOSSED-NAME), a 10-character expiration date in the COBOL `EXPIRAION` spelling (CARD-EXPIRAION-DATE), a 1-character active status flag (CARD-ACTIVE-STATUS), and a 59-byte FILLER (typically spaces). The byte count adds to `16 + 11 + 3 + 50 + 10 + 1 + 59 = 150` bytes exactly per the CVACT02Y copybook declaration.

The fixture is encoded in ASCII (US-ASCII) for test convenience. Production CARDFILE inputs in the source mainframe environment are encoded in EBCDIC IBM-1047 per AAP §0.6.5; the test harness sets the codepage override via `application.properties` key `carddemo.file.carddata.charset=US-ASCII` so the same `FileCardRepository` adapter can read both encodings without code changes. The byte-for-byte parity assertion compares the captured `stdout.txt` (ASCII output from a COBOL run on the ASCII fixture) to the Java output (ASCII output from a Java run on the same ASCII fixture); the EBCDIC code path is exercised separately by adapter-level unit tests that are out of scope for this golden-record fixture.

The record ordering in the fixture follows the VSAM KSDS primary-key sequence on FD-CARD-NUM. Since the COBOL `READ CARDFILE-FILE INTO CARD-RECORD` at L93 walks the KSDS in primary-key order (because the SELECT clause at L31 specifies `ACCESS MODE IS SEQUENTIAL`), the captured `stdout.txt` emits records in increasing CARD-NUM order. The Java translation MUST preserve this ordering exactly; the `FileCardRepository.streamSequential()` method returns a `Stream<CardRecord>` ordered by CARD-NUM ascending, and the `CbAct02C` main-loop translation consumes the stream in encounter order without reordering, parallelization, or sort buffer interposition.

## Phase 4: Sequential Read Flow (5 paragraphs)

Every paragraph in `app/cbl/CBACT02C.cbl` is enumerated below with exact line citations:

- PROCEDURE DIVISION entry at `[app/cbl/CBACT02C.cbl:L70]`
- Start banner DISPLAY at `[app/cbl/CBACT02C.cbl:L71]` — `DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'`
- `PERFORM 0000-CARDFILE-OPEN` at `[app/cbl/CBACT02C.cbl:L72]`
- Main loop at `[app/cbl/CBACT02C.cbl:L74-L81]` — `PERFORM UNTIL END-OF-FILE = 'Y'`
- Per-iteration body of the main loop:
  - L75-L80 nested IF / PERFORM / IF / DISPLAY block
  - L76: `PERFORM 1000-CARDFILE-GET-NEXT`
  - L77: inner `IF END-OF-FILE = 'N'`
  - L78: `DISPLAY CARD-RECORD` — main-loop record dump; SOLE emission per iteration; SINGLE-DISPLAY pattern
  - L79: inner `END-IF`
  - L80: outer `END-IF`
  - L81: `END-PERFORM`
- `PERFORM 9000-CARDFILE-CLOSE` at `[app/cbl/CBACT02C.cbl:L83]`
- End banner DISPLAY at `[app/cbl/CBACT02C.cbl:L85]` — `DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'`
- `GOBACK` at `[app/cbl/CBACT02C.cbl:L87]`
- `1000-CARDFILE-GET-NEXT` at `[app/cbl/CBACT02C.cbl:L92]`:
  - L93: `READ CARDFILE-FILE INTO CARD-RECORD`
  - L94: `IF CARDFILE-STATUS = '00'`
  - L95: `MOVE 0 TO APPL-RESULT`
  - L96: `*        DISPLAY CARD-RECORD` — COMMENTED OUT; SINGLE-DISPLAY signature; preserved verbatim as comment in the Java translation
  - L97: `ELSE`
  - L98: `IF CARDFILE-STATUS = '10' MOVE 16 TO APPL-RESULT`
  - L101: other status `MOVE 12 TO APPL-RESULT`
  - L104-L114: APPL-AOK / APPL-EOF / error decision tree
  - L107-L108: `IF APPL-EOF MOVE 'Y' TO END-OF-FILE`
  - L110: non-EOF error path emits `'ERROR READING CARDFILE'`
  - L111-L113: `MOVE CARDFILE-STATUS TO IO-STATUS` -> `PERFORM 9910-DISPLAY-IO-STATUS` -> `PERFORM 9999-ABEND-PROGRAM`
  - L116: `EXIT`
- `0000-CARDFILE-OPEN` at `[app/cbl/CBACT02C.cbl:L118]`:
  - L119: `MOVE 8 TO APPL-RESULT`
  - L120: `OPEN INPUT CARDFILE-FILE`
  - L121: `IF CARDFILE-STATUS = '00'` -> `MOVE 0 TO APPL-RESULT`
  - L123: `ELSE MOVE 12 TO APPL-RESULT`
  - L126-L133: success / failure decision tree
  - L129: `DISPLAY 'ERROR OPENING CARDFILE'` — uses `'CARDFILE'` single word, CONSISTENT with L110 and L147
  - L130-L132: `MOVE` / `PERFORM` error chain
  - L134: `EXIT`
- `9000-CARDFILE-CLOSE` at `[app/cbl/CBACT02C.cbl:L136]`:
  - L137: `ADD 8 TO ZERO GIVING APPL-RESULT`
  - L138: `CLOSE CARDFILE-FILE`
  - L139: `IF CARDFILE-STATUS = '00'`
  - L140: `SUBTRACT APPL-RESULT FROM APPL-RESULT` (sets APPL-RESULT to 0)
  - L142: `ELSE ADD 12 TO ZERO GIVING APPL-RESULT`
  - L144-L151: success / failure decision tree
  - L147: `DISPLAY 'ERROR CLOSING CARDFILE'` — CONSISTENT `'CARDFILE'` single word
  - L148-L150: `MOVE` / `PERFORM` error chain
  - L152: `EXIT`
- `9999-ABEND-PROGRAM` at `[app/cbl/CBACT02C.cbl:L154]` (NUMERIC 4-DIGIT PREFIX):
  - L155: `DISPLAY 'ABENDING PROGRAM'`
  - L156: `MOVE 0 TO TIMING`
  - L157: `MOVE 999 TO ABCODE`
  - L158: `CALL 'CEE3ABD'`
- `9910-DISPLAY-IO-STATUS` at `[app/cbl/CBACT02C.cbl:L161]` (NUMERIC 4-DIGIT PREFIX):
  - L162-L168: `IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'` branch — extract chars, convert binary, `DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04`
  - L169-L172: `ELSE` branch — `MOVE '0000' TO IO-STATUS-04`, `MOVE IO-STATUS TO IO-STATUS-04(3:2)`, DISPLAY identical literal
  - L174: `EXIT`

CBACT02C performs NO writes, NO rewrites, NO updates. The Java translation MUST faithfully open and close CARDFILE-FILE and produce a SINGLE-DISPLAY for each successful read — from the L78 main-loop site only, NOT from the L96 site inside `1000-CARDFILE-GET-NEXT` which is COMMENTED OUT in the COBOL source and MUST remain emission-free in the Java translation.

The five-paragraph PROCEDURE DIVISION inventory above is the COMPLETE set of paragraphs in CBACT02C; no additional procedural sections, no `DECLARATIVES`, no `EXIT PROGRAM` constructs, no `STOP RUN` outside the `GOBACK` at L87. Every flow path from program entry to either normal `GOBACK` exit or `CEE3ABD` abend transit is traceable through this enumeration. The Java translation MUST mirror this paragraph-to-private-method cardinality one-to-one: five COBOL paragraphs map to five private Java methods on `CbAct02C`, each annotated with the matching `@CobolParagraph(...)` value so that traceability from Java back to the COBOL source line ranges remains direct and unambiguous over time.

## Phase 5: Verbatim DISPLAY Message Catalog (9 entries, verified)

Every distinct DISPLAY content in CBACT02C is enumerated below. The L168 / L172 pair counts as a single catalog entry because both branches emit the byte-identical literal `'FILE STATUS IS: NNNN'` followed by the IO-STATUS-04 four-digit field.

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBACT02C'` `` | L71 | Unconditional start banner emitted before `PERFORM 0000-CARDFILE-OPEN` |
| 2 | `DISPLAY CARD-RECORD` (full 150-byte record) | L78 | Main-loop record dump after `PERFORM 1000-CARDFILE-GET-NEXT` returns with `END-OF-FILE = 'N'` — SOLE emission per iteration; SINGLE-DISPLAY pattern |
| 3 | `` `'END OF EXECUTION OF PROGRAM CBACT02C'` `` | L85 | Unconditional end banner emitted after `PERFORM 9000-CARDFILE-CLOSE` and before `GOBACK` |
| 4 | `*        DISPLAY CARD-RECORD` | L96 | COMMENTED OUT inside `1000-CARDFILE-GET-NEXT` on `CARDFILE-STATUS = '00'` success path — preserved as COBOL comment; emits nothing |
| 5 | `` `'ERROR READING CARDFILE'` `` | L110 | `1000-CARDFILE-GET-NEXT` non-EOF error path |
| 6 | `` `'ERROR OPENING CARDFILE'` `` | L129 | `0000-CARDFILE-OPEN` failure path — uses `'CARDFILE'` single word, CONSISTENT with L110 and L147 |
| 7 | `` `'ERROR CLOSING CARDFILE'` `` | L147 | `9000-CARDFILE-CLOSE` failure path — CONSISTENT `'CARDFILE'` single word |
| 8 | `` `'ABENDING PROGRAM'` `` | L155 | `9999-ABEND-PROGRAM` before `CALL 'CEE3ABD'` |
| 9 | `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 | L168, L172 | `9910-DISPLAY-IO-STATUS` — both `IF` and `ELSE` branches emit identical literal followed by the four-digit IO-STATUS-04 field |

ALL messages preserve EXACT bytes including embedded SPACES and the embedded COLON. UNLIKE CBCUS01C, CBACT02C uses CONSISTENT `'CARDFILE'` (single word) across all three error messages (L110, L129, L147) — there is NO L129 vs L110 / L147 inconsistency to preserve. The Java translation MUST produce byte-identical strings for each of the 8 active catalogued messages. The L96 COMMENTED OUT line emits nothing in COBOL execution and MUST emit nothing in the Java translation; preserve it as a Java code comment for traceability documenting the SINGLE-DISPLAY pattern provenance.

## Phase 6: Read-Outcome Branches (NO Reject Codes)

CBACT02C emits NO reject codes (unlike CBTRN02C's five codes 100 / 101 / 102 / 103 / 109). There is no reject file, no reject record layout, no validation trailer record. CBACT02C is a pure read-and-display utility — every record that returns FILE STATUS '00' is dumped to stdout once at L78 (SINGLE-DISPLAY pattern), and any non-success non-EOF status triggers an abend.

The read-outcome branches inside `1000-CARDFILE-GET-NEXT`:

- `CARDFILE-STATUS = '00'` -> success path -> `MOVE 0 TO APPL-RESULT` at L95 -> NO DISPLAY emission at L96 (COMMENTED OUT) -> caller's main loop emits `DISPLAY CARD-RECORD` at L78 (SOLE emission per iteration)
- `CARDFILE-STATUS = '10'` -> EOF path -> `MOVE 16 TO APPL-RESULT` at L99 -> `IF APPL-EOF` condition true at L107 -> `MOVE 'Y' TO END-OF-FILE` at L108 -> main loop's `PERFORM UNTIL END-OF-FILE = 'Y'` terminates
- Any other status -> error path -> `MOVE 12 TO APPL-RESULT` at L101 -> `DISPLAY 'ERROR READING CARDFILE'` at L110 -> `MOVE CARDFILE-STATUS TO IO-STATUS` at L111 -> `PERFORM 9910-DISPLAY-IO-STATUS` at L112 -> `PERFORM 9999-ABEND-PROGRAM` at L113

APPL-RESULT 88-level conditions at `[app/cbl/CBACT02C.cbl:L61-L63]`: APPL-AOK = 0, APPL-EOF = 16. Other values trigger abend via `9999-ABEND-PROGRAM`. The two named 88-level conditions partition the value space cleanly for translation to a sealed hierarchy.

RETURN-CODE: NOT set explicitly on success or failure within CBACT02C's PROCEDURE DIVISION. On the `9999-ABEND-PROGRAM` path, ABCODE = 999 (set at L157) is passed to CEE3ABD via the `CALL 'CEE3ABD'` at L158; the OS-level return code is platform-dependent on the COBOL runtime. On normal completion via `GOBACK` at L87, RETURN-CODE remains at its default value (0).

The branch structure is exhaustive: `CARDFILE-STATUS = '00'`, `CARDFILE-STATUS = '10'`, and "any other status" together partition the entire FILE STATUS value space, and the COBOL `IF` / `ELSE` ladder at L94-L103 enforces this partitioning explicitly. The Java translation maps this onto a sealed `FileStatus` hierarchy with `Ok`, `EndOfFile`, and `IoError(int code)` permits per AAP §0.6.2; a pattern-matching switch on the parsed FILE STATUS value enforces exhaustiveness at compile time without a `default` clause per AAP §0.7.4. Any new FILE STATUS code introduced in future COBOL maintenance (e.g., a code added in `1000-CARDFILE-GET-NEXT`) would surface immediately as a compile error in the Java translation, prompting an explicit case-handling decision rather than a silent fallthrough.

The abend path is symmetric across all three error paragraphs: `1000-CARDFILE-GET-NEXT` (L110-L113), `0000-CARDFILE-OPEN` (L129-L132), and `9000-CARDFILE-CLOSE` (L147-L150) all execute the same three-step chain — `DISPLAY` an error message, `MOVE CARDFILE-STATUS TO IO-STATUS`, `PERFORM 9910-DISPLAY-IO-STATUS`, `PERFORM 9999-ABEND-PROGRAM`. The Java translation factors this chain into a private helper method on `CbAct02C` that takes the error-message string as a parameter and is invoked from the three paragraph methods; the helper preserves the COBOL ordering of the four operations exactly so the abend log output is byte-identical regardless of which paragraph triggered it.

## Phase 7: Single-Output stdout.txt Byte Contract

The byte contract for the single captured output file `stdout.txt`:

- **Format**: line-based ASCII text. Each COBOL `DISPLAY` statement produces ONE line of output ending in LF (Unix-style newline on captured Linux run).
- **First line**: L71 banner `` `'START OF EXECUTION OF PROGRAM CBACT02C'` ``
- **Last line**: L85 banner `` `'END OF EXECUTION OF PROGRAM CBACT02C'` ``
- **Per CARDFILE record body** (executes once per input record on the success path): EXACTLY ONE line:
  - L78 conditional `DISPLAY CARD-RECORD` (150-byte record verbatim including 59-byte FILLER spaces) — emitted in main loop AFTER `PERFORM 1000-CARDFILE-GET-NEXT` returns with `END-OF-FILE = 'N'`
  - L96's `DISPLAY CARD-RECORD` is COMMENTED OUT (`*` in column 7) and emits nothing — the structural reason the per-record line count is 1 and not 2
- **Total expected line count**: `2 + N` where N is the number of CARDFILE records in `app/data/ASCII/carddata.txt`. Currently N = 50 records (verified via `wc -l app/data/ASCII/carddata.txt`). For the present fixture: `2 + 50 = 52` lines.
- **Byte-for-byte parity rule**: `CbAct02C.run(...)` MUST produce, for the deterministic input set, a captured stdout byte sequence equal to this captured baseline BYTE-for-BYTE.
- **Trailing whitespace**: COBOL DISPLAY of a fixed-width 150-byte record emits trailing spaces from the 59-byte FILLER; the captured `stdout.txt` is the reference. The Java translation MUST match byte-by-byte, including the 59 trailing space characters per record line.
- **PAN+CVV exposure**: L78's full-record dump includes CARD-NUM (16-byte card-number PAN) AND CARD-CVV-CD (3-byte CVV) unmasked. The test-only sink writes the unmasked bytes for parity assertion; production logger sinks apply card-PAN masking (mask all but the last 4 digits of PAN) AND CVV masking (mask all 3 digits of CVV) per AAP §0.7.2 via a separate sink.
- **No deterministic Clock needed**: CBACT02C does NOT invoke FUNCTION CURRENT-DATE; no timestamp generation occurs anywhere in the program; the captured output is naturally deterministic given fixed input.
- **Encoding contract**: the captured `stdout.txt` is encoded in the same character set as the input fixture (US-ASCII for the test fixture). The Java translation MUST emit US-ASCII bytes when configured for the test fixture so that the captured baseline and the Java output are byte-comparable without any transcoding step in the parity assertion. The byte sequence is treated as opaque bytes by the assertion; no character-level normalization is performed.
- **Line-terminator contract**: the captured `stdout.txt` uses LF terminators (0x0A) on every line including the final line. The Java translation MUST use the same LF terminator on every emitted line so that line counts and byte counts agree exactly. Do NOT introduce CRLF terminators (0x0D 0x0A) regardless of the host operating system; CRLF would inflate the byte count by N bytes for N lines and would break byte-for-byte parity even when line content matches.

## Phase 8: NO Opened-But-Unused Files

CBACT02C opens EXACTLY ONE file (CARDFILE-FILE) and reads it sequentially. This is in CONTRAST to CBTRN01C which opens 6 files but reads only 3 (3 opened-but-unused: CUSTOMER, CARD, TRANSACT). The Java translation has a SINGLE `CardRepository` port — no auxiliary ports — making `CbAct02C` among the smallest in the application layer in terms of constructor-injected dependencies.

| File | Open | Close | Active | Status |
|---|---|---|---|---|
| CARDFILE-FILE | L120 (0000-CARDFILE-OPEN) | L138 (9000-CARDFILE-CLOSE) | YES (sequential READ at L93) | Active — sole port |

No 'opened-but-unused' boilerplate exists in CBACT02C. The single CARDFILE-FILE port is both opened and actively read in the main loop. The Java translation's `CardRepository` port is therefore the sole constructor-injected dependency of `CbAct02C`, and there are no additional ports stubbed out or wired without use.

The single-port profile combined with the SINGLE-DISPLAY pattern keeps `CbAct02C` among the smallest application-layer classes by both constructor signature and method body. A reasonable Java translation footprint is one public entry method (translating the main-loop driver), five private paragraph methods (one per COBOL paragraph), and no static helpers beyond what the shared `Decimals` and EBCDIC transcoding utilities provide module-wide. There is no need for an internal `ProgramRegistry` lookup here because CBACT02C makes no dynamic `CALL` with a variable program name; the only static `CALL` is `CALL 'CEE3ABD'` at L158 which translates to throwing an abend exception, not to a registry lookup.


## Phase 9: Preserved Behaviors and Idiosyncrasies (DO NOT FIX)

The following 12 COBOL behaviors are preserved verbatim per AAP §0.7.1 Minimal Change Clause. None of these are bugs to be "fixed"; they are observable behaviors that downstream consumers may depend on.

1. **SINGLE-DISPLAY pattern (L96 COMMENTED OUT)** — the marquee behavior UNIQUE to CBACT02C in the simple sequential-reader family. The COBOL source at L96 has `*        DISPLAY CARD-RECORD` with the `*` in column 7 making it a COBOL comment line. This means only L78 emits per iteration, producing 1 record-display line per record (not 2 like CBACT01C / CBACT03C / CBCUS01C DOUBLE-DISPLAY siblings). PRESERVED per AAP §0.7.1. The Java translation MUST NOT enable an L96-equivalent emission site; preserve as a Java code comment documenting the suppressed DISPLAY for traceability. Do NOT "fix" by enabling the commented-out emission.

2. **Consistent `'CARDFILE'` literal across all three error messages** — L110 `'ERROR READING CARDFILE'`, L129 `'ERROR OPENING CARDFILE'`, L147 `'ERROR CLOSING CARDFILE'` all use the single-word `'CARDFILE'`. UNLIKE CBCUS01C which has L129 `'CUSTFILE'` (no space) vs L110 / L147 `'CUSTOMER FILE'` (with space) inconsistency, CBACT02C is CONSISTENT. PRESERVED verbatim per AAP §0.7.1. Do NOT invent an inconsistency in the Java translation and do NOT change `'CARDFILE'` to `'CARD FILE'` (two words).

3. **9999-ABEND-PROGRAM and 9910-DISPLAY-IO-STATUS paragraphs use NUMERIC 4-DIGIT PREFIXES** — distinct from CBCUS01C's Z-prefix utility paragraphs. PRESERVED per AAP §0.7.1 in the `@CobolParagraph` annotation values. The Java translation MUST preserve the numeric prefixes exactly: `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`, `0000-CARDFILE-OPEN`, `1000-CARDFILE-GET-NEXT`, `9000-CARDFILE-CLOSE`.

4. **CARD-EXPIRAION-DATE COBOL spelling preserved verbatim** — the field name uses the COBOL misspelling `EXPIRAION` (missing the second `T`). The Java translation MUST preserve this spelling in the `CardRecord` field name (e.g., `cardExpiraionDate`), in any `@CobolField` annotation values if used, and in any error message or Javadoc that references the field. Do NOT correct to `EXPIRATION`. PRESERVED per AAP §0.7.1.

5. **MOVE 999 TO ABCODE before CALL `'CEE3ABD'`** at L157-L158 — the ABCODE value is 999, not 0 or any RETURN-CODE-style value. PRESERVED per AAP §0.7.1; the Java translation MUST raise an abend exception carrying the equivalent ABCODE = 999 metadata so any downstream operator dashboard or test assertion that keys on the abend code continues to function identically.

6. **APPL-RESULT 88-level conditions** at L61-L63 (APPL-AOK = 0, APPL-EOF = 16) — preserve as sealed-type pattern per AAP §0.6.2: a sealed hierarchy `ApplResult { Aok, Eof, Error(int code) }` with `Aok` and `Eof` as singleton instances and `Error` as a record carrying the residual status code. Pattern-matching switch on this hierarchy enforces exhaustiveness at compile time without a `default` clause.

7. **TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES** at L53-L56 — translate to sealed interface per AAP §0.6.2 with `TwoBytesBinary` and `TwoBytesAlpha` permits. This REDEFINES is used inside `9910-DISPLAY-IO-STATUS` at L165-L167 to convert a status nybble to a four-digit decimal display via the `MOVE 0 TO TWO-BYTES-BINARY` / `MOVE IO-STAT2 TO TWO-BYTES-RIGHT` / `MOVE TWO-BYTES-BINARY TO IO-STATUS-0403` chain.

8. **9910-DISPLAY-IO-STATUS emits identical literal in BOTH branches** at L168 and L172 — even though the `IF` branch (status not numeric or starts with '9') and the `ELSE` branch (status numeric and not starting with '9') use different value-conversion paths, the displayed literal `'FILE STATUS IS: NNNN'` is identical. The Java translation MUST emit byte-identical strings from both translated branches. PRESERVED per AAP §0.7.1.

9. **EXIT paragraph terminators** at L116, L134, L152, L174 — paragraphs use `EXIT` as the final statement; control flow relies on PERFORM-return semantics. The Java translation MUST preserve PERFORM-style semantics (each paragraph is a private method called from the appropriate site; no GOTO; no fallthrough between paragraphs). PRESERVED per AAP §0.7.1.

10. **Apache 2.0 license block at L1-L21 NOT reproduced** in either this README or the captured `stdout.txt`. PRESERVED-AS-IS in `app/cbl/CBACT02C.cbl` per AAP §0.2.2 but NOT propagated into Java source files, test fixtures, or this documentation. The license header lives only in the COBOL source.

11. **`Ver:` trailer at L177 NOT reproduced** in this README or `stdout.txt`. PRESERVED-AS-IS in `app/cbl/CBACT02C.cbl` per AAP §0.2.2 but NOT propagated into any Java artifact or this documentation.

12. **CARD-NUM (PAN) and CARD-CVV-CD unmasked in captured stdout** — L78's full-record dump emits both the 16-digit PAN and 3-digit CVV as part of the 150-byte CARD-RECORD. Per AAP §0.7.1 byte-for-byte parity, the captured fixture preserves unmasked bytes via a TEST-ONLY sink. Production logger sinks mask all but the last 4 digits of PAN and all 3 digits of CVV via a SEPARATE sink per AAP §0.7.2. These are different surfaces (test parity vs production logging) and are NOT in conflict.

NONE of these idiosyncrasies are bugs to be "fixed". They are observable behaviors that downstream consumers (operators monitoring logs, parsers reading FILE STATUS codes, byte-for-byte parity assertions) may depend on. Per AAP §0.7.1 Minimal Change Clause, ALL of these are preserved verbatim in the Java translation.

The single most consequential idiosyncrasy in this list is item 1 — the SINGLE-DISPLAY pattern produced by the COMMENTED OUT L96 site. It is the most readily testable behavior at the byte level: a 50-record input fixture produces exactly 52 stdout lines (1 START banner + 50 SINGLE-DISPLAY emissions + 1 END banner), not 102 lines as the DOUBLE-DISPLAY siblings would produce. If a future Java refactor inadvertently enables an L96-equivalent emission site (e.g., by uncommenting a comment or by treating L96 as an active dispatch point during a mechanical port), the byte-for-byte parity assertion will report a 102-vs-52 line-count mismatch on the first run against the captured baseline, which is the desired failure mode.

The second most consequential idiosyncrasy is item 4 — the COBOL `EXPIRAION` spelling. This propagates into the `CardRecord` Java field name `cardExpiraionDate`, into any `@CobolField` annotation that names the field, into any error message referencing the field, and into any property-test generator name. Correcting the spelling would silently desynchronize the Java code from the COBOL source and would break any downstream tooling that performs reverse lookups by field name.

## Phase 10: Java Mapping Invariants

The following invariants apply to the Java translation of CBACT02C and to every test artifact in this folder:

- **One class per COBOL PROGRAM-ID**: `CBACT02C` -> `CbAct02C` in `com.blitzy.carddemo.application.account` per AAP §0.4.1. The `account/` subpackage groups CBACT02C alongside its CBACT01C / CBACT03C / CBACT04C siblings, all of which read account-domain VSAM files. CBCUS01C lives in `customer/` because it reads CUSTFILE.
- **`@CobolProgram("CBACT02C")` annotation** citing original PROGRAM-ID, source path `app/cbl/CBACT02C.cbl`, and translation date per AAP §0.7.1. The annotation is the durable traceability link.
- **`@CobolParagraph` annotations** on each private method translated from a COBOL paragraph: `@CobolParagraph("1000-CARDFILE-GET-NEXT")`, `@CobolParagraph("0000-CARDFILE-OPEN")`, `@CobolParagraph("9000-CARDFILE-CLOSE")`, `@CobolParagraph("9999-ABEND-PROGRAM")`, `@CobolParagraph("9910-DISPLAY-IO-STATUS")` — NUMERIC 4-DIGIT PREFIXES preserved per AAP §0.7.1.
- **Constructor injection** of a single port: `CardRepository` from `com.blitzy.carddemo.domain.port` — no Spring, no container per AAP §0.3.6. This is the SOLE constructor parameter — CBACT02C is among the minimum-dependency programs in the application layer.
- **NO `BigDecimal` arithmetic site** in CbAct02C (CBACT02C performs no math). The only numeric WORKING-STORAGE fields are APPL-RESULT (PIC S9(9) COMP) used as an enum-like discriminator and ABCODE / TIMING (PIC S9(9) BINARY) used for the CEE3ABD abend signal. CARD-ACCT-ID (PIC 9(11)) is parsed as `long` and CARD-CVV-CD (PIC 9(03)) is parsed as `int` in `CardRecord`; no `Decimals` utility call site exists in CbAct02C.
- **`java.time.LocalDate` for CARD-EXPIRAION-DATE** — the SOLE java.time site in CbAct02C. The Java field MUST preserve the COBOL `EXPIRAION` spelling (e.g., `cardExpiraionDate`). Parsing via `DateTimeFormatter` (strict resolver style) from the 10-character PIC X(10) field.
- **NO `java.util.Date` / `java.util.Calendar` / `java.text.SimpleDateFormat`** per AAP §0.6.4 anywhere in the CbAct02C path or `CardRecord` parser / encoder.
- **NO `java.io.File`** — use `java.nio.file.Path`, `Files.newByteChannel`, `SeekableByteChannel` per AAP §0.6.5.
- **NO `ThreadLocal`** — use `ScopedValue` per AAP §0.6.6. CbAct02C is sequential single-threaded; **NO virtual-thread fan-out** because the DISPLAY ordering MUST be preserved per AAP §0.6.6 — the SINGLE-DISPLAY pattern requires deterministic per-record emission in CARDFILE key order.
- **EBCDIC IBM-1047 default codepage** per AAP §0.6.5; per-file override via `application.properties` keys (e.g., `carddemo.file.carddata.charset`). ASCII fixtures use `Charset.forName("US-ASCII")` for test runs.
- **Sealed-type pattern** (per AAP §0.6.2) for: APPL-RESULT 88-level conditions as `ApplResult { Aok, Eof, Error(int code) }` sealed hierarchy; FILE STATUS code hierarchy; TWO-BYTES-ALPHA REDEFINES TWO-BYTES-BINARY as sealed `TwoBytes { Binary(short), Alpha(char, char) }`.
- **Pattern-matching switch** for case-discrimination on FILE STATUS codes and APPL-RESULT — NO `default` branch per AAP §0.7.4; exhaustiveness enforced by compiler.
- **Records, not POJOs**: `CardRecord` (from CVACT02Y) declared as Java `record` per AAP §0.3.2 with 6 named components (`cardNum String`, `cardAcctId long`, `cardCvvCd int`, `cardEmbossedName String`, `cardExpiraionDate LocalDate`, `cardActiveStatus char`) plus a 59-byte `byte[]` FILLER component preserved verbatim.
- **`parse(byte[])` and `encode()`** static factory + instance method pair on `CardRecord` per AAP §0.3.2 byte-level contract; round-trip invariant `parse(buf).encode()` equals the original `buf` for every valid 150-byte buffer.
- **NO Spring, NO Hibernate, NO Spring Batch** — plain Java with constructor injection per AAP §0.3.6.
- **Sequential execution mandated** — NO virtual threads per AAP §0.6.6 because the SINGLE-DISPLAY pattern's deterministic per-record emission ordering is observable. Any reordering changes observable output and is FORBIDDEN.
- **JVM flags**: `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4.
- **NO `--enable-preview`** per AAP §0.7.4.
- **Test-only sink for unmasked PAN+CVV**: production logger sinks apply CARD-NUM masking (all but last 4 digits) AND CARD-CVV-CD masking (all 3 digits) per AAP §0.7.2 via a separate sink; the test driver uses a separate sink that captures unmasked bytes for byte-for-byte parity. These two sinks share the underlying DISPLAY-translation utility but write to different destinations.
- **SINGLE-DISPLAY translation**: ONLY ONE record-dump method-call site exists in the Java translation (translating L78), with a COMMENT in the Java code at the equivalent of L96 documenting the suppressed COBOL DISPLAY for traceability. The Java method translating `1000-CARDFILE-GET-NEXT` MUST NOT emit CARD-RECORD; only the main-loop translation MUST emit at the L78-equivalent site, producing N stdout lines for N records.

Taken together, these 20 invariants describe a faithfully translated, dependency-minimal application-layer class that produces byte-for-byte identical stdout output to the COBOL baseline for the same input fixture. Any departure from these invariants in a future change set MUST be flagged explicitly in `java/MIGRATION_NOTES.md` and reviewed against AAP §0.7.1 Minimal Change Clause before being merged.


## Phase 11: Test Class Override Map

`CbAct02CGoldenTest` extends `GoldenRecordTest` and overrides the protected hook methods that identify the program under test, the input file path, the auxiliary inputs (none for CBACT02C), and the expected output file path. No `runProgram(...)` override is required because the default base-class hook handles single-port wiring for sequential-reader programs.

```java
// com.blitzy.carddemo.tests.golden.CbAct02CGoldenTest
@Override
protected Class<?> programClass() {
    return com.blitzy.carddemo.application.account.CbAct02C.class;
}

@Override
protected Path inputFile() {
    return resolveAppDataPath("carddata.txt");
}

@Override
protected List<Path> auxiliaryInputs() {
    return List.of(); // CBACT02C reads ONE file only - no auxiliaries
}

@Override
protected Path expectedOutputFile() {
    return resolveExpectedOutputPath("cbact02c", "stdout.txt");
}

// NO runProgram(...) override needed; default base-class hook handles single-port wiring
```

The `@Disabled` verification checklist below MUST be satisfied before the `@Disabled` annotation can be removed from `CbAct02CGoldenTest`:

1. Classpath fixture resolver returns non-null for `carddata.txt` (the sole input).
2. Expected `stdout.txt` exists in this folder and is non-empty (captured, not placeholder).
3. Expected output committed via the capture procedure in `java/MIGRATION_NOTES.md` §1.6 — NOT hand-edited.
4. Original `app/data/ASCII/carddata.txt` remains BYTE-IDENTICAL after the test run (test harness MUST NOT mutate the source files per AAP §0.2.2).
5. No production DEPENDENCY references `System.out` for record emission (DISPLAY translations use SLF4J in production; the test driver uses an unmasked test-only sink for byte-for-byte parity).
6. `@CobolProgram("CBACT02C")` annotation is present on the `CbAct02C` class with the source path and translation date.
7. `@CobolParagraph` annotations are present on each translated paragraph method, citing the full original COBOL paragraph name (`1000-CARDFILE-GET-NEXT`, `0000-CARDFILE-OPEN`, `9000-CARDFILE-CLOSE`, `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`).
8. No `--enable-preview` in the test runner's JVM args; no preview JEP 502 / 505 / 507 / 512 usage anywhere in the CbAct02C path or `CardRecord`.
9. The `-XX:+UseCompactObjectHeaders` flag (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) are documented in the test runner's JVM args per AAP §0.3.4.
10. **NO virtual threads** are used in the CbAct02C execution path; sequential CARDFILE read order preserved exactly per AAP §0.6.6.
11. **SINGLE-DISPLAY pattern verified**: 50 records produce 50 record-display lines bracketed by START and END banners = 52 lines total. The L96 emission site in COBOL is COMMENTED OUT; the Java translation MUST NOT emit at the L96-equivalent site.
12. Consistent `'CARDFILE'` literal preserved verbatim in the Java translation across all three error messages (L110, L129, L147).
13. NUMERIC 4-digit paragraph prefix naming preserved in `@CobolParagraph` annotation values (`9999-`, `9910-`, `0000-`, `1000-`, `9000-`).
14. CARD-EXPIRAION-DATE COBOL spelling preserved verbatim in Java field name (`cardExpiraionDate`) and `@CobolField` / Javadoc annotations.

After every checklist item passes, removing the `@Disabled` annotation is the final step that brings `CbAct02CGoldenTest` into the active PR-gate test suite. The test then runs on every PR per AAP §0.6.11 as part of the `mvn -B clean verify` invocation in CI; a failure of this test blocks merge until either the COBOL baseline is re-captured or the regression in `CbAct02C` is fixed. The test is intentionally minimal: it inherits a single `byteForByteParity()` method from `GoldenRecordTest` and provides only the four override hooks documented above; this minimalism is by design so that the test surface remains stable across future refactors of `CbAct02C` and `CardRecord`.

## Phase 12: Capture Procedure Cross-Reference

The cross-references for the canonical COBOL build / run path used to capture this fixture's `stdout.txt`:

- See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build and run path used to capture this fixture's `stdout.txt`.
- The capture procedure exists because the user prompt left this as a `TODO` marker per AAP §0.7.5; the carried-forward TODO is tracked in `MIGRATION_NOTES.md`.
- **Determinism**:
  - NO Clock injection required (CBACT02C does NOT invoke FUNCTION CURRENT-DATE).
  - The single ASCII input `carddata.txt` is read-only; not mutated by any test run.
- **Capture command shape** (illustrative; canonical path documented in `java/MIGRATION_NOTES.md` §1.6): compile CBACT02C with GnuCOBOL or z/OS COBOL -> execute with CARDFILE ASSIGN pointing to `app/data/ASCII/carddata.txt` -> capture stdout -> commit the captured bytes as `stdout.txt` in this folder.
- **PAN+CVV exposure**:
  - COBOL CBACT02C DOES `DISPLAY CARD-RECORD` at L78 on every successful iteration, which includes CARD-NUM (16-byte card-number PAN) AND CARD-CVV-CD (3-byte CVV) unmasked.
  - Per AAP §0.7.2, production Java logs MUST mask all but the last 4 digits of card PANs AND all 3 digits of CVV; the captured `stdout.txt` fixture preserves COBOL unmasked behavior for parity assertion via a test-only sink.
  - The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- **Until capture is performed**: `CbAct02CGoldenTest` is `@Disabled("Awaiting COBOL CBACT02C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the SINGLE-DISPLAY pattern: each of 50 input records MUST emit ONCE (from L78 only, NOT L96 which is COMMENTED OUT), producing 52 total lines including START and END banners.")`; the parity assertion is unreachable until the placeholder is replaced with a real capture.
- **Re-capture trigger**: if the ASCII fixture `carddata.txt` changes OR the COBOL source `app/cbl/CBACT02C.cbl` changes, this fixture MUST be re-captured following the procedure in `java/MIGRATION_NOTES.md` §1.6. Do NOT ad-hoc edit `stdout.txt`; always re-run the capture procedure end-to-end so the captured bytes truly reflect the COBOL baseline.
- **Capture environment notes**: per AAP §0.7.5, the canonical COBOL build environment is documented in `java/MIGRATION_NOTES.md` §1.6 (GnuCOBOL on Linux is the recommended path because the ASCII fixtures are already in the right codepage; the z/OS path requires an EBCDIC-to-ASCII transcoding step on the captured output before commit). Whichever path is used, the LF line-terminator convention from Phase 7 MUST be honored; CRLF terminators introduced by tooling on the capture host MUST be normalized to LF before commit, otherwise the captured baseline will disagree with the Java translation's LF-only output and the parity assertion will fail.
- **Commit hygiene**: the captured `stdout.txt` is committed as a binary-comparable text file. Set the appropriate `.gitattributes` entry if the repository default conversion would alter line terminators on checkout. The captured file's byte-level integrity is the contract; any silent normalization breaks the contract.

## Phase 13: Scenario Inventory (10 scenarios)

The captured fixture MUST exercise the following 10 scenarios. The harness's byte-for-byte parity assertion implicitly covers all of them once the fixture is captured against the COBOL baseline:

1. **Happy path full sequence** — start banner (L71) -> 50 record dumps from L78 only (SINGLE-DISPLAY pattern) -> end banner (L85) = 52 lines total.
2. **Sequential CARDFILE ordering preserved** — records appear in stdout in the exact order they appear in `app/data/ASCII/carddata.txt`; NO virtual threads used (per AAP §0.6.6); the harness is single-threaded.
3. **EOF detection** — final CARDFILE read returns FILE STATUS '10'; `MOVE 16 TO APPL-RESULT` at L99 sets the APPL-EOF condition; `MOVE 'Y' TO END-OF-FILE` at L108 terminates main loop; end banner emitted at L85.
4. **SINGLE-DISPLAY of every record** — 50 records times 1 display each = 50 record dumps; the L96 emission inside `1000-CARDFILE-GET-NEXT` is COMMENTED OUT and emits nothing; only the L78 emission in main loop is active.
5. **CARD-RECORD 150-byte format verbatim** — the L78 record dump emits all 6 named fields + 59-byte FILLER in CVACT02Y declaration order: CARD-NUM (16), CARD-ACCT-ID (11), CARD-CVV-CD (3), CARD-EMBOSSED-NAME (50), CARD-EXPIRAION-DATE (10), CARD-ACTIVE-STATUS (1), FILLER (59).
6. **Consistent `'CARDFILE'` literal preserved** — all three error messages (L110, L129, L147) use `'CARDFILE'` (single word); no inconsistency to preserve; preserved verbatim per AAP §0.7.1.
7. **NUMERIC 4-digit paragraph prefix preserved** — 0000-CARDFILE-OPEN, 1000-CARDFILE-GET-NEXT, 9000-CARDFILE-CLOSE, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS all use 4-digit numeric prefixes (distinct from CBCUS01C's Z-prefix); the `@CobolParagraph` annotation values preserve the numeric prefixes verbatim.
8. **No RETURN-CODE setting on normal completion** — unlike CBTRN02C which sets RETURN-CODE = 4 on rejects, CBACT02C leaves RETURN-CODE at default 0; only `9999-ABEND-PROGRAM` (via `CEE3ABD` at L158 with ABCODE = 999) affects the abend exit code.
9. **Sequential execution mandate** — multiple test runs with the same input produce byte-identical stdout; NO non-deterministic reordering due to virtual threads or parallelism; the SINGLE-DISPLAY pattern's per-record emission ordering is deterministic.
10. **PAN+CVV masking dichotomy** — CARD-NUM (PIC X(16) 16-digit card-number) AND CARD-CVV-CD (PIC 9(03) 3-digit CVV) are preserved unmasked in the captured `stdout.txt` test fixture via a test-only sink; production logger sinks mask all but the last 4 digits of PAN AND all 3 digits of CVV via a separate sink per AAP §0.7.2.

Each scenario above is implicitly exercised by the byte-for-byte parity assertion once `stdout.txt` is captured and committed. There are no scenario-specific test methods on `CbAct02CGoldenTest`; the single `byteForByteParity()` method inherited from `GoldenRecordTest` covers all 10 scenarios in a single assertion because every observable behavior is encoded in the stdout byte sequence. If a future change to `CbAct02C` breaks any of these scenarios, the parity assertion will fail with a detailed byte-level diff that points to the first divergent line, which combined with the COBOL line citations in this README makes root-cause analysis straightforward.

The scenario inventory is intentionally focused on observable behaviors at the byte level. There is NO scenario for "behavior X under a synthetic input" because the harness operates against a single fixed input (`app/data/ASCII/carddata.txt`); any synthetic-input testing is covered by separate property-based or unit tests on `CbAct02C`, `CardRecord`, or `FileCardRepository` that are out of scope for this golden-record fixture. Conversely, the scenarios above ARE the parity contract: any divergence between the Java translation and the COBOL baseline that does not manifest in one of these 10 scenarios is invisible to this fixture and must be detected elsewhere (e.g., by code review or by a different test class targeting the divergent area).



## Contrast Matrix — CBACT02C vs. sibling programs

The following table contrasts CBACT02C with the sibling sequential readers in the CardDemo family and with the validation-only CBTRN01C to make the SINGLE-DISPLAY pattern's uniqueness and the single-port profile's simplicity legible at a glance:

| Aspect | CBACT02C (this) | CBACT01C | CBACT03C | CBCUS01C | CBTRN01C |
|---|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CB (batch) | CB (batch) | CB (batch) | CB (batch) |
| Role | Card file sequential reader | Account file sequential reader | Card xref sequential reader | Customer file sequential reader | Daily transaction validator |
| Source line count | 178 | (similar) | 178 | 178 | 491 |
| File ports | 1 (CARDFILE only) | 1 (ACCTFILE only) | 1 (XREFFILE only) | 1 (CUSTFILE only) | 6 (3 active + 3 unused) |
| Opened-but-unused files | 0 | 0 | 0 | 0 | 3 |
| Output file count | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) | 1 (stdout.txt) |
| JCL driver | `app/jcl/READCARD.jcl` (EXISTS) | `app/jcl/READACCT.jcl` (EXISTS) | `app/jcl/READXREF.jcl` (EXISTS) | `app/jcl/READCUST.jcl` (EXISTS) | NONE |
| Java FQCN subpackage | `account/` | `account/` | `account/` | `customer/` | `transaction/` |
| Record size (bytes) | 150 | 300 | 50 (smallest) | 500 (largest) | (variable) |
| DISPLAY pattern | **SINGLE-DISPLAY** (L78 only; L96 COMMENTED OUT) | DOUBLE-DISPLAY | DOUBLE-DISPLAY | DOUBLE-DISPLAY | (single dump) |
| Per-record stdout lines | 1 (SINGLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 2 (DOUBLE-DISPLAY) | 1-6 (variable diagnostic) |
| Total stdout for 50 records | 52 lines (1 + 50 + 1) | 102 lines (1 + 100 + 1) | 102 lines (1 + 100 + 1) | 102 lines (1 + 100 + 1) | (varies) |
| Paragraph prefix style | NUMERIC 4-digit | NUMERIC 4-digit | NUMERIC 4-digit | Z-prefix utility paragraphs | NUMERIC 4-digit |
| Error message consistency | CONSISTENT 'CARDFILE' (all three) | (verify in sibling fixture) | CONSISTENT 'XREFFILE' (all three) | INCONSISTENT (L129 'CUSTFILE' no space) | (verify) |
| Reject codes | 0 | 0 | 0 | 0 | 0 |
| BigDecimal arithmetic | NO | NO | NO | NO | NO |
| java.time fields | YES (CARD-EXPIRAION-DATE LocalDate) | NO (verify) | NO (no date in xref) | YES (CUST-DOB) | (verify) |
| Sensitive PII | CARD-NUM PAN + CARD-CVV-CD | (account ID; not PAN) | XREF-CARD-NUM PAN | (CUSTFILE PII fields) | (varies) |
| Clock dependency | NO | NO | NO | NO | NO |
| Virtual threads allowed | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) | NO (sequential mandated) |
| Test fixture pattern | input/README + expected/README + 1 placeholder data file | (same) | (same) | (same) | (same; but 5 input fixtures referenced) |
| Parity criticality | secondary | secondary | secondary | secondary | secondary |

CBACT02C is UNIQUE in the simple-sequential-reader family for its SINGLE-DISPLAY pattern — the L96 `DISPLAY CARD-RECORD` is COMMENTED OUT, leaving only L78 as the active record-emission site. This produces 1 record-display line per record (not 2 like CBACT01C / CBACT03C / CBCUS01C DOUBLE-DISPLAY siblings). CBACT02C is the median record size in the family at 150 bytes (versus CBACT01C's 300, CBACT03C's 50, and CBCUS01C's 500), and uses CONSISTENT `'CARDFILE'` messaging like CBACT03C's `'XREFFILE'` consistency (UNLIKE CBCUS01C's L129 spacing inconsistency). CBTRN01C differs by having SIX file ports, three of which are opened-but-unused, and uses variable diagnostic stdout output instead of a fixed-pattern record dump.

## Verbatim Message Catalog Cross-Reference

The 9-entry message catalog enumerated in Phase 5 (counting L168 / L172 as a single catalog entry because both branches emit identical literal) is the COMPLETE catalog for CBACT02C. Cross-references in `java/MIGRATION_NOTES.md` will record any message-string changes between the COBOL baseline and the Java translation; NONE are expected per AAP §0.7.1. ALL three error messages CONSISTENTLY use `'CARDFILE'` (single word) — UNLIKE CBCUS01C's L129 vs L110 / L147 inconsistency, CBACT02C has NO inconsistency to preserve. L96 is COMMENTED OUT (preserved as comment) — this is part of the SINGLE-DISPLAY signature. The Java translation MUST produce byte-identical strings for each of the 8 active catalogued messages and MUST NOT emit at the L96-equivalent site.

The catalog is the canonical reference for any future diff against `stdout.txt`. If the parity assertion ever fails after a Java change, the first action is to compare the failing line against this catalog: a divergence on lines 1 or last is a banner regression (L71 or L85); a divergence on a record line is either a `CardRecord` encoding regression or a sort-order regression; a divergence that introduces a new line (line-count mismatch) is most likely an accidental L96 emission re-enabled in the Java translation, which is the single most common regression risk for this program. Use the catalog plus the COBOL line citations to root-cause the change.

## Source Lineage

The following source files were directly consulted to author this contract. They are REFERENCE only — these files MUST REMAIN UNCHANGED per AAP §0.1.1 / §0.2.2 / §0.7.1:

- `app/cbl/CBACT02C.cbl` (178 lines) — Primary COBOL source; PROGRAM-ID at L23; AUTHOR `AWS` at L24; SELECT clause at L29-L33; FD declaration at L37-L40; PROCEDURE DIVISION at L70; main loop at L74-L81; 5 paragraphs total (1 driver flow plus 3 sequential read / open / close plus 2 numeric-prefix utility paragraphs); SINGLE-DISPLAY pattern with L96 COMMENTED OUT.
- `app/cpy/CVACT02Y.cpy` (14 lines) — CARD-RECORD 150-byte layout: 6 named fields (CARD-NUM PIC X(16), CARD-ACCT-ID PIC 9(11), CARD-CVV-CD PIC 9(03), CARD-EMBOSSED-NAME PIC X(50), CARD-EXPIRAION-DATE PIC X(10), CARD-ACTIVE-STATUS PIC X(01)) + 59-byte FILLER. Note: CARD-EXPIRAION-DATE preserves COBOL misspelling verbatim (missing the second `T` in `EXPIRATION`).
- `app/data/ASCII/carddata.txt` (7,550 bytes; 50 records) — CARDFILE input fixture (REFERENCE; read-only; sequential 150-byte payloads each followed by LF; full 59-byte FILLER included on disk; each on-disk line is 151 bytes = 150-byte payload + 1-byte LF).
- `app/jcl/READCARD.jcl` — JCL driver (REFERENCE only; not invoked by the test harness). Translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardDumpApp.java` per AAP §0.4.1. The JCL declares a single step `STEP05` with `EXEC PGM=CBACT02C`, a STEPLIB DD pointing to `AWS.M2.CARDDEMO.LOADLIB`, a CARDFILE DD pointing to `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`, and SYSOUT and SYSPRINT both set to `SYSOUT=*` so the DISPLAY emissions land in the spool. The Java equivalent maps STEPLIB to the shaded jar's classpath, CARDFILE to a filesystem path supplied via `application.properties`, and SYSOUT to either an SLF4J sink (production) or an in-memory `ByteArrayOutputStream` (test). The Java equivalent does NOT inherit the JCL JOB card's NOTIFY directive or operator-class settings; those are mainframe operator concerns that are out of scope for this refactor per AAP §0.2.2.

All four source files are unchanged by this refactor and remain in the `app/` tree as the canonical reference implementation per AAP §0.2.2. Any future maintenance of these files (e.g., a bug fix in the COBOL source) requires re-capturing the `stdout.txt` baseline per the procedure in `java/MIGRATION_NOTES.md` §1.6 and re-running the parity assertion to confirm the Java translation reflects the COBOL maintenance change correctly.

## Cross-References

The following related files and folders form the dependency graph around this fixture:

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbAct02CGoldenTest.java` — JUnit 5 test class consuming this fixture (`@Disabled` until capture committed)
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class with byte-for-byte parity assertion
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CbAct02C.java` — Class under test
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardRecord.java` — CARD-RECORD from CVACT02Y (150 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/CardRepository.java` — Repository port interface
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileCardRepository.java` — File-backed implementation of `CardRepository`
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardDumpApp.java` — JCL-derived main class (translated from `app/jcl/READCARD.jcl`)
- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbact03c/expected/README.md` (canonical pattern reference; DOUBLE-DISPLAY pattern with NUMERIC 4-digit prefixes and CONSISTENT `'XREFFILE'` messaging)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbact01c/expected/README.md` (when present; DOUBLE-DISPLAY pattern for ACCTFILE)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbcus01c/expected/README.md` (when present; DOUBLE-DISPLAY pattern with Z-prefix paragraphs and `'CUSTFILE'` / `'CUSTOMER FILE'` message inconsistency)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cbtrn01c/expected/README.md` (validation-only program template; SIX file ports vs CBACT02C's ONE port; NO JCL driver vs `READCARD.jcl`)

## Authority References

The following AAP sections are cited in this README and govern the byte-level contract:

- AAP §0.1.1 (refactoring objective — byte-for-byte parity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope — golden-record fixtures under `java/carddemo-tests/src/test/resources/golden/**/*`)
- AAP §0.2.2 (`app/` IMMUTABLE — never modified by the Java refactor)
- AAP §0.3.1 (harness directory convention — `<program>/input/` + `<program>/expected/`)
- AAP §0.3.2 (records, sealed types, ports — translation patterns)
- AAP §0.3.4 (JVM flags — `-XX:+UseCompactObjectHeaders` and Shenandoah generational)
- AAP §0.3.6 (hexagonal architecture — no Spring, no container)
- AAP §0.4.1 (CBACT02C -> CbAct02C in `com.blitzy.carddemo.application.account`; ASCII fixtures REFERENCE only, never copied)
- AAP §0.4.1 also covers the JCL-to-main-class mapping (READCARD.jcl -> ReadCardDumpApp.java) — the harness bypasses the main class and invokes CbAct02C directly for harness simplicity
- AAP §0.6.1 (`Decimals` utility — NOT applicable to CBACT02C because no arithmetic occurs)
- AAP §0.6.2 (sealed-type pattern — APPL-RESULT, FILE STATUS, TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES)
- AAP §0.6.4 (`java.time` — used here for CARD-EXPIRAION-DATE LocalDate, the SOLE java.time site)
- AAP §0.6.5 (`java.nio.file` — EBCDIC IBM-1047 default codepage)
- AAP §0.6.6 (sequential execution — `ScopedValue` replaces `ThreadLocal`; NO virtual threads)
- AAP §0.6.8 (program-by-program mapping checklist)
- AAP §0.6.11 (golden-record harness PR gate — `@Disabled` until capture committed)
- AAP §0.7.1 (Minimal Change Clause — preserve SINGLE-DISPLAY, consistent `'CARDFILE'` messaging, numeric 4-digit paragraph prefixes, COBOL `EXPIRAION` spelling, identical L168 / L172 literal)
- AAP §0.7.2 (no PAN + CVV in production logs — applies to CARD-NUM and CARD-CVV-CD via separate sink)
- AAP §0.7.4 (forbidden features — JEP 502 / 505 / 507 / 512; no `default` branches in pattern-matching switch)
- AAP §0.7.5 (capture procedure documented in `MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline — `[<path>:Lnnn]` for every claim about COBOL behavior)

The cascade above is structured so that each invariant in this README ties back to an AAP section that establishes the governing rule. If a future change set requires deviation from any invariant (for example, if the SINGLE-DISPLAY pattern must be retired in favor of a DOUBLE-DISPLAY in response to some unforeseen requirement), the change MUST be accompanied by an update to the relevant AAP section first, then a re-capture of `stdout.txt`, then a corresponding edit to this README. The README is downstream of the AAP and downstream of the captured baseline; it is never the source of truth on its own. The order of operations matters because reversing it would create a documentation drift between this README and the active AAP, leading to confusion in code review.

The 20 AAP sections listed above are the minimal set governing this fixture; the full AAP (sections 0.1 through 0.8) describes the broader migration context and may be consulted for cross-cutting concerns such as the overall hexagonal architecture, the JVM-tuning baseline, the dependency inventory, or the special analysis on decimal arithmetic. None of those broader concerns directly govern a byte of this fixture, but they govern the surrounding Java translation work that consumes the fixture.

## DO NOT Modify the Fixture Data Without Re-Capture

The `stdout.txt` file (once captured) is COUPLED with the read-only ASCII fixture `app/data/ASCII/carddata.txt` and the Java translation's faithful sequential execution with the SINGLE-DISPLAY pattern. If `carddata.txt` content changes or the COBOL source `app/cbl/CBACT02C.cbl` changes, this fixture MUST be re-captured per `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits to `stdout.txt` without re-capture WILL break byte-for-byte parity and WILL produce silent test failures or, worse, silent test passes against a wrong baseline.

Do NOT invent a `'CUSTFILE'`-style inconsistency for CBACT02C — all three error messages CONSISTENTLY use `'CARDFILE'` (single word) — PRESERVED per AAP §0.7.1. Do NOT change paragraph naming from numeric 4-digit prefixes (`9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`) to Z-prefix style — PRESERVED per AAP §0.7.1. Do NOT mask PAN (CARD-NUM 16-digit card-number) or CVV (CARD-CVV-CD 3-digit) in the captured `stdout.txt` — the test driver uses an unmasked sink for byte-for-byte parity; production logs apply masking via a separate sink per AAP §0.7.2. Do NOT introduce virtual threads — sequential execution is mandated per AAP §0.6.6. **Do NOT enable the L96 emission** — preserve it as a COBOL comment in the COBOL source AND as a Java code comment for traceability; the SINGLE-DISPLAY pattern emits only at L78 per iteration. Do NOT correct the COBOL `EXPIRAION` spelling to `EXPIRATION` — preserve verbatim in Java field names and annotations.

If the captured `stdout.txt` is found out of sync with the COBOL baseline at any point in the future (e.g., because someone edited the file directly or because a transient capture run produced an incorrect baseline), the corrective action is always to re-capture, never to hand-edit the captured file to match the Java output. Hand-editing inverts the parity contract: a hand-edited baseline confirms that the Java code matches the hand-edits rather than confirming that the Java code matches the COBOL baseline. The capture procedure in `java/MIGRATION_NOTES.md` §1.6 is the single source of truth for producing a valid `stdout.txt`; deviations from that procedure WILL silently corrupt the parity guarantee that this entire fixture exists to enforce.

In summary: this README is the contract; `app/cbl/CBACT02C.cbl` is the source of truth; `app/data/ASCII/carddata.txt` is the test input; `stdout.txt` (once captured) is the byte-level baseline; and `CbAct02CGoldenTest` is the gate that ensures the Java translation matches the baseline.

All five artifacts together guarantee that the SINGLE-DISPLAY pattern, the consistent `'CARDFILE'` messaging, the numeric 4-digit paragraph prefixes, the COBOL `EXPIRAION` spelling, and every other preserved-as-is COBOL behavior survive intact across the COBOL-to-Java 25 LTS migration per AAP §0.7.1.

