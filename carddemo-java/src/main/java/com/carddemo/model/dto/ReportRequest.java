package com.carddemo.model.dto;

import jakarta.validation.constraints.Size;

/**
 * Request DTO for the report-submission endpoint {@code POST /api/reports/submit}.
 *
 * <p>This record is the JSON request body accepted by
 * {@code com.carddemo.controller.ReportController} and consumed by
 * {@code com.carddemo.service.report.ReportSubmissionService}, which publishes the
 * submission onto the SQS FIFO queue that replaces the original CICS
 * transient-data-queue (TDQ) {@code WRITEQ TD} online-to-batch bridge of program
 * {@code CORPT00C}.</p>
 *
 * <p>The contract mirrors the <strong>input</strong> (selection) fields of the
 * {@code CORPT00} BMS symbolic map: the three report-type flags
 * (monthly / yearly / custom), the custom start/end date range kept split into
 * month / day / year parts exactly as the 3270 map presents them, and the
 * confirmation flag. Screen chrome, attribute/length subfields, PF-key legends and the
 * response-only {@code ERRMSG} field are intentionally excluded.</p>
 *
 * <p>Every component is a {@link String} whose {@link Size} maximum equals the
 * corresponding BMS field length, preserving the external interface contract exactly.
 * Field-edit semantics from {@code CSSETATY} are expressed here as Jakarta Bean
 * Validation constraints. The three report-type flags remain discrete single-character
 * flags (not collapsed into an enum) to avoid any feature expansion.</p>
 *
 * <p>Reference lineage (COBOL not copied): source map {@code app/cpy-bms/CORPT00.CPY}
 * and field-edit copybook {@code app/cpy/CSSETATY.cpy}; original repository commit
 * {@code 27d6c6f}.</p>
 *
 * @param monthly    monthly report-type selection flag (CORPT00 {@code MONTHLY}, PIC X(1))
 * @param yearly     yearly report-type selection flag (CORPT00 {@code YEARLY}, PIC X(1))
 * @param custom     custom-range report-type selection flag (CORPT00 {@code CUSTOM}, PIC X(1))
 * @param startMonth custom range start month (CORPT00 {@code SDTMM}, PIC X(2))
 * @param startDay   custom range start day (CORPT00 {@code SDTDD}, PIC X(2))
 * @param startYear  custom range start year (CORPT00 {@code SDTYYYY}, PIC X(4))
 * @param endMonth   custom range end month (CORPT00 {@code EDTMM}, PIC X(2))
 * @param endDay     custom range end day (CORPT00 {@code EDTDD}, PIC X(2))
 * @param endYear    custom range end year (CORPT00 {@code EDTYYYY}, PIC X(4))
 * @param confirm    submission confirmation flag (CORPT00 {@code CONFIRM}, PIC X(1))
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
