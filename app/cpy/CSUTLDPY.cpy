      ******************************************************************
      *Procedure Division Copybook for DATE related code
      ******************************************************************
      *Date validation paragraph for reuse and hopefully not misuse
      *Accompanying WORKING Storage is CSUTLDTR
      ******************************************************************
      * ***  PERFORM EDIT-DATE-CCYYMMDD
      *         THRU EDIT-DATE-CCYYMMDD-EXIT
      *         to validate CCYYMMDD dates
      *      Reusable paras
      *      a) EDIT-YEAR-CCYY
      *      b) EDIT-MONTH
      *      c) EDIT-DAY
      *      d) EDIT-DATE-OF-BIRTH
      *      e) EDIT-DATE-OF-BIRTH
      ******************************************************************
      *
      * Working storage fields: see CSUTLDWY.cpy for all
      *   WS-EDIT-DATE-*, FLG-*, WS-RETURN-MSG, and
      *   INPUT-ERROR field definitions
      * Consumed by: COACTUPC.cbl (via COPY CSUTLDPY)
      * Called subprogram: CSUTLDTC.cbl (LE CEEDAYS wrapper)
      *
      * Validation flow: the calling program PERFORMs
      *   EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT.
      *   Sub-paragraphs validate each component in order:
      *   EDIT-YEAR-CCYY -> EDIT-MONTH -> EDIT-DAY ->
      *   EDIT-DAY-MONTH-YEAR -> EDIT-DATE-LE
      *
      * Pattern notes:
      *  - GO TO is used for early exit on validation
      *    failure (acknowledged as intentional structured
      *    programming violation in the original comments)
      *  - WS-RETURN-MSG-OFF guard ensures only the first
      *    error message is stored; subsequent failures
      *    set flags but do not overwrite the message
      *
      * Entry point: initializes the composite date flag
      * to invalid. The calling program PERFORM THRU
      * orchestrates the sub-paragraph validation sequence.
       EDIT-DATE-CCYYMMDD.
           SET WS-EDIT-DATE-IS-INVALID   TO TRUE
           .

      ******************************************************************
      *Check for valid year and century
      ******************************************************************
      * Validates 4-digit year (CCYY): rejects blank or
      * space input, verifies numeric content, restricts
      * century to 19xx or 20xx. Sets FLG-YEAR-ISVALID
      * on success.
       EDIT-YEAR-CCYY.

           SET FLG-YEAR-NOT-OK             TO TRUE

      *    Not supplied
           IF WS-EDIT-DATE-CCYY            EQUAL LOW-VALUES
           OR WS-EDIT-DATE-CCYY            EQUAL SPACES
              SET INPUT-ERROR              TO TRUE
              SET  FLG-YEAR-BLANK          TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ' : Year must be supplied.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
      *       Intentional violation of structured programming norms
              GO TO EDIT-YEAR-CCYY-EXIT
           ELSE
              CONTINUE
           END-IF

      *    Not numeric
           IF WS-EDIT-DATE-CCYY            IS NOT NUMERIC
              SET INPUT-ERROR              TO TRUE
              SET  FLG-YEAR-NOT-OK         TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ' must be 4 digit number.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-YEAR-CCYY-EXIT
           ELSE
              CONTINUE
           END-IF

      ******************************************************************
      *    Century not reasonable
      ******************************************************************
      *  Not having learnt our lesson from history and Y2K
      *  And being unable to imagine COBOL in the 2100s
      *  We code only 19 and 20 as valid century values
      ******************************************************************
           IF THIS-CENTURY
           OR LAST-CENTURY
              CONTINUE
           ELSE
              SET INPUT-ERROR              TO TRUE
              SET  FLG-YEAR-NOT-OK         TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ' : Century is not valid.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-YEAR-CCYY-EXIT
           END-IF

           SET FLG-YEAR-ISVALID            TO TRUE
           .
       EDIT-YEAR-CCYY-EXIT.
           EXIT
           .
      * Validates 2-digit month (MM): rejects blank or
      * space input, checks range 1-12 via 88-level
      * condition WS-VALID-MONTH, converts alphanumeric
      * to numeric via NUMVAL intrinsic function.
      * Sets FLG-MONTH-ISVALID on success.
       EDIT-MONTH.
           SET FLG-MONTH-NOT-OK            TO TRUE

           IF WS-EDIT-DATE-MM              EQUAL LOW-VALUES
           OR WS-EDIT-DATE-MM              EQUAL SPACES
              SET INPUT-ERROR              TO TRUE
              SET  FLG-MONTH-BLANK         TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ' : Month must be supplied.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-MONTH-EXIT
           ELSE
              CONTINUE
           END-IF

      *    Month not reasonable
           IF WS-VALID-MONTH
              CONTINUE
           ELSE
              SET INPUT-ERROR              TO TRUE
              SET  FLG-MONTH-NOT-OK        TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ': Month must be a number between 1 and 12.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-MONTH-EXIT
           END-IF

           IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-MM) = 0
              COMPUTE WS-EDIT-DATE-MM-N
                          = FUNCTION NUMVAL (WS-EDIT-DATE-MM)
              END-COMPUTE
           ELSE
              SET INPUT-ERROR              TO TRUE
              SET  FLG-MONTH-NOT-OK        TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ': Month must be a number between 1 and 12.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-MONTH-EXIT
           END-IF

           SET FLG-MONTH-ISVALID           TO TRUE
           .
       EDIT-MONTH-EXIT.
           EXIT
           .


      * Validates 2-digit day (DD): rejects blank or
      * space input, verifies numeric via TEST-NUMVAL
      * intrinsic, converts via NUMVAL, and checks
      * range 1-31 using 88-level WS-VALID-DAY.
       EDIT-DAY.

           SET FLG-DAY-ISVALID             TO TRUE

           IF WS-EDIT-DATE-DD              EQUAL LOW-VALUES
           OR WS-EDIT-DATE-DD              EQUAL SPACES
              SET INPUT-ERROR              TO TRUE
              SET  FLG-DAY-BLANK           TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ' : Day must be supplied.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DAY-EXIT
           ELSE
              CONTINUE
           END-IF

           IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-DD) = 0
              COMPUTE WS-EDIT-DATE-DD-N
                          = FUNCTION NUMVAL (WS-EDIT-DATE-DD)
              END-COMPUTE
           ELSE
              SET INPUT-ERROR              TO TRUE
              SET  FLG-DAY-NOT-OK          TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ':day must be a number between 1 and 31.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DAY-EXIT
           END-IF

           IF WS-VALID-DAY
              CONTINUE
           ELSE
              SET INPUT-ERROR              TO TRUE
              SET FLG-DAY-NOT-OK          TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ':day must be a number between 1 and 31.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DAY-EXIT
           END-IF
           .

           SET FLG-DAY-ISVALID           TO TRUE
           .
       EDIT-DAY-EXIT.
           EXIT
           .

      * Cross-field day/month/year validation: checks
      * combinations invalid together but valid alone:
      *  - Day 31 in a non-31-day month
      *  - Day 30 in February
      *  - Day 29 in Feb for non-leap years
      * Leap year: if year ends in 00 (century year)
      * divides full year by 400; otherwise by 4.
      * Remainder of zero means leap year.
       EDIT-DAY-MONTH-YEAR.
      ******************************************************************
      *    Checking for any other combinations
      ******************************************************************
           IF  NOT WS-31-DAY-MONTH
           AND WS-DAY-31
              SET INPUT-ERROR              TO TRUE
              SET FLG-DAY-NOT-OK           TO TRUE
              SET FLG-MONTH-NOT-OK         TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ':Cannot have 31 days in this month.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DATE-CCYYMMDD-EXIT
           END-IF

           IF  WS-FEBRUARY
           AND WS-DAY-30
              SET INPUT-ERROR              TO TRUE
              SET FLG-DAY-NOT-OK           TO TRUE
              SET FLG-MONTH-NOT-OK         TO TRUE
              IF WS-RETURN-MSG-OFF
                 STRING
                   FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ':Cannot have 30 days in this month.'
                   DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DATE-CCYYMMDD-EXIT
           END-IF

      * Leap year check for Feb 29: century years (YY=00)
      * must be divisible by 400; other years by 4.
      * Non-zero remainder means not a leap year.
           IF  WS-FEBRUARY
           AND WS-DAY-29
               IF WS-EDIT-DATE-YY-N = 0
                  MOVE 400                TO  WS-DIV-BY
               ELSE
                  MOVE 4                  TO  WS-DIV-BY
               END-IF

               DIVIDE WS-EDIT-DATE-CCYY-N
                   BY WS-DIV-BY
               GIVING WS-DIVIDEND
               REMAINDER WS-REMAINDER

               IF WS-REMAINDER = ZEROES
                  CONTINUE
               ELSE
                  SET INPUT-ERROR          TO TRUE
                  SET FLG-DAY-NOT-OK       TO TRUE
                  SET FLG-MONTH-NOT-OK     TO TRUE
                  SET FLG-YEAR-NOT-OK      TO TRUE
                  IF WS-RETURN-MSG-OFF
                  STRING
                    FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                   ':Not a leap year.Cannot have 29 days in this month.'
                    DELIMITED BY SIZE
                   INTO WS-RETURN-MSG
                  END-IF
                  GO TO EDIT-DATE-CCYYMMDD-EXIT
               END-IF
           END-IF

           IF WS-EDIT-DATE-IS-VALID
              CONTINUE
           ELSE
              GO TO EDIT-DATE-CCYYMMDD-EXIT
           END-IF
           .
       EDIT-DAY-MONTH-YEAR-EXIT.
           EXIT
           .

      * Final validation via Language Environment (LE):
      * calls CSUTLDTC subprogram wrapping the LE
      * CEEDAYS callable service. Severity code 0 means
      * valid; non-zero indicates an invalid date that
      * slipped past field-level checks above.
      * See app/cbl/CSUTLDTC.cbl for the LE wrapper.
       EDIT-DATE-LE.
      ******************************************************************
      *    In case some one managed to enter a bad date that passsed all
      *    the edits above ......
      *                  Use LE Services to verify the supplied date
      ******************************************************************
           INITIALIZE WS-DATE-VALIDATION-RESULT
           MOVE 'YYYYMMDD'                   TO WS-DATE-FORMAT

005100     CALL 'CSUTLDTC'
           USING WS-EDIT-DATE-CCYYMMDD
               , WS-DATE-FORMAT
               , WS-DATE-VALIDATION-RESULT

           IF WS-SEVERITY-N = 0
              CONTINUE
           ELSE
              SET INPUT-ERROR                TO TRUE
              SET FLG-DAY-NOT-OK             TO TRUE
              SET FLG-MONTH-NOT-OK           TO TRUE
              SET FLG-YEAR-NOT-OK            TO TRUE
              IF WS-RETURN-MSG-OFF
              STRING
                FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                ' validation error Sev code: '
                WS-SEVERITY
                ' Message code: '
                WS-MSG-NO
                DELIMITED BY SIZE
               INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DATE-LE-EXIT
           END-IF

           IF NOT INPUT-ERROR
              SET FLG-DAY-ISVALID           TO TRUE
           END-IF
           .

       EDIT-DATE-LE-EXIT.
           EXIT
           .
      *    If we got here all edits were cleared
           SET WS-EDIT-DATE-IS-VALID        TO TRUE
           .
       EDIT-DATE-CCYYMMDD-EXIT.
           EXIT
           .

      ******************************************************************
      *Date of Birth Reasonableness check
      ******************************************************************
      *  At the time of writing this program
      *  Time travel was not possible.
      *  Date of birth in the future is not acceptable
      ******************************************************************
      *
      * Date-of-birth reasonableness: converts both the
      * input date and current system date to integer
      * day counts via INTEGER-OF-DATE intrinsic.
      * If input date >= current date the date is in
      * the future and is rejected as invalid.
       EDIT-DATE-OF-BIRTH.

           MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-YYYYMMDD

           COMPUTE WS-EDIT-DATE-BINARY =
               FUNCTION INTEGER-OF-DATE (WS-EDIT-DATE-CCYYMMDD-N)
           COMPUTE WS-CURRENT-DATE-BINARY =
               FUNCTION INTEGER-OF-DATE (WS-CURRENT-DATE-YYYYMMDD-N)

           IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY
      *    IF FUNCTION FIND-DURATION(FUNCTION CURRENT-DATE
      *                             ,WS-EDIT-DATE-CCYYMMDD)
      *                             ,DAYS) > 0
              CONTINUE
           ELSE
              SET INPUT-ERROR                TO TRUE
              SET FLG-DAY-NOT-OK             TO TRUE
              SET FLG-MONTH-NOT-OK           TO TRUE
              SET FLG-YEAR-NOT-OK            TO TRUE
              IF WS-RETURN-MSG-OFF
              STRING
                FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
                ':cannot be in the future '
                DELIMITED BY SIZE
               INTO WS-RETURN-MSG
              END-IF
              GO TO EDIT-DATE-OF-BIRTH-EXIT
           END-IF
           .
       EDIT-DATE-OF-BIRTH-EXIT.
           EXIT
           .
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT
      *
