package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import com.carddemo.service.shared.FileStatusMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InterestCalculationProcessor} (COBOL {@code CBACT04C} parity): the
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} HALF_EVEN formula, the DEFAULT disclosure-group
 * fallback, the zero-rate skip, the fatal not-found reads, and the deterministic TRAN-ID.
 */
@ExtendWith(MockitoExtension.class)
class InterestCalculationProcessorTest {

    private static final String PARM_DATE = "2026-08-15";
    private static final Long ACCOUNT_ID = 12345678901L;
    private static final String GROUP_ID = "GOLD";
    private static final String TYPE_CD = "01";
    private static final Integer CAT_CD = 5;
    private static final String CARD_NUM = "4111111111111111";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;
    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;
    @Mock
    private FileStatusMapper fileStatusMapper;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);

    private InterestCalculationProcessor processor() {
        return new InterestCalculationProcessor(accountRepository, disclosureGroupRepository,
                cardCrossReferenceRepository, fileStatusMapper, registry, PARM_DATE, fixedClock);
    }

    private static TransactionCategoryBalance tcatbal(BigDecimal balance) {
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setId(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CD, CAT_CD));
        b.setTranCatBal(balance);
        return b;
    }

    private static DisclosureGroup group(String groupId, String rate) {
        DisclosureGroup g = new DisclosureGroup();
        g.setId(new DisclosureGroupId(groupId, TYPE_CD, CAT_CD));
        g.setDisIntRate(new BigDecimal(rate));
        return g;
    }

    private void stubAccountWithGroup() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctGroupId(GROUP_ID);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    private void stubXref() {
        CardCrossReference x = new CardCrossReference();
        x.setXrefAcctId(ACCOUNT_ID);
        x.setXrefCardNum(CARD_NUM);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(x));
    }

    @Test
    void nonZeroRateComputesInterestWithHalfEvenScaleTwo() {
        stubAccountWithGroup();
        stubXref();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(group(GROUP_ID, "12.00")));

        // (1000.00 * 12.00) / 1200 = 10.00
        Transaction tx = processor().process(tcatbal(new BigDecimal("1000.00")));

        assertThat(tx).isNotNull();
        assertThat(tx.getTranAmt()).isEqualByComparingTo("10.00");
        assertThat(tx.getTranAmt().scale()).isEqualTo(2);
        assertThat(tx.getTranId()).isEqualTo(PARM_DATE + "000001");
        assertThat(tx.getTranTypeCd()).isEqualTo("01");
        assertThat(tx.getTranCatCd()).isEqualTo(5);
        assertThat(tx.getTranSource()).isEqualTo("System");
        assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);
        assertThat(tx.getTranDesc()).isEqualTo("Int. for a/c " + "12345678901");
        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(1.0);
    }

    @Test
    void zeroRateReturnsNullAndWritesNothing() {
        stubAccountWithGroup();
        when(disclosureGroupRepository.findById(any()))
                .thenReturn(Optional.of(group(GROUP_ID, "0.00")));

        Transaction tx = processor().process(tcatbal(new BigDecimal("1000.00")));

        assertThat(tx).isNull();
        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(0.0);
    }

    @Test
    void defaultGroupFallbackUsedWhenPrimaryMissing() {
        stubAccountWithGroup();
        stubXref();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(group("DEFAULT", "6.00")));

        // (2000.00 * 6.00) / 1200 = 10.00
        Transaction tx = processor().process(tcatbal(new BigDecimal("2000.00")));

        assertThat(tx).isNotNull();
        assertThat(tx.getTranAmt()).isEqualByComparingTo("10.00");
    }

    @Test
    void bothPrimaryAndDefaultMissingThrowsViaFileStatusMapper() {
        stubAccountWithGroup();
        when(disclosureGroupRepository.findById(any())).thenReturn(Optional.empty());
        when(fileStatusMapper.toException(any(), any(), any()))
                .thenReturn(new RecordNotFoundException("DisclosureGroup not found"));

        assertThatThrownBy(() -> processor().process(tcatbal(new BigDecimal("100.00"))))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void accountNotFoundThrows() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor().process(tcatbal(new BigDecimal("100.00"))))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void crossReferenceNotFoundThrows() {
        stubAccountWithGroup();
        when(disclosureGroupRepository.findById(any()))
                .thenReturn(Optional.of(group(GROUP_ID, "12.00")));
        lenient().when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> processor().process(tcatbal(new BigDecimal("100.00"))))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void nullItemThrows() {
        assertThatThrownBy(() -> processor().process(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidParmDateLengthRejectedByConstructor() {
        assertThatThrownBy(() -> new InterestCalculationProcessor(accountRepository, disclosureGroupRepository,
                cardCrossReferenceRepository, fileStatusMapper, registry, "2026", fixedClock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
