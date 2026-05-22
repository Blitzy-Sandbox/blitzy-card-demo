/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Transaction-report submission request DTO carried by
 * {@code POST /api/reports/submit}.
 *
 * <p><b>What this DTO replaces.</b>  The 3270 transaction-report screen
 * rendered by the COBOL/CICS program {@code CORPT00C.cbl} (CICS
 * transaction id {@code CR00}, BMS mapset {@code CORPT00}, map
 * {@code CORPT0A}) is the sole online-to-batch bridge in the CardDemo
 * source: the user picks a report timeframe on the terminal, the program
 * writes a fully-formed JCL job stream (the {@code JOB-DATA} 80-byte
 * lines defined at lines 81-127 of {@code CORPT00C.cbl}) to the CICS
 * <i>transient data queue</i> {@code JOBS}, and JES picks the job up off
 * the internal reader and runs {@code app/jcl/TRANREPT.jcl} which in
 * turn drives {@code CBTRN03C.cbl} to produce the printable report.  In
 * the Java target this asynchronous decoupling is preserved: the
 * {@code ReportController#submitReport(ReportRequestDto)} handler
 * publishes a {@code report.requested} message to Amazon MSK (Kafka)
 * partitioned by the issuing account (per AAP &sect;0.6.5 ordering
 * guarantees); a Step Functions trigger consumes the topic and submits
 * the {@code TransactionReportJob} via AWS Batch (per AAP &sect;0.1.1
 * "online-to-batch bridge" and the &sect;0.4.1 transformation row for
 * {@code CORPT00C}).
 *
 * <p><b>Report-type selector.</b>  The CORPT00.bms map carries three
 * radio-style 1-character unprotected fields &mdash; {@code MONTHLY}
 * (lines 80-93), {@code YEARLY} (lines 94-107), and {@code CUSTOM}
 * (lines 108-121) &mdash; with the symbolic-map record components
 * {@code MONTHLYI}, {@code YEARLYI}, {@code CUSTOMI} (each
 * {@code PIC X(1)}) defined at lines 60, 66, 72 of
 * {@code app/cpy-bms/CORPT00.CPY}.  The {@code PROCESS-ENTER-KEY}
 * paragraph in {@code CORPT00C.cbl} (lines 208-456) selects on
 * {@code EVALUATE TRUE} with three {@code WHEN ... NOT = SPACES AND
 * LOW-VALUES} clauses (lines 213, 239, 256) &mdash; the first
 * non-blank selector wins.  The Java target collapses the three
 * mutually-exclusive 1-character flags into a single
 * {@link #reportType()} string with the value domain
 * {@code MONTHLY | YEARLY | CUSTOM} enforced via the {@link Pattern}
 * annotation below.  This change removes the source's tolerance for
 * any non-space character as a "selected" indicator and forces a
 * strict, normalized vocabulary that round-trips cleanly across JSON.
 *
 * <p><b>Custom-range dates.</b>  When the {@code CUSTOM} selector is
 * chosen, the COBOL flow at lines 256-436 requires six numeric
 * sub-fields {@code SDTMMI, SDTDDI, SDTYYYYI} (start month/day/year)
 * and {@code EDTMMI, EDTDDI, EDTYYYYI} (end month/day/year), each
 * {@code PIC X(2)} or {@code PIC X(4)} per
 * {@code app/cpy-bms/CORPT00.CPY} (lines 78, 84, 90, 96, 102, 108).
 * The program then concatenates them at lines 381-386 of
 * {@code CORPT00C.cbl} into {@code WS-START-DATE} and
 * {@code WS-END-DATE} of the form {@code YYYY-MM-DD} (the
 * {@code WS-DATE-FORMAT} literal at line 72) and passes both to
 * {@code CSUTLDTC} for LE {@code CEEDAYS} validation.  The Java DTO
 * collapses the date triplets into two {@link LocalDate} fields
 * serialized as ISO-8601 {@code yyyy-MM-dd} by Jackson via
 * {@link JsonFormat}.  LE-style validation (leap year, month/day
 * range, future-date guard) is delegated to
 * {@code DateValidationService} in {@code ../validation/} (port of
 * {@code app/cpy/CSUTLDPY.cpy} and {@code app/cbl/CSUTLDTC.cbl} per
 * AAP &sect;0.5.2 dependency-removal table).
 *
 * <p><b>Confirmation flag.</b>  The CORPT00.bms {@code CONFIRM} field
 * at lines 206-210 of the BMS source is a 1-character unprotected,
 * underline-highlighted prompt with adjacent literal {@code (Y/N)}
 * (line 217).  The corresponding {@code SUBMIT-JOB-TO-INTRDR}
 * paragraph at lines 462-494 of {@code CORPT00C.cbl} requires the
 * field to be non-empty (line 464) and accepts only
 * {@code 'Y' OR 'y' OR 'N' OR 'n'} (lines 478, 480) &mdash; any other
 * character triggers
 * {@code '" is not a valid value to confirm..."'} (line 488).  The
 * Java target keeps a single 1-character {@link #confirm()} string
 * with the same Y/N domain enforced via {@link Pattern @Pattern}.
 * The pattern below is upper-case only (the controller normalizes
 * casing via {@code dto.confirm().toUpperCase(Locale.ROOT)} before
 * comparison; lowercase {@code 'y'}/{@code 'n'} are not accepted at
 * the API contract level &mdash; an intentional tightening for a JSON
 * REST surface where canonical-form input is expected, while the
 * service layer still recognises both cases for backward-compatible
 * processing of any tooling that mimics the terminal flow).
 *
 * <p><b>Cross-field validation note.</b>  The semantic rule "when
 * {@code reportType == CUSTOM} both {@link #startDate()} and
 * {@link #endDate()} must be non-null and {@code startDate <= endDate}"
 * is a service-layer concern (the COBOL program performs this work
 * inside {@code PROCESS-ENTER-KEY} as a sequence of {@code IF}
 * statements at lines 259-379).  Bean Validation does not enforce
 * cross-field rules without a custom validator, and this DTO
 * deliberately captures only STRUCTURAL validation (format, presence
 * of the selector, value domain) at the controller boundary.
 * Relational/business validation &mdash; including the
 * {@code Start Date - Not a valid date...} branches at lines 396-406
 * and the {@code End Date - Not a valid date...} branches at lines
 * 416-426 &mdash; is replicated by
 * {@code ReportSubmissionService.submit(dto)} which delegates
 * leap-year and calendar-day verification to
 * {@code DateValidationService} before publishing the
 * {@code report.requested} Kafka message.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/CORPT00.bms} (mapset
 *       {@code CORPT00}, map {@code CORPT0A})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/CORPT00.CPY}
 *       ({@code CORPT0AI} / {@code CORPT0AO})</li>
 *   <li>Program: {@code app/cbl/CORPT00C.cbl} (CICS transaction
 *       {@code CR00}, paragraphs {@code MAIN-PARA},
 *       {@code PROCESS-ENTER-KEY}, {@code SUBMIT-JOB-TO-INTRDR})</li>
 *   <li>JCL Replaced: {@code app/jcl/TRANREPT.jcl} (operationally
 *       submitted via {@code report.requested} MSK topic + Step
 *       Functions trigger + AWS Batch submission of
 *       {@code TransactionReportJob} per AAP &sect;0.4.1)</li>
 *   <li>Driving Batch Program (post-bridge):
 *       {@code app/cbl/CBTRN03C.cbl} (transaction-report variant)</li>
 *   <li>Date-validation Provenance: {@code app/cpy/CSUTLDPY.cpy}
 *       (validation procedures) and {@code app/cbl/CSUTLDTC.cbl}
 *       (LE {@code CEEDAYS} wrapper); ported to
 *       {@code ../validation/DateValidationService} per AAP
 *       &sect;0.4.1</li>
 * </ul>
 *
 * <p><b>REST endpoint mapping</b> (per AAP &sect;0.3.4):
 * <ul>
 *   <li>{@code POST /api/reports/submit} &mdash; request body carries
 *       this DTO. The controller validates Bean-Validation constraints
 *       (HTTP {@code 400 Bad Request} via {@code GlobalExceptionHandler}
 *       on failure) then forwards to
 *       {@code ReportSubmissionService.submit(...)} which publishes
 *       the {@code report.requested} MSK Kafka message via
 *       {@code KafkaEventPublisher} (one of the two MSK publishers per
 *       AAP &sect;0.4.1). On success the response is HTTP
 *       {@code 202 Accepted} with the issued message-id (the eventual
 *       AWS Batch job-id) as the body.</li>
 *   <li>Authenticated via Spring Security JWT issued by
 *       {@code AuthController.signin(...)}; either {@code ROLE_USER}
 *       or {@code ROLE_ADMIN} suffices since the source CORPT00C
 *       program is reachable from the regular main menu
 *       ({@code COMEN01.bms} option) and from the admin menu
 *       ({@code COADM01.bms} option).</li>
 * </ul>
 *
 * <p><b>BMS field origin &mdash; CORPT0A map mapping:</b>
 * <pre>{@code
 *   BMS field   Length   CORPT0AI field      Record component      Direction
 *   ---------   ------   ----------------    -------------------   ---------
 *   MONTHLY     X(01)    MONTHLYI            reportType (= MONTHLY)  input
 *   YEARLY      X(01)    YEARLYI             reportType (= YEARLY)   input
 *   CUSTOM      X(01)    CUSTOMI             reportType (= CUSTOM)   input
 *   SDTMM       X(02)    SDTMMI              startDate (month part)  input
 *   SDTDD       X(02)    SDTDDI              startDate (day part)    input
 *   SDTYYYY     X(04)    SDTYYYYI            startDate (year part)   input
 *   EDTMM       X(02)    EDTMMI              endDate (month part)    input
 *   EDTDD       X(02)    EDTDDI              endDate (day part)      input
 *   EDTYYYY     X(04)    EDTYYYYI            endDate (year part)     input
 *   CONFIRM     X(01)    CONFIRMI            confirm                 input
 * }</pre>
 *
 * <p><b>Record components are immutable</b> by definition of the Java
 * {@code record} construct; mutation flows through service-layer
 * re-construction.  No Lombok is used (per AAP &sect;0.5.2 and the
 * file-level prohibition: "NO Lombok").
 *
 * @param reportType the report timeframe selector. Required; must be
 *                   {@code "MONTHLY"}, {@code "YEARLY"}, or
 *                   {@code "CUSTOM"}. Maps to the three radio-selector
 *                   fields {@code MONTHLY} / {@code YEARLY} /
 *                   {@code CUSTOM} on the {@code CORPT00.bms} map; in
 *                   the source any non-blank character on one of the
 *                   three 1-character flags selects that mode, while
 *                   the Java target enforces a strict
 *                   {@code MONTHLY|YEARLY|CUSTOM} normalized vocabulary.
 * @param startDate  the start of the date range. Required only when
 *                   {@link #reportType()} is {@code "CUSTOM"};
 *                   otherwise computed by the service from the current
 *                   date (first-of-month for {@code MONTHLY}, first-of-
 *                   year {@code YEARLY-01-01} for {@code YEARLY}).
 *                   Maps to the BMS triplet {@code SDTMM/SDTDD/SDTYYYY};
 *                   serialized as ISO-8601 {@code yyyy-MM-dd}.
 * @param endDate    the end of the date range. Required only when
 *                   {@link #reportType()} is {@code "CUSTOM"};
 *                   otherwise computed by the service (last day of
 *                   current month for {@code MONTHLY},
 *                   {@code YEARLY-12-31} for {@code YEARLY}). Maps to
 *                   the BMS triplet {@code EDTMM/EDTDD/EDTYYYY};
 *                   serialized as ISO-8601 {@code yyyy-MM-dd}.
 * @param confirm    the submission confirmation flag. Must be
 *                   {@code "Y"} (proceed to publish the
 *                   {@code report.requested} message) or {@code "N"}
 *                   (abort the submission and return the user to the
 *                   selection form). Maps to the BMS field
 *                   {@code CONFIRM} ({@code CONFIRMI PIC X(1)}); on a
 *                   {@code null} or empty value the service emits the
 *                   COBOL-derived message
 *                   {@code "Please confirm to print the <type>
 *                   report..."} (per lines 464-474 of
 *                   {@code CORPT00C.cbl}).
 */
@Schema(name = "ReportRequestDto",
        description = "Transaction-report submission request DTO. Replaces "
                + "the 3270 report-selection screen rendered by COBOL "
                + "program CORPT00C / BMS mapset CORPT00 / map CORPT0A "
                + "(the sole online-to-batch bridge in the CardDemo "
                + "source). Carries a single timeframe selector "
                + "(MONTHLY / YEARLY / CUSTOM), two optional ISO-8601 "
                + "dates used only for CUSTOM ranges, and a single Y/N "
                + "confirmation flag. Submitted via POST "
                + "/api/reports/submit; the controller forwards to "
                + "ReportSubmissionService which publishes the "
                + "report.requested MSK Kafka message that a Step "
                + "Functions trigger consumes to submit the AWS Batch "
                + "TransactionReportJob (replaces JCL TRANREPT.jcl per "
                + "AAP §0.4.1).")
public record ReportRequestDto(

        /**
         * Report-type selector &mdash; one of {@code MONTHLY},
         * {@code YEARLY}, or {@code CUSTOM}.
         *
         * <p>Replaces the three mutually-exclusive 1-character
         * radio-selector fields {@code MONTHLYI}, {@code YEARLYI},
         * {@code CUSTOMI} of {@code app/cpy-bms/CORPT00.CPY} (lines 60,
         * 66, 72).  The COBOL {@code PROCESS-ENTER-KEY} paragraph at
         * lines 212-443 of {@code CORPT00C.cbl} selects the report
         * window as follows:
         * <ul>
         *   <li>{@code MONTHLY} &mdash; preset start = first day of
         *       current month, end = last day of current month
         *       (lines 213-238).</li>
         *   <li>{@code YEARLY}  &mdash; preset start =
         *       {@code YYYY-01-01}, end = {@code YYYY-12-31}
         *       (lines 239-255).</li>
         *   <li>{@code CUSTOM}  &mdash; uses the operator-supplied
         *       {@link #startDate()} and {@link #endDate()} fields and
         *       runs the date triplet validation cascade at lines
         *       256-426.</li>
         * </ul>
         */
        @NotNull(message = "Report type is required")
        @Pattern(regexp = "^(MONTHLY|YEARLY|CUSTOM)$",
                message = "Report type must be MONTHLY, YEARLY, or CUSTOM")
        @Schema(description = "Report timeframe selector. MONTHLY = "
                + "current calendar month, YEARLY = current calendar "
                + "year, CUSTOM = operator-supplied startDate / endDate "
                + "range. Replaces the three radio-style 1-character "
                + "fields MONTHLY / YEARLY / CUSTOM on CORPT00.bms.",
                example = "MONTHLY",
                allowableValues = {"MONTHLY", "YEARLY", "CUSTOM"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("reportType")
        String reportType,

        /**
         * Start of the requested date range &mdash; required only when
         * {@link #reportType()} is {@code "CUSTOM"}.
         *
         * <p>Replaces the BMS triplet {@code SDTMM} / {@code SDTDD} /
         * {@code SDTYYYY} of {@code app/cpy-bms/CORPT00.CPY} (lines 78,
         * 84, 90) which the COBOL program concatenates into
         * {@code WS-START-DATE} of the form {@code YYYY-MM-DD} at
         * lines 381-383 of {@code CORPT00C.cbl} and validates via
         * {@code CALL 'CSUTLDTC'} (lines 392-394).  The Jackson
         * {@link JsonFormat} annotation pins the wire format to ISO-8601
         * (per AAP &sect;0.5.2 dependency-removal table the LE
         * {@code CEEDAYS}-based date validation is replaced by native
         * {@code java.time.LocalDate.parse} + {@code DateValidationService}).
         *
         * <p>Bean Validation is intentionally not used to enforce
         * "required when reportType=CUSTOM" because Jakarta does not
         * support cross-field rules without a custom validator; the
         * conditional-presence rule lives in
         * {@code ReportSubmissionService.submit(dto)}.
         */
        @Schema(description = "Start of the requested date range, "
                + "ISO-8601 yyyy-MM-dd. Required only when reportType = "
                + "CUSTOM; ignored for MONTHLY and YEARLY where the "
                + "service computes the timeframe. Replaces the BMS "
                + "triplet SDTMM/SDTDD/SDTYYYY.",
                example = "2026-01-01",
                format = "date",
                type = "string")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("startDate")
        LocalDate startDate,

        /**
         * End of the requested date range &mdash; required only when
         * {@link #reportType()} is {@code "CUSTOM"}.
         *
         * <p>Replaces the BMS triplet {@code EDTMM} / {@code EDTDD} /
         * {@code EDTYYYY} of {@code app/cpy-bms/CORPT00.CPY} (lines 96,
         * 102, 108) which the COBOL program concatenates into
         * {@code WS-END-DATE} at lines 384-386 of
         * {@code CORPT00C.cbl} and validates via {@code CSUTLDTC} at
         * lines 412-414.  Inclusive interval &mdash; the downstream
         * report covers every transaction whose
         * {@code TRAN-PROC-DT} is between {@link #startDate()} and
         * this date inclusive.
         *
         * <p>The chronological invariant
         * {@code startDate <= endDate} is enforced at the service
         * layer (per the cross-field note in the class JavaDoc and
         * AAP &sect;0.7.1 layered-validation guidance).
         */
        @Schema(description = "End of the requested date range, "
                + "ISO-8601 yyyy-MM-dd. Required only when reportType = "
                + "CUSTOM; ignored for MONTHLY and YEARLY where the "
                + "service computes the timeframe. Replaces the BMS "
                + "triplet EDTMM/EDTDD/EDTYYYY. Inclusive endpoint.",
                example = "2026-12-31",
                format = "date",
                type = "string")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("endDate")
        LocalDate endDate,

        /**
         * Submission-confirmation flag &mdash; {@code "Y"} to publish
         * the {@code report.requested} message; {@code "N"} to abort
         * the request and return to the selection form.
         *
         * <p>Replaces the {@code CONFIRM} field of
         * {@code app/cpy-bms/CORPT00.CPY} (line 114,
         * {@code CONFIRMI PIC X(1)}).  The COBOL
         * {@code SUBMIT-JOB-TO-INTRDR} paragraph at lines 462-494 of
         * {@code CORPT00C.cbl} treats an empty value as
         * "ask for confirmation" (re-renders the selection form with
         * "Please confirm to print the &lt;type&gt; report..." per
         * lines 464-474), {@code 'Y'} or {@code 'y'} as "submit" (line
         * 478), {@code 'N'} or {@code 'n'} as "cancel" (line 480), and
         * any other character as
         * {@code '"<x>" is not a valid value to confirm...'} (line 488).
         *
         * <p>The {@link Pattern} below restricts the JSON contract to
         * the canonical upper-case alphabet {@code [YN]}; the service
         * layer applies the same dual-case tolerance as the original
         * COBOL flow if needed.  The pattern is intentionally not
         * paired with {@link NotNull} because a null/missing value is
         * a legitimate state in the workflow (it means "confirmation
         * not yet provided", which the service translates to the
         * COBOL re-prompt message).
         */
        @Pattern(regexp = "^[YN]$",
                message = "Confirmation must be 'Y' or 'N'")
        @Schema(description = "Submission confirmation. 'Y' to publish "
                + "the report.requested Kafka message and trigger the "
                + "AWS Batch report job; 'N' to cancel the submission. "
                + "Replaces the CORPT00.bms CONFIRM field (PIC X(1)). "
                + "A null/missing value triggers the COBOL-derived "
                + "'Please confirm to print the <type> report...' "
                + "re-prompt at the service layer.",
                example = "Y",
                allowableValues = {"Y", "N"})
        @JsonProperty("confirm")
        String confirm
) {
}
