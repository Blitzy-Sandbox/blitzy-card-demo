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
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Daily transaction-posting batch service &mdash; the Java target for the
 * COBOL batch program {@code app/cbl/CBTRN02C.cbl}, with overlapping
 * preliminary-read semantics from {@code app/cbl/CBTRN01C.cbl}
 * subsumed (the standalone {@code CBTRN01C} "dump" semantics now flow
 * through this service's repository iteration). The companion COBOL
 * report variant {@code app/cbl/CBTRN03C.cbl} is intentionally handled
 * by a separate {@code TransactionReportService} per AAP &sect;0.4.1
 * (this service owns the <em>posting</em> half of the cascade only).
 *
 * <p>The service implements the <b>4-stage validation cascade</b> and
 * the post-success update sequence the COBOL source performs for each
 * record in the {@code DALYTRAN} staging file. Records that fail
 * validation are written to the {@code DALYREJS} rejects file (now
 * the S3 prefix {@code dalyrejs/}) with a verbatim reject reason code
 * (100&ndash;109) and reason-description trailer (paragraph
 * {@code 2500-WRITE-REJECT-REC}). The job's UNIX/JES return code is
 * {@code 4} when any rejects exist and {@code 0} otherwise
 * (paragraph {@code MAIN-PARA} L228-L231).</p>
 *
 * <h2>COBOL reject codes (preserved verbatim per AAP &sect;0.7.2)</h2>
 *
 * <table>
 *   <caption>CBTRN02C.cbl reject codes</caption>
 *   <tr><th>Reject code</th><th>COBOL paragraph (line)</th>
 *       <th>Reject description (verbatim)</th></tr>
 *   <tr><td>{@code 100}</td><td>{@code 1500-A-LOOKUP-XREF} (L385)</td>
 *       <td>{@code INVALID CARD NUMBER FOUND}</td></tr>
 *   <tr><td>{@code 101}</td><td>{@code 1500-B-LOOKUP-ACCT} (L397)</td>
 *       <td>{@code ACCOUNT RECORD NOT FOUND}</td></tr>
 *   <tr><td>{@code 102}</td><td>{@code 1500-B-LOOKUP-ACCT} (L410)</td>
 *       <td>{@code OVERLIMIT TRANSACTION}</td></tr>
 *   <tr><td>{@code 103}</td><td>{@code 1500-B-LOOKUP-ACCT} (L417)</td>
 *       <td>{@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}</td></tr>
 *   <tr><td>{@code 109}</td><td>{@code 2800-UPDATE-ACCOUNT-REC}</td>
 *       <td>{@code ACCOUNT RECORD NOT FOUND} (REWRITE INVALID KEY at
 *       post-time &rarr; mapped to JPA
 *       {@link OptimisticLockingFailureException})</td></tr>
 * </table>
 *
 * <h2>COBOL paragraph translation</h2>
 *
 * <table>
 *   <caption>CBTRN02C.cbl &harr; TransactionPostingService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L194-L234)</td>
 *       <td>{@link #postDailyTransactions(LocalDate)}</td></tr>
 *   <tr><td>{@code 1500-VALIDATE-TRAN} (L370-L378)</td>
 *       <td>{@link #validateTransaction(DailyTransaction)}</td></tr>
 *   <tr><td>{@code 1500-A-LOOKUP-XREF} (L380-L392)</td>
 *       <td>Stage 1 inside
 *       {@link #validateTransaction(DailyTransaction)} &mdash; reject
 *       100</td></tr>
 *   <tr><td>{@code 1500-B-LOOKUP-ACCT} (L393-L421)</td>
 *       <td>Stages 2-4 inside
 *       {@link #validateTransaction(DailyTransaction)} &mdash;
 *       rejects 101 (account not found), 102 (overlimit), 103
 *       (expired)</td></tr>
 *   <tr><td>{@code 2000-POST-TRANSACTION} (L424-L444)</td>
 *       <td>{@link #postTransaction(DailyTransaction, ValidationResult)}
 *       (orchestrates 2700/2800/2900 then emits MSK and audit events)
 *       </td></tr>
 *   <tr><td>{@code 2500-WRITE-REJECT-REC} (L446-L465)</td>
 *       <td>{@link #writeRejectRecord(DailyTransaction, ValidationResult,
 *       LocalDate)} &mdash; calls
 *       {@link S3OutputService#writeRejection(String, String)}</td></tr>
 *   <tr><td>{@code 2700-UPDATE-TCATBAL} +
 *       {@code 2700-A-CREATE-TCATBAL-REC} +
 *       {@code 2700-B-UPDATE-TCATBAL-REC} (L467-L539)</td>
 *       <td>{@link #updateTcatbal(DailyTransaction,
 *       CardCrossReference)}</td></tr>
 *   <tr><td>{@code 2800-UPDATE-ACCOUNT-REC} (L545-L560) &mdash;
 *       reject 109 on REWRITE INVALID KEY</td>
 *       <td>{@link #updateAccount(DailyTransaction, Account)}</td></tr>
 *   <tr><td>{@code 2900-WRITE-TRANSACTION-FILE} (L562-L578)</td>
 *       <td>{@link TransactionRepository#save(Object)} via
 *       {@link #postTransaction(DailyTransaction, ValidationResult)}</td></tr>
 * </table>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL programs:</b>
 *       {@code app/cbl/CBTRN02C.cbl} (primary &mdash; posting engine),
 *       {@code app/cbl/CBTRN01C.cbl} (preliminary DALYTRAN reader,
 *       subsumed here), and {@code app/cbl/CBTRN03C.cbl} (report
 *       variant, handled by {@code TransactionReportService}).</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CVTRA06Y.cpy}
 *       ({@code DALYTRAN-RECORD}; {@link DailyTransaction}),
 *       {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}; {@link CardCrossReference}),
 *       {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD}; {@link Account}),
 *       {@code app/cpy/CVTRA01Y.cpy}
 *       ({@code TRAN-CAT-BAL-RECORD};
 *       {@link TransactionCategoryBalance}),
 *       {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}; {@link Transaction}).</li>
 *   <li><b>JCL:</b> invoked by {@code app/jcl/POSTTRAN.jcl} STEP15
 *       (PGM=CBTRN02C) &mdash; replaced operationally by the
 *       {@code DailyTransactionPostingJob} Spring Batch tasklet
 *       inside an AWS Batch job definition wired from the EOD
 *       Step Functions state machine
 *       ({@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}).</li>
 * </ul>
 *
 * <h2>Implementation rules (AAP &sect;0.7.1 Refactor Discipline)</h2>
 * <ul>
 *   <li><b>Reject codes preserved verbatim:</b> Every reject code
 *       (100, 101, 102, 103, 109) and its description matches the
 *       COBOL source byte-for-byte. Downstream consumers of the
 *       DALYREJS S3 object receive identical reject reason codes.</li>
 *   <li><b>Return code semantics:</b> {@link PostingResult#returnCode()}
 *       mirrors COBOL {@code RETURN-CODE}: {@code 4} when any
 *       rejects exist, {@code 0} otherwise. AWS Batch maps this to
 *       its own job exit code so Step Functions conditional branches
 *       behave identically to JCL {@code COND=(4,LT)}.</li>
 *   <li><b>4-stage cascade short-circuit:</b> The COBOL source
 *       short-circuits at the first failure (paragraph
 *       {@code 1500-VALIDATE-TRAN} only calls
 *       {@code 1500-B-LOOKUP-ACCT} when
 *       {@code WS-VALIDATION-FAIL-REASON = 0}). The Java port
 *       preserves this with early {@code return} on first reject.</li>
 *   <li><b>BigDecimal arithmetic:</b> All monetary arithmetic uses
 *       {@link BigDecimal} with {@link RoundingMode#HALF_EVEN}
 *       (banker's rounding) and explicit
 *       {@code .setScale(2, RoundingMode.HALF_EVEN)} per AAP
 *       &sect;0.6.1 and &sect;0.7.1. <b>No</b> {@code float} or
 *       {@code double} appears anywhere in this service.</li>
 *   <li><b>{@code ON SIZE ERROR}:</b> Every {@link BigDecimal}
 *       operation is wrapped in a {@code try}/{@code catch} that
 *       converts {@link ArithmeticException} to
 *       {@link OnSizeErrorException} (per AAP &sect;0.7.1 rule 13).</li>
 *   <li><b>Per-record transaction boundary:</b>
 *       {@link #postTransaction(DailyTransaction, ValidationResult)}
 *       is annotated {@code @Transactional(rollbackFor =
 *       Exception.class, isolation = READ_COMMITTED, propagation =
 *       REQUIRES_NEW)} so each posted record gets its own commit
 *       boundary &mdash; mirroring COBOL per-record commit semantics
 *       where any mid-record I/O error caused a 9999-ABEND-PROGRAM
 *       (per AAP &sect;0.7.1 rule 8).</li>
 *   <li><b>No direct AWS SDK calls:</b> S3 writes go through
 *       {@link S3OutputService}; Kafka publishes through
 *       {@link KafkaEventPublisher}; OpenSearch/CloudTrail audits
 *       through {@link AuditLogService} (per AAP &sect;0.7.1
 *       "Isolate all AWS service integrations").</li>
 *   <li><b>PII discipline:</b> Log statements never include the
 *       full card number or the full amount in info-level output
 *       (per AAP rule 10). Card numbers reach OpenSearch via the
 *       {@link AuditLogService} adapter which performs masking.</li>
 *   <li><b>Optimistic-locking failures &rarr; reject 109:</b> JPA
 *       {@link OptimisticLockingFailureException} at the
 *       {@code accountRepository.save} boundary is wrapped as a
 *       {@link CardDemoException} carrying reason code
 *       {@code "ACCOUNT_REWRITE_FAILED"} (mapped to COBOL reject
 *       code 109 semantics &mdash; "ACCOUNT RECORD NOT FOUND" on
 *       REWRITE INVALID KEY).</li>
 * </ul>
 */
@Service
public class TransactionPostingService {

    private static final Logger LOG =
            LoggerFactory.getLogger(TransactionPostingService.class);

    // -------------------------------------------------------------------------
    // Verbatim COBOL reject codes (preserved per AAP §0.7.2)
    // -------------------------------------------------------------------------

    /**
     * COBOL: {@code 1500-A-LOOKUP-XREF} (L385) reject code &mdash;
     * INVALID CARD NUMBER FOUND (file status '23' on XREF READ).
     */
    static final int REJECT_INVALID_CARD = 100;

    /**
     * COBOL: {@code 1500-B-LOOKUP-ACCT} (L397) reject code &mdash;
     * ACCOUNT RECORD NOT FOUND (file status '23' on ACCT READ).
     */
    static final int REJECT_ACCOUNT_NOT_FOUND = 101;

    /**
     * COBOL: {@code 1500-B-LOOKUP-ACCT} (L410) reject code &mdash;
     * OVERLIMIT TRANSACTION (post-debit balance exceeds
     * ACCT-CREDIT-LIMIT).
     */
    static final int REJECT_OVERLIMIT = 102;

    /**
     * COBOL: {@code 1500-B-LOOKUP-ACCT} (L417) reject code &mdash;
     * TRANSACTION RECEIVED AFTER ACCT EXPIRATION
     * (ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS (1:10)).
     */
    static final int REJECT_EXPIRED = 103;

    /**
     * COBOL: {@code 2800-UPDATE-ACCOUNT-REC} reject code &mdash;
     * ACCOUNT RECORD NOT FOUND on REWRITE INVALID KEY. In the Java
     * target this is the {@link OptimisticLockingFailureException}
     * path at {@code accountRepository.save}.
     */
    static final int REJECT_ACCOUNT_REWRITE_FAILED = 109;

    /** Verbatim COBOL reject description for reject code 100. */
    static final String DESC_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /** Verbatim COBOL reject description for reject code 101 (and 109). */
    static final String DESC_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** Verbatim COBOL reject description for reject code 102. */
    static final String DESC_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /** Verbatim COBOL reject description for reject code 103. */
    static final String DESC_EXPIRED =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * COBOL {@code MOVE 4 TO RETURN-CODE} (L230). When any rejection
     * occurs the job exits with this code; downstream JCL/Step
     * Functions conditional branches use this to detect partial
     * failures (per AAP &sect;0.7.2 "Error codes and condition
     * handling surfaced to downstream consumers must be preserved
     * verbatim").
     */
    static final int RETURN_CODE_WITH_REJECTS = 4;

    /**
     * Inclusive upper bound for any monetary {@link BigDecimal} field
     * after arithmetic &mdash; explicit overflow guard per AAP
     * &sect;0.6.1 ("each arithmetic operation is wrapped in a check
     * against the configured precision"). The value
     * {@code 99,999,999,999.99} matches the widest signed COBOL
     * monetary {@code PIC S9(11)V99} present in the CardDemo record
     * layouts and is therefore a conservative ceiling for every
     * field-level COMPUTE/ADD/SUBTRACT result. Any post-arithmetic
     * value whose absolute magnitude exceeds this bound triggers an
     * {@link OnSizeErrorException} that mirrors the COBOL
     * {@code ON SIZE ERROR} clause semantics.
     */
    static final BigDecimal MAX_AMOUNT =
            new BigDecimal("99999999999.99");

    /** Audit event type for a successfully posted transaction. */
    static final String AUDIT_EVENT_POSTED = "transaction.posted";

    /** Audit event type for a rejected transaction. */
    static final String AUDIT_EVENT_REJECTED = "transaction.rejected";

    /**
     * Operator code used in audit records to identify a batch-emitted
     * event (vs. an online operator-emitted event). The COBOL source
     * used the program name in DISPLAY statements; the Java target
     * uses the symbolic {@code "BATCH"} identifier so OpenSearch
     * dashboards can filter on operator type.
     */
    static final String OPERATOR_BATCH = "BATCH";

    // -------------------------------------------------------------------------
    // Constructor injection (per AAP §0.7.1 rule 9 — NO @Autowired on fields,
    // final fields, single constructor)
    // -------------------------------------------------------------------------

    private final DailyTransactionRepository dailyTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final TransactionCategoryBalanceRepository
            transactionCategoryBalanceRepository;
    private final S3OutputService s3OutputService;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;

    /**
     * Sole constructor &mdash; receives all collaborators via
     * constructor injection per AAP &sect;0.7.1 rule 9 ("Constructor
     * injection only &mdash; final fields, single constructor").
     *
     * @param dailyTransactionRepository           Spring Data JPA
     *                                             repository for the
     *                                             {@code daily_transactions}
     *                                             staging table
     *                                             (replaces COBOL
     *                                             {@code DALYTRAN-FILE}
     *                                             sequential read)
     * @param transactionRepository                Spring Data JPA
     *                                             repository for the
     *                                             {@code transactions}
     *                                             journal (replaces
     *                                             COBOL
     *                                             {@code TRANSACT-FILE}
     *                                             RANDOM WRITE)
     * @param accountRepository                    Spring Data JPA
     *                                             repository for the
     *                                             {@code accounts}
     *                                             table (replaces
     *                                             COBOL
     *                                             {@code ACCOUNT-FILE}
     *                                             RANDOM READ/REWRITE)
     * @param cardCrossReferenceRepository         Spring Data JPA
     *                                             repository for
     *                                             {@code card_xref}
     *                                             (replaces COBOL
     *                                             {@code XREF-FILE}
     *                                             RANDOM READ)
     * @param transactionCategoryBalanceRepository Spring Data JPA
     *                                             repository for
     *                                             {@code tran_cat_bal}
     *                                             (replaces COBOL
     *                                             {@code TCATBAL-FILE}
     *                                             RANDOM READ/WRITE/REWRITE)
     * @param s3OutputService                      AWS SDK v2 adapter
     *                                             writing rejection
     *                                             records to the
     *                                             {@code dalyrejs/} S3
     *                                             prefix (replaces
     *                                             COBOL
     *                                             {@code DALYREJS-FILE}
     *                                             sequential WRITE)
     * @param kafkaEventPublisher                  MSK Kafka producer
     *                                             adapter emitting
     *                                             {@code transaction.posted}
     *                                             and
     *                                             {@code account.updated}
     *                                             events partitioned
     *                                             by account ID
     * @param auditLogService                      Audit log adapter
     *                                             emitting structured
     *                                             events to Amazon
     *                                             OpenSearch (and
     *                                             Amazon CloudTrail
     *                                             for cross-cutting
     *                                             AWS API events) per
     *                                             AAP &sect;0.6.6
     */
    public TransactionPostingService(
            DailyTransactionRepository dailyTransactionRepository,
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            TransactionCategoryBalanceRepository
                    transactionCategoryBalanceRepository,
            S3OutputService s3OutputService,
            KafkaEventPublisher kafkaEventPublisher,
            AuditLogService auditLogService) {
        this.dailyTransactionRepository = Objects.requireNonNull(
                dailyTransactionRepository,
                "dailyTransactionRepository must not be null");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository,
                "transactionRepository must not be null");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.s3OutputService = Objects.requireNonNull(s3OutputService,
                "s3OutputService must not be null");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    // =========================================================================
    // Public API — top-level entry point + exported records
    // =========================================================================

    /**
     * Summary returned to the caller (typically a Spring Batch
     * {@code Tasklet} step inside the {@code DailyTransactionPostingJob}
     * or an AWS Step Functions invocation via the
     * {@code StepFunctionsOrchestrator} adapter).
     *
     * <p>The {@code returnCode} field mirrors the COBOL
     * {@code RETURN-CODE} register exactly: {@code 4} when any
     * rejections occurred (COBOL: {@code IF WS-REJECT-COUNT > 0 MOVE
     * 4 TO RETURN-CODE} at L229-L230), otherwise {@code 0}. This
     * value is surfaced to AWS Batch as the container exit code so
     * downstream Step Functions conditional branches behave
     * identically to JCL {@code COND=} clauses.</p>
     *
     * @param transactionCount total number of DALYTRAN records
     *                         processed (COBOL
     *                         {@code WS-TRANSACTION-COUNT})
     * @param rejectCount      number of records that failed validation
     *                         (COBOL {@code WS-REJECT-COUNT})
     * @param returnCode       COBOL-equivalent {@code RETURN-CODE}
     *                         (0 or 4)
     */
    public record PostingResult(int transactionCount,
                                int rejectCount,
                                int returnCode) {
    }

    /**
     * Validation outcome carrier returned by
     * {@link #validateTransaction(DailyTransaction)}. On success
     * (reasonCode == 0) the {@code xref} and {@code account} fields
     * are populated with the looked-up entities so the post-success
     * paragraphs (2700/2800/2900) can use them without redundant
     * repository round-trips. On failure (reasonCode 100&ndash;103)
     * the {@code xref} and {@code account} fields are {@code null}
     * and the {@code reasonDescription} holds the verbatim COBOL
     * reject description for downstream emission to DALYREJS.
     *
     * @param reasonCode        validation result code: {@code 0} for
     *                          success, 100&ndash;103 for COBOL
     *                          reject codes
     * @param reasonDescription verbatim COBOL reject description
     *                          (empty string on success)
     * @param xref              looked-up {@link CardCrossReference} on
     *                          success of stage 1, {@code null}
     *                          otherwise
     * @param account           looked-up {@link Account} on success of
     *                          stage 2, {@code null} otherwise
     */
    public record ValidationResult(int reasonCode,
                                   String reasonDescription,
                                   CardCrossReference xref,
                                   Account account) {
        /**
         * Factory method returning a successful validation result
         * carrying both looked-up entities. Equivalent to COBOL
         * {@code WS-VALIDATION-FAIL-REASON = 0} state (and
         * description is left blank per
         * {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC}
         * at L209).
         *
         * @param x the looked-up {@link CardCrossReference}
         * @param a the looked-up {@link Account}
         * @return a successful {@code ValidationResult}
         */
        public static ValidationResult ok(CardCrossReference x, Account a) {
            return new ValidationResult(0, "", x, a);
        }

        /**
         * Factory method returning a failed validation result with
         * the given verbatim COBOL reject code and description.
         * The {@code xref} and {@code account} fields are {@code
         * null} because the cascade has short-circuited and any
         * further values are undefined per the COBOL source.
         *
         * @param code the verbatim COBOL reject code
         *             (100&ndash;103)
         * @param desc the verbatim COBOL reject description
         * @return a failing {@code ValidationResult}
         */
        public static ValidationResult reject(int code, String desc) {
            return new ValidationResult(code, desc, null, null);
        }
    }

    /**
     * Top-level entry point invoked by the Spring Batch
     * {@code DailyTransactionPostingJob} tasklet or directly by the
     * {@code StepFunctionsOrchestrator} adapter. Translates the
     * {@code PROCEDURE DIVISION} main loop of {@code CBTRN02C.cbl}
     * (L194-L234) into a Java {@code for}-each over the
     * {@code DailyTransactionRepository} contents.
     *
     * <p>Per-record flow (verbatim from COBOL):</p>
     * <ol>
     *   <li>Read each {@link DailyTransaction} (COBOL: PERFORM
     *       UNTIL END-OF-FILE / READ NEXT loop).</li>
     *   <li>Increment {@code WS-TRANSACTION-COUNT}.</li>
     *   <li>Reset {@code WS-VALIDATION-FAIL-REASON} = 0 and call
     *       {@link #validateTransaction(DailyTransaction)} (COBOL:
     *       PERFORM 1500-VALIDATE-TRAN).</li>
     *   <li>If the validation passed: call
     *       {@link #postTransaction(DailyTransaction, ValidationResult)}
     *       (COBOL: PERFORM 2000-POST-TRANSACTION).</li>
     *   <li>Otherwise: increment {@code WS-REJECT-COUNT} and call
     *       {@link #writeRejectRecord(DailyTransaction,
     *       ValidationResult, LocalDate)} (COBOL: PERFORM
     *       2500-WRITE-REJECT-REC).</li>
     * </ol>
     *
     * <p>On completion logs {@code WS-TRANSACTION-COUNT} and
     * {@code WS-REJECT-COUNT} (COBOL L227-L228) and returns a
     * {@link PostingResult} whose {@link PostingResult#returnCode()}
     * is {@code 4} when any rejects occurred, {@code 0} otherwise
     * (COBOL L229-L230 {@code MOVE 4 TO RETURN-CODE}).</p>
     *
     * <p>Per-record posting is performed inside an isolated
     * {@code @Transactional(propagation = REQUIRES_NEW)} boundary
     * (see {@link #postTransaction(DailyTransaction,
     * ValidationResult)}) so per-record commits remain independent
     * &mdash; mirroring COBOL per-record commit semantics where any
     * mid-record I/O error caused a 9999-ABEND-PROGRAM. The outer
     * loop is intentionally <em>not</em> {@code @Transactional} so
     * one record's failure does not roll back the whole batch.</p>
     *
     * @param batchDate the business date of this batch run (used as
     *                  the S3 object-key prefix when writing reject
     *                  records to {@code dalyrejs/<date>/}). Must
     *                  not be {@code null}.
     * @return a {@link PostingResult} summary of the run
     * @throws NullPointerException if {@code batchDate} is null
     */
    public PostingResult postDailyTransactions(LocalDate batchDate) {
        Objects.requireNonNull(batchDate, "batchDate must not be null");

        // COBOL: CBTRN02C - START OF EXECUTION (L194)
        LOG.info("CBTRN02C: START OF EXECUTION OF PROGRAM CBTRN02C batchDate={}",
                batchDate);

        // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' / READ DALYTRAN-FILE INTO
        // DALYTRAN-RECORD (L202-L218). The repository-driven iteration
        // replaces the COBOL OPEN INPUT DALYTRAN-FILE + sequential READ
        // NEXT loop per AAP §0.4.1.
        List<DailyTransaction> dalyTransactions =
                dailyTransactionRepository.findAll();

        // COBOL: WS-TRANSACTION-COUNT PIC 9(09) VALUE 0
        //        WS-REJECT-COUNT      PIC 9(09) VALUE 0  (L185-L186)
        int transactionCount = 0;
        int rejectCount = 0;

        for (DailyTransaction dt : dalyTransactions) {
            // COBOL: ADD 1 TO WS-TRANSACTION-COUNT (L206)
            transactionCount++;

            // COBOL: MOVE 0 TO WS-VALIDATION-FAIL-REASON
            //        MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC (L208-L209)
            //        PERFORM 1500-VALIDATE-TRAN (L210)
            ValidationResult vr = validateTransaction(dt);

            if (vr.reasonCode() == 0) {
                // COBOL: IF WS-VALIDATION-FAIL-REASON = 0
                //          PERFORM 2000-POST-TRANSACTION (L211-L212)
                postTransaction(dt, vr);
            } else {
                // COBOL: ELSE ADD 1 TO WS-REJECT-COUNT
                //             PERFORM 2500-WRITE-REJECT-REC (L213-L215)
                rejectCount++;
                writeRejectRecord(dt, vr, batchDate);
            }
        }

        // COBOL: IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE (L229-L230)
        int returnCode = rejectCount > 0 ? RETURN_CODE_WITH_REJECTS : 0;

        // COBOL: DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT
        //        DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT
        //        DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C' (L227-L232)
        LOG.info("CBTRN02C: TRANSACTIONS PROCESSED : {}", transactionCount);
        LOG.info("CBTRN02C: TRANSACTIONS REJECTED  : {}", rejectCount);
        LOG.info("CBTRN02C: END OF EXECUTION OF PROGRAM CBTRN02C "
                + "returnCode={}", returnCode);

        return new PostingResult(transactionCount, rejectCount, returnCode);
    }

    // =========================================================================
    // Private paragraphs — one Java method per COBOL paragraph; order preserved
    // =========================================================================

    /**
     * COBOL: {@code 1500-VALIDATE-TRAN} (L370-L378) &mdash; the
     * 4-stage validation cascade short-circuiting on first failure
     * per COBOL {@code IF WS-VALIDATION-FAIL-REASON = 0 PERFORM
     * 1500-B-LOOKUP-ACCT} pattern.
     *
     * <p>Stages:
     * <ol>
     *   <li><b>1500-A-LOOKUP-XREF</b> (L380-L392) &mdash; READ
     *       XREF-FILE by DALYTRAN-CARD-NUM &rarr; reject code 100
     *       on file status '23' (NOTFND).</li>
     *   <li><b>1500-B-LOOKUP-ACCT</b> (L393-L401) &mdash; READ
     *       ACCOUNT-FILE by XREF-ACCT-ID &rarr; reject code 101 on
     *       file status '23'.</li>
     *   <li><b>Credit-limit check</b> (L403-L413) &mdash; COMPUTE
     *       WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
     *       + DALYTRAN-AMT; IF ACCT-CREDIT-LIMIT &lt; WS-TEMP-BAL
     *       MOVE 102 TO WS-VALIDATION-FAIL-REASON.</li>
     *   <li><b>Expiration check</b> (L414-L420) &mdash; IF
     *       ACCT-EXPIRAION-DATE &lt; DALYTRAN-ORIG-TS (1:10) MOVE
     *       103 TO WS-VALIDATION-FAIL-REASON.</li>
     * </ol>
     *
     * @param dt the daily transaction under validation
     * @return a {@link ValidationResult} carrying either the
     *         looked-up entities (success) or the verbatim COBOL
     *         reject code/description (failure)
     */
    ValidationResult validateTransaction(DailyTransaction dt) {
        // COBOL: 1500-A-LOOKUP-XREF (L380-L392)
        // MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM (L382)
        // READ XREF-FILE INTO CARD-XREF-RECORD
        //   INVALID KEY MOVE 100 TO WS-VALIDATION-FAIL-REASON  (L384-L387)
        String cardNum = dt.getDalytranCardNum();
        if (cardNum == null || cardNum.isBlank()) {
            // Defensive: a null/blank card number cannot resolve to an
            // XREF row, so short-circuit with reject code 100 rather
            // than issuing a repository call with an invalid key.
            return ValidationResult.reject(REJECT_INVALID_CARD,
                    DESC_INVALID_CARD);
        }
        Optional<CardCrossReference> xrefOpt =
                cardCrossReferenceRepository.findById(cardNum);
        if (xrefOpt.isEmpty()) {
            return ValidationResult.reject(REJECT_INVALID_CARD,
                    DESC_INVALID_CARD);
        }
        CardCrossReference xref = xrefOpt.get();

        // COBOL: 1500-B-LOOKUP-ACCT (L393-L401)
        // MOVE XREF-ACCT-ID TO FD-ACCT-ID (L394)
        // READ ACCOUNT-FILE INTO ACCOUNT-RECORD
        //   INVALID KEY MOVE 101 TO WS-VALIDATION-FAIL-REASON  (L395-L399)
        Optional<Account> acctOpt =
                accountRepository.findById(xref.getXrefAcctId());
        if (acctOpt.isEmpty()) {
            return ValidationResult.reject(REJECT_ACCOUNT_NOT_FOUND,
                    DESC_ACCOUNT_NOT_FOUND);
        }
        Account account = acctOpt.get();

        // COBOL: credit-limit check (CBTRN02C L403-L413)
        //   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                       - ACCT-CURR-CYC-DEBIT
        //                       + DALYTRAN-AMT  (L403-L405)
        //   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE
        //   ELSE MOVE 102 TO WS-VALIDATION-FAIL-REASON  (L407-L412)
        BigDecimal cycCredit = nonNullScale2(account.getAcctCurrCycCredit());
        BigDecimal cycDebit = nonNullScale2(account.getAcctCurrCycDebit());
        BigDecimal amt = nonNullScale2(dt.getDalytranAmt());
        BigDecimal tempBal;
        try {
            // COBOL ADD/SUBTRACT/COMPUTE semantics — explicit setScale(2,
            // HALF_EVEN) at the boundary preserves COBOL PIC S9(09)V99
            // decimal-arithmetic semantics exactly per AAP §0.6.1.
            tempBal = cycCredit.subtract(cycDebit).add(amt)
                    .setScale(2, RoundingMode.HALF_EVEN);
        } catch (ArithmeticException e) {
            // COBOL ON SIZE ERROR semantics per AAP §0.7.1 rule 5 — any
            // overflow in WS-TEMP-BAL surfaces as a typed exception that
            // bubbles up to the Spring Batch tasklet, then to AWS Batch
            // as a non-zero exit code.
            throw new OnSizeErrorException(
                    "ON_SIZE_ERROR_WS_TEMP_BAL",
                    "ON SIZE ERROR computing WS-TEMP-BAL for "
                            + "tranId=" + safe(dt.getDalytranId()),
                    e);
        }
        // Explicit overflow guard per AAP §0.6.1 (Java BigDecimal arithmetic
        // does not raise ArithmeticException on result magnitude — the guard
        // implements the COBOL ON SIZE ERROR semantics directly).
        guardOnSizeError(tempBal, "WS-TEMP-BAL");
        BigDecimal creditLimit = nonNullScale2(account.getAcctCreditLimit());
        if (creditLimit.compareTo(tempBal) < 0) {
            return ValidationResult.reject(REJECT_OVERLIMIT, DESC_OVERLIMIT);
        }

        // COBOL: expiration check (CBTRN02C L414-L420)
        //   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE
        //   ELSE MOVE 103 TO WS-VALIDATION-FAIL-REASON
        //   The COBOL substring (1:10) extracts "YYYY-MM-DD" from the
        //   X(26) DB2-format timestamp — equivalent to
        //   LocalDateTime.toLocalDate() in Java.
        LocalDate origDate = dt.getDalytranOrigTs() != null
                ? dt.getDalytranOrigTs().toLocalDate()
                : null;
        LocalDate expiry = account.getAcctExpirationDate();
        if (origDate != null && expiry != null && expiry.isBefore(origDate)) {
            return ValidationResult.reject(REJECT_EXPIRED, DESC_EXPIRED);
        }

        return ValidationResult.ok(xref, account);
    }

    /**
     * COBOL: {@code 2000-POST-TRANSACTION} (L424-L444) &mdash; the
     * post-validation update orchestrator.
     *
     * <p>Per-record commit boundary: each invocation runs in its own
     * transaction (per AAP &sect;0.7.1 rule 8 + agent_prompt
     * "{@code @Transactional(rollbackFor = Exception.class,
     * isolation = READ_COMMITTED, propagation = REQUIRES_NEW)}"
     * directive). This mirrors COBOL per-record commit semantics
     * where any mid-record I/O error caused a 9999-ABEND-PROGRAM
     * (which, in the Java target, becomes a rolled-back transaction
     * surfacing a {@link CardDemoException} to the batch tasklet).</p>
     *
     * <p>Steps (preserve COBOL order):</p>
     * <ol>
     *   <li>Build the target {@link Transaction} entity by copying
     *       each {@code DALYTRAN-*} field to the matching
     *       {@code TRAN-*} field (COBOL L425-L437).</li>
     *   <li>{@code PERFORM 2700-UPDATE-TCATBAL} (L440) &rarr;
     *       {@link #updateTcatbal(DailyTransaction,
     *       CardCrossReference)}.</li>
     *   <li>{@code PERFORM 2800-UPDATE-ACCOUNT-REC} (L441) &rarr;
     *       {@link #updateAccount(DailyTransaction, Account)}.</li>
     *   <li>{@code PERFORM 2900-WRITE-TRANSACTION-FILE} (L442)
     *       &rarr; {@code transactionRepository.save(tx)}.</li>
     *   <li>Post-commit side effects: publish
     *       {@code transaction.posted} and {@code account.updated}
     *       MSK events plus emit an OpenSearch/CloudTrail audit
     *       record.</li>
     * </ol>
     *
     * @param dt the daily transaction being posted
     * @param vr the validation result carrying the looked-up
     *           {@link CardCrossReference} and {@link Account}
     */
    @Transactional(
            rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    void postTransaction(DailyTransaction dt, ValidationResult vr) {
        // COBOL: MOVE DALYTRAN-* TO TRAN-* (L425-L437)
        Transaction tx = new Transaction();
        tx.setTranId(dt.getDalytranId());
        tx.setTranTypeCd(dt.getDalytranTypeCd());
        tx.setTranCatCd(dt.getDalytranCatCd());
        tx.setTranSource(dt.getDalytranSource());
        tx.setTranDesc(dt.getDalytranDesc());
        // Monetary amount preserved with scale=2, HALF_EVEN per AAP §0.6.1.
        tx.setTranAmt(nonNullScale2(dt.getDalytranAmt()));
        tx.setTranMerchantId(dt.getDalytranMerchantId());
        tx.setTranMerchantName(dt.getDalytranMerchantName());
        tx.setTranMerchantCity(dt.getDalytranMerchantCity());
        tx.setTranMerchantZip(dt.getDalytranMerchantZip());
        tx.setTranCardNum(dt.getDalytranCardNum());
        tx.setTranOrigTs(dt.getDalytranOrigTs());
        // COBOL: PERFORM Z-GET-DB2-FORMAT-TIMESTAMP / MOVE DB2-FORMAT-TS
        //        TO TRAN-PROC-TS (L437-L438) — replaced by native
        //        LocalDateTime.now() per AAP §0.5.2 (Java native date/time
        //        replaces LE CEEDAYS).
        tx.setTranProcTs(LocalDateTime.now());

        // COBOL: PERFORM 2700-UPDATE-TCATBAL (L440)
        updateTcatbal(dt, vr.xref());
        // COBOL: PERFORM 2800-UPDATE-ACCOUNT-REC (L441)
        updateAccount(dt, vr.account());
        // COBOL: PERFORM 2900-WRITE-TRANSACTION-FILE (L442)
        // The COBOL paragraph writes FD-TRANFILE-REC FROM TRAN-RECORD; the
        // Java target persists the JPA entity via the Spring Data
        // repository, which executes the matching INSERT.
        transactionRepository.save(tx);

        // -- Side effects (after successful commit boundary). ---------------
        // These are best-effort: any failure here is logged but does NOT
        // roll back the transaction post (the COBOL source had no
        // post-commit eventing; these are additive per AAP §0.6.5). They
        // are inside the @Transactional method so events are emitted
        // after the row is persisted in JPA terms; actual Kafka delivery
        // may complete asynchronously per Spring Kafka's @Async producer.
        publishPostedEvents(tx, vr.account());
        emitPostedAudit(tx, vr.account());
    }

    /**
     * COBOL: {@code 2700-UPDATE-TCATBAL} (L467-L539) &mdash; upserts
     * the per-{@code (account, type, category)} running balance.
     *
     * <p>Composite-key construction (COBOL L469-L471):</p>
     * <pre>
     * MOVE XREF-ACCT-ID    TO FD-TRANCAT-ACCT-ID
     * MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
     * MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD
     * </pre>
     *
     * <p>The COBOL source distinguishes two branches:</p>
     * <ul>
     *   <li><b>2700-A-CREATE-TCATBAL-REC</b> &mdash; when the
     *       TCATBAL READ returns file status '23' (NOTFND), the
     *       record is created (INITIALIZE + WRITE with DALYTRAN-AMT
     *       as the initial balance).</li>
     *   <li><b>2700-B-UPDATE-TCATBAL-REC</b> &mdash; when the
     *       TCATBAL READ succeeds, ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     *       and REWRITE.</li>
     * </ul>
     *
     * @param dt   the daily transaction being posted
     * @param xref the cross-reference row resolved in stage 1 of
     *             {@link #validateTransaction(DailyTransaction)}
     */
    void updateTcatbal(DailyTransaction dt, CardCrossReference xref) {
        // COBOL: 2700-UPDATE-TCATBAL — composite key construction
        // FD-TRAN-CAT-KEY = (ACCT-ID, TYPE-CD, CD) per CBTRN02C L93-L96.
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                xref.getXrefAcctId(),
                dt.getDalytranTypeCd(),
                dt.getDalytranCatCd());
        Optional<TransactionCategoryBalance> tcatOpt =
                transactionCategoryBalanceRepository.findById(key);

        if (tcatOpt.isEmpty()) {
            // COBOL: 2700-A-CREATE-TCATBAL-REC — file status '23' = NOTFND.
            //   INITIALIZE TRAN-CAT-BAL-RECORD; MOVE keys; MOVE DALYTRAN-AMT
            //   TO TRAN-CAT-BAL; WRITE FD-TRAN-CAT-BAL-RECORD.
            LOG.info("CBTRN02C: TCATBAL record not found for key "
                    + "acctId={} typeCd={} catCd={} - creating",
                    xref.getXrefAcctId(),
                    dt.getDalytranTypeCd(),
                    dt.getDalytranCatCd());
            TransactionCategoryBalance tcat = new TransactionCategoryBalance();
            tcat.setId(key);
            // COBOL: MOVE DALYTRAN-AMT TO TRAN-CAT-BAL — explicit scale=2 to
            // preserve PIC S9(09)V99 semantics per AAP §0.6.1.
            tcat.setTranCatBal(nonNullScale2(dt.getDalytranAmt()));
            transactionCategoryBalanceRepository.save(tcat);
        } else {
            // COBOL: 2700-B-UPDATE-TCATBAL-REC
            //   ADD DALYTRAN-AMT TO TRAN-CAT-BAL
            //   REWRITE FD-TRAN-CAT-BAL-RECORD.
            TransactionCategoryBalance tcat = tcatOpt.get();
            BigDecimal newBal;
            try {
                newBal = nonNullScale2(tcat.getTranCatBal())
                        .add(nonNullScale2(dt.getDalytranAmt()))
                        .setScale(2, RoundingMode.HALF_EVEN);
            } catch (ArithmeticException e) {
                // COBOL ON SIZE ERROR — TCATBAL accumulator overflow.
                throw new OnSizeErrorException(
                        "ON_SIZE_ERROR_TCATBAL",
                        "ON SIZE ERROR updating TCATBAL for "
                                + "tranId=" + safe(dt.getDalytranId()),
                        e);
            }
            // Explicit overflow guard per AAP §0.6.1.
            guardOnSizeError(newBal, "TRAN-CAT-BAL");
            tcat.setTranCatBal(newBal);
            transactionCategoryBalanceRepository.save(tcat);
        }
    }

    /**
     * COBOL: {@code 2800-UPDATE-ACCOUNT-REC} (L545-L560) &mdash;
     * updates the {@link Account} entity with DALYTRAN-AMT and
     * REWRITEs it. Optimistic-lock failure on save (the Java
     * equivalent of COBOL REWRITE INVALID KEY) maps to reject code
     * 109 semantics via a {@link CardDemoException} with reason code
     * {@code "ACCOUNT_REWRITE_FAILED"}.
     *
     * <p>Sign-based routing (COBOL L547-L550):</p>
     * <pre>
     * IF DALYTRAN-AMT >= 0
     *   ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     * ELSE
     *   ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     * END-IF
     * </pre>
     *
     * <p>This routing is a verbatim business rule per AAP &sect;0.7.1
     * Refactor Discipline ("Account balance signed amount routing is
     * a verbatim business rule — DO NOT optimize/simplify").</p>
     *
     * @param dt      the daily transaction being posted
     * @param account the account resolved in stage 2 of
     *                {@link #validateTransaction(DailyTransaction)}
     */
    void updateAccount(DailyTransaction dt, Account account) {
        // COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL (L546)
        BigDecimal newBalance;
        try {
            newBalance = nonNullScale2(account.getAcctCurrBal())
                    .add(nonNullScale2(dt.getDalytranAmt()))
                    .setScale(2, RoundingMode.HALF_EVEN);
        } catch (ArithmeticException e) {
            throw new OnSizeErrorException(
                    "ON_SIZE_ERROR_ACCT_CURR_BAL",
                    "ON SIZE ERROR ADD DALYTRAN-AMT TO ACCT-CURR-BAL for "
                            + "acctId=" + account.getAcctId(),
                    e);
        }
        // Explicit overflow guard per AAP §0.6.1.
        guardOnSizeError(newBalance, "ACCT-CURR-BAL");
        account.setAcctCurrBal(newBalance);

        // COBOL: IF DALYTRAN-AMT >= 0
        //          ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
        //        ELSE
        //          ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
        //        END-IF (L547-L550)
        BigDecimal amt = nonNullScale2(dt.getDalytranAmt());
        if (amt.signum() >= 0) {
            BigDecimal newCredit;
            try {
                newCredit = nonNullScale2(account.getAcctCurrCycCredit())
                        .add(amt)
                        .setScale(2, RoundingMode.HALF_EVEN);
            } catch (ArithmeticException e) {
                throw new OnSizeErrorException(
                        "ON_SIZE_ERROR_ACCT_CURR_CYC_CREDIT",
                        "ON SIZE ERROR ADD DALYTRAN-AMT TO "
                                + "ACCT-CURR-CYC-CREDIT for acctId="
                                + account.getAcctId(),
                        e);
            }
            // Explicit overflow guard per AAP §0.6.1.
            guardOnSizeError(newCredit, "ACCT-CURR-CYC-CREDIT");
            account.setAcctCurrCycCredit(newCredit);
        } else {
            BigDecimal newDebit;
            try {
                newDebit = nonNullScale2(account.getAcctCurrCycDebit())
                        .add(amt)
                        .setScale(2, RoundingMode.HALF_EVEN);
            } catch (ArithmeticException e) {
                throw new OnSizeErrorException(
                        "ON_SIZE_ERROR_ACCT_CURR_CYC_DEBIT",
                        "ON SIZE ERROR ADD DALYTRAN-AMT TO "
                                + "ACCT-CURR-CYC-DEBIT for acctId="
                                + account.getAcctId(),
                        e);
            }
            // Explicit overflow guard per AAP §0.6.1.
            guardOnSizeError(newDebit, "ACCT-CURR-CYC-DEBIT");
            account.setAcctCurrCycDebit(newDebit);
        }

        // COBOL: REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        //        INVALID KEY MOVE 109 TO WS-VALIDATION-FAIL-REASON
        // JPA REWRITE = save. OptimisticLockingFailureException corresponds
        // to the COBOL INVALID KEY condition (i.e., the row no longer
        // matches the snapshot loaded at stage 2; mirrors COBOL
        // before/after image comparison). Map to reject code 109.
        try {
            accountRepository.save(account);
        } catch (OptimisticLockingFailureException e) {
            // COBOL: MOVE 109 TO WS-VALIDATION-FAIL-REASON
            //        DISPLAY 'ERROR REWRITING ACCOUNT' ; ABEND.
            // The Java target raises a typed CardDemoException carrying
            // reject code 109 semantics — the surrounding @Transactional
            // boundary rolls back so the row state remains consistent.
            throw new CardDemoException(
                    "ACCOUNT_REWRITE_FAILED",
                    "ACCOUNT RECORD NOT FOUND (109) for acctId="
                            + account.getAcctId(),
                    e);
        }
    }

    /**
     * COBOL: {@code 2500-WRITE-REJECT-REC} (L446-L465) &mdash; writes
     * the rejected daily transaction to the DALYREJS sequential file,
     * now an S3 prefix (replaces COBOL {@code WRITE FD-REJS-RECORD
     * FROM REJECT-RECORD}). The output record is exactly 430 bytes:
     * 350-byte REJECT-TRAN-DATA (the DALYTRAN record) concatenated
     * with the 80-byte VALIDATION-TRAILER (4-byte zero-padded
     * reject reason code + 76-byte left-padded reason description).
     *
     * <p>S3 object key format:
     * {@code dalyrejs/<batchDate>/rejects-<epochMs>.txt}. Per AAP
     * &sect;0.7.1 the S3 SDK call is delegated to
     * {@link S3OutputService#writeRejection(String, String)} (never
     * inlined here).</p>
     *
     * <p>Audit emission: after a successful S3 write, an audit
     * record is emitted to OpenSearch via
     * {@link AuditLogService#logTransactionEvent(String, Long,
     * String, String, String, Map, String)} preserving the
     * transaction ID + reject reason code + description verbatim
     * per AAP &sect;0.7.2 ("Audit trail content must continue to be
     * emitted with the same values and semantics").</p>
     *
     * @param dt        the daily transaction that failed validation
     * @param vr        the validation result carrying the verbatim
     *                  COBOL reject code and description
     * @param batchDate the business date used as the S3 object-key
     *                  prefix
     */
    void writeRejectRecord(DailyTransaction dt,
                           ValidationResult vr,
                           LocalDate batchDate) {
        // COBOL: 2500-WRITE-REJECT-REC (L446-L465)
        //   MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA  (L447)
        //   MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER  (L448)
        //   WRITE FD-REJS-RECORD FROM REJECT-RECORD  (L451)
        // VALIDATION-TRAILER layout (L181-L182):
        //   05 WS-VALIDATION-FAIL-REASON      PIC 9(04)  (4 bytes, zero-padded)
        //   05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)  (76 bytes, left-padded)
        // Total trailer width = 80 bytes; record total = 350 + 80 = 430.
        String reasonCode = String.format("%04d", vr.reasonCode());
        String reasonDesc = padRight(vr.reasonDescription(), 76);

        // Build the 430-byte (350+80) reject record verbatim per AAP §0.7.2
        // "regulatory reporting output formats must remain identical
        // byte-for-byte".
        String rejectLine = padTo350(dt) + reasonCode + reasonDesc;

        // Compose the S3 batch run identifier — used by the adapter as
        // the per-day object-key prefix (e.g.,
        // dalyrejs/2025-01-15/rejs-<ts>.rejs). This satisfies the
        // S3OutputService contract that batchRunId is non-blank.
        String batchRunId = "POSTTRAN-" + batchDate.toString();

        try {
            s3OutputService.writeRejection(batchRunId, rejectLine);
            emitRejectedAudit(dt, vr);
        } catch (CardDemoException e) {
            // A CardDemoException raised inside the audit path (or by
            // the adapter validating batchRunId) is re-thrown so the
            // batch tasklet surfaces it to AWS Batch. The S3 write
            // already completed at this point if the audit failed.
            throw e;
        } catch (RuntimeException e) {
            // S3 upload failure — map to CardDemoException carrying
            // a reason code so the Spring Batch tasklet surfaces a
            // non-zero exit to AWS Batch (per AAP §0.7.1).
            LOG.error("CBTRN02C: failed to write reject record for "
                    + "tranId={}", safe(dt.getDalytranId()), e);
            throw new CardDemoException(
                    "WRITE_REJECT_FAILED",
                    "Failed to write reject record for tranId="
                            + safe(dt.getDalytranId()),
                    e);
        }
    }

    // =========================================================================
    // Private helpers — DALYTRAN serialization, side-effect emitters
    // =========================================================================

    /**
     * Serializes the daily transaction back to its 350-byte
     * fixed-width form per {@code CVTRA06Y.cpy}. Field widths are
     * preserved verbatim from the copybook so the DALYREJS S3 object
     * is byte-identical to the original mainframe sequential file.
     *
     * <p>Layout (350 bytes total):</p>
     * <pre>
     *  16 + 2 + 4 + 10 + 100 + 12 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 19
     *  = 350
     * </pre>
     *
     * <ul>
     *   <li>PIC X(*) — left-aligned, blank-padded</li>
     *   <li>PIC 9(*) — right-aligned, zero-padded, unsigned</li>
     *   <li>PIC S9(09)V99 — sign-prefixed (+/-) + 9 integer digits
     *       + literal '.' + 2 fractional digits (12 chars)</li>
     *   <li>PIC X(26) — DB2-format timestamp
     *       {@code yyyy-MM-dd-HH.mm.ss.NNNNNN}</li>
     *   <li>Trailing 19-byte FILLER to bring total to 350 bytes
     *       (the COBOL copybook is silent on the trailing padding
     *       width, but the RECLN=350 / KEYLEN=16 constraints from
     *       {@code app/jcl/TRANFILE.jcl} require it).</li>
     * </ul>
     *
     * @param dt the daily transaction to serialize
     * @return the 350-byte fixed-width record (without trailing
     *         newline)
     */
    private String padTo350(DailyTransaction dt) {
        StringBuilder sb = new StringBuilder(350);
        // 16 bytes — DALYTRAN-ID PIC X(16)
        sb.append(padRight(dt.getDalytranId(), 16));
        // 2 bytes — DALYTRAN-TYPE-CD PIC X(02)
        sb.append(padRight(dt.getDalytranTypeCd(), 2));
        // 4 bytes — DALYTRAN-CAT-CD PIC 9(04)
        sb.append(formatNumeric(dt.getDalytranCatCd(), 4));
        // 10 bytes — DALYTRAN-SOURCE PIC X(10)
        sb.append(padRight(dt.getDalytranSource(), 10));
        // 100 bytes — DALYTRAN-DESC PIC X(100)
        sb.append(padRight(dt.getDalytranDesc(), 100));
        // 12 bytes — DALYTRAN-AMT PIC S9(09)V99 (signed, 12 chars)
        sb.append(formatSignedAmount(dt.getDalytranAmt()));
        // 9 bytes — DALYTRAN-MERCHANT-ID PIC 9(09)
        sb.append(formatNumeric(dt.getDalytranMerchantId(), 9));
        // 50 bytes — DALYTRAN-MERCHANT-NAME PIC X(50)
        sb.append(padRight(dt.getDalytranMerchantName(), 50));
        // 50 bytes — DALYTRAN-MERCHANT-CITY PIC X(50)
        sb.append(padRight(dt.getDalytranMerchantCity(), 50));
        // 10 bytes — DALYTRAN-MERCHANT-ZIP PIC X(10)
        sb.append(padRight(dt.getDalytranMerchantZip(), 10));
        // 16 bytes — DALYTRAN-CARD-NUM PIC X(16)
        sb.append(padRight(dt.getDalytranCardNum(), 16));
        // 26 bytes — DALYTRAN-ORIG-TS PIC X(26)
        sb.append(formatDb2Timestamp(dt.getDalytranOrigTs()));
        // 26 bytes — DALYTRAN-PROC-TS PIC X(26)
        sb.append(formatDb2Timestamp(dt.getDalytranProcTs()));
        // 19 bytes — trailing FILLER to reach 350-byte record size
        sb.append(" ".repeat(19));
        return sb.toString();
    }

    /**
     * Pads or truncates a string to exactly {@code width} characters,
     * blank-padded on the right (COBOL PIC X semantics).
     *
     * @param v     the value to pad ({@code null} treated as blank)
     * @param width the target width
     * @return the padded string
     */
    private static String padRight(String v, int width) {
        String s = v == null ? "" : v;
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        return String.format("%-" + width + "s", s);
    }

    /**
     * Formats an integral value as a zero-padded unsigned numeric
     * string (COBOL PIC 9(width) semantics). {@code null} is treated
     * as zero.
     *
     * @param value the value to format (Integer or Long)
     * @param width the target width
     * @return the zero-padded string
     */
    private static String formatNumeric(Number value, int width) {
        long n = value == null ? 0L : value.longValue();
        return String.format("%0" + width + "d", n);
    }

    /**
     * Formats a signed {@link BigDecimal} amount as a 12-character
     * COBOL {@code PIC S9(09)V99} string with explicit sign prefix
     * (+ or -) followed by 9 integer digits and 2 fractional digits.
     * The decimal point is <em>implied</em> per COBOL {@code V99}
     * semantics &mdash; no literal '.' is emitted.
     *
     * @param value the amount ({@code null} treated as zero)
     * @return the 12-character formatted amount
     */
    private static String formatSignedAmount(BigDecimal value) {
        BigDecimal v = value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : value.setScale(2, RoundingMode.HALF_EVEN);
        String sign = v.signum() < 0 ? "-" : "+";
        BigDecimal abs = v.abs();
        String plain = abs.toPlainString();
        String[] parts = plain.split("\\.");
        String intPart = parts.length > 0 ? parts[0] : "0";
        String fracPart = parts.length > 1 ? parts[1] : "00";
        if (fracPart.length() < 2) {
            fracPart = fracPart + "0".repeat(2 - fracPart.length());
        } else if (fracPart.length() > 2) {
            fracPart = fracPart.substring(0, 2);
        }
        long intValue = intPart.isEmpty() ? 0L : Long.parseLong(intPart);
        // COBOL V99 = implied decimal — no literal '.' in storage.
        return sign + String.format("%09d", intValue) + fracPart;
    }

    /**
     * Formats a {@link LocalDateTime} as the COBOL DB2-format
     * timestamp {@code X(26)} string {@code yyyy-MM-dd-HH.mm.ss.NNNNNN}.
     * {@code null} produces 26 blank characters.
     *
     * @param ts the timestamp ({@code null} treated as blank)
     * @return the 26-character formatted timestamp
     */
    private static String formatDb2Timestamp(LocalDateTime ts) {
        if (ts == null) {
            return " ".repeat(26);
        }
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%06d",
                ts.getYear(),
                ts.getMonthValue(),
                ts.getDayOfMonth(),
                ts.getHour(),
                ts.getMinute(),
                ts.getSecond(),
                ts.getNano() / 1000);
    }

    /**
     * Returns the input {@link BigDecimal} normalized to scale 2 with
     * banker's rounding, or {@code BigDecimal.ZERO} (scale 2) if the
     * input is {@code null}. Centralizes the AAP &sect;0.6.1
     * arithmetic-discipline rule so every monetary read from the
     * domain entities consistently presents a non-null,
     * 2-scale {@link BigDecimal}.
     *
     * @param value the value to normalize
     * @return the normalized value
     */
    private static BigDecimal nonNullScale2(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : value.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Verifies a post-arithmetic {@link BigDecimal} value does not
     * exceed the configured {@link #MAX_AMOUNT} ceiling &mdash;
     * explicit COBOL {@code ON SIZE ERROR} replication per AAP
     * &sect;0.6.1 ("each arithmetic operation is wrapped in a check
     * against the configured precision, and an
     * {@link OnSizeErrorException} is thrown if the result would
     * exceed the column's precision"). Returns the value unchanged
     * on success so call sites can chain.
     *
     * @param value     the post-arithmetic value to check
     * @param fieldName the human-readable COBOL field name embedded
     *                  in the {@link OnSizeErrorException} message
     *                  for traceability (e.g., {@code "ACCT-CURR-BAL"})
     * @return the value unchanged, on success
     * @throws OnSizeErrorException if {@code value.abs() > MAX_AMOUNT}
     */
    private static BigDecimal guardOnSizeError(BigDecimal value,
                                               String fieldName) {
        if (value != null && value.abs().compareTo(MAX_AMOUNT) > 0) {
            // COBOL: ON SIZE ERROR — the receiving field cannot
            // accommodate the post-arithmetic value. Surface as a
            // typed exception so the surrounding @Transactional
            // boundary rolls back the per-record commit and the
            // Spring Batch tasklet propagates a non-zero exit code
            // to AWS Batch.
            throw new OnSizeErrorException(
                    "ON_SIZE_ERROR",
                    "ON SIZE ERROR on " + fieldName
                            + " — post-arithmetic value exceeds "
                            + "MAX_AMOUNT=" + MAX_AMOUNT.toPlainString());
        }
        return value;
    }

    /**
     * Returns a safe (non-null) string for logging — never PII per
     * AAP rule 10. {@code null} is rendered as the literal
     * {@code "null"}; otherwise the value is returned unchanged
     * (the caller is responsible for ensuring it does not contain
     * full card numbers or amounts).
     *
     * @param s the candidate string
     * @return a safe representation for log output
     */
    private static String safe(String s) {
        return s == null ? "null" : s;
    }

    /**
     * Publishes the {@code transaction.posted} and
     * {@code account.updated} MSK events for a posted transaction
     * (per AAP &sect;0.6.5 MSK Topic Ordering Guarantees). Both
     * events are partitioned by the account ID via the adapter's
     * key formatter so per-account ordering is preserved.
     *
     * <p>Per AAP &sect;0.7.1 ("isolate AWS SDK calls in dedicated
     * adapter classes"), Kafka publish failures are <em>not</em>
     * propagated to the caller &mdash; instead they are logged at
     * WARN level so the rest of the batch run continues. The
     * @Async producer in {@link KafkaEventPublisher} returns a
     * {@link java.util.concurrent.CompletableFuture} which is not
     * awaited here (fire-and-forget on the producer thread pool).</p>
     *
     * @param tx      the persisted transaction
     * @param account the updated account
     */
    private void publishPostedEvents(Transaction tx, Account account) {
        Long acctId = account.getAcctId();

        // Build a TransactionAddDto envelope from the posted Transaction
        // and post-update account state — this is what the
        // KafkaEventPublisher.publishTransactionPosted API expects.
        try {
            TransactionAddDto txEvent = buildTransactionAddDto(tx, acctId);
            kafkaEventPublisher.publishTransactionPosted(acctId, txEvent);
        } catch (RuntimeException ex) {
            // Replaces: implicit downstream-notification absence in the
            // original COBOL batch — we log and continue per AAP §0.6.5
            // (event publishing is additive; primary state is already
            // committed).
            LOG.warn("CBTRN02C: transaction.posted publish failed for "
                    + "acctId={} tranId={} (continuing)",
                    acctId, safe(tx.getTranId()), ex);
        }

        try {
            AccountUpdateDto acctEvent = buildAccountUpdateDto(account);
            kafkaEventPublisher.publishAccountUpdated(acctId, acctEvent);
        } catch (RuntimeException ex) {
            LOG.warn("CBTRN02C: account.updated publish failed for "
                    + "acctId={} (continuing)", acctId, ex);
        }
    }

    /**
     * Constructs a minimal {@link TransactionAddDto} envelope for the
     * MSK {@code transaction.posted} event. Only the fields needed
     * by downstream audit/reporting consumers are populated;
     * BMS-specific operator-input fields (zip code overrides etc.)
     * are left {@code null} since this is a server-emitted event,
     * not an operator-keyed request.
     *
     * @param tx     the persisted transaction
     * @param acctId the account ID (used as the Kafka partition key
     *               by the adapter)
     * @return a transaction-add DTO suitable for Kafka publication
     */
    private TransactionAddDto buildTransactionAddDto(Transaction tx,
                                                    Long acctId) {
        return new TransactionAddDto(
                // accountId — formatted to the 11-digit zero-padded
                // string per AAP §0.6.5 for consistent partitioner hashing.
                String.format("%011d", acctId),
                tx.getTranCardNum(),
                tx.getTranTypeCd(),
                tx.getTranCatCd(),
                tx.getTranSource(),
                tx.getTranDesc(),
                nonNullScale2(tx.getTranAmt()),
                tx.getTranOrigTs(),
                tx.getTranProcTs(),
                tx.getTranMerchantId(),
                tx.getTranMerchantName(),
                tx.getTranMerchantCity(),
                tx.getTranMerchantZip(),
                "Y");
    }

    /**
     * Constructs a minimal {@link AccountUpdateDto} envelope for the
     * MSK {@code account.updated} event. Most BMS-specific operator-
     * input fields (customer name, address overrides, government
     * IDs, etc.) are left {@code null} since this is a server-emitted
     * event derived from the post-update {@link Account} entity (not
     * an operator-keyed request).
     *
     * @param account the post-update account entity
     * @return an account-update DTO suitable for Kafka publication
     */
    private AccountUpdateDto buildAccountUpdateDto(Account account) {
        return new AccountUpdateDto(
                account.getAcctId(),
                account.getAcctActiveStatus(),
                account.getAcctCurrBal(),
                account.getAcctCreditLimit(),
                account.getAcctCashCreditLimit(),
                account.getAcctOpenDate(),
                account.getAcctExpirationDate(),
                account.getAcctReissueDate(),
                account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit(),
                account.getAcctAddrZip(),
                account.getAcctGroupId(),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                account.getVersion());
    }

    /**
     * Emits an audit record for a successfully posted transaction to
     * Amazon OpenSearch (and CloudTrail for cross-cutting AWS API
     * events) per AAP &sect;0.6.6. PII-safe: the payload contains
     * only the transaction ID and account ID; full card numbers are
     * <em>not</em> included (the adapter performs additional
     * sanitization as defense-in-depth).
     *
     * @param tx      the persisted transaction
     * @param account the updated account
     */
    private void emitPostedAudit(Transaction tx, Account account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tranId", tx.getTranId());
        payload.put("acctId", account.getAcctId());
        payload.put("tranTypeCd", tx.getTranTypeCd());
        payload.put("tranCatCd", tx.getTranCatCd());
        payload.put("tranProcTs", tx.getTranProcTs());
        auditLogService.logTransactionEvent(
                tx.getTranId(),
                account.getAcctId(),
                OPERATOR_BATCH,
                AUDIT_EVENT_POSTED,
                null,
                payload,
                tx.getTranId());
    }

    /**
     * Emits an audit record for a rejected transaction to Amazon
     * OpenSearch (and CloudTrail for cross-cutting AWS API events)
     * per AAP &sect;0.6.6 &mdash; preserving the verbatim COBOL
     * reject code and description per AAP &sect;0.7.2.
     *
     * @param dt the daily transaction that failed validation
     * @param vr the validation result carrying the reject code and
     *           description
     */
    private void emitRejectedAudit(DailyTransaction dt, ValidationResult vr) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tranId", dt.getDalytranId());
        payload.put("rejectCode", vr.reasonCode());
        payload.put("rejectDescription", vr.reasonDescription());
        // Note: card number is NOT placed in the payload per AAP §0.6.6
        // PII discipline — the adapter sanitizes additionally as
        // defense-in-depth.
        auditLogService.logTransactionEvent(
                dt.getDalytranId(),
                null,
                OPERATOR_BATCH,
                AUDIT_EVENT_REJECTED,
                String.format("%04d", vr.reasonCode()),
                payload,
                dt.getDalytranId());
    }
}
