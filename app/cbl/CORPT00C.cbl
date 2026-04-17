      ******************************************************************        
      * Program     : CORPT00C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Print Transaction reports by submitting batch 
      *               job from online using extra partition TDQ.  
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
      * CICS online program: Batch report submission
      * Program:     CORPT00C
      * Transaction: CR00
      * BMS Map:     CORPT00 / CORPT0A
      *================================================================*
      * Collects report criteria from the user: date range
      * (monthly, yearly, or custom), report type selection,
      * and confirmation before submission.
      *
      * Validates start and end dates using CSUTLDTC (the
      * shared date validation subprogram which wraps the
      * Language Environment CEEDAYS service).
      *
      * Generates a JCL deck dynamically in working storage
      * (JOB-DATA structure) incorporating user-specified date
      * parameters, then writes each 80-byte JCL line to the
      * extrapartition TDQ named JOBS via EXEC CICS WRITEQ TD.
      * The extrapartition TDQ maps to a SPOOL dataset that
      * JES picks up for batch execution, invoking the
      * TRANREPT cataloged procedure (program CBTRN03C).
      *
      * Navigation: ENTER submits the report request after
      *   confirmation. PF3 returns to the main menu
      *   (COMEN01C). Any other key displays an error.
      *
      * See: app/cpy/COCOM01Y.cpy  (COMMAREA layout)
      *      app/bms/CORPT00.bms    (BMS map definition)
      *      app/jcl/TRANREPT.jcl   (batch report procedure)
      *      app/cbl/CBTRN03C.cbl   (transaction report pgm)
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CORPT00C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.
      *
      * Program control fields, flags, and work variables
       01 WS-VARIABLES.
      *  Program name and CICS transaction ID for this screen
         05 WS-PGMNAME                 PIC X(08) VALUE 'CORPT00C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CR00'.
      *  General-purpose message buffer sent to screen ERRMSG
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      *  VSAM file name for transaction data (not directly
      *  opened by this program; used by the batch job)
         05 WS-TRANSACT-FILE             PIC X(08) VALUE 'TRANSACT'.
      *  Error flag: set to 'Y' when validation fails;
      *  prevents further processing until user corrects input
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      *  End-of-file indicator for TRANSACT browsing
         05 WS-TRANSACT-EOF            PIC X(01) VALUE 'N'.
           88 TRANSACT-EOF                       VALUE 'Y'.
           88 TRANSACT-NOT-EOF                   VALUE 'N'.
      *  Controls whether SEND MAP uses ERASE option:
      *  'Y' on first display to clear screen completely
         05 WS-SEND-ERASE-FLG          PIC X(01) VALUE 'Y'.
           88 SEND-ERASE-YES                     VALUE 'Y'.
           88 SEND-ERASE-NO                      VALUE 'N'.
      *  Loop terminator for JCL line writing iteration
         05 WS-END-LOOP                PIC X(01) VALUE 'N'.
           88 END-LOOP-YES                       VALUE 'Y'.
           88 END-LOOP-NO                        VALUE 'N'.
      *
      *  CICS response and reason codes for WRITEQ TD calls
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      *  Record counter (unused in current logic)
         05 WS-REC-COUNT               PIC S9(04) COMP VALUE ZEROS.
      *  Loop index for iterating JCL lines in JOB-DATA
         05 WS-IDX                     PIC S9(04) COMP VALUE ZEROS.
      *  Report type label: 'Monthly', 'Yearly', or 'Custom'
         05 WS-REPORT-NAME             PIC X(10) VALUE SPACES.

      *  Start date in YYYY-MM-DD format, assembled from user
      *  input or auto-calculated for monthly/yearly reports;
      *  injected into JCL PARM fields for the batch job
         05 WS-START-DATE.
            10 WS-START-DATE-YYYY      PIC X(04) VALUE SPACES.
            10 FILLER                  PIC X(01) VALUE '-'.
            10 WS-START-DATE-MM        PIC X(02) VALUE SPACES.
            10 FILLER                  PIC X(01) VALUE '-'.
            10 WS-START-DATE-DD        PIC X(02) VALUE SPACES.
      *  End date in YYYY-MM-DD format, paired with start date
         05 WS-END-DATE.
            10 WS-END-DATE-YYYY        PIC X(04) VALUE SPACES.
            10 FILLER                  PIC X(01) VALUE '-'.
            10 WS-END-DATE-MM          PIC X(02) VALUE SPACES.
            10 FILLER                  PIC X(01) VALUE '-'.
            10 WS-END-DATE-DD          PIC X(02) VALUE SPACES.
      *  Date format string passed to CSUTLDTC for validation
         05 WS-DATE-FORMAT             PIC X(10) VALUE 'YYYY-MM-DD'.
      *
      *  Numeric work fields for NUMVAL-C conversion of
      *  screen input (alphanumeric) to numeric values
         05 WS-NUM-99                  PIC 99   VALUE 0.
         05 WS-NUM-9999                PIC 9999 VALUE 0.
      *
      *  Transaction display formatting (unused in report)
         05 WS-TRAN-AMT                PIC +99999999.99.
         05 WS-TRAN-DATE               PIC X(08) VALUE '00/00/00'.
      *  Buffer holding one 80-byte JCL line to write to TDQ
         05 JCL-RECORD                 PIC X(80) VALUE ' '.

      *----------------------------------------------------------------*
      * JCL deck template for batch transaction report job.
      * This hardcoded skeleton is populated at runtime with
      * user-specified start and end dates, then written line
      * by line to the JOBS extrapartition TDQ for JES pickup.
      * The TRANREPT cataloged procedure invokes CBTRN03C.
      *----------------------------------------------------------------*
       01 JOB-DATA.
        02 JOB-DATA-1.
      *  JOB card: job name TRNRPT00, CLASS=A, MSGCLASS=0
         05 FILLER                     PIC X(80) VALUE
         "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,".
      *  JOB card continuation: notify submitting user
         05 FILLER                     PIC X(80) VALUE
         "// NOTIFY=&SYSUID".
         05 FILLER                     PIC X(80) VALUE
         "//*".
      *  JCLLIB: locate the TRANREPT cataloged procedure
         05 FILLER                     PIC X(80) VALUE
         "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')".
         05 FILLER                     PIC X(80) VALUE
         "//*".
      *  EXEC: invoke the TRANREPT procedure (runs CBTRN03C)
         05 FILLER                     PIC X(80) VALUE
         "//STEP10 EXEC PROC=TRANREPT".
         05 FILLER                     PIC X(80) VALUE
         "//*".
      *  SYMNAMES DD: DFSORT symbolic names for field mapping
      *  in the SORT step within the TRANREPT procedure
         05 FILLER                     PIC X(80) VALUE
         "//STEP05R.SYMNAMES DD *".
      *  Map TRAN-CARD-NUM at offset 263, length 16, zoned dec
         05 FILLER                     PIC X(80) VALUE
         "TRAN-CARD-NUM,263,16,ZD".
      *  Map TRAN-PROC-DT at offset 305, length 10, character
         05 FILLER                     PIC X(80) VALUE
         "TRAN-PROC-DT,305,10,CH".
      *  PARM-START-DATE: runtime-populated start date value
      *  injected into the SORT control card for filtering
         05 FILLER-1.
            10 FILLER                  PIC X(18) VALUE
         "PARM-START-DATE,C'".
            10 PARM-START-DATE-1       PIC X(10) VALUE SPACES.
            10 FILLER                  PIC X(52) VALUE "'".
      *  PARM-END-DATE: runtime-populated end date value
         05 FILLER-2.
            10 FILLER                  PIC X(16) VALUE
         "PARM-END-DATE,C'".
            10 PARM-END-DATE-1         PIC X(10) VALUE SPACES.
            10 FILLER                  PIC X(54) VALUE "'".
      *  End of SYMNAMES in-stream data
         05 FILLER                     PIC X(80) VALUE
         "/*".
      *  DATEPARM DD: alternate date parameter input for
      *  CBTRN03C report program (start + end dates)
         05 FILLER                     PIC X(80) VALUE
         "//STEP10R.DATEPARM DD *".
      *  Start and end dates as space-delimited values
         05 FILLER-3.
            10 PARM-START-DATE-2       PIC X(10) VALUE SPACES.
            10 FILLER                  PIC X VALUE SPACE.
            10 PARM-END-DATE-2         PIC X(10) VALUE SPACES.
            10 FILLER                  PIC X(59) VALUE SPACES.
      *  End of DATEPARM in-stream data
         05 FILLER                     PIC X(80) VALUE
         "/*".
      *  Sentinel: marks the logical end of the JCL deck
         05 FILLER                     PIC X(80) VALUE
         "/*EOF".
      *  REDEFINES as array of 80-byte lines for iteration
      *  during TDQ WRITEQ loop (up to 1000 lines max)
        02 JOB-DATA-2 REDEFINES JOB-DATA-1.
         05 JOB-LINES OCCURS 1000 TIMES PIC X(80).

      *----------------------------------------------------------------*
      * Parameter block for CSUTLDTC date validation subprogram.
      * Passes a date string and format, receives severity code,
      * message number, and text. Severity '0000' means valid.
      * See: app/cbl/CSUTLDTC.cbl for the LE CEEDAYS wrapper.
      *----------------------------------------------------------------*
       01 CSUTLDTC-PARM.
      *  Date value to validate (YYYY-MM-DD format)
          05 CSUTLDTC-DATE                   PIC X(10).
      *  Format descriptor matching the date layout
          05 CSUTLDTC-DATE-FORMAT            PIC X(10).
      *  Result block returned by CSUTLDTC
          05 CSUTLDTC-RESULT.
      *     Severity code: '0000' = valid date
             10 CSUTLDTC-RESULT-SEV-CD       PIC X(04).
             10 FILLER                       PIC X(11).
      *     Message number: '2513' = Gregorian not applicable
             10 CSUTLDTC-RESULT-MSG-NUM      PIC X(04).
      *     Descriptive error message text
             10 CSUTLDTC-RESULT-MSG          PIC X(61).

      * COMMAREA structure for inter-program communication
       COPY COCOM01Y.

      * BMS symbolic map for report submission screen
       COPY CORPT00.

      * Application title and banner text
       COPY COTTL01Y.
      * Date/time working storage fields
       COPY CSDAT01Y.
      * Common user message definitions
       COPY CSMSG01Y.

      * 350-byte transaction record layout (TRAN-RECORD)
       COPY CVTRA05Y.

      * CICS attention identifier constants (ENTER, PF keys)
       COPY DFHAID.
      * BMS attribute constants (colors, highlights)
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      * DFHCOMMAREA receives the COMMAREA passed by CICS on
      * each pseudo-conversational re-entry. EIBCALEN holds
      * the actual length; zero means no COMMAREA (direct
      * start), which triggers redirect to the sign-on screen.
      *----------------------------------------------------------------*
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      *----------------------------------------------------------------*
      * Main entry point for pseudo-conversational processing.
      * On each CICS dispatch under transaction CR00:
      *   1. Resets error and control flags
      *   2. If no COMMAREA (EIBCALEN=0), redirects to sign-on
      *   3. On first entry (PGM-CONTEXT=0), displays the
      *      empty report criteria screen with cursor on the
      *      Monthly selection field
      *   4. On re-entry, receives user input and dispatches
      *      based on AID key:
      *      - ENTER: validates and submits report
      *      - PF3:   returns to main menu (COMEN01C)
      *      - Other: displays invalid key message
      *   5. Returns to CICS with TRANSID CR00 to await the
      *      next terminal interaction (pseudo-conversational)
      *----------------------------------------------------------------*
       MAIN-PARA.
      *    Reset all control flags for this conversation turn
           SET ERR-FLG-OFF TO TRUE
           SET TRANSACT-NOT-EOF TO TRUE
           SET SEND-ERASE-YES TO TRUE
      *    Clear message areas on screen and in working storage
           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF CORPT0AO
      *    No COMMAREA means user arrived without navigation
      *    context; redirect to the sign-on screen COSGN00C
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
               PERFORM RETURN-TO-PREV-SCREEN
           ELSE
      *        Copy COMMAREA into local working storage
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      *        First entry: display empty report criteria form
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO CORPT0AO
      *            Position cursor on the Monthly selection
                   MOVE -1       TO MONTHLYL OF CORPT0AI
                   PERFORM SEND-TRNRPT-SCREEN
               ELSE
      *            Re-entry: receive user input from screen
                   PERFORM RECEIVE-TRNRPT-SCREEN
      *            Dispatch on attention identifier (AID key)
                   EVALUATE EIBAID
                       WHEN DFHENTER
      *                    ENTER pressed: process report request
                           PERFORM PROCESS-ENTER-KEY
                       WHEN DFHPF3
      *                    PF3: return to main menu COMEN01C
                           MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
                           PERFORM RETURN-TO-PREV-SCREEN
                       WHEN OTHER
      *                    Unrecognized key: show error msg
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE -1       TO MONTHLYL OF CORPT0AI
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-TRNRPT-SCREEN
                   END-EVALUATE
               END-IF
           END-IF
      *    Return to CICS and wait for next terminal input
      *    under transaction CR00 (pseudo-conversational wait)
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.


      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Processes the ENTER key. Evaluates which report type the
      * user selected (Monthly, Yearly, or Custom date range),
      * auto-calculates or validates the start/end dates, then
      * proceeds to JCL generation and TDQ submission.
      *
      * For Monthly: auto-calculates the 1st through last day
      *   of the current calendar month.
      * For Yearly:  auto-calculates Jan 1 through Dec 31
      *   of the current calendar year.
      * For Custom:  validates all 6 user-entered date fields
      *   (MM/DD/YYYY for start and end), normalizes via
      *   NUMVAL-C, range-checks components, and calls
      *   CSUTLDTC for full date validity before submission.
      *
      * If no report type is selected, displays an error
      * prompting the user to choose one.
      *----------------------------------------------------------------*
       PROCESS-ENTER-KEY.

           DISPLAY 'PROCESS ENTER KEY'
      *    Evaluate which report type checkbox the user marked
           EVALUATE TRUE
      *        ---- Monthly report: current calendar month ----
               WHEN MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES
                   MOVE 'Monthly'   TO WS-REPORT-NAME
      *            Capture system date for auto-calculation
                   MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      *            Start date = 1st day of current month
                   MOVE WS-CURDATE-YEAR     TO WS-START-DATE-YYYY
                   MOVE WS-CURDATE-MONTH    TO WS-START-DATE-MM
                   MOVE '01'                TO WS-START-DATE-DD
      *            Populate both JCL parameter slots
                   MOVE WS-START-DATE       TO PARM-START-DATE-1
                                               PARM-START-DATE-2
      *            Calculate end date = last day of month by
      *            advancing to 1st of next month then backing
      *            off one day via INTEGER-OF-DATE arithmetic
                   MOVE 1              TO WS-CURDATE-DAY
                   ADD 1               TO WS-CURDATE-MONTH
      *            Handle December -> January year rollover
                   IF WS-CURDATE-MONTH > 12
                       ADD 1           TO WS-CURDATE-YEAR
                       MOVE 1          TO WS-CURDATE-MONTH
                   END-IF
      *            Subtract 1 from integer date of next month
      *            to get the last day of the current month
                   COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(
                           FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)
      *            End date components now hold last day of month
                   MOVE WS-CURDATE-YEAR     TO WS-END-DATE-YYYY
                   MOVE WS-CURDATE-MONTH    TO WS-END-DATE-MM
                   MOVE WS-CURDATE-DAY      TO WS-END-DATE-DD
      *            Populate both JCL end-date parameter slots
                   MOVE WS-END-DATE         TO PARM-END-DATE-1
                                               PARM-END-DATE-2
      *            Proceed to JCL submission and TDQ write
                   PERFORM SUBMIT-JOB-TO-INTRDR
      *        ---- Yearly report: current calendar year ------
               WHEN YEARLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES
                   MOVE 'Yearly'   TO WS-REPORT-NAME
                   MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      *            Start date = Jan 1 of current year
                   MOVE WS-CURDATE-YEAR     TO WS-START-DATE-YYYY
                                               WS-END-DATE-YYYY
                   MOVE '01'                TO WS-START-DATE-MM
                                               WS-START-DATE-DD
                   MOVE WS-START-DATE       TO PARM-START-DATE-1
                                               PARM-START-DATE-2
      *            End date = Dec 31 of current year
                   MOVE '12'                TO WS-END-DATE-MM
                   MOVE '31'                TO WS-END-DATE-DD
                   MOVE WS-END-DATE         TO PARM-END-DATE-1
                                               PARM-END-DATE-2
      *            Proceed to JCL submission and TDQ write
                   PERFORM SUBMIT-JOB-TO-INTRDR
      *        ---- Custom report: user-specified date range --
               WHEN CUSTOMI OF CORPT0AI NOT = SPACES AND LOW-VALUES
      *            Phase 1: Emptiness check — ensure all six
      *            date component fields (start MM/DD/YYYY and
      *            end MM/DD/YYYY) contain data. The EVALUATE
      *            catches the first empty field and positions
      *            cursor on it for correction.
                   EVALUATE TRUE
                       WHEN SDTMMI OF CORPT0AI = SPACES OR
                                                   LOW-VALUES
                           MOVE 'Start Date - Month can NOT be empty...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO SDTMML OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       WHEN SDTDDI OF CORPT0AI = SPACES OR
                                                   LOW-VALUES
                           MOVE 'Start Date - Day can NOT be empty...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO SDTDDL OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       WHEN SDTYYYYI OF CORPT0AI = SPACES OR
                                                   LOW-VALUES
                           MOVE 'Start Date - Year can NOT be empty...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO SDTYYYYL OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       WHEN EDTMMI OF CORPT0AI = SPACES OR
                                                   LOW-VALUES
                           MOVE 'End Date - Month can NOT be empty...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO EDTMML OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       WHEN EDTDDI OF CORPT0AI = SPACES OR
                                                   LOW-VALUES
                           MOVE 'End Date - Day can NOT be empty...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO EDTDDL OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       WHEN EDTYYYYI OF CORPT0AI = SPACES OR
                                                   LOW-VALUES
                           MOVE 'End Date - Year can NOT be empty...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO EDTYYYYL OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       WHEN OTHER
                           CONTINUE
                   END-EVALUATE

      *            Phase 2: NUMVAL-C normalization — convert
      *            the alphanumeric screen input values into
      *            numeric form so that range checks can use
      *            standard numeric comparisons. NUMVAL-C
      *            strips leading/trailing spaces and signs.
                   COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C
                                         (SDTMMI OF CORPT0AI)
                   MOVE WS-NUM-99      TO SDTMMI OF CORPT0AI

                   COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C
                                         (SDTDDI OF CORPT0AI)
                   MOVE WS-NUM-99      TO SDTDDI OF CORPT0AI

                   COMPUTE WS-NUM-9999 = FUNCTION NUMVAL-C
                                           (SDTYYYYI OF CORPT0AI)
                   MOVE WS-NUM-9999      TO SDTYYYYI OF CORPT0AI

                   COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C
                                         (EDTMMI OF CORPT0AI)
                   MOVE WS-NUM-99      TO EDTMMI OF CORPT0AI

                   COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C
                                         (EDTDDI OF CORPT0AI)
                   MOVE WS-NUM-99      TO EDTDDI OF CORPT0AI

                   COMPUTE WS-NUM-9999 = FUNCTION NUMVAL-C
                                           (EDTYYYYI OF CORPT0AI)
                   MOVE WS-NUM-9999      TO EDTYYYYI OF CORPT0AI
      *
      *            Phase 3: Range validation — check each date
      *            component against valid ranges. Month must be
      *            01-12, day must be 01-31, year must be
      *            numeric. Cursor positions on the first invalid
      *            field found. Note: does not stop on first
      *            error; continues checking all fields.
                   IF SDTMMI OF CORPT0AI IS NOT NUMERIC OR
                      SDTMMI OF CORPT0AI > '12'
                       MOVE 'Start Date - Not a valid Month...'
                         TO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO SDTMML OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
                   END-IF

                   IF SDTDDI OF CORPT0AI IS NOT NUMERIC OR
                      SDTDDI OF CORPT0AI > '31'
                       MOVE 'Start Date - Not a valid Day...'
                         TO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO SDTDDL OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
                   END-IF

                   IF SDTYYYYI OF CORPT0AI IS NOT NUMERIC
                       MOVE 'Start Date - Not a valid Year...'
                         TO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO SDTYYYYL OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
                   END-IF

                   IF EDTMMI OF CORPT0AI IS NOT NUMERIC OR
                      EDTMMI OF CORPT0AI > '12'
                       MOVE 'End Date - Not a valid Month...'
                         TO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO EDTMML OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
                   END-IF

                   IF EDTDDI OF CORPT0AI IS NOT NUMERIC OR
                      EDTDDI OF CORPT0AI > '31'
                       MOVE 'End Date - Not a valid Day...'
                         TO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO EDTDDL OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
                   END-IF

                   IF EDTYYYYI OF CORPT0AI IS NOT NUMERIC
                       MOVE 'End Date - Not a valid Year...'
                         TO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO EDTYYYYL OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
                   END-IF

      *            Assemble YYYY-MM-DD date strings from the
      *            validated screen input components
                   MOVE SDTYYYYI OF CORPT0AI TO WS-START-DATE-YYYY
                   MOVE SDTMMI   OF CORPT0AI TO WS-START-DATE-MM
                   MOVE SDTDDI   OF CORPT0AI TO WS-START-DATE-DD
                   MOVE EDTYYYYI OF CORPT0AI TO WS-END-DATE-YYYY
                   MOVE EDTMMI   OF CORPT0AI TO WS-END-DATE-MM
                   MOVE EDTDDI   OF CORPT0AI TO WS-END-DATE-DD
      *
      *            Phase 4: Full date validity via CSUTLDTC —
      *            calls the shared date validation subprogram
      *            which wraps the LE CEEDAYS service to verify
      *            the date is a real calendar date (e.g. rejects
      *            Feb 30). Severity '0000' = valid. Message
      *            number '2513' is tolerated (Gregorian caveat).
                   MOVE WS-START-DATE        TO CSUTLDTC-DATE
                   MOVE WS-DATE-FORMAT       TO CSUTLDTC-DATE-FORMAT
                   MOVE SPACES               TO CSUTLDTC-RESULT
      *            CALL to CSUTLDTC for start date validation
                   CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                                           CSUTLDTC-DATE-FORMAT
                                           CSUTLDTC-RESULT
      *            Evaluate start date validation result
                   IF CSUTLDTC-RESULT-SEV-CD = '0000'
                       CONTINUE
                   ELSE
                       IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
                           MOVE 'Start Date - Not a valid date...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO SDTMML OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       END-IF
                   END-IF
      *            Repeat validation for end date
                   MOVE WS-END-DATE          TO CSUTLDTC-DATE
                   MOVE WS-DATE-FORMAT       TO CSUTLDTC-DATE-FORMAT
                   MOVE SPACES               TO CSUTLDTC-RESULT
      *            CALL to CSUTLDTC for end date validation
                   CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                                           CSUTLDTC-DATE-FORMAT
                                           CSUTLDTC-RESULT
      *            Evaluate end date validation result
                   IF CSUTLDTC-RESULT-SEV-CD = '0000'
                       CONTINUE
                   ELSE
                       IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
                           MOVE 'End Date - Not a valid date...'
                             TO WS-MESSAGE
                           MOVE 'Y'     TO WS-ERR-FLG
                           MOVE -1       TO EDTMML OF CORPT0AI
                           PERFORM SEND-TRNRPT-SCREEN
                       END-IF
                   END-IF

      *            Inject validated dates into JCL parameter
      *            slots in the JOB-DATA skeleton
                   MOVE WS-START-DATE       TO PARM-START-DATE-1
                                               PARM-START-DATE-2
                   MOVE WS-END-DATE         TO PARM-END-DATE-1
                                               PARM-END-DATE-2
                   MOVE 'Custom'   TO WS-REPORT-NAME
      *            Submit only if no validation errors occurred
                   IF NOT ERR-FLG-ON
                       PERFORM SUBMIT-JOB-TO-INTRDR
                   END-IF
      *        ---- No report type selected -------------------
               WHEN OTHER
                   MOVE 'Select a report type to print report...' TO
                                   WS-MESSAGE
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE -1       TO MONTHLYL OF CORPT0AI
                   PERFORM SEND-TRNRPT-SCREEN
           END-EVALUATE
      *
      *    Success confirmation: if the report was submitted
      *    without error, clear all input fields and display a
      *    green confirmation message indicating the report name
      *    (Monthly, Yearly, or Custom) followed by success text.
           IF NOT ERR-FLG-ON

               PERFORM INITIALIZE-ALL-FIELDS
      *        Set message color to green for success feedback
               MOVE DFHGREEN           TO ERRMSGC  OF CORPT0AO
      *        Build confirmation: "<ReportName> report
      *        submitted for printing ..."
               STRING WS-REPORT-NAME   DELIMITED BY SPACE
                 ' report submitted for printing ...'
                                       DELIMITED BY SIZE
                 INTO WS-MESSAGE
               MOVE -1       TO MONTHLYL OF CORPT0AI
               PERFORM SEND-TRNRPT-SCREEN

           END-IF.


      *----------------------------------------------------------------*
      *                      SUBMIT-JOB-TO-INTRDR
      *----------------------------------------------------------------*
      * Handles job submission to the internal reader (JES) via
      * the JOBS extrapartition Transient Data Queue (TDQ).
      *
      * Flow:
      *   1. If CONFIRM field is empty, prompts user with
      *      "Please confirm to print the <type> report..."
      *   2. Evaluates confirmation input (Y/y to proceed,
      *      N/n to cancel, other = invalid)
      *   3. On confirmation, iterates JOB-DATA lines (up to
      *      1000 x 80-byte records) writing each to the JOBS
      *      TDQ until the /*EOF sentinel or blank line
      *   4. The JOBS TDQ is defined as an extrapartition
      *      queue mapped to a SPOOL dataset; JES picks up
      *      the JCL deck for batch execution automatically
      *----------------------------------------------------------------*
       SUBMIT-JOB-TO-INTRDR.
      *    Prompt for confirmation if user has not yet entered
      *    a Y/N value in the CONFIRM field on screen
           IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES
               STRING
                 'Please confirm to print the '
                                   DELIMITED BY SIZE
                 WS-REPORT-NAME    DELIMITED BY SPACE
                 ' report...'      DELIMITED BY SIZE
                 INTO WS-MESSAGE
               MOVE 'Y'     TO WS-ERR-FLG
      *        Position cursor on the CONFIRM field
               MOVE -1       TO CONFIRML OF CORPT0AI
               PERFORM SEND-TRNRPT-SCREEN
           END-IF
      *    Evaluate the confirmation response
           IF NOT ERR-FLG-ON
               EVALUATE TRUE
                   WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'
      *                User confirmed: proceed with submission
                       CONTINUE
                   WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'
      *                User declined: clear fields and redisplay
                       PERFORM INITIALIZE-ALL-FIELDS
                       MOVE 'Y'     TO WS-ERR-FLG
                       PERFORM SEND-TRNRPT-SCREEN
                   WHEN OTHER
      *                Invalid confirmation value: show error
                       STRING
                         '"'               DELIMITED BY SIZE
                         CONFIRMI OF CORPT0AI    DELIMITED BY SPACE
                         '" is not a valid value to confirm...'
                                           DELIMITED BY SIZE
                         INTO WS-MESSAGE
                       MOVE 'Y'     TO WS-ERR-FLG
                       MOVE -1       TO CONFIRML OF CORPT0AI
                       PERFORM SEND-TRNRPT-SCREEN
               END-EVALUATE
      *
      *        Write JCL deck to TDQ: iterate the JOB-DATA
      *        array (REDEFINES of JOB-DATA-1 as 80-byte
      *        lines). Each line is written via WRITEQ TD to
      *        the JOBS extrapartition TDQ until the /*EOF
      *        sentinel or a blank line terminates the loop.
               SET END-LOOP-NO TO TRUE

               PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 1000 OR
                                      END-LOOP-YES  OR ERR-FLG-ON
      *            Copy current JCL line to the write buffer
                   MOVE JOB-LINES(WS-IDX) TO JCL-RECORD
      *            Detect end-of-deck sentinel or empty line
                   IF JCL-RECORD = '/*EOF' OR
                      JCL-RECORD = SPACES OR LOW-VALUES
                       SET END-LOOP-YES TO TRUE
                   END-IF
      *            Write current line to the JOBS TDQ
                   PERFORM WIRTE-JOBSUB-TDQ
               END-PERFORM

           END-IF.

      *----------------------------------------------------------------*
      *                      WIRTE-JOBSUB-TDQ
      *----------------------------------------------------------------*
      * Writes one 80-byte JCL record to the JOBS Transient
      * Data Queue (TDQ) via EXEC CICS WRITEQ TD.
      *
      * The JOBS queue is an extrapartition TDQ defined in the
      * CICS CSD (or via DFHDCT). It maps to an output SPOOL
      * dataset. When the final record (/*EOF) is written, JES
      * automatically picks up the accumulated JCL deck and
      * submits it as a batch job for execution.
      *
      * RESP/RESP2 are captured for error handling. A NORMAL
      * response allows the loop to continue; any other code
      * triggers an error message and halts submission.
      *
      * Note: the paragraph name retains the original typo
      * ("WIRTE" instead of "WRITE") for compatibility.
      *----------------------------------------------------------------*
       WIRTE-JOBSUB-TDQ.
      *    EXEC CICS WRITEQ TD writes JCL-RECORD (80 bytes)
      *    to the JOBS extrapartition TDQ for JES submission
           EXEC CICS WRITEQ TD
             QUEUE ('JOBS')
             FROM (JCL-RECORD)
             LENGTH (LENGTH OF JCL-RECORD)
             RESP(WS-RESP-CD)
             RESP2(WS-REAS-CD)
           END-EXEC.
      *    Evaluate CICS response code from the WRITEQ TD call
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      *            Write succeeded: continue to next JCL line
                   CONTINUE
               WHEN OTHER
      *            TDQ write failure: log RESP/REAS codes to
      *            SYSOUT and display error message to user
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to Write TDQ (JOBS)...' TO
                                   WS-MESSAGE
                   MOVE -1       TO MONTHLYL OF CORPT0AI
                   PERFORM SEND-TRNRPT-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      RETURN-TO-PREV-SCREEN
      *----------------------------------------------------------------*
      * Transfers control to the target program via EXEC CICS
      * XCTL, preserving navigation context in the COMMAREA.
      * Defaults to sign-on screen COSGN00C if no target is set.
      * Records this program as the source for back-navigation.
      * Resets PGM-CONTEXT to 0 so the target program treats
      * the entry as a fresh first-time display.
      *----------------------------------------------------------------*
       RETURN-TO-PREV-SCREEN.
      *    Default to sign-on if no target program is specified
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      *    Record navigation source for back-trail in COMMAREA
           MOVE WS-TRANID    TO CDEMO-FROM-TRANID
           MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
      *    Reset context so target displays its initial screen
           MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      *    Transfer control — does not return to this program
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
               COMMAREA(CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-TRNRPT-SCREEN
      *----------------------------------------------------------------*
      * Sends BMS map CORPT0A (from mapset CORPT00) to the
      * 3270 terminal. Populates header fields first, then
      * copies the current message to the screen error area.
      *
      * Uses ERASE on first display (SEND-ERASE-YES) to clear
      * the entire screen before painting; on subsequent sends
      * within the same conversation, omits ERASE to preserve
      * existing field data. CURSOR option positions the cursor
      * at the field whose length was set to -1 (a BMS
      * convention that tells CICS where to place the cursor).
      *
      * After sending, unconditionally jumps to RETURN-TO-CICS
      * to hand control back to CICS for the next interaction.
      *----------------------------------------------------------------*
       SEND-TRNRPT-SCREEN.
      *    Populate title, date, and time in the header area
           PERFORM POPULATE-HEADER-INFO
      *    Copy the message buffer to the screen error field
           MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO
      *    First display: ERASE clears screen before painting
           IF SEND-ERASE-YES
               EXEC CICS SEND
                         MAP('CORPT0A')
                         MAPSET('CORPT00')
                         FROM(CORPT0AO)
                         ERASE
                         CURSOR
               END-EXEC
           ELSE
      *        Subsequent sends: no ERASE to retain field data
               EXEC CICS SEND
                         MAP('CORPT0A')
                         MAPSET('CORPT00')
                         FROM(CORPT0AO)
      *                  ERASE
                         CURSOR
               END-EXEC
           END-IF.
      *    Jump to RETURN-TO-CICS for pseudo-conversational wait
           GO TO RETURN-TO-CICS.

      *----------------------------------------------------------------*
      *                         RETURN-TO-CICS
      *----------------------------------------------------------------*
      * Returns control to CICS and enters pseudo-conversational
      * wait. TRANSID CR00 tells CICS to re-invoke CORPT00C
      * when the user presses a key. COMMAREA preserves session
      * state (PGM-CONTEXT, user ID, navigation history) across
      * the wait. The commented LENGTH clause is not required
      * because CICS infers it from the COMMAREA data item.
      *----------------------------------------------------------------*
       RETURN-TO-CICS.
      *    Return to CICS and wait for next user input
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
      *              LENGTH(LENGTH OF CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-TRNRPT-SCREEN
      *----------------------------------------------------------------*
      * Receives user input from BMS map CORPT0A (mapset
      * CORPT00) into the symbolic input area CORPT0AI.
      * This reads the terminal data stream and populates the
      * CORPT0AI copybook fields (MONTHLYI, YEARLYI, CUSTOMI,
      * SDTMMI/SDTDDI/SDTYYYYI, EDTMMI/EDTDDI/EDTYYYYI,
      * CONFIRMI). RESP/RESP2 capture any CICS errors.
      * See: app/cpy-bms/CORPT00.CPY for symbolic map layout.
      *----------------------------------------------------------------*
       RECEIVE-TRNRPT-SCREEN.
      *    RECEIVE MAP reads terminal input into symbolic area
           EXEC CICS RECEIVE
                     MAP('CORPT0A')
                     MAPSET('CORPT00')
                     INTO(CORPT0AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Populates the standard CardDemo screen header fields:
      *   - Application titles (from COTTL01Y copybook)
      *   - Transaction ID (CR00) and program name (CORPT00C)
      *   - Current date in MM/DD/YY format
      *   - Current time in HH:MM:SS format
      * Uses FUNCTION CURRENT-DATE intrinsic to capture system
      * timestamp, then reformats into display-ready fields
      * defined in the CSDAT01Y date/time working storage.
      *----------------------------------------------------------------*
       POPULATE-HEADER-INFO.
      *    Capture current system date and time
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      *    Set application banner titles from COTTL01Y copybook
           MOVE CCDA-TITLE01           TO TITLE01O OF CORPT0AO
           MOVE CCDA-TITLE02           TO TITLE02O OF CORPT0AO
      *    Display transaction ID and program name in header
           MOVE WS-TRANID              TO TRNNAMEO OF CORPT0AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF CORPT0AO
      *    Format date as MM/DD/YY for header display
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF CORPT0AO
      *    Format time as HH:MM:SS for header display
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF CORPT0AO.

      *----------------------------------------------------------------*
      *                      INITIALIZE-ALL-FIELDS
      *----------------------------------------------------------------*
      * Resets all user-editable screen fields and the message
      * buffer to spaces. Positions cursor on the Monthly
      * selection field (MONTHLYL = -1) so the user starts
      * from a clean state. Called after successful job
      * submission and when the user declines confirmation.
      *----------------------------------------------------------------*
       INITIALIZE-ALL-FIELDS.
      *    Position cursor on Monthly selection field
           MOVE -1              TO MONTHLYL OF CORPT0AI
      *    Clear all report type selections, date components,
      *    confirmation input, and the message work area
           INITIALIZE              MONTHLYI OF CORPT0AI
                                   YEARLYI  OF CORPT0AI
                                   CUSTOMI  OF CORPT0AI
                                   SDTMMI   OF CORPT0AI
                                   SDTDDI   OF CORPT0AI
                                   SDTYYYYI OF CORPT0AI
                                   EDTMMI   OF CORPT0AI
                                   EDTDDI   OF CORPT0AI
                                   EDTYYYYI OF CORPT0AI
                                   CONFIRMI OF CORPT0AI
                                   WS-MESSAGE.
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:33 CDT
      *
