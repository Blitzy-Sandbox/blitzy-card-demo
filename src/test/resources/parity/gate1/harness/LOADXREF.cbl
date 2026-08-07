      ******************************************************************
      * Program     : LOADXREF.CBL
      * Application : CardDemo
      * Type        : Gate 1 parity-oracle derivation utility (BATCH COBOL)
      * Function    : Loads app/data/ASCII/cardxref.txt into the INDEXED XREFFILE
      *               that app/cbl/CBTRN02C.cbl:L40-L44 opens INPUT. Stands in for the
      *               IDCAMS REPRO of app/jcl/XREFFILE.jcl. The fixture carries the 36
      *               populated bytes of app/cpy/CVACT03Y.cpy; the 14-byte trailing
      *               FILLER is space-padded by the LINE SEQUENTIAL read.
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
       PROGRAM-ID.    LOADXREF.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT FLAT-FILE ASSIGN TO FLATIN
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS FLAT-STATUS.
           SELECT KSDS-FILE ASSIGN TO XREFFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS KS-KEY
                  FILE STATUS  IS KSDS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  FLAT-FILE.
       01  FLAT-REC                   PIC X(50).
       FD  KSDS-FILE.
       01  KSDS-REC.
           05 KS-KEY                  PIC X(16).
           05 KS-DATA                 PIC X(34).
       WORKING-STORAGE SECTION.
       01  FLAT-STATUS                PIC X(02).
       01  KSDS-STATUS                PIC X(02).
       01  WS-EOF                     PIC X(01) VALUE 'N'.
       01  WS-COUNT                   PIC 9(07) VALUE 0.
       PROCEDURE DIVISION.
           OPEN INPUT FLAT-FILE
           IF FLAT-STATUS NOT = '00'
              DISPLAY 'LOADXREF OPEN INPUT FAILED ' FLAT-STATUS
              MOVE 12 TO RETURN-CODE
              GOBACK
           END-IF
           OPEN OUTPUT KSDS-FILE
           IF KSDS-STATUS NOT = '00'
              DISPLAY 'LOADXREF OPEN OUTPUT FAILED ' KSDS-STATUS
              MOVE 12 TO RETURN-CODE
              GOBACK
           END-IF
           PERFORM UNTIL WS-EOF = 'Y'
              READ FLAT-FILE
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                    MOVE FLAT-REC TO KSDS-REC
                    WRITE KSDS-REC
                    IF KSDS-STATUS NOT = '00'
                       DISPLAY 'LOADXREF WRITE FAILED ' KSDS-STATUS
                       MOVE 12 TO RETURN-CODE
                       GOBACK
                    END-IF
                    ADD 1 TO WS-COUNT
              END-READ
           END-PERFORM
           CLOSE FLAT-FILE
           CLOSE KSDS-FILE
           DISPLAY 'LOADXREF LOADED ' WS-COUNT
           GOBACK.
