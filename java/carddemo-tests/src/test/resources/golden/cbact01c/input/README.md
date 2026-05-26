# CBACT01C (Account File Sequential Reader) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is a documentation-only marker for the *input side* of the CBACT01C golden-record fixture. The
source-of-truth fixture is `app/data/ASCII/acctdata.txt`, which is REFERENCED via classpath relative path
from the Java test harness and is NEVER copied into this folder per AAP §0.4.1. This folder is the sibling
of `../expected/` per the AAP §0.3.1 directory convention; together they form the `cbact01c/input/` plus
`cbact01c/expected/` pair consumed by `com.blitzy.carddemo.tests.golden.CbAct01CGoldenTest`, which remains
`@Disabled` per AAP §0.6.11 until COBOL-side captures are committed under `../expected/`. All citations
below follow the `[<path>:Lnnn]` discipline mandated by AAP §0.8.1. The marquee distinguishing characteristic
of CBACT01C is the DOUBLE-DISPLAY pattern: each successful ACCTFILE read emits 13 stdout lines (12 from
`1100-DISPLAY-ACCT-RECORD` invoked at L96 plus 1 from the main-loop `DISPLAY ACCOUNT-RECORD` at L78).

## Why this folder is documentation-only

1. No data files: AAP §0.4.1 mandates that ASCII fixtures live ONLY in `app/data/ASCII/` and are REFERENCED
   via classpath relative path from the Java test harness; copying `acctdata.txt` into this folder would
   violate the single-source-of-truth invariant.
2. No `.gitkeep`: this README is itself the version-control marker for the folder; no separate placeholder
   file is required or permitted.
3. No COBOL source mirrors: AAP §0.2.2 mandates that the `app/` tree (including `app/cbl/CBACT01C.cbl` and
   `app/cpy/CVACT01Y.cpy`) is IMMUTABLE; the COBOL sources are referenced by path only, never re-hosted.
4. No captured COBOL output: program stdout captured from the COBOL baseline belongs in `../expected/` per
   AAP §0.3.1, never in this `input/` folder.
5. The `input/` plus `expected/` pair convention is preserved purely by this README's presence; deleting
   this README would erase the documented contract for the input side of the fixture.

Cross-references to related documentation:

- `../expected/README.md` — the captured-output side of the same `cbact01c/` fixture pair.
- `../../cbact02c/input/README.md` — sibling SINGLE-DISPLAY variant (one full-record dump per read).
- `../../cbact03c/input/README.md` — sibling DOUBLE-DISPLAY xref variant for the card cross-reference reader.

## Conceptual input contract (documented; data referenced from app/data/ASCII/)

### FILE-CONTROL SELECT clause + FD record layout

| Element | Value |
|---|---|
| SELECT name | `ACCTFILE-FILE` `[app/cbl/CBACT01C.cbl:L29]` |
| ASSIGN TO | `ACCTFILE` `[app/cbl/CBACT01C.cbl:L29]` |
| ORGANIZATION | `INDEXED` `[app/cbl/CBACT01C.cbl:L30]` |
| ACCESS MODE | `SEQUENTIAL` `[app/cbl/CBACT01C.cbl:L31]` |
| RECORD KEY | `FD-ACCT-ID` `[app/cbl/CBACT01C.cbl:L32]` |
| FILE STATUS | `ACCTFILE-STATUS` `[app/cbl/CBACT01C.cbl:L33]` |
| FD record | `FD-ACCTFILE-REC` `[app/cbl/CBACT01C.cbl:L37-L40]` — 300 bytes |
| FD-ACCT-ID | `PIC 9(11)` `[app/cbl/CBACT01C.cbl:L39]` |
| FD-ACCT-DATA | `PIC X(289)` `[app/cbl/CBACT01C.cbl:L40]` |
| Working-storage record | `ACCOUNT-RECORD` via `COPY CVACT01Y` `[app/cbl/CBACT01C.cbl:L45]` |

CVACT01Y ACCOUNT-RECORD layout (12 named fields plus 178-byte FILLER):

| # | Field | PIC | Bytes | Offset | Java type | Notes |
|---|---|---|---|---|---|---|
| 1 | ACCT-ID | `9(11)` | 11 | 0 | long | primary key |
| 2 | ACCT-ACTIVE-STATUS | `X(01)` | 1 | 11 | char | 'Y'/'N' flag |
| 3 | ACCT-CURR-BAL | `S9(10)V99` | 12 | 12 | BigDecimal scale 2 | monetary |
| 4 | ACCT-CREDIT-LIMIT | `S9(10)V99` | 12 | 24 | BigDecimal scale 2 | monetary |
| 5 | ACCT-CASH-CREDIT-LIMIT | `S9(10)V99` | 12 | 36 | BigDecimal scale 2 | monetary |
| 6 | ACCT-OPEN-DATE | `X(10)` | 10 | 48 | LocalDate | YYYY-MM-DD |
| 7 | ACCT-EXPIRAION-DATE | `X(10)` | 10 | 58 | LocalDate | YYYY-MM-DD — COBOL sic spelling `EXPIRAION` PRESERVED per AAP §0.7.1 |
| 8 | ACCT-REISSUE-DATE | `X(10)` | 10 | 68 | LocalDate | YYYY-MM-DD |
| 9 | ACCT-CURR-CYC-CREDIT | `S9(10)V99` | 12 | 78 | BigDecimal scale 2 | monetary |
| 10 | ACCT-CURR-CYC-DEBIT | `S9(10)V99` | 12 | 90 | BigDecimal scale 2 | monetary |
| 11 | ACCT-ADDR-ZIP | `X(10)` | 10 | 102 | String | NOT displayed by 1100 paragraph |
| 12 | ACCT-GROUP-ID | `X(10)` | 10 | 112 | String | displayed at L129 |
| — | FILLER | `X(178)` | 178 | 122 | byte[178] | preserved verbatim |

Total: 11 + 1 + (12 * 5) + (10 * 3) + (10 * 2) + 178 = 300 bytes per `[app/cpy/CVACT01Y.cpy:L4-L17]`.

### Sequential read flow paragraphs (6 paragraphs)

| # | Paragraph | Line range | Purpose | Java translation hint |
|---|---|---|---|---|
| 1 | `0000-ACCTFILE-OPEN` | L133-L149 | Open ACCTFILE; abend on non-'00' file status | `AccountRepository.streamSequential()` initialization |
| 2 | `1000-ACCTFILE-GET-NEXT` | L92-L116 | READ INTO ACCOUNT-RECORD; on '00' PERFORM `1100-DISPLAY-ACCT-RECORD`; on '10' set EOF flag; otherwise abend | iterator `.next()` returning `Optional<AccountRecord>` |
| 3 | `1100-DISPLAY-ACCT-RECORD` | L118-L131 | UNIQUE to CBACT01C; 11 field DISPLAYs + 1 separator = 12 stdout lines per call | helper method emitting field DISPLAYs and 49-hyphen separator |
| 4 | `9000-ACCTFILE-CLOSE` | L151-L167 | Close ACCTFILE; abend on non-'00' file status | iterator `.close()` |
| 5 | `9999-ABEND-PROGRAM` | L169-L173 | DISPLAY `'ABENDING PROGRAM'`, MOVE 999 TO ABCODE, CALL `'CEE3ABD'` | sealed exception hierarchy plus faithful `System.exit(999)` |
| 6 | `9910-DISPLAY-IO-STATUS` | L176-L189 | Decode and display IO-STATUS; emit identical `'FILE STATUS IS: NNNN'` literal in both IF and ELSE branches | utility method on FileStatus sealed type |

All paragraph names use NUMERIC 4-digit prefixes (`0000-`, `1000-`, `1100-`, `9000-`, `9999-`, `9910-`) and
must be preserved in the Java translation's `@CobolParagraph` annotation values per AAP §0.7.1.

### Verbatim DISPLAY message catalog (21+ entries)

| # | Site | Line | Verbatim DISPLAY argument | Category |
|---|---|---|---|---|
| 1 | program start banner | L71 | `'START OF EXECUTION OF PROGRAM CBACT01C'` | banner |
| 2 | program end banner | L85 | `'END OF EXECUTION OF PROGRAM CBACT01C'` | banner |
| 3 | main-loop full-record dump (SECOND half of DOUBLE-DISPLAY) | L78 | `ACCOUNT-RECORD` (full 300-byte record verbatim) | full-record |
| 4 | 1100 field 1/11 | L119 | `'ACCT-ID                 :'   ACCT-ID` | field |
| 5 | 1100 field 2/11 | L120 | `'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS` | field |
| 6 | 1100 field 3/11 | L121 | `'ACCT-CURR-BAL           :'   ACCT-CURR-BAL` | field |
| 7 | 1100 field 4/11 | L122 | `'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT` | field |
| 8 | 1100 field 5/11 | L123 | `'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT` | field |
| 9 | 1100 field 6/11 | L124 | `'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE` | field |
| 10 | 1100 field 7/11 | L125 | `'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE` (sic preserved) | field |
| 11 | 1100 field 8/11 | L126 | `'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE` | field |
| 12 | 1100 field 9/11 | L127 | `'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT` | field |
| 13 | 1100 field 10/11 | L128 | `'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT` | field |
| 14 | 1100 field 11/11 | L129 | `'ACCT-GROUP-ID           :'   ACCT-GROUP-ID` | field |
| 15 | 1100 separator | L130 | 49 ASCII hyphens (`-` repeated 49 times) | separator |
| 16 | 1000 read error | L110 | `'ERROR READING ACCOUNT FILE'` (TWO words `ACCOUNT FILE`) | error — 3-WAY inconsistency site 1 |
| 17 | 0000 open error | L144 | `'ERROR OPENING ACCTFILE'` (SINGLE word `ACCTFILE`) | error — 3-WAY inconsistency site 2 |
| 18 | 9000 close error | L162 | `'ERROR CLOSING ACCOUNT FILE'` (TWO words `ACCOUNT FILE`) | error — 3-WAY inconsistency site 3 |
| 19 | 9999 abend banner | L170 | `'ABENDING PROGRAM'` | abend |
| 20 | 9910 NUMERIC branch | L183 | `'FILE STATUS IS: NNNN' IO-STATUS-04` | file-status |
| 21 | 9910 NON-NUMERIC branch | L187 | `'FILE STATUS IS: NNNN' IO-STATUS-04` (identical literal to L183) | file-status |

Two-word `ACCOUNT FILE` appears at L110 and L162; single-word `ACCTFILE` appears at L144. This 2-out-of-3
inconsistency is preserved verbatim per AAP §0.7.1 — the Java translation MUST emit these three exact
strings without "consistency fixes". The 9910 paragraph emits the identical `'FILE STATUS IS: NNNN'` literal
at L183 and L187; both DISPLAY sites must be preserved as two separate emission points.

### Fixture data location (source-of-truth)

| Attribute | Value |
|---|---|
| Source-of-truth path | `app/data/ASCII/acctdata.txt` (REFERENCE only per AAP §0.4.1) |
| Total bytes on disk | 15,050 |
| Total records | 50 |
| On-disk record length | 301 bytes (300-byte ACCOUNT-RECORD payload + 1-byte LF terminator) |
| Effective record length | 300 bytes (after LF strip) |
| FILLER on disk | INCLUDED (178 bytes of mostly ASCII spaces per record) |
| Classpath resolver | `GoldenRecordTest.resolveAppDataPath("acctdata.txt")` -> `app/data/ASCII/acctdata.txt` |
| Mutability | read-only (AAP §0.2.2 IMMUTABLE) |
| Production codepage | EBCDIC IBM-1047 (per AAP §0.6.5) |
| Test codepage | US-ASCII (this fixture is pre-transcoded for test convenience) |
| Sample first-record payload prefix (122 bytes) | `00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-202025-05-2000000000000{00000000000{A000000000` |

In each fixture record the trailing 178 bytes are ASCII spaces (FILLER), and the 10 bytes preceding FILLER
at offsets 112-121 are ASCII spaces (the ACCT-GROUP-ID field in record 1 is space-padded after the leading
`A`). Verified directly from `app/data/ASCII/acctdata.txt` via byte inspection.

## Cross-reference to the Java test class

The consumer of this fixture is `com.blitzy.carddemo.tests.golden.CbAct01CGoldenTest`, which extends the
shared base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` per AAP §0.6.11. The class under test
is `com.blitzy.carddemo.application.account.CbAct01C`, located in the `account/` subpackage alongside the
sibling translations for CBACT02C, CBACT03C, and CBACT04C per AAP §0.4.1. The sole repository port consumed
by the use case is `com.blitzy.carddemo.domain.port.AccountRepository`, wired against the file-backed
adapter `com.blitzy.carddemo.adapter.file.FileAccountRepository`. The domain record returned by the
adapter is `com.blitzy.carddemo.domain.record.AccountRecord` — the 300-byte translation of CVACT01Y per
AAP §0.4.1. The JCL-derived main class `com.blitzy.carddemo.app.ReadAccountDumpApp` (translated from
`app/jcl/READACCT.jcl`) is the production entry point; the golden-record test invokes the `CbAct01C` use
case directly and BYPASSES the main class so that wiring concerns do not contaminate parity assertions.
Test class hook methods follow the pattern: `programClass()` returns `CbAct01C.class`, `inputFile()`
returns `resolveAppDataPath("acctdata.txt")`, `auxiliaryInputs()` returns `List.of()` (CBACT01C reads
exactly one file), and `expectedOutputFile()` returns `resolveExpectedOutputPath("cbact01c", "stdout.txt")`.
The test class remains `@Disabled` until the corresponding capture in `../expected/` is committed per
AAP §0.6.11. Per AAP §0.6.6, CBACT01C executes sequentially — NO virtual threads — because DISPLAY ordering
is observable in stdout and reordering would violate byte-for-byte parity. No deterministic Clock fixture
is required because CBACT01C does NOT invoke FUNCTION CURRENT-DATE and has no time-based behavior to mock.

## Capture procedure cross-reference

The procedure to regenerate the corresponding `../expected/stdout.txt` from a COBOL run is documented in
`java/MIGRATION_NOTES.md` §1.6 — the resolution of the `[TODO]` "regenerate golden-record fixtures from
COBOL" marker carried forward per AAP §0.7.5. Re-capture is required whenever any of these inputs changes:
`app/data/ASCII/acctdata.txt`, `app/cbl/CBACT01C.cbl`, or `app/cpy/CVACT01Y.cpy`. CBACT01C is fully
deterministic: it has no Clock dependency, no random elements, no environment variables consumed, and no
network or external service interaction; its stdout is purely a function of the `acctdata.txt` content,
which means capture results are bit-stable across runs on the same input. PII exposure note: ACCT-ID is an
11-digit account identifier, NOT a card PAN; AAP §0.7.2 PAN masking requirements apply to CBACT02C's
CARD-NUM, NOT to CBACT01C's ACCT-ID — so no masking is required for this fixture's input or expected output.
The `@Disabled` annotation on `CbAct01CGoldenTest` stays in place until the corresponding `../expected/stdout.txt`
file is committed; only then does the test become a live PR gate.

## Behavioral invariants preserved by this fixture

- **DOUBLE-DISPLAY pattern (L78 + L96 -> 1100): marquee preserved behavior**; 12 lines from `1100-DISPLAY-ACCT-RECORD` invoked at L96 plus 1 line from the main-loop full-record `DISPLAY ACCOUNT-RECORD` at L78 = 13 stdout lines per record. Do NOT collapse or deduplicate the redundant dump.
- **652-line stdout total**: 1 START banner + 50 records * 13 lines + 1 END banner = 1 + 650 + 1 = 652. CONTRAST with CBACT02C's 52-line SINGLE-DISPLAY total (1 + 50 + 1) where each record emits only one full-record line.
- **Sequential ACCTFILE read order**: records appear in stdout in fixture order (records 1-50). NO virtual threads per AAP §0.6.6 — reordering would change observable stdout and violate byte-for-byte parity.
- **3-WAY error message inconsistency**: L110 `'ERROR READING ACCOUNT FILE'` (two words), L144 `'ERROR OPENING ACCTFILE'` (single word), L162 `'ERROR CLOSING ACCOUNT FILE'` (two words) — the 2-out-of-3 two-word pattern is PRESERVED verbatim per AAP §0.7.1.
- **NUMERIC 4-digit paragraph prefixes preserved**: `0000-`, `1000-`, `1100-`, `9000-`, `9999-`, `9910-` — must be reflected in `@CobolParagraph` annotation values on the Java translation per AAP §0.7.1. Contrast with CBCUS01C's `Z-`-prefixed paragraph naming.
- **ACCT-EXPIRAION-DATE COBOL sic spelling preserved**: the Java field name MUST be `acctExpiraionDate` (NOT `acctExpirationDate`); the COBOL source at `[app/cpy/CVACT01Y.cpy:L11]` spells the field `ACCT-EXPIRAION-DATE` and that spelling is preserved per AAP §0.7.1.
- **1100-DISPLAY-ACCT-RECORD emits 12 lines per call**: 11 field DISPLAYs at L119-L129 plus 1 separator at L130 = 12 lines. This paragraph is UNIQUE to CBACT01C among CBACT02C/CBACT03C/CBACT04C.
- **ACCT-ADDR-ZIP omitted from 1100 paragraph**: only 11 of the 12 named CVACT01Y fields are displayed (ACCT-ID through ACCT-GROUP-ID, skipping ACCT-ADDR-ZIP at offset 102). The omission is preserved as-is per AAP §0.7.1.
- **49-hyphen separator at L130 preserved verbatim**: exactly 49 ASCII `-` characters (verified `echo -n '---...---' | wc -c` returns 49); not 48, not 50.
- **MOVE 999 TO ABCODE before CALL `'CEE3ABD'`** at L172-L173: the abend code 999 must be reflected in the Java translation's exit code or sealed exception payload per faithful-translation rules under AAP §0.7.1.
- **APPL-RESULT 88-level conditions** (`88 APPL-AOK VALUE 0.` plus `88 APPL-EOF VALUE 16.`) at L62-L63 translate to a sealed-type pattern per AAP §0.6.10.
- **TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES** at L53-L56 translates to a sealed interface per AAP §0.6.2.
- **9910-DISPLAY-IO-STATUS emits identical literal in BOTH IF/ELSE branches** at L183 and L187 (both emit `'FILE STATUS IS: NNNN' IO-STATUS-04`); both emission sites must be preserved as two separate DISPLAY calls in the Java translation.
- **EXIT paragraph terminators preserved** at L116, L131, L149, L167, and L189; each translated method retains the same logical paragraph boundary.
- **5 BigDecimal monetary fields** (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT) parsed via the `Decimals` utility per AAP §0.6.1. CBACT01C performs NO arithmetic on these fields — only parse and encode; there are NO BigDecimal compute sites in this program.
- **3 LocalDate fields** (ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE) use `java.time.LocalDate` per AAP §0.6.4; no legacy date types.
- **`@CobolProgram("CBACT01C")` annotation REQUIRED** on `CbAct01C.java` per AAP §0.7.1 for traceability (cites PROGRAM-ID at `[app/cbl/CBACT01C.cbl:L23]` and AUTHOR `AWS` at `[app/cbl/CBACT01C.cbl:L24]`).
- **`ScopedValue` replaces `ThreadLocal`** per AAP §0.6.6 — applies even though CBACT01C is single-threaded by mandate, for forward-compatibility with batch composition in `carddemo-batch`.

## Source lineage

- `app/cbl/CBACT01C.cbl` (193 lines) — primary COBOL source; UNCHANGED per AAP §0.1.1 and §0.2.2.
- `app/cpy/CVACT01Y.cpy` (20 lines) — 300-byte ACCOUNT-RECORD layout; UNCHANGED per AAP §0.1.1 and §0.2.2.
- `app/data/ASCII/acctdata.txt` (15,050 bytes; 50 records * 301 bytes/line) — ACCTFILE input fixture, REFERENCE only and never copied per AAP §0.4.1; UNCHANGED per AAP §0.1.1 and §0.2.2.
- `app/jcl/READACCT.jcl` — JCL driver, REFERENCE only; translated to `com.blitzy.carddemo.app.ReadAccountDumpApp` per AAP §0.4.1; the golden-record test class bypasses the main class.

## Authority references

- AAP §0.1.1 — Core refactoring objective: the `app/` tree is IMMUTABLE.
- AAP §0.2.1 — In-scope wildcard `java/carddemo-tests/src/test/resources/golden/**/*` covers this README.
- AAP §0.2.2 — Out of scope: the `app/` tree IMMUTABLE; no edits to COBOL sources.
- AAP §0.3.1 — Target structure: `<program>/input/` plus `<program>/expected/` per program.
- AAP §0.3.2 — Hexagonal design pattern application (records, sealed types, ports, adapters).
- AAP §0.4.1 — ASCII fixtures REFERENCED via classpath, NEVER copied; CBACT01C maps to `com.blitzy.carddemo.application.account.CbAct01C` in the `account/` subpackage.
- AAP §0.6.1 — BigDecimal arithmetic via the `Decimals` utility with `MathContext.DECIMAL128`.
- AAP §0.6.2 — REDEFINES translates to sealed interface (applies to TWO-BYTES-ALPHA REDEFINES at L54-L56).
- AAP §0.6.4 — `java.time` for dates; strict `LocalDate.parse` resolver style.
- AAP §0.6.5 — File I/O exactness; EBCDIC IBM-1047 default codepage; `java.nio.file` only.
- AAP §0.6.6 — Virtual-thread fan-out forbidden when reordering changes observable output; `ScopedValue` replaces `ThreadLocal` entirely.
- AAP §0.6.8 — Program-by-program checklist (CBACT01C entry: sequential reader pattern).
- AAP §0.6.10 — Sealed-type hierarchies for 88-level conditions (applies to APPL-AOK / APPL-EOF at L62-L63).
- AAP §0.6.11 — Golden-record harness; `@Disabled` scaffolding pattern; non-negotiable PR gate.
- AAP §0.7.1 — Minimal Change Clause; preserve DOUBLE-DISPLAY pattern, the 3-WAY error message inconsistency, NUMERIC paragraph prefixes, and the ACCT-EXPIRAION-DATE sic spelling verbatim.
- AAP §0.7.2 — PAN masking applies to card PAN (CBACT02C's CARD-NUM); NOT applicable to ACCT-ID (CBACT01C).
- AAP §0.7.4 — Forbidden features: preview JEPs 502 / 505 / 507, `ThreadLocal`, `java.util.Date`, `java.io.File`, `double`/`float` for monetary values, `--enable-preview` JVM flag.
- AAP §0.7.5 — Capture procedure for golden-record fixtures documented in `java/MIGRATION_NOTES.md` §1.6.
- AAP §0.8.1 — Citation discipline using the `[<path>:Lnnn]` format.
- Sibling pattern reference: `../../cbact02c/input/README.md` — SINGLE-DISPLAY variant for the card sequential reader.
- Sibling pattern reference: `../../cbact03c/input/README.md` — DOUBLE-DISPLAY xref variant for the card cross-reference reader.

## DO NOT add files here

This README is the ONLY file ever to live in this folder. Do not add a copy of `acctdata.txt`; the fixture
is REFERENCED via classpath relative path per AAP §0.4.1 and copying it here would create a divergent
second copy that violates the single-source-of-truth invariant. Do not add COBOL source mirrors; the
`app/cbl/CBACT01C.cbl` and `app/cpy/CVACT01Y.cpy` sources are IMMUTABLE per AAP §0.2.2 and are referenced
by path only. Do not add a `.gitkeep`; this README is the version-control marker. Captured COBOL stdout
belongs in `../expected/`, never in this `input/` folder. Any new test fixtures needed must be placed under
`app/data/ASCII/` per AAP §0.2.2, NOT under `java/carddemo-tests/src/test/resources/golden/cbact01c/input/`.
