      ******************************************************************
      * CardDemo - Transaction altered Layout for use in reporting
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
      *----------------------------------------------------------------*
      * Record Layout Copybook: Reporting/Statement Transaction
      * Layout (350 bytes total record length).
      *
      * Defines an alternate flattened transaction record
      * (TRNX-RECORD) optimized for the statement generation
      * batch program CBSTM03A.CBL. Unlike the standard
      * CVTRA05Y.cpy layout (TRAN-RECORD) which uses the
      * transaction ID as primary key, this layout places the
      * card number first in the composite key, enabling
      * efficient card-based sequential processing for
      * statement grouping and sorting.
      *
      * Composite Key (32 bytes):
      *   TRNX-CARD-NUM (16 bytes) - primary sort key
      *   TRNX-ID       (16 bytes) - secondary sort key
      *
      * Key Difference from CVTRA05Y.cpy:
      *   CVTRA05Y: TRAN-ID is primary key (ID-keyed)
      *   COSTM01:  TRNX-CARD-NUM is primary (card-keyed)
      * This key ordering groups all transactions for the same
      * card together, allowing CBSTM03A to process one card
      * at a time without secondary sort operations.
      *
      * The TRNX- field name prefix distinguishes this record
      * from the TRAN- prefix used in CVTRA05Y.cpy, preventing
      * name collisions when both copybooks are included.
      *
      * Cross-References:
      *   Standard layout : CVTRA05Y.cpy (TRAN-RECORD)
      *   Consumer program: CBSTM03A.CBL (statement generation)
      *----------------------------------------------------------------*
       01  TRNX-RECORD.
      * Composite key group (32 bytes, bytes 1-32)
      * Enables card-based sequential access for statements
           05  TRNX-KEY.
      * Card number - primary sort key (bytes 1-16)
               10  TRNX-CARD-NUM                       PIC X(16).
      * Transaction ID - secondary key (bytes 17-32)
               10  TRNX-ID                             PIC X(16).
      * Non-key transaction data (318 bytes, bytes 33-350)
      * Same fields as CVTRA05Y but with TRNX- prefix
           05  TRNX-REST.
      * Transaction type code (bytes 33-34)
               10  TRNX-TYPE-CD                        PIC X(02).
      * Transaction category code (bytes 35-38)
               10  TRNX-CAT-CD                         PIC 9(04).
      * Transaction source identifier (bytes 39-48)
               10  TRNX-SOURCE                         PIC X(10).
      * Transaction description text (bytes 49-148)
               10  TRNX-DESC                           PIC X(100).
      * Transaction amount with 2 decimal places
      * Signed numeric in display format (bytes 149-159)
               10  TRNX-AMT                            PIC S9(09)V99.
      * Merchant identifier (bytes 160-168)
               10  TRNX-MERCHANT-ID                    PIC 9(09).
      * Merchant name (bytes 169-218)
               10  TRNX-MERCHANT-NAME                  PIC X(50).
      * Merchant city (bytes 219-268)
               10  TRNX-MERCHANT-CITY                  PIC X(50).
      * Merchant ZIP or postal code (bytes 269-278)
               10  TRNX-MERCHANT-ZIP                   PIC X(10).
      * Original transaction timestamp (bytes 279-304)
      * ISO-8601 format: YYYY-MM-DD-HH.MM.SS.NNNNNN
               10  TRNX-ORIG-TS                        PIC X(26).
      * Processing/posting timestamp (bytes 305-330)
      * ISO-8601 format: YYYY-MM-DD-HH.MM.SS.NNNNNN
               10  TRNX-PROC-TS                        PIC X(26).
      * Reserved filler for future use (bytes 331-350)
               10  FILLER                              PIC X(20).


