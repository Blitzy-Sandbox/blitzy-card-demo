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
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.service.shared.FileStatusMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InterestCalculationProcessor}, the interest-calculation stage
 * {@link org.springframework.batch.item.ItemProcessor} that re-platforms the per-balance logic of
 * {@code app/cbl/CBACT04C.cbl} (paragraphs {@code 1200-GET-INTEREST-RATE},
 * {@code 1200-A-GET-DEFAULT-INT-RATE}, {@code 1300-COMPUTE-INTEREST}, {@code 1300-B-WRITE-TX}),
 * driven by {@code app/jcl/INTCALC.jcl} (which passes {@code PARM='2022071800'}); source commit
 * {@code 27d6c6f}, COBOL referenced only and not copied.
 *
 * <p>The suite verifies the binding contracts of that stage:</p>
 * <ul>
 *   <li><strong>Interest-formula fidelity</strong> &mdash; {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 *       computed with the literal divisor {@code 1200}, scale&nbsp;2 and
 *       {@link java.math.RoundingMode#HALF_EVEN}; the {@code 0.125 -> 0.12} and {@code 0.375 -> 0.38}
 *       cases prove {@code HALF_EVEN} (a {@code HALF_UP} implementation would yield {@code 0.13}).</li>
 *   <li><strong>Zero-rate filtering</strong> &mdash; {@code DIS-INT-RATE = 0} returns {@code null}, so
 *       Spring Batch filters the item and writes no transaction (COBOL {@code IF DIS-INT-RATE NOT = 0}).</li>
 *   <li><strong>DEFAULT disclosure-group fallback</strong> &mdash; a primary-group miss (COBOL FILE
 *       STATUS {@code '23'}) re-reads the reserved {@code "DEFAULT"} group; a miss on both is fatal
 *       ({@link RecordNotFoundException}).</li>
 *   <li><strong>TRAN-ID construction</strong> &mdash; the 16-character id is the 10-character
 *       {@code PARM-DATE} concatenated with a six-digit, zero-padded running suffix that increments
 *       per emitted transaction ({@code WS-TRANID-SUFFIX}).</li>
 *   <li><strong>Build-only semantics</strong> &mdash; the processor reads and builds but never
 *       persists; persistence is the batch writer's responsibility.</li>
 * </ul>
 *
 * <p>Pure-JVM test: the three repository collaborators are Mockito mocks, while the
 * {@link FileStatusMapper} (a stateless component) and {@link SimpleMeterRegistry} are real
 * instances and the {@link Clock} is fixed for deterministic timestamps. No Spring context,
 * Testcontainers, or AWS dependency is involved. Monetary assertions use
 * {@code isEqualByComparingTo} so that {@link BigDecimal} scale never affects equality.</p>
 */
@ExtendWith(MockitoExtension.class)
class InterestCalculationProcessorTest {

    /** Ten-character processing date supplied by {@code INTCALC.jcl} ({@code PARM='2022071800'}). */
    private static final String PARM_DATE = "2022071800";

    /** Account's own disclosure-group id (COBOL {@code ACCT-GROUP-ID}, {@code PIC X(10)}). */
    private static final String ACCOUNT_GROUP_ID = "A000000000";

    /** Reserved fallback disclosure-group id (COBOL literal {@code 'DEFAULT'}). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Transaction type code component of the disclosure-group key (COBOL {@code TRAN-TYPE-CD}). */
    private static final String TYPE_CD = "01";

    /** Transaction category code component of the disclosure-group key (COBOL {@code TRAN-CAT-CD}). */
    private static final int CAT_CD = 5;

    /** Owning account id used across the scenarios (COBOL {@code TRANCAT-ACCT-ID}). */
    private static final long ACCT_ID = 11L;

    /** Card number resolved from the card cross-reference (COBOL {@code XREF-CARD-NUM}, 16 chars). */
    private static final String CARD_NUM = "1234567890123456";

    /** Fixed instant backing the deterministic clock; drives the asserted DB2 timestamp. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T10:15:30.123456Z");

    /** DB2 timestamp ({@code yyyy-MM-dd-HH.mm.ss.SSSSSS}) derived from {@link #FIXED_INSTANT} at UTC. */
    private static final String EXPECTED_TIMESTAMP = "2022-07-18-10.15.30.123456";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private SimpleMeterRegistry registry;
    private FileStatusMapper fileStatusMapper;
    private InterestCalculationProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // FileStatusMapper is a stateless component: a real instance maps FILE STATUS '23' to a real
        // RecordNotFoundException, which the both-missing path relies on (a mock would return null).
        fileStatusMapper = new FileStatusMapper();
        final Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        processor = new InterestCalculationProcessor(
                accountRepository,
                disclosureGroupRepository,
                cardCrossReferenceRepository,
                fileStatusMapper,
                registry,
                PARM_DATE,
                fixedClock);
    }

    /**
     * Builds a transaction-category balance (COBOL {@code TRAN-CAT-BAL-RECORD}, copybook CVTRA01Y).
     *
     * @param acctId the account id key component
     * @param typeCd the transaction type code key component
     * @param catCd  the transaction category code key component
     * @param bal    the balance, parsed exactly into a {@link BigDecimal}
     * @return a populated {@link TransactionCategoryBalance}
     */
    private TransactionCategoryBalance tcatbal(final long acctId, final String typeCd, final int catCd,
            final String bal) {
        final TransactionCategoryBalance balance = new TransactionCategoryBalance();
        balance.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
        balance.setTranCatBal(new BigDecimal(bal));
        return balance;
    }

    /**
     * Builds an account carrying only the fields the processor reads (COBOL {@code ACCT-GROUP-ID}).
     *
     * @param acctId  the account id
     * @param groupId the account's disclosure-group id
     * @return a populated {@link Account}
     */
    private Account account(final long acctId, final String groupId) {
        final Account acct = new Account();
        acct.setAcctId(acctId);
        acct.setAcctGroupId(groupId);
        return acct;
    }

    /**
     * Builds a disclosure group (COBOL {@code DIS-GROUP-RECORD}, copybook CVTRA02Y).
     *
     * @param groupId the account-group id key component
     * @param typeCd  the transaction type code key component
     * @param catCd   the transaction category code key component
     * @param rate    the interest rate, parsed exactly into a {@link BigDecimal}
     * @return a populated {@link DisclosureGroup}
     */
    private DisclosureGroup disclosureGroup(final String groupId, final String typeCd, final int catCd,
            final String rate) {
        final DisclosureGroup group = new DisclosureGroup();
        group.setId(new DisclosureGroupId(groupId, typeCd, catCd));
        group.setDisIntRate(new BigDecimal(rate));
        return group;
    }

    /**
     * Builds a card cross-reference (COBOL {@code CARD-XREF-RECORD}, copybook CVACT03Y).
     *
     * @param cardNum the card number
     * @param acctId  the linked account id
     * @return a populated {@link CardCrossReference}
     */
    private CardCrossReference xref(final String cardNum, final long acctId) {
        final CardCrossReference reference = new CardCrossReference();
        reference.setXrefCardNum(cardNum);
        reference.setXrefAcctId(acctId);
        return reference;
    }

    /**
     * Stubs the account and card cross-reference reads for a balance owned by {@link #ACCT_ID} whose
     * group resolves to {@link #ACCOUNT_GROUP_ID}.
     */
    private void stubAccountAndCard() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCOUNT_GROUP_ID)));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref(CARD_NUM, ACCT_ID)));
    }

    /**
     * Stubs the primary disclosure-group read to return a group with the supplied rate.
     *
     * @param rate the interest rate to expose on the primary group
     */
    private void stubPrimaryRate(final String rate) {
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCOUNT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(ACCOUNT_GROUP_ID, TYPE_CD, CAT_CD, rate)));
    }

    @Test
    void computesInterest_primaryCase_10_00() {
        stubAccountAndCard();
        stubPrimaryRate("12.00");

        final Transaction tx = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00"));

        assertThat(tx).isNotNull();
        // (1000.00 * 12.00) / 1200 = 10.00 exactly.
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(tx.getTranId()).isEqualTo("2022071800000001").hasSize(16);
        assertThat(tx.getTranTypeCd()).isEqualTo("01");
        assertThat(tx.getTranCatCd()).isEqualTo(5);
        assertThat(tx.getTranSource()).isEqualTo("System");
        assertThat(tx.getTranDesc()).startsWith("Int. for a/c ");
        assertThat(tx.getTranMerchantId()).isEqualTo(0L);
        assertThat(tx.getTranMerchantName()).isEmpty();
        assertThat(tx.getTranMerchantCity()).isEmpty();
        assertThat(tx.getTranMerchantZip()).isEmpty();
        assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);
        // 26-character DB2 timestamp; deterministic because the Clock is fixed at construction.
        assertThat(tx.getTranProcTs()).matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}");
        assertThat(tx.getTranProcTs()).isEqualTo(EXPECTED_TIMESTAMP);
        assertThat(tx.getTranOrigTs()).isEqualTo(tx.getTranProcTs());
        assertThat(registry.counter(MetricsConfig.BATCH_RECORDS_PROCESSED).count()).isEqualTo(1.0d);
    }

    @Test
    void halfEvenRounding_roundsToEvenDown_0_12() {
        stubAccountAndCard();
        stubPrimaryRate("12.00");

        final Transaction tx = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "12.50"));

        // 12.50 * 12.00 = 150.0000; / 1200 = 0.125. HALF_EVEN keeps the even preceding digit (2) -> 0.12.
        // HALF_UP would yield 0.13, so this case discriminates HALF_EVEN from HALF_UP.
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("0.12"));
    }

    @Test
    void halfEvenRounding_roundsToEvenUp_0_38() {
        stubAccountAndCard();
        stubPrimaryRate("12.00");

        final Transaction tx = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "37.50"));

        // 37.50 * 12.00 = 450.0000; / 1200 = 0.375. HALF_EVEN rounds the odd preceding digit (7) up to
        // the even digit (8) -> 0.38 (again distinct from any non-banker's-rounding mode).
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("0.38"));
    }

    @Test
    void zeroRate_returnsNull_noTransaction() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCOUNT_GROUP_ID)));
        stubPrimaryRate("0.00");

        final Transaction tx = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00"));

        assertThat(tx).isNull();
        // The zero-rate guard returns before the card lookup, so no cross-reference read occurs.
        verifyNoInteractions(cardCrossReferenceRepository);
        // No interest transaction emitted -> the processed-records counter is not incremented.
        assertThat(registry.counter(MetricsConfig.BATCH_RECORDS_PROCESSED).count()).isEqualTo(0.0d);
    }

    @Test
    void defaultGroupFallback_onPrimaryMiss() {
        stubAccountAndCard();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCOUNT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD, "6.00")));

        final Transaction tx = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00"));

        // (1000.00 * 6.00) / 1200 = 5.00, computed from the DEFAULT group's rate.
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("5.00"));
        verify(disclosureGroupRepository)
                .findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD));
    }

    @Test
    void bothPrimaryAndDefaultMissing_throwsRecordNotFound() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(ACCT_ID, ACCOUNT_GROUP_ID)));
        when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCOUNT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void missingAccount_throwsRecordNotFound() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void tranIdSuffix_incrementsPerEmittedTransaction() {
        stubAccountAndCard();
        stubPrimaryRate("12.00");

        final Transaction first = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00"));
        final Transaction second = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00"));

        // The suffix increments BEFORE the id is built, so the first emitted id is ...000001.
        assertThat(first.getTranId()).isEqualTo("2022071800000001").hasSize(16)
                .startsWith(PARM_DATE).endsWith("000001");
        assertThat(second.getTranId()).isEqualTo("2022071800000002").hasSize(16)
                .startsWith(PARM_DATE).endsWith("000002");
        assertThat(registry.counter(MetricsConfig.BATCH_RECORDS_PROCESSED).count()).isEqualTo(2.0d);
    }

    @Test
    void processor_neverPersists() {
        stubAccountAndCard();
        stubPrimaryRate("12.00");

        final Transaction tx = processor.process(tcatbal(ACCT_ID, TYPE_CD, CAT_CD, "1000.00"));

        assertThat(tx).isNotNull();
        // The processor only reads and builds; the batch writer owns persistence.
        verify(accountRepository, never()).save(any());
        verify(disclosureGroupRepository, never()).save(any());
        verify(cardCrossReferenceRepository, never()).save(any());
    }
}
