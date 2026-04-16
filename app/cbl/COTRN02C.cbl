      ******************************************************************        
      * Program     : COTRN02C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Add a new Transaction to TRANSACT file
      ******************************************************************
      * Copyright Amazon.com, Inc. or its affiliates.                   
      * All Rights Reserved.                                            
      *                                                                 
      * Licensed under the Apache License, Version 2.0 (the "License"). 
      * You may not use this file except in compliance with the License.
      * You may obtain a copy of the License at                         
      *                                                                 
      *    http://www.apache.org/licenses/LICENSE-2.0                   
      *                                                                 
      * Unless required by applicable law or agreed to in writing,      
      * software distributed under the License is distributed on an     
      * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    
      * either express or implied. See the License for the specific     
      * language governing permissions and limitations under the License
      ****************************************************************** 
      *================================================================*
      * Program:     COTRN02C
      * Transaction: CT02
      * BMS Map:     COTRN02 / COTRN2A
      * Function:    Transaction add screen. Collects card number,
      *              account ID, transaction type/category/source,
      *              amount, description, merchant info, and timestamps.
      *              Validates key fields against CXACAIX (card cross-
      *              reference by card number) and CCXREF (account
      *              cross-reference). Generates next transaction ID via
      *              STARTBR/READPREV at HIGH-VALUES, then writes new
      *              record to TRANSACT. PF5 copies last transaction.
      * Flow:        Two-phase interaction — (1) data entry with
      *              field-level validation, (2) user confirmation
      *              (Y/N) followed by VSAM WRITE on commit.
      * Files:       CXACAIX (READ — validate card number via AIX)
      *              CCXREF  (READ — validate account from xref)
      *              TRANSACT (STARTBR, READPREV, ENDBR, WRITE)
      * Navigation:  PF3 returns to caller. PF4 clears form.
      *              PF5 copies last transaction data into form.
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COTRN02C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.
      *
      * --- Program constants and VSAM file name literals ----------
       01 WS-VARIABLES.
         05 WS-PGMNAME                 PIC X(08) VALUE 'COTRN02C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CT02'.
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      * VSAM KSDS file identifiers used by EXEC CICS I/O
         05 WS-TRANSACT-FILE           PIC X(08) VALUE 'TRANSACT'.
         05 WS-ACCTDAT-FILE            PIC X(08) VALUE 'ACCTDAT '.
         05 WS-CCXREF-FILE             PIC X(08) VALUE 'CCXREF  '.
         05 WS-CXACAIX-FILE            PIC X(08) VALUE 'CXACAIX '.
      *
      * --- Error and response flags --------------------------------
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      * CICS RESP / RESP2 codes from file I/O operations
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      * Tracks whether user modified screen data (reserved)
         05 WS-USR-MODIFIED            PIC X(01) VALUE 'N'.
           88 USR-MODIFIED-YES                   VALUE 'Y'.
           88 USR-MODIFIED-NO                    VALUE 'N'.
      *
      * --- Numeric work fields for conversion and formatting -------
         05 WS-TRAN-AMT                PIC +99999999.99.
         05 WS-TRAN-DATE               PIC X(08) VALUE '00/00/00'.
      * Numeric versions of key screen inputs for NUMVAL conversion
         05 WS-ACCT-ID-N               PIC 9(11) VALUE 0.
         05 WS-CARD-NUM-N              PIC 9(16) VALUE 0.
      * Next transaction ID derived via browse-to-end pattern
         05 WS-TRAN-ID-N               PIC 9(16) VALUE ZEROS.
      * Signed numeric amount for TRAN-AMT population
         05 WS-TRAN-AMT-N              PIC S9(9)V99 VALUE ZERO.
      * Edited amount for screen display (+99999999.99)
         05 WS-TRAN-AMT-E              PIC +99999999.99 VALUE ZEROS.
      * Date format mask passed to CSUTLDTC for validation
         05 WS-DATE-FORMAT             PIC X(10) VALUE 'YYYY-MM-DD'.
      *
      * --- Parameter block for CSUTLDTC date validation call ------
      * Passes date string and format to the CSUTLDTC subprogram.
      * CSUTLDTC wraps LE callable service CEEDAYS to validate
      * calendar dates. Returns severity code and message number.
       01 CSUTLDTC-PARM.
          05 CSUTLDTC-DATE                   PIC X(10).
          05 CSUTLDTC-DATE-FORMAT            PIC X(10).
          05 CSUTLDTC-RESULT.
      * Severity 0000 = valid date; non-zero = error
             10 CSUTLDTC-RESULT-SEV-CD       PIC X(04).
             10 FILLER                       PIC X(11).
      * Message 2513 = LE internal warning (treated as valid)
             10 CSUTLDTC-RESULT-MSG-NUM      PIC X(04).
             10 CSUTLDTC-RESULT-MSG          PIC X(61).

      * COMMAREA structure for inter-program communication.
      * See COCOM01Y.cpy for the shared CARDDEMO-COMMAREA layout
      * containing routing, user identity, and program context.
       COPY COCOM01Y.
      * CT02-specific extension of the COMMAREA for this program.
      * Carries transaction browse state and selection from the
      * transaction list screen (COTRN00C) when navigating here.
          05 CDEMO-CT02-INFO.
      * First and last transaction IDs for page boundary tracking
             10 CDEMO-CT02-TRNID-FIRST     PIC X(16).
             10 CDEMO-CT02-TRNID-LAST      PIC X(16).
             10 CDEMO-CT02-PAGE-NUM        PIC 9(08).
             10 CDEMO-CT02-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
                88 NEXT-PAGE-YES                     VALUE 'Y'.
                88 NEXT-PAGE-NO                      VALUE 'N'.
      * Selection flag and selected card number from list screen
             10 CDEMO-CT02-TRN-SEL-FLG     PIC X(01).
             10 CDEMO-CT02-TRN-SELECTED    PIC X(16).

      * BMS symbolic map for transaction add screen (COTRN2A)
       COPY COTRN02.

      * Application title and banner text
       COPY COTTL01Y.
      * Date/time working storage fields
       COPY CSDAT01Y.
      * Common user message definitions
       COPY CSMSG01Y.

      * 350-byte transaction record layout (TRAN-RECORD)
       COPY CVTRA05Y.
      * 300-byte account record layout (ACCT-REC)
       COPY CVACT01Y.
      * 50-byte card cross-reference record (CARD-XREF-REC)
       COPY CVACT03Y.

      * CICS attention identifier constants (ENTER, PF keys)
       COPY DFHAID.
      * BMS attribute constants (colors, highlights)
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
      * CICS passes the COMMAREA from the calling program via the
      * DFHCOMMAREA linkage area. EIBCALEN holds the byte length.
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * Main entry point. If card number was passed via COMMAREA,
      * auto-populate. AID dispatch: Enter=validate+add, PF3=back,
      * PF4=clear, PF5=copy last transaction data.
       MAIN-PARA.
      * Reset error and modification flags at start of each
      * pseudo-conversational iteration
           SET ERR-FLG-OFF     TO TRUE
           SET USR-MODIFIED-NO TO TRUE

           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COTRN2AO
      * If no COMMAREA passed (EIBCALEN=0), the program was
      * started outside the normal menu flow — redirect to sign-on
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
               PERFORM RETURN-TO-PREV-SCREEN
           ELSE
      * Restore COMMAREA from CICS linkage for this iteration
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      * First-time entry: initialize output map and set cursor
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO COTRN2AO
                   MOVE -1       TO ACTIDINL OF COTRN2AI
      * If a card number was selected on the prior list screen,
      * pre-populate it and trigger validation immediately
                   IF CDEMO-CT02-TRN-SELECTED NOT =
                                              SPACES AND LOW-VALUES
                       MOVE CDEMO-CT02-TRN-SELECTED TO
                            CARDNINI OF COTRN2AI
                       PERFORM PROCESS-ENTER-KEY
                   END-IF
                   PERFORM SEND-TRNADD-SCREEN
               ELSE
      * Re-entry: receive user input and dispatch on AID key
                   PERFORM RECEIVE-TRNADD-SCREEN
                   EVALUATE EIBAID
                       WHEN DFHENTER
      * ENTER validates input then checks confirmation flag
                           PERFORM PROCESS-ENTER-KEY
                       WHEN DFHPF3
      * PF3 returns to calling program or main menu
                           IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
                               MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
                           ELSE
                               MOVE CDEMO-FROM-PROGRAM TO
                               CDEMO-TO-PROGRAM
                           END-IF
                           PERFORM RETURN-TO-PREV-SCREEN
                       WHEN DFHPF4
      * PF4 clears all screen fields and resets the form
                           PERFORM CLEAR-CURRENT-SCREEN
                       WHEN DFHPF5
      * PF5 copies last transaction data into the form fields
                           PERFORM COPY-LAST-TRAN-DATA
                       WHEN OTHER
      * Any other AID key is invalid — show error message
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-TRNADD-SCREEN
                   END-EVALUATE
               END-IF
           END-IF
      * Return to CICS with pseudo-conversational wait.
      * Preserves COMMAREA and re-triggers this program on CT02.
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Validate key fields and data fields, then check the
      * confirmation flag (Y/N). Two-phase commit: first ENTER
      * populates fields and prompts for Y/N confirmation; second
      * ENTER with Y commits the WRITE to TRANSACT.
       PROCESS-ENTER-KEY.
      * Phase 1: validate account/card keys and all data fields
           PERFORM VALIDATE-INPUT-KEY-FIELDS
           PERFORM VALIDATE-INPUT-DATA-FIELDS.
      * Phase 2: evaluate the confirmation flag from screen
           EVALUATE CONFIRMI OF COTRN2AI
               WHEN 'Y'
               WHEN 'y'
      * User confirmed — proceed to generate ID and write record
                   PERFORM ADD-TRANSACTION
               WHEN 'N'
               WHEN 'n'
               WHEN SPACES
               WHEN LOW-VALUES
      * Not yet confirmed — prompt user to enter Y to commit
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Confirm to add this transaction...'
                                TO WS-MESSAGE
                   MOVE -1      TO CONFIRML OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
      * Invalid confirmation value entered
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Invalid value. Valid values are (Y/N)...'
                                TO WS-MESSAGE
                   MOVE -1      TO CONFIRML OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      VALIDATE-INPUT-KEY-FIELDS
      *----------------------------------------------------------------*
      * Validate account ID or card number. User supplies one; the
      * program resolves the other via cross-reference files.
      * Account path: NUMVAL → READ CXACAIX → auto-fill card.
      * Card path: NUMVAL → READ CCXREF → auto-fill account.
       VALIDATE-INPUT-KEY-FIELDS.
      * Mutual-resolution: accept account OR card, derive the other
           EVALUATE TRUE
               WHEN ACTIDINI OF COTRN2AI NOT = SPACES AND LOW-VALUES
      * Account ID supplied — validate numeric format
                   IF ACTIDINI OF COTRN2AI IS NOT NUMERIC
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE 'Account ID must be Numeric...' TO
                                       WS-MESSAGE
                       MOVE -1       TO ACTIDINL OF COTRN2AI
                       PERFORM SEND-TRNADD-SCREEN
                   END-IF
      * Convert display format to numeric and look up in CXACAIX
      * alternate index to resolve the associated card number
                   COMPUTE WS-ACCT-ID-N = FUNCTION NUMVAL(ACTIDINI OF
                   COTRN2AI)
                   MOVE WS-ACCT-ID-N            TO XREF-ACCT-ID
                                                ACTIDINI OF COTRN2AI
                   PERFORM READ-CXACAIX-FILE
      * Auto-populate the card number from the xref record
                   MOVE XREF-CARD-NUM         TO CARDNINI OF COTRN2AI
               WHEN CARDNINI OF COTRN2AI NOT = SPACES AND LOW-VALUES
      * Card number supplied — validate numeric format
                   IF CARDNINI OF COTRN2AI IS NOT NUMERIC
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE 'Card Number must be Numeric...' TO
                                       WS-MESSAGE
                       MOVE -1       TO CARDNINL OF COTRN2AI
                       PERFORM SEND-TRNADD-SCREEN
                   END-IF
      * Convert and look up in CCXREF to resolve account ID
                   COMPUTE WS-CARD-NUM-N = FUNCTION NUMVAL(CARDNINI OF
                   COTRN2AI)
                   MOVE WS-CARD-NUM-N        TO XREF-CARD-NUM
                                                CARDNINI OF COTRN2AI
                   PERFORM READ-CCXREF-FILE
      * Auto-populate the account ID from the xref record
                   MOVE XREF-ACCT-ID         TO ACTIDINI OF COTRN2AI
               WHEN OTHER
      * Neither account nor card supplied — require at least one
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Account or Card Number must be entered...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                 VALIDATE-INPUT-DATA-FIELDS
      *----------------------------------------------------------------*
      * Validate all data-entry fields in four stages:
      * (1) Mandatory presence checks for 11 fields
      * (2) Numeric format checks for type/category codes
      * (3) Amount format validation (+/-99999999.99)
      * (4) Date format and calendar validation via CSUTLDTC
      * (5) Merchant ID numeric check
       VALIDATE-INPUT-DATA-FIELDS.
      * If a prior key-field error occurred, clear data fields
      * so the user re-enters them after fixing the key field
           IF ERR-FLG-ON
               MOVE SPACES      TO TTYPCDI  OF COTRN2AI
                                   TCATCDI  OF COTRN2AI
                                   TRNSRCI  OF COTRN2AI
                                   TRNAMTI  OF COTRN2AI
                                   TDESCI   OF COTRN2AI
                                   TORIGDTI OF COTRN2AI
                                   TPROCDTI OF COTRN2AI
                                   MIDI     OF COTRN2AI
                                   MNAMEI   OF COTRN2AI
                                   MCITYI   OF COTRN2AI
                                   MZIPI    OF COTRN2AI
           END-IF.

      * --- Stage 1: mandatory presence checks for 11 data fields.
      * First blank field found stops evaluation and sends error.
           EVALUATE TRUE
               WHEN TTYPCDI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Type CD can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TTYPCDL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TCATCDI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Category CD can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TCATCDL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TRNSRCI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Source can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TRNSRCL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TDESCI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Description can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TDESCL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TRNAMTI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Amount can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TRNAMTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TORIGDTI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Orig Date can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TORIGDTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TPROCDTI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Proc Date can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TPROCDTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN MIDI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Merchant ID can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO MIDL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN MNAMEI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Merchant Name can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO MNAMEL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN MCITYI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Merchant City can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO MCITYL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN MZIPI OF COTRN2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Merchant Zip can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO MZIPL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
                   CONTINUE
           END-EVALUATE.
      *
      * --- Stage 2: numeric format checks for type/category codes
           EVALUATE TRUE
               WHEN TTYPCDI OF COTRN2AI NOT NUMERIC
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Type CD must be Numeric...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TTYPCDL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN TCATCDI OF COTRN2AI NOT NUMERIC
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Category CD must be Numeric...' TO
                                   WS-MESSAGE
                   MOVE -1       TO TCATCDL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
                   CONTINUE
           END-EVALUATE
      *
      * --- Stage 3: amount positional format validation.
      * Expected layout: sign(1) digits(8) decimal(1) cents(2)
      * e.g. +00001234.56 or -00001234.56
           EVALUATE TRUE
               WHEN TRNAMTI OF COTRN2AI(1:1) NOT EQUAL '-' AND '+'
               WHEN TRNAMTI OF COTRN2AI(2:8) NOT NUMERIC
               WHEN TRNAMTI OF COTRN2AI(10:1) NOT = '.'
               WHEN TRNAMTI OF COTRN2AI(11:2) IS NOT NUMERIC
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Amount should be in format -99999999.99' TO
                                   WS-MESSAGE
                   MOVE -1       TO TRNAMTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
                   CONTINUE
           END-EVALUATE
      *
      * --- Stage 4a: origination date format (YYYY-MM-DD)
           EVALUATE TRUE
               WHEN TORIGDTI OF COTRN2AI(1:4) IS NOT NUMERIC
               WHEN TORIGDTI OF COTRN2AI(5:1) NOT EQUAL '-'
               WHEN TORIGDTI OF COTRN2AI(6:2) NOT NUMERIC
               WHEN TORIGDTI OF COTRN2AI(8:1) NOT EQUAL '-'
               WHEN TORIGDTI OF COTRN2AI(9:2) NOT NUMERIC
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Orig Date should be in format YYYY-MM-DD' TO
                                   WS-MESSAGE
                   MOVE -1       TO TORIGDTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
                   CONTINUE
           END-EVALUATE
      *
      * --- Stage 4b: processing date format (YYYY-MM-DD)
           EVALUATE TRUE
               WHEN TPROCDTI OF COTRN2AI(1:4) IS NOT NUMERIC
               WHEN TPROCDTI OF COTRN2AI(5:1) NOT EQUAL '-'
               WHEN TPROCDTI OF COTRN2AI(6:2) NOT NUMERIC
               WHEN TPROCDTI OF COTRN2AI(8:1) NOT EQUAL '-'
               WHEN TPROCDTI OF COTRN2AI(9:2) NOT NUMERIC
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Proc Date should be in format YYYY-MM-DD' TO
                                   WS-MESSAGE
                   MOVE -1       TO TPROCDTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
                   CONTINUE
           END-EVALUATE
      * Convert amount string to signed numeric via NUMVAL-C and
      * reformat into edited display format for screen echo
           COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI OF
           COTRN2AI)
           MOVE WS-TRAN-AMT-N TO WS-TRAN-AMT-E
           MOVE WS-TRAN-AMT-E TO TRNAMTI OF COTRN2AI

      * --- Stage 4c: calendar validation of origination date.
      * CALL CSUTLDTC wraps LE CEEDAYS to check the date is a
      * real calendar date (not just correctly formatted).
      * Severity 0000 = valid; msg 2513 = LE warning (acceptable).
           MOVE TORIGDTI OF COTRN2AI TO CSUTLDTC-DATE
           MOVE WS-DATE-FORMAT       TO CSUTLDTC-DATE-FORMAT
           MOVE SPACES               TO CSUTLDTC-RESULT

           CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                                   CSUTLDTC-DATE-FORMAT
                                   CSUTLDTC-RESULT

           IF CSUTLDTC-RESULT-SEV-CD = '0000'
               CONTINUE
           ELSE
               IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
                   MOVE 'Orig Date - Not a valid date...'
                     TO WS-MESSAGE
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE -1       TO TORIGDTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               END-IF
           END-IF
      * --- Stage 4d: calendar validation of processing date
           MOVE TPROCDTI OF COTRN2AI TO CSUTLDTC-DATE
           MOVE WS-DATE-FORMAT       TO CSUTLDTC-DATE-FORMAT
           MOVE SPACES               TO CSUTLDTC-RESULT

           CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                                   CSUTLDTC-DATE-FORMAT
                                   CSUTLDTC-RESULT

           IF CSUTLDTC-RESULT-SEV-CD = '0000'
               CONTINUE
           ELSE
               IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
                   MOVE 'Proc Date - Not a valid date...'
                     TO WS-MESSAGE
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE -1       TO TPROCDTL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               END-IF
           END-IF

      * --- Stage 5: merchant ID must be numeric
           IF MIDI OF COTRN2AI IS NOT NUMERIC
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'Merchant ID must be Numeric...' TO
                               WS-MESSAGE
               MOVE -1       TO MIDL OF COTRN2AI
               PERFORM SEND-TRNADD-SCREEN
           END-IF
           .

      *----------------------------------------------------------------*
      *                        ADD-TRANSACTION
      *----------------------------------------------------------------*
      * Generate the next transaction ID using the browse-to-end
      * pattern and write a new 350-byte TRAN-RECORD to the
      * TRANSACT VSAM KSDS. See CVTRA05Y.cpy for record layout.
       ADD-TRANSACTION.
      * --- Next-ID generation via browse-to-end pattern ----------
      * Set TRAN-ID to HIGH-VALUES so STARTBR positions at or
      * beyond the last record in the KSDS key sequence.
           MOVE HIGH-VALUES TO TRAN-ID
           PERFORM STARTBR-TRANSACT-FILE
      * READPREV reads the last (highest-key) record backward,
      * placing the highest existing transaction ID in TRAN-ID.
           PERFORM READPREV-TRANSACT-FILE
           PERFORM ENDBR-TRANSACT-FILE
      * Convert the highest ID to numeric and add 1 for the new ID
           MOVE TRAN-ID     TO WS-TRAN-ID-N
           ADD 1 TO WS-TRAN-ID-N
      * --- Build the new TRAN-RECORD from screen input -----------
      * Initialize clears all fields including FILLER bytes
           INITIALIZE TRAN-RECORD
      * Assign the newly generated sequential transaction ID
           MOVE WS-TRAN-ID-N         TO TRAN-ID
      * Map screen fields to the 350-byte record layout
           MOVE TTYPCDI  OF COTRN2AI TO TRAN-TYPE-CD
           MOVE TCATCDI  OF COTRN2AI TO TRAN-CAT-CD
           MOVE TRNSRCI  OF COTRN2AI TO TRAN-SOURCE
           MOVE TDESCI   OF COTRN2AI TO TRAN-DESC
      * Convert edited amount back to signed numeric for storage
           COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI OF
           COTRN2AI)
           MOVE WS-TRAN-AMT-N TO TRAN-AMT
      * Card number, merchant details, and timestamps from screen
           MOVE CARDNINI OF COTRN2AI TO TRAN-CARD-NUM
           MOVE MIDI     OF COTRN2AI TO TRAN-MERCHANT-ID
           MOVE MNAMEI   OF COTRN2AI TO TRAN-MERCHANT-NAME
           MOVE MCITYI   OF COTRN2AI TO TRAN-MERCHANT-CITY
           MOVE MZIPI    OF COTRN2AI TO TRAN-MERCHANT-ZIP
           MOVE TORIGDTI OF COTRN2AI TO TRAN-ORIG-TS
           MOVE TPROCDTI OF COTRN2AI TO TRAN-PROC-TS
      * Write the populated record to TRANSACT VSAM KSDS
           PERFORM WRITE-TRANSACT-FILE.

      *----------------------------------------------------------------*
      *                      COPY-LAST-TRAN-DATA
      *----------------------------------------------------------------*
      * PF5 handler: copy field values from the last transaction
      * record into the screen input fields as a data entry
      * convenience. Requires valid account/card first.
      * Uses the same browse-to-end pattern as ADD-TRANSACTION.
       COPY-LAST-TRAN-DATA.
      * Ensure account/card keys are valid before browsing
           PERFORM VALIDATE-INPUT-KEY-FIELDS
      * Browse to end to read the last transaction record
           MOVE HIGH-VALUES TO TRAN-ID
           PERFORM STARTBR-TRANSACT-FILE
           PERFORM READPREV-TRANSACT-FILE
           PERFORM ENDBR-TRANSACT-FILE
      * If browse succeeded, map last transaction fields to screen
           IF NOT ERR-FLG-ON
               MOVE TRAN-AMT TO WS-TRAN-AMT-E
               MOVE TRAN-TYPE-CD        TO TTYPCDI  OF COTRN2AI
               MOVE TRAN-CAT-CD         TO TCATCDI  OF COTRN2AI
               MOVE TRAN-SOURCE         TO TRNSRCI  OF COTRN2AI
               MOVE WS-TRAN-AMT-E       TO TRNAMTI  OF COTRN2AI
               MOVE TRAN-DESC           TO TDESCI   OF COTRN2AI
               MOVE TRAN-ORIG-TS        TO TORIGDTI OF COTRN2AI
               MOVE TRAN-PROC-TS        TO TPROCDTI OF COTRN2AI
               MOVE TRAN-MERCHANT-ID    TO MIDI     OF COTRN2AI
               MOVE TRAN-MERCHANT-NAME  TO MNAMEI   OF COTRN2AI
               MOVE TRAN-MERCHANT-CITY  TO MCITYI   OF COTRN2AI
               MOVE TRAN-MERCHANT-ZIP   TO MZIPI    OF COTRN2AI
           END-IF
      * Proceed to normal enter-key processing with copied data
           PERFORM PROCESS-ENTER-KEY.

      *----------------------------------------------------------------*
      *                      RETURN-TO-PREV-SCREEN
      *----------------------------------------------------------------*
      * Transfer control to the previous or default screen via
      * EXEC CICS XCTL, passing the COMMAREA for state continuity.
       RETURN-TO-PREV-SCREEN.
      * Default to sign-on screen if no target was set
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      * Record this program as the source for the target program
           MOVE WS-TRANID    TO CDEMO-FROM-TRANID
           MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
      * Reset context to first-time entry in the target program
           MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      * XCTL transfers control — this program is removed from
      * the CICS program link stack
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
               COMMAREA(CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-TRNADD-SCREEN
      *----------------------------------------------------------------*
      * Populate header fields and send BMS map COTRN2A to the
      * 3270 terminal. ERASE clears the screen before painting.
      * CURSOR positions to the field set with MOVE -1 to its
      * length byte. After SEND, issues CICS RETURN to wait
      * for the next user interaction (pseudo-conversational).
       SEND-TRNADD-SCREEN.

           PERFORM POPULATE-HEADER-INFO
      * Copy any error or success message to the screen output
           MOVE WS-MESSAGE TO ERRMSGO OF COTRN2AO
      * Send the symbolic output map to the terminal
           EXEC CICS SEND
                     MAP('COTRN2A')
                     MAPSET('COTRN02')
                     FROM(COTRN2AO)
                     ERASE
                     CURSOR
           END-EXEC.
      * Return to CICS — the next user AID key will restart
      * this program with the preserved COMMAREA
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
      *              LENGTH(LENGTH OF CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-TRNADD-SCREEN
      *----------------------------------------------------------------*
      * Receive user input from BMS map COTRN2A into the
      * symbolic input area COTRN2AI. RESP/RESP2 capture any
      * CICS errors during the RECEIVE operation.
       RECEIVE-TRNADD-SCREEN.

           EXEC CICS RECEIVE
                     MAP('COTRN2A')
                     MAPSET('COTRN02')
                     INTO(COTRN2AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Fill screen header fields with application titles from
      * COTTL01Y.cpy, transaction/program names, and the current
      * date and time from FUNCTION CURRENT-DATE.
      * See CSDAT01Y.cpy for the date/time working storage layout.
       POPULATE-HEADER-INFO.
      * Capture system date/time into CSDAT01Y work area
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      * Set application banner titles from COTTL01Y constants
           MOVE CCDA-TITLE01           TO TITLE01O OF COTRN2AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COTRN2AO
           MOVE WS-TRANID              TO TRNNAMEO OF COTRN2AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COTRN2AO
      * Reformat date from YYYYMMDD to MM/DD/YY for display
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COTRN2AO
      * Reformat time from HHMMSSCC to HH:MM:SS for display
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COTRN2AO.

      *----------------------------------------------------------------*
      *                      READ-CXACAIX-FILE
      *----------------------------------------------------------------*
      * Read the account-to-card alternate index (CXACAIX) using
      * the account ID as the key. Returns the cross-reference
      * record (CVACT03Y.cpy layout) containing XREF-CARD-NUM.
       READ-CXACAIX-FILE.
      * Keyed READ against the CXACAIX alternate index path
           EXEC CICS READ
                DATASET   (WS-CXACAIX-FILE)
                INTO      (CARD-XREF-RECORD)
                LENGTH    (LENGTH OF CARD-XREF-RECORD)
                RIDFLD    (XREF-ACCT-ID)
                KEYLENGTH (LENGTH OF XREF-ACCT-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      * Evaluate CICS response code from the READ
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      * Record found — CARD-XREF-RECORD contains the mapping
                   CONTINUE
               WHEN DFHRESP(NOTFND)
      * No matching account in the cross-reference AIX
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Account ID NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
      * Unexpected CICS error — log RESP/REAS and report
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup Acct in XREF AIX file...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      READ-CCXREF-FILE
      *----------------------------------------------------------------*
      * Read the card-to-account cross-reference file (CCXREF)
      * using card number as the key. Returns the CARD-XREF-RECORD
      * (CVACT03Y.cpy layout) containing XREF-ACCT-ID.
       READ-CCXREF-FILE.
      * Keyed READ against the CCXREF base cluster by card number
           EXEC CICS READ
                DATASET   (WS-CCXREF-FILE)
                INTO      (CARD-XREF-RECORD)
                LENGTH    (LENGTH OF CARD-XREF-RECORD)
                RIDFLD    (XREF-CARD-NUM)
                KEYLENGTH (LENGTH OF XREF-CARD-NUM)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      * Evaluate CICS response code from the READ
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      * Card found — CARD-XREF-RECORD has the account mapping
                   CONTINUE
               WHEN DFHRESP(NOTFND)
      * No matching card in the cross-reference file
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Card Number NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO CARDNINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
      * Unexpected CICS error — log and report
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup Card # in XREF file...' TO
                                   WS-MESSAGE
                   MOVE -1       TO CARDNINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                    STARTBR-TRANSACT-FILE
      *----------------------------------------------------------------*
      * Start a browse on the TRANSACT VSAM KSDS. When TRAN-ID
      * is set to HIGH-VALUES, CICS positions at or beyond the
      * last record, enabling READPREV to fetch the highest key.
      * NOTFND means the file is empty (no records to browse).
       STARTBR-TRANSACT-FILE.
      * Initiate browse session at the RIDFLD position
           EXEC CICS STARTBR
                DATASET   (WS-TRANSACT-FILE)
                RIDFLD    (TRAN-ID)
                KEYLENGTH (LENGTH OF TRAN-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      * Browse positioned — ready for READPREV
                   CONTINUE
               WHEN DFHRESP(NOTFND)
      * No records in TRANSACT — cannot derive next ID
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Transaction ID NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
      * Unexpected CICS error on STARTBR
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup Transaction...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                    READPREV-TRANSACT-FILE
      *----------------------------------------------------------------*
      * Read backward from the browse position to retrieve the
      * record with the highest transaction ID. The TRAN-ID
      * field is updated by CICS to reflect the actual key read.
      * ENDFILE means the file has no records — set ID to zero
      * so ADD 1 yields transaction ID 0000000000000001.
       READPREV-TRANSACT-FILE.
      * Read the preceding record in key-descending order
           EXEC CICS READPREV
                DATASET   (WS-TRANSACT-FILE)
                INTO      (TRAN-RECORD)
                LENGTH    (LENGTH OF TRAN-RECORD)
                RIDFLD    (TRAN-ID)
                KEYLENGTH (LENGTH OF TRAN-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      * TRAN-ID now holds the highest existing transaction key
                   CONTINUE
               WHEN DFHRESP(ENDFILE)
      * Empty file — start numbering from zero (add 1 later)
                   MOVE ZEROS TO TRAN-ID
               WHEN OTHER
      * Unexpected error during backward browse read
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup Transaction...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                    ENDBR-TRANSACT-FILE
      *----------------------------------------------------------------*
      * End the TRANSACT file browse session started by STARTBR.
      * Must be called after READPREV to release browse resources.
       ENDBR-TRANSACT-FILE.

           EXEC CICS ENDBR
                DATASET   (WS-TRANSACT-FILE)
           END-EXEC.

      *----------------------------------------------------------------*
      *                    WRITE-TRANSACT-FILE
      *----------------------------------------------------------------*
      * Write the new 350-byte transaction record to TRANSACT
      * VSAM KSDS using the generated TRAN-ID as the primary key.
      * See CVTRA05Y.cpy for the TRAN-RECORD layout.
       WRITE-TRANSACT-FILE.
      * Insert the new record into the KSDS
           EXEC CICS WRITE
                DATASET   (WS-TRANSACT-FILE)
                FROM      (TRAN-RECORD)
                LENGTH    (LENGTH OF TRAN-RECORD)
                RIDFLD    (TRAN-ID)
                KEYLENGTH (LENGTH OF TRAN-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      * Success: clear form, show green confirmation with new ID
                   PERFORM INITIALIZE-ALL-FIELDS
                   MOVE SPACES             TO WS-MESSAGE
                   MOVE DFHGREEN           TO ERRMSGC  OF COTRN2AO
                   STRING 'Transaction added successfully. '
                                               DELIMITED BY SIZE
                     ' Your Tran ID is ' DELIMITED BY SIZE
                          TRAN-ID  DELIMITED BY SPACE
                          '.' DELIMITED BY SIZE
                     INTO WS-MESSAGE
                   PERFORM SEND-TRNADD-SCREEN
               WHEN DFHRESP(DUPKEY)
               WHEN DFHRESP(DUPREC)
      * Duplicate key — generated ID already exists (race condition)
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Tran ID already exist...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
               WHEN OTHER
      * Unexpected CICS error during WRITE
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to Add Transaction...' TO
                                   WS-MESSAGE
                   MOVE -1       TO ACTIDINL OF COTRN2AI
                   PERFORM SEND-TRNADD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                    CLEAR-CURRENT-SCREEN
      *----------------------------------------------------------------*
      * PF4 handler: reset all screen fields to blank and
      * re-send the empty transaction add form.
       CLEAR-CURRENT-SCREEN.

           PERFORM INITIALIZE-ALL-FIELDS.
           PERFORM SEND-TRNADD-SCREEN.

      *----------------------------------------------------------------*
      *                    INITIALIZE-ALL-FIELDS
      *----------------------------------------------------------------*
      * Clear all symbolic map input fields (key fields, data
      * fields, confirmation flag) and the error message buffer.
      * Sets cursor to account ID field via MOVE -1 to its
      * length byte (ACTIDINL).
       INITIALIZE-ALL-FIELDS.
      * Position cursor to account ID input field
           MOVE -1              TO ACTIDINL OF COTRN2AI
      * Blank all input fields and the message area
           MOVE SPACES          TO ACTIDINI OF COTRN2AI
                                   CARDNINI OF COTRN2AI
                                   TTYPCDI  OF COTRN2AI
                                   TCATCDI  OF COTRN2AI
                                   TRNSRCI  OF COTRN2AI
                                   TRNAMTI  OF COTRN2AI
                                   TDESCI   OF COTRN2AI
                                   TORIGDTI OF COTRN2AI
                                   TPROCDTI OF COTRN2AI
                                   MIDI     OF COTRN2AI
                                   MNAMEI   OF COTRN2AI
                                   MCITYI   OF COTRN2AI
                                   MZIPI    OF COTRN2AI
                                   CONFIRMI OF COTRN2AI
                                   WS-MESSAGE.

      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:34 CDT
      *
