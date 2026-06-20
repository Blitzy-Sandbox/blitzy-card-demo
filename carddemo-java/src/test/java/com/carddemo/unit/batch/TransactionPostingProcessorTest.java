package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.enums.RejectCode;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.shared.FileStatusMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionPostingProcessor}, the per-record posting-stage
 * {@link org.springframework.batch.item.ItemProcessor} that re-platforms the COBOL daily-transaction
 * posting program {@code app/cbl/CBTRN02C.cbl} (logic only; traceability via source commit
 * {@code 27d6c6f}). This is the most behaviorally critical unit test in the batch tier: it proves
 * byte-exact parity with the COBOL four-stage validation cascade ({@code 1500-VALIDATE-TRAN} and its
 * {@code 1500-A}/{@code 1500-B} sub-paragraphs) and the success posting path
 * ({@code 2000-POST-TRANSACTION}, {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC}).
 *
 * <p>The binding parity guarantees verified here are:</p>
 * <ul>
 *   <li><strong>Reject codes 100&ndash;103</strong> &mdash; emitted in the exact COBOL order, with
 *       descriptions sourced from the {@link RejectCode} enum (the source of truth), never hardcoded
 *       literals.</li>
 *   <li><strong>Stage short-circuit</strong> &mdash; an absent cross-reference (stage A) stops before
 *       the account lookup; an absent account (stage B) stops before the category-balance lookup.</li>
 *   <li><strong>Non-short-circuiting C&rarr;D with 103 precedence</strong> &mdash; the overlimit (C)
 *       and expiration (D) checks run as two consecutive {@code IF} blocks with no {@code GO TO}
 *       between them; when both fail the expiration result (103) overwrites the overlimit result
 *       (102). This is the headline parity assertion.</li>
 *   <li><strong>{@code BigDecimal.compareTo} (never {@code equals})</strong> &mdash; the inclusive
 *       {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} boundary and the credit/debit sign split are proven
 *       with scale-2 decimals.</li>
 *   <li><strong>Deterministic {@code tranProcTs}</strong> &mdash; produced from an injected fixed
 *       {@link Clock} in DB2 format {@code yyyy-MM-dd-HH.mm.ss.SSSSSS}.</li>
 *   <li><strong>80-character reject trailer</strong> &mdash; a 4-digit zero-padded reason code
 *       followed by the 76-character space-padded description.</li>
 *   <li><strong>Read-only processor</strong> &mdash; the processor reads and computes only; it never
 *       calls a repository {@code save}/{@code saveAndFlush}. Persistence is the writer's job.</li>
 * </ul>
 *
 * <p>Pure-JVM test: Mockito repositories plus {@link FileStatusMapper}, a real
 * {@link SimpleMeterRegistry}, and a fixed {@link Clock}; no Spring context, Testcontainers, or AWS
 * dependency. The {@link Clock} is supplied through the processor's explicit six-argument constructor
 * to keep the processing timestamp deterministic.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionPostingProcessorTest {

    /** Card number that resolves to a present cross-reference and account in the happy-path stubs. */
    private static final String CARD = "1234567890123456";

    /** Card number deliberately absent from the cross-reference repository (drives the 100 reject). */
    private static final String MISSING_CARD = "9999999999999999";

    /** Account id carried by the cross-reference and used as the account / composite-key id. */
    private static final Long ACCT_ID = 12345L;

    /** Transaction type code ({@code DALYTRAN-TYPE-CD}), a two-character string. */
    private static final String TYPE_CD = "01";

    /** Transaction category code ({@code DALYTRAN-CAT-CD}). */
    private static final int CAT_CD = 5;

    /**
     * 26-character origination timestamp ({@code DALYTRAN-ORIG-TS}); its first ten characters
     * ({@code 2022-07-18}) are the origination date used by the expiration check (stage D).
     */
    private static final String ORIG_TS = "2022-07-18-00.00.00.000000";

    /**
     * Expected DB2-format processing timestamp produced from {@link #FIXED_CLOCK} via the processor's
     * {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} formatter.
     */
    private static final String EXPECTED_PROC_TS = "2022-07-18-10.15.30.123456";

    /** Fixed clock so {@code tranProcTs} is deterministic across runs. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T10:15:30.123456Z"), ZoneOffset.UTC);

    @Mock
    private CardCrossReferenceRepository xrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository tcatbalRepository;

    @Mock
    private FileStatusMapper fileStatusMapper;

    /** Real Micrometer registry so the processed/rejected counters can be asserted directly. */
    private SimpleMeterRegistry registry;

    private TransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new TransactionPostingProcessor(
                xrefRepository, accountRepository, tcatbalRepository, fileStatusMapper, registry, FIXED_CLOCK);
    }

    /**
     * Builds a daily-transaction record (COBOL {@code DALYTRAN-RECORD}, copybook {@code CVTRA06Y})
     * with the card, type, category, amount, and origination timestamp varied per test and the
     * remaining descriptive fields fixed.
     *
     * @param card   the card number ({@code DALYTRAN-CARD-NUM})
     * @param typeCd the transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param catCd  the transaction category code ({@code DALYTRAN-CAT-CD})
     * @param amt    the transaction amount ({@code DALYTRAN-AMT}) as a scale-2 decimal literal
     * @param origTs the 26-character origination timestamp ({@code DALYTRAN-ORIG-TS})
     * @return a populated daily-transaction record
     */
    private DailyTransaction daily(final String card, final String typeCd, final int catCd,
            final String amt, final String origTs) {
        final DailyTransaction d = new DailyTransaction();
        d.setDalytranId("DT00000000000001");
        d.setDalytranTypeCd(typeCd);
        d.setDalytranCatCd(catCd);
        d.setDalytranSource("POS TERM");
        d.setDalytranDesc("GROCERY");
        d.setDalytranAmt(new BigDecimal(amt));
        d.setDalytranMerchantId(123456789L);
        d.setDalytranMerchantName("WHOLE FOODS");
        d.setDalytranMerchantCity("AUSTIN");
        d.setDalytranMerchantZip("78701");
        d.setDalytranCardNum(card);
        d.setDalytranOrigTs(origTs);
        d.setDalytranProcTs("");
        return d;
    }

    /**
     * Builds a card cross-reference (COBOL {@code CARD-XREF-RECORD}) linking the card to its account.
     *
     * @param card   the card number ({@code XREF-CARD-NUM})
     * @param acctId the account id ({@code XREF-ACCT-ID})
     * @return a populated cross-reference record
     */
    private CardCrossReference xref(final String card, final Long acctId) {
        final CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(card);
        x.setXrefCustId(7L);
        x.setXrefAcctId(acctId);
        return x;
    }

    /**
     * Builds an account (COBOL {@code ACCOUNT-RECORD}, copybook {@code CVACT01Y}) with the balance,
     * limit, cycle, and expiration fields the validation and posting computations read.
     *
     * @param id          the account id ({@code ACCT-ID})
     * @param currBal     the current balance ({@code ACCT-CURR-BAL})
     * @param creditLimit the credit limit ({@code ACCT-CREDIT-LIMIT})
     * @param cycCredit   the current-cycle credit ({@code ACCT-CURR-CYC-CREDIT})
     * @param cycDebit    the current-cycle debit ({@code ACCT-CURR-CYC-DEBIT})
     * @param expDate     the expiration date ({@code ACCT-EXPIRAION-DATE}), {@code yyyy-MM-dd}
     * @return a populated account record
     */
    private Account acct(final Long id, final String currBal, final String creditLimit,
            final String cycCredit, final String cycDebit, final String expDate) {
        final Account a = new Account();
        a.setAcctId(id);
        a.setAcctCurrBal(new BigDecimal(currBal));
        a.setAcctCreditLimit(new BigDecimal(creditLimit));
        a.setAcctCashCreditLimit(new BigDecimal("0.00"));
        a.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        a.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        a.setAcctExpiraionDate(expDate);
        a.setAcctGroupId("DEFAULT   ");
        return a;
    }

    // ----------------------------------------------------------------------------------------------
    // Stage A & B: short-circuit rejects (CBTRN02C 1500-A-LOOKUP-XREF / 1500-B-LOOKUP-ACCT).
    // ----------------------------------------------------------------------------------------------

    /**
     * Stage A ({@code 1500-A-LOOKUP-XREF}): an absent cross-reference rejects with code 100 and stops
     * the cascade before the account or category-balance repositories are touched.
     */
    @Test
    void stageA_missingXref_rejects100_andStops() {
        when(xrefRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

        final PostingResult r = processor.process(daily(MISSING_CARD, TYPE_CD, CAT_CD, "10.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(100);
        assertThat(r.rejectReasonDescription().strip()).isEqualTo(RejectCode.INVALID_CARD_NUMBER.getDescription());
        assertThat(r.postedTransaction()).isNull();
        assertThat(r.updatedAccount()).isNull();
        assertThat(r.updatedCategoryBalance()).isNull();
        assertThat(r.originalDailyTransaction()).isNotNull();
        verifyNoInteractions(accountRepository, tcatbalRepository);
    }

    /**
     * Stage B ({@code 1500-B-LOOKUP-ACCT}): a present cross-reference but an absent account rejects
     * with code 101 and stops before the category-balance repository is touched.
     */
    @Test
    void stageB_missingAccount_rejects101_andStops() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "10.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(101);
        assertThat(r.rejectReasonDescription().strip()).isEqualTo(RejectCode.ACCOUNT_NOT_FOUND.getDescription());
        assertThat(r.postedTransaction()).isNull();
        verifyNoInteractions(tcatbalRepository);
    }

    // ----------------------------------------------------------------------------------------------
    // Stage C (overlimit) and Stage D (expiration) evaluated singly, plus the inclusive boundary.
    // ----------------------------------------------------------------------------------------------

    /**
     * Stage C only ({@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}): the projected balance exceeds the
     * credit limit while the account is unexpired, so the reject code is 102.
     */
    @Test
    void stageC_overlimitOnly_rejects102() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "1000.00", "0.00", "2099-12-31")));

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "500.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(102);
        assertThat(r.rejectReasonDescription().strip()).isEqualTo(RejectCode.OVERLIMIT_TRANSACTION.getDescription());
        verifyNoInteractions(tcatbalRepository);
    }

    /**
     * Stage D only ({@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}): the account is expired
     * relative to the origination date while remaining within the credit limit, so the reject code is
     * 103.
     */
    @Test
    void stageD_expirationOnly_rejects103() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2020-01-01")));

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "10.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(103);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION.getDescription());
        verifyNoInteractions(tcatbalRepository);
    }

    /**
     * The overlimit boundary is inclusive: when the credit limit exactly equals the projected balance,
     * {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} holds (proving {@code compareTo >= 0}, not {@code equals}),
     * and with a passing expiration check the record posts rather than rejecting.
     */
    @Test
    void overlimitBoundary_equalCreditLimit_passesStageC() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "1000.00", ORIG_TS));

        assertThat(r.rejected()).isFalse();
        assertThat(r.rejectCode()).isNull();
        assertThat(r.postedTransaction()).isNotNull();
    }

    // ----------------------------------------------------------------------------------------------
    // Headline parity: C and D both fail -> 103 (no short-circuit; D overwrites C).
    // ----------------------------------------------------------------------------------------------

    /**
     * The single most important parity case. With the projected balance over the credit limit
     * (stage C would set 102) and the account expired (stage D sets 103), the two checks run
     * sequentially with no short-circuit, so the expiration result overwrites the overlimit result
     * and the final reject code must be 103, exactly as {@code CBTRN02C} {@code 1500-B-LOOKUP-ACCT}
     * runs its two {@code IF} blocks back to back without a {@code GO TO} between them.
     */
    @Test
    void bothOverlimitAndExpired_finalRejectCodeIs103_expirationPrecedence() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "1000.00", "0.00", "2020-01-01")));

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "500.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode())
                .withFailMessage("CBTRN02C C->D run sequentially without short-circuit; "
                        + "when both fail, 103 (expiration) must overwrite 102 (overlimit)")
                .isEqualTo(103);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION.getDescription());
        verifyNoInteractions(tcatbalRepository);
    }

    // ----------------------------------------------------------------------------------------------
    // Reject trailer layout: 4-digit zero-padded code + 76-character description = 80 bytes.
    // ----------------------------------------------------------------------------------------------

    /**
     * The reject trailer is exactly 80 characters: a 4-digit zero-padded reason code
     * ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}) followed by the 76-character space-padded
     * description ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). Driven through the stage-A
     * (100) reject.
     */
    @Test
    void rejectTrailer_is80Chars_codeZeroPaddedPlusDescription() {
        when(xrefRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

        final PostingResult r = processor.process(daily(MISSING_CARD, TYPE_CD, CAT_CD, "10.00", ORIG_TS));

        final String trailer = r.rejectTrailer();
        assertThat(trailer).isNotNull();
        assertThat(trailer).hasSize(80);
        assertThat(trailer.substring(0, 4)).isEqualTo("0100");
        assertThat(trailer.substring(4)).hasSize(76);
        assertThat(trailer.substring(4).strip()).isEqualTo(RejectCode.INVALID_CARD_NUMBER.getDescription());
    }

    // ----------------------------------------------------------------------------------------------
    // Success path: 1:1 mapping, deterministic timestamp, TCATBAL find-or-create, account sign split.
    // ----------------------------------------------------------------------------------------------

    /**
     * All stages pass and the category balance is absent ({@code 2700-A-CREATE-TCATBAL-REC}): the
     * processor builds a posted transaction by 1:1 field copy and initializes the new category
     * balance to the transaction amount. Also proves the composite key is built from
     * {@code (acctId, typeCd, catCd)}.
     */
    @Test
    void allStagesPass_tcatbalAbsent_createsBalanceEqualToAmount() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "150.00", ORIG_TS));

        assertThat(r.rejected()).isFalse();
        assertThat(r.postedTransaction()).isNotNull();
        assertThat(r.updatedAccount()).isNotNull();
        assertThat(r.updatedCategoryBalance()).isNotNull();

        final Transaction t = r.postedTransaction();
        assertThat(t.getTranId()).isEqualTo("DT00000000000001");
        assertThat(t.getTranTypeCd()).isEqualTo(TYPE_CD);
        assertThat(t.getTranCatCd()).isEqualTo(CAT_CD);
        assertThat(t.getTranSource()).isEqualTo("POS TERM");
        assertThat(t.getTranCardNum()).isEqualTo(CARD);
        assertThat(t.getTranOrigTs()).isEqualTo(ORIG_TS);
        assertThat(t.getTranMerchantId()).isEqualTo(123456789L);
        assertThat(t.getTranAmt()).isEqualByComparingTo(new BigDecimal("150.00"));

        assertThat(r.updatedCategoryBalance().getTranCatBal()).isEqualByComparingTo(new BigDecimal("150.00"));

        verify(tcatbalRepository).findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD));
    }

    /**
     * All stages pass and the category balance is present ({@code 2700-B-UPDATE-TCATBAL-REC}): the
     * transaction amount is added to the existing balance ({@code 40.00 + 150.00 = 190.00}).
     */
    @Test
    void allStagesPass_tcatbalPresent_addsAmountToExistingBalance() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        final TransactionCategoryBalance existing = new TransactionCategoryBalance();
        existing.setId(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD));
        existing.setTranCatBal(new BigDecimal("40.00"));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(existing));
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(false);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "150.00", ORIG_TS));

        assertThat(r.rejected()).isFalse();
        assertThat(r.updatedCategoryBalance().getTranCatBal()).isEqualByComparingTo(new BigDecimal("190.00"));
    }

    /**
     * The processing timestamp is stamped from the injected {@link Clock} in DB2 format
     * {@code yyyy-MM-dd-HH.mm.ss.SSSSSS}, making it deterministic.
     */
    @Test
    void tranProcTs_setFromInjectedClock_db2Format() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "150.00", ORIG_TS));

        assertThat(r.postedTransaction().getTranProcTs()).isEqualTo(EXPECTED_PROC_TS);
    }

    /**
     * Account update for a non-negative amount ({@code 2800-UPDATE-ACCOUNT-REC}): the amount is added
     * to both the current balance and the current-cycle credit, leaving the debit unchanged.
     */
    @Test
    void accountUpdate_positiveAmount_addsToCurrBalAndCycCredit() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "100.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "150.00", ORIG_TS));

        assertThat(r.updatedAccount().getAcctCurrBal()).isEqualByComparingTo("250.00");
        assertThat(r.updatedAccount().getAcctCurrCycCredit()).isEqualByComparingTo("150.00");
        assertThat(r.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
    }

    /**
     * Account update for a negative amount ({@code 2800-UPDATE-ACCOUNT-REC}): the amount is added to
     * the current balance and to the current-cycle debit (leaving the credit unchanged), proving the
     * {@code compareTo(BigDecimal.ZERO) >= 0} credit/debit sign split. The projected balance stays
     * within the limit so stage C passes.
     */
    @Test
    void accountUpdate_negativeAmount_addsToCurrBalAndCycDebit() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "100.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "-30.00", ORIG_TS));

        assertThat(r.updatedAccount().getAcctCurrBal()).isEqualByComparingTo("70.00");
        assertThat(r.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo("-30.00");
        assertThat(r.updatedAccount().getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
    }

    // ----------------------------------------------------------------------------------------------
    // Read-only invariant and metrics.
    // ----------------------------------------------------------------------------------------------

    /**
     * The processor reads and computes only; it never persists. Persistence of the posted
     * transaction, account, and category balance is the writer's responsibility within the chunk
     * transaction.
     */
    @Test
    void processor_neverPersists() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        final PostingResult r = processor.process(daily(CARD, TYPE_CD, CAT_CD, "150.00", ORIG_TS));

        assertThat(r.rejected()).isFalse();
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveAndFlush(any());
        verify(tcatbalRepository, never()).save(any());
        verify(xrefRepository, never()).save(any());
    }

    /**
     * A posted record increments the tag-free {@code carddemo.batch.records.processed} counter once.
     */
    @Test
    void incrementsProcessed_onPostedResult() {
        when(xrefRepository.findById(CARD)).thenReturn(Optional.of(xref(CARD, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(acct(ACCT_ID, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        processor.process(daily(CARD, TYPE_CD, CAT_CD, "150.00", ORIG_TS));

        assertThat(registry.get("carddemo.batch.records.processed").counter().count()).isEqualTo(1.0);
    }

    /**
     * A reject increments the {@code carddemo.batch.records.rejected} counter once, tagged by the
     * {@link RejectCode} enum name (the production tag value is {@code code.name()}, not the numeric
     * code). Driven through the stage-A (100) reject.
     */
    @Test
    void incrementsRejected_onStageAReject_taggedByRejectCodeName() {
        when(xrefRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

        processor.process(daily(MISSING_CARD, TYPE_CD, CAT_CD, "10.00", ORIG_TS));

        assertThat(registry.get("carddemo.batch.records.rejected")
                .tag("reason", RejectCode.INVALID_CARD_NUMBER.name())
                .counter().count()).isEqualTo(1.0);
    }
}
