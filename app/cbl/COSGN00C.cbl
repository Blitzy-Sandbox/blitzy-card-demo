      ******************************************************************        
      * Program     : COSGN00C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Signon Screen for the CardDemo Application
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
      * CICS online program: Sign-on screen - application entry
      * point. This is the first program invoked by users.
      * Transaction: CC00   BMS Map: COSGN00 / COSGN0A
      * Authenticates against USRSEC VSAM KSDS dataset
      * On success, routes to COMEN01C (main menu) or
      * COADM01C (admin menu) based on user type
      * Credential validation matches user ID and password
      * against SEC-USER-DATA record (see CSUSR01Y.cpy)
      * Failed login displays error and re-sends sign-on screen
      * Root of the entire CardDemo online application call chain
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COSGN00C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.
      * Working storage contains program identity, credentials,
      * error state, and CICS response fields for sign-on logic.

       01 WS-VARIABLES.
      * Program identity and transaction constants
         05 WS-PGMNAME                 PIC X(08) VALUE 'COSGN00C'.
         05 WS-TRANID                  PIC X(04) VALUE 'CC00'.
      * General-purpose message buffer for error display area
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      * VSAM file name literal for CICS READ on USRSEC dataset
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
      * Error flag controls whether authentication proceeds
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      * CICS response and reason codes from API calls
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      * User credentials from screen input (uppercased copies)
         05 WS-USER-ID                 PIC X(08).
         05 WS-USER-PWD                PIC X(08).

      * COMMAREA layout - initialized here during sign-on and
      * carried to all subsequent programs in the call chain.
      * See COCOM01Y.cpy for field definitions.
       COPY COCOM01Y.

      * Symbolic BMS map for sign-on screen (COSGN0A).
      * Defines input fields (user ID, password) and output
      * fields (titles, error messages, date/time display).
       COPY COSGN00.

      * Application title and banner text constants
       COPY COTTL01Y.
      * Date and time working storage fields
       COPY CSDAT01Y.
      * Common user-facing message text constants
       COPY CSMSG01Y.
      * SEC-USER-DATA: 80-byte user security record layout.
      * Fields: user ID, first/last name, password, user type.
      * See CSUSR01Y.cpy for field definitions.
       COPY CSUSR01Y.

      * CICS-supplied AID key constants (DFHENTER, DFHPF3, etc)
       COPY DFHAID.
      * CICS-supplied BMS attribute constants
       COPY DFHBMSCA.
      *COPY DFHATTR.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
      * DFHCOMMAREA receives the COMMAREA passed by CICS on
      * pseudo-conversational return or from a calling program.
      * EIBCALEN=0 on first entry (no COMMAREA exists yet).
       LINKAGE SECTION.
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                      PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * MAIN-PARA: Entry point using CICS pseudo-conversational
      * model. Checks EIBCALEN to determine first entry vs return.
       MAIN-PARA.
      * Resets error flag and clears message areas each iteration
           SET ERR-FLG-OFF TO TRUE

           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COSGN0AO

      * EIBCALEN=0 means first-time entry - no prior COMMAREA.
      * Sends blank sign-on screen with cursor on user ID field.
      * EIBCALEN>0 means returning from pseudo-conversational
      * wait - processes the AID key the user pressed.
           IF EIBCALEN = 0
               MOVE LOW-VALUES TO COSGN0AO
               MOVE -1       TO USERIDL OF COSGN0AI
               PERFORM SEND-SIGNON-SCREEN
           ELSE
      * Evaluates which AID key the user pressed
               EVALUATE EIBAID
      *            ENTER key: processes login credentials
                   WHEN DFHENTER
                       PERFORM PROCESS-ENTER-KEY
      *            PF3 key: exits session with thank-you message
                   WHEN DFHPF3
                       MOVE CCDA-MSG-THANK-YOU        TO WS-MESSAGE
                       PERFORM SEND-PLAIN-TEXT
      *            Any other key: displays invalid key error
                   WHEN OTHER
                       MOVE 'Y'                       TO WS-ERR-FLG
                       MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                       PERFORM SEND-SIGNON-SCREEN
               END-EVALUATE
           END-IF.

      * Pseudo-conversational return: sends COMMAREA back to CICS
      * and waits for next user input under transaction CC00.
      * Control returns to MAIN-PARA when user presses a key.
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
                     LENGTH(LENGTH OF CARDDEMO-COMMAREA)
           END-EXEC.


      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Handles ENTER key press: receives screen input, validates
      * credentials are present, then attempts authentication.
       PROCESS-ENTER-KEY.
      * Receives user-entered data from sign-on BMS map
           EXEC CICS RECEIVE
                     MAP('COSGN0A')
                     MAPSET('COSGN00')
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      * Validates that both user ID and password are provided.
      * Sets error flag and re-sends screen if either is empty.
      * MOVE -1 to length field positions cursor on that field.
           EVALUATE TRUE
               WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
                   MOVE 'Y'      TO WS-ERR-FLG
                   MOVE 'Please enter User ID ...' TO WS-MESSAGE
                   MOVE -1       TO USERIDL OF COSGN0AI
                   PERFORM SEND-SIGNON-SCREEN
               WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
                   MOVE 'Y'      TO WS-ERR-FLG
                   MOVE 'Please enter Password ...' TO WS-MESSAGE
                   MOVE -1       TO PASSWDL OF COSGN0AI
                   PERFORM SEND-SIGNON-SCREEN
               WHEN OTHER
                   CONTINUE
           END-EVALUATE.

      * Uppercases both credentials for case-insensitive match.
      * Stores user ID in both working storage and COMMAREA.
           MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
                           WS-USER-ID
                           CDEMO-USER-ID
           MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO
                           WS-USER-PWD

      * Proceeds to VSAM authentication only if no input errors
           IF NOT ERR-FLG-ON
               PERFORM READ-USER-SEC-FILE
           END-IF.

      *----------------------------------------------------------------*
      *                      SEND-SIGNON-SCREEN
      *----------------------------------------------------------------*
      * Sends the sign-on BMS map to the 3270 terminal.
      * Populates header info (titles, date, time) then sends
      * the map with ERASE (clears screen) and CURSOR (positions
      * cursor at the field with length set to -1).
       SEND-SIGNON-SCREEN.
      * Populates application titles, date, time, and CICS IDs
           PERFORM POPULATE-HEADER-INFO
      * Moves any error or informational message to screen area
           MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO
      * Sends BMS map COSGN0A from mapset COSGN00 to terminal
           EXEC CICS SEND
                     MAP('COSGN0A')
                     MAPSET('COSGN00')
                     FROM(COSGN0AO)
                     ERASE
                     CURSOR
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-PLAIN-TEXT
      *----------------------------------------------------------------*
      * Sends a plain text message and ends the CICS session.
      * Used for the PF3 exit path to display thank-you text.
       SEND-PLAIN-TEXT.
      * Sends message text to terminal (no BMS map formatting).
      * ERASE clears screen, FREEKB unlocks keyboard.
           EXEC CICS SEND TEXT
                     FROM(WS-MESSAGE)
                     LENGTH(LENGTH OF WS-MESSAGE)
                     ERASE
                     FREEKB
           END-EXEC.
      * Returns to CICS without TRANSID - ends user session.
      * No pseudo-conversational return; session terminates.
           EXEC CICS RETURN
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Populates screen header fields with application titles,
      * current date/time, transaction name, program name, and
      * CICS system identifiers. Called before every SEND MAP.
       POPULATE-HEADER-INFO.
      * Gets current system date and time (YYYYMMDDHHMMSSCC)
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
      * Sets application banner titles from COTTL01Y constants
           MOVE CCDA-TITLE01           TO TITLE01O OF COSGN0AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COSGN0AO
      * Displays transaction ID and program name on screen
           MOVE WS-TRANID              TO TRNNAMEO OF COSGN0AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COSGN0AO
      * Reformats date from YYYYMMDD to MM/DD/YY for display
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COSGN0AO
      * Reformats time from HHMMSS to HH:MM:SS for display
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COSGN0AO
      * Retrieves CICS application ID for display on screen
           EXEC CICS ASSIGN
               APPLID(APPLIDO OF COSGN0AO)
           END-EXEC
      * Retrieves CICS system ID for display on screen
           EXEC CICS ASSIGN
               SYSID(SYSIDO OF COSGN0AO)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      READ-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Reads the USRSEC VSAM KSDS by user ID key, validates
      * password, initializes COMMAREA, and routes to the
      * appropriate menu program based on user type.
       READ-USER-SEC-FILE.
      * Reads user security record from USRSEC VSAM dataset.
      * RIDFLD is the user ID key; reads into SEC-USER-DATA
      * layout (see CSUSR01Y.cpy for 80-byte record structure).
           EXEC CICS READ
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (WS-USER-ID)
                KEYLENGTH (LENGTH OF WS-USER-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.
      * Evaluates CICS response code from the READ operation.
      * RESP=0: record found. RESP=13: NOTFND. Other: I/O err.
           EVALUATE WS-RESP-CD
               WHEN 0
      * Record found - compares entered password with stored pwd
                   IF SEC-USR-PWD = WS-USER-PWD
      * Authentication succeeds - initializes COMMAREA fields.
      * Sets origin transaction/program, user ID, user type,
      * and resets context to zero (first entry to next pgm).
                       MOVE WS-TRANID    TO CDEMO-FROM-TRANID
                       MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
                       MOVE WS-USER-ID   TO CDEMO-USER-ID
                       MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
                       MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      * Routes to menu based on user type from USRSEC record.
      * Admin users (type 'A') go to COADM01C admin menu.
      * Regular users (type 'U') go to COMEN01C main menu.
      * XCTL transfers control and passes the COMMAREA.
                       IF CDEMO-USRTYP-ADMIN
                            EXEC CICS XCTL
                              PROGRAM ('COADM01C')
                              COMMAREA(CARDDEMO-COMMAREA)
                            END-EXEC
                       ELSE
                            EXEC CICS XCTL
                              PROGRAM ('COMEN01C')
                              COMMAREA(CARDDEMO-COMMAREA)
                            END-EXEC
                       END-IF
                   ELSE
      * Password mismatch - re-sends screen with error message.
      * Positions cursor on password field for retry.
                       MOVE 'Wrong Password. Try again ...' TO
                                                          WS-MESSAGE
                       MOVE -1       TO PASSWDL OF COSGN0AI
                       PERFORM SEND-SIGNON-SCREEN
                   END-IF
      * RESP=13 (NOTFND): user ID not in USRSEC dataset.
      * Positions cursor on user ID field for correction.
               WHEN 13
                   MOVE 'Y'      TO WS-ERR-FLG
                   MOVE 'User not found. Try again ...' TO WS-MESSAGE
                   MOVE -1       TO USERIDL OF COSGN0AI
                   PERFORM SEND-SIGNON-SCREEN
      * Any other RESP code indicates unexpected I/O error.
      * Displays generic verification failure message.
               WHEN OTHER
                   MOVE 'Y'      TO WS-ERR-FLG
                   MOVE 'Unable to verify the User ...' TO WS-MESSAGE
                   MOVE -1       TO USERIDL OF COSGN0AI
                   PERFORM SEND-SIGNON-SCREEN
           END-EVALUATE.
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:33 CDT
      *
