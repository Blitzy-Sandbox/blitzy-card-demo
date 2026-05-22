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

/**
 * Mutable request DTO for
 * {@link ReportSubmissionService#submit(ReportSubmissionRequest)} — the Java
 * replacement for the {@code CORPT0AI} BMS-mapped input record populated by
 * the COBOL {@code RECEIVE-TRNRPT-SCREEN} paragraph (lines 596-604 of
 * {@code app/cbl/CORPT00C.cbl}, TRANID {@code CR00}, the transaction-report
 * submission dispatcher). Carries the operator's three-way report-mode
 * selection ({@code MONTHLY}, {@code YEARLY}, {@code CUSTOM}), the
 * user-supplied date window (used only when {@code reportMode = CUSTOM}),
 * and the {@code Y}/{@code N} confirmation prompt response.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl</h2>
 *
 * <p>The COBOL {@code RECEIVE-TRNRPT-SCREEN} paragraph populates the
 * {@code CORPT0AI} BMS input map. The {@code PROCESS-ENTER-KEY} paragraph
 * (lines 208-456) then consults six pieces of state to dispatch the
 * report job:
 *
 * <ul>
 *   <li>{@code MONTHLYI OF CORPT0AI} (line 213) — the {@code PIC X(01)}
 *       "Monthly" radio button; populated when the user types a non-blank
 *       character in the monthly position. Maps to {@link #reportMode}
 *       value {@code "MONTHLY"} on this DTO.</li>
 *   <li>{@code YEARLYI OF CORPT0AI} (line 239) — the {@code PIC X(01)}
 *       "Yearly" radio button. Maps to {@link #reportMode} value
 *       {@code "YEARLY"} on this DTO.</li>
 *   <li>{@code CUSTOMI OF CORPT0AI} (line 256) — the {@code PIC X(01)}
 *       "Custom" radio button. Maps to {@link #reportMode} value
 *       {@code "CUSTOM"} on this DTO; when this mode is selected, the
 *       {@link #startDate} and {@link #endDate} fields below are
 *       consulted.</li>
 *   <li>{@code SDTMMI / SDTDDI / SDTYYYYI OF CORPT0AI} (lines 259-279) —
 *       three two-and-four-character fields holding the start-date month,
 *       day, and year. The Java migration collapses these into a single
 *       {@link #startDate} string in canonical {@code YYYY-MM-DD} ISO
 *       format because the REST controller layer surfaces dates as ISO
 *       strings rather than as three independent fields.</li>
 *   <li>{@code EDTMMI / EDTDDI / EDTYYYYI OF CORPT0AI} (lines 280-300) —
 *       three fields holding the end-date month, day, and year. Maps to
 *       {@link #endDate} on this DTO.</li>
 *   <li>{@code CONFIRMI OF CORPT0AI} (line 464) — the {@code PIC X(01)}
 *       confirmation prompt. Maps to {@link #confirmation} on this DTO.
 *       Accepted values are {@code "Y"}/{@code "y"} to proceed and
 *       {@code "N"}/{@code "n"} to cancel.</li>
 * </ul>
 *
 * <h2>Mutability vs Immutability</h2>
 *
 * <p>This DTO uses setter-style mutability rather than the immutable
 * positional-constructor convention adopted by {@link UserDeleteRequest} and
 * the other small-fixed-set request DTOs. The rationale is that the REST
 * controller and JSON deserializer ({@code Jackson}) populate the request
 * field-by-field via setters; a constructor with five positional arguments
 * (mode, startDate, endDate, confirmation — and the order is not
 * intuitive) would be harder to deserialize and harder to extend if the
 * Java migration later needs to add the {@code FROM-ACCOUNT-ID} filter
 * that {@code CORPT00C.cbl} does not currently expose but its BMS map
 * permits via the unused {@code TRNIDFI} field. The setter style matches
 * the convention used by {@link UserAddRequest} for the same reason.
 *
 * <h2>No Validation</h2>
 *
 * <p>This class deliberately performs no field validation in the setters.
 * Per the established convention used by {@link UserAddRequest} /
 * {@link CardListRequest}, validation of the payload (empty fields,
 * invalid dates, start-after-end) is performed by the
 * {@link ReportSubmissionService} so the reject paths emit the
 * COBOL-equivalent reject messages rather than
 * {@link IllegalArgumentException}. Carrying validation in the service also
 * keeps it visible to the test suite and countable for JaCoCo coverage
 * purposes (AAP §0.7.1).
 *
 * @see ReportSubmissionService
 * @see ReportSubmissionResult
 */
public final class ReportSubmissionRequest {

    /**
     * Report mode selector — the Java equivalent of the
     * {@code MONTHLYI}/{@code YEARLYI}/{@code CUSTOMI} BMS radio buttons in
     * {@code CORPT00C.cbl} (lines 213, 239, 256). Recognised values
     * (case-insensitive, with leading/trailing whitespace trimmed by the
     * service): {@code "MONTHLY"}, {@code "YEARLY"}, {@code "CUSTOM"}. Any
     * other value (including {@code null}, empty string, or whitespace)
     * yields the {@code 'Please select a report type...'} reject path.
     */
    private String reportMode;

    /**
     * Custom-mode report start date — {@code YYYY-MM-DD} ISO string holding
     * the start of the report time window. Consulted only when
     * {@link #reportMode} = {@code "CUSTOM"}; ignored for {@code MONTHLY}
     * and {@code YEARLY} modes (which derive the window from the injected
     * {@code Clock}). Maps to the COBOL {@code SDTYYYYI/SDTMMI/SDTDDI}
     * three-field input in {@code CORPT0AI} (lines 381-383 of CORPT00C.cbl).
     */
    private String startDate;

    /**
     * Custom-mode report end date — {@code YYYY-MM-DD} ISO string holding
     * the end of the report time window. Consulted only when
     * {@link #reportMode} = {@code "CUSTOM"}; ignored for {@code MONTHLY}
     * and {@code YEARLY} modes. Maps to the COBOL
     * {@code EDTYYYYI/EDTMMI/EDTDDI} three-field input in
     * {@code CORPT0AI} (lines 384-386 of CORPT00C.cbl).
     */
    private String endDate;

    /**
     * Operator confirmation token — the Java equivalent of
     * {@code CONFIRMI OF CORPT0AI} in {@code CORPT00C.cbl} (line 464).
     * Recognised values (case-insensitive, with trim): {@code "Y"}/{@code "y"}
     * to proceed with submission, {@code "N"}/{@code "n"} to cancel. Any
     * other value (including {@code null} or empty) is treated as a
     * cancellation per the
     * {@code ReportSubmissionService#submit(ReportSubmissionRequest)}
     * confirmation gate.
     */
    private String confirmation;

    /**
     * Default constructor — required for JSON deserialization (Jackson) and
     * for the setter-style population pattern used by the controller layer.
     * No fields are populated; the caller must invoke each setter before
     * passing the request to {@link ReportSubmissionService#submit(ReportSubmissionRequest)}.
     */
    public ReportSubmissionRequest() {
        // Intentionally empty — fields populated via setters.
    }

    /**
     * @return the report mode selector ({@code "MONTHLY"} / {@code "YEARLY"}
     *         / {@code "CUSTOM"}); may be {@code null} or empty (the
     *         service surfaces the canonical reject message in that case)
     */
    public String getReportMode() {
        return reportMode;
    }

    /**
     * Sets the report mode selector. Recognised values (case-insensitive,
     * with trim): {@code "MONTHLY"}, {@code "YEARLY"}, {@code "CUSTOM"}.
     *
     * @param reportMode the mode selector; may be {@code null} or empty,
     *                   in which case the service surfaces the
     *                   {@code 'Please select a report type...'} reject
     */
    public void setReportMode(String reportMode) {
        this.reportMode = reportMode;
    }

    /**
     * @return the custom-mode start date as a {@code YYYY-MM-DD} ISO string;
     *         may be {@code null} or empty (the service surfaces the
     *         canonical reject in that case when {@link #reportMode} =
     *         {@code "CUSTOM"})
     */
    public String getStartDate() {
        return startDate;
    }

    /**
     * Sets the custom-mode start date.
     *
     * @param startDate the {@code YYYY-MM-DD} ISO string; may be {@code null}
     *                  or empty
     */
    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    /**
     * @return the custom-mode end date as a {@code YYYY-MM-DD} ISO string;
     *         may be {@code null} or empty
     */
    public String getEndDate() {
        return endDate;
    }

    /**
     * Sets the custom-mode end date.
     *
     * @param endDate the {@code YYYY-MM-DD} ISO string; may be {@code null}
     *                or empty
     */
    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    /**
     * @return the operator confirmation token ({@code "Y"} / {@code "N"});
     *         may be {@code null} or empty (the service treats any value
     *         other than {@code "Y"}/{@code "y"} as cancellation)
     */
    public String getConfirmation() {
        return confirmation;
    }

    /**
     * Sets the operator confirmation token.
     *
     * @param confirmation the confirmation token ({@code "Y"}/{@code "y"}
     *                     to proceed, anything else cancels); may be
     *                     {@code null} or empty
     */
    public void setConfirmation(String confirmation) {
        this.confirmation = confirmation;
    }
}
