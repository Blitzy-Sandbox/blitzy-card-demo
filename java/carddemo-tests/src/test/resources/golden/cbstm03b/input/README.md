# CBSTM03B Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files. CBSTM03B is a callable file-services subroutine (translated to the Java class `com.blitzy.carddemo.application.statement.CbStm03B`); it receives its inputs through `LK-M03B-AREA` LINKAGE SECTION fields populated by the caller (CBSTM03A or a test harness driver), NOT through file-based fixtures. The conventional `input/` subdirectory created per AAP §0.6.11 (golden-record harness convention) exists only to make the absence of fixture inputs **explicit and discoverable** to developers browsing the harness tree.

## Why this folder is documentation-only

- CBSTM03B is a **callable subroutine**, invoked from COBOL via `CALL 'CBSTM03B' USING WS-M03B-AREA` (where `WS-M03B-AREA` is the caller's working-storage mirror of `LK-M03B-AREA`).
- Per `app/cbl/CBSTM03B.CBL:L114`: `PROCEDURE DIVISION USING LK-M03B-AREA.` — the subroutine binds exactly one parameter group; there is no standalone main-program entry, no JCL `EXEC PGM=CBSTM03B`, and no fixture-file feed.
- CBSTM03B contains no `EXEC CICS` and no `EXEC SQL`; it is pure batch COBOL with QSAM/VSAM access. The Java translation has no CICS-related or relational-database concerns.
- All inputs flow via the **LINKAGE SECTION** at `app/cbl/CBSTM03B.CBL:L99-112`:

```cobol
LINKAGE SECTION.
01  LK-M03B-AREA.
    05  LK-M03B-DD          PIC X(08).
    05  LK-M03B-OPER        PIC X(01).
      88  M03B-OPEN       VALUE 'O'.
      88  M03B-CLOSE      VALUE 'C'.
      88  M03B-READ       VALUE 'R'.
      88  M03B-READ-K     VALUE 'K'.
      88  M03B-WRITE      VALUE 'W'.
      88  M03B-REWRITE    VALUE 'Z'.
    05  LK-M03B-RC          PIC X(02).
    05  LK-M03B-KEY         PIC X(25).
    05  LK-M03B-KEY-LN      PIC S9(4).
    05  LK-M03B-FLDT        PIC X(1000).

PROCEDURE DIVISION USING LK-M03B-AREA.
```

- Parameter contract (6 fields; total wire size DD(8) + OPER(1) + RC(2) + KEY(25) + KEY-LN(4) + FLDT(1000) = 1040 bytes; field order interleaved per source — NOT all-inputs-then-all-outputs):

  | Field | PIC | Size | Direction | Purpose |
  | --- | --- | --- | --- | --- |
  | `LK-M03B-DD` | `X(08)` | 8 bytes | IN | DD name: `'TRNXFILE'`, `'XREFFILE'`, `'CUSTFILE'`, `'ACCTFILE'` |
  | `LK-M03B-OPER` | `X(01)` | 1 byte | IN | Operation code: O/C/R/K/W/Z |
  | `LK-M03B-RC` | `X(02)` | 2 bytes | OUT | 2-byte FILE STATUS code returned to the caller |
  | `LK-M03B-KEY` | `X(25)` | 25 bytes | IN | Key buffer for READ-K (substring `(1:LK-M03B-KEY-LN)` is moved into the FD area) |
  | `LK-M03B-KEY-LN` | `S9(4)` | 4 digits signed | IN | Effective key length used by the READ-K substring move |
  | `LK-M03B-FLDT` | `X(1000)` | 1000 bytes | OUT | Field data buffer that receives the record on a successful READ or READ-K |

- Per-file implementation matrix (sourced from `app/cbl/CBSTM03B.CBL:L30-53` SELECT clauses and `app/cbl/CBSTM03B.CBL:L57-78` FD groups):

  | File | DD name | Record size | Access mode | Implemented operations | Source paragraph |
  | --- | --- | --- | --- | --- | --- |
  | TRNX-FILE | `TRNXFILE` | 350 bytes | SEQUENTIAL (INDEXED) | OPEN, READ (sequential), CLOSE | `1000-TRNXFILE-PROC` (L133-155) |
  | XREF-FILE | `XREFFILE` | 50 bytes | SEQUENTIAL (INDEXED) | OPEN, READ (sequential), CLOSE | `2000-XREFFILE-PROC` (L157-179) |
  | CUST-FILE | `CUSTFILE` | 500 bytes | RANDOM (INDEXED) | OPEN, READ-K (keyed), CLOSE | `3000-CUSTFILE-PROC` (L181-204) |
  | ACCT-FILE | `ACCTFILE` | 300 bytes | RANDOM (INDEXED) | OPEN, READ-K (keyed), CLOSE | `4000-ACCTFILE-PROC` (L206-229) |

- Per-file FILE STATUS storage lives in WORKING-STORAGE at `app/cbl/CBSTM03B.CBL:L80-97`. Each paragraph's terminal `MOVE` copies the appropriate `<FILE>FILE-STATUS` (2-byte group) into `LK-M03B-RC` so the caller sees the standard COBOL FILE STATUS code (`'00'` ok, `'10'` end-of-file on sequential READ, `'23'` not-found on keyed READ-K, etc.). The COBOL FILE STATUS taxonomy maps one-to-one onto the sealed `FileStatus` hierarchy in the Java translation (see AAP §0.6.10).
- FD key type asymmetry: `FD-ACCT-ID` at `app/cbl/CBSTM03B.CBL:L77` is `PIC 9(11)` (numeric), unlike the three other file keys (`FD-TRNXS-ID`, `FD-XREF-CARD-NUM`, `FD-CUST-ID`) which are all `PIC X`. This affects the keyed-read MOVE at L214 (`MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID`): COBOL performs an implicit alphanumeric-to-numeric conversion when the target picture is `9(...)`. The Java translation must replicate this conversion behavior exactly so that the byte layout written into the file's key area matches the COBOL baseline.
- **Dead-code observation**: `M03B-WRITE` (`'W'` at `app/cbl/CBSTM03B.CBL:L107`) and `M03B-REWRITE` (`'Z'` at `app/cbl/CBSTM03B.CBL:L108`) are declared as 88-level conditions but NO paragraph branch tests `IF M03B-WRITE` or `IF M03B-REWRITE`. The four file paragraphs implement only `IF M03B-OPEN`, `IF M03B-READ` (TRNX/XREF) or `IF M03B-READ-K` (CUST/ACCT), and `IF M03B-CLOSE`. Therefore `OPER='W'` and `OPER='Z'` fall through every IF block, exit via the paragraph's `MOVE <file>FILE-STATUS TO LK-M03B-RC` statement at L152 / L176 / L201 / L226, and return the file's existing (prior) FILE STATUS without state change. Translate faithfully per AAP §0.7.1; flagged in `java/MIGRATION_NOTES.md`.
- Therefore, NO `app/data/ASCII/<program>.txt` fixture file exists for CBSTM03B, and the conventional `input/` directory for fixture data is empty.

## Where the actual test scenario inputs live

- The deterministic test scenario file `input_calls.txt` lives under the **sibling `../expected/` directory** (NOT here): see [`../expected/input_calls.txt`](../expected/input_calls.txt).
- This placement is **per parent folder requirements** (cited verbatim): "`input_calls.txt` — synthesized CALL sequence scenario (test-owned scaffolding, NOT a COBOL capture)" and "The test scenario file lives in `expected/` (NOT in `app/data/ASCII/`)".
- The scenario file is **test-owned scaffolding** — both the COBOL baseline run (used to capture expected outputs) AND the Java translation under test are driven by the **same** scenario file. This makes it part of the EXPECTED contract: BOTH sides must agree on the input vocabulary in order for byte-for-byte parity over the captured `stdout.txt` to be a meaningful binding contract.
- See [`../expected/README.md`](../expected/README.md) for:
  - The comprehensive list of test scenarios that `input_calls.txt` MUST cover (16 minimum scenarios plus 3 optional dispatcher/dead-code scenarios).
  - The format of the scenario file (one CALL per line, pipe-delimited; field widths mirror the LINKAGE SECTION at `app/cbl/CBSTM03B.CBL:L99-112`).
  - The capture procedure for `stdout.txt`.
  - The sealed `FileStatus` hierarchy per AAP §0.6.10.

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.CbStm03BGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbStm03BGoldenTest.java`. It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`.
- The test class is `@Disabled` until BOTH `../expected/input_calls.txt` AND the captured `../expected/stdout.txt` are committed (per AAP §0.6.11: "Initial test scaffolding may use placeholder expected files marked `@Disabled` until COBOL captures are available").
- Required overrides on the test class:
  - `inputFile()` returns `resolveExpectedOutputPath("cbstm03b", "input_calls.txt")` — confirming that the input scenario file is resolved from the `expected/` directory, NOT from this `input/` directory.
  - `expectedOutputFile()` returns `resolveExpectedOutputPath("cbstm03b", "stdout.txt")`.
  - `programClass()` returns `com.blitzy.carddemo.application.statement.CbStm03B.class`.
- The Java class under test is `com.blitzy.carddemo.application.statement.CbStm03B` (note the `statement/` subpackage per AAP §0.3.1 and AAP §0.4.1, NOT `util/`). It uses only `java.nio.file` for file I/O (AAP §0.6.5) and exposes the sealed `FileStatus` hierarchy `{Ok, EndOfFile, NotFound, DuplicateKey, IoError(int code, String description)}` per AAP §0.6.10.

## Stateful file handles between calls

- CBSTM03B is **stateful across CALL invocations**: an OPEN persists a file handle in the subroutine's internal state (specifically, the COBOL runtime's file table entry for `TRNX-FILE`, `XREF-FILE`, `CUST-FILE`, or `ACCT-FILE`); subsequent READ or READ-K calls advance or seek that handle; CLOSE releases it.
- The state model is **per-DD, cumulative across calls**. Two CALLs with different DDs (e.g., `TRNXFILE` then `XREFFILE`) do NOT interfere with each other's handles. Two CALLs with the same DD share state: a sequential READ on the same DD advances the cursor from where the previous READ left off, and the EOF condition is triggered when the cursor moves past the last record.
- Pre-condition discipline for `input_calls.txt`:
  - **OPEN-before-READ-before-CLOSE**. Calling READ before OPEN produces a file-not-open status (e.g., `'47'` or `'92'` depending on the compiler/runtime). Calling READ after CLOSE produces an analogous error.
  - **Reopening a closed file is a fresh start**. A subsequent OPEN on a previously CLOSEd DD resets the sequential cursor to the first record (TRNX/XREF) or refreshes the keyed index (CUST/ACCT).
  - **Unknown DDs skip the status MOVE entirely**. The dispatcher's `WHEN OTHER → GO TO 9999-GOBACK` at `app/cbl/CBSTM03B.CBL:L127-128` bypasses ALL four file-paragraph status MOVEs, so an unknown DD leaves `LK-M03B-RC` in whatever state it was on entry. This is a documented observable behavior that the Java translation must preserve.
- The Java translation MUST manage file lifecycles deterministically and MUST NOT wrap a single CALL in a try-with-resources block that closes the handle when the call returns — file state MUST persist across method invocations exactly as in COBOL. No GC-driven close; explicit close via the OPER=`'C'` CALL is mandatory.
- This stateful contract is the key reason that `input_calls.txt` is a sequenced CALL log (line order matters) rather than a set of independent test inputs. Reordering its lines would produce different FILE STATUS values, which would in turn produce a different `stdout.txt`, which would invalidate the captured parity baseline.

## Capture procedure cross-reference

- See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build/run path used to capture expected outputs for the golden-record harness.
- The capture procedure exists because the user prompt originally left the COBOL build/run path as an unresolved marker; AAP §0.7.5 resolves that marker by documenting the procedure in `MIGRATION_NOTES.md` §1.6.
- Capture requires a small COBOL driver program (NOT CBSTM03A itself, since CBSTM03A has a hard-coded invocation pattern that is not parameterised by `input_calls.txt`). The driver MUST:
  - Read `input_calls.txt` line by line, skipping comment lines (`#` prefix) and blank lines.
  - Parse the pipe-delimited fields into the four LINKAGE input slots (`LK-M03B-DD`, `LK-M03B-OPER`, `LK-M03B-KEY`, `LK-M03B-KEY-LN`).
  - Populate `WS-M03B-AREA` (mirroring `LK-M03B-AREA`).
  - Issue `CALL "CBSTM03B" USING WS-M03B-AREA`.
  - Write `LK-M03B-RC` and a hex/text dump of `LK-M03B-FLDT` (truncated to the per-DD record length: 350 / 50 / 500 / 300 bytes) to stdout, one line per CALL, preserving line order.
- Until capture is performed: `../expected/stdout.txt` MAY be an empty placeholder and `CbStm03BGoldenTest` remains `@Disabled`.

## Source lineage

- `app/cbl/CBSTM03B.CBL` — COBOL source: callable file-services subroutine (230 lines).
  - FILE-CONTROL at L30-53 (four SELECT clauses, all `ORGANIZATION IS INDEXED`).
  - FILE SECTION at L57-78 (four FDs; record sizes 350/50/500/300).
  - WORKING-STORAGE STATUS items at L80-97 (per-DD 2-byte FILE STATUS group).
  - LINKAGE SECTION at L99-112 (the `LK-M03B-AREA` parameter group reproduced above).
  - PROCEDURE DIVISION USING at L114.
  - `0000-START` dispatcher EVALUATE at L116-128 (delegates to one of the four file paragraphs by DD; `WHEN OTHER` at L127-128 falls through to `9999-GOBACK` at L130-131).
  - Per-paragraph line ranges: `1000-TRNXFILE-PROC` L133-155 (exits `1900-EXIT` at L151 / `1999-EXIT` at L154); `2000-XREFFILE-PROC` L157-179 (exits `2900-EXIT` at L175 / `2999-EXIT` at L178); `3000-CUSTFILE-PROC` L181-204 (exits `3900-EXIT` at L200 / `3999-EXIT` at L203); `4000-ACCTFILE-PROC` L206-229 (exits `4900-EXIT` at L225 / `4999-EXIT` at L228).
  - The two-level exit pattern `<NNNN>9-EXIT` (MOVE file-status) and `<NNNN>99-EXIT` (`EXIT.`) is traversed by `PERFORM <NNNN>-PROC THRU <NNNN>99-EXIT`, ensuring the status MOVE always executes regardless of which IF branch was taken.
- `app/cbl/CBSTM03A.CBL` — COBOL caller: statement-generation main program that issues `CALL "CBSTM03B" USING WS-M03B-AREA` to drive file I/O. Provides the canonical caller-side invocation pattern; the `WS-M03B-AREA` working-storage structure at `app/cbl/CBSTM03A.CBL:L71` is field-for-field identical to `LK-M03B-AREA`.

## Authority references

- AAP §0.2.1 — In-scope: `golden/` directory tree under `carddemo-tests`.
- AAP §0.3.1 — Golden-record harness directory structure; `statement/` subpackage for `CbStm03B`.
- AAP §0.4.1 — CBSTM03B translation: "Callable file-services subroutine; translate as utility class".
- AAP §0.6.5 — `java.nio.file` mandate for all file I/O; `java.io.File` is forbidden in new code.
- AAP §0.6.10 — Sealed `FileStatus` hierarchy `{Ok, EndOfFile, NotFound, DuplicateKey, IoError(int code, String description)}`.
- AAP §0.6.11 — PR gate; `@Disabled` scaffolding pattern for golden-record harness.
- AAP §0.7.1 — Minimal Change Clause; preserve LINKAGE SECTION shape and dead-code operations verbatim.
- AAP §0.7.5 — Capture procedure documented in `java/MIGRATION_NOTES.md`.

## DO NOT add files here

This folder MUST remain documentation-only:

- Do NOT add fixture data files (e.g., copies of `app/data/ASCII/*.txt`).
- Do NOT add `.gitkeep` placeholders — this `README.md` IS the directory's marker, and by virtue of its presence preserves the empty `input/` folder in version control.
- Do NOT add subdirectories.
- Do NOT rename this file; the path `java/carddemo-tests/src/test/resources/golden/cbstm03b/input/README.md` is referenced by tooling and by other harness documentation.

CBSTM03B has no file-based input feed by design; introducing one would constitute a behavior change beyond migration scope per AAP §0.7.1. If a future test scenario requires additional CALL coverage, add it to [`../expected/input_calls.txt`](../expected/input_calls.txt) (which is the authoritative scenario contract per `../expected/README.md`), not to this folder.
