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
import com.carddemo.service.shared.FileStatusMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-record {@link ItemProcessor} for the interest-calculation stage of the batch pipeline
 * (lineage: {@code app/cbl/CBACT04C.cbl} driven by {@code app/jcl/INTCALC.jcl}, source commit
 * {@code 27d6c6f}; REFERENCE ONLY, COBOL is not copied). It re-platforms the per-balance logic of
 * the COBOL paragraphs {@code 1200-GET-INTEREST-RATE}, {@code 1200-A-GET-DEFAULT-INT-RATE},
 * {@code 1300-COMPUTE-INTEREST}, {@code 1300-B-WRITE-TX} and the documented-empty
 * {@code 1400-COMPUTE-FEES}.
 *
 * <p>The COBOL program reads the transaction-category-balance dataset (TCATBALF) sequentially; this
 * processor receives one {@link TransactionCategoryBalance} row per invocation and returns the
 * computed interest {@link Transaction}, or {@code null} when the applicable rate is zero. Returning
 * {@code null} causes Spring Batch to filter the item, mirroring the COBOL behaviour of writing no
 * transaction when {@code DIS-INT-RATE = 0}.</p>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><strong>Account group resolution</strong> &mdash; the owning account is read by id
 *       ({@code 1100-GET-ACCT-DATA}); a missing account is a fatal data error
 *       ({@link RecordNotFoundException}), matching the COBOL {@code INVALID KEY} abend path.</li>
 *   <li><strong>Interest-rate lookup with DEFAULT fallback</strong> &mdash; the disclosure group is
 *       read by the account's own group id; on a not-found result (COBOL FILE STATUS {@code '23'})
 *       the reserved {@code "DEFAULT"} group is read instead. When both reads are empty the lookup
 *       is fatal, routed through {@link FileStatusMapper} as a {@link RecordNotFoundException}.</li>
 *   <li><strong>Interest formula</strong> &mdash; {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200},
 *       computed in {@link BigDecimal} with the literal divisor {@code 1200}, scale&nbsp;2 and
 *       {@link RoundingMode#HALF_EVEN}; no floating point and no algebraic simplification.</li>
 *   <li><strong>Transaction build</strong> &mdash; a fully populated interest {@link Transaction} is
 *       returned but never persisted (the batch writer owns persistence). No account-balance rollup
 *       is performed here; that cross-record concern belongs to the job/writer where account
 *       control-breaks are coordinated.</li>
 *   <li><strong>Metrics</strong> &mdash; {@code carddemo.batch.records.processed} is incremented once
 *       per emitted interest transaction.</li>
 * </ul>
 *
 * <p>The bean is {@link StepScope step-scoped}: the {@code WS-TRANID-SUFFIX} running counter is held
 * in an {@link AtomicLong} that is recreated (reset to zero) for each step execution.</p>
 */
@Component
@StepScope
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance, Transaction> {

    /** Reserved disclosure-group id used as the interest-rate fallback ({@code 1200-A}, literal {@code 'DEFAULT'}). */
    private static final String DEFAULT_DISCLOSURE_GROUP_ID = "DEFAULT";

    /** Transaction type code written on every interest transaction (COBOL {@code MOVE '01' TO TRAN-TYPE-CD}). */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /** Transaction category code written on every interest transaction (COBOL {@code MOVE '05'}; numeric 5). */
    private static final int INTEREST_TRAN_CAT_CD = 5;

    /** Transaction source literal written on every interest transaction (COBOL {@code MOVE 'System' TO TRAN-SOURCE}). */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /** Description prefix (COBOL {@code STRING 'Int. for a/c ', ACCT-ID}); the trailing space is significant. */
    private static final String INTEREST_DESC_PREFIX = "Int. for a/c ";

    /** Merchant id written on interest transactions (COBOL {@code MOVE 0 TO TRAN-MERCHANT-ID}). */
    private static final long INTEREST_MERCHANT_ID = 0L;

    /** Blank value for the merchant text fields (COBOL {@code MOVE SPACES}); fixed-width padded downstream. */
    private static final String BLANK = "";

    /** Fixed divisor of the monthly-interest formula (COBOL {@code / 1200}); used verbatim, no simplification. */
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal("1200");

    /** Scale of the computed interest amount (COBOL {@code WS-MONTHLY-INT PIC S9(09)V99}). */
    private static final int INTEREST_AMOUNT_SCALE = 2;

    /** Required width of the PARM date (COBOL {@code PARM-DATE PIC X(10)}); guarantees a 16-char TRAN-ID. */
    private static final int PARM_DATE_LENGTH = 10;

    /** Modulus constraining the running suffix to six digits (COBOL {@code WS-TRANID-SUFFIX PIC 9(06)}). */
    private static final long TRANID_SUFFIX_MODULUS = 1_000_000L;

    /** Number of digits in the zero-padded TRAN-ID suffix (COBOL {@code WS-TRANID-SUFFIX PIC 9(06)}). */
    private static final String TRANID_SUFFIX_FORMAT = "%06d";

    /** Number of digits in the zero-padded account id used in the description (COBOL {@code ACCT-ID PIC 9(11)}). */
    private static final String ACCT_ID_FORMAT = "%011d";

    /** Logical entity name used in not-found diagnostics for the account read. */
    private static final String ACCOUNT_ENTITY = "Account";

    /** Logical entity name used in not-found diagnostics for the disclosure-group read. */
    private static final String DISCLOSURE_GROUP_ENTITY = "DisclosureGroup";

    /** Logical entity name used in not-found diagnostics for the card cross-reference read. */
    private static final String CARD_XREF_ENTITY = "CardCrossReference";

    /** Counter incremented once per emitted interest transaction (matches the observability meter name). */
    private static final String METRIC_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** DB2 timestamp format {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (COBOL {@code DB2-FORMAT-TS PIC X(26)}). */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /** Account repository (re-platforms ACCTDAT); supplies the owning account's group id. */
    private final AccountRepository accountRepository;

    /** Disclosure-group repository (re-platforms DISCGRP); supplies the interest rate. */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /** Card cross-reference repository (re-platforms CARDXREF / CXACAIX); supplies the card number. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Shared FILE STATUS mapper; routes the fatal missing-DEFAULT-group read to the abend-equivalent exception. */
    private final FileStatusMapper fileStatusMapper;

    /** Micrometer registry used to record the processed-records metric. */
    private final MeterRegistry meterRegistry;

    /** Ten-character processing date (COBOL {@code PARM-DATE}); the high-order part of every TRAN-ID. */
    private final String parmDate;

    /** Clock supplying the transaction timestamps; injectable so the value is deterministic under test. */
    private final Clock clock;

    /** Running TRAN-ID suffix (COBOL {@code WS-TRANID-SUFFIX}, {@code VALUE 0}); one per emitted transaction. */
    private final AtomicLong tranIdSuffix = new AtomicLong(0L);

    /**
     * Creates the processor.
     *
     * @param accountRepository            repository for the owning account (ACCTDAT); must not be {@code null}
     * @param disclosureGroupRepository    repository for the disclosure group (DISCGRP); must not be {@code null}
     * @param cardCrossReferenceRepository repository for the card cross-reference (CARDXREF); must not be {@code null}
     * @param fileStatusMapper             shared FILE STATUS mapper for the fatal not-found path; must not be {@code null}
     * @param meterRegistry                Micrometer registry for the processed-records metric; must not be {@code null}
     * @param parmDate                     the {@code parmDate} job parameter (COBOL {@code PARM-DATE PIC X(10)}); must be
     *                                     non-{@code null} and exactly {@value #PARM_DATE_LENGTH} characters
     * @param clock                        clock supplying the transaction timestamps; when {@code null} the system
     *                                     default-zone clock is used
     */
    public InterestCalculationProcessor(
            final AccountRepository accountRepository,
            final DisclosureGroupRepository disclosureGroupRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final FileStatusMapper fileStatusMapper,
            final MeterRegistry meterRegistry,
            @Value("#{jobParameters['parmDate']}") final String parmDate,
            @Nullable final Clock clock) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.disclosureGroupRepository =
                Objects.requireNonNull(disclosureGroupRepository, "disclosureGroupRepository must not be null");
        this.cardCrossReferenceRepository =
                Objects.requireNonNull(cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.parmDate = requireValidParmDate(parmDate);
        this.clock = (clock != null) ? clock : Clock.systemDefaultZone();
    }

    /**
     * Computes the interest transaction for a single transaction-category balance.
     *
     * <p>Control flow mirrors the COBOL driver exactly: resolve the account group id, look up the
     * interest rate (with the DEFAULT-group fallback), and &mdash; only when the rate is non-zero
     * &mdash; compute the interest, build the transaction and run the (no-op) fee step. A zero rate
     * yields {@code null} so the item is filtered and no transaction is written.</p>
     *
     * @param tcatbal the transaction-category balance to process; must not be {@code null}
     * @return the computed interest {@link Transaction}, or {@code null} when the applicable rate is zero
     * @throws IllegalArgumentException if {@code tcatbal}, its composite id, or its account id is {@code null}
     * @throws RecordNotFoundException  if the owning account, the disclosure group (after the DEFAULT
     *                                  fallback), or the card cross-reference cannot be found
     */
    @Override
    @Nullable
    public Transaction process(final TransactionCategoryBalance tcatbal) {
        if (tcatbal == null) {
            throw new IllegalArgumentException("TransactionCategoryBalance item must not be null");
        }
        final TransactionCategoryBalanceId id = tcatbal.getId();
        if (id == null) {
            throw new IllegalArgumentException("TransactionCategoryBalance is missing its composite id");
        }
        final Long accountId = id.getAccountId();
        if (accountId == null) {
            throw new IllegalArgumentException("TransactionCategoryBalance id is missing its account id");
        }
        final String typeCode = id.getTypeCode();
        final Integer categoryCode = id.getCategoryCode();

        // 1100-GET-ACCT-DATA: resolve the owning account's disclosure group id.
        final String accountGroupId = resolveAccountGroupId(accountId);

        // 1200-GET-INTEREST-RATE (with the 1200-A-GET-DEFAULT-INT-RATE fallback).
        final BigDecimal disIntRate =
                getInterestRate(accountGroupId, typeCode, categoryCode).getDisIntRate();

        // COBOL main loop: a transaction is computed and written only when the rate is non-zero.
        if (disIntRate.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // 1300-COMPUTE-INTEREST -> 1300-B-WRITE-TX -> 1400-COMPUTE-FEES (no-op).
        final BigDecimal monthlyInterest = computeMonthlyInterest(tcatbal.getTranCatBal(), disIntRate);
        final Transaction interestTransaction = buildInterestTransaction(accountId, monthlyInterest);
        computeFees();

        meterRegistry.counter(METRIC_RECORDS_PROCESSED).increment();
        return interestTransaction;
    }

    /**
     * 1100-GET-ACCT-DATA &mdash; reads the owning account and returns its group id. The COBOL batch
     * driver reads accounts in lockstep, so a missing account is a fatal data error (the
     * {@code INVALID KEY} path leads to {@code 9999-ABEND-PROGRAM}).
     *
     * @param accountId the account id to read
     * @return the account's group id (COBOL {@code ACCT-GROUP-ID})
     * @throws RecordNotFoundException if no account exists for {@code accountId}
     */
    private String resolveAccountGroupId(final Long accountId) {
        final Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> RecordNotFoundException.forKey(ACCOUNT_ENTITY, accountId));
        return account.getAcctGroupId();
    }

    /**
     * 1200-GET-INTEREST-RATE with the 1200-A-GET-DEFAULT-INT-RATE fallback. Reads the disclosure
     * group by the account's own group id; on a not-found result (COBOL FILE STATUS {@code '23'}) it
     * re-reads with the reserved {@code "DEFAULT"} group id. When the DEFAULT read is also empty the
     * COBOL abends; that path is surfaced through the shared {@link FileStatusMapper} as a
     * {@link RecordNotFoundException}.
     *
     * @param accountGroupId the account's disclosure group id
     * @param typeCode       the transaction type code component of the key
     * @param categoryCode   the transaction category code component of the key
     * @return the resolved {@link DisclosureGroup}
     * @throws RecordNotFoundException if neither the account's group nor the DEFAULT group exists
     */
    private DisclosureGroup getInterestRate(
            final String accountGroupId, final String typeCode, final Integer categoryCode) {
        final DisclosureGroupId primaryKey = new DisclosureGroupId(accountGroupId, typeCode, categoryCode);
        final Optional<DisclosureGroup> primary = disclosureGroupRepository.findById(primaryKey);
        if (primary.isPresent()) {
            return primary.get();
        }
        final DisclosureGroupId defaultKey =
                new DisclosureGroupId(DEFAULT_DISCLOSURE_GROUP_ID, typeCode, categoryCode);
        final Optional<DisclosureGroup> fallback = disclosureGroupRepository.findById(defaultKey);
        if (fallback.isPresent()) {
            return fallback.get();
        }
        throw fileStatusMapper.toException(
                RecordNotFoundException.FILE_STATUS, DISCLOSURE_GROUP_ENTITY, defaultKey);
    }

    /**
     * 1300-COMPUTE-INTEREST &mdash; {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The balance and rate
     * are multiplied first and then divided by the literal {@code 1200}, producing a scale-2 amount
     * rounded {@link RoundingMode#HALF_EVEN}. The arithmetic is entirely in {@link BigDecimal}; the
     * divisor is never algebraically simplified.
     *
     * @param balance the transaction-category balance (COBOL {@code TRAN-CAT-BAL})
     * @param rate    the disclosure-group interest rate (COBOL {@code DIS-INT-RATE})
     * @return the monthly interest amount, scale&nbsp;2
     * @throws IllegalArgumentException if {@code balance} is {@code null}
     */
    private BigDecimal computeMonthlyInterest(final BigDecimal balance, final BigDecimal rate) {
        if (balance == null) {
            throw new IllegalArgumentException("TransactionCategoryBalance.tranCatBal must not be null");
        }
        return balance.multiply(rate)
                .divide(MONTHLY_INTEREST_DIVISOR, INTEREST_AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * 1300-B-WRITE-TX &mdash; builds (but does not persist) the interest transaction. The batch writer
     * owns persistence; no {@code save} is performed here and no account-balance rollup is applied.
     *
     * @param accountId       the owning account id (used in the TRAN-ID suffix context and description)
     * @param monthlyInterest the computed interest amount
     * @return a fully populated interest {@link Transaction}
     * @throws RecordNotFoundException if no card cross-reference exists for {@code accountId}
     */
    private Transaction buildInterestTransaction(final Long accountId, final BigDecimal monthlyInterest) {
        final Transaction tx = new Transaction();
        tx.setTranId(nextTransactionId());
        tx.setTranTypeCd(INTEREST_TRAN_TYPE_CD);
        tx.setTranCatCd(INTEREST_TRAN_CAT_CD);
        tx.setTranSource(INTEREST_TRAN_SOURCE);
        tx.setTranDesc(buildDescription(accountId));
        tx.setTranAmt(monthlyInterest);
        tx.setTranMerchantId(INTEREST_MERCHANT_ID);
        tx.setTranMerchantName(BLANK);
        tx.setTranMerchantCity(BLANK);
        tx.setTranMerchantZip(BLANK);
        tx.setTranCardNum(resolveCardNumber(accountId));
        final String timestamp = currentDb2Timestamp();
        tx.setTranOrigTs(timestamp);
        tx.setTranProcTs(timestamp);
        return tx;
    }

    /**
     * Builds the next 16-character TRAN-ID: the 10-character {@code PARM-DATE} concatenated with the
     * running suffix (COBOL {@code WS-TRANID-SUFFIX PIC 9(06)}). The suffix is incremented first
     * (COBOL {@code ADD 1 TO WS-TRANID-SUFFIX}) and wrapped to six digits, preserving the fixed width.
     *
     * @return a 16-character transaction id
     */
    private String nextTransactionId() {
        final long suffix = tranIdSuffix.incrementAndGet() % TRANID_SUFFIX_MODULUS;
        return parmDate + String.format(TRANID_SUFFIX_FORMAT, suffix);
    }

    /**
     * Builds the interest transaction description (COBOL {@code STRING 'Int. for a/c ', ACCT-ID}). The
     * account id is an 11-digit field ({@code PIC 9(11)}) and is rendered zero-padded to 11 digits so
     * the description bytes match the COBOL output; the persistence layer pads the field to its width.
     *
     * @param accountId the owning account id
     * @return the description string
     */
    private static String buildDescription(final Long accountId) {
        return INTEREST_DESC_PREFIX + String.format(ACCT_ID_FORMAT, accountId);
    }

    /**
     * 1110-GET-XREF-DATA &mdash; resolves the card number from the card cross-reference, read by
     * account id through the CXACAIX alternate index. The COBOL reads the cross-reference for the
     * account and uses the matching card number; a missing cross-reference is a fatal data error (the
     * {@code INVALID KEY} path leads to {@code 9999-ABEND-PROGRAM}).
     *
     * @param accountId the owning account id
     * @return the card number (COBOL {@code XREF-CARD-NUM})
     * @throws RecordNotFoundException if no cross-reference exists for {@code accountId}
     */
    private String resolveCardNumber(final Long accountId) {
        final List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (xrefs == null || xrefs.isEmpty()) {
            throw RecordNotFoundException.forKey(CARD_XREF_ENTITY, accountId);
        }
        return xrefs.get(0).getXrefCardNum();
    }

    /**
     * Returns the current timestamp in DB2 format {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (COBOL
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}, {@code DB2-FORMAT-TS PIC X(26)}). The injected {@link Clock}
     * makes the value deterministic under test.
     *
     * @return the formatted 26-character timestamp
     */
    private String currentDb2Timestamp() {
        return LocalDateTime.now(clock).format(DB2_TIMESTAMP_FORMATTER);
    }

    /**
     * 1400-COMPUTE-FEES &mdash; preserved as a documented no-op. The COBOL paragraph body is empty
     * ("To be implemented") and no fee logic is invented.
     */
    private void computeFees() {
        // No-op: CBACT04C 1400-COMPUTE-FEES is empty in the source; preserved for parity.
    }

    /**
     * Validates the {@code parmDate} job parameter (COBOL {@code PARM-DATE PIC X(10)}). A non-null,
     * exactly-ten-character value is required so that the concatenated TRAN-ID is always 16 characters.
     *
     * @param parmDate the candidate processing date
     * @return {@code parmDate} when valid
     * @throws IllegalArgumentException if {@code parmDate} is not exactly {@value #PARM_DATE_LENGTH} characters
     */
    private static String requireValidParmDate(final String parmDate) {
        Objects.requireNonNull(parmDate, "parmDate job parameter must not be null");
        if (parmDate.length() != PARM_DATE_LENGTH) {
            throw new IllegalArgumentException(
                    "parmDate must be exactly " + PARM_DATE_LENGTH
                            + " characters (COBOL PARM-DATE PIC X(10)) but was '" + parmDate + "'");
        }
        return parmDate;
    }
}
