# CORPT00C (Transaction Reports, CR00) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is **intentionally documentation-only** — it contains NO fixture data files (no `.txt`, no `.jsonl`, no `.bin`, no `.csv`, no `.gitkeep`, no subfolders). CORPT00C is the **online CICS Transaction Reports submission program** (transaction `CR00`, source `app/cbl/CORPT00C.cbl`, 649 lines total) translated to Java `com.blitzy.carddemo.application.report.CoRpt00C` per AAP §0.4.1. CORPT00C is an **online-to-batch bridge**: it collects a report mode (Monthly, Yearly, or Custom) plus an optional custom date range plus a Y/N confirmation, then writes a 17-line JCL skeleton to the CICS Transient Data Queue `JOBS` for downstream batch execution by the `TRANREPT` JCL/procedure. The Java translation routes the JCL skeleton write to a direct-invocation port (per AAP §0.4.1 IMPLEMENTATION DECISION — CICS TDQ → direct invocation) since CICS TDQ replacement orchestration is out of scope. CORPT00C is **READ-NONE-WRITE-TDQ** at the file-system level: it does not READ or WRITE any VSAM dataset; it declares but does not actively use the TRANSACT/CVTRA05Y layout (declared at `app/cbl/CORPT00C.cbl:L146`; `WS-TRANSACT-FILE` literal at L40). Its sole side effect is the `WRITEQ TD` on the JOBS queue at `app/cbl/CORPT00C.cbl:L517-L523`, translated to direct invocation in Java. The deterministic test scenario file (`input_scenario.txt`), the captured `stdout.txt`, the serialized BMS `bms_output.txt`, and the captured JCL deck artifact (`jcl_skeleton.txt`) ALL live in the sibling `../expected/` folder, because the consuming Java test class `CoRpt00CGoldenTest` resolves all fixture paths through the base class helper `resolveExpectedOutputPath("corpt00c", ...)` → `src/test/resources/golden/corpt00c/expected/`.

## Why this folder is documentation-only

1. CORPT00C is an **online CICS pseudo-conversational program** (transaction `CR00`). Its user-facing input arrives via the BMS map `CORPT0A` defined in `app/bms/CORPT00.bms`. The source COBOL working-storage at `app/cbl/CORPT00C.cbl:L37-L40` declares `WS-PGMNAME PIC X(08) VALUE 'CORPT00C'` (L37), `WS-TRANID PIC X(04) VALUE 'CR00'` (L38), `WS-MESSAGE PIC X(80) VALUE SPACES` (L39), and `WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'` (L40 — declared but unused for file I/O in CORPT00C). The 17-line JCL skeleton lives inline as a static literal block `JOB-DATA` at `app/cbl/CORPT00C.cbl:L81-L127`.
2. Inputs are not file-based fixtures sourced from `app/data/ASCII/*.txt`. Instead, the deterministic test scenario is encoded as a **synthesized scenario script** that drives a CICS pseudo-conversation; the scenario script is **test-owned scaffolding** authored alongside the captured expected outputs in `../expected/`.
3. Per the sibling precedent established by `golden/cosgn00c/input/`, `golden/cotrn00c/input/`, `golden/cousr00c/input/`, etc., the conventional `input/` subdirectory is preserved as a documentation marker even though no fixture data files reside there.
4. The harness convention from AAP §0.3.1 and §0.6.11 mandates the `input/` + `expected/` pairing per program for discoverability and parallel structure across all 28 program test folders.
5. The consuming test class `CoRpt00CGoldenTest` resolves all fixture paths through the base class helper `resolveExpectedOutputPath(programDir, fileName)`, which maps every path to `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely: `inputFile()` returns `resolveExpectedOutputPath("corpt00c", "input_scenario.txt")` → `../expected/input_scenario.txt`; `expectedOutputs()` returns entries for `stdout.txt`, `bms_output.txt`, and `jcl_skeleton.txt` — all under `../expected/`. All overrides route to `../expected/`; none routes to this `input/` folder.

The sibling `../expected/` folder contains:

- `../expected/README.md` — Authoritative fixture contract for the CORPT00C golden-record test.
- `../expected/input_scenario.txt` — CICS pseudo-conversation driver script (TEST-OWNED).
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER). Contains `'PROCESS ENTER KEY'` from `app/cbl/CORPT00C.cbl:L210` plus any WHEN OTHER error-path `'RESP:' / 'REAS:'` DISPLAY from L529.
- `../expected/bms_output.txt` — Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER).
- `../expected/jcl_skeleton.txt` — Byte-identical reproduction of the 17-line JCL deck emitted by `SUBMIT-JOB-TO-INTRDR` / `WIRTE-JOBSUB-TDQ` (CAPTURE PLACEHOLDER). The Java direct-invocation port captures the same 17 80-byte lines that COBOL `WRITEQ TD`s to the JOBS queue.

## Conceptual input contract (documented; data lives in ../expected/)

### BMS map inputs: 3 radio-selectors + 6 date components + 1 confirm

The CORPT0A BMS map (defined in `app/bms/CORPT00.bms` and symbolic copybook
`app/cpy-bms/CORPT00.CPY`) presents a single-screen submission form. The editable input fields and
output fields are:

| Field | Position | Length | Attribute | Direction | Notes |
|---|---|---|---|---|---|
| `MONTHLY` | (7,10) | 1 char | GREEN, FSET, **IC**, NORM, UNPROT, UNDERLINE | Input | Monthly radio selector (current month); IC = initial cursor on first display |
| `YEARLY` | (9,10) | 1 char | GREEN, FSET, NORM, UNPROT, UNDERLINE | Input | Yearly radio selector (current year) |
| `CUSTOM` | (11,10) | 1 char | GREEN, FSET, NORM, UNPROT, UNDERLINE | Input | Custom radio selector (manual date range) |
| `SDTMM` | (13,29) | 2 chars | GREEN, FSET, NORM, NUM, UNPROT, UNDERLINE | Input | Start date month (01-12) |
| `SDTDD` | (13,34) | 2 chars | GREEN, FSET, NORM, NUM, UNPROT, UNDERLINE | Input | Start date day (01-31) |
| `SDTYYYY` | (13,39) | 4 chars | GREEN, FSET, NORM, NUM, UNPROT, UNDERLINE | Input | Start date year (YYYY) |
| `EDTMM` | (14,29) | 2 chars | GREEN, FSET, NORM, NUM, UNPROT, UNDERLINE | Input | End date month (01-12) |
| `EDTDD` | (14,34) | 2 chars | GREEN, FSET, NORM, NUM, UNPROT, UNDERLINE | Input | End date day (01-31) |
| `EDTYYYY` | (14,39) | 4 chars | GREEN, FSET, NORM, NUM, UNPROT, UNDERLINE | Input | End date year (YYYY) |
| `CONFIRM` | (19,66) | 1 char | GREEN, FSET, NORM, UNPROT, UNDERLINE | Input | Y/N submission confirmation |
| `ERRMSG` | (23,1) | 78 chars | RED, ASKIP, BRT, FSET | Output | Error/info/success message text |
| `PGMNAME`, `TRNNAME`, `TITLE01`, `TITLE02`, `CURDATE`, `CURTIME` | rows 1-2 | various | BLUE / YELLOW, ASKIP, FSET | Output | Header fields populated by `POPULATE-HEADER-INFO` at `app/cbl/CORPT00C.cbl:L609-L628` |

**CRITICAL distinguisher**: CORPT00C has NO `PASSWD` field (unlike COSGN00C); NO 16-digit PAN field
(unlike COCRDLI/COCRDSL/COCRDUP); NO pagination (unlike COTRN00C); NO row-selectors (unlike
COTRN00C/COUSR00C). The map is a single-screen submission form whose only inputs are 3 mutually-
exclusive radio selectors, 6 date components, and a Y/N confirmation.

### CICS pseudo-conversational dispatch (2 named AID keys + WHEN OTHER)

The `EVALUATE EIBAID` block at `app/cbl/CORPT00C.cbl:L184-L195` routes each AID key to its handler:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN OTHER
        MOVE 'Y'                       TO WS-ERR-FLG
        MOVE -1       TO MONTHLYL OF CORPT0AI
        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
        PERFORM SEND-TRNRPT-SCREEN
END-EVALUATE
```

CORPT00C handles only **2 named AID keys** (`DFHENTER`, `DFHPF3`) plus `WHEN OTHER`. The PF3 destination is `COMEN01C` (Main Menu) — NOT `COSGN00C`; `COSGN00C` is reached only when `EIBCALEN = 0` at first-time entry per `app/cbl/CORPT00C.cbl:L172-L174`. Contrast: `COTRN00C` handles 4 named keys (`DFHENTER`, `DFHPF3`, `DFHPF7`, `DFHPF8`) — CORPT00C is a single-screen form without pagination, so it advertises only `'ENTER=Continue  F3=Back'` at `app/bms/CORPT00.bms:L226`. The Java translation's pattern-matching switch on a sealed `AidKey` hierarchy MUST be exhaustive over the same set per AAP §0.7.4 — NO `default` branch that masks missing cases.

### Three mutually-exclusive report modes + JCL submission

CORPT00C's execution sequence proceeds through these steps:

1. **First connection (`EIBCALEN = 0`)** at `app/cbl/CORPT00C.cbl:L172-L174`: `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM`, `PERFORM RETURN-TO-PREV-SCREEN` — invalid direct entry is routed to signon.
2. **First reentry pass (`EIBCALEN > 0`, `NOT CDEMO-PGM-REENTER`)** at L177-L181: `SET CDEMO-PGM-REENTER TO TRUE`, `MOVE LOW-VALUES TO CORPT0AO`, `MOVE -1 TO MONTHLYL OF CORPT0AI` (cursor lands on MONTHLY due to its `IC` attribute and the explicit `MONTHLYL = -1`), `PERFORM SEND-TRNRPT-SCREEN`.
3. **Subsequent reentry — ENTER pressed** at L185-L186 → `PROCESS-ENTER-KEY` at L208: the outer `EVALUATE TRUE` selects exactly ONE of 4 branches:
   - `MONTHLYI NOT = SPACES/LOW-VALUES` (L213-L238) → `MOVE 'Monthly' TO WS-REPORT-NAME`, derive start date = first day of current month and end date = last day of current month via `DATE-OF-INTEGER` / `INTEGER-OF-DATE` arithmetic at L223-L230, `PERFORM SUBMIT-JOB-TO-INTRDR`.
   - `YEARLYI NOT = SPACES/LOW-VALUES` (L239-L255) → `MOVE 'Yearly'`, start date = `YYYY-01-01`, end date = `YYYY-12-31`, `PERFORM SUBMIT-JOB-TO-INTRDR`.
   - `CUSTOMI NOT = SPACES/LOW-VALUES` (L256-L436): inner `EVALUATE TRUE` for 6 empty-field checks at L258-L303; then `NUMVAL-C` normalization at L305-L327; then 6 format validations at L329-L379; then assemble `WS-START-DATE` / `WS-END-DATE` at L381-L386; `CALL 'CSUTLDTC' USING CSUTLDTC-DATE CSUTLDTC-DATE-FORMAT CSUTLDTC-RESULT` (twice — once each for start and end date) at L392-L394 and L412-L414; check `CSUTLDTC-RESULT-SEV-CD = '0000'` (success) or `MSG-NUM NOT = '2513'` (true error → emit message); if NOT `ERR-FLG-ON` → `PERFORM SUBMIT-JOB-TO-INTRDR`.
   - `WHEN OTHER` (L437-L442) → `MOVE 'Select a report type to print report...' TO WS-MESSAGE`.
4. **`SUBMIT-JOB-TO-INTRDR`** at L462: confirm-prompt — empty `CONFIRMI` triggers `'Please confirm to print the <name> report...'` at L466-L470; `CONFIRMI='Y'/'y'` → `CONTINUE`; `CONFIRMI='N'/'n'` → `INITIALIZE-ALL-FIELDS` + reshow screen; `WHEN OTHER` → `'"X" is not a valid value to confirm...'` STRINGed at L485-L490.
5. **JCL skeleton emission** at L498-L508: `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 1000 OR END-LOOP-YES OR ERR-FLG-ON`; `MOVE JOB-LINES(WS-IDX) TO JCL-RECORD`; if `JCL-RECORD = '/*EOF' OR SPACES OR LOW-VALUES` → `SET END-LOOP-YES TO TRUE`; `PERFORM WIRTE-JOBSUB-TDQ`. The 17-line JCL skeleton from `JOB-DATA` at L81-L125 is written line-by-line; the `/*EOF` marker at L125 terminates the loop.
6. **`WIRTE-JOBSUB-TDQ`** at L515-L535 (note the COBOL paragraph-name typo `WIRTE` preserved verbatim): `EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)`; in Java this is replaced by direct method invocation on a `JclSkeletonSink` port (per AAP §0.4.1 IMPLEMENTATION DECISION). Each call emits one 80-byte line.
7. **PF3 pressed** at L187-L189 → `MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM`, `PERFORM RETURN-TO-PREV-SCREEN` → `EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(CARDDEMO-COMMAREA)` at L548-L551.
8. **Other AID key** → `WHEN OTHER` branch: `CCDA-MSG-INVALID-KEY` (from `app/cpy/CSMSG01Y.cpy:L20-L21`).

The PARM-START-DATE / PARM-END-DATE byte-offsets within the `JOB-DATA` literal block (each line is exactly 80 bytes):

- `PARM-START-DATE-1` lives in `FILLER-1` at `app/cbl/CORPT00C.cbl:L103-L107` of JCL skeleton Line 11; it occupies bytes 19-28 (after the 18-byte literal `"PARM-START-DATE,C'"`); bytes 29-80 hold a 52-byte literal `"'"` + 51 spaces.
- `PARM-END-DATE-1` lives in `FILLER-2` at `app/cbl/CORPT00C.cbl:L108-L112` of JCL skeleton Line 12; it occupies bytes 17-26 (after the 16-byte literal `"PARM-END-DATE,C'"`); bytes 27-80 hold a 54-byte literal `"'"` + 53 spaces.
- `PARM-START-DATE-2` + space + `PARM-END-DATE-2` live in `FILLER-3` at `app/cbl/CORPT00C.cbl:L117-L121` of JCL skeleton Line 15 (the `DATEPARM` block) — bytes 1-10 = `PARM-START-DATE-2`, byte 11 = SPACE, bytes 12-21 = `PARM-END-DATE-2`, bytes 22-80 = 59 SPACES.

The Java fixture asserts byte-identical reproduction of all 17 lines with these 4 date placeholders substituted per the captured scenario.

### Conceptual fixture data (lives in ../expected/)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|---|---|---|---|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script |
| `stdout.txt` | `../expected/stdout.txt` | CAPTURE PLACEHOLDER | Captured COBOL DISPLAY output |
| `bms_output.txt` | `../expected/bms_output.txt` | CAPTURE PLACEHOLDER | Serialized BMS SEND MAP output across scenarios |
| `jcl_skeleton.txt` | `../expected/jcl_skeleton.txt` | CAPTURE PLACEHOLDER | 17-line JCL deck byte-identical to TDQ JOBS write |

The scenario file MUST cover the following required test scenarios:

1. **Initial display** (`EIBCALEN > 0`, first reentry pass) → cursor on `MONTHLY`.
2. **No report mode selected** → `'Select a report type to print report...'`.
3. **Monthly mode + valid `CONFIRM='Y'`** → 17-line JCL emitted with first-of-month / last-of-month dates derived from `FUNCTION CURRENT-DATE` (test fixture MUST use a fixed `Clock` via `ScopedValue<Clock>` per AAP §0.6.4 for deterministic dates).
4. **Yearly mode + valid `CONFIRM='Y'`** → 17-line JCL emitted with `YYYY-01-01` / `YYYY-12-31` dates.
5. **Custom mode + valid dates + `CONFIRM='Y'`** → 17-line JCL emitted with user-supplied dates.
6. **Custom mode + empty `SDTMM`** → `'Start Date - Month can NOT be empty...'`.
7. **Custom mode + non-numeric / out-of-range date component** → respective `'Start Date - Not a valid <Month/Day/Year>...'` or `'End Date - Not a valid <Month/Day/Year>...'`.
8. **Custom mode + CSUTLDTC failure** (e.g., Feb 30) → `'Start Date - Not a valid date...'` or `'End Date - Not a valid date...'`.
9. **Any mode + empty `CONFIRM`** → `'Please confirm to print the <mode> report...'`.
10. **Any mode + `CONFIRM='X'` (invalid)** → `'"X" is not a valid value to confirm...'`.
11. **Any mode + `CONFIRM='N'`** → `INITIALIZE-ALL-FIELDS` + screen redisplay.
12. **PF3** → XCTL `COMEN01C`.
13. **Other AID key** (e.g., PF12) → `CCDA-MSG-INVALID-KEY`.
14. **Deterministic Clock**: ALL Monthly/Yearly scenarios MUST be run under a fixed `Clock` (e.g., `2024-06-15`) via `ScopedValue<Clock>` so `FUNCTION CURRENT-DATE` produces predictable JCL output for byte-identical assertion (AAP §0.6.4, §0.6.6).

## Cross-reference to the Java test class

The Java test class is `com.blitzy.carddemo.tests.golden.CoRpt00CGoldenTest` at
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoRpt00CGoldenTest.java`. It
extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` at
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`. The
class under test is `com.blitzy.carddemo.application.report.CoRpt00C` (note the `report`
subpackage per AAP §0.4.1).

Override summary (all overrides route to `../expected/`):

- `programClass()` returns `com.blitzy.carddemo.application.report.CoRpt00C.class`.
- `inputFile()` returns `resolveExpectedOutputPath("corpt00c", "input_scenario.txt")` — routes to
  `../expected/input_scenario.txt`.
- `expectedOutputFile()` returns `resolveExpectedOutputPath("corpt00c", "stdout.txt")` — routes to
  `../expected/stdout.txt`.
- `expectedOutputs()` returns `ExpectedOutput` entries for `stdout.txt`, `bms_output.txt`, and
  `jcl_skeleton.txt` (all under `../expected/`). **NO `transact_after.txt` or similar
  after-fixture** — CORPT00C is a TDQ-write-only program; it does not modify VSAM file state.

**All overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper. None
routes to this `input/` folder.** This is the architectural reason the folder is empty.

The test is `@Disabled` per AAP §0.6.11 (initial test scaffolding uses placeholder expected files
marked `@Disabled` until COBOL captures are committed; the harness skeleton, base class, and
per-program test classes are created unconditionally) with the following verification list:

1. Initial display semantics: `EIBCALEN > 0` first reentry → `MONTHLY` field has cursor.
2. `EIBCALEN = 0` routing to `COSGN00C` via `RETURN-TO-PREV-SCREEN`.
3. No mode selected → `'Select a report type to print report...'`.
4. Monthly mode date derivation: first-of-month to last-of-month under fixed `Clock`.
5. Yearly mode date derivation: `YYYY-01-01` to `YYYY-12-31` under fixed `Clock`.
6. Custom mode: 6 empty-field error messages; 6 numeric/range validation messages; 2 `CSUTLDTC`
   date-validity calls.
7. `CSUTLDTC` success (`CSUTLDTC-RESULT-SEV-CD = '0000'`) → `CONTINUE`.
8. `CSUTLDTC` failure with `MSG-NUM = '2513'` → `CONTINUE` (special case).
9. `CSUTLDTC` failure with `MSG-NUM NOT = '2513'` → emit
   `'<Start|End> Date - Not a valid date...'`.
10. `CONFIRM` Y/N/X/empty handling — all 4 paths exercised.
11. JCL skeleton emission: 17 lines, byte-identical to `JOB-DATA` template with
    `PARM-START-DATE-1/2` and `PARM-END-DATE-1/2` placeholders substituted; `/*EOF` terminates the
    loop. The Java direct-invocation port `JclSkeletonSink` receives the same sequence the COBOL
    TDQ JOBS write would have received.
12. PF3 → XCTL `COMEN01C`; PF-other → `CCDA-MSG-INVALID-KEY`.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CORPT00C) for the CICS COBOL build/run path used to capture expected outputs. The capture procedure is documented per AAP §0.7.5, which carries forward the user's unresolved capture-path marker; the resolution is to document the procedure in `MIGRATION_NOTES.md` rather than embed it in this README.

**Deterministic Clock requirement**: CORPT00C's Monthly and Yearly modes derive dates from
`FUNCTION CURRENT-DATE` at `app/cbl/CORPT00C.cbl:L215, L241, L611`. To produce a byte-identical JCL
skeleton for parity assertion, the capture procedure MUST run COBOL under a fixed clock; the Java
translation uses `ScopedValue<Clock>` (per AAP §0.6.4 and §0.6.6) to inject the same fixed `Clock`
during test execution. The `Clock` value is documented in `MIGRATION_NOTES.md` §1.6.

Until the capture is performed and committed, the `../expected/stdout.txt`,
`../expected/bms_output.txt`, and `../expected/jcl_skeleton.txt` files hold placeholder content and
the test is `@Disabled`.

## Behavioral invariants preserved by this fixture

- **TDQ → Direct invocation IMPLEMENTATION DECISION** (per AAP §0.4.1): The original COBOL
  `EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)` at `app/cbl/CORPT00C.cbl:L517-L523` is
  **NOT** replicated in Java; instead, the 17 JCL skeleton lines are passed to a direct-invocation
  port (e.g., `JclSkeletonSink`) that the composition root wires to the `CbTrn03C` use case
  (downstream Transaction Report writer). The Java fixture asserts the byte-identical sequence of
  17 skeleton lines with date substitution at `PARM-START-DATE-1` (within line 11),
  `PARM-END-DATE-1` (within line 12), `PARM-START-DATE-2` and `PARM-END-DATE-2` (within line 15 /
  `DATEPARM` block) per `JOB-DATA` at `app/cbl/CORPT00C.cbl:L81-L127`. Documented in
  `java/MIGRATION_NOTES.md`.
- **No file I/O on TRANSACT or any other VSAM dataset**: CORPT00C declares `WS-TRANSACT-FILE` at
  L40 and `COPY CVTRA05Y` at L146 but performs no `STARTBR` / `READ` / `READNEXT` / `READPREV` /
  `WRITE` / `REWRITE` / `DELETE`. Its only side effect is the TDQ `WRITEQ` at L517-L523, translated
  to direct invocation. Post-run state of all VSAM datasets is unchanged.
- **Three mutually-exclusive report modes** at `app/cbl/CORPT00C.cbl:L212-L443`: Monthly, Yearly,
  Custom; only one may be selected per submission per the outer `EVALUATE TRUE` order (Monthly >
  Yearly > Custom > `WHEN OTHER`). If multiple radio selectors are set, the `EVALUATE TRUE` matches
  the FIRST non-empty branch in source order — preserve EXACTLY this evaluation order per AAP
  §0.7.1.
- **Monthly mode date derivation** at L213-L238: `WS-START-DATE = YYYY-MM-01` (current month, day
  01); `WS-END-DATE` = last day of current month derived via
  `COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)`
  after rolling month forward. The Java translation MUST mirror this algorithm using
  `java.time.LocalDate.plusMonths(1).withDayOfMonth(1).minusDays(1)` or equivalent — same final
  result.
- **Yearly mode date derivation** at L239-L255: `WS-START-DATE = YYYY-01-01`;
  `WS-END-DATE = YYYY-12-31`. Java: `LocalDate.of(year, 1, 1)` and `LocalDate.of(year, 12, 31)`.
- **Custom mode `NUMVAL-C` normalization** at L305-L327: each of 6 date components is `COMPUTE`d
  through `FUNCTION NUMVAL-C` and then `MOVE`d back into the BMS input field. This handles
  leading-space / zero-padding from terminal input. The Java translation MUST mirror via explicit
  `String` trim + `parseInt`, then reformat to fixed width — `NUMVAL-C` accepts leading spaces,
  leading zeros, and unsigned numeric strings and returns the numeric value.
- **Custom mode validation order** at L329-L379: `NUMERIC` check + range check (`> '12'` for month,
  `> '31'` for day) — 6 separate `IF` checks emitting `'... Not a valid <Month/Day/Year>...'`
  messages. The Java translation MUST emit these messages in the same order they would be emitted
  by sequential `IF` evaluation.
- **`CSUTLDTC` external CALL** at L392-L394 and L412-L414: TWO calls (once per date) `USING
  CSUTLDTC-DATE` (10 bytes `YYYY-MM-DD`), `CSUTLDTC-DATE-FORMAT` (10 bytes literal `'YYYY-MM-DD'`),
  `CSUTLDTC-RESULT` (80 bytes containing `SEV-CD` / `FILLER` / `MSG-NUM` / `MSG`). The Java
  translation calls a constructor-injected `DateValidator` (translated from `app/cbl/CSUTLDTC.cbl`)
  that wraps `LocalDate.parse` with a strict resolver style per AAP §0.6.4.
- **`CSUTLDTC` result handling** at L396-L406 and L416-L426: `SEV-CD = '0000'` → `CONTINUE` (valid);
  else if `MSG-NUM NOT = '2513'` → emit error. The `'2513'` exception means `CSUTLDTC` reports a
  known soft-error code that the program treats as continue-anyway. Preserve this special-case
  exactly per AAP §0.7.1.
- **JCL skeleton is byte-exact** at L81-L125: the 17 lines in `JOB-DATA` MUST be reproduced
  byte-for-byte in the Java JCL-skeleton sink; any deviation in whitespace, quoting, or line breaks
  is a defect. The Java translation expresses the skeleton as a static byte-array constant or text
  block (text blocks permitted per AAP §0.7.3 finalized features), with placeholders at the exact
  byte offsets: `PARM-START-DATE-1` (bytes 19-28 of line 11), `PARM-END-DATE-1` (bytes 17-26 of
  line 12), `PARM-START-DATE-2` (bytes 1-10 of line 15), `PARM-END-DATE-2` (bytes 12-21 of line 15).
- **`CONFIRM` Y/N exact handling** at L478-L483: `WHEN CONFIRMI = 'Y' OR 'y'` → proceed;
  `WHEN CONFIRMI = 'N' OR 'n'` → `INITIALIZE-ALL-FIELDS` + redisplay. Both upper and lower case
  must be accepted exactly — no broader case-insensitive matching, no other characters accepted.
- **`CONFIRM` invalid value** at L484-L493: `WHEN OTHER` STRINGs the user-typed character into the
  message: `'"' + CONFIRMI + '" is not a valid value to confirm...'`. Java MUST mirror via a
  `String.format` / text block / concatenation that interpolates the user-typed character into a
  known stable pattern.
- **`CONFIRM`-empty triggers re-prompt** at L464-L474: `IF CONFIRMI = SPACES OR LOW-VALUES` →
  STRINGs `'Please confirm to print the ' + WS-REPORT-NAME + ' report...'` INTO `WS-MESSAGE`.
  CRITICAL: this happens AFTER the report-mode validation but BEFORE the JCL emission loop — empty
  `CONFIRM` is its own dedicated error path.
- **VERBATIM error messages with trailing `...` ASCII ellipses preserved EXACTLY** (per AAP §0.7.1;
  citations are to `app/cbl/CORPT00C.cbl` unless noted):
  - L193 (via COPY): `CCDA-MSG-INVALID-KEY` (`'Invalid key pressed. Please see below...         '`
    — 50 chars exact with trailing spaces — from `app/cpy/CSMSG01Y.cpy:L20-L21`).
  - L261-L262: `'Start Date - Month can NOT be empty...'`.
  - L268-L269: `'Start Date - Day can NOT be empty...'`.
  - L275-L276: `'Start Date - Year can NOT be empty...'`.
  - L282-L283: `'End Date - Month can NOT be empty...'`.
  - L289-L290: `'End Date - Day can NOT be empty...'`.
  - L296-L297: `'End Date - Year can NOT be empty...'`.
  - L331-L332: `'Start Date - Not a valid Month...'`.
  - L340-L341: `'Start Date - Not a valid Day...'`.
  - L348-L349: `'Start Date - Not a valid Year...'`.
  - L357-L358: `'End Date - Not a valid Month...'`.
  - L366-L367: `'End Date - Not a valid Day...'`.
  - L374-L375: `'End Date - Not a valid Year...'`.
  - L400-L401: `'Start Date - Not a valid date...'` (CSUTLDTC failure path with
    `MSG-NUM NOT = '2513'`).
  - L420-L421: `'End Date - Not a valid date...'` (CSUTLDTC failure path with
    `MSG-NUM NOT = '2513'`).
  - L438-L439: `'Select a report type to print report...'`.
  - L449-L450: `' report submitted for printing ...'` (STRINGed with `WS-REPORT-NAME` — note SPACE
    before `...`).
  - L466-L469: `'Please confirm to print the ' + WS-REPORT-NAME + ' report...'` (STRING
    construction).
  - L486-L489: `'"' + CONFIRMI + '" is not a valid value to confirm...'` (STRING construction).
  - L531-L532: `'Unable to Write TDQ (JOBS)...'`.
- **NO Unicode ellipsis** (U+2026) anywhere in the Java translation or fixtures — only 3 ASCII periods
  `...` per AAP §0.7.4 forbidden-content guidance.
- **No `PASSWD` / no PAN on the map**: CORPT00C does not display passwords or card numbers. There
  is no `SEC-USR-PWD` reference; there is no `TRAN-CARD-NUM` display field. This is a structural
  privacy property — the BMS map carries only date components and selector flags.
- **Deterministic Clock for `FUNCTION CURRENT-DATE`**: under capture and Java test runtime, the
  system clock is fixed via `ScopedValue<Clock>` per AAP §0.6.4 / §0.6.6 to make Monthly/Yearly
  date derivation deterministic. Without this, parity assertions cannot hold across calendar
  boundaries.
- **PF3 destination is `COMEN01C`, not `COSGN00C`** at L187-L189: PF3 returns to the Main Menu.
  `COSGN00C` is reached only via the `EIBCALEN = 0` first-time-entry path at L172-L174.
- **`WIRTE-JOBSUB-TDQ` paragraph-name typo preserved**: the COBOL paragraph at
  `app/cbl/CORPT00C.cbl:L515` is named `WIRTE-JOBSUB-TDQ` (transposed letters in `WIRTE`/`WRITE`);
  the Java translation cites this typo verbatim in any `@CobolProgram` traceability comment or
  Javadoc that references the source paragraph. Do NOT silently correct to `WRITE-JOBSUB-TDQ`.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CoRpt00C` MUST carry
  `@CobolProgram("CORPT00C")` Javadoc-style annotation citing the original PROGRAM-ID, source path
  `app/cbl/CORPT00C.cbl`, and the translation date.

## Source lineage

- `app/cbl/CORPT00C.cbl` (649 lines) — Transaction-Reports CICS COBOL program. `PROGRAM-ID
  CORPT00C` at L24; transaction `CR00` at L38; key paragraphs: `MAIN-PARA` (L163),
  `PROCESS-ENTER-KEY` (L208), `SUBMIT-JOB-TO-INTRDR` (L462), `WIRTE-JOBSUB-TDQ` (L515) [typo
  `WIRTE` preserved], `RETURN-TO-PREV-SCREEN` (L540), `SEND-TRNRPT-SCREEN` (L556), `RETURN-TO-CICS`
  (L585), `RECEIVE-TRNRPT-SCREEN` (L596), `POPULATE-HEADER-INFO` (L609), `INITIALIZE-ALL-FIELDS`
  (L633). JCL skeleton inline at L81-L127.
- `app/bms/CORPT00.bms` (231 lines) — BMS map definition. MAPSET `CORPT00` at L19, MAP `CORPT0A` at
  L26, `SIZE=(24,80)` at L28. Editable input fields: `MONTHLY (7,10) LENGTH=1`, `YEARLY (9,10)
  LENGTH=1`, `CUSTOM (11,10) LENGTH=1`, `SDTMM (13,29) LENGTH=2 NUM`, `SDTDD (13,34) LENGTH=2 NUM`,
  `SDTYYYY (13,39) LENGTH=4 NUM`, `EDTMM (14,29) LENGTH=2 NUM`, `EDTDD (14,34) LENGTH=2 NUM`,
  `EDTYYYY (14,39) LENGTH=4 NUM`, `CONFIRM (19,66) LENGTH=1`. Footer text `'ENTER=Continue
  F3=Back'` at L226 (2 named AID keys advertised).
- `app/cpy-bms/CORPT00.CPY` (224 lines) — Symbolic map copybook defining `01 CORPT0AI` (input
  record) at L17 and `01 CORPT0AO REDEFINES CORPT0AI` (output record) at L121; each visible BMS
  field expands to companion length `L`, flag `F`, attribute `A REDEFINES`, input `I`, output
  `O`/`C`/`P`/`H`/`V`.
- `app/cpy/COCOM01Y.cpy` (47 lines) — `01 CARDDEMO-COMMAREA`. Includes `CDEMO-GENERAL-INFO` with
  `CDEMO-USER-TYPE` at L26 (88-levels `CDEMO-USRTYP-ADMIN VALUE 'A'` at L27 and
  `CDEMO-USRTYP-USER VALUE 'U'` at L28) and `CDEMO-PGM-CONTEXT` at L29 (88-levels
  `CDEMO-PGM-ENTER VALUE 0` at L30, `CDEMO-PGM-REENTER VALUE 1` at L31).
- `app/cpy/CSMSG01Y.cpy` (24 lines) — `01 CCDA-COMMON-MESSAGES`. Contains `CCDA-MSG-THANK-YOU` at
  L18-L19 (UNUSED in CORPT00C) and `CCDA-MSG-INVALID-KEY` at L20-L21 (USED in `MAIN-PARA`
  `WHEN OTHER` at L193).
- `app/cpy/CSDAT01Y.cpy` — `WS-DATE-TIME` / `WS-CURDATE-DATA` fields used by `FUNCTION CURRENT-DATE`
  assignments (referenced at L215, L241, L611).
- `app/cpy/COTTL01Y.cpy` — Title constants (`CCDA-TITLE01`, `CCDA-TITLE02` referenced at L613-L614).
- `app/cpy/CVTRA05Y.cpy` — `01 TRAN-RECORD` 350-byte fixed-width layout. DECLARED at L146 but NOT
  actively used for VSAM I/O in CORPT00C; declared for compile-time symbol availability only.
- `app/cbl/CSUTLDTC.cbl` (157 lines) — External date-validation subroutine called twice in
  `PROCESS-ENTER-KEY` at L392-L394 and L412-L414. Translated to Java
  `com.blitzy.carddemo.application.util.DateValidator` per AAP §0.4.1.

## Authority references

- AAP §0.1.1 (refactoring objective — preserve byte-for-byte fidelity COBOL → Java 25).
- AAP §0.2.1 (in-scope: `golden/corpt00c/` directory tree as part of the
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference
  implementation).
- AAP §0.3.1 (harness directory layout: `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (CORPT00C → CoRpt00C; TDQ JOBS → direct invocation IMPLEMENTATION DECISION; `report`
  subpackage placement).
- AAP §0.6.4 (Date semantics — `java.time` only; `LocalDate` / `LocalDateTime`; deterministic
  `Clock` via `ScopedValue<Clock>`).
- AAP §0.6.6 (batch throughput / `ScopedValue` replaces `ThreadLocal`).
- AAP §0.6.11 (golden-record harness design; `@Disabled` until COBOL captures committed).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is verbatim error messages, JCL skeleton bytes,
  validation order, EVALUATE order).
- AAP §0.7.4 (forbidden features — no preview JEP 502 / 505 / 507 / 512 in production; no
  `default` branch; no `java.util.Date` / `Calendar`).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6).
- Sibling pattern reference: `golden/cosgn00c/input/README.md`, `golden/cotrn00c/input/README.md`,
  `golden/cousr00c/input/README.md` (canonical 9-phase pattern).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep` placeholders,
`input_scenario.txt`, `jcl_skeleton.txt`, or any other content. The `README.md` IS the directory's
marker. All fixture data files for the CORPT00C parity test live in the sibling `../expected/`
folder, because the consuming test class `CoRpt00CGoldenTest` resolves every fixture path through
the base class helper `resolveExpectedOutputPath("corpt00c", ...)`. Adding files here would create
duplicate, stale, or unreachable fixtures. If a future test scenario requires additional inputs,
add the new file to `../expected/` and extend the appropriate override in `CoRpt00CGoldenTest.java`
(`auxiliaryInputs()` or a new path-returning method) — not here.
