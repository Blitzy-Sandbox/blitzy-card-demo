# COUSR01C Golden-Record Fixtures — expected/

This folder is the authoritative **expected-output and scenario-contract**
container for the COUSR01C (User Add online; CICS transaction `CU01`)
golden-record parity test. The consuming test class is
`com.blitzy.carddemo.tests.golden.CoUsr01CGoldenTest`, which extends the
shared base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The
test class overrides four methods that all resolve through the base-class
helper `resolveExpectedOutputPath("cousr01c", ...)` and route here:
`inputFile()` (the scenario contract), `auxiliaryInputs()` (the initial
USRSEC state), `expectedOutputFile()` (the harness diagnostics stream),
and `expectedOutputs()` (the post-condition mapping for the mutated
USRSEC and the serialized BMS output).

COUSR01C is a **SINGLE-PASS ADD**: a single CICS pseudo-conversation
submission carrying all 5 unprotected BMS fields (FNAME, LNAME, USERID,
PASSWD, USRTYPE) triggers the AID=ENTER path, which performs sequential
empty-field validation, then issues `EXEC CICS WRITE` on the USRSEC
dataset, and on `DFHRESP(NORMAL)` produces a `usrsec_after.txt` that is
**exactly 80 bytes LONGER** than the initial `usrsec.txt`. The
`input_scenario.txt` file lives in this `expected/` folder (rather than
the sibling `../input/`) because BOTH the COBOL CICS reference run AND
the Java translation under test must consume the same deterministic
scenario contract for byte-for-byte parity to be meaningful; the sibling
`../input/README.md` documents this routing explicitly.

## Files in this folder

### `README.md`

This file. Consumed by humans only. Documents the entire fixture
contract: scenario semantics, file layouts, byte-level invariants, the
8 verbatim COBOL error/info messages, the BMS map deviations from the
sibling user-management programs, and the Minimal-Change-Clause
protections that govern any subsequent edit. Not consumed by the test
harness directly.

### `input_scenario.txt`

Format: one CICS pseudo-conversation submission per line; whitespace
separated. Field 1 = AID key name (one of `ENTER`, `PF3`, `PF4`). Fields
2-6 = the 5 BMS input fields in their COBOL EVALUATE-validation order
from `app/cbl/COUSR01C.cbl:L117-L151`: `FNAMEI` (PIC X(20)), `LNAMEI`
(PIC X(20)), `USERIDI` (PIC X(08)), `PASSWDI` (PIC X(08)), `USRTYPEI`
(PIC X(01)). Field 7 = the caller's `CDEMO-FROM-PROGRAM` (PIC X(08))
that populates the inbound `CARDDEMO-COMMAREA` (e.g., `COADM01C` for
admin-menu invocation). Field 8 = expected outcome label keyed to the
numbered scenarios in the "Required test scenarios" section below.
Lines beginning with `#` are comments and skipped by the harness.
Consumed by the `inputFile()` override.

### `usrsec.txt`

The initial USRSEC fixture. Concatenated 80-byte SEC-USER-DATA records
in SEC-USR-ID ascending order, with no record terminators (VSAM-KSDS
convention emulated on the filesystem). Contains at least 3
deterministic test users (`ADMIN001`, `EXISTUSR`, `KEEPUSR1`)
documented in the "Test users registry" section. This file is
**test-owned scaffolding** — it is NOT a copy of any file under
`app/data/ASCII/`; the test fixture exists exclusively to exercise
COUSR01C without coupling to the broader ASCII fixture set. Plaintext
passwords are preserved per AAP §0.1.3. Consumed by the
`auxiliaryInputs()` override.

### `usrsec_after.txt`

The expected USRSEC state after the scenario completes. For the
WRITE-NORMAL scenario (scenario 2), byte length = `len(usrsec.txt) + 80`
exactly: a single 80-byte SEC-USER-DATA slot for `NEWUSR01` is inserted
at the correct SEC-USR-ID sort position, and all pre-existing records
are preserved byte-for-byte without shift or modification. For
non-mutating scenarios (DUPKEY rejection, blank-field rejection, PF4
clear), this file is **byte-identical** to `usrsec.txt`. **CRITICAL
CONTRAST**: the sibling `golden/cousr02c/expected/usrsec_after.txt` is
the SAME byte length as its `usrsec.txt` (REWRITE preserves length); the
sibling `golden/cousr03c/expected/usrsec_after.txt` is SHORTER by 80
bytes (DELETE removes one record). Consumed via the `expectedOutputs()`
mapping (`usrsec.txt` → this file).

### `stdout.txt`

Purely harness diagnostics. COUSR01C has **ZERO active DISPLAY
statements** — the only DISPLAY in the program source is at
`app/cbl/COUSR01C.cbl:L268` and is commented out. Therefore this file
contains only the test harness's own diagnostic preamble and outcome
labels; it MUST NOT contain any byte sequence that originated from the
Java `CoUsr01C` translation itself. **NO-PLAINTEXT-PASSWORD invariant**
per AAP §0.7.2: the harness asserts that NO byte sequence anywhere in
`stdout.txt` matches any plaintext password from `usrsec.txt` OR any
`PASSWDI` value from `input_scenario.txt`. Consumed by the
`expectedOutputFile()` override.

### `bms_output.txt`

The serialized BMS `SEND MAP COUSR1A MAPSET COUSR01 FROM(COUSR1AO)`
output captured per scenario. Deterministic serialization format
specified in `java/MIGRATION_NOTES.md` §1.6, covering: the 5 BMS
unprotected-field positions with their attribute bytes, the dynamic
ERRMSG color byte (DFHGREEN on add-success per `app/cbl/COUSR01C.cbl:L254`;
default RED per BMS attribute `COLOR=RED` at `app/bms/COUSR01.bms:L152`
for all error paths), the IC (initial cursor) attribute byte on the
FNAME field at `(8, 18)` per `app/bms/COUSR01.bms:L84-L88`, and the DRK
(dark / non-display) attribute marker on the PASSWD field at `(11, 55)`
per `app/bms/COUSR01.bms:L126-L130`. The password byte IS present in the
screen buffer — DRK suppresses render but does not erase buffer bytes —
and the serializer preserves it for byte-for-byte parity. Consumed via
the `expectedOutputs()` mapping (`bms_output.txt` → this file).

## Test users registry (fixture determinism)

The initial `usrsec.txt` is seeded with three deterministic test users
chosen to exercise distinct code paths: an admin actor, a duplicate-key
collision target, and a control record that must survive every scenario
untouched. Scenario 2 adds a fourth record (`NEWUSR01`) that appears
only in `usrsec_after.txt`.

| SEC-USR-ID | SEC-USR-FNAME (20) | SEC-USR-LNAME (20) | SEC-USR-PWD | SEC-USR-TYPE | FILLER (23) | Role |
|---|---|---|---|---|---|---|
| `ADMIN001` | `'ADMIN               '` | `'USER                '` | `'ADMINPWD'` | `'A'` | 23 spaces | admin actor; also a DUPKEY test target |
| `EXISTUSR` | `'EXISTING            '` | `'USER                '` | `'EXISTPWD'` | `'U'` | 23 spaces | DUPKEY rejection target (scenario 3) |
| `KEEPUSR1` | `'KEEP                '` | `'USER01              '` | `'KEEP1PWD'` | `'U'` | 23 spaces | control (never modified by any scenario) |
| `NEWUSR01` | `'NEW                 '` | `'USER01              '` | `'NEW01PWD'` | `'U'` | 23 spaces | NEW record added in scenario 2 (appears only in `usrsec_after.txt`) |

Each row above represents exactly 80 bytes (8 + 20 + 20 + 8 + 1 + 23 =
80) per `app/cpy/CSUSR01Y.cpy:L17-L23`. Trailing spaces inside the
quoted character strings are significant and MUST be preserved verbatim
by the capture procedure.

The exact user-ids, names, and passwords shown above are **EXAMPLES**.
The capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 may
select different deterministic values, provided that (a) the chosen
values are documented in this README before commit, (b) the values
satisfy the role each record plays in the scenarios below, and (c) the
values are applied consistently across all four data files
(`usrsec.txt`, `usrsec_after.txt`, `input_scenario.txt`, and
`bms_output.txt`).

## Required test scenarios

The harness executes the following 7 scenarios, each driven by one or
more lines in `input_scenario.txt` and asserted against the expected
post-conditions documented here.

1. **Admin-only access guard (menu-level, NOT in-program)** — COUSR01C
   source contains NO admin check: whole-file inspection reveals no
   reference to `SEC-USR-TYPE`, no test against `'A'`, and no abort
   path keyed to user type. The admin-only gate is enforced
   exclusively by the calling convention of `COADM01C`, the only
   program that routes operators to `CU01`. The test asserts this
   routing fact (via `CDEMO-FROM-PROGRAM` in the commarea) rather
   than an in-program guard. Cite: `app/cbl/COUSR01C.cbl` whole-file
   inspection.

2. **Valid add by entered fields (WRITE NORMAL)** — AID=ENTER with all
   5 inputs populated (e.g., `FNAMEI='NEW'`, `LNAMEI='USER01'`,
   `USERIDI='NEWUSR01'`, `PASSWDI='NEW01PWD'`, `USRTYPEI='U'`).
   Empty-field WHENs fall through; the `IF NOT ERR-FLG-ON` block at
   `app/cbl/COUSR01C.cbl:L153-L159` moves the 5 inputs into
   `SEC-USER-DATA` and PERFORMs WRITE-USER-SEC-FILE. `DFHRESP(NORMAL)`
   fires. Expected ERRMSG is the STRING construction
   `'User NEWUSR01 has been added ...'` (user-id space-delimited per
   `DELIMITED BY SPACE`); `ERRMSGC` attribute byte = `DFHGREEN`;
   `usrsec_after.txt` is 80 bytes LONGER than `usrsec.txt`. Cite:
   `app/cbl/COUSR01C.cbl:L251-L259`.

3. **DUPKEY/DUPREC rejection (existing user ID)** — AID=ENTER with
   `USERIDI='EXISTUSR'` (a pre-existing key) and the other 4 fields
   non-empty. Empty-field WHENs fall through; inputs are moved to
   SEC-USER-DATA; WRITE-USER-SEC-FILE is performed. RESP is one of
   `DFHRESP(DUPKEY)` or `DFHRESP(DUPREC)`, both falling into the
   combined branch at `app/cbl/COUSR01C.cbl:L260-L266`. Expected
   ERRMSG = `'User ID already exist...'`. Cursor reset to USERIDL
   (`MOVE -1 TO USERIDL OF COUSR1AI` at L265). `usrsec_after.txt` is
   **byte-identical** to `usrsec.txt`. Cite:
   `app/cbl/COUSR01C.cbl:L260-L266`.

4. **Blank required-fields rejected (5 separate validation submissions)**
   — each empty field is exercised in its own submission; the first
   empty field in source declaration order wins because the WHEN
   clauses of the `EVALUATE TRUE` block at
   `app/cbl/COUSR01C.cbl:L117-L151` are mutually exclusive and order
   sensitive. The exact pairings are:
   - FNAMEI empty → ERRMSG `'First Name can NOT be empty...'`; cursor →
     FNAMEL; cite `app/cbl/COUSR01C.cbl:L118-L123`.
   - LNAMEI empty → ERRMSG `'Last Name can NOT be empty...'`; cursor →
     LNAMEL; cite `app/cbl/COUSR01C.cbl:L124-L129`.
   - USERIDI empty → ERRMSG `'User ID can NOT be empty...'`; cursor →
     USERIDL; cite `app/cbl/COUSR01C.cbl:L130-L135`.
   - PASSWDI empty → ERRMSG `'Password can NOT be empty...'`; cursor →
     PASSWDL; cite `app/cbl/COUSR01C.cbl:L136-L141`.
   - USRTYPEI empty → ERRMSG `'User Type can NOT be empty...'`; cursor
     → USRTYPEL; cite `app/cbl/COUSR01C.cbl:L142-L147`.
   For all 5 sub-scenarios, `usrsec_after.txt` is byte-identical to
   `usrsec.txt` (the `IF NOT ERR-FLG-ON` guard at L153 short-circuits
   the WRITE).

5. **PF4 clear** — AID=PF4. EIBAID dispatch at
   `app/cbl/COUSR01C.cbl:L96-L97` routes to CLEAR-CURRENT-SCREEN,
   which PERFORMs INITIALIZE-ALL-FIELDS (resets 5 inputs and message
   to SPACES, sets `FNAMEL` to `-1` for cursor) then PERFORMs
   SEND-USRADD-SCREEN to re-render the empty form.
   `usrsec_after.txt` is byte-identical to `usrsec.txt` (no WRITE).
   Cite: `app/cbl/COUSR01C.cbl:L279-L295`.

6. **Plaintext password preserved in `usrsec_after.txt` byte-for-byte
   (AAP §0.1.3)** — scenario 2 is re-validated at byte offsets 49-56
   of the newly inserted record (`'NEW01PWD'` plaintext; no hashing,
   no encryption, no encoding transformation). The 8 SEC-USR-PWD
   bytes MUST equal the `PASSWDI` bytes from `input_scenario.txt`.
   Cite: `app/cpy/CSUSR01Y.cpy:L21`.

7. **Password NEVER in `stdout.txt` (AAP §0.7.2)** — the harness
   scans `stdout.txt` for every plaintext password byte sequence from
   `usrsec.txt` AND every `PASSWDI` byte sequence from
   `input_scenario.txt` (including scenario-2's new password). No
   match is permitted at any offset. Since COUSR01C has zero active
   DISPLAYs (the only one at `app/cbl/COUSR01C.cbl:L268` is commented
   out), `CoUsr01C` has no permitted program-output log emission,
   which makes this invariant straightforward to satisfy. Cite:
   `app/cbl/COUSR01C.cbl:L268` (commented DISPLAY).

Supplementary note: scenario 8 (OPTIONAL — PF3 routing back to admin
menu, verifying `MOVE 'COADM01C' TO CDEMO-TO-PROGRAM` at
`app/cbl/COUSR01C.cbl:L94` followed by RETURN-TO-PREV-SCREEN's XCTL)
exercises cross-program navigation but does not mutate USRSEC.
Scenario 9 (OPTIONAL — WHEN OTHER on the outer EIBAID dispatch by
pressing PF12, which is advertised as `F12=Exit` at
`app/bms/COUSR01.bms:L155-L159` but has NO `WHEN DFHPF12` branch in
the EIBAID dispatch at `app/cbl/COUSR01C.cbl:L88-L103`; PF12 falls
through to WHEN OTHER at L98-L102 and emits `CCDA-MSG-INVALID-KEY` =
`'Invalid key pressed. Please see below...'` from
`app/cpy/CSMSG01Y.cpy:L20-L21`) verifies the preserved-as-is COBOL
anomaly per AAP §0.7.1.

## BMS map invariants

- Field positions, lengths, colors, and attribute clauses are preserved
  verbatim from `app/bms/COUSR01.bms` in `bms_output.txt`. No field is
  reordered, renamed, repositioned, or recolored.
- **IC (initial cursor) attribute byte on the FNAME field** at screen
  position `(8, 18)` per `app/bms/COUSR01.bms:L84-L88`
  (`ATTRB=(FSET,IC,NORM,UNPROT)`). This is the **distinguishing BMS
  difference** between COUSR01C and the sibling programs
  COUSR02C / COUSR03C, which both place IC on `USRIDIN`. The IC
  attribute byte MUST appear at FNAME's attribute-byte offset in the
  serialized output.
- ERRMSG color is dynamic: `MOVE DFHGREEN TO ERRMSGC OF COUSR1AO` at
  `app/cbl/COUSR01C.cbl:L254` on the WRITE-NORMAL branch; default RED
  per `COLOR=RED` at `app/bms/COUSR01.bms:L152` for all error paths
  (empty-field rejections, DUPKEY/DUPREC, WRITE-OTHER, invalid-AID).
- Constant header fields `TRNNAME='CU01'` and `PGMNAME='COUSR01C'`
  populated from working-storage at `app/cbl/COUSR01C.cbl:L37` and L36
  respectively, then moved into the output fields at
  `app/cbl/COUSR01C.cbl:L220-L221`.
- `CURDATE` and `CURTIME` are sourced from `MOVE FUNCTION CURRENT-DATE`
  at `app/cbl/COUSR01C.cbl:L216`, decomposed via component MOVEs at
  L223-L233, rendered as `mm/dd/yy` / `hh:mm:ss`. The Java translation
  injects a fixed clock via `ScopedValue` per AAP §0.6.6 for
  deterministic golden capture; the COBOL run uses a deterministic
  environment per `java/MIGRATION_NOTES.md` §1.6.
- **PASSWD field WITH DRK attribute** at `(11, 55)` length `8` per
  `app/bms/COUSR01.bms:L126-L130` (`ATTRB=(DRK,FSET,UNPROT)`). The 8
  PASSWD bytes ARE present in the screen buffer (and in
  `bms_output.txt`); DRK suppresses the 3270 render but does NOT erase
  or mask the buffer bytes. This is the BMS-level expression of the
  plaintext preservation policy in AAP §0.1.3, and is a separate
  surface from the SLF4J log stream governed by AAP §0.7.2.
- **Footer-vs-code mismatch** (preserved as-is per AAP §0.7.1): the
  footer at `app/bms/COUSR01.bms:L155-L159` advertises
  `'ENTER=Add User  F3=Back  F4=Clear  F12=Exit'` (43 chars), but the
  EIBAID `EVALUATE` block at `app/cbl/COUSR01C.cbl:L88-L103` has NO
  `WHEN DFHPF12` branch — only `DFHENTER`, `DFHPF3`, `DFHPF4`, and
  `WHEN OTHER`. PF12 falls through to WHEN OTHER and emits
  `CCDA-MSG-INVALID-KEY`. The Java translation MUST NOT add a
  synthetic `DFHPF12` handler.

## USRSEC file invariants

- 80-byte fixed-width records, no record terminators (VSAM-KSDS
  convention emulated on the filesystem). Field layout per
  `app/cpy/CSUSR01Y.cpy:L17-L23`: SEC-USR-ID `PIC X(08)` (offset 1),
  SEC-USR-FNAME `PIC X(20)` (offset 9), SEC-USR-LNAME `PIC X(20)`
  (offset 29), SEC-USR-PWD `PIC X(08)` (offset 49), SEC-USR-TYPE
  `PIC X(01)` (offset 57), SEC-USR-FILLER `PIC X(23)` (offset 58);
  total 80 bytes.
- Plaintext password preservation per AAP §0.1.3. Password flows from
  BMS input `PASSWDI` into SEC-USR-PWD via
  `MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD` at
  `app/cbl/COUSR01C.cbl:L157`; no transformation in the COBOL pipeline.
- SEC-USR-ID ascending sort order is preserved per AAP §0.7.1.
- **WRITE-appends-record semantics**: `EXEC CICS WRITE` at
  `app/cbl/COUSR01C.cbl:L240-L248` inserts a single new 80-byte slot
  at the correct SEC-USR-ID sort position; total file byte length
  increases by exactly 80 bytes; no pre-existing record is modified
  or shifted.
- COUSR01C-vs-COUSR02C-vs-COUSR03C contrast: COUSR01C's
  `usrsec_after.txt` is LONGER by 80 bytes (WRITE); COUSR02C's is the
  SAME length (REWRITE); COUSR03C's is SHORTER by 80 bytes (DELETE).
- **DUPKEY/DUPREC short-circuit** (UNIQUE TO ADD): if the new
  SEC-USR-ID matches an existing key, CICS WRITE returns one of
  `DFHRESP(DUPKEY)` or `DFHRESP(DUPREC)`, both falling into the
  rejection branch at `app/cbl/COUSR01C.cbl:L260-L266` without
  modifying the file. Neither COUSR02C (REWRITE) nor COUSR03C
  (DELETE) exposes this branch.
- Byte-for-byte parity per AAP §0.6.11: the harness compares
  `usrsec_after.txt` using byte equality, not field-level structured
  equality. Any extra newline, padding change, or sign-nybble drift
  would break the test.

## Java mapping invariants

- Plaintext password preservation in the `SecUserData` Java record per
  AAP §0.1.3. The 8 password bytes flow from BMS input through to the
  on-disk SEC-USR-PWD field with no transformation. No `BCrypt`,
  `Argon2`, `scrypt`, or `PBKDF2` is introduced; any move toward
  password hashing is an OUT-OF-SCOPE behavior change.
- Password NEVER in logs per AAP §0.7.2. Because COUSR01C has ZERO
  active DISPLAY statements (the only one at
  `app/cbl/COUSR01C.cbl:L268` is commented out), the Java `CoUsr01C`
  MUST emit ZERO program-output log lines via `log.info(...)`,
  `log.warn(...)`, `log.error(...)`, or any equivalent surface. Any
  diagnostic content in `stdout.txt` is harness-emitted, not
  program-emitted.
- The password IS present in the BMS output buffer (DRK attribute set)
  per the BMS map invariants section. Logs and BMS buffer are
  governed by separate AAP rules: §0.7.2 governs logs (zero
  plaintext); §0.1.3 governs on-disk and in-buffer representation.
- `FileUserSecurityRepository.create(record)` implements
  WRITE-appends-record semantics with a sort-ordered insert at the
  SEC-USR-ID ascending position; total file byte length increases by
  exactly 80; no pre-existing record is shifted. Uses `java.nio.file`
  per AAP §0.6.5; no `java.io.File`.
- DUPKEY/DUPREC handling: `create(record)` MUST detect key collision
  before writing and throw a typed exception; `CoUsr01C` catches and
  emits the verbatim `'User ID already exist...'`.
- Single-pass flow: unlike COUSR02C (two-pass ENTER → PF5 to REWRITE)
  and COUSR03C (ENTER → PF5 for READ-UPDATE then DELETE), COUSR01C
  completes in a single submission. No commarea state is carried.
- No `java.util.Date`, no `java.util.Calendar`, no
  `java.text.SimpleDateFormat`; CURDATE / CURTIME use `java.time`
  exclusively per AAP §0.6.4.
- No Spring, no Spring Batch, no Spring Security, no Hibernate, no
  JPA, no Flyway, no PostgreSQL per AAP §0.6.12.
- No `double`, no `float` (general rule; COUSR01C has no monetary
  fields). No `ThreadLocal`; cross-method context propagation (e.g.,
  fixed-clock injection for deterministic CURDATE/CURTIME) uses
  `ScopedValue` per AAP §0.6.6.
- **Eight verbatim error/info messages** are preserved
  character-for-character, including the 3-ASCII-period ellipsis
  (`...`, NEVER the Unicode horizontal ellipsis character):
  - `'First Name can NOT be empty...'` — emitted from
    `app/cbl/COUSR01C.cbl:L120-L121`.
  - `'Last Name can NOT be empty...'` — emitted from
    `app/cbl/COUSR01C.cbl:L126-L127`.
  - `'User ID can NOT be empty...'` — emitted from
    `app/cbl/COUSR01C.cbl:L132-L133`.
  - `'Password can NOT be empty...'` — emitted from
    `app/cbl/COUSR01C.cbl:L138-L139`.
  - `'User Type can NOT be empty...'` — emitted from
    `app/cbl/COUSR01C.cbl:L144-L145`.
  - `'User <id> has been added ...'` — constructed by the COBOL
    `STRING` at `app/cbl/COUSR01C.cbl:L255-L258` with the user-id
    space-delimited (`DELIMITED BY SPACE` on the SEC-USR-ID source).
  - `'User ID already exist...'` — emitted from `app/cbl/COUSR01C.cbl:L263`.
  - `'Unable to Add User...'` — emitted from `app/cbl/COUSR01C.cbl:L270`.

**Three-way contrast across the user-management programs:**

| Aspect | COUSR01C (Add) | COUSR02C (Update) | COUSR03C (Delete) |
|---|---|---|---|
| Transaction ID | `'CU01'` | `'CU02'` | `'CU03'` |
| File operation | `EXEC CICS WRITE` | `EXEC CICS REWRITE` | `EXEC CICS DELETE` |
| `usrsec_after.txt` byte length | **LONGER by 80 bytes** | SAME | SHORTER by 80 bytes |
| Editable BMS fields | 5 (FNAME+LNAME+USERID+PASSWD-DRK+USRTYPE) | 5 (USRIDIN+FNAME+LNAME+PASSWD-DRK+USRTYPE) | 1 (USRIDIN only) |
| Initial cursor (IC) | **on FNAME (8,18)** | on USRIDIN | on USRIDIN |
| PASSWD field on BMS | PRESENT at (11,55) DRK | PRESENT at (13,16) DRK | ABSENT entirely |
| Two-pass flow | **NO (single-pass)** | YES (ENTER then PF5) | YES (ENTER then PF5) |
| Auto-trigger from COUSR00C | NO | YES (row 'U') | YES (row 'D') |
| Verbatim error messages | **8** | 11 | 6 |
| AID keys handled | **3 named (ENTER, PF3, PF4) + WHEN OTHER** — NO PF5, NO PF12 | 5 named + OTHER | 4 named + OTHER |
| Active DISPLAY statements | **0 (L268 commented)** | 2 | 2 |
| DUPKEY/DUPREC handling | **YES (unique to ADD)** | NO | NO |
| Footer-vs-code mismatch | **YES (F12=Exit advertised; not handled)** | NO | NO |

## Capture procedure cross-reference

The full capture procedure for `usrsec.txt`, `usrsec_after.txt`,
`stdout.txt`, and `bms_output.txt` lives in `java/MIGRATION_NOTES.md`
§1.6 per AAP §0.7.5. That section documents the COBOL CICS build/run
path used to capture the baseline outputs, the fixed-clock injection
methodology that produces deterministic CURDATE/CURTIME values for
both COBOL and Java, and the `bms_output.txt` serialization format
including the rendering of the DRK attribute byte on the PASSWD
field and the IC attribute byte on the FNAME field. The procedure is
the single source of truth for fixture regeneration; this README is
the single source of truth for the fixture **contract**.

## Test class @Disabled mandate

The test class `com.blitzy.carddemo.tests.golden.CoUsr01CGoldenTest`
MUST remain annotated
`@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")`
until ALL FIVE data files (`input_scenario.txt`, `usrsec.txt`,
`usrsec_after.txt`, `stdout.txt`, `bms_output.txt`) have been committed
alongside this README with non-placeholder content captured per the
procedure documented in `java/MIGRATION_NOTES.md` §1.6. The `@Disabled`
annotation is removed in a single commit that also introduces the five
fixture files. This is the non-negotiable PR gate per AAP §0.6.11.

When all five fixture files exist, the harness MUST demonstrate the
following 7 invariants before `@Disabled` may be removed:

1. Admin-only access guard demonstrated at menu-level (NOT in-program).
2. Valid add by entered fields (WRITE NORMAL) — `usrsec_after.txt` is
   80 bytes LONGER than `usrsec.txt`.
3. DUPKEY/DUPREC rejection — `usrsec_after.txt` is byte-identical to
   `usrsec.txt`.
4. Blank required-fields rejected (5 sub-scenarios in source order;
   first-empty wins).
5. PF4 clear — `usrsec_after.txt` is byte-identical to `usrsec.txt`.
6. Plaintext password preserved in `usrsec_after.txt` byte-for-byte
   (AAP §0.1.3).
7. Password NEVER appears in `stdout.txt` (AAP §0.7.2).

## Cross-references

- Sibling `input/` folder (documentation-only):
  `java/carddemo-tests/src/test/resources/golden/cousr01c/input/README.md`
- Test class:
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoUsr01CGoldenTest.java`
- Base test class:
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
- Class under test:
  `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr01C.java`
- Input DTO:
  `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr01Input.java`
- Output DTO:
  `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/user/CoUsr01Output.java`
- Domain record:
  `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/SecUserData.java`
- Port:
  `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/UserSecurityRepository.java`
- File-based adapter:
  `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileUserSecurityRepository.java`
- Sibling fixture (Update):
  `java/carddemo-tests/src/test/resources/golden/cousr02c/expected/README.md`
- Sibling fixture (Delete):
  `java/carddemo-tests/src/test/resources/golden/cousr03c/expected/README.md`
- Capture procedure: `java/MIGRATION_NOTES.md` §1.6

## Source lineage

- `app/cbl/COUSR01C.cbl` — PROGRAM-ID at L23; WORKING-STORAGE at L35-L44
  (`WS-PGMNAME='COUSR01C'`, `WS-TRANID='CU01'`, `WS-MESSAGE` PIC X(80),
  `WS-USRSEC-FILE='USRSEC  '`, `WS-ERR-FLG` with 88-conditions
  `ERR-FLG-ON`/`ERR-FLG-OFF`, `WS-RESP-CD`/`WS-REAS-CD` COMP); COPY
  statements at L46-L56 (`COCOM01Y`, `COUSR01`, `COTTL01Y`, `CSDAT01Y`,
  `CSMSG01Y`, `CSUSR01Y`, `DFHAID`, `DFHBMSCA`); LINKAGE SECTION at
  L62-L65 (`DFHCOMMAREA OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN`);
  MAIN-PARA at L71-L110 (EIBAID dispatch at L88-L103: three named
  branches `DFHENTER`/`DFHPF3`/`DFHPF4` plus `WHEN OTHER`; closing
  `EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(...)` at L107-L110);
  PROCESS-ENTER-KEY at L115-L162 (`EVALUATE TRUE` block at L117-L151 —
  first empty wins; conditional MOVEs + PERFORM WRITE at L153-L159
  inside `IF NOT ERR-FLG-ON` guard); RETURN-TO-PREV-SCREEN at L165-L178
  (XCTL; defaults `CDEMO-TO-PROGRAM` to `COSGN00C` if blank);
  SEND-USRADD-SCREEN at L184-L196; RECEIVE-USRADD-SCREEN at L201-L209;
  POPULATE-HEADER-INFO at L214-L233 (CURDATE/CURTIME formatting via
  component MOVEs); WRITE-USER-SEC-FILE at L238-L274 (`EXEC CICS WRITE`
  at L240-L248; `DFHRESP(NORMAL)` branch at L251-L259; combined
  `DFHRESP(DUPKEY)`/`DFHRESP(DUPREC)` rejection at L260-L266;
  commented-out `DISPLAY` at L268; `WHEN OTHER` at L267-L273);
  CLEAR-CURRENT-SCREEN at L279-L282; INITIALIZE-ALL-FIELDS at L287-L295.
- `app/bms/COUSR01.bms` — mapset `COUSR01` at L19 with `CTRL=(ALARM,FREEKB)`,
  `EXTATT=YES`, `LANG=COBOL`, `MODE=INOUT`; map `COUSR1A` at L26-L28 with
  `SIZE=(24,80)`; title bar at L29-L74 (TRNNAME/TITLE01/CURDATE row 1,
  PGMNAME/TITLE02/CURTIME row 2); centered `'Add User'` at (4,35) NEUTRAL
  BRT at L75-L79; FNAME at (8,18) length 20 GREEN UNDERLINE with
  `ATTRB=(FSET,IC,NORM,UNPROT)` at L84-L88 — IC distinguishes COUSR01C;
  LNAME at (8,56) length 20 with `ATTRB=(FSET,NORM,UNPROT)` at L97-L101;
  USERID at (11,15) length 8 at L111-L115; PASSWD at (11,55) length 8
  with `ATTRB=(DRK,FSET,UNPROT)` at L126-L130 — DRK suppresses render
  but buffer bytes are present; USRTYPE at (14,17) length 1 at L141-L145;
  ERRMSG at (23,1) length 78 RED with `ATTRB=(ASKIP,BRT,FSET)` at
  L151-L154; footer at (24,1) length 43 YELLOW with
  `INITIAL='ENTER=Add User  F3=Back  F4=Clear  F12=Exit'` at L155-L159 —
  advertises F12 but EIBAID dispatch has no `WHEN DFHPF12` branch.
- `app/cpy-bms/COUSR01.CPY` — `01 COUSR1AI` symbolic input map at
  L17-L90 (12 fields, each decomposed into Length/Flag/Attribute/Input
  sub-fields per BMS convention; the 5 unprotected-field Input bytes are
  `FNAMEI PIC X(20)` at L60, `LNAMEI PIC X(20)` at L66, `USERIDI PIC X(08)`
  at L72, `PASSWDI PIC X(08)` at L78, `USRTYPEI PIC X(01)` at L84;
  `ERRMSGI PIC X(78)` at L90 carries the dynamic error message);
  `01 COUSR1AO REDEFINES COUSR1AI` symbolic output map at L91-L164
  (12 fields with Color/Pen/Highlight/Validation/Output sub-fields,
  including `ERRMSGC` whose `DFHGREEN` setting on WRITE-NORMAL is at
  `app/cbl/COUSR01C.cbl:L254`).
- `app/cpy/CSUSR01Y.cpy` — 80-byte `01 SEC-USER-DATA` at L17-L23:
  `SEC-USR-ID PIC X(08)` (offset 1), `SEC-USR-FNAME PIC X(20)` (offset 9),
  `SEC-USR-LNAME PIC X(20)` (offset 29), `SEC-USR-PWD PIC X(08)` (offset
  49; plaintext per AAP §0.1.3), `SEC-USR-TYPE PIC X(01)` (offset 57;
  `'A'`/`'U'`), `SEC-USR-FILLER PIC X(23)` (offset 58).
- `app/cpy/CSMSG01Y.cpy` — `CCDA-MSG-INVALID-KEY PIC X(50)` at L20-L21
  with `VALUE 'Invalid key pressed. Please see below...         '`
  (38 visible chars + 12 trailing spaces); emitted by the outer EIBAID
  WHEN OTHER branch at `app/cbl/COUSR01C.cbl:L101`.

## Authority references

- AAP §0.1.3 — plaintext password preservation (SEC-USR-PWD bytes flow
  through CICS WRITE unmodified; no hashing in scope).
- AAP §0.2.1 — `golden/cousr01c/` declared in-scope for CREATE.
- AAP §0.3.1 — golden-record harness structure
  (`<program>/input/` + `<program>/expected/`).
- AAP §0.4.1 — one Java class per COBOL PROGRAM-ID: COUSR01C →
  `com.blitzy.carddemo.application.user.CoUsr01C`.
- AAP §0.6.4 — `java.time` mandate for all date/time handling;
  `java.util.Date` / `Calendar` / `SimpleDateFormat` forbidden.
- AAP §0.6.5 — `java.nio.file` mandate for all file I/O; `java.io.File`
  forbidden.
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal`; used here for
  fixed-clock injection of CURDATE / CURTIME determinism during golden
  capture.
- AAP §0.6.11 — non-negotiable PR gate; the `@Disabled` scaffolding
  pattern documented in the "Test class @Disabled mandate" section.
- AAP §0.6.12 — architectural override: no Spring, no PostgreSQL, no
  Spring Batch, no Hibernate, no Flyway, no Spring Security.
- AAP §0.7.1 — Minimal Change Clause: preserve verbatim error
  messages, SEC-USR-ID sort order, and the footer-vs-code mismatch
  as-is; do NOT "fix" obvious anomalies during translation.
- AAP §0.7.2 — no password or PAN bytes in logs; PCI-relevant
  controls; the plaintext password MUST NEVER appear in `stdout.txt`
  even though it may appear in the on-disk `usrsec_after.txt` and the
  in-buffer `bms_output.txt` (DRK on screen, present in buffer).
- AAP §0.7.4 — no JEP preview features (JEPs 502, 505, 507, 512); no
  `--enable-preview` JVM flag.
- AAP §0.7.5 — capture procedure documented in
  `java/MIGRATION_NOTES.md` §1.6.

## DO NOT modify the fixture data without re-capture

The five data files (`input_scenario.txt`, `usrsec.txt`,
`usrsec_after.txt`, `stdout.txt`, `bms_output.txt`) are a **coupled
set**: every byte in `usrsec_after.txt` and `bms_output.txt` is a
function of the bytes in `usrsec.txt`, `input_scenario.txt`, and the
COUSR01C source. Modifying any one file without re-running the capture
procedure in `java/MIGRATION_NOTES.md` §1.6 WILL break byte-for-byte
parity and WILL cause the test to fail when `@Disabled` is removed.
The following protective rules apply to every subsequent edit:

- Do NOT remove or normalize trailing spaces in ERRMSG. The COBOL
  ERRMSG field is `PIC X(78)` at `app/bms/COUSR01.bms:L153` and is
  space-padded on the right by the COBOL `MOVE 'msg' TO WS-MESSAGE`
  statement; the padding is part of the byte-stream contract.
- Do NOT normalize trailing newlines in `usrsec.txt` or
  `usrsec_after.txt`. There are NO record terminators per VSAM-KSDS
  convention — concatenated 80-byte slots with no delimiters; adding
  an LF would shift the next record by one byte and destroy parity.
- Do NOT hash, encrypt, encode, or otherwise transform the password
  field at byte offsets 49-56 of any SEC-USER-DATA record. Plaintext
  preservation is mandated by AAP §0.1.3; any move toward BCrypt /
  Argon2 / scrypt / PBKDF2 is an OUT-OF-SCOPE behavior change.
- Do NOT erase, mask, or substitute the password bytes at the DRK
  attribute PASSWD field position `(11, 55)` in `bms_output.txt`. DRK
  suppresses render at the 3270 terminal; it does NOT erase buffer
  bytes — the serialized output MUST contain the password byte
  sequence at the PASSWD field's input-buffer position.
- Do NOT "fix" the footer-vs-code mismatch in `app/bms/COUSR01.bms` or
  `app/cbl/COUSR01C.cbl`. The footer at `app/bms/COUSR01.bms:L155-L159`
  advertises `F12=Exit` but the EIBAID dispatch in
  `app/cbl/COUSR01C.cbl:L88-L103` has no `WHEN DFHPF12` branch; PF12
  falls through to WHEN OTHER and emits `CCDA-MSG-INVALID-KEY` per
  `app/cpy/CSMSG01Y.cpy:L20-L21`. Preserve as-is per AAP §0.7.1.
- Do NOT add a Java `DFHPF12` handler inside `CoUsr01C`. The Java
  translation mirrors the COBOL EIBAID dispatch exactly: 3 named
  branches (`DFHENTER`, `DFHPF3`, `DFHPF4`) plus a default
  WHEN-OTHER branch. A synthetic PF12 handler violates AAP §0.7.1.
- Do NOT add an in-program admin check inside `CoUsr01C`. The
  admin-only gate is enforced by the calling convention of `COADM01C`;
  COUSR01C source contains no `SEC-USR-TYPE` test, no `'A'`
  comparison, and no abort path keyed to user type.
- Do NOT change the validation ordering of the 5 empty-field checks
  inside PROCESS-ENTER-KEY. The order
  FNAMEI → LNAMEI → USERIDI → PASSWDI → USRTYPEI is established by
  `EVALUATE TRUE` at `app/cbl/COUSR01C.cbl:L117-L151` and determines
  which message wins when multiple fields are empty.
- Do NOT change the text of any of the 8 verbatim error/info messages.
  All are preserved character-for-character including the trailing
  3-ASCII-period ellipses (`...`): `'First Name can NOT be empty...'`,
  `'Last Name can NOT be empty...'`, `'User ID can NOT be empty...'`,
  `'Password can NOT be empty...'`, `'User Type can NOT be empty...'`,
  `'User <id> has been added ...'` (STRING-constructed),
  `'User ID already exist...'`, and `'Unable to Add User...'`.
- Do NOT use the Unicode horizontal ellipsis character anywhere in any
  data file, scenario label, or message text. Always three ASCII
  periods (`...`). The COBOL source uses three ASCII periods
  exclusively per `app/cbl/COUSR01C.cbl:L120`, L126, L132, L138, L144,
  L257, L263, L270; drift to the Unicode codepoint would break
  byte-for-byte parity.
