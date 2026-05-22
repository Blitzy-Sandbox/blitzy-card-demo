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
 * Result DTO for {@link BillPaymentService#payBill(BillPaymentRequest)} — the
 * Java replacement for the {@code COBIL0AO} BMS-mapped output record's
 * {@code ERRMSGO} message field emitted by {@code app/cbl/COBIL00C.cbl} (TRANID
 * {@code CB00}, the bill-payment dispatcher). Encodes either a <em>success</em>
 * outcome carrying the {@code 'Payment successful. Your Transaction ID is XXX.'}
 * confirmation (COBIL00C.cbl lines 527–531), or a <em>failure</em> outcome
 * carrying one of the five COBOL-equivalent reject messages.
 *
 * <h2>COBOL Provenance — COBIL00C.cbl</h2>
 *
 * <p>The COBOL workflow writes one of the following messages to
 * {@code ERRMSGO OF COBIL0AO} via {@code SEND-BILLPAY-SCREEN} (lines 286–301)
 * depending on the outcome of {@code PROCESS-ENTER-KEY} (lines 154–244) and
 * {@code WRITE-TRANSACT-FILE} (lines 510–547):
 *
 * <ul>
 *   <li><b>Success</b> — {@code WRITE TRANSACT} returns {@code DFHRESP(NORMAL)}
 *       and {@code REWRITE ACCTDAT} succeeds (line 523):
 *       {@code STRING 'Payment successful. ' ' Your Transaction ID is ' TRAN-ID '.'}
 *       (lines 527–531). The {@link #success(String)} factory returns a
 *       success outcome carrying that exact message format.</li>
 *   <li><b>Empty account ID</b> — {@code ACTIDINI = SPACES OR LOW-VALUES} in
 *       {@code PROCESS-ENTER-KEY} (lines 159–164): {@code 'Acct ID can NOT
 *       be empty...'}.</li>
 *   <li><b>Account not found</b> — {@code READ-ACCTDAT-FILE} returns
 *       {@code DFHRESP(NOTFND)} (line 359):
 *       {@code 'Account ID NOT found...'}. The Java migration also surfaces
 *       this same reject when the {@code CXACAIX} cross-reference read fails
 *       (line 423–428) because both paths represent a missing account in the
 *       backing store from the operator's perspective.</li>
 *   <li><b>Zero balance</b> — {@code ACCT-CURR-BAL <= ZEROS} (line 198–201):
 *       {@code 'You have nothing to pay...'}. Bill payment is a full-balance
 *       payoff so a zero-balance account has nothing to settle.</li>
 *   <li><b>Confirmation cancelled</b> — {@code CONFIRMI = 'N' OR 'n'}
 *       (lines 178–181): the COBOL workflow clears the screen and re-prompts.
 *       The Java migration surfaces this as the explicit
 *       {@code 'Confirmation cancelled by user. Try again'} reject message
 *       (Java-migration phrasing aligned with the REST flow where there is no
 *       on-screen redisplay).</li>
 *   <li><b>Invalid confirmation</b> — {@code CONFIRMI} is not {@code Y/N} (lines
 *       185–190 {@code 'Invalid value. Valid values are (Y/N)...'} or lines
 *       182–184 {@code SPACES/LOW-VALUES} which fall through to
 *       {@code 'Confirm to make a bill payment...'} at lines 237–238). The
 *       Java migration collapses both branches into the single
 *       {@code 'Please confirm to make bill payment...'} reject because the
 *       REST request payload has no separate "blank vs invalid" distinction —
 *       any non-Y/N value is treated as a request for confirmation.</li>
 * </ul>
 *
 * <p>I/O errors (the COBOL {@code WHEN OTHER} branches at lines 365–371,
 * 396–402, 429–436, 540–547) surface in the Java migration as
 * {@link org.springframework.dao.DataAccessException} subclasses propagated
 * from the repository; the service does not catch them, letting the
 * controller layer's exception-handler chain produce the Java equivalent of
 * the COBOL {@code 'Unable to lookup Account...'} / {@code 'Unable to Add
 * Bill pay Transaction...'} response. This mirrors the convention used by
 * {@link TransactionDetailService} and {@link UserDeleteService}.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied {@code 'Payment successful. Your Transaction ID is XXX.'}
 *       message.</li>
 *   <li>{@link #failure(String)} returns {@code success = false} with one
 *       of the five COBOL-equivalent reject messages.</li>
 * </ul>
 *
 * <p>The constructor is private; no production or test code instantiates
 * this class directly. The {@code success} and {@code message} fields are
 * {@code final} (set once at construction) so the result is effectively
 * immutable after the factory returns it.
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 */
public final class BillPaymentResult {

    /**
     * Whether the bill-payment operation succeeded ({@code true}) or was
     * rejected ({@code false}). Drives the controller's HTTP status code
     * mapping: success → 200 OK (with the success confirmation in the response
     * body); failure → 400 Bad Request (with the rejection message in the
     * response body) for empty / not-found / zero-balance / confirmation
     * scenarios.
     */
    private final boolean success;

    /**
     * Human-readable outcome message — one of the COBOL-equivalent strings
     * enumerated in the class-level COBOL Provenance section. Never
     * {@code null} (both factory methods require a non-{@code null} message).
     */
    private final String message;

    /**
     * Private constructor enforcing the factory-method-only construction
     * contract. Both fields are populated exactly once.
     *
     * @param success outcome flag
     * @param message outcome message
     */
    private BillPaymentResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by {@link BillPaymentService#payBill(BillPaymentRequest)}
     * after {@code WRITE TRANSACT} returns {@code DFHRESP(NORMAL)} and
     * {@code REWRITE ACCTDAT} succeeds; the canonical message format is
     * {@code 'Payment successful. Your Transaction ID is XXX.'}
     * (mirroring the COBOL {@code STRING} construct at lines 527–531).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static BillPaymentResult success(String message) {
        return new BillPaymentResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link BillPaymentService#payBill(BillPaymentRequest)}
     * for all five reject branches (empty account ID, account not found,
     * zero balance, cancelled confirmation, invalid confirmation).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link BillPaymentService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static BillPaymentResult failure(String message) {
        return new BillPaymentResult(false, message);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any of
     *         the five reject outcomes (empty account ID, account not found,
     *         zero balance, cancelled confirmation, invalid confirmation)
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
}
