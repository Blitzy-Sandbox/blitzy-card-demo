      *****************************************************************         
      *    Data-structure for transaction type (RECLN = 60)                     
      *****************************************************************         
      *
      * Record Layout Copybook: Transaction type record (60 bytes)
      * Defines the record structure for the TRANTYPE VSAM KSDS
      * dataset used for transaction type code lookups.
      * Primary key: TRAN-TYPE (2 bytes) - numeric type codes
      * Small lookup table with 7 records for broad transaction
      * categories:
      *   01 = Purchase       05 = Refund
      *   02 = Payment        06 = Reversal
      *   03 = Credit         07 = Adjustment
      *   04 = Authorization
      *
      * Consuming programs:
      *   CBTRN03C.cbl - Transaction reporting (type description
      *                   lookup for report output)
      *
      * Data loaded via: app/jcl/TRANTYPE.jcl
      * ASCII fixture:   app/data/ASCII/trantype.txt (7 records)
      *
      * Cross-references:
      *   CVTRA04Y.cpy - Transaction category record (child
      *                   subcategories within each type)
      *   CVTRA05Y.cpy - Transaction record (TRAN-TYPE-CD field
      *                   references this type code)
      *
       01  TRAN-TYPE-RECORD.                                                    
      * Bytes 1-2: Primary key - 2-digit numeric type code
           05  TRAN-TYPE                               PIC X(02).               
      * Bytes 3-52: Human-readable description of the type
           05  TRAN-TYPE-DESC                          PIC X(50).               
      * Bytes 53-60: Reserved filler (unused, zero-filled in data)
           05  FILLER                                  PIC X(08).               
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
      *
