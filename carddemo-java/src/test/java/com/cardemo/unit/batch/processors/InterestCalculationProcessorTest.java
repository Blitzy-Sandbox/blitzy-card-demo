package com.cardemo.unit.batch.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.InterestCalculationProcessor;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fast, fully-mocked unit test for {@link InterestCalculationProcessor} &mdash; the Spring Batch
 * {@code ItemProcessor<TransactionCategoryBalance, InterestCalculationResult>} that reproduces the
 * {@code 1200-GET-INTEREST-RATE} disclosure-group lookup (with its {@code DEFAULT} fallback), the
 * {@code 1300-COMPUTE-INTEREST} interest formula and the {@code 1300-B-WRITE-TX} field mapping of the
 * legacy AWS CardDemo interest-calculator batch program {@code app/cbl/CBACT04C.cbl}.
 *
 * <h2>Provenance / governance</h2>
 * <p>The COBOL source {@code app/cbl/CBACT04C.cbl} is <strong>read-only reference</strong> material at the
 * frozen legacy baseline commit SHA {@code 27d6c6f}; it is <strong>never copied</strong> into this
 * repository and is referenced here only by SHA and paragraph/line locator. Per the Minimal Change Clause
 * (AAP &sect;0.7.1) and the 100% behavioural-parity requirement (AAP &sect;0.7.2), these tests assert
 * COBOL-identical arithmetic, control flow and field values and invent nothing. <strong>Interest-formula
 * fidelity is the central requirement</strong> (AAP &sect;0.7.3 / &sect;0.7.6). The application base package
 * is {@code com.cardemo} (decision D-006, <em>not</em> {@code com.carddemo}).</p>
 *
 * <h2>The four parity points this test locks down</h2>
 * <ol>
 *   <li><strong>Formula, unrearranged.</strong> COBOL {@code COMPUTE WS-MONTHLY-INT =
 *       ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (CBACT04C L464-465) is reproduced <em>exactly</em> as
 *       {@code tranCatBal.multiply(disIntRate).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN)}
 *       &mdash; multiply first, then divide by the literal {@code 1200}, scale&nbsp;2, banker's rounding;
 *       never algebraically rearranged.</li>
 *   <li><strong>Zero-rate path.</strong> COBOL {@code IF DIS-INT-RATE NOT = 0} (L214): a zero rate skips
 *       {@code 1300-COMPUTE-INTEREST}/{@code 1300-B-WRITE-TX} but the row is still processed, so the
 *       processor returns a <em>non-null</em> carrier (never {@code null}, which would make Spring Batch
 *       silently filter the row out of the chunk) with {@link BigDecimal#ZERO} interest, no transaction
 *       and {@code interestApplied == false}.</li>
 *   <li><strong>DEFAULT fallback = a literal second {@code findById}.</strong> COBOL status&nbsp;23 on the
 *       primary disclosure-group read re-reads with group id {@code 'DEFAULT'} (L437-438); a missing
 *       {@code DEFAULT} group is a COBOL abend (L455) &rarr; {@link RecordNotFoundException}.</li>
 *   <li><strong>Run-global transaction-id suffix.</strong> COBOL {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}
 *       (L173) is incremented {@code ADD 1 TO WS-TRANID-SUFFIX} (L474) across the <em>whole run</em>, not
 *       reset at account boundaries.</li>
 * </ol>
 *
 * <h2>Test strategy</h2>
 * <p>This is a pure unit test: {@code @ExtendWith(MockitoExtension.class)} with {@code @Mock} repositories
 * and the processor instantiated directly &mdash; <strong>no</strong> Spring context, database, AWS,
 * Testcontainers or {@code spring-batch-test}, and no new dependencies. Mockito runs in its default
 * {@code STRICT_STUBS} mode; because the production {@code process(..)} resolves the card cross-reference
 * (1110-GET-XREF-DATA) <em>before</em> the {@code DIS-INT-RATE NOT = 0} guard, every path that gets past the
 * account read consumes the account, cross-reference and disclosure-group stubs, so each test stubs exactly
 * those lookups and <strong>no {@code lenient()} is required</strong>. All monetary values are
 * {@link BigDecimal} built from {@link String} literals and compared with
 * {@code compareTo}/{@code isEqualByComparingTo}, never {@code equals} (AAP &sect;0.7.3); the lone
 * {@link BigDecimal#scale()} check pins {@code WS-MONTHLY-INT PIC S9(09)V99} and is distinct from value
 * comparison.</p>
 *
 * <h2>Seams used (production-provided)</h2>
 * <ul>
 *   <li><strong>Constructor.</strong> The processor exposes a public test-friendly constructor
 *       {@code (AccountRepository, DisclosureGroupRepository, CardCrossReferenceRepository, Clock)} whose
 *       first three parameters are in the same order as the production {@code @Autowired} 3-arg
 *       constructor. This test uses the 4-arg overload with a {@link #FIXED_CLOCK fixed clock} so the
 *       generated {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} are deterministic (the same seam the sibling
 *       {@code TransactionPostingProcessorTest} uses); no Spring context is involved.</li>
 *   <li><strong>{@code parmDate}.</strong> The processor binds the run date via Spring Batch late binding
 *       ({@code @Value("#{jobParameters['parmDate']}")}) but also exposes a public
 *       {@link InterestCalculationProcessor#setParmDate(String) setParmDate} setter, so the test sets it
 *       directly to {@value #PARM_DATE} &mdash; no Spring context and no {@code ReflectionTestUtils}.</li>
 * </ul>
 *
 * <h2>Note on {@code java.time} timestamp field type (deviation rationale)</h2>
 * <p>COBOL {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} are {@code PIC X(26)} text, but the migrated
 * {@link Transaction} stores them as {@link LocalDateTime}. The "26-character DB2 shape" is therefore
 * asserted by rendering the stored value back through the COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP} layout
 * ({@code yyyy-MM-dd-HH.mm.ss.SSSSSS}) and checking length&nbsp;26 and the DB2 regex, rather than calling
 * {@code hasSize(26)} on a {@link LocalDateTime}. Because the clock is fixed the exact stamped value is also
 * asserted (the prompt's preferred deterministic path).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationProcessor — CBACT04C interest formula + DEFAULT fallback + tx mapping (SHA 27d6c6f)")
class InterestCalculationProcessorTest {

    // --- Shared fixture constants -------------------------------------------------------------------

    /** Owning account id (TRANCAT-ACCT-ID); golden-ish value informed by app/data/ASCII/tcatbal.txt. */
    private static final Long ACCT_ID = 11L;

    /** A second, distinct account id used to prove the run-global tranId suffix is not reset per account. */
    private static final Long ACCT_ID_2 = 22L;

    /** Account group id (ACCT-GROUP-ID) used to key the primary disclosure-group lookup. */
    private static final String GROUP_ID = "GRP1";

    /** Transaction-type code component (TRANCAT-TYPE-CD / DIS-TRAN-TYPE-CD). */
    private static final String TYPE_CODE = "01";

    /** Transaction-category code component (TRANCAT-CD / DIS-TRAN-CAT-CD). */
    private static final Integer CAT_CODE = 5;

    /** The literal DEFAULT disclosure-group id COBOL re-reads with (1200-GET-INTEREST-RATE, L437). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Cross-reference card number (XREF-CARD-NUM) resolved for the interest transaction. */
    private static final String CARD_NUM = "4111111111111111";

    /** Cross-reference card number for the second account (suffix test). */
    private static final String CARD_NUM_2 = "4222222222222222";

    /** The run date job parameter (PARM-DATE PIC X(10)); fixed so the built tranId is deterministic. */
    private static final String PARM_DATE = "2022-12-31";

    /**
     * Fixed clock pinned to a sub-hundredth instant so the interest transaction's timestamps are
     * deterministic AND exercise the COBOL hundredths truncation of {@code Z-GET-DB2-FORMAT-TIMESTAMP}:
     * {@code 680,000,000 ns -> 68 hundredths -> ".680000"}.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-12-31T23:12:32.680000000Z"), ZoneOffset.UTC);

    /**
     * Exact value {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} must hold after
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} runs on {@link #FIXED_CLOCK}: the nanoseconds are truncated to
     * hundredths (680,000,000 ns). COBOL moves the SAME {@code DB2-FORMAT-TS} into both fields, so the two
     * timestamps are identical.
     */
    private static final LocalDateTime EXPECTED_TS =
            LocalDateTime.of(2022, 12, 31, 23, 12, 32, 680_000_000);

    /**
     * The 26-character DB2 timestamp layout produced by COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * ({@code yyyy-MM-dd-HH.mm.ss.} + six fractional digits). Used only to render the stored
     * {@link LocalDateTime} back to its external-interface text shape for the length/regex assertion.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /** Regex for the 26-character DB2 timestamp shape {@code yyyy-MM-dd-HH.mm.ss.ffffff}. */
    private static final String DB2_TIMESTAMP_REGEX =
            "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private CardCrossReferenceRepository crossReferenceRepository;

    private InterestCalculationProcessor processor;

    @BeforeEach
    void setUp() {
        // Production constructor parameter order is (accountRepository, disclosureGroupRepository,
        // crossReferenceRepository[, clock]). The 4-arg overload injects a FIXED clock so the freshly
        // stamped TRAN-ORIG-TS/TRAN-PROC-TS are deterministic; the 3-arg production constructor would use
        // Clock.systemDefaultZone(). All three repositories are STRICT_STUBS mocks.
        processor = new InterestCalculationProcessor(
                accountRepository, disclosureGroupRepository, crossReferenceRepository, FIXED_CLOCK);
        // PARM-DATE arrives via Spring Batch late binding in production; set it directly through the
        // public setParmDate seam (no Spring context, no ReflectionTestUtils).
        processor.setParmDate(PARM_DATE);
    }

    // --- Phase A: fixtures / builders ---------------------------------------------------------------

    /**
     * Builds a {@link TransactionCategoryBalance} staging row with the {@code @EmbeddedId} composite key
     * {@code (acctId, typeCode, catCode)} and the running balance {@code TRAN-CAT-BAL}.
     *
     * @param acctId   owning account id (TRANCAT-ACCT-ID)
     * @param typeCode transaction-type code (TRANCAT-TYPE-CD)
     * @param catCode  transaction-category code (TRANCAT-CD)
     * @param balance  the running balance as a decimal string (TRAN-CAT-BAL)
     * @return a fresh, fully-populated category-balance input row
     */
    private TransactionCategoryBalance tcb(long acctId, String typeCode, int catCode, String balance) {
        TransactionCategoryBalance row = new TransactionCategoryBalance();
        row.setId(new TransactionCategoryBalanceId(acctId, typeCode, catCode));
        row.setTranCatBal(new BigDecimal(balance));
        return row;
    }

    /**
     * Builds an {@link Account} carrying just the fields the processor reads: the id (for the result
     * carrier and the transaction description) and the account-group id (for the disclosure-group key).
     *
     * @param id      the account id (ACCT-ID)
     * @param groupId the account-group id (ACCT-GROUP-ID)
     * @return a fresh account
     */
    private Account acct(long id, String groupId) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctGroupId(groupId);
        return account;
    }

    /**
     * Builds a {@link DisclosureGroup} carrying the interest rate {@code DIS-INT-RATE}. The processor only
     * reads {@link DisclosureGroup#getDisIntRate()}, so the embedded key is left unset.
     *
     * @param rate the disclosure-group interest rate as a decimal string (DIS-INT-RATE, scale 2)
     * @return a fresh disclosure group with the supplied rate
     */
    private DisclosureGroup dg(String rate) {
        DisclosureGroup group = new DisclosureGroup();
        group.setDisIntRate(new BigDecimal(rate));
        return group;
    }

    /**
     * Builds a {@link CardCrossReference} resolving an account id to its primary card number, reproducing
     * the {@code CXACAIX} alternate-index row read by {@code 1110-GET-XREF-DATA}.
     *
     * @param cardNum the cross-reference card number (XREF-CARD-NUM)
     * @param acctId  the owning account id (XREF-ACCT-ID)
     * @return a fresh cross-reference row
     */
    private CardCrossReference xref(String cardNum, long acctId) {
        CardCrossReference crossReference = new CardCrossReference();
        crossReference.setXrefCardNum(cardNum);
        crossReference.setXrefAcctId(acctId);
        return crossReference;
    }

    /**
     * Convenience: the primary disclosure-group key (account group + type + category) for the standard
     * fixture, i.e. the key the processor builds first in {@code 1200-GET-INTEREST-RATE}.
     *
     * @return the primary {@link DisclosureGroupId}
     */
    private DisclosureGroupId primaryKey() {
        return new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE);
    }

    /**
     * Convenience: the {@code DEFAULT}-group key the processor builds on the fallback path
     * ({@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}), preserving the SAME type and category as the
     * primary key.
     *
     * @return the {@code DEFAULT} {@link DisclosureGroupId}
     */
    private DisclosureGroupId defaultKey() {
        return new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CAT_CODE);
    }

    /**
     * Stubs the account read (1100-GET-ACCT-DATA) and the cross-reference read (1110-GET-XREF-DATA) for the
     * standard {@link #ACCT_ID} fixture. Both are consumed by every path that gets past the account read
     * (the xref lookup runs before the rate guard), so this keeps each test free of {@code lenient()}.
     */
    private void stubAccountAndXref() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct(ACCT_ID, GROUP_ID)));
        when(crossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));
    }

    // --- Phase B: the interest formula (1300-COMPUTE-INTEREST, CBACT04C L464-465) -------------------

    /**
     * The central parity requirement (AAP &sect;0.7.3 / &sect;0.7.6): the COBOL interest formula must be
     * reproduced with exact decimal fidelity and <strong>never algebraically rearranged</strong>.
     *
     * <p><strong>COBOL (CBACT04C L464-465):</strong>
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, result
     * {@code WS-MONTHLY-INT PIC S9(09)V99} (scale 2).</p>
     *
     * <p><strong>Java (production, asserted here):</strong>
     * {@code tranCatBal.multiply(disIntRate).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN)}
     * &mdash; multiply first, then divide by the literal {@code 1200}, scale&nbsp;2, banker's rounding. A
     * future maintainer must NOT "simplify" this (e.g. pre-dividing the rate by 1200, or using {@code /12}
     * then {@code /100}, or {@code HALF_UP}): the grouping, divisor literal, scale and rounding mode are all
     * part of the parity contract.</p>
     */
    @Nested
    @DisplayName("1300-COMPUTE-INTEREST: (TRAN-CAT-BAL * DIS-INT-RATE) / 1200, scale 2, HALF_EVEN (CBACT04C L464-465)")
    class InterestFormula {

        @Test
        @DisplayName("known value: 1000.00 * 12.00 / 1200 = 10.00 (scale 2); interest applied; tx amount = interest")
        void knownValueOnePercentMonthlyEqualsTenDollars() {
            stubAccountAndXref();
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));

            assertThat(result).isNotNull();
            assertThat(result.interestApplied()).isTrue();
            assertThat(result.accountId()).isEqualTo(ACCT_ID);
            // 1000.00 * 12.00 / 1200 = 10.00 (annual 12% -> monthly amount). compareTo, never equals.
            assertThat(result.monthlyInterest()).isEqualByComparingTo("10.00");
            assertThat(result.interestTransaction()).isNotNull();
            assertThat(result.interestTransaction().getTranAmt()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("HALF_EVEN proof: 100.00 * 0.30 / 1200 = 0.025 -> 0.02 (rounds to EVEN; HALF_UP would give 0.03)")
        void bankersRoundingRoundsHalfDownToEven() {
            stubAccountAndXref();
            // raw quotient 30/1200 = 0.025, exactly on the .005 boundary; HALF_EVEN picks the even neighbour
            // 0.02 (the '2' digit), whereas HALF_UP would round away from zero to 0.03 -> this proves the
            // production divide() uses RoundingMode.HALF_EVEN, not HALF_UP.
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("0.30")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "100.00"));

            assertThat(result.interestApplied()).isTrue();
            assertThat(result.monthlyInterest()).isEqualByComparingTo("0.02");
        }

        @Test
        @DisplayName("HALF_EVEN proof: 100.00 * 1.50 / 1200 = 0.125 -> 0.12 (rounds to EVEN; HALF_UP would give 0.13)")
        void bankersRoundingRoundsHalfDownToEvenSecondCase() {
            stubAccountAndXref();
            // raw quotient 150/1200 = 0.125, exactly on the .005 boundary; HALF_EVEN picks the even neighbour
            // 0.12, whereas HALF_UP would give 0.13 -> a second independent proof of banker's rounding.
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("1.50")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "100.00"));

            assertThat(result.interestApplied()).isTrue();
            assertThat(result.monthlyInterest()).isEqualByComparingTo("0.12");
        }

        @Test
        @DisplayName("HALF_EVEN to even neighbour: 100.00 * 0.90 / 1200 = 0.075 -> 0.08 (rounds toward even '8')")
        void bankersRoundingRoundsHalfUpToEven() {
            stubAccountAndXref();
            // raw quotient 90/1200 = 0.075, on the .005 boundary; HALF_EVEN rounds to the even neighbour
            // 0.08 (the '8' digit). (Here HALF_UP coincidentally also yields 0.08; the distinguishing cases
            // above carry the HALF_EVEN-vs-HALF_UP proof.)
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("0.90")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "100.00"));

            assertThat(result.interestApplied()).isTrue();
            assertThat(result.monthlyInterest()).isEqualByComparingTo("0.08");
        }

        @Test
        @DisplayName("scale is exactly 2: pins WS-MONTHLY-INT PIC S9(09)V99 (legitimate scale() check, not value comparison)")
        void monthlyInterestScaleIsTwo() {
            stubAccountAndXref();
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));

            // scale() (not value) is asserted here: WS-MONTHLY-INT is PIC S9(09)V99 -> exactly two decimals.
            assertThat(result.monthlyInterest().scale()).isEqualTo(2);
            assertThat(result.interestTransaction().getTranAmt().scale()).isEqualTo(2);
        }
    }

    // --- Phase C: zero-rate path (IF DIS-INT-RATE NOT = 0, CBACT04C L214) ---------------------------

    @Nested
    @DisplayName("zero rate: row processed but no interest tx; carrier non-null so the account roll-up still sees the row")
    class RateZeroProducesNoInterest {

        @Test
        @DisplayName("DIS-INT-RATE == 0 => no interest, carrier non-null (NOT null), interestApplied=false (CBACT04C L214)")
        void zeroRateReturnsNonNullCarrierWithNoTransaction() {
            stubAccountAndXref();
            // COBOL only performs 1300-COMPUTE-INTEREST / 1300-B-WRITE-TX when DIS-INT-RATE NOT = 0; a zero
            // rate advances the browse but writes no interest transaction.
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("0.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));

            // The processor must NOT return null: a null ItemProcessor result filters the row out of the
            // Spring Batch chunk, which would hide it from the downstream 1050-UPDATE-ACCOUNT roll-up.
            assertThat(result).isNotNull();
            assertThat(result.interestApplied()).isFalse();
            assertThat(result.interestTransaction()).isNull();
            // accountId is still populated so the account-boundary roll-up sees this row.
            assertThat(result.accountId()).isEqualTo(ACCT_ID);
            // Zero interest, checked with compareTo/signum (never equals: 0.00 is not equal() to 0).
            assertThat(result.monthlyInterest()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.monthlyInterest().signum()).isZero();
        }
    }

    // --- Phase D: DEFAULT disclosure-group fallback (1200-GET-INTEREST-RATE, CBACT04C L437-438, L455) -

    @Nested
    @DisplayName("1200-GET-INTEREST-RATE: primary miss => literal second findById with group 'DEFAULT' (CBACT04C L437-438)")
    class DefaultDisclosureGroupFallback {

        @Test
        @DisplayName("fallback succeeds: primary absent (status 23) -> re-read group 'DEFAULT'; rate from DEFAULT; two findById IN ORDER")
        void primaryMissReReadsWithDefaultGroupInOrder() {
            stubAccountAndXref();
            // COBOL DISCGRP-STATUS = '23' on the primary read -> MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
            // and PERFORM 1200-A-GET-DEFAULT-INT-RATE (a literal second keyed read).
            when(disclosureGroupRepository.findById(primaryKey())).thenReturn(Optional.empty());
            when(disclosureGroupRepository.findById(defaultKey()))
                    .thenReturn(Optional.of(dg("18.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));

            // Interest computed from the DEFAULT rate: 1000.00 * 18.00 / 1200 = 15.00.
            assertThat(result.interestApplied()).isTrue();
            assertThat(result.monthlyInterest()).isEqualByComparingTo("15.00");

            // Both lookups happened, in COBOL order: the primary key first, THEN the DEFAULT key (same
            // transaction type and category, only the group id changes to the literal "DEFAULT").
            InOrder ordered = inOrder(disclosureGroupRepository);
            ordered.verify(disclosureGroupRepository).findById(primaryKey());
            ordered.verify(disclosureGroupRepository).findById(defaultKey());
            ordered.verifyNoMoreInteractions();
            // The defaultKey() fixture pins the exact fallback key: group "DEFAULT", SAME type/cat as primary.
            assertThat(defaultKey().getGroupId()).isEqualTo(DEFAULT_GROUP_ID);
            assertThat(defaultKey().getTypeCode()).isEqualTo(TYPE_CODE);
            assertThat(defaultKey().getCatCode()).isEqualTo(CAT_CODE);
        }

        @Test
        @DisplayName("DEFAULT also missing => fatal RecordNotFoundException (COBOL abend 'ERROR READING DEFAULT DISCLOSURE GROUP', L455)")
        void primaryAndDefaultBothMissingIsFatal() {
            stubAccountAndXref();
            when(disclosureGroupRepository.findById(primaryKey())).thenReturn(Optional.empty());
            when(disclosureGroupRepository.findById(defaultKey())).thenReturn(Optional.empty());

            // COBOL displays 'ERROR READING DEFAULT DISCLOSURE GROUP' and abends -> RecordNotFoundException
            // (FILE STATUS "23"); the message references the DEFAULT disclosure-group key.
            assertThatThrownBy(() -> processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00")))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(DEFAULT_GROUP_ID);
        }
    }

    // --- Phase E: interest transaction field mapping (1300-B-WRITE-TX, CBACT04C L473-498) -----------

    @Nested
    @DisplayName("1300-B-WRITE-TX: the built interest Transaction carries the exact COBOL field values (CBACT04C L474-498)")
    class InterestTransactionFields {

        @Test
        @DisplayName("interest tx fields: type '01', cat 5, source 'System', desc 'Int. for a/c '+id, amt=interest, merchant 0/blank, cardNum=xref, origTs==procTs")
        void interestTransactionCarriesExactCobolFieldValues() {
            stubAccountAndXref();
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));
            Transaction tx = result.interestTransaction();

            assertThat(tx).isNotNull();
            assertThat(tx.getTranTypeCd()).isEqualTo("01");                 // MOVE '01' TO TRAN-TYPE-CD
            assertThat(tx.getTranCatCd()).isEqualTo(5);                     // MOVE '05' TO TRAN-CAT-CD (Integer 5)
            assertThat(tx.getTranSource()).isEqualTo("System");             // MOVE 'System' TO TRAN-SOURCE
            // STRING 'Int. for a/c ', ACCT-ID -> the trailing space before the id is part of the literal.
            assertThat(tx.getTranDesc())
                    .startsWith("Int. for a/c ")
                    .isEqualTo("Int. for a/c " + ACCT_ID);
            // MOVE WS-MONTHLY-INT TO TRAN-AMT: the amount equals the computed monthly interest exactly.
            assertThat(tx.getTranAmt()).isEqualByComparingTo(result.monthlyInterest());
            assertThat(tx.getTranAmt()).isEqualByComparingTo("10.00");
            assertThat(tx.getTranMerchantId()).isEqualTo(0L);              // MOVE 0 TO TRAN-MERCHANT-ID
            // MOVE SPACES TO TRAN-MERCHANT-NAME/CITY/ZIP -> stored as blank by the migrated entity.
            assertThat(tx.getTranMerchantName()).isBlank();
            assertThat(tx.getTranMerchantCity()).isBlank();
            assertThat(tx.getTranMerchantZip()).isBlank();
            // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM: resolved via findByXrefAcctId (1110-GET-XREF-DATA).
            assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);

            // COBOL moves the SAME DB2-FORMAT-TS into both TRAN-ORIG-TS and TRAN-PROC-TS (L497-498).
            assertThat(tx.getTranOrigTs()).isNotNull().isEqualTo(EXPECTED_TS);
            assertThat(tx.getTranProcTs()).isEqualTo(tx.getTranOrigTs());
            // The migrated field is a LocalDateTime (not 26-char text); the 26-character DB2 external shape
            // is verified by rendering the stored value back through Z-GET-DB2-FORMAT-TIMESTAMP's layout.
            String origRendered = tx.getTranOrigTs().format(DB2_TIMESTAMP_FORMAT);
            assertThat(origRendered).hasSize(26).matches(DB2_TIMESTAMP_REGEX);
        }

        @Test
        @DisplayName("tranId = PARM-DATE (10) + 6-digit zero-padded suffix => 16 chars, starts with PARM-DATE")
        void tranIdIsParmDatePlusSixDigitSuffix() {
            stubAccountAndXref();
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));
            String tranId = result.interestTransaction().getTranId();

            // STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID: 10 + 6 = 16 chars.
            assertThat(tranId).startsWith(PARM_DATE).hasSize(16);
            // The 6-character suffix (the part after the 10-char PARM-DATE) is all digits.
            assertThat(tranId.substring(PARM_DATE.length())).hasSize(6).containsOnlyDigits();
            // First interest tx of this run -> suffix 000001 (WS-TRANID-SUFFIX starts at 0, pre-incremented).
            assertThat(tranId).isEqualTo(PARM_DATE + "000001");
        }

        @Test
        @DisplayName("tranId suffix is run-global, NOT reset per account (CBACT04C WS-TRANID-SUFFIX, L173/L474)")
        void tranIdSuffixIsRunGlobalNotResetPerAccount() {
            // Two rows on the SAME processor instance for DIFFERENT accounts, each producing an interest tx.
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct(ACCT_ID, GROUP_ID)));
            when(accountRepository.findById(ACCT_ID_2)).thenReturn(Optional.of(acct(ACCT_ID_2, GROUP_ID)));
            when(crossReferenceRepository.findByXrefAcctId(ACCT_ID))
                    .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));
            when(crossReferenceRepository.findByXrefAcctId(ACCT_ID_2))
                    .thenReturn(List.of(xref(CARD_NUM_2, ACCT_ID_2)));
            // Both accounts share group GRP1, so both rows resolve the same primary disclosure-group key.
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            String firstTranId = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"))
                    .interestTransaction().getTranId();
            // Second row belongs to a DIFFERENT account; the suffix must NOT reset to 000001.
            String secondTranId = processor.process(tcb(ACCT_ID_2, TYPE_CODE, CAT_CODE, "1000.00"))
                    .interestTransaction().getTranId();

            // Run-global, monotonically increasing across the account boundary: 000001 then 000002.
            assertThat(firstTranId).isEqualTo(PARM_DATE + "000001");
            assertThat(secondTranId).isEqualTo(PARM_DATE + "000002");
        }
    }

    // --- Phase F: omitted / deferred behaviour (1400 fees stub, 1050 roll-up, account abend) --------

    @Nested
    @DisplayName("omitted/deferred behaviour: 1400 fees no-op, 1050 roll-up not here, account-miss fatal")
    class OmittedAndDeferredBehavior {

        @Test
        @DisplayName("1400-COMPUTE-FEES is a no-op stub in CBACT04C@27d6c6f — no fee added; tx amount == pure interest (Minimal Change Clause)")
        void feesStubAddsNothingBeyondInterest() {
            stubAccountAndXref();
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            InterestCalculationResult result = processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));

            // CBACT04C 1400-COMPUTE-FEES (L518) is "To be implemented" -> an empty EXIT stub. No fee logic
            // is invented here: the transaction amount is exactly WS-MONTHLY-INT with no extra component.
            assertThat(result.interestTransaction().getTranAmt())
                    .isEqualByComparingTo(result.monthlyInterest());
            assertThat(result.monthlyInterest()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("account roll-up (1050-UPDATE-ACCOUNT) is NOT the processor's job: no save, ACCT-CURR-BAL unchanged")
        void accountRollUpNotPerformedHere() {
            // Account roll-up (ADD WS-TOTAL-INT TO ACCT-CURR-BAL + REWRITE) happens at the account boundary
            // in the writer/job layer, not in this chunk-oriented ItemProcessor. The carrier only exposes
            // accountId + monthlyInterest for downstream aggregation.
            Account account = acct(ACCT_ID, GROUP_ID);
            account.setAcctCurrBal(new BigDecimal("500.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(crossReferenceRepository.findByXrefAcctId(ACCT_ID))
                    .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));
            when(disclosureGroupRepository.findById(primaryKey()))
                    .thenReturn(Optional.of(dg("12.00")));

            processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00"));

            // The processor must NOT mutate ACCT-CURR-BAL and must NOT persist the account.
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("500.00");
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("missing owning account (1100-GET-ACCT-DATA INVALID KEY) => fatal RecordNotFoundException (COBOL abend)")
        void missingAccountIsFatal() {
            // Only the account lookup is stubbed (empty); the xref/disclosure reads are never reached, so
            // under STRICT_STUBS they must not be stubbed.
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> processor.process(tcb(ACCT_ID, TYPE_CODE, CAT_CODE, "1000.00")))
                    .isInstanceOf(RecordNotFoundException.class);
        }
    }
}
