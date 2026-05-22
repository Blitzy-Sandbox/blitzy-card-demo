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

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Mutable request DTO for {@link TransactionAddService#addTransaction(TransactionAddRequest)}
 * — the Java replacement for the {@code COTRN2AI} BMS-mapped input record carrying the
 * 13 operator-entered fields from {@code app/bms/COTRN02.bms} as read by
 * {@code app/cbl/COTRN02C.cbl} (TRANID {@code CT02}, the transaction-add dispatcher).
 *
 * <h2>COBOL Provenance — COTRN02C.cbl</h2>
 *
 * <p>The COBOL {@code RECEIVE-TRNADD-SCREEN} paragraph (lines 472–480) populates the
 * {@code COTRN2AI} input map with the operator's keystrokes. {@code PROCESS-ENTER-KEY}
 * (lines 173–248) then consults the 13 input fields, each of which corresponds
 * directly to a setter on this DTO:
 *
 * <ul>
 *   <li>{@code ACTIDINI OF COTRN2AI PIC X(11)} — the 11-character account ID. Mapped
 *       to {@link #accountId}. Validated by {@code VALIDATE-INPUT-KEY-FIELDS}
 *       (lines 193–230) as numeric and cross-referenced via {@code CXACAIX} to
 *       resolve the card number when only the account ID is supplied.</li>
 *   <li>{@code CARDNINI OF COTRN2AI PIC X(16)} — the 16-character card number
 *       (Visa PAN). Mapped to {@link #cardNumber}. Validated as numeric and
 *       cross-referenced via {@code CCXREF} to resolve the account ID when only
 *       the card number is supplied.</li>
 *   <li>{@code TTYPCDI OF COTRN2AI PIC X(02)} — the 2-character transaction-type
 *       code. Mapped to {@link #transactionTypeCode}. Validated by
 *       {@code VALIDATE-INPUT-DATA-FIELDS} as non-empty and numeric.</li>
 *   <li>{@code TCATCDI OF COTRN2AI PIC X(04)} — the 4-character transaction-category
 *       code. Mapped to {@link #transactionCategoryCode}. Validated as non-empty
 *       and numeric.</li>
 *   <li>{@code TRNSRCI OF COTRN2AI PIC X(10)} — the 10-character transaction source.
 *       Mapped to {@link #source}. Validated as non-empty.</li>
 *   <li>{@code TDESCI OF COTRN2AI PIC X(100)} — the 100-character transaction
 *       description. Mapped to {@link #description}. Validated as non-empty.</li>
 *   <li>{@code TRNAMTI OF COTRN2AI PIC X(12)} — the 12-character transaction amount
 *       in edited display format ({@code -99999999.99} or {@code +99999999.99}).
 *       Mapped to {@link #amount} as a {@link BigDecimal} at scale 2 to preserve
 *       the COBOL {@code TRAN-AMT PIC S9(09)V99} precision per AAP §0.10.3.</li>
 *   <li>{@code TORIGDTI OF COTRN2AI PIC X(10)} — the 10-character origin date in
 *       {@code YYYY-MM-DD} format. Mapped to {@link #originDate}. Validated as
 *       non-empty, format-matching, and semantically valid via CSUTLDTC.</li>
 *   <li>{@code TPROCDTI OF COTRN2AI PIC X(10)} — the 10-character process date in
 *       {@code YYYY-MM-DD} format. Mapped to {@link #processDate}. Validated as
 *       non-empty, format-matching, and semantically valid via CSUTLDTC.</li>
 *   <li>{@code MIDI OF COTRN2AI PIC X(09)} — the 9-character merchant ID. Mapped
 *       to {@link #merchantId}. Validated as non-empty and numeric.</li>
 *   <li>{@code MNAMEI OF COTRN2AI PIC X(50)} — the 50-character merchant name.
 *       Mapped to {@link #merchantName}. Validated as non-empty.</li>
 *   <li>{@code MCITYI OF COTRN2AI PIC X(50)} — the 50-character merchant city.
 *       Mapped to {@link #merchantCity}. Validated as non-empty.</li>
 *   <li>{@code MZIPI OF COTRN2AI PIC X(10)} — the 10-character merchant ZIP. Mapped
 *       to {@link #merchantZip}. Validated as non-empty.</li>
 * </ul>
 *
 * <h2>Mutability — Setter-Driven Population (matches {@link UserAddRequest})</h2>
 *
 * <p>Unlike sibling request DTOs in this package that expose immutable positional
 * constructors (such as {@link BillPaymentRequest}), this class is populated via a
 * no-argument constructor plus per-field setters — the convention also used by
 * {@link UserAddRequest}. Rationale: the controller layer maps a JSON request body
 * (or {@code @ModelAttribute} form) onto this DTO via the Jackson / Spring MVC
 * standard JavaBeans contract, which requires both the default constructor and the
 * public setters. The mutability is confined to the request-binding phase; once
 * the controller passes the DTO to the service, the service treats it as a
 * read-only value object.
 *
 * <h2>No Validation in the DTO</h2>
 *
 * <p>This class deliberately performs no field validation in the constructor or
 * setters. Per the established convention used by every other request DTO in this
 * package, validation of the payload (the 11 empty checks, 3 numeric checks, the
 * amount format check, the 2 date format/semantic checks, plus the key-fields
 * either-account-or-card check) is performed by {@link TransactionAddService} so
 * the reject paths emit the COBOL-equivalent reject messages rather than
 * {@link IllegalArgumentException}. Carrying validation in the service also keeps
 * it visible to the test suite and countable for JaCoCo coverage purposes per
 * AAP §0.7.1.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link TransactionAddService} compilation and the {@code TransactionAddServiceTest}
 * unit test suite. Subsequent migration agents (REFACTOR flavor) will add Bean
 * Validation constraints ({@code @NotBlank}, {@code @Size(max = 11)},
 * {@code @Pattern("\\d{11}")}, {@code @DecimalMin}, etc.) when the full Spring MVC
 * controller layer is wired up.
 *
 * @see TransactionAddService
 * @see TransactionAddResult
 */
public class TransactionAddRequest {

    /**
     * The 11-character account ID — COBOL {@code ACTIDINI OF COTRN2AI PIC X(11)}.
     * May be {@code null} or empty when the operator supplies a card number
     * instead; the service routes through the
     * {@link com.aws.carddemo.repository.CardXrefRepository} to resolve the
     * account ID via card-number lookup in that case.
     */
    private String accountId;

    /**
     * The 16-character card number (Visa PAN) — COBOL {@code CARDNINI OF COTRN2AI
     * PIC X(16)}. May be {@code null} or empty when the operator supplies an
     * account ID instead; the service routes through
     * {@link com.aws.carddemo.repository.CardXrefRepository#findByAccountId(String)}
     * to resolve the card number via account-ID lookup in that case.
     */
    private String cardNumber;

    /**
     * The 2-character transaction-type code — COBOL {@code TTYPCDI OF COTRN2AI
     * PIC X(02)}. Foreign-key reference to {@code trantype.txt}: {@code "01"} =
     * Purchase, {@code "02"} = Payment, {@code "03"} = Credit, etc. Validated as
     * non-empty and numeric by {@link TransactionAddService}.
     */
    private String transactionTypeCode;

    /**
     * The 4-character transaction-category code — COBOL {@code TCATCDI OF
     * COTRN2AI PIC X(04)}. Foreign-key reference to {@code trancatg.txt}:
     * {@code "0001"} = Regular Sales Draft, etc. Validated as non-empty and
     * numeric by {@link TransactionAddService}.
     */
    private String transactionCategoryCode;

    /**
     * The 10-character transaction source — COBOL {@code TRNSRCI OF COTRN2AI
     * PIC X(10)}. Space-padded literal (for example, {@code "POS TERM  "}
     * identifies point-of-sale-originated transactions). Validated as
     * non-empty by {@link TransactionAddService}.
     */
    private String source;

    /**
     * The 100-character free-form transaction description — COBOL
     * {@code TDESCI OF COTRN2AI PIC X(100)}. Validated as non-empty by
     * {@link TransactionAddService}.
     */
    private String description;

    /**
     * The transaction amount — COBOL {@code TRNAMTI OF COTRN2AI PIC X(12)} in
     * edited display format mapped to {@link Transaction#getAmount()} as a
     * {@link BigDecimal} at scale 2 per the COBOL {@code TRAN-AMT PIC S9(09)V99}
     * field width. Per AAP §0.10.3 ("No float or double used for any monetary
     * value — BigDecimal exclusively") this field is strictly {@link BigDecimal};
     * negative values are accepted (the COBOL field is SIGNED so refunds are
     * valid input). Validated as non-null by {@link TransactionAddService}.
     *
     * <p>The {@link JsonFormat} annotation pins the JSON wire-format to a
     * string so the inbound HTTP request body carries the value as the
     * quoted string literal {@code "100.50"} rather than the JSON numeric
     * literal {@code 100.50}. The string form preserves the COBOL scale-2
     * contract exactly (trailing zeros and all) and keeps test-code and
     * client-code free of Java {@code double} literals — which the AAP
     * forbids at any monetary calculation boundary. Jackson's
     * {@code BigDecimalDeserializer} accepts both numeric and string JSON
     * for backward compatibility, so existing numeric request bodies
     * continue to deserialise correctly.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal amount;

    /**
     * The 10-character origin date in {@code YYYY-MM-DD} format — COBOL
     * {@code TORIGDTI OF COTRN2AI PIC X(10)}. Validated as non-empty,
     * format-matching ({@code \d{4}-\d{2}-\d{2}}), and semantically valid via
     * the {@code CSUTLDTC} replacement logic in {@link TransactionAddService}.
     */
    private String originDate;

    /**
     * The 10-character process date in {@code YYYY-MM-DD} format — COBOL
     * {@code TPROCDTI OF COTRN2AI PIC X(10)}. Validated as non-empty,
     * format-matching, and semantically valid by {@link TransactionAddService}.
     */
    private String processDate;

    /**
     * The 9-character merchant ID — COBOL {@code MIDI OF COTRN2AI PIC X(09)}.
     * Maps to the persisted {@link Transaction#getMerchantId()} field
     * ({@code TRAN-MERCHANT-ID PIC 9(09)}). Validated as non-empty and numeric
     * by {@link TransactionAddService}.
     */
    private String merchantId;

    /**
     * The 50-character merchant name — COBOL {@code MNAMEI OF COTRN2AI
     * PIC X(50)}. Validated as non-empty by {@link TransactionAddService}.
     */
    private String merchantName;

    /**
     * The 50-character merchant city — COBOL {@code MCITYI OF COTRN2AI
     * PIC X(50)}. Validated as non-empty by {@link TransactionAddService}.
     */
    private String merchantCity;

    /**
     * The 10-character merchant ZIP — COBOL {@code MZIPI OF COTRN2AI
     * PIC X(10)}. Validated as non-empty by {@link TransactionAddService}.
     */
    private String merchantZip;

    /** Default no-argument constructor — required by JSON / form binding. */
    public TransactionAddRequest() {
        // Intentionally empty — fields populated via setters by the controller's
        // JSON or @ModelAttribute binder.
    }

    /** @return the 11-character account ID; may be {@code null} or empty when a card number is supplied instead */
    public String getAccountId() {
        return accountId;
    }

    /** @param accountId the 11-character account ID */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /** @return the 16-character card number; may be {@code null} or empty when an account ID is supplied instead */
    public String getCardNumber() {
        return cardNumber;
    }

    /** @param cardNumber the 16-character card number */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /** @return the 2-character transaction-type code */
    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    /** @param transactionTypeCode the 2-character transaction-type code */
    public void setTransactionTypeCode(String transactionTypeCode) {
        this.transactionTypeCode = transactionTypeCode;
    }

    /** @return the 4-character transaction-category code */
    public String getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    /** @param transactionCategoryCode the 4-character transaction-category code */
    public void setTransactionCategoryCode(String transactionCategoryCode) {
        this.transactionCategoryCode = transactionCategoryCode;
    }

    /** @return the 10-character transaction source */
    public String getSource() {
        return source;
    }

    /** @param source the 10-character transaction source */
    public void setSource(String source) {
        this.source = source;
    }

    /** @return the 100-character transaction description */
    public String getDescription() {
        return description;
    }

    /** @param description the 100-character transaction description */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return the transaction amount (BigDecimal, scale 2; signed) */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @param amount the transaction amount (BigDecimal, scale 2; signed) */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** @return the 10-character origin date in {@code YYYY-MM-DD} format */
    public String getOriginDate() {
        return originDate;
    }

    /** @param originDate the 10-character origin date in {@code YYYY-MM-DD} format */
    public void setOriginDate(String originDate) {
        this.originDate = originDate;
    }

    /** @return the 10-character process date in {@code YYYY-MM-DD} format */
    public String getProcessDate() {
        return processDate;
    }

    /** @param processDate the 10-character process date in {@code YYYY-MM-DD} format */
    public void setProcessDate(String processDate) {
        this.processDate = processDate;
    }

    /** @return the 9-character merchant ID */
    public String getMerchantId() {
        return merchantId;
    }

    /** @param merchantId the 9-character merchant ID */
    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    /** @return the 50-character merchant name */
    public String getMerchantName() {
        return merchantName;
    }

    /** @param merchantName the 50-character merchant name */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /** @return the 50-character merchant city */
    public String getMerchantCity() {
        return merchantCity;
    }

    /** @param merchantCity the 50-character merchant city */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /** @return the 10-character merchant ZIP */
    public String getMerchantZip() {
        return merchantZip;
    }

    /** @param merchantZip the 10-character merchant ZIP */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }
}
