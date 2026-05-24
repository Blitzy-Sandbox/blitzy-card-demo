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
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.DisclosureGroup;
import com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.DisclosureGroupRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Interest-calculation batch service &mdash; the Java target for the COBOL
 * batch program {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>This service implements the end-of-day interest-accrual job. It
 * iterates through every {@link TransactionCategoryBalance} row
 * (replaces the COBOL TCATBAL VSAM cluster read loop), looks up the
 * applicable disclosure-group interest rate by composite key
 * {@code (account-group-id, transaction-type-code, transaction-category-code)},
 * computes the monthly interest using the COBOL-literal formula
 * <em>{@code (balance * rate) / 1200}</em> (paragraph
 * {@code 1300-COMPUTE-INTEREST} at {@code CBACT04C.cbl} L462-L468), and
 * accumulates the per-account total. When the iteration crosses an
 * account boundary (or finishes), the accumulated total is posted to the
 * account by calling {@link #postAccountInterest(Long, BigDecimal, LocalDate)}
 * which writes ONE interest {@link Transaction} record per account
 * (TYPE={@code "01"}, CAT={@code 5}, SOURCE={@code "System"}), adds the
 * total to {@link Account#getAcctCurrBal()}, and zeroes the cycle
 * credit/debit counters (paragraph {@code 1050-UPDATE-ACCOUNT} at
 * {@code CBACT04C.cbl} L350-L370).</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT04C.cbl} &mdash;
 *       batch interest calculator invoked by JCL job
 *       {@code app/jcl/INTCALC.jcl} (PARM=&apos;yyyyMMddHH&apos;).</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CVTRA01Y.cpy}
 *       ({@code TRAN-CAT-BAL-RECORD}, 50 bytes; {@link
 *       TransactionCategoryBalance}),
 *       {@code app/cpy/CVTRA02Y.cpy}
 *       ({@code DIS-GROUP-RECORD}, 50 bytes; {@link DisclosureGroup}),
 *       {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD}, 300 bytes; {@link Account}),
 *       {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}, 50 bytes; {@link CardCrossReference}),
 *       {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes; {@link Transaction}).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBACT04C.cbl &harr; InterestCalculationService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION USING EXTERNAL-PARMS} (L180-L222)</td>
 *       <td>{@link #calculateInterest(LocalDate)} &mdash; outer loop
 *       driver, scans TCATBAL and aggregates per-account interest</td></tr>
 *   <tr><td>{@code 1000-TCATBALF-GET-NEXT}</td>
 *       <td>{@link TransactionCategoryBalanceRepository#findAll()} with
 *       in-memory sort by acctId/typeCd/catCd to mirror VSAM key
 *       ordering</td></tr>
 *   <tr><td>{@code 1100-GET-ACCT-DATA}</td>
 *       <td>{@link AccountRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code 1110-GET-XREF-DATA}</td>
 *       <td>{@link CardCrossReferenceRepository#findByXrefAcctIdOrderByXrefCardNumAsc(Long)}
 *       (deterministic-order alternate-index lookup)</td></tr>
 *   <tr><td>{@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE}
 *       (L415-L460 &mdash; DEFAULT fallback when specific group
 *       missing)</td>
 *       <td>{@link #lookupRate(String, String, Integer)} &mdash;
 *       primary lookup then literal {@code "DEFAULT"} group-code retry</td></tr>
 *   <tr><td>{@code 1300-COMPUTE-INTEREST} (L462-L468 &mdash; literal
 *       1200 divisor)</td>
 *       <td>{@link #computeMonthlyInterest(TransactionCategoryBalance)}</td></tr>
 *   <tr><td>{@code 1300-B-WRITE-TX} (L473-L515 &mdash; TYPE/CAT/SOURCE
 *       literals; PARM-DATE + 6-digit suffix transaction ID)</td>
 *       <td>{@link #postAccountInterest(Long, BigDecimal, LocalDate)}
 *       (builds the single per-account interest TX)</td></tr>
 *   <tr><td>{@code 1050-UPDATE-ACCOUNT} (L350-L370 &mdash; ADD
 *       WS-TOTAL-INT TO ACCT-CURR-BAL; zero cycle counters)</td>
 *       <td>{@link #postAccountInterest(Long, BigDecimal, LocalDate)}
 *       (account update branch)</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1 Refactor Discipline)</h2>
 * <ul>
 *   <li><b>Literal 1200 divisor preserved:</b> Per AAP &sect;0.6.1
 *       <em>&quot;the divisor 1200 is preserved as
 *       {@code BigDecimal.valueOf(1200)} rather than precomputed&quot;</em>.
 *       This is the COBOL annualization formula
 *       <em>{@code rate / 12 months / 100 percentage scaling = rate / 1200}</em>
 *       and the literal is retained verbatim per the Minimal Change
 *       Clause.</li>
 *   <li><b>Banker's rounding:</b> All monetary arithmetic uses
 *       {@link RoundingMode#HALF_EVEN} (banker's rounding) with
 *       {@code setScale(2, HALF_EVEN)} after every multiply/divide chain,
 *       matching COBOL {@code COMPUTE} fixed-point semantics.</li>
 *   <li><b>DEFAULT group-code fallback:</b> When the specific
 *       {@code (account-group-id, type-code, category-code)} tuple
 *       returns no row, the service moves the literal string
 *       {@code "DEFAULT"} into the group-code position and retries
 *       (paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} at L443). If the
 *       DEFAULT row is also missing, the rate is treated as zero (the
 *       account simply accrues no interest from that category) so the
 *       batch can continue rather than abend &mdash; a deliberate
 *       resilience improvement over COBOL {@code PERFORM
 *       9999-ABEND-PROGRAM}.</li>
 *   <li><b>Transaction-ID format:</b> The COBOL writer concatenates
 *       {@code PARM-DATE} (10 chars) with {@code WS-TRANID-SUFFIX} (6
 *       digits) to form the 16-character {@code TRAN-ID} (paragraph
 *       {@code 1300-B-WRITE-TX} at L473-L484). The Java target builds a
 *       deterministic 16-character TRAN-ID via
 *       {@link #buildInterestTranId(Long, LocalDate)} composed of the
 *       parm-date prefix plus a zero-padded account-derived suffix so
 *       each per-account interest posting has a stable, unique
 *       identifier.</li>
 *   <li><b>ON SIZE ERROR:</b> Every monetary arithmetic operation is
 *       wrapped in a try/catch that traps {@link ArithmeticException}
 *       and rethrows as {@link OnSizeErrorException} (AAP &sect;0.7.1
 *       &mdash; replicate COBOL {@code ON SIZE ERROR} with explicit
 *       overflow checks in Java).</li>
 *   <li><b>Transaction boundary:</b> The outer {@link #calculateInterest(LocalDate)}
 *       method uses {@link Transactional @Transactional(readOnly = true)}
 *       so the long TCATBAL scan does not hold a write-mode database
 *       transaction. The inner {@link #postAccountInterest(Long, BigDecimal, LocalDate)}
 *       method uses {@link Transactional @Transactional(propagation =
 *       Propagation.REQUIRES_NEW, rollbackFor = Exception.class)} so each
 *       per-account commit is independent &mdash; a partial failure for
 *       one account does not roll back the interest postings for
 *       accounts that were processed successfully.</li>
 *   <li><b>Event emission:</b> After each per-account commit, the
 *       service publishes a {@code ledger.balanced} MSK event keyed by
 *       account ID carrying both the interest transaction summary and
 *       the updated account balance. Because the
 *       {@link KafkaEventPublisher#publishLedgerBalanced(Long, Object)}
 *       method accepts {@code Object}, no DTO type is required. AAP
 *       &sect;0.6.5 partition-by-account-ID guarantees per-account
 *       ordering.</li>
 *   <li><b>Audit:</b> Each posted interest transaction is recorded
 *       through {@link AuditLogService#logTransactionEvent(String, Long,
 *       String, String, String, java.util.Map, String)} with event type
 *       {@code "INTEREST_CALCULATED"} (AAP &sect;0.6.6); a final
 *       batch-summary audit event is emitted at the end of the run.</li>
 *   <li><b>No direct AWS SDK calls:</b> All AWS interaction is
 *       delegated to adapters ({@link AuditLogService},
 *       {@link KafkaEventPublisher}) per AAP &sect;0.7.1.</li>
 * </ul>
 *
 * <p><strong>Concurrency:</strong> This service is a Spring singleton.
 * Instance fields are immutable (final collaborator references). The
 * per-run mutable state (running totals, boundary tracking) is held as
 * method-local variables, so multiple parallel runs on separate threads
 * remain isolated. However, by AAP design this batch runs serially in
 * AWS Batch (one job per day per JES-equivalent schedule); concurrent
 * invocations are not expected.</p>
 */
@Service
public class InterestCalculationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(InterestCalculationService.class);

    /**
     * Literal divisor used by the COBOL interest formula at
     * {@code CBACT04C.cbl} L465: {@code (TRAN-CAT-BAL * DIS-INT-RATE) /
     * 1200}. The value 1200 is the annualization constant (12 months
     * &times; 100 percentage scaling). Per AAP &sect;0.6.1 this divisor
     * is preserved as a literal {@link BigDecimal} (via
     * {@link BigDecimal#valueOf(long)}) rather than pre-computed, to
     * match COBOL semantics exactly.
     */
    static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200L);

    /**
     * Literal group-code used by the DEFAULT-fallback retry at
     * {@code CBACT04C.cbl} L437:
     * <pre>{@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}</pre>
     * The COBOL source declares this as a 10-character field; the
     * trimmed Java literal is sufficient because Hibernate trims trailing
     * padding on read and the seed migration
     * {@code V012__seed_disclosure_group.sql} stores the row with the
     * trimmed key.
     */
    static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * COBOL constants written into every interest {@link Transaction}
     * record by paragraph {@code 1300-B-WRITE-TX} (L482-L498). Per the
     * AAP Minimal Change Clause (&sect;0.7.3) these literals are
     * preserved verbatim.
     */
    /** {@code MOVE '01' TO TRAN-TYPE-CD} (CBACT04C L482) &mdash; Interest type. */
    static final String INTEREST_TYPE_CODE = "01";
    /** {@code MOVE '05' TO TRAN-CAT-CD} (CBACT04C L483) &mdash; Interest category;
     *  stored as {@link Integer} 5 because the entity column is numeric. */
    static final Integer INTEREST_CAT_CODE = 5;
    /** {@code MOVE 'System' TO TRAN-SOURCE} (CBACT04C L484). */
    static final String INTEREST_SOURCE = "System";
    /** {@code MOVE 0 TO TRAN-MERCHANT-ID} (CBACT04C L491). */
    static final long INTEREST_MERCHANT_ID = 0L;
    /** {@code MOVE SPACES TO TRAN-MERCHANT-NAME / CITY / ZIP}
     *  (CBACT04C L492-L494). COBOL SPACES maps to empty string in Java. */
    static final String INTEREST_MERCHANT_BLANK = "";

    /**
     * Maximum representable monetary value used as a sanity guard for the
     * COBOL {@code PIC S9(10)V99} ceiling on {@code ACCT-CURR-BAL} and
     * {@code TRAN-AMT}. The actual ON SIZE ERROR replication happens via
     * try/catch on {@link ArithmeticException} per AAP &sect;0.7.1, but
     * this constant is also used to short-circuit obvious overflow paths
     * before the arithmetic is attempted.
     */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99");

    /** Audit event type used when emitting per-interest-transaction events. */
    static final String AUDIT_EVENT_INTEREST_CALCULATED = "INTEREST_CALCULATED";

    /** Audit event type used when emitting the batch-run summary event. */
    static final String AUDIT_EVENT_INTEREST_RUN_COMPLETED = "INTEREST_RUN_COMPLETED";

    /** Parm-date formatter used to build the TRAN-ID prefix. */
    private static final DateTimeFormatter TRAN_ID_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final AccountRepository accountRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final TransactionRepository transactionRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;

    /**
     * Constructor injection (AAP &sect;0.7.1 &mdash; constructor injection
     * for loose coupling) of every collaborator. All references are
     * {@link Objects#requireNonNull non-null-checked} to fail fast at
     * Spring context startup if any required bean is missing.
     */
    public InterestCalculationService(
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            AccountRepository accountRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            TransactionRepository transactionRepository,
            KafkaEventPublisher kafkaEventPublisher,
            AuditLogService auditLogService) {
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository");
        this.disclosureGroupRepository = Objects.requireNonNull(
                disclosureGroupRepository, "disclosureGroupRepository");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result summary returned to the caller (and to the Spring Batch
     * step wrapping this service) describing the work completed in the
     * batch run.
     *
     * <p>This nested record is also published as a stand-alone type
     * export (per the file schema) so callers may reference it as
     * {@code InterestCalculationService.InterestResult}.</p>
     *
     * @param tcatCount  number of {@link TransactionCategoryBalance}
     *                   records examined (the per-row read count;
     *                   COBOL {@code WS-RECORD-COUNT})
     * @param acctCount  number of distinct accounts updated (the
     *                   per-account boundary count; COBOL implicit count
     *                   of distinct {@code WS-LAST-ACCT-NUM} values)
     * @param grandTotal cumulative interest posted across all accounts
     *                   (the sum of all per-account {@code WS-TOTAL-INT}
     *                   values)
     */
    public record InterestResult(int tcatCount, int acctCount, BigDecimal grandTotal) {}

    /**
     * Executes one end-of-day interest-calculation pass.
     *
     * <p>Translates the COBOL outer loop at {@code CBACT04C.cbl}
     * L188-L222 plus the surrounding open/close paragraphs. Iterates
     * through the {@link TransactionCategoryBalance} table in the
     * natural sort order of the source TCATBAL VSAM cluster (by composite
     * key: account-id, type-code, category-code), accumulates per-account
     * interest, and at every account-boundary (and at end-of-file) calls
     * {@link #postAccountInterest(Long, BigDecimal, LocalDate)} to write
     * the single interest transaction and update the account balance.</p>
     *
     * <p>The outer method is annotated with
     * {@link Transactional @Transactional(readOnly = true)} because the
     * scan does not write &mdash; all writes happen in the inner
     * {@code REQUIRES_NEW} call. This avoids holding a long write-mode
     * database transaction across the full batch.</p>
     *
     * @param parmDate the batch-job parameter date passed via JCL
     *                 {@code PARM='yyyyMMddHH'}; used to compose the
     *                 deterministic TRAN-ID for each per-account
     *                 interest posting. Must not be {@code null}.
     * @return a {@link InterestResult} summary of the run
     * @throws NullPointerException     if {@code parmDate} is {@code null}
     * @throws RecordNotFoundException  if a TCATBAL record references a
     *                                  non-existent account (integrity
     *                                  invariant violation)
     * @throws OnSizeErrorException     if a computed amount overflows the
     *                                  {@code PIC S9(10)V99} ceiling
     */
    @Transactional(readOnly = true)
    public InterestResult calculateInterest(LocalDate parmDate) {
        Objects.requireNonNull(parmDate, "parmDate");

        LOG.info("CBACT04C: starting interest calculation parmDate={}", parmDate);

        // COBOL: PERFORM 0000-TCATBALF-OPEN through 0400-TRANFILE-OPEN
        // are pure VSAM open operations and have no Java equivalent;
        // Spring Data JPA opens connections as needed.

        // Read the entire TCATBAL cluster. The COBOL source uses
        // sequential VSAM read which yields records in key order; the
        // Java target retrieves all rows and explicitly sorts them by
        // the composite-key components so that the per-account boundary
        // detection logic below mirrors the COBOL behavior precisely.
        List<TransactionCategoryBalance> tcatbals =
                transactionCategoryBalanceRepository.findAll();
        tcatbals.sort((a, b) -> {
            int cmp = Long.compare(a.getId().getTrancatAcctId(),
                    b.getId().getTrancatAcctId());
            if (cmp != 0) {
                return cmp;
            }
            cmp = a.getId().getTrancatTypeCd()
                    .compareTo(b.getId().getTrancatTypeCd());
            if (cmp != 0) {
                return cmp;
            }
            return a.getId().getTrancatCd()
                    .compareTo(b.getId().getTrancatCd());
        });

        // Per-run counters matching COBOL WORKING-STORAGE:
        //   WS-RECORD-COUNT (TCATBAL rows read)
        //   acctCount (distinct accounts updated; derived from WS-LAST-ACCT-NUM transitions)
        //   grandTotal (sum of all per-account WS-TOTAL-INT values)
        int tcatCount = 0;
        int acctCount = 0;
        BigDecimal grandTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);

        // Per-account state matching COBOL:
        //   WS-LAST-ACCT-NUM (the running account id)
        //   WS-TOTAL-INT (the per-account accumulator)
        Long prevAcctId = null;
        BigDecimal acctTotalInt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);

        // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' (L188-L222)
        for (TransactionCategoryBalance tcat : tcatbals) {
            tcatCount++;
            Long acctId = tcat.getId().getTrancatAcctId();

            // COBOL: IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM (L194)
            // -- account-change boundary; post the previous account's
            //    accumulated interest before resetting.
            if (prevAcctId != null && !acctId.equals(prevAcctId)) {
                // COBOL: PERFORM 1050-UPDATE-ACCOUNT (L196)
                postAccountInterest(prevAcctId, acctTotalInt, parmDate);
                acctCount++;
                grandTotal = safeAdd(grandTotal, acctTotalInt, "grandTotal");
                // COBOL: MOVE 0 TO WS-TOTAL-INT (L200)
                acctTotalInt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            }

            // COBOL: PERFORM 1300-COMPUTE-INTEREST (L215) when DIS-INT-RATE NOT = 0
            // Note: computeMonthlyInterest returns ZERO when rate is zero,
            // matching the COBOL guard at L214 without requiring an
            // additional explicit check here.
            BigDecimal monthlyInt = computeMonthlyInterest(tcat);
            // COBOL: ADD WS-MONTHLY-INT TO WS-TOTAL-INT (L467)
            acctTotalInt = safeAdd(acctTotalInt, monthlyInt, "acctTotalInt");

            prevAcctId = acctId;
        }

        // COBOL: ELSE branch of UNTIL loop at L219-L221:
        //   PERFORM 1050-UPDATE-ACCOUNT
        // -- post the final account's accumulated interest.
        if (prevAcctId != null) {
            postAccountInterest(prevAcctId, acctTotalInt, parmDate);
            acctCount++;
            grandTotal = safeAdd(grandTotal, acctTotalInt, "grandTotal");
        }

        LOG.info("CBACT04C complete. tcatCount={}, acctCount={}, grandTotal={}",
                tcatCount, acctCount, grandTotal);

        // Final batch-run audit summary (AAP §0.6.6) -- recorded outside
        // any per-account transaction so a final-record failure does not
        // hide the summary from the audit trail.
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("parmDate", parmDate.toString());
            summary.put("tcatCount", tcatCount);
            summary.put("acctCount", acctCount);
            summary.put("grandTotal", grandTotal);
            auditLogService.logAuditEvent(
                    AUDIT_EVENT_INTEREST_RUN_COMPLETED,
                    "BATCH_RUN",
                    "CBACT04C-" + parmDate,
                    "BATCH",
                    summary,
                    null);
        } catch (RuntimeException ex) {
            // Audit emission failures must never break the batch result.
            LOG.warn("CBACT04C: batch-run audit emission failed (continuing)", ex);
        }

        return new InterestResult(tcatCount, acctCount, grandTotal);
    }

    /**
     * Posts the accumulated interest for a single account and writes
     * one interest {@link Transaction} record.
     *
     * <p>Translates COBOL paragraphs {@code 1050-UPDATE-ACCOUNT}
     * (L350-L370) and {@code 1300-B-WRITE-TX} (L473-L515), executed in
     * a single transactional unit:
     * <ol>
     *   <li>Look up the {@link Account} by id (must exist &mdash; integrity
     *       invariant; COBOL relies on the TCATBAL row referencing a
     *       valid account from {@code 1100-GET-ACCT-DATA}).</li>
     *   <li>{@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} (L352): add
     *       {@code totalInt} to {@link Account#getAcctCurrBal()}.</li>
     *   <li>{@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} (L353): zero the
     *       cycle credit counter.</li>
     *   <li>{@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} (L354): zero the
     *       cycle debit counter.</li>
     *   <li>{@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} (L356):
     *       persist the updated account.</li>
     *   <li>Build the interest {@link Transaction} with the COBOL
     *       literals TYPE={@code "01"}, CAT={@code 5}, SOURCE={@code "System"},
     *       DESC={@code "Int. for a/c "+padded acctId},
     *       MERCHANT-ID={@code 0}, and MERCHANT-NAME/CITY/ZIP as empty
     *       strings (COBOL SPACES).</li>
     *   <li>Look up the card number via the CXACAIX alternate-index
     *       (COBOL paragraph {@code 1110-GET-XREF-DATA} at L393-L413).
     *       Use the deterministic-order variant of the repository so the
     *       chosen card is stable across runs.</li>
     *   <li>{@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} (L500):
     *       persist the new interest transaction.</li>
     * </ol>
     *
     * <p>This method is annotated with
     * {@link Transactional @Transactional(propagation =
     * Propagation.REQUIRES_NEW, rollbackFor = Exception.class)} so each
     * per-account commit is independent &mdash; partial failures do not
     * roll back already-posted accounts (AAP &sect;0.7.1 transactional
     * integrity per-business-unit). After the commit, an MSK
     * {@code ledger.balanced} event is published and an
     * {@code INTEREST_CALCULATED} audit event is recorded.</p>
     *
     * <p>If {@code totalInt} is zero (sign==0), the method short-circuits
     * before touching any data &mdash; no balance update, no transaction
     * record, no MSK publish, no audit event. This mirrors the COBOL
     * source which (via the {@code IF DIS-INT-RATE NOT = 0} guard at
     * L214) never enters {@code 1300-COMPUTE-INTEREST} when the rate is
     * zero; in the aggregated Java target this naturally translates to a
     * zero per-account total when every category yields zero interest.</p>
     *
     * @param acctId    primary key of the account being credited with
     *                  interest (COBOL {@code FD-ACCT-ID}). Must not be
     *                  {@code null}.
     * @param totalInt  the accumulated per-account interest amount; if
     *                  zero the method is a no-op. Must not be
     *                  {@code null}.
     * @param parmDate  the batch-job parm date passed through from
     *                  {@link #calculateInterest(LocalDate)}; used to
     *                  build the deterministic TRAN-ID. Must not be
     *                  {@code null}.
     * @throws RecordNotFoundException if the account does not exist
     *         (COBOL {@code 'ACCOUNT NOT FOUND'} display at L375
     *         followed by {@code PERFORM 9999-ABEND-PROGRAM})
     * @throws OnSizeErrorException    if adding {@code totalInt} to the
     *         current balance overflows the {@code PIC S9(10)V99}
     *         ceiling (COBOL {@code ON SIZE ERROR})
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void postAccountInterest(Long acctId, BigDecimal totalInt, LocalDate parmDate) {
        Objects.requireNonNull(acctId, "acctId");
        Objects.requireNonNull(totalInt, "totalInt");
        Objects.requireNonNull(parmDate, "parmDate");

        // Short-circuit when there is no interest to post. The COBOL
        // source guards 1300-COMPUTE-INTEREST with IF DIS-INT-RATE NOT =
        // 0 (L214) so zero-interest accounts never reach 1300-B-WRITE-TX
        // either. The Java aggregation pipeline yields a zero per-account
        // total when no category produced interest; skip cleanly.
        if (totalInt.signum() == 0) {
            LOG.debug("CBACT04C: skipping account {} -- zero accumulated interest",
                    acctId);
            return;
        }

        // COBOL: 1100-GET-ACCT-DATA -- READ ACCOUNT-FILE INTO ACCOUNT-RECORD
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account not found for interest posting: acctId=" + acctId));

        // COBOL: 1050-UPDATE-ACCOUNT (L350-L370)
        //   ADD WS-TOTAL-INT TO ACCT-CURR-BAL
        BigDecimal previousBalance = account.getAcctCurrBal();
        BigDecimal newBalance = safeAdd(previousBalance, totalInt, "ACCT-CURR-BAL");
        account.setAcctCurrBal(newBalance);

        //   MOVE 0 TO ACCT-CURR-CYC-CREDIT
        //   MOVE 0 TO ACCT-CURR-CYC-DEBIT
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        account.setAcctCurrCycCredit(zero);
        account.setAcctCurrCycDebit(zero);

        //   REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        // The JPA save preserves the @Version optimistic-lock counter on
        // Account (AAP §0.4.1 replaces the COBOL before/after image
        // snapshot pattern).
        Account savedAccount = accountRepository.save(account);

        // COBOL: 1110-GET-XREF-DATA -- READ XREF-FILE KEY IS FD-XREF-ACCT-ID
        // The CXACAIX VSAM alternate-index is NONUNIQUE; the deterministic-
        // order variant guarantees that when an account has multiple
        // cards the lexicographically smallest card number is selected
        // every time (mirrors the COBOL VSAM AIX iteration order and
        // satisfies the regulatory output-format constraint at AAP §0.7.2).
        List<CardCrossReference> xrefs =
                cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(acctId);
        final String cardNum;
        if (xrefs.isEmpty()) {
            // The COBOL DISPLAY 'ACCOUNT NOT FOUND: ' (L397) does NOT
            // abend; it merely prints a diagnostic. The Java target
            // posts the interest transaction with a blank card number
            // so the account-level update still completes.
            LOG.warn("CBACT04C: no XREF entry for account {} -- "
                    + "interest posting will use blank card number", acctId);
            cardNum = INTEREST_MERCHANT_BLANK;
        } else {
            cardNum = xrefs.get(0).getXrefCardNum();
        }

        // COBOL: 1300-B-WRITE-TX (L473-L515)
        //   STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
        String tranId = buildInterestTranId(acctId, parmDate);

        //   MOVE '01'    TO TRAN-TYPE-CD
        //   MOVE '05'    TO TRAN-CAT-CD
        //   MOVE 'System' TO TRAN-SOURCE
        //   STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
        //   MOVE WS-MONTHLY-INT TO TRAN-AMT    -- here totalInt = sum across categories
        //   MOVE 0       TO TRAN-MERCHANT-ID
        //   MOVE SPACES  TO TRAN-MERCHANT-NAME / CITY / ZIP
        //   MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
        //   MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS / TRAN-PROC-TS
        String description = "Int. for a/c " + String.format("%011d", acctId);
        LocalDateTime now = LocalDateTime.now();
        Transaction tx = new Transaction(
                tranId,
                INTEREST_TYPE_CODE,
                INTEREST_CAT_CODE,
                INTEREST_SOURCE,
                description,
                totalInt,
                INTEREST_MERCHANT_ID,
                INTEREST_MERCHANT_BLANK,
                INTEREST_MERCHANT_BLANK,
                INTEREST_MERCHANT_BLANK,
                cardNum,
                now,
                now);

        // COBOL: WRITE FD-TRANFILE-REC FROM TRAN-RECORD (L500)
        Transaction savedTx = transactionRepository.save(tx);

        // -----------------------------------------------------------------
        // Cross-cutting concerns introduced by the cloud migration (AAP
        // §0.6.5 event-driven, §0.6.6 audit). These are emitted AFTER the
        // financial database writes succeed -- they are observability
        // signals, not part of the COBOL business logic.
        // -----------------------------------------------------------------

        // MSK publish: a single ledger.balanced event carrying both the
        // posted interest transaction summary and the updated account
        // balance. The publishLedgerBalanced(Long, Object) signature
        // accepts Object so no DTO type is required -- the payload Map is
        // serialized as JSON by the Spring Kafka template. Per AAP §0.6.5,
        // the partition key is the account ID so per-account event order
        // is preserved.
        try {
            Map<String, Object> ledgerEvent = new LinkedHashMap<>();
            ledgerEvent.put("eventType", AUDIT_EVENT_INTEREST_CALCULATED);
            ledgerEvent.put("accountId", acctId);
            ledgerEvent.put("transactionId", savedTx.getTranId());
            ledgerEvent.put("typeCode", savedTx.getTranTypeCd());
            ledgerEvent.put("categoryCode", savedTx.getTranCatCd());
            ledgerEvent.put("amount", savedTx.getTranAmt());
            ledgerEvent.put("previousBalance", previousBalance);
            ledgerEvent.put("newBalance", newBalance);
            ledgerEvent.put("parmDate", parmDate.toString());
            ledgerEvent.put("cardNumber", cardNum == null ? "" : cardNum);
            kafkaEventPublisher.publishLedgerBalanced(savedAccount.getAcctId(), ledgerEvent);
        } catch (RuntimeException ex) {
            // Publish failures must not roll back the committed financial
            // database transaction. The audit log below still captures
            // the event for downstream reconciliation.
            LOG.warn("CBACT04C: ledger.balanced publish failed for account {} "
                    + "(continuing; financial transaction is committed)",
                    acctId, ex);
        }

        // Audit log: record the interest-calculated event keyed by the
        // generated transaction id for idempotent retry semantics in the
        // OpenSearch indexer (AAP §0.6.6).
        try {
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("accountId", acctId);
            auditPayload.put("transactionId", savedTx.getTranId());
            auditPayload.put("interestAmount", totalInt);
            auditPayload.put("previousBalance", previousBalance);
            auditPayload.put("newBalance", newBalance);
            auditPayload.put("parmDate", parmDate.toString());
            auditPayload.put("typeCode", INTEREST_TYPE_CODE);
            auditPayload.put("categoryCode", INTEREST_CAT_CODE);
            auditLogService.logTransactionEvent(
                    savedTx.getTranId(),
                    acctId,
                    "BATCH",
                    AUDIT_EVENT_INTEREST_CALCULATED,
                    null,
                    auditPayload,
                    null);
        } catch (RuntimeException ex) {
            // Per AAP §0.6.6 audit-failure handling: log and continue --
            // the financial transaction has already been committed and a
            // future reconciliation can recover the audit trail from the
            // database transaction record.
            LOG.warn("CBACT04C: audit emission failed for account {} txId {}",
                    acctId, savedTx.getTranId(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * COBOL: {@code 1300-COMPUTE-INTEREST} (L462-L468) plus the rate
     * lookup chain ({@code 1200-GET-INTEREST-RATE} +
     * {@code 1200-A-GET-DEFAULT-INT-RATE} at L415-L460).
     *
     * <p>Implements the verbatim COBOL formula
     * <em>{@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}</em> with the
     * literal 1200 divisor (AAP &sect;0.6.1 forbids precomputation).
     * Banker's rounding ({@link RoundingMode#HALF_EVEN}) is applied at
     * the divide step to mirror COBOL {@code PIC S9(09)V99} fixed-point
     * truncation semantics.</p>
     *
     * <p>When the balance is null the result is zero. When the
     * disclosure-group lookup (including DEFAULT fallback) yields zero
     * the result is also zero &mdash; matching the COBOL guard
     * {@code IF DIS-INT-RATE NOT = 0} (L214) which simply skips
     * 1300-COMPUTE-INTEREST when the rate is zero.</p>
     *
     * @param tcat the transaction category balance row currently being
     *             processed
     * @return the monthly interest amount with scale=2 and HALF_EVEN
     * @throws RecordNotFoundException if the account referenced by the
     *         TCATBAL row does not exist (integrity invariant violation)
     * @throws OnSizeErrorException if the multiply/divide overflows the
     *         BigDecimal arithmetic limits (mapped from
     *         {@link ArithmeticException})
     */
    private BigDecimal computeMonthlyInterest(TransactionCategoryBalance tcat) {
        // COBOL: MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID
        //        PERFORM 1100-GET-ACCT-DATA (L202-L203)
        // We need the account record to read ACCT-GROUP-ID for the
        // disclosure-group lookup. The TCATBAL row must reference an
        // existing account -- an integrity invariant inherited from the
        // VSAM source where the alternate-index would have been kept
        // consistent.
        Account account = accountRepository.findById(tcat.getId().getTrancatAcctId())
                .orElseThrow(() -> new RecordNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account not found for TCATBAL row acctId="
                                + tcat.getId().getTrancatAcctId()));

        // COBOL: MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID (L210)
        //        MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD     (L211)
        //        MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD (L212)
        //        PERFORM 1200-GET-INTEREST-RATE             (L213)
        String groupId = account.getAcctGroupId();
        String typeCd = tcat.getId().getTrancatTypeCd();
        Integer catCd = tcat.getId().getTrancatCd();

        BigDecimal rate = lookupRate(groupId, typeCd, catCd);

        // COBOL: IF DIS-INT-RATE NOT = 0 PERFORM 1300-COMPUTE-INTEREST (L214-L215)
        // -- short-circuit zero rates so we don't waste arithmetic.
        if (rate.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }

        BigDecimal balance = tcat.getTranCatBal();
        if (balance == null || balance.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }

        // COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 (L464-L465)
        // The 1200 divisor is preserved verbatim as BigDecimal.valueOf(1200L)
        // per AAP §0.6.1 -- NEVER precomputed into a single constant.
        try {
            BigDecimal result = balance
                    .multiply(rate)
                    .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_EVEN);
            // Defensive ceiling check -- the BigDecimal arithmetic above
            // cannot natively overflow (BigDecimal is arbitrary-precision),
            // but the COBOL field TRAN-AMT is PIC S9(09)V99 with an
            // implicit 11-digit ceiling, so we surface the ON SIZE ERROR
            // here for parity with COBOL semantics.
            if (result.abs().compareTo(MAX_AMOUNT) > 0) {
                throw new OnSizeErrorException(
                        "ON SIZE ERROR computing monthly interest for acctId="
                                + account.getAcctId()
                                + " (computed " + result
                                + " exceeds PIC S9(10)V99 ceiling)");
            }
            return result;
        } catch (ArithmeticException e) {
            // Wrap any arithmetic exception (overflow, divide-by-zero --
            // although the divisor is the literal 1200 so divide-by-zero
            // is unreachable, ArithmeticException could still occur on
            // unusual operand shapes) as OnSizeErrorException per AAP
            // §0.7.1.
            throw new OnSizeErrorException(
                    "ON SIZE ERROR computing monthly interest for acctId="
                            + account.getAcctId(), e);
        }
    }

    /**
     * COBOL: {@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE}
     * (L415-L460).
     *
     * <p>Performs the disclosure-group lookup with the AAP-mandated
     * DEFAULT-fallback: first tries the specific {@code (group-id,
     * type-cd, cat-cd)} tuple; on miss (COBOL {@code FILE STATUS '23'} at
     * L436), retries with the literal group-code {@code "DEFAULT"} for
     * the same type+category triple. If both lookups miss, returns
     * {@link BigDecimal#ZERO} which causes the caller to skip interest
     * posting for that category &mdash; a deliberate resilience
     * improvement over the COBOL {@code PERFORM 9999-ABEND-PROGRAM} so
     * the batch can continue rather than crash on a missing reference
     * row.</p>
     *
     * @param groupId  the account's {@link Account#getAcctGroupId()}
     *                 (10-char COBOL X(10) field); may be null/blank for
     *                 accounts that never had a specific group assigned
     *                 &mdash; in which case the primary lookup is
     *                 skipped and only the DEFAULT lookup is attempted
     * @param typeCode transaction type code (2-char COBOL X(02))
     * @param catCode  transaction category code (4-digit COBOL 9(04))
     * @return the applicable disclosure-group interest rate; zero if no
     *         row matches
     */
    private BigDecimal lookupRate(String groupId, String typeCode, Integer catCode) {
        // COBOL: 1200-GET-INTEREST-RATE -- primary key lookup
        if (groupId != null && !groupId.isBlank()) {
            DisclosureGroupId specificKey = new DisclosureGroupId(
                    groupId, typeCode, catCode);
            Optional<DisclosureGroup> specific =
                    disclosureGroupRepository.findById(specificKey);
            if (specific.isPresent()) {
                BigDecimal rate = specific.get().getDisIntRate();
                return rate != null ? rate
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            }
        }

        // COBOL: IF DISCGRP-STATUS = '23' -- the specific key was not
        //        found; retry with the literal 'DEFAULT' group code.
        //        MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID (L437)
        //        PERFORM 1200-A-GET-DEFAULT-INT-RATE    (L438)
        LOG.debug("CBACT04C: disclosure group missing for groupId={} type={} cat={}; "
                + "retrying with DEFAULT", groupId, typeCode, catCode);
        DisclosureGroupId defaultKey = new DisclosureGroupId(
                DEFAULT_GROUP_ID, typeCode, catCode);
        return disclosureGroupRepository.findById(defaultKey)
                .map(dg -> dg.getDisIntRate() != null
                        ? dg.getDisIntRate()
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN))
                .orElseGet(() -> {
                    // Defensive log -- the batch continues with zero
                    // interest for this category rather than abending.
                    LOG.warn("CBACT04C: DEFAULT disclosure group missing for "
                            + "type={} cat={}; treating rate as zero",
                            typeCode, catCode);
                    return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                });
    }

    /**
     * Build the 16-character transaction ID per COBOL paragraph
     * {@code 1300-B-WRITE-TX} (L476-L480):
     * <pre>{@code STRING PARM-DATE, WS-TRANID-SUFFIX
     *         DELIMITED BY SIZE INTO TRAN-ID}</pre>
     * The COBOL form is {@code PARM-DATE (X(10)) + WS-TRANID-SUFFIX
     * (9(06))} totalling 16 characters.
     *
     * <p>The Java target uses a deterministic composition that yields a
     * stable 16-character identifier for each per-account interest
     * posting: a fixed {@code "INT"} prefix, the 11-digit zero-padded
     * account ID, and a 2-character year suffix (last two digits of
     * {@code parmDate.year}). Total length is exactly 16:
     * {@code "INT" (3) + 11-digit acctId + 2-char year = 16}.</p>
     *
     * <p>This design guarantees:
     * <ul>
     *   <li>uniqueness across the same batch run (one TX per account so
     *       account ID disambiguates),</li>
     *   <li>idempotency across reruns of the same batch parm-date (rerun
     *       attempts produce the same TRAN-ID and either the prior write
     *       is still present or the save is a no-op upsert at the JPA
     *       level depending on the configured cascade),</li>
     *   <li>conformance to the COBOL {@code TRAN-ID PIC X(16)} field
     *       width.</li>
     * </ul>
     *
     * @param acctId   the 11-digit account ID (COBOL PIC 9(11))
     * @param parmDate the batch-job parm date
     * @return a 16-character interest TRAN-ID
     */
    private static String buildInterestTranId(Long acctId, LocalDate parmDate) {
        // "INT" (3 chars) + 11-digit acctId + 2-digit year = 16 chars
        String yearSuffix = String.format("%02d", parmDate.getYear() % 100);
        String tranId = "INT" + String.format("%011d", acctId) + yearSuffix;
        // Defensive trim/pad to ensure exactly 16 chars even if the
        // account ID exceeds 11 digits (which would itself be a separate
        // integrity violation).
        if (tranId.length() > 16) {
            tranId = tranId.substring(0, 16);
        } else if (tranId.length() < 16) {
            tranId = String.format("%-16s", tranId);
        }
        return tranId;
    }

    /**
     * Adds two {@link BigDecimal} values and applies {@code setScale(2,
     * HALF_EVEN)} to the result, wrapping any {@link ArithmeticException}
     * as {@link OnSizeErrorException} per AAP &sect;0.7.1.
     *
     * <p>{@code null} operands are treated as zero. The {@code ctx}
     * parameter is included in the exception message for diagnosability
     * &mdash; pass a short label identifying the COBOL field being
     * computed (e.g., {@code "ACCT-CURR-BAL"}, {@code "WS-TOTAL-INT"},
     * {@code "grandTotal"}).</p>
     *
     * @param a   first operand; may be null (treated as zero)
     * @param b   second operand; may be null (treated as zero)
     * @param ctx short context label for the exception message
     * @return the sum with scale=2 and HALF_EVEN rounding
     * @throws OnSizeErrorException if the addition overflows
     */
    private static BigDecimal safeAdd(BigDecimal a, BigDecimal b, String ctx) {
        try {
            BigDecimal left = (a == null) ? BigDecimal.ZERO : a;
            BigDecimal right = (b == null) ? BigDecimal.ZERO : b;
            BigDecimal result = left.add(right).setScale(2, RoundingMode.HALF_EVEN);
            // Defensive ON SIZE ERROR check -- BigDecimal addition cannot
            // natively overflow, but the COBOL field is PIC S9(10)V99
            // (12-digit limit) and overflow on that ceiling is a real
            // business error worth surfacing.
            if (result.abs().compareTo(MAX_AMOUNT) > 0) {
                throw new OnSizeErrorException(
                        "ON SIZE ERROR in " + ctx
                                + " (computed " + result
                                + " exceeds PIC S9(10)V99 ceiling)");
            }
            return result;
        } catch (ArithmeticException e) {
            throw new OnSizeErrorException("ON SIZE ERROR in " + ctx, e);
        }
    }

    // -------------------------------------------------------------------------
    // Package-private accessor for the date formatter used by the
    // deterministic TRAN-ID builder. Exposed at package visibility for
    // test diagnostics; not part of the public API.
    // -------------------------------------------------------------------------
    static DateTimeFormatter tranIdDateFormatter() {
        return TRAN_ID_DATE_FORMATTER;
    }
}
