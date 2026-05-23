# CSUTLDTC Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data
files. CSUTLDTC (the COBOL CEEDAYS wrapper subroutine, translated to the Java
class `com.blitzy.carddemo.application.util.DateValidator`) receives its inputs
through **method parameters**, not through file-based fixtures. The conventional
`input/` subdirectory created per AAP §0.6.11 (golden-record harness convention)
exists only to make the absence of fixture inputs **explicit and discoverable**
to developers browsing the harness tree.

## Why this folder is documentation-only

- CSUTLDTC is a **callable subroutine**, invoked via the COBOL statement
  `CALL "CSUTLDTC" USING LS-DATE, LS-DATE-FORMAT, LS-RESULT`.
- Per `app/cbl/CSUTLDTC.cbl:L88`:
  `PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT.` — the program
  has no `FILE-CONTROL`, no `SELECT`, no `OPEN`, no `READ`, and no `WRITE`. It
  has no file-based input feed of any kind.
- All inputs flow via the **LINKAGE SECTION** at `app/cbl/CSUTLDTC.cbl:L83-86`:

  ```cobol
  LINKAGE SECTION.
     01 LS-DATE         PIC X(10).
     01 LS-DATE-FORMAT  PIC X(10).
     01 LS-RESULT       PIC X(80).
  ```

- Parameter contract:

  | Parameter      | PIC     | Direction | Description                                                                                                                                                          |
  |----------------|---------|-----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
  | `LS-DATE`        | `X(10)` | Input     | 10-character input date string (e.g., `'2024-02-29'`, `'2023-12-31'`).                                                                                               |
  | `LS-DATE-FORMAT` | `X(10)` | Input     | 10-character format mask (e.g., `'YYYY-MM-DD'`, `'YYYYMMDD  '` left-justified, space-padded to 10 chars).                                                            |
  | `LS-RESULT`      | `X(80)` | Output    | 80-character caller-facing result message (matches the `WS-MESSAGE` working-storage structure at `app/cbl/CSUTLDTC.cbl:L42-57`).                                     |
  | `RETURN-CODE`    | (implicit) | Output | Numeric severity returned via `MOVE WS-SEVERITY-N TO RETURN-CODE` at `app/cbl/CSUTLDTC.cbl:L98`.                                                                     |

- Therefore, NO `app/data/ASCII/<program>.txt` fixture file exists for CSUTLDTC,
  and the conventional `input/` directory for fixture data is empty.
- Callers (e.g., `app/cpy/CSUTLDPY.cpy` via the `EDIT-DATE-LE` paragraph at
  `app/cpy/CSUTLDPY.cpy:L284-325`) construct the `LS-DATE` / `LS-DATE-FORMAT`
  values inline before issuing `CALL "CSUTLDTC" USING ...` — no file-based input
  feed exists.

## Where the actual test scenario inputs live

- The deterministic test scenario file `input_calls.txt` lives under the
  **sibling `../expected/` directory** (NOT here):
  [../expected/input_calls.txt](../expected/input_calls.txt).
- This placement is **per folder requirements** (cited verbatim):
  "`input_calls.txt` — synthesized CALL sequence (test-owned scaffolding, NOT a
  COBOL capture; lives under `expected/` because it's the deterministic test
  scenario contract)".
- The scenario file is **test-owned scaffolding** — both the COBOL baseline run
  (used to capture expected outputs) AND the Java translation under test are
  driven by the **same** scenario file. This makes it part of the EXPECTED
  contract: BOTH sides agree on the inputs in order to validate consistent
  outputs.
- See [../expected/README.md](../expected/README.md) for:
  - The comprehensive list of test scenarios that `input_calls.txt` MUST cover
    (12+ scenarios — e.g., valid leap-year date, invalid Feb 29 on a non-leap
    year, century 19 vs. century 20, blank input, non-numeric input, bad format
    mask).
  - The format of the scenario file (one CALL per line:
    `<LS-DATE>|<LS-DATE-FORMAT>` or equivalent).
  - The capture procedure for `stdout.txt` and `call_results.txt`.

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.DateValidatorGoldenTest`
  at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/DateValidatorGoldenTest.java`.
- It extends the abstract base class
  `com.blitzy.carddemo.tests.golden.GoldenRecordTest`.
- Its `inputFile()` override returns
  `resolveExpectedOutputPath("csutldtc", "input_calls.txt")` — confirming that
  the input scenario file is resolved from the `expected/` directory, **not**
  from this `input/` directory.
- Its `expectedOutputs()` override declares two captured outputs, both in
  `../expected/`:
  - `stdout.txt` — captured stdout from the COBOL CSUTLDTC reference run.
  - `call_results.txt` — captured per-call `LS-RESULT` + `RETURN-CODE` tuples.
- The test is `@Disabled` until both `../expected/input_calls.txt` AND the
  captured `../expected/stdout.txt` + `../expected/call_results.txt` are
  committed (per AAP §0.6.11: "Initial test scaffolding may use placeholder
  expected files marked `@Disabled` until COBOL captures are available").
- The Java class under test is
  `com.blitzy.carddemo.application.util.DateValidator`, which uses
  `java.time.LocalDate.parse(...)` with `ResolverStyle.STRICT` to replicate the
  CEEDAYS strict calendar semantics (per AAP §0.4.1: "replace LE service call
  with `LocalDate.parse` strict resolver; expose same input/output record shape
  as original LINKAGE SECTION").

## Capture procedure cross-reference

- See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure
  for CSUTLDTC) for the COBOL build/run path used to capture expected outputs.
- The capture procedure exists because the user prompt originally left the
  COBOL build/run path as an unresolved marker; that marker has now been
  resolved per AAP §0.7.5 by documenting the procedure in `MIGRATION_NOTES.md`.
- Until capture is performed, the `../expected/` directory holds
  placeholder/scaffolding files and the test remains `@Disabled`.

## Source lineage

- `app/cbl/CSUTLDTC.cbl` — COBOL source: CEEDAYS wrapper subroutine (158 lines).
  LINKAGE SECTION at `app/cbl/CSUTLDTC.cbl:L83-86` defines the `LS-DATE` /
  `LS-DATE-FORMAT` / `LS-RESULT` input/output contract; PROCEDURE DIVISION USING
  at `app/cbl/CSUTLDTC.cbl:L88` binds the three parameters; the `EVALUATE` on
  `FEEDBACK-CODE` at `app/cbl/CSUTLDTC.cbl:L128-149` maps the CEEDAYS feedback
  code to the 15-character `WS-RESULT` slot inside `LS-RESULT`.
- `app/cpy/CSUTLDPY.cpy` — Procedure Division copybook containing the
  `EDIT-DATE-LE` paragraph (`app/cpy/CSUTLDPY.cpy:L284-325`) that constructs
  `WS-EDIT-DATE-CCYYMMDD` and the literal `'YYYYMMDD'` format mask, then issues
  `CALL 'CSUTLDTC' USING WS-EDIT-DATE-CCYYMMDD, WS-DATE-FORMAT, WS-DATE-VALIDATION-RESULT`.
  Defines the canonical caller-side input-construction pattern.
- `app/cpy/CSUTLDWY.cpy` — Working-Storage copybook defining the shared
  `WS-EDIT-DATE-CCYYMMDD` data layout (century / year / month / day components
  with REDEFINES for numeric access) and the `WS-DATE-VALIDATION-RESULT` 80-byte
  structure that mirrors `LS-RESULT` field-for-field.

## Authority references

- AAP §0.2.1 (in-scope: `golden/` directory tree under `carddemo-tests`).
- AAP §0.4.1 (CSUTLDTC translation: "CEEDAYS wrapper; replace LE service call
  with `LocalDate.parse` strict resolver; expose same input/output record shape
  as original LINKAGE SECTION").
- AAP §0.6.11 (golden-record harness directory convention: every program has
  `input/` and `expected/` subdirectories).
- AAP §0.7.1 (Minimal Change Clause; preserve the LINKAGE SECTION shape
  verbatim).
- AAP §0.7.5 (capture procedure documented in `MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files,
`.gitkeep` placeholders, or any other content. This `README.md` IS the
directory's marker.

If a future test scenario requires fixture-based input for CSUTLDTC, evaluate
first whether the scenario actually requires a file-based input feed —
CSUTLDTC's contract has no such mechanism; introducing one would constitute a
behavior change beyond migration scope per AAP §0.7.1 — or whether the scenario
should instead be added to `../expected/input_calls.txt`.
