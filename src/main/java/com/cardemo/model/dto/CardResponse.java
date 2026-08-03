/*
 * ******************************************************************
 * Program     : CardResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for one card: the business fields
 *               of the card detail and card update screens with the
 *               primary account number masked, the 3270 furniture
 *               dropped, and the sealed as-displayed snapshot attached
 *               on a read.
 * Source      : app/cbl/COCRDSLC.cbl (887 lines) over mapset COCRDSL,
 *               app/cpy-bms/COCRDSL.CPY:54-108 (15 input fields)
 *               @ 7756d89
 * Source      : app/cbl/COCRDUPC.cbl (1,560 lines) over mapset
 *               COCRDUP, app/cpy-bms/COCRDUP.CPY:54-120 (17 input
 *               fields, including EXPDAYI which COCRDSL lacks)
 *               @ 7756d89
 * Source      : app/cpy/CVACT02Y.cpy:L18 (CARD-NUM PIC X(16)),
 *               :L20 (CARD-CVV-CD PIC X(03) - never emitted)
 *               @ 7756d89
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
 * One card, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the wire type for the card detail read and the card update write. It is deliberately
 * <em>not</em> {@link CardDto}: that type is the faithful transcription of the symbolic maps, carries the
 * six screen-header fields and the function-key captions, and carries the primary account number in full
 * because the 3270 screen displayed it in full. All three properties are correct for a field contract and
 * wrong for a public response, so the contract stays where it is and the response is this type.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>The full card number.</b> {@link #maskedCardNumber} carries the {@link ApiMasking} rendering and
 *       nothing else. There is no component, accessor or serialised property anywhere on this type from
 *       which the full sixteen digits can be recovered.</li>
 *   <li><b>The card verification value.</b> {@code CARD-CVV-CD} at {@code app/cpy/CVACT02Y.cpy:L20} is
 *       authentication data. No symbolic map declares it and {@code app/cbl/COCRDUPC.cbl:L1108-L1112} sends
 *       the old embossed name, status and expiry components back to the screen without it, so the source
 *       never displayed it and neither does this. It travels only inside {@link #snapshotToken}, sealed.</li>
 *   <li><b>The screen header and the function-key captions.</b> {@code TRNNAME}, {@code TITLE01},
 *       {@code CURDATE}, {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}, {@code FKEYS} and
 *       {@code FKEYSC} describe a terminal, not a card. A client knows the date and does not have function
 *       keys.</li>
 *   <li><b>Every CICS and 3270 mechanism.</b> No next-program, next-mapset, next-map, cursor position,
 *       colour attribute or field-protection flag appears here.</li>
 *   </ul>
 *
 * <h2>Inputs, outputs, side effects</h2>
 *
 * <p><b>Inputs.</b> Built only by its two static factories, from a service-produced {@link CardDto}.
 * <b>Outputs.</b> Serialised by Jackson; every component is a JSON property and there are no others.
 * <b>Side effects.</b> None; the type is an immutable record and its factories are pure.</p>
 *
 * <h2>Failure modes</h2>
 *
 * <p>The factories reject a null projection with {@link NullPointerException}, which is a wiring defect
 * rather than a request outcome. Nothing else here can fail: a response is being rendered, and every
 * component is optional on the wire because the source itself leaves fields blank on some paths.</p>
 *
 * @param accountId the eleven-character account identifier, {@code ACCTSIDI PIC X(11)} at
 *     {@code app/cpy-bms/COCRDSL.CPY:60}; may be null when the source left it blank
 * @param maskedCardNumber the card number reduced by {@link ApiMasking#maskCardNumber(String)}; may be null
 * @param cardholderName the embossed name, {@code CRDNAMEI PIC X(50)} at
 *     {@code app/cpy-bms/COCRDSL.CPY:78}; may be null
 * @param cardStatusCode the active status as a raw one-character code, {@code CRDSTCDI PIC X(1)} at
 *     {@code app/cpy-bms/COCRDSL.CPY:84}. Relayed as text and not as a boolean, because the map declares a
 *     character and the corpus tests it against the literals {@code 'Y'} and {@code 'N'}; may be null
 * @param expiryMonth the expiry month as text, {@code EXPMONI PIC X(2)}; relayed as text and never as a date
 *     type, because the map declares characters and parsing them would invent a validation the map does not
 *     express; may be null
 * @param expiryYear the expiry year as text, {@code EXPYEARI PIC X(4)}, on the same terms; may be null
 * @param informationMessage the screen information message, {@code INFOMSGI}; the source's own literal,
 *     relayed byte for byte because it is a caption that discloses no field value; may be null
 * @param errorMessage the screen error message, {@code ERRMSGI}, on the same terms; may be null
 * @param snapshotToken the sealed as-displayed snapshot to return in {@code If-Match} on a subsequent
 *     update, present on a read and <b>null on a write</b>. It is opaque: a client cannot read it, alter it
 *     undetected, use it for another card or use it indefinitely. It is the only way the update precondition
 *     can be satisfied, because the values it protects include ones this type must never emit
 */
public record CardResponse(
        String accountId,
        String maskedCardNumber,
        String cardholderName,
        String cardStatusCode,
        String expiryMonth,
        String expiryYear,
        String informationMessage,
        String errorMessage,
        String snapshotToken) {

    /**
     * Builds the response for a card <b>read</b>, attaching the sealed snapshot the matching update will
     * require.
     *
     * @param projection the service-produced card projection; must not be null
     * @param snapshotToken the sealed as-displayed snapshot; may be null only when the caller could not
     *     obtain one, in which case the client will be unable to update and the reason belongs in the log
     * @return the response; never null
     * @throws NullPointerException if {@code projection} is null
     */
    public static CardResponse readOf(final CardDto projection, final String snapshotToken) {
        Objects.requireNonNull(projection, "projection must not be null");
        return new CardResponse(
                projection.getAccountId(),
                ApiMasking.maskCardNumber(projection.getCardNumber()),
                projection.getCardholderName(),
                projection.getCardStatusCode(),
                projection.getExpiryMonth(),
                projection.getExpiryYear(),
                projection.getInformationMessage(),
                projection.getErrorMessage(),
                snapshotToken);
    }

    /**
     * Builds the response for a card <b>write</b>, carrying the values that were actually stored.
     *
     * <p>No snapshot token is issued. A client that wishes to edit again reads the card again, which is one
     * request and removes any question of a token outliving the state it describes.</p>
     *
     * @param projection the refreshed card projection the write produced; must not be null
     * @return the response, with {@link #snapshotToken} null; never null
     * @throws NullPointerException if {@code projection} is null
     */
    public static CardResponse writeOf(final CardDto projection) {
        return readOf(projection, null);
    }
}
