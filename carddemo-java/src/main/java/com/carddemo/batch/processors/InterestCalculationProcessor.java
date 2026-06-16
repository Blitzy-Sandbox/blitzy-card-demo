package com.carddemo.batch.processors;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.key.DisclosureGroupId;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that computes monthly interest for a single
 * transaction-category balance, replacing the per-record interest logic of the mainframe
 * interest calculator {@code app/cbl/CBACT04C.cbl} (source commit {@code 27d6c6f}) driven by
 * {@code app/jcl/INTCALC.jcl}.
 *
 * <p>The source program reads {@code TCATBALF} sequentially and, for each balance, looks up the
 * disclosure-group interest rate, computes the monthly interest, and writes one interest
 * transaction. This processor models the per-record leg of that flow: it receives one
 * {@link TransactionCategoryBalance}, returns the computed interest {@link Transaction} for the
 * downstream writer to persist, or returns {@code null} so Spring Batch filters the record when
 * the resolved rate is zero (the COBOL {@code IF DIS-INT-RATE NOT = 0} guard at which no
 * transaction is written).</p>
 *
 * <p>Paragraph mapping (each COBOL paragraph becomes an explicit method invocation; there is no
 * implicit fall-through):</p>
 * <ul>
 *   <li>{@code 1100-GET-ACCT-DATA} &rarr; account lookup that yields {@code ACCT-GROUP-ID}.</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} / {@code 1200-A-GET-DEFAULT-INT-RATE} &rarr;
 *       {@link #resolveInterestRate(String, String, Integer)} with the {@code "DEFAULT"} group
 *       fallback (FILE STATUS {@code 23} on the first read drives the fallback rather than an
 *       error; an absent {@code "DEFAULT"} group is fatal).</li>
 *   <li>{@code 1300-COMPUTE-INTEREST} &rarr;
 *       {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}.</li>
 *   <li>{@code 1300-B-WRITE-TX} &rarr; {@link #buildInterestTransaction(Long, BigDecimal)}
 *       (build only; persistence belongs to the writer).</li>
 *   <li>{@code 1400-COMPUTE-FEES} &rarr; {@link #computeFees()}, an empty paragraph in the source
 *       ("To be implemented") preserved as a no-op.</li>
 * </ul>
 *
 * <p>The COBOL account-level interest roll-up ({@code 1050-UPDATE-ACCOUNT}: add total interest to
 * {@code ACCT-CURR-BAL}, zero the cycle credit/debit) spans records and is coordinated where the
 * account control-break is handled (the job/writer), so it is intentionally outside this
 * processor: no account balance is updated and no entity is saved here.</p>
 *
 * <p>The bean is {@link StepScope step-scoped} so the per-run transaction-id suffix
 * ({@code WS-TRANID-SUFFIX}) is created fresh for each step execution. The job's
 * {@code parmDate} parameter ({@code PARM='2022071800'}) is late-bound from the step's job
 * parameters and forms the first ten characters of the sixteen-character transaction id.</p>
 */
@Component
@StepScope
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance, Transaction> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /** Metric incremented once for every interest transaction emitted by this stage. */
    private static final String RECORDS_PROCESSED_METRIC = "carddemo.batch.records.processed";

    /** Metric incremented once for every balance dropped because its interest rate is zero. */
    private static final String RECORDS_REJECTED_METRIC = "carddemo.batch.records.rejected";

    /** Tag key used to classify rejected records on {@link #RECORDS_REJECTED_METRIC}. */
    private static final String REJECT_REASON_TAG = "reason";

    /** Reject reason recorded when a balance is skipped due to a zero interest rate. */
    private static final String ZERO_RATE_REJECT_REASON = "zero-interest-rate";

    /** Reserved disclosure-group id used as the interest-rate fallback (COBOL {@code 1200-A}). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Transaction type code stamped on each interest transaction (COBOL {@code MOVE '01'}). */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /** Transaction category code stamped on each interest transaction (COBOL {@code MOVE '05'}). */
    private static final int INTEREST_TRAN_CAT_CD = 5;

    /** Transaction source stamped on each interest transaction (COBOL {@code MOVE 'System'}). */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /** Leading text of the interest transaction description (COBOL {@code 'Int. for a/c '}). */
    private static final String INTEREST_DESC_PREFIX = "Int. for a/c ";

    /** Fixed divisor of the monthly-interest formula (COBOL literal {@code 1200}). */
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal("1200");

    /** Scale of the computed monthly interest (COBOL {@code WS-MONTHLY-INT PIC S9(09)V99}). */
    private static final int INTEREST_SCALE = 2;

    /** Zero-padded format of the account id in the description (COBOL {@code ACCT-ID PIC 9(11)}). */
    private static final String ACCOUNT_ID_DESC_FORMAT = "%011d";

    /** Zero-padded format of the transaction-id suffix (COBOL {@code WS-TRANID-SUFFIX PIC 9(06)}). */
    private static final String SUFFIX_FORMAT = "%06d";

    /** Modulus that keeps the running suffix within the six-digit {@code PIC 9(06)} range. */
    private static final long SUFFIX_MODULUS = 1_000_000L;

    /** DB2 timestamp pattern producing a 26-character value (COBOL {@code DB2-FORMAT-TS}). */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    private final AccountRepository accountRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final MeterRegistry meterRegistry;
    private final String parmDate;
    private final Clock clock;

    /** Running transaction-id suffix; reset per step-scoped instance (COBOL {@code WS-TRANID-SUFFIX}). */
    private final AtomicLong suffixCounter = new AtomicLong(0L);

    /**
     * Primary (framework) constructor. Spring injects the data-access beans and the meter
     * registry, late-binds the job's {@code parmDate} parameter, and defaults the {@link Clock}
     * to the system zone. The {@code parmDate} job parameter mirrors the {@code INTCALC.jcl}
     * {@code PARM='2022071800'}; when absent it falls back to the {@code carddemo.batch.interest.parm-date}
     * property (default {@code 2022071800}).
     *
     * @param accountRepository            account lookups for the owning group id
     * @param disclosureGroupRepository    disclosure-group (interest rate) lookups
     * @param cardCrossReferenceRepository card cross-reference lookups by account id
     * @param meterRegistry                Micrometer registry for processed/rejected counters
     * @param parmDate                     ten-character run date forming the transaction-id prefix
     */
    @Autowired
    public InterestCalculationProcessor(
            AccountRepository accountRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            MeterRegistry meterRegistry,
            @Value("#{jobParameters['parmDate'] ?: "
                    + "'${carddemo.batch.interest.parm-date:2022071800}'}")
            String parmDate) {
        this(accountRepository, disclosureGroupRepository, cardCrossReferenceRepository,
                meterRegistry, parmDate, Clock.systemDefaultZone());
    }

    /**
     * Full constructor used by tests to supply a deterministic {@link Clock}, exercising the same
     * logic the framework constructor delegates to.
     *
     * @param accountRepository            account lookups for the owning group id
     * @param disclosureGroupRepository    disclosure-group (interest rate) lookups
     * @param cardCrossReferenceRepository card cross-reference lookups by account id
     * @param meterRegistry                Micrometer registry for processed/rejected counters
     * @param parmDate                     ten-character run date forming the transaction-id prefix
     * @param clock                        clock used for the origin/processing timestamps
     */
    public InterestCalculationProcessor(
            AccountRepository accountRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            MeterRegistry meterRegistry,
            String parmDate,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.meterRegistry = meterRegistry;
        this.parmDate = parmDate;
        this.clock = clock;
    }

    /**
     * Computes interest for one transaction-category balance and returns the interest transaction
     * to be written, or {@code null} when the resolved rate is zero (no transaction is produced).
     *
     * <p>Flow (preserving the COBOL execution order): resolve the owning account's group id, read
     * the disclosure-group rate with the {@code "DEFAULT"} fallback, then — only when the rate is
     * non-zero — compute the monthly interest, build the interest transaction, and run the
     * (no-op) fee step.</p>
     *
     * @param tcatbal the transaction-category balance to process; its embedded id supplies the
     *                account id, transaction type code, and transaction category code
     * @return the computed interest {@link Transaction}, or {@code null} when the interest rate is
     *         zero so Spring Batch filters the record
     */
    @Override
    public Transaction process(TransactionCategoryBalance tcatbal) {
        TransactionCategoryBalanceId key = tcatbal.getId();
        Long accountId = key.getAccountId();
        String typeCode = key.getTypeCode();
        Integer categoryCode = key.getCategoryCode();

        // 1100-GET-ACCT-DATA: the COBOL driver reads accounts in lockstep, so an absent account is
        // a fatal data error rather than a skip.
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> RecordNotFoundException.forKey("Account", accountId));

        // 1200-GET-INTEREST-RATE (+ 1200-A-GET-DEFAULT-INT-RATE fallback).
        BigDecimal interestRate = resolveInterestRate(account.getAcctGroupId(), typeCode, categoryCode);

        // Main-loop guard: IF DIS-INT-RATE NOT = 0 (scale-insensitive comparison via compareTo).
        if (interestRate.compareTo(BigDecimal.ZERO) == 0) {
            meterRegistry.counter(RECORDS_REJECTED_METRIC, REJECT_REASON_TAG, ZERO_RATE_REJECT_REASON)
                    .increment();
            return null;
        }

        // 1300-COMPUTE-INTEREST.
        BigDecimal monthlyInterest = computeMonthlyInterest(tcatbal.getTranCatBal(), interestRate);

        // 1300-B-WRITE-TX (build only; the writer persists).
        Transaction interestTransaction = buildInterestTransaction(accountId, monthlyInterest);

        // 1400-COMPUTE-FEES (documented no-op in the source program).
        computeFees();

        meterRegistry.counter(RECORDS_PROCESSED_METRIC).increment();
        return interestTransaction;
    }

    /**
     * Resolves the disclosure-group interest rate for the supplied key, falling back to the
     * reserved {@code "DEFAULT"} account group when the primary group has no row (COBOL FILE
     * STATUS {@code 23}). A missing {@code "DEFAULT"} group is fatal, mirroring the source abend.
     *
     * @param accountGroupId the account's own group id (COBOL {@code ACCT-GROUP-ID})
     * @param typeCode       the transaction type code component of the key
     * @param categoryCode   the transaction category code component of the key
     * @return the resolved interest rate (COBOL {@code DIS-INT-RATE}, scale 2)
     * @throws RecordNotFoundException when neither the primary nor the {@code "DEFAULT"} group exists
     */
    private BigDecimal resolveInterestRate(String accountGroupId, String typeCode, Integer categoryCode) {
        DisclosureGroupId primaryKey = new DisclosureGroupId(accountGroupId, typeCode, categoryCode);
        DisclosureGroup disclosureGroup = disclosureGroupRepository.findById(primaryKey).orElse(null);

        if (disclosureGroup == null) {
            LOGGER.debug("Disclosure group '{}' not found for type '{}' category {}; "
                    + "retrying with the DEFAULT group", accountGroupId, typeCode, categoryCode);
            DisclosureGroupId defaultKey = new DisclosureGroupId(DEFAULT_GROUP_ID, typeCode, categoryCode);
            disclosureGroup = disclosureGroupRepository.findById(defaultKey)
                    .orElseThrow(() -> RecordNotFoundException.forKey("DisclosureGroup", defaultKey));
        }

        return disclosureGroup.getDisIntRate();
    }

    /**
     * Computes the monthly interest exactly as the COBOL formula
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}: multiply the balance
     * by the rate first, then divide by the literal {@code 1200}, rounding to scale 2 with
     * banker's rounding ({@link RoundingMode#HALF_EVEN}). No algebraic simplification is applied.
     *
     * @param categoryBalance the transaction-category balance (COBOL {@code TRAN-CAT-BAL})
     * @param interestRate    the disclosure-group rate (COBOL {@code DIS-INT-RATE})
     * @return the monthly interest amount, scale 2
     */
    private BigDecimal computeMonthlyInterest(BigDecimal categoryBalance, BigDecimal interestRate) {
        return categoryBalance
                .multiply(interestRate)
                .divide(MONTHLY_INTEREST_DIVISOR, INTEREST_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Builds the interest {@link Transaction} (COBOL {@code 1300-B-WRITE-TX}). The transaction id
     * is the ten-character {@code parmDate} concatenated with the six-digit running suffix
     * (incremented first, matching {@code ADD 1 TO WS-TRANID-SUFFIX}), always sixteen characters.
     * The description reproduces {@code STRING 'Int. for a/c ', ACCT-ID} with the account id in its
     * eleven-digit zero-padded form. The origin and processing timestamps are a single current
     * DB2-format value. The instance is returned for the writer to persist; nothing is saved here.
     *
     * @param accountId       the owning account id (COBOL {@code ACCT-ID})
     * @param monthlyInterest the computed interest amount (COBOL {@code WS-MONTHLY-INT})
     * @return the populated interest transaction
     */
    private Transaction buildInterestTransaction(Long accountId, BigDecimal monthlyInterest) {
        long suffix = suffixCounter.incrementAndGet() % SUFFIX_MODULUS;
        String transactionId = parmDate + String.format(SUFFIX_FORMAT, suffix);
        String timestamp = LocalDateTime.now(clock).format(DB2_TIMESTAMP_FORMAT);
        String cardNumber = resolveCardNumber(accountId);

        Transaction transaction = new Transaction();
        transaction.setTranId(transactionId);
        transaction.setTranTypeCd(INTEREST_TRAN_TYPE_CD);
        transaction.setTranCatCd(INTEREST_TRAN_CAT_CD);
        transaction.setTranSource(INTEREST_TRAN_SOURCE);
        transaction.setTranDesc(INTEREST_DESC_PREFIX + String.format(ACCOUNT_ID_DESC_FORMAT, accountId));
        transaction.setTranAmt(monthlyInterest);
        transaction.setTranMerchantId(0L);
        transaction.setTranMerchantName("");
        transaction.setTranMerchantCity("");
        transaction.setTranMerchantZip("");
        transaction.setTranCardNum(cardNumber);
        transaction.setTranOrigTs(timestamp);
        transaction.setTranProcTs(timestamp);
        return transaction;
    }

    /**
     * Resolves the card number for the account from the card cross-reference (COBOL
     * {@code XREF-CARD-NUM}, read by the account-id alternate key). The first matching row is used,
     * matching the single keyed read the source performs per account; an absent cross-reference is
     * fatal, mirroring the source abend on a failed XREF read.
     *
     * @param accountId the account id whose card cross-reference is read
     * @return the cross-referenced card number
     * @throws RecordNotFoundException when no cross-reference row exists for the account
     */
    private String resolveCardNumber(Long accountId) {
        List<CardCrossReference> crossReferences = cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (crossReferences.isEmpty()) {
            throw RecordNotFoundException.forKey("CardCrossReference", accountId);
        }
        return crossReferences.get(0).getXrefCardNum();
    }

    /**
     * Fee computation step (COBOL {@code 1400-COMPUTE-FEES}). The source paragraph body is empty
     * ("To be implemented") and is preserved as a no-op so the per-record invocation chain matches
     * the original without introducing any fee behaviour.
     */
    private void computeFees() {
        // No-op: the source 1400-COMPUTE-FEES paragraph is intentionally empty.
    }
}
