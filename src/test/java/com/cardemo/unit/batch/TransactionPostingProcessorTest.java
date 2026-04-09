package com.cardemo.unit.batch;

import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionPostingProcessor} — the Spring Batch
 * {@code ItemProcessor<DailyTransaction, Transaction>} that implements the
 * CBTRN02C.cbl daily-transaction-posting 4-stage validation cascade.
 *
 * <p>The four sequential validation stages mirror COBOL paragraphs:
 * <ol>
 *   <li>Stage 1 (1500-A-LOOKUP-XREF): Card cross-reference lookup — reject code 100</li>
 *   <li>Stage 2 (1500-B-LOOKUP-ACCT): Account lookup — reject code 101</li>
 *   <li>Stage 3 (1500-C-CHECK-CREDIT): Credit limit check — reject code 102</li>
 *   <li>Stage 4 (1500-D-CHECK-EXPIRY): Account expiry date check — reject code 103</li>
 * </ol>
 *
 * <p>Pure unit tests — no Spring context. All three repository dependencies are Mockito mocks
 * injected via constructor. All financial fields use {@link BigDecimal} exclusively; assertions
 * use {@code isEqualByComparingTo()} (never {@code equals()}) per AAP §0.8.2.
 */
@ExtendWith(MockitoExtension.class)
class TransactionPostingProcessorTest {

    // ── Mock Dependencies ────────────────────────────────────────────────────

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Mock
    private MetricsConfig metricsConfig;

    // ── Class Under Test ─────────────────────────────────────────────────────

    private TransactionPostingProcessor processor;

    // ── Reusable Test Constants ──────────────────────────────────────────────

    private static final String CARD_NUM = "4000000000000001";
    private static final String ACCT_ID = "00000000001";
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CURR_BAL = new BigDecimal("5000.00");
    private static final BigDecimal CYC_CREDIT = new BigDecimal("1000.00");
    private static final BigDecimal CYC_DEBIT = new BigDecimal("500.00");
    private static final BigDecimal TRAN_AMOUNT = new BigDecimal("100.00");
    private static final LocalDate FUTURE_EXPIRY_DATE = LocalDate.of(2025, 12, 31);
    private static final LocalDate PAST_EXPIRY_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDateTime DEFAULT_TRAN_TS = LocalDateTime.of(2024, 6, 15, 10, 30);
    private static final String TYPE_CD = "SA";
    private static final Short CAT_CD = (short) 5001;
    private static final String CUST_ID = "000000001";

    // ── Setup ────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        processor = new TransactionPostingProcessor(
                cardCrossReferenceRepository,
                accountRepository,
                transactionCategoryBalanceRepository,
                metricsConfig
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stage 1: XREF Lookup — Reject Code 100
    // Maps CBTRN02C 1500-A-LOOKUP-XREF: FILE STATUS '23' → INVALID CARD NUMBER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When the card number from the daily transaction is not found in the CARDXREF
     * dataset, the processor rejects the item with code 100 and returns null
     * (Spring Batch filtering convention). No further validation stages execute.
     */
    @Test
    void process_shouldRejectWithCode100WhenCardNotFoundInXref() throws Exception {
        String unknownCard = "9999999999999999";
        DailyTransaction dt = createDailyTransaction(unknownCard, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(unknownCard))
                .thenReturn(Optional.empty());

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
        // Stage 1 failure short-circuits: account lookup never attempted
        verify(accountRepository, never()).findById(anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stage 2: Account Lookup — Reject Code 101
    // Maps CBTRN02C 1500-B-LOOKUP-ACCT: FILE STATUS '23' → ACCOUNT NOT FOUND
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When the XREF resolves to an account ID that does not exist in the ACCTDAT
     * dataset, the processor rejects the item with code 101. TCATBAL operations
     * are never reached.
     */
    @Test
    void process_shouldRejectWithCode101WhenAccountNotFound() throws Exception {
        String unknownAcct = "99999999999";
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, unknownAcct)));
        when(accountRepository.findById(unknownAcct))
                .thenReturn(Optional.empty());

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
        // Stage 2 failure short-circuits: TCATBAL lookup never attempted
        verify(transactionCategoryBalanceRepository, never()).findById(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stage 3: Credit Limit Check — Reject Code 102
    // Maps CBTRN02C 1500-C-CHECK-CREDIT: COMPUTE WS-TEMP-BAL =
    //   ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
    // Reject when tempBal > creditLimit (COBOL: strict >)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When cycCredit - cycDebit + tranAmount exceeds the credit limit,
     * the processor rejects with code 102 (OVERLIMIT TRANSACTION).
     * Formula: tempBal = 4500.00 - 0.00 + 600.00 = 5100.00 > 5000.00 → REJECT
     */
    @Test
    void process_shouldRejectWithCode102WhenOverCreditLimit() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, new BigDecimal("600.00"));

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                new BigDecimal("4500.00"), BigDecimal.ZERO, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
    }

    /**
     * Boundary test: tempBal exactly equals creditLimit is NOT rejected.
     * COBOL uses strict greater-than ({@code >}), not greater-than-or-equal.
     * Formula: tempBal = 4400.00 - 0.00 + 600.00 = 5000.00 == 5000.00 → OK
     */
    @Test
    void process_shouldNotRejectWhenExactlyAtCreditLimit() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, new BigDecimal("600.00"));

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                new BigDecimal("4400.00"), BigDecimal.ZERO, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("200.00"))));

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
    }

    /**
     * Verifies the debit component of the credit-limit formula is correctly
     * subtracted. cycDebit reduces tempBal, but a large credit + amount can
     * still exceed the limit.
     * Formula: tempBal = 4900.00 - 200.00 + 400.00 = 5100.00 > 5000.00 → REJECT
     */
    @Test
    void process_shouldAccountForDebitInCreditLimitCheck() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, new BigDecimal("400.00"));

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                new BigDecimal("4900.00"), new BigDecimal("200.00"), FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stage 4: Expiry Date Check — Reject Code 103
    // Maps CBTRN02C 1500-D-CHECK-EXPIRY: IF ACCT-EXPIRAION-DATE < tran-date
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When the account expiry date is BEFORE the transaction date, the
     * transaction is rejected with code 103 (CARD EXPIRED).
     * Account expired 2020-01-01, transaction on 2024-06-15 → REJECT
     */
    @Test
    void process_shouldRejectWithCode103WhenAccountExpired() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT,
                LocalDateTime.of(2024, 6, 15, 10, 30));

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, PAST_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
    }

    /**
     * Boundary test: when the transaction date equals the account expiry date,
     * the transaction is NOT rejected. COBOL: {@code IF ACCT-EXPIRAION-DATE <
     * date → reject}; equal means NOT before, so it passes.
     */
    @Test
    void process_shouldNotRejectWhenTransactionDateEqualsExpiryDate() throws Exception {
        LocalDate sameDate = LocalDate.of(2024, 6, 15);
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT,
                LocalDateTime.of(2024, 6, 15, 10, 30));

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, sameDate);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("200.00"))));

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Successful Posting — Field Mapping (2000-POST-TRANSACTION)
    // Maps CBTRN02C 2000-POST-TRANSACTION: builds Transaction from DailyTransaction
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Validates that a fully valid daily transaction produces a non-null
     * {@link Transaction} entity with every field correctly mapped from the
     * source {@link DailyTransaction}, and that {@code tranProcTs} is set to
     * approximately the current time.
     */
    @Test
    void process_shouldReturnTransactionForValidDailyTransaction() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);
        setupValidPath();

        LocalDateTime beforeProcess = LocalDateTime.now();
        Transaction result = processor.process(dt);
        LocalDateTime afterProcess = LocalDateTime.now();

        assertThat(result).isNotNull();
        assertThat(result.getTranId()).isEqualTo(dt.getDalytranId());
        assertThat(result.getTranTypeCd()).isEqualTo(dt.getDalytranTypeCd());
        assertThat(result.getTranCatCd()).isEqualTo(dt.getDalytranCatCd());
        assertThat(result.getTranSource()).isEqualTo(dt.getDalytranSource());
        assertThat(result.getTranDesc()).isEqualTo(dt.getDalytranDesc());
        assertThat(result.getTranAmt()).isEqualByComparingTo(dt.getDalytranAmt());
        assertThat(result.getTranCardNum()).isEqualTo(dt.getDalytranCardNum());
        assertThat(result.getTranMerchantId()).isEqualTo(dt.getDalytranMerchantId());
        assertThat(result.getTranMerchantName()).isEqualTo(dt.getDalytranMerchantName());
        assertThat(result.getTranMerchantCity()).isEqualTo(dt.getDalytranMerchantCity());
        assertThat(result.getTranMerchantZip()).isEqualTo(dt.getDalytranMerchantZip());
        assertThat(result.getTranOrigTs()).isEqualTo(dt.getDalytranOrigTs());
        // tranProcTs is set to current time during processing
        assertThat(result.getTranProcTs()).isNotNull();
        assertThat(result.getTranProcTs()).isAfterOrEqualTo(beforeProcess);
        assertThat(result.getTranProcTs()).isBeforeOrEqualTo(afterProcess);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TCATBAL Update — Create or Update Pattern (2100-UPDATE-TCATBAL)
    // Maps CBTRN02C 2700-UPDATE-TCATBAL: if exists → ADD; if not → WRITE new
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When an existing TCATBAL record is found for the (acctId, typeCode, catCode)
     * composite key, the processor adds the transaction amount to the existing
     * balance and saves the updated entity.
     */
    @Test
    void process_shouldUpdateExistingTcatbalOnValidTransaction() throws Exception {
        BigDecimal existingBalance = new BigDecimal("500.00");
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, existingBalance)));

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(transactionCategoryBalanceRepository).save(captor.capture());
        TransactionCategoryBalance savedTcatbal = captor.getValue();
        // 500.00 + 100.00 = 600.00
        assertThat(savedTcatbal.getTranCatBal())
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    /**
     * When no TCATBAL record exists for the composite key, the processor
     * creates a new entity with the transaction amount as the initial balance.
     */
    @Test
    void process_shouldCreateNewTcatbalWhenNotExists() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.empty());

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(transactionCategoryBalanceRepository).save(captor.capture());
        TransactionCategoryBalance savedTcatbal = captor.getValue();
        // New record: initial balance equals the transaction amount
        assertThat(savedTcatbal.getTranCatBal())
                .isEqualByComparingTo(TRAN_AMOUNT);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Account Balance Update (2200-UPDATE-ACCOUNT)
    // Maps CBTRN02C: IF DALYTRAN-AMT >= 0 → ADD to CYC-CREDIT
    //               IF DALYTRAN-AMT < 0  → ADD to CYC-DEBIT
    //               Always ADD to ACCT-CURR-BAL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Positive transaction amount: adds to cycCredit and currBal.
     * CycDebit remains unchanged.
     * Initial: currBal=5000, cycCredit=1000, cycDebit=500, amount=+200
     * Result: currBal=5200, cycCredit=1200, cycDebit=500
     */
    @Test
    void process_shouldAddPositiveAmountToCycCredit() throws Exception {
        BigDecimal positiveAmount = new BigDecimal("200.00");
        DailyTransaction dt = createDailyTransaction(CARD_NUM, positiveAmount);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("300.00"))));

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account savedAccount = captor.getValue();

        // currBal = 5000.00 + 200.00 = 5200.00
        assertThat(savedAccount.getAcctCurrBal())
                .isEqualByComparingTo(new BigDecimal("5200.00"));
        // cycCredit = 1000.00 + 200.00 = 1200.00 (positive → credit path)
        assertThat(savedAccount.getAcctCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("1200.00"));
        // cycDebit unchanged
        assertThat(savedAccount.getAcctCurrCycDebit())
                .isEqualByComparingTo(CYC_DEBIT);
    }

    /**
     * Negative transaction amount (refund/reversal): adds the raw negative
     * value to cycDebit and currBal per COBOL ADD semantics.
     * Initial: currBal=5000, cycCredit=1000, cycDebit=500, amount=-150
     * Result: currBal=4850, cycCredit=1000, cycDebit=350 (500 + -150)
     *
     * <p>Note: COBOL {@code ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT} adds the raw
     * signed value, not the absolute value. A negative amount decreases cycDebit.
     */
    @Test
    void process_shouldAddNegativeAmountToCycDebit() throws Exception {
        BigDecimal negativeAmount = new BigDecimal("-150.00");
        DailyTransaction dt = createDailyTransaction(CARD_NUM, negativeAmount);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("300.00"))));

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account savedAccount = captor.getValue();

        // currBal = 5000.00 + (-150.00) = 4850.00
        assertThat(savedAccount.getAcctCurrBal())
                .isEqualByComparingTo(new BigDecimal("4850.00"));
        // cycDebit = 500.00 + (-150.00) = 350.00 (raw negative per COBOL ADD)
        assertThat(savedAccount.getAcctCurrCycDebit())
                .isEqualByComparingTo(new BigDecimal("350.00"));
        // cycCredit unchanged
        assertThat(savedAccount.getAcctCurrCycCredit())
                .isEqualByComparingTo(CYC_CREDIT);
    }

    /**
     * Zero-amount transaction: per COBOL {@code IF DALYTRAN-AMT >= 0}, zero
     * routes to the cycCredit path. Adding zero is effectively a no-op, so all
     * balances remain unchanged. This test verifies the processor handles
     * zero-amount transactions without error and that the account is saved.
     */
    @Test
    void process_shouldTreatZeroAmountAsCycDebitPath() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, BigDecimal.ZERO);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("300.00"))));

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account savedAccount = captor.getValue();

        // currBal = 5000.00 + 0.00 = 5000.00
        assertThat(savedAccount.getAcctCurrBal())
                .isEqualByComparingTo(CURR_BAL);
        // Zero routes to >= 0 (credit) path — cycCredit unchanged by zero add
        assertThat(savedAccount.getAcctCurrCycCredit())
                .isEqualByComparingTo(CYC_CREDIT);
        // cycDebit unchanged
        assertThat(savedAccount.getAcctCurrCycDebit())
                .isEqualByComparingTo(CYC_DEBIT);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Account REWRITE Error — Reject Code 109
    // Maps CBTRN02C 2200-UPDATE-ACCOUNT: REWRITE fails → reject code 109
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When {@code accountRepository.save()} throws an exception (simulating
     * COBOL REWRITE failure), the processor catches the error, rejects the
     * transaction with code 109, and returns null.
     */
    @Test
    void process_shouldRejectWithCode109WhenAccountSaveFails() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("300.00"))));
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new RuntimeException("Simulated REWRITE failure"));

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cascade Short-Circuit Behavior
    // Verifies that validation stages short-circuit on first failure
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When Stage 1 (XREF lookup) fails, Stage 2+ are never reached: the
     * account repository and TCATBAL repository must not be called.
     */
    @Test
    void process_shouldNotCheckCreditLimitWhenXrefNotFound() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.empty());

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
        verify(accountRepository, never()).findById(anyString());
        verify(transactionCategoryBalanceRepository, never()).findById(any());
        verify(transactionCategoryBalanceRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    /**
     * When Stage 2 (account lookup) fails, Stages 3-4 and update operations
     * are never reached: the TCATBAL repository must not be called.
     */
    @Test
    void process_shouldNotCheckExpiryWhenAccountNotFound() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.empty());

        Transaction result = processor.process(dt);

        assertThat(result).isNull();
        verify(transactionCategoryBalanceRepository, never()).findById(any());
        verify(transactionCategoryBalanceRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    /**
     * Full valid path: verifies that all four validation stages are executed
     * and all update operations (TCATBAL + account) are performed.
     */
    @Test
    void process_shouldExerciseAllFourStagesForValidTransaction() throws Exception {
        DailyTransaction dt = createDailyTransaction(CARD_NUM, TRAN_AMOUNT);
        setupValidPath();

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        // Stage 1: XREF lookup
        verify(cardCrossReferenceRepository).findById(CARD_NUM);
        // Stage 2: Account lookup
        verify(accountRepository).findById(ACCT_ID);
        // TCATBAL create-or-update
        verify(transactionCategoryBalanceRepository).findById(any(TransactionCategoryBalanceId.class));
        verify(transactionCategoryBalanceRepository).save(any(TransactionCategoryBalance.class));
        // Account balance update
        verify(accountRepository).save(any(Account.class));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BigDecimal Precision Tests
    // Per AAP §0.8.2: zero floating-point substitution, all comparisons via
    // compareTo(), all amounts constructed from String (never double)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Edge-case precision test: credit limit boundary at the cent level.
     * creditLimit="1000.01", cycCredit="999.99", cycDebit="0.00", amount="0.03"
     * tempBal = 999.99 - 0.00 + 0.03 = 1000.02 > 1000.01 → REJECT
     *
     * <p>This would fail with floating-point arithmetic due to binary
     * representation error. BigDecimal guarantees exact cent-level precision.
     */
    @Test
    void process_shouldMaintainBigDecimalPrecisionForCreditLimitCheck() throws Exception {
        BigDecimal precisionLimit = new BigDecimal("1000.01");
        BigDecimal precisionCredit = new BigDecimal("999.99");
        BigDecimal precisionAmount = new BigDecimal("0.03");
        DailyTransaction dt = createDailyTransaction(CARD_NUM, precisionAmount);

        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, precisionLimit, CURR_BAL,
                precisionCredit, BigDecimal.ZERO, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));

        Transaction result = processor.process(dt);

        // tempBal = 999.99 + 0.03 = 1000.02 > 1000.01 → REJECT
        assertThat(result).isNull();
    }

    /**
     * Verifies that the transaction amount passes through the processor without
     * any floating-point contamination. The notorious {@code 0.1} value would
     * become {@code 0.09999999...} if processed through {@code double} at any
     * stage.
     */
    @Test
    void process_shouldNeverUseFloatingPointForFinancialFields() throws Exception {
        BigDecimal preciseAmount = new BigDecimal("0.10");
        DailyTransaction dt = createDailyTransaction(CARD_NUM, preciseAmount);
        setupValidPath();

        Transaction result = processor.process(dt);

        assertThat(result).isNotNull();
        // compareTo ignores trailing-zero scale differences (e.g. 0.10 vs 0.1)
        // but detects floating-point corruption (0.09999999... ≠ 0.10)
        assertThat(result.getTranAmt())
                .isEqualByComparingTo(new BigDecimal("0.10"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper Methods — Test Fixture Construction
    // All financial values use BigDecimal(String) constructor, never double
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a {@link DailyTransaction} with default timestamp.
     */
    private DailyTransaction createDailyTransaction(String cardNum, BigDecimal amount) {
        return createDailyTransaction(cardNum, amount, DEFAULT_TRAN_TS);
    }

    /**
     * Creates a fully-populated {@link DailyTransaction} test fixture.
     */
    private DailyTransaction createDailyTransaction(String cardNum, BigDecimal amount,
                                                     LocalDateTime timestamp) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId("T0000000001");
        dt.setDalytranCardNum(cardNum);
        dt.setDalytranAmt(amount);
        dt.setDalytranTypeCd(TYPE_CD);
        dt.setDalytranCatCd(CAT_CD);
        dt.setDalytranSource("POS TERM");
        dt.setDalytranDesc("Test transaction");
        dt.setDalytranMerchantId("MERCH001");
        dt.setDalytranMerchantName("Test Merchant");
        dt.setDalytranMerchantCity("Test City");
        dt.setDalytranMerchantZip("12345");
        dt.setDalytranOrigTs(timestamp);
        return dt;
    }

    /**
     * Creates an {@link Account} test fixture with all required financial fields.
     * All amounts use BigDecimal — no float/double.
     */
    private Account createAccount(String acctId, BigDecimal creditLimit, BigDecimal currBal,
                                  BigDecimal cycCredit, BigDecimal cycDebit, LocalDate expiryDate) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCurrBal(currBal);
        account.setAcctCurrCycCredit(cycCredit);
        account.setAcctCurrCycDebit(cycDebit);
        account.setAcctExpDate(expiryDate);
        return account;
    }

    /**
     * Creates a {@link CardCrossReference} linking a card number to an account.
     */
    private CardCrossReference createXref(String cardNum, String acctId) {
        return new CardCrossReference(cardNum, CUST_ID, acctId);
    }

    /**
     * Creates a {@link TransactionCategoryBalance} with the given composite key
     * components and initial balance.
     */
    private TransactionCategoryBalance createTcatbal(String acctId, String typeCode,
                                                      Short catCode, BigDecimal balance) {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(acctId, typeCode, catCode);
        return new TransactionCategoryBalance(id, balance);
    }

    /**
     * Convenience method: sets up all mocks for a fully valid transaction path
     * (all 4 stages pass, TCATBAL exists, account save succeeds). Uses default
     * test constants.
     */
    private void setupValidPath() {
        when(cardCrossReferenceRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(createXref(CARD_NUM, ACCT_ID)));
        Account account = createAccount(ACCT_ID, CREDIT_LIMIT, CURR_BAL,
                CYC_CREDIT, CYC_DEBIT, FUTURE_EXPIRY_DATE);
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account));
        when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(createTcatbal(ACCT_ID, TYPE_CD, CAT_CD, new BigDecimal("500.00"))));
    }
}
