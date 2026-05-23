# COTRN02C (Transaction Add, CT02) — Golden-Record Test Fixture Contract

## Phase 0: Header and Authority Cascade

This document is the AUTHORITATIVE binding contract for the COTRN02C
golden-record test fixture, mediating between (a) the COBOL source
`app/cbl/COTRN02C.cbl` (Transaction Add; CICS transaction CT02), (b)
the Java translation
`com.blitzy.carddemo.application.transaction.CoTrn02C`, (c) the harness
`com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest` extending
`com.blitzy.carddemo.tests.golden.GoldenRecordTest`, and (d) the four
fixture files in this directory plus three read-only ASCII fixtures in
`app/data/ASCII/` referenced via classpath per AAP §0.4.1.

COTRN02C is THE MOST COMPLEX online program in CardDemo: a 783-line
COBOL source `[app/cbl/COTRN02C.cbl:L1-L783]` with 13 unprotected BMS
input fields plus one CONFIRM field, dual CSUTLDTC date-validation
calls at `[app/cbl/COTRN02C.cbl:L389-L407,L409-L427]`, a two-path
CARDXREF AIX lookup at `[app/cbl/COTRN02C.cbl:L576-L637]`, auto-trigger
from COTRN00C at `[app/cbl/COTRN02C.cbl:L124-L129]`, a double-space
success message via STRING at `[app/cbl/COTRN02C.cbl:L728-L732]`, and
an amount regex with required sign character at
`[app/cbl/COTRN02C.cbl:L340]`.

Authority references (binding): AAP §0.1.1 (`app/` immutable); §0.2.1
(in-scope wildcard `java/carddemo-tests/src/test/resources/golden/**/*`);
§0.2.2 (`app/` never modified); §0.3.1 (harness `<program>/input/` +
`<program>/expected/`); §0.4.1 (CoTrn02C in
`com.blitzy.carddemo.application.transaction`; ASCII fixtures via
classpath); §0.6.1 (`BigDecimal` scale 2 for TRAN-AMT); §0.6.4
(`java.time`; CSUTLDTC → `DateValidator` with `ResolverStyle.STRICT`);
§0.6.5 (`java.nio.file`); §0.6.6 (`ScopedValue` replaces `ThreadLocal`;
fixed-clock injection); §0.6.11 (golden-record PR gate; `@Disabled`
until capture); §0.6.12 (architectural override — no Spring/Hibernate/
Flyway); §0.7.1 (Minimal Change Clause — verbatim error messages,
double-space success, amount regex, sort order, FIRST-empty-wins
ordering); §0.7.2 (mask all but last 4 digits of card PAN); §0.7.4 (no
JEP-preview features); §0.7.5 (capture procedure in
`java/MIGRATION_NOTES.md` §1.6).

Sibling pattern reference: `golden/cousr00c/expected/README.md`
established the 13-phase contract pattern. The test class
`com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest` is `@Disabled
("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` until
`transact.txt`, `stdout.txt`, and `bms_output.txt` are replaced with
captured COBOL artifacts. Placeholder content suffices for harness
scaffolding (test class loads, fixture-routing resolves) but NOT for
byte-level parity.

## Phase 1: COBOL Source — PROGRAM-ID and Working-Storage Variables

PROGRAM-ID is `COTRN02C` `[app/cbl/COTRN02C.cbl:L23]`. The complete
inventory of working-storage variables referenced by the test scenarios:

| COBOL Name | PIC | Source Line | Java Equivalent | Notes |
|---|---|---|---|---|
| `WS-PGMNAME` | `X(08) VALUE 'COTRN02C'` | `[app/cbl/COTRN02C.cbl:L36]` | `CoTrn02C.PGMNAME` | Identifies emitter program in commarea |
| `WS-TRANID` | `X(04) VALUE 'CT02'` | `[app/cbl/COTRN02C.cbl:L37]` | `CoTrn02C.TRANID` | CICS transaction ID on EXEC CICS RETURN |
| `WS-MESSAGE` | `X(80) VALUE SPACES` | `[app/cbl/COTRN02C.cbl:L38]` | local `String` | Routed to ERRMSGO on SEND |
| `WS-TRANSACT-FILE` / `WS-ACCTDAT-FILE` / `WS-CCXREF-FILE` / `WS-CXACAIX-FILE` | `X(08)` (each) | `[app/cbl/COTRN02C.cbl:L39-L42]` | dataset-name constants | Trailing space padding preserved verbatim |
| `WS-ERR-FLG` (88 ON/OFF) / `WS-USR-MODIFIED` | `X(01) VALUE 'N'` | `[app/cbl/COTRN02C.cbl:L44-L46,L49-L51]` | `boolean` two-state | Set inside validation paragraphs |
| `WS-RESP-CD` / `WS-REAS-CD` | `S9(09) COMP` | `[app/cbl/COTRN02C.cbl:L47-L48]` | sealed `FileStatus.IoError(int, int)` | CICS RESP / RESP2 capture |
| `WS-TRAN-AMT-N` / `WS-TRAN-AMT-E` | `S9(9)V99` / `+99999999.99` | `[app/cbl/COTRN02C.cbl:L58-L59]` | `BigDecimal` scale 2 / `String` edited | NUMVAL-C of TRNAMTI then re-edit |
| `WS-ACCT-ID-N` / `WS-CARD-NUM-N` / `WS-TRAN-ID-N` | `9(11)` / `9(16)` / `9(16)` | `[app/cbl/COTRN02C.cbl:L55-L57]` | `long` / `String` / `String` | NUMVAL of input + auto-incremented Tran-ID |
| `WS-DATE-FORMAT` | `X(10) VALUE 'YYYY-MM-DD'` | `[app/cbl/COTRN02C.cbl:L60]` | `DateTimeFormatter` constant | Passed to CSUTLDTC |

CSUTLDTC-PARM structure at `[app/cbl/COTRN02C.cbl:L62-L69]`: 80-byte
LINKAGE-equivalent record passed to date-validation subroutine
(`app/cbl/CSUTLDTC.cbl`). Fields: `CSUTLDTC-DATE X(10)` (input);
`CSUTLDTC-DATE-FORMAT X(10)` (input); `CSUTLDTC-RESULT-SEV-CD X(04)`
(output, `'0000'` = success); `FILLER X(11)` (typically `'Mesg Code: '`);
`CSUTLDTC-RESULT-MSG-NUM X(04)` (output, `'2513'` = documented soft-fail
code); `CSUTLDTC-RESULT-MSG X(61)` (output, human-readable). Java
translation: `DateValidator` per AAP §0.4.1 using `LocalDate.parse` with
`DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)`.

## Phase 2: Commarea Sub-Structure (CDEMO-CT02-INFO)

The base `CARDDEMO-COMMAREA` is defined in
`[app/cpy/COCOM01Y.cpy:L19-L44]`. COTRN02C extends it inline with the
`CDEMO-CT02-INFO` sub-record at `[app/cbl/COTRN02C.cbl:L71-L80]` (after
`COPY COCOM01Y.` at `[app/cbl/COTRN02C.cbl:L71]`):

| Field | PIC | Source | Java Equivalent | Purpose |
|---|---|---|---|---|
| `CDEMO-CT02-TRNID-FIRST` / `CDEMO-CT02-TRNID-LAST` | `X(16)` / `X(16)` | `[app/cbl/COTRN02C.cbl:L73-L74]` | `CdemoCt02Info.trnidFirst/Last` | Reserved sibling-browse pagination |
| `CDEMO-CT02-PAGE-NUM` / `CDEMO-CT02-NEXT-PAGE-FLG` (88 YES/NO) | `9(08)` / `X(01) VALUE 'N'` | `[app/cbl/COTRN02C.cbl:L75-L78]` | `CdemoCt02Info.pageNum/nextPage` | Reserved pagination + sealed flag |
| `CDEMO-CT02-TRN-SEL-FLG` | `X(01)` | `[app/cbl/COTRN02C.cbl:L79]` | `CdemoCt02Info.trnSelFlag` | Selection flag from prior invocation |
| `CDEMO-CT02-TRN-SELECTED` | `X(16)` | `[app/cbl/COTRN02C.cbl:L80]` | `CdemoCt02Info.trnSelected` | **CRITICAL** — auto-trigger from COTRN00C row 'A' |

Auto-trigger mechanism at `[app/cbl/COTRN02C.cbl:L124-L129]` — when
COTRN00C selects a row with action 'A' and XCTLs to COTRN02C with
`CDEMO-CT02-TRN-SELECTED` populated, MAIN-PARA pre-loads CARDNINI:

```
IF CDEMO-CT02-TRN-SELECTED NOT = SPACES AND LOW-VALUES
    MOVE CDEMO-CT02-TRN-SELECTED TO CARDNINI OF COTRN2AI
    PERFORM PROCESS-ENTER-KEY
END-IF
```

Base commarea fields (`CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`,
`CDEMO-TO-PROGRAM`, `CDEMO-PGM-CONTEXT` with 88 `CDEMO-PGM-ENTER` /
`CDEMO-PGM-REENTER`) defined at `[app/cpy/COCOM01Y.cpy:L20-L31]` are
populated on every outbound XCTL via RETURN-TO-PREV-SCREEN at
`[app/cbl/COTRN02C.cbl:L500-L511]`.

## Phase 3: MAIN-PARA Dispatch Logic

MAIN-PARA `[app/cbl/COTRN02C.cbl:L107-L159]`: (1) initialize flags at
`[app/cbl/COTRN02C.cbl:L109-L113]` (SET ERR-FLG-OFF, SET USR-MODIFIED-NO,
MOVE SPACES to WS-MESSAGE/ERRMSGO); (2) `IF EIBCALEN = 0`
`[app/cbl/COTRN02C.cbl:L115-L117]` → XCTL to `'COSGN00C'`; (3) `ELSE`
MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA at L119; (4) EXEC
CICS RETURN with TRANSID = WS-TRANID at
`[app/cbl/COTRN02C.cbl:L156-L159]`.

First-pass vs re-entry at `[app/cbl/COTRN02C.cbl:L120-L153]`. First
pass: SET CDEMO-PGM-REENTER TO TRUE; MOVE LOW-VALUES TO COTRN2AO;
MOVE -1 TO ACTIDINL (IC marker); check auto-trigger; PERFORM
SEND-TRNADD-SCREEN. Re-entry: PERFORM RECEIVE-TRNADD-SCREEN; EVALUATE
EIBAID with exactly 4 named AID keys plus WHEN OTHER:

| EIBAID | Behavior | Java Equivalent | Source |
|---|---|---|---|
| `DFHENTER` | PERFORM PROCESS-ENTER-KEY | `case AidKey.Enter -> processEnterKey()` | `[app/cbl/COTRN02C.cbl:L134-L135]` |
| `DFHPF3` | XCTL to CDEMO-FROM-PROGRAM (default `'COMEN01C'`) | `case AidKey.PfKey03 -> returnToPrev(...)` | `[app/cbl/COTRN02C.cbl:L136-L143]` |
| `DFHPF4` | PERFORM CLEAR-CURRENT-SCREEN | `case AidKey.PfKey04 -> clearCurrentScreen()` | `[app/cbl/COTRN02C.cbl:L144-L145]` |
| `DFHPF5` | PERFORM COPY-LAST-TRAN-DATA | `case AidKey.PfKey05 -> copyLastTranData()` | `[app/cbl/COTRN02C.cbl:L146-L147]` |
| WHEN OTHER | MOVE CCDA-MSG-INVALID-KEY; SEND | `case AidKey other -> messageInvalidKey()` | `[app/cbl/COTRN02C.cbl:L148-L151]` |

AID-key invariants: NO PF7/PF8 handling (no pagination — contrast
COUSR00C); NO PF12 handling (footer-vs-code mismatch absent — contrast
COUSR01C); PF3 unconditionally falls back to `'COMEN01C'` when
CDEMO-FROM-PROGRAM is empty per `[app/cbl/COTRN02C.cbl:L137-L142]`.

Java translation MUST use pattern-matching `switch` on sealed `AidKey`
hierarchy `[app/cpy/CVCRD01Y.cpy:§CCARD-AID]` with NO `default` branch
(AAP §0.7.4). Compiler-enforced exhaustiveness routes unhandled permits
through the WHEN-OTHER catch-all that sets CCDA-MSG-INVALID-KEY.

## Phase 4: PROCESS-ENTER-KEY — Validation Pipeline and CONFIRM Dispatch

PROCESS-ENTER-KEY `[app/cbl/COTRN02C.cbl:L164-L188]` performs two
sequential PERFORM calls before dispatching on CONFIRM:
(1) PERFORM VALIDATE-INPUT-KEY-FIELDS at L166; (2) PERFORM
VALIDATE-INPUT-DATA-FIELDS at L167. After both PERFORMs return (each
may have already exited via SEND on error — the COBOL flow exits via
EXEC CICS RETURN inside SEND-TRNADD-SCREEN at
`[app/cbl/COTRN02C.cbl:L530-L534]` rather than returning to MAIN-PARA),
EVALUATE CONFIRMI at `[app/cbl/COTRN02C.cbl:L169-L188]`:

| CONFIRMI | Behavior | Source |
|---|---|---|
| `'Y'` or `'y'` | PERFORM ADD-TRANSACTION | `[app/cbl/COTRN02C.cbl:L170-L172]` |
| `'N'`/`'n'`/SPACES/LOW-VALUES | `'Confirm to add this transaction...'`; MOVE -1 TO CONFIRML; SEND | `[app/cbl/COTRN02C.cbl:L173-L181]` (message L178) |
| WHEN OTHER | `'Invalid value. Valid values are (Y/N)...'`; MOVE -1 TO CONFIRML; SEND | `[app/cbl/COTRN02C.cbl:L182-L187]` (message L184) |

Java: pattern-matching switch on sealed `ConfirmValue { Yes, No, Empty,
Invalid }` hierarchy with NO `default` branch per AAP §0.7.4.

## Phase 5: VALIDATE-INPUT-KEY-FIELDS and VALIDATE-INPUT-DATA-FIELDS — 22 Verbatim Error Messages

VALIDATE-INPUT-KEY-FIELDS `[app/cbl/COTRN02C.cbl:L193-L230]` is an
EVALUATE TRUE at L195 with three branches:

| Branch | Behavior | Source |
|---|---|---|
| ACTIDINI populated | IF NOT NUMERIC → `'Account ID must be Numeric...'`; ELSE COMPUTE WS-ACCT-ID-N; READ CXACAIX; MOVE XREF-CARD-NUM into CARDNINI | `[app/cbl/COTRN02C.cbl:L196-L209]` (L199) |
| CARDNINI populated | IF NOT NUMERIC → `'Card Number must be Numeric...'`; ELSE COMPUTE WS-CARD-NUM-N; READ CCXREF; MOVE XREF-ACCT-ID into ACTIDINI | `[app/cbl/COTRN02C.cbl:L210-L223]` (L213) |
| WHEN OTHER (both empty) | `'Account or Card Number must be entered...'`; MOVE -1 TO ACTIDINL; SEND | `[app/cbl/COTRN02C.cbl:L224-L229]` (L226) |

VALIDATE-INPUT-DATA-FIELDS `[app/cbl/COTRN02C.cbl:L235-L437]` has five
EVALUATE TRUE blocks plus a final IF for merchant-ID. If ERR-FLG-ON is
set from key-field validation, all 11 unprotected data fields are
reset to SPACES at `[app/cbl/COTRN02C.cbl:L237-L249]`.

Block 1 — 11 empty-field checks in FIRST-empty-wins order at
`[app/cbl/COTRN02C.cbl:L251-L320]` preserves source ordering per AAP
§0.7.1: (4) TTYPCDI L252-L257 → `'Type CD can NOT be empty...'` (L254);
(5) TCATCDI L258-L263 → `'Category CD can NOT be empty...'` (L260);
(6) TRNSRCI L264-L269 → `'Source can NOT be empty...'` (L266); (7)
TDESCI L270-L275 → `'Description can NOT be empty...'` (L272); (8)
TRNAMTI L276-L281 → `'Amount can NOT be empty...'` (L278); (9) TORIGDTI
L282-L287 → `'Orig Date can NOT be empty...'` (L284); (10) TPROCDTI
L288-L293 → `'Proc Date can NOT be empty...'` (L290); (11) MIDI
L294-L299 → `'Merchant ID can NOT be empty...'` (L296); (12) MNAMEI
L300-L305 → `'Merchant Name can NOT be empty...'` (L302); (13) MCITYI
L306-L311 → `'Merchant City can NOT be empty...'` (L308); (14) MZIPI
L312-L317 → `'Merchant Zip can NOT be empty...'` (L314).

Block 2 — Type/Category numeric `[app/cbl/COTRN02C.cbl:L322-L337]`:
(15) TTYPCDI NOT NUMERIC → `'Type CD must be Numeric...'` (L325); (16)
TCATCDI NOT NUMERIC → `'Category CD must be Numeric...'` (L331).

Block 3 — Amount regex `[app/cbl/COTRN02C.cbl:L339-L351]`. The COBOL
disjunction `TRNAMTI(1:1) NOT EQUAL '-' AND '+' OR (2:8) NOT NUMERIC
OR (10:1) NOT '.' OR (11:2) NOT NUMERIC` equals Java regex
`^[+-][0-9]{8}\.[0-9]{2}$` (sign required; 8 digits; dot; 2 digits).
PRESERVE VERBATIM per AAP §0.7.1. Message 17:
`'Amount should be in format -99999999.99'` (L345 — NO trailing ellipsis).

Block 4 — Orig Date format `[app/cbl/COTRN02C.cbl:L353-L366]`. Message
18: `'Orig Date should be in format YYYY-MM-DD'` (L360 — NO ellipsis).

Block 5 — Proc Date format `[app/cbl/COTRN02C.cbl:L368-L381]`. Message
19: `'Proc Date should be in format YYYY-MM-DD'` (L375 — NO ellipsis).

Amount normalization at `[app/cbl/COTRN02C.cbl:L383-L386]`: COMPUTE
WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI); MOVE to WS-TRAN-AMT-E
(re-edit with `+99999999.99` picture); MOVE back to TRNAMTI.

First CSUTLDTC CALL — ORIG date `[app/cbl/COTRN02C.cbl:L389-L407]`. IF
SEV-CD = `'0000'` → CONTINUE; ELSE IF MSG-NUM != `'2513'` → message
20: `'Orig Date - Not a valid date...'` (L401). `'2513'` is the only
documented soft-fail code; preserve verbatim. Second CSUTLDTC CALL —
PROC date `[app/cbl/COTRN02C.cbl:L409-L427]`. Same pattern. Message
21: `'Proc Date - Not a valid date...'` (L421). Merchant-ID numeric
check `[app/cbl/COTRN02C.cbl:L430-L436]`. Message 22: `'Merchant ID
must be Numeric...'` (L432).

**Total verbatim validation messages: 22** (3 key-field + 11 empty + 2
numeric + 3 format + 2 date-validity + 1 merchant-numeric). An upstream
agent reference cited 17 messages; the verified count from direct
inspection of `app/cbl/COTRN02C.cbl` is 22. Discrepancy noted in
`java/MIGRATION_NOTES.md` per AAP §0.7.5.

## Phase 6: ADD-TRANSACTION and WRITE-TRANSACT-FILE — Auto-Increment + Double-Space Success Message

ADD-TRANSACTION `[app/cbl/COTRN02C.cbl:L442-L466]`:
(1) Auto-increment Tran-ID at L444-L449: MOVE HIGH-VALUES TO TRAN-ID;
STARTBR; READPREV (or MOVE ZEROS on ENDFILE per empty-file fallback at
`[app/cbl/COTRN02C.cbl:L688-L689]`); ENDBR; MOVE TRAN-ID TO
WS-TRAN-ID-N; ADD 1.
(2) INITIALIZE TRAN-RECORD; 13 MOVE statements at L450-L465 (the 13
unprotected BMS fields plus auto-incremented Tran-ID).
(3) PERFORM WRITE-TRANSACT-FILE at L466.

TRAN-RECORD layout per `[app/cpy/CVTRA05Y.cpy:L4-L18]` (350 bytes total):
TRAN-ID `X(16)` bytes 1-16 (`[app/cpy/CVTRA05Y.cpy:L5]`); TRAN-TYPE-CD
`X(02)` bytes 17-18 (`[app/cpy/CVTRA05Y.cpy:L6]`); TRAN-CAT-CD `9(04)`
bytes 19-22 (`[app/cpy/CVTRA05Y.cpy:L7]`); TRAN-SOURCE `X(10)` bytes
23-32 (`[app/cpy/CVTRA05Y.cpy:L8]`); TRAN-DESC `X(100)` bytes 33-132
(`[app/cpy/CVTRA05Y.cpy:L9]`; BMS limits input to 60; space-padded);
TRAN-AMT `S9(09)V99` bytes 133-143 (`BigDecimal` scale 2 per AAP §0.6.1);
TRAN-MERCHANT-ID `9(09)` bytes 144-152 (`[app/cpy/CVTRA05Y.cpy:L11]`);
TRAN-MERCHANT-NAME `X(50)` bytes 153-202 (BMS limits to 30);
TRAN-MERCHANT-CITY `X(50)` bytes 203-252 (BMS limits to 25);
TRAN-MERCHANT-ZIP `X(10)` bytes 253-262; TRAN-CARD-NUM `X(16)` bytes
263-278; TRAN-ORIG-TS `X(26)` bytes 279-304 (BMS limits to 10;
space-padded); TRAN-PROC-TS `X(26)` bytes 305-330; FILLER `X(20)`
bytes 331-350.

WRITE-TRANSACT-FILE `[app/cbl/COTRN02C.cbl:L711-L749]`. DFHRESP(NORMAL)
at L724-L734: PERFORM INITIALIZE-ALL-FIELDS; MOVE DFHGREEN TO ERRMSGC.
STRING composition at L728-L732 concatenates `'Transaction added
successfully. '` (trailing space) + `' Your Tran ID is '` (leading
space) + TRAN-ID + `'.'` to produce verbatim `'Transaction added
successfully.  Your Tran ID is <id>.'` with TWO consecutive spaces
between the period and "Your". PRESERVE BYTE-PERFECT per AAP §0.7.1 —
single-space normalization is a defect. DFHRESP(DUPKEY)/DFHRESP(DUPREC) at L735-L741 → `'Tran ID
already exist...'` (L738). WHEN OTHER at L742-L748 → DISPLAY RESP/REAS
at L743 → `'Unable to Add Transaction...'` (L745).

COPY-LAST-TRAN-DATA `[app/cbl/COTRN02C.cbl:L471-L495]` is the PF5
handler: PERFORM VALIDATE-INPUT-KEY-FIELDS; STARTBR/READPREV/ENDBR with
HIGH-VALUES; IF NOT ERR-FLG-ON move 11 fields from TRAN-RECORD back to
corresponding BMS input fields at L482-L492; PERFORM PROCESS-ENTER-KEY
at L495 to re-validate.

## Phase 7: READ-CXACAIX-FILE and READ-CCXREF-FILE — CARDXREF AIX Lookup

READ-CXACAIX-FILE `[app/cbl/COTRN02C.cbl:L576-L604]` issues `EXEC CICS
READ DATASET(WS-CXACAIX-FILE) RIDFLD(XREF-ACCT-ID)` — alternate-index
read by account-ID. Branches: DFHRESP(NORMAL) → CONTINUE
(XREF-CARD-NUM populated); DFHRESP(NOTFND) → `'Account ID NOT found...'`
(L593); WHEN OTHER → DISPLAY at L598 → `'Unable to lookup Acct in XREF
AIX file...'` (L600).

READ-CCXREF-FILE `[app/cbl/COTRN02C.cbl:L609-L637]` issues `EXEC CICS
READ DATASET(WS-CCXREF-FILE) RIDFLD(XREF-CARD-NUM)` — primary-index
read by card-number. Branches: DFHRESP(NORMAL) → CONTINUE
(XREF-ACCT-ID populated); DFHRESP(NOTFND) → `'Card Number NOT found...'`
(L626); WHEN OTHER → DISPLAY at L631 → `'Unable to lookup Card # in
XREF file...'` (L633).

Java: `CardXrefRepository` port in `com.blitzy.carddemo.domain.port` with
`Optional<CardXrefRecord> findByAccountId(long)` (CXACAIX) and `findByCardNumber(String)`
(CCXREF); file-based adapter `com.blitzy.carddemo.adapter.file.FileCardXrefRepository`
per AAP §0.4.1. The Java logger MUST mask all but last 4 digits of any card number per AAP §0.7.2.

## Phase 8: STARTBR-/READPREV-/ENDBR-TRANSACT-FILE — Auto-Increment Browse

STARTBR-TRANSACT-FILE `[app/cbl/COTRN02C.cbl:L642-L668]` issues `EXEC
CICS STARTBR DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID)`. RIDFLD set by
callers to HIGH-VALUES at `[app/cbl/COTRN02C.cbl:L444,L475]`. Branches:
NORMAL → CONTINUE; NOTFND → `'Transaction ID NOT found...'` (L657);
WHEN OTHER → DISPLAY at L662 → `'Unable to lookup Transaction...'`
(L664).

READPREV-TRANSACT-FILE `[app/cbl/COTRN02C.cbl:L673-L697]` issues
READPREV INTO(TRAN-RECORD). Branches: NORMAL → CONTINUE (TRAN-RECORD
populated with last existing record); ENDFILE at L688-L689 → **MOVE
ZEROS TO TRAN-ID** (empty-file fallback — first add yields Tran-ID
`0000000000000001`); WHEN OTHER → DISPLAY at L691 → `'Unable to lookup
Transaction...'` (L693 — duplicate verbatim text shared with L664).

ENDBR-TRANSACT-FILE `[app/cbl/COTRN02C.cbl:L702-L706]` issues `EXEC
CICS ENDBR DATASET(WS-TRANSACT-FILE)` — fire-and-forget (no RESP
capture).

## Phase 9: SEND/RECEIVE/POPULATE-HEADER Paragraphs

SEND-TRNADD-SCREEN `[app/cbl/COTRN02C.cbl:L516-L534]`: PERFORM
POPULATE-HEADER-INFO at L518; MOVE WS-MESSAGE TO ERRMSGO at L520; EXEC
CICS SEND MAP(`'COTRN2A'`) MAPSET(`'COTRN02'`) FROM(COTRN2AO) ERASE
CURSOR at L522-L528; EXEC CICS RETURN TRANSID(WS-TRANID)
COMMAREA(CARDDEMO-COMMAREA) at L530-L534 — terminates the program
pseudo-conversationally; ALL error paths flow through SEND
unconditionally.

RECEIVE-TRNADD-SCREEN `[app/cbl/COTRN02C.cbl:L539-L547]`: EXEC CICS
RECEIVE MAP(`'COTRN2A'`) MAPSET(`'COTRN02'`) INTO(COTRN2AI) RESP RESP2
at L541-L547. The receive populates the `COTRN2AI` input record per
`[app/cpy-bms/COTRN02.CPY:§COTRN2AI]`. No EVALUATE on the response.

POPULATE-HEADER-INFO `[app/cbl/COTRN02C.cbl:L552-L571]`: MOVE FUNCTION
CURRENT-DATE TO WS-CURDATE-DATA at L554; MOVE CCDA-TITLE01 TO TITLE01O
at L556; MOVE CCDA-TITLE02 TO TITLE02O at L557; MOVE WS-TRANID TO
TRNNAMEO at L558; MOVE WS-PGMNAME TO PGMNAMEO at L559; format CURDATE
as `mm/dd/yy` at L561-L565; format CURTIME as `hh:mm:ss` at L567-L571.

Java: static helper in `com.blitzy.carddemo.application.util.ScreenAttributeSetter`
per AAP §0.4.1 using `java.time.LocalDateTime` plus `DateTimeFormatter.ofPattern("MM/dd/yy")`
/ `ofPattern("HH:mm:ss")`. The clock MUST be INJECTED via `ScopedValue<Clock>` per AAP §0.6.6
— NEVER `LocalDateTime.now()` — to keep `bms_output.txt` deterministic.

## Phase 10: BMS Map COTRN2A Layout — 24 × 80 Screen

The COTRN02 BMS mapset begins at `[app/bms/COTRN02.bms:L19]` with
`DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT
STORAGE=AUTO TIOAPFX=YES`. The map COTRN2A is declared at
`[app/bms/COTRN02.bms:L26-L28]` with `COLUMN=1 LINE=1 SIZE=(24,80)`.
Symbolic copybook `app/cpy-bms/COTRN02.CPY` defines `01 COTRN2AI` /
`01 COTRN2AO`, referenced via `COPY COTRN02.` at
`[app/cbl/COTRN02C.cbl:L82]`.

| Row/Field | Pos / Len | Attr | Color | Source |
|---|---|---|---|---|
| 1: `'Tran:'` / TRNNAME / TITLE01 / `'Date:'` / CURDATE (init `'mm/dd/yy'`) | (1,1)/5, (1,7)/4, (1,21)/40, (1,65)/5, (1,71)/8 | ASKIP+FSET | BLUE/YELLOW | `[app/bms/COTRN02.bms:L29-L51]` |
| 2: `'Prog:'` / PGMNAME / TITLE02 / `'Time:'` / CURTIME (init `'hh:mm:ss'`) | (2,1)/5, (2,7)/8, (2,21)/40, (2,65)/5, (2,71)/8 | ASKIP+FSET | BLUE/YELLOW | `[app/bms/COTRN02.bms:L52-L74]` |
| 4: `'Add Transaction'` | (4,30)/15 | ASKIP, BRT | NEUTRAL | `[app/bms/COTRN02.bms:L75-L79]` |
| 6: `'Enter Acct #:'` / **ACTIDIN (IC)** / `'(or)'` / `'Card #:'` / CARDNIN | (6,6)/13, (6,21)/11, (6,37)/4, (6,46)/7, (6,55)/16 | label ASKIP, input FSET IC NORM UNPROT UNDERLINE | TURQUOISE / GREEN / NEUTRAL | `[app/bms/COTRN02.bms:L80-L108]` |
| 8: dashes separator (70) | (8,6)/70 | ASKIP, NORM | NEUTRAL | `[app/bms/COTRN02.bms:L111-L116]` |
| 10: `'Type CD:'` / TTYPCD / `'Category CD:'` / TCATCD / `'Source:'` / TRNSRC | (10,6)/8, (10,15)/2, (10,23)/12, (10,36)/4, (10,46)/7, (10,54)/10 | label ASKIP, input FSET NORM UNPROT | TURQUOISE / GREEN | `[app/bms/COTRN02.bms:L117-L153]` |
| 12: `'Description:'` / TDESC | (12,6)/12, (12,19)/60 | label ASKIP, input FSET NORM UNPROT | TURQUOISE / GREEN | `[app/bms/COTRN02.bms:L156-L166]` |
| 14: `'Amount:'` / TRNAMT / `'Orig Date:'` / TORIGDT / `'Proc Date:'` / TPROCDT | (14,6)/7, (14,14)/12, (14,31)/10, (14,42)/10, (14,57)/10, (14,68)/10 | label ASKIP, input FSET NORM UNPROT | TURQUOISE / GREEN | `[app/bms/COTRN02.bms:L169-L205]` |
| 15: `'(-99999999.99)'` / `'(YYYY-MM-DD)'` x 2 | (15,13)/14, (15,41)/12, (15,67)/12 | ASKIP, NORM | BLUE | `[app/bms/COTRN02.bms:L208-L222]` |
| 16: `'Merchant ID:'` / MID / `'Merchant Name:'` / MNAME | (16,6)/12, (16,19)/9, (16,33)/14, (16,48)/30 | label ASKIP, input FSET NORM UNPROT | TURQUOISE / GREEN | `[app/bms/COTRN02.bms:L223-L246]` |
| 18: `'Merchant City:'` / MCITY / `'Merchant Zip:'` / MZIP | (18,6)/14, (18,21)/25, (18,53)/13, (18,67)/10 | label ASKIP, input FSET NORM UNPROT | TURQUOISE / GREEN | `[app/bms/COTRN02.bms:L249-L272]` |
| 21: confirm prompt / CONFIRM / `'(Y/N)'` | (21,6)/55, (21,63)/1, (21,66)/5 | label ASKIP, CONFIRM FSET NORM UNPROT | TURQUOISE / GREEN / NEUTRAL | `[app/bms/COTRN02.bms:L275-L292]` |
| 23: **ERRMSG** | (23,1)/78 | **ASKIP, BRT, FSET** | RED (DFHGREEN on success) | `[app/bms/COTRN02.bms:L293-L296]` |
| 24: footer | (24,1)/53 | ASKIP, NORM | YELLOW | `[app/bms/COTRN02.bms:L297-L302]` |

Row 24 footer text: `'ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last
Tran.'` per `[app/bms/COTRN02.bms:L301]`.

CRITICAL STRUCTURAL INVARIANTS: COTRN2A has NO PASSWD field anywhere
(contrast: COUSR01C/COUSR02C have PASSWD with DRK); IC marker is on
ACTIDIN at (6, 21) per `[app/bms/COTRN02.bms:L88]`; ERRMSG color is
dynamic (DFHGREEN on WRITE-NORMAL per `[app/cbl/COTRN02C.cbl:L727]`,
RED otherwise); footer accurately reflects 4 handled AID keys (no
mismatch); TRNNAME=`'CT02'` / PGMNAME=`'COTRN02C'` static constants per
`[app/cbl/COTRN02C.cbl:L36-L37]`; symbolic-map records `COTRN2AI` /
`COTRN2AO` per `[app/cpy-bms/COTRN02.CPY:§01-COTRN2AI,§01-COTRN2AO]`.

## Phase 11: Required Test Scenarios

| # | Scenario | AID | COBOL Source | Expected Output |
|---|---|---|---|---|
| 1 | Initial display (signed on; CDEMO-CT02-TRN-SELECTED blank) | (first pass) | `[app/cbl/COTRN02C.cbl:L120-L130]` | Empty fields; IC on ACTIDIN; no error |
| 2 | CXACAIX path: ACTIDIN populated → READ CXACAIX → CARDNIN auto-populated | DFHENTER | `[app/cbl/COTRN02C.cbl:L196-L209]` | CARDNIN filled from XREF |
| 3 | CCXREF path: CARDNIN populated → READ CCXREF → ACTIDIN auto-populated | DFHENTER | `[app/cbl/COTRN02C.cbl:L210-L223]` | ACTIDIN filled from XREF |
| 4 | ACTIDIN non-numeric | DFHENTER | `[app/cbl/COTRN02C.cbl:L197-L202]` | `'Account ID must be Numeric...'` (L199) |
| 5 | CARDNIN non-numeric | DFHENTER | `[app/cbl/COTRN02C.cbl:L211-L216]` | `'Card Number must be Numeric...'` (L213) |
| 6 | Both ACTIDIN and CARDNIN empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L224-L229]` | `'Account or Card Number must be entered...'` (L226) |
| 7 | TTYPCDI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L252-L257]` | `'Type CD can NOT be empty...'` (L254) |
| 8 | TCATCDI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L258-L263]` | `'Category CD can NOT be empty...'` (L260) |
| 9 | TRNSRCI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L264-L269]` | `'Source can NOT be empty...'` (L266) |
| 10 | TDESCI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L270-L275]` | `'Description can NOT be empty...'` (L272) |
| 11 | TRNAMTI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L276-L281]` | `'Amount can NOT be empty...'` (L278) |
| 12 | TORIGDTI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L282-L287]` | `'Orig Date can NOT be empty...'` (L284) |
| 13 | TPROCDTI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L288-L293]` | `'Proc Date can NOT be empty...'` (L290) |
| 14 | MIDI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L294-L299]` | `'Merchant ID can NOT be empty...'` (L296) |
| 15 | MNAMEI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L300-L305]` | `'Merchant Name can NOT be empty...'` (L302) |
| 16 | MCITYI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L306-L311]` | `'Merchant City can NOT be empty...'` (L308) |
| 17 | MZIPI empty | DFHENTER | `[app/cbl/COTRN02C.cbl:L312-L317]` | `'Merchant Zip can NOT be empty...'` (L314) |
| 18 | TTYPCDI non-numeric | DFHENTER | `[app/cbl/COTRN02C.cbl:L323-L328]` | `'Type CD must be Numeric...'` (L325) |
| 19 | TCATCDI non-numeric | DFHENTER | `[app/cbl/COTRN02C.cbl:L329-L334]` | `'Category CD must be Numeric...'` (L331) |
| 20 | TRNAMTI bad format | DFHENTER | `[app/cbl/COTRN02C.cbl:L340-L348]` | `'Amount should be in format -99999999.99'` (L345 — NO trailing ellipsis) |
| 21 | TORIGDTI bad format | DFHENTER | `[app/cbl/COTRN02C.cbl:L354-L363]` | `'Orig Date should be in format YYYY-MM-DD'` (L360 — NO trailing ellipsis) |
| 22 | TPROCDTI bad format | DFHENTER | `[app/cbl/COTRN02C.cbl:L369-L378]` | `'Proc Date should be in format YYYY-MM-DD'` (L375 — NO trailing ellipsis) |
| 23 | TORIGDTI invalid date (SEV-CD != '0000' AND MSG-NUM != '2513') | DFHENTER | `[app/cbl/COTRN02C.cbl:L389-L407]` | `'Orig Date - Not a valid date...'` (L401) |
| 24 | TPROCDTI invalid date | DFHENTER | `[app/cbl/COTRN02C.cbl:L409-L427]` | `'Proc Date - Not a valid date...'` (L421) |
| 25 | MIDI non-numeric | DFHENTER | `[app/cbl/COTRN02C.cbl:L430-L436]` | `'Merchant ID must be Numeric...'` (L432) |
| 26 | CONFIRMI = blank/N/n | DFHENTER | `[app/cbl/COTRN02C.cbl:L173-L181]` | `'Confirm to add this transaction...'` (L178) |
| 27 | CONFIRMI = invalid (`'X'`) | DFHENTER | `[app/cbl/COTRN02C.cbl:L182-L187]` | `'Invalid value. Valid values are (Y/N)...'` (L184) |
| 28 | All valid + CONFIRM=`'Y'` → WRITE NORMAL | DFHENTER | `[app/cbl/COTRN02C.cbl:L442-L466,L724-L734]` | `'Transaction added successfully.  Your Tran ID is <id>.'` (L728-L732, double-space); transact.txt +350 bytes |
| 29 | WRITE returns DUPKEY/DUPREC | DFHENTER | `[app/cbl/COTRN02C.cbl:L735-L741]` | `'Tran ID already exist...'` (L738) |
| 30 | PF5 COPY-LAST-TRAN-DATA | DFHPF5 | `[app/cbl/COTRN02C.cbl:L471-L495]` | 11 fields pre-populated from last transaction |
| 31 | PF4 CLEAR | DFHPF4 | `[app/cbl/COTRN02C.cbl:L754-L757]` | All fields re-initialized |
| 32 | PF3 BACK | DFHPF3 | `[app/cbl/COTRN02C.cbl:L137-L143]` | XCTL to CDEMO-FROM-PROGRAM (default `'COMEN01C'`) |
| 33 | OTHER AID (e.g., PF12) | (other) | `[app/cbl/COTRN02C.cbl:L148-L151]` | `'Invalid key pressed. Please see below...         '` (CCDA-MSG-INVALID-KEY) |
| 34 | Auto-trigger from COTRN00C | (first pass) | `[app/cbl/COTRN02C.cbl:L124-L129]` | CARDNINI auto-populated; auto-PERFORM PROCESS-ENTER-KEY |

Each scenario MUST be re-validated against captured BMS output once
`bms_output.txt` is replaced with a real capture per
`java/MIGRATION_NOTES.md` §1.6.

## Phase 12: Required Fixture Files in This Directory

1. **`README.md`** (THIS file; CREATED). 13-phase authoritative contract.
2. **`input_scenario.txt`** (TEST-OWNED; CREATED). Multi-scenario harness
   directive script (line-oriented; `#` comments). Documents the
   sequential 3270 interactions enumerated in Phase 11. Source-grounded
   against COBOL line numbers. Consumed by `inputFile()` override on
   `CoTrn02CGoldenTest`.
3. **`transact.txt`** (CAPTURE PLACEHOLDER; replaced per
   `MIGRATION_NOTES.md` §1.6). Initial-state TRANSACT VSAM KSDS — 350-byte
   fixed-width records per `[app/cpy/CVTRA05Y.cpy:L4-L18]`. Sort: TRAN-ID
   ascending. After successful WRITE the file byte length grows by 350.
   Capture procedure MAY split into `transact.txt` (initial) +
   `transact_after.txt` (post-WRITE).
4. **`stdout.txt`** (CAPTURE PLACEHOLDER). Captured DISPLAY output.
   COTRN02C contains 5 active DISPLAY statements emitting `'RESP:'
   WS-RESP-CD 'REAS:' WS-REAS-CD` at L598 (CXACAIX WHEN OTHER), L631
   (CCXREF WHEN OTHER), L662 (STARTBR WHEN OTHER), L691 (READPREV WHEN
   OTHER), L743 (WRITE WHEN OTHER). Under successful scenarios `stdout.txt`
   is empty. CRITICAL per AAP §0.7.2: NO full PAN — mask all but last 4.
5. **`bms_output.txt`** (CAPTURE PLACEHOLDER). Serialized BMS SEND MAP
   output. Deterministic format per `MIGRATION_NOTES.md` §1.6 — field
   colors, attributes, IC marker on ACTIDIN, ERRMSG color (DFHGREEN/RED),
   CURDATE/CURTIME from fixed-clock per AAP §0.6.6.

Read-only ASCII fixtures (NOT copied — read via classpath per AAP §0.4.1):
`app/data/ASCII/cardxref.txt` (CARDXREF, REFERENCE only);
`app/data/ASCII/acctdata.txt` (ACCTDATA, REFERENCE only);
`app/data/ASCII/dailytran.txt` (DALYTRAN, REFERENCE only). These remain
in `app/` per AAP §0.1.1.

## Phase 13: Structural Invariants

### 13.1 BMS Map Invariants

- COTRN2A has exactly 13 unprotected fields + 1 CONFIRM field (14
  user-editable positions total). IC marker is on ACTIDIN at (6, 21)
  per `[app/bms/COTRN02.bms:L88]`. ERRMSG at (23, 1) length 78 with
  dynamic color (DFHGREEN on WRITE-NORMAL per
  `[app/cbl/COTRN02C.cbl:L727]`, RED otherwise).
- Footer at (24, 1) length 53 advertises `'ENTER=Continue  F3=Back
  F4=Clear  F5=Copy Last Tran.'` per `[app/bms/COTRN02.bms:L297-L302]`
  — accurately reflects 4 named AID keys handled.
- TRNNAME=`'CT02'` / PGMNAME=`'COTRN02C'` static constants per
  `[app/cbl/COTRN02C.cbl:L36-L37]`. NO PASSWD field on map (verified
  by inspection of `[app/bms/COTRN02.bms:L19-L302]`). CURDATE format
  `mm/dd/yy`; CURTIME format `hh:mm:ss` per
  `[app/cbl/COTRN02C.cbl:L561-L571]`.

### 13.2 TRANSACT File Invariants

- 350-byte fixed-width records per `[app/cpy/CVTRA05Y.cpy:L4-L18]`;
  sort: ascending by TRAN-ID (16 bytes, key field).
- WRITE-appends-record with auto-incremented TRAN-ID: ADD-TRANSACTION
  at `[app/cbl/COTRN02C.cbl:L442-L466]` reads the highest existing
  TRAN-ID via STARTBR/READPREV/ENDBR, adds 1, then WRITEs. Per
  empty-file fallback at `[app/cbl/COTRN02C.cbl:L688-L689]`, an empty
  TRANSACT yields TRAN-ID `0000000000000001` on first WRITE.
- **After successful WRITE: `transact.txt` byte delta = +350 bytes.**
  Existing records preserved verbatim per AAP §0.7.1.

### 13.3 Dual CSUTLDTC Date Validation Invariants

- Two sequential CSUTLDTC CALLs — ORIG at `[app/cbl/COTRN02C.cbl:L389-L407]`,
  PROC at `[app/cbl/COTRN02C.cbl:L409-L427]`; CSUTLDTC-PARM at
  `[app/cbl/COTRN02C.cbl:L62-L69]`. Success: SEV-CD = `'0000'`. Soft-fail:
  SEV-CD != `'0000'` AND MSG-NUM = `'2513'` (continue) — `'2513'` is the
  ONLY documented soft-fail code; preserve verbatim. Hard-fail: emit error
  and SEND. Java: `DateValidator` (from `app/cbl/CSUTLDTC.cbl` per AAP §0.4.1)
  using `LocalDate.parse` with `DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)`.

### 13.4 CARDXREF AIX Lookup Invariants

- Two cross-reference paths in VALIDATE-INPUT-KEY-FIELDS EVALUATE TRUE at
  `[app/cbl/COTRN02C.cbl:L195]`: ACTIDINI populated → READ CXACAIX (alt
  index) at L208 → CARDNINI auto-populated at L209; CARDNINI populated →
  READ CCXREF (primary index) at L222 → ACTIDINI auto-populated at L223;
  both empty → error at L226. Java: `CardXrefRepository` port with
  `findByAccountId(long)` and `findByCardNumber(String)` per AAP §0.4.1.

### 13.5 Java Mapping Invariants

- Class under test: `com.blitzy.carddemo.application.transaction.CoTrn02C`;
  test class: `com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest` (no
  `transaction` subpackage on test classes — matches sibling pattern).
  BMS map → DTO record pair `CoTrn02Input` + `CoTrn02Output` per AAP §0.4.1.
- TRAN-AMT: `BigDecimal` scale 2; `MathContext.DECIMAL128`; explicit
  `RoundingMode.HALF_EVEN` for `ROUNDED` per AAP §0.6.1. Dates/times use
  `java.time` per §0.6.4; file I/O uses `java.nio.file` per §0.6.5;
  `ScopedValue` (fixed-clock injection) per §0.6.6.
- Every `EVALUATE` translates to pattern-matching `switch` with NO
  `default` branch per AAP §0.7.4. Exhaustiveness guaranteed by sealing
  the discriminator (`AidKey`, `ConfirmValue`, `FileStatus`).
- Preserve all 22 verbatim validation error messages, the double-space
  in `'Transaction added successfully.  Your Tran ID is <id>.'`, amount
  regex `^[+-][0-9]{8}\.[0-9]{2}$`, sort order (TRAN-ID ascending), and
  FIRST-empty-wins validation ordering — all per AAP §0.7.1. No card-number
  PAN logged in full — mask all but last 4 digits per AAP §0.7.2.

## Verbatim COBOL Message Catalog

The Java implementation MUST emit these strings EXACTLY (AAP §0.7.1).
NEVER paraphrase; NEVER replace `...` with Unicode ellipsis; NEVER
alter punctuation or spacing. PRESERVE double-space in message #34.

| # | Message (verbatim) | COBOL Line | Category |
|---|---|---|---|
| 1 | `'Account ID must be Numeric...'` | `[app/cbl/COTRN02C.cbl:L199]` | key-field |
| 2 | `'Card Number must be Numeric...'` | `[app/cbl/COTRN02C.cbl:L213]` | key-field |
| 3 | `'Account or Card Number must be entered...'` | `[app/cbl/COTRN02C.cbl:L226]` | key-field |
| 4 | `'Type CD can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L254]` | empty |
| 5 | `'Category CD can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L260]` | empty |
| 6 | `'Source can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L266]` | empty |
| 7 | `'Description can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L272]` | empty |
| 8 | `'Amount can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L278]` | empty |
| 9 | `'Orig Date can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L284]` | empty |
| 10 | `'Proc Date can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L290]` | empty |
| 11 | `'Merchant ID can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L296]` | empty |
| 12 | `'Merchant Name can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L302]` | empty |
| 13 | `'Merchant City can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L308]` | empty |
| 14 | `'Merchant Zip can NOT be empty...'` | `[app/cbl/COTRN02C.cbl:L314]` | empty |
| 15 | `'Type CD must be Numeric...'` | `[app/cbl/COTRN02C.cbl:L325]` | numeric |
| 16 | `'Category CD must be Numeric...'` | `[app/cbl/COTRN02C.cbl:L331]` | numeric |
| 17 | `'Amount should be in format -99999999.99'` | `[app/cbl/COTRN02C.cbl:L345]` | format (NO trailing ellipsis) |
| 18 | `'Orig Date should be in format YYYY-MM-DD'` | `[app/cbl/COTRN02C.cbl:L360]` | format (NO trailing ellipsis) |
| 19 | `'Proc Date should be in format YYYY-MM-DD'` | `[app/cbl/COTRN02C.cbl:L375]` | format (NO trailing ellipsis) |
| 20 | `'Orig Date - Not a valid date...'` | `[app/cbl/COTRN02C.cbl:L401]` | date-validity |
| 21 | `'Proc Date - Not a valid date...'` | `[app/cbl/COTRN02C.cbl:L421]` | date-validity |
| 22 | `'Merchant ID must be Numeric...'` | `[app/cbl/COTRN02C.cbl:L432]` | numeric |
| 23 | `'Confirm to add this transaction...'` | `[app/cbl/COTRN02C.cbl:L178]` | confirm |
| 24 | `'Invalid value. Valid values are (Y/N)...'` | `[app/cbl/COTRN02C.cbl:L184]` | confirm |
| 25 | `'Account ID NOT found...'` | `[app/cbl/COTRN02C.cbl:L593]` | file-IO |
| 26 | `'Unable to lookup Acct in XREF AIX file...'` | `[app/cbl/COTRN02C.cbl:L600]` | file-IO |
| 27 | `'Card Number NOT found...'` | `[app/cbl/COTRN02C.cbl:L626]` | file-IO |
| 28 | `'Unable to lookup Card # in XREF file...'` | `[app/cbl/COTRN02C.cbl:L633]` | file-IO |
| 29 | `'Transaction ID NOT found...'` | `[app/cbl/COTRN02C.cbl:L657]` | file-IO |
| 30 | `'Unable to lookup Transaction...'` | `[app/cbl/COTRN02C.cbl:L664]` | file-IO |
| 31 | `'Unable to lookup Transaction...'` (same text as #30) | `[app/cbl/COTRN02C.cbl:L693]` | file-IO |
| 32 | `'Tran ID already exist...'` | `[app/cbl/COTRN02C.cbl:L738]` | file-IO |
| 33 | `'Unable to Add Transaction...'` | `[app/cbl/COTRN02C.cbl:L745]` | file-IO |
| 34 | `'Transaction added successfully.  Your Tran ID is <16-char-tran-id>.'` (**DOUBLE SPACE** between "successfully." and "Your") | `[app/cbl/COTRN02C.cbl:L728-L732]` | success |
| 35 | `'Invalid key pressed. Please see below...         '` (50 chars incl. 9 trailing spaces) | `[app/cpy/CSMSG01Y.cpy:L20-L21]` | CCDA-MSG-INVALID-KEY |

Message #34 is composed by STRING at `[app/cbl/COTRN02C.cbl:L728-L732]`
with four literals: `'Transaction added successfully. '` (trailing
space) + `' Your Tran ID is '` (leading space) + TRAN-ID + `'.'` →
yields the verbatim sequence with TWO consecutive spaces between the
period and "Your". Single-space normalization in Java is a defect.

## Capture Procedure Cross-Reference

To replace `transact.txt`, `stdout.txt`, and `bms_output.txt` with
captured COBOL artifacts, follow the procedure in
`java/MIGRATION_NOTES.md` §1.6 (per AAP §0.7.5). Until those captures
are committed, `CoTrn02CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Source Lineage

- `app/cbl/COTRN02C.cbl` (783 lines) — COBOL source. Paragraph inventory in Phases 3-9.
- `app/cbl/CSUTLDTC.cbl` — CEEDAYS date-validity wrapper; called twice at `[app/cbl/COTRN02C.cbl:L389-L407,L409-L427]`.
- `app/bms/COTRN02.bms` (307 lines) — BMS map definition; field layout in Phase 10.
- `app/cpy-bms/COTRN02.CPY` — symbolic map copybook (`01 COTRN2AI` / `01 COTRN2AO`), referenced via `COPY COTRN02.` at `[app/cbl/COTRN02C.cbl:L82]`. Java generates DTO records `CoTrn02Input` / `CoTrn02Output` field-for-field from `[app/cpy-bms/COTRN02.CPY:§COTRN2AI,§COTRN2AO]`.
- `app/cpy/CVTRA05Y.cpy` (21 lines) — 350-byte TRAN-RECORD layout referenced by `transact.txt`.
- `app/cpy/COCOM01Y.cpy` — base `CARDDEMO-COMMAREA` `[app/cpy/COCOM01Y.cpy:L19-L44]`; extended inline by COTRN02C at `[app/cbl/COTRN02C.cbl:L72-L80]` with CDEMO-CT02-INFO.
- `app/cpy/CSMSG01Y.cpy` — `CCDA-MSG-INVALID-KEY` `[app/cpy/CSMSG01Y.cpy:L20-L21]` referenced by WHEN OTHER at `[app/cbl/COTRN02C.cbl:L150]`.
- `app/data/ASCII/cardxref.txt`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/dailytran.txt` — read-only fixtures (REFERENCE only, NOT copied per AAP §0.4.1).

## Cross-References

- Sibling `input/` README: `java/carddemo-tests/src/test/resources/golden/cotrn02c/input/README.md`
- Test class: `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoTrn02CGoldenTest.java`
- Base class: `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`
- Class under test: `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CoTrn02C.java`
- Input DTO: `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CoTrn02Input.java`
- Output DTO: `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CoTrn02Output.java`
- Domain record (from CVTRA05Y): `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java`
- TRANSACT port: `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/TransactionRepository.java`
- CARDXREF port: `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/CardXrefRepository.java`
- File-based adapters: `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileTransactionRepository.java`, `.../FileCardXrefRepository.java`
- Related fixtures: `java/carddemo-tests/src/test/resources/golden/cousr01c/`, `.../cousr02c/`, `.../cousr03c/`, `.../csutldtc/`
- Capture procedure: `java/MIGRATION_NOTES.md` §1.6

## Authority References

AAP §0.1.1, §0.2.1, §0.2.2, §0.3.1, §0.4.1, §0.6.1, §0.6.4, §0.6.5,
§0.6.6, §0.6.11, §0.6.12, §0.7.1, §0.7.2, §0.7.4, §0.7.5.

## Contrast Matrix: COTRN02C vs Sibling User-Management Programs

| Aspect | COTRN02C (Add Transaction) | COUSR01C (Add User) | COUSR02C (Update User) | COUSR03C (Delete User) |
|---|---|---|---|---|
| Transaction ID | CT02 | CU01 | CU02 | CU03 |
| Target dataset | TRANSACT VSAM KSDS | USRSEC | USRSEC | USRSEC |
| File operation | WRITE (auto-increment TRAN-ID) | WRITE (user-supplied SEC-USR-ID) | READ + REWRITE | READ + DELETE |
| Editable BMS fields | **13 unprotected + 1 CONFIRM** | 5 | 5 | 1 |
| Initial cursor (IC) | ACTIDIN (6, 21) | FNAME (8, 18) | FNAME (8, 18) | USRIDIN |
| AID keys handled | **4 named (ENTER, PF3, PF4, PF5) + OTHER** | 3 | 6 | 5 |
| Verbatim validation errors | **22 (most complex)** | 5 | 11 | 6 |
| Dual CSUTLDTC date validation | **YES (ORIG + PROC)** | NO | NO | NO |
| CARDXREF AIX lookup | **YES (CXACAIX + CCXREF)** | NO | NO | NO |
| Auto-trigger | **YES (from COTRN00C row 'A')** | NO | YES | YES |
| Success message double-space | **YES — `'successfully.  Your Tran ID is'`** | NO | NO | NO |
| Amount regex | **`^[+-][0-9]{8}\.[0-9]{2}$`** | N/A | N/A | N/A |
| DRK password field on map | NO | YES | YES | NO |
| Footer-vs-code mismatch | NO | YES (F12=Exit advertised) | varies | NO |
| Active COBOL DISPLAYs | **5 (inside WHEN OTHER paths)** | 0 | 0 | 0 |
| Record byte length | 350 | 80 | 80 | 80 |
| Post-WRITE byte delta | +350 | +80 | 0 | -80 |
| Total program lines | **783 (most complex)** | ~360 | ~400 | ~360 |

## DO NOT Modify Without Re-Capture

```
This contract is the authoritative specification for the COTRN02C golden-record
test fixture. Any change to a fixture file, scenario count, message verbatim,
double-space preservation, amount regex, validation ordering, sort order, or BMS
map invariant MUST be accompanied by a corresponding change to this README and
re-captured transact.txt, stdout.txt, and bms_output.txt artifacts. See AAP
section 0.7.1 (Minimal Change Clause) and AAP section 0.6.11 (PR gate).

Protective rules:
- DO NOT remove trailing spaces in ERRMSG (78-char space-padded).
- DO NOT normalize record terminators in transact.txt.
- DO NOT collapse the double-space in success message #34.
- DO NOT change validation ordering (preserve FIRST-empty-wins source order).
- DO NOT use Unicode ellipsis (replace U+2026 with three ASCII periods).
- DO NOT add a PF12 handler (none exists in COBOL source).
- DO NOT modify the amount regex.
- DO NOT log full card PAN; mask all but last 4 digits per AAP section 0.7.2.
```
