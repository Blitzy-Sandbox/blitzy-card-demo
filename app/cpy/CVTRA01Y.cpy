      *****************************************************************         
      *    Data-structure for transaction category balance (RECLN = 50)         
      *****************************************************************         
      * Record Layout Copybook: Transaction category
      * balance record (50 bytes)
      * Defines the record structure for the TCATBALF
      * VSAM KSDS dataset
      *
      * Composite primary key (17 bytes):
      *   TRANCAT-ACCT-ID (11) + TRANCAT-TYPE-CD (2)
      *   + TRANCAT-CD (4)
      * Maintains running balance totals aggregated by
      *   account, transaction type, and category
      * Updated by CBTRN02C.cbl during batch transaction
      *   posting
      *
      * Consuming programs:
      *   CBACT04C.cbl - interest calculation
      *   CBTRN02C.cbl - transaction posting
      * Data loaded/rebuilt via: app/jcl/TCATBALF.jcl
      * Fixture data: app/data/ASCII/tcatbal.txt
      *   (50 records)
      *
      * Cross-references:
      *   Account ID  -> CVACT01Y.cpy (ACCT-ID)
      *   Type code   -> CVTRA03Y.cpy (tran type)
      *   Category    -> CVTRA04Y.cpy (tran category)
      *
       01  TRAN-CAT-BAL-RECORD.                                                 
      * Composite key group (17 bytes, bytes 1-17)
      * Enables balance lookups by account + type
      *   + category combination
           05  TRAN-CAT-KEY.                                                    
      * Account identifier (bytes 1-11)
      * Foreign key to CVACT01Y.cpy
              10 TRANCAT-ACCT-ID                       PIC 9(11).               
      * Transaction type code (bytes 12-13)
      * Foreign key to CVTRA03Y.cpy
              10 TRANCAT-TYPE-CD                       PIC X(02).               
      * Category code (bytes 14-17)
      * Foreign key to CVTRA04Y.cpy
              10 TRANCAT-CD                            PIC 9(04).               
      * Running balance amount (bytes 18-28)
      * Signed S9(09)V99 tracks net balance
      *   (credits and debits)
           05  TRAN-CAT-BAL                            PIC S9(09)V99.           
      * Reserved/unused space (bytes 29-50)
           05  FILLER                                  PIC X(22).               
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
      *
