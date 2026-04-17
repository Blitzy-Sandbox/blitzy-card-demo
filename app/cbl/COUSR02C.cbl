      ******************************************************************        
      * Program     : COUSR02C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Update a user in USRSEC file
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
      * CICS online program: Update existing user (admin function)
      *================================================================*
      * Program:     COUSR02C
      * Transaction: CU02
      * BMS Map:     COUSR02 / COUSR2A
      * Function:    Two-phase operation:
      *              (1) Fetch user by ID from USRSEC VSAM KSDS
      *              (2) Edit fields (name, password, type) and submit
      *              Compares each field to detect changes and REWRITEs
      *              only when at least one field was modified.
      * Pattern:     Uses READ UPDATE + REWRITE on USRSEC VSAM KSDS
      * Files:       USRSEC (READ UPDATE, REWRITE)
      * Navigation:  ENTER fetches user record for editing.
      *              PF3 saves changes and returns to caller.
      *              PF4 clears screen. PF5 saves changes.
      *              PF12 returns to admin menu without saving.
      * Copybooks:   COCOM01Y, CSUSR01Y, COTTL01Y, CSDAT01Y,
      *              CSMSG01Y
      * See also:    app/cpy/COCOM01Y.cpy (COMMAREA layout)
      *              app/cpy/CSUSR01Y.cpy (user record layout)
      *              app/bms/COUSR02.bms  (BMS map definition)
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COUSR02C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

      * Working storage fields for program control and I/O
       01 WS-VARIABLES.
      *    Program name and transaction ID for pseudo-conversational
      *    RETURN TRANSID and XCTL breadcrumb tracking
         05 WS-PGMNAME                 PIC X(08) VALUE 'COUSR02C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CU02'.
      *    General-purpose message buffer sent to ERRMSGO on screen
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      *    VSAM USRSEC file name constant for EXEC CICS file I/O
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
      *    Error flag: set to 'Y' when validation or I/O fails;
      *    gates subsequent processing within a paragraph
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      *    CICS RESP and RESP2 codes captured from every EXEC CICS
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      *    Modification tracker: set to 'Y' when any screen field
      *    differs from the current USRSEC record value
         05 WS-USR-MODIFIED            PIC X(01) VALUE 'N'.
           88 USR-MODIFIED-YES                   VALUE 'Y'.
           88 USR-MODIFIED-NO                    VALUE 'N'.

      * COMMAREA structure for inter-program communication.
      * Provides routing fields (FROM/TO program/tranid), user
      * identity, and program context flag for pseudo-conversational
      * re-entry detection. See app/cpy/COCOM01Y.cpy
       COPY COCOM01Y.
      *    CU02-specific COMMAREA extension: paging state and the
      *    user ID pre-selected from the user list screen (COUSR00C)
          05 CDEMO-CU02-INFO.
             10 CDEMO-CU02-USRID-FIRST     PIC X(08).
             10 CDEMO-CU02-USRID-LAST      PIC X(08).
             10 CDEMO-CU02-PAGE-NUM        PIC 9(08).
             10 CDEMO-CU02-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
                88 NEXT-PAGE-YES                     VALUE 'Y'.
                88 NEXT-PAGE-NO                      VALUE 'N'.
             10 CDEMO-CU02-USR-SEL-FLG     PIC X(01).
             10 CDEMO-CU02-USR-SELECTED    PIC X(08).

      * BMS symbolic map for user update screen (COUSR2A)
       COPY COUSR02.

      * Application title and banner text
       COPY COTTL01Y.
      * Date/time working storage fields
       COPY CSDAT01Y.
      * Common user message definitions
       COPY CSMSG01Y.
      * User security record layout (80-byte USRSEC VSAM KSDS).
      * Key: SEC-USR-ID (8 bytes). See app/cpy/CSUSR01Y.cpy
       COPY CSUSR01Y.

      * CICS attention identifier constants (ENTER, PF keys)
       COPY DFHAID.
      * BMS attribute constants (colors, highlights)
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
      * DFHCOMMAREA is the CICS-provided communication area.
      * EIBCALEN holds its length; zero on the very first invocation
      * (no COMMAREA yet), nonzero on pseudo-conversational re-entry.
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * Main entry point — pseudo-conversational controller.
      * Checks EIBCALEN to detect first invocation vs re-entry.
      * First entry: initializes screen; if a user ID was passed
      * via COMMAREA from the user list, auto-fetches that record.
      * Re-entry: receives screen input and dispatches on AID key:
      *   ENTER  = fetch/lookup user by ID
      *   PF3    = save changes and return to calling program
      *   PF4    = clear all screen fields
      *   PF5    = save changes (stay on screen)
      *   PF12   = return to admin menu (COADM01C) without saving
      *   OTHER  = display invalid-key error message
       MAIN-PARA.
      *    Reset error and modification flags at start of each pass
           SET ERR-FLG-OFF     TO TRUE
           SET USR-MODIFIED-NO TO TRUE

           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COUSR2AO
      *    Pseudo-conversational check: EIBCALEN = 0 means no
      *    COMMAREA exists — redirect to sign-on (COSGN00C)
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
               PERFORM RETURN-TO-PREV-SCREEN
           ELSE
      *        Restore the saved COMMAREA from previous pass
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      *        First entry: initialize output map, set cursor to
      *        user-ID field, and auto-fetch if a user was pre-
      *        selected from the list screen (COUSR00C)
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO COUSR2AO
                   MOVE -1       TO USRIDINL OF COUSR2AI
                   IF CDEMO-CU02-USR-SELECTED NOT =
                                              SPACES AND LOW-VALUES
                       MOVE CDEMO-CU02-USR-SELECTED TO
                            USRIDINI OF COUSR2AI
                       PERFORM PROCESS-ENTER-KEY
                   END-IF
                   PERFORM SEND-USRUPD-SCREEN
               ELSE
      *            Re-entry: collect terminal input then dispatch
      *            based on the attention identifier key pressed
                   PERFORM RECEIVE-USRUPD-SCREEN
                   EVALUATE EIBAID
      *                ENTER: fetch user record for editing
                       WHEN DFHENTER
                           PERFORM PROCESS-ENTER-KEY
      *                PF3: save changes then navigate back
                       WHEN DFHPF3
                           PERFORM UPDATE-USER-INFO
                           IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
                               MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                           ELSE
                               MOVE CDEMO-FROM-PROGRAM TO
                               CDEMO-TO-PROGRAM
                           END-IF
                           PERFORM RETURN-TO-PREV-SCREEN
      *                PF4: clear all fields for a fresh entry
                       WHEN DFHPF4
                           PERFORM CLEAR-CURRENT-SCREEN
      *                PF5: save changes but stay on screen
                       WHEN DFHPF5
                           PERFORM UPDATE-USER-INFO
      *                PF12: return to admin menu without saving
                       WHEN DFHPF12
                           MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                           PERFORM RETURN-TO-PREV-SCREEN
      *                Any other key: show invalid-key message
                       WHEN OTHER
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-USRUPD-SCREEN
                   END-EVALUATE
               END-IF
           END-IF

      *    Return control to CICS with pseudo-conversational wait.
      *    TRANSID('CU02') tells CICS to re-invoke this program
      *    when the user next presses an AID key on the terminal.
      *    COMMAREA preserves state across the conversational gap.
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Phase 1 — Fetch: validate user ID is non-empty, then read
      * the USRSEC record with UPDATE intent. On success, populate
      * screen fields with current values for editing.
       PROCESS-ENTER-KEY.
      *    Validate that the user ID input field is not blank
           EVALUATE TRUE
               WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN OTHER
                   MOVE -1       TO USRIDINL OF COUSR2AI
                   CONTINUE
           END-EVALUATE
      *    Clear editable fields before populating from the record,
      *    then issue READ UPDATE on USRSEC keyed by user ID
           IF NOT ERR-FLG-ON
               MOVE SPACES      TO FNAMEI   OF COUSR2AI
                                   LNAMEI   OF COUSR2AI
                                   PASSWDI  OF COUSR2AI
                                   USRTYPEI OF COUSR2AI
               MOVE USRIDINI  OF COUSR2AI TO SEC-USR-ID
               PERFORM READ-USER-SEC-FILE
           END-IF.
      *    On successful read, copy record fields to screen input
      *    fields so the user sees current values for editing
           IF NOT ERR-FLG-ON
               MOVE SEC-USR-FNAME      TO FNAMEI    OF COUSR2AI
               MOVE SEC-USR-LNAME      TO LNAMEI    OF COUSR2AI
               MOVE SEC-USR-PWD        TO PASSWDI   OF COUSR2AI
               MOVE SEC-USR-TYPE       TO USRTYPEI  OF COUSR2AI
               PERFORM SEND-USRUPD-SCREEN
           END-IF.

      *----------------------------------------------------------------*
      *                      UPDATE-USER-INFO
      *----------------------------------------------------------------*
      * Phase 2 — Submit: validate all required fields are non-
      * empty, re-read the record with UPDATE lock, compare each
      * screen field to the stored value. If any field changed,
      * set USR-MODIFIED-YES and REWRITE the record. If nothing
      * changed, display an informational message in red.
       UPDATE-USER-INFO.
      *    Required-field validation cascade: user ID, first name,
      *    last name, password, and user type must all be non-empty
           EVALUATE TRUE
               WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN FNAMEI OF COUSR2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'First Name can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO FNAMEL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN LNAMEI OF COUSR2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Last Name can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO LNAMEL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN PASSWDI OF COUSR2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Password can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO PASSWDL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN USRTYPEI OF COUSR2AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User Type can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRTYPEL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN OTHER
                   MOVE -1       TO FNAMEL OF COUSR2AI
                   CONTINUE
           END-EVALUATE

      *    All fields valid — re-read record with UPDATE lock,
      *    then compare each screen field against stored value
           IF NOT ERR-FLG-ON
               MOVE USRIDINI  OF COUSR2AI TO SEC-USR-ID
               PERFORM READ-USER-SEC-FILE
      *        Field-by-field change detection: compare screen
      *        input (xxxI OF COUSR2AI) to record field (SEC-USR-
      *        xxx). Move changed value into record and flag it.
               IF FNAMEI  OF COUSR2AI NOT = SEC-USR-FNAME
                   MOVE FNAMEI   OF COUSR2AI TO SEC-USR-FNAME
                   SET USR-MODIFIED-YES TO TRUE
               END-IF
               IF LNAMEI  OF COUSR2AI NOT = SEC-USR-LNAME
                   MOVE LNAMEI   OF COUSR2AI TO SEC-USR-LNAME
                   SET USR-MODIFIED-YES TO TRUE
               END-IF
               IF PASSWDI  OF COUSR2AI NOT = SEC-USR-PWD
                   MOVE PASSWDI  OF COUSR2AI TO SEC-USR-PWD
                   SET USR-MODIFIED-YES TO TRUE
               END-IF
               IF USRTYPEI  OF COUSR2AI NOT = SEC-USR-TYPE
                   MOVE USRTYPEI OF COUSR2AI TO SEC-USR-TYPE
                   SET USR-MODIFIED-YES TO TRUE
               END-IF
      *        If at least one field changed, REWRITE the record;
      *        otherwise show a red message asking user to modify
               IF USR-MODIFIED-YES
                   PERFORM UPDATE-USER-SEC-FILE
               ELSE
                   MOVE 'Please modify to update ...' TO
                                   WS-MESSAGE
                   MOVE DFHRED       TO ERRMSGC  OF COUSR2AO
                   PERFORM SEND-USRUPD-SCREEN
               END-IF

           END-IF.

      *----------------------------------------------------------------*
      *                      RETURN-TO-PREV-SCREEN
      *----------------------------------------------------------------*
      * Transfer control to the target program via EXEC CICS XCTL,
      * passing the COMMAREA. Defaults to sign-on if no target set.
      * Stamps this program's name and transaction as the breadcrumb
      * so the target knows who called it.
       RETURN-TO-PREV-SCREEN.
      *    Default to sign-on screen if no target program was set
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      *    Set breadcrumb fields so the next program knows where
      *    this navigation came from; reset context to first-entry
           MOVE WS-TRANID    TO CDEMO-FROM-TRANID
           MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
           MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      *    XCTL transfers control — this program does not resume
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
               COMMAREA(CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-USRUPD-SCREEN
      *----------------------------------------------------------------*
      * Populate header fields and send BMS map COUSR2A to the
      * 3270 terminal. ERASE clears the screen before painting;
      * CURSOR positions to the field whose length was set to -1.
       SEND-USRUPD-SCREEN.

           PERFORM POPULATE-HEADER-INFO
      *    Copy the current message (error or informational) to
      *    the screen error-message output field
           MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO
      *    Send the assembled output map to the terminal
           EXEC CICS SEND
                     MAP('COUSR2A')
                     MAPSET('COUSR02')
                     FROM(COUSR2AO)
                     ERASE
                     CURSOR
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-USRUPD-SCREEN
      *----------------------------------------------------------------*
      * Receive user input from BMS map COUSR2A into the symbolic
      * input area COUSR2AI. RESP/RESP2 capture any receive errors.
       RECEIVE-USRUPD-SCREEN.
      *    Read terminal input into the symbolic map input buffer
           EXEC CICS RECEIVE
                     MAP('COUSR2A')
                     MAPSET('COUSR02')
                     INTO(COUSR2AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Fill screen header fields from COTTL01Y banner text and
      * CSDAT01Y date/time work areas. Reformats the intrinsic
      * CURRENT-DATE (YYYYMMDD) into MM/DD/YY and HH:MM:SS.
       POPULATE-HEADER-INFO.
      *    Capture system date and time into work area
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      *    Set application title lines from COTTL01Y constants
           MOVE CCDA-TITLE01           TO TITLE01O OF COUSR2AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COUSR2AO
      *    Display the current transaction ID and program name
           MOVE WS-TRANID              TO TRNNAMEO OF COUSR2AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COUSR2AO
      *    Reformat date from YYYYMMDD to MM/DD/YY for display
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COUSR2AO
      *    Reformat time from HHMMSSCC to HH:MM:SS for display
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COUSR2AO.

      *----------------------------------------------------------------*
      *                      READ-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Read user record from USRSEC VSAM KSDS with UPDATE intent.
      * The UPDATE option acquires an exclusive lock on the record
      * so it can be REWRITEn later without a second lookup.
      * RESP handling: NORMAL prompts user to press PF5,
      * NOTFND sets error flag, OTHER logs and reports failure.
       READ-USER-SEC-FILE.
      *    EXEC CICS READ with UPDATE acquires a record-level lock
      *    on the USRSEC VSAM KSDS keyed by SEC-USR-ID (8 bytes)
           EXEC CICS READ
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (SEC-USR-ID)
                KEYLENGTH (LENGTH OF SEC-USR-ID)
                UPDATE
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.
      *    Evaluate CICS RESP code to determine outcome
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      *            Record found and locked — tell user to press PF5
                   CONTINUE
                   MOVE 'Press PF5 key to save your updates ...' TO
                                   WS-MESSAGE
                   MOVE DFHNEUTR       TO ERRMSGC  OF COUSR2AO
                   PERFORM SEND-USRUPD-SCREEN
               WHEN DFHRESP(NOTFND)
      *            No record matches the supplied user ID
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN OTHER
      *            Unexpected error — log RESP/REAS to SYSOUT
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO FNAMEL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      UPDATE-USER-SEC-FILE
      *----------------------------------------------------------------*
      * REWRITE the modified USRSEC record. The prior READ UPDATE
      * already holds the record lock, so REWRITE completes the
      * update cycle. RESP handling: NORMAL builds a green success
      * message, NOTFND flags error, OTHER logs and reports.
       UPDATE-USER-SEC-FILE.
      *    Write modified SEC-USER-DATA back to USRSEC VSAM KSDS
           EXEC CICS REWRITE
                DATASET   (WS-USRSEC-FILE)
                FROM      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.
      *    Evaluate CICS RESP code after REWRITE
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      *            Success — build green confirmation message
                   MOVE SPACES             TO WS-MESSAGE
                   MOVE DFHGREEN           TO ERRMSGC  OF COUSR2AO
                   STRING 'User '     DELIMITED BY SIZE
                          SEC-USR-ID  DELIMITED BY SPACE
                          ' has been updated ...' DELIMITED BY SIZE
                     INTO WS-MESSAGE
                   PERFORM SEND-USRUPD-SCREEN
               WHEN DFHRESP(NOTFND)
      *            Record not found (should not occur after READ)
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
               WHEN OTHER
      *            Unexpected error — log RESP/REAS to SYSOUT
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to Update User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO FNAMEL OF COUSR2AI
                   PERFORM SEND-USRUPD-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      CLEAR-CURRENT-SCREEN
      *----------------------------------------------------------------*
      * Reset all screen fields and re-send the blank form.
      * Invoked by PF4 to give the user a clean slate.
       CLEAR-CURRENT-SCREEN.

           PERFORM INITIALIZE-ALL-FIELDS.
           PERFORM SEND-USRUPD-SCREEN.

      *----------------------------------------------------------------*
      *                      INITIALIZE-ALL-FIELDS
      *----------------------------------------------------------------*
      * Clear all symbolic map input fields and the message area.
      * Sets cursor to the user-ID field (length = -1 triggers
      * CURSOR positioning on next SEND MAP).
       INITIALIZE-ALL-FIELDS.
      *    Position cursor to user-ID and blank all editable fields
           MOVE -1              TO USRIDINL OF COUSR2AI
           MOVE SPACES          TO USRIDINI OF COUSR2AI
                                   FNAMEI   OF COUSR2AI
                                   LNAMEI   OF COUSR2AI
                                   PASSWDI  OF COUSR2AI
                                   USRTYPEI OF COUSR2AI
                                   WS-MESSAGE.
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:34 CDT
      *
