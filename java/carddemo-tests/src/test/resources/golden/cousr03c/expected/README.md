# COUSR03C Golden-Record Fixtures — expected/

This folder holds the **expected outputs** and the **synthesized scenario
contract** for the COUSR03C (User Delete; CICS transaction `CU03`)
golden-record parity test. The test class is
`com.blitzy.carddemo.tests.golden.CoUsr03CGoldenTest` (`@Disabled` until
all five data files documented below are committed with non-placeholder
content, per AAP §0.6.11).

COUSR03C is an online CICS pseudo-conversational program, so the
conventional `<program>/input/` and `<program>/expected/` split is
followed. The sibling `../input/` folder is intentionally empty for this
program — the deterministic scenario contract `input_scenario.txt` and
the auxiliary input `usrsec.txt` live here under `expected/` because they
are **test-owned scaffolding shared by BOTH the COBOL CICS capture run
AND the Java translation under test**. Both sides must agree on the input
vocabulary in order for byte-for-byte parity to be meaningful.

## Critical source-verified facts (from COBOL source)

### COBOL PROGRAM-ID and entry contract

Verbatim from `app/cbl/COUSR03C.cbl:L23,L36-L40`:

```cobol
PROGRAM-ID. COUSR03C.

01 WS-VARIABLES.
   05 WS-PGMNAME      PIC X(08) VALUE 'COUSR03C'.
   05 WS-TRANID       PIC X(04) VALUE 'CU03'.
   05 WS-MESSAGE      PIC X(80) VALUE SPACES.
   05 WS-USRSEC-FILE  PIC X(08) VALUE 'USRSEC  '.
```

### COBOL EIBAID dispatch table

The five AID-key branches plus `WHEN OTHER` in MAIN-PARA's
`EVALUATE EIBAID` block, verbatim from `app/cbl/COUSR03C.cbl:L108-L130`:

| AID Key | Behavior | Source Line |
|---|---|---|
| `DFHENTER` | PERFORM PROCESS-ENTER-KEY (read USRSEC + display "Press PF5..." confirmation) | L109-L110 |
| `DFHPF3` | XCTL to `CDEMO-FROM-PROGRAM` (or `COADM01C` if blank) | L111-L118 |
| `DFHPF4` | PERFORM CLEAR-CURRENT-SCREEN (re-initialize fields and re-send empty screen) | L119-L120 |
| `DFHPF5` | PERFORM DELETE-USER-INFO (read USRSEC with UPDATE + DELETE) | L121-L122 |
| `DFHPF12` | XCTL to `COADM01C` (admin menu) | L123-L125 |
| `WHEN OTHER` | Set `WS-ERR-FLG='Y'`, MOVE `CCDA-MSG-INVALID-KEY` to WS-MESSAGE, SEND screen | L126-L129 |

### First-pass auto-trigger from COUSR00C

Verbatim from `app/cbl/COUSR03C.cbl:L99-L104`:

```cobol
IF CDEMO-CU03-USR-SELECTED NOT =
                           SPACES AND LOW-VALUES
    MOVE CDEMO-CU03-USR-SELECTED TO
         USRIDINI OF COUSR3AI
    PERFORM PROCESS-ENTER-KEY
END-IF
PERFORM SEND-USRDEL-SCREEN
```

This auto-trigger fires on FIRST-PASS only (NOT `CDEMO-PGM-REENTER`).
When invoked by COUSR00C with `CDEMO-CU03-USR-SELECTED` populated from
the row `'D'` selection, COUSR03C copies the selected user-id into
`USRIDINI` and immediately PROCESSES the ENTER key path (reading USRSEC
and displaying the `'Press PF5 key to delete this user ...'`
confirmation).

### READ-USER-SEC-FILE response handling

The `EXEC CICS READ` at `app/cbl/COUSR03C.cbl:L269-L278` uses the
`UPDATE` clause to lock the record for subsequent DELETE. The three
`EVALUATE WS-RESP-CD` branches, verbatim from `app/cbl/COUSR03C.cbl:L280-L300`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | ERRMSG color | Source Line |
|---|---|---|---|
| `DFHRESP(NORMAL)` | `'Press PF5 key to delete this user ...'` | `DFHNEUTR` (NEUTRAL) | L281-L286 |
| `DFHRESP(NOTFND)` | `'User ID NOT found...'` | (ERR-FLG set; default RED via ERRMSG attr) | L287-L292 |
| `WHEN OTHER` | `'Unable to lookup User...'` | (ERR-FLG set; default RED) | L293-L299 |

The `WHEN OTHER` branch additionally emits
`DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` at
`app/cbl/COUSR03C.cbl:L294` — this is one of only TWO DISPLAY statements
in the entire COUSR03C source (the other is the symmetric statement at
L330 in DELETE-USER-SEC-FILE). The DISPLAY goes to the JES output stream
under CICS, and is translated to an SLF4J INFO emission on the Java side
that is surfaced in `stdout.txt`.

### DELETE-USER-SEC-FILE response handling

The `EXEC CICS DELETE` at `app/cbl/COUSR03C.cbl:L307-L311` deletes the
record locked by the previous READ-UPDATE. The three
`EVALUATE WS-RESP-CD` branches, verbatim from `app/cbl/COUSR03C.cbl:L313-L336`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | ERRMSG color | Source Line |
|---|---|---|---|
| `DFHRESP(NORMAL)` | `STRING 'User ' SEC-USR-ID(SPACE-delimited) ' has been deleted ...'` | `DFHGREEN` | L314-L322 |
| `DFHRESP(NOTFND)` | `'User ID NOT found...'` | (ERR-FLG set; default RED) | L323-L328 |
| `WHEN OTHER` | `'Unable to Update User...'` | (ERR-FLG set; default RED) | L329-L335 |

The `STRING ... DELIMITED BY SPACE` construction at
`app/cbl/COUSR03C.cbl:L318-L321` trims the deleted user-id of trailing
spaces before concatenating it into the message:

```cobol
STRING 'User '     DELIMITED BY SIZE
       SEC-USR-ID  DELIMITED BY SPACE
       ' has been deleted ...' DELIMITED BY SIZE
  INTO WS-MESSAGE
```

For SEC-USR-ID = `'USER0001'` (8 characters, no trailing spaces) the
result is `'User USER0001 has been deleted ...'`. For SEC-USR-ID =
`'USR1    '` (with trailing spaces) the result is
`'User USR1 has been deleted ...'`.

### PROCESS-ENTER-KEY input validation

Verbatim from `app/cbl/COUSR03C.cbl:L142-L169`:

```cobol
EVALUATE TRUE
    WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
        ...
    WHEN OTHER
        ...
END-EVALUATE

IF NOT ERR-FLG-ON
    MOVE SPACES      TO FNAMEI/LNAMEI/USRTYPEI OF COUSR3AI
    MOVE USRIDINI    TO SEC-USR-ID
    PERFORM READ-USER-SEC-FILE
END-IF.

IF NOT ERR-FLG-ON
    MOVE SEC-USR-FNAME  TO FNAMEI    OF COUSR3AI
    MOVE SEC-USR-LNAME  TO LNAMEI    OF COUSR3AI
    MOVE SEC-USR-TYPE   TO USRTYPEI  OF COUSR3AI
    PERFORM SEND-USRDEL-SCREEN
END-IF.
```

**CRITICAL**: PROCESS-ENTER-KEY moves `SEC-USR-FNAME`, `SEC-USR-LNAME`,
and `SEC-USR-TYPE` into the BMS output map — but **does NOT move
`SEC-USR-PWD`** into any displayed field. The plaintext password
(per AAP §0.1.3 preservation mandate) is read into memory in
`SEC-USER-DATA` but never echoed to the user. The Java translation MUST
preserve this exact omission: the BMS output map `COUSR3A` has no
password field; `CoUsr03Output` likewise omits any password field.

### DELETE-USER-INFO orchestration

Verbatim from `app/cbl/COUSR03C.cbl:L174-L192`:

```cobol
EVALUATE TRUE
    WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
        ...
END-EVALUATE

IF NOT ERR-FLG-ON
    MOVE USRIDINI  OF COUSR3AI TO SEC-USR-ID
    PERFORM READ-USER-SEC-FILE
    PERFORM DELETE-USER-SEC-FILE
END-IF.
```

When PF5 is pressed, the program first READs (with UPDATE) then DELETEs.
Both paragraphs may PERFORM SEND-USRDEL-SCREEN — under CICS
pseudo-conversational semantics, the LAST SEND-MAP queued before
`EXEC CICS RETURN` is the screen actually delivered to the terminal.
Therefore the user observes the DELETE success message
(`'User <id> has been deleted ...'` in GREEN), not the intermediate
"Press PF5" message that READ would have queued.

### NO admin-only access guard in COUSR03C source

Inspection of `app/cbl/COUSR03C.cbl` (entire 360-line program) confirms
that COUSR03C does **NOT** itself perform any check on `SEC-USR-TYPE` of
the calling user. The admin-only restriction is enforced upstream by the
admin menu (`COADM01C`) which is the only program that XCTLs to COUSR03C
under normal flows. Per AAP §0.7.1 ("Preserve existing functionality and
behavior exactly as-is"), the Java translation MUST NOT introduce an
internal admin check inside `CoUsr03C` itself. Scenario 1 in the test
catalogue (see below) thus exercises the menu-level gate (i.e., the
admin gate is realized by routing: a non-admin user cannot reach
`CoUsr03C` from `CoMen01C` because the main menu omits the delete
option). This nuance is documented explicitly so reviewers do not expect
an in-program guard.

### SEC-USER-DATA 80-byte structure

Verbatim from `app/cpy/CSUSR01Y.cpy:L17-L23`:

| Offset (1-based) | Length | Field | PIC | Notes |
|---|---|---|---|---|
| 1  | 8  | SEC-USR-ID    | X(08) | Primary key |
| 9  | 20 | SEC-USR-FNAME | X(20) | First name (space-padded) |
| 29 | 20 | SEC-USR-LNAME | X(20) | Last name (space-padded) |
| 49 | 8  | SEC-USR-PWD   | X(08) | **Plaintext password** per AAP §0.1.3 |
| 57 | 1  | SEC-USR-TYPE  | X(01) | `'A'` = admin, `'U'` = user |
| 58 | 23 | SEC-USR-FILLER| X(23) | Trailing filler (spaces) |
| **80** | | | | **Total record length** |

Arithmetic verification: 8 + 20 + 20 + 8 + 1 + 23 = **80** bytes.

Records in `usrsec.txt` are 80-byte fixed-width concatenated (no record
terminators per VSAM convention emulated on the filesystem).

### BMS map COUSR3A layout

The output map (`COUSR3AO`) places fields at the following 24×80 screen
positions, verbatim from `app/bms/COUSR03.bms`. The Java
`CoUsr03Output` record's `encode()` method MUST emit these bytes in this
exact spatial layout; deviations of even one column or one space WILL
fail byte-for-byte parity in `bms_output.txt`.

| Row,Col | Field | Length | Color | Attribute | Notes / Initial |
|---|---|---|---|---|---|
| 1,1   | Literal `'Tran:'` | 5  | BLUE     | ASKIP,NORM      | |
| 1,7   | `TRNNAME`         | 4  | BLUE     | ASKIP,FSET,NORM | filled from `WS-TRANID = 'CU03'` |
| 1,21  | `TITLE01`         | 40 | YELLOW   | ASKIP,FSET,NORM | filled from `CCDA-TITLE01` |
| 1,65  | Literal `'Date:'` | 5  | BLUE     | ASKIP,NORM      | |
| 1,71  | `CURDATE`         | 8  | BLUE     | ASKIP,FSET,NORM | initial `'mm/dd/yy'`; filled with `WS-CURDATE-MM-DD-YY` |
| 2,1   | Literal `'Prog:'` | 5  | BLUE     | ASKIP,NORM      | |
| 2,7   | `PGMNAME`         | 8  | BLUE     | ASKIP,FSET,NORM | filled from `WS-PGMNAME = 'COUSR03C'` |
| 2,21  | `TITLE02`         | 40 | YELLOW   | ASKIP,FSET,NORM | filled from `CCDA-TITLE02` |
| 2,65  | Literal `'Time:'` | 5  | BLUE     | ASKIP,NORM      | |
| 2,71  | `CURTIME`         | 8  | BLUE     | ASKIP,FSET,NORM | initial `'hh:mm:ss'`; filled with `WS-CURTIME-HH-MM-SS` |
| 4,35  | Literal `'Delete User'` | 11 | NEUTRAL | ASKIP,BRT | screen title |
| 6,6   | Literal `'Enter User ID:'` | 14 | GREEN | ASKIP,NORM | input prompt |
| 6,21  | `USRIDIN`         | 8  | GREEN    | FSET,IC,NORM,UNPROT,UNDERLINE | cursor here on first display (`IC`) |
| 8,6   | Literal 70-char asterisks | 70 | YELLOW | (default) | separator: `'**********************************************************************'` |
| 11,6  | Literal `'First Name:'` | 11 | TURQUOISE | ASKIP,NORM | |
| 11,18 | `FNAME`           | 20 | BLUE     | ASKIP,FSET,NORM,UNDERLINE | filled from `SEC-USR-FNAME` |
| 13,6  | Literal `'Last Name:'` | 10 | TURQUOISE | ASKIP,NORM | |
| 13,18 | `LNAME`           | 20 | BLUE     | ASKIP,FSET,NORM,UNDERLINE | filled from `SEC-USR-LNAME` |
| 15,6  | Literal `'User Type: '` | 11 | TURQUOISE | ASKIP,NORM | trailing space inside the initial value |
| 15,17 | `USRTYPE`         | 1  | BLUE     | ASKIP,FSET,NORM,UNDERLINE | filled from `SEC-USR-TYPE` |
| 15,19 | Literal `'(A=Admin, U=User)'` | 17 | BLUE | ASKIP,NORM | type hint |
| 23,1  | `ERRMSG`          | 78 | RED      | ASKIP,BRT,FSET | dynamic message; color overridden to `DFHNEUTR` for "Press PF5..." and `DFHGREEN` for delete-success |
| 24,1  | Literal `'ENTER=Fetch  F3=Back  F4=Clear  F5=Delete'` | 58 | YELLOW | ASKIP,NORM | PF-key footer |

**CRITICAL — no password field**: BMS map `COUSR3A` defines NO password
field anywhere on the 24×80 screen. The map exposes `FNAME`, `LNAME`,
`USRTYPE` — and pointedly omits the password. Verified by inspection of
`app/bms/COUSR03.bms` (entire 154-line file). The Java `CoUsr03Output`
record's `encode()` MUST replicate this exact omission. The
`bms_output.txt` fixture MUST contain zero bytes from `SEC-USR-PWD` at
any cursor position.

## Files in this folder

### `README.md` (this file)

This document. Authoritative contract for the COUSR03C golden-record
fixture.

### `input_scenario.txt` — Synthesized CICS Pseudo-Conversation Script (test-owned scaffolding)

- This file is the **deterministic test scenario contract** consumed by
  `CoUsr03CGoldenTest.inputFile()` via
  `resolveExpectedOutputPath("cousr03c", "input_scenario.txt")`.
- It is test-owned scaffolding — NOT a COBOL capture and NOT derived
  from `app/data/ASCII/*.txt` (USRSEC is not part of the 9 ASCII
  fixtures per AAP §0.4.1).
- It lives here under `expected/` (NOT under `../input/`) because BOTH
  the COBOL baseline run AND the Java translation are driven by this
  file. Both sides must agree on the input vocabulary in order for
  byte-for-byte parity to be meaningful.
- **Format**: one CICS pseudo-conversation submission per line. Each
  line carries five whitespace-separated fields:
  - Field 1: AID key name (`ENTER`, `PF3`, `PF4`, `PF5`, `PF12`).
  - Field 2: 8-character `USRIDIN` field value (right-padded with
    spaces; an underscore `_` MAY be used to denote a literal space for
    readability if needed).
  - Field 3: 8-character `CDEMO-CU03-USR-SELECTED` commarea value (for
    first-pass auto-trigger simulation from COUSR00C; blank
    `--------` if not auto-triggering).
  - Field 4: Caller `CDEMO-FROM-PROGRAM` value (e.g., `COADM01C` or
    `COUSR00C`; 8 chars).
  - Field 5: Expected outcome label (correlates to a section in
    `bms_output.txt` and `stdout.txt`).
- Lines beginning with `#` are comments (ignored by the harness).
  Blank lines are also ignored.
- **Status when initially committed**: this README documents the file;
  the file itself is committed in a subsequent PR alongside the COBOL
  capture and the removal of `@Disabled` from `CoUsr03CGoldenTest`.

### `usrsec.txt` — Test-Owned Auxiliary Input (initial USRSEC state)

- Consumed by `CoUsr03CGoldenTest.auxiliaryInputs()` as the starting
  state of the user-security file.
- 80-byte fixed-width concatenated `SEC-USER-DATA` records (no record
  terminators per VSAM convention emulated on the filesystem).
- Contains AT LEAST 4 deterministic test users to cover all scenarios:
  - 1 admin user (e.g., `'ADMIN001'` with `SEC-USR-TYPE='A'`) — the
    simulated actor for "admin invocation" scenarios.
  - 1 target user `'DELUSR01'` (`SEC-USR-TYPE='U'`) — to be
    successfully deleted in scenario 2.
  - 1 target user `'DELUSR02'` (`SEC-USR-TYPE='U'`) — for the
    auto-trigger scenario 3.
  - 1 retained user `'KEEPUSR1'` (`SEC-USR-TYPE='U'`) for the
    PF3-back scenario 5 and the PF12-cancellation scenario 7
    (record NOT deleted).
- Plaintext passwords preserved per AAP §0.1.3 — each test user-id
  and its plaintext password is documented explicitly in this README's
  "Test users registry" section so fixture regeneration is fully
  deterministic.
- Test-owned scaffolding — NOT derived from `app/data/ASCII/*.txt`
  (per AAP §0.4.1 which enumerates the 9 ASCII fixtures; USRSEC is
  intentionally absent from that list).
- File size: N × 80 bytes exactly (no trailing newline; no record
  separator).

### `usrsec_after.txt` — Expected USRSEC State After Successful DELETE

- Consumed by `CoUsr03CGoldenTest.expectedOutputs()` (mapped as the
  expected post-DELETE USRSEC content; the harness asserts byte-for-byte
  equality against the actual file produced by
  `FileUserSecurityRepository.delete(...)`).
- Reflects the **physical DELETE semantics**: the deleted record is
  removed from the file; subsequent records shift up logically (zero
  gap; no tombstone bytes).
- **Byte-shorter than `usrsec.txt`**: if `usrsec.txt` contains N records
  (N × 80 bytes), then `usrsec_after.txt` contains (N−1) × 80 bytes
  after a successful single-record DELETE.
- Remaining records preserve their plaintext passwords verbatim —
  DELETE does NOT scramble or zero out the password bytes of OTHER
  records (per AAP §0.1.3 preserve-as-is mandate).
- Sort order of remaining records is preserved per AAP §0.7.1
  ("All file naming conventions, sort orders, and batch sequencing").
- For scenarios that do NOT execute a DELETE (scenarios 4, 5, 6, 7),
  `usrsec_after.txt` is byte-identical to `usrsec.txt`. If the harness
  exercises multiple scenarios in a single run, the `usrsec_after.txt`
  may be split into per-scenario expected files; the chosen approach is
  documented in `java/MIGRATION_NOTES.md` §1.6.

### `stdout.txt` — Captured Java SLF4J / COBOL DISPLAY Output

- Consumed by `CoUsr03CGoldenTest.expectedOutputFile()` (the primary
  stdout-comparison target inherited from `GoldenRecordTest`).
- Captures SLF4J INFO output produced by the Java `CoUsr03C` execution
  corresponding to the COBOL DISPLAY emissions plus harness diagnostics.
- **COUSR03C COBOL DISPLAY inventory**: COUSR03C contains exactly TWO
  `DISPLAY` statements in source — both inside `WHEN OTHER` branches of
  the `EVALUATE WS-RESP-CD` blocks in READ-USER-SEC-FILE
  (`app/cbl/COUSR03C.cbl:L294`) and DELETE-USER-SEC-FILE
  (`app/cbl/COUSR03C.cbl:L330`). Both emit
  `'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD`. These DISPLAYs fire ONLY on
  unexpected CICS response codes (not NORMAL or NOTFND); under the
  documented test scenarios, neither DISPLAY is expected to fire.
  Therefore `stdout.txt` content for the "happy path" scenarios is
  largely harness diagnostics (start/end markers, scenario boundaries)
  rather than program DISPLAY output.
- **NO PLAINTEXT PASSWORD invariant** (per AAP §0.7.2 extended to
  passwords): this file MUST NOT contain any byte sequence that matches
  any `SEC-USR-PWD` value from `usrsec.txt`. The harness asserts this
  invariant as a secondary check BEYOND byte-for-byte parity.
- **NO CARD PAN invariant**: COUSR03C does not access CARDFILE;
  included as a defensive invariant in case future maintenance
  accidentally introduces PAN logging.
- Trailing newlines and line-ending convention captured exactly (LF or
  CRLF per the COBOL capture environment; default LF for ASCII-mode
  capture).

### `bms_output.txt` — Serialized BMS SEND MAP Output

- Consumed by `CoUsr03CGoldenTest.expectedOutputs()` as the expected
  screen-state target.
- Captures the `COUSR3AO` output map representations for each scenario
  submission, serialized per the format documented in
  `java/MIGRATION_NOTES.md` §1.6.
- Per-scenario block format (illustrative; final format settled in the
  capture-procedure doc): one block per `EXEC CICS SEND MAP`
  invocation, containing the 24×80 character buffer with field
  attributes encoded inline. The serialization MUST be deterministic so
  that BOTH the COBOL reference run AND the Java `CoUsr03Output.encode()`
  produce identical bytes.
- **NO PLAINTEXT PASSWORD invariant**: BMS map `COUSR3A` has no
  password field by design (verified above — see "BMS map COUSR3A
  layout"). Therefore `bms_output.txt` MUST contain zero bytes from
  `SEC-USR-PWD`. The harness asserts this invariant.
- Color and attribute byte preservation: BLUE / GREEN / YELLOW /
  TURQUOISE / RED BRT / NEUTRAL BRT attribute changes — notably the
  dynamic `ERRMSGC` override to `DFHNEUTR` for `'Press PF5...'` and
  `DFHGREEN` for delete-success — MUST be reproduced byte-for-byte.

## Test users registry (fixture determinism)

The following test user records MUST appear in `usrsec.txt` for full
scenario coverage. The exact user-id values and plaintext passwords
listed here are intentionally weak placeholder strings selected for
fixture determinism only; they are NOT production credentials and MUST
NOT be reused for any real authentication purpose.

| User-id | First Name | Last Name | Password | Type | Filler | Total | Used in scenario(s) |
|---|---|---|---|---|---|---|---|
| `ADMIN001` | `'ADMIN               '` (20 char) | `'USER                '` (20 char) | `'ADMINPWD'` | `'A'` | 23 spaces | 80 | 1 (admin guard demonstration); 2, 3, 6 (actor) |
| `DELUSR01` | `'DELETE              '` (20 char) | `'USER01              '` (20 char) | `'DEL01PWD'` | `'U'` | 23 spaces | 80 | 2 (successful delete by entered user-id) |
| `DELUSR02` | `'DELETE              '` (20 char) | `'USER02              '` (20 char) | `'DEL02PWD'` | `'U'` | 23 spaces | 80 | 3 (auto-trigger from CoUsr00C) |
| `KEEPUSR1` | `'KEEP                '` (20 char) | `'USER01              '` (20 char) | `'KEEP1PWD'` | `'U'` | 23 spaces | 80 | 5 (PF3 back; record NOT deleted), 7 (PF12 cancel) |

The exact user-id values (`ADMIN001` etc.) and plaintext passwords
(`ADMINPWD` etc.) are EXAMPLES; the capture process documented in
`java/MIGRATION_NOTES.md` §1.6 may select different deterministic
values, provided they are documented in this README and applied
consistently across `usrsec.txt`, `usrsec_after.txt`, and the COBOL
reference run.

## Required test scenarios

`input_scenario.txt` MUST exercise — at minimum — the following 7
scenarios. The `@Disabled` annotation rationale on
`CoUsr03CGoldenTest` cites exactly this 7-point list.

1. **Admin-only access guard (menu-level, not in-program)** —
   COUSR03C source contains NO admin check (see "NO admin-only access
   guard in COUSR03C source" above). The admin-only restriction is
   enforced by the calling admin menu `COADM01C` which is the only
   canonical caller of COUSR03C. Test scenario 1 verifies this
   routing fact rather than an in-program guard; it asserts that
   `CoMen01C` (the non-admin main menu) does NOT route to
   `CoUsr03C`. If the test class subjects this assertion to its own
   test method rather than a fixture, that method may live OUTSIDE
   this fixture (e.g., a unit test on `CoMen01C`'s menu table). The
   chosen implementation is documented in
   `java/MIGRATION_NOTES.md` §1.6.
2. **Valid delete by entered user-id** — AID=`ENTER` with
   `USRIDIN='DELUSR01'` (first submission triggers READ →
   `'Press PF5 key to delete this user ...'`); then AID=`PF5` with
   `USRIDIN='DELUSR01'` (second submission triggers READ-UPDATE +
   DELETE). Expected ERRMSG sequence:
   `'Press PF5 key to delete this user ...'` (NEUTRAL) →
   `'User DELUSR01 has been deleted ...'` (GREEN). Expected
   `usrsec_after.txt`: record for `DELUSR01` physically removed
   (3 records remain, byte-shorter by 80).
3. **Auto-trigger flow from CoUsr00C row 'D' selection** — First
   submission: AID=`ENTER` with `USRIDIN='        '` (8 spaces) AND
   `CDEMO-CU03-USR-SELECTED='DELUSR02'` AND
   `CDEMO-FROM-PROGRAM='COUSR00C'`. First-pass logic (NOT REENTER,
   per `app/cbl/COUSR03C.cbl:L95-L104`) copies the selected user-id
   into `USRIDINI` and PERFORMs `PROCESS-ENTER-KEY`, which reads
   USRSEC and displays `'Press PF5 key to delete this user ...'`.
   Then AID=`PF5` with `USRIDIN='DELUSR02'` triggers DELETE.
   Expected ERRMSG sequence:
   `'Press PF5 key to delete this user ...'` (NEUTRAL) →
   `'User DELUSR02 has been deleted ...'` (GREEN).
4. **User-id NOT found** — AID=`ENTER` with `USRIDIN='NONEXIST'`.
   CICS READ returns `DFHRESP(NOTFND)`. Expected ERRMSG:
   `'User ID NOT found...'` (RED BRT, default ERRMSG attribute).
   Expected `usrsec_after.txt`: byte-identical to `usrsec.txt`
   (no DELETE executed).
5. **PF3 back navigation** — AID=`PF3` with any value in `USRIDIN`.
   Returns to `CDEMO-FROM-PROGRAM` (or `COADM01C` if blank). No
   DELETE issued; `usrsec_after.txt` byte-identical to `usrsec.txt`.
   Note: PF3 dispatches XCTL to the previous program; the resulting
   screen (from that program) is NOT captured in this fixture's
   `bms_output.txt` — only COUSR03C's behavior is captured.
6. **PF4 clear** — AID=`PF4` with any value in `USRIDIN`.
   `CLEAR-CURRENT-SCREEN` re-initializes all fields (per
   `app/cbl/COUSR03C.cbl:L341-L344` and the
   `INITIALIZE-ALL-FIELDS` paragraph at
   `app/cbl/COUSR03C.cbl:L349-L356`) and re-sends the empty
   data-entry screen. Expected ERRMSG: empty (SPACES). Expected
   `usrsec_after.txt`: byte-identical to `usrsec.txt`.
7. **PF12 cancellation** — AID=`PF12` with any value in `USRIDIN`.
   Returns to `COADM01C` (XCTL). No DELETE issued; `usrsec_after.txt`
   byte-identical to `usrsec.txt`. Similar to scenario 5 regarding
   XCTL screen capture scope.

Supplementary note: `WHEN OTHER` (any AID key other than
ENTER/PF3/PF4/PF5/PF12) sets `WS-ERR-FLG='Y'` and emits
`CCDA-MSG-INVALID-KEY` (verbatim from `app/cpy/CSMSG01Y.cpy:L20-L21`,
text: `'Invalid key pressed. Please see below...         '`,
PIC X(50)). If the harness exercises this branch, it constitutes
scenario 8 (optional; document in `java/MIGRATION_NOTES.md` §1.6).

## BMS map invariants

- Field positions, lengths, colors, and attributes preserved verbatim
  per `app/bms/COUSR03.bms` (24×80 screen layout summarized in the
  "BMS map COUSR3A layout" table above).
- Cursor placement (`IC` attribute on `USRIDIN` at position 6,21)
  preserved verbatim per AAP §0.7.1.
- ERRMSG color dynamic override: `DFHNEUTR` (NEUTRAL) for
  `'Press PF5 key to delete this user ...'`
  (`app/cbl/COUSR03C.cbl:L285`); `DFHGREEN` for
  `'User <id> has been deleted ...'` (`app/cbl/COUSR03C.cbl:L317`);
  default RED BRT for error cases.
- `TRNNAME` field always carries `'CU03'` (from `WS-TRANID`);
  `PGMNAME` field always carries `'COUSR03C'` (from `WS-PGMNAME`).
- `CURDATE` format `'mm/dd/yy'` and `CURTIME` format `'hh:mm:ss'`
  populated from `FUNCTION CURRENT-DATE` (see `POPULATE-HEADER-INFO`
  at `app/cbl/COUSR03C.cbl:L243-L262`). Java side uses `ScopedValue`
  to inject a fixed clock (per AAP §0.6.6) so the captured
  `CURDATE`/`CURTIME` values are deterministic across COBOL and Java
  runs; the fixed clock value is documented in
  `java/MIGRATION_NOTES.md` §1.6.
- NO password field on the screen — verified by inspection of
  `app/bms/COUSR03.bms` (entire 154-line file). The screen layout
  deliberately omits password rendering.

## USRSEC file invariants

- 80-byte fixed-width records (no record terminators); see
  "SEC-USER-DATA 80-byte structure" above.
- **Plaintext password preservation** per AAP §0.1.3. NO hashing is
  introduced in this refactor; any move to BCrypt/Argon2 is OUT OF
  SCOPE and is documented in `java/MIGRATION_NOTES.md` for a
  follow-up effort.
- Sort order preserved per AAP §0.7.1 ("All file naming conventions,
  sort orders, and batch sequencing").
- **Physical DELETE semantics**: the Java
  `FileUserSecurityRepository` MUST implement DELETE such that the
  deleted record is physically removed and subsequent records shift
  up (no tombstone bytes, no gap). The byte-for-byte parity
  assertion against `usrsec_after.txt` is the binding contract.
- Byte-for-byte parity rule per AAP §0.6.11 ("byte-for-byte file
  fidelity is non-negotiable").

## Java mapping invariants

- **Plaintext password preservation**: the Java `SecUserData` record
  (in `com.blitzy.carddemo.domain.record`) MUST expose `SEC-USR-PWD`
  as `String` (or `byte[]`) without any encryption, hashing, or
  scrambling. Per AAP §0.1.3.
- **Password NEVER in logs**: the Java `CoUsr03C` class MUST NOT log,
  DISPLAY, emit to SLF4J, or include in any exception message the
  `SEC-USR-PWD` value. The `stdout.txt` invariant assertion enforces
  this.
- **Password NEVER in BMS output**: `CoUsr03Output.encode()` MUST
  omit `SEC-USR-PWD` from the serialized screen-state output. The
  `bms_output.txt` invariant assertion enforces this.
- **Physical DELETE on file**: `FileUserSecurityRepository.delete(userId)`
  MUST rewrite the file with the deleted record physically removed
  (subsequent records shift up; no gap). Use `java.nio.file` per
  AAP §0.6.5; never `java.io.File`.
- **No `java.util.Date` / `java.util.Calendar` /
  `java.text.SimpleDateFormat`**: use only `java.time` per
  AAP §0.6.4.
- **No Spring / Spring Batch / Spring Security / Hibernate / Flyway /
  PostgreSQL**: the file-based default applies per AAP §0.6.12
  architectural override.
- **No `double` / `float`**: COUSR03C does not perform monetary
  arithmetic, but this is a cross-cutting cascade rule from
  AAP §0.6.1.
- **No `ThreadLocal`**: use `ScopedValue` per AAP §0.6.6.
- **Verbatim error messages**: the message literals from COUSR03C
  source MUST be preserved character-for-character in the Java
  translation (no rephrasing, no localization, no exclamation-point
  normalization):
  - `'User ID can NOT be empty...'`
    (`app/cbl/COUSR03C.cbl:L147, L179`)
  - `'User ID NOT found...'`
    (`app/cbl/COUSR03C.cbl:L289, L325`)
  - `'Press PF5 key to delete this user ...'`
    (`app/cbl/COUSR03C.cbl:L283`) — note the single space between
    `user` and `...`
  - `'Unable to lookup User...'`
    (`app/cbl/COUSR03C.cbl:L296`)
  - `'Unable to Update User...'`
    (`app/cbl/COUSR03C.cbl:L332`)
  - `'User <id> has been deleted ...'` constructed via
    `STRING ... DELIMITED BY SPACE`
    (`app/cbl/COUSR03C.cbl:L318-L321`) — the Java translation uses
    explicit `String.strip()` (or equivalent right-trim of trailing
    spaces) on the 8-character `SEC-USR-ID` field before
    concatenation, to mimic COBOL `DELIMITED BY SPACE` semantics;
    the resulting message must equal the COBOL output
    byte-for-byte.
  - `CCDA-MSG-INVALID-KEY` (verbatim text
    `'Invalid key pressed. Please see below...         '` from
    `app/cpy/CSMSG01Y.cpy:L20-L21`, PIC X(50)).

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture
procedure) for the canonical COBOL CICS build/run path, the COBOL
driver harness that iterates `input_scenario.txt` and produces
`usrsec_after.txt`, `stdout.txt`, and `bms_output.txt`, the encoding
(ASCII-mode capture), the fixed-clock injection for `CURDATE`/`CURTIME`
determinism, and the exact `bms_output.txt` serialization format.
This procedure exists because the user prompt left this as a `[TODO]`
marker per AAP §0.7.5
(*"To regenerate golden-record fixtures from COBOL [TODO — document
the COBOL build/run path here]."*) — the resolution is to document
the procedure in `MIGRATION_NOTES.md` rather than embed it here.
Until capture is performed, the five data files are absent from this
folder and `CoUsr03CGoldenTest` remains `@Disabled`.

## Test class @Disabled mandate

`CoUsr03CGoldenTest.java`
(at
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr03CGoldenTest.java`)
is annotated `@Disabled` until ALL five data files
(`input_scenario.txt`, `usrsec.txt`, `usrsec_after.txt`,
`stdout.txt`, `bms_output.txt`) are committed with non-placeholder
content. The `@Disabled` annotation's `value` parameter cites this
README and references AAP §0.6.11.

The **7-point verification checklist** that serves as the activation
checklist for removing `@Disabled` from `CoUsr03CGoldenTest`:

1. Admin-only access guard (menu-level routing fact, not in-program
   check).
2. Valid delete by entered user-id (READ + DELETE).
3. Auto-trigger flow from CoUsr00C row `'D'` selection.
4. User-id NOT found produces error
   (`'User ID NOT found...'`).
5. Confirmation prompt before DELETE
   (`'Press PF5 key to delete this user ...'`).
6. Record physically removed from `usrsec.txt` after DELETE
   (`usrsec_after.txt` byte-shorter by 80).
7. Password column NEVER appears in `stdout.txt` or `bms_output.txt`
   per AAP §0.7.2.

## Cross-references

- `java/carddemo-tests/src/test/resources/golden/cousr03c/input/` —
  sibling input folder (currently empty; the descendant agent
  assigned there may create its own marker README explaining why the
  conventional `input/` is empty for this fixture; not authoritative
  for this fixture).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr03CGoldenTest.java`
  — the JUnit 5 test class consuming this fixture; `@Disabled` until
  all five data files are present.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
  — the abstract base class defining `inputFile()`,
  `auxiliaryInputs()`, `expectedOutputFile()`, `expectedOutputs()`,
  and the byte-for-byte parity assertion.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr03C.java`
  — the Java class under test (online user delete).
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr03Input.java`
  and `CoUsr03Output.java` — entry-contract DTO records translating
  the `COUSR3AI`/`COUSR3AO` symbolic maps.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/SecUserData.java`
  — the 80-byte `SEC-USER-DATA` record (translated from
  `app/cpy/CSUSR01Y.cpy`).
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileUserSecurityRepository.java`
  — file-backed adapter implementing `UserSecurityRepository` port;
  provides `read(userId)` and `delete(userId)` with physical-DELETE
  semantics.
- `java/MIGRATION_NOTES.md` §1.6 — capture procedure documentation
  (canonical authority for fixture regeneration).

## Source lineage

- `app/cbl/COUSR03C.cbl` — COBOL source: User Delete CICS program
  (360 lines). PROGRAM-ID at L23; WS-VARIABLES at L35-L47;
  `CDEMO-CU03-INFO` commarea fields at L50-L58; LINKAGE SECTION at
  L73-L76; MAIN-PARA at L82-L137; PROCESS-ENTER-KEY at L142-L169;
  DELETE-USER-INFO at L174-L192; RETURN-TO-PREV-SCREEN at
  L197-L208; SEND-USRDEL-SCREEN at L213-L225; RECEIVE-USRDEL-SCREEN
  at L230-L238; POPULATE-HEADER-INFO at L243-L262; READ-USER-SEC-FILE
  at L267-L300; DELETE-USER-SEC-FILE at L305-L336;
  CLEAR-CURRENT-SCREEN at L341-L344; INITIALIZE-ALL-FIELDS at
  L349-L356.
- `app/bms/COUSR03.bms` — BMS map definition (154 lines): mapset
  `COUSR03`, map `COUSR3A`, 24×80 screen with
  `CTRL=(ALARM,FREEKB)`, `EXTATT=YES`, `MODE=INOUT`. Field layout
  summarized in "BMS map COUSR3A layout" table above.
- `app/cpy-bms/COUSR03.CPY` — symbolic map copybook (152 lines)
  defining `COUSR3AI` (input map) and `COUSR3AO` (output map
  `REDEFINES`) with 11 fields: `TRNNAME`, `TITLE01`, `CURDATE`,
  `PGMNAME`, `TITLE02`, `CURTIME`, `USRIDIN`, `FNAME`, `LNAME`,
  `USRTYPE`, `ERRMSG`.
- `app/cpy/CSUSR01Y.cpy` — `SEC-USER-DATA` 80-byte record layout
  (26 lines): `SEC-USR-ID` PIC X(08), `SEC-USR-FNAME` PIC X(20),
  `SEC-USR-LNAME` PIC X(20), `SEC-USR-PWD` PIC X(08),
  `SEC-USR-TYPE` PIC X(01), `SEC-USR-FILLER` PIC X(23).

## Authority references

- AAP §0.1.3 (plaintext password preservation; `SEC-USR-PWD` bytes
  NOT scrambled by DELETE)
- AAP §0.2.1 (in-scope: `golden/cousr03c/` directory tree as part of
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.3.1 (golden-record harness structure:
  `<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 (COUSR03C → `CoUsr03C` translation; user-delete online
  program)
- AAP §0.6.5 (`java.nio.file` mandate for file I/O)
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal` for batch-run
  context; applied to fixed-clock injection)
- AAP §0.6.11 (non-negotiable PR gate; `@Disabled` scaffolding
  pattern; captured COBOL outputs; byte-for-byte file fidelity is
  non-negotiable)
- AAP §0.6.12 (architectural override: no Spring / no PostgreSQL /
  no Spring Batch / no Spring Security / no Hibernate / no Flyway)
- AAP §0.7.1 (Minimal Change Clause; preserve verbatim error
  messages; sort orders; padding; rounding direction)
- AAP §0.7.2 (no password / no PAN in logs; PCI-relevant controls)
- AAP §0.7.5 (capture procedure documented in
  `java/MIGRATION_NOTES.md`)

## DO NOT modify the fixture data without re-capture

The five data files in this folder (`input_scenario.txt`,
`usrsec.txt`, `usrsec_after.txt`, `stdout.txt`, `bms_output.txt`) are
a **coupled set**. If `input_scenario.txt` is modified (scenarios
added, removed, or reordered), then `usrsec.txt`, `usrsec_after.txt`,
`stdout.txt`, and `bms_output.txt` MUST be re-captured from the COBOL
CICS reference run per the procedure in `java/MIGRATION_NOTES.md`
§1.6. Ad-hoc edits to expected outputs without re-capture WILL break
byte-for-byte parity. Do NOT "fix" what looks like odd-formatting in
the expected files — those are CICS/BMS's observable behavior and
must be reproduced faithfully per AAP §0.7.1. Specifically: do NOT
remove the trailing spaces inside `ERRMSG` (78-char field), do NOT
normalize trailing newlines in `usrsec.txt` (records are concatenated
without separators), and do NOT introduce password hashing in either
`usrsec.txt` or `usrsec_after.txt` (per AAP §0.1.3).
