# COUSR03C Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture
data files. COUSR03C is the **admin-only user-delete online CICS program**
(transaction `CU03`) translated to Java
`com.blitzy.carddemo.application.user.CoUsr03C`. The deterministic test
scenario file (`input_scenario.txt`) and the initial-state USRSEC fixture
(`usrsec.txt`) BOTH live in the **sibling `../expected/` folder**, because the
consuming Java test class `CoUsr03CGoldenTest` resolves all fixture paths
through the base class helper `resolveExpectedOutputPath("cousr03c", ...)` —
which routes to `src/test/resources/golden/cousr03c/expected/`. This
conventional `input/` subdirectory (per AAP §0.6.11 harness convention) is
preserved only to make the absence of fixture data files **explicit and
discoverable**, mirroring the `golden/csutldtc/` sibling precedent.

## Why this folder is documentation-only

- COUSR03C is an **online CICS pseudo-conversational program** (transaction
  `CU03`). Its user-facing input arrives via the BMS map `COUSR3A` defined in
  `app/bms/COUSR03.bms` — specifically the single unprotected field `USRIDIN`
  (PIC X(8)) at screen position (6, 21).
- The source COBOL working-storage at `app/cbl/COUSR03C.cbl:L35-L47` declares
  the program-name, transaction-id, and USRSEC dataset literals:
  `WS-PGMNAME PIC X(08) VALUE 'COUSR03C'`,
  `WS-TRANID PIC X(04) VALUE 'CU03'`,
  `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '`.
- Inputs are not file-based fixtures sourced from `app/data/ASCII/*.txt` (per
  AAP §0.4.1, USRSEC is **not** among the 9 ASCII fixture files). The
  deterministic test scenario is instead encoded as a **synthesized scenario
  script** plus a **synthesized initial-state USRSEC fixture**, both
  **test-owned scaffolding** authored alongside the captured expected outputs.
- The consuming test class `CoUsr03CGoldenTest` resolves all fixture paths
  through the base class helper
  `resolveExpectedOutputPath(programDir, fileName)`, which maps every path to
  `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely:
  - `inputFile()` returns
    `resolveExpectedOutputPath("cousr03c", "input_scenario.txt")` →
    `../expected/input_scenario.txt`.
  - `auxiliaryInputs()` returns
    `List.of(resolveExpectedOutputPath("cousr03c", "usrsec.txt"))` →
    `../expected/usrsec.txt`.
- Therefore, NO fixture data files exist in this `input/` folder.

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map input: USRIDIN

- The sole user-input field is `USRIDIN` (PIC X(8)), defined at
  `app/bms/COUSR03.bms` lines 85-89 with attributes `(FSET,IC,NORM,UNPROT)`,
  `LENGTH=8`, `HILIGHT=UNDERLINE`, `COLOR=GREEN`.
- In the symbolic map copybook `app/cpy-bms/COUSR03.CPY`, this becomes the
  input field `USRIDINI PIC X(8)` on record `01 COUSR3AI` (with companion
  length field `USRIDINL` and attribute byte `USRIDINF`).
- Read-only display fields populated AFTER the READ on USRSEC:
  `FNAMEI PIC X(20)`, `LNAMEI PIC X(20)`, `USRTYPEI PIC X(1)`. The
  `ERRMSGI PIC X(78)` field receives the message text from `WS-MESSAGE`.

### CICS pseudo-conversational dispatch (5 AID keys)

The `EVALUATE EIBAID` block at `app/cbl/COUSR03C.cbl:L108-L130` routes each
AID key to the appropriate handler:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        ... XCTL to CDEMO-FROM-PROGRAM (default COADM01C)
    WHEN DFHPF4
        PERFORM CLEAR-CURRENT-SCREEN
    WHEN DFHPF5
        PERFORM DELETE-USER-INFO
    WHEN DFHPF12
        MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN OTHER
        MOVE 'Y' TO WS-ERR-FLG
        MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        PERFORM SEND-USRDEL-SCREEN
END-EVALUATE
```

The DELETE flow is **two-pass**:

1. **Pass 1 (ENTER key)** → `PROCESS-ENTER-KEY` paragraph
   (`app/cbl/COUSR03C.cbl:L142-L169`): the operator types a user-id into
   `USRIDIN`. After validation (non-empty), the program issues
   `EXEC CICS READ DATASET('USRSEC  ') INTO(SEC-USER-DATA) RIDFLD(SEC-USR-ID) UPDATE`.
   On `DFHRESP(NORMAL)`, the program populates `FNAMEI`/`LNAMEI`/`USRTYPEI`
   from `SEC-USR-FNAME`/`LNAME`/`TYPE` and displays the message
   `'Press PF5 key to delete this user ...'` (informational, `DFHNEUTR`
   color). **The record is NOT yet deleted.**
2. **Pass 2 (PF5 key)** → `DELETE-USER-INFO` paragraph
   (`app/cbl/COUSR03C.cbl:L174-L192`): the operator confirms with PF5. The
   program re-reads the record (via the same `READ UPDATE`) and issues
   `EXEC CICS DELETE DATASET('USRSEC  ')`. On `DFHRESP(NORMAL)`, the program
   emits the success message via
   `STRING 'User ' SEC-USR-ID ' has been deleted ...' INTO WS-MESSAGE`
   (`DFHGREEN` color).

### Auto-trigger flow from COUSR00C row 'D' selection

When COUSR03C is entered from COUSR00C (User List screen) with
`CDEMO-CU03-USR-SELECTED` populated (a row 'D' selection in the list), the
MAIN-PARA logic at `app/cbl/COUSR03C.cbl:L99-L104` copies the value to
`USRIDINI OF COUSR3AI` and immediately PERFORMs `PROCESS-ENTER-KEY` without
waiting for the operator to press ENTER. The auto-trigger advances the
program directly into Pass 1 (display-for-confirmation).

### Conceptual fixture data (lives in `../expected/`)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|--------------------|-----------------|------------------|---------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script: BMS keystroke sequence (USRIDIN field values), AID key sequence (ENTER / PF3 / PF4 / PF5 / PF12), and confirmation responses; covers scenarios (a)–(e) below |
| `usrsec.txt` | `../expected/usrsec.txt` | Test-owned scaffolding (synthesized) | 80-byte fixed-width USRSEC initial-state fixture with at least 4 deterministic users covering positive and negative scenarios; per `app/cpy/CSUSR01Y.cpy` layout; plaintext passwords preserved per AAP §0.1.3 |

Test scenarios that the scenario file MUST cover:

- (a) **Successful delete by entered user-id**: USRIDIN populated → ENTER
  (display record + confirmation prompt) → PF5 (confirm delete) → record
  removed.
- (b) **Auto-trigger flow from CoUsr00C row 'D'**: `CDEMO-CU03-USR-SELECTED`
  populated → immediate `PROCESS-ENTER-KEY` → PF5 confirm.
- (c) **User-id NOT found rejection**: USRIDIN populated with non-existent
  id → ENTER → READ NOTFND → error message `'User ID NOT found...'`
  (verbatim with trailing ellipsis).
- (d) **Delete cancellation via PF12**: USRIDIN populated → ENTER → PF12 →
  XCTL to `COADM01C` with NO DELETE issued; USRSEC record preserved
  unchanged.
- (e) **Non-admin user attempting delete**: rejected by the admin-only
  access guard inherited from the COSGN00C/COMEN01C/COADM01C chain.

USRSEC fixture composition (4 deterministic users):

- An admin user (`SEC-USR-TYPE = 'A'`) attempting the delete operations.
- A target user `'DELUSR01'` (`SEC-USR-TYPE = 'U'`) used for the
  successful-delete scenario.
- A target user `'DELUSR02'` used for the cancellation scenario.
- A NON-admin user (`SEC-USR-TYPE = 'U'`) attempting the delete (negative
  case for the admin-only access guard).

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.CoUsr03CGoldenTest`
  at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr03CGoldenTest.java`.
  It extends the abstract base class
  `com.blitzy.carddemo.tests.golden.GoldenRecordTest`.
- The class under test is `com.blitzy.carddemo.application.user.CoUsr03C`
  (the Java translation of `app/cbl/COUSR03C.cbl`).
- Override summary:
  - `programClass()` → `com.blitzy.carddemo.application.user.CoUsr03C.class`.
  - `inputFile()` →
    `resolveExpectedOutputPath("cousr03c", "input_scenario.txt")` — routes to
    `../expected/input_scenario.txt`.
  - `expectedOutputFile()` →
    `resolveExpectedOutputPath("cousr03c", "stdout.txt")` — routes to
    `../expected/stdout.txt`.
  - `auxiliaryInputs()` →
    `List.of(resolveExpectedOutputPath("cousr03c", "usrsec.txt"))` — routes
    to `../expected/usrsec.txt`.
  - `expectedOutputs()` → three `ExpectedOutput` entries: `stdout.txt`,
    `bms_output.txt`, and `usrsec.txt` (mapped from
    `../expected/usrsec_after.txt`, the post-DELETE state).
- **All five overrides route to `../expected/` via the
  `resolveExpectedOutputPath(...)` helper. None routes to this `input/`
  folder.** This is the architectural reason the folder is empty.
- The test is `@Disabled` with the following 7-point verification list (per
  AAP §0.6.11 — "Initial test scaffolding may use placeholder expected files
  marked `@Disabled` until COBOL captures are available"):
  1. Admin-only access guard.
  2. Valid delete by entered user-id (READ + DELETE).
  3. Auto-trigger flow from CoUsr00C row 'D' selection.
  4. User-id NOT found produces error.
  5. Confirmation prompt before DELETE.
  6. Record physically removed from `usrsec.txt` after DELETE (byte-shorter
     file).
  7. Password column NEVER appears in `stdout.txt` per AAP §0.7.2.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure
for COUSR03C) for the CICS COBOL build/run path used to capture expected
outputs. The capture procedure exists because the user prompt left this as
a `[TODO]` marker (AAP §0.7.5: "To regenerate golden-record fixtures from
COBOL [TODO — document the COBOL build/run path here]"). Until the capture
is performed, the `../expected/` directory holds placeholder/scaffolding
files and the test is `@Disabled`.

## Behavioral invariants preserved by this fixture

- **Two-pass DELETE confirmation**: the ENTER key only displays the record
  for confirmation; the PF5 key executes the actual `EXEC CICS DELETE`. Both
  passes are exercised by `input_scenario.txt`. Source:
  `app/cbl/COUSR03C.cbl:L142-L192`.
- **Physical DELETE semantics**: per `app/cbl/COUSR03C.cbl:L307-L311`, the
  program issues `EXEC CICS DELETE DATASET(WS-USRSEC-FILE)`. COBOL VSAM
  DELETE physically removes the record. The Java translation's file-based
  adapter MUST replicate this — `usrsec_after.txt` is byte-shorter than
  `usrsec.txt` by exactly 80 bytes per deleted record (one `SEC-USER-DATA`
  record per AAP §0.1.3 and `app/cpy/CSUSR01Y.cpy`).
- **Plaintext password preservation (AAP §0.1.3)**: `SEC-USR-PWD` stores
  passwords as `PIC X(08)` plaintext, preserved as-is in surviving USRSEC
  records. Any move to a password-hashing scheme would constitute a behavior
  change beyond migration scope and is OUT OF SCOPE.
- **No password in stdout (AAP §0.7.2)**: even though the `SEC-USR-PWD` byte
  range (bytes 49–56) is present in the USRSEC fixture, the password column
  NEVER appears in `stdout.txt` (verification point 7 of the `@Disabled`
  list).
- **Verbatim error messages** (each cited from the source paragraph):
  - `'User ID can NOT be empty...'` (PROCESS-ENTER-KEY, DELETE-USER-INFO —
    `app/cbl/COUSR03C.cbl:L147-L148, L179-L180`)
  - `'User ID NOT found...'` (READ NOTFND, DELETE NOTFND —
    `app/cbl/COUSR03C.cbl:L289, L325`)
  - `'Unable to lookup User...'` (READ WHEN OTHER —
    `app/cbl/COUSR03C.cbl:L296-L297`)
  - `'Unable to Update User...'` (DELETE WHEN OTHER —
    `app/cbl/COUSR03C.cbl:L332-L333`)
  - `'Press PF5 key to delete this user ...'` (READ NORMAL, informational —
    `app/cbl/COUSR03C.cbl:L283-L284`)
  - `'User <id> has been deleted ...'` (DELETE NORMAL, success via STRING —
    `app/cbl/COUSR03C.cbl:L318-L321`)
- **Auto-trigger first-pass** (`app/cbl/COUSR03C.cbl:L99-L104`): when
  entered from COUSR00C with `CDEMO-CU03-USR-SELECTED` populated, the
  program copies the value to `USRIDINI` and PERFORMs `PROCESS-ENTER-KEY`
  without waiting for ENTER. Scenario (b) exercises this path.
- **PF12 cancellation** (`app/cbl/COUSR03C.cbl:L123-L125`): PF12 XCTLs to
  `COADM01C` with NO DELETE issued; the USRSEC record under cancellation
  MUST be unchanged in `usrsec_after.txt`. Scenario (d) exercises this path.
- **`@CobolProgram` traceability** (per AAP §0.7.1 documentation
  discipline): the Java class `CoUsr03C` MUST carry
  `@CobolProgram("COUSR03C")` Javadoc-style annotation citing the original
  PROGRAM-ID, source path `app/cbl/COUSR03C.cbl`, and the translation date.

## Source lineage

- `app/cbl/COUSR03C.cbl` — Admin-only user-delete CICS COBOL program (340+
  lines). PROGRAM-ID `COUSR03C` at line 23; transaction `CU03`; paragraphs:
  MAIN-PARA, PROCESS-ENTER-KEY, DELETE-USER-INFO, RETURN-TO-PREV-SCREEN,
  SEND-USRDEL-SCREEN, RECEIVE-USRDEL-SCREEN, POPULATE-HEADER-INFO,
  READ-USER-SEC-FILE, DELETE-USER-SEC-FILE, CLEAR-CURRENT-SCREEN,
  SEND-MESSAGE.
- `app/bms/COUSR03.bms` — BMS map definition. MAPSET `COUSR03`, MAP
  `COUSR3A`, SIZE=(24,80). Sole input field `USRIDIN PIC X(8)` at (6,21).
- `app/cpy-bms/COUSR03.CPY` — Symbolic map copybook. Defines input record
  `01 COUSR3AI` (input fields suffixed `I`) and output record
  `01 COUSR3AO REDEFINES COUSR3AI` (output fields suffixed `O`).
- `app/cpy/CSUSR01Y.cpy` — `01 SEC-USER-DATA` 80-byte record:
  SEC-USR-ID(8) + SEC-USR-FNAME(20) + SEC-USR-LNAME(20) + SEC-USR-PWD(8) +
  SEC-USR-TYPE(1) + SEC-USR-FILLER(23) = 80 bytes total.

## Authority references

- AAP §0.2.1 (in-scope: `golden/cousr03c/` directory tree as part of
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` +
  `<program>/expected/`).
- AAP §0.4.1 (COUSR03C → CoUsr03C user-delete online program; one Java
  class per COBOL PROGRAM-ID).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled`
  scaffolding pattern until COBOL captures are committed).
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` is UNCHANGED and
  reserved as the reference implementation).
- AAP §0.1.3 (plaintext password preservation in `SEC-USR-PWD`;
  behavior-parity mandate).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is behavior including edge
  cases, error codes, and message strings).
- AAP §0.7.2 (no card PAN logged in full; "Maintain all PCI-relevant
  controls (audit logging, data retention, masking)" — by extension, no
  plaintext password column appears in stdout).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files,
`.gitkeep` placeholders, `input_scenario.txt`, `usrsec.txt`, or any other
content. The `README.md` IS the directory's marker.

All fixture data files for the COUSR03C parity test live in the sibling
`../expected/` folder, because the consuming test class
`CoUsr03CGoldenTest` resolves every fixture path through the base class
helper `resolveExpectedOutputPath("cousr03c", ...)`. Adding files here
would create duplicate, stale, or unreachable fixtures. If a future test
scenario requires additional inputs, add the new file to `../expected/`
and extend the appropriate override in `CoUsr03CGoldenTest.java`
(`auxiliaryInputs()` or a new path-returning method) — not here.
