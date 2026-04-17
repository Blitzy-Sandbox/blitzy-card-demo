      ******************************************************************
      * Program     : CBACT03C.CBL                                      
      * Application : CardDemo                                          
      * Type        : BATCH COBOL Program                                
      * Function    : Read and print account cross reference data file.     
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
      * Batch utility program: Sequential read and display of card
      * cross-reference file.
      * Reads all records from CARDXREF VSAM KSDS dataset in
      * XREF-CARD-NUM key sequence and displays each to SYSOUT.
      * Record layout: CARD-XREF-RECORD (50 bytes) from CVACT03Y.cpy
      * Cross-reference links card numbers to customer IDs and
      * account IDs.
      * JCL wrapper: app/jcl/READXREF.jcl
      * Abends via CEE3ABD (code 999) on any I/O error.
      ******************************************************************
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBACT03C.                                                 
       AUTHOR.        AWS.                                                      
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
       FILE-CONTROL.                                                            
      * CARDXREF VSAM KSDS -- primary key XREF-CARD-NUM (16 bytes)
      * Opened SEQUENTIAL for full-file scan in key order
           SELECT XREFFILE-FILE ASSIGN TO   XREFFILE                            
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS SEQUENTIAL                                    
                  RECORD KEY   IS FD-XREF-CARD-NUM                              
                  FILE STATUS  IS XREFFILE-STATUS.                              
      *                                                                         
       DATA DIVISION.                                                           
       FILE SECTION.                                                            
      * FD record for CARDXREF: 50-byte physical I/O buffer
      * FD-XREF-CARD-NUM (16) serves as KSDS primary key
      * FD-XREF-DATA (34) holds remaining cross-ref fields
       FD  XREFFILE-FILE.                                                       
       01  FD-XREFFILE-REC.                                                     
           05 FD-XREF-CARD-NUM                  PIC X(16).                      
           05 FD-XREF-DATA                      PIC X(34).                      
                                                                                
       WORKING-STORAGE SECTION.                                                 
                                                                                
      *****************************************************************         
      * Includes 50-byte CARD-XREF-RECORD from CVACT03Y.cpy
      * Card-to-customer-to-account linking layout:
      *   XREF-CARD-NUM  PIC X(16) -- card number (key)
      *   XREF-CUST-ID   PIC 9(09) -- customer identifier
      *   XREF-ACCT-ID   PIC 9(11) -- account identifier
      *   FILLER          PIC X(14) -- reserved
       COPY CVACT03Y.                                                           
      * Two-byte FILE STATUS: '00'=OK, '10'=EOF, other=error
       01  XREFFILE-STATUS.                                                     
           05  XREFFILE-STAT1      PIC X.                                       
           05  XREFFILE-STAT2      PIC X.                                       
                                                                                
      * Working copy of FILE STATUS for display formatting
       01  IO-STATUS.                                                           
           05  IO-STAT1            PIC X.                                       
           05  IO-STAT2            PIC X.                                       
      * Binary/alpha overlay for VSAM extended status extraction
       01  TWO-BYTES-BINARY        PIC 9(4) BINARY.                             
       01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.                  
           05  TWO-BYTES-LEFT      PIC X.                                       
           05  TWO-BYTES-RIGHT     PIC X.                                       
      * Formatted 4-digit status code for DISPLAY output
       01  IO-STATUS-04.                                                        
           05  IO-STATUS-0401      PIC 9   VALUE 0.                             
           05  IO-STATUS-0403      PIC 999 VALUE 0.                             
                                                                                
      * Return code: 0=OK (APPL-AOK), 16=EOF (APPL-EOF)
       01  APPL-RESULT             PIC S9(9)   COMP.                            
           88  APPL-AOK            VALUE 0.                                     
           88  APPL-EOF            VALUE 16.                                    
                                                                                
      * EOF sentinel flag: 'Y' terminates main read loop
       01  END-OF-FILE             PIC X(01)    VALUE 'N'.                      
      * CEE3ABD parameters: timing=0 (immediate), abcode=999
       01  ABCODE                  PIC S9(9) BINARY.                            
       01  TIMING                  PIC S9(9) BINARY.                            
                                                                                
      *****************************************************************         
      * Main control -- opens CARDXREF, reads all cross-reference
      * records, displays each to SYSOUT, then closes the file.
      * Pattern: OPEN -> sequential READ loop -> CLOSE -> GOBACK
       PROCEDURE DIVISION.                                                      
           DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.                    
           PERFORM 0000-XREFFILE-OPEN.                                          
                                                                                
      * Loop through all cross-reference records sequentially
      * until 1000-XREFFILE-GET-NEXT signals end-of-file.
      * Note: record is displayed both in GET-NEXT and here.
           PERFORM UNTIL END-OF-FILE = 'Y'                                      
               IF  END-OF-FILE = 'N'                                            
                   PERFORM 1000-XREFFILE-GET-NEXT                               
                   IF  END-OF-FILE = 'N'                                        
                       DISPLAY CARD-XREF-RECORD                                 
                   END-IF                                                       
               END-IF                                                           
           END-PERFORM.                                                         
                                                                                
           PERFORM 9000-XREFFILE-CLOSE.                                         
                                                                                
           DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.                      
                                                                                
      * Returns control to the calling JCL step
           GOBACK.                                                              
                                                                                
      *****************************************************************         
      * I/O ROUTINES TO ACCESS A KSDS, VSAM DATA SET...               *         
      *****************************************************************         
      * Reads next sequential record from XREFFILE into the
      * CARD-XREF-RECORD work area (CVACT03Y). Sets APPL-RESULT:
      *   0  (APPL-AOK) = successful read
      *   16 (APPL-EOF) = end of file reached
      *   12             = unexpected I/O error
       1000-XREFFILE-GET-NEXT.                                                  
      * READ INTO copies the FD buffer to CARD-XREF-RECORD
           READ XREFFILE-FILE INTO CARD-XREF-RECORD.                            
      * Evaluate FILE STATUS: '00'=success, '10'=EOF, other=error
           IF  XREFFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
               DISPLAY CARD-XREF-RECORD                                         
           ELSE                                                                 
               IF  XREFFILE-STATUS = '10'                                       
                   MOVE 16 TO APPL-RESULT                                       
               ELSE                                                             
                   MOVE 12 TO APPL-RESULT                                       
               END-IF                                                           
           END-IF                                                               
      * Act on result: continue if OK, set EOF flag, or abend
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               IF  APPL-EOF                                                     
                   MOVE 'Y' TO END-OF-FILE                                      
               ELSE                                                             
                   DISPLAY 'ERROR READING XREFFILE'                             
                   MOVE XREFFILE-STATUS TO IO-STATUS                            
                   PERFORM 9910-DISPLAY-IO-STATUS                               
                   PERFORM 9999-ABEND-PROGRAM                                   
               END-IF                                                           
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens XREFFILE for sequential input processing.
      * Sets APPL-RESULT to 0 on success or 12 on failure.
      * Abends the program if the file cannot be opened.
       0000-XREFFILE-OPEN.                                                      
      * Preset result to 8 (pending); OPEN resets FILE STATUS
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT XREFFILE-FILE                                             
      * Check FILE STATUS: '00' = successful open
           IF  XREFFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
      * On failure: display status and abend with code 999
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING XREFFILE'                                 
               MOVE XREFFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes XREFFILE after all records are processed.
      * Uses ADD/SUBTRACT arithmetic instead of MOVE for result.
      * Abends the program if the file cannot be closed.
       9000-XREFFILE-CLOSE.                                                     
      * Preset result to 8 via ADD arithmetic
           ADD 8 TO ZERO GIVING APPL-RESULT.                                    
           CLOSE XREFFILE-FILE                                                  
      * SUBTRACT self zeroes APPL-RESULT on success
           IF  XREFFILE-STATUS = '00'                                           
               SUBTRACT APPL-RESULT FROM APPL-RESULT                            
           ELSE                                                                 
               ADD 12 TO ZERO GIVING APPL-RESULT                                
           END-IF                                                               
      * On failure: display status and abend with code 999
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING XREFFILE'                                 
               MOVE XREFFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
      * Abends program via IBM LE CEE3ABD with abend code 999.
      * TIMING=0 means abend immediately without cleanup delay.
       9999-ABEND-PROGRAM.                                                      
           DISPLAY 'ABENDING PROGRAM'                                           
           MOVE 0 TO TIMING                                                     
           MOVE 999 TO ABCODE                                                   
      * CEE3ABD terminates the run unit with user abend U0999
           CALL 'CEE3ABD'.                                                      
                                                                                
      *****************************************************************         
      * Formats FILE STATUS into a readable 4-digit display.
      * Handles two cases:
      *  1) VSAM extended status (non-numeric or '9x') --
      *     extracts binary byte-2 as 3-digit numeric
      *  2) Standard status -- pads to 4 digits right-justified
       9910-DISPLAY-IO-STATUS.                                                  
      * Branch 1: VSAM extended status requires binary decoding
           IF  IO-STATUS NOT NUMERIC                                            
           OR  IO-STAT1 = '9'                                                   
               MOVE IO-STAT1 TO IO-STATUS-04(1:1)                               
               MOVE 0        TO TWO-BYTES-BINARY                                
               MOVE IO-STAT2 TO TWO-BYTES-RIGHT                                 
               MOVE TWO-BYTES-BINARY TO IO-STATUS-0403                          
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           ELSE                                                                 
      * Branch 2: Standard FILE STATUS -- right-justify in 4 chars
               MOVE '0000' TO IO-STATUS-04                                      
               MOVE IO-STATUS TO IO-STATUS-04(3:2)                              
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:31 CDT
      *
