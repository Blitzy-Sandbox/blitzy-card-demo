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

import com.aws.carddemo.entity.Transaction;

import java.math.BigDecimal;

/**
 * Result DTO for {@link TransactionDetailService#getTransaction(String)} —
 * the Java replacement for the {@code COTRN1AO} BMS-mapped output record
 * emitted by {@code app/cbl/COTRN01C.cbl} (TRANID {@code CT01}). Encodes
 * either a <em>success</em> outcome carrying the hydrated transaction
 * fields ready for the REST controller layer to serialise, or a
 * <em>failure</em> outcome carrying the COBOL-equivalent reject message
 * (NOTFND reject path).
 *
 * <h2>COBOL Provenance — COTRN01C.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL {@code PROCESS-ENTER-KEY}
 * paragraph block (lines 176–192) that populates the {@code COTRN1AO}
 * output map fields:
 * <ul>
 *   <li>{@code TRAN-ID} → {@link #transactionId}</li>
 *   <li>{@code TRAN-CARD-NUM} → {@link #cardNumber}</li>
 *   <li>{@code TRAN-TYPE-CD} → {@link #transactionTypeCode}</li>
 *   <li>{@code TRAN-CAT-CD} → {@link #transactionCategoryCode}</li>
 *   <li>{@code TRAN-SOURCE} → {@link #source}</li>
 *   <li>{@code TRAN-AMT} (formatted via {@code WS-TRAN-AMT PIC +99999999.99})
 *       → {@link #amount} (BigDecimal scale 2)</li>
 *   <li>{@code TRAN-DESC} → {@link #description}</li>
 *   <li>{@code TRAN-ORIG-TS} → {@link #originTimestamp}</li>
 *   <li>{@code TRAN-PROC-TS} → {@link #processTimestamp}</li>
 *   <li>{@code TRAN-MERCHANT-ID} → {@link #merchantId}</li>
 *   <li>{@code TRAN-MERCHANT-NAME} → {@link #merchantName}</li>
 *   <li>{@code TRAN-MERCHANT-CITY} → {@link #merchantCity}</li>
 *   <li>{@code TRAN-MERCHANT-ZIP} → {@link #merchantZip}</li>
 * </ul>
 *
 * <p>The failure outcome corresponds to one of the two reject paths
 * encoded by {@code COTRN01C.cbl} {@code READ-TRANSACT-FILE}:
 * <ul>
 *   <li>{@code DFHRESP(NOTFND)} → {@code "Transaction ID NOT found..."}
 *       (line 285–288). This is the primary reject path covered by the
 *       {@link TransactionDetailService} unit tests.</li>
 *   <li>{@code WHEN OTHER} (I/O error) → {@code "Unable to lookup Transaction..."}
 *       (line 289–295). Surfaces in production as a propagated
 *       {@link org.springframework.dao.DataAccessException} from the
 *       repository layer; the service does not translate this branch
 *       into a {@code TransactionDetailResponse.failure(...)} value
 *       because the controller's exception-handler chain owns the
 *       infrastructure-error response shape (mirroring the COBOL
 *       {@code HANDLE ABEND} fallback).</li>
 * </ul>
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so that the invariant between {@link #success} and {@link #message}
 * (and between {@link #success} and the populated data fields) cannot be
 * violated: {@link #failure(String)} returns a failure outcome carrying
 * only the reject message (all data fields {@code null});
 * {@link #success(Transaction)} returns a success outcome with all data
 * fields populated from the hydrated entity. The constructor is
 * package-private; no production or test code instantiates this class
 * directly.
 *
 * <p>The class deliberately exposes <strong>only getters</strong> — no
 * setters and no public no-args constructor — so the response is
 * effectively read-only after factory construction. This is in line with
 * AAP §0.10.2 (Minimal Change Clause: "Do not introduce patterns,
 * abstractions, or optimizations beyond what the migration requires") and
 * AAP §0.10.4 (Immutable Boundaries: downstream consumers see the
 * hydrated fields exactly as the COBOL baseline produced them).
 *
 * <p>Jackson — the JSON serialiser used by the Spring MVC REST controller
 * layer — serialises out-bound responses via the public getter methods
 * only, so removing setters has no impact on the wire-format contract.
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>The {@link #amount} field is a {@link BigDecimal} (never
 * {@code double}/{@code float}) at scale 2 matching the COBOL
 * {@code TRAN-AMT PIC S9(09)V99} field. The
 * {@link #success(Transaction)} factory copies the entity's amount
 * by reference (no arithmetic, no rescale), preserving whatever scale
 * the entity carries; the entity itself is populated by the repository
 * which must hydrate the field at scale 2 per the COBOL contract. Tests
 * assert both the value and the scale on the returned response.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link TransactionDetailService}
 * in {@code com.aws.carddemo.service} (rather than under a dedicated DTO
 * subpackage), matching the convention established by
 * {@link AccountViewResponse}, {@link MainMenuResponse}, and
 * {@link AdminMenuResponse} for service/request/response triples that are
 * tightly coupled to a single service contract.
 *
 * @see TransactionDetailService
 * @see Transaction
 */
public class TransactionDetailResponse {

    /**
     * {@code true} when the lookup succeeded and the transaction was
     * found; {@code false} for the NOTFND reject branch.
     *
     * <p>Tests assert via {@link #isSuccess()}: {@code .isTrue()} for the
     * happy-path test, {@code .isFalse()} for the reject-path test.
     */
    private boolean success;

    /**
     * Reject message populated when {@link #success} is {@code false};
     * {@code null} when {@link #success} is {@code true}. Carries the
     * Java-migration equivalent of the COBOL {@code WS-MESSAGE} reject
     * string verbatim (per AAP §0.10.4 immutable-boundaries clause —
     * downstream consumers reading the JSON error envelope must continue
     * to see the same textual reasons as the COBOL baseline).
     */
    private String message;

    /** 16-character {@code TRAN-ID}; {@code null} on reject. */
    private String transactionId;
    /** 16-character {@code TRAN-CARD-NUM}; {@code null} on reject. */
    private String cardNumber;
    /** 2-character {@code TRAN-TYPE-CD}; {@code null} on reject. */
    private String transactionTypeCode;
    /** 4-character {@code TRAN-CAT-CD}; {@code null} on reject. */
    private String transactionCategoryCode;
    /** 10-character {@code TRAN-SOURCE}; {@code null} on reject. */
    private String source;

    /** {@code TRAN-AMT} (BigDecimal scale 2); {@code null} on reject. */
    private BigDecimal amount;

    /** 100-character {@code TRAN-DESC}; {@code null} on reject. */
    private String description;

    /** 9-digit {@code TRAN-MERCHANT-ID}; {@code null} on reject. */
    private String merchantId;
    /** 50-character {@code TRAN-MERCHANT-NAME}; {@code null} on reject. */
    private String merchantName;
    /** 50-character {@code TRAN-MERCHANT-CITY}; {@code null} on reject. */
    private String merchantCity;
    /** 10-character {@code TRAN-MERCHANT-ZIP}; {@code null} on reject. */
    private String merchantZip;

    /** 26-character {@code TRAN-ORIG-TS}; {@code null} on reject. */
    private String originTimestamp;
    /** 26-character {@code TRAN-PROC-TS}; {@code null} on reject. */
    private String processTimestamp;

    /**
     * Package-private no-args constructor. The only callers are the two
     * static factory methods on this class ({@link #success(Transaction)}
     * and {@link #failure(String)}). Production code paths inside
     * {@link TransactionDetailService} always invoke a factory method so
     * the invariant between {@link #success}, {@link #message}, and the
     * data fields cannot be violated by an external caller.
     */
    TransactionDetailResponse() {
        // intentionally empty — fields are populated by the factory
        // methods through direct field assignment.
    }

    /**
     * Factory method for failure outcomes. Returns a response with
     * {@link #success} {@code false}, the supplied reject message set,
     * and every data field left {@code null}.
     *
     * @param message the COBOL-equivalent reject message
     *                (e.g. {@code "Transaction ID NOT found..."})
     * @return a populated failure response
     */
    public static TransactionDetailResponse failure(String message) {
        TransactionDetailResponse response = new TransactionDetailResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    /**
     * Factory method for success outcomes. Returns a response with
     * {@link #success} {@code true}, {@link #message} {@code null}, and
     * every data field hydrated from the supplied transaction entity.
     *
     * <p>Maps every field that {@code COTRN01C.cbl}
     * {@code PROCESS-ENTER-KEY} populates on the {@code COTRN1AO} BMS map
     * (preserving AAP §0.10.4 immutable-boundaries clause — downstream
     * consumers see the same fields as the COBOL baseline).
     *
     * <p>Per AAP §0.10.3 (Financial Precision): the {@link Transaction#getAmount()}
     * field is copied by reference — no arithmetic, no rescale. The
     * BigDecimal scale carried on the entity is preserved verbatim on the
     * response. Callers that need to assert on the scale should call
     * {@link #getAmount()}{@code .scale()}.
     *
     * @param transaction the hydrated {@link Transaction} entity from
     *                    {@code TRANSACT}
     * @return a populated success response
     */
    public static TransactionDetailResponse success(Transaction transaction) {
        TransactionDetailResponse response = new TransactionDetailResponse();
        response.success = true;
        // Transaction header fields (key + reference codes)
        response.transactionId = transaction.getTransactionId();
        response.cardNumber = transaction.getCardNumber();
        response.transactionTypeCode = transaction.getTransactionTypeCode();
        response.transactionCategoryCode = transaction.getTransactionCategoryCode();
        response.source = transaction.getSource();
        // Monetary + free-form description fields
        response.amount = transaction.getAmount();
        response.description = transaction.getDescription();
        // Merchant fields
        response.merchantId = transaction.getMerchantId();
        response.merchantName = transaction.getMerchantName();
        response.merchantCity = transaction.getMerchantCity();
        response.merchantZip = transaction.getMerchantZip();
        // Timestamps
        response.originTimestamp = transaction.getOriginTimestamp();
        response.processTimestamp = transaction.getProcessTimestamp();
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

    /** @return the 16-character {@code TRAN-ID} ({@code null} on reject) */
    public String getTransactionId() {
        return transactionId;
    }

    /** @return the 16-character {@code TRAN-CARD-NUM} ({@code null} on reject) */
    public String getCardNumber() {
        return cardNumber;
    }

    /** @return the 2-character {@code TRAN-TYPE-CD} ({@code null} on reject) */
    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    /** @return the 4-character {@code TRAN-CAT-CD} ({@code null} on reject) */
    public String getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    /** @return the 10-character {@code TRAN-SOURCE} ({@code null} on reject) */
    public String getSource() {
        return source;
    }

    /** @return the {@code TRAN-AMT} (BigDecimal scale 2; {@code null} on reject) */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @return the 100-character {@code TRAN-DESC} ({@code null} on reject) */
    public String getDescription() {
        return description;
    }

    /** @return the 9-digit {@code TRAN-MERCHANT-ID} ({@code null} on reject) */
    public String getMerchantId() {
        return merchantId;
    }

    /** @return the 50-character {@code TRAN-MERCHANT-NAME} ({@code null} on reject) */
    public String getMerchantName() {
        return merchantName;
    }

    /** @return the 50-character {@code TRAN-MERCHANT-CITY} ({@code null} on reject) */
    public String getMerchantCity() {
        return merchantCity;
    }

    /** @return the 10-character {@code TRAN-MERCHANT-ZIP} ({@code null} on reject) */
    public String getMerchantZip() {
        return merchantZip;
    }

    /** @return the 26-character {@code TRAN-ORIG-TS} ({@code null} on reject) */
    public String getOriginTimestamp() {
        return originTimestamp;
    }

    /** @return the 26-character {@code TRAN-PROC-TS} ({@code null} on reject) */
    public String getProcessTimestamp() {
        return processTimestamp;
    }
}
