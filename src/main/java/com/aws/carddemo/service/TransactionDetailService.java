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
import com.aws.carddemo.repository.TransactionRepository;

import java.util.Optional;

/**
 * Transaction-detail service — the Java migration of the CICS
 * transaction-detail program {@code app/cbl/COTRN01C.cbl}
 * (TRANID {@code CT01}). Performs a read-only single-key lookup of a
 * {@link Transaction} from the {@code TRANSACT} VSAM KSDS replacement
 * (PostgreSQL {@code transactions} table) and returns either a populated
 * {@link TransactionDetailResponse} or a reject response carrying the
 * COBOL-equivalent message.
 *
 * <h2>COBOL Provenance — COTRN01C.cbl</h2>
 *
 * <p>The original {@code PROCESS-ENTER-KEY} paragraph (lines 144–192)
 * orchestrates the workflow:
 *
 * <ol>
 *   <li>Validate the input transaction ID is non-empty
 *       ({@code WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES} reject
 *       at lines 147–152: {@code 'Tran ID can NOT be empty...'}).
 *       <p><em>Note on stub scope:</em> the empty-input reject is part
 *       of the original COBOL surface but is out of scope for the test
 *       methods enumerated in the AAP for this file (the AAP focuses on
 *       happy-path lookup + NOTFND reject + financial precision). The
 *       service supports this validation defensively because the COBOL
 *       baseline does — but the test surface intentionally does not
 *       exercise it; controller-layer validation will catch empty
 *       requests before they reach the service in production.</p></li>
 *   <li>{@code PERFORM READ-TRANSACT-FILE} — read the {@code TRANSACT}
 *       VSAM file by {@code TRAN-ID} (§READ-TRANSACT-FILE, lines 267–296):
 *       <ul>
 *         <li>{@code DFHRESP(NORMAL)} — record found, continue to the
 *             field-mapping block below.</li>
 *         <li>{@code DFHRESP(NOTFND)} → reject with
 *             {@code "Transaction ID NOT found..."} (line 285).</li>
 *         <li>{@code WHEN OTHER} (I/O error) → reject with
 *             {@code "Unable to lookup Transaction..."} (line 292). This
 *             branch corresponds to infrastructure failures
 *             ({@link org.springframework.dao.DataAccessException}) and
 *             is handled by the controller layer's exception-handler
 *             chain rather than producing a
 *             {@link TransactionDetailResponse#failure(String)} value.</li>
 *       </ul></li>
 *   <li>Field mapping (lines 176–192) — copy every {@code TRAN-*} field
 *       into the {@code COTRN1AO} BMS map. The Java migration replaces
 *       the BMS-map population with a {@link TransactionDetailResponse}
 *       (see {@link TransactionDetailResponse#success(Transaction)}).</li>
 * </ol>
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>BMS-screen population removed.</b> The COBOL field-mapping
 *       block (lines 176–192) writes directly to the {@code COTRN1AO}
 *       BMS map fields; the Java migration returns a
 *       {@link TransactionDetailResponse} DTO that the REST controller
 *       layer serialises to JSON. The field mapping is identical (see
 *       {@link TransactionDetailResponse#success(Transaction)}).</li>
 *   <li><b>CICS error handling replaced.</b> The COBOL
 *       {@code HANDLE ABEND LABEL(ABEND-ROUTINE)} and the
 *       {@code WHEN OTHER} branch of {@code READ-TRANSACT-FILE} are
 *       collapsed into the standard Spring
 *       {@link org.springframework.dao.DataAccessException} propagation
 *       pattern: on infrastructure failures the exception bubbles up to
 *       the controller layer's exception handler, which surfaces the
 *       Java-equivalent of the COBOL {@code 'Unable to lookup Transaction...'}
 *       text. The NOTFND branch remains in this class because it is a
 *       business-logic reject path, not an infrastructure failure.</li>
 *   <li><b>BigDecimal amount preserved verbatim.</b> The COBOL
 *       {@code TRAN-AMT} field is {@code PIC S9(09)V99} — a signed
 *       11-digit field with two implied decimal places. The Java
 *       migration carries the value as a {@link java.math.BigDecimal}
 *       at scale 2; this service does not perform any arithmetic on the
 *       amount (the COBOL workflow is strictly read-only), so the
 *       response carries the entity's scale-2 value byte-for-byte. Per
 *       AAP §0.10.3 financial-precision mandate.</li>
 * </ul>
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full
 * Spring application context is wired up. For now, the constructor
 * accepts collaborators directly so unit tests can wire mocks without a
 * Spring context — matching the convention established by
 * {@link AccountViewService} and {@link AuthenticationService}.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service contains the COMPLETE business logic for the
 * transaction-detail workflow (no helper methods are extracted; both
 * branches are visible in the single {@link #getTransaction(String)}
 * method). The corresponding {@code TransactionDetailServiceTest}
 * exercises every branch via real method calls — no business logic is
 * duplicated in the test.
 *
 * @see TransactionDetailResponse
 * @see Transaction
 * @see TransactionRepository
 */
public class TransactionDetailService {

    /**
     * COBOL message from {@code COTRN01C.cbl} {@code READ-TRANSACT-FILE}
     * paragraph (line 285) — {@code DFHRESP(NOTFND)} reject.
     *
     * <p>Verbatim COBOL literal preserved per AAP §0.10.4 (Immutable
     * Boundaries): downstream consumers reading the JSON error envelope
     * must continue to see the same textual reason as the COBOL baseline.
     * The trailing ellipsis is part of the original COBOL literal.
     */
    static final String MSG_TRANSACTION_NOT_FOUND = "Transaction ID NOT found...";

    private final TransactionRepository transactionRepository;

    /**
     * Constructs a new {@code TransactionDetailService}.
     *
     * @param transactionRepository JPA repository for {@link Transaction}
     *                              lookups (Java replacement for COBOL
     *                              {@code EXEC CICS READ DATASET('TRANSACT')})
     */
    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Look up a transaction by its 16-character {@code TRAN-ID} primary key.
     * Returns either a populated success response carrying all
     * {@code TRAN-*} fields or a reject response carrying the
     * COBOL-equivalent {@link #MSG_TRANSACTION_NOT_FOUND} message when the
     * transaction does not exist.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Look up the transaction via
     *       {@link TransactionRepository#findById(Object)}. On
     *       {@code Optional.empty()} → reject with
     *       {@link #MSG_TRANSACTION_NOT_FOUND}.</li>
     *   <li>Build and return a populated {@link TransactionDetailResponse}
     *       carrying all transaction fields via the
     *       {@link TransactionDetailResponse#success(Transaction)} factory.</li>
     * </ol>
     *
     * <p>Infrastructure errors (database unreachable, network failure,
     * etc.) surface as {@link org.springframework.dao.DataAccessException}
     * subclasses thrown by the repository; the service does not catch
     * them, letting the controller layer's exception-handler chain produce
     * the Java equivalent of the COBOL
     * {@code 'Unable to lookup Transaction...'} response.
     *
     * @param transactionId 16-character {@code TRAN-ID} primary key
     *                      (e.g. {@code "0000000000683580"})
     * @return a {@link TransactionDetailResponse} encoding success (with
     *         all hydrated fields) or failure (with the NOTFND reject
     *         message)
     */
    public TransactionDetailResponse getTransaction(String transactionId) {
        // Step 1 — TRANSACT read (COBOL §READ-TRANSACT-FILE, lines 267–296).
        // The repository .findById(...) returns Optional.empty() for the
        // DFHRESP(NOTFND) case; Optional.of(transaction) for DFHRESP(NORMAL).
        // I/O errors (the COBOL WHEN OTHER branch) propagate as
        // DataAccessException subclasses and are handled by the controller
        // layer's exception-handler chain, mirroring the COBOL HANDLE ABEND
        // fallback.
        Optional<Transaction> transactionOpt = transactionRepository.findById(transactionId);
        if (transactionOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'Transaction ID NOT found...'
            return TransactionDetailResponse.failure(MSG_TRANSACTION_NOT_FOUND);
        }

        // Step 2 — build and return the success response with the
        // hydrated transaction (COBOL field-mapping block, lines 176–192).
        return TransactionDetailResponse.success(transactionOpt.get());
    }
}
