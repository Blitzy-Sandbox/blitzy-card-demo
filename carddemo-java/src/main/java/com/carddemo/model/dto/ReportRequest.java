package com.carddemo.model.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload for the report-submission operation, accepted as the JSON body of
 * {@code POST /api/reports/submit} by {@code com.carddemo.controller.ReportController}
 * and consumed by {@code com.carddemo.service.report.ReportSubmissionService}.
 *
 * <p>The service replays the original CICS online-to-batch bridge: where the COBOL
 * program {@code CORPT00C} wrote the request to a transient-data queue
 * ({@code WRITEQ TD}) to trigger downstream batch reporting, the modern service
 * publishes the equivalent message to an <strong>SQS FIFO</strong> queue that drives a
 * Spring Batch report job. This DTO is the wire contract that carries the operator's
 * selection across that boundary.</p>
 *
 * <p>It mirrors the <em>input / selection</em> fields of the {@code CORPT00} BMS
 * symbolic map: a mutually-exclusive report-type selection expressed as three single
 * character flags ({@code monthly}, {@code yearly}, {@code custom}); a custom reporting
 * window whose start and end dates are presented as <strong>split month / day / year
 * part fields</strong>; and a single character {@code confirm} flag. The split date
 * contract is preserved verbatim here so the wire format matches the 3270 input layout
 * one-to-one — the parts are intentionally <em>not</em> merged into composite date
 * values, and the three report-type flags are intentionally <em>not</em> collapsed into
 * an enumeration, keeping the contract identical to the source map with no feature
 * expansion.</p>
 *
 * <p>Every component is a {@link String} whose {@link Size} maximum length matches the
 * corresponding BMS field length exactly (single-character flags {@code max = 1};
 * month / day parts {@code max = 2}; four-digit year parts {@code max = 4}), echoing the
 * field-edit semantics of {@code CSSETATY.cpy} as declarative Jakarta Bean Validation
 * constraints.</p>
 *
 * <p>Screen chrome (transaction name, titles, current date / time, program name), the
 * server-generated {@code ERRMSG} field (output-only, carried on the response contract),
 * and PF-key legend / attribute fields from the BMS map are deliberately excluded; they
 * are not part of the request.</p>
 *
 * <p>Lineage: derived (not copied) from the AWS CardDemo COBOL source at commit
 * {@code 27d6c6f} — {@code app/cpy-bms/CORPT00.CPY} (field layout) and
 * {@code app/cpy/CSSETATY.cpy} (field-edit / attribute semantics that motivate the
 * Jakarta Bean Validation constraints below).</p>
 *
 * @param monthly    single-character flag selecting the monthly report ({@code MONTHLY}, X(1))
 * @param yearly     single-character flag selecting the yearly report ({@code YEARLY}, X(1))
 * @param custom     single-character flag selecting a custom date-range report ({@code CUSTOM}, X(1))
 * @param startMonth custom-range start month part ({@code SDTMM}, X(2))
 * @param startDay   custom-range start day part ({@code SDTDD}, X(2))
 * @param startYear  custom-range start year part ({@code SDTYYYY}, X(4))
 * @param endMonth   custom-range end month part ({@code EDTMM}, X(2))
 * @param endDay     custom-range end day part ({@code EDTDD}, X(2))
 * @param endYear    custom-range end year part ({@code EDTYYYY}, X(4))
 * @param confirm    single-character confirmation flag ({@code CONFIRM}, X(1))
 */
public record ReportRequest(
        @Size(max = 1) String monthly,
        @Size(max = 1) String yearly,
        @Size(max = 1) String custom,
        @Size(max = 2) String startMonth,
        @Size(max = 2) String startDay,
        @Size(max = 4) String startYear,
        @Size(max = 2) String endMonth,
        @Size(max = 2) String endDay,
        @Size(max = 4) String endYear,
        @Size(max = 1) String confirm) {
}
