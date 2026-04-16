       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CBSTM03B.
       AUTHOR.        AWS.
      ******************************************************************
      * Program     : CBSTM03B.CBL
      * Application : CardDemo
      * Type        : BATCH COBOL Subroutine
      * Function    : Does file processing related to Transact Report
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
      * This program is to called by the statement create program
      * It does file handling
      ******************************************************************
      * Batch subroutine: File service abstraction for CBSTM03A
      * Called via COBOL CALL 'CBSTM03B' USING parameters
      * Provides OPEN, READ, CLOSE for 4 VSAM indexed datasets
      * Abstracts file I/O so CBSTM03A focuses on statement
      * formatting and report generation logic
      * Files served:
      *   TRNXFILE - Transactions (sequential, CVTRA05Y layout)
      *   XREFFILE - Card cross-refs (sequential, CVACT03Y)
      *   CUSTFILE - Customers (random keyed, CVCUS01Y layout)
      *   ACCTFILE - Accounts (random keyed, CVACT01Y layout)
      * Caller: CBSTM03A.CBL (statement generation program)
      ******************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
      *----------------------------------------------------------------*
      * FILE-CONTROL assigns 4 VSAM KSDS datasets to COBOL file
      * names. Sequential access files (TRNX, XREF) support
      * full-file scans. Random access files (CUST, ACCT)
      * support keyed lookups by primary key.
      *----------------------------------------------------------------*
      * Transaction file - sequential scan of all transactions
      * Indexed by composite key: card number + transaction ID
      * Record layout corresponds to CVTRA05Y.cpy (350 bytes)
           SELECT TRNX-FILE ASSIGN TO TRNXFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS FD-TRNXS-ID
                  FILE STATUS  IS TRNXFILE-STATUS.

      * Cross-reference file - sequential scan of card-to-acct
      * links. Indexed by card number (16-byte key)
      * Record layout corresponds to CVACT03Y.cpy (50 bytes)
           SELECT XREF-FILE ASSIGN TO   XREFFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS FD-XREF-CARD-NUM
                  FILE STATUS  IS XREFFILE-STATUS.

      * Customer file - random access for keyed lookups by
      * customer ID (9-digit). Retrieves customer details
      * for statement generation
      * Record layout corresponds to CVCUS01Y.cpy (500 bytes)
           SELECT CUST-FILE ASSIGN TO CUSTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-CUST-ID
                  FILE STATUS  IS CUSTFILE-STATUS.

      * Account file - random access for keyed lookups by
      * account ID (11-digit). Retrieves account details
      * for statement generation
      * Record layout corresponds to CVACT01Y.cpy (300 bytes)
           SELECT ACCT-FILE ASSIGN TO ACCTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-ACCT-ID
                  FILE STATUS  IS ACCTFILE-STATUS.

      *
      *----------------------------------------------------------------*
      * DATA DIVISION defines file record structures, working
      * storage for FILE STATUS tracking, and the LINKAGE
      * SECTION parameter interface from CBSTM03A via CALL.
      *----------------------------------------------------------------*
       DATA DIVISION.
       FILE SECTION.
      * Transaction file descriptor - 350-byte records
      * Key: FD-TRNXS-ID (card num 16 + tran ID 16 = 32)
      * Remaining 318 bytes hold transaction detail fields
       FD  TRNX-FILE.
       01  FD-TRNXFILE-REC.
           05 FD-TRNXS-ID.
              10  FD-TRNX-CARD                  PIC X(16).
              10  FD-TRNX-ID                    PIC X(16).
           05 FD-ACCT-DATA                      PIC X(318).

      * Cross-reference file descriptor - 50-byte records
      * Key: FD-XREF-CARD-NUM (16-byte card number)
      * Links cards to customer IDs and account IDs
       FD  XREF-FILE.
       01  FD-XREFFILE-REC.
           05 FD-XREF-CARD-NUM                  PIC X(16).
           05 FD-XREF-DATA                      PIC X(34).

      * Customer file descriptor - 500-byte records
      * Key: FD-CUST-ID (9-digit customer identifier)
      * Holds name, address, demographics, credit score
       FD  CUST-FILE.
       01  FD-CUSTFILE-REC.
           05 FD-CUST-ID                        PIC X(09).
           05 FD-CUST-DATA                      PIC X(491).

      * Account file descriptor - 300-byte records
      * Key: FD-ACCT-ID (11-digit account identifier)
      * Holds balances, credit limits, dates, group info
       FD  ACCT-FILE.
       01  FD-ACCTFILE-REC.
           05 FD-ACCT-ID                        PIC 9(11).
           05 FD-ACCT-DATA                      PIC X(289).

       WORKING-STORAGE SECTION.
      *----------------------------------------------------------------*
      * FILE STATUS variables - each file has a 2-byte status
      * field populated by the runtime after every I/O operation
      * Common values: '00'=success, '10'=end-of-file,
      * '23'=record not found, '35'=file not found,
      * '97'=file not open. Status is propagated to caller
      * via LK-M03B-RC in the LINKAGE SECTION.
      *----------------------------------------------------------------*
      *****************************************************************
       01  TRNXFILE-STATUS.
           05  TRNXFILE-STAT1      PIC X.
           05  TRNXFILE-STAT2      PIC X.

       01  XREFFILE-STATUS.
           05  XREFFILE-STAT1      PIC X.
           05  XREFFILE-STAT2      PIC X.

       01  CUSTFILE-STATUS.
           05  CUSTFILE-STAT1      PIC X.
           05  CUSTFILE-STAT2      PIC X.

       01  ACCTFILE-STATUS.
           05  ACCTFILE-STAT1      PIC X.
           05  ACCTFILE-STAT2      PIC X.

      *----------------------------------------------------------------*
      * LINKAGE SECTION - Parameter interface from CBSTM03A
      * Caller passes a single group structure LK-M03B-AREA
      * containing file name, operation code, return code,
      * key, key length, and a 1000-byte record buffer.
      *----------------------------------------------------------------*
       LINKAGE SECTION.
      * Parameter block received via CALL 'CBSTM03B' USING
       01  LK-M03B-AREA.
      * DD name identifying target file (e.g. TRNXFILE)
           05  LK-M03B-DD          PIC X(08).
      * Operation code - single char dispatching the action
           05  LK-M03B-OPER        PIC X(01).
             88  M03B-OPEN       VALUE 'O'.
             88  M03B-CLOSE      VALUE 'C'.
             88  M03B-READ       VALUE 'R'.
             88  M03B-READ-K     VALUE 'K'.
             88  M03B-WRITE      VALUE 'W'.
             88  M03B-REWRITE    VALUE 'Z'.
      * Return code - receives FILE STATUS after I/O op
      * '00'=success, '10'=EOF, '23'=not found
           05  LK-M03B-RC          PIC X(02).
      * Record key value for random-access READ-K ops
           05  LK-M03B-KEY         PIC X(25).
      * Length of the key value in LK-M03B-KEY
           05  LK-M03B-KEY-LN      PIC S9(4).
      * Record data buffer - receives full record on READ
      * 1000 bytes accommodates the largest record (500-byte
      * customer) with room to spare
           05  LK-M03B-FLDT        PIC X(1000).

      *----------------------------------------------------------------*
      * PROCEDURE DIVISION - Entry point receives LK-M03B-AREA
      * from CBSTM03A via CALL statement. Dispatches to the
      * appropriate file handler based on the DD name in
      * LK-M03B-DD, then returns control via GOBACK.
      *----------------------------------------------------------------*
       PROCEDURE DIVISION USING LK-M03B-AREA.

      * Main entry point - dispatches by file DD name to the
      * appropriate file processing paragraph
       0000-START.

      * Route to the correct file handler based on DD name
      * Each WHEN clause performs the matching file handler
      * paragraph. Unrecognized DD names fall through to GOBACK.
           EVALUATE LK-M03B-DD
             WHEN 'TRNXFILE'
               PERFORM 1000-TRNXFILE-PROC THRU 1999-EXIT
             WHEN 'XREFFILE'
               PERFORM 2000-XREFFILE-PROC THRU 2999-EXIT
             WHEN 'CUSTFILE'
               PERFORM 3000-CUSTFILE-PROC THRU 3999-EXIT
             WHEN 'ACCTFILE'
               PERFORM 4000-ACCTFILE-PROC THRU 4999-EXIT
             WHEN OTHER
               GO TO 9999-GOBACK.

      * Returns control to calling program CBSTM03A
       9999-GOBACK.
           GOBACK.

      *----------------------------------------------------------------*
      * 1000 - Transaction file (TRNXFILE) handler
      * Processes OPEN, READ, and CLOSE operations for the
      * transaction VSAM KSDS. Sequential access mode reads
      * records in key order for full-file scanning.
      * Record layout: 350 bytes per CVTRA05Y.cpy
      *----------------------------------------------------------------*
       1000-TRNXFILE-PROC.

      * Opens transaction file for sequential input reading
           IF M03B-OPEN
               OPEN INPUT TRNX-FILE
               GO TO 1900-EXIT
           END-IF.

      * Reads next sequential transaction record into buffer
      * Returns '10' in RC at end-of-file
           IF M03B-READ
               READ TRNX-FILE INTO LK-M03B-FLDT
               END-READ
               GO TO 1900-EXIT
           END-IF.

      * Closes the transaction file after processing completes
           IF M03B-CLOSE
               CLOSE TRNX-FILE
               GO TO 1900-EXIT
           END-IF.

      * Propagates TRNXFILE FILE STATUS to caller return code
       1900-EXIT.
           MOVE TRNXFILE-STATUS TO LK-M03B-RC.

      * PERFORM THRU exit point for 1000-TRNXFILE-PROC
       1999-EXIT.
           EXIT.

      *----------------------------------------------------------------*
      * 2000 - Cross-reference file (XREFFILE) handler
      * Processes OPEN, READ, and CLOSE operations for the
      * card-to-account cross-reference VSAM KSDS. Sequential
      * access reads records in card-number order.
      * Record layout: 50 bytes per CVACT03Y.cpy
      *----------------------------------------------------------------*
       2000-XREFFILE-PROC.

      * Opens cross-reference file for sequential input reading
           IF M03B-OPEN
               OPEN INPUT XREF-FILE
               GO TO 2900-EXIT
           END-IF.

      * Reads next sequential xref record into buffer
      * Returns '10' in RC at end-of-file
           IF M03B-READ
               READ XREF-FILE INTO LK-M03B-FLDT
               END-READ
               GO TO 2900-EXIT
           END-IF.

      * Closes the cross-reference file after processing
           IF M03B-CLOSE
               CLOSE XREF-FILE
               GO TO 2900-EXIT
           END-IF.

      * Propagates XREFFILE FILE STATUS to caller return code
       2900-EXIT.
           MOVE XREFFILE-STATUS TO LK-M03B-RC.

      * PERFORM THRU exit point for 2000-XREFFILE-PROC
       2999-EXIT.
           EXIT.

      *----------------------------------------------------------------*
      * 3000 - Customer file (CUSTFILE) handler
      * Processes OPEN, READ-K (keyed), and CLOSE operations
      * for the customer VSAM KSDS. Random access mode enables
      * direct lookup by customer ID without scanning.
      * Record layout: 500 bytes per CVCUS01Y.cpy
      *----------------------------------------------------------------*
       3000-CUSTFILE-PROC.

      * Opens customer file for random keyed input reading
           IF M03B-OPEN
               OPEN INPUT CUST-FILE
               GO TO 3900-EXIT
           END-IF.

      * Reads customer record by key - moves caller-supplied
      * key (trimmed to LK-M03B-KEY-LN) into FD-CUST-ID,
      * then reads matching record into the data buffer.
      * Returns '23' if customer not found
           IF M03B-READ-K
               MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID
               READ CUST-FILE INTO LK-M03B-FLDT
               END-READ
               GO TO 3900-EXIT
           END-IF.

      * Closes the customer file after processing completes
           IF M03B-CLOSE
               CLOSE CUST-FILE
               GO TO 3900-EXIT
           END-IF.

      * Propagates CUSTFILE FILE STATUS to caller return code
       3900-EXIT.
           MOVE CUSTFILE-STATUS TO LK-M03B-RC.

      * PERFORM THRU exit point for 3000-CUSTFILE-PROC
       3999-EXIT.
           EXIT.

      *----------------------------------------------------------------*
      * 4000 - Account file (ACCTFILE) handler
      * Processes OPEN, READ-K (keyed), and CLOSE operations
      * for the account VSAM KSDS. Random access mode enables
      * direct lookup by account ID for statement generation.
      * Record layout: 300 bytes per CVACT01Y.cpy
      *----------------------------------------------------------------*
       4000-ACCTFILE-PROC.

      * Opens account file for random keyed input reading
           IF M03B-OPEN
               OPEN INPUT ACCT-FILE
               GO TO 4900-EXIT
           END-IF.

      * Reads account record by key - moves caller-supplied
      * key (trimmed to LK-M03B-KEY-LN) into FD-ACCT-ID,
      * then reads matching record into the data buffer.
      * Returns '23' if account not found
           IF M03B-READ-K
               MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID
               READ ACCT-FILE INTO LK-M03B-FLDT
               END-READ
               GO TO 4900-EXIT
           END-IF.

      * Closes the account file after processing completes
           IF M03B-CLOSE
               CLOSE ACCT-FILE
               GO TO 4900-EXIT
           END-IF.

      * Propagates ACCTFILE FILE STATUS to caller return code
       4900-EXIT.
           MOVE ACCTFILE-STATUS TO LK-M03B-RC.

      * PERFORM THRU exit point for 4000-ACCTFILE-PROC
       4999-EXIT.
           EXIT.

