# COCRDUPC Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files. COCRDUPC is
the **card-update online CICS program** (transaction `CCUP`) translated to Java
`com.blitzy.carddemo.application.card.CoCrdUpC`. The deterministic test scenario file
(`input_scenario.txt`) and the auxiliary input fixture(s) consumed by the parity harness BOTH live
in the **sibling `../expected/` folder**, because the consuming Java test class
`CoCrdUpCGoldenTest` resolves all fixture paths through the base class helper
`resolveExpectedOutputPath("cocrdupc", ...)` — which routes to
`src/test/resources/golden/cocrdupc/expected/`. This conventional `input/` subdirectory (per AAP
§0.6.11 harness convention) is preserved only to make the absence of fixture data files **explicit
and discoverable**, mirroring the sibling precedents in `golden/csutldtc/`, `golden/cousr02c/`,
`golden/cousr03c/`, `golden/cotrn02c/`, and `golden/cosgn00c/`.

## Why this folder is documentation-only

- COCRDUPC is an **online CICS pseudo-conversational program** (transaction `CCUP`). Its
  user-facing input arrives via the BMS map `CCRDUPA` defined in `app/bms/COCRDUP.bms` — with 4
  visible editable fields (`CRDNAME`, `CRDSTCD`, `EXPMON`, `EXPYEAR`), 1 hidden `DRK` field
  (`EXPDAY`), and 2 search-key fields (`ACCTSID`, `CARDSID`).
- The source COBOL working-storage at `app/cbl/COCRDUPC.cbl:L220-L254` declares:
  `LIT-THISPGM PIC X(8) VALUE 'COCRDUPC'` (L220), `LIT-THISTRANID PIC X(4) VALUE 'CCUP'` (L222),
  `LIT-THISMAPSET PIC X(8) VALUE 'COCRDUP '` (L224, trailing space to fill 8 chars),
  `LIT-THISMAP PIC X(7) VALUE 'CCRDUPA'` (L226),
  `LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '` (L252, trailing space to fill 8 chars), and
  `LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '` (L254, declared but NOT referenced by
  this program — only the primary `CARDDAT` index is used).
- Inputs are NOT file-based fixtures sourced solely from `app/data/ASCII/*.txt`. The repository
  fixtures `app/data/ASCII/carddata.txt` and `app/data/ASCII/cardxref.txt` are referenced as
  **initial state** for the file-based adapter, but the scenario-driving BMS keystroke sequence is
  **test-owned scaffolding** authored alongside the captured expected outputs in `../expected/`.
- The consuming test class `CoCrdUpCGoldenTest` resolves all fixture paths through the base class
  helper `resolveExpectedOutputPath(programDir, fileName)`, which maps every path to
  `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely:
  - `inputFile()` returns `resolveExpectedOutputPath("cocrdupc", "input_scenario.txt")` →
    `../expected/input_scenario.txt`.
  - Auxiliary inputs reference `app/data/ASCII/carddata.txt` and `app/data/ASCII/cardxref.txt`
    via classpath relative path (NOT copied into this fixture tree per AAP §0.4.1).
- Therefore, NO fixture data files exist in this `input/` folder. The conventional `input/`
  directory is preserved only to maintain the per-program `input/`+`expected/` pairing established
  by AAP §0.6.11 and the listed sibling precedents.

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map input fields (4 editable + 1 hidden + 2 search-keys)

- `ACCTSID` (PIC X(11)) at (7, 45) — search key, `ATTRB=(FSET,IC,NORM,PROT)` (`app/bms/COCRDUP.bms:L84-L88`),
  `COLOR=DEFAULT`, `HILIGHT=UNDERLINE`. The `IC` (initial-cursor) attribute places the cursor here
  on first display. The map-time `PROT` attribute is overridden dynamically by 3300-SETUP-SCREEN-ATTRS
  to make the field unprotected on the data-entry pass.
- `CARDSID` (PIC X(16)) at (8, 45) — search key, `ATTRB=(FSET,NORM,UNPROT)` (`app/bms/COCRDUP.bms:L96-L100`),
  `COLOR=DEFAULT`, `HILIGHT=UNDERLINE`.
- `CRDNAME` (PIC X(50)) at (11, 25) — editable Embossed Name, `ATTRB=(UNPROT)`, `HILIGHT=UNDERLINE`
  (`app/bms/COCRDUP.bms:L107-L110`).
- `CRDSTCD` (PIC X(1)) at (13, 25) — editable Active Y/N, `ATTRB=(UNPROT)`, `HILIGHT=UNDERLINE`
  (`app/bms/COCRDUP.bms:L117-L120`).
- `EXPMON` (PIC X(2)) at (15, 25) — editable Expiry Month, `ATTRB=(UNPROT)`, `HILIGHT=UNDERLINE`,
  `JUSTIFY=(RIGHT)` (`app/bms/COCRDUP.bms:L127-L131`).
- `EXPYEAR` (PIC X(4)) at (15, 30) — editable Expiry Year, `ATTRB=(UNPROT)`, `HILIGHT=UNDERLINE`,
  `JUSTIFY=(RIGHT)` (`app/bms/COCRDUP.bms:L135-L139`).
- `EXPDAY` (PIC X(2)) at (15, 36) — **HIDDEN via `ATTRB=(DRK,FSET,PROT)` + `HILIGHT=OFF`**
  (`app/bms/COCRDUP.bms:L142-L146`); day-of-month preserved from CARDDAT round-trip
  (CARD-EXPIRAION-DATE positions 9:2); NOT operator-editable.

### CICS pseudo-conversational dispatch (4 AID keys + invalid-defaults-to-ENTER)

AID validity (`app/cbl/COCRDUPC.cbl:L367-L560`):

- `CCARD-AID-ENTER` — always valid.
- `CCARD-AID-PFK03` (F3 Exit) — always valid; XCTLs to `CDEMO-FROM-PROGRAM` (default `COMEN01C`).
- `CCARD-AID-PFK05` (F5 Save) — valid ONLY when `CCUP-CHANGES-OK-NOT-CONFIRMED` (L416).
- `CCARD-AID-PFK12` (F12 Cancel) — valid ONLY when NOT `CCUP-DETAILS-NOT-FETCHED` (L418).
- All other AIDs are re-mapped to ENTER (SET PFK-INVALID TO TRUE).

The 5-branch `EVALUATE TRUE` dispatch in 0000-MAIN at `app/cbl/COCRDUPC.cbl:L429-L559` (condensed):

```cobol
EVALUATE TRUE
    WHEN CCARD-AID-PFK03
      OR (CCUP-CHANGES-OKAYED-AND-DONE
          AND CDEMO-LAST-MAPSET = LIT-CCLISTMAPSET)
      OR (CCUP-CHANGES-FAILED
          AND CDEMO-LAST-MAPSET = LIT-CCLISTMAPSET)
        EXEC CICS SYNCPOINT END-EXEC
        EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
                       COMMAREA(CARDDEMO-COMMAREA) END-EXEC
    WHEN (CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM)
      OR (CCARD-AID-PFK12 AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM)
        PERFORM 9000-READ-DATA
        SET CCUP-SHOW-DETAILS TO TRUE
        PERFORM 3000-SEND-MAP
    WHEN CCUP-DETAILS-NOT-FETCHED AND CDEMO-PGM-ENTER
      OR CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER
        INITIALIZE WS-THIS-PROGCOMMAREA
        SET CCUP-DETAILS-NOT-FETCHED TO TRUE
        PERFORM 3000-SEND-MAP
    WHEN CCUP-CHANGES-OKAYED-AND-DONE
    WHEN CCUP-CHANGES-FAILED
        INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE
                   CDEMO-ACCT-ID CDEMO-CARD-NUM
        SET CDEMO-PGM-ENTER TO TRUE
        SET CCUP-DETAILS-NOT-FETCHED TO TRUE
        PERFORM 3000-SEND-MAP
    WHEN OTHER
        PERFORM 1000-PROCESS-INPUTS
        PERFORM 2000-DECIDE-ACTION
        PERFORM 3000-SEND-MAP
END-EVALUATE
```

### Two-phase READ + REWRITE flow

1. Pass 1 (ENTER with `ACCTSID` + `CARDSID`) → 9000-READ-DATA (`app/cbl/COCRDUPC.cbl:L1343-L1372`)
   → 9100-GETCARD-BYACCTCARD (`app/cbl/COCRDUPC.cbl:L1376-L1415`) → `EXEC CICS READ FILE(CARDDAT)`
   keyed on CARDSID → populate `CCUP-OLD-*` fields (CARDNAME uppercased via `INSPECT CONVERTING`)
   → display.
2. Pass 2 (ENTER again with edits) → 1200-EDIT-MAP-INPUTS (`app/cbl/COCRDUPC.cbl:L641-L717`) →
   1230-1260 field validations → SET `CCUP-CHANGES-OK-NOT-CONFIRMED`.
3. Pass 3 (PF5 Save) → 2000-DECIDE-ACTION (`app/cbl/COCRDUPC.cbl:L948-L1029`) → 9200-WRITE-PROCESSING
   (`app/cbl/COCRDUPC.cbl:L1420-L1494`) → `EXEC CICS READ ... UPDATE` (lock) →
   9300-CHECK-CHANGE-IN-REC (optimistic concurrency) → `EXEC CICS REWRITE` → `'C'` state.

### Conceptual fixture data table

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|--------------------|-----------------|------------------|---------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script: BMS keystroke sequences (`ACCTSID` + `CARDSID` on Pass 1; `CRDNAME`/`CRDSTCD`/`EXPMON`/`EXPYEAR` edits on Pass 2), AID key sequence, and confirmation responses |
| `carddata.txt` initial state | `app/data/ASCII/carddata.txt` (via classpath, NOT COPIED) | Repository fixture | 50-record initial state for CARDDAT (150-byte records) |
| `cardxref.txt` initial state | `app/data/ASCII/cardxref.txt` (via classpath, NOT COPIED) | Repository fixture | 50-record related dataset (36-byte rows; not directly used by COCRDUPC) |

Test scenarios that `input_scenario.txt` MUST cover (per the test class's 6-point `@Disabled`
verification list):

- (a) **Successful update (REWRITE)**: `ACCTSID` + `CARDSID` populated → ENTER → record displayed
  with editable fields populated → modify `CRDNAME` and/or `CRDSTCD` and/or `EXPMON` and/or
  `EXPYEAR` → ENTER → `CCUP-CHANGES-OK-NOT-CONFIRMED` + `'Changes validated.Press F5 to save'` →
  PF5 → REWRITE succeeds → `'Changes committed to database'` (state `'C'`).
- (b) **PF12 cancel (no REWRITE)**: record displayed → PF12 → state preserved without REWRITE →
  `carddata.txt` byte-identical to initial state.
- (c) **Invalid input (no REWRITE)**: invalid `CRDSTCD` (e.g., `'X'` not Y/N), or invalid `EXPMON`
  (e.g., 13), or invalid `EXPYEAR` (e.g., 1900), or non-alphabetic `CRDNAME` → ENTER → SET
  `CCUP-CHANGES-NOT-OK` → corresponding verbatim error message → `carddata.txt` byte-identical to
  initial state.
- (d) **Card not found**: `CARDSID` with no matching record → READ NOTFND →
  `'Did not find cards for this search condition'` (state preserved as `DETAILS-NOT-FETCHED`).
- (e) **No-change detected**: open record for edit, do not modify any field, press ENTER →
  uppercase-name comparison detects `NO-CHANGES-DETECTED` → state stays `SHOW-DETAILS` with message
  `'No change detected with respect to values fetched.'`.
- (f) **PAN masking in stdout**: ANY captured DISPLAY emission that includes `CARD-NUM` MUST mask
  all but last 4 digits per AAP §0.7.2.

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.CoCrdUpCGoldenTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoCrdUpCGoldenTest.java`. It
  extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`.
- The class under test is `com.blitzy.carddemo.application.card.CoCrdUpC` (the Java translation of
  `app/cbl/COCRDUPC.cbl`).
- Override summary (5 method overrides routing to `../expected/` or `app/data/ASCII/` via classpath):
  - `programClass()` → `com.blitzy.carddemo.application.card.CoCrdUpC.class`.
  - `inputFile()` → `resolveExpectedOutputPath("cocrdupc", "input_scenario.txt")` —
    routes to `../expected/input_scenario.txt`.
  - `expectedOutputFile()` → `resolveExpectedOutputPath("cocrdupc", "stdout.txt")` —
    routes to `../expected/stdout.txt`.
  - `auxiliaryInputs()` → references `app/data/ASCII/carddata.txt` AND
    `app/data/ASCII/cardxref.txt` via classpath relative path (NOT copied).
  - `expectedOutputs()` → three `ExpectedOutput` entries: `stdout.txt`, `bms_output.txt`,
    `carddata.txt` (post-REWRITE state, SAME byte length as initial-state `carddata.txt`).
- **All five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper or
  to `app/data/ASCII/` via classpath. None routes to this `input/` folder.** This is the
  architectural reason the folder is empty.
- The test is `@Disabled` per AAP §0.6.11 ("Initial test scaffolding may use placeholder expected
  files marked `@Disabled` until COBOL captures are available"). The 6-point verification list:
  1. Successful update (REWRITE) preserves byte-for-byte parity on post-REWRITE `carddata.txt`.
  2. PF12 cancel produces no REWRITE.
  3. Invalid input produces no REWRITE with verbatim error message.
  4. Card-not-found produces verbatim `'Did not find cards for this search condition'`.
  5. No-change-detected produces verbatim `'No change detected with respect to values fetched.'`.
  6. PAN never appears unmasked in `stdout.txt` per AAP §0.7.2.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COCRDUPC) for the
CICS COBOL build/run path used to capture expected outputs. The capture procedure exists because
the user prompt left this as a `[TODO]` marker (AAP §0.7.5: "To regenerate golden-record fixtures
from COBOL [TODO — document the COBOL build/run path here]"). Until the capture is performed, the
`../expected/` directory holds placeholder/scaffolding files and the test is `@Disabled`.

## Behavioral invariants preserved by this fixture

- **Multi-pass READ + REWRITE flow with optimistic concurrency check**: `app/cbl/COCRDUPC.cbl:L1343-L1494`
  covers 9000-READ-DATA, 9100-GETCARD-BYACCTCARD, 9200-WRITE-PROCESSING, and 9300-CHECK-CHANGE-IN-REC.
- **In-place REWRITE semantics**: the 150-byte CARD-RECORD slot is preserved by VSAM REWRITE; total
  `carddata.txt` byte length is unchanged. The Java translation's file-based adapter MUST replicate
  this — post-REWRITE `carddata.txt` MUST be the SAME byte length as initial-state `carddata.txt`,
  with only the content of the updated record(s) changed.
- **9300-CHECK-CHANGE-IN-REC optimistic concurrency**: compare current record (CVV, NAME, EXPYEAR
  positions 1:4, EXPMON positions 6:2, EXPDAY positions 9:2, STATUS) vs. `CCUP-OLD-*` snapshot
  before REWRITE; if mismatch → SET `DATA-WAS-CHANGED-BEFORE-UPDATE` → no REWRITE; refresh `OLD-*`
  fields from current record and re-display.
- **Uppercase normalization of `CARD-EMBOSSED-NAME` during compare**:
  `INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER` (in 9300-CHECK-CHANGE-IN-REC).
- **STRING construction of `CARD-EXPIRAION-DATE`**: 10-byte output via
  `STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY → CARD-UPDATE-EXPIRAION-DATE`
  produces the `YYYY-MM-DD` form occupying bytes 81-90 of the rewritten record.
- **EXPDAY hidden field preservation**: `ATTRB=(DRK,FSET,PROT)` on the BMS map
  (`app/bms/COCRDUP.bms:L142-L146`); day-of-month carried from CARDDAT round-trip; never
  operator-editable.
- **4 BMS field validations** (`app/cbl/COCRDUPC.cbl:L806-L945`):
  - 1230-EDIT-NAME — alphabetic-only via
    `INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO` +
    `FUNCTION LENGTH(FUNCTION TRIM(...)) = 0` check; ensures only alphabets + space allowed.
  - 1240-EDIT-CARDSTATUS — must be `Y` or `N` (`FLG-YES-NO-CHECK`).
  - 1250-EDIT-EXPIRY-MON — must be 1-12 numeric (`CARD-MONTH-CHECK` at L92-95).
  - 1260-EDIT-EXPIRY-YEAR — must be 1950-2099 numeric (`CARD-YEAR-CHECK` at L96-99).
- **7-state `CCUP-CHANGE-ACTION` machine** (`app/cbl/COCRDUPC.cbl:L278-L290`):
  `CCUP-DETAILS-NOT-FETCHED` (`LOW-VALUES`/SPACES) → `CCUP-SHOW-DETAILS` (`'S'`) →
  `CCUP-CHANGES-NOT-OK` (`'E'`) or `CCUP-CHANGES-OK-NOT-CONFIRMED` (`'N'`) →
  `CCUP-CHANGES-OKAYED-AND-DONE` (`'C'`) or `CCUP-CHANGES-OKAYED-LOCK-ERROR` (`'L'`) or
  `CCUP-CHANGES-OKAYED-BUT-FAILED` (`'F'`). Aggregate `CCUP-CHANGES-MADE` covers `'E','N','C','L','F'`;
  aggregate `CCUP-CHANGES-FAILED` covers `'L','F'`.
- **PAN masking in logs** (AAP §0.7.2): all but last 4 digits of `CARD-NUM` MUST be masked in any
  `stdout.txt` output.
- **All 29 verbatim COBOL messages preserved** byte-for-byte (3 ASCII periods, NOT Unicode):
  - `'Details of selected card shown above'` (L161).
  - `'Please enter Account and Card Number'` (L163).
  - `'Update card details presented above.'` (L165).
  - `'Changes validated.Press F5 to save'` (L167; NO space after period).
  - `'Changes committed to database'` (L169).
  - `'Changes unsuccessful. Please try again'` (L171).
  - `'PF03 pressed.Exiting              '` (L176; 14 trailing spaces preserved).
  - `'Account number not provided'` (L178).
  - `'Card number not provided'` (L180).
  - `'Card name not provided'` (L182).
  - `'Card name can only contain alphabets and spaces'` (L184).
  - `'No input received'` (L186).
  - `'No change detected with respect to values fetched.'` (L188).
  - `'Account number must be a non zero 11 digit number'` (L190 and L192 duplicate).
  - `'Card number if supplied must be a 16 digit number'` (L194).
  - `'Card Active Status must be Y or N'` (L196).
  - `'Card expiry month must be between 1 and 12'` (L198).
  - `'Invalid card expiry year'` (L200).
  - `'Did not find this account in cards database'` (L202).
  - `'Did not find cards for this search condition'` (L204).
  - `'Could not lock record for update'` (L206).
  - `'Record changed by some one else. Please review'` (L208).
  - `'Update of record failed'` (L210).
  - `'Error reading Card Data File'` (L212).
  - `'Looks Good.... so far'` (L214; FOUR periods plus space).
  - `'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'` (L745).
  - `'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'` (L789).
  - `'UNEXPECTED DATA SCENARIO'` (L1023; ABEND case).
  - `'UNEXPECTED ABEND OCCURRED.'` (L1534; ABEND-ROUTINE default).
- **`@CobolProgram("COCRDUPC")` traceability annotation** per AAP §0.7.1: the Java class
  `CoCrdUpC` MUST carry the annotation citing the original PROGRAM-ID, source path
  `app/cbl/COCRDUPC.cbl`, and the translation date.

## Source lineage

- `app/cbl/COCRDUPC.cbl` — Card Update CICS COBOL program (1,560 lines). PROGRAM-ID `COCRDUPC`;
  transaction `CCUP`; key paragraphs: 0000-MAIN (L367), 1000-PROCESS-INPUTS (L564),
  1100-RECEIVE-MAP (L578), 1200-EDIT-MAP-INPUTS (L641), 1210-EDIT-ACCOUNT (L721), 1220-EDIT-CARD
  (L762), 1230-EDIT-NAME (L806), 1240-EDIT-CARDSTATUS (L845), 1250-EDIT-EXPIRY-MON (L877),
  1260-EDIT-EXPIRY-YEAR (L913), 2000-DECIDE-ACTION (L948), 3000-SEND-MAP (L1035),
  3100-SCREEN-INIT (L1052), 3200-SETUP-SCREEN-VARS (L1082), 3250-SETUP-INFOMSG (L1138),
  3300-SETUP-SCREEN-ATTRS (L1168), 3400-SEND-SCREEN (L1324), 9000-READ-DATA (L1343),
  9100-GETCARD-BYACCTCARD (L1376), 9200-WRITE-PROCESSING (L1420), 9300-CHECK-CHANGE-IN-REC (L1498),
  ABEND-ROUTINE (L1531).
- `app/bms/COCRDUP.bms` — BMS map definition (172 lines). MAPSET `COCRDUP`, MAP `CCRDUPA`,
  `SIZE=(24,80)`, `CTRL=FREEKB`, `DSATTS=(COLOR,HILIGHT,PS,VALIDN)`. Editable `CRDNAME`/`CRDSTCD`/
  `EXPMON`/`EXPYEAR` plus hidden `EXPDAY` (`DRK,FSET,PROT`) and search keys `ACCTSID`/`CARDSID`.
- `app/cpy-bms/COCRDUP.CPY` — Symbolic map copybook (224 lines). Defines `01 CCRDUPAI` input
  record and `01 CCRDUPAO REDEFINES CCRDUPAI` output record across 17 field groups (TRNNAME,
  TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME, ACCTSID, CARDSID, CRDNAME, CRDSTCD, EXPMON, EXPYEAR,
  EXPDAY, INFOMSG, ERRMSG, FKEYS, FKEYSC).
- `app/cpy/CVACT02Y.cpy` — `01 CARD-RECORD` 150-byte fixed-width layout:
  `CARD-NUM(16)` + `CARD-ACCT-ID(11)` + `CARD-CVV-CD(3)` + `CARD-EMBOSSED-NAME(50)` +
  `CARD-EXPIRAION-DATE(10)` + `CARD-ACTIVE-STATUS(1)` + `FILLER(59)` = 150 bytes.
- `app/data/ASCII/carddata.txt` — 50-record CARDDAT initial-state fixture (150-byte records;
  REFERENCE ONLY; NOT COPIED into this fixture folder per AAP §0.4.1).
- `app/data/ASCII/cardxref.txt` — 50-record CARDXREF related-dataset fixture (36-byte rows;
  REFERENCE ONLY).

These COBOL/BMS/copybook source files remain UNCHANGED per AAP §0.1.1, AAP §0.2.2, and AAP §0.7.1.

## Authority references

- AAP §0.2.1 (in-scope: `golden/cocrdupc/` directory tree as part of the
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (COCRDUPC → `CoCrdUpC` card-update online program; one class per COBOL PROGRAM-ID;
  ASCII fixtures read via classpath, NOT COPIED).
- AAP §0.6.4 (`java.time` mandate for all date/time values).
- AAP §0.6.5 (`java.nio.file` mandate for all file I/O).
- AAP §0.6.6 (`ScopedValue` for cross-method batch-run context propagation).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern
  until COBOL captures are committed).
- AAP §0.6.12 (architectural override: no enterprise framework container; plain Java with
  constructor injection and file-based adapters).
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` UNCHANGED; reserved as the reference
  implementation and as the source for golden-record test fixtures).
- AAP §0.7.1 (Minimal Change Clause; preserve the verbatim 29 COBOL messages including period
  and comma counts, trailing spaces, and ASCII punctuation).
- AAP §0.7.2 (PAN masking; no card PAN logged in full — mask all but last 4 digits in logs and
  error messages).
- AAP §0.7.4 (no JEP preview features in production code).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep`
placeholders, `input_scenario.txt`, or any other content. The `README.md` IS the directory's
marker.

All fixture data files for the COCRDUPC parity test live in the sibling `../expected/` folder,
because the consuming test class `CoCrdUpCGoldenTest` resolves every fixture path through the
base class helper `resolveExpectedOutputPath("cocrdupc", ...)`. Adding files here would create
duplicate, stale, or unreachable fixtures.

If a future test scenario requires additional inputs, add the new file to `../expected/` and
extend the appropriate override in `CoCrdUpCGoldenTest.java` (`auxiliaryInputs()` or a new
path-returning method) — not here.
