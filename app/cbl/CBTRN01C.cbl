      ******************************************************************
      * Program     : CBTRN01C.CBL                                      
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
      * CBTRN01C - Daily Transaction Driver (Batch)
      *
      * Batch processing program: Daily transaction driver.
      * Opens DALYTRAN (daily transaction staging file) and
      * supporting lookup files (CUSTFILE, XREFFILE, CARDFILE,
      * ACCTFILE, TRANFILE).
      * Reads daily transaction records sequentially and performs
      * initial validation by looking up each card number in the
      * cross-reference file (XREFFILE). If the card is found,
      * reads the associated account record from ACCTFILE to
      * confirm the account exists. Transactions with
      * unverifiable card numbers are skipped with a diagnostic
      * DISPLAY message.
      *
      * Works in conjunction with CBTRN02C (transaction posting
      * engine) which handles the actual posting of validated
      * transactions. CBTRN02C is invoked by POSTTRAN.jcl
      * (see app/jcl/POSTTRAN.jcl). This program (CBTRN01C)
      * is not directly referenced by any JCL job and serves
      * as a standalone daily-transaction validation utility.
      *
      * Files accessed:
      *   DALYTRAN  - Daily transaction staging (sequential
      *               input, 350-byte records per CVTRA06Y)
      *   CUSTFILE  - Customer master VSAM KSDS (random read
      *               by FD-CUST-ID, 500-byte per CVCUS01Y)
      *   XREFFILE  - Card-to-account cross-reference VSAM
      *               KSDS (random read by FD-XREF-CARD-NUM,
      *               50-byte per CVACT03Y)
      *   CARDFILE  - Card master VSAM KSDS (random read by
      *               FD-CARD-NUM, 150-byte per CVACT02Y)
      *   ACCTFILE  - Account master VSAM KSDS (random read
      *               by FD-ACCT-ID, 300-byte per CVACT01Y)
      *   TRANFILE  - Transaction master VSAM KSDS (random
      *               read by FD-TRANS-ID, 350-byte per
      *               CVTRA05Y)
      *
      * Validation flow per daily transaction:
      *   1. Read next DALYTRAN record (sequential)
      *   2. Look up card number in XREFFILE
      *   3. If found, read account from ACCTFILE using
      *      the XREF-ACCT-ID
      *   4. If card not found or account missing, display
      *      diagnostic message and skip
      *
      * Copybooks:
      *   CVTRA06Y - Daily transaction record (350 bytes)
      *   CVCUS01Y - Customer record (500 bytes)
      *   CVACT03Y - Card cross-reference record (50 bytes)
      *   CVACT02Y - Card record (150 bytes)
      *   CVACT01Y - Account record (300 bytes)
      *   CVTRA05Y - Transaction record (350 bytes)
      *
      * Cross-references:
      *   Posting engine: app/cbl/CBTRN02C.cbl
      *   Related JCL:    app/jcl/POSTTRAN.jcl
      *
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CBTRN01C.
       AUTHOR.        AWS.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
      * DALYTRAN-FILE: Daily transaction staging file
      * Sequential input of 350-byte daily transaction
      * records for validation processing
           SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
                  ORGANIZATION IS SEQUENTIAL
                  ACCESS MODE  IS SEQUENTIAL
                  FILE STATUS  IS DALYTRAN-STATUS.

      * CUSTOMER-FILE: Customer master VSAM KSDS
      * Random read by 9-digit customer ID (FD-CUST-ID)
      * Used for customer existence validation
           SELECT CUSTOMER-FILE ASSIGN TO   CUSTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-CUST-ID
                  FILE STATUS  IS CUSTFILE-STATUS.

      * XREF-FILE: Card-to-account cross-reference VSAM KSDS
      * Random read by 16-byte card number
      * (FD-XREF-CARD-NUM). Maps card to account ID
           SELECT XREF-FILE ASSIGN TO   XREFFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-XREF-CARD-NUM
                  FILE STATUS  IS XREFFILE-STATUS.

      * CARD-FILE: Card master VSAM KSDS
      * Random read by 16-byte card number (FD-CARD-NUM)
      * Used for card validity verification
           SELECT CARD-FILE ASSIGN TO   CARDFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-CARD-NUM
                  FILE STATUS  IS CARDFILE-STATUS.

      * ACCOUNT-FILE: Account master VSAM KSDS
      * Random read by 11-digit account ID (FD-ACCT-ID)
      * Used for account existence verification
           SELECT ACCOUNT-FILE ASSIGN TO   ACCTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-ACCT-ID
                  FILE STATUS  IS ACCTFILE-STATUS.

      * TRANSACT-FILE: Transaction master VSAM KSDS
      * Random read by 16-byte transaction ID
      * (FD-TRANS-ID) for existing record checks
           SELECT TRANSACT-FILE ASSIGN TO   TRANFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-TRANS-ID
                  FILE STATUS  IS TRANFILE-STATUS.
      *
       DATA DIVISION.
       FILE SECTION.
      *---------------------------------------------------------------*
      * FD for daily transaction staging file (DALYTRAN)
      * 350-byte record: 16-byte transaction ID + 334-byte data
      * Reads into DALYTRAN-RECORD via COPY CVTRA06Y
      *---------------------------------------------------------------*
       FD  DALYTRAN-FILE.
       01  FD-TRAN-RECORD.
           05 FD-TRAN-ID                        PIC X(16).
           05 FD-CUST-DATA                      PIC X(334).

      *---------------------------------------------------------------*
      * FD for customer master file (CUSTFILE)
      * 500-byte record: 9-digit customer ID key + 491-byte data
      * Reads into CUSTOMER-RECORD via COPY CVCUS01Y
      *---------------------------------------------------------------*
       FD  CUSTOMER-FILE.
       01  FD-CUSTFILE-REC.
           05 FD-CUST-ID                        PIC 9(09).
           05 FD-CUST-DATA                      PIC X(491).

      *---------------------------------------------------------------*
      * FD for card-to-account cross-reference file (XREFFILE)
      * 50-byte record: 16-byte card number key + 34-byte data
      * Reads into CARD-XREF-RECORD via COPY CVACT03Y
      *---------------------------------------------------------------*
       FD  XREF-FILE.
       01  FD-XREFFILE-REC.
           05 FD-XREF-CARD-NUM                  PIC X(16).
           05 FD-XREF-DATA                      PIC X(34).

      *---------------------------------------------------------------*
      * FD for card master file (CARDFILE)
      * 150-byte record: 16-byte card number key + 134-byte data
      * Reads into CARD-RECORD via COPY CVACT02Y
      *---------------------------------------------------------------*
       FD  CARD-FILE.
       01  FD-CARDFILE-REC.
           05 FD-CARD-NUM                       PIC X(16).
           05 FD-CARD-DATA                      PIC X(134).

      *---------------------------------------------------------------*
      * FD for account master file (ACCTFILE)
      * 300-byte record: 11-digit account ID key + 289-byte data
      * Reads into ACCOUNT-RECORD via COPY CVACT01Y
      *---------------------------------------------------------------*
       FD  ACCOUNT-FILE.
       01  FD-ACCTFILE-REC.
           05 FD-ACCT-ID                        PIC 9(11).
           05 FD-ACCT-DATA                      PIC X(289).

      *---------------------------------------------------------------*
      * FD for transaction master file (TRANFILE)
      * 350-byte record: 16-byte transaction ID key + 334-byte data
      * Reads into TRAN-RECORD via COPY CVTRA05Y
      *---------------------------------------------------------------*
       FD  TRANSACT-FILE.
       01  FD-TRANFILE-REC.
           05 FD-TRANS-ID                       PIC X(16).
           05 FD-ACCT-DATA                      PIC X(334).

       WORKING-STORAGE SECTION.

      *****************************************************************
      * Copybook record layouts and FILE STATUS variables
      * Each COPY brings in a working-storage record layout used
      * as the INTO target for READ operations. The FILE STATUS
      * 2-byte fields capture I/O return codes: '00' = success,
      * '10' = end-of-file, other = error.
      *****************************************************************
      * Daily transaction staging record layout (350 bytes)
      * See app/cpy/CVTRA06Y.cpy for field definitions
       COPY CVTRA06Y.
      * FILE STATUS for DALYTRAN-FILE (sequential input)
       01  DALYTRAN-STATUS.
           05  DALYTRAN-STAT1      PIC X.
           05  DALYTRAN-STAT2      PIC X.

      * Customer master record layout (500 bytes)
      * See app/cpy/CVCUS01Y.cpy for field definitions
       COPY CVCUS01Y.
      * FILE STATUS for CUSTOMER-FILE (VSAM KSDS)
       01  CUSTFILE-STATUS.
           05  CUSTFILE-STAT1      PIC X.
           05  CUSTFILE-STAT2      PIC X.

      * Card-to-account cross-reference record layout (50 bytes)
      * See app/cpy/CVACT03Y.cpy for field definitions
       COPY CVACT03Y.
      * FILE STATUS for XREF-FILE (VSAM KSDS)
       01  XREFFILE-STATUS.
           05  XREFFILE-STAT1      PIC X.
           05  XREFFILE-STAT2      PIC X.

      * Card master record layout (150 bytes)
      * See app/cpy/CVACT02Y.cpy for field definitions
       COPY CVACT02Y.
      * FILE STATUS for CARD-FILE (VSAM KSDS)
       01  CARDFILE-STATUS.
           05  CARDFILE-STAT1      PIC X.
           05  CARDFILE-STAT2      PIC X.

      * Account master record layout (300 bytes)
      * See app/cpy/CVACT01Y.cpy for field definitions
       COPY CVACT01Y.
      * FILE STATUS for ACCOUNT-FILE (VSAM KSDS)
       01  ACCTFILE-STATUS.
           05  ACCTFILE-STAT1      PIC X.
           05  ACCTFILE-STAT2      PIC X.

      * Transaction record layout (350 bytes)
      * See app/cpy/CVTRA05Y.cpy for field definitions
       COPY CVTRA05Y.
      * FILE STATUS for TRANSACT-FILE (VSAM KSDS)
       01  TRANFILE-STATUS.
           05  TRANFILE-STAT1      PIC X.
           05  TRANFILE-STAT2      PIC X.

      *---------------------------------------------------------------*
      * Generic I/O status area used by Z-DISPLAY-IO-STATUS
      * to format and display file status codes for diagnostics
      *---------------------------------------------------------------*
       01  IO-STATUS.
           05  IO-STAT1            PIC X.
           05  IO-STAT2            PIC X.

      * Binary-to-alpha conversion helper for non-numeric
      * FILE STATUS byte 2 (used in Z-DISPLAY-IO-STATUS)
       01  TWO-BYTES-BINARY        PIC 9(4) BINARY.
       01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.
           05  TWO-BYTES-LEFT      PIC X.
           05  TWO-BYTES-RIGHT     PIC X.

      * Formatted 4-digit I/O status for display output
      * Format: NNNN where first digit is status class
       01  IO-STATUS-04.
           05  IO-STATUS-0401      PIC 9   VALUE 0.
           05  IO-STATUS-0403      PIC 999 VALUE 0.

      * Application result code used after every I/O operation
      * 0 = success (APPL-AOK), 16 = end-of-file (APPL-EOF)
      * 12 = I/O error, 8 = initial pre-operation value
       01  APPL-RESULT             PIC S9(9)   COMP.
           88  APPL-AOK            VALUE 0.
           88  APPL-EOF            VALUE 16.

      * End-of-file flag for DALYTRAN sequential read loop
      * 'N' = continue reading, 'Y' = EOF reached
       01  END-OF-DAILY-TRANS-FILE             PIC X(01)    VALUE 'N'.
      * Abend code and timing parameters for CEE3ABD call
       01  ABCODE                  PIC S9(9) BINARY.
       01  TIMING                  PIC S9(9) BINARY.
      * Validation status flags for cross-reference and
      * account lookups. 0 = success, 4 = record not found
       01  WS-MISC-VARIABLES.
           05 WS-XREF-READ-STATUS  PIC 9(04).
           05 WS-ACCT-READ-STATUS  PIC 9(04).

      *****************************************************************
      *---------------------------------------------------------------*
      * Main control: opens 6 VSAM files, reads DALYTRAN
      * sequentially, validates each transaction card number
      * via cross-reference lookup, then reads the account.
      * Unverifiable cards are skipped with a DISPLAY message.
      *---------------------------------------------------------------*
       PROCEDURE DIVISION.
       MAIN-PARA.
           DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'.
      * Open all six files: DALYTRAN (sequential input),
      * CUSTFILE, XREFFILE, CARDFILE, ACCTFILE, TRANFILE
      * (all VSAM KSDS random-read). Each open paragraph
      * abends the program if FILE STATUS is not '00'.
           PERFORM 0000-DALYTRAN-OPEN.
           PERFORM 0100-CUSTFILE-OPEN.
           PERFORM 0200-XREFFILE-OPEN.
           PERFORM 0300-CARDFILE-OPEN.
           PERFORM 0400-ACCTFILE-OPEN.
           PERFORM 0500-TRANFILE-OPEN.

      * Main processing loop: reads DALYTRAN records one at
      * a time until end-of-file. For each record, validates
      * the card number via cross-reference lookup and then
      * verifies the associated account exists.
           PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
               IF  END-OF-DAILY-TRANS-FILE = 'N'
      * Read the next daily transaction record
                   PERFORM 1000-DALYTRAN-GET-NEXT
      * Display the raw record for diagnostic trace
                   IF  END-OF-DAILY-TRANS-FILE = 'N'
                       DISPLAY DALYTRAN-RECORD
                   END-IF
      * Step 1: Look up card number in cross-reference
      * Copies DALYTRAN-CARD-NUM to XREF-CARD-NUM for
      * the keyed READ in 2000-LOOKUP-XREF
                   MOVE 0                 TO WS-XREF-READ-STATUS
                   MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
                   PERFORM 2000-LOOKUP-XREF
      * Step 2: If XREF found, read the account record
      * using XREF-ACCT-ID from the cross-reference
                   IF WS-XREF-READ-STATUS = 0
                     MOVE 0            TO WS-ACCT-READ-STATUS
                     MOVE XREF-ACCT-ID TO ACCT-ID
                     PERFORM 3000-READ-ACCOUNT
      * Report if account does not exist for this card
                     IF WS-ACCT-READ-STATUS NOT = 0
                         DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
                     END-IF
                   ELSE
      * Card not in cross-reference: skip this transaction
                     DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
                     ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'
                     DALYTRAN-ID
                   END-IF
               END-IF
           END-PERFORM.

      * Close all six files after processing completes
           PERFORM 9000-DALYTRAN-CLOSE.
           PERFORM 9100-CUSTFILE-CLOSE.
           PERFORM 9200-XREFFILE-CLOSE.
           PERFORM 9300-CARDFILE-CLOSE.
           PERFORM 9400-ACCTFILE-CLOSE.
           PERFORM 9500-TRANFILE-CLOSE.

           DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'.

           GOBACK.

      *****************************************************************
      * READS FILE                                                    *
      *****************************************************************
      * Reads the next daily transaction record sequentially
      * from DALYTRAN-FILE into DALYTRAN-RECORD (CVTRA06Y).
      * Maps FILE STATUS to APPL-RESULT:
      *   '00' -> 0  (APPL-AOK)  success
      *   '10' -> 16 (APPL-EOF)  end-of-file
      *   other -> 12            I/O error, triggers abend
      * Sets END-OF-DAILY-TRANS-FILE = 'Y' on EOF to stop
      * the main processing loop.
       1000-DALYTRAN-GET-NEXT.
      * Sequential READ populates DALYTRAN-RECORD fields
           READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
      * Translate FILE STATUS to application result code
           IF  DALYTRAN-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               IF  DALYTRAN-STATUS = '10'
                   MOVE 16 TO APPL-RESULT
               ELSE
                   MOVE 12 TO APPL-RESULT
               END-IF
           END-IF
      * Evaluate result: continue on success, set EOF flag
      * on end-of-file, or abend on unexpected I/O error
           IF  APPL-AOK
               CONTINUE
           ELSE
               IF  APPL-EOF
                   MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
               ELSE
                   DISPLAY 'ERROR READING DAILY TRANSACTION FILE'
                   MOVE DALYTRAN-STATUS TO IO-STATUS
                   PERFORM Z-DISPLAY-IO-STATUS
                   PERFORM Z-ABEND-PROGRAM
               END-IF
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Looks up the card number in the cross-reference VSAM
      * file (XREFFILE). Performs a keyed READ using
      * FD-XREF-CARD-NUM as the primary key. On success,
      * populates CARD-XREF-RECORD (CVACT03Y) with the
      * cross-reference data including XREF-ACCT-ID and
      * XREF-CUST-ID. Sets WS-XREF-READ-STATUS = 4 on
      * INVALID KEY (card number not found in XREFFILE).
       2000-LOOKUP-XREF.
      * Copy card number to FD key field for VSAM lookup
           MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM
      * Keyed READ into CARD-XREF-RECORD (50-byte xref)
           READ XREF-FILE  RECORD INTO CARD-XREF-RECORD
           KEY IS FD-XREF-CARD-NUM
                INVALID KEY
      * Card not found: set status flag to 4 (not found)
                  DISPLAY 'INVALID CARD NUMBER FOR XREF'
                  MOVE 4 TO WS-XREF-READ-STATUS
                NOT INVALID KEY
      * Card found: display mapped card, account, customer
                  DISPLAY 'SUCCESSFUL READ OF XREF'
                  DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM
                  DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID
                  DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID
           END-READ.
      *---------------------------------------------------------------*
      * Reads the account master record from ACCTFILE using
      * FD-ACCT-ID as the primary key. On success, populates
      * ACCOUNT-RECORD (CVACT01Y) with account details
      * (300 bytes). Sets WS-ACCT-READ-STATUS = 4 on
      * INVALID KEY (account not found).
       3000-READ-ACCOUNT.
      * Copy account ID to FD key field for VSAM lookup
           MOVE ACCT-ID TO FD-ACCT-ID
      * Keyed READ into ACCOUNT-RECORD (300-byte account)
           READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD
           KEY IS FD-ACCT-ID
                INVALID KEY
      * Account not found: set status flag to 4
                  DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
                  MOVE 4 TO WS-ACCT-READ-STATUS
                NOT INVALID KEY
                  DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
           END-READ.
      *---------------------------------------------------------------*
      * Opens the daily transaction staging file for sequential
      * input. Sets APPL-RESULT to 8 before OPEN as a
      * pre-operation default, then checks DALYTRAN-STATUS.
      * Abends the program if the file cannot be opened.
       0000-DALYTRAN-OPEN.
      * Set pre-operation default result
           MOVE 8 TO APPL-RESULT.
      * Open DALYTRAN for sequential input reading
           OPEN INPUT DALYTRAN-FILE
      * Check FILE STATUS: '00' = success
           IF  DALYTRAN-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on open failure (critical file)
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE'
               MOVE DALYTRAN-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.

      *---------------------------------------------------------------*
      * Opens the customer master VSAM KSDS for random input.
      * Abends the program if the file cannot be opened.
       0100-CUSTFILE-OPEN.
           MOVE 8 TO APPL-RESULT.
           OPEN INPUT CUSTOMER-FILE
      * Check FILE STATUS: '00' = success
           IF  CUSTFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on open failure (critical file)
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING CUSTOMER FILE'
               MOVE CUSTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Opens the card-to-account cross-reference VSAM KSDS
      * for random input. This file maps card numbers to
      * account IDs. Abends on open failure.
       0200-XREFFILE-OPEN.
           MOVE 8 TO APPL-RESULT.
           OPEN INPUT XREF-FILE
      * Check FILE STATUS: '00' = success
           IF  XREFFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on open failure (critical file)
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING CROSS REF FILE'
               MOVE XREFFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Opens the card master VSAM KSDS for random input.
      * Abends the program if the file cannot be opened.
       0300-CARDFILE-OPEN.
           MOVE 8 TO APPL-RESULT.
           OPEN INPUT CARD-FILE
      * Check FILE STATUS: '00' = success
           IF  CARDFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on open failure (critical file)
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING CARD FILE'
               MOVE CARDFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Opens the account master VSAM KSDS for random input.
      * Validates account existence during the transaction
      * validation flow. Abends on open failure.
       0400-ACCTFILE-OPEN.
           MOVE 8 TO APPL-RESULT.
           OPEN INPUT ACCOUNT-FILE
      * Check FILE STATUS: '00' = success
           IF  ACCTFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on open failure (critical file)
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING ACCOUNT FILE'
               MOVE ACCTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Opens the transaction master VSAM KSDS for random
      * input. Used for checking existing transaction records.
      * Abends on open failure.
       0500-TRANFILE-OPEN.
           MOVE 8 TO APPL-RESULT.
           OPEN INPUT TRANSACT-FILE
      * Check FILE STATUS: '00' = success
           IF  TRANFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on open failure (critical file)
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING TRANSACTION FILE'
               MOVE TRANFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes the daily transaction staging file.
      * Uses ADD 8 TO ZERO as the pre-operation default.
      * Checks DALYTRAN-STATUS after CLOSE; abends on error.
       9000-DALYTRAN-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE DALYTRAN-FILE
      * Check FILE STATUS: '00' = success
           IF  DALYTRAN-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on close failure
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING CUSTOMER FILE'
               MOVE CUSTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes the customer master VSAM KSDS file.
      * Checks CUSTFILE-STATUS after CLOSE; abends on error.
       9100-CUSTFILE-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE CUSTOMER-FILE
      * Check FILE STATUS: '00' = success
           IF  CUSTFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on close failure
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING CUSTOMER FILE'
               MOVE CUSTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes the card-to-account cross-reference VSAM KSDS.
      * Checks XREFFILE-STATUS after CLOSE; abends on error.
       9200-XREFFILE-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE XREF-FILE
      * Check FILE STATUS: '00' = success
           IF  XREFFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on close failure
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING CROSS REF FILE'
               MOVE XREFFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes the card master VSAM KSDS file.
      * Checks CARDFILE-STATUS after CLOSE; abends on error.
       9300-CARDFILE-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE CARD-FILE
      * Check FILE STATUS: '00' = success
           IF  CARDFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on close failure
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING CARD FILE'
               MOVE CARDFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes the account master VSAM KSDS file.
      * Checks ACCTFILE-STATUS after CLOSE; abends on error.
       9400-ACCTFILE-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE ACCOUNT-FILE
      * Check FILE STATUS: '00' = success
           IF  ACCTFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on close failure
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING ACCOUNT FILE'
               MOVE ACCTFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * Closes the transaction master VSAM KSDS file.
      * Checks TRANFILE-STATUS after CLOSE; abends on error.
       9500-TRANFILE-CLOSE.
           ADD 8 TO ZERO GIVING APPL-RESULT.
           CLOSE TRANSACT-FILE
      * Check FILE STATUS: '00' = success
           IF  TRANFILE-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               MOVE 12 TO APPL-RESULT
           END-IF
      * Abend on close failure
           IF  APPL-AOK
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING TRANSACTION FILE'
               MOVE TRANFILE-STATUS TO IO-STATUS
               PERFORM Z-DISPLAY-IO-STATUS
               PERFORM Z-ABEND-PROGRAM
           END-IF
           EXIT.

      *---------------------------------------------------------------*
      * Abnormal termination handler. Calls the Language
      * Environment CEE3ABD service with abend code 999 and
      * TIMING = 0 (immediate abend, no cleanup delay).
      * Invoked when any critical file operation fails.
       Z-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
      * TIMING = 0: immediate termination
           MOVE 0 TO TIMING
      * ABCODE = 999: application-defined abend code
           MOVE 999 TO ABCODE
           CALL 'CEE3ABD'.

      *****************************************************************
      * Formats and displays the FILE STATUS code stored in
      * IO-STATUS for diagnostic output. Handles two cases:
      *   1. Non-numeric or '9x' status: converts the binary
      *      second byte to a 3-digit decimal via the
      *      TWO-BYTES-BINARY/ALPHA REDEFINES technique
      *   2. Numeric status: copies the 2-byte status into
      *      positions 3-4 of the 4-digit display field
      * Output format: 'FILE STATUS IS: NNNN' followed by
      * the 4-digit IO-STATUS-04 value.
       Z-DISPLAY-IO-STATUS.
      * Non-numeric or '9x' FILE STATUS needs binary decode
           IF  IO-STATUS NOT NUMERIC
           OR  IO-STAT1 = '9'
               MOVE IO-STAT1 TO IO-STATUS-04(1:1)
               MOVE 0        TO TWO-BYTES-BINARY
               MOVE IO-STAT2 TO TWO-BYTES-RIGHT
               MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
           ELSE
      * Numeric FILE STATUS: simple 2-digit to 4-digit map
               MOVE '0000' TO IO-STATUS-04
               MOVE IO-STATUS TO IO-STATUS-04(3:2)
               DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
           END-IF
           EXIT.


