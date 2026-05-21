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

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code TRANSACT} VSAM KSDS file's
 * {@code TRAN-RECORD} record described by {@code app/cpy/CVTRA05Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA05Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 350-byte record:
 * <pre>
 *   01 TRAN-RECORD.
 *      05 TRAN-ID                    PIC X(16).      --&gt; {@link #transactionId}         (primary key)
 *      05 TRAN-TYPE-CD               PIC X(02).      --&gt; {@link #transactionTypeCode}
 *      05 TRAN-CAT-CD                PIC 9(04).      --&gt; {@link #transactionCategoryCode}
 *      05 TRAN-SOURCE                PIC X(10).      --&gt; {@link #source}
 *      05 TRAN-DESC                  PIC X(100).     --&gt; {@link #description}
 *      05 TRAN-AMT                   PIC S9(09)V99.  --&gt; {@link #amount}                 (BigDecimal scale 2)
 *      05 TRAN-MERCHANT-ID           PIC 9(09).      --&gt; {@link #merchantId}
 *      05 TRAN-MERCHANT-NAME         PIC X(50).      --&gt; {@link #merchantName}
 *      05 TRAN-MERCHANT-CITY         PIC X(50).      --&gt; {@link #merchantCity}
 *      05 TRAN-MERCHANT-ZIP          PIC X(10).      --&gt; {@link #merchantZip}
 *      05 TRAN-CARD-NUM              PIC X(16).      --&gt; {@link #cardNumber}
 *      05 TRAN-ORIG-TS               PIC X(26).      --&gt; {@link #originTimestamp}
 *      05 TRAN-PROC-TS               PIC X(26).      --&gt; {@link #processTimestamp}
 *      05 FILLER                     PIC X(20).
 * </pre>
 *
 * <h2>COBOL Read Path Surfaced By This Entity — COTRN01C.cbl</h2>
 *
 * <p>The {@code COTRN01C.cbl} program performs an
 * {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)} against this
 * record and renders the populated fields to the {@code COTRN1AO} BMS map
 * (lines 178–190 of {@code app/cbl/COTRN01C.cbl}). The Java migration
 * exposes this entity through
 * {@link com.aws.carddemo.repository.TransactionRepository#findById(Object)}
 * and the result is mapped into a
 * {@link com.aws.carddemo.service.TransactionDetailResponse} by
 * {@link com.aws.carddemo.service.TransactionDetailService}.
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>All monetary fields are {@link BigDecimal} per AAP §0.10.3
 *       ("No float or double used for any monetary value — BigDecimal
 *       exclusively"). The {@link #amount} scale matches the COBOL
 *       {@code PIC S9(09)V99} field (two digits after the implied decimal
 *       point); callers and tests must preserve this scale.</li>
 *   <li>No JPA annotations are present yet; subsequent REFACTOR-flavor
 *       agents add {@code @Entity}, {@code @Id}, {@code @Column}, and
 *       (where the business workflow requires optimistic locking)
 *       {@code @Version} when the entity is wired into the Hibernate
 *       {@code SessionFactory}. Read-only lookups via
 *       {@link com.aws.carddemo.service.TransactionDetailService} do not
 *       need optimistic locking — the {@code COTRN01C.cbl} workflow is
 *       strictly read-only.</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.service.TransactionDetailService} compilation and
 * the transaction-detail test suite. Subsequent migration agents
 * (REFACTOR flavor) will add JPA annotations and Bean Validation
 * constraints once the supporting infrastructure (Flyway schema, Hibernate
 * SessionFactory) lands.
 *
 * <h2>Security — toString() Excludes Amount</h2>
 *
 * <p>{@link #toString()} explicitly omits {@link #amount},
 * {@link #cardNumber}, {@link #merchantName}, {@link #merchantCity},
 * and {@link #merchantZip} so the entity cannot leak monetary values or
 * cardholder PII into log output. Per AAP §0.10.5 ("No financial data
 * written to logs at any level"; no PII either).
 *
 * @see com.aws.carddemo.service.TransactionDetailService
 * @see com.aws.carddemo.repository.TransactionRepository
 */
public class Transaction {

    /**
     * 16-character {@code TRAN-ID} primary key per {@code CVTRA05Y.cpy}
     * ({@code PIC X(16)}). Stored as a fixed-width string to preserve the
     * COBOL key format byte-for-byte across VSAM-to-PostgreSQL migration.
     * Examples: daily-transaction IDs in {@code dailytran.txt} are
     * zero-padded numeric strings (e.g. {@code "0000000000683580"});
     * interest transactions generated by {@code CBACT04C} concatenate the
     * 10-character INTCALC PARM with a 6-digit sequential suffix
     * (e.g. {@code "2022071800000001"}).
     */
    private String transactionId;

    /**
     * 2-character {@code TRAN-TYPE-CD} per {@code CVTRA05Y.cpy}
     * ({@code PIC X(02)}). Foreign-key reference to the
     * {@code trantype.txt} reference data: {@code "01"} = Purchase,
     * {@code "02"} = Payment, {@code "03"} = Credit, etc.
     */
    private String transactionTypeCode;

    /**
     * 4-character {@code TRAN-CAT-CD} per {@code CVTRA05Y.cpy}
     * ({@code PIC 9(04)}). Foreign-key reference to the
     * {@code trancatg.txt} reference data: {@code "0001"} = Regular Sales
     * Draft, {@code "0005"} = Interest Amount, etc. Stored as a string to
     * preserve the COBOL zero-padded numeric key format.
     */
    private String transactionCategoryCode;

    /**
     * 10-character {@code TRAN-SOURCE} per {@code CVTRA05Y.cpy}
     * ({@code PIC X(10)}). Identifies the origin of the transaction:
     * {@code "System    "} (10-character space-padded) for interest
     * transactions generated by {@code CBACT04C}; {@code "POS TERM  "} for
     * POS-originated daily transactions.
     */
    private String source;

    /**
     * 100-character free-form description per {@code CVTRA05Y.cpy}
     * ({@code TRAN-DESC PIC X(100)}). Renders directly to the
     * {@code TDESCI} field of the {@code COTRN1AO} BMS map in
     * {@code COTRN01C.cbl} line 184.
     */
    private String description;

    /**
     * {@code TRAN-AMT} per {@code CVTRA05Y.cpy} ({@code PIC S9(09)V99}).
     * Always a {@link BigDecimal} with scale 2 per AAP §0.10.3
     * financial-precision mandate. The signed COBOL field carries up to
     * 9 integer digits + 2 fractional digits (range
     * {@code -999999999.99} to {@code +999999999.99}).
     */
    private BigDecimal amount;

    /**
     * 9-digit {@code TRAN-MERCHANT-ID} per {@code CVTRA05Y.cpy}
     * ({@code PIC 9(09)}). Stored as a string to preserve the COBOL
     * zero-padded numeric format.
     */
    private String merchantId;

    /** 50-character {@code TRAN-MERCHANT-NAME} per {@code CVTRA05Y.cpy} ({@code PIC X(50)}). */
    private String merchantName;

    /** 50-character {@code TRAN-MERCHANT-CITY} per {@code CVTRA05Y.cpy} ({@code PIC X(50)}). */
    private String merchantCity;

    /** 10-character {@code TRAN-MERCHANT-ZIP} per {@code CVTRA05Y.cpy} ({@code PIC X(10)}). */
    private String merchantZip;

    /**
     * 16-character {@code TRAN-CARD-NUM} per {@code CVTRA05Y.cpy}
     * ({@code PIC X(16)}). Foreign-key reference to {@code carddata.txt}
     * by the cardholder PAN; renders to the {@code CARDNUMI} field of the
     * {@code COTRN1AO} BMS map in {@code COTRN01C.cbl} line 179.
     */
    private String cardNumber;

    /**
     * 26-character {@code TRAN-ORIG-TS} per {@code CVTRA05Y.cpy}
     * ({@code PIC X(26)}). ISO-like timestamp recorded when the transaction
     * was originated at the source system. Stored as a string to preserve
     * the exact COBOL byte representation.
     */
    private String originTimestamp;

    /**
     * 26-character {@code TRAN-PROC-TS} per {@code CVTRA05Y.cpy}
     * ({@code PIC X(26)}). ISO-like timestamp recorded when the transaction
     * was processed by the posting workflow ({@code CBTRN02C}).
     */
    private String processTimestamp;

    /** Default no-arg constructor (required by JPA reflection-based instantiation). */
    public Transaction() {
        // intentionally empty
    }

    /** @return the 16-character {@code TRAN-ID} primary key */
    public String getTransactionId() {
        return transactionId;
    }

    /** @param transactionId the 16-character {@code TRAN-ID} primary key */
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    /** @return the 2-character {@code TRAN-TYPE-CD} */
    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    /** @param transactionTypeCode the 2-character {@code TRAN-TYPE-CD} */
    public void setTransactionTypeCode(String transactionTypeCode) {
        this.transactionTypeCode = transactionTypeCode;
    }

    /** @return the 4-character {@code TRAN-CAT-CD} */
    public String getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    /** @param transactionCategoryCode the 4-character {@code TRAN-CAT-CD} */
    public void setTransactionCategoryCode(String transactionCategoryCode) {
        this.transactionCategoryCode = transactionCategoryCode;
    }

    /** @return the 10-character {@code TRAN-SOURCE} */
    public String getSource() {
        return source;
    }

    /** @param source the 10-character {@code TRAN-SOURCE} */
    public void setSource(String source) {
        this.source = source;
    }

    /** @return the 100-character {@code TRAN-DESC} */
    public String getDescription() {
        return description;
    }

    /** @param description the 100-character {@code TRAN-DESC} */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return the {@code TRAN-AMT} (BigDecimal, scale 2) */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @param amount the {@code TRAN-AMT} (BigDecimal, scale 2) */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** @return the 9-digit {@code TRAN-MERCHANT-ID} */
    public String getMerchantId() {
        return merchantId;
    }

    /** @param merchantId the 9-digit {@code TRAN-MERCHANT-ID} */
    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    /** @return the 50-character {@code TRAN-MERCHANT-NAME} */
    public String getMerchantName() {
        return merchantName;
    }

    /** @param merchantName the 50-character {@code TRAN-MERCHANT-NAME} */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /** @return the 50-character {@code TRAN-MERCHANT-CITY} */
    public String getMerchantCity() {
        return merchantCity;
    }

    /** @param merchantCity the 50-character {@code TRAN-MERCHANT-CITY} */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /** @return the 10-character {@code TRAN-MERCHANT-ZIP} */
    public String getMerchantZip() {
        return merchantZip;
    }

    /** @param merchantZip the 10-character {@code TRAN-MERCHANT-ZIP} */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /** @return the 16-character {@code TRAN-CARD-NUM} */
    public String getCardNumber() {
        return cardNumber;
    }

    /** @param cardNumber the 16-character {@code TRAN-CARD-NUM} */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /** @return the 26-character {@code TRAN-ORIG-TS} */
    public String getOriginTimestamp() {
        return originTimestamp;
    }

    /** @param originTimestamp the 26-character {@code TRAN-ORIG-TS} */
    public void setOriginTimestamp(String originTimestamp) {
        this.originTimestamp = originTimestamp;
    }

    /** @return the 26-character {@code TRAN-PROC-TS} */
    public String getProcessTimestamp() {
        return processTimestamp;
    }

    /** @param processTimestamp the 26-character {@code TRAN-PROC-TS} */
    public void setProcessTimestamp(String processTimestamp) {
        this.processTimestamp = processTimestamp;
    }

    /**
     * Equality is based on the primary key {@link #transactionId} alone. JPA-managed
     * entities are considered equal iff they share the same primary key value; the
     * amount, merchant details, and other mutable state are deliberately excluded
     * from equality so transient and managed copies of the same logical transaction
     * compare equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction)) {
            return false;
        }
        Transaction other = (Transaction) o;
        return Objects.equals(transactionId, other.transactionId);
    }

    /** Hash by primary key, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }

    /**
     * Diagnostic string deliberately omitting the {@link #amount},
     * {@link #cardNumber}, {@link #merchantName}, {@link #merchantCity},
     * and {@link #merchantZip} fields per AAP §0.10.5 ("No financial data
     * written to logs at any level"; no PII either). Exposes only the
     * primary key, type/category codes, source, and merchant-ID — safe
     * identifiers for operational log lines.
     */
    @Override
    public String toString() {
        return "Transaction{"
                + "transactionId='" + transactionId + '\''
                + ", transactionTypeCode='" + transactionTypeCode + '\''
                + ", transactionCategoryCode='" + transactionCategoryCode + '\''
                + ", source='" + source + '\''
                + ", merchantId='" + merchantId + '\''
                + '}';
    }
}
