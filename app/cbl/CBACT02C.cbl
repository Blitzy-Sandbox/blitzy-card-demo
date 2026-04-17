      ******************************************************************
      * Program     : CBACT02C.CBL                                      
      * Application : CardDemo                                          
      * Type        : BATCH COBOL Program                               
      * Function    : Read and print card data file.                    
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
      * Batch diagnostic utility: sequentially reads all records from
      * the CARDDAT VSAM KSDS (credit card file) and displays each
      * record to SYSOUT. Uses CVACT02Y copybook for the 150-byte
      * CARD-RECORD layout. Invoked by JCL job READCARD.jcl.
      * Abends via CEE3ABD on any I/O error.
      ******************************************************************
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBACT02C.                                                 
       AUTHOR.        AWS.                                                      
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
       FILE-CONTROL.                                                            
      * CARDFILE: CARDDAT VSAM KSDS accessed sequentially
      * for full-file dump. Primary key is the 16-byte card
      * number (FD-CARD-NUM).
      * FILE STATUS checked after every I/O operation.
           SELECT CARDFILE-FILE ASSIGN TO   CARDFILE                            
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS SEQUENTIAL                                    
                  RECORD KEY   IS FD-CARD-NUM                                   
                  FILE STATUS  IS CARDFILE-STATUS.                              
      *                                                                         
       DATA DIVISION.                                                           
       FILE SECTION.                                                            
       FD  CARDFILE-FILE.                                                       
       01  FD-CARDFILE-REC.                                                     
      * 16-byte card number primary key
           05 FD-CARD-NUM                       PIC X(16).                      
      * Remaining 134 bytes of the 150-byte card record
           05 FD-CARD-DATA                      PIC X(134).                     
                                                                                
       WORKING-STORAGE SECTION.                                                 
                                                                                
      *****************************************************************         
      * Includes 150-byte CARD-RECORD layout from CVACT02Y:
      * card number, account link, CVV, embossed name,
      * expiration date, active status
       COPY CVACT02Y.                                                           
      * Two-byte FILE STATUS: '00'=OK, '10'=EOF,
      * '35'=file not found, other=error
       01  CARDFILE-STATUS.                                                     
           05  CARDFILE-STAT1      PIC X.                                       
           05  CARDFILE-STAT2      PIC X.                                       
                                                                                
      * Intermediate I/O status for formatted display
       01  IO-STATUS.                                                           
           05  IO-STAT1            PIC X.                                       
           05  IO-STAT2            PIC X.                                       
      * Binary-to-display conversion for non-numeric status
       01  TWO-BYTES-BINARY        PIC 9(4) BINARY.                             
       01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.                  
           05  TWO-BYTES-LEFT      PIC X.                                       
           05  TWO-BYTES-RIGHT     PIC X.                                       
      * 4-digit formatted I/O status for DISPLAY output
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
      * PROCEDURE DIVISION: Opens CARDDAT, reads all card
      * records sequentially until EOF, displays each to
      * SYSOUT, then closes the file and terminates.
       PROCEDURE DIVISION.                                                      
           DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.                    
      * Opens CARDFILE VSAM KSDS for sequential input
           PERFORM 0000-CARDFILE-OPEN.                                          
                                                                                
      * Main read loop: iterates until EOF flag set to 'Y'
           PERFORM UNTIL END-OF-FILE = 'Y'                                      
               IF  END-OF-FILE = 'N'                                            
                   PERFORM 1000-CARDFILE-GET-NEXT                               
                   IF  END-OF-FILE = 'N'                                        
                       DISPLAY CARD-RECORD                                      
                   END-IF                                                       
               END-IF                                                           
           END-PERFORM.                                                         
                                                                                
      * Closes CARDFILE after all records processed
           PERFORM 9000-CARDFILE-CLOSE.                                         
                                                                                
           DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.                      
                                                                                
           GOBACK.                                                              
                                                                                
      *****************************************************************         
      * I/O ROUTINES TO ACCESS A KSDS, VSAM DATA SET...               *         
      *****************************************************************         
      * Reads next sequential record from CARDFILE into the
      * CARD-RECORD working storage area (CVACT02Y layout).
      * Sets APPL-RESULT: 0=OK, 16=EOF, 12=I/O error.
       1000-CARDFILE-GET-NEXT.                                                  
      * Reads next sequential record into CARD-RECORD
           READ CARDFILE-FILE INTO CARD-RECORD.                                 
      * Status '00': successful read
           IF  CARDFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
      *        DISPLAY CARD-RECORD                                              
           ELSE                                                                 
      * Status '10': end of file reached
               IF  CARDFILE-STATUS = '10'                                       
                   MOVE 16 TO APPL-RESULT                                       
               ELSE                                                             
      * Any other status: I/O error
                   MOVE 12 TO APPL-RESULT                                       
               END-IF                                                           
           END-IF                                                               
      * Evaluate APPL-RESULT via 88-level conditions
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               IF  APPL-EOF                                                     
      * Signals main loop to terminate
                   MOVE 'Y' TO END-OF-FILE                                      
               ELSE                                                             
      * I/O error: display status and abend
                   DISPLAY 'ERROR READING CARDFILE'                             
                   MOVE CARDFILE-STATUS TO IO-STATUS                            
                   PERFORM 9910-DISPLAY-IO-STATUS                               
                   PERFORM 9999-ABEND-PROGRAM                                   
               END-IF                                                           
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens CARDFILE for sequential INPUT with FILE STATUS
      * check. Abends via 9999-ABEND-PROGRAM on failure.
       0000-CARDFILE-OPEN.                                                      
      * Preset APPL-RESULT to non-zero before OPEN attempt
           MOVE 8 TO APPL-RESULT.                                               
      * Opens CARDDAT VSAM KSDS for sequential input
           OPEN INPUT CARDFILE-FILE                                             
      * Status '00': file opened successfully
           IF  CARDFILE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
      * On failure, display status and abend program
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING CARDFILE'                                 
               MOVE CARDFILE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes CARDFILE and verifies clean close via FILE
      * STATUS. Abends via 9999-ABEND-PROGRAM on failure.
       9000-CARDFILE-CLOSE.                                                     
      * Preset APPL-RESULT to 8 (non-zero) before CLOSE
           ADD 8 TO ZERO GIVING APPL-RESULT.                                    
      * Closes CARDDAT VSAM dataset
           CLOSE CARDFILE-FILE                                                  
      * Status '00': zeroes APPL-RESULT (SUBTRACT X FROM X)
           IF  CARDFILE-STATUS = '00'                                           
               SUBTRACT APPL-RESULT FROM APPL-RESULT                            
           ELSE                                                                 
               ADD 12 TO ZERO GIVING APPL-RESULT                                
           END-IF                                                               
      * On failure, display status and abend program
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING CARDFILE'                                 
               MOVE CARDFILE-STATUS TO IO-STATUS                                
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
      * numeric and non-numeric status codes.
       9910-DISPLAY-IO-STATUS.                                                  
      * Non-numeric or class-9: binary status byte handling
           IF  IO-STATUS NOT NUMERIC                                            
           OR  IO-STAT1 = '9'                                                   
               MOVE IO-STAT1 TO IO-STATUS-04(1:1)                               
               MOVE 0        TO TWO-BYTES-BINARY                                
               MOVE IO-STAT2 TO TWO-BYTES-RIGHT                                 
               MOVE TWO-BYTES-BINARY TO IO-STATUS-0403                          
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           ELSE                                                                 
      * Standard numeric status: display as 4-digit value
               MOVE '0000' TO IO-STATUS-04                                      
               MOVE IO-STATUS TO IO-STATUS-04(3:2)                              
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04                      
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:31 CDT
      *
