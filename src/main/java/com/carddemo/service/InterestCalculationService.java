package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.entity.Account;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Application service implementing the monthly <strong>interest calculation</strong>
 * batch logic &mdash; the Java&nbsp;25 / Spring&nbsp;Boot translation of the legacy
 * batch COBOL program {@code CBACT04C} ({@code app/cbl/CBACT04C.cbl}, 652&nbsp;LOC,
 * frozen source commit SHA {@code 27d6c6f}).
 *
 * <p>{@code CBACT04C} is the interest calculator of the CardDemo batch pipeline
 * (JCL step {@code INTCALC}). It reads the transaction-category-balance file
 * ({@code TCATBAL}) in account order and, for every category balance of an
 * account, resolves the applicable disclosure-group interest rate, computes the
 * monthly interest, writes one interest {@code Transaction} per category, and at
 * each account boundary rolls the accrued interest into the account balance while
 * resetting the current-cycle credit and debit accumulators.</p>
 *
 * <h2>Batch-tier reuse (AAP G3: {@code CALL} &rarr; bean injection)</h2>
 * <p>The mainframe design is a single monolithic {@code PERFORM UNTIL END-OF-FILE}
 * loop. In the modular target that read loop belongs to the Spring Batch layer:
 * {@code batch/InterestCalculationJob} drives a chunk-oriented step whose reader
 * emits one account at a time and whose processor delegates the per-account
 * computation to {@link #applyInterestToAccount(Long)} on this
 * constructor-injected bean. Extracting the business logic here (rather than in
 * the job) keeps it independently unit-testable and lets the online tier reuse the
 * same rules if required.</p>
 *
 * <h2>COBOL&nbsp;&rarr;&nbsp;Java paragraph mapping</h2>
 * <table border="1">
 *   <caption>Traceability of {@code CBACT04C} paragraphs to methods of this class</caption>
 *   <tr><th>COBOL paragraph</th><th>Behaviour</th><th>Java method</th></tr>
 *   <tr>
 *     <td>{@code 1200-GET-INTEREST-RATE} / {@code 1200-A-GET-DEFAULT-INT-RATE}</td>
 *     <td>Keyed read of {@code DISCGRP} by (group, type, category); on
 *         {@code FILE STATUS '23'} substitute the literal group {@code 'DEFAULT'}
 *         and re-read.</td>
 *     <td>{@link #resolveInterestRate(String, String, Integer)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 1300-COMPUTE-INTEREST}</td>
 *     <td>{@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.</td>
 *     <td>{@link #calculateMonthlyInterest(BigDecimal, BigDecimal)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 1300-B-WRITE-TX}</td>
 *     <td>Build and write one interest transaction per category
 *         (type {@code '01'}, category {@code 5}, source {@code 'System'}).</td>
 *     <td>{@link #applyInterestToAccount(Long)} (via the private transaction builder)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 1050-UPDATE-ACCOUNT}</td>
 *     <td>{@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}; zero the current-cycle credit
 *         and debit; {@code REWRITE} the account.</td>
 *     <td>{@link #applyInterestToAccount(Long)} (account-boundary tail)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 1400-COMPUTE-FEES}</td>
 *     <td>Marked "To be implemented" in the source &mdash; a no-op. Deliberately
 *         <em>not</em> implemented here (no scope expansion, Gate&nbsp;7).</td>
 *     <td>&mdash;</td>
 *   </tr>
 * </table>
 *
 * <h2>Decimal fidelity (AAP G2, &sect;0.8.2)</h2>
 * <p>Every monetary computation uses {@link java.math.BigDecimal}; no
 * {@code float} or {@code double} appears anywhere in this class. The monthly
 * interest reproduces the COBOL {@code COMPUTE} exactly &mdash;
 * {@code balance * rate / 1200} &mdash; rounded to scale&nbsp;2 with
 * {@link java.math.RoundingMode#HALF_UP}. The account balance is likewise kept at
 * scale&nbsp;2, and the current-cycle credit/debit accumulators are reset to
 * {@code 0.00}, mirroring {@code MOVE 0} against the {@code PIC S9(10)V99}
 * fields.</p>
 *
 * <h2>Transaction boundary (AAP &sect;0.8.4)</h2>
 * <p>{@link #applyInterestToAccount(Long)} is annotated
 * {@code @Transactional(rollbackFor = Exception.class)} so that the interest
 * transactions written for an account and the account-balance rewrite commit
 * atomically: if any write fails, none is persisted. This preserves the
 * all-or-nothing semantics an account boundary had under the original file
 * processing.</p>
 *
 * <h2>Concurrency &amp; logging</h2>
 * <p>The service is a stateless, thread-safe Spring singleton using constructor
 * injection. Structured logs are emitted only at {@code DEBUG} level and carry no
 * monetary values and no card numbers &mdash; only the account identifier and a
 * count of interest transactions written &mdash; consistent with the migration's
 * observability and data-protection rules.</p>
 *
 * @see TransactionCategoryBalanceRepository
 * @see DisclosureGroupRepository
 * @see AccountRepository
 * @see TransactionRepository
 * @see CrossReferenceService
 */
@Service
public class InterestCalculationService {

    /** Structured logger; emits only DEBUG counts, never monetary values or card numbers. */
    private static final Logger log = LoggerFactory.getLogger(InterestCalculationService.class);

    /**
     * Transaction type code stamped on every generated interest transaction.
     * COBOL {@code 1300-B-WRITE-TX}: {@code MOVE '01' TO TRAN-TYPE-CD}.
     */
    private static final String TRAN_TYPE_INTEREST = "01";

    /**
     * Transaction category code stamped on every generated interest transaction.
     * COBOL {@code 1300-B-WRITE-TX}: {@code MOVE '05' TO TRAN-CAT-CD}; the target
     * {@code TRAN-CAT-CD} column is numeric, so the literal maps to {@link Integer}
     * {@code 5}.
     */
    private static final Integer TRAN_CAT_INTEREST = 5;

    /**
     * Origination source stamped on every generated interest transaction.
     * COBOL {@code 1300-B-WRITE-TX}: {@code MOVE 'System' TO TRAN-SOURCE}.
     */
    private static final String TRAN_SOURCE_SYSTEM = "System";

    /**
     * Description prefix for a generated interest transaction. COBOL
     * {@code 1300-B-WRITE-TX} builds {@code TRAN-DESC} as
     * {@code STRING 'Int. for a/c ', ACCT-ID}, so the full description is this
     * prefix concatenated with the account identifier.
     */
    private static final String DESC_PREFIX = "Int. for a/c ";

    /**
     * Fallback account-group identifier used when no disclosure group exists for
     * the account's own group. COBOL {@code 1200-GET-INTEREST-RATE} substitutes
     * {@code 'DEFAULT'} on {@code FILE STATUS '23'} and re-reads
     * ({@code 1200-A-GET-DEFAULT-INT-RATE}).
     */
    private static final String DEFAULT_GROUP = "DEFAULT";

    /**
     * Divisor converting an annual percentage rate to a monthly fraction:
     * {@code (balance * annualRatePercent) / 1200} = {@code balance *
     * (annualRatePercent / 100) / 12}. Reproduces the literal {@code / 1200} of
     * the COBOL {@code 1300-COMPUTE-INTEREST} {@code COMPUTE} statement.
     */
    private static final BigDecimal DIVISOR_1200 = BigDecimal.valueOf(1200);

    /**
     * Decimal scale of every monetary value produced by this service (two
     * fractional digits), matching the COBOL {@code Vnn} pictures
     * ({@code PIC S9(n)V99}) and the {@code NUMERIC(p,2)} columns.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * Format mask for the 16-digit, left-zero-padded {@code TRAN-ID} key
     * ({@code PIC 9(16)} in the legacy working storage).
     */
    private static final String TRAN_ID_FORMAT = "%016d";

    /**
     * Blank value written to the merchant name/city/ZIP fields of an interest
     * transaction. COBOL {@code 1300-B-WRITE-TX} moves {@code SPACES} into the
     * fixed-width {@code TRAN-MERCHANT-*} fields; in the relational model a blank
     * fixed-width field is represented as the empty string, and the file-I/O
     * boundary re-pads to the picture width for byte-equivalent output.
     */
    private static final String BLANK = "";

    /**
     * Formatter for the 26-character DB2-style timestamp text
     * ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}) written to {@code TRAN-ORIG-TS} and
     * {@code TRAN-PROC-TS}. The six {@code S} pattern letters always render six
     * fractional-second digits, so the result is exactly 26 characters &mdash; the
     * width of the COBOL {@code PIC X(26)} timestamp fields. {@link DateTimeFormatter}
     * instances are immutable and thread-safe.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** Repository over {@code TCATBAL} category balances (grouped per account). */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** Repository over the {@code DISCGRP} disclosure-group / interest-rate table. */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /** Repository over the {@code ACCOUNT} master (read then rewrite at the boundary). */
    private final AccountRepository accountRepository;

    /** Repository over the {@code TRANSACT} master (one interest row saved per category). */
    private final TransactionRepository transactionRepository;

    /** Shared cross-reference service resolving the account's card number and next id. */
    private final CrossReferenceService crossReferenceService;

    /**
     * Creates the service with its collaborating repositories and shared
     * cross-reference service.
     *
     * <p>Spring injects every dependency through this single constructor (so no
     * {@code @Autowired} annotation is required), and each is stored in a
     * {@code final} field, making the service effectively immutable and safe for
     * concurrent request and batch threads. This constructor-injection wiring is
     * the idiomatic replacement for the static COBOL {@code CALL} / VSAM linkage
     * (AAP&nbsp;&sect;0.4.3).</p>
     *
     * @param transactionCategoryBalanceRepository repository of per-account
     *        category balances ({@code TCATBAL}); must not be {@code null}
     * @param disclosureGroupRepository            repository of disclosure-group
     *        interest rates ({@code DISCGRP}); must not be {@code null}
     * @param accountRepository                    repository of account masters
     *        ({@code ACCOUNT}); must not be {@code null}
     * @param transactionRepository                repository of posted
     *        transactions ({@code TRANSACT}); must not be {@code null}
     * @param crossReferenceService                shared service resolving an
     *        account's card number and the next transaction id; must not be
     *        {@code null}
     */
    public InterestCalculationService(
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            CrossReferenceService crossReferenceService) {
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.crossReferenceService = crossReferenceService;
    }

    /**
     * Computes the monthly interest for a single category balance at a given
     * annual rate &mdash; the Java equivalent of the COBOL
     * {@code 1300-COMPUTE-INTEREST} statement
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
     *
     * <p>The result is rounded to scale&nbsp;2 using
     * {@link RoundingMode#HALF_UP}, reproducing the COBOL rounding of the
     * {@code PIC S9(09)V99} target field. The method is pure (no I/O, no shared
     * state) and therefore trivially unit-testable; for example a balance of
     * {@code 1000.00} at an annual rate of {@code 12.00} yields {@code 10.00}
     * ({@code 1000 * 12 / 1200}).</p>
     *
     * <p>Both arguments are treated null-safely: a {@code null} balance or rate is
     * interpreted as {@link BigDecimal#ZERO}, so the method never throws a
     * {@link NullPointerException} and yields {@code 0.00} whenever either operand
     * is absent.</p>
     *
     * @param categoryBalance the category running balance
     *        ({@code TRAN-CAT-BAL}); {@code null} is treated as zero
     * @param annualRate      the annual interest rate as a percentage
     *        ({@code DIS-INT-RATE}); {@code null} is treated as zero
     * @return the monthly interest, always at scale&nbsp;2
     */
    public BigDecimal calculateMonthlyInterest(BigDecimal categoryBalance, BigDecimal annualRate) {
        BigDecimal balance = (categoryBalance == null) ? BigDecimal.ZERO : categoryBalance;
        BigDecimal rate = (annualRate == null) ? BigDecimal.ZERO : annualRate;
        return balance.multiply(rate).divide(DIVISOR_1200, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Resolves the annual interest rate for an (account-group, transaction-type,
     * transaction-category) combination, reproducing COBOL
     * {@code 1200-GET-INTEREST-RATE} and its {@code 1200-A-GET-DEFAULT-INT-RATE}
     * fallback.
     *
     * <p>The disclosure group is first looked up by the account's own group id. If
     * no row exists &mdash; the equivalent of the VSAM {@code FILE STATUS '23'}
     * "record not found" branch &mdash; the lookup is retried with the literal
     * account group {@code 'DEFAULT'}. If neither the specific nor the default row
     * exists (or a matched row carries no rate), {@link BigDecimal#ZERO} is
     * returned so the caller simply accrues no interest for that category, rather
     * than failing.</p>
     *
     * @param acctGroupId the account's own group identifier
     *        ({@code ACCT-GROUP-ID} &rarr; {@code DIS-ACCT-GROUP-ID})
     * @param tranTypeCd  the transaction type code
     *        ({@code TRANCAT-TYPE-CD} &rarr; {@code DIS-TRAN-TYPE-CD})
     * @param tranCatCd   the transaction category code
     *        ({@code TRANCAT-CD} &rarr; {@code DIS-TRAN-CAT-CD})
     * @return the applicable annual interest rate, or {@link BigDecimal#ZERO} when
     *         neither the specific nor the {@code 'DEFAULT'} disclosure group
     *         supplies one; never {@code null}
     */
    public BigDecimal resolveInterestRate(String acctGroupId, String tranTypeCd, Integer tranCatCd) {
        Optional<DisclosureGroup> group = disclosureGroupRepository.findById(
                new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd));
        if (group.isEmpty()) {
            group = disclosureGroupRepository.findById(
                    new DisclosureGroupId(DEFAULT_GROUP, tranTypeCd, tranCatCd));
        }
        return group.map(DisclosureGroup::getDisIntRate).orElse(BigDecimal.ZERO);
    }

    /**
     * Applies monthly interest to a single account, reproducing the per-account
     * body of the {@code CBACT04C} main loop together with its account-boundary
     * update ({@code 1050-UPDATE-ACCOUNT}).
     *
     * <p>Processing steps, in COBOL order:</p>
     * <ol>
     *   <li>Load the account master ({@code 1100-GET-ACCT-DATA}); a missing
     *       account raises a {@link ResourceNotFoundException}, the Java form of
     *       the COBOL "ACCOUNT NOT FOUND" {@code INVALID KEY} path.</li>
     *   <li>Resolve the account's cross-reference card number
     *       ({@code 1110-GET-XREF-DATA}) via {@link CrossReferenceService}; a
     *       missing cross-reference likewise surfaces as a
     *       {@link ResourceNotFoundException}.</li>
     *   <li>Seed the running transaction-id counter from the shared next-id
     *       generator (the modern replacement for {@code WS-TRANID-SUFFIX}); each
     *       written transaction consumes the next 16-digit id.</li>
     *   <li>Start the account's total interest at zero ({@code MOVE 0 TO
     *       WS-TOTAL-INT}).</li>
     *   <li>For every category balance of the account
     *       ({@code 1000-TCATBALF-GET-NEXT} loop): resolve the interest rate
     *       ({@code 1200-*}); when the rate is non-zero ({@code IF DIS-INT-RATE NOT
     *       = 0}) compute the monthly interest ({@code 1300-COMPUTE-INTEREST}),
     *       accumulate it into the account total, and write one interest
     *       transaction ({@code 1300-B-WRITE-TX}).</li>
     *   <li>At the account boundary ({@code 1050-UPDATE-ACCOUNT}) add the total
     *       interest to the current balance, reset the current-cycle credit and
     *       debit to {@code 0.00}, and persist the account.</li>
     * </ol>
     *
     * <p>The method is annotated {@code @Transactional(rollbackFor =
     * Exception.class)} so the interest transactions and the account-balance
     * rewrite commit atomically; any failure rolls back the whole account's work.</p>
     *
     * @param acctId the identifier of the account to process
     *        ({@code TRANCAT-ACCT-ID} / {@code ACCT-ID}); must not be {@code null}
     * @return the total interest applied to the account, always at scale&nbsp;2
     *         ({@code 0.00} when no category carried a non-zero rate)
     * @throws ResourceNotFoundException if the account, or its card
     *         cross-reference, cannot be found
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal applyInterestToAccount(Long acctId) {
        // 1100-GET-ACCT-DATA: keyed read of the account master.
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found for id: " + acctId));

        // 1110-GET-XREF-DATA: resolve the account's cross-reference card number
        // (XREF-CARD-NUM) once; every interest transaction for this account carries it.
        String cardNumber = crossReferenceService.resolvePrimaryCardNumber(acctId);

        // Seed the running transaction-id counter. The shared generator yields the
        // first free 16-digit id (the modern equivalent of PARM-DATE + WS-TRANID-SUFFIX).
        long nextTransactionId = Long.parseLong(crossReferenceService.generateNextTransactionId());

        // MOVE 0 TO WS-TOTAL-INT: the account's accrued interest starts at zero.
        BigDecimal totalInterest = BigDecimal.ZERO;

        List<TransactionCategoryBalance> categoryBalances =
                transactionCategoryBalanceRepository.findByIdTrancatAcctId(acctId);

        int interestTransactionCount = 0;
        for (TransactionCategoryBalance categoryBalance : categoryBalances) {
            TransactionCategoryBalanceId key = categoryBalance.getId();

            // 1200-GET-INTEREST-RATE (+ 1200-A DEFAULT fallback).
            BigDecimal annualRate = resolveInterestRate(
                    account.getAcctGroupId(), key.getTrancatTypeCd(), key.getTrancatCd());

            // IF DIS-INT-RATE NOT = 0: only accrue and write when a rate applies.
            if (annualRate.signum() != 0) {
                // 1300-COMPUTE-INTEREST: monthly interest for this category.
                BigDecimal monthlyInterest =
                        calculateMonthlyInterest(categoryBalance.getTranCatBal(), annualRate);

                // ADD WS-MONTHLY-INT TO WS-TOTAL-INT.
                totalInterest = totalInterest.add(monthlyInterest);

                // 1300-B-WRITE-TX: one interest transaction per category.
                String tranId = String.format(TRAN_ID_FORMAT, nextTransactionId);
                nextTransactionId++;
                transactionRepository.save(
                        buildInterestTransaction(tranId, acctId, cardNumber, monthlyInterest));
                interestTransactionCount++;
            }
        }

        // 1050-UPDATE-ACCOUNT: roll the accrued interest into the balance and reset
        // the current-cycle credit/debit accumulators (MOVE 0 TO ...), then REWRITE.
        totalInterest = totalInterest.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        account.setAcctCurrBal(
                account.getAcctCurrBal().add(totalInterest).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        account.setAcctCurrCycCredit(BigDecimal.ZERO.setScale(MONEY_SCALE));
        account.setAcctCurrCycDebit(BigDecimal.ZERO.setScale(MONEY_SCALE));
        accountRepository.save(account);

        log.debug("Applied interest to account {}: {} interest transaction(s) written",
                acctId, interestTransactionCount);

        return totalInterest;
    }

    /**
     * Builds one interest {@link Transaction}, populating every field exactly as
     * COBOL {@code 1300-B-WRITE-TX} does.
     *
     * <p>Constant fields (type {@code '01'}, category {@code 5}, source
     * {@code 'System'}), the {@code 'Int. for a/c ' + acctId} description, the
     * zero merchant id, the blank merchant name/city/ZIP, the cross-reference card
     * number, and the origination/processing timestamps (a single current
     * 26-character value used for both) all mirror the source paragraph. The
     * transaction amount is the category's monthly interest at scale&nbsp;2.</p>
     *
     * @param tranId          the pre-formatted 16-digit transaction id
     * @param acctId          the owning account id (for the description)
     * @param cardNumber      the cross-reference card number ({@code XREF-CARD-NUM})
     * @param monthlyInterest the category's monthly interest ({@code WS-MONTHLY-INT})
     * @return a fully populated, unsaved interest transaction
     */
    private Transaction buildInterestTransaction(String tranId, Long acctId,
            String cardNumber, BigDecimal monthlyInterest) {
        String timestamp = currentDb2Timestamp();
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd(TRAN_TYPE_INTEREST);
        transaction.setTranCatCd(TRAN_CAT_INTEREST);
        transaction.setTranSource(TRAN_SOURCE_SYSTEM);
        transaction.setTranDesc(DESC_PREFIX + acctId);
        transaction.setTranAmt(monthlyInterest);
        transaction.setTranMerchantId(0L);
        transaction.setTranMerchantName(BLANK);
        transaction.setTranMerchantCity(BLANK);
        transaction.setTranMerchantZip(BLANK);
        transaction.setTranCardNum(cardNumber);
        transaction.setTranOrigTs(timestamp);
        transaction.setTranProcTs(timestamp);
        return transaction;
    }

    /**
     * Returns the current wall-clock time as a 26-character DB2-style timestamp
     * text ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}), the Java equivalent of the COBOL
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraph. The width matches the
     * {@code PIC X(26)} {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS} fields exactly.
     *
     * @return the current timestamp rendered as a 26-character string
     */
    private String currentDb2Timestamp() {
        return LocalDateTime.now().format(DB2_TIMESTAMP_FORMAT);
    }
}
