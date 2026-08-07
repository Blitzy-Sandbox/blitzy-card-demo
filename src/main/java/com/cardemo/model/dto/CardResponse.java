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
 * Source      : app/cpy/CVACT02Y.cpy:L5 (CARD-NUM PIC X(16)),
 *               :L7 (CARD-CVV-CD PIC 9(03) - never emitted)
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
 *   <li><b>The card verification value.</b> {@code CARD-CVV-CD} at {@code app/cpy/CVACT02Y.cpy:L7} is
 *       authentication data. No symbolic map declares it and {@code app/cbl/COCRDUPC.cbl:L1108-L1112} sends
 *       the old embossed name, status and expiry components back to the screen without it, so the source
 *       never displayed it and neither does this. It is stored but has no read path at any layer, so no
 *       component here could carry it and {@link #oldDetails} does not either - see
 *       {@code CardUpdateService.checkChangeInRec9300} for why the concurrency question the source's
 *       {@code :1503} predicate asked is answered by the {@code @Version} column instead.</li>
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
 * @param oldDetails the {@code CCUP-OLD-DETAILS} group of {@code app/cbl/COCRDUPC.cbl:L291-L301} as the read
 *     projected it, present on a read and <b>null on a write</b>, to be echoed back unaltered as the
 *     {@code oldDetails} member of a subsequent update's request body. It is the group and not flat
 *     components because {@code app/cpy-bms/COCRDSL.CPY} declares fifteen fields and none of them is the
 *     expiry <em>day</em> that {@code :1507} compares, so this is the only route by which a client obtains
 *     it; and because the comparison at {@code :1498-1521} is a byte comparison after a one-sided fold, so
 *     none of its five comparison values may be trimmed or re-cased on the way out. Its
 *     {@code cardNumber} member is the one value it does <b>not</b> carry - see {@link #readOf}
 * @param cardKey the sealed, opaque reference to this card, present on a read and <b>null on a write</b>.
 *     It says <em>which</em> card, where {@code oldDetails} says <em>what was shown</em>; a client that
 *     arrived by filter can continue by reference without retaining the number it supplied. It is not a
 *     precondition and does not substitute for one. A write issues neither, because a caller intending a
 *     further edit re-reads, and the re-read issues both afresh
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
        CardUpdateRequest.CardDetails oldDetails,
        String cardKey) {

    /**
     * Builds the response for a card <b>read</b>, attaching the as-displayed group the matching update will
     * require.
     *
     * <p><b>The group is rebuilt with its card number withheld, and only that.</b> All five values
     * {@code :1503-1508} compares - the embossed name, the three expiry components and the active status -
     * are relayed byte for byte, because the comparison is byte-sensitive. The card number is not one of
     * them: {@code 9300-CHECK-CHANGE-IN-REC} never reads it, and the source did not obtain it from the
     * stored record either - {@code :1347} moves the <em>received</em> {@code CC-CARD-NUM} into
     * {@code CCUP-OLD-CARDID}. So the group's copy is redundant with the request's own identity field, while
     * emitting it here would hand back the full sixteen digits that {@link #maskedCardNumber} exists to
     * withhold. The update restores it from the request's own card number, citing the same line.</p>
     *
     * @param projection the service-produced card projection; must not be null
     * @param oldDetails the as-displayed group; may be null only when the caller could not obtain one, in
     *     which case the client will be unable to update and the reason belongs in the log
     * @param cardKey the opaque, sealed reference the update operation accepts in place of the card number
     *     this response does not disclose; may be null, in which case a client that has the card number
     *     already can still update, and one that does not cannot
     * @return the response; never null
     * @throws NullPointerException if {@code projection} is null
     */
    public static CardResponse readOf(final CardDto projection,
                                      final CardUpdateRequest.CardDetails oldDetails,
                                      final String cardKey) {
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
                withoutCardNumber(oldDetails),
                cardKey);
    }

    /**
     * Builds the response for a card <b>write</b>, carrying the values that were actually stored.
     *
     * <p>No as-displayed group is issued. A client that wishes to edit again reads the card again, which is
     * one request and removes any question of a stale group outliving the state it describes.</p>
     *
     * @param projection the refreshed card projection the write produced; must not be null
     * @return the response, with {@link #oldDetails} null; never null
     * @throws NullPointerException if {@code projection} is null
     */
    public static CardResponse writeOf(final CardDto projection) {
        // No group and no card reference. The rationale is one rationale, not two: a client that wants to
        // edit again reads the card again, and that read issues both. Handing out a reference here would
        // make it outlive the state it describes for no benefit.

        return readOf(projection, null, null);
    }

    /**
     * Rebuilds an as-displayed group without its card number.
     *
     * <p>The card number is a never-emit value on this type, and the group does not need to carry it: it is
     * not one of the five values {@code app/cbl/COCRDUPC.cbl:L1503-L1508} compares, and {@code :L1347} moves
     * the <em>received</em> {@code CC-CARD-NUM} into {@code CCUP-OLD-CARDID} rather than reading it from the
     * stored record, so the update restores it from the request's own identity field.</p>
     *
     * <p>Every other member is relayed by reference, unaltered.</p>
     *
     * @param oldDetails the projected group, or null when none was obtained
     * @return the group with its card number null, or null when {@code oldDetails} was null
     */
    private static CardUpdateRequest.CardDetails withoutCardNumber(
            final CardUpdateRequest.CardDetails oldDetails) {
        return oldDetails == null
                ? null
                : new CardUpdateRequest.CardDetails(oldDetails.accountId(), null,
                        oldDetails.cardData());
    }
}
