package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.RejectReason;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Behavioural-parity unit tests for {@link PostTransactionProcessor} &mdash; the posting engine of
 * the CardDemo transaction-posting batch job and the Java translation of the per-record body of
 * COBOL {@code CBTRN02C} (source SHA {@code 27d6c6f}).
 *
 * <p>This is a pure JUnit&nbsp;5 + Mockito unit test: it uses {@link MockitoExtension} with mocked
 * repositories and <strong>no Spring context, no database and no containers</strong>, so it runs in
 * milliseconds under Surefire. It locks 100% behavioural parity (AAP&nbsp;G1/G3) for the four
 * translated paragraphs &mdash; {@code 1500-VALIDATE-TRAN}, {@code 2000-POST-TRANSACTION},
 * {@code 2700-UPDATE-TCATBAL} and {@code 2800-UPDATE-ACCOUNT-REC} &mdash; covering every validation
 * branch, the reject-reason codes, decimal fidelity (AAP&nbsp;G2) and, above all, the last-write-wins
 * reject rule where reason {@code 103} overwrites {@code 102}.</p>
 *
 * <h2>What is pinned here</h2>
 * <ul>
 *   <li><strong>Reject branches</strong> &mdash; {@code 100} (invalid card, short-circuit),
 *       {@code 101} (account not found), {@code 102} (overlimit) and {@code 103} (expired), each with
 *       its inclusive boundary, plus the critical {@code 102}+{@code 103}&nbsp;&rarr;&nbsp;{@code 103}
 *       case that proves the two checks are independent {@code if} statements (never {@code else if}).</li>
 *   <li><strong>Valid posting</strong> &mdash; the 1:1 field copy from {@link DailyTransaction} to
 *       {@link Transaction} and the 26-character {@code TRAN-PROC-TS} processing timestamp (made
 *       deterministic by injecting a fixed {@link Clock}).</li>
 *   <li><strong>Side-effects</strong> &mdash; the category-balance create/update ({@code 2700}) and
 *       the sign-based account accumulation ({@code 2800}), each asserted with exact decimal scale,
 *       and the guarantee that <em>no</em> side-effect is applied on any reject.</li>
 *   <li><strong>Fault handling</strong> &mdash; an unparseable origination timestamp is an
 *       ABEND-class fault surfaced as {@link FileProcessingException}; a {@code null} item breaks the
 *       chunk contract and fails fast with {@link NullPointerException}.</li>
 * </ul>
 *
 * <p>Every monetary assertion compares value with {@link BigDecimal#compareTo(BigDecimal)} and checks
 * {@link BigDecimal#scale()} independently, so a scale regression can never hide behind a
 * value-equal comparison. The suite compiles warning-free under {@code -Xlint:all} (Gate&nbsp;2): it
 * uses fully generic mocks and captors with no raw types, unchecked casts or suppressed warnings. No
 * COBOL source is reproduced; the design rationale lives in {@code docs/decision-log.md} and the
 * paragraph mapping in {@code docs/traceability-matrix.md}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostTransactionProcessor — CBTRN02C posting-engine parity")
class PostTransactionProcessorTest {

    // ---------------------------------------------------------------------------------------------
    // Shared fixtures / constants. Values are chosen to make the arithmetic obvious and to match the
    // Gate-1 fixture origination date (2022-06-10), so origDate = DALYTRAN-ORIG-TS(1:10) = 2022-06-10.
    // ---------------------------------------------------------------------------------------------

    /** A valid 16-character card number ({@code DALYTRAN-CARD-NUM PIC X(16)}). */
    private static final String CARD_NUM = "4111111111111111";

    /** The account id the cross-reference resolves to ({@code XREF-ACCT-ID PIC 9(11)}). */
    private static final Long ACCT_ID = 12_345_678_901L;

    /** Transaction type code ({@code DALYTRAN-TYPE-CD PIC X(02)}). */
    private static final String TYPE_CD = "01";

    /** Transaction category code ({@code DALYTRAN-CAT-CD PIC 9(04)}). */
    private static final int CAT_CD = 5;

    /** Merchant id ({@code DALYTRAN-MERCHANT-ID PIC 9(09)}). */
    private static final Long MERCHANT_ID = 987_654_321L;

    /** Origination timestamp; its first 10 characters are the origination date 2022-06-10. */
    private static final String ORIG_TS = "2022-06-10-12.30.00.000000";

    /** Origination date parsed from {@link #ORIG_TS} (used for the expiration boundary cases). */
    private static final LocalDate ORIG_DATE = LocalDate.of(2022, 6, 10);

    /** A far-future expiration date that never trips the {@code 103} expiration check. */
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);

    /**
     * A fixed clock so {@code TRAN-PROC-TS} is deterministic. {@code LocalDateTime.now(clock)}
     * resolves to {@code 2024-01-15T10:20:30.123456}, which the processor formats with
     * {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} to {@link #EXPECTED_PROC_TS}.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-15T10:20:30.123456Z"), ZoneOffset.UTC);

    /** The exact 26-character processing timestamp produced from {@link #FIXED_CLOCK}. */
    private static final String EXPECTED_PROC_TS = "2024-01-15-10.20.30.123456";

    /** The {@code TRAN-PROC-TS} contract shape: {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (26 chars). */
    private static final String PROC_TS_REGEX = "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}";

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * TRANSACT master used solely by the F2 idempotency guard (D-028). Left unstubbed by the
     * business-parity tests: an unstubbed Mockito mock returns {@code false} from
     * {@code existsById(...)}, so the guard falls through and every existing test still exercises
     * the full posting path exactly as before. The dedicated F2 idempotency-guard tests stub it to
     * {@code true} to prove the re-run filter.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /** The class under test, constructed fresh per test with the deterministic clock. */
    private PostTransactionProcessor processor;

    @BeforeEach
    void setUp() {
        // Use the package-private (repos + Clock) constructor so TRAN-PROC-TS is deterministic.
        // No stubbing happens here: MockitoExtension runs with strict stubs, and per-test stubbing
        // keeps every stub necessary (no UnnecessaryStubbingException).
        processor = new PostTransactionProcessor(
                cardXrefRepository, accountRepository, transactionCategoryBalanceRepository,
                transactionRepository, FIXED_CLOCK);
    }

    // ---------------------------------------------------------------------------------------------
    // Builders — construct fixtures from plain strings so each test reads as data, not boilerplate.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a fully populated daily-transaction staging row ({@code CVTRA06Y}).
     *
     * @param cardNum the card number ({@code DALYTRAN-CARD-NUM})
     * @param amount  the signed amount as a decimal string ({@code DALYTRAN-AMT})
     * @param origTs  the 26-char origination timestamp ({@code DALYTRAN-ORIG-TS})
     * @param typeCd  the transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param catCd   the transaction category code ({@code DALYTRAN-CAT-CD})
     * @return a populated {@link DailyTransaction}
     */
    private static DailyTransaction dailyTran(
            String cardNum, String amount, String origTs, String typeCd, int catCd) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId("TRN0000000000001");
        dt.setDalytranCardNum(cardNum);
        dt.setDalytranAmt(new BigDecimal(amount));
        dt.setDalytranOrigTs(origTs);
        dt.setDalytranTypeCd(typeCd);
        dt.setDalytranCatCd(catCd);
        dt.setDalytranSource("POS");
        dt.setDalytranDesc("TEST TRANSACTION");
        dt.setDalytranMerchantId(MERCHANT_ID);
        dt.setDalytranMerchantName("TEST MERCHANT");
        dt.setDalytranMerchantCity("TEST CITY");
        dt.setDalytranMerchantZip("12345");
        return dt;
    }

    /**
     * Builds an account ({@code CVACT01Y}) with the balance fields the posting engine reads and
     * rewrites. The account id is always {@link #ACCT_ID} so it lines up with {@link #xref}.
     *
     * @param creditLimit the credit limit ({@code ACCT-CREDIT-LIMIT}) as a decimal string
     * @param cycCredit   the current-cycle credit total ({@code ACCT-CURR-CYC-CREDIT})
     * @param cycDebit    the current-cycle debit total ({@code ACCT-CURR-CYC-DEBIT})
     * @param currBal     the current balance ({@code ACCT-CURR-BAL})
     * @param expiration  the expiration date ({@code ACCT-EXPIRAION-DATE})
     * @return a populated {@link Account}
     */
    private static Account account(
            String creditLimit, String cycCredit, String cycDebit, String currBal, LocalDate expiration) {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctCreditLimit(new BigDecimal(creditLimit));
        account.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        account.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        account.setAcctCurrBal(new BigDecimal(currBal));
        account.setAcctExpirationDate(expiration);
        return account;
    }

    /**
     * Builds a card cross-reference row ({@code CVACT03Y}) linking a card number to an account id.
     *
     * @param cardNum the card number (primary key, {@code XREF-CARD-NUM})
     * @param acctId  the account id the card resolves to ({@code XREF-ACCT-ID})
     * @return a populated {@link CardXref}
     */
    private static CardXref xref(String cardNum, Long acctId) {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(cardNum);
        xref.setXrefCustId(999_000_111L);
        xref.setXrefAcctId(acctId);
        return xref;
    }

    /** The composite category-balance key the processor builds: (acctId, typeCd, catCd). */
    private static TransactionCategoryBalanceId tcatId(Long acctId, String typeCd, int catCd) {
        return new TransactionCategoryBalanceId(acctId, typeCd, catCd);
    }

    /**
     * Asserts a monetary {@link BigDecimal} equals the expected value <em>and</em> carries scale 2.
     * Value is compared with {@link BigDecimal#compareTo(BigDecimal)} (so {@code 250} and
     * {@code 250.00} are value-equal) while {@link BigDecimal#scale()} is asserted separately, so a
     * scale regression cannot hide behind a value-equal comparison.
     *
     * @param actual   the value under test
     * @param expected the expected value as a decimal string
     */
    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.compareTo(new BigDecimal(expected)))
                .as("value %s should equal %s", actual, expected)
                .isZero();
        assertThat(actual.scale()).as("scale of %s", actual).isEqualTo(2);
    }

    // =============================================================================================
    // 1500-A-LOOKUP-XREF — reason 100 (invalid card number), short-circuit.
    // =============================================================================================

    @Test
    @DisplayName("100 INVALID_CARD_NUMBER: unknown card short-circuits — no account/TCATBAL lookup")
    void reject100_invalidCard_shortCircuits() {
        DailyTransaction dt = dailyTran(CARD_NUM, "50.00", ORIG_TS, TYPE_CD, CAT_CD);
        // 1500-A-LOOKUP-XREF: the card is not in the cross-reference file.
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        PostingResult result = processor.process(dt);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.isPosted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.INVALID_CARD_NUMBER);
        assertThat(result.rejectReason().getCode()).isEqualTo(100);
        assertThat(result.sourceTransaction()).isSameAs(dt);
        assertThat(result.postedTransaction()).isNull();

        // COBOL: when 1500-A sets reason 100, 1500-B never runs — no account and no TCATBAL access.
        verifyNoInteractions(accountRepository, transactionCategoryBalanceRepository);
    }

    // =============================================================================================
    // 1500-B-LOOKUP-ACCT — reason 101 (account not found).
    // =============================================================================================

    @Test
    @DisplayName("101 ACCOUNT_NOT_FOUND: card resolves but account is missing — no TCATBAL, no save")
    void reject101_accountNotFound() {
        DailyTransaction dt = dailyTran(CARD_NUM, "50.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        // 1500-B-LOOKUP-ACCT: INVALID KEY on the account read.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        PostingResult result = processor.process(dt);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.ACCOUNT_NOT_FOUND);
        assertThat(result.rejectReason().getCode()).isEqualTo(101);
        assertThat(result.postedTransaction()).isNull();

        // No posting side-effects: the account balance is never rewritten and TCATBAL is untouched.
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionCategoryBalanceRepository);
    }

    // =============================================================================================
    // 1500-B — reason 102 (overlimit): an INDEPENDENT if, inclusive at the boundary.
    // =============================================================================================

    @Test
    @DisplayName("102 OVERLIMIT: WS-TEMP-BAL exceeds ACCT-CREDIT-LIMIT — rejected, no side-effects")
    void reject102_overlimit() {
        // creditLimit 1000.00; WS-TEMP-BAL = 0 - 0 + 1500.00 = 1500.00 > 1000.00 -> reject 102.
        Account account = account("1000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "1500.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.OVERLIMIT);
        assertThat(result.rejectReason().getCode()).isEqualTo(102);
        assertThat(result.postedTransaction()).isNull();

        // No side-effects on a reject: account balances unchanged, nothing saved, TCATBAL untouched.
        assertMoney(account.getAcctCurrBal(), "0.00");
        assertMoney(account.getAcctCurrCycCredit(), "0.00");
        assertMoney(account.getAcctCurrCycDebit(), "0.00");
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionCategoryBalanceRepository);
    }

    @Test
    @DisplayName("102 boundary: ACCT-CREDIT-LIMIT == WS-TEMP-BAL is inclusive (>=) — posted, not rejected")
    void overlimitBoundary_posted() {
        // creditLimit 1000.00; WS-TEMP-BAL = 0 - 0 + 1000.00 = 1000.00; 1000.00 >= 1000.00 -> OK.
        Account account = account("1000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "1000.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.VALID);
        assertThat(result.postedTransaction()).isNotNull();
    }

    // =============================================================================================
    // 1500-B — reason 103 (expired): a SECOND, INDEPENDENT if, inclusive at the boundary.
    // =============================================================================================

    @Test
    @DisplayName("103 ACCOUNT_EXPIRED: expiration before origination date — rejected, no side-effects")
    void reject103_expired() {
        // Within limit, but ACCT-EXPIRAION-DATE 2020-01-01 is before origDate 2022-06-10 -> reject 103.
        Account account = account("5000.00", "0.00", "0.00", "0.00", LocalDate.of(2020, 1, 1));
        DailyTransaction dt = dailyTran(CARD_NUM, "100.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.ACCOUNT_EXPIRED);
        assertThat(result.rejectReason().getCode()).isEqualTo(103);
        assertThat(result.postedTransaction()).isNull();

        assertMoney(account.getAcctCurrBal(), "0.00");
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionCategoryBalanceRepository);
    }

    @Test
    @DisplayName("103 boundary: ACCT-EXPIRAION-DATE == origination date is inclusive (>=) — posted")
    void expirationBoundary_posted() {
        // Expiration exactly equals origDate (2022-06-10): expiration.isBefore(orig) is false -> OK.
        Account account = account("5000.00", "0.00", "0.00", "0.00", ORIG_DATE);
        DailyTransaction dt = dailyTran(CARD_NUM, "100.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.VALID);
        assertThat(result.postedTransaction()).isNotNull();
    }

    // =============================================================================================
    // ★★ THE CRITICAL PARITY TEST: both overlimit AND expired => reason 103 (last-write-wins).
    // =============================================================================================

    @Test
    @DisplayName("★ 103 overwrites 102 when BOTH fail (sequential independent IFs, never else-if)")
    void bothOverlimitAndExpired_reason103_lastWriteWins() {
        // creditLimit 1000.00 with amount 1500.00 -> overlimit (would be 102); AND expiration
        // 2020-01-01 before origDate 2022-06-10 -> expired (103). CBTRN02C evaluates overlimit then
        // expiration as two INDEPENDENT if-blocks, so the expiration assignment runs last and the
        // final WS-VALIDATION-FAIL-REASON is 103. A naive else-if port would wrongly emit 102.
        Account account = account("1000.00", "0.00", "0.00", "0.00", LocalDate.of(2020, 1, 1));
        DailyTransaction dt = dailyTran(CARD_NUM, "1500.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isRejected()).isTrue();
        // The whole point: the final reason is ACCOUNT_EXPIRED (103), NOT OVERLIMIT (102).
        assertThat(result.rejectReason())
                .as("103 (expired) must overwrite 102 (overlimit) — last-write-wins")
                .isEqualTo(RejectReason.ACCOUNT_EXPIRED);
        assertThat(result.rejectReason().getCode()).isEqualTo(103);
        assertThat(result.rejectReason()).isNotEqualTo(RejectReason.OVERLIMIT);
        assertThat(result.postedTransaction()).isNull();

        // Still a reject, so still no side-effects.
        assertMoney(account.getAcctCurrBal(), "0.00");
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionCategoryBalanceRepository);
    }

    // =============================================================================================
    // 2000-POST-TRANSACTION — valid posting: 1:1 field copy + 26-char TRAN-PROC-TS.
    // =============================================================================================

    @Test
    @DisplayName("Valid posting: 1:1 field copy DALYTRAN->TRAN and a deterministic 26-char TRAN-PROC-TS")
    void validPosting_fieldMapping_and_procTs() {
        // A distinct, fully-populated daily row so every copied field is unambiguous.
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId("TRN0000000000042");
        dt.setDalytranTypeCd("07");
        dt.setDalytranCatCd(1234);
        dt.setDalytranSource("ONLINE");
        dt.setDalytranDesc("GROCERY STORE PURCHASE");
        dt.setDalytranAmt(new BigDecimal("123.45"));
        dt.setDalytranMerchantId(555_444_333L);
        dt.setDalytranMerchantName("ACME MARKETS");
        dt.setDalytranMerchantCity("SEATTLE");
        dt.setDalytranMerchantZip("98101");
        dt.setDalytranCardNum(CARD_NUM);
        dt.setDalytranOrigTs(ORIG_TS);

        Account account = account("5000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        // TCATBAL findById is left at the Mockito default (Optional.empty) — its create path runs but
        // is irrelevant to this test, which focuses on the posted-transaction field mapping.

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();
        Transaction tx = result.postedTransaction();
        assertThat(tx).isNotNull();

        // 1:1 field copy (2000-POST-TRANSACTION MOVEs CVTRA06Y -> CVTRA05Y).
        assertThat(tx.getTranId()).isEqualTo("TRN0000000000042");
        assertThat(tx.getTranTypeCd()).isEqualTo("07");
        assertThat(tx.getTranCatCd()).isEqualTo(1234);
        assertThat(tx.getTranSource()).isEqualTo("ONLINE");
        assertThat(tx.getTranDesc()).isEqualTo("GROCERY STORE PURCHASE");
        assertThat(tx.getTranMerchantId()).isEqualTo(555_444_333L);
        assertThat(tx.getTranMerchantName()).isEqualTo("ACME MARKETS");
        assertThat(tx.getTranMerchantCity()).isEqualTo("SEATTLE");
        assertThat(tx.getTranMerchantZip()).isEqualTo("98101");
        assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);
        assertThat(tx.getTranOrigTs()).isEqualTo(ORIG_TS);
        // Amount preserved at scale 2 (compareTo for value, scale() for scale).
        assertMoney(tx.getTranAmt(), "123.45");

        // TRAN-PROC-TS: set by the processor (Z-GET-DB2-FORMAT-TIMESTAMP) to a 26-char DB2 timestamp.
        assertThat(tx.getTranProcTs()).isNotNull();
        assertThat(tx.getTranProcTs()).hasSize(26);
        assertThat(tx.getTranProcTs()).matches(PROC_TS_REGEX);
        // Deterministic under the injected fixed clock.
        assertThat(tx.getTranProcTs()).isEqualTo(EXPECTED_PROC_TS);
    }

    // =============================================================================================
    // 2700-UPDATE-TCATBAL — create when absent (INVALID KEY / status '23') vs update when present.
    // =============================================================================================

    @Test
    @DisplayName("2700 create: absent TCATBAL row is created with balance == amount, key (acctId,type,cat)")
    void tcatbal_create_whenAbsent() {
        Account account = account("5000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "75.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        // 2700: INVALID KEY -> WS-CREATE-TRANCAT-REC = 'Y' -> 2700-A-CREATE-TCATBAL-REC.
        when(transactionCategoryBalanceRepository.findById(any())).thenReturn(Optional.empty());

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();

        // The composite key must be built from (XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD).
        ArgumentCaptor<TransactionCategoryBalanceId> keyCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
        verify(transactionCategoryBalanceRepository).findById(keyCaptor.capture());
        TransactionCategoryBalanceId key = keyCaptor.getValue();
        assertThat(key.getTrancatAcctId()).isEqualTo(ACCT_ID);
        assertThat(key.getTrancatTypeCd()).isEqualTo(TYPE_CD);
        assertThat(key.getTrancatCd()).isEqualTo(CAT_CD);
        assertThat(key).isEqualTo(tcatId(ACCT_ID, TYPE_CD, CAT_CD));

        // A brand-new (transient) balance MUST be persisted; its balance equals the amount at scale 2.
        ArgumentCaptor<TransactionCategoryBalance> balCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(transactionCategoryBalanceRepository).save(balCaptor.capture());
        TransactionCategoryBalance saved = balCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(tcatId(ACCT_ID, TYPE_CD, CAT_CD));
        assertMoney(saved.getTranCatBal(), "75.00");
    }

    @Test
    @DisplayName("2700 update: present TCATBAL row accumulates amount (200.00 + 50.00 = 250.00), same row")
    void tcatbal_update_whenPresent() {
        Account account = account("5000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "50.00", ORIG_TS, TYPE_CD, CAT_CD);

        TransactionCategoryBalance existing = new TransactionCategoryBalance();
        existing.setId(tcatId(ACCT_ID, TYPE_CD, CAT_CD));
        existing.setTranCatBal(new BigDecimal("200.00"));

        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        // 2700: row present -> 2700-B-UPDATE-TCATBAL-REC (ADD DALYTRAN-AMT TO TRAN-CAT-BAL).
        when(transactionCategoryBalanceRepository.findById(tcatId(ACCT_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(existing));

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();

        ArgumentCaptor<TransactionCategoryBalance> balCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(transactionCategoryBalanceRepository).save(balCaptor.capture());
        TransactionCategoryBalance saved = balCaptor.getValue();
        // The existing managed row is mutated in place (not replaced) and re-saved.
        assertThat(saved).isSameAs(existing);
        assertMoney(saved.getTranCatBal(), "250.00");
    }

    // =============================================================================================
    // 2800-UPDATE-ACCOUNT-REC — stateful balance accumulation, split by the sign of DALYTRAN-AMT.
    // =============================================================================================

    @Test
    @DisplayName("2800 positive: ACCT-CURR-BAL and ACCT-CURR-CYC-CREDIT accumulate; debit unchanged")
    void account_accumulation_positiveAmount() {
        // currBal 100.00, cycCredit 0.00, cycDebit 0.00; amount +500.00.
        Account account = account("5000.00", "0.00", "0.00", "100.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "500.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();
        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL: 100.00 + 500.00 = 600.00.
        assertMoney(account.getAcctCurrBal(), "600.00");
        // IF DALYTRAN-AMT >= 0 -> ADD TO ACCT-CURR-CYC-CREDIT: 0.00 + 500.00 = 500.00.
        assertMoney(account.getAcctCurrCycCredit(), "500.00");
        // Debit bucket untouched for a positive amount.
        assertMoney(account.getAcctCurrCycDebit(), "0.00");
        // Read-then-rewrite: the managed account is persisted (2800 REWRITE FD-ACCTFILE-REC).
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("2800 negative: ACCT-CURR-CYC-DEBIT gets the SIGNED amount (COBOL ADD), credit unchanged")
    void account_accumulation_negativeAmount() {
        // currBal 100.00, cycCredit 10.00, cycDebit 5.00; amount -75.50.
        Account account = account("5000.00", "10.00", "5.00", "100.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "-75.50", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();
        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL: 100.00 + (-75.50) = 24.50.
        assertMoney(account.getAcctCurrBal(), "24.50");
        // ELSE ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT: COBOL adds the SIGNED amount, so
        // 5.00 + (-75.50) = -70.50 (NOT +80.50). This mirrors CBTRN02C exactly.
        assertMoney(account.getAcctCurrCycDebit(), "-70.50");
        // Credit bucket untouched for a negative amount.
        assertMoney(account.getAcctCurrCycCredit(), "10.00");
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("Decimal fidelity: sub-cent inputs normalise to scale 2 HALF_UP (10.005 -> 10.01)")
    void account_accumulation_roundsHalfUpToScale2() {
        // A sub-cent amount (10.005) exercises the scale-2 HALF_UP normalisation applied by the
        // processor after each arithmetic step (COBOL PIC S9(n)V99 truncation/rounding).
        Account account = account("5000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        DailyTransaction dt = dailyTran(CARD_NUM, "10.005", ORIG_TS, TYPE_CD, CAT_CD);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        assertThat(result.isPosted()).isTrue();
        // 0.00 + 10.005 -> 10.01 (HALF_UP to scale 2), for both the balance and the credit bucket.
        assertMoney(account.getAcctCurrBal(), "10.01");
        assertMoney(account.getAcctCurrCycCredit(), "10.01");
        // The posted amount is likewise normalised to scale 2.
        assertMoney(result.postedTransaction().getTranAmt(), "10.01");
    }

    // =============================================================================================
    // Fault handling — ABEND-class data fault and the broken chunk contract.
    // =============================================================================================

    @Test
    @DisplayName("ABEND: an unparseable DALYTRAN-ORIG-TS raises FileProcessingException (9999-ABEND)")
    void abend_unparseableOrigTs_throwsFileProcessingException() {
        Account account = account("5000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        // A syntactically wrong 10-char date prefix cannot be parsed to a LocalDate.
        DailyTransaction garbage = dailyTran(CARD_NUM, "100.00", "20XX-01-01-00.00.00.000000", TYPE_CD, CAT_CD);
        assertThatExceptionOfType(FileProcessingException.class)
                .isThrownBy(() -> processor.process(garbage));

        // A too-short timestamp (< 10 chars) is likewise an ABEND-class fault, not a business reject.
        DailyTransaction tooShort = dailyTran(CARD_NUM, "100.00", "2022", TYPE_CD, CAT_CD);
        assertThatExceptionOfType(FileProcessingException.class)
                .isThrownBy(() -> processor.process(tooShort));
    }

    @Test
    @DisplayName("Chunk contract: a null item fails fast with NullPointerException (never swallowed)")
    void nullInput_throwsNullPointerException() {
        // The chunk contract guarantees a non-null item (end-of-input is the reader returning null).
        assertThatNullPointerException().isThrownBy(() -> processor.process(null));
        verifyNoInteractions(cardXrefRepository, accountRepository, transactionCategoryBalanceRepository);
    }

    // =============================================================================================
    // F2 idempotency guard (D-028) — a daily row already in the TRANSACT master is filtered so a
    // POSTTRAN re-run neither double-posts balances nor re-rejects; a not-yet-posted row is unchanged.
    // =============================================================================================

    @Test
    @DisplayName("F2 idempotency: an already-posted DALYTRAN-ID returns null and skips ALL posting logic")
    void alreadyPosted_returnsNull_andSkipsAllPostingLogic() {
        // dailyTran(...) assigns DALYTRAN-ID = "TRN0000000000001"; the posted Transaction's PK is the
        // same id, so the TRANSACT master already carrying that id means "posted by a prior run".
        DailyTransaction dt = dailyTran(CARD_NUM, "50.00", ORIG_TS, TYPE_CD, CAT_CD);
        when(transactionRepository.existsById("TRN0000000000001")).thenReturn(true);

        PostingResult result = processor.process(dt);

        // Returning null makes Spring Batch FILTER the item: not posted, not rejected, not counted.
        assertThat(result).isNull();

        // The guard short-circuits before 1500-VALIDATE-TRAN, so none of the posting collaborators
        // are touched — no lookups, no balance mutation, no writes (prevents the F2 double-post).
        verifyNoInteractions(cardXrefRepository, accountRepository, transactionCategoryBalanceRepository);
    }

    @Test
    @DisplayName("F2 idempotency: a not-yet-posted DALYTRAN-ID falls through and posts exactly as before")
    void notYetPosted_processesNormally() {
        DailyTransaction dt = dailyTran(CARD_NUM, "50.00", ORIG_TS, TYPE_CD, CAT_CD);
        // Explicit first-run guard state: the id is absent from the TRANSACT master.
        when(transactionRepository.existsById("TRN0000000000001")).thenReturn(false);
        Account account = account("5000.00", "0.00", "0.00", "0.00", FAR_FUTURE);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        PostingResult result = processor.process(dt);

        // First-run behaviour is unchanged: the record posts (blind-ADD parity preserved).
        assertThat(result).isNotNull();
        assertThat(result.isPosted()).isTrue();
        assertThat(result.postedTransaction()).isNotNull();
        assertThat(result.postedTransaction().getTranId()).isEqualTo("TRN0000000000001");
        // The account balance accumulates the amount, exactly as it did before the guard existed.
        assertMoney(account.getAcctCurrBal(), "50.00");
        assertMoney(account.getAcctCurrCycCredit(), "50.00");
    }
}
