package com.cardemo.unit.batch;

import com.cardemo.batch.processors.InterestCalculationProcessor;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InterestCalculationProcessor} — the Spring Batch
 * ItemProcessor that implements CBACT04C.cbl (652 lines) interest calculation
 * logic from the CardDemo mainframe application.
 *
 * <p>Tests the exact interest formula {@code (balance × rate) / 1200} with
 * {@code RoundingMode.HALF_EVEN} (banker's rounding, COBOL default), the
 * critical DEFAULT group fallback when a specific disclosure group is not
 * found, account boundary detection, transaction record generation, and
 * account balance update logic.
 *
 * <p>All financial values use {@link BigDecimal} with {@code compareTo()}
 * assertions — zero float/double anywhere in this file (AAP §0.8.2).
 */
@ExtendWith(MockitoExtension.class)
class InterestCalculationProcessorTest {

    /** Default account ID used across most tests. */
    private static final String ACCT_ID = "00000000001";

    /** Disclosure group ID for default account. */
    private static final String GROUP_ID = "GROUP1";

    /** JCL PARM date for transaction ID generation. */
    private static final String PARM_DATE = "2024-01-15";

    /** Card number associated with the default account via XREF. */
    private static final String CARD_NUM = "4000000000000001";

    /** Transaction type code for standard interest calculations. */
    private static final String TYPE_CODE = "SA";

    /** Transaction category code (Short) for standard interest calculations. */
    private static final short CAT_CODE = (short) 1;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    private InterestCalculationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InterestCalculationProcessor(
                disclosureGroupRepository,
                cardCrossReferenceRepository,
                accountRepository
        );
        processor.setParmDate(PARM_DATE);

        // Default account mock — used by every test since process() triggers
        // account loading on first invocation (CBACT04C 1100-GET-ACCT-DATA)
        Account account = createAccount(ACCT_ID, GROUP_ID,
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        // Default XREF mock — provides card number for generated transactions
        // (CBACT04C 1110-GET-XREF-DATA alternate key lookup via CXACAIX)
        CardCrossReference xref = createXref(CARD_NUM, ACCT_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref));
    }

    // ========================================================================
    // Interest Formula Tests — (balance × rate) / 1200 with HALF_EVEN
    // Maps CBACT04C paragraph 1300-COMPUTE-INTEREST:
    //   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
    // ========================================================================

    /**
     * Verifies the exact interest formula: {@code (balance × rate) / 1200}.
     * Uses clean values that divide evenly to isolate formula correctness.
     * Maps CBACT04C paragraph 1300-COMPUTE-INTEREST.
     * CRITICAL: Formula is (balance × rate) / 1200 — NO algebraic simplification.
     */
    @Test
    void process_shouldComputeInterestWithExactFormula() throws Exception {
        // balance = 1200.00, rate = 12.00% annual
        // Expected: (1200.00 × 12.00) / 1200 = 12.00
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg));

        Transaction result = processor.process(item);

        assertThat(result).isNotNull();
        assertThat(result.getTranAmt().compareTo(new BigDecimal("12.00"))).isEqualTo(0);
    }

    /**
     * Verifies RoundingMode.HALF_EVEN (banker's rounding, COBOL default).
     * Tests three scenarios:
     * <ol>
     *   <li>Exact result (no rounding needed)</li>
     *   <li>Standard rounding (digit > 5)</li>
     *   <li>HALF_EVEN midpoint — distinguishes from HALF_UP</li>
     * </ol>
     */
    @Test
    void process_shouldUseBankersRoundingForInterest() throws Exception {
        // Test 1: Exact result — (1000.00 × 7.50) / 1200 = 6.25
        TransactionCategoryBalance item1 = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1000.00"));
        DisclosureGroup dg1 = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("7.50"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg1));

        Transaction result1 = processor.process(item1);
        assertThat(result1).isNotNull();
        assertThat(result1.getTranAmt().compareTo(new BigDecimal("6.25"))).isEqualTo(0);

        // Test 2: Rounding required — (333.33 × 5.00) / 1200 = 1.388875 → 1.39
        short catCode2 = (short) 2;
        TransactionCategoryBalance item2 = createTcatbal(ACCT_ID, "PR", catCode2,
                new BigDecimal("333.33"));
        DisclosureGroup dg2 = createDisclosureGroup(GROUP_ID, "PR", catCode2,
                new BigDecimal("5.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq("PR"), eq(catCode2)))
                .thenReturn(Optional.of(dg2));

        Transaction result2 = processor.process(item2);
        assertThat(result2).isNotNull();
        assertThat(result2.getTranAmt().compareTo(new BigDecimal("1.39"))).isEqualTo(0);

        // Test 3: HALF_EVEN midpoint — (183.00 × 10.00) / 1200 = 1.525
        // HALF_EVEN rounds to 1.52 (even digit); HALF_UP would give 1.53
        short catCode3 = (short) 3;
        TransactionCategoryBalance item3 = createTcatbal(ACCT_ID, "FE", catCode3,
                new BigDecimal("183.00"));
        DisclosureGroup dg3 = createDisclosureGroup(GROUP_ID, "FE", catCode3,
                new BigDecimal("10.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq("FE"), eq(catCode3)))
                .thenReturn(Optional.of(dg3));

        Transaction result3 = processor.process(item3);
        assertThat(result3).isNotNull();
        // HALF_EVEN: 1.525 → 1.52 (2 is even), not 1.53 as HALF_UP would give
        assertThat(result3.getTranAmt().compareTo(new BigDecimal("1.52"))).isEqualTo(0);
    }

    /**
     * Verifies the result scale matches COBOL PIC S9(9)V99 — exactly 2 decimal places.
     */
    @Test
    void process_shouldProduceScaleTwoResult() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("2400.00"));
        DisclosureGroup dg = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("18.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg));

        Transaction result = processor.process(item);

        assertThat(result).isNotNull();
        // (2400.00 × 18.00) / 1200 = 36.00
        assertThat(result.getTranAmt().compareTo(new BigDecimal("36.00"))).isEqualTo(0);
        assertThat(result.getTranAmt().scale()).isEqualTo(2);
    }

    // ========================================================================
    // DEFAULT Group Fallback Tests
    // Maps CBACT04C paragraphs:
    //   1200-GET-INTEREST-RATE: READ DISCGRP by acct group+type+cat
    //   1200-A-GET-DEFAULT-INT-RATE: fallback with 'DEFAULT' group ID
    // ========================================================================

    /**
     * Verifies 2-step fallback: specific group not found → retry with "DEFAULT".
     * Maps CBACT04C paragraphs 1200-GET-INTEREST-RATE + 1200-A-GET-DEFAULT-INT-RATE.
     */
    @Test
    void process_shouldFallbackToDefaultGroupWhenSpecificNotFound() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));

        // Specific group → not found (FILE STATUS '23')
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.empty());

        // DEFAULT fallback → rate 6.00%
        DisclosureGroup defaultDg = createDisclosureGroup("DEFAULT", TYPE_CODE, CAT_CODE,
                new BigDecimal("6.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq("DEFAULT"), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(defaultDg));

        Transaction result = processor.process(item);

        assertThat(result).isNotNull();
        // Interest computed using DEFAULT rate: (1200.00 × 6.00) / 1200 = 6.00
        assertThat(result.getTranAmt().compareTo(new BigDecimal("6.00"))).isEqualTo(0);

        // Verify: repo called TWICE — first specific groupId, then "DEFAULT"
        verify(disclosureGroupRepository).findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE));
        verify(disclosureGroupRepository).findByGroupIdAndTypeCodeAndCatCode(
                eq("DEFAULT"), eq(TYPE_CODE), eq(CAT_CODE));
    }

    /**
     * Verifies ABEND when both specific and DEFAULT groups are not found.
     * Maps CBACT04C paragraph 9999-ABEND-PROGRAM triggered from 1200-A.
     */
    @Test
    void process_shouldThrowExceptionWhenDefaultGroupAlsoNotFound() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));

        // Both specific and DEFAULT → not found → ABEND
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq("DEFAULT"), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.empty());

        // CardDemoException extends RuntimeException (not in depends_on_files)
        assertThatThrownBy(() -> processor.process(item))
                .isInstanceOf(RuntimeException.class);
    }

    // ========================================================================
    // Zero Rate Test
    // ========================================================================

    /**
     * Verifies that a zero interest rate produces no transaction.
     * When DIS-INT-RATE = 0, COBOL skips WRITE and process() returns null.
     */
    @Test
    void process_shouldReturnNullWhenInterestRateIsZero() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("5000.00"));
        DisclosureGroup dg = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                BigDecimal.ZERO);
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg));

        Transaction result = processor.process(item);

        // Zero rate → zero interest → no transaction generated
        assertThat(result).isNull();
    }

    // ========================================================================
    // Transaction Generation Tests
    // Maps CBACT04C paragraph 1300-B-WRITE-TX:
    //   MOVE '01' TO TRAN-TYPE-CD, MOVE '0005' TO TRAN-CAT-CD,
    //   MOVE 'System' TO TRAN-SOURCE
    //   STRING 'Int. for a/c ' ACCT-ID INTO TRAN-DESC
    // ========================================================================

    /**
     * Verifies generated interest transaction has correct fixed fields:
     * type='01', cat=5, source='System', desc starts with 'Int. for a/c '.
     */
    @Test
    void process_shouldGenerateTransactionWithCorrectFixedFields() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg));

        Transaction result = processor.process(item);

        assertThat(result).isNotNull();
        // Fixed fields from CBACT04C 1300-B-WRITE-TX
        assertThat(result.getTranTypeCd()).isEqualTo("01");
        assertThat(result.getTranCatCd()).isEqualTo((short) 5);
        assertThat(result.getTranSource()).isEqualTo("System");
        assertThat(result.getTranDesc()).startsWith("Int. for a/c ");
        assertThat(result.getTranDesc()).contains(ACCT_ID);
    }

    /**
     * Verifies transaction ID format: parmDate + '-' + 5-digit auto-incrementing suffix.
     * Maps CBACT04C: STRING PARM-DATE '-' WS-TRANID-SUFFIX INTO TRAN-ID.
     */
    @Test
    void process_shouldGenerateTransactionIdWithParmDateAndSuffix() throws Exception {
        // First item — suffix starts at 00001
        TransactionCategoryBalance item1 = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg1 = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg1));

        Transaction result1 = processor.process(item1);
        assertThat(result1).isNotNull();
        assertThat(result1.getTranId()).isEqualTo("2024-01-15-00001");

        // Second item — auto-incrementing suffix → 00002
        short catCode2 = (short) 2;
        TransactionCategoryBalance item2 = createTcatbal(ACCT_ID, "PR", catCode2,
                new BigDecimal("600.00"));
        DisclosureGroup dg2 = createDisclosureGroup(GROUP_ID, "PR", catCode2,
                new BigDecimal("6.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq("PR"), eq(catCode2)))
                .thenReturn(Optional.of(dg2));

        Transaction result2 = processor.process(item2);
        assertThat(result2).isNotNull();
        assertThat(result2.getTranId()).isEqualTo("2024-01-15-00002");
    }

    // ========================================================================
    // Account Boundary Detection and Update Tests
    // Maps CBACT04C paragraphs:
    //   1050-UPDATE-ACCOUNT: ADD WS-TOTAL-INT TO ACCT-CURR-BAL,
    //                        MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT
    //   1100-GET-ACCT-DATA: READ ACCTDAT when TRANCAT-ACCT-ID changes
    //   1110-GET-XREF-DATA: READ XREFFILE by alternate key (account ID)
    // ========================================================================

    /**
     * Verifies that interest amounts from multiple TCATBAL records for the SAME
     * account are accumulated and applied as a single total on account break.
     */
    @Test
    void process_shouldAccumulateTotalInterestPerAccount() throws Exception {
        // Item 1: (1200.00 × 10.00) / 1200 = 10.00
        TransactionCategoryBalance item1 = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg1 = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("10.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg1));

        Transaction result1 = processor.process(item1);
        assertThat(result1).isNotNull();
        assertThat(result1.getTranAmt().compareTo(new BigDecimal("10.00"))).isEqualTo(0);

        // Item 2: same account, different type/cat → (600.00 × 10.00) / 1200 = 5.00
        short catCode2 = (short) 2;
        TransactionCategoryBalance item2 = createTcatbal(ACCT_ID, "PR", catCode2,
                new BigDecimal("600.00"));
        DisclosureGroup dg2 = createDisclosureGroup(GROUP_ID, "PR", catCode2,
                new BigDecimal("10.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq("PR"), eq(catCode2)))
                .thenReturn(Optional.of(dg2));

        Transaction result2 = processor.process(item2);
        assertThat(result2).isNotNull();
        assertThat(result2.getTranAmt().compareTo(new BigDecimal("5.00"))).isEqualTo(0);

        // Trigger account break with a DIFFERENT account to flush accumulated interest
        String acctId2 = "00000000002";
        Account account2 = createAccount(acctId2, "GROUP2",
                new BigDecimal("3000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"));
        when(accountRepository.findById(acctId2)).thenReturn(Optional.of(account2));
        CardCrossReference xref2 = createXref("5000000000000001", acctId2);
        when(cardCrossReferenceRepository.findByXrefAcctId(acctId2))
                .thenReturn(List.of(xref2));

        TransactionCategoryBalance item3 = createTcatbal(acctId2, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg3 = createDisclosureGroup("GROUP2", TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq("GROUP2"), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg3));

        processor.process(item3);

        // Verify account 1 saved with accumulated total interest (10.00 + 5.00 = 15.00)
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account savedAccount = captor.getValue();
        // Original balance 5000.00 + total interest 15.00 = 5015.00
        assertThat(savedAccount.getAcctCurrBal().compareTo(new BigDecimal("5015.00")))
                .isEqualTo(0);
    }

    /**
     * Verifies that on account break, the previous account is updated:
     * balance increased by total interest, cycle credit/debit zeroed.
     * Maps CBACT04C paragraph 1050-UPDATE-ACCOUNT.
     */
    @Test
    void process_shouldUpdateAccountOnAccountBreak() throws Exception {
        // Process item for account 1: interest = (1200.00 × 18.00) / 1200 = 18.00
        TransactionCategoryBalance item1 = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg1 = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("18.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg1));

        processor.process(item1);

        // Trigger account break with a different account
        String acctId2 = "00000000002";
        Account account2 = createAccount(acctId2, "GROUP2",
                new BigDecimal("3000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"));
        when(accountRepository.findById(acctId2)).thenReturn(Optional.of(account2));
        CardCrossReference xref2 = createXref("5000000000000001", acctId2);
        when(cardCrossReferenceRepository.findByXrefAcctId(acctId2))
                .thenReturn(List.of(xref2));

        TransactionCategoryBalance item2 = createTcatbal(acctId2, TYPE_CODE, CAT_CODE,
                new BigDecimal("600.00"));
        DisclosureGroup dg2 = createDisclosureGroup("GROUP2", TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq("GROUP2"), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg2));

        processor.process(item2);

        // Verify previous account was updated per 1050-UPDATE-ACCOUNT:
        //   ADD WS-TOTAL-INT TO ACCT-CURR-BAL → 5000.00 + 18.00 = 5018.00
        //   MOVE 0 TO ACCT-CURR-CYC-CREDIT
        //   MOVE 0 TO ACCT-CURR-CYC-DEBIT
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account savedAccount = captor.getValue();

        assertThat(savedAccount.getAcctCurrBal().compareTo(new BigDecimal("5018.00")))
                .isEqualTo(0);
        assertThat(savedAccount.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO))
                .isEqualTo(0);
        assertThat(savedAccount.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO))
                .isEqualTo(0);
    }

    /**
     * Verifies that XREF lookup is performed when processing a new account.
     * Maps CBACT04C paragraph 1110-GET-XREF-DATA: READ XREFFILE by account ID.
     */
    @Test
    void process_shouldLookupXrefOnNewAccount() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("1200.00"));
        DisclosureGroup dg = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg));

        processor.process(item);

        // Verify XREF lookup was called (CBACT04C 1110-GET-XREF-DATA)
        verify(cardCrossReferenceRepository).findByXrefAcctId(ACCT_ID);
    }

    // ========================================================================
    // BigDecimal Precision Tests — AAP §0.8.2 compliance
    // ========================================================================

    /**
     * Verifies BigDecimal precision throughout calculations — no float/double.
     * Tests very small values (rounds to zero → null) and precise non-zero results.
     * CRITICAL: All assertions use compareTo(), never equals() (AAP §0.8.2).
     */
    @Test
    void process_shouldMaintainBigDecimalPrecisionThroughout() throws Exception {
        // Test 1: Very small values — rate is non-zero so transaction IS generated
        // (0.01 × 0.01) / 1200 = 0.000000083... → 0.00 with scale 2
        // Processor checks if RATE is zero (not computed interest), so non-zero rate
        // still generates a transaction with 0.00 amount — BigDecimal precision preserved
        TransactionCategoryBalance item1 = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("0.01"));
        DisclosureGroup dg1 = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("0.01"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg1));

        Transaction result1 = processor.process(item1);
        // Non-zero rate → transaction generated even with 0.00 computed interest
        assertThat(result1).isNotNull();
        assertThat(result1.getTranAmt().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(result1.getTranAmt().scale()).isEqualTo(2);

        // Test 2: Precise non-zero result with BigDecimal arithmetic
        // (9999.99 × 1.23) / 1200 = 12299.9877 / 1200 = 10.2499... → 10.25
        short catCode2 = (short) 2;
        TransactionCategoryBalance item2 = createTcatbal(ACCT_ID, "PR", catCode2,
                new BigDecimal("9999.99"));
        DisclosureGroup dg2 = createDisclosureGroup(GROUP_ID, "PR", catCode2,
                new BigDecimal("1.23"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq("PR"), eq(catCode2)))
                .thenReturn(Optional.of(dg2));

        Transaction result2 = processor.process(item2);
        assertThat(result2).isNotNull();
        assertThat(result2.getTranAmt().compareTo(new BigDecimal("10.25"))).isEqualTo(0);
    }

    // ========================================================================
    // Fees Computation Test — No-op stub
    // Maps CBACT04C paragraph 1400-COMPUTE-FEES (stub / no-op in COBOL)
    // ========================================================================

    /**
     * Verifies computeFees() (CBACT04C 1400-COMPUTE-FEES stub) does not throw.
     * The COBOL paragraph is a no-op placeholder; Java equivalent must be safe.
     */
    @Test
    void process_shouldNotThrowOnFeesComputation() throws Exception {
        TransactionCategoryBalance item = createTcatbal(ACCT_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("2400.00"));
        DisclosureGroup dg = createDisclosureGroup(GROUP_ID, TYPE_CODE, CAT_CODE,
                new BigDecimal("12.00"));
        when(disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
                eq(GROUP_ID), eq(TYPE_CODE), eq(CAT_CODE)))
                .thenReturn(Optional.of(dg));

        // computeFees() is called inside process() — verify no exception thrown
        Transaction result = processor.process(item);
        assertThat(result).isNotNull();
        // (2400.00 × 12.00) / 1200 = 24.00
        assertThat(result.getTranAmt().compareTo(new BigDecimal("24.00"))).isEqualTo(0);
    }

    // ========================================================================
    // Helper Methods — Entity construction utilities
    // All amounts use BigDecimal, NEVER float/double (AAP §0.8.2)
    // ========================================================================

    /**
     * Creates a TransactionCategoryBalance entity with composite key and balance.
     *
     * @param acctId   account identifier (11-char VSAM key)
     * @param typeCode transaction type code (2-char)
     * @param catCode  transaction category code (Short, from PIC 9(4))
     * @param balance  TRAN-CAT-BAL BigDecimal amount
     * @return configured TransactionCategoryBalance entity
     */
    private TransactionCategoryBalance createTcatbal(String acctId, String typeCode,
                                                      short catCode, BigDecimal balance) {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                acctId, typeCode, catCode);
        return new TransactionCategoryBalance(id, balance);
    }

    /**
     * Creates a DisclosureGroup entity with composite key and interest rate.
     *
     * @param groupId  disclosure group identifier or "DEFAULT"
     * @param typeCode transaction type code
     * @param catCode  transaction category code (Short)
     * @param rate     DIS-INT-RATE BigDecimal (annual percentage, e.g. 18.00)
     * @return configured DisclosureGroup entity
     */
    private DisclosureGroup createDisclosureGroup(String groupId, String typeCode,
                                                   short catCode, BigDecimal rate) {
        DisclosureGroupId id = new DisclosureGroupId(groupId, typeCode, catCode);
        return new DisclosureGroup(id, rate);
    }

    /**
     * Creates an Account entity with BigDecimal financial fields.
     *
     * @param acctId    account identifier
     * @param groupId   disclosure group reference
     * @param currBal   ACCT-CURR-BAL
     * @param cycCredit ACCT-CURR-CYC-CREDIT
     * @param cycDebit  ACCT-CURR-CYC-DEBIT
     * @return configured Account entity
     */
    private Account createAccount(String acctId, String groupId, BigDecimal currBal,
                                   BigDecimal cycCredit, BigDecimal cycDebit) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctGroupId(groupId);
        account.setAcctCurrBal(currBal);
        account.setAcctCurrCycCredit(cycCredit);
        account.setAcctCurrCycDebit(cycDebit);
        return account;
    }

    /**
     * Creates a CardCrossReference entity mapping card number to account.
     *
     * @param cardNum card number (16-digit)
     * @param acctId  account identifier for CXACAIX alternate index lookup
     * @return configured CardCrossReference entity
     */
    private CardCrossReference createXref(String cardNum, String acctId) {
        return new CardCrossReference(cardNum, "CUST0001", acctId);
    }
}
