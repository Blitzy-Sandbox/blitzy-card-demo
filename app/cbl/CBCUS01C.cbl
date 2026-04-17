      ******************************************************************
      * Program     : CBCUS01C.CBL                                      
      * Application : CardDemo                                          
      * Type        : BATCH COBOL Program                                
      * Function    : Read and print customer data file.                
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
      * Batch utility program: Sequential read and display of the
      * customer master file (CUSTDAT).
      * Reads all records from the CUSTDAT VSAM KSDS dataset in
      * CUST-ID key sequence and writes each 500-byte
      * CUSTOMER-RECORD to SYSOUT for diagnostic verification
      * or data audit purposes.
      *
      * Record layout: CUSTOMER-RECORD (500 bytes) defined in
      * CVCUS01Y.cpy — contains customer demographics (name),
      * contact info (address, phone), identity data (SSN,
      * government ID, DOB), and financial profile (EFT
      * account, card holder indicator, FICO credit score).
      *
      * Invoked by: JCL job READCUST.jcl
      *
      * Files accessed:
      *   CUSTFILE (CUSTDAT) — Customer master VSAM KSDS
      *     Primary key: CUST-ID (9 bytes numeric)
      *     Record size: 500 bytes fixed
      *     Access mode: Sequential (full-file scan)
      *
      * Abend codes:
      *   999 — Unrecoverable file I/O error (via CEE3ABD)
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CBCUS01C.
       AUTHOR.        AWS.
 
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
      * CUSTDAT VSAM KSDS — primary key FD-CUST-ID (9 bytes
      * numeric), accessed sequentially for full-file read
           SELECT CUSTFILE-FILE ASSIGN TO   CUSTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS FD-CUST-ID
                  FILE STATUS  IS CUSTFILE-STATUS.
      *
       DATA DIVISION.
       FILE SECTION.
      * File description for CUSTDAT — 500-byte customer records
      * FD-CUST-ID is the 9-byte numeric primary key
       FD  CUSTFILE-FILE.
       01  FD-CUSTFILE-REC.
           05 FD-CUST-ID                        PIC 9(09).
           05 FD-CUST-DATA                      PIC X(491).

       WORKING-STORAGE SECTION.

      *****************************************************************
      * Includes 500-byte CUSTOMER-RECORD layout from
      * CVCUS01Y.cpy — demographics (first/middle/last name),
      * address (3 lines, state, country, ZIP), phone (2),
      * SSN, government ID, DOB, EFT account ID,
      * primary card holder indicator, FICO credit score
       COPY CVCUS01Y.
      * Two-byte FILE STATUS: '00'=OK, '10'=EOF, other=error
       01  CUSTFILE-STATUS.
           05  CUSTFILE-STAT1      PIC X.
           05  CUSTFILE-STAT2      PIC X.

      * General I/O status work area used by Z-DISPLAY-IO-STATUS
      * to format and display file status diagnostics
       01  IO-STATUS.
           05  IO-STAT1            PIC X.
           05  IO-STAT2            PIC X.
      * Binary-to-display conversion area for non-numeric
      * (class 9) file status second byte
       01  TWO-BYTES-BINARY        PIC 9(4) BINARY.
       01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.
           05  TWO-BYTES-LEFT      PIC X.
           05  TWO-BYTES-RIGHT     PIC X.
      * Formatted 4-digit display area for file status output
      * Positions 1: class digit, 2-4: detail code
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
      * Main control — opens CUSTDAT, reads all customer
      * records sequentially, displays each to SYSOUT,
      * then closes the file and returns to caller.
       PROCEDURE DIVISION.
           DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
           PERFORM 0000-CUSTFILE-OPEN.

           PERFORM UNTIL END-OF-FILE = 'Y'
               IF  END-OF-FILE = 'N'
                   PERFORM 1000-CUSTFILE-GET-NEXT
                   IF  END-OF-FILE = 'N'
                       DISPLAY CUSTOMER-RECORD 
                   END-IF
               END-IF
           END-PERFORM.

           PERFORM 9000-CUSTFILE-CLOSE.

           DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.

           GOBACK.

      *****************************************************************
      * I/O ROUTINES TO ACCESS A KSDS, VSAM DATA SET...               *
      *****************************************************************
      * Reads next sequential record from CUSTFILE into
      * CUSTOMER-RECORD (CVCUS01Y layout). Evaluates
      * FILE STATUS: '00' = success (displays record),
      * '10' = end-of-file (sets APPL-EOF flag),
      * other = error (displays status and abends).
       1000-CUSTFILE-GET-NEXT.
           READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
           IF  CUSTFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
               DISPLAY CUSTOMER-RECORD 
           ELSE
               IF  CUSTFILE-STATUS = '10'
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
                   DISPLAY 'ERROR READING CUSTOMER FILE'
                   MOVE CUSTFILE-STATUS TO IO-STATUS
                   PERFORM Z-DISPLAY-IO-STATUS
                   PERFORM Z-ABEND-PROGRAM
               END-IF
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Opens CUSTFILE for sequential input. Abends on failure.
       0000-CUSTFILE-OPEN.
           MOVE 8 TO APPL-RESULT.
           OPEN INPUT CUSTFILE-FILE
           IF  CUSTFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING CUSTFILE'
               MOVE CUSTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes CUSTFILE. Abends on close failure.
       9000-CUSTFILE-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE CUSTFILE-FILE
           IF  CUSTFILE-STATUS = '00'
               SUBTRACT APPL-RESULT FROM APPL-RESULT
           ELSE
               ADD 12 TO ZERO GIVING APPL-RESULT
           END-IF
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING CUSTOMER FILE'
               MOVE CUSTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.

      * Forces abnormal program termination via CEE3ABD
      * (LE Language Environment abend service).
      * Sets abend code 999 to signal unrecoverable I/O
      * error. Timing 0 requests immediate termination.
       Z-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
           MOVE 0 TO TIMING
           MOVE 999 TO ABCODE
           CALL 'CEE3ABD'.

      *****************************************************************
      * Formats and displays the FILE STATUS code to SYSOUT.
      * Handles both numeric (class 0-8) and non-numeric
      * (class 9 with binary second byte) status formats.
      * Outputs a 4-digit NNNN display for diagnostics.
       Z-DISPLAY-IO-STATUS.
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
