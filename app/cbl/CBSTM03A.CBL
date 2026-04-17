       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CBSTM03A.
       AUTHOR.        AWS.
      ******************************************************************
      * Program     : CBSTM03A.CBL
      * Application : CardDemo
      * Type        : BATCH COBOL Program
      * Function    : Print Account Statements from Transaction data
      *               in two formats : 1/plain text and 2/HTML
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
      * This program is to create statement based on the data in
      * transaction file. The following features are excercised
      * to help create excercise modernization tooling
      ******************************************************************
      *  1. Mainframe Control block addressing
      *  2. Alter and GO TO statements
      *  3. COMP and COMP-3 variables
      *  4. 2 dimensional array
      *  5. Call to Subroutine
      ******************************************************************
      *
      * Batch processing program: Customer statement generation
      * (text + HTML).
      *
      * Generates account statements from transaction data in
      * two output formats:
      *   1. Plain text statements to STMTFILE (80-char lines)
      *   2. HTML statements to HTMLFILE (100-char lines)
      *
      * Performs 4-entity join:
      *   XREFFILE (card-to-acct) -> CUSTFILE (customer) ->
      *   ACCTFILE (account) -> TRNXFILE (transactions)
      *
      * Buffers transaction records in a 2D memory array
      * (51 cards x 10 transactions each) before writing.
      *
      * JCL wrapper: app/jcl/CREASTMT.JCL
      * Pipeline position: Runs after COMBTRAN (combine),
      *   produces final statement output.
      *
      * Subroutine: Calls CBSTM03B for all VSAM file I/O
      *   (open, sequential read, keyed read, close).
      *   See app/cbl/CBSTM03B.CBL
      *
      * Files accessed (via CBSTM03B subroutine):
      *   TRNXFILE  - Transaction master (KSDS, sequential)
      *   XREFFILE  - Card cross-reference (KSDS, sequential)
      *   CUSTFILE  - Customer master (KSDS, random/keyed)
      *   ACCTFILE  - Account master (KSDS, random/keyed)
      * Files accessed directly (OPEN OUTPUT in this program):
      *   STMTFILE  - Plain-text statement output (sequential)
      *   HTMLFILE  - HTML statement output (sequential)
      *
      * NOTE: Uses legacy COBOL constructs per lines 26-35
      *   comment block: mainframe control block addressing
      *   (TIOT/TCB/PSA), ALTER/GO TO flow control,
      *   COMP and COMP-3 variables, 2D arrays, and
      *   subroutine CALL to CBSTM03B.
      *
      * Copybooks used:
      *   COSTM01  - TRNX-RECORD reporting transaction layout
      *              See app/cpy/COSTM01.CPY
      *   CVACT03Y - CARD-XREF-RECORD (50-byte xref)
      *              See app/cpy/CVACT03Y.cpy
      *   CUSTREC  - CUSTOMER-RECORD (500-byte, alternate)
      *              See app/cpy/CUSTREC.cpy
      *   CVACT01Y - ACCOUNT-RECORD (300-byte)
      *              See app/cpy/CVACT01Y.cpy
      *
      *****************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
      * Statement text output - sequential file, 80-char records.
      * Contains formatted plain-text account statements.
           SELECT STMT-FILE ASSIGN TO STMTFILE.
      * Statement HTML output - sequential file, 100-char records.
      * Contains HTML-formatted account statements with table
      * layout and inline CSS styling.
           SELECT HTML-FILE ASSIGN TO HTMLFILE.
      * NOTE: TRNXFILE, XREFFILE, CUSTFILE, and ACCTFILE are
      * accessed indirectly via CALL to CBSTM03B subroutine.
      * They are not defined in this program's FILE-CONTROL.
      * See app/cbl/CBSTM03B.CBL for their SELECT definitions.
      *
       DATA DIVISION.
       FILE SECTION.
      * FD for plain-text statement output. Each record is one
      * 80-byte line of the formatted account statement.
       FD  STMT-FILE.
       01  FD-STMTFILE-REC         PIC X(80).
      * FD for HTML statement output. Each record is one
      * 100-byte line of the HTML document for the statement.
       FD  HTML-FILE.
       01  FD-HTMLFILE-REC         PIC X(100).

       WORKING-STORAGE SECTION.

      * TRNX-RECORD: Reporting transaction layout (350 bytes).
      * Contains card number, transaction ID, type code,
      * category, source, description, amount, merchant info,
      * and timestamps. See app/cpy/COSTM01.CPY
       COPY COSTM01.

      * CARD-XREF-RECORD: Card cross-reference record (50 bytes).
      * Links card number to customer ID and account ID.
      * See app/cpy/CVACT03Y.cpy
       COPY CVACT03Y.

      * CUSTOMER-RECORD: Customer entity record (500 bytes).
      * Contains customer ID, name, address, phone, SSN, DOB,
      * FICO score. NOTE: Uses CUSTREC.cpy (alternate layout)
      * rather than CVCUS01Y.cpy. See app/cpy/CUSTREC.cpy
       COPY CUSTREC.

      * ACCOUNT-RECORD: Account entity record (300 bytes).
      * Contains account ID, status, balances, credit limits,
      * dates, and group ID. See app/cpy/CVACT01Y.cpy
       COPY CVACT01Y.

      * Binary (COMP) counters and index variables for the
      * in-memory transaction table traversal.
       01  COMP-VARIABLES          COMP.
      * CR-CNT: Number of distinct cards loaded into table
           05  CR-CNT              PIC S9(4) VALUE 0.
      * TR-CNT: Transaction count for current card being loaded
           05  TR-CNT              PIC S9(4) VALUE 0.
      * CR-JMP: Card index used during statement generation
           05  CR-JMP              PIC S9(4) VALUE 0.
      * TR-JMP: Transaction index within current card
           05  TR-JMP              PIC S9(4) VALUE 0.
      * Packed-decimal (COMP-3) accumulator for statement totals.
       01  COMP3-VARIABLES         COMP-3.
      * WS-TOTAL-AMT: Running total of transaction amounts for
      * current card's statement (reset per card).
           05  WS-TOTAL-AMT        PIC S9(9)V99 VALUE 0.
      * Miscellaneous control and work fields.
       01  MISC-VARIABLES.
      * WS-FL-DD: Controls ALTER/GO TO dispatch in 0000-START.
      * Holds the DD name of the file to open next, or
      * 'READTRNX' to trigger the transaction read loop.
      * Initial value 'TRNXFILE' starts the open sequence.
           05  WS-FL-DD            PIC X(8) VALUE 'TRNXFILE'.
      * WS-TRN-AMT: Display-format copy of WS-TOTAL-AMT for
      * formatting into the statement total line.
           05  WS-TRN-AMT          PIC S9(9)V99 VALUE 0.
      * WS-SAVE-CARD: Tracks the previous card number to detect
      * card-break boundaries during transaction loading.
           05  WS-SAVE-CARD VALUE SPACES PIC X(16).
      * END-OF-FILE: EOF flag for XREFFILE sequential read.
      * 'N' = more records, 'Y' = end of file reached.
           05  END-OF-FILE         PIC X(01) VALUE 'N'.
      *
      * WS-M03B-AREA: Communication area passed to CBSTM03B
      * subroutine via CALL USING. Contains the DD name, the
      * requested operation, return code, optional key, and a
      * 1000-byte data buffer for record transfer.
       01  WS-M03B-AREA.
      * WS-M03B-DD: Target file DD name (TRNXFILE, XREFFILE,
      * CUSTFILE, or ACCTFILE).
           05  WS-M03B-DD          PIC X(08).
      * WS-M03B-OPER: Operation code with 88-level conditions.
           05  WS-M03B-OPER        PIC X(01).
             88  M03B-OPEN       VALUE 'O'.
             88  M03B-CLOSE      VALUE 'C'.
             88  M03B-READ       VALUE 'R'.
             88  M03B-READ-K     VALUE 'K'.
             88  M03B-WRITE      VALUE 'W'.
             88  M03B-REWRITE    VALUE 'Z'.
      * WS-M03B-RC: Return code from CBSTM03B ('00' = success,
      * '10' = end of file, other = error).
           05  WS-M03B-RC          PIC X(02).
      * WS-M03B-KEY: Key value for keyed reads ('K' operation).
           05  WS-M03B-KEY         PIC X(25).
      * WS-M03B-KEY-LN: Length of key in WS-M03B-KEY.
           05  WS-M03B-KEY-LN      PIC S9(4).
      * WS-M03B-FLDT: 1000-byte data buffer. On read, CBSTM03B
      * returns the record here; caller moves to a copybook
      * layout (TRNX-RECORD, CARD-XREF-RECORD, etc.).
           05  WS-M03B-FLDT        PIC X(1000).

      *
      * STATEMENT-LINES: Plain-text statement line buffers.
      * Each sub-item is one 80-byte output line. The program
      * populates fields (ST-NAME, ST-ACCT-ID, etc.) and
      * writes each line via WRITE FROM.
      * ST-LINE0:  Banner "START OF STATEMENT"
      * ST-LINE1:  Customer full name
      * ST-LINE2-4: Customer address (3 lines)
      * ST-LINE5:  Divider (dashes)
      * ST-LINE6:  "Basic Details" section header
      * ST-LINE7:  Account ID
      * ST-LINE8:  Current balance
      * ST-LINE9:  FICO score
      * ST-LINE10: Divider (dashes)
      * ST-LINE11: "TRANSACTION SUMMARY" header
      * ST-LINE12: Divider (dashes)
      * ST-LINE13: Column headers (Tran ID, Details, Amount)
      * ST-LINE14: Transaction detail line (reused per txn)
      * ST-LINE14A: Total expenditure line
      * ST-LINE15: Banner "END OF STATEMENT"
       01  STATEMENT-LINES.
           05  ST-LINE0.
               10  FILLER  VALUE ALL '*'                PIC X(31).
               10  FILLER  VALUE ALL 'START OF STATEMENT' PIC X(18).
               10  FILLER  VALUE ALL '*'                PIC X(31).
           05  ST-LINE1.
               10  ST-NAME                              PIC X(75).
               10  FILLER  VALUE SPACES                 PIC X(05).
           05  ST-LINE2.
               10  ST-ADD1                              PIC X(50).
               10  FILLER  VALUE SPACES                 PIC X(30).
           05  ST-LINE3.
               10  ST-ADD2                              PIC X(50).
               10  FILLER  VALUE SPACES                 PIC X(30).
           05  ST-LINE4.
               10  ST-ADD3                              PIC X(80).
           05  ST-LINE5.
               10  FILLER  VALUE ALL '-'                PIC X(80).
           05  ST-LINE6.
               10  FILLER  VALUE SPACES                 PIC X(33).
               10  FILLER  VALUE 'Basic Details'        PIC X(14).
               10  FILLER  VALUE SPACES                 PIC X(33).
           05  ST-LINE7.
               10  FILLER  VALUE 'Account ID         :' PIC X(20).
               10  ST-ACCT-ID                           PIC X(20).
               10  FILLER  VALUE SPACES                 PIC X(40).
           05  ST-LINE8.
               10  FILLER  VALUE 'Current Balance    :' PIC X(20).
               10  ST-CURR-BAL                          PIC 9(9).99-.
               10  FILLER  VALUE SPACES                 PIC X(07).
               10  FILLER  VALUE SPACES                 PIC X(40).
           05  ST-LINE9.
               10  FILLER  VALUE 'FICO Score         :' PIC X(20).
               10  ST-FICO-SCORE                        PIC X(20).
               10  FILLER  VALUE SPACES                 PIC X(40).
           05  ST-LINE10.
               10  FILLER  VALUE ALL '-'                PIC X(80).
           05  ST-LINE11.
               10  FILLER  VALUE SPACES                 PIC X(30).
               10  FILLER  VALUE 'TRANSACTION SUMMARY ' PIC X(20).
               10  FILLER  VALUE SPACES                 PIC X(30).
           05  ST-LINE12.
               10  FILLER  VALUE ALL '-'                PIC X(80).
           05  ST-LINE13.
               10  FILLER  VALUE 'Tran ID         '     PIC X(16).
               10  FILLER  VALUE 'Tran Details    '     PIC X(51).
               10  FILLER  VALUE '  Tran Amount'        PIC X(13).
           05  ST-LINE14.
               10  ST-TRANID                            PIC X(16).
               10  FILLER            VALUE ' '          PIC X(01).
               10  ST-TRANDT                            PIC X(49).
               10  FILLER            VALUE '$'          PIC X(01).
               10  ST-TRANAMT                           PIC Z(9).99-.
           05  ST-LINE14A.
               10  FILLER            VALUE 'Total EXP:' PIC X(10).
               10  FILLER            VALUE SPACES       PIC X(56).
               10  FILLER            VALUE '$'          PIC X(01).
               10  ST-TOTAL-TRAMT                       PIC Z(9).99-.
           05  ST-LINE15.
               10  FILLER  VALUE ALL '*'                PIC X(32).
               10  FILLER  VALUE ALL 'END OF STATEMENT' PIC X(16).
               10  FILLER  VALUE ALL '*'                PIC X(32).

      *
      * HTML-LINES: HTML statement markup line buffers.
      * HTML-FIXED-LN (100 bytes) is reused via 88-level
      * conditions to emit different HTML tags/lines.
      * Each SET <88-name> TO TRUE loads the corresponding
      * literal into HTML-FIXED-LN for the next WRITE.
      * Covers: DOCTYPE, html/head/body open/close, table
      * structure, bank branding, section headers, column
      * headers, and transaction detail row styling.
      * HTML-L11:  Account number heading (dynamic)
      * HTML-L23:  Customer name paragraph (dynamic)
      * HTML-ADDR-LN: Address line (dynamic, STRING built)
      * HTML-BSIC-LN: Basic details line (dynamic)
      * HTML-TRAN-LN: Transaction detail line (dynamic)
       01  HTML-LINES.
           05  HTML-FIXED-LN        PIC X(100).
             88  HTML-L01 VALUE '<!DOCTYPE html>'.
             88  HTML-L02 VALUE '<html lang="en">'.
             88  HTML-L03 VALUE '<head>'.
             88  HTML-L04 VALUE '<meta charset="utf-8">'.
             88  HTML-L05 VALUE '<title>HTML Table Layout</title>'.
             88  HTML-L06 VALUE '</head>'.
             88  HTML-L07 VALUE '<body style="margin:0px;">'.
             88  HTML-L08 VALUE '<table  align="center" frame="box" styl
      -             'e="width:70%; font:12px Segoe UI,sans-serif;">'.
             88  HTML-LTRS VALUE '<tr>'.
             88  HTML-LTRE VALUE '</tr>'.
             88  HTML-LTDS VALUE '<td>'.
             88  HTML-LTDE VALUE '</td>'.
             88  HTML-L10 VALUE '<td colspan="3" style="padding:0px 5px;
      -             'background-color:#1d1d96b3;">'.
             88  HTML-L15 VALUE '<td colspan="3" style="padding:0px 5px;
      -             'background-color:#FFAF33;">'.
             88  HTML-L16
               VALUE '<p style="font-size:16px">Bank of XYZ</p>'.
             88  HTML-L17
               VALUE '<p>410 Terry Ave N</p>'.
             88  HTML-L18
               VALUE '<p>Seattle WA 99999</p>'.
             88  HTML-L22-35
                          VALUE '<td colspan="3" style="padding:0px 5px;
      -              'background-color:#f2f2f2;">'.
             88  HTML-L30-42
                          VALUE '<td colspan="3" style="padding:0px 5px;
      -              'background-color:#33FFD1; text-align:center;">'.
             88  HTML-L31
               VALUE '<p style="font-size:16px">Basic Details</p>'.
             88  HTML-L43
              VALUE '<p style="font-size:16px">Transaction Summary</p>'.
             88  HTML-L47
               VALUE '<td style="width:25%; padding:0px 5px; background-
      -              'color:#33FF5E; text-align:left;">'.
             88  HTML-L48
               VALUE '<p style="font-size:16px">Tran ID</p>'.
             88  HTML-L50
               VALUE '<td style="width:55%; padding:0px 5px; background-
      -              'color:#33FF5E; text-align:left;">'.
             88  HTML-L51
               VALUE '<p style="font-size:16px">Tran Details</p>'.
             88  HTML-L53
               VALUE '<td style="width:20%; padding:0px 5px; background-
      -              'color:#33FF5E; text-align:right;">'.
             88  HTML-L54
               VALUE '<p style="font-size:16px">Amount</p>'.
             88  HTML-L58
               VALUE '<td style="width:25%; padding:0px 5px; background-
      -              'color:#f2f2f2; text-align:left;">'.
             88  HTML-L61
               VALUE '<td style="width:55%; padding:0px 5px; background-
      -              'color:#f2f2f2; text-align:left;">'.
             88  HTML-L64
               VALUE '<td style="width:20%; padding:0px 5px; background-
      -              'color:#f2f2f2; text-align:right;">'.
             88  HTML-L75
               VALUE '<h3>End of Statement</h3>'.
             88  HTML-L78 VALUE '</table>'.
             88  HTML-L79 VALUE '</body>'.
             88  HTML-L80 VALUE '</html>'.
           05  HTML-L11.
               10  FILLER   PIC X(34)
                          VALUE '<h3>Statement for Account Number: '.
               10  L11-ACCT PIC X(20).
               10  FILLER   PIC X(05) VALUE '</h3>'.
           05  HTML-L23.
               10  FILLER   PIC X(26)
                          VALUE '<p style="font-size:16px">'.
               10  L23-NAME PIC X(50).
           05  HTML-ADDR-LN PIC X(100).
           05  HTML-BSIC-LN PIC X(100).
           05  HTML-TRAN-LN PIC X(100).

      *
      * WS-TRNX-TABLE: In-memory 2D transaction buffer.
      * Stores up to 51 distinct cards, each with up to 10
      * transactions. Loaded during 8500-READTRNX-READ before
      * statement generation begins. Each entry holds the card
      * number (16 bytes), transaction ID (16 bytes), and the
      * remaining transaction fields (318 bytes, maps to
      * TRNX-REST in COSTM01 copybook layout).
       01  WS-TRNX-TABLE.
           05  WS-CARD-TBL OCCURS 51 TIMES.
               10  WS-CARD-NUM                          PIC X(16).
               10  WS-TRAN-TBL OCCURS 10 TIMES.
                   15  WS-TRAN-NUM                      PIC X(16).
                   15  WS-TRAN-REST                     PIC X(318).
      * WS-TRN-TBL-CNTR: Parallel array of transaction counts,
      * one per card slot. WS-TRCT(n) holds the number of
      * transactions stored in WS-CARD-TBL(n).
       01  WS-TRN-TBL-CNTR.
           05  WS-TRN-TBL-CTR OCCURS 51 TIMES.
               10  WS-TRCT               PIC S9(4) COMP.

      *---------------------------------------------------------------*
      * z/OS control block addressing fields (legacy construct).
      * PSAPTR: Pointer to the Prefixed Save Area (address 0).
      * BUMP-TIOT / TIOT-INDEX: Used for pointer arithmetic to
      * walk TIOT (Task I/O Table) entries. REDEFINES lets the
      * numeric field be used as a POINTER for SET ADDRESS.
      *---------------------------------------------------------------*
       01  PSAPTR                  POINTER.
       01  BUMP-TIOT               PIC S9(08) BINARY VALUE ZERO.
       01  TIOT-INDEX              REDEFINES BUMP-TIOT POINTER.

      *---------------------------------------------------------------*
      * LINKAGE SECTION: z/OS control block overlays.
      * These structures map mainframe system areas accessed
      * via pointer chaining: PSA -> TCB -> TIOT.
      * PSA-BLOCK:  Prefixed Save Area (offset 536 -> TCB ptr)
      * TCB-BLOCK:  Task Control Block (offset 12 -> TIOT ptr)
      * TIOT-BLOCK: Task I/O Table header (job/step/proc names)
      * TIOT-ENTRY: Individual DD name entry in the TIOT
      * This is a legacy pattern for displaying the JCL job
      * name and enumerating DD allocations at runtime.
      *---------------------------------------------------------------*
       LINKAGE SECTION.
       01  ALIGN-PSA        PIC 9(16) BINARY.
       01  PSA-BLOCK.
           05  FILLER       PIC X(536).
           05  TCB-POINT    POINTER.
       01  TCB-BLOCK.
           05  FILLER       PIC X(12).
           05  TIOT-POINT   POINTER.
       01  TIOT-BLOCK.
           05  TIOTNJOB     PIC X(08).
           05  TIOTJSTP     PIC X(08).
           05  TIOTPSTP     PIC X(08).
       01  TIOT-ENTRY.
           05  TIOT-SEG.
               10  TIO-LEN  PIC X(01).
               10  FILLER   PIC X(03).
               10  TIOCDDNM PIC X(08).
               10  FILLER   PIC X(05).
               10  UCB-ADDR PIC X(03).
                   88 NULL-UCB    VALUES LOW-VALUES.
           05  FILLER       PIC X(04).
             88  END-OF-TIOT      VALUE LOW-VALUES.
      *****************************************************************
       PROCEDURE DIVISION.
      *****************************************************************
      *    Check Unit Control blocks (legacy TIOT inspection)         *
      *    Chains through z/OS system areas: PSA -> TCB -> TIOT       *
      *    to display the JCL job name, step name, and all DD         *
      *    allocations. This is a diagnostic feature preserved        *
      *    for modernization testing.                                 *
      *****************************************************************
      * Address PSA (Prefixed Save Area) at location zero,
      * then chain to TCB (Task Control Block) and TIOT
      * (Task I/O Table) to extract job identity.
           SET ADDRESS OF PSA-BLOCK   TO PSAPTR.
           SET ADDRESS OF TCB-BLOCK   TO TCB-POINT.
           SET ADDRESS OF TIOT-BLOCK  TO TIOT-POINT.
           SET TIOT-INDEX             TO TIOT-POINT.
           DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP.

      * Advance past TIOT header to the first DD entry.
           COMPUTE BUMP-TIOT = BUMP-TIOT + LENGTH OF TIOT-BLOCK.
           SET ADDRESS OF TIOT-ENTRY  TO TIOT-INDEX.

      * Walk TIOT entries, displaying each DD name and whether
      * it has a valid UCB (Unit Control Block) association.
           DISPLAY 'DD Names from TIOT: '.
           PERFORM UNTIL END-OF-TIOT
                      OR TIO-LEN = LOW-VALUES
               IF NOT NULL-UCB
                   DISPLAY ': ' TIOCDDNM ' -- valid UCB'
               ELSE
                   DISPLAY ': ' TIOCDDNM ' --  null UCB'
               END-IF
               COMPUTE BUMP-TIOT = BUMP-TIOT + LENGTH OF TIOT-SEG
               SET ADDRESS OF TIOT-ENTRY TO TIOT-INDEX
           END-PERFORM.

      * Display the last TIOT entry after the loop exits.
           IF NOT NULL-UCB
               DISPLAY ': ' TIOCDDNM ' -- valid UCB'
           ELSE
               DISPLAY ': ' TIOCDDNM ' -- null  UCB'
           END-IF.

      * Open both output files for writing and clear the
      * in-memory transaction table and counters.
           OPEN OUTPUT STMT-FILE HTML-FILE.
           INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR.

      *---------------------------------------------------------------*
      * 0000-START: Central dispatch using ALTER/GO TO (legacy).
      * WS-FL-DD controls which file-open paragraph runs next.
      * The ALTER statement dynamically changes the target of
      * 8100-FILE-OPEN's GO TO at runtime. This implements a
      * sequential open chain: TRNXFILE -> XREFFILE -> CUSTFILE
      * -> ACCTFILE, then falls into 1000-MAINLINE.
      * 'READTRNX' triggers the transaction read loop instead.
      * This is an intentional legacy pattern preserved for
      * modernization testing (see header lines 26-35).
      *---------------------------------------------------------------*
       0000-START.

      * Dispatch based on WS-FL-DD value: alter the GO TO
      * target in 8100-FILE-OPEN then jump to it, or go
      * directly to the transaction read loop.
           EVALUATE WS-FL-DD
             WHEN 'TRNXFILE'
               ALTER 8100-FILE-OPEN TO PROCEED TO 8100-TRNXFILE-OPEN
               GO TO 8100-FILE-OPEN
             WHEN 'XREFFILE'
               ALTER 8100-FILE-OPEN TO PROCEED TO 8200-XREFFILE-OPEN
               GO TO 8100-FILE-OPEN
             WHEN 'CUSTFILE'
               ALTER 8100-FILE-OPEN TO PROCEED TO 8300-CUSTFILE-OPEN
               GO TO 8100-FILE-OPEN
             WHEN 'ACCTFILE'
               ALTER 8100-FILE-OPEN TO PROCEED TO 8400-ACCTFILE-OPEN
               GO TO 8100-FILE-OPEN
             WHEN 'READTRNX'
               GO TO 8500-READTRNX-READ
             WHEN OTHER
               GO TO 9999-GOBACK.

      *---------------------------------------------------------------*
      * 1000-MAINLINE: Main processing loop.
      * Iterates through XREFFILE records sequentially. For
      * each cross-reference record (card-to-customer-to-acct):
      *   1. Calls 1000-XREFFILE-GET-NEXT (sequential read)
      *   2. Calls 2000-CUSTFILE-GET (keyed read by CUST-ID)
      *   3. Calls 3000-ACCTFILE-GET (keyed read by ACCT-ID)
      *   4. Calls 5000-CREATE-STATEMENT (text + HTML output)
      *   5. Calls 4000-TRNXFILE-GET (scan in-memory table)
      * Loops until XREFFILE returns EOF ('Y' in END-OF-FILE).
      * After loop, closes all four files via CBSTM03B, then
      * closes STMT-FILE and HTML-FILE directly.
      *---------------------------------------------------------------*
       1000-MAINLINE.
           PERFORM UNTIL END-OF-FILE = 'Y'
               IF  END-OF-FILE = 'N'
                   PERFORM 1000-XREFFILE-GET-NEXT
                   IF  END-OF-FILE = 'N'
                       PERFORM 2000-CUSTFILE-GET
                       PERFORM 3000-ACCTFILE-GET
                       PERFORM 5000-CREATE-STATEMENT
                       MOVE 1 TO CR-JMP
                       MOVE ZERO TO WS-TOTAL-AMT
                       PERFORM 4000-TRNXFILE-GET
                   END-IF
               END-IF
           END-PERFORM.

      * Close all four VSAM files via CBSTM03B subroutine.
           PERFORM 9100-TRNXFILE-CLOSE.

           PERFORM 9200-XREFFILE-CLOSE.

           PERFORM 9300-CUSTFILE-CLOSE.

           PERFORM 9400-ACCTFILE-CLOSE.

      * Close the two directly-opened output files.
           CLOSE STMT-FILE HTML-FILE.

      *---------------------------------------------------------------*
      * 9999-GOBACK: Normal program termination. Returns
      * control to the calling JCL step (CREASTMT.JCL).
      *---------------------------------------------------------------*
       9999-GOBACK.
           GOBACK.

      *---------------------------------------------------------------*
      * 1000-XREFFILE-GET-NEXT: Reads the next cross-reference
      * record sequentially from XREFFILE via CBSTM03B ('R').
      * On success ('00'), moves the 1000-byte buffer into the
      * CARD-XREF-RECORD copybook layout (50 bytes used).
      * On EOF ('10'), sets END-OF-FILE = 'Y' to stop the
      * main loop. On any other return code, abends.
      *---------------------------------------------------------------*
       1000-XREFFILE-GET-NEXT.

      * Request sequential read from XREFFILE via CBSTM03B.
           MOVE 'XREFFILE' TO WS-M03B-DD.
           SET M03B-READ TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           MOVE SPACES TO WS-M03B-FLDT.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

      * Evaluate return code: '00' = success, '10' = EOF.
           EVALUATE WS-M03B-RC
             WHEN '00'
               CONTINUE
             WHEN '10'
               MOVE 'Y' TO END-OF-FILE
             WHEN OTHER
               DISPLAY 'ERROR READING XREFFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-EVALUATE.

      * Map the buffer to CARD-XREF-RECORD (CVACT03Y layout):
      * XREF-CARD-NUM, XREF-CUST-ID, XREF-ACCT-ID.
           MOVE WS-M03B-FLDT TO CARD-XREF-RECORD.

           EXIT.

      *---------------------------------------------------------------*
      * 2000-CUSTFILE-GET: Reads a customer record by keyed
      * lookup from CUSTFILE via CBSTM03B ('K' = keyed read).
      * Uses XREF-CUST-ID from the current cross-reference
      * record as the lookup key. On success, maps the buffer
      * to CUSTOMER-RECORD (CUSTREC.cpy layout, 500 bytes).
      * Provides name and address for statement header.
      *---------------------------------------------------------------*
       2000-CUSTFILE-GET.

      * Set up keyed read: DD = CUSTFILE, key = XREF-CUST-ID
      * (9 bytes), operation = 'K' (keyed random read).
           MOVE 'CUSTFILE' TO WS-M03B-DD.
           SET M03B-READ-K TO TRUE.
           MOVE XREF-CUST-ID TO WS-M03B-KEY.
           MOVE ZERO TO WS-M03B-KEY-LN.
           COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID.
           MOVE ZERO TO WS-M03B-RC.
           MOVE SPACES TO WS-M03B-FLDT.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

      * Any non-zero return code is treated as a fatal error.
           EVALUATE WS-M03B-RC
             WHEN '00'
               CONTINUE
             WHEN OTHER
               DISPLAY 'ERROR READING CUSTFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-EVALUATE.

      * Map buffer to CUSTOMER-RECORD: CUST-FIRST-NAME,
      * CUST-LAST-NAME, CUST-ADDR-*, CUST-FICO-CREDIT-SCORE.
           MOVE WS-M03B-FLDT TO CUSTOMER-RECORD.

           EXIT.

      *---------------------------------------------------------------*
      * 3000-ACCTFILE-GET: Reads an account record by keyed
      * lookup from ACCTFILE via CBSTM03B ('K' = keyed read).
      * Uses XREF-ACCT-ID from the current cross-reference
      * record as the lookup key. On success, maps the buffer
      * to ACCOUNT-RECORD (CVACT01Y.cpy layout, 300 bytes).
      * Provides account ID, current balance, and credit limit
      * for the statement.
      *---------------------------------------------------------------*
       3000-ACCTFILE-GET.

      * Set up keyed read: DD = ACCTFILE, key = XREF-ACCT-ID
      * (11 bytes), operation = 'K' (keyed random read).
           MOVE 'ACCTFILE' TO WS-M03B-DD.
           SET M03B-READ-K TO TRUE.
           MOVE XREF-ACCT-ID TO WS-M03B-KEY.
           MOVE ZERO TO WS-M03B-KEY-LN.
           COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-ACCT-ID.
           MOVE ZERO TO WS-M03B-RC.
           MOVE SPACES TO WS-M03B-FLDT.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

      * Any non-zero return code is treated as a fatal error.
           EVALUATE WS-M03B-RC
             WHEN '00'
               CONTINUE
             WHEN OTHER
               DISPLAY 'ERROR READING ACCTFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-EVALUATE.

      * Map buffer to ACCOUNT-RECORD: ACCT-ID, ACCT-CURR-BAL,
      * ACCT-CREDIT-LIMIT, etc.
           MOVE WS-M03B-FLDT TO ACCOUNT-RECORD.

           EXIT.

      *---------------------------------------------------------------*
      * 4000-TRNXFILE-GET: Scans the in-memory transaction table
      * for transactions matching the current XREF-CARD-NUM.
      * For each matching card slot, iterates through its
      * transactions, writes each via 6000-WRITE-TRANS, and
      * accumulates the total amount. After all transactions
      * are written, writes the plain-text footer (divider,
      * total line, end-of-statement banner) and the HTML
      * footer (end-of-statement heading, close table/body/html
      * tags).
      *---------------------------------------------------------------*
       4000-TRNXFILE-GET.
      * Outer loop: scan card slots in the 2D table.
      * Inner loop: iterate transactions for matching card.
           PERFORM VARYING CR-JMP FROM 1 BY 1
             UNTIL CR-JMP > CR-CNT
             OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM)
               IF XREF-CARD-NUM = WS-CARD-NUM (CR-JMP)
                   MOVE WS-CARD-NUM (CR-JMP) TO TRNX-CARD-NUM
                   PERFORM VARYING TR-JMP FROM 1 BY 1
                     UNTIL (TR-JMP > WS-TRCT (CR-JMP))
      * Reconstruct TRNX-RECORD fields from table entries.
                       MOVE WS-TRAN-NUM (CR-JMP, TR-JMP)
                         TO TRNX-ID
                       MOVE WS-TRAN-REST (CR-JMP, TR-JMP)
                         TO TRNX-REST
      * Write one transaction detail line (text + HTML).
                       PERFORM 6000-WRITE-TRANS
      * Accumulate running total (COMP-3 for precision).
                       ADD TRNX-AMT TO WS-TOTAL-AMT
                   END-PERFORM
               END-IF
           END-PERFORM.
      * Format and write the total expenditure line.
           MOVE WS-TOTAL-AMT TO WS-TRN-AMT.
           MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT.
      * Write plain-text footer: divider, total, end banner.
           WRITE FD-STMTFILE-REC FROM ST-LINE12.
           WRITE FD-STMTFILE-REC FROM ST-LINE14A.
           WRITE FD-STMTFILE-REC FROM ST-LINE15.

      * Write HTML footer: end-of-statement row, close table,
      * close body and html tags.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L10 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L75 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L78 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L79 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L80 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

           EXIT.
      *---------------------------------------------------------------*
      * 5000-CREATE-STATEMENT: Builds and writes the header
      * section of both the plain-text and HTML statements for
      * the current cross-reference record's card.
      * Assembles customer name (STRING concatenation of first,
      * middle, last), address lines, and account details
      * (account ID, current balance, FICO score) from the
      * 4-entity join results (CUSTOMER-RECORD + ACCOUNT-RECORD).
      * Writes the "START OF STATEMENT" banner, customer info,
      * basic details section, and column headers for the
      * transaction summary that follows in 4000-TRNXFILE-GET.
      *---------------------------------------------------------------*
       5000-CREATE-STATEMENT.
      * Clear all statement line buffers for the new card.
           INITIALIZE STATEMENT-LINES.
      * Write the text banner and begin the HTML document.
           WRITE FD-STMTFILE-REC FROM ST-LINE0.
           PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT.
      * Build customer full name via STRING concatenation,
      * trimming trailing spaces from each name component.
           STRING CUST-FIRST-NAME DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  CUST-MIDDLE-NAME DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  CUST-LAST-NAME DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  INTO ST-NAME
           END-STRING.
      * Populate address lines from CUSTOMER-RECORD fields.
           MOVE CUST-ADDR-LINE-1 TO ST-ADD1.
           MOVE CUST-ADDR-LINE-2 TO ST-ADD2.
      * Build city/state/country/ZIP on one line via STRING.
           STRING CUST-ADDR-LINE-3 DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  CUST-ADDR-STATE-CD DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  CUST-ADDR-COUNTRY-CD DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  CUST-ADDR-ZIP DELIMITED BY ' '
                  ' ' DELIMITED BY SIZE
                  INTO ST-ADD3
           END-STRING.

      * Populate account details from ACCOUNT-RECORD.
           MOVE ACCT-ID TO ST-ACCT-ID.
           MOVE ACCT-CURR-BAL TO ST-CURR-BAL.
           MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE.
      * Write the HTML name/address/basic-details section.
           PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT.

      * Write the plain-text statement header lines in order:
      * name, address (3 lines), divider, "Basic Details",
      * divider, account ID, balance, FICO, divider,
      * "TRANSACTION SUMMARY", divider, column headers, divider.
           WRITE FD-STMTFILE-REC FROM ST-LINE1.
           WRITE FD-STMTFILE-REC FROM ST-LINE2.
           WRITE FD-STMTFILE-REC FROM ST-LINE3.
           WRITE FD-STMTFILE-REC FROM ST-LINE4.
           WRITE FD-STMTFILE-REC FROM ST-LINE5.
           WRITE FD-STMTFILE-REC FROM ST-LINE6.
           WRITE FD-STMTFILE-REC FROM ST-LINE5.
           WRITE FD-STMTFILE-REC FROM ST-LINE7.
           WRITE FD-STMTFILE-REC FROM ST-LINE8.
           WRITE FD-STMTFILE-REC FROM ST-LINE9.
           WRITE FD-STMTFILE-REC FROM ST-LINE10.
           WRITE FD-STMTFILE-REC FROM ST-LINE11.
           WRITE FD-STMTFILE-REC FROM ST-LINE12.
           WRITE FD-STMTFILE-REC FROM ST-LINE13.
           WRITE FD-STMTFILE-REC FROM ST-LINE12.

           EXIT.

      *---------------------------------------------------------------*
      * 5100-WRITE-HTML-HEADER: Writes the HTML document
      * preamble for one card's statement:
      *   - DOCTYPE, html, head (charset, title), body tags
      *   - Main table with 70% width, centered layout
      *   - Header row with account number heading
      *   - Bank branding row (Bank of XYZ, address)
      *   - Opens the customer details section (light gray bg)
      * Each line is emitted by setting an 88-level condition
      * on HTML-FIXED-LN, then writing the record.
      *---------------------------------------------------------------*
       5100-WRITE-HTML-HEADER.

      * Emit HTML document structure: DOCTYPE through <body>.
           SET HTML-L01 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L02 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L03 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L04 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L05 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L06 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L07 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L08 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L10 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Write account number heading (dynamic field L11-ACCT).
           MOVE ACCT-ID TO L11-ACCT.
           WRITE FD-HTMLFILE-REC FROM HTML-L11.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
      * Write bank branding row: "Bank of XYZ" with address.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L15 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L16 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L17 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L18 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
      * Open the customer details section (light gray bg).
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L22-35 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Exit point for HTML header write.
       5100-EXIT.
           EXIT.

      *---------------------------------------------------------------*
      * 5200-WRITE-HTML-NMADBS: Writes the HTML customer name,
      * address, and account basic details section. Uses STRING
      * to build dynamic HTML paragraphs with inline styles.
      * Then emits the "Basic Details" section header, account
      * ID, current balance, FICO score rows, and the
      * "Transaction Summary" header with column headings
      * (Tran ID, Tran Details, Amount) for the detail rows
      * that follow from 6000-WRITE-TRANS.
      *---------------------------------------------------------------*
       5200-WRITE-HTML-NMADBS.

      * Build customer name paragraph with inline font style.
           MOVE ST-NAME TO L23-NAME.
           MOVE SPACES TO FD-HTMLFILE-REC
           STRING '<p style="font-size:16px">' DELIMITED BY '*'
                  L23-NAME DELIMITED BY '  '
                  '  ' DELIMITED BY SIZE
                  '</p>' DELIMITED BY '*'
                  INTO FD-HTMLFILE-REC
           END-STRING.
           WRITE FD-HTMLFILE-REC.
      * Build and write HTML address lines (3 paragraphs).
           MOVE SPACES TO HTML-ADDR-LN.
           STRING '<p>' DELIMITED BY '*'
                  ST-ADD1 DELIMITED BY '  '
                  '  ' DELIMITED BY SIZE
                  '</p>' DELIMITED BY '*'
                  INTO HTML-ADDR-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN.
           MOVE SPACES TO HTML-ADDR-LN.
           STRING '<p>' DELIMITED BY '*'
                  ST-ADD2 DELIMITED BY '  '
                  '  ' DELIMITED BY SIZE
                  '</p>' DELIMITED BY '*'
                  INTO HTML-ADDR-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN.
           MOVE SPACES TO HTML-ADDR-LN.
           STRING '<p>' DELIMITED BY '*'
                  ST-ADD3 DELIMITED BY '  '
                  '  ' DELIMITED BY SIZE
                  '</p>' DELIMITED BY '*'
                  INTO HTML-ADDR-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN.

      * Close customer details section and open "Basic Details"
      * section (teal background, centered heading).
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L30-42 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L31 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
      * Open account detail row (light gray background).
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L22-35 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Build and write account details: ID, balance, FICO.
           MOVE SPACES TO HTML-BSIC-LN.
           STRING '<p>Account ID         : ' DELIMITED BY '*'
                  ST-ACCT-ID DELIMITED BY '*'
                  '</p>' DELIMITED BY '*'
                  INTO HTML-BSIC-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-BSIC-LN.
           MOVE SPACES TO HTML-BSIC-LN.
           STRING '<p>Current Balance    : ' DELIMITED BY '*'
                  ST-CURR-BAL DELIMITED BY '*'
                  '</p>' DELIMITED BY '*'
                  INTO HTML-BSIC-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-BSIC-LN.
           MOVE SPACES TO HTML-BSIC-LN.
           STRING '<p>FICO Score         : ' DELIMITED BY '*'
                  ST-FICO-SCORE DELIMITED BY '*'
                  '</p>' DELIMITED BY '*'
                  INTO HTML-BSIC-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-BSIC-LN.
      * Close basic details and write "Transaction Summary"
      * section header (teal background, centered).
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L30-42 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L43 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
      * Write transaction column headers: Tran ID (25%),
      * Tran Details (55%), Amount (20%) with green background.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L47 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L48 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L50 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L51 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L53 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-L54 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Exit point for HTML name/address/basic details.
       5200-EXIT.
           EXIT.

      *---------------------------------------------------------------*
      * 6000-WRITE-TRANS: Writes one transaction detail line to
      * both the plain-text and HTML statement files.
      * For text: populates ST-LINE14 fields (transaction ID,
      *   description, dollar amount) and writes one 80-byte
      *   record.
      * For HTML: builds a 3-column table row with transaction
      *   ID (25%, light gray), description (55%), and amount
      *   (20%, right-aligned), each wrapped in <p> tags.
      * Called from 4000-TRNXFILE-GET for each transaction
      * in the current card's slot in the in-memory table.
      *---------------------------------------------------------------*
       6000-WRITE-TRANS.
      * Populate plain-text transaction detail fields.
           MOVE TRNX-ID TO ST-TRANID.
           MOVE TRNX-DESC TO ST-TRANDT.
           MOVE TRNX-AMT TO ST-TRANAMT.
      * Write one plain-text transaction detail line.
           WRITE FD-STMTFILE-REC FROM ST-LINE14.

      * Begin HTML table row for this transaction.
           SET HTML-LTRS TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Column 1: Transaction ID (25% width, light gray bg).
           SET HTML-L58 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           MOVE SPACES TO HTML-TRAN-LN.
           STRING '<p>' DELIMITED BY '*'
                  ST-TRANID DELIMITED BY '*'
                  '</p>' DELIMITED BY '*'
                  INTO HTML-TRAN-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-TRAN-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Column 2: Transaction description (55% width).
           SET HTML-L61 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           MOVE SPACES TO HTML-TRAN-LN.
           STRING '<p>' DELIMITED BY '*'
                  ST-TRANDT DELIMITED BY '*'
                  '</p>' DELIMITED BY '*'
                  INTO HTML-TRAN-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-TRAN-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Column 3: Transaction amount (20% width, right-align).
           SET HTML-L64 TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.
           MOVE SPACES TO HTML-TRAN-LN.
           STRING '<p>' DELIMITED BY '*'
                  ST-TRANAMT DELIMITED BY '*'
                  '</p>' DELIMITED BY '*'
                  INTO HTML-TRAN-LN
           END-STRING.
           WRITE FD-HTMLFILE-REC FROM HTML-TRAN-LN.
           SET HTML-LTDE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

      * Close the HTML table row.
           SET HTML-LTRE TO TRUE.
           WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN.

           EXIT.

      *---------------------------------------------------------------*
      * 8100-FILE-OPEN: ALTER target paragraph (legacy pattern).
      * The GO TO destination is changed at runtime by ALTER
      * statements in 0000-START. Initial target is
      * 8100-TRNXFILE-OPEN; subsequent ALTERs redirect to
      * 8200, 8300, 8400 as each file is opened in sequence.
      *---------------------------------------------------------------*
       8100-FILE-OPEN.
           GO TO 8100-TRNXFILE-OPEN
           .

      *---------------------------------------------------------------*
      * 8100-TRNXFILE-OPEN: Opens TRNXFILE via CBSTM03B ('O'),
      * reads the first transaction record, initializes the
      * in-memory table counters, and redirects to 0000-START
      * with WS-FL-DD = 'READTRNX' to begin the transaction
      * loading loop. Accepts return codes '00' and '04'.
      *---------------------------------------------------------------*
       8100-TRNXFILE-OPEN.
      * Open TRNXFILE (indexed sequential) via CBSTM03B.
           MOVE 'TRNXFILE' TO WS-M03B-DD.
           SET M03B-OPEN TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING TRNXFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

      * Read the first transaction record to prime the loop.
           SET M03B-READ TO TRUE.
           MOVE SPACES TO WS-M03B-FLDT.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR READING TRNXFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

      * Map first record and initialize table loading state.
           MOVE WS-M03B-FLDT TO TRNX-RECORD.
           MOVE TRNX-CARD-NUM TO WS-SAVE-CARD.
           MOVE 1 TO CR-CNT.
           MOVE 0 TO TR-CNT.
      * Set dispatch to 'READTRNX' and loop back to 0000-START
      * to continue loading transactions into the table.
           MOVE 'READTRNX' TO WS-FL-DD.
           GO TO 0000-START.
           EXIT.

      *---------------------------------------------------------------*
      * 8200-XREFFILE-OPEN: Opens XREFFILE (card-to-account
      * cross-reference, indexed sequential) via CBSTM03B.
      * After successful open, chains to CUSTFILE open by
      * setting WS-FL-DD and jumping back to 0000-START.
      * This is the second file in the ALTER open sequence:
      * TRNXFILE -> XREFFILE -> CUSTFILE -> ACCTFILE.
      *---------------------------------------------------------------*
       8200-XREFFILE-OPEN.
      * Open XREFFILE via CBSTM03B subroutine.
           MOVE 'XREFFILE' TO WS-M03B-DD.
           SET M03B-OPEN TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING XREFFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

      * Chain to next file: set dispatch to CUSTFILE and
      * return to 0000-START for the next ALTER target.
           MOVE 'CUSTFILE' TO WS-FL-DD.
           GO TO 0000-START.
           EXIT.
      *---------------------------------------------------------------*
      * 8300-CUSTFILE-OPEN: Opens CUSTFILE (customer master,
      * indexed random access) via CBSTM03B. After successful
      * open, chains to ACCTFILE open by setting WS-FL-DD
      * and jumping back to 0000-START. Third file in the
      * ALTER open sequence.
      *---------------------------------------------------------------*
       8300-CUSTFILE-OPEN.
      * Open CUSTFILE via CBSTM03B subroutine.
           MOVE 'CUSTFILE' TO WS-M03B-DD.
           SET M03B-OPEN TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING CUSTFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

      * Chain to next file: set dispatch to ACCTFILE and
      * return to 0000-START for the next ALTER target.
           MOVE 'ACCTFILE' TO WS-FL-DD.
           GO TO 0000-START.
           EXIT.
      *---------------------------------------------------------------*
      * 8400-ACCTFILE-OPEN: Opens ACCTFILE (account master,
      * indexed random access) via CBSTM03B. This is the
      * fourth and final file in the ALTER open sequence.
      * After successful open, falls through directly to
      * 1000-MAINLINE to begin the main processing loop
      * (no 0000-START dispatch needed — all files are open).
      *---------------------------------------------------------------*
       8400-ACCTFILE-OPEN.
      * Open ACCTFILE via CBSTM03B subroutine.
           MOVE 'ACCTFILE' TO WS-M03B-DD.
           SET M03B-OPEN TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR OPENING ACCTFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

      * All four VSAM files are now open. Jump directly to
      * the main processing loop (bypasses 0000-START).
           GO TO 1000-MAINLINE.
           EXIT.
      *---------------------------------------------------------------*
      * 8500-READTRNX-READ: Recursive GO TO loop that reads
      * all transactions from TRNXFILE into the 2D in-memory
      * table (WS-TRNX-TABLE). Groups records by card number
      * (up to 51 cards, 10 transactions each). Detects card
      * breaks via WS-SAVE-CARD comparison. On end-of-file
      * (RC '10'), exits to 8599-EXIT. On success (RC '00'),
      * loops back to self via GO TO (legacy recursion).
      *---------------------------------------------------------------*
       8500-READTRNX-READ.
      * Card-break detection: if same card, increment the
      * transaction counter; otherwise finalize the previous
      * card slot count and start a new card slot.
           IF WS-SAVE-CARD = TRNX-CARD-NUM
               ADD 1 TO TR-CNT
           ELSE
               MOVE TR-CNT TO WS-TRCT (CR-CNT)
               ADD 1 TO CR-CNT
               MOVE 1 TO TR-CNT
           END-IF.

      * Store current transaction in the 2D table at
      * position [CR-CNT, TR-CNT] and update save-card.
           MOVE TRNX-CARD-NUM TO WS-CARD-NUM (CR-CNT).
           MOVE TRNX-ID TO WS-TRAN-NUM (CR-CNT, TR-CNT).
           MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT).
           MOVE TRNX-CARD-NUM TO WS-SAVE-CARD.

      * Read next transaction via CBSTM03B sequential read.
           MOVE 'TRNXFILE' TO WS-M03B-DD.
           SET M03B-READ TO TRUE.
           MOVE SPACES TO WS-M03B-FLDT.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

      * Evaluate return code: '00' = success (loop back),
      * '10' = end-of-file (exit), other = error (abend).
           EVALUATE WS-M03B-RC
             WHEN '00'
               MOVE WS-M03B-FLDT TO TRNX-RECORD
               GO TO 8500-READTRNX-READ
             WHEN '10'
               GO TO 8599-EXIT
             WHEN OTHER
               DISPLAY 'ERROR READING TRNXFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-EVALUATE.

      *---------------------------------------------------------------*
      * 8599-EXIT: Finalizes the transaction table load.
      * Saves the transaction count for the last card slot,
      * sets dispatch to XREFFILE, and returns to 0000-START
      * to begin the next phase (ALTER opens XREFFILE).
      *---------------------------------------------------------------*
       8599-EXIT.
      * Save final card's transaction count.
           MOVE TR-CNT TO WS-TRCT (CR-CNT).
      * Transition to XREFFILE processing phase.
           MOVE 'XREFFILE' TO WS-FL-DD.
           GO TO 0000-START.
           EXIT.

      *---------------------------------------------------------------*
      * 9100-TRNXFILE-CLOSE: Closes TRNXFILE via CBSTM03B
      * ('C' operation). Called from 1000-MAINLINE after all
      * cross-reference records have been processed. Abends
      * if close returns an unexpected return code.
      *---------------------------------------------------------------*
       9100-TRNXFILE-CLOSE.
      * Close TRNXFILE via CBSTM03B subroutine.
           MOVE 'TRNXFILE' TO WS-M03B-DD.
           SET M03B-CLOSE TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING TRNXFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

           EXIT.

      *---------------------------------------------------------------*
      * 9200-XREFFILE-CLOSE: Closes XREFFILE via CBSTM03B
      * ('C' operation). Called from 1000-MAINLINE during the
      * file close sequence after main processing completes.
      *---------------------------------------------------------------*
       9200-XREFFILE-CLOSE.
      * Close XREFFILE via CBSTM03B subroutine.
           MOVE 'XREFFILE' TO WS-M03B-DD.
           SET M03B-CLOSE TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING XREFFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

           EXIT.
      *---------------------------------------------------------------*
      * 9300-CUSTFILE-CLOSE: Closes CUSTFILE via CBSTM03B
      * ('C' operation). Called from 1000-MAINLINE during the
      * file close sequence after main processing completes.
      *---------------------------------------------------------------*
       9300-CUSTFILE-CLOSE.
      * Close CUSTFILE via CBSTM03B subroutine.
           MOVE 'CUSTFILE' TO WS-M03B-DD.
           SET M03B-CLOSE TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING CUSTFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

           EXIT.
      *---------------------------------------------------------------*
      * 9400-ACCTFILE-CLOSE: Closes ACCTFILE via CBSTM03B
      * ('C' operation). Last VSAM file to close. Called from
      * 1000-MAINLINE during the file close sequence.
      *---------------------------------------------------------------*
       9400-ACCTFILE-CLOSE.
      * Close ACCTFILE via CBSTM03B subroutine.
           MOVE 'ACCTFILE' TO WS-M03B-DD.
           SET M03B-CLOSE TO TRUE.
           MOVE ZERO TO WS-M03B-RC.
           CALL 'CBSTM03B' USING WS-M03B-AREA.

           IF WS-M03B-RC = '00' OR '04'
               CONTINUE
           ELSE
               DISPLAY 'ERROR CLOSING ACCTFILE'
               DISPLAY 'RETURN CODE: ' WS-M03B-RC
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

           EXIT.

      *---------------------------------------------------------------*
      * 9999-ABEND-PROGRAM: Abnormal termination handler.
      * Displays diagnostic message and invokes LE runtime
      * service CEE3ABD to issue an abend. Called via PERFORM
      * from any file-open, file-read, or file-close paragraph
      * when CBSTM03B returns an unexpected return code.
      * CEE3ABD terminates the enclosing run unit with a
      * user abend code (default 999), producing a dump for
      * post-mortem analysis.
      *---------------------------------------------------------------*
       9999-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
           CALL 'CEE3ABD'.

