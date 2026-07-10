package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Validated request body for the transaction reporting endpoint exposed by
 * {@code ReportController} (legacy online transaction {@code CR00}).
 *
 * <p>This record is the Java 25 / Spring Boot translation of the input fields
 * of the CORPT00 BMS screen ({@code app/cpy-bms/CORPT00.CPY}, program
 * {@code CORPT00C}). On the mainframe the report was requested from a 3270
 * screen and dispatched through a CICS Transient Data Queue to the JES bridge;
 * in the migrated system the same request is submitted over REST and produces
 * the report asynchronously via an SQS FIFO-triggered Spring Batch launch.
 *
 * <p>Field mapping from the CORPT00 symbolic map to this DTO:
 * <ul>
 *   <li>The three mutually exclusive {@code MONTHLY} / {@code YEARLY} /
 *       {@code CUSTOM} selection flags ({@code PIC X(1)} each) collapse into a
 *       single {@link #reportType()} value.</li>
 *   <li>{@code SDTMM} + {@code SDTDD} + {@code SDTYYYY} (the split MM/DD/YYYY
 *       screen fields) compose the ISO-8601 {@link #startDate()}.</li>
 *   <li>{@code EDTMM} + {@code EDTDD} + {@code EDTYYYY} compose
 *       {@link #endDate()}.</li>
 *   <li>{@code CONFIRM} ({@code PIC X(1)}, legacy value {@code 'Y'}) becomes the
 *       optional {@link #confirm()} flag; it carries the pseudo-conversational
 *       confirm-before-submit state in a stateless REST model.</li>
 * </ul>
 *
 * <p><strong>Cross-field validation.</strong> Only field-level constraints live
 * on this record. When {@link #reportType()} is {@code CUSTOM}, both
 * {@link #startDate()} and {@link #endDate()} are mandatory and
 * {@code startDate} must be on or before {@code endDate}; those cross-field
 * rules are enforced in {@code ReportService}. For {@code MONTHLY} and
 * {@code YEARLY} the service derives the reporting range and any supplied dates
 * are ignored.
 *
 * @param reportType the requested reporting period selector; required and
 *        restricted to {@code MONTHLY}, {@code YEARLY} or {@code CUSTOM}
 * @param startDate  inclusive start of the reporting window; required by
 *        {@code ReportService} only when {@code reportType} is {@code CUSTOM}
 * @param endDate    inclusive end of the reporting window; required by
 *        {@code ReportService} only when {@code reportType} is {@code CUSTOM}
 * @param confirm    optional confirm-before-submit flag ({@code true} confirms
 *        submission); {@code null} when the client has not yet confirmed
 */
public record ReportRequest(

        @NotBlank(message = "reportType is required and must not be blank")
        @Pattern(
                regexp = "MONTHLY|YEARLY|CUSTOM",
                message = "reportType must be one of: MONTHLY, YEARLY or CUSTOM")
        String reportType,

        LocalDate startDate,

        LocalDate endDate,

        Boolean confirm) {
}
