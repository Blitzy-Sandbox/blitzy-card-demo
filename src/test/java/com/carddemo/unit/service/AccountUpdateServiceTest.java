/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.dto.AccountDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.ValidationLookupService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link AccountUpdateService}, the
 * transactional dual-record account/customer update (the Java realization of the
 * COBOL {@code COACTUPC} update program @ {@code 27d6c6f}, the largest program in
 * the corpus).
 *
 * <p>The tests lock the COBOL flow: account-id validation, the no-input guard,
 * the cross-reference / account / customer load short-circuit, the
 * {@code 1200-EDIT-MAP-INPUTS} field-edit cascade (collaborators
 * {@link DateValidationService} and {@link ValidationLookupService} are mocked),
 * the no-change guard, the atomic dual-record persistence reproducing the CICS
 * {@code SYNCPOINT}, and the translation of an optimistic-locking conflict (the
 * {@code 9700-CHECK-CHANGE-IN-REC} replacement) into a
 * {@link ConcurrentUpdateException}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService - COACTUPC dual-record account update @ 27d6c6f")
class AccountUpdateServiceTest {

    private static final Long ACCOUNT_ID = 123L;
    private static final Long CUSTOMER_ID = 456L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private ValidationLookupService validationLookupService;

    @InjectMocks
    private AccountUpdateService service;

    /**
     * A fully valid update request whose values pass every field edit. The
     * derived (assembled) values are: open {@code 2020-01-15}, expiry
     * {@code 2025-12-31}, reissue {@code 2021-06-30}, dob {@code 1990-05-20},
     * ssn {@code 123456789}, phone1 {@code (415)555-1234},
     * phone2 {@code (416)556-5678}.
     */
    private AccountDto.UpdateRequest validRequest() {
        return new AccountDto.UpdateRequest(
                "00000000123", "Y",
                "2020", "01", "15",
                "2025", "12", "31",
                "2021", "06", "30",
                "1990", "05", "20",
                new BigDecimal("1000.00"), new BigDecimal("500.00"),
                new BigDecimal("250.00"), new BigDecimal("100.00"),
                "GRP1", new BigDecimal("50.00"),
                "456",
                "123", "45", "6789",
                "700",
                "John", "Q", "Public",
                "1 Main St", "CA", "Apt 2", "12345", "Springfield", "USA",
                "415", "555", "1234",
                "416", "556", "5678",
                "GID123", "9876543210", "Y");
    }

    /** An all-blank request that triggers the no-input guard. */
    private AccountDto.UpdateRequest blankRequest() {
        return new AccountDto.UpdateRequest(
                "00000000123", null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null, null,
                null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null);
    }

    /** A request whose only populated field is an invalid account-status flag. */
    private AccountDto.UpdateRequest invalidStatusRequest() {
        return new AccountDto.UpdateRequest(
                "00000000123", "X",
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null, null,
                null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null);
    }

    private CardXref xref() {
        CardXref xref = new CardXref();
        xref.setXrefAcctId(ACCOUNT_ID);
        xref.setXrefCardNum("1234567890123456");
        xref.setXrefCustId(CUSTOMER_ID);
        return xref;
    }

    /** A freshly-loaded account (mostly null) so the valid request always changes it. */
    private Account freshAccount() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        return account;
    }

    /** A freshly-loaded customer (mostly null) so the valid request always changes it. */
    private Customer freshCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        return customer;
    }

    /** An account whose stored values already equal the validRequest()-derived values. */
    private Account matchingAccount() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setActiveStatus("Y");
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setCashCreditLimit(new BigDecimal("500.00"));
        account.setCurrBal(new BigDecimal("250.00"));
        account.setCurrCycCredit(new BigDecimal("100.00"));
        account.setCurrCycDebit(new BigDecimal("50.00"));
        account.setOpenDate("2020-01-15");
        account.setExpirationDate("2025-12-31");
        account.setReissueDate("2021-06-30");
        account.setGroupId("GRP1");
        return account;
    }

    /** A customer whose stored values already equal the validRequest()-derived values. */
    private Customer matchingCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setFirstName("John");
        customer.setMiddleName("Q");
        customer.setLastName("Public");
        customer.setAddrLine1("1 Main St");
        customer.setAddrLine2("Apt 2");
        customer.setAddrLine3("Springfield");
        customer.setAddrStateCd("CA");
        customer.setAddrCountryCd("USA");
        customer.setAddrZip("12345");
        customer.setPhoneNum1("(415)555-1234");
        customer.setPhoneNum2("(416)556-5678");
        customer.setSsn("123456789");
        customer.setGovtIssuedId("GID123");
        customer.setDob("1990-05-20");
        customer.setEftAccountId("9876543210");
        customer.setPriCardHolderInd("Y");
        customer.setFicoCreditScore(700);
        return customer;
    }

    private void stubLookupsValid() {
        when(validationLookupService.isValidStateCode(anyString())).thenReturn(true);
        when(validationLookupService.isValidAreaCode(anyString())).thenReturn(true);
        when(validationLookupService.isValidStateZipCombo(anyString(), anyString())).thenReturn(true);
    }

    // -----------------------------------------------------------------
    // Account-id and no-input guards
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateAccount: a null account id raises 'Account number not provided'")
    void updateAccountNullIdRaisesNotProvided() {
        assertThatThrownBy(() -> service.updateAccount(null, validRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number not provided");
    }

    @Test
    @DisplayName("updateAccount: an out-of-range account id raises the account-number message")
    void updateAccountOutOfRangeIdRaisesNotValid() {
        assertThatThrownBy(() -> service.updateAccount(100_000_000_000L, validRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number must be a non zero 11 digit number");
    }

    @Test
    @DisplayName("updateAccount: an all-blank request raises 'No input received'")
    void updateAccountNoInputRaises() {
        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, blankRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
    }

    // -----------------------------------------------------------------
    // Load short-circuit (xref -> account -> customer)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateAccount: an empty cross-reference raises the xref not-found message")
    void updateAccountMissingXrefRaises() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account card xref file");
    }

    @Test
    @DisplayName("updateAccount: a missing account master record raises the account not-found message")
    void updateAccountMissingAccountRaises() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account master file");
    }

    @Test
    @DisplayName("updateAccount: a missing customer master record raises the customer not-found message")
    void updateAccountMissingCustomerRaises() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find associated customer in master file");
    }

    // -----------------------------------------------------------------
    // Field-edit cascade
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateAccount: an invalid account-status flag fails the field-edit cascade")
    void updateAccountInvalidStatusFailsValidation() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(freshCustomer()));

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, invalidStatusRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account Active Status must be Y or N");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // -----------------------------------------------------------------
    // No-change guard
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateAccount: a valid request matching stored values raises the no-change guard")
    void updateAccountNoChangeRaises() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(matchingAccount()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(matchingCustomer()));
        stubLookupsValid();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No change detected with respect to values fetched.");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // -----------------------------------------------------------------
    // Happy path: atomic dual-record persistence
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateAccount: a valid change persists BOTH the account and the customer (dual-record SYNCPOINT)")
    void updateAccountHappyPathPersistsBothRecords() {
        Account account = freshAccount();
        Customer customer = freshCustomer();
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        stubLookupsValid();

        AccountDto.ViewResponse response = service.updateAccount(ACCOUNT_ID, validRequest());

        assertThat(response).isNotNull();
        assertThat(response.accountId()).isEqualTo("123");
        assertThat(response.customerId()).isEqualTo("456");
        assertThat(response.accountStatus()).isEqualTo("Y");
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.lastName()).isEqualTo("Public");
        verify(accountRepository).save(account);
        verify(customerRepository).save(customer);
    }

    // -----------------------------------------------------------------
    // Optimistic-locking conflict
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateAccount: an optimistic-locking conflict is translated to ConcurrentUpdateException")
    void updateAccountOptimisticLockMapsToConcurrentUpdate() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(freshCustomer()));
        stubLookupsValid();
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new OptimisticLockingFailureException("version conflict"));

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ConcurrentUpdateException.class);
    }
}
