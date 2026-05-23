# CSUTLDTC Golden-Record Fixtures — expected/

This folder holds the **expected outputs** and the **synthesized scenario
contract** for the CSUTLDTC (CEEDAYS date-validation wrapper) golden-record
parity test. The test class is
`com.blitzy.carddemo.tests.golden.DateValidatorGoldenTest`
(`@Disabled` until all three data files documented below are committed with
non-placeholder content, per AAP §0.6.11).

CSUTLDTC is a callable subroutine: per `app/cbl/CSUTLDTC.cbl:L88` its entry
point is `PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT.` —
there is no fixture-style input file in `../input/`. The deterministic
scenario contract `input_calls.txt` lives here under `expected/` (per the
parent folder requirements) because it is **test-owned scaffolding shared by
BOTH the COBOL baseline capture run AND the Java translation under test**.
Both sides must agree on the input vocabulary in order for byte-for-byte
parity to be meaningful. See `../input/README.md` for the rationale behind
the intentionally empty sibling folder.

## Files in this folder

### `README.md` (this file)

This document. Authoritative contract for the CSUTLDTC golden-record
fixture: enumerates the data files, codifies the byte-level WS-MESSAGE
structure, lists the required test scenarios, declares the Java mapping
invariants, and cross-references the COBOL capture procedure.

### `input_calls.txt` — Synthesized CALL Sequence (test-owned scaffolding)

- This file is the **deterministic test scenario contract** consumed by
  `DateValidatorGoldenTest.inputFile()`.
- It is test-owned scaffolding — NOT a COBOL capture and NOT derived from
  `app/data/ASCII/`.
- It lives here under `expected/` (NOT under `../input/`) per folder
  requirements because BOTH the COBOL baseline run AND the Java translation
  are driven by this file. Both sides must agree on the input vocabulary
  in order for byte-for-byte parity to be meaningful.
- **Format**: one CALL per line. Each line carries two whitespace-separated
  fields:
  - Field 1: 10-character `LS-DATE` value (right-padded with spaces if
    shorter than 10 characters).
  - Field 2: 10-character `LS-DATE-FORMAT` value (right-padded with spaces
    if shorter than 10 characters).
- The 10-character field widths exactly match the COBOL LINKAGE SECTION
  layout (`LS-DATE PIC X(10)`, `LS-DATE-FORMAT PIC X(10)`) at
  `app/cbl/CSUTLDTC.cbl:L84-85`.
- Lines beginning with `#` are comments (ignored by the harness). Blank
  lines are also ignored.
- Example (illustrative, not the canonical content):

  ```text
  # Valid date in YYYY-MM-DD format
  2024-12-31 YYYY-MM-DD
  # Invalid calendar date (Feb 30)
  2024-02-30 YYYY-MM-DD
  ```

- **Status when initially committed**: present (synthesized at
  fixture-creation time). The Java test class `DateValidatorGoldenTest`
  is `@Disabled` until ALL three files (this one + `call_results.txt` +
  `stdout.txt`) are present with non-placeholder content.

### `call_results.txt` — Captured COBOL Per-CALL Return Records

- This file contains, for each CALL line in `input_calls.txt`, the captured
  80-character `LS-RESULT` returned by the COBOL CSUTLDTC reference run,
  optionally followed by the numeric `RETURN-CODE`.
- The 80-character structure mirrors `WS-MESSAGE` at
  `app/cbl/CSUTLDTC.cbl:L42-57`. The structure is composed of the following
  contiguous fields (total = 80 bytes):

  | Offset | Length | Field | Source Line | Notes |
  |--------|--------|-------|-------------|-------|
  | 0  | 4  | WS-SEVERITY        | L43-44 | 4-digit numeric severity (e.g., `'0000'`) via `WS-SEVERITY-N` REDEFINES |
  | 4  | 11 | FILLER 'Mesg Code:' | L45   | Literal — `'Mesg Code:'` = 10 chars + 1 trailing space |
  | 15 | 4  | WS-MSG-NO          | L46-47 | 4-digit numeric CEEDAYS message number |
  | 19 | 1  | FILLER SPACE       | L48    | Single space |
  | 20 | 15 | WS-RESULT          | L49    | One of 10 mapped messages (see EVALUATE table below) |
  | 35 | 1  | FILLER SPACE       | L50    | Single space |
  | 36 | 9  | FILLER 'TstDate:'  | L51    | Literal — `'TstDate:'` = 8 chars + 1 trailing space |
  | 45 | 10 | WS-DATE            | L52    | Echoed `LS-DATE` input |
  | 55 | 1  | FILLER SPACE       | L53    | Single space |
  | 56 | 10 | FILLER 'Mask used:' | L54   | Literal — `'Mask used:'` = exact 10 chars |
  | 66 | 10 | WS-DATE-FMT        | L55    | Echoed `LS-DATE-FORMAT` input |
  | 76 | 1  | FILLER SPACE       | L56    | Single space |
  | 77 | 3  | FILLER SPACES      | L57    | Three trailing spaces |
  | **80** | | | | **Total record length** |

  Arithmetic verification: 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3 = **80** bytes.

- After processing, the program issues `MOVE WS-SEVERITY-N TO RETURN-CODE`
  at `app/cbl/CSUTLDTC.cbl:L98` to expose the numeric severity to the
  caller. The Java translation MUST expose the same numeric severity via a
  `RETURN-CODE`-equivalent field on the output record.

- **EVALUATE feedback-code → WS-RESULT mapping** (verbatim from
  `app/cbl/CSUTLDTC.cbl:L128-149`). These 10 mappings constitute the
  complete fixture output vocabulary. The Java translation MUST reproduce
  them character-for-character (trailing spaces preserved):

  | Feedback Condition | WS-RESULT (15 chars) | CEEDAYS FB Token (hex) |
  |--------------------|----------------------|------------------------|
  | FC-INVALID-DATE      | `'Date is valid'` (+2 trailing spaces) | `X'0000000000000000'` |
  | FC-INSUFFICIENT-DATA | `'Insufficient'` (+3 trailing spaces)  | `X'000309CB59C3C5C5'` |
  | FC-BAD-DATE-VALUE    | `'Datevalue error'` (exact 15)         | `X'000309CC59C3C5C5'` |
  | FC-INVALID-ERA       | `'Invalid Era    '` (4 trailing spaces) | `X'000309CD59C3C5C5'` |
  | FC-UNSUPP-RANGE      | `'Unsupp. Range  '` (2 trailing spaces) | `X'000309D159C3C5C5'` |
  | FC-INVALID-MONTH     | `'Invalid month  '` (2 trailing spaces) | `X'000309D559C3C5C5'` |
  | FC-BAD-PIC-STRING    | `'Bad Pic String '` (1 trailing space)  | `X'000309D659C3C5C5'` |
  | FC-NON-NUMERIC-DATA  | `'Nonnumeric data'` (exact 15)         | `X'000309D859C3C5C5'` |
  | FC-YEAR-IN-ERA-ZERO  | `'YearInEra is 0 '` (1 trailing space)  | `X'000309D959C3C5C5'` |
  | WHEN OTHER           | `'Date is invalid'` (exact 15)         | (any other token)      |

  The condition name `FC-INVALID-DATE` is a misleading legacy COBOL
  identifier: its CEEDAYS feedback token `X'0000000000000000'` is the
  "OK" feedback code, and its WS-RESULT text is `'Date is valid'`. Do NOT
  confuse the condition name with an actual invalid-date result.

- **Capture procedure**: the COBOL reference run reads `input_calls.txt`,
  invokes CSUTLDTC for each line, and appends the 80-character `LS-RESULT`
  (plus the trailing `RETURN-CODE` value if logged) to this file. The
  exact build/run path is documented in `java/MIGRATION_NOTES.md` §1.6.

- **Byte-for-byte parity rule**: the Java `DateValidator` MUST produce,
  for each input line, a `LS-RESULT` byte sequence equal to the captured
  value in this file bit-for-bit (no trailing-whitespace normalization, no
  character substitution, no encoding shift). The `DateValidatorGoldenTest`
  parity assertion is the binding gate.

- **Status when initially committed**: PLACEHOLDER (empty or minimal
  scaffolding) — `DateValidatorGoldenTest` remains `@Disabled` until the
  file contains captured outputs that exactly correspond to every line in
  `input_calls.txt`.

### `stdout.txt` — Captured COBOL DISPLAY / Harness Log Output

- The CSUTLDTC program source contains a commented-out `DISPLAY WS-MESSAGE`
  at `app/cbl/CSUTLDTC.cbl:L96` (the line is preceded by a `*` comment
  marker and does NOT execute). Therefore, CSUTLDTC produces **no inherent
  DISPLAY output** during normal operation.
- This `stdout.txt` file therefore captures the **harness-driven log
  output** produced when the COBOL reference driver iterates through
  `input_calls.txt`, calls CSUTLDTC, and writes a structured trace to
  stdout (one log line per call, typically echoing the input line and the
  80-character `LS-RESULT`).
- The Java test driver in `DateValidatorGoldenTest` produces an equivalent
  stdout trace; byte-for-byte parity with this file constitutes the
  **expected output** of `expectedOutputFile()` per the `GoldenRecordTest`
  base class contract.
- **Format**: line-based ASCII text. The exact format MUST be agreed
  between the COBOL driver and the Java driver such that both produce
  identical bytes given identical `input_calls.txt`. The format MUST be
  specified in `java/MIGRATION_NOTES.md` §1.6 alongside the capture
  procedure.
- **Status when initially committed**: PLACEHOLDER —
  `DateValidatorGoldenTest` remains `@Disabled` until the file contains
  captured stdout that exactly matches what the Java driver produces given
  the committed `input_calls.txt`.

## Required test scenarios

`input_calls.txt` MUST exercise — at minimum — every distinct CEEDAYS
feedback code branch in the EVALUATE block at
`app/cbl/CSUTLDTC.cbl:L128-149`. The following scenarios constitute the
required activation set:

1. **Valid date (well-formed Gregorian)** — `LS-DATE='2024-12-31'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → expected WS-RESULT `'Date is valid  '`
   (with 2 trailing spaces), severity `'0000'`.
2. **Invalid calendar date (Feb 30)** — `LS-DATE='2024-02-30'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → CEEDAYS feedback `FC-BAD-DATE-VALUE`
   → expected WS-RESULT `'Datevalue error'`.
3. **Invalid calendar date (Apr 31)** — `LS-DATE='2024-04-31'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-BAD-DATE-VALUE` →
   `'Datevalue error'`.
4. **Leap-year valid** — `LS-DATE='2024-02-29'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-INVALID-DATE` (the OK feedback) →
   `'Date is valid  '`. 2024 IS a leap year.
5. **Leap-year invalid** — `LS-DATE='2023-02-29'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-BAD-DATE-VALUE` →
   `'Datevalue error'`. 2023 is NOT a leap year.
6. **Year boundary low** — `LS-DATE='0001-01-01'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → CEEDAYS-specific behavior. Document
   the captured COBOL behavior in `java/MIGRATION_NOTES.md` §1.6;
   expected to be either `'Date is valid  '` (within CEEDAYS supported
   range) OR `'Unsupp. Range  '` (`FC-UNSUPP-RANGE`).
7. **Year boundary high** — `LS-DATE='9999-12-31'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → `'Date is valid  '`.
8. **Format mismatch** — `LS-DATE='2024/12/31'`,
   `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-NON-NUMERIC-DATA` OR
   `FC-BAD-PIC-STRING` (depending on CEEDAYS heuristic) →
   `'Nonnumeric data'` or `'Bad Pic String '`.
9. **Insufficient data** — `LS-DATE='2024-12   '` (with trailing spaces;
   day missing), `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-INSUFFICIENT-DATA`
   → `'Insufficient   '` (with 3 trailing spaces).
10. **Invalid month** — `LS-DATE='2024-13-15'`,
    `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-INVALID-MONTH` →
    `'Invalid month  '`.
11. **Year-in-era zero** — input crafted to trigger CEEDAYS
    year-in-era-zero feedback (e.g., a 0 year-of-era with explicit era
    prefix) → `FC-YEAR-IN-ERA-ZERO` → `'YearInEra is 0 '`.
12. **Bad PIC string (invalid format pattern)** — `LS-DATE='2024-12-31'`,
    `LS-DATE-FORMAT='XYZ       '` (10-char invalid format) →
    `FC-BAD-PIC-STRING` → `'Bad Pic String '`.
13. **Pre-CEEDAYS-range date** — `LS-DATE='1582-12-31'` (pre-1583
    Gregorian cutover), `LS-DATE-FORMAT='YYYY-MM-DD'` → `FC-UNSUPP-RANGE`
    → `'Unsupp. Range  '`.
14. **Invalid era** — input crafted to trigger an era-validation failure
    (CEEDAYS-specific; document if not applicable to the format masks in
    use) → `FC-INVALID-ERA` → `'Invalid Era    '`.

Scenarios 11 and 14 MAY be omitted if CEEDAYS does not produce those
feedback codes for any feasible CSUTLDTC input under the formats actually
used by CardDemo callers (which use `'YYYY-MM-DD'` per `app/cbl/COTRN02C.cbl`
and `'YYYYMMDD'` per the `EDIT-DATE-LE` paragraph in `app/cpy/CSUTLDPY.cpy`).
If omitted, document the omission in `java/MIGRATION_NOTES.md` §1.6.

## Java mapping invariants

- **`ResolverStyle.STRICT` is MANDATORY**. The Java `DateValidator` MUST
  construct its formatters via
  `java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(java.time.format.ResolverStyle.STRICT)`
  (and analogous for `'YYYYMMDD'`). The default `ResolverStyle.SMART`
  silently coerces Feb 30 → Feb 28/29, which would BREAK parity with
  CEEDAYS rejection behavior (scenario 2). Reference: AAP §0.4.1 ("strict
  resolver").
  - Use the format-pattern letter `uuuu` (year, strict) rather than `yyyy`
    (year-of-era) under STRICT resolver to avoid the era ambiguity that
    `yyyy` triggers in strict mode.
- **NO `java.util.Date`, `java.util.Calendar`, `java.text.SimpleDateFormat`**
  anywhere in the Java implementation. Use only `java.time` types.
  Reference: AAP §0.6.4.
- **LINKAGE SECTION shape preserved verbatim**. The Java method signature
  MUST accept and return records mirroring the COBOL byte-level contract
  at `app/cbl/CSUTLDTC.cbl:L83-86`:
  - Input record: 10-char date string + 10-char format-mask string.
  - Output record: 80-char `LS-RESULT` payload composed per the WS-MESSAGE
    structure table above (offsets 0..79).
  - Numeric severity exposed via a `RETURN-CODE`-equivalent field on the
    output record (mirrors `MOVE WS-SEVERITY-N TO RETURN-CODE` at
    `app/cbl/CSUTLDTC.cbl:L98`).
- **Byte-for-byte parity rule**: for every line in `input_calls.txt`,
  `DateValidator.invoke(lsDate, lsDateFormat)` MUST produce a `lsResult`
  byte array equal to the corresponding captured value in
  `call_results.txt`. Trailing spaces, FILLER literal text, and field
  padding must all match byte-for-byte.
- **Feedback code → WS-RESULT mapping preserved exactly**: the Java
  implementation reproduces the 10-row mapping table (FC-INVALID-DATE
  through WHEN OTHER) shown above. Use a `sealed interface` or `switch`
  expression in idiomatic Java 25; no `default` branch that hides missing
  cases. Reference: AAP §0.6.10, AAP §0.7.3.
- **No card PAN logged**: not directly applicable to CSUTLDTC (no PAN
  data flows through the LINKAGE SECTION), but the harness-driven
  `stdout.txt` MUST NOT include any data that would conflict with PCI
  logging mandates. Reference: AAP §0.7.2.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture
procedure for CSUTLDTC) for the canonical COBOL build/run path, the
COBOL driver source (which iterates `input_calls.txt`), the encoding
(ASCII; not EBCDIC since this fixture corresponds to ASCII-mode
capture), and the exact stdout format produced by the harness driver.

The capture procedure exists because the user prompt originally left this
as a `[TODO]` marker per AAP §0.7.5: *"To regenerate golden-record
fixtures from COBOL [TODO — document the COBOL build/run path here]."*
The resolution is to document the procedure in
`java/MIGRATION_NOTES.md` §1.6 rather than embed it here. Until capture
is performed, `call_results.txt` and `stdout.txt` MAY be empty
placeholder files and `DateValidatorGoldenTest` remains `@Disabled`.

## Test class @Disabled mandate

`DateValidatorGoldenTest.java` is annotated `@Disabled` until ALL three
data files (`input_calls.txt`, `call_results.txt`, `stdout.txt`) are
committed with non-placeholder content. The `@Disabled` annotation's
`value` parameter cites this README and references AAP §0.6.11.

The 9-point activation checklist (excerpted from the
`DateValidatorGoldenTest.java` agent prompt) — every item MUST pass
before the `@Disabled` annotation is removed:

1. Valid YYYY-MM-DD date parses to `java.time.LocalDate`.
2. Invalid calendar date (e.g., Feb 30) returns the specific CEEDAYS
   error code matching `FC-BAD-DATE-VALUE`.
3. Format mismatch returns the CEEDAYS format-error code
   (`FC-NON-NUMERIC-DATA` or `FC-BAD-PIC-STRING`).
4. Leap-year dates: `2024-02-29` is OK; `2023-02-29` is rejected.
5. Year boundaries (`0001-01-01`, `9999-12-31`) match CEEDAYS behavior
   captured in `call_results.txt`.
6. Blank / insufficient input returns `FC-INSUFFICIENT-DATA` →
   `'Insufficient   '`.
7. Pre-CEEDAYS-range dates (pre-1583 Gregorian) return `FC-UNSUPP-RANGE`
   → `'Unsupp. Range  '`.
8. LINKAGE SECTION output record shape preserved verbatim
   (`app/cpy/CSUTLDPY.cpy` / `app/cpy/CSUTLDWY.cpy` layout).
9. `DateTimeFormatter` uses `ResolverStyle.STRICT` (not `SMART` or
   `LENIENT`).

## Cross-references

- `../input/README.md` — sibling marker explaining why the conventional
  `input/` folder is intentionally empty for this callable-subroutine
  program.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/DateValidatorGoldenTest.java`
  — the JUnit 5 test class consuming this fixture (currently `@Disabled`
  pending capture).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
  — the abstract base class defining `inputFile()`, `expectedOutputFile()`,
  `expectedOutputs()`, `resolveExpectedOutputPath(String, String)`, and
  the byte-for-byte parity assertion.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/util/DateValidator.java`
  — the Java class under test (fully-qualified name
  `com.blitzy.carddemo.application.util.DateValidator`; CEEDAYS
  replacement; uses `LocalDate.parse(...)` with `ResolverStyle.STRICT`).
- `java/MIGRATION_NOTES.md` §1.6 — capture procedure documentation
  (resolves the user `[TODO]` marker per AAP §0.7.5).

## Source lineage

- `app/cbl/CSUTLDTC.cbl` — COBOL source: CEEDAYS wrapper subroutine
  (158 lines). LINKAGE SECTION at L83-86, PROCEDURE DIVISION at L88,
  WS-MESSAGE structure at L42-57, CEEDAYS CALL at L116-120, EVALUATE
  feedback mapping at L128-149. `MOVE WS-MESSAGE TO LS-RESULT` at L97;
  `MOVE WS-SEVERITY-N TO RETURN-CODE` at L98. Commented-out
  `DISPLAY WS-MESSAGE` at L96 (does NOT execute).
- `app/cpy/CSUTLDPY.cpy` — Procedure Division copybook containing the
  `EDIT-DATE-LE` paragraph that CALLs CSUTLDTC with the literal
  `'YYYYMMDD'` format. Defines the canonical caller-side
  input-construction pattern used by `CBTRN03C` and similar batch
  programs.
- `app/cpy/CSUTLDWY.cpy` — Working-storage copybook defining the shared
  `WS-EDIT-DATE-CCYYMMDD` data layout (century / year / month / day
  components, with 88-level `THIS-CENTURY VALUE 20` and
  `LAST-CENTURY VALUE 19`) and the 80-byte `WS-DATE-VALIDATION-RESULT`
  envelope that mirrors `WS-MESSAGE` in CSUTLDTC.

## Authority references

- AAP §0.2.1 (in-scope: `golden/` directory tree under `carddemo-tests`)
- AAP §0.3.1 (golden-record harness structure under
  `java/carddemo-tests/src/test/resources/golden/<program>/{input,expected}/`)
- AAP §0.4.1 (CSUTLDTC → `DateValidator` translation: "CEEDAYS wrapper;
  replace LE service call with `LocalDate.parse` strict resolver; expose
  same input/output record shape as original LINKAGE SECTION")
- AAP §0.6.4 (`java.time.*` mandate; NO `java.util.Date` or
  `java.util.Calendar`)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` scaffolding
  pattern)
- AAP §0.7.1 (Minimal Change Clause; preserve LINKAGE SECTION shape
  verbatim)
- AAP §0.7.5 (capture procedure documented in
  `java/MIGRATION_NOTES.md` §1.6)

## DO NOT modify the fixture data without re-capture

The three data files in this folder (`input_calls.txt`,
`call_results.txt`, `stdout.txt`) are a **coupled set**. If
`input_calls.txt` is modified (scenarios added, removed, or reordered),
then `call_results.txt` and `stdout.txt` MUST be re-captured from the
COBOL reference run per the procedure in `java/MIGRATION_NOTES.md` §1.6.
Ad-hoc edits to expected outputs without re-capture WILL break
byte-for-byte parity. Do NOT "fix" what looks like odd-formatting in the
expected files — those are CEEDAYS's observable behavior and must be
reproduced faithfully per AAP §0.7.1.
