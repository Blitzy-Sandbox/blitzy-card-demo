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
 * Immutable request DTO for {@link BillPaymentService#payBill(BillPaymentRequest)} —
 * the Java replacement for the {@code COBIL0AI} BMS-mapped input record carrying the
 * target account identifier and the operator's confirmation prompt response from
 * {@code app/bms/COBIL00.bms} as read by {@code app/cbl/COBIL00C.cbl} (TRANID
 * {@code CB00}, the bill-payment dispatcher).
 *
 * <h2>COBOL Provenance — COBIL00C.cbl</h2>
 *
 * <p>The COBOL {@code RECEIVE-BILLPAY-SCREEN} paragraph (lines 306–314) populates the
 * {@code COBIL0AI} input map with the operator's keystroke. The
 * {@code PROCESS-ENTER-KEY} paragraph (lines 154–244) then consults two pieces of
 * state to dispatch the payment:
 *
 * <ul>
 *   <li>{@code ACTIDINI OF COBIL0AI PIC X(11)} — the 11-character zero-padded
 *       target account identifier. Empty/spaces → {@code 'Acct ID can NOT be
 *       empty...'} reject (lines 159–164). This is the {@link #accountId} field.</li>
 *   <li>{@code CONFIRMI OF COBIL0AI PIC X(01)} — the single-character
 *       confirmation prompt response (line 173 {@code EVALUATE CONFIRMI}).
 *       Accepted values are {@code 'Y'}/{@code 'y'} (proceed with payment) and
 *       {@code 'N'}/{@code 'n'} (cancel). Any other value rejects with the
 *       {@code 'Invalid value. Valid values are (Y/N)...'} message
 *       (lines 185–190). This is the {@link #confirmation} field.</li>
 * </ul>
 *
 * <h2>Bill Payment is Full Balance Only — No Amount Field</h2>
 *
 * <p>Unlike a generic payment service, {@code COBIL00C.cbl} pays off the FULL
 * current balance of the account in a single transaction (line 224
 * {@code MOVE ACCT-CURR-BAL TO TRAN-AMT}, line 234
 * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} which zeroes the
 * balance). There is no partial-payment workflow in COBIL00C, so this DTO
 * deliberately does not expose an {@code amount} field. Per AAP §0.10.2
 * Minimal Change Clause: "do not add features ... not present in the original
 * COBOL" — the migration preserves the full-balance-only semantic.
 *
 * <h2>Immutability</h2>
 *
 * <p>Both fields are {@code final} and populated only via the two-argument
 * constructor. There are no setters because the request is conceptually a
 * value object — once the controller layer constructs it, the service treats
 * it as read-only. Per AAP §0.10.10 style consistency: this matches the
 * positional-constructor convention used by every other small-fixed-set
 * request DTO in this package ({@link UserDeleteRequest},
 * {@link AdminMenuRequest}, {@link MainMenuRequest}).
 *
 * <h2>No Validation</h2>
 *
 * <p>This class deliberately performs no field validation in the constructor.
 * Per the established convention used by {@link UserDeleteRequest} /
 * {@link AdminMenuRequest} / {@link UserListRequest}, validation of the
 * payload (empty {@code accountId}, invalid {@code confirmation}) is performed
 * by the {@link BillPaymentService} so the reject paths emit the
 * COBOL-equivalent reject messages rather than
 * {@link IllegalArgumentException}. Carrying validation in the service also
 * keeps it visible to the test suite and countable for JaCoCo coverage
 * purposes (AAP §0.7.1).
 *
 * @see BillPaymentService
 * @see BillPaymentResult
 */
public final class BillPaymentRequest {

    /**
     * Target account identifier — the 11-character zero-padded {@code ACCT-ID}
     * primary key of the account whose full balance is to be paid off. Originates
     * from {@code ACTIDINI OF COBIL0AI PIC X(11)} in the COBOL {@code COBIL00C}
     * dispatcher. May be {@code null} or empty when the operator presses Enter
     * without entering an account ID; the service interprets that as the
     * {@code 'Acct ID can NOT be empty...'} reject (COBIL00C.cbl line 161).
     */
    private final String accountId;

    /**
     * Operator's confirmation response — the single-character {@code CONFIRMI}
     * prompt response (COBIL00C.cbl line 173 {@code EVALUATE CONFIRMI}).
     * Accepted values:
     * <ul>
     *   <li>{@code "Y"} or {@code "y"} — proceed with full-balance payment</li>
     *   <li>{@code "N"} or {@code "n"} — cancel payment (no DB access)</li>
     *   <li>any other value (including {@code null}, empty, single non-Y/N
     *       characters) — reject with the
     *       {@code 'Please confirm to make bill payment...'} message; no DB
     *       access occurs</li>
     * </ul>
     *
     * <p>The Java service performs the case-insensitive Y/N match before any
     * database access; rejects for non-Y/N values therefore short-circuit
     * without touching the repository layer (see
     * {@link BillPaymentService#payBill(BillPaymentRequest)} confirmation gate).
     */
    private final String confirmation;

    /**
     * Constructs an immutable bill-payment request with the supplied account
     * identifier and confirmation response. Per AAP §0.10.10, validation is
     * not performed in the constructor; the {@link BillPaymentService} surfaces
     * any rejection as a {@link BillPaymentResult} with the appropriate
     * COBOL-equivalent message.
     *
     * @param accountId    11-character zero-padded {@code ACCT-ID} primary key;
     *                     may be {@code null} or empty to trigger the
     *                     {@code 'Acct ID can NOT be empty...'} reject
     * @param confirmation single-character confirmation response; expected
     *                     {@code "Y"}/{@code "y"} or {@code "N"}/{@code "n"};
     *                     any other value triggers the
     *                     {@code 'Please confirm to make bill payment...'}
     *                     reject
     */
    public BillPaymentRequest(String accountId, String confirmation) {
        this.accountId = accountId;
        this.confirmation = confirmation;
    }

    /**
     * @return the 11-character zero-padded {@code ACCT-ID} primary key of the
     *         account whose full balance is to be paid off; may be {@code null}
     *         or empty (triggers the empty-account-ID reject)
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * @return the single-character confirmation response; expected
     *         {@code "Y"}/{@code "y"} or {@code "N"}/{@code "n"}; may be
     *         {@code null} or empty (triggers the confirmation-required
     *         reject)
     */
    public String getConfirmation() {
        return confirmation;
    }
}
