package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.account.AccountViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountViewService} — validates the COACTVWC.cbl
 * multi-dataset read chain: CXACAIX → ACCTDAT → CUSTDAT.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Input validation (null, blank, non-numeric, all-zeros, wrong-length)</li>
 *   <li>3-step read chain failure scenarios (xref/account/customer not found)</li>
 *   <li>Successful read with DTO assembly and field mapping</li>
 *   <li>BigDecimal precision for monetary fields (compareTo, never equals)</li>
 *   <li>Call order verification (CrossRef → Account → Customer)</li>
 * </ul>
 *
 * <p>No Spring context is loaded — pure Mockito unit tests.
 */
@ExtendWith(MockitoExtension.class)
class AccountViewServiceTest {

    /** Valid 11-digit account ID for success test cases. */
    private static final String VALID_ACCT_ID = "00000000012";

    /** Customer ID resolved from cross-reference in success scenarios. */
    private static final String CUST_ID = "000000001";

    /** Card number from cross-reference fixture. */
    private static final String CARD_NUM = "4111111111111111";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @InjectMocks
    private AccountViewService accountViewService;

    /** Account entity test fixture — constructed in setUp with BigDecimal monetary fields. */
    private Account account;

    /** Customer entity test fixture — constructed in setUp with all customer fields. */
    private Customer customer;

    /** Cross-reference entity test fixture — links account → card → customer. */
    private CardCrossReference crossReference;

    /**
     * Constructs test fixtures before each test method:
     * <ul>
     *   <li>Account with BigDecimal monetary fields matching PIC S9(7)V99 COMP-3</li>
     *   <li>Customer with all name, address, phone, SSN, DOB, and FICO fields</li>
     *   <li>CardCrossReference linking VALID_ACCT_ID → CUST_ID → CARD_NUM</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Build Account entity with BigDecimal monetary fields (PIC S9(7)V99 COMP-3)
        account = new Account();
        account.setAcctId(VALID_ACCT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1500.75"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        account.setAcctExpDate(LocalDate.of(2027, 12, 31));
        account.setAcctReissueDate(LocalDate.of(2024, 6, 1));
        account.setAcctCurrCycCredit(new BigDecimal("200.00"));
        account.setAcctCurrCycDebit(new BigDecimal("350.50"));
        account.setAcctGroupId("GRP001");

        // Build Customer entity with all fields from CVCUS01Y.cpy (500-byte record)
        customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setCustFirstName("JOHN");
        customer.setCustLastName("DOE");
        customer.setCustMiddleName("M");
        customer.setCustAddrLine1("123 MAIN ST");
        customer.setCustAddrLine2("APT 4B");
        customer.setCustAddrLine3("");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrCountryCd("US");
        customer.setCustAddrZip("10001");
        customer.setCustPhoneNum1("2125551234");
        customer.setCustPhoneNum2("2125555678");
        customer.setCustSsn("123456789");
        customer.setCustDob(LocalDate.of(1985, 3, 22));
        customer.setCustFicoCreditScore((short) 750);
        customer.setCustGovtIssuedId("DL12345678");
        customer.setCustEftAccountId("EFT00001");
        customer.setCustPriCardHolderInd("Y");

        // Build CardCrossReference entity (CVACT03Y.cpy, 50-byte record)
        crossReference = new CardCrossReference();
        crossReference.setXrefCardNum(CARD_NUM);
        crossReference.setXrefCustId(CUST_ID);
        crossReference.setXrefAcctId(VALID_ACCT_ID);
    }

    // =========================================================================
    // Input Validation Tests (COACTVWC guard checks)
    // =========================================================================

    @Test
    @DisplayName("COACTVWC: null account ID → IllegalArgumentException")
    void testGetAccountView_nullAcctId_throwsIllegalArgument() {
        assertThatThrownBy(() -> accountViewService.getAccountView(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("COACTVWC: blank account ID → IllegalArgumentException")
    void testGetAccountView_blankAcctId_throwsIllegalArgument() {
        assertThatThrownBy(() -> accountViewService.getAccountView("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("COACTVWC: non-numeric account ID 'ABCDE' → IllegalArgumentException")
    void testGetAccountView_nonNumericAcctId_throwsIllegalArgument() {
        assertThatThrownBy(() -> accountViewService.getAccountView("ABCDE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("COACTVWC: all-zeros account ID '00000000000' → IllegalArgumentException")
    void testGetAccountView_allZerosAcctId_throwsIllegalArgument() {
        assertThatThrownBy(() -> accountViewService.getAccountView("00000000000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("COACTVWC: wrong-length account ID '12345' → IllegalArgumentException")
    void testGetAccountView_wrongLengthAcctId_throwsIllegalArgument() {
        assertThatThrownBy(() -> accountViewService.getAccountView("12345"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 3-Step Read Chain Not-Found Tests (CXACAIX → ACCTDAT → CUSTDAT)
    // =========================================================================

    @Test
    @DisplayName("COACTVWC 9200-GETCARDXREF: empty xref list → RecordNotFoundException")
    void testGetAccountView_xrefNotFound_throwsRecordNotFound() {
        when(cardCrossReferenceRepository.findByXrefAcctId(VALID_ACCT_ID))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> accountViewService.getAccountView(VALID_ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class);

        // Downstream repositories must NOT be called when xref fails
        verify(accountRepository, never()).findById(VALID_ACCT_ID);
        verify(customerRepository, never()).findById(CUST_ID);
    }

    @Test
    @DisplayName("COACTVWC 9300-GETACCTDATA: account not found → RecordNotFoundException")
    void testGetAccountView_accountNotFound_throwsRecordNotFound() {
        when(cardCrossReferenceRepository.findByXrefAcctId(VALID_ACCT_ID))
                .thenReturn(List.of(crossReference));
        when(accountRepository.findById(VALID_ACCT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountViewService.getAccountView(VALID_ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class);

        // Customer repository must NOT be called when account fails
        verify(customerRepository, never()).findById(CUST_ID);
    }

    @Test
    @DisplayName("COACTVWC 9400-GETCUSTDATA: customer not found → RecordNotFoundException")
    void testGetAccountView_customerNotFound_throwsRecordNotFound() {
        when(cardCrossReferenceRepository.findByXrefAcctId(VALID_ACCT_ID))
                .thenReturn(List.of(crossReference));
        when(accountRepository.findById(VALID_ACCT_ID))
                .thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountViewService.getAccountView(VALID_ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // =========================================================================
    // Successful Read Tests
    // =========================================================================

    @Test
    @DisplayName("COACTVWC: all 3 reads succeed → returns populated AccountDto")
    void testGetAccountView_success_returnsPopulatedDto() {
        stubAllRepositoriesForSuccess();

        AccountDto dto = accountViewService.getAccountView(VALID_ACCT_ID);

        assertThat(dto).isNotNull();
        assertThat(dto.getAcctId()).isEqualTo(VALID_ACCT_ID);
    }

    @Test
    @DisplayName("COACTVWC: verify account fields mapped — acctId, status, balances, dates, group")
    void testGetAccountView_success_accountFieldsMapped() {
        stubAllRepositoriesForSuccess();

        AccountDto dto = accountViewService.getAccountView(VALID_ACCT_ID);

        assertThat(dto.getAcctId()).isEqualTo(VALID_ACCT_ID);
        assertThat(dto.getAcctActiveStatus()).isEqualTo("Y");
        // BigDecimal compareTo for monetary fields — per AAP §0.8.2
        assertThat(dto.getAcctCurrBal().compareTo(new BigDecimal("1500.75"))).isZero();
        assertThat(dto.getAcctCreditLimit().compareTo(new BigDecimal("5000.00"))).isZero();
        assertThat(dto.getAcctCashCreditLimit().compareTo(new BigDecimal("1000.00"))).isZero();
        assertThat(dto.getAcctOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(dto.getAcctExpDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(dto.getAcctReissueDate()).isEqualTo(LocalDate.of(2024, 6, 1));
        assertThat(dto.getAcctCurrCycCredit().compareTo(new BigDecimal("200.00"))).isZero();
        assertThat(dto.getAcctCurrCycDebit().compareTo(new BigDecimal("350.50"))).isZero();
        assertThat(dto.getAcctGroupId()).isEqualTo("GRP001");
    }

    @Test
    @DisplayName("COACTVWC: verify customer fields mapped — name, address, phone, SSN, DOB, FICO")
    void testGetAccountView_success_customerFieldsMapped() {
        stubAllRepositoriesForSuccess();

        AccountDto dto = accountViewService.getAccountView(VALID_ACCT_ID);

        assertThat(dto.getCustId()).isEqualTo(CUST_ID);
        assertThat(dto.getCustFname()).isEqualTo("JOHN");
        assertThat(dto.getCustMname()).isEqualTo("M");
        assertThat(dto.getCustLname()).isEqualTo("DOE");
        assertThat(dto.getCustAddr1()).isEqualTo("123 MAIN ST");
        assertThat(dto.getCustState()).isEqualTo("NY");
        assertThat(dto.getCustZip()).isEqualTo("10001");
        assertThat(dto.getCustCountry()).isEqualTo("US");
        assertThat(dto.getCustPhone1()).isEqualTo("2125551234");
        assertThat(dto.getCustPhone2()).isEqualTo("2125555678");
        assertThat(dto.getCustSsn()).isEqualTo("123456789");
        assertThat(dto.getCustDob()).isEqualTo(LocalDate.of(1985, 3, 22));
        // custFicoScore is mapped from Short → String in the service
        assertThat(dto.getCustFicoScore()).isEqualTo("750");
        assertThat(dto.getCustGovtId()).isEqualTo("DL12345678");
        assertThat(dto.getCustEftAcct()).isEqualTo("EFT00001");
        assertThat(dto.getCustProfileFlag()).isEqualTo("Y");
    }

    @Test
    @DisplayName("COACTVWC: monetary fields currentBalance and creditLimit are BigDecimal")
    void testGetAccountView_success_monetaryFieldsAreBigDecimal() {
        stubAllRepositoriesForSuccess();

        AccountDto dto = accountViewService.getAccountView(VALID_ACCT_ID);

        // Verify the runtime types are BigDecimal — no float/double substitution
        assertThat(dto.getAcctCurrBal()).isInstanceOf(BigDecimal.class);
        assertThat(dto.getAcctCreditLimit()).isInstanceOf(BigDecimal.class);
        assertThat(dto.getAcctCashCreditLimit()).isInstanceOf(BigDecimal.class);
        assertThat(dto.getAcctCurrCycCredit()).isInstanceOf(BigDecimal.class);
        assertThat(dto.getAcctCurrCycDebit()).isInstanceOf(BigDecimal.class);
    }

    // =========================================================================
    // BigDecimal Precision Tests (AAP §0.8.2 — compareTo, never equals)
    // =========================================================================

    @Test
    @DisplayName("AAP §0.8.2: balance assertion uses BigDecimal.compareTo() — never equals()")
    void testGetAccountView_balance_compareTo() {
        stubAllRepositoriesForSuccess();

        AccountDto dto = accountViewService.getAccountView(VALID_ACCT_ID);

        // Must use compareTo() == 0, NOT equals() which is scale-sensitive
        // e.g. new BigDecimal("1500.75").equals(new BigDecimal("1500.750")) returns false
        BigDecimal expectedBalance = new BigDecimal("1500.75");
        assertThat(dto.getAcctCurrBal().compareTo(expectedBalance)).isZero();

        // Also verify with different scale representation
        BigDecimal sameValueDifferentScale = new BigDecimal("1500.7500");
        assertThat(dto.getAcctCurrBal().compareTo(sameValueDifferentScale)).isZero();
    }

    @Test
    @DisplayName("AAP §0.8.2: creditLimit assertion uses BigDecimal.compareTo() — never equals()")
    void testGetAccountView_creditLimit_compareTo() {
        stubAllRepositoriesForSuccess();

        AccountDto dto = accountViewService.getAccountView(VALID_ACCT_ID);

        // Must use compareTo() == 0 for all financial assertions
        BigDecimal expectedCreditLimit = new BigDecimal("5000.00");
        assertThat(dto.getAcctCreditLimit().compareTo(expectedCreditLimit)).isZero();

        // Verify with an equivalent value at a different scale
        BigDecimal sameValueDifferentScale = BigDecimal.valueOf(5000);
        assertThat(dto.getAcctCreditLimit().compareTo(sameValueDifferentScale)).isZero();
    }

    // =========================================================================
    // Call Order Verification (CrossRef → Account → Customer)
    // =========================================================================

    @Test
    @DisplayName("COACTVWC: verify 3-step read chain order — xref first, then account, then customer")
    void testGetAccountView_callOrder() {
        stubAllRepositoriesForSuccess();

        accountViewService.getAccountView(VALID_ACCT_ID);

        // Verify exact 3-step invocation sequence preserving COACTVWC.cbl paragraph order:
        // 9200-GETCARDXREF-BYACCT → 9300-GETACCTDATA-BYACCT → 9400-GETCUSTDATA-BYCUST
        InOrder inOrder = inOrder(cardCrossReferenceRepository, accountRepository, customerRepository);
        inOrder.verify(cardCrossReferenceRepository, times(1)).findByXrefAcctId(VALID_ACCT_ID);
        inOrder.verify(accountRepository, times(1)).findById(VALID_ACCT_ID);
        inOrder.verify(customerRepository, times(1)).findById(CUST_ID);
        inOrder.verifyNoMoreInteractions();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Stubs all three repositories for a successful 3-step read chain:
     * <ol>
     *   <li>CardCrossReferenceRepository.findByXrefAcctId → populated list</li>
     *   <li>AccountRepository.findById → present account</li>
     *   <li>CustomerRepository.findById → present customer</li>
     * </ol>
     */
    private void stubAllRepositoriesForSuccess() {
        when(cardCrossReferenceRepository.findByXrefAcctId(VALID_ACCT_ID))
                .thenReturn(List.of(crossReference));
        when(accountRepository.findById(VALID_ACCT_ID))
                .thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID))
                .thenReturn(Optional.of(customer));
    }
}
