# COADM01C (Admin Menu, CA00) — Golden-Record Test Fixture Contract

## Phase 0: Header and Authority Cascade

This document is the AUTHORITATIVE binding contract for the COADM01C
golden-record test fixture, mediating between (a) the COBOL source
`app/cbl/COADM01C.cbl` (Admin Menu for admin users; CICS transaction
`CA00`), (b) the Java translation
`com.blitzy.carddemo.application.menu.CoAdm01C`, (c) the harness
`com.blitzy.carddemo.tests.golden.CoAdm01CGoldenTest`, and (d) the
three fixture files in this directory (`input_scenario.txt`,
`stdout.txt`, `bms_output.txt`). COADM01C dispatches to the
admin-only user-maintenance programs (`COUSR00C` list, `COUSR01C`
add, `COUSR02C` update, `COUSR03C` delete) per the static admin
menu options table in `app/cpy/COADM02Y.cpy`. It is reached only
when COSGN00C identifies the signed-on user as admin
(`CDEMO-USRTYP-ADMIN`) per `[app/cbl/COSGN00C.cbl:L230-L234]`. The
program has NO direct file I/O — it operates entirely on the static
admin menu options table and the CICS commarea.

Authority references (binding):

- AAP §0.2.1 — in-scope: `golden/coadm01c/` directory tree under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard.
- AAP §0.3.1 — harness directory convention: `input/` + `expected/` per program.
- AAP §0.4.1 — COADM01C → `com.blitzy.carddemo.application.menu.CoAdm01C`; BMS map → DTO records `CoAdm01Input` / `CoAdm01Output`; uses `COADM02Y` menu table for dispatch.
- AAP §0.6.4 — `java.time` mandate for date/time formatting in POPULATE-HEADER-INFO.
- AAP §0.6.6 — virtual threads, `ScopedValue` for context propagation (NOT `ThreadLocal`).
- AAP §0.6.10 — sealed `UserType { Admin, User }` from 88-level conditions in `app/cpy/COCOM01Y.cpy`; sealed `PgmContext { Enter, Reenter }` from 88-levels at `[app/cpy/COCOM01Y.cpy:L30-L31]`; sealed `AidKey` hierarchy.
- AAP §0.6.11 — golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern until COBOL capture committed.
- AAP §0.6.12 — architectural override: plain Java with constructor injection; NO Spring container; NO relational database; NO batch framework.
- AAP §0.7.1 — Minimal Change Clause; verbatim error messages; preserve sequencing and BMS layout.
- AAP §0.7.4 — pattern-matching exhaustiveness — NO `default` branches in `switch`; sealed `AidKey` hierarchy.
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.

Sibling pattern reference: this README adopts the 13-phase pattern
from `golden/comen01c/expected/README.md` (the closest sibling —
Main Menu counterpart for regular users). The 13-phase format is
preserved verbatim; content is re-grounded against COADM01C's
admin-only dispatch semantics. The test class
`com.blitzy.carddemo.tests.golden.CoAdm01CGoldenTest` is annotated
`@Disabled("Awaiting COBOL capture per java/MIGRATION_NOTES.md §1.6")`
until `stdout.txt` and `bms_output.txt` in this directory are
replaced with captured COBOL artifacts per AAP §0.6.11. Placeholder
content suffices for harness scaffolding but NOT for byte-level
parity assertions.

## Phase 1: COBOL Source — PROGRAM-ID and Working-Storage Variables

`PROGRAM-ID` is `COADM01C` at `[app/cbl/COADM01C.cbl:L23]`. `AUTHOR`
is `AWS` at `[app/cbl/COADM01C.cbl:L24]`. The file header comment at
`[app/cbl/COADM01C.cbl:L1-L6]` documents Function = "Admin Menu for
Admin users", Type = "CICS COBOL Program".

The complete inventory of working-storage variables under
`01 WS-VARIABLES` at `[app/cbl/COADM01C.cbl:L35]`:

| COBOL Name | PIC | Source Line | Java Equivalent | Notes |
|---|---|---|---|---|
| `WS-PGMNAME` | `X(08) VALUE 'COADM01C'` | `[app/cbl/COADM01C.cbl:L36]` | `CoAdm01C.PGMNAME` constant | Emitter program in commarea on XCTL; propagated to `CDEMO-FROM-PROGRAM`. |
| `WS-TRANID` | `X(04) VALUE 'CA00'` | `[app/cbl/COADM01C.cbl:L37]` | `CoAdm01C.TRANID` constant | CICS transaction ID — `CA00` (NOT `CM00` like COMEN01C). |
| `WS-MESSAGE` | `X(80) VALUE SPACES` | `[app/cbl/COADM01C.cbl:L38]` | local `String message` in use-case | Routed to `ERRMSGO` in SEND-MENU-SCREEN. |
| `WS-USRSEC-FILE` | `X(08) VALUE 'USRSEC  '` | `[app/cbl/COADM01C.cbl:L39]` | (DEAD) — see Phase 13.2 | Declared but NEVER referenced; COADM01C does NOT read USRSEC. Translated faithfully per AAP §0.7.1; flagged in `java/MIGRATION_NOTES.md`. |
| `WS-ERR-FLG` + 88-levels | `X(01) VALUE 'N'` | `[app/cbl/COADM01C.cbl:L40-L42]` | sealed `ErrorState { Off, On }` | 88-level `ERR-FLG-ON VALUE 'Y'` at L41; `ERR-FLG-OFF VALUE 'N'` at L42. |
| `WS-RESP-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COADM01C.cbl:L43]` | `int respCd` | CICS RESP code captured by RECEIVE. |
| `WS-REAS-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COADM01C.cbl:L44]` | `int reasCd` | CICS RESP2 code captured by RECEIVE. |
| `WS-OPTION-X` | `X(02) JUST RIGHT` | `[app/cbl/COADM01C.cbl:L45]` | `String optionX` | Right-justified text holder for input option before numeric conversion. |
| `WS-OPTION` | `9(02) VALUE 0` | `[app/cbl/COADM01C.cbl:L46]` | `int option` | Numeric option after INSPECT zero-padding and conversion. |
| `WS-IDX` | `S9(04) COMP VALUE ZEROS` | `[app/cbl/COADM01C.cbl:L47]` | `int idx` | Loop variable for strip loop and BUILD-MENU-OPTIONS. |
| `WS-ADMIN-OPT-TXT` | `X(40) VALUE SPACES` | `[app/cbl/COADM01C.cbl:L48]` | local `StringBuilder optTxt` | **CRITICAL**: COADM01C uses `WS-ADMIN-OPT-TXT`; sibling COMEN01C uses `WS-MENU-OPT-TXT` — this is the only working-storage naming difference. |

COPY directives at `[app/cbl/COADM01C.cbl:L50-L61]`:

- L50: `COPY COCOM01Y.` — CARDDEMO-COMMAREA
- L51: `COPY COADM02Y.` — static admin menu options table (4 entries)
- L53: `COPY COADM01.` — BMS symbolic map COADM1AI/COADM1AO
- L55: `COPY COTTL01Y.` — screen title constants (CCDA-TITLE01, CCDA-TITLE02)
- L56: `COPY CSDAT01Y.` — date/time work areas (WS-CURDATE-DATA, WS-CURTIME, WS-CURDATE-MM-DD-YY)
- L57: `COPY CSMSG01Y.` — common messages (CCDA-MSG-INVALID-KEY)
- L58: `COPY CSUSR01Y.` — SEC-USER-DATA (declared but NEVER referenced — dead inclusion preserved per AAP §0.7.1)
- L60: `COPY DFHAID.` — AID-key constants (DFHENTER, DFHPF3, etc.)
- L61: `COPY DFHBMSCA.` — BMS attribute constants (DFHGREEN, etc.)

## Phase 2: LINKAGE SECTION and Commarea Structure

LINKAGE SECTION at `[app/cbl/COADM01C.cbl:L66-L69]`:

```cobol
LINKAGE SECTION.
01  DFHCOMMAREA.
  05  LK-COMMAREA                           PIC X(01)
      OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
```

CARDDEMO-COMMAREA structure from `[app/cpy/COCOM01Y.cpy:L19-L44]`:
`05 CDEMO-GENERAL-INFO` (29 bytes; nested `CDEMO-FROM-TRANID PIC X(04)`,
`CDEMO-FROM-PROGRAM PIC X(08)`, `CDEMO-TO-TRANID PIC X(04)`,
`CDEMO-TO-PROGRAM PIC X(08)`, `CDEMO-USER-ID PIC X(08)`,
`CDEMO-USER-TYPE PIC X(01)` + 88-levels at L27-L28,
`CDEMO-PGM-CONTEXT PIC 9(01)` + 88-levels at L30-L31), followed by
`CDEMO-CUSTOMER-INFO` (84), `CDEMO-ACCOUNT-INFO` (12),
`CDEMO-CARD-INFO` (16), and `CDEMO-MORE-INFO` (14). Total commarea
length: ~196 bytes.

Java mapping per AAP §0.4.1, §0.6.10:

- `CardDemoCommarea` record in `com.blitzy.carddemo.domain.commarea` with nested `CdemoGeneralInfo` sub-record.
- Sealed `UserType { Admin, User }` from 88-levels at `[app/cpy/COCOM01Y.cpy:L27-L28]`.
- Sealed `PgmContext { Enter, Reenter }` from 88-levels at `[app/cpy/COCOM01Y.cpy:L30-L31]`.

EIBCALEN gating: `EIBCALEN = 0` triggers
`[app/cbl/COADM01C.cbl:L82-L84]` (XCTL to COSGN00C); `EIBCALEN > 0`
triggers `[app/cbl/COADM01C.cbl:L85-L105]` (process commarea-driven
entry).

**CRITICAL upstream invariant**: COADM01C is reached ONLY when
`CDEMO-USRTYP-ADMIN` is true. This is enforced by COSGN00C at
`[app/cbl/COSGN00C.cbl:L230-L234]` via `IF CDEMO-USRTYP-ADMIN EXEC
CICS XCTL PROGRAM('COADM01C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
ELSE EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(CARDDEMO-COMMAREA)
END-EXEC END-IF`. COADM01C itself does NOT verify
`CDEMO-USER-TYPE` — it trusts the upstream signon enforcement.
There is no in-program admin guard anywhere in `app/cbl/COADM01C.cbl`.

## Phase 3: MAIN-PARA Dispatch Logic

MAIN-PARA at `[app/cbl/COADM01C.cbl:L75-L110]` executes the following
sequence on every CICS invocation of transaction `CA00`:

1. L77-L80: Initialize — `SET ERR-FLG-OFF TO TRUE`; `MOVE SPACES` to `WS-MESSAGE` and `ERRMSGO OF COADM1AO`.
2. L82-L84: EIBCALEN gate — `IF EIBCALEN = 0` → `MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM` → `PERFORM RETURN-TO-SIGNON-SCREEN`.
3. L85-L86: ELSE branch — `MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA`.
4. L87-L90: First non-zero entry — `IF NOT CDEMO-PGM-REENTER` → `SET CDEMO-PGM-REENTER TO TRUE` → `MOVE LOW-VALUES TO COADM1AO` → `PERFORM SEND-MENU-SCREEN`.
5. L91-L92: **RECEIVE-MENU-SCREEN invocation** — `ELSE PERFORM RECEIVE-MENU-SCREEN`. **CRITICAL**: distinct from sibling COMEN01C whose RECEIVE paragraph is dead code; COADM01C actively invokes RECEIVE on the REENTER branch.
6. L93-L103: `EVALUATE EIBAID` — dispatch on AID key.
7. L107-L110: `EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)`.

AID key dispatch (from `EVALUATE EIBAID` at L93-L103):

| AID Key | COBOL Source Line | Handler Paragraph | Java Equivalent |
|---|---|---|---|
| `DFHENTER` | `[app/cbl/COADM01C.cbl:L94-L95]` | `PROCESS-ENTER-KEY` | sealed `case AidKey.Enter -> processEnterKey()` |
| `DFHPF3` | `[app/cbl/COADM01C.cbl:L96-L98]` | inline: MOVE 'COSGN00C' then `RETURN-TO-SIGNON-SCREEN` | sealed `case AidKey.PfKey03 -> returnToSignon("COSGN00C")` |
| `WHEN OTHER` | `[app/cbl/COADM01C.cbl:L99-L102]` | inline: set ERR-FLG-ON, move `CCDA-MSG-INVALID-KEY`, `SEND-MENU-SCREEN` | sealed exhaustive switch over `AidKey` covers all remaining permits |

Java translation: pattern-matching switch on sealed `AidKey`
hierarchy per AAP §0.6.10. NO `default` branch per AAP §0.7.4 —
exhaustiveness is the safety guarantee. Each remaining permit of
the sealed hierarchy (`Clear`, `Pa1`, `Pa2`, `PfKey01`, `PfKey02`,
`PfKey04`-`PfKey12`) is matched explicitly and maps to the WHEN
OTHER behavior.

**CRITICAL**: COADM01C handles ONLY 2 named AID keys (`DFHENTER`,
`DFHPF3`) + WHEN OTHER:

- NO PF7/PF8 (no pagination — contrast: COUSR00C uses these for paging)
- NO PF4/PF5 (no row-level actions)
- NO PF12, PA1, PA2, CLEAR named handlers
- BMS footer at `[app/bms/COADM01.bms:L158-L162]` advertises only `ENTER=Continue  F3=Exit`

## Phase 4: PROCESS-ENTER-KEY — Option Validation and Dispatch

PROCESS-ENTER-KEY at `[app/cbl/COADM01C.cbl:L115-L155]` is the only
program-internal logic path: it normalizes the user-entered option
text, validates it as numeric and in-range, and dispatches to the
selected user-maintenance program.

1. **Trailing-space-stripping loop** at `[app/cbl/COADM01C.cbl:L117-L121]` — `PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI OF COADM1AI BY -1 UNTIL OPTIONI OF COADM1AI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1`. Scans `OPTIONI` from the right; stops at the first non-space character or at index 1 (defensive lower bound).
2. **Substring + INSPECT** at `[app/cbl/COADM01C.cbl:L122-L123]` — `MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X` then `INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'` (internal spaces become zeros).
3. **Numeric conversion** at `[app/cbl/COADM01C.cbl:L124-L125]` — `MOVE WS-OPTION-X TO WS-OPTION` (PIC X(02) → PIC 9(02) coercion) then `MOVE WS-OPTION TO OPTIONO OF COADM1AO` (echo to output map).
4. **Validation gate** at `[app/cbl/COADM01C.cbl:L127-L134]`:

   ```cobol
   IF WS-OPTION IS NOT NUMERIC OR
      WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
      WS-OPTION = ZEROS
       MOVE 'Y'     TO WS-ERR-FLG
       MOVE 'Please enter a valid option number...' TO
                               WS-MESSAGE
       PERFORM SEND-MENU-SCREEN
   END-IF
   ```

   Three failure conditions: non-numeric, `> 4` (the
   `CDEMO-ADMIN-OPT-COUNT` value per `[app/cpy/COADM02Y.cpy:L20]`),
   or zero. Verbatim message:
   `'Please enter a valid option number...'` — literal three-dot
   `...` preserved per AAP §0.7.1 (NEVER Unicode HORIZONTAL ELLIPSIS U+2026).

5. **NO admin-only access guard** (CRITICAL DIFFERENCE from sibling COMEN01C):

   - COMEN01C contains an admin-only guard `IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'` (dead at runtime because all COMEN02Y entries use `'U'`).
   - COADM01C does NOT have this guard — admin access is upstream-enforced by COSGN00C at `[app/cbl/COSGN00C.cbl:L230-L234]`, and all 4 admin options are admin-only by definition.
   - COADM02Y entries have NO USRTYPE field at all (only NUM, NAME, PGMNAME — 45 bytes/entry vs 46 for COMEN02Y).

6. **Dispatch decision** at `[app/cbl/COADM01C.cbl:L137-L155]`:

   - L137: `IF NOT ERR-FLG-ON` — proceed only if validation passed.
   - L138: `IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'` — gate the XCTL on the first 5 chars of the program-name slot.
   - L139-L145: Populate `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-PGM-CONTEXT = 0`, then `EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)) COMMAREA(CARDDEMO-COMMAREA)`.
   - L147-L154: Otherwise (DUMMY "coming soon" STRING branch):

     ```cobol
     MOVE SPACES             TO WS-MESSAGE
     MOVE DFHGREEN           TO ERRMSGC  OF COADM1AO
     STRING 'This option '       DELIMITED BY SIZE
    *                CDEMO-ADMIN-OPT-NAME(WS-OPTION)
    *                                DELIMITED BY SIZE
             'is coming soon ...'   DELIMITED BY SIZE
        INTO WS-MESSAGE
     PERFORM SEND-MENU-SCREEN
     ```

   - **CRITICAL**: The `CDEMO-ADMIN-OPT-NAME` variable substitution is **COMMENTED OUT** at `[app/cbl/COADM01C.cbl:L150-L151]` — the resulting message is the simpler string `'This option is coming soon ...'` with NO variable substitution.
   - This path is CURRENTLY DEAD CODE because all 4 populated `CDEMO-ADMIN-OPT-PGMNAME` values start with `COUSR` (not `DUMMY`) per `[app/cpy/COADM02Y.cpy:L27]`, `[app/cpy/COADM02Y.cpy:L32]`, `[app/cpy/COADM02Y.cpy:L37]`, `[app/cpy/COADM02Y.cpy:L42]`. Per AAP §0.7.1, translate faithfully and flag in `java/MIGRATION_NOTES.md`.

Java translation: pattern-matching switch on sealed
`MenuDispatchResult` hierarchy (`Dispatch`, `ValidationError`,
`ComingSoon`) per AAP §0.6.10 and §0.7.4. NO `default` branch.

## Phase 5: RETURN-TO-SIGNON-SCREEN — Default Signon Redirect

RETURN-TO-SIGNON-SCREEN at `[app/cbl/COADM01C.cbl:L160-L167]`:
`IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES MOVE 'COSGN00C' TO
CDEMO-TO-PROGRAM END-IF EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
END-EXEC`.

Behaviors:

- Default-case logic at `[app/cbl/COADM01C.cbl:L162-L164]`: if `CDEMO-TO-PROGRAM` uninitialized or blank, populate with `'COSGN00C'`.
- Used by 2 MAIN-PARA branches: L82-L84 (EIBCALEN=0; uninitialized so default applies); L96-L98 (DFHPF3; explicitly sets `'COSGN00C'` first, default skipped).
- XCTL at `[app/cbl/COADM01C.cbl:L165-L167]` ends current task.

**CRITICAL**: COADM01C's RETURN-TO-SIGNON-SCREEN XCTL at
`[app/cbl/COADM01C.cbl:L165-L167]` does **NOT** pass a COMMAREA.
Compare:

- L165-L167 (XCTL to signon): `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) END-EXEC.` — **NO** COMMAREA clause.
- L142-L145 (XCTL to admin menu option program): `EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA) END-EXEC` — **HAS** COMMAREA.

This is a behaviorally significant difference: the signon transition
effectively starts a fresh session without carrying state.

Java translation: private method returning a typed `XctlTarget`
record carrying program name and an optional commarea reference;
dispatched via `ProgramRegistry` per AAP §0.3.2. The
`Optional<CardDemoCommarea>` slot is empty for the signon transition
and populated for menu-option dispatch.

## Phase 6: SEND/RECEIVE/POPULATE-HEADER/BUILD-OPTIONS Paragraphs

**SEND-MENU-SCREEN** at `[app/cbl/COADM01C.cbl:L172-L184]`: performs
`POPULATE-HEADER-INFO`, then `BUILD-MENU-OPTIONS`, then
`MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO`, then
`EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01') FROM(COADM1AO) ERASE END-EXEC`.

**RECEIVE-MENU-SCREEN** at `[app/cbl/COADM01C.cbl:L189-L197]`:
`EXEC CICS RECEIVE MAP('COADM1A') MAPSET('COADM01') INTO(COADM1AI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC`.
Invoked at `[app/cbl/COADM01C.cbl:L91-L92]` BEFORE the EVALUATE
EIBAID on the REENTER branch (NOT dead code, unlike COMEN01C's
RECEIVE paragraph).

**POPULATE-HEADER-INFO** at `[app/cbl/COADM01C.cbl:L202-L221]`:

- L204: `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA`
- L206-L207: `MOVE CCDA-TITLE01 TO TITLE01O`; `MOVE CCDA-TITLE02 TO TITLE02O` (both from `COTTL01Y`)
- L208-L209: `MOVE WS-TRANID TO TRNNAMEO` (`'CA00'`); `MOVE WS-PGMNAME TO PGMNAMEO` (`'COADM01C'`)
- L211-L215: Decompose `WS-CURDATE-DATA` → `WS-CURDATE-MM/DD/YY` → MOVE to `CURDATEO` (format `mm/dd/yy`)
- L217-L221: Decompose `WS-CURTIME` → `WS-CURTIME-HH/MM/SS` → MOVE to `CURTIMEO` (format `hh:mm:ss`)

**BUILD-MENU-OPTIONS** at `[app/cbl/COADM01C.cbl:L226-L263]`:
`PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT`,
then `STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE, '. '
DELIMITED BY SIZE, CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
INTO WS-ADMIN-OPT-TXT`, then `EVALUATE WS-IDX` with `WHEN 1` through
`WHEN 10` distributing `WS-ADMIN-OPT-TXT` to `OPTN001O`-`OPTN010O`
and `WHEN OTHER CONTINUE`.

- Loops `WS-IDX` from 1 BY 1 UNTIL `> CDEMO-ADMIN-OPT-COUNT` (= 4 per `[app/cpy/COADM02Y.cpy:L20]`).
- STRING concatenates `NUM` + `. ` + `NAME` into `WS-ADMIN-OPT-TXT` (length 40).
- `EVALUATE WS-IDX` distributes `WS-ADMIN-OPT-TXT` to `OPTN001O` through `OPTN010O` based on `WS-IDX`.
- Cases WHEN 5..10 (at lines 247-258) are DEAD CODE at runtime (loop never reaches them because count=4); `WHEN OTHER CONTINUE` (lines 259-260) is also dead.

Java translation:

- `BuildAdminMenuOptions` helper using static `AdminMenuTable` record from `com.blitzy.carddemo.domain.menu` per AAP §0.4.1.
- `EVALUATE WS-IDX` becomes pattern-matching switch over sealed `MenuRowIndex` hierarchy (`Row01`..`Row10`); NO `default` per AAP §0.7.4.
- Date/time: `LocalDateTime` injected via constructor or `ScopedValue` (NOT live `LocalDateTime.now()`) per AAP §0.6.4 and §0.6.6 — deterministic for byte-for-byte parity.
- `DateTimeFormatter.ofPattern("MM/dd/yy")` for `CURDATEO` and `DateTimeFormatter.ofPattern("HH:mm:ss")` for `CURTIMEO`.

## Phase 7: BMS Map COADM1A Layout — 24 × 80 Screen

Comprehensive table of every field on COADM1A (defined in
`app/bms/COADM01.bms`, 167 lines; symbolic copybook
`app/cpy-bms/COADM01.CPY`). Mapset name (`COADM01`) declared at
`[app/bms/COADM01.bms:L19]`; Map name (`COADM1A`) declared at
`[app/bms/COADM01.bms:L26]`; map size `(24,80)` at
`[app/bms/COADM01.bms:L28]`.

| Field Name | Position | Length | Color | Attribute | Direction | Notes | Source |
|---|---|---|---|---|---|---|---|
| `Tran:` label | (1,1) | 5 | BLUE | ASKIP,NORM | static | INITIAL='Tran:' | `[app/bms/COADM01.bms:L29-L33]` |
| `TRNNAME` | (1,7) | 4 | BLUE | ASKIP,FSET,NORM | output | Set to `'CA00'` by POPULATE-HEADER-INFO | `[app/bms/COADM01.bms:L34-L37]` |
| `TITLE01` | (1,21) | 40 | YELLOW | ASKIP,FSET,NORM | output | `CCDA-TITLE01` from COTTL01Y | `[app/bms/COADM01.bms:L38-L41]` |
| `Date:` label | (1,65) | 5 | BLUE | ASKIP,NORM | static | INITIAL='Date:' | `[app/bms/COADM01.bms:L42-L46]` |
| `CURDATE` | (1,71) | 8 | BLUE | ASKIP,FSET,NORM | output | INITIAL='mm/dd/yy'; populated mm/dd/yy | `[app/bms/COADM01.bms:L47-L51]` |
| `Prog:` label | (2,1) | 5 | BLUE | ASKIP,NORM | static | INITIAL='Prog:' | `[app/bms/COADM01.bms:L52-L56]` |
| `PGMNAME` | (2,7) | 8 | BLUE | ASKIP,FSET,NORM | output | Set to `'COADM01C'` | `[app/bms/COADM01.bms:L57-L60]` |
| `TITLE02` | (2,21) | 40 | YELLOW | ASKIP,FSET,NORM | output | `CCDA-TITLE02` from COTTL01Y | `[app/bms/COADM01.bms:L61-L64]` |
| `Time:` label | (2,65) | 5 | BLUE | ASKIP,NORM | static | INITIAL='Time:' | `[app/bms/COADM01.bms:L65-L69]` |
| `CURTIME` | (2,71) | 8 | BLUE | ASKIP,FSET,NORM | output | INITIAL='hh:mm:ss'; populated hh:mm:ss | `[app/bms/COADM01.bms:L70-L74]` |
| `Admin Menu` heading | (4,35) | 10 | NEUTRAL | ASKIP,BRT | static | INITIAL='Admin Menu' — DISTINGUISHER from COMEN01C 'Main Menu' (9 chars) | `[app/bms/COADM01.bms:L75-L79]` |
| `OPTN001` | (6,20) | 40 | BLUE | ASKIP,FSET,NORM | output | Populated by BUILD-MENU-OPTIONS for WS-IDX=1 → 'User List (Security)' | `[app/bms/COADM01.bms:L80-L84]` |
| `OPTN002` | (7,20) | 40 | BLUE | ASKIP,FSET,NORM | output | WS-IDX=2 → 'User Add (Security)' | `[app/bms/COADM01.bms:L85-L89]` |
| `OPTN003` | (8,20) | 40 | BLUE | ASKIP,FSET,NORM | output | WS-IDX=3 → 'User Update (Security)' | `[app/bms/COADM01.bms:L90-L94]` |
| `OPTN004` | (9,20) | 40 | BLUE | ASKIP,FSET,NORM | output | WS-IDX=4 → 'User Delete (Security)' | `[app/bms/COADM01.bms:L95-L99]` |
| `OPTN005` | (10,20) | 40 | BLUE | ASKIP,FSET,NORM | output | Unpopulated at runtime (CDEMO-ADMIN-OPT-COUNT=4) | `[app/bms/COADM01.bms:L100-L104]` |
| `OPTN006`-`OPTN012` | (11,20)-(17,20) | 40 each | BLUE | ASKIP,FSET,NORM | output | All unpopulated at runtime | `[app/bms/COADM01.bms:L105-L139]` |
| `Please select an option :` label | (20,15) | 25 | TURQUOISE | ASKIP,BRT | static | INITIAL='Please select an option :' | `[app/bms/COADM01.bms:L140-L144]` |
| `OPTION` input | (20,41) | 2 | (default) | FSET,IC,NORM,NUM,UNPROT | input | HILIGHT=UNDERLINE; JUSTIFY=(RIGHT,ZERO); ONLY UNPROT field | `[app/bms/COADM01.bms:L145-L149]` |
| 0-length GREEN marker | (20,44) | 0 | GREEN | ASKIP,NORM | n/a | Attribute byte for ERRMSGC color override | `[app/bms/COADM01.bms:L150-L153]` |
| `ERRMSG` | (23,1) | 78 | RED | ASKIP,BRT,FSET | output | Carries WS-MESSAGE content | `[app/bms/COADM01.bms:L154-L157]` |
| Footer | (24,1) | 23 | YELLOW | ASKIP,NORM | static | INITIAL='ENTER=Continue  F3=Exit' (two spaces between Continue and F3) | `[app/bms/COADM01.bms:L158-L162]` |

Symbolic copybook structures at `[app/cpy-bms/COADM01.CPY:L17]`
define `01 COADM1AI` (input view); the `OPTION` input field is
`OPTIONI PIC X(2)` at `[app/cpy-bms/COADM01.CPY:L132]`. The output
overlay `01 COADM1AO REDEFINES COADM1AI` at
`[app/cpy-bms/COADM01.CPY:L139]` exposes color/attribute/highlight
override slots and field-output components.

Java translation:

- COADM01-mapset symbolic structures map to Java records `CoAdm01Input` and `CoAdm01Output` in `com.blitzy.carddemo.application.menu`.
- Each field becomes a record component with appropriate type (`String` for X, `int` for 9-digit numeric).

**STRUCTURAL INVARIANTS** (must be highlighted prominently):

- COADM1A is exactly 24 × 80.
- OPTION at (20,41) length 2 is the ONLY UNPROT input field.
- 12 OPTN00No output fields at rows 6-17 column 20; only OPTN001O..OPTN004O are populated at runtime.
- NO PASSWD field anywhere.
- Footer advertises only the 2 named AID keys handled (ENTER, F3); WHEN OTHER produces an error.
- Mapset name `COADM01`; Map name `COADM1A` — exact names in both SEND MAP at `[app/cbl/COADM01C.cbl:L179-L184]` and RECEIVE MAP at `[app/cbl/COADM01C.cbl:L191-L197]`.
- Row 4 heading is `Admin Menu` (10 chars; structural distinguisher vs COMEN01C `Main Menu` 9 chars).

## Phase 8: Capture Procedure Cross-Reference

To replace `stdout.txt` and `bms_output.txt` with captured COBOL
artifacts, follow the capture procedure documented in
`java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Capture involves
running COBOL COADM01C under z/OS or Hercules emulation against the
deterministic directive stream in `input_scenario.txt`, capturing
the program's stdout via SYSPRINT redirection (expected to be empty
since COADM01C contains zero `DISPLAY` statements), and capturing
BMS SEND MAP frames via either CICS trace-table extraction or a BMS
print-image utility (expected to be ~6 frames for scenarios 2, 7,
8, 9, 10, 12). Until both captures are committed,
`CoAdm01CGoldenTest` remains `@Disabled` per AAP §0.6.11.

Note about expected EMPTY `stdout.txt`: COADM01C contains ZERO
`DISPLAY` statements (verified by full-file grep returning 0).
Captured stdout should consist only of trace lines that the test
class strips before byte-equality assertion.

Note about expected ~6 BMS SEND MAP frames in `bms_output.txt`:
Scenarios that XCTL away (1, 3, 4, 5, 6, 11) produce NO pre-XCTL
SEND MAP. Only scenarios completing via `EXEC CICS RETURN` (2, 7,
8, 9, 10, 12) produce SEND MAP output.

## Phase 9: Test Class Status and @Disabled Mandate

- `CoAdm01CGoldenTest` MUST live at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoAdm01CGoldenTest.java`.
- MUST extend `GoldenRecordTest` base class.
- MUST be annotated `@org.junit.jupiter.api.Disabled` with reason "Captured COBOL stdout.txt and bms_output.txt not yet committed; see MIGRATION_NOTES.md §1.6" until COBOL captures committed per AAP §0.6.11.
- MUST register `programClass()` → `com.blitzy.carddemo.application.menu.CoAdm01C.class`.
- MUST register `inputFile()` returning classpath path `golden/coadm01c/expected/input_scenario.txt`.
- MUST register `expectedOutputs()` returning a map containing `stdout.txt` and `bms_output.txt`.
- MUST NOT declare USRSEC auxiliary input — COADM01C does not read USRSEC; no `usrsec.txt` fixture exists in this directory.
- Byte-for-byte parity contract: actual stdout (after trace stripping) == expected stdout; actual concatenated BMS frames == expected bms_output.

## Phase 10: Required Test Scenarios

The harness exercises 12 scenarios that together cover every
reachable branch in `MAIN-PARA`, `PROCESS-ENTER-KEY`, and
`RETURN-TO-SIGNON-SCREEN`:

| # | Scenario | AID Key | COBOL Path | Expected Outcome |
|---|---|---|---|---|
| 1 | Initial connection (EIBCALEN=0) | DFHENTER | `[app/cbl/COADM01C.cbl:L82-L84]` | XCTL to COSGN00C (no commarea) |
| 2 | First non-zero entry (CDEMO-PGM-ENTER=0) | DFHENTER | `[app/cbl/COADM01C.cbl:L87-L90]` | SEND MAP initial admin menu; CICS RETURN |
| 3 | ENTER + option 01 | DFHENTER | `[app/cbl/COADM01C.cbl:L138-L145]` | XCTL to COUSR00C (user list) |
| 4 | ENTER + option 02 | DFHENTER | `[app/cbl/COADM01C.cbl:L138-L145]` | XCTL to COUSR01C (user add) |
| 5 | ENTER + option 03 | DFHENTER | `[app/cbl/COADM01C.cbl:L138-L145]` | XCTL to COUSR02C (user update) |
| 6 | ENTER + option 04 | DFHENTER | `[app/cbl/COADM01C.cbl:L138-L145]` | XCTL to COUSR03C (user delete) |
| 7 | ENTER + option 05 (out of range; > 4) | DFHENTER | `[app/cbl/COADM01C.cbl:L127-L134]` | ERRMSG = `'Please enter a valid option number...'` |
| 8 | ENTER + option 00 (zero) | DFHENTER | `[app/cbl/COADM01C.cbl:L127-L134]` | ERRMSG = `'Please enter a valid option number...'` |
| 9 | ENTER + non-numeric (AB) | DFHENTER | `[app/cbl/COADM01C.cbl:L127-L134]` | ERRMSG = `'Please enter a valid option number...'` |
| 10 | ENTER + blank option | DFHENTER | `[app/cbl/COADM01C.cbl:L117-L134]` | ERRMSG = `'Please enter a valid option number...'` |
| 11 | PF3 pressed | DFHPF3 | `[app/cbl/COADM01C.cbl:L96-L98]` | XCTL to COSGN00C (no commarea) |
| 12 | Invalid AID key (PF1) | DFHPF1 | `[app/cbl/COADM01C.cbl:L99-L102]` | ERRMSG = CCDA-MSG-INVALID-KEY |

Notes:

- Dead-code path at `[app/cbl/COADM01C.cbl:L147-L154]` (DUMMY "coming soon" branch) is NOT exercised — all 4 PGMNAMEs start with `COUSR`.
- Both dead paths (DUMMY branch and BUILD-MENU-OPTIONS WHEN 5..10) MUST still be translated faithfully into Java per AAP §0.7.1; unreachability documented in `java/MIGRATION_NOTES.md`.

## Phase 11: CARDDEMO-ADMIN-MENU-OPTIONS Table

The static admin menu options table from
`[app/cpy/COADM02Y.cpy:L19-L48]` is the authoritative source for
the admin menu entries. Structure:

- L19: `01 CARDDEMO-ADMIN-MENU-OPTIONS.`
- L20: `05 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4` — controls validation upper bound (Phase 4 step 4) and BUILD iteration (Phase 6).
- L22-L42: 4 menu entries via FILLER (each entry: NUM PIC 9(02) + NAME PIC X(35) + PGMNAME PIC X(08) = 45 bytes).
- L44-L48: REDEFINES `CDEMO-ADMIN-OPTIONS` with `CDEMO-ADMIN-OPT OCCURS 9 TIMES` (sub-fields: `CDEMO-ADMIN-OPT-NUM PIC 9(02)`, `CDEMO-ADMIN-OPT-NAME PIC X(35)`, `CDEMO-ADMIN-OPT-PGMNAME PIC X(08)`; 45 bytes × 9 = 405 bytes total).

All 4 populated entries:

| # | COBOL Source | NUM | NAME (35 chars) | PGMNAME (8 chars) |
|---|---|---|---|---|
| 1 | `[app/cpy/COADM02Y.cpy:L24-L27]` | `01` | `User List (Security)               ` | `COUSR00C` |
| 2 | `[app/cpy/COADM02Y.cpy:L29-L32]` | `02` | `User Add (Security)                ` | `COUSR01C` |
| 3 | `[app/cpy/COADM02Y.cpy:L34-L37]` | `03` | `User Update (Security)             ` | `COUSR02C` |
| 4 | `[app/cpy/COADM02Y.cpy:L39-L42]` | `04` | `User Delete (Security)             ` | `COUSR03C` |

**Critical structural differences from COMEN02Y** (document prominently):

- COADM02Y entries: 3 sub-fields (NUM + NAME + PGMNAME = 45 bytes/entry).
- COMEN02Y entries: 4 sub-fields (NUM + NAME + PGMNAME + USRTYPE = 46 bytes/entry).
- COADM02Y has NO USRTYPE field because admin-only is upstream-enforced by COSGN00C.
- REDEFINES dimensions differ: `OCCURS 9 TIMES` (COADM02Y) vs `OCCURS 12 TIMES` (COMEN02Y).

Java translation:

- `AdminMenuTable` static record in `com.blitzy.carddemo.domain.menu` per AAP §0.4.1.
- Holds `List<AdminMenuEntry>` of exactly 4 entries.
- Each `AdminMenuEntry` is a record with `int num`, `String name` (exact 35-char padded), `String pgmName` (exact 8-char).
- NO `usrType` field (matching COBOL structure).
- Loaded at class-load time, immutable; exposed as a singleton.

## Phase 12: Required Fixture Files in This Directory

The four fixture files in this directory:

1. **`README.md`** (THIS FILE): Authoritative 13-phase contract. Source-grounded against COBOL line numbers. Self-contained — does NOT require COBOL run to validate.
2. **`input_scenario.txt`**: 12-scenario harness directive script (line-oriented, directive grammar AID/FIELD/COMMAREA/EIBCALEN/EXPECT_*). Source-grounded against COBOL line numbers. Self-contained.
3. **`stdout.txt`** (CREATED as placeholder): comment header. Expected captured content is EMPTY (COADM01C has no DISPLAY statements). Replaced per `java/MIGRATION_NOTES.md` §1.6.
4. **`bms_output.txt`** (CREATED as placeholder): comment header. Expected captured content is ~6 BMS SEND MAP frames concatenated (scenarios 2, 7, 8, 9, 10, 12). Replaced per `java/MIGRATION_NOTES.md` §1.6.

Explicitly: NO `usrsec.txt` fixture (COADM01C does NOT read
USRSEC); NO `usrsec_after.txt` fixture (COADM01C does NOT write);
NO `acctdata.txt` / `carddata.txt` / `.bin` fixtures (COADM01C has
zero VSAM I/O).

## Phase 13: Structural Invariants

### 13.1 BMS Map Invariants

- COADM1A is exactly 24 × 80.
- OPTION at (20,41) length 2 is the ONLY UNPROT input field.
- 12 OPTN00No fields at rows 6-17 column 20; only OPTN001O..OPTN004O populated by BUILD-MENU-OPTIONS at `[app/cbl/COADM01C.cbl:L226-L263]`.
- ERRMSG at (23,1) length 78 RED ATTRB=(ASKIP,BRT,FSET).
- Footer at (24,1) length 23 YELLOW: `ENTER=Continue  F3=Exit` (two spaces between `Continue` and `F3`).
- NO PASSWD field.
- Mapset name `COADM01`; Map name `COADM1A` — exact names in both SEND MAP at `[app/cbl/COADM01C.cbl:L179-L184]` and RECEIVE MAP at `[app/cbl/COADM01C.cbl:L191-L197]`.
- Row 4 heading `Admin Menu` (10 chars at `[app/bms/COADM01.bms:L75-L79]`; structural distinguisher vs COMEN01C `Main Menu` 9 chars).
- Symbolic input view `01 COADM1AI` declared at `[app/cpy-bms/COADM01.CPY:L17]`; output overlay `01 COADM1AO` declared at `[app/cpy-bms/COADM01.CPY:L139]`.

### 13.2 Static Table Invariants (CRITICAL — NO FILE I/O)

- Per AAP §0.7.1, `app/cpy/COADM02Y.cpy` is the AUTHORITATIVE menu options source.
- COADM01C does NOT modify menu options at runtime — the table is read-only and loaded at compile/copybook expansion time.
- COADM01C does NOT read any file from disk — NO `STARTBR`, NO `READ`, NO `READNEXT`, NO `READPREV` anywhere in `app/cbl/COADM01C.cbl`.
- Therefore NO `usrsec.txt` fixture required and NO USRSEC state assertions.
- Java translation MUST load menu options at class-load time (static initializer in `AdminMenuTable`); immutable.
- `CDEMO-ADMIN-OPT-COUNT = 4`; `CDEMO-ADMIN-OPT` array dimensioned for 9 via REDEFINES; slots 5..9 are uninitialized at runtime.
- `WS-USRSEC-FILE` declaration at `[app/cbl/COADM01C.cbl:L39]` and `COPY CSUSR01Y.` directive at `[app/cbl/COADM01C.cbl:L58]` are both DEAD declarations preserved verbatim per AAP §0.7.1.

### 13.3 Java Mapping Invariants

- Pattern-matching switch on sealed `AidKey` hierarchy per AAP §0.6.10; NO `default` per AAP §0.7.4.
- Pattern-matching switch on sealed `PgmContext { Enter, Reenter }` for first-entry detection per AAP §0.6.10.
- COADM01C does NOT use sealed `UserType { Admin, User }` for any in-program check (unlike COMEN01C's dead admin-only guard); however COSGN00C uses it at `[app/cbl/COSGN00C.cbl:L230-L234]` to dispatch to COADM01C vs COMEN01C.
- Date/time formatting (POPULATE-HEADER-INFO) uses `java.time.LocalDateTime` + `DateTimeFormatter` per AAP §0.6.4; injected via constructor or `ScopedValue` per AAP §0.6.6 (NOT `LocalDateTime.now()` live calls; deterministic for byte-for-byte parity).
- `ScopedValue` for per-request context (NOT `ThreadLocal`) per AAP §0.6.6.
- All 3 verbatim error/info messages MUST appear EXACTLY in Java code (see Verbatim COBOL Message Catalog below).
- `CoAdm01C` MUST carry `@CobolProgram("COADM01C")` Javadoc annotation citing PROGRAM-ID, source path `app/cbl/COADM01C.cbl`, and translation date per AAP §0.7.1.
- NO Spring container, NO ORM, NO relational driver, NO batch framework — plain Java with constructor injection per AAP §0.6.12.

## Contrast Matrix: COADM01C vs Sibling Menu Programs

| Aspect | COADM01C (Admin Menu) | COMEN01C (Main Menu) | COSGN00C (Signon) |
|---|---|---|---|
| Transaction ID | CA00 | CM00 | CC00 |
| Audience | Admins (CDEMO-USRTYP-ADMIN) | Regular users (CDEMO-USRTYP-USER) | Both (pre-signon) |
| Menu options table | `COADM02Y.cpy` (4 entries; NO USRTYPE field) | `COMEN02Y.cpy` (10 entries; has USRTYPE) | N/A |
| Entry structure | 45 bytes (NUM+NAME+PGMNAME) | 46 bytes (NUM+NAME+PGMNAME+USRTYPE) | N/A |
| REDEFINES dimension | OCCURS 9 TIMES | OCCURS 12 TIMES | N/A |
| File I/O | NONE (static table only) | NONE (static table only) | USRSEC READ |
| AID keys handled | DFHENTER, DFHPF3, OTHER | DFHENTER, DFHPF3, OTHER | DFHENTER, DFHPF3, OTHER |
| First-entry (EIBCALEN=0) | XCTL to COSGN00C | XCTL to COSGN00C | Direct screen display |
| First non-zero entry | LOW-VALUES + SEND MAP COADM1A | LOW-VALUES + SEND MAP COMEN1A | Direct screen display |
| RECEIVE-MENU-SCREEN | Invoked on REENTER (L91-L92) | NOT invoked (dead code) | Invoked |
| PF3 destination | XCTL to COSGN00C (NO COMMAREA at L165-L167) | XCTL to COSGN00C (HAS COMMAREA) | (terminates session) |
| Option dispatch | XCTL via CDEMO-ADMIN-OPT-PGMNAME | XCTL via CDEMO-MENU-OPT-PGMNAME | XCTL to COMEN01C or COADM01C |
| Admin-only blocking | Not needed (upstream-enforced) | Dead code (all options USRTYPE='U') | Performed via USRTYP dispatch |
| DUMMY "coming soon" | Dead code (no DUMMY entries) | Dead code (no DUMMY entries) | N/A |
| BMS map heading (row 4) | `Admin Menu` (10 chars) | `Main Menu` (9 chars) | (different title) |
| BMS map name | COADM1A in mapset COADM01 | COMEN1A in mapset COMEN01 | COSGN0A in mapset COSGN00 |
| Verbatim error messages | 3 (option invalid, coming soon, invalid key) | 4 (incl. admin-only) | (different — invalid creds, invalid key) |
| Workspace var name | WS-ADMIN-OPT-TXT | WS-MENU-OPT-TXT | N/A |

## Verbatim COBOL Message Catalog

The following verbatim error/info message strings are emitted by
COADM01C. Java implementation MUST emit these strings EXACTLY per
AAP §0.7.1 (Minimal Change Clause). NEVER paraphrase. NEVER replace
literal `...` with Unicode HORIZONTAL ELLIPSIS U+2026. NEVER add/remove
punctuation. NEVER strip trailing padding.

| # | Message | COBOL Source | Triggering Condition | Currently Reachable |
|---|---|---|---|---|
| 1 | `'Please enter a valid option number...'` | `[app/cbl/COADM01C.cbl:L131-L132]` | Option not numeric, > 4, or = 0 | YES (scenarios 7, 8, 9, 10) |
| 2 | `'This option is coming soon ...'` (STRING-constructed; `CDEMO-ADMIN-OPT-NAME` commented out at L150-L151) | `[app/cbl/COADM01C.cbl:L149-L153]` | `CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'` | NO (dead code: no DUMMY entries) |
| 3 | `'Invalid key pressed. Please see below...         '` (49 chars between quotes: 37-char message + literal `...` + 9 trailing ASCII spaces; PIC X(50) at runtime) | `[app/cpy/CSMSG01Y.cpy:L20-L21]` | WHEN OTHER AID key | YES (scenario 12) |

Message 2 MUST appear in Java even though unreachable per AAP §0.7.1;
unreachability documented in `java/MIGRATION_NOTES.md`.

**Note on Message 2**: Unlike COMEN01C where the STRING construction
includes a NAME-field substitution at runtime, COADM01C has the
`CDEMO-ADMIN-OPT-NAME` substitution line COMMENTED OUT at
`[app/cbl/COADM01C.cbl:L150-L151]`. The resulting message is
therefore the simpler string `'This option is coming soon ...'`
with NO variable substitution.

## Source Lineage

- `app/cbl/COADM01C.cbl` (269 lines) — COBOL source; PROGRAM-ID at L23; paragraph inventory documented in phases 3-6.
- `app/bms/COADM01.bms` (167 lines) — BMS map; field layout in Phase 7.
- `app/cpy-bms/COADM01.CPY` (261 lines) — symbolic map copybook (`01 COADM1AI` at L17, `01 COADM1AO REDEFINES COADM1AI` at L139).
- `app/cpy/COADM02Y.cpy` (52 lines) — `CARDDEMO-ADMIN-MENU-OPTIONS` static table; 4 active entries documented in Phase 11.
- `app/cpy/COCOM01Y.cpy` (47 lines) — `CARDDEMO-COMMAREA` with 88-level taxonomies for `UserType` and `PgmContext`.
- `app/cpy/CSMSG01Y.cpy` (25 lines) — common message constants; `CCDA-MSG-INVALID-KEY` at L20-L21.
- `app/cpy/COTTL01Y.cpy` — screen title constants (`CCDA-TITLE01`, `CCDA-TITLE02`).
- `app/cpy/CSDAT01Y.cpy` — `WS-CURDATE-DATA` structure for date/time decomposition.
- `app/cbl/COSGN00C.cbl:L230-L234` — upstream XCTL to COADM01C from admin signon path.

## Capture Procedure Cross-Reference

To replace `stdout.txt` and `bms_output.txt` with captured COBOL
artifacts, follow the procedure documented in
`java/MIGRATION_NOTES.md` §1.6 (per AAP §0.7.5). Until both files
are committed with captured content, `CoAdm01CGoldenTest` MUST
remain `@Disabled` per AAP §0.6.11.

## DO NOT Modify Footer

```
This contract is the authoritative specification for the COADM01C golden-record
test fixture. Any change to a fixture file, scenario count, message verbatim,
BMS map invariant, or menu options table MUST be accompanied by a corresponding
change to this README and re-captured stdout.txt and bms_output.txt artifacts.
See AAP §0.7.1 (Minimal Change Clause) and AAP §0.6.11 (PR gate).
```
