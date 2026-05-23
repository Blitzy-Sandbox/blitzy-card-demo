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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Transaction-detail (read-only view) service &mdash; the Java target for
 * the COBOL/CICS program {@code app/cbl/COTRN01C.cbl} (CICS transaction id
 * {@code CT01}).
 *
 * <p>This service returns the detail view for a single transaction
 * identified by its 16-character transaction ID. In the COBOL source,
 * {@code COTRN01C.cbl} issues an {@code EXEC CICS READ} (read-only) on
 * the {@code TRANSACT} VSAM KSDS, populates the {@code CT01DTAO} output
 * redefine of the {@code CT01DTLA} BMS symbolic map, and issues
 * {@code EXEC CICS SEND MAP} to render the record as a read-only
 * display.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COTRN01C.cbl} (CICS TRANID
 *       {@code 'CT01'}, file {@code 'TRANSACT'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COTRN01.bms} (mapset
 *       {@code COTRN01}, map {@code CT01DTLA}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COTRN01.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes) &mdash; {@link Transaction}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       (KEYS(16 0)).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COTRN01C.cbl &harr; TransactionDetailService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td>
 *       <td>{@link #viewTransaction(String)}</td></tr>
 *   <tr><td>{@code 9000-READ-DATA} / {@code EXEC CICS READ
 *       FILE('TRANSACT') INTO(TRAN-RECORD) RIDFLD(WS-TRAN-ID)}</td>
 *       <td>{@link TransactionRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code DFHRESP(NOTFND)} &rarr; emits {@code "Transaction
 *       NOT found..."}</td>
 *       <td>{@link RecordNotFoundException} &rarr; HTTP 404</td></tr>
 *   <tr><td>{@code SEND-MAP CT01DTLA}</td>
 *       <td>{@link #toDto(Transaction)} returning the JSON response
 *       envelope</td></tr>
 * </table>
 *
 * <h2>Read-only / transactional semantics</h2>
 * <p>Declared {@code @Transactional(readOnly = true)}. The COBOL source
 * uses {@code EXEC CICS READ} (not {@code READ UPDATE}); transactions
 * are append-only and never updated in place, so no optimistic-lock
 * field appears on this DTO.</p>
 *
 * <h2>PCI-DSS guard rails</h2>
 * <p>The card number on the transaction detail page is rendered as
 * <b>masked</b> {@code ****-****-****-LAST4} per AAP &sect;0.6.6.
 * Transaction detail responses are emitted to authorized REST clients
 * over TLS 1.2+; logging uses only the entity's masked
 * {@code toString()}.</p>
 *
 * @see TransactionRepository
 * @see TransactionDetailDto
 * @see Transaction
 */
@Service
public class TransactionDetailService {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionDetailService.class);

    private final TransactionRepository transactionRepository;

    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
    }

    /**
     * Returns the detail view for the transaction identified by
     * {@code tranId}.
     *
     * @param tranId the 16-character transaction identifier
     * @return the {@link TransactionDetailDto} read-only view
     * @throws RecordNotFoundException when no transaction row exists for
     *         {@code tranId} (HTTP 404 via {@code GlobalExceptionHandler})
     */
    @Transactional(readOnly = true)
    public TransactionDetailDto viewTransaction(String tranId) {
        // COBOL: 9000-READ-DATA — EXEC CICS READ FILE('TRANSACT')
        Transaction t = transactionRepository.findById(tranId)
                .orElseThrow(() -> {
                    // COBOL: DFHRESP(NOTFND) → "Transaction NOT found..."
                    LOG.debug("TransactionDetailService: tran_id not found");
                    return new RecordNotFoundException("TRANSACTION_NOT_FOUND",
                            "Transaction not found");
                });
        LOG.info("TransactionDetailService.viewTransaction returning detail for tran={}", t);
        return toDto(t);
    }

    /**
     * Maps a {@link Transaction} entity to the
     * {@link TransactionDetailDto} read-only response. The PAN is
     * masked per PCI-DSS guard rails (AAP &sect;0.6.6).
     */
    private TransactionDetailDto toDto(Transaction t) {
        return new TransactionDetailDto(
                t.getTranId(),
                t.getTranTypeCd(),
                t.getTranCatCd(),
                t.getTranSource(),
                t.getTranDesc(),
                t.getTranAmt(),
                t.getTranMerchantId(),
                t.getTranMerchantName(),
                t.getTranMerchantCity(),
                t.getTranMerchantZip(),
                maskCardNumber(t.getTranCardNum()),
                t.getTranOrigTs(),
                t.getTranProcTs());
    }

    /** PCI-DSS PAN masking helper &mdash; ****-****-****-LAST4. */
    private String maskCardNumber(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }
}
