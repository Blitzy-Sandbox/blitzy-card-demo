# CBSTM03B Golden-Record Fixtures — expected/

This folder holds the expected outputs and the synthesized scenario contract for the CBSTM03B golden-record parity test. The consuming test class is `com.blitzy.carddemo.tests.golden.CbStm03BGoldenTest`, currently annotated `@Disabled` pending the two data files (`input_calls.txt` and `stdout.txt`) described below. The `@Disabled` annotation is the AAP §0.6.11 scaffolding pattern: the test class and the fixture's `README.md` contract are committed first; the data files follow in a later capture commit per `java/MIGRATION_NOTES.md` §1.6; the test re-enables itself by removing the `@Disabled` once both files are present with non-placeholder content.

CBSTM03B is a callable COBOL subroutine (`PROCEDURE DIVISION USING LK-M03B-AREA` at `app/cbl/CBSTM03B.CBL:L114`), not a standalone batch job; therefore no fixture-style input file exists in `../input/`. The deterministic test scenario contract (`input_calls.txt`) lives in this `expected/` folder because BOTH the COBOL baseline capture run AND the Java translation are driven by it. Both sides must agree on the input vocabulary before byte-for-byte parity over the captured `stdout.txt` becomes a meaningful binding contract. See `../input/README.md` for the empty-sibling rationale and the project-wide convention that "subroutine" fixtures place the driving call list under `expected/` rather than under `input/`.

## Files in this folder

Two data files are expected to land in this folder; this README describes them so the COBOL capture commit and the Java implementation commit can proceed independently, each conforming to the same authoritative contract.

### README.md (this file)

Authoritative contract for the CBSTM03B golden-record fixture.

### input_calls.txt — Synthesized CALL Sequence (test-owned scaffolding)

- The **deterministic test scenario contract** consumed by `CbStm03BGoldenTest.inputFile()`.
- Test-owned scaffolding — NOT a COBOL capture and NOT derived from `app/data/ASCII/`. The file represents the cross-system input vocabulary: it is produced by hand (or by a generator script) at fixture-creation time, then consumed verbatim by both the COBOL driver and the Java driver.
- Lives in `expected/` (not `../input/`) because BOTH the COBOL reference run AND the Java translation are driven by this file. Both sides must agree on the input vocabulary in order for byte-for-byte parity to be meaningful.
- **Format**: one CALL per line, pipe-delimited; field widths mirror the LINKAGE SECTION (`app/cbl/CBSTM03B.CBL:L99-112`).
  - Field 1: 8-character `LK-M03B-DD` (`'TRNXFILE'`, `'XREFFILE'`, `'CUSTFILE'`, `'ACCTFILE'`, space-padded to 8).
  - Field 2: 1-character `LK-M03B-OPER` (`'O'`, `'C'`, `'R'`, `'K'`, `'W'`, `'Z'`).
  - Field 3: 25-character `LK-M03B-KEY` (used only when `OPER='K'`; right-pad with spaces to 25; for `O`/`R`/`C`/`W`/`Z` use 25 spaces).
  - Field 4: signed 4-digit `LK-M03B-KEY-LN` rendered as 5 characters with explicit sign (e.g., `+0009`, `+0011`, `+0016`); the underlying COBOL byte representation is `PIC S9(4)` USAGE DISPLAY (4 bytes; sign overpunched on the last digit).
- Lines beginning with `#` are comments (ignored). Blank lines are also ignored. The COBOL driver and the Java driver MUST both implement identical comment-stripping and blank-skipping semantics so that the input cursor advances in lockstep.
- Illustrative example block:

  ```text
  # Open TRNXFILE for sequential read
  TRNXFILE|O|                         |+0000
  # Sequential read of TRNXFILE
  TRNXFILE|R|                         |+0000
  # Keyed read of CUSTFILE with 9-byte customer ID
  CUSTFILE|K|000000123                |+0009
  # Close TRNXFILE
  TRNXFILE|C|                         |+0000
  ```

- The example above is illustrative only. The committed `input_calls.txt` MUST exercise all 16 minimum required scenarios listed in the **Required Test Scenarios** section below, in the order given, plus the optional dispatcher-level scenarios 17–19 if captured.
- **Status when initially committed**: present (synthesized at fixture-creation time). `CbStm03BGoldenTest` remains `@Disabled` until BOTH this file and `stdout.txt` are present with non-placeholder content.

### stdout.txt — Captured COBOL DISPLAY / Harness Log Output

- CBSTM03B itself contains NO DISPLAY statements (file I/O dispatch only; all error reporting belongs to the caller CBSTM03A). Therefore `stdout.txt` captures **harness-driven log output** produced when a COBOL driver iterates through `input_calls.txt`, calls CBSTM03B per line, and writes a structured trace to stdout.
- The Java test driver in `CbStm03BGoldenTest` produces an equivalent stdout trace; byte-for-byte parity with this file is the binding contract per the `GoldenRecordTest` base class `expectedOutputFile()` mechanism.
- **Format**: line-based ASCII text. The exact format MUST be agreed between the COBOL harness driver and the Java driver such that both produce identical bytes given identical `input_calls.txt`. The format MUST be specified in `java/MIGRATION_NOTES.md` §1.6 alongside the capture procedure. Suggested per-line format: `<DD>|<OPER>|<RC>|<KEY>|<KEY-LN>|<FLDT_HEX_DUMP>` where `<FLDT_HEX_DUMP>` is the FLDT buffer's contents up to the file's actual record length (350/50/500/300 bytes depending on DD), hex-encoded for ASCII safety.
- Hex-encoding the FLDT dump avoids ambiguity around embedded low-ASCII control characters, EBCDIC residue, and binary remnants in the file data; it also makes the stdout file safely commit-able to Git without binary-detection heuristics misclassifying it.
- Line ordering in `stdout.txt` MUST be identical to the call ordering in `input_calls.txt`. The N-th non-comment, non-blank line in `input_calls.txt` produces the N-th line in `stdout.txt`.
- **Status when initially committed**: PLACEHOLDER — `CbStm03BGoldenTest` remains `@Disabled` until captured stdout matches what the Java driver produces given the committed `input_calls.txt`.

## Required Test Scenarios

`input_calls.txt` MUST exercise — at minimum — every distinct (DD, operation, FILE STATUS) combination across the four DDs. The implemented operation matrix per DD is summarized below; access modes and FD record sizes are sourced verbatim from `app/cbl/CBSTM03B.CBL:L30-53` (SELECT clauses) and `app/cbl/CBSTM03B.CBL:L57-78` (FD groups).

| DD | Implemented Operations | Required Scenarios |
| --- | --- | --- |
| TRNXFILE | O (open), R (sequential read), C (close) | OPEN OK; READ first; READ to EOF (RC=`'10'`); CLOSE OK |
| XREFFILE | O, R, C | OPEN OK; READ first; READ to EOF (RC=`'10'`); CLOSE OK |
| CUSTFILE | O, K (keyed read), C | OPEN OK; READ-K with valid 9-byte key (RC=`'00'`); READ-K with not-found key (RC=`'23'`); CLOSE OK |
| ACCTFILE | O, K, C | OPEN OK; READ-K with valid 11-byte numeric key (RC=`'00'`); READ-K with not-found key (RC=`'23'`); CLOSE OK |

The access modes are declared by the `SELECT` clauses at `app/cbl/CBSTM03B.CBL:L30-53`:

```cobol
SELECT TRNX-FILE ASSIGN TO TRNXFILE
       ORGANIZATION IS INDEXED
       ACCESS MODE  IS SEQUENTIAL
       RECORD KEY   IS FD-TRNXS-ID
       FILE STATUS  IS TRNXFILE-STATUS.

SELECT XREF-FILE ASSIGN TO   XREFFILE
       ORGANIZATION IS INDEXED
       ACCESS MODE  IS SEQUENTIAL
       RECORD KEY   IS FD-XREF-CARD-NUM
       FILE STATUS  IS XREFFILE-STATUS.

SELECT CUST-FILE ASSIGN TO CUSTFILE
       ORGANIZATION IS INDEXED
       ACCESS MODE  IS RANDOM
       RECORD KEY   IS FD-CUST-ID
       FILE STATUS  IS CUSTFILE-STATUS.

SELECT ACCT-FILE ASSIGN TO ACCTFILE
       ORGANIZATION IS INDEXED
       ACCESS MODE  IS RANDOM
       RECORD KEY   IS FD-ACCT-ID
       FILE STATUS  IS ACCTFILE-STATUS.
```

All four files are declared `ORGANIZATION IS INDEXED` (VSAM KSDS on the mainframe). For the file-based Java adapter the keyed-access semantics for CUSTFILE/ACCTFILE are emulated via an in-memory offset index built at OPEN time; sequential access for TRNXFILE/XREFFILE simply walks the underlying ASCII fixture in `app/data/ASCII/`.

The matrix above is derived directly from the COBOL paragraphs: 1000-TRNXFILE-PROC tests only `IF M03B-OPEN`, `IF M03B-READ`, and `IF M03B-CLOSE`; 2000-XREFFILE-PROC follows the identical shape; 3000-CUSTFILE-PROC tests `IF M03B-OPEN`, `IF M03B-READ-K`, and `IF M03B-CLOSE`; 4000-ACCTFILE-PROC follows the identical shape. There is no paragraph in CBSTM03B that tests `IF M03B-WRITE` or `IF M03B-REWRITE`, even though the 88-levels at `app/cbl/CBSTM03B.CBL:L107-108` exist for them.

Per-DD FD record layouts (sourced from `app/cbl/CBSTM03B.CBL:L57-78`) — these define what bytes land in the `LK-M03B-FLDT` buffer on a successful READ or READ-K:

| DD | Source FD | Record length | Field 1 | Field 2 | Notes |
| --- | --- | --- | --- | --- | --- |
| TRNXFILE | `FD-TRNXFILE-REC` | 350 bytes | `FD-TRNXS-ID` (32 bytes; card 16 + tran-id 16) | `FD-ACCT-DATA` `X(318)` | Composite key on first 32 bytes; sequential access |
| XREFFILE | `FD-XREFFILE-REC` | 50 bytes | `FD-XREF-CARD-NUM` `X(16)` | `FD-XREF-DATA` `X(34)` | Sequential access by card number |
| CUSTFILE | `FD-CUSTFILE-REC` | 500 bytes | `FD-CUST-ID` `X(09)` | `FD-CUST-DATA` `X(491)` | Random access by customer ID |
| ACCTFILE | `FD-ACCTFILE-REC` | 300 bytes | `FD-ACCT-ID` `9(11)` | `FD-ACCT-DATA` `X(289)` | Random access by numeric account ID; note the `PIC 9` USAGE at `app/cbl/CBSTM03B.CBL:L77` |

On a successful READ or READ-K, the FLDT buffer's first N bytes (N=350/50/500/300) contain the record bytes; the remaining 1000-N bytes are whatever the buffer previously held. The Java translation MUST preserve this trailing-residual behavior to match COBOL byte-for-byte; specifically, the implementation MUST NOT zero-fill or space-fill the remainder of the FLDT buffer after a short-record read.

Dispatcher-level scenarios (beyond the per-DD matrix):

- **Unknown DD** (e.g., `'BADFILE '` 8 chars) → `WHEN OTHER` at `app/cbl/CBSTM03B.CBL:L127-128` → `GO TO 9999-GOBACK` (no FILE STATUS update; `LK-M03B-RC` is whatever was last written — typically uninitialized spaces on first call). The Java translation may return `FileStatus.IoError` with an explanatory code per AAP §0.6.10; document the captured byte sequence faithfully.
- **Dead-code operations**: `OPER='W'` (WRITE) and `OPER='Z'` (REWRITE) on ANY DD → no IF branch matches → fall through to the paragraph's exit `MOVE <FILE>FILE-STATUS TO LK-M03B-RC` (e.g., `1900-EXIT` at `app/cbl/CBSTM03B.CBL:L151-152`); the file's existing status from the prior OPEN/READ is returned. Document this faithfully per AAP §0.7.1 and flag in `java/MIGRATION_NOTES.md`.
- **OPEN-CLOSE without READ** (sanity check; RC sequence: `'00'` for OPEN, `'00'` for CLOSE).
- **READ before OPEN** (would produce file-not-open status, typically `'47'` or `'92'` depending on compiler/runtime — document the captured value).
- **Double-CLOSE** (would produce file-not-open status on second call).

Minimum required scenario enumeration:

1. **TRNXFILE OPEN OK** — `DD='TRNXFILE'`, `OPER='O'` → RC=`'00'`
2. **TRNXFILE READ first record** — `DD='TRNXFILE'`, `OPER='R'` → RC=`'00'`; FLDT contains 350-byte record
3. **TRNXFILE READ to EOF** — `DD='TRNXFILE'`, `OPER='R'` (after exhausting file) → RC=`'10'`
4. **TRNXFILE CLOSE OK** — `DD='TRNXFILE'`, `OPER='C'` → RC=`'00'`
5. **XREFFILE OPEN OK** — RC=`'00'`
6. **XREFFILE READ first record** — RC=`'00'`; FLDT contains 50-byte record
7. **XREFFILE READ to EOF** — RC=`'10'`
8. **XREFFILE CLOSE OK** — RC=`'00'`
9. **CUSTFILE OPEN OK** — RC=`'00'`
10. **CUSTFILE READ-K with valid key** — `OPER='K'`, `KEY='000000001                '` (right-padded to 25), `KEY-LN=+0009` → RC=`'00'`; FLDT contains 500-byte record
11. **CUSTFILE READ-K with not-found key** — `KEY='999999999                '`, `KEY-LN=+0009` → RC=`'23'`
12. **CUSTFILE CLOSE OK** — RC=`'00'`
13. **ACCTFILE OPEN OK** — RC=`'00'`
14. **ACCTFILE READ-K with valid 11-byte numeric key** — `KEY='00000000001              '`, `KEY-LN=+0011` → RC=`'00'`; FLDT contains 300-byte record (note: FD-ACCT-ID is `PIC 9(11)` numeric at `app/cbl/CBSTM03B.CBL:L77`)
15. **ACCTFILE READ-K with not-found key** — `KEY='99999999999              '`, `KEY-LN=+0011` → RC=`'23'`
16. **ACCTFILE CLOSE OK** — RC=`'00'`

Optional additional scenarios (document outcomes in `java/MIGRATION_NOTES.md` §1.6 after capture):

17. **Unknown DD** — `DD='BADFILE '`, any OPER → falls through to GOBACK (no status update)
18. **Dead-code WRITE** — `DD='TRNXFILE'`, `OPER='W'` → no IF branch matches; FILE STATUS unchanged from prior OPEN/READ
19. **Dead-code REWRITE** — `DD='TRNXFILE'`, `OPER='Z'` → no IF branch matches; FILE STATUS unchanged

Scenarios 1–16 are the activation gate: the test cannot be re-enabled until all sixteen are present in `input_calls.txt` and all sixteen are captured into `stdout.txt`. Scenarios 17–19 are advisory; they probe undefined edges of CBSTM03B's behavior and their captured outputs should be documented in `java/MIGRATION_NOTES.md` §1.6 once observed, so that the Java translation can faithfully reproduce them rather than guess at COBOL fall-through semantics.

## Java mapping invariants

The Java translation MUST honor every invariant below. Each invariant is sourced directly from `app/cbl/CBSTM03B.CBL` and the architectural overrides in the AAP; together they form the binding interface contract between the Java implementation under `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/statement/CbStm03B.java` and the byte-level outputs captured into `stdout.txt`.

- **Sealed `FileStatus` hierarchy mandatory** per AAP §0.6.10: `sealed interface FileStatus permits Ok, EndOfFile, NotFound, DuplicateKey, IoError`. The `IoError` permit is a record `record IoError(int code, String description) implements FileStatus {}` for unmodeled status codes (e.g., `'35'` file-not-found at open, `'47'` open-mode-conflict, `'92'` logic-error). Pattern-matching `switch` over `FileStatus` MUST be exhaustive without a `default` branch; the compiler enforces that every status surfaced by CBSTM03B is explicitly mapped at every call site.
- **Mapping of COBOL FILE STATUS codes to permits**:
  - `'00'` → `FileStatus.Ok`
  - `'10'` → `FileStatus.EndOfFile`
  - `'23'` → `FileStatus.NotFound`
  - `'22'` → `FileStatus.DuplicateKey` (not exercised by CBSTM03B's read-only operations but present for completeness)
  - `'35'`, `'47'`, `'92'`, other → `FileStatus.IoError(code, description)`

  | COBOL FILE STATUS | Meaning | Sealed permit |
  | --- | --- | --- |
  | `'00'` | Operation succeeded | `FileStatus.Ok` |
  | `'10'` | End-of-file on sequential READ | `FileStatus.EndOfFile` |
  | `'22'` | Duplicate key on WRITE/REWRITE | `FileStatus.DuplicateKey` |
  | `'23'` | Record not found on READ-K | `FileStatus.NotFound` |
  | `'35'` | File not found at OPEN | `FileStatus.IoError(35, "file not found")` |
  | `'47'` | Open-mode conflict | `FileStatus.IoError(47, "open mode conflict")` |
  | `'92'` | Logic error (e.g., READ before OPEN) | `FileStatus.IoError(92, "logic error")` |
  | any other | (passed through) | `FileStatus.IoError(code, description)` |
- **NO `java.io.File`** anywhere in the Java implementation. Use only `java.nio.file` types (`Path`, `Files.newByteChannel`, `SeekableByteChannel`). Reference AAP §0.6.5. Random keyed access for CUSTFILE and ACCTFILE is implemented via an in-memory `TreeMap<String, Long>` index over the file's record offsets (built once at OPEN time), with `SeekableByteChannel.position(...)` performing the seek for each READ-K.
- **LINKAGE SECTION shape preserved verbatim**. The Java method signature MUST accept and return a record mirroring the COBOL byte-level contract. The authoritative source layout is:

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
  ```

  - Input fields (caller-supplied): `LK-M03B-DD` (8 bytes), `LK-M03B-OPER` (1 byte), `LK-M03B-KEY` (25 bytes), `LK-M03B-KEY-LN` (signed 4-digit, `PIC S9(4)`).
  - Output fields (callee-populated): `LK-M03B-RC` (2-byte FILE STATUS) and `LK-M03B-FLDT` (1000-byte payload buffer).
  - Note: the on-wire byte layout interleaves RC between OPER and KEY (DD-OPER-RC-KEY-KEY-LN-FLDT = 8+1+2+25+4+1000 = **1040 bytes**); this is the LINKAGE SECTION order at `app/cbl/CBSTM03B.CBL:L99-112` and MUST be preserved verbatim.
  - The interleaved RC position differs from a naive "all-inputs-then-all-outputs" layout. The Java record MUST declare its components in the same byte order if it ever serializes to a wire format that pretends to be the COBOL `LK-M03B-AREA`. For the JVM-only invocation path used by `CbStm03BGoldenTest`, declaration order can be ergonomic, but the parse/encode pair MUST emit bytes in DD-OPER-RC-KEY-KEY-LN-FLDT order.

  The per-field byte-offset table for the 1040-byte LINKAGE area:

  | Field | Direction | COBOL PIC | Offset | Length | Cumulative end |
  | --- | --- | --- | --- | --- | --- |
  | `LK-M03B-DD` | input | `X(08)` | 0 | 8 | 8 |
  | `LK-M03B-OPER` | input | `X(01)` | 8 | 1 | 9 |
  | `LK-M03B-RC` | output | `X(02)` | 9 | 2 | 11 |
  | `LK-M03B-KEY` | input | `X(25)` | 11 | 25 | 36 |
  | `LK-M03B-KEY-LN` | input | `S9(4)` (DISPLAY) | 36 | 4 | 40 |
  | `LK-M03B-FLDT` | output | `X(1000)` | 40 | 1000 | 1040 |
- **Stateful file handles**: the Java `CbStm03B` utility class instance holds a `Map<String, FileSession>` keyed by DD name (`'TRNXFILE'`, `'XREFFILE'`, `'CUSTFILE'`, `'ACCTFILE'`). OPEN allocates; READ/READ-K advances or seeks; CLOSE deallocates. Java MUST NOT use try-with-resources blocks that release between independent CALLs — file state must persist across method invocations exactly as in COBOL. Concrete COBOL → `java.nio.file` mapping:

  | COBOL operation | `java.nio.file` equivalent | State change in `FileSession` |
  | --- | --- | --- |
  | `OPEN INPUT TRNX-FILE` | `Files.newByteChannel(path, READ)` returning `SeekableByteChannel` | Allocate `FileSession`; load offset index (CUSTFILE/ACCTFILE only) |
  | `READ <FILE> INTO LK-M03B-FLDT` (sequential) | `channel.read(buffer)` from current `position()` | Advance cursor by record length |
  | `MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-<x>-ID` + `READ <FILE> INTO LK-M03B-FLDT` (keyed) | Lookup offset via `TreeMap<String, Long>`; `channel.position(offset)`; `channel.read(buffer)` | Seek cursor; no advance |
  | `CLOSE <FILE>` | `channel.close()` | Remove `FileSession` from map |
- **Sequential read cursor preservation**: for TRNXFILE and XREFFILE, the `FileSession` MUST persist the current read offset across CALLs. A second `OPER='R'` CALL returns the next record, not the first record. This is the COBOL semantic implied by the `READ TRNX-FILE INTO LK-M03B-FLDT` statement at `app/cbl/CBSTM03B.CBL:L141`; the file cursor lives in the FCB, which persists across calls so long as no CLOSE intervenes.
- **Dead-code preservation**: `OPER='W'` and `OPER='Z'` produce NO state change in CBSTM03B (no IF branch matches the 88-levels at `app/cbl/CBSTM03B.CBL:L107-108`; the file's existing status is returned via the paragraph's exit MOVE). Java MUST reproduce this behavior faithfully per AAP §0.7.1; flag in `java/MIGRATION_NOTES.md`. The Java implementation MUST NOT throw `UnsupportedOperationException` or any other exception on `OPER='W'`/`OPER='Z'` — the COBOL behavior is silent fall-through with the previously latched FILE STATUS.
- **Numeric-key conversion for ACCTFILE**: `MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID` at `app/cbl/CBSTM03B.CBL:L214` performs an implicit USAGE conversion from alphanumeric (`PIC X(25)`) to numeric (`PIC 9(11)`). The Java translation MUST parse the leading `keyLn` characters of `KEY` as a left-zero-padded decimal string for ACCTFILE READ-K only; CUSTFILE READ-K at `app/cbl/CBSTM03B.CBL:L189` operates on an alphanumeric key (`PIC X(09)`) and passes the substring through as-is.
- **Byte-for-byte parity rule**: for every line in `input_calls.txt`, `CbStm03B.invoke(dd, oper, key, keyLn)` MUST produce an output record (`rc` + `fldt`) whose stdout trace bytes equal the corresponding bytes in `stdout.txt`.
- **No card PAN logged in full**: TRNX records contain a 16-byte card number (`FD-TRNX-CARD` at `app/cbl/CBSTM03B.CBL:L61`); the harness driver and the Java logger MUST mask all but the last 4 digits per AAP §0.7.2. Document the masking format in `java/MIGRATION_NOTES.md` §1.6.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CBSTM03B) for the canonical COBOL build/run path, the COBOL driver source (which iterates `input_calls.txt`), the encoding (ASCII for the captured stdout since this fixture corresponds to ASCII-mode capture), and the exact stdout format produced by the harness driver.

- Capture for CBSTM03B requires a small COBOL driver program (NOT CBSTM03A itself, since CBSTM03A has a hard-coded invocation pattern). The driver MUST: (a) read `input_calls.txt` line by line, (b) parse the pipe-delimited fields, (c) populate `WS-M03B-AREA` (mirroring `LK-M03B-AREA`), (d) `CALL "CBSTM03B" USING WS-M03B-AREA`, (e) emit a structured line to stdout containing the input echo + `LK-M03B-RC` + truncated/dump of `LK-M03B-FLDT`.
- The required input fixture files (TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE) are loaded from `app/data/ASCII/` via the appropriate JCL/IDCAMS reload (analogous to `app/jcl/ACCTFILE.jcl`, `app/jcl/CARDFILE.jcl`, `app/jcl/CUSTFILE.jcl`, `app/jcl/XREFFILE.jcl`).
- Until capture is performed: `stdout.txt` MAY be an empty placeholder and `CbStm03BGoldenTest` remains `@Disabled`. The placeholder MUST contain a single line stating its placeholder status so that downstream tooling does not mistake an empty file for a clean run.

## Test class @Disabled mandate

`CbStm03BGoldenTest.java` is annotated `@Disabled` until BOTH `input_calls.txt` AND `stdout.txt` are committed with non-placeholder content. The `@Disabled` annotation's `value` parameter cites this README and references AAP §0.6.11. The annotation MUST NOT be removed until every item in the activation checklist below is verifiably complete; a partial removal (e.g., enabling only the sequential scenarios) is forbidden because it would silently mask any failing keyed-read or dead-code scenarios.

The activation checklist:

1. `input_calls.txt` contains all 16 minimum required scenarios (plus optional 17–19)
2. `stdout.txt` contains the captured COBOL output for all scenarios in `input_calls.txt`
3. Sealed `FileStatus` hierarchy implemented in `carddemo-domain` per AAP §0.6.10
4. `com.blitzy.carddemo.application.statement.CbStm03B` class is implemented with stateful file-handle map
5. Java driver produces stdout matching `stdout.txt` byte-for-byte
6. FILE STATUS codes (`'00'`, `'10'`, `'23'`, `'35'`, `'47'`, `'92'`) map to the documented sealed permits
7. Dead-code operations (`'W'`, `'Z'`) produce no state change (matching COBOL fall-through)
8. Stateful OPEN/READ/CLOSE lifecycle correctly modeled (no try-with-resources between calls)
9. LINKAGE SECTION 1040-byte total layout preserved (8+1+2+25+4+1000 = 1040 bytes; verified against `app/cbl/CBSTM03B.CBL:L99-112`)

When every item is checked, the `@Disabled` annotation on `CbStm03BGoldenTest` is removed in the same commit that introduces the data files, and the test executes on every PR thereafter per AAP §0.6.11.

## Cross-references

- `../input/README.md` — sibling marker explaining why the conventional `input/` folder is intentionally empty
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbStm03BGoldenTest.java` — the JUnit 5 test class consuming this fixture (project-root-relative path); `@Disabled` until `input_calls.txt` and `stdout.txt` are committed
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — abstract base class defining `inputFile()`, `expectedOutputFile()`, and the byte-for-byte parity assertion
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/statement/CbStm03B.java` — the Java class under test (callable file-services subroutine translated as utility class)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/` — sealed `FileStatus` hierarchy location (per AAP §0.6.10)
- `java/MIGRATION_NOTES.md` §1.6 — capture procedure documentation

## Source lineage

- `app/cbl/CBSTM03B.CBL` — COBOL source: callable file-services subroutine (230 lines). LINKAGE SECTION at L99-112, PROCEDURE DIVISION at L114, dispatcher EVALUATE at L116-128, file paragraphs at L133-229. Per-paragraph line ranges: 1000-TRNXFILE-PROC L133-155 (exits 1900-EXIT/1999-EXIT); 2000-XREFFILE-PROC L157-179 (exits 2900-EXIT/2999-EXIT); 3000-CUSTFILE-PROC L181-204 (exits 3900-EXIT/3999-EXIT); 4000-ACCTFILE-PROC L206-229 (exits 4900-EXIT/4999-EXIT).
- `app/cbl/CBSTM03A.CBL` — COBOL caller: statement-generation main program that issues `CALL "CBSTM03B" USING WS-M03B-AREA` to drive file I/O. Provides the canonical caller-side invocation pattern; `WS-M03B-AREA` working-storage structure is field-for-field identical to `LK-M03B-AREA`.

The dispatcher `EVALUATE` at `app/cbl/CBSTM03B.CBL:L116-128` selects the per-DD paragraph via a string match on `LK-M03B-DD`:

```cobol
0000-START.

    EVALUATE LK-M03B-DD
      WHEN 'TRNXFILE'
        PERFORM 1000-TRNXFILE-PROC THRU 1999-EXIT
      WHEN 'XREFFILE'
        PERFORM 2000-XREFFILE-PROC THRU 2999-EXIT
      WHEN 'CUSTFILE'
        PERFORM 3000-CUSTFILE-PROC THRU 3999-EXIT
      WHEN 'ACCTFILE'
        PERFORM 4000-ACCTFILE-PROC THRU 4999-EXIT
      WHEN OTHER
        GO TO 9999-GOBACK.
```

The `WHEN OTHER` branch bypasses ALL four file-paragraph status MOVEs, so an unknown DD leaves `LK-M03B-RC` in whatever state it was on entry to the call. The Java translation MUST document its choice (leave RC unchanged versus explicitly return `FileStatus.IoError(0, "unknown DD")`) in `java/MIGRATION_NOTES.md` §1.6 after capture confirms the COBOL behavior.

The representative file-paragraph pattern is 1000-TRNXFILE-PROC at `app/cbl/CBSTM03B.CBL:L133-155`:

```cobol
1000-TRNXFILE-PROC.

    IF M03B-OPEN
        OPEN INPUT TRNX-FILE
        GO TO 1900-EXIT
    END-IF.

    IF M03B-READ
        READ TRNX-FILE INTO LK-M03B-FLDT
        END-READ
        GO TO 1900-EXIT
    END-IF.

    IF M03B-CLOSE
        CLOSE TRNX-FILE
        GO TO 1900-EXIT
    END-IF.

1900-EXIT.
    MOVE TRNXFILE-STATUS TO LK-M03B-RC.

1999-EXIT.
    EXIT.
```

The two-level exit pattern (`1900-EXIT` performs the status MOVE; `1999-EXIT` provides the `PERFORM ... THRU` boundary) ensures the status MOVE always executes regardless of which IF branch was taken — including the no-op (dead-code) fall-through when `OPER='W'` or `OPER='Z'` matches no IF clause. The Java translation MUST execute the equivalent status-read after every operation including the no-op fall-through. The XREFFILE/CUSTFILE/ACCTFILE paragraphs follow the identical pattern with their respective FD names and exit labels.

## Authority references

- AAP §0.2.1 — In-scope: golden/ directory tree under carddemo-tests
- AAP §0.3.1 — Golden-record harness directory structure
- AAP §0.4.1 — CBSTM03B → CbStm03B utility class translation (in `application/statement/`)
- AAP §0.6.5 — `java.nio.file` mandate for all file I/O
- AAP §0.6.10 — Sealed `FileStatus` hierarchy `{Ok, EndOfFile, NotFound, DuplicateKey, IoError(int code, String description)}`
- AAP §0.6.11 — PR gate; `@Disabled` scaffolding pattern
- AAP §0.7.1 — Minimal Change Clause; preserve LINKAGE SECTION shape and dead-code operations verbatim
- AAP §0.7.2 — Card PAN masking (last-4 only) in logs
- AAP §0.7.5 — Capture procedure documented in `java/MIGRATION_NOTES.md`

## DO NOT modify

The two data files in this folder (`input_calls.txt`, `stdout.txt`) are a **coupled set**. If `input_calls.txt` is modified (scenarios added, removed, or reordered), then `stdout.txt` MUST be re-captured from the COBOL reference run per the procedure in `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits to expected outputs without re-capture WILL break byte-for-byte parity. Do NOT 'fix' what looks like odd FILE STATUS codes or padding in the expected file — those are CBSTM03B's observable behavior and must be reproduced faithfully per AAP §0.7.1.
