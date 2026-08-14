/*
 * ******************************************************************
 * Program     : TransactionResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for one transaction: the business
 *               fields of the transaction detail and transaction add
 *               screens with the primary account number masked and the
 *               3270 furniture dropped.
 * Source      : app/cbl/COTRN01C.cbl (330 lines) over mapset COTRN01,
 *               app/cpy-bms/COTRN01.CPY:17-144 (21 input fields)
 *               @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl (783 lines) over mapset COTRN02,
 *               :L58-L59 (WS-TRAN-AMT-N PIC S9(9)V99 and
 *               WS-TRAN-AMT-E PIC +99999999.99) @ 7756d89
 * Source      : app/cpy/CVTRA05Y.cpy (350-byte TRAN-RECORD, card
 *               number at offset 263 for 16 bytes) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.dto;

import java.util.Objects;

/**
 * One transaction, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the wire type for the transaction detail read and the transaction add write. It is deliberately
 * not {@link TransactionDto}: that type transcribes the symbolic maps and therefore carries the six
 * screen-header fields, the echoed search-key field, the page number, the row table, and the card number in
 * full. All of those are correct as a field contract; the last is a primary account number and may not be
 * emitted over HTTP.</p>
 *
 * <h2>Money is text, exactly as the source rendered it</h2>
 *
 * <p>{@link #amount} carries the display rendering the source produced -
 * {@code WS-TRAN-AMT-E PIC +99999999.99} at {@code app/cbl/COTRN02C.cbl:L59}, twelve characters with a
 * mandatory sign - and not a JSON number. That is not a stylistic choice: the picture has eight integer
 * digits while {@code WS-TRAN-AMT-N PIC S9(9)V99} at {@code :L58} holds nine, so the source itself discards a
 * ninth integer digit when it renders. Emitting a JSON number would silently repair that, changing what the
 * screen showed. A negative amount is genuine - the posting logic accumulates negatives into the cycle debit -
 * and is never normalised to an absolute value.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>The full card number.</b> {@link #maskedCardNumber} is the {@link ApiMasking} rendering and there
 *       is no component from which the full sixteen digits can be recovered.</li>
 *   <li><b>The screen header.</b> {@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME},
 *       {@code TITLE02} and {@code CURTIME} describe a terminal.</li>
 *   <li><b>The echoed search key, the page number and the row table.</b> A single-transaction response
 *       carries one transaction; the list envelope carries pages.</li>
 *   </ul>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}, from a service-produced {@link TransactionDto}. <b>Outputs.</b>
 * Serialised by Jackson; every component is a JSON property and there are no others. <b>Side effects.</b>
 * None. <b>Failure modes.</b> A null projection is a wiring defect and raises
 * {@link NullPointerException}.</p>
 *
 * @param transactionId the sixteen-character identifier, {@code TRNIDI PIC X(16)}; may be null on a path
 *     where the source left it blank
 * @param maskedCardNumber the card number reduced by {@link ApiMasking#maskCardNumber(String)}; may be null
 * @param typeCode the two-character transaction type, {@code TTYPCDI PIC X(2)}; may be null
 * @param categoryCode the four-character category, {@code TCATCDI PIC X(4)}; may be null
 * @param source the ten-character source, {@code TRNSRCI PIC X(10)}; may be null
 * @param description the hundred-character description, {@code TDESCI PIC X(100)}; may be null
 * @param amount the amount on the legacy display mask, as text; may be null
 * @param originatingDate the originating timestamp as text, {@code TORIGDTI PIC X(26)}; may be null
 * @param processingDate the processing timestamp as text, {@code TPROCDTI PIC X(26)}; may be null
 * @param merchantId the merchant identifier, {@code MIDI PIC X(9)}; may be null
 * @param merchantName the merchant name, {@code MNAMEI PIC X(50)}; may be null
 * @param merchantCity the merchant city, {@code MCITYI PIC X(50)}; may be null
 * @param merchantZip the merchant postal code, {@code MZIPI PIC X(10)}; may be null
 * @param statusMessage {@code ERRMSGI}, the byte-exact screen literal the turn produced, relayed unchanged
 *     because it is a caption that discloses no field value. It is named a status rather than an error
 *     because the source uses the same field for both a refusal and a confirmation notice; may be null
 */
public record TransactionResponse(
        String transactionId,
        String maskedCardNumber,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        String amount,
        String originatingDate,
        String processingDate,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String statusMessage) {

    /**
     * Projects a service-produced transaction onto its response form, masking the card number.
     *
     * @param projection the projection the service produced; must not be null
     * @return the response; never null
     * @throws NullPointerException if {@code projection} is null
     */
    public static TransactionResponse of(final TransactionDto projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        return new TransactionResponse(
                projection.transactionId(),
                ApiMasking.maskCardNumber(projection.cardNumber()),
                projection.typeCode(),
                projection.categoryCode(),
                projection.source(),
                projection.description(),
                projection.amount(),
                projection.originatingDate(),
                projection.processingDate(),
                projection.merchantId(),
                projection.merchantName(),
                projection.merchantCity(),
                projection.merchantZip(),
                projection.errorMessage());
    }
}
