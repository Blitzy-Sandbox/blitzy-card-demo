/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the {@code cards} table, mapping the COBOL {@code CARD-RECORD}
 * layout from copybook {@code app/cpy/CVACT02Y.cpy} (RECLN 150) at source commit
 * {@code 27d6c6f}.
 *
 * <p>The 16-character card number ({@code CARD-NUM}) is the natural primary key;
 * the trailing 59-byte {@code FILLER} is reserved padding and is not persisted.
 * The {@code version} column backs JPA optimistic locking, reproducing the
 * legacy {@code 9300-CHECK-CHANGE-IN-REC} re-read-and-compare concurrency
 * guard.</p>
 */
@Entity
@Table(name = "cards")
public class Card implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * {@code CARD-NUM PIC X(16)} — 16-character card number; natural primary key.
     * The {@link SqlTypes#CHAR} JDBC type aligns this {@code String} mapping with
     * the fixed-length {@code CHAR(16)} column so Hibernate schema validation
     * accepts the {@code bpchar} type reported by PostgreSQL.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /** {@code CARD-ACCT-ID PIC 9(11)} — owning account identifier. */
    @Column(name = "card_acct_id")
    private Long cardAcctId;

    /** {@code CARD-CVV-CD PIC 9(03)} — card verification value. */
    @Column(name = "cvv_cd")
    private Integer cvvCd;

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} — name embossed on the card. */
    @Column(name = "embossed_name", length = 50)
    private String embossedName;

    /**
     * {@code CARD-EXPIRAION-DATE PIC X(10)} — card expiration date. The column
     * name {@code expiraion_date} preserves the legacy COBOL misspelling.
     */
    @Column(name = "expiraion_date", length = 10)
    private String expirationDate;

    /**
     * {@code CARD-ACTIVE-STATUS PIC X(01)} — active status flag. The
     * {@link SqlTypes#CHAR} JDBC type aligns this {@code String} mapping with the
     * fixed-length {@code CHAR(1)} column for Hibernate schema validation.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    /** Optimistic-lock version counter ({@code 9300-CHECK-CHANGE-IN-REC}). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Default no-argument constructor required by the JPA provider.
     */
    public Card() {
        // Required by JPA.
    }

    /**
     * Creates a fully populated {@code Card}.
     *
     * @param cardNum        the 16-character card number (primary key)
     * @param cardAcctId     the owning account identifier
     * @param cvvCd          the card verification value
     * @param embossedName   the name embossed on the card
     * @param expirationDate the card expiration date
     * @param activeStatus   the active status flag
     * @param version        the optimistic-lock version counter
     */
    public Card(String cardNum, Long cardAcctId, Integer cvvCd, String embossedName,
                String expirationDate, String activeStatus, Long version) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cvvCd = cvvCd;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
        this.version = version;
    }

    /**
     * Returns the 16-character card number (primary key).
     *
     * @return the card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the 16-character card number (primary key).
     *
     * @param cardNum the card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the owning account identifier.
     *
     * @return the account identifier
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the owning account identifier.
     *
     * @param cardAcctId the account identifier
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the card verification value.
     *
     * @return the CVV code
     */
    public Integer getCvvCd() {
        return cvvCd;
    }

    /**
     * Sets the card verification value.
     *
     * @param cvvCd the CVV code
     */
    public void setCvvCd(Integer cvvCd) {
        this.cvvCd = cvvCd;
    }

    /**
     * Returns the name embossed on the card.
     *
     * @return the embossed name
     */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Sets the name embossed on the card.
     *
     * @param embossedName the embossed name
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /**
     * Returns the card expiration date.
     *
     * @return the expiration date
     */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param expirationDate the expiration date
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the active status flag.
     *
     * @return the active status
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the active status flag.
     *
     * @param activeStatus the active status
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the optimistic-lock version counter.
     *
     * @return the version counter
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-lock version counter.
     *
     * @param version the version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares cards for equality using the {@code cardNum} primary key only;
     * the optimistic-lock {@code version} is intentionally excluded.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code Card} with an equal
     *         {@code cardNum}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Card other)) {
            return false;
        }
        return Objects.equals(cardNum, other.cardNum);
    }

    /**
     * Returns a hash code derived from the {@code cardNum} primary key only.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }
}
