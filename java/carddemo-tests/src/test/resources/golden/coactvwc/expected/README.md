# COACTVWC (Account View, CAVW) -- Golden-Record Fixture Contract

## Phase 0 -- Header and Authority Cascade

This folder holds the authoritative golden-record fixture contract for the COACTVWC (Account View, CICS transaction `CAVW`) Java translation. Every fact below is derived by direct citation of source-line locators within the unchanged COBOL source tree under `app/`. This README is the single source of truth that binds the captured COBOL baseline artifacts in this directory to the Java translation under test and to the test harness that asserts byte-level parity.

Authority cascade (binding):

- AAP section 0.2.1 includes this fixture under the `java/carddemo-tests/src/test/resources/golden/**/*` trailing-wildcard scope for CREATE.
- AAP section 0.3.1 mandates the hexagonal layout and the `@CobolProgram` traceability annotation on every translated class.
- AAP section 0.4.1 fixes the translation target: `COACTVWC` -> `com.blitzy.carddemo.application.account.CoActVwC` in the `application.account` subpackage (NOT `application.card`).
- AAP section 0.6.11 mandates byte-for-byte golden-record parity as the non-negotiable PR gate and requires `@Disabled` until a COBOL capture commits real bytes for the placeholder artifacts in this directory.
- AAP section 0.7.1 (Minimal Change Clause) governs verbatim message preservation, including every quirk such as the L672 double-space and the L408/L411 duplicate paragraph label.
- AAP section 0.7.2 mandates PAN masking (last 4 digits visible only) for any 16-digit card number that reaches captured logs or diagnostic outputs.

Java translation target. The COBOL `PROGRAM-ID COACTVWC` maps to `com.blitzy.carddemo.application.account.CoActVwC`. The companion BMS DTOs are `com.blitzy.carddemo.application.account.CoActVwInput` (the input record reflecting the CACTVWAI symbolic copybook) and `com.blitzy.carddemo.application.account.CoActVwOutput` (the output record reflecting the CACTVWAO symbolic copybook). The class carries a `@CobolProgram("COACTVWC")` Javadoc-style annotation citing the original PROGRAM-ID, the source path `app/cbl/COACTVWC.cbl`, and the translation date per AAP section 0.3.1.

Java test class. `com.blitzy.carddemo.tests.golden.CoActVwCGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test class MUST carry `@Disabled("Pending COBOL baseline capture -- see java/MIGRATION_NOTES.md section 1.6")` per AAP section 0.6.11 until captured artifacts replace the placeholders in this directory.

## Phase 1 -- COBOL Source: PROGRAM-ID and WS-VARIABLES

The source file `app/cbl/COACTVWC.cbl` is 941 lines total. The IDENTIFICATION DIVISION begins at L21; the PROGRAM-ID declaration appears at L22-L23. The WORKING-STORAGE SECTION at L34 declares the program's state variables. The literal constants in `WS-LITERALS` (L142-L202) carry the program-self-reference and the XCTL/file-name targets. The LINKAGE SECTION at L256-L259 declares the opaque `DFHCOMMAREA` with FILLER OCCURS DEPENDING ON `EIBCALEN`, which the PROCEDURE DIVISION reslices into `CARDDEMO-COMMAREA` plus `WS-THIS-PROGCOMMAREA` at L282-L293.

PROGRAM-ID block at `app/cbl/COACTVWC.cbl:L21-L27`:

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID.
           COACTVWC.
       DATE-WRITTEN.
           May 2022.
       DATE-COMPILED.
           Today.
```

`WS-INFO-MSG PIC X(40)` 88-level catalog at `app/cbl/COACTVWC.cbl:L110-L116`:

```cobol
          05  WS-INFO-MSG                           PIC X(40).
            88  WS-NO-INFO-MESSAGE                 VALUES
                                                   SPACES LOW-VALUES.
            88  WS-PROMPT-FOR-INPUT                 VALUE
                'Enter or update id of account to display'.
            88  WS-INFORM-OUTPUT                    VALUE
                'Displaying details of given Account'.
```

`WS-RETURN-MSG PIC X(75)` 88-level catalog at `app/cbl/COACTVWC.cbl:L117-L138`:

```cobol
          05  WS-RETURN-MSG                         PIC X(75).
            88  WS-RETURN-MSG-OFF                   VALUE SPACES.
            88  WS-EXIT-MESSAGE                     VALUE
                'PF03 pressed.Exiting              '.
            88  WS-PROMPT-FOR-ACCT                  VALUE
                'Account number not provided'.
            88  NO-SEARCH-CRITERIA-RECEIVED         VALUE
                'No input received'.
            88  SEARCHED-ACCT-ZEROES                VALUE
                'Account number must be a non zero 11 digit number'.
            88  SEARCHED-ACCT-NOT-NUMERIC           VALUE
                'Account number must be a non zero 11 digit number'.
            88  DID-NOT-FIND-ACCT-IN-CARDXREF       VALUE
                'Did not find this account in account card xref file'.
            88  DID-NOT-FIND-ACCT-IN-ACCTDAT        VALUE
                'Did not find this account in account master file'.
            88  DID-NOT-FIND-CUST-IN-CUSTDAT        VALUE
                'Did not find associated customer in master file'.
            88  XREF-READ-ERROR                     VALUE
                'Error reading account card xref File'.
            88  CODING-TO-BE-DONE                   VALUE
                'Looks Good.... so far'.
```

The literal table at `app/cbl/COACTVWC.cbl:L142-L193` defines every program-identification and external-target constant. The table below shows the literals directly referenced by COACTVWC paragraphs plus the source-typo row that MUST be preserved verbatim.

| Variable | Value (verbatim) | Line | Notes |
|---|---|---|---|
| LIT-THISPGM | `'COACTVWC'` | 144 | Self-reference; used in ABEND-CULPRIT and CDEMO-FROM-PROGRAM updates |
| LIT-THISTRANID | `'CAVW'` | 146 | CICS transaction ID for this program |
| LIT-THISMAPSET | `'COACTVW '` | 148 | PIC X(8); 7 chars plus 1 trailing space |
| LIT-THISMAP | `'CACTVWA'` | 150 | PIC X(7); 7 chars exactly |
| LIT-CCLISTPGM | `'COCRDLIC'` | 152 | Card-list program (XCTL source for PFK03 reentry) |
| LIT-CCLISTTRANID | `'CCLI'` | 154 | Card-list transaction ID |
| LIT-CARDUDPATETRANID | `'CCUP'` | 162 | PIC X(4); SOURCE TYPO PRESERVED -- variable name is `CARDUDPATE` (not `CARDUPDATE`) |
| LIT-MENUPGM | `'COMEN01C'` | 169 | Default PFK03 exit target when CDEMO-FROM-PROGRAM is unset |
| LIT-MENUTRANID | `'CM00'` | 171 | Default menu transaction ID |
| LIT-ACCTFILENAME | `'ACCTDAT '` | 185 | Primary account VSAM file (READ-only) |
| LIT-CARDFILENAME | `'CARDDAT '` | 187 | Declared but unreferenced by COACTVWC |
| LIT-CUSTFILENAME | `'CUSTDAT '` | 189 | Customer VSAM file (READ-only) |
| LIT-CARDFILENAME-ACCT-PATH | `'CARDAIX '` | 191 | Alternate-index path; DECLARED BUT UNUSED in COACTVWC |
| LIT-CARDXREFNAME-ACCT-PATH | `'CXACAIX '` | 193 | Cross-reference alternate-index path (READ-only) |

Source anomalies preserved per AAP section 0.7.1 (Minimal Change Clause):

- `LIT-CARDUDPATETRANID` at L161: variable name contains `CARDUDPATE` (transposed D and P). MUST be preserved verbatim in any source-traceable Java artifact.
- `SEARCHED-ACCT-ZEROES` (L125-L126) and `SEARCHED-ACCT-NOT-NUMERIC` (L127-L128) carry IDENTICAL VALUE `'Account number must be a non zero 11 digit number'`. The Java translation MUST emit byte-identical text for both.
- `CODING-TO-BE-DONE` at L137-L138 binds to `'Looks Good.... so far'` (four periods). DEAD CODE: never referenced by any SET/IF/PERFORM in the program; preserved verbatim.
- `WS-EXIT-MESSAGE` at L119-L120: `'PF03 pressed.Exiting              '` is 20-char prefix + EXACTLY 14 trailing spaces = 34 bytes within quotes. The Java translation MUST emit the 14 trailing spaces byte-exact.

## Phase 2 -- Commarea: CARDDEMO-COMMAREA + WS-THIS-PROGCOMMAREA Local Extension

The LINKAGE SECTION at `app/cbl/COACTVWC.cbl:L256-L259` declares an opaque `DFHCOMMAREA` with FILLER OCCURS DEPENDING ON `EIBCALEN`. The PROCEDURE DIVISION reslices the opaque payload at L282-L293 via reference modification into two complementary structures:

1. `CARDDEMO-COMMAREA` from `COPY COCOM01Y.` at L211, defined in `app/cpy/COCOM01Y.cpy:L19-L44`. This is the 160-byte shared structure with nested groups CDEMO-GENERAL-INFO, CDEMO-CUSTOMER-INFO, CDEMO-ACCOUNT-INFO, CDEMO-CARD-INFO, and CDEMO-MORE-INFO totaling 16 elementary items.
2. `WS-THIS-PROGCOMMAREA` declared INLINE at L213-L216 (NOT in any copybook) with 12-byte sub-structure `CA-CALL-CONTEXT` containing `CA-FROM-PROGRAM PIC X(08)` and `CA-FROM-TRANID PIC X(04)`. COACTVWC carries its own local extension for caller-context that flows forward on every pseudo-conversation cycle.
3. `WS-COMMAREA PIC X(2000)` at L218: the 2000-byte serialization buffer holding `CARDDEMO-COMMAREA` (offset 0, length 160) followed by `WS-THIS-PROGCOMMAREA` (offset 160, length 12). Passed as the `COMMAREA` operand on `EXEC CICS RETURN` (see Phase 7).

Key 88-level value spaces preserved as sealed Java types: `CDEMO-USER-TYPE` with `'A'`/`'U'` (sealed `UserType {Admin, User}`); `CDEMO-PGM-CONTEXT` with `0`/`1` (sealed `PgmContext {Enter, Reenter}`). The 2000-byte length provides headroom beyond the 172 bytes actually used. The Java translation MUST preserve the 2000-byte length and field-order for byte-for-byte interop per AAP section 0.7.1.

## Phase 3 -- 0000-MAIN Dispatch

The 0000-MAIN paragraph at `app/cbl/COACTVWC.cbl:L262-L407` is the program's pseudo-conversational dispatch entry. After `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)` at L264-L266 and `INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA` at L268-L270, the dispatcher distinguishes between two execution contexts (first-time vs. reentry) and then routes through a 4-branch `EVALUATE TRUE` to the appropriate handler.

### Phase 3.1 -- First-Time Entry (EIBCALEN = 0)

`app/cbl/COACTVWC.cbl:L282-L293` detects first-time entry via `IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)`. On the true branch, `INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA` zeroizes both commarea structures. On the false branch, two reference-modified `MOVE DFHCOMMAREA(offset:length)` operations reconstitute the prior commarea payload byte-for-byte into the two structures. Fresh entry routes the dispatcher to the `WHEN CDEMO-PGM-ENTER` branch which performs `1000-SEND-MAP` and returns the blank form populated by `WS-PROMPT-FOR-INPUT`. This branch corresponds to test scenario 1 in `input_scenario.txt`.

### Phase 3.2 -- Reentry Detection from CDEMO-FROM-PROGRAM

The compound condition at L283-L284 treats menu-initiated entries as fresh (`CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER`), while preserving the commarea for ENTER reentries from the same program. The PFK silent-coercion logic at L306-L314 then enforces that any AID byte other than ENTER or PFK03 is rewritten to ENTER:

```cobol
           SET PFK-INVALID TO TRUE
           IF CCARD-AID-ENTER OR
              CCARD-AID-PFK03
              SET PFK-VALID TO TRUE
           END-IF

           IF PFK-INVALID
              SET CCARD-AID-ENTER TO TRUE
           END-IF
```

Test scenario 11 (PF4 silently coerced to ENTER) exercises this coercion path.

### Phase 3.3 -- EIBAID Dispatch (4-Branch EVALUATE TRUE)

The 4-branch `EVALUATE TRUE` at `app/cbl/COACTVWC.cbl:L323-L383` is the central dispatch table:

| Branch | Condition | Line | Action | Test Scenarios |
|---|---|---|---|---|
| 1 | `CCARD-AID-PFK03` | 324 | XCTL to CDEMO-FROM-PROGRAM or LIT-MENUPGM (L328-L352) | 9, 10 |
| 2 | `CDEMO-PGM-ENTER` | 353 | PERFORM 1000-SEND-MAP; GO TO COMMON-RETURN | 1 |
| 3 | `CDEMO-PGM-REENTER` | 361 | PERFORM 2000-PROCESS-INPUTS; if INPUT-ERROR send map, else PERFORM 9000-READ-ACCT + 1000-SEND-MAP | 2, 3, 4, 5, 6, 7, 8, 11, 12 |
| 4 | `WHEN OTHER` | 375 | MOVE 'UNEXPECTED DATA SCENARIO' to WS-RETURN-MSG; PERFORM SEND-PLAIN-TEXT | (defensive only) |

EXPLICIT NOTE: 4 branches, NOT 5. COACTVWC does NOT have a COCRDLIC-style auto-trigger-from-list branch. Any test scenario that arrives with `CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM` is treated identically to any other ENTER context: 1000-SEND-MAP is performed, and the blank form is sent. The Java translation expresses this 4-branch dispatch as a pattern-matching switch over a sealed `DispatchState` hierarchy with permits for `ExitToPriorProgram`, `FirstShow`, `ReenterValidate`, and `UnexpectedData`. Per AAP section 0.7.4 no fall-through branch is permitted; compiler-enforced exhaustiveness checking is the safety guarantee.

## Phase 4 -- 2000-PROCESS-INPUTS Validation Pipeline

The 2000-PROCESS-INPUTS paragraph at `app/cbl/COACTVWC.cbl:L596-L605` orchestrates input handling for every REENTER pass. It performs 2100-RECEIVE-MAP to pull the latest CACTVWAI buffer from CICS, then 2200-EDIT-MAP-INPUTS to normalize and validate the single editable filter field, then propagates `WS-RETURN-MSG` into `CCARD-ERROR-MSG` and sets the next-map routing constants for the subsequent SEND.

### Phase 4.1 -- 2100-RECEIVE-MAP

`app/cbl/COACTVWC.cbl:L610-L617` issues the receive of the CACTVWAI buffer:

```cobol
       2100-RECEIVE-MAP.
           EXEC CICS RECEIVE MAP(LIT-THISMAP)
                     MAPSET(LIT-THISMAPSET)
                     INTO(CACTVWAI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC
           .
```

The `CACTVWAI` input record is the symbolic copybook 01-level group defined in `app/cpy-bms/COACTVW.CPY`, providing the L (length), F (flag), A (attribute), and I (input value) quartet pattern for each BMS field. Only the `ACCTSIDI PIC X(11)` sub-field is operator-editable (UNPROT in the BMS map); all other I-fields are echo-only on RECEIVE.

### Phase 4.2 -- 2200-EDIT-MAP-INPUTS Normalization

`app/cbl/COACTVWC.cbl:L622-L643`:

1. Initialize `INPUT-OK` and `FLG-ACCTFILTER-ISVALID` at L624-L625.
2. Normalize ACCTSIDI: if `'*'` or SPACES, MOVE LOW-VALUES TO `CC-ACCT-ID`; else MOVE ACCTSIDI TO `CC-ACCT-ID` (L628-L633). The wildcard `'*'` and blank inputs are treated identically as "filter not supplied".
3. PERFORM 2210-EDIT-ACCOUNT THRU 2210-EDIT-ACCOUNT-EXIT (L636-L637).
4. Cross-field edit at L640-L642: IF `FLG-ACCTFILTER-BLANK`, SET `NO-SEARCH-CRITERIA-RECEIVED` TO TRUE, which binds `WS-RETURN-MSG` to `'No input received'` (the verbatim text at L123-L124).

EXPLICIT NOTE: There is NO `2220-EDIT-CARD` paragraph in COACTVWC. Unlike COCRDSLC which validates two filter fields (account and card), COACTVWC has only ONE editable input field (`ACCTSID`). The validation pipeline is correspondingly simpler: a single per-field edit invocation and one cross-field check on the BLANK flag.

### Phase 4.3 -- 2210-EDIT-ACCOUNT

`app/cbl/COACTVWC.cbl:L649-L681`. The paragraph implements three validation cases in order:

```cobol
       2210-EDIT-ACCOUNT.
           SET FLG-ACCTFILTER-NOT-OK TO TRUE
           IF CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES
              SET INPUT-ERROR           TO TRUE
              SET FLG-ACCTFILTER-BLANK  TO TRUE
              IF WS-RETURN-MSG-OFF
                 SET WS-PROMPT-FOR-ACCT TO TRUE
              END-IF
              MOVE ZEROES TO CDEMO-ACCT-ID
              GO TO  2210-EDIT-ACCOUNT-EXIT
           END-IF
           IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES
              SET INPUT-ERROR TO TRUE
              SET FLG-ACCTFILTER-NOT-OK TO TRUE
              IF WS-RETURN-MSG-OFF
                MOVE
              'Account Filter must  be a non-zero 11 digit number'
                              TO WS-RETURN-MSG
              END-IF
              MOVE ZERO TO CDEMO-ACCT-ID
              GO TO 2210-EDIT-ACCOUNT-EXIT
           ELSE
              MOVE CC-ACCT-ID TO CDEMO-ACCT-ID
              SET FLG-ACCTFILTER-ISVALID TO TRUE
           END-IF.
```

EXPLICIT CALLOUT (DOUBLE-SPACE ANOMALY): The L672 inline literal `'Account Filter must  be a non-zero 11 digit number'` contains TWO consecutive space characters between `must` and `be`. This verbatim source anomaly is preserved per AAP section 0.7.1. The Java translation MUST emit this exact 51-character string with the double space byte-exact. A single missing space would cause a byte-level mismatch on golden-record capture comparison and would fail the PR gate per AAP section 0.6.11.

Case mapping:

- Empty/SPACES `ACCTSID` -> binds `WS-RETURN-MSG` to `'Account number not provided'` via `WS-PROMPT-FOR-ACCT` (L121-L122). Test scenario 2.
- ZEROES `ACCTSID` -> binds `WS-RETURN-MSG` to `'Account number must be a non zero 11 digit number'` via `SEARCHED-ACCT-ZEROES` (L125-L126). Test scenario 3. Note: SEARCHED-ACCT-NOT-NUMERIC at L127-L128 is identical text but is NOT directly used by this paragraph; the inline literal at L672 is used for non-numeric content.
- Non-numeric (alphabetic or mixed) `ACCTSID` -> binds `WS-RETURN-MSG` to the inline literal `'Account Filter must  be a non-zero 11 digit number'` at L672 (with DOUBLE SPACE). Test scenario 4.

## Phase 5 -- 9000-READ-ACCT Behavior

The 9000-READ-ACCT paragraph at `app/cbl/COACTVWC.cbl:L687-L718` is the cross-file read orchestrator. It chains three READ-only EXEC CICS READ operations across three distinct VSAM files: the card cross-reference alternate index (CXACAIX), the account master (ACCTDAT), and the customer master (CUSTDAT). Each read is gated on the success of the prior step; any failure prevents subsequent invocations and surfaces a per-file diagnostic message.

### Phase 5.1 -- 9000-READ-ACCT Orchestrator

`app/cbl/COACTVWC.cbl:L687-L718` chains three READ-only EXEC CICS READ operations through three VSAM files with progressive gating on the `FLG-ACCTFILTER-NOT-OK` and `DID-NOT-FIND-*` 88-level flags. The sequence is: `SET WS-NO-INFO-MESSAGE TO TRUE` (L689); `MOVE CDEMO-ACCT-ID TO WS-CARD-RID-ACCT-ID` (L691) which binds the 11-digit RIDFLD; `PERFORM 9200-GETCARDXREF-BYACCT` (L693); early-exit if `FLG-ACCTFILTER-NOT-OK` (L697-L699); `PERFORM 9300-GETACCTDATA-BYACCT` (L701); early-exit if `DID-NOT-FIND-ACCT-IN-ACCTDAT` (L704-L706); `MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID` (L708) which binds the customer-ID RIDFLD acquired from the XREF; `PERFORM 9400-GETCUSTDATA-BYCUST` (L710); early-exit if `DID-NOT-FIND-CUST-IN-CUSTDAT` (L713-L715). The Java translation models this as three private methods on `CoActVwC` invoked sequentially, with early-exit gating expressed as an `if (flag) return;` short-circuit.

### Phase 5.2 -- 9200-GETCARDXREF-BYACCT

`app/cbl/COACTVWC.cbl:L723-L770` issues the cross-reference alternate-index lookup. Verbatim fence (header + READ):

```cobol
       9200-GETCARDXREF-BYACCT.

      *    Read the Card file. Access via alternate index ACCTID
      *
           EXEC CICS READ
                DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
                RIDFLD    (WS-CARD-RID-ACCT-ID-X)
                KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
                INTO      (CARD-XREF-RECORD)
                LENGTH    (LENGTH OF CARD-XREF-RECORD)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
```

CRITICAL: There is NO `UPDATE` clause on this READ. The lookup acquires no record lock and produces no post-run state change in the underlying CXACAIX file.

The 3 RESP paths at `app/cbl/COACTVWC.cbl:L737-L769`:

- `WHEN DFHRESP(NORMAL)` (L738): MOVE `XREF-CUST-ID` TO `CDEMO-CUST-ID`; MOVE `XREF-CARD-NUM` TO `CDEMO-CARD-NUM`. This binds the customer ID that will be the RIDFLD for the third read (9400-GETCUSTDATA-BYCUST) and propagates the card number through the commarea for any downstream consumer.
- `WHEN DFHRESP(NOTFND)` (L741): SET INPUT-ERROR; SET FLG-ACCTFILTER-NOT-OK; if `WS-RETURN-MSG-OFF`, build a dynamic STRING (L747-L757) into `WS-RETURN-MSG` with operands `'Account:'`, `WS-CARD-RID-ACCT-ID-X`, `' not found in'`, `' Cross ref file.  Resp:'`, `ERROR-RESP`, `' Reas:'`, `ERROR-RESP2`, all `DELIMITED BY SIZE`. EXPLICIT CALLOUT (DOUBLE-SPACE ANOMALY): the literal `' Cross ref file.  Resp:'` at L751 contains TWO consecutive space characters between `file.` and `Resp:`, preserved per AAP section 0.7.1. The corresponding ACCTDAT (9300) and CUSTDAT (9400) templates use different forms (`file.Resp:` and `master.Resp: ` respectively) and are documented in Phases 5.3 and 5.4.

- `WHEN OTHER` (L759): SET INPUT-ERROR; SET FLG-ACCTFILTER-NOT-OK; MOVE `'READ'` TO `ERROR-OPNAME`; MOVE `LIT-CARDXREFNAME-ACCT-PATH` TO `ERROR-FILE`; MOVE WS-RESP-CD/WS-REAS-CD TO ERROR-RESP/ERROR-RESP2; MOVE `WS-FILE-ERROR-MESSAGE` TO `WS-RETURN-MSG`. The 75-byte WS-FILE-ERROR-MESSAGE template is defined at L86-L105 and produces strings of the form `File Error: READ     on CXACAIX  returned RESP 12        ,RESP2 13        `. Test scenario 12 exercises this WHEN OTHER path.

### Phase 5.3 -- 9300-GETACCTDATA-BYACCT

`app/cbl/COACTVWC.cbl:L774-L820` issues the account master lookup with the same `EXEC CICS READ DATASET RIDFLD KEYLENGTH INTO LENGTH RESP RESP2` shape as 9200 (no UPDATE clause; READ-only access to ACCTDAT) but binding `LIT-ACCTFILENAME` as the DATASET and `ACCOUNT-RECORD` as the INTO target. The 3 RESP paths at L786-L819:

- `WHEN DFHRESP(NORMAL)` (L787): SET `FOUND-ACCT-IN-MASTER` TO TRUE. The 300-byte ACCOUNT-RECORD is now available in working storage for the screen-build phase.
- `WHEN DFHRESP(NOTFND)` (L789): SET INPUT-ERROR; SET FLG-ACCTFILTER-NOT-OK; build dynamic STRING into `WS-RETURN-MSG` using the L796-L806 template `'Account:' + WS-CARD-RID-ACCT-ID-X + ' not found in' + ' Acct Master file.Resp:' + ERROR-RESP + ' Reas:' + ERROR-RESP2`. ACCTDAT template uses `file.Resp:` (NO double space), distinguishing it from the 9200 template.
- `WHEN OTHER` (L809): MOVE `'READ'` TO `ERROR-OPNAME`; MOVE `LIT-ACCTFILENAME` TO `ERROR-FILE`; MOVE `WS-FILE-ERROR-MESSAGE` TO `WS-RETURN-MSG` (the 75-byte template).

### Phase 5.4 -- 9400-GETCUSTDATA-BYCUST

`app/cbl/COACTVWC.cbl:L825-L869` issues the customer master lookup with the same READ shape (no UPDATE clause; READ-only access to CUSTDAT) but binding `LIT-CUSTFILENAME` as the DATASET, `WS-CARD-RID-CUST-ID-X` as the RIDFLD, and `CUSTOMER-RECORD` as the INTO target. The 3 RESP paths at L836-L868:

- `WHEN DFHRESP(NORMAL)` (L837): SET `FOUND-CUST-IN-MASTER` TO TRUE. The 500-byte CUSTOMER-RECORD is now available for the screen-build phase, including the SSN that 1200-SETUP-SCREEN-VARS reformats via the STRING verb at L496-L504 from the contiguous 9-digit `CUST-SSN PIC 9(09)` to the hyphenated 11-character `NNN-NN-NNNN` form.
- `WHEN DFHRESP(NOTFND)` (L839): SET INPUT-ERROR; SET FLG-CUSTFILTER-NOT-OK; build dynamic STRING into `WS-RETURN-MSG` using the L846-L856 template `'CustId:' + WS-CARD-RID-CUST-ID-X + ' not found' + ' in customer master.Resp: ' + ERROR-RESP + ' REAS:' + ERROR-RESP2`. CUSTDAT template uses `master.Resp: ` (trailing space after the colon) and the partial-uppercase keyword `' REAS:'` (with a leading space).
- `WHEN OTHER` (L858): MOVE `LIT-CUSTFILENAME` TO `ERROR-FILE`; MOVE `WS-FILE-ERROR-MESSAGE` TO `WS-RETURN-MSG`.

### Phase 5.5 -- Error Cascade and READ-Only Invariant

The orchestrator gates each subsequent read on the success of the prior one. Failure cascade:

- Failure in 9200 (XREF NOTFND or OTHER) sets `FLG-ACCTFILTER-NOT-OK`, which prevents both 9300 and 9400 from executing (`IF FLG-ACCTFILTER-NOT-OK GO TO 9000-READ-ACCT-EXIT` at L697-L699). Test scenario 6 (CXACAIX NOTFND) and test scenario 12 (CXACAIX OTHER) exercise this path.
- Failure in 9300 (ACCTDAT NOTFND or OTHER) sets `FLG-ACCTFILTER-NOT-OK`, which (in combination with the lack of an explicit `DID-NOT-FIND-ACCT-IN-ACCTDAT` flag-set) is checked via the implicit binding to `WS-RETURN-MSG` and the early-exit at L704-L706. Test scenario 7 exercises this path.
- Failure in 9400 (CUSTDAT NOTFND or OTHER) leaves CUSTOMER-RECORD unpopulated; 1200-SETUP-SCREEN-VARS still emits the ACCOUNT fields under `IF FOUND-ACCT-IN-MASTER` (because that flag was set by 9300) but skips the CUSTOMER fields under `IF FOUND-CUST-IN-MASTER`. Test scenario 8 exercises this path.

INVARIANT: ALL THREE files (ACCTDAT, CUSTDAT, CXACAIX) are READ-only across the entire 941-line program. There is no `UPDATE` clause on any READ, no `REWRITE` verb, no `WRITE` verb, and no `DELETE` verb anywhere in `app/cbl/COACTVWC.cbl`. This is the foundation of the three `EXPECT_ACCTDAT_UNCHANGED`, `EXPECT_CUSTDAT_UNCHANGED`, and `EXPECT_CXACAIX_UNCHANGED` invariants asserted on EVERY scenario in `input_scenario.txt`. The Java translation MUST preserve this invariant: the three repository ports `AccountRepository`, `CustomerRepository`, and `CardXrefRepository` are used solely through their read-shaped methods; no write-shaped method is invoked from any code path reachable from `CoActVwC`.


## Phase 6 -- Screen Build: 1100/1200/1300

The 1000-SEND-MAP wrapper at `app/cbl/COACTVWC.cbl:L416-L425` performs four sub-paragraphs in sequence: 1100-SCREEN-INIT, 1200-SETUP-SCREEN-VARS, 1300-SETUP-SCREEN-ATTRS, 1400-SEND-SCREEN. The 1400 step is documented under Phase 8.

### Phase 6.1 -- 1100-SCREEN-INIT

`app/cbl/COACTVWC.cbl:L431-L455` zeroizes the CACTVWAO output map via `MOVE LOW-VALUES TO CACTVWAO` and populates the constant header fields. Key moves: `CCDA-TITLE01 -> TITLE01O`, `CCDA-TITLE02 -> TITLE02O`, `LIT-THISTRANID -> TRNNAMEO`, `LIT-THISPGM -> PGMNAMEO`. The current-date and current-time are sourced from `FUNCTION CURRENT-DATE` (invoked twice at L434 and L441; both calls yield identical results on the same CPU clock tick). Date is reformatted to `mm/dd/yy` via WS-CURDATE-MM/DD/YY and moved to `CURDATEO`; time is reformatted to `hh:mm:ss` via WS-CURTIME-HH/MM/SS and moved to `CURTIMEO`. The Java translation MUST inject a fixed `java.time.Clock` instance per AAP section 0.6.6 to ensure byte-reproducible captures across test runs; reproducible captures depend on the clock being deterministic at test time.

### Phase 6.2 -- 1200-SETUP-SCREEN-VARS

`app/cbl/COACTVWC.cbl:L460-L535` populates the data fields of CACTVWAO conditionally on the `FOUND-ACCT-IN-MASTER` and `FOUND-CUST-IN-MASTER` flags set during Phase 5 reads. Field-population mapping summary:

```text
WHEN EIBCALEN = 0 (first-time entry):
  SET WS-PROMPT-FOR-INPUT TO TRUE

WHEN FOUND-ACCT-IN-MASTER (set by 9300):
  ACCT-ACTIVE-STATUS         -> ACSTTUSO of CACTVWAO (1 char)
  ACCT-CURR-BAL              -> ACURBALO of CACTVWAO (PICOUT '+ZZZ,ZZZ,ZZZ.99')
  ACCT-CREDIT-LIMIT          -> ACRDLIMO of CACTVWAO
  ACCT-CASH-CREDIT-LIMIT     -> ACSHLIMO of CACTVWAO
  ACCT-CURR-CYC-CREDIT       -> ACRCYCRO of CACTVWAO
  ACCT-CURR-CYC-DEBIT        -> ACRCYDBO of CACTVWAO
  ACCT-OPEN-DATE             -> ADTOPENO of CACTVWAO (10 chars YYYY-MM-DD)
  ACCT-EXPIRAION-DATE        -> AEXPDTO  of CACTVWAO (NOTE: COBOL misspelling preserved)
  ACCT-REISSUE-DATE          -> AREISDTO of CACTVWAO
  ACCT-GROUP-ID              -> AADDGRPO of CACTVWAO

WHEN FOUND-CUST-IN-MASTER (set by 9400):
  CUST-ID                    -> ACSTNUMO of CACTVWAO (9 digits)
  STRING CUST-SSN -> 'NNN-NN-NNNN' -> ACSTSSNO of CACTVWAO (11 chars with hyphens)
  CUST-FICO-CREDIT-SCORE     -> ACSTFCOO of CACTVWAO (3 chars)
  CUST-DOB-YYYY-MM-DD        -> ACSTDOBO of CACTVWAO
  CUST-FIRST-NAME            -> ACSFNAMO of CACTVWAO (25 chars)
  CUST-MIDDLE-NAME           -> ACSMNAMO of CACTVWAO
  CUST-LAST-NAME             -> ACSLNAMO of CACTVWAO
  CUST-ADDR-LINE-1           -> ACSADL1O of CACTVWAO (50 chars)
  CUST-ADDR-LINE-2           -> ACSADL2O of CACTVWAO
  CUST-ADDR-LINE-3           -> ACSCITYO of CACTVWAO (NOTE: city is stored in ADDR-LINE-3)
  CUST-ADDR-STATE-CD         -> ACSSTTEO of CACTVWAO (2 chars)
  CUST-ADDR-ZIP              -> ACSZIPCO of CACTVWAO (5 chars)
  CUST-ADDR-COUNTRY-CD       -> ACSCTRYO of CACTVWAO (3 chars)
  CUST-PHONE-NUM-1           -> ACSPHN1O of CACTVWAO (13 chars)
  CUST-PHONE-NUM-2           -> ACSPHN2O of CACTVWAO
  CUST-GOVT-ISSUED-ID        -> ACSGOVTO of CACTVWAO (20 chars)
  CUST-EFT-ACCOUNT-ID        -> ACSEFTCO of CACTVWAO (10 chars)
  CUST-PRI-CARD-HOLDER-IND   -> ACSPFLGO of CACTVWAO (1 char Y/N)
```

The COBOL field name `ACCT-EXPIRAION-DATE` at `app/cpy/CVACT01Y.cpy:L11` carries a misspelling (`EXPIRAION` instead of `EXPIRATION`). The translation preserves the source field name byte-for-byte in any traceable artifact per AAP section 0.7.1. The corresponding Java record field on `AccountRecord` from CVACT01Y carries the corrected spelling in its public API for semantic clarity, but any byte-traceable copy of the source must preserve the typo.

After the data-field population, L528-L530 checks `IF WS-NO-INFO-MESSAGE` and falls back to `SET WS-PROMPT-FOR-INPUT TO TRUE` when no other info message has been bound, ensuring the operator always sees a non-empty prompt or status in the INFOMSG field. Lines L532-L534 then move `WS-RETURN-MSG` to `ERRMSGO` and `WS-INFO-MSG` to `INFOMSGO`.

### Phase 6.3 -- 1300-SETUP-SCREEN-ATTRS

`app/cbl/COACTVWC.cbl:L541-L572` sets the attribute bytes that govern field protection, cursor placement, and field highlighting on the next SEND MAP. Key actions:

- MOVE `DFHBMFSE` TO `ACCTSIDA` (L543) marks the ACCTSID input attribute byte as Modified-Data-Tag (MDT) field-set, ensuring the field will be transmitted back on the next RECEIVE.
- The EVALUATE TRUE at L546-L552 positions the cursor to ACCTSID on validation failure or blank input by setting `ACCTSIDL = -1` (the BMS sentinel for cursor positioning).
- MOVE `DFHDFCOL` TO `ACCTSIDC` (L555) sets the default color attribute; IF `FLG-ACCTFILTER-NOT-OK` then overridden to `DFHRED` (L557-L559).
- The blank-reentry sentinel at L561-L565 writes `'*'` into `ACCTSIDO` and sets red color when the operator submitted blanks on a REENTER cycle.
- The INFOMSG color is set to `DFHBMDAR` (dark/no-highlight) when WS-INFO-MSG is empty (L567-L568), otherwise to `DFHNEUTR` (L570).

## Phase 7 -- COMMON-RETURN

The COMMON-RETURN paragraph at `app/cbl/COACTVWC.cbl:L394-L407` is the single exit point for every non-XCTL completion path. It propagates `WS-RETURN-MSG` into the next-frame error message field, serializes both commarea structures into the 2000-byte `WS-COMMAREA` buffer, and issues `EXEC CICS RETURN` with the program-self transaction ID:

```cobol
       COMMON-RETURN.
           MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
           MOVE CARDDEMO-COMMAREA TO WS-COMMAREA
           MOVE WS-THIS-PROGCOMMAREA TO
                WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
                            LENGTH OF WS-THIS-PROGCOMMAREA)
           EXEC CICS RETURN TRANSID(LIT-THISTRANID)
                            COMMAREA(WS-COMMAREA)
                            LENGTH(LENGTH OF WS-COMMAREA) END-EXEC.
```

The next pseudo-conversation cycle re-enters at 0000-MAIN. Because the immediately-preceding 1400-SEND-SCREEN paragraph sets `CDEMO-PGM-REENTER` to TRUE before the SEND (L581), the next cycle will route through the `WHEN CDEMO-PGM-REENTER` branch and exercise the 2000-PROCESS-INPUTS validation pipeline.

The duplicate `0000-MAIN-EXIT.` paragraph label at `app/cbl/COACTVWC.cbl:L408` and `L411` is a COBOL source-anomaly preserved per AAP section 0.7.1. The second occurrence at L411-L413 is unreachable because COBOL paragraph dispatch resolves to the FIRST definition encountered. The Java translation MUST acknowledge this dead label in inline comments on the corresponding `CoActVwC.commonReturn()` method or its dispatch table; no Java semantic is impacted because the duplicate label has no effect at the COBOL byte-level either.

## Phase 8 -- 1400-SEND-SCREEN

The 1400-SEND-SCREEN paragraph at `app/cbl/COACTVWC.cbl:L577-L591` issues the BMS SEND MAP with ERASE, FREEKB, and CURSOR options:

```cobol
       1400-SEND-SCREEN.

           MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
           MOVE LIT-THISMAP    TO CCARD-NEXT-MAP
           SET  CDEMO-PGM-REENTER TO TRUE
           EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET)
                          FROM(CACTVWAO) CURSOR ERASE FREEKB
                          RESP(WS-RESP-CD) END-EXEC.
```

The `ERASE` flag clears the 3270 device buffer before the new map is sent, ensuring no residual character data from the prior screen leaks into the next display. The `FREEKB` flag unlocks the keyboard so the operator can type into the editable field. The `CURSOR` flag honors the `ACCTSIDL = -1` sentinel set by 1300-SETUP-SCREEN-ATTRS to place the cursor at the appropriate position. The Java translation models the SEND MAP outcome as a byte buffer in `CoActVwOutput`; the byte buffer is serialized in the bms_output.txt capture artifact.

## Phase 9 -- ABEND-ROUTINE

The ABEND-ROUTINE paragraph at `app/cbl/COACTVWC.cbl:L916-L937` is the program's catastrophic-failure handler, registered via `EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)` at L264-L266 inside 0000-MAIN:

```cobol
       ABEND-ROUTINE.
           IF ABEND-MSG EQUAL LOW-VALUES
              MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG
           END-IF
           MOVE LIT-THISPGM TO ABEND-CULPRIT
           EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA)
                          NOHANDLE END-EXEC
           EXEC CICS HANDLE ABEND CANCEL END-EXEC
           EXEC CICS ABEND ABCODE('9999') END-EXEC.
```

The `ABEND-MSG` working-storage field is defined in `app/cpy/CSMSG02Y.cpy` (brought in by `COPY CSMSG02Y.` at L238). When ABEND-MSG is unbound (LOW-VALUES), the default `'UNEXPECTED ABEND OCCURRED.'` at L919 is supplied. The 4-character ABCODE literal `'9999'` at L935 is the CICS-visible abend identifier and MUST be preserved byte-exact in the Java exception translation. A recommended Java idiom is a domain exception `CicsAbendException` with a `String abcode` field, instantiated as `new CicsAbendException("9999", abendMessage)` at the handler call site.

## Phase 10 -- BMS Map Layout: CACTVWA, 24x80

The BMS source `app/bms/COACTVW.bms` is 378 lines total and defines exactly one mapset and one map. The DFHMSD header at L20-L24 declares the mapset `COACTVW` with `LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&&SYSPARM`. The DFHMDI header at L25-L28 declares the map `CACTVWA` with `CTRL=(FREEKB), SIZE=(24,80)` and the four dynamic-attribute flags `DSATTS=(COLOR,HILIGHT,PS,VALIDN)` plus matching MAPATTS. The terminating `DFHMSD TYPE=FINAL` at L374 closes the mapset.

### Phase 10.1 -- Header Rows (1-2)

The first two rows carry the screen identification banner (program name, transaction ID, current date, current time, two titles).

```text
(1, 1)  literal   length 5 ASKIP,NORM BLUE INITIAL='Tran:'
(1, 7)  TRNNAME   length 4 ASKIP,FSET,NORM BLUE
(1,21)  TITLE01   length 40 ASKIP,NORM YELLOW
(1,65)  literal   length 5 ASKIP,NORM BLUE INITIAL='Date:'
(1,71)  CURDATE   length 8 ASKIP,NORM BLUE INITIAL='mm/dd/yy'
(2, 1)  literal   length 5 ASKIP,NORM BLUE INITIAL='Prog:'
(2, 7)  PGMNAME   length 8 ASKIP,NORM BLUE
(2,21)  TITLE02   length 40 ASKIP,NORM YELLOW
(2,65)  literal   length 5 ASKIP,NORM BLUE INITIAL='Time:'
(2,71)  CURTIME   length 8 ASKIP,NORM BLUE INITIAL='hh:mm:ss'
```

### Phase 10.2 -- Editable Input Row (4-5)

Row 4 carries the screen sub-heading. Row 5 carries the single editable input field (ACCTSID) and the read-only account-active-status echo (ACSTTUS).

```text
(4,33)  literal   length 12 NEUTRAL INITIAL='View Account'
(5,19)  literal   length 16 ASKIP,NORM TURQUOISE INITIAL='Account Number :'
(5,38)  ACCTSID   length 11 ATTRB=(FSET,IC,NORM,UNPROT) COLOR=GREEN
                  HILIGHT=UNDERLINE PICIN='99999999999' VALIDN=(MUSTFILL)
(5,50)  separator length 0  (stop field)
(5,57)  literal   length 12 TURQUOISE INITIAL='Active Y/N: '
(5,70)  ACSTTUS   length 1  ASKIP HILIGHT=UNDERLINE
(5,72)  separator length 0  (stop field)
```

ACCTSID is the ONLY editable input field on the entire 24x80 map. The `UNPROT` attribute marks it as operator-modifiable; the `IC` (Insert Cursor) attribute requests the BMS runtime to place the cursor here on the SEND. `PICIN='99999999999'` constrains operator input to exactly 11 digits, and `VALIDN=(MUSTFILL)` instructs the BMS runtime to require all 11 character positions to be supplied. All other 30+ output fields are protected echo-only.

### Phase 10.3 -- Read-Only Output Rows (6-20)

Rows 6 through 20 carry the account and customer data, each populated by 1200-SETUP-SCREEN-VARS conditional on the corresponding FOUND-* flag.

```text
Row 6:  Opened     ADTOPEN  10 chars UNDERLINE
        Credit Limit         ACRDLIM  15 chars UNDERLINE RIGHT PICOUT='+ZZZ,ZZZ,ZZZ.99'
Row 7:  Expiry     AEXPDT   10 chars UNDERLINE
        Cash credit Limit    ACSHLIM  15 chars UNDERLINE RIGHT PICOUT='+ZZZ,ZZZ,ZZZ.99'
Row 8:  Reissue    AREISDT  10 chars UNDERLINE
        Current Balance      ACURBAL  15 chars UNDERLINE RIGHT PICOUT='+ZZZ,ZZZ,ZZZ.99'
Row 9:                       Current Cycle Credit ACRCYCR  15 chars
                                                          PICOUT='+ZZZ,ZZZ,ZZZ.99'
Row 10: Account Group        AADDGRP  10 chars UNDERLINE
        Current Cycle Debit  ACRCYDB  15 chars PICOUT='+ZZZ,ZZZ,ZZZ.99'
Row 11: literal              length 16 NEUTRAL INITIAL='Customer Details' at (11,32)
Row 12: Customer id          ACSTNUM   9 chars UNDERLINE
        SSN                  ACSTSSN  12 chars UNDERLINE (NNN-NN-NNNN format)
Row 13: Date of birth        ACSTDOB  10 chars UNDERLINE
        FICO Score           ACSTFCO   3 chars UNDERLINE
Row 14: First Name / Middle Name / Last Name (column labels)
Row 15: ACSFNAM 25 / ACSMNAM 25 / ACSLNAM 25 (all UNDERLINE)
Row 16: Address              ACSADL1  50 chars UNDERLINE
        State                ACSSTTE   2 chars UNDERLINE
Row 17: (continuation)       ACSADL2  50 chars UNDERLINE
        Zip                  ACSZIPC   5 chars UNDERLINE RIGHT
Row 18: City                 ACSCITY  50 chars UNDERLINE   (city is in BMS row 18!)
        Country              ACSCTRY   3 chars UNDERLINE
Row 19: Phone 1              ACSPHN1  13 chars UNDERLINE
        Government Issued Id Ref      ACSGOVT  20 chars UNDERLINE
Row 20: Phone 2              ACSPHN2  13 chars UNDERLINE
        EFT Account Id       ACSEFTC  10 chars UNDERLINE
        Primary Card Holder Y/N        ACSPFLG  1 char UNDERLINE
```

### Phase 10.4 -- Message Rows (22, 23, 24)

The bottom three rows carry the status, error, and function-key footer messages.

```text
(22,23)  INFOMSG   length 45 ATTRB=(PROT) COLOR=NEUTRAL HILIGHT=OFF
                            -- holds WS-INFO-MSG content
(23, 1)  ERRMSG    length 78 ATTRB=(ASKIP,BRT,FSET) COLOR=RED
                            -- holds WS-RETURN-MSG content
(24, 1)  literal   length 60 ASKIP,NORM TURQUOISE INITIAL='  F3=Exit '
                            -- static function-key footer
```

The 60-character function-key footer at row 24 column 1 begins with TWO leading spaces before `F3=Exit `, per the BMS INITIAL clause at L373. This indentation is part of the captured byte layout and MUST be preserved in the bms_output.txt capture.


## Phase 11 -- Required Test Scenarios

The 12 test scenarios below cover every reachable execution path through 0000-MAIN, 2000-PROCESS-INPUTS, 9000-READ-ACCT, and the screen-build trio (1100/1200/1300). Each scenario sets EIBCALEN, the AID byte, and the field bindings, configures the MOCK_READ responses for the three VSAM files, and asserts the expected outcome. Every scenario asserts the three EXPECT_ACCTDAT_UNCHANGED / EXPECT_CUSTDAT_UNCHANGED / EXPECT_CXACAIX_UNCHANGED invariants because COACTVWC is READ-only across all 941 source lines.

1. `first_time_entry_no_commarea` -- EIBCALEN=0, no commarea. The 0000-MAIN test at L282 routes to `INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA`, then the `WHEN CDEMO-PGM-ENTER` branch performs 1000-SEND-MAP. Expected: blank ACCTSID field, INFOMSG `'Enter or update id of account to display'` (WS-PROMPT-FOR-INPUT). No ABCODE.
2. `enter_with_blank_acctsid` -- EIBCALEN>0, AID=ENTER, CDEMO-PGM-REENTER=1, ACCTSIDI=spaces. The REENTER branch runs 2000-PROCESS-INPUTS; 2210-EDIT-ACCOUNT detects empty input at L653 and sets `WS-PROMPT-FOR-ACCT` -> `'Account number not provided'`. Expected: ERRMSG `'Account number not provided'`. No ABCODE.
3. `enter_with_zeros_acctsid` -- EIBCALEN>0, AID=ENTER, CDEMO-PGM-REENTER=1, ACCTSIDI='00000000000'. 2210-EDIT-ACCOUNT routes through L666-L676 (`CC-ACCT-ID EQUAL ZEROES` branch). Expected: ERRMSG `'Account Filter must  be a non-zero 11 digit number'` (L672 inline literal with DOUBLE SPACE). No ABCODE.
4. `enter_with_nonnumeric_acctsid` -- EIBCALEN>0, AID=ENTER, CDEMO-PGM-REENTER=1, ACCTSIDI contains alphabetic characters. 2210-EDIT-ACCOUNT routes through L666-L676 (`CC-ACCT-ID IS NOT NUMERIC` branch). Expected: ERRMSG `'Account Filter must  be a non-zero 11 digit number'` (L672 inline literal with DOUBLE SPACE byte-exact). No ABCODE.
5. `enter_valid_full_chain_success` -- EIBCALEN>0, AID=ENTER, CDEMO-PGM-REENTER=1, ACCTSIDI='00000000001' (valid). MOCK_READ: CXACAIX returns NORMAL with XREF-CUST-ID='000000001'; ACCTDAT returns NORMAL with full ACCOUNT-RECORD; CUSTDAT returns NORMAL with full CUSTOMER-RECORD. Expected: INFOMSG `'Displaying details of given Account'` (WS-INFORM-OUTPUT via 1200-SETUP-SCREEN-VARS fallback), CACTVWAO populated with all 10 account fields plus all 18 customer fields, SSN reformatted to `'NNN-NN-NNNN'` form. No ABCODE.
6. `enter_valid_notfnd_in_cxacaix` -- EIBCALEN>0, ENTER, valid format ACCTSIDI. MOCK_READ: CXACAIX returns NOTFND. 9200-GETCARDXREF-BYACCT WHEN NOTFND path runs the dynamic STRING at L747-L757. Expected: ERRMSG matches the L747-L757 template `'Account:' + acctid + ' not found in' + ' Cross ref file.  Resp:' + RESP + ' Reas:' + RESP2` (DOUBLE SPACE after `file.`). 9300 and 9400 are NOT invoked. No ABCODE.
7. `enter_valid_notfnd_in_acctdat` -- EIBCALEN>0, ENTER, valid format ACCTSIDI. MOCK_READ: CXACAIX returns NORMAL; ACCTDAT returns NOTFND. 9300 WHEN NOTFND runs the L796-L806 STRING template `'Account:' + acctid + ' not found in' + ' Acct Master file.Resp:' + RESP + ' Reas:' + RESP2`. Expected: ERRMSG matches L796-L806 template. 9400 is NOT invoked. No ABCODE.
8. `enter_valid_notfnd_in_custdat` -- EIBCALEN>0, ENTER, valid format ACCTSIDI. MOCK_READ: CXACAIX returns NORMAL; ACCTDAT returns NORMAL; CUSTDAT returns NOTFND. 9400 WHEN NOTFND runs the L846-L856 STRING template `'CustId:' + custid + ' not found' + ' in customer master.Resp: ' + RESP + ' REAS:' + RESP2`. Expected: ERRMSG matches L846-L856 template, ACCOUNT data populated on screen, CUSTOMER data fields unpopulated. No ABCODE.
9. `pf03_eibcalen_zero` -- EIBCALEN=0, AID=PF3. The 0000-MAIN test at L282 INITIALIZEs commarea; the PFK silent-coercion at L306-L314 leaves PFK03 as a valid AID; `WHEN CCARD-AID-PFK03` runs at L324-L352. Because CDEMO-FROM-TRANID/CDEMO-FROM-PROGRAM are SPACES/LOW-VALUES after INITIALIZE, the branch routes to LIT-MENUTRANID and LIT-MENUPGM. Expected: XCTL to COMEN01C, CDEMO-FROM-PROGRAM updated to 'COACTVWC', no map send. WS-EXIT-MESSAGE is NOT explicitly set in COACTVWC's PFK03 branch; the message is set by the caller's downstream display. No ABCODE.
10. `pf03_from_cocrdlic` -- EIBCALEN>0 with CDEMO-FROM-PROGRAM='COCRDLIC' and CDEMO-FROM-TRANID='CCLI', AID=PF3. `WHEN CCARD-AID-PFK03` routes back to COCRDLIC (because the IF at L334 detects FROM-PROGRAM is set). Expected: XCTL to COCRDLIC, CDEMO-FROM-PROGRAM updated to 'COACTVWC', no map send. No ABCODE.
11. `invalid_aid_pfk04` -- EIBCALEN>0, AID=PF4 (DFHPF4), CDEMO-PGM-REENTER=1, ACCTSIDI=spaces. The PFK silent-coercion at L306-L314 detects PFK04 is not ENTER and not PFK03, sets PFK-INVALID, and then forces `SET CCARD-AID-ENTER TO TRUE`. The EVALUATE then takes `WHEN CDEMO-PGM-REENTER` because the program-context flag remains 1. 2000-PROCESS-INPUTS runs; with blank input, expected behavior matches scenario 2 (ERRMSG `'Account number not provided'`). No ABCODE.
12. `cxacaix_read_other_error` -- EIBCALEN>0, ENTER, valid format ACCTSIDI. MOCK_READ: CXACAIX returns a non-NORMAL non-NOTFND response (e.g., RESP=12 INVREQ). 9200-GETCARDXREF-BYACCT WHEN OTHER path at L759-L767 runs. Expected: ERRMSG matches the 75-byte WS-FILE-ERROR-MESSAGE template `'File Error: READ     on CXACAIX  returned RESP <code>     ,RESP2 <code>     '` with the actual RESP/RESP2 codes substituted. 9300 and 9400 are NOT invoked. No ABCODE (the program does not ABEND on file I/O errors; it surfaces the diagnostic and returns to the operator).

For every scenario the harness asserts: (a) the captured ERRMSG matches the expected message byte-exact; (b) the captured CACTVWAO buffer (where applicable) matches the expected serialized form byte-exact; (c) the post-run ACCTDAT, CUSTDAT, and CXACAIX file byte contents equal the pre-run contents (no modification). The three UNCHANGED invariants are universal across all 12 scenarios because COACTVWC has zero write-shaped CICS verbs in its source.

## Phase 12 -- Files in This Directory

This directory contains exactly four files. The README.md (this document) is the authoritative contract; the three sibling files are the captured baseline artifacts that the Java test class compares against.

| File | Purpose | Size |
|---|---|---|
| `README.md` | This document (authoritative contract) | 500-700 lines |
| `input_scenario.txt` | CICS pseudo-conversation driver script for all 12 scenarios | 200-280 lines |
| `stdout.txt` | Captured COBOL DISPLAY output -- EXPECTED EMPTY (placeholder) | 8 lines (all comments) |
| `bms_output.txt` | Captured serialized BMS SEND MAP outputs (placeholder pending capture) | 13 lines (all comments) |

Until COBOL capture completes per `java/MIGRATION_NOTES.md` section 1.6, `stdout.txt` and `bms_output.txt` hold placeholder content and the consuming Java test class `CoActVwCGoldenTest` is `@Disabled` per AAP section 0.6.11.

## Phase 13 -- Structural Invariants

### Phase 13.1 -- BMS Map Structure

CACTVWA is a 24-row by 80-column 3270 BMS map with 1 editable input field (ACCTSID), approximately 30 read-only output fields, and 3 message rows. The mapset and map names MUST match the COBOL literals byte-for-byte: `COACTVW` (mapset, declared as `LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '` at L147-L148 with one trailing space) and `CACTVWA` (map, declared as `LIT-THISMAP PIC X(7) VALUE 'CACTVWA'` at L149-L150 exactly 7 characters). Any deviation in mapset or map name causes the EXEC CICS SEND MAP / RECEIVE MAP to fail at the CICS resource-resolution layer.

### Phase 13.2 -- Account/Customer/Xref No-Modification

All three VSAM files (ACCTDAT, CUSTDAT, CXACAIX) are READ-only. There is no `UPDATE` clause on any READ, no `REWRITE` verb, no `WRITE` verb, and no `DELETE` verb in `app/cbl/COACTVWC.cbl`. Post-run bytes MUST equal pre-run bytes for each of the three files. This invariant is expressed via three EXPECT_ACCTDAT_UNCHANGED / EXPECT_CUSTDAT_UNCHANGED / EXPECT_CXACAIX_UNCHANGED assertions in EVERY scenario of `input_scenario.txt`. The Java translation MUST preserve this invariant through repository-port discipline: the file-backed adapters expose read-shaped methods only, and the use case `CoActVwC` never invokes a write-shaped method on any port.

### Phase 13.3 -- READ-Only vs Update Programs

COACTVWC (Account View, transaction `CAVW`) is functionally distinct from COACTUPC (Account Update, transaction `CAUP`). The two programs share the conceptual domain of account data but differ fundamentally in CICS file access mode. COACTVWC issues only `EXEC CICS READ` without the UPDATE clause; COACTUPC issues `EXEC CICS READ ... UPDATE` followed by `EXEC CICS REWRITE` to mutate the account record. The two have distinct BMS mapsets (`COACTVW` vs `COACTUP`) and distinct transactions. The COACTUPC translation lives in a sibling `golden/coactupc/expected/` folder (not this one); any merge of the two fixtures would invalidate the READ-only contract.

### Phase 13.4 -- Java Class Mapping and @CobolProgram Annotation

The Java class `com.blitzy.carddemo.application.account.CoActVwC` carries a `@CobolProgram` annotation citing the original PROGRAM-ID, the source file path, and the translation date per AAP section 0.3.1. The annotation is informational only (the annotation retention is SOURCE per the CobolProgram declaration), serving as inline Javadoc-style traceability that any future maintainer can use to locate the COBOL baseline:

```java
@CobolProgram(
    programId = "COACTVWC",
    sourcePath = "app/cbl/COACTVWC.cbl",
    translatedOn = "YYYY-MM-DD"
)
public final class CoActVwC {
    // method per original COBOL paragraph
}
```

### Phase 13.5 -- PAN Masking

Any 16-digit `XREF-CARD-NUM` value (PIC X(16) from `app/cpy/CVACT03Y.cpy:L5`) reaching captured logs, diagnostic messages, or abnormal-exit dumps MUST be masked such that only the last 4 digits are visible (e.g., `************1234`), per AAP section 0.7.2. The COACTVW BMS map itself does NOT display CARD-NUM on the visible screen rows; the value is acquired from the CXACAIX read solely to flow forward through `CDEMO-CARD-NUM` in the commarea, where downstream programs (COCRDSLC, COCRDUPC) may use it as their own RIDFLD. PAN masking is therefore principally an invariant for the Java translation's logging surfaces: any SLF4J log call that takes a CardXrefRecord as an argument MUST mask its `XREF-CARD-NUM` before formatting.

## Verbatim COBOL Message Catalog

This catalog reproduces every operator-visible message string that the COACTVWC program can emit, with byte-exact source-line citations.

```text
WS-INFO-MSG 88-level conditions (3 total):
  L111-L112 WS-NO-INFO-MESSAGE  VALUES SPACES LOW-VALUES.
  L113-L114 WS-PROMPT-FOR-INPUT VALUE 'Enter or update id of account to display'
  L115-L116 WS-INFORM-OUTPUT    VALUE 'Displaying details of given Account'

WS-RETURN-MSG 88-level conditions (10 total):
  L118     WS-RETURN-MSG-OFF              VALUE SPACES
  L119-L120 WS-EXIT-MESSAGE                VALUE 'PF03 pressed.Exiting              '
              (20-char prefix + 14 trailing spaces = 34 bytes within quotes)
  L121-L122 WS-PROMPT-FOR-ACCT             VALUE 'Account number not provided'
  L123-L124 NO-SEARCH-CRITERIA-RECEIVED    VALUE 'No input received'
  L125-L126 SEARCHED-ACCT-ZEROES           VALUE 'Account number must be a non zero 11 digit number'
  L127-L128 SEARCHED-ACCT-NOT-NUMERIC      VALUE 'Account number must be a non zero 11 digit number'  (DUPLICATE TEXT)
  L129-L130 DID-NOT-FIND-ACCT-IN-CARDXREF  VALUE 'Did not find this account in account card xref file'
  L131-L132 DID-NOT-FIND-ACCT-IN-ACCTDAT   VALUE 'Did not find this account in account master file'
  L133-L134 DID-NOT-FIND-CUST-IN-CUSTDAT   VALUE 'Did not find associated customer in master file'
  L135-L136 XREF-READ-ERROR                VALUE 'Error reading account card xref File'
  L137-L138 CODING-TO-BE-DONE              VALUE 'Looks Good.... so far'  (DEAD CODE)

Inline literals (3 total):
  L379     'UNEXPECTED DATA SCENARIO'     -- WHEN OTHER branch of 0000-MAIN
  L672     'Account Filter must  be a non-zero 11 digit number'  (DOUBLE SPACE between 'must' and 'be'; 51 chars)
  L919     'UNEXPECTED ABEND OCCURRED.'   -- default ABEND-MSG when LOW-VALUES

Dynamic STRING templates (3 total, in 9200/9300/9400 WHEN NOTFND paths):
  L747-L757 'Account:' + ACCT-ID + ' not found in' + ' Cross ref file.  Resp:' + ERROR-RESP + ' Reas:' + ERROR-RESP2
              (DOUBLE SPACE after 'file.')
  L796-L806 'Account:' + ACCT-ID + ' not found in' + ' Acct Master file.Resp:' + ERROR-RESP + ' Reas:' + ERROR-RESP2
              (NO double space; tight 'file.Resp:' form)
  L846-L856 'CustId:' + CUST-ID + ' not found' + ' in customer master.Resp: ' + ERROR-RESP + ' REAS:' + ERROR-RESP2
              (trailing space after 'Resp:'; uppercase ' REAS:')

WS-FILE-ERROR-MESSAGE template (75 bytes, L86-L105 in WORKING-STORAGE):
  'File Error: ' + ERROR-OPNAME (8) + ' on ' + ERROR-FILE (9) + ' returned RESP ' + ERROR-RESP (10)
  + ',RESP2 ' + ERROR-RESP2 (10) + 5-byte trailing FILLER of spaces
```

## Capture Procedure Cross-Reference

See `java/MIGRATION_NOTES.md` section 1.6 for the CICS COBOL build/run path used to capture COACTVWC expected outputs per AAP section 0.7.5. Until COBOL capture commits real bytes, this folder holds placeholder `stdout.txt` and `bms_output.txt` files and the consuming Java test class `com.blitzy.carddemo.tests.golden.CoActVwCGoldenTest` is `@Disabled` per AAP section 0.6.11.

## Source Lineage

All source files below are UNCHANGED per AAP sections 0.1.1 and 0.2.2; this fixture derives every fact by direct citation of these immutable references.

- `app/cbl/COACTVWC.cbl` (941 lines; PROGRAM-ID COACTVWC)
- `app/bms/COACTVW.bms` (378 lines; MAPSET COACTVW, MAP CACTVWA)
- `app/cpy-bms/COACTVW.CPY` (464 lines; CACTVWAI and CACTVWAO symbolic maps)
- `app/cpy/CVACT01Y.cpy` (ACCOUNT-RECORD, 300 bytes)
- `app/cpy/CVACT03Y.cpy` (CARD-XREF-RECORD, 50 bytes)
- `app/cpy/CVCUS01Y.cpy` (CUSTOMER-RECORD, 500 bytes)
- `app/cpy/COCOM01Y.cpy` (CARDDEMO-COMMAREA, 160 bytes)
- `app/cpy/CVCRD01Y.cpy` (CC-WORK-AREAS, AID-key 88-levels)
- `app/cpy/CSDAT01Y.cpy` (WS-DATE-TIME structure)
- `app/data/ASCII/acctdata.txt` (50 account records, 15050 bytes)
- `app/data/ASCII/custdata.txt` (50 customer records, 25050 bytes)
- `app/data/ASCII/cardxref.txt` (cross-reference fixture, 1850 bytes)

## Cross-References

The Java artifacts that consume or depend on this fixture (one bullet per file):

- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoActVwCGoldenTest.java` (test class)
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` (test base class)
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CoActVwC.java` (class under test)
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CoActVwInput.java` (input DTO per AAP section 0.4.1)
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CoActVwOutput.java` (output DTO)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java` (from CVACT01Y)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` (from CVACT03Y)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CustomerRecord.java` (from CVCUS01Y)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/commarea/CardDemoCommarea.java` (from COCOM01Y)
- `../input/README.md` (sibling documentation-only marker)

## Authority References

The AAP sections that govern every fact and rule documented in this README:

- AAP section 0.1.1 (COBOL source is UNCHANGED reference implementation)
- AAP section 0.2.1 (file inclusion as part of `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP section 0.2.2 (out-of-scope: `app/` tree is UNCHANGED)
- AAP section 0.3.1 (`@CobolProgram` annotation + harness directory convention)
- AAP section 0.4.1 (transformation mapping for COACTVWC -> CoActVwC in application.account subpackage)
- AAP section 0.4.3 (trailing wildcards including `golden/**/*`)
- AAP section 0.6.4 (java.time mandate; ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, CUST-DOB-YYYY-MM-DD all LocalDate)
- AAP section 0.6.5 (java.nio.file mandate; no java.io.File)
- AAP section 0.6.6 (ScopedValue replaces ThreadLocal; fixed-Clock injection for byte-reproducible captures)
- AAP section 0.6.11 (golden-record PR gate; @Disabled until capture committed)
- AAP section 0.6.12 (architectural override: plain Java with constructor injection, no relational persistence)
- AAP section 0.7.1 (Minimal Change Clause; verbatim message preservation including L672 double-space and L408/L411 duplicate paragraph)
- AAP section 0.7.2 (PAN masking)
- AAP section 0.7.4 (no JEP preview features; no fall-through in pattern-matching switches; no ThreadLocal)
- AAP section 0.7.5 (capture procedure cross-reference in MIGRATION_NOTES.md)

## Contrast Matrix (COACTVWC vs COCRDSLC vs COCRDUPC)

This matrix highlights the key dimensions that distinguish COACTVWC from its two closest siblings.

| Dimension | COACTVWC (CAVW) | COCRDSLC (CCDL) | COCRDUPC (CCUP) |
|---|---|---|---|
| Purpose | View Account | View Card Detail | Update Card |
| Java package | application.account | application.card | application.card |
| Java class | CoActVwC | CoCrdSlC | CoCrdUpC |
| BMS mapset | COACTVW | COCRDSL | COCRDUP |
| BMS map | CACTVWA | CCRDSLA | CCRDUPA |
| Files READ | 3 (CXACAIX, ACCTDAT, CUSTDAT) | 1 (CARDDAT) | 1 (CARDDAT) |
| READ mode | READ-only | READ-only | READ + REWRITE |
| Editable fields | 1 (ACCTSID) | 2 (ACCTSID, CARDSID) | 2+ (ACCTSID, CARDSID, card fields) |
| Main EVALUATE branches | 4 | 5 (incl. auto-trigger from COCRDLIC) | 5+ |
| EXPECT_*_UNCHANGED assertions | 3 (ACCTDAT, CUSTDAT, CXACAIX) | 1 (CARDDAT) | 0 (CARDDAT modified) |

## DO NOT Modify Without Re-Capture

```text
PROTECTIVE RULES:

1. Any change to stdout.txt or bms_output.txt MUST be paired with a documented
   COBOL re-capture per java/MIGRATION_NOTES.md section 1.6.

2. input_scenario.txt MAY be extended with new scenarios, but existing scenarios
   MUST NOT be modified once committed (this would invalidate captured outputs).

3. README.md MAY be updated for clarity, but the authoritative invariants
   (program identity, file lengths, message catalog, scenario count) MUST NOT
   change without re-capture.

4. The L672 double-space ('must  be') and L120 14-trailing-space WS-EXIT-MESSAGE
   are byte-level preservation requirements per AAP section 0.7.1.

5. The 3 EXPECT_*_UNCHANGED invariants on every scenario are non-negotiable PR
   gates per AAP section 0.6.11.
```
