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
      * Batch diagnostic utility: sequentially reads all records from
      * the ACCTDAT VSAM KSDS (account master file) and displays
      * each record's key fields to SYSOUT. Uses CVACT01Y copybook
      * for the 300-byte ACCOUNT-RECORD layout. Invoked by JCL job
      * READACCT.jcl. Abends via CEE3ABD on any I/O error.
      ******************************************************************
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBACT01C.                                                 
       AUTHOR.        AWS.                                                      
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
       FILE-CONTROL.                                                            
      * ACCTFILE: VSAM KSDS accessed sequentially for full-file scan.
      * Key is the 11-digit account ID (FD-ACCT-ID).
      * FILE STATUS checked after every I/O operation.
           SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE                              
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS SEQUENTIAL                                    
                  RECORD KEY   IS FD-ACCT-ID                                    
                  FILE STATUS  IS ACCTFILE-STATUS.                              
      *                                                                         
       DATA DIVISION.                                                           
       FILE SECTION.                                                            
       FD  ACCTFILE-FILE.                                                       
       01  FD-ACCTFILE-REC.                                                     
      * 11-byte account ID primary key
           05 FD-ACCT-ID                        PIC 9(11).                      
      * Remaining 289 bytes of the 300-byte account record
           05 FD-ACCT-DATA                      PIC X(289).                     
                                                                                
       WORKING-STORAGE SECTION.                                                 
                                                                                
      *****************************************************************         
      * ACCOUNT-RECORD layout: see CVACT01Y.cpy for field details
       COPY CVACT01Y.                                                           
      * Two-byte FILE STATUS: '00'=OK, '10'=EOF, other=error
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
           READ ACCTFILE-FILE INTO ACCOUNT-RECORD.                              
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
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT ACCTFILE-FILE                                             
           IF  ACCTFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
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
           ADD 8 TO ZERO GIVING APPL-RESULT.                                    
           CLOSE ACCTFILE-FILE                                                  
           IF  ACCTFILE-STATUS = '00'                                           
               SUBTRACT APPL-RESULT FROM APPL-RESULT                            
           ELSE                                                                 
               ADD 12 TO ZERO GIVING APPL-RESULT                                
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING ACCOUNT FILE'                             
               MOVE ACCTFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      * Abends program via IBM LE CEE3ABD with abend code 999.
       9999-ABEND-PROGRAM.                                                      
           DISPLAY 'ABENDING PROGRAM'                                           
           MOVE 0 TO TIMING                                                     
           MOVE 999 TO ABCODE                                                   
           CALL 'CEE3ABD'.                                                      
                                                                                
      *****************************************************************         
      * Formats FILE STATUS into 4-digit display. Handles both
      * numeric (e.g., '00', '10') and non-numeric (binary stat2)
      * status codes for diagnostic output.
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
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:31 CDT
      *
