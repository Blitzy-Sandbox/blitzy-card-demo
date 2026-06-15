package com.cardemo.batch.processors;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.InterestCalculationResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-record
 * <strong>interest computation</strong> of the legacy AWS CardDemo batch program
 * <strong>{@code CBACT04C}</strong> (Interest Calculator) in the greenfield
 * Java&nbsp;25 LTS + Spring Boot&nbsp;3.5.x migration.
 *
 * <h2>Provenance</h2>
 * <p>Translated from COBOL {@code app/cbl/CBACT04C.cbl} at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is <strong>read-only</strong>
 * reference material and is <strong>never copied</strong> into this repository;
 * traceability is by commit SHA only (AAP &sect;0.7.2). Per the <strong>Minimal
 * Change Clause</strong> (AAP &sect;0.7.1) this migration reproduces the COBOL
 * behaviour <em>exactly</em> &mdash; no business-rule "improvements", no extra
 * computations, no feature additions &mdash; and documents every technology
 * substitution at its point of use. <strong>Interest-formula fidelity is the
 * central requirement</strong> (AAP &sect;0.7.6). The application base package is
 * {@code com.cardemo} (decision D-006).
 *
 * <h2>What this processor reproduces ({@code CBACT04C} per-row body)</h2>
 * <p>{@code CBACT04C} browses the {@code TCATBAL} transaction-category-balance
 * KSDS sequentially ({@code 1000-TCATBALF-GET-NEXT}). For each
 * {@code TRAN-CAT-BAL-RECORD} this processor reproduces the per-row work the COBOL
 * main loop performs once the record is in hand, in the COBOL evaluation order:</p>
 * <ol>
 *   <li><strong>{@code 1100-GET-ACCT-DATA}.</strong> Read the owning account by
 *       {@code TRANCAT-ACCT-ID}. COBOL {@code READ ACCOUNT-FILE ... INVALID KEY}
 *       &rarr; {@link AccountRepository#findById(Object)}; a missing account is a
 *       COBOL abend &rarr; {@link RecordNotFoundException}.</li>
 *   <li><strong>{@code 1110-GET-XREF-DATA}.</strong> Read the account's primary
 *       card cross-reference by the {@code XREF-ACCT-ID} alternate index to obtain
 *       {@code XREF-CARD-NUM} for the interest transaction. COBOL keyed
 *       {@code READ XREF-FILE} &rarr;
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}, taking the
 *       first row (COBOL's single keyed read returns one record).</li>
 *   <li><strong>{@code 1200-GET-INTEREST-RATE}</strong> (with the {@code DEFAULT}
 *       fallback). Resolve the disclosure-group rate; see
 *       {@link #resolveInterestRate(Account, TransactionCategoryBalanceId)}.</li>
 *   <li><strong>{@code 1300-COMPUTE-INTEREST}</strong>, performed only when
 *       {@code DIS-INT-RATE NOT = 0}; see
 *       {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}.</li>
 *   <li><strong>{@code 1300-B-WRITE-TX}</strong>, which <em>builds</em> the
 *       interest {@link Transaction}; see
 *       {@link #buildInterestTransaction(Account, CardCrossReference, BigDecimal)}.
 *       (COBOL also performs the file {@code WRITE} here; in the migration the
 *       actual persistence is the writer's responsibility &mdash; see the boundary
 *       note below.)</li>
 *   <li><strong>{@code 1400-COMPUTE-FEES}</strong> is an <strong>empty stub</strong>
 *       in {@code CBACT04C@27d6c6f} ("To be implemented") &mdash; intentionally
 *       <em>not</em> implemented here (no behaviour to preserve), per the Minimal
 *       Change Clause.</li>
 * </ol>
 *
 * <h2>Account-boundary roll-up ({@code 1050-UPDATE-ACCOUNT}) is NOT done here</h2>
 * <p>COBOL accumulates {@code WS-MONTHLY-INT} into a per-account
 * {@code WS-TOTAL-INT} and, at each account break (when {@code TRANCAT-ACCT-ID}
 * changes) and at end-of-file, performs {@code 1050-UPDATE-ACCOUNT}:
 * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}, zero the cycle credit/debit, and
 * {@code REWRITE} the account. A chunk-oriented Spring Batch {@code ItemProcessor}
 * processes one item in isolation and <strong>cannot</strong> observe account
 * boundaries across items, so this processor deliberately does <strong>not</strong>
 * mutate {@code ACCT-CURR-BAL}. Instead it emits, on every processed row, the
 * owning {@link InterestCalculationResult#accountId()} and the per-row
 * {@link InterestCalculationResult#monthlyInterest()}; the
 * {@code com.cardemo.batch.writers}/{@code com.cardemo.batch.jobs} layer owns the
 * {@code 1050} roll-up (for example an aggregating writer keyed by {@code accountId}
 * that sums the interest and applies it to {@code acctCurrBal}, or a dedicated
 * account-update step). If the integrating agent instead configures a
 * tasklet/non-chunk step that replicates the exact sequential browse, this carrier
 * still suffices.</p>
 *
 * <h2>Processor / writer boundary (do not cross)</h2>
 * <p>This processor only <em>resolves</em>, <em>computes</em> and <em>builds</em>.
 * The COBOL file {@code WRITE FD-TRANFILE-REC} of {@code 1300-B-WRITE-TX} and the
 * account {@code REWRITE} of {@code 1050-UPDATE-ACCOUNT} are intentionally the
 * responsibility of the {@code com.cardemo.batch.writers} layer. No database
 * writes, {@code save}s or {@code REWRITE}s are performed here; the built interest
 * {@link Transaction} is handed to the writer via the
 * {@link InterestCalculationResult} carrier.</p>
 *
 * <h2>Technology substitutions (documented per Minimal Change Clause)</h2>
 * <ul>
 *   <li>VSAM keyed {@code READ ... INVALID KEY} &rarr;
 *       {@link Optional}-returning {@code JpaRepository.findById}; "invalid key"
 *       &rarr; {@link Optional#isEmpty()} (AAP &sect;0.7.5 FILE STATUS mapping).</li>
 *   <li>{@code DISCGRP-STATUS = '23'} (not found) on the primary disclosure-group
 *       read &rarr; a literal <strong>second</strong> {@code findById} with group
 *       id {@code "DEFAULT"}; a missing {@code DEFAULT} group is a COBOL abend
 *       &rarr; {@link RecordNotFoundException}.</li>
 *   <li>COBOL abend ({@code 9999-ABEND-PROGRAM} / {@code CEE3ABD}) on an
 *       unresolvable mandatory record &rarr; {@link RecordNotFoundException}
 *       (unchecked; {@code FILE_STATUS_CODE "23"}).</li>
 *   <li>{@code COMP-3}/{@code PIC S9(n)V99} money and rate &rarr; {@link BigDecimal};
 *       comparisons use {@link BigDecimal#compareTo(BigDecimal)} (never
 *       {@code equals}), and no {@code float}/{@code double} appears anywhere
 *       (AAP &sect;0.7.3).</li>
 *   <li>{@code FUNCTION CURRENT-DATE} / {@code Z-GET-DB2-FORMAT-TIMESTAMP} &rarr;
 *       {@code java.time} via an injectable {@link Clock} (see
 *       {@link #db2FormatTimestamp(LocalDateTime)}).</li>
 *   <li>{@code WS-TOTAL-INT} / {@code 1050-UPDATE-ACCOUNT} account roll-up &rarr;
 *       writer/job-level account aggregation (explicitly out of this processor; see
 *       above).</li>
 * </ul>
 *
 * <h2>Scope &amp; thread-safety</h2>
 * <p>The class is {@link StepScope step-scoped} so that the late-bound job
 * parameter {@code parmDate} resolves at step-execution time (the COBOL
 * {@code PARM-DATE} arrives the same way from JCL) and so the run-global
 * transaction-id suffix counter is created fresh for each job run &mdash; matching
 * COBOL's {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}. The suffix is a single
 * run-global monotonically increasing counter (see {@link #buildInterestTransaction}),
 * so the owning step must execute single-threaded for the sequence to remain
 * deterministic, which matches COBOL's single-threaded batch execution. Apart from
 * that counter and the late-bound {@code parmDate}, the processor's collaborators
 * (three repositories and a {@link Clock}) are immutable and injected.</p>
 *
 * @see InterestCalculationResult
 * @see ItemProcessor
 */
@Component
@StepScope
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance, InterestCalculationResult> {

    /**
     * The literal disclosure-group id used for the {@code DEFAULT} fallback read.
     * COBOL {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} (1200-GET-INTEREST-RATE)
     * before re-reading the disclosure-group file.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Interest-formula divisor. COBOL {@code COMPUTE WS-MONTHLY-INT =
     * ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Held as a {@link BigDecimal} literal
     * so the division is exact-precision (no {@code int}/{@code double} promotion),
     * per AAP &sect;0.7.3.
     */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /**
     * Scale of the computed monthly interest. COBOL {@code WS-MONTHLY-INT
     * PIC S9(09)V99} &rarr; two decimal places.
     */
    private static final int MONTHLY_INTEREST_SCALE = 2;

    /**
     * Transaction type code for generated interest transactions. COBOL
     * {@code MOVE '01' TO TRAN-TYPE-CD} (1300-B-WRITE-TX).
     */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /**
     * Transaction category code for generated interest transactions. COBOL
     * {@code MOVE '05' TO TRAN-CAT-CD}; the migrated {@link Transaction#getTranCatCd()}
     * field is an {@link Integer}, so the COBOL two-character {@code '05'} maps to
     * the numeric value {@code 5}.
     */
    private static final Integer INTEREST_TRAN_CAT_CD = 5;

    /**
     * Transaction source for generated interest transactions. COBOL
     * {@code MOVE 'System' TO TRAN-SOURCE} (1300-B-WRITE-TX).
     */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /**
     * Description prefix for generated interest transactions. COBOL
     * {@code STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC}; the
     * trailing space before the account id is part of the COBOL literal and is
     * preserved exactly.
     */
    private static final String INTEREST_TRAN_DESC_PREFIX = "Int. for a/c ";

    /**
     * Blank value for the merchant fields of an interest transaction. COBOL
     * {@code MOVE SPACES TO TRAN-MERCHANT-NAME / TRAN-MERCHANT-CITY /
     * TRAN-MERCHANT-ZIP}. The migrated entity stores these as trimmed
     * {@link String}s, so the empty string is the faithful representation of the
     * COBOL {@code SPACES}.
     */
    private static final String BLANK_MERCHANT_FIELD = "";

    /**
     * Merchant id for an interest transaction. COBOL
     * {@code MOVE 0 TO TRAN-MERCHANT-ID}; the migrated entity field is a
     * {@link Long}.
     */
    private static final Long INTEREST_TRAN_MERCHANT_ID = 0L;

    /**
     * Date/time portion of the DB2 timestamp format produced by COBOL
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}: {@code yyyy-MM-dd-HH.mm.ss.} (note the
     * hyphen between date and time and the dot separators within the time), up to
     * and including the trailing dot that precedes the fractional seconds. This
     * matches the helper used by {@code TransactionPostingProcessor} so both batch
     * paths render the timestamp identically.
     */
    // COBOL Z-GET-DB2-FORMAT-TIMESTAMP layout: EEEE-MM-DD-UU.MM.SS. + fraction.
    private static final DateTimeFormatter DB2_TIMESTAMP_BASE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.");

    /**
     * Full 26-character DB2 timestamp pattern including six fractional-second
     * digits, used to parse the string produced by
     * {@link #db2FormatTimestamp(LocalDateTime)} back into a {@link LocalDateTime}
     * so the stored {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} carry exactly the
     * COBOL hundredths precision (the trailing {@code "0000"} keeps the remaining
     * digits zero).
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FULL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /**
     * Account repository &mdash; the JPA replacement for the keyed
     * {@code READ ACCOUNT-FILE} of {@code 1100-GET-ACCT-DATA}. Typed
     * {@code JpaRepository<Account, Long>} because the account key is the
     * eleven-digit {@code ACCT-ID}.
     */
    private final AccountRepository accountRepository;

    /**
     * Disclosure-group repository &mdash; the JPA replacement for the keyed
     * {@code READ DISCGRP-FILE} of {@code 1200-GET-INTEREST-RATE} (and its
     * {@code DEFAULT} re-read). Typed
     * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>} over the three-part
     * composite key.
     */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Card cross-reference repository &mdash; the JPA replacement for the
     * alternate-index {@code READ XREF-FILE KEY IS FD-XREF-ACCT-ID} of
     * {@code 1110-GET-XREF-DATA}. The {@code findByXrefAcctId} derived query
     * reproduces the {@code CXACAIX} alternate-index lookup.
     */
    private final CardCrossReferenceRepository crossReferenceRepository;

    /**
     * Clock backing the {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} timestamps.
     * Defaults to the system clock in production and is injectable so tests can pin
     * a fixed instant and assert the generated timestamps deterministically. This
     * is the {@code java.time} replacement for the implicit system clock read by
     * COBOL's {@code FUNCTION CURRENT-DATE} in {@code Z-GET-DB2-FORMAT-TIMESTAMP}.
     */
    private final Clock clock;

    /**
     * The run date supplied as an external job parameter. COBOL receives this as
     * {@code PARM-DATE PIC X(10)} (a {@code yyyy-MM-dd} string) through the
     * {@code EXTERNAL-PARMS} linkage from the JCL {@code PARM}/{@code DATEPARM}; here
     * it arrives via Spring Batch late binding (see {@link #setParmDate(String)})
     * and is used to build the interest transaction id deterministically (see
     * {@link #buildInterestTransaction(Account, CardCrossReference, BigDecimal)}).
     */
    private String parmDate;

    /**
     * Run-global, monotonically increasing transaction-id suffix counter. COBOL
     * {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}, incremented by
     * {@code ADD 1 TO WS-TRANID-SUFFIX} immediately before each interest
     * transaction id is built. The counter is run-global (<strong>not</strong>
     * reset per account); because the bean is {@link StepScope step-scoped} it is
     * created fresh (zero) for each job run, matching the COBOL {@code VALUE 0}
     * initialisation. The owning step must run single-threaded for the sequence to
     * stay deterministic, matching COBOL's single-threaded batch.
     */
    private long tranIdSuffix = 0L;

    /**
     * Production constructor used by Spring for component injection. Uses the
     * system-default-zone {@link Clock} so the interest transaction's timestamps are
     * stamped from the current time, exactly as {@code FUNCTION CURRENT-DATE} does
     * on the mainframe.
     *
     * @param accountRepository         the account repository (must not be
     *                                  {@code null})
     * @param disclosureGroupRepository the disclosure-group repository (must not be
     *                                  {@code null})
     * @param crossReferenceRepository  the card cross-reference repository (must not
     *                                  be {@code null})
     */
    @Autowired
    public InterestCalculationProcessor(AccountRepository accountRepository,
                                        DisclosureGroupRepository disclosureGroupRepository,
                                        CardCrossReferenceRepository crossReferenceRepository) {
        this(accountRepository, disclosureGroupRepository, crossReferenceRepository,
                Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor allowing a fixed {@link Clock} so the generated
     * {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} timestamps are deterministic.
     * Behaves identically to the production constructor in every other respect.
     *
     * @param accountRepository         the account repository (must not be
     *                                  {@code null})
     * @param disclosureGroupRepository the disclosure-group repository (must not be
     *                                  {@code null})
     * @param crossReferenceRepository  the card cross-reference repository (must not
     *                                  be {@code null})
     * @param clock                     the clock used to stamp the transaction
     *                                  timestamps (must not be {@code null})
     */
    public InterestCalculationProcessor(AccountRepository accountRepository,
                                        DisclosureGroupRepository disclosureGroupRepository,
                                        CardCrossReferenceRepository crossReferenceRepository,
                                        Clock clock) {
        this.accountRepository = accountRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.crossReferenceRepository = crossReferenceRepository;
        this.clock = clock;
    }

    /**
     * Late-binding setter for the run date job parameter. Reproduces the COBOL
     * {@code PARM-DATE PIC X(10)} that {@code CBACT04C} receives through its
     * {@code EXTERNAL-PARMS} linkage from the JCL {@code PARM}/{@code DATEPARM}. The
     * SpEL {@code #{jobParameters['parmDate']}} is resolved by Spring Batch at
     * step-execution time, which is why the bean is {@link StepScope step-scoped}.
     *
     * @param parmDate the run date as a 10-character {@code yyyy-MM-dd} string
     */
    @Value("#{jobParameters['parmDate']}")
    public void setParmDate(String parmDate) {
        this.parmDate = parmDate;
    }

    /**
     * Returns the run date job parameter ({@code PARM-DATE}) currently bound to this
     * processor, primarily for testing and diagnostics.
     *
     * @return the run date as a {@code yyyy-MM-dd} string, or {@code null} if not yet
     *         bound
     */
    public String getParmDate() {
        return parmDate;
    }

    /**
     * Reproduces the per-record interest computation of {@code CBACT04C}'s main loop
     * for a single {@code TRAN-CAT-BAL-RECORD}, returning the routing carrier consumed
     * by the interest writer/job.
     *
     * <p>The COBOL evaluation order is preserved exactly and is <strong>not</strong>
     * reordered: resolve the account ({@code 1100-GET-ACCT-DATA}) &rarr; resolve the
     * primary card number ({@code 1110-GET-XREF-DATA}) &rarr; resolve the interest
     * rate with the {@code DEFAULT} fallback ({@code 1200-GET-INTEREST-RATE}) &rarr;
     * guard {@code DIS-INT-RATE NOT = 0} &rarr; compute the monthly interest
     * ({@code 1300-COMPUTE-INTEREST}) &rarr; build the interest transaction
     * ({@code 1300-B-WRITE-TX}).</p>
     *
     * <p>This method <strong>never returns {@code null}</strong>: returning
     * {@code null} from a Spring Batch {@code ItemProcessor} filters the item out of
     * the chunk, which would drop the row from the downstream account-boundary
     * roll-up ({@code 1050-UPDATE-ACCOUNT}). On the zero-rate path it therefore
     * returns a carrier with a {@code null} transaction, {@link BigDecimal#ZERO}
     * interest and {@code interestApplied == false}, still exposing the
     * {@code accountId} so the roll-up sees the row &mdash; matching COBOL, which
     * advances the browse on a zero rate but writes no interest transaction.</p>
     *
     * @param item the transaction-category-balance staging record supplied by the
     *             reader (the sequential {@code TCATBAL} browse); non-{@code null}
     *             per the Spring Batch chunk contract
     * @return a non-{@code null} {@link InterestCalculationResult}: interest-applied
     *         (with the built transaction) or zero-rate (transaction {@code null})
     * @throws RecordNotFoundException if the owning account, its card cross-reference,
     *                                 or the disclosure-group rate (including the
     *                                 {@code DEFAULT} fallback) cannot be resolved
     *                                 &mdash; the typed mapping of a COBOL abend
     */
    @Override
    public InterestCalculationResult process(TransactionCategoryBalance item) {
        // The @EmbeddedId composite key carries TRANCAT-ACCT-ID / TRANCAT-TYPE-CD /
        // TRANCAT-CD (the FD-TRAN-CAT-KEY group of the TCATBAL record).
        final TransactionCategoryBalanceId key = item.getId();

        // --- 1100-GET-ACCT-DATA: resolve the owning account ----------------------
        // COBOL: MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID; READ ACCOUNT-FILE ... INVALID KEY.
        // Substitution: keyed VSAM READ -> findById; INVALID KEY / abend on a missing
        // account -> RecordNotFoundException (AAP §0.7.5 FILE STATUS mapping).
        final Account account = accountRepository.findById(key.getAcctId())
                .orElseThrow(() -> new RecordNotFoundException("Account",
                        String.valueOf(key.getAcctId())));

        // --- 1110-GET-XREF-DATA: resolve the primary card number (TRAN-CARD-NUM) ---
        final String cardNumber = resolveCardNumber(account);

        // --- 1200-GET-INTEREST-RATE (with the literal DEFAULT fallback) ----------
        final BigDecimal disIntRate = resolveInterestRate(account, key);

        // --- Guard: COBOL IF DIS-INT-RATE NOT = 0 (1300-COMPUTE-INTEREST/1400 only
        // run when the rate is non-zero). Use compareTo, NEVER equals, for BigDecimal
        // (AAP §0.7.3 -- equals is scale-sensitive, e.g. 0.00 != 0).
        if (disIntRate.compareTo(BigDecimal.ZERO) == 0) {
            // Zero-rate path: COBOL processes the row (advances the browse) but performs
            // neither 1300-COMPUTE-INTEREST nor 1300-B-WRITE-TX, so no interest
            // transaction is produced and WS-TOTAL-INT is unchanged for this row.
            // Return a carrier (NEVER null) carrying the accountId and zero interest so
            // the account-boundary roll-up still sees the row.
            return new InterestCalculationResult(null, account.getAcctId(),
                    BigDecimal.ZERO, false);
        }

        // 1300-COMPUTE-INTEREST: compute WS-MONTHLY-INT for this row.
        final BigDecimal monthlyInterest = computeMonthlyInterest(item.getTranCatBal(), disIntRate);

        // 1300-B-WRITE-TX: BUILD (do not persist) the interest transaction; the actual
        // WRITE is the writer's responsibility (see the class Javadoc boundary note).
        final Transaction interestTransaction =
                buildInterestTransaction(account, cardNumber, monthlyInterest);

        // 1400-COMPUTE-FEES is an EMPTY STUB in CBACT04C@27d6c6f ("To be implemented")
        // -- intentionally NOT implemented here (no behaviour to preserve), per the
        // Minimal Change Clause (AAP §0.7.1). No fee logic is invented.

        return new InterestCalculationResult(interestTransaction, account.getAcctId(),
                monthlyInterest, true);
    }

    /**
     * Reproduces {@code 1110-GET-XREF-DATA}: resolves the owning account's primary
     * card number ({@code XREF-CARD-NUM}) for the interest transaction.
     *
     * <p>COBOL performs a keyed {@code READ XREF-FILE KEY IS FD-XREF-ACCT-ID} on the
     * cross-reference alternate index. The migrated
     * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)} derived query
     * reproduces that {@code CXACAIX} alternate-index lookup but returns a
     * {@link List}; COBOL's single keyed read yields one record, so the
     * first/representative row is taken. CardDemo's batch path has one primary card
     * per account; if several exist, taking the first preserves COBOL's "first read"
     * behaviour.</p>
     *
     * @param account the resolved owning account
     * @return the 16-character cross-reference card number ({@code XREF-CARD-NUM})
     * @throws RecordNotFoundException if the account owns no cross-reference row
     *                                 &mdash; the typed mapping of the COBOL xref-read
     *                                 failure (abend)
     */
    private String resolveCardNumber(Account account) {
        final List<CardCrossReference> xrefs =
                crossReferenceRepository.findByXrefAcctId(account.getAcctId());
        if (xrefs.isEmpty()) {
            // COBOL: a non-'00' status on the XREF read -> 9999-ABEND-PROGRAM. Map the
            // abend to a typed exception (AAP §0.7.5).
            throw new RecordNotFoundException("CardCrossReference (by account)",
                    String.valueOf(account.getAcctId()));
        }
        // COBOL's keyed READ returns a single record -> take the first/representative row.
        return xrefs.get(0).getXrefCardNum();
    }

    /**
     * Reproduces {@code 1200-GET-INTEREST-RATE} including its exact {@code DEFAULT}
     * fallback: resolves the disclosure-group interest rate
     * ({@code DIS-INT-RATE}) for the row's account group, transaction type and
     * category.
     *
     * <p>The lookup is performed as <strong>two sequential</strong>
     * {@code findById} calls, mirroring the COBOL control flow exactly:</p>
     * <ol>
     *   <li>A primary read keyed by {@code ACCT-GROUP-ID} + {@code TRANCAT-TYPE-CD} +
     *       {@code TRANCAT-CD} (COBOL {@code READ DISCGRP-FILE}). A present result
     *       (COBOL {@code DISCGRP-STATUS = '00'}) yields the rate directly.</li>
     *   <li>If the primary read is empty (COBOL {@code DISCGRP-STATUS = '23'}), a
     *       <strong>second</strong> read keyed with the literal group id
     *       {@code "DEFAULT"} (COBOL {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID};
     *       {@code 1200-A-GET-DEFAULT-INT-RATE}). This is a processor-level second
     *       call; there is intentionally <strong>no</strong> dedicated repository
     *       finder for it (the legacy system never had one).</li>
     * </ol>
     *
     * <p>COBOL status 23 on the primary read &rarr; retry with literal group id
     * {@code 'DEFAULT'}; failure of {@code DEFAULT} is fatal (COBOL displays
     * "ERROR READING DEFAULT DISCLOSURE GROUP" and abends) &rarr;
     * {@link RecordNotFoundException}.</p>
     *
     * @param account the resolved owning account (provides {@code ACCT-GROUP-ID})
     * @param key     the row's composite key (provides {@code TRANCAT-TYPE-CD} and
     *                {@code TRANCAT-CD})
     * @return the resolved disclosure-group interest rate ({@code DIS-INT-RATE},
     *         scale&nbsp;2)
     * @throws RecordNotFoundException if neither the primary nor the {@code DEFAULT}
     *                                 disclosure-group record exists
     */
    private BigDecimal resolveInterestRate(Account account, TransactionCategoryBalanceId key) {
        // Primary key from ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD.
        // COBOL: MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID; MOVE TRANCAT-TYPE-CD TO
        // FD-DIS-TRAN-TYPE-CD; MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD; READ DISCGRP-FILE.
        final DisclosureGroupId primaryKey = new DisclosureGroupId(
                account.getAcctGroupId(), key.getTypeCode(), key.getCatCode());
        final Optional<DisclosureGroup> primary = disclosureGroupRepository.findById(primaryKey);
        if (primary.isPresent()) {
            // COBOL DISCGRP-STATUS = '00': use the resolved DIS-INT-RATE as-is.
            return primary.get().getDisIntRate();
        }

        // DEFAULT fallback (COBOL DISCGRP-STATUS = '23' -> re-read with group 'DEFAULT').
        // Literal SECOND findById -- no dedicated repository method exists for it.
        final DisclosureGroupId defaultKey = new DisclosureGroupId(
                DEFAULT_GROUP_ID, key.getTypeCode(), key.getCatCode());
        final Optional<DisclosureGroup> fallback = disclosureGroupRepository.findById(defaultKey);

        // COBOL status 23 on primary -> retry with literal group id 'DEFAULT'; failure of
        // DEFAULT is fatal (abend) -> RecordNotFoundException.
        return fallback
                .orElseThrow(() -> new RecordNotFoundException("DisclosureGroup (DEFAULT)",
                        DEFAULT_GROUP_ID + "/" + key.getTypeCode() + "/" + key.getCatCode()))
                .getDisIntRate();
    }

    /**
     * Reproduces {@code 1300-COMPUTE-INTEREST}'s formula with exact fidelity &mdash;
     * the central requirement of this migration (AAP &sect;0.7.6).
     *
     * <p>COBOL: {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
     * The grouping and precedence are preserved <strong>without algebraic
     * rearrangement</strong>: the balance is multiplied by the rate <em>first</em>,
     * then divided by the literal {@code 1200}. The result scale is {@code 2}
     * ({@code WS-MONTHLY-INT PIC S9(09)V99}) and the division uses
     * {@link RoundingMode#HALF_EVEN} (banker's rounding) per AAP &sect;0.7.3 /
     * &sect;0.7.6. All operands are {@link BigDecimal}; no {@code float}/{@code double}
     * is used anywhere.</p>
     *
     * @param tranCatBal the transaction-category balance ({@code TRAN-CAT-BAL})
     * @param disIntRate the disclosure-group interest rate ({@code DIS-INT-RATE},
     *                   guaranteed non-zero by the caller's guard)
     * @return the monthly interest ({@code WS-MONTHLY-INT}), scale&nbsp;2
     */
    private BigDecimal computeMonthlyInterest(BigDecimal tranCatBal, BigDecimal disIntRate) {
        // COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // Multiply first, then divide by the literal 1200 -- match COBOL grouping exactly.
        return tranCatBal.multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONTHLY_INTEREST_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Reproduces {@code 1300-B-WRITE-TX}'s field {@code MOVE}s, building (but not
     * persisting) the interest {@link Transaction} from the resolved account, card
     * number and computed monthly interest.
     *
     * <p>The fields are populated in COBOL order (CBACT04C lines 473-498). The
     * transaction id is {@code PARM-DATE} concatenated with a 6-digit zero-padded
     * run-global suffix &mdash; COBOL {@code ADD 1 TO WS-TRANID-SUFFIX} (executed
     * <em>before</em> the {@code STRING}) followed by
     * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}.
     * The pre-increment ({@code ++tranIdSuffix}) reproduces "increment, then use",
     * and the suffix is run-global (never reset per account). The origination and
     * processing timestamps are set to the <em>same</em> value from a single
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} (COBOL moves {@code DB2-FORMAT-TS} to both
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}).</p>
     *
     * <p>This method performs <strong>no persistence</strong>; the COBOL
     * {@code WRITE FD-TRANFILE-REC} is the writer's responsibility.</p>
     *
     * @param account         the resolved owning account (provides {@code ACCT-ID}
     *                        for the description)
     * @param cardNumber      the resolved {@code XREF-CARD-NUM}
     * @param monthlyInterest the computed {@code WS-MONTHLY-INT} (the transaction
     *                        amount)
     * @return the built interest {@link Transaction}, ready for the interest writer
     */
    private Transaction buildInterestTransaction(Account account, String cardNumber,
                                                 BigDecimal monthlyInterest) {
        final Transaction tran = new Transaction();

        // ADD 1 TO WS-TRANID-SUFFIX (PIC 9(06)) happens BEFORE the STRING -> pre-increment.
        // STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID:
        // PARM-DATE (10 chars) + suffix zero-padded to 6 digits = the 16-char TRAN-ID.
        // The suffix is RUN-GLOBAL (not reset per account); see the field's Javadoc.
        tran.setTranId(String.format("%s%06d", parmDate, ++tranIdSuffix));

        tran.setTranTypeCd(INTEREST_TRAN_TYPE_CD);            // MOVE '01'     TO TRAN-TYPE-CD
        tran.setTranCatCd(INTEREST_TRAN_CAT_CD);              // MOVE '05'     TO TRAN-CAT-CD (Integer 5)
        tran.setTranSource(INTEREST_TRAN_SOURCE);             // MOVE 'System' TO TRAN-SOURCE
        // STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
        // (the trailing space in the prefix literal is part of the COBOL literal).
        tran.setTranDesc(INTEREST_TRAN_DESC_PREFIX + account.getAcctId());
        tran.setTranAmt(monthlyInterest);                     // MOVE WS-MONTHLY-INT TO TRAN-AMT
        tran.setTranMerchantId(INTEREST_TRAN_MERCHANT_ID);    // MOVE 0      TO TRAN-MERCHANT-ID
        tran.setTranMerchantName(BLANK_MERCHANT_FIELD);       // MOVE SPACES TO TRAN-MERCHANT-NAME
        tran.setTranMerchantCity(BLANK_MERCHANT_FIELD);       // MOVE SPACES TO TRAN-MERCHANT-CITY
        tran.setTranMerchantZip(BLANK_MERCHANT_FIELD);        // MOVE SPACES TO TRAN-MERCHANT-ZIP
        tran.setTranCardNum(cardNumber);                      // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM

        // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP once; MOVE DB2-FORMAT-TS to BOTH timestamps.
        final LocalDateTime timestamp = nextDb2Timestamp();
        tran.setTranOrigTs(timestamp);                        // MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS
        tran.setTranProcTs(timestamp);                        // MOVE DB2-FORMAT-TS TO TRAN-PROC-TS
        return tran;
    }

    /**
     * Produces a timestamp truncated to COBOL hundredths precision, reproducing
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} on the current {@link #clock} time.
     *
     * <p>The COBOL paragraph builds a 26-character DB2-format string from
     * {@code FUNCTION CURRENT-DATE}, keeping hundredths-of-second precision and
     * zero-padding the remaining fractional digits. This method builds that exact
     * rendering via {@link #db2FormatTimestamp(LocalDateTime)} and parses it back, so
     * the {@link LocalDateTime} stored on the interest transaction carries precisely
     * the COBOL hundredths precision (the rest zeroed). Identical to the helper used
     * by {@code TransactionPostingProcessor} so both batch paths agree byte-for-byte.</p>
     *
     * @return the current timestamp truncated to COBOL hundredths precision
     */
    private LocalDateTime nextDb2Timestamp() {
        // FUNCTION CURRENT-DATE -> java.time (the Clock is injectable for deterministic tests).
        final LocalDateTime now = LocalDateTime.now(clock);
        return LocalDateTime.parse(db2FormatTimestamp(now), DB2_TIMESTAMP_FULL);
    }

    /**
     * Renders a {@link LocalDateTime} into the 26-character DB2 timestamp string
     * produced by COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP}:
     * {@code yyyy-MM-dd-HH.mm.ss.} + a 2-digit hundredths-of-second value
     * ({@code COB-MIL}) + the literal {@code "0000"} ({@code COB-REST}), for example
     * {@code 2022-07-19-23.12.32.680000}.
     *
     * <p>The hundredths are computed as {@code nano / 10_000_000} (0&ndash;99),
     * reproducing the COBOL {@code COB-MIL PIC X(02)} hundredths field, and the
     * trailing {@code "0000"} reproduces the {@code COB-REST}/{@code DB2-REST} literal
     * so the fractional portion is always six digits (26 characters total).</p>
     *
     * @param now the timestamp to render (typically {@link LocalDateTime#now(Clock)})
     * @return the 26-character DB2-format timestamp string
     */
    private static String db2FormatTimestamp(LocalDateTime now) {
        // COB-MIL: hundredths-of-second (2 digits); COB-REST: literal '0000'.
        final int hundredths = now.getNano() / 10_000_000;
        return DB2_TIMESTAMP_BASE.format(now) + String.format("%02d", hundredths) + "0000";
    }
}
