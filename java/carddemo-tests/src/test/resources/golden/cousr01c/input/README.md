# COUSR01C Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture
data files. COUSR01C is the **admin-only user-add online CICS program**
(transaction `CU01`) translated to Java
`com.blitzy.carddemo.application.user.CoUsr01C`. The deterministic test
scenario file (`input_scenario.txt`) and the initial-state USRSEC fixture
(`usrsec.txt`) BOTH live in the **sibling `../expected/` folder**, because the
consuming Java test class `CoUsr01CGoldenTest` resolves all fixture paths
through the base class helper `resolveExpectedOutputPath("cousr01c", ...)` —
which routes to `src/test/resources/golden/cousr01c/expected/`. This
conventional `input/` subdirectory (per AAP §0.3.1 harness convention) is
preserved only to make the absence of fixture data files **explicit and
discoverable**, mirroring the `golden/cousr02c/input/` and
`golden/cousr03c/input/` sibling precedents.

## Why this folder is documentation-only

1. **Java harness resolution always routes to `expected/`**: the test class
   `com.blitzy.carddemo.tests.golden.CoUsr01CGoldenTest` extends
   `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and overrides
   `inputFile()`, `auxiliaryInputs()`, `expectedOutputFile()`, and
   `expectedOutputs()` all via the base class helper
   `resolveExpectedOutputPath(programDir, fileName)`, which ALWAYS routes to
   `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely:
   - `inputFile()` returns
     `resolveExpectedOutputPath("cousr01c", "input_scenario.txt")` →
     `../expected/input_scenario.txt`.
   - `auxiliaryInputs()` returns
     `List.of(resolveExpectedOutputPath("cousr01c", "usrsec.txt"))` →
     `../expected/usrsec.txt`.
2. **Shared fixture vocabulary**: both the COBOL CICS reference run AND the
   Java translation under test consume the SAME `input_scenario.txt` and
   `usrsec.txt`. The shared fixture vocabulary forces both implementations to
   start from the identical state — without this, byte-for-byte parity is
   meaningless. The sibling `../expected/` folder is the natural
   single-source-of-truth location.
3. **Sibling precedent**: this pattern matches `golden/cousr02c/input/` and
   `golden/cousr03c/input/`. Maintaining the same structure across the three
   USRSEC-mutating fixtures simplifies future test discovery and capture
   procedure documentation.
4. **AAP §0.3.1 compliance**: the AAP mandates the `<program>/input/` +
   `<program>/expected/` split. The presence of THIS folder (`input/`)
   preserves that compliance even when no input files live here.
5. **COUSR01C input is not file-based**: COUSR01C is an online CICS
   pseudo-conversational program whose user-facing input arrives via the BMS
   map `COUSR1A` defined in `app/bms/COUSR01.bms`. The 5 unprotected fields
   (`FNAME`, `LNAME`, `USERID`, `PASSWD`, `USRTYPE`) are populated by the
   operator. The source COBOL working-storage at
   `app/cbl/COUSR01C.cbl:L35-L44` declares
   `WS-PGMNAME PIC X(08) VALUE 'COUSR01C'`,
   `WS-TRANID PIC X(04) VALUE 'CU01'`,
   `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '`, and
   `WS-ERR-FLG PIC X(01) VALUE 'N'` with 88-conditions `ERR-FLG-ON` (`'Y'`)
   and `ERR-FLG-OFF` (`'N'`). Inputs are not file-based fixtures sourced from
   `app/data/ASCII/*.txt` (per AAP §0.4.1, USRSEC is **not** among the 9
   ASCII fixture files). The deterministic test scenario is instead encoded
   as a **synthesized scenario script** plus a **synthesized initial-state
   USRSEC fixture**, both of which are **test-owned scaffolding** authored
   alongside the captured expected outputs.

## Conceptual input contract (documented; data lives in ../expected/)

### BMS map input fields (5 editable fields, all populated on a single pass)

All five fields are GREEN, UNDERLINE-highlighted, and `UNPROT` per
`app/bms/COUSR01.bms`. Unlike COUSR02C/COUSR03C, all five fields are
populated by the operator from scratch on a single pass; there is no prior
READ that pre-populates any cell.

| Symbolic name | Screen position | Length | Editable | Attribute | Notes |
|---|---|---|---|---|---|
| `FNAMEI`   | row 8, col 18  | 20 | YES | FSET,**IC**,NORM,UNPROT | **initial cursor here** (distinguishes COUSR01C from COUSR02C/COUSR03C, where IC is on `USRIDIN`); First Name |
| `LNAMEI`   | row 8, col 56  | 20 | YES | FSET,NORM,UNPROT | Last Name |
| `USERIDI`  | row 11, col 15 | 8  | YES | FSET,NORM,UNPROT | New User ID (will become the `SEC-USR-ID` primary key) |
| `PASSWDI`  | row 11, col 55 | 8  | YES | **DRK**,FSET,UNPROT | Password; **DRK = hidden from terminal renderer**; bytes present in screen buffer |
| `USRTYPEI` | row 14, col 17 | 1  | YES | FSET,NORM,UNPROT | `'A'` admin / `'U'` user |

In the symbolic map copybook `app/cpy-bms/COUSR01.CPY`, these become input
fields on record `01 COUSR1AI`: `FNAMEI` (lines 55-60), `LNAMEI` (lines
61-66), `USERIDI` (lines 67-72), `PASSWDI` (lines 73-78 — the field also
present in the COUSR02 symbolic copybook but absent in COUSR03), `USRTYPEI`
(lines 79-84), and `ERRMSGI` (each with companion length `*L`, flag `*F`,
attribute `*A` fields). The output record `01 COUSR1AO REDEFINES COUSR1AI`
at line 91 provides per-field color (`*C`), pattern (`*P`), highlight
(`*H`), and validation (`*V`) control bytes.

### CICS pseudo-conversational dispatch (3 named AID keys + WHEN OTHER fallthrough)

The `EVALUATE EIBAID` block at `app/cbl/COUSR01C.cbl:L78-L105` routes each
AID key to the appropriate handler:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN DFHPF4
        PERFORM CLEAR-CURRENT-SCREEN
    WHEN OTHER
        MOVE 'Y' TO WS-ERR-FLG
        MOVE -1 TO FNAMEL OF COUSR1AI
        MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        PERFORM SEND-USRADD-SCREEN
END-EVALUATE
```

| AID Key | Behavior | Source Line |
|---|---|---|
| `DFHENTER` | `PERFORM PROCESS-ENTER-KEY` (validate 5 fields + WRITE new SEC-USER-DATA) | L91-92 |
| `DFHPF3`   | `MOVE 'COADM01C' TO CDEMO-TO-PROGRAM`; `PERFORM RETURN-TO-PREV-SCREEN` | L93-95 |
| `DFHPF4`   | `PERFORM CLEAR-CURRENT-SCREEN` (re-initialize fields; re-send empty screen) | L96-97 |
| WHEN OTHER | Set `WS-ERR-FLG='Y'`; `MOVE -1 TO FNAMEL`; `MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE`; `PERFORM SEND-USRADD-SCREEN` | L98-102 |

**COUSR01C handles only 3 named AID keys (NO `DFHPF5`, NO `DFHPF12`).** The
footer at (24, 1) advertises `'F12=Exit'` but the COBOL EIBAID dispatch does
NOT define a `WHEN DFHPF12` branch — PF12 falls through to `WHEN OTHER` and
produces `CCDA-MSG-INVALID-KEY`. Per AAP §0.7.1, this footer-vs-code
mismatch is preserved as-is.

### Single-pass ADD flow (NO two-pass like COUSR02C/COUSR03C; NO auto-trigger from COUSR00C)

- The operator populates all 5 input fields and presses ENTER **ONCE**.
- COUSR01C runs the 5 sequential empty-field validations in source order
  (FIRST empty field wins): `FNAMEI` → `LNAMEI` → `USERIDI` → `PASSWDI` →
  `USRTYPEI` per `app/cbl/COUSR01C.cbl:L115-L160`.
- If all 5 are non-empty, COUSR01C MOVEs them into the in-memory
  `SEC-USER-DATA` (`MOVE USERIDI TO SEC-USR-ID`, `MOVE FNAMEI TO
  SEC-USR-FNAME`, `MOVE LNAMEI TO SEC-USR-LNAME`, `MOVE PASSWDI TO
  SEC-USR-PWD`, `MOVE USRTYPEI TO SEC-USR-TYPE` — lines 154-158) and
  PERFORMs `WRITE-USER-SEC-FILE`.
- The `EXEC CICS WRITE` at `app/cbl/COUSR01C.cbl:L240-L248` returns
  `DFHRESP(NORMAL)`, `DFHRESP(DUPKEY)` ALSO `DFHRESP(DUPREC)`, or some other
  CICS response code; COUSR01C MOVEs the corresponding verbatim message into
  `WS-MESSAGE` and re-sends the screen.
- **There is no separate "confirm" pass like COUSR02C/COUSR03C** (which
  require ENTER followed by PF5). This single-pass simplicity is a
  distinguishing characteristic of COUSR01C.
- **There is no auto-trigger pattern like COUSR02C/COUSR03C** (which are
  invoked from `CoUsr00C` row `'U'` / `'D'` selection with `USRIDIN`
  pre-populated via `CDEMO-CU02-USR-SELECTED` /
  `CDEMO-CU03-USR-SELECTED`). COUSR01C is invoked from `COADM01C` with
  empty input fields; the operator types all 5 fields from scratch.
- **No in-program admin check**: the admin-only restriction is enforced
  upstream by the calling admin menu `COADM01C`. COUSR01C source contains
  NO admin guard.

### Conceptual fixture data (lives in ../expected/)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|--------------------|-----------------|------------------|---------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script: BMS keystroke sequences (5 input fields), AID key sequence (ENTER / PF3 / PF4), and confirmation responses; covers all scenarios listed in the `@Disabled` verification list |
| `usrsec.txt`         | `../expected/usrsec.txt`         | Test-owned scaffolding (synthesized) | 80-byte fixed-width USRSEC initial-state fixture with at least 3 deterministic users covering positive and negative scenarios; per `app/cpy/CSUSR01Y.cpy` layout; plaintext passwords preserved per AAP §0.1.3 |

Test scenarios that the scenario file MUST cover:

| Submission | AID | FNAME | LNAME | USERID | PASSWD | USRTYPE | Expected outcome |
|---|---|---|---|---|---|---|---|
| (a) Valid Admin add | ENTER | `'NEW'`   | `'ADMIN01'` | `'NEWADM01'` | `'NEW01PWD'` | `'A'` | `'User NEWADM01 has been added ...'` (DFHGREEN); WRITE NORMAL; `usrsec_after.txt` 80 bytes LONGER |
| (b) Valid User add  | ENTER | `'NEW'`   | `'USER01'`  | `'NEWUSR01'` | `'NEW02PWD'` | `'U'` | `'User NEWUSR01 has been added ...'` (DFHGREEN); WRITE NORMAL |
| (c) DUPKEY rejected | ENTER | `'X'`     | `'Y'`       | `'EXISTUSR'` | `'P'`        | `'U'` | `'User ID already exist...'`; WRITE DUPKEY/DUPREC; no file change |
| (d) FNAMEI empty    | ENTER | (blank)   | `'X'`       | `'X'`        | `'P'`        | `'U'` | `'First Name can NOT be empty...'`; no WRITE |
| (e) LNAMEI empty    | ENTER | `'X'`     | (blank)     | `'X'`        | `'P'`        | `'U'` | `'Last Name can NOT be empty...'`; no WRITE |
| (f) USERIDI empty   | ENTER | `'X'`     | `'X'`       | (blank)      | `'P'`        | `'U'` | `'User ID can NOT be empty...'`; no WRITE |
| (g) PASSWDI empty   | ENTER | `'X'`     | `'X'`       | `'X'`        | (blank)      | `'U'` | `'Password can NOT be empty...'`; no WRITE |
| (h) USRTYPEI empty  | ENTER | `'X'`     | `'X'`       | `'X'`        | `'P'`        | (blank) | `'User Type can NOT be empty...'`; no WRITE |
| (i) PF4 clear       | PF4   | (any)     | (any)       | (any)        | (any)        | (any) | empty screen re-sent; no WRITE |
| (j) PF3 back        | PF3   | (any)     | (any)       | (any)        | (any)        | (any) | XCTL to `COADM01C`; no WRITE |
| (k) PF12 fallthrough| PF12  | (any)     | (any)       | (any)        | (any)        | (any) | falls through to `WHEN OTHER`; `CCDA-MSG-INVALID-KEY`; no WRITE (footer-vs-code mismatch preserved) |

USRSEC fixture composition:

- An existing user `'EXISTUSR'` used for the DUPKEY rejection scenario (c).
- A control user whose record must be byte-identical between `usrsec.txt`
  and `usrsec_after.txt` for scenarios that do not WRITE.
- A baseline set of users (e.g., `'ADMIN001'`, `'TESTUSR1'`) provided so
  that `usrsec.txt` is non-trivial.

## Cross-reference to the Java test class

- The Java test class is
  `com.blitzy.carddemo.tests.golden.CoUsr01CGoldenTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr01CGoldenTest.java`.
  It extends the abstract base class
  `com.blitzy.carddemo.tests.golden.GoldenRecordTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`.
- The class under test is
  `com.blitzy.carddemo.application.user.CoUsr01C` (the Java translation of
  `app/cbl/COUSR01C.cbl`).
- Override summary:
  - `programClass()` →
    `com.blitzy.carddemo.application.user.CoUsr01C.class`.
  - `inputFile()` →
    `resolveExpectedOutputPath("cousr01c", "input_scenario.txt")` — routes
    to `../expected/input_scenario.txt`.
  - `expectedOutputFile()` →
    `resolveExpectedOutputPath("cousr01c", "stdout.txt")` — routes to
    `../expected/stdout.txt`.
  - `auxiliaryInputs()` →
    `List.of(resolveExpectedOutputPath("cousr01c", "usrsec.txt"))` — routes
    to `../expected/usrsec.txt`.
  - `expectedOutputs()` → three `ExpectedOutput` entries: `stdout.txt`,
    `bms_output.txt`, and `usrsec.txt` (mapped from
    `../expected/usrsec_after.txt`, the post-WRITE state, **80 bytes
    LONGER** than `../expected/usrsec.txt`).
- **All five overrides route to `../expected/` via the
  `resolveExpectedOutputPath(...)` helper. None routes to this `input/`
  folder.** This is the architectural reason the folder is empty.
- The test is `@Disabled` per AAP §0.6.11 ("Initial test scaffolding may
  use placeholder expected files marked `@Disabled` until COBOL captures
  are available; the harness skeleton, base class, and per-program test
  classes are created unconditionally"). The 8-point verification list:
  1. Admin-only access guard demonstrated at menu-level (NOT in-program);
     COUSR01C source contains no admin check.
  2. Valid new Admin user written with `SEC-USER-TYPE = 'A'`.
  3. Valid new User user written with `SEC-USER-TYPE = 'U'`.
  4. DUPKEY error on existing user-id.
  5. Invalid `SEC-USER-TYPE` rejected.
  6. Blank required-fields rejected (all five empty-field validations in
     source order).
  7. PLAINTEXT password preserved exactly (`PIC X(08)`) per AAP §0.1.3.
  8. Password NEVER appears in `stdout.txt` per AAP §0.7.2.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture
procedure) for the CICS COBOL build/run path used to capture `usrsec.txt`,
`usrsec_after.txt`, `stdout.txt`, and `bms_output.txt`. The
`input_scenario.txt` is test-owned scaffolding (NOT captured from COBOL);
it is the deterministic driver script for both the COBOL reference run and
the Java translation under test. The capture procedure exists because the
user prompt left this as a `[TODO]` marker (AAP §0.7.5: "To regenerate
golden-record fixtures from COBOL [TODO — document the COBOL build/run
path here]"). Until the capture is performed, the `../expected/` directory
holds placeholder/scaffolding files and the test is `@Disabled`.

## Behavioral invariants preserved by this fixture

- **Single-pass WRITE semantics**: ENTER triggers `PROCESS-ENTER-KEY`
  which validates 5 fields and (on success) directly calls
  `WRITE-USER-SEC-FILE`. No two-pass confirmation like COUSR02C/COUSR03C;
  no auto-trigger from COUSR00C row selection. Source:
  `app/cbl/COUSR01C.cbl:L115-L160, L238-L274`.
- **WRITE-creates-new-record semantics**: per
  `app/cbl/COUSR01C.cbl:L240-L248`, the program issues `EXEC CICS WRITE
  DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)`. COBOL VSAM WRITE creates
  a new 80-byte record. The Java translation's file-based adapter MUST
  replicate this — `usrsec_after.txt` MUST be exactly **80 bytes
  LONGER** than `usrsec.txt` per successful WRITE. This contrasts with
  COUSR02C (REWRITE → SAME byte length) and COUSR03C (DELETE →
  BYTE-SHORTER by 80).
- **5 sequential empty-field validations in source order; FIRST empty
  field wins**: per `app/cbl/COUSR01C.cbl:L117-L151`, the program
  `EVALUATE`s `FNAMEI` → `LNAMEI` → `USERIDI` → `PASSWDI` → `USRTYPEI`.
  As soon as any field is `SPACES OR LOW-VALUES`, the program emits the
  corresponding verbatim message and short-circuits (no WRITE). The test
  scenario MUST exercise each of the 5 empty cases with the OTHER 4
  fields populated, so the FIRST-empty-wins ordering is observable.
- **DUPKEY/DUPREC short-circuit unique to ADD**: per
  `app/cbl/COUSR01C.cbl:L260-L266`, if `SEC-USR-ID` matches an existing
  record's key, WRITE is rejected with `'User ID already exist...'` and
  NO file mutation occurs. `usrsec_after.txt` is byte-identical to
  `usrsec.txt` in this scenario.
- **Plaintext password preservation (AAP §0.1.3)**: `SEC-USR-PWD` stores
  passwords as `PIC X(08)` plaintext. The program writes the 8 plaintext
  bytes from `PASSWDI` directly into `SEC-USR-PWD` via `MOVE PASSWDI TO
  SEC-USR-PWD` (`app/cbl/COUSR01C.cbl:L157`). Any move to a
  password-hashing scheme would constitute a behavior change beyond
  migration scope and is explicitly OUT OF SCOPE for this refactor.
- **Password IS PRESENT in BMS output buffer with DRK attribute**
  (different surface from log stream): the `PASSWD` field at (11, 55) is
  defined with `ATTRB=(DRK,FSET,UNPROT)` in
  `app/bms/COUSR01.bms:L126-L130`. The DRK attribute causes the 3270
  renderer to hide the value from the operator, but the bytes ARE in the
  screen buffer. The Java translation's BMS-output simulation MUST
  replicate the byte-level behavior at screen position (11, 55).
- **No password in stdout (AAP §0.7.2)**: COUSR01C contains **ZERO
  active `DISPLAY` statements**. Line 268 is a commented-out `DISPLAY`
  (`* DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD`). Therefore the
  password NEVER reaches `stdout.txt` from this program. This is
  verification point 8 of the test class's `@Disabled` list and is
  asserted by inequality comparison. This is distinct from COUSR02C and
  COUSR03C, which have ACTIVE `DISPLAY` statements emitting
  `WS-RESP-CD` / `WS-REAS-CD` (but not the password) in their error
  paths.
- **8 verbatim messages** (each cited from source paragraph; each with
  EXACT 3-dot ASCII ellipsis):
  - `'First Name can NOT be empty...'` (`app/cbl/COUSR01C.cbl:L120`).
  - `'Last Name can NOT be empty...'` (`app/cbl/COUSR01C.cbl:L126`).
  - `'User ID can NOT be empty...'` (`app/cbl/COUSR01C.cbl:L132`).
  - `'Password can NOT be empty...'` (`app/cbl/COUSR01C.cbl:L138`).
  - `'User Type can NOT be empty...'` (`app/cbl/COUSR01C.cbl:L144`).
  - `'User ID already exist...'` (`app/cbl/COUSR01C.cbl:L263`,
    DUPKEY/DUPREC, `DFHRED` color).
  - `'Unable to Add User...'` (`app/cbl/COUSR01C.cbl:L270`, WHEN OTHER,
    `DFHRED` color).
  - `'User <id> has been added ...'`
    (`app/cbl/COUSR01C.cbl:L251-L259`, WRITE NORMAL success via
    `STRING`, `DFHGREEN` color, note the single space before the 3-dot
    ellipsis).
- **IC (initial cursor) on FNAME** (distinguishes COUSR01C from
  COUSR02C/COUSR03C where IC is on `USRIDIN`): per
  `app/bms/COUSR01.bms:L84-L88`, the `FNAME` field at (8, 18) carries
  the IC attribute. The Java BMS-output simulation MUST place the cursor
  at (8, 18) on screen render.
- **Footer-vs-code mismatch preserved as-is** (per AAP §0.7.1): the
  footer at (24, 1) reads `'ENTER=Add User  F3=Back  F4=Clear  F12=Exit'`
  (`app/bms/COUSR01.bms:L155-L159`) but `EVALUATE EIBAID` at
  `app/cbl/COUSR01C.cbl:L90-L103` does NOT define a `WHEN DFHPF12`
  branch. PF12 falls through to `WHEN OTHER`. This mismatch is
  preserved as-is — the Java translation MUST NOT silently add a PF12
  handler.
- **No in-program admin check**: COUSR01C source contains NO admin
  guard. The admin-only restriction is enforced upstream by `COADM01C`
  (the calling admin menu). Verification point 1 of the `@Disabled`
  list demonstrates this menu-level access guard.
- **Zero active COBOL `DISPLAY`s**: the only DISPLAY-shaped line in
  COUSR01C (line 268) is commented out. The Java translation MUST NOT
  introduce logger emissions of password values; `WS-RESP-CD` /
  `WS-REAS-CD` are not displayed either.
- **`@CobolProgram` traceability** (per AAP §0.7.1 documentation
  discipline): the Java class `CoUsr01C` MUST carry
  `@CobolProgram("COUSR01C")` Javadoc-style annotation citing the
  original PROGRAM-ID, source path `app/cbl/COUSR01C.cbl`, and the
  translation date.

## Source lineage

- `app/cbl/COUSR01C.cbl` — Admin-only user-add CICS COBOL program (~330
  lines). PROGRAM-ID `COUSR01C` at line 23; transaction `CU01`; key
  paragraphs: MAIN-PARA EIBAID dispatch at L78-L105; PROCESS-ENTER-KEY at
  L115-L160; WRITE-USER-SEC-FILE at L238-L274; CLEAR-CURRENT-SCREEN at
  L279-L282; INITIALIZE-ALL-FIELDS at L287-L295; sequential paragraphs
  also include RETURN-TO-PREV-SCREEN, SEND-USRADD-SCREEN,
  RECEIVE-USRADD-SCREEN, POPULATE-HEADER-INFO, SEND-MESSAGE.
- `app/bms/COUSR01.bms` — BMS map definition (~165 lines). MAPSET
  `COUSR01`, MAP `COUSR1A`, SIZE=(24,80), CTRL=(ALARM,FREEKB),
  EXTATT=YES, MODE=INOUT, LANG=COBOL. Editable fields: `FNAME` at
  (8, 18) with `IC`, `LNAME` at (8, 56), `USERID` at (11, 15), `PASSWD`
  at (11, 55) with `DRK`, `USRTYPE` at (14, 17). Footer at (24, 1):
  `'ENTER=Add User  F3=Back  F4=Clear  F12=Exit'`.
- `app/cpy-bms/COUSR01.CPY` — Symbolic map copybook (~165 lines).
  Defines input record `01 COUSR1AI` (input fields suffixed `I` —
  `FNAMEI` at lines 55-60, `LNAMEI` at lines 61-66, `USERIDI` at lines
  67-72, `PASSWDI` at lines 73-78, `USRTYPEI` at lines 79-84) and output
  record `01 COUSR1AO REDEFINES COUSR1AI` at line 91 (output fields with
  `*C`/`*P`/`*H`/`*V` control bytes).
- `app/cpy/CSUSR01Y.cpy` — `01 SEC-USER-DATA` 80-byte record. Field
  layout: `SEC-USR-ID(8)` + `SEC-USR-FNAME(20)` + `SEC-USR-LNAME(20)` +
  `SEC-USR-PWD(8)` + `SEC-USR-TYPE(1)` + `SEC-USR-FILLER(23)` = 80
  bytes total.

## Authority references

- AAP §0.1.3 (plaintext password preservation in `SEC-USR-PWD`;
  behavior-parity mandate; `SEC-USR-PWD` bytes preserved verbatim
  through WRITE).
- AAP §0.2.1 (in-scope: `golden/cousr01c/` directory tree as part of
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` +
  `<program>/expected/`).
- AAP §0.4.1 (COUSR01C → CoUsr01C user-add online program; one Java
  class per COBOL PROGRAM-ID).
- AAP §0.6.4 (`java.time` mandate).
- AAP §0.6.5 (`java.nio.file` mandate).
- AAP §0.6.6 (`ScopedValue` for cross-method batch-run context
  propagation).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate;
  `@Disabled` scaffolding pattern until COBOL captures are committed).
- AAP §0.6.12 (architectural override mandating plain Java 25 with no
  application container; file-based default).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is behavior including
  edge cases, error codes, message strings with exact ellipsis spacing,
  and footer-vs-code mismatch).
- AAP §0.7.2 (no card PAN logged in full; "Maintain all PCI-relevant
  controls (audit logging, data retention, masking)" — by extension, no
  plaintext password column appears in `stdout.txt`).
- AAP §0.7.4 (no JEP preview features).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data
files, `.gitkeep` placeholders, `input_scenario.txt`, `usrsec.txt`, or
any other content. The `README.md` IS the directory's marker.

All fixture data files for the COUSR01C parity test live in the sibling
`../expected/` folder, because the consuming test class
`CoUsr01CGoldenTest` resolves every fixture path through the base class
helper `resolveExpectedOutputPath("cousr01c", ...)`. Adding files here
would create duplicate, stale, or unreachable fixtures. If a future
test scenario requires additional inputs, add the new file to
`../expected/` and extend the appropriate override in
`CoUsr01CGoldenTest.java` (`auxiliaryInputs()` or a new path-returning
method) — not here. Modifying this folder requires updating
`CoUsr01CGoldenTest`, `GoldenRecordTest`, the capture procedure in
`java/MIGRATION_NOTES.md` §1.6, AND the sibling `cousr02c/`/`cousr03c/`
patterns for consistency.
