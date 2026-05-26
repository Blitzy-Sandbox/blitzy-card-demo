# COTRN01C (Transaction View, CT01) — Golden-Record `expected/` Fixture Contract

## Phase 0 — Header & Authority Cascade

This README is the authoritative contract document for the COTRN01C golden-record test fixture residing at `java/carddemo-tests/src/test/resources/golden/cotrn01c/expected/`. It binds the captured COBOL baseline artifacts in this directory to the Java translation under test, the test harness that asserts byte-level parity, and the unchanged COBOL source files under `app/` from which every fact in this document is derived.

Authority cascade (binding): AAP §0.6.11 mandates byte-for-byte golden-record parity as the non-negotiable PR gate; AAP §0.7.1 (Minimal Change Clause) governs faithful translation including the preservation of every observable quirk; AAP §0.1.1 and §0.2.2 fix the COBOL source tree as the immutable reference implementation. This README cites only source files that exist under `app/`, with line locators derived from direct file inspection.

Program identification: COBOL `PROGRAM-ID. COTRN01C` at `app/cbl/COTRN01C.cbl:L23`; CICS transaction `CT01` (`WS-TRANID PIC X(04) VALUE 'CT01'` at `app/cbl/COTRN01C.cbl:L37`); BMS map `COTRN1A` in mapset `COTRN01` (`app/bms/COTRN01.bms:L19,L26`); symbolic copybook `app/cpy-bms/COTRN01.CPY` (272 lines); record layout `app/cpy/CVTRA05Y.cpy` (21 lines, 350-byte TRAN-RECORD). Function: view a single transaction record from the TRANSACT VSAM KSDS by primary key TRAN-ID.

Java translation: the COTRN01C program maps to `com.blitzy.carddemo.application.transaction.CoTrn01C` per AAP §0.4.1 (transaction subpackage). The class carries a `@CobolProgram("COTRN01C")` Javadoc-style annotation citing the original PROGRAM-ID, the source path `app/cbl/COTRN01C.cbl`, and the translation date per AAP §0.7.1.

Java test class: `com.blitzy.carddemo.tests.golden.CoTrn01CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test class MUST carry `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until captured artifacts replace the placeholders in this directory.

This README binds 5 files in this directory: `README.md` (this document), `input_scenario.txt` (the 10-scenario CICS pseudo-conversation driver), `transact.txt` (initial TRANSACT state, 350-byte fixed-width records, ASCII), `stdout.txt` (captured COBOL DISPLAY output — expected empty under happy-path scenarios), and `bms_output.txt` (serialized BMS SEND MAP frames, one per scenario).

## Phase 1 — COBOL Source: PROGRAM-ID and WS-VARIABLES

The source file `app/cbl/COTRN01C.cbl` is 330 lines total. The IDENTIFICATION DIVISION begins at `app/cbl/COTRN01C.cbl:L22`; the PROGRAM-ID declaration appears at L23; the AUTHOR clause `AWS.` at L24. The WORKING-STORAGE SECTION (L33-L72) declares the program's state variables, four COPY directives that resolve to record layouts and constant catalogues, and the inline CDEMO-CT01-INFO commarea extension documented in Phase 2.

WS-VARIABLES table:

| WS Variable | Source Line | PIC Clause | Purpose |
|---|---|---|---|
| `WS-PGMNAME` | `app/cbl/COTRN01C.cbl:L36` | `X(08) VALUE 'COTRN01C'` | Self-identification, written to CDEMO-FROM-PROGRAM before XCTL |
| `WS-TRANID` | `app/cbl/COTRN01C.cbl:L37` | `X(04) VALUE 'CT01'` | CICS transaction ID for EXEC CICS RETURN at L137 |
| `WS-MESSAGE` | `app/cbl/COTRN01C.cbl:L38` | `X(80) VALUE SPACES` | Error/info message routed to ERRMSGO on every SEND |
| `WS-TRANSACT-FILE` | `app/cbl/COTRN01C.cbl:L39` | `X(08) VALUE 'TRANSACT'` | Dataset name passed to EXEC CICS READ at L270 |
| `WS-ERR-FLG` | `app/cbl/COTRN01C.cbl:L40-L42` | `X(01) VALUE 'N'` with 88 ERR-FLG-ON='Y' / ERR-FLG-OFF='N' | Gates the post-validation populate sequence |
| `WS-RESP-CD` | `app/cbl/COTRN01C.cbl:L43` | `S9(09) COMP VALUE ZEROS` | CICS RESP capture (READ and RECEIVE) |
| `WS-REAS-CD` | `app/cbl/COTRN01C.cbl:L44` | `S9(09) COMP VALUE ZEROS` | CICS RESP2 capture |
| `WS-USR-MODIFIED` | `app/cbl/COTRN01C.cbl:L45-L47` | `X(01) VALUE 'N'` with 88 USR-MODIFIED-YES='Y' / USR-MODIFIED-NO='N' | Declared but unreferenced in PROCEDURE DIVISION — preserve faithfully |
| `WS-TRAN-AMT` | `app/cbl/COTRN01C.cbl:L49` | `+99999999.99` (signed display) | Sign-edited image of TRAN-AMT for display in TRNAMTI |
| `WS-TRAN-DATE` | `app/cbl/COTRN01C.cbl:L50` | `X(08) VALUE '00/00/00'` | Declared but unreferenced — preserve faithfully |

## Phase 2 — Commarea: CARDDEMO-COMMAREA + CDEMO-CT01-INFO Inline Extension

`CARDDEMO-COMMAREA` is defined in `app/cpy/COCOM01Y.cpy:L19-L44` and brought into COTRN01C via `COPY COCOM01Y` at `app/cbl/COTRN01C.cbl:L52`. The base structure carries CDEMO-GENERAL-INFO (FROM-TRANID, FROM-PROGRAM, TO-TRANID, TO-PROGRAM, USER-ID, USER-TYPE with 88-levels CDEMO-USRTYP-ADMIN='A' / CDEMO-USRTYP-USER='U', PGM-CONTEXT with 88-levels CDEMO-PGM-ENTER=0 / CDEMO-PGM-REENTER=1), CDEMO-CUSTOMER-INFO, CDEMO-ACCOUNT-INFO, CDEMO-CARD-INFO, and CDEMO-MORE-INFO.

The `CDEMO-CT01-INFO` sub-group is NOT defined inside `COCOM01Y.cpy` itself — it is appended INLINE in COTRN01C.cbl at L53-L61, immediately after the `COPY COCOM01Y.` directive at L52. This inline-append pattern mirrors the convention used by sibling online programs (CDEMO-CT00-INFO in COTRN00C, CDEMO-CT02-INFO in COTRN02C, CDEMO-CU00-INFO in COUSR00C). The Java translation places CDEMO-CT01-INFO fields on the same Java commarea record as the other CDEMO-*-INFO sub-groups; the inline append does not require a separate domain record.

CDEMO-CT01-INFO inline sub-fields:

| Sub-Field | Source Line | PIC + Purpose |
|---|---|---|
| `CDEMO-CT01-TRNID-FIRST` | `app/cbl/COTRN01C.cbl:L54` | `PIC X(16)` — page-first transaction ID maintained by COTRN00C list paging |
| `CDEMO-CT01-TRNID-LAST` | `app/cbl/COTRN01C.cbl:L55` | `PIC X(16)` — page-last transaction ID maintained by COTRN00C list paging |
| `CDEMO-CT01-PAGE-NUM` | `app/cbl/COTRN01C.cbl:L56` | `PIC 9(08)` — current page number for COTRN00C list paging |
| `CDEMO-CT01-NEXT-PAGE-FLG` | `app/cbl/COTRN01C.cbl:L57-L59` | `PIC X(01) VALUE 'N'` with 88 NEXT-PAGE-YES='Y' / NEXT-PAGE-NO='N' |
| `CDEMO-CT01-TRN-SEL-FLG` | `app/cbl/COTRN01C.cbl:L60` | `PIC X(01)` — list-row selection flag set by COTRN00C |
| `CDEMO-CT01-TRN-SELECTED` | `app/cbl/COTRN01C.cbl:L61` | `PIC X(16)` — auto-trigger field consumed by COTRN01C at L103-L108 |

COTRN01C consumes `CDEMO-CT01-TRN-SELECTED` at L103-L108 (the auto-trigger path documented in Phase 4.4) but does not write to the other CDEMO-CT01-INFO sub-fields. TRNID-FIRST, TRNID-LAST, PAGE-NUM, NEXT-PAGE-FLG, and TRN-SEL-FLG are populated by COTRN00C (the upstream Transaction List program) and pass through the commarea unchanged when COTRN01C is invoked.

## Phase 3 — MAIN-PARA: Pseudo-Conversational Dispatch

MAIN-PARA at `app/cbl/COTRN01C.cbl:L86` is the program's single entry paragraph. It resets WS-ERR-FLG and WS-USR-MODIFIED at L88-L89, clears WS-MESSAGE and ERRMSGO at L91-L92, then routes execution along three mutually exclusive paths discriminated by EIBCALEN and CDEMO-PGM-REENTER.

### 3.1 First-time entry (EIBCALEN = 0)

At L94-L96, when no commarea is passed (the program is invoked cold-start by the CICS region), MAIN-PARA moves `'COSGN00C'` to CDEMO-TO-PROGRAM and PERFORMs RETURN-TO-PREV-SCREEN to XCTL back to the signon program. COTRN01C cannot operate without a commarea; first-time direct entry redirects to signon to establish the user-context envelope.

### 3.2 First reentry pass (NOT CDEMO-PGM-REENTER)

At L97-L98, MAIN-PARA copies `DFHCOMMAREA(1:EIBCALEN)` into CARDDEMO-COMMAREA. The first-reentry guard at L99-L109 detects the initial pass after upstream XCTL:

```cobol
IF NOT CDEMO-PGM-REENTER
    SET CDEMO-PGM-REENTER    TO TRUE
    MOVE LOW-VALUES          TO COTRN1AO
    MOVE -1       TO TRNIDINL OF COTRN1AI
    IF CDEMO-CT01-TRN-SELECTED NOT =
                               SPACES AND LOW-VALUES
        MOVE CDEMO-CT01-TRN-SELECTED TO
             TRNIDINI OF COTRN1AI
        PERFORM PROCESS-ENTER-KEY
    END-IF
    PERFORM SEND-TRNVIEW-SCREEN
```

The first-reentry pass is where the auto-trigger from COTRN00C is detected: if CDEMO-CT01-TRN-SELECTED carries a populated transaction ID, the program synthesizes an ENTER-key invocation by moving the value to TRNIDINI and PERFORMing PROCESS-ENTER-KEY before the final SEND. This is the only path that performs PROCESS-ENTER-KEY without an explicit user keystroke.

### 3.3 Subsequent reentry pass (EIBAID dispatch)

When CDEMO-PGM-REENTER is already TRUE, MAIN-PARA reads the screen via RECEIVE-TRNVIEW-SCREEN at L111 and dispatches on EIBAID at L112-L132:

```cobol
EVALUATE EIBAID
    WHEN DFHENTER
        PERFORM PROCESS-ENTER-KEY
    WHEN DFHPF3
        IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
            MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
        ELSE
            MOVE CDEMO-FROM-PROGRAM TO
            CDEMO-TO-PROGRAM
        END-IF
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN DFHPF4
        PERFORM CLEAR-CURRENT-SCREEN
    WHEN DFHPF5
        MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM
        PERFORM RETURN-TO-PREV-SCREEN
    WHEN OTHER
        MOVE 'Y'                       TO WS-ERR-FLG
        MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
        PERFORM SEND-TRNVIEW-SCREEN
END-EVALUATE
```

COTRN01C handles EXACTLY 4 named AID keys (DFHENTER, DFHPF3, DFHPF4, DFHPF5) plus the catch-all WHEN OTHER. There is no PF7/PF8 (no pagination — COTRN01C displays a single record, not a list). PF3 has a dual destination depending on whether CDEMO-FROM-PROGRAM is populated (branch to the caller) or empty (default to the main menu COMEN01C). PF5 always XCTLs back to COTRN00C (the transaction-list program). The WHEN OTHER branch routes invalid AID keys to a re-display with the shared error message CCDA-MSG-INVALID-KEY.

### 3.4 EXEC CICS RETURN

At L136-L139, after all three dispatch paths complete, MAIN-PARA issues `EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)`. This is the pseudo-conversational return that releases the CICS task; the next user keystroke re-invokes COTRN01C with the updated commarea, and CDEMO-PGM-REENTER is already TRUE on that re-entry.

## Phase 4 — PROCESS-ENTER-KEY: TRNID Validation + Auto-Trigger Path

PROCESS-ENTER-KEY at `app/cbl/COTRN01C.cbl:L144-L192` is the program's business-logic paragraph. The validation pipeline has exactly 1 check (TRNIDIN empty-or-low-values), vastly simpler than COTRN02C's 14-check pipeline. After validation, if no error is flagged, the paragraph clears 13 output fields, moves TRNIDIN into TRAN-ID, reads the TRANSACT record, and on a NORMAL response populates the 13 output fields from the TRAN-RECORD.

### 4.1 TRNIDIN empty-or-low-values check

The single validation gate is at L146-L156:

```cobol
EVALUATE TRUE
    WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'Tran ID can NOT be empty...' TO
                        WS-MESSAGE
        MOVE -1       TO TRNIDINL OF COTRN1AI
        PERFORM SEND-TRNVIEW-SCREEN
    WHEN OTHER
        MOVE -1       TO TRNIDINL OF COTRN1AI
        CONTINUE
END-EVALUATE
```

The cursor-position move (`MOVE -1 TO TRNIDINL`) is unconditional — both WHEN branches reposition the cursor on TRNIDIN. On the WHEN OTHER branch (non-empty input) the paragraph falls through to the clear-and-read sequence in subsection 4.2; on the empty-input branch the paragraph re-displays the screen with the verbatim message above and the WS-ERR-FLG gate suppresses the populate sequence.

### 4.2 Clear-and-read sequence

At L158-L174, when `NOT ERR-FLG-ON`, the paragraph clears the 13 output fields (so a re-read against a different TRAN-ID does not display stale data from a previous read), moves TRNIDINI into TRAN-ID (the RIDFLD for the READ), and PERFORMs READ-TRANSACT-FILE. The 13 cleared output fields are:

```text
TRNIDI   CARDNUMI TTYPCDI  TCATCDI  TRNSRCI
TRNAMTI  TDESCI   TORIGDTI TPROCDTI MIDI
MNAMEI   MCITYI   MZIPI
```

### 4.3 Populate-13-fields sequence

At L176-L192, when `NOT ERR-FLG-ON` after a successful READ, the paragraph moves TRAN-AMT into the edited image WS-TRAN-AMT (sign-edit preserving the `+nnnnnnnn.nn` display format from L49) and then issues 13 individual MOVE statements to populate the BMS output fields:

| Source Field (TRAN-RECORD) | Destination Field (COTRN1AI) | Source Line |
|---|---|---|
| TRAN-ID | TRNIDI | `app/cbl/COTRN01C.cbl:L178` |
| TRAN-CARD-NUM | CARDNUMI | `app/cbl/COTRN01C.cbl:L179` |
| TRAN-TYPE-CD | TTYPCDI | `app/cbl/COTRN01C.cbl:L180` |
| TRAN-CAT-CD | TCATCDI | `app/cbl/COTRN01C.cbl:L181` |
| TRAN-SOURCE | TRNSRCI | `app/cbl/COTRN01C.cbl:L182` |
| WS-TRAN-AMT | TRNAMTI | `app/cbl/COTRN01C.cbl:L183` |
| TRAN-DESC | TDESCI | `app/cbl/COTRN01C.cbl:L184` |
| TRAN-ORIG-TS | TORIGDTI | `app/cbl/COTRN01C.cbl:L185` |
| TRAN-PROC-TS | TPROCDTI | `app/cbl/COTRN01C.cbl:L186` |
| TRAN-MERCHANT-ID | MIDI | `app/cbl/COTRN01C.cbl:L187` |
| TRAN-MERCHANT-NAME | MNAMEI | `app/cbl/COTRN01C.cbl:L188` |
| TRAN-MERCHANT-CITY | MCITYI | `app/cbl/COTRN01C.cbl:L189` |
| TRAN-MERCHANT-ZIP | MZIPI | `app/cbl/COTRN01C.cbl:L190` |

Length-truncation behavior on MOVE is preserved per COBOL rules: TRAN-DESC is `PIC X(100)` while TDESCI is `PIC X(60)`, so the trailing 40 characters are dropped on copy. TRAN-MERCHANT-NAME is `PIC X(50)` while MNAMEI is `PIC X(30)` — 20 trailing characters dropped. TRAN-MERCHANT-CITY is `PIC X(50)` while MCITYI is `PIC X(25)` — 25 trailing characters dropped. TRAN-ORIG-TS and TRAN-PROC-TS are `PIC X(26)` while TORIGDTI and TPROCDTI are `PIC X(10)` — only the first 10 bytes (the calendar date in `YYYY-MM-DD` form) are displayed. The Java translation in CoTrn01C MUST replicate every truncation byte-for-byte.

### 4.4 Auto-trigger from COTRN00C

At L103-L108 (inside the first-reentry guard documented in 3.2), the auto-trigger conditional synthesizes an ENTER-key invocation:

```cobol
IF CDEMO-CT01-TRN-SELECTED NOT =
                           SPACES AND LOW-VALUES
    MOVE CDEMO-CT01-TRN-SELECTED TO
         TRNIDINI OF COTRN1AI
    PERFORM PROCESS-ENTER-KEY
END-IF
```

This block is first-pass-only — the `IF NOT CDEMO-PGM-REENTER` guard at L99 ensures the auto-trigger fires exactly once when COTRN00C XCTLs into COTRN01C with a pre-selected transaction. On subsequent reentries (after the user presses ENTER, PF3, PF4, or PF5) the auto-trigger does not re-fire because the program is already in REENTER state. The Java translation in CoTrn01C MUST honor this first-pass-only semantics; re-firing on every invocation would clobber the user's typed input.

## Phase 5 — READ-TRANSACT-FILE: UPDATE-with-no-REWRITE Quirk and Verbatim Messages

READ-TRANSACT-FILE at `app/cbl/COTRN01C.cbl:L267-L296` is the program's file-access paragraph. It contains COTRN01C's defining behavioral anomaly: it issues `EXEC CICS READ ... UPDATE ...` (acquiring a CICS update-mode lock on the record), but the program flow never issues REWRITE, WRITE, or DELETE. The lock is implicitly released by the next syncpoint, which occurs at the EXEC CICS RETURN at L136-L139 on pseudo-conversational task end.

### 5.1 EXEC CICS READ with UPDATE clause

The READ invocation at L269-L278 carries the UPDATE keyword on its own line at L275:

```cobol
EXEC CICS READ
     DATASET   (WS-TRANSACT-FILE)
     INTO      (TRAN-RECORD)
     LENGTH    (LENGTH OF TRAN-RECORD)
     RIDFLD    (TRAN-ID)
     KEYLENGTH (LENGTH OF TRAN-ID)
     UPDATE
     RESP      (WS-RESP-CD)
     RESP2     (WS-REAS-CD)
END-EXEC.
```

Per AAP §0.7.1 (Minimal Change Clause), the UPDATE clause is preserved faithfully in the Java translation: the port method MUST be named something like `findForUpdate(TranId id)` to mirror the semantic intent (acquire equivalent lock semantics where the chosen repository adapter supports them; file-based default need not acquire any lock); the Java use-case method MUST NEVER invoke any rewrite, write, or update method against the TRANSACT repository; the deviation MUST be flagged in `java/MIGRATION_NOTES.md` per AAP §0.7.1; the post-run TRANSACT file byte length and content MUST be identical to the input (the READ-only invariant; see Phase 13.2).

### 5.2 WHEN DFHRESP(NORMAL) — success path

At L281-L282, when the READ returns NORMAL the EVALUATE branches to CONTINUE — no error flag, no message, no cursor reposition. Control falls through the EVALUATE, returns to PROCESS-ENTER-KEY's L176 gate, finds `NOT ERR-FLG-ON`, and enters the 13-field populate sequence documented in Phase 4.3.

### 5.3 WHEN DFHRESP(NOTFND) — not-found path

At L283-L288, when the READ returns NOTFND the paragraph sets WS-ERR-FLG to 'Y', moves the verbatim message `'Transaction ID NOT found...'` (L285-L286, terminated by exactly 3 ASCII periods, never the Unicode ellipsis character) into WS-MESSAGE, repositions the cursor on TRNIDIN by moving -1 into TRNIDINL, and PERFORMs SEND-TRNVIEW-SCREEN. The WS-ERR-FLG gate prevents the populate sequence from executing on the now-stale TRAN-RECORD buffer.

### 5.4 WHEN OTHER — unexpected RESP path (sole DISPLAY)

At L289-L295, when the READ returns any other response code (I/O error, dataset disabled, deadlock, etc.) the paragraph executes the program's only DISPLAY statement at L290:

```cobol
WHEN OTHER
    DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
    MOVE 'Y'     TO WS-ERR-FLG
    MOVE 'Unable to lookup Transaction...' TO
                    WS-MESSAGE
    MOVE -1       TO TRNIDINL OF COTRN1AI
    PERFORM SEND-TRNVIEW-SCREEN
```

The DISPLAY at L290 writes the captured RESP and RESP2 values to the CICS region's JES/log surface, which the capture harness redirects to `stdout.txt`. Under the 10 happy-path scenarios documented in Phase 11, this WHEN OTHER branch is NOT reached; captured `stdout.txt` is therefore expected to be empty (or contain only the framing artifacts introduced by the capture script). The verbatim message `'Unable to lookup Transaction...'` (3 ASCII periods) is moved to WS-MESSAGE and routed to ERRMSGO on the subsequent SEND.

## Phase 6 — CLEAR-CURRENT-SCREEN and INITIALIZE-ALL-FIELDS

CLEAR-CURRENT-SCREEN at `app/cbl/COTRN01C.cbl:L301-L304` is the PF4 handler: it PERFORMs INITIALIZE-ALL-FIELDS and then PERFORMs SEND-TRNVIEW-SCREEN. The paragraph contains no business logic of its own; it exists to combine the two operations into a single dispatch target invoked from the EIBAID EVALUATE at L124.

INITIALIZE-ALL-FIELDS at `app/cbl/COTRN01C.cbl:L309-L326` performs the field-wipe. It moves -1 into TRNIDINL (positioning the cursor on TRNIDIN), then issues a group-move of SPACES into 14 named fields plus WS-MESSAGE. The 14 cleared fields are:

```text
TRNIDINI TRNIDI   CARDNUMI TTYPCDI  TCATCDI
TRNSRCI  TRNAMTI  TDESCI   TORIGDTI TPROCDTI
MIDI     MNAMEI   MCITYI   MZIPI
```

Note that this is one more field than the 13 cleared by PROCESS-ENTER-KEY at L158-L173: PF4 also clears TRNIDINI (the user's typed input), whereas PROCESS-ENTER-KEY leaves TRNIDINI intact because its value has just been moved into TRAN-ID for the lookup.

## Phase 7 — RETURN-TO-PREV-SCREEN: XCTL Dispatch

RETURN-TO-PREV-SCREEN at `app/cbl/COTRN01C.cbl:L197-L208` is the program's exit paragraph. It is PERFORMed from MAIN-PARA on the EIBCALEN=0 path (L96), the DFHPF3 path (L122), and the DFHPF5 path (L127). The paragraph applies the default-program fallback, updates the commarea provenance fields, resets PGM-CONTEXT, and issues an XCTL.

### 7.1 Default-program fallback

At L199-L201, the paragraph checks whether CDEMO-TO-PROGRAM is empty (LOW-VALUES or SPACES); if so, it moves `'COSGN00C'` into CDEMO-TO-PROGRAM. This is the safety net for first-time entry (which already pre-loads COSGN00C at L95) and any caller that XCTLed in without presetting the destination. The fallback ensures the XCTL at L206 never targets an empty program name.

### 7.2 Commarea update before XCTL

At L202-L204, before the XCTL fires, the paragraph writes `WS-TRANID` (`'CT01'`) into CDEMO-FROM-TRANID, writes `WS-PGMNAME` (`'COTRN01C'`) into CDEMO-FROM-PROGRAM, and writes ZEROS into CDEMO-PGM-CONTEXT. The PGM-CONTEXT reset is critical: it ensures the destination program sees CDEMO-PGM-ENTER (88-level value 0) on its first reentry, not CDEMO-PGM-REENTER (value 1) which would skip the destination's initialization logic.

### 7.3 XCTL invocation

At L205-L208, the paragraph issues `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)`. XCTL transfers control without preserving the calling program's stack frame; the commarea is the only state that survives the transfer.

### 7.4 Destination table

The four XCTL destinations exposed by RETURN-TO-PREV-SCREEN:

| Source Path | CDEMO-TO-PROGRAM Set To | Source Lines |
|---|---|---|
| EIBCALEN = 0 (first-time entry) | `COSGN00C` | `app/cbl/COTRN01C.cbl:L95` |
| DFHPF3 with empty CDEMO-FROM-PROGRAM | `COMEN01C` | `app/cbl/COTRN01C.cbl:L117` |
| DFHPF3 with populated CDEMO-FROM-PROGRAM | CDEMO-FROM-PROGRAM (e.g., `COTRN00C`) | `app/cbl/COTRN01C.cbl:L119-L120` |
| DFHPF5 | `COTRN00C` | `app/cbl/COTRN01C.cbl:L126` |

## Phase 8 — No STARTBR/READPREV/ENDBR (Single Direct READ Only)

COTRN01C has NO browse cursor. The program does not issue STARTBR, READPREV, READNEXT, or ENDBR against the TRANSACT dataset. The file-access surface is a single direct READ by primary key TRAN-ID (L269-L278). This is the most significant structural difference from COTRN02C (which uses STARTBR + READPREV to auto-increment TRAN-ID before WRITE) and from COTRN00C (which uses STARTBR + READNEXT to paginate the transaction list).

Implication for the Java translation: `CoTrn01C.execute()` invokes `transactionRepository.findById(TranId id)` (or `findForUpdate(TranId id)` per Phase 5.1 to mirror the UPDATE-clause semantic intent); no cursor lifecycle (open/iterate/close) is required. The repository port implementation under `carddemo-adapter-file` reads a single fixed-width record from the indexed offset and returns it; the file-based default need not implement actual locking.

## Phase 9 — Screen I/O Paragraphs: SEND, RECEIVE, POPULATE-HEADER

SEND-TRNVIEW-SCREEN at `app/cbl/COTRN01C.cbl:L213-L225` is the unified display routine invoked from every path that ends in a re-display. It first PERFORMs POPULATE-HEADER-INFO at L215, then moves WS-MESSAGE into ERRMSGO OF COTRN1AO at L217, then issues `EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01') FROM(COTRN1AO) ERASE CURSOR` at L219-L225. The ERASE option clears the 3270 buffer before the new frame is written; the CURSOR option positions the cursor at the field whose length attribute equals -1 (TRNIDIN, per the cursor moves throughout the program).

RECEIVE-TRNVIEW-SCREEN at `app/cbl/COTRN01C.cbl:L230-L238` is invoked only from MAIN-PARA's subsequent-reentry path (L111). It issues `EXEC CICS RECEIVE MAP('COTRN1A') MAPSET('COTRN01') INTO(COTRN1AI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)` and populates the COTRN1AI input record from the 3270 buffer.

POPULATE-HEADER-INFO at `app/cbl/COTRN01C.cbl:L243-L262` writes the fixed header band (rows 1-2 of the BMS map). It moves the result of `FUNCTION CURRENT-DATE` into WS-CURDATE-DATA at L245, then populates TITLE01O and TITLE02O from CCDA-TITLE01 and CCDA-TITLE02 (declared in copybook COTTL01Y), TRNNAMEO from WS-TRANID, PGMNAMEO from WS-PGMNAME, formats CURDATEO as `mm/dd/yy` via the WS-CURDATE-MM-DD-YY work area, and formats CURTIMEO as `hh:mm:ss` via the WS-CURTIME-HH-MM-SS work area.

Java translation note: per AAP §0.6.4, all date/time values use `java.time` types (LocalDate, LocalTime, LocalDateTime). The test harness MUST inject a fixed `Clock` (via `ScopedValue<Clock>` per AAP §0.6.6) so that CURDATEO and CURTIMEO captures are reproducible across test runs; without a fixed clock the wall-clock-dependent output bytes would change between captures and break byte-for-byte parity.

## Phase 10 — BMS Map COTRN1A: 24×80 Screen with 1 Input + 13 Output Fields

The BMS source `app/bms/COTRN01.bms` (273 lines) defines MAPSET COTRN01 (L19-L25) and MAP COTRN1A (L26-L28) with SIZE=(24,80), CTRL=(ALARM,FREEKB), EXTATT=YES, LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES. The symbolic copybook `app/cpy-bms/COTRN01.CPY` (272 lines) defines the COTRN1AI input record at L17 and the COTRN1AO output record (REDEFINES) at L145.

### 10.1 Header row fields (rows 1-2)

| Field | (Row, Col) | Length | Attributes | Color | Source Line |
|---|---|---|---|---|---|
| (label) `'Tran:'` | (1,1) | 5 | ASKIP,NORM | BLUE | `app/bms/COTRN01.bms:L29-L33` |
| TRNNAME | (1,7) | 4 | ASKIP,FSET,NORM | BLUE | `app/bms/COTRN01.bms:L34-L37` |
| TITLE01 | (1,21) | 40 | ASKIP,FSET,NORM | YELLOW | `app/bms/COTRN01.bms:L38-L41` |
| (label) `'Date:'` | (1,65) | 5 | ASKIP,NORM | BLUE | `app/bms/COTRN01.bms:L42-L46` |
| CURDATE | (1,71) | 8 | ASKIP,FSET,NORM | BLUE | `app/bms/COTRN01.bms:L47-L51` |
| (label) `'Prog:'` | (2,1) | 5 | ASKIP,NORM | BLUE | `app/bms/COTRN01.bms:L52-L56` |
| PGMNAME | (2,7) | 8 | ASKIP,FSET,NORM | BLUE | `app/bms/COTRN01.bms:L57-L60` |
| TITLE02 | (2,21) | 40 | ASKIP,FSET,NORM | YELLOW | `app/bms/COTRN01.bms:L61-L64` |
| (label) `'Time:'` | (2,65) | 5 | ASKIP,NORM | BLUE | `app/bms/COTRN01.bms:L65-L69` |
| CURTIME | (2,71) | 8 | ASKIP,FSET,NORM | BLUE | `app/bms/COTRN01.bms:L70-L74` |

### 10.2 SOLE editable input field: TRNIDIN

The only unprotected field in the entire COTRN1A map is TRNIDIN at `app/bms/COTRN01.bms:L85-L90`:

```text
TRNIDIN DFHMDF ATTRB=(FSET,IC,NORM,UNPROT),
               COLOR=GREEN,
               HILIGHT=UNDERLINE,
               LENGTH=16,
               POS=(6,21),
               INITIAL=' '
```

The `IC` attribute (Initial Cursor) marks the field where the cursor positions on screen startup. `UNPROT` is the ONLY non-ASKIP attribute in the entire map; every other field carries ASKIP, making the screen effectively read-only apart from this one input. `LENGTH=16` matches TRAN-ID `PIC X(16)` in CVTRA05Y.

### 10.3 Output-only display fields (13 fields)

Every output field in the body of the map carries the ASKIP attribute, preventing user modification. The 13 output fields map directly to the 13 MOVE statements documented in Phase 4.3:

| Field | (Row, Col) | Length | Maps From (TRAN-RECORD) |
|---|---|---|---|
| TRNID | (10,22) | 16 | TRAN-ID |
| CARDNUM | (10, col-near-45) | 16 | TRAN-CARD-NUM |
| TTYPCD | row-12 area | 2 | TRAN-TYPE-CD |
| TCATCD | row-12 area | 4 | TRAN-CAT-CD |
| TRNSRC | row-13 area | 10 | TRAN-SOURCE |
| TRNAMT | row-14 area | 12 | WS-TRAN-AMT (sign-edited) |
| TDESC | row-15 area | 60 | TRAN-DESC (truncated from 100) |
| TORIGDT | row-16 area | 10 | TRAN-ORIG-TS (first 10 of 26) |
| TPROCDT | row-17 area | 10 | TRAN-PROC-TS (first 10 of 26) |
| MID | row-19 area | 9 | TRAN-MERCHANT-ID |
| MNAME | row-20 area | 30 | TRAN-MERCHANT-NAME (truncated from 50) |
| MCITY | row-21 area | 25 | TRAN-MERCHANT-CITY (truncated from 50) |
| MZIP | row-22 area | 10 | TRAN-MERCHANT-ZIP |

### 10.4 ERRMSG field

ERRMSG is positioned on row 23 with LENGTH=78, ASKIP, COLOR=RED, and sits below the body field band. Per `app/cbl/COTRN01C.cbl:L217`, SEND-TRNVIEW-SCREEN moves WS-MESSAGE into ERRMSGO before every SEND. WS-MESSAGE is `PIC X(80)` while ERRMSGO is `PIC X(78)` — the last 2 bytes of WS-MESSAGE are truncated by COBOL's MOVE semantics. The Java translation in CoTrn01C MUST replicate this 80-to-78 truncation byte-for-byte; emitting 80 bytes into a 78-byte buffer would shift the ERASE/CURSOR framing in the captured BMS output and break parity.

## Phase 11 — Required Test Scenarios (10 Total)

The `input_scenario.txt` driver file in this directory specifies exactly 10 scenarios, each simulating one CICS pseudo-conversational invocation of CoTrn01C. Together they exercise every branch of MAIN-PARA's EIBAID dispatch, both reentry passes (first and subsequent), both branches of the TRNIDIN validation gate, and the auto-trigger from upstream COTRN00C.

| # | Scenario | AID Key | Inputs | Expected Outcome | Source Lines |
|---|---|---|---|---|---|
| 1 | Initial display (first reentry, no auto-trigger) | — | EIBCALEN > 0, NOT CDEMO-PGM-REENTER, CDEMO-CT01-TRN-SELECTED = LOW-VALUES | Send empty COTRN1AO; cursor on TRNIDIN; no error | `app/cbl/COTRN01C.cbl:L99-L109` |
| 2 | ENTER with empty TRNIDIN | DFHENTER | TRNIDINI = SPACES | EXPECT_MSG `'Tran ID can NOT be empty...'`; cursor returns to TRNIDIN | `app/cbl/COTRN01C.cbl:L146-L156` |
| 3 | ENTER with valid TRNIDIN found | DFHENTER | TRNIDINI = (existing ID from `transact.txt`) | READ NORMAL; populate all 13 output fields; ERRMSG = spaces | `app/cbl/COTRN01C.cbl:L144-L192, L267-L296` |
| 4 | ENTER with TRNIDIN not found | DFHENTER | TRNIDINI = (nonexistent ID) | READ NOTFND; EXPECT_MSG `'Transaction ID NOT found...'` | `app/cbl/COTRN01C.cbl:L283-L288` |
| 5 | PF3 with empty CDEMO-FROM-PROGRAM | DFHPF3 | CDEMO-FROM-PROGRAM = LOW-VALUES | XCTL → COMEN01C | `app/cbl/COTRN01C.cbl:L115-L122` |
| 6 | PF3 with populated CDEMO-FROM-PROGRAM | DFHPF3 | CDEMO-FROM-PROGRAM = `COTRN00C` | XCTL → COTRN00C | `app/cbl/COTRN01C.cbl:L115-L122` |
| 7 | PF4 clear | DFHPF4 | (any) | CLEAR-CURRENT-SCREEN; all 14 fields cleared; cursor on TRNIDIN | `app/cbl/COTRN01C.cbl:L123-L124, L301-L326` |
| 8 | PF5 return | DFHPF5 | (any) | XCTL → COTRN00C | `app/cbl/COTRN01C.cbl:L125-L127` |
| 9 | Auto-trigger from COTRN00C | (none) | EIBCALEN > 0, NOT CDEMO-PGM-REENTER, CDEMO-CT01-TRN-SELECTED = (existing ID) | Auto MOVE TO TRNIDINI; PERFORM PROCESS-ENTER-KEY; READ NORMAL; populate 13 fields | `app/cbl/COTRN01C.cbl:L103-L108` |
| 10 | WHEN OTHER invalid AID (e.g., PF6) | DFHPF6 | (any) | EXPECT_MSG CCDA-MSG-INVALID-KEY | `app/cbl/COTRN01C.cbl:L128-L131` |

### 11.1 EXPECT_TRANSACT_UNCHANGED invariant

ALL 10 scenarios MUST include `EXPECT_TRANSACT_UNCHANGED` as the final assertion directive before `END_SCENARIO`. This directive asserts that the TRANSACT file's byte length and content are identical before and after the scenario executes (the READ-only invariant per AAP §0.7.1). Even scenarios 3 and 9 — which both successfully READ a record under the UPDATE clause — must leave the file unchanged because no REWRITE ever fires (see Phase 13.2 and Phase 13.3).

## Phase 12 — 5 Files in This Directory

The `expected/` directory carries exactly 5 files. Three of the four non-README files are capture placeholders; they contain only comment text until the COBOL baseline capture is performed per `java/MIGRATION_NOTES.md` §1.6.

| File | Type | Status | Purpose |
|---|---|---|---|
| `README.md` | Documentation | TEST-OWNED | This authoritative contract document |
| `input_scenario.txt` | Test driver | TEST-OWNED | 10-scenario CICS pseudo-conversation directive script |
| `transact.txt` | Data fixture | CAPTURE PLACEHOLDER | Initial TRANSACT state (350-byte fixed-width records, ASCII) |
| `stdout.txt` | Captured output | CAPTURE PLACEHOLDER | Captured COBOL DISPLAY output (expected empty under the 10 scenarios) |
| `bms_output.txt` | Captured output | CAPTURE PLACEHOLDER | Serialized BMS SEND MAP frames (about 10 frames, one per scenario) |

The test class `CoTrn01CGoldenTest` MUST remain `@Disabled` per AAP §0.6.11 until the 3 capture-placeholder files are replaced with artifacts generated by a real COBOL run against the COTRN01C source.

## Phase 13 — Structural Invariants

### 13.1 BMS map invariant

The COTRN1A map has EXACTLY 1 unprotected input field: TRNIDIN at `app/bms/COTRN01.bms:L85-L90` carrying `ATTRB=(FSET,IC,NORM,UNPROT)`. Every other field on the screen carries ASKIP. Adding, removing, or flipping the UNPROT attribute on any field constitutes a behavior change and is FORBIDDEN under AAP §0.7.1.

### 13.2 TRANSACT no-modification invariant

COTRN01C is effectively READ-only. Despite issuing `EXEC CICS READ ... UPDATE ...` at `app/cbl/COTRN01C.cbl:L269-L278` with the UPDATE clause at L275, the program NEVER issues REWRITE, WRITE, or DELETE against the TRANSACT dataset. The post-run TRANSACT file byte length and content MUST be identical to the input. Asserting this invariant on every scenario is the purpose of `EXPECT_TRANSACT_UNCHANGED` documented in Phase 11.1.

### 13.3 READ-with-UPDATE-no-REWRITE quirk

This is COTRN01C's signature behavioral anomaly. The UPDATE keyword acquires a CICS update-mode (exclusive) lock on the record; the lock is released implicitly by the next syncpoint, which occurs at the `EXEC CICS RETURN` at `app/cbl/COTRN01C.cbl:L136-L139` on pseudo-conversational task end. The Java translation MUST faithfully preserve this observable behavior: acquire equivalent lock semantics via repository method naming or transactional boundary (or, for the file-based adapter, no actual lock — record the intent in the method name only) WITHOUT ever invoking a rewrite, write, or delete method. The deviation MUST be flagged in `java/MIGRATION_NOTES.md` per AAP §0.7.1.

### 13.4 Java class mapping

The COBOL `PROGRAM-ID. COTRN01C` maps to Java class `com.blitzy.carddemo.application.transaction.CoTrn01C` per AAP §0.4.1. The class MUST carry a `@CobolProgram("COTRN01C")` Javadoc-style annotation citing the original PROGRAM-ID, the source path `app/cbl/COTRN01C.cbl`, and the translation date per AAP §0.7.1. The Java test class is `com.blitzy.carddemo.tests.golden.CoTrn01CGoldenTest` extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test class MUST be annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the capture placeholders in this directory are replaced with captured artifacts.

### 13.5 PAN masking invariant

Per AAP §0.7.2, no 16-digit card-number PAN value MUST appear unmasked in `bms_output.txt`, `stdout.txt`, `transact.txt` after capture, or any log/error message emitted by the Java translation. The capture script documented in `java/MIGRATION_NOTES.md` §1.6 MUST mask CARDNUMO to show only the last 4 digits (the masked placeholder `XXXXXXXXXXXX####` with 12 `X` characters followed by 4 `#` characters). The Java translation MUST NEVER log a full PAN under any condition.

## Verbatim COBOL Message Catalog

COTRN01C emits exactly 3 program-internal verbatim error messages plus 1 external constant reference (CCDA-MSG-INVALID-KEY from CSMSG01Y). All trailing ellipses are 3 ASCII periods (`...`), NEVER the Unicode ellipsis character. Any translation that substitutes Unicode for these ASCII periods will fail byte-for-byte parity against the captured baseline.

| # | Verbatim Message | Source Line | Trigger |
|---|---|---|---|
| 1 | `'Tran ID can NOT be empty...'` | `app/cbl/COTRN01C.cbl:L149-L150` | PROCESS-ENTER-KEY WHEN TRNIDINI = SPACES OR LOW-VALUES |
| 2 | `'Transaction ID NOT found...'` | `app/cbl/COTRN01C.cbl:L285-L286` | READ-TRANSACT-FILE WHEN DFHRESP(NOTFND) |
| 3 | `'Unable to lookup Transaction...'` | `app/cbl/COTRN01C.cbl:L292-L293` | READ-TRANSACT-FILE WHEN OTHER |
| 4 | CCDA-MSG-INVALID-KEY (`'Invalid key pressed. Please see below...         '`) | `app/cpy/CSMSG01Y.cpy:L20-L21`; referenced at `app/cbl/COTRN01C.cbl:L130` | MAIN-PARA EVALUATE EIBAID WHEN OTHER |

## Capture Procedure Cross-Reference

The capture procedure for `stdout.txt`, `bms_output.txt`, and `transact.txt` is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until the COBOL baseline capture is performed and the placeholder files in this directory are replaced with captured artifacts, the test class `CoTrn01CGoldenTest` MUST remain `@Disabled` per AAP §0.6.11. The capture script MUST mask card numbers to the last 4 digits before committing artifacts (per Phase 13.5) and MUST inject a fixed `Clock` via `ScopedValue<Clock>` per AAP §0.6.6 so that the date/time values written by POPULATE-HEADER-INFO (Phase 9) are reproducible across runs.

## Source Lineage

Each source file below is REFERENCE-only and preserved unchanged per AAP §0.1.1 and §0.2.2:

- `app/cbl/COTRN01C.cbl` (330 lines) — Transaction-view CICS COBOL program
- `app/bms/COTRN01.bms` (273 lines) — BMS map definition MAPSET COTRN01, MAP COTRN1A
- `app/cpy-bms/COTRN01.CPY` (272 lines) — Symbolic map copybook for COTRN1AI and COTRN1AO
- `app/cpy/CVTRA05Y.cpy` (21 lines) — TRAN-RECORD 350-byte layout
- `app/cpy/COCOM01Y.cpy` (47 lines) — CARDDEMO-COMMAREA (CDEMO-CT01-INFO is appended INLINE in COTRN01C.cbl L53-L61, NOT in this copybook)
- `app/cpy/CSMSG01Y.cpy` (24 lines) — Common messages including CCDA-MSG-INVALID-KEY
- `app/cpy/COTTL01Y.cpy` — Screen titles CCDA-TITLE01 and CCDA-TITLE02
- `app/cpy/CSDAT01Y.cpy` — Date constants WS-CURDATE-DATA, WS-CURDATE-MM-DD-YY, WS-CURTIME-HH-MM-SS

## Cross-References

- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cotrn02c/expected/README.md` (closest structural sibling — Transaction Add, CT02; WRITE semantics with 33-message validation pipeline)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cousr00c/expected/README.md` (13-phase pattern template)
- Sibling fixture: `java/carddemo-tests/src/test/resources/golden/cotrn01c/input/README.md` (documentation-only marker explaining why no fixture data lives in `input/`)
- Java class under test: `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CoTrn01C.java`
- Java test class: `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoTrn01CGoldenTest.java`
- Java repository port: `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/TransactionRepository.java`
- Java record: `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java`
- Migration notes: `java/MIGRATION_NOTES.md` §1.6 (capture procedure)

## Authority References

- AAP §0.1.1 (COBOL source tree under `app/` is UNCHANGED)
- AAP §0.2.1 (in-scope: `golden/cotrn01c/expected/` directory tree)
- AAP §0.2.2 (out-of-scope: any modification to `app/` tree)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 (COTRN01C → `com.blitzy.carddemo.application.transaction.CoTrn01C`)
- AAP §0.6.4 (`java.time` mandate; no legacy date/calendar/format classes)
- AAP §0.6.5 (`java.nio.file` mandate)
- AAP §0.6.6 (ScopedValue replaces ThreadLocal; fixed-clock injection)
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` until COBOL capture committed)
- AAP §0.6.12 (architectural override: no Spring; plain Java with constructor injection; file-based default)
- AAP §0.7.1 (Minimal Change Clause; preserve READ-with-UPDATE-no-REWRITE quirk; verbatim messages; `@CobolProgram` annotation)
- AAP §0.7.2 (PAN masking in logs and display surfaces)
- AAP §0.7.4 (no preview features; pattern-matching exhaustiveness; sealed types covering every observable AID key)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)

## Contrast Matrix — COTRN01C vs COTRN02C vs COTRN00C

COTRN01C is the simplest of the three transaction-family online programs. The matrix below records the differentiators that govern fixture content (scenario count, verbatim message inventory, captured artifact size) and Java translation surface area.

| Aspect | COTRN01C (View) | COTRN02C (Add) | COTRN00C (List) |
|---|---|---|---|
| CICS transaction | CT01 | CT02 | CT00 |
| Editable BMS input fields | 1 (TRNIDIN) | 13 + CONFIRM | varies (cursor + selection) |
| AID keys handled | 4 named + OTHER | 4 named + OTHER | 4 named + OTHER (including PF7/PF8) |
| Validation messages | 3 + CCDA-MSG-INVALID-KEY | 33 + CCDA-MSG-INVALID-KEY | about 5-7 + CCDA-MSG-INVALID-KEY |
| Active DISPLAY count | 1 (L290, WHEN OTHER) | 5 | 3 |
| CSUTLDTC date validation | NO | YES (2 calls) | NO |
| CARDXREF AIX lookup | NO | YES | NO |
| Auto-increment TRAN-ID | NO | YES (STARTBR / READPREV) | NO |
| CONFIRM Y/N dispatch | NO | YES | NO |
| TRANSACT WRITE | NO (READ UPDATE no REWRITE) | YES | NO |
| TRANSACT browse cursor | NO | NO (single READ) | YES (STARTBR / READNEXT) |
| Post-run TRANSACT state | UNCHANGED | +350 bytes per add scenario | UNCHANGED |
| Auto-trigger from upstream | YES (CDEMO-CT01-TRN-SELECTED) | YES (CDEMO-CT02-TRN-SELECTED) | N/A |

## DO NOT Modify Without Re-Capture

```text
Per AAP section 0.7.1 (Minimal Change Clause), any change to a fixture
file in this directory MUST be accompanied by a corresponding update to
this README.md AND re-captured stdout.txt / bms_output.txt /
transact.txt artifacts per java/MIGRATION_NOTES.md section 1.6.

Examples of changes requiring re-capture:
- Modifying any scenario in input_scenario.txt (count, order, AID, FIELD value)
- Modifying any verbatim COBOL error message (the 3 messages in Phase 5 + CCDA-MSG-INVALID-KEY)
- Modifying any BMS field expectation (Phase 10 layout table)
- Modifying the READ-with-UPDATE-no-REWRITE quirk handling (Phase 13.3)
- Modifying the @Disabled annotation message (Phase 13.4)

If a change is purely additive (e.g., adding a new scenario), the new
scenario's expected output MUST be captured from a COBOL run before the
change can be committed. The PR gate per AAP section 0.6.11 will reject
any change that breaks byte-for-byte parity.
```
