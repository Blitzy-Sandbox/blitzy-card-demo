      ******************************************************************
      * Program     : CBTRN02C.CBL                                      
      * Application : CardDemo                                          
      * Type        : BATCH COBOL Program                                
      * Function    : Post the records from daily transaction file.     
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
      *
      * CBTRN02C - Transaction Posting Engine (Batch)
      *
      * Reads daily transactions from DALYTRAN sequentially and
      * performs a multi-stage validation cascade before posting.
      * Validation stages:
      *   1. Cross-reference lookup (card number -> account)
      *   2. Account verification (account record exists)
      *   3. Credit limit check (balance + amount <= limit)
      *   4. Expiration date check (account not expired)
      * Valid transactions are posted to TRANSACT, their amounts
      * are accumulated in TCATBAL category-balance records, and
      * account balances are updated via REWRITE. Rejected
      * transactions are written to DALYREJS with a reason code.
      * Sets RETURN-CODE = 4 if any rejects occurred.
      *
      * Invoked by: POSTTRAN.jcl (EXEC PGM=CBTRN02C)
      * Pipeline position: First business processing step
      *
      * Files accessed:
      *   DALYTRAN  - Daily transaction staging (sequential input)
      *   TRANSACT  - Transaction master (sequential output)
      *   XREFFILE  - Card cross-reference (KSDS, random read)
      *   DALYREJS  - Daily rejects (sequential output)
      *   ACCTFILE  - Account master (KSDS, I-O for REWRITE)
      *   TCATBALF  - Category balance (KSDS, I-O for WRITE
      *               and REWRITE)
      *
      * Copybooks: CVTRA06Y (daily transaction record),
      *            CVTRA05Y (transaction record),
      *            CVACT03Y (cross-reference record),
      *            CVACT01Y (account record),
      *            CVTRA01Y (category-balance record)
      *
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBTRN02C.                                                 
       AUTHOR.        AWS.                                                      
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
       FILE-CONTROL.                                                            
      * Daily transaction staging file — sequential input
      * Reads 350-byte records produced by extract step
           SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN                              
                  ORGANIZATION IS SEQUENTIAL                                    
                  ACCESS MODE  IS SEQUENTIAL                                    
                  FILE STATUS  IS DALYTRAN-STATUS.                              
                                                                                
      * Transaction master VSAM KSDS — random WRITE for
      * posting validated transactions (keyed by TRAN-ID)
           SELECT TRANSACT-FILE ASSIGN TO TRANFILE                              
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-TRANS-ID                                   
                  FILE STATUS  IS TRANFILE-STATUS.                              
                                                                                
      * Card-to-account cross-reference VSAM KSDS
      * Random READ by card number for validation stage 1
           SELECT XREF-FILE ASSIGN TO   XREFFILE                                
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-XREF-CARD-NUM                              
                  FILE STATUS  IS XREFFILE-STATUS.                              
                                                                                
      * Daily rejects file — sequential output
      * Captures invalid transactions with rejection reason
           SELECT DALYREJS-FILE ASSIGN TO DALYREJS                              
                  ORGANIZATION IS SEQUENTIAL                                    
                  ACCESS MODE  IS SEQUENTIAL                                    
                  FILE STATUS  IS DALYREJS-STATUS.                              
                                                                                
      * Account master VSAM KSDS — I-O mode for REWRITE
      * Updates account current balance after posting
           SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE                               
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-ACCT-ID                                    
                  FILE STATUS  IS ACCTFILE-STATUS.                              
                                                                                
      * Transaction category balance VSAM KSDS — I-O mode
      * WRITE for new categories, REWRITE for existing
      * Composite key: account-ID + type-code + category
           SELECT TCATBAL-FILE ASSIGN TO TCATBALF                               
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-TRAN-CAT-KEY                               
                  FILE STATUS  IS TCATBALF-STATUS.                              
                                                                                
      *                                                                         
       DATA DIVISION.                                                           
       FILE SECTION.                                                            
      * FD for daily transaction staging input (350 bytes)
      * Key field FD-TRAN-ID maps to DALYTRAN-ID in WS copy
       FD  DALYTRAN-FILE.                                                       
       01  FD-TRAN-RECORD.                                                      
           05 FD-TRAN-ID                        PIC X(16).                      
           05 FD-CUST-DATA                      PIC X(334).                     
                                                                                
      * FD for transaction master VSAM output (350 bytes)
      * FD-TRANS-ID is the RECORD KEY for random WRITE
       FD  TRANSACT-FILE.                                                       
       01  FD-TRANFILE-REC.                                                     
           05 FD-TRANS-ID                       PIC X(16).                      
           05 FD-ACCT-DATA                      PIC X(334).                     
                                                                                
      * FD for card cross-reference VSAM (50 bytes)
      * FD-XREF-CARD-NUM is the RECORD KEY for random READ
       FD  XREF-FILE.                                                           
       01  FD-XREFFILE-REC.                                                     
           05 FD-XREF-CARD-NUM                  PIC X(16).                      
           05 FD-XREF-DATA                      PIC X(34).                      
                                                                                
      * FD for daily rejects sequential output (430 bytes)
      * 350-byte transaction data + 80-byte rejection trailer
       FD  DALYREJS-FILE.                                                       
       01  FD-REJS-RECORD.                                                      
           05 FD-REJECT-RECORD                  PIC X(350).                     
           05 FD-VALIDATION-TRAILER             PIC X(80).                      
                                                                                
      * FD for account master VSAM I-O (300 bytes)
      * FD-ACCT-ID is RECORD KEY for READ and REWRITE
       FD  ACCOUNT-FILE.                                                        
       01  FD-ACCTFILE-REC.                                                     
           05 FD-ACCT-ID                        PIC 9(11).                      
           05 FD-ACCT-DATA                      PIC X(289).                     
                                                                                
      * FD for category balance VSAM I-O (50 bytes)
      * Composite key: account(11) + type(2) + category(4)
       FD  TCATBAL-FILE.                                                        
       01  FD-TRAN-CAT-BAL-RECORD.                                              
           05 FD-TRAN-CAT-KEY.                                                  
              10 FD-TRANCAT-ACCT-ID             PIC 9(11).                      
              10 FD-TRANCAT-TYPE-CD             PIC X(02).                      
              10 FD-TRANCAT-CD                  PIC 9(04).                      
           05 FD-FD-TRAN-CAT-DATA               PIC X(33).                      
                                                                                
       WORKING-STORAGE SECTION.                                                 
                                                                                
      *****************************************************************         
      * CVTRA06Y: 350-byte daily transaction staging record
      * See app/cpy/CVTRA06Y.cpy for DALYTRAN-RECORD layout
       COPY CVTRA06Y.                                                           
      * FILE STATUS for DALYTRAN sequential input
       01  DALYTRAN-STATUS.                                                     
           05  DALYTRAN-STAT1      PIC X.                                       
           05  DALYTRAN-STAT2      PIC X.                                       
                                                                                
      * CVTRA05Y: 350-byte posted transaction master record
      * See app/cpy/CVTRA05Y.cpy for TRAN-RECORD layout
       COPY CVTRA05Y.                                                           
      * FILE STATUS for TRANSACT VSAM output
       01  TRANFILE-STATUS.                                                     
           05  TRANFILE-STAT1      PIC X.                                       
           05  TRANFILE-STAT2      PIC X.                                       
                                                                                
      * CVACT03Y: 50-byte card cross-reference record
      * See app/cpy/CVACT03Y.cpy for CARD-XREF-RECORD layout
       COPY CVACT03Y.                                                           
      * FILE STATUS for XREFFILE VSAM random read
       01  XREFFILE-STATUS.                                                     
           05  XREFFILE-STAT1      PIC X.                                       
           05  XREFFILE-STAT2      PIC X.                                       
                                                                                
      * FILE STATUS for DALYREJS sequential output
      * No copybook — reject file uses FD-REJS-RECORD layout
       01  DALYREJS-STATUS.                                                     
           05  DALYREJS-STAT1      PIC X.                                       
           05  DALYREJS-STAT2      PIC X.                                       
                                                                                
      * CVACT01Y: 300-byte account master record
      * See app/cpy/CVACT01Y.cpy for ACCOUNT-RECORD layout
       COPY CVACT01Y.                                                           
      * FILE STATUS for ACCTFILE VSAM I-O (read/rewrite)
       01  ACCTFILE-STATUS.                                                     
           05  ACCTFILE-STAT1      PIC X.                                       
           05  ACCTFILE-STAT2      PIC X.                                       
                                                                                
      * CVTRA01Y: 50-byte category balance record
      * See app/cpy/CVTRA01Y.cpy for TRAN-CAT-BAL-RECORD
       COPY CVTRA01Y.                                                           
      * FILE STATUS for TCATBALF VSAM I-O (read/write/rewrite)
       01  TCATBALF-STATUS.                                                     
           05  TCATBALF-STAT1      PIC X.                                       
           05  TCATBALF-STAT2      PIC X.                                       
                                                                                
      * General I/O status work area for display formatting
       01  IO-STATUS.                                                           
           05  IO-STAT1            PIC X.                                       
           05  IO-STAT2            PIC X.                                       
      * Binary-to-alpha conversion area for status display
       01  TWO-BYTES-BINARY        PIC 9(4) BINARY.                             
       01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.                  
           05  TWO-BYTES-LEFT      PIC X.                                       
           05  TWO-BYTES-RIGHT     PIC X.                                       
      * Formatted 4-digit status code for DISPLAY output
       01  IO-STATUS-04.                                                        
           05  IO-STATUS-0401      PIC 9   VALUE 0.                             
           05  IO-STATUS-0403      PIC 999 VALUE 0.                             
                                                                                
      * Application result code — controls error flow
      * APPL-AOK (0) = success, APPL-EOF (16) = end of file
      * Value 12 signals a critical I/O error
       01  APPL-RESULT             PIC S9(9)   COMP.                            
           88  APPL-AOK            VALUE 0.                                     
           88  APPL-EOF            VALUE 16.                                    
                                                                                
      * End-of-file flag for DALYTRAN read loop
       01  END-OF-FILE             PIC X(01)    VALUE 'N'.                      
      * ABEND code and timing for CEE3ABD abnormal end call
       01  ABCODE                  PIC S9(9) BINARY.                            
       01  TIMING                  PIC S9(9) BINARY.                            
      * Timestamp work areas for processing-timestamp generation
      * COBOL-TS receives FUNCTION CURRENT-DATE output
      * DB2-FORMAT-TS is reformatted as YYYY-MM-DD-HH.MM.SS.NN0000
      * T I M E S T A M P   D B 2  X(26)     EEEE-MM-DD-UU.MM.SS.HH0000         
       01  COBOL-TS.                                                            
           05 COB-YYYY                  PIC X(04).                              
           05 COB-MM                    PIC X(02).                              
           05 COB-DD                    PIC X(02).                              
           05 COB-HH                    PIC X(02).                              
           05 COB-MIN                   PIC X(02).                              
           05 COB-SS                    PIC X(02).                              
           05 COB-MIL                   PIC X(02).                              
           05 COB-REST                  PIC X(05).                              
       01  DB2-FORMAT-TS                PIC X(26).                              
       01  FILLER REDEFINES DB2-FORMAT-TS.                                      
           06 DB2-YYYY                  PIC X(004).                      E      
           06 DB2-STREEP-1              PIC X.                           -      
           06 DB2-MM                    PIC X(002).                      M      
           06 DB2-STREEP-2              PIC X.                           -      
           06 DB2-DD                    PIC X(002).                      D      
           06 DB2-STREEP-3              PIC X.                           -      
           06 DB2-HH                    PIC X(002).                      U      
           06 DB2-DOT-1                 PIC X.                                  
           06 DB2-MIN                   PIC X(002).                             
           06 DB2-DOT-2                 PIC X.                                  
           06 DB2-SS                    PIC X(002).                             
           06 DB2-DOT-3                 PIC X.                                  
           06 DB2-MIL                   PIC 9(002).                             
           06 DB2-REST                  PIC X(04).                              
                                                                                
      * Reject output record: transaction data + reason trailer
      * Written to DALYREJS for failed validation records
        01 REJECT-RECORD.                                                       
           05 REJECT-TRAN-DATA          PIC X(350).                             
           05 VALIDATION-TRAILER        PIC X(80).                              
                                                                                
      * Validation failure details populated during cascade
      * Reason codes: 100=bad card, 101=no account,
      *   102=over limit, 103=expired account,
      *   109=rewrite failure
        01 WS-VALIDATION-TRAILER.                                               
           05 WS-VALIDATION-FAIL-REASON      PIC 9(04).                         
           05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).                         
                                                                                
      * Processing counters and temporary balance work area
      * WS-TRANSACTION-COUNT: total transactions read
      * WS-REJECT-COUNT: total rejected transactions
      * WS-TEMP-BAL: scratch area for credit limit check
        01 WS-COUNTERS.                                                         
           05 WS-TRANSACTION-COUNT          PIC 9(09) VALUE 0.                  
           05 WS-REJECT-COUNT               PIC 9(09) VALUE 0.                  
           05 WS-TEMP-BAL                   PIC S9(09)V99.                      
                                                                                
      * Processing flags
      * WS-CREATE-TRANCAT-REC: 'Y' when TCATBAL record is new
        01 WS-FLAGS.                                                            
           05 WS-CREATE-TRANCAT-REC         PIC X(01) VALUE 'N'.                
                                                                                
      *****************************************************************         
      * Main control: Opens all 6 files, reads daily transactions
      * in a loop, validates each, posts or rejects, then closes
      * files and sets RETURN-CODE based on reject count.
      *****************************************************************         
       PROCEDURE DIVISION.                                                      
           DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.                    
      * Open all files — abends immediately if any open fails
           PERFORM 0000-DALYTRAN-OPEN.                                          
           PERFORM 0100-TRANFILE-OPEN.                                          
           PERFORM 0200-XREFFILE-OPEN.                                          
           PERFORM 0300-DALYREJS-OPEN.                                          
           PERFORM 0400-ACCTFILE-OPEN.                                          
           PERFORM 0500-TCATBALF-OPEN.                                          
                                                                                
      * Main processing loop — reads until EOF on DALYTRAN
      * For each record: increment counter, reset validation
      * fields, run validation cascade, then post or reject
           PERFORM UNTIL END-OF-FILE = 'Y'                                      
               IF  END-OF-FILE = 'N'                                            
                   PERFORM 1000-DALYTRAN-GET-NEXT                               
                   IF  END-OF-FILE = 'N'                                        
                     ADD 1 TO WS-TRANSACTION-COUNT                              
      *              DISPLAY DALYTRAN-RECORD                                    
                     MOVE 0 TO WS-VALIDATION-FAIL-REASON                        
                     MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC              
                     PERFORM 1500-VALIDATE-TRAN                                 
                     IF WS-VALIDATION-FAIL-REASON = 0                           
                       PERFORM 2000-POST-TRANSACTION                            
                     ELSE                                                       
                       ADD 1 TO WS-REJECT-COUNT                                 
                       PERFORM 2500-WRITE-REJECT-REC                            
                     END-IF                                                     
                   END-IF                                                       
               END-IF                                                           
           END-PERFORM.                                                         
                                                                                
      * Close all files in reverse logical order
           PERFORM 9000-DALYTRAN-CLOSE.                                         
           PERFORM 9100-TRANFILE-CLOSE.                                         
           PERFORM 9200-XREFFILE-CLOSE.                                         
           PERFORM 9300-DALYREJS-CLOSE.                                         
           PERFORM 9400-ACCTFILE-CLOSE.                                         
           PERFORM 9500-TCATBALF-CLOSE.                                         
      * Display final processing summary counts
           DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT              
           DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT                   
      * Set RETURN-CODE 4 if any rejects; 0 if all posted OK
           IF WS-REJECT-COUNT > 0                                               
              MOVE 4 TO RETURN-CODE                                             
           END-IF                                                               
           DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'.                      
                                                                                
           GOBACK.                                                              
      *---------------------------------------------------------------*         
      * Opens daily transaction staging file for sequential input.
      * Abends via 9999-ABEND-PROGRAM if FILE STATUS is not '00'.
      *---------------------------------------------------------------*         
       0000-DALYTRAN-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT DALYTRAN-FILE                                             
           IF  DALYTRAN-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING DALYTRAN'                                 
               MOVE DALYTRAN-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens transaction master VSAM for output (WRITE only).
      * Abends if the dataset cannot be opened.
      *---------------------------------------------------------------*         
       0100-TRANFILE-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN OUTPUT TRANSACT-FILE                                            
           IF  TRANFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING TRANSACTION FILE'                         
               MOVE TRANFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Opens card cross-reference VSAM for random input READ.
      * Used for validation stage 1: card-to-account lookup.
      *---------------------------------------------------------------*         
       0200-XREFFILE-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT XREF-FILE                                                 
           IF  XREFFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING CROSS REF FILE'                           
               MOVE XREFFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens daily rejects file for sequential output WRITE.
      * Captures transactions that fail the validation cascade.
      *---------------------------------------------------------------*         
       0300-DALYREJS-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN OUTPUT DALYREJS-FILE                                            
           IF  DALYREJS-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING DALY REJECTS FILE'                        
               MOVE DALYREJS-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens account master VSAM in I-O mode for balance REWRITE.
      * Reads account to verify, then rewrites updated balance.
      *---------------------------------------------------------------*         
       0400-ACCTFILE-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN I-O  ACCOUNT-FILE                                               
           IF  ACCTFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING ACCOUNT MASTER FILE'                      
               MOVE ACCTFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens category balance VSAM in I-O mode.
      * Supports WRITE for new keys, REWRITE for existing.
      *---------------------------------------------------------------*         
       0500-TCATBALF-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN I-O  TCATBAL-FILE                                               
           IF  TCATBALF-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING TRANSACTION BALANCE FILE'                 
               MOVE TCATBALF-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Reads the next daily transaction record sequentially.
      * Status '00' = success, '10' = EOF, other = error.
      * On EOF sets END-OF-FILE = 'Y' to terminate main loop.
      * On error displays FILE STATUS and abends.
      *---------------------------------------------------------------*         
       1000-DALYTRAN-GET-NEXT.                                                  
           READ DALYTRAN-FILE INTO DALYTRAN-RECORD.                             
           IF  DALYTRAN-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
      *        DISPLAY DALYTRAN-RECORD                                          
           ELSE                                                                 
               IF  DALYTRAN-STATUS = '10'                                       
                   MOVE 16 TO APPL-RESULT                                       
               ELSE                                                             
                   MOVE 12 TO APPL-RESULT                                       
               END-IF                                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               IF  APPL-EOF                                                     
                   MOVE 'Y' TO END-OF-FILE                                      
               ELSE                                                             
                   DISPLAY 'ERROR READING DALYTRAN FILE'                        
                   MOVE DALYTRAN-STATUS TO IO-STATUS                            
                   PERFORM 9910-DISPLAY-IO-STATUS                               
                   PERFORM 9999-ABEND-PROGRAM                                   
               END-IF                                                           
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Validation cascade orchestrator. Calls each validation
      * stage in sequence; short-circuits on first failure.
      * Stage 1 (1500-A): Cross-ref lookup by card number
      * Stage 2 (1500-B): Account lookup + credit/expiry checks
      * WS-VALIDATION-FAIL-REASON = 0 means all stages passed.
      *---------------------------------------------------------------*         
       1500-VALIDATE-TRAN.                                                      
           PERFORM 1500-A-LOOKUP-XREF.                                          
           IF WS-VALIDATION-FAIL-REASON = 0                                     
              PERFORM 1500-B-LOOKUP-ACCT                                        
           ELSE                                                                 
              CONTINUE                                                          
           END-IF                                                               
      * ADD MORE VALIDATIONS HERE                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Validation stage 1: Cross-reference lookup.
      * Reads XREFFILE by DALYTRAN-CARD-NUM to resolve the
      * card number to an account. Sets reason code 100 if
      * the card number is not found in the xref dataset.
      * On success, CARD-XREF-RECORD holds XREF-ACCT-ID
      * needed by stage 2. See app/cpy/CVACT03Y.cpy.
      *---------------------------------------------------------------*         
       1500-A-LOOKUP-XREF.                                                      
      *    DISPLAY 'CARD NUMBER: ' DALYTRAN-CARD-NUM                            
           MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM                           
           READ XREF-FILE INTO CARD-XREF-RECORD                                 
              INVALID KEY                                                       
                MOVE 100 TO WS-VALIDATION-FAIL-REASON                           
                MOVE 'INVALID CARD NUMBER FOUND'                                
                  TO WS-VALIDATION-FAIL-REASON-DESC                             
              NOT INVALID KEY                                                   
      *           DISPLAY 'ACCOUNT RECORD FOUND'                                
                  CONTINUE                                                      
           END-READ                                                             
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Validation stage 2: Account lookup and business checks.
      * Reads ACCTFILE by XREF-ACCT-ID from stage 1.
      * Reason 101: account record not found (INVALID KEY).
      * If found, performs two additional checks:
      *   Credit limit check (reason 102): computes projected
      *     balance = cycle-credit - cycle-debit + tran-amount
      *     and rejects if it exceeds ACCT-CREDIT-LIMIT.
      *   Expiration check (reason 103): compares account
      *     expiration date against transaction origin date.
      * See app/cpy/CVACT01Y.cpy for ACCOUNT-RECORD layout.
      *---------------------------------------------------------------*         
       1500-B-LOOKUP-ACCT.                                                      
           MOVE XREF-ACCT-ID TO FD-ACCT-ID                                      
           READ ACCOUNT-FILE INTO ACCOUNT-RECORD                                
              INVALID KEY                                                       
                MOVE 101 TO WS-VALIDATION-FAIL-REASON                           
                MOVE 'ACCOUNT RECORD NOT FOUND'                                 
                  TO WS-VALIDATION-FAIL-REASON-DESC                             
              NOT INVALID KEY                                                   
      *         DISPLAY 'ACCT-CREDIT-LIMIT:' ACCT-CREDIT-LIMIT                  
      *         DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT                       
      * Compute projected balance for credit limit check
                COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT                      
                                    - ACCT-CURR-CYC-DEBIT                       
                                    + DALYTRAN-AMT                              
                                                                                
      * Reject if projected balance exceeds credit limit
                IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL                             
                  CONTINUE                                                      
                ELSE                                                            
                  MOVE 102 TO WS-VALIDATION-FAIL-REASON                         
                  MOVE 'OVERLIMIT TRANSACTION'                                  
                    TO WS-VALIDATION-FAIL-REASON-DESC                           
                END-IF                                                          
      * Reject if account expired before transaction date
                IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)               
                  CONTINUE                                                      
                ELSE                                                            
                  MOVE 103 TO WS-VALIDATION-FAIL-REASON                         
                  MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'             
                    TO WS-VALIDATION-FAIL-REASON-DESC                           
                END-IF                                                          
           END-READ                                                             
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Posts a validated transaction. Copies all fields from
      * DALYTRAN-RECORD (staging) to TRAN-RECORD (master),
      * generates a processing timestamp, then orchestrates
      * three update operations:
      *   1. Update/create category balance (2700)
      *   2. Update account current balance (2800)
      *   3. Write posted record to TRANSACT VSAM (2900)
      * See app/cpy/CVTRA05Y.cpy for TRAN-RECORD layout and
      *     app/cpy/CVTRA06Y.cpy for DALYTRAN-RECORD layout.
      *---------------------------------------------------------------*         
       2000-POST-TRANSACTION.                                                   
      * Map daily transaction fields to posted record layout
           MOVE  DALYTRAN-ID            TO    TRAN-ID                           
           MOVE  DALYTRAN-TYPE-CD       TO    TRAN-TYPE-CD                      
           MOVE  DALYTRAN-CAT-CD        TO    TRAN-CAT-CD                       
           MOVE  DALYTRAN-SOURCE        TO    TRAN-SOURCE                       
           MOVE  DALYTRAN-DESC          TO    TRAN-DESC                         
           MOVE  DALYTRAN-AMT           TO    TRAN-AMT                          
           MOVE  DALYTRAN-MERCHANT-ID   TO    TRAN-MERCHANT-ID                  
           MOVE  DALYTRAN-MERCHANT-NAME TO    TRAN-MERCHANT-NAME                
           MOVE  DALYTRAN-MERCHANT-CITY TO    TRAN-MERCHANT-CITY                
           MOVE  DALYTRAN-MERCHANT-ZIP  TO    TRAN-MERCHANT-ZIP                 
           MOVE  DALYTRAN-CARD-NUM      TO    TRAN-CARD-NUM                     
           MOVE  DALYTRAN-ORIG-TS       TO    TRAN-ORIG-TS                      
      * Generate DB2-format processing timestamp
           PERFORM Z-GET-DB2-FORMAT-TIMESTAMP                                   
           MOVE  DB2-FORMAT-TS          TO    TRAN-PROC-TS                      
                                                                                
      * Execute the three posting sub-steps
           PERFORM 2700-UPDATE-TCATBAL                                          
           PERFORM 2800-UPDATE-ACCOUNT-REC                                      
           PERFORM 2900-WRITE-TRANSACTION-FILE                                  
                                                                                
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Writes a rejected transaction to the DALYREJS file.
      * Copies the original 350-byte DALYTRAN data and appends
      * the 80-byte validation trailer containing the failure
      * reason code and description. Abends on write error.
      *---------------------------------------------------------------*         
       2500-WRITE-REJECT-REC.                                                   
           MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA                             
           MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER                     
      *     DISPLAY '***' REJECT-RECORD                                         
           MOVE 8 TO APPL-RESULT                                                
           WRITE FD-REJS-RECORD FROM REJECT-RECORD                              
           IF DALYREJS-STATUS = '00'                                            
               MOVE 0 TO  APPL-RESULT                                           
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR WRITING TO REJECTS FILE'                          
               MOVE DALYREJS-STATUS  TO IO-STATUS                               
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Updates category balance for the posted transaction.
      * Builds composite key: XREF-ACCT-ID + TYPE-CD + CAT-CD
      * and reads TCATBALF. If record not found (status '23')
      * creates a new record via 2700-A; otherwise updates
      * existing record via 2700-B. Abends on unexpected error.
      * See app/cpy/CVTRA01Y.cpy for TRAN-CAT-BAL-RECORD.
      *---------------------------------------------------------------*         
       2700-UPDATE-TCATBAL.                                                     
      * Update the balances in transaction balance file.                        
      * Build the composite key from xref account + tran codes
           MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID                              
           MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD                          
           MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD                                
                                                                                
      * Attempt READ — INVALID KEY means new category combo
           MOVE 'N' TO WS-CREATE-TRANCAT-REC                                    
           READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD                           
              INVALID KEY                                                       
                DISPLAY 'TCATBAL record not found for key : '                   
                   FD-TRAN-CAT-KEY '.. Creating.'                               
                MOVE 'Y' TO WS-CREATE-TRANCAT-REC                               
           END-READ.                                                            
                                                                                
      * Status '00' (found) or '23' (not found) are expected
           IF  TCATBALF-STATUS = '00'  OR '23'                                  
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR READING TRANSACTION BALANCE FILE'                 
               MOVE TCATBALF-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF.                                                              
                                                                                
      * Route to CREATE (new key) or UPDATE (existing key)
           IF WS-CREATE-TRANCAT-REC = 'Y'                                       
              PERFORM 2700-A-CREATE-TCATBAL-REC                                 
           ELSE                                                                 
              PERFORM 2700-B-UPDATE-TCATBAL-REC                                 
           END-IF                                                               
                                                                                
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Creates a new category balance record when the composite
      * key (account + type + category) does not exist yet.
      * Initializes the record, sets the key fields, adds the
      * transaction amount as the initial balance, then WRITEs.
      *---------------------------------------------------------------*         
       2700-A-CREATE-TCATBAL-REC.                                               
           INITIALIZE TRAN-CAT-BAL-RECORD                                       
           MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID                                 
           MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD                             
           MOVE DALYTRAN-CAT-CD TO TRANCAT-CD                                   
           ADD DALYTRAN-AMT TO TRAN-CAT-BAL                                     
                                                                                
           WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD                
                                                                                
           IF  TCATBALF-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR WRITING TRANSACTION BALANCE FILE'                 
               MOVE TCATBALF-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF.                                                              
      *---------------------------------------------------------------*         
      * Updates an existing category balance record by adding
      * the transaction amount to TRAN-CAT-BAL and REWRITEing.
      *---------------------------------------------------------------*         
       2700-B-UPDATE-TCATBAL-REC.                                               
           ADD DALYTRAN-AMT TO TRAN-CAT-BAL                                     
           REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD              
                                                                                
           IF  TCATBALF-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR REWRITING TRANSACTION BALANCE FILE'               
               MOVE TCATBALF-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF.                                                              
                                                                                
      *---------------------------------------------------------------*         
      * Updates account balances to reflect the posted transaction.
      * Adds transaction amount to ACCT-CURR-BAL (running total).
      * Positive amounts accumulate in ACCT-CURR-CYC-CREDIT;
      * negative amounts accumulate in ACCT-CURR-CYC-DEBIT.
      * REWRITEs account record; sets reason 109 on failure.
      * See app/cpy/CVACT01Y.cpy for ACCOUNT-RECORD layout.
      *---------------------------------------------------------------*         
       2800-UPDATE-ACCOUNT-REC.                                                 
      * Update the balances in account record to reflect posted trans.          
      * Add to running current balance
           ADD DALYTRAN-AMT  TO ACCT-CURR-BAL                                   
      * Route to credit or debit cycle accumulator
           IF DALYTRAN-AMT >= 0                                                 
              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT                          
           ELSE                                                                 
              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT                           
           END-IF                                                               
                                                                                
      * Persist updated account via REWRITE
           REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD                         
              INVALID KEY                                                       
                MOVE 109 TO WS-VALIDATION-FAIL-REASON                           
                MOVE 'ACCOUNT RECORD NOT FOUND'                                 
                  TO WS-VALIDATION-FAIL-REASON-DESC                             
           END-REWRITE.                                                         
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Writes the posted transaction record to TRANSACT VSAM.
      * Uses random WRITE keyed by TRAN-ID. Abends on write
      * failure (e.g., duplicate key or dataset full).
      *---------------------------------------------------------------*         
       2900-WRITE-TRANSACTION-FILE.                                             
           MOVE 8 TO  APPL-RESULT.                                              
           WRITE FD-TRANFILE-REC FROM TRAN-RECORD                               
                                                                                
           IF  TRANFILE-STATUS = '00'                                           
               MOVE 0 TO  APPL-RESULT                                           
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR WRITING TO TRANSACTION FILE'                      
               MOVE TRANFILE-STATUS  TO IO-STATUS                               
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Closes daily transaction staging input file.
      *---------------------------------------------------------------*         
       9000-DALYTRAN-CLOSE.                                                     
           MOVE 8 TO  APPL-RESULT.                                              
           CLOSE DALYTRAN-FILE                                                  
           IF  DALYTRAN-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING DALYTRAN FILE'                            
               MOVE DALYTRAN-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes transaction master VSAM output file.
      *---------------------------------------------------------------*         
       9100-TRANFILE-CLOSE.                                                     
           MOVE 8 TO  APPL-RESULT.                                              
           CLOSE TRANSACT-FILE                                                  
           IF  TRANFILE-STATUS = '00'                                           
               MOVE 0 TO  APPL-RESULT                                           
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING TRANSACTION FILE'                         
               MOVE TRANFILE-STATUS  TO IO-STATUS                               
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Closes card cross-reference VSAM input file.
      *---------------------------------------------------------------*         
       9200-XREFFILE-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE XREF-FILE                                                      
           IF  XREFFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING CROSS REF FILE'                           
               MOVE XREFFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes daily rejects sequential output file.
      *---------------------------------------------------------------*         
       9300-DALYREJS-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE DALYREJS-FILE                                                  
           IF  DALYREJS-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING DAILY REJECTS FILE'                       
               MOVE XREFFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes account master VSAM I-O file.
      *---------------------------------------------------------------*         
       9400-ACCTFILE-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE ACCOUNT-FILE                                                   
           IF  ACCTFILE-STATUS  = '00'                                          
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING ACCOUNT FILE'                             
               MOVE ACCTFILE-STATUS  TO IO-STATUS                               
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Closes transaction category balance VSAM I-O file.
      *---------------------------------------------------------------*         
       9500-TCATBALF-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE TCATBAL-FILE                                                   
           IF  TCATBALF-STATUS  = '00'                                          
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING TRANSACTION BALANCE FILE'                 
               MOVE TCATBALF-STATUS  TO IO-STATUS                               
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Generates a DB2-format timestamp (YYYY-MM-DD-HH.MM.SS.NN)
      * from FUNCTION CURRENT-DATE. Used to set TRAN-PROC-TS
      * on each posted transaction record.
      *---------------------------------------------------------------*         
       Z-GET-DB2-FORMAT-TIMESTAMP.                                              
           MOVE FUNCTION CURRENT-DATE TO COBOL-TS                               
           MOVE COB-YYYY TO DB2-YYYY                                            
           MOVE COB-MM   TO DB2-MM                                              
           MOVE COB-DD   TO DB2-DD                                              
           MOVE COB-HH   TO DB2-HH                                              
           MOVE COB-MIN  TO DB2-MIN                                             
           MOVE COB-SS   TO DB2-SS                                              
           MOVE COB-MIL  TO DB2-MIL                                             
           MOVE '0000'   TO DB2-REST                                            
           MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3                   
           MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3                            
      *    DISPLAY 'DB2-TIMESTAMP = ' DB2-FORMAT-TS                             
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Abnormal program termination via LE CEE3ABD service.
      * Called when any critical I/O error is unrecoverable.
      * ABCODE 999 signals a batch processing failure.
      *---------------------------------------------------------------*         
       9999-ABEND-PROGRAM.                                                      
           DISPLAY 'ABENDING PROGRAM'                                           
           MOVE 0 TO TIMING                                                     
           MOVE 999 TO ABCODE                                                   
           CALL 'CEE3ABD'.                                                      
                                                                                
      *****************************************************************         
      * Formats and displays the FILE STATUS code for diagnosis.
      * Handles both numeric status ('00'-'99') and non-numeric
      * extended status (class-9 with binary second byte).
      * Converts binary byte to displayable 4-digit code.
      *****************************************************************         
       9910-DISPLAY-IO-STATUS.                                                  
           IF  IO-STATUS NOT NUMERIC                                            
           OR  IO-STAT1 = '9'                                                   
               MOVE IO-STAT1 TO IO-STATUS-04(1:1)                               
               MOVE 0        TO TWO-BYTES-BINARY                                
               MOVE IO-STAT2 TO TWO-BYTES-RIGHT                                 
               MOVE TWO-BYTES-BINARY TO IO-STATUS-0403                          
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           ELSE                                                                 
               MOVE '0000' TO IO-STATUS-04                                      
               MOVE IO-STATUS TO IO-STATUS-04(3:2)                              
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:32 CDT
      *
