# COCRDSLC (View Credit Card Detail, CCDL) -- Golden-Record `expected/` Fixture Contract

## Phase 0 -- Header and Authority Cascade

This README is the authoritative contract document for the COCRDSLC golden-record test fixture residing at `java/carddemo-tests/src/test/resources/golden/cocrdslc/expected/`. It binds the captured COBOL baseline artifacts in this directory to the Java translation under test, the test harness that asserts byte-level parity, and the unchanged COBOL source files under `app/` from which every fact in this document is derived.

Authority cascade (binding). AAP section 0.6.11 mandates byte-for-byte golden-record parity as the non-negotiable PR gate. AAP section 0.7.1 (Minimal Change Clause) governs faithful translation including the preservation of every observable quirk, including dead code, misspellings, and the precise byte layout of every emitted message. AAP section 0.1.1 and 0.2.2 fix the COBOL source tree under `app/` as the immutable reference implementation; this fixture directory derives every fact by direct citation of source-line locators within `app/cbl/COCRDSLC.cbl` (887 lines), `app/bms/COCRDSL.bms` (157 lines), `app/cpy-bms/COCRDSL.CPY` (200 lines), and the supporting copybooks `app/cpy/CVACT02Y.cpy`, `app/cpy/COCOM01Y.cpy`, `app/cpy/CVCRD01Y.cpy`, `app/cpy/CSDAT01Y.cpy`, `app/cpy/CSMSG01Y.cpy`, `app/cpy/CSMSG02Y.cpy`, and `app/cpy/COTTL01Y.cpy`. AAP section 0.2.1 enumerates this fixture as in-scope for CREATE. AAP section 0.3.1 mandates the hexagonal layout and the `@CobolProgram` traceability annotation. AAP section 0.4.1 fixes the Java class naming convention. AAP section 0.6.4 mandates `java.time` for date/time values. AAP section 0.6.5 mandates `java.nio.file` for all file I/O. AAP section 0.6.6 mandates `ScopedValue` for cross-method context propagation. AAP section 0.6.12 records the architectural override against any framework-based, container-based, or relational-persistence-based target proposed by historical tech-spec drafts. AAP section 0.7.2 mandates PAN masking (last 4 digits visible) in any captured surface. AAP section 0.7.4 forbids preview JEPs in production code. AAP section 0.7.5 documents the capture procedure cross-reference.

Program identification. COBOL `PROGRAM-ID. COCRDSLC` at `app/cbl/COCRDSLC.cbl:L23`. CICS transaction `CCDL` via `LIT-THISTRANID PIC X(4) VALUE 'CCDL'` at `app/cbl/COCRDSLC.cbl:L165-L166`. BMS mapset `COCRDSL` via `LIT-THISMAPSET PIC X(8) VALUE 'COCRDSL '` at `app/cbl/COCRDSLC.cbl:L167-L168` (7 chars plus one trailing space to fill PIC X(8)). BMS map `CCRDSLA` via `LIT-THISMAP PIC X(7) VALUE 'CCRDSLA'` at `app/cbl/COCRDSLC.cbl:L169-L170`. VSAM file `CARDDAT` via `LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '` at `app/cbl/COCRDSLC.cbl:L187-L188` (READ-only access; never opened for UPDATE, WRITE, REWRITE, or DELETE). Source file total: 887 lines. Function: accept and process credit-card detail-view requests from a CICS pseudo-conversational session.

Java translation target. The COCRDSLC program maps to `com.blitzy.carddemo.application.card.CoCrdSlC` per AAP section 0.4.1 (card subpackage of the application module). The companion BMS DTOs are `com.blitzy.carddemo.application.card.CoCrdSlInput` (the input record reflecting the CCRDSLAI symbolic copybook) and `com.blitzy.carddemo.application.card.CoCrdSlOutput` (the output record reflecting the CCRDSLAO REDEFINES). The class carries a `@CobolProgram("COCRDSLC")` Javadoc-style annotation citing the original PROGRAM-ID, the source path `app/cbl/COCRDSLC.cbl`, and the translation date per AAP section 0.7.1.

Java test class. `com.blitzy.carddemo.tests.golden.CoCrdSlCGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test class MUST carry `@Disabled("Pending COBOL baseline capture -- see java/MIGRATION_NOTES.md section 1.6")` per AAP section 0.6.11 until captured artifacts replace the placeholders in this directory.

File inventory bound by this README. This directory contains exactly 4 files: `README.md` (this document, the authoritative contract), `input_scenario.txt` (the TEST-OWNED 12-scenario CICS pseudo-conversation driver), `stdout.txt` (capture placeholder, expected EMPTY because COCRDSLC issues no DISPLAY statements), and `bms_output.txt` (capture placeholder for serialized BMS SEND MAP frames, approximately 12 frames after capture, one per scenario). There is no `carddata.txt` in this folder; the CARDDAT fixture is `app/data/ASCII/carddata.txt`, read REFERENCE-only via classpath relative path per AAP section 0.2.1 and 0.4.1, with no per-scenario copy required because COCRDSLC never modifies CARDDAT.

## Phase 1 -- COBOL Source: PROGRAM-ID and WS-VARIABLES

The source file `app/cbl/COCRDSLC.cbl` is 887 lines total. The IDENTIFICATION DIVISION begins at L22; the PROGRAM-ID declaration appears at L23-L24. The WORKING-STORAGE SECTION (L35 through L240) declares the program's state variables, the literal constants in `WS-LITERALS` (L162-L190), and the COPY directives that resolve to shared record layouts and constant catalogues. The LINKAGE SECTION (L242-L246) declares the `DFHCOMMAREA` with a 1-to-32767 FILLER OCCURS DEPENDING ON `EIBCALEN`, which the PROCEDURE DIVISION reslices into `CARDDEMO-COMMAREA` plus `WS-THIS-PROGCOMMAREA` at L274-L278.

The `WS-LITERALS` 01-level group at L162 contains the program's literal constants. The table below reproduces each literal verbatim from the source, preserving the COBOL `PIC X(n)` allocation and the quoted VALUE clause.

| Variable | Value (verbatim, padded as in source) | Line | Notes |
|---|---|---|---|
| LIT-THISPGM | `'COCRDSLC'` | 164 | Self-reference, used in ABEND-CULPRIT and CDEMO-FROM-PROGRAM updates |
| LIT-THISTRANID | `'CCDL'` | 166 | CICS transaction ID for this program |
| LIT-THISMAPSET | `'COCRDSL '` | 168 | PIC X(8); 7 characters plus 1 trailing space to fill |
| LIT-THISMAP | `'CCRDSLA'` | 170 | PIC X(7); 7 characters exactly |
| LIT-CCLISTPGM | `'COCRDLIC'` | 172 | Card-list caller program (XCTL source and target) |
| LIT-CCLISTTRANID | `'CCLI'` | 174 | Card-list transaction ID |
| LIT-CCLISTMAPSET | `'COCRDLI'` | 176 | PIC X(7); used in CDEMO-LAST-MAPSET comparison at L505,L527 |
| LIT-CCLISTMAP | `'CCRDSLA'` | 178 | PIC X(7); source-quirk: value matches self-map name |
| LIT-MENUPGM | `'COMEN01C'` | 180 | Default exit target on PF3 when CDEMO-FROM-PROGRAM is unset |
| LIT-MENUTRANID | `'CM00'` | 182 | Default menu transaction ID |
| LIT-MENUMAPSET | `'COMEN01'` | 184 | PIC X(7) |
| LIT-MENUMAP | `'COMEN1A'` | 186 | PIC X(7) |
| LIT-CARDFILENAME | `'CARDDAT '` | 188 | Primary VSAM file (READ-only) |
| LIT-CARDFILENAME-ACCT-PATH | `'CARDAIX '` | 190 | Alternate index path; DEAD CODE reference (see Phase 5e) |

`LIT-CARDFILENAME-ACCT-PATH` is referenced only from the dead `9150-GETCARD-BYACCT` paragraph at `app/cbl/COCRDSLC.cbl:L779-L809`. The orchestrator `9000-READ-DATA` at `app/cbl/COCRDSLC.cbl:L726-L730` calls `9100-GETCARD-BYACCTCARD` exclusively; no other paragraph invokes `9150-GETCARD-BYACCT`. Per AAP section 0.7.1, the Java translation MUST include `9150` as a private method on `CoCrdSlC` and flag it in `java/MIGRATION_NOTES.md` section 1.6 as DEAD CODE PRESERVED.

## Phase 2 -- Commarea: CARDDEMO-COMMAREA + WS-THIS-PROGCOMMAREA Local Extension

The LINKAGE SECTION at `app/cbl/COCRDSLC.cbl:L242-L246` declares an opaque `DFHCOMMAREA` with FILLER OCCURS DEPENDING ON `EIBCALEN`. The PROCEDURE DIVISION reslices this opaque payload into two complementary structures using the COBOL `MOVE ... (offset:length)` reference modification at L274-L278:

1. `CARDDEMO-COMMAREA` from `COPY COCOM01Y.` at `app/cbl/COCRDSLC.cbl:L198`, defined in `app/cpy/COCOM01Y.cpy:L19-L44`. This is shared across the whole Card Demo program family. It contains nested groups: `CDEMO-GENERAL-INFO` (transaction routing, user identity, pgm-context), `CDEMO-CUSTOMER-INFO`, `CDEMO-ACCOUNT-INFO`, `CDEMO-CARD-INFO`, and `CDEMO-MORE-INFO` (CDEMO-LAST-MAP, CDEMO-LAST-MAPSET).
2. `WS-THIS-PROGCOMMAREA` declared INLINE at `app/cbl/COCRDSLC.cbl:L200-L203` (not in any copybook). It contains:
   - `CA-CALL-CONTEXT.CA-FROM-PROGRAM PIC X(08)` (the program-id of the caller, used to determine PF3 return target)
   - `CA-CALL-CONTEXT.CA-FROM-TRANID PIC X(04)` (the transaction-id of the caller)
3. `WS-COMMAREA PIC X(2000)` at `app/cbl/COCRDSLC.cbl:L205` is the serialization buffer that holds the concatenation of `CARDDEMO-COMMAREA` followed by `WS-THIS-PROGCOMMAREA`, used as the `COMMAREA` operand on `EXEC CICS RETURN` (see Phase 7).

The verbatim COBOL fence at `app/cbl/COCRDSLC.cbl:L198-L205`:

```text
       COPY COCOM01Y.

       01 WS-THIS-PROGCOMMAREA.
          05 CA-CALL-CONTEXT.
             10 CA-FROM-PROGRAM                    PIC X(08).
             10 CA-FROM-TRANID                     PIC X(04).

       01  WS-COMMAREA                             PIC X(2000).
```

`WS-COMMAREA PIC X(2000)` is the `EXEC CICS RETURN COMMAREA` payload, holding the concatenated serialized form of `CARDDEMO-COMMAREA` plus `WS-THIS-PROGCOMMAREA`. The Java translation MUST preserve the 2000-byte length and the field-order for byte-for-byte interop with any COBOL handler that still consumes this commarea (per AAP section 0.7.1 Minimal Change Clause). The Java DTO `com.blitzy.carddemo.domain.commarea.CardDemoCommarea` (from `app/cpy/COCOM01Y.cpy`) plus an inline `CoCrdSlC.LocalCommareaExtension` record together replicate the 2000-byte commarea exactly.

## Phase 3 -- 0000-MAIN Dispatch

The 0000-MAIN paragraph at `app/cbl/COCRDSLC.cbl:L248-L392` is the program's pseudo-conversational dispatch entry. After `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)` at L250-L252 and `INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA` at L254-L256, the dispatcher distinguishes between three execution contexts. The 5-branch `EVALUATE TRUE` at L304-L381 then routes to the appropriate handler.

### Phase 3a -- First-Time Entry (EIBCALEN = 0)

`app/cbl/COCRDSLC.cbl:L268-L279` detects first-time entry via `IF EIBCALEN = 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)`. The first-time-entry behavior is:

1. INITIALIZE CARDDEMO-COMMAREA
2. INITIALIZE WS-THIS-PROGCOMMAREA
3. After the PFK store at L284 and the EIBAID coercion at L291-L299, the EVALUATE branch `WHEN CDEMO-PGM-ENTER` at L349-L356 is entered
4. PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP-EXIT
5. GO TO COMMON-RETURN

The verbatim COBOL fence at `app/cbl/COCRDSLC.cbl:L268-L279`:

```text
           IF EIBCALEN IS EQUAL TO 0
               OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM
               AND NOT CDEMO-PGM-REENTER)
              INITIALIZE CARDDEMO-COMMAREA
                         WS-THIS-PROGCOMMAREA
           ELSE
              MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA)  TO
                                CARDDEMO-COMMAREA
              MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
                               LENGTH OF WS-THIS-PROGCOMMAREA ) TO
                                WS-THIS-PROGCOMMAREA
           END-IF
```

Within 1200-SETUP-SCREEN-VARS at L459-L460, the path `IF EIBCALEN = 0 SET WS-PROMPT-FOR-INPUT TO TRUE` causes `WS-INFO-MSG` to be set to `'Please enter Account and Card Number'` (the verbatim text at L132). This branch corresponds to test scenario 1 in `input_scenario.txt`.

### Phase 3b -- Reentry from COCRDLIC (Auto-Trigger)

`app/cbl/COCRDSLC.cbl:L339-L348` detects auto-trigger when `WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM`. In this case the caller (COCRDLIC) has pre-populated `CDEMO-ACCT-ID` and `CDEMO-CARD-NUM` in the commarea. Behavior:

1. SET INPUT-OK TO TRUE
2. MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N (auto-bind account)
3. MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N (auto-bind card number)
4. PERFORM 9000-READ-DATA THRU 9000-READ-DATA-EXIT (direct CARDDAT lookup, no 2000-PROCESS-INPUTS)
5. PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP-EXIT
6. GO TO COMMON-RETURN

The validation pipeline 2000-PROCESS-INPUTS is BYPASSED because the caller is treated as a trusted source of pre-validated keys. This branch corresponds to test scenario 11 in `input_scenario.txt`. The Java translation MUST replicate this bypass exactly: when the input record carries the auto-trigger flag, the validation methods are skipped.

### Phase 3c -- EIBAID Dispatch and EVALUATE TRUE 5-Branch Table

PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT at `app/cbl/COCRDSLC.cbl:L284-L285` decodes the AID byte into one of the named 88-level conditions on `CCARD-AID` (from `app/cpy/CVCRD01Y.cpy:L3-L19`): `CCARD-AID-ENTER`, `CCARD-AID-CLEAR`, `CCARD-AID-PA1`, `CCARD-AID-PA2`, and `CCARD-AID-PFK01` through `CCARD-AID-PFK12`. The YYYY-STORE-PFKEY paragraph is brought in via `COPY 'CSSTRPFY'` at `app/cbl/COCRDSLC.cbl:L855`.

Lines L291-L299 then apply a silent coercion. If the AID is neither `CCARD-AID-ENTER` nor `CCARD-AID-PFK03`, the flag `PFK-INVALID` is set, and then the AID is coerced to `CCARD-AID-ENTER`. This means any unsupported function key (PF1, PF2, PF4..PF12, PA1, PA2, CLEAR) is silently treated as ENTER. The verbatim COBOL fence at L291-L299:

```text
           SET PFK-INVALID TO TRUE
           IF CCARD-AID-ENTER OR
              CCARD-AID-PFK03
              SET PFK-VALID TO TRUE
           END-IF

           IF PFK-INVALID
              SET CCARD-AID-ENTER TO TRUE
           END-IF
```

This silent coercion is the source of scenario 12's behavior (PF04 coerced to ENTER, validation pipeline runs, both filters blank because the test sends empty inputs, so `NO-SEARCH-CRITERIA-RECEIVED` triggers). Lines L304-L381 form the 5-branch EVALUATE dispatcher.

| Branch | Condition | Line | Action | Test Scenarios |
|---|---|---|---|---|
| 1 | `CCARD-AID-PFK03` | 305 | XCTL to CDEMO-FROM-PROGRAM or LIT-MENUPGM (L309-L334) | 9, 10 |
| 2 | `CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM` | 339-340 | Auto-trigger pre-validated read (L341-L348) | 11 |
| 3 | `CDEMO-PGM-ENTER` (other) | 349 | Fresh 1000-SEND-MAP only (L354-L356) | 1 |
| 4 | `CDEMO-PGM-REENTER` | 357 | 2000-PROCESS-INPUTS then 9000-READ-DATA + 1000-SEND-MAP (L358-L371) | 2, 3, 4, 5, 6, 7, 8, 12 |
| 5 | `WHEN OTHER` | 373 | MOVE 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG; PERFORM SEND-PLAIN-TEXT (L374-L380) | (defensive only) |

The Java translation expresses this 5-branch dispatch as a pattern-matching switch over a sealed `DispatchState` hierarchy with permits for `ExitToPriorProgram`, `AutoTrigger`, `FirstShow`, `ReenterValidate`, and `UnexpectedData`. Per AAP section 0.7.4 no fall-through branch is permitted; exhaustiveness checking enforced by the compiler is the safety guarantee.

## Phase 4 -- 2000-PROCESS-INPUTS Validation Pipeline

The 2000-PROCESS-INPUTS paragraph at `app/cbl/COCRDSLC.cbl:L582-L591` orchestrates input handling for every REENTER pass. It performs 2100-RECEIVE-MAP to pull the latest CCRDSLAI buffer from CICS, then 2200-EDIT-MAP-INPUTS to normalize and validate the two filter fields, then propagates `WS-RETURN-MSG` into `CCARD-ERROR-MSG` for the next SEND.

### Phase 4a -- 2100-RECEIVE-MAP

`app/cbl/COCRDSLC.cbl:L596-L607` issues the receive of the CCRDSLAI buffer:

```text
           EXEC CICS RECEIVE MAP(LIT-THISMAP)
                     MAPSET(LIT-THISMAPSET)
                     INTO(CCRDSLAI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC
```

The `CCRDSLAI` input record is the symbolic copybook 01-level group at `app/cpy-bms/COCRDSL.CPY:L17-L108`, defining 17 named sub-field groupings with the L (length), F (flag), A (attribute), and I (input value) quartet pattern used by BMS symbolic copybooks. The two editable sub-fields are `ACCTSIDI PIC X(11)` at line 60 of the symbolic copybook and `CARDSIDI PIC X(16)` at line 66.

### Phase 4b -- 2200-EDIT-MAP-INPUTS Normalization

`app/cbl/COCRDSLC.cbl:L608-L641`:

1. Initialize INPUT-OK, FLG-CARDFILTER-ISVALID, FLG-ACCTFILTER-ISVALID at L610-L612.
2. Normalize ACCTSIDI: if `'*'` or SPACES, MOVE LOW-VALUES TO CC-ACCT-ID; else MOVE ACCTSIDI to CC-ACCT-ID (L615-L620). The wildcard `*` and blank inputs are treated identically as "filter not supplied".
3. Normalize CARDSIDI: same wildcard pattern, MOVE result to CC-CARD-NUM (L622-L627).
4. PERFORM 2210-EDIT-ACCOUNT THRU 2210-EDIT-ACCOUNT-EXIT (L630-L631).
5. PERFORM 2220-EDIT-CARD THRU 2220-EDIT-CARD-EXIT (L633-L634).
6. Cross-field edit at L637-L640: IF both filters are flagged BLANK, SET NO-SEARCH-CRITERIA-RECEIVED TO TRUE, which causes `WS-RETURN-MSG` to bind to `'No input received'` (the verbatim text at L143).

### Phase 4c -- 2210-EDIT-ACCOUNT

`app/cbl/COCRDSLC.cbl:L647-L679`:

- Empty/LOW-VALUES/ZEROES check at L651-L661. If `CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES OR CC-ACCT-ID-N EQUAL ZEROS`, then SET INPUT-ERROR TO TRUE, SET FLG-ACCTFILTER-BLANK TO TRUE; if WS-RETURN-MSG-OFF, SET WS-PROMPT-FOR-ACCT TO TRUE (binds to `'Account number not provided'` at L139); MOVE ZEROES TO CDEMO-ACCT-ID; GO TO 2210-EDIT-ACCOUNT-EXIT.
- Numeric check at L665-L678: if `CC-ACCT-ID IS NOT NUMERIC`, SET INPUT-ERROR, SET FLG-ACCTFILTER-NOT-OK. The inline message MOVE at L668-L671:

```text
           IF WS-RETURN-MSG-OFF
             MOVE
           'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'
                           TO WS-RETURN-MSG
           END-IF
```

The literal at `app/cbl/COCRDSLC.cbl:L670` has NO space after the comma between `FILTER` and `IF`. This must be reproduced byte-for-byte by the Java translation. Then MOVE ZERO TO CDEMO-ACCT-ID; GO TO exit. Valid-path: MOVE CC-ACCT-ID TO CDEMO-ACCT-ID; SET FLG-ACCTFILTER-ISVALID TO TRUE (L676-L677).

### Phase 4d -- 2220-EDIT-CARD

`app/cbl/COCRDSLC.cbl:L685-L720` mirrors the structure of 2210 for the card-number filter:

- Empty/zero check at L691-L702: if `CC-CARD-NUM EQUAL LOW-VALUES OR CC-CARD-NUM EQUAL SPACES OR CC-CARD-NUM-N EQUAL ZEROS`, set INPUT-ERROR, FLG-CARDFILTER-BLANK; if WS-RETURN-MSG-OFF, SET WS-PROMPT-FOR-CARD TO TRUE (binds to `'Card number not provided'` at L141); MOVE ZEROES TO CDEMO-CARD-NUM; GO TO exit.
- Numeric check at L706-L719: if `CC-CARD-NUM IS NOT NUMERIC`, SET INPUT-ERROR, SET FLG-CARDFILTER-NOT-OK. The inline message MOVE at L709-L712:

```text
           IF WS-RETURN-MSG-OFF
              MOVE
           'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'
                           TO WS-RETURN-MSG
           END-IF
```

The literal at `app/cbl/COCRDSLC.cbl:L711` also has NO space after the comma between `FILTER` and `IF`. This must be reproduced byte-for-byte. Valid-path: MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM; SET FLG-CARDFILTER-ISVALID TO TRUE (L717-L718).

## Phase 5 -- 9000-READ-DATA / 9100-GETCARD-BYACCTCARD Behavior

The 9000-READ-DATA paragraph at `app/cbl/COCRDSLC.cbl:L726-L730` is a thin wrapper that performs 9100-GETCARD-BYACCTCARD exclusively. The presence of a second `9150-GETCARD-BYACCT` paragraph at L779-L809 is documented in Phase 5e as dead code per AAP section 0.7.1.

### Phase 5a -- EXEC CICS READ (Plain READ, No UPDATE Clause)

`app/cbl/COCRDSLC.cbl:L736-L750` issues the CARDDAT lookup. The CRITICAL distinguishing feature: there is NO `UPDATE` clause on this READ. The commented-out alternative at L739 (`*    MOVE CC-ACCT-ID-N      TO WS-CARD-RID-ACCT-ID`) shows the dead-code-path setup for `9150-GETCARD-BYACCT` (see Phase 5e).

The verbatim COBOL fence at `app/cbl/COCRDSLC.cbl:L736-L750`:

```text
       9100-GETCARD-BYACCTCARD.
       *    Read the Card file
       *
       *    MOVE CC-ACCT-ID-N      TO WS-CARD-RID-ACCT-ID
           MOVE CC-CARD-NUM       TO WS-CARD-RID-CARDNUM

           EXEC CICS READ
                FILE      (LIT-CARDFILENAME)
                RIDFLD    (WS-CARD-RID-CARDNUM)
                KEYLENGTH (LENGTH OF WS-CARD-RID-CARDNUM)
                INTO      (CARD-RECORD)
                LENGTH    (LENGTH OF CARD-RECORD)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
```

The lookup is keyed by CARD-NUM (the 16-byte primary key on CARDDAT VSAM KSDS), NOT by ACCT-ID. This distinguishes COCRDSLC from COCRDUPC, which uses `READ UPDATE` followed by `REWRITE` to mutate the record. COCRDSLC has no record-lock semantics, no rewrite, and no post-run file-state change.

### Phase 5b -- WHEN DFHRESP(NORMAL): FOUND-CARDS-FOR-ACCOUNT

`app/cbl/COCRDSLC.cbl:L752-L754`:

```text
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  SET FOUND-CARDS-FOR-ACCOUNT TO TRUE
```

`FOUND-CARDS-FOR-ACCOUNT` is the 88-level on `WS-INFO-MSG` that binds the message to `'   Displaying requested details'` at `app/cbl/COCRDSLC.cbl:L129-L130` (with exactly 3 leading spaces). The info message is later moved into `INFOMSGO` of the output map at L496 within 1200-SETUP-SCREEN-VARS.

### Phase 5c -- WHEN DFHRESP(NOTFND): DID-NOT-FIND-ACCTCARD-COMBO

`app/cbl/COCRDSLC.cbl:L755-L761`:

```text
               WHEN DFHRESP(NOTFND)
                  SET INPUT-ERROR                    TO TRUE
                  SET FLG-ACCTFILTER-NOT-OK          TO TRUE
                  SET FLG-CARDFILTER-NOT-OK          TO TRUE
                  IF  WS-RETURN-MSG-OFF
                      SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE
                  END-IF
```

`DID-NOT-FIND-ACCTCARD-COMBO` binds `WS-RETURN-MSG` to `'Did not find cards for this search condition'` at `app/cbl/COCRDSLC.cbl:L153-L154`. Both filter flags are flipped to NOT-OK so 1300-SETUP-SCREEN-ATTRS applies DFHRED color to both filter fields.

### Phase 5d -- WHEN OTHER: WS-FILE-ERROR-MESSAGE Template

`app/cbl/COCRDSLC.cbl:L762-L771`:

```text
               WHEN OTHER
                  SET INPUT-ERROR                    TO TRUE
                  IF  WS-RETURN-MSG-OFF
                      SET FLG-ACCTFILTER-NOT-OK      TO TRUE
                  END-IF
                  MOVE 'READ'                        TO ERROR-OPNAME
                  MOVE LIT-CARDFILENAME              TO ERROR-FILE
                  MOVE WS-RESP-CD                    TO ERROR-RESP
                  MOVE WS-REAS-CD                    TO ERROR-RESP2
                  MOVE WS-FILE-ERROR-MESSAGE         TO WS-RETURN-MSG
```

`WS-FILE-ERROR-MESSAGE` is the 50-character templated message defined at `app/cbl/COCRDSLC.cbl:L102-L121` (inside `WS-MISC-STORAGE`, NOT in CSMSG02Y as one might expect from the abend variables copybook). The rendered template format is `File Error: READ on CARDDAT  returned RESP <code>,RESP2 <code>` (note the two spaces after `CARDDAT` because `LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '` includes the trailing space and `ERROR-FILE PIC X(9)` provides one additional column).

### Phase 5e -- 9150-GETCARD-BYACCT (Dead Code Path)

`app/cbl/COCRDSLC.cbl:L779-L812` defines an alternate-index lookup path that is NEVER INVOKED by the orchestrator. The 9000-READ-DATA paragraph at L726-L730 calls only 9100-GETCARD-BYACCTCARD; no other paragraph in the program calls 9150. This paragraph references:

- `LIT-CARDFILENAME-ACCT-PATH` = `'CARDAIX '` at L190
- `WS-CARD-RID-ACCT-ID` (the 11-byte numeric key, populated from `CC-ACCT-ID-N`)
- The DEAD message `'Did not find this account in cards database'` at L151-L152 (DID-NOT-FIND-ACCT-IN-CARDXREF 88-level condition)
- The DEAD message `'Error reading Card Data File'` at L155-L156 (XREF-READ-ERROR 88-level condition)

Per AAP section 0.7.1 ("translate faithfully, do not fix"), the Java translation MUST include the dead-code path as a private method `private void getCardByAcct()` on `CoCrdSlC` and flag it in `java/MIGRATION_NOTES.md` section 1.6 as DEAD CODE PRESERVED. The method is never called from `mainDispatch()` or `readData()`. The 88-level condition messages SEARCHED-ACCT-ZEROES, SEARCHED-ACCT-NOT-NUMERIC (L144-L147), SEARCHED-CARD-NOT-NUMERIC (L148-L149), DID-NOT-FIND-ACCT-IN-CARDXREF (L151-L152), XREF-READ-ERROR (L155-L156), and CODING-TO-BE-DONE (L157-L158) are likewise dead in current flow; the catalog in the supplementary section enumerates them.

## Phase 6 -- Screen Build: 1100-SCREEN-INIT, 1200-SETUP-SCREEN-VARS, 1300-SETUP-SCREEN-ATTRS

The 1000-SEND-MAP paragraph at `app/cbl/COCRDSLC.cbl:L412-L425` orchestrates four sub-paragraphs in sequence: 1100-SCREEN-INIT (clear + header), 1200-SETUP-SCREEN-VARS (data binding), 1300-SETUP-SCREEN-ATTRS (color, cursor, protect), and 1400-SEND-SCREEN (the actual SEND, documented in Phase 8).

### Phase 6a -- 1100-SCREEN-INIT

`app/cbl/COCRDSLC.cbl:L427-L455`:

1. MOVE LOW-VALUES TO CCRDSLAO (clear the entire output map buffer at L428)
2. MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA at L430 (and again at L437; the duplicate is preserved verbatim per AAP section 0.7.1)
3. MOVE CCDA-TITLE01 TO TITLE01O OF CCRDSLAO at L432 (from `app/cpy/COTTL01Y.cpy:L18` value `'      AWS Mainframe Modernization       '`)
4. MOVE CCDA-TITLE02 TO TITLE02O OF CCRDSLAO at L433 (from `app/cpy/COTTL01Y.cpy:L20-L22` value `'              CardDemo                  '`)
5. MOVE LIT-THISTRANID TO TRNNAMEO OF CCRDSLAO at L434
6. MOVE LIT-THISPGM TO PGMNAMEO OF CCRDSLAO at L435
7. MOVE WS-CURDATE fields into WS-CURDATE-MM-DD-YY at L439-L441 and then TO CURDATEO at L443 (formatted `mm/dd/yy`)
8. MOVE WS-CURTIME fields into WS-CURTIME-HH-MM-SS at L445-L447 and then TO CURTIMEO at L449 (formatted `hh:mm:ss`)

The Java translation must compute the date/time deterministically via an injected `Clock` per AAP section 0.6.6. The test harness binds a fixed `Clock` via `ScopedValue<Clock>` so `CURDATEO` and `CURTIMEO` are deterministic for the parity comparison. `java.time.LocalDate` and `java.time.LocalTime` are mandated by AAP section 0.6.4. The forbidden legacy date and time APIs documented in AAP section 0.6.4 are out of scope for this translation.

### Phase 6b -- 1200-SETUP-SCREEN-VARS

`app/cbl/COCRDSLC.cbl:L457-L500`. Conditional population by execution context:

- IF EIBCALEN = 0 (L459-L460): SET WS-PROMPT-FOR-INPUT TO TRUE (binds `WS-INFO-MSG` to `'Please enter Account and Card Number'` at L131-L132).
- ELSE (L461-L486): populate ACCTSIDO from CDEMO-ACCT-ID or LOW-VALUES if zero; populate CARDSIDO from CDEMO-CARD-NUM or LOW-VALUES if zero.
- IF FOUND-CARDS-FOR-ACCOUNT (L474-L485): populate output detail fields from CARD-RECORD (15-line block):
  - CRDNAMEO from CARD-EMBOSSED-NAME (50 bytes; from `app/cpy/CVACT02Y.cpy:L8`)
  - CARD-EXPIRAION-DATE is moved into the redefined CARD-EXPIRAION-DATE-X group at L477-L478 (preserving the original misspelling per `app/cpy/CVACT02Y.cpy:L9`)
  - EXPMONO from CARD-EXPIRY-MONTH (2 bytes, derived from the redefined view) at L480
  - EXPYEARO from CARD-EXPIRY-YEAR (4 bytes) at L482
  - CRDSTCDO from CARD-ACTIVE-STATUS (1 byte, `Y` or `N`; from `app/cpy/CVACT02Y.cpy:L10`) at L484

For the Java translation, `CARD-EXPIRAION-DATE` is parsed as `LocalDate` using `DateTimeFormatter.ofPattern("yyyy-MM-dd")` per AAP section 0.6.4 (the source format is 10 characters, e.g. `2024-12-31`, matching ISO_LOCAL_DATE). The COBOL misspelling `EXPIRAION` is preserved in the Java record field name `cardExpiraionDate` to remain faithful to `CVACT02Y.cpy:L9`.

Finally L490-L496: IF `WS-NO-INFO-MESSAGE`, default to WS-PROMPT-FOR-INPUT; MOVE WS-RETURN-MSG TO ERRMSGO at L494; MOVE WS-INFO-MSG TO INFOMSGO at L496.

### Phase 6c -- 1300-SETUP-SCREEN-ATTRS

`app/cbl/COCRDSLC.cbl:L502-L558`. Conditional attributes built into the output map:

1. Protect/unprotect at L505-L512: IF CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM, MOVE DFHBMPRF (protect) TO ACCTSIDA / CARDSIDA OF CCRDSLAI; ELSE MOVE DFHBMFSE (unprotect, force-erase, set-front-stop) TO ACCTSIDA / CARDSIDA.
2. Cursor positioning EVALUATE TRUE at L515-L524: WHEN FLG-ACCTFILTER-NOT-OK / FLG-ACCTFILTER-BLANK, MOVE -1 TO ACCTSIDL; WHEN FLG-CARDFILTER-NOT-OK / FLG-CARDFILTER-BLANK, MOVE -1 TO CARDSIDL; WHEN OTHER, MOVE -1 TO ACCTSIDL (default to ACCTSID).
3. Color resets at L527-L531: IF from COCRDLIC, MOVE DFHDFCOL (default color) TO ACCTSIDC / CARDSIDC of CCRDSLAO.
4. Error color at L533-L539: IF FLG-ACCTFILTER-NOT-OK, MOVE DFHRED TO ACCTSIDC; IF FLG-CARDFILTER-NOT-OK, MOVE DFHRED TO CARDSIDC.
5. Blank-with-reenter at L541-L551: IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER, MOVE '*' TO ACCTSIDO + DFHRED TO ACCTSIDC; same for FLG-CARDFILTER-BLANK with CARDSIDO / CARDSIDC.
6. INFOMSG color at L553-L557: IF WS-NO-INFO-MESSAGE, MOVE DFHBMDAR (dark) TO INFOMSGC; ELSE MOVE DFHNEUTR (neutral) TO INFOMSGC.

The DFH* attribute byte constants come from `COPY DFHBMSCA.` at `app/cbl/COCRDSLC.cbl:L208`. The Java translation defines a `ScreenAttributeSetter` utility (from `app/cpy/CSSETATY.cpy` per AAP section 0.4.1) that exposes these byte constants as static finals.

## Phase 7 -- COMMON-RETURN: EXEC CICS RETURN with COMMAREA

`app/cbl/COCRDSLC.cbl:L394-L407` is the COMMON-RETURN paragraph. It performs two actions:

1. Serialize the in-memory commarea: MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG at L395, then MOVE CARDDEMO-COMMAREA TO WS-COMMAREA at L397, then MOVE WS-THIS-PROGCOMMAREA TO WS-COMMAREA at offset `LENGTH OF CARDDEMO-COMMAREA + 1` via reference modification at L398-L400.
2. Issue the EXEC CICS RETURN at L402-L406:

```text
           EXEC CICS RETURN
                TRANSID (LIT-THISTRANID)
                COMMAREA (WS-COMMAREA)
                LENGTH(LENGTH OF WS-COMMAREA)
           END-EXEC
```

The TRANSID is always `CCDL` (the program's own transaction; the pseudo-conversational loop re-enters COCRDSLC). The COMMAREA is the 2000-byte `WS-COMMAREA` per Phase 2. The Java translation expresses this return shape as the `CoCrdSlOutput` record carrying the serialized 2000-byte commarea plus the BMS map output buffer.

## Phase 8 -- 1400-SEND-SCREEN: EXEC CICS SEND MAP

`app/cbl/COCRDSLC.cbl:L563-L580` is the actual SEND. The verbatim fence:

```text
       1400-SEND-SCREEN.

           MOVE LIT-THISMAPSET         TO CCARD-NEXT-MAPSET
           MOVE LIT-THISMAP            TO CCARD-NEXT-MAP
           SET  CDEMO-PGM-REENTER TO TRUE

           EXEC CICS SEND MAP(CCARD-NEXT-MAP)
                          MAPSET(CCARD-NEXT-MAPSET)
                          FROM(CCRDSLAO)
                          CURSOR
                          ERASE
                          FREEKB
                          RESP(WS-RESP-CD)
           END-EXEC
```

`SET CDEMO-PGM-REENTER TO TRUE` at L567, executed immediately before the SEND, means subsequent invocations of COCRDSLC under the same pseudo-conversation fall through the `WHEN CDEMO-PGM-REENTER` branch of 0000-MAIN (Phase 3c branch 4). The CURSOR, ERASE, and FREEKB options preserve standard CICS terminal behavior: cursor positioned per the `-1` MOVE in 1300-SETUP-SCREEN-ATTRS, screen erased before paint, and keyboard unlocked after paint.

## Phase 9 -- ABEND-ROUTINE: ABEND ABCODE 9999

`app/cbl/COCRDSLC.cbl:L857-L878` is the ABEND-ROUTINE handler. It is registered via `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)` at L250-L252 at the top of 0000-MAIN. Sequence of actions:

1. IF ABEND-MSG EQUAL LOW-VALUES, MOVE `'UNEXPECTED ABEND OCCURRED.'` TO ABEND-MSG at L859-L861 (binds the default abend message verbatim at L860).
2. MOVE LIT-THISPGM TO ABEND-CULPRIT at L863 (binds `'COCRDSLC'`).
3. EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA) NOHANDLE at L865-L869 (paint the abend frame to the terminal).
4. EXEC CICS HANDLE ABEND CANCEL at L871-L873 (deregister the handler to avoid infinite loop on the subsequent ABEND).
5. EXEC CICS ABEND ABCODE(`'9999'`) at L875-L877 (terminate the transaction with abend code 9999).

The 0000-MAIN `WHEN OTHER` branch at L373-L380 takes a DIFFERENT path: it MOVES `'UNEXPECTED DATA SCENARIO'` TO WS-RETURN-MSG (NOT ABEND-MSG) at L377-L378, then PERFORMS SEND-PLAIN-TEXT at L379-L380, and continues. The text `'UNEXPECTED DATA SCENARIO'` is therefore observable in the SEND-PLAIN-TEXT output, not as part of the abend frame. The `'UNEXPECTED ABEND OCCURRED.'` text is the default placeholder inside ABEND-ROUTINE itself when no upstream caller has populated ABEND-MSG.

## Phase 10 -- BMS Map Layout (CCRDSLA, 24x80)

The `CCRDSLA` BMS map is defined in `app/bms/COCRDSL.bms` (157 lines). The map header at L25-L28 declares:

- `CTRL=(FREEKB)` (keyboard unlocked at paint completion)
- `DSATTS=(COLOR,HILIGHT,PS,VALIDN)`
- `MAPATTS=(COLOR,HILIGHT,PS,VALIDN)`
- `SIZE=(24,80)` (24 rows by 80 columns; total addressable cells 1920)

The BMS map contains exactly 12 named DFHMDF fields plus 10 unnamed label/separator DFHMDF entries, for a total of 22 DFHMDF declarations. The four sub-tables below enumerate them by row band.

### Phase 10a -- Header Row Fields (Rows 1-2)

| Field | Pos | Length | Attrs | Color | Initial | Lines |
|---|---|---|---|---|---|---|
| (label) `'Tran:'` | (1,1) | 5 | ASKIP,NORM | BLUE | `'Tran:'` | 29-33 |
| TRNNAME | (1,7) | 4 | ASKIP,FSET,NORM | BLUE | - | 34-37 |
| TITLE01 | (1,21) | 40 | ASKIP,NORM | YELLOW | - | 38-41 |
| (label) `'Date:'` | (1,65) | 5 | ASKIP,NORM | BLUE | `'Date:'` | 42-46 |
| CURDATE | (1,71) | 8 | ASKIP,NORM | BLUE | `'mm/dd/yy'` | 47-51 |
| (label) `'Prog:'` | (2,1) | 5 | ASKIP,NORM | BLUE | `'Prog:'` | 52-56 |
| PGMNAME | (2,7) | 8 | ASKIP,NORM | BLUE | - | 57-60 |
| TITLE02 | (2,21) | 40 | ASKIP,NORM | YELLOW | - | 61-64 |
| (label) `'Time:'` | (2,65) | 5 | ASKIP,NORM | BLUE | `'Time:'` | 65-69 |
| CURTIME | (2,71) | 8 | ASKIP,NORM | BLUE | `'hh:mm:ss'` | 70-74 |

### Phase 10b -- Editable Input Fields (Rows 7-8)

| Field | Pos | Length | Attrs | Color | Hilight | Source Lines |
|---|---|---|---|---|---|---|
| ACCTSID | (7,45) | 11 | FSET,IC,NORM,UNPROT | DEFAULT | UNDERLINE | 84-88 |
| CARDSID | (8,45) | 16 | FSET,NORM,UNPROT | DEFAULT | UNDERLINE | 96-100 |

There are EXACTLY TWO editable input fields on the CCRDSLA map. ACCTSID carries the `IC` (initial cursor) attribute placing the cursor on ACCTSID at first paint; CARDSID does not carry IC. The lengths 11 and 16 chars match the COBOL `PIC 9(11)` account-id and `PIC X(16)` card-number declarations from the symbolic copybook at `app/cpy-bms/COCRDSL.CPY:L60` and L66.

### Phase 10c -- Read-Only Output Fields (Rows 4, 11, 13, 15)

| Field | Pos | Length | Attrs | Color | Hilight | Initial | Source Lines |
|---|---|---|---|---|---|---|---|
| (heading) `'View Credit Card Detail'` | (4,30) | 23 | - | NEUTRAL | - | `'View Credit Card Detail'` | 75-78 |
| (label) `'Account Number    :'` | (7,23) | 19 | ASKIP,NORM | TURQUOISE | - | `'Account Number    :'` | 79-83 |
| (label) `'Card Number       :'` | (8,23) | 19 | ASKIP,NORM | TURQUOISE | - | `'Card Number       :'` | 91-95 |
| (label) `'Name on card      :'` | (11,4) | 20 | - | TURQUOISE | - | `'Name on card      :'` | 103-106 |
| CRDNAME | (11,25) | 50 | - | - | UNDERLINE | - | 107-109 |
| (label) `'Card Active Y/N   : '` | (13,4) | 20 | - | TURQUOISE | - | `'Card Active Y/N   : '` | 112-115 |
| CRDSTCD | (13,25) | 1 | ASKIP | - | UNDERLINE | - | 116-119 |
| (label) `'Expiry Date       : '` | (15,4) | 20 | - | TURQUOISE | - | `'Expiry Date       : '` | 122-125 |
| EXPMON | (15,25) | 2 | ASKIP | - | UNDERLINE | - | 126-129 |
| (separator) `'/'` | (15,28) | 1 | - | - | - | `'/'` | 130-132 |
| EXPYEAR | (15,30) | 4 | ASKIP | - | UNDERLINE | - | 133-136 |

The label texts in rows 7, 8, 11, 13, and 15 contain preserved internal whitespace (4 spaces in `'Account Number    :'`, 7 spaces in `'Card Number       :'`, 6 spaces in `'Name on card      :'`, 3 spaces in `'Card Active Y/N   : '` plus trailing space, 7 spaces in `'Expiry Date       : '` plus trailing space). These exact byte sequences are preserved verbatim per AAP section 0.7.1.

### Phase 10d -- Message and Function-Key Rows (Rows 20, 23, 24)

| Field | Pos | Length | Attrs | Color | Hilight | Initial | Source Lines |
|---|---|---|---|---|---|---|---|
| INFOMSG | (20,25) | 40 | PROT | NEUTRAL | OFF | - | 139-143 |
| ERRMSG | (23,1) | 80 | ASKIP,BRT,FSET | RED | - | - | 144-147 |
| FKEYS | (24,1) | 75 | ASKIP,NORM | YELLOW | - | `'ENTER=Search Cards  F3=Exit'` | 148-152 |

The FKEYS field carries the initial text `'ENTER=Search Cards  F3=Exit'` (with two spaces between `Cards` and `F3`) per `app/bms/COCRDSL.bms:L152`. The function-key allowance on CCRDSLA is therefore documented to the user as ENTER and F3 only; the silent coercion at COCRDSLC L291-L299 enforces this contract at the program level.

## Phase 11 -- Required Test Scenarios (12 Total)

### Phase 11a -- Scenario Inventory Table

| # | Scenario | EIBCALEN | AID | Fields | Expected Outcome |
|---|---|---|---|---|---|
| 1 | First-time entry | 0 | DFHENTER | (none) | INFOMSGO = `'Please enter Account and Card Number'`, cursor on ACCTSID |
| 2 | Both filters blank | 2000 | DFHENTER | ACCTSIDI blank + CARDSIDI blank | ERRMSGO = `'No input received'`, DFHRED on both filter fields, cursor on ACCTSID |
| 3 | ACCTSID blank only | 2000 | DFHENTER | ACCTSIDI blank, CARDSIDI valid | ERRMSGO = `'Account number not provided'`, DFHRED on ACCTSIDC, cursor on ACCTSID |
| 4 | CARDSID blank only | 2000 | DFHENTER | ACCTSIDI valid, CARDSIDI blank | ERRMSGO = `'Card number not provided'`, DFHRED on CARDSIDC, cursor on CARDSID |
| 5 | Non-numeric ACCTSID | 2000 | DFHENTER | ACCTSIDI = `'ABCDEFGHIJK'` | ERRMSGO = `'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'` |
| 6 | Non-numeric CARDSID | 2000 | DFHENTER | CARDSIDI non-numeric | ERRMSGO = `'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'` |
| 7 | Found in CARDDAT | 2000 | DFHENTER | ACCTSIDI + CARDSIDI valid and present in CARDDAT | FOUND-CARDS-FOR-ACCOUNT path; populate CRDNAMEO, CRDSTCDO, EXPMONO, EXPYEARO; INFOMSGO = `'   Displaying requested details'` |
| 8 | Not found in CARDDAT | 2000 | DFHENTER | ACCTSIDI + CARDSIDI valid but absent | ERRMSGO = `'Did not find cards for this search condition'`, both filter colors red |
| 9 | PF3 with EIBCALEN = 0 | 0 | DFHPF3 | (none) | XCTL to COMEN01C with CDEMO-FROM-PROGRAM and CDEMO-FROM-TRANID updated |
| 10 | PF3 from COCRDLIC | 2000 | DFHPF3 | COMMAREA: CDEMO-FROM-PROGRAM = `'COCRDLIC'` | XCTL to COCRDLIC |
| 11 | Auto-trigger from COCRDLIC | 2000 | DFHENTER | COMMAREA: CDEMO-PGM-CONTEXT = 0, CDEMO-FROM-PROGRAM = `'COCRDLIC'`, CDEMO-ACCT-ID populated, CDEMO-CARD-NUM populated | FOUND-CARDS-FOR-ACCOUNT path; bypass validation; populate detail fields |
| 12 | Invalid PFK (PF04) | 2000 | DFHPF4 | (empty input fields) | AID coerced to ENTER (L291-L299), then validation pipeline runs, ERRMSGO = `'No input received'` |

### Phase 11b -- EXPECT_CARDDAT_UNCHANGED Invariant

Every scenario in the inventory above asserts the directive `EXPECT_CARDDAT_UNCHANGED`. The justification is structural: COCRDSLC issues only `EXEC CICS READ` at L742-L750 and L783-L791. There is no `READ UPDATE`, no `REWRITE`, no `WRITE`, no `WRITE FROM`, no `DELETE`, and no `STARTBR/READPREV/ENDBR` sequence anywhere in `app/cbl/COCRDSLC.cbl`. The CARDDAT VSAM file (translated to the ASCII fixture `app/data/ASCII/carddata.txt` per AAP section 0.4.1) MUST be byte-identical pre-run and post-run.

This invariant guards against accidental introduction of write-side effects during translation. For example, mistakenly opening `carddata.txt` with `Files.write(..., StandardOpenOption.WRITE)` or with a `SeekableByteChannel` in WRITE mode would mutate the fixture and break the invariant. The test harness verifies the invariant via SHA-256 hash equality on the CARDDAT fixture file before and after each scenario.

## Phase 12 -- Files in This Directory (4 Total)

| Filename | Type | Status | Size | Purpose |
|---|---|---|---|---|
| `README.md` | Documentation | CREATED (this file) | 500-700 lines | Authoritative 13-phase fixture contract |
| `input_scenario.txt` | Test driver | CREATED | 200-280 lines | TEST-OWNED CICS pseudo-conversation driver, 12 scenarios |
| `stdout.txt` | Capture placeholder | CREATED | 8 lines | Captured COBOL stdout (expected EMPTY because COCRDSLC issues no DISPLAY statements) |
| `bms_output.txt` | Capture placeholder | CREATED | 13 lines | Captured BMS SEND MAP frames (approximately 12 frames after capture) |

There is no `carddata.txt` in this folder. The CARDDAT fixture is `app/data/ASCII/carddata.txt`, read REFERENCE-only via classpath relative path per AAP section 0.2.1 and 0.4.1. The decision to NOT copy `carddata.txt` into this fixture directory avoids three risks: (a) duplication of fixture data (single source of truth: `app/data/ASCII/`), (b) fixture drift (the test reading a stale local copy after the baseline is updated), and (c) bloat of the `src/test/resources` tree. COCRDSLC has no mutation concern, so a per-scenario baseline copy is unnecessary.

## Phase 13 -- Structural Invariants

### Phase 13a -- BMS Map Invariant

The CCRDSLA map (per `app/bms/COCRDSL.bms`) has EXACTLY TWO editable input fields (ACCTSID at L84-L88 and CARDSID at L96-L100). All other fields are either ASKIP (skip on cursor advance) or PROT (protected). Read-only display fields (CRDNAME, CRDSTCD, EXPMON, EXPYEAR) are ASKIP. Function-key allowance: ENTER and F3 only, per the FKEYS initial text at L152. Per AAP section 0.7.1, any change to the BMS layout (adding fields, changing positions, changing lengths, changing attributes, changing colors) is a behavior change and is FORBIDDEN by this refactor.

### Phase 13b -- CARDDAT No-Modification Invariant

COCRDSLC is the canonical READ-only program in the Card Demo set. Every scenario in the test inventory asserts EXPECT_CARDDAT_UNCHANGED. Any Java implementation that opens `carddata.txt` with a write option, modifies `CardRecord` instances in place and writes them back, or introduces caching that bypasses the per-call re-read MUST fail this invariant test. The Java `FileCardRepository` adapter at `com.blitzy.carddemo.adapter.file.FileCardRepository` (per AAP section 0.4.1) opens the file in READ-only mode via `Files.newByteChannel(path, StandardOpenOption.READ)` per AAP section 0.6.5.

### Phase 13c -- READ-Only Quirk vs COCRDUPC's READ-UPDATE-REWRITE

COCRDSLC issues `EXEC CICS READ` WITHOUT the `UPDATE` clause at `app/cbl/COCRDSLC.cbl:L742-L750`. This is structurally simpler than the sibling COCRDUPC, which issues `EXEC CICS READ ... UPDATE` (acquiring a record lock) followed by `EXEC CICS REWRITE` (releasing the lock with the modified record). COCRDSLC has no record-lock concerns, no concurrent-update conflict semantics, and produces no post-run state change.

| Aspect | COCRDSLC | COCRDUPC |
|---|---|---|
| READ clause | `READ` (no UPDATE) | `READ UPDATE` |
| Subsequent write | none | `REWRITE` |
| Lock acquired | no | yes |
| Post-run file state | identical | mutated |
| Test invariant | EXPECT_CARDDAT_UNCHANGED | EXPECT_CARDDAT_MUTATED |

### Phase 13d -- Java Class Mapping (@CobolProgram annotation)

The Java translation `com.blitzy.carddemo.application.card.CoCrdSlC` MUST carry the `@CobolProgram` annotation (per AAP section 0.3.1) citing:

- PROGRAM-ID: `COCRDSLC`
- Source path: `app/cbl/COCRDSLC.cbl`
- Translation date: (filled at code-generation time)

The public entry point method is `public CoCrdSlOutput handle(CoCrdSlInput input, CardDemoCommarea commarea)`. The method signature preserves the LINKAGE SECTION shape per AAP section 0.7.2. Private methods correspond to internal paragraphs: `mainDispatch()` for 0000-MAIN, `sendMap()` for 1000-SEND-MAP, `screenInit()` for 1100-SCREEN-INIT, `setupScreenVars()` for 1200-SETUP-SCREEN-VARS, `setupScreenAttrs()` for 1300-SETUP-SCREEN-ATTRS, `sendScreen()` for 1400-SEND-SCREEN, `processInputs()` for 2000-PROCESS-INPUTS, `receiveMap()` for 2100-RECEIVE-MAP, `editMapInputs()` for 2200-EDIT-MAP-INPUTS, `editAccount()` for 2210-EDIT-ACCOUNT, `editCard()` for 2220-EDIT-CARD, `readData()` for 9000-READ-DATA, `getCardByAcctCard()` for 9100-GETCARD-BYACCTCARD, `getCardByAcct()` for 9150-GETCARD-BYACCT (dead code preserved), and `abendRoutine()` for the abend handler.

### Phase 13e -- PAN Masking Invariant

Per AAP section 0.7.2, any 16-digit card-number value MUST be masked to show only the last 4 digits in any captured output surface (including `bms_output.txt`). The mask character is ASCII `*` (0x2A). Example: a 16-character card-number captured in CARDSIDO is rendered as `************0001` (12 stars followed by the last 4 digits).

CRITICAL: the masking is applied at CAPTURE time to the captured output file, NOT at SEND time inside the COBOL or Java program. The actual BMS frame buffer sent to the terminal contains the unmasked card-number; the capture script masks it before writing to disk. This is necessary so the Java translation produces byte-identical SEND output to COBOL (both unmasked at the wire), and the parity test compares masked-to-masked. If the masking were applied at SEND time inside Java but not inside COBOL, the parity test would fail spuriously. The capture procedure documented in `java/MIGRATION_NOTES.md` section 1.6 enumerates the exact masking steps.

## Verbatim COBOL Message Catalog

The 16 verbatim text messages emitted (or referenced) by COCRDSLC, with source-line citations into `app/cbl/COCRDSLC.cbl`. Each message is reproduced byte-for-byte; whitespace before, within, and after each quoted literal is preserved.

| # | Message Text (verbatim, including leading or trailing whitespace) | Source Line | Condition Name or Context |
|---|---|---|---|
| 1 | `'   Displaying requested details'` (3 leading spaces) | 130 | FOUND-CARDS-FOR-ACCOUNT (WS-INFO-MSG 88-level) |
| 2 | `'Please enter Account and Card Number'` | 132 | WS-PROMPT-FOR-INPUT (WS-INFO-MSG 88-level) |
| 3 | `'PF03 pressed.Exiting              '` (14 trailing spaces) | 137 | WS-EXIT-MESSAGE (WS-RETURN-MSG 88-level) |
| 4 | `'Account number not provided'` | 139 | WS-PROMPT-FOR-ACCT (WS-RETURN-MSG 88-level) |
| 5 | `'Card number not provided'` | 141 | WS-PROMPT-FOR-CARD (WS-RETURN-MSG 88-level) |
| 6 | `'No input received'` | 143 | NO-SEARCH-CRITERIA-RECEIVED (WS-RETURN-MSG 88-level) |
| 7 | `'Account number must be a non zero 11 digit number'` | 145, 147 | SEARCHED-ACCT-ZEROES and SEARCHED-ACCT-NOT-NUMERIC (identical text; DEAD: never raised by current flow) |
| 8 | `'Card number if supplied must be a 16 digit number'` | 149 | SEARCHED-CARD-NOT-NUMERIC (DEAD) |
| 9 | `'Did not find this account in cards database'` | 152 | DID-NOT-FIND-ACCT-IN-CARDXREF (DEAD; only reachable via 9150 path) |
| 10 | `'Did not find cards for this search condition'` | 154 | DID-NOT-FIND-ACCTCARD-COMBO (WS-RETURN-MSG 88-level) |
| 11 | `'Error reading Card Data File'` | 156 | XREF-READ-ERROR (DEAD; only reachable via 9150 path) |
| 12 | `'Looks Good.... so far'` (4 periods, space, `so far`) | 158 | CODING-TO-BE-DONE (DEAD: debug placeholder) |
| 13 | `'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'` (no space after comma) | 670 | Inline MOVE in 2210-EDIT-ACCOUNT |
| 14 | `'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'` (no space after comma) | 711 | Inline MOVE in 2220-EDIT-CARD |
| 15 | `'UNEXPECTED DATA SCENARIO'` | 377 | 0000-MAIN WHEN OTHER (MOVE to WS-RETURN-MSG) |
| 16 | `'UNEXPECTED ABEND OCCURRED.'` | 860 | ABEND-ROUTINE default ABEND-MSG |

Messages 7, 8, 9, 11, and 12 are DEAD CODE in the current flow. Messages 9 and 11 are only reachable via `9150-GETCARD-BYACCT` (which is never invoked). Messages 7, 8, and 12 are 88-level conditions that no SET statement ever triggers in the current source. Per AAP section 0.7.1 ("translate faithfully, do not fix"), the Java translation MUST preserve all 16 messages verbatim in the corresponding 88-level enumerations (sealed types) and flag the dead messages in `java/MIGRATION_NOTES.md` section 1.6 as DEAD MESSAGE PRESERVED.

## Capture Procedure Cross-Reference

The procedure for capturing the COBOL baseline outputs is documented in `java/MIGRATION_NOTES.md` section 1.6 per AAP section 0.7.5. Summary of the capture steps:

1. Load `app/data/ASCII/carddata.txt` into a CICS-compatible VSAM CARDDAT file (or its in-memory equivalent for a CICS test harness).
2. Bind a fixed `Clock` instant via environment variable or job-control parameter so that `1100-SCREEN-INIT` produces deterministic `CURDATEO` and `CURTIMEO` output.
3. Replay each of the 12 scenarios from `input_scenario.txt` against COCRDSLC running under a CICS test harness, simulating the pseudo-conversational EIBCALEN and EIBAID inputs documented in Phase 11a.
4. Capture stdout (expected EMPTY because COCRDSLC issues no `DISPLAY` statements; the stdout placeholder exists to document this expectation) and the 1920-byte CCRDSLAO output buffer from each `EXEC CICS SEND MAP`.
5. Apply PAN masking (Phase 13e) to the captured CARDSIDO bytes within each 1920-byte frame.
6. Concatenate the 12 captured frames into `bms_output.txt` with `=== SCENARIO n ===` separators.
7. Commit `bms_output.txt` and `stdout.txt`, replacing the placeholders shipped with this fixture.
8. Remove the `@Disabled` annotation from `CoCrdSlCGoldenTest` per AAP section 0.6.11.

## Source Lineage

| Source File | Purpose | Status |
|---|---|---|
| `app/cbl/COCRDSLC.cbl` | COBOL program (887 lines) | REFERENCE only (unchanged per AAP section 0.1.1) |
| `app/bms/COCRDSL.bms` | BMS map definition (157 lines) | REFERENCE only |
| `app/cpy-bms/COCRDSL.CPY` | Symbolic copybook (200 lines, defines CCRDSLAI / CCRDSLAO) | REFERENCE only |
| `app/cpy/CVACT02Y.cpy` | CARD-RECORD layout (14 lines, 150-byte record) | REFERENCE only |
| `app/cpy/COCOM01Y.cpy` | CARDDEMO-COMMAREA (48 lines) | REFERENCE only |
| `app/cpy/CVCRD01Y.cpy` | CC-WORK-AREA: AID conditions, REDEFINES, work-state fields | REFERENCE only |
| `app/cpy/CSDAT01Y.cpy` | Current date/time work area (formatted output) | REFERENCE only |
| `app/cpy/CSMSG01Y.cpy` | Common messages (thank-you, invalid-key) | REFERENCE only |
| `app/cpy/CSMSG02Y.cpy` | ABEND-DATA work area (ABEND-CODE, ABEND-CULPRIT, ABEND-REASON, ABEND-MSG) | REFERENCE only |
| `app/cpy/COTTL01Y.cpy` | Screen titles (CCDA-TITLE01, CCDA-TITLE02, CCDA-THANK-YOU) | REFERENCE only |
| `app/data/ASCII/carddata.txt` | CARD-RECORD ASCII fixture (one record per line, 150 bytes each) | REFERENCE only via classpath |

## Cross-References

The Java cross-references for this fixture:

- Java application class: `com.blitzy.carddemo.application.card.CoCrdSlC`
- Java BMS DTOs: `com.blitzy.carddemo.application.card.CoCrdSlInput` and `com.blitzy.carddemo.application.card.CoCrdSlOutput`
- Java record types: `com.blitzy.carddemo.domain.record.CardRecord` (from `app/cpy/CVACT02Y.cpy`), `com.blitzy.carddemo.domain.commarea.CardDemoCommarea` (from `app/cpy/COCOM01Y.cpy`)
- Java sealed types: `com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey` (permits Enter, Pa1, Pa2, Clear, PfKey01..PfKey12; from `app/cpy/CVCRD01Y.cpy:L3-L19`)
- Java port: `com.blitzy.carddemo.domain.port.CardRepository`
- Java adapter (file): `com.blitzy.carddemo.adapter.file.FileCardRepository` (per AAP section 0.4.1)
- Java test class: `com.blitzy.carddemo.tests.golden.CoCrdSlCGoldenTest`
- Java test base class: `com.blitzy.carddemo.tests.golden.GoldenRecordTest`
- Java utility: `com.blitzy.carddemo.application.util.ScreenAttributeSetter` (from `app/cpy/CSSETATY.cpy`)
- Java utility: `com.blitzy.carddemo.application.util.PfKeyDecoder` (from `app/cpy/CSSTRPFY.cpy` brought in by `COPY 'CSSTRPFY'` at `app/cbl/COCRDSLC.cbl:L855`)

## Authority References

The following AAP sections are binding for this fixture:

- AAP section 0.1.1: COBOL source under `app/` MUST remain unchanged
- AAP section 0.2.1: Files in scope for CREATE (this fixture is enumerated)
- AAP section 0.2.2: Files explicitly OUT OF SCOPE (all of `app/`)
- AAP section 0.3.1: Hexagonal architecture and `@CobolProgram` annotation
- AAP section 0.4.1: Java class naming convention `com.blitzy.carddemo.application.card.CoCrdSlC`
- AAP section 0.6.4: `java.time` mandate (LocalDate for CARD-EXPIRAION-DATE)
- AAP section 0.6.5: `java.nio.file` mandate for all file I/O
- AAP section 0.6.6: `ScopedValue` for cross-method context propagation; fixed-Clock injection for deterministic timestamps
- AAP section 0.6.11: Golden-record PR gate; `@Disabled` until capture committed
- AAP section 0.6.12: architectural override of historical tech-spec drafts; no framework container, no relational persistence by default
- AAP section 0.7.1: Minimal Change Clause; verbatim messages byte-exact; dead-code preserved
- AAP section 0.7.2: PAN masking (last 4 visible) in all logs and BMS output captures
- AAP section 0.7.4: No JEP preview features
- AAP section 0.7.5: Capture procedure documented in `java/MIGRATION_NOTES.md` section 1.6

## Contrast Matrix (COCRDSLC vs COCRDUPC vs COCRDLIC)

| Aspect | COCRDSLC | COCRDUPC | COCRDLIC |
|---|---|---|---|
| Function | View card detail | Update card detail | List cards |
| CICS transaction | CCDL | CCUP | CCLI |
| BMS mapset | COCRDSL | COCRDUP | COCRDLI |
| BMS map | CCRDSLA | CCRDUPA | CCRDLIA |
| Source file | `app/cbl/COCRDSLC.cbl` | `app/cbl/COCRDUPC.cbl` | `app/cbl/COCRDLIC.cbl` |
| Source lines | 887 | (per source) | (per source) |
| CARDDAT op | `READ` only | `READ UPDATE` + `REWRITE` | `STARTBR` + `READNEXT` + `ENDBR` |
| Editable BMS fields | 2 (ACCTSID, CARDSID) | 7 (4 visible + 2 search + 1 hidden EXPDAY) | 2 (ACCTSID, CARDSID) |
| AID keys supported | ENTER, PF3 | ENTER, PF3, PF5, PF12 | ENTER, PF3, PF7, PF8 |
| State machine | None (5 EVALUATE branches) | 7-state CCUP-CHANGE-ACTION | Pagination state |
| Caller programs | COCRDLIC, COMEN01C | COCRDLIC, COCRDSLC, COMEN01C | COMEN01C, COADM01C |
| Java class | CoCrdSlC | CoCrdUpC | CoCrdLiC |
| Post-run file state | UNCHANGED | MUTATED | UNCHANGED |
| Heading text | `'View Credit Card Detail'` | `'Update Credit Card Detail'` | `'List Credit Cards'` (or similar) |
| Number of verbatim messages | 16 | 29 | (per source) |
| Has dead-code paragraph? | Yes (9150-GETCARD-BYACCT) | No | No |

## DO NOT Modify Without Re-Capture

```text
WARNING: The 4 files in this directory form the COCRDSLC PR gate per AAP
section 0.6.11. Modifying any of:
   - README.md            (this file, the authoritative contract)
   - input_scenario.txt   (TEST-OWNED 12-scenario driver)
   - stdout.txt           (capture placeholder, replaced after COBOL run)
   - bms_output.txt       (capture placeholder, replaced after COBOL run)

requires a paired re-capture from the COBOL baseline per
java/MIGRATION_NOTES.md section 1.6. Specifically:
   - Changing any verbatim message text invalidates the COBOL/Java parity
     and MUST be backed by an `app/cbl/COCRDSLC.cbl` source change (which
     is FORBIDDEN per AAP 0.1.1).
   - Adding or removing scenarios changes the test surface and requires
     re-capture of bms_output.txt and stdout.txt.
   - Changing the BMS field tables in Phase 10 changes the test contract
     and requires a paired update of input_scenario.txt and re-capture
     of bms_output.txt.

Any uncoordinated edit will cause CoCrdSlCGoldenTest to fail on next CI run
or, worse, will silently mask a real regression. Always pair edits with
capture runs.
```
