package com.cardemo.service.shared;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Spring service replacing COBOL CSUTLDTC.cbl subprogram (157 lines) and
 * CSUTLDPY.cpy date validation paragraphs (376 lines) with CSUTLDWY.cpy
 * working storage (90 lines).
 *
 * <p>Provides comprehensive date validation including:
 * <ol>
 *   <li>CCYYMMDD format parsing and validation</li>
 *   <li>Year/century reasonableness checks (19xx/20xx)</li>
 *   <li>Month validity (1-12)</li>
 *   <li>Day validity (1-31 with month-specific constraints)</li>
 *   <li>Leap year detection and February 29 validation</li>
 *   <li>30/31-day month constraints</li>
 *   <li>Date-of-birth future-date rejection</li>
 *   <li>LE CEEDAYS equivalent validation via {@link java.time.LocalDate}</li>
 * </ol>
 *
 * <p>Maps COBOL {@code CALL 'CSUTLDTC'} to Spring {@code @Autowired DateValidationService}.
 *
 * <p>Source traceability:
 * <ul>
 *   <li>CSUTLDTC.cbl — CardDemo_v1.0-15-g27d6c6f-68</li>
 *   <li>CSUTLDPY.cpy — CardDemo_v1.0-15-g27d6c6f-68</li>
 *   <li>CSUTLDWY.cpy — CardDemo_v1.0-15-g27d6c6f-68</li>
 * </ul>
 *
 * <p>This is the most consumed shared service — used by AccountUpdateService,
 * CardUpdateService, TransactionAddService, and batch processors. It is fully
 * stateless and thread-safe.
 *
 * @see java.time.LocalDate
 */
@Service
public class DateValidationService {

    private static final Logger logger = LoggerFactory.getLogger(DateValidationService.class);

    /**
     * Months that have 31 days.
     * Mirrors COBOL 88-level condition WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12
     * from CSUTLDWY.cpy lines 21-23.
     */
    private static final Set<Integer> MONTHS_WITH_31_DAYS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /** February month constant — mirrors COBOL WS-FEBRUARY VALUE 2 (CSUTLDWY.cpy line 24). */
    private static final int FEBRUARY = 2;

    /** Minimum valid century — mirrors COBOL LAST-CENTURY VALUE 19 (CSUTLDWY.cpy line 10). */
    private static final int LAST_CENTURY = 19;

    /** Maximum valid century — mirrors COBOL THIS-CENTURY VALUE 20 (CSUTLDWY.cpy line 9). */
    private static final int THIS_CENTURY = 20;

    /** Required length for CCYYMMDD date strings. */
    private static final int CCYYMMDD_LENGTH = 8;

    /**
     * STRICT mode DateTimeFormatter for CCYYMMDD format.
     * Uses 'uuuu' (not 'yyyy') for proper STRICT resolver behavior with
     * {@link ResolverStyle#STRICT}. This replaces the LE CEEDAYS API for
     * final date validation (EDIT-DATE-LE paragraph, CSUTLDPY.cpy lines 284-331).
     */
    private static final DateTimeFormatter CCYYMMDD_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);

    /** CEEDAYS feedback result text for a valid date (severity 0). */
    private static final String CEEDAYS_VALID = "Date is valid";

    /**
     * Validation result preserving COBOL FEEDBACK-CODE semantics.
     *
     * <p>Mirrors the WS-MESSAGE (80-byte) structure from CSUTLDTC.cbl (lines 42-57)
     * and the WS-EDIT-DATE-FLGS from CSUTLDWY.cpy (lines 43-57). Callers can
     * inspect the severity code AND individual field validity flags to determine
     * the exact nature of a validation failure.
     *
     * @param valid         true if date passed all validation checks
     * @param severityCode  severity code: 0 = success, greater than 0 = error
     *                      (from COBOL WS-SEVERITY-N)
     * @param resultMessage 15-char result text (from COBOL WS-RESULT), e.g.
     *                      "Date is valid", "Datevalue error", "Invalid month"
     * @param fullMessage   complete validation message matching COBOL WS-MESSAGE
     *                      80-byte layout or WS-RETURN-MSG error text
     * @param yearValid     FLG-YEAR-ISVALID equivalent (CSUTLDWY.cpy line 47)
     * @param monthValid    FLG-MONTH-ISVALID equivalent (CSUTLDWY.cpy line 51)
     * @param dayValid      FLG-DAY-ISVALID equivalent (CSUTLDWY.cpy line 55)
     */
    public record DateValidationResult(
            boolean valid,
            int severityCode,
            String resultMessage,
            String fullMessage,
            boolean yearValid,
            boolean monthValid,
            boolean dayValid
    ) { }

    /**
     * Validates a date string in CCYYMMDD format.
     *
     * <p>Equivalent to COBOL: {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT}
     * (CSUTLDPY.cpy lines 18-331).
     *
     * <p>Validation cascade (mirrors CSUTLDPY.cpy paragraph flow):
     * <ol>
     *   <li>EDIT-YEAR-CCYY — year/century validation (lines 25-90)</li>
     *   <li>EDIT-MONTH — month validation 1-12 (lines 91-147)</li>
     *   <li>EDIT-DAY — day validation 1-31 (lines 150-207)</li>
     *   <li>EDIT-DAY-MONTH-YEAR — combination validation: 31-day, Feb 30, leap year
     *       (lines 209-282)</li>
     *   <li>EDIT-DATE-LE — final LocalDate.parse validation replacing LE CEEDAYS
     *       (lines 284-331)</li>
     * </ol>
     *
     * @param dateStr      date string in CCYYMMDD format (e.g., "20230115")
     * @param variableName the field name for error messages (maps to COBOL
     *                     WS-EDIT-VARIABLE-NAME used in STRING operations)
     * @return DateValidationResult with validity flags and messages
     */
    public DateValidationResult validateDate(String dateStr, String variableName) {
        logger.debug("Validating date: dateStr='{}', variableName='{}'", dateStr, variableName);

        // Trim variable name — mirrors COBOL FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
        String varName = (variableName != null) ? variableName.trim() : "";

        // ────────────────────────────────────────────────────────────────────
        // Null/blank/length check before component extraction.
        // If the input is null, blank, or not exactly 8 characters, the year
        // component cannot be extracted, so we report a year error immediately.
        // ────────────────────────────────────────────────────────────────────
        if (dateStr == null || dateStr.isBlank() || dateStr.length() != CCYYMMDD_LENGTH) {
            String msg = varName + " : Year must be supplied.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Insufficient", msg,
                    false, false, false);
        }

        // Extract CCYYMMDD components — mirrors CSUTLDWY.cpy field definitions
        String ccyy = dateStr.substring(0, 4); // WS-EDIT-DATE-CCYY
        String cc   = dateStr.substring(0, 2); // WS-EDIT-DATE-CC
        String yy   = dateStr.substring(2, 4); // WS-EDIT-DATE-YY
        String mm   = dateStr.substring(4, 6); // WS-EDIT-DATE-MM
        String dd   = dateStr.substring(6, 8); // WS-EDIT-DATE-DD

        // ────────────────────────────────────────────────────────────────────
        // Step 1: EDIT-YEAR-CCYY equivalent (CSUTLDPY.cpy lines 25-90)
        // Validates the 4-digit year with century reasonableness.
        // ────────────────────────────────────────────────────────────────────

        // Blank check — mirrors CSUTLDPY.cpy lines 30-45
        if (ccyy.isBlank()) {
            String msg = varName + " : Year must be supplied.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Insufficient", msg,
                    false, false, false);
        }

        // Numeric check — mirrors CSUTLDPY.cpy lines 48-61
        // COBOL: IF WS-EDIT-DATE-CCYY IS NOT NUMERIC
        if (!isNumeric(ccyy)) {
            String msg = varName + " must be 4 digit number.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Nonnumeric data", msg,
                    false, false, false);
        }

        // Century check — mirrors CSUTLDPY.cpy lines 70-84
        // COBOL: IF THIS-CENTURY OR LAST-CENTURY → CONTINUE, ELSE → error
        // Only centuries 19 and 20 are valid
        int ccNum = Integer.parseInt(cc);
        if (ccNum != THIS_CENTURY && ccNum != LAST_CENTURY) {
            String msg = varName + " : Century is not valid.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Invalid Era", msg,
                    false, false, false);
        }

        // Year validated — mirrors SET FLG-YEAR-ISVALID TO TRUE (line 86)
        boolean yearValid = true;

        // ────────────────────────────────────────────────────────────────────
        // Step 2: EDIT-MONTH equivalent (CSUTLDPY.cpy lines 91-147)
        // Validates the 2-digit month field (01-12).
        // ────────────────────────────────────────────────────────────────────

        // Blank check — mirrors CSUTLDPY.cpy lines 94-108
        if (mm.isBlank()) {
            String msg = varName + " : Month must be supplied.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Insufficient", msg,
                    yearValid, false, false);
        }

        // Numeric and range check — mirrors CSUTLDPY.cpy lines 111-141
        // COBOL checks WS-VALID-MONTH (VALUES 1 THROUGH 12) then TEST-NUMVAL;
        // both error paths use the same message text.
        int monthNum;
        try {
            monthNum = Integer.parseInt(mm);
        } catch (NumberFormatException e) {
            String msg = varName + ": Month must be a number between 1 and 12.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Nonnumeric data", msg,
                    yearValid, false, false);
        }

        if (monthNum < 1 || monthNum > 12) {
            String msg = varName + ": Month must be a number between 1 and 12.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Invalid month", msg,
                    yearValid, false, false);
        }

        // Month validated — mirrors SET FLG-MONTH-ISVALID TO TRUE (line 143)
        boolean monthValid = true;

        // ────────────────────────────────────────────────────────────────────
        // Step 3: EDIT-DAY equivalent (CSUTLDPY.cpy lines 150-207)
        // Validates the 2-digit day field (01-31).
        // ────────────────────────────────────────────────────────────────────

        // Blank check — mirrors CSUTLDPY.cpy lines 154-168
        if (dd.isBlank()) {
            String msg = varName + " : Day must be supplied.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Insufficient", msg,
                    yearValid, monthValid, false);
        }

        // Numeric check — mirrors CSUTLDPY.cpy lines 170-185 (FUNCTION TEST-NUMVAL)
        int dayNum;
        try {
            dayNum = Integer.parseInt(dd);
        } catch (NumberFormatException e) {
            String msg = varName + ":day must be a number between 1 and 31.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Nonnumeric data", msg,
                    yearValid, monthValid, false);
        }

        // Range check — mirrors CSUTLDPY.cpy lines 187-200
        // COBOL: 88 WS-VALID-DAY VALUES 1 THROUGH 31.
        if (dayNum < 1 || dayNum > 31) {
            String msg = varName + ":day must be a number between 1 and 31.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Datevalue error", msg,
                    yearValid, monthValid, false);
        }

        // Day validated — mirrors SET FLG-DAY-ISVALID TO TRUE (line 203)
        boolean dayValid = true;

        // ────────────────────────────────────────────────────────────────────
        // Step 4: EDIT-DAY-MONTH-YEAR equivalent (CSUTLDPY.cpy lines 209-282)
        // Combination validation: 31-day months, February 30, leap year Feb 29.
        // ────────────────────────────────────────────────────────────────────

        // 31-day month check — mirrors CSUTLDPY.cpy lines 213-226
        // COBOL: IF NOT WS-31-DAY-MONTH AND WS-DAY-31
        // Sets FLG-DAY-NOT-OK and FLG-MONTH-NOT-OK (but NOT FLG-YEAR-NOT-OK)
        if (!MONTHS_WITH_31_DAYS.contains(monthNum) && dayNum == 31) {
            String msg = varName + ":Cannot have 31 days in this month.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Datevalue error", msg,
                    yearValid, false, false);
        }

        // February 30 check — mirrors CSUTLDPY.cpy lines 228-241
        // COBOL: IF WS-FEBRUARY AND WS-DAY-30
        // Sets FLG-DAY-NOT-OK and FLG-MONTH-NOT-OK
        if (monthNum == FEBRUARY && dayNum == 30) {
            String msg = varName + ":Cannot have 30 days in this month.";
            logger.warn("Date validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Datevalue error", msg,
                    yearValid, false, false);
        }

        // February 29 leap year check — mirrors CSUTLDPY.cpy lines 243-272
        // COBOL leap year algorithm:
        //   IF WS-EDIT-DATE-YY-N = 0 (century year, e.g. 1900, 2000)
        //     MOVE 400 TO WS-DIV-BY
        //   ELSE
        //     MOVE 4 TO WS-DIV-BY
        //   END-IF
        //   DIVIDE WS-EDIT-DATE-CCYY-N BY WS-DIV-BY ... REMAINDER
        //   IF WS-REMAINDER = ZEROES → leap year
        // This correctly implements the Gregorian calendar:
        //   2000 → 2000 % 400 = 0 → leap year
        //   1900 → 1900 % 400 = 300 → NOT leap year
        //   2024 → 2024 % 4 = 0 → leap year
        //   2023 → 2023 % 4 = 3 → NOT leap year
        if (monthNum == FEBRUARY && dayNum == 29) {
            int yearNum = Integer.parseInt(ccyy);
            int yyNum = Integer.parseInt(yy);
            int divisor = (yyNum == 0) ? 400 : 4;
            if (yearNum % divisor != 0) {
                // Not a leap year — COBOL error message has NO space between
                // "year." and "Cannot" (CSUTLDPY.cpy line 266)
                // Sets FLG-DAY-NOT-OK, FLG-MONTH-NOT-OK, FLG-YEAR-NOT-OK
                String msg = varName
                        + ":Not a leap year.Cannot have 29 days in this month.";
                logger.warn("Date validation failed: {}", msg);
                return new DateValidationResult(false, 4, "Datevalue error", msg,
                        false, false, false);
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // Step 5: EDIT-DATE-LE equivalent (CSUTLDPY.cpy lines 284-331)
        // Final validation using java.time.LocalDate with STRICT resolver.
        // Replaces the LE CEEDAYS service call:
        //   CALL 'CSUTLDTC' USING WS-EDIT-DATE-CCYYMMDD, WS-DATE-FORMAT,
        //                         WS-DATE-VALIDATION-RESULT
        // This catches any edge-case dates that passed the manual checks above.
        // ────────────────────────────────────────────────────────────────────
        try {
            LocalDate.parse(dateStr, CCYYMMDD_FORMATTER);
        } catch (DateTimeParseException e) {
            // Mirrors CSUTLDPY.cpy lines 298-316: severity and message code error
            String msg = varName + " validation error Sev code: 0003 Message code: 0000";
            logger.warn("Date validation failed (LE equivalent): {} — cause: {}",
                    msg, e.getMessage());
            return new DateValidationResult(false, 3, "Datevalue error", msg,
                    false, false, false);
        }

        // ────────────────────────────────────────────────────────────────────
        // Step 6: All validations passed.
        // Mirrors SET WS-EDIT-DATE-IS-VALID TO TRUE (CSUTLDPY.cpy line 327).
        // ────────────────────────────────────────────────────────────────────
        String fullMessage = buildCeedaysMessage(0, 0, CEEDAYS_VALID, dateStr, "YYYYMMDD");
        logger.info("Date validation successful: dateStr='{}'", dateStr);
        return new DateValidationResult(true, 0, CEEDAYS_VALID, fullMessage,
                true, true, true);
    }

    /**
     * Validates a date and checks it is not in the future (date-of-birth validation).
     *
     * <p>Equivalent to COBOL: EDIT-DATE-OF-BIRTH paragraph (CSUTLDPY.cpy lines 341-372).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Validate date format via {@link #validateDate(String, String)}</li>
     *   <li>Get current date (mirrors COBOL {@code FUNCTION CURRENT-DATE})</li>
     *   <li>Compare dates using integer-of-date (mirrors COBOL
     *       {@code FUNCTION INTEGER-OF-DATE})</li>
     *   <li>If current date is after or equal to edit date → valid (past or today)</li>
     *   <li>If current date is before edit date → error "cannot be in the future"</li>
     * </ol>
     *
     * @param dateStr      date string in CCYYMMDD format
     * @param variableName the field name for error messages
     * @return DateValidationResult with DOB-specific messaging
     */
    public DateValidationResult validateDateOfBirth(String dateStr, String variableName) {
        logger.debug("Validating date of birth: dateStr='{}', variableName='{}'",
                dateStr, variableName);

        // First validate the date format — equivalent to calling validateDate before DOB check
        DateValidationResult dateResult = validateDate(dateStr, variableName);
        if (!dateResult.valid()) {
            return dateResult;
        }

        String varName = (variableName != null) ? variableName.trim() : "";

        // Parse the validated date — safe since validateDate already confirmed validity
        LocalDate editDate = LocalDate.parse(dateStr, CCYYMMDD_FORMATTER);

        // Get current date — mirrors FUNCTION CURRENT-DATE (CSUTLDPY.cpy line 343)
        LocalDate currentDate = LocalDate.now();

        // Compare dates — mirrors CSUTLDPY.cpy lines 350-368
        // COBOL: IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY → CONTINUE
        // Current date is after or equal to edit date → date is today or in the past → valid
        if (currentDate.isAfter(editDate) || currentDate.isEqual(editDate)) {
            logger.info("Date of birth validation successful: dateStr='{}'", dateStr);
            return dateResult;
        }

        // Date is in the future — mirrors CSUTLDPY.cpy lines 356-367
        // COBOL: IF WS-CURRENT-DATE-BINARY < WS-EDIT-DATE-BINARY (date is in the future)
        // Explicit isBefore check for defensive clarity (inverse of the isAfter||isEqual above)
        if (currentDate.isBefore(editDate)) {
            // COBOL: STRING variable-name ':cannot be in the future ' DELIMITED BY SIZE
            // Note: the COBOL message has a trailing space — preserved exactly
            // Sets FLG-DAY-NOT-OK, FLG-MONTH-NOT-OK, FLG-YEAR-NOT-OK
            String msg = varName + ":cannot be in the future ";
            logger.warn("Date of birth validation failed: {}", msg);
            return new DateValidationResult(false, 4, "Datevalue error", msg,
                    false, false, false);
        }

        // Defensive fallback — should never reach here since isAfter||isEqual||isBefore
        // covers all cases, but included for robustness
        logger.info("Date of birth validation successful: dateStr='{}'", dateStr);
        return dateResult;
    }

    /**
     * Validates a date using format mask, equivalent to COBOL {@code CALL 'CSUTLDTC'}.
     * This is the direct Java replacement for the LE CEEDAYS service.
     *
     * <p>Mirrors CSUTLDTC.cbl PROCEDURE DIVISION (lines 88-154):
     * <ul>
     *   <li>Accepts a date string and format mask</li>
     *   <li>Returns a result with severity code and message matching the 80-byte
     *       WS-MESSAGE layout</li>
     *   <li>Maps CEEDAYS FEEDBACK-CODE conditions to result messages</li>
     * </ul>
     *
     * <p>Supported format masks:
     * <ul>
     *   <li>{@code YYYYMMDD} — primary format used by CSUTLDPY.cpy</li>
     *   <li>{@code YYYY-MM-DD} — ISO date format</li>
     *   <li>{@code MMDDYYYY} — US date format</li>
     *   <li>{@code MM/DD/YYYY} — US date format with separators</li>
     * </ul>
     *
     * @param dateStr    the date string to validate (up to 10 characters)
     * @param formatMask the date format mask (e.g., "YYYYMMDD")
     * @return DateValidationResult with CEEDAYS-equivalent severity codes and
     *         the 80-byte WS-MESSAGE formatted fullMessage
     */
    public DateValidationResult validateWithCeedays(String dateStr, String formatMask) {
        logger.debug("Validating with CEEDAYS equivalent: dateStr='{}', formatMask='{}'",
                dateStr, formatMask);

        String dateTrimmed = (dateStr != null) ? dateStr.trim() : "";
        String maskTrimmed = (formatMask != null) ? formatMask.trim() : "";

        // Handle null/empty input — mirrors FC-INSUFFICIENT-DATA feedback code
        // (CSUTLDTC.cbl line 63: 88 FC-INSUFFICIENT-DATA)
        if (dateTrimmed.isEmpty()) {
            String fullMsg = buildCeedaysMessage(3, 2507, "Insufficient",
                    dateTrimmed, maskTrimmed);
            logger.warn("CEEDAYS validation failed: Insufficient data");
            return new DateValidationResult(false, 3, "Insufficient", fullMsg,
                    false, false, false);
        }

        // Handle empty format mask — mirrors FC-BAD-PIC-STRING feedback code
        // (CSUTLDTC.cbl line 68: 88 FC-BAD-PIC-STRING)
        if (maskTrimmed.isEmpty()) {
            String fullMsg = buildCeedaysMessage(3, 2518, "Bad Pic String",
                    dateTrimmed, maskTrimmed);
            logger.warn("CEEDAYS validation failed: Bad picture string (empty mask)");
            return new DateValidationResult(false, 3, "Bad Pic String", fullMsg,
                    false, false, false);
        }

        // Map COBOL format mask to Java DateTimeFormatter pattern
        DateTimeFormatter formatter = mapFormatMask(maskTrimmed);
        if (formatter == null) {
            // Unsupported mask — mirrors FC-BAD-PIC-STRING
            String fullMsg = buildCeedaysMessage(3, 2518, "Bad Pic String",
                    dateTrimmed, maskTrimmed);
            logger.warn("CEEDAYS validation failed: Unsupported format mask '{}'",
                    maskTrimmed);
            return new DateValidationResult(false, 3, "Bad Pic String", fullMsg,
                    false, false, false);
        }

        // Check for non-numeric data in numeric-only formats
        // Mirrors FC-NON-NUMERIC-DATA (CSUTLDTC.cbl line 69)
        if ("YYYYMMDD".equalsIgnoreCase(maskTrimmed) && !isNumeric(dateTrimmed)) {
            String fullMsg = buildCeedaysMessage(3, 2520, "Nonnumeric data",
                    dateTrimmed, maskTrimmed);
            logger.warn("CEEDAYS validation failed: Non-numeric data in '{}'",
                    dateTrimmed);
            return new DateValidationResult(false, 3, "Nonnumeric data", fullMsg,
                    false, false, false);
        }

        // Try parsing — replaces actual CEEDAYS call to get Lillian date
        // (CSUTLDTC.cbl lines 116-120)
        try {
            LocalDate.parse(dateTrimmed, formatter);
        } catch (DateTimeParseException e) {
            // Map the parse exception to the closest CEEDAYS feedback code
            String resultText = mapParseExceptionToResult(dateTrimmed, maskTrimmed, e);
            int msgNo = mapResultToMsgNo(resultText);
            String fullMsg = buildCeedaysMessage(3, msgNo, resultText,
                    dateTrimmed, maskTrimmed);
            logger.warn("CEEDAYS validation failed: {} for dateStr='{}', formatMask='{}'",
                    resultText, dateTrimmed, maskTrimmed);
            return new DateValidationResult(false, 3, resultText, fullMsg,
                    false, false, false);
        }

        // Success — mirrors FC-INVALID-DATE (CSUTLDTC.cbl line 62)
        // Note: FC-INVALID-DATE at severity 0 means the date IS valid
        // (confusing COBOL naming: the feedback code name means "no error with the date")
        String fullMsg = buildCeedaysMessage(0, 0, CEEDAYS_VALID,
                dateTrimmed, maskTrimmed);
        logger.info("CEEDAYS validation successful: dateStr='{}', formatMask='{}'",
                dateTrimmed, maskTrimmed);
        return new DateValidationResult(true, 0, CEEDAYS_VALID, fullMsg,
                true, true, true);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Private helper methods
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks if a string contains only ASCII digit characters ('0'-'9').
     * Equivalent to COBOL {@code IS NUMERIC} test and
     * {@code FUNCTION TEST-NUMVAL} for integer data.
     *
     * @param str the string to test
     * @return true if every character is a digit; false if null, empty, or
     *         contains non-digit characters
     */
    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Maps COBOL date format masks to Java {@link DateTimeFormatter} patterns.
     * Mirrors CSUTLDTC.cbl VSTRING format mask handling where the format
     * string is passed to the CEEDAYS API.
     *
     * <p>All returned formatters use {@link ResolverStyle#STRICT} to match
     * the rigorous validation behavior of the LE CEEDAYS service.
     *
     * @param formatMask the COBOL-style format mask string
     * @return a DateTimeFormatter with STRICT resolver, or null for unsupported masks
     */
    private static DateTimeFormatter mapFormatMask(String formatMask) {
        return switch (formatMask.toUpperCase()) {
            case "YYYYMMDD" -> DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);
            case "YYYY-MM-DD" -> DateTimeFormatter.ofPattern("uuuu-MM-dd")
                    .withResolverStyle(ResolverStyle.STRICT);
            case "MMDDYYYY" -> DateTimeFormatter.ofPattern("MMdduuuu")
                    .withResolverStyle(ResolverStyle.STRICT);
            case "MM/DD/YYYY" -> DateTimeFormatter.ofPattern("MM/dd/uuuu")
                    .withResolverStyle(ResolverStyle.STRICT);
            default -> null;
        };
    }

    /**
     * Builds the 80-byte WS-MESSAGE string matching CSUTLDTC.cbl WS-MESSAGE layout.
     *
     * <p>Layout (from CSUTLDTC.cbl lines 42-57):
     * <pre>
     * WS-SEVERITY    PIC X(04)     — 4 bytes
     * FILLER         PIC X(11)     — "Mesg Code: " (VALUE 'Mesg Code:' right-padded to 11)
     * WS-MSG-NO      PIC X(04)     — 4 bytes
     * FILLER         PIC X(01)     — SPACE
     * WS-RESULT      PIC X(15)     — 15 bytes
     * FILLER         PIC X(01)     — SPACE
     * FILLER         PIC X(09)     — "TstDate: " (VALUE 'TstDate:' right-padded to 9)
     * WS-DATE        PIC X(10)     — 10 bytes
     * FILLER         PIC X(01)     — SPACE
     * FILLER         PIC X(10)     — "Mask used:" (VALUE 'Mask used:' exact fit)
     * WS-DATE-FMT    PIC X(10)     — 10 bytes
     * FILLER         PIC X(01)     — SPACE
     * FILLER         PIC X(03)     — SPACES
     * Total: 80 bytes
     * </pre>
     *
     * @param severity   numeric severity code (0-9999)
     * @param msgNo      message number (0-9999)
     * @param result     result text (up to 15 characters)
     * @param dateStr    date string (up to 10 characters)
     * @param formatMask format mask (up to 10 characters)
     * @return formatted 80-character message string
     */
    private static String buildCeedaysMessage(int severity, int msgNo,
                                               String result, String dateStr,
                                               String formatMask) {
        String sevStr = String.format("%04d", severity);
        String msgStr = String.format("%04d", msgNo);
        String resStr = padRight(result, 15);
        String dateField = padRight(dateStr, 10);
        String maskField = padRight(formatMask, 10);

        // Assemble the 80-byte message matching the COBOL record layout exactly
        return sevStr                    // WS-SEVERITY    PIC X(04) = 4
                + "Mesg Code: "          // FILLER         PIC X(11) = 11
                + msgStr                 // WS-MSG-NO      PIC X(04) = 4
                + " "                    // FILLER         PIC X(01) = 1
                + resStr                 // WS-RESULT      PIC X(15) = 15
                + " "                    // FILLER         PIC X(01) = 1
                + "TstDate: "            // FILLER         PIC X(09) = 9
                + dateField              // WS-DATE        PIC X(10) = 10
                + " "                    // FILLER         PIC X(01) = 1
                + "Mask used:"           // FILLER         PIC X(10) = 10
                + maskField              // WS-DATE-FMT    PIC X(10) = 10
                + " "                    // FILLER         PIC X(01) = 1
                + "   ";                 // FILLER         PIC X(03) = 3
        // Total: 4+11+4+1+15+1+9+10+1+10+10+1+3 = 80
    }

    /**
     * Right-pads a string to the specified length with spaces.
     * Mirrors COBOL PIC X(n) field behavior where values shorter than the
     * field length are space-padded on the right.
     *
     * @param str    the string to pad (may be null)
     * @param length the target length
     * @return a string of exactly {@code length} characters
     */
    private static String padRight(String str, int length) {
        if (str == null) {
            return " ".repeat(length);
        }
        if (str.length() >= length) {
            return str.substring(0, length);
        }
        return str + " ".repeat(length - str.length());
    }

    /**
     * Maps a {@link DateTimeParseException} to the closest CEEDAYS feedback
     * result text. Mirrors CSUTLDTC.cbl EVALUATE TRUE (lines 128-149)
     * feedback code mapping.
     *
     * @param dateStr    the date string that caused the exception
     * @param formatMask the format mask used
     * @param e          the parse exception
     * @return a 15-character (max) result text matching COBOL WS-RESULT values
     */
    private static String mapParseExceptionToResult(String dateStr, String formatMask,
                                                     DateTimeParseException e) {
        String message = e.getMessage().toLowerCase();

        // Attempt to determine the specific error type from the exception message
        if (message.contains("monthofyear") || message.contains("month")) {
            return "Invalid month";
        }
        if (message.contains("year")) {
            return "Invalid Era";
        }
        if (message.contains("could not be parsed")) {
            // General parse failure — check if data is non-numeric for numeric format
            if ("YYYYMMDD".equalsIgnoreCase(formatMask) && !isNumeric(dateStr)) {
                return "Nonnumeric data";
            }
            return "Datevalue error";
        }
        // Default — mirrors WHEN OTHER → 'Date is invalid' (CSUTLDTC.cbl line 148)
        return "Date is invalid";
    }

    /**
     * Maps a result text to the corresponding CEEDAYS message number.
     * Derived from CSUTLDTC.cbl feedback code hex values (lines 62-70):
     * <ul>
     *   <li>FC-INVALID-DATE → 0 (success)</li>
     *   <li>FC-INSUFFICIENT-DATA → 2507</li>
     *   <li>FC-BAD-DATE-VALUE → 2508</li>
     *   <li>FC-INVALID-ERA → 2509</li>
     *   <li>FC-UNSUPP-RANGE → 2513</li>
     *   <li>FC-INVALID-MONTH → 2517</li>
     *   <li>FC-BAD-PIC-STRING → 2518</li>
     *   <li>FC-NON-NUMERIC-DATA → 2520</li>
     *   <li>FC-YEAR-IN-ERA-ZERO → 2521</li>
     * </ul>
     *
     * @param resultText the result text string
     * @return the CEEDAYS message number
     */
    private static int mapResultToMsgNo(String resultText) {
        return switch (resultText) {
            case "Date is valid"   -> 0;
            case "Insufficient"    -> 2507;
            case "Datevalue error" -> 2508;
            case "Invalid Era"     -> 2509;
            case "Unsupp. Range"   -> 2513;
            case "Invalid month"   -> 2517;
            case "Bad Pic String"  -> 2518;
            case "Nonnumeric data" -> 2520;
            case "YearInEra is 0"  -> 2521;
            default                -> 9999;
        };
    }
}
