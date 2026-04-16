      *****************************************************************
      *    Data-structure for card entity (RECLN 150)
      *****************************************************************
      * Record Layout Copybook: Credit card record (150 bytes)
      * Defines the record structure for the CARDDAT VSAM KSDS
      * dataset -- the credit card master file.
      * Primary key: CARD-NUM (16 bytes, position 1)
      * 50 records in fixture data (app/data/ASCII/carddata.txt)
      * Data loaded via: app/jcl/CARDFILE.jcl
      *
      * Consuming programs:
      *   CBACT02C  - Batch card file read utility
      *   COCRDLIC  - Online card list browse
      *   COCRDSLC  - Online card detail view
      *   COCRDUPC  - Online card update
      *   CBTRN01C  - Batch transaction validation
      *   COACTVWC  - Online account view (card cross-ref)
      *
      * Cross-references:
      *   CVACT01Y.cpy - Account record (ACCT-ID = CARD-ACCT-ID)
      *   CVACT03Y.cpy - Cross-ref (XREF-CARD-NUM = CARD-NUM)
      *   carddata.txt  - ASCII fixture data
      *
       01  CARD-RECORD.
      * Card number -- 16-digit identifier, primary key for
      * CARDDAT VSAM KSDS (bytes 1-16)
           05  CARD-NUM                          PIC X(16).
      * Owning account ID -- 11-digit foreign key linking
      * this card to an account record in CVACT01Y
      * (bytes 17-27)
           05  CARD-ACCT-ID                      PIC 9(11).
      * Card Verification Value -- 3-digit CVV security
      * code (bytes 28-30)
           05  CARD-CVV-CD                       PIC 9(03).
      * Embossed name -- cardholder name printed on the
      * physical card (50 chars, bytes 31-80)
           05  CARD-EMBOSSED-NAME                PIC X(50).
      * Expiration date -- card expiry date (10 chars,
      * bytes 81-90). Note: field name retains original
      * typo "EXPIRAION" (not "EXPIRATION")
           05  CARD-EXPIRAION-DATE               PIC X(10).
      * Active status flag -- 'Y' = active, 'N' = inactive
      * (1 byte, byte 91)
           05  CARD-ACTIVE-STATUS                PIC X(01).
      * Reserved filler for future expansion
      * (59 bytes, bytes 92-150)
           05  FILLER                            PIC X(59).
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
      *
