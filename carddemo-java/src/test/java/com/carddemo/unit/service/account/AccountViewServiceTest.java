package com.carddemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.dto.AccountViewResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.account.AccountViewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AccountViewService} (COBOL COACTVWC, source commit 27d6c6f). Pure
 * Mockito/JVM test; rationale in DECISION_LOG.md.
 */
@ExtendWith(MockitoExtension.class)
class AccountViewServiceTest {

  private static final Long ACCT_ID = 12345678901L;
  private static final Long CUST_ID = 123456789L;

  private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
  private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1000.00");
  private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56");
  private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("250.00");
  private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("75.25");

  private static final Long SSN_RAW = 987654321L;
  private static final String SSN_FORMATTED = "987-65-4321";
  private static final String CITY = "Springfield";

  @Mock private AccountRepository accountRepository;
  @Mock private CustomerRepository customerRepository;
  @Mock private CardCrossReferenceRepository cardCrossReferenceRepository;

  @InjectMocks private AccountViewService service;

  private static Account anAccount() {
    Account account = new Account();
    account.setAcctId(ACCT_ID);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(CURRENT_BALANCE);
    account.setAcctCreditLimit(CREDIT_LIMIT);
    account.setAcctCashCreditLimit(CASH_CREDIT_LIMIT);
    account.setAcctOpenDate("2020-01-15");
    account.setAcctExpiraionDate("2027-01-15");
    account.setAcctReissueDate("2024-01-15");
    account.setAcctCurrCycCredit(CURRENT_CYCLE_CREDIT);
    account.setAcctCurrCycDebit(CURRENT_CYCLE_DEBIT);
    account.setAcctAddrZip("62704");
    account.setAcctGroupId("DEFAULT");
    return account;
  }

  private static Customer aCustomer() {
    Customer customer = new Customer();
    customer.setCustId(CUST_ID);
    customer.setCustFirstName("JANE");
    customer.setCustMiddleName("Q");
    customer.setCustLastName("DOE");
    customer.setCustAddrLine1("123 MAIN ST");
    customer.setCustAddrLine2("APT 4B");
    customer.setCustAddrLine3(CITY);
    customer.setCustAddrStateCd("IL");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("62704");
    customer.setCustPhoneNum1("(217)555-0100");
    customer.setCustPhoneNum2("(217)555-0199");
    customer.setCustSsn(SSN_RAW);
    customer.setCustGovtIssuedId("IL-DL-0099");
    customer.setCustDobYyyyMmDd("1985-06-30");
    customer.setCustEftAccountId("EFT0001234");
    customer.setCustPriCardHolderInd("Y");
    customer.setCustFicoCreditScore(750);
    return customer;
  }

  private static CardCrossReference aXref() {
    CardCrossReference xref = new CardCrossReference();
    xref.setXrefCardNum("1234567890123456");
    xref.setXrefCustId(CUST_ID);
    xref.setXrefAcctId(ACCT_ID);
    return xref;
  }

  @Test
  @DisplayName("happy path: xref -> account -> customer join returns a fully populated view")
  void getAccountView_returnsPopulatedResponse_whenAllRecordsExist() {
    when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(aXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(anAccount()));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(aCustomer()));

    AccountViewResponse response = service.getAccountView(ACCT_ID);

    assertThat(response).isNotNull();

    assertThat(response.creditLimit()).isEqualByComparingTo(CREDIT_LIMIT);
    assertThat(response.creditLimit().scale()).isEqualTo(2);
    assertThat(response.cashCreditLimit()).isEqualByComparingTo(CASH_CREDIT_LIMIT);
    assertThat(response.cashCreditLimit().scale()).isEqualTo(2);
    assertThat(response.currentBalance()).isEqualByComparingTo(CURRENT_BALANCE);
    assertThat(response.currentBalance().scale()).isEqualTo(2);
    assertThat(response.currentCycleCredit()).isEqualByComparingTo(CURRENT_CYCLE_CREDIT);
    assertThat(response.currentCycleCredit().scale()).isEqualTo(2);
    assertThat(response.currentCycleDebit()).isEqualByComparingTo(CURRENT_CYCLE_DEBIT);
    assertThat(response.currentCycleDebit().scale()).isEqualTo(2);

    assertThat(response.accountId()).isEqualTo(String.valueOf(ACCT_ID));
    assertThat(response.customerId()).isEqualTo(String.valueOf(CUST_ID));
    assertThat(response.ssn()).isEqualTo(SSN_FORMATTED);
    assertThat(response.city()).isEqualTo(CITY);
    assertThat(response.infoMessage()).isNull();
    assertThat(response.errorMessage()).isNull();
  }

  @Test
  @DisplayName("guard: null or non-positive account id is rejected before any repository read")
  void getAccountView_throwsRecordNotFound_whenAccountIdIsNullOrNonPositive() {
    assertThatThrownBy(() -> service.getAccountView(null))
        .isInstanceOf(RecordNotFoundException.class);
    assertThatThrownBy(() -> service.getAccountView(0L))
        .isInstanceOf(RecordNotFoundException.class);
    assertThatThrownBy(() -> service.getAccountView(-5L))
        .isInstanceOf(RecordNotFoundException.class);

    Throwable thrown = catchThrowable(() -> service.getAccountView(0L));
    assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
    assertThat(((RecordNotFoundException) thrown).getFileStatus()).isEqualTo("23");
  }

  @Test
  @DisplayName("xref empty: no cross-reference for the account id throws not-found")
  void getAccountView_throwsRecordNotFound_whenCrossReferenceListIsEmpty() {
    when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.getAccountView(ACCT_ID))
        .isInstanceOf(RecordNotFoundException.class)
        .hasMessageContaining("cross-reference");
  }

  @Test
  @DisplayName("account missing: cross-reference resolves but account master read is empty")
  void getAccountView_throwsRecordNotFound_whenAccountMissing() {
    when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(aXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getAccountView(ACCT_ID))
        .isInstanceOf(RecordNotFoundException.class)
        .hasMessageContaining("Account not found");
  }

  @Test
  @DisplayName("customer missing: account resolves but customer master read is empty")
  void getAccountView_throwsRecordNotFound_whenCustomerMissing() {
    when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(aXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(anAccount()));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getAccountView(ACCT_ID))
        .isInstanceOf(RecordNotFoundException.class)
        .hasMessageContaining("Customer");
  }
}
