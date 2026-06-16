package com.carddemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.AccountUpdateRequest;
import com.carddemo.model.dto.AccountUpdateResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.account.AccountUpdateService;
import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.ValidationLookupService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * JVM-only Mockito unit tests for {@link AccountUpdateService}, the Java translation of the
 * COBOL online account-update program COACTUPC (source commit {@code 27d6c6f}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService - COACTUPC dual ACCTDAT+CUSTDAT update, optimistic locking, first-error-wins edits")
class AccountUpdateServiceTest {

    private static final String ACCT_ID_STR = "12345678901";
    private static final Long ACCT_ID = 12345678901L;
    private static final Long CUST_ID = 123456789L;

    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("2500.00");
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56");
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("300.00");
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("150.00");

    private static final String OPEN_DATE = "2020-01-15";
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    @Mock private AccountRepository accountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CardCrossReferenceRepository cardCrossReferenceRepository;
    @Mock private DateValidationService dateValidationService;
    @Mock private ValidationLookupService validationLookupService;

    @InjectMocks private AccountUpdateService service;

    // ----- fixtures -----

    private AccountUpdateRequest validRequest() {
        return validRequest(ACCT_ID_STR);
    }

    private AccountUpdateRequest validRequest(String accountId) {
        // version 0L matches the anAccount() fixture, so the optimistic-concurrency
        // guard is transparent for these tests; the stale-version case is exercised
        // separately by updateAccount_throwsConcurrency_whenClientVersionStale().
        return validRequest(accountId, 0L);
    }

    private AccountUpdateRequest validRequest(String accountId, Long version) {
        return new AccountUpdateRequest(
                version,
                accountId,
                "Y",
                "2020", "01", "15",
                CREDIT_LIMIT,
                "2025", "12", "31",
                CASH_CREDIT_LIMIT,
                "2023", "06", "30",
                CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT,
                "DEFAULT",
                CURRENT_CYCLE_DEBIT,
                "123456789",
                "123", "45", "6789",
                "1990", "06", "15",
                "750",
                "John", "Quincy", "Public",
                "123 Main Street", "CA", "Suite 100",
                "90210", "Beverly Hills", "USA",
                "415", "555", "1234",
                "GOVID1234567890",
                "628", "555", "5678",
                "1234567890",
                "Y");
    }

    private Account anAccount() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setVersion(0L);
        return account;
    }

    private Customer aCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        return customer;
    }

    private CardCrossReference aXref() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefAcctId(ACCT_ID);
        xref.setXrefCustId(CUST_ID);
        return xref;
    }

    private static DateValidationService.DateValidationResult acceptable() {
        return new DateValidationService.DateValidationResult(
                true, 0, 0, "Date is valid", "", DATE_FORMAT);
    }

    /** Stubs the cross-reference, account, and customer reads that precede field editing. */
    private void stubReadChain() {
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(aXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(anAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(aCustomer()));
    }

    /** Stubs the date and lookup shared services so every input edit passes. */
    private void stubAllEditsPass() {
        when(dateValidationService.validateDate(any(), any())).thenReturn(acceptable());
        when(validationLookupService.isValidStateCode("CA")).thenReturn(true);
        when(validationLookupService.isValidStateZip("CA", "90210")).thenReturn(true);
        when(validationLookupService.isValidAreaCode(anyString())).thenReturn(true);
    }

    // ----- tests -----

    @Test
    @DisplayName("both ACCTDAT and CUSTDAT rewrites succeed -> success response with scale-2 money echoed")
    void updateAccount_returnsSuccess_whenAllValidAndBothSaved() {
        stubReadChain();
        stubAllEditsPass();
        when(accountRepository.saveAndFlush(any(Account.class))).thenReturn(anAccount());
        when(customerRepository.saveAndFlush(any(Customer.class))).thenReturn(aCustomer());

        AccountUpdateResponse response = service.updateAccount(validRequest());

        assertThat(response).isNotNull();
        assertThat(response.infoMessage()).isEqualTo("Changes committed to database");
        assertThat(response.errorMessage()).isNull();

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

        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(customerRepository).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("optimistic-lock failure on the ACCTDAT rewrite -> ConcurrencyException(entity=Account) carrying the cause")
    void updateAccount_throwsConcurrency_whenOptimisticLockOnSave() {
        stubReadChain();
        stubAllEditsPass();
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));

        Throwable thrown = catchThrowable(() -> service.updateAccount(validRequest()));

        assertThat(thrown)
                .isInstanceOf(ConcurrencyException.class)
                .hasMessageContaining("Record changed by some one else");
        ConcurrencyException ex = (ConcurrencyException) thrown;
        assertThat(ex.getEntity()).isEqualTo("Account");
        assertThat(ex.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("stale client version (echoed != persisted) -> ConcurrencyException(entity=Account), no save")
    void updateAccount_throwsConcurrency_whenClientVersionStale() {
        // All three reads (xref, account, customer) succeed; the persisted account is
        // version 0L while the client echoes a stale 99L, modelling an edit submitted
        // against an out-of-date read. The before/after-image guard must reject before
        // any ACCTDAT/CUSTDAT mutation (COACTUPC parity).
        stubReadChain();

        Throwable thrown = catchThrowable(() -> service.updateAccount(validRequest(ACCT_ID_STR, 99L)));

        assertThat(thrown)
                .isInstanceOf(ConcurrencyException.class)
                .hasMessageContaining("Record changed by some one else");
        ConcurrencyException ex = (ConcurrencyException) thrown;
        assertThat(ex.getEntity()).isEqualTo("Account");
        assertThat(ex.getCause()).isNull();

        verify(accountRepository, never()).saveAndFlush(any(Account.class));
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
    }

    /**
     * A failure on the second (CUSTDAT) rewrite propagates and yields no partial-success
     * response; the real {@code @Transactional} rollback is asserted in the integration/e2e
     * tiers, since a unit test cannot drive a transaction manager.
     */
    @Test
    @DisplayName("second (CUSTDAT) rewrite fails -> exception propagates, no partial-success response")
    void updateAccount_propagatesException_whenSecondUpdateFails_noPartialSuccess() {
        stubReadChain();
        stubAllEditsPass();
        when(customerRepository.saveAndFlush(any(Customer.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Customer.class, CUST_ID));

        Throwable thrown = catchThrowable(() -> service.updateAccount(validRequest()));

        assertThat(thrown).isInstanceOf(CardDemoException.class);
        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(customerRepository).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("first failing edit wins -> ValidationException(field=openDate) when the open date is invalid")
    void updateAccount_throwsValidationException_whenFieldInvalid() {
        stubReadChain();
        when(dateValidationService.validateDate(OPEN_DATE, DATE_FORMAT))
                .thenReturn(new DateValidationService.DateValidationResult(
                        false, 3, 2508, "Datevalue error", OPEN_DATE, DATE_FORMAT));

        Throwable thrown = catchThrowable(() -> service.updateAccount(validRequest()));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Open Date is not valid");
        ValidationException ex = (ValidationException) thrown;
        assertThat(ex.getField()).isEqualTo("openDate");
    }

    @Test
    @DisplayName("account master missing -> RecordNotFoundException")
    void updateAccount_throwsRecordNotFound_whenAccountMissing() {
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(aXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Account not found");
    }

    @Test
    @DisplayName("cross-reference missing -> RecordNotFoundException")
    void updateAccount_throwsRecordNotFound_whenCrossReferenceMissing() {
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateAccount(validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Account cross-reference not found");
    }

    @Test
    @DisplayName("non-numeric account filter -> ValidationException(field=accountId)")
    void updateAccount_throwsValidationException_whenAccountIdNonNumeric() {
        Throwable thrown = catchThrowable(() -> service.updateAccount(validRequest("ABC")));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        ValidationException ex = (ValidationException) thrown;
        assertThat(ex.getField()).isEqualTo("accountId");
    }
}
