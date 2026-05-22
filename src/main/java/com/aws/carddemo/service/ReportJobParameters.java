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
package com.aws.carddemo.service;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable value object carrying the parameters that
 * {@link ReportSubmissionService#submit(ReportSubmissionRequest)} passes to
 * {@link ReportJobDispatcher#dispatch(ReportJobParameters)} after all
 * COBOL-equivalent reject paths have cleared. Represents the Java
 * replacement for the JCL {@code PARM-START-DATE} / {@code PARM-END-DATE}
 * data items assembled by the COBOL {@code PROCESS-ENTER-KEY} and
 * {@code SUBMIT-JOB-TO-INTRDR} paragraphs of {@code app/cbl/CORPT00C.cbl}.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl</h2>
 *
 * <p>The COBOL workflow assembles the JCL submission as follows:
 *
 * <ul>
 *   <li>{@code //TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,...} — the
 *       outer {@code JOB} statement (line 84 of CORPT00C.cbl). The
 *       eight-character JCL JOB name {@code TRNRPT00} maps to
 *       {@link #jobName} on this DTO. Spring Batch consumers also use
 *       this token as the {@code JobParameters} {@code jobName} key.</li>
 *   <li>{@code //STEP10 EXEC PROC=TRANREPT} — the JCL {@code EXEC} statement
 *       (line 94 of CORPT00C.cbl). The procedure name {@code TRANREPT}
 *       maps to {@link #procName} on this DTO.</li>
 *   <li>{@code PARM-START-DATE-1} / {@code PARM-START-DATE-2} — {@code PIC X(10)}
 *       fields holding the {@code YYYY-MM-DD} start date populated by
 *       {@code MOVE WS-START-DATE TO PARM-START-DATE-1} (lines 220, 247,
 *       429 of CORPT00C.cbl). Maps to {@link #startDate} on this DTO as a
 *       typed {@link LocalDate}.</li>
 *   <li>{@code PARM-END-DATE-1} / {@code PARM-END-DATE-2} — {@code PIC X(10)}
 *       fields holding the {@code YYYY-MM-DD} end date populated by
 *       {@code MOVE WS-END-DATE TO PARM-END-DATE-1} (lines 235, 252, 431
 *       of CORPT00C.cbl). Maps to {@link #endDate} on this DTO as a typed
 *       {@link LocalDate}.</li>
 * </ul>
 *
 * <p>In the COBOL baseline these four fields are character strings; the
 * Java migration types them as {@link LocalDate} so the dispatcher and the
 * downstream Spring Batch {@code Job} can validate them at the Java type
 * boundary rather than at parse time in the batch step. The
 * {@code ReportSubmissionServiceTest} {@code HappyPath} group asserts on
 * the typed {@link LocalDate} values directly (e.g.,
 * {@code assertThat(params.getStartDate()).isEqualTo(LocalDate.of(2024, 1, 1))}).
 *
 * <h2>Immutability and Equality</h2>
 *
 * <p>All four fields are {@code final} and populated exactly once via the
 * canonical static factory {@link #of(String, LocalDate, LocalDate)}. The
 * class is {@code final} so no subclass can break the immutability
 * invariant. Value-equality is defined on the four fields so
 * {@link ArgumentCaptor}-captured instances can be compared via
 * {@code assertThat(captured).isEqualTo(expected)} without relying on
 * reference identity.
 *
 * <h2>Construction Contract — Factory Method Only</h2>
 *
 * <p>Instances are created exclusively via {@link #of(String, LocalDate, LocalDate)};
 * the constructor is private. This convention matches the
 * {@code success(...)} / {@code failure(...)} factory pattern used by
 * {@link UserDeleteResult} / {@link UserAddResult} and the rest of the
 * service-package DTOs (AAP §0.10.10 style consistency). Two-argument
 * factories are not provided because the JCL JOB name and PROC name are
 * load-bearing constants of the migration (changing them would break the
 * batch-job lookup contract); the canonical factory accepts them
 * explicitly so the caller cannot accidentally pass a wrong job name.
 *
 * @see ReportJobDispatcher
 * @see ReportJobHandle
 * @see ReportSubmissionService
 */
public final class ReportJobParameters {

    /**
     * JCL PROC name dispatched with these parameters — verbatim COBOL
     * literal {@code TRANREPT} from line 94 of {@code app/cbl/CORPT00C.cbl}
     * ({@code //STEP10 EXEC PROC=TRANREPT}). Held alongside {@link #jobName}
     * so downstream Spring Batch consumers can locate the correct
     * {@code Job} bean and the correct step composition (the PROC name
     * encodes the multi-step pipeline that the COBOL JCL would have
     * triggered). Tests assert only on {@link #jobName} because the PROC
     * name is implied by the job name in the Java migration (every job
     * maps one-for-one to the COBOL JCL PROC of the same logical name).
     */
    private final String jobName;

    /**
     * Report start date — {@code YYYY-MM-DD} inclusive lower bound for the
     * transaction-report time window. Computed by
     * {@link ReportSubmissionService} from the request inputs:
     * <ul>
     *   <li>{@code MONTHLY} mode: first day of the current month (driven by
     *       the injected {@code Clock}).</li>
     *   <li>{@code YEARLY} mode: first day of the current year.</li>
     *   <li>{@code CUSTOM} mode: the user-supplied
     *       {@link ReportSubmissionRequest#getStartDate()} after
     *       strict-ISO parsing.</li>
     * </ul>
     * Guaranteed non-{@code null} by {@link #of(String, LocalDate, LocalDate)}.
     */
    private final LocalDate startDate;

    /**
     * Report end date — {@code YYYY-MM-DD} inclusive upper bound for the
     * transaction-report time window. Computed by
     * {@link ReportSubmissionService} from the request inputs:
     * <ul>
     *   <li>{@code MONTHLY} mode: last day of the current month (driven by
     *       the injected {@code Clock}; uses {@code LocalDate#lengthOfMonth()}
     *       so the leap-year February-29 boundary is handled implicitly).</li>
     *   <li>{@code YEARLY} mode: December 31 of the current year.</li>
     *   <li>{@code CUSTOM} mode: the user-supplied
     *       {@link ReportSubmissionRequest#getEndDate()} after
     *       strict-ISO parsing.</li>
     * </ul>
     * Guaranteed non-{@code null} by {@link #of(String, LocalDate, LocalDate)}
     * and guaranteed to be on-or-after {@link #startDate} by the
     * {@code start &lt;= end} check in {@link ReportSubmissionService}.
     */
    private final LocalDate endDate;

    /**
     * Private constructor enforcing the {@link #of(String, LocalDate, LocalDate)}
     * factory-only construction contract.
     */
    private ReportJobParameters(String jobName, LocalDate startDate, LocalDate endDate) {
        this.jobName = jobName;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /**
     * Builds a {@code ReportJobParameters} value object from the canonical
     * job name and the resolved start/end dates.
     *
     * <p>All three arguments are validated as non-{@code null} so downstream
     * consumers can rely on the getters never returning {@code null}. The
     * factory does not validate the {@code start <= end} relationship — that
     * invariant is enforced upstream by {@link ReportSubmissionService}
     * because the COBOL-equivalent reject message (the verbatim
     * {@code 'End Date should be greater than Start Date'}) must be
     * surfaced through {@link ReportSubmissionResult#getMessage()} rather
     * than via an {@link IllegalArgumentException}.
     *
     * @param jobName   the JCL JOB name (e.g., {@code TRNRPT00}); must be
     *                  non-{@code null} and non-blank
     * @param startDate the report start date; must be non-{@code null}
     * @param endDate   the report end date; must be non-{@code null}
     * @return a fresh, immutable parameter object
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code jobName} is blank
     */
    public static ReportJobParameters of(String jobName, LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(jobName, "jobName must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        if (jobName.trim().isEmpty()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        return new ReportJobParameters(jobName, startDate, endDate);
    }

    /**
     * @return the JCL JOB name (e.g., {@code TRNRPT00}); never {@code null}
     *         and never blank
     */
    public String getJobName() {
        return jobName;
    }

    /**
     * @return the report start date ({@code YYYY-MM-DD}, inclusive); never
     *         {@code null}
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * @return the report end date ({@code YYYY-MM-DD}, inclusive); never
     *         {@code null}
     */
    public LocalDate getEndDate() {
        return endDate;
    }

    /**
     * Value-equality based on the three load-bearing fields. Two parameter
     * objects with the same job name and date window are interchangeable;
     * this supports {@code ArgumentCaptor}-based test assertions that
     * compare captured arguments by value rather than by reference.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReportJobParameters that)) {
            return false;
        }
        return jobName.equals(that.jobName)
            && startDate.equals(that.startDate)
            && endDate.equals(that.endDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobName, startDate, endDate);
    }

    /**
     * Diagnostic rendering — exposes the three load-bearing fields. Safe to
     * log because none of the fields carry PCI/PII content (the date window
     * is a date range, not a financial value).
     */
    @Override
    public String toString() {
        return "ReportJobParameters[jobName=" + jobName
            + ", startDate=" + startDate
            + ", endDate=" + endDate + "]";
    }
}
