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
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Transaction-detail (read-only view) service &mdash; the Java target for
 * the COBOL/CICS program {@code app/cbl/COTRN01C.cbl} (CICS transaction id
 * {@code 'CT01'}, program id {@code COTRN01C}, function "View a Transaction
 * from TRANSACT file").
 *
 * <p>This service returns the detail view for a single transaction
 * identified by its 16-character transaction ID. In the COBOL source,
 * {@code COTRN01C.cbl} performs the following pseudo-conversational
 * sequence:</p>
 * <ol>
 *   <li>{@code PROCESS-ENTER-KEY} (COTRN01C.cbl L144&ndash;L174) &mdash;
 *       validates the operator-supplied {@code TRNIDINI} field. If empty
 *       or space-filled, sets {@code WS-ERR-FLG = 'Y'} and emits
 *       {@code 'Tran ID can NOT be empty...'} to {@code WS-MESSAGE}.</li>
 *   <li>{@code READ-TRANSACT-FILE} (COTRN01C.cbl L267&ndash;L296) &mdash;
 *       issues {@code EXEC CICS READ DATASET('TRANSACT') INTO(TRAN-RECORD)
 *       RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)} against the
 *       {@code TRANSACT} VSAM KSDS. On {@code DFHRESP(NOTFND)}, sets
 *       {@code WS-ERR-FLG = 'Y'} and emits
 *       {@code 'Transaction ID NOT found...'} to {@code WS-MESSAGE}.</li>
 *   <li>Populates the {@code COTRN1AO} output symbolic-map fields from
 *       the {@code TRAN-RECORD} layout and issues {@code EXEC CICS SEND
 *       MAP('COTRN1A')} to render the read-only display.</li>
 * </ol>
 *
 * <p>In the Java target, the operator-keyed BMS terminal interaction is
 * replaced by the REST endpoint {@code GET /api/transactions/{id}} on
 * {@code com.awsm2.carddemo.controller.TransactionController}. The
 * controller delegates to {@link #getTransactionDetail(String)} which
 * performs the same logical steps:</p>
 * <ol>
 *   <li>validate the supplied {@code tranId} (mirrors COBOL
 *       {@code PROCESS-ENTER-KEY}) &mdash; throws
 *       {@link ValidationException} for null/blank input, which the
 *       {@code GlobalExceptionHandler} translates to HTTP 400 Bad
 *       Request;</li>
 *   <li>invoke {@link TransactionRepository#findById(Object)} (mirrors
 *       COBOL {@code READ-TRANSACT-FILE}) &mdash; throws
 *       {@link RecordNotFoundException} on empty {@link java.util.Optional},
 *       which the {@code GlobalExceptionHandler} translates to HTTP 404
 *       Not Found per AAP &sect;0.4.1 ("Maps VSAM NOTFND to 404 Not
 *       Found");</li>
 *   <li>project the {@link Transaction} JPA entity onto the
 *       {@link TransactionDetailDto} response record (mirrors COBOL
 *       {@code MOVE TRAN-* TO ...I OF COTRN1AI} block at L177&ndash;L191).
 *       The full 16-digit Primary Account Number (PAN) is passed through
 *       to the DTO &mdash; PAN masking for logging is performed by
 *       {@link TransactionDetailDto#toString()}, NOT by this service.</li>
 * </ol>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COTRN01C.cbl} (CICS TRANID
 *       {@code 'CT01'}, file {@code 'TRANSACT'}; AAP &sect;0.4.1 online
 *       programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COTRN01.bms} (mapset
 *       {@code COTRN01}, map {@code COTRN1A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COTRN01.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes) &mdash; mapped to JPA entity
 *       {@link Transaction}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       (KEYS(16 0), RECORDSIZE(350 350)) &mdash; replaced by the
 *       {@code transactions} PostgreSQL table per V005 Flyway
 *       migration.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COTRN01C.cbl &harr; TransactionDetailService</caption>
 *   <tr><th>COBOL paragraph (line range)</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code MAIN-PARA} (L86&ndash;L139) entry-point dispatch</td>
 *       <td>{@link #getTransactionDetail(String)} entry point</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (L144&ndash;L174) &mdash;
 *       empty-input validation: emits
 *       {@code 'Tran ID can NOT be empty...'}</td>
 *       <td>{@link ValidationException} &rarr; HTTP 400 Bad Request via
 *       {@code GlobalExceptionHandler}</td></tr>
 *   <tr><td>{@code READ-TRANSACT-FILE} (L267&ndash;L296) &mdash;
 *       {@code EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID)}</td>
 *       <td>{@link TransactionRepository#findById(Object)} returning
 *       {@link java.util.Optional Optional&lt;Transaction&gt;}</td></tr>
 *   <tr><td>{@code WHEN DFHRESP(NOTFND)} branch (L283&ndash;L288) &mdash;
 *       emits {@code 'Transaction ID NOT found...'}</td>
 *       <td>{@link RecordNotFoundException} &rarr; HTTP 404 Not Found via
 *       {@code GlobalExceptionHandler} (FILE STATUS '23' preserved
 *       verbatim per AAP &sect;0.7.2)</td></tr>
 *   <tr><td>{@code MOVE TRAN-* TO ...I OF COTRN1AI} block
 *       (L177&ndash;L191) &mdash; populates symbolic-map output fields
 *       from the {@code TRAN-RECORD} layout</td>
 *       <td>{@link #toDto(Transaction)} returning a
 *       {@link TransactionDetailDto} record</td></tr>
 *   <tr><td>{@code SEND-TRNVIEW-SCREEN} (L213&ndash;L225) &mdash;
 *       {@code EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01')}</td>
 *       <td>JSON serialization of the returned DTO by Spring MVC; the
 *       REST controller wraps the DTO in an {@code ApiResponse} envelope
 *       and returns HTTP 200 OK</td></tr>
 * </table>
 *
 * <h2>Read-only / transactional semantics (AAP &sect;0.7.1)</h2>
 * <p>Declared {@link Transactional @Transactional(readOnly = true)}. The
 * COBOL source issues {@code EXEC CICS READ} with the {@code UPDATE}
 * option (see {@code READ-TRANSACT-FILE} at L275), but the program never
 * issues a corresponding {@code REWRITE} or {@code DELETE} on the
 * {@code TRANSACT} file &mdash; {@code COTRN01C} is a strictly read-only
 * inquiry. The {@code UPDATE} keyword on the {@code READ} is residual
 * from the source's pseudo-conversational template and has no functional
 * effect because the cursor is released by the implicit
 * {@code SYNCPOINT} on {@code EXEC CICS RETURN}. Accordingly the Java
 * target uses a read-only transaction boundary, enabling Hibernate
 * flush-mode optimizations and a consistent-snapshot read against RDS
 * PostgreSQL Multi-AZ (AAP &sect;0.6.2). Per AAP &sect;0.7.1 ("Use
 * &#64;Transactional with proper isolation levels for transactional
 * integrity") the default isolation is inherited from the connection
 * pool (PostgreSQL {@code READ COMMITTED}).</p>
 *
 * <h2>Caching policy (AAP &sect;0.3.3 / &sect;0.7.1)</h2>
 * <p>Unlike {@code AccountViewService} and {@code CardDetailService},
 * this service does <b>not</b> employ an ElastiCache Redis cache-aside
 * lookup. The {@code transactions} table is append-only (per the
 * {@link Transaction} entity invariant) and individual transaction
 * detail lookups are uncommon &mdash; the cache hit rate would not
 * justify the consistency complexity or the cache memory footprint. The
 * cache-miss penalty (a single primary-key lookup against the
 * {@code transactions} table on RDS PostgreSQL Multi-AZ) is bounded and
 * acceptable. This is the same rationale documented in the agent
 * prompt for this file.</p>
 *
 * <h2>PCI-DSS guard rails (AAP &sect;0.6.6)</h2>
 * <p>The transaction detail response carries the full 16-digit Primary
 * Account Number (PAN) in {@link TransactionDetailDto#cardNumber()}
 * &mdash; matching the legacy 3270 View Transaction screen behavior of
 * displaying the full PAN to the authenticated operator (per AAP
 * &sect;0.7.3 Minimal Change Clause). REST endpoint authorization is
 * enforced by Spring Security on {@code TransactionController} (only
 * authenticated principals with role {@code USER} or {@code ADMIN}
 * may invoke {@code GET /api/transactions/{id}}); TLS 1.2+ is enforced
 * on the ALB; and the DTO's {@link TransactionDetailDto#toString()}
 * override masks the PAN to its last 4 digits to prevent accidental
 * logging of the full PAN to CloudWatch Logs. Defense in depth is
 * provided by the CloudWatch log filters and Amazon Macie S3 scanning
 * described in AAP &sect;0.6.6.</p>
 *
 * <h2>Monetary precision (AAP &sect;0.6.1 / &sect;0.7.1)</h2>
 * <p>The transaction amount is read from the {@link Transaction} entity
 * as a {@link BigDecimal} mapped to PostgreSQL {@code NUMERIC(11, 2)}
 * (matching COBOL {@code TRAN-AMT PIC S9(09)V99} from
 * {@code app/cpy/CVTRA05Y.cpy} L10). The {@link #toDto(Transaction)}
 * mapper applies {@link BigDecimal#setScale(int,
 * java.math.RoundingMode) setScale(2, HALF_EVEN)} explicitly before
 * populating the DTO &mdash; complying with AAP &sect;0.6.1
 * ("BigDecimal with RoundingMode.HALF_EVEN for all monetary values")
 * and the DTO contract ("Producer must apply setScale(2,
 * RoundingMode.HALF_EVEN) on every value"). No arithmetic is performed
 * in this read-only path, so the {@code ON SIZE ERROR} branch (AAP
 * &sect;0.6.1) does not apply.</p>
 *
 * <h2>Constructor injection and thread safety (AAP &sect;0.7.1)</h2>
 * <p>This class uses constructor injection only (no
 * {@code @Autowired} field injection or setter injection per AAP
 * &sect;0.7.1 "Constructor injection only"). The single injected
 * collaborator ({@link TransactionRepository}) is a stateless Spring
 * Data JPA proxy; the service itself holds no mutable state and is
 * therefore thread-safe and may be invoked concurrently from any
 * number of HTTP worker threads.</p>
 *
 * @see TransactionRepository
 * @see TransactionDetailDto
 * @see Transaction
 * @see RecordNotFoundException
 * @see ValidationException
 */
@Service
public class TransactionDetailService {

    /**
     * SLF4J logger for structured JSON log emission. Per AAP &sect;0.7.2
     * (observability), application logs are shipped to CloudWatch Logs
     * via the Logback + {@code logstash-logback-encoder} configuration
     * declared in {@code src/main/resources/logback-spring.xml}. This
     * logger emits diagnostic info for transaction detail lookups,
     * validation failures, and not-found events &mdash; never including
     * the full PAN (only the masked DTO {@code toString()} form when
     * logged).
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(TransactionDetailService.class);

    /**
     * Spring Data JPA repository for the {@link Transaction} entity.
     * Replaces the COBOL {@code EXEC CICS READ DATASET('TRANSACT')}
     * primitive at {@code COTRN01C.cbl} L269&ndash;L278. Injected via
     * the constructor; never reassigned.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Constructs the service with the required {@link TransactionRepository}
     * collaborator. Per AAP &sect;0.7.1 ("Dependency injection for loose
     * coupling") and the agent-prompt rule ("Constructor injection
     * only"), Spring resolves the constructor parameter via component
     * scanning and supplies the singleton {@link TransactionRepository}
     * bean.
     *
     * @param transactionRepository the Spring Data JPA repository for
     *                              {@link Transaction}; must not be
     *                              {@code null}.
     * @throws NullPointerException if {@code transactionRepository} is
     *                              {@code null} &mdash; defensive
     *                              fail-fast at bean construction
     *                              time, never expected to fire in
     *                              practice because Spring's
     *                              autowiring rejects unsatisfied
     *                              constructor parameters at startup.
     */
    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
    }

    /**
     * Returns the transaction detail view for the transaction identified
     * by {@code tranId}.
     *
     * <p>This method is the Java translation of the COBOL/CICS sequence
     * {@code PROCESS-ENTER-KEY} (L144&ndash;L174) followed by
     * {@code READ-TRANSACT-FILE} (L267&ndash;L296) in
     * {@code app/cbl/COTRN01C.cbl}. The COBOL flow is:</p>
     * <pre>{@code
     *   IF TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES
     *       MOVE 'Y' TO WS-ERR-FLG
     *       MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE
     *       ...
     *   MOVE TRNIDINI OF COTRN1AI TO TRAN-ID
     *   PERFORM READ-TRANSACT-FILE  *> EXEC CICS READ FILE('TRANSACT')
     *                               *> RIDFLD(TRAN-ID)
     *                               *> WHEN DFHRESP(NOTFND)
     *                               *>   MOVE 'Transaction ID NOT found...'
     *                               *>     TO WS-MESSAGE
     * }</pre>
     *
     * <h3>Flow</h3>
     * <ol>
     *   <li><b>Validate</b> &mdash; null/blank check on {@code tranId}.
     *       Throws {@link ValidationException} on failure (mirrors COBOL
     *       paragraph {@code PROCESS-ENTER-KEY} at L147&ndash;L152).
     *       Whitespace is trimmed before any further processing
     *       &mdash; matching the COBOL behavior where the
     *       fixed-width {@code TRNIDINI PIC X(16)} field is space-
     *       padded on the right and the {@code MOVE} to
     *       {@code TRAN-ID} preserves the trim semantics implicitly.</li>
     *   <li><b>Database read</b> &mdash; invoke
     *       {@link TransactionRepository#findById(Object)} keyed by the
     *       trimmed transaction ID (mirrors COBOL paragraph
     *       {@code READ-TRANSACT-FILE} at L269&ndash;L278). On
     *       {@link java.util.Optional#empty()}, throw
     *       {@link RecordNotFoundException} (mirrors COBOL
     *       {@code WHEN DFHRESP(NOTFND)} branch at L283&ndash;L288).
     *       The default reason code is the verbatim COBOL
     *       {@code FILE STATUS '23'} value per AAP &sect;0.7.2 (the
     *       caller passes the entity-type tag "Transaction" as the
     *       first constructor argument matching the established
     *       codebase pattern; see {@code CardDetailService}).</li>
     *   <li><b>Build DTO</b> &mdash; project the {@link Transaction}
     *       entity onto a {@link TransactionDetailDto} record via the
     *       canonical record constructor (no static factory is exposed
     *       on the DTO record). The
     *       {@link Transaction#getTranAmt() tranAmt} value is rescaled
     *       to {@code scale=2} with {@link RoundingMode#HALF_EVEN} per
     *       AAP &sect;0.6.1.</li>
     *   <li><b>Return</b> &mdash; the controller wraps the DTO in an
     *       {@code ApiResponse} envelope and Spring MVC serializes it
     *       as the {@code HTTP 200 OK} JSON body.</li>
     * </ol>
     *
     * @param tranId the 16-character transaction identifier supplied by
     *               the caller via {@code GET /api/transactions/{id}}.
     *               Leading and trailing whitespace is trimmed before
     *               the database lookup. Must not be {@code null} or
     *               blank.
     * @return the {@link TransactionDetailDto} read-only view for the
     *         requested transaction; never {@code null}.
     * @throws ValidationException if {@code tranId} is {@code null} or
     *                             blank after trimming &mdash; mirrors
     *                             COBOL paragraph
     *                             {@code PROCESS-ENTER-KEY} validation
     *                             (HTTP 400 Bad Request via
     *                             {@code GlobalExceptionHandler}).
     * @throws RecordNotFoundException if no transaction row exists for
     *                                 {@code tranId} &mdash; mirrors
     *                                 COBOL {@code FILE STATUS '23'}
     *                                 {@code DFHRESP(NOTFND)} handling
     *                                 at {@code READ-TRANSACT-FILE}
     *                                 L283&ndash;L288 (HTTP 404 Not
     *                                 Found via
     *                                 {@code GlobalExceptionHandler}).
     */
    @Transactional(readOnly = true)
    public TransactionDetailDto getTransactionDetail(String tranId) {
        // COBOL: COTRN01C:PROCESS-ENTER-KEY — IF TRNIDINI OF COTRN1AI =
        // SPACES OR LOW-VALUES → MOVE 'Y' TO WS-ERR-FLG / MOVE 'Tran ID
        // can NOT be empty...' TO WS-MESSAGE. The Java target raises
        // ValidationException which the GlobalExceptionHandler
        // translates to HTTP 400 Bad Request. The empty-string check
        // covers both COBOL SPACES (space-padded) and LOW-VALUES
        // (binary-zero-padded) input shapes.
        if (tranId == null || tranId.isBlank()) {
            LOG.debug("TransactionDetailService: empty tranId rejected");
            throw new ValidationException("Tran ID can NOT be empty...");
        }
        // COBOL: COTRN01C:PROCESS-ENTER-KEY L172 — MOVE TRNIDINI OF
        // COTRN1AI TO TRAN-ID. The COBOL fixed-width PIC X(16) field is
        // space-padded; the Java target trims whitespace explicitly so
        // a caller-supplied padded value still resolves correctly
        // against the PostgreSQL VARCHAR(16) primary key.
        String trimmed = tranId.trim();

        LOG.debug("TransactionDetailService.getTransactionDetail tranId={}", trimmed);

        // COBOL: COTRN01C:READ-TRANSACT-FILE — EXEC CICS READ
        // DATASET('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID)
        // KEYLENGTH(LENGTH OF TRAN-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        // — primary-key read against the TRANSACT VSAM KSDS (now the
        // PostgreSQL "transactions" table per V005 Flyway migration).
        // Spring Data JPA returns Optional.empty() on NOTFND; the
        // Optional.orElseThrow bridge maps that to the typed domain
        // exception RecordNotFoundException — preserving the COBOL
        // FILE STATUS '23' semantics. The (entity-type, identifier)
        // constructor form matches the established codebase pattern
        // (see CardDetailService) and the agent-prompt example for
        // this file.
        Transaction tx = transactionRepository.findById(trimmed)
                .orElseThrow(() -> {
                    // COBOL: COTRN01C:READ-TRANSACT-FILE L283-L288 —
                    // WHEN DFHRESP(NOTFND) → MOVE 'Transaction ID NOT
                    // found...' TO WS-MESSAGE. The diagnostic message
                    // echoes the supplied identifier; per AAP §0.6.6
                    // the identifier is non-PII (a 16-character opaque
                    // sequence number, NOT a PAN), so it is safe to
                    // include in the error envelope.
                    LOG.info("TransactionDetailService: Transaction ID NOT found tranId={}",
                            trimmed);
                    // QA Final-CP6 Finding M6 (MINOR): structured error
                    // code (TRANSACTION_NOT_FOUND) matching the post-fix
                    // exception envelope pattern. Replaces the previous
                    // entity-class name ("Transaction") so the `code`
                    // field on the wire envelope is consistent with the
                    // pattern used by CardDetailService (CARD_NOT_FOUND),
                    // AccountViewService (ACCOUNT_NOT_FOUND), etc.
                    return new RecordNotFoundException("TRANSACTION_NOT_FOUND",
                            "tranId=" + trimmed);
                });

        // COBOL: COTRN01C:PROCESS-ENTER-KEY L177-L191 — MOVE TRAN-* TO
        // *I OF COTRN1AI. The Java target projects the JPA entity onto
        // the read-only response DTO. The full PAN flows through to the
        // DTO unmasked — masking is the DTO's responsibility (it applies
        // only in toString() output per AAP §0.6.6); REST callers
        // receive the full PAN to match the legacy 3270 screen behavior
        // (authorization enforced by Spring Security upstream).
        TransactionDetailDto dto = toDto(tx);

        // Per AAP §0.7.2 observability — log the successful read at
        // INFO level using only the masked DTO toString() form. The
        // TransactionDetailDto.toString() override masks the PAN to
        // last-4 digits, so no full PAN reaches CloudWatch Logs even
        // through accidental DTO logging.
        LOG.info("TransactionDetailService.getTransactionDetail returning {}", dto);
        return dto;
    }

    /**
     * Projects the {@link Transaction} JPA entity onto a
     * {@link TransactionDetailDto} response record.
     *
     * <p>This mapper is the Java translation of the
     * {@code MOVE TRAN-* TO ...I OF COTRN1AI} block at
     * {@code app/cbl/COTRN01C.cbl} L177&ndash;L191 (the
     * {@code PROCESS-ENTER-KEY} paragraph following a successful
     * {@code READ-TRANSACT-FILE}). Every COBOL {@code MOVE} statement
     * has a corresponding component assignment in the record
     * constructor, in the same order as the BMS map's symbolic-map
     * output fields:</p>
     * <pre>{@code
     *   MOVE TRAN-ID            TO TRNIDI    OF COTRN1AI   → transactionId
     *   MOVE TRAN-CARD-NUM      TO CARDNUMI  OF COTRN1AI   → cardNumber
     *   MOVE TRAN-TYPE-CD       TO TTYPCDI   OF COTRN1AI   → transactionType
     *   MOVE TRAN-CAT-CD        TO TCATCDI   OF COTRN1AI   → transactionCategory
     *   MOVE TRAN-SOURCE        TO TRNSRCI   OF COTRN1AI   → source
     *   MOVE WS-TRAN-AMT        TO TRNAMTI   OF COTRN1AI   → amount
     *   MOVE TRAN-DESC          TO TDESCI    OF COTRN1AI   → description
     *   MOVE TRAN-ORIG-TS       TO TORIGDTI  OF COTRN1AI   → originationTimestamp
     *   MOVE TRAN-PROC-TS       TO TPROCDTI  OF COTRN1AI   → processingTimestamp
     *   MOVE TRAN-MERCHANT-ID   TO MIDI      OF COTRN1AI   → merchantId
     *   MOVE TRAN-MERCHANT-NAME TO MNAMEI    OF COTRN1AI   → merchantName
     *   MOVE TRAN-MERCHANT-CITY TO MCITYI    OF COTRN1AI   → merchantCity
     *   MOVE TRAN-MERCHANT-ZIP  TO MZIPI     OF COTRN1AI   → merchantZip
     * }</pre>
     *
     * <p>The DTO record constructor argument order matches the record's
     * component declaration order (see {@link TransactionDetailDto}). The
     * card number is passed through <b>unmasked</b> &mdash; PAN masking
     * is handled by the DTO's {@link TransactionDetailDto#toString()}
     * override per AAP &sect;0.6.6, not by this service.</p>
     *
     * <h3>Monetary precision (AAP &sect;0.6.1)</h3>
     * <p>The transaction amount is rescaled to {@code scale=2} with
     * {@link RoundingMode#HALF_EVEN} (banker's rounding) before
     * populating the DTO. This satisfies the DTO contract ("Producer
     * must apply setScale(2, RoundingMode.HALF_EVEN) on every value")
     * and matches the COBOL {@code MOVE TRAN-AMT TO WS-TRAN-AMT
     * PIC +99999999.99} display formatting at
     * {@code app/cbl/COTRN01C.cbl} L177 (the COBOL {@code WS-TRAN-AMT}
     * working-storage variable is a 2-decimal-place display picture).
     * Although the database column is already {@code NUMERIC(11, 2)}
     * and the JDBC driver typically returns the {@link BigDecimal} with
     * the correct scale, the explicit {@code setScale} call is a
     * defensive guarantee per the AAP and the DTO contract.</p>
     *
     * @param t the persisted {@link Transaction} entity; must not be
     *          {@code null} (the caller ensures this via the
     *          {@link java.util.Optional#orElseThrow} bridge before
     *          invoking this method).
     * @return the {@link TransactionDetailDto} response record; never
     *         {@code null}.
     */
    private TransactionDetailDto toDto(Transaction t) {
        // COBOL: COTRN01C:PROCESS-ENTER-KEY L177 — MOVE TRAN-AMT TO
        // WS-TRAN-AMT (PIC +99999999.99). The Java target applies
        // setScale(2, HALF_EVEN) explicitly to satisfy the DTO contract
        // and AAP §0.6.1 BigDecimal arithmetic rule. Null-safe — a
        // null amount (which should never occur given the NOT NULL
        // constraint on the V005 tran_amt column) is propagated as
        // null to the DTO rather than triggering an NPE here, matching
        // the COBOL behavior where MOVE of a numeric value is always
        // well-defined.
        BigDecimal amount = t.getTranAmt();
        if (amount != null) {
            amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        }

        // COBOL: COTRN01C:PROCESS-ENTER-KEY L178-L191 — MOVE TRAN-*
        // TO *I OF COTRN1AI. Component order matches the
        // TransactionDetailDto record declaration order:
        //   (transactionId, transactionType, transactionCategory,
        //    source, description, amount, merchantId, merchantName,
        //    merchantCity, merchantZip, cardNumber,
        //    originationTimestamp, processingTimestamp)
        return new TransactionDetailDto(
                t.getTranId(),
                t.getTranTypeCd(),
                t.getTranCatCd(),
                t.getTranSource(),
                t.getTranDesc(),
                amount,
                t.getTranMerchantId(),
                t.getTranMerchantName(),
                t.getTranMerchantCity(),
                t.getTranMerchantZip(),
                // PAN masking — PCI-DSS Requirement 3.4.1 enforced at
                // the producer so the wire DTO never carries the full
                // PAN. Issue CP4-#13: previous behavior returned the
                // unmasked card number on this detail endpoint while
                // the list endpoint correctly masked it. Both endpoints
                // now use the identical "************XXXX" 12-asterisk
                // + last-4 pattern as TransactionListService.maskPan
                // and CardListService.maskPan.
                maskPan(t.getTranCardNum()),
                t.getTranOrigTs(),
                t.getTranProcTs());
    }

    /**
     * Masks a card-number string for PCI-DSS-compliant transmission.
     *
     * <p>Applies the canonical {@code "************XXXX"} format
     * (12 leading asterisks + the trailing 4 characters of the input).
     * This implementation mirrors {@code TransactionListService.maskPan}
     * and {@code CardListService.maskPan} verbatim to guarantee
     * identical PAN-masking semantics across all list-and-detail
     * endpoints (Issue CP4-#13). PCI-DSS v4.0 Requirement 3.4.1
     * (mask all but the last 4) is satisfied: at most the last 4
     * characters are revealed.</p>
     *
     * <p>Defensive handling:</p>
     * <ul>
     *   <li>{@code null} or shorter-than-4 input &rarr; returns
     *       {@code "****"} (fully masked) so no partial digits ever
     *       leak.</li>
     *   <li>Standard 16-character input (the
     *       {@code TRAN-CARD-NUM PIC X(16)} COBOL contract) &rarr;
     *       returns 12 asterisks + last 4 digits.</li>
     * </ul>
     *
     * @param pan the card-number string to mask; may be {@code null}
     * @return a non-{@code null} masked representation; never reveals
     *         more than the last 4 characters of the input
     */
    // COBOL: COTRN01C had no PAN masking — the legacy 3270 screen
    // displayed the full 16-digit TRAN-CARD-NUM. PAN masking is a
    // Java-side PCI-DSS guard rail added per AAP §0.6.6 and Issue
    // CP4-#13 so the REST DTO is consistent with the list endpoint.
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "************" + pan.substring(pan.length() - 4);
    }
}
