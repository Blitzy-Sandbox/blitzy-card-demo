# COCRDSLC (Card View, CCDL) — Golden-Record `input/` Folder (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files and this
`README.md` is the only file present. COCRDSLC is the **card-view online CICS program** (transaction
`CCDL`, "View Credit Card Detail") translated to Java `com.blitzy.carddemo.application.card.CoCrdSlC`.
COCRDSLC performs a single READ-only lookup against the CARDDAT VSAM KSDS keyed on the 16-byte card
number (`WS-CARD-RID-CARDNUM`); the program issues `EXEC CICS READ` WITHOUT an `UPDATE` clause
anywhere in its 887 lines — confirmed at `app/cbl/COCRDSLC.cbl:L742-L750`. Reentry from COCRDLIC
(Card List, `CCLI`) is supported via the pre-validated path at `app/cbl/COCRDSLC.cbl:L339-L348` when
`CDEMO-FROM-PROGRAM = LIT-CCLISTPGM`. Primary fixtures (`carddata.txt`, `cardxref.txt`) live in
`app/data/ASCII/` and are read via classpath (per AAP §0.4.1 "REFERENCE only, NOT COPIED"); the
test-owned CICS driver script (`input_scenario.txt`) lives in `../expected/` per the canonical
sibling convention established by `golden/cocrdupc/input/README.md` and `golden/cotrn01c/input/README.md`.

## Why this folder is documentation-only

The source COBOL working-storage at `app/cbl/COCRDSLC.cbl:L163-L190` declares
`LIT-THISPGM PIC X(8) VALUE 'COCRDSLC'` (L163-L164), `LIT-THISTRANID PIC X(4) VALUE 'CCDL'`
(L165-L166), `LIT-THISMAPSET PIC X(8) VALUE 'COCRDSL '` (L167-L168, trailing space),
`LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'` (L171-L172) for the card-list calling program,
`LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '` (L187-L188, trailing space — primary file), and
`LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '` (L189-L190, declared but NOT referenced by
the active 9000-READ-DATA flow — see Phase 3.3 for the dead-code analysis).

The user-facing input arrives via the BMS map `CCRDSLA` defined in `app/bms/COCRDSL.bms` with
**only TWO editable input fields** — ACCTSID and CARDSID — plus AID-key dispatch (ENTER, PF03 valid
at `app/cbl/COCRDSLC.cbl:L292-L295`; all other AIDs re-mapped to ENTER at
`app/cbl/COCRDSLC.cbl:L297-L299`). The consuming Java test class
`com.blitzy.carddemo.tests.golden.CoCrdSlCGoldenTest` extends
`com.blitzy.carddemo.tests.golden.GoldenRecordTest` and routes ALL fixture paths via
`resolveExpectedOutputPath("cocrdslc", filename)` to `src/test/resources/golden/cocrdslc/expected/`.
Therefore NO fixture data files exist in this `input/` folder.

Keeping an empty `input/` folder with only this README preserves the established
`<program>/input/` + `<program>/expected/` symmetry from AAP §0.3.1 across all 28+ program test
fixtures, while making the absence of data files self-documenting. Each conceptual input fixture
lives at its actual home:

- `app/data/ASCII/carddata.txt` — Primary CARDDAT initial-state fixture (REFERENCE; read via classpath; NOT COPIED).
- `app/data/ASCII/cardxref.txt` — Secondary CARDXREF initial-state fixture (REFERENCE; read via classpath; NOT COPIED).
- `../expected/README.md` — Authoritative fixture contract for the COCRDSLC golden-record test.
- `../expected/input_scenario.txt` — CICS pseudo-conversation driver script (TEST-OWNED).
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER).
- `../expected/bms_output.txt` — Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data lives elsewhere)

### BMS map inputs: ACCTSID + CARDSID (2 editable fields)

The CCRDSLA BMS map (defined in `app/bms/COCRDSL.bms` and symbolic copybook
`app/cpy-bms/COCRDSL.CPY`) has **exactly TWO editable input fields**:

- `ACCTSIDI` (PIC X(11)) — Account Number filter, at screen position (7, 45),
  ATTRB=(FSET,IC,NORM,UNPROT) per `app/bms/COCRDSL.bms:L84-L88`. The `IC` (initial-cursor) attribute
  places the cursor here on first display.
- `CARDSIDI` (PIC X(16)) — Card Number filter, at screen position (8, 45),
  ATTRB=(FSET,NORM,UNPROT) per `app/bms/COCRDSL.bms:L96-L100`.

Plus AID-key dispatch (ENTER, PF03 valid per `app/cbl/COCRDSLC.cbl:L292-L295`; all other AIDs are
re-mapped to ENTER per `app/cbl/COCRDSLC.cbl:L297-L299` via the CSSTRPFY copy at L855).

All other on-screen data fields (CRDNAME, CRDSTCD, EXPMON, EXPYEAR, INFOMSG) are PROT or ASKIP —
they are **output-only display fields** populated from CARD-RECORD after a successful READ. This
confirms COCRDSLC is a strict **VIEW** program, distinct from COCRDUPC (Card Update) where
CRDNAME/CRDSTCD/EXPMON/EXPYEAR are UNPROT.

### CICS dispatch context (commarea overlay; reentry detection)

The reentry detection at `app/cbl/COCRDSLC.cbl:L268-L279` reads the inbound commarea overlay:

```cobol
IF EIBCALEN IS EQUAL TO 0
    OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM
    AND NOT CDEMO-PGM-REENTER)
   INITIALIZE CARDDEMO-COMMAREA
              WS-THIS-PROGCOMMAREA
ELSE
   MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA) TO
                     CARDDEMO-COMMAREA
   MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
                    LENGTH OF WS-THIS-PROGCOMMAREA ) TO
                     WS-THIS-PROGCOMMAREA
END-IF
```

COCRDSLC reads `DFHCOMMAREA(1:LENGTH OF CARDDEMO-COMMAREA)` plus the `WS-THIS-PROGCOMMAREA` overlay
(containing `CA-FROM-PROGRAM PIC X(08)` and `CA-FROM-TRANID PIC X(04)` per
`app/cbl/COCRDSLC.cbl:L200-L203`). The reentry detection distinguishes three cases:

- `EIBCALEN = 0` (first-time, no commarea) → INITIALIZE both areas.
- `CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER` → also initialize (fresh entry from main menu COMEN01C).
- Else → preserve existing commarea (re-entry from same transaction or from COCRDLIC).

The 4-branch main `EVALUATE TRUE` at `app/cbl/COCRDSLC.cbl:L304-L381` then dispatches:

1. WHEN CCARD-AID-PFK03 → EXIT path: XCTL to CDEMO-FROM-PROGRAM (or LIT-MENUPGM default), MOVE LIT-THISMAPSET/MAP TO CDEMO-LAST-MAPSET/MAP.
2. WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM ('COCRDLIC') → pre-loaded path at L339-L348: SET INPUT-OK, MOVE CDEMO-ACCT-ID + CDEMO-CARD-NUM, PERFORM 9000-READ-DATA + 1000-SEND-MAP, GO TO COMMON-RETURN.
3. WHEN CDEMO-PGM-ENTER → fresh entry at L349-L356: PERFORM 1000-SEND-MAP (display empty search form), GO TO COMMON-RETURN.
4. WHEN CDEMO-PGM-REENTER → user submitted filters at L357-L371: PERFORM 2000-PROCESS-INPUTS; if INPUT-ERROR display error, else PERFORM 9000-READ-DATA + 1000-SEND-MAP.
5. WHEN OTHER → ABEND-ROUTINE with `'UNEXPECTED DATA SCENARIO'` per `app/cbl/COCRDSLC.cbl:L377`.

### READ flow: 9100-GETCARD-BYACCTCARD (active) vs 9150-GETCARD-BYACCT (DEAD CODE)

The active READ at `app/cbl/COCRDSLC.cbl:L740-L750`:

```cobol
MOVE CC-CARD-NUM       TO WS-CARD-RID-CARDNUM

EXEC CICS READ
     FILE      (LIT-CARDFILENAME)
     RIDFLD    (WS-CARD-RID-CARDNUM)
     KEYLENGTH (LENGTH OF WS-CARD-RID-CARDNUM)
     INTO      (CARD-RECORD)
     LENGTH    (LENGTH OF CARD-RECORD)
     RESP      (WS-RESP-CD)
     RESP2     (WS-REAS-CD)
END-EXEC
```

Key observations:

- The active READ at `app/cbl/COCRDSLC.cbl:L742-L750` has **NO `UPDATE` clause** — COCRDSLC is strictly READ-only and NEVER issues `EXEC CICS REWRITE`. The post-run CARDDAT file is byte-identical to the input.
- The READ key is the **16-byte card number ONLY** (account ID is validated for non-blank/numeric by 2210-EDIT-ACCOUNT but is NEVER used as part of the VSAM key).
- 9000-READ-DATA at L726-L730 ONLY performs 9100-GETCARD-BYACCTCARD (NEVER 9150).
- 9150-GETCARD-BYACCT at `app/cbl/COCRDSLC.cbl:L779-L812` is **DEAD CODE** — the alternate-index path that would use CARDAIX (`LIT-CARDFILENAME-ACCT-PATH = 'CARDAIX '`) is never invoked. Its references to `DID-NOT-FIND-ACCT-IN-CARDXREF` (L799) and the `XREF-READ-ERROR` 88-condition (L155-L156) make those 88-levels effectively unreachable through the 9000 entry. Per AAP §0.7.1 (Minimal Change Clause), the Java translation MUST preserve 9150 verbatim and flag it as DEAD CODE in `java/MIGRATION_NOTES.md`.

The response handling at `app/cbl/COCRDSLC.cbl:L752-L772`:

- WHEN DFHRESP(NORMAL) → SET FOUND-CARDS-FOR-ACCOUNT (emits `'   Displaying requested details'`).
- WHEN DFHRESP(NOTFND) → SET INPUT-ERROR + FLG-ACCTFILTER-NOT-OK + FLG-CARDFILTER-NOT-OK + DID-NOT-FIND-ACCTCARD-COMBO (emits `'Did not find cards for this search condition'`).
- WHEN OTHER → SET INPUT-ERROR + builds WS-FILE-ERROR-MESSAGE template (RESP/REAS error report).

### Fixture table (data lives in app/data/ASCII/ or ../expected/)

| Fixture File | Path | Layout Copybook | Record Length | Records | Mode |
|---|---|---|---|---|---|
| carddata.txt | `app/data/ASCII/carddata.txt` | `app/cpy/CVACT02Y.cpy` (CARD-RECORD) | 150 bytes | 50 | REFERENCE (classpath; NOT COPIED) |
| cardxref.txt | `app/data/ASCII/cardxref.txt` | `app/cpy/CVACT03Y.cpy` (CARD-XREF-RECORD) | 50 bytes | 50 | REFERENCE (classpath; NOT COPIED) |
| input_scenario.txt | `../expected/input_scenario.txt` | (test-owned) | n/a | n/a | TEST-OWNED CICS driver script |

The post-run `carddata.txt` is byte-identical to the input (READ-only semantics), so no
`carddata_after.txt` fixture is required in `../expected/`.

### Scenarios driven by ../expected/input_scenario.txt

The TEST-OWNED `../expected/input_scenario.txt` drives 12 scenarios (full details in
`../expected/README.md` Phase 11):

1. **Fresh entry, no input** → display empty search form (branch 3 of main EVALUATE).
2. **ENTER with only ACCTSID populated** → 2210-EDIT-ACCOUNT validates 11-digit; CARDSID empty → `'Card number not provided'`.
3. **ENTER with only CARDSID populated** → 2220-EDIT-CARD validates 16-digit; ACCTSID empty → `'Account number not provided'`.
4. **ENTER with both empty** → `'No input received'`.
5. **ENTER with ACCTSID non-numeric** → `'Account number must be a non zero 11 digit number'`.
6. **ENTER with ACCTSID = "00000000000"** → `'Account number must be a non zero 11 digit number'`.
7. **ENTER with CARDSID non-numeric or non-16-digit** → `'Card number if supplied must be a 16 digit number'`.
8. **ENTER with valid ACCTSID + valid CARDSID, READ NORMAL** → display CARD-RECORD fields + `'   Displaying requested details'`.
9. **ENTER with valid filters but no matching record** → READ NOTFND → `'Did not find cards for this search condition'`.
10. **Reentry from COCRDLIC (CDEMO-FROM-PROGRAM = 'COCRDLIC')** → pre-validated path: PERFORM 9000-READ-DATA + 1000-SEND-MAP without re-running EDITs.
11. **PF03** → XCTL to CDEMO-FROM-PROGRAM (or LIT-MENUPGM default) with `'PF03 pressed.Exiting              '` (14 trailing spaces).
12. **Invalid AID (e.g., PF01)** → re-mapped to ENTER per `app/cbl/COCRDSLC.cbl:L297-L299`; behaves as scenario 4 if no inputs provided.

## Cross-reference to the Java test class

The Java test class is `com.blitzy.carddemo.tests.golden.CoCrdSlCGoldenTest` at
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoCrdSlCGoldenTest.java`. It
extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The class
under test is `com.blitzy.carddemo.application.card.CoCrdSlC` (note the `card` subpackage per AAP
§0.4.1). Override summary (5 method overrides):

- `programClass()` returns `com.blitzy.carddemo.application.card.CoCrdSlC.class`.
- `inputFile()` returns `resolveExpectedOutputPath("cocrdslc", "input_scenario.txt")` → routes to `../expected/input_scenario.txt`.
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cocrdslc", "stdout.txt")` → routes to `../expected/stdout.txt`.
- `auxiliaryInputs()` references `app/data/ASCII/carddata.txt` AND `app/data/ASCII/cardxref.txt` via classpath relative path (NOT copied per AAP §0.4.1).
- `expectedOutputs()` returns `ExpectedOutput` entries for `stdout.txt` and `bms_output.txt` (both under `../expected/`). **NO `carddata_after.txt` entry** — because COCRDSLC is READ-only (no REWRITE), the post-run CARDDAT state is byte-identical to the input.

All five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper or to
`app/data/ASCII/` via classpath. **None routes to this `input/` folder.** This is the architectural
reason the folder is empty. The test MUST be annotated
`@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until
COBOL captures are committed.

The harness verifies six observable properties when enabled:

1. Fresh entry displays empty search form correctly.
2. ACCTSID/CARDSID validation produces correct verbatim error messages.
3. READ NORMAL produces `'   Displaying requested details'` plus populated CARD fields.
4. READ NOTFND produces `'Did not find cards for this search condition'`.
5. PF03 produces `'PF03 pressed.Exiting              '` (14 trailing spaces preserved).
6. PAN never appears unmasked in `stdout.txt` or `bms_output.txt` per AAP §0.7.2.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COCRDSLC) for the
CICS COBOL build/run path used to capture expected outputs. Per AAP §0.7.5 (the user `[TODO]`
marker resolution), the procedure is documented in `MIGRATION_NOTES.md`. Until the capture is
performed and committed, `../expected/stdout.txt` and `../expected/bms_output.txt` hold placeholder
content and `CoCrdSlCGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **READ-only file semantics**: COCRDSLC does not call `EXEC CICS REWRITE` anywhere in its 887 lines — verified at `app/cbl/COCRDSLC.cbl:L742-L750` where the READ has no `UPDATE` clause. Post-run `carddata.txt` MUST be byte-identical to pre-run state.
- **PAN masking per AAP §0.7.2**: any 16-digit CARDSIDO or CARD-NUM values in captured `bms_output.txt` and `stdout.txt` MUST be masked (last 4 digits visible). The **input** card numbers in `../expected/input_scenario.txt` (TEST-OWNED) MAY appear in full because the driver script is not subject to PCI logging rules. Java logs MUST NEVER carry an unmasked PAN.
- **Verbatim message preservation per AAP §0.7.1**: 13 distinct WS-INFO-MSG / WS-RETURN-MSG 88-level conditions plus 4 inline messages must be reproduced byte-for-byte. The EXACT 3 leading spaces on `'   Displaying requested details'` (L130), the EXACT 14 trailing spaces on `'PF03 pressed.Exiting              '` (L137), and the EXACT FOUR-period-plus-space ending on `'Looks Good.... so far'` (L158 — DEAD CODE) MUST be preserved.
- **Dead code preservation per AAP §0.7.1**: 9150-GETCARD-BYACCT paragraph at L779-L812 is translated faithfully even though unreachable from 9000-READ-DATA. DID-NOT-FIND-ACCT-IN-CARDXREF (L151-L152), XREF-READ-ERROR alternate path (L800-L807), and CODING-TO-BE-DONE (L157-L158) are all DEAD CODE — each is flagged in `java/MIGRATION_NOTES.md`.
- **Verbatim error messages** (13 distinct WS-INFO-MSG / WS-RETURN-MSG 88-level conditions):

```text
'   Displaying requested details'                      app/cbl/COCRDSLC.cbl:L130 (3 leading spaces)
'Please enter Account and Card Number'                 app/cbl/COCRDSLC.cbl:L132
'PF03 pressed.Exiting              '                   app/cbl/COCRDSLC.cbl:L137 (14 trailing spaces)
'Account number not provided'                          app/cbl/COCRDSLC.cbl:L139
'Card number not provided'                             app/cbl/COCRDSLC.cbl:L141
'No input received'                                    app/cbl/COCRDSLC.cbl:L143
'Account number must be a non zero 11 digit number'    app/cbl/COCRDSLC.cbl:L145, L147 (2 conditions, same text)
'Card number if supplied must be a 16 digit number'    app/cbl/COCRDSLC.cbl:L149
'Did not find this account in cards database'          app/cbl/COCRDSLC.cbl:L152 (DEAD CODE)
'Did not find cards for this search condition'         app/cbl/COCRDSLC.cbl:L154
'Error reading Card Data File'                         app/cbl/COCRDSLC.cbl:L156
'Looks Good.... so far'                                app/cbl/COCRDSLC.cbl:L158 (4 periods + space, DEAD CODE)
```

- **Verbatim inline error messages** (4 distinct):

```text
'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER' app/cbl/COCRDSLC.cbl:L670
'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER' app/cbl/COCRDSLC.cbl:L711
'UNEXPECTED DATA SCENARIO'                             app/cbl/COCRDSLC.cbl:L377
'UNEXPECTED ABEND OCCURRED.'                           app/cbl/COCRDSLC.cbl:L860
```

- **Fixed Clock injection** (AAP §0.6.6): tests inject a deterministic clock via `ScopedValue` so that CURDATEO and CURTIMEO fields produce reproducible byte sequences in `bms_output.txt`.
- **AID-key restriction**: only ENTER and PF03 are valid (`app/cbl/COCRDSLC.cbl:L292-L295`); all other AIDs are re-mapped to ENTER via the `IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE` block at L297-L299. The Java translation's pattern-matching switch MUST replicate this AID re-mapping faithfully.
- **Reentry from COCRDLIC pre-validated path** (`app/cbl/COCRDSLC.cbl:L339-L348`): when `CDEMO-FROM-PROGRAM = LIT-CCLISTPGM ('COCRDLIC')`, the program skips re-validation and directly performs 9000-READ-DATA + 1000-SEND-MAP using the CDEMO-ACCT-ID + CDEMO-CARD-NUM passed via commarea.
- **STARTBR/ENDBR NOT used**: unlike COCRDLIC (which paginates), COCRDSLC issues a single-record READ — no browse cursor is opened.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CoCrdSlC` MUST carry `@CobolProgram("COCRDSLC")` Javadoc-style annotation citing the original PROGRAM-ID, source path `app/cbl/COCRDSLC.cbl`, and translation date.

## Source lineage

These REFERENCE-only source files remain UNCHANGED per AAP §0.1.1 and §0.2.2:

- `app/cbl/COCRDSLC.cbl` (887 lines) — Card-view CICS COBOL program; PROGRAM-ID `COCRDSLC` at L23-L24; transaction `CCDL` at L165-L166; key paragraphs: 0000-MAIN (L248), COMMON-RETURN (L394), 1000-SEND-MAP (L412), 1100-SCREEN-INIT (L427), 1200-SETUP-SCREEN-VARS (L457), 1300-SETUP-SCREEN-ATTRS (L502), 1400-SEND-SCREEN (L563), 2000-PROCESS-INPUTS (L582), 2100-RECEIVE-MAP (L596), 2200-EDIT-MAP-INPUTS (L608), 2210-EDIT-ACCOUNT (L647), 2220-EDIT-CARD (L685), 9000-READ-DATA (L726), 9100-GETCARD-BYACCTCARD (L736 — ACTIVE), 9150-GETCARD-BYACCT (L779 — DEAD CODE), SEND-LONG-TEXT (L820), SEND-PLAIN-TEXT (L838), ABEND-ROUTINE (L857).
- `app/bms/COCRDSL.bms` (157 lines) — BMS map definition. MAPSET `COCRDSL`, MAP `CCRDSLA`, SIZE=(24,80). Two editable input fields: `ACCTSID PIC X(11)` at (7,45) ATTRB=(FSET,IC,NORM,UNPROT) and `CARDSID PIC X(16)` at (8,45) ATTRB=(FSET,NORM,UNPROT). Header fields TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME. Output-only display fields CRDNAME (50), CRDSTCD (1), EXPMON (2), EXPYEAR (4), INFOMSG (40), ERRMSG (80), FKEYS (75).
- `app/cpy-bms/COCRDSL.CPY` (200 lines) — Symbolic map copybook defining `01 CCRDSLAI` (input record) and `01 CCRDSLAO REDEFINES CCRDSLAI` (output record); each visible BMS field expands to L/F/A/I+O groups.
- `app/cpy/CVACT02Y.cpy` — `01 CARD-RECORD` 150-byte fixed-width layout (7 fields: CARD-NUM 16 + CARD-ACCT-ID 11 + CARD-CVV-CD 3 + CARD-EMBOSSED-NAME 50 + CARD-EXPIRAION-DATE 10 + CARD-ACTIVE-STATUS 1 + FILLER 59).
- `app/cpy/CVACT03Y.cpy` — `01 CARD-XREF-RECORD` 50-byte fixed-width layout (4 fields: XREF-CARD-NUM 16 + XREF-CUST-ID 9 + XREF-ACCT-ID 11 + FILLER 14). Referenced by harness even though COCRDSLC's COPY at L237 is COMMENTED OUT.
- `app/cpy/COCOM01Y.cpy` — `01 CARDDEMO-COMMAREA` containing CDEMO-FROM-PROGRAM, CDEMO-FROM-TRANID, CDEMO-PGM-ENTER/REENTER, CDEMO-USRTYP-USER, CDEMO-ACCT-ID, CDEMO-CARD-NUM, CDEMO-LAST-MAPSET/MAP used by COCRDSLC.
- `app/cpy/CVCRD01Y.cpy` — `CC-WORK-AREA` + AidKey sealed taxonomy (CCARD-AID-ENTER, CCARD-AID-PFK03, etc.); cited at COCRDSLC L194.
- `app/data/ASCII/carddata.txt` — 150-byte CARDDAT initial-state fixture (50 records; REFERENCE only).
- `app/data/ASCII/cardxref.txt` — 50-byte CARD-XREF-RECORD initial-state fixture (REFERENCE only).

## Authority references

- AAP §0.2.1 (in-scope: `golden/cocrdslc/` directory tree as part of `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (COCRDSLC → CoCrdSlC card-view online program; ASCII fixtures read via classpath, NOT COPIED).
- AAP §0.6.4 (`java.time` mandate for date-bearing fields).
- AAP §0.6.5 (`java.nio.file` mandate for all file I/O; NO file-class from the legacy I/O package).
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal`).
- AAP §0.6.11 (golden-record PR gate; `@Disabled` scaffolding until COBOL captures committed).
- AAP §0.6.12 (architectural override: no Spring/PostgreSQL/Hibernate/Spring Batch/Spring Security).
- AAP §0.1.1 and §0.2.2 (COBOL source tree under `app/` is UNCHANGED).
- AAP §0.7.1 (Minimal Change Clause; preserve verbatim 13+4 COBOL messages, dead code, READ-only semantics).
- AAP §0.7.2 (PAN masking; mask all but last 4 digits in logs/displays).
- AAP §0.7.4 (no JEP preview features; no fallback branches in pattern-matching switches).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).
- Sibling pattern references: `golden/cocrdupc/input/README.md`, `golden/cotrn01c/input/README.md` (both documentation-only markers).

## DO NOT add files here

```text
This folder MUST remain documentation-only. Do NOT add fixture data files,
.gitkeep placeholders, input_scenario.txt, carddata.txt, cardxref.txt, or
any other content. The README.md IS the directory's marker.

DO NOT add carddata.txt — it lives in app/data/ASCII/ and is read via classpath
DO NOT add cardxref.txt — same as above
DO NOT add input_scenario.txt — lives in ../expected/ per canonical convention
DO NOT add any binary file, fixture, screenshot, log, or capture under this folder
DO NOT modify this README.md without re-validating the consuming Java test class

All fixture data files for the COCRDSLC parity test live either in
app/data/ASCII/ (primary fixtures, read via classpath) or in the sibling
../expected/ folder (test-owned scaffolding), because the consuming test class
CoCrdSlCGoldenTest resolves every fixture path through
resolveExpectedOutputPath("cocrdslc", ...). Adding files here would create
duplicate, stale, or unreachable fixtures.

If a future test scenario requires additional inputs, add the new file to
../expected/ and extend the appropriate override in CoCrdSlCGoldenTest.java
(auxiliaryInputs() or a new path-returning method) — not here.
```
