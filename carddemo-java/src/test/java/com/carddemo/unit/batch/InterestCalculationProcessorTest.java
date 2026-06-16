package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.InterestCalculationProcessor;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InterestCalculationProcessor}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the processor
 * re-platforms the per-record legs of the mainframe interest-calculation program
 * {@code app/cbl/CBACT04C.cbl} driven by {@code app/jcl/INTCALC.jcl} ({@code PARM='2022071800'}),
 * reading the disclosure-group rate from the layout in {@code app/cpy/CVTRA02Y.cpy}. The tests pin
 * the four binding parities of that program:</p>
 * <ul>
 *   <li>{@code 1300-COMPUTE-INTEREST}: {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE)
 *       / 1200} reproduced with multiply-then-divide, scale 2, {@link java.math.RoundingMode#HALF_EVEN}
 *       (AAP section 0.8.2). The {@code 0.125 -> 0.12} and {@code 0.375 -> 0.38} cases discriminate
 *       HALF_EVEN from HALF_UP.</li>
 *   <li>The main-loop guard {@code IF DIS-INT-RATE NOT = 0}: a zero rate emits no transaction
 *       ({@code process} returns {@code null}).</li>
 *   <li>{@code 1200-A-GET-DEFAULT-INT-RATE}: a primary disclosure-group miss falls back to the
 *       reserved {@code "DEFAULT"} group; an absent {@code "DEFAULT"} group is fatal (AAP
 *       section 0.8.5).</li>
 *   <li>{@code 1300-B-WRITE-TX}: {@code TRAN-ID = parmDate(10) + ADD-1 WS-TRANID-SUFFIX(6)} and the
 *       fixed stamps ({@code '01'} / {@code '05'} / {@code 'System'} / {@code 'Int. for a/c '}).</li>
 * </ul>
 *
 * <p>The collaborators are mocked with Mockito; the metric registry is a real
 * {@link SimpleMeterRegistry} and the clock is a fixed {@link Clock} so the emitted DB2 timestamp is
 * deterministic. No Spring context, no Testcontainers. Monetary fields are asserted by value with
 * {@code isEqualByComparingTo} and never with scale-sensitive {@code BigDecimal.equals} (AAP
 * section 0.8.2).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationProcessor - CBACT04C interest formula, DEFAULT fallback, zero-rate, TRAN-ID")
class InterestCalculationProcessorTest {

    /** Ten-character run date forming the transaction-id prefix (INTCALC.jcl PARM='2022071800'). */
    private static final String PARM_DATE = "2022071800";

    /** Account group id used for the primary disclosure-group lookup (ACCT-GROUP-ID, length 10). */
    private static final String ACCT_GROUP_ID = "A000000000";

    /** Reserved disclosure-group id used as the interest-rate fallback (COBOL 1200-A). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Transaction type code component of the keys and the emitted transaction (MOVE '01'). */
    private static final String TYPE_CD = "01";

    /** Transaction category code component of the keys and the emitted transaction (MOVE '05'). */
    private static final int CAT_CD = 5;

    /** Account id under test. */
    private static final long ACCT_ID = 11L;

    /** Sixteen-character card number returned by the cross-reference lookup (XREF-CARD-NUM). */
    private static final String CARD_NUM = "1234567890123456";

    /** DB2 timestamp derived from the fixed clock (yyyy-MM-dd-HH.mm.ss.SSSSSS, 26 characters). */
    private static final String EXPECTED_TIMESTAMP = "2022-07-18-10.15.30.123456";

    /** Pattern of the 26-character DB2 timestamp stamped on the interest transaction. */
    private static final String DB2_TS_REGEX = "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}";

    /** Processed-records counter name; mirrors {@code MetricsConfig.BATCH_RECORDS_PROCESSED}. */
    private static final String METRIC_PROCESSED = "carddemo.batch.records.processed";

    /** Rejected-records counter name; mirrors {@code MetricsConfig.BATCH_RECORDS_REJECTED}. */
    private static final String METRIC_REJECTED = "carddemo.batch.records.rejected";

    /** Tag key classifying rejected records; mirrors {@code MetricsConfig.TAG_REASON}. */
    private static final String REJECT_REASON_TAG = "reason";

    /** Reject reason recorded when a balance is skipped due to a zero interest rate. */
    private static final String ZERO_RATE_REASON = "zero-interest-rate";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private SimpleMeterRegistry registry;
    private InterestCalculationProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Clock fixedClock = Clock.fixed(Instant.parse("2022-07-18T10:15:30.123456Z"), ZoneOffset.UTC);
        processor = new InterestCalculationProcessor(
                accountRepository,
                disclosureGroupRepository,
                cardCrossReferenceRepository,
                registry,
                PARM_DATE,
                fixedClock);
    }

    @Test
    @DisplayName("process(): (1000.00 x 12.00)/1200 = 10.00 and stamps the 1300-B-WRITE-TX fields")
    void computesInterest_primaryCase_10_00() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCT_GROUP_ID, TYPE_CD, CAT_CD, "12.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));

        Transaction t = processor.process(balance);

        assertThat(t).isNotNull();
        // (1000.00 x 12.00) / 1200 = 10.00 exactly; compared by value, never BigDecimal.equals.
        assertThat(t.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(t.getTranId()).isEqualTo("2022071800000001").hasSize(16);
        assertThat(t.getTranTypeCd()).isEqualTo("01");
        assertThat(t.getTranCatCd()).isEqualTo(5);
        assertThat(t.getTranSource()).isEqualTo("System");
        assertThat(t.getTranDesc()).startsWith("Int. for a/c ");
        assertThat(t.getTranMerchantId()).isEqualTo(0L);
        assertThat(t.getTranCardNum()).isEqualTo(CARD_NUM);
        // 26-character DB2 timestamp from the injected fixed clock; origin and processing are identical.
        assertThat(t.getTranProcTs()).matches(DB2_TS_REGEX).isEqualTo(EXPECTED_TIMESTAMP);
        assertThat(t.getTranOrigTs()).isEqualTo(t.getTranProcTs());
        assertThat(registry.get(METRIC_PROCESSED).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("process(): 0.125 rounds HALF_EVEN down to 0.12 (HALF_UP would yield 0.13)")
    void halfEvenRounding_roundsToEvenDown_0_12() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "12.50");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCT_GROUP_ID, TYPE_CD, CAT_CD, "12.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));

        Transaction t = processor.process(balance);

        // (12.50 x 12.00)/1200 = 0.125 -> HALF_EVEN keeps the even preceding digit 2 -> 0.12
        // (HALF_UP would instead yield 0.13, so this case proves banker's rounding).
        assertThat(t.getTranAmt()).isEqualByComparingTo(new BigDecimal("0.12"));
    }

    @Test
    @DisplayName("process(): 0.375 rounds HALF_EVEN up to 0.38 (odd preceding digit 7 -> even 8)")
    void halfEvenRounding_roundsToEvenUp_0_38() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "37.50");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCT_GROUP_ID, TYPE_CD, CAT_CD, "12.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));

        Transaction t = processor.process(balance);

        // (37.50 x 12.00)/1200 = 0.375 -> HALF_EVEN rounds the odd preceding digit 7 up to 0.38.
        assertThat(t.getTranAmt()).isEqualByComparingTo(new BigDecimal("0.38"));
    }

    @Test
    @DisplayName("process(): a zero interest rate returns null (no transaction) and skips the card lookup")
    void zeroRate_returnsNull_noTransaction() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCT_GROUP_ID, TYPE_CD, CAT_CD, "0.00")));

        Transaction t = processor.process(balance);

        // COBOL IF DIS-INT-RATE NOT = 0 guard: a zero rate emits no transaction.
        assertThat(t).isNull();
        // The guard returns before 1300-B-WRITE-TX, so the card cross-reference is never read.
        verifyNoInteractions(cardCrossReferenceRepository);
        assertThat(registry.get(METRIC_REJECTED).tags(REJECT_REASON_TAG, ZERO_RATE_REASON).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("process(): a primary disclosure-group miss falls back to DEFAULT (rate 6.00 -> 5.00)")
    void defaultGroupFallback_onPrimaryMiss() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD, "6.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));

        Transaction t = processor.process(balance);

        // (1000.00 x 6.00)/1200 = 5.00 using the DEFAULT-group rate.
        assertThat(t).isNotNull();
        assertThat(t.getTranAmt()).isEqualByComparingTo(new BigDecimal("5.00"));
        // Proves the 1200-A DEFAULT path executed (relies on DisclosureGroupId.equals).
        verify(disclosureGroupRepository).findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD));
    }

    @Test
    @DisplayName("process(): missing primary AND DEFAULT disclosure groups throw RecordNotFoundException")
    void bothPrimaryAndDefaultMissing_throwsRecordNotFound() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(balance))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("process(): a missing account throws RecordNotFoundException (1100-GET-ACCT-DATA)")
    void missingAccount_throwsRecordNotFound() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(balance))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("process(): the transaction-id suffix increments per emitted transaction (000001, 000002)")
    void tranIdSuffix_incrementsPerEmittedTransaction() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCT_GROUP_ID, TYPE_CD, CAT_CD, "12.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));

        Transaction first = processor.process(balance);
        Transaction second = processor.process(balance);

        // WS-TRANID-SUFFIX is incremented (ADD 1) before the id is built, so the first id ends 000001.
        assertThat(first.getTranId()).isEqualTo("2022071800000001").hasSize(16);
        assertThat(second.getTranId()).isEqualTo("2022071800000002").hasSize(16);
        assertThat(first.getTranId()).startsWith(PARM_DATE);
        assertThat(second.getTranId()).startsWith(PARM_DATE);
    }

    @Test
    @DisplayName("process(): the processor only computes/builds and never persists (the writer persists)")
    void processor_neverPersists() {
        TransactionCategoryBalance balance = tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00");
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCT_GROUP_ID, TYPE_CD, CAT_CD, "12.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));

        processor.process(balance);

        verify(accountRepository, never()).save(any(Account.class));
        verify(disclosureGroupRepository, never()).save(any(DisclosureGroup.class));
        verify(cardCrossReferenceRepository, never()).save(any(CardCrossReference.class));
    }

    /**
     * Builds a transaction-category balance keyed by the supplied components (CVTRA01Y layout).
     *
     * @param acctId the account id component of the composite key
     * @param typeCd the transaction type code component of the composite key
     * @param catCd  the transaction category code component of the composite key
     * @param bal    the category balance (TRAN-CAT-BAL) as a decimal string
     * @return the populated balance instance
     */
    private static TransactionCategoryBalance tcatbal(long acctId, String typeCd, int catCd, String bal) {
        TransactionCategoryBalance balance = new TransactionCategoryBalance();
        balance.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
        balance.setTranCatBal(new BigDecimal(bal));
        return balance;
    }

    /**
     * Builds an account carrying the group id that drives the disclosure-group lookup.
     *
     * @param acctId  the account id (ACCT-ID)
     * @param groupId the account group id (ACCT-GROUP-ID)
     * @return the populated account instance
     */
    private static Account account(long acctId, String groupId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctGroupId(groupId);
        return account;
    }

    /**
     * Builds a disclosure group (CVTRA02Y layout) carrying the interest rate for the supplied key.
     *
     * @param groupId the account group id component of the composite key
     * @param typeCd  the transaction type code component of the composite key
     * @param catCd   the transaction category code component of the composite key
     * @param rate    the disclosure interest rate (DIS-INT-RATE) as a decimal string
     * @return the populated disclosure-group instance
     */
    private static DisclosureGroup disclosureGroup(String groupId, String typeCd, int catCd, String rate) {
        DisclosureGroup group = new DisclosureGroup();
        group.setId(new DisclosureGroupId(groupId, typeCd, catCd));
        group.setDisIntRate(new BigDecimal(rate));
        return group;
    }

    /**
     * Builds a card cross-reference linking a card number to an account (XREF layout).
     *
     * @param cardNum the cross-referenced card number (XREF-CARD-NUM)
     * @param acctId  the account id the card is linked to (XREF-ACCT-ID)
     * @return the populated cross-reference instance
     */
    private static CardCrossReference xref(String cardNum, long acctId) {
        CardCrossReference crossReference = new CardCrossReference();
        crossReference.setXrefCardNum(cardNum);
        crossReference.setXrefAcctId(acctId);
        return crossReference;
    }
}
