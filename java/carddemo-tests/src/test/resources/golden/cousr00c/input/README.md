# COUSR00C Golden-Record Fixtures — `input/` (Documentation-Only)

This folder is **intentionally documentation-only** — it contains NO fixture data files. COUSR00C is the **admin-only user-list online CICS program** (transaction `CU00`) translated to Java `com.blitzy.carddemo.application.user.CoUsr00C`. COUSR00C is the **navigation hub** for user-administration: row `'U'` selections XCTL to `COUSR02C` (Update User) and row `'D'` selections XCTL to `COUSR03C` (Delete User), passing the selected user-id via the commarea field `CDEMO-CU00-USR-SELECTED`. The deterministic test scenario file (`input_scenario.txt`) and the initial-state USRSEC fixture (`usrsec.txt`) BOTH live in the **sibling `../expected/` folder**, because the consuming Java test class `CoUsr00CGoldenTest` resolves all fixture paths through the base class helper `resolveExpectedOutputPath("cousr00c", ...)` — which routes to `src/test/resources/golden/cousr00c/expected/`. This conventional `input/` subdirectory (per AAP §0.6.11 harness convention) is preserved only to make the absence of fixture data files **explicit and discoverable**, mirroring the sibling precedents in `golden/cousr01c/`, `golden/cousr02c/`, `golden/cousr03c/`, and `golden/csutldtc/`.

## Why this folder is documentation-only

- COUSR00C is an **online CICS pseudo-conversational program** (transaction `CU00`). Its user-facing input
  arrives via the BMS map `COUSR0A` defined in `app/bms/COUSR00.bms` — specifically: the single user-id
  search field `USRIDIN` (PIC X(8)), the ten 1-character selection fields `SEL0001`-`SEL0010` (one per
  displayed user row), and AID-key dispatch (ENTER, PF3, PF7, PF8).
- The source COBOL working-storage at `app/cbl/COUSR00C.cbl:L35-L54` declares the program-name,
  transaction-id, and USRSEC dataset literals: `WS-PGMNAME PIC X(08) VALUE 'COUSR00C'`,
  `WS-TRANID PIC X(04) VALUE 'CU00'`, `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '` (2-space padding).
  The commarea sub-record at `app/cbl/COUSR00C.cbl:L67-L75` defines `CDEMO-CU00-INFO` with
  `USRID-FIRST`, `USRID-LAST`, `PAGE-NUM`, `NEXT-PAGE-FLG`, `USR-SEL-FLG`, and `USR-SELECTED` — the
  cross-program selection vehicle driving the navigation hub role.
- Inputs are not file-based fixtures sourced from `app/data/ASCII/*.txt` (per AAP §0.4.1, USRSEC is
  **not** among the 9 ASCII fixture files). The deterministic test scenario is instead encoded as a
  **synthesized scenario script** plus a **synthesized initial-state USRSEC fixture**, both
  **test-owned scaffolding** authored alongside the captured expected outputs in `../expected/`.
- The consuming test class `CoUsr00CGoldenTest` resolves all fixture paths through
  `resolveExpectedOutputPath(programDir, fileName)`, which maps every path to
  `src/test/resources/golden/<programDir>/expected/<fileName>`. Concretely: `inputFile()` returns
  `resolveExpectedOutputPath("cousr00c", "input_scenario.txt")` → `../expected/input_scenario.txt`;
  `auxiliaryInputs()` returns `List.of(resolveExpectedOutputPath("cousr00c", "usrsec.txt"))` →
  `../expected/usrsec.txt`.
- Therefore, NO fixture data files exist in this `input/` folder. The conventional `input/` directory
  is preserved only to maintain the per-program `input/` + `expected/` pairing established by AAP §0.6.11.

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map inputs: USRIDIN + 10 SEL000N fields

- Primary user-input fields on the COUSR0A map:
  - `USRIDIN` (PIC X(8)) at screen position (6, 21) — search/positioning user-id; cite
    `app/bms/COUSR00.bms:L95-L98` for attributes `ATTRB=(FSET,NORM,UNPROT)`, `LENGTH=8`,
    `HILIGHT=UNDERLINE`, `COLOR=GREEN`.
  - `SEL0001` - `SEL0010` (PIC X(1) each) at column 6 of rows 10-19 — one-character row-selection
    fields; cite `app/bms/COUSR00.bms:L153-L158` for `SEL0001` attributes `ATTRB=(FSET,NORM,UNPROT)`,
    `LENGTH=1`, `HILIGHT=UNDERLINE`, `COLOR=GREEN`, `POS=(10,6)`, `INITIAL=' '`.
- Read-only display fields (output only — populated after STARTBR/READNEXT): `USRID01`-`USRID10`
  (8-char) from `SEC-USR-ID`; `FNAME01`-`FNAME10` (20-char) from `SEC-USR-FNAME`;
  `LNAME01`-`LNAME10` (20-char) from `SEC-USR-LNAME`; `UTYPE01`-`UTYPE10` (1-char) from
  `SEC-USR-TYPE`; `PAGENUM` (8-char numeric) current page number. The `ERRMSGI PIC X(78)` field
  receives message text from `WS-MESSAGE` (per `app/cbl/COUSR00C.cbl:L38`).
- **CRITICAL invariant: NO PASSWD field on the COUSR0A map** — verified absence in
  `app/bms/COUSR00.bms` and `app/cpy-bms/COUSR00.CPY`. This distinguishes COUSR00C (and COUSR03C)
  from COUSR01C and COUSR02C, which both have a `PASSWD` field with `DRK` attribute.

### CICS pseudo-conversational dispatch (4 AID keys + OTHER)

The `EVALUATE EIBAID` block at `app/cbl/COUSR00C.cbl:L120-L138` routes each AID key to its handler:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN DFHPF7
        PERFORM PROCESS-PF7-KEY
    WHEN DFHPF8
        PERFORM PROCESS-PF8-KEY
    WHEN OTHER
        MOVE 'Y'                       TO WS-ERR-FLG
        MOVE -1                        TO USRIDINL OF COUSR0AI
        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
        PERFORM SEND-USRLST-SCREEN
END-EVALUATE
```

The browse-with-pagination flow is **READ-ONLY**:

1. **Initial entry (first reentry pass)** — `app/cbl/COUSR00C.cbl:L115-L119`: when `NOT
   CDEMO-PGM-REENTER`, the program clears the output buffer, performs `PROCESS-ENTER-KEY` (which
   then performs `PROCESS-PAGE-FORWARD`), and sends the screen showing page 1.
2. **ENTER (PROCESS-ENTER-KEY)** — `app/cbl/COUSR00C.cbl:L149-L232`: scans the 10 `SEL000NI` fields;
   if any is populated, captures the selection flag (`'U'`/`'u'` or `'D'`/`'d'`) and the
   corresponding `USRID0NI` into `CDEMO-CU00-USR-SEL-FLG` / `CDEMO-CU00-USR-SELECTED`, then XCTLs
   to `COUSR02C` or `COUSR03C`. If no selection, resets `CDEMO-CU00-PAGE-NUM` to 0 and performs
   `PROCESS-PAGE-FORWARD`.
3. **PF7 backward (PROCESS-PF7-KEY)** — `app/cbl/COUSR00C.cbl:L237-L255`: if `CDEMO-CU00-PAGE-NUM
   > 1`, performs `PROCESS-PAGE-BACKWARD`; else emits `'You are already at the top of the page...'`.
4. **PF8 forward (PROCESS-PF8-KEY)** — `app/cbl/COUSR00C.cbl:L260-L277`: if `NEXT-PAGE-YES`, performs
   `PROCESS-PAGE-FORWARD`; else emits `'You are already at the bottom of the page...'`.
5. **PF3 back** — `app/cbl/COUSR00C.cbl:L125-L127`: XCTL to `COADM01C` (admin menu — NOT
   `COSGN00C`; `COSGN00C` is reached only via the `EIBCALEN = 0` first-time-entry path at
   `app/cbl/COUSR00C.cbl:L110-L112`).

### Navigation hub role (originator for COUSR02C and COUSR03C)

Per `app/cbl/COUSR00C.cbl:L187-L215`, COUSR00C is the **navigation hub** from which COUSR02C (Update
User) and COUSR03C (Delete User) flows originate:

- Row selected with `'U'` or `'u'` (case-insensitive — cite `app/cbl/COUSR00C.cbl:L190-L191`): MOVE
  `USRID0NI` to `CDEMO-CU00-USR-SELECTED`, MOVE `'COUSR02C'` to `CDEMO-TO-PROGRAM`,
  `EXEC CICS XCTL PROGRAM('COUSR02C') COMMAREA(CARDDEMO-COMMAREA)`.
- Row selected with `'D'` or `'d'` (cite `app/cbl/COUSR00C.cbl:L200-L201`): MOVE `USRID0NI` to
  `CDEMO-CU00-USR-SELECTED`, MOVE `'COUSR03C'` to `CDEMO-TO-PROGRAM`,
  `EXEC CICS XCTL PROGRAM('COUSR03C') COMMAREA(CARDDEMO-COMMAREA)`.
- COUSR02C and COUSR03C each have their own commarea-info sub-record (`CDEMO-CU02-USR-SELECTED` and
  `CDEMO-CU03-USR-SELECTED` respectively, defined in `app/cpy/COCOM01Y.cpy`), but COUSR00C writes to
  its OWN `CDEMO-CU00-USR-SELECTED` field. Cross-program selection propagation is via the commarea
  overlay structure in COCOM01Y; each downstream program's auto-trigger first-pass logic reads its
  own sub-record.

### Conceptual fixture data (lives in `../expected/`)

| Conceptual fixture | Actual location | Source of inputs | Purpose |
|--------------------|-----------------|------------------|---------|
| `input_scenario.txt` | `../expected/input_scenario.txt` | Test-owned scaffolding (synthesized) | Deterministic CICS pseudo-conversation script: 12 scenarios covering initial display, PF8 forward × 3, PF7 backward × 3, row `'U'`/`'D'` XCTLs, invalid selection, USRIDIN positioning, PF3 back. |
| `usrsec.txt` | `../expected/usrsec.txt` | Test-owned scaffolding (synthesized) | 80-byte fixed-width USRSEC initial-state fixture with 22 deterministic users (1 admin + 21 regular) — exercises 3-page pagination (page 1 = users 1-10, page 2 = 11-20, page 3 = 21-22 partial); per `app/cpy/CSUSR01Y.cpy` layout; plaintext passwords preserved per AAP §0.1.3. |

Test scenarios that the scenario file MUST cover:

- **Scenario 1** — Initial display (admin signed on, `EIBCALEN > 0`): STARTBR + READNEXT × 10; page
  1 displayed; `PAGENUMO = '00000001'`.
- **Scenario 2** — PF8 forward → page 2 (users 11-20); `PAGENUMO = '00000002'`.
- **Scenario 3** — PF8 forward → page 3 (partial; users 21-22); `PAGENUMO = '00000003'`; ERRMSG =
  `'You have reached the bottom of the page...'`.
- **Scenario 4** — PF8 at last page → boundary message
  `'You are already at the bottom of the page...'`; no navigation.
- **Scenario 5** — PF7 backward → page 2.
- **Scenario 6** — PF7 backward → page 1.
- **Scenario 7** — PF7 at page 1 → boundary message `'You are already at the top of the page...'`.
- **Scenario 8** — ENTER with `'U'` on row 5 → XCTL `COUSR02C`; `CDEMO-CU00-USR-SELECTED` populated.
- **Scenario 9** — ENTER with `'D'` on row 3 → XCTL `COUSR03C`; `CDEMO-CU00-USR-SELECTED` populated.
- **Scenario 10** — ENTER with invalid selection `'X'` → message
  `'Invalid selection. Valid values are U and D'`.
- **Scenario 11** — USRIDIN positioning + ENTER → page repositioned at the typed user-id. STARTBR
  with `GTEQ` commented out at `app/cbl/COUSR00C.cbl:L592` means exact key match is required.
- **Scenario 12** — PF3 → XCTL `COADM01C` (admin menu, NOT `COSGN00C`).

USRSEC fixture composition (22 records): 1 admin user (`SEC-USR-TYPE = 'A'`) + 21 regular users
(`SEC-USR-TYPE = 'U'`); sorted ascending by `SEC-USR-ID` (VSAM KSDS browse sequence); file size = 22
× 80 = 1760 bytes exactly; pagination boundaries: page 1 = records 1-10, page 2 = 11-20, page 3 =
21-22 (partial).

## Cross-reference to the Java test class

- The Java test class is `com.blitzy.carddemo.tests.golden.CoUsr00CGoldenTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr00CGoldenTest.java`. It
  extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` at
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`.
- The class under test is `com.blitzy.carddemo.application.user.CoUsr00C` (the Java translation of
  `app/cbl/COUSR00C.cbl`).
- Override summary:
  - `programClass()` → `com.blitzy.carddemo.application.user.CoUsr00C.class`.
  - `inputFile()` → `resolveExpectedOutputPath("cousr00c", "input_scenario.txt")` — routes to
    `../expected/input_scenario.txt`.
  - `expectedOutputFile()` → `resolveExpectedOutputPath("cousr00c", "stdout.txt")` — routes to
    `../expected/stdout.txt`.
  - `auxiliaryInputs()` → `List.of(resolveExpectedOutputPath("cousr00c", "usrsec.txt"))` — routes to
    `../expected/usrsec.txt`.
  - `expectedOutputs()` → `ExpectedOutput` entries for `stdout.txt` and `bms_output.txt` (both under
    `../expected/`). **NO `usrsec_after.txt` entry** — because COUSR00C is READ-ONLY, the post-run
    USRSEC state is byte-identical to the input and no after-fixture is declared.
- **All five overrides route to `../expected/` via the `resolveExpectedOutputPath(...)` helper. None
  routes to this `input/` folder.** This is the architectural reason the folder is empty.
- The test is `@Disabled` with the following 12-point verification list (per AAP §0.6.11 — initial
  test scaffolding uses placeholder expected files marked `@Disabled` until COBOL captures are
  committed; the harness skeleton, base class, and per-program test classes are created
  unconditionally):
  1. Admin-only access guard (enforced by the calling chain — admin menu `COADM01C` dispatches
     `COUSR00C`).
  2. Initial display of page 1 with `PAGENUMO = '00000001'`.
  3. PF8 forward pagination across all 3 pages.
  4. PF7 backward pagination back to page 1.
  5. PF8 at bottom boundary → `'You are already at the bottom of the page...'`.
  6. PF7 at top boundary → `'You are already at the top of the page...'`.
  7. Row `'U'`/`'u'` selection → XCTL `COUSR02C` with `CDEMO-CU00-USR-SELECTED` populated correctly.
  8. Row `'D'`/`'d'` selection → XCTL `COUSR03C` with `CDEMO-CU00-USR-SELECTED` populated correctly.
  9. Invalid selection character → `'Invalid selection. Valid values are U and D'`.
  10. PF3 → XCTL `COADM01C` (admin menu, NOT `COSGN00C`).
  11. USRSEC file UNCHANGED after run (READ-ONLY semantics — no WRITE/REWRITE/DELETE).
  12. NO password value in `stdout.txt` or `bms_output.txt` (structural — `COUSR0A` has no PASSWD
      field).

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COUSR00C) for the
CICS COBOL build/run path used to capture expected outputs. The capture procedure exists because
the user prompt left this as a `[TODO]` marker (AAP §0.7.5: "To regenerate golden-record fixtures
from COBOL [TODO — document the COBOL build/run path here]"). Until the capture is performed and
committed, the `../expected/stdout.txt` and `../expected/bms_output.txt` files hold placeholder
content and the test is `@Disabled`.

## Behavioral invariants preserved by this fixture

- **READ-ONLY browse semantics** (`app/cbl/COUSR00C.cbl:L586-L691`): COUSR00C uses STARTBR /
  READNEXT / READPREV / ENDBR; it never issues WRITE, REWRITE, or DELETE. The post-run state of
  USRSEC is byte-identical to the input — this is the structural distinguisher from sibling
  COUSR01C / COUSR02C / COUSR03C, which each modify USRSEC.
- **STARTBR without GTEQ** (`app/cbl/COUSR00C.cbl:L592` where `GTEQ` is COMMENTED OUT): the STARTBR
  requires the RIDFLD to match an existing key EXACTLY. If `USRIDIN` is set to a non-existent
  user-id, STARTBR returns `DFHRESP(NOTFND)`, triggering the `'You are at the top of the page...'`
  path at `app/cbl/COUSR00C.cbl:L603-L604`. This quirk MUST be preserved per AAP §0.7.1.
- **Plaintext password preservation (AAP §0.1.3)**: `SEC-USR-PWD` stores passwords as `PIC X(08)`
  plaintext in `usrsec.txt`, preserved as-is in the synthesized fixture. Any move to a
  password-hashing scheme is OUT OF SCOPE for this refactor.
- **No password in `stdout` or `bms_output` (AAP §0.7.2)**: even though `SEC-USR-PWD` bytes (offset
  49-56 within each USRSEC record) are present in the fixture, NO password value appears in
  `stdout.txt` or `bms_output.txt`. This is **structural** for COUSR00C: the `COUSR0A` BMS map has
  NO PASSWD field at all, so there is no display surface where a password could leak. The `DISPLAY`
  statements at `app/cbl/COUSR00C.cbl:L608, L642, L676` are in WHEN OTHER error paths and emit only
  RESP/REAS codes — never password bytes.
- **Pagination boundary vs run-out semantics** (`app/cbl/COUSR00C.cbl:L237-L277`): boundary
  messages `'You are already at the top of the page...'` (`app/cbl/COUSR00C.cbl:L251`) and
  `'You are already at the bottom of the page...'` (`app/cbl/COUSR00C.cbl:L273`) are emitted WITHOUT
  navigation when the user tries to go beyond the available range. These differ from the run-out
  messages `'You have reached the top of the page...'` (`app/cbl/COUSR00C.cbl:L671-L672`, READPREV
  ENDFILE during PROCESS-PAGE-BACKWARD) and `'You have reached the bottom of the page...'`
  (`app/cbl/COUSR00C.cbl:L637-L638`, READNEXT ENDFILE during PROCESS-PAGE-FORWARD).
- **Case-insensitive row selection** (`app/cbl/COUSR00C.cbl:L190-L191, L200-L201`): both upper-case
  (`'U'`, `'D'`) AND lower-case (`'u'`, `'d'`) are accepted as valid selections. The Java
  translation MUST mirror this exactly.
- **PF3 destination is `COADM01C`, not `COSGN00C`** (`app/cbl/COUSR00C.cbl:L125-L127`): PF3 returns
  to the admin menu. The `COSGN00C` destination is reached only via the `EIBCALEN = 0`
  first-time-entry path at `app/cbl/COUSR00C.cbl:L110-L112`. Scenario 12 MUST EXPECT_XCTL
  `COADM01C`.
- **Verbatim error messages** (each cited from the source paragraph):
  - `'You are at the top of the page...'` (STARTBR NOTFND — `app/cbl/COUSR00C.cbl:L603-L604`).
  - `'You have reached the bottom of the page...'` (READNEXT ENDFILE —
    `app/cbl/COUSR00C.cbl:L637-L638`).
  - `'You have reached the top of the page...'` (READPREV ENDFILE —
    `app/cbl/COUSR00C.cbl:L671-L672`).
  - `'You are already at the top of the page...'` (PF7 at page 1 — `app/cbl/COUSR00C.cbl:L251`).
  - `'You are already at the bottom of the page...'` (PF8 past last page —
    `app/cbl/COUSR00C.cbl:L273`).
  - `'Unable to lookup User...'` (3 occurrences: STARTBR / READNEXT / READPREV WHEN OTHER —
    `app/cbl/COUSR00C.cbl:L610-L611, L644-L645, L678-L679`).
  - `'Invalid selection. Valid values are U and D'` (selection EVALUATE WHEN OTHER —
    `app/cbl/COUSR00C.cbl:L211-L213`).
  - `CCDA-MSG-INVALID-KEY` (from `app/cpy/CSMSG01Y.cpy`, for AID keys other than ENTER / PF3 / PF7 /
    PF8 — `app/cbl/COUSR00C.cbl:L135`).
- **AID-key handling** (`app/cbl/COUSR00C.cbl:L120-L138`): COUSR00C handles exactly 4 named AID
  keys: `DFHENTER`, `DFHPF3`, `DFHPF7`, `DFHPF8`. Any other AID key (PF1, PF4-PF6, PF9-PF12, CLEAR,
  PA1, PA2) falls through to `WHEN OTHER` and emits `CCDA-MSG-INVALID-KEY`. The Java translation's
  pattern-matching switch MUST be exhaustive over the same set per AAP §0.7.4 — NO `default` branch
  that masks missing cases.
- **`@CobolProgram` traceability** (per AAP §0.7.1 documentation discipline): the Java class
  `CoUsr00C` MUST carry `@CobolProgram("COUSR00C")` Javadoc-style annotation citing the original
  PROGRAM-ID, source path `app/cbl/COUSR00C.cbl`, and the translation date.

## Source lineage

- `app/cbl/COUSR00C.cbl` — User-list CICS COBOL program (695 lines). PROGRAM-ID `COUSR00C` at line
  23; transaction `CU00`; key paragraphs: MAIN-PARA (L98), PROCESS-ENTER-KEY (L149), PROCESS-PF7-KEY
  (L237), PROCESS-PF8-KEY (L260), PROCESS-PAGE-FORWARD (L282), PROCESS-PAGE-BACKWARD,
  POPULATE-USER-DATA, STARTBR-USER-SEC-FILE (L586), READNEXT-USER-SEC-FILE (L619),
  READPREV-USER-SEC-FILE (L653), ENDBR-USER-SEC-FILE (L687).
- `app/bms/COUSR00.bms` — BMS map definition (463 lines). MAPSET `COUSR00`, MAP `COUSR0A`,
  SIZE=(24,80). Sole row-level input field `USRIDIN PIC X(8)` at (6,21); 10 selection fields
  SEL0001-SEL0010 at column 6 of rows 10-19; 10 user-data row groups (USRID0NN, FNAME0NN, LNAME0NN,
  UTYPE0NN); ERRMSG at row 23; footer at row 24. **NO PASSWD field anywhere on the map** (structural
  invariant).
- `app/cpy-bms/COUSR00.CPY` — Symbolic map copybook (728 lines). Defines input record `01 COUSR0AI`
  (input fields suffixed `I`) and output record `01 COUSR0AO REDEFINES COUSR0AI` (output fields
  suffixed `O`).
- `app/cpy/CSUSR01Y.cpy` — `01 SEC-USER-DATA` 80-byte record: `SEC-USR-ID(8)` + `SEC-USR-FNAME(20)`
  + `SEC-USR-LNAME(20)` + `SEC-USR-PWD(8)` + `SEC-USR-TYPE(1)` + `SEC-USR-FILLER(23)` = 80 bytes
  total.

## Authority references

- AAP §0.2.1 (in-scope: `golden/cousr00c/` directory tree as part of the
  `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 (COUSR00C → CoUsr00C user-list online program; one Java class per COBOL PROGRAM-ID; in
  `com.blitzy.carddemo.application.user`).
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern
  until COBOL captures are committed).
- AAP §0.1.1 / §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference
  implementation).
- AAP §0.1.3 (plaintext password preservation in `SEC-USR-PWD`; behavior-parity mandate).
- AAP §0.7.1 (Minimal Change Clause; preserve-as-is behavior including verbatim error messages, sort
  orders, sequencing, edge cases).
- AAP §0.7.2 (no card PAN logged in full; admin-only access guards; by extension no plaintext
  password column appears in `stdout` or BMS output).
- AAP §0.7.4 (no preview features; pattern-matching exhaustiveness — NO `default` branches).
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md`).

## DO NOT add files here

This folder MUST remain documentation-only. Do NOT add fixture data files, `.gitkeep` placeholders,
`input_scenario.txt`, `usrsec.txt`, or any other content. The `README.md` IS the directory's
marker. All fixture data files for the COUSR00C parity test live in the sibling `../expected/`
folder, because the consuming test class `CoUsr00CGoldenTest` resolves every fixture path through
the base class helper `resolveExpectedOutputPath("cousr00c", ...)`. Adding files here would create
duplicate, stale, or unreachable fixtures. If a future test scenario requires additional inputs,
add the new file to `../expected/` and extend the appropriate override in `CoUsr00CGoldenTest.java`
(`auxiliaryInputs()` or a new path-returning method) — not here.
