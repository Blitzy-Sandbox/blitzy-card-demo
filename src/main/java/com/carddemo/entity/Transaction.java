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

import com.carddemo.enums.TransactionTypeCode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the COBOL {@code TRAN-RECORD} layout (copybook
 * {@code CVTRA05Y} @ {@code 27d6c6f}, RECLN 350), mapped to the PostgreSQL
 * {@code transactions} table.
 *
 * <p>This is the <em>posted</em> transactions fact table: the durable record of
 * transactions that have completed the posting pipeline. The {@code tran_id}
 * 16-character identifier is the natural primary key; the trailing 20-byte
 * copybook {@code FILLER} is reserved padding and is not persisted.
 *
 * <p>The two-character {@code TRAN-TYPE-CD} is exposed as a typed
 * {@link TransactionTypeCode} via {@link TransactionTypeConverter}; the
 * underlying {@code tran_type_cd} column remains {@code CHAR(2)}. Monetary
 * amounts use {@link BigDecimal} (scale 2) for decimal exactness, and the two
 * timestamp columns are preserved as fixed-width {@code CHAR(26)} text to retain
 * the legacy {@code YYYY-MM-DD HH:MM:SS.mmmmmm} format byte-for-byte.
 */
@Entity
@Table(name = "transactions")
public class Transaction implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code TRAN-ID PIC X(16)} — transaction identifier; natural primary key. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_id", length = 16, columnDefinition = "char(16)")
    private String tranId;

    /** {@code TRAN-TYPE-CD PIC X(02)} — transaction type code, typed via the converter. */
    @Convert(converter = TransactionTypeConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", length = 2, columnDefinition = "char(2)")
    private TransactionTypeCode transactionType;

    /** {@code TRAN-CAT-CD PIC 9(04)} — transaction category code. */
    @Column(name = "tran_cat_cd")
    private Integer tranCatCd;

    /** {@code TRAN-SOURCE PIC X(10)} — origination source. */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** {@code TRAN-DESC PIC X(100)} — transaction description. */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /** {@code TRAN-AMT PIC S9(09)V99} — signed monetary amount, scale 2. */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} — merchant identifier. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} — merchant name. */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} — merchant city. */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} — merchant ZIP code. */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /** {@code TRAN-CARD-NUM PIC X(16)} — card number. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16, columnDefinition = "char(16)")
    private String cardNum;

    /** {@code TRAN-ORIG-TS PIC X(26)} — origination timestamp text, preserved verbatim. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "orig_ts", length = 26, columnDefinition = "char(26)")
    private String origTs;

    /** {@code TRAN-PROC-TS PIC X(26)} — processing timestamp text, preserved verbatim. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "proc_ts", length = 26, columnDefinition = "char(26)")
    private String procTs;

    /**
     * Creates an empty transaction. Required by the JPA provider for entity
     * instantiation.
     */
    public Transaction() {
        // No-argument constructor required by the JPA specification.
    }

    /**
     * Creates a fully populated posted transaction.
     *
     * @param tranId          the transaction identifier ({@code TRAN-ID})
     * @param transactionType the transaction type ({@code TRAN-TYPE-CD})
     * @param tranCatCd       the transaction category code ({@code TRAN-CAT-CD})
     * @param tranSource      the origination source ({@code TRAN-SOURCE})
     * @param tranDesc        the transaction description ({@code TRAN-DESC})
     * @param tranAmt         the monetary amount ({@code TRAN-AMT})
     * @param merchantId      the merchant identifier ({@code TRAN-MERCHANT-ID})
     * @param merchantName    the merchant name ({@code TRAN-MERCHANT-NAME})
     * @param merchantCity    the merchant city ({@code TRAN-MERCHANT-CITY})
     * @param merchantZip     the merchant ZIP code ({@code TRAN-MERCHANT-ZIP})
     * @param cardNum         the card number ({@code TRAN-CARD-NUM})
     * @param origTs          the origination timestamp text ({@code TRAN-ORIG-TS})
     * @param procTs          the processing timestamp text ({@code TRAN-PROC-TS})
     */
    public Transaction(String tranId, TransactionTypeCode transactionType, Integer tranCatCd,
            String tranSource, String tranDesc, BigDecimal tranAmt, Long merchantId,
            String merchantName, String merchantCity, String merchantZip,
            String cardNum, String origTs, String procTs) {
        this.tranId = tranId;
        this.transactionType = transactionType;
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
     * Returns the transaction identifier ({@code TRAN-ID}).
     *
     * @return the transaction identifier
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * Sets the transaction identifier ({@code TRAN-ID}).
     *
     * @param tranId the transaction identifier to set
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Returns the transaction type ({@code TRAN-TYPE-CD}).
     *
     * @return the transaction type
     */
    public TransactionTypeCode getTransactionType() {
        return transactionType;
    }

    /**
     * Sets the transaction type ({@code TRAN-TYPE-CD}).
     *
     * @param transactionType the transaction type to set
     */
    public void setTransactionType(TransactionTypeCode transactionType) {
        this.transactionType = transactionType;
    }

    /**
     * Returns the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @return the transaction category code
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @param tranCatCd the transaction category code to set
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the origination source ({@code TRAN-SOURCE}).
     *
     * @return the origination source
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Sets the origination source ({@code TRAN-SOURCE}).
     *
     * @param tranSource the origination source to set
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the transaction description ({@code TRAN-DESC}).
     *
     * @return the transaction description
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Sets the transaction description ({@code TRAN-DESC}).
     *
     * @param tranDesc the transaction description to set
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the monetary amount ({@code TRAN-AMT}).
     *
     * @return the monetary amount with scale 2
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Sets the monetary amount ({@code TRAN-AMT}).
     *
     * @param tranAmt the monetary amount to set
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the merchant identifier ({@code TRAN-MERCHANT-ID}).
     *
     * @return the merchant identifier
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier ({@code TRAN-MERCHANT-ID}).
     *
     * @param merchantId the merchant identifier to set
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name ({@code TRAN-MERCHANT-NAME}).
     *
     * @return the merchant name
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name ({@code TRAN-MERCHANT-NAME}).
     *
     * @param merchantName the merchant name to set
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city ({@code TRAN-MERCHANT-CITY}).
     *
     * @return the merchant city
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city ({@code TRAN-MERCHANT-CITY}).
     *
     * @param merchantCity the merchant city to set
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP code ({@code TRAN-MERCHANT-ZIP}).
     *
     * @return the merchant ZIP code
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP code ({@code TRAN-MERCHANT-ZIP}).
     *
     * @param merchantZip the merchant ZIP code to set
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number ({@code TRAN-CARD-NUM}).
     *
     * @return the card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number ({@code TRAN-CARD-NUM}).
     *
     * @param cardNum the card number to set
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the origination timestamp text ({@code TRAN-ORIG-TS}).
     *
     * @return the origination timestamp text
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the origination timestamp text ({@code TRAN-ORIG-TS}).
     *
     * @param origTs the origination timestamp text to set
     */
    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp text ({@code TRAN-PROC-TS}).
     *
     * @return the processing timestamp text
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the processing timestamp text ({@code TRAN-PROC-TS}).
     *
     * @param procTs the processing timestamp text to set
     */
    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Compares this transaction with another for equality based solely on the
     * {@code tranId} primary key, consistent with JPA entity-identity semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code Transaction} with an
     *         equal {@code tranId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Transaction that = (Transaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    /**
     * Returns a hash code derived solely from the {@code tranId} primary key,
     * consistent with {@link #equals(Object)}.
     *
     * @return the hash code for this transaction
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }
}
