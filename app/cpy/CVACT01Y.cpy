      *****************************************************************
      *    Data-structure for  account entity (RECLN 300)
      *****************************************************************
      *
      * Record Layout Copybook: Account master record (300 bytes)
      * Defines the record structure for the ACCTDAT VSAM KSDS
      * dataset. Primary key: ACCT-ID (11 bytes, position 1).
      * Core financial entity -- stores balances, limits, dates,
      * and group classification for each credit card account.
      * Most widely consumed record layout in the application.
      * 50 records in fixture data (app/data/ASCII/acctdata.txt)
      *
      * Consuming Programs (Online):
      *   COACTVWC - Account view
      *   COACTUPC - Account update
      *   COTRN02C - Transaction add
      *   COBIL00C - Bill payment
      * Consuming Programs (Batch):
      *   CBACT01C - Account file read utility
      *   CBACT04C - Interest calculation
      *   CBTRN01C - Daily transaction driver
      *   CBTRN02C - Transaction posting engine
      *   CBSTM03A - Statement generation
      *
      * Data loaded via: app/jcl/ACCTFILE.jcl
      *
      * Cross-References:
      *   CVACT02Y.cpy - Card (CARD-ACCT-ID -> ACCT-ID)
      *   CVACT03Y.cpy - Xref (XREF-ACCT-ID -> ACCT-ID)
      *   CVTRA02Y.cpy - Disclosure group
      *     (DIS-ACCT-GROUP-ID -> ACCT-GROUP-ID)
      *     for interest rate lookup
      *   CVTRA01Y.cpy - Category balance
      *     (TRANCAT-ACCT-ID -> ACCT-ID)
      *
      *****************************************************************
       01  ACCOUNT-RECORD.
      * --- Account number, VSAM primary key (bytes 1-11) ---
           05  ACCT-ID                           PIC 9(11).
      * --- Active/inactive status flag (byte 12) ---
           05  ACCT-ACTIVE-STATUS                PIC X(01).
      * --- Current balance: signed display numeric with
      *     implied decimal V99. Updated by CBTRN02C
      *     (posting), CBACT04C (interest), COBIL00C
      *     (bill pay). Bytes 13-24. ---
           05  ACCT-CURR-BAL                     PIC S9(10)V99.
      * --- Credit limit: signed display numeric V99
      *     (bytes 25-36) ---
           05  ACCT-CREDIT-LIMIT                 PIC S9(10)V99.
      * --- Cash advance credit limit (bytes 37-48) ---
           05  ACCT-CASH-CREDIT-LIMIT            PIC S9(10)V99.
      * --- Account open date, YYYY-MM-DD (bytes 49-58) ---
           05  ACCT-OPEN-DATE                    PIC X(10).
      * --- Expiration date (bytes 59-68)
      *     Note: field name typo "EXPIRAION" is preserved
      *     from the original source (same as CVACT02Y) ---
           05  ACCT-EXPIRAION-DATE               PIC X(10). 
      * --- Card reissue date (bytes 69-78) ---
           05  ACCT-REISSUE-DATE                 PIC X(10).
      * --- Running cycle credit total, updated during
      *     batch posting by CBTRN02C (bytes 79-90) ---
           05  ACCT-CURR-CYC-CREDIT              PIC S9(10)V99.
      * --- Running cycle debit total, updated during
      *     batch posting by CBTRN02C (bytes 91-102) ---
           05  ACCT-CURR-CYC-DEBIT               PIC S9(10)V99.
      * --- Billing ZIP code (bytes 103-112) ---
           05  ACCT-ADDR-ZIP                     PIC X(10).
      * --- Account group ID for interest rate lookup.
      *     Links to DIS-ACCT-GROUP-ID in CVTRA02Y.cpy
      *     (bytes 113-122) ---
           05  ACCT-GROUP-ID                     PIC X(10).
      * --- Reserved for future expansion (bytes 123-300) ---
           05  FILLER                            PIC X(178).      
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT
      *
