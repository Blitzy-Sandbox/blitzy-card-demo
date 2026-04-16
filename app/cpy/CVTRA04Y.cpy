      *****************************************************************         
      *    Data-structure for transaction category type (RECLN = 60)            
      *****************************************************************         
      *
      * Record Layout Copybook: Transaction category
      * record (60 bytes). Defines the record structure
      * for the TRANCATG VSAM KSDS dataset.
      *
      * Composite primary key: TRAN-TYPE-CD (2 bytes)
      *   + TRAN-CAT-CD (4 bytes) = 6-byte key.
      * Categories are subtypes within a transaction
      * type (e.g., type 'PR' purchase may have
      * categories for retail, online, etc.).
      *
      * Cross-references:
      *   Parent type:  CVTRA03Y.cpy
      *     (TRAN-TYPE-RECORD)
      *   Balance rec:  CVTRA01Y.cpy
      *     (uses TYPE-CD + CAT-CD in its key)
      *   ASCII data:   app/data/ASCII/trancatg.txt
      *     (18 records)
      *   Loaded via:   app/jcl/TRANCATG.jcl
      *   Consumer:     CBTRN03C.cbl
      *     (report category description lookup)
      *
      *---------------------------------------------------------
      * 01 TRAN-CAT-RECORD: Root record (60 bytes total)
      *---------------------------------------------------------
       01  TRAN-CAT-RECORD.                                                     
      *    05 TRAN-CAT-KEY: Composite primary key
      *       group (6 bytes, bytes 1-6)
           05  TRAN-CAT-KEY.                                                    
      *       10 TRAN-TYPE-CD: Transaction type code
      *          (bytes 1-2, PIC X(02)). Foreign key
      *          to CVTRA03Y.cpy TRAN-TYPE field
              10  TRAN-TYPE-CD                         PIC X(02).               
      *       10 TRAN-CAT-CD: Category code within
      *          type (bytes 3-6, PIC 9(04)). Numeric
      *          identifier, unique within each type
              10  TRAN-CAT-CD                          PIC 9(04).               
      *    05 TRAN-CAT-TYPE-DESC: Human-readable
      *       category description text
      *       (bytes 7-56, PIC X(50))
           05  TRAN-CAT-TYPE-DESC                      PIC X(50).               
      *    05 FILLER: Reserved/unused space
      *       (bytes 57-60, PIC X(04))
           05  FILLER                                  PIC X(04).               
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:01 CDT
      *
