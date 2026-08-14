/*
 * ******************************************************************
 * Program     : BillPaymentResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for a bill payment: which of the
 *               three no-failure endings the turn reached, the balance
 *               echo, the generated transaction identifier, and the
 *               byte-exact screen caption.
 * Source      : app/cbl/COBIL00C.cbl (572 lines) over mapset COBIL00
 *               :L173-L191 (the four-arm confirmation gate; 'N' and
 *               'n' clear the screen and write nothing),
 *               :L193-L194 (the balance echo), :L197-L205
 *               ('You have nothing to pay...'),
 *               :L212-L219 (the descending-browse identifier),
 *               :L224 (the payment is the FULL balance),
 *               :L234-L235 (the balance is driven to zero),
 *               :L527-L531 (the success notice and its double space)
 *               @ 7756d89
 * Source      : app/cpy-bms/COBIL00.CPY (10 input fields) @ 7756d89
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

/**
 * The outcome of a bill payment, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It reports which of the operation's three no-failure endings was reached and the data that ending
 * produced. It is not the service's screen record, which additionally carries the six screen-header members,
 * the message kind standing in for a colour attribute, and the cursor field standing in for
 * {@code SEND MAP ... CURSOR}. Those three describe how a 3270 terminal painted a screen and have no place on
 * a JSON contract.</p>
 *
 * <h2>The three endings, and why cancellation is one of them</h2>
 *
 * <ul>
 *   <li>{@link Outcome#SETTLED} - the confirmation was {@code 'Y'} or {@code 'y'}, the balance was above zero,
 *       a transaction was written and the balance was driven to exactly zero. The payment is always the
 *       <b>full</b> balance: {@code app/cbl/COBIL00C.cbl:L224} moves the entire current balance into the
 *       transaction amount, and there is no partial-payment path anywhere in the program.</li>
 *   <li>{@link Outcome#CONFIRMATION_REQUIRED} - the confirmation was blank or low values, so the source
 *       prompted and wrote nothing.</li>
 *   <li>{@link Outcome#CANCELLED} - the confirmation was {@code 'N'} or {@code 'n'}.
 *       {@code app/cbl/COBIL00C.cbl:L178-L181} clears the screen and raises the input-error flag, and writes
 *       nothing. <b>That is a valid answer to a valid request, not a client error.</b> The operator was asked
 *       whether to pay, said no, and the system did as it was told. Reporting it as a rejected request would
 *       misstate the source and would tell a client to correct input that was already correct.</li>
 *   </ul>
 *
 * <p>Every other ending the operation can reach is a typed failure and never produces this type.</p>
 *
 * <h2>Money is text, exactly as the source rendered it</h2>
 *
 * <p>{@link #currentBalance} carries the {@code CURBALI PIC X(14)} rendering on the source's
 * {@code +9999999999.99} mask, as text. The balance guard itself - {@code IF ACCT-CURR-BAL <= ZEROS} at
 * {@code app/cbl/COBIL00C.cbl:L198} - is evaluated as a scaled decimal comparison inside the service and never
 * by a binary floating type and never by equality.</p>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> The five components below, supplied by the operation. <b>Outputs.</b> Serialised by
 * Jackson; every component is a JSON property and there are no others. <b>Side effects.</b> None.
 * <b>Failure modes.</b> None: a failing turn raises a typed exception instead of producing this type.</p>
 *
 * @param outcome which of the three no-failure endings was reached; never null
 * @param accountId the account as submitted and echoed by the screen. Blank after a settled payment, because
 *     {@code :L524} runs {@code INITIALIZE-ALL-FIELDS} before the success notice is composed - preserved
 *     rather than repaired; may be null
 * @param currentBalance the balance echo on the legacy display mask, as text. Blank after a settled payment
 *     for the same reason; may be null
 * @param transactionId the sixteen-digit identifier the descending browse generated, present only on
 *     {@link Outcome#SETTLED} and null otherwise
 * @param message the byte-exact screen caption from {@code WS-MESSAGE}. Empty on cancellation, because
 *     {@code :L180-L181} sets the flag and emits no literal at all - an absence that is itself source
 *     behaviour and is preserved; may be null
 */
public record BillPaymentResponse(
        Outcome outcome,
        String accountId,
        String currentBalance,
        String transactionId,
        String message) {

    /**
     * The three endings of a bill payment that are not failures.
     *
     * <p>Named for what happened to the money rather than for the 3270 arm that produced it, because a client
     * branches on the former. The mapping to the source's own outcome vocabulary is given on each constant.</p>
     */
    public enum Outcome {

        /**
         * The balance was paid in full and driven to zero. {@code app/cbl/COBIL00C.cbl:L224} and
         * {@code :L234-L235}.
         */
        SETTLED,

        /**
         * The confirmation was blank or low values, so the operator was prompted and nothing was written.
         * {@code app/cbl/COBIL00C.cbl:L236-L239}.
         */
        CONFIRMATION_REQUIRED,

        /**
         * The confirmation was {@code 'N'} or {@code 'n'}: the screen was cleared and nothing was written.
         * {@code app/cbl/COBIL00C.cbl:L178-L181}.
         */
        CANCELLED
    }
}
