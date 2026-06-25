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
package com.carddemo.service;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-detail read service backing {@code GET /api/transactions/{id}}.
 *
 * <p>Translates (never copies) the CardDemo online program
 * {@code app/cbl/COTRN01C.cbl} (CICS transaction {@code CT01}, "view a
 * transaction") at source commit SHA {@code 27d6c6f}. The enter-key driver
 * paragraph {@code PROCESS-ENTER-KEY} and the keyed read paragraph
 * {@code READ-TRANSACT-FILE} (line 267) are realised by
 * {@link #getTransaction(String)}, which reproduces the program's ordered
 * behaviour:</p>
 * <ol>
 *   <li>{@code PROCESS-ENTER-KEY} &mdash; reject a blank transaction identifier
 *       ({@code TRNIDINI = SPACES OR LOW-VALUES}) with the user-visible message
 *       {@code "Tran ID can NOT be empty..."}.</li>
 *   <li>{@code READ-TRANSACT-FILE} &mdash; the keyed
 *       {@code EXEC CICS READ DATASET(TRANSACT) RIDFLD(TRAN-ID)} becomes a
 *       primary-key {@link TransactionRepository#findById(Object)}; the
 *       {@code DFHRESP(NOTFND)} branch surfaces {@code "Transaction ID NOT
 *       found..."}. The {@code WHEN OTHER} access-error branch ({@code "Unable
 *       to lookup Transaction..."}) is left to the persistence layer: any
 *       {@code DataAccessException} propagates to the centralized handler.</li>
 * </ol>
 *
 * <p>The screen-population block that copies each {@code TRAN-RECORD} field
 * (copybook {@code CVTRA05Y}) onto the {@code COTRN1A} map is realised by
 * {@link #toDetail(Transaction)}, mapping each field one-to-one onto
 * {@link TransactionDto.Detail}. The monetary amount remains a
 * {@link java.math.BigDecimal} at its stored scale of two, and the
 * 26-character origination and processing timestamps are surfaced verbatim.</p>
 *
 * <p>The lookup is read-only and runs inside a single read-only transaction;
 * the repository collaborator is supplied by constructor injection.</p>
 */
@Service
public class TransactionDetailService {

    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param transactionRepository the repository used to read transaction
     *                              records by primary key
     */
    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns the detail view of a single posted transaction identified by its
     * sixteen-character transaction identifier.
     *
     * @param transactionId the transaction identifier ({@code TRAN-ID})
     * @return the transaction detail mapped onto a {@link TransactionDto.Detail}
     * @throws ValidationException     when {@code transactionId} is {@code null}
     *                                 or blank, carrying the message
     *                                 {@code "Tran ID can NOT be empty..."} keyed
     *                                 to the {@code transactionId} field
     * @throws RecordNotFoundException when no transaction matches the supplied
     *                                 identifier, carrying the message
     *                                 {@code "Transaction ID NOT found..."}
     */
    @Transactional(readOnly = true)
    public TransactionDto.Detail getTransaction(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new ValidationException(
                    "Tran ID can NOT be empty...",
                    Map.of("transactionId", "Tran ID can NOT be empty..."));
        }

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RecordNotFoundException("Transaction ID NOT found..."));

        return toDetail(transaction);
    }

    /**
     * Maps a persisted {@link Transaction} onto its {@link TransactionDto.Detail}
     * projection, preserving the field order and one-to-one field assignment of
     * the COBOL screen-population block in {@code PROCESS-ENTER-KEY}.
     *
     * @param transaction the transaction record to map
     * @return the detail projection
     */
    private static TransactionDto.Detail toDetail(Transaction transaction) {
        return new TransactionDto.Detail(
                transaction.getTranId(),
                transaction.getCardNum(),
                mapTransactionType(transaction),
                formatCategoryCode(transaction.getTranCatCd()),
                transaction.getTranSource(),
                transaction.getTranDesc(),
                transaction.getTranAmt(),
                transaction.getOrigTs(),
                transaction.getProcTs(),
                formatMerchantId(transaction.getMerchantId()),
                transaction.getMerchantName(),
                transaction.getMerchantCity(),
                transaction.getMerchantZip());
    }

    /**
     * Renders the typed transaction type as its two-character
     * {@code TRAN-TYPE-CD} code (for example, {@code "01"}).
     *
     * @param transaction the transaction whose type is rendered
     * @return the two-character type code, or {@code null} when the transaction
     *         carries no type
     */
    private static String mapTransactionType(Transaction transaction) {
        return transaction.getTransactionType() == null
                ? null
                : transaction.getTransactionType().getCode();
    }

    /**
     * Renders the transaction category code as a four-digit, zero-padded string,
     * mirroring the legacy {@code TRAN-CAT-CD PIC 9(04)} field width.
     *
     * @param tranCatCd the category code; may be {@code null}
     * @return the zero-padded category code, or {@code null} when {@code null}
     */
    private static String formatCategoryCode(Integer tranCatCd) {
        return tranCatCd == null ? null : String.format("%04d", tranCatCd);
    }

    /**
     * Renders the merchant identifier as a nine-digit, zero-padded string,
     * mirroring the legacy {@code TRAN-MERCHANT-ID PIC 9(09)} field width.
     *
     * @param merchantId the merchant identifier; may be {@code null}
     * @return the zero-padded merchant identifier, or {@code null} when
     *         {@code null}
     */
    private static String formatMerchantId(Long merchantId) {
        return merchantId == null ? null : String.format("%09d", merchantId);
    }
}
