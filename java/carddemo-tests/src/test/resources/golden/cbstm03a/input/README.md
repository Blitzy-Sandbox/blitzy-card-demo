# CBSTM03A Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files.

CBSTM03A is the **statement generator (DEVIATION-flagged per AAP §0.4.1)**, translated to Java `com.blitzy.carddemo.application.statement.CbStm03A`. It performs a composite read across FIVE input sources and produces THREE outputs (STMTFILE 80-byte FB text, HTMLFILE 100-byte FB HTML, SYSOUT stdout).

The four ASCII fixtures CBSTM03A reads directly (`custdata.txt`, `acctdata.txt`, `cardxref.txt`, plus a synthesized TRNXFILE seeded from `dailytran.txt`-equivalent data) live under `app/data/ASCII/` (immutable per AAP §0.2.2). The Java test harness resolves them via classpath relative path per AAP §0.4.1 ("Read directly from `app/` via classpath relative path; NOT copied").

The conventional `input/` subdirectory exists per AAP §0.6.11 (golden-record harness convention) and is used here as an **explicit documentation surface** for non-obvious decisions:

- WHY no fixture files are copied here.
- WHERE the actual fixtures live.
- HOW the TRNXFILE input is synthesized via SORT.
- WHAT the DEVIATION items mean for the test harness.

## Why this folder is documentation-only

- CBSTM03A's input fixtures are NEVER copied into this folder; they are resolved via classpath relative path from `app/data/ASCII/` per AAP §0.4.1.
- The `app/` tree is **IMMUTABLE** per AAP §0.2.2: the original COBOL source tree must remain unmodified; it stays in the repository as the reference implementation and as the source for golden-record test fixtures.
- The conventional `input/` folder is preserved per the harness convention (AAP §0.6.11 / §0.3.1: `golden/<program>/input/` and `golden/<program>/expected/` per-program folders) only to make the absence of copied fixtures **explicit and discoverable** to developers browsing the harness tree.
- The AAP requirement is binding (verbatim from AAP §0.4.1, ASCII fixture table row applying to all 9 fixtures): "Read directly from `app/` via classpath relative path; NOT copied".
- The five inputs CBSTM03A consumes:

| # | DD name | Source path | Record size | Access mode | Source paragraph | CBSTM03B op |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `XREFFILE` | `app/data/ASCII/cardxref.txt` | 50 bytes | SEQUENTIAL (INDEXED) | `1000-XREFFILE-GET-NEXT` (`app/cbl/CBSTM03A.CBL` L345-L366) | `M03B-READ` |
| 2 | `CUSTFILE` | `app/data/ASCII/custdata.txt` | 500 bytes | RANDOM (INDEXED) | `2000-CUSTFILE-GET` (`app/cbl/CBSTM03A.CBL` L368-L390) | `M03B-READ-K` |
| 3 | `ACCTFILE` | `app/data/ASCII/acctdata.txt` | 300 bytes | RANDOM (INDEXED) | `3000-ACCTFILE-GET` (`app/cbl/CBSTM03A.CBL` L392-L414) | `M03B-READ-K` |
| 4 | `TRNXFILE` | **synthesized** from TRANSACT-like input via SORT (`app/jcl/CREASTMT.JCL` L44-L62) | 350 bytes | SEQUENTIAL (INDEXED) | `8100-TRNXFILE-OPEN`, `8500-READTRNX-READ` (`app/cbl/CBSTM03A.CBL` L730-L762, L818-L853) | `M03B-OPEN`, `M03B-READ` |
| 5 | (indirect) | `app/data/ASCII/carddata.txt` | 150 bytes | NOT OPENED | n/a — referenced indirectly via XREFFILE's XREF-CARD-NUM | n/a |

Therefore, NO ASCII fixture file is copied into this `input/` folder. The Java test harness resolves all four directly-opened inputs via classpath relative path from `app/data/ASCII/`. The TRNXFILE input is built at test-runtime via an in-memory equivalent of the JCL SORT step.

Notes on the carddata.txt indirect role: CBSTM03A never opens or reads `app/data/ASCII/carddata.txt`. The card-to-account-to-customer chain originates from the XREFFILE record's `XREF-CARD-NUM` (`app/cpy/CVACT03Y.cpy` L5); card-level metadata is not part of the statement output, so the production code never touches the card master. The file is listed in the source lineage section for traceability only.

## How the Java test harness resolves fixture paths

The Java test class `com.blitzy.carddemo.tests.golden.CbStm03AGoldenTest` extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` (AAP §0.6.11).

The test class overrides `Path inputFile()` to return the synthesized TRNXFILE path produced by the in-process SORT helper. It additionally provides auxiliary input paths for the three directly-read fixtures via an `auxiliaryInputs()` override (or equivalent constructor wiring). Each path is resolved using a classpath helper such as `getClass().getClassLoader().getResource("app/data/ASCII/cardxref.txt").toURI()` and then converted to `java.nio.file.Path` per AAP §0.6.5 (no `java.io.File` in new code).

The four classpath resource keys are:

- `app/data/ASCII/cardxref.txt`
- `app/data/ASCII/custdata.txt`
- `app/data/ASCII/acctdata.txt`
- synthesized TRNXFILE (produced at runtime by an in-process port of the SORT step; NOT a checked-in fixture)

The TRNXFILE synthesizer reads the source TRANSACT-style records and emits 350-byte fixed-width records:

- Apply `Comparator` ordering equivalent to `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` (`app/jcl/CREASTMT.JCL` L53). The sort key is composite: bytes 263-278 (CARD-NUM, 16 chars, ascending) and then bytes 1-16 (TRAN-ID, 16 chars, ascending).
- Apply record rearrangement equivalent to `OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)` (`app/jcl/CREASTMT.JCL` L54). The OUTREC specification places the 16-byte CARD-NUM at output offset 1-16, the 16-byte TRAN-ID at output offset 17-32, the original 262-byte body slice at output offset 17-278 (overlapping with TRAN-ID — TRAN-ID overlays the leading 16 bytes of the body), and a 50-byte trailing slice at offset 279-328.
- The resulting layout is aligned with the `TRNX-RECORD` layout in `app/cpy/COSTM01.CPY` (350 bytes total).

The synthesizer MUST be deterministic; given identical input bytes, identical output bytes are produced. NO virtual-thread fan-out per AAP §0.6.6 — sort orders MUST be preserved.

ASCII fixtures under `app/data/ASCII/` are **read-only references**; the test harness MUST NOT mutate them per AAP §0.2.2.

EBCDIC vs. ASCII handling: the 9 ASCII fixtures are pre-transcoded for test convenience; production code supports both EBCDIC IBM-1047 default and ASCII per `application.properties` codepage override per AAP §0.6.5.

## Where the actual byte contract lives

The authoritative byte-for-byte contract lives in the **sibling `../expected/` folder's README.md**. That contract document includes:

- The three captured-output files (`stmt_text.txt`, `stmt_html.html`, `stdout.txt`).
- The byte-for-byte parity rules.
- The DEVIATION matrix.
- The verbatim COBOL DISPLAY message catalog.
- The 80-byte STMTFILE record layouts.
- The 100-byte HTMLFILE record layouts.
- The `@Disabled` mandate.

See `../expected/README.md` for the authoritative byte-for-byte parity contract and the captured-output file inventory.

The sibling contract README is organized as follows:

- Phase 0 authority cascade (AAP sections enumerated).
- Phase 1 test identity and Java mapping targets.
- Phase 2 files in `expected/` folder (`README.md`, `stmt_text.txt`, `stmt_html.html`, `stdout.txt`).
- Phase 3 conceptual input universe (the same 5 fixtures documented here from the producer's side).
- Phase 4 statement generation flow (paragraph-by-paragraph).
- Phase 5 verbatim COBOL DISPLAY message catalog.
- Phase 6 DEVIATION items (TIOT/TCB/PSA, ALTER/GO TO, 2D table).
- Phase 7 80-byte STMTFILE and 100-byte HTMLFILE record layouts.
- Phase 8 three output files byte contracts.
- Phase 9 preserved behaviors and idiosyncrasies.
- Phase 10 Java mapping invariants.
- Phase 11 test class override map.
- Phase 12 capture procedure cross-reference.
- Phase 13 scenario inventory.

## DEVIATION Flag Context

Per AAP §0.4.1, CBSTM03A is **DEVIATION-flagged**. The five DEVIATION items binding on the Java translation:

| # | Deviation | CBSTM03A source | Java equivalent | Parity impact |
| --- | --- | --- | --- | --- |
| 1 | TIOT/TCB/PSA control-block inspection | `app/cbl/CBSTM03A.CBL` L235-L260 (LINKAGE SECTION) + L262-L291 (PROCEDURE DIVISION inspection) | Informational DISPLAY of configured file paths from `application.properties` | `stdout.txt` content differs in TIOT section; documented in `java/MIGRATION_NOTES.md` §1.6; informational only — does NOT affect `stmt_text.txt` or `stmt_html.html` byte parity |
| 2 | ALTER + GO TO control flow (0000-START dispatcher) | `app/cbl/CBSTM03A.CBL` L296-L314 (0000-START with ALTER) + GO TO statements at L727, L761, L780, L798, L815, L840, L852 | Explicit Java state machine using sealed `FileDispatchState` interface OR sequential method invocations preserving TRNXFILE -> XREFFILE -> CUSTFILE -> ACCTFILE -> READTRNX -> MAINLINE ordering | NONE — Java execution order MUST produce identical output sequence |
| 3 | 2D in-memory table WS-TRNX-TABLE (51 cards by 10 transactions) | `app/cbl/CBSTM03A.CBL` L225-L230 | Java `record CardTransactionBuffer(String cardNum, List<TranRecord> transactions)` constrained to 51 cards max + 10 transactions per card OR fixed-size `String[51][10]` with explicit count tracking | NONE — buffering semantics preserved; capacity boundary preserved exactly |
| 4 | COMP/COMP-3 arithmetic | `app/cbl/CBSTM03A.CBL` L59-L63 (CR-CNT, TR-CNT, CR-JMP, TR-JMP `PIC S9(4) COMP`) + L64-L65 (WS-TOTAL-AMT `PIC S9(9)V99 COMP-3`) | `int` for counters; `BigDecimal` scale 2 via `com.blitzy.carddemo.domain.util.Decimals` per AAP §0.6.1 | NONE — totals MUST match to last cent |
| 5 | CBSTM03B CALL subroutine | 13 call sites at `app/cbl/CBSTM03A.CBL` L351, L377, L401, L734, L746, L769, L787, L805, L835, L860, L877, L893, L909 | Direct Java method invocations on `com.blitzy.carddemo.application.statement.CbStm03B` collaborator (constructor-injected per AAP §0.3.6 — no container framework); return codes preserved as 2-byte String `'00'`/`'04'`/`'10'`/OTHER | NONE — return codes preserved exactly per AAP §0.7.1 |

ALL deviations are documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.4.1 / §0.7.5. The deviations do NOT introduce behavior changes — they translate z/OS-specific idioms into equivalent Java idioms while preserving identical observable output bytes for the STMTFILE and HTMLFILE outputs.

CBSTM03B return code dichotomy (Java sealed `Cbstm03bResult` hierarchy): at paragraph `1000-XREFFILE-GET-NEXT` (`app/cbl/CBSTM03A.CBL` L353-L362) the `EVALUATE` accepts ONLY `'00'` as success and `'10'` as end-of-file; at the OPEN/READ/CLOSE paragraphs (L736, L748, L771, L789, L807, L862, L879, L895, L911) the `IF` clause accepts `'00'` OR `'04'` as success. This 4-state semantic must be preserved exactly in the Java translation.

WS-TRNX-TABLE capacity boundary (test-scenario implication): unlike CBSTM03B (pure pass-through) or CBTRN02C (record-by-record posting), CBSTM03A pre-buffers up to 510 transactions (51 cards by 10 transactions) in WS-TRNX-TABLE before the `1000-MAINLINE` loop begins (`app/cbl/CBSTM03A.CBL` L225-L230). A test fixture with more than 51 distinct cards OR more than 10 transactions for a single card would exceed buffer capacity and trigger COBOL subscript out-of-range behavior. Test scenarios MUST respect this capacity envelope.

## Stateful file handles across CBSTM03B calls

CBSTM03B is **stateful across CALL invocations**:

- An OPEN persists a file handle in CBSTM03B's internal state (the COBOL runtime's file table entry for `TRNX-FILE`, `XREF-FILE`, `CUST-FILE`, or `ACCT-FILE`).
- Subsequent READ or READ-K calls advance or seek that handle.
- CLOSE releases it.

CBSTM03A's `0000-START` dispatcher state machine relies on this discipline. The dispatcher cycles through this exact sequence:

- TRNXFILE OPEN at paragraph `8100-TRNXFILE-OPEN` (`app/cbl/CBSTM03A.CBL` L730-L762).
- READTRNX (which iteratively reads ALL TRNXFILE records into the 2D in-memory table) at paragraph `8500-READTRNX-READ` (`app/cbl/CBSTM03A.CBL` L818-L853).
- XREFFILE OPEN at paragraph `8200-XREFFILE-OPEN` (`app/cbl/CBSTM03A.CBL` L765-L781).
- CUSTFILE OPEN at paragraph `8300-CUSTFILE-OPEN` (`app/cbl/CBSTM03A.CBL` L783-L799).
- ACCTFILE OPEN at paragraph `8400-ACCTFILE-OPEN` (`app/cbl/CBSTM03A.CBL` L801-L816).
- `1000-MAINLINE` proceeds (`app/cbl/CBSTM03A.CBL` L316-L329).

Implications for the Java translation: file lifecycles MUST be managed deterministically; try-with-resources blocks that release handles between independent CBSTM03B calls are FORBIDDEN — file state MUST persist across method invocations exactly as in COBOL. Explicit close is mandatory via paragraphs:

- `9100-TRNXFILE-CLOSE` at `app/cbl/CBSTM03A.CBL` L856-L870.
- `9200-XREFFILE-CLOSE` at `app/cbl/CBSTM03A.CBL` L872-L887.
- `9300-CUSTFILE-CLOSE` at `app/cbl/CBSTM03A.CBL` L888-L903.
- `9400-ACCTFILE-CLOSE` at `app/cbl/CBSTM03A.CBL` L904-L919.

No GC-driven close. No finalizer-driven close. No try-with-resources between unrelated CALL invocations.

Implications for the test harness: the four directly-opened classpath fixtures (cardxref.txt, custdata.txt, acctdata.txt, synthesized TRNXFILE) are each opened ONCE per test run, kept open through the full statement-generation pass, and closed at the end. This stateful contract is the key reason the test runner cannot simply pass `byte[]` arrays — it must pass `Path` objects that the production code opens with the same OPEN -> READ/READ-K -> CLOSE discipline.

## Cross-reference to the Java test class

The Java test class is `com.blitzy.carddemo.tests.golden.CbStm03AGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbStm03AGoldenTest.java`.

- It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` (AAP §0.6.11).
- Its `inputFile()` override returns the synthesized TRNXFILE Path produced by the in-process SORT helper.
- Its `expectedOutputFile()` override returns `resolveExpectedOutputPath("cbstm03a", "stmt_text.txt")` — confirming the expected files live in the sibling `../expected/` directory, NOT in this folder.
- Its `expectedOutputs()` override returns three `ExpectedOutput` entries: `stmt_text.txt`, `stmt_html.html`, `stdout.txt`, all resolved from `../expected/`.
- The test is `@Disabled("Awaiting COBOL capture per MIGRATION_NOTES.md section 1.6; DEVIATION documentation pending")` until BOTH conditions are met:
  - The three placeholder data files (`stmt_text.txt`, `stmt_html.html`, `stdout.txt`) in `../expected/` are replaced with genuine COBOL captures.
  - The DEVIATION items are fully documented in `java/MIGRATION_NOTES.md` §1.6.
- The `@Disabled` pattern is mandated by AAP §0.6.11: "Initial test scaffolding may use placeholder expected files marked `@Disabled` until COBOL captures are available".

The Java class under test is `com.blitzy.carddemo.application.statement.CbStm03A` (per AAP §0.3.1 and §0.4.1, CBSTM03A lives in the `application/statement/` subpackage, NOT `util/`).

The callable subroutine translation is `com.blitzy.carddemo.application.statement.CbStm03B` (same subpackage; sibling utility class, constructor-injected collaborator).

File I/O uses ONLY `java.nio.file` per AAP §0.6.5 (no `java.io.File`).

Domain records used by the test (all in `com.blitzy.carddemo.domain.record`):

- `TrnxRecord` (from `app/cpy/COSTM01.CPY`) — 350-byte TRNX-RECORD.
- `CardXrefRecord` (from `app/cpy/CVACT03Y.cpy`) — 50-byte CARD-XREF-RECORD.
- `CustomerLegacyRecord` (from `app/cpy/CUSTREC.cpy`) — 500-byte CUSTOMER-RECORD.
- `AccountRecord` (from `app/cpy/CVACT01Y.cpy`) — 300-byte ACCOUNT-RECORD.

Sealed `FileStatus` hierarchy per AAP §0.6.10: `{Ok, EndOfFile, NotFound, DuplicateKey, IoError(int code, String description)}`. The COBOL FILE STATUS taxonomy maps one-to-one onto the sealed hierarchy.

The class under test carries the `@CobolProgram("CBSTM03A")` annotation citing original PROGRAM-ID, source path `app/cbl/CBSTM03A.CBL`, and translation date per AAP §0.7.1.

## Capture procedure cross-reference

The canonical COBOL build/run path used to capture the expected outputs in `../expected/` is documented in `java/MIGRATION_NOTES.md` §1.6.

The capture procedure exists because the user prompt originally left the COBOL build/run path as an unresolved marker; AAP §0.7.5 resolves that marker by directing the procedure into `java/MIGRATION_NOTES.md` §1.6.

The full capture command shape (illustrative; canonical path documented in `java/MIGRATION_NOTES.md` §1.6):

- Compile CBSTM03A + CBSTM03B (GnuCOBOL or z/OS COBOL).
- Execute STEP010 SORT (`app/jcl/CREASTMT.JCL` L44-L55) + STEP020 IDCAMS REPRO (`app/jcl/CREASTMT.JCL` L56-L62) to produce TRNXFILE.
- Execute STEP040 EXEC PGM=CBSTM03A (`app/jcl/CREASTMT.JCL` L79) with DD assignments pointing to the ASCII fixtures (read-only) and the synthesized TRNXFILE.
- Capture STMTFILE -> `stmt_text.txt`.
- Capture HTMLFILE -> `stmt_html.html`.
- Capture SYSOUT -> `stdout.txt`.

TIOT inspection DEVIATION impact on parity: the captured `stdout.txt` will contain z/OS-specific TIOT entries that are NOT byte-reproducible in a Java environment. The Java translation emits informational equivalent output (DD names from `application.properties`), and the parity assertion for `stdout.txt` is relaxed to a structural assertion (substring presence, line count) rather than byte-exact equality. The byte-exact assertions for `stmt_text.txt` and `stmt_html.html` remain in full force per AAP §0.6.5.

Re-capture trigger: if the ASCII fixtures change OR the COBOL source changes OR the SORT helper logic changes, the fixture MUST be re-captured. Do NOT ad-hoc edit any of the three output files; always re-run the capture procedure.

Until capture is performed: `CbStm03AGoldenTest` remains `@Disabled` and the parity assertion is unreachable.

## Source lineage

NONE of these files are copied into this folder; they are listed for traceability per AAP §0.8.1.

`app/cbl/CBSTM03A.CBL` (924 lines) — Primary COBOL source: statement generator.

- `PROGRAM-ID` at L2 (`CBSTM03A`); author tag at L3.
- FD STMT-FILE at L44-L45 (`FD-STMTFILE-REC PIC X(80)`).
- FD HTML-FILE at L46-L47 (`FD-HTMLFILE-REC PIC X(100)`).
- COMP variables at L59-L63 (`CR-CNT`, `TR-CNT`, `CR-JMP`, `TR-JMP` all `PIC S9(4) COMP`).
- COMP-3 variable at L64-L65 (`WS-TOTAL-AMT PIC S9(9)V99 COMP-3`).
- `WS-FL-DD` at L67 with literal values `'TRNXFILE'`, `'XREFFILE'`, `'CUSTFILE'`, `'ACCTFILE'`, `'READTRNX'`.
- `WS-M03B-AREA` at L71-L83 (mirrors CBSTM03B LINKAGE SECTION field-for-field).
- `WS-TRNX-TABLE` at L225-L230 (the 51-by-10 2D buffer array).
- LINKAGE SECTION control-block structures at L239-L260 (`PSA-BLOCK`, `TCB-BLOCK`, `TIOT-BLOCK`, `TIOT-ENTRY`).
- PROCEDURE DIVISION TIOT inspection at L262-L291 (the z/OS-specific control-block walk that is DEVIATION-flagged).
- `0000-START` dispatcher at L296-L314 (the ALTER + GO TO state machine).
- `1000-MAINLINE` at L316-L329 (the outer loop driven by XREFFILE).
- `1000-XREFFILE-GET-NEXT` at L345-L366 (sequential XREFFILE read).
- `2000-CUSTFILE-GET` at L368-L390 (keyed CUSTFILE read by XREF-CUST-ID).
- `3000-ACCTFILE-GET` at L392-L414 (keyed ACCTFILE read by XREF-ACCT-ID).
- `4000-TRNXFILE-GET` at L416-L456 (in-memory table lookup for buffered transactions).
- `5000-CREATE-STATEMENT` at L458-L504 (statement assembly).
- `5100-WRITE-HTML-HEADER` at L506-L555 (HTML header emission).
- `5200-WRITE-HTML-NMADBS` at L558-L672 (HTML name/address/balance section emission).
- `6000-WRITE-TRANS` at L675-L723 (transaction-line emission to both STMTFILE and HTMLFILE).
- `8100-FILE-OPEN` ALTER target at L726-L728 (the paragraph that ALTER rewrites).
- `8100-TRNXFILE-OPEN` at L730-L762 (TRNXFILE open + initial READ).
- `8200-XREFFILE-OPEN` at L765-L781 (XREFFILE open).
- `8300-CUSTFILE-OPEN` at L783-L799 (CUSTFILE open).
- `8400-ACCTFILE-OPEN` at L801-L816 (ACCTFILE open).
- `8500-READTRNX-READ` at L818-L853 (iterative TRNXFILE read into WS-TRNX-TABLE).
- `9100-TRNXFILE-CLOSE` through `9400-ACCTFILE-CLOSE` at L856-L919 (the four CLOSE paragraphs).
- `9999-ABEND-PROGRAM` at L921-L923 (ABEND handler invoking `CEE3ABD`).

`app/cbl/CBSTM03B.CBL` (230 lines) — Callable file-services subroutine.

- `PROCEDURE DIVISION USING LK-M03B-AREA` at L114.
- 88-levels on LK-M03B-OPER at L103-L108 (`OPEN='O'`, `CLOSE='C'`, `READ='R'`, `READ-K='K'`, `WRITE='W'`, `REWRITE='Z'`).
- Per-file paragraphs:
  - `1000-TRNXFILE-PROC` at L133-L155 (sequential).
  - `2000-XREFFILE-PROC` at L157-L179 (sequential).
  - `3000-CUSTFILE-PROC` at L181-L204 (keyed).
  - `4000-ACCTFILE-PROC` at L206-L229 (keyed).

`app/jcl/CREASTMT.JCL` (97 lines) — JCL driver.

- STEP010 SORT at L44-L55 with `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` at L53 and `OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)` at L54.
- STEP020 IDCAMS REPRO at L56-L62 (loads sorted records into the KSDS).
- STEP030 IEFBR14 cleanup at L66-L75 (deletes prior-run STMTFILE/HTMLFILE).
- STEP040 EXEC PGM=CBSTM03A at L79 with DDs TRNXFILE (L83), XREFFILE (L84), ACCTFILE (L85), CUSTFILE (L86), STMTFILE (L87-L91), HTMLFILE (L92-L96), SYSOUT (L82).

`app/cpy/COSTM01.CPY` (39 lines) — TRNX-RECORD layout: 350 bytes total.

- TRNX-KEY = `TRNX-CARD-NUM PIC X(16)` (L22) + `TRNX-ID PIC X(16)` (L23) — 32-byte composite key.
- TRNX-REST = `TRNX-TYPE-CD PIC X(02)` (L25) + `TRNX-CAT-CD PIC 9(04)` (L26) + `TRNX-SOURCE PIC X(10)` (L27) + `TRNX-DESC PIC X(100)` (L28) + `TRNX-AMT PIC S9(09)V99` (L29) + `TRNX-MERCHANT-ID PIC 9(09)` (L30) + `TRNX-MERCHANT-NAME PIC X(50)` (L31) + `TRNX-MERCHANT-CITY PIC X(50)` (L32) + `TRNX-MERCHANT-ZIP PIC X(10)` (L33) + `TRNX-ORIG-TS PIC X(26)` (L34) + `TRNX-PROC-TS PIC X(26)` (L35) + `FILLER PIC X(20)` (L36).

`app/cpy/CVACT03Y.cpy` (11 lines) — CARD-XREF-RECORD layout: 50 bytes.

- `XREF-CARD-NUM PIC X(16)` (L5) + `XREF-CUST-ID PIC 9(09)` (L6) + `XREF-ACCT-ID PIC 9(11)` (L7) + `FILLER PIC X(14)` (L8).

`app/cpy/CUSTREC.cpy` (26 lines) — CUSTOMER-RECORD layout: 500 bytes.

- `CUST-ID PIC 9(09)` key (L5).
- First/middle/last name `PIC X(25)` each (L6-L8).
- Address lines 1-3 `PIC X(50)` each (L9-L11).
- State/country/ZIP (L12-L14).
- Phone numbers (L15-L16).
- SSN (L17), GOVT-ID (L18), DOB (L19), EFT-ACCT-ID (L20), PRI-CARD-HOLDER-IND (L21), FICO score (L22).
- `FILLER PIC X(168)` (L23).

`app/cpy/CVACT01Y.cpy` (20 lines) — ACCOUNT-RECORD layout: 300 bytes.

- `ACCT-ID PIC 9(11)` key (L5).
- `ACCT-ACTIVE-STATUS PIC X(01)` (L6).
- 5 BigDecimal monetary fields `PIC S9(10)V99` at L7-L9 and L13-L14.
- 3 LocalDate `PIC X(10)` fields at L10-L12.
- `ACCT-ADDR-ZIP` (L15), `ACCT-GROUP-ID` (L16), `FILLER PIC X(178)` (L17).

ASCII fixtures (REFERENCE only; classpath-resolved):

- `app/data/ASCII/cardxref.txt` — XREFFILE input fixture.
- `app/data/ASCII/custdata.txt` — CUSTFILE input fixture.
- `app/data/ASCII/acctdata.txt` — ACCTFILE input fixture.
- `app/data/ASCII/carddata.txt` — Card master (NOT directly opened by CBSTM03A; listed for completeness; referenced indirectly via the XREFFILE's XREF-CARD-NUM chain).
- `app/data/ASCII/dailytran.txt` — TRANSACT-style daily-transaction fixture used to seed TRNXFILE via the test-time SORT helper.

## Authority references

- AAP §0.1.1 — Core refactoring objective; immutable `app/` reference.
- AAP §0.2.1 — In-scope: golden-record harness inputs; REFERENCE-only fixtures (`java/carddemo-tests/src/test/resources/golden/**/*`).
- AAP §0.2.2 — `app/` tree IMMUTABLE.
- AAP §0.3.1 — Refactored structure; `golden/<program>/` per-program folders.
- AAP §0.4.1 — Fixture file table: 9 ASCII fixtures REFERENCE only, NOT copied; CBSTM03A DEVIATION flag entry; CbStm03A in `application/statement/` subpackage.
- AAP §0.6.1 — `BigDecimal` and `Decimals` utility for COMP-3 monetary values.
- AAP §0.6.5 — File I/O exactness: byte-for-byte round-trip; EBCDIC IBM-1047 default; per-file codepage overrides; `java.nio.file` mandate.
- AAP §0.6.6 — Sequential execution mandated; NO virtual-thread fan-out where reordering would break observable output.
- AAP §0.6.10 — Sealed-type hierarchies: `FileStatus`, `AccountStatus`, etc.
- AAP §0.6.11 — Golden-record harness design; `@Disabled` until COBOL capture available.
- AAP §0.6.12 — Architectural override: no container framework, no relational database by default.
- AAP §0.7.1 — Refactor discipline: translate faithfully even when DEVIATION-flagged; minimal change clause.
- AAP §0.7.5 — Capture procedure documentation -> `java/MIGRATION_NOTES.md` §1.6.
- AAP §0.8.1 — Citation discipline: every claim cites source location with line/section locator.

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep` placeholders, or any other content. The `README.md` IS the directory's marker; by virtue of its presence it preserves the empty `input/` folder in version control without requiring a `.gitkeep`.

- Do NOT copy `app/data/ASCII/cardxref.txt`, `custdata.txt`, `acctdata.txt`, `carddata.txt`, `dailytran.txt`, or any other fixture into this folder; classpath resolution from `app/data/ASCII/` is the canonical access path per AAP §0.4.1.
- Do NOT add a pre-built synthesized TRNXFILE — the test harness builds it deterministically at runtime via an in-process equivalent of the JCL SORT step (`app/jcl/CREASTMT.JCL` L44-L62).
- Do NOT add raw COBOL source — `CBSTM03A.CBL` and `CBSTM03B.CBL` remain ONLY in `app/cbl/` per AAP §0.2.2 (the `app/` tree is IMMUTABLE).
- Do NOT add `.bin`, `.dat`, or `.csv` files — captured COBOL outputs (`stmt_text.txt`, `stmt_html.html`, `stdout.txt`) belong in the sibling `../expected/` folder.
- Do NOT add container-framework, ORM, migration-tool, or relational-database configuration — the architectural override per AAP §0.6.12 explicitly removes these from scope.
- Do NOT add subdirectories.
- Do NOT rename this file; the path `java/carddemo-tests/src/test/resources/golden/cbstm03a/input/README.md` is referenced by tooling and by other harness documentation.
- If a future test scenario requires additional fixture coverage, add the fixture to `app/data/ASCII/` only with explicit re-capture instructions in `java/MIGRATION_NOTES.md` §1.6 — the `app/` tree update would require a separate user authorization per AAP §0.2.2.
