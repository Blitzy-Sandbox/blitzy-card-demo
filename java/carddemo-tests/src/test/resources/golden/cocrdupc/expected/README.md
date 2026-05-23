# COCRDUPC Golden-Record Fixtures — expected/

This folder holds the **expected outputs** and the **synthesized
scenario contract** for the COCRDUPC (Card Update; CICS transaction
`CCUP`) golden-record parity test. The test class FQCN is
`com.blitzy.carddemo.tests.golden.CoCrdUpCGoldenTest` and remains
`@Disabled` until ALL four data files documented below
(`input_scenario.txt`, `carddata.txt`, `stdout.txt`,
`bms_output.txt`) are committed with non-placeholder content per
AAP §0.6.11.

COCRDUPC is an online CICS pseudo-conversational program executing a
**multi-pass UPDATE flow**:

- **Pass 1** — `ENTER` with `ACCTSID + CARDSID` → `9000-READ-DATA`
  → `EXEC CICS READ FILE(CARDDAT)` by `CARD-NUM` → state transitions
  to `CCUP-SHOW-DETAILS`; INFOMSG = `'Details of selected card shown above'`.
- **Pass 2** — `ENTER` with `ACCTSID + CARDSID + CRDNAME + CRDSTCD +
  EXPMON + EXPYEAR` (plus the hidden `EXPDAY` echo) →
  `1200-EDIT-MAP-INPUTS` runs all validations → if all pass and at
  least one field differs from the OLD snapshot, state transitions
  to `CCUP-CHANGES-OK-NOT-CONFIRMED`; INFOMSG =
  `'Changes validated.Press F5 to save'`.
- **Pass 3** — `PF5` → `9200-WRITE-PROCESSING` →
  `EXEC CICS READ ... UPDATE` acquires lock →
  `9300-CHECK-CHANGE-IN-REC` compares six fields against the
  snapshot (optimistic concurrency) → if unchanged,
  `EXEC CICS REWRITE` rewrites the slot in place; state transitions
  to `CCUP-CHANGES-OKAYED-AND-DONE`; INFOMSG =
  `'Changes committed to database'`.

The conventional `<program>/input/` and `<program>/expected/` split
is followed; the sibling `../input/` folder contains only a marker
README explaining its intentional emptiness. The deterministic
scenario contract `input_scenario.txt` lives here under `expected/`
because BOTH the COBOL CICS reference run AND the Java translation
under test consume it — both sides must agree on the input
vocabulary in order for byte-for-byte parity to be meaningful.

## Files in this folder

### `README.md` (this file)

This document. Authoritative contract for the COCRDUPC golden-record
fixture. The ONLY direct child file committed in the initial PR; the
four data files documented below are committed in a subsequent PR
alongside the COBOL capture and the removal of `@Disabled` from
`CoCrdUpCGoldenTest`.

### `input_scenario.txt` — Synthesized CICS Pseudo-Conversation Script

- The **deterministic test scenario contract** consumed by
  `CoCrdUpCGoldenTest.inputFile()` via
  `resolveExpectedOutputPath("cocrdupc", "input_scenario.txt")`.
- Test-owned scaffolding — NOT a COBOL capture. It lives here under
  `expected/` (NOT under `../input/`) because BOTH the COBOL CICS
  baseline run AND the Java translation are driven by this file.
- **Format**: one CICS pseudo-conversation submission per line, each
  carrying 10 whitespace-separated fields:
  - Field 1: AID key name (`ENTER`, `PF3`, `PF5`, `PF12`; the only
    permitted set per `app/cbl/COCRDUPC.cbl:L413-L424`).
  - Field 2: 11-char `ACCTSID` (right-padded with spaces).
  - Field 3: 16-char `CARDSID` (right-padded with spaces).
  - Field 4: 50-char `CRDNAMEI` (right-padded with spaces;
    underscore `_` MAY denote a literal space).
  - Field 5: 1-char `CRDSTCDI` (`Y`, `N`, or space).
  - Field 6: 2-char `EXPMONI` (`01`-`12` or space).
  - Field 7: 4-char `EXPYEARI` (`1950`-`2099` or space).
  - Field 8: 2-char `EXPDAYI` (hidden echo from prior screen-buffer
    round-trip; `01`-`31` or space).
  - Field 9: Caller `CDEMO-FROM-PROGRAM` (e.g., `COCRDLIC` for the
    pre-load branch, `COMEN01C` for fresh entry; 8 chars).
  - Field 10: Expected outcome label.
- Lines beginning with `#` are comments (ignored). Blank lines also
  ignored.

### `carddata.txt` — Expected CARDDAT State After Scenario REWRITE

- Consumed by `CoCrdUpCGoldenTest.expectedOutputs()` as the expected
  post-REWRITE CARDDAT content. The harness asserts byte-for-byte
  equality against the actual file produced by
  `FileCardRepository.update(record)`.
- Reflects **in-place REWRITE semantics**: the updated record
  occupies the same 150-byte slot as before; subsequent records are
  NOT shifted; the total file byte length is unchanged.
- **Same total byte length as `app/data/ASCII/carddata.txt`** (50 x
  150 = 7500 bytes of records, plus the line-terminator convention
  preserved from the source fixture). Only the affected slot(s)
  differ.
- For scenarios that do NOT execute a REWRITE (PF12 cancel, invalid
  input producing no REWRITE, READ `NOTFND`, no-change-detected
  short-circuit), `carddata.txt` is byte-identical to the initial
  CARDDAT state loaded from `app/data/ASCII/carddata.txt`. If the
  harness exercises multiple scenarios in a single run,
  `carddata.txt` may be split into per-scenario expected files; the
  chosen approach is documented in `java/MIGRATION_NOTES.md` §1.6.

### `stdout.txt` — Captured Java SLF4J / COBOL DISPLAY Output

- Consumed by `CoCrdUpCGoldenTest.expectedOutputFile()` (the primary
  stdout-comparison target inherited from `GoldenRecordTest`).
- Captures SLF4J INFO output produced by the Java `CoCrdUpC`
  execution corresponding to COBOL DISPLAY emissions plus harness
  diagnostics.
- **PAN MASKING INVARIANT (AAP §0.7.2)**: any emission that includes
  `CARD-NUM` MUST mask all but the last 4 digits. Mask format:
  `'************<4digits>'` (12 asterisks followed by the last 4
  digits of the 16-digit PAN). This invariant is enforced as a
  secondary check BEYOND byte-for-byte parity: the harness scans
  `stdout.txt` for any 16-digit numeric substring matching any card
  PAN in `app/data/ASCII/carddata.txt` and fails the test if found.
- COCRDUPC contains DISPLAYs primarily in error-status paths (the
  `WS-FILE-ERROR-MESSAGE` template construction); under the
  documented test scenarios these fire only on unexpected response
  codes. "Happy path" content is largely harness diagnostics.
- Trailing newlines and line-ending convention captured exactly (LF
  only, ASCII-mode capture).

### `bms_output.txt` — Serialized BMS SEND MAP Output

- Consumed by `CoCrdUpCGoldenTest.expectedOutputs()` as the expected
  screen-state target.
- Captures the `CCRDUPAO` output map representations for each
  scenario submission, serialized per the format documented in
  `java/MIGRATION_NOTES.md` §1.6.
- Per-scenario block format: one block per `EXEC CICS SEND MAP`
  invocation, containing the 24x80 character buffer with field
  attributes encoded inline. The serialization MUST be deterministic
  so that BOTH the COBOL reference run AND the Java
  `CoCrdUpOutput.encode()` produce identical bytes.
- **EXPDAY byte preservation**: the `DRK,FSET,PROT` attribute on the
  `EXPDAY` field at (15,36) length=2 hides the bytes from the 3270
  terminal RENDERER, but the bytes ARE present in the screen buffer.
  The Java `CoCrdUpOutput.encode()` MUST emit them. The day-of-month
  bytes round-trip from `CARDDAT` through `CCUP-OLD-EXPDAY` through
  the BMS output and back to `CCUP-NEW-EXPDAY` per
  `app/cbl/COCRDUPC.cbl:L621,L1365-L1366`.
- **PAN bytes IN `bms_output.txt`**: the `CARDSID` field at (8,45)
  length=16 contains the full PAN. Byte-for-byte parity demands the
  bytes be preserved. The BMS screen buffer is a CICS surface, NOT
  a log surface — PAN masking applies to `stdout.txt` only. Do NOT
  mask `CARDSID` bytes in `bms_output.txt`.
- Color and attribute byte preservation: BLUE / YELLOW / TURQUOISE /
  NEUTRAL / RED BRT / DRK attribute changes (notably the dynamic
  `INFOMSGC` brightening when an info message is present, `FKEYSC`
  brightening to BRT when `PROMPT-FOR-CONFIRMATION` is active, and
  `ERRMSGC` toggling between RED BRT and field-asterisk-on-BLANK)
  MUST be reproduced byte-for-byte.

## CCUP-CHANGE-ACTION 7-state machine

The screen-data state machine is declared verbatim at
`app/cbl/COCRDUPC.cbl:L274-L290`:

```cobol
01 WS-THIS-PROGCOMMAREA.
   05 CARD-UPDATE-SCREEN-DATA.
      10 CCUP-CHANGE-ACTION             PIC X(1) VALUE LOW-VALUES.
         88 CCUP-DETAILS-NOT-FETCHED       VALUES LOW-VALUES, SPACES.
         88 CCUP-SHOW-DETAILS              VALUE 'S'.
         88 CCUP-CHANGES-MADE              VALUES 'E', 'N', 'C', 'L', 'F'.
         88 CCUP-CHANGES-NOT-OK            VALUE 'E'.
         88 CCUP-CHANGES-OK-NOT-CONFIRMED  VALUE 'N'.
         88 CCUP-CHANGES-OKAYED-AND-DONE   VALUE 'C'.
         88 CCUP-CHANGES-FAILED            VALUES 'L', 'F'.
         88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'.
         88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'.
```

AID-key gating at `app/cbl/COCRDUPC.cbl:L413-L424` permits only:
`ENTER` (always); `PF3` (always); `PF5` only when
`CCUP-CHANGES-OK-NOT-CONFIRMED` (after validated Pass 2); `PF12`
only when NOT `CCUP-DETAILS-NOT-FETCHED` (after successful initial
READ). Any other AID is silently coerced to `ENTER`.

State transitions:

| Current State | Inducing Action | Next State |
|---|---|---|
| DETAILS-NOT-FETCHED (LOW-VALUES/SPACES) | PFK03 | XCTL to `CDEMO-FROM-PROGRAM` or `COMEN01C` |
| DETAILS-NOT-FETCHED | ENTER with valid ACCT+CARD; READ NORMAL | SHOW-DETAILS |
| DETAILS-NOT-FETCHED | ENTER with invalid input | CHANGES-NOT-OK (input error displayed) |
| DETAILS-NOT-FETCHED | ENTER with READ NOTFND | DETAILS-NOT-FETCHED (ERRMSG = `'Did not find cards for this search condition'`) |
| SHOW-DETAILS ('S') | ENTER with valid edits | CHANGES-OK-NOT-CONFIRMED |
| SHOW-DETAILS | ENTER with invalid edits | CHANGES-NOT-OK |
| SHOW-DETAILS | ENTER with no changes | SHOW-DETAILS (NO-CHANGES-DETECTED) |
| CHANGES-OK-NOT-CONFIRMED ('N') | PFK05 + REWRITE NORMAL | CHANGES-OKAYED-AND-DONE ('C') |
| CHANGES-OK-NOT-CONFIRMED | PFK05 + lock failure | CHANGES-OKAYED-LOCK-ERROR ('L') |
| CHANGES-OK-NOT-CONFIRMED | PFK05 + REWRITE failure | CHANGES-OKAYED-BUT-FAILED ('F') |
| CHANGES-OK-NOT-CONFIRMED | PFK05 + 9300 detects external change | SHOW-DETAILS (re-display refreshed snapshot) |
| CHANGES-OK-NOT-CONFIRMED | PFK12 (cancel) | DETAILS-NOT-FETCHED (after post-save reset) |
| CHANGES-NOT-OK ('E') | ENTER with corrected edits | CHANGES-OK-NOT-CONFIRMED (or CHANGES-NOT-OK if still bad) |
| CHANGES-OKAYED-AND-DONE | branch-4 dispatch | DETAILS-NOT-FETCHED (reset to fresh entry) |
| CHANGES-FAILED ('L' or 'F') | branch-4 dispatch | DETAILS-NOT-FETCHED (reset to fresh entry) |

## Required test scenarios

`input_scenario.txt` MUST exercise, at minimum, the following six
scenarios. The capture procedure documented in
`java/MIGRATION_NOTES.md` §1.6 may add further scenarios provided
each new scenario is listed here and the `bms_output.txt` /
`stdout.txt` / `carddata.txt` outputs are re-captured.

1. **Successful update (multi-pass REWRITE)** — using an
   `ACCTSID`/`CARDSID` drawn from `app/data/ASCII/carddata.txt`,
   modify only `CRDNAMEI` (leave `CRDSTCDI`, `EXPMONI`, `EXPYEARI`
   unchanged from the displayed snapshot). End state:
   `CCUP-CHANGES-OKAYED-AND-DONE`; INFOMSG =
   `'Changes committed to database'`. Expected `carddata.txt`: same
   byte length as initial; only the affected slot differs (`CARD-NUM`
   key unchanged; `CARD-EMBOSSED-NAME` updated; `CARD-CVV-CD`,
   `CARD-EXPIRAION-DATE`, `CARD-ACTIVE-STATUS` unchanged;
   `FILLER`(59) preserved verbatim).
2. **PF12 cancel (no REWRITE)** — after a successful Pass 1 READ and
   a validated Pass 2, submit `PF12`. End state: post-save reset to
   DETAILS-NOT-FETCHED. Expected `carddata.txt`: byte-identical to
   initial CARDDAT state.
3. **Invalid input (no REWRITE)** — four sub-scenarios, each
   producing a verbatim error from the 29-message inventory and NO
   REWRITE:
   - Invalid `CRDSTCDI` value `'X'` →
     `'Card Active Status must be Y or N'` per
     `app/cbl/COCRDUPC.cbl:L845-L873`.
   - Invalid `EXPMONI` value `'13'` →
     `'Card expiry month must be between 1 and 12'` per
     `app/cbl/COCRDUPC.cbl:L877-L908`.
   - Invalid `EXPYEARI` value `'1900'` →
     `'Invalid card expiry year'` per
     `app/cbl/COCRDUPC.cbl:L913-L944`.
   - Non-alphabetic `CRDNAMEI` (e.g. containing `'9'` or `'!'`) →
     `'Card name can only contain alphabets and spaces'` per
     `app/cbl/COCRDUPC.cbl:L806-L840`.
   Expected `carddata.txt` for all four sub-scenarios: byte-identical
   to initial CARDDAT state.
4. **Card not found (READ NOTFND)** — submit an `ACCTSID`/`CARDSID`
   pair that does not exist in CARDDAT. End state:
   DETAILS-NOT-FETCHED; ERRMSG =
   `'Did not find cards for this search condition'` per
   `app/cbl/COCRDUPC.cbl:L1395-L1401`. Expected `carddata.txt`:
   byte-identical to initial CARDDAT state.
5. **No-change-detected (no REWRITE)** — submit Pass 2 ENTER with
   `CRDNAMEI`, `CRDSTCDI`, `EXPMONI`, `EXPYEARI` exactly equal to
   the OLD snapshot. End state: SHOW-DETAILS; ERRMSG =
   `'No change detected with respect to values fetched.'` per
   `app/cbl/COCRDUPC.cbl:L680-L683,L187-L188`. Expected
   `carddata.txt`: byte-identical to initial CARDDAT state.
6. **PAN masking invariant (AAP §0.7.2)** — across all scenarios
   above, `stdout.txt` MUST NOT contain any 16-digit numeric
   substring matching any card PAN from `app/data/ASCII/carddata.txt`
   or `input_scenario.txt`. Any DISPLAY-equivalent emission that
   references a card number MUST use the
   `'************<4digits>'` mask format. `bms_output.txt` MAY (and
   typically WILL) contain the full PAN at the `CARDSID` field
   position (8,45) length=16 because the screen buffer is a CICS
   surface, NOT a log.

## Verbatim COBOL message inventory (29 distinct literals)

All messages preserve trailing periods, spaces, and ASCII ellipses
(three periods `...`, NEVER the Unicode ellipsis character). INFO
messages are 40-byte `WS-INFO-MSG` 88-levels at
`app/cbl/COCRDUPC.cbl:L157-L171`; RETURN/ERROR messages are 75-byte
`WS-RETURN-MSG` 88-levels at `app/cbl/COCRDUPC.cbl:L173-L214`.

1. `'Details of selected card shown above'` (`FOUND-CARDS-FOR-ACCOUNT`; L160-L161)
2. `'Please enter Account and Card Number'` (`PROMPT-FOR-SEARCH-KEYS`; L162-L163)
3. `'Update card details presented above.'` (`PROMPT-FOR-CHANGES`; L164-L165)
4. `'Changes validated.Press F5 to save'` (`PROMPT-FOR-CONFIRMATION`; NO space after the period; L166-L167)
5. `'Changes committed to database'` (`CONFIRM-UPDATE-SUCCESS`; L168-L169)
6. `'Changes unsuccessful. Please try again'` (`INFORM-FAILURE`; L170-L171)
7. `'PF03 pressed.Exiting              '` (`WS-EXIT-MESSAGE`; 14 trailing spaces preserved; L175-L176)
8. `'Account number not provided'` (`WS-PROMPT-FOR-ACCT`; L177-L178)
9. `'Card number not provided'` (`WS-PROMPT-FOR-CARD`; L179-L180)
10. `'Card name not provided'` (`WS-PROMPT-FOR-NAME`; L181-L182)
11. `'Card name can only contain alphabets and spaces'` (`WS-NAME-MUST-BE-ALPHA`; L183-L184)
12. `'No input received'` (`NO-SEARCH-CRITERIA-RECEIVED`; L185-L186)
13. `'No change detected with respect to values fetched.'` (`NO-CHANGES-DETECTED`; L187-L188)
14. `'Account number must be a non zero 11 digit number'` (`SEARCHED-ACCT-ZEROES` and `SEARCHED-ACCT-NOT-NUMERIC`; L189-L192)
15. `'Card number if supplied must be a 16 digit number'` (`SEARCHED-CARD-NOT-NUMERIC`; L193-L194)
16. `'Card Active Status must be Y or N'` (`CARD-STATUS-MUST-BE-YES-NO`; L195-L196)
17. `'Card expiry month must be between 1 and 12'` (`CARD-EXPIRY-MONTH-NOT-VALID`; L197-L198)
18. `'Invalid card expiry year'` (`CARD-EXPIRY-YEAR-NOT-VALID`; L199-L200)
19. `'Did not find this account in cards database'` (`DID-NOT-FIND-ACCT-IN-CARDXREF`; L201-L202)
20. `'Did not find cards for this search condition'` (`DID-NOT-FIND-ACCTCARD-COMBO`; L203-L204)
21. `'Could not lock record for update'` (`COULD-NOT-LOCK-FOR-UPDATE`; L205-L206)
22. `'Record changed by some one else. Please review'` (`DATA-WAS-CHANGED-BEFORE-UPDATE`; L207-L208)
23. `'Update of record failed'` (`LOCKED-BUT-UPDATE-FAILED`; L209-L210)
24. `'Error reading Card Data File'` (`XREF-READ-ERROR`; L211-L212)
25. `'Looks Good.... so far'` (`CODING-TO-BE-DONE`; four periods plus space; L213-L214)
26. `'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'` (inline error at `app/cbl/COCRDUPC.cbl:L745`)
27. `'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'` (inline error at `app/cbl/COCRDUPC.cbl:L789`)
28. `'UNEXPECTED DATA SCENARIO'` (ABEND case at `app/cbl/COCRDUPC.cbl:L1023`)
29. `'UNEXPECTED ABEND OCCURRED.'` (ABEND-ROUTINE default at `app/cbl/COCRDUPC.cbl:L1534`)

File error template (`WS-FILE-ERROR-MESSAGE` at
`app/cbl/COCRDUPC.cbl:L133-L152`):

```text
'File Error: <8-byte OPNAME> on <9-byte FILE> returned RESP <10-byte RESP> ,RESP2 <10-byte RESP2>     '
```

## BMS map invariants

The map `CCRDUPA` within mapset `COCRDUP` is a 24x80 screen with
`CTRL=(FREEKB)`, `DSATTS=(COLOR,HILIGHT,PS,VALIDN)`, and
`MAPATTS=(COLOR,HILIGHT,PS,VALIDN)` per `app/bms/COCRDUP.bms:L20-L168`.
Field summary (row,col / length / attribute / source line):

| Row,Col | Field | Length | Attribute | Source |
|---|---|---|---|---|
| 1,7 | `TRNNAME` | 4 | ASKIP,FSET,NORM,BLUE; INITIAL `'CCUP'` | L34-L37 |
| 1,21 | `TITLE01` | 40 | ASKIP,NORM,YELLOW | L38-L41 |
| 1,71 | `CURDATE` | 8 | ASKIP,NORM,BLUE; INITIAL `'mm/dd/yy'` | L47-L51 |
| 2,7 | `PGMNAME` | 8 | ASKIP,NORM,BLUE; INITIAL `'COCRDUPC'` | L57-L60 |
| 2,21 | `TITLE02` | 40 | ASKIP,NORM,YELLOW | L61-L64 |
| 2,71 | `CURTIME` | 8 | ASKIP,NORM,BLUE; INITIAL `'hh:mm:ss'` | L70-L74 |
| 7,45 | `ACCTSID` | 11 | FSET,IC,NORM,PROT,UNDERLINE | L84-L88 |
| 8,45 | `CARDSID` | 16 | FSET,NORM,UNPROT,UNDERLINE | L96-L100 |
| 11,25 | `CRDNAME` | 50 | UNPROT,UNDERLINE | L107-L110 |
| 13,25 | `CRDSTCD` | 1 | UNPROT,UNDERLINE | L117-L120 |
| 15,25 | `EXPMON` | 2 | UNPROT,UNDERLINE,RIGHT-JUSTIFY | L127-L131 |
| 15,30 | `EXPYEAR` | 4 | UNPROT,UNDERLINE,RIGHT-JUSTIFY | L135-L139 |
| 15,36 | **`EXPDAY`** | 2 | **DRK,FSET,PROT**,HILIGHT=OFF,RIGHT-JUSTIFY | L142-L146 (HIDDEN) |
| 20,25 | `INFOMSG` | 40 | PROT,HILIGHT=OFF,NEUTRAL | L149-L153 |
| 23,1 | `ERRMSG` | 80 | ASKIP,BRT,FSET,RED | L154-L157 |
| 24,1 | `FKEYS` | 21 | ASKIP,NORM,YELLOW; INITIAL `'ENTER=Process F3=Exit'` | L158-L162 |
| 24,23 | `FKEYSC` | 18 | ASKIP,DRK,YELLOW; INITIAL `'F5=Save F12=Cancel'` | L163-L167 |

The symbolic copybook `app/cpy-bms/COCRDUP.CPY` defines `CCRDUPAI`
(input record, L17-L120) and `CCRDUPAO REDEFINES CCRDUPAI` (output
record, L121-L224) with 17 named fields each plus a 12-byte FILLER
prefix.

- Field positions, lengths, colors, and attributes are preserved
  verbatim per `app/bms/COCRDUP.bms`. The Java `CoCrdUpOutput.encode()`
  method MUST emit these bytes in this exact spatial layout;
  deviations of even one column or one space WILL fail byte-for-byte
  parity in `bms_output.txt`.
- Cursor placement: the `IC` attribute on `ACCTSID` at (7,45) per
  `app/bms/COCRDUP.bms:L84-L88` sets the initial cursor position.
- ACCTSID PROT-vs-UNPROT state dependency: source declares
  `ATTRB=(FSET,IC,NORM,PROT)` plus `HILIGHT=UNDERLINE` — `ACCTSID`
  is always PROT in the source map; the field becomes the cursor
  target via `IC` but is read-only on the rendered screen. The
  account/card numeric search keys are typically entered from the
  CARD LIST screen (CCLISTPGM) via the pre-load branch (dispatch
  branch 2), or by an initial typed entry that bypasses the BMS
  PROT via re-initialization of `DFHMSD`. Any such override is
  flagged in `java/MIGRATION_NOTES.md` §1.6 if exercised.
- `EXPDAY` hidden field: `DRK,FSET,PROT,HILIGHT=OFF` attribute at
  (15,36) length=2 hides the bytes from the 3270 renderer but the
  bytes ARE preserved in the screen buffer. Java
  `CoCrdUpOutput.encode()` MUST emit these bytes byte-for-byte.
- `INFOMSG` color: NEUTRAL with `PROT,HILIGHT=OFF`; brightened
  (`HILIGHT=BRT`) when an info message is present, driven by
  `3250-SETUP-INFOMSG` at `app/cbl/COCRDUPC.cbl:L1138-L1166`.
- `FKEYSC` color: `ASKIP,DRK` initially; brightened to BRT when
  `PROMPT-FOR-CONFIRMATION` is active (highlighting the `F5=Save`
  guidance) per `3300-SETUP-SCREEN-ATTRS` at
  `app/cbl/COCRDUPC.cbl:L1168-L1321`.
- `ERRMSG` color: RED BRT for `*-NOT-OK` or `*-BLANK` fields; a cell
  asterisk `'*'` is inserted into the field on BLANK conditions
  driven by the same `3300-SETUP-SCREEN-ATTRS` paragraph.
- `TRNNAME` always carries `'CCUP'` from `WS-TRANID` at
  `app/cbl/COCRDUPC.cbl:L221-L222`.
- `PGMNAME` always carries `'COCRDUPC'` from `WS-PGMNAME` at
  `app/cbl/COCRDUPC.cbl:L219-L220`.
- `CURDATE` format `'mm/dd/yy'` and `CURTIME` format `'hh:mm:ss'`
  populated from `FUNCTION CURRENT-DATE` via the `CSDAT01Y`
  copybook. Java side uses `ScopedValue` (per AAP §0.6.6) to inject
  a fixed clock so the captured `CURDATE`/`CURTIME` values are
  deterministic across COBOL and Java runs; the fixed clock value
  is documented in `java/MIGRATION_NOTES.md` §1.6.

## CARDDAT file invariants

The 150-byte `CARD-RECORD` layout per `app/cpy/CVACT02Y.cpy:L4-L11`:

| Offset | Length | Field | PIC | Notes |
|---|---|---|---|---|
| 1 | 16 | `CARD-NUM` | X(16) | Primary key |
| 17 | 11 | `CARD-ACCT-ID` | 9(11) | |
| 28 | 3 | `CARD-CVV-CD` | 9(03) | |
| 31 | 50 | `CARD-EMBOSSED-NAME` | X(50) | space-padded |
| 81 | 10 | `CARD-EXPIRAION-DATE` | X(10) | format `YYYY-MM-DD` |
| 91 | 1 | `CARD-ACTIVE-STATUS` | X(01) | `Y` or `N` |
| 92 | 59 | `FILLER` | X(59) | spaces |

Total: 16 + 11 + 3 + 50 + 10 + 1 + 59 = **150** bytes.

- 150-byte fixed-width records; no record terminators (the ASCII
  fixture's per-line LF is a human-readability convenience —
  strip on read; production semantic is byte-stream concatenation).
- **In-place REWRITE semantics**: `FileCardRepository.update(record)`
  MUST overwrite the 150-byte slot for the existing record; total
  file byte length is unchanged; no record added or removed;
  subsequent records NOT shifted. Per `EXEC CICS REWRITE` at
  `app/cbl/COCRDUPC.cbl:L1477-L1483`.
- Sort order preserved per AAP §0.7.1 — REWRITE does NOT change
  `CARD-NUM` (primary key); only mutable fields change
  (`CARD-CVV-CD`, `CARD-EMBOSSED-NAME`, `CARD-EXPIRAION-DATE`,
  `CARD-ACTIVE-STATUS`).
- Byte-for-byte parity rule per AAP §0.6.11.
- ASCII fixture `app/data/ASCII/carddata.txt` is REFERENCE ONLY;
  NOT COPIED into this folder. It is 50 records x 150 bytes, loaded
  as initial state via classpath relative path per
  `java/MIGRATION_NOTES.md` §1.6.

## Optimistic concurrency check (9300)

- Before REWRITE, `9200-WRITE-PROCESSING` re-reads the record with
  the `UPDATE` clause (acquires lock) per
  `app/cbl/COCRDUPC.cbl:L1427-L1436`.
- `9300-CHECK-CHANGE-IN-REC` then compares **six fields** against
  the `CCUP-OLD-*` snapshot, after uppercase normalization of the
  embossed name via `INSPECT CONVERTING LIT-LOWER TO LIT-UPPER`:
  - `CARD-CVV-CD` vs `CCUP-OLD-CVV-CD`
  - `CARD-EMBOSSED-NAME` vs `CCUP-OLD-CRDNAME`
  - `CARD-EXPIRAION-DATE(1:4)` (YEAR) vs `CCUP-OLD-EXPYEAR`
  - `CARD-EXPIRAION-DATE(6:2)` (MONTH) vs `CCUP-OLD-EXPMON`
  - `CARD-EXPIRAION-DATE(9:2)` (DAY) vs `CCUP-OLD-EXPDAY`
  - `CARD-ACTIVE-STATUS` vs `CCUP-OLD-CRDSTCD`

  per `app/cbl/COCRDUPC.cbl:L1498-L1519`.
- If ANY field differs → `SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE`
  → the OLD-* fields are refreshed from the just-read record →
  control flows to `9200-WRITE-PROCESSING-EXIT` WITHOUT a REWRITE →
  the outer `2000-DECIDE-ACTION` transitions back to SHOW-DETAILS
  for re-display, returning ERRMSG =
  `'Record changed by some one else. Please review'`.
- The Java translation MUST replicate this contract exactly:
  `CoCrdUpC` MUST re-read the record before REWRITE, MUST apply the
  uppercase normalization, MUST compare these exact six fields, and
  MUST refresh the snapshot on detected change without writing.
- Single-threaded file-based adapter test scenarios cannot
  naturally trigger the optimistic-concurrency check (no external
  mutation between READ and REWRITE), so an additional integration
  test MAY simulate the race condition by directly mutating
  `carddata.txt` between Pass 1 and Pass 3. The chosen approach is
  documented in `java/MIGRATION_NOTES.md` §1.6 if exercised.

## Java mapping invariants

- **PAN masking in logs**: Java `CoCrdUpC` MUST NOT log, emit to
  SLF4J, or include in any exception message the unmasked
  `CARD-NUM`. Mask format: `'************<4digits>'`. Per AAP §0.7.2.
- **In-place REWRITE on file**: `FileCardRepository.update(record)`
  MUST rewrite the 150-byte slot in place; use `java.nio.file` per
  AAP §0.6.5; never `java.io.File`.
- **Optimistic concurrency**: Java MUST re-read before REWRITE and
  compare the six fields against the snapshot per the 9300 contract
  above.
- **Uppercase normalization on `CARD-EMBOSSED-NAME`**: Java MUST
  replicate `INSPECT CONVERTING LIT-LOWER TO LIT-UPPER` with
  `.toUpperCase(Locale.ROOT)` (or equivalent byte-level transform);
  applied during the 9300 compare AND during the initial 9000
  population of `CCUP-OLD-CRDNAME`.
- **STRING construction of `CARD-EXPIRAION-DATE`**: Java MUST
  construct as `<YYYY> + '-' + <MM> + '-' + <DD>` (10 bytes total)
  per `app/cbl/COCRDUPC.cbl:L1467-L1474`.
- **`@CobolProgram("COCRDUPC")` traceability annotation** on the
  Java `CoCrdUpC` class per AAP §0.7.1.
- **`java.time` only**: NO `java.util.Date`, NO `java.util.Calendar`,
  NO `java.text.SimpleDateFormat`. Per AAP §0.6.4.
- **`java.nio.file` only**: NO `java.io.File` anywhere in new code.
  Per AAP §0.6.5.
- **`ScopedValue` only**: NO `ThreadLocal` in new code. Per
  AAP §0.6.6.
- **No frameworks**: no Spring, no Spring Batch, no Spring Security,
  no Hibernate, no Flyway, no PostgreSQL. Per AAP §0.6.12.
- **No floating-point monetary types**: NO `double`, NO `float` for
  any monetary value. COCRDUPC does not itself perform monetary
  arithmetic, but the cross-cutting cascade rule from AAP §0.6.1
  applies.
- **Verbatim 29 messages**: all 29 COBOL messages enumerated above
  MUST be preserved byte-for-byte (trailing periods, commas,
  spaces, and ASCII ellipses `...` — NEVER the Unicode ellipsis).
- **No JEP preview features**: per AAP §0.7.4, no `--enable-preview`,
  no JEP 502 (Stable Values), no JEP 505 (Structured Concurrency),
  no JEP 507 (Primitive Patterns); JEP 512 reserved for ad-hoc
  utilities only.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-Record Fixture Capture
Instructions) per AAP §0.7.5 for the canonical COBOL CICS build/run
path, the harness that iterates `input_scenario.txt` and produces
`carddata.txt` / `stdout.txt` / `bms_output.txt`, the encoding
(ASCII-mode capture), the fixed-clock injection for
`CURDATE`/`CURTIME` determinism, and the exact `bms_output.txt`
serialization format including the DRK attribute byte rendering for
the `EXPDAY` field. Until capture is performed the four data files
are absent from this folder and `CoCrdUpCGoldenTest` remains
`@Disabled`.

## Test class @Disabled mandate

`CoCrdUpCGoldenTest.java` (at
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoCrdUpCGoldenTest.java`)
is annotated `@Disabled` until ALL four data files
(`input_scenario.txt`, `carddata.txt`, `stdout.txt`,
`bms_output.txt`) are committed with non-placeholder content. The
`@Disabled` annotation's `value` parameter cites this README and
references AAP §0.6.11.

The **6-point verification checklist** that serves as the activation
checklist for removing `@Disabled` from `CoCrdUpCGoldenTest`:

1. Successful update (multi-pass REWRITE) preserves byte-for-byte
   parity in `carddata.txt`.
2. PF12 cancel produces no REWRITE (`carddata.txt` byte-identical
   to initial).
3. Invalid input produces no REWRITE with the verbatim error message
   for each of the four sub-scenarios.
4. Card-not-found produces the verbatim message
   `'Did not find cards for this search condition'`.
5. No-change-detected produces the verbatim message
   `'No change detected with respect to values fetched.'`.
6. No card PAN appears unmasked in `stdout.txt` per AAP §0.7.2.

## Cross-references

- `java/carddemo-tests/src/test/resources/golden/cocrdupc/input/` —
  sibling input folder (marker README only; the deterministic
  scenario contract lives here under `expected/`).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoCrdUpCGoldenTest.java`
  — JUnit 5 test class (`@Disabled` until all four data files
  present).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
  — abstract base class defining `inputFile()`,
  `expectedOutputFile()`, `expectedOutputs()`,
  `resolveExpectedOutputPath(programDir, fileName)`, and the
  byte-for-byte parity assertion.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/card/CoCrdUpC.java`
  — Java class under test (card update online).
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/card/CoCrdUpInput.java`
  and `CoCrdUpOutput.java` — entry-contract DTO records translating
  `CCRDUPAI` / `CCRDUPAO`.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardRecord.java`
  — 150-byte `CARD-RECORD` (translated from `app/cpy/CVACT02Y.cpy`).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/CardRepository.java`
  — port interface (`read(cardNum)`, `update(record)`).
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileCardRepository.java`
  — file-backed adapter with in-place REWRITE semantics.
- `java/MIGRATION_NOTES.md` §1.6 — golden-record fixture capture
  procedure (canonical authority).
- Sibling fixtures: `golden/cousr02c/expected/README.md` (in-place
  REWRITE precedent); `golden/cotrn02c/expected/README.md` (batch
  capture precedent).

## Source lineage

- `app/cbl/COCRDUPC.cbl` — COBOL source (1,560 lines). PROGRAM-ID
  at L23-L24. Key paragraphs:
  - `0000-MAIN` at L367-L562 (5-branch `EVALUATE TRUE` dispatch at
    L429-L543; AID validation at L413-L424; `COMMON-RETURN` at
    L546-L559).
  - `1000-PROCESS-INPUTS` at L564-L576; `1100-RECEIVE-MAP` at
    L578-L640 (star-wildcard normalization, `EXPDAYI` moved through
    at L621); `1200-EDIT-MAP-INPUTS` at L641-L719 (uppercase EQ
    compare at L680-L683).
  - Edit paragraphs: `1210-EDIT-ACCOUNT` at L721-L760;
    `1220-EDIT-CARD` at L762-L804; `1230-EDIT-NAME` at L806-L843;
    `1240-EDIT-CARDSTATUS` at L845-L876; `1250-EDIT-EXPIRY-MON` at
    L877-L912; `1260-EDIT-EXPIRY-YEAR` at L913-L947.
  - `2000-DECIDE-ACTION` at L948-L1031 (inner `EVALUATE TRUE` driving
    `9200-WRITE-PROCESSING` and the four outcome states).
  - SEND-MAP orchestration: `3000-SEND-MAP` at L1035-L1050;
    `3100-SCREEN-INIT` at L1052-L1080; `3200-SETUP-SCREEN-VARS` at
    L1082-L1136; `3250-SETUP-INFOMSG` at L1138-L1166;
    `3300-SETUP-SCREEN-ATTRS` at L1168-L1321; `3400-SEND-SCREEN` at
    L1324-L1340.
  - I/O paragraphs: `9000-READ-DATA` at L1343-L1374;
    `9100-GETCARD-BYACCTCARD` at L1376-L1417;
    `9200-WRITE-PROCESSING` at L1420-L1496;
    `9300-CHECK-CHANGE-IN-REC` at L1498-L1523; `ABEND-ROUTINE` at
    L1531-L1556.
- `app/bms/COCRDUP.bms` — BMS map definition (172 lines): MAPSET
  `COCRDUP`, MAP `CCRDUPA`, 24x80 screen. The `EXPDAY` field at
  L142-L146 with `ATTRB=(DRK,FSET,PROT)` is the distinguishing
  BMS-level feature — it carries the day-of-month through the
  screen round-trip while hidden from the operator.
- `app/cpy-bms/COCRDUP.CPY` — symbolic map copybook (224 lines)
  defining `CCRDUPAI` (input map; L17-L120) and
  `CCRDUPAO REDEFINES CCRDUPAI` (output map; L121-L224) with the
  17 named fields enumerated in the BMS map table above.
- `app/cpy/CVACT02Y.cpy` — `CARD-RECORD` 150-byte layout per
  `app/cpy/CVACT02Y.cpy:L1-L11`; fields enumerated in the CARDDAT
  file invariants section above.
- `app/data/ASCII/carddata.txt` — REFERENCE initial state (50 x
  150 bytes; loaded via classpath; NOT copied into this folder).

## Authority references

- AAP §0.2.1 (in-scope: `golden/cocrdupc/` directory tree as part
  of the `java/carddemo-tests/src/test/resources/golden/**/*`
  wildcard).
- AAP §0.3.1 (golden-record harness structure:
  `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (COCRDUPC → `CoCrdUpC` translation; one Java class per
  COBOL PROGRAM-ID; card update online program).
- AAP §0.6.4 (`java.time` mandate; no `java.util.Date` /
  `Calendar` / `SimpleDateFormat`).
- AAP §0.6.5 (`java.nio.file` mandate; no `java.io.File`;
  byte-for-byte round-trip).
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal`; fixed-clock
  injection for deterministic `CURDATE`/`CURTIME`).
- AAP §0.6.11 (non-negotiable PR gate; `@Disabled` scaffolding
  pattern; captured COBOL outputs; byte-for-byte file fidelity is
  non-negotiable).
- AAP §0.6.12 (architectural override: no Spring / no PostgreSQL /
  no Spring Batch / no Hibernate / no Flyway / no Spring Security).
- AAP §0.7.1 (Minimal Change Clause; preserve verbatim messages;
  sort orders; field-validation behavior preserved as-is).
- AAP §0.7.2 (no card PAN in logs; mask all but the last 4 digits;
  critical for `stdout.txt` capture).
- AAP §0.7.4 (no JEP preview features 502, 505, 507; no
  `--enable-preview`; JEP 512 utilities-only).
- AAP §0.7.5 (capture procedure documented in
  `java/MIGRATION_NOTES.md`).

## DO NOT modify the fixture data without re-capture

- The four data files in this folder (`input_scenario.txt`,
  `carddata.txt`, `stdout.txt`, `bms_output.txt`) are a
  **coupled set**.
- If `input_scenario.txt` is modified (scenarios added, removed, or
  reordered), then ALL of `carddata.txt`, `stdout.txt`, and
  `bms_output.txt` MUST be re-captured per
  `java/MIGRATION_NOTES.md` §1.6.
- Ad-hoc edits to expected outputs without re-capture WILL break
  byte-for-byte parity.
- Do NOT "fix" what looks like odd formatting in the expected files
  — those are CICS/BMS observable behavior and must be reproduced
  faithfully per AAP §0.7.1.
- Do NOT remove the trailing spaces inside `ERRMSG` (80-char field)
  or `INFOMSG` (40-char field).
- Do NOT normalize trailing newlines in `carddata.txt` (records are
  concatenated without separators in production semantics; the
  ASCII fixture's per-line LF is a human-readability convenience to
  be replicated, not removed).
- Do NOT remove the `EXPDAY` bytes from `bms_output.txt` (the DRK
  attribute hides the bytes from the operator's terminal but does
  NOT erase them from the screen buffer per BMS semantics).
- Do NOT mask `CARDSID` bytes in `bms_output.txt` — the BMS screen
  buffer is a CICS surface, NOT a log surface. PAN masking applies
  to `stdout.txt` ONLY.
- DO ensure no unmasked card PAN appears anywhere in `stdout.txt`
  per AAP §0.7.2.
