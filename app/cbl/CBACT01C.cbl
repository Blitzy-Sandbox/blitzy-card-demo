      ******************************************************************
      * Program     : CBACT01C.CBL                                      
      * Application : CardDemo                                          
      * Type        : BATCH COBOL Program                                
      * Function    : Read and print account data file.                 
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
      * Batch utility program: Sequential read and display of
      * the account master file (ACCTDAT VSAM KSDS dataset).
      * Reads all records in ACCT-ID key sequence and outputs
      * each account record to SYSOUT via DISPLAY statements.
      * Used for data verification and diagnostic dump during
      * development and environment validation.
      *
      * Record layout: ACCOUNT-RECORD (300 bytes) defined in
      *   copybook CVACT01Y (app/cpy/CVACT01Y.cpy)
      * VSAM access: Sequential read via INDEXED organization
      * JCL wrapper: app/jcl/READACCT.jcl
      * Abends via CEE3ABD on any I/O error
      *
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBACT01C.                                                 
       AUTHOR.        AWS.                                                      
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
      * File I/O configuration for batch account file
       FILE-CONTROL.                                                            
      * ACCTFILE: VSAM KSDS accessed sequentially for full-file scan.
      * Key is the 11-digit account ID (FD-ACCT-ID).
      * FILE STATUS checked after every I/O operation.
           SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE                              
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS SEQUENTIAL                                    
                  RECORD KEY   IS FD-ACCT-ID                                    
                  FILE STATUS  IS ACCTFILE-STATUS.                              
      * INDEXED SEQUENTIAL access reads records in
      * ascending ACCT-ID (11-digit account ID) key order
      *                                                                         
       DATA DIVISION.                                                           
      * File descriptor for the ACCTDAT VSAM dataset
       FILE SECTION.                                                            
       FD  ACCTFILE-FILE.                                                       
       01  FD-ACCTFILE-REC.                                                     
      * 11-byte account ID primary key
           05 FD-ACCT-ID                        PIC 9(11).                      
      * Remaining 289 bytes of the 300-byte account record
           05 FD-ACCT-DATA                      PIC X(289).                     
      * FD-ACCT-ID(11) + FD-ACCT-DATA(289) = 300-byte rec
                                                                                
      * Working storage for account record processing
       WORKING-STORAGE SECTION.                                                 
                                                                                
      *****************************************************************         
      * Includes 300-byte ACCOUNT-RECORD layout from copybook
      * CVACT01Y (app/cpy/CVACT01Y.cpy) — account fields:
      * ACCT-ID, balances, dates, status, limits, group ID
       COPY CVACT01Y.                                                           
      * Two-byte FILE STATUS: '00'=success, '10'=EOF,
      * '35'=file not found, other=I/O error
       01  ACCTFILE-STATUS.                                                     
           05  ACCTFILE-STAT1      PIC X.                                       
           05  ACCTFILE-STAT2      PIC X.                                       
                                                                                
      * Intermediate I/O status for formatted display
       01  IO-STATUS.                                                           
           05  IO-STAT1            PIC X.                                       
           05  IO-STAT2            PIC X.                                       
      * Binary-to-display conversion area for non-numeric status
       01  TWO-BYTES-BINARY        PIC 9(4) BINARY.                             
       01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.                  
           05  TWO-BYTES-LEFT      PIC X.                                       
           05  TWO-BYTES-RIGHT     PIC X.                                       
      * 4-digit formatted I/O status for DISPLAY output
       01  IO-STATUS-04.                                                        
           05  IO-STATUS-0401      PIC 9   VALUE 0.                             
           05  IO-STATUS-0403      PIC 999 VALUE 0.                             
                                                                                
      * Return code: 0=OK (APPL-AOK), 16=EOF (APPL-EOF), 12=error
       01  APPL-RESULT             PIC S9(9)   COMP.                            
           88  APPL-AOK            VALUE 0.                                     
           88  APPL-EOF            VALUE 16.                                    
                                                                                
      * EOF sentinel flag: 'Y' terminates main read loop
       01  END-OF-FILE             PIC X(01)    VALUE 'N'.                      
      * CEE3ABD parameters: timing=0 (immediate), abcode=999
       01  ABCODE                  PIC S9(9) BINARY.                            
       01  TIMING                  PIC S9(9) BINARY.                            
                                                                                
      *****************************************************************         
      * PROCEDURE DIVISION: Opens the account file, reads all
      * records sequentially until EOF, displays each record,
      * then closes the file and terminates.
       PROCEDURE DIVISION.                                                      
           DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.                    
      * Opens ACCTFILE VSAM KSDS for sequential input
           PERFORM 0000-ACCTFILE-OPEN.                                          
                                                                                
      * Main read loop: iterates until EOF flag set to 'Y'
           PERFORM UNTIL END-OF-FILE = 'Y'                                      
               IF  END-OF-FILE = 'N'                                            
                   PERFORM 1000-ACCTFILE-GET-NEXT                               
                   IF  END-OF-FILE = 'N'                                        
                       DISPLAY ACCOUNT-RECORD                                   
                   END-IF                                                       
               END-IF                                                           
           END-PERFORM.                                                         
                                                                                
      * Closes ACCTFILE after all records processed
           PERFORM 9000-ACCTFILE-CLOSE.                                         
                                                                                
           DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.                      
                                                                                
           GOBACK.                                                              
                                                                                
      *****************************************************************         
      * I/O ROUTINES TO ACCESS A KSDS, VSAM DATA SET...               *         
      *****************************************************************         
      * Reads next sequential record from ACCTFILE into the
      * ACCOUNT-RECORD working storage area (CVACT01Y layout).
      * Sets APPL-RESULT: 0=OK, 16=EOF, 12=I/O error.
       1000-ACCTFILE-GET-NEXT.                                                  
      * Read next VSAM record into ACCOUNT-RECORD area
           READ ACCTFILE-FILE INTO ACCOUNT-RECORD.                              
      * Check FILE STATUS: '00'=OK, '10'=EOF, other=err
           IF  ACCTFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
               PERFORM 1100-DISPLAY-ACCT-RECORD                                 
           ELSE                                                                 
               IF  ACCTFILE-STATUS = '10'                                       
                   MOVE 16 TO APPL-RESULT                                       
               ELSE                                                             
                   MOVE 12 TO APPL-RESULT                                       
               END-IF                                                           
           END-IF                                                               
      * Evaluate result: continue, set EOF, or abend
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               IF  APPL-EOF                                                     
                   MOVE 'Y' TO END-OF-FILE                                      
               ELSE                                                             
                   DISPLAY 'ERROR READING ACCOUNT FILE'                         
                   MOVE ACCTFILE-STATUS TO IO-STATUS                            
                   PERFORM 9910-DISPLAY-IO-STATUS                               
                   PERFORM 9999-ABEND-PROGRAM                                   
               END-IF                                                           
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Displays individual account record fields to SYSOUT for
      * diagnostic verification of VSAM dataset contents.
       1100-DISPLAY-ACCT-RECORD.                                                
           DISPLAY 'ACCT-ID                 :'   ACCT-ID                        
           DISPLAY 'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS             
           DISPLAY 'ACCT-CURR-BAL           :'   ACCT-CURR-BAL                  
           DISPLAY 'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT              
           DISPLAY 'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT         
           DISPLAY 'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE                 
           DISPLAY 'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE            
           DISPLAY 'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE              
           DISPLAY 'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT           
           DISPLAY 'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT            
           DISPLAY 'ACCT-GROUP-ID           :'   ACCT-GROUP-ID                  
           DISPLAY '-------------------------------------------------'          
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens ACCTFILE for sequential input. Abends on failure.
       0000-ACCTFILE-OPEN.                                                      
      * Initialize result to 8 (pending I/O operation)
           MOVE 8 TO APPL-RESULT.                                               
      * Open ACCTDAT for read-only sequential access
           OPEN INPUT ACCTFILE-FILE                                             
      * Validate FILE STATUS after OPEN -- abend on fail
           IF  ACCTFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
      * If OPEN failed, display error and abend
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING ACCTFILE'                                 
               MOVE ACCTFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes ACCTFILE. Abends on close failure.
       9000-ACCTFILE-CLOSE.                                                     
      * Initialize result to 8 (pending I/O operation)
           ADD 8 TO ZERO GIVING APPL-RESULT.                                    
      * Close ACCTDAT VSAM file to release dataset
           CLOSE ACCTFILE-FILE                                                  
      * Check close status -- zeros result on success
           IF  ACCTFILE-STATUS = '00'                                           
               SUBTRACT APPL-RESULT FROM APPL-RESULT                            
           ELSE                                                                 
               ADD 12 TO ZERO GIVING APPL-RESULT                                
           END-IF                                                               
      * If close failed, display error and abend
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING ACCOUNT FILE'                             
               MOVE ACCTFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      * Terminates program abnormally via CEE3ABD (Language
      * Environment abend service) with abend code 999 and
      * CLEANUP timing for resource cleanup
       9999-ABEND-PROGRAM.                                                      
           DISPLAY 'ABENDING PROGRAM'                                           
      * Set CLEANUP timing (0=immediate) and abend code
           MOVE 0 TO TIMING                                                     
           MOVE 999 TO ABCODE                                                   
      * Calls LE abend handler to terminate the program
           CALL 'CEE3ABD'.                                                      
                                                                                
      *****************************************************************         
      * Formats FILE STATUS into 4-digit display. Handles both
      * numeric (e.g., '00', '10') and non-numeric (binary stat2)
      * status codes for diagnostic output.
       9910-DISPLAY-IO-STATUS.                                                  
      * Non-numeric or class-9: convert binary byte
           IF  IO-STATUS NOT NUMERIC                                            
           OR  IO-STAT1 = '9'                                                   
               MOVE IO-STAT1 TO IO-STATUS-04(1:1)                               
               MOVE 0        TO TWO-BYTES-BINARY                                
               MOVE IO-STAT2 TO TWO-BYTES-RIGHT                                 
               MOVE TWO-BYTES-BINARY TO IO-STATUS-0403                          
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
      * Numeric status: pad to 4 digits for display
           ELSE                                                                 
               MOVE '0000' TO IO-STATUS-04                                      
               MOVE IO-STATUS TO IO-STATUS-04(3:2)                              
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:31 CDT
      *
