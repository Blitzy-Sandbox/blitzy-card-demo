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
 * Result DTO for {@link ReportSubmissionService#submit(ReportSubmissionRequest)} —
 * the Java replacement for the {@code CORPT0AO} BMS-mapped output record's
 * {@code ERRMSGO} message field emitted by {@code app/cbl/CORPT00C.cbl}
 * (TRANID {@code CR00}, the transaction-report submission dispatcher).
 * Encodes either a <em>success</em> outcome carrying the
 * {@code '... report submitted for printing ...'} confirmation plus the
 * dispatcher-assigned {@link ReportJobHandle}, or a <em>failure</em>
 * outcome carrying one of the seven COBOL-equivalent (or Java-migration-added)
 * reject messages with a {@code null} job handle.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl</h2>
 *
 * <p>The COBOL workflow writes one of the following messages to
 * {@code ERRMSGO OF CORPT0AO} via {@code SEND-TRNRPT-SCREEN} (lines 556-580)
 * depending on the outcome of {@code PROCESS-ENTER-KEY} (lines 208-456) plus
 * {@code SUBMIT-JOB-TO-INTRDR} (lines 462-510):
 *
 * <ul>
 *   <li><b>Success</b> — {@code WIRTE-JOBSUB-TDQ} returns
 *       {@code DFHRESP(NORMAL)} for every JCL record (lines 525-527):
 *       {@code STRING WS-REPORT-NAME ' report submitted for printing ...'}
 *       (lines 448-454). The {@link #success(String, ReportJobHandle)}
 *       factory returns a success outcome carrying that exact message plus
 *       the dispatcher-assigned handle.</li>
 *   <li><b>No report mode selected</b> — {@code WHEN OTHER} branch of the
 *       three-way mode {@code EVALUATE} (lines 437-442):
 *       {@code 'Select a report type to print report...'}.</li>
 *   <li><b>Custom-mode start date empty</b> — empty-field checks at lines
 *       259-279 of CORPT00C.cbl (three separate paragraphs for month, day,
 *       year): {@code 'Start Date - Month/Day/Year can NOT be empty...'}.
 *       The Java migration collapses the three COBOL sub-fields into a
 *       single ISO-date string and surfaces one unified message anchored
 *       on the load-bearing phrases {@code "start date"} and {@code "empty"}.</li>
 *   <li><b>Custom-mode end date empty</b> — empty-field checks at lines
 *       280-300 of CORPT00C.cbl: {@code 'End Date - Month/Day/Year can NOT
 *       be empty...'}. Java migration uses one unified message anchored on
 *       {@code "end date"} and {@code "empty"}.</li>
 *   <li><b>Start date invalid</b> — {@code CSUTLDTC} validation fails on
 *       start date (lines 388-406): {@code 'Start Date - Not a valid
 *       date...'}.</li>
 *   <li><b>End date invalid</b> — {@code CSUTLDTC} validation fails on
 *       end date (lines 408-426): {@code 'End Date - Not a valid date...'}.</li>
 *   <li><b>Start &gt; End</b> — Java-migration addition (no COBOL
 *       equivalent — the COBOL flow does not check the date ordering
 *       before submitting the JCL, leaving the validation to the
 *       downstream {@code CBTRN03C} report processor). The Java migration
 *       enforces the {@code start &lt;= end} invariant at the submission
 *       boundary so the operator gets immediate feedback rather than
 *       waiting for the batch job to fail. Message:
 *       {@code 'End Date should be greater than Start Date'}.</li>
 *   <li><b>Confirmation cancelled</b> — {@code CONFIRMI = 'N' OR 'n'}
 *       branch of the confirmation {@code EVALUATE} (lines 480-483 of
 *       CORPT00C.cbl). The COBOL flow initialises all fields and
 *       redisplays the screen with no message; the Java migration returns
 *       an explicit {@code 'Confirmation cancelled by user'} message so
 *       the REST controller layer can surface a clear cancellation
 *       outcome to downstream callers.</li>
 * </ul>
 *
 * <p>Infrastructure errors (the COBOL {@code WHEN OTHER} branch of
 * {@code WIRTE-JOBSUB-TDQ} at lines 528-534
 * {@code 'Unable to Write TDQ (JOBS)...'}) surface in the Java migration as
 * {@link RuntimeException} subclasses propagated from
 * {@link ReportJobDispatcher#dispatch(ReportJobParameters)}; the service
 * does not catch them, letting the controller layer's exception-handler
 * chain produce the Java equivalent of the {@code 'Unable to Write...'}
 * response.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success},
 * {@link #message}, and {@link #jobHandle} cannot be violated:
 * <ul>
 *   <li>{@link #success(String, ReportJobHandle)} returns
 *       {@code success = true} with the supplied {@code '... report
 *       submitted for printing ...'} message AND the dispatcher-assigned
 *       handle.</li>
 *   <li>{@link #failure(String)} returns {@code success = false} with one
 *       of the seven COBOL-equivalent (or Java-migration-added) reject
 *       messages AND a {@code null} job handle (no job was dispatched).</li>
 * </ul>
 *
 * <p>The constructor is private; no production or test code instantiates
 * this class directly. The three fields are {@code final} (set once at
 * construction) so the result is effectively immutable after the factory
 * returns it.
 *
 * @see ReportSubmissionService
 * @see ReportSubmissionRequest
 * @see ReportJobHandle
 */
public final class ReportSubmissionResult {

    /**
     * Whether the report submission succeeded ({@code true}) or was
     * rejected ({@code false}). Drives the controller's HTTP status code
     * mapping: success → 202 Accepted (the batch job has been queued, not
     * yet completed); failure → 400 Bad Request (with the rejection
     * message in the response body) for any of the reject paths.
     */
    private final boolean success;

    /**
     * Human-readable outcome message — one of the COBOL-equivalent strings
     * enumerated in the class-level COBOL Provenance section, or the
     * canonical Java-side {@code 'Confirmation cancelled by user'} /
     * {@code 'End Date should be greater than Start Date'} for the two
     * Java-migration additions. Never {@code null} (both factory methods
     * require a non-{@code null} message).
     */
    private final String message;

    /**
     * Dispatcher-assigned handle for the submitted job — non-{@code null}
     * only on the success path; {@code null} for every reject outcome (no
     * job was dispatched). Carried separately from {@link #message} so
     * downstream callers can poll job status without parsing the
     * confirmation message.
     */
    private final ReportJobHandle jobHandle;

    /**
     * Private constructor enforcing the factory-method-only construction
     * contract. All three fields are populated exactly once.
     *
     * @param success   outcome flag
     * @param message   outcome message
     * @param jobHandle dispatcher handle (non-{@code null} only on success)
     */
    private ReportSubmissionResult(boolean success, String message, ReportJobHandle jobHandle) {
        this.success = success;
        this.message = message;
        this.jobHandle = jobHandle;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message
     * and dispatcher handle.
     *
     * <p>Used by {@link ReportSubmissionService#submit(ReportSubmissionRequest)}
     * after {@link ReportJobDispatcher#dispatch(ReportJobParameters)}
     * returns a non-{@code null} handle; the canonical message format is
     * {@code '... report submitted for printing ...'} (mirroring the COBOL
     * {@code STRING} construct at lines 448-454 of CORPT00C.cbl).
     *
     * @param message   the success confirmation message; expected
     *                  non-{@code null}
     * @param jobHandle the dispatcher-assigned handle for the submitted
     *                  job; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true} and a
     *         non-{@code null} {@code getJobHandle()}
     */
    public static ReportSubmissionResult success(String message, ReportJobHandle jobHandle) {
        return new ReportSubmissionResult(true, message, jobHandle);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link ReportSubmissionService#submit(ReportSubmissionRequest)}
     * for all reject branches (no mode, empty start date, empty end date,
     * invalid start date, invalid end date, start-after-end, confirmation
     * cancelled). The returned outcome has a {@code null} job handle to
     * indicate no job was dispatched.
     *
     * @param message the rejection message; expected non-{@code null} and
     *                to match one of the COBOL-equivalent (or
     *                Java-migration-added) literals declared on
     *                {@link ReportSubmissionService}
     * @return a failure outcome with {@code isSuccess() == false} and
     *         {@code getJobHandle() == null}
     */
    public static ReportSubmissionResult failure(String message) {
        return new ReportSubmissionResult(false, message, null);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any
     *         of the reject outcomes
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return the human-readable outcome message (success confirmation or
     *         rejection reason); never {@code null}
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return the dispatcher-assigned handle for the submitted job;
     *         non-{@code null} only when {@link #isSuccess()} returns
     *         {@code true}; {@code null} for every reject outcome (because
     *         no job was dispatched)
     */
    public ReportJobHandle getJobHandle() {
        return jobHandle;
    }
}
