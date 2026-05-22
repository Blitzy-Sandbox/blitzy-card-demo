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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code CARDXREF} VSAM KSDS file's
 * {@code CARD-XREF-RECORD} record described by {@code app/cpy/CVACT03Y.cpy}.
 * Cross-references card numbers, customer identifiers, and account
 * identifiers — the bridge table that lets the read-only
 * {@link com.aws.carddemo.service.AccountViewService} discover the card
 * number associated with an account ID
 * (COBOL: {@code STARTBR}/{@code READNEXT CARDAIX} alternate-index path).
 *
 * <h2>COBOL Provenance — CVACT03Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 50-byte record:
 * <pre>
 *   01 CARD-XREF-RECORD.
 *      05 XREF-CARD-NUM PIC X(16). --&gt; {@link #cardNumber} (primary key)
 *      05 XREF-CUST-ID  PIC 9(09). --&gt; {@link #customerId}
 *      05 XREF-ACCT-ID  PIC 9(11). --&gt; {@link #accountId}
 *      05 FILLER        PIC X(14).
 * </pre>
 *
 * <p>The COBOL {@code CARDAIX} alternate index keys on {@code XREF-ACCT-ID}
 * rather than {@code XREF-CARD-NUM}, so {@code COACTVWC.cbl} can resolve
 * account-to-card the way {@link com.aws.carddemo.service.AccountViewService}
 * does via {@link com.aws.carddemo.repository.CardXrefRepository#findByAccountId(String)}.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.service.AccountViewService} compilation and the
 * account-view test suite. Subsequent migration agents (REFACTOR flavor) will
 * add JPA annotations ({@code @Entity}, {@code @Id}, {@code @Column},
 * {@code @Index} for the {@code accountId} alternate-index path), Bean
 * Validation constraints, and a proper equals/hashCode contract once the
 * entity is wired into the Hibernate {@code SessionFactory}.
 *
 * <h2>Security — toString() Includes Only Identifiers</h2>
 *
 * <p>{@link #toString()} surfaces all three fields ({@link #cardNumber},
 * {@link #customerId}, {@link #accountId}). Per AAP §0.10.5 the card number
 * IS a sensitive identifier — in production logging this field MUST be
 * masked by the logging framework's PCI/PII redaction filter (the
 * {@code logback-test.xml} turbofilter configured under
 * {@code src/test/resources/}). The {@link #toString()} method itself is
 * intentionally simple at the entity boundary; redaction is a logging
 * concern.
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see com.aws.carddemo.repository.CardXrefRepository
 */
@Entity
@Table(name = "card_xref", indexes = {
        @Index(name = "idx_cardxref_account_id", columnList = "xref_acct_id")
})
public class CardXref {

    /**
     * 16-character {@code XREF-CARD-NUM} primary key per {@code CVACT03Y.cpy}
     * ({@code PIC X(16)}). Stored as the credit-card PAN (Visa test PANs
     * {@code 4111111111111101}–{@code 4111111111111150} in the fixture
     * dataset).
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "xref_card_num", columnDefinition = "CHAR(16)", nullable = false, length = 16)
    private String cardNumber;

    /**
     * 9-character {@code XREF-CUST-ID} foreign key per {@code CVACT03Y.cpy}
     * ({@code PIC 9(09)}). Zero-padded numeric string referring to
     * {@link Customer#getCustomerId()}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "xref_cust_id", columnDefinition = "CHAR(9)", nullable = false, length = 9)
    private String customerId;

    /**
     * 11-character {@code XREF-ACCT-ID} foreign key per {@code CVACT03Y.cpy}
     * ({@code PIC 9(11)}). Zero-padded numeric string referring to
     * {@link Account#getAccountId()}. This is the column the {@code CARDAIX}
     * alternate index keys on, and the column that
     * {@link com.aws.carddemo.repository.CardXrefRepository#findByAccountId(String)}
     * resolves against.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "xref_acct_id", columnDefinition = "CHAR(11)", nullable = false, length = 11)
    private String accountId;

    /** Default no-arg constructor (required by JPA reflection-based instantiation). */
    public CardXref() {
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

    /** @return the 9-character zero-padded customer foreign key */
    public String getCustomerId() {
        return customerId;
    }

    /** @param customerId the 9-character zero-padded customer foreign key */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    /** @return the 11-character zero-padded account foreign key */
    public String getAccountId() {
        return accountId;
    }

    /** @param accountId the 11-character zero-padded account foreign key */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /** Equality based on the primary key {@link #cardNumber}. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXref)) {
            return false;
        }
        CardXref other = (CardXref) o;
        return Objects.equals(cardNumber, other.cardNumber);
    }

    /** Hash by primary key, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(cardNumber);
    }

    @Override
    public String toString() {
        return "CardXref{"
                + "cardNumber='" + cardNumber + '\''
                + ", customerId='" + customerId + '\''
                + ", accountId='" + accountId + '\''
                + '}';
    }
}
