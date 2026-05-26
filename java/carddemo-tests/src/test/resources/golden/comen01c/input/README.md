# COMEN01C Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files. COMEN01C
is the **regular-user Main Menu controller** (CICS transaction `CM00`), translated to Java
`com.blitzy.carddemo.application.menu.CoMen01C`. COMEN01C is reached AFTER successful signon for
non-admin users; admins reach `COADM01C` instead (the `SEC-USR-TYPE = 'A'` branch of COSGN00C),
while the regular-user signon branch (`SEC-USR-TYPE = 'U'`) `EXEC CICS XCTL`s to COMEN01C.
COMEN01C is a **menu-navigation-only program** with NO VSAM file I/O — it neither reads from nor
writes to USRSEC, ACCTDAT, CARDDAT, CXACAIX, CUSTDAT, or any other dataset. Its only inputs are:
(1) the BMS map `COMEN1A` (synthesized 3270 keystrokes encoded in
`../expected/input_scenario.txt`), (2) the `CARDDEMO-COMMAREA` (synthesized commarea state encoded
in `../expected/input_scenario.txt`), and (3) the compiled-in static `COMEN02Y` menu-options
table (not a runtime fixture). Therefore NO source-side `app/data/ASCII/*.txt` fixture is
consumed — confirmed by AAP §0.4.1 which enumerates exactly 9 ASCII fixtures (acctdata, carddata,
cardxref, custdata, dailytran, discgrp, tcatbal, trancatg, trantype), none of which are read by
COMEN01C. The deterministic test scenario file (`input_scenario.txt`) lives in the sibling
`../expected/` folder, because the consuming Java test class `CoMen01CGoldenTest` resolves all
fixture paths through the base class helper `resolveExpectedOutputPath("comen01c", ...)` →
`src/test/resources/golden/comen01c/expected/`. This conventional `input/` subdirectory (per AAP
§0.6.11 harness convention) is preserved only to make the absence of fixture data files
**explicit and discoverable**, mirroring the sibling precedents in `golden/cosgn00c/`,
`golden/cousr00c/`, `golden/cousr01c/`, and `golden/csutldtc/`.

## Why this folder is documentation-only

1. COMEN01C is an **online CICS pseudo-conversational program** (transaction `CM00`). Its user-facing input arrives via the BMS map `COMEN1A` defined in `app/bms/COMEN01.bms` — specifically ONE editable input field: `OPTION PIC X(2)` (numeric, with `FSET, IC, NORM, NUM, UNPROT, HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO)` attributes at `app/bms/COMEN01.bms:L145-L149`) — plus AID-key dispatch (`DFHENTER`, `DFHPF3`). Source working-storage at `app/cbl/COMEN01C.cbl:L36-L48` declares `WS-PGMNAME = 'COMEN01C'` (L36), `WS-TRANID = 'CM00'` (L37), `WS-OPTION-X PIC X(02) JUST RIGHT` (L45), `WS-OPTION PIC 9(02) VALUE 0` (L46).
2. Inputs are not file-based fixtures sourced from `app/data/ASCII/*.txt` (per AAP §0.4.1, no ASCII fixture corresponds to COMEN01C's working set). The deterministic test scenario is encoded as a **synthesized scenario script** plus synthesized commarea state — both **test-owned scaffolding** authored alongside the captured expected outputs in `../expected/`.
3. Per the sibling precedent established by `golden/cosgn00c/input/`, `golden/cousr00c/input/`, and `golden/csutldtc/input/`, the conventional `input/` subdirectory is preserved as a documentation marker even though no fixture data files reside there.
4. The harness convention from AAP §0.6.11 mandates the `input/` + `expected/` pairing per program for discoverability and parallel structure across all 28 program test folders.
5. The consuming test class `CoMen01CGoldenTest` resolves all fixture paths through the base class helper `resolveExpectedOutputPath(programDir, fileName)`, which maps every path to `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely: `inputFile()` returns `resolveExpectedOutputPath("comen01c", "input_scenario.txt")` → `../expected/input_scenario.txt`. All overrides route to `../expected/`; none routes to this `input/` folder.

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map input field (1 editable: OPTION)

| Field                                                                          | Position                             | Length        | Attribute                                                             | Direction | Notes                                                                                                    |
|--------------------------------------------------------------------------------|--------------------------------------|---------------|-----------------------------------------------------------------------|-----------|----------------------------------------------------------------------------------------------------------|
| `OPTION`                                                                       | (20,41)                              | 2 chars       | `FSET, IC, NORM, NUM, UNPROT, HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO)` | Input     | Sole editable field; numeric; right-justified zero-filled (typed `5` → buffer `05`)                      |
| `ERRMSG`                                                                       | (23,1)                               | 78 chars      | `RED, ASKIP, BRT, FSET`                                               | Output    | Error/info message                                                                                       |
| 12 menu-option output fields `OPTN001`..`OPTN012`                              | rows 6-17, col 20                    | 40 chars each | `BLUE, ASKIP, FSET, NORM`                                             | Output    | Only 10 populated by `BUILD-MENU-OPTIONS` (count = 10 per `COMEN02Y` L21); fields 11 and 12 remain blank |
| Header fields `TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`, `TITLE02`, `CURTIME` | row 1 col 7/21/71, row 2 col 7/21/71 | varies        | output                                                                | Output    | Populated by `POPULATE-HEADER-INFO` from `FUNCTION CURRENT-DATE`                                         |

**CRITICAL DISTINGUISHER**: `COMEN1A` has NO `PASSWD` field (verified absence — the entire BMS
file at `app/bms/COMEN01.bms` defines only `OPTION` as editable). Contrast `COSGN0A` which HAS
`USERID` + `PASSWD` fields; contrast `COUSR0A` which has `USRIDIN` + 10 `SEL` row-selectors. The
structural input surface of COMEN01C is the **simplest** among the menu/signon family — just one
2-char numeric option.

### CICS pseudo-conversational dispatch (2 named AID keys + WHEN OTHER)

The `EVALUATE EIBAID` block at `app/cbl/COMEN01C.cbl:L91-L103` routes each AID key to its
handler:

```cobol
            ELSE
                PERFORM RECEIVE-MENU-SCREEN
                EVALUATE EIBAID
                    WHEN DFHENTER
                        PERFORM PROCESS-ENTER-KEY
                    WHEN DFHPF3
                        MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
                        PERFORM RETURN-TO-SIGNON-SCREEN
                    WHEN OTHER
                        MOVE 'Y'                       TO WS-ERR-FLG
                        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                        PERFORM SEND-MENU-SCREEN
                END-EVALUATE
```

COMEN01C handles only **2 named AID keys** (`DFHENTER`, `DFHPF3`) plus `WHEN OTHER`. The Java
translation's pattern-matching switch MUST be exhaustive over the same set per AAP §0.7.4 with
NO `default` branch that masks missing cases.

**CRITICAL OBSERVATION**: `RECEIVE-MENU-SCREEN` IS called at L92 (BEFORE the `EVALUATE EIBAID`),
retrieving the BMS input buffer into `COMEN1AI` for subsequent use by `PROCESS-ENTER-KEY`. The
paragraph itself is defined at `app/cbl/COMEN01C.cbl:L199-L207` and issues `EXEC CICS RECEIVE
MAP('COMEN1A') MAPSET('COMEN01') INTO(COMEN1AI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)`. The
`RECEIVE-MENU-SCREEN` paragraph is therefore NOT dead code — it executes on every REENTER
traversal.

### Three-stage dispatch flow description

1. **First connection (`EIBCALEN = 0`)** — `MAIN-PARA` entry at `app/cbl/COMEN01C.cbl:L82-L84`:
   `MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM`, `PERFORM RETURN-TO-SIGNON-SCREEN`. The
   `RETURN-TO-SIGNON-SCREEN` paragraph at `app/cbl/COMEN01C.cbl:L170-L177` defaults
   `CDEMO-TO-PROGRAM` to `'COSGN00C'` (if blank or LOW-VALUES) and issues
   `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)`. COMEN01C is NOT the initial-display program; it
   expects to be reached with `EIBCALEN > 0` from a successful signon. This is the structural
   distinguisher vs. COSGN00C, which DOES display its own screen on `EIBCALEN = 0`.
2. **First non-zero entry (NOT REENTER)** — ELSE branch at `app/cbl/COMEN01C.cbl:L85-L90`:
   `MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA`, `SET CDEMO-PGM-REENTER TO TRUE`,
   `MOVE LOW-VALUES TO COMEN1AO`, `PERFORM SEND-MENU-SCREEN`. The `SEND-MENU-SCREEN` paragraph
   at `app/cbl/COMEN01C.cbl:L182-L194` performs `POPULATE-HEADER-INFO` (date/time, titles,
   program name, transaction id), `BUILD-MENU-OPTIONS` (concatenate 10 menu entries from
   `COMEN02Y` into `OPTN001O`..`OPTN010O` via `STRING` + `EVALUATE WS-IDX` at L243-L275), and
   issues `EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE`.
3. **REENTER branch** (second and subsequent entries) at `app/cbl/COMEN01C.cbl:L91-L103`:
   `PERFORM RECEIVE-MENU-SCREEN` (L92), then `EVALUATE EIBAID` dispatch:
   - `WHEN DFHENTER` → `PROCESS-ENTER-KEY` at `app/cbl/COMEN01C.cbl:L115-L165`: strip trailing
     spaces from `OPTIONI` (L117-L121); convert blank → `'0'` and parse to `WS-OPTION` numeric
     (L122-L124); validate non-numeric / > 10 / = 0 → `'Please enter a valid option number...'`
     (L127-L134); admin-only guard for `USRTYPE = 'A'` → `'No access - Admin Only option... '`
     (L136-L143, DEAD CODE); if NOT `ERR-FLG-ON` dispatch via
     `EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) COMMAREA(CARDDEMO-COMMAREA)`
     (L152-L155); commarea pre-population at L147-L151 sets `CDEMO-FROM-TRANID = 'CM00'`,
     `CDEMO-FROM-PROGRAM = 'COMEN01C'`, `CDEMO-PGM-CONTEXT = ZEROS`.
   - `WHEN DFHPF3` → `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM`, `PERFORM RETURN-TO-SIGNON-SCREEN`
     (L96-L98) → XCTL to `COSGN00C` (return to signon).
   - `WHEN OTHER` → `MOVE 'Y' TO WS-ERR-FLG`, `MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE`,
     `PERFORM SEND-MENU-SCREEN` (L99-L102) — re-renders the menu with the verbatim
     `CCDA-MSG-INVALID-KEY` error.

### Menu-options table (compiled-in, not a runtime fixture)

The static dispatch table comes from `app/cpy/COMEN02Y.cpy` (95 lines). `CDEMO-MENU-OPT-COUNT
PIC 9(02) VALUE 10` is declared at L21. The 10 active entries at L23-L84 (all `USRTYPE = 'U'`)
are:

| Option | Display Name (35 chars) | Target Program | User Type |
|--------|-------------------------|----------------|-----------|
| 01     | Account View            | `COACTVWC`     | U         |
| 02     | Account Update          | `COACTUPC`     | U         |
| 03     | Credit Card List        | `COCRDLIC`     | U         |
| 04     | Credit Card View        | `COCRDSLC`     | U         |
| 05     | Credit Card Update      | `COCRDUPC`     | U         |
| 06     | Transaction List        | `COTRN00C`     | U         |
| 07     | Transaction View        | `COTRN01C`     | U         |
| 08     | Transaction Add         | `COTRN02C`     | U         |
| 09     | Transaction Reports     | `CORPT00C`     | U         |
| 10     | Bill Payment            | `COBIL00C`     | U         |

Note: **ALL 10 entries have `USRTYPE = 'U'` (regular user)**. The admin-only guard at
`app/cbl/COMEN01C.cbl:L136-L143` is therefore DEAD CODE in the regular-user menu — no entry can
trigger the `'No access - Admin Only option... '` branch. Translate the guard faithfully per AAP
§0.7.1 ("If a COBOL paragraph contains dead code, translate it faithfully and flag it in
`MIGRATION_NOTES.md`; do not 'fix' it"). The DUMMY "coming soon" branch at
`app/cbl/COMEN01C.cbl:L157-L164` is also DEAD CODE — no entry has `PGMNAME` starting with
`'DUMMY'`. The `REDEFINES` at `app/cpy/COMEN02Y.cpy:L87-L92` (`CDEMO-MENU-OPTIONS REDEFINES
CDEMO-MENU-OPTIONS-DATA` with `10 CDEMO-MENU-OPT OCCURS 12 TIMES`) provides table-style access
to the 12-element array (only 10 entries are initialized; entries 11 and 12 contain whatever
bytes follow the 10 initialized entries — typically LOW-VALUES or padding).

### Conceptual fixture data (lives in `../expected/`)

| Conceptual fixture   | Actual location                  | Source of inputs                     | Purpose                                                             |
|----------------------|----------------------------------|--------------------------------------|---------------------------------------------------------------------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script covering 12 scenarios |

**NO `usrsec.txt`** — COMEN01C does NOT read or write the `USRSEC` dataset (the
`WS-USRSEC-FILE` declaration at `app/cbl/COMEN01C.cbl:L39` and `COPY CSUSR01Y.` at L58 are DEAD
inclusions; `SEC-USER-DATA` is never referenced in the program body). **NO `acctdata.txt` /
`carddata.txt` / etc.** — COMEN01C has NO VSAM file I/O.

The scenario file MUST cover the 12 required test scenarios:

1. **`EIBCALEN = 0` first connection** → `MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM`, `RETURN-TO-SIGNON-SCREEN` → XCTL `COSGN00C` (no menu display).
2. **First non-zero entry (`PGM-CONTEXT = 0`)** → `SET CDEMO-PGM-REENTER`, `SEND-MENU-SCREEN` (initial menu display with cursor on `OPTION`).
3. **Option 01 + ENTER** → XCTL `COACTVWC` with commarea populated (`CDEMO-FROM-PROGRAM = 'COMEN01C'`, `CDEMO-FROM-TRANID = 'CM00'`, `CDEMO-PGM-CONTEXT = 0`).
4. **Option 03 + ENTER** → XCTL `COCRDLIC`.
5. **Option 06 + ENTER** → XCTL `COTRN00C`.
6. **Option 10 + ENTER** → XCTL `COBIL00C`.
7. **Option 11 (> `CDEMO-MENU-OPT-COUNT = 10`) + ENTER** → `'Please enter a valid option number...'`.
8. **Option 00 + ENTER** → `'Please enter a valid option number...'` (`WS-OPTION = ZEROS` branch of `app/cbl/COMEN01C.cbl:L129`).
9. **Non-numeric option (e.g., `AA`) + ENTER** → `'Please enter a valid option number...'` (`IS NOT NUMERIC` branch of `app/cbl/COMEN01C.cbl:L127`).
10. **Blank option + ENTER** → `INSPECT REPLACING ALL ' ' BY '0'` → `WS-OPTION = 00` → ZEROS branch → same error.
11. **PF3** → `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM`, XCTL `COSGN00C` (return to signon).
12. **Invalid AID key (e.g., PF1)** → `CCDA-MSG-INVALID-KEY` `'Invalid key pressed. Please see below...         '` re-rendered on menu.

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.CoMen01CGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoMen01CGoldenTest.java`. It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`. The class under test is `com.blitzy.carddemo.application.menu.CoMen01C` (the Java translation of `app/cbl/COMEN01C.cbl`).
- Override summary (5 methods, ALL routing to `../expected/`):
  - `programClass()` → `com.blitzy.carddemo.application.menu.CoMen01C.class`.
  - `inputFile()` → `resolveExpectedOutputPath("comen01c", "input_scenario.txt")` → `../expected/input_scenario.txt`.
  - `expectedOutputFile()` → `resolveExpectedOutputPath("comen01c", "stdout.txt")` → `../expected/stdout.txt`.
  - `auxiliaryInputs()` → `List.of()` (empty — COMEN01C has NO file I/O; no auxiliary fixtures required).
  - `expectedOutputs()` → `ExpectedOutput` entries for `stdout.txt` and `bms_output.txt` (both under `../expected/`). **NO `usrsec_after` / `acctdata_after` / etc. entries** — COMEN01C is purely a navigation controller and does not mutate any VSAM dataset.
- **All five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper.
  None routes to this `input/` folder.** This is the architectural reason the folder is empty.
- The test is `@Disabled` with the following 12-point verification list (per AAP §0.6.11 —
  initial test scaffolding uses placeholder expected files marked `@Disabled` until COBOL
  captures are committed; the harness skeleton, base class, and per-program test classes are
  created unconditionally):
  1. `EIBCALEN = 0` first connection → `MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM`, `RETURN-TO-SIGNON-SCREEN` → XCTL `COSGN00C`; no menu display.
  2. First non-zero entry (`CDEMO-PGM-CONTEXT = 0`) → `SET CDEMO-PGM-REENTER`, `SEND-MENU-SCREEN` initial display; cursor on `OPTION` (IC).
  3. Option `01` + ENTER → XCTL `COACTVWC` with `CDEMO-FROM-PROGRAM = 'COMEN01C'`, `CDEMO-FROM-TRANID = 'CM00'`, `CDEMO-PGM-CONTEXT = 0`.
  4. Option `03` + ENTER → XCTL `COCRDLIC` with same commarea pre-population.
  5. Option `06` + ENTER → XCTL `COTRN00C` with same commarea pre-population.
  6. Option `10` + ENTER → XCTL `COBIL00C` with same commarea pre-population.
  7. Option `11` (> `CDEMO-MENU-OPT-COUNT = 10`) + ENTER → `'Please enter a valid option number...'`.
  8. Option `00` + ENTER → `'Please enter a valid option number...'` (`WS-OPTION = ZEROS`).
  9. Non-numeric option (e.g., `AA`) + ENTER → `'Please enter a valid option number...'` (`IS NOT NUMERIC`).
  10. Blank option + ENTER → `INSPECT REPLACING ALL ' ' BY '0'` → `WS-OPTION = 00` → ZEROS branch → same error.
  11. PF3 → `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM`, XCTL `COSGN00C`; differs from COSGN00C's PF3 which issues `EXEC CICS RETURN` (session-end).
  12. Invalid AID key → `CCDA-MSG-INVALID-KEY` verbatim re-rendered on menu; `SEND-MENU-SCREEN` invoked.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure) for the CICS COBOL
build/run path used to capture expected outputs. The capture procedure exists because the user
prompt left this as a `[TODO]` marker (AAP §0.7.5: "To regenerate golden-record fixtures from
COBOL [TODO — document the COBOL build/run path here]"). Until the capture is performed and
committed, the `../expected/stdout.txt` and `../expected/bms_output.txt` files hold placeholder
content and the test is `@Disabled`. Since COMEN01C has NO VSAM I/O, the capture procedure does
NOT require seeding any dataset; the synthesized commarea state in `input_scenario.txt` is
sufficient to drive the program through all 12 scenarios.

## Behavioral invariants preserved by this fixture

- **No file I/O semantics**: COMEN01C contains NO `EXEC CICS READ` / `WRITE` / `REWRITE` /
  `DELETE` / `STARTBR` / `READNEXT` / `READPREV` / `ENDBR` statements (verified by full read of
  `app/cbl/COMEN01C.cbl`). The `WS-USRSEC-FILE` declaration at `app/cbl/COMEN01C.cbl:L39` and
  `COPY CSUSR01Y.` at L58 are DEAD inclusions; `SEC-USER-DATA` is never referenced. The
  post-run state of every VSAM dataset is byte-identical to the input (in fact, no dataset is
  touched at all).
- **Static menu-options table**: The 10 active menu entries come from the compiled-in
  `COMEN02Y` copybook (`app/cpy/COMEN02Y.cpy`); they are NOT runtime-loaded from any file. The
  Java translation expresses this as a static immutable list in
  `com.blitzy.carddemo.domain.menu.MainMenuTable` per AAP §0.4.1.
- **Right-justified zero-fill option parsing** (`app/cbl/COMEN01C.cbl:L117-L124` +
  `app/bms/COMEN01.bms:L147`): the BMS `JUSTIFY=(RIGHT,ZERO)` attribute right-justifies typed
  input and zero-fills, so typed `5` becomes BMS buffer `05`. The `PERFORM VARYING` at L117-L121
  strips trailing spaces right-to-left; the `INSPECT` at L123 replaces remaining spaces with
  `'0'`; the `MOVE` at L124 numericizes `WS-OPTION-X` to `WS-OPTION`. A blank field thus yields
  `WS-OPTION = 00` (which then triggers the ZEROS validation at L129).
- **Validation gate**: An option is rejected if (a) `WS-OPTION IS NOT NUMERIC`,
  (b) `WS-OPTION > CDEMO-MENU-OPT-COUNT` (= 10), or (c) `WS-OPTION = ZEROS` — cite
  `app/cbl/COMEN01C.cbl:L127-L129`. All three failure modes produce the verbatim message
  `'Please enter a valid option number...'` (L131-L132).
- **`EIBCALEN = 0` first-time entry redirects to signon** (`app/cbl/COMEN01C.cbl:L82-L84`):
  unlike COSGN00C (which displays its own signon screen on `EIBCALEN = 0`), COMEN01C XCTLs back
  to COSGN00C. This enforces the invariant that the user must signon before reaching the main
  menu.
- **Reentry flag flips on first display** (`app/cbl/COMEN01C.cbl:L87-L90`): on the first
  non-zero entry, `CDEMO-PGM-REENTER` is set to TRUE (via `SET CDEMO-PGM-REENTER TO TRUE` which
  writes `1` to `CDEMO-PGM-CONTEXT`) — so subsequent invocations take the REENTER branch and
  call `RECEIVE-MENU-SCREEN` before dispatch. This is the standard pseudo-conversational
  pattern.
- **Commarea pre-population on dispatch** (`app/cbl/COMEN01C.cbl:L147-L151`): before XCTLing
  to the chosen target program, COMEN01C sets `CDEMO-FROM-TRANID = 'CM00'`,
  `CDEMO-FROM-PROGRAM = 'COMEN01C'`, `CDEMO-PGM-CONTEXT = ZEROS`. The target program reads
  these to identify its caller and to know it is being entered fresh (not reentered).
- **PF3 returns to signon** (`app/cbl/COMEN01C.cbl:L96-L98, L170-L177`): PF3 XCTLs to
  `COSGN00C` (after `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM`). Note this differs from sibling
  COSGN00C where PF3 issues `SEND-PLAIN-TEXT` + `EXEC CICS RETURN` (session-end). COMEN01C's
  PF3 effectively "logs out" by re-driving the signon entry.
- **Dead-code paragraphs translated faithfully** (per AAP §0.7.1): the admin-only guard at
  `app/cbl/COMEN01C.cbl:L136-L143` (`'No access - Admin Only option... '`) is DEAD because all
  10 entries in `COMEN02Y` have `USRTYPE = 'U'`; the DUMMY "coming soon" branch at
  `app/cbl/COMEN01C.cbl:L157-L164` (`'This option <name> is coming soon ...'`) is DEAD because
  no entry in `COMEN02Y` has `PGMNAME` starting with `'DUMMY'`. Both are translated as
  compiled-but-unreachable methods in the Java port; documented in `java/MIGRATION_NOTES.md`
  per AAP §0.7.1 ("translate faithfully; do not 'fix' in this refactor").
- **AID-key handling** (`app/cbl/COMEN01C.cbl:L93-L103`): COMEN01C handles exactly 2 named AID
  keys: `DFHENTER`, `DFHPF3`. Any other AID key (`CLEAR`, `PA1`, `PA2`, `PF1`-`PF2`,
  `PF4`-`PF12`) falls through to `WHEN OTHER` and emits `CCDA-MSG-INVALID-KEY`. The Java
  translation's pattern-matching switch on a sealed `AidKey` hierarchy MUST be exhaustive over
  the same set per AAP §0.7.4 — NO `default` branch.
- **Verbatim error messages** (each cited from source):
  `'Please enter a valid option number...'` — `app/cbl/COMEN01C.cbl:L131-L132` (invalid /
  out-of-range / zero option); `'No access - Admin Only option... '` —
  `app/cbl/COMEN01C.cbl:L140-L141` (DEAD: regular user + `USRTYPE = 'A'`); `'This option <name>
  is coming soon ...'` — STRING-constructed at `app/cbl/COMEN01C.cbl:L159-L163` (DEAD:
  `PGMNAME(1:5) = 'DUMMY'`); `'Invalid key pressed. Please see below...         '`
  (`CCDA-MSG-INVALID-KEY`, `PIC X(50)` field width, 50 chars with trailing spaces preserved) —
  `app/cpy/CSMSG01Y.cpy:L20-L21` (`WHEN OTHER` AID branch).
- **Sealed type usage in Java translation** (per AAP §0.6.10): `CDEMO-USER-TYPE` 88-levels
  (`CDEMO-USRTYP-ADMIN VALUE 'A'`, `CDEMO-USRTYP-USER VALUE 'U'` at
  `app/cpy/COCOM01Y.cpy:L27-L28`) → sealed `UserType { Admin, User }` — used by the admin-only
  guard at `app/cbl/COMEN01C.cbl:L136-L143`. `CDEMO-PGM-CONTEXT` 88-levels (`CDEMO-PGM-ENTER
  VALUE 0`, `CDEMO-PGM-REENTER VALUE 1` at `app/cpy/COCOM01Y.cpy:L30-L31`) → sealed
  `PgmContext { Enter, Reenter }` — used by the NOT REENTER branch at
  `app/cbl/COMEN01C.cbl:L87-L90`.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CoMen01C` MUST carry
  `@CobolProgram("COMEN01C")` Javadoc-style annotation citing the original PROGRAM-ID, source
  path `app/cbl/COMEN01C.cbl`, and the translation date.

## Source lineage

- `app/cbl/COMEN01C.cbl` — Main Menu CICS COBOL program (282 lines). `PROGRAM-ID COMEN01C` at L23; transaction `CM00` declared at L37; key paragraphs: `MAIN-PARA` (L75-L110), `PROCESS-ENTER-KEY` (L115-L165), `RETURN-TO-SIGNON-SCREEN` (L170-L177), `SEND-MENU-SCREEN` (L182-L194), `RECEIVE-MENU-SCREEN` (L199-L207), `POPULATE-HEADER-INFO` (L212-L231), `BUILD-MENU-OPTIONS` (L236-L277).
- `app/bms/COMEN01.bms` — BMS map definition (167 lines). MAPSET `COMEN01` at L19, MAP `COMEN1A` at L26, `SIZE=(24,80)` at L28. Sole input field: `OPTION PIC X(2)` at L145-L149 (`FSET, IC, NORM, NUM, UNPROT, HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO)`). 12 menu-option output fields `OPTN001`-`OPTN012` at L80-L139 (rows 6-17, col 20, length 40, BLUE). `ERRMSG` at L154-L157. Footer `'ENTER=Continue  F3=Exit'` at L158-L162 (length 23). **NO `PASSWD` field** (structural distinguisher vs. COSGN0A).
- `app/cpy-bms/COMEN01.CPY` — Symbolic map copybook (260 lines). Defines input record `01 COMEN1AI` at L17 (input fields suffixed `I` with companion `*L`, `*F`, `*A`) and output record `01 COMEN1AO REDEFINES COMEN1AI` (output fields suffixed `O` plus `*C`, `*P`, `*H`, `*V` control characters).
- `app/cpy/COMEN02Y.cpy` — `01 CARDDEMO-MAIN-MENU-OPTIONS` (95 lines). Static menu-options table; `CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10` at L21; 10 active entries (each 4 sub-FILLERs: option `PIC 9(02)`, name `PIC X(35)`, program `PIC X(08)`, user-type `PIC X(01)`) at L23-L84; `REDEFINES` table overlay `CDEMO-MENU-OPT OCCURS 12 TIMES` at L87-L92.
- `app/cpy/COCOM01Y.cpy` — `01 CARDDEMO-COMMAREA` (47 lines). Includes `CDEMO-GENERAL-INFO` with `CDEMO-USER-TYPE` at L26 (88-levels `CDEMO-USRTYP-ADMIN VALUE 'A'` at L27 and `CDEMO-USRTYP-USER VALUE 'U'` at L28) and `CDEMO-PGM-CONTEXT` at L29 (88-levels `CDEMO-PGM-ENTER VALUE 0` at L30 and `CDEMO-PGM-REENTER VALUE 1` at L31).
- `app/cpy/CSMSG01Y.cpy` — `01 CCDA-COMMON-MESSAGES` (24 lines). Contains `CCDA-MSG-INVALID-KEY` (used by COMEN01C `WHEN OTHER` AID branch) at L20-L21.

## Authority references

- AAP §0.2.1 (in-scope: `golden/comen01c/` directory tree as part of the
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (COMEN01C → CoMen01C main menu online program; one Java class per COBOL
  PROGRAM-ID; in `com.blitzy.carddemo.application.menu`; uses `COMEN02Y` menu table).
- AAP §0.6.10 (sealed `UserType { Admin, User }` and `PgmContext { Enter, Reenter }` from
  88-level conditions in `app/cpy/COCOM01Y.cpy`).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding
  pattern until COBOL captures are committed).
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the
  reference implementation).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is behavior including verbatim error messages
  and dead-code translation).
- AAP §0.7.4 (no preview features; pattern-matching exhaustiveness — NO `default` branches).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep` placeholders, `input_scenario.txt`, or any other content. The `README.md` IS the directory's marker. All fixture data files for the COMEN01C parity test live in the sibling `../expected/` folder, because the consuming test class `CoMen01CGoldenTest` resolves every fixture path through the base class helper `resolveExpectedOutputPath("comen01c", ...)`. Adding files here would create duplicate, stale, or unreachable fixtures. If a future test scenario requires additional inputs, add the new file to `../expected/` and extend the appropriate override in `CoMen01CGoldenTest.java` (`auxiliaryInputs()` or a new path-returning method) — not here.
