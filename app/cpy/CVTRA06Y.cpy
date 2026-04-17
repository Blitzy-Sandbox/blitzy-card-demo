      *****************************************************************         
      *    Data-structure for DALYTRANsaction record (RECLN = 350)              
      *****************************************************************         
      *
      * Record Layout Copybook: Daily transaction staging
      * record (350 bytes)
      *
      * Defines the input record structure for the DAILYTRAN
      * VSAM dataset, which serves as the staging area for
      * daily transactions before they are validated and
      * posted to the transaction master file.
      *
      * This layout mirrors TRAN-RECORD in CVTRA05Y.cpy
      * (same 350-byte structure) but uses the DALYTRAN-
      * prefix to avoid name conflicts when both copybooks
      * are included in the same program.
      *
      * Data source: app/data/ASCII/dailytran.txt
      * Loaded via JCL into the DAILYTRAN VSAM KSDS
      *
      * Consuming programs:
      *   CBTRN01C.cbl - Daily transaction driver
      *   CBTRN02C.cbl - Transaction posting engine
      *
      * Cross-references:
      *   CVTRA05Y.cpy - Mirror layout (TRAN-RECORD)
      *   dailytran.txt - Source fixture data
      *
       01  DALYTRAN-RECORD.                                                     
      * --- Transaction identification (bytes 1-32) ---
           05  DALYTRAN-ID                             PIC X(16).               
           05  DALYTRAN-TYPE-CD                        PIC X(02).               
           05  DALYTRAN-CAT-CD                         PIC 9(04).               
           05  DALYTRAN-SOURCE                         PIC X(10).               
      * --- Transaction description (bytes 33-132) ---
           05  DALYTRAN-DESC                           PIC X(100).              
      * --- Transaction amount (bytes 133-143) ---
      *     Signed with implied decimal (S9(09)V99).
      *     Negative values represent credits/refunds.
           05  DALYTRAN-AMT                            PIC S9(09)V99.           
      * --- Merchant information (bytes 144-262) ---
           05  DALYTRAN-MERCHANT-ID                    PIC 9(09).               
           05  DALYTRAN-MERCHANT-NAME                  PIC X(50).               
           05  DALYTRAN-MERCHANT-CITY                  PIC X(50).               
           05  DALYTRAN-MERCHANT-ZIP                   PIC X(10).               
      * --- Card and timestamps (bytes 263-330) ---
           05  DALYTRAN-CARD-NUM                       PIC X(16).               
      *     26-char ISO timestamps (YYYY-MM-DD-HH.MM.SS.nnnnnn)
           05  DALYTRAN-ORIG-TS                        PIC X(26).               
           05  DALYTRAN-PROC-TS                        PIC X(26).               
      * --- Reserved filler (bytes 331-350) ---
           05  FILLER                                  PIC X(20).       
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:01 CDT
      *
