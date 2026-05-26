# COMEN01C (Main Menu, CM00) — Golden-Record Test Fixture Contract

## Phase 0: Header and Authority Cascade

This document is the AUTHORITATIVE binding contract for the COMEN01C
golden-record test fixture, mediating between (a) the COBOL source
`app/cbl/COMEN01C.cbl` (Main Menu for regular users; CICS transaction
`CM00`), (b) the Java translation `com.blitzy.carddemo.application.menu.CoMen01C`,
(c) the harness `com.blitzy.carddemo.tests.golden.CoMen01CGoldenTest`,
and (d) the three fixture files in this directory (`input_scenario.txt`,
`stdout.txt`, `bms_output.txt`). COMEN01C is the main-menu landing page
for regular (non-admin) users; it is reached via XCTL from COSGN00C
after a successful signon when `CDEMO-USER-TYPE = 'U'`. COMEN01C is
the sibling of COADM01C (admin menu) and uses the static `COMEN02Y`
menu table for option-to-program dispatch.

ANY change to a fixture file, scenario count, message verbatim,
BMS map invariant, or menu options table MUST be accompanied by a
corresponding change to this README and re-captured `stdout.txt` and
`bms_output.txt` artifacts. Future contract updates MUST modify
content within phases rather than reorganizing phases.

Authority references (binding):

- AAP §0.2.1 — in-scope: `golden/comen01c/` directory tree under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard.
- AAP §0.3.1 — harness directory convention: `input/` + `expected/` per program.
- AAP §0.4.1 — COMEN01C → `com.blitzy.carddemo.application.menu.CoMen01C`; BMS map → DTO records `CoMen01Input` / `CoMen01Output`; uses `COMEN02Y` menu table for dispatch.
- AAP §0.6.4 — `java.time` mandate for date/time formatting in POPULATE-HEADER-INFO.
- AAP §0.6.10 — sealed `UserType { Admin, User }` from 88-level conditions in `app/cpy/COCOM01Y.cpy`; sealed `PgmContext { Enter, Reenter }` from 88-levels at `[app/cpy/COCOM01Y.cpy:L30-L31]`.
- AAP §0.6.11 — golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern until COBOL capture committed.
- AAP §0.6.12 — architectural override: no Spring container; plain Java with constructor injection; no relational database; no batch framework.
- AAP §0.7.1 — Minimal Change Clause; verbatim error messages; preserve sequencing and BMS layout.
- AAP §0.7.4 — pattern-matching exhaustiveness — NO `default` branches in `switch`; sealed `AidKey` hierarchy.
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.

Sibling pattern reference: this README adopts the 13-phase pattern
from `golden/cousr00c/expected/README.md` (canonical pattern source)
and `golden/cosgn00c/expected/README.md` (sibling menu-dispatch
program with signon role). The 13-phase format is preserved verbatim;
content is re-grounded against COMEN01C's menu-dispatch-only semantics.

The test class `com.blitzy.carddemo.tests.golden.CoMen01CGoldenTest`
is annotated `@Disabled("Awaiting COBOL capture per java/MIGRATION_NOTES.md §1.6")`
until `stdout.txt` and `bms_output.txt` in this directory are replaced
with captured COBOL artifacts per AAP §0.6.11. Placeholder content
suffices for harness scaffolding but NOT for byte-level parity assertions.

## Phase 1: COBOL Source — PROGRAM-ID and Working-Storage Variables

`PROGRAM-ID` is `COMEN01C` `[app/cbl/COMEN01C.cbl:L23]`. `AUTHOR` is
`AWS` `[app/cbl/COMEN01C.cbl:L24]`. The complete inventory of
working-storage variables that participate in test scenarios is
enumerated in the table below.

| COBOL Name | PIC | Source Line | Java Equivalent | Notes |
|---|---|---|---|---|
| `WS-PGMNAME` | `X(08) VALUE 'COMEN01C'` | `[app/cbl/COMEN01C.cbl:L36]` | `CoMen01C.PGMNAME` constant | Emitter program in commarea on XCTL; propagated to `CDEMO-FROM-PROGRAM`. |
| `WS-TRANID` | `X(04) VALUE 'CM00'` | `[app/cbl/COMEN01C.cbl:L37]` | `CoMen01C.TRANID` constant | CICS transaction ID — `CM00` (NOT `CC00` like COSGN00C). |
| `WS-MESSAGE` | `X(80) VALUE SPACES` | `[app/cbl/COMEN01C.cbl:L38]` | local `String message` in use-case | Routed to `ERRMSGO` in SEND-MENU-SCREEN. |
| `WS-USRSEC-FILE` | `X(08) VALUE 'USRSEC  '` | `[app/cbl/COMEN01C.cbl:L39]` | (DEAD) — see Phase 13.2 | Declared but NEVER referenced; COMEN01C does NOT read USRSEC. Translated faithfully per AAP §0.7.1; flagged in `java/MIGRATION_NOTES.md`. |
| `WS-ERR-FLG` (88 ERR-FLG-ON/OFF) | `X(01) VALUE 'N'` | `[app/cbl/COMEN01C.cbl:L40-L42]` | local `boolean errorFlag` | Sealed two-state — `'Y'` / `'N'`; no third value permitted. |
| `WS-RESP-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COMEN01C.cbl:L43]` | (DEAD — captured by RECEIVE-MENU-SCREEN paragraph that is never invoked) | RESP code holder; see Phase 6 RECEIVE-MENU-SCREEN dead-code note. |
| `WS-REAS-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COMEN01C.cbl:L44]` | (DEAD — same reason as `WS-RESP-CD`) | RESP2 reason code holder. |
| `WS-OPTION-X` | `X(02) JUST RIGHT` | `[app/cbl/COMEN01C.cbl:L45]` | local `String optionRightAligned` (length 2) | Alphanumeric, JUST RIGHT — used by INSPECT REPLACING to convert leading spaces to `'0'`. |
| `WS-OPTION` | `9(02) VALUE 0` | `[app/cbl/COMEN01C.cbl:L46]` | local `int optionNumber` | Numeric copy of `WS-OPTION-X` after INSPECT. |
| `WS-IDX` | `S9(04) COMP VALUE ZEROS` | `[app/cbl/COMEN01C.cbl:L47]` | local `int idx` | Loop index for PERFORM VARYING in both option-trim search and menu-options construction. |
| `WS-MENU-OPT-TXT` | `X(40) VALUE SPACES` | `[app/cbl/COMEN01C.cbl:L48]` | local `String menuOptText` (length 40) | Workspace for STRING construction in BUILD-MENU-OPTIONS. |

COPY directives at `[app/cbl/COMEN01C.cbl:L50-L61]` pull in the
structural record layouts:

- `[app/cbl/COMEN01C.cbl:L50]` — `COPY COCOM01Y.` — `CARDDEMO-COMMAREA` structure (see Phase 2).
- `[app/cbl/COMEN01C.cbl:L51]` — `COPY COMEN02Y.` — static menu options table (see Phase 11).
- `[app/cbl/COMEN01C.cbl:L53]` — `COPY COMEN01.` — BMS symbolic map `COMEN1AI` / `COMEN1AO` (see Phase 7).
- `[app/cbl/COMEN01C.cbl:L55]` — `COPY COTTL01Y.` — screen title constants (CCDA-TITLE01, CCDA-TITLE02).
- `[app/cbl/COMEN01C.cbl:L56]` — `COPY CSDAT01Y.` — date/time work areas (WS-CURDATE-DATA decomposition; see Phase 6).
- `[app/cbl/COMEN01C.cbl:L57]` — `COPY CSMSG01Y.` — common messages including `CCDA-MSG-INVALID-KEY` at `[app/cpy/CSMSG01Y.cpy:L20-L21]`.
- `[app/cbl/COMEN01C.cbl:L58]` — `COPY CSUSR01Y.` — 80-byte `SEC-USER-DATA`; included but NEVER referenced; dead inclusion per AAP §0.7.1.
- `[app/cbl/COMEN01C.cbl:L60]` — `COPY DFHAID.` — named AID-key constants (`DFHENTER`, `DFHPF3`, etc.).
- `[app/cbl/COMEN01C.cbl:L61]` — `COPY DFHBMSCA.` — BMS attribute byte constants (`DFHGREEN`, etc.).

## Phase 2: LINKAGE SECTION and Commarea Structure

The `LINKAGE SECTION` declares `DFHCOMMAREA` with the standard
OCCURS-DEPENDING-ON `EIBCALEN` pattern at `[app/cbl/COMEN01C.cbl:L66-L69]`:

```
LINKAGE SECTION.
01  DFHCOMMAREA.
  05  LK-COMMAREA                           PIC X(01)
      OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
```

The base `CARDDEMO-COMMAREA` is defined in `app/cpy/COCOM01Y.cpy` and
is brought into COMEN01C via `COPY COCOM01Y.` at `[app/cbl/COMEN01C.cbl:L50]`.
The commarea sub-structure (per `[app/cpy/COCOM01Y.cpy:L19-L44]`) is:

| Group | Members |
|---|---|
| `CDEMO-GENERAL-INFO` | `CDEMO-FROM-TRANID` X(04), `CDEMO-FROM-PROGRAM` X(08), `CDEMO-TO-TRANID` X(04), `CDEMO-TO-PROGRAM` X(08), `CDEMO-USER-ID` X(08), `CDEMO-USER-TYPE` X(01), `CDEMO-PGM-CONTEXT` 9(01). |
| `CDEMO-CUSTOMER-INFO` | `CDEMO-CUST-ID` 9(09), `CDEMO-CUST-FNAME` X(25), `CDEMO-CUST-MNAME` X(25), `CDEMO-CUST-LNAME` X(25). |
| `CDEMO-ACCOUNT-INFO` | `CDEMO-ACCT-ID` 9(11), `CDEMO-ACCT-STATUS` X(01). |
| `CDEMO-CARD-INFO` | `CDEMO-CARD-NUM` 9(16). |
| `CDEMO-MORE-INFO` | `CDEMO-LAST-MAP` X(7), `CDEMO-LAST-MAPSET` X(7). |

88-level conditions (sealed-type sources per AAP §0.6.10):

- `CDEMO-USRTYP-ADMIN` VALUE `'A'` at `[app/cpy/COCOM01Y.cpy:L27]` → sealed permit `UserType.Admin`.
- `CDEMO-USRTYP-USER` VALUE `'U'` at `[app/cpy/COCOM01Y.cpy:L28]` → sealed permit `UserType.User`.
- `CDEMO-PGM-ENTER` VALUE `0` at `[app/cpy/COCOM01Y.cpy:L30]` → sealed permit `PgmContext.Enter`.
- `CDEMO-PGM-REENTER` VALUE `1` at `[app/cpy/COCOM01Y.cpy:L31]` → sealed permit `PgmContext.Reenter`.

In the Java translation per AAP §0.4.1, the `CardDemoCommarea` outer
record contains a `CdemoGeneralInfo` nested record carrying the
dispatch metadata. Pattern-matching switches on `UserType` and
`PgmContext` are exhaustive without `default` per AAP §0.7.4.

**EIBCALEN gating** (the key dispatch axis for MAIN-PARA):

- `EIBCALEN = 0`: First transaction entry without prior commarea — triggers L82-L84 path (XCTL to COSGN00C). The Java translation accepts a `null` commarea parameter for this case.
- `EIBCALEN > 0`: Subsequent entry via XCTL — triggers L86-L103 path (process commarea-driven re-entry).

Scenario 1 in `input_scenario.txt` simulates `EIBCALEN = 0`; scenarios
2-12 simulate `EIBCALEN > 0` with a populated commarea.

## Phase 3: MAIN-PARA Dispatch Logic

MAIN-PARA at `[app/cbl/COMEN01C.cbl:L75-L110]` is a single decision
tree gated on `EIBCALEN`, then on `CDEMO-PGM-CONTEXT`, then on
`EIBAID`. The five-step flow is:

1. **Initialization** at `[app/cbl/COMEN01C.cbl:L77-L80]`: `SET ERR-FLG-OFF TO TRUE`; `MOVE SPACES TO WS-MESSAGE` and to `ERRMSGO OF COMEN1AO` (the screen error field).

2. **EIBCALEN gate** at `[app/cbl/COMEN01C.cbl:L82-L84]`: `IF EIBCALEN = 0` → `MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM` → `PERFORM RETURN-TO-SIGNON-SCREEN`. This is the auto-redirect path for first-time entry without a commarea.

3. **ELSE branch** at `[app/cbl/COMEN01C.cbl:L86]`: `MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA` — copy the incoming commarea (length-truncated to the actual EIBCALEN size) into working-storage.

4. **First non-zero entry check** at `[app/cbl/COMEN01C.cbl:L87-L90]`: `IF NOT CDEMO-PGM-REENTER` (i.e., `CDEMO-PGM-CONTEXT = 0`, meaning `CDEMO-PGM-ENTER`) → `SET CDEMO-PGM-REENTER TO TRUE` (sets context to 1) → `MOVE LOW-VALUES TO COMEN1AO` (clear all output map fields) → `PERFORM SEND-MENU-SCREEN`. This is the "show the empty menu for the first time" path.

5. **ELSE branch** at `[app/cbl/COMEN01C.cbl:L91-L103]`: `PERFORM RECEIVE-MENU-SCREEN` (note: see Phase 6 — the RECEIVE-MENU-SCREEN paragraph is actually a dead-code paragraph in current sources because EIBAID and OPTIONI are pre-populated by the CICS dispatcher when the transaction starts; the PERFORM RECEIVE-MENU-SCREEN call at L92 is preserved verbatim per AAP §0.7.1), then `EVALUATE EIBAID`:
    - `WHEN DFHENTER` at `[app/cbl/COMEN01C.cbl:L94-L95]`: `PERFORM PROCESS-ENTER-KEY` (transfer to Phase 4 logic).
    - `WHEN DFHPF3` at `[app/cbl/COMEN01C.cbl:L96-L98]`: `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM` → `PERFORM RETURN-TO-SIGNON-SCREEN` (Phase 5 logic).
    - `WHEN OTHER` at `[app/cbl/COMEN01C.cbl:L99-L102]`: `MOVE 'Y' TO WS-ERR-FLG` → `MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE` → `PERFORM SEND-MENU-SCREEN`.

6. **Final EXEC CICS RETURN** at `[app/cbl/COMEN01C.cbl:L107-L110]`: `EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)`. Returns control to CICS with `TRANSID = CM00` set for the next interaction. This RETURN is reached only when an XCTL did NOT terminate the task (i.e., when SEND-MENU-SCREEN was invoked).

| EIBAID Constant | COBOL Path | Java AidKey Permit | Outcome |
|---|---|---|---|
| `DFHENTER` | `[app/cbl/COMEN01C.cbl:L94-L95]` | `AidKey.Enter` | Dispatch to PROCESS-ENTER-KEY (Phase 4). |
| `DFHPF3` | `[app/cbl/COMEN01C.cbl:L96-L98]` | `AidKey.PfKey03` | XCTL to COSGN00C (Phase 5). |
| All other AID keys (WHEN OTHER) | `[app/cbl/COMEN01C.cbl:L99-L102]` | All other permits of sealed `AidKey` | SEND-MENU-SCREEN with `CCDA-MSG-INVALID-KEY`. |

The Java translation uses pattern-matching `switch` on a sealed
`AidKey` hierarchy (Enter, Clear, Pa1, Pa2, PfKey01..PfKey12) per
AAP §0.6.10. The branch logic is exhaustive — every permit is named
explicitly — so NO `default` arm is needed per AAP §0.7.4.

**CRITICAL: COMEN01C handles ONLY 2 named AID keys (DFHENTER, DFHPF3) plus OTHER:**

- NO PF7 / PF8 handling (no pagination — contrast: COUSR00C handles PF7/PF8 for browse).
- NO PF4 / PF5 handling (no row-level actions — contrast: COUSR02C / COUSR03C).
- NO PF12 handling.
- NO PA1 / PA2 / CLEAR handling.
- The BMS map footer at `[app/bms/COMEN01.bms:L158-L162]` advertises only `ENTER=Continue  F3=Exit` (23 chars), matching the program's AID-key handling exactly.

## Phase 4: PROCESS-ENTER-KEY — Option Validation and Dispatch

PROCESS-ENTER-KEY at `[app/cbl/COMEN01C.cbl:L115-L165]` implements
the option-processing logic. Six sub-steps:

1. **Trailing-space-stripping search** at `[app/cbl/COMEN01C.cbl:L117-L121]` uses `PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1 UNTIL OPTIONI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1`. Finds the last non-space character position of OPTIONI (a PIC X(2) input field). `WS-IDX` ends at 1 (whole field is spaces) or at the position of the last non-space character.

2. **Substring extraction and INSPECT** at `[app/cbl/COMEN01C.cbl:L122-L123]`:
   - L122: `MOVE OPTIONI OF COMEN1AI(1:WS-IDX) TO WS-OPTION-X` — copy the trimmed prefix.
   - L123: `INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'` — convert remaining spaces to zeros (handles the JUST RIGHT layout where the value is right-aligned within `WS-OPTION-X`).

3. **Numeric conversion** at `[app/cbl/COMEN01C.cbl:L124-L125]`:
   - L124: `MOVE WS-OPTION-X TO WS-OPTION` — converts the alphanumeric field to the numeric `PIC 9(02)` field.
   - L125: `MOVE WS-OPTION TO OPTIONO OF COMEN1AO` — echo back to the output map.

4. **Validation gate** at `[app/cbl/COMEN01C.cbl:L127-L134]`: three failure conditions combined by `OR`:
   - `WS-OPTION IS NOT NUMERIC`
   - `WS-OPTION > CDEMO-MENU-OPT-COUNT` (where `CDEMO-MENU-OPT-COUNT = 10` per `[app/cpy/COMEN02Y.cpy:L21]`)
   - `WS-OPTION = ZEROS`

   On any failure: `MOVE 'Y' TO WS-ERR-FLG` and `MOVE 'Please enter a valid option number...' TO WS-MESSAGE` and `PERFORM SEND-MENU-SCREEN`. The verbatim error message `'Please enter a valid option number...'` includes a literal three-dot ASCII ellipsis (three U+002E period characters); NEVER substitute the single Unicode horizontal-ellipsis codepoint U+2026.

5. **Admin-only access guard** at `[app/cbl/COMEN01C.cbl:L136-L143]`: if `CDEMO-USRTYP-USER` AND `CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'`, then `SET ERR-FLG-ON TO TRUE`, `MOVE SPACES TO WS-MESSAGE`, `MOVE 'No access - Admin Only option... ' TO WS-MESSAGE`, `PERFORM SEND-MENU-SCREEN`. This branch is CURRENTLY DEAD CODE because all 10 populated `CDEMO-MENU-OPT-USRTYPE` values across `[app/cpy/COMEN02Y.cpy:L29]`, `[app/cpy/COMEN02Y.cpy:L35]`, `[app/cpy/COMEN02Y.cpy:L41]`, `[app/cpy/COMEN02Y.cpy:L47]`, `[app/cpy/COMEN02Y.cpy:L53]`, `[app/cpy/COMEN02Y.cpy:L59]`, `[app/cpy/COMEN02Y.cpy:L65]`, `[app/cpy/COMEN02Y.cpy:L72]`, `[app/cpy/COMEN02Y.cpy:L78]`, and `[app/cpy/COMEN02Y.cpy:L84]` are `'U'`. The dead path is translated faithfully per AAP §0.7.1 and flagged in `java/MIGRATION_NOTES.md`. The verbatim message `'No access - Admin Only option... '` includes ONE trailing space within the single-quoted literal — verified by direct read of `[app/cbl/COMEN01C.cbl:L140-L141]`.

6. **Dispatch decision** at `[app/cbl/COMEN01C.cbl:L145-L165]`: L145 `IF NOT ERR-FLG-ON` gates the dispatch on validation success. L146 `IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'` then routes to XCTL. L147-L155 set `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, and `CDEMO-PGM-CONTEXT` (two commented-out lines at L149-L150 preserved as Javadoc notes in the Java translation), then issue `EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) COMMAREA(CARDDEMO-COMMAREA)`. The XCTL transfers control unconditionally; the COBOL paragraph never returns once XCTL fires. L157-L164 are reached only when L146 is FALSE (target program name starts with `'DUMMY'`); they construct a "coming soon" message via `STRING 'This option ' DELIMITED BY SIZE, CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE, 'is coming soon ...' DELIMITED BY SIZE INTO WS-MESSAGE`. Per `[app/cbl/COMEN01C.cbl:L161]` the middle source field is delimited by `SPACE` (figurative constant) — STRING copies the menu name up to (but not including) the FIRST space encountered. The DUMMY path is CURRENTLY DEAD CODE because all 10 populated `CDEMO-MENU-OPT-PGMNAME` values start with `'CO'` (not `'DUMMY'`); slots 11/12 in the REDEFINES at `[app/cpy/COMEN02Y.cpy:L87-L92]` could theoretically hold `'DUMMY'` but are not populated. Translated faithfully per AAP §0.7.1.

The Java translation models the dispatch decision as a pattern-matching
switch on a sealed `MenuDispatchResult` hierarchy with permits
`Dispatch`, `ValidationError`, `AccessDenied`, and `ComingSoon` —
exhaustive without `default` per AAP §0.6.10 and AAP §0.7.4.

## Phase 5: RETURN-TO-SIGNON-SCREEN — Default Signon Redirect

RETURN-TO-SIGNON-SCREEN at `[app/cbl/COMEN01C.cbl:L170-L177]`:

```
RETURN-TO-SIGNON-SCREEN.
    IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
        MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
    END-IF
    EXEC CICS
        XCTL PROGRAM(CDEMO-TO-PROGRAM)
    END-EXEC.
```

Behavior:

- Default-case logic: if `CDEMO-TO-PROGRAM` is uninitialized (`LOW-VALUES`) or blank, populate with `'COSGN00C'` (the signon program).
- Used by two MAIN-PARA branches:
  - `[app/cbl/COMEN01C.cbl:L82-L84]` (EIBCALEN=0 first-time entry) — `CDEMO-TO-PROGRAM` is uninitialized so the default applies.
  - `[app/cbl/COMEN01C.cbl:L96-L98]` (DFHPF3) — `CDEMO-TO-PROGRAM` is explicitly set to `'COSGN00C'` before the `PERFORM`, so the default branch is skipped.
- `EXEC CICS XCTL` ends the current task without returning to the CICS RETURN at `[app/cbl/COMEN01C.cbl:L107-L110]`.

The Java translation models this as a private method on `CoMen01C`
that returns a typed `XctlTarget` record carrying the program name;
the caller dispatches via the `ProgramRegistry` per AAP §0.3.2.
There is NO COMMAREA on the XCTL at L175-L177 — the commarea is
implicitly retained from the caller's previous frame. The Java
translation preserves this behavior by NOT re-setting the commarea
parameter on the dispatch call.

## Phase 6: SEND/RECEIVE/POPULATE-HEADER/BUILD-OPTIONS Paragraphs

### 6.1 SEND-MENU-SCREEN

SEND-MENU-SCREEN at `[app/cbl/COMEN01C.cbl:L182-L194]` performs
`POPULATE-HEADER-INFO`, performs `BUILD-MENU-OPTIONS`, moves
`WS-MESSAGE` to `ERRMSGO OF COMEN1AO`, then issues `EXEC CICS SEND
MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE`. Always SEND
with `ERASE` (full-screen clear-and-redraw). The `MAP` and `MAPSET`
literals match the BMS definitions at `[app/bms/COMEN01.bms:L19]`
(`COMEN01 DFHMSD`) and `[app/bms/COMEN01.bms:L26]` (`COMEN1A DFHMDI`).

### 6.2 RECEIVE-MENU-SCREEN

RECEIVE-MENU-SCREEN at `[app/cbl/COMEN01C.cbl:L199-L207]` issues
`EXEC CICS RECEIVE MAP('COMEN1A') MAPSET('COMEN01') INTO(COMEN1AI)
RESP(WS-RESP-CD) RESP2(WS-REAS-CD)`. RECEIVE-MENU-SCREEN IS invoked
from MAIN-PARA at `[app/cbl/COMEN01C.cbl:L92]`. However, neither
`WS-RESP-CD` nor `WS-REAS-CD` is ever inspected after the RECEIVE —
the program does not branch on EIBRESP or RESP2. The RECEIVE serves
only to populate `COMEN1AI` from the inbound 3270 datastream; the
success/failure check is omitted. Translated faithfully per AAP §0.7.1;
the Java translation parses the inbound DTO and reads `EIBAID`
directly without a separate `FileStatus` check on the RECEIVE.

### 6.3 POPULATE-HEADER-INFO

POPULATE-HEADER-INFO at `[app/cbl/COMEN01C.cbl:L212-L231]` decomposes
`FUNCTION CURRENT-DATE` into the date/time fields shown in the
header rows. L214 `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA`
populates the date/time work area defined at `[app/cpy/CSDAT01Y.cpy:L18-L29]`.
L216-L219 copy `CCDA-TITLE01`, `CCDA-TITLE02`, `WS-TRANID`, and
`WS-PGMNAME` to the corresponding output map fields. L221-L225
decompose date components into the `WS-CURDATE-MM-DD-YY` group
field per `[app/cpy/CSDAT01Y.cpy:L30-L35]` (format `mm/dd/yy`).
L227-L231 decompose time components into `WS-CURTIME-HH-MM-SS`
per `[app/cpy/CSDAT01Y.cpy:L36-L41]` (format `hh:mm:ss`).

The Java translation uses `java.time.LocalDateTime` + `DateTimeFormatter`
per AAP §0.6.4 with patterns `MM/dd/yy` and `HH:mm:ss`. For test
determinism per AAP §0.6.6, the current `LocalDateTime` MUST be
injected via constructor or `ScopedValue` rather than read live;
the fixture capture procedure provides a fixed timestamp used by
both the COBOL run and the Java run.

### 6.4 BUILD-MENU-OPTIONS

BUILD-MENU-OPTIONS at `[app/cbl/COMEN01C.cbl:L236-L277]` iterates
from 1 to `CDEMO-MENU-OPT-COUNT` (= 10 per `[app/cpy/COMEN02Y.cpy:L21]`),
building `'NN. <name>'` strings (2-digit number, period, space, then
35-char menu name) via STRING DELIMITED BY SIZE into `WS-MENU-OPT-TXT`,
then routing each built string via `EVALUATE WS-IDX` to the
corresponding `OPTN00NO` output field. WHEN arms 1 through 10 cover
the populated indices; WHEN 11 at `[app/cbl/COMEN01C.cbl:L269-L270]`
and WHEN 12 at `[app/cbl/COMEN01C.cbl:L271-L272]` are present in the
EVALUATE but unreachable (loop terminates at `WS-IDX > 10`). The
`WHEN OTHER` → `CONTINUE` arm at `[app/cbl/COMEN01C.cbl:L273-L274]`
is similarly unreachable. Translated faithfully per AAP §0.7.1.

The Java translation models the EVALUATE as a pattern-matching switch
over a sealed `MenuRowIndex` hierarchy with exhaustive permits
(`Row01` through `Row12`) per AAP §0.6.10; NO `default` arm per
AAP §0.7.4. The dead `Row11`/`Row12` arms are translated as no-op
routings, preserving COBOL structural parity per AAP §0.7.1. The
menu table comes from the static record `MainMenuTable` in
`com.blitzy.carddemo.domain.menu` per AAP §0.3.1; see Phase 11.

## Phase 7: BMS Map COMEN1A Layout

The COMEN1A map is defined in `app/bms/COMEN01.bms` (167 lines) and
the symbolic copybook in `app/cpy-bms/COMEN01.CPY` (260 lines). The
map is `24 × 80` per `[app/bms/COMEN01.bms:L26-L28]` (`COMEN1A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)`).

| Field Name | Position (row, col) | Length | Attribute / Color | Direction | BMS Source |
|---|---|---|---|---|---|
| `'Tran:'` literal | (1,1) | 5 | ASKIP, NORM, BLUE | Output const | `[app/bms/COMEN01.bms:L29-L33]` |
| `TRNNAME` | (1,7) | 4 | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L34-L37]` — populated with `WS-TRANID` (`CM00`). |
| `TITLE01` | (1,21) | 40 | ASKIP, FSET, NORM, YELLOW | Output | `[app/bms/COMEN01.bms:L38-L41]` — populated with `CCDA-TITLE01` from `[app/cpy/COTTL01Y.cpy:L18-L19]`. |
| `'Date:'` literal | (1,65) | 5 | ASKIP, NORM, BLUE | Output const | `[app/bms/COMEN01.bms:L42-L46]` |
| `CURDATE` | (1,71) | 8 | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L47-L51]` — format `mm/dd/yy`. |
| `'Prog:'` literal | (2,1) | 5 | ASKIP, NORM, BLUE | Output const | `[app/bms/COMEN01.bms:L52-L56]` |
| `PGMNAME` | (2,7) | 8 | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L57-L60]` — populated with `WS-PGMNAME` (`COMEN01C`). |
| `TITLE02` | (2,21) | 40 | ASKIP, FSET, NORM, YELLOW | Output | `[app/bms/COMEN01.bms:L61-L64]` — populated with `CCDA-TITLE02`. |
| `'Time:'` literal | (2,65) | 5 | ASKIP, NORM, BLUE | Output const | `[app/bms/COMEN01.bms:L65-L69]` |
| `CURTIME` | (2,71) | 8 | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L70-L74]` — format `hh:mm:ss`. |
| `'Main Menu'` heading | (4,35) | 9 | ASKIP, BRT, NEUTRAL | Output const | `[app/bms/COMEN01.bms:L75-L79]` |
| `OPTN001` | (6,20) | 40 | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L80-L84]` |
| `OPTN002` | (7,20) | 40 | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L85-L89]` |
| `OPTN003`-`OPTN010` | (8-15,20) | 40 each | ASKIP, FSET, NORM, BLUE | Output | `[app/bms/COMEN01.bms:L90-L129]` — populated by BUILD-MENU-OPTIONS WHEN arms 3-10. |
| `OPTN011` | (16,20) | 40 | ASKIP, FSET, NORM, BLUE | Output (UNPOPULATED) | `[app/bms/COMEN01.bms:L130-L134]` — not populated by BUILD-MENU-OPTIONS. |
| `OPTN012` | (17,20) | 40 | ASKIP, FSET, NORM, BLUE | Output (UNPOPULATED) | `[app/bms/COMEN01.bms:L135-L139]` — not populated by BUILD-MENU-OPTIONS. |
| `'Please select an option :'` label | (20,15) | 25 | ASKIP, BRT, TURQUOISE | Output const | `[app/bms/COMEN01.bms:L140-L144]` |
| `OPTION` user-entry field | (20,41) | 2 | FSET, IC, NORM, NUM, UNPROT, HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO) | **Input** | `[app/bms/COMEN01.bms:L145-L149]` — ONLY UNPROT field. Symbolic names `OPTIONI` / `OPTIONO`. |
| Attribute marker (0-length) | (20,44) | 0 | ASKIP, NORM, GREEN | sentinel | `[app/bms/COMEN01.bms:L150-L153]` — terminates the OPTION field. |
| `ERRMSG` | (23,1) | 78 | ASKIP, BRT, FSET, RED | Output | `[app/bms/COMEN01.bms:L154-L157]` — carries the `WS-MESSAGE` error text. |
| Footer `'ENTER=Continue  F3=Exit'` | (24,1) | 23 | ASKIP, NORM, YELLOW | Output const | `[app/bms/COMEN01.bms:L158-L162]` — TWO spaces between `Continue` and `F3`. |

**STRUCTURAL INVARIANTS (must be preserved verbatim in the Java translation):**

- COMEN1A is exactly 24 × 80.
- The OPTION input field at (20,41) length 2 is the ONLY UNPROT (user-enterable) field on the map; all 12 OPTN00N fields and all label/title fields are output-only.
- NO PASSWD field anywhere (this map is not for credential entry — contrast COSGN00).
- The footer text exactly mirrors the 2 AID keys handled by MAIN-PARA at `[app/cbl/COMEN01C.cbl:L93-L103]`: ENTER and F3.
- The mapset name is `COMEN01` (`DFHMSD MAPSET=`) and the map name is `COMEN1A` (`DFHMDI MAP=`); the `EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01')` at `[app/cbl/COMEN01C.cbl:L189-L194]` uses these exact names.

The symbolic copybook at `app/cpy-bms/COMEN01.CPY` declares the COBOL
`01 COMEN1AI.` record at `[app/cpy-bms/COMEN01.CPY:L17]` and the
companion `01 COMEN1AO.` output record later in the same file. Field
suffixes follow CICS BMS conventions: `OPTN001L` (length, COMP S9(4)),
`OPTN001F` (flag), `OPTN001A` (attribute byte; REDEFINES the F field),
and `OPTN001I` / `OPTN001O` (the actual 40-character value). These
suffix conventions are documented at `[app/cpy-bms/COMEN01.CPY:L55-L60]`
for the OPTN001 group as the canonical example; OPTN002 through
OPTN012 follow the same shape. Verified by direct read of
`[app/cpy-bms/COMEN01.CPY:L17-L100]`.

## Phase 8: Capture Procedure Cross-Reference

To replace `stdout.txt` and `bms_output.txt` in this directory with
captured COBOL artifacts, follow the procedure documented in
`java/MIGRATION_NOTES.md` §1.6 (per AAP §0.7.5). The procedure
involves running COBOL COMEN01C under a z/OS or Hercules emulation
harness against the same `input_scenario.txt` interaction sequence,
capturing stdout via standard z/OS SYSPRINT redirection, and capturing
BMS SEND MAP output via a CICS trace-table capture or BMS print-image
utility. Until those captures are committed,
`com.blitzy.carddemo.tests.golden.CoMen01CGoldenTest` is `@Disabled`
per AAP §0.6.11.

Expected emptiness of `stdout.txt`: COMEN01C contains ZERO COBOL
`DISPLAY` statements (verified by reading all 282 lines of
`app/cbl/COMEN01C.cbl`), so any captured stdout content should be
only capture-script trace lines that the test class strips during
comparison. The expected canonical content of `stdout.txt` is
effectively empty (zero bytes), or a small fixed trace-prologue that
the harness normalizes away.

Expected ~6 BMS SEND MAP frames in `bms_output.txt`: scenarios that
XCTL away produce no pre-XCTL SEND MAP. Only scenarios that reach
`PERFORM SEND-MENU-SCREEN` before `EXEC CICS RETURN` at
`[app/cbl/COMEN01C.cbl:L107-L110]` produce a SEND MAP frame. Per the
12-scenario plan in Phase 10, scenarios 2, 7, 8, 9, 10, and 12 are
expected to produce SEND MAP output. Scenarios 1, 3, 4, 5, 6, and 11
produce XCTL-out only and no pre-XCTL SEND MAP.

## Phase 9: Test Class Status and @Disabled Mandate

The test class `com.blitzy.carddemo.tests.golden.CoMen01CGoldenTest`:

- MUST extend the harness base class `GoldenRecordTest` in `com.blitzy.carddemo.tests.golden`.
- MUST be annotated with JUnit 5 `@org.junit.jupiter.api.Disabled("Awaiting COBOL capture per java/MIGRATION_NOTES.md §1.6")` until both `stdout.txt` and `bms_output.txt` in this directory are replaced with captured COBOL artifacts per AAP §0.6.11.
- MUST register the program class as `com.blitzy.carddemo.application.menu.CoMen01C.class`.
- MUST register `inputFile()` returning the classpath path `golden/comen01c/expected/input_scenario.txt`.
- MUST register `expectedOutputs()` returning a map from `stdout.txt` → `golden/comen01c/expected/stdout.txt` and `bms_output.txt` → `golden/comen01c/expected/bms_output.txt`.
- MUST NOT declare a USRSEC-related auxiliary input. COMEN01C does NOT read USRSEC; NO `usrsec.txt` is present in this directory (verified absence — see Phase 12 and Phase 13.2).
- MUST follow the byte-for-byte parity assertion contract: actual stdout bytes EQUAL expected stdout bytes; actual concatenated BMS SEND MAP frames bytes EQUAL expected `bms_output.txt` bytes.

The `@Disabled` annotation's documentation message MUST cite
AAP §0.6.11 and reference `java/MIGRATION_NOTES.md` §1.6.

## Phase 10: Required Test Scenarios

The `input_scenario.txt` directive script enumerates 12 sequential
3270 interactions covering the full COMEN01C dispatch matrix.

| # | Scenario | AID Key | COBOL Path | Expected Outcome |
|---|---|---|---|---|
| 1 | Initial connection (EIBCALEN=0) | DFHENTER | `[app/cbl/COMEN01C.cbl:L82-L84]` | XCTL to COSGN00C (via RETURN-TO-SIGNON-SCREEN). |
| 2 | First non-zero entry (CDEMO-PGM-ENTER=0) | DFHENTER | `[app/cbl/COMEN01C.cbl:L87-L90]` | SEND MAP initial empty menu; CICS RETURN with TRANSID=CM00. |
| 3 | ENTER with option 01 | DFHENTER | `[app/cbl/COMEN01C.cbl:L146-L155]` | XCTL to `COACTVWC` per `[app/cpy/COMEN02Y.cpy:L25-L29]`. |
| 4 | ENTER with option 03 | DFHENTER | `[app/cbl/COMEN01C.cbl:L146-L155]` | XCTL to `COCRDLIC` per `[app/cpy/COMEN02Y.cpy:L37-L41]`. |
| 5 | ENTER with option 06 | DFHENTER | `[app/cbl/COMEN01C.cbl:L146-L155]` | XCTL to `COTRN00C` per `[app/cpy/COMEN02Y.cpy:L55-L59]`. |
| 6 | ENTER with option 10 | DFHENTER | `[app/cbl/COMEN01C.cbl:L146-L155]` | XCTL to `COBIL00C` per `[app/cpy/COMEN02Y.cpy:L80-L84]`. |
| 7 | ENTER with option 11 (out of range) | DFHENTER | `[app/cbl/COMEN01C.cbl:L127-L134]` | ERRMSG = `'Please enter a valid option number...'`. |
| 8 | ENTER with option 00 (zero) | DFHENTER | `[app/cbl/COMEN01C.cbl:L127-L134]` | ERRMSG = `'Please enter a valid option number...'`. |
| 9 | ENTER with non-numeric option (AB) | DFHENTER | `[app/cbl/COMEN01C.cbl:L127-L134]` | ERRMSG = `'Please enter a valid option number...'`. |
| 10 | ENTER with blank option | DFHENTER | `[app/cbl/COMEN01C.cbl:L117-L134]` | ERRMSG = `'Please enter a valid option number...'` (trimmed to empty → INSPECT replaces SPACE with `'0'` → `WS-OPTION = 00` → ZEROS check fires). |
| 11 | PF3 pressed | DFHPF3 | `[app/cbl/COMEN01C.cbl:L96-L98]` | XCTL to COSGN00C. |
| 12 | Invalid AID key (PF1) | DFHPF1 | `[app/cbl/COMEN01C.cbl:L99-L102]` | ERRMSG = `CCDA-MSG-INVALID-KEY` value (50-char literal from `[app/cpy/CSMSG01Y.cpy:L20-L21]`). |

Dead-code paths NOT exercised by these 12 scenarios:

- The admin-only block at `[app/cbl/COMEN01C.cbl:L136-L143]` is NOT exercised because all 10 populated `CDEMO-MENU-OPT-USRTYPE` values in `[app/cpy/COMEN02Y.cpy]` are `'U'` (User). The dead path is translated faithfully into Java per AAP §0.7.1; the unreachability is documented in `java/MIGRATION_NOTES.md` rather than corrected.
- The DUMMY "coming soon" path at `[app/cbl/COMEN01C.cbl:L157-L164]` is NOT exercised because all 10 populated `CDEMO-MENU-OPT-PGMNAME` values start with `'CO'` (not `'DUMMY'`). The dead path is translated faithfully into Java per AAP §0.7.1; the unreachability is documented in `java/MIGRATION_NOTES.md`.
- The OTHER arms at `[app/cbl/COMEN01C.cbl:L269-L274]` of BUILD-MENU-OPTIONS' EVALUATE (WHEN 11, WHEN 12, WHEN OTHER) are NOT reachable because the outer PERFORM VARYING terminates at `WS-IDX > CDEMO-MENU-OPT-COUNT` (= 10). Translated faithfully as no-op switch arms.

## Phase 11: CARDDEMO-MAIN-MENU-OPTIONS Table

The static menu table is defined in `app/cpy/COMEN02Y.cpy` at
`[app/cpy/COMEN02Y.cpy:L19-L92]`. Key landmarks:

- `[app/cpy/COMEN02Y.cpy:L21]` — `CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10`. Controls both the upper bound in PROCESS-ENTER-KEY's validation gate at `[app/cbl/COMEN01C.cbl:L128]` and the PERFORM VARYING termination in BUILD-MENU-OPTIONS at `[app/cbl/COMEN01C.cbl:L238-L239]`.
- `[app/cpy/COMEN02Y.cpy:L23-L84]` — 10 menu entries, one 4-field record per option (OPT-NUM, OPT-NAME, OPT-PGMNAME, OPT-USRTYPE).
- `[app/cpy/COMEN02Y.cpy:L87-L92]` — REDEFINES `CDEMO-MENU-OPTIONS-DATA` with `CDEMO-MENU-OPT(12)` containing `CDEMO-MENU-OPT-NUM PIC 9(02)`, `CDEMO-MENU-OPT-NAME PIC X(35)`, `CDEMO-MENU-OPT-PGMNAME PIC X(08)`, `CDEMO-MENU-OPT-USRTYPE PIC X(01)` — totaling 46 bytes × 12 = 552 bytes. The REDEFINES dimensions the array for 12 entries even though only 10 are populated by the surrounding FILLER VALUE clauses.

All 10 populated entries:

| # | COBOL Source | NUM | NAME (35 chars, padded) | PGMNAME | USRTYPE |
|---|---|---|---|---|---|
| 1  | `[app/cpy/COMEN02Y.cpy:L25-L29]` | `01` | `Account View                       ` | `COACTVWC` | `U` |
| 2  | `[app/cpy/COMEN02Y.cpy:L31-L35]` | `02` | `Account Update                     ` | `COACTUPC` | `U` |
| 3  | `[app/cpy/COMEN02Y.cpy:L37-L41]` | `03` | `Credit Card List                   ` | `COCRDLIC` | `U` |
| 4  | `[app/cpy/COMEN02Y.cpy:L43-L47]` | `04` | `Credit Card View                   ` | `COCRDSLC` | `U` |
| 5  | `[app/cpy/COMEN02Y.cpy:L49-L53]` | `05` | `Credit Card Update                 ` | `COCRDUPC` | `U` |
| 6  | `[app/cpy/COMEN02Y.cpy:L55-L59]` | `06` | `Transaction List                   ` | `COTRN00C` | `U` |
| 7  | `[app/cpy/COMEN02Y.cpy:L61-L65]` | `07` | `Transaction View                   ` | `COTRN01C` | `U` |
| 8  | `[app/cpy/COMEN02Y.cpy:L67-L72]` | `08` | `Transaction Add                    ` | `COTRN02C` | `U` |
| 9  | `[app/cpy/COMEN02Y.cpy:L74-L78]` | `09` | `Transaction Reports                ` | `CORPT00C` | `U` |
| 10 | `[app/cpy/COMEN02Y.cpy:L80-L84]` | `10` | `Bill Payment                       ` | `COBIL00C` | `U` |

Slot-8 lineage: the source at `[app/cpy/COMEN02Y.cpy:L69]` contains a
commented-out alternative literal `'Transaction Add (Admin Only)       '`
suggesting an earlier design where slot 8 was admin-only; the current
active value at `[app/cpy/COMEN02Y.cpy:L70]` is `'Transaction Add                    '`
with USRTYPE `'U'` at `[app/cpy/COMEN02Y.cpy:L72]`. The Java
translation preserves the active value verbatim per AAP §0.7.1; the
commented-out alternative is preserved as a Javadoc note on the
`MainMenuTable` entry.

The Java translation models the table as a static `MainMenuTable`
record in `com.blitzy.carddemo.domain.menu` per AAP §0.3.1, holding a
`List<MenuEntry>` of exactly 10 entries. Each `MenuEntry` is itself a
record with fields:

- `num` — `int` (2-digit ID).
- `name` — `String` with exact 35-character preservation including trailing space padding.
- `pgmName` — `String` with exact 8-character preservation.
- `usrType` — sealed `UserType { Admin, User }` per AAP §0.6.10.

The table is loaded at class-load time (e.g., from a static
initializer in `MainMenuTable`) and immutable thereafter. Slots 11 and
12 dimensioned by the REDEFINES at `[app/cpy/COMEN02Y.cpy:L87-L92]`
are absent from the Java collection — the Java translation does NOT
allocate dummy slots for the unreachable indices, and the pattern
matching switch in BUILD-MENU-OPTIONS' Java equivalent preserves
no-op arms for `Row11` and `Row12` as documented in Phase 6.4.

## Phase 12: Required Fixture Files

The `expected/` directory in this golden-record fixture contains
exactly **three** files plus this README. NO auxiliary files are
present (e.g., NO `usrsec.txt`, NO `usrsec_after.txt`, NO `acctdata.txt`).

1. **`README.md`** (THIS FILE): Authoritative 13-phase contract document. Source-grounded against COBOL line numbers verified by direct file read. Self-contained — does NOT require a COBOL run to validate its structure. Documents every aspect of the COMEN01C dispatch matrix, BMS map layout, menu options table, and Java translation invariants.

2. **`input_scenario.txt`**: 12-scenario harness directive script. Format: line-oriented; one directive per line; comments start with `#`. Documents 12 sequential 3270 interactions corresponding to the COMEN01C transaction flow. Source-grounded against COBOL line numbers (each scenario cites its COBOL path). Self-contained — does NOT require a COBOL run to validate its structure. Aligned with the 12 scenarios in Phase 10 of this README.

3. **`stdout.txt`**: Placeholder file containing comment lines that document the expected captured content. Expected captured content from a real COBOL run is **EMPTY** (no `DISPLAY` statement exists anywhere in COMEN01C source — verified by direct file read of all 282 lines). To be replaced by captured artifact per `java/MIGRATION_NOTES.md` §1.6 before `CoMen01CGoldenTest` can be `@Disabled`-removed.

4. **`bms_output.txt`**: Placeholder file containing comment lines that document the expected captured content. Expected captured content is ~6 BMS SEND MAP frames concatenated (scenarios 2, 7, 8, 9, 10, and 12 per Phase 10). To be replaced by captured artifact per `java/MIGRATION_NOTES.md` §1.6 before `CoMen01CGoldenTest` can be `@Disabled`-removed.

**Verified absences:**

- NO `usrsec.txt` file is present in this directory because COMEN01C does NOT read USRSEC. The `WS-USRSEC-FILE` declaration at `[app/cbl/COMEN01C.cbl:L39]` is a dead-code carry-over that is never referenced by any `EXEC CICS READ`, `STARTBR`, or related file-control statement in the program. The `COPY CSUSR01Y.` directive at `[app/cbl/COMEN01C.cbl:L58]` is similarly inert — the `SEC-USER-DATA` 01-level group is never referenced by any procedural statement.
- NO `usrsec_after.txt` file is present because COMEN01C does NOT write to any file at all. Verified by absence of any `EXEC CICS WRITE`, `REWRITE`, or `DELETE` anywhere in the 282-line source.

## Phase 13: Structural Invariants

### 13.1 BMS Map Invariants

- COMEN1A is exactly **24 × 80**.
- The OPTION field at `(20,41)` length 2 is the **ONLY UNPROT** (user-enterable) field on the map.
- 12 OPTN00N output fields at rows 6 through 17, column 20, length 40 each — but only OPTN001 through OPTN010 are populated by BUILD-MENU-OPTIONS at `[app/cbl/COMEN01C.cbl:L236-L277]`. OPTN011 and OPTN012 are defined in the BMS map at `[app/bms/COMEN01.bms:L130-L139]` and the symbolic copybook `app/cpy-bms/COMEN01.CPY` but remain empty (spaces) at runtime.
- `ERRMSG` at `(23,1)` length 78, color RED. Receives `WS-MESSAGE` content via L187: `MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO`.
- Footer at `(24,1)` is exactly `'ENTER=Continue  F3=Exit'` — 23 characters; note the TWO spaces between `Continue` and `F3`.
- NO PASSWD field anywhere on the map (verified absence — see `[app/bms/COMEN01.bms]` line-by-line scan).
- Mapset name: `COMEN01`; Map name: `COMEN1A` — these EXACT names appear in both the `EXEC CICS SEND` at `[app/cbl/COMEN01C.cbl:L189-L194]` and the (defined but inert-via-no-RESP-check) `EXEC CICS RECEIVE` at `[app/cbl/COMEN01C.cbl:L201-L207]`.

### 13.2 Static Table Invariants — NO FILE I/O

This is the most distinguishing invariant of COMEN01C vs. its siblings:

- Per AAP §0.7.1, `app/cpy/COMEN02Y.cpy` is the AUTHORITATIVE source of menu options.
- COMEN01C does NOT modify the menu options table at runtime — no MOVE statement targets any `CDEMO-MENU-OPT-*` field; the table is read-only.
- COMEN01C does NOT read any file from disk — there is NO `EXEC CICS READ`, NO `STARTBR`, NO `READNEXT`, NO `READPREV`, and NO `ENDBR` anywhere in the source. Verified by direct read of all 282 lines.
- COMEN01C does NOT write to any file — there is NO `EXEC CICS WRITE`, NO `REWRITE`, and NO `DELETE` anywhere in the source.
- Therefore, NO `usrsec.txt` fixture is required and NO USRSEC state assertions are exercised by `CoMen01CGoldenTest`. The fixture inventory in Phase 12 (3 files plus this README) is complete.
- The Java translation MUST load the menu options at class-load time (e.g., from a static initializer in `MainMenuTable` in `com.blitzy.carddemo.domain.menu`) and treat the result as immutable.
- `CDEMO-MENU-OPT-COUNT = 10`; `CDEMO-MENU-OPT` array dimensioned for 12 via the REDEFINES at `[app/cpy/COMEN02Y.cpy:L87-L92]`; slots 11 and 12 are uninitialized at runtime.

### 13.3 Java Mapping Invariants

- Pattern-matching `switch` on a sealed `AidKey` hierarchy for EIBAID dispatch per AAP §0.6.10; NO `default` arm per AAP §0.7.4. Permits: `Enter`, `Clear`, `Pa1`, `Pa2`, `PfKey01` through `PfKey12`.
- Pattern-matching `switch` on a sealed `PgmContext { Enter, Reenter }` for the first-entry detection at `[app/cbl/COMEN01C.cbl:L87]`; exhaustive without `default`.
- Pattern-matching `switch` on a sealed `UserType { Admin, User }` for the admin-only access guard at `[app/cbl/COMEN01C.cbl:L136-L143]` — translated faithfully even though currently unreachable per AAP §0.7.1.
- Pattern-matching `switch` on a sealed `MenuRowIndex { Row01..Row12 }` for the BUILD-MENU-OPTIONS dispatch per AAP §0.6.10; exhaustive without `default`.
- Date/time formatting (POPULATE-HEADER-INFO) uses `java.time.LocalDateTime` + `DateTimeFormatter` per AAP §0.6.4; patterns `MM/dd/yy` and `HH:mm:ss`. The current `LocalDateTime` MUST be injected via constructor or `ScopedValue` for test determinism per AAP §0.6.6.
- `ScopedValue` for any per-request context (NOT `ThreadLocal`) per AAP §0.6.6.
- All four verbatim error messages MUST appear EXACTLY in the Java code as in the COBOL source — see the Verbatim COBOL Message Catalog below.
- The `CoMen01C` class MUST carry a `@CobolProgram("COMEN01C")` Javadoc-style annotation citing the original `PROGRAM-ID`, source path `app/cbl/COMEN01C.cbl`, and translation date per AAP §0.7.1 traceability mandate.
- NO Spring container, NO ORM framework, NO relational database, NO batch framework — plain Java with constructor injection per AAP §0.6.12.

## Contrast Matrix: COMEN01C vs Sibling Menu Programs

| Aspect | COMEN01C (Main Menu) | COADM01C (Admin Menu) | COSGN00C (Signon) |
|---|---|---|---|
| Transaction ID | `CM00` | `CA00` | `CC00` |
| Audience | Regular users (`CDEMO-USRTYP-USER`) | Admins (`CDEMO-USRTYP-ADMIN`) | Both (pre-signon, no user-type yet) |
| Menu options table | `app/cpy/COMEN02Y.cpy` (10 entries) | `app/cpy/COADM02Y.cpy` (admin-specific entries) | N/A |
| File I/O | NONE (static table only) | NONE (static table only) | USRSEC READ (single record by RIDFLD) |
| AID keys handled | DFHENTER, DFHPF3, OTHER | (similar dispatch model) | DFHENTER, DFHPF3, OTHER |
| First-entry behavior (EIBCALEN=0) | XCTL to COSGN00C | XCTL to COSGN00C | Direct screen display |
| First non-zero entry (PGM-ENTER) | LOW-VALUES + SEND MAP COMEN1A | LOW-VALUES + SEND MAP COADM1A | Direct screen display |
| PF3 destination | XCTL to COSGN00C | XCTL to COSGN00C | (PF3 not handled; terminates session via OTHER) |
| Option dispatch | XCTL via `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)` | XCTL via `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)` | XCTL to COMEN01C or COADM01C based on USRTYPE |
| Admin-only blocking | Dead code (all options USRTYPE='U') | N/A (admin already authenticated) | N/A |
| DUMMY "coming soon" | Dead code (no DUMMY entries in current data) | (may differ — verify in sibling contract) | N/A |
| BMS map | COMEN1A in mapset COMEN01 | COADM1A in mapset COADM01 | COSGN0A in mapset COSGN00 |
| Verbatim error messages | 4 (option invalid, no access, coming soon, invalid key) | (similar 4) | (different — invalid creds, invalid key, thank-you) |
| Fixture files | 3 (no usrsec.txt) | 3 (no usrsec.txt) | 4 (includes usrsec.txt) |

## Verbatim COBOL Message Catalog

The Java implementation MUST emit these strings EXACTLY (Minimal
Change Clause per AAP §0.7.1). NEVER paraphrase, NEVER replace the
three ASCII period characters (U+002E) with the single Unicode
horizontal-ellipsis codepoint U+2026, NEVER add/remove punctuation, NEVER strip trailing padding spaces.

| # | Message (verbatim, single-quoted) | COBOL Source | Triggering Condition | Currently Reachable |
|---|---|---|---|---|
| 1 | `'Please enter a valid option number...'` | `[app/cbl/COMEN01C.cbl:L131-L132]` | Option not numeric, > 10, or = 0 | YES (scenarios 7, 8, 9, 10) |
| 2 | `'No access - Admin Only option... '` | `[app/cbl/COMEN01C.cbl:L140-L141]` | `CDEMO-USRTYP-USER` AND `CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'` | NO (dead code: all 10 options USRTYPE='U') |
| 3 | `'This option '` + name + `'is coming soon ...'` (STRING-constructed) | `[app/cbl/COMEN01C.cbl:L159-L163]` | `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'` (i.e., NOT not-equal-to-DUMMY at L146) | NO (dead code: no DUMMY entries) |
| 4 | `'Invalid key pressed. Please see below...         '` (50 chars total: 41 text + 9 trailing spaces) | `[app/cpy/CSMSG01Y.cpy:L20-L21]` | WHEN OTHER AID key (not ENTER, not PF3) | YES (scenario 12) |

Notes on message #3 STRING construction at `[app/cbl/COMEN01C.cbl:L159-L163]`:
the middle `CDEMO-MENU-OPT-NAME` operand is `DELIMITED BY SPACE`
(figurative constant), so STRING copies characters from the menu name
up to (but not including) the FIRST space encountered. The resulting
`WS-MESSAGE` value is `'This option '` + `<trimmed-name>` +
`'is coming soon ...'` with NO separating space between the trimmed
name and the trailing literal. Per AAP §0.7.1 the Java translation
MUST emit this exact composition, including the lack of separating
space. Messages 2 and 3 MUST still appear in the Java code even
though currently unreachable — translated faithfully per AAP §0.7.1.

For message #4 (`'Invalid key pressed. Please see below...         '`),
the field is declared `PIC X(50)` at `[app/cpy/CSMSG01Y.cpy:L20]`, so
total length is exactly 50 characters: 41 visible
(`Invalid key pressed. Please see below...`) + 9 trailing spaces —
verified by direct character count of `[app/cpy/CSMSG01Y.cpy:L21]`.

## Source Lineage

This contract derives from the following source files (all in the
preserved `app/` reference tree per AAP §0.2.2):

- `app/cbl/COMEN01C.cbl` (282 lines) — COBOL source; PROGRAM-ID at L23; paragraph inventory documented in Phases 3-6 (MAIN-PARA at L75-L110, PROCESS-ENTER-KEY at L115-L165, RETURN-TO-SIGNON-SCREEN at L170-L177, SEND-MENU-SCREEN at L182-L194, RECEIVE-MENU-SCREEN at L199-L207, POPULATE-HEADER-INFO at L212-L231, BUILD-MENU-OPTIONS at L236-L277).
- `app/bms/COMEN01.bms` (167 lines) — BMS map definition; mapset `COMEN01` at L19; map `COMEN1A` at L26; field-by-field layout in Phase 7.
- `app/cpy-bms/COMEN01.CPY` (260 lines) — symbolic map copybook; declares `01 COMEN1AI.` (input record) at L17 and `01 COMEN1AO.` (output record) later in the same file; field shape (length, flag, attribute, value) verified at L17-L100 for the canonical OPTN001 group.
- `app/cpy/COMEN02Y.cpy` (95 lines) — `CARDDEMO-MAIN-MENU-OPTIONS` static table; 10 active entries enumerated in Phase 11; REDEFINES dimensioned for 12 at L87-L92.
- `app/cpy/COCOM01Y.cpy` (47 lines) — `CARDDEMO-COMMAREA` with 88-level taxonomies for `UserType (Admin/User)` at L27-L28 and `PgmContext (Enter/Reenter)` at L30-L31.
- `app/cpy/CSMSG01Y.cpy` (24 lines) — common message constants including `CCDA-MSG-INVALID-KEY` at L20-L21.
- `app/cpy/COTTL01Y.cpy` (27 lines) — screen title constants (CCDA-TITLE01 at L18-L19, CCDA-TITLE02 at L20-L22) populated to TITLE01O / TITLE02O by POPULATE-HEADER-INFO.
- `app/cpy/CSDAT01Y.cpy` (58 lines) — `WS-DATE-TIME` work area with date/time field decomposition used in POPULATE-HEADER-INFO at L221-L231 of the COBOL source.

## Capture Procedure Cross-Reference

To replace `stdout.txt` and `bms_output.txt` with captured COBOL
artifacts, follow the procedure documented in
`java/MIGRATION_NOTES.md` §1.6 (per AAP §0.7.5). Until those captures
are committed, `com.blitzy.carddemo.tests.golden.CoMen01CGoldenTest`
is `@Disabled` per AAP §0.6.11.

## DO NOT Modify

```
This contract is the authoritative specification for the COMEN01C golden-record
test fixture. Any change to a fixture file, scenario count, message verbatim,
BMS map invariant, or menu options table MUST be accompanied by a corresponding
change to this README and re-captured stdout.txt and bms_output.txt artifacts.
See AAP section 0.7.1 (Minimal Change Clause) and AAP section 0.6.11 (PR gate).
```
