      *****************************************************************         
      * Program:     COACTVWC.CBL                                     *         
      * Layer:       Business logic                                   *         
      * Function:    Accept and process Account View request          *         
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
      * Program:     COACTVWC
      * Transaction: CAVW
      * BMS Map:     COACTVW / CACTVW
      * Function:    Account view (read-only) screen. Performs a
      *              3-entity join: reads ACCTDAT for account details,
      *              CXACAIX (card cross-reference by account) for the
      *              associated card, and CUSTDAT for customer name and
      *              demographics. All data is display-only.
      * Files:       ACCTDAT (READ), CXACAIX (READ), CUSTDAT (READ)
      * Navigation:  PF3 returns to calling program or main menu.
      *              Enter re-displays after input validation.
      *================================================================*
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.                                                              
           COACTVWC.                                                            
       DATE-WRITTEN.                                                            
           May 2022.                                                            
       DATE-COMPILED.                                                           
           Today.                                                               
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
                                                                                
       DATA DIVISION.                                                           
      *================================================================         
      * WORKING-STORAGE holds all local variables for this                      
      * pseudo-conversational program: CICS response codes,                     
      * input-validation flags, cross-reference record IDs,                     
      * file-read status trackers, error message templates,                     
      * screen output messages, navigation literal constants,                   
      * and all shared copybook record buffers.                                 
      *================================================================         
                                                                                
       WORKING-STORAGE SECTION.                                                 
       01  WS-MISC-STORAGE.                                                     
      ******************************************************************        
      * General CICS related                                                    
      ******************************************************************        
      * WS-RESP-CD and WS-REAS-CD capture CICS RESP/RESP2                       
      * codes after every EXEC CICS call for error handling.                    
      * WS-TRANID stores the current transaction ID (CAVW).                     
         05 WS-CICS-PROCESSNG-VARS.                                             
            07 WS-RESP-CD                          PIC S9(09) COMP              
                                                   VALUE ZEROS.                 
            07 WS-REAS-CD                          PIC S9(09) COMP              
                                                   VALUE ZEROS.                 
            07 WS-TRANID                           PIC X(4)                     
                                                   VALUE SPACES.                
      ******************************************************************        
      *      Input edits                                                        
      ******************************************************************        
                                                                                
      * WS-INPUT-FLAG: tracks overall input validation state.                   
      *   INPUT-OK (0) = valid, INPUT-ERROR (1) = invalid.                      
         05  WS-INPUT-FLAG                         PIC X(1).                    
           88  INPUT-OK                            VALUE '0'.                   
           88  INPUT-ERROR                         VALUE '1'.                   
           88  INPUT-PENDING                       VALUE LOW-VALUES.            
      * WS-PFK-FLAG: tracks whether the pressed PF key is                       
      * valid for this screen (Enter or PF3 only).                              
         05  WS-PFK-FLAG                           PIC X(1).                    
           88  PFK-VALID                           VALUE '0'.                   
           88  PFK-INVALID                         VALUE '1'.                   
           88  INPUT-PENDING                       VALUE LOW-VALUES.            
      * WS-EDIT-ACCT-FLAG: tracks account ID input validity.                    
      *   NOT-OK(0)=invalid, ISVALID(1)=ok, BLANK=not entered.                  
         05  WS-EDIT-ACCT-FLAG                     PIC X(1).                    
           88  FLG-ACCTFILTER-NOT-OK               VALUE '0'.                   
           88  FLG-ACCTFILTER-ISVALID              VALUE '1'.                   
           88  FLG-ACCTFILTER-BLANK                VALUE ' '.                   
      * WS-EDIT-CUST-FLAG: tracks customer lookup result.                       
         05  WS-EDIT-CUST-FLAG                     PIC X(1).                    
           88  FLG-CUSTFILTER-NOT-OK               VALUE '0'.                   
           88  FLG-CUSTFILTER-ISVALID              VALUE '1'.                   
           88  FLG-CUSTFILTER-BLANK                VALUE ' '.                   
      ******************************************************************        
      * Output edits                                                            
      ******************************************************************        
      *  05  EDIT-FIELD-9-2                PIC +ZZZ,ZZZ,ZZZ.99.                 
      ******************************************************************        
      *      File and data Handling                                             
      ******************************************************************        
      * WS-XREF-RID: composite record ID structure used as                      
      * RIDFLD for VSAM reads. Contains card number (16),                       
      * customer ID (9), and account ID (11). The REDEFINES                     
      * fields provide alphanumeric (PIC X) views for CICS                      
      * READ RIDFLD which requires PIC X key fields.                            
         05  WS-XREF-RID.                                                       
           10  WS-CARD-RID-CARDNUM                 PIC X(16).                   
           10  WS-CARD-RID-CUST-ID                 PIC 9(09).                   
           10  WS-CARD-RID-CUST-ID-X REDEFINES                                  
                  WS-CARD-RID-CUST-ID              PIC X(09).                   
           10  WS-CARD-RID-ACCT-ID                 PIC 9(11).                   
           10  WS-CARD-RID-ACCT-ID-X REDEFINES                                  
                  WS-CARD-RID-ACCT-ID              PIC X(11).                   
      * WS-FILE-READ-FLAGS: track which master files were                       
      * successfully read during the 3-entity join. Controls                    
      * which data sections populate the display screen.                        
         05  WS-FILE-READ-FLAGS. 
           10 WS-ACCOUNT-MASTER-READ-FLAG          PIC X(1).
              88 FOUND-ACCT-IN-MASTER              VALUE '1'.
           10 WS-CUST-MASTER-READ-FLAG             PIC X(1).
              88 FOUND-CUST-IN-MASTER              VALUE '1'.                   
      * WS-FILE-ERROR-MESSAGE: pre-formatted template for                       
      * VSAM file I/O error messages. Fills operation name,                     
      * file name, RESP code, and RESP2 reason on error.                        
         05  WS-FILE-ERROR-MESSAGE.                                             
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
      * WS-LONG-MSG: 500-byte buffer for debug text display.                    
         05  WS-LONG-MSG                           PIC X(500).                  
      * WS-INFO-MSG: 40-byte informational message displayed                    
      * above the input area. 88-levels provide canned text.                    
         05  WS-INFO-MSG                           PIC X(40).                   
           88  WS-NO-INFO-MESSAGE                 VALUES                        
                                                  SPACES LOW-VALUES.            
           88  WS-PROMPT-FOR-INPUT                 VALUE                        
               'Enter or update id of account to display'.
           88  WS-INFORM-OUTPUT                    VALUE
               'Displaying details of given Account'.                           
      * WS-RETURN-MSG: 75-byte error/status message. 88-level                   
      * conditions provide pre-defined messages for each                        
      * error scenario (not found, invalid input, etc.).                        
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
      *****************************************************************         
      *      Literals and Constants                                             
      ******************************************************************        
      * WS-LITERALS: constant values for this program, its                      
      * transaction ID, BMS mapset/map names, and literals                      
      * for every program this screen can navigate to.                          
       01 WS-LITERALS.                                                          
          05 LIT-THISPGM                           PIC X(8)                     
                                                   VALUE 'COACTVWC'.            
          05 LIT-THISTRANID                        PIC X(4)                     
                                                   VALUE 'CAVW'.                
          05 LIT-THISMAPSET                        PIC X(8)                     
                                                   VALUE 'COACTVW '.            
          05 LIT-THISMAP                           PIC X(7)                     
                                                   VALUE 'CACTVWA'.             
          05 LIT-CCLISTPGM                         PIC X(8)                     
                                                   VALUE 'COCRDLIC'.            
          05 LIT-CCLISTTRANID                      PIC X(4)                     
                                                   VALUE 'CCLI'.                
          05 LIT-CCLISTMAPSET                      PIC X(7)                     
                                                   VALUE 'COCRDLI'.             
          05 LIT-CCLISTMAP                         PIC X(7)                     
                                                   VALUE 'CCRDSLA'.             
          05 LIT-CARDUPDATEPGM                           PIC X(8)               
                                                   VALUE 'COCRDUPC'.            
          05 LIT-CARDUDPATETRANID                        PIC X(4)               
                                                   VALUE 'CCUP'.                
          05 LIT-CARDUPDATEMAPSET                        PIC X(8)               
                                                   VALUE 'COCRDUP '.            
          05 LIT-CARDUPDATEMAP                           PIC X(7)               
                                                   VALUE 'CCRDUPA'.             
                                                                                
          05 LIT-MENUPGM                           PIC X(8)                     
                                                   VALUE 'COMEN01C'.            
          05 LIT-MENUTRANID                        PIC X(4)                     
                                                   VALUE 'CM00'.                
          05 LIT-MENUMAPSET                        PIC X(7)                     
                                                   VALUE 'COMEN01'.             
          05 LIT-MENUMAP                           PIC X(7)                     
                                                   VALUE 'COMEN1A'.             
          05  LIT-CARDDTLPGM                       PIC X(8)                     
                                                   VALUE 'COCRDSLC'.            
          05  LIT-CARDDTLTRANID                    PIC X(4)                     
                                                   VALUE 'CCDL'.                
          05  LIT-CARDDTLMAPSET                    PIC X(7)                     
                                                   VALUE 'COCRDSL'.             
          05  LIT-CARDDTLMAP                       PIC X(7)                     
                                                   VALUE 'CCRDSLA'.             
      * VSAM dataset name literals for the 3-entity join:                       
      *   ACCTDAT  = account master KSDS (key: acct-id)                         
      *   CARDDAT  = card master KSDS (key: card-num)                           
      *   CUSTDAT  = customer master KSDS (key: cust-id)                        
      *   CARDAIX  = card file AIX by account                                   
      *   CXACAIX  = card xref AIX/PATH by account (used                        
      *              to look up cards linked to an account)                     
          05 LIT-ACCTFILENAME                      PIC X(8)                     
                                                   VALUE 'ACCTDAT '.            
          05 LIT-CARDFILENAME                      PIC X(8)                     
                                                   VALUE 'CARDDAT '.            
          05 LIT-CUSTFILENAME                      PIC X(8)                     
                                                   VALUE 'CUSTDAT '.            
          05 LIT-CARDFILENAME-ACCT-PATH            PIC X(8)                     
                                                   VALUE 'CARDAIX '.            
          05 LIT-CARDXREFNAME-ACCT-PATH            PIC X(8)                     
                                                   VALUE 'CXACAIX '.            
          05 LIT-ALL-ALPHA-FROM                    PIC X(52)                    
             VALUE                                                              
             'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'.            
          05 LIT-ALL-SPACES-TO                     PIC X(52)                    
                                                   VALUE SPACES.                
          05 LIT-UPPER                             PIC X(26)                    
                                 VALUE 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.            
          05 LIT-LOWER                             PIC X(26)                    
                                 VALUE 'abcdefghijklmnopqrstuvwxyz'.            
                                                                                
      ******************************************************************        
      *Other common working storage Variables                                   
      ******************************************************************        
      * Card work area: AID/PF-key flags, routing fields,                       
      * and next-screen navigation pointers.                                    
      * See app/cpy/CVCRD01Y.cpy                                                
       COPY CVCRD01Y.                                                           
                                                                                
      ******************************************************************        
      *Application Commmarea Copybook                                           
      * CARDDEMO-COMMAREA: shared navigation/state contract                     
      * passed between all CardDemo programs via CICS XCTL                      
      * and RETURN COMMAREA. Contains from/to program IDs,                      
      * user info, account/card/customer context, and                           
      * program reentry state. See app/cpy/COCOM01Y.cpy                         
       COPY COCOM01Y.                                                           
                                                                                
      * WS-THIS-PROGCOMMAREA: local extension appended after                    
      * CARDDEMO-COMMAREA for this program calling context.                     
       01 WS-THIS-PROGCOMMAREA.                                                 
          05 CA-CALL-CONTEXT.                                                   
             10 CA-FROM-PROGRAM                    PIC X(08).                   
             10 CA-FROM-TRANID                     PIC X(04).                   
                                                                                
      * WS-COMMAREA: 2000-byte buffer used for CICS RETURN                      
      * COMMAREA. Combines CARDDEMO-COMMAREA + local area.                      
       01  WS-COMMAREA                             PIC X(2000).                 
                                                                                
      *IBM SUPPLIED COPYBOOKS                                                   
      * DFHBMSCA: IBM-supplied BMS attribute constants (e.g.                    
      * DFHBMFSE, DFHBMDAR, DFHRED, DFHDFCOL, DFHNEUTR).                        
       COPY DFHBMSCA.                                                           
      * DFHAID: IBM-supplied AID byte constants for mapping                     
      * terminal keys (ENTER, PF1-PF24, CLEAR, PA1-PA3).                        
       COPY DFHAID.                                                             
                                                                                
      *COMMON COPYBOOKS                                                         
      *Screen Titles                                                            
      * Screen title text: banner lines displayed at top of                     
      * every CardDemo screen. See app/cpy/COTTL01Y.cpy                         
       COPY COTTL01Y.                                                           
                                                                                
      *BMS Copybook                                                             
      * BMS symbolic map for account view screen. Defines                       
      * CACTVWAI (input) and CACTVWAO (output) record                           
      * structures with field suffixes (L/A/C/I/O).                             
      * See app/cpy-bms/COACTVW.CPY, app/bms/COACTVW.bms                        
       COPY COACTVW.                                                            
                                                                                
      *Current Date                                                             
      * Date/time working storage: WS-CURDATE-DATA populated                    
      * by FUNCTION CURRENT-DATE. See app/cpy/CSDAT01Y.cpy                      
       COPY CSDAT01Y.                                                           
                                                                                
      *Common Messages                                                          
      * Common application messages (thank-you, invalid key).                   
      * See app/cpy/CSMSG01Y.cpy                                                
       COPY CSMSG01Y.                                                           
                                                                                
      *Abend Variables                                                          
      * Abend data work area: ABEND-CODE, ABEND-CULPRIT,                        
      * ABEND-REASON, ABEND-MSG. See app/cpy/CSMSG02Y.cpy                       
       COPY CSMSG02Y.                                                           
                                                                                
      *Signed on user data                                                      
      * Signed-on user security record layout (80 bytes).                       
      * See app/cpy/CSUSR01Y.cpy                                                
       COPY CSUSR01Y.                                                           
                                                                                
      *ACCOUNT RECORD LAYOUT                                                    
      * ACCOUNT-RECORD layout (300 bytes): ACCT-ID (key),                       
      * ACCT-ACTIVE-STATUS, balances, credit limits, dates,                     
      * cycle credits/debits. See app/cpy/CVACT01Y.cpy                          
       COPY CVACT01Y.                                                           
                                                                                
                                                                                
      *CUSTOMER RECORD LAYOUT                                                   
      * CARD-RECORD layout (150 bytes): CARD-NUM (key),                         
      * CARD-ACCT-ID, CVV, embossed name, expiration date,                      
      * active status. See app/cpy/CVACT02Y.cpy                                 
       COPY CVACT02Y.                                                           
                                                                                
      *CARD XREF LAYOUT                                                         
      * CARD-XREF-RECORD layout (50 bytes): XREF-CARD-NUM,                      
      * XREF-CUST-ID, XREF-ACCT-ID. Links cards to accounts                     
      * and customers. See app/cpy/CVACT03Y.cpy                                 
       COPY CVACT03Y.                                                           
                                                                                
      *CUSTOMER LAYOUT                                                          
      * CUSTOMER-RECORD layout (500 bytes): CUST-ID (key),                      
      * name, address, phone, SSN, government ID, DOB, FICO                     
      * score. See app/cpy/CVCUS01Y.cpy                                         
       COPY CVCUS01Y.                                                           
                                                                                
      *================================================================         
      * LINKAGE SECTION: defines DFHCOMMAREA as a variable-                     
      * length area. EIBCALEN = 0 on first invocation (no                       
      * data passed); > 0 on pseudo-conversational re-entry.                    
      *================================================================         
       LINKAGE SECTION.                                                         
       01  DFHCOMMAREA.                                                         
         05  FILLER                                PIC X(1)                     
             OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.                     
                                                                                
      *================================================================         
      * PROCEDURE DIVISION                                                      
      * Pseudo-conversational flow:                                             
      *   1. 0000-MAIN checks EIBCALEN and program context                      
      *   2. First entry (PGM-ENTER): sends empty form                          
      *   3. Re-entry (PGM-REENTER): receives input, reads                      
      *      3 VSAM files (xref, account, customer), then                       
      *      populates and sends the screen                                     
      *   4. PF3: transfers control back to calling program                     
      *   5. COMMON-RETURN: issues CICS RETURN TRANSID to                       
      *      maintain pseudo-conversational loop                                
      *================================================================         
       PROCEDURE DIVISION.                                                      
      ****************************************************************          
      * 0000-MAIN: entry point for the account view program.                    
      * Registers the abend handler, initializes work areas,                    
      * restores COMMAREA from prior invocation, maps the                       
      * AID key, then routes via EVALUATE to PF3 (exit),                        
      * PGM-ENTER (first display), or PGM-REENTER (process                      
      * input and perform 3-entity join).                                       
      ****************************************************************          
       0000-MAIN.                                                               
                                                                                
      * Register abend handler to capture unexpected failures                   
           EXEC CICS HANDLE ABEND                                               
                     LABEL(ABEND-ROUTINE)                                       
           END-EXEC                                                             
                                                                                
      * Clear all working storage before processing                             
           INITIALIZE CC-WORK-AREA                                              
                      WS-MISC-STORAGE                                           
                      WS-COMMAREA                                               
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
      * Pseudo-conversational check: EIBCALEN = 0 means this                    
      * is the first invocation (no prior COMMAREA). Also                       
      * reinitializes when arriving fresh from the main menu.                   
      * Otherwise restores CARDDEMO-COMMAREA and local area                     
      * from the passed DFHCOMMAREA using reference modification.               
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
                                                                                
      *****************************************************************         
      * Remap PFkeys as needed.                                                 
      * Store the Mapped PF Key                                                 
      *****************************************************************         
           PERFORM YYYY-STORE-PFKEY                                             
              THRU YYYY-STORE-PFKEY-EXIT                                        
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
                                                                                
      *****************************************************************         
      * Decide what to do based on inputs received                              
      *****************************************************************         
      *****************************************************************         
      *****************************************************************         
      * Decide what to do based on inputs received                              
      *****************************************************************         
      * Main routing logic based on AID key and program context:                
      *   CCARD-AID-PFK03 -> exit via XCTL to caller/menu                       
      *   CDEMO-PGM-ENTER -> first entry, display empty form                    
      *   CDEMO-PGM-REENTER -> process user input then read                     
      *                        data and redisplay                               
           EVALUATE TRUE                                                        
              WHEN CCARD-AID-PFK03                                              
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
      * Transfers control to the calling program or main menu.                  
      * XCTL does not return — the target program takes over.                   
                   EXEC CICS XCTL                                               
                             PROGRAM (CDEMO-TO-PROGRAM)                         
                             COMMAREA(CARDDEMO-COMMAREA)                        
                   END-EXEC                                                     
      * First entry from another program: send the empty                        
      * account view form and return to CICS.                                   
              WHEN CDEMO-PGM-ENTER                                              
      ******************************************************************        
      *            COMING FROM SOME OTHER CONTEXT                               
      *            SELECTION CRITERIA TO BE GATHERED                            
      ******************************************************************        
                   PERFORM 1000-SEND-MAP THRU                                   
                           1000-SEND-MAP-EXIT                                   
                   GO TO COMMON-RETURN                                          
      * Re-entry after user submits input: receive the map,                     
      * validate the account ID, then perform the 3-entity                      
      * join (xref + account + customer) if input is valid.                     
              WHEN CDEMO-PGM-REENTER                                            
                   PERFORM 2000-PROCESS-INPUTS                                  
                      THRU 2000-PROCESS-INPUTS-EXIT                             
                   IF INPUT-ERROR                                               
                      PERFORM 1000-SEND-MAP                                     
                         THRU 1000-SEND-MAP-EXIT                                
                      GO TO COMMON-RETURN                                       
                   ELSE                                                         
                      PERFORM 9000-READ-ACCT                                    
                         THRU 9000-READ-ACCT-EXIT                               
                      PERFORM 1000-SEND-MAP                                     
                         THRU 1000-SEND-MAP-EXIT                                
                      GO TO COMMON-RETURN                                       
                   END-IF                                                       
      * Unexpected program context — signals a logic error.                     
      * Displays diagnostic text and returns without a map.                     
             WHEN OTHER                                                         
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
      ****************************************************************          
      * COMMON-RETURN: pseudo-conversational return point.                      
      * Copies any pending error message into COMMAREA, then                    
      * combines CARDDEMO-COMMAREA and local prog area into                     
      * WS-COMMAREA. Issues CICS RETURN TRANSID(CAVW) so                        
      * CICS re-invokes this program on the next terminal                       
      * input from the user.                                                    
      ****************************************************************          
       COMMON-RETURN.                                                           
           MOVE WS-RETURN-MSG     TO CCARD-ERROR-MSG                            
                                                                                
           MOVE  CARDDEMO-COMMAREA    TO WS-COMMAREA                            
           MOVE  WS-THIS-PROGCOMMAREA TO                                        
                  WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:                  
                               LENGTH OF WS-THIS-PROGCOMMAREA )                 
                                                                                
      * CICS RETURN with TRANSID keeps the pseudo-                              
      * conversational loop alive — CICS will re-invoke                         
      * COACTVWC when the user presses a key.                                   
           EXEC CICS RETURN                                                     
                TRANSID (LIT-THISTRANID)                                        
                COMMAREA (WS-COMMAREA)                                          
                LENGTH(LENGTH OF WS-COMMAREA)                                   
           END-EXEC                                                             
           .                                                                    
       0000-MAIN-EXIT.                                                          
           EXIT                                                                 
           .                                                                    
       0000-MAIN-EXIT.                                                          
           EXIT                                                                 
           .                                                                    
                                                                                
                                                                                
      ****************************************************************          
      * 1000-SEND-MAP: orchestrates the screen output.                          
      *   1100 initializes header fields (title, date, time)                    
      *   1200 populates data fields from record buffers                        
      *   1300 sets field attributes (color, protection)                        
      *   1400 sends the BMS map to the 3270 terminal                           
      ****************************************************************          
       1000-SEND-MAP.                                                           
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
                                                                                
      ****************************************************************          
      * 1100-SCREEN-INIT: initializes the BMS output buffer                     
      * CACTVWAO to LOW-VALUES, populates application title                     
      * banners, transaction/program names, and the current                     
      * date and time in MM/DD/YY and HH:MM:SS format.                          
      ****************************************************************          
       1100-SCREEN-INIT.                                                        
           MOVE LOW-VALUES             TO CACTVWAO                              
                                                                                
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA                       
                                                                                
           MOVE CCDA-TITLE01           TO TITLE01O OF CACTVWAO                  
           MOVE CCDA-TITLE02           TO TITLE02O OF CACTVWAO                  
           MOVE LIT-THISTRANID         TO TRNNAMEO OF CACTVWAO                  
           MOVE LIT-THISPGM            TO PGMNAMEO OF CACTVWAO                  
                                                                                
           MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA                       
                                                                                
           MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM                         
           MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD                         
           MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY                         
                                                                                
           MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF CACTVWAO                  
                                                                                
           MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH                         
           MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM                         
           MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS                         
                                                                                
           MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF CACTVWAO                  
                                                                                
           .                                                                    
                                                                                
       1100-SCREEN-INIT-EXIT.                                                   
           EXIT                                                                 
           .                                                                    
      ****************************************************************          
      * 1200-SETUP-SCREEN-VARS: maps data from the 3-entity                     
      * join results into BMS screen output fields:                             
      *   - Account data: status, balances, limits, dates                       
      *     (from ACCOUNT-RECORD / CVACT01Y)                                    
      *   - Customer data: name, address, SSN, FICO, DOB,                       
      *     phones, government ID, EFT account                                  
      *     (from CUSTOMER-RECORD / CVCUS01Y)                                   
      *   - Xref data: linked card number                                       
      *     (from CARD-XREF-RECORD / CVACT03Y)                                  
      * If no data has been read yet, prompts for input.                        
      ****************************************************************          
       1200-SETUP-SCREEN-VARS.                                                  
      *    INITIALIZE SEARCH CRITERIA                                           
           IF EIBCALEN = 0                                                      
              SET  WS-PROMPT-FOR-INPUT TO TRUE                                  
           ELSE
              IF FLG-ACCTFILTER-BLANK  
                 MOVE LOW-VALUES   TO ACCTSIDO OF CACTVWAO                      
              ELSE                                                              
                 MOVE CC-ACCT-ID   TO ACCTSIDO OF CACTVWAO                      
              END-IF                                                            
                                                                                
      * Map account-level fields when either master was read                    
              IF FOUND-ACCT-IN-MASTER                                           
              OR FOUND-CUST-IN-MASTER                                           
                 MOVE ACCT-ACTIVE-STATUS  TO ACSTTUSO OF CACTVWAO               
                                                                                
                 MOVE ACCT-CURR-BAL       TO ACURBALO OF CACTVWAO               
                                                                                
                 MOVE ACCT-CREDIT-LIMIT   TO ACRDLIMO OF CACTVWAO               
                                                                                
                 MOVE ACCT-CASH-CREDIT-LIMIT
                                          TO ACSHLIMO OF CACTVWAO               
                                                                                
                 MOVE ACCT-CURR-CYC-CREDIT          
                                          TO ACRCYCRO OF CACTVWAO               
                                                                                
                 MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO OF CACTVWAO               
                                                                                
                 MOVE ACCT-OPEN-DATE      TO ADTOPENO OF CACTVWAO               
                 MOVE ACCT-EXPIRAION-DATE TO AEXPDTO  OF CACTVWAO               
                 MOVE ACCT-REISSUE-DATE   TO AREISDTO OF CACTVWAO               
                 MOVE ACCT-GROUP-ID       TO AADDGRPO OF CACTVWAO               
              END-IF                                                            
                                                                                
      * Map customer demographics when customer was found.                      
      * SSN is formatted as NNN-NN-NNNN using STRING.                           
              IF FOUND-CUST-IN-MASTER                                           
                MOVE CUST-ID              TO ACSTNUMO OF CACTVWAO               
      *         MOVE CUST-SSN             TO ACSTSSNO OF CACTVWAO       
                STRING 
                    CUST-SSN(1:3)
                    '-'                 
                    CUST-SSN(4:2)
                    '-'
                    CUST-SSN(6:4)
                    DELIMITED BY SIZE
                    INTO ACSTSSNO OF CACTVWAO
                END-STRING                                                      
                MOVE CUST-FICO-CREDIT-SCORE                                     
                                          TO ACSTFCOO OF CACTVWAO               
                MOVE CUST-DOB-YYYY-MM-DD  TO ACSTDOBO OF CACTVWAO               
                MOVE CUST-FIRST-NAME      TO ACSFNAMO OF CACTVWAO               
                MOVE CUST-MIDDLE-NAME     TO ACSMNAMO OF CACTVWAO               
                MOVE CUST-LAST-NAME       TO ACSLNAMO OF CACTVWAO               
                MOVE CUST-ADDR-LINE-1     TO ACSADL1O OF CACTVWAO               
                MOVE CUST-ADDR-LINE-2     TO ACSADL2O OF CACTVWAO               
                MOVE CUST-ADDR-LINE-3     TO ACSCITYO OF CACTVWAO               
                MOVE CUST-ADDR-STATE-CD   TO ACSSTTEO OF CACTVWAO               
                MOVE CUST-ADDR-ZIP        TO ACSZIPCO OF CACTVWAO               
                MOVE CUST-ADDR-COUNTRY-CD TO ACSCTRYO OF CACTVWAO               
                MOVE CUST-PHONE-NUM-1     TO ACSPHN1O OF CACTVWAO               
                MOVE CUST-PHONE-NUM-2     TO ACSPHN2O OF CACTVWAO               
                MOVE CUST-GOVT-ISSUED-ID  TO ACSGOVTO OF CACTVWAO               
                MOVE CUST-EFT-ACCOUNT-ID  TO ACSEFTCO OF CACTVWAO               
                MOVE CUST-PRI-CARD-HOLDER-IND                                   
                                          TO ACSPFLGO OF CACTVWAO               
              END-IF                                                            
                                                                                
            END-IF                                                              
                                                                                
      *    SETUP MESSAGE                                                        
           IF WS-NO-INFO-MESSAGE                                                
             SET WS-PROMPT-FOR-INPUT TO TRUE                                    
           END-IF                                                               
                                                                                
           MOVE WS-RETURN-MSG          TO ERRMSGO OF CACTVWAO                   
                                                                                
           MOVE WS-INFO-MSG            TO INFOMSGO OF CACTVWAO                  
           .                                                                    
                                                                                
       1200-SETUP-SCREEN-VARS-EXIT.                                             
           EXIT                                                                 
           .                                                                    
                                                                                
      ****************************************************************          
      * 1300-SETUP-SCREEN-ATTRS: configures BMS field                           
      * attributes for the account view screen.                                 
      *   - Sets account ID field to FSET (force send)                          
      *   - Positions cursor on the account ID input                            
      *   - Sets account ID color: default, red if invalid                      
      *   - Marks blank input with asterisk in red                              
      *   - Controls info message visibility (dark/neutral)                     
      ****************************************************************          
       1300-SETUP-SCREEN-ATTRS.                                                 
      *    PROTECT OR UNPROTECT BASED ON CONTEXT                                
           MOVE DFHBMFSE               TO ACCTSIDA OF CACTVWAI                  
                                                                                
      *    POSITION CURSOR                                                      
           EVALUATE TRUE                                                        
              WHEN FLG-ACCTFILTER-NOT-OK                                        
              WHEN FLG-ACCTFILTER-BLANK                                         
                   MOVE -1             TO ACCTSIDL OF CACTVWAI                  
              WHEN OTHER                                                        
                   MOVE -1             TO ACCTSIDL OF CACTVWAI                  
           END-EVALUATE                                                         
                                                                                
      *    SETUP COLOR                                                          
           MOVE DFHDFCOL               TO ACCTSIDC OF CACTVWAO                  
                                                                                
           IF FLG-ACCTFILTER-NOT-OK                                             
              MOVE DFHRED              TO ACCTSIDC OF CACTVWAO                  
           END-IF                                                               
                                                                                
           IF  FLG-ACCTFILTER-BLANK                                             
           AND CDEMO-PGM-REENTER                                                
               MOVE '*'                TO ACCTSIDO OF CACTVWAO                  
               MOVE DFHRED             TO ACCTSIDC OF CACTVWAO                  
           END-IF                                                               
                                                                                
           IF  WS-NO-INFO-MESSAGE                                               
               MOVE DFHBMDAR           TO INFOMSGC OF CACTVWAO                  
           ELSE                                                                 
               MOVE DFHNEUTR           TO INFOMSGC OF CACTVWAO                  
           END-IF                                                               
           .                                                                    
                                                                                
       1300-SETUP-SCREEN-ATTRS-EXIT.                                            
           EXIT                                                                 
           .                                                                    
      ****************************************************************          
      * 1400-SEND-SCREEN: sends the COACTVW/CACTVWA BMS map                     
      * to the 3270 terminal. Sets CDEMO-PGM-REENTER so the                     
      * next invocation processes user input. Uses CURSOR to                    
      * position at the field marked with length -1, ERASE                      
      * to clear the screen, and FREEKB to unlock keyboard.                     
      ****************************************************************          
       1400-SEND-SCREEN.                                                        
                                                                                
           MOVE LIT-THISMAPSET         TO CCARD-NEXT-MAPSET                     
           MOVE LIT-THISMAP            TO CCARD-NEXT-MAP                        
           SET  CDEMO-PGM-REENTER TO TRUE                                       
                                                                                
      * Sends output buffer CACTVWAO to the terminal screen                     
           EXEC CICS SEND MAP(CCARD-NEXT-MAP)                                   
                          MAPSET(CCARD-NEXT-MAPSET)                             
                          FROM(CACTVWAO)                                        
                          CURSOR                                                
                          ERASE                                                 
                          FREEKB                                                
                          RESP(WS-RESP-CD)                                      
           END-EXEC                                                             
           .                                                                    
       1400-SEND-SCREEN-EXIT.                                                   
           EXIT                                                                 
           .                                                                    
                                                                                
      ****************************************************************          
      * 2000-PROCESS-INPUTS: orchestrates user input handling.                  
      * Receives the BMS map from the terminal, validates the                   
      * account ID input, then stores the current program and                   
      * map context for the next display cycle.                                 
      ****************************************************************          
       2000-PROCESS-INPUTS.                                                     
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
      ****************************************************************          
      * 2100-RECEIVE-MAP: issues EXEC CICS RECEIVE MAP to                       
      * read user input from the 3270 terminal into the BMS                     
      * input buffer CACTVWAI. Captures RESP and RESP2 codes.                   
      ****************************************************************          
       2100-RECEIVE-MAP.                                                        
           EXEC CICS RECEIVE MAP(LIT-THISMAP)                                   
                     MAPSET(LIT-THISMAPSET)                                     
                     INTO(CACTVWAI)                                             
                     RESP(WS-RESP-CD)                                           
                     RESP2(WS-REAS-CD)                                          
           END-EXEC                                                             
           .                                                                    
                                                                                
       2100-RECEIVE-MAP-EXIT.                                                   
           EXIT                                                                 
           .                                                                    
      ****************************************************************          
      * 2200-EDIT-MAP-INPUTS: validates all user-entered                        
      * fields. Initializes flags to OK, reads account ID                       
      * from the input buffer, delegates to 2210-EDIT-ACCOUNT                   
      * for field-level validation, then checks if no search                    
      * criteria were received (blank account ID).                              
      ****************************************************************          
       2200-EDIT-MAP-INPUTS.                                                    
                                                                                
           SET INPUT-OK                  TO TRUE                                
           SET FLG-ACCTFILTER-ISVALID    TO TRUE                                
                                                                                
      *    REPLACE * WITH LOW-VALUES                                            
           IF  ACCTSIDI OF CACTVWAI = '*'                                       
           OR  ACCTSIDI OF CACTVWAI = SPACES                                    
               MOVE LOW-VALUES           TO  CC-ACCT-ID                         
           ELSE                                                                 
               MOVE ACCTSIDI OF CACTVWAI TO  CC-ACCT-ID                         
           END-IF                                                               
                                                                                
      *    INDIVIDUAL FIELD EDITS                                               
           PERFORM 2210-EDIT-ACCOUNT                                            
              THRU 2210-EDIT-ACCOUNT-EXIT                                       
                                                                                
      *    CROSS FIELD EDITS                                                    
           IF  FLG-ACCTFILTER-BLANK                                             
               SET NO-SEARCH-CRITERIA-RECEIVED TO TRUE                          
           END-IF                                                               
           .                                                                    
                                                                                
       2200-EDIT-MAP-INPUTS-EXIT.                                               
           EXIT                                                                 
           .                                                                    
                                                                                
      ****************************************************************          
      * 2210-EDIT-ACCOUNT: validates the account ID input.                      
      * Checks for: blank/missing (prompts user), non-numeric                   
      * or all-zeros (rejects with error message). On success,                  
      * stores the validated ID into CDEMO-ACCT-ID in the                       
      * COMMAREA for use by the 9000-READ-ACCT join logic.                      
      ****************************************************************          
       2210-EDIT-ACCOUNT.                                                       
           SET FLG-ACCTFILTER-NOT-OK TO TRUE                                    
                                                                                
      *    Not supplied                                                         
           IF CC-ACCT-ID   EQUAL LOW-VALUES                                     
           OR CC-ACCT-ID   EQUAL SPACES                                         
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
           OR CC-ACCT-ID  EQUAL ZEROES                                          
              SET INPUT-ERROR TO TRUE                                           
              SET FLG-ACCTFILTER-NOT-OK TO TRUE                                 
              IF WS-RETURN-MSG-OFF                                              
                MOVE                                                            
              'Account Filter must  be a non-zero 11 digit number'      00
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
                                                                                
      ****************************************************************          
      * 9000-READ-ACCT: orchestrates the 3-entity join that                     
      * retrieves all data for the account view display.                        
      * Execution order (each step exits early on failure):                     
      *   1. 9200: Read CXACAIX (card xref AIX by account)                      
      *      -> obtains XREF-CUST-ID and XREF-CARD-NUM                          
      *   2. 9300: Read ACCTDAT (account master by acct ID)                     
      *      -> obtains balances, limits, dates, status                         
      *   3. 9400: Read CUSTDAT (customer master by cust ID                     
      *            obtained from the xref record in step 1)                     
      *      -> obtains demographics, name, address, SSN                        
      * If any read fails (NOTFND or error), sets INPUT-ERROR                   
      * and returns so the error message is displayed.                          
      ****************************************************************          
       9000-READ-ACCT.                                                          
                                                                                
           SET  WS-NO-INFO-MESSAGE  TO TRUE
           
           MOVE CDEMO-ACCT-ID TO WS-CARD-RID-ACCT-ID                            
                                                                                
           PERFORM 9200-GETCARDXREF-BYACCT                                      
              THRU 9200-GETCARDXREF-BYACCT-EXIT                                 
                                                                                
      *    IF DID-NOT-FIND-ACCT-IN-CARDXREF                                     
           IF FLG-ACCTFILTER-NOT-OK                                             
              GO TO 9000-READ-ACCT-EXIT                                         
           END-IF                                                               
                                                                                
           PERFORM 9300-GETACCTDATA-BYACCT                                      
              THRU 9300-GETACCTDATA-BYACCT-EXIT                                 
                                                                                
           IF DID-NOT-FIND-ACCT-IN-ACCTDAT                                      
              GO TO 9000-READ-ACCT-EXIT                                         
           END-IF                                                               
                                                                                
           MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID                            
                                                                                
           PERFORM 9400-GETCUSTDATA-BYCUST                                      
              THRU 9400-GETCUSTDATA-BYCUST-EXIT                                 
                                                                                
           IF DID-NOT-FIND-CUST-IN-CUSTDAT                                      
              GO TO 9000-READ-ACCT-EXIT                                         
           END-IF                                                               
                                                                                
                                                                                
           .                                                                    
                                                                                
       9000-READ-ACCT-EXIT.                                                     
           EXIT                                                                 
           .                                                                    
      ****************************************************************          
      * 9200-GETCARDXREF-BYACCT: reads the card cross-                          
      * reference file via the CXACAIX alternate index (AIX).                   
      * CXACAIX is an AIX/PATH defined over the CARDXREF                        
      * base cluster, allowing lookup by account number                         
      * instead of the primary key (card number). This is                       
      * the first step of the 3-entity join — it resolves                       
      * the account ID to a customer ID and card number.                        
      *                                                                         
      * RESP handling:                                                          
      *   NORMAL  -> stores XREF-CUST-ID and XREF-CARD-NUM                      
      *   NOTFND  -> account not in cross-reference file                        
      *   OTHER   -> unexpected VSAM error (logs details)                       
      ****************************************************************          
       9200-GETCARDXREF-BYACCT.                                                 
                                                                                
      *    Read the Card file. Access via alternate index ACCTID                
      *                                                                         
      * Reads CXACAIX using account ID as the alternate key.                    
      * KEYLENGTH specifies the AIX key size (11 bytes).                        
           EXEC CICS READ                                                       
                DATASET   (LIT-CARDXREFNAME-ACCT-PATH)                          
                RIDFLD    (WS-CARD-RID-ACCT-ID-X)                               
                KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)                     
                INTO      (CARD-XREF-RECORD)                                    
                LENGTH    (LENGTH OF CARD-XREF-RECORD)                          
                RESP      (WS-RESP-CD)                                          
                RESP2     (WS-REAS-CD)                                          
           END-EXEC                                                             
                                                                                
      * Evaluates CICS response from the cross-reference read                   
           EVALUATE WS-RESP-CD                                                  
               WHEN DFHRESP(NORMAL)                                             
                  MOVE XREF-CUST-ID               TO CDEMO-CUST-ID              
                  MOVE XREF-CARD-NUM              TO CDEMO-CARD-NUM             
               WHEN DFHRESP(NOTFND)                                             
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-ACCTFILTER-NOT-OK       TO TRUE                       
                  IF WS-RETURN-MSG-OFF                                          
                    MOVE WS-RESP-CD               TO ERROR-RESP                 
                    MOVE WS-REAS-CD               TO ERROR-RESP2                
                    STRING                                                      
                    'Account:'                                                  
                     WS-CARD-RID-ACCT-ID-X                                      
                    ' not found in'                                             
                    ' Cross ref file.  Resp:'                                   
                    ERROR-RESP                                                  
                    ' Reas:'                                                    
                    ERROR-RESP2                                                 
                    DELIMITED BY SIZE                                           
                    INTO WS-RETURN-MSG                                          
                    END-STRING                                                  
                  END-IF                                                        
               WHEN OTHER                                                       
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-ACCTFILTER-NOT-OK                TO TRUE              
                  MOVE 'READ'                     TO ERROR-OPNAME               
                  MOVE LIT-CARDXREFNAME-ACCT-PATH TO ERROR-FILE                 
                  MOVE WS-RESP-CD                 TO ERROR-RESP                 
                  MOVE WS-REAS-CD                 TO ERROR-RESP2                
                  MOVE WS-FILE-ERROR-MESSAGE      TO WS-RETURN-MSG              
      *                                              WS-LONG-MSG                
      *          PERFORM SEND-LONG-TEXT                                         
           END-EVALUATE                                                         
           .                                                                    
       9200-GETCARDXREF-BYACCT-EXIT.                                            
           EXIT                                                                 
           .                                                                    
      ****************************************************************          
      * 9300-GETACCTDATA-BYACCT: reads the account master                       
      * file (ACCTDAT VSAM KSDS) using the account ID as                        
      * the primary key. Populates ACCOUNT-RECORD (300 bytes)                   
      * with balances, credit limits, dates, and status.                        
      *                                                                         
      * RESP handling:                                                          
      *   NORMAL  -> sets FOUND-ACCT-IN-MASTER flag                             
      *   NOTFND  -> account not in master file                                 
      *   OTHER   -> unexpected VSAM error (logs details)                       
      ****************************************************************          
       9300-GETACCTDATA-BYACCT.                                                 
                                                                                
      * Reads ACCTDAT using account ID as the primary key                       
           EXEC CICS READ                                                       
                DATASET   (LIT-ACCTFILENAME)                                    
                RIDFLD    (WS-CARD-RID-ACCT-ID-X)                               
                KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)                     
                INTO      (ACCOUNT-RECORD)                                      
                LENGTH    (LENGTH OF ACCOUNT-RECORD)                            
                RESP      (WS-RESP-CD)                                          
                RESP2     (WS-REAS-CD)                                          
           END-EXEC                                                             
                                                                                
      * Evaluates CICS response from the account master read                    
           EVALUATE WS-RESP-CD                                                  
               WHEN DFHRESP(NORMAL)                                             
                  SET FOUND-ACCT-IN-MASTER        TO TRUE                       
               WHEN DFHRESP(NOTFND)                                             
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-ACCTFILTER-NOT-OK       TO TRUE                       
      *           SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE                      
                  IF WS-RETURN-MSG-OFF                                          
                    MOVE WS-RESP-CD               TO ERROR-RESP                 
                    MOVE WS-REAS-CD               TO ERROR-RESP2                
                    STRING                                                      
                    'Account:'                                                  
                     WS-CARD-RID-ACCT-ID-X                                      
                    ' not found in'                                             
                    ' Acct Master file.Resp:'                                   
                    ERROR-RESP                                                  
                    ' Reas:'                                                    
                    ERROR-RESP2                                                 
                    DELIMITED BY SIZE                                           
                    INTO WS-RETURN-MSG                                          
                    END-STRING                                                  
                  END-IF                                                        
      *                                                                         
               WHEN OTHER                                                       
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-ACCTFILTER-NOT-OK                TO TRUE              
                  MOVE 'READ'                     TO ERROR-OPNAME               
                  MOVE LIT-ACCTFILENAME           TO ERROR-FILE                 
                  MOVE WS-RESP-CD                 TO ERROR-RESP                 
                  MOVE WS-REAS-CD                 TO ERROR-RESP2                
                  MOVE WS-FILE-ERROR-MESSAGE      TO WS-RETURN-MSG              
      *                                              WS-LONG-MSG                
      *           PERFORM SEND-LONG-TEXT                                        
           END-EVALUATE                                                         
           .                                                                    
       9300-GETACCTDATA-BYACCT-EXIT.                                            
           EXIT                                                                 
           .                                                                    
                                                                                
      ****************************************************************          
      * 9400-GETCUSTDATA-BYCUST: reads the customer master                      
      * file (CUSTDAT VSAM KSDS) using the customer ID                          
      * obtained from the cross-reference record in step 1.                     
      * Populates CUSTOMER-RECORD (500 bytes) with name,                        
      * address, SSN, FICO score, and other demographics.                       
      * This completes the 3-entity join.                                       
      *                                                                         
      * RESP handling:                                                          
      *   NORMAL  -> sets FOUND-CUST-IN-MASTER flag                             
      *   NOTFND  -> customer not in master file                                
      *   OTHER   -> unexpected VSAM error (logs details)                       
      ****************************************************************          
       9400-GETCUSTDATA-BYCUST.                                                 
      * Reads CUSTDAT using customer ID from the xref record                    
           EXEC CICS READ                                                       
                DATASET   (LIT-CUSTFILENAME)                                    
                RIDFLD    (WS-CARD-RID-CUST-ID-X)                               
                KEYLENGTH (LENGTH OF WS-CARD-RID-CUST-ID-X)                     
                INTO      (CUSTOMER-RECORD)                                     
                LENGTH    (LENGTH OF CUSTOMER-RECORD)                           
                RESP      (WS-RESP-CD)                                          
                RESP2     (WS-REAS-CD)                                          
           END-EXEC                                                             
                                                                                
      * Evaluates CICS response from the customer master read                   
           EVALUATE WS-RESP-CD                                                  
               WHEN DFHRESP(NORMAL)                                             
                  SET FOUND-CUST-IN-MASTER        TO TRUE                       
               WHEN DFHRESP(NOTFND)                                             
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-CUSTFILTER-NOT-OK       TO TRUE                       
      *           SET DID-NOT-FIND-CUST-IN-CUSTDAT TO TRUE                      
                  MOVE WS-RESP-CD               TO ERROR-RESP                   
                  MOVE WS-REAS-CD               TO ERROR-RESP2                  
                  IF WS-RETURN-MSG-OFF                                          
                    STRING                                                      
                    'CustId:'                                                   
                     WS-CARD-RID-CUST-ID-X                                      
                    ' not found'                                                
                    ' in customer master.Resp: '                                
                    ERROR-RESP                                                  
                    ' REAS:'                                                    
                    ERROR-RESP2                                                 
                    DELIMITED BY SIZE                                           
                    INTO WS-RETURN-MSG                                          
                    END-STRING                                                  
                  END-IF                                                        
               WHEN OTHER                                                       
                  SET INPUT-ERROR                 TO TRUE                       
                  SET FLG-CUSTFILTER-NOT-OK                TO TRUE              
                  MOVE 'READ'                     TO ERROR-OPNAME               
                  MOVE LIT-CUSTFILENAME           TO ERROR-FILE                 
                  MOVE WS-RESP-CD                 TO ERROR-RESP                 
                  MOVE WS-REAS-CD                 TO ERROR-RESP2                
                  MOVE WS-FILE-ERROR-MESSAGE      TO WS-RETURN-MSG              
      *                                              WS-LONG-MSG                
      *           PERFORM SEND-LONG-TEXT                                        
           END-EVALUATE                                                         
           .                                                                    
       9400-GETCUSTDATA-BYCUST-EXIT.                                            
           EXIT                                                                 
           .                                                                    
                                                                                
      *****************************************************************         
      * Plain text exit - Dont use in production                      *         
      *****************************************************************         
      * Sends a plain text message to the terminal and returns                  
      * to CICS without a map. Used for unexpected-data abends.                 
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
      *****************************************************************         
      * Display Long text and exit                                    *         
      * This is primarily for debugging and should not be used in     *         
      * regular course                                                *         
      *****************************************************************         
      * Sends the 500-byte debug text buffer and returns.                       
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
      *Common code to store PFKey
      ******************************************************************
      * CSSTRPFY: included COBOL paragraphs that map the                        
      * EIBAID byte to CCARD-AID-* condition flags in the                       
      * CC-WORK-AREA. Also folds PF13-PF24 onto PF1-PF12.                       
      * See app/cpy/CSSTRPFY.cpy                                                
       COPY 'CSSTRPFY'
           .

      ****************************************************************          
      * ABEND-ROUTINE: handles unexpected abends. Sets a                        
      * default message if none was provided, identifies                        
      * this program as the culprit, sends the abend data                       
      * to the terminal, cancels the abend handler to                           
      * prevent recursion, then forces an abend with code                       
      * '9999' so CICS logs the failure.                                        
      ****************************************************************          
       ABEND-ROUTINE.                                                           
                                                                                
           IF ABEND-MSG EQUAL LOW-VALUES                                        
              MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG                    
           END-IF                                                               
                                                                                
           MOVE LIT-THISPGM       TO ABEND-CULPRIT                              
                                                                                
      * Sends abend diagnostic data to the terminal screen                      
           EXEC CICS SEND                                                       
                            FROM (ABEND-DATA)                                   
                            LENGTH(LENGTH OF ABEND-DATA)                        
                            NOHANDLE                                            
           END-EXEC                                                             
                                                                                
      * Cancels abend handler to prevent recursive abends                       
           EXEC CICS HANDLE ABEND                                               
                CANCEL                                                          
           END-EXEC                                                             
                                                                                
      * Forces a CICS abend with code 9999 for diagnostics                      
           EXEC CICS ABEND                                                      
                ABCODE('9999')                                                  
           END-EXEC                                                             
           .                                                                    
                                                                                
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:32 CDT
      *
