# COSGN00C (Signon, CC00) — Golden-Record Test Fixture Contract

## Phase 0: Header and Authority Cascade

This document is the AUTHORITATIVE binding contract for the COSGN00C
golden-record test fixture, mediating between (a) the COBOL source
`app/cbl/COSGN00C.cbl` (Signon; CICS transaction `CC00`), (b) the Java
translation `com.blitzy.carddemo.application.signon.CoSgn00C`, (c) the
harness `com.blitzy.carddemo.tests.golden.CoSgn00CGoldenTest`, and (d) the
four fixture files (`usrsec.txt`, `input_scenario.txt`, `stdout.txt`,
`bms_output.txt`). COSGN00C is the **primary CICS entry point** of the
CardDemo application — the ONLY program reached when `EIBCALEN = 0` at first
connection; all other online programs check this condition and `EXEC CICS
XCTL` to COSGN00C as the universal signon entry. Pattern adapted from
`golden/cousr00c/expected/README.md` and tailored for COSGN00C's
single-READ + branched-XCTL + PF3-session-terminate semantics.

ANY change to a fixture file, scenario count, message verbatim, or BMS map
invariant MUST be accompanied by a corresponding change to this README.
Future contract updates MUST modify content within phases rather than
reorganizing phases.

Authority references (binding):

- AAP §0.1.1 — preserve `app/` COBOL source tree UNCHANGED as reference implementation.
- AAP §0.1.3 — plaintext password preservation in storage; SEC-USR-PWD remains `PIC X(08)` per behavior parity.
- AAP §0.2.1 — in-scope: `golden/cosgn00c/` directory tree; source files `app/cbl/COSGN00C.cbl`, `app/bms/COSGN00.bms`, `app/cpy-bms/COSGN00.CPY`.
- AAP §0.3.1 — harness structure: `input/` + `expected/` per program.
- AAP §0.4.1 — COSGN00C → `com.blitzy.carddemo.application.signon.CoSgn00C`; BMS map → DTO records `CoSgn00Input` / `CoSgn00Output`.
- AAP §0.6.4 — `java.time` mandate for date/time formatting in POPULATE-HEADER-INFO.
- AAP §0.6.5 — `java.nio.file` mandate for all file I/O; NO `java.io.File`.
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal`; virtual-thread fan-out forbidden where ordering is observable.
- AAP §0.6.11 — golden-record harness as non-negotiable PR gate; `@Disabled` until COBOL capture committed.
- AAP §0.6.12 — architectural override: no Spring container; plain Java with constructor injection.
- AAP §0.7.1 — Minimal Change Clause: verbatim error messages; case-insensitive matching via `FUNCTION UPPER-CASE`.
- AAP §0.7.2 — SECURITY MANDATE: no password value in any log or display surface; no card PAN logged in full.
- AAP §0.7.4 — pattern-matching exhaustiveness; no JEP 502/505/507 preview features.
- AAP §0.7.5 — capture procedure in `java/MIGRATION_NOTES.md` §1.6.

Sibling pattern reference: `golden/cousr00c/expected/README.md`. COSGN00C and
COUSR00C are both READ-ONLY USRSEC consumers but differ structurally: COSGN00C
uses a single `EXEC CICS READ DATASET` (random by RIDFLD) while COUSR00C uses
sequential browse via STARTBR / READNEXT / READPREV / ENDBR. The 13-phase
contract pattern is preserved verbatim; the contents are re-grounded against
COSGN00C-specific signon-entry semantics.

The test class `com.blitzy.carddemo.tests.golden.CoSgn00CGoldenTest` is
annotated `@Disabled("Awaiting COBOL capture per java/MIGRATION_NOTES.md §1.6")`
until `stdout.txt` and `bms_output.txt` in this directory are replaced with
captured COBOL artifacts per AAP §0.6.11. Placeholder content suffices for
harness scaffolding but NOT for byte-level parity assertions.

## Phase 1: COBOL Source — PROGRAM-ID and Working-Storage Variables

`PROGRAM-ID` is `COSGN00C` `[app/cbl/COSGN00C.cbl:L23]`. `AUTHOR` is `AWS`
`[app/cbl/COSGN00C.cbl:L24]`. The complete inventory of working-storage variables
referenced by the test scenarios appears in the table below.

| COBOL Name | PIC | Source Line | Java Equivalent | Notes |
|---|---|---|---|---|
| `WS-PGMNAME` | `X(08) VALUE 'COSGN00C'` | `[app/cbl/COSGN00C.cbl:L36]` | `CoSgn00C.PGMNAME` constant | Identifies emitter program in commarea on XCTL |
| `WS-TRANID` | `X(04) VALUE 'CC00'` | `[app/cbl/COSGN00C.cbl:L37]` | `CoSgn00C.TRANID` constant | CICS transaction ID — `CC00` (NOT `CU00` like the COUSR sibling) |
| `WS-MESSAGE` | `X(80) VALUE SPACES` | `[app/cbl/COSGN00C.cbl:L38]` | local `String` in use-case | Routed to `ERRMSGO` in SEND-SIGNON-SCREEN; routed to SEND TEXT FROM in SEND-PLAIN-TEXT |
| `WS-USRSEC-FILE` | `X(08) VALUE 'USRSEC  '` | `[app/cbl/COSGN00C.cbl:L39]` | `UserSecurityRepository.DATASET_NAME` | Trailing 2-space padding to reach 8 bytes preserved verbatim |
| `WS-ERR-FLG` (88 ERR-FLG-ON/OFF) | `X(01) VALUE 'N'` | `[app/cbl/COSGN00C.cbl:L40-L42]` | local `boolean errorFlag` | Sealed two-state — no third value permitted |
| `WS-RESP-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COSGN00C.cbl:L43]` | sealed `FileStatus` hierarchy | Captured from CICS RESP on EXEC CICS RECEIVE / READ |
| `WS-REAS-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COSGN00C.cbl:L44]` | included in `FileStatus.IoError(int code, int reason, ...)` | RESP2 reason code |
| `WS-USER-ID` | `X(08)` | `[app/cbl/COSGN00C.cbl:L45]` | local `String userId` (length 8) | Upper-cased copy of `USERIDI` for RIDFLD lookup |
| `WS-USER-PWD` | `X(08)` | `[app/cbl/COSGN00C.cbl:L46]` | local `String userPwd` (length 8) | Upper-cased copy of `PASSWDI` for byte-equal comparison |

COPY directives at `[app/cbl/COSGN00C.cbl:L48-L58]` pull in the structural
record layouts: `COCOM01Y` (commarea), `COSGN00` (BMS symbolic map), `COTTL01Y`
(screen titles), `CSDAT01Y` (date/time work areas), `CSMSG01Y` (common messages
incl. CCDA-MSG-THANK-YOU and CCDA-MSG-INVALID-KEY), `CSUSR01Y` (80-byte
SEC-USER-DATA), `DFHAID` (named AID-key constants `DFHENTER`, `DFHPF3`, etc.),
and `DFHBMSCA` (BMS attribute byte constants). The commented-out `*COPY DFHATTR`
line is present in source but inactive; faithfully translated as a Javadoc
comment in the Java class.

## Phase 2: LINKAGE SECTION and CARDDEMO-COMMAREA

The `LINKAGE SECTION` declares `DFHCOMMAREA` with the standard
OCCURS-DEPENDING-ON `EIBCALEN` pattern at `[app/cbl/COSGN00C.cbl:L64-L67]`:

```
LINKAGE SECTION.
01  DFHCOMMAREA.
  05  LK-COMMAREA                           PIC X(01)
      OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
```

In the Java translation, the canonical `CardDemoCommarea` record parameter on
the `CoSgn00C` use-case entry method is **nullable** — when CICS routes the
first connection to COSGN00C with `EIBCALEN = 0`, the commarea passed to Java is
`null` (or equivalently a freshly LOW-VALUES-initialized record). Per AAP §0.7.4
and AAP §0.6.4 mandates, the JEP 513 Flexible Constructor Bodies feature is
used in records to validate commarea state before binding fields; no
`default` branch in any pattern-matching switch on commarea sub-records.

The base `CARDDEMO-COMMAREA` is defined in `app/cpy/COCOM01Y.cpy` at
`[app/cpy/COCOM01Y.cpy:L19-L44]`. The five sub-records and their Java mappings:

| Commarea Sub-Record | Source | Java Equivalent | Sealed Hierarchies |
|---|---|---|---|
| `CDEMO-GENERAL-INFO` | `[app/cpy/COCOM01Y.cpy:L20-L31]` | `CardDemoCommarea.GeneralInfo` record | `UserType` sealed (Admin = 'A' / User = 'U') from `[app/cpy/COCOM01Y.cpy:L27-L28]`; `PgmContext` sealed (Enter = 0 / Reenter = 1) from `[app/cpy/COCOM01Y.cpy:L30-L31]` |
| `CDEMO-CUSTOMER-INFO` | `[app/cpy/COCOM01Y.cpy:L32-L36]` | `CardDemoCommarea.CustomerInfo` record | Untouched by COSGN00C |
| `CDEMO-ACCOUNT-INFO` | `[app/cpy/COCOM01Y.cpy:L37-L39]` | `CardDemoCommarea.AccountInfo` record | Untouched by COSGN00C |
| `CDEMO-CARD-INFO` | `[app/cpy/COCOM01Y.cpy:L40-L41]` | `CardDemoCommarea.CardInfo` record | Untouched by COSGN00C |
| `CDEMO-MORE-INFO` | `[app/cpy/COCOM01Y.cpy:L42-L44]` | `CardDemoCommarea.MoreInfo` record | Untouched by COSGN00C |

Commarea fields populated on success — set in READ-USER-SEC-FILE at
`[app/cbl/COSGN00C.cbl:L224-L228]` before the XCTL dispatch:

| Commarea Field | Value Source | COBOL Line |
|---|---|---|
| `CDEMO-FROM-TRANID` | `WS-TRANID` (= `'CC00'`) | `[app/cbl/COSGN00C.cbl:L224]` |
| `CDEMO-FROM-PROGRAM` | `WS-PGMNAME` (= `'COSGN00C'`) | `[app/cbl/COSGN00C.cbl:L225]` |
| `CDEMO-USER-ID` | `WS-USER-ID` (upper-cased input) | `[app/cbl/COSGN00C.cbl:L226]` |
| `CDEMO-USER-TYPE` | `SEC-USR-TYPE` (read from USRSEC; `'A'` or `'U'`) | `[app/cbl/COSGN00C.cbl:L227]` |
| `CDEMO-PGM-CONTEXT` | `ZEROS` (reset to first-time entry from POV of downstream) | `[app/cbl/COSGN00C.cbl:L228]` |


## Phase 3: MAIN-PARA EIBAID Dispatch Logic

The MAIN-PARA EVALUATE-EIBAID block at `[app/cbl/COSGN00C.cbl:L80-L96]` is the
root dispatch for every conversational turn AFTER the first connection. The
structure has ONLY 2 named AID branches plus a WHEN OTHER catch-all — a
narrower surface than sibling user-management programs (COUSR00C handles 4 AID
keys; COUSR03C handles 5). The block opens with the entry-point check at
`[app/cbl/COSGN00C.cbl:L80-L83]`: when `EIBCALEN = 0` (first connection), the
program MOVEs LOW-VALUES to `COSGN0AO`, MOVEs -1 to `USERIDL` (initial-cursor
positioning), and PERFORMs SEND-SIGNON-SCREEN. COSGN00C is the **ONLY**
EIBCALEN=0 program in the CardDemo system; every other online program checks
this condition and XCTLs here as the signon entry point.

| AID Key | Source Line | Handler | Java Equivalent (Sealed Pattern) |
|---|---|---|---|
| `DFHENTER` | `[app/cbl/COSGN00C.cbl:L86-L87]` | PERFORM PROCESS-ENTER-KEY | `AidKey.Enter` pattern case |
| `DFHPF3` | `[app/cbl/COSGN00C.cbl:L88-L90]` | MOVE CCDA-MSG-THANK-YOU + PERFORM SEND-PLAIN-TEXT | `AidKey.PfKey03` pattern case |
| WHEN OTHER | `[app/cbl/COSGN00C.cbl:L91-L94]` | MOVE 'Y' to WS-ERR-FLG; MOVE CCDA-MSG-INVALID-KEY; PERFORM SEND-SIGNON-SCREEN | catch-all permit handler (NOT a `default` branch) |

The Java translation MUST be a pattern-matching `switch` on a sealed `AidKey`
hierarchy from `app/cpy/CVCRD01Y.cpy:§CCARD-AID` with NO `default` branch per
AAP §0.7.4. Exhaustiveness is enforced by the compiler — every AID-key permit
must be handled explicitly. The catch-all "other AID" outcome is realized via
the residual permits in `AidKey` that are not `Enter` or `PfKey03` (e.g., the
remaining PF-key, PA-key, and CLEAR permits).

The final `EXEC CICS RETURN` at `[app/cbl/COSGN00C.cbl:L98-L102]` with
`TRANSID(WS-TRANID)`, `COMMAREA(CARDDEMO-COMMAREA)`, and
`LENGTH(LENGTH OF CARDDEMO-COMMAREA)` is the pseudo-conversation re-entry
point. This applies for the EIBCALEN=0 initial-display path, the ENTER path
(both validation-failure and password-mismatch branches), and the WHEN OTHER
invalid-AID path. The PF3 path takes a DIFFERENT return route through
SEND-PLAIN-TEXT (see Phase 7) — it does NOT pass through L98-L102.

## Phase 4: PROCESS-ENTER-KEY — Input Validation and Case Normalization

The PROCESS-ENTER-KEY paragraph at `[app/cbl/COSGN00C.cbl:L108-L140]` is the
only handler invoked for the DFHENTER AID-key. It performs four steps:

1. **EXEC CICS RECEIVE** at `[app/cbl/COSGN00C.cbl:L110-L115]` — reads user
   input from the terminal into the COSGN0AI symbolic input record (USERIDI,
   PASSWDI plus header fields). RESP and RESP2 are captured into `WS-RESP-CD`
   and `WS-REAS-CD`.
2. **EVALUATE TRUE** block at `[app/cbl/COSGN00C.cbl:L117-L130]`:
   - `WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES` at
     `[app/cbl/COSGN00C.cbl:L118-L122]` → MOVE `'Y'` to WS-ERR-FLG; MOVE the
     verbatim string `'Please enter User ID ...'` to WS-MESSAGE
     `[app/cbl/COSGN00C.cbl:L120]`; MOVE -1 to USERIDL (cursor positioning);
     PERFORM SEND-SIGNON-SCREEN.
   - `WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES` at
     `[app/cbl/COSGN00C.cbl:L123-L127]` → MOVE `'Y'` to WS-ERR-FLG; MOVE the
     verbatim string `'Please enter Password ...'` to WS-MESSAGE
     `[app/cbl/COSGN00C.cbl:L125]`; MOVE -1 to PASSWDL; PERFORM SEND-SIGNON-SCREEN.
   - `WHEN OTHER` at `[app/cbl/COSGN00C.cbl:L128-L129]` → `CONTINUE` (proceed
     to case normalization).
3. **Case normalization** at `[app/cbl/COSGN00C.cbl:L132-L136]`:
   - `MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID, CDEMO-USER-ID`
   - `MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD`
4. **Conditional file read** at `[app/cbl/COSGN00C.cbl:L138-L140]`:
   - `IF NOT ERR-FLG-ON` → PERFORM READ-USER-SEC-FILE.

The `FUNCTION UPPER-CASE` call at L132-L136 means every lower- or mixed-case
USERID variant resolves to the same canonical upper-case key for the RIDFLD
lookup; similarly the password is upper-cased before byte-equality. The stored
password in USRSEC MUST therefore be stored in upper-case form for signon to
succeed — `usrsec.txt` reflects this. `MOVE -1 TO USERIDL / PASSWDL` is the
CICS BMS cursor-positioning convention: length = -1 places the cursor at that
field on the next SEND MAP.

The Java translation mirrors this: `UserSecurityRepository.read(userId.toUpperCase(Locale.ROOT))`
returns `Optional<SecUserData>`; the password comparison performs a byte-exact
`Arrays.equals(...)` between the stored 8-byte password and the upper-cased
input — the comparison is byte-equal AFTER input has been upper-cased per L132-L136.

## Phase 5: READ-USER-SEC-FILE — VSAM Single-Record READ Logic

The READ-USER-SEC-FILE paragraph at `[app/cbl/COSGN00C.cbl:L209-L257]` is the
sole USRSEC accessor in COSGN00C. It performs exactly ONE `EXEC CICS READ` and
dispatches based on the RESP code.

The READ at `[app/cbl/COSGN00C.cbl:L211-L219]` specifies: `DATASET(WS-USRSEC-FILE)`
= `'USRSEC  '`; `INTO(SEC-USER-DATA)` — 80-byte buffer per
`[app/cpy/CSUSR01Y.cpy:L17-L23]` (SEC-USR-ID `X(08)`, SEC-USR-FNAME `X(20)`,
SEC-USR-LNAME `X(20)`, SEC-USR-PWD `X(08)` plaintext, SEC-USR-TYPE `X(01)`,
SEC-USR-FILLER `X(23)`); `LENGTH` = 80; `RIDFLD(WS-USER-ID)` — the upper-cased
input from Phase 4; `KEYLENGTH` = 8; `RESP(WS-RESP-CD) / RESP2(WS-REAS-CD)` capture.

The post-read `EVALUATE WS-RESP-CD` at `[app/cbl/COSGN00C.cbl:L221-L257]` has
three branches:

- **`WHEN 0`** (equivalent to `DFHRESP(NORMAL)`) at `[app/cbl/COSGN00C.cbl:L222]`:
  the password compare `IF SEC-USR-PWD = WS-USER-PWD` at
  `[app/cbl/COSGN00C.cbl:L223]` is a byte-exact equality between the stored
  8-byte password and the upper-cased input.
  - **Success path** at `[app/cbl/COSGN00C.cbl:L224-L240]`: populate the
    five commarea fields per Phase 2; branch on SEC-USR-TYPE.
    - `IF CDEMO-USRTYP-ADMIN` (SEC-USR-TYPE = 'A') at
      `[app/cbl/COSGN00C.cbl:L230-L234]` → `EXEC CICS XCTL PROGRAM('COADM01C')
      COMMAREA(CARDDEMO-COMMAREA)`.
    - `ELSE` (regular user; SEC-USR-TYPE = 'U') at
      `[app/cbl/COSGN00C.cbl:L235-L239]` → `EXEC CICS XCTL PROGRAM('COMEN01C')
      COMMAREA(CARDDEMO-COMMAREA)`.
  - **Password-mismatch path** at `[app/cbl/COSGN00C.cbl:L241-L246]`: MOVE the
    verbatim string `'Wrong Password. Try again ...'` to WS-MESSAGE
    `[app/cbl/COSGN00C.cbl:L242-L243]`; MOVE -1 to PASSWDL; PERFORM
    SEND-SIGNON-SCREEN. Note WS-ERR-FLG is NOT set in this branch — the COBOL
    source intentionally allows the user to retry without elevating the
    error state. Translated faithfully (see MIGRATION_NOTES.md).
- **`WHEN 13`** (equivalent to `DFHRESP(NOTFND)`) at
  `[app/cbl/COSGN00C.cbl:L247-L251]`: MOVE `'Y'` to WS-ERR-FLG; MOVE the
  verbatim string `'User not found. Try again ...'` to WS-MESSAGE
  `[app/cbl/COSGN00C.cbl:L249]`; MOVE -1 to USERIDL; PERFORM SEND-SIGNON-SCREEN.
- **`WHEN OTHER`** at `[app/cbl/COSGN00C.cbl:L252-L256]`: MOVE `'Y'` to
  WS-ERR-FLG; MOVE the verbatim string `'Unable to verify the User ...'` to
  WS-MESSAGE `[app/cbl/COSGN00C.cbl:L254]`; MOVE -1 to USERIDL; PERFORM
  SEND-SIGNON-SCREEN. Triggered on any unexpected RESP value other than 0 or
  13 (e.g., DFHRESP(IOERR), DFHRESP(NOTOPEN), DFHRESP(INVREQ)).

NOTE on COBOL idiom: the source at L222 and L247 uses literal numeric values
`WHEN 0` and `WHEN 13` rather than the symbolic `DFHRESP(NORMAL)` /
`DFHRESP(NOTFND)` macros. The numeric values are functionally equivalent since
the IBM-supplied `DFHRESP` macro expansion yields 0 for NORMAL and 13 for
NOTFND on EXEC CICS RESP returns. The Java translation documents the symbolic
intent in Javadoc for clarity.

Java translation contract: `UserSecurityRepository.read(userId)` returns
`Optional<SecUserData>` (AAP §0.4.1 with sealed `FileStatus` companion for
non-normal outcomes); the EVALUATE becomes a pattern-matching switch with
NO `default` branch (AAP §0.7.4); the XCTL branching becomes a strategy
pattern via `ProgramRegistry` (AAP §0.3.2) or direct method-reference
dispatch into `CoAdm01C` (admin) or `CoMen01C` (user); the password
comparison MUST be byte-exact between stored plaintext and upper-cased
input per AAP §0.1.3.

## Phase 6: SEND/RECEIVE/POPULATE-HEADER Paragraphs

Three auxiliary paragraphs handle screen I/O and header population:

1. **SEND-SIGNON-SCREEN** at `[app/cbl/COSGN00C.cbl:L145-L157]` — emits the
   signon map after any validation failure or first connection. Sequence:
   PERFORM POPULATE-HEADER-INFO; MOVE WS-MESSAGE to ERRMSGO OF COSGN0AO;
   `EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00') FROM(COSGN0AO) ERASE
   CURSOR`. The ERASE clause clears any previously-displayed content;
   CURSOR honors the prior MOVE -1 to USERIDL or PASSWDL.

2. **SEND-PLAIN-TEXT** at `[app/cbl/COSGN00C.cbl:L162-L172]` — the PF3 exit
   path's terminal output. Sequence: `EXEC CICS SEND TEXT FROM(WS-MESSAGE)
   LENGTH(LENGTH OF WS-MESSAGE) ERASE FREEKB`; `EXEC CICS RETURN` (no
   TRANSID; no COMMAREA). After this RETURN, the pseudo-conversation ends and
   CICS de-allocates the transaction. This is the unique PF3 termination
   path — distinct from the L98-L102 conversational RETURN.

3. **POPULATE-HEADER-INFO** at `[app/cbl/COSGN00C.cbl:L177-L204]` — populates
   header fields on COSGN0AO before every SEND MAP. Sequence: MOVE FUNCTION
   CURRENT-DATE to WS-CURDATE-DATA (L179); MOVE CCDA-TITLE01/CCDA-TITLE02 to
   TITLE01O/TITLE02O (L181-L182); MOVE WS-TRANID (`'CC00'`) to TRNNAMEO (L183);
   MOVE WS-PGMNAME (`'COSGN00C'`) to PGMNAMEO (L184); format date components
   into WS-CURDATE-MM-DD-YY → CURDATEO (L186-L190); format time components
   into WS-CURTIME-HH-MM-SS → CURTIMEO (L192-L196); `EXEC CICS ASSIGN APPLID`
   into APPLIDO (L198-L200); `EXEC CICS ASSIGN SYSID` into SYSIDO (L202-L204).

Java translation: a `populateHeaderInfo()` private method on `CoSgn00C` that
uses `java.time.LocalDateTime` and `DateTimeFormatter.ofPattern("MM/dd/yy")` /
`("HH:mm:ss")` (AAP §0.6.4). The current date/time MUST be **injected** via
constructor or `ScopedValue` to keep tests deterministic. APPLID/SYSID values
are similarly injected to avoid environment dependence during golden-record
comparison.

## Phase 7: PF3 Exit Path — Session Termination

The PF3 branch at `[app/cbl/COSGN00C.cbl:L88-L90]` exhibits unique session-end
behavior. It does NOT XCTL to any program — it does:

1. MOVE `CCDA-MSG-THANK-YOU` to `WS-MESSAGE` (the verbatim 50-char message
   from `app/cpy/CSMSG01Y.cpy:L18-L19`).
2. PERFORM SEND-PLAIN-TEXT, which emits `EXEC CICS SEND TEXT` followed by
   `EXEC CICS RETURN` with no TRANSID and no COMMAREA.
3. The `EXEC CICS RETURN` without TRANSID terminates the pseudo-conversation;
   CICS releases the transaction and returns control to the terminal selector
   menu.

This contrasts with sibling COUSR00C where PF3 XCTLs to COADM01C. COSGN00C
is unique because it is the **session entry point** — there is no "previous
program" to return to. The only way out is to terminate the session entirely.
The Java translation: the PF3 branch in the pattern-matching switch returns a
special `SessionEnd` outcome record (a sentinel sealed permit, never a
`default`); the composition root in `carddemo-app` interprets `SessionEnd`
as a clean exit (no further dispatch; main method returns). The verbatim
message string `'Thank you for using CardDemo application...      '` (literal
49 chars as defined in `app/cpy/CSMSG01Y.cpy:L18-L19`: 43 message bytes + 6
trailing ASCII space bytes; field width PIC X(50)) MUST be preserved exactly
in the captured SEND TEXT artifact.


## Phase 8: Capture Procedure Cross-Reference and Security Mandate

To replace `stdout.txt` and `bms_output.txt` with captured COBOL artifacts,
follow the procedure documented in `java/MIGRATION_NOTES.md` §1.6 (per AAP
§0.7.5). Until those captures are committed, `CoSgn00CGoldenTest` is
`@Disabled` per AAP §0.6.11.

**CRITICAL SECURITY MANDATE** (per AAP §0.7.2):

The capture procedure for COSGN00C is UNIQUE among CardDemo programs because
COSGN00C is the only program where USRSEC passwords transit through the BMS
input buffer (via `FIELD PASSWDI=` directives in `input_scenario.txt`). Even
though COSGN0A's PASSWD field has the DRK attribute (non-display) per
`[app/bms/COSGN00.bms:L175-L180]`, the BMS input buffer in memory MAY contain
the typed password bytes for the input direction. The COSGN00C capture
procedure MUST:

1. Verify that NO `SEC-USR-PWD` value from `usrsec.txt` appears in either the
   captured `stdout.txt` or `bms_output.txt`.
2. Mask any captured BMS input-buffer regions corresponding to the PASSWDI
   field at its byte-offset within the COSGN0AI symbolic map per
   `[app/cpy-bms/COSGN00.CPY:L73-L78]`.
3. Strip any DISPLAY-equivalent trace output that includes the password value.
4. Reject the commit if any password value is detected (CI gate).

This is in contrast to sibling COUSR00C where the BMS map (COUSR0A) has NO
PASSWD field at all — so the security mandate is automatically satisfied by
absence rather than by masking. The `MIGRATION_NOTES.md` §1.6 procedure
documents the masking step explicitly, implemented via byte-level buffer
offset replacement rather than text substitution (which would be unreliable
against arbitrary password content).

## Phase 9: Test Class @Disabled Mandate

Per AAP §0.6.11, the test class `CoSgn00CGoldenTest` MUST be annotated
`@Disabled("Awaiting COBOL capture per java/MIGRATION_NOTES.md §1.6")` until
both `stdout.txt` and `bms_output.txt` are replaced with captured COBOL
artifacts. When enabled, the test class's verification points MUST include
AT LEAST these 11 assertions:

1. **Scenario 1** initial display: SEND-SIGNON-SCREEN matches expected layout; `USERIDL = -1` (cursor at USERID).
2. **Scenario 2** empty USERID: `ERRMSGO` contains verbatim `'Please enter User ID ...'`.
3. **Scenario 3** empty PASSWORD: `ERRMSGO` contains verbatim `'Please enter Password ...'`.
4. **Scenario 4** non-existent USERID: `ERRMSGO` contains verbatim `'User not found. Try again ...'`.
5. **Scenario 5** wrong password: `ERRMSGO` contains verbatim `'Wrong Password. Try again ...'`.
6. **Scenario 6** admin signon: XCTL target = `'COADM01C'`; commarea CDEMO-USER-ID / CDEMO-USER-TYPE / CDEMO-FROM-PROGRAM / CDEMO-FROM-TRANID match; USRSEC byte-identical pre/post.
7. **Scenario 7** regular-user signon: XCTL target = `'COMEN01C'`; commarea fields match expected.
8. **Scenario 8** PF3 exit: SEND TEXT contains verbatim `'Thank you for using CardDemo application...      '` (literal 49 chars: 43-char message + 6 trailing spaces); RETURN with no TRANSID.
9. **Scenario 9** invalid AID: `ERRMSGO` contains verbatim `'Invalid key pressed. Please see below...         '` (literal 49 chars: 40-char message + 9 trailing spaces).
10. **Scenarios 10 & 11** case-insensitive matching: lower- and mixed-case inputs both XCTL to `COADM01C` with `CDEMO-USER-ID = 'ADMIN001'`.
11. **Security assertions** (scenarios 6, 7, 10, 11): captured outputs contain NO `SEC-USR-PWD` value from `usrsec.txt`.

## Phase 10: Required Test Scenarios

The 12 scenarios from `input_scenario.txt` are summarized below.

| # | Scenario | AID | COBOL Source | Expected Output |
|---|---|---|---|---|
| 1 | Initial display (EIBCALEN=0; first connection) | (none) | `[app/cbl/COSGN00C.cbl:L80-L83]` | MOVE LOW-VALUES to COSGN0AO; SEND-SIGNON-SCREEN; cursor at USERID |
| 2 | ENTER with empty USERID | ENTER | `[app/cbl/COSGN00C.cbl:L118-L122]` | ERRMSGO = `'Please enter User ID ...'` |
| 3 | ENTER with USERID filled, empty PASSWORD | ENTER | `[app/cbl/COSGN00C.cbl:L123-L127]` | ERRMSGO = `'Please enter Password ...'` |
| 4 | ENTER with non-existent USERID (RESP=13) | ENTER | `[app/cbl/COSGN00C.cbl:L247-L251]` | ERRMSGO = `'User not found. Try again ...'` |
| 5 | ENTER with valid USERID, wrong PASSWORD | ENTER | `[app/cbl/COSGN00C.cbl:L223,L241-L246]` | ERRMSGO = `'Wrong Password. Try again ...'` |
| 6 | ENTER with valid admin signon (SEC-USR-TYPE='A') | ENTER | `[app/cbl/COSGN00C.cbl:L230-L234]` | XCTL `COADM01C` with commarea populated |
| 7 | ENTER with valid regular user signon (SEC-USR-TYPE='U') | ENTER | `[app/cbl/COSGN00C.cbl:L235-L239]` | XCTL `COMEN01C` with commarea populated |
| 8 | PF3 exit | PF3 | `[app/cbl/COSGN00C.cbl:L88-L90,L162-L172]` | SEND TEXT with `CCDA-MSG-THANK-YOU`; RETURN no TRANSID — session terminates |
| 9 | Invalid AID key (e.g., PF1) | other | `[app/cbl/COSGN00C.cbl:L91-L94]` | ERRMSGO = `CCDA-MSG-INVALID-KEY` |
| 10 | ENTER with lower-case USERID `admin001` + matching pwd | ENTER | `[app/cbl/COSGN00C.cbl:L132-L136]` | Upper-cased → admin XCTL `COADM01C` (case-insensitive USERID) |
| 11 | ENTER with mixed-case credentials | ENTER | `[app/cbl/COSGN00C.cbl:L132-L136]` | Upper-cased → admin XCTL `COADM01C` (case-insensitive PASSWORD) |
| 12 | ENTER with simulated WHEN OTHER RESP code | ENTER | `[app/cbl/COSGN00C.cbl:L252-L256]` | ERRMSGO = `'Unable to verify the User ...'` |

Notes per scenario:

- Scenario 1 covers the unique EIBCALEN=0 entry path (`[app/cbl/COSGN00C.cbl:L80-L83]`).
- Scenarios 2 and 3 exercise the EVALUATE TRUE empty-input checks.
- Scenarios 4 and 12 exercise the `WHEN 13` and `WHEN OTHER` branches of the
  READ-USER-SEC-FILE RESP-code dispatch.
- Scenario 5 exercises the password-mismatch ELSE branch of the success path.
- Scenarios 6 and 7 exercise the branched XCTL routing to COADM01C vs. COMEN01C.
- Scenario 8 exercises the unique session-end SEND-PLAIN-TEXT path.
- Scenario 9 exercises the WHEN OTHER AID-key branch of MAIN-PARA.
- Scenarios 10 and 11 verify that `FUNCTION UPPER-CASE` at L132-L136 makes
  both USERID and PASSWORD comparison case-insensitive.

## Phase 11: BMS Map COSGN0A Layout — 24 × 80 Screen

The COSGN0A map is defined at `[app/bms/COSGN00.bms:L26-L28]` with
`SIZE=(24,80)`. Every named field on the map:

| Field Name | Position | Length | Attribute | Direction | Notes |
|---|---|---|---|---|---|
| `Tran :` label | (1,1) | 6 | BLUE, ASKIP, NORM | output | `[app/bms/COSGN00.bms:L29-L33]` |
| `TRNNAME` | (1,8) | 4 | BLUE, ASKIP, FSET, NORM | output | Populated to `'CC00'` by POPULATE-HEADER-INFO `[app/bms/COSGN00.bms:L34-L37]` |
| `TITLE01` | (1,21) | 40 | YELLOW, ASKIP, FSET, NORM | output | First screen title from `CCDA-TITLE01` |
| `Date :` label | (1,64) | 6 | BLUE, ASKIP, NORM | output | — |
| `CURDATE` | (1,71) | 8 | BLUE, ASKIP, FSET, NORM | output | `mm/dd/yy` from WS-CURDATE-MM-DD-YY |
| `Prog :` label | (2,1) | 6 | BLUE, ASKIP, NORM | output | — |
| `PGMNAME` | (2,8) | 8 | BLUE, FSET, NORM, PROT | output | Populated to `'COSGN00C'` |
| `TITLE02` | (2,21) | 40 | YELLOW, ASKIP, FSET, NORM | output | Second screen title from `CCDA-TITLE02` |
| `Time :` label | (2,64) | 6 | BLUE, ASKIP, NORM | output | — |
| `CURTIME` | (2,71) | 9 | BLUE, FSET, NORM, PROT | output | `Ahh:mm:ss` from WS-CURTIME-HH-MM-SS |
| `AppID:` label | (3,1) | 6 | BLUE, FSET, NORM, PROT | output | — |
| `APPLID` | (3,8) | 8 | BLUE, FSET, NORM, PROT | output | Populated via EXEC CICS ASSIGN APPLID |
| `SysID:` label | (3,64) | 6 | BLUE, ASKIP, NORM | output | — |
| `SYSID` | (3,71) | 8 | BLUE, FSET, NORM, PROT | output | Populated via EXEC CICS ASSIGN SYSID |
| Banner text 'Credit Card Demo...' | (5,6) | 66 | NEUTRAL, ASKIP, NORM | output | `[app/bms/COSGN00.bms:L94-L99]` |
| Currency-bill ASCII-art block | rows 7-15 | varies | BLUE, ASKIP, NORM | output | Decorative; `[app/bms/COSGN00.bms:L100-L144]` |
| Prompt `Type your User ID and Password, then press ENTER:` | (17,16) | 49 | TURQUOISE, ASKIP, NORM | output | `[app/bms/COSGN00.bms:L145-L150]` |
| `User ID     :` label | (19,29) | 13 | TURQUOISE, ASKIP, NORM | output | — |
| **`USERID`** | (19,43) | 8 | GREEN, **FSET, IC, NORM, UNPROT** | INPUT | **IC = initial cursor** `[app/bms/COSGN00.bms:L156-L160]` |
| `(8 Char)` hint | (19,52) | 8 | BLUE, ASKIP, NORM | output | — |
| `Password    :` label | (20,29) | 13 | TURQUOISE, ASKIP, NORM | output | — |
| **`PASSWD`** | (20,43) | 8 | GREEN, **DRK, FSET, UNPROT** | INPUT | **DRK = non-display**; INITIAL `'________'` `[app/bms/COSGN00.bms:L175-L180]` |
| `(8 Char)` hint | (20,52) | 8 | BLUE, ASKIP, NORM | output | — |
| `ERRMSG` | (23,1) | 78 | RED, ASKIP, BRT, FSET | output | `[app/bms/COSGN00.bms:L197-L200]` |
| Footer `ENTER=Sign-on  F3=Exit` | (24,1) | 22 | YELLOW, ASKIP, NORM | output | `[app/bms/COSGN00.bms:L201-L205]` |

**CRITICAL STRUCTURAL DISTINGUISHER vs. COUSR00C**: COSGN0A HAS a `PASSWD`
field with the `DRK` attribute (non-display). Sibling COUSR0A (used by
COUSR00C) does NOT have any `PASSWD` field. The `DRK` attribute causes the
typed password to display as blanks on the 3270 terminal but the bytes ARE
present in the BMS input buffer (for the input direction). The Java
translation mirrors this exactly:

- The `CoSgn00Input` DTO record HAS a `String password` field (length 8).
- The `CoSgn00Output` DTO record does NOT include the password value in any
  displayable surface (the response only carries header/title/message/cursor
  fields and DOES carry the input USERIDI echo for re-display but NEVER the
  PASSWDI value).
- All logging surfaces (SLF4J) MUST mask the password value entirely.

The symbolic copybook `[app/cpy-bms/COSGN00.CPY:L17-L84]` defines the COSGN0AI
input record (USERIDI, PASSWDI plus header fields with length/attribute/flag
sub-fields); the overlay `[app/cpy-bms/COSGN00.CPY:L85-L152]` defines the
COSGN0AO output record. The Java DTO records mirror the named input fields
(USERIDI → `userId`, PASSWDI → `password`) and the named output fields
(TRNNAMEO, TITLE01O, CURDATEO, PGMNAMEO, TITLE02O, CURTIMEO, APPLIDO, SYSIDO,
USERIDO, PASSWDO suppressed, ERRMSGO).

## Phase 12: Required Fixture Files in This Directory

This directory contains exactly five fixture files (including this README):

1. **`README.md`** (this file): The authoritative 14-section contract document.
   Source-grounded against COBOL line numbers in `app/cbl/COSGN00C.cbl`. The
   single source of truth for the COSGN00C golden-record fixture.

2. **`usrsec.txt`** (CREATED): Synthesized USRSEC fixture. 4 SEC-USER-DATA
   records of 80 bytes each = 320 bytes total. 1 admin record keyed `ADMIN001`
   plus 3 regular-user records. Sorted ascending by SEC-USR-ID for VSAM KSDS
   convention. Plaintext passwords preserved in storage per AAP §0.1.3
   (storage and logging are separate surfaces; storage retains plaintext per
   behavior parity).

3. **`input_scenario.txt`** (CREATED): 12-scenario harness directive script.
   Format: line-oriented; one directive per line; comments start with `#`.
   Documents 12 sequential 3270 interactions corresponding to the COSGN00C
   transaction flow. Source-grounded against COBOL line numbers. Includes
   `ASSERT_NO_PASSWORD_IN_OUTPUT` assertions on scenarios 6, 7, 10, 11 per
   AAP §0.7.2.

4. **`stdout.txt`** (CREATED as placeholder): Placeholder file containing
   comment lines pending COBOL capture. Expected captured content is EMPTY
   under the 12 scenarios (the COSGN00C source has no DISPLAY statements
   exercised by the scenarios). Replaced by captured artifact per
   `java/MIGRATION_NOTES.md` §1.6.

5. **`bms_output.txt`** (CREATED as placeholder): Placeholder file containing
   comment lines pending COBOL capture. Expected captured content is
   approximately 7 SEND MAP frames + 1 SEND TEXT frame concatenated. Replaced
   by captured artifact per `java/MIGRATION_NOTES.md` §1.6. CRITICAL: capture
   script MUST mask any password bytes per AAP §0.7.2.

**There is no `usrsec_after.txt` in this directory.** COSGN00C is READ-ONLY:
the program performs only `EXEC CICS READ` at `[app/cbl/COSGN00C.cbl:L211-L219]`
with NO `WRITE`, `REWRITE`, `DELETE`, `STARTBR`, `READNEXT`, `READPREV`, or
`ENDBR`. The post-run state of USRSEC is byte-identical to `usrsec.txt`, so
no separate after-fixture is required. The test harness asserts this
invariant via `ASSERT_USRSEC_UNCHANGED` directives in `input_scenario.txt`
scenarios 6, 7, 8, 10, 11.

## Phase 13: Structural Invariants

### 13.1 BMS Map Invariants

- COSGN0A is a 24-row × 80-column 3270 model-2 screen (`[app/bms/COSGN00.bms:L26-L28]`).
- COSGN0A has exactly 2 input fields with UNPROT attribute: USERID at (19,43) and PASSWD at (20,43) — both 8 chars, GREEN, FSET.
- USERID has the IC attribute (initial cursor) — cursor lands here on first SEND MAP.
- PASSWD has the DRK attribute (non-display) — value not visible on terminal but present in input buffer.
- Footer at row 24: `ENTER=Sign-on  F3=Exit` (22 chars, YELLOW). ERRMSG at (23,1) is length 78, RED, ASKIP, BRT, FSET.

### 13.2 USRSEC File Invariants (READ-ONLY)

- Per AAP §0.7.1, `usrsec.txt` is the INITIAL state AND the (implicit) FINAL state because COSGN00C is READ-ONLY.
- COSGN00C does NOT modify USRSEC. It issues only `EXEC CICS READ` at `[app/cbl/COSGN00C.cbl:L211-L219]` — NO `STARTBR`, `READNEXT`, `READPREV`, `WRITE`, `REWRITE`, `DELETE`, or `ENDBR`.
- A `usrsec_after.txt` snapshot, if taken, MUST be byte-identical to `usrsec.txt`. The test class does NOT declare a `usrsec_after.txt` ExpectedOutput; only `stdout.txt` and `bms_output.txt` are in `expectedOutputs()`.
- Sort order: ascending by SEC-USR-ID (VSAM KSDS convention).
- Record length: exactly 80 bytes per SEC-USER-DATA record (`[app/cpy/CSUSR01Y.cpy:L17-L23]`); file length: exactly 4 × 80 = 320 bytes.

### 13.3 Java Mapping Invariants

- Plaintext password preserved in domain record `SecUserData` (`PIC X(08)` → `String` of length 8) per AAP §0.1.3; password NEVER appears in any SLF4J log statement (AAP §0.7.2).
- Password byte-equal comparison performed AFTER upper-casing of input per `[app/cbl/COSGN00C.cbl:L132-L136]` and `[app/cbl/COSGN00C.cbl:L223]`.
- `EVALUATE EIBAID` `[app/cbl/COSGN00C.cbl:L80-L96]` → pattern-matching `switch` on sealed `AidKey` with NO `default` branch (AAP §0.7.4).
- `EVALUATE WS-RESP-CD` `[app/cbl/COSGN00C.cbl:L221-L257]` → pattern-matching `switch` on sealed `FileStatus` hierarchy with NO `default` branch.
- Date/time formatting (POPULATE-HEADER-INFO at `[app/cbl/COSGN00C.cbl:L177-L204]`) uses `java.time.LocalDateTime` + `DateTimeFormatter` per AAP §0.6.4; current date/time MUST be injected (constructor or `ScopedValue`) for deterministic tests.
- All file I/O uses `java.nio.file` (NOT `java.io.File`) per AAP §0.6.5; `ScopedValue` (NOT `ThreadLocal`) for per-request context per AAP §0.6.6.
- NO password hashing introduced — flagged as out-of-scope behavioral change in `java/MIGRATION_NOTES.md` per AAP §0.1.3.
- `@CobolProgram("COSGN00C")` traceability annotation per AAP §0.7.1 cites PROGRAM-ID, source path, and translation date.


## Contrast Matrix: COSGN00C (Signon) vs. Sibling User-Management Programs

| Aspect | COSGN00C (Signon) | COUSR00C (List) | COUSR01C (Add) | COUSR03C (Delete) |
|---|---|---|---|---|
| Transaction ID | `CC00` (L37) | `CU00` | `CU01` | `CU03` |
| File operation | EXEC CICS READ (single record by RIDFLD) | STARTBR / READNEXT / READPREV / ENDBR (browse) | WRITE | READ + DELETE |
| `usrsec_after.txt` | IDENTICAL to usrsec.txt (READ-ONLY) | IDENTICAL (READ-ONLY) | LONGER by 80 bytes | SHORTER by 80 bytes |
| Editable BMS input fields | 2 (USERID + PASSWD) | 1 (USRIDIN) + 10 SEL rows | 5 (USRIDIN, FNAME, LNAME, PASSWD-DRK, USRTYPE) | 1 (USRIDIN only) |
| PASSWD field on map | PRESENT with DRK attribute | ABSENT | PRESENT with DRK | ABSENT |
| AID keys handled (named) | 2 (ENTER, PF3) | 4 (ENTER, PF3, PF7, PF8) | varies | 5 (ENTER, PF3, PF4, PF5, PF12) |
| PF3 destination | SEND-PLAIN-TEXT + RETURN (session-end) | XCTL COADM01C | XCTL COADM01C | XCTL COADM01C |
| `EIBCALEN = 0` entry-point | YES — primary signon; ONLY program reached at first connection | NO | NO | NO |
| Case-insensitive matching | YES via FUNCTION UPPER-CASE (L132-L136) | N/A | N/A | N/A |
| XCTL routing branches | 2 (COADM01C if `'A'`; COMEN01C if `'U'`) | N/A | N/A | N/A |

## Verbatim COBOL Message Catalog

The 7 verbatim messages used by COSGN00C — every Java emission of these
strings MUST be byte-exact per AAP §0.7.1 (Minimal Change Clause). NEVER
paraphrase. NEVER replace `...` with Unicode ellipsis `…`. NEVER add or
remove punctuation. NEVER strip trailing padding spaces.

| # | Message (verbatim, single-quoted) | COBOL Line | Triggering Condition |
|---|---|---|---|
| 1 | `'Please enter User ID ...'` | `[app/cbl/COSGN00C.cbl:L120]` | Empty `USERIDI` input |
| 2 | `'Please enter Password ...'` | `[app/cbl/COSGN00C.cbl:L125]` | Empty `PASSWDI` input |
| 3 | `'Wrong Password. Try again ...'` | `[app/cbl/COSGN00C.cbl:L242-L243]` | READ returns 0 + password mismatch |
| 4 | `'User not found. Try again ...'` | `[app/cbl/COSGN00C.cbl:L249]` | READ returns 13 (DFHRESP(NOTFND)) |
| 5 | `'Unable to verify the User ...'` | `[app/cbl/COSGN00C.cbl:L254]` | READ WHEN OTHER (unexpected RESP) |
| 6 | `'Thank you for using CardDemo application...      '` (49 chars: 43-char message + 6 trailing spaces; PIC X(50)) | `app/cpy/CSMSG01Y.cpy:L18-L19` | PF3 exit (`CCDA-MSG-THANK-YOU`) |
| 7 | `'Invalid key pressed. Please see below...         '` (49 chars: 40-char message + 9 trailing spaces; PIC X(50)) | `app/cpy/CSMSG01Y.cpy:L20-L21` | WHEN OTHER AID key (`CCDA-MSG-INVALID-KEY`) |

The 50-char `CCDA-MSG-THANK-YOU` and `CCDA-MSG-INVALID-KEY` literals from
`CSMSG01Y` MUST preserve their exact 6 and 9 trailing-space padding
respectively. The literal string in single quotes has 49 chars; the COBOL
field width is `PIC X(50)` (one implicit space pad applied at storage time).

## Source Lineage

Source files this contract derives from (read-only references; preserved
unchanged per AAP §0.1.1):

- `app/cbl/COSGN00C.cbl` (260 lines) — COBOL source; paragraph inventory in Phases 3-7.
- `app/bms/COSGN00.bms` (210 lines) — BMS map definition; field layout in Phase 11.
- `app/cpy-bms/COSGN00.CPY` (152 lines) — symbolic map copybook (01 COSGN0AI / 01 COSGN0AO).
- `app/cpy/CSUSR01Y.cpy` (26 lines) — 80-byte SEC-USER-DATA layout (referenced by `usrsec.txt`).
- `app/cpy/COCOM01Y.cpy` (47 lines) — CARDDEMO-COMMAREA with CDEMO-GENERAL-INFO and the 88-conditions for CDEMO-USRTYP-ADMIN/USER.
- `app/cpy/CSMSG01Y.cpy` (24 lines) — common message constants including CCDA-MSG-THANK-YOU and CCDA-MSG-INVALID-KEY.

## Capture Procedure Cross-Reference

To replace `stdout.txt` and `bms_output.txt` with captured COBOL artifacts,
follow the procedure documented in `java/MIGRATION_NOTES.md` §1.6 (per AAP
§0.7.5). Until those captures are committed, `CoSgn00CGoldenTest` remains
`@Disabled` per AAP §0.6.11. The COSGN00C capture procedure has a UNIQUE
security requirement (per AAP §0.7.2): because COSGN00C is the only
CardDemo program where passwords transit through the BMS input buffer, the
capture script MUST mask any password bytes before commit. The masking is
done at the byte-offset level corresponding to the PASSWDI field within the
COSGN0AI symbolic map (offset and length per `[app/cpy-bms/COSGN00.CPY:L73-L78]`).

## DO NOT Modify

```
This contract is the authoritative specification for the COSGN00C golden-record
test fixture. Any change to a fixture file, scenario count, message verbatim, or
BMS map invariant MUST be accompanied by a corresponding change to this README
and re-captured stdout.txt and bms_output.txt artifacts. See AAP section 0.7.1
(Minimal Change Clause) and AAP section 0.6.11 (PR gate). COSGN00C is the
primary CICS entry point (EIBCALEN=0); breaking changes have repository-wide
impact across all 17 online programs that XCTL to COSGN00C when EIBCALEN=0.
```
