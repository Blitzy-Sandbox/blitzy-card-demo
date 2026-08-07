      ******************************************************************
      * Program     : DUMPTRAN.CBL
      * Application : CardDemo
      * Type        : Gate 1 parity-oracle derivation utility (BATCH COBOL)
      * Function    : Unloads the TRANFILE written by app/cbl/CBTRN02C.cbl in key
      *               order, so the postings of its 2900-WRITE-TRANSACTION-FILE
      *               paragraph at :L562-L579 become a reviewable flat image.
      * Source      : No legacy analogue - this is scaffolding that replaces
      *               the IDCAMS utility steps of the JCL named above so the
      *               FROZEN app/cbl/CBTRN02C.cbl can be executed unmodified.
      *               It is never part of the shipped application. @ 7756d89
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
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    DUMPTRAN.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT KSDS-FILE ASSIGN TO TRANFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS KS-KEY
                  FILE STATUS  IS KSDS-STATUS.
           SELECT FLAT-FILE ASSIGN TO FLATOUT
                  ORGANIZATION IS SEQUENTIAL
                  ACCESS MODE  IS SEQUENTIAL
                  FILE STATUS  IS FLAT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  KSDS-FILE.
       01  KSDS-REC.
           05 KS-KEY                  PIC X(16).
           05 KS-DATA                 PIC X(334).
       FD  FLAT-FILE.
       01  FLAT-REC                   PIC X(350).
       WORKING-STORAGE SECTION.
       01  FLAT-STATUS                PIC X(02).
       01  KSDS-STATUS                PIC X(02).
       01  WS-EOF                     PIC X(01) VALUE 'N'.
       01  WS-COUNT                   PIC 9(07) VALUE 0.
       PROCEDURE DIVISION.
           OPEN INPUT KSDS-FILE
           IF KSDS-STATUS NOT = '00'
              DISPLAY 'DUMPTRAN OPEN INPUT FAILED ' KSDS-STATUS
              MOVE 12 TO RETURN-CODE
              GOBACK
           END-IF
           OPEN OUTPUT FLAT-FILE
           IF FLAT-STATUS NOT = '00'
              DISPLAY 'DUMPTRAN OPEN OUTPUT FAILED ' FLAT-STATUS
              MOVE 12 TO RETURN-CODE
              GOBACK
           END-IF
           PERFORM UNTIL WS-EOF = 'Y'
              READ KSDS-FILE NEXT RECORD
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                    MOVE KSDS-REC TO FLAT-REC
                    WRITE FLAT-REC
                    ADD 1 TO WS-COUNT
              END-READ
           END-PERFORM
           CLOSE KSDS-FILE
           CLOSE FLAT-FILE
           DISPLAY 'DUMPTRAN DUMPED ' WS-COUNT
           GOBACK.
