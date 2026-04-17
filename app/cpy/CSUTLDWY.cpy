      ******************************************************************
      *Working Storage Copybook for DATE related code
      ******************************************************************
      *----------------------------------------------------------------*
      * Working Storage Copybook: Date editing and validation
      * fields used by the date validation paragraphs.
      * Companion to CSUTLDPY.cpy (PROCEDURE DIVISION paragraphs)
      * Called via CSUTLDTC.cbl for LE CEEDAYS date validation
      *
      * Included at the 10-level -- designed to nest under a
      * parent 01-level or 05-level group in the consuming
      * program's WORKING-STORAGE SECTION.
      *
      * Consuming program: COACTUPC.cbl (COPY 'CSUTLDWY'
      *   under WS-GENERIC-EDITS at 05-level)
      *----------------------------------------------------------------*
      *----------------------------------------------------------------*
      * 8-byte date input field in CCYYMMDD format with
      * component views for century (CC), year (YY), month
      * (MM), and day (DD). Each component has alphanumeric
      * (PIC X) and numeric (PIC 9) REDEFINES to allow both
      * string and numeric operations.
      *----------------------------------------------------------------*
           10 WS-EDIT-DATE-CCYYMMDD.
              20 WS-EDIT-DATE-CCYY.
      * Century component (CC): 2 bytes alphanumeric + numeric
                 25 WS-EDIT-DATE-CC                PIC X(2).
                 25 WS-EDIT-DATE-CC-N REDEFINES    WS-EDIT-DATE-CC
                                                   PIC 9(2).
      * 88-level: THIS-CENTURY = year starts with 20
      *           LAST-CENTURY = year starts with 19
                    88 THIS-CENTURY                VALUE 20.
                    88 LAST-CENTURY                VALUE 19.
      * Year-within-century component (YY): 2 bytes
                 25 WS-EDIT-DATE-YY                PIC X(2).
                 25 WS-EDIT-DATE-YY-N REDEFINES    WS-EDIT-DATE-YY
                                                   PIC 9(2).
      * Full 4-digit year as numeric for arithmetic
              20 WS-EDIT-DATE-CCYY-N  REDEFINES
                 WS-EDIT-DATE-CCYY                 PIC 9(4).
      * Month component (MM): 2 bytes alphanumeric + numeric
              20 WS-EDIT-DATE-MM                   PIC X(2).
              20 WS-EDIT-DATE-MM-N REDEFINES WS-EDIT-DATE-MM
                                                   PIC 9(2).
      * Month 88-level conditions for validation:
      *   WS-VALID-MONTH  = month is 1 through 12
      *   WS-31-DAY-MONTH = Jan,Mar,May,Jul,Aug,Oct,Dec
      *   WS-FEBRUARY     = month is February (leap year)
                 88 WS-VALID-MONTH                 VALUES
                                                   1 THROUGH 12.
                 88 WS-31-DAY-MONTH                VALUES
                                                   1, 3, 5, 7,
                                                   8, 10, 12.
                 88 WS-FEBRUARY                    VALUE 2.
      * Day component (DD): 2 bytes alphanumeric + numeric
              20 WS-EDIT-DATE-DD                   PIC X(2).
              20 WS-EDIT-DATE-DD-N REDEFINES WS-EDIT-DATE-DD
                                                   PIC 9(2).
      * Day 88-level conditions for validation:
      *   WS-VALID-DAY     = day 1 through 31 (broad check)
      *   WS-DAY-31        = exactly 31 (boundary check)
      *   WS-DAY-30        = exactly 30 (boundary check)
      *   WS-DAY-29        = exactly 29 (Feb leap check)
      *   WS-VALID-FEB-DAY = day 1 through 28 (non-leap Feb)
                 88 WS-VALID-DAY                   VALUES
                                                   1 THROUGH 31.
                 88 WS-DAY-31                      VALUE 31.
                 88 WS-DAY-30                      VALUE 30.
                 88 WS-DAY-29                      VALUE 29.
                 88 WS-VALID-FEB-DAY               VALUES
                                                   1 THROUGH 28.
      *----------------------------------------------------------------*
      * Numeric REDEFINES of the full 8-byte date for
      * arithmetic and range comparisons as 9(8) integer.
      *----------------------------------------------------------------*
           10 WS-EDIT-DATE-CCYYMMDD-N REDEFINES
              WS-EDIT-DATE-CCYYMMDD                PIC 9(8).
      * Binary integer for LE CEEDAYS Lillian date arithmetic
           10 WS-EDIT-DATE-BINARY                  PIC S9(9) BINARY.
      *----------------------------------------------------------------*
      * Current date storage for date-of-birth comparison.
      * Holds today's date in YYYYMMDD format with numeric
      * REDEFINES and a binary field for date arithmetic.
      *----------------------------------------------------------------*
           10 WS-CURRENT-DATE.
              20 WS-CURRENT-DATE-YYYYMMDD          PIC X(8).
              20 WS-CURRENT-DATE-YYYYMMDD-N REDEFINES
                 WS-CURRENT-DATE-YYYYMMDD          PIC 9(8).
              20 WS-CURRENT-DATE-BINARY            PIC S9(9) BINARY.
      *----------------------------------------------------------------*
      * Composite validation flag (3 bytes: year+month+day)
      * LOW-VALUES = valid, '0' = not ok, 'B' = blank/missing
      * 88-level conditions on the group test all 3 bytes:
      *   WS-EDIT-DATE-IS-VALID   = LOW-VALUES (all valid)
      *   WS-EDIT-DATE-IS-INVALID = '000' (all parts invalid)
      * Individual byte flags enable granular error reporting.
      *----------------------------------------------------------------*
           10 WS-EDIT-DATE-FLGS.
               88 WS-EDIT-DATE-IS-VALID            VALUE LOW-VALUES.
               88 WS-EDIT-DATE-IS-INVALID          VALUE '000'.
      * Year validation flag byte (1 of 3)
               20 WS-EDIT-YEAR-FLG                 PIC X(01).
                  88 FLG-YEAR-ISVALID              VALUE LOW-VALUES.
                  88 FLG-YEAR-NOT-OK               VALUE '0'.
                  88 FLG-YEAR-BLANK                VALUE 'B'.
      * Month validation flag byte (2 of 3)
               20 WS-EDIT-MONTH                    PIC X(01).
                  88 FLG-MONTH-ISVALID             VALUE LOW-VALUES.
                  88 FLG-MONTH-NOT-OK              VALUE '0'.
                  88 FLG-MONTH-BLANK               VALUE 'B'.
      * Day validation flag byte (3 of 3)
               20 WS-EDIT-DAY                      PIC X(01).
                  88 FLG-DAY-ISVALID               VALUE LOW-VALUES.
                  88 FLG-DAY-NOT-OK                VALUE '0'.
                  88 FLG-DAY-BLANK                 VALUE 'B'.
      * Date format mask passed to LE CEEDAYS via CSUTLDTC
          10 WS-DATE-FORMAT                        PIC X(08)
                                                   VALUE 'YYYYMMDD'.
      *----------------------------------------------------------------*
      * Structured result buffer from LE CEEDAYS call via
      * CSUTLDTC. Severity 0 = valid date; non-zero = error.
      * Contains: severity code, LE message number, result
      * text, the date tested, and the format mask used.
      *----------------------------------------------------------------*
          10 WS-DATE-VALIDATION-RESULT .
               20 WS-SEVERITY                      PIC X(04).
               20 WS-SEVERITY-N                    REDEFINES
                  WS-SEVERITY                      PIC 9(4).
               20 FILLER                           PIC X(11)
                                                   VALUE 'Mesg Code:'.
               20 WS-MSG-NO                        PIC X(04).
               20 WS-MSG-NO-N                      REDEFINES
                  WS-MSG-NO                        Pic 9(4).
               20 FILLER                           PIC X(01)
                                                   VALUE SPACE.
               20 WS-RESULT                        PIC X(15).
               20 FILLER                           PIC X(01)
                                                   VALUE SPACE.
               20 FILLER                           PIC X(09)
                                                   VALUE 'TstDate:'.
               20 WS-DATE                          PIC X(10).
               20 FILLER                           PIC X(01)
                                                   VALUE SPACE.
               20 FILLER                           PIC X(10)
                                                   VALUE 'Mask used:' .
               20 WS-DATE-FMT                      PIC X(10).
               20 FILLER                           PIC X(01)
                                                   VALUE SPACE.
               20 FILLER                           PIC X(03)
                                                   VALUE SPACES.

      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT
      *
