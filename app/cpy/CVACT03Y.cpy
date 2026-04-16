      *****************************************************************         
      *    Data-structure for card xref (RECLN 50)                              
      *****************************************************************         
      * Record Layout Copybook: Card-account-customer
      *   cross-reference (50 bytes)
      * Defines the record structure for the CARDXREF VSAM
      *   KSDS dataset (also accessed via CXACAIX alternate
      *   index)
      * Primary key: XREF-CARD-NUM (16 bytes, position 1)
      * Central linking entity: maps card number to customer
      *   ID and account ID
      * This is the most heavily used lookup record in
      *   CardDemo -- enables navigation from any card to
      *   its owning customer and account
      *
      * Fixture data: app/data/ASCII/cardxref.txt (50 records)
      * Data loaded via: app/jcl/XREFFILE.jcl
      * CXACAIX alternate index enables lookup by account ID
      *   (used by COACTVWC.cbl for account view)
      *
      * Consuming programs (online):
      *   COACTVWC, COACTUPC, COCRDSLC, COCRDUPC,
      *   COTRN02C, COBIL00C
      * Consuming programs (batch):
      *   CBACT03C, CBTRN01C, CBTRN02C, CBTRN03C,
      *   CBACT04C, CBSTM03A
      *
      * Cross-references:
      *   Card:     CVACT02Y.cpy (CARD-RECORD)
      *   Account:  CVACT01Y.cpy (ACCOUNT-RECORD)
      *   Customer: CVCUS01Y.cpy (CUSTOMER-RECORD)
      *
       01 CARD-XREF-RECORD.                                                     
      * XREF-CARD-NUM: 16-char card number, primary key
      *   (bytes 1-16). Links to CARD-RECORD in
      *   CVACT02Y.cpy and TRAN-CARD-NUM in CVTRA05Y.cpy
           05  XREF-CARD-NUM                     PIC X(16).                     
      * XREF-CUST-ID: 9-digit customer ID foreign key
      *   (bytes 17-25). Links to CUSTOMER-RECORD in
      *   CVCUS01Y.cpy
           05  XREF-CUST-ID                      PIC 9(09).                     
      * XREF-ACCT-ID: 11-digit account ID foreign key
      *   (bytes 26-36). Links to ACCOUNT-RECORD in
      *   CVACT01Y.cpy
           05  XREF-ACCT-ID                      PIC 9(11).                     
      * FILLER: Reserved space (bytes 37-50, 14 bytes)
           05  FILLER                            PIC X(14).                     
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
      *
