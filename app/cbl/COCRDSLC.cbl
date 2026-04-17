      *****************************************************************         
      * Program:     COCRDSLC.CBL                                     *         
      * Layer:       Business logic                                   *         
      * Function:    Accept and process credit card detail request    *         
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
      * Program:     COCRDSLC
      * Transaction: CCDL
      * BMS Map:     COCRDSL / CCRDSL
      * Function:    Card detail (view-only) screen. Reads CARDDAT VSAM
      *              KSDS by card number to display card details. May
      *              also look up by account ID using the card-file
      *              alternate index (ACCTID path). Validates account
      *              and card number inputs before reading.
      * Files:       CARDDAT (READ), CARDAIX (READ via ACCTID path)
      * Navigation:  PF3 returns to calling program (usually COCRDLIC).
      *              Enter re-displays after processing input criteria.
      *================================================================*
                                                                                
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.                                                              
           COCRDSLC.                                                            
       DATE-WRITTEN.                                                            
           April 2022.                                                          
       DATE-COMPILED.                                                           
           Today.                                                               
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
                                                                                
       DATA DIVISION.                                                           
                                                                                
       WORKING-STORAGE SECTION.                                                 
       01  WS-MISC-STORAGE.                                                     
      * WS-MISC-STORAGE aggregates all local working variables
      * for CICS processing, input validation, screen output,
      * file I/O keys, and user-facing messages.
      ******************************************************************        
      * General CICS related                                                    
      ******************************************************************        
         05 WS-CICS-PROCESSNG-VARS.                                             
      * WS-RESP-CD / WS-REAS-CD capture CICS RESP/RESP2
      * return codes. WS-TRANID stores this program's
      * transaction ID (CCDL).
            07 WS-RESP-CD                          PIC S9(09) COMP              
                                                   VALUE ZEROS.                 
            07 WS-REAS-CD                          PIC S9(09) COMP              
                                                   VALUE ZEROS.                 
            07 WS-TRANID                           PIC X(4)                     
                                                   VALUE SPACES.                
      ******************************************************************        
      *      Input edits                                                        
      ******************************************************************        
                                                                                
      * WS-INPUT-FLAG tracks overall validation outcome.
      * INPUT-OK='0', INPUT-ERROR='1', PENDING=LOW-VALUES.
         05  WS-INPUT-FLAG                         PIC X(1).                    
           88  INPUT-OK                            VALUE '0'.                   
           88  INPUT-ERROR                         VALUE '1'.                   
           88  INPUT-PENDING                       VALUE LOW-VALUES.            
         05  WS-EDIT-ACCT-FLAG                     PIC X(1).                    
           88  FLG-ACCTFILTER-NOT-OK               VALUE '0'.                   
           88  FLG-ACCTFILTER-ISVALID              VALUE '1'.                   
           88  FLG-ACCTFILTER-BLANK                VALUE ' '.                   
      * WS-EDIT-CARD-FLAG mirrors the account flag for
      * the 16-digit card number field.
         05  WS-EDIT-CARD-FLAG                     PIC X(1).                    
           88  FLG-CARDFILTER-NOT-OK               VALUE '0'.                   
           88  FLG-CARDFILTER-ISVALID             VALUE '1'.                    
           88  FLG-CARDFILTER-BLANK                VALUE ' '.                   
         05  WS-RETURN-FLAG                        PIC X(1).                    
           88  WS-RETURN-FLAG-OFF                  VALUE LOW-VALUES.            
           88  WS-RETURN-FLAG-ON                   VALUE '1'.                   
         05  WS-PFK-FLAG                           PIC X(1).                    
           88  PFK-VALID                           VALUE '0'.                   
           88  PFK-INVALID                         VALUE '1'.                   
      * Only ENTER and PF3 are valid AID keys for this
      * screen. Any other key defaults to ENTER.
      ******************************************************************        
      * Output edits                                                            
      ******************************************************************        
         05 CICS-OUTPUT-EDIT-VARS.                                              
      * Output edit variables hold numeric-to-display
      * conversions for card fields. REDEFINES clauses
      * allow both alphanumeric and numeric access.
      * Expiration date splits into year/month/day.
           10  CARD-ACCT-ID-X                      PIC X(11).                   
           10  CARD-ACCT-ID-N REDEFINES CARD-ACCT-ID-X                          
                                                   PIC 9(11).                   
           10  CARD-CVV-CD-X                       PIC X(03).                   
           10  CARD-CVV-CD-N REDEFINES  CARD-CVV-CD-X                           
                                                   PIC 9(03).                   
           10  CARD-CARD-NUM-X                     PIC X(16).                   
           10  CARD-CARD-NUM-N REDEFINES  CARD-CARD-NUM-X                       
                                                   PIC 9(16).                   
           10  CARD-NAME-EMBOSSED-X                PIC X(50).                   
           10  CARD-STATUS-X                       PIC X.                       
           10  CARD-EXPIRAION-DATE-X               PIC X(10).                   
           10  FILLER REDEFINES CARD-EXPIRAION-DATE-X.                          
               20 CARD-EXPIRY-YEAR                 PIC X(4).                    
               20 FILLER                           PIC X(1).                    
               20 CARD-EXPIRY-MONTH                PIC X(2).                    
               20 FILLER                           PIC X(1).                    
               20 CARD-EXPIRY-DAY                  PIC X(2).                    
           10  CARD-EXPIRAION-DATE-N REDEFINES                                  
               CARD-EXPIRAION-DATE-X               PIC 9(10).                   
                                                                                
      ******************************************************************        
      *      File and data Handling                                             
      ******************************************************************        
         05  WS-CARD-RID.                                                       
      * WS-CARD-RID is the composite RIDFLD for CICS READ.
      * CARDNUM (16 bytes) = primary key for CARDDAT;
      * ACCT-ID (11 bytes) = alternate index key.
           10  WS-CARD-RID-CARDNUM                 PIC X(16).                   
           10  WS-CARD-RID-ACCT-ID                 PIC 9(11).                   
           10  WS-CARD-RID-ACCT-ID-X REDEFINES                                  
                  WS-CARD-RID-ACCT-ID              PIC X(11).                   
         05  WS-FILE-ERROR-MESSAGE.                                             
      * WS-FILE-ERROR-MESSAGE builds: 'File Error: <op>
      * on <file> returned RESP <code>,RESP2 <code>'.
           10  FILLER                              PIC X(12)                    
                                                   VALUE 'File Error: '.        
           10  ERROR-OPNAME                        PIC X(8)                     
                                                   VALUE SPACES.                
           10  FILLER                              PIC X(4)                     
                                                   VALUE ' on '.                
           10  ERROR-FILE                          PIC X(9)                     
                                                   VALUE SPACES.                
           10  FILLER                              PIC X(15)                    
                                                   VALUE                        
                                                   ' returned RESP '.           
           10  ERROR-RESP                          PIC X(10)                    
                                                   VALUE SPACES.                
           10  FILLER                              PIC X(7)                     
                                                   VALUE ',RESP2 '.             
           10  ERROR-RESP2                         PIC X(10)                    
                                                   VALUE SPACES.                
          10  FILLER                               PIC X(5)                     
                                                   VALUE SPACES.                
      ******************************************************************        
      *      Output Message Construction                                        
      ******************************************************************        
         05  WS-LONG-MSG                           PIC X(500).                  
      * WS-LONG-MSG: debug text buffer for SEND TEXT.
         05  WS-INFO-MSG                           PIC X(40).                   
           88  WS-NO-INFO-MESSAGE                 VALUES                        
                                                  SPACES LOW-VALUES.            
           88  FOUND-CARDS-FOR-ACCOUNT             VALUE                        
               '   Displaying requested details'.                               
           88  WS-PROMPT-FOR-INPUT                 VALUE                        
               'Please enter Account and Card Number'.                          
                                                                                
      * WS-RETURN-MSG holds error/status text for the
      * screen error line (ERRMSG row 23). 88-level
      * conditions set predefined messages for each
      * validation failure and lookup outcome.
         05  WS-RETURN-MSG                         PIC X(75).                   
           88  WS-RETURN-MSG-OFF                   VALUE SPACES.                
           88  WS-EXIT-MESSAGE                     VALUE                        
               'PF03 pressed.Exiting              '.                            
           88  WS-PROMPT-FOR-ACCT                  VALUE                        
               'Account number not provided'.                                   
           88  WS-PROMPT-FOR-CARD                  VALUE                        
               'Card number not provided'.                                      
           88  NO-SEARCH-CRITERIA-RECEIVED         VALUE                        
               'No input received'.                                             
           88  SEARCHED-ACCT-ZEROES                VALUE                        
               'Account number must be a non zero 11 digit number'.             
           88  SEARCHED-ACCT-NOT-NUMERIC           VALUE                        
               'Account number must be a non zero 11 digit number'.             
           88  SEARCHED-CARD-NOT-NUMERIC           VALUE                        
               'Card number if supplied must be a 16 digit number'.             
                                                                                
           88  DID-NOT-FIND-ACCT-IN-CARDXREF       VALUE                        
               'Did not find this account in cards database'.                   
           88  DID-NOT-FIND-ACCTCARD-COMBO         VALUE                        
               'Did not find cards for this search condition'.                  
           88  XREF-READ-ERROR                     VALUE                        
               'Error reading Card Data File'.                                  
           88  CODING-TO-BE-DONE                   VALUE                        
               'Looks Good.... so far'.                                         
      ******************************************************************        
      *      Literals and Constants                                             
      ******************************************************************        
       01 WS-LITERALS.                                                          
      * WS-LITERALS holds compile-time constants for
      * this program (COCRDSLC/CCDL), its BMS map
      * (COCRDSL/CCRDSLA), card list (COCRDLIC/CCLI),
      * main menu (COMEN01C/CM00), and VSAM files
      * (CARDDAT, CARDAIX).
          05 LIT-THISPGM                           PIC X(8)                     
                                                   VALUE 'COCRDSLC'.            
          05 LIT-THISTRANID                        PIC X(4)                     
                                                   VALUE 'CCDL'.                
          05 LIT-THISMAPSET                        PIC X(8)                     
                                                   VALUE 'COCRDSL '.            
          05 LIT-THISMAP                           PIC X(7)                     
                                                   VALUE 'CCRDSLA'.             
          05 LIT-CCLISTPGM                         PIC X(8)                     
                                                   VALUE 'COCRDLIC'.            
          05 LIT-CCLISTTRANID                      PIC X(4)                     
                                                   VALUE 'CCLI'.                
          05 LIT-CCLISTMAPSET                      PIC X(7)                     
                                                   VALUE 'COCRDLI'.             
          05 LIT-CCLISTMAP                         PIC X(7)                     
                                                   VALUE 'CCRDSLA'.             
          05 LIT-MENUPGM                           PIC X(8)                     
                                                   VALUE 'COMEN01C'.            
          05 LIT-MENUTRANID                        PIC X(4)                     
                                                   VALUE 'CM00'.                
          05 LIT-MENUMAPSET                        PIC X(7)                     
                                                   VALUE 'COMEN01'.             
          05 LIT-MENUMAP                           PIC X(7)                     
                                                   VALUE 'COMEN1A'.             
          05 LIT-CARDFILENAME                      PIC X(8)                     
                                                   VALUE 'CARDDAT '.            
          05 LIT-CARDFILENAME-ACCT-PATH            PIC X(8)                     
                                                   VALUE 'CARDAIX '.            
      ******************************************************************        
      *Other common working storage Variables                                   
      ******************************************************************        
       COPY CVCRD01Y.                                                           
      * CVCRD01Y: card work area with AID key flags
      * (CCARD-AID-*), navigation fields, and
      * account/card filter fields (CC-ACCT-ID,
      * CC-CARD-NUM). See app/cpy/CVCRD01Y.cpy
                                                                                
      ******************************************************************        
      *Application Commmarea Copybook                                           
       COPY COCOM01Y.                                                           
      * COCOM01Y: CARDDEMO-COMMAREA with routing
      * fields (FROM/TO program/tranid), user ID,
      * PGM-CONTEXT flag, and account/card carry-
      * forward. See app/cpy/COCOM01Y.cpy
                                                                                
       01 WS-THIS-PROGCOMMAREA.                                                 
          05 CA-CALL-CONTEXT.                                                   
             10 CA-FROM-PROGRAM                    PIC X(08).                   
             10 CA-FROM-TRANID                     PIC X(04).                   
      * WS-THIS-PROGCOMMAREA holds local call context
      * (calling program and transaction) appended
      * beyond the shared CARDDEMO-COMMAREA.
                                                                                
       01  WS-COMMAREA                             PIC X(2000).                 
      * WS-COMMAREA: 2000-byte buffer passed on
      * CICS RETURN. Concatenates CARDDEMO-COMMAREA
      * and WS-THIS-PROGCOMMAREA.
                                                                                
      *IBM SUPPLIED COPYBOOKS                                                   
       COPY DFHBMSCA.                                                           
       COPY DFHAID.                                                             
      * DFHBMSCA: BMS attribute constants (DFHBMPRF,
      * DFHBMFSE, DFHRED, DFHDFCOL, DFHBMDAR, etc.).
      * DFHAID: AID byte values for EIBAID mapping.
                                                                                
      *COMMON COPYBOOKS                                                         
      *Screen Titles                                                            
       COPY COTTL01Y.                                                           
      * COTTL01Y: CCDA-TITLE01/02 banner text.
      * See app/cpy/COTTL01Y.cpy
      *Credit Card Search Screen Layout                                         
       COPY COCRDSL.                                                            
      * COCRDSL: symbolic BMS map from COCRDSL.bms.
      * Defines CCRDSLAI (input), CCRDSLAO (output)
      * with ACCTSID, CARDSID, CRDNAME, CRDSTCD,
      * EXPMON, EXPYEAR, ERRMSG, INFOMSG fields.
                                                                                
      *Current Date                                                             
       COPY CSDAT01Y.                                                           
      * CSDAT01Y: date/time working storage for the
      * screen header. See app/cpy/CSDAT01Y.cpy
                                                                                
      *Common Messages                                                          
       COPY CSMSG01Y.                                                           
      * CSMSG01Y: common messages (thank-you, invalid
      * key). See app/cpy/CSMSG01Y.cpy
                                                                                
      *Abend Variables                                                          
       COPY CSMSG02Y.                                                           
      * CSMSG02Y: abend data work area (ABEND-MSG,
      * ABEND-CULPRIT, ABEND-CODE) for the handler.
                                                                                
      *Signed on user data                                                      
       COPY CSUSR01Y.                                                           
      * CSUSR01Y: signed-on user security record.
                                                                                
      *Dataset layouts                                                          
      *ACCOUNT RECORD LAYOUT                                                    
      *COPY CVACT01Y.                                                           
                                                                                
      *CARD RECORD LAYOUT                                                       
       COPY CVACT02Y.                                                           
      * CVACT02Y: 150-byte CARD-RECORD — CARD-NUM(16),
      * CARD-ACCT-ID(11), CARD-CVV-CD(3),
      * CARD-EMBOSSED-NAME(50), CARD-EXPIRAION-DATE(10),
      * CARD-ACTIVE-STATUS(1). See app/cpy/CVACT02Y.cpy
                                                                                
      *CARD XREF LAYOUT                                                         
      *COPY CVACT03Y.                                                           
                                                                                
      *CUSTOMER LAYOUT                                                          
       COPY CVCUS01Y.                                                           
      * CVCUS01Y: 500-byte customer record layout.
      * Included for potential cross-entity lookups.
                                                                                
      *----------------------------------------------------------------*
      * LINKAGE SECTION: receives DFHCOMMAREA from CICS.
      * Variable-length FILLER accepts up to 32767 bytes,
      * sized by EIBCALEN at runtime.
      *----------------------------------------------------------------*
       LINKAGE SECTION.                                                         
       01  DFHCOMMAREA.                                                         
         05  FILLER                                PIC X(1)                     
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.                     
                                                                                
      *================================================================*
      * PROCEDURE DIVISION — pseudo-conversational flow.
      * Each user interaction triggers a new CICS task.
      * COMMAREA preserves state between interactions.
      * Main EVALUATE routes on AID key and PGM-CONTEXT.
      *================================================================*
       PROCEDURE DIVISION.                                                      
       0000-MAIN.                                                               
      * 0000-MAIN: entry point for each task invocation.
      * Registers abend handler, clears work areas,
      * restores COMMAREA, maps PF keys, validates AID,
      * and routes to the appropriate processing path.
                                                                                
           EXEC CICS HANDLE ABEND                                               
                     LABEL(ABEND-ROUTINE)                                       
           END-EXEC                                                             
      * Registers ABEND-ROUTINE as the abend handler
      * for unexpected failures during this task.
                                                                                
           INITIALIZE CC-WORK-AREA                                              
                      WS-MISC-STORAGE                                           
                      WS-COMMAREA                                               
      * Clears card work area, misc storage, and the
      * return COMMAREA to remove residual data.
      *****************************************************************         
      * Store our context                                                       
      *****************************************************************         
           MOVE LIT-THISTRANID       TO WS-TRANID                               
      *****************************************************************         
      * Ensure error message is cleared                               *         
      *****************************************************************         
           SET WS-RETURN-MSG-OFF  TO TRUE                                       
      *****************************************************************         
      * Store passed data if  any                *                              
      *****************************************************************         
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
      * If first invocation or from main menu without
      * reenter context, clears COMMAREA. Otherwise
      * restores the shared CARDDEMO-COMMAREA and local
      * call-context from DFHCOMMAREA passed by CICS.
      *****************************************************************         
      * Remap PFkeys as needed.                                                 
      * Store the Mapped PF Key                                                 
      *****************************************************************         
           PERFORM YYYY-STORE-PFKEY                                             
              THRU YYYY-STORE-PFKEY-EXIT                                        
      * YYYY-STORE-PFKEY (CSSTRPFY copybook) maps the
      * EIBAID byte to CCARD-AID-* 88-level conditions.
      *****************************************************************         
      * Check the AID to see if its valid at this point               *         
      * F3 - Exit                                                               
      * Enter show screen again                                                 
      *****************************************************************         
           SET PFK-INVALID TO TRUE                                              
           IF CCARD-AID-ENTER OR                                                
              CCARD-AID-PFK03                                                   
              SET PFK-VALID TO TRUE                                             
           END-IF                                                               
                                                                                
           IF PFK-INVALID                                                       
              SET CCARD-AID-ENTER TO TRUE                                       
           END-IF                                                               
      * Unrecognized AID key defaults to ENTER behavior
      * to prevent navigation errors.
                                                                                
      *****************************************************************         
      * Decide what to do based on inputs received                              
      *****************************************************************         
           EVALUATE TRUE                                                        
              WHEN CCARD-AID-PFK03                                              
      * PF3: transfers control back to the calling
      * program via XCTL. Defaults to main menu if
      * no caller is recorded in COMMAREA.
      ******************************************************************        
      *            XCTL TO CALLING PROGRAM OR MAIN MENU                         
      ******************************************************************        
                   IF CDEMO-FROM-TRANID    EQUAL LOW-VALUES                     
                   OR CDEMO-FROM-TRANID    EQUAL SPACES                         
                      MOVE LIT-MENUTRANID  TO CDEMO-TO-TRANID                   
                   ELSE                                                         
                      MOVE CDEMO-FROM-TRANID  TO CDEMO-TO-TRANID                
                   END-IF                                                       
                                                                                
                   IF CDEMO-FROM-PROGRAM   EQUAL LOW-VALUES                     
                   OR CDEMO-FROM-PROGRAM   EQUAL SPACES                         
                      MOVE LIT-MENUPGM     TO CDEMO-TO-PROGRAM                  
                   ELSE                                                         
                      MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM               
                   END-IF                                                       
                                                                                
                   MOVE LIT-THISTRANID     TO CDEMO-FROM-TRANID                 
                   MOVE LIT-THISPGM        TO CDEMO-FROM-PROGRAM                
                                                                                
                   SET  CDEMO-USRTYP-USER  TO TRUE                              
                   SET  CDEMO-PGM-ENTER    TO TRUE                              
                   MOVE LIT-THISMAPSET     TO CDEMO-LAST-MAPSET                 
                   MOVE LIT-THISMAP        TO CDEMO-LAST-MAP                    
      *                                                                         
                   EXEC CICS XCTL                                               
                             PROGRAM (CDEMO-TO-PROGRAM)                         
                             COMMAREA(CARDDEMO-COMMAREA)                        
                   END-EXEC                                                     
      * EXEC CICS XCTL transfers control with COMMAREA
      * to the target program. This task ends.
      ******************************************************************        
      *            COMING FROM CREDIT CARD LIST SCREEN                          
      *            SELECTION CRITERIA ALREADY VALIDATED                         
      ******************************************************************        
              WHEN CDEMO-PGM-ENTER                                              
               AND CDEMO-FROM-PROGRAM  EQUAL LIT-CCLISTPGM                      
      * From card list (COCRDLIC): account and card
      * numbers are pre-validated. Copies them from
      * COMMAREA, reads card record, sends the map.
                   SET INPUT-OK TO TRUE                                         
                   MOVE CDEMO-ACCT-ID       TO CC-ACCT-ID-N                     
                   MOVE CDEMO-CARD-NUM      TO CC-CARD-NUM-N                    
                   PERFORM 9000-READ-DATA                                       
                      THRU 9000-READ-DATA-EXIT                                  
                   PERFORM 1000-SEND-MAP                                        
                     THRU 1000-SEND-MAP-EXIT                                    
                   GO TO COMMON-RETURN                                          
              WHEN CDEMO-PGM-ENTER                                              
      ******************************************************************        
      *            COMING FROM SOME OTHER CONTEXT                               
      *            SELECTION CRITERIA TO BE GATHERED                            
      ******************************************************************        
                   PERFORM 1000-SEND-MAP THRU                                   
                           1000-SEND-MAP-EXIT                                   
                   GO TO COMMON-RETURN                                          
      * From another context without pre-set criteria:
      * sends a blank search form for user input.
              WHEN CDEMO-PGM-REENTER                                            
      * Re-enter: user submitted search criteria.
      * Processes inputs, reads data if valid, and
      * re-displays screen with results or errors.
                   PERFORM 2000-PROCESS-INPUTS                                  
                      THRU 2000-PROCESS-INPUTS-EXIT                             
                   IF INPUT-ERROR                                               
                      PERFORM 1000-SEND-MAP                                     
                         THRU 1000-SEND-MAP-EXIT                                
                      GO TO COMMON-RETURN                                       
                   ELSE                                                         
                      PERFORM 9000-READ-DATA                                    
                         THRU 9000-READ-DATA-EXIT                               
                      PERFORM 1000-SEND-MAP                                     
                         THRU 1000-SEND-MAP-EXIT                                
                      GO TO COMMON-RETURN                                       
                                                                                
                   END-IF                                                       
                                                                                
              WHEN OTHER                                                        
      * Unexpected program context: sets abend info
      * and sends a plain text error message.
                   MOVE LIT-THISPGM    TO ABEND-CULPRIT                         
                   MOVE '0001'         TO ABEND-CODE                            
                   MOVE SPACES         TO ABEND-REASON                          
                   MOVE 'UNEXPECTED DATA SCENARIO'                              
                                       TO WS-RETURN-MSG                         
                   PERFORM SEND-PLAIN-TEXT                                      
                      THRU SEND-PLAIN-TEXT-EXIT                                 
           END-EVALUATE                                                         
                                                                                
                                                                                
      * If we had an error setup error message that slipped through             
      * Display and return                                                      
           IF INPUT-ERROR                                                       
              MOVE WS-RETURN-MSG  TO CCARD-ERROR-MSG                            
              PERFORM 1000-SEND-MAP                                             
                 THRU 1000-SEND-MAP-EXIT                                        
              GO TO COMMON-RETURN                                               
           END-IF                                                               
           .                                                                    
                                                                                
       COMMON-RETURN.                                                           
      * COMMON-RETURN: pseudo-conversational return.
      * Copies error message to work area, builds the
      * concatenated COMMAREA, and issues CICS RETURN
      * with TRANSID(CCDL) to re-invoke on next input.
           MOVE WS-RETURN-MSG     TO CCARD-ERROR-MSG                            
                                                                                
           MOVE  CARDDEMO-COMMAREA    TO WS-COMMAREA                            
           MOVE  WS-THIS-PROGCOMMAREA TO                                        
                  WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:                  
                               LENGTH OF WS-THIS-PROGCOMMAREA )                 
                                                                                
           EXEC CICS RETURN                                                     
                TRANSID (LIT-THISTRANID)                                        
                COMMAREA (WS-COMMAREA)                                          
                LENGTH(LENGTH OF WS-COMMAREA)                                   
           END-EXEC                                                             
      * CICS RETURN with TRANSID and COMMAREA ends
      * this task. Next user input starts a new task
      * with the saved COMMAREA restored.
           .                                                                    
       0000-MAIN-EXIT.                                                          
           EXIT                                                                 
           .                                                                    
                                                                                
       1000-SEND-MAP.                                                           
      * 1000-SEND-MAP: orchestrates screen output by
      * calling init, variable setup, attribute setup,
      * and the actual CICS SEND MAP in sequence.
           PERFORM 1100-SCREEN-INIT                                             
              THRU 1100-SCREEN-INIT-EXIT                                        
           PERFORM 1200-SETUP-SCREEN-VARS                                       
              THRU 1200-SETUP-SCREEN-VARS-EXIT                                  
           PERFORM 1300-SETUP-SCREEN-ATTRS                                      
              THRU 1300-SETUP-SCREEN-ATTRS-EXIT                                 
           PERFORM 1400-SEND-SCREEN                                             
              THRU 1400-SEND-SCREEN-EXIT                                        
           .                                                                    
                                                                                
       1000-SEND-MAP-EXIT.                                                      
           EXIT                                                                 
           .                                                                    
                                                                                
       1100-SCREEN-INIT.                                                        
      * 1100-SCREEN-INIT: clears the output map buffer
      * (CCRDSLAO) and populates header fields (titles,
      * transaction name, program name, date, time).
           MOVE LOW-VALUES TO CCRDSLAO                                          
      * Clears all output map fields to LOW-VALUES
      * to prevent residual data on screen.
                                                                                
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA                       
                                                                                
           MOVE CCDA-TITLE01           TO TITLE01O OF CCRDSLAO                  
           MOVE CCDA-TITLE02           TO TITLE02O OF CCRDSLAO                  
           MOVE LIT-THISTRANID         TO TRNNAMEO OF CCRDSLAO                  
           MOVE LIT-THISPGM            TO PGMNAMEO OF CCRDSLAO                  
                                                                                
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA                       
                                                                                
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM                         
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD                         
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY                         
                                                                                
           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF CCRDSLAO                  
      * Formats current date as MM/DD/YY for header.
                                                                                
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH                         
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM                         
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS                         
                                                                                
           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF CCRDSLAO                  
      * Formats current time as HH:MM:SS for header.
                                                                                
           .                                                                    
                                                                                
       1100-SCREEN-INIT-EXIT.                                                   
           EXIT                                                                 
           .                                                                    
                                                                                
       1200-SETUP-SCREEN-VARS.                                                  
      * 1200-SETUP-SCREEN-VARS: populates the card
      * detail fields on the BMS map. Maps record
      * fields: CARD-EMBOSSED-NAME -> CRDNAMEO,
      * CARD-ACTIVE-STATUS -> CRDSTCDO, expiration
      * date components -> EXPMONO/EXPYEARO.
      *    INITIALIZE SEARCH CRITERIA                                           
           IF EIBCALEN = 0                                                      
              SET  WS-PROMPT-FOR-INPUT TO TRUE                                  
           ELSE                                                                 
              IF CDEMO-ACCT-ID = 0                                              
                 MOVE LOW-VALUES   TO ACCTSIDO OF CCRDSLAO                      
              ELSE                                                              
                 MOVE CC-ACCT-ID   TO ACCTSIDO OF CCRDSLAO                      
              END-IF                                                            
                                                                                
              IF CDEMO-CARD-NUM = 0                                             
                MOVE LOW-VALUES   TO CARDSIDO OF CCRDSLAO                       
              ELSE                                                              
                MOVE CC-CARD-NUM  TO CARDSIDO OF CCRDSLAO                       
              END-IF                                                            
                                                                                
              IF FOUND-CARDS-FOR-ACCOUNT                                        
                 MOVE CARD-EMBOSSED-NAME                                        
                                        TO CRDNAMEO OF CCRDSLAO                 
                 MOVE CARD-EXPIRAION-DATE                                       
                                        TO CARD-EXPIRAION-DATE-X                
                                                                                
                 MOVE CARD-EXPIRY-MONTH TO EXPMONO  OF CCRDSLAO                 
                                                                                
                 MOVE CARD-EXPIRY-YEAR  TO EXPYEARO OF CCRDSLAO                 
                                                                                
                 MOVE CARD-ACTIVE-STATUS TO CRDSTCDO OF CCRDSLAO                
      * Field mapping: CARD-EMBOSSED-NAME -> CRDNAMEO,
      * CARD-EXPIRY-MONTH -> EXPMONO,
      * CARD-EXPIRY-YEAR -> EXPYEARO,
      * CARD-ACTIVE-STATUS -> CRDSTCDO.
              END-IF                                                            
            END-IF                                                              
                                                                                
                                                                                
      *    SETUP MESSAGE                                                        
           IF WS-NO-INFO-MESSAGE                                                
             SET WS-PROMPT-FOR-INPUT TO TRUE                                    
           END-IF                                                               
                                                                                
           MOVE WS-RETURN-MSG          TO ERRMSGO OF CCRDSLAO                   
                                                                                
           MOVE WS-INFO-MSG            TO INFOMSGO OF CCRDSLAO                  
           .                                                                    
                                                                                
       1200-SETUP-SCREEN-VARS-EXIT.                                             
           EXIT                                                                 
           .                                                                    
       1300-SETUP-SCREEN-ATTRS.                                                 
      * 1300-SETUP-SCREEN-ATTRS: sets BMS field
      * attributes (protection, cursor, color) based
      * on validation results and caller context.
                                                                                
      *    PROTECT OR UNPROTECT BASED ON CONTEXT                                
           IF  CDEMO-LAST-MAPSET  EQUAL LIT-CCLISTMAPSET 
           AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM                           
              MOVE DFHBMPRF     TO ACCTSIDA OF CCRDSLAI                         
              MOVE DFHBMPRF     TO CARDSIDA OF CCRDSLAI                         
           ELSE                                                                 
              MOVE DFHBMFSE      TO ACCTSIDA OF CCRDSLAI                        
              MOVE DFHBMFSE      TO CARDSIDA OF CCRDSLAI                        
           END-IF                                                               
      * Protects account/card fields (DFHBMPRF) when
      * arriving from card list (read-only view).
      * Otherwise sets them editable (DFHBMFSE).
                                                                                
      *    POSITION CURSOR                                                      
           EVALUATE TRUE                                                        
              WHEN FLG-ACCTFILTER-NOT-OK                                        
              WHEN FLG-ACCTFILTER-BLANK                                         
                   MOVE -1             TO ACCTSIDL OF CCRDSLAI                  
              WHEN FLG-CARDFILTER-NOT-OK                                        
              WHEN FLG-CARDFILTER-BLANK                                         
                   MOVE -1             TO CARDSIDL OF CCRDSLAI                  
              WHEN OTHER                                                        
                   MOVE -1             TO ACCTSIDL OF CCRDSLAI                  
           END-EVALUATE                                                         
      * Places cursor on the first field with an
      * error or blank condition for user correction.
                                                                                
      *    SETUP COLOR                                                          
           IF  CDEMO-LAST-MAPSET   EQUAL LIT-CCLISTMAPSET
           AND CDEMO-FROM-PROGRAM  EQUAL LIT-CCLISTPGM                          
              MOVE DFHDFCOL     TO ACCTSIDC OF CCRDSLAO                         
              MOVE DFHDFCOL     TO CARDSIDC OF CCRDSLAO                         
           END-IF                                                               
                                                                                
           IF FLG-ACCTFILTER-NOT-OK                                             
              MOVE DFHRED              TO ACCTSIDC OF CCRDSLAO                  
           END-IF                                                               
                                                                                
           IF FLG-CARDFILTER-NOT-OK                                             
              MOVE DFHRED              TO CARDSIDC OF CCRDSLAO                  
           END-IF                                                               
                                                                                
           IF  FLG-ACCTFILTER-BLANK                                             
           AND CDEMO-PGM-REENTER                                                
               MOVE '*'                TO ACCTSIDO OF CCRDSLAO                  
               MOVE DFHRED             TO ACCTSIDC OF CCRDSLAO                  
           END-IF                                                               
                                                                                
           IF  FLG-CARDFILTER-BLANK                                             
           AND CDEMO-PGM-REENTER                                                
               MOVE '*'                TO CARDSIDO OF CCRDSLAO                  
               MOVE DFHRED             TO CARDSIDC OF CCRDSLAO                  
           END-IF                                                               
      * Sets error fields to red (DFHRED). Blank
      * required fields show '*' marker in red.
                                                                                
           IF  WS-NO-INFO-MESSAGE                                               
               MOVE DFHBMDAR           TO INFOMSGC OF CCRDSLAO                  
           ELSE                                                                 
               MOVE DFHNEUTR           TO INFOMSGC OF CCRDSLAO                  
           END-IF                                                               
           .                                                                    
       1300-SETUP-SCREEN-ATTRS-EXIT.                                            
            EXIT.                                                               
                                                                                
                                                                                
       1400-SEND-SCREEN.                                                        
      * 1400-SEND-SCREEN: issues CICS SEND MAP to
      * display the card detail screen. Sets
      * PGM-CONTEXT to reenter for the next task.
                                                                                
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
      * SEND MAP with CURSOR positions at the field
      * set in 1300. ERASE clears the terminal.
      * FREEKB unlocks the keyboard for input.
           .                                                                    
       1400-SEND-SCREEN-EXIT.                                                   
           EXIT                                                                 
           .                                                                    
                                                                                
       2000-PROCESS-INPUTS.                                                     
      * 2000-PROCESS-INPUTS: receives BMS map input,
      * validates fields, and stores error/navigation
      * context for re-display.
           PERFORM 2100-RECEIVE-MAP                                             
              THRU 2100-RECEIVE-MAP-EXIT                                        
           PERFORM 2200-EDIT-MAP-INPUTS                                         
              THRU 2200-EDIT-MAP-INPUTS-EXIT                                    
           MOVE WS-RETURN-MSG  TO CCARD-ERROR-MSG                               
           MOVE LIT-THISPGM    TO CCARD-NEXT-PROG                               
           MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET                             
           MOVE LIT-THISMAP    TO CCARD-NEXT-MAP                                
           .                                                                    
                                                                                
       2000-PROCESS-INPUTS-EXIT.                                                
           EXIT                                                                 
           .                                                                    
       2100-RECEIVE-MAP.                                                        
      * 2100-RECEIVE-MAP: issues CICS RECEIVE MAP to
      * read user input from the terminal into the
      * CCRDSLAI input buffer.
           EXEC CICS RECEIVE MAP(LIT-THISMAP)                                   
                     MAPSET(LIT-THISMAPSET)                                     
                     INTO(CCRDSLAI)                                             
                     RESP(WS-RESP-CD)                                           
                     RESP2(WS-REAS-CD)                                          
           END-EXEC                                                             
      * Reads the 3270 data stream into CCRDSLAI,
      * capturing ACCTSIDI and CARDSIDI fields.
           .                                                                    
                                                                                
       2100-RECEIVE-MAP-EXIT.                                                   
           EXIT                                                                 
           .                                                                    
       2200-EDIT-MAP-INPUTS.                                                    
      * 2200-EDIT-MAP-INPUTS: validates user input.
      * Sanitizes '*' and spaces to LOW-VALUES, then
      * delegates to account and card field editors.
      * Cross-checks that at least one criterion exists.
                                                                                
           SET INPUT-OK                  TO TRUE                                
           SET FLG-CARDFILTER-ISVALID    TO TRUE                                
           SET FLG-ACCTFILTER-ISVALID    TO TRUE                                
                                                                                
      *    REPLACE * WITH LOW-VALUES                                            
           IF  ACCTSIDI OF CCRDSLAI = '*'                                       
           OR  ACCTSIDI OF CCRDSLAI = SPACES                                    
               MOVE LOW-VALUES           TO  CC-ACCT-ID                         
           ELSE                                                                 
               MOVE ACCTSIDI OF CCRDSLAI TO  CC-ACCT-ID                         
           END-IF                                                               
                                                                                
           IF  CARDSIDI OF CCRDSLAI = '*'                                       
           OR  CARDSIDI OF CCRDSLAI = SPACES                                    
               MOVE LOW-VALUES           TO  CC-CARD-NUM                        
           ELSE                                                                 
               MOVE CARDSIDI OF CCRDSLAI TO  CC-CARD-NUM                        
           END-IF                                                               
                                                                                
      *    INDIVIDUAL FIELD EDITS                                               
           PERFORM 2210-EDIT-ACCOUNT                                            
              THRU 2210-EDIT-ACCOUNT-EXIT                                       
                                                                                
           PERFORM 2220-EDIT-CARD                                               
              THRU 2220-EDIT-CARD-EXIT                                          
                                                                                
      *    CROSS FIELD EDITS                                                    
           IF  FLG-ACCTFILTER-BLANK                                             
           AND FLG-CARDFILTER-BLANK                                             
               SET NO-SEARCH-CRITERIA-RECEIVED TO TRUE                          
           END-IF                                                               
           .                                                                    
                                                                                
       2200-EDIT-MAP-INPUTS-EXIT.                                               
           EXIT                                                                 
           .                                                                    
                                                                                
       2210-EDIT-ACCOUNT.                                                       
      * 2210-EDIT-ACCOUNT: validates account number.
      * Checks blank/zero (prompts user), then
      * non-numeric (rejects with error). On success,
      * stores value in CDEMO-ACCT-ID.
           SET FLG-ACCTFILTER-NOT-OK TO TRUE                                    
                                                                                
      *    Not supplied                                                         
           IF CC-ACCT-ID   EQUAL LOW-VALUES                                     
           OR CC-ACCT-ID   EQUAL SPACES                                         
           OR CC-ACCT-ID-N EQUAL ZEROS                                          
              SET INPUT-ERROR           TO TRUE                                 
              SET FLG-ACCTFILTER-BLANK  TO TRUE                                 
              IF WS-RETURN-MSG-OFF                                              
                 SET WS-PROMPT-FOR-ACCT TO TRUE                                 
              END-IF                                                            
              MOVE ZEROES       TO CDEMO-ACCT-ID                                
              GO TO  2210-EDIT-ACCOUNT-EXIT                                     
           END-IF                                                               
      *                                                                         
      *    Not numeric                                                          
      *    Not 11 characters                                                    
           IF CC-ACCT-ID  IS NOT NUMERIC                                        
              SET INPUT-ERROR TO TRUE                                           
              SET FLG-ACCTFILTER-NOT-OK TO TRUE                                 
              IF WS-RETURN-MSG-OFF                                              
                MOVE                                                            
              'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'            
                              TO WS-RETURN-MSG                                  
              END-IF                                                            
              MOVE ZERO       TO CDEMO-ACCT-ID                                  
              GO TO 2210-EDIT-ACCOUNT-EXIT                                      
           ELSE                                                                 
              MOVE CC-ACCT-ID TO CDEMO-ACCT-ID                                  
              SET FLG-ACCTFILTER-ISVALID TO TRUE                                
           END-IF                                                               
           .                                                                    
                                                                                
       2210-EDIT-ACCOUNT-EXIT.                                                  
           EXIT                                                                 
           .                                                                    
                                                                                
       2220-EDIT-CARD.                                                          
      * 2220-EDIT-CARD: validates 16-digit card number.
      * Checks blank/zero, then non-numeric. On success,
      * stores in CDEMO-CARD-NUM.
      *    Not numeric                                                          
      *    Not 16 characters                                                    
           SET FLG-CARDFILTER-NOT-OK TO TRUE                                    
                                                                                
      *    Not supplied                                                         
           IF CC-CARD-NUM   EQUAL LOW-VALUES                                    
           OR CC-CARD-NUM   EQUAL SPACES                                        
           OR CC-CARD-NUM-N EQUAL ZEROS                                         
              SET INPUT-ERROR           TO TRUE                                 
              SET FLG-CARDFILTER-BLANK  TO TRUE                                 
              IF WS-RETURN-MSG-OFF                                              
                 SET WS-PROMPT-FOR-CARD TO TRUE                                 
              END-IF                                                            
                                                                                
              MOVE ZEROES       TO CDEMO-CARD-NUM                               
              GO TO  2220-EDIT-CARD-EXIT                                        
           END-IF                                                               
      *                                                                         
      *    Not numeric                                                          
      *    Not 16 characters                                                    
           IF CC-CARD-NUM  IS NOT NUMERIC                                       
              SET INPUT-ERROR TO TRUE                                           
              SET FLG-CARDFILTER-NOT-OK TO TRUE                                 
              IF WS-RETURN-MSG-OFF                                              
                 MOVE                                                           
              'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'            
                              TO WS-RETURN-MSG                                  
              END-IF                                                            
              MOVE ZERO       TO CDEMO-CARD-NUM                                 
              GO TO 2220-EDIT-CARD-EXIT                                         
           ELSE                                                                 
              MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM                              
              SET FLG-CARDFILTER-ISVALID TO TRUE                                
           END-IF                                                               
           .                                                                    
                                                                                
       2220-EDIT-CARD-EXIT.                                                     
           EXIT                                                                 
           .                                                                    
                                                                                
       9000-READ-DATA.                                                          
      * 9000-READ-DATA: delegates to the appropriate
      * VSAM read routine to fetch the card record.
                                                                                
           PERFORM 9100-GETCARD-BYACCTCARD                                      
              THRU 9100-GETCARD-BYACCTCARD-EXIT                                 
           .                                                                    
                                                                                
       9000-READ-DATA-EXIT.                                                     
           EXIT                                                                 
           .                                                                    
                                                                                
       9100-GETCARD-BYACCTCARD.                                                 
      * 9100-GETCARD-BYACCTCARD: reads CARDDAT VSAM
      * KSDS by primary key (card number, 16 bytes).
      * Evaluates RESP: NORMAL=success, NOTFND=error,
      * OTHER=builds file error message.
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
      * Reads CARDDAT by card number. RIDFLD points
      * to WS-CARD-RID-CARDNUM (16-byte primary key).
      * INTO loads the 150-byte CARD-RECORD layout.
                                                                                
           EVALUATE WS-RESP-CD                                                  
               WHEN DFHRESP(NORMAL)                                             
                  SET FOUND-CARDS-FOR-ACCOUNT TO TRUE                           
               WHEN DFHRESP(NOTFND)                                             
                  SET INPUT-ERROR                    TO TRUE                    
                  SET FLG-ACCTFILTER-NOT-OK          TO TRUE                    
                  SET FLG-CARDFILTER-NOT-OK          TO TRUE                    
                  IF  WS-RETURN-MSG-OFF                                         
                      SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE                   
                  END-IF                                                        
               WHEN OTHER                                                       
                  SET INPUT-ERROR                    TO TRUE                    
                  IF  WS-RETURN-MSG-OFF                                         
                      SET FLG-ACCTFILTER-NOT-OK      TO TRUE                    
                  END-IF                                                        
                  MOVE 'READ'                        TO ERROR-OPNAME            
                  MOVE LIT-CARDFILENAME                TO ERROR-FILE            
                  MOVE WS-RESP-CD                    TO ERROR-RESP              
                  MOVE WS-REAS-CD                    TO ERROR-RESP2             
                  MOVE WS-FILE-ERROR-MESSAGE         TO WS-RETURN-MSG           
           END-EVALUATE                                                         
      * NORMAL: card found, sets display flag.
      * NOTFND: no match, flags both account and card.
      * OTHER: unexpected VSAM error, builds error msg.
           .                                                                    
                                                                                
       9100-GETCARD-BYACCTCARD-EXIT.                                            
           EXIT                                                                 
           .                                                                    
                                                                                
       9150-GETCARD-BYACCT.                                                     
      * 9150-GETCARD-BYACCT: reads CARDAIX alternate
      * index path by account ID (11 bytes). Used when
      * only an account number is available.
                                                                                
      *    Read the Card file. Access via alternate index ACCTID                
      *                                                                         
           EXEC CICS READ                                                       
                FILE      (LIT-CARDFILENAME-ACCT-PATH)                          
                RIDFLD    (WS-CARD-RID-ACCT-ID)                                 
                KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID)                       
                INTO      (CARD-RECORD)                                         
                LENGTH    (LENGTH OF CARD-RECORD)                               
                RESP      (WS-RESP-CD)                                          
                RESP2     (WS-REAS-CD)                                          
           END-EXEC                                                             
      * Reads via alternate index path CARDAIX by
      * account ID. Returns the first matching card.
                                                                                
           EVALUATE WS-RESP-CD                                                  
               WHEN DFHRESP(NORMAL)                                             
                  SET FOUND-CARDS-FOR-ACCOUNT TO TRUE                           
               WHEN DFHRESP(NOTFND)                                             
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-ACCTFILTER-NOT-OK                TO TRUE              
                  SET DID-NOT-FIND-ACCT-IN-CARDXREF TO TRUE                     
               WHEN OTHER                                                       
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-ACCTFILTER-NOT-OK                TO TRUE              
                  MOVE 'READ'                     TO ERROR-OPNAME               
                  MOVE LIT-CARDFILENAME-ACCT-PATH TO ERROR-FILE                 
                  MOVE WS-RESP-CD                 TO ERROR-RESP                 
                  MOVE WS-REAS-CD                 TO ERROR-RESP2                
                  MOVE WS-FILE-ERROR-MESSAGE      TO WS-RETURN-MSG              
           END-EVALUATE                                                         
      * NORMAL: card found via account AIX lookup.
      * NOTFND: account not in card cross-reference.
      * OTHER: builds formatted file error message.
           .                                                                    
       9150-GETCARD-BYACCT-EXIT.                                                
           EXIT                                                                 
           .                                                                    
                                                                                
                                                                                
      *****************************************************************         
      * Display Long text and exit                                    *         
      * This is primarily for debugging and should not be used in     *         
      * regular course                                                *         
      *****************************************************************         
       SEND-LONG-TEXT.                                                          
           EXEC CICS SEND TEXT                                                  
                     FROM(WS-LONG-MSG)                                          
                     LENGTH(LENGTH OF WS-LONG-MSG)                              
                     ERASE                                                      
                     FREEKB                                                     
           END-EXEC                                                             
                                                                                
           EXEC CICS RETURN                                                     
           END-EXEC                                                             
           .                                                                    
       SEND-LONG-TEXT-EXIT.                                                     
           EXIT                                                                 
           .                                                                    
                                                                                
      *****************************************************************         
      * Plain text exit - Dont use in production                      *         
      *****************************************************************         
       SEND-PLAIN-TEXT.                                                         
           EXEC CICS SEND TEXT                                                  
                     FROM(WS-RETURN-MSG)                                        
                     LENGTH(LENGTH OF WS-RETURN-MSG)                            
                     ERASE                                                      
                     FREEKB                                                     
           END-EXEC                                                             
                                                                                
           EXEC CICS RETURN                                                     
           END-EXEC                                                             
           .                                                                    
       SEND-PLAIN-TEXT-EXIT.                                                    
           EXIT                                                                 
           .                                                                    
      ******************************************************************        
      *Common code to store PFKey
      ******************************************************************
       COPY 'CSSTRPFY'
           .
       ABEND-ROUTINE.                                                           
      * ABEND-ROUTINE: handles unexpected abends.
      * Sends abend data area to the terminal,
      * cancels further abend handling, then issues
      * CICS ABEND with code '9999' to terminate.
                                                                                
           IF ABEND-MSG EQUAL LOW-VALUES                                        
              MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG                    
           END-IF                                                               
                                                                                
           MOVE LIT-THISPGM       TO ABEND-CULPRIT                              
                                                                                
           EXEC CICS SEND                                                       
                            FROM (ABEND-DATA)                                   
                            LENGTH(LENGTH OF ABEND-DATA)                        
                            NOHANDLE                                            
           END-EXEC                                                             
                                                                                
           EXEC CICS HANDLE ABEND                                               
                CANCEL                                                          
           END-EXEC                                                             
                                                                                
           EXEC CICS ABEND                                                      
                ABCODE('9999')                                                  
           END-EXEC                                                             
           .                                                                    
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:33 CDT
      *
