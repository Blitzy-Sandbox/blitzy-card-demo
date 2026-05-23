# COUSR02C Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO
fixture data files. COUSR02C is the **admin-only user-update online CICS
program** (transaction `CU02`) translated to Java
`com.blitzy.carddemo.application.user.CoUsr02C`. The deterministic test
scenario file (`input_scenario.txt`) and the initial-state USRSEC fixture
(`usrsec.txt`) BOTH live in the **sibling `../expected/` folder**, because
the consuming Java test class `CoUsr02CGoldenTest` resolves all fixture
paths through `resolveExpectedOutputPath("cousr02c", ...)` — which routes
to `src/test/resources/golden/cousr02c/expected/`. This conventional
`input/` subdirectory (per AAP §0.6.11) is preserved only to make the
absence of fixture data files **explicit and discoverable**, mirroring the
`golden/csutldtc/` and `golden/cousr03c/` sibling precedents.

## Why this folder is documentation-only

- COUSR02C is an **online CICS pseudo-conversational program**
  (transaction `CU02`). Its user-facing input arrives via the BMS map
  `COUSR2A` defined in `app/bms/COUSR02.bms` — with five unprotected
  fields: `USRIDIN` (PIC X(8)) at (6, 21), `FNAME` (PIC X(20)) at
  (11, 18), `LNAME` (PIC X(20)) at (11, 56), `PASSWD` (PIC X(8)) at
  (13, 16) with `ATTRB=(DRK,FSET,UNPROT)`, and `USRTYPE` (PIC X(1)) at
  (15, 17).
- The source COBOL working-storage at `app/cbl/COUSR02C.cbl:L36-L47`
  declares: `WS-PGMNAME PIC X(08) VALUE 'COUSR02C'`,
  `WS-TRANID PIC X(04) VALUE 'CU02'`,
  `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '`, plus the
  change-detection flag `WS-USR-MODIFIED PIC X(01) VALUE 'N'` with
  88-conditions `USR-MODIFIED-YES` (`'Y'`) and `USR-MODIFIED-NO` (`'N'`).
- Inputs are not file-based fixtures sourced from `app/data/ASCII/*.txt`
  (per AAP §0.4.1, USRSEC is **not** among the 9 ASCII fixture files).
  The deterministic test scenario is instead encoded as a **synthesized
  scenario script** plus a **synthesized initial-state USRSEC fixture**,
  both **test-owned scaffolding** authored alongside the captured
  expected outputs.
- The consuming test class `CoUsr02CGoldenTest` resolves all fixture
  paths through `resolveExpectedOutputPath(programDir, fileName)`, which
  maps every path to
  `src/test/resources/golden/<programDir>/expected/<fileName>`.
  Concretely:
  - `inputFile()` →
    `resolveExpectedOutputPath("cousr02c", "input_scenario.txt")` →
    `../expected/input_scenario.txt`.
  - `auxiliaryInputs()` →
    `List.of(resolveExpectedOutputPath("cousr02c", "usrsec.txt"))` →
    `../expected/usrsec.txt`.
- Therefore, NO fixture data files exist in this `input/` folder. The
  conventional `input/` directory is preserved only to maintain the
  per-program `input/`+`expected/` pairing established by AAP §0.6.11
  and the `golden/csutldtc/` / `golden/cousr03c/` sibling precedents.

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map input fields (5 editable fields)

All five fields are GREEN, UNDERLINE-highlighted, and `UNPROT` per
`app/bms/COUSR02.bms`. Pass 1 reads only `USRIDIN`; the program then
populates the other four for in-place edit on Pass 2.

- `USRIDIN` (PIC X(8)) at (6, 21) — `ATTRB=(FSET,IC,NORM,UNPROT)`. The Pass-1
  sole input that identifies the user to update.
- `FNAME` (PIC X(20)) at (11, 18) — `ATTRB=(FSET,NORM,UNPROT)`.
- `LNAME` (PIC X(20)) at (11, 56) — `ATTRB=(FSET,NORM,UNPROT)`.
- `PASSWD` (PIC X(8)) at (13, 16) — `ATTRB=(DRK,FSET,UNPROT)`. **The DRK
  attribute hides the value on the 3270 screen renderer.** The field IS
  populated on Pass 1 from `SEC-USR-PWD` per `app/cbl/COUSR02C.cbl:L169`
  (`MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI`), but the rendered cells
  display blanks due to DRK.
- `USRTYPE` (PIC X(1)) at (15, 17) — `ATTRB=(FSET,NORM,UNPROT)`. Accepts
  `'A'` (Admin) or `'U'` (User).

In the symbolic map copybook `app/cpy-bms/COUSR02.CPY`, these become input
fields on record `01 COUSR2AI`: `USRIDINI`, `FNAMEI`, `LNAMEI`, `PASSWDI`
(the PASSWD group spans copybook lines 73-78 — **the field NOT present in
the COUSR03 symbolic copybook**), `USRTYPEI`, and `ERRMSGI`. The output
record `01 COUSR2AO REDEFINES COUSR2AI` provides per-field color (`*C`),
highlight (`*H`), pattern (`*P`), and validation (`*V`) control bytes;
`PASSWDC`/`PASSWDP`/`PASSWDH`/`PASSWDV` are defined at lines 148-152.

### CICS pseudo-conversational dispatch (6 AID keys)

The `EVALUATE EIBAID` block at `app/cbl/COUSR02C.cbl:L108-L131` routes each
AID key to the appropriate handler:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        PERFORM UPDATE-USER-INFO
        IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
            MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
        ELSE
            MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
        END-IF
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN DFHPF4
        PERFORM CLEAR-CURRENT-SCREEN
    WHEN DFHPF5
        PERFORM UPDATE-USER-INFO
    WHEN DFHPF12
        MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN OTHER
        MOVE 'Y' TO WS-ERR-FLG
        MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        PERFORM SEND-USRUPD-SCREEN
END-EVALUATE
```

The UPDATE flow is **two-pass**:

1. **Pass 1 (ENTER key)** → `PROCESS-ENTER-KEY`
   (`app/cbl/COUSR02C.cbl:L143-L172`): the operator types a user-id into
   `USRIDIN`. After validation (non-empty), the program clears the other
   editable fields (lines 158-161), MOVEs `USRIDINI` to `SEC-USR-ID`, and
   issues `EXEC CICS READ DATASET('USRSEC  ') ... UPDATE`. On
   `DFHRESP(NORMAL)`, the program populates
   `FNAMEI`/`LNAMEI`/`PASSWDI`/`USRTYPEI` from
   `SEC-USR-FNAME`/`LNAME`/`PWD`/`TYPE` (lines 167-170) and displays
   `'Press PF5 key to save your updates ...'` (informational, `DFHNEUTR`
   color). The `PASSWDI` field receives `SEC-USR-PWD` verbatim but is
   rendered as blanks due to BMS DRK. **The record is NOT yet rewritten.**
2. **Pass 2 (PF5 → Save / PF3 → Save & Exit)** → `UPDATE-USER-INFO`
   (`app/cbl/COUSR02C.cbl:L177-L245`): the operator edits any of
   `FNAME`/`LNAME`/`PASSWD`/`USRTYPE` and presses PF5 (stay on screen) or
   PF3 (save and XCTL to `CDEMO-FROM-PROGRAM`, default `COADM01C`). After
   the 5 empty-field validations, the program re-reads the record
   (`READ UPDATE`), executes the per-field change-detection IF blocks
   (lines 219-234) that compare each input field to `SEC-USR-*`, MOVEs
   differing values back, and SETs `USR-MODIFIED-YES TO TRUE`. If
   `USR-MODIFIED-YES`, the program issues
   `EXEC CICS REWRITE DATASET('USRSEC  ') FROM(SEC-USER-DATA)`. If no
   field changed, the program emits `'Please modify to update ...'`
   (`DFHRED` color via `ERRMSGC`) and issues NO REWRITE.

### Auto-trigger flow from COUSR00C row 'U' selection

When COUSR02C is entered from COUSR00C (User List screen) with
`CDEMO-CU02-USR-SELECTED` populated (a row `'U'` selection in the list),
the MAIN-PARA logic at `app/cbl/COUSR02C.cbl:L99-L105` copies the value
to `USRIDINI OF COUSR2AI` and immediately PERFORMs `PROCESS-ENTER-KEY`
without waiting for ENTER. The auto-trigger advances the program directly
into Pass 1 (display-for-edit with `PASSWDI` populated from `SEC-USR-PWD`
but rendered as blanks due to BMS DRK).

### Conceptual fixture data (lives in `../expected/`)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|--------------------|-----------------|------------------|---------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script: BMS keystroke sequences (USRIDIN on Pass 1; FNAME/LNAME/PASSWD/USRTYPE edits on Pass 2), AID key sequence (ENTER → PF3 or PF5 or PF12), and confirmation responses; covers all 8 scenarios (a)–(h) below |
| `usrsec.txt` | `../expected/usrsec.txt` | Test-owned scaffolding (synthesized) | 80-byte fixed-width USRSEC initial-state fixture with at least 4 deterministic users covering positive and negative scenarios; per `app/cpy/CSUSR01Y.cpy` layout; plaintext passwords preserved per AAP §0.1.3 |

Test scenarios that the scenario file MUST cover (per the test class's
8-point `@Disabled` verification list):

- (a) **Admin-only access guard**: non-admin operator
  (`SEC-USR-TYPE = 'U'`) attempting update is rejected upstream by the
  COSGN00C/COMEN01C/COADM01C chain.
- (b) **Valid update by entered user-id (READ + REWRITE)**: `USRIDIN`
  populated → ENTER → fields populated from `SEC-USR-*` → modify one or
  more fields → PF5 → REWRITE succeeds →
  `'User <id> has been updated ...'` (`DFHGREEN` color).
- (c) **Auto-trigger flow from CoUsr00C row 'U'**:
  `CDEMO-CU02-USR-SELECTED` populated → immediate `PROCESS-ENTER-KEY` →
  display-for-edit → PF5 → REWRITE.
- (d) **User-id NOT found** (READ NOTFND): non-existent id → ENTER →
  `'User ID NOT found...'` (verbatim with trailing 3-dot ellipsis).
- (e) **Invalid SEC-USER-TYPE rejected**: operator inputs a `USRTYPE`
  value other than `'A'` or `'U'`; per the COBOL semantics the program
  stores any non-blank byte and downstream consumers reject it. The test
  asserts the byte-for-byte echo to USRSEC.
- (f) **Blank required-fields rejected**: each of the 5 fields empty →
  corresponding verbatim error (per `app/cbl/COUSR02C.cbl:L177-L213`).
- (g) **Password update preserves plaintext bytes** (AAP §0.1.3): a
  `PASSWD` edit from `'OLDPWD01'` to `'NEWPWD01'` results in
  `usrsec_after.txt` containing the new 8-byte plaintext value in bytes
  49–56 of the corresponding record.
- (h) **Password NEVER appears in `stdout.txt`** (AAP §0.7.2): even
  though `PASSWDI` receives `SEC-USR-PWD` at line 169 of
  `app/cbl/COUSR02C.cbl`, no `DISPLAY` statement emits `SEC-USR-PWD`; the
  only `DISPLAY` statements (lines 347, 384) emit `WS-RESP-CD` and
  `WS-REAS-CD` only.

USRSEC fixture composition (4 deterministic users):

- Admin user `'ADMIN001'` (`SEC-USR-TYPE = 'A'`) attempting the updates.
- Target user `'UPDUSR01'` (`SEC-USR-TYPE = 'U'`) for the
  successful-update scenario (b)/(g).
- Target user `'UPDUSR02'` for the auto-trigger scenario (c) and the
  cancellation scenario.
- Untouched control user `'KEEPUSR1'` whose record must be byte-identical
  in `usrsec_after.txt`.

## Cross-reference to the Java test class

- The Java test class is
  `com.blitzy.carddemo.tests.golden.CoUsr02CGoldenTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr02CGoldenTest.java`.
  It extends the abstract base class
  `com.blitzy.carddemo.tests.golden.GoldenRecordTest`.
- The class under test is `com.blitzy.carddemo.application.user.CoUsr02C`
  (the Java translation of `app/cbl/COUSR02C.cbl`).
- Override summary:
  - `programClass()` → `com.blitzy.carddemo.application.user.CoUsr02C.class`.
  - `inputFile()` →
    `resolveExpectedOutputPath("cousr02c", "input_scenario.txt")` →
    `../expected/input_scenario.txt`.
  - `expectedOutputFile()` →
    `resolveExpectedOutputPath("cousr02c", "stdout.txt")` →
    `../expected/stdout.txt`.
  - `auxiliaryInputs()` →
    `List.of(resolveExpectedOutputPath("cousr02c", "usrsec.txt"))` →
    `../expected/usrsec.txt`.
  - `expectedOutputs()` → three `ExpectedOutput` entries: `stdout.txt`,
    `bms_output.txt`, and `usrsec.txt` (mapped from
    `../expected/usrsec_after.txt`, the post-REWRITE state with SAME byte
    length as `../expected/usrsec.txt`).
- **All five overrides route to `../expected/` via the
  `resolveExpectedOutputPath(...)` helper. None routes to this `input/`
  folder.** This is the architectural reason the folder is empty.
- The test is `@Disabled` per AAP §0.6.11 ("Initial test scaffolding may
  use placeholder expected files marked `@Disabled` until COBOL captures
  are available"). The 8-point verification list:
  1. Admin-only access guard.
  2. Valid update by entered user-id (READ + REWRITE).
  3. Auto-trigger flow from CoUsr00C row 'U' selection.
  4. User-id NOT found produces error.
  5. Invalid SEC-USER-TYPE rejected.
  6. Blank required-fields rejected.
  7. Password update preserves plaintext bytes (AAP §0.1.3).
  8. Password NEVER appears in `stdout.txt` per AAP §0.7.2.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure
for COUSR02C) for the CICS COBOL build/run path used to capture expected
outputs. The capture procedure exists because the user prompt left this as
a `[TODO]` marker (AAP §0.7.5: "To regenerate golden-record fixtures from
COBOL [TODO — document the COBOL build/run path here]"). Until the capture
is performed, the `../expected/` directory holds placeholder/scaffolding
files and the test is `@Disabled`.

## Behavioral invariants preserved by this fixture

- **Two-pass UPDATE flow**: ENTER displays and populates the four editable
  fields (including `PASSWDI`); PF3 or PF5 issues the actual
  `EXEC CICS REWRITE`. Both passes are exercised by `input_scenario.txt`.
  Source: `app/cbl/COUSR02C.cbl:L143-L245`.
- **In-place REWRITE semantics** (NOT physical delete — the key contrast
  with COUSR03C): per `app/cbl/COUSR02C.cbl:L360-L366`, the program issues
  `EXEC CICS REWRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)`. VSAM
  REWRITE updates the record in place, preserving its 80-byte slot. The
  Java translation MUST replicate this — `usrsec_after.txt` MUST be the
  SAME byte length as `usrsec.txt`, with only the content of the updated
  record(s) changed. (For COUSR03C, by contrast, `usrsec_after.txt` is
  byte-shorter by exactly 80 bytes per deleted record.)
- **Modification tracking via `USR-MODIFIED` flag**
  (`app/cbl/COUSR02C.cbl:L219-L243`): the program compares each input
  field (`FNAMEI`/`LNAMEI`/`PASSWDI`/`USRTYPEI`) to its `SEC-USR-*`
  counterpart. If ALL four match, the program emits
  `'Please modify to update ...'` (`DFHRED` color) and issues NO REWRITE;
  otherwise it issues the REWRITE. An additional scenario MUST cover the
  no-modification short-circuit to assert no REWRITE side effect.
- **Plaintext password preservation (AAP §0.1.3)**: `SEC-USR-PWD` stores
  passwords as `PIC X(08)` plaintext. The program flows plaintext bytes
  through the full READ → display (`PASSWDI` via line 169) → edit →
  REWRITE cycle. Any move to a password-hashing scheme is OUT OF SCOPE.
- **Password movement into BMS map** (`app/cbl/COUSR02C.cbl:L169`):
  `MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI` writes the plaintext password
  into the symbolic map, but the BMS attribute `(DRK,FSET,UNPROT)` on
  `PASSWD` causes the 3270 renderer to hide the value. The Java
  translation MUST replicate the byte-level behavior (`PASSWDI` contains
  `SEC-USR-PWD`) while the BMS-output simulation MUST omit the cell
  contents (or write blanks) at screen position (13, 16).
- **No password in stdout (AAP §0.7.2)**: even though `SEC-USR-PWD`
  reaches `PASSWDI` on Pass 1, NO `DISPLAY` statement emits the password
  value. The only two `DISPLAY` statements
  (`app/cbl/COUSR02C.cbl:L347, L384`) emit
  `'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` only. This is verification
  point 8 of the test class's `@Disabled` list.
- **5 empty-field validations** (`app/cbl/COUSR02C.cbl:L179-L213`), each
  producing a verbatim error with exact 3-dot trailing ellipsis:
  `'User ID can NOT be empty...'` (PROCESS-ENTER-KEY L148; UPDATE-USER-INFO
  L182), `'First Name can NOT be empty...'` (L188),
  `'Last Name can NOT be empty...'` (L194),
  `'Password can NOT be empty...'` (L200),
  `'User Type can NOT be empty...'` (L206).
- **READ/REWRITE outcome messages** (verbatim, each cited from source):
  - `'Press PF5 key to save your updates ...'` (READ NORMAL,
    informational, `DFHNEUTR` — `app/cbl/COUSR02C.cbl:L336-L339`).
  - `'User ID NOT found...'` (READ NOTFND L342; REWRITE NOTFND L379).
  - `'Unable to lookup User...'` (READ WHEN OTHER — L349).
  - `'Unable to Update User...'` (REWRITE WHEN OTHER — L386).
  - `'User <id> has been updated ...'` (REWRITE NORMAL success via STRING
    — `app/cbl/COUSR02C.cbl:L372-L375`, `DFHGREEN`).
  - `'Please modify to update ...'` (no-change short-circuit —
    `app/cbl/COUSR02C.cbl:L239-L242`, `DFHRED`).
- **Auto-trigger first-pass** (`app/cbl/COUSR02C.cbl:L99-L105`): when
  entered from COUSR00C with `CDEMO-CU02-USR-SELECTED` populated, the
  program copies the value to `USRIDINI` and PERFORMs `PROCESS-ENTER-KEY`
  without waiting for ENTER. Scenario (c) exercises this path.
- **PF12 cancellation** (`app/cbl/COUSR02C.cbl:L124-L126`): PF12 MOVEs
  `'COADM01C'` to `CDEMO-TO-PROGRAM` and PERFORMs
  `RETURN-TO-PREV-SCREEN` with NO REWRITE issued. The USRSEC record under
  cancellation MUST be unchanged in `usrsec_after.txt` (byte-identical to
  `usrsec.txt`).
- **`@CobolProgram` traceability** (per AAP §0.7.1 documentation
  discipline): the Java class `CoUsr02C` MUST carry
  `@CobolProgram("COUSR02C")` Javadoc-style annotation citing the
  original PROGRAM-ID, source path `app/cbl/COUSR02C.cbl`, and the
  translation date.

## Source lineage

- `app/cbl/COUSR02C.cbl` — Admin-only user-update CICS COBOL program
  (415+ lines). PROGRAM-ID `COUSR02C` at line 23; transaction `CU02`; key
  paragraphs: MAIN-PARA EIBAID dispatch at L108-L131; PROCESS-ENTER-KEY
  at L143-L172; UPDATE-USER-INFO at L177-L245; READ-USER-SEC-FILE at
  L320-L353; UPDATE-USER-SEC-FILE at L358-L390; supporting paragraphs
  RETURN-TO-PREV-SCREEN, SEND-USRUPD-SCREEN, RECEIVE-USRUPD-SCREEN,
  POPULATE-HEADER-INFO, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS.
- `app/bms/COUSR02.bms` — BMS map. MAPSET `COUSR02`, MAP `COUSR2A`,
  SIZE=(24,80). Editable fields: `USRIDIN` (sole Pass-1 input at (6, 21)),
  `FNAME` (11, 18), `LNAME` (11, 56), **`PASSWD` (13, 16) with
  `ATTRB=(DRK,FSET,UNPROT)`**, `USRTYPE` (15, 17). Footer at (24, 1):
  `'ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel'`.
- `app/cpy-bms/COUSR02.CPY` — Symbolic map copybook. Defines
  `01 COUSR2AI` (input fields suffixed `I`, including the `PASSWDI` group
  spanning lines 73-78 — the field NOT present in the COUSR03 symbolic
  copybook) and `01 COUSR2AO REDEFINES COUSR2AI` (output fields with
  `*C`/`*P`/`*H`/`*V` control bytes — `PASSWDC`/`PASSWDP`/`PASSWDH`/
  `PASSWDV` at lines 148-152).
- `app/cpy/CSUSR01Y.cpy` — `01 SEC-USER-DATA` 80-byte record:
  `SEC-USR-ID(8)` + `SEC-USR-FNAME(20)` + `SEC-USR-LNAME(20)` +
  `SEC-USR-PWD(8)` + `SEC-USR-TYPE(1)` + `SEC-USR-FILLER(23)` = 80 bytes.

## Authority references

- AAP §0.2.1 (in-scope: `golden/cousr02c/` directory tree as part of
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` +
  `<program>/expected/`).
- AAP §0.4.1 (COUSR02C → CoUsr02C user-update online program; one Java
  class per COBOL PROGRAM-ID).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled`
  scaffolding pattern until COBOL captures are committed).
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` is UNCHANGED and
  reserved as the reference implementation).
- AAP §0.1.3 (plaintext password preservation in `SEC-USR-PWD`;
  behavior-parity mandate).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is behavior including edge
  cases, error codes, and message strings with exact ellipsis spacing).
- AAP §0.7.2 (no card PAN logged in full; "Maintain all PCI-relevant
  controls (audit logging, data retention, masking)" — by extension, no
  plaintext password column appears in stdout).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files,
`.gitkeep` placeholders, `input_scenario.txt`, `usrsec.txt`, or any other
content. The `README.md` IS the directory's marker.

All fixture data files for the COUSR02C parity test live in the sibling
`../expected/` folder, because the consuming test class
`CoUsr02CGoldenTest` resolves every fixture path through the base class
helper `resolveExpectedOutputPath("cousr02c", ...)`. Adding files here
would create duplicate, stale, or unreachable fixtures. If a future test
scenario requires additional inputs, add the new file to `../expected/`
and extend the appropriate override in `CoUsr02CGoldenTest.java`
(`auxiliaryInputs()` or a new path-returning method) — not here.
