# COACTVWC (Account View, CAVW) — Golden-Record `input/` Folder (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files and this `README.md` is the only file present. COACTVWC is the **account-view CICS online program** (transaction `CAVW`, "View Account Detail") translated to Java `com.blitzy.carddemo.application.account.CoActVwC` per AAP §0.4.1. COACTVWC performs a **3-step READ-only file chain**: CXACAIX alternate index by account-id → ACCTDAT by account-id → CUSTDAT by customer-id (the customer-id is retrieved from the CARD-XREF-RECORD returned by the first READ). All three `EXEC CICS READ` paragraphs are issued WITHOUT any `UPDATE` clause anywhere across the program's 941 lines — verified at `app/cbl/COACTVWC.cbl:L723-L770` (CXACAIX), `app/cbl/COACTVWC.cbl:L774-L823` (ACCTDAT), and `app/cbl/COACTVWC.cbl:L825-L872` (CUSTDAT). Primary fixtures (`acctdata.txt`, `custdata.txt`, `cardxref.txt`) live in `app/data/ASCII/` and are read via classpath relative path (per AAP §0.4.1, "REFERENCE only, NOT COPIED"); the test-owned CICS driver script (`input_scenario.txt`) lives in the sibling `../expected/` folder per the canonical convention from AAP §0.3.1.

## Why this folder is documentation-only

The COBOL working-storage literal block at `app/cbl/COACTVWC.cbl:L143-L202` declares the program's identity and the names of every external resource it touches: `LIT-THISPGM PIC X(8) VALUE 'COACTVWC'` (L143-L144), `LIT-THISTRANID PIC X(4) VALUE 'CAVW'` (L145-L146), `LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '` (L147-L148, 8 chars with trailing space), `LIT-THISMAP PIC X(7) VALUE 'CACTVWA'` (L149-L150, 7 chars), `LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '` (L184-L185), `LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '` (L188-L189), and `LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '` (L192-L193) for the actively used CARDXREF alternate index. `LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '` (L190-L191) is declared but NOT referenced by any active READ paragraph in COACTVWC — the primary card alternate-index path is dead code in this program.

The user-facing input arrives via the BMS map `CACTVWA` defined in `app/bms/COACTVW.bms` with **only ONE editable input field**: ACCTSID, the 11-digit account number, at row 5 column 38, with `ATTRB=(FSET,IC,NORM,UNPROT)`, `PICIN='99999999999'`, `VALIDN=(MUSTFILL)`, `COLOR=GREEN`, `HILIGHT=UNDERLINE`. The only valid AIDs are ENTER and PF03; all other AIDs are silently re-mapped to ENTER via the standard CC-WORK-AREA pattern, whose AidKey 88-level conditions live at `app/cpy/CVCRD01Y.cpy` (CCARD-AID-ENTER, CCARD-AID-CLEAR, CCARD-AID-PA1, CCARD-AID-PA2, CCARD-AID-PFK01 through CCARD-AID-PFK12).

The consuming Java test class `com.blitzy.carddemo.tests.golden.CoActVwCGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and routes ALL fixture paths via `resolveExpectedOutputPath("coactvwc", filename)` to `src/test/resources/golden/coactvwc/expected/`; therefore NO fixture data files belong in this `input/` folder. Keeping an empty `input/` folder with only this README preserves the established `<program>/input/` + `<program>/expected/` symmetry from AAP §0.3.1 across all program test fixtures, while making the absence of data files self-documenting.

Each conceptual input fixture lives at its actual home:

- `app/data/ASCII/acctdata.txt` — Primary ACCTDAT initial-state fixture (REFERENCE; read via classpath; NOT COPIED).
- `app/data/ASCII/custdata.txt` — Primary CUSTDAT initial-state fixture (REFERENCE; read via classpath; NOT COPIED).
- `app/data/ASCII/cardxref.txt` — Primary CARDXREF/CXACAIX initial-state fixture (REFERENCE; read via classpath; NOT COPIED).
- `../expected/README.md` — Authoritative fixture contract for the COACTVWC golden-record test.
- `../expected/input_scenario.txt` — CICS pseudo-conversation driver script (TEST-OWNED).
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER — expected EMPTY because COACTVWC has ZERO active DISPLAY verbs).
- `../expected/bms_output.txt` — Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER).

## Conceptual input contract

### BMS map inputs — single editable field ACCTSID

The CACTVWA BMS map (defined in `app/bms/COACTVW.bms`, symbolic copybook `app/cpy-bms/COACTVW.CPY`) has **exactly ONE editable input field**: `ACCTSIDI` PIC X(11), at screen position (5, 38), with `ATTRB=(FSET,IC,NORM,UNPROT)`, `PICIN='99999999999'`, `VALIDN=(MUSTFILL)`, `COLOR=GREEN`, `HILIGHT=UNDERLINE`. The `IC` attribute marks the initial cursor position, so the cursor lands in ACCTSID on first display of the map.

AID-key dispatch accepts only ENTER and PF03 per `app/cbl/COACTVWC.cbl:L306-L314`; all other AIDs are silently re-mapped to ENTER via the standard CC-WORK-AREA pattern (PFK-INVALID flag set at L306; remap to CCARD-AID-ENTER at L312-L314). The AidKey definition lives at `app/cpy/CVCRD01Y.cpy:L4-L19` (16 conditions: CCARD-AID-ENTER, CCARD-AID-CLEAR, CCARD-AID-PA1, CCARD-AID-PA2, CCARD-AID-PFK01 through CCARD-AID-PFK12).

All other on-screen data fields — ASTTUSO (active-status), ACURBALO (current balance), ACRDLIMO (credit limit), ACSHLIMO (cash credit limit), ACRCYCRO (current cycle credit), ACRCYDBO (current cycle debit), ADTOPENO (open date), AEXPDTO (expiration date), AREISDTO (reissue date), AADDGRPO (account group id), ACSTNUMO (customer number), ACSTSSNO (SSN), ACSTFCOO (FICO score), ACSTDOBO (DOB), ACSFNAMO (first name), ACSMNAMO (middle name), ACSLNAMO (last name), ACSADL1O (address line 1), ACSADL2O (address line 2), ACSCITYO (city), ACSSTTEO (state), ACSZIPCO (ZIP), ACSCTRYO (country), ACSPHN1O (phone 1), ACSPHN2O (phone 2), ACSGOVTO (govt-issued id), ACSEFTCO (EFT account id), ACSPFLGO (primary-card-holder indicator), INFOMSGO (info message), ERRMSGO (error message) — are PROT or ASKIP. They are **output-only display fields** populated from ACCT-RECORD and CUST-RECORD after a successful 3-step file lookup chain. This confirms COACTVWC is a strict **VIEW** program, distinct from COACTUPC (Account Update) where editable fields would carry the UNPROT attribute.

### CICS dispatch context — commarea overlay and reentry detection

The reentry detection at `app/cbl/COACTVWC.cbl:L282-L293` reads the inbound commarea overlay (DFHCOMMAREA carries CARDDEMO-COMMAREA in the first segment and WS-THIS-PROGCOMMAREA in the second segment) and initializes both areas when the program is freshly entered:

```cobol
IF EIBCALEN IS EQUAL TO 0
    OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM
    AND NOT CDEMO-PGM-REENTER)
   INITIALIZE CARDDEMO-COMMAREA
              WS-THIS-PROGCOMMAREA
ELSE
   MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA)
                                  TO CARDDEMO-COMMAREA
   MOVE DFHCOMMAREA (LENGTH OF CARDDEMO-COMMAREA + 1:
                     LENGTH OF WS-THIS-PROGCOMMAREA)
                                  TO WS-THIS-PROGCOMMAREA
END-IF
```

The **4-branch main EVALUATE TRUE** at `app/cbl/COACTVWC.cbl:L323-L383` dispatches the request:

1. **WHEN CCARD-AID-PFK03** (L324-L352) — EXIT path: XCTL to CDEMO-FROM-PROGRAM (or LIT-MENUPGM default when CDEMO-FROM-PROGRAM is LOW-VALUES or SPACES); sets WS-EXIT-MESSAGE in CCARD-ERROR-MSG.
2. **WHEN CDEMO-PGM-ENTER** (L353-L360) — fresh entry: PERFORM 1000-SEND-MAP to display the empty form with WS-PROMPT-FOR-INPUT, then GO TO COMMON-RETURN.
3. **WHEN CDEMO-PGM-REENTER** (L361-L374) — user submitted account ID: PERFORM 2000-PROCESS-INPUTS; if INPUT-ERROR, PERFORM 1000-SEND-MAP and GO TO COMMON-RETURN; else PERFORM 9000-READ-ACCT (the 3-step chain) followed by 1000-SEND-MAP.
4. **WHEN OTHER** (L375-L382) — sets ABEND-CULPRIT to LIT-THISPGM and ABEND-CODE to `'0001'`, MOVE `'UNEXPECTED DATA SCENARIO'` to WS-RETURN-MSG (cite `app/cbl/COACTVWC.cbl:L379`), then PERFORM SEND-PLAIN-TEXT.

Unlike COCRDSLC (Card View) which has an auto-trigger branch when called from COCRDLIC (Card List), COACTVWC has NO auto-trigger-from-COCRDLIC branch — only 4 EVALUATE branches, not 5.

### 3-step READ flow — CXACAIX → ACCTDAT → CUSTDAT (all READ-only)

The 9000-READ-ACCT orchestrator at `app/cbl/COACTVWC.cbl:L687-L722` sequences three single-record READs against three different VSAM files. Each subsequent READ is gated by a found-flag set by the prior step:

```cobol
9200-GETCARDXREF-BYACCT.   *> app/cbl/COACTVWC.cbl:L723-L770
    EXEC CICS READ
         DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
         INTO      (CARD-XREF-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC.
    *> NORMAL: MOVE XREF-CUST-ID TO CDEMO-CUST-ID;
    *>         MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM

9300-GETACCTDATA-BYACCT.   *> app/cbl/COACTVWC.cbl:L774-L823
    EXEC CICS READ
         DATASET   (LIT-ACCTFILENAME)
         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
         INTO      (ACCOUNT-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC.
    *> NORMAL: SET FOUND-ACCT-IN-MASTER TO TRUE

9400-GETCUSTDATA-BYCUST.   *> app/cbl/COACTVWC.cbl:L825-L872
    EXEC CICS READ
         DATASET   (LIT-CUSTFILENAME)
         RIDFLD    (WS-CARD-RID-CUST-ID-X)
         INTO      (CUSTOMER-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC.
    *> NORMAL: SET FOUND-CUST-IN-MASTER TO TRUE
```

Critical observations:

- **NO `UPDATE` clause on any of the 3 READs** — COACTVWC is strictly READ-only across all 3 files. Post-run `acctdata.txt`, `custdata.txt`, AND `cardxref.txt` MUST all be byte-identical to pre-run state.
- The 9200 RIDFLD is `WS-CARD-RID-ACCT-ID-X` — the alphanumeric `PIC X(11)` REDEFINES of the numeric `WS-CARD-RID-ACCT-ID PIC 9(11)` declared at `app/cbl/COACTVWC.cbl:L78-L80`.
- The 3-step chain establishes a dependency: 9300 and 9400 are gated by FOUND-ACCT-IN-MASTER and FOUND-CUST-IN-MASTER flags set in the prior step. If CXACAIX READ fails NOTFND, neither 9300 nor 9400 executes; the NOTFND path builds a dynamic STRING error message and returns immediately (orchestrator GO TOs at L697-L698, L704-L705, L713-L714).
- Each READ paragraph has separate WHEN NORMAL / WHEN NOTFND / WHEN OTHER paths inside an `EVALUATE WS-RESP-CD`. WHEN OTHER paths construct dynamic error messages embedding ERROR-RESP and ERROR-RESP2 codes from the WS-FILE-ERROR-MESSAGE template at `app/cbl/COACTVWC.cbl:L86-L105`.
- 3 RESP outcomes per READ × 3 READs = 9 distinct response paths total; the full inventory is cross-referenced in `../expected/README.md` Phase 6.

### Fixture table — data lives in `app/data/ASCII/` or `../expected/`, not here

| Fixture File | Path | Layout Copybook | Record Length | Mode |
|---|---|---|---|---|
| acctdata.txt | `app/data/ASCII/acctdata.txt` | `app/cpy/CVACT01Y.cpy` (ACCOUNT-RECORD) | 300 bytes | REFERENCE (classpath; NOT COPIED) |
| custdata.txt | `app/data/ASCII/custdata.txt` | `app/cpy/CVCUS01Y.cpy` (CUSTOMER-RECORD) | 500 bytes | REFERENCE (classpath; NOT COPIED) |
| cardxref.txt | `app/data/ASCII/cardxref.txt` | `app/cpy/CVACT03Y.cpy` (CARD-XREF-RECORD) | 50 bytes | REFERENCE (classpath; NOT COPIED) |
| input_scenario.txt | `../expected/input_scenario.txt` | (test-owned) | n/a | TEST-OWNED CICS driver script |

Post-run `acctdata.txt`, `custdata.txt`, AND `cardxref.txt` are ALL byte-identical to their inputs (READ-only semantics across the entire 3-file chain), so NO `*_after.txt` fixtures are required in `../expected/`. The harness asserts post-run equality against the original `app/data/ASCII/*.txt` bytes directly.

### Scenarios driven by `../expected/input_scenario.txt`

Full per-scenario detail lives in `../expected/README.md` Phase 11; the enumeration below is provided here for cross-reference only:

1. **Fresh entry, no input** → display empty form with WS-PROMPT-FOR-INPUT.
2. **ENTER with ACCTSID blank** → WS-PROMPT-FOR-ACCT (verified at `app/cbl/COACTVWC.cbl:L658`).
3. **ENTER with ACCTSID = `'00000000000'`** → SEARCHED-ACCT-ZEROES (verified at `app/cbl/COACTVWC.cbl:L666-L667`).
4. **ENTER with ACCTSID non-numeric** → inline `'Account Filter must  be a non-zero 11 digit number'` (DOUBLE SPACE between "must" and "be" preserved verbatim per `app/cbl/COACTVWC.cbl:L672`).
5. **ENTER with valid ACCTSID, full 3-step chain succeeds** → WS-INFORM-OUTPUT plus populated account+customer fields on the map.
6. **ENTER with valid ACCTSID, NOT found in CXACAIX** → 9200 STRING template error from `app/cbl/COACTVWC.cbl:L747-L757`.
7. **ENTER with valid ACCTSID, found in CXACAIX but NOT in ACCTDAT** → 9300 STRING template error from `app/cbl/COACTVWC.cbl:L796-L806`.
8. **ENTER with valid ACCTSID, found in CXACAIX + ACCTDAT but NOT in CUSTDAT** → 9400 STRING template error from `app/cbl/COACTVWC.cbl:L846-L856`.
9. **PF03 with EIBCALEN=0** → XCTL to LIT-MENUPGM (COMEN01C) with WS-EXIT-MESSAGE.
10. **PF03 with CDEMO-FROM-PROGRAM = COCRDLIC** → XCTL to COCRDLIC with WS-EXIT-MESSAGE.
11. **Invalid AID (e.g., PFK04)** → silently re-mapped to ENTER per the AidKey pattern at `app/cbl/COACTVWC.cbl:L306-L314`.
12. **CXACAIX READ WHEN OTHER (non-NOTFND I/O error)** → 9200 STRING with double-space after `file.` (the same anomaly applies to the dynamic template at L751).

## Cross-reference to the Java test class

The Java test class is `com.blitzy.carddemo.tests.golden.CoActVwCGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoActVwCGoldenTest.java`. It extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The class under test is `com.blitzy.carddemo.application.account.CoActVwC` per AAP §0.4.1 — note the `account` subpackage, NOT `card`; this distinguishes COACTVWC (account view) from COCRDSLC (card view) which lives under `com.blitzy.carddemo.application.card.CoCrdSlC`.

Override summary (5 methods on the test class):

- `programClass()` returns `com.blitzy.carddemo.application.account.CoActVwC.class`.
- `inputFile()` returns `resolveExpectedOutputPath("coactvwc", "input_scenario.txt")` → routes to `../expected/input_scenario.txt`.
- `expectedOutputFile()` returns `resolveExpectedOutputPath("coactvwc", "stdout.txt")` → routes to `../expected/stdout.txt`.
- `auxiliaryInputs()` references `app/data/ASCII/acctdata.txt`, `app/data/ASCII/custdata.txt`, AND `app/data/ASCII/cardxref.txt` via classpath relative path (NOT copied per AAP §0.4.1).
- `expectedOutputs()` returns `ExpectedOutput` entries for `stdout.txt` and `bms_output.txt` (both under `../expected/`). **NO `acctdata_after.txt`, `custdata_after.txt`, or `cardxref_after.txt` entries** — because COACTVWC is READ-only across all 3 files, post-run state is byte-identical to input and is verified by direct equality against the original fixtures.

All 5 overrides route either to `../expected/` (via `resolveExpectedOutputPath(...)`) or to `app/data/ASCII/` (via classpath). **None routes to this `input/` folder** — that is the architectural reason the folder is empty. The test class MUST be annotated `@Disabled("Pending COBOL baseline capture — see java/MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the COBOL captures are committed.

Verification points the test enforces:

1. Fresh entry displays the empty form correctly with WS-PROMPT-FOR-INPUT.
2. ACCTSID validation produces correct verbatim error messages, including the L672 double-space anomaly.
3. 3-step chain success produces `'Displaying details of given Account'` plus populated account+customer fields; SSN formatted as `NNN-NN-NNNN`; phone formatted as `(NNN)NNN-NNNN`.
4. Each NOTFND step in the chain produces the correct chain-specific STRING error message (9200 / 9300 / 9400).
5. PF03 produces `'PF03 pressed.Exiting              '` with 14 trailing spaces preserved byte-for-byte.
6. Any 16-digit PAN value (e.g., XREF-CARD-NUM held in commarea) appears MASKED in `stdout.txt` and `bms_output.txt` per AAP §0.7.2 — last 4 digits visible only.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md §1.6` (Golden-record fixture capture procedure for COACTVWC) for the CICS COBOL build/run path used to capture expected outputs, per the AAP §0.7.5 resolution of the user `[TODO]` marker on golden-record fixture regeneration. Until the capture is performed and committed, `../expected/stdout.txt` and `../expected/bms_output.txt` hold placeholder content and `CoActVwCGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **READ-only file semantics across 3 files**: COACTVWC does not call `EXEC CICS REWRITE` anywhere in its 941 lines. Post-run `acctdata.txt`, `custdata.txt`, AND `cardxref.txt` MUST all be byte-identical to pre-run state.
- **PAN masking per AAP §0.7.2**: any 16-digit XREF-CARD-NUM value held in CDEMO-CARD-NUM or surfaced in captured `bms_output.txt` and `stdout.txt` MUST be masked (last 4 visible only). The card number is not displayed on the COACTVW BMS map (no CRDNUM field), so PAN masking is principally an invariant for any auxiliary diagnostic output paths and for commarea logging.
- **SSN formatting**: CUST-SSN PIC 9(09) is formatted as `NNN-NN-NNNN` via COBOL STRING verb in `1200-SETUP-SCREEN-VARS` (around `app/cbl/COACTVWC.cbl:L460-L540`) — preserve hyphen positions byte-for-byte.
- **Phone formatting**: CUST-PHONE-NUM-1 and CUST-PHONE-NUM-2 PIC X(15) preserved as-is (source format `(NNN)NNN-NNNN` followed by 4 trailing spaces to fill the 15-byte field).
- **Verbatim message preservation per AAP §0.7.1**: 12 distinct WS-INFO-MSG / WS-RETURN-MSG 88-level conditions plus 3 inline error messages (the L672 inline with double-space anomaly, the L379 WHEN-OTHER scenario, the L919 ABEND default) must be reproduced byte-for-byte in any captured output.

88-level message inventory (preserve byte-for-byte):

```text
'Enter or update id of account to display'            app/cbl/COACTVWC.cbl:L113-L114 (WS-PROMPT-FOR-INPUT)
'Displaying details of given Account'                 app/cbl/COACTVWC.cbl:L115-L116 (WS-INFORM-OUTPUT)
'PF03 pressed.Exiting              '                  app/cbl/COACTVWC.cbl:L119-L120 (WS-EXIT-MESSAGE, 14 trailing spaces)
'Account number not provided'                         app/cbl/COACTVWC.cbl:L121-L122 (WS-PROMPT-FOR-ACCT)
'No input received'                                   app/cbl/COACTVWC.cbl:L123-L124 (NO-SEARCH-CRITERIA-RECEIVED)
'Account number must be a non zero 11 digit number'   app/cbl/COACTVWC.cbl:L125-L126 (SEARCHED-ACCT-ZEROES)
'Account number must be a non zero 11 digit number'   app/cbl/COACTVWC.cbl:L127-L128 (SEARCHED-ACCT-NOT-NUMERIC, DUPLICATE TEXT)
'Did not find this account in account card xref file' app/cbl/COACTVWC.cbl:L129-L130 (DID-NOT-FIND-ACCT-IN-CARDXREF)
'Did not find this account in account master file'    app/cbl/COACTVWC.cbl:L131-L132 (DID-NOT-FIND-ACCT-IN-ACCTDAT)
'Did not find associated customer in master file'     app/cbl/COACTVWC.cbl:L133-L134 (DID-NOT-FIND-CUST-IN-CUSTDAT)
'Error reading account card xref File'                app/cbl/COACTVWC.cbl:L135-L136 (XREF-READ-ERROR)
'Looks Good.... so far'                               app/cbl/COACTVWC.cbl:L137-L138 (CODING-TO-BE-DONE, 4 periods + space, DEAD CODE)
```

Inline error message inventory (preserve byte-for-byte):

```text
'Account Filter must  be a non-zero 11 digit number'  app/cbl/COACTVWC.cbl:L672 (INLINE, DOUBLE SPACE between "must" and "be")
'UNEXPECTED DATA SCENARIO'                            app/cbl/COACTVWC.cbl:L379 (0000-MAIN WHEN OTHER)
'UNEXPECTED ABEND OCCURRED.'                          app/cbl/COACTVWC.cbl:L919 (ABEND-ROUTINE default)
```

- **Dead code preservation per AAP §0.7.1**: the `CODING-TO-BE-DONE` 88-level condition is DEAD CODE — translate faithfully and flag in `java/MIGRATION_NOTES.md §1.6` rather than removing it.
- **Duplicate exit-paragraph anomaly**: `0000-MAIN-EXIT.` appears at BOTH `app/cbl/COACTVWC.cbl:L408` AND `app/cbl/COACTVWC.cbl:L411` — a source anomaly that the Java translation must preserve as two adjacent empty exit methods OR document explicitly in the class-level Javadoc.
- **Typo preservation**: `LIT-CARDUDPATETRANID` at `app/cbl/COACTVWC.cbl:L161` is a typo (CARDUDPATE not CARDUPDATE) holding the value `'CCUP'`; preserve the Java field name verbatim and flag in `java/MIGRATION_NOTES.md §1.6` per AAP §0.7.1.
- **9200 STRING template double-space anomaly**: at `app/cbl/COACTVWC.cbl:L747-L757` the dynamic error template emits `'Account:' WS-CARD-RID-ACCT-ID-X ' not found in' ' Cross ref file.  Resp:' ERROR-RESP ' Reas:' ERROR-RESP2` — the DOUBLE SPACE after `file.` at `app/cbl/COACTVWC.cbl:L751` must be preserved byte-for-byte in the Java translation's text-block STRING equivalent.
- **Fixed Clock injection** (AAP §0.6.6): tests inject a deterministic `java.time.Clock` via `ScopedValue` so any CURDATEO and CURTIMEO header fields populated from the COBOL date/time work area (`CSDAT01Y.cpy`) produce reproducible byte sequences in `bms_output.txt`.
- **AID-key handling**: only ENTER and PF03 are valid; all other AIDs are silently re-mapped to ENTER per the AidKey pattern. The Java translation's pattern-matching switch MUST replicate this re-mapping faithfully WITHOUT a `default` branch (per AAP §0.7.4 exhaustiveness mandate).
- **STARTBR/ENDBR NOT used**: unlike COCRDLIC (which paginates a card list), COACTVWC issues single-record READs across all 3 files — no browse cursor is opened anywhere in the program.
- **`@CobolProgram` traceability** (per AAP §0.3.1): the Java class `CoActVwC` MUST carry `@CobolProgram("COACTVWC")` annotation citing the original PROGRAM-ID `COACTVWC`, source path `app/cbl/COACTVWC.cbl`, and the translation date.
- **ABEND ABCODE preservation**: the literal `'9999'` ABCODE at `app/cbl/COACTVWC.cbl:L935` (issued by the `EXEC CICS ABEND` in ABEND-ROUTINE) must be preserved in the Java exception translation as a typed exception carrying the same code.

## Source lineage

The following REFERENCE-only source files are UNCHANGED per AAP §0.1.1 / §0.2.2; they are read by the harness via classpath relative path or are cited here for traceability only:

- `app/cbl/COACTVWC.cbl` (941 lines) — Account-view CICS COBOL program; PROGRAM-ID `COACTVWC` at `app/cbl/COACTVWC.cbl:L22-L23`; transaction `'CAVW'` at `app/cbl/COACTVWC.cbl:L145-L146`; key paragraphs: 0000-MAIN (L262), COMMON-RETURN (L394), 0000-MAIN-EXIT (L408 AND L411 — duplicate-label anomaly), 1000-SEND-MAP (L416), 1100-SCREEN-INIT (L431), 1200-SETUP-SCREEN-VARS (L460), 1300-SETUP-SCREEN-ATTRS (L541), 1400-SEND-SCREEN (L577), 2000-PROCESS-INPUTS (L596), 2100-RECEIVE-MAP (L610), 2200-EDIT-MAP-INPUTS (L622), 2210-EDIT-ACCOUNT (L649), 9000-READ-ACCT (L687), 9200-GETCARDXREF-BYACCT (L723 — reads CXACAIX), 9300-GETACCTDATA-BYACCT (L774 — reads ACCTDAT), 9400-GETCUSTDATA-BYCUST (L825 — reads CUSTDAT), SEND-PLAIN-TEXT (L877), SEND-LONG-TEXT (L896), ABEND-ROUTINE (L916).
- `app/bms/COACTVW.bms` (378 lines) — BMS map definition; MAPSET `COACTVW`, MAP `CACTVWA`, SIZE=(24,80); single editable input field `ACCTSID PIC X(11)` at (5,38) `ATTRB=(FSET,IC,NORM,UNPROT)` `PICIN='99999999999'` `VALIDN=(MUSTFILL)` (verified at `app/bms/COACTVW.bms:L84-L90`).
- `app/cpy-bms/COACTVW.CPY` (464 lines) — Symbolic map copybook defining `01 CACTVWAI` (input record) and `01 CACTVWAO REDEFINES CACTVWAI` (output record); each visible BMS field expands to L/F/A/I+O groups.
- `app/cpy/CVACT01Y.cpy` (20 lines) — `01 ACCOUNT-RECORD` 300-byte fixed-width layout (13 fields: ACCT-ID PIC 9(11), ACCT-ACTIVE-STATUS PIC X(01), ACCT-CURR-BAL PIC S9(10)V99, ACCT-CREDIT-LIMIT PIC S9(10)V99, ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99, ACCT-OPEN-DATE PIC X(10), ACCT-EXPIRAION-DATE PIC X(10) — COBOL misspelling preserved, ACCT-REISSUE-DATE PIC X(10), ACCT-CURR-CYC-CREDIT PIC S9(10)V99, ACCT-CURR-CYC-DEBIT PIC S9(10)V99, ACCT-ADDR-ZIP PIC X(10), ACCT-GROUP-ID PIC X(10), FILLER PIC X(178)). Total = 300.
- `app/cpy/CVACT03Y.cpy` (11 lines) — `01 CARD-XREF-RECORD` 50-byte fixed-width layout (4 fields: XREF-CARD-NUM PIC X(16), XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11), FILLER PIC X(14)). Total = 50.
- `app/cpy/CVCUS01Y.cpy` (26 lines) — `01 CUSTOMER-RECORD` 500-byte fixed-width layout (19 fields: CUST-ID PIC 9(09), CUST-FIRST-NAME PIC X(25), CUST-MIDDLE-NAME PIC X(25), CUST-LAST-NAME PIC X(25), CUST-ADDR-LINE-1 PIC X(50), CUST-ADDR-LINE-2 PIC X(50), CUST-ADDR-LINE-3 PIC X(50), CUST-ADDR-STATE-CD PIC X(02), CUST-ADDR-COUNTRY-CD PIC X(03), CUST-ADDR-ZIP PIC X(10), CUST-PHONE-NUM-1 PIC X(15), CUST-PHONE-NUM-2 PIC X(15), CUST-SSN PIC 9(09), CUST-GOVT-ISSUED-ID PIC X(20), CUST-DOB-YYYY-MM-DD PIC X(10), CUST-EFT-ACCOUNT-ID PIC X(10), CUST-PRI-CARD-HOLDER-IND PIC X(01), CUST-FICO-CREDIT-SCORE PIC 9(03), FILLER PIC X(168)). Total = 500.
- `app/cpy/COCOM01Y.cpy` (47 lines) — `01 CARDDEMO-COMMAREA`: CDEMO-GENERAL-INFO (CDEMO-FROM-TRANID PIC X(4), CDEMO-FROM-PROGRAM PIC X(8), CDEMO-USER-TYPE PIC X(1) with 88-levels CDEMO-USRTYP-ADMIN='A' / CDEMO-USRTYP-USER='U', CDEMO-PGM-CONTEXT PIC 9(1) with 88-levels CDEMO-PGM-ENTER=0 / CDEMO-PGM-REENTER=1) plus CDEMO-CUSTOMER-INFO, CDEMO-ACCOUNT-INFO, CDEMO-CARD-INFO, CDEMO-MORE-INFO.
- `app/cpy/CVCRD01Y.cpy` (46 lines) — `01 CC-WORK-AREAS`: AID 88-conditions on CCARD-AID PIC X(5) (CCARD-AID-ENTER, CCARD-AID-CLEAR, CCARD-AID-PA1, CCARD-AID-PA2, CCARD-AID-PFK01 through CCARD-AID-PFK12); REDEFINES pairs CC-ACCT-ID/CC-ACCT-ID-N, CC-CARD-NUM/CC-CARD-NUM-N, CC-CUST-ID/CC-CUST-ID-N; CCARD-NEXT-PROG, CCARD-NEXT-MAPSET, CCARD-NEXT-MAP, CCARD-ERROR-MSG, CCARD-RETURN-MSG fields.
- `app/data/ASCII/acctdata.txt` — 300-byte ACCOUNT-RECORD initial-state fixture (REFERENCE only via classpath).
- `app/data/ASCII/custdata.txt` — 500-byte CUSTOMER-RECORD initial-state fixture (REFERENCE only via classpath).
- `app/data/ASCII/cardxref.txt` — 50-byte CARD-XREF-RECORD initial-state fixture (REFERENCE only via classpath).

## Authority references

- AAP §0.1.1 (Core refactoring objective; COBOL source tree under `app/` is UNCHANGED).
- AAP §0.2.1 (in-scope: `golden/coactvwc/` directory tree as part of the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.2.2 (out-of-scope: `app/` tree NOT modified by this refactor).
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`; `@CobolProgram` annotation traceability).
- AAP §0.4.1 (COACTVWC → CoActVwC account-view online program in `com.blitzy.carddemo.application.account`; ASCII fixtures via classpath, NOT COPIED).
- AAP §0.6.4 (`java.time` mandate for date-bearing fields — ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, CUST-DOB-YYYY-MM-DD).
- AAP §0.6.5 (`java.nio.file` mandate for all file I/O; NO legacy file-class).
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal`; fixed-Clock injection for reproducible date/time bytes).
- AAP §0.6.11 (golden-record PR gate; `@Disabled` scaffolding until COBOL captures committed).
- AAP §0.6.12 (architectural override: no Spring / no PostgreSQL / no Hibernate / no Spring Batch / no Spring Security).
- AAP §0.7.1 (Minimal Change Clause; verbatim 12 88-level messages + 3 inline error messages + double-space anomaly + duplicate exit-paragraph + dead-code preservation + LIT-CARDUDPATETRANID typo).
- AAP §0.7.2 (PAN masking; mask all but last 4 digits in logs and displays).
- AAP §0.7.4 (no JEP preview features; no `default` branches in pattern-matching switches).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md §1.6`).
- Sibling pattern: `golden/cocrdslc/input/README.md` (documentation-only marker pattern established for the analogous card-view program).

## DO NOT add files here

```text
This folder MUST contain EXACTLY one file: README.md (this file).

DO NOT add:
- .gitkeep or other empty marker files
- Fixture data files of any kind (no .txt, .dat, .csv, .json, .bin)
- Subfolders of any kind
- Binary files of any kind
- PAN-bearing logs or screen captures
- Captured COBOL outputs (those belong in ../expected/)
- Java source files
- Build artifacts

Rationale:
- The consuming Java test class CoActVwCGoldenTest resolves ALL fixture
  paths via resolveExpectedOutputPath("coactvwc", filename), which routes
  to ../expected/.  No code path reads from this input/ folder.
- Primary read-only fixtures (acctdata.txt, custdata.txt, cardxref.txt)
  live in app/data/ASCII/ and are read via classpath relative path per
  AAP §0.4.1 -- they MUST NOT be copied here.
- Adding files here creates stale or duplicate fixtures that diverge
  from the authoritative copies, violating AAP §0.7.1 (preserve-as-is).
- The empty input/ folder + README.md marker preserves the established
  <program>/input/ + <program>/expected/ symmetry from AAP §0.3.1.
```
