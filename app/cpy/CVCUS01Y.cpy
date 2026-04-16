      *****************************************************************
      *    Data-structure for Customer entity (RECLN 500)
      *****************************************************************
      * Record Layout Copybook: Customer master record (500 bytes)
      * Defines the record structure for the CUSTDAT VSAM KSDS
      * dataset -- the primary customer master file.
      * Primary key: CUST-ID (9 bytes, position 1)
      * Contains customer demographics, contact info, identity,
      * and financial profile data.
      * 50 records in fixture data (app/data/ASCII/custdata.txt)
      * Data loaded via: app/jcl/CUSTFILE.jcl
      *
      * Consuming programs:
      *   Online: COACTVWC, COACTUPC, COCRDSLC, COCRDUPC
      *   Batch:  CBCUS01C, CBTRN01C
      *   Stmt:   CBSTM03A
      *
      * Cross-references:
      *   Alternate layout: app/cpy/CUSTREC.cpy
      *   XREF link field:  app/cpy/CVACT03Y.cpy (XREF-CUST-ID)
      *   ASCII data:       app/data/ASCII/custdata.txt
      *****************************************************************
       01  CUSTOMER-RECORD.
      * --- Customer identification (bytes 1-9) ---
      * VSAM primary key, 9-digit numeric customer identifier
           05  CUST-ID                                 PIC 9(09).
      * --- Customer name fields (bytes 10-84) ---
      * Customer first name (bytes 10-34)
           05  CUST-FIRST-NAME                         PIC X(25).
      * Customer middle name (bytes 35-59)
           05  CUST-MIDDLE-NAME                        PIC X(25).
      * Customer last name (bytes 60-84)
           05  CUST-LAST-NAME                          PIC X(25).
      * --- Mailing address fields (bytes 85-249) ---
      * Street address line 1 (bytes 85-134)
           05  CUST-ADDR-LINE-1                        PIC X(50).
      * Street address line 2 (bytes 135-184)
           05  CUST-ADDR-LINE-2                        PIC X(50).
      * Street address line 3 (bytes 185-234)
           05  CUST-ADDR-LINE-3                        PIC X(50).         
      * US state code, 2-char abbreviation (bytes 235-236)
           05  CUST-ADDR-STATE-CD                      PIC X(02).
      * Country code, 3-char ISO-style (bytes 237-239)
           05  CUST-ADDR-COUNTRY-CD                    PIC X(03).
      * ZIP or postal code (bytes 240-249)
           05  CUST-ADDR-ZIP                           PIC X(10).
      * --- Contact phone numbers (bytes 250-279) ---
      * Primary phone number (bytes 250-264)
           05  CUST-PHONE-NUM-1                        PIC X(15).
      * Secondary or alternate phone number (bytes 265-279)
           05  CUST-PHONE-NUM-2                        PIC X(15).
      * --- Identity and financial fields (bytes 280-332) ---
      * Social Security Number (bytes 280-288)
      * Display numeric PIC 9(09) -- no sign, no decimal
           05  CUST-SSN                                PIC 9(09).
      * Government-issued identification (bytes 289-308)
           05  CUST-GOVT-ISSUED-ID                     PIC X(20).
      * Date of birth in YYYY-MM-DD format (bytes 309-318)
           05  CUST-DOB-YYYY-MM-DD                     PIC X(10).
      * Electronic funds transfer account ID (bytes 319-328)
           05  CUST-EFT-ACCOUNT-ID                     PIC X(10).
      * Primary card holder indicator (byte 329)
      * Y/N flag: whether this customer is the primary
      * holder on the linked card(s)
           05  CUST-PRI-CARD-HOLDER-IND                PIC X(01).
      * FICO credit score (bytes 330-332)
      * Numeric PIC 9(03), practical range 300-850
           05  CUST-FICO-CREDIT-SCORE                  PIC 9(03).
      * --- Reserved area (bytes 333-500) ---
      * Filler reserved for future expansion (168 bytes)
           05  FILLER                                  PIC X(168).      
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
      *
