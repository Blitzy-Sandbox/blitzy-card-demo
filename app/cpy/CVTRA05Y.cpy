      *****************************************************************         
      *    Data-structure for TRANsaction record (RECLN = 350)                  
      *****************************************************************         
      *
      * Record Layout Copybook: Transaction master record
      * (350 bytes, fixed-length)
      *
      * Defines the record structure for the TRANSACT VSAM
      * KSDS dataset, the primary transaction store for both
      * online and batch transaction records.
      * Primary key: TRAN-ID (16 bytes, position 1-16)
      *
      * Most heavily consumed record layout in CardDemo,
      * used by online transaction screens (COTRN00C,
      * COTRN01C, COTRN02C, COBIL00C, CORPT00C) and batch
      * processing (CBTRN02C, CBTRN03C, CBACT04C, CBSTM03A)
      *
      * Cross-references:
      *   CVTRA06Y.cpy - Staging record (DALYTRAN-RECORD,
      *                   identical layout with DALYTRAN-
      *                   prefix)
      *   COSTM01.CPY  - Reporting layout (TRNX-RECORD)
      *   CVTRA03Y.cpy - Transaction type lookup
      *   CVTRA04Y.cpy - Transaction category lookup
      *   CVACT02Y.cpy - Card record (CARD-RECORD)
      *   CVACT03Y.cpy - Cross-reference (CARD-XREF-RECORD)
      *
      *-------------------------------------------------------
       01  TRAN-RECORD.                                                         
      * Transaction ID - VSAM primary key (bytes 1-16)
           05  TRAN-ID                                 PIC X(16).               
      * Transaction type code (bytes 17-18)
      * References TRAN-TYPE-RECORD in CVTRA03Y.cpy
           05  TRAN-TYPE-CD                            PIC X(02).               
      * Transaction category code (bytes 19-22)
      * References TRAN-CAT-RECORD in CVTRA04Y.cpy
           05  TRAN-CAT-CD                             PIC 9(04).               
      * Transaction source identifier (bytes 23-32)
           05  TRAN-SOURCE                             PIC X(10).               
      * Free-text description (bytes 33-132)
           05  TRAN-DESC                               PIC X(100).              
      * Transaction amount (bytes 133-143)
      * Signed numeric S9(09)V99 - V is implied
      * decimal (not stored), allows +/-999999999.99
           05  TRAN-AMT                                PIC S9(09)V99.           
      * Merchant identifier (bytes 144-152)
           05  TRAN-MERCHANT-ID                        PIC 9(09).               
      * Merchant name (bytes 153-202)
           05  TRAN-MERCHANT-NAME                      PIC X(50).               
      * Merchant city (bytes 203-252)
           05  TRAN-MERCHANT-CITY                      PIC X(50).               
      * Merchant ZIP/postal code (bytes 253-262)
           05  TRAN-MERCHANT-ZIP                       PIC X(10).               
      * Card number (bytes 263-278)
      * Foreign key to CARD-RECORD in CVACT02Y.cpy
      * and CARD-XREF-RECORD in CVACT03Y.cpy
           05  TRAN-CARD-NUM                           PIC X(16).               
      * Origination timestamp (bytes 279-304)
      * ISO format YYYY-MM-DD-HH.MM.SS.NNNNNN
           05  TRAN-ORIG-TS                            PIC X(26).               
      * Processing timestamp (bytes 305-330)
      * ISO format YYYY-MM-DD-HH.MM.SS.NNNNNN
           05  TRAN-PROC-TS                            PIC X(26).               
      * Reserved for future use (bytes 331-350)
           05  FILLER                                  PIC X(20).               
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:01 CDT
      *
