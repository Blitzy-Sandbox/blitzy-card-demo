# COTRN00C (Transaction List, CT00) — Golden-Record `input/` Folder (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files (no `.txt`, no `.jsonl`, no `.bin`, no `.csv`, no `.gitkeep`, no subfolders). COTRN00C is the **transaction-list online CICS pseudo-conversational program** (transaction `CT00`, source `app/cbl/COTRN00C.cbl`, 699 lines total) translated to Java `com.blitzy.carddemo.application.transaction.CoTrn00C` per AAP §0.4.1. COTRN00C is the **navigation entry point** to the transaction-view workflow: row 'S'/'s' selections XCTL to `COTRN01C` with the selected `TRAN-ID` passed via the commarea field `CDEMO-CT00-TRN-SELECTED PIC X(16)` declared at `app/cbl/COTRN00C.cbl:L70`. COTRN00C is **READ-ONLY**: it issues only `STARTBR`, `READNEXT`, `READPREV`, and `ENDBR` against TRANSACT — never `WRITE`, `REWRITE`, or `DELETE` — see `app/cbl/COTRN00C.cbl:L591-L600, L626-L634, L660-L668, L694-L696`. All fixture data files for the COTRN00C parity test live in the sibling `../expected/` folder.

## Why this folder is documentation-only

COTRN00C's WS-VARIABLES at `app/cbl/COTRN00C.cbl:L35-L57` declare the program-name, transaction-id, and TRANSACT dataset literals (`WS-PGMNAME PIC X(08) VALUE 'COTRN00C'` at L36; `WS-TRANID PIC X(04) VALUE 'CT00'` at L37; `WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'` at L39). The COPY directives at `app/cbl/COTRN00C.cbl:L61-L81` pull in `COCOM01Y` (commarea base), `COTRN00` (BMS symbolic map), `COTTL01Y` (screen titles), `CSDAT01Y` (date constants), `CSMSG01Y` (common messages including `CCDA-MSG-INVALID-KEY`), `CVTRA05Y` (350-byte TRAN-RECORD), `DFHAID` (CICS AID constants), and `DFHBMSCA` (BMS attribute constants). The user-facing input arrives via the BMS map `COTRN0A` (single editable `TRNIDIN` search field plus ten `SEL000N` row-selection fields) and AID-key dispatch — no file-based fixtures from `app/data/ASCII/*.txt` are consumed by the Java test directly.

The consuming Java test class `com.blitzy.carddemo.tests.golden.CoTrn00CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) overrides `inputFile()`, `expectedOutputFile()`, `auxiliaryInputs()`, and `expectedOutputs()` to call `resolveExpectedOutputPath("cotrn00c", fileName)`, which mechanically routes every fixture path to `src/test/resources/golden/cotrn00c/expected/`. Therefore NO fixture data files exist in this `input/` folder.

Keeping an empty `input/` folder with only this README preserves the established `<program>/input/` + `<program>/expected/` symmetry from AAP §0.3.1 across all program test fixtures, while making the absence of data files explicit and self-documenting.

The sibling `../expected/` folder contains:

- `../expected/README.md` — Authoritative fixture contract for the COTRN00C golden-record test.
- `../expected/input_scenario.txt` — CICS pseudo-conversation driver script (TEST-OWNED).
- `../expected/transact.txt` — TRANSACT VSAM KSDS initial-state fixture (CAPTURE PLACEHOLDER); 350-byte fixed-width records sorted ascending by `TRAN-ID PIC X(16)`.
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER; contains only the WHEN OTHER error-path DISPLAYs at `app/cbl/COTRN00C.cbl:L613`, `L647`, and `L681` — never reached under normal NORMAL/NOTFND/ENDFILE scenarios).
- `../expected/bms_output.txt` — Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data lives in ../expected/)

### BMS map inputs: TRNIDIN + 10 SEL000N selection fields

The COTRN0A BMS map (defined in `app/bms/COTRN00.bms` and symbolic copybook `app/cpy-bms/COTRN00.CPY`) has the following editable input fields:

- `TRNIDINI` (PIC X(16)) declared at `app/cpy-bms/COTRN00.CPY:L66` — the search/positioning transaction ID. BMS declares it at `app/bms/COTRN00.bms:L95-L99` with `ATTRB=(FSET,NORM,UNPROT)`, `COLOR=GREEN`, `HILIGHT=UNDERLINE`, at position `POS=(6,21)`. Unlike COTRN01C, COTRN00C's `TRNIDIN` does NOT carry the `IC` (initial-cursor) attribute — the program drives cursor placement via `MOVE -1 TO TRNIDINL` on demand.
- `SEL0001I` through `SEL0010I` (PIC X(1) each) at column 3 of rows 10 through 19 — one-character row-selection fields paired with each displayed transaction. ATTRB=(FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE; the first one is at `app/bms/COTRN00.bms:L153-L158`, `POS=(10,3)`. The symbolic input record begins `SEL0001L` at `app/cpy-bms/COTRN00.CPY:L67` and `SEL0001I` at L72.
- AID-key dispatch: ENTER, PF3, PF7, PF8 named keys plus WHEN OTHER.

Per-row read-only display fields populated by the program after STARTBR/READNEXT:

- `TRNID01` - `TRNID10` (16 chars each): transaction ID copied from `TRAN-ID`.
- `TDATE01` - `TDATE10` (8 chars each): date in MM/DD/YY format derived from `TRAN-ORIG-TS` via `WS-CURDATE` substring slicing at `app/cbl/COTRN00C.cbl:L383-L388`.
- `TDESC01` - `TDESC10` (26 chars each): from `TRAN-DESC` (the symbolic display window is 26 characters; the full `TRAN-DESC` field in TRAN-RECORD is larger and is truncated on assignment).
- `TAMT001` - `TAMT010` (12 chars each): edited amount from `TRAN-AMT` via `WS-TRAN-AMT PIC +99999999.99` (declared at `app/cbl/COTRN00C.cbl:L56`).
- `PAGENUM` (8 chars numeric) at `app/cpy-bms/COTRN00.CPY:L60`: current page number from `CDEMO-CT00-PAGE-NUM`.

The `ERRMSG` field (PIC X(78) at row 23) receives the message text from `WS-MESSAGE`. **CRITICAL invariant: NO PASSWD field anywhere on the COTRN0A map** — and NO PAN field. The map displays `TRAN-ID`, date, description, and amount only; it does NOT display `TRAN-CARD-NUM`. This is a structural privacy property of the list view.

### CICS pseudo-conversational dispatch (4 named keys + WHEN OTHER)

The `EVALUATE EIBAID` block at `app/cbl/COTRN00C.cbl:L119-L134` dispatches each AID key:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN DFHPF7
        PERFORM PROCESS-PF7-KEY
    WHEN DFHPF8
        PERFORM PROCESS-PF8-KEY
    WHEN OTHER
        MOVE 'Y'                       TO WS-ERR-FLG
        MOVE -1       TO TRNIDINL OF COTRN0AI
        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
        PERFORM SEND-TRNLST-SCREEN
END-EVALUATE
```

COTRN00C handles EXACTLY 4 named AID keys (ENTER, PF3, PF7, PF8) plus WHEN OTHER. PF3 destination is `COMEN01C` (Main Menu) at `app/cbl/COTRN00C.cbl:L123` — NOT `COSGN00C`; `COSGN00C` is reached only when `EIBCALEN = 0` at first-time entry per `app/cbl/COTRN00C.cbl:L107-L109`. PF7 and PF8 drive backward and forward pagination respectively.

### READ-ONLY browse-with-pagination flow

1. **First reentry pass** at `app/cbl/COTRN00C.cbl:L112-L116`: when `NOT CDEMO-PGM-REENTER`, the program SETs the reentry flag, clears the COTRN0AO output buffer (MOVE LOW-VALUES), performs `PROCESS-ENTER-KEY` (which falls through to `PROCESS-PAGE-FORWARD` at L225), and sends the screen showing page 1.
2. **Subsequent reentry passes** at `app/cbl/COTRN00C.cbl:L117-L134`: PERFORMs `RECEIVE-TRNLST-SCREEN`, then dispatches on `EIBAID` per the cobol fence above.
3. **PROCESS-ENTER-KEY** at `app/cbl/COTRN00C.cbl:L146-L229`: scans the 10 `SEL000NI` fields (L148-L182); if any is populated, captures the selection character and the corresponding `TRNID0NI` into `CDEMO-CT00-TRN-SEL-FLG` / `CDEMO-CT00-TRN-SELECTED`, then EVALUATEs the selection character — 'S' or 's' (case-insensitive at L186-L187) triggers `EXEC CICS XCTL PROGRAM(COTRN01C) COMMAREA(CARDDEMO-COMMAREA)` at L188-L195; WHEN OTHER emits `'Invalid selection. Valid value is S'` at L199. If no selection (refresh), validates `TRNIDINI` numeric format at L208-L218 and PERFORMs `PROCESS-PAGE-FORWARD` starting from page 0 at L224-L225.
4. **PROCESS-PF7-KEY (backward)** at `app/cbl/COTRN00C.cbl:L234-L252`: positions on `CDEMO-CT00-TRNID-FIRST` (L236-L240); if `CDEMO-CT00-PAGE-NUM > 1` performs `PROCESS-PAGE-BACKWARD`; else emits boundary message `'You are already at the top of the page...'` at L248-L249 and sets `SEND-ERASE-NO`.
5. **PROCESS-PF8-KEY (forward)** at `app/cbl/COTRN00C.cbl:L257-L274`: positions on `CDEMO-CT00-TRNID-LAST` (L259-L263); if `NEXT-PAGE-YES` performs `PROCESS-PAGE-FORWARD`; else emits boundary message `'You are already at the bottom of the page...'` at L270-L271 and sets `SEND-ERASE-NO`.
6. **PROCESS-PAGE-FORWARD / PROCESS-PAGE-BACKWARD** at `app/cbl/COTRN00C.cbl:L279-L328` / `L333-L376`: ten-records-per-page browse with `WS-IDX` iterating 1→10 (forward) or 10→1 (backward); a lookahead READ at L308 (forward) / L360 (backward) sets `NEXT-PAGE-YES`/`NEXT-PAGE-NO` and adjusts `CDEMO-CT00-PAGE-NUM` accordingly.

### Conceptual fixture data (lives in ../expected/)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|--------------------|-----------------|------------------|---------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script: initial display, PF8 forward across pages, PF7 backward, row 'S' XCTL, invalid selection, `TRNIDIN` positioning, PF3 back to `COMEN01C` |
| `transact.txt` | `../expected/transact.txt` | Test-owned scaffolding (synthesized) | 350-byte fixed-width TRANSACT initial-state records sorted ascending by `TRAN-ID PIC X(16)`; per `app/cpy/CVTRA05Y.cpy` layout. NO COPY is made from `app/data/ASCII/` per AAP §0.4.1 |

The upstream ASCII fixture source `app/data/ASCII/dailytran.txt` (and any TRANSACT-shaped fixture present alongside it) is REFERENCE-ONLY per AAP §0.4.1 — it is consumed by the upstream COBOL capture procedure documented in `java/MIGRATION_NOTES.md` §1.6, not by the Java test directly.

## Cross-reference to the Java test class

The Java test class is `com.blitzy.carddemo.tests.golden.CoTrn00CGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoTrn00CGoldenTest.java`. It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The class under test is `com.blitzy.carddemo.application.transaction.CoTrn00C` (note the `transaction` subpackage per AAP §0.4.1). The test class MUST be annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until COBOL captures are committed.

Override summary (all five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper; none routes to this `input/` folder):

- `programClass()` returns `com.blitzy.carddemo.application.transaction.CoTrn00C.class`.
- `inputFile()` returns `resolveExpectedOutputPath("cotrn00c", "input_scenario.txt")` routing to `../expected/input_scenario.txt`.
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cotrn00c", "stdout.txt")` routing to `../expected/stdout.txt`.
- `auxiliaryInputs()` returns `List.of(resolveExpectedOutputPath("cotrn00c", "transact.txt"))` routing to `../expected/transact.txt`.
- `expectedOutputs()` returns entries for `stdout.txt` and `bms_output.txt` (both under `../expected/`).

**NO `transact_after.txt` entry** is declared in `expectedOutputs()`. Because COTRN00C is READ-ONLY (`STARTBR`/`READNEXT`/`READPREV`/`ENDBR` only, no `WRITE`/`REWRITE`/`DELETE` per `app/cbl/COTRN00C.cbl:L591-L600, L626-L634, L660-L668, L694-L696`), the post-run TRANSACT state is byte-identical to the input and no after-fixture is required.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COTRN00C) for the CICS COBOL build/run path used to capture expected outputs, per AAP §0.7.5 (the user TODO marker resolution). The capture procedure exercises the deterministic scenario script in `../expected/input_scenario.txt`, runs it against the COBOL `COTRN00C` program under CICS, and records the resulting BMS SEND MAP output buffer and any DISPLAY output to `../expected/bms_output.txt` and `../expected/stdout.txt` respectively.

Until the capture is performed and committed, `../expected/stdout.txt`, `../expected/bms_output.txt`, and `../expected/transact.txt` hold placeholder content and `CoTrn00CGoldenTest` is `@Disabled` per AAP §0.6.11. Once the capture is performed and the placeholder content is replaced with the real COBOL-captured artifacts, the `@Disabled` annotation MUST be removed in the same commit; this guarantees the byte-for-byte parity gate per AAP §0.1.1 activates only when the reference artifacts are authoritative.

## Behavioral invariants preserved by this fixture

- **READ-ONLY browse semantics** (`app/cbl/COTRN00C.cbl:L591-L600, L626-L634, L660-L668, L694-L696`): COTRN00C uses only `STARTBR` / `READNEXT` / `READPREV` / `ENDBR`; it never issues `WRITE`, `REWRITE`, or `DELETE`. The TRANSACT file content is byte-identical before and after every scenario.
- **10-rows-per-page pagination**: `PROCESS-PAGE-FORWARD` at `app/cbl/COTRN00C.cbl:L279-L328` iterates `WS-IDX` from 1 through 10; `PROCESS-PAGE-BACKWARD` at `app/cbl/COTRN00C.cbl:L333-L376` iterates from 10 down to 1.
- **Page-boundary lookahead**: After populating row 10, an additional `READNEXT` at `app/cbl/COTRN00C.cbl:L308` determines `NEXT-PAGE-YES` vs `NEXT-PAGE-NO` at L309-L313 (forward); a symmetric `READPREV` lookahead at L360 with page-number decrement at L362-L367 governs the backward direction.
- **STARTBR without GTEQ** (`app/cbl/COTRN00C.cbl:L597` where `GTEQ` is COMMENTED OUT): `STARTBR` requires an exact key match. If `TRNIDINI` is a numeric but non-existent transaction ID, `STARTBR` returns `DFHRESP(NOTFND)`, triggering the `'You are at the top of the page...'` path at L608-L609. Preserved verbatim per AAP §0.7.1 — the Java translation MUST NOT "fix" this by adding GTEQ-equivalent semantics.
- **Date display format MM/DD/YY**: `WS-TRAN-DATE PIC X(08) VALUE '00/00/00'` (`app/cbl/COTRN00C.cbl:L57`); populated by `POPULATE-TRAN-DATA` at L383-L388 from `TRAN-ORIG-TS` via `WS-CURDATE` substring slicing.
- **Amount edit format**: `WS-TRAN-AMT PIC +99999999.99` at `app/cbl/COTRN00C.cbl:L56` — signed sentinel plus 8 digits plus decimal point plus 2 cents (12 characters total).
- **VERBATIM error messages with trailing 3-ASCII-period ellipses preserved EXACTLY** (per AAP §0.7.1):
  - `app/cbl/COTRN00C.cbl:L132` `CCDA-MSG-INVALID-KEY` (external constant from `app/cpy/CSMSG01Y.cpy`, COPY at L76) — emitted for the WHEN OTHER AID-key path
  - `app/cbl/COTRN00C.cbl:L199` `'Invalid selection. Valid value is S'` (NO trailing ellipsis)
  - `app/cbl/COTRN00C.cbl:L214` `'Tran ID must be Numeric ...'` (note: SPACE before `...`)
  - `app/cbl/COTRN00C.cbl:L248-L249` `'You are already at the top of the page...'`
  - `app/cbl/COTRN00C.cbl:L270-L271` `'You are already at the bottom of the page...'`
  - `app/cbl/COTRN00C.cbl:L608-L609` `'You are at the top of the page...'` (STARTBR `DFHRESP(NOTFND)`)
  - `app/cbl/COTRN00C.cbl:L615-L616` `'Unable to lookup transaction...'` (STARTBR WHEN OTHER)
  - `app/cbl/COTRN00C.cbl:L642-L643` `'You have reached the bottom of the page...'` (READNEXT `DFHRESP(ENDFILE)`)
  - `app/cbl/COTRN00C.cbl:L649-L650` `'Unable to lookup transaction...'` (READNEXT WHEN OTHER)
  - `app/cbl/COTRN00C.cbl:L676-L677` `'You have reached the top of the page...'` (READPREV `DFHRESP(ENDFILE)`)
  - `app/cbl/COTRN00C.cbl:L683-L684` `'Unable to lookup transaction...'` (READPREV WHEN OTHER)
- **NO Unicode ellipsis** anywhere in the Java translation or fixtures — only 3 ASCII periods `...` per AAP §0.7.4 forbidden content. The L214 message uniquely carries a SPACE between `Numeric` and `...`; this spacing is byte-significant and MUST be preserved.
- **Case-insensitive 'S'/'s' row selection** at `app/cbl/COTRN00C.cbl:L186-L187`: both `'S'` and `'s'` trigger XCTL to `COTRN01C`. The Java translation MUST mirror this exact behavior — both case variants accepted.
- **CDEMO-CT00-INFO commarea is inline-appended** to `CARDDEMO-COMMAREA` at `app/cbl/COTRN00C.cbl:L62-L70` (NOT defined in `app/cpy/COCOM01Y.cpy`); the appended sub-record contains six fields:
  - `CDEMO-CT00-TRNID-FIRST PIC X(16)` at L63 — first TRAN-ID on the currently displayed page (for PF7 backward positioning).
  - `CDEMO-CT00-TRNID-LAST PIC X(16)` at L64 — last TRAN-ID on the currently displayed page (for PF8 forward positioning).
  - `CDEMO-CT00-PAGE-NUM PIC 9(08)` at L65 — current page number, displayed in the `PAGENUM` BMS field.
  - `CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'` at L66-L68 with 88-levels `NEXT-PAGE-YES VALUE 'Y'` and `NEXT-PAGE-NO VALUE 'N'`.
  - `CDEMO-CT00-TRN-SEL-FLG PIC X(01)` at L69 — captured selection character from the chosen `SEL000NI` field.
  - `CDEMO-CT00-TRN-SELECTED PIC X(16)` at L70 — captured TRAN-ID from the corresponding `TRNID0NI` field; consumed by COTRN01C as the auto-display target on XCTL.

  Java records MUST reflect this inline placement to maintain commarea byte layout per AAP §0.7.1.
- **AID-key set restricted to 4 named keys** (`app/cbl/COTRN00C.cbl:L119-L134`): ENTER, PF3, PF7, PF8. Any other AID key (PF1, PF2, PF4, PF5, PF6, PF9-PF12, CLEAR, PA1, PA2) falls through to WHEN OTHER and emits `CCDA-MSG-INVALID-KEY`. The Java translation's pattern-matching switch MUST be exhaustive over the same set per AAP §0.7.4 — and MUST NOT carry a fallback branch.
- **PF3 destination is COMEN01C, not COSGN00C** (`app/cbl/COTRN00C.cbl:L122-L124`): PF3 always returns to the Main Menu. `COSGN00C` is reached only via the `EIBCALEN = 0` first-time-entry path at L107-L109. (Note distinction from COUSR00C, where PF3 returns to the admin menu `COADM01C`.)
- **TRNIDIN must be numeric when present** (`app/cbl/COTRN00C.cbl:L208-L218`): if non-blank, must satisfy the COBOL `IS NUMERIC` test; otherwise the program emits `'Tran ID must be Numeric ...'` at L214. Preserve the SPACE-before-ellipsis character sequence exactly.
- **Conditional READNEXT-skip-first / READPREV-skip-first**: PROCESS-PAGE-FORWARD at `app/cbl/COTRN00C.cbl:L285-L287` issues an extra `READNEXT` when `EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3` (i.e., on PF8 forward continuation), which skips past the position record so the page begins with the next record. The symmetric backward case at L339-L341 issues an extra `READPREV` when `EIBAID NOT = DFHENTER AND DFHPF8`. This conditional is byte-significant for pagination boundaries.
- **WS-SEND-ERASE-FLG state machine** (`app/cbl/COTRN00C.cbl:L46-L48`): the default `SEND-ERASE-YES` causes `EXEC CICS SEND MAP` to use the `ERASE` option (full screen redraw). The boundary-message paths at L250 (PF7 top boundary) and L272 (PF8 bottom boundary) `SET SEND-ERASE-NO TO TRUE` so that the previously displayed transaction list remains visible while the boundary message is shown. The Java translation MUST preserve this conditional ERASE behavior.
- **Initial-state setup at `app/cbl/COTRN00C.cbl:L97-L100`**: the first four statements of `MAIN-PARA` are `SET ERR-FLG-OFF TO TRUE`, `SET TRANSACT-NOT-EOF TO TRUE`, `SET NEXT-PAGE-NO TO TRUE`, `SET SEND-ERASE-YES TO TRUE`. These initial flag states are byte-significant for the dispatch logic in all subsequent paragraphs.
- **PERFORM UNTIL termination conditions**: PROCESS-PAGE-FORWARD loop at L297 terminates on `WS-IDX >= 11 OR TRANSACT-EOF OR ERR-FLG-ON`; PROCESS-PAGE-BACKWARD loop at L351 terminates on `WS-IDX <= 0 OR TRANSACT-EOF OR ERR-FLG-ON`. The three-condition disjunction MUST be preserved exactly in the Java translation's loop guard.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CoTrn00C` MUST carry a `@CobolProgram("COTRN00C")` Javadoc-style annotation citing the original PROGRAM-ID, source path `app/cbl/COTRN00C.cbl`, and translation date.
- **Three indistinguishable `'Unable to lookup transaction...'` messages**: the same string literal appears at three distinct source locations (`app/cbl/COTRN00C.cbl:L615-L616`, `L649-L650`, `L683-L684`), one per WHEN OTHER branch of STARTBR, READNEXT, and READPREV respectively. The Java translation MUST emit the identical string at all three sites; the originating CICS verb cannot be inferred from the message text alone, only from the surrounding control flow.

## Source lineage

- `app/cbl/COTRN00C.cbl` (699 lines) — Transaction-list CICS COBOL program. `PROGRAM-ID COTRN00C` at L23; transaction `CT00` at L37; key paragraphs: `MAIN-PARA` (L95), `PROCESS-ENTER-KEY` (L146), `PROCESS-PF7-KEY` (L234), `PROCESS-PF8-KEY` (L257), `PROCESS-PAGE-FORWARD` (L279), `PROCESS-PAGE-BACKWARD` (L333), `POPULATE-TRAN-DATA` (L381), `INITIALIZE-TRAN-DATA` (L450), `RETURN-TO-PREV-SCREEN` (L510), `SEND-TRNLST-SCREEN` (L527), `RECEIVE-TRNLST-SCREEN` (L554), `POPULATE-HEADER-INFO` (L567), `STARTBR-TRANSACT-FILE` (L591), `READNEXT-TRANSACT-FILE` (L624), `READPREV-TRANSACT-FILE` (L658), `ENDBR-TRANSACT-FILE` (L692).
- `app/bms/COTRN00.bms` (464 lines) — BMS map definition. MAPSET `COTRN00`, MAP `COTRN0A`, `SIZE=(24,80)`. Editable input fields: `TRNIDIN` at `(6,21)` LENGTH=16, `SEL0001`-`SEL0010` at column 3 of rows 10-19 LENGTH=1 each. Display fields `TRNID01`-`TRNID10`, `TDATE01`-`TDATE10`, `TDESC01`-`TDESC10`, `TAMT001`-`TAMT010`, `PAGENUM`, plus header fields `TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`, `TITLE02`, `CURTIME`, `ERRMSG`.
- `app/cpy-bms/COTRN00.CPY` (728 lines) — Symbolic map copybook defining `01 COTRN0AI` (input record at L17) and `01 COTRN0AO REDEFINES COTRN0AI` (output record); each visible BMS field expands to companion length L, flag F, attribute A REDEFINES, input I, output O/C/P/H/V.
- `app/cpy/CVTRA05Y.cpy` — `01 TRAN-RECORD` 350-byte fixed-width layout (14 fields including `TRAN-ID PIC X(16)`, `TRAN-AMT PIC S9(09)V99`, `TRAN-DESC`, `TRAN-CARD-NUM PIC X(16)`, `TRAN-ORIG-TS PIC X(26)`, `TRAN-PROC-TS PIC X(26)`).
- `app/cpy/COCOM01Y.cpy` — `01 CARDDEMO-COMMAREA` base structure. The `CDEMO-CT00-INFO` subgroup is NOT in this copybook; it is appended INLINE in `app/cbl/COTRN00C.cbl:L62-L70` immediately after the `COPY COCOM01Y.` directive at L61.
- `app/cpy/COTTL01Y.cpy` — Title constants (copied at `app/cbl/COTRN00C.cbl:L74`).
- `app/cpy/CSDAT01Y.cpy` — Date/time work area (copied at `app/cbl/COTRN00C.cbl:L75`).
- `app/cpy/CSMSG01Y.cpy` — Common messages including `CCDA-MSG-INVALID-KEY` (copied at `app/cbl/COTRN00C.cbl:L76`; referenced at L132).
- `app/data/ASCII/dailytran.txt` — Upstream ASCII fixture source. REFERENCE-ONLY per AAP §0.4.1; NOT COPIED into this fixture. Consumed by the upstream COBOL capture procedure, not by the Java test directly.

The COBOL COPY statements also reference two CICS-supplied copybooks at `app/cbl/COTRN00C.cbl:L80-L81` (`DFHAID` and `DFHBMSCA`) which are not part of the application source tree. The Java translation replaces `DFHAID` constants (DFHENTER, DFHPF3, DFHPF7, DFHPF8, etc.) with a sealed `AidKey` hierarchy per AAP §0.6.10, and replaces `DFHBMSCA` BMS attribute constants with static helpers in the `ScreenAttributeSetter` utility per AAP §0.4.1.

## Authority references

- AAP §0.1.1 (refactoring objective — preserve byte-for-byte fidelity)
- AAP §0.2.1 (in-scope: `golden/cotrn00c/` directory tree as part of `java/carddemo-tests/src/test/resources/golden/**` wildcard)
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference implementation)
- AAP §0.3.1 (harness directory layout: `<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 (transformation plan — TRANSACT fixture REFERENCED from `app/data/ASCII/` via classpath, NOT COPIED)
- AAP §0.6.1 (decimal arithmetic fidelity — `TRAN-AMT PIC S9(09)V99` translates to `BigDecimal` with explicit scale and `RoundingMode`)
- AAP §0.6.4 (date semantics — `TRAN-ORIG-TS PIC X(26)` translates to `java.time.LocalDateTime`; no legacy date types in new code)
- AAP §0.6.5 (file I/O exactness — byte-for-byte; `java.nio.file` only)
- AAP §0.6.6 (batch throughput — virtual-thread fan-out preserves sort order; `ScopedValue` replaces ambient context)
- AAP §0.6.10 (sealed-type hierarchies — `AidKey` permits Enter, Clear, Pa1, Pa2, PfKey01..PfKey12 replaces `DFHAID` constants)
- AAP §0.6.11 (golden-record harness design; `@Disabled` until COBOL captures committed)
- AAP §0.6.12 (architectural override — no Spring; plain Java with constructor injection; file-based default)
- AAP §0.7.1 (Minimal Change Clause; preserve READ-ONLY browse semantics; verbatim error messages with trailing `...`)
- AAP §0.7.4 (forbidden features — no preview features; pattern-matching switch must be exhaustive)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- Sibling pattern reference: `golden/cousr00c/input/README.md` and `golden/cotrn01c/input/README.md` (9-phase pattern template established across sibling fixtures)

## DO NOT add files here

> Adding any fixture data file to this directory will desynchronize it from the consuming Java test class `CoTrn00CGoldenTest`, which routes ALL fixture paths through `resolveExpectedOutputPath("cotrn00c", ...)` to `../expected/`. The `expected/` folder is the SINGLE source of truth for COTRN00C fixture content. If a future test scenario requires additional inputs, add the new file to `../expected/` and extend the appropriate override in `CoTrn00CGoldenTest.java` (`auxiliaryInputs()` or a new path-returning method) — not here.
