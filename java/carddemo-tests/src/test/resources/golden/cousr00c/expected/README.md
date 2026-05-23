# COUSR00C (User List, CU00) — Golden-Record Test Fixture Contract

## Phase 0: Header and Authority Cascade

This document is the AUTHORITATIVE binding contract for the COUSR00C
golden-record test fixture, mediating between (a) the COBOL source
`app/cbl/COUSR00C.cbl` (User List; CICS transaction CU00), (b) the
Java translation `com.blitzy.carddemo.application.user.CoUsr00C`,
(c) the harness `com.blitzy.carddemo.tests.golden.CoUsr00CGoldenTest`,
and (d) the four fixture files in this directory. COUSR00C is the
admin-only, READ-ONLY user-list browser that paginates VSAM browsing
of USRSEC 10 users per page (PF8 forward / PF7 backward) and
dispatches row-level 'U'/'D' selections to COUSR02C (Update) or
COUSR03C (Delete) via EXEC CICS XCTL. Future contract updates MUST
modify content within phases rather than reorganizing phases.

Authority references (binding):

- AAP §0.1.3 — plaintext password preservation in storage; never in display or log.
- AAP §0.2.1 — in-scope: `golden/cousr00c/` directory as part of
  `java/carddemo-tests/src/test/resources/golden/**/*`; source files
  `app/cbl/COUSR00C.cbl`, `app/bms/COUSR00.bms`, `app/cpy-bms/COUSR00.CPY`.
- AAP §0.3.1 — harness structure: input/ + expected/ per program.
- AAP §0.4.1 — COUSR00C → CoUsr00C in `com.blitzy.carddemo.application.user`;
  BMS map → DTO records `CoUsr00Input`/`CoUsr00Output`.
- AAP §0.6.4 — `java.time` mandate in POPULATE-HEADER-INFO.
- AAP §0.6.5 — `java.nio.file` mandate; NO `java.io.File`.
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal`; virtual-thread
  fan-out forbidden where ordering is observable.
- AAP §0.6.11 — golden-record harness as non-negotiable PR gate;
  `@Disabled` until COBOL capture committed.
- AAP §0.6.12 — architectural override: no Spring; plain Java with
  constructor injection.
- AAP §0.7.1 — Minimal Change Clause: verbatim error messages; preserve sequencing.
- AAP §0.7.2 — no password value in logs or display; admin guard at COADM01C.
- AAP §0.7.4 — no JEP 502/505/507 preview features.
- AAP §0.7.5 — capture procedure in `java/MIGRATION_NOTES.md` §1.6.

Sibling pattern reference: `golden/cousr03c/expected/README.md` —
COUSR0A and COUSR3A are the only two user-CRUD BMS maps that LACK a
`PASSWD` field, making COUSR03C the closest structural sibling; unlike
COUSR03C, COUSR00C is fully READ-ONLY and supports pagination.

The test class `com.blitzy.carddemo.tests.golden.CoUsr00CGoldenTest` is
annotated `@Disabled` until `stdout.txt` and `bms_output.txt` are
replaced with captured COBOL artifacts per `java/MIGRATION_NOTES.md`
§1.6. Placeholders suffice for harness-scaffolding but NOT for byte-level parity assertions.

## Phase 1: COBOL Source — PROGRAM-ID and Working-Storage Variables

PROGRAM-ID is `COUSR00C` `[app/cbl/COUSR00C.cbl:L23]`. The complete
inventory of working-storage variables referenced by the test scenarios:

| COBOL Name | PIC | Source Line | Java Equivalent | Notes |
|---|---|---|---|---|
| `WS-PGMNAME` | `X(08) VALUE 'COUSR00C'` | `[app/cbl/COUSR00C.cbl:L36]` | `CoUsr00C.PGMNAME` constant | Identifies emitter program in commarea on XCTL |
| `WS-TRANID` | `X(04) VALUE 'CU00'` | `[app/cbl/COUSR00C.cbl:L37]` | `CoUsr00C.TRANID` constant | CICS transaction ID; used on EXEC CICS RETURN |
| `WS-MESSAGE` | `X(80) VALUE SPACES` | `[app/cbl/COUSR00C.cbl:L38]` | local `String` in use-case | Routed to ERRMSGO in SEND-USRLST-SCREEN |
| `WS-USRSEC-FILE` | `X(08) VALUE 'USRSEC  '` | `[app/cbl/COUSR00C.cbl:L39]` | `UserSecurityRepository.DATASET_NAME` | Trailing 2-space padding preserved verbatim |
| `WS-ERR-FLG` (88 ERR-FLG-ON/OFF) | `X(01) VALUE 'N'` | `[app/cbl/COUSR00C.cbl:L40-L42]` | local `boolean errorFlag` | Sealed `Boolean`-equivalent — no third state |
| `WS-USER-SEC-EOF` (88 USER-SEC-EOF/USER-SEC-NOT-EOF) | `X(01) VALUE 'N'` | `[app/cbl/COUSR00C.cbl:L43-L45]` | local `boolean userSecEof` | True when READNEXT/READPREV returns ENDFILE |
| `WS-SEND-ERASE-FLG` (88 SEND-ERASE-YES/NO) | `X(01) VALUE 'Y'` | `[app/cbl/COUSR00C.cbl:L46-L48]` | local `boolean sendErase` | Controls ERASE clause on SEND MAP |
| `WS-RESP-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COUSR00C.cbl:L50]` | sealed `FileStatus` hierarchy | Captured from CICS RESP per call |
| `WS-REAS-CD` | `S9(09) COMP VALUE ZEROS` | `[app/cbl/COUSR00C.cbl:L51]` | included in `FileStatus.IoError(int code, int reason, ...)` | RESP2 reason code |
| `WS-REC-COUNT` | `S9(04) COMP VALUE ZEROS` | `[app/cbl/COUSR00C.cbl:L52]` | unused in current flow (declared but not driven) | Dead-code candidate; preserve faithfully |
| `WS-IDX` | `S9(04) COMP VALUE ZEROS` | `[app/cbl/COUSR00C.cbl:L53]` | local `int idx` (1..10) | Pagination row index |
| `WS-PAGE-NUM` | `S9(04) COMP VALUE ZEROS` | `[app/cbl/COUSR00C.cbl:L54]` | local `int pageNum` | Distinct from `CDEMO-CU00-PAGE-NUM` (commarea) |
| `WS-USER-DATA` (`USER-REC OCCURS 10`) | group | `[app/cbl/COUSR00C.cbl:L56-L64]` | `List<UserRow>` of size 10 | Sub-fields: `USER-SEL` X(01), `USER-ID` X(08), `USER-NAME` X(25), `USER-TYPE` X(08) |

The data-name `USER-NAME` PIC X(25) `[app/cbl/COUSR00C.cbl:L62]` is
internal scratch — the displayed names come from `SEC-USR-FNAME` and
`SEC-USR-LNAME` directly per POPULATE-USER-DATA (Phase 5), NOT from
`WS-USER-DATA`. The asymmetry is preserved (flagged in MIGRATION_NOTES.md).

## Phase 2: Commarea Sub-Structure (CDEMO-CU00-INFO)

The base `CARDDEMO-COMMAREA` is defined in `app/cpy/COCOM01Y.cpy`
`[app/cpy/COCOM01Y.cpy:L19-L44]`. COUSR00C extends it inline with the
`CDEMO-CU00-INFO` sub-record at `[app/cbl/COUSR00C.cbl:L67-L75]` (after
the `COPY COCOM01Y.` directive at `[app/cbl/COUSR00C.cbl:L66]`). The
fields and their cross-program peers:

| Field | PIC | Source | Java Equivalent | Purpose |
|---|---|---|---|---|
| `CDEMO-CU00-USRID-FIRST` | `X(08)` | `[app/cbl/COUSR00C.cbl:L68]` | `CdemoCu00Info.usridFirst` | Page-first user-id; written by POPULATE-USER-DATA WHEN 1; used by PF7 backward STARTBR positioning |
| `CDEMO-CU00-USRID-LAST` | `X(08)` | `[app/cbl/COUSR00C.cbl:L69]` | `CdemoCu00Info.usridLast` | Page-last user-id; written by POPULATE-USER-DATA WHEN 10; used by PF8 forward STARTBR positioning |
| `CDEMO-CU00-PAGE-NUM` | `9(08)` | `[app/cbl/COUSR00C.cbl:L70]` | `CdemoCu00Info.pageNum` | Current page number; displayed in PAGENUMO |
| `CDEMO-CU00-NEXT-PAGE-FLG` (88 NEXT-PAGE-YES/NO) | `X(01) VALUE 'N'` | `[app/cbl/COUSR00C.cbl:L71-L73]` | `CdemoCu00Info.nextPage` (sealed) | 'Y' when forward read found ≥ 11 records in current direction |
| `CDEMO-CU00-USR-SEL-FLG` | `X(01)` | `[app/cbl/COUSR00C.cbl:L74]` | `CdemoCu00Info.userSelFlag` | Captured selection action ('U'/'u'/'D'/'d') |
| `CDEMO-CU00-USR-SELECTED` | `X(08)` | `[app/cbl/COUSR00C.cbl:L75]` | `CdemoCu00Info.userSelected` | User-id targeted for U/D action |

Cross-program commarea overlays — when COUSR00C XCTLs to a sibling, the
commarea includes per-target sub-records that the sibling consumes:
`CDEMO-CU02-INFO` (defined in `app/cbl/COUSR02C.cbl` after `COPY COCOM01Y`)
includes `CDEMO-CU02-USR-SELECTED PIC X(08)` — populated by COUSR00C
PROCESS-ENTER-KEY when selection = 'U'/'u' then XCTL to COUSR02C
`[app/cbl/COUSR00C.cbl:L196-L199]`. `CDEMO-CU03-INFO` (defined in
`app/cbl/COUSR03C.cbl`) includes `CDEMO-CU03-USR-SELECTED PIC X(08)` —
populated when selection = 'D'/'d' then XCTL to COUSR03C
`[app/cbl/COUSR00C.cbl:L206-L209]`.

The base commarea fields `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`,
`CDEMO-TO-PROGRAM`, and `CDEMO-PGM-CONTEXT` (88 CDEMO-PGM-ENTER /
CDEMO-PGM-REENTER) defined in `app/cpy/COCOM01Y.cpy` are populated on
every outbound XCTL with `WS-TRANID`, `WS-PGMNAME`, the target program
literal, and `0` (re-enter the destination as a fresh first-pass)
`[app/cbl/COUSR00C.cbl:L192-L199,L202-L209,L511-L516]`.

## Phase 3: MAIN-PARA Dispatch Logic

MAIN-PARA `[app/cbl/COUSR00C.cbl:L98-L144]` performs three steps:
initialize flags `[app/cbl/COUSR00C.cbl:L100-L108]`; decide first-time
vs. re-enter and dispatch `[app/cbl/COUSR00C.cbl:L110-L139]`;
EXEC CICS RETURN with TRANSID = `WS-TRANID` and the populated commarea
`[app/cbl/COUSR00C.cbl:L141-L144]`.

The dispatch step has three outer branches: (a)
`IF EIBCALEN = 0` `[app/cbl/COUSR00C.cbl:L110-L112]` → first-time entry
without commarea (user has not signed on); XCTL to `'COSGN00C'`. (b)
`ELSE IF NOT CDEMO-PGM-REENTER` `[app/cbl/COUSR00C.cbl:L114-L119]` →
first-pass via re-entry from a sibling (e.g., admin-menu selection):
copy DFHCOMMAREA, set the re-enter flag, blank the output map, perform
PROCESS-ENTER-KEY (which proceeds to PROCESS-PAGE-FORWARD with
`CDEMO-CU00-PAGE-NUM = 0`), then SEND screen. (c) `ELSE`
`[app/cbl/COUSR00C.cbl:L120-L138]` → re-entry (pseudo-conversational
continuation): RECEIVE the screen, then EVALUATE EIBAID — the
five-branch dispatch:

| EIBAID | Behavior | Java Equivalent | Source |
|---|---|---|---|
| `DFHENTER` | PERFORM PROCESS-ENTER-KEY | `case AidKey.Enter -> processEnterKey()` | `[app/cbl/COUSR00C.cbl:L123-L124]` |
| `DFHPF3` | MOVE `'COADM01C'` TO CDEMO-TO-PROGRAM; PERFORM RETURN-TO-PREV-SCREEN | `case AidKey.PfKey03 -> returnToPrevScreen("COADM01C")` | `[app/cbl/COUSR00C.cbl:L125-L127]` |
| `DFHPF7` | PERFORM PROCESS-PF7-KEY (backward) | `case AidKey.PfKey07 -> processPf7Key()` | `[app/cbl/COUSR00C.cbl:L128-L129]` |
| `DFHPF8` | PERFORM PROCESS-PF8-KEY (forward) | `case AidKey.PfKey08 -> processPf8Key()` | `[app/cbl/COUSR00C.cbl:L130-L131]` |
| `WHEN OTHER` | MOVE `CCDA-MSG-INVALID-KEY` TO WS-MESSAGE; SEND screen | `case AidKey other -> messageInvalidKey()` (sealed exhaustiveness fills remaining permits) | `[app/cbl/COUSR00C.cbl:L132-L136]` |

CRITICAL: COUSR00C handles exactly 4 NAMED AID keys plus a catch-all
WHEN OTHER branch. Specifically:

- NO PF4 handling. Contrast COUSR03C, which handles PF4 (CLEAR-CURRENT-SCREEN).
- NO PF5 handling. Contrast COUSR02C (UPDATE) and COUSR03C (DELETE),
  which handle PF5 (commit-action).
- NO PF12 handling. Contrast COUSR03C, which handles PF12 (XCTL to admin menu).
- PF3 in COUSR00C unconditionally targets `'COADM01C'` (admin menu)
  — NOT `CDEMO-FROM-PROGRAM`. This is verified by inspection of
  `[app/cbl/COUSR00C.cbl:L126]`. Contrast COUSR03C, which XCTLs back
  to `CDEMO-FROM-PROGRAM` (the originating menu).

The Java translation MUST use a pattern-matching `switch` on a sealed
`AidKey` hierarchy `[app/cpy/CVCRD01Y.cpy:§CCARD-AID]` with NO `default`
branch (per AAP §0.7.4). The compiler-enforced exhaustiveness guarantees
that every additional AID permit reachable in the sealed hierarchy is
handled — typically routing all unhandled permits through the WHEN
OTHER catch-all path that sets `CCDA-MSG-INVALID-KEY`.

## Phase 4: PROCESS-ENTER-KEY — Row Selection Dispatch

PROCESS-ENTER-KEY `[app/cbl/COUSR00C.cbl:L149-L232]` performs: (1) scan
rows 1..10 for a non-blank `SEL000NI` and capture the selection flag
and user-id via an EVALUATE TRUE block of 11 WHEN clauses
`[app/cbl/COUSR00C.cbl:L151-L185]`; (2) if a selection was captured,
dispatch by selection-flag value via inner EVALUATE
`[app/cbl/COUSR00C.cbl:L187-L216]`; (3) regardless of selection, set
up the positioning key (USRIDIN search input or LOW-VALUES when blank),
reset `CDEMO-CU00-PAGE-NUM` to 0, and PROCESS-PAGE-FORWARD
`[app/cbl/COUSR00C.cbl:L218-L232]`.

The inner-EVALUATE dispatch by selection flag:

| Selection Flag | Behavior | Source |
|---|---|---|
| `'U'` or `'u'` | MOVE `'COUSR02C'` TO CDEMO-TO-PROGRAM; XCTL | `[app/cbl/COUSR00C.cbl:L190-L199]` |
| `'D'` or `'d'` | MOVE `'COUSR03C'` TO CDEMO-TO-PROGRAM; XCTL | `[app/cbl/COUSR00C.cbl:L200-L209]` |
| WHEN OTHER (any other non-space character) | MOVE `'Invalid selection. Valid values are U and D'` TO WS-MESSAGE; MOVE -1 TO USRIDINL | `[app/cbl/COUSR00C.cbl:L210-L214]` |

The verbatim selection-validation message at
`[app/cbl/COUSR00C.cbl:L211-L213]` is:
`'Invalid selection. Valid values are U and D'`

The case-insensitive comparison (both `u`/`d` and `U`/`D` accepted) is
realized by adjacent WHEN clauses per case
(`WHEN 'U' WHEN 'u'` and `WHEN 'D' WHEN 'd'`) within the inner EVALUATE
`[app/cbl/COUSR00C.cbl:L190-L201]`. The Java translation MUST use a
sealed enum-like `SelectionAction { U, D, NONE, INVALID }` (or
equivalent sealed hierarchy) with NO `default` branch — relying on
pattern-matching exhaustiveness per AAP §0.7.4.

USRIDIN-search asymmetry: when the user types a value into the row-6
search field, the value is moved to `SEC-USR-ID` to seed the next
STARTBR positioning `[app/cbl/COUSR00C.cbl:L218-L222]`. When blank,
`LOW-VALUES` is moved to `SEC-USR-ID` and (combined with the
commented-out GTEQ in STARTBR) causes positioning before the
lowest-keyed record. This is Scenario 11.

## Phase 5: PROCESS-PF7-KEY and PROCESS-PF8-KEY — Pagination

PROCESS-PF7-KEY `[app/cbl/COUSR00C.cbl:L237-L255]`: set `SEC-USR-ID` to
`CDEMO-CU00-USRID-FIRST` (or LOW-VALUES) `[app/cbl/COUSR00C.cbl:L239-L243]`;
`IF CDEMO-CU00-PAGE-NUM > 1` `[app/cbl/COUSR00C.cbl:L248-L249]` →
PROCESS-PAGE-BACKWARD; ELSE MOVE
`'You are already at the top of the page...'` TO WS-MESSAGE
`[app/cbl/COUSR00C.cbl:L251-L252]` and SEND WITHOUT ERASE
`[app/cbl/COUSR00C.cbl:L253-L254]`.

PROCESS-PF8-KEY `[app/cbl/COUSR00C.cbl:L260-L277]`: set `SEC-USR-ID` to
`CDEMO-CU00-USRID-LAST` (or HIGH-VALUES) `[app/cbl/COUSR00C.cbl:L262-L266]`;
`IF NEXT-PAGE-YES` `[app/cbl/COUSR00C.cbl:L270-L271]` →
PROCESS-PAGE-FORWARD; ELSE MOVE
`'You are already at the bottom of the page...'` TO WS-MESSAGE
`[app/cbl/COUSR00C.cbl:L273-L274]` and SEND WITHOUT ERASE
`[app/cbl/COUSR00C.cbl:L275-L276]`.

PROCESS-PAGE-FORWARD `[app/cbl/COUSR00C.cbl:L282-L331]` performs:
STARTBR `[app/cbl/COUSR00C.cbl:L284]`; conditional initial READNEXT to
skip the positioning record when EIBAID is neither DFHENTER nor PF7
nor PF3 `[app/cbl/COUSR00C.cbl:L288-L290]`; INITIALIZE-USER-DATA loop
over WS-IDX 1..10 `[app/cbl/COUSR00C.cbl:L292-L296]`; READNEXT +
POPULATE-USER-DATA loop until WS-IDX ≥ 11 OR USER-SEC-EOF OR ERR-FLG-ON
`[app/cbl/COUSR00C.cbl:L300-L306]`; trailing READNEXT to test for next
page and set NEXT-PAGE-YES/NO accordingly
`[app/cbl/COUSR00C.cbl:L308-L323]`; ENDBR
`[app/cbl/COUSR00C.cbl:L325]`; SEND `[app/cbl/COUSR00C.cbl:L327-L329]`.

PROCESS-PAGE-BACKWARD `[app/cbl/COUSR00C.cbl:L336-L379]` performs the
mirror with READPREV: STARTBR, conditional initial READPREV when
EIBAID is neither DFHENTER nor PF8 `[app/cbl/COUSR00C.cbl:L342-L344]`,
INITIALIZE-USER-DATA loop, descending READPREV loop with WS-IDX from
10 down `[app/cbl/COUSR00C.cbl:L352-L360]`, a trailing READPREV to
test for prior page and adjust page number
`[app/cbl/COUSR00C.cbl:L362-L372]`, ENDBR, and SEND.

POPULATE-USER-DATA `[app/cbl/COUSR00C.cbl:L384-L441]` is a 10-WHEN
EVALUATE that maps the current `SEC-USER-DATA` record into one of the
10 BMS output rows. Two cross-cuts: WHEN 1 also stores `SEC-USR-ID`
into `CDEMO-CU00-USRID-FIRST` `[app/cbl/COUSR00C.cbl:L387-L392]`;
WHEN 10 also stores it into `CDEMO-CU00-USRID-LAST`
`[app/cbl/COUSR00C.cbl:L433-L438]`. INITIALIZE-USER-DATA
`[app/cbl/COUSR00C.cbl:L446-L501]` mirrors the structure, moving
SPACES into each USRID/FNAME/LNAME/UTYPE output field for the indexed
row. Java: a single `for (int idx = 1; idx <= 10; idx++)` loop
suffices for both, indexing into the `CoUsr00Output` row list. NO
virtual-thread parallelization per AAP §0.6.6 — row order is observable.

## Phase 6: STARTBR-USER-SEC-FILE — VSAM Browse Start

STARTBR-USER-SEC-FILE `[app/cbl/COUSR00C.cbl:L586-L614]` issues
`EXEC CICS STARTBR DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)
KEYLENGTH(LENGTH OF SEC-USR-ID)` with RESP/RESP2 captured. The GTEQ
clause is COMMENTED OUT at `[app/cbl/COUSR00C.cbl:L592]` — meaning
STARTBR requires the key to exist EXACTLY (not GTEQ-positioning).
This affects USRIDIN search behavior (Scenario 11 in the
`input_scenario.txt`).

Three response branches `[app/cbl/COUSR00C.cbl:L597-L614]`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | Action | Source |
|---|---|---|---|
| `DFHRESP(NORMAL)` | (unchanged) | CONTINUE — positioned for READ | `[app/cbl/COUSR00C.cbl:L598-L599]` |
| `DFHRESP(NOTFND)` | `'You are at the top of the page...'` | SET USER-SEC-EOF; MOVE -1 to USRIDINL; SEND screen | `[app/cbl/COUSR00C.cbl:L600-L606]` |
| WHEN OTHER | `'Unable to lookup User...'` | DISPLAY RESP/REAS; SET WS-ERR-FLG; SEND screen | `[app/cbl/COUSR00C.cbl:L607-L613]` |

The DISPLAY at `[app/cbl/COUSR00C.cbl:L608]` is one of three
diagnostic emissions to the SYSOUT stream — all on WHEN OTHER error
paths. None of the 12 scenarios exercises this path under the
synthesized `usrsec.txt`, so expected `stdout.txt` is empty.

Java: `UserSecurityRepository.startBrowse(ridfld)` returns
`Optional<BrowsePosition>` (empty on NOTFND) or throws a sealed
exception per AAP §0.4.1 (`FileStatus` hierarchy). All file I/O uses
`java.nio.file` per AAP §0.6.5.

## Phase 7: READNEXT-USER-SEC-FILE — VSAM Forward Read

READNEXT-USER-SEC-FILE `[app/cbl/COUSR00C.cbl:L619-L648]` issues
`EXEC CICS READNEXT DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
LENGTH(LENGTH OF SEC-USER-DATA) RIDFLD(SEC-USR-ID)` with RESP/RESP2
captured. Three response branches `[app/cbl/COUSR00C.cbl:L631-L648]`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | Action | Source |
|---|---|---|---|
| `DFHRESP(NORMAL)` | (unchanged) | CONTINUE — SEC-USER-DATA populated | `[app/cbl/COUSR00C.cbl:L632-L633]` |
| `DFHRESP(ENDFILE)` | `'You have reached the bottom of the page...'` | SET USER-SEC-EOF; MOVE -1 to USRIDINL; SEND screen | `[app/cbl/COUSR00C.cbl:L634-L640]` |
| WHEN OTHER | `'Unable to lookup User...'` | DISPLAY RESP/REAS at `[app/cbl/COUSR00C.cbl:L642]`; SET WS-ERR-FLG; SEND screen | `[app/cbl/COUSR00C.cbl:L641-L647]` |

Java: `UserSecurityRepository.readNext()` returns `Optional<SecUserData>`
(empty on ENDFILE); the use-case translates the result into the same
observable WS-USER-SEC-EOF state machine.

## Phase 8: READPREV-USER-SEC-FILE — VSAM Backward Read

READPREV-USER-SEC-FILE `[app/cbl/COUSR00C.cbl:L653-L682]` issues
`EXEC CICS READPREV DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
LENGTH(LENGTH OF SEC-USER-DATA) RIDFLD(SEC-USR-ID)` with RESP/RESP2
captured. Three response branches `[app/cbl/COUSR00C.cbl:L665-L682]`:

| WS-RESP-CD | WS-MESSAGE (verbatim) | Action | Source |
|---|---|---|---|
| `DFHRESP(NORMAL)` | (unchanged) | CONTINUE — SEC-USER-DATA populated | `[app/cbl/COUSR00C.cbl:L666-L667]` |
| `DFHRESP(ENDFILE)` | `'You have reached the top of the page...'` | SET USER-SEC-EOF; MOVE -1 to USRIDINL; SEND screen | `[app/cbl/COUSR00C.cbl:L668-L674]` |
| WHEN OTHER | `'Unable to lookup User...'` | DISPLAY RESP/REAS at `[app/cbl/COUSR00C.cbl:L676]`; SET WS-ERR-FLG; SEND screen | `[app/cbl/COUSR00C.cbl:L675-L681]` |

Java: `UserSecurityRepository.readPrev()` returns
`Optional<SecUserData>` (empty on ENDFILE). The READPREV ENDFILE
message is `'You have reached the top of the page...'` — the symmetric
pair of READNEXT's `'You have reached the bottom of the page...'`. The
Minimal Change Clause (AAP §0.7.1) forbids any rewording.

## Phase 9: SEND/RECEIVE/POPULATE-HEADER/ENDBR Paragraphs

SEND-USRLST-SCREEN `[app/cbl/COUSR00C.cbl:L522-L544]`: PERFORM
POPULATE-HEADER-INFO; MOVE WS-MESSAGE TO ERRMSGO; if SEND-ERASE-YES
`[app/cbl/COUSR00C.cbl:L528]` → SEND MAP('COUSR0A') MAPSET('COUSR00')
FROM(COUSR0AO) ERASE CURSOR `[app/cbl/COUSR00C.cbl:L529-L535]`; else
(boundary-message resend without clearing the screen) → SEND without
ERASE `[app/cbl/COUSR00C.cbl:L536-L544]`.

RECEIVE-USRLST-SCREEN `[app/cbl/COUSR00C.cbl:L549-L557]` issues a single
`EXEC CICS RECEIVE MAP('COUSR0A') MAPSET('COUSR00') INTO(COUSR0AI)
RESP(WS-RESP-CD) RESP2(WS-REAS-CD)`. No EVALUATE on the response — RESP
is captured but not branched upon (RECEIVE is assumed successful).

POPULATE-HEADER-INFO `[app/cbl/COUSR00C.cbl:L562-L581]` reads
`FUNCTION CURRENT-DATE` into WS-CURDATE-DATA, then assigns header
fields: TITLE01O / TITLE02O from `app/cpy/COTTL01Y.cpy`; TRNNAMEO from
WS-TRANID; PGMNAMEO from WS-PGMNAME; date/time fields formatted as
`mm/dd/yy` (CURDATEO) and `hh:mm:ss` (CURTIMEO) via substring moves at
`[app/cbl/COUSR00C.cbl:L571-L581]`.

ENDBR-USER-SEC-FILE `[app/cbl/COUSR00C.cbl:L687-L691]` issues
`EXEC CICS ENDBR DATASET(WS-USRSEC-FILE)` — fire-and-forget (no RESP
capture). The Java translation MAY log an I/O failure on ENDBR but
MUST NOT change observable behavior.

Java translation: header formatting uses `java.time.LocalDateTime` +
`DateTimeFormatter.ofPattern("MM/dd/yy")` / `("HH:mm:ss")` per AAP
§0.6.4. The clock MUST be INJECTED via constructor or
`ScopedValue<Clock>` per AAP §0.6.6 (NOT `LocalDateTime.now()`) so
`bms_output.txt` parity holds across runs.

## Phase 10: Required Test Scenarios

| # | Scenario | AID | COBOL Source Path | Expected Output |
|---|---|---|---|---|
| 1 | Initial display — admin signed on, first invocation with non-zero EIBCALEN | (re-enter via prior XCTL) | `[app/cbl/COUSR00C.cbl:L114-L119]` triggers PROCESS-ENTER-KEY then SEND | Page 1 with the first 10 USRSEC records; PAGENUMO = `'00000001'`; ERRMSG empty |
| 2 | PF8 forward to page 2 | DFHPF8 | `[app/cbl/COUSR00C.cbl:L270-L271]` → PROCESS-PAGE-FORWARD `[app/cbl/COUSR00C.cbl:L282]` | Page 2 with next 10 USRSEC records; PAGENUMO = `'00000002'` |
| 3 | PF8 forward to page 3 (partial: 2 of 22 records remain) | DFHPF8 | `[app/cbl/COUSR00C.cbl:L282-L323]`; readnext returns ENDFILE during the trailing test-read at L311 | Rows 1-2 populated with the final 2 records; rows 3-10 blank; PAGENUMO = `'00000003'`; ERRMSG = `'You have reached the bottom of the page...'` |
| 4 | PF8 at last page (CDEMO-CU00-NEXT-PAGE-FLG = 'N') | DFHPF8 | `[app/cbl/COUSR00C.cbl:L272-L276]` boundary path | Screen content unchanged; ERRMSG = `'You are already at the bottom of the page...'`; no STARTBR |
| 5 | PF7 backward to page 2 | DFHPF7 | `[app/cbl/COUSR00C.cbl:L248-L249]` → PROCESS-PAGE-BACKWARD `[app/cbl/COUSR00C.cbl:L336]` | Page 2 redisplayed |
| 6 | PF7 backward to page 1 | DFHPF7 | `[app/cbl/COUSR00C.cbl:L336-L379]`; READPREV returns ENDFILE on the trailing test-read | Page 1 redisplayed; ERRMSG may include `'You have reached the top of the page...'` from the trailing READPREV at L671-L672 |
| 7 | PF7 at page 1 (top boundary, `CDEMO-CU00-PAGE-NUM = 1`) | DFHPF7 | `[app/cbl/COUSR00C.cbl:L250-L254]` boundary path | Screen content unchanged; ERRMSG = `'You are already at the top of the page...'` |
| 8 | ENTER with 'U' selection on a populated row | DFHENTER | `[app/cbl/COUSR00C.cbl:L190-L199]` → XCTL to COUSR02C | XCTL outbound; commarea CDEMO-CU02-USR-SELECTED populated; USRSEC unchanged |
| 9 | ENTER with 'D' selection on a populated row | DFHENTER | `[app/cbl/COUSR00C.cbl:L200-L209]` → XCTL to COUSR03C | XCTL outbound; commarea CDEMO-CU03-USR-SELECTED populated; USRSEC unchanged |
| 10 | ENTER with an invalid selection character (e.g., 'X') in a SEL000N field | DFHENTER | `[app/cbl/COUSR00C.cbl:L210-L214]` WHEN OTHER in inner-EVALUATE | ERRMSG = `'Invalid selection. Valid values are U and D'`; screen redisplayed |
| 11 | USRIDIN search populated + ENTER | DFHENTER | `[app/cbl/COUSR00C.cbl:L218-L228]` re-positions, then PROCESS-PAGE-FORWARD | Page repositioned starting at the search key (or first key ≥ when GTEQ is enabled; here EXACT match required because GTEQ is commented out at L592) |
| 12 | PF3 — back to admin menu | DFHPF3 | `[app/cbl/COUSR00C.cbl:L125-L127]` → XCTL to `'COADM01C'` | XCTL outbound to COADM01C — NOT to COSGN00C; USRSEC unchanged |

Each scenario MUST be re-validated against the captured BMS output once
`bms_output.txt` is replaced with a real capture per
`java/MIGRATION_NOTES.md` section 1.6. Until then, the harness asserts
only state-machine invariants (e.g., XCTL emitted with correct
PROGRAM and COMMAREA contents in scenarios 8/9/12, ERRMSG verbatim in
scenarios 3/4/6/7/10).

## Phase 11: BMS Map COUSR0A Layout — 24 × 80 Screen

The COUSR00 BMS mapset begins at `[app/bms/COUSR00.bms:L19]` with
`DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT
STORAGE=AUTO TIOAPFX=YES`. The map COUSR0A is the only map in the
mapset, declared at `[app/bms/COUSR00.bms:L26-L28]` with
`COLUMN=1 LINE=1 SIZE=(24,80)`. The symbolic input/output copybook is
`app/cpy-bms/COUSR00.CPY` (record 01 COUSR0AI for input, 01 COUSR0AO
for output) `[app/cpy-bms/COUSR00.CPY:§01-COUSR0AI,§01-COUSR0AO]`.

| Field Name | Pos (row, col) | Length | Attribute | Color | Direction | BMS Source |
|---|---|---|---|---|---|---|
| Row 1 labels `'Tran:'` (1,1) and `'Date:'` (1,65) | (1, 1) / (1, 65) | 5 / 5 | ASKIP, NORM | BLUE | constants | `[app/bms/COUSR00.bms:L29-L33,L42-L46]` |
| `TRNNAME` / `TITLE01` / `CURDATE` (INITIAL=`'mm/dd/yy'`) | (1, 7) / (1, 21) / (1, 71) | 4 / 40 / 8 | ASKIP, FSET, NORM | BLUE / YELLOW / BLUE | output | `[app/bms/COUSR00.bms:L34-L51]` |
| Row 2 labels `'Prog:'` (2,1) and `'Time:'` (2,65) | (2, 1) / (2, 65) | 5 / 5 | ASKIP, NORM | BLUE | constants | `[app/bms/COUSR00.bms:L52-L56,L65-L69]` |
| `PGMNAME` / `TITLE02` / `CURTIME` (INITIAL=`'hh:mm:ss'`) | (2, 7) / (2, 21) / (2, 71) | 8 / 40 / 8 | ASKIP, FSET, NORM | BLUE / YELLOW / BLUE | output | `[app/bms/COUSR00.bms:L57-L74]` |
| Row 4: `'List Users'` (4,35) BRT NEUTRAL; `'Page:'` (4,65) BRT TURQUOISE; `PAGENUM` (4,71) | — | 10 / 5 / 8 | ASKIP, BRT or NORM | NEUTRAL / TURQUOISE / BLUE | constants + output | `[app/bms/COUSR00.bms:L75-L89]` |
| Row 6: `'Search User ID:'` (6,5) and `USRIDIN` (6,21) | — | 15 / 8 | ASKIP NORM / FSET NORM UNPROT HILIGHT=UNDERLINE | TURQUOISE / GREEN | constant + INPUT | `[app/bms/COUSR00.bms:L90-L99]` |
| Row 8 column headers: `'Sel'` (8,5) len 3; `'User ID '` (8,12) len 8; `'     First Name     '` (8,24) len 20; `'     Last Name      '` (8,48) len 20; `'Type'` (8,72) len 4 | — | varies | ASKIP, NORM | NEUTRAL | constants | `[app/bms/COUSR00.bms:L103-L127]` |
| Row 9 dash separator (mirrors row 8 columns) | (9, ...) | matching row 8 | ASKIP, NORM | NEUTRAL | constant | `[app/bms/COUSR00.bms:L128-L152]` |
| `SEL0001` (10,6) | row 10 col 6 | 1 | FSET, NORM, UNPROT, HILIGHT=UNDERLINE | GREEN | INPUT | `[app/bms/COUSR00.bms:L153-L158]` |
| `USRID01` / `FNAME01` / `LNAME01` / `UTYPE01` | (10, 12 / 24 / 48 / 73) | 8 / 20 / 20 / 1 | ASKIP, FSET, NORM | BLUE | output | `[app/bms/COUSR00.bms:L162-L181]` |
| `SEL0002`..`SEL0009` and corresponding `USRID0N`/`FNAME0N`/`LNAME0N`/`UTYPE0N` for N in 2..9 | rows 11..18, columns identical to row 10 | same | same | same | per-pattern | `[app/bms/COUSR00.bms:L182-L422]` |
| `SEL0010` (19,6) and `USRID10`/`FNAME10`/`LNAME10`/`UTYPE10` | (19, 6 / 12 / 24 / 48 / 73) | 1 / 8 / 20 / 20 / 1 | as row 10 | as row 10 | INPUT / output | `[app/bms/COUSR00.bms:L423-L442]` |
| Row 21 help text INITIAL=`Type 'U' to Update or 'D' to Delete a User from the list` | (21, 12) | 56 | ASKIP, BRT | NEUTRAL | constant | `[app/bms/COUSR00.bms:L443-L448]` |
| `ERRMSG` | (23, 1) | 78 | ASKIP, BRT, FSET | RED | output | `[app/bms/COUSR00.bms:L449-L452]` |
| Row 24 footer INITIAL=`ENTER=Continue  F3=Back  F7=Backward  F8=Forward` | (24, 1) | 48 | ASKIP, NORM | YELLOW | constant | `[app/bms/COUSR00.bms:L453-L458]` |

**CRITICAL STRUCTURAL INVARIANT:** COUSR0A has **NO `PASSWD` field
anywhere on the map**. This is a verified absence — there is no field
at any (row, col) position whose name is `PASSWD` or any synonym. The
BMS source from `[app/bms/COUSR00.bms:L19-L460]` contains zero
`DFHMDF` entries with a `PASSWD`-equivalent name. Contrast: COUSR1A
(used by COUSR01C, Add) and COUSR2A (used by COUSR02C, Update) both
have `PASSWD` fields with `DRK` (dark/non-display) attribute that
produce blank output for the password; COUSR3A (used by COUSR03C,
Delete) does not have a PASSWD field (matches COUSR00C). The Java
translation MUST verify this absence by static introspection of the
`CoUsr00Output` record's field set — the record MUST NOT contain any
field named or aliased to "password" in any form.

## Phase 12: Required Fixture Files in This Directory

This directory contains four fixture files plus this README. The test
class `CoUsr00CGoldenTest` consumes them through the base-class
helpers `resolveAuxiliaryInputPath()` (for `usrsec.txt`),
`resolveExpectedOutputPath()` (for `stdout.txt` and `bms_output.txt`),
and the scenario script via the `inputFile()` override.

1. **`input_scenario.txt`** (CREATED). 12-scenario harness directive
   script — one directive per line; comments begin with `#`. Documents
   the 12 sequential 3270 interactions enumerated in Phase 10.
   Source-grounded against COBOL line numbers in that table.
   Self-contained: validation does NOT require a COBOL run.

2. **`usrsec.txt`** (CREATED). Synthesized USRSEC fixture: 22
   `SEC-USER-DATA` records of 80 bytes each = 1760 bytes total
   (1 admin + 21 regular-user records), sorted ascending by
   `SEC-USR-ID` for VSAM KSDS browse semantics per
   `[app/cpy/CSUSR01Y.cpy:§SEC-USER-DATA]`. Plaintext passwords are
   preserved in storage (per AAP §0.1.3) but NEVER enumerated in
   `stdout.txt`, `bms_output.txt`, or this README (per AAP §0.7.2).
   Auxiliary input — the harness wires it into a tmp-dir and
   configures the file-backed `UserSecurityRepository` to read from
   it. COUSR00C is READ-ONLY so `usrsec.txt` is byte-identical
   pre- and post-run.

3. **`stdout.txt`** (placeholder CREATED). The captured stdout from
   the COBOL reference run. Expected content under the 12-scenario
   script is EMPTY because the COBOL `DISPLAY` statements at
   `[app/cbl/COUSR00C.cbl:L608,L642,L676]` are each guarded by
   `WHEN OTHER` (unexpected RESP) branches not exercised by the
   synthesized fixture. The NOTFND branch at
   `[app/cbl/COUSR00C.cbl:L600-L606]` does not DISPLAY either.
   Replaced by captured artifact per `java/MIGRATION_NOTES.md` §1.6.

4. **`bms_output.txt`** (placeholder CREATED). The captured
   concatenation of SEND MAP frames emitted by COUSR00C during the
   12-scenario run. Each frame is the 1920-byte (24 × 80) 3270 buffer
   image after one SEND MAP, plus a frame separator. Expected count is
   approximately 9-11 frames (the count depends on which scenarios
   emit a SEND before issuing an XCTL). Replaced per
   `java/MIGRATION_NOTES.md` §1.6.

`usrsec_after.txt` is NOT present because COUSR00C is READ-ONLY —
STARTBR/READNEXT/READPREV/ENDBR do not mutate the dataset. The post-run
state of USRSEC is byte-identical to the input state. The harness
asserts this invariant via `ASSERT_USRSEC_UNCHANGED` directives in
`input_scenario.txt` (a `Files.mismatch(...)` check against the input
file).

## Phase 13: Structural Invariants

### 13.1 BMS Map Invariants

- COUSR0A has exactly 10 selection rows (`SEL0001` through `SEL0010`).
- `USRIDIN` is the ONLY input field beyond the 10 `SEL000N` fields.
- `ERRMSG` is at row (23, 1) with length 78.
- NO `PASSWD` field exists anywhere on the map (verified absence by
  inspection of `[app/bms/COUSR00.bms:L19-L460]`).
- Footer text at row 24 is exactly:
  `ENTER=Continue  F3=Back  F7=Backward  F8=Forward`.
- Total displayed user-data rows per page = 10 (no scrolling per page).
- Total fields with `UNPROT` attribute = 11 (`USRIDIN` +
  `SEL0001`..`SEL0010`).
- The map uses `CTRL=(ALARM,FREEKB)` `[app/bms/COUSR00.bms:L19]` —
  preserved by faithful translation (Java emits the same buffer).

### 13.2 USRSEC File Invariants (CRITICAL — READ-ONLY)

- Per AAP §0.7.1, `usrsec.txt` is the INITIAL state for every scenario.
- COUSR00C does NOT modify USRSEC. It issues only `STARTBR`,
  `READNEXT`, `READPREV`, and `ENDBR` operations — NO `WRITE`, NO
  `REWRITE`, NO `DELETE`. Verified by grep of `[app/cbl/COUSR00C.cbl]`
  for those CICS verbs (zero matches).
- Therefore, if a `usrsec_after.txt` snapshot were taken post-run, it
  MUST be byte-identical to `usrsec.txt`.
- The test class does NOT declare a `usrsec_after.txt` ExpectedOutput.
  Only `stdout.txt` and `bms_output.txt` appear in `expectedOutputs()`.
- Sort order: ascending by `SEC-USR-ID` (VSAM KSDS browse sequence).
- Record length: 80 bytes per `SEC-USER-DATA` per
  `[app/cpy/CSUSR01Y.cpy:§SEC-USER-DATA]`.
- File length: 22 × 80 = 1760 bytes (no separators, no EOF marker).
- Plaintext password values are PRESENT in `usrsec.txt` per AAP §0.1.3
  but MUST NEVER appear in any other artifact per AAP §0.7.2.

### 13.3 Java Mapping Invariants

- Plaintext password preserved in domain record `SecUserData` (COBOL
  `PIC X(08)` → Java `String` of length 8) per
  `[app/cpy/CSUSR01Y.cpy:§SEC-USR-PWD]` and AAP §0.1.3.
- Password NEVER appears in any SLF4J log (AAP §0.7.2).
- Password NEVER appears in `bms_output.txt` because the BMS map
  itself has NO PASSWD field (structural invariant, Phase 13.1).
- READ-ONLY browse via `UserSecurityRepository.startBrowse(ridfld)` +
  `readNext()` / `readPrev()` / `endBrowse()`.
- Browse iteration MUST NOT be virtual-thread-parallelized per AAP
  §0.6.6 — ordering is observable; reordering breaks parity.
- `ScopedValue` carries per-request context (user-id, run-id, clock) in
  lieu of `ThreadLocal` per AAP §0.6.6.
- Every `EVALUATE` translates to a pattern-matching `switch` with NO
  `default` branch per AAP §0.7.4. Exhaustiveness is guaranteed by
  sealing the discriminator (`AidKey`, `SelectionAction`, `FileStatus`).
- POPULATE-HEADER-INFO uses `java.time.LocalDateTime` +
  `DateTimeFormatter` per AAP §0.6.4. The clock is injected for test
  determinism (NOT `LocalDateTime.now()`).
- All file I/O uses `java.nio.file` (NOT `java.io.File`) per AAP §0.6.5.
- No framework container (no Spring) per AAP §0.6.12 — the use-case
  class receives the repository via constructor injection from a
  plain-Java composition root.

## Contrast Matrix: COUSR00C vs Sibling User-CRUD Programs

| Aspect | COUSR00C (List) | COUSR01C (Add) | COUSR02C (Update) | COUSR03C (Delete) |
|---|---|---|---|---|
| Transaction ID | CU00 | CU01 | CU02 | CU03 |
| File operation | STARTBR / READNEXT / READPREV / ENDBR (READ-ONLY) | WRITE | READ + REWRITE | READ + DELETE |
| `usrsec_after.txt` | IDENTICAL to usrsec.txt (no after-fixture in directory) | LONGER by 80 bytes | SAME byte length (in-place update) | SHORTER by 80 bytes |
| Editable BMS fields | 1 (USRIDIN search) + 10 SEL rows | 5 (USRIDIN, FNAME, LNAME, PASSWD-DRK, USRTYPE) | 5 (same five) | 1 (USRIDIN only) |
| PASSWD field on map | **ABSENT** | PRESENT with DRK | PRESENT with DRK | ABSENT |
| Pagination | **YES (10/page)** | NO | NO | NO |
| AID keys handled | 4 (ENTER, PF3, PF7, PF8) + WHEN OTHER | varies (ENTER, PF3, PF4) | 6 (ENTER, PF3, PF4, PF5, PF12, OTHER) | 5 (ENTER, PF3, PF4, PF5, PF12) + WHEN OTHER |
| PF3 destination | **COADM01C** (literal) | CDEMO-FROM-PROGRAM or COADM01C | CDEMO-FROM-PROGRAM or COADM01C | CDEMO-FROM-PROGRAM or COADM01C |
| Row selection XCTL | YES — 10 SEL rows route 'U'→COUSR02C, 'D'→COUSR03C | N/A | N/A | N/A |
| Auto-trigger on first pass | N/A (originator of selection chain) | N/A | When CDEMO-CU02-USR-SELECTED populated | When CDEMO-CU03-USR-SELECTED populated |

## Verbatim COBOL Message Catalog

The Java translation MUST emit these strings EXACTLY (Minimal Change
Clause, AAP §0.7.1). NEVER paraphrase, NEVER replace literal `...` with
Unicode ellipsis, NEVER alter punctuation or spacing.

| Message (verbatim) | COBOL Line | Triggering Condition |
|---|---|---|
| `'You are at the top of the page...'` | `[app/cbl/COUSR00C.cbl:L603-L604]` | STARTBR returns `DFHRESP(NOTFND)` (positioning key not found) |
| `'You have reached the bottom of the page...'` | `[app/cbl/COUSR00C.cbl:L637-L638]` | READNEXT returns `DFHRESP(ENDFILE)` |
| `'You have reached the top of the page...'` | `[app/cbl/COUSR00C.cbl:L671-L672]` | READPREV returns `DFHRESP(ENDFILE)` |
| `'You are already at the top of the page...'` | `[app/cbl/COUSR00C.cbl:L251-L252]` | PF7 pressed when `CDEMO-CU00-PAGE-NUM` = 1 |
| `'You are already at the bottom of the page...'` | `[app/cbl/COUSR00C.cbl:L273-L274]` | PF8 pressed when `CDEMO-CU00-NEXT-PAGE-FLG` = 'N' |
| `'Unable to lookup User...'` | `[app/cbl/COUSR00C.cbl:L610-L611,L644-L645,L678-L679]` | STARTBR / READNEXT / READPREV WHEN OTHER (unexpected RESP code) |
| `'Invalid selection. Valid values are U and D'` | `[app/cbl/COUSR00C.cbl:L211-L213]` | SEL000NI is neither blank/LOW-VALUES nor 'U'/'u'/'D'/'d' |
| `'Invalid key pressed. Please see below...         '` (CCDA-MSG-INVALID-KEY) | `[app/cpy/CSMSG01Y.cpy:L20-L21]` | WHEN OTHER AID key (PF1, PF4, PF5, etc.) in MAIN-PARA EIBAID EVALUATE |

The CCDA-MSG-INVALID-KEY value is a PIC X(50) constant whose literal
includes 9 trailing spaces to fill the 50-byte field after the literal
`'Invalid key pressed. Please see below...'` — the trailing spaces
ARE part of the verbatim string as it flows into WS-MESSAGE (X(80))
and is right-padded further to 80 bytes by COBOL MOVE semantics. The
Java translation MUST preserve the full 50-character constant exactly.

## Source Lineage

This contract derives from the following source files. Any change to
this contract MUST be accompanied by a change-of-truth audit against these.

- `app/cbl/COUSR00C.cbl` (695 lines) — COBOL source. Paragraph inventory:
  MAIN-PARA `[L98-L144]`, PROCESS-ENTER-KEY `[L149-L232]`, PROCESS-PF7-KEY
  `[L237-L255]`, PROCESS-PF8-KEY `[L260-L277]`, PROCESS-PAGE-FORWARD
  `[L282-L331]`, PROCESS-PAGE-BACKWARD `[L336-L379]`, POPULATE-USER-DATA
  `[L384-L441]`, INITIALIZE-USER-DATA `[L446-L501]`, RETURN-TO-PREV-SCREEN
  `[L506-L517]`, SEND-USRLST-SCREEN `[L522-L544]`, RECEIVE-USRLST-SCREEN
  `[L549-L557]`, POPULATE-HEADER-INFO `[L562-L581]`, STARTBR-USER-SEC-FILE
  `[L586-L614]`, READNEXT-USER-SEC-FILE `[L619-L648]`, READPREV-USER-SEC-FILE
  `[L653-L682]`, ENDBR-USER-SEC-FILE `[L687-L691]`.
- `app/bms/COUSR00.bms` (463 lines) — BMS map definition; field layout in Phase 11.
- `app/cpy-bms/COUSR00.CPY` (728 lines) — symbolic map copybook
  (01 COUSR0AI / 01 COUSR0AO). Used by COBOL via `COPY COUSR00.` at
  `[app/cbl/COUSR00C.cbl:L76]`. Java generates equivalent DTO records
  `CoUsr00Input` / `CoUsr00Output` field-for-field from
  `[app/cpy-bms/COUSR00.CPY:§COUSR0AI,§COUSR0AO]`.
- `app/cpy/CSUSR01Y.cpy` — 80-byte `SEC-USER-DATA` referenced by
  `usrsec.txt` `[app/cpy/CSUSR01Y.cpy:L17-L23]`.
- `app/cpy/COCOM01Y.cpy` — base `CARDDEMO-COMMAREA`
  `[app/cpy/COCOM01Y.cpy:L19-L44]`; extended inline by COUSR00C at
  `[app/cbl/COUSR00C.cbl:L67-L75]` with CDEMO-CU00-INFO.
- `app/cpy/CSMSG01Y.cpy` — `CCDA-MSG-INVALID-KEY`
  `[app/cpy/CSMSG01Y.cpy:L20-L21]` referenced by MAIN-PARA WHEN OTHER at
  `[app/cbl/COUSR00C.cbl:L135]`.

## Capture Procedure Cross-Reference

To replace `stdout.txt` and `bms_output.txt` with captured COBOL
artifacts, follow `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until
those artifacts are committed, `CoUsr00CGoldenTest` is `@Disabled` per
AAP §0.6.11 and parity assertions remain inert. Placeholder content
suffices for harness-scaffolding verification (test class loads,
fixture-routing resolves, auxiliary-input wiring connects) but NOT for byte-level parity.

## DO NOT Modify

```
This contract is the authoritative specification for the COUSR00C golden-record
test fixture. Any change to a fixture file, scenario count, message verbatim, or
BMS map invariant MUST be accompanied by a corresponding change to this README
and re-captured stdout.txt and bms_output.txt artifacts. See AAP section 0.7.1
(Minimal Change Clause) and AAP section 0.6.11 (PR gate).
```
