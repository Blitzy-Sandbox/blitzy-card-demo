# COSGN00C Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files. COSGN00C is
the **CICS signon entry program** (transaction `CC00`) — the AUTHENTICATION ENTRY POINT translated
to Java `com.blitzy.carddemo.application.signon.CoSgn00C`. COSGN00C is the **ONLY program reached
when `EIBCALEN = 0`** at first connection; all other COBOL programs in the system check
`IF EIBCALEN = 0` and `EXEC CICS XCTL` to COSGN00C as the universal signon entry point. COSGN00C is
**READ-ONLY** (a single `EXEC CICS READ DATASET` on USRSEC; no STARTBR / WRITE / REWRITE / DELETE).
The deterministic test scenario file (`input_scenario.txt`) and the initial-state USRSEC fixture
(`usrsec.txt`) BOTH live in the sibling `../expected/` folder, because the consuming Java test class
`CoSgn00CGoldenTest` resolves all fixture paths through the base class helper
`resolveExpectedOutputPath("cosgn00c", ...)` — which routes to
`src/test/resources/golden/cosgn00c/expected/`. This conventional `input/` subdirectory (per AAP
§0.6.11 harness convention) is preserved only to make the absence of fixture data files **explicit
and discoverable**, mirroring the sibling precedents in `golden/cousr00c/`, `golden/cousr01c/`,
`golden/cousr02c/`, `golden/cousr03c/`, and `golden/csutldtc/`.

## Why this folder is documentation-only

1. COSGN00C is an **online CICS pseudo-conversational program** (transaction `CC00`). Its
   user-facing input arrives via the BMS map `COSGN0A` defined in `app/bms/COSGN00.bms` —
   specifically two input fields: `USERID PIC X(8)` (with `FSET, IC, NORM, UNPROT` attributes at
   `app/bms/COSGN00.bms:L156-L160`) and `PASSWD PIC X(8)` (with `DRK, FSET, UNPROT` attributes at
   `app/bms/COSGN00.bms:L175-L180`) — plus AID-key dispatch (`DFHENTER`, `DFHPF3`). The source
   COBOL working-storage at `app/cbl/COSGN00C.cbl:L36-L46` declares
   `WS-PGMNAME PIC X(08) VALUE 'COSGN00C'` (L36), `WS-TRANID PIC X(04) VALUE 'CC00'` (L37),
   `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '` (L39, two-space right-padding to fill 8 chars),
   `WS-USER-ID PIC X(08)` (L45), and `WS-USER-PWD PIC X(08)` (L46).
2. Inputs are not file-based fixtures sourced from `app/data/ASCII/*.txt` (per AAP §0.4.1, USRSEC
   is **not** among the 9 ASCII fixture files). The deterministic test scenario is encoded as a
   **synthesized scenario script** plus a **synthesized initial-state USRSEC fixture**, both
   **test-owned scaffolding** authored alongside the captured expected outputs in `../expected/`.
3. Per the sibling precedent established by `golden/cousr00c/input/`, `golden/cousr01c/input/`,
   `golden/cousr02c/input/`, `golden/cousr03c/input/`, and `golden/csutldtc/input/`, the
   conventional `input/` subdirectory is preserved as a documentation marker even though no
   fixture data files reside there.
4. The harness convention from AAP §0.6.11 mandates the `input/` + `expected/` pairing per program
   for discoverability and parallel structure across all 28 program test folders.
5. The consuming test class `CoSgn00CGoldenTest` resolves all fixture paths through the base class
   helper `resolveExpectedOutputPath(programDir, fileName)`, which maps every path to
   `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely: `inputFile()` returns
   `resolveExpectedOutputPath("cosgn00c", "input_scenario.txt")` →
   `../expected/input_scenario.txt`; `auxiliaryInputs()` returns
   `List.of(resolveExpectedOutputPath("cosgn00c", "usrsec.txt"))` → `../expected/usrsec.txt`. All
   five overrides route to `../expected/`; none routes to this `input/` folder.

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map input fields (2 editable: USERID + PASSWD)

| Field    | Position | Length   | Attribute                     | Direction |
|----------|----------|----------|-------------------------------|-----------|
| `USERID` | (19,43)  | 8 chars  | GREEN, FSET, IC, NORM, UNPROT | Input     |
| `PASSWD` | (20,43)  | 8 chars  | GREEN, FSET, **DRK**, UNPROT  | Input     |
| `ERRMSG` | (23,1)   | 78 chars | RED, ASKIP, BRT, FSET         | Output    |

- `USERID`: User-ID; case-insensitive matching via `FUNCTION UPPER-CASE` at
  `app/cbl/COSGN00C.cbl:L132-L134`. The `IC` (initial-cursor) attribute places the cursor here on
  initial display.
- `PASSWD`: Password; case-insensitive matching via `FUNCTION UPPER-CASE` at
  `app/cbl/COSGN00C.cbl:L135-L136`. The `DRK` (non-display) attribute causes typed bytes to render
  as blanks on the 3270 terminal, but the bytes ARE present in the BMS input buffer in memory.
- `ERRMSG`: Output message receiving text from `WS-MESSAGE` via `MOVE WS-MESSAGE TO ERRMSGO` at
  `app/cbl/COSGN00C.cbl:L149`.

**CRITICAL DISTINGUISHER**: `COSGN0A` HAS a `PASSWD` field with `DRK` attribute (verified presence
at `app/bms/COSGN00.bms:L175-L180`). Contrast the `cousr00c` sibling: `COUSR0A` has NO `PASSWD`
field at all. The `DRK` attribute renders typed characters as blanks on the 3270 terminal, but the
bytes ARE present in the BMS input buffer in memory — the capture script MUST mask them per AAP
§0.7.2.

### CICS pseudo-conversational dispatch (2 named AID keys + WHEN OTHER)

The `EVALUATE EIBAID` block at `app/cbl/COSGN00C.cbl:L85-L95` routes each AID key to its handler:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        MOVE CCDA-MSG-THANK-YOU        TO WS-MESSAGE
        PERFORM SEND-PLAIN-TEXT
    WHEN OTHER
        MOVE 'Y'                       TO WS-ERR-FLG
        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
        PERFORM SEND-SIGNON-SCREEN
END-EVALUATE
```

COSGN00C handles only **2 named AID keys** (`DFHENTER`, `DFHPF3`) plus `WHEN OTHER`. Contrast:
`COUSR00C` handles 4 named keys (`DFHENTER`, `DFHPF3`, `DFHPF7`, `DFHPF8`). The Java translation's
pattern-matching switch MUST be exhaustive over the same set per AAP §0.7.4 — NO `default` branch
that masks missing cases. The COSGN00C `WHEN OTHER` branch (`app/cbl/COSGN00C.cbl:L91-L94`) does
NOT include `MOVE -1 TO USERIDL OF COSGN0AI` — that explicit cursor reposition is a COUSR00C
pattern, not a COSGN00C pattern.

### Single-pass READ flow description

1. **First connection (`EIBCALEN = 0`)** — MAIN-PARA entry at `app/cbl/COSGN00C.cbl:L80-L83`:
   `MOVE LOW-VALUES TO COSGN0AO`, `MOVE -1 TO USERIDL OF COSGN0AI`, `PERFORM SEND-SIGNON-SCREEN`.
   Cursor lands on `USERID` due to its `IC` attribute and the explicit `USERIDL = -1`.
2. **ENTER pressed** — `PROCESS-ENTER-KEY` at `app/cbl/COSGN00C.cbl:L108-L140`:
   `EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')`; validate `USERIDI` not empty (else
   `'Please enter User ID ...'`); validate `PASSWDI` not empty (else
   `'Please enter Password ...'`); upper-case both via `FUNCTION UPPER-CASE`; perform
   `READ-USER-SEC-FILE` when no error flag is set.
3. **READ-USER-SEC-FILE** at `app/cbl/COSGN00C.cbl:L209-L257`:
   `EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)`; then
   `EVALUATE WS-RESP-CD`:
   - `WHEN 0` (`DFHRESP(NORMAL)`) + password byte-equal match → `IF CDEMO-USRTYP-ADMIN` then
     `EXEC CICS XCTL PROGRAM('COADM01C')` else `EXEC CICS XCTL PROGRAM('COMEN01C')`
     (`app/cbl/COSGN00C.cbl:L230-L240`).
   - `WHEN 0` + password mismatch → `'Wrong Password. Try again ...'`
     (`app/cbl/COSGN00C.cbl:L242-L243`).
   - `WHEN 13` (`DFHRESP(NOTFND)`) → `'User not found. Try again ...'`
     (`app/cbl/COSGN00C.cbl:L249`).
   - `WHEN OTHER` → `'Unable to verify the User ...'` (`app/cbl/COSGN00C.cbl:L254`).
4. **PF3 pressed** — `MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE`; `PERFORM SEND-PLAIN-TEXT`; the
   `SEND-PLAIN-TEXT` paragraph at `app/cbl/COSGN00C.cbl:L162-L172` issues `EXEC CICS RETURN` with
   NO `TRANSID` and NO `COMMAREA` — the pseudo-conversation TERMINATES. This differs from sibling
   `COUSR00C` which XCTLs to `COADM01C` on PF3.
5. **Other AID key** — falls through to `WHEN OTHER` and emits `CCDA-MSG-INVALID-KEY`.

### Authentication routing decision (TWO XCTL destinations)

The branched routing based on `SEC-USR-TYPE` is the structural distinguisher of COSGN00C — cite
`app/cbl/COSGN00C.cbl:L230-L240`:

- `SEC-USR-TYPE = 'A'` (admin via 88-level `CDEMO-USRTYP-ADMIN`) →
  `EXEC CICS XCTL PROGRAM('COADM01C') COMMAREA(CARDDEMO-COMMAREA)`.
- `SEC-USR-TYPE = 'U'` (regular user, else branch) →
  `EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(CARDDEMO-COMMAREA)`.

The commarea populated on successful signon at `app/cbl/COSGN00C.cbl:L224-L228` includes:
`CDEMO-FROM-TRANID = WS-TRANID ('CC00')`, `CDEMO-FROM-PROGRAM = WS-PGMNAME ('COSGN00C')`,
`CDEMO-USER-ID` (from upper-cased `WS-USER-ID` — see `app/cbl/COSGN00C.cbl:L132-L134`),
`CDEMO-USER-TYPE = SEC-USR-TYPE`, `CDEMO-PGM-CONTEXT = ZEROS`. The Java translation expresses
`CDEMO-USER-TYPE` as a sealed `UserType { Admin, User }` from the 88-level conditions
`CDEMO-USRTYP-ADMIN ('A')` / `CDEMO-USRTYP-USER ('U')` defined at `app/cpy/COCOM01Y.cpy:L27-L28`
per AAP §0.6.10.

### Conceptual fixture data (lives in `../expected/`)

| Conceptual fixture   | Actual location                  | Source of inputs                     |
|----------------------|----------------------------------|--------------------------------------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) |
| `usrsec.txt`         | `../expected/usrsec.txt`         | Test-owned scaffolding (synthesized) |

- `input_scenario.txt` — Deterministic CICS pseudo-conversation script covering the 12 scenarios
  listed below.
- `usrsec.txt` — 80-byte fixed-width `SEC-USER-DATA` initial-state fixture; minimum 4 records
  (1 admin + 3 regular) per `app/cpy/CSUSR01Y.cpy` layout; **plaintext passwords preserved per AAP
  §0.1.3**.

The scenario file MUST cover the 12 required test scenarios:

1. **Initial display** (`EIBCALEN = 0`) → cursor on `USERID`.
2. **Empty USERID** → `'Please enter User ID ...'`.
3. **Empty PASSWORD** → `'Please enter Password ...'`.
4. **Non-existent USERID** → `'User not found. Try again ...'`.
5. **Valid USERID, wrong PASSWORD** → `'Wrong Password. Try again ...'`.
6. **Valid admin signon** → XCTL `COADM01C` with commarea populated.
7. **Valid regular user signon** → XCTL `COMEN01C` with commarea populated.
8. **PF3 exit** → `SEND-PLAIN-TEXT` thank-you + `EXEC CICS RETURN` (session-end).
9. **Invalid AID key** → `CCDA-MSG-INVALID-KEY`.
10. **Lower-case USERID** → upper-cased via `FUNCTION UPPER-CASE` → match stored value.
11. **Lower-case PASSWORD** → upper-cased via `FUNCTION UPPER-CASE` → match stored value.
12. **`WHEN OTHER` RESP** (simulated I/O error) → `'Unable to verify the User ...'`.

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.CoSgn00CGoldenTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoSgn00CGoldenTest.java`. It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`. The class under test is `com.blitzy.carddemo.application.signon.CoSgn00C` (the Java translation of `app/cbl/COSGN00C.cbl`).
- Override summary (5 methods, ALL routing to `../expected/`):
  - `programClass()` → `com.blitzy.carddemo.application.signon.CoSgn00C.class`.
  - `inputFile()` → `resolveExpectedOutputPath("cosgn00c", "input_scenario.txt")` — routes to
    `../expected/input_scenario.txt`.
  - `expectedOutputFile()` → `resolveExpectedOutputPath("cosgn00c", "stdout.txt")` — routes to
    `../expected/stdout.txt`.
  - `auxiliaryInputs()` → `List.of(resolveExpectedOutputPath("cosgn00c", "usrsec.txt"))` — routes
    to `../expected/usrsec.txt`.
  - `expectedOutputs()` → `ExpectedOutput` entries for `stdout.txt` and `bms_output.txt` (both
    under `../expected/`). **NO `usrsec_after.txt` entry** — because COSGN00C is READ-ONLY, the
    post-run USRSEC state is byte-identical to the input and no after-fixture is declared.
- **All five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper.
  None routes to this `input/` folder.** This is the architectural reason the folder is empty.
- The test is `@Disabled` with the following 11-point verification list (per AAP §0.6.11 — initial
  test scaffolding uses placeholder expected files marked `@Disabled` until COBOL captures are
  committed; the harness skeleton, base class, and per-program test classes are created
  unconditionally):
  1. Authentication entry-point semantics: `EIBCALEN = 0` first-time entry → initial display with
     cursor on `USERID`.
  2. Empty USERID validation → `'Please enter User ID ...'`.
  3. Empty PASSWORD validation → `'Please enter Password ...'`.
  4. Non-existent USERID (`DFHRESP(NOTFND)`, `WS-RESP-CD = 13`) →
     `'User not found. Try again ...'`.
  5. Wrong-password (`DFHRESP(NORMAL)`, `WS-RESP-CD = 0`, byte-mismatch on `SEC-USR-PWD`) →
     `'Wrong Password. Try again ...'`.
  6. Admin-signon (`SEC-USR-TYPE = 'A'`) → XCTL `COADM01C` with `CDEMO-USER-TYPE = 'A'`,
     `CDEMO-FROM-PROGRAM = 'COSGN00C'`, `CDEMO-FROM-TRANID = 'CC00'`, `CDEMO-USER-ID` populated.
  7. Regular-user-signon (`SEC-USR-TYPE = 'U'`) → XCTL `COMEN01C` with `CDEMO-USER-TYPE = 'U'`.
  8. PF3 exit → `SEND-PLAIN-TEXT` with verbatim `CCDA-MSG-THANK-YOU` + `EXEC CICS RETURN` (no
     `TRANSID`, no `COMMAREA`); session terminates without XCTL.
  9. `WHEN OTHER` AID key → `CCDA-MSG-INVALID-KEY` verbatim.
  10. Case-insensitive matching: lower-case + mixed-case credentials → upper-cased via
      `FUNCTION UPPER-CASE` → match stored values.
  11. **SECURITY ASSERTION** (per AAP §0.7.2): NO password value (`SEC-USR-PWD` bytes) appears
      anywhere in `stdout.txt` or `bms_output.txt` captured artifacts; the capture script per
      `java/MIGRATION_NOTES.md` §1.6 MUST mask any password bytes from the BMS input buffer;
      assert via `ASSERT_NO_PASSWORD_IN_OUTPUT` directives in scenarios 6, 7, 10, 11.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COSGN00C) for the
CICS COBOL build/run path used to capture expected outputs. The capture procedure exists because
the user prompt left this as a `[TODO]` marker (AAP §0.7.5: "To regenerate golden-record fixtures
from COBOL [TODO — document the COBOL build/run path here]"). Until the capture is performed and
committed, the `../expected/stdout.txt` and `../expected/bms_output.txt` files hold placeholder
content and the test is `@Disabled`. **SECURITY-CRITICAL** (per AAP §0.7.2): the capture script
MUST verify and mask any `SEC-USR-PWD` value before committing captured artifacts. The `PASSWD`
field of the BMS input buffer (`PIC X(8)` at offset within the `COSGN0AI` structure) MUST be
zeroed or replaced with placeholder bytes in any captured `stdout.txt` or `bms_output.txt` before
the artifact is committed.

## Behavioral invariants preserved by this fixture

- **READ-ONLY semantics**: COSGN00C uses a single `EXEC CICS READ DATASET` on USRSEC
  (`app/cbl/COSGN00C.cbl:L211-L219`); it never issues `WRITE`, `REWRITE`, `DELETE`, or `STARTBR`.
  The post-run state of USRSEC is byte-identical to the input.
- **Case-insensitive credential matching** (`app/cbl/COSGN00C.cbl:L132-L136`): both `USERIDI` and
  `PASSWDI` are upper-cased via `FUNCTION UPPER-CASE` before the VSAM READ and the password
  byte-equal comparison. The Java translation MUST mirror this EXACTLY — lower-case, upper-case,
  and mixed-case inputs ALL match the same stored value.
- **`EIBCALEN = 0` universal entry point**: COSGN00C is the ONLY program reachable on first
  connection (`app/cbl/COSGN00C.cbl:L80-L83`). All other COBOL programs check `IF EIBCALEN = 0`
  and XCTL to COSGN00C as the universal signon entry.
- **Plaintext password preservation (AAP §0.1.3)**: `SEC-USR-PWD` stores passwords as `PIC X(08)`
  plaintext in `usrsec.txt`. This is preserved as-is in the synthesized fixture. Any move to a
  password-hashing scheme would constitute a behavior change beyond migration scope and is
  explicitly OUT OF SCOPE; documented in `java/MIGRATION_NOTES.md` for follow-up effort.
- **No password in `stdout` or `bms_output` (AAP §0.7.2 — SECURITY MANDATE)**: even though
  `SEC-USR-PWD` bytes (offset 49-56 within each USRSEC record) are present in `usrsec.txt` for
  reference comparison, NO password value appears in `stdout.txt` or `bms_output.txt` captured
  artifacts. The Java test MUST assert this exclusion via `ASSERT_NO_PASSWORD_IN_OUTPUT`
  directives. The capture-script procedure in `java/MIGRATION_NOTES.md` §1.6 MUST mask any
  password bytes from BMS input buffers before commit.
- **`PASSWD` field `DRK` attribute** (`app/bms/COSGN00.bms:L175-L180`): the `COSGN0A` BMS map's
  `PASSWD` field has the `DRK` (non-display) attribute, causing typed characters to render as
  blanks on the 3270 terminal. However, the bytes ARE present in the BMS input buffer in memory;
  the capture script must remove them from captured artifacts.
- **PF3 session-end semantics** (`app/cbl/COSGN00C.cbl:L88-L90, L162-L172`): PF3 does NOT XCTL
  anywhere. It MOVEs `CCDA-MSG-THANK-YOU` TO `WS-MESSAGE`, performs `SEND-PLAIN-TEXT`, and the
  `SEND-PLAIN-TEXT` paragraph issues `EXEC CICS RETURN` with no `TRANSID` and no `COMMAREA` — the
  pseudo-conversation terminates. This differs from sibling COUSR00C where PF3 XCTLs to
  `COADM01C`.
- **Branched XCTL routing** (`app/cbl/COSGN00C.cbl:L230-L240`): Successful signon dispatches to
  `COADM01C` (admin) OR `COMEN01C` (user) based on `SEC-USR-TYPE`. The Java translation expresses
  this via sealed `UserType { Admin, User }` from 88-level conditions at
  `app/cpy/COCOM01Y.cpy:L27-L28`; the dispatch is a pattern-matching switch with NO `default`
  branch per AAP §0.7.4.
- **Verbatim error messages** (each cited from source):
  - `'Please enter User ID ...'` — `app/cbl/COSGN00C.cbl:L120` (empty `USERIDI`).
  - `'Please enter Password ...'` — `app/cbl/COSGN00C.cbl:L125` (empty `PASSWDI`).
  - `'Wrong Password. Try again ...'` — `app/cbl/COSGN00C.cbl:L242-L243` (`DFHRESP(NORMAL)` +
    password mismatch).
  - `'User not found. Try again ...'` — `app/cbl/COSGN00C.cbl:L249` (`DFHRESP(NOTFND)`,
    `WS-RESP-CD = 13`).
  - `'Unable to verify the User ...'` — `app/cbl/COSGN00C.cbl:L254` (`DFHRESP` `WHEN OTHER`).
  - `'Thank you for using CardDemo application...      '` (`CCDA-MSG-THANK-YOU`, `PIC X(50)` field
    width, trailing spaces preserved) — `app/cpy/CSMSG01Y.cpy:L18-L19` (PF3 exit).
  - `'Invalid key pressed. Please see below...         '` (`CCDA-MSG-INVALID-KEY`, `PIC X(50)`
    field width, trailing spaces preserved) — `app/cpy/CSMSG01Y.cpy:L20-L21` (`WHEN OTHER` AID
    key).
- **AID-key handling** (`app/cbl/COSGN00C.cbl:L85-L95`): COSGN00C handles exactly 2 named AID
  keys: `DFHENTER`, `DFHPF3`. Any other AID key (`CLEAR`, `PA1`, `PA2`, `PF1`-`PF2`, `PF4`-`PF12`)
  falls through to `WHEN OTHER` and emits `CCDA-MSG-INVALID-KEY`. The Java translation's
  pattern-matching switch on a sealed `AidKey` hierarchy MUST be exhaustive over the same set per
  AAP §0.7.4 — NO `default` branch.
- **`@CobolProgram` traceability** (per AAP §0.7.1): the Java class `CoSgn00C` MUST carry
  `@CobolProgram("COSGN00C")` Javadoc-style annotation citing the original PROGRAM-ID, source
  path `app/cbl/COSGN00C.cbl`, and the translation date.

## Source lineage

- `app/cbl/COSGN00C.cbl` — Signon CICS COBOL program (260 lines). `PROGRAM-ID COSGN00C` at L23; transaction `CC00` declared at L37; key paragraphs: `MAIN-PARA` (L73-L102), `PROCESS-ENTER-KEY` (L108-L140), `SEND-SIGNON-SCREEN` (L145-L157), `SEND-PLAIN-TEXT` (L162-L172), `POPULATE-HEADER-INFO` (L177-L204), `READ-USER-SEC-FILE` (L209-L257).
- `app/bms/COSGN00.bms` — BMS map definition (210 lines). MAPSET `COSGN00` at L19, MAP `COSGN0A` at L26, `SIZE=(24,80)` at L28. Input fields: `USERID PIC X(8)` at L156-L160 (`FSET, IC, NORM, UNPROT`, `COLOR=GREEN`, `HILIGHT=OFF`) and `PASSWD PIC X(8)` at L175-L180 (`DRK, FSET, UNPROT`, `COLOR=GREEN`, `HILIGHT=OFF`, `INITIAL='________'`). `ERRMSG` at L197-L200. Footer `'ENTER=Sign-on  F3=Exit'` (`LENGTH=22`) at L201-L205. **HAS a `PASSWD` field** with `DRK` attribute (structural distinguisher vs. `COUSR0A` which has none).
- `app/cpy-bms/COSGN00.CPY` — Symbolic map copybook (152 lines). Defines input record `01 COSGN0AI` at L17 (input fields suffixed `I` with companion `*L`, `*F`, `*A`) and output record `01 COSGN0AO REDEFINES COSGN0AI` (output fields suffixed `O` plus `*C`, `*P`, `*H`, `*V` control characters).
- `app/cpy/CSUSR01Y.cpy` — `01 SEC-USER-DATA` 80-byte record (26 lines). Field layout: `SEC-USR-ID(8)` at L18 + `SEC-USR-FNAME(20)` at L19 + `SEC-USR-LNAME(20)` at L20 + `SEC-USR-PWD(8)` at L21 + `SEC-USR-TYPE(1)` at L22 + `SEC-USR-FILLER(23)` at L23 = 80 bytes total. `SEC-USR-PWD` is **plaintext** per AAP §0.1.3.
- `app/cpy/COCOM01Y.cpy` — `01 CARDDEMO-COMMAREA` (47 lines). Includes `CDEMO-GENERAL-INFO` with `CDEMO-USER-TYPE` at L26 (88-levels `CDEMO-USRTYP-ADMIN VALUE 'A'` at L27 and `CDEMO-USRTYP-USER VALUE 'U'` at L28) used by COSGN00C to choose the XCTL destination.
- `app/cpy/CSMSG01Y.cpy` — `01 CCDA-COMMON-MESSAGES` (24 lines). Contains `CCDA-MSG-THANK-YOU` (PF3 exit) at L18-L19 and `CCDA-MSG-INVALID-KEY` (`WHEN OTHER` AID) at L20-L21.

## Authority references

- AAP §0.2.1 (in-scope: `golden/cosgn00c/` directory tree as part of the
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (COSGN00C → CoSgn00C signon online program; one Java class per COBOL PROGRAM-ID; in
  `com.blitzy.carddemo.application.signon`).
- AAP §0.6.10 (sealed `UserType { Admin, User }` from 88-level conditions in
  `app/cpy/COCOM01Y.cpy`).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern
  until COBOL captures are committed).
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference
  implementation).
- AAP §0.1.3 (plaintext password preservation in `SEC-USR-PWD`; storage vs. logging separation).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is behavior including verbatim error messages
  and case-insensitive matching).
- AAP §0.7.2 (**SECURITY MANDATE — no password value in any log/display surface**).
- AAP §0.7.4 (no preview features; pattern-matching exhaustiveness — NO `default` branches).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep` placeholders, `input_scenario.txt`, `usrsec.txt`, or any other content. The `README.md` IS the directory's marker. All fixture data files for the COSGN00C parity test live in the sibling `../expected/` folder, because the consuming test class `CoSgn00CGoldenTest` resolves every fixture path through the base class helper `resolveExpectedOutputPath("cosgn00c", ...)`. Adding files here would create duplicate, stale, or unreachable fixtures. If a future test scenario requires additional inputs, add the new file to `../expected/` and extend the appropriate override in `CoSgn00CGoldenTest.java` (`auxiliaryInputs()` or a new path-returning method) — not here.
