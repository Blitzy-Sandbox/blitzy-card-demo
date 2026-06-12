package com.cardemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.ValidationLookupService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests for {@link AccountUpdateService} — the {@code COACTUPC} dual-record update, the highest-risk
 * concurrency/transaction site in the estate and a CP3-required test target. Covers: the
 * {@code 1200-EDIT-MAP-INPUTS} validation accumulation (multiple field errors raised together), the
 * client-version stale-form check (409 before any write — the Group F fix), the server-side JPA
 * {@code @Version} optimistic-lock catch (409), the dual Account+Customer write, and the
 * {@code SYNCPOINT ROLLBACK} → {@code @Transactional(rollbackFor=Exception.class)} mapping.
 */
class AccountUpdateServiceTest {

    private static final Long ACCT_ID = 1L;
    private static final Long CUST_ID = 100L;
    private static final long ENTITY_VERSION = 5L;

    private AccountRepository accountRepository;
    private CustomerRepository customerRepository;
    private DateValidationService dateValidationService;
    private ValidationLookupService validationLookupService;
    private AccountUpdateService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        customerRepository = mock(CustomerRepository.class);
        dateValidationService = mock(DateValidationService.class);
        validationLookupService = mock(ValidationLookupService.class);
        // Permissive date + lookup validators (lenient: not every test reaches the field cascade).
        lenient().when(dateValidationService.validateCcyymmdd(anyString(), anyString()))
                .thenReturn(DateValidationResult.ofValid());
        lenient().when(dateValidationService.validateDateOfBirth(anyString(), anyString()))
                .thenReturn(DateValidationResult.ofValid());
        lenient().when(validationLookupService.isValidStateCode(anyString())).thenReturn(true);
        lenient().when(validationLookupService.isValidStateZip(anyString(), anyString())).thenReturn(true);
        lenient().when(validationLookupService.isValidAreaCode(anyString())).thenReturn(true);
        service = new AccountUpdateService(accountRepository, customerRepository,
                dateValidationService, validationLookupService);
    }

    private Account accountWithVersion(long version) {
        Account a = new Account();
        a.setAcctId(ACCT_ID);
        a.setVersion(version);
        // Editable fields null -> hasChanges() returns true on the first compare.
        return a;
    }

    private void stubFinds() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(accountWithVersion(ENTITY_VERSION)));
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
    }

    /** A fully-valid AccountDto that passes every 1200-EDIT-MAP-INPUTS field edit. */
    private AccountDto validRequest() {
        AccountDto dto = new AccountDto();
        dto.setAccountId("1");
        dto.setCustomerId("100");
        dto.setAccountStatus("Y");
        dto.setOpenDate(LocalDate.of(2020, 1, 1));
        dto.setExpirationDate(LocalDate.of(2030, 1, 1));
        dto.setReissueDate(LocalDate.of(2024, 1, 1));
        dto.setDateOfBirth(LocalDate.of(1990, 1, 1));
        dto.setCreditLimit(new BigDecimal("5000.00"));
        dto.setCashCreditLimit(new BigDecimal("1000.00"));
        dto.setCurrentBalance(new BigDecimal("250.00"));
        dto.setCurrentCycleCredit(new BigDecimal("10.00"));
        dto.setCurrentCycleDebit(new BigDecimal("20.00"));
        dto.setSsn("123-45-6789");
        dto.setFicoScore("700");
        dto.setFirstName("John");
        dto.setMiddleName("");
        dto.setLastName("Doe");
        dto.setAddressLine1("123 Main St");
        dto.setState("TX");
        dto.setZipCode("75001");
        dto.setCity("Dallas");
        dto.setCountryCode("USA");
        dto.setEftAccountId("1234567890");
        dto.setPrimaryCardHolderIndicator("Y");
        return dto;
    }

    @Test
    @DisplayName("validation accumulation: multiple invalid fields are raised together")
    void validationAccumulation() {
        stubFinds();
        AccountDto request = validRequest();
        request.setAccountStatus("X");   // 1220-EDIT-YESNO failure
        request.setFicoScore("999");     // 1275-EDIT-FICO-SCORE range failure

        assertThatThrownBy(() -> service.updateAccount("1", request))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getValidationErrors()).hasSizeGreaterThanOrEqualTo(2);
                    assertThat(ve.getValidationErrors())
                            .anyMatch(m -> m.contains("Account Status"))
                            .anyMatch(m -> m.contains("FICO Score"));
                });
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("stale client version (!= stored) -> 409 ConcurrentModificationException, no write")
    void staleClientVersionRejected() {
        stubFinds();
        AccountDto request = validRequest();
        request.setVersion(ENTITY_VERSION - 1); // stale

        assertThatThrownBy(() -> service.updateAccount("1", request))
                .isInstanceOf(ConcurrentModificationException.class);
        verify(accountRepository, never()).saveAndFlush(any());
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("server-side @Version conflict on save -> 409 ConcurrentModificationException")
    void jpaOptimisticLockMapsToConflict() {
        stubFinds();
        when(accountRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));
        AccountDto request = validRequest();
        request.setVersion(ENTITY_VERSION); // matches -> passes the client check, fails at save

        assertThatThrownBy(() -> service.updateAccount("1", request))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    @DisplayName("success: both Account and Customer are written (dual REWRITE in one transaction)")
    void dualWriteOnSuccess() {
        stubFinds();
        when(accountRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(customerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        AccountDto request = validRequest();
        request.setVersion(ENTITY_VERSION);

        AccountDto result = service.updateAccount("1", request);

        assertThat(result).isNotNull();
        verify(accountRepository).saveAndFlush(any());
        verify(customerRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("null request body -> ValidationException")
    void nullRequestRejected() {
        assertThatThrownBy(() -> service.updateAccount("1", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("updateAccount is annotated @Transactional(rollbackFor = Exception.class) (SYNCPOINT ROLLBACK)")
    void rollbackIntentPreserved() throws Exception {
        Transactional ann = AccountUpdateService.class
                .getMethod("updateAccount", String.class, AccountDto.class)
                .getAnnotation(Transactional.class);
        assertThat(ann).isNotNull();
        assertThat(ann.rollbackFor()).contains(Exception.class);
    }

    @Test
    @DisplayName("null client version (opt-in) proceeds to write -> @Version remains the safety net")
    void nullClientVersionProceeds() {
        stubFinds();
        when(accountRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(customerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        AccountDto request = validRequest();
        request.setVersion(null);

        assertThatCode(() -> service.updateAccount("1", request)).doesNotThrowAnyException();
        verify(accountRepository).saveAndFlush(any());
    }
}
