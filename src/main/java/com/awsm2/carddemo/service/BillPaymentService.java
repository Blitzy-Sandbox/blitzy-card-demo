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

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bill payment service &mdash; the Java target for the COBOL/CICS program
 * {@code app/cbl/COBIL00C.cbl} (CICS transaction id {@code 'CB00'}).
 *
 * <p>This service implements the &quot;pay-full-balance&quot; online flow
 * modeled on the COBOL source:</p>
 * <ol>
 *   <li>validates the operator's input (account ID + confirm flag);</li>
 *   <li>reads the {@code ACCT-RECORD} for the supplied {@code ACCT-ID};</li>
 *   <li>verifies that the current balance is strictly positive (otherwise
 *       short-circuits with the verbatim COBOL message
 *       <em>"You have nothing to pay..."</em>);</li>
 *   <li>resolves the cardholder's primary card via the
 *       {@code CARDXREF} alternate index;</li>
 *   <li>assigns the next sequential 16-digit transaction ID
 *       (the MAX-TRAN-ID + 1 idiom, originally
 *       {@code STARTBR}/{@code READPREV}/{@code ENDBR} on
 *       {@code TRANSACT});</li>
 *   <li>writes a new {@code TRAN-RECORD} whose amount equals the
 *       current account balance, with verbatim COBOL-literal type code
 *       {@code '02'}, category {@code 2}, source {@code 'POS TERM'},
 *       description {@code 'BILL PAYMENT - ONLINE'}, merchant id
 *       {@code 999999999}, merchant name {@code 'BILL PAYMENT'}, city /
 *       ZIP {@code 'N/A'} preserved per AAP &sect;0.7.3 Minimal Change
 *       Clause;</li>
 *   <li>subtracts the transaction amount from the account balance
 *       (the {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
 *       step) with explicit {@link OnSizeErrorException} guard
 *       replicating the COBOL {@code ON SIZE ERROR} semantic per
 *       AAP &sect;0.7.1;</li>
 *   <li>persists the new {@code ACCT-RECORD} (replacing the CICS
 *       {@code REWRITE} on {@code ACCTDAT});</li>
 *   <li>invalidates the account's cache entry under the
 *       {@code account-view} namespace (cache-aside discipline);</li>
 *   <li>publishes <b>both</b>
 *       {@link KafkaEventPublisher#publishTransactionPosted(Long,
 *       TransactionAddDto) transaction.posted} and
 *       {@link KafkaEventPublisher#publishAccountUpdated(Long,
 *       AccountUpdateDto) account.updated} MSK events partitioned by
 *       the owning account ID for per-account ordering per
 *       AAP &sect;0.6.5;</li>
 *   <li>emits an immutable audit event via
 *       {@link AuditLogService#logTransactionEvent(String, Long, String,
 *       String, String, Map, String) logTransactionEvent} carrying the
 *       transaction ID, account ID, operator code, event type
 *       {@code "BILL_PAID"}, and a PII-safe payload (last four digits
 *       of the card only &mdash; never the full PAN).</li>
 * </ol>
 *
 * <h2>COBOL paragraph-to-Java translation map (AAP &sect;0.7.3)</h2>
 *
 * <table>
 *   <caption>{@code COBIL00C.cbl} &harr;
 *            {@link #processBillPayment(BillPaymentDto)}</caption>
 *   <tr>
 *     <th>COBOL paragraph / line</th>
 *     <th>Java equivalent</th>
 *   </tr>
 *   <tr>
 *     <td>{@code PROCESS-ENTER-KEY} (L154&ndash;L191) &mdash; input
 *         validation (account ID + confirm flag)</td>
 *     <td>{@code validateAccountId(...)} +
 *         {@code dispatchConfirm(...)} (this class)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code READ-ACCTDAT-FILE} &mdash;
 *         {@code EXEC CICS READ DATASET('ACCTDAT') RIDFLD(ACCT-ID)}</td>
 *     <td>{@link AccountRepository#findById(Object)} +
 *         {@link RecordNotFoundException} on NOTFND</td>
 *   </tr>
 *   <tr>
 *     <td>{@code IF ACCT-CURR-BAL <= ZEROS} (L198) &mdash;
 *         {@code 'You have nothing to pay...'} short-circuit</td>
 *     <td>{@link BigDecimal#signum()} &le; 0 returns an informational
 *         DTO (no exception &mdash; the COBOL was a screen prompt, not
 *         a hard error per AAP)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code READ-CXACAIX-FILE} (L211) &mdash;
 *         {@code EXEC CICS READ DATASET('CXACAIX') RIDFLD(XREF-ACCT-ID)}
 *         on the {@code CARDXREF.VSAM.AIX} alternate index</td>
 *     <td>{@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
 *         (B-tree index {@code idx_cardxref_acct_id} per AAP
 *         &sect;0.6.2)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MOVE HIGH-VALUES TO TRAN-ID; STARTBR; READPREV; ENDBR;
 *         ADD 1 TO WS-TRAN-ID-NUM} (L212&ndash;L217)</td>
 *     <td>{@link TransactionRepository#findTopByOrderByTranIdDesc()} +
 *         numeric add 1 + zero-pad to 16 digits via
 *         {@code String.format("%016d", n)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code INITIALIZE TRAN-RECORD; MOVE * TO TRAN-*}
 *         (L218&ndash;L232)</td>
 *     <td>{@link Transaction} constructor with verbatim COBOL literal
 *         constants defined at the top of this class</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MOVE ACCT-CURR-BAL TO TRAN-AMT} (L224)</td>
 *     <td>{@code tranAmt = account.getAcctCurrBal()} (BigDecimal
 *         scale=2; RoundingMode.HALF_EVEN preserved)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM} (L225)</td>
 *     <td>{@code tx.setTranCardNum(xref.getXrefCardNum())}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET-CURRENT-TIMESTAMP} (L230, L249&ndash;L267) &mdash;
 *         CICS {@code ASKTIME} / {@code FORMATTIME}</td>
 *     <td>{@link LocalDateTime#now()} replacing LE
 *         {@code CEEDAYS}/{@code CEEDATE} per AAP &sect;0.6.3</td>
 *   </tr>
 *   <tr>
 *     <td>{@code WRITE-TRANSACT-FILE} (L233) &mdash;
 *         {@code EXEC CICS WRITE DATASET('TRANSACT')}</td>
 *     <td>{@link TransactionRepository#save(Object)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
 *         (L234) with implicit COBOL {@code ON SIZE ERROR}</td>
 *     <td>{@link BigDecimal#subtract(BigDecimal)} +
 *         {@code .setScale(2, RoundingMode.HALF_EVEN)};
 *         {@link ArithmeticException} wrapped as
 *         {@link OnSizeErrorException} per AAP &sect;0.7.1</td>
 *   </tr>
 *   <tr>
 *     <td>{@code UPDATE-ACCTDAT-FILE} (L235) &mdash;
 *         {@code EXEC CICS REWRITE DATASET('ACCTDAT')}</td>
 *     <td>{@link AccountRepository#save(Object)} inside the same
 *         {@link Transactional @Transactional} boundary</td>
 *   </tr>
 *   <tr>
 *     <td>(implicit CICS task-end {@code SYNCPOINT})</td>
 *     <td>{@link Transactional @Transactional(rollbackFor = Exception.class,
 *         isolation = Isolation.READ_COMMITTED)} &mdash; coordinates
 *         {@code TRANSACT} insert + {@code ACCTDAT} update as one
 *         logical UOW (AAP &sect;0.6.2 / &sect;0.7.1)</td>
 *   </tr>
 *   <tr>
 *     <td>(NEW) Cache-aside post-write invalidation</td>
 *     <td>{@link CacheService#evict(String, String)} on
 *         {@code "account-view"} namespace (AAP &sect;0.7.1)</td>
 *   </tr>
 *   <tr>
 *     <td>(NEW) Inter-service event publication</td>
 *     <td>{@link KafkaEventPublisher#publishTransactionPosted} +
 *         {@link KafkaEventPublisher#publishAccountUpdated} both
 *         partitioned by account ID per AAP &sect;0.6.5</td>
 *   </tr>
 *   <tr>
 *     <td>(NEW) Immutable audit trail (replaces implicit COBOL
 *         audit emission)</td>
 *     <td>{@link AuditLogService#logTransactionEvent} writing to
 *         CloudTrail + OpenSearch per AAP &sect;0.6.6</td>
 *   </tr>
 * </table>
 *
 * <h2>Confirm-flag semantics (COBIL00C PROCESS-ENTER-KEY L173&ndash;L191)</h2>
 *
 * <p>The COBOL {@code EVALUATE CONFIRMI} cascade is translated as
 * follows. The agent_prompt explicitly directs the
 * <em>"Confirm to pay the balance (Y/N)..."</em> and
 * <em>"You have nothing to pay..."</em> branches to be returned as
 * informational message DTOs (not thrown) because the COBOL handled
 * them as screen messages, and the explicit error branches to throw
 * {@link ValidationException} with verbatim COBOL strings.</p>
 *
 * <ul>
 *   <li>{@code WHEN 'Y' / 'y'} &rarr; full payment path (the
 *       happy path).</li>
 *   <li>{@code WHEN 'N' / 'n'} &rarr; informational DTO with
 *       {@code confirm = "N"} preserved &mdash; no payment posted.</li>
 *   <li>{@code WHEN SPACES / LOW-VALUES} &rarr; informational DTO
 *       (after reading the account so the caller can see the current
 *       balance) &mdash; no payment posted.</li>
 *   <li>{@code WHEN OTHER} &rarr; throws
 *       {@code ValidationException("Invalid value. Valid values are
 *       (Y/N)...")} per L185&ndash;L190.</li>
 * </ul>
 *
 * <h2>Monetary precision (AAP &sect;0.6.1)</h2>
 *
 * <p>The COBOL {@code ACCT-CURR-BAL PIC S9(10)V99} and {@code TRAN-AMT
 * PIC S9(09)V99} fields are translated to {@link BigDecimal} with
 * {@code scale = 2} and {@link RoundingMode#HALF_EVEN} (banker's
 * rounding) at every arithmetic boundary. No {@code float} or
 * {@code double} is ever used for any monetary value. The COBOL
 * {@code ON SIZE ERROR} semantic is replicated explicitly via an
 * {@link ArithmeticException} catch wrapped as
 * {@link OnSizeErrorException} per AAP &sect;0.7.1.</p>
 *
 * <h2>Transactional integrity (AAP &sect;0.6.2)</h2>
 *
 * <p>The transaction-write + account-update pair is the second
 * explicit dual-dataset transactional boundary in the source code
 * base (after {@code COACTUPC.cbl}). The original COBIL00C program
 * relies on the implicit CICS task-end {@code SYNCPOINT} because
 * both writes are in the same task. In the Java target, the
 * {@link Transactional @Transactional(rollbackFor = Exception.class,
 * isolation = Isolation.READ_COMMITTED)} declaration provides the
 * same atomic dual-write guarantee &mdash; any thrown exception
 * (including the {@link OnSizeErrorException} guard on the balance
 * subtract) rolls back BOTH the {@code TRANSACT} insert and the
 * {@code ACCTDAT} rewrite.</p>
 *
 * <h2>PII discipline (AAP &sect;0.6.6 / &sect;0.7.1)</h2>
 *
 * <p>Per the PCI-DSS-aligned logging discipline, this service
 * NEVER logs the full Primary Account Number (PAN). The card number
 * appears in the {@link Transaction} entity persisted to the
 * authoritative {@code transactions} table and in the
 * {@link TransactionAddDto} payload published to the MSK
 * {@code transaction.posted} topic, but only the last four digits of
 * the PAN appear in any audit log payload or SLF4J log message
 * emitted by this class.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 traceability)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COBIL00C.cbl} (CICS
 *       TRANID {@code 'CB00'}; files {@code 'ACCTDAT'} +
 *       {@code 'CARDXREF'} + {@code 'TRANSACT'})</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COBIL00.bms} (mapset
 *       {@code COBIL00}, map {@code COBIL0A})</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COBIL00.CPY}</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVACT01Y.cpy}
 *       (account), {@code app/cpy/CVTRA05Y.cpy} (transaction),
 *       {@code app/cpy/CVACT03Y.cpy} (cross-reference)</li>
 *   <li><b>REST endpoint:</b> {@code POST /api/billing/pay} (see
 *       {@code BillingController})</li>
 * </ul>
 *
 * @see AccountRepository
 * @see CardCrossReferenceRepository
 * @see TransactionRepository
 * @see KafkaEventPublisher
 * @see CacheService
 * @see AuditLogService
 * @see BillPaymentDto
 */
@Service
public class BillPaymentService {

    /**
     * Class-level SLF4J logger. Used for diagnostic logging of every
     * phase of the COBIL00C-equivalent flow. PII discipline is enforced
     * end-to-end (AAP &sect;0.6.6 / &sect;0.7.1): only zero-padded
     * {@code acctId} and the last four digits of the card number may
     * ever appear in log output &mdash; never the full 16-digit PAN.
     */
    private static final Logger LOG = LoggerFactory.getLogger(BillPaymentService.class);

    // =========================================================================
    // COBOL literal constants (verbatim per AAP §0.7.3 Minimal Change Clause)
    // =========================================================================

    /**
     * Transaction type code constant for bill payment.
     * <p>COBOL: {@code MOVE '02' TO TRAN-TYPE-CD} (COBIL00C.cbl L220).
     */
    private static final String BILL_PAY_TYPE_CODE = "02";

    /**
     * Transaction category code constant for bill payment.
     * <p>COBOL: {@code MOVE 2 TO TRAN-CAT-CD} (COBIL00C.cbl L221).
     * The {@link Transaction#getTranCatCd() tranCatCd} field is typed as
     * {@link Integer} per the entity contract, so the COBOL numeric
     * literal {@code 2} translates verbatim to {@code Integer.valueOf(2)}.
     */
    private static final Integer BILL_PAY_CAT_CODE = 2;

    /**
     * Transaction source string constant for bill payment.
     * <p>COBOL: {@code MOVE 'POS TERM' TO TRAN-SOURCE} (COBIL00C.cbl L222).
     */
    private static final String BILL_PAY_SOURCE = "POS TERM";

    /**
     * Transaction description string constant for bill payment.
     * <p>COBOL: {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}
     * (COBIL00C.cbl L223).
     */
    private static final String BILL_PAY_DESC = "BILL PAYMENT - ONLINE";

    /**
     * Merchant ID constant for bill payment (9-digit identifier).
     * <p>COBOL: {@code MOVE 999999999 TO TRAN-MERCHANT-ID}
     * (COBIL00C.cbl L226).
     */
    private static final Long BILL_PAY_MERCHANT_ID = 999_999_999L;

    /**
     * Merchant name constant for bill payment.
     * <p>COBOL: {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}
     * (COBIL00C.cbl L227).
     */
    private static final String BILL_PAY_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * Merchant city/zip placeholder constant for bill payment.
     * <p>COBOL: {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} /
     * {@code TRAN-MERCHANT-ZIP} (COBIL00C.cbl L228&ndash;L229).
     */
    private static final String BILL_PAY_NA = "N/A";

    // =========================================================================
    // MAX-TRAN-ID + 1 idiom support constants
    // =========================================================================

    /**
     * Seed transaction ID used when the transactions journal is empty
     * (the JPA equivalent of COBOL {@code MOVE HIGH-VALUES TO TRAN-ID}
     * followed by {@code STARTBR}/{@code READPREV} returning an EOF
     * condition). Sixteen zero digits so the first generated ID becomes
     * {@code "0000000000000001"}.
     */
    private static final String SEED_TRAN_ID = "0000000000000000";

    /**
     * Numeric ceiling for the 16-digit transaction ID space (the
     * largest value {@code TRAN-ID PIC 9(16)} can carry). Exceeding
     * this raises an {@link OnSizeErrorException} to replicate the
     * COBOL {@code ON SIZE ERROR} semantic on the MAX-TRAN-ID + 1
     * arithmetic.
     */
    private static final BigDecimal MAX_TRAN_ID = new BigDecimal("9999999999999999");

    /**
     * Numeric absolute ceiling for the account balance after the
     * COBOL {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
     * step (COBIL00C.cbl L234). Mirrors the {@code PIC S9(10)V99}
     * declaration of {@code ACCT-CURR-BAL} per
     * {@code app/cpy/CVACT01Y.cpy:L7-L14} (10 integer digits + 2
     * fraction digits = absolute max 99,999,999,999.99). Exceeding
     * this raises {@link OnSizeErrorException} to replicate the COBOL
     * {@code ON SIZE ERROR} semantic per AAP &sect;0.7.1.
     */
    private static final BigDecimal MAX_ACCOUNT_BALANCE = new BigDecimal("99999999999.99");

    /**
     * Numeric absolute ceiling for the {@code TRAN-AMT} column.
     * Mirrors the {@code PIC S9(09)V99} declaration of
     * {@code TRAN-AMT} per {@code app/cpy/CVTRA05Y.cpy:L10} (9 integer
     * digits + 2 fraction digits = absolute max 999,999,999.99). The
     * mapped JDBC type is {@code NUMERIC(11,2)} per
     * {@code db/migration/V005__create_transaction.sql}, which the
     * PostgreSQL driver enforces at INSERT time and surfaces as
     * {@code DataIntegrityViolationException} on overflow.
     *
     * <p>This ceiling is STRICTLY tighter than {@link #MAX_ACCOUNT_BALANCE}
     * (the {@code ACCT-CURR-BAL} field has 10 integer digits, while
     * {@code TRAN-AMT} has only 9). When COBIL00C copies
     * {@code ACCT-CURR-BAL} into {@code TRAN-AMT} (line 224 of the
     * source), any account balance with an absolute value greater than
     * {@code 999,999,999.99} would overflow the narrower {@code TRAN-AMT}
     * field. The source COBOL would have produced an {@code ON SIZE
     * ERROR} condition; the Java target raises an
     * {@link OnSizeErrorException} pre-check before attempting the
     * insert so the failure is a typed business-logic exception (HTTP
     * 422 Unprocessable Entity) rather than a database-layer
     * {@code DataIntegrityViolationException} (HTTP 500). Issue
     * CP4-#6.</p>
     */
    private static final BigDecimal MAX_TRAN_AMT = new BigDecimal("999999999.99");

    // =========================================================================
    // Cache namespace and audit metadata
    // =========================================================================

    /**
     * Cache namespace for the account-view cache entries that
     * {@link AccountViewService} populates and that this service
     * invalidates on every successful balance change. Aligned with
     * {@link AccountViewService#CACHE_NS} so the invalidation hits
     * exactly the same key the view service populated (cache-aside
     * coherence per AAP &sect;0.7.1).
     */
    private static final String CACHE_NS_ACCOUNT = AccountViewService.CACHE_NS;

    /**
     * Audit event-type literal emitted on every successful bill
     * payment posting. Indexed under
     * {@code AuditLogService.transactionIndexName} on OpenSearch so
     * fraud-team queries can filter by event type per AAP
     * &sect;0.6.6.
     */
    private static final String AUDIT_EVENT_BILL_PAID = "BILL_PAID";

    /**
     * Operator identifier emitted on the audit event when the
     * service is invoked outside an authenticated REST context
     * (e.g., a Spring Batch retry). Production REST flows would
     * supply the JWT subject claim from the controller; this fallback
     * keeps the audit trail complete when no caller principal is
     * available.
     */
    private static final String AUDIT_OPERATOR_SYSTEM = "system";

    // =========================================================================
    // Collaborator beans (constructor-injected, final per AAP §0.7.1 DI rule)
    // =========================================================================

    /** Spring Data JPA repository for {@link Account}. */
    private final AccountRepository accountRepository;

    /** Spring Data JPA repository for {@link CardCrossReference}. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Spring Data JPA repository for {@link Transaction}. */
    private final TransactionRepository transactionRepository;

    /** MSK Kafka producer adapter (publishes both topics). */
    private final KafkaEventPublisher kafkaEventPublisher;

    /** ElastiCache Redis cache-aside adapter (invalidation only here). */
    private final CacheService cacheService;

    /** CloudTrail + OpenSearch immutable audit-trail adapter. */
    private final AuditLogService auditLogService;

    /**
     * Constructor &mdash; constructor-injected final fields per AAP
     * &sect;0.7.1 DI discipline (no field {@code @Autowired}; no
     * setter injection; no Lombok). Every collaborator is non-null;
     * {@link Objects#requireNonNull(Object, String)} fast-fails if
     * the Spring application context fails to supply one.
     *
     * @param accountRepository            account aggregate repository
     * @param cardCrossReferenceRepository card cross-reference repository
     *                                     (alternate-index lookup)
     * @param transactionRepository        transaction journal repository
     * @param kafkaEventPublisher          MSK Kafka producer adapter
     * @param cacheService                 ElastiCache Redis adapter
     * @param auditLogService              CloudTrail + OpenSearch adapter
     */
    public BillPaymentService(AccountRepository accountRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository,
                              TransactionRepository transactionRepository,
                              KafkaEventPublisher kafkaEventPublisher,
                              CacheService cacheService,
                              AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher must not be null");
        this.cacheService = Objects.requireNonNull(cacheService,
                "cacheService must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    // =========================================================================
    // Public API — translates COBIL00C MAIN-PARA + PROCESS-ENTER-KEY
    // =========================================================================

    /**
     * Process a bill payment for the supplied account, paying the
     * full current balance.
     *
     * <p>Translates {@code COBIL00C.cbl} {@code MAIN-PARA} and
     * {@code PROCESS-ENTER-KEY} (lines 99&ndash;244). The
     * {@link Transactional @Transactional} boundary replaces the
     * implicit CICS task-end {@code SYNCPOINT} that originally wrapped
     * the {@code WRITE TRANSACT} + {@code REWRITE ACCTDAT} pair; any
     * thrown {@link CardDemoException} subclass &mdash;
     * {@link ValidationException}, {@link RecordNotFoundException},
     * or {@link OnSizeErrorException} &mdash; rolls back BOTH writes
     * (AAP &sect;0.6.2 / &sect;0.7.1).</p>
     *
     * <h3>Confirm flag semantics</h3>
     * <ul>
     *   <li>{@code "Y"} / {@code "y"} &rarr; full payment posted; the
     *       returned DTO carries {@code transactionId}, {@code postedAt},
     *       and {@code amountPaid} populated.</li>
     *   <li>{@code "N"} / {@code "n"} &rarr; informational DTO returned
     *       with the {@code confirm = "N"} flag preserved; no payment
     *       posted and no XREF / TRANSACT read performed.</li>
     *   <li>{@code null} or blank &rarr; informational DTO returned
     *       AFTER reading the account so the caller sees the current
     *       balance; no payment posted (the COBOL "blank confirm" path
     *       reads the account for display per L182&ndash;L184).</li>
     *   <li>Any other value &rarr;
     *       {@link ValidationException} thrown with the verbatim COBOL
     *       message {@code "Invalid value. Valid values are (Y/N)..."}.</li>
     * </ul>
     *
     * <h3>Balance-zero short-circuit</h3>
     *
     * <p>After reading the account on the {@code "Y"} path, if
     * {@code ACCT-CURR-BAL <= 0} the service returns an informational
     * DTO carrying the current balance &mdash; no transaction is
     * posted, no XREF is read, no MSK events are published. This
     * matches the COBOL {@code IF ACCT-CURR-BAL <= ZEROS} branch at
     * L198 which sets {@code WS-ERR-FLG = 'Y'} and emits the screen
     * message {@code "You have nothing to pay..."}.</p>
     *
     * @param request the bill payment request DTO (account ID +
     *                confirm flag are required; all other fields are
     *                response-only and may be {@code null} on
     *                input)
     * @return a populated {@link BillPaymentDto} carrying either the
     *         confirmation receipt (on success) or the informational
     *         response (on cancel / preview / zero-balance)
     * @throws ValidationException     if account ID is missing,
     *                                 non-numeric, non-positive, OR if
     *                                 confirm carries an invalid
     *                                 (non-Y/N) value
     * @throws RecordNotFoundException if no account exists for the
     *                                 supplied ID OR no card cross-
     *                                 reference exists for the account
     *                                 on the {@code "Y"} payment path
     * @throws OnSizeErrorException    if the post-payment balance would
     *                                 overflow the COBOL {@code PIC
     *                                 S9(10)V99} precision ceiling OR
     *                                 the MAX-TRAN-ID + 1 arithmetic
     *                                 would exhaust the 16-digit
     *                                 ID space
     */
    // COBOL: COBIL00C:MAIN-PARA + PROCESS-ENTER-KEY (L99-L244)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BillPaymentDto processBillPayment(BillPaymentDto request) {
        Objects.requireNonNull(request, "request must not be null");

        // ---- COBOL: PROCESS-ENTER-KEY input-validation gate ------------
        // COBIL00C L158-L167: EVALUATE TRUE WHEN ACTIDINI = SPACES OR
        // LOW-VALUES → set ERR-FLG-ON, emit "Acct ID can NOT be empty...".
        Long acctId = validateAccountId(request);

        // COBIL00C L173-L191: EVALUATE CONFIRMI cascade — Y/y proceeds,
        // N/n cancels, blank/spaces displays balance, OTHER errors.
        String confirm = request.confirm();

        // Dispatch on confirm BEFORE reading the account for the N/n
        // branch (cancellation is cheap and short-circuits the read);
        // for the blank-confirm branch we still need to read the account
        // because the COBOL READ-ACCTDAT-FILE runs even on blank confirm
        // (L184) to populate the current balance for screen display.
        if (isCancellation(confirm)) {
            // COBOL: WHEN 'N' / 'n' (L178-L181) — operator cancelled
            // without confirming. Informational DTO; no payment posted,
            // no XREF read, no MSK events, no audit emission.
            LOG.info("Bill payment cancelled by operator acctId={}",
                    String.format("%011d", acctId));
            return cancellationDto(acctId, confirm);
        }
        if (!isBlankConfirm(confirm) && !isAffirmativeConfirm(confirm)) {
            // COBOL: WHEN OTHER (L185-L190) — emit verbatim error
            // string and set ERR-FLG-ON. In the Java target this is a
            // hard validation failure: HTTP 400 via GlobalExceptionHandler.
            LOG.info("Invalid confirm flag rejected acctId={} confirm={}",
                    String.format("%011d", acctId), confirm);
            throw new ValidationException(
                    "Invalid value. Valid values are (Y/N)...");
        }

        // ---- COBOL: READ-ACCTDAT-FILE ----------------------------------
        // Replaces EXEC CICS READ DATASET('ACCTDAT') RIDFLD(ACCT-ID); a
        // FILE STATUS '23' (NOTFND) condition maps to RecordNotFoundException
        // → HTTP 404 via GlobalExceptionHandler (AAP §0.7.1).
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Account not found: acctId=" + acctId));
        LOG.debug("Account loaded acctId={} version={}",
                String.format("%011d", acctId), account.getVersion());

        // COBOL: MOVE ACCT-CURR-BAL TO WS-CURR-BAL / CURBALI OF COBIL0AI
        // (L193-L194) — captured for the response DTO regardless of which
        // branch we take below.
        BigDecimal currentBalance = safeBalance(account.getAcctCurrBal());

        // Branch on confirm AFTER account read so the response DTO
        // always carries the live balance (mirrors the COBOL screen
        // re-display semantics).
        if (isBlankConfirm(confirm)) {
            // COBOL: WHEN SPACES / LOW-VALUES (L182-L184) — read the
            // account for display, then re-send the bill-payment screen
            // without writing any TRANSACT or REWRITE on ACCTDAT.
            LOG.info("Confirm flag blank — returning current balance preview acctId={} balance={}",
                    String.format("%011d", acctId), currentBalance);
            return previewDto(acctId, currentBalance, confirm);
        }

        // ---- COBOL: IF ACCT-CURR-BAL <= ZEROS (L197-L206) --------------
        // 'You have nothing to pay...' short-circuit — the COBOL sets
        // ERR-FLG-ON but the agent_prompt §1.5.1 directs this to be
        // returned as an informational DTO (not thrown) because the
        // operator's screen displayed a message and re-rendered the
        // map. The Java target preserves that semantic by returning a
        // DTO with currentBalance populated and transactionId null.
        if (currentBalance.signum() <= 0) {
            LOG.info("Nothing to pay short-circuit acctId={} balance={} message={}",
                    String.format("%011d", acctId), currentBalance,
                    "You have nothing to pay...");
            return nothingToPayDto(acctId, currentBalance);
        }

        // ---- COBOL: READ-CXACAIX-FILE (L211) ---------------------------
        // Resolves the cardholder's primary card via the CXACAIX
        // alternate index. NONUNIQUEKEY semantics preserved: the COBOL
        // source has a 1:1 mapping in CARDDEMO data so taking the first
        // row is safe. An empty result means no card is linked to the
        // account → RecordNotFoundException (FILE STATUS 23).
        List<CardCrossReference> xrefs =
                cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw new RecordNotFoundException(
                    "CardCrossReference not found: No card for acctId=" + acctId);
        }
        CardCrossReference xref = xrefs.get(0);
        final String cardNum = xref.getXrefCardNum();
        final String cardLast4 = lastFourOfPan(cardNum);
        LOG.debug("XREF resolved acctId={} cardLast4={}",
                String.format("%011d", acctId), cardLast4);

        // ---- COBOL: MAX-TRAN-ID + 1 (L212-L217) ------------------------
        // STARTBR/READPREV/ENDBR HIGH-VALUES sequence is replaced by a
        // descending-ordered top-1 query on the transactions table. An
        // empty journal seeds with "0000000000000000" so the first
        // generated ID becomes "0000000000000001" (matches the COBOL
        // semantic that ADD 1 TO ZEROS = 1 zero-padded to 16 digits).
        final String newTranId = nextTransactionId();
        LOG.debug("Next tranId generated acctId={} newTranId={}",
                String.format("%011d", acctId), newTranId);

        // ---- COBOL: INITIALIZE TRAN-RECORD + MOVE * literals -----------
        // (L218-L232) - verbatim literal constants per Minimal Change.
        // GET-CURRENT-TIMESTAMP (L230, L249-L267) maps to
        // LocalDateTime.now() per AAP §0.6.3.
        final LocalDateTime now = LocalDateTime.now();
        // COBOL: MOVE ACCT-CURR-BAL TO TRAN-AMT (L224). Setting scale=2
        // with HALF_EVEN preserves the COBOL PIC 9 decimal-arithmetic
        // semantic at the boundary (AAP §0.6.1).
        final BigDecimal tranAmt = currentBalance.setScale(2, RoundingMode.HALF_EVEN);

        // ON SIZE ERROR pre-check (Issue CP4-#6): when ACCT-CURR-BAL has
        // an absolute value greater than the narrower TRAN-AMT field's
        // ceiling (PIC S9(09)V99 = ±999,999,999.99), the implicit
        // truncation in the source COBOL would have raised the COMPUTE
        // ON SIZE ERROR condition (COBIL00C L224 implicit truncation,
        // L234 explicit ON SIZE ERROR). In the Java target, the
        // narrower NUMERIC(11,2) column would reject the INSERT with a
        // DataIntegrityViolationException and surface as HTTP 500. We
        // throw a typed OnSizeErrorException here so the failure is a
        // business-logic 422 Unprocessable Entity per AAP §0.7.1
        // ("Map COBOL RETURN-CODE / condition codes to Spring exception
        // hierarchy").
        if (tranAmt.abs().compareTo(MAX_TRAN_AMT) > 0) {
            throw new OnSizeErrorException(
                    "TRAN_AMT_OVERFLOW",
                    "Account balance " + tranAmt
                            + " exceeds TRAN-AMT PIC S9(09)V99 ceiling "
                            + "(999,999,999.99); cannot copy to TRAN-AMT for bill "
                            + "payment. COBOL: COBIL00C L224 MOVE ACCT-CURR-BAL TO "
                            + "TRAN-AMT would have raised ON SIZE ERROR.");
        }
        Transaction tx = new Transaction(
                newTranId,
                BILL_PAY_TYPE_CODE,            // COBOL: '02'
                BILL_PAY_CAT_CODE,             // COBOL: 2 (Integer)
                BILL_PAY_SOURCE,               // COBOL: 'POS TERM'
                BILL_PAY_DESC,                 // COBOL: 'BILL PAYMENT - ONLINE'
                tranAmt,                       // COBOL: MOVE ACCT-CURR-BAL TO TRAN-AMT
                BILL_PAY_MERCHANT_ID,          // COBOL: 999999999
                BILL_PAY_MERCHANT_NAME,        // COBOL: 'BILL PAYMENT'
                BILL_PAY_NA,                   // COBOL: 'N/A'
                BILL_PAY_NA,                   // COBOL: 'N/A'
                cardNum,                       // COBOL: MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
                now,                           // COBOL: WS-TIMESTAMP → TRAN-ORIG-TS
                now);                          // COBOL: WS-TIMESTAMP → TRAN-PROC-TS

        // ---- COBOL: WRITE-TRANSACT-FILE (L233) -------------------------
        // EXEC CICS WRITE DATASET('TRANSACT') → save() within the
        // @Transactional boundary.
        Transaction savedTran = transactionRepository.save(tx);

        // ---- COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT ---
        // (L234) — explicit ON SIZE ERROR guard. The BigDecimal subtract
        // never throws ArithmeticException on a finite-precision result,
        // but we defensively wrap any such occurrence AND check the
        // result against the column-precision ceiling per AAP §0.7.1.
        final BigDecimal newBalance;
        try {
            newBalance = currentBalance.subtract(tranAmt)
                    .setScale(2, RoundingMode.HALF_EVEN);
        } catch (ArithmeticException ae) {
            throw new OnSizeErrorException(
                    "ON SIZE ERROR subtracting bill payment", ae);
        }
        if (newBalance.abs().compareTo(MAX_ACCOUNT_BALANCE) > 0) {
            throw new OnSizeErrorException(
                    "BALANCE_OVERFLOW",
                    "Post-payment balance exceeds PIC S9(10)V99 ceiling");
        }
        account.setAcctCurrBal(newBalance);

        // ---- COBOL: UPDATE-ACCTDAT-FILE (L235) -------------------------
        // EXEC CICS REWRITE DATASET('ACCTDAT') → save() within the
        // @Transactional boundary so the TRANSACT insert + ACCTDAT
        // update commit as one logical unit of work (AAP §0.6.2).
        Account savedAccount = accountRepository.save(account);

        // ---- Cache-aside invalidation (post-write) ---------------------
        // Per AAP §0.7.1 cache-aside discipline: write paths invalidate
        // cached reads so subsequent AccountViewService calls re-populate
        // from the authoritative database state. The eviction is
        // best-effort by design — the CacheService.evict adapter swallows
        // exceptions internally so a Redis outage cannot abort the
        // @Transactional commit of the balance change.
        final String cacheKey = String.format("%011d", savedAccount.getAcctId());
        cacheService.evict(CACHE_NS_ACCOUNT, cacheKey);

        // ---- MSK event publication (per AAP §0.6.5 — partition by acct) ----
        // Both topics are partitioned by account ID via the Long acctId
        // overload of the publisher (the adapter formats the key as a
        // zero-padded 11-digit string internally to match the COBOL
        // ACCT-ID PIC 9(11) layout). The publishes are @Async so they
        // never block the @Transactional commit; failures are logged
        // inside the adapter without rolling back the database state.
        TransactionAddDto txEvent = new TransactionAddDto(
                String.format("%011d", savedAccount.getAcctId()),  // accountId 11-digit String
                savedTran.getTranCardNum(),                         // cardNumber
                savedTran.getTranTypeCd(),                          // transactionType "02"
                savedTran.getTranCatCd(),                           // transactionCategory Integer 2
                savedTran.getTranSource(),                          // source "POS TERM"
                savedTran.getTranDesc(),                            // description
                savedTran.getTranAmt(),                             // amount BigDecimal
                savedTran.getTranOrigTs(),                          // originationTimestamp
                savedTran.getTranProcTs(),                          // processingTimestamp
                savedTran.getTranMerchantId(),                      // merchantId 999999999
                savedTran.getTranMerchantName(),                    // merchantName
                savedTran.getTranMerchantCity(),                    // merchantCity "N/A"
                savedTran.getTranMerchantZip(),                     // merchantZip "N/A"
                "Y");                                               // confirm "Y" (server-confirmed)
        kafkaEventPublisher.publishTransactionPosted(
                savedAccount.getAcctId(), txEvent);

        // Build a minimal AccountUpdateDto carrying the account-side
        // state only — customer-derived fields are omitted (null)
        // because the schema does not authorize a CustomerRepository
        // dependency in this service. Downstream consumers of
        // account.updated must treat null customer fields as "not
        // present in this event" (the canonical source of customer
        // state remains the Customer aggregate).
        AccountUpdateDto acctEvent = buildAccountUpdateEvent(savedAccount);
        kafkaEventPublisher.publishAccountUpdated(
                savedAccount.getAcctId(), acctEvent);

        // ---- Immutable audit emission (CloudTrail + OpenSearch) --------
        // Per AAP §0.7.1: "Audit trail content — transaction IDs,
        // timestamps, operator codes — must continue to be emitted with
        // the same values and semantics, now written to CloudTrail +
        // OpenSearch". PII discipline (§0.6.6): only acctId + last4 of
        // card may appear in the audit payload; full PAN is NEVER
        // logged.
        Map<String, Object> auditPayload = Map.of(
                "amount", tranAmt,
                "cardLast4", cardLast4);
        auditLogService.logTransactionEvent(
                savedTran.getTranId(),                          // transactionId
                savedAccount.getAcctId(),                       // accountId
                AUDIT_OPERATOR_SYSTEM,                          // operatorCode
                AUDIT_EVENT_BILL_PAID,                          // eventType
                null,                                           // reasonCode
                auditPayload,                                   // payload
                null);                                          // correlationId

        // ---- Structured success log (PII-safe) ------------------------
        // Operational visibility for the success path. acctId is
        // zero-padded to 11 digits to match the COBOL ACCT-ID PIC 9(11)
        // layout; the card number is masked to last4 per AAP §0.6.6.
        LOG.info("Bill payment posted tranId={} acctId={} cardLast4={} amountPaid={} newBalance={}",
                savedTran.getTranId(),
                String.format("%011d", savedAccount.getAcctId()),
                cardLast4,
                tranAmt,
                newBalance);

        // ---- Build success response DTO -------------------------------
        // accountId (echoed), currentBalance (new — zero after full
        // payment), confirm ("Y"), transactionId (16-digit), postedAt
        // (LocalDateTime), amountPaid (BigDecimal).
        return new BillPaymentDto(
                String.format("%011d", savedAccount.getAcctId()),
                newBalance,
                "Y",
                savedTran.getTranId(),
                savedTran.getTranProcTs(),
                tranAmt);
    }

    // =========================================================================
    // Private helpers — validation, MAX-TRAN-ID generation, DTO builders
    // =========================================================================

    /**
     * Validates the account ID on the inbound DTO and converts it to a
     * {@link Long}. Replicates the COBOL
     * {@code EVALUATE TRUE WHEN ACTIDINI = SPACES OR LOW-VALUES} gate
     * at {@code COBIL00C.cbl} L159-L164.
     *
     * <p>The verbatim COBOL message
     * {@code "Acct ID can NOT be empty..."} is preserved exactly per
     * AAP &sect;0.7.1 Minimal Change Clause &mdash; on any failure
     * (null, blank, non-numeric, non-positive) a
     * {@link ValidationException} carrying that exact string is
     * thrown.</p>
     *
     * @param request the inbound bill-payment DTO
     * @return the parsed {@code Long} account ID (always &gt; 0 on
     *         success)
     * @throws ValidationException if the account ID is null, blank,
     *                             non-numeric, or non-positive
     */
    // COBOL: COBIL00C:PROCESS-ENTER-KEY (L159-L164)
    private Long validateAccountId(BillPaymentDto request) {
        final String raw = request.accountId();
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("Acct ID can NOT be empty...");
        }
        // Strict numeric check — COBOL PIC 9(11) accepts ONLY decimal
        // digits. Non-digit characters (spaces, letters, hyphens) and
        // leading signs all fail the COBOL EVALUATE for SPACES /
        // LOW-VALUES → "empty" condition.
        final String trimmed = raw.trim();
        if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
            throw new ValidationException("Acct ID can NOT be empty...");
        }
        final long acctId;
        try {
            acctId = Long.parseLong(trimmed);
        } catch (NumberFormatException nfe) {
            throw new ValidationException("Acct ID can NOT be empty...");
        }
        if (acctId <= 0L) {
            // COBOL PIC 9(11) is conceptually unsigned; an effective
            // value of zero is treated as missing (per the COBOL
            // SPACES / LOW-VALUES check semantics).
            throw new ValidationException("Acct ID can NOT be empty...");
        }
        return acctId;
    }

    /**
     * Returns {@code true} when the supplied confirm flag indicates
     * affirmative payment confirmation. Mirrors the COBOL
     * {@code WHEN 'Y' / 'y'} branch of {@code EVALUATE CONFIRMI}
     * (L174-L177).
     */
    private static boolean isAffirmativeConfirm(String confirm) {
        return "Y".equals(confirm) || "y".equals(confirm);
    }

    /**
     * Returns {@code true} when the supplied confirm flag indicates
     * cancellation. Mirrors the COBOL {@code WHEN 'N' / 'n'} branch
     * of {@code EVALUATE CONFIRMI} (L178-L181).
     */
    private static boolean isCancellation(String confirm) {
        return "N".equals(confirm) || "n".equals(confirm);
    }

    /**
     * Returns {@code true} when the supplied confirm flag is null,
     * empty, or all whitespace. Mirrors the COBOL
     * {@code WHEN SPACES / LOW-VALUES} branch of
     * {@code EVALUATE CONFIRMI} (L182-L184) where the program reads
     * the account for display without writing.
     */
    private static boolean isBlankConfirm(String confirm) {
        return confirm == null || confirm.isBlank();
    }

    /**
     * Generates the next 16-digit zero-padded transaction ID by
     * reading the highest existing {@code tranId} from
     * {@link TransactionRepository#findTopByOrderByTranIdDesc()} and
     * adding 1. Implements the MAX-TRAN-ID + 1 idiom (COBOL
     * {@code STARTBR}/{@code READPREV}/{@code ENDBR} HIGH-VALUES
     * sequence) per AAP &sect;0.6.2.
     *
     * <p>An empty journal seeds with {@link #SEED_TRAN_ID} so the
     * first generated ID becomes {@code "0000000000000001"}. A
     * non-numeric existing ID (defensive guard against data-quality
     * issues) re-seeds from zero with a warning log.</p>
     *
     * <p><b>BUG #6 fix (QA CP5):</b> The lookup is scoped to
     * <em>16-digit all-numeric</em> {@code tran_id} values only via
     * {@link TransactionRepository#findTopByNumericTranIdOrderByTranIdDesc()}.
     * The previous implementation used the unfiltered
     * {@link TransactionRepository#findTopByOrderByTranIdDesc()},
     * which could return an alphanumeric batch ID (e.g.
     * {@code "HAPPY00000000099"}) because {@code 'H' > '9'} under
     * lexical sort. That caused {@code new BigDecimal(maxId)} to throw,
     * the catch block to reseed to {@code 0}, and this method to emit
     * {@code "0000000000000001"} &mdash; colliding with any other
     * reseed caller (notably
     * {@code TransactionAddService.nextTransactionId()}) and causing
     * JPA {@code save()} to silently UPDATE the prior record on the
     * duplicate primary key (confirmed data loss in QA CP5 regression
     * tests). Filtering at the SQL layer to
     * {@code tran_id ~ '^[0-9]{16}$'} guarantees {@code maxId} is
     * always parseable.</p>
     *
     * @return the new 16-digit zero-padded transaction ID
     * @throws OnSizeErrorException if the 16-digit ID space has been
     *                              exhausted (i.e., MAX-TRAN-ID + 1
     *                              would overflow PIC 9(16))
     */
    // COBOL: COBIL00C:PROCESS-ENTER-KEY (L212-L217)
    private String nextTransactionId() {
        // BUG #6 fix: filter to all-numeric 16-digit tran_ids only so
        // the CP5 batch's alphanumeric DALYTRAN-IDs cannot poison this
        // online sequence (AAP §0.7.2 financial-data-integrity rule).
        // findTopByNumericTranIdOrderByTranIdDesc() returns
        // Optional<Transaction> — we extract the tranId field (the
        // COBOL TRAN-ID PIC 9(16)) before incrementing. An empty
        // numeric-id journal (no numeric rows yet) is treated as
        // "0000000000000000" so ADD 1 yields the canonical first ID.
        final String maxId = transactionRepository
                .findTopByNumericTranIdOrderByTranIdDesc()
                .map(Transaction::getTranId)
                .orElse(SEED_TRAN_ID);

        BigDecimal numeric;
        try {
            numeric = new BigDecimal(maxId.trim());
        } catch (NumberFormatException nfe) {
            // Defensive: the regex filter `^[0-9]{16}$` already
            // guarantees an all-digit string of length 16, so this
            // branch should be unreachable. Kept as a safety net in
            // case future fixtures introduce e.g. unicode-digit
            // characters that match POSIX regex but fail Java's
            // BigDecimal numeric parser. Log and re-seed rather than
            // fail to preserve the existing public contract.
            LOG.warn("Non-numeric tranId encountered after numeric "
                    + "filter; re-seeding from zero ({})", maxId);
            numeric = BigDecimal.ZERO;
        }
        final BigDecimal next = numeric.add(BigDecimal.ONE);
        if (next.compareTo(MAX_TRAN_ID) > 0) {
            // Replicates the COBOL ON SIZE ERROR semantic on the
            // ADD 1 TO WS-TRAN-ID-NUM statement (AAP §0.7.1).
            throw new OnSizeErrorException(
                    "TRAN_ID_OVERFLOW",
                    "Transaction ID space exhausted (max 9999999999999999)");
        }
        return String.format("%016d", next.longValueExact());
    }

    /**
     * Builds the {@link AccountUpdateDto} event payload published to
     * the {@code account.updated} MSK topic after a successful bill
     * payment. Carries account-side fields verbatim from the just-
     * persisted {@link Account} aggregate; customer-side fields are
     * omitted (null) because this service is not authorized to load
     * the {@link com.awsm2.carddemo.domain.Customer} aggregate per
     * the file schema {@code depends_on_files} contract.
     *
     * <p>Consumers of {@code account.updated} treat null
     * customer-derived fields as "not present in this event" &mdash;
     * the authoritative source for customer state is the Customer
     * aggregate, not the bill-payment event stream.</p>
     *
     * @param a the just-persisted {@link Account} carrying the new
     *          balance (zero after full payment) and the
     *          {@code @Version}-tracked optimistic-lock token
     * @return a populated {@link AccountUpdateDto} ready for MSK
     *         publication
     */
    // Replaces: COBIL00C UPDATE-ACCTDAT-FILE downstream consumers
    private static AccountUpdateDto buildAccountUpdateEvent(Account a) {
        return new AccountUpdateDto(
                a.getAcctId(),                  // accountId (Long)
                a.getAcctActiveStatus(),        // activeStatus
                a.getAcctCurrBal(),             // currentBalance (newBal)
                a.getAcctCreditLimit(),         // creditLimit
                a.getAcctCashCreditLimit(),     // cashCreditLimit
                a.getAcctOpenDate(),            // openDate
                a.getAcctExpirationDate(),      // expirationDate
                a.getAcctReissueDate(),         // reissueDate
                a.getAcctCurrCycCredit(),       // currentCycleCredit
                a.getAcctCurrCycDebit(),        // currentCycleDebit
                a.getAcctAddrZip(),             // addressZip
                a.getAcctGroupId(),             // accountGroupId
                // Customer fields are null in this service per file
                // schema (no CustomerRepository dependency).
                null,                           // customerId
                null,                           // firstName
                null,                           // middleName
                null,                           // lastName
                null,                           // customerSsn
                null,                           // phoneNumber1
                null,                           // phoneNumber2
                null,                           // addressLine1
                null,                           // addressLine2
                null,                           // addressLine3
                null,                           // stateCode
                null,                           // countryCode
                null,                           // zipCode
                null,                           // dateOfBirth
                null,                           // governmentIssuedId
                null,                           // eftAccountId
                null,                           // primaryCardHolderIndicator
                null,                           // ficoCreditScore
                a.getVersion());                // version (optimistic lock)
    }

    /**
     * Builds the informational response DTO for the
     * "blank confirm — preview" path
     * (COBIL00C {@code WHEN SPACES / LOW-VALUES}, L182-L184).
     * The current balance is populated so the caller can see the
     * balance, but no transaction is posted; the caller is expected
     * to send a follow-up request with {@code confirm = "Y"} to
     * complete the payment.
     */
    private static BillPaymentDto previewDto(Long acctId, BigDecimal currentBalance, String confirm) {
        return new BillPaymentDto(
                String.format("%011d", acctId),
                currentBalance,
                confirm,           // echo the (blank) confirm flag back
                null,              // transactionId — not posted
                null,              // postedAt — not posted
                null);             // amountPaid — not posted
    }

    /**
     * Builds the informational response DTO for the cancellation
     * path (COBIL00C {@code WHEN 'N' / 'n'}, L178-L181). Echoes the
     * cancellation flag back to the caller; no balance is shown
     * because the COBOL CLEAR-CURRENT-SCREEN paragraph wipes the
     * displayed balance, and no transaction is posted.
     */
    private static BillPaymentDto cancellationDto(Long acctId, String confirm) {
        return new BillPaymentDto(
                String.format("%011d", acctId),
                null,              // currentBalance — not shown after cancel
                confirm,           // echo "N"/"n" back
                null,              // transactionId — not posted
                null,              // postedAt — not posted
                null);             // amountPaid — not posted
    }

    /**
     * Builds the informational response DTO for the
     * "balance &le; 0 — nothing to pay" short-circuit
     * (COBIL00C {@code IF ACCT-CURR-BAL &lt;= ZEROS}, L198-L206).
     * Per the agent_prompt schema description this is returned (not
     * thrown) because the COBOL handled it as a screen message.
     */
    private static BillPaymentDto nothingToPayDto(Long acctId, BigDecimal currentBalance) {
        return new BillPaymentDto(
                String.format("%011d", acctId),
                currentBalance,    // 0 or negative — caller sees the balance
                null,              // confirm cleared — caller may not re-submit Y
                null,              // transactionId — not posted
                null,              // postedAt — not posted
                null);             // amountPaid — not posted
    }

    /**
     * Returns the last four characters of a 16-digit PAN for
     * PII-safe logging per AAP &sect;0.6.6 / &sect;0.7.1. Defensive
     * against short or null inputs &mdash; never echoes the full
     * card number anywhere.
     */
    private static String lastFourOfPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "----";
        }
        return pan.substring(pan.length() - 4);
    }

    /**
     * Returns a safe (non-null) {@link BigDecimal} for the account
     * balance, defaulting to {@link BigDecimal#ZERO} if the source
     * column was {@code NULL}. Defensive guard against partially
     * populated test fixtures &mdash; in production data the column
     * is {@code NOT NULL}.
     */
    private static BigDecimal safeBalance(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
