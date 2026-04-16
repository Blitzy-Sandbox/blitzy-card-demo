      *****************************************************************         
      *    Data-structure for disclosure group (RECLN = 50)                     
      *****************************************************************         
      * Record Layout Copybook: Disclosure group record
      * (50 bytes). Defines the record structure for the
      * DISCGRP VSAM KSDS dataset used for interest rate
      * disclosure groups.
      *
      * Composite primary key (16 bytes):
      *   DIS-ACCT-GROUP-ID (10)
      *   + DIS-TRAN-TYPE-CD (2)
      *   + DIS-TRAN-CAT-CD  (4)
      *
      * Maps account groups to interest rates by
      * transaction type and category. The composite
      * key joins an account group to a type+category
      * pair to determine the applicable interest rate
      * for interest calculation.
      *
      * Consuming programs:
      *   - CBACT04C.cbl (interest calculation/posting)
      *
      * Data source:
      *   app/data/ASCII/discgrp.txt (51 records)
      * Loaded via:
      *   app/jcl/DISCGRP.jcl
      *
      * Cross-references:
      *   - CVACT01Y.cpy (ACCT-GROUP-ID links here)
      *   - CVTRA03Y.cpy (transaction type record)
      *   - CVTRA04Y.cpy (transaction category record)
      *
       01  DIS-GROUP-RECORD.                                                    
      * Composite key group - 16 bytes (bytes 1-16)
      * Uniquely identifies a disclosure group entry
           05  DIS-GROUP-KEY.                                                   
      * Account group identifier (bytes 1-10)
              10 DIS-ACCT-GROUP-ID                     PIC X(10).               
      * Transaction type code (bytes 11-12)
              10 DIS-TRAN-TYPE-CD                      PIC X(02).               
      * Transaction category code (bytes 13-16)
              10 DIS-TRAN-CAT-CD                       PIC 9(04).               
      * Interest rate - signed numeric with implied
      * decimal. S9(04)V99 allows rates up to
      * +/-9999.99% (bytes 17-22, 6-byte DISPLAY)
           05  DIS-INT-RATE                            PIC S9(04)V99.           
      * Reserved/unused space (bytes 23-50, 28 bytes)
           05  FILLER                                  PIC X(28).               
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
      *
