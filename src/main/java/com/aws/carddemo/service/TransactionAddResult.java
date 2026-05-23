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
 * Result DTO for {@link TransactionAddService#addTransaction(TransactionAddRequest)}
 * — the Java replacement for the {@code COTRN2AO} BMS-mapped output record's
 * {@code ERRMSGO} message field emitted by {@code app/cbl/COTRN02C.cbl}
 * (TRANID {@code CT02}, the transaction-add dispatcher). Encodes either a
 * <em>success</em> outcome carrying the
 * {@code 'Transaction added successfully. Your Transaction ID is XXX.'}
 * confirmation, or a <em>failure</em> outcome carrying one of the
 * COBOL-equivalent reject messages produced by the
 * {@code VALIDATE-INPUT-KEY-FIELDS} (lines 193–230) and
 * {@code VALIDATE-INPUT-DATA-FIELDS} (lines 235–437) cascades.
 *
 * <h2>COBOL Provenance — COTRN02C.cbl</h2>
 *
 * <p>The COBOL workflow writes one of the following messages to
 * {@code ERRMSGO OF COTRN2AO} via {@code SEND-TRNADD-SCREEN} (lines 553–564)
 * depending on the outcome of {@code PROCESS-ENTER-KEY} (lines 173–248) plus
 * {@code ADD-TRANSACTION} (lines 442–466):
 *
 * <h3>Success branch</h3>
 * <ul>
 *   <li><b>Transaction written</b> — {@code WRITE-TRANSACT-FILE} returns
 *       {@code DFHRESP(NORMAL)}: {@code STRING 'Transaction added
 *       successfully. ' ' Your Transaction ID is ' TRAN-ID '.'} (mirrors
 *       the COBOL {@code STRING} construct after {@code WRITE-TRANSACT-FILE}
 *       at lines 451–466). The {@link #success(String)} factory returns a
 *       success outcome carrying that message.</li>
 * </ul>
 *
 * <h3>Key-fields validation rejects (COTRN02C lines 193–230)</h3>
 * <ul>
 *   <li><b>Both account ID and card number empty</b> — neither field is
 *       supplied: {@code 'Account or Card Number must be entered...'}</li>
 *   <li><b>Account ID non-numeric</b> — supplied but contains a non-digit
 *       character: {@code 'Account ID must be Numeric...'}</li>
 *   <li><b>Card number non-numeric</b> — supplied but contains a non-digit
 *       character: {@code 'Card Number must be Numeric...'}</li>
 * </ul>
 *
 * <h3>Data-fields validation rejects (COTRN02C lines 235–437)</h3>
 * <p>Empty checks (in COBOL paragraph order):
 * <ul>
 *   <li>{@code 'Type CD can NOT be empty...'} (TTYPCDI empty)</li>
 *   <li>{@code 'Category CD can NOT be empty...'} (TCATCDI empty)</li>
 *   <li>{@code 'Source can NOT be empty...'} (TRNSRCI empty)</li>
 *   <li>{@code 'Description can NOT be empty...'} (TDESCI empty)</li>
 *   <li>{@code 'Amount can NOT be empty...'} (TRNAMTI empty)</li>
 *   <li>{@code 'Orig Date can NOT be empty...'} (TORIGDTI empty)</li>
 *   <li>{@code 'Proc Date can NOT be empty...'} (TPROCDTI empty)</li>
 *   <li>{@code 'Merchant ID can NOT be empty...'} (MIDI empty)</li>
 *   <li>{@code 'Merchant Name can NOT be empty...'} (MNAMEI empty)</li>
 *   <li>{@code 'Merchant City can NOT be empty...'} (MCITYI empty)</li>
 *   <li>{@code 'Merchant Zip can NOT be empty...'} (MZIPI empty)</li>
 * </ul>
 *
 * <p>Numeric checks:
 * <ul>
 *   <li>{@code 'Type CD must be Numeric...'} (TTYPCDI non-numeric)</li>
 *   <li>{@code 'Category CD must be Numeric...'} (TCATCDI non-numeric)</li>
 *   <li>{@code 'Merchant ID must be Numeric...'} (MIDI non-numeric)</li>
 * </ul>
 *
 * <p>Format checks:
 * <ul>
 *   <li>{@code 'Amount should be in format -99999999.99'} (TRNAMTI does not
 *       match the {@code [-+]\d{8}\.\d{2}} edited pattern)</li>
 *   <li>{@code 'Orig Date should be in format YYYY-MM-DD'} (TORIGDTI does not
 *       match the {@code \d{4}-\d{2}-\d{2}} pattern)</li>
 *   <li>{@code 'Proc Date should be in format YYYY-MM-DD'} (TPROCDTI does not
 *       match the {@code \d{4}-\d{2}-\d{2}} pattern)</li>
 * </ul>
 *
 * <p>Semantic date validation (Java replacement for CSUTLDTC):
 * <ul>
 *   <li>{@code 'Orig Date - Not a valid date...'} (TORIGDTI fails the
 *       {@code java.time.LocalDate.parse(...)} resolution that mirrors
 *       the COBOL {@code CEEDAYS} call: invalid month, invalid day,
 *       non-leap-year February 29, etc.)</li>
 *   <li>{@code 'Proc Date - Not a valid date...'} (TPROCDTI fails the same
 *       resolution)</li>
 * </ul>
 *
 * <p>I/O errors (the COBOL {@code WHEN OTHER} branches after the various
 * file operations) surface in the Java migration as
 * {@link org.springframework.dao.DataAccessException} subclasses propagated
 * from the repository; the service does not catch them, letting the
 * controller layer's exception-handler chain produce the Java equivalent of
 * the COBOL {@code 'Unable to Add Transaction...'} response.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied success-confirmation message.</li>
 *   <li>{@link #failure(String)} returns {@code success = false} with one
 *       of the COBOL-equivalent reject messages.</li>
 * </ul>
 *
 * <p>The constructor is private; no production or test code instantiates
 * this class directly. The {@code success} and {@code message} fields are
 * {@code final} (set once at construction) so the result is effectively
 * immutable after the factory returns it.
 *
 * @see TransactionAddService
 * @see TransactionAddRequest
 */
public final class TransactionAddResult {

    /**
     * Whether the add operation succeeded ({@code true}) or was rejected
     * ({@code false}). Drives the controller's HTTP status code mapping:
     * success → 201 Created (with the success confirmation in the response
     * body); validation failure → 400 Bad Request (with the rejection
     * message in the response body).
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
    private TransactionAddResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by {@link TransactionAddService#addTransaction(TransactionAddRequest)}
     * after {@code WRITE-TRANSACT-FILE} returns {@code DFHRESP(NORMAL)}; the
     * canonical message format is
     * {@code 'Transaction added successfully. Your Transaction ID is XXX.'}
     * mirroring the COBOL {@code STRING} construct emitted after
     * {@code ADD-TRANSACTION} (lines 442–466).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static TransactionAddResult success(String message) {
        return new TransactionAddResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link TransactionAddService#addTransaction(TransactionAddRequest)}
     * for every reject branch in the validation cascade (key-fields rejects,
     * 11 empty-field rejects, 3 numeric rejects, amount-format reject, 2
     * date-format rejects, 2 date-semantic rejects).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link TransactionAddService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static TransactionAddResult failure(String message) {
        return new TransactionAddResult(false, message);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any of
     *         the reject outcomes enumerated in the COBOL Provenance section
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
