/*
 * ******************************************************************
 * Program     : ApiMasking.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 *               support
 * Function    : The single rule by which a card number is reduced
 *               before it may appear in an HTTP response body, so that
 *               the REST surface never emits a primary account number
 *               the 3270 screens rendered in full.
 * Source      : app/cpy/CVACT02Y.cpy:L18 (CARD-NUM PIC X(16), the
 *               16-byte primary account number) @ 7756d89
 * Source      : app/cpy-bms/COCRDSL.CPY:66 (CARDSIDI PIC X(16)),
 *               app/cpy-bms/COCRDLI.CPY (CRDNUM01..07 PIC X(16)),
 *               app/cpy-bms/COTRN01.CPY (CARDNUMI PIC X(16))
 *               - the screens that displayed it in full @ 7756d89
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
 * The one masking rule the REST surface applies to a card number.
 *
 * <h2>What it does and why it exists once</h2>
 *
 * <p>The legacy screens displayed the sixteen-byte primary account number of {@code CVACT02Y.cpy:L18} in
 * full, because a 3270 terminal in a card-operations centre is a controlled physical environment. An HTTP
 * response is not: it is logged by proxies, cached by clients, captured by browser tooling and forwarded by
 * gateways. Emitting the full value over HTTP would therefore disclose it far more widely than the source
 * ever did, so every response type that carries a card reference carries a masked one.</p>
 *
 * <p>The rule lives here, once, rather than in each response type. A masking rule implemented three times is
 * a rule that will eventually be implemented three different ways, and the one that is wrong will be the one
 * that is not tested.</p>
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li>A value with more than {@value #VISIBLE_DIGITS} characters keeps its last {@value #VISIBLE_DIGITS}
 *       and every earlier character becomes {@value #MASK_CHARACTER}. Length is therefore preserved, which
 *       keeps a sixteen-digit number distinguishable from a malformed one <em>without</em> disclosing any
 *       part of the number that identifies the card.</li>
 *   <li>A value of {@value #VISIBLE_DIGITS} characters or fewer is masked <em>in full</em>. Showing the last
 *       four of a four-character value would show all of it.</li>
 *   <li>{@code null} is returned unchanged, so "no card reference" stays distinct from "a masked one".</li>
 *   <li>A blank value is returned unchanged. The card-list rows of {@code app/cbl/COCRDLIC.cbl} are padded
 *       to the seven-row table depth with blanks, and turning a blank filler row into a run of asterisks
 *       would invent a card where the source displayed none.</li>
 *   </ul>
 *
 * <p>Trailing blanks are significant and are preserved: the symbolic maps declare fixed-width fields and the
 * value may arrive space padded, so the mask is applied to the trimmed extent while the padding is kept.</p>
 *
 * <h2>Inputs, outputs, side effects and failure modes</h2>
 *
 * <p><b>Inputs.</b> One card number as text, in any state including null and blank. <b>Outputs.</b> The
 * masked rendering. <b>Side effects.</b> None; the method is pure. <b>Failure modes.</b> None: there is no
 * input this method rejects, because a response is being built and refusing to render one is not an option
 * the caller can act on. Callers validate; this masks.</p>
 *
 * <p>This class is a stateless holder of one static method, is {@code final}, and cannot be instantiated.</p>
 */
public final class ApiMasking {

    /**
     * How many trailing characters survive masking, namely four - the industry-conventional last four, and
     * the largest suffix that identifies a card to its holder without identifying it to anyone else.
     */
    public static final int VISIBLE_DIGITS = 4;

    /** The character every masked position becomes. */
    public static final char MASK_CHARACTER = '*';

    /**
     * Not instantiable: this class is one rule, not an object.
     */
    private ApiMasking() {
        throw new AssertionError("ApiMasking holds one static rule and is never instantiated");
    }

    /**
     * Masks a card number for emission in an HTTP response.
     *
     * @param cardNumber the card number as the service produced it, possibly space padded to its declared
     *     sixteen-byte width; may be null and may be blank
     * @return null when {@code cardNumber} is null, the value unchanged when it is blank, and otherwise the
     *     masked rendering described on this class; never longer or shorter than the input
     */
    public static String maskCardNumber(final String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return cardNumber;
        }
        final int significantLength = significantLengthOf(cardNumber);
        final int visible = significantLength > VISIBLE_DIGITS ? VISIBLE_DIGITS : 0;
        final StringBuilder masked = new StringBuilder(cardNumber.length());
        masked.append(String.valueOf(MASK_CHARACTER).repeat(significantLength - visible));
        masked.append(cardNumber, significantLength - visible, significantLength);
        masked.append(cardNumber, significantLength, cardNumber.length());
        return masked.toString();
    }

    /**
     * Returns the length of the value up to but excluding its trailing blanks, which is the extent the mask
     * is applied to.
     *
     * @param value a non-blank value
     * @return the index one past the last non-blank character; at least one, because the value is not blank
     */
    private static int significantLengthOf(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return end;
    }
}
