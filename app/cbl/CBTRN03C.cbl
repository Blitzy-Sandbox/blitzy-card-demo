      ******************************************************************
      * Program     : CBTRN03C.CBL                                      
      * Application : CardDemo                                          
      * Type        : BATCH COBOL Program                                
      * Function    : Print the transaction detail report.     
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
      * CBTRN03C - Transaction Detail Report (Batch)
      *
      * Reads the transaction master file (TRANFILE) sequentially
      * and produces a paginated detail report filtered by a
      * date range read from a parameter file (DATEPARM).
      * Transactions outside the date range are skipped.
      * For each transaction, looks up the card cross-reference,
      * transaction type description, and transaction category
      * description. The report includes 3-level totals:
      *   - Page totals (every WS-PAGE-SIZE lines)
      *   - Account totals (on card number change)
      *   - Grand total (at end of file)
      *
      * Invoked by: TRANREPT.jcl
      *
      * Files accessed:
      *   TRANFILE  - Transaction master (sequential input)
      *   XREFFILE  - Card cross-reference (KSDS, random read)
      *   TRANTYPF  - Transaction type lookup (KSDS, random)
      *   TRANCATF  - Transaction category lookup (KSDS, random)
      *   REPTFILE  - Report output (sequential, 133-col)
      *   DATEPARM  - Date range parameter file (sequential)
      *
      * Copybooks: CVTRA05Y (transaction record),
      *            CVACT03Y (cross-reference record),
      *            CVTRA03Y (transaction type record),
      *            CVTRA04Y (transaction category record),
      *            CVTRA07Y (report line formats)
      *
      * Report output uses 133-char print lines (CVTRA07Y)
      * Date range read from DATEPARM DD (REPROC or SYSIN)
      *
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBTRN03C.                                                 
       AUTHOR.        AWS.                                                      
                                                                                
       ENVIRONMENT DIVISION.                                                    
       INPUT-OUTPUT SECTION.                                                    
       FILE-CONTROL.                                                            
      * TRANSACT-FILE: Posted transaction master VSAM KSDS,
      *   sequential access for date-filtered scan
           SELECT TRANSACT-FILE ASSIGN TO TRANFILE                              
                  ORGANIZATION IS SEQUENTIAL                                    
                  FILE STATUS  IS TRANFILE-STATUS.                              
                                                                                
      * XREF-FILE: Card cross-reference VSAM KSDS, random
      *   access to resolve account ID by card number
           SELECT XREF-FILE ASSIGN TO CARDXREF                                  
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-XREF-CARD-NUM                              
                  FILE STATUS  IS CARDXREF-STATUS.                              
                                                                                
      * TRANTYPE-FILE: Transaction type VSAM KSDS, random
      *   access for type description by 2-byte code
           SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE                              
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-TRAN-TYPE                                  
                  FILE STATUS  IS TRANTYPE-STATUS.                              
                                                                                
      * TRANCATG-FILE: Transaction category VSAM KSDS,
      *   random access by composite key (type+category)
           SELECT TRANCATG-FILE ASSIGN TO TRANCATG                              
                  ORGANIZATION IS INDEXED                                       
                  ACCESS MODE  IS RANDOM                                        
                  RECORD KEY   IS FD-TRAN-CAT-KEY                               
                  FILE STATUS  IS TRANCATG-STATUS.                              
                                                                                
      * REPORT-FILE: Output report, sequential 133-char
      *   print lines routed to GDG dataset via JCL
           SELECT REPORT-FILE ASSIGN TO TRANREPT                                
                  ORGANIZATION IS SEQUENTIAL                                    
                  FILE STATUS  IS TRANREPT-STATUS.                              
                                                                                
      * DATE-PARMS-FILE: Date range parameter input file,
      *   contains start and end dates for filtering
           SELECT DATE-PARMS-FILE ASSIGN TO DATEPARM                            
                  ORGANIZATION IS SEQUENTIAL                                    
                  FILE STATUS  IS DATEPARM-STATUS.                              
      *                                                                         
       DATA DIVISION.                                                           
       FILE SECTION.                                                            
      * Transaction master - 350-byte record: data(304),
      *   process timestamp(26), filler(20)
       FD  TRANSACT-FILE.                                                       
       01 FD-TRANFILE-REC.                                                      
          05 FD-TRANS-DATA      PIC X(304).                                     
          05 FD-TRAN-PROC-TS    PIC X(26).                                      
          05 FD-FILLER          PIC X(20).                                      
                                                                                
      * Card cross-reference - 50-byte record: 16-byte
      *   card number key plus 34 bytes of xref data
       FD  XREF-FILE.                                                           
       01  FD-CARDXREF-REC.                                                     
           05 FD-XREF-CARD-NUM                  PIC X(16).                      
           05 FD-XREF-DATA                      PIC X(34).                      
                                                                                
      * Transaction type - 60-byte record: 2-byte type code
      *   key plus 50-byte description and 8-byte filler
       FD  TRANTYPE-FILE.                                                       
       01 FD-TRANTYPE-REC.                                                      
          05 FD-TRAN-TYPE       PIC X(02).                                      
          05 FD-TRAN-DATA       PIC X(58).                                      
                                                                                
      * Transaction category - 60-byte record: composite
      *   key (type-cd + cat-cd) plus 50-byte description
       FD  TRANCATG-FILE.                                                       
       01 FD-TRAN-CAT-RECORD.                                                   
           05  FD-TRAN-CAT-KEY.                                                 
              10  FD-TRAN-TYPE-CD                         PIC X(02).            
              10  FD-TRAN-CAT-CD                          PIC 9(04).            
           05  FD-TRAN-CAT-DATA                           PIC X(54).            
                                                                                
      * Report output - 133-byte standard print line width
       FD  REPORT-FILE.                                                         
       01 FD-REPTFILE-REC       PIC X(133).                                     
                                                                                
      * Date parameter - 80-byte card-image record with
      *   start-date(10), separator(1), end-date(10)
       FD  DATE-PARMS-FILE.                                                     
       01 FD-DATEPARM-REC       PIC X(80).                                      
                                                                                
       WORKING-STORAGE SECTION.                                                 
                                                                                
      *****************************************************************         
      * Include 350-byte transaction record layout
      * See app/cpy/CVTRA05Y.cpy for field definitions
       COPY CVTRA05Y.                                                           
       01 TRANFILE-STATUS.                                                      
          05 TRANFILE-STAT1     PIC X.                                          
          05 TRANFILE-STAT2     PIC X.                                          
                                                                                
      * Include 50-byte card cross-reference record
      * See app/cpy/CVACT03Y.cpy for field definitions
       COPY CVACT03Y.                                                           
       01  CARDXREF-STATUS.                                                     
           05  CARDXREF-STAT1      PIC X.                                       
           05  CARDXREF-STAT2      PIC X.                                       
                                                                                
      * Include 60-byte transaction type record layout
      * See app/cpy/CVTRA03Y.cpy for field definitions
       COPY CVTRA03Y.                                                           
       01  TRANTYPE-STATUS.                                                     
           05  TRANTYPE-STAT1      PIC X.                                       
           05  TRANTYPE-STAT2      PIC X.                                       
                                                                                
      * Include 60-byte transaction category record
      * See app/cpy/CVTRA04Y.cpy for field definitions
       COPY CVTRA04Y.                                                           
       01  TRANCATG-STATUS.                                                     
           05  TRANCATG-STAT1      PIC X.                                       
           05  TRANCATG-STAT2      PIC X.                                       
                                                                                
      * Include report format structures: headers, detail
      * line, page/account/grand totals (CVTRA07Y.cpy)
       COPY CVTRA07Y.                                                           
       01 TRANREPT-STATUS.                                                      
           05 REPTFILE-STAT1     PIC X.                                         
           05 REPTFILE-STAT2     PIC X.                                         
                                                                                
      * FILE STATUS area for date parameter file
       01 DATEPARM-STATUS.                                                      
           05 DATEPARM-STAT1     PIC X.                                         
           05 DATEPARM-STAT2     PIC X.                                         
                                                                                
      * Date parameter working storage: start-date(10)
      *   + separator(1) + end-date(10) from DATEPARM DD
       01 WS-DATEPARM-RECORD.                                                   
           05 WS-START-DATE      PIC X(10).                                     
           05 FILLER             PIC X(01).                                     
           05 WS-END-DATE        PIC X(10).                                     
                                                                                
      * Report control variables: first-time flag, line
      *   counter, page size, 3-level total accumulators
      *   (page, account, grand), and card number tracker
       01 WS-REPORT-VARS.                                                       
           05 WS-FIRST-TIME      PIC X      VALUE 'Y'.                          
           05 WS-LINE-COUNTER    PIC 9(09) COMP-3                               
                                            VALUE 0.                            
           05 WS-PAGE-SIZE       PIC 9(03) COMP-3                               
                                            VALUE 20.                           
           05 WS-BLANK-LINE      PIC X(133) VALUE SPACES.                       
           05 WS-PAGE-TOTAL      PIC S9(09)V99 VALUE 0.                         
           05 WS-ACCOUNT-TOTAL   PIC S9(09)V99 VALUE 0.                         
           05 WS-GRAND-TOTAL     PIC S9(09)V99 VALUE 0.                         
           05 WS-CURR-CARD-NUM   PIC X(16) VALUE SPACES.                        
                                                                                
      * General I/O status and binary conversion fields
      *   for displaying extended FILE STATUS codes
       01 IO-STATUS.                                                            
          05 IO-STAT1           PIC X.                                          
          05 IO-STAT2           PIC X.                                          
       01 TWO-BYTES-BINARY      PIC 9(4) BINARY.                                
       01 TWO-BYTES-ALPHA REDEFINES TWO-BYTES-BINARY.                           
          05 TWO-BYTES-LEFT     PIC X.                                          
          05 TWO-BYTES-RIGHT    PIC X.                                          
       01 IO-STATUS-04.                                                         
          05 IO-STATUS-0401     PIC 9      VALUE 0.                             
          05 IO-STATUS-0403     PIC 999    VALUE 0.                             
                                                                                
      * Application result code with 88-level conditions:
      *   APPL-AOK(0) = success, APPL-EOF(16) = end of file
       01 APPL-RESULT           PIC S9(9) COMP.                                 
          88 APPL-AOK                      VALUE 0.                             
          88 APPL-EOF                      VALUE 16.                            
                                                                                
      * End-of-file flag and abend control fields
       01 END-OF-FILE           PIC X(01)  VALUE 'N'.                           
       01 ABCODE                PIC S9(9) BINARY.                               
       01 TIMING                PIC S9(9) BINARY.                               
                                                                                
      *****************************************************************         
      * Main control: opens all six files, reads date
      *   parameters, loops through transactions with
      *   date filtering and control-break on card number,
      *   enriches each row via lookups, writes detail
      *   report with 3-level totals, then closes files.
       PROCEDURE DIVISION.                                                      
           DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'.                    
           PERFORM 0000-TRANFILE-OPEN.                                          
           PERFORM 0100-REPTFILE-OPEN.                                          
           PERFORM 0200-CARDXREF-OPEN.                                          
           PERFORM 0300-TRANTYPE-OPEN.                                          
           PERFORM 0400-TRANCATG-OPEN.                                          
           PERFORM 0500-DATEPARM-OPEN.                                          
                                                                                
           PERFORM 0550-DATEPARM-READ.                                          
                                                                                
      * Main processing loop: reads transactions, filters
      *   by date range, detects card number change for
      *   control break, enriches with type and category
      *   lookups, writes detail line. On EOF, writes
      *   final page totals and grand total.
           PERFORM UNTIL END-OF-FILE = 'Y'                                      
             IF END-OF-FILE = 'N'                                               
                PERFORM 1000-TRANFILE-GET-NEXT                                  
                IF TRAN-PROC-TS (1:10) >= WS-START-DATE                         
                   AND TRAN-PROC-TS (1:10) <= WS-END-DATE                       
                   CONTINUE                                                     
                ELSE                                                            
                   NEXT SENTENCE                                                
                END-IF                                                          
                IF END-OF-FILE = 'N'                                            
                   DISPLAY TRAN-RECORD                                          
                   IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM                       
                     IF WS-FIRST-TIME = 'N'                                     
                       PERFORM 1120-WRITE-ACCOUNT-TOTALS                        
                     END-IF                                                     
                     MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM                     
                     MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM                     
                     PERFORM 1500-A-LOOKUP-XREF                                 
                   END-IF                                                       
                   MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE             
                   PERFORM 1500-B-LOOKUP-TRANTYPE                               
                   MOVE TRAN-TYPE-CD OF TRAN-RECORD                             
                     TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY                      
                   MOVE TRAN-CAT-CD OF TRAN-RECORD                              
                     TO FD-TRAN-CAT-CD OF FD-TRAN-CAT-KEY                       
                   PERFORM 1500-C-LOOKUP-TRANCATG                               
                   PERFORM 1100-WRITE-TRANSACTION-REPORT                        
                ELSE                                                            
                 DISPLAY 'TRAN-AMT ' TRAN-AMT                                   
                 DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL                         
                 ADD TRAN-AMT TO WS-PAGE-TOTAL                                  
                                 WS-ACCOUNT-TOTAL                               
                 PERFORM 1110-WRITE-PAGE-TOTALS                                 
                 PERFORM 1110-WRITE-GRAND-TOTALS                                
                END-IF                                                          
             END-IF                                                             
           END-PERFORM.                                                         
                                                                                
           PERFORM 9000-TRANFILE-CLOSE.                                         
           PERFORM 9100-REPTFILE-CLOSE.                                         
           PERFORM 9200-CARDXREF-CLOSE.                                         
           PERFORM 9300-TRANTYPE-CLOSE.                                         
           PERFORM 9400-TRANCATG-CLOSE.                                         
           PERFORM 9500-DATEPARM-CLOSE.                                         
                                                                                
           DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'.                      
                                                                                
           GOBACK.                                                              
                                                                                
      * Read the date parameter file.                                           
      * Reads start/end dates from DATEPARM DD record.
      * On success displays the reporting date range.
      * On EOF sets END-OF-FILE flag to skip processing.
      * On error displays FILE STATUS and abends.
       0550-DATEPARM-READ.                                                      
           READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD                         
           EVALUATE DATEPARM-STATUS                                             
             WHEN '00'                                                          
                 MOVE 0 TO APPL-RESULT                                          
             WHEN '10'                                                          
                 MOVE 16 TO APPL-RESULT                                         
             WHEN OTHER                                                         
                 MOVE 12 TO APPL-RESULT                                         
           END-EVALUATE                                                         
                                                                                
           IF APPL-AOK                                                          
              DISPLAY 'Reporting from ' WS-START-DATE                           
                 ' to ' WS-END-DATE                                             
           ELSE                                                                 
              IF APPL-EOF                                                       
                 MOVE 'Y' TO END-OF-FILE                                        
              ELSE                                                              
                 DISPLAY 'ERROR READING DATEPARM FILE'                          
                 MOVE DATEPARM-STATUS TO IO-STATUS                              
                 PERFORM 9910-DISPLAY-IO-STATUS                                 
                 PERFORM 9999-ABEND-PROGRAM                                     
              END-IF                                                            
           .                                                                    
                                                                                
      *****************************************************************         
      * I/O ROUTINES TO ACCESS A KSDS, VSAM DATA SET...               *         
      *****************************************************************         
      * Reads next sequential record from TRANSACT-FILE
      *   into TRAN-RECORD (350-byte layout, CVTRA05Y).
      *   Sets END-OF-FILE on status '10' (EOF).
      *   Abends via 9999 on any other I/O error.
       1000-TRANFILE-GET-NEXT.                                                  
           READ TRANSACT-FILE INTO TRAN-RECORD.                                 
                                                                                
           EVALUATE TRANFILE-STATUS                                             
             WHEN '00'                                                          
                 MOVE 0 TO APPL-RESULT                                          
             WHEN '10'                                                          
                 MOVE 16 TO APPL-RESULT                                         
             WHEN OTHER                                                         
                 MOVE 12 TO APPL-RESULT                                         
           END-EVALUATE                                                         
                                                                                
           IF APPL-AOK                                                          
              CONTINUE                                                          
           ELSE                                                                 
              IF APPL-EOF                                                       
                 MOVE 'Y' TO END-OF-FILE                                        
              ELSE                                                              
                 DISPLAY 'ERROR READING TRANSACTION FILE'                       
                 MOVE TRANFILE-STATUS TO IO-STATUS                              
                 PERFORM 9910-DISPLAY-IO-STATUS                                 
                 PERFORM 9999-ABEND-PROGRAM                                     
              END-IF                                                            
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Handles report output for one transaction row.
      *   On first call sets date range in header and
      *   writes page headers. Checks page-size boundary
      *   for page break. Accumulates transaction amount
      *   into page and account totals, writes detail.
       1100-WRITE-TRANSACTION-REPORT.                                           
           IF WS-FIRST-TIME = 'Y'                                               
              MOVE 'N' TO WS-FIRST-TIME                                         
              MOVE WS-START-DATE TO REPT-START-DATE                             
              MOVE WS-END-DATE TO REPT-END-DATE                                 
              PERFORM 1120-WRITE-HEADERS                                        
           END-IF                                                               
                                                                                
           IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0                   
              PERFORM 1110-WRITE-PAGE-TOTALS                                    
              PERFORM 1120-WRITE-HEADERS                                        
           END-IF                                                               
                                                                                
           ADD TRAN-AMT TO WS-PAGE-TOTAL                                        
                           WS-ACCOUNT-TOTAL                                     
           PERFORM 1120-WRITE-DETAIL                                            
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Writes page total line, rolls page total into
      *   grand total, resets page accumulator, writes
      *   separator line (TRANSACTION-HEADER-2).
       1110-WRITE-PAGE-TOTALS.                                                  
           MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL                                
           MOVE REPORT-PAGE-TOTALS TO FD-REPTFILE-REC                           
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL                                  
           MOVE 0 TO WS-PAGE-TOTAL                                              
           ADD 1 TO WS-LINE-COUNTER                                             
           MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC                         
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
                                                                                
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Writes account total on card number change.
      *   Resets account accumulator and writes a
      *   separator line after the total.
       1120-WRITE-ACCOUNT-TOTALS.                                               
           MOVE WS-ACCOUNT-TOTAL   TO REPT-ACCOUNT-TOTAL                        
           MOVE REPORT-ACCOUNT-TOTALS TO FD-REPTFILE-REC                        
           PERFORM 1111-WRITE-REPORT-REC                                        
           MOVE 0 TO WS-ACCOUNT-TOTAL                                           
           ADD 1 TO WS-LINE-COUNTER                                             
           MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC                         
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
                                                                                
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Writes grand total line at end of report.
      *   Grand total is the sum of all page totals.
       1110-WRITE-GRAND-TOTALS.                                                 
           MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL                              
           MOVE REPORT-GRAND-TOTALS TO FD-REPTFILE-REC                          
           PERFORM 1111-WRITE-REPORT-REC                                        
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Writes report page header block: report name
      *   with date range, blank line, column headers,
      *   and a separator line (dashes).
       1120-WRITE-HEADERS.                                                      
           MOVE REPORT-NAME-HEADER TO FD-REPTFILE-REC                           
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
                                                                                
           MOVE WS-BLANK-LINE TO FD-REPTFILE-REC                                
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
                                                                                
           MOVE TRANSACTION-HEADER-1 TO FD-REPTFILE-REC                         
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
                                                                                
           MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC                         
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
                                                                                
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Low-level write routine for one report line.
      *   Checks TRANREPT-STATUS after WRITE and abends
      *   on any non-zero FILE STATUS.
       1111-WRITE-REPORT-REC.                                                   
                                                                                
           WRITE FD-REPTFILE-REC                                                
           IF TRANREPT-STATUS = '00'                                            
              MOVE 0 TO APPL-RESULT                                             
           ELSE                                                                 
              MOVE 12 TO APPL-RESULT                                            
           END-IF                                                               
           IF APPL-AOK                                                          
              CONTINUE                                                          
           ELSE                                                                 
              DISPLAY 'ERROR WRITING REPTFILE'                                  
              MOVE TRANREPT-STATUS TO IO-STATUS                                 
              PERFORM 9910-DISPLAY-IO-STATUS                                    
              PERFORM 9999-ABEND-PROGRAM                                        
           END-IF                                                               
           EXIT.                                                                
                                                                                
      * Formats one transaction detail line from enriched
      *   data: tran ID, account ID (from XREF), type
      *   code+desc, category code+desc, source, amount.
      *   Uses TRANSACTION-DETAIL-REPORT from CVTRA07Y.
       1120-WRITE-DETAIL.                                                       
           INITIALIZE TRANSACTION-DETAIL-REPORT                                 
           MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID                                 
           MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID                          
           MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD              
           MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC                         
           MOVE TRAN-CAT-CD OF TRAN-RECORD  TO TRAN-REPORT-CAT-CD               
           MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC                      
           MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE                               
           MOVE TRAN-AMT TO TRAN-REPORT-AMT                                     
           MOVE TRANSACTION-DETAIL-REPORT TO FD-REPTFILE-REC                    
           PERFORM 1111-WRITE-REPORT-REC                                        
           ADD 1 TO WS-LINE-COUNTER                                             
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens TRANSACT-FILE for sequential input.
      *   Abends on non-zero FILE STATUS.
       0000-TRANFILE-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT TRANSACT-FILE                                             
           IF TRANFILE-STATUS = '00'                                            
              MOVE 0 TO APPL-RESULT                                             
           ELSE                                                                 
              MOVE 12 TO APPL-RESULT                                            
           END-IF                                                               
           IF APPL-AOK                                                          
              CONTINUE                                                          
           ELSE                                                                 
              DISPLAY 'ERROR OPENING TRANFILE'                                  
              MOVE TRANFILE-STATUS TO IO-STATUS                                 
              PERFORM 9910-DISPLAY-IO-STATUS                                    
              PERFORM 9999-ABEND-PROGRAM                                        
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens REPORT-FILE for sequential output.
      *   Abends on non-zero FILE STATUS.
       0100-REPTFILE-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN OUTPUT REPORT-FILE                                              
           IF TRANREPT-STATUS = '00'                                            
              MOVE 0 TO APPL-RESULT                                             
           ELSE                                                                 
              MOVE 12 TO APPL-RESULT                                            
           END-IF                                                               
           IF APPL-AOK                                                          
              CONTINUE                                                          
           ELSE                                                                 
              DISPLAY 'ERROR OPENING REPTFILE'                                  
              MOVE TRANREPT-STATUS TO IO-STATUS                                 
              PERFORM 9910-DISPLAY-IO-STATUS                                    
              PERFORM 9999-ABEND-PROGRAM                                        
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens XREF-FILE (card cross-reference) for input.
      *   Random access by card number key.
      *   Abends on non-zero FILE STATUS.
       0200-CARDXREF-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT XREF-FILE                                                 
           IF  CARDXREF-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING CROSS REF FILE'                           
               MOVE CARDXREF-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens TRANTYPE-FILE (transaction type) for input.
      *   Random access by 2-byte type code key.
      *   Abends on non-zero FILE STATUS.
       0300-TRANTYPE-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT TRANTYPE-FILE                                             
           IF  TRANTYPE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING TRANSACTION TYPE FILE'                    
               MOVE TRANTYPE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens TRANCATG-FILE (transaction category) for
      *   input. Random access by composite key.
      *   Abends on non-zero FILE STATUS.
       0400-TRANCATG-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT TRANCATG-FILE                                             
           IF  TRANCATG-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING TRANSACTION CATG FILE'                    
               MOVE TRANCATG-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Opens DATE-PARMS-FILE for sequential input.
      *   Abends on non-zero FILE STATUS.
       0500-DATEPARM-OPEN.                                                      
           MOVE 8 TO APPL-RESULT.                                               
           OPEN INPUT DATE-PARMS-FILE                                           
           IF  DATEPARM-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR OPENING DATE PARM FILE'                           
               MOVE DATEPARM-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Reads card cross-reference by FD-XREF-CARD-NUM
      *   to resolve the account ID (XREF-ACCT-ID) for
      *   the current transaction. Abends on invalid key
      *   (card number not found in XREF file).
       1500-A-LOOKUP-XREF.                                                      
           READ XREF-FILE INTO CARD-XREF-RECORD                                 
              INVALID KEY                                                       
                 DISPLAY 'INVALID CARD NUMBER : '  FD-XREF-CARD-NUM             
                 MOVE 23 TO IO-STATUS                                           
                 PERFORM 9910-DISPLAY-IO-STATUS                                 
                 PERFORM 9999-ABEND-PROGRAM                                     
           END-READ                                                             
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Reads transaction type record by FD-TRAN-TYPE
      *   to get type description (TRAN-TYPE-DESC).
      *   Abends on invalid key (unknown type code).
       1500-B-LOOKUP-TRANTYPE.                                                  
           READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD                             
              INVALID KEY                                                       
                 DISPLAY 'INVALID TRANSACTION TYPE : '  FD-TRAN-TYPE            
                 MOVE 23 TO IO-STATUS                                           
                 PERFORM 9910-DISPLAY-IO-STATUS                                 
                 PERFORM 9999-ABEND-PROGRAM                                     
           END-READ                                                             
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Reads transaction category by composite key
      *   (type-cd + cat-cd) to get category description.
      *   Abends on invalid key (unknown category).
       1500-C-LOOKUP-TRANCATG.                                                  
           READ TRANCATG-FILE INTO TRAN-CAT-RECORD                              
              INVALID KEY                                                       
                 DISPLAY 'INVALID TRAN CATG KEY : '  FD-TRAN-CAT-KEY            
                 MOVE 23 TO IO-STATUS                                           
                 PERFORM 9910-DISPLAY-IO-STATUS                                 
                 PERFORM 9999-ABEND-PROGRAM                                     
           END-READ                                                             
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes the transaction master file.
      *   Abends on non-zero FILE STATUS.
       9000-TRANFILE-CLOSE.                                                     
           ADD 8 TO ZERO GIVING APPL-RESULT.                                    
           CLOSE TRANSACT-FILE                                                  
           IF TRANFILE-STATUS = '00'                                            
              SUBTRACT APPL-RESULT FROM APPL-RESULT                             
           ELSE                                                                 
              ADD 12 TO ZERO GIVING APPL-RESULT                                 
           END-IF                                                               
           IF APPL-AOK                                                          
              CONTINUE                                                          
           ELSE                                                                 
              DISPLAY 'ERROR CLOSING POSTED TRANSACTION FILE'                   
              MOVE TRANFILE-STATUS TO IO-STATUS                                 
              PERFORM 9910-DISPLAY-IO-STATUS                                    
              PERFORM 9999-ABEND-PROGRAM                                        
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes the report output file.
      *   Abends on non-zero FILE STATUS.
       9100-REPTFILE-CLOSE.                                                     
           ADD 8 TO ZERO GIVING APPL-RESULT.                                    
           CLOSE REPORT-FILE                                                    
           IF TRANREPT-STATUS = '00'                                            
              SUBTRACT APPL-RESULT FROM APPL-RESULT                             
           ELSE                                                                 
              ADD 12 TO ZERO GIVING APPL-RESULT                                 
           END-IF                                                               
           IF APPL-AOK                                                          
              CONTINUE                                                          
           ELSE                                                                 
              DISPLAY 'ERROR CLOSING REPORT FILE'                               
              MOVE TRANREPT-STATUS TO IO-STATUS                                 
              PERFORM 9910-DISPLAY-IO-STATUS                                    
              PERFORM 9999-ABEND-PROGRAM                                        
           END-IF                                                               
           EXIT.                                                                
                                                                                
      *---------------------------------------------------------------*         
      * Closes the card cross-reference file.
      *   Abends on non-zero FILE STATUS.
       9200-CARDXREF-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE XREF-FILE                                                      
           IF  CARDXREF-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING CROSS REF FILE'                           
               MOVE CARDXREF-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes the transaction type lookup file.
      *   Abends on non-zero FILE STATUS.
       9300-TRANTYPE-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE TRANTYPE-FILE                                                  
           IF  TRANTYPE-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING TRANSACTION TYPE FILE'                    
               MOVE TRANTYPE-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes the transaction category lookup file.
      *   Abends on non-zero FILE STATUS.
       9400-TRANCATG-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE TRANCATG-FILE                                                  
           IF  TRANCATG-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING TRANSACTION CATG FILE'                    
               MOVE TRANCATG-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
      *---------------------------------------------------------------*         
      * Closes the date parameter file.
      *   Abends on non-zero FILE STATUS.
       9500-DATEPARM-CLOSE.                                                     
           MOVE 8 TO APPL-RESULT.                                               
           CLOSE DATE-PARMS-FILE                                                
           IF  DATEPARM-STATUS = '00'                                           
               MOVE 0 TO APPL-RESULT                                            
           ELSE                                                                 
               MOVE 12 TO APPL-RESULT                                           
           END-IF                                                               
           IF  APPL-AOK                                                         
               CONTINUE                                                         
           ELSE                                                                 
               DISPLAY 'ERROR CLOSING DATE PARM FILE'                           
               MOVE DATEPARM-STATUS TO IO-STATUS                                
               PERFORM 9910-DISPLAY-IO-STATUS                                   
               PERFORM 9999-ABEND-PROGRAM                                       
           END-IF                                                               
           EXIT.                                                                
                                                                                
                                                                                
                                                                                
                                                                                
      * Terminates the program abnormally via CEE3ABD
      *   with abend code 999. Called on any I/O error.
       9999-ABEND-PROGRAM.                                                      
           DISPLAY 'ABENDING PROGRAM'                                           
           MOVE 0 TO TIMING                                                     
           MOVE 999 TO ABCODE                                                   
           CALL 'CEE3ABD'.                                                      
                                                                                
      *****************************************************************         
      * Displays FILE STATUS in human-readable NNNN
      *   format. Handles both numeric (00-99) and
      *   non-numeric (9x with binary byte) statuses.
       9910-DISPLAY-IO-STATUS.                                                  
           IF IO-STATUS NOT NUMERIC                                             
              OR IO-STAT1 = '9'                                                 
              MOVE IO-STAT1 TO IO-STATUS-04(1:1)                                
              MOVE 0 TO TWO-BYTES-BINARY                                        
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
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:32 CDT
      *
