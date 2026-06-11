package com.cardemo.unit.validation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JVM behavioural-parity unit test for {@link DateValidationService}.
 *
 * <p>This test proves that the migrated Java date-validation service reproduces <em>exactly</em>
 * the observable behaviour of the legacy COBOL date estate, the parity target being the frozen
 * AWS CardDemo sources at commit SHA {@code 27d6c6f}:</p>
 * <ul>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} &mdash; the {@code CALL 'CEEDAYS'} wrapper that returns a
 *       severity-coded "Date is valid" / "Date is invalid" / "Insufficient" result;</li>
 *   <li>{@code app/cpy/CSUTLDPY.cpy} &mdash; the reusable {@code EDIT-DATE-CCYYMMDD} edit cascade
 *       ({@code EDIT-YEAR-CCYY}, {@code EDIT-MONTH}, {@code EDIT-DAY}, {@code EDIT-DAY-MONTH-YEAR},
 *       {@code EDIT-DATE-LE}, {@code EDIT-DATE-OF-BIRTH});</li>
 *   <li>{@code app/cpy/CSUTLDWY.cpy} &mdash; the working-storage 88-level value sets
 *       ({@code WS-VALID-MONTH 1..12}, {@code WS-31-DAY-MONTH 1,3,5,7,8,10,12},
 *       {@code WS-VALID-DAY 1..31}, {@code THIS-CENTURY 20}, {@code LAST-CENTURY 19}).</li>
 * </ul>
 *
 * <p>The COBOL source is read-only reference and is <strong>never copied</strong> into this
 * repository (AAP &sect;0.7.2); only its behaviour &mdash; the same valid/invalid outcomes and the
 * same distinctive failure-message phrases &mdash; is asserted here.</p>
 *
 * <h2>Why this is a plain JUnit 5 test</h2>
 * <p>{@link DateValidationService} depends only on the JDK and a {@link java.time.Clock}, so this is
 * a fast, isolated unit test: <em>no</em> {@code @SpringBootTest}, application context, database,
 * AWS, Testcontainers or Mockito. The service is instantiated directly via its
 * {@link DateValidationService#DateValidationService(Clock)} constructor with a <em>fixed</em>
 * clock so the date-of-birth reasonableness check (Rule G) is deterministic.</p>
 *
 * <h2>Assertion strategy</h2>
 * <p>The COBOL builds each failure message as {@code STRING FUNCTION TRIM(label) <suffix>}; the
 * leading label and inter-token spacing are implementation-rendered. To enforce parity without
 * brittleness this test asserts {@link DateValidationResult#valid()} exactly for every case and,
 * for failures, asserts that {@link DateValidationResult#message()} <em>contains</em> the
 * distinctive, label-independent COBOL suffix phrase. The {@link DateValidationResult#severity()}
 * contract (zero when valid, positive when invalid) is asserted alongside.</p>
 */
class DateValidationServiceTest {

    /**
     * Fixed "today" for the date-of-birth check: 2024-06-15 (UTC). Injecting a fixed
     * {@link Clock} replaces the COBOL {@code FUNCTION CURRENT-DATE} so the future-date rule is
     * deterministic and reproducible on any machine, in any time zone, on any run date.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-06-15T00:00:00Z"), ZoneOffset.UTC);

    /**
     * The system under test. A fixed clock is supplied so date-of-birth assertions are
     * deterministic; the clock is irrelevant to every other method, which is purely functional.
     */
    private final DateValidationService service = new DateValidationService(FIXED_CLOCK);

    // -------------------------------------------------------------------------------------------
    // Shared assertion helpers. These name the nested result type once (the only place it is
    // referenced), keeping the individual tests free of type noise per the agent-prompt guidance.
    // -------------------------------------------------------------------------------------------

    /**
     * Asserts a valid outcome: {@code valid() == true}, severity {@code 0}, and the canonical
     * "Date is valid" text moved by {@code CSUTLDTC} on its {@code FC-INVALID-DATE} (success) branch.
     */
    private static void assertValid(DateValidationResult result) {
        assertThat(result.valid()).as("valid()").isTrue();
        assertThat(result.severity()).as("severity()").isZero();
        assertThat(result.message()).as("message()").isEqualTo("Date is valid");
    }

    /**
     * Asserts an invalid outcome by outcome only (no message coupling): {@code valid() == false}
     * with a positive severity. Used where the agent-prompt specifies outcome-only assertions.
     */
    private static void assertInvalid(DateValidationResult result) {
        assertThat(result.valid()).as("valid()").isFalse();
        assertThat(result.severity()).as("severity()").isPositive();
    }

    /**
     * Asserts an invalid outcome and that the message contains the distinctive COBOL suffix phrase.
     *
     * @param result the result under test
     * @param phrase the label-independent suffix phrase the COBOL paragraph would have produced
     */
    private static void assertInvalidContaining(DateValidationResult result, String phrase) {
        assertThat(result.valid()).as("valid()").isFalse();
        assertThat(result.severity()).as("severity()").isPositive();
        assertThat(result.message()).as("message()").contains(phrase);
    }

    // ===========================================================================================
    // Rule A — validateDate: CEEDAYS-equivalent strict real-calendar check (CSUTLDTC.cbl).
    // Outcome-only assertions; validateDate does NOT enforce the 19xx/20xx century window.
    // ===========================================================================================

    @Nested
    @DisplayName("Rule A — validateDate: CEEDAYS strict real-calendar check (CSUTLDTC)")
    class ValidateDateStrictCalendar {

        @ParameterizedTest(name = "validateDate(\"{0}\") is a real date -> valid")
        @ValueSource(strings = {"20231115", "20240229", "20000229"})
        @DisplayName("Real dates incl. leap 2024-02-29 and mod-400 2000-02-29 are valid")
        void realDatesAreValid(String date) {
            assertValid(service.validateDate(date));
        }

        @ParameterizedTest(name = "validateDate(\"{0}\") is not a real date -> invalid")
        @ValueSource(strings = {"20230229", "19000229", "20231301", "20230230"})
        @DisplayName("Impossible dates (non-leap Feb 29, mod-400 1900-02-29, month 13, Feb 30) are invalid")
        void impossibleDatesAreInvalid(String date) {
            assertInvalid(service.validateDate(date));
        }

        @Test
        @DisplayName("A null date is rejected (CEEDAYS could not validate an absent value)")
        void nullDateIsInvalid() {
            assertInvalid(service.validateDate(null));
        }
    }

    // ===========================================================================================
    // Rule B — century window: only 19xx and 20xx accepted (CSUTLDPY EDIT-YEAR-CCYY).
    // A real calendar year such as 1899 or 2100 is FORBIDDEN by CardDemo and must not leak through
    // LocalDate acceptance. Asserted via the validateCcyymmdd orchestrator and validateYear.
    // ===========================================================================================

    @Nested
    @DisplayName("Rule B — century window: only 19xx/20xx accepted (CSUTLDPY EDIT-YEAR-CCYY)")
    class CenturyWindow {

        @Test
        @DisplayName("18991231 is a real date but century 18 is forbidden (validateCcyymmdd)")
        void year1899RejectedByCenturyWindow() {
            assertInvalidContaining(service.validateCcyymmdd("18991231", "Test Date"),
                    "Century is not valid");
        }

        @Test
        @DisplayName("21000101 is a real date but century 21 is forbidden (validateCcyymmdd)")
        void year2100RejectedByCenturyWindow() {
            assertInvalidContaining(service.validateCcyymmdd("21000101", "Test Date"),
                    "Century is not valid");
        }

        @Test
        @DisplayName("19991231 (century 19) passes the full cascade")
        void year1999PassesCascade() {
            assertValid(service.validateCcyymmdd("19991231", "Test Date"));
        }

        @Test
        @DisplayName("20231115 (century 20) passes the full cascade")
        void year2023PassesCascade() {
            assertValid(service.validateCcyymmdd("20231115", "Test Date"));
        }

        @ParameterizedTest(name = "validateYear(\"{0}\") -> Century is not valid")
        @ValueSource(strings = {"1899", "2100"})
        @DisplayName("validateYear rejects centuries outside 19/20")
        void validateYearRejectsOutOfWindowCenturies(String ccyy) {
            assertInvalidContaining(service.validateYear(ccyy, "Open Date"), "Century is not valid");
        }

        @Test
        @DisplayName("validateYear accepts 2024 (century 20)")
        void validateYearAcceptsInWindow() {
            assertValid(service.validateYear("2024", "Open Date"));
        }
    }

    // ===========================================================================================
    // Rule C — year component (CSUTLDPY EDIT-YEAR-CCYY): supplied, then a 4-digit number.
    // ===========================================================================================

    @Nested
    @DisplayName("Rule C — validateYear component (CSUTLDPY EDIT-YEAR-CCYY)")
    class YearComponent {

        @Test
        @DisplayName("Blank year -> 'Year must be supplied'")
        void blankYearMustBeSupplied() {
            assertInvalidContaining(service.validateYear("", "Open Date"), "Year must be supplied");
        }

        @Test
        @DisplayName("Non-numeric year '20AB' -> 'must be 4 digit number'")
        void nonNumericYearMustBe4Digit() {
            assertInvalidContaining(service.validateYear("20AB", "Open Date"), "must be 4 digit number");
        }
    }

    // ===========================================================================================
    // Rule D — month component (CSUTLDPY EDIT-MONTH / CSUTLDWY WS-VALID-MONTH VALUES 1 THROUGH 12).
    // ===========================================================================================

    @Nested
    @DisplayName("Rule D — validateMonth component (CSUTLDPY EDIT-MONTH, WS-VALID-MONTH 1..12)")
    class MonthComponent {

        @Test
        @DisplayName("Blank month -> 'Month must be supplied'")
        void blankMonthMustBeSupplied() {
            assertInvalidContaining(service.validateMonth("", "Open Date"), "Month must be supplied");
        }

        @ParameterizedTest(name = "validateMonth(\"{0}\") -> out of 1..12")
        @ValueSource(strings = {"00", "13"})
        @DisplayName("Month outside 1..12 -> 'Month must be a number between 1 and 12'")
        void monthOutOfRangeRejected(String mm) {
            assertInvalidContaining(service.validateMonth(mm, "Open Date"),
                    "Month must be a number between 1 and 12");
        }

        @ParameterizedTest(name = "validateMonth(\"{0}\") -> valid")
        @ValueSource(strings = {"01", "12"})
        @DisplayName("Boundary months 01 and 12 are valid")
        void boundaryMonthsAreValid(String mm) {
            assertValid(service.validateMonth(mm, "Open Date"));
        }
    }

    // ===========================================================================================
    // Rule E — day component (CSUTLDPY EDIT-DAY / CSUTLDWY WS-VALID-DAY VALUES 1 THROUGH 31).
    // Month-specific limits (28/29/30) are applied later by validateDayMonthYear (Rule F).
    // ===========================================================================================

    @Nested
    @DisplayName("Rule E — validateDay component (CSUTLDPY EDIT-DAY, WS-VALID-DAY 1..31)")
    class DayComponent {

        @Test
        @DisplayName("Blank day -> 'Day must be supplied'")
        void blankDayMustBeSupplied() {
            assertInvalidContaining(service.validateDay("", "Open Date"), "Day must be supplied");
        }

        @ParameterizedTest(name = "validateDay(\"{0}\") -> out of 1..31")
        @ValueSource(strings = {"00", "32"})
        @DisplayName("Day outside 1..31 -> 'day must be a number between 1 and 31'")
        void dayOutOfRangeRejected(String dd) {
            assertInvalidContaining(service.validateDay(dd, "Open Date"),
                    "day must be a number between 1 and 31");
        }

        @ParameterizedTest(name = "validateDay(\"{0}\") -> valid")
        @ValueSource(strings = {"01", "31"})
        @DisplayName("Boundary days 01 and 31 are valid (component-level range only)")
        void boundaryDaysAreValid(String dd) {
            assertValid(service.validateDay(dd, "Open Date"));
        }
    }

    // ===========================================================================================
    // Rule F — cross-field day/month/year combinations (CSUTLDPY EDIT-DAY-MONTH-YEAR):
    // a 31st in a non-31-day month, the 30th of February, and the 29th of February in a non-leap
    // year. Leap detection follows the COBOL mod-400 (YY==00) / mod-4 rule. Asserted through both
    // validateDayMonthYear(int,int,int,String) and the validateCcyymmdd orchestrator.
    // ===========================================================================================

    @Nested
    @DisplayName("Rule F — cross-field day/month/year (CSUTLDPY EDIT-DAY-MONTH-YEAR)")
    class CrossFieldDayMonthYear {

        @Test
        @DisplayName("April 31 via validateCcyymmdd -> 'Cannot have 31 days in this month'")
        void april31ViaOrchestrator() {
            assertInvalidContaining(service.validateCcyymmdd("20230431", "Test Date"),
                    "Cannot have 31 days in this month");
        }

        @Test
        @DisplayName("April 31 via validateDayMonthYear -> 'Cannot have 31 days in this month'")
        void april31ViaComponent() {
            assertInvalidContaining(service.validateDayMonthYear(2023, 4, 31, "Test Date"),
                    "Cannot have 31 days in this month");
        }

        @Test
        @DisplayName("May 31 is valid (May is a 31-day month)")
        void may31IsValid() {
            assertValid(service.validateCcyymmdd("20230531", "Test Date"));
        }

        @Test
        @DisplayName("February 30 -> 'Cannot have 30 days in this month'")
        void february30Rejected() {
            assertInvalidContaining(service.validateCcyymmdd("20230230", "Test Date"),
                    "Cannot have 30 days in this month");
        }

        @Test
        @DisplayName("February 29 in non-leap 2023 -> 'Not a leap year'")
        void february29NonLeapRejected() {
            assertInvalidContaining(service.validateCcyymmdd("20230229", "Test Date"), "Not a leap year");
        }

        @Test
        @DisplayName("February 29 in leap 2024 is valid")
        void february29LeapValid() {
            assertValid(service.validateCcyymmdd("20240229", "Test Date"));
        }

        @Test
        @DisplayName("February 29 in 2000 is valid (divisible by 400 -> leap)")
        void february29Year2000Valid() {
            assertValid(service.validateDayMonthYear(2000, 2, 29, "Test Date"));
        }

        @Test
        @DisplayName("February 29 in 1900 -> 'Not a leap year' (divisible by 100 but not 400)")
        void february29Year1900Rejected() {
            assertInvalidContaining(service.validateDayMonthYear(1900, 2, 29, "Test Date"),
                    "Not a leap year");
        }
    }

    // ===========================================================================================
    // Rule G — date of birth (CSUTLDPY EDIT-DATE-OF-BIRTH): the full validateCcyymmdd cascade,
    // then the reasonableness rule that a DOB may not lie in the future. COBOL compared
    // CURRENT-DATE-BINARY > EDIT-DATE-BINARY (strictly after), so today itself is also rejected.
    // Deterministic via FIXED_CLOCK = 2024-06-15.
    // ===========================================================================================

    @Nested
    @DisplayName("Rule G — validateDateOfBirth (CSUTLDPY EDIT-DATE-OF-BIRTH); today = 2024-06-15")
    class DateOfBirth {

        @Test
        @DisplayName("A clearly past DOB (1990-01-01) is valid")
        void pastDateOfBirthIsValid() {
            assertValid(service.validateDateOfBirth("19900101", "Date of Birth"));
        }

        @Test
        @DisplayName("DOB equal to today (2024-06-15) is rejected (COBOL requires current > edit)")
        void dateOfBirthEqualToTodayRejected() {
            assertInvalidContaining(service.validateDateOfBirth("20240615", "Date of Birth"),
                    "cannot be in the future");
        }

        @Test
        @DisplayName("DOB tomorrow (2024-06-16) is rejected as in the future")
        void dateOfBirthTomorrowRejected() {
            assertInvalidContaining(service.validateDateOfBirth("20240616", "Date of Birth"),
                    "cannot be in the future");
        }

        @Test
        @DisplayName("DOB far in the future (2030-01-01, still century 20) is rejected")
        void dateOfBirthFarFutureRejected() {
            assertInvalidContaining(service.validateDateOfBirth("20300101", "Date of Birth"),
                    "cannot be in the future");
        }

        @Test
        @DisplayName("DOB that fails the underlying date edit (2023-02-29) is invalid (edit fails first)")
        void dateOfBirthFailingDateEditIsInvalid() {
            assertInvalid(service.validateDateOfBirth("20230229", "Date of Birth"));
        }
    }
}
