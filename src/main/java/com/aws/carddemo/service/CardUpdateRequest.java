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
 * Mutable request DTO for {@link CardUpdateService#updateCard(CardUpdateRequest)}
 * — the Java replacement for the {@code COCRDUPC.cbl} input record built
 * from the {@code CCRDUPA} BMS map fields plus the {@code CARD-UPDATE-RECORD}
 * structure ({@code app/cbl/COCRDUPC.cbl} lines 314–321). Carries the
 * operator-supplied card-update payload from the REST controller layer into
 * the service.
 *
 * <h2>COBOL Provenance — CARD-UPDATE-RECORD ({@code COCRDUPC.cbl} 314–321)</h2>
 *
 * <p>The COBOL record layout this DTO mirrors:
 * <pre>
 *   01 CARD-UPDATE-RECORD.
 *      05 CARD-UPDATE-NUM             PIC X(16). --&gt; {@link #cardNumber}     (immutable PK)
 *      05 CARD-UPDATE-ACCT-ID         PIC 9(11). --&gt; {@link #accountId}
 *      05 CARD-UPDATE-CVV-CD          PIC 9(03). --&gt; {@link #cvvCode}
 *      05 CARD-UPDATE-EMBOSSED-NAME   PIC X(50). --&gt; {@link #embossedName}
 *      05 CARD-UPDATE-EXPIRAION-DATE  PIC X(10). --&gt; {@link #expirationDate} (ISO YYYY-MM-DD)
 *      05 CARD-UPDATE-ACTIVE-STATUS   PIC X(01). --&gt; {@link #activeStatus}   ('Y' or 'N')
 * </pre>
 *
 * <p>The COBOL field name {@code CARD-UPDATE-EXPIRAION-DATE} retains the
 * misspelling ({@code EXPIRAION} instead of {@code EXPIRATION}) in the
 * source-of-truth COBOL for fidelity; the Java field is correctly named
 * {@link #expirationDate}. The same convention is used by
 * {@link Card#getExpirationDate()}.
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #version} carries the JPA optimistic-locking version counter
 *       supplied by the operator's prior screen render (loaded from
 *       {@link Card#getVersion()}). The {@link CardUpdateService} does not
 *       use this field directly because JPA's {@code @Version} field on the
 *       loaded {@link Card} entity is the actual source of truth; the field
 *       is carried on the DTO for symmetry with the response envelope and
 *       for future controller-layer round-trip semantics (the controller may
 *       wish to enforce that the client-supplied version matches the
 *       loaded version on first read before calling the service).</li>
 * </ul>
 *
 * <h2>Validation Contract</h2>
 *
 * <p>This DTO is a pure value carrier and performs no validation. All field
 * validation is centralised in
 * {@link CardUpdateService#updateCard(CardUpdateRequest)} so the test suite
 * can exercise the validation cascade through a single entry point per AAP
 * §0.10.1 (Require Test Coverage rule — tests call the production service
 * directly). The expected reject paths and their COBOL provenance are
 * enumerated in {@link CardUpdateResult}'s class-level Javadoc.
 *
 * <h2>Mutability — Setter-Based Construction</h2>
 *
 * <p>The class uses setter-based mutation (rather than a builder or
 * immutable record) so it integrates cleanly with JSON deserialization
 * frameworks (Jackson's default no-arg constructor + setter binding) without
 * additional annotations. This matches the convention used by
 * {@link UserUpdateRequest}, {@link UserAddRequest}, and the rest of the
 * service-request DTOs in this package.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link CardUpdateService} compilation and the
 * {@code CardUpdateServiceTest} unit test suite. Subsequent migration agents
 * (REFACTOR flavor) may add Bean Validation constraints
 * ({@code @NotBlank}, {@code @Pattern}, {@code @Size}) and Jackson
 * annotations once the controller layer is wired up.
 *
 * @see CardUpdateService
 * @see CardUpdateResult
 * @see Card
 */
public class CardUpdateRequest {

    /**
     * 16-character {@code CARD-UPDATE-NUM} per {@code COCRDUPC.cbl} line 315
     * ({@code PIC X(16)}) — the immutable primary key of the {@link Card}
     * entity being updated. The service rejects any value that is not
     * exactly 16 numeric digits ({@link CardUpdateService#MSG_CARD_NUMBER_INVALID}
     * for non-numeric or wrong-length input;
     * {@link CardUpdateService#MSG_CARD_NUMBER_REQUIRED} for null/empty/
     * whitespace input). The field is never mutated on the persisted entity
     * during an update (PK preservation contract — see the
     * {@code preservesCardNumberAsImmutableKey} unit test).
     */
    private String cardNumber;

    /**
     * 11-character {@code CARD-UPDATE-ACCT-ID} per {@code COCRDUPC.cbl} line
     * 316 ({@code PIC 9(11)}) — the zero-padded numeric account ID that owns
     * this card. Carried on the request for symmetry with the COBOL
     * record layout; subsequent migration agents may add account-cross-
     * reference validation against {@code CardXrefRepository} when the
     * full Spring controller layer is wired.
     */
    private String accountId;

    /**
     * 3-character {@code CARD-UPDATE-CVV-CD} per {@code COCRDUPC.cbl} line
     * 317 ({@code PIC 9(03)}) — the card-verification value. PCI-sensitive
     * credential; the service rejects any value that is not exactly 3
     * numeric digits ({@link CardUpdateService#MSG_CVV_INVALID}). Per AAP
     * §0.10.5 ("No financial data written to logs at any level"), the
     * {@link #toString()} method MUST be overridden by future migration
     * agents to mask this field; the current stub does not implement
     * {@code toString()} at all.
     */
    private String cvvCode;

    /**
     * 50-character {@code CARD-UPDATE-EMBOSSED-NAME} per {@code COCRDUPC.cbl}
     * line 318 ({@code PIC X(50)}) — the cardholder's name as physically
     * embossed on the card. The service rejects null, empty, or whitespace-
     * only values ({@link CardUpdateService#MSG_EMBOSSED_NAME_REQUIRED}).
     * Mirrors the COBOL {@code 1230-EDIT-NAME} paragraph (lines 808–830).
     */
    private String embossedName;

    /**
     * 10-character {@code CARD-UPDATE-EXPIRAION-DATE} per {@code COCRDUPC.cbl}
     * line 319 ({@code PIC X(10)}) — the card expiration date in ISO
     * {@code YYYY-MM-DD} format. The service parses this value through
     * {@link java.time.LocalDate#parse(CharSequence, java.time.format.DateTimeFormatter)}
     * with {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE}, which
     * is strict by default (Feb 30 is rejected, Feb 29 in a non-leap year
     * is rejected, alphabetic year is rejected, separator other than
     * {@code "-"} is rejected). Mirrors the COBOL {@code 1250-EDIT-EXPIRY-MON}
     * + {@code 1260-EDIT-EXPIRY-YEAR} paragraphs collapsed into a single
     * strict-date parse in the Java migration. The field name follows the
     * correct spelling ({@code expirationDate}); the COBOL copybook retains
     * the {@code EXPIRAION} misspelling for source-of-truth fidelity.
     */
    private String expirationDate;

    /**
     * Single-character {@code CARD-UPDATE-ACTIVE-STATUS} per
     * {@code COCRDUPC.cbl} line 320 ({@code PIC X(01)}) — the card's active
     * flag. The service rejects any value other than {@code "Y"} or
     * {@code "N"} ({@link CardUpdateService#MSG_ACTIVE_STATUS_INVALID}).
     * Mirrors the COBOL {@code 1240-EDIT-CARDSTATUS} paragraph.
     */
    private String activeStatus;

    /**
     * JPA optimistic-locking version counter loaded by the controller layer
     * from {@link Card#getVersion()} when it served the operator's edit
     * screen. May be {@code null} when the controller layer omits the field.
     * The {@link CardUpdateService} does not directly inspect this field —
     * JPA's {@code @Version} contract on the loaded {@link Card} entity
     * is the authoritative source of optimistic-locking state — but the
     * field is carried here for symmetry with the response envelope and
     * for future round-trip semantics (e.g., a controller-layer check that
     * the client-supplied version matches the loaded version before the
     * service call).
     */
    private Long version;

    /**
     * Default no-arg constructor — required by Jackson's default
     * deserialization contract and by reflection-based test frameworks.
     */
    public CardUpdateRequest() {
        // intentionally empty
    }

    /** @return the 16-character card number (immutable PK) */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the 16-character card number.
     *
     * @param cardNumber the card number; expected exactly 16 numeric digits.
     *                   Null, empty, whitespace-only, or non-numeric values
     *                   are rejected by {@link CardUpdateService}.
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /** @return the 11-character zero-padded account ID */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the 11-character account ID.
     *
     * @param accountId the zero-padded account ID owning this card
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /** @return the 3-character card-verification value (PCI-sensitive) */
    public String getCvvCode() {
        return cvvCode;
    }

    /**
     * Sets the 3-character card-verification value (PCI-sensitive).
     *
     * @param cvvCode the CVV; expected exactly 3 numeric digits. Null,
     *                empty, whitespace, non-numeric, and wrong-length
     *                values are rejected by {@link CardUpdateService}.
     */
    public void setCvvCode(String cvvCode) {
        this.cvvCode = cvvCode;
    }

    /** @return the 50-character embossed cardholder name */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Sets the 50-character embossed cardholder name.
     *
     * @param embossedName the cardholder's name as embossed on the card.
     *                     Null, empty, and whitespace-only values are
     *                     rejected by {@link CardUpdateService}.
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /** @return the card expiration date in ISO {@code YYYY-MM-DD} format */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param expirationDate the expiration date in ISO {@code YYYY-MM-DD}
     *                       format. Malformed dates (month 13, Feb 30,
     *                       Feb 29 in non-leap year, alphabetic year,
     *                       slash separator, empty) are rejected by
     *                       {@link CardUpdateService} via strict
     *                       {@link java.time.LocalDate#parse} validation.
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /** @return the single-character active status flag ({@code 'Y'} or {@code 'N'}) */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the single-character active status flag.
     *
     * @param activeStatus the active status; expected {@code "Y"} or
     *                     {@code "N"}. Any other value (including {@code "X"},
     *                     {@code "1"}, {@code "YES"}, single space, etc.)
     *                     is rejected by {@link CardUpdateService}.
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /** @return the JPA optimistic-locking version supplied by the controller, or {@code null} */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the JPA optimistic-locking version.
     *
     * @param version the version counter loaded from {@link Card#getVersion()}
     *                when the controller served the operator's edit screen;
     *                may be {@code null}
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
