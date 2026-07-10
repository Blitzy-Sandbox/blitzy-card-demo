package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ReportRequest}, the transaction-reporting request body
 * that replaces the legacy CardDemo {@code CORPT00} BMS screen (CICS transaction
 * {@code CR00}, backing program {@code CORPT00C}).
 *
 * <p>On the mainframe the report was requested from a 3270 screen and dispatched
 * through a CICS Transient Data Queue to the JES bridge; in the migrated system
 * the same parameters arrive over REST and drive an SQS FIFO-triggered Spring
 * Batch launch. The DTO carries the fields migrated from the {@code CORPT00}
 * symbolic map (source SHA {@code 27d6c6f}; no COBOL is reproduced here): the
 * three mutually exclusive {@code MONTHLYI}/{@code YEARLYI}/{@code CUSTOMI}
 * selection flags ({@code PIC X(1)}) collapse into a single {@code reportType};
 * the split {@code SDTMM}/{@code SDTDD}/{@code SDTYYYY} and
 * {@code EDTMM}/{@code EDTDD}/{@code EDTYYYY} screen fields compose the ISO
 * {@code startDate} and {@code endDate}; and {@code CONFIRMI} ({@code PIC X(1)})
 * becomes the optional {@code confirm} flag.</p>
 *
 * <p>Four concerns are exercised, mirroring the migration's parity requirements:</p>
 * <ol>
 *   <li><strong>Valid instances</strong> — every allowed {@code reportType}
 *       ({@code MONTHLY}, {@code YEARLY}, {@code CUSTOM}) validates cleanly.</li>
 *   <li><strong>{@code reportType} pattern</strong> — {@code @NotBlank} reproduces
 *       the mandatory-selection edit and {@code @Pattern} restricts the value to
 *       the exact allowed set. Violations are asserted by property path
 *       ({@link ConstraintViolation#getPropertyPath()}) and by the offending
 *       constraint annotation, so an accidental annotation change breaks the
 *       build. The regex is case-sensitive and full-match, so {@code "monthly"},
 *       {@code "WEEKLY"}, and {@code "MONTHLY "} (trailing space) are all
 *       rejected.</li>
 *   <li><strong>Dates</strong> — {@code startDate}/{@code endDate} bind from and
 *       serialize to ISO-8601 date strings. Only field-level constraints live on
 *       this record; the cross-field rules ("{@code CUSTOM} requires both dates"
 *       and "{@code startDate} &le; {@code endDate}") are enforced downstream in
 *       {@code ReportService}, so the programmatic {@link jakarta.validation.Validator}
 *       intentionally reports no violations for those cases here — this test pins
 *       that contract so the responsibility boundary cannot silently move.</li>
 *   <li><strong>JSON round-trip</strong> — the request binds from the wire
 *       contract and round-trips without loss, including the nullable
 *       {@code confirm} flag.</li>
 * </ol>
 *
 * <p>The tests are deliberately pure and framework-free: they use the shared
 * programmatic {@link jakarta.validation.Validator} and {@code ObjectMapper}
 * exposed by {@link DtoTestSupport} — no Spring context, no Testcontainers, and no
 * mocks — so they run in milliseconds and contribute fast line coverage toward the
 * Gate&nbsp;8 (&ge;80%) JaCoCo threshold. Rationale is documented in
 * {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("ReportRequest — reportType pattern validation, date binding, and JSON round-trip")
class ReportRequestTest {

    /** Inclusive start of a valid custom reporting window ({@code 2024-01-01}). */
    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    /** Inclusive end of a valid custom reporting window ({@code 2024-01-31}). */
    private static final LocalDate END = LocalDate.of(2024, 1, 31);

    // ------------------------------------------------------------------
    // Phase 1 — valid instances. Every allowed reportType validates cleanly
    // (the three MONTHLY/YEARLY/CUSTOM selection flags of the CORPT00 map).
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "reportType=\"{0}\" with valid dates and confirm has no violations")
    @ValueSource(strings = {"MONTHLY", "YEARLY", "CUSTOM"})
    @DisplayName("Each allowed reportType (MONTHLY, YEARLY, CUSTOM) yields zero violations")
    void allowedReportTypesHaveNoViolations(String reportType) {
        ReportRequest request = new ReportRequest(reportType, START, END, Boolean.TRUE);

        Set<ConstraintViolation<ReportRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A MONTHLY request with no dates and no confirm is valid (both are optional at the DTO level)")
    void monthlyWithoutDatesOrConfirmIsValid() {
        // MONTHLY/YEARLY derive their range in ReportService and ignore any supplied
        // dates, so a bare selection with null dates and no confirm is a valid body.
        ReportRequest request = new ReportRequest("MONTHLY", null, null, null);

        Set<ConstraintViolation<ReportRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 2 — reportType pattern. @Pattern restricts the value to the exact
    // allowed set (case-sensitive, full-match); @NotBlank rejects null/blank.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "reportType=\"{0}\" (outside the allowed set) triggers a single @Pattern violation")
    @ValueSource(strings = {"WEEKLY", "DAILY", "monthly", "yearly", "custom", "MONTHLY ", "CUSTOMX"})
    @DisplayName("A non-blank reportType outside {MONTHLY,YEARLY,CUSTOM} violates @Pattern on 'reportType'")
    void invalidReportTypeViolatesPattern(String reportType) {
        // Every value here is non-blank (so @NotBlank passes) yet fails the
        // case-sensitive, full-match regex, leaving exactly one @Pattern violation.
        ReportRequest request = new ReportRequest(reportType, START, END, Boolean.TRUE);

        Set<ConstraintViolation<ReportRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("reportType");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
        });
    }

    @Test
    @DisplayName("A null reportType violates @NotBlank only (@Pattern treats null as valid)")
    void nullReportTypeViolatesNotBlankOnly() {
        // Bean Validation short-circuits @Pattern on null, so only @NotBlank fires.
        ReportRequest request = new ReportRequest(null, START, END, Boolean.TRUE);

        Set<ConstraintViolation<ReportRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("reportType");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class);
        });
    }

    @ParameterizedTest(name = "blank-but-non-null reportType [{0}] violates both @NotBlank and @Pattern")
    @ValueSource(strings = {"", "   "})
    @DisplayName("An empty or whitespace-only reportType violates both @NotBlank and @Pattern on 'reportType'")
    void blankNonNullReportTypeViolatesNotBlankAndPattern(String reportType) {
        // Unlike null, an empty/whitespace value is a non-null CharSequence, so
        // @Pattern also evaluates (and fails) in addition to @NotBlank.
        ReportRequest request = new ReportRequest(reportType, START, END, Boolean.TRUE);

        Set<ConstraintViolation<ReportRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("reportType"));
        assertThat(violations)
                .extracting(violation ->
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                .containsExactlyInAnyOrder("NotBlank", "Pattern");
    }

    // ------------------------------------------------------------------
    // Phase 3 — dates. ISO-8601 binding/serialization plus the explicit
    // contract that cross-field rules live in ReportService, not on the DTO.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("startDate and endDate bind from ISO-8601 strings on the wire")
    void datesBindFromIsoStrings() {
        String json = "{\"reportType\":\"CUSTOM\","
                + "\"startDate\":\"2024-01-01\",\"endDate\":\"2024-01-31\",\"confirm\":true}";

        ReportRequest request = DtoTestSupport.fromJson(json, ReportRequest.class);

        assertThat(request.reportType()).isEqualTo("CUSTOM");
        assertThat(request.startDate()).isEqualTo(START);
        assertThat(request.endDate()).isEqualTo(END);
        assertThat(request.confirm()).isTrue();
    }

    @Test
    @DisplayName("startDate and endDate serialize as ISO-8601 date strings, not numeric timestamp arrays")
    void datesSerializeAsIsoStrings() {
        String json = DtoTestSupport.toJson(new ReportRequest("CUSTOM", START, END, Boolean.TRUE));

        assertThat(json).contains("\"startDate\":\"2024-01-01\"");
        assertThat(json).contains("\"endDate\":\"2024-01-31\"");
    }

    @Test
    @DisplayName("The DTO does not enforce the CUSTOM-requires-dates rule (ReportService owns it)")
    void customWithMissingDatesHasNoFieldLevelViolations() {
        // Only field-level constraints live on this record. The "CUSTOM requires both
        // start and end" rule is enforced in ReportService, so the field-level
        // Validator must report zero violations for a CUSTOM body with null dates.
        ReportRequest missingDates = new ReportRequest("CUSTOM", null, null, Boolean.TRUE);

        assertThat(DtoTestSupport.validate(missingDates)).isEmpty();
    }

    @Test
    @DisplayName("The DTO does not enforce start<=end ordering (ReportService owns it)")
    void customWithStartAfterEndHasNoFieldLevelViolations() {
        // The "startDate <= endDate" rule is likewise a ReportService concern; an
        // inverted range carries no field-level violation on the DTO itself.
        ReportRequest inverted = new ReportRequest("CUSTOM", END, START, Boolean.TRUE);

        assertThat(DtoTestSupport.validate(inverted)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 4 — JSON round-trip. The request binds from and survives a
    // serialize/deserialize cycle without loss, including nullable confirm.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A fully-populated request round-trips through JSON without loss")
    void fullyPopulatedRequestRoundTrips() {
        ReportRequest original = new ReportRequest("CUSTOM", START, END, Boolean.TRUE);

        ReportRequest restored = DtoTestSupport.roundTrip(original, ReportRequest.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.reportType()).isEqualTo("CUSTOM");
        assertThat(restored.startDate()).isEqualTo(START);
        assertThat(restored.endDate()).isEqualTo(END);
        assertThat(restored.confirm()).isTrue();
    }

    @ParameterizedTest(name = "confirm={0} round-trips through JSON")
    @NullSource
    @ValueSource(booleans = {true, false})
    @DisplayName("The confirm flag round-trips as true, false, or null")
    void confirmFlagRoundTrips(Boolean confirm) {
        ReportRequest original = new ReportRequest("MONTHLY", START, END, confirm);

        ReportRequest restored = DtoTestSupport.roundTrip(original, ReportRequest.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.confirm()).isEqualTo(confirm);
    }
}
