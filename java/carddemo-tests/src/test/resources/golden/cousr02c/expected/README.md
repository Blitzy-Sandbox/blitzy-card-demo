# COUSR02C Golden-Record Fixtures — expected/

This folder holds the **expected outputs** and the **synthesized scenario
contract** for the COUSR02C (User Update; CICS transaction `CU02`)
golden-record parity test. The test class is
`com.blitzy.carddemo.tests.golden.CoUsr02CGoldenTest` (`@Disabled` until
all five data files documented below are committed with non-placeholder
content, per AAP §0.6.11).

COUSR02C is an online CICS pseudo-conversational program executing a
**two-pass UPDATE flow** (Pass 1: `ENTER` → READ existing user fields
including plaintext password into the BMS map for in-place edit;
Pass 2: `PF5` or `PF3` → optionally `REWRITE` the locked record if any
of the four editable fields changed). The conventional
`<program>/input/` and `<program>/expected/` split is followed; the
sibling `../input/` folder contains only a marker README explaining why
it is intentionally documentation-only for this program. The
deterministic scenario contract `input_scenario.txt` and the auxiliary
input `usrsec.txt` live here under `expected/` because they are
**test-owned scaffolding shared by BOTH the COBOL CICS capture run AND
the Java translation under test** — both sides must agree on the input
vocabulary in order for byte-for-byte parity to be meaningful.

## Critical source-verified facts (from COBOL source)

### COBOL PROGRAM-ID and entry contract

Verbatim from `app/cbl/COUSR02C.cbl:L23,L35-L47`:

```cobol
PROGRAM-ID. COUSR02C.

01 WS-VARIABLES.
   05 WS-PGMNAME                 PIC X(08) VALUE 'COUSR02C'.
   05 WS-TRANID                  PIC X(04) VALUE 'CU02'.
   05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
   05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
   05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
     88 ERR-FLG-ON                         VALUE 'Y'.
     88 ERR-FLG-OFF                        VALUE 'N'.
   05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
   05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
   05 WS-USR-MODIFIED            PIC X(01) VALUE 'N'.
     88 USR-MODIFIED-YES                   VALUE 'Y'.
     88 USR-MODIFIED-NO                    VALUE 'N'.
```

The `WS-USR-MODIFIED` flag is the **distinguishing field for COUSR02C**
that does not exist in COUSR03C. It drives the no-change short-circuit
logic (lines 219–243): only when at least one of the four editable
fields (`FNAME` / `LNAME` / `PASSWD` / `USRTYPE`) differs from the
persisted `SEC-USR-*` value does the `REWRITE` actually execute;
otherwise the program emits `'Please modify to update ...'` and returns
the user to the screen without modifying USRSEC.

### CDEMO-CU02-INFO commarea expansion

Verbatim from `app/cbl/COUSR02C.cbl:L49-L58`:

```cobol
COPY COCOM01Y.
   05 CDEMO-CU02-INFO.
      10 CDEMO-CU02-USRID-FIRST     PIC X(08).
      10 CDEMO-CU02-USRID-LAST      PIC X(08).
      10 CDEMO-CU02-PAGE-NUM        PIC 9(08).
      10 CDEMO-CU02-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
         88 NEXT-PAGE-YES                     VALUE 'Y'.
         88 NEXT-PAGE-NO                      VALUE 'N'.
      10 CDEMO-CU02-USR-SEL-FLG     PIC X(01).
      10 CDEMO-CU02-USR-SELECTED    PIC X(08).
```

`CDEMO-CU02-USR-SELECTED` carries the user-id selected via row `'U'` on
the COUSR00C user-list screen; it auto-triggers the
`PROCESS-ENTER-KEY` first-pass when set.

### COBOL EIBAID dispatch table

The six AID-key branches in MAIN-PARA's `EVALUATE EIBAID` block,
verbatim from `app/cbl/COUSR02C.cbl:L108-L131`:

| AID Key | Behavior | Source Line |
|---|---|---|
| `DFHENTER` | PERFORM PROCESS-ENTER-KEY (read USRSEC + display existing user fields incl. PASSWDI) | L109-L110 |
| `DFHPF3` | PERFORM UPDATE-USER-INFO then XCTL to `CDEMO-FROM-PROGRAM` (or `COADM01C` if blank) — **Save & Exit** | L111-L119 |
| `DFHPF4` | PERFORM CLEAR-CURRENT-SCREEN (re-initialize fields and re-send empty screen) | L120-L121 |
| `DFHPF5` | PERFORM UPDATE-USER-INFO (Pass 2: actual REWRITE; stays on screen) — **Save only** | L122-L123 |
| `DFHPF12` | XCTL to `COADM01C` — **Cancel (no save)** | L124-L126 |
| `WHEN OTHER` | Set `WS-ERR-FLG='Y'`, MOVE `CCDA-MSG-INVALID-KEY` to WS-MESSAGE, SEND-USRUPD-SCREEN | L127-L130 |

### First-pass auto-trigger from COUSR00C

Verbatim from `app/cbl/COUSR02C.cbl:L99-L105`:

```cobol
IF CDEMO-CU02-USR-SELECTED NOT =
                           SPACES AND LOW-VALUES
    MOVE CDEMO-CU02-USR-SELECTED TO
         USRIDINI OF COUSR2AI
    PERFORM PROCESS-ENTER-KEY
END-IF
PERFORM SEND-USRUPD-SCREEN
```

This auto-trigger fires on FIRST-PASS only (NOT `CDEMO-PGM-REENTER`).
When invoked by COUSR00C with `CDEMO-CU02-USR-SELECTED` populated from
row `'U'` selection, COUSR02C copies the selected user-id into
`USRIDINI` and immediately PROCESSES the ENTER key path (reading USRSEC
and populating ALL of `FNAMEI`/`LNAMEI`/`PASSWDI`/`USRTYPEI` for
display/edit).

### PROCESS-ENTER-KEY input validation and field population

Verbatim from `app/cbl/COUSR02C.cbl:L143-L172`:

```cobol
EVALUATE TRUE
    WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'User ID can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO USRIDINL OF COUSR2AI
        PERFORM SEND-USRUPD-SCREEN
    WHEN OTHER
        MOVE -1       TO USRIDINL OF COUSR2AI
        CONTINUE
END-EVALUATE

IF NOT ERR-FLG-ON
    MOVE SPACES      TO FNAMEI   OF COUSR2AI
                        LNAMEI   OF COUSR2AI
                        PASSWDI  OF COUSR2AI
                        USRTYPEI OF COUSR2AI
    MOVE USRIDINI  OF COUSR2AI TO SEC-USR-ID
    PERFORM READ-USER-SEC-FILE
END-IF.

IF NOT ERR-FLG-ON
    MOVE SEC-USR-FNAME      TO FNAMEI    OF COUSR2AI
    MOVE SEC-USR-LNAME      TO LNAMEI    OF COUSR2AI
    MOVE SEC-USR-PWD        TO PASSWDI   OF COUSR2AI
    MOVE SEC-USR-TYPE       TO USRTYPEI  OF COUSR2AI
    PERFORM SEND-USRUPD-SCREEN
END-IF.
```

**CRITICAL — line 169 moves `SEC-USR-PWD` into `PASSWDI`**: This is the
single most important distinguishing fact between COUSR02C and
COUSR03C. COUSR02C MUST populate the password field on the screen so
the operator can edit it (or leave it unchanged). COUSR03C does NOT
populate any password field — its BMS map has no password field at
all.

The Java translation MUST preserve this exact field population in
`CoUsr02C` Pass 1: when `PROCESS-ENTER-KEY` is executed, the Java code
reads the `SecUserData` record via
`userSecurityRepository.read(userId)` and populates ALL of
`firstName`, `lastName`, `password`, and `userType` fields on the
`CoUsr02Output` DTO. The password bytes are placed at BMS field
position (13, 16) per the COUSR02 BMS map layout — the `DRK` attribute
means the 3270 terminal renderer hides them from the operator's view,
but the bytes ARE present in the serialized screen buffer.

### UPDATE-USER-INFO orchestration with five empty-field validations and change detection

Verbatim from `app/cbl/COUSR02C.cbl:L177-L245`:

```cobol
EVALUATE TRUE
    WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'User ID can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO USRIDINL OF COUSR2AI
        PERFORM SEND-USRUPD-SCREEN
    WHEN FNAMEI OF COUSR2AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'First Name can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO FNAMEL OF COUSR2AI
        PERFORM SEND-USRUPD-SCREEN
    WHEN LNAMEI OF COUSR2AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'Last Name can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO LNAMEL OF COUSR2AI
        PERFORM SEND-USRUPD-SCREEN
    WHEN PASSWDI OF COUSR2AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'Password can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO PASSWDL OF COUSR2AI
        PERFORM SEND-USRUPD-SCREEN
    WHEN USRTYPEI OF COUSR2AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'User Type can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO USRTYPEL OF COUSR2AI
        PERFORM SEND-USRUPD-SCREEN
    WHEN OTHER
        MOVE -1       TO FNAMEL OF COUSR2AI
        CONTINUE
END-EVALUATE

IF NOT ERR-FLG-ON
    MOVE USRIDINI  OF COUSR2AI TO SEC-USR-ID
    PERFORM READ-USER-SEC-FILE

    IF FNAMEI  OF COUSR2AI NOT = SEC-USR-FNAME
        MOVE FNAMEI   OF COUSR2AI TO SEC-USR-FNAME
        SET USR-MODIFIED-YES TO TRUE
    END-IF
    IF LNAMEI  OF COUSR2AI NOT = SEC-USR-LNAME
        MOVE LNAMEI   OF COUSR2AI TO SEC-USR-LNAME
        SET USR-MODIFIED-YES TO TRUE
    END-IF
    IF PASSWDI  OF COUSR2AI NOT = SEC-USR-PWD
        MOVE PASSWDI  OF COUSR2AI TO SEC-USR-PWD
        SET USR-MODIFIED-YES TO TRUE
    END-IF
    IF USRTYPEI  OF COUSR2AI NOT = SEC-USR-TYPE
        MOVE USRTYPEI OF COUSR2AI TO SEC-USR-TYPE
        SET USR-MODIFIED-YES TO TRUE
    END-IF

    IF USR-MODIFIED-YES
        PERFORM UPDATE-USER-SEC-FILE
    ELSE
        MOVE 'Please modify to update ...' TO
                        WS-MESSAGE
        MOVE DFHRED       TO ERRMSGC  OF COUSR2AO
        PERFORM SEND-USRUPD-SCREEN
    END-IF

END-IF.
```

The five empty-field validations short-circuit in the order listed
(`USRIDIN`, `FNAME`, `LNAME`, `PASSWD`, `USRTYPE`); only the FIRST
empty field triggers an error message. After successful validation,
`READ-USER-SEC-FILE` is invoked with the `UPDATE` lock; each of the
four editable fields is compared to its persisted `SEC-USR-*`
counterpart; any difference flips `WS-USR-MODIFIED` to `'Y'`. If
`USR-MODIFIED-YES` evaluates true at L236, `UPDATE-USER-SEC-FILE` is
performed (CICS `REWRITE`); otherwise the program emits
`'Please modify to update ...'` in `DFHRED` (red) color and returns to
the screen without writing.

### READ-USER-SEC-FILE response handling

The `EXEC CICS READ` at `app/cbl/COUSR02C.cbl:L322-L331` uses the
`UPDATE` clause to lock the record for subsequent `REWRITE`. The three
`EVALUATE WS-RESP-CD` branches, verbatim from
`app/cbl/COUSR02C.cbl:L320-L353`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | ERRMSG color | Source Line |
|---|---|---|---|
| `DFHRESP(NORMAL)` | `'Press PF5 key to save your updates ...'` (preceded by `CONTINUE`) | `DFHNEUTR` (NEUTRAL) | L334-L339 |
| `DFHRESP(NOTFND)` | `'User ID NOT found...'` | (ERR-FLG set; default RED via ERRMSG attr) | L340-L345 |
| `WHEN OTHER` | `'Unable to lookup User...'` | (ERR-FLG set; default RED) | L346-L352 |

The `WHEN OTHER` branch additionally emits
`DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` at
`app/cbl/COUSR02C.cbl:L347` — this is ONE of only TWO `DISPLAY`
statements in COUSR02C; it fires ONLY on unexpected CICS response
codes (NOT `NORMAL` or `NOTFND`) and emits `RESP`/`REAS` codes only —
NEVER the password value.

Note the COBOL anomaly at L335-L339: the `WHEN DFHRESP(NORMAL)` branch
contains a `CONTINUE` statement followed by additional code (`MOVE` to
`WS-MESSAGE` / `MOVE DFHNEUTR` / `PERFORM SEND-USRUPD-SCREEN`). The
`CONTINUE` is a no-op, but the subsequent `MOVE`/`PERFORM` statements
DO execute. Per AAP §0.7.1 ("Make only the minimal necessary changes
... do not 'fix' it"), the Java translation faithfully reproduces this
exact flow: under the Pass-1 ENTER path the program ALWAYS sends
`'Press PF5 key to save your updates ...'` after a successful READ
(this is the confirmation that the field display is populated for
editing).

### UPDATE-USER-SEC-FILE response handling

The `EXEC CICS REWRITE` at `app/cbl/COUSR02C.cbl:L360-L366` rewrites
the record locked by the previous READ-UPDATE. The three
`EVALUATE WS-RESP-CD` branches, verbatim from
`app/cbl/COUSR02C.cbl:L358-L390`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | ERRMSG color | Source Line |
|---|---|---|---|
| `DFHRESP(NORMAL)` | `STRING 'User ' SEC-USR-ID(SPACE-delimited) ' has been updated ...'` | `DFHGREEN` | L369-L376 |
| `DFHRESP(NOTFND)` | `'User ID NOT found...'` | (ERR-FLG set; default RED) | L377-L382 |
| `WHEN OTHER` | `'Unable to Update User...'` | (ERR-FLG set; default RED) | L383-L389 |

The COBOL `STRING ... DELIMITED BY SPACE` construction at
`app/cbl/COUSR02C.cbl:L372-L375` trims the updated user-id of trailing
spaces before concatenating it into the message:

```cobol
STRING 'User '     DELIMITED BY SIZE
       SEC-USR-ID  DELIMITED BY SPACE
       ' has been updated ...' DELIMITED BY SIZE
  INTO WS-MESSAGE
```

For `SEC-USR-ID = 'UPDUSR01'` (8 characters, no trailing spaces) the
result is `'User UPDUSR01 has been updated ...'`. For
`SEC-USR-ID = 'USR1    '` (with trailing spaces) the result is
`'User USR1 has been updated ...'`.

**CRITICAL CONTRAST WITH COUSR03C**: COUSR02C uses `EXEC CICS REWRITE`
(preserves the 80-byte slot in-place); COUSR03C uses
`EXEC CICS DELETE` (physically removes the slot). Consequently, the
byte length of `usrsec_after.txt` for COUSR02C is **identical** to
`usrsec.txt` (same number of records × 80 bytes); for COUSR03C the
byte length is **shorter by exactly 80 bytes per deletion**.

### NO admin-only access guard in COUSR02C source

Inspection of `app/cbl/COUSR02C.cbl` (entire 414-line program) confirms
that COUSR02C does **NOT** itself perform any check on `SEC-USR-TYPE`
of the calling user. The admin-only restriction described in the
folder requirements (scenario 1) is enforced upstream by the admin
menu `COADM01C`, which is the only program that XCTLs to COUSR02C
under normal flows. Per AAP §0.7.1 ("Preserve existing functionality
and behavior exactly as-is"), the Java translation MUST NOT introduce
an internal admin check inside `CoUsr02C` itself. Scenario 1 in the
test catalogue thus exercises the menu-level gate (i.e., the admin
gate is realized by routing: a non-admin user cannot reach `CoUsr02C`
from `CoMen01C` because the main menu omits the update option). This
nuance is documented explicitly so reviewers do not expect an
in-program guard.

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

Records in `usrsec.txt` are 80-byte fixed-width concatenated (no
record terminators per VSAM convention emulated on the filesystem).
`usrsec_after.txt` is the same byte length as `usrsec.txt` (in-place
`REWRITE` preserves slot count — this is the key contrast with
COUSR03C, whose `usrsec_after.txt` is byte-shorter due to physical
DELETE).

### BMS map COUSR2A layout

The map (`COUSR2A` within mapset `COUSR02`) places fields at the
following 24×80 screen positions per `app/bms/COUSR02.bms:L19-L165`.
The Java `CoUsr02Output` record's `encode()` method MUST emit these
bytes in this exact spatial layout; deviations of even one column or
one space WILL fail byte-for-byte parity in `bms_output.txt`.

| Row,Col | Field | Length | Color | Attribute | Notes / Initial |
|---|---|---|---|---|---|
| 1,1   | Literal `'Tran:'` | 5  | BLUE     | ASKIP,NORM      | `app/bms/COUSR02.bms:L29-L33` |
| 1,7   | `TRNNAME`         | 4  | BLUE     | ASKIP,FSET,NORM | filled from `WS-TRANID = 'CU02'`; L34-L37 |
| 1,21  | `TITLE01`         | 40 | YELLOW   | ASKIP,FSET,NORM | filled from `CCDA-TITLE01`; L38-L41 |
| 1,65  | Literal `'Date:'` | 5  | BLUE     | ASKIP,NORM      | L42-L46 |
| 1,71  | `CURDATE`         | 8  | BLUE     | ASKIP,FSET,NORM | initial `'mm/dd/yy'`; filled with `WS-CURDATE-MM-DD-YY`; L47-L51 |
| 2,1   | Literal `'Prog:'` | 5  | BLUE     | ASKIP,NORM      | L52-L56 |
| 2,7   | `PGMNAME`         | 8  | BLUE     | ASKIP,FSET,NORM | filled from `WS-PGMNAME = 'COUSR02C'`; L57-L60 |
| 2,21  | `TITLE02`         | 40 | YELLOW   | ASKIP,FSET,NORM | filled from `CCDA-TITLE02`; L61-L64 |
| 2,65  | Literal `'Time:'` | 5  | BLUE     | ASKIP,NORM      | L65-L69 |
| 2,71  | `CURTIME`         | 8  | BLUE     | ASKIP,FSET,NORM | initial `'hh:mm:ss'`; filled with `WS-CURTIME-HH-MM-SS`; L70-L74 |
| 4,35  | Literal `'Update User'` | 11 | NEUTRAL | ASKIP,BRT | screen title; L75-L79 |
| 6,6   | Literal `'Enter User ID:'` | 14 | GREEN | ASKIP,NORM | input prompt; L80-L84 |
| 6,21  | `USRIDIN`         | 8  | GREEN    | FSET,IC,NORM,UNPROT,UNDERLINE | sole user-id input; cursor here on first display (`IC`); L85-L89 |
| 8,6   | Literal 70-char asterisks | 70 | YELLOW | (default) | separator: `'**********************************************************************'`; L93-L97 |
| 11,6  | Literal `'First Name:'` | 11 | TURQUOISE | ASKIP,NORM | L98-L102 |
| 11,18 | `FNAME`           | 20 | GREEN    | FSET,NORM,UNPROT,UNDERLINE | **editable** First Name input; L103-L107 |
| 11,45 | Literal `'Last Name:'` | 10 | TURQUOISE | ASKIP,NORM | L111-L115 |
| 11,56 | `LNAME`           | 20 | GREEN    | FSET,NORM,UNPROT,UNDERLINE | **editable** Last Name input; L116-L120 |
| 13,6  | Literal `'Password:'` | 9 | TURQUOISE | ASKIP,NORM | L125-L129 |
| 13,16 | **`PASSWD`**      | **8** | **GREEN** | **DRK,FSET,UNPROT,UNDERLINE** | **editable Password input — `DRK` (dark/hidden) attribute**; L130-L134 |
| 13,25 | Literal `'(8 Char)'` | 8 | BLUE | ASKIP,NORM | input hint; L135-L139 |
| 15,6  | Literal `'User Type: '` | 11 | TURQUOISE | ASKIP,NORM | trailing space inside the initial value; L140-L144 |
| 15,17 | `USRTYPE`         | 1  | GREEN    | FSET,NORM,UNPROT,UNDERLINE | **editable** User Type input; L145-L149 |
| 15,19 | Literal `'(A=Admin, U=User)'` | 17 | BLUE | ASKIP,NORM | type hint; L150-L154 |
| 23,1  | `ERRMSG`          | 78 | RED      | ASKIP,BRT,FSET | dynamic message; color overridden to `DFHNEUTR` for "Press PF5..." (L338), `DFHGREEN` for update-success (L371), `DFHRED` for "Please modify..." (L241); L155-L158 |
| 24,1  | Literal `'ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel'` | 58 | YELLOW | ASKIP,NORM | PF-key footer (note `&&` is BMS escape for single `&`); L159-L164 |

**CRITICAL DIFFERENCE FROM COUSR03C — PASSWORD FIELD PRESENT WITH DRK
ATTRIBUTE**:

The COUSR02 BMS map defines a `PASSWD` field at row 13, column 16,
length 8, with `ATTRB=(DRK,FSET,UNPROT)` (verified at
`app/bms/COUSR02.bms:L130-L134`). The `DRK` (dark) attribute means the
3270 terminal renderer does NOT echo this field's contents to the
operator's screen — the bytes appear invisible to the user. However,
the bytes ARE present in the screen buffer transmitted between CICS
and the terminal. The `bms_output.txt` capture MUST contain the
password value at position (13, 16); the `stdout.txt` capture MUST
NOT.

This is the single greatest BMS-level difference between COUSR02C and
COUSR03C: COUSR03C's BMS map `COUSR3A` has no password field anywhere
on the 24×80 screen, while COUSR02C's BMS map `COUSR2A` has the
`PASSWD` field with `DRK` attribute.

**Java mapping consequence**: `CoUsr02Output.encode()` MUST write the
password bytes at the screen-buffer position corresponding to
(13, 16), AND `CoUsr02Output.encode()` MUST set the `DRK` attribute
byte for the field's attribute marker. Both invariants are validated
by byte-for-byte parity against `bms_output.txt`. Meanwhile, NO
password bytes may appear anywhere in `stdout.txt` (which captures
SLF4J INFO / DISPLAY emissions, NOT screen buffers).

### COUSR2AI input record / COUSR2AO output record REDEFINES

The symbolic map copybook `app/cpy-bms/COUSR02.CPY` defines
`01 COUSR2AI` (input map, L17-L90) and
`01 COUSR2AO REDEFINES COUSR2AI` (output map, L91-L164) with 12 fields
each: `TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`, `TITLE02`,
`CURTIME`, `USRIDIN`, `FNAME`, `LNAME`, **`PASSWD`**, `USRTYPE`,
`ERRMSG`.

Each input field has L (length), F (flag), I (input value) sub-fields:

- `PASSWDL    COMP  PIC  S9(4)` at `app/cpy-bms/COUSR02.CPY:L73`
- `PASSWDF    PICTURE X` at L74 (also `REDEFINES` as `PASSWDA` at L75-L76)
- `PASSWDI  PIC X(8)` at L78 — **the actual 8-byte input password value**

Each output field has C (color), P (programmed-symbol), H (highlight),
V (validation), O (output value) sub-fields:

- `PASSWDC    PICTURE X` at L148 — color attribute byte
- `PASSWDP    PICTURE X` at L149
- `PASSWDH    PICTURE X` at L150
- `PASSWDV    PICTURE X` at L151
- `PASSWDO  PIC X(8)` at L152 — **the actual 8-byte output password value**

The Java `CoUsr02Input` record (per AAP §0.4.1 entry-contract DTO
mapping) MUST mirror these field shapes for the input symbolic map,
and `CoUsr02Output` MUST mirror the `REDEFINES` output structure.

## Files in this folder

### `README.md` (this file)

This document. Authoritative contract for the COUSR02C golden-record
fixture.

### `input_scenario.txt` — Synthesized CICS Pseudo-Conversation Script (test-owned scaffolding)

- This file is the **deterministic test scenario contract** consumed
  by `CoUsr02CGoldenTest.inputFile()` via
  `resolveExpectedOutputPath("cousr02c", "input_scenario.txt")`.
- It is test-owned scaffolding — NOT a COBOL capture and NOT derived
  from `app/data/ASCII/*.txt` (USRSEC is not part of the 9 ASCII
  fixtures per AAP §0.4.1).
- It lives here under `expected/` (NOT under `../input/`) per folder
  requirements because BOTH the COBOL baseline run AND the Java
  translation are driven by this file. Both sides must agree on the
  input vocabulary in order for byte-for-byte parity to be meaningful.
- **Format**: one CICS pseudo-conversation submission per line. Each
  line carries fields (whitespace-separated; field count and exact
  serialization documented in `java/MIGRATION_NOTES.md` §1.6):
  - Field 1: AID key name (`ENTER`, `PF3`, `PF4`, `PF5`, `PF12`).
  - Field 2: 8-character `USRIDIN` field value (right-padded with
    spaces; an underscore `_` MAY be used to denote a literal space
    for readability if needed).
  - Field 3: 20-character `FNAMEI` field value (right-padded with
    spaces; for Pass-2 submissions; blank
    `--------------------` if not setting).
  - Field 4: 20-character `LNAMEI` field value (similarly padded).
  - Field 5: 8-character `PASSWDI` field value (the new password value
    to submit; the COBOL reference and Java translation must agree
    byte-for-byte on the slot value).
  - Field 6: 1-character `USRTYPEI` field value (`A`, `U`, or space).
  - Field 7: 8-character `CDEMO-CU02-USR-SELECTED` commarea value (for
    first-pass auto-trigger simulation from COUSR00C; blank
    `--------` if not auto-triggering).
  - Field 8: Caller `CDEMO-FROM-PROGRAM` value (e.g., `COADM01C` or
    `COUSR00C`; 8 chars).
  - Field 9: Expected outcome label (correlates to a section in
    `bms_output.txt` and `stdout.txt`).
- Lines beginning with `#` are comments (ignored by the harness).
  Blank lines are also ignored.
- **Status when initially committed**: this README documents the file;
  the file itself is committed in a subsequent PR alongside the COBOL
  capture and the removal of `@Disabled` from `CoUsr02CGoldenTest`.

### `usrsec.txt` — Test-Owned Auxiliary Input (initial USRSEC state)

- Consumed by `CoUsr02CGoldenTest.auxiliaryInputs()` as the starting
  state of the user-security file.
- 80-byte fixed-width concatenated `SEC-USER-DATA` records (no record
  terminators per VSAM convention emulated on the filesystem).
- Contains AT LEAST 4 deterministic test users to cover all scenarios:
  - 1 admin user (e.g., `'ADMIN001'` with `SEC-USR-TYPE='A'`) — the
    simulated actor for "admin invocation" scenarios.
  - 1 target user `'UPDUSR01'` (`SEC-USR-TYPE='U'`) — to be
    successfully updated in scenario 2 (any combination of
    `FNAME`/`LNAME`/`PASSWORD`/`USRTYPE` differences).
  - 1 target user `'UPDUSR02'` (`SEC-USR-TYPE='U'`) — for the
    auto-trigger scenario 3 OR the password-update scenario 7
    (document the binding scenario in the file's own comment header).
  - 1 untouched user `'KEEPUSR1'` (`SEC-USR-TYPE='U'`) — verifies NO
    spurious modifications to records OTHER than the targeted one.
- Plaintext passwords preserved per AAP §0.1.3 — each test user-id and
  its plaintext password is documented explicitly in this README's
  "Test users registry" section so fixture regeneration is fully
  deterministic.
- Test-owned scaffolding — NOT derived from `app/data/ASCII/*.txt`
  (per AAP §0.4.1 which enumerates the 9 ASCII fixtures; USRSEC is
  intentionally absent from that list).
- File size: N × 80 bytes exactly (no trailing newline; no record
  separator).


### `usrsec_after.txt` — Expected USRSEC State After Successful REWRITE

- Consumed by `CoUsr02CGoldenTest.expectedOutputs()` (mapped as the
  expected post-REWRITE USRSEC content; the harness asserts
  byte-for-byte equality against the actual file produced by
  `FileUserSecurityRepository.update(...)`).
- Reflects the **in-place REWRITE semantics**: the updated record
  occupies the same 80-byte slot as before; subsequent records are
  NOT shifted; the total file byte length is unchanged.
- **Same byte length as `usrsec.txt`**: if `usrsec.txt` contains N
  records (N × 80 bytes), then `usrsec_after.txt` also contains
  exactly N × 80 bytes after a successful REWRITE. This is the
  **critical distinguishing fact from `cousr03c/expected/usrsec_after.txt`**
  which is byte-shorter by exactly 80 bytes per deletion.
- Only the 80-byte block for the updated user-id differs from
  `usrsec.txt`; all other records preserve their plaintext passwords
  and field values verbatim — REWRITE does NOT touch any record other
  than the locked one (per AAP §0.1.3 preserve-as-is mandate).
- Sort order of all records (including the updated one, since the
  primary key `SEC-USR-ID` is NOT changed by COUSR02C's
  `UPDATE-USER-INFO`) is preserved per AAP §0.7.1 ("All file naming
  conventions, sort orders, and batch sequencing").
- For scenarios that do NOT execute a REWRITE (PF3-cancel WITHOUT a
  `USR-MODIFIED-YES`, PF12-cancel, PF4-clear, validation-error
  producing `'X can NOT be empty...'`, READ `NOTFND` producing
  `'User ID NOT found...'`, or the `'Please modify to update ...'`
  no-change short-circuit), `usrsec_after.txt` is byte-identical to
  `usrsec.txt`. If the harness exercises multiple scenarios in a
  single run, the `usrsec_after.txt` may be split into per-scenario
  expected files; the chosen approach is documented in
  `java/MIGRATION_NOTES.md` §1.6.

### `stdout.txt` — Captured Java SLF4J / COBOL DISPLAY Output

- Consumed by `CoUsr02CGoldenTest.expectedOutputFile()` (the primary
  stdout-comparison target inherited from `GoldenRecordTest`).
- Captures SLF4J INFO output produced by the Java `CoUsr02C` execution
  corresponding to the COBOL DISPLAY emissions plus harness
  diagnostics.
- **COUSR02C COBOL DISPLAY inventory**: COUSR02C contains exactly TWO
  `DISPLAY` statements in source — both inside `WHEN OTHER` branches
  of the `EVALUATE WS-RESP-CD` blocks in `READ-USER-SEC-FILE`
  (`app/cbl/COUSR02C.cbl:L347`) and `UPDATE-USER-SEC-FILE`
  (`app/cbl/COUSR02C.cbl:L384`). Both emit
  `'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD`. These DISPLAYs fire ONLY on
  unexpected CICS response codes (not `NORMAL` or `NOTFND`); under the
  documented test scenarios, neither DISPLAY is expected to fire.
  Therefore `stdout.txt` content for the "happy path" scenarios is
  largely harness diagnostics (start/end markers, scenario boundaries)
  rather than program DISPLAY output.
- **NO PLAINTEXT PASSWORD invariant** (per AAP §0.7.2 extended to
  passwords): this file MUST NOT contain any byte sequence that
  matches any `SEC-USR-PWD` value from `usrsec.txt` OR any new
  password value from `input_scenario.txt` `PASSWDI` field. The
  harness asserts this invariant as a secondary check BEYOND
  byte-for-byte parity. This is **especially important for COUSR02C**
  because (unlike COUSR03C) COUSR02C reads `SEC-USR-PWD` into the
  application's working memory at `PROCESS-ENTER-KEY` (line 169) AND
  reads `PASSWDI` from the screen into the application's memory
  before REWRITE (lines 215-217, 227-230). Despite the password value
  traversing application memory and being copied to the screen
  buffer, NO `DISPLAY` statement in COUSR02C emits it — and the Java
  translation MUST preserve this exact omission (no
  `log.info(password)`, no `log.debug(password)`, no
  `Exception(... password ...)`, no
  `System.out.println(... password ...)`).
- **NO CARD PAN invariant**: COUSR02C does not access CARDFILE;
  included as a defensive invariant in case future maintenance
  accidentally introduces PAN logging.
- Trailing newlines and line-ending convention captured exactly (LF
  or CRLF per the COBOL capture environment; default LF for ASCII-mode
  capture).

### `bms_output.txt` — Serialized BMS SEND MAP Output

- Consumed by `CoUsr02CGoldenTest.expectedOutputs()` as the expected
  screen-state target.
- Captures the `COUSR2AO` output map representations for each
  scenario submission, serialized per the format documented in
  `java/MIGRATION_NOTES.md` §1.6.
- Per-scenario block format (illustrative; final format settled in
  the capture-procedure doc): one block per `EXEC CICS SEND MAP`
  invocation, containing the 24×80 character buffer with field
  attributes encoded inline. The serialization MUST be deterministic
  so that BOTH the COBOL reference run AND the Java
  `CoUsr02Output.encode()` produce identical bytes.
- **PASSWORD BYTE PRESENT at field position (13, 16) — but with `DRK`
  attribute byte**: This is the **critical distinguishing fact from
  `cousr03c/expected/bms_output.txt`**. COUSR02C's BMS map `COUSR2A`
  has a `PASSWD` field with `ATTRB=(DRK,FSET,UNPROT)` (verified at
  `app/bms/COUSR02.bms:L130-L134`). The `DRK` attribute makes the
  field invisible to the 3270 terminal RENDERER, but the bytes ARE
  present in the screen buffer transmitted from CICS to the terminal.
  Therefore `bms_output.txt` MUST contain:
  - The password value bytes (from `PASSWDO` field, populated either
    from `SEC-USR-PWD` in Pass 1 ENTER, or from the new `PASSWDI`
    echo in Pass 2 confirmation paths) at the column-16 offset within
    row 13 of the 24×80 buffer.
  - The `DRK` attribute byte (corresponding to the `PASSWDC`
    color/attribute marker) preceding the field's bytes.
- Color and attribute byte preservation: BLUE / GREEN / YELLOW /
  TURQUOISE / RED BRT / NEUTRAL BRT / DRK attribute changes (notably
  the dynamic `ERRMSGC` override to `DFHNEUTR` for "Press PF5...",
  `DFHGREEN` for update-success, `DFHRED` for "Please modify...") MUST
  be reproduced byte-for-byte.

## Test users registry (fixture determinism)

The following test user records MUST appear in `usrsec.txt` for full
scenario coverage. The exact user-id values and plaintext passwords
listed here are intentionally weak placeholder strings selected for
fixture determinism only; they are NOT production credentials and MUST
NOT be reused for any real authentication purpose.

| User-id | First Name | Last Name | Password | Type | Filler | Total | Used in scenario(s) |
|---|---|---|---|---|---|---|---|
| `ADMIN001` | `'ADMIN               '` (20 char) | `'USER                '` (20 char) | `'ADMINPWD'` | `'A'` | 23 spaces | 80 | 1 (admin gate demonstration); 2, 3, 6, 7 (actor/observer) |
| `UPDUSR01` | `'UPDATE              '` (20 char) | `'USER01              '` (20 char) | `'UPD01PWD'` | `'U'` | 23 spaces | 80 | 2 (successful update by entered user-id); 5, 6 (invalid type / blank-field) |
| `UPDUSR02` | `'UPDATE              '` (20 char) | `'USER02              '` (20 char) | `'UPD02PWD'` | `'U'` | 23 spaces | 80 | 3 (auto-trigger from CoUsr00C); 7 (password update preserves plaintext bytes) |
| `KEEPUSR1` | `'KEEP                '` (20 char) | `'USER01              '` (20 char) | `'KEEP1PWD'` | `'U'` | 23 spaces | 80 | NEGATIVE — never the targeted user; verifies no spurious modifications |

The exact user-id values (`ADMIN001` etc.) and plaintext passwords
(`ADMINPWD` etc.) are EXAMPLES; the capture process documented in
`java/MIGRATION_NOTES.md` §1.6 may select different deterministic
values, provided they are documented in this README and applied
consistently across `usrsec.txt`, `usrsec_after.txt`, and the COBOL
reference run.

For scenario 7 (password update), the `input_scenario.txt` line
submits a new `PASSWDI` value (e.g., `'NEWPW001'`); the corresponding
`usrsec_after.txt` line for `UPDUSR02` then has bytes 49–56 =
`'NEWPW001'` (with no hashing — plaintext per AAP §0.1.3).

## Required test scenarios

`input_scenario.txt` MUST exercise — at minimum — the following 8
scenarios. The `@Disabled` annotation rationale on
`CoUsr02CGoldenTest` cites exactly this 8-point list.

1. **Admin-only access guard (menu-level, not in-program)** — Document
   explicitly: COUSR02C source contains NO admin check (see "NO
   admin-only access guard in COUSR02C source" above). The admin-only
   restriction is enforced by the calling admin menu `COADM01C` which
   is the only canonical caller of COUSR02C. Test scenario 1 verifies
   this routing fact rather than an in-program guard; it asserts that
   `CoMen01C` (the non-admin main menu) does NOT route to `CoUsr02C`.
   If the test class subjects this assertion to its own test method
   rather than a fixture, that method may live OUTSIDE this fixture
   (e.g., a unit test on `CoMen01C`'s menu table). The chosen
   implementation is documented in `java/MIGRATION_NOTES.md` §1.6.
2. **Valid update by entered user-id (READ + REWRITE)** — First
   submission: AID=`ENTER` with `USRIDIN='UPDUSR01'` (triggers READ;
   Pass 1 displays the existing record's
   `FNAME`/`LNAME`/`PASSWORD`/`USRTYPE` in the BMS map; ERRMSG =
   `'Press PF5 key to save your updates ...'` in `DFHNEUTR`). Second
   submission: AID=`PF5` with same `USRIDIN='UPDUSR01'` plus modified
   `FNAMEI` / `LNAMEI` / `PASSWDI` / `USRTYPEI` values (triggers
   READ-UPDATE + REWRITE; ERRMSG =
   `'User UPDUSR01 has been updated ...'` in `DFHGREEN`). Expected
   `usrsec_after.txt`: same byte length as `usrsec.txt`; the 80-byte
   block for `UPDUSR01` differs from `usrsec.txt` only in the fields
   that were changed.
3. **Auto-trigger flow from CoUsr00C row 'U' selection** — First
   submission: AID=`ENTER` with `USRIDIN='        '` (8 spaces) AND
   `CDEMO-CU02-USR-SELECTED='UPDUSR02'` AND
   `CDEMO-FROM-PROGRAM='COUSR00C'`. First-pass logic (NOT REENTER,
   per `app/cbl/COUSR02C.cbl:L95-L104`) copies the selected user-id
   into `USRIDINI` and PERFORMs `PROCESS-ENTER-KEY`, which reads
   USRSEC and populates ALL of
   `FNAMEI`/`LNAMEI`/`PASSWDI`/`USRTYPEI` for editing (including the
   password bytes per line 169) and displays
   `'Press PF5 key to save your updates ...'` in `DFHNEUTR`. Second
   submission: AID=`PF5` with `USRIDIN='UPDUSR02'` plus modified
   field(s) triggers REWRITE.
4. **User-id NOT found produces error** — AID=`ENTER` with
   `USRIDIN='NONEXIST'`. CICS READ returns `DFHRESP(NOTFND)`.
   Expected ERRMSG: `'User ID NOT found...'` (RED BRT, default
   ERRMSG attribute). Expected `usrsec_after.txt`: byte-identical to
   `usrsec.txt` (no REWRITE executed).
5. **Invalid SEC-USER-TYPE rejected (behavior preserved as-is per AAP
   §0.7.1)** — A submission containing `USRTYPEI` not in
   `{'A','U'}`. Inspection of `app/cbl/COUSR02C.cbl:L177-L245` shows
   that COUSR02C's `UPDATE-USER-INFO` does NOT itself
   enumerate-validate `USRTYPEI` against `{'A','U'}` — it only
   validates that `USRTYPEI` is non-empty (line 204). Any non-empty
   single character including `'X'` or `'1'` passes through to
   REWRITE. Per AAP §0.7.1 ("Make only the minimal necessary changes
   ... do not 'fix' it"), the Java translation faithfully reproduces
   this — and scenario 5 documents the exact behavior
   (`USRTYPEI='X'` is accepted by COUSR02C and written into
   `SEC-USR-TYPE` byte 57). If a downstream agent argues for
   tightening this validation, it MUST be flagged as a behavior
   change in `java/MIGRATION_NOTES.md` and deferred to a separate
   effort.
6. **Blank required-fields rejected** — Each of the five empty-field
   validations (`USRIDIN`, `FNAMEI`, `LNAMEI`, `PASSWDI`,
   `USRTYPEI`) is exercised separately by submitting PF5 with that
   one field blank (SPACES or LOW-VALUES) and all OTHER fields
   populated. Expected ERRMSG sequence (FIRST empty field wins):
   - `USRIDIN` empty → `'User ID can NOT be empty...'` (RED BRT,
     default attr)
   - `FNAMEI` empty → `'First Name can NOT be empty...'` (RED BRT)
   - `LNAMEI` empty → `'Last Name can NOT be empty...'` (RED BRT)
   - `PASSWDI` empty → `'Password can NOT be empty...'` (RED BRT)
   - `USRTYPEI` empty → `'User Type can NOT be empty...'` (RED BRT)

   Expected `usrsec_after.txt`: byte-identical to `usrsec.txt`
   (no REWRITE executed).
7. **Password update preserves plaintext bytes (AAP §0.1.3)** — A
   two-pass scenario submits a new `PASSWDI='NEWPW001'` (8 chars)
   for `UPDUSR02` while leaving `FNAMEI`/`LNAMEI`/`USRTYPEI`
   unchanged. Pass 2 detects `PASSWDI ≠ SEC-USR-PWD` via the IF
   block at `app/cbl/COUSR02C.cbl:L227-L230`, sets
   `USR-MODIFIED-YES`, and REWRITEs the `SEC-USER-DATA` record with
   the new PASSWORD bytes at offsets 49–56. Expected
   `usrsec_after.txt`: the 80-byte block for `UPDUSR02` has bytes
   49–56 equal to `'NEWPW001'` (plaintext, no hashing, no
   scrambling). Expected ERRMSG:
   `'User UPDUSR02 has been updated ...'` (`DFHGREEN`). All other
   records' 80-byte blocks are byte-identical to `usrsec.txt`.
8. **Password NEVER appears in `stdout.txt` (AAP §0.7.2)** — Even
   though `SEC-USR-PWD` is moved into `PASSWDI` at line 169
   (`PROCESS-ENTER-KEY`) and the new password is read from `PASSWDI`
   for REWRITE (lines 215-217 inside `UPDATE-USER-INFO` and lines
   227-230 inside the change-detection comparison), NO `DISPLAY`
   statement in COUSR02C emits the password value. The two `DISPLAY`
   statements in COUSR02C (lines 347 and 384) emit only
   `'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` codes. The harness
   asserts as a secondary check that NO byte sequence in
   `stdout.txt` matches ANY plaintext password from `usrsec.txt` OR
   ANY updated password from `input_scenario.txt`'s `PASSWDI`
   fields. This invariant runs OUTSIDE byte-for-byte parity (because
   it is an inequality assertion, not an equality assertion).

Supplementary note: `WHEN OTHER` (any AID key other than
ENTER/PF3/PF4/PF5/PF12) sets `WS-ERR-FLG='Y'` and emits
`CCDA-MSG-INVALID-KEY` (verbatim from `app/cpy/CSMSG01Y.cpy`). If the
harness exercises this branch, it constitutes scenario 9 (optional;
document in `java/MIGRATION_NOTES.md` §1.6).


## BMS map invariants

- Field positions, lengths, colors, and attributes preserved verbatim
  per `app/bms/COUSR02.bms` (24×80 screen layout summarized in the
  "BMS map COUSR2A layout" table above).
- Cursor placement (`IC` attribute on `USRIDIN` at position 6,21)
  preserved verbatim per AAP §0.7.1.
- ERRMSG color dynamic override: `DFHNEUTR` (NEUTRAL) for
  `'Press PF5 key to save your updates ...'`
  (`app/cbl/COUSR02C.cbl:L338`); `DFHGREEN` for
  `'User <id> has been updated ...'` (`app/cbl/COUSR02C.cbl:L371`);
  `DFHRED` for `'Please modify to update ...'`
  (`app/cbl/COUSR02C.cbl:L241`); default RED BRT for other error
  cases.
- `TRNNAME` field always carries `'CU02'` (from `WS-TRANID` at
  `app/cbl/COUSR02C.cbl:L37`); `PGMNAME` field always carries
  `'COUSR02C'` (from `WS-PGMNAME` at `app/cbl/COUSR02C.cbl:L36`).
- `CURDATE` format `'mm/dd/yy'` and `CURTIME` format `'hh:mm:ss'`
  populated from `FUNCTION CURRENT-DATE` (see `POPULATE-HEADER-INFO`
  at `app/cbl/COUSR02C.cbl:L296-L315`). Java side uses `ScopedValue`
  to inject a fixed clock (per AAP §0.6.6) so the captured
  `CURDATE`/`CURTIME` values are deterministic across COBOL and Java
  runs; the fixed clock value is documented in
  `java/MIGRATION_NOTES.md` §1.6.
- **`PASSWD` field IS present on the screen at (13, 16) with `DRK`
  attribute** — verified by inspection of
  `app/bms/COUSR02.bms:L130-L134`. The `DRK` attribute makes the
  field invisible to the 3270 terminal renderer, but the password
  bytes ARE transmitted in the 24×80 screen buffer between CICS and
  the terminal. This is the **single greatest BMS-level difference**
  between COUSR02C and COUSR03C (which has no password field at
  all). Java `CoUsr02Output.encode()` MUST preserve both the field
  position AND the `DRK` attribute byte.

## USRSEC file invariants

- 80-byte fixed-width records (no record terminators); see
  "SEC-USER-DATA 80-byte structure" above.
- **Plaintext password preservation** per AAP §0.1.3. NO hashing is
  introduced in this refactor; any move to BCrypt/Argon2 is OUT OF
  SCOPE and is documented in `java/MIGRATION_NOTES.md` for a
  follow-up effort.
- Sort order preserved per AAP §0.7.1 ("All file naming conventions,
  sort orders, and batch sequencing"). REWRITE does NOT change the
  `SEC-USR-ID` (primary key) of any record — only the modifiable
  fields (`SEC-USR-FNAME`, `SEC-USR-LNAME`, `SEC-USR-PWD`,
  `SEC-USR-TYPE`).
- **In-place REWRITE semantics**: the Java
  `FileUserSecurityRepository.update(record)` MUST implement REWRITE
  such that the 80-byte slot for the existing record is overwritten
  with the new field values; the total file byte length is
  unchanged; no record is added or removed; subsequent records are
  NOT shifted. The byte-for-byte parity assertion against
  `usrsec_after.txt` is the binding contract.
  - **CRITICAL CONTRAST WITH COUSR03C**: COUSR03C uses physical
    DELETE (records shift up; file byte length shrinks by 80).
    COUSR02C uses in-place REWRITE (file byte length unchanged).
- Byte-for-byte parity rule per AAP §0.6.11 ("byte-for-byte file
  fidelity is non-negotiable").

## Java mapping invariants

- **Plaintext password preservation**: the Java `SecUserData` record
  (in `com.blitzy.carddemo.domain.record`) MUST expose `SEC-USR-PWD`
  as `String` (or `byte[]`) without any encryption, hashing, or
  scrambling. Per AAP §0.1.3.
- **Password NEVER in logs**: the Java `CoUsr02C` class MUST NOT log,
  DISPLAY, emit to SLF4J, or include in any exception message the
  `SEC-USR-PWD` value OR any new password value coming from the
  input map. The `stdout.txt` invariant assertion enforces this.
  Per AAP §0.7.2.
- **Password IS PRESENT in BMS output buffer**: unlike COUSR03C,
  `CoUsr02Output.encode()` MUST populate the `PASSWD` field at
  screen position (13, 16) with the password bytes. The `DRK`
  attribute byte (in `PASSWDC` at offset within the output record)
  MUST also be set. Both invariants are validated by byte-for-byte
  parity against `bms_output.txt`. This is NOT a violation of the
  no-password-in-logs rule because the BMS screen buffer is a
  different surface from the SLF4J log stream — the `DRK` attribute
  hides the bytes from the OPERATOR's terminal, but the bytes are
  still part of the captured screen state.
- **In-place REWRITE on file**:
  `FileUserSecurityRepository.update(userId, record)` MUST rewrite
  the 80-byte slot for the existing record in-place; the total file
  byte length is unchanged; subsequent records are NOT shifted. Use
  `java.nio.file` per AAP §0.6.5; never `java.io.File`. (Contrast
  with `FileUserSecurityRepository.delete(userId)` in the cousr03c
  fixture, which shrinks the file.)
- **Change detection short-circuit**: the Java `CoUsr02C` MUST
  replicate the COBOL `WS-USR-MODIFIED` flag semantics: compare each
  of the four editable fields (firstName, lastName, password,
  userType) byte-for-byte to the persisted `SEC-USR-*` value; if ANY
  differ, set a local boolean `modified = true` and proceed to
  REWRITE; if NONE differ, emit `'Please modify to update ...'`
  (`DFHRED`) and return without calling `update(...)`. The order of
  comparison (`FNAMEI` first, then `LNAMEI`, then `PASSWDI`, then
  `USRTYPEI`) is observable via the modification flag only — there
  is no ordering effect on the REWRITE itself.
- **No `java.util.Date` / `java.util.Calendar` /
  `java.text.SimpleDateFormat`**: use only `java.time` per
  AAP §0.6.4.
- **No Spring / Spring Batch / Spring Security / Hibernate / Flyway /
  PostgreSQL**: the file-based default applies per AAP §0.6.12
  architectural override.
- **No `double` / `float`**: COUSR02C does not perform monetary
  arithmetic, but this is a cross-cutting cascade rule from
  AAP §0.6.1.
- **No `ThreadLocal`**: use `ScopedValue` per AAP §0.6.6.
- **No `java.io.File`**: use `java.nio.file` per AAP §0.6.5.
- **Verbatim error messages**: the eleven message literals from
  COUSR02C source MUST be preserved character-for-character in the
  Java translation (no rephrasing, no localization, no
  exclamation-point normalization; preserve trailing `...` as three
  ASCII periods, NEVER the Unicode ellipsis character):
  - `'User ID can NOT be empty...'`
    (`app/cbl/COUSR02C.cbl:L148-L149, L182-L183`)
  - `'First Name can NOT be empty...'`
    (`app/cbl/COUSR02C.cbl:L188-L189`)
  - `'Last Name can NOT be empty...'`
    (`app/cbl/COUSR02C.cbl:L194-L195`)
  - `'Password can NOT be empty...'`
    (`app/cbl/COUSR02C.cbl:L200-L201`)
  - `'User Type can NOT be empty...'`
    (`app/cbl/COUSR02C.cbl:L206-L207`)
  - `'Please modify to update ...'`
    (`app/cbl/COUSR02C.cbl:L239-L240`; note space before ellipsis)
  - `'Press PF5 key to save your updates ...'`
    (`app/cbl/COUSR02C.cbl:L336-L337`; note space before ellipsis)
  - `'User ID NOT found...'`
    (`app/cbl/COUSR02C.cbl:L342-L343, L379-L380`)
  - `'Unable to lookup User...'`
    (`app/cbl/COUSR02C.cbl:L349-L350`)
  - `'Unable to Update User...'`
    (`app/cbl/COUSR02C.cbl:L386-L387`)
  - `'User <id> has been updated ...'` constructed via
    `STRING ... DELIMITED BY SPACE`
    (`app/cbl/COUSR02C.cbl:L372-L375`; note space before ellipsis)
    — the Java translation uses `String.formatted` or explicit
    `String.strip()` (or equivalent right-trim of trailing spaces)
    on the 8-character `SEC-USR-ID` field before concatenation, to
    mimic COBOL `DELIMITED BY SPACE` semantics; the resulting
    message must equal the COBOL output byte-for-byte.
  - `CCDA-MSG-INVALID-KEY` (verbatim text from
    `app/cpy/CSMSG01Y.cpy`; not reproduced here).

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture
procedure) for the canonical COBOL CICS build/run path, the COBOL
driver harness that iterates `input_scenario.txt` and produces
`usrsec_after.txt`, `stdout.txt`, and `bms_output.txt`, the encoding
(ASCII-mode capture), the fixed-clock injection for
`CURDATE`/`CURTIME` determinism, and the exact `bms_output.txt`
serialization format (including the `DRK` attribute byte rendering
for the `PASSWD` field). This procedure exists because the user
prompt left this as a `[TODO]` marker per AAP §0.7.5
(*"To regenerate golden-record fixtures from COBOL [TODO — document
the COBOL build/run path here]."*) — the resolution is to document
the procedure in `MIGRATION_NOTES.md` rather than embed it here.
Until capture is performed, the five data files are absent from this
folder and `CoUsr02CGoldenTest` remains `@Disabled`.

## Test class @Disabled mandate

`CoUsr02CGoldenTest.java` (at
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr02CGoldenTest.java`)
is annotated `@Disabled` until ALL five data files
(`input_scenario.txt`, `usrsec.txt`, `usrsec_after.txt`,
`stdout.txt`, `bms_output.txt`) are committed with non-placeholder
content. The `@Disabled` annotation's `value` parameter cites this
README and references AAP §0.6.11.

The **8-point verification checklist** that serves as the activation
checklist for removing `@Disabled` from `CoUsr02CGoldenTest`:

1. Admin-only access guard (menu-level routing fact, not in-program
   check).
2. Valid update by entered user-id (READ + REWRITE).
3. Auto-trigger flow from `CoUsr00C` row `'U'` selection.
4. User-id NOT found produces error.
5. Invalid `SEC-USER-TYPE` rejected (behavior preserved as-is — see
   scenario 5 note).
6. Blank required-fields rejected (all five empty-field validations).
7. Password update preserves plaintext bytes (AAP §0.1.3).
8. Password NEVER appears in `stdout.txt` per AAP §0.7.2.

## Cross-references

- `java/carddemo-tests/src/test/resources/golden/cousr02c/input/` —
  sibling input folder (contains only a marker README explaining why
  the conventional `input/` is documentation-only for this fixture;
  not authoritative for this fixture; the deterministic scenario
  contract lives here under `expected/` instead).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr02CGoldenTest.java`
  — the JUnit 5 test class consuming this fixture; `@Disabled` until
  all five data files are present.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
  — the abstract base class defining `inputFile()`,
  `auxiliaryInputs()`, `expectedOutputFile()`, `expectedOutputs()`,
  the `resolveExpectedOutputPath(programDir, fileName)` helper, and
  the byte-for-byte parity assertion.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr02C.java`
  — the Java class under test (user update online).
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr02Input.java`
  and `CoUsr02Output.java` — entry-contract DTO records translating
  the `COUSR2AI`/`COUSR2AO` symbolic maps.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/SecUserData.java`
  — the 80-byte `SEC-USER-DATA` record (translated from
  `app/cpy/CSUSR01Y.cpy`).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/UserSecurityRepository.java`
  — the port interface exposing `read(userId)` and
  `update(userId, record)` operations.
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileUserSecurityRepository.java`
  — file-backed adapter implementing `UserSecurityRepository` port;
  provides `read(userId)` and `update(userId, record)` with
  in-place REWRITE semantics.
- `java/carddemo-tests/src/test/resources/golden/cousr03c/expected/README.md`
  — sibling DELETE fixture; cross-referenced for the critical
  COUSR02C-vs-COUSR03C contrast (REWRITE vs DELETE; password field
  PRESENT-with-DRK vs ABSENT).
- `java/MIGRATION_NOTES.md` §1.6 — capture procedure documentation
  (canonical authority for fixture regeneration).

## Source lineage

- `app/cbl/COUSR02C.cbl` — COBOL source: User Update CICS program
  (414 lines). PROGRAM-ID at L23; WS-VARIABLES at L35-L47 (including
  `WS-USR-MODIFIED` flag at L45-L47 — distinguishes COUSR02C from
  COUSR03C); `CDEMO-CU02-INFO` commarea fields at L50-L58; LINKAGE
  SECTION at L73-L76; `MAIN-PARA` at L82-L138 (including 6-branch
  EIBAID dispatch at L108-L131 and first-pass auto-trigger at
  L99-L104); `PROCESS-ENTER-KEY` at L143-L172 (including critical
  `MOVE SEC-USR-PWD TO PASSWDI` at L169 — distinguishes COUSR02C
  from COUSR03C); `UPDATE-USER-INFO` at L177-L245 (including five
  empty-field validations at L180-L209 and change-detection IF
  blocks at L219-L234); `RETURN-TO-PREV-SCREEN` at L250-L261;
  `SEND-USRUPD-SCREEN` at L266-L278; `RECEIVE-USRUPD-SCREEN` at
  L283-L291; `POPULATE-HEADER-INFO` at L296-L315;
  `READ-USER-SEC-FILE` at L320-L353; `UPDATE-USER-SEC-FILE` at
  L358-L390; `CLEAR-CURRENT-SCREEN` at L395-L398;
  `INITIALIZE-ALL-FIELDS` at L403-L411.
- `app/bms/COUSR02.bms` — BMS map definition (170 lines): mapset
  `COUSR02`, map `COUSR2A`, 24×80 screen with
  `CTRL=(ALARM,FREEKB)`, `EXTATT=YES`, `MODE=INOUT`. Field layout
  summarized in "BMS map COUSR2A layout" table above. The `PASSWD`
  field at L130-L134 with `ATTRB=(DRK,FSET,UNPROT)` is the
  distinguishing BMS-level feature vs. COUSR03C.
- `app/cpy-bms/COUSR02.CPY` — symbolic map copybook (164 lines)
  defining `COUSR2AI` (input map; L17-L90) and
  `COUSR2AO REDEFINES COUSR2AI` (output map; L91-L164) with 12
  fields each: `TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`,
  `TITLE02`, `CURTIME`, `USRIDIN`, `FNAME`, `LNAME`, **`PASSWD`**
  (input at L73-L78; output at L148-L152), `USRTYPE`, `ERRMSG`.
- `app/cpy/CSUSR01Y.cpy` — `SEC-USER-DATA` 80-byte record layout
  (27 lines): `SEC-USR-ID` PIC X(08), `SEC-USR-FNAME` PIC X(20),
  `SEC-USR-LNAME` PIC X(20), `SEC-USR-PWD` PIC X(08),
  `SEC-USR-TYPE` PIC X(01), `SEC-USR-FILLER` PIC X(23).

## Authority references

- AAP §0.1.3 (plaintext password preservation; `SEC-USR-PWD` bytes
  preserved verbatim through REWRITE — distinct from physical DELETE
  semantics of COUSR03C)
- AAP §0.2.1 (in-scope: `golden/cousr02c/` directory tree as part of
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.3.1 (golden-record harness structure:
  `<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 (COUSR02C → `CoUsr02C` translation; one Java class per
  COBOL PROGRAM-ID; user-update online program)
- AAP §0.6.4 (`java.time` mandate; no `java.util.Date`/`Calendar`/`SimpleDateFormat`)
- AAP §0.6.5 (`java.nio.file` mandate for file I/O; no
  `java.io.File`)
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal` for batch-run
  context; applied to fixed-clock injection)
- AAP §0.6.11 (non-negotiable PR gate; `@Disabled` scaffolding
  pattern; captured COBOL outputs; byte-for-byte file fidelity is
  non-negotiable)
- AAP §0.6.12 (architectural override: no Spring / no PostgreSQL /
  no Spring Batch / no Hibernate / no Flyway / no Spring Security)
- AAP §0.7.1 (Minimal Change Clause; preserve verbatim error
  messages; sort orders; field-validation behavior preserved as-is
  including the non-enumeration of `USRTYPE` values)
- AAP §0.7.2 (no password / no PAN in logs; PCI-relevant controls;
  password NEVER in `stdout.txt`)
- AAP §0.7.4 (no JEP preview features)
- AAP §0.7.5 (capture procedure documented in
  `java/MIGRATION_NOTES.md`)

## DO NOT modify the fixture data without re-capture

The five data files in this folder (`input_scenario.txt`,
`usrsec.txt`, `usrsec_after.txt`, `stdout.txt`, `bms_output.txt`) are
a **coupled set**. If `input_scenario.txt` is modified (scenarios
added, removed, or reordered), then `usrsec.txt`, `usrsec_after.txt`,
`stdout.txt`, and `bms_output.txt` MUST be re-captured from the
COBOL CICS reference run per the procedure in
`java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits to expected outputs
without re-capture WILL break byte-for-byte parity. Do NOT "fix"
what looks like odd-formatting in the expected files — those are
CICS/BMS's observable behavior and must be reproduced faithfully per
AAP §0.7.1. Specifically: do NOT remove the trailing spaces inside
`ERRMSG` (78-char field), do NOT normalize trailing newlines in
`usrsec.txt` (records are concatenated without separators), do NOT
introduce password hashing in either `usrsec.txt` or
`usrsec_after.txt` (per AAP §0.1.3), and do NOT remove or rewrite
the password bytes at the `DRK`-attribute `PASSWD` field position
(13, 16) inside `bms_output.txt` (the `DRK` attribute hides the
bytes from the operator's terminal but does NOT erase them from the
screen buffer per BMS semantics).
