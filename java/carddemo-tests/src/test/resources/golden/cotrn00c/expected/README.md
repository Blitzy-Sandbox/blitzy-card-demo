# Golden-Record Contract — COTRN00C (Transaction List Online; CICS CT00)

This document is the BINDING authoritative contract for the COTRN00C
golden-record test fixture. It mediates between (a) the COBOL source
`app/cbl/COTRN00C.cbl` (699 lines; PROGRAM-ID `COTRN00C` at [L23]; CICS
transaction `CT00` at [L37]; "List Transactions from TRANSACT file" per
[L5]), (b) the Java translation
`com.blitzy.carddemo.application.transaction.CoTrn00C`, (c) the test class
`com.blitzy.carddemo.tests.golden.CoTrn00CGoldenTest` extending
`GoldenRecordTest`, and (d) the fixture files in this directory. BMS
mapset `COTRN00` at [`app/bms/COTRN00.bms`:L19]; map `COTRN0A` (24x80) at
[L26-L28]. COTRN00C is a READ-ONLY browse program
(STARTBR/READNEXT/READPREV/ENDBR against TRANSACT, 10 rows/page, ascending
by TRAN-ID). Byte-for-byte parity is non-negotiable (AAP §0.6.11 PR gate).

## Authority Cascade

- AAP §0.1.1 — refactoring objective: byte-for-byte parity
- AAP §0.2.1 — golden-record fixtures in scope
- AAP §0.2.2 — `app/` tree immutable
- AAP §0.3.1 — fixture directory layout (`<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 — TRANSACT fixture REFERENCED; outputs CAPTURED
- AAP §0.6.4 — `ScopedValue` for fixed `java.time.Clock` propagation
- AAP §0.6.5 — file I/O exactness (`java.nio.file` only)
- AAP §0.6.6 — virtual-thread fan-out restrictions (FORBIDDEN here; would reorder)
- AAP §0.6.11 — golden-record harness PR gate (`@Disabled` until capture)
- AAP §0.6.12 — architectural override (no Spring; plain Java constructor injection)
- AAP §0.7.1 — Minimal Change Clause (verbatim messages preserved EXACTLY)
- AAP §0.7.2 — PAN masking in logs (`XXXXXXXXXXXX####`)
- AAP §0.7.4 — forbidden preview features (no JEP 502/505/507/512 in production)
- AAP §0.7.5 — capture procedure cross-reference (`java/MIGRATION_NOTES.md` §1.6)

## Phase 0 — Overview

### Program Identity

| Attribute | Value | Source |
|---|---|---|
| COBOL PROGRAM-ID | `COTRN00C` | [`app/cbl/COTRN00C.cbl`:L23] |
| COBOL AUTHOR | `AWS` | [`app/cbl/COTRN00C.cbl`:L24] |
| COBOL header function | `List Transactions from TRANSACT file` | [`app/cbl/COTRN00C.cbl`:L5] |
| CICS transaction ID | `CT00` (WS-TRANID PIC X(04)) | [`app/cbl/COTRN00C.cbl`:L37] |
| BMS mapset | `COTRN00` | [`app/bms/COTRN00.bms`:L19] |
| BMS map | `COTRN0A` (size 24x80) | [`app/bms/COTRN00.bms`:L26-L28] |
| Java application class FQN | `com.blitzy.carddemo.application.transaction.CoTrn00C` | AAP §0.3.1 |
| Java test class FQN | `com.blitzy.carddemo.tests.golden.CoTrn00CGoldenTest` | AAP §0.6.11 |

### Read-Only Browse Semantics

COTRN00C uses ONLY four browse operations on TRANSACT: STARTBR
([L591-L600]), READNEXT ([L624-L634]), READPREV ([L658-L668]), and ENDBR
([L692-L696]); NEVER REWRITE/WRITE/DELETE/UNLOCK. Therefore `transact.txt`
MUST be byte-identical pre and post every scenario; asserted by
`EXPECT_TRANSACT_UNCHANGED`. Virtual-thread fan-out is FORBIDDEN (AAP
§0.6.6) because it would reorder the ascending-by-TRAN-ID sequence.

### Java Translation Target

The Java SUT is `com.blitzy.carddemo.application.transaction.CoTrn00C` in
module `carddemo-application` (one Java class per COBOL `PROGRAM-ID` per
AAP §0.3.1). Test class `CoTrn00CGoldenTest` in `carddemo-tests` extends
`GoldenRecordTest` (AAP §0.6.11) and is `@Disabled("Pending COBOL baseline
capture — see MIGRATION_NOTES.md §1.6")` until the 3 placeholders are
replaced.

## Phase 1 — PROGRAM-ID and WORKING-STORAGE Verbatim

Byte-faithful reproduction from `app/cbl/COTRN00C.cbl` (trailing `[Lnn]`
cite source lines).

```cobol
IDENTIFICATION DIVISION.                                          [L22-L24]
PROGRAM-ID. COTRN00C.
AUTHOR.     AWS.

WORKING-STORAGE SECTION.                                          [L33]

01 WS-VARIABLES.                                                  [L35]
   05 WS-PGMNAME              PIC X(08) VALUE 'COTRN00C'.         [L36]
   05 WS-TRANID               PIC X(04) VALUE 'CT00'.             [L37]
   05 WS-MESSAGE              PIC X(80) VALUE SPACES.             [L38]
   05 WS-TRANSACT-FILE        PIC X(08) VALUE 'TRANSACT'.         [L39]
   05 WS-ERR-FLG              PIC X(01) VALUE 'N'.                [L40]
     88 ERR-FLG-ON                      VALUE 'Y'.                [L41]
     88 ERR-FLG-OFF                     VALUE 'N'.                [L42]
   05 WS-TRANSACT-EOF         PIC X(01) VALUE 'N'.                [L43]
     88 TRANSACT-EOF                    VALUE 'Y'.                [L44]
     88 TRANSACT-NOT-EOF                VALUE 'N'.                [L45]
   05 WS-SEND-ERASE-FLG       PIC X(01) VALUE 'Y'.                [L46]
     88 SEND-ERASE-YES                  VALUE 'Y'.                [L47]
     88 SEND-ERASE-NO                   VALUE 'N'.                [L48]
   05 WS-RESP-CD              PIC S9(09) COMP VALUE ZEROS.        [L50]
   05 WS-REAS-CD              PIC S9(09) COMP VALUE ZEROS.        [L51]
   05 WS-REC-COUNT            PIC S9(04) COMP VALUE ZEROS.        [L52]
   05 WS-IDX                  PIC S9(04) COMP VALUE ZEROS.        [L53]
   05 WS-PAGE-NUM             PIC S9(04) COMP VALUE ZEROS.        [L54]
   05 WS-TRAN-AMT             PIC +99999999.99.                   [L56]
   05 WS-TRAN-DATE            PIC X(08) VALUE '00/00/00'.         [L57]
```

NOTE: WS-PAGE-NUM at [L54] initializes to zero; PROCESS-PAGE-FORWARD's
`COMPUTE` at [L306-L307] increments to 1 on first invocation. WS-TRAN-AMT
at [L56] has NO `VALUE` clause; populated from `TRAN-AMT` in
POPULATE-TRAN-DATA at [L383]. Edit pattern `PIC +99999999.99` emits a
leading sign (space for positive, `-` for negative) + 8 integer digits +
`.` + 2 fractional digits — total 12 bytes, matching `TAMT00n` BMS
LENGTH=12 at [`app/bms/COTRN00.bms`:L179].

## Phase 2 — Commarea CDEMO-CT00-INFO Inline Extension

```cobol
COPY COCOM01Y.                                                    [L61]
   05 CDEMO-CT00-INFO.                                            [L62]
      10 CDEMO-CT00-TRNID-FIRST     PIC X(16).                    [L63]
      10 CDEMO-CT00-TRNID-LAST      PIC X(16).                    [L64]
      10 CDEMO-CT00-PAGE-NUM        PIC 9(08).                    [L65]
      10 CDEMO-CT00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.          [L66]
         88 NEXT-PAGE-YES                     VALUE 'Y'.          [L67]
         88 NEXT-PAGE-NO                      VALUE 'N'.          [L68]
      10 CDEMO-CT00-TRN-SEL-FLG     PIC X(01).                    [L69]
      10 CDEMO-CT00-TRN-SELECTED    PIC X(16).                    [L70]
```

**CRITICAL**: This 58-byte extension (16+16+8+1+1+16) is appended INLINE in
COTRN00C after `COPY COCOM01Y.` at [L61]; it is NOT defined inside
`app/cpy/COCOM01Y.cpy`. The base commarea at [`app/cpy/COCOM01Y.cpy`:L19-L44]
defines CDEMO-GENERAL-INFO / CUSTOMER-INFO / ACCOUNT-INFO / CARD-INFO /
MORE-INFO only; CDEMO-CT00-INFO is COTRN00C-specific. The Java
`com.blitzy.carddemo.domain.commarea.CardDemoCommarea` MUST include a
nested record `CdemoCt00Info` with the 6 fields plus a sealed
`NextPageFlag {Yes, No}` hierarchy mirroring [L67-L68] 88-levels (AAP §0.3.2).

## Phase 3 — MAIN-PARA Dispatch

### First-Time Entry (EIBCALEN = 0)

At [L107-L109]: `IF EIBCALEN = 0 / MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM /
PERFORM RETURN-TO-PREV-SCREEN`. RETURN-TO-PREV-SCREEN at [L510-L521]
issues `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)`
at [L518-L521]. The user is sent to `COSGN00C` to authenticate.

### Reenter First Time (CDEMO-PGM-REENTER false)

At [L112-L116]: `SET CDEMO-PGM-REENTER TO TRUE / MOVE LOW-VALUES TO COTRN0AO /
PERFORM PROCESS-ENTER-KEY / PERFORM SEND-TRNLST-SCREEN`. On first invocation
all SEL fields are SPACES so the EVALUATE in PROCESS-ENTER-KEY falls through;
PROCESS-PAGE-FORWARD paints page 1 with TRAN-ID = LOW-VALUES.

### Reenter Subsequent (EIBAID Dispatch)

At [L117-L134] dispatches by EIBAID:

| EIBAID Value | Action | Cited Lines |
|---|---|---|
| `DFHENTER` | `PERFORM PROCESS-ENTER-KEY` | [L120-L121] |
| `DFHPF3` | `MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM; PERFORM RETURN-TO-PREV-SCREEN` | [L122-L124] |
| `DFHPF7` | `PERFORM PROCESS-PF7-KEY` (page backward) | [L125-L126] |
| `DFHPF8` | `PERFORM PROCESS-PF8-KEY` (page forward) | [L127-L128] |
| `WHEN OTHER` | `MOVE 'Y' TO WS-ERR-FLG; MOVE -1 TO TRNIDINL; MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE; PERFORM SEND-TRNLST-SCREEN` | [L129-L133] |

`CCDA-MSG-INVALID-KEY` is sourced from `app/cpy/CSMSG01Y.cpy` at [L20-L21]:
`PIC X(50) VALUE 'Invalid key pressed. Please see below...         '.` (literal
is 40 chars + 9 trailing spaces in source; padded by COBOL to 50-byte field
value).

### Final RETURN with COMMAREA

At [L138-L141]: `EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)
END-EXEC.` COMMAREA carries CDEMO-CT00-INFO updates (TRNID-FIRST/LAST,
PAGE-NUM, NEXT-PAGE-FLG, TRN-SEL-FLG, TRN-SELECTED) so the next pseudo-
conversational invocation observes the same pagination state.

## Phase 4 — PROCESS-ENTER-KEY (Row Selection + Tran ID Search)

### 10-Row Selection EVALUATE

At [L148-L182], an `EVALUATE TRUE` walks rows 1..10 in order:
`WHEN SEL000nI OF COTRN0AI NOT = SPACES AND LOW-VALUES → MOVE SEL000nI TO
CDEMO-CT00-TRN-SEL-FLG; MOVE TRNIDnnI TO CDEMO-CT00-TRN-SELECTED` for n in
1..10, with `WHEN OTHER → MOVE SPACES TO CDEMO-CT00-TRN-SEL-FLG / SPACES
TO CDEMO-CT00-TRN-SELECTED` at [L179-L181]. **ORDER MATTERS**: the EVALUATE
picks the FIRST non-blank SEL field (first-match-wins) — if user types `S`
in both SEL0003 and SEL0007, only row 3 is selected. Java translation must
iterate sequentially and short-circuit on first non-blank.

### XCTL on 'S'/'s' Selection

At [L183-L195], inner `EVALUATE CDEMO-CT00-TRN-SEL-FLG`: `WHEN 'S' / WHEN
's'` falls through both labels to a common branch that moves `'COTRN01C'`
to CDEMO-TO-PROGRAM, WS-TRANID to CDEMO-FROM-TRANID, WS-PGMNAME to
CDEMO-FROM-PROGRAM, `0` to CDEMO-PGM-CONTEXT, then `EXEC CICS XCTL
PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) END-EXEC` at
[L192-L195]. Control transfers to `COTRN01C` (Transaction View). Java
translation invokes `CoTrn01C` via `ProgramRegistry` (AAP §0.4.2) or
constructor-injected collaborator; XCTL bypasses further validation.

### Invalid Selection Branch

At [L196-L202]: `WHEN OTHER → MOVE 'Invalid selection. Valid value is S' TO
WS-MESSAGE; MOVE -1 TO TRNIDINL OF COTRN0AI; END-EVALUATE`.

**CRITICAL VERBATIM**: the message at [L199] is `'Invalid selection. Valid value is S'`
with **NO trailing ellipsis** — this is the ONLY message in COTRN00C without `...`.

**PRESERVATION NOTE**: [L197] (`SET TRANSACT-EOF TO TRUE`) and [L202]
(`PERFORM SEND-TRNLST-SCREEN`) are COMMENTED OUT (leading `*` in column 7);
the Java translation MUST NOT execute either statement (AAP §0.7.1).

### TRNIDIN Numeric Validation

At [L206-L219]: `IF TRNIDINI OF COTRN0AI = SPACES OR LOW-VALUES → MOVE
LOW-VALUES TO TRAN-ID`; otherwise `IF TRNIDINI OF COTRN0AI IS NUMERIC →
MOVE TRNIDINI TO TRAN-ID`; otherwise `MOVE 'Y' TO WS-ERR-FLG; MOVE
'Tran ID must be Numeric ...' TO WS-MESSAGE; MOVE -1 TO TRNIDINL; PERFORM
SEND-TRNLST-SCREEN`.

**CRITICAL VERBATIM**: the message at [L214] is `'Tran ID must be Numeric ...'`
with ONE SPACE between `Numeric` and `...` — preserve EXACTLY.

After validation, [L224-L225] sets `MOVE 0 TO CDEMO-CT00-PAGE-NUM` and
`PERFORM PROCESS-PAGE-FORWARD`. Initial `PAGE-NUM = 0` causes
PROCESS-PAGE-FORWARD's `COMPUTE CDEMO-CT00-PAGE-NUM = CDEMO-CT00-PAGE-NUM + 1`
at [L306-L307] to land on 1 on the first successful page render.

## Phase 5 — PROCESS-PF7-KEY (Page Backward)

### Page-Boundary Guard

At [L236-L240]: `IF CDEMO-CT00-TRNID-FIRST = SPACES OR LOW-VALUES / MOVE
LOW-VALUES TO TRAN-ID / ELSE / MOVE CDEMO-CT00-TRNID-FIRST TO TRAN-ID /
END-IF`. The `CDEMO-CT00-TRNID-FIRST` anchor (set by POPULATE-TRAN-DATA
WHEN 1 at [L392-L393]) enables backward navigation from a known boundary.

### Backward Dispatch

At [L242-L252]: `SET NEXT-PAGE-YES TO TRUE`; `MOVE -1 TO TRNIDINL`; `IF
CDEMO-CT00-PAGE-NUM > 1 → PERFORM PROCESS-PAGE-BACKWARD`; otherwise `MOVE
'You are already at the top of the page...' TO WS-MESSAGE; SET SEND-ERASE-NO
TO TRUE; PERFORM SEND-TRNLST-SCREEN`.

**CRITICAL VERBATIM**: boundary message at [L248] is `'You are already at
the top of the page...'`. **DUAL-PATH SEND**: `SEND-ERASE-NO` means
existing page contents remain visible while only ERRMSG row 23 updates
(vs default SEND-ERASE-YES at [L100] which ERASEs before painting).

### TRNIDINL Cursor Reset

`MOVE -1 TO TRNIDINL OF COTRN0AI` at [L243] (and at 12 other sites: L105,
L131, L201, L216, L221, L265, L610, L617, L644, L651, L678, L685) is a BMS
attribute manipulation, not a data move: the `-L` length-sentinel field,
when set to `-1`, directs CICS to position the cursor at the field's first
column upon the next SEND with `CURSOR` operand.

## Phase 6 — PROCESS-PF8-KEY (Page Forward)

### Page-Boundary Guard

At [L259-L263]: `IF CDEMO-CT00-TRNID-LAST = SPACES OR LOW-VALUES / MOVE
HIGH-VALUES TO TRAN-ID / ELSE / MOVE CDEMO-CT00-TRNID-LAST TO TRAN-ID`.
The anchor (set by POPULATE-TRAN-DATA WHEN 10 at [L438-L439]) enables
forward navigation. Note PF7 uses `LOW-VALUES` default; PF8 uses
`HIGH-VALUES`.

### Forward Dispatch

At [L267-L274]: `IF NEXT-PAGE-YES → PERFORM PROCESS-PAGE-FORWARD`;
otherwise `MOVE 'You are already at the bottom of the page...' TO
WS-MESSAGE; SET SEND-ERASE-NO TO TRUE; PERFORM SEND-TRNLST-SCREEN`.

**CRITICAL VERBATIM**: boundary message at [L270] is `'You are already at
the bottom of the page...'`. `SEND-ERASE-NO` preserves current page view.

## Phase 7 — PROCESS-PAGE-FORWARD / PROCESS-PAGE-BACKWARD

### PROCESS-PAGE-FORWARD Outer Loop

At [L279-L328]: `PERFORM STARTBR-TRANSACT-FILE`; skip-first-READNEXT
optimization at [L285] is `IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3`
(STARTBR positions on first matching record without READNEXT consuming it
for re-renders; for fresh forward navigation, the first READNEXT advances
past the boundary key). Loop at [L297] `PERFORM UNTIL WS-IDX >= 11 OR
TRANSACT-EOF OR ERR-FLG-ON` calls READNEXT then POPULATE-TRAN-DATA 10
times; `IF WS-IDX > 1` at [L316-L319] handles partial-last-page; finally
PERFORM ENDBR-TRANSACT-FILE, MOVE PAGE-NUM TO PAGENUMI, PERFORM
SEND-TRNLST-SCREEN.

### PROCESS-PAGE-BACKWARD Outer Loop

At [L333-L376]: similar skeleton; skip-first-READPREV optimization at
[L339] is `IF EIBAID NOT = DFHENTER AND DFHPF8`. **REVERSED LOOP**: WS-IDX
initialized to 10 at [L349] and decremented at [L355] — POPULATE-TRAN-DATA
runs 10 DOWN to 1 so on-screen TRNID01..TRNID10 rows are ordered ASCENDING
even though READPREV reads descending. Double-READPREV lookahead at [L360]
determines whether a previous page exists; if yes, [L362-L367] decrements
`CDEMO-CT00-PAGE-NUM` (edge case `MOVE 1 TO CDEMO-CT00-PAGE-NUM` at [L366]).

### POPULATE-TRAN-DATA per Row

At [L381-L445]. Prologue: TRAN-AMT → WS-TRAN-AMT [L383]; TRAN-ORIG-TS →
WS-TIMESTAMP [L384]; then [L385-L387] extract YY/MM/DD via
`WS-TIMESTAMP-DT-YYYY(3:2)` (chars 3-4 of 4-digit year, e.g., `2025` →
`25`), WS-TIMESTAMP-DT-MM, WS-TIMESTAMP-DT-DD; assemble `WS-CURDATE-MM-DD-YY
TO WS-TRAN-DATE` [L388]. Resulting `WS-TRAN-DATE` is `MM/DD/YY` (8 bytes,
matching `TDATEnn` LENGTH=8).

Each per-row WHEN branch issues 4 MOVEs (TRAN-ID, WS-TRAN-DATE, TRAN-DESC,
WS-TRAN-AMT). Two are special: **WHEN 1** at [L391-L396] additionally
writes `CDEMO-CT00-TRNID-FIRST` via multi-target MOVE at [L392-L393]
(`MOVE TRAN-ID TO TRNID01I OF COTRN0AI CDEMO-CT00-TRNID-FIRST`); **WHEN 10**
at [L437-L442] additionally writes `CDEMO-CT00-TRNID-LAST` at [L438-L439].
These anchor moves are the BOUNDARY KEYS for next PF7/PF8 navigation.
`WHEN OTHER → CONTINUE` at [L443-L444] is the safety net.

### INITIALIZE-TRAN-DATA per Row

At [L450-L505]. For each WS-IDX 1..10 the paragraph clears the row's 4
output fields via `MOVE SPACES TO TRNIDnnI, TDATEnnI, TDESCnnI, TAMT00nI`;
`WHEN OTHER → CONTINUE` at [L503-L504]. Invoked BEFORE POPULATE-TRAN-DATA
so stale data from prior pages does not bleed through partial-last-page.

## Phase 8 — STARTBR / READNEXT / READPREV / ENDBR

### STARTBR-TRANSACT-FILE

At [L591-L619]: `EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID)
KEYLENGTH(LENGTH OF TRAN-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC`.

**CRITICAL PRESERVATION (GTEQ)**: `GTEQ` operand is COMMENTED OUT at
[L597] (source is `*         GTEQ`). Translation MUST preserve this — do
NOT enable GTEQ; default STARTBR positioning is EQUAL (exact-match), which
matches COBOL observable behavior. Uncommenting GTEQ breaks byte-for-byte
parity: STARTBR-NOTFND would no longer fire on missing exact keys.

EVALUATE branches: `WHEN DFHRESP(NORMAL) CONTINUE`; `WHEN DFHRESP(NOTFND)
SET TRANSACT-EOF TO TRUE; MOVE 'You are at the top of the page...' TO
WS-MESSAGE` ([L608]); `WHEN OTHER DISPLAY 'RESP:' WS-RESP-CD 'REAS:'
WS-REAS-CD` ([L613]) `MOVE 'Y' TO WS-ERR-FLG; MOVE 'Unable to lookup
transaction...' TO WS-MESSAGE` ([L615]).

**CRITICAL VERBATIM**: [L608] is `'You are at the top of the page...'`
(distinct from [L248] and [L676] — see Supplementary Section 2).

### READNEXT-TRANSACT-FILE

At [L624-L653]. Structure mirrors STARTBR. EVALUATE: NORMAL → CONTINUE;
ENDFILE → SET TRANSACT-EOF TO TRUE; `MOVE 'You have reached the bottom of
the page...' TO WS-MESSAGE` ([L642]); OTHER → DISPLAY ([L647]) then `MOVE
'Unable to lookup transaction...' TO WS-MESSAGE` ([L649] — BYTE-IDENTICAL
to [L615]).

### READPREV-TRANSACT-FILE

At [L658-L687]. Structure mirrors READNEXT. EVALUATE: NORMAL → CONTINUE;
ENDFILE → SET TRANSACT-EOF TO TRUE; `MOVE 'You have reached the top of the
page...' TO WS-MESSAGE` ([L676]); OTHER → DISPLAY ([L681]) then `MOVE
'Unable to lookup transaction...' TO WS-MESSAGE` ([L683] — BYTE-IDENTICAL
to [L615] and [L649]).

### ENDBR-TRANSACT-FILE

At [L692-L696]: `EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE) END-EXEC.` NO
error handling; ENDBR is presumed to succeed and no RESP/RESP2 check is
performed. The Java translation MUST NOT add error handling here (AAP §0.7.1).

## Phase 9 — SEND / RECEIVE / POPULATE-HEADER-INFO / RETURN-TO-PREV-SCREEN

### SEND-TRNLST-SCREEN

At [L527-L549]: `PERFORM POPULATE-HEADER-INFO`; `MOVE WS-MESSAGE TO ERRMSGO
OF COTRN0AO`; `IF SEND-ERASE-YES → EXEC CICS SEND MAP('COTRN0A')
MAPSET('COTRN00') FROM(COTRN0AO) ERASE CURSOR END-EXEC`; otherwise same
SEND **without** ERASE (`*                  ERASE` commented out at [L546])
but with `CURSOR`. **DUAL-PATH SEND**: `SEND-ERASE-YES` is the default (set
at [L100]); `SEND-ERASE-NO` is set only at PF7/PF8 boundary error paths
([L250] and [L272]). `CURSOR` uses the field's `-L` length-sentinel.

### RECEIVE-TRNLST-SCREEN

At [L554-L562]: `EXEC CICS RECEIVE MAP('COTRN0A') MAPSET('COTRN00')
INTO(COTRN0AI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC.` Invoked from
MAIN-PARA at [L118] for reentries to read back symbolic-map fields.

### POPULATE-HEADER-INFO

At [L567-L586]: `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` [L569];
populate TITLE01O/TITLE02O from CCDA-TITLE01/CCDA-TITLE02; populate
TRNNAMEO from WS-TRANID; PGMNAMEO from WS-PGMNAME. Date assembled
MM/DD/YY into CURDATEO at [L580]; time assembled HH/MM/SS into CURTIMEO at
[L586]. The Java translation MUST source date/time from a `java.time.Clock`
provided via `ScopedValue` (AAP §0.6.4) for fixture determinism; capture
procedure binds Instant `2025-09-16T13:42:00Z`, ZoneId `America/New_York`
(see Supplementary Section 4).

### RETURN-TO-PREV-SCREEN

At [L510-L521]: defaults CDEMO-TO-PROGRAM to `'COSGN00C'` if blank; moves
WS-TRANID to CDEMO-FROM-TRANID, WS-PGMNAME to CDEMO-FROM-PROGRAM, ZEROS to
CDEMO-PGM-CONTEXT; then `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
COMMAREA(CARDDEMO-COMMAREA) END-EXEC`. Invoked from MAIN-PARA at TWO sites:
[L109] (first-time entry; CDEMO-TO-PROGRAM = 'COSGN00C') and [L124] (PF3
dispatch; CDEMO-TO-PROGRAM = 'COMEN01C').

## Phase 10 — BMS Map COTRN0A Layout

### Screen Geometry (24x80)

The map is defined at [`app/bms/COTRN00.bms`:L26-L28] with `COLUMN=1,
LINE=1, SIZE=(24,80)`. Row-by-row layout:

```text
Row  Content
 1   'Tran:'(1,1) | TRNNAME(1,7) | TITLE01(1,21) | 'Date:'(1,65) | CURDATE(1,71)
 2   'Prog:'(2,1) | PGMNAME(2,7) | TITLE02(2,21) | 'Time:'(2,65) | CURTIME(2,71)
 4   'List Transactions'(4,30) | 'Page:'(4,65) | PAGENUM(4,71)
 6   'Search Tran ID:'(6,5) | TRNIDIN(6,21) LEN=16
 8   'Sel'(8,2) | ' Transaction ID '(8,8) | '  Date  '(8,27) | 'Description'(8,38) | 'Amount'(8,67)
 9   '---'(9,2) | 16x'-'(9,8) | 8x'-'(9,27) | 26x'-'(9,38) | 12x'-'(9,67)
10..19  Per-row group n (rows 10..19 → entries 1..10): SEL000n(n,3) LEN=1 |
        TRNIDnn(n,8) LEN=16 | TDATEnn(n,27) LEN=8 | TDESCnn(n,38) LEN=26 |
        TAMT00n(n,67) LEN=12
21   BRT/NEUTRAL 50-byte legend at (21,12): "Type 'S' to View Transaction details from the list"
23   ERRMSG at (23,1) LEN=78 BRT/FSET COLOR=RED (verbatim message echo)
24   YELLOW 48-byte prompt at (24,1): 'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'
```

### Editable Fields (11 total)

- `TRNIDIN` POS=(6,21) LENGTH=16 UNPROT NORM GREEN HILIGHT=UNDERLINE FSET
  ([`app/bms/COTRN00.bms`:L95-L99])
- `SEL0001`..`SEL0010` POS=(10,3)..(19,3) LENGTH=1 UNPROT NORM GREEN
  HILIGHT=UNDERLINE FSET ([L153-L158] through [L414-L419]) — 10 identical
  1-byte fields, one per list row.

All other fields are ASKIP (protected from input).

### Color/Attribute Map

- **BLUE** — header data fields (TRNNAME, PGMNAME, CURDATE, CURTIME,
  PAGENUM, TRNIDnn, TDATEnn, TDESCnn, TAMT00n)
- **YELLOW** — TITLE01, TITLE02, and the row-24 PF-key prompt
- **TURQUOISE (BRT)** — `'List Transactions'` (4,30), `'Page:'`,
  `'Search Tran ID:'` (per [L80-L83, L90-L94])
- **GREEN HILIGHT=UNDERLINE** — editable inputs (TRNIDIN, SEL0001..SEL0010)
- **NEUTRAL** — separator dashes, column headers, row-21 BRT legend
- **RED for ERRMSG** (row 23) — verbatim message echo BRT/FSET per
  [`app/bms/COTRN00.bms`:L450-L453]

## Phase 11 — Required Test Scenarios

10 BINDING scenarios in `input_scenario.txt` (in declaration order):

1. **first_time_entry** (EIBCALEN=0) → `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM;
   PERFORM RETURN-TO-PREV-SCREEN` → EXPECT_XCTL `COSGN00C` (no SEND MAP).
2. **initial_display_page1** (EIBCALEN>0, CDEMO-PGM-REENTER false) →
   PROCESS-ENTER-KEY with TRNIDIN blank → PROCESS-PAGE-FORWARD with
   TRAN-ID=LOW-VALUES → first 10 transactions displayed,
   CDEMO-CT00-PAGE-NUM=1, TRNID-FIRST/LAST set, ERRMSG=SPACES.
3. **pf8_forward_to_page2** (DFHPF8, NEXT-PAGE-YES) → PROCESS-PAGE-FORWARD
   with TRAN-ID=CDEMO-CT00-TRNID-LAST → page 2, CDEMO-CT00-PAGE-NUM=2.
4. **pf8_at_last_page** (DFHPF8 when NEXT-PAGE-NO) → ERRMSG=`'You are
   already at the bottom of the page...'`, SEND-ERASE-NO (page preserved).
5. **pf7_backward_to_page1** (DFHPF7 from page 2) → PROCESS-PAGE-BACKWARD
   with TRAN-ID=CDEMO-CT00-TRNID-FIRST → page 1, CDEMO-CT00-PAGE-NUM=1.
6. **pf7_at_top_page** (DFHPF7 when CDEMO-CT00-PAGE-NUM=1) →
   ERRMSG=`'You are already at the top of the page...'`, SEND-ERASE-NO.
7. **enter_select_s_row5** (DFHENTER, SEL0005='S') → EVALUATE WHEN SEL0005I
   → MOVE TRNID05I to CDEMO-CT00-TRN-SELECTED → EVALUATE 'S' → EXPECT_XCTL
   `COTRN01C`.
8. **enter_invalid_selection_x** (DFHENTER, SEL0003='X') → EVALUATE WHEN
   SEL0003I → 'X' to CDEMO-CT00-TRN-SEL-FLG → EVALUATE WHEN OTHER →
   ERRMSG=`'Invalid selection. Valid value is S'` (NO trailing ellipsis).
9. **trnidin_non_numeric** (DFHENTER, TRNIDIN='ABCD') → IF TRNIDINI NOT
   NUMERIC → ERRMSG=`'Tran ID must be Numeric ...'` (space before `...`).
10. **pf3_to_main_menu** (DFHPF3) → `MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM;
    PERFORM RETURN-TO-PREV-SCREEN` → EXPECT_XCTL `COMEN01C` (Main Menu,
    NOT admin menu COADM01C; per [L122-L124]).

### EXPECT_TRANSACT_UNCHANGED Invariant

Every scenario MUST end with `EXPECT_TRANSACT_UNCHANGED` asserting that
`transact.txt` is byte-identical pre and post (READ-ONLY browse: NO
REWRITE/WRITE/DELETE/UNLOCK). The harness SHA-256 compares post-state
against pre-state from the input fixture — any mismatch fails the test.

## Phase 12 — Files in This Directory

| Filename | Purpose | Ownership |
|---|---|---|
| `README.md` | This file — authoritative contract | DOCUMENTATION |
| `input_scenario.txt` | Deterministic CICS pseudo-conversation script (10 scenarios) | TEST-OWNED |
| `transact.txt` | TRANSACT initial-state fixture (350-byte fixed-width records) | CAPTURE PLACEHOLDER |
| `stdout.txt` | Captured COBOL DISPLAY output | CAPTURE PLACEHOLDER |
| `bms_output.txt` | Captured BMS SEND MAP frames | CAPTURE PLACEHOLDER |

Three files (`transact.txt`, `stdout.txt`, `bms_output.txt`) are CAPTURE
PLACEHOLDERS pending COBOL baseline capture per `java/MIGRATION_NOTES.md`
§1.6. Until capture, `CoTrn00CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Phase 13 — Structural Invariants

### Read-Only Browse Semantics

STARTBR / READNEXT / READPREV / ENDBR only. NO REWRITE, NO WRITE, NO DELETE,
NO UNLOCK. The TRANSACT file MUST be byte-identical before and after every
scenario; asserted by `EXPECT_TRANSACT_UNCHANGED` in every scenario.

### 10-Rows-Per-Page Strict Constant

WS-IDX bounds at [L297] (`UNTIL WS-IDX >= 11`) and [L351]
(`UNTIL WS-IDX <= 0`) are IMMUTABLE. The page size is exactly 10; never
alter. The BMS map has exactly 10 row positions (rows 10-19); any change
breaks byte-for-byte invariant.

### Date Format MM/DD/YY

All TDATEnn cells display `MM/DD/YY` format derived from TRAN-ORIG-TS
substring slicing (PIC X(08) VALUE '00/00/00' default at [L57]). Never use
ISO (`YYYY-MM-DD`), European (`DD/MM/YY`), or 4-digit year. Extraction at
[L385-L388].

### Sort Order

Ascending by TRAN-ID PIC X(16) lexicographic. TRANSACT KSDS native key
order is preserved by STARTBR/READNEXT. Virtual-thread fan-out is FORBIDDEN
here (AAP §0.6.6) because it would reorder records. Sequential single-thread
processing is mandatory.

### PAN Masking

BMS map COTRN0A does NOT display TRAN-CARD-NUM (PIC X(16) per
`app/cpy/CVTRA05Y.cpy` [L15]); only TRNID, TDATE, TDESC, and TAMT are
shown. On-screen PAN exposure is N/A. HOWEVER, any LOG output (Java SLF4J)
MUST mask TRAN-CARD-NUM values flowing through — mask format
`XXXXXXXXXXXX####` (last 4 digits preserved) per AAP §0.7.2.

## Contrast Matrix

| Aspect | COTRN00C (List, CT00) | COTRN01C (View, CT01) | COTRN02C (Add, CT02) |
|---|---|---|---|
| Function | List transactions with pagination | View single transaction by ID | Add new transaction |
| AID keys | ENTER, PF3, **PF7, PF8**, OTHER | ENTER, PF3, PF4, PF5, OTHER | ENTER, PF3, PF4, PF5, OTHER |
| BMS map | COTRN0A (10 list rows, search, 10 sel) | COTRN1A (single record) | COTRN2A (input form) |
| TRANSACT ops | **STARTBR/READNEXT/READPREV/ENDBR** | Single READ | STARTBR/READPREV/WRITE |
| Mutability | **READ-ONLY** (browse) | READ-ONLY (single READ) | READ-WRITE (creates new record) |
| Active DISPLAYs | 3 ([L613], [L647], [L681]) | 1 ([L290]) | 5 (L598, L631, L662, L691, L743) |
| XCTL targets | COSGN00C (EIBCALEN=0), COMEN01C (PF3), COTRN01C ('S' selection) | COMEN01C/CDEMO-FROM/COTRN00C | COMEN01C/CDEMO-FROM |
| Pagination | YES (10 rows, CDEMO-CT00-PAGE-NUM) | NO | NO |
| Commarea extension | CDEMO-CT00-INFO (58 bytes inline) | CDEMO-CT01-INFO (inline) | CDEMO-CT02-INFO (inline) |
| Verbatim error messages | 9 distinct | varies | 35 distinct |
| Dual SEND-ERASE paths | YES (PF7/PF8 boundary uses SEND-ERASE-NO) | NO (always SEND-ERASE-YES) | NO (always SEND-ERASE-YES) |
| Auto-trigger | NO (entry only via menu) | YES (XCTL from COTRN00C 'S') | YES (XCTL from COTRN00C 'A') |

## Verbatim COBOL Message Catalog

| # | Verbatim Message | Source Line | Originating Branch |
|---|---|---|---|
| 1 | `'Invalid selection. Valid value is S'` | [L199] | PROCESS-ENTER-KEY inner EVALUATE WHEN OTHER (NO trailing ellipsis — the ONLY message without `...`) |
| 2 | `'Tran ID must be Numeric ...'` | [L214] | PROCESS-ENTER-KEY TRNIDIN not numeric (ONE space before `...`) |
| 3 | `'You are already at the top of the page...'` | [L248] | PROCESS-PF7-KEY when CDEMO-CT00-PAGE-NUM = 1 |
| 4 | `'You are already at the bottom of the page...'` | [L270] | PROCESS-PF8-KEY when NEXT-PAGE-NO |
| 5 | `'You are at the top of the page...'` | [L608] | STARTBR-TRANSACT-FILE DFHRESP(NOTFND) |
| 6 | `'Unable to lookup transaction...'` | [L615] / [L649] / [L683] | STARTBR/READNEXT/READPREV WHEN OTHER (3 byte-identical occurrences) |
| 7 | `'You have reached the bottom of the page...'` | [L642] | READNEXT-TRANSACT-FILE DFHRESP(ENDFILE) |
| 8 | `'You have reached the top of the page...'` | [L676] | READPREV-TRANSACT-FILE DFHRESP(ENDFILE) |
| 9 | `CCDA-MSG-INVALID-KEY` from CSMSG01Y [L20-L21]: `'Invalid key pressed. Please see below...         '` (PIC X(50); 40-char message + 9 explicit trailing spaces + COBOL pad to 50) | [L132] | MAIN-PARA EIBAID WHEN OTHER |

**CRITICAL distinction**: messages 3, 5, and 8 all reference "the top of the
page" but use DIFFERENT verbs:

- Message 3 ([L248]): `'You are already at the top of the page...'` — PF7
  boundary (user-initiated page navigation)
- Message 5 ([L608]): `'You are at the top of the page...'` — STARTBR
  NOTFND (file-system condition)
- Message 8 ([L676]): `'You have reached the top of the page...'` —
  READPREV ENDFILE (cursor exhaustion)

All 3 are BYTE-DISTINCT strings and MUST be preserved separately. The
Unicode horizontal-ellipsis code point U+2026 is FORBIDDEN — ONLY 3 ASCII
periods `...` (three bytes 0x2E 0x2E 0x2E).

## Source Lineage

- `app/cbl/COTRN00C.cbl` (699 lines) — primary COBOL program; PROGRAM-ID `COTRN00C`; CICS transaction `CT00`
- `app/bms/COTRN00.bms` (464 lines) — BMS mapset `COTRN00`, MAP `COTRN0A`, size 24x80
- `app/cpy-bms/COTRN00.CPY` (728 lines) — BMS symbolic copybook; defines `COTRN0AI` (input) and `COTRN0AO` (output)
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD 350-byte layout consumed by READNEXT/READPREV INTO clause
- `app/cpy/COCOM01Y.cpy` — CARDDEMO-COMMAREA base; extended INLINE at [L62-L70] with CDEMO-CT00-INFO (58 bytes)
- `app/cpy/CSMSG01Y.cpy` — system message constants; CCDA-MSG-INVALID-KEY consumed at [L132]

## Capture Procedure Cross-Reference

Full procedure documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5.
Summary: (1) run COBOL `COTRN00C` under CICS (z/OS or Micro Focus enterprise
developer) replaying the 10 scenarios from `input_scenario.txt`;
(2) capture per scenario: initial TRANSACT KSDS contents → `transact.txt`,
DISPLAY stdout → `stdout.txt` (expected EMPTY under happy paths), BMS SEND
MAP frames → `bms_output.txt`; (3) transcode EBCDIC IBM-1047 → ASCII (AAP
§0.6.5); (4) apply PAN masking `XXXXXXXXXXXX####` to TRAN-CARD-NUM bytes
(AAP §0.7.2); (5) bind fixed `java.time.Clock` (Instant
`2025-09-16T13:42:00Z`, ZoneId `America/New_York`) for CURDATE/CURTIME
determinism (AAP §0.6.4); (6) commit the 3 captured artifacts replacing
placeholders; (7) remove `@Disabled` annotation from `CoTrn00CGoldenTest`.

## Cross-References

- Parallel fixture folder: `java/carddemo-tests/src/test/resources/golden/cotrn00c/input/` (pre-state)
- Sibling view fixture: `.../golden/cotrn01c/expected/` (COTRN01C transaction view)
- Sibling add fixture: `.../golden/cotrn02c/expected/` (COTRN02C transaction add; 13+7 template)
- Java test class FQN: `com.blitzy.carddemo.tests.golden.CoTrn00CGoldenTest`
- Java application class FQN: `com.blitzy.carddemo.application.transaction.CoTrn00C`
- Base class: `com.blitzy.carddemo.tests.golden.GoldenRecordTest` (AAP §0.6.11)
- Commarea record: `com.blitzy.carddemo.domain.commarea.CardDemoCommarea` with nested `CdemoCt00Info`
- TRAN record: `com.blitzy.carddemo.domain.record.TranRecord`
- Repository port: `com.blitzy.carddemo.domain.port.TransactionRepository`
  (file-backed adapter: `com.blitzy.carddemo.adapter.file.FileTransactionRepository`)

## Authority References

- AAP §0.1.1 — refactoring objective
- AAP §0.2.1 — fixtures in scope for CREATE
- AAP §0.2.2 — `app/` tree out of scope for modification
- AAP §0.3.1 — refactored fixture directory structure
- AAP §0.4.1 — file-by-file transformation plan
- AAP §0.6.4 — `ScopedValue` propagation
- AAP §0.6.5 — file I/O exactness (byte-for-byte parity)
- AAP §0.6.6 — virtual-thread fan-out restrictions
- AAP §0.6.11 — golden-record harness PR gate
- AAP §0.6.12 — architectural override (no Spring)
- AAP §0.7.1 — Minimal Change Clause
- AAP §0.7.2 — PAN masking in logs
- AAP §0.7.4 — forbidden preview features
- AAP §0.7.5 — capture procedure cross-reference

## DO NOT Modify Without Re-Capture

The 3 placeholder files (`transact.txt`, `stdout.txt`, `bms_output.txt`)
MUST be regenerated by the COBOL capture procedure when ANY of: (a) the
COBOL source `app/cbl/COTRN00C.cbl` changes (cannot happen per AAP §0.2.2
— `app/` is immutable); (b) the scenario manifest in `input_scenario.txt`
changes (e.g., adding an 11th scenario); (c) the fixed-Clock value changes
(would change CURDATE/CURTIME bytes); (d) the codepage/transcoding changes
(would change every byte).

Modifying these files by hand without a corresponding COBOL re-capture
breaks the byte-for-byte parity invariant and is FORBIDDEN. Any drift
between Java-emitted bytes and committed `bms_output.txt` produces a test
failure (AAP §0.6.11). The `README.md` and `input_scenario.txt` are
TEST-OWNED and may be modified — but EVERY change to `input_scenario.txt`
requires re-running the COBOL capture procedure to keep placeholders
synchronized.
