# COTRN01C (Transaction View, CT01) — Golden-Record `input/` Folder (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files (no `.txt`, no `.jsonl`, no `.bin`, no `.csv`, no `.gitkeep`, no subfolders). COTRN01C is the **single-transaction view online CICS program** (transaction `CT01`, source `app/cbl/COTRN01C.cbl`, 330 lines total) translated to Java `com.blitzy.carddemo.application.transaction.CoTrn01C` per AAP §0.4.1. COTRN01C has a **distinctive READ-with-UPDATE-but-no-REWRITE quirk** preserved verbatim per AAP §0.7.1 — see `app/cbl/COTRN01C.cbl:L275` for the `UPDATE` clause and `app/cbl/COTRN01C.cbl:L267-L296` for the entire READ-TRANSACT-FILE paragraph confirming no REWRITE is ever issued. COTRN01C is **callable from COTRN00C via row-selection auto-trigger** through the commarea field `CDEMO-CT01-TRN-SELECTED PIC X(16)` at `app/cbl/COTRN01C.cbl:L61`; when this field is non-blank and non-low-values on the first reentry pass, the program automatically MOVEs it to `TRNIDINI` and PERFORMs `PROCESS-ENTER-KEY`, skipping the interactive user-input phase (see `app/cbl/COTRN01C.cbl:L103-L108`). All fixture data files for the COTRN01C parity test live in the sibling `../expected/` folder.

## Why this folder is documentation-only

COTRN01C's WS-VARIABLES at `app/cbl/COTRN01C.cbl:L35-L50` declare the program-name, transaction-id, and TRANSACT dataset literals (`WS-PGMNAME PIC X(08) VALUE 'COTRN01C'` at L36; `WS-TRANID PIC X(04) VALUE 'CT01'` at L37; `WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'` at L39). The COPY directives at `app/cbl/COTRN01C.cbl:L52-L72` pull in `COCOM01Y` (commarea), `COTRN01` (BMS symbolic map), `COTTL01Y` (screen titles), `CSDAT01Y` (date constants), `CSMSG01Y` (common messages), `CVTRA05Y` (TRAN-RECORD layout), `DFHAID` (CICS AID constants), and `DFHBMSCA` (BMS attribute constants). The user-facing input arrives via the BMS map `COTRN1A` defined in `app/bms/COTRN01.bms` (single editable field `TRNIDIN`) plus AID-key dispatch — no file-based fixtures from `app/data/ASCII/*.txt` are consumed.

The consuming Java test class `com.blitzy.carddemo.tests.golden.CoTrn01CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) overrides `inputFile()`, `expectedOutputFile()`, `auxiliaryInputs()`, and `expectedOutputs()` to call `resolveExpectedOutputPath("cotrn01c", fileName)`, which mechanically routes every fixture path to `src/test/resources/golden/cotrn01c/expected/`. Therefore NO fixture data files exist in this `input/` folder.

Keeping an empty `input/` folder with only this README preserves the established `<program>/input/` + `<program>/expected/` symmetry from AAP §0.3.1 across all 28+ program test fixtures, while making the absence of data files explicit and self-documenting.

The sibling `../expected/` folder contains:

- `../expected/README.md` — Authoritative fixture contract for the COTRN01C golden-record test.
- `../expected/input_scenario.txt` — CICS pseudo-conversation driver script (TEST-OWNED).
- `../expected/transact.txt` — TRANSACT VSAM KSDS initial-state fixture (CAPTURE PLACEHOLDER).
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER; contains the `app/cbl/COTRN01C.cbl:L290` DISPLAY only under the WHEN OTHER error path).
- `../expected/bms_output.txt` — Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data lives in ../expected/)

### BMS map inputs: TRNIDIN + no other editable fields

The COTRN1A BMS map (defined in `app/bms/COTRN01.bms` and symbolic copybook `app/cpy-bms/COTRN01.CPY`) has exactly ONE editable input field:

- `TRNIDINI` (PIC X(16)) — the 16-character transaction ID search/fetch key, located in the symbolic input record `01 COTRN1AI` at `app/cpy-bms/COTRN01.CPY:L60` with companion length sub-field `TRNIDINL` (COMP S9(4)) at `app/cpy-bms/COTRN01.CPY:L55`. The BMS definition declares `ATTRB=(FSET,IC,NORM,UNPROT)` at `app/bms/COTRN01.bms:L85-L90` — the `IC` flag marks the initial cursor position at startup.

Plus AID-key dispatch from `app/cbl/COTRN01C.cbl:L112-L132`: ENTER, PF3, PF4, PF5 named keys + WHEN OTHER (covering PF1/PF2/PF6-PF12/CLEAR/PA1/PA2/PA3).

All 13 other display fields on the map (`TRNIDI`, `CARDNUMI`, `TTYPCDI`, `TCATCDI`, `TRNSRCI`, `TRNAMTI`, `TDESCI`, `TORIGDTI`, `TPROCDTI`, `MIDI`, `MNAMEI`, `MCITYI`, `MZIPI`) are **protected output fields** populated from `TRAN-RECORD` after a successful READ (see `app/cbl/COTRN01C.cbl:L178-L190`).

### CICS pseudo-conversational dispatch (4 named keys + WHEN OTHER)

The `EVALUATE EIBAID` block at `app/cbl/COTRN01C.cbl:L112-L132` dispatches each AID key:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        IF CDEMO-FROM-PROGRAM = LOW-VALUES OR SPACES
            MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
        ELSE
            MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
        END-IF
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN DFHPF4
        PERFORM CLEAR-CURRENT-SCREEN
    WHEN DFHPF5
        MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN OTHER
        MOVE 'Y'                       TO WS-ERR-FLG
        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
        PERFORM SEND-TRNVIEW-SCREEN
END-EVALUATE
```

COTRN01C handles EXACTLY 4 named AID keys (ENTER, PF3, PF4, PF5) plus WHEN OTHER. There is no PF7/PF8 handler (no pagination — single-record view) and no PF12 handler. PF3 has a dual destination at `app/cbl/COTRN01C.cbl:L115-L122`: if `CDEMO-FROM-PROGRAM` is blank or low-values, it XCTLs to `COMEN01C`; otherwise it XCTLs to whichever program populated `CDEMO-FROM-PROGRAM`. PF5 always XCTLs `COTRN00C` (return to the transaction-list screen).

### Single-pass READ conversation flow

The conversation flow is single-pass: one READ per user submission, no multi-step validation pipeline, no auto-increment, no STARTBR cursor management.

1. **First reentry pass (no auto-trigger)** at `app/cbl/COTRN01C.cbl:L99-L109`: clears the COTRN1AO output buffer (L101), sets the initial cursor on `TRNIDIN` via `MOVE -1 TO TRNIDINL` (L102), checks the auto-trigger condition (L103-L108), then sends the screen (L109).
2. **Subsequent reentry passes** at `app/cbl/COTRN01C.cbl:L110-L132`: receive the screen, then dispatch on `EIBAID` per the cobol fence in section 3.2 above.
3. **PROCESS-ENTER-KEY** at `app/cbl/COTRN01C.cbl:L144-L192`: validates `TRNIDINI` non-empty (L147-L156); on success, clears 13 output fields (L159-L171), MOVEs `TRNIDINI` to `TRAN-ID` (L172), and PERFORMs `READ-TRANSACT-FILE` (L173); after the READ succeeds, populates the 13 output fields from `TRAN-RECORD` (L177-L190) and PERFORMs `SEND-TRNVIEW-SCREEN` (L191).
4. **READ-TRANSACT-FILE** at `app/cbl/COTRN01C.cbl:L267-L296`: issues a single `EXEC CICS READ` with the `UPDATE` clause at L275; on NORMAL response (L281-L282) CONTINUE; on NOTFND (L283-L288) emits `'Transaction ID NOT found...'`; on WHEN OTHER (L289-L295) emits `'Unable to lookup Transaction...'` AND issues the program's sole DISPLAY at L290.

### Auto-trigger from COTRN00C via CDEMO-CT01-TRN-SELECTED

The first-pass auto-trigger logic at `app/cbl/COTRN01C.cbl:L99-L109` allows COTRN00C (Transaction List) to dispatch directly into a record-view without a user-input step:

```cobol
IF NOT CDEMO-PGM-REENTER
    SET CDEMO-PGM-REENTER TO TRUE
    MOVE LOW-VALUES TO COTRN1AO
    MOVE -1 TO TRNIDINL OF COTRN1AI
    IF CDEMO-CT01-TRN-SELECTED NOT = SPACES
       AND CDEMO-CT01-TRN-SELECTED NOT = LOW-VALUES
       MOVE CDEMO-CT01-TRN-SELECTED TO TRNIDINI OF COTRN1AI
       PERFORM PROCESS-ENTER-KEY
    END-IF
    PERFORM SEND-TRNVIEW-SCREEN
END-IF
```

This branch is taken when a COTRN00C user types `'S'` next to a transaction row; COTRN00C MOVEs the selected row's `TRAN-ID` into `CDEMO-CT01-TRN-SELECTED` and XCTLs to COTRN01C. The auto-trigger path bypasses the interactive `TRNIDIN` entry and directly displays the selected transaction.

The Java translation in `com.blitzy.carddemo.application.transaction.CoTrn01C` MUST preserve this auto-trigger as a first-pass condition that branches to the READ-by-TRAN-ID flow before any user-input phase. Per AAP §0.7.1, this conditional ordering is byte-significant for the resulting BMS_OUTPUT captured in `../expected/bms_output.txt`.

### Verbatim COBOL error messages

The following 3 distinct verbatim message strings MUST be preserved exactly by the Java translation per AAP §0.7.1 (Minimal Change Clause). Preserve the trailing 3-ASCII-period ellipsis `...` (NEVER the Unicode horizontal-ellipsis character at codepoint U+2026). Plus the externally defined `CCDA-MSG-INVALID-KEY` constant referenced from `CSMSG01Y`.

```text
'Tran ID can NOT be empty...'              app/cbl/COTRN01C.cbl:L149
'Transaction ID NOT found...'              app/cbl/COTRN01C.cbl:L285-L286
'Unable to lookup Transaction...'          app/cbl/COTRN01C.cbl:L292-L293
CCDA-MSG-INVALID-KEY (from CSMSG01Y)       referenced at L130
```

COTRN01C emits exactly 3 program-internal verbatim error messages plus the external `CCDA-MSG-INVALID-KEY` constant. This is a much smaller message set than COTRN02C's 33-message catalog, reflecting the simpler single-field READ semantics (no validation pipeline, no auto-increment, no cross-reference lookup, no date validation).

### No CSUTLDTC call (no date input)

COTRN01C does NOT call `CSUTLDTC` (the date validator). `TRAN-ORIG-TS` and `TRAN-PROC-TS` are OUTPUT fields populated from `TRAN-RECORD` after the READ (see `app/cbl/COTRN01C.cbl:L185-L186`) — never user-input — so no validation is required. This is a notable structural difference from COTRN02C (Add Transaction) which calls `CSUTLDTC` twice (once for origination date, once for processing date).

### No CARDXREF AIX lookup

COTRN01C does NOT use the `CARDXREF` alternate index. The user-supplied `TRNIDINI` is matched directly against the `TRAN-ID` primary key in TRANSACT (see `app/cbl/COTRN01C.cbl:L273` for `RIDFLD(TRAN-ID)`). This is a notable structural difference from COTRN02C (which uses the `CARDXREF` AIX to validate card-account relationships) and from `COCRDLIC`/`COCRDSLC` card-detail flows.

## Cross-reference to the Java test class

The Java test class is `com.blitzy.carddemo.tests.golden.CoTrn01CGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoTrn01CGoldenTest.java`. It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The class under test is `com.blitzy.carddemo.application.transaction.CoTrn01C` (note the `transaction` subpackage per AAP §0.4.1). The test class MUST be annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the COBOL-captured artifacts replace the placeholder content in `../expected/`.

Override summary: `programClass()` returns `com.blitzy.carddemo.application.transaction.CoTrn01C.class`; `inputFile()` returns `resolveExpectedOutputPath("cotrn01c", "input_scenario.txt")` routing to `../expected/input_scenario.txt`; `expectedOutputFile()` returns `resolveExpectedOutputPath("cotrn01c", "stdout.txt")` routing to `../expected/stdout.txt`; `auxiliaryInputs()` returns `List.of(resolveExpectedOutputPath("cotrn01c", "transact.txt"))` routing to `../expected/transact.txt`; `expectedOutputs()` returns `ExpectedOutput` entries for `stdout.txt` and `bms_output.txt` (both under `../expected/`). **NO `transact_after.txt` entry** is declared in `expectedOutputs()` — because COTRN01C is effectively READ-only (issues `EXEC CICS READ ... UPDATE` but never `EXEC CICS REWRITE`), the post-run TRANSACT state is byte-identical to the input and no after-fixture is required. All five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper; none routes to this `input/` folder.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COTRN01C) for the CICS COBOL build/run path used to capture expected outputs, per AAP §0.7.5 (the user TODO marker resolution). Until the capture is performed and committed, `../expected/stdout.txt`, `../expected/bms_output.txt`, and `../expected/transact.txt` hold placeholder content and `CoTrn01CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **READ-with-UPDATE-but-no-REWRITE quirk** (`app/cbl/COTRN01C.cbl:L267-L296`, especially the `UPDATE` clause at L275): COTRN01C issues `EXEC CICS READ ... UPDATE ...` acquiring an update-mode lock on the `TRAN-RECORD` but NEVER issues `EXEC CICS REWRITE`. Per AAP §0.7.1 (Minimal Change Clause), this quirk is preserved faithfully in the Java translation and flagged in `java/MIGRATION_NOTES.md`; the post-run TRANSACT file is byte-identical to the input.
- **Single editable BMS input field**: only `TRNIDIN` (16 chars, declared at `app/cpy-bms/COTRN01.CPY:L60` and defined at `app/bms/COTRN01.bms:L85-L90`) is user-editable. All 13 other display fields are output-only (ATTRB=ASKIP) or program-internal.
- **Three verbatim error messages**: `'Tran ID can NOT be empty...'` at `app/cbl/COTRN01C.cbl:L149`, `'Transaction ID NOT found...'` at `app/cbl/COTRN01C.cbl:L285-L286`, and `'Unable to lookup Transaction...'` at `app/cbl/COTRN01C.cbl:L292-L293` — preserved EXACTLY with ASCII `...` ellipsis (3 periods).
- **One active DISPLAY at L290**: `DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` inside `READ-TRANSACT-FILE` under the WHEN OTHER path; NOT reached under the normal NORMAL or NOTFND scenarios. This is the only path that contributes content to `../expected/stdout.txt`.
- **AID-key set**: 4 named (ENTER, PF3, PF4, PF5) + WHEN OTHER, dispatched at `app/cbl/COTRN01C.cbl:L112-L132`. PF3 has a dual destination (`COMEN01C` if `CDEMO-FROM-PROGRAM` is blank or low-values, else `CDEMO-FROM-PROGRAM`). PF5 always XCTLs `COTRN00C`.
- **Auto-trigger from COTRN00C** via `CDEMO-CT01-TRN-SELECTED PIC X(16)` declared at `app/cbl/COTRN01C.cbl:L61` and acted upon at `app/cbl/COTRN01C.cbl:L103-L108` — first-pass-only behavior; subsequent reentries do not re-evaluate the auto-trigger.
- **No CONFIRM dispatch flow** — unlike COTRN02C, COTRN01C has no Y/N confirmation step because it is a read, not a write. There is no `CONFIRM`-field validation paragraph.
- **No CSUTLDTC date validation** — unlike COTRN02C, no user-input date fields exist on the COTRN1A map; `TRAN-ORIG-TS` and `TRAN-PROC-TS` are outputs populated at `app/cbl/COTRN01C.cbl:L185-L186`.
- **No CARDXREF AIX lookup** — unlike COTRN02C, no cross-reference validation; direct primary-key lookup via `RIDFLD(TRAN-ID)` at `app/cbl/COTRN01C.cbl:L273`.
- **No auto-increment TRAN-ID** — unlike COTRN02C, which auto-increments via STARTBR / READPREV; COTRN01C only reads existing records and never writes.
- **PAN masking in display**: per AAP §0.7.2, any `CARDNUMO` value rendered in `../expected/bms_output.txt` after capture MUST be masked (show only the last 4 digits of the 16-digit PAN). The capture script in `java/MIGRATION_NOTES.md` §1.6 enforces this; Java logs MUST NEVER carry an unmasked PAN.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CoTrn01C` MUST carry a `@CobolProgram("COTRN01C")` Javadoc-style annotation citing the original PROGRAM-ID, source path `app/cbl/COTRN01C.cbl`, and translation date.

## Source lineage

- `app/cbl/COTRN01C.cbl` (330 lines) — Transaction-view CICS COBOL program; PROGRAM-ID `COTRN01C` at L23; transaction `CT01` at L37; key paragraphs: MAIN-PARA (L86), PROCESS-ENTER-KEY (L144), RETURN-TO-PREV-SCREEN (L197), SEND-TRNVIEW-SCREEN (L213), RECEIVE-TRNVIEW-SCREEN (L230), POPULATE-HEADER-INFO (L243), READ-TRANSACT-FILE (L267 with the `UPDATE` clause at L275), CLEAR-CURRENT-SCREEN (L301), INITIALIZE-ALL-FIELDS (L309).
- `app/bms/COTRN01.bms` (273 lines) — BMS map definition. MAPSET `COTRN01`, MAP `COTRN1A`, `SIZE=(24,80)`. Sole editable input field `TRNIDIN PIC X(16)` at row 6 column 21 with `ATTRB=(FSET,IC,NORM,UNPROT)` at L85-L90. Header fields `TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`, `TITLE02`, `CURTIME`. Output-only fields `TRNIDO`, `CARDNUMO`, `TTYPCDO`, `TCATCDO`, `TRNSRCO`, `TRNAMTO`, `TDESCO`, `TORIGDTO`, `TPROCDTO`, `MIDO`, `MNAMEO`, `MCITYO`, `MZIPO`, `ERRMSGO`.
- `app/cpy-bms/COTRN01.CPY` (272 lines) — Symbolic map copybook defining `01 COTRN1AI` (input record) and `01 COTRN1AO REDEFINES COTRN1AI` (output record); each visible BMS field expands to 4-5 sub-fields (length L, flag F, attribute A REDEFINES, input I, output O/C/P/H/V).
- `app/cpy/CVTRA05Y.cpy` — `01 TRAN-RECORD` 350-byte fixed-width layout (14 fields including `TRAN-ID PIC X(16)`, `TRAN-AMT PIC S9(09)V99`, `TRAN-CARD-NUM PIC X(16)`, `TRAN-ORIG-TS`/`TRAN-PROC-TS PIC X(26)`, and the `TRAN-MERCHANT-*` group).
- `app/cpy/COCOM01Y.cpy` — `01 CARDDEMO-COMMAREA`. The `CDEMO-CT01-INFO` sub-group containing `CDEMO-CT01-TRNID-FIRST`, `CDEMO-CT01-TRNID-LAST`, `CDEMO-CT01-PAGE-NUM`, `CDEMO-CT01-NEXT-PAGE-FLG`, `CDEMO-CT01-TRN-SEL-FLG`, `CDEMO-CT01-TRN-SELECTED` is appended inline in `app/cbl/COTRN01C.cbl:L53-L61` (immediately after the `COPY COCOM01Y.` directive at L52) — not in the COCOM01Y copybook itself.
- `app/cpy/CSMSG01Y.cpy` — common messages including `CCDA-MSG-INVALID-KEY` (referenced at `app/cbl/COTRN01C.cbl:L130`).

## Authority references

- AAP §0.2.1 (in-scope: `golden/cotrn01c/` directory tree)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 (COTRN01C → `com.blitzy.carddemo.application.transaction.CoTrn01C`)
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` until COBOL capture committed)
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` is UNCHANGED)
- AAP §0.7.1 (Minimal Change Clause; preserve READ-with-UPDATE-but-no-REWRITE quirk; verbatim error messages)
- AAP §0.7.2 (PAN masking in logs and display surfaces; no card-number values appear in clear text)
- AAP §0.7.4 (no preview features; pattern-matching exhaustiveness)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.6.4 (`java.time` mandate for `TRAN-ORIG-TS` / `TRAN-PROC-TS` `LocalDateTime` parsing)
- AAP §0.6.5 (`java.nio.file` mandate for all file I/O)
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal`; virtual-thread fan-out forbidden where ordering is observable)
- AAP §0.6.12 (architectural override: no Spring; plain Java with constructor injection; no PostgreSQL; file-based default)
- Sibling pattern reference: `golden/cotrn02c/input/README.md` (closest CT-series structural sibling — also a documentation-only marker) and `golden/cousr00c/input/README.md` (9-phase pattern template)

## DO NOT add files here

```text
This folder MUST remain documentation-only. Do NOT add fixture data files,
.gitkeep placeholders, input_scenario.txt, transact.txt, or any other content.
The README.md IS the directory's marker.

All fixture data files for the COTRN01C parity test live in the sibling
../expected/ folder because the consuming test class CoTrn01CGoldenTest
resolves every fixture path through resolveExpectedOutputPath("cotrn01c", ...).
Adding files here would create duplicate, stale, or unreachable fixtures.

If a future test scenario requires additional inputs, add the new file to
../expected/ and extend the appropriate override in CoTrn01CGoldenTest.java
(auxiliaryInputs() or a new path-returning method) — not here.
```
