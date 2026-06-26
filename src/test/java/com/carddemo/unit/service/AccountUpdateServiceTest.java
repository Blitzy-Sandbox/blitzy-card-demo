/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link AccountUpdateService}, the Java
 * realization of the legacy CICS program {@code COACTUPC} (transaction
 * {@code CAUP}, {@code PUT /api/accounts/{id}}) at source commit {@code 27d6c6f}.
 *
 * <p>{@code COACTUPC} is the largest program in the CardDemo corpus (4,236&nbsp;LOC,
 * AAP&nbsp;&sect;0.4.1.1, &sect;0.6.5). It performs a <strong>dual-record
 * (ACCOUNT&nbsp;+&nbsp;CUSTOMER) atomic update</strong> under
 * {@code @Transactional(rollbackFor = Exception.class)} (CICS {@code SYNCPOINT}
 * parity) and reproduces the COBOL {@code 9300/9700-CHECK-CHANGE-IN-REC}
 * re-read-and-compare guard with JPA {@code @Version} optimistic locking
 * (AAP&nbsp;&sect;0.6.3). The byte-exact validation messages exercised here are
 * Gate&nbsp;1 / Gate&nbsp;4 critical and are asserted verbatim against
 * {@code app/cbl/COACTUPC.cbl}.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong> {@code @SpringBootTest},
 * {@code MockMvc}, {@code spring-security-test}, or Testcontainers, and touches no
 * database, AWS, or network resource. The five collaborators
 * ({@link AccountRepository}, {@link CustomerRepository}, {@link CardXrefRepository},
 * {@link DateValidationService}, {@link ValidationLookupService}) are Mockito mocks
 * injected through the service's constructor by {@link MockitoExtension}, which runs
 * with strict stubs so that every {@code when(...)} declaration is exercised.</p>
 *
 * <p>The service resolves the customer through the cross-reference
 * (xref&nbsp;&rarr;&nbsp;account&nbsp;&rarr;&nbsp;customer), runs the ordered
 * {@code 1200-EDIT-MAP-INPUTS} field cascade collecting per-field failures into an
 * insertion-ordered map (so the surfaced message is the <em>first</em> failing
 * field), detects no-input and no-change conditions, then rewrites and saves both
 * records inside one transaction. Monetary amounts are {@link BigDecimal} values;
 * every monetary assertion uses {@code compareTo} semantics
 * ({@code isEqualByComparingTo}) and never {@code double}/{@code float}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService - COACTUPC (CAUP) dual-record atomic update @ 27d6c6f")
class AccountUpdateServiceTest {

    /** Path-level account identifier used across the suite (a valid 11-digit key). */
    private static final Long ACCOUNT_ID = 1L;

    /** Owning customer identifier carried by the cross-reference record. */
    private static final Long CUSTOMER_ID = 100L;

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

    // =================================================================
    // Phase 1 - successful dual-record update (the headline parity test)
    // =================================================================

    @Test
    @DisplayName("updateAccount: valid change saves BOTH account and customer atomically")
    void updateAccountValidChangeSavesBothRecordsAtomically() {
        // Arrange: existing records that differ from the request in exactly one
        // account field (credit limit) and one customer field (last name).
        Account account = matchingAccount();
        account.setCreditLimit(new BigDecimal("1000.00"));
        Customer customer = matchingCustomer();
        customer.setLastName("Olsen");

        stubLoads(account, customer);
        stubValidLookups();
        when(accountRepository.save(any(Account.class))).thenReturn(account);
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);

        // Act
        AccountDto.ViewResponse response = service.updateAccount(ACCOUNT_ID, validRequest().build());

        // Assert: the dual save is atomic - both repositories are written exactly once.
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(accountRepository).save(accountCaptor.capture());
        verify(customerRepository).save(customerCaptor.capture());

        // Mutated values were persisted (money compared via compareTo, never equals).
        assertThat(accountCaptor.getValue().getCreditLimit())
                .isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(customerCaptor.getValue().getLastName()).isEqualTo("Public");

        // The refreshed view reflects the updates.
        assertThat(response.accountId()).isEqualTo("1");
        assertThat(response.customerId()).isEqualTo("100");
        assertThat(response.creditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(response.lastName()).isEqualTo("Public");
    }

    // =================================================================
    // Phase 2 - load / not-found failures (xref -> account -> customer)
    // =================================================================

    @Test
    @DisplayName("updateAccount: missing xref -> RecordNotFoundException, no record is saved")
    void updateAccountMissingXrefThrowsAndSavesNothing() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account card xref file");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: missing account master -> RecordNotFoundException, no record is saved")
    void updateAccountMissingAccountThrowsAndSavesNothing() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in account master file");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: missing customer master -> RecordNotFoundException, no record is saved")
    void updateAccountMissingCustomerThrowsAndSavesNothing() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(matchingAccount()));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find associated customer in master file");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // =================================================================
    // Phase 2b - account-identifier edits (1210-EDIT-ACCOUNT)
    // =================================================================

    @Test
    @DisplayName("updateAccount: null account id -> 'Account number not provided', no record is saved")
    void updateAccountNullIdThrowsAndSavesNothing() {
        assertThatThrownBy(() -> service.updateAccount(null, validRequest().build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number not provided");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: zero account id -> 'Account number must be a non zero 11 digit number'")
    void updateAccountZeroIdThrowsAndSavesNothing() {
        assertThatThrownBy(() -> service.updateAccount(0L, validRequest().build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number must be a non zero 11 digit number");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // =================================================================
    // Phase 3 - validation cascade (verbatim messages; NO save occurs)
    // =================================================================

    @Test
    @DisplayName("updateAccount: invalid active status -> 'Account Active Status must be Y or N'")
    void updateAccountInvalidStatusThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().accountStatus("X").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account Active Status must be Y or N");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: blank last name -> 'Last name not provided'")
    void updateAccountBlankLastNameThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().lastName("").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Last name not provided");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: non-alphabetic name -> 'Name can only contain alphabets and spaces'")
    void updateAccountNonAlphaLastNameThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().lastName("Doe123").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Name can only contain alphabets and spaces");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: missing credit limit -> 'Credit Limit must be supplied'")
    void updateAccountMissingCreditLimitThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().creditLimit(null).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Credit Limit must be supplied");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: invalid credit limit (scale > 2) -> 'Credit Limit is not valid'")
    void updateAccountInvalidCreditLimitThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID,
                validRequest().creditLimit(new BigDecimal("100.123")).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Credit Limit is not valid");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: expiry month out of range -> 'Card expiry month must be between 1 and 12'")
    void updateAccountExpiryMonthOutOfRangeThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().expiryMonth("13").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: invalid expiry year (bad century) -> 'Invalid card expiry year'")
    void updateAccountInvalidExpiryYearThrowsValidation() {
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().expiryYear("1850").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid card expiry year");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // =================================================================
    // Phase 4 - no-change / no-input detection
    // =================================================================

    @Test
    @DisplayName("updateAccount: request equals fetched values -> 'No change detected with respect to values fetched.'")
    void updateAccountNoChangeThrowsValidation() {
        // Loaded records mirror exactly what the valid request would set, so neither
        // applyAccountChanges nor applyCustomerChanges reports a change.
        stubLoads(matchingAccount(), matchingCustomer());
        stubValidLookups();

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No change detected with respect to values fetched.");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: empty request -> 'No input received' before any record is read")
    void updateAccountEmptyRequestThrowsNoInput() {
        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, emptyRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: null request -> 'No input received' before any record is read")
    void updateAccountNullRequestThrowsNoInput() {
        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // =================================================================
    // Phase 5 - optimistic-lock concurrency parity (9700-CHECK-CHANGE-IN-REC)
    // =================================================================

    @Test
    @DisplayName("updateAccount: Spring optimistic-lock failure -> ConcurrentUpdateException (default message)")
    void updateAccountSpringOptimisticLockMapsToConcurrentUpdate() {
        Account account = matchingAccount();
        account.setCreditLimit(new BigDecimal("1000.00"));
        stubLoads(account, matchingCustomer());
        stubValidLookups();
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage("Record changed by some one else. Please review");

        // The customer record is never written once the account save fails.
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: JPA optimistic-lock failure -> ConcurrentUpdateException (default message)")
    void updateAccountJpaOptimisticLockMapsToConcurrentUpdate() {
        Account account = matchingAccount();
        account.setCreditLimit(new BigDecimal("1000.00"));
        stubLoads(account, matchingCustomer());
        stubValidLookups();
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new OptimisticLockException("conflict"));

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage("Record changed by some one else. Please review");

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("updateAccount: stale client version (request behind persisted record) -> ConcurrentUpdateException, no record is saved")
    void updateAccountStaleClientVersionThrowsAndSavesNothing() {
        // COBOL 9300/9700-CHECK-CHANGE-IN-REC parity for the CROSS-REQUEST stale-read
        // scenario the QA report flagged as F-05-1 (silent lost update): the caller
        // read the account at version 0 (the token echoed back in request.version())
        // but another transaction has since advanced the persisted record to version
        // 2. The explicit version comparison must reject the stale write with the
        // byte-exact 409 message BEFORE any change is applied or persisted, mirroring
        // the parallel-contention path. The 1200-EDIT-MAP-INPUTS field cascade still
        // runs first (so the loaded records are fully validated), hence the same load
        // and lookup stubs as the no-change test.
        Account account = matchingAccount();
        account.setVersion(2L);
        stubLoads(account, matchingCustomer());
        stubValidLookups();

        // validRequest().build() echoes version 0L - stale relative to the persisted 2L.
        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage("Record changed by some one else. Please review");

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // =================================================================
    // Phase 6 - transactional rollback intent
    // =================================================================

    @Test
    @DisplayName("updateAccount: non-optimistic persistence failure surfaces so @Transactional rolls back")
    void updateAccountUnexpectedPersistenceFailurePropagates() {
        // The service declares @Transactional(rollbackFor = Exception.class); the
        // actual rollback is exercised by the Testcontainers integration suite. Here
        // we assert that a non-optimistic failure from the first save SURFACES
        // unchanged (it is not swallowed or remapped), so the declarative boundary
        // would roll back the dual-record unit of work, and the second record is
        // never written.
        Account account = matchingAccount();
        account.setCreditLimit(new BigDecimal("1000.00"));
        stubLoads(account, matchingCustomer());
        stubValidLookups();
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new IllegalStateException("unexpected persistence failure"));

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected persistence failure");

        verify(customerRepository, never()).save(any(Customer.class));
    }

    // =================================================================
    // Shared stubbing helpers
    // =================================================================

    /**
     * Stubs the xref&nbsp;&rarr;&nbsp;account&nbsp;&rarr;&nbsp;customer load chain so
     * that {@code updateAccount} reaches the field-edit cascade with the supplied
     * records.
     *
     * @param account  the account returned by {@code findById(ACCOUNT_ID)}
     * @param customer the customer returned by {@code findById(CUSTOMER_ID)}
     */
    private void stubLoads(Account account, Customer customer) {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
    }

    /**
     * Stubs the three {@link ValidationLookupService} checks to accept the valid
     * baseline state code, area code and state-ZIP combination so that the
     * field-edit cascade passes.
     */
    private void stubValidLookups() {
        when(validationLookupService.isValidStateCode(anyString())).thenReturn(true);
        when(validationLookupService.isValidAreaCode(anyString())).thenReturn(true);
        when(validationLookupService.isValidStateZipCombo(anyString(), anyString())).thenReturn(true);
    }

    // =================================================================
    // Fixture builders
    // =================================================================

    /** Cross-reference record linking the account to {@link #CUSTOMER_ID}. */
    private static CardXref xref() {
        return new CardXref("1234567890123456", CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Builds an {@link Account} whose persistent fields exactly equal the values the
     * {@link #validRequest() valid baseline request} would apply, so that a no-change
     * update is detected unless a test mutates a field first.
     *
     * @return a fully populated account matching the baseline request
     */
    private static Account matchingAccount() {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setActiveStatus("Y");
        account.setCreditLimit(new BigDecimal("5000.00"));
        account.setCashCreditLimit(new BigDecimal("1000.00"));
        account.setCurrBal(new BigDecimal("250.00"));
        account.setCurrCycCredit(new BigDecimal("100.00"));
        account.setCurrCycDebit(new BigDecimal("75.00"));
        account.setOpenDate("2010-01-15");
        account.setExpirationDate("2025-12-31");
        account.setReissueDate("2020-06-30");
        account.setGroupId("GRP01");
        account.setVersion(0L);
        return account;
    }

    /**
     * Builds a {@link Customer} whose persistent fields exactly equal the values the
     * {@link #validRequest() valid baseline request} would apply (note the COBOL
     * field mapping: request city&nbsp;&rarr;&nbsp;{@code addrLine3}, request
     * addressLine2&nbsp;&rarr;&nbsp;{@code addrLine2}, assembled SSN, phone and dates).
     *
     * @return a fully populated customer matching the baseline request
     */
    private static Customer matchingCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setFirstName("John");
        customer.setMiddleName("Q");
        customer.setLastName("Public");
        customer.setAddrLine1("123 Main St");
        customer.setAddrLine2("Apt 4");
        customer.setAddrLine3("Anytown");
        customer.setAddrStateCd("CA");
        customer.setAddrCountryCd("USA");
        customer.setAddrZip("90001");
        customer.setPhoneNum1("(415)555-1234");
        customer.setPhoneNum2(null);
        customer.setSsn("123456789");
        customer.setGovtIssuedId("GID12345");
        customer.setDob("1980-07-04");
        customer.setEftAccountId("1234567890");
        customer.setPriCardHolderInd("Y");
        customer.setFicoCreditScore(700);
        customer.setVersion(0L);
        return customer;
    }

    /**
     * Returns a builder seeded with a fully valid {@code COACTUP} update payload.
     * Tests override only the field under test through the fluent setters.
     *
     * @return a request builder primed with the valid baseline
     */
    private static RequestBuilder validRequest() {
        return new RequestBuilder();
    }

    /**
     * Builds an "empty" update request: every editable segment is {@code null} so
     * that {@code isNoInput} reports no input. The {@code accountId} and
     * {@code customerId} key segments are populated because the service's no-input
     * check intentionally ignores them.
     *
     * @return an all-blank update request
     */
    private static AccountDto.UpdateRequest emptyRequest() {
        return new AccountDto.UpdateRequest(
                "1", null,                 // accountId, accountStatus
                null, null, null,          // openYear, openMonth, openDay
                null, null, null,          // expiryYear, expiryMonth, expiryDay
                null, null, null,          // reissueYear, reissueMonth, reissueDay
                null, null, null,          // dobYear, dobMonth, dobDay
                null, null, null,          // creditLimit, cashCreditLimit, currentBalance
                null, null, null,          // currentCycleCredit, accountGroupId, currentCycleDebit
                "100",                     // customerId
                null, null, null,          // ssnPart1, ssnPart2, ssnPart3
                null,                      // ficoScore
                null, null, null,          // firstName, middleName, lastName
                null, null, null, null, null, null, // addressLine1, state, addressLine2, zipCode, city, country
                null, null, null,          // phone1Area, phone1Prefix, phone1Line
                null, null, null,          // phone2Area, phone2Prefix, phone2Line
                null, null, null,          // governmentId, eftAccountId, primaryCardHolder
                0L);                       // version (no-input check fires before the version compare)
    }

    /**
     * Mutable builder for {@link AccountDto.UpdateRequest}. All segments default to a
     * valid baseline; only the handful exercised by the tests are overridable so the
     * intent of each test stays obvious.
     */
    private static final class RequestBuilder {

        private String accountStatus = "Y";
        private String expiryYear = "2025";
        private String expiryMonth = "12";
        private BigDecimal creditLimit = new BigDecimal("5000.00");
        private String lastName = "Public";

        private RequestBuilder accountStatus(String value) {
            this.accountStatus = value;
            return this;
        }

        private RequestBuilder expiryYear(String value) {
            this.expiryYear = value;
            return this;
        }

        private RequestBuilder expiryMonth(String value) {
            this.expiryMonth = value;
            return this;
        }

        private RequestBuilder creditLimit(BigDecimal value) {
            this.creditLimit = value;
            return this;
        }

        private RequestBuilder lastName(String value) {
            this.lastName = value;
            return this;
        }

        private AccountDto.UpdateRequest build() {
            return new AccountDto.UpdateRequest(
                    "1", accountStatus,                       // accountId, accountStatus
                    "2010", "01", "15",                       // openYear, openMonth, openDay
                    expiryYear, expiryMonth, "31",            // expiryYear, expiryMonth, expiryDay
                    "2020", "06", "30",                       // reissueYear, reissueMonth, reissueDay
                    "1980", "07", "04",                       // dobYear, dobMonth, dobDay
                    creditLimit, new BigDecimal("1000.00"),   // creditLimit, cashCreditLimit
                    new BigDecimal("250.00"),                 // currentBalance
                    new BigDecimal("100.00"), "GRP01",        // currentCycleCredit, accountGroupId
                    new BigDecimal("75.00"),                  // currentCycleDebit
                    "100",                                    // customerId
                    "123", "45", "6789",                      // ssnPart1, ssnPart2, ssnPart3
                    "700",                                    // ficoScore
                    "John", "Q", lastName,                    // firstName, middleName, lastName
                    "123 Main St", "CA", "Apt 4",             // addressLine1, state, addressLine2
                    "90001", "Anytown", "USA",                // zipCode, city, country
                    "415", "555", "1234",                     // phone1Area, phone1Prefix, phone1Line
                    null, null, null,                         // phone2Area, phone2Prefix, phone2Line
                    "GID12345", "1234567890", "Y",            // governmentId, eftAccountId, primaryCardHolder
                    0L);                                      // version (equals loaded entity -> verifyVersion passes)
        }
    }
}
