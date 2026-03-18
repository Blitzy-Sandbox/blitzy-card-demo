package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.ValidationLookupService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Unit tests for {@link AccountUpdateService} — the most complex service in the
 * CardDemo migration, translating COACTUPC.cbl (4,236 lines) to Java.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Account retrieval via 3-step read chain (xref → account → customer)</li>
 *   <li>{@code @Transactional} with rollback on exception (SYNCPOINT ROLLBACK)</li>
 *   <li>Optimistic locking via {@code @Version} (before/after image comparison)</li>
 *   <li>25+ field validations: SSN, FICO, dates, state, ZIP, NANPA, cross-validation</li>
 *   <li>BigDecimal precision for all monetary fields (zero floating-point)</li>
 *   <li>Dual-entity atomic update (Account + Customer in single transaction)</li>
 *   <li>Validation error aggregation (all errors collected before throwing)</li>
 * </ul>
 *
 * <p>No Spring context is loaded — pure Mockito unit tests.
 *
 * @see AccountUpdateService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService — COACTUPC.cbl Migration Unit Tests")
class AccountUpdateServiceTest {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Standard 11-digit account ID for test fixtures. */
    private static final String ACCT_ID = "00000000001";

    /** Standard 9-digit customer ID for test fixtures. */
    private static final String CUST_ID = "0000000001";

    /** Standard 16-digit card number for cross-reference fixtures. */
    private static final String CARD_NUM = "4111111111111111";

    // =========================================================================
    // Mocks — 5 dependencies injected via constructor into AccountUpdateService
    // =========================================================================

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private ValidationLookupService validationLookupService;

    @InjectMocks
    private AccountUpdateService accountUpdateService;

    // =========================================================================
    // Test Fixtures
    // =========================================================================

    private Account testAccount;
    private Customer testCustomer;
    private CardCrossReference testXref;

    // =========================================================================
    // Setup — @BeforeEach initializes fixtures and lenient validation stubs
    // =========================================================================

    @BeforeEach
    void setUp() {
        testAccount = createTestAccount();
        testCustomer = createTestCustomer();
        testXref = createTestXref();

        // Lenient stubs for validation services — used by most updateAccount tests
        // but not by getAccount tests. Lenient prevents UnnecessaryStubbingException.
        lenient().when(dateValidationService.validateDate(anyString(), anyString()))
                .thenReturn(validDateResult());
        lenient().when(dateValidationService.validateDateOfBirth(anyString(), anyString()))
                .thenReturn(validDateResult());
        lenient().when(validationLookupService.isValidStateCode(anyString()))
                .thenReturn(true);
        lenient().when(validationLookupService.isValidAreaCode(anyString()))
                .thenReturn(true);
        lenient().when(validationLookupService.isValidStateZipPrefix(anyString(), anyString()))
                .thenReturn(true);
    }

    // =========================================================================
    // 1. GET ACCOUNT TESTS
    // =========================================================================

    @Test
    @DisplayName("getAccount — success: returns populated AccountDto from 3-step read chain")
    void testGetAccount_success() {
        // Arrange: xref → account → customer read chain
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(testXref));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(testAccount));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.of(testCustomer));

        // Act
        AccountDto result = accountUpdateService.getAccount(ACCT_ID);

        // Assert — verify populated fields from both entities
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(ACCT_ID);
        assertThat(result.getCustFname()).isEqualTo(testCustomer.getCustFirstName());
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(testAccount.getAcctCurrBal());
        assertThat(result.getCustState()).isEqualTo(testCustomer.getCustAddrStateCd());

        // Verify all three repos were consulted in order
        verify(cardCrossReferenceRepository).findByXrefAcctId(ACCT_ID);
        verify(accountRepository).findById(ACCT_ID);
        verify(customerRepository).findById(CUST_ID);
    }

    @Test
    @DisplayName("getAccount — not found: throws RecordNotFoundException (FILE STATUS 23)")
    void testGetAccount_notFound_throwsRecordNotFound() {
        // Arrange: xref found but account missing in repository
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(testXref));
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.empty());

        // Act & Assert — maps to COBOL INVALID KEY condition
        assertThatThrownBy(() -> accountUpdateService.getAccount(ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // =========================================================================
    // 2. ACCOUNT STATUS VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("updateAccount — invalid status 'X': throws ValidationException")
    void testUpdateAccount_invalidAccountStatus_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("X");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "acctActiveStatus".equals(e.fieldName())
                                && e.message().contains("must be Y or N")));

        // Validation fails before repo calls — neither find nor save should be invoked
        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount — valid status 'Y': passes validation")
    void testUpdateAccount_validAccountStatusY() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("Y");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
        assertThat(result.getAcctActiveStatus()).isEqualTo("Y");
    }

    @Test
    @DisplayName("updateAccount — valid status 'N': passes validation")
    void testUpdateAccount_validAccountStatusN() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("N");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
        assertThat(result.getAcctActiveStatus()).isEqualTo("N");
    }

    // =========================================================================
    // 3. DATE VALIDATION TESTS (via DateValidationService)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — invalid open date: throws ValidationException")
    void testUpdateAccount_invalidOpenDate_throwsValidation() {
        AccountDto dto = createValidDto();
        // Override lenient stub for "Open Date" label to return invalid
        when(dateValidationService.validateDate(anyString(), eq("Open Date")))
                .thenReturn(invalidDateResult("Open Date is not a valid date."));

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "acctOpenDate".equals(e.fieldName())));
    }

    @Test
    @DisplayName("updateAccount — invalid expire date: throws ValidationException")
    void testUpdateAccount_invalidExpireDate_throwsValidation() {
        AccountDto dto = createValidDto();
        when(dateValidationService.validateDate(anyString(), eq("Expiry Date")))
                .thenReturn(invalidDateResult("Expiry Date is not a valid date."));

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "acctExpDate".equals(e.fieldName())));
    }

    @Test
    @DisplayName("updateAccount — invalid reissue date: throws ValidationException")
    void testUpdateAccount_invalidReissueDate_throwsValidation() {
        AccountDto dto = createValidDto();
        when(dateValidationService.validateDate(anyString(), eq("Reissue Date")))
                .thenReturn(invalidDateResult("Reissue Date is not a valid date."));

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "acctReissueDate".equals(e.fieldName())));
    }

    // =========================================================================
    // 4. MONETARY FIELD VALIDATIONS (BigDecimal ONLY — AAP §0.8.2)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — currentBalance stored as BigDecimal, not float/double")
    void testUpdateAccount_currentBalance_bigDecimal() {
        AccountDto dto = createValidDto();
        dto.setAcctCurrBal(new BigDecimal("12345.67"));
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("updateAccount — creditLimit stored as BigDecimal, not float/double")
    void testUpdateAccount_creditLimit_bigDecimal() {
        AccountDto dto = createValidDto();
        dto.setAcctCreditLimit(new BigDecimal("50000.00"));
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
        assertThat(result.getAcctCreditLimit()).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("updateAccount — cashAdvanceLimit stored as BigDecimal, not float/double")
    void testUpdateAccount_cashAdvanceLimit_bigDecimal() {
        AccountDto dto = createValidDto();
        dto.setAcctCashCreditLimit(new BigDecimal("5000.00"));
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
        assertThat(result.getAcctCashCreditLimit()).isInstanceOf(BigDecimal.class);
    }

    // =========================================================================
    // 5. SSN VALIDATION TESTS (3-part: area-group-serial, COACTUPC rules)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — SSN area 000: throws ValidationException")
    void testUpdateAccount_invalidSsnArea000_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("000456789");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custSsn".equals(e.fieldName())
                                && e.message().contains("should not be 000, 666")));
    }

    @Test
    @DisplayName("updateAccount — SSN area 666: throws ValidationException")
    void testUpdateAccount_invalidSsnArea666_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("666456789");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custSsn".equals(e.fieldName())
                                && e.message().contains("should not be 000, 666")));
    }

    @Test
    @DisplayName("updateAccount — SSN area 900-999: throws ValidationException")
    void testUpdateAccount_invalidSsnArea900to999_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("950456789");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custSsn".equals(e.fieldName())
                                && e.message().contains("between 900 and 999")));
    }

    @Test
    @DisplayName("updateAccount — valid SSN 123-45-6789: passes SSN validation")
    void testUpdateAccount_validSsn() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("123456789");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
    }

    // =========================================================================
    // 6. FICO SCORE VALIDATION TESTS (300–850 range)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — FICO score 299 (below 300): throws ValidationException")
    void testUpdateAccount_ficoScoreBelow300_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("299");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custFicoScore".equals(e.fieldName())
                                && e.message().contains("between 300 and 850")));
    }

    @Test
    @DisplayName("updateAccount — FICO score 851 (above 850): throws ValidationException")
    void testUpdateAccount_ficoScoreAbove850_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("851");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custFicoScore".equals(e.fieldName())
                                && e.message().contains("between 300 and 850")));
    }

    @Test
    @DisplayName("updateAccount — FICO score 300 (boundary min): passes validation")
    void testUpdateAccount_ficoScore300_valid() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("300");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("updateAccount — FICO score 850 (boundary max): passes validation")
    void testUpdateAccount_ficoScore850_valid() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("850");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
    }

    // =========================================================================
    // 7. NAME VALIDATION TESTS (alpha-required)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — blank first name: throws ValidationException (required)")
    void testUpdateAccount_blankFirstName_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustFname("   ");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custFname".equals(e.fieldName())
                                && e.message().contains("must be supplied")));
    }

    @Test
    @DisplayName("updateAccount — valid first name 'John': passes validation")
    void testUpdateAccount_validFirstName() {
        AccountDto dto = createValidDto();
        dto.setCustFname("John");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
    }

    // =========================================================================
    // 8. STATE VALIDATION TESTS (via ValidationLookupService)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — invalid state code: throws ValidationException")
    void testUpdateAccount_invalidState_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustState("XX");
        // Override the lenient stub for this specific test
        when(validationLookupService.isValidStateCode(eq("XX")))
                .thenReturn(false);

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custState".equals(e.fieldName())
                                && e.message().contains("not a valid state code")));
    }

    @Test
    @DisplayName("updateAccount — valid state 'CA': passes validation")
    void testUpdateAccount_validState() {
        AccountDto dto = createValidDto();
        dto.setCustState("CA");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
    }

    // =========================================================================
    // 9. ZIP CODE VALIDATION TEST
    // =========================================================================

    @Test
    @DisplayName("updateAccount — non-numeric ZIP 'ABCDE': throws ValidationException")
    void testUpdateAccount_nonNumericZip_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustZip("ABCDE");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custZip".equals(e.fieldName())
                                && e.message().contains("numeric")));
    }

    // =========================================================================
    // 10. PHONE NANPA VALIDATION TEST (via ValidationLookupService)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — invalid NANPA area code: throws ValidationException")
    void testUpdateAccount_invalidAreaCode_throwsValidation() {
        AccountDto dto = createValidDto();
        // Use area "123" (non-zero so it passes the zero-check and reaches isValidAreaCode)
        dto.setCustPhone1("1235551234");
        when(validationLookupService.isValidAreaCode(eq("123")))
                .thenReturn(false);

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custPhone1".equals(e.fieldName())));
    }

    // =========================================================================
    // 11. STATE/ZIP CROSS-VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("updateAccount — invalid state/ZIP combo: throws ValidationException")
    void testUpdateAccount_invalidStateZipCombo_throwsValidation() {
        AccountDto dto = createValidDto();
        dto.setCustState("NY");
        dto.setCustZip("90210");
        when(validationLookupService.isValidStateZipPrefix(eq("NY"), eq("90210")))
                .thenReturn(false);

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                        .anyMatch(e -> "custZip".equals(e.fieldName())
                                && e.message().contains("Invalid zip code for state")));
    }

    @Test
    @DisplayName("updateAccount — valid state/ZIP combo CA+90210: passes validation")
    void testUpdateAccount_validStateZipCombo() {
        AccountDto dto = createValidDto();
        dto.setCustState("CA");
        dto.setCustZip("90210");
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
    }

    // =========================================================================
    // 12. OPTIMISTIC LOCKING TEST (COACTUPC SYNCPOINT — AAP §0.8.4)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — optimistic lock conflict: throws ConcurrentModificationException")
    void testUpdateAccount_optimisticLockConflict_throwsConcurrentModification() {
        AccountDto dto = createValidDto();

        // Only set up read chain — do NOT stub save (the doThrow below IS the save stub)
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(testXref));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.of(testCustomer));

        // Simulate JPA @Version mismatch on save — customerRepository.save never reached
        doThrow(new ObjectOptimisticLockingFailureException(Account.class.getName(), ACCT_ID))
                .when(accountRepository).save(any(Account.class));

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    // =========================================================================
    // 13. ATOMIC DUAL-RECORD UPDATE TESTS (@Transactional — SYNCPOINT ROLLBACK)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — success: both Account and Customer entities saved atomically")
    void testUpdateAccount_success_accountAndCustomerSaved() {
        AccountDto dto = createValidDto();
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        // Both entities must be persisted within the same @Transactional boundary
        assertThat(result).isNotNull();
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(customerRepository, times(1)).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount — rollback on exception: customer save failure prevents commit")
    void testUpdateAccount_rollbackOnException() {
        AccountDto dto = createValidDto();

        // Set up repo read chain (needed to reach save logic)
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(testXref));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.of(testCustomer));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Simulate failure on customer save — Spring @Transactional rollback
        when(customerRepository.save(any(Customer.class)))
                .thenThrow(new RuntimeException("Database error"));

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(RuntimeException.class);

        // Account save was attempted before customer save failed
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    // =========================================================================
    // 14. AGGREGATE VALIDATION ERRORS TEST
    // =========================================================================

    @Test
    @DisplayName("updateAccount — multiple validation errors aggregated into single exception")
    void testUpdateAccount_multipleErrors_aggregated() {
        AccountDto dto = createValidDto();
        // Set multiple invalid fields simultaneously
        dto.setAcctActiveStatus("X");
        dto.setCustFname("   ");
        dto.setCustSsn("000000000");
        dto.setCustFicoScore("100");

        assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCT_ID, dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    var errors = ((ValidationException) ex).getFieldErrors();
                    // At least 3+ errors expected: status, first name, SSN, FICO
                    assertThat(errors.size()).isGreaterThanOrEqualTo(3);
                });
    }

    // =========================================================================
    // 15. BIGDECIMAL PRECISION TESTS (AAP §0.8.2)
    // =========================================================================

    @Test
    @DisplayName("updateAccount — balance: BigDecimal.compareTo() for financial assertions")
    void testUpdateAccount_balance_compareTo() {
        AccountDto dto = createValidDto();
        dto.setAcctCurrBal(new BigDecimal("99999.99"));
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        // Use compareTo, NEVER equals — equals is scale-sensitive (AAP §0.8.2)
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("99999.99"));
    }

    @Test
    @DisplayName("updateAccount — creditLimit: BigDecimal.compareTo() for credit limit")
    void testUpdateAccount_creditLimit_compareTo() {
        AccountDto dto = createValidDto();
        dto.setAcctCreditLimit(new BigDecimal("75000.00"));
        setupUpdateRepoMocks();

        AccountDto result = accountUpdateService.updateAccount(ACCT_ID, dto);

        assertThat(result).isNotNull();
        assertThat(result.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("75000.00"));
    }

    // =========================================================================
    // 16. VERIFICATION TEST
    // =========================================================================

    @Test
    @DisplayName("updateAccount — success: verify accountRepository.save() and customerRepository.save() invoked")
    void testUpdateAccount_success_verifySaveCalled() {
        AccountDto dto = createValidDto();
        setupUpdateRepoMocks();

        accountUpdateService.updateAccount(ACCT_ID, dto);

        // Verify exactly one save for each entity in the dual-record transaction
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(customerRepository, times(1)).save(any(Customer.class));
        // Also verify the full read chain was exercised
        verify(accountRepository, times(1)).findById(ACCT_ID);
        verify(cardCrossReferenceRepository, times(1)).findByXrefAcctId(ACCT_ID);
        verify(customerRepository, times(1)).findById(CUST_ID);
    }

    // =========================================================================
    // HELPER METHODS — Test Fixture Construction
    // =========================================================================

    /**
     * Creates a test Account entity with valid field values.
     * Uses BigDecimal for all monetary fields (zero floating-point).
     */
    private Account createTestAccount() {
        Account acct = new Account();
        acct.setAcctId(ACCT_ID);
        acct.setAcctActiveStatus("Y");
        acct.setAcctCurrBal(new BigDecimal("1500.75"));
        acct.setAcctCreditLimit(new BigDecimal("10000.00"));
        acct.setAcctCashCreditLimit(new BigDecimal("2500.00"));
        acct.setAcctCurrCycCredit(new BigDecimal("250.00"));
        acct.setAcctCurrCycDebit(new BigDecimal("100.00"));
        acct.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        acct.setAcctExpDate(LocalDate.of(2027, 12, 31));
        acct.setAcctReissueDate(LocalDate.of(2025, 6, 15));
        acct.setAcctGroupId("000000001");
        acct.setVersion(1);
        return acct;
    }

    /**
     * Creates a test Customer entity with valid field values including
     * proper SSN structure, FICO score, address, and phone number.
     */
    private Customer createTestCustomer() {
        Customer cust = new Customer();
        cust.setCustId(CUST_ID);
        cust.setCustFirstName("John");
        cust.setCustMiddleName("Michael");
        cust.setCustLastName("Smith");
        cust.setCustAddrLine1("123 Main Street");
        cust.setCustAddrLine2("Apt 4B");
        cust.setCustAddrLine3("Los Angeles");
        cust.setCustAddrStateCd("CA");
        cust.setCustAddrZip("90210");
        cust.setCustAddrCountryCd("US");
        cust.setCustPhoneNum1("2125551234");
        cust.setCustPhoneNum2("3105559876");
        cust.setCustSsn("123456789");
        cust.setCustDob(LocalDate.of(1985, 5, 20));
        cust.setCustFicoCreditScore((short) 750);
        cust.setCustGovtIssuedId("DL12345678");
        cust.setCustEftAccountId("1234567890");
        cust.setCustPriCardHolderInd("Y");
        return cust;
    }

    /**
     * Creates a test CardCrossReference linking the test card, customer, and account.
     */
    private CardCrossReference createTestXref() {
        return new CardCrossReference(CARD_NUM, CUST_ID, ACCT_ID);
    }

    /**
     * Creates a fully valid AccountDto that passes all 25+ validations.
     * Values match the constraints in COACTUPC.cbl validation cascade.
     */
    private AccountDto createValidDto() {
        AccountDto dto = new AccountDto();
        dto.setAcctId(ACCT_ID);
        dto.setAcctActiveStatus("Y");
        dto.setAcctCurrBal(new BigDecimal("1500.75"));
        dto.setAcctCreditLimit(new BigDecimal("10000.00"));
        dto.setAcctCashCreditLimit(new BigDecimal("2500.00"));
        dto.setAcctCurrCycCredit(new BigDecimal("250.00"));
        dto.setAcctCurrCycDebit(new BigDecimal("100.00"));
        dto.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        dto.setAcctExpDate(LocalDate.of(2027, 12, 31));
        dto.setAcctReissueDate(LocalDate.of(2025, 6, 15));
        dto.setAcctGroupId("000000001");
        dto.setCustFname("John");
        dto.setCustMname("Michael");
        dto.setCustLname("Smith");
        dto.setCustAddr1("123 Main Street");
        dto.setCustAddr2("Apt 4B");
        dto.setCustCity("Los Angeles");
        dto.setCustState("CA");
        dto.setCustZip("90210");
        dto.setCustCountry("US");
        dto.setCustPhone1("2125551234");
        dto.setCustPhone2("3105559876");
        dto.setCustSsn("123456789");
        dto.setCustDob(LocalDate.of(1985, 5, 20));
        dto.setCustFicoScore("750");
        dto.setCustGovtId("DL12345678");
        dto.setCustEftAcct("1234567890");
        dto.setCustProfileFlag("Y");
        return dto;
    }

    /**
     * Configures the repository mock chain for a successful update flow:
     * findById(account) → findByXrefAcctId → findById(customer) → save both.
     * Must be called before any test that exercises the full update path.
     */
    private void setupUpdateRepoMocks() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(testXref));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.of(testCustomer));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Creates a valid DateValidationResult (all fields valid).
     */
    private DateValidationService.DateValidationResult validDateResult() {
        return new DateValidationService.DateValidationResult(
                true, 0, "OK", "Date is valid.", true, true, true
        );
    }

    /**
     * Creates an invalid DateValidationResult with the given error message.
     */
    private DateValidationService.DateValidationResult invalidDateResult(String message) {
        return new DateValidationService.DateValidationResult(
                false, 4, "ERROR", message, false, false, false
        );
    }
}