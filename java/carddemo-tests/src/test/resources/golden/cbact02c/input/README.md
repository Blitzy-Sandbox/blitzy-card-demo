# CBACT02C (Card File Sequential Reader) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is a documentation-only marker for the input side of the CBACT02C golden-record fixture. The conceptual input is `app/data/ASCII/carddata.txt`, which is REFERENCED via classpath from `app/data/ASCII/` and is NEVER copied into this folder per AAP §0.4.1. The folder exists as a sibling of `../expected/` per the AAP §0.3.1 harness directory convention (`<program>/input/` + `<program>/expected/`).

The consuming Java test class is `com.blitzy.carddemo.tests.golden.CbAct02CGoldenTest`, which extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and is annotated `@Disabled` per AAP §0.6.11 until the COBOL baseline capture for `../expected/stdout.txt` is committed. Citations follow `[<path>:Lnnn]` per AAP §0.8.1.

CBACT02C belongs to the simple sequential-reader family but is the SOLE member of that family using the SINGLE-DISPLAY pattern: the L96 `DISPLAY CARD-RECORD` inside `1000-CARDFILE-GET-NEXT` is COMMENTED OUT, so only the L78 `DISPLAY CARD-RECORD` in the main loop fires per successful read. CBACT01C, CBACT03C, and CBCUS01C all use the DOUBLE-DISPLAY pattern; CBACT02C is the lone SINGLE-DISPLAY exception.

## Why this folder is documentation-only

1. **No data files**: per AAP §0.4.1, ASCII fixtures live ONLY in `app/data/ASCII/` and are REFERENCED via classpath relative path from this test resources tree. They are NEVER copied here. Copying `carddata.txt` would double the storage footprint, introduce drift risk between the COBOL reference implementation and the Java parity harness, and violate the AAP §0.2.2 immutability mandate for the `app/` tree.
2. **No `.gitkeep`**: this README itself serves as the persistence marker for version control; an explicit `.gitkeep` would be redundant.
3. **No COBOL source mirrors**: per AAP §0.2.2, the `app/` tree (including `app/cbl/CBACT02C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/jcl/READCARD.jcl`) is IMMUTABLE; mirroring its contents here would create a parallel source of truth and violate the single-source mandate.
4. **No captured COBOL output**: that belongs in the parallel `../expected/` folder (along with its own README); the input side is purely conceptual contract documentation.
5. **Pair convention**: the AAP §0.3.1 harness convention `<program>/input/` + `<program>/expected/` is preserved by this folder's existence; the empty-folder-with-README pattern matches the canonical siblings `golden/cbcus01c/input/README.md` and `golden/cbact03c/input/README.md`.

Cross-references to sibling folders and pattern templates:

- The contract for the captured output lives at `../expected/README.md` (see `../expected/stdout.txt` once captured per `java/MIGRATION_NOTES.md` §1.6).
- The canonical templates for this input-side marker pattern live at `java/carddemo-tests/src/test/resources/golden/cbcus01c/input/README.md` (DOUBLE-DISPLAY, Z-prefix variant) and `java/carddemo-tests/src/test/resources/golden/cbact03c/input/README.md` (DOUBLE-DISPLAY, numeric-prefix variant — closest pattern relative; same `account/` subpackage, same numeric 4-digit prefixes, but DOUBLE-DISPLAY rather than SINGLE-DISPLAY).

## Conceptual input contract (documented; data referenced from app/data/ASCII/)

CBACT02C opens EXACTLY ONE file (CARDFILE-FILE) and reads it sequentially. The single input is sourced from `app/data/ASCII/carddata.txt` via classpath reference and is NEVER copied into this folder per AAP §0.4.1. The single output (`stdout.txt`) lives in the sibling `../expected/` folder. This section documents the conceptual contract; data location is documented in subsection 2.4.

### FILE-CONTROL SELECT clause + FD record layout

CBACT02C has EXACTLY ONE file port (CARDFILE-FILE), declared via a single SELECT/FD pair. The SELECT clause is at `app/cbl/CBACT02C.cbl:L29-L33`; the FD declaration is at `app/cbl/CBACT02C.cbl:L37-L40`.

| Aspect | Value | Source |
|---|---|---|
| SELECT name | `CARDFILE-FILE` | `[app/cbl/CBACT02C.cbl:L29]` |
| ASSIGN TO | `CARDFILE` (DD name) | `[app/cbl/CBACT02C.cbl:L29]` |
| ORGANIZATION | INDEXED | `[app/cbl/CBACT02C.cbl:L30]` |
| ACCESS MODE | SEQUENTIAL | `[app/cbl/CBACT02C.cbl:L31]` |
| RECORD KEY | `FD-CARD-NUM` | `[app/cbl/CBACT02C.cbl:L32]` |
| FILE STATUS | `CARDFILE-STATUS` | `[app/cbl/CBACT02C.cbl:L33]` |
| FD record layout | `FD-CARDFILE-REC` (150 bytes total) | `[app/cbl/CBACT02C.cbl:L37-L40]` |
| FD-CARD-NUM | PIC X(16) — 16 bytes | `[app/cbl/CBACT02C.cbl:L39]` |
| FD-CARD-DATA | PIC X(134) — 134 bytes | `[app/cbl/CBACT02C.cbl:L40]` |
| Working-storage record | `CARD-RECORD` (150 bytes) via `COPY CVACT02Y.` at L45 | `[app/cpy/CVACT02Y.cpy]` |

CVACT02Y CARD-RECORD layout (7 named fields + 59-byte FILLER, 150 bytes total):

| Field | PIC | Bytes | Java Type | Notes |
|---|---|---|---|---|
| CARD-NUM | X(16) | 16 | String | 16-digit card-number — sensitive PAN per AAP §0.7.2 |
| CARD-ACCT-ID | 9(11) | 11 | long | Numeric account ID |
| CARD-CVV-CD | 9(03) | 3 | int | 3-digit CVV — SENSITIVE per AAP §0.7.2 |
| CARD-EMBOSSED-NAME | X(50) | 50 | String | Cardholder name |
| CARD-EXPIRAION-DATE | X(10) | 10 | LocalDate | COBOL field name spelled `EXPIRAION` (sic) — PRESERVED verbatim, NOT corrected to `EXPIRATION` per AAP §0.7.1 |
| CARD-ACTIVE-STATUS | X(01) | 1 | char | Y/N flag |
| FILLER | X(59) | 59 | byte[59] | Preserved verbatim (spaces on disk in this fixture) |

Total = 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes (matches FD declaration at L37-L40). The ASCII fixture stores the full 150-byte payload + 1 LF terminator per record; the 59-byte FILLER is INCLUDED on disk (mostly spaces) and the Java `FileCardRepository` adapter passes the full 150-byte buffer to `CardRecord.parse(byte[])` with NO reconstitution required (distinct from the CBACT03C `cardxref.txt` adapter, which reconstitutes a 14-byte FILLER omitted on disk).

### Sequential read flow paragraphs (5 paragraphs)

CBACT02C contains 5 paragraphs total (1 sequential read + 1 open + 1 close + 2 utility paragraphs in addition to the unnamed main PROCEDURE DIVISION entry). All translate to private methods on `CbAct02C` annotated with `@CobolParagraph` per AAP §0.7.1.

| Paragraph | Line | Role | Notes |
|---|---|---|---|
| `0000-CARDFILE-OPEN` | L118-L134 | OPEN INPUT CARDFILE-FILE; verify status `'00'`; abend on failure | NUMERIC 4-digit prefix |
| `1000-CARDFILE-GET-NEXT` | L92-L116 | READ CARDFILE-FILE INTO CARD-RECORD; on status `'00'` set APPL-RESULT=0 (L96 DISPLAY COMMENTED OUT — SINGLE-DISPLAY signature); on status `'10'` set END-OF-FILE; on other status abend | NUMERIC 4-digit prefix; L96 suppressed DISPLAY documented in Java code comment for traceability |
| `9000-CARDFILE-CLOSE` | L136-L152 | CLOSE CARDFILE-FILE; verify status `'00'`; abend on failure | NUMERIC 4-digit prefix |
| `9999-ABEND-PROGRAM` | L154-L158 | DISPLAY `'ABENDING PROGRAM'`; MOVE 999 TO ABCODE; CALL `'CEE3ABD'` | NUMERIC 4-digit prefix (distinct from CBCUS01C's Z-prefix) |
| `9910-DISPLAY-IO-STATUS` | L161-L174 | Convert IO-STATUS bytes to 4-digit display; DISPLAY `'FILE STATUS IS: NNNN' IO-STATUS-04` from BOTH IF (L168) and ELSE (L172) branches with identical literal | NUMERIC 4-digit prefix (distinct from CBCUS01C's Z-prefix) |

Main PROCEDURE DIVISION orchestration:

- Main PROCEDURE DIVISION entry at `[app/cbl/CBACT02C.cbl:L70]`.
- Start banner DISPLAY at L71: `'START OF EXECUTION OF PROGRAM CBACT02C'`.
- Main loop at L74-L81: `PERFORM UNTIL END-OF-FILE = 'Y'` with inner conditional `DISPLAY CARD-RECORD` at L78 (SOLE record-dump emission per iteration — SINGLE-DISPLAY signature).
- End banner DISPLAY at L85: `'END OF EXECUTION OF PROGRAM CBACT02C'`.
- GOBACK at L87.

NO writes, NO rewrites, NO updates. The Java translation MUST faithfully open/close CARDFILE-FILE and produce EXACTLY ONE `DISPLAY CARD-RECORD` per successful read (at the L78-equivalent site). The Java translation MUST NOT activate an equivalent of the L96 DISPLAY — it is preserved as a code comment for traceability per AAP §0.7.1 Minimal Change Clause, citing `app/cbl/CBACT02C.cbl:L96` as the source of the comment. The Java class `CbAct02C` will be annotated `@CobolProgram("CBACT02C")` per AAP §0.7.1, and each translated paragraph method MUST carry a `@CobolParagraph` Javadoc annotation citing the original COBOL paragraph name verbatim (including the NUMERIC 4-digit prefix).

### Verbatim DISPLAY message catalog (8 entries)

The catalog below enumerates every DISPLAY message string in CBACT02C, INCLUDING the L96 COMMENTED-OUT entry documented as PRESERVED COMMENTED OUT. The Java translation MUST produce byte-identical strings for each ACTIVE message; the L96 entry is NOT emitted.

| # | Verbatim Bytes | Lines | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBACT02C'` `` | L71 | Unconditional start banner — ACTIVE |
| 2 | `` `'END OF EXECUTION OF PROGRAM CBACT02C'` `` | L85 | Unconditional end banner — ACTIVE |
| 3 | `DISPLAY CARD-RECORD` (150-byte record verbatim) | L78 | SOLE record-dump emission per successful read — ACTIVE (main loop after PERFORM returns) |
| 4 | `*        DISPLAY CARD-RECORD` (150-byte record) | L96 | COMMENTED OUT in `1000-CARDFILE-GET-NEXT` — PRESERVED verbatim per AAP §0.7.1; the Java translation MUST NOT emit this; the Java code SHOULD include a code comment documenting the suppressed source for traceability |
| 5 | `` `'ERROR READING CARDFILE'` `` | L110 | 1000-CARDFILE-GET-NEXT non-EOF error path — ACTIVE on read failure |
| 6 | `` `'ERROR OPENING CARDFILE'` `` | L129 | 0000-CARDFILE-OPEN failure path — ACTIVE on open failure |
| 7 | `` `'ERROR CLOSING CARDFILE'` `` | L147 | 9000-CARDFILE-CLOSE failure path — ACTIVE on close failure |
| 8 | `` `'ABENDING PROGRAM'` `` (L155) + `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 (L168, L172) | L155, L168, L172 | 9999-ABEND-PROGRAM banner (L155) and 9910-DISPLAY-IO-STATUS (L168 IF / L172 ELSE — both branches emit identical literal) — ACTIVE |

ALL active error messages use CONSISTENT single-word `'CARDFILE'` literal across L110 (read error), L129 (open error), and L147 (close error). UNLIKE CBCUS01C (which has an L129 `'CUSTFILE'` no-space vs L110/L147 `'CUSTOMER FILE'` with-space inconsistency), CBACT02C is CONSISTENT — there is NO whitespace inconsistency to preserve. Catalog entry 4 (L96) is the SOLE COMMENTED-OUT DISPLAY in CBACT02C and is the marquee SINGLE-DISPLAY signature distinguishing this program from the rest of the sequential-reader family.

### Fixture data location (source-of-truth)

The fixture data lives at `app/data/ASCII/carddata.txt` and is REFERENCED via classpath, never copied.

| Aspect | Value |
|---|---|
| Source-of-truth path | `app/data/ASCII/carddata.txt` |
| File size | 7,550 bytes |
| Record count | 50 records |
| On-disk record length | 151 bytes = 150-byte CARD-RECORD payload + 1 LF terminator |
| FILLER status | INCLUDED on disk (59-byte FILLER mostly spaces); NO reconstitution required by adapter (distinct from CBACT03C `cardxref.txt` which omits its 14-byte FILLER) |
| Effective record length | 150 bytes |
| Classpath resolver | `resolveAppDataPath("carddata.txt")` from `GoldenRecordTest` base class |
| Mutability | Read-only; per AAP §0.2.2 the `app/` tree MUST remain unchanged after test runs |
| Codepage (production) | EBCDIC IBM-1047 per AAP §0.6.5; per-file override via `application.properties` |
| Codepage (test fixtures) | `Charset.forName("US-ASCII")` since fixtures are pre-transcoded |

NEVER copy `carddata.txt` into this folder. The Java test class resolves the path via classpath; the AAP §0.4.1 mandate of "ASCII fixtures REFERENCED via classpath from `app/data/ASCII/`, NEVER COPIED" is absolute.

## Cross-reference to the Java test class

- Java test class FQCN: `com.blitzy.carddemo.tests.golden.CbAct02CGoldenTest` (extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`).
- Class under test FQCN: `com.blitzy.carddemo.application.account.CbAct02C` (NOTE: `account/` subpackage alongside CBACT01C and CBACT03C — distinct from CBCUS01C's `customer/` subpackage).
- Sole constructor port: `com.blitzy.carddemo.domain.port.CardRepository`.
- File-backed adapter: `com.blitzy.carddemo.adapter.file.FileCardRepository`.
- Domain record: `com.blitzy.carddemo.domain.record.CardRecord` (150 bytes from CVACT02Y).
- JCL-derived main class: `com.blitzy.carddemo.app.ReadCardDumpApp` (translated from `app/jcl/READCARD.jcl` per AAP §0.4.1).
- Test class invocation: BYPASSES `ReadCardDumpApp` and invokes `CbAct02C` directly via the constructor-injected `CardRepository` port for finer-grained test wiring (consistent with sibling sequential-reader tests).
- Test class hook methods on `GoldenRecordTest`:
  - `programClass()` returns `CbAct02C.class`.
  - `inputFile()` returns `resolveAppDataPath("carddata.txt")`.
  - `auxiliaryInputs()` returns `List.of()` (CBACT02C reads ONE file only — no auxiliaries).
  - `expectedOutputFile()` returns `resolveExpectedOutputPath("cbact02c", "stdout.txt")`.
- `@Disabled` until `../expected/stdout.txt` is captured per AAP §0.6.11.

Sequential execution mandate per AAP §0.6.6: virtual threads are FORBIDDEN inside `CbAct02C` because DISPLAY output ordering is observable; any reordering of `DISPLAY CARD-RECORD` emissions breaks byte-for-byte parity. The Java translation MUST execute the main loop serially in the same CARDFILE read order as the COBOL baseline.

No-deterministic-clock note: CBACT02C does NOT invoke `FUNCTION CURRENT-DATE`; no timestamp generation occurs anywhere in the program. NO injected `Clock` is required for reproducible captures. This significantly simplifies the test fixture compared to CBTRN02C, which requires deterministic clock injection.

## Capture procedure cross-reference

The capture procedure for `../expected/stdout.txt` is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. The procedure MUST run the COBOL CBACT02C program against the SAME `app/data/ASCII/carddata.txt` fixture that the Java test will reference. Re-capture trigger: if `app/data/ASCII/carddata.txt` OR `app/cbl/CBACT02C.cbl` OR `app/cpy/CVACT02Y.cpy` changes, the fixture MUST be re-captured. Do NOT hand-edit `../expected/stdout.txt`.

Determinism: CBACT02C does NOT invoke FUNCTION CURRENT-DATE; no `Clock` dependency is required; captured output is naturally deterministic given fixed input.

PAN + CVV exposure dichotomy: the 16-byte CARD-NUM and 3-byte CARD-CVV-CD are captured UNMASKED in `../expected/stdout.txt` via a test-only sink for byte-for-byte parity. Production logger sinks apply card-PAN masking (all but last 4 digits) and CVV redaction per AAP §0.7.2 via a separate sink. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact. The two sinks are distinct surfaces.

Until the capture is performed and committed, `CbAct02CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **SINGLE-DISPLAY pattern (L96 commented out)**: the marquee idiosyncrasy unique to CBACT02C among the sequential-reader family. The L96 `*        DISPLAY CARD-RECORD` is COMMENTED OUT in `1000-CARDFILE-GET-NEXT`; only the L78 `DISPLAY CARD-RECORD` in the main loop fires per successful read. PRESERVED verbatim per AAP §0.7.1. The Java translation MUST NOT activate the L96 emission — it is preserved as a code comment for traceability.
- **52-line stdout total**: 1 START banner + (50 x 1 SINGLE-DISPLAY) + 1 END banner = 52 lines for the 50-record `carddata.txt` fixture. CONTRAST with CBACT01C, CBACT03C, and CBCUS01C, which all produce 102 lines (DOUBLE-DISPLAY: 1 + (50 x 2) + 1).
- **Sequential CARDFILE read order**: records appear in stdout in the exact order they appear in `app/data/ASCII/carddata.txt`; NO virtual threads, NO reordering, NO parallelism per AAP §0.6.6.
- **Consistent `'CARDFILE'` single-word literal**: all three error messages (L110 read, L129 open, L147 close) use single-word `'CARDFILE'`. UNLIKE CBCUS01C's L129 `'CUSTFILE'` vs L110/L147 `'CUSTOMER FILE'` inconsistency, CBACT02C is CONSISTENT. PRESERVED verbatim per AAP §0.7.1.
- **NUMERIC 4-digit paragraph prefixes**: `0000-`, `1000-`, `9000-`, `9999-`, `9910-` paragraph names preserved verbatim in `@CobolParagraph` annotation values. UNLIKE CBCUS01C's Z-prefix utility paragraphs, CBACT02C uses numeric prefixes throughout. PRESERVED per AAP §0.7.1.
- **CARD-EXPIRAION-DATE COBOL spelling preserved**: the COBOL field name is `CARD-EXPIRAION-DATE` (note the sic spelling `EXPIRAION` — missing the second `T`). The Java record field name MUST preserve this spelling verbatim, e.g., `cardExpiraionDate` (NOT `cardExpirationDate`). PRESERVED per AAP §0.7.1.
- **MOVE 999 TO ABCODE before CALL `'CEE3ABD'`**: at L157 set ABCODE to 999, then CALL `'CEE3ABD'` at L158; the Java translation MUST raise an abend exception carrying equivalent ABCODE=999 metadata.
- **APPL-RESULT 88-level conditions**: APPL-AOK=0, APPL-EOF=16 at L61-L63 — translate as sealed-type pattern `ApplResult { Aok, Eof, Error(int code) }` per AAP §0.6.2.
- **TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES**: at L53-L56 — translate as sealed interface `TwoBytes { Binary, Alpha }` per AAP §0.6.2; used inside 9910-DISPLAY-IO-STATUS at L165-L167.
- **9910-DISPLAY-IO-STATUS emits identical literal in both branches**: L168 and L172 emit `'FILE STATUS IS: NNNN' IO-STATUS-04` — the Java translation MUST produce byte-identical strings from both translated branches. PRESERVED per AAP §0.7.1.
- **EXIT paragraph terminators**: paragraphs use `EXIT` as final statement (L116, L134, L152, L174); the Java translation preserves PERFORM-return semantics — each paragraph is a private method; no GOTO; no fallthrough. PRESERVED per AAP §0.7.1.
- **No reject codes**: CBACT02C emits NO reject codes (unlike CBTRN02C). It is a pure read-and-display utility.
- **No arithmetic**: CBACT02C performs NO math; no `Decimals` utility call is required per AAP §0.6.1; CARD-ACCT-ID is parsed as `long`, CARD-CVV-CD is parsed as `int`.
- **CARD-EXPIRAION-DATE is the SOLE `java.time` site**: the 10-byte PIC X(10) field is the only `java.time.LocalDate` site in `CbAct02C` per AAP §0.6.4 (distinct from CBACT03C, which has NO date fields at all in its 50-byte CARD-XREF-RECORD layout).
- **No deterministic Clock**: CBACT02C does NOT invoke FUNCTION CURRENT-DATE; captured output is deterministic by construction.
- **Sole CardRepository port**: no opened-but-unused files. CBACT02C reads ONE file only; distinct from CBTRN01C's 6-port profile.
- **PAN + CVV exposure dichotomy**: CARD-NUM (16-byte 16-digit card-number) AND CARD-CVV-CD (3-byte 3-digit CVV) are captured UNMASKED in `../expected/stdout.txt` via a test-only sink for byte-for-byte parity; production logger sinks mask CARD-NUM (all but last 4 digits) and redact CARD-CVV-CD via a separate sink per AAP §0.7.2. The two sinks are distinct surfaces.
- **`java.nio.file` only** (AAP §0.6.5): the referenced fixture is accessed via `Files.newByteChannel`, `Files.readAllBytes`, or `SeekableByteChannel`. EBCDIC IBM-1047 is the production default; ASCII fixtures use `Charset.forName("US-ASCII")` for test convenience.
- **`ScopedValue` replaces `ThreadLocal`** (AAP §0.6.6): batch-run context (run ID, processing date, tenant) flows through `ScopedValue.where(...).run(...)`. No `ThreadLocal` in new code.
- **`@CobolProgram("CBACT02C")` annotation required** (AAP §0.7.1): the Java class `CbAct02C` MUST carry the `@CobolProgram` annotation citing the original PROGRAM-ID, source path `app/cbl/CBACT02C.cbl`, and translation date.

NONE of these invariants are bugs to be "fixed". Per the AAP §0.7.1 Minimal Change Clause, ALL are preserved verbatim — especially the SINGLE-DISPLAY pattern (L96 commented out) and the `CARD-EXPIRAION-DATE` sic spelling.

## Source lineage

All sources listed below are REFERENCE-only and remain UNCHANGED per AAP §0.1.1, §0.2.2, and §0.7.1.

- `app/cbl/CBACT02C.cbl` (178 lines) — Primary COBOL source; `PROGRAM-ID. CBACT02C.` at L23; `AUTHOR. AWS.` at L24; SELECT clause at L29-L33; FD declaration at L37-L40; `COPY CVACT02Y.` at L45; PROCEDURE DIVISION at L70; main loop at L74-L81; L96 COMMENTED-OUT `DISPLAY CARD-RECORD` (SINGLE-DISPLAY signature); 5 paragraphs total (1 sequential read at L92, 1 open at L118, 1 close at L136, plus 2 numeric-prefix utility paragraphs `9999-ABEND-PROGRAM` at L154 and `9910-DISPLAY-IO-STATUS` at L161).
- `app/cpy/CVACT02Y.cpy` (14 lines) — CARD-RECORD 150-byte layout: 7 named fields (CARD-NUM PIC X(16), CARD-ACCT-ID PIC 9(11), CARD-CVV-CD PIC 9(03), CARD-EMBOSSED-NAME PIC X(50), CARD-EXPIRAION-DATE PIC X(10) [sic spelling], CARD-ACTIVE-STATUS PIC X(01)) + 59-byte FILLER.
- `app/data/ASCII/carddata.txt` (7,550 bytes; 50 records of 150 bytes + LF each) — CARDFILE input fixture (REFERENCE only via classpath; read-only; full 150-byte payload on disk INCLUDING the 59-byte FILLER as ASCII spaces — no adapter reconstitution required).
- `app/jcl/READCARD.jcl` (31 lines) — JCL driver (REFERENCE only; not invoked by the test). Invokes `EXEC PGM=CBACT02C` at STEP05 (L22); the CARDFILE DD at L25-L26 points to `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`. Translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCardDumpApp.java` per AAP §0.4.1. The Java test class bypasses the main class and invokes `CbAct02C` directly via constructor injection.

## Authority references

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS.
- AAP §0.2.1 — Golden-record fixtures in scope under `java/carddemo-tests/src/test/resources/golden/**/*`.
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE.
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` + `<program>/expected/`.
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports.
- AAP §0.4.1 — CBACT02C -> CbAct02C in `com.blitzy.carddemo.application.account`; ASCII fixtures REFERENCED via classpath, NEVER copied.
- AAP §0.6.1 — `Decimals` utility (NOT applicable to CBACT02C — no math).
- AAP §0.6.2 — Sealed-type pattern (REDEFINES, 88-level taxonomies).
- AAP §0.6.4 — `java.time.LocalDate` for CARD-EXPIRAION-DATE; never `java.util.Date`/`Calendar`.
- AAP §0.6.5 — `java.nio.file`; EBCDIC IBM-1047 default; per-file codepage override.
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely; sequential execution preserved (NO virtual threads).
- AAP §0.6.8 — Program-by-program mapping checklist entry for CBACT02C.
- AAP §0.6.11 — Golden-record harness PR gate; `@Disabled` until COBOL captures committed.
- AAP §0.7.1 — Minimal Change Clause; preserve SINGLE-DISPLAY (L96 commented out), consistent `'CARDFILE'` messaging, numeric 4-digit paragraph prefixes, CARD-EXPIRAION-DATE sic spelling, ABCODE=999, CEE3ABD CALL.
- AAP §0.7.2 — No card PAN or CVV in production logs (applies to CARD-NUM and CARD-CVV-CD via a separate sink — test fixture preserves unmasked bytes).
- AAP §0.7.4 — Forbidden features; no `default` branches that hide cases.
- AAP §0.7.5 — Capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]`.
- Sibling pattern references: `java/carddemo-tests/src/test/resources/golden/cbcus01c/input/README.md` (DOUBLE-DISPLAY, Z-prefix variant; `customer/` subpackage; INCONSISTENT `'CUSTFILE'`/`'CUSTOMER FILE'` messaging) and `java/carddemo-tests/src/test/resources/golden/cbact03c/input/README.md` (DOUBLE-DISPLAY, numeric-prefix variant — closest pattern relative; same 8-H2 + 4-H3 structure; same `account/` subpackage; same numeric 4-digit prefixes; differs in DOUBLE-DISPLAY pattern vs CBACT02C's SINGLE-DISPLAY; 50-byte CARD-XREF-RECORD vs 150-byte CARD-RECORD; NO date fields vs CBACT02C's CARD-EXPIRAION-DATE; NO CVV vs CBACT02C's CARD-CVV-CD; FILLER OMITTED on disk vs CBACT02C's FILLER INCLUDED on disk).

## DO NOT add files here

This folder MUST contain exactly ONE file: this README. Adding any other file (data, captured output, source mirror, `.gitkeep`) is FORBIDDEN.

Specifically:

- Captured COBOL output belongs in `../expected/stdout.txt`, not here.
- The ASCII fixture `app/data/ASCII/carddata.txt` is REFERENCED via classpath per AAP §0.4.1; copying it here would violate the single-source mandate.
- COBOL source files (`app/cbl/CBACT02C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/jcl/READCARD.jcl`) are IMMUTABLE per AAP §0.2.2; mirroring them here would create a parallel source of truth.
- Do NOT introduce `.gitkeep` — this README serves as the version-control marker.
- Do NOT introduce a `stdout.txt` or any capture output here — that belongs in `../expected/`.

If new input fixtures are needed for additional CBACT02C test scenarios beyond byte-for-byte parity, they MUST be added under `app/data/ASCII/` per AAP §0.2.2 modification rules and the user prompt's mandate that the `app/` tree remain the single source of fixture truth — NOT here.
