# CBACT03C (Card Cross-Reference File Sequential Reader) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is a documentation-only marker for the input side of the CBACT03C golden-record fixture. The conceptual input is `app/data/ASCII/cardxref.txt`, which is REFERENCED via classpath from `app/data/ASCII/` and is NEVER copied into this folder per AAP §0.4.1. The folder exists as a sibling of `../expected/` per the AAP §0.3.1 harness directory convention (`<program>/input/` + `<program>/expected/`).

The consuming Java test class is `com.blitzy.carddemo.tests.golden.CbAct03CGoldenTest`, which extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and is annotated `@Disabled` per AAP §0.6.11 until the COBOL baseline capture for `../expected/stdout.txt` is committed. Citations follow `[<path>:Lnnn]` per AAP §0.8.1.

CBACT03C is part of the DOUBLE-DISPLAY sequential-reader family alongside CBACT01C, CBACT02C, and CBCUS01C; each successful read emits the 50-byte CARD-XREF-RECORD twice on stdout, once inside `1000-XREFFILE-GET-NEXT` (at L96) and once back in the main loop (at L78).

## Why this folder is documentation-only

1. **No data files**: per AAP §0.4.1, ASCII fixtures live ONLY in `app/data/ASCII/` and are REFERENCED via classpath relative path from this test resources tree. They are NEVER copied here. Copying `cardxref.txt` would double the storage footprint, introduce drift risk between the COBOL reference implementation and the Java parity harness, and violate the AAP §0.2.2 immutability mandate for the `app/` tree.
2. **No `.gitkeep`**: this README itself serves as the persistence marker for version control; an explicit `.gitkeep` would be redundant.
3. **No COBOL source mirrors**: per AAP §0.2.2, the `app/` tree (including `app/cbl/CBACT03C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/jcl/READXREF.jcl`) is IMMUTABLE; mirroring its contents here would create a parallel source of truth and violate the single-source mandate.
4. **No captured COBOL output**: that belongs in the parallel `../expected/` folder (along with its own README); the input side is purely conceptual contract documentation.
5. **Pair convention**: the AAP §0.3.1 harness convention `<program>/input/` + `<program>/expected/` is preserved by this folder's existence; the empty-folder-with-README pattern matches the canonical sibling `golden/cbcus01c/input/README.md`.

Cross-references in the sibling folder and pattern template:

- The contract for the captured output lives at `../expected/README.md` (see `../expected/stdout.txt` once captured per `java/MIGRATION_NOTES.md` §1.6).
- The canonical template for this input-side marker pattern lives at `java/carddemo-tests/src/test/resources/golden/cbcus01c/input/README.md`.

## Conceptual input contract (documented; data referenced from app/data/ASCII/)

CBACT03C opens EXACTLY ONE file (XREFFILE-FILE) and reads it sequentially. The single input is sourced from `app/data/ASCII/cardxref.txt` via classpath reference and is NEVER copied into this folder per AAP §0.4.1. The single output (`stdout.txt`) lives in the sibling `../expected/` folder. This section documents the conceptual contract; data location is documented in subsection 2.4.

### FILE-CONTROL SELECT clause + FD record layout

CBACT03C has EXACTLY ONE file port (XREFFILE-FILE), declared via a single SELECT/FD pair. The SELECT clause is at `app/cbl/CBACT03C.cbl:L29-L33`; the FD declaration is at `app/cbl/CBACT03C.cbl:L37-L40`.

| Aspect | Value | Source |
|---|---|---|
| SELECT name | `XREFFILE-FILE` | `[app/cbl/CBACT03C.cbl:L29]` |
| ASSIGN TO | `XREFFILE` (DD name) | `[app/cbl/CBACT03C.cbl:L29]` |
| ORGANIZATION | INDEXED | `[app/cbl/CBACT03C.cbl:L30]` |
| ACCESS MODE | SEQUENTIAL | `[app/cbl/CBACT03C.cbl:L31]` |
| RECORD KEY | `FD-XREF-CARD-NUM` | `[app/cbl/CBACT03C.cbl:L32]` |
| FILE STATUS | `XREFFILE-STATUS` | `[app/cbl/CBACT03C.cbl:L33]` |
| FD record layout | `FD-XREFFILE-REC` (50 bytes total) | `[app/cbl/CBACT03C.cbl:L37-L40]` |
| FD-XREF-CARD-NUM | PIC X(16) — 16 bytes | `[app/cbl/CBACT03C.cbl:L39]` |
| FD-XREF-DATA | PIC X(34) — 34 bytes | `[app/cbl/CBACT03C.cbl:L40]` |
| Working-storage record | `CARD-XREF-RECORD` (50 bytes) via `COPY CVACT03Y.` at L45 | `[app/cpy/CVACT03Y.cpy]` |

CVACT03Y CARD-XREF-RECORD layout (50 bytes total):

| Field | PIC | Bytes | Java Type | Notes |
|---|---|---|---|---|
| XREF-CARD-NUM | X(16) | 16 | String | 16-digit card-number — sensitive PAN per AAP §0.7.2 |
| XREF-CUST-ID | 9(09) | 9 | long | Numeric customer ID |
| XREF-ACCT-ID | 9(11) | 11 | long | Numeric account ID |
| FILLER | X(14) | 14 | byte[14] | Preserved verbatim; OMITTED in ASCII fixture (reconstituted by adapter) |

Total = 16 + 9 + 11 + 14 = 50 bytes (matches FD declaration). The ASCII fixture provides 36-byte payloads + LF; the 14-byte FILLER is reconstituted with spaces (ASCII 0x20) by the Java `FileCardXrefRepository` adapter before passing the 50-byte buffer to `CardXrefRecord.parse(byte[])` per AAP §0.6.5.

### Sequential read flow paragraphs (5 paragraphs)

CBACT03C contains 5 paragraphs total (1 sequential read + 1 open + 1 close + 2 utility paragraphs in addition to the unnamed main PROCEDURE DIVISION entry). All translate to private methods on `CbAct03C` annotated with `@CobolParagraph` per AAP §0.7.1.

| Paragraph | Line | Role | Notes |
|---|---|---|---|
| `0000-XREFFILE-OPEN` | L118-L134 | OPEN INPUT XREFFILE-FILE; verify status `'00'`; abend on failure | NUMERIC 4-digit prefix |
| `1000-XREFFILE-GET-NEXT` | L92-L116 | READ XREFFILE-FILE INTO CARD-XREF-RECORD; on status `'00'` emit `DISPLAY CARD-XREF-RECORD` at L96 (FIRST half of DOUBLE-DISPLAY); on status `'10'` set END-OF-FILE; on other status abend | NUMERIC 4-digit prefix |
| `9000-XREFFILE-CLOSE` | L136-L152 | CLOSE XREFFILE-FILE; verify status `'00'`; abend on failure | NUMERIC 4-digit prefix |
| `9999-ABEND-PROGRAM` | L154-L158 | DISPLAY `'ABENDING PROGRAM'`; MOVE 999 TO ABCODE; CALL `'CEE3ABD'` | NUMERIC 4-digit prefix (distinct from CBCUS01C's Z-prefix) |
| `9910-DISPLAY-IO-STATUS` | L161-L174 | Convert IO-STATUS bytes to 4-digit display; DISPLAY `'FILE STATUS IS: NNNN' IO-STATUS-04` from BOTH IF (L168) and ELSE (L172) branches with identical literal | NUMERIC 4-digit prefix (distinct from CBCUS01C's Z-prefix) |

Main PROCEDURE DIVISION orchestration:

- Main PROCEDURE DIVISION entry at `[app/cbl/CBACT03C.cbl:L70]`.
- Start banner DISPLAY at L71: `'START OF EXECUTION OF PROGRAM CBACT03C'`.
- Main loop at L74-L81: `PERFORM UNTIL END-OF-FILE = 'Y'` with inner conditional `DISPLAY CARD-XREF-RECORD` at L78 (SECOND half of DOUBLE-DISPLAY pattern).
- End banner DISPLAY at L85: `'END OF EXECUTION OF PROGRAM CBACT03C'`.
- GOBACK at L87.

NO writes, NO rewrites, NO updates. The Java translation MUST faithfully open/close XREFFILE-FILE and produce the DOUBLE-DISPLAY for each successful read. The Java class `CbAct03C` will be annotated `@CobolProgram("CBACT03C")` per AAP §0.7.1, and each translated paragraph method MUST carry a `@CobolParagraph` Javadoc annotation citing the original COBOL paragraph name verbatim (including the NUMERIC 4-digit prefix, NOT a Z-prefix).

### Verbatim DISPLAY message catalog (7 entries)

The catalog below enumerates every distinct DISPLAY message string in CBACT03C. The L168/L172 pair is counted as a single entry (entry 8) because both branches emit the identical literal. The Java translation MUST produce byte-identical strings for each message.

| # | Verbatim Bytes | Lines | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBACT03C'` `` | L71 | Unconditional start banner |
| 2 | `` `'END OF EXECUTION OF PROGRAM CBACT03C'` `` | L85 | Unconditional end banner |
| 3 | `DISPLAY CARD-XREF-RECORD` (50-byte record verbatim) | L78, L96 | DOUBLE-DISPLAY emission per successful read (L96 inside 1000-XREFFILE-GET-NEXT; L78 in main loop) |
| 4 | `` `'ERROR READING XREFFILE'` `` | L110 | 1000-XREFFILE-GET-NEXT non-EOF error path |
| 5 | `` `'ERROR OPENING XREFFILE'` `` | L129 | 0000-XREFFILE-OPEN failure (CONSISTENT 'XREFFILE' single-word literal) |
| 6 | `` `'ERROR CLOSING XREFFILE'` `` | L147 | 9000-XREFFILE-CLOSE failure path |
| 7 | `` `'ABENDING PROGRAM'` `` | L155 | 9999-ABEND-PROGRAM before CALL `'CEE3ABD'` |
| 8 | `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 | L168, L172 | 9910-DISPLAY-IO-STATUS — both IF/ELSE branches emit identical literal |

ALL messages preserve EXACT bytes including embedded spaces and colon placement. UNLIKE CBCUS01C (which has L129 `'CUSTFILE'` no-space vs L110/L147 `'CUSTOMER FILE'` with-space inconsistency), CBACT03C uses CONSISTENT `'XREFFILE'` single-word across all three error messages (L110, L129, L147). There is NO inconsistency to preserve. All messages PRESERVED verbatim per AAP §0.7.1.

### Fixture data location (source-of-truth)

The fixture data lives at `app/data/ASCII/cardxref.txt` and is REFERENCED via classpath, never copied.

| Aspect | Value |
|---|---|
| Source-of-truth path | `app/data/ASCII/cardxref.txt` |
| File size | 1,850 bytes |
| Record count | 50 records |
| On-disk record length | 37 bytes = 36-byte payload + 1 LF terminator |
| 14-byte FILLER status | OMITTED on disk; reconstituted by FileCardXrefRepository adapter with spaces (ASCII 0x20) |
| Reconstituted record length | 50 bytes |
| Classpath resolver | `resolveAppDataPath("cardxref.txt")` from `GoldenRecordTest` base class |
| Mutability | Read-only; per AAP §0.2.2 the `app/` tree MUST remain unchanged after test runs |
| Codepage (production) | EBCDIC IBM-1047 per AAP §0.6.5; per-file override via `application.properties` |
| Codepage (test fixtures) | `Charset.forName("US-ASCII")` since fixtures are pre-transcoded |

NEVER copy `cardxref.txt` into this folder. The Java test class resolves the path via classpath; the AAP §0.4.1 mandate of "ASCII fixtures REFERENCED via classpath from `app/data/ASCII/`, NEVER COPIED" is absolute.

## Cross-reference to the Java test class

- Java test class FQCN: `com.blitzy.carddemo.tests.golden.CbAct03CGoldenTest` (extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`).
- Class under test FQCN: `com.blitzy.carddemo.application.account.CbAct03C` (NOTE: `account/` subpackage alongside CBACT01C and CBACT02C — distinct from CBCUS01C's `customer/` subpackage).
- Sole constructor port: `com.blitzy.carddemo.domain.port.CardXrefRepository`.
- File-backed adapter: `com.blitzy.carddemo.adapter.file.FileCardXrefRepository`.
- Domain record: `com.blitzy.carddemo.domain.record.CardXrefRecord` (50 bytes from CVACT03Y).
- JCL-derived main class: `com.blitzy.carddemo.app.ReadCardXrefDumpApp` (translated from `app/jcl/READXREF.jcl` per AAP §0.4.1).
- Test class invocation: BYPASSES `ReadCardXrefDumpApp` and invokes `CbAct03C` directly via the constructor-injected `CardXrefRepository` port for finer-grained test wiring (consistent with sibling sequential-reader tests).
- Test class hook methods on `GoldenRecordTest`:
  - `programClass()` returns `CbAct03C.class`.
  - `inputFile()` returns `resolveAppDataPath("cardxref.txt")`.
  - `auxiliaryInputs()` returns `List.of()` (CBACT03C reads ONE file only — no auxiliaries).
  - `expectedOutputFile()` returns `resolveExpectedOutputPath("cbact03c", "stdout.txt")`.
- `@Disabled` until `../expected/stdout.txt` is captured per AAP §0.6.11.

## Capture procedure cross-reference

The capture procedure for `../expected/stdout.txt` is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. The procedure MUST run the COBOL CBACT03C program against the SAME `app/data/ASCII/cardxref.txt` fixture that the Java test will reference. Re-capture trigger: if `app/data/ASCII/cardxref.txt` OR `app/cbl/CBACT03C.cbl` OR `app/cpy/CVACT03Y.cpy` changes, the fixture MUST be re-captured. Do NOT hand-edit `../expected/stdout.txt`.

Determinism: CBACT03C does NOT invoke FUNCTION CURRENT-DATE; no `Clock` dependency is required; captured output is naturally deterministic given fixed input.

PAN exposure: the 16-byte XREF-CARD-NUM is captured UNMASKED in `../expected/stdout.txt` via a test-only sink for byte-for-byte parity. Production logger sinks apply card-PAN masking (all but last 4 digits) per AAP §0.7.2 via a separate sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.

Until the capture is performed and committed, `CbAct03CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **DOUBLE-DISPLAY pattern**: each successful read produces TWO `DISPLAY CARD-XREF-RECORD` lines on stdout (L96 inside 1000-XREFFILE-GET-NEXT, then L78 in main loop). PRESERVED verbatim per AAP §0.7.1.
- **102-line stdout total**: 1 START banner + (50 x 2 DOUBLE-DISPLAY) + 1 END banner = 102 lines for the 50-record `cardxref.txt` fixture.
- **Sequential XREFFILE read order**: records appear in stdout in the exact order they appear in `app/data/ASCII/cardxref.txt`; NO virtual threads, NO reordering, NO parallelism per AAP §0.6.6.
- **Consistent `'XREFFILE'` literal**: all three error messages (L110, L129, L147) use single-word `'XREFFILE'`. UNLIKE CBCUS01C's L129 vs L110/L147 inconsistency, CBACT03C is CONSISTENT. PRESERVED verbatim per AAP §0.7.1.
- **Numeric 4-digit paragraph prefixes**: 0000-/1000-/9000-/9999-/9910- paragraph names preserved verbatim in `@CobolParagraph` annotation values. UNLIKE CBCUS01C's utility paragraphs, CBACT03C uses numeric prefixes throughout. PRESERVED per AAP §0.7.1.
- **ABCODE = 999 before CEE3ABD CALL**: at L157 MOVE 999 TO ABCODE, then CALL `'CEE3ABD'` at L158; the Java translation MUST raise an abend exception carrying equivalent ABCODE=999 metadata.
- **APPL-RESULT 88-level conditions**: APPL-AOK=0, APPL-EOF=16 at L61-L63 — translate as sealed-type pattern `ApplResult { Aok, Eof, Error(int code) }` per AAP §0.6.2.
- **TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES**: at L53-L56 — translate as sealed interface `TwoBytes { Binary, Alpha }` per AAP §0.6.2; used inside 9910-DISPLAY-IO-STATUS at L165-L167.
- **9910-DISPLAY-IO-STATUS emits identical literal in both branches**: L168 and L172 emit `'FILE STATUS IS: NNNN' IO-STATUS-04` — the Java translation MUST produce byte-identical strings from both translated branches. PRESERVED per AAP §0.7.1.
- **EXIT paragraph terminators**: paragraphs use `EXIT` as final statement (L116, L134, L152, L174); the Java translation preserves PERFORM-return semantics — each paragraph is a private method; no GOTO; no fallthrough. PRESERVED per AAP §0.7.1.
- **14-byte FILLER reconstitution**: the ASCII fixture omits the 14-byte FILLER (records are 36 bytes on disk + 1 LF); the Java `FileCardXrefRepository` adapter MUST reconstitute with spaces (ASCII 0x20). The L78/L96 DISPLAY emits all 50 bytes (36 digits + 14 spaces) per record. PRESERVED per AAP §0.7.1.
- **No reject codes**: CBACT03C emits NO reject codes. It is a pure read-and-display utility.
- **No arithmetic**: CBACT03C performs NO math; no `Decimals` utility call is required per AAP §0.6.1.
- **No date fields**: CARD-XREF-RECORD has NO date fields; no `java.time` site is required in `CbAct03C` per AAP §0.6.4 (distinct from CBCUS01C's CUST-DOB).
- **No deterministic Clock**: CBACT03C does NOT invoke FUNCTION CURRENT-DATE; captured output is deterministic by construction.
- **Sole CardXrefRepository port**: no opened-but-unused files. CBACT03C has only ONE port; distinct from CBTRN01C's 6-port profile.
- **PAN exposure dichotomy**: XREF-CARD-NUM (16-byte 16-digit card-number) is captured UNMASKED in `../expected/stdout.txt` via a test-only sink for byte-for-byte parity; production logger sinks mask all but last 4 digits via a separate sink per AAP §0.7.2. The two sinks are distinct surfaces.
- **`java.nio.file` only** (AAP §0.6.5): the referenced fixture is accessed via `Files.newByteChannel`, `Files.readAllBytes`, or `SeekableByteChannel`. EBCDIC IBM-1047 is the production default; ASCII fixtures use `Charset.forName("US-ASCII")` for test convenience.
- **`ScopedValue` replaces `ThreadLocal`** (AAP §0.6.6): batch-run context (run ID, processing date, tenant) flows through `ScopedValue.where(...).run(...)`. No `ThreadLocal` in new code.
- **Pattern-matching switch with exhaustiveness** (AAP §0.7.4): any switch on a `FileStatus` sealed hierarchy uses pattern matching with compiler-enforced exhaustiveness; no branch hides cases.

NONE of these invariants are bugs to be "fixed". Per the AAP §0.7.1 Minimal Change Clause, ALL are preserved verbatim.

## Source lineage

All sources listed below are REFERENCE-only and remain UNCHANGED per AAP §0.1.1, §0.2.2, and §0.7.1.

- `app/cbl/CBACT03C.cbl` (178 lines) — Primary COBOL source; `PROGRAM-ID. CBACT03C.` at L23; `AUTHOR. AWS.` at L24; SELECT clause at L29-L33; FD declaration at L37-L40; `COPY CVACT03Y.` at L45; PROCEDURE DIVISION at L70; main loop at L74-L81; 5 paragraphs total (1 sequential read at L92, 1 open at L118, 1 close at L136, plus 2 numeric-prefix utility paragraphs `9999-ABEND-PROGRAM` at L154 and `9910-DISPLAY-IO-STATUS` at L161).
- `app/cpy/CVACT03Y.cpy` (12 lines) — CARD-XREF-RECORD 50-byte layout: 3 named fields (XREF-CARD-NUM PIC X(16), XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11)) + 14-byte FILLER.
- `app/data/ASCII/cardxref.txt` (1,850 bytes; 50 records of 36-byte payloads + LF each) — XREFFILE input fixture (REFERENCE only via classpath; read-only; the 14-byte FILLER is omitted on disk and reconstituted by the Java adapter).
- `app/jcl/READXREF.jcl` — JCL driver (REFERENCE only; not invoked by the test). Invokes `EXEC PGM=CBACT03C` at STEP05; the XREFFILE DD points to `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`. Translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardXrefDumpApp.java` per AAP §0.4.1.

## Authority references

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS.
- AAP §0.2.1 — Golden-record fixtures in scope under `java/carddemo-tests/src/test/resources/golden/**/*`.
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE.
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` + `<program>/expected/`.
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports.
- AAP §0.4.1 — CBACT03C -> CbAct03C in `com.blitzy.carddemo.application.account`; ASCII fixtures REFERENCED via classpath, NEVER copied.
- AAP §0.6.1 — `Decimals` utility (NOT applicable to CBACT03C — no math).
- AAP §0.6.2 — Sealed-type pattern (REDEFINES, 88-level taxonomies).
- AAP §0.6.4 — `java.time` only; never `java.util.Date`/`Calendar` (NOTE: CBACT03C has NO date fields).
- AAP §0.6.5 — `java.nio.file`; EBCDIC IBM-1047 default; per-file codepage override.
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely; sequential execution preserved (NO virtual threads).
- AAP §0.6.8 — Program-by-program mapping checklist entry for CBACT03C.
- AAP §0.6.11 — Golden-record harness PR gate; `@Disabled` until COBOL captures committed.
- AAP §0.7.1 — Minimal Change Clause; preserve DOUBLE-DISPLAY, consistent `'XREFFILE'` messaging, numeric 4-digit paragraph prefixes, ABCODE=999, CEE3ABD CALL.
- AAP §0.7.2 — No card PAN in production logs (applies to XREF-CARD-NUM via a separate sink — test fixture preserves unmasked bytes).
- AAP §0.7.4 — Forbidden features; no `default` branches that hide cases.
- AAP §0.7.5 — Capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]`.
- Sibling pattern reference: `java/carddemo-tests/src/test/resources/golden/cbcus01c/input/README.md` (canonical authoring pattern; same 8-H2 + 4-H3 structure; same DOUBLE-DISPLAY family — but CUSTFILE not XREFFILE, `customer/` subpackage not `account/`, Z-prefix paragraphs not numeric, INCONSISTENT `'CUSTFILE'`/`'CUSTOMER FILE'` not CONSISTENT `'XREFFILE'`).

## DO NOT add files here

This folder MUST contain exactly ONE file: this README. Adding any other file (data, captured output, source mirror, `.gitkeep`) is FORBIDDEN.

Specifically:

- Captured COBOL output belongs in `../expected/stdout.txt`, not here.
- The ASCII fixture `app/data/ASCII/cardxref.txt` is REFERENCED via classpath per AAP §0.4.1; copying it here would violate the single-source mandate.
- COBOL source files (`app/cbl/CBACT03C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/jcl/READXREF.jcl`) are IMMUTABLE per AAP §0.2.2; mirroring them here would create a parallel source of truth.
- Do NOT introduce `.gitkeep` — this README serves as the version-control marker.
- Do NOT introduce a `stdout.txt` or any capture output here — that belongs in `../expected/`.

If new input fixtures are needed for additional CBACT03C test scenarios beyond byte-for-byte parity, they MUST be added under `app/data/ASCII/` per AAP §0.2.2 modification rules and the user prompt's mandate that the `app/` tree remain the single source of fixture truth — NOT here.
