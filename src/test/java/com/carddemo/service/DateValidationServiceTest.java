package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.exception.DateValidationException;

/**
 * Pure, fast unit test for {@link DateValidationService}, the {@code CCYYMMDD}
 * date validator migrated from the COBOL LE date-utility program
 * {@code CSUTLDTC} and the {@code CSUTLDPY}/{@code CSUTLDWY} edit copybooks
 * ({@code app/cbl/CSUTLDTC.cbl}, frozen reference SHA {@code 27d6c6f} &mdash;
 * read-only, not copied into this repository).
 *
 * <p>The service is pure (no collaborators, no I/O), so it is exercised directly
 * with no Spring context, database, Testcontainers, Docker, or live AWS. The
 * ordered {@code EDIT-DATE-CCYYMMDD} edit chain is walked branch by branch &mdash;
 * length/supplied/numeric/century for the year, supplied/numeric/range for the
 * month and day, and the combined day-in-month and leap-year rules &mdash;
 * asserting both the {@link DateValidationException} type and the verbatim
 * message text so the external message contract (Gate&nbsp;5) is preserved. The
 * non-throwing predicate wrapper and the field-name prefixing are covered too.</p>
 */
@DisplayName("DateValidationService — COBOL CSUTLDTC/CSUTLDPY date edits (SHA 27d6c6f)")
class DateValidationServiceTest {

    private DateValidationService service;

    @BeforeEach
    void setUp() {
        service = new DateValidationService();
    }

    @Nested
    @DisplayName("Valid dates")
    class Valid {

        @Test
        @DisplayName("ordinary date parses to the exact LocalDate")
        void ordinary() {
            assertThat(service.validateDateCcyyMmDd("20240115"))
                    .isEqualTo(LocalDate.of(2024, 1, 15));
        }

        @Test
        @DisplayName("Feb 29 in a leap year is accepted")
        void leapDay() {
            assertThat(service.validateDateCcyyMmDd("20240229"))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("year 2000 Feb 29 accepted (century divisor 400 branch)")
        void year2000LeapDay() {
            assertThat(service.validateDateCcyyMmDd("20000229"))
                    .isEqualTo(LocalDate.of(2000, 2, 29));
        }

        @Test
        @DisplayName("31st of a 31-day month accepted")
        void lastDayOf31DayMonth() {
            assertThat(service.validateDateCcyyMmDd("20240131"))
                    .isEqualTo(LocalDate.of(2024, 1, 31));
        }
    }

    @Nested
    @DisplayName("Year edits — EDIT-YEAR-CCYY")
    class YearEdits {

        @Test
        @DisplayName("null → Year must be supplied")
        void nullDate() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd(null))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Year must be supplied");
        }

        @Test
        @DisplayName("wrong length → Year must be supplied")
        void wrongLength() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("2024011"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Year must be supplied");
        }

        @Test
        @DisplayName("blank year → Year must be supplied")
        void yearBlank() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("    0115"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Year must be supplied");
        }

        @Test
        @DisplayName("non-numeric year → must be 4 digit number")
        void yearNotNumeric() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20X40115"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("4 digit number");
        }

        @Test
        @DisplayName("century other than 19/20 → Century is not valid")
        void centuryInvalid() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("18990101"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Century is not valid");
        }
    }

    @Nested
    @DisplayName("Month edits — EDIT-MONTH")
    class MonthEdits {

        @Test
        @DisplayName("blank month → Month must be supplied")
        void monthBlank() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("2024  15"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Month must be supplied");
        }

        @Test
        @DisplayName("non-numeric month → month range message")
        void monthNotNumeric() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("2024XX15"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Month must be a number between 1 and 12");
        }

        @Test
        @DisplayName("month 13 → month range message")
        void monthOutOfRange() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20241315"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Month must be a number between 1 and 12");
        }
    }

    @Nested
    @DisplayName("Day edits — EDIT-DAY")
    class DayEdits {

        @Test
        @DisplayName("blank day → Day must be supplied")
        void dayBlank() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("202401  "))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Day must be supplied");
        }

        @Test
        @DisplayName("non-numeric day → day range message")
        void dayNotNumeric() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("202401XX"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("day must be a number between 1 and 31");
        }

        @Test
        @DisplayName("day 32 → day range message")
        void dayOutOfRange() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20240132"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("day must be a number between 1 and 31");
        }
    }

    @Nested
    @DisplayName("Combined day-in-month / leap-year edits")
    class DayInMonthEdits {

        @Test
        @DisplayName("31st of a 30-day month → cannot have 31 days")
        void thirtyOneInThirtyDayMonth() {
            // April has 30 days.
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20240431"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Cannot have 31 days");
        }

        @Test
        @DisplayName("Feb 30 → cannot have 30 days")
        void februaryThirty() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20240230"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Cannot have 30 days");
        }

        @Test
        @DisplayName("Feb 29 in a non-leap year → not a leap year")
        void februaryTwentyNineNonLeap() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20230229"))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageContaining("Not a leap year");
        }
    }

    @Nested
    @DisplayName("Predicate wrapper + field-name prefix + carried value")
    class WrapperAndExtras {

        @Test
        @DisplayName("isValidDateCcyyMmDd → true for a valid date")
        void predicateTrue() {
            assertThat(service.isValidDateCcyyMmDd("20240115")).isTrue();
        }

        @Test
        @DisplayName("isValidDateCcyyMmDd → false for an invalid date")
        void predicateFalse() {
            assertThat(service.isValidDateCcyyMmDd("20240230")).isFalse();
        }

        @Test
        @DisplayName("field name is trimmed and prefixed onto the message")
        void fieldNamePrefixed() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("18990101", "  Birth  "))
                    .isInstanceOf(DateValidationException.class)
                    .hasMessageStartingWith("Birth")
                    .hasMessageContaining("Century is not valid");
        }

        @Test
        @DisplayName("the offending raw value is carried on the exception")
        void carriesInvalidValue() {
            assertThatThrownBy(() -> service.validateDateCcyyMmDd("20241315"))
                    .isInstanceOf(DateValidationException.class)
                    .extracting(ex -> ((DateValidationException) ex).getInvalidValue())
                    .isEqualTo("20241315");
        }
    }
}
