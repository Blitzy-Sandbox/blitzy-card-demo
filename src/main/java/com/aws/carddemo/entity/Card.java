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
package com.aws.carddemo.entity;

import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code CARDDAT} VSAM KSDS file's
 * {@code CARD-RECORD} record described by {@code app/cpy/CVACT02Y.cpy}.
 * Represents a single credit card with primary-key access by 16-character
 * card number — the input source for the read-only
 * {@link com.aws.carddemo.service.CardDetailService} (Java migration of
 * {@code app/cbl/COCRDSLC.cbl}, TRANID {@code CCDL}).
 *
 * <h2>COBOL Provenance — CVACT02Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 150-byte record:
 * <pre>
 *   01 CARD-RECORD.
 *      05 CARD-NUM             PIC X(16). --&gt; {@link #cardNumber}      (primary key)
 *      05 CARD-ACCT-ID         PIC 9(11). --&gt; {@link #accountId}       (zero-padded numeric)
 *      05 CARD-CVV-CD          PIC 9(03). --&gt; {@link #cvvCode}         (3-digit security code)
 *      05 CARD-EMBOSSED-NAME   PIC X(50). --&gt; {@link #embossedName}    (cardholder name)
 *      05 CARD-EXPIRAION-DATE  PIC X(10). --&gt; {@link #expirationDate}  (ISO YYYY-MM-DD)
 *      05 CARD-ACTIVE-STATUS   PIC X(01). --&gt; {@link #activeStatus}    ({@code 'Y'} or {@code 'N'})
 *      05 FILLER               PIC X(59).
 * </pre>
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #version} is a JPA {@code @Version} field for optimistic
 *       locking — the Java replacement for COBOL's before/after-image record
 *       comparison used by {@code COCRDUPC.cbl} (card update). Read-only
 *       lookups in {@link com.aws.carddemo.service.CardDetailService} do
 *       not increment the version, so the field is set but never mutated in
 *       this code path.</li>
 *   <li>The COBOL field name {@code CARD-EXPIRAION-DATE} retains the
 *       misspelling ({@code EXPIRAION} instead of {@code EXPIRATION}) in
 *       the copybook for source-of-truth fidelity. The Java field is
 *       correctly named {@link #expirationDate}; record-layout
 *       serialisation handles the COBOL mapping.</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.service.CardDetailService} compilation and the
 * card-detail test suite. Subsequent migration agents (REFACTOR flavor) will
 * add JPA annotations ({@code @Entity}, {@code @Id}, {@code @Column},
 * {@code @Version}), Bean Validation constraints, and a proper equals/hashCode
 * contract once the entity is wired into the Hibernate {@code SessionFactory}.
 *
 * <h2>Security — toString() Excludes Sensitive Fields</h2>
 *
 * <p>{@link #toString()} explicitly omits {@link #cvvCode} (the 3-digit
 * card-verification value is a PCI-sensitive credential) and surfaces
 * {@link #cardNumber} only as a primary-key identifier. In production
 * logging the {@link #cardNumber} field MUST be masked by the logging
 * framework's PCI/PII redaction filter (the {@code logback-test.xml}
 * turbofilter configured under {@code src/test/resources/}). The
 * {@link #toString()} method itself is intentionally simple at the entity
 * boundary; redaction is a logging concern.
 *
 * @see com.aws.carddemo.service.CardDetailService
 * @see com.aws.carddemo.repository.CardRepository
 */
public class Card {

    /**
     * 16-character {@code CARD-NUM} primary key per {@code CVACT02Y.cpy}
     * ({@code PIC X(16)}). Stored as the credit-card PAN (Visa test PANs
     * {@code 4111111111111101}–{@code 4111111111111150} in the fixture
     * dataset).
     */
    private String cardNumber;

    /**
     * 11-character {@code CARD-ACCT-ID} foreign key per {@code CVACT02Y.cpy}
     * ({@code PIC 9(11)}). Zero-padded numeric string referring to
     * {@link Account#getAccountId()}. The COBOL workflow stores this as a
     * numeric value; the Java migration carries it as a fixed-width
     * zero-padded string to preserve the byte-for-byte VSAM key format.
     */
    private String accountId;

    /**
     * 3-character {@code CARD-CVV-CD} card-verification value per
     * {@code CVACT02Y.cpy} ({@code PIC 9(03)}). PCI-sensitive credential
     * carried as a string to preserve potential leading zeros (e.g.
     * {@code "001"} through {@code "999"}). Never written to log output
     * per AAP §0.10.5 — the {@link #toString()} method explicitly omits
     * this field.
     */
    private String cvvCode;

    /**
     * 50-character {@code CARD-EMBOSSED-NAME} per {@code CVACT02Y.cpy}
     * ({@code PIC X(50)}). The cardholder's name as it appears physically
     * embossed on the credit card (typically {@code "FIRSTNAME LASTNAME"}
     * with trailing spaces to fill the field width).
     */
    private String embossedName;

    /**
     * 10-character {@code CARD-EXPIRAION-DATE} per {@code CVACT02Y.cpy}
     * ({@code PIC X(10)}). Stored as an ISO-style {@code YYYY-MM-DD} string
     * to preserve the COBOL record format. The Java migration field name
     * uses the correct spelling ({@code expirationDate}); the COBOL
     * copybook retains the misspelling for source-of-truth fidelity.
     *
     * <p>The {@link com.aws.carddemo.service.CardDetailService} derives a
     * boolean {@code isExpired} flag from this value by comparing it
     * against the injected {@link java.time.Clock} — a display-only
     * enhancement that did not exist in COBOL but is needed for the REST
     * API contract.
     */
    private String expirationDate;

    /**
     * Single-character {@code CARD-ACTIVE-STATUS} flag per
     * {@code CVACT02Y.cpy} ({@code PIC X(01)}). Conventionally {@code 'Y'}
     * (active) or {@code 'N'} (inactive); other values are reserved for
     * future use. Drives reject-code paths in posting workflows
     * ({@code CBTRN02C}: inactive cards rejected with code 102).
     */
    private String activeStatus;

    /**
     * JPA optimistic-locking version. Replaces COBOL's before/after-image
     * record comparison used by {@code COCRDUPC.cbl}; incremented
     * automatically by Hibernate on each {@code save()} once the
     * {@code @Version} annotation is added by subsequent REFACTOR-flavor
     * agents. Read-only paths in
     * {@link com.aws.carddemo.service.CardDetailService} do not mutate this
     * field.
     */
    private Long version;

    /** Default no-arg constructor (required by JPA reflection-based instantiation). */
    public Card() {
        // intentionally empty
    }

    /** @return the 16-character card number (Visa PAN) */
    public String getCardNumber() {
        return cardNumber;
    }

    /** @param cardNumber the 16-character card number (Visa PAN) */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /** @return the 11-character zero-padded account foreign key */
    public String getAccountId() {
        return accountId;
    }

    /** @param accountId the 11-character zero-padded account foreign key */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /** @return the 3-character card-verification value (PCI-sensitive) */
    public String getCvvCode() {
        return cvvCode;
    }

    /** @param cvvCode the 3-character card-verification value (PCI-sensitive) */
    public void setCvvCode(String cvvCode) {
        this.cvvCode = cvvCode;
    }

    /** @return the 50-character embossed cardholder name */
    public String getEmbossedName() {
        return embossedName;
    }

    /** @param embossedName the 50-character embossed cardholder name */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /** @return the card expiration date (ISO-style {@code YYYY-MM-DD}) */
    public String getExpirationDate() {
        return expirationDate;
    }

    /** @param expirationDate the card expiration date (ISO-style {@code YYYY-MM-DD}) */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /** @return the single-character active status flag ({@code 'Y'} or {@code 'N'}) */
    public String getActiveStatus() {
        return activeStatus;
    }

    /** @param activeStatus the single-character active status flag */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /** @return the JPA optimistic-locking version */
    public Long getVersion() {
        return version;
    }

    /** @param version the JPA optimistic-locking version */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Equality is based on the primary key {@link #cardNumber} alone.
     * JPA-managed entities are considered equal iff they share the same
     * primary key value; the version and other mutable state are
     * deliberately excluded from equality so transient and managed copies
     * of the same logical card compare equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Card)) {
            return false;
        }
        Card other = (Card) o;
        return Objects.equals(cardNumber, other.cardNumber);
    }

    /** Hash by primary key, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(cardNumber);
    }

    /**
     * Diagnostic string deliberately omitting {@link #cvvCode} (PCI-sensitive)
     * per AAP §0.10.5 ("No financial data written to logs at any level").
     * Exposes only the non-sensitive identifiers and status flags safe to
     * surface in operational log lines.
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNumber='" + cardNumber + '\''
                + ", accountId='" + accountId + '\''
                + ", embossedName='" + embossedName + '\''
                + ", expirationDate='" + expirationDate + '\''
                + ", activeStatus='" + activeStatus + '\''
                + ", version=" + version
                + '}';
    }
}
