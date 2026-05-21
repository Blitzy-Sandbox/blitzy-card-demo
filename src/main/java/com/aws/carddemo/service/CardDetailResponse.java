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

import com.aws.carddemo.entity.Card;

/**
 * Result DTO for {@link CardDetailService#getCard(String)} — the Java
 * replacement for the {@code CCRDSLA} BMS-mapped output record emitted
 * by {@code app/cbl/COCRDSLC.cbl} (TRANID {@code CCDL}). Encodes either a
 * <em>success</em> outcome carrying the hydrated card fields (plus the
 * Java-migration-added {@link #expired} display flag) ready for the REST
 * controller layer to serialise, or a <em>failure</em> outcome carrying
 * the COBOL-equivalent reject message (NOTFND reject path).
 *
 * <h2>COBOL Provenance — COCRDSLC.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL field-mapping block that
 * populates the {@code CCRDSLA} BMS output map after a successful
 * {@code 9100-GETCARD-BYACCTCARD} read of {@code CARDDAT}:
 * <ul>
 *   <li>{@code CARD-NUM} → {@link #cardNumber}</li>
 *   <li>{@code CARD-ACCT-ID} → {@link #accountId}</li>
 *   <li>{@code CARD-CVV-CD} → {@link #cvvCode} (PCI-sensitive, surfaced only
 *       to authenticated controllers and never logged)</li>
 *   <li>{@code CARD-EMBOSSED-NAME} → {@link #embossedName}</li>
 *   <li>{@code CARD-EXPIRAION-DATE} → {@link #expirationDate}</li>
 *   <li>{@code CARD-ACTIVE-STATUS} → {@link #activeStatus}</li>
 * </ul>
 *
 * <p>The failure outcome corresponds to the COBOL
 * {@code DFHRESP(NOTFND)} branch of {@code 9100-GETCARD-BYACCTCARD}
 * (lines 755–761):
 * <ul>
 *   <li>{@code WHEN DFHRESP(NOTFND)} → reject with the Java equivalent
 *       of the COBOL {@code 'Did not find cards for this search
 *       condition'} message; this Java migration normalises that
 *       phrasing to the more conventional {@code "Card number not
 *       found..."} (still anchoring on the "not found" phrase that
 *       downstream consumers and tests look for).</li>
 *   <li>{@code WHEN OTHER} (I/O error, lines 762–771) → surfaces in
 *       production as a propagated
 *       {@link org.springframework.dao.DataAccessException} from the
 *       repository layer; the service does not translate this branch into
 *       a {@code CardDetailResponse.failure(...)} value because the
 *       controller's exception-handler chain owns the
 *       infrastructure-error response shape (mirroring the COBOL
 *       {@code HANDLE ABEND} fallback).</li>
 * </ul>
 *
 * <h2>Java Migration Addition — {@code expired} Display Flag</h2>
 *
 * <p>The COBOL workflow displays the {@code CARD-EXPIRAION-DATE} field
 * verbatim on the {@code CCRDSLA} BMS map and lets the operator interpret
 * it. The Java migration adds a derived {@link #expired} boolean flag
 * (computed by {@link CardDetailService} against the injected
 * {@link java.time.Clock}) so downstream REST consumers can render
 * expired-card indicators without parsing the date string themselves.
 *
 * <p>This is a display-only enhancement that does not exist in the COBOL
 * source. It is computed by the service layer on every successful lookup
 * and carried on the response DTO; the value of {@link #expirationDate}
 * is untouched (preserving AAP §0.10.4 immutable-boundaries — downstream
 * consumers reading {@link #expirationDate} continue to see the same byte
 * sequence the COBOL baseline produced).
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so that the invariant between {@link #success} and {@link #message}
 * (and between {@link #success} and the populated data fields) cannot be
 * violated: {@link #failure(String)} returns a failure outcome carrying
 * only the reject message (all data fields {@code null}, {@link #expired}
 * {@code false}); {@link #success(Card, boolean)} returns a success
 * outcome with all data fields populated from the hydrated entity and
 * the supplied {@link #expired} flag. The constructor is package-private;
 * no production or test code instantiates this class directly.
 *
 * <p>The class deliberately exposes <strong>only getters</strong> — no
 * setters and no public no-args constructor — so the response is
 * effectively read-only after factory construction. This is in line with
 * AAP §0.10.2 (Minimal Change Clause: "Do not introduce patterns,
 * abstractions, or optimizations beyond what the migration requires") and
 * AAP §0.10.4 (Immutable Boundaries: downstream consumers see the
 * hydrated fields exactly as the COBOL baseline produced them, plus the
 * documented Java-migration-added {@link #expired} flag).
 *
 * <p>Jackson — the JSON serialiser used by the Spring MVC REST controller
 * layer — serialises out-bound responses via the public getter methods
 * only, so removing setters has no impact on the wire-format contract.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link CardDetailService} in
 * {@code com.aws.carddemo.service} (rather than under a dedicated DTO
 * subpackage), matching the convention established by
 * {@link AccountViewResponse}, {@link TransactionDetailResponse},
 * {@link MainMenuResponse}, and {@link AdminMenuResponse} for
 * service/request/response triples that are tightly coupled to a single
 * service contract.
 *
 * @see CardDetailService
 * @see Card
 */
public class CardDetailResponse {

    /**
     * {@code true} when the lookup succeeded and the card was found;
     * {@code false} for the NOTFND reject branch.
     *
     * <p>Tests assert via {@link #isSuccess()}: {@code .isTrue()} for the
     * happy-path test, {@code .isFalse()} for the reject-path test.
     */
    private boolean success;

    /**
     * Reject message populated when {@link #success} is {@code false};
     * {@code null} when {@link #success} is {@code true}. Carries the
     * Java-migration equivalent of the COBOL {@code WS-MESSAGE} /
     * {@code WS-RETURN-MSG} reject string anchored on the "not found"
     * phrase (per AAP §0.10.4 immutable-boundaries clause — downstream
     * consumers reading the JSON error envelope continue to see the same
     * discriminating reason text as the COBOL baseline).
     */
    private String message;

    /** 16-character {@code CARD-NUM}; {@code null} on reject. */
    private String cardNumber;
    /** 11-character {@code CARD-ACCT-ID}; {@code null} on reject. */
    private String accountId;
    /** 3-character {@code CARD-CVV-CD}; {@code null} on reject. PCI-sensitive. */
    private String cvvCode;
    /** 50-character {@code CARD-EMBOSSED-NAME}; {@code null} on reject. */
    private String embossedName;
    /** 10-character {@code CARD-EXPIRAION-DATE} (ISO {@code YYYY-MM-DD}); {@code null} on reject. */
    private String expirationDate;
    /** Single-character {@code CARD-ACTIVE-STATUS} ({@code 'Y'} / {@code 'N'}); {@code null} on reject. */
    private String activeStatus;

    /**
     * Java-migration display flag — {@code true} when the card's
     * {@link #expirationDate} is strictly earlier than "today" as resolved
     * via the {@link CardDetailService}'s injected {@link java.time.Clock}.
     * {@code false} on a successful lookup of a non-expired card and
     * {@code false} on any failure outcome (the field defaults to
     * {@code false} when {@link #success} is {@code false}).
     */
    private boolean expired;

    /**
     * Package-private no-args constructor. The only callers are the two
     * static factory methods on this class ({@link #success(Card, boolean)}
     * and {@link #failure(String)}). Production code paths inside
     * {@link CardDetailService} always invoke a factory method so the
     * invariant between {@link #success}, {@link #message}, and the data
     * fields cannot be violated by an external caller.
     */
    CardDetailResponse() {
        // intentionally empty — fields are populated by the factory
        // methods through direct field assignment.
    }

    /**
     * Factory method for failure outcomes. Returns a response with
     * {@link #success} {@code false}, the supplied reject message set,
     * every data field left {@code null}, and {@link #expired}
     * {@code false}.
     *
     * @param message the COBOL-equivalent reject message
     *                (e.g. {@code "Card number not found..."})
     * @return a populated failure response
     */
    public static CardDetailResponse failure(String message) {
        CardDetailResponse response = new CardDetailResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    /**
     * Factory method for success outcomes. Returns a response with
     * {@link #success} {@code true}, {@link #message} {@code null}, every
     * data field hydrated from the supplied card entity, and the
     * {@link #expired} flag set to the supplied value (computed by
     * {@link CardDetailService} via the injected {@link java.time.Clock}).
     *
     * <p>Maps every field that {@code COCRDSLC.cbl} populates on the
     * {@code CCRDSLA} BMS map (preserving AAP §0.10.4 immutable-boundaries
     * clause — downstream consumers see the same fields as the COBOL
     * baseline, plus the documented Java-migration-added {@link #expired}
     * flag).
     *
     * @param card    the hydrated {@link Card} entity from {@code CARDDAT}
     * @param expired {@code true} when the card's expiration date is
     *                strictly earlier than "now" per the service's
     *                injected clock; {@code false} otherwise
     * @return a populated success response
     */
    public static CardDetailResponse success(Card card, boolean expired) {
        CardDetailResponse response = new CardDetailResponse();
        response.success = true;
        // Card identifier fields (PK + FK)
        response.cardNumber = card.getCardNumber();
        response.accountId = card.getAccountId();
        // PCI-sensitive verification code — surfaced only to authenticated
        // controllers; redaction at the logging boundary is enforced by
        // the logback turbofilter (AAP §0.10.5).
        response.cvvCode = card.getCvvCode();
        // Display-oriented fields
        response.embossedName = card.getEmbossedName();
        response.expirationDate = card.getExpirationDate();
        response.activeStatus = card.getActiveStatus();
        // Java-migration display flag (derived by the service from the
        // injected Clock; not present in COBOL).
        response.expired = expired;
        return response;
    }

    /** @return {@code true} when the lookup succeeded, {@code false} on the NOTFND reject path */
    public boolean isSuccess() {
        return success;
    }

    /** @return the reject message ({@code null} on success) */
    public String getMessage() {
        return message;
    }

    /** @return the 16-character card number ({@code null} on reject) */
    public String getCardNumber() {
        return cardNumber;
    }

    /** @return the 11-character zero-padded account foreign key ({@code null} on reject) */
    public String getAccountId() {
        return accountId;
    }

    /** @return the 3-character card-verification value ({@code null} on reject; PCI-sensitive) */
    public String getCvvCode() {
        return cvvCode;
    }

    /** @return the 50-character embossed cardholder name ({@code null} on reject) */
    public String getEmbossedName() {
        return embossedName;
    }

    /** @return the card expiration date (ISO-style {@code YYYY-MM-DD}; {@code null} on reject) */
    public String getExpirationDate() {
        return expirationDate;
    }

    /** @return the single-character active status flag ({@code null} on reject) */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * @return {@code true} when the card's expiration date is strictly
     *         earlier than "today" per the service's injected clock;
     *         {@code false} on a successful lookup of a non-expired card
     *         and {@code false} on any failure outcome
     */
    public boolean isExpired() {
        return expired;
    }
}
