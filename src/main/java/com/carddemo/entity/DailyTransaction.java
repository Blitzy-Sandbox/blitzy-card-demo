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
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA staging entity for the COBOL {@code DALYTRAN-RECORD} layout (copybook
 * {@code CVTRA06Y} @ {@code 27d6c6f}, RECLN 350), mapped to the PostgreSQL
 * {@code daily_transaction} table.
 *
 * <p>This is the <em>unposted</em> daily-transaction staging input consumed by
 * the legacy posting engine {@code CBTRN02C} (realized in the migration as
 * {@code TransactionPostingProcessor}). Its column set mirrors the posted
 * {@code transactions} table exactly; the table is seeded empty and populated
 * from the daily transaction feed prior to each posting run.
 *
 * <p>Staging-specific design notes:
 * <ul>
 *   <li>{@code tranTypeCd} is intentionally a raw {@link String} ({@code CHAR(2)})
 *       rather than a typed enum or a converted value. Staging rows are
 *       unvalidated batch input that must load without throwing; the four-stage
 *       posting cascade validates the type code separately downstream.</li>
 *   <li>No optimistic-lock {@code version} column exists for staging rows, so
 *       this entity deliberately declares no {@code @Version} field.</li>
 *   <li>The monetary {@code tranAmt} is a {@link BigDecimal} with scale 2 to
 *       preserve COBOL {@code PIC S9(09)V99} decimal exactness; floating-point
 *       types are never used.</li>
 *   <li>The fixed-width {@code CHAR} columns ({@code tranId}, {@code tranTypeCd},
 *       {@code cardNum}, {@code origTs}, {@code procTs}) declare
 *       {@code @JdbcTypeCode(SqlTypes.CHAR)} so the provider binds and validates
 *       them as SQL {@code CHAR}, matching the Flyway {@code daily_transaction}
 *       schema under {@code ddl-auto=validate} (a {@link String} field otherwise
 *       defaults to {@code VARCHAR}, which fails schema validation against a
 *       {@code CHAR} column).</li>
 *   <li>The trailing copybook {@code FILLER PIC X(20)} carries no data and is
 *       therefore not mapped to a column.</li>
 * </ul>
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code DALYTRAN-ID PIC X(16)} — primary key (transaction identifier). */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_id", length = 16, columnDefinition = "char(16)")
    private String tranId;

    /** {@code DALYTRAN-TYPE-CD PIC X(02)} — raw, unvalidated transaction type code. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", length = 2, columnDefinition = "char(2)")
    private String tranTypeCd;

    /** {@code DALYTRAN-CAT-CD PIC 9(04)} — transaction category code. */
    @Column(name = "tran_cat_cd")
    private Integer tranCatCd;

    /** {@code DALYTRAN-SOURCE PIC X(10)} — origination source. */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** {@code DALYTRAN-DESC PIC X(100)} — transaction description. */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /** {@code DALYTRAN-AMT PIC S9(09)V99} — signed monetary amount, scale 2. */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} — merchant identifier. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)} — merchant name. */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} — merchant city. */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} — merchant ZIP code. */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /** {@code DALYTRAN-CARD-NUM PIC X(16)} — card number. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16, columnDefinition = "char(16)")
    private String cardNum;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)} — origination timestamp text, preserved verbatim. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "orig_ts", length = 26, columnDefinition = "char(26)")
    private String origTs;

    /** {@code DALYTRAN-PROC-TS PIC X(26)} — processing timestamp text, preserved verbatim. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "proc_ts", length = 26, columnDefinition = "char(26)")
    private String procTs;

    /**
     * Creates an empty staging transaction. Required by the JPA provider for
     * entity instantiation.
     */
    public DailyTransaction() {
        // No-argument constructor required by the JPA specification.
    }

    /**
     * Creates a fully populated staging transaction.
     *
     * @param tranId       the transaction identifier ({@code DALYTRAN-ID})
     * @param tranTypeCd   the raw transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param tranCatCd    the transaction category code ({@code DALYTRAN-CAT-CD})
     * @param tranSource   the origination source ({@code DALYTRAN-SOURCE})
     * @param tranDesc     the transaction description ({@code DALYTRAN-DESC})
     * @param tranAmt      the monetary amount ({@code DALYTRAN-AMT})
     * @param merchantId   the merchant identifier ({@code DALYTRAN-MERCHANT-ID})
     * @param merchantName the merchant name ({@code DALYTRAN-MERCHANT-NAME})
     * @param merchantCity the merchant city ({@code DALYTRAN-MERCHANT-CITY})
     * @param merchantZip  the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP})
     * @param cardNum      the card number ({@code DALYTRAN-CARD-NUM})
     * @param origTs       the origination timestamp text ({@code DALYTRAN-ORIG-TS})
     * @param procTs       the processing timestamp text ({@code DALYTRAN-PROC-TS})
     */
    public DailyTransaction(String tranId, String tranTypeCd, Integer tranCatCd,
            String tranSource, String tranDesc, BigDecimal tranAmt, Long merchantId,
            String merchantName, String merchantCity, String merchantZip,
            String cardNum, String origTs, String procTs) {
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTs = origTs;
        this.procTs = procTs;
    }

    /**
     * Returns the transaction identifier ({@code DALYTRAN-ID}).
     *
     * @return the transaction identifier
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * Sets the transaction identifier ({@code DALYTRAN-ID}).
     *
     * @param tranId the transaction identifier to set
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Returns the raw transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @return the raw two-character transaction type code
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the raw transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @param tranTypeCd the raw two-character transaction type code to set
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @return the transaction category code
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @param tranCatCd the transaction category code to set
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the origination source ({@code DALYTRAN-SOURCE}).
     *
     * @return the origination source
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Sets the origination source ({@code DALYTRAN-SOURCE}).
     *
     * @param tranSource the origination source to set
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the transaction description ({@code DALYTRAN-DESC}).
     *
     * @return the transaction description
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Sets the transaction description ({@code DALYTRAN-DESC}).
     *
     * @param tranDesc the transaction description to set
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the monetary amount ({@code DALYTRAN-AMT}).
     *
     * @return the monetary amount with scale 2
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Sets the monetary amount ({@code DALYTRAN-AMT}).
     *
     * @param tranAmt the monetary amount to set
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @return the merchant identifier
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @param merchantId the merchant identifier to set
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @return the merchant name
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @param merchantName the merchant name to set
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @return the merchant city
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @param merchantCity the merchant city to set
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @return the merchant ZIP code
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @param merchantZip the merchant ZIP code to set
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @return the card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @param cardNum the card number to set
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the origination timestamp text ({@code DALYTRAN-ORIG-TS}).
     *
     * @return the origination timestamp text
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the origination timestamp text ({@code DALYTRAN-ORIG-TS}).
     *
     * @param origTs the origination timestamp text to set
     */
    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp text ({@code DALYTRAN-PROC-TS}).
     *
     * @return the processing timestamp text
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the processing timestamp text ({@code DALYTRAN-PROC-TS}).
     *
     * @param procTs the processing timestamp text to set
     */
    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Compares this staging transaction with another for equality based solely
     * on the {@code tranId} primary key, consistent with JPA entity-identity
     * semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code DailyTransaction}
     *         with an equal {@code tranId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DailyTransaction that = (DailyTransaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    /**
     * Returns a hash code derived solely from the {@code tranId} primary key,
     * consistent with {@link #equals(Object)}.
     *
     * @return the hash code for this staging transaction
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    /**
     * Returns a diagnostic string representation of this staging transaction.
     *
     * @return a string containing the field values of this entity
     */
    @Override
    public String toString() {
        return "DailyTransaction{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranSource='" + tranSource + '\''
                + ", tranDesc='" + tranDesc + '\''
                + ", tranAmt=" + tranAmt
                + ", merchantId=" + merchantId
                + ", merchantName='" + merchantName + '\''
                + ", merchantCity='" + merchantCity + '\''
                + ", merchantZip='" + merchantZip + '\''
                + ", cardNum='" + cardNum + '\''
                + ", origTs='" + origTs + '\''
                + ", procTs='" + procTs + '\''
                + '}';
    }
}
