      ******************************************************************        
      * Program     : COUSR03C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Delete a user from USRSEC file
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
      * Program:     COUSR03C
      * Transaction: CU03
      * BMS Map:     COUSR03 / COUSR3A
      * Function:    User delete (admin function). Two-phase
      *              operation: (1) accept user ID (from input
      *              or COMMAREA), read USRSEC with UPDATE intent,
      *              display name and type as read-only for
      *              confirmation; (2) PF5 executes the DELETE.
      *              All detail fields are protected/read-only.
      *              Uses READ + DELETE pattern on USRSEC VSAM
      *              KSDS. Handles NOTFND and unexpected errors.
      * Files:       USRSEC VSAM KSDS (READ UPDATE, DELETE)
      * Navigation:  PF3 returns to caller. PF4 clears screen.
      *              PF5 confirms and deletes. PF12 returns to admin.
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COUSR03C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.
      *
      * Program control fields: identifiers, DDname, error
      * flag, CICS response codes, and modification tracker.
      *
       01 WS-VARIABLES.
         05 WS-PGMNAME                 PIC X(08) VALUE 'COUSR03C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CU03'.
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-USR-MODIFIED            PIC X(01) VALUE 'N'.
           88 USR-MODIFIED-YES                   VALUE 'Y'.
           88 USR-MODIFIED-NO                    VALUE 'N'.
           
      * COMMAREA structure for inter-program communication.
      * See app/cpy/COCOM01Y.cpy for field definitions.
       COPY COCOM01Y.
      * CU03 program-specific COMMAREA extension: user ID
      * range for browse and the selected user for delete.
          05 CDEMO-CU03-INFO.
             10 CDEMO-CU03-USRID-FIRST     PIC X(08).
             10 CDEMO-CU03-USRID-LAST      PIC X(08).
             10 CDEMO-CU03-PAGE-NUM        PIC 9(08).
             10 CDEMO-CU03-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
                88 NEXT-PAGE-YES                     VALUE 'Y'.
                88 NEXT-PAGE-NO                      VALUE 'N'.
             10 CDEMO-CU03-USR-SEL-FLG     PIC X(01).
             10 CDEMO-CU03-USR-SELECTED    PIC X(08).

      * BMS symbolic map for user delete screen (COUSR3A).
      * See app/bms/COUSR03.bms for screen layout definition.
       COPY COUSR03.

      * Application title and banner text
       COPY COTTL01Y.
      * Date/time working storage fields
       COPY CSDAT01Y.
      * Common user message definitions
       COPY CSMSG01Y.
      * User security record layout (80-byte USRSEC).
      * See app/cpy/CSUSR01Y.cpy for field definitions.
       COPY CSUSR01Y.

      * CICS attention identifier constants (ENTER, PF keys)
       COPY DFHAID.
      * BMS attribute constants (colors, highlights)
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
      * CICS passes the COMMAREA on each pseudo-conversational
      * re-entry. EIBCALEN indicates the length received.
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * Main entry point. If user ID was passed via COMMAREA
      * (CDEMO-CU03-USR-SELECTED), auto-populate and look up.
      * AID dispatch: Enter=lookup, PF3=back, PF4=clear,
      * PF5=delete, PF12=admin menu.
       MAIN-PARA.

           SET ERR-FLG-OFF     TO TRUE
           SET USR-MODIFIED-NO TO TRUE

           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COUSR3AO
      *
      * Pseudo-conversational: no COMMAREA means first entry
      * so redirect to sign-on screen.
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
               PERFORM RETURN-TO-PREV-SCREEN
           ELSE
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      * First-time entry: initialize screen, auto-lookup if
      * a user ID was passed from the user list screen.
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO COUSR3AO
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   IF CDEMO-CU03-USR-SELECTED NOT =
                                              SPACES AND LOW-VALUES
                       MOVE CDEMO-CU03-USR-SELECTED TO
                            USRIDINI OF COUSR3AI
                       PERFORM PROCESS-ENTER-KEY
                   END-IF
                   PERFORM SEND-USRDEL-SCREEN
      * Re-entry: receive screen input and dispatch by AID
               ELSE
                   PERFORM RECEIVE-USRDEL-SCREEN
      * Enter = look up user, PF3 = return to caller,
      * PF4 = clear, PF5 = confirm delete, PF12 = admin
                   EVALUATE EIBAID
                       WHEN DFHENTER
                           PERFORM PROCESS-ENTER-KEY
                       WHEN DFHPF3
                           IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
                               MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                           ELSE
                               MOVE CDEMO-FROM-PROGRAM TO
                               CDEMO-TO-PROGRAM
                           END-IF
                           PERFORM RETURN-TO-PREV-SCREEN
                       WHEN DFHPF4
                           PERFORM CLEAR-CURRENT-SCREEN
                       WHEN DFHPF5
                           PERFORM DELETE-USER-INFO
                       WHEN DFHPF12
                           MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                           PERFORM RETURN-TO-PREV-SCREEN
                       WHEN OTHER
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-USRDEL-SCREEN
                   END-EVALUATE
               END-IF
           END-IF

      * Return to CICS with pseudo-conversational wait.
      * TRANSID CU03 causes CICS to re-invoke this program
      * when the user next presses a key on the terminal.
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Validate user ID is non-empty, then read the USRSEC
      * record with UPDATE intent. On success, display user
      * name and type for deletion confirmation.
       PROCESS-ENTER-KEY.
      * Validate the user ID input is non-empty.
           EVALUATE TRUE
               WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   PERFORM SEND-USRDEL-SCREEN
               WHEN OTHER
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   CONTINUE
           END-EVALUATE

      * Clear display fields before fresh VSAM read.
           IF NOT ERR-FLG-ON
               MOVE SPACES      TO FNAMEI   OF COUSR3AI
                                   LNAMEI   OF COUSR3AI
                                   USRTYPEI OF COUSR3AI
               MOVE USRIDINI  OF COUSR3AI TO SEC-USR-ID
               PERFORM READ-USER-SEC-FILE
           END-IF.
      * Populate screen with user details (read-only view).
           IF NOT ERR-FLG-ON
               MOVE SEC-USR-FNAME      TO FNAMEI    OF COUSR3AI
               MOVE SEC-USR-LNAME      TO LNAMEI    OF COUSR3AI
               MOVE SEC-USR-TYPE       TO USRTYPEI  OF COUSR3AI
               PERFORM SEND-USRDEL-SCREEN
           END-IF.

      *----------------------------------------------------------------*
      *                      DELETE-USER-INFO
      *----------------------------------------------------------------*
      * Validate user ID, re-read with UPDATE, then perform
      * the actual DELETE of the USRSEC record.
       DELETE-USER-INFO.
      * Check user ID is non-empty before proceeding.
           EVALUATE TRUE
               WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID can NOT be empty...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   PERFORM SEND-USRDEL-SCREEN
               WHEN OTHER
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   CONTINUE
           END-EVALUATE

      * Re-read USRSEC with UPDATE intent, then delete the
      * held record. Both steps check CICS RESP codes.
           IF NOT ERR-FLG-ON
               MOVE USRIDINI  OF COUSR3AI TO SEC-USR-ID
               PERFORM READ-USER-SEC-FILE
               PERFORM DELETE-USER-SEC-FILE
           END-IF.

      *----------------------------------------------------------------*
      *                      RETURN-TO-PREV-SCREEN
      *----------------------------------------------------------------*
      * Transfer control to the previous screen via EXEC CICS
      * XCTL, passing the COMMAREA.
       RETURN-TO-PREV-SCREEN.
      * Default to sign-on if no target program is set.
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      * Record origin for the target program breadcrumb.
           MOVE WS-TRANID    TO CDEMO-FROM-TRANID
           MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
      * Reset context to first-time entry for target.
           MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      * Transfer control; does not return to this program.
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
               COMMAREA(CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-USRDEL-SCREEN
      *----------------------------------------------------------------*
      * Populate header and send BMS map COUSR3A with ERASE
      * and CURSOR positioning to the terminal.
       SEND-USRDEL-SCREEN.

           PERFORM POPULATE-HEADER-INFO
      * Copy current message text to BMS output field.
           MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO
      * Send map COUSR3A with ERASE (clear screen first)
      * and CURSOR (position at field with length -1).
           EXEC CICS SEND
                     MAP('COUSR3A')
                     MAPSET('COUSR03')
                     FROM(COUSR3AO)
                     ERASE
                     CURSOR
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-USRDEL-SCREEN
      *----------------------------------------------------------------*
      * Receive user input from BMS map COUSR3A into the
      * symbolic input area COUSR3AI.
       RECEIVE-USRDEL-SCREEN.
      * Receive terminal input into symbolic input area.
           EXEC CICS RECEIVE
                     MAP('COUSR3A')
                     MAPSET('COUSR03')
                     INTO(COUSR3AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Fill screen header: application titles, transaction
      * name, program name, current date and time.
       POPULATE-HEADER-INFO.
      * Obtain system date and time via intrinsic function.
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      * Set application banner titles from COTTL01Y copybook.
           MOVE CCDA-TITLE01           TO TITLE01O OF COUSR3AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COUSR3AO
           MOVE WS-TRANID              TO TRNNAMEO OF COUSR3AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COUSR3AO
      * Format date as MM/DD/YY for screen header display.
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COUSR3AO
      * Format time as HH:MM:SS for screen header display.
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COUSR3AO.

      *----------------------------------------------------------------*
      *                      READ-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Read user record from USRSEC VSAM KSDS with UPDATE
      * intent. Handles NORMAL (found — prompt for PF5 to
      * confirm), NOTFND (invalid ID), and OTHER errors.
       READ-USER-SEC-FILE.
      * Issue EXEC CICS READ with UPDATE option to hold the
      * record for a subsequent DELETE if user confirms PF5.
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
      * Evaluate CICS RESP code from the READ operation.
           EVALUATE WS-RESP-CD
      * Record found: prompt user to press PF5 to confirm.
               WHEN DFHRESP(NORMAL)
                   CONTINUE
                   MOVE 'Press PF5 key to delete this user ...' TO
                                   WS-MESSAGE
                   MOVE DFHNEUTR       TO ERRMSGC  OF COUSR3AO
                   PERFORM SEND-USRDEL-SCREEN
      * User ID not in USRSEC file.
               WHEN DFHRESP(NOTFND)
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   PERFORM SEND-USRDEL-SCREEN
      * Unexpected CICS error: log and display generic msg.
               WHEN OTHER
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO FNAMEL OF COUSR3AI
                   PERFORM SEND-USRDEL-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      DELETE-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Delete the currently held USRSEC record via EXEC CICS
      * DELETE. Handles NORMAL (success — show confirmation),
      * NOTFND (already deleted), and OTHER errors.
       DELETE-USER-SEC-FILE.
      * Issue EXEC CICS DELETE on the record held by the
      * prior READ UPDATE. No RIDFLD needed (held record).
           EXEC CICS DELETE
                DATASET   (WS-USRSEC-FILE)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.
      * Evaluate CICS RESP code from the DELETE operation.
           EVALUATE WS-RESP-CD
      * Delete successful: clear fields, show green message.
               WHEN DFHRESP(NORMAL)
                   PERFORM INITIALIZE-ALL-FIELDS
                   MOVE SPACES             TO WS-MESSAGE
                   MOVE DFHGREEN           TO ERRMSGC  OF COUSR3AO
                   STRING 'User '     DELIMITED BY SIZE
                          SEC-USR-ID  DELIMITED BY SPACE
                          ' has been deleted ...' DELIMITED BY SIZE
                     INTO WS-MESSAGE
                   PERFORM SEND-USRDEL-SCREEN
      * Record vanished between READ and DELETE.
               WHEN DFHRESP(NOTFND)
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'User ID NOT found...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR3AI
                   PERFORM SEND-USRDEL-SCREEN
      * Unexpected CICS error on DELETE operation.
               WHEN OTHER
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to Update User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO FNAMEL OF COUSR3AI
                   PERFORM SEND-USRDEL-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      CLEAR-CURRENT-SCREEN
      *----------------------------------------------------------------*
      * Reset all screen fields and re-send the blank form.
       CLEAR-CURRENT-SCREEN.

           PERFORM INITIALIZE-ALL-FIELDS.
           PERFORM SEND-USRDEL-SCREEN.

      *----------------------------------------------------------------*
      *                      INITIALIZE-ALL-FIELDS
      *----------------------------------------------------------------*
      * Clear all symbolic map input fields and message area.
      * Set cursor to user ID input field (length = -1).
       INITIALIZE-ALL-FIELDS.

           MOVE -1              TO USRIDINL OF COUSR3AI
           MOVE SPACES          TO USRIDINI OF COUSR3AI
                                   FNAMEI   OF COUSR3AI
                                   LNAMEI   OF COUSR3AI
                                   USRTYPEI OF COUSR3AI
                                   WS-MESSAGE.
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:35 CDT
      *
