      ******************************************************************        
      * Program     : COMEN01C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Main Menu for the Regular users
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
      * CICS online program: Main menu controller - central
      * navigation hub for the CardDemo application.
      *
      * Program:     COMEN01C
      * Transaction: CM00
      * BMS Map:     COMEN01 / CMEN01A
      *
      * Displays 10 functional options and routes the user to
      * the selected target program via EXEC CICS XCTL. The
      * menu is table-driven via the COMEN02Y copybook, which
      * holds a 10-entry routing table mapping option numbers
      * to COBOL program names.
      *
      * Option routing (from COMEN02Y):
      *   1  Account View        -> COACTVWC
      *   2  Account Update      -> COACTUPC
      *   3  Credit Card List    -> COCRDLIC
      *   4  Credit Card View    -> COCRDSLC
      *   5  Credit Card Update  -> COCRDUPC
      *   6  Transaction List    -> COTRN00C
      *   7  Transaction View    -> COTRN01C
      *   8  Transaction Add     -> COTRN02C
      *   9  Transaction Reports -> CORPT00C
      *  10  Bill Payment        -> COBIL00C
      *
      * Reached from COSGN00C (sign-on) via XCTL after the
      * user authenticates successfully. All functional
      * programs return here when the user presses PF3,
      * which triggers XCTL back to COMEN01C.
      *
      * Files:       None (menu metadata from COMEN02Y)
      * Copybooks:   COCOM01Y  - COMMAREA layout
      *              COMEN02Y  - Menu routing table
      *              COMEN01   - BMS symbolic map
      *              COTTL01Y  - Screen title text
      *              CSDAT01Y  - Date/time work fields
      *              CSMSG01Y  - Common messages
      *              CSUSR01Y  - User security record
      *              DFHAID    - AID key constants
      *              DFHBMSCA  - BMS attribute constants
      * Navigation:  PF3  - returns to sign-on (COSGN00C)
      *              ENTER - XCTLs to selected program
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COMEN01C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

      * Local working storage for the main menu program
       01 WS-VARIABLES.
      *  Program name constant used in COMMAREA and screen header
         05 WS-PGMNAME                 PIC X(08) VALUE 'COMEN01C'.
      *  CICS transaction ID for pseudo-conversational return
         05 WS-TRANID                  PIC X(04) VALUE 'CM00'.
      *  General message buffer displayed in the error/info area
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      *  VSAM file name for user security dataset (not read
      *  directly by this program; reserved for future use)
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
      *  Error flag controls whether the menu re-displays with
      *  an error message instead of routing to a target program
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      *  CICS RESP and RESP2 codes from RECEIVE MAP
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      *  Intermediate alphanumeric holder for the menu option
      *  input; right-justified so leading spaces become zeros
         05 WS-OPTION-X                PIC X(02) JUST RIGHT.
      *  Numeric menu option after conversion from WS-OPTION-X
         05 WS-OPTION                  PIC 9(02) VALUE 0.
      *  Loop index used in BUILD-MENU-OPTIONS and input trimming
         05 WS-IDX                     PIC S9(04) COMP VALUE ZEROS.
      *  Formatted menu line text (e.g. "1. Account View")
         05 WS-MENU-OPT-TXT            PIC X(40) VALUE SPACES.

      * COMMAREA layout (CARDDEMO-COMMAREA). Carries user
      * identity (CDEMO-USER-ID), user type flag
      * (CDEMO-USRTYP-ADMIN / CDEMO-USRTYP-USER), routing
      * state (CDEMO-PGM-REENTER), and previous/next program
      * context across XCTL calls.
      * See: app/cpy/COCOM01Y.cpy
       COPY COCOM01Y.
      * 10-entry menu routing table (CARDDEMO-MAIN-MENU-OPTIONS).
      * Each entry holds an option number (PIC 9(02)), a
      * 35-byte option name, an 8-byte target COBOL program
      * name, and a 1-byte user-type qualifier ('U' = all
      * users, 'A' = admin only). The table is addressed via
      * REDEFINES array CDEMO-MENU-OPT(1..12).
      * See: app/cpy/COMEN02Y.cpy
       COPY COMEN02Y.

      * BMS symbolic map for main menu screen CMEN01A.
      * Defines input area COMEN1AI (option input, AID) and
      * output area COMEN1AO (titles, option lines, messages).
      * See: app/bms/COMEN01.bms
       COPY COMEN01.

      * Application title and banner text (CCDA-TITLE01,
      * CCDA-TITLE02) displayed in the screen header area
       COPY COTTL01Y.
      * Date and time working storage fields used to format
      * the MM/DD/YY date and HH:MM:SS time in the header
       COPY CSDAT01Y.
      * Common user-facing messages (CCDA-MSG-INVALID-KEY,
      * CCDA-MSG-THANK-YOU) shown in the error message line
       COPY CSMSG01Y.
      * 80-byte user security record layout (SEC-USER-DATA).
      * Provides SEC-USR-TYPE used to verify whether the
      * current user qualifies for admin-only menu options
      * See: app/cpy/CSUSR01Y.cpy
       COPY CSUSR01Y.

      * CICS attention identifier constants (DFHENTER,
      * DFHPF3, etc.) used to evaluate EIBAID after
      * RECEIVE MAP
       COPY DFHAID.
      * BMS attribute constants (DFHGREEN, DFHRED, etc.)
      * used to set field color/highlight in the map output
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
      * DFHCOMMAREA is the CICS-managed communication area
      * passed between programs via XCTL or RETURN COMMAREA.
      * Its actual length is indicated by EIBCALEN. On first
      * invocation from the sign-on program, the caller may
      * pass the full CARDDEMO-COMMAREA; EIBCALEN reflects
      * the length actually transferred.
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * Main entry point — implements the CICS pseudo-
      * conversational model. Each terminal interaction is a
      * separate task invocation:
      *   1. EIBCALEN = 0: No COMMAREA present — the program
      *      was started without context (abnormal); redirects
      *      to the sign-on screen.
      *   2. First entry (CDEMO-PGM-REENTER = 0): Arrived via
      *      XCTL from COSGN00C after authentication. Sets the
      *      re-enter flag, clears the output map, and sends
      *      the menu screen.
      *   3. Re-entry (CDEMO-PGM-REENTER = 1): Terminal user
      *      submitted input. Receives the BMS map and
      *      dispatches based on AID key (Enter or PF3).
       MAIN-PARA.

      *    Reset the error flag at the start of every task
           SET ERR-FLG-OFF TO TRUE

      *    Clear any prior message from the output map
           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COMEN1AO

      *    Guard: if EIBCALEN is zero no COMMAREA was passed —
      *    redirect to sign-on to establish a valid session
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
               PERFORM RETURN-TO-SIGNON-SCREEN
           ELSE
      *        Copy the incoming COMMAREA into the local
      *        CARDDEMO-COMMAREA structure (COCOM01Y)
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      *        First entry: flag is zero — set re-enter flag,
      *        initialize the output map, and display the menu
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO COMEN1AO
                   PERFORM SEND-MENU-SCREEN
               ELSE
      *            Re-entry: read the user input from the map
                   PERFORM RECEIVE-MENU-SCREEN
      *            Dispatch based on the AID key the user pressed
                   EVALUATE EIBAID
                       WHEN DFHENTER
      *                    User pressed Enter — validate and
      *                    route to the selected menu option
                           PERFORM PROCESS-ENTER-KEY
                       WHEN DFHPF3
      *                    PF3 pressed — return to sign-on
      *                    screen (COSGN00C) to log off
                           MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
                           PERFORM RETURN-TO-SIGNON-SCREEN
                       WHEN OTHER
      *                    Any other AID key is invalid; set
      *                    error flag and re-display menu with
      *                    the standard invalid-key message
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-MENU-SCREEN
                   END-EVALUATE
               END-IF
           END-IF

      *    Pseudo-conversational return: gives up the CICS task
      *    but tells CICS to re-invoke transaction CM00 when
      *    the terminal user presses a key. The COMMAREA is
      *    preserved across the wait so that the next
      *    invocation resumes with full session context.
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Validates the menu option the user typed, enforces
      * access rules, and transfers control to the target
      * program. Processing steps:
      *   1. Trim trailing spaces from the option input field
      *   2. Convert to numeric and range-check (1-10)
      *   3. Reject admin-only options for regular users
      *   4. XCTL to the target program from COMEN02Y table
      *   5. If the target is a DUMMY placeholder, display a
      *      "coming soon" informational message instead
       PROCESS-ENTER-KEY.

      *    Step 1: Find the last non-space character in the
      *    option input field by scanning right to left
           PERFORM VARYING WS-IDX
                   FROM LENGTH OF OPTIONI OF COMEN1AI BY -1 UNTIL
                   OPTIONI OF COMEN1AI(WS-IDX:1) NOT = SPACES OR
                   WS-IDX = 1
           END-PERFORM
      *    Extract the trimmed input and replace remaining
      *    spaces with zeros so the value converts cleanly
           MOVE OPTIONI OF COMEN1AI(1:WS-IDX) TO WS-OPTION-X
           INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
      *    Move the cleaned value to the numeric field and
      *    echo it back to the screen output field
           MOVE WS-OPTION-X              TO WS-OPTION
           MOVE WS-OPTION                TO OPTIONO OF COMEN1AO

      *    Step 2: Range validation — option must be numeric,
      *    within 1..CDEMO-MENU-OPT-COUNT (10), and non-zero
           IF WS-OPTION IS NOT NUMERIC OR
              WS-OPTION > CDEMO-MENU-OPT-COUNT OR
              WS-OPTION = ZEROS
               MOVE 'Y'     TO WS-ERR-FLG
               MOVE 'Please enter a valid option number...' TO
                               WS-MESSAGE
               PERFORM SEND-MENU-SCREEN
           END-IF

      *    Step 3: Admin-only access check. If the user type
      *    (from COMMAREA CDEMO-USRTYP) is 'U' (regular) but
      *    the selected option requires type 'A' (admin),
      *    deny access and re-display the menu
           IF CDEMO-USRTYP-USER AND
              CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
               SET ERR-FLG-ON          TO TRUE
               MOVE SPACES             TO WS-MESSAGE
               MOVE 'No access - Admin Only option... ' TO
                                       WS-MESSAGE
               PERFORM SEND-MENU-SCREEN
           END-IF

      *    Step 4: If no errors, attempt to transfer control
           IF NOT ERR-FLG-ON
      *        Check whether the target program name starts
      *        with 'DUMMY' — a placeholder for unimplemented
      *        features. Real programs get an XCTL transfer.
               IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
      *            Record the originating transaction and
      *            program in the COMMAREA so the target
      *            program knows where the user came from
                   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
                   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
      *            MOVE WS-USER-ID   TO CDEMO-USER-ID
      *            MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
      *            Reset the program context flag so the
      *            target program treats this as a first entry
                   MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      *            XCTL transfers control to the target
      *            program and passes the full COMMAREA.
      *            This program does not regain control;
      *            the target program is responsible for
      *            returning the user to this menu via PF3.
                   EXEC CICS
                       XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
                       COMMAREA(CARDDEMO-COMMAREA)
                   END-EXEC
               END-IF
      *        Step 5: If target is DUMMY, display a green
      *        informational message indicating the feature
      *        is not yet available
               MOVE SPACES             TO WS-MESSAGE
               MOVE DFHGREEN           TO ERRMSGC  OF COMEN1AO
               STRING 'This option '       DELIMITED BY SIZE
                       CDEMO-MENU-OPT-NAME(WS-OPTION)
                                       DELIMITED BY SPACE
                       'is coming soon ...'   DELIMITED BY SIZE
                  INTO WS-MESSAGE
               PERFORM SEND-MENU-SCREEN
           END-IF.

      *----------------------------------------------------------------*
      *                      RETURN-TO-SIGNON-SCREEN
      *----------------------------------------------------------------*
      * Transfers control to the sign-on screen so the user
      * can log on again or end the session. Defaults the
      * target to COSGN00C if no explicit program was set in
      * the COMMAREA. Because XCTL is used (not LINK), this
      * program is removed from the program chain and does
      * not receive control back.
       RETURN-TO-SIGNON-SCREEN.

      *    Default the target program if it was never set
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      *    XCTL without COMMAREA — the sign-on program
      *    starts fresh with EIBCALEN = 0
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-MENU-SCREEN
      *----------------------------------------------------------------*
      * Assembles the full screen content and sends it to the
      * 3270 terminal. Calls POPULATE-HEADER-INFO for titles,
      * date, and time, then BUILD-MENU-OPTIONS to format the
      * numbered option lines. The ERASE option clears the
      * terminal screen before painting the new map.
       SEND-MENU-SCREEN.

      *    Fill the screen header (titles, date, time)
           PERFORM POPULATE-HEADER-INFO
      *    Build the numbered menu option lines from COMEN02Y
           PERFORM BUILD-MENU-OPTIONS

      *    Copy any pending message into the map error line
           MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO

      *    Send the assembled output map to the terminal.
      *    MAP('COMEN1A') identifies the map within the
      *    mapset COMEN01. ERASE clears the screen first.
           EXEC CICS SEND
                     MAP('COMEN1A')
                     MAPSET('COMEN01')
                     FROM(COMEN1AO)
                     ERASE
           END-EXEC.

      *----------------------------------------------------------------*
      *                      RECEIVE-MENU-SCREEN
      *----------------------------------------------------------------*
      * Reads the user input from the terminal into the BMS
      * symbolic input area COMEN1AI. The RESP and RESP2
      * options capture the CICS response code so the program
      * can detect errors (e.g. MAPFAIL if no data was
      * modified) without abending.
       RECEIVE-MENU-SCREEN.

           EXEC CICS RECEIVE
                     MAP('COMEN1A')
                     MAPSET('COMEN01')
                     INTO(COMEN1AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Populates the screen header fields that appear at the
      * top of every menu display: two application title
      * lines (from COTTL01Y), the transaction ID, the
      * program name, and the current date and time formatted
      * as MM/DD/YY and HH:MM:SS respectively. Uses the
      * COBOL FUNCTION CURRENT-DATE intrinsic to obtain the
      * system date/time in YYYYMMDDHHMMSSFF format, then
      * rearranges the components via CSDAT01Y work fields.
       POPULATE-HEADER-INFO.

      *    Obtain the system date and time (YYYYMMDDHHMMSSFF)
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA

      *    Set the two-line application banner from COTTL01Y
           MOVE CCDA-TITLE01           TO TITLE01O OF COMEN1AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COMEN1AO
      *    Display the transaction ID and program name
           MOVE WS-TRANID              TO TRNNAMEO OF COMEN1AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COMEN1AO

      *    Reformat YYYYMMDD into MM/DD/YY for screen display
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COMEN1AO

      *    Reformat HHMMSSFF into HH:MM:SS for screen display
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COMEN1AO.

      *----------------------------------------------------------------*
      *                      BUILD-MENU-OPTIONS
      *----------------------------------------------------------------*
      * Iterates through the COMEN02Y menu routing table and
      * formats a numbered display line for each active option.
      * The loop runs from 1 to CDEMO-MENU-OPT-COUNT (10).
      * Each iteration builds a string like "1. Account View"
      * and places it into the corresponding output map field
      * (OPTN001O through OPTN012O) via an EVALUATE dispatch.
      * The EVALUATE supports up to 12 slots even though only
      * 10 options are currently defined in the routing table.
       BUILD-MENU-OPTIONS.

           PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
                           WS-IDX > CDEMO-MENU-OPT-COUNT

               MOVE SPACES             TO WS-MENU-OPT-TXT

      *        Build the formatted line: "NN. Option Name"
               STRING CDEMO-MENU-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
                      '. '                         DELIMITED BY SIZE
                      CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE
                 INTO WS-MENU-OPT-TXT

      *        Map the formatted line to the correct output
      *        field in the BMS symbolic map. Each OPTN00nO
      *        corresponds to a screen row on the menu display.
               EVALUATE WS-IDX
                   WHEN 1
                       MOVE WS-MENU-OPT-TXT TO OPTN001O
                   WHEN 2
                       MOVE WS-MENU-OPT-TXT TO OPTN002O
                   WHEN 3
                       MOVE WS-MENU-OPT-TXT TO OPTN003O
                   WHEN 4
                       MOVE WS-MENU-OPT-TXT TO OPTN004O
                   WHEN 5
                       MOVE WS-MENU-OPT-TXT TO OPTN005O
                   WHEN 6
                       MOVE WS-MENU-OPT-TXT TO OPTN006O
                   WHEN 7
                       MOVE WS-MENU-OPT-TXT TO OPTN007O
                   WHEN 8
                       MOVE WS-MENU-OPT-TXT TO OPTN008O
                   WHEN 9
                       MOVE WS-MENU-OPT-TXT TO OPTN009O
                   WHEN 10
                       MOVE WS-MENU-OPT-TXT TO OPTN010O
                   WHEN 11
                       MOVE WS-MENU-OPT-TXT TO OPTN011O
                   WHEN 12
                       MOVE WS-MENU-OPT-TXT TO OPTN012O
                   WHEN OTHER
                       CONTINUE
               END-EVALUATE

           END-PERFORM.


      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:33 CDT
      *
