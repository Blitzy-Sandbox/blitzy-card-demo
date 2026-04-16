      *================================================================*
      * Record Layout Copybook: CUSTREC.cpy
      * Alternate customer record layout (500 bytes)
      *
      * Duplicate of CVCUS01Y.cpy with the same CUSTOMER-RECORD
      * 01-level name and identical field layout.
      * Key difference:
      *   CUST-DOB-YYYYMMDD (this file) vs
      *   CUST-DOB-YYYY-MM-DD (CVCUS01Y.cpy)
      *
      * Used by CBSTM03A.CBL (statement generation) - likely
      * exists to avoid COPY REPLACING conflicts when both
      * customer record views are needed in one program.
      *
      * Note: This file uses TAB-indented formatting unlike
      * other copybooks which use standard COBOL spacing.
      *
      * Cross-References:
      *   Primary layout : CVCUS01Y.cpy
      *   Consumer       : CBSTM03A.CBL
      *   ASCII data     : custdata.txt (app/data/ASCII/)
      *================================================================*
      *****************************************************************
      *    Data-structure for Customer entity (RECLN 500)
      *****************************************************************
      * 500-byte customer master record definition.
      * Structurally identical to CVCUS01Y.cpy record.
       01  CUSTOMER-RECORD.
      *  Customer identification (bytes 1-9)
           05  CUST-ID                                 PIC 9(09).
      *  Customer name fields (bytes 10-84)
		     05  CUST-FIRST-NAME                         PIC X(25).
		     05  CUST-MIDDLE-NAME                        PIC X(25).
		     05  CUST-LAST-NAME                          PIC X(25).
      *  Customer address fields (bytes 85-249)
		     05  CUST-ADDR-LINE-1                        PIC X(50).
		     05  CUST-ADDR-LINE-2                        PIC X(50).
		     05  CUST-ADDR-LINE-3                        PIC X(50).		   
		     05  CUST-ADDR-STATE-CD                      PIC X(02).
		     05  CUST-ADDR-COUNTRY-CD                    PIC X(03).
		     05  CUST-ADDR-ZIP                           PIC X(10).
      *  Customer contact fields (bytes 250-279)
		     05  CUST-PHONE-NUM-1                        PIC X(15).
		     05  CUST-PHONE-NUM-2                        PIC X(15).
      *  Identity and government fields (bytes 280-308)
		     05  CUST-SSN                                PIC 9(09).
		     05  CUST-GOVT-ISSUED-ID                     PIC X(20).
      *  Date of birth (bytes 309-318)
      *  NOTE: Named CUST-DOB-YYYYMMDD here;
      *        CVCUS01Y.cpy uses CUST-DOB-YYYY-MM-DD
		     05  CUST-DOB-YYYYMMDD                       PIC X(10).
      *  Financial and account fields (bytes 319-332)
		     05  CUST-EFT-ACCOUNT-ID                     PIC X(10).
		     05  CUST-PRI-CARD-HOLDER-IND                PIC X(01).
		     05  CUST-FICO-CREDIT-SCORE                  PIC 9(03).
      *  Reserved filler to pad record to 500 bytes
      *  (bytes 333-500)
             05  FILLER                                  PIC X(168).      
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT
      *
