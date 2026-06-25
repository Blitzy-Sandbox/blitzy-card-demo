package com.carddemo.unit.dto;

import com.carddemo.dto.ReportDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit test for {@link ReportDto} and its nested
 * {@link ReportDto.SubmitRequest} record (REST body for
 * {@code POST /api/reports/submit}, replacing CICS transaction {@code CR00} /
 * program {@code CORPT00C}).
 *
 * <p>This test uses a standalone jakarta Bean Validation {@link Validator}
 * only — no Spring, Testcontainers, or Mockito. It guards the byte-accurate
 * field contract derived from BMS mapset {@code app/bms/CORPT00.bms} and program
 * {@code app/cbl/CORPT00C.cbl} at SHA {@code 27d6c6f}.</p>
 *
 * <p>The defining contract is field <em>segmentation</em>: the report-type
 * selectors {@code monthly}/{@code yearly}/{@code custom} are kept as three
 * <em>separate</em> single-character flags (never collapsed into one enum), and
 * each date is kept as <em>un-merged</em> {@code MM}/{@code DD}/{@code YYYY}
 * segments (never a single combined date string), yielding exactly ten
 * components.</p>
 */
class ReportDtoTest {

    /** Shared, thread-safe jakarta Bean Validation validator for all tests. */
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        // The factory is intentionally not closed: the derived Validator must
        // outlive this setup method for every @Test, and a unit test's JVM is
        // short-lived. Using a local (not try-with-resources) keeps the import
        // surface minimal and avoids validating against a closed factory.
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ------------------------------------------------------------------
    // Fixtures — a fully-valid request and single-field variants of it.
    // Argument order mirrors the SubmitRequest canonical component order:
    // monthly, yearly, custom, startMonth, startDay, startYear,
    // endMonth, endDay, endYear, confirm.
    // ------------------------------------------------------------------

    private static ReportDto.SubmitRequest validRequest() {
        return new ReportDto.SubmitRequest(
                "Y", " ", " ",
                "01", "15", "2024",
                "02", "20", "2024",
                "Y");
    }

    private static ReportDto.SubmitRequest withMonthly(String monthly) {
        return new ReportDto.SubmitRequest(
                monthly, " ", " ",
                "01", "15", "2024",
                "02", "20", "2024",
                "Y");
    }

    private static ReportDto.SubmitRequest withStartMonth(String startMonth) {
        return new ReportDto.SubmitRequest(
                "Y", " ", " ",
                startMonth, "15", "2024",
                "02", "20", "2024",
                "Y");
    }

    private static ReportDto.SubmitRequest withStartYear(String startYear) {
        return new ReportDto.SubmitRequest(
                "Y", " ", " ",
                "01", "15", startYear,
                "02", "20", "2024",
                "Y");
    }

    private static ReportDto.SubmitRequest withEndYear(String endYear) {
        return new ReportDto.SubmitRequest(
                "Y", " ", " ",
                "01", "15", "2024",
                "02", "20", endYear,
                "Y");
    }

    private static ReportDto.SubmitRequest withConfirm(String confirm) {
        return new ReportDto.SubmitRequest(
                "Y", " ", " ",
                "01", "15", "2024",
                "02", "20", "2024",
                confirm);
    }

    /**
     * Asserts the validation produced at least one violation and that every
     * violation reports the supplied {@code field} as its property path. This
     * tolerates fields that breach both {@code @Size} and {@code @Pattern}
     * simultaneously (e.g. an over-long, non-conforming value) while still
     * proving the breach is localized to the expected component.
     */
    private static void assertOnlyViolationsOn(
            Set<ConstraintViolation<ReportDto.SubmitRequest>> violations, String field) {
        assertThat(violations)
                .as("expected at least one constraint violation on '%s'", field)
                .isNotEmpty()
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly(field);
    }

    // ------------------------------------------------------------------
    // Record shape — the segmentation proof (10 components, separate flags,
    // un-merged date segments).
    // ------------------------------------------------------------------

    @Test
    void submitRequestHasExactlyTenComponents() {
        assertThat(ReportDto.SubmitRequest.class.getRecordComponents())
                .as("SubmitRequest must expose exactly the ten CORPT00 input fields")
                .hasSize(10);
    }

    @Test
    void submitRequestComponentsInOrder() {
        assertThat(ReportDto.SubmitRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "monthly", "yearly", "custom",
                        "startMonth", "startDay", "startYear",
                        "endMonth", "endDay", "endYear",
                        "confirm");
    }

    @Test
    void reportFlagsAreSeparateFields() {
        List<String> flagNames = List.of("monthly", "yearly", "custom");

        assertThat(ReportDto.SubmitRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .contains("monthly", "yearly", "custom")
                .doesNotContain("reportType", "frequency", "reportFrequency");

        for (RecordComponent component : ReportDto.SubmitRequest.class.getRecordComponents()) {
            if (flagNames.contains(component.getName())) {
                assertThat(component.getType())
                        .as("report-type flag '%s' must be a String", component.getName())
                        .isEqualTo(String.class);
            }
        }
    }

    @Test
    void dateSegmentsAreNotMerged() {
        List<String> segmentNames = List.of(
                "startMonth", "startDay", "startYear",
                "endMonth", "endDay", "endYear");

        assertThat(ReportDto.SubmitRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .contains("startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear")
                .doesNotContain("startDate", "endDate");

        for (RecordComponent component : ReportDto.SubmitRequest.class.getRecordComponents()) {
            if (segmentNames.contains(component.getName())) {
                assertThat(component.getType())
                        .as("date segment '%s' must be a String", component.getName())
                        .isEqualTo(String.class);
            }
        }
    }

    // ------------------------------------------------------------------
    // Decimal exactness (AAP 0.6.1) — no floating-point components.
    // ------------------------------------------------------------------

    @Test
    void noFloatingPointComponents() {
        for (RecordComponent component : ReportDto.SubmitRequest.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' must not be floating-point (decimal exactness, AAP 0.6.1)",
                            component.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    // ------------------------------------------------------------------
    // Bean validation — both violating and passing inputs, asserting the
    // exact property path for every failure.
    // ------------------------------------------------------------------

    @Test
    void validInstancePasses() {
        Set<ConstraintViolation<ReportDto.SubmitRequest>> violations = validator.validate(validRequest());
        assertThat(violations).isEmpty();
    }

    @Test
    void monthlyFlagInvalidCharFailsPattern() {
        assertOnlyViolationsOn(validator.validate(withMonthly("X")), "monthly");

        // "Y" (selected), " " (blank) and "" (absent) all satisfy [Yy ]?.
        assertThat(validator.validate(withMonthly("Y"))).isEmpty();
        assertThat(validator.validate(withMonthly(" "))).isEmpty();
        assertThat(validator.validate(withMonthly(""))).isEmpty();
    }

    @Test
    void startMonthThreeDigitsFailsSize() {
        assertOnlyViolationsOn(validator.validate(withStartMonth("123")), "startMonth");

        assertThat(validator.validate(withStartMonth("12"))).isEmpty();
    }

    @Test
    void startYearFiveDigitsFailsSize() {
        assertOnlyViolationsOn(validator.validate(withStartYear("20245")), "startYear");

        assertThat(validator.validate(withStartYear("2024"))).isEmpty();
    }

    @Test
    void startMonthNonDigitFailsPattern() {
        assertOnlyViolationsOn(validator.validate(withStartMonth("ab")), "startMonth");
    }

    @Test
    void endYearFiveDigitsFailsSize() {
        assertOnlyViolationsOn(validator.validate(withEndYear("20245")), "endYear");

        assertThat(validator.validate(withEndYear("2024"))).isEmpty();
    }

    @Test
    void confirmInvalidCharFailsPattern() {
        assertOnlyViolationsOn(validator.validate(withConfirm("Q")), "confirm");

        assertThat(validator.validate(withConfirm("N"))).isEmpty();
    }
}
