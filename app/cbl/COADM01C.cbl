      ******************************************************************        
      * Program     : COADM01C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Admin Menu for Admin users
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
      * CICS online program: Admin menu controller
      * Program:     COADM01C
      * Transaction: CA00
      * BMS Map:     COADM01 / COADM1A
      * Function:    Displays 4 admin options and routes via XCTL
      *              to user-administration programs:
      *                1 - User List   (COUSR00C)
      *                2 - User Add    (COUSR01C)
      *                3 - User Update (COUSR02C)
      *                4 - User Delete (COUSR03C)
      *              Menu is table-driven via COADM02Y copybook
      *              (4-entry routing array). Screen layout supports
      *              up to 10 display slots via EVALUATE dispatch.
      *              Admin-only access is enforced by COMEN01C
      *              before transfer to this program.
      * Files:       None (menu metadata from COADM02Y copybook)
      * Navigation:  PF3 returns to sign-on (COSGN00C).
      *              ENTER on valid option XCTLs to target program.
      * Copybooks:   COCOM01Y, COADM02Y, COADM01, COTTL01Y,
      *              CSDAT01Y, CSMSG01Y, CSUSR01Y, DFHAID,
      *              DFHBMSCA
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COADM01C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

       01 WS-VARIABLES.
      * Current program name used in EXEC CICS RETURN TRANSID
      * and stored in CDEMO-FROM-PROGRAM before XCTL calls
         05 WS-PGMNAME                 PIC X(08) VALUE 'COADM01C'.
      * CA00 pseudo-conversational transaction ID; CICS re-invokes
      * this program under CA00 after each terminal interaction
         05 WS-TRANID                  PIC X(04) VALUE 'CA00'.
      * 80-byte screen message area; content moves to ERRMSGO
      * on the BMS output map before each SEND MAP
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      * VSAM KSDS file name for user security data (USRSEC);
      * available for shared logic but not directly read here
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
      * Validation error flag with 88-level conditions.
      * ERR-FLG-ON (Y) signals invalid input and triggers
      * screen re-display with error message.
      * ERR-FLG-OFF (N) signals clean state for processing.
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      * CICS API response and reason codes captured from
      * EXEC CICS RECEIVE MAP RESP/RESP2 options
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      * Menu option processing fields:
      * WS-OPTION-X holds raw screen input (right-justified)
      * WS-OPTION holds numeric result after space-to-zero
      * conversion. Valid range is 1 through
      * CDEMO-ADMIN-OPT-COUNT (currently 4).
         05 WS-OPTION-X                PIC X(02) JUST RIGHT.
         05 WS-OPTION                  PIC 9(02) VALUE 0.
      * Loop counter for BUILD-MENU-OPTIONS iteration and
      * also used in PROCESS-ENTER-KEY for trailing-space trim
         05 WS-IDX                     PIC S9(04) COMP VALUE ZEROS.
      * Formatted menu option text assembled by STRING
      * (e.g., "1. User List (Security)")
         05 WS-ADMIN-OPT-TXT           PIC X(40) VALUE SPACES.

      * COMMAREA structure for inter-program communication
       COPY COCOM01Y.
      * Admin menu option table (4 entries: user CRUD programs)
       COPY COADM02Y.

      * BMS symbolic map for admin menu screen (COADM1A)
       COPY COADM01.

      * Application title and banner text
       COPY COTTL01Y.
      * Date/time working storage fields
       COPY CSDAT01Y.
      * Common user message definitions
       COPY CSMSG01Y.
      * User security record layout (80-byte USRSEC)
       COPY CSUSR01Y.

      * CICS attention identifier constants (ENTER, PF keys)
       COPY DFHAID.
      * BMS attribute constants (colors, highlights)
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
      * DFHCOMMAREA is the CICS-managed communication area passed
      * between pseudo-conversational invocations. EIBCALEN holds
      * its byte length. On first entry EIBCALEN is 0 (no prior
      * COMMAREA). On re-entry the content is copied into
      * CARDDEMO-COMMAREA (COCOM01Y) for program use.
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * Main entry point. If no COMMAREA, redirect to sign-on.
      * On first entry, send admin menu. On re-entry, receive
      * input and dispatch based on AID key pressed.
       MAIN-PARA.

      * Reset error flag and clear screen message areas
           SET ERR-FLG-OFF TO TRUE

           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COADM1AO

      * EIBCALEN = 0 means no COMMAREA exists (direct start or
      * abnormal entry). Redirect to sign-on screen (COSGN00C).
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
               PERFORM RETURN-TO-SIGNON-SCREEN
           ELSE
      * Copy CICS COMMAREA into CARDDEMO-COMMAREA (COCOM01Y)
      * for access to routing fields and user identity
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      * CDEMO-PGM-REENTER (88-level in COCOM01Y) distinguishes
      * first entry (value 0) from subsequent re-entries (value 1).
      * On first entry, mark re-enter, clear output map, and send
      * the admin menu screen to the terminal.
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO COADM1AO
                   PERFORM SEND-MENU-SCREEN
               ELSE
      * On re-entry, receive user input and evaluate the AID key:
      * ENTER processes the selected menu option,
      * PF3 returns to sign-on screen,
      * any other key shows an invalid-key error message.
                   PERFORM RECEIVE-MENU-SCREEN
                   EVALUATE EIBAID
                       WHEN DFHENTER
                           PERFORM PROCESS-ENTER-KEY
                       WHEN DFHPF3
                           MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
                           PERFORM RETURN-TO-SIGNON-SCREEN
                       WHEN OTHER
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-MENU-SCREEN
                   END-EVALUATE
               END-IF
           END-IF

      * Return to CICS with pseudo-conversational wait;
      * re-invoke under transaction CA00 on next terminal input
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Validate the admin option entered by the user. Trim
      * trailing spaces, convert to numeric, enforce range check
      * (1 through CDEMO-ADMIN-OPT-COUNT), then use the COADM02Y
      * routing table to look up the target program name and
      * XCTL to it. Option mapping:
      *   1 -> COUSR00C (User List)
      *   2 -> COUSR01C (User Add)
      *   3 -> COUSR02C (User Update)
      *   4 -> COUSR03C (User Delete)
       PROCESS-ENTER-KEY.

      * Trim trailing spaces from the screen input field by
      * scanning backwards from the field length until a
      * non-space character is found
           PERFORM VARYING WS-IDX
                   FROM LENGTH OF OPTIONI OF COADM1AI BY -1 UNTIL
                   OPTIONI OF COADM1AI(WS-IDX:1) NOT = SPACES OR
                   WS-IDX = 1
           END-PERFORM
      * Copy trimmed input to right-justified WS-OPTION-X,
      * replace remaining spaces with zeros, then move to
      * numeric WS-OPTION for validation
           MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X
           INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
           MOVE WS-OPTION-X              TO WS-OPTION
      * Echo validated option back to screen output field
           MOVE WS-OPTION                TO OPTIONO OF COADM1AO

      * Range check: option must be numeric, within 1 through
      * CDEMO-ADMIN-OPT-COUNT (4), and not zero. On failure
      * set error flag and re-display the menu with a message.
           IF WS-OPTION IS NOT NUMERIC OR
              WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
              WS-OPTION = ZEROS
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'Please enter a valid option number...' TO
                                       WS-MESSAGE
               PERFORM SEND-MENU-SCREEN
           END-IF


      * If validation passed, look up the target program name
      * from the COADM02Y routing table using WS-OPTION as index
           IF NOT ERR-FLG-ON
      * Skip entries whose program name starts with 'DUMMY'
      * (placeholder for future options not yet implemented)
               IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
      * Store current transaction/program in COMMAREA for
      * the target program's back-navigation context, then
      * reset PGM-CONTEXT so target starts fresh
                   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
                   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
                   MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      * Transfer control to the selected admin program via
      * EXEC CICS XCTL. This does not return; control passes
      * entirely to the target (e.g., COUSR00C for User List).
                   EXEC CICS
                       XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
                       COMMAREA(CARDDEMO-COMMAREA)
                   END-EXEC
               END-IF
      * If the option maps to a DUMMY program, display a
      * "coming soon" informational message in green
               MOVE SPACES             TO WS-MESSAGE
               MOVE DFHGREEN           TO ERRMSGC  OF COADM1AO
               STRING 'This option '       DELIMITED BY SIZE
      *                CDEMO-ADMIN-OPT-NAME(WS-OPTION)
      *                                DELIMITED BY SIZE
                       'is coming soon ...'   DELIMITED BY SIZE
                  INTO WS-MESSAGE
               PERFORM SEND-MENU-SCREEN
           END-IF.

      *----------------------------------------------------------------*
      *                      RETURN-TO-SIGNON-SCREEN
      *----------------------------------------------------------------*
      * Transfer control to the sign-on screen via EXEC CICS XCTL.
      * Defaults to COSGN00C if CDEMO-TO-PROGRAM is not set.
      * PF3 in MAIN-PARA routes here for back-navigation.
       RETURN-TO-SIGNON-SCREEN.

      * Guard against uninitialized target program field
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      * XCTL transfers control without return; this program's
      * storage is released by CICS
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-MENU-SCREEN
      *----------------------------------------------------------------*
      * Populate header fields and admin option lines, then send
      * BMS map COADM1A from MAPSET COADM01 to the 3270 terminal
      * with ERASE to clear the screen before painting.
       SEND-MENU-SCREEN.

      * Fill titles, date/time, transaction and program name
           PERFORM POPULATE-HEADER-INFO
      * Format the numbered option lines from COADM02Y table
           PERFORM BUILD-MENU-OPTIONS

      * Copy any pending message to the screen error/info field
           MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO

      * Send the complete output map to the terminal
           EXEC CICS SEND
                     MAP('COADM1A')
                     MAPSET('COADM01')
                     FROM(COADM1AO)
                     ERASE
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-MENU-SCREEN
      *----------------------------------------------------------------*
      * Receive user input from BMS map COADM1A into the
      * symbolic input area COADM1AI. RESP and RESP2 capture
      * CICS response codes for error detection (e.g., MAPFAIL
      * if no modified fields are transmitted).
       RECEIVE-MENU-SCREEN.

           EXEC CICS RECEIVE
                     MAP('COADM1A')
                     MAPSET('COADM01')
                     INTO(COADM1AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Fill screen header: application titles, transaction
      * name, program name, current date and time.
      * Uses FUNCTION CURRENT-DATE (intrinsic) and CSDAT01Y
      * working storage to reformat into MM/DD/YY and HH:MM:SS.
       POPULATE-HEADER-INFO.

      * Retrieve system date and time (YYYYMMDDHHMMSSCC format)
      * into WS-CURDATE-DATA defined in CSDAT01Y copybook
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA

      * Set application banner lines from COTTL01Y copybook
           MOVE CCDA-TITLE01           TO TITLE01O OF COADM1AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COADM1AO
      * Display transaction ID and program name in header bar
           MOVE WS-TRANID              TO TRNNAMEO OF COADM1AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COADM1AO

      * Reformat date from YYYYMMDD to MM/DD/YY display format
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COADM1AO

      * Reformat time from HHMMSSCC to HH:MM:SS display format
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COADM1AO.

      *----------------------------------------------------------------*
      *                      BUILD-MENU-OPTIONS
      *----------------------------------------------------------------*
      * Iterate through the COADM02Y admin option table (4 active
      * entries) and format numbered option text lines for the
      * BMS screen output fields OPTN001O through OPTN010O.
      * Uses EVALUATE to dispatch each index to the corresponding
      * screen field. The structure supports up to 10 display
      * slots although only 4 are currently populated.
       BUILD-MENU-OPTIONS.

      * Loop from 1 to CDEMO-ADMIN-OPT-COUNT (4)
           PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
                           WS-IDX > CDEMO-ADMIN-OPT-COUNT

               MOVE SPACES             TO WS-ADMIN-OPT-TXT

      * Build display text: option number + ". " + option name
      * e.g., "1. User List (Security)"
               STRING CDEMO-ADMIN-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
                      '. '                         DELIMITED BY SIZE
                      CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
                 INTO WS-ADMIN-OPT-TXT

      * Map loop index to corresponding BMS screen output field
               EVALUATE WS-IDX
                   WHEN 1
                       MOVE WS-ADMIN-OPT-TXT TO OPTN001O
                   WHEN 2
                       MOVE WS-ADMIN-OPT-TXT TO OPTN002O
                   WHEN 3
                       MOVE WS-ADMIN-OPT-TXT TO OPTN003O
                   WHEN 4
                       MOVE WS-ADMIN-OPT-TXT TO OPTN004O
                   WHEN 5
                       MOVE WS-ADMIN-OPT-TXT TO OPTN005O
                   WHEN 6
                       MOVE WS-ADMIN-OPT-TXT TO OPTN006O
                   WHEN 7
                       MOVE WS-ADMIN-OPT-TXT TO OPTN007O
                   WHEN 8
                       MOVE WS-ADMIN-OPT-TXT TO OPTN008O
                   WHEN 9
                       MOVE WS-ADMIN-OPT-TXT TO OPTN009O
                   WHEN 10
                       MOVE WS-ADMIN-OPT-TXT TO OPTN010O
                   WHEN OTHER
                       CONTINUE
               END-EVALUATE

           END-PERFORM.


      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:32 CDT
      *
