      ******************************************************************        
      * Program     : COUSR00C.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : List all users from USRSEC file
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
      * CICS online program: User list browse (admin function)
      * Transaction: CU00
      * BMS Map:     COUSR00 / COUSR0A
      * Function:    Reads USRSEC VSAM KSDS records and displays a
      *              paginated user list (10 rows per page). Uses
      *              STARTBR/READNEXT/READPREV/ENDBR browse pattern.
      * Features:    F7/F8 paging, user ID search filter via the
      *              USRIDIN input field, and row selection for
      *              update (U) or delete (D) actions.
      * Admin-only:  Accessible from admin menu (COADM01C). User
      *              type validated via CDEMO-USRTYP-ADMIN flag in
      *              the COMMAREA (see COCOM01Y.cpy).
      * Files:       USRSEC (STARTBR, READNEXT, READPREV, ENDBR)
      * Navigation:  PF3 returns to admin menu (COADM01C).
      *              PF7 pages backward. PF8 pages forward.
      *              Enter with U/D selection routes to COUSR02C
      *              (update) or COUSR03C (delete) via XCTL.
      * Copybooks:   COCOM01Y (COMMAREA), CSUSR01Y (SEC-USER-DATA),
      *              COTTL01Y (titles), CSDAT01Y (date/time),
      *              CSMSG01Y (messages), DFHAID, DFHBMSCA
      * See also:    app/bms/COUSR00.bms (screen layout)
      *              app/cpy-bms/COUSR00.CPY (symbolic map)
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COUSR00C.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

      * Program identity and state flags
       01 WS-VARIABLES.
      *  Program name used in COMMAREA routing and screen header
         05 WS-PGMNAME                 PIC X(08) VALUE 'COUSR00C'.
      *  CICS transaction ID for pseudo-conversational RETURN
         05 WS-TRANID                  PIC X(04) VALUE 'CU00'.
      *  Message buffer displayed in the screen error/info area
         05 WS-MESSAGE                 PIC X(80) VALUE SPACES.
      *  CICS file name for the USRSEC VSAM KSDS dataset
         05 WS-USRSEC-FILE             PIC X(08) VALUE 'USRSEC  '.
      *  Error flag: set to Y on CICS or validation error
         05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.
           88 ERR-FLG-ON                         VALUE 'Y'.
           88 ERR-FLG-OFF                        VALUE 'N'.
      *  End-of-file flag for USRSEC browse operations
         05 WS-USER-SEC-EOF            PIC X(01) VALUE 'N'.
           88 USER-SEC-EOF                       VALUE 'Y'.
           88 USER-SEC-NOT-EOF                   VALUE 'N'.
      *  Controls whether SEND MAP uses ERASE (full redraw)
         05 WS-SEND-ERASE-FLG          PIC X(01) VALUE 'Y'.
           88 SEND-ERASE-YES                     VALUE 'Y'.
           88 SEND-ERASE-NO                      VALUE 'N'.

      *  CICS RESP and RESP2 codes from file I/O operations
         05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.
      *  Record counter (unused in current logic)
         05 WS-REC-COUNT               PIC S9(04) COMP VALUE ZEROS.
      *  Row index for 10-row screen list (1 through 10)
         05 WS-IDX                     PIC S9(04) COMP VALUE ZEROS.
      *  Local page number tracker
         05 WS-PAGE-NUM                PIC S9(04) COMP VALUE ZEROS.

      * 10-row display buffer for user list screen assembly.
      * Each row holds selection flag, user ID, name, and type.
       01 WS-USER-DATA.
         02 USER-REC OCCURS 10 TIMES.
           05 USER-SEL                   PIC X(01).
           05 FILLER                     PIC X(02).
           05 USER-ID                    PIC X(08).
           05 FILLER                     PIC X(02).
           05 USER-NAME                  PIC X(25).
           05 FILLER                     PIC X(02).
           05 USER-TYPE                  PIC X(08).

      * COMMAREA for inter-program communication (COCOM01Y.cpy).
      * Carries user ID, program routing, and context flags.
       COPY COCOM01Y.
      *  CU00-specific extension: paging state and selection
          05 CDEMO-CU00-INFO.
      *     First user ID on current page (paging anchor)
             10 CDEMO-CU00-USRID-FIRST     PIC X(08).
      *     Last user ID on current page (paging anchor)
             10 CDEMO-CU00-USRID-LAST      PIC X(08).
      *     Current page number displayed to the user
             10 CDEMO-CU00-PAGE-NUM        PIC 9(08).
      *     Flag indicating more records exist after this page
             10 CDEMO-CU00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
                88 NEXT-PAGE-YES                     VALUE 'Y'.
                88 NEXT-PAGE-NO                      VALUE 'N'.
      *     Selection action entered by user (U=update, D=delete)
             10 CDEMO-CU00-USR-SEL-FLG     PIC X(01).
      *     User ID of the selected row for update/delete
             10 CDEMO-CU00-USR-SELECTED    PIC X(08).
      * BMS symbolic map for user list screen COUSR0A.
      * Defines input (COUSR0AI) and output (COUSR0AO) areas
      * with 10 rows of SEL/USRID/FNAME/LNAME/UTYPE fields.
       COPY COUSR00.

      * Application title and banner text (COTTL01Y.cpy).
      * Provides CCDA-TITLE01, CCDA-TITLE02 for screen header.
       COPY COTTL01Y.
      * Date/time working storage fields (CSDAT01Y.cpy).
      * Provides WS-CURDATE-DATA and formatted date/time views.
       COPY CSDAT01Y.
      * Common user message definitions (CSMSG01Y.cpy).
      * Provides CCDA-MSG-INVALID-KEY and CCDA-MSG-THANK-YOU.
       COPY CSMSG01Y.
      * 80-byte user security record layout (CSUSR01Y.cpy).
      * Defines SEC-USER-DATA with SEC-USR-ID (key), names,
      * password, type, and filler fields.
       COPY CSUSR01Y.

      * CICS attention identifier constants (DFHAID).
      * Provides DFHENTER, DFHPF3, DFHPF7, DFHPF8, etc.
       COPY DFHAID.
      * BMS attribute byte constants (DFHBMSCA).
      * Provides field attribute values for colors/highlights.
       COPY DFHBMSCA.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
       LINKAGE SECTION.
      * CICS passes the COMMAREA via DFHCOMMAREA. Length is
      * in EIBCALEN (0 on first entry, >0 on re-entry).
       01  DFHCOMMAREA.
         05  LK-COMMAREA                           PIC X(01)
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      * Main entry point. On first entry, perform forward page.
      * On re-entry, dispatch AID: Enter=select user, PF3=back,
      * PF7=page backward, PF8=page forward.
       MAIN-PARA.

      *    Initialize state flags for this interaction cycle
           SET ERR-FLG-OFF TO TRUE
           SET USER-SEC-NOT-EOF TO TRUE
           SET NEXT-PAGE-NO TO TRUE
           SET SEND-ERASE-YES TO TRUE

      *    Clear message areas on screen and in working storage
           MOVE SPACES TO WS-MESSAGE
                          ERRMSGO OF COUSR0AO

      *    Set cursor to the user ID search input field
           MOVE -1       TO USRIDINL OF COUSR0AI

      *    Check EIBCALEN: 0 means no COMMAREA (not routed
      *    here properly) so redirect to sign-on screen
           IF EIBCALEN = 0
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
               PERFORM RETURN-TO-PREV-SCREEN
           ELSE
      *        Copy COMMAREA from linkage into working storage
               MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
      *        First-time entry: display initial user list page
               IF NOT CDEMO-PGM-REENTER
                   SET CDEMO-PGM-REENTER    TO TRUE
                   MOVE LOW-VALUES          TO COUSR0AO
                   PERFORM PROCESS-ENTER-KEY
                   PERFORM SEND-USRLST-SCREEN
               ELSE
      *            Re-entry: receive user input and dispatch
      *            based on the AID key pressed
                   PERFORM RECEIVE-USRLST-SCREEN
                   EVALUATE EIBAID
                       WHEN DFHENTER
      *                    Process row selection or search
                           PERFORM PROCESS-ENTER-KEY
                       WHEN DFHPF3
      *                    PF3: return to admin menu COADM01C
                           MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                           PERFORM RETURN-TO-PREV-SCREEN
                       WHEN DFHPF7
      *                    PF7: page backward in user list
                           PERFORM PROCESS-PF7-KEY
                       WHEN DFHPF8
      *                    PF8: page forward in user list
                           PERFORM PROCESS-PF8-KEY
                       WHEN OTHER
      *                    Unrecognized key: show error message
                           MOVE 'Y'                       TO WS-ERR-FLG
                           MOVE -1       TO USRIDINL OF COUSR0AI
                           MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
                           PERFORM SEND-USRLST-SCREEN
                   END-EVALUATE
               END-IF
           END-IF

      *    Pseudo-conversational return: CICS suspends this
      *    task and re-invokes via transaction CU00 when the
      *    user presses a key, passing COMMAREA for state
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      PROCESS-ENTER-KEY
      *----------------------------------------------------------------*
      * Process ENTER key. Scans all 10 selection fields to
      * find a user row marked for action, then dispatches
      * U=update (COUSR02C) or D=delete (COUSR03C) via XCTL.
      * If no selection, uses the search filter field to set
      * the browse starting position and refreshes the list.
       PROCESS-ENTER-KEY.

      *    Scan the 10 row selection fields (SEL0001 - SEL0010)
      *    to find the first non-empty selection. Captures
      *    both the action flag (U or D) and the user ID.
           EVALUATE TRUE
               WHEN SEL0001I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0001I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID01I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0002I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0002I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID02I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0003I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0003I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID03I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0004I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0004I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID04I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0005I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0005I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID05I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0006I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0006I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID06I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0007I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0007I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID07I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0008I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0008I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID08I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0009I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0009I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID09I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN SEL0010I OF COUSR0AI NOT = SPACES AND LOW-VALUES
                   MOVE SEL0010I OF COUSR0AI TO CDEMO-CU00-USR-SEL-FLG
                   MOVE USRID10I OF COUSR0AI TO CDEMO-CU00-USR-SELECTED
               WHEN OTHER
      *            No row selected: clear selection state
                   MOVE SPACES   TO CDEMO-CU00-USR-SEL-FLG
                   MOVE SPACES   TO CDEMO-CU00-USR-SELECTED
           END-EVALUATE

      *    If a valid selection exists, route to the target
      *    program via EXEC CICS XCTL passing the COMMAREA
           IF (CDEMO-CU00-USR-SEL-FLG NOT = SPACES AND LOW-VALUES) AND
              (CDEMO-CU00-USR-SELECTED NOT = SPACES AND LOW-VALUES)
               EVALUATE CDEMO-CU00-USR-SEL-FLG
                   WHEN 'U'
                   WHEN 'u'
      *                U/u = Update: XCTL to COUSR02C
                        MOVE 'COUSR02C'   TO CDEMO-TO-PROGRAM
                        MOVE WS-TRANID    TO CDEMO-FROM-TRANID
                        MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
                        MOVE 0        TO CDEMO-PGM-CONTEXT
                        EXEC CICS
                            XCTL PROGRAM(CDEMO-TO-PROGRAM)
                            COMMAREA(CARDDEMO-COMMAREA)
                        END-EXEC
                   WHEN 'D'
                   WHEN 'd'
      *                D/d = Delete: XCTL to COUSR03C
                        MOVE 'COUSR03C'   TO CDEMO-TO-PROGRAM
                        MOVE WS-TRANID    TO CDEMO-FROM-TRANID
                        MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
                        MOVE 0        TO CDEMO-PGM-CONTEXT
                        EXEC CICS
                            XCTL PROGRAM(CDEMO-TO-PROGRAM)
                            COMMAREA(CARDDEMO-COMMAREA)
                        END-EXEC
                   WHEN OTHER
      *                Invalid selection character
                       MOVE
                       'Invalid selection. Valid values are U and D' TO
                                       WS-MESSAGE
                       MOVE -1       TO USRIDINL OF COUSR0AI
               END-EVALUATE
           END-IF

      *    Search filter: use the user ID input field to set
      *    the browse starting position. If blank, start from
      *    the beginning of the USRSEC file (LOW-VALUES key).
           IF USRIDINI OF COUSR0AI = SPACES OR LOW-VALUES
               MOVE LOW-VALUES TO SEC-USR-ID
           ELSE
               MOVE USRIDINI  OF COUSR0AI TO SEC-USR-ID
           END-IF

      *    Position cursor back to the search input field
           MOVE -1       TO USRIDINL OF COUSR0AI

      *    Reset page number and perform a fresh forward browse
           MOVE 0       TO CDEMO-CU00-PAGE-NUM
           PERFORM PROCESS-PAGE-FORWARD

      *    Clear the search input field on successful display
           IF NOT ERR-FLG-ON
               MOVE SPACE   TO USRIDINO  OF COUSR0AO
           END-IF.

      *----------------------------------------------------------------*
      *                      PROCESS-PF7-KEY
      *----------------------------------------------------------------*
      * Handle PF7 (page backward). Uses the first user ID on
      * the current page as the browse anchor and reads
      * backward to fill the previous page of results.
       PROCESS-PF7-KEY.

      *    Set browse key to first user ID on current page.
      *    If blank, start from beginning (LOW-VALUES).
           IF CDEMO-CU00-USRID-FIRST = SPACES OR LOW-VALUES
               MOVE LOW-VALUES TO SEC-USR-ID
           ELSE
               MOVE CDEMO-CU00-USRID-FIRST TO SEC-USR-ID
           END-IF

      *    Assume more pages exist ahead (for NEXT-PAGE flag)
           SET NEXT-PAGE-YES TO TRUE
           MOVE -1       TO USRIDINL OF COUSR0AI

      *    Only page backward if not already on page 1
           IF CDEMO-CU00-PAGE-NUM > 1
               PERFORM PROCESS-PAGE-BACKWARD
           ELSE
      *        Already at top: show informational message
               MOVE 'You are already at the top of the page...' TO
                               WS-MESSAGE
               SET SEND-ERASE-NO TO TRUE
               PERFORM SEND-USRLST-SCREEN
           END-IF.

      *----------------------------------------------------------------*
      *                      PROCESS-PF8-KEY
      *----------------------------------------------------------------*
      * Handle PF8 (page forward). Uses the last user ID on
      * the current page as the browse anchor and reads
      * forward to fill the next page of results.
       PROCESS-PF8-KEY.

      *    Set browse key to last user ID on current page.
      *    If blank, position to end (HIGH-VALUES).
           IF CDEMO-CU00-USRID-LAST = SPACES OR LOW-VALUES
               MOVE HIGH-VALUES TO SEC-USR-ID
           ELSE
               MOVE CDEMO-CU00-USRID-LAST TO SEC-USR-ID
           END-IF

           MOVE -1       TO USRIDINL OF COUSR0AI

      *    Only page forward if more records exist
           IF NEXT-PAGE-YES
               PERFORM PROCESS-PAGE-FORWARD
           ELSE
      *        Already at bottom: show informational message
               MOVE 'You are already at the bottom of the page...' TO
                               WS-MESSAGE
               SET SEND-ERASE-NO TO TRUE
               PERFORM SEND-USRLST-SCREEN
           END-IF.

      *----------------------------------------------------------------*
      *                      PROCESS-PAGE-FORWARD
      *----------------------------------------------------------------*
      * Browse USRSEC forward from the current position. Opens
      * a browse, reads up to 10 records via READNEXT, populates
      * screen rows, and checks for more pages via peek-ahead.
       PROCESS-PAGE-FORWARD.

      *    Open a browse cursor at the current key position
           PERFORM STARTBR-USER-SEC-FILE

           IF NOT ERR-FLG-ON

      *        Skip the anchor record on PF8 re-entry so the
      *        next page starts after the last displayed record
               IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3
                   PERFORM READNEXT-USER-SEC-FILE
               END-IF

      *        Clear all 10 screen rows before populating
               IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
               PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
                   PERFORM INITIALIZE-USER-DATA
               END-PERFORM
               END-IF

      *        Read up to 10 records to fill the screen rows
               MOVE 1             TO  WS-IDX

               PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR ERR-FLG-ON
                   PERFORM READNEXT-USER-SEC-FILE
                   IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
                       PERFORM POPULATE-USER-DATA
                       COMPUTE WS-IDX = WS-IDX + 1
                   END-IF
               END-PERFORM

      *        Peek-ahead: try reading one more record to
      *        determine if another page exists beyond this one
               IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
                   COMPUTE CDEMO-CU00-PAGE-NUM =
                           CDEMO-CU00-PAGE-NUM + 1
                   PERFORM READNEXT-USER-SEC-FILE
                   IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
                       SET NEXT-PAGE-YES TO TRUE
                   ELSE
                       SET NEXT-PAGE-NO TO TRUE
                   END-IF
               ELSE
      *            Partial page or empty: no more pages ahead
                   SET NEXT-PAGE-NO TO TRUE
                   IF WS-IDX > 1
                       COMPUTE CDEMO-CU00-PAGE-NUM = CDEMO-CU00-PAGE-NUM
                        + 1
                   END-IF
               END-IF

      *        Close the browse cursor
               PERFORM ENDBR-USER-SEC-FILE

      *        Update page number on screen and send the map
               MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI  OF COUSR0AI
               MOVE SPACE   TO USRIDINO  OF COUSR0AO
               PERFORM SEND-USRLST-SCREEN

           END-IF.

      *----------------------------------------------------------------*
      *                      PROCESS-PAGE-BACKWARD
      *----------------------------------------------------------------*
      * Browse USRSEC backward from the current position. Opens
      * a browse at the first user ID, reads up to 10 records
      * in reverse via READPREV, populates rows from bottom up,
      * and adjusts the page number accordingly.
       PROCESS-PAGE-BACKWARD.

      *    Open browse at the first user ID of current page
           PERFORM STARTBR-USER-SEC-FILE

           IF NOT ERR-FLG-ON

      *        Skip the anchor record on PF7 re-entry so the
      *        previous page ends before the first displayed ID
               IF EIBAID NOT = DFHENTER  AND DFHPF8
                   PERFORM READPREV-USER-SEC-FILE
               END-IF

      *        Clear all 10 screen rows before populating
               IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
               PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
                   PERFORM INITIALIZE-USER-DATA
               END-PERFORM
               END-IF

      *        Read up to 10 records backward, filling rows
      *        from position 10 down to 1 (reverse order)
               MOVE 10          TO  WS-IDX

               PERFORM UNTIL WS-IDX <= 0 OR USER-SEC-EOF OR ERR-FLG-ON
                   PERFORM READPREV-USER-SEC-FILE
                   IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
                       PERFORM POPULATE-USER-DATA
                       COMPUTE WS-IDX = WS-IDX - 1
                   END-IF
               END-PERFORM

      *        Peek-ahead backward: check if more records
      *        exist before this page to set page number
               IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
               PERFORM READPREV-USER-SEC-FILE
               IF NEXT-PAGE-YES
                   IF USER-SEC-NOT-EOF AND ERR-FLG-OFF AND
                       CDEMO-CU00-PAGE-NUM > 1
                       SUBTRACT 1 FROM CDEMO-CU00-PAGE-NUM
                   ELSE
                       MOVE 1 TO CDEMO-CU00-PAGE-NUM
                   END-IF
               END-IF
               END-IF

      *        Close the browse cursor
               PERFORM ENDBR-USER-SEC-FILE

      *        Update page number on screen and send the map
               MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI  OF COUSR0AI
               PERFORM SEND-USRLST-SCREEN

           END-IF.

      *----------------------------------------------------------------*
      *                      POPULATE-USER-DATA
      *----------------------------------------------------------------*
      * Map USRSEC record fields (user ID, first name, last
      * name, type) into the appropriate screen row based on
      * the current row index (WS-IDX). Row 1 also captures
      * USRID-FIRST; row 10 also captures USRID-LAST for
      * paging anchor state in the COMMAREA.
       POPULATE-USER-DATA.

      *    Dispatch to the correct row based on WS-IDX
           EVALUATE WS-IDX
               WHEN 1
      *            Row 1: also save first user ID for paging
                   MOVE SEC-USR-ID    TO USRID01I OF COUSR0AI
                                         CDEMO-CU00-USRID-FIRST
                   MOVE SEC-USR-FNAME TO FNAME01I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME01I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE01I OF COUSR0AI
               WHEN 2
                   MOVE SEC-USR-ID    TO USRID02I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME02I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME02I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE02I OF COUSR0AI
               WHEN 3
                   MOVE SEC-USR-ID    TO USRID03I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME03I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME03I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE03I OF COUSR0AI
               WHEN 4
                   MOVE SEC-USR-ID    TO USRID04I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME04I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME04I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE04I OF COUSR0AI
               WHEN 5
                   MOVE SEC-USR-ID    TO USRID05I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME05I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME05I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE05I OF COUSR0AI
               WHEN 6
                   MOVE SEC-USR-ID    TO USRID06I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME06I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME06I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE06I OF COUSR0AI
               WHEN 7
                   MOVE SEC-USR-ID    TO USRID07I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME07I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME07I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE07I OF COUSR0AI
               WHEN 8
                   MOVE SEC-USR-ID    TO USRID08I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME08I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME08I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE08I OF COUSR0AI
               WHEN 9
                   MOVE SEC-USR-ID    TO USRID09I OF COUSR0AI
                   MOVE SEC-USR-FNAME TO FNAME09I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME09I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE09I OF COUSR0AI
               WHEN 10
      *            Row 10: also save last user ID for paging
                   MOVE SEC-USR-ID    TO USRID10I OF COUSR0AI
                                         CDEMO-CU00-USRID-LAST
                   MOVE SEC-USR-FNAME TO FNAME10I OF COUSR0AI
                   MOVE SEC-USR-LNAME TO LNAME10I OF COUSR0AI
                   MOVE SEC-USR-TYPE  TO UTYPE10I OF COUSR0AI
               WHEN OTHER
                   CONTINUE
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      INITIALIZE-USER-DATA
      *----------------------------------------------------------------*
      * Clear a single screen row (user ID, first name, last
      * name, type) at the row index WS-IDX. Ensures stale
      * data from a prior page does not appear on screen.
       INITIALIZE-USER-DATA.

           EVALUATE WS-IDX
               WHEN 1
                   MOVE SPACES TO USRID01I OF COUSR0AI
                   MOVE SPACES TO FNAME01I OF COUSR0AI
                   MOVE SPACES TO LNAME01I OF COUSR0AI
                   MOVE SPACES TO UTYPE01I OF COUSR0AI
               WHEN 2
                   MOVE SPACES TO USRID02I OF COUSR0AI
                   MOVE SPACES TO FNAME02I OF COUSR0AI
                   MOVE SPACES TO LNAME02I OF COUSR0AI
                   MOVE SPACES TO UTYPE02I OF COUSR0AI
               WHEN 3
                   MOVE SPACES TO USRID03I OF COUSR0AI
                   MOVE SPACES TO FNAME03I OF COUSR0AI
                   MOVE SPACES TO LNAME03I OF COUSR0AI
                   MOVE SPACES TO UTYPE03I OF COUSR0AI
               WHEN 4
                   MOVE SPACES TO USRID04I OF COUSR0AI
                   MOVE SPACES TO FNAME04I OF COUSR0AI
                   MOVE SPACES TO LNAME04I OF COUSR0AI
                   MOVE SPACES TO UTYPE04I OF COUSR0AI
               WHEN 5
                   MOVE SPACES TO USRID05I OF COUSR0AI
                   MOVE SPACES TO FNAME05I OF COUSR0AI
                   MOVE SPACES TO LNAME05I OF COUSR0AI
                   MOVE SPACES TO UTYPE05I OF COUSR0AI
               WHEN 6
                   MOVE SPACES TO USRID06I OF COUSR0AI
                   MOVE SPACES TO FNAME06I OF COUSR0AI
                   MOVE SPACES TO LNAME06I OF COUSR0AI
                   MOVE SPACES TO UTYPE06I OF COUSR0AI
               WHEN 7
                   MOVE SPACES TO USRID07I OF COUSR0AI
                   MOVE SPACES TO FNAME07I OF COUSR0AI
                   MOVE SPACES TO LNAME07I OF COUSR0AI
                   MOVE SPACES TO UTYPE07I OF COUSR0AI
               WHEN 8
                   MOVE SPACES TO USRID08I OF COUSR0AI
                   MOVE SPACES TO FNAME08I OF COUSR0AI
                   MOVE SPACES TO LNAME08I OF COUSR0AI
                   MOVE SPACES TO UTYPE08I OF COUSR0AI
               WHEN 9
                   MOVE SPACES TO USRID09I OF COUSR0AI
                   MOVE SPACES TO FNAME09I OF COUSR0AI
                   MOVE SPACES TO LNAME09I OF COUSR0AI
                   MOVE SPACES TO UTYPE09I OF COUSR0AI
               WHEN 10
                   MOVE SPACES TO USRID10I OF COUSR0AI
                   MOVE SPACES TO FNAME10I OF COUSR0AI
                   MOVE SPACES TO LNAME10I OF COUSR0AI
                   MOVE SPACES TO UTYPE10I OF COUSR0AI
               WHEN OTHER
                   CONTINUE
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      RETURN-TO-PREV-SCREEN
      *----------------------------------------------------------------*
      * Transfer control to the previous screen via EXEC CICS
      * XCTL, passing the COMMAREA. Defaults to sign-on
      * (COSGN00C) if no target program is set.
       RETURN-TO-PREV-SCREEN.

      *    Safety check: default to sign-on if target is empty
           IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
               MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
           END-IF
      *    Record this program as the source for the target
           MOVE WS-TRANID    TO CDEMO-FROM-TRANID
           MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
      *    Reset context to initial-entry state
           MOVE ZEROS        TO CDEMO-PGM-CONTEXT
      *    XCTL transfers control; this program does not
      *    receive control back after this call
           EXEC CICS
               XCTL PROGRAM(CDEMO-TO-PROGRAM)
               COMMAREA(CARDDEMO-COMMAREA)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      SEND-USRLST-SCREEN
      *----------------------------------------------------------------*
      * Populate header fields and send BMS map COUSR0A to
      * the terminal. Uses ERASE to clear the screen on full
      * page redraws; omits ERASE for in-place updates (e.g.,
      * error messages on unsupported key press).
       SEND-USRLST-SCREEN.

      *    Fill title, transaction, program, date, and time
           PERFORM POPULATE-HEADER-INFO

      *    Copy message text to the screen error/info line
           MOVE WS-MESSAGE TO ERRMSGO OF COUSR0AO

      *    Full redraw: ERASE clears the terminal before send
           IF SEND-ERASE-YES
               EXEC CICS SEND
                         MAP('COUSR0A')
                         MAPSET('COUSR00')
                         FROM(COUSR0AO)
                         ERASE
                         CURSOR
               END-EXEC
           ELSE
      *        Partial update: send without ERASE to preserve
      *        existing screen content (only changes refresh)
               EXEC CICS SEND
                         MAP('COUSR0A')
                         MAPSET('COUSR00')
                         FROM(COUSR0AO)
      *                  ERASE
                         CURSOR
               END-EXEC
           END-IF.

      *----------------------------------------------------------------*
      *                      RECEIVE-USRLST-SCREEN
      *----------------------------------------------------------------*
      * Receive user input from BMS map COUSR0A into the
      * symbolic input area COUSR0AI. RESP/RESP2 capture
      * any receive errors (e.g., MAPFAIL if no data sent).
       RECEIVE-USRLST-SCREEN.

      *    Read terminal input into the symbolic map buffer
           EXEC CICS RECEIVE
                     MAP('COUSR0A')
                     MAPSET('COUSR00')
                     INTO(COUSR0AI)
                     RESP(WS-RESP-CD)
                     RESP2(WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                      POPULATE-HEADER-INFO
      *----------------------------------------------------------------*
      * Fill screen header: application titles from COTTL01Y,
      * transaction name, program name, and current date/time
      * formatted via CSDAT01Y fields.
       POPULATE-HEADER-INFO.

      *    Retrieve system date/time into CSDAT01Y structure
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA

      *    Set application banner titles from COTTL01Y copybook
           MOVE CCDA-TITLE01           TO TITLE01O OF COUSR0AO
           MOVE CCDA-TITLE02           TO TITLE02O OF COUSR0AO
      *    Display transaction ID and program name in header
           MOVE WS-TRANID              TO TRNNAMEO OF COUSR0AO
           MOVE WS-PGMNAME             TO PGMNAMEO OF COUSR0AO

      *    Format date as MM/DD/YY for screen display
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY

           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COUSR0AO

      *    Format time as HH:MM:SS for screen display
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS

           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COUSR0AO.

      *----------------------------------------------------------------*
      *                      STARTBR-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Start a browse on the USRSEC VSAM KSDS from the key
      * in SEC-USR-ID. Positions the cursor at or after the
      * given key. Handles NORMAL, NOTFND, and OTHER RESP.
       STARTBR-USER-SEC-FILE.

      *    Open browse cursor at the key in SEC-USR-ID.
      *    GTEQ is commented out; default is GTEQ positioning.
           EXEC CICS STARTBR
                DATASET   (WS-USRSEC-FILE)
                RIDFLD    (SEC-USR-ID)
                KEYLENGTH (LENGTH OF SEC-USR-ID)
      *         GTEQ
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.

      *    Evaluate CICS RESP code from the STARTBR
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      *            Browse opened successfully at the key
                   CONTINUE
               WHEN DFHRESP(NOTFND)
      *            Key not found: signal EOF and inform user
                   CONTINUE
                   SET USER-SEC-EOF TO TRUE
                   MOVE 'You are at the top of the page...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR0AI
                   PERFORM SEND-USRLST-SCREEN
               WHEN OTHER
      *            Unexpected error: log RESP codes, set error
      *            flag, and display error message
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR0AI
                   PERFORM SEND-USRLST-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      READNEXT-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Read the next sequential record from the USRSEC
      * browse into SEC-USER-DATA (see CSUSR01Y.cpy).
      * Handles NORMAL, ENDFILE, and OTHER RESP codes.
       READNEXT-USER-SEC-FILE.

      *    Read next record; RIDFLD updates with the key read
           EXEC CICS READNEXT
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (SEC-USR-ID)
                KEYLENGTH (LENGTH OF SEC-USR-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.

      *    Evaluate CICS RESP code from the READNEXT
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      *            Record read successfully into SEC-USER-DATA
                   CONTINUE
               WHEN DFHRESP(ENDFILE)
      *            End of file reached: signal EOF to caller
                   CONTINUE
                   SET USER-SEC-EOF TO TRUE
                   MOVE 'You have reached the bottom of the page...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR0AI
                   PERFORM SEND-USRLST-SCREEN
               WHEN OTHER
      *            Unexpected error: log and display error
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR0AI
                   PERFORM SEND-USRLST-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      READPREV-USER-SEC-FILE
      *----------------------------------------------------------------*
      * Read the previous record from the USRSEC browse into
      * SEC-USER-DATA (see CSUSR01Y.cpy). Used by the
      * backward paging logic to fill rows in reverse order.
      * Handles NORMAL, ENDFILE, and OTHER RESP codes.
       READPREV-USER-SEC-FILE.

      *    Read previous record; RIDFLD updates with key read
           EXEC CICS READPREV
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (SEC-USR-ID)
                KEYLENGTH (LENGTH OF SEC-USR-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.

      *    Evaluate CICS RESP code from the READPREV
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
      *            Record read successfully into SEC-USER-DATA
                   CONTINUE
               WHEN DFHRESP(ENDFILE)
      *            Beginning of file reached: signal EOF
                   CONTINUE
                   SET USER-SEC-EOF TO TRUE
                   MOVE 'You have reached the top of the page...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR0AI
                   PERFORM SEND-USRLST-SCREEN
               WHEN OTHER
      *            Unexpected error: log and display error
                   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                   MOVE 'Y'     TO WS-ERR-FLG
                   MOVE 'Unable to lookup User...' TO
                                   WS-MESSAGE
                   MOVE -1       TO USRIDINL OF COUSR0AI
                   PERFORM SEND-USRLST-SCREEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      ENDBR-USER-SEC-FILE
      *----------------------------------------------------------------*
      * End the USRSEC file browse session. Releases the
      * browse cursor opened by STARTBR-USER-SEC-FILE.
       ENDBR-USER-SEC-FILE.

      *    Close the browse cursor on the USRSEC dataset
           EXEC CICS ENDBR
                DATASET   (WS-USRSEC-FILE)
           END-EXEC.
      
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:34 CDT
      *
