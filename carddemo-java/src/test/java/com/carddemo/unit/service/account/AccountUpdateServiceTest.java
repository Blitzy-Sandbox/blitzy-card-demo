package com.carddemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.carddemo.service.shared.DateValidationService.DateValidationResult;
import com.carddemo.service.shared.ValidationLookupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Isolated, JVM-only unit tests for {@link AccountUpdateService}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the online
 * account-maintenance program {@code app/cbl/COACTUPC.cbl} runs the {@code 1200-EDIT-MAP-INPUTS}
 * field-edit cascade (first-error-wins), then atomically rewrites the account and customer masters
 * ({@code 9600-WRITE-PROCESSING}, the system's sole {@code SYNCPOINT ROLLBACK}). The
 * before/after record-image comparison ({@code 9700-CHECK-CHANGE-IN-REC}) is reproduced by the JPA
 * {@code @Version} optimistic lock: the stateless client echoes the {@code version} it last read,
 * and {@code AccountUpdateService} compares it against the persisted account version, rejecting a
 * stale update with a {@link ConcurrencyException} (AAP &sect;0.8.4). These tests pin that contract:
 * a matching version commits the dual save; a stale version is rejected before any write
 * (cross-request lost-update prevention); a flush-time {@link ObjectOptimisticLockingFailureException}
 * is also translated to {@link ConcurrencyException} (the in-transaction race window); and the
 * date-of-birth future-date edit emits the exact COBOL message
 * {@code "Date of Birth:cannot be in the future "}.</p>
 */
@DisplayName("AccountUpdateService - COACTUPC validation, dual-save rollback, @Version concurrency (echoed DTO version)")
@ExtendWith(MockitoExtension.class)
class AccountUpdateServiceTest {

    private static final Long ACCT_ID = 1L;
    private static final Long CUST_ID = 100L;

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
    @Mock
    private EntityManager entityManager;

    private AccountUpdateService service() {
        return new AccountUpdateService(accountRepository, customerRepository,
                cardCrossReferenceRepository, dateValidationService, validationLookupService,
                entityManager);
    }

    /**
     * A fully valid update request whose echoed optimistic-lock {@code version} (0L) matches the
     * persisted account version stubbed by {@link #stubReads()} (also 0L), so the before/after
     * version compare passes and the edit/commit path is exercised. Callers override the few fields
     * they wish to make invalid; tests needing a stale-version conflict use
     * {@link #validRequestWithVersion(long)}.
     */
    private static AccountUpdateRequest req(String accountId, String accountStatus,
            String dobYear, String dobMonth, String dobDay,
            String ssnPart1, String ficoScore, String stateCode) {
        return new AccountUpdateRequest(
                accountId, accountStatus,
                "2000", "01", "01",
                new BigDecimal("1000.00"),
                "2030", "12", "31",
                new BigDecimal("500.00"),
                "2025", "01", "01",
                new BigDecimal("250.00"),
                new BigDecimal("10.00"),
                "GRP1",
                new BigDecimal("5.00"),
                "100",
                ssnPart1, "45", "6789",
                dobYear, dobMonth, dobDay,
                ficoScore,
                "JOHN", "", "DOE",
                "123 MAIN ST",
                stateCode,
                "",
                "75001",
                "DALLAS",
                "USA",
                "", "", "",
                "GOVID123",
                "", "", "",
                "1234567890",
                "Y",
                0L);
    }

    /**
     * Rebuilds {@link #validRequest()} with a caller-supplied optimistic-lock {@code version}, so a
     * test can submit a stale token (one not matching the persisted account version) and assert the
     * before/after compare rejects it. Every other field stays valid.
     */
    private static AccountUpdateRequest validRequestWithVersion(long version) {
        AccountUpdateRequest base = validRequest();
        return new AccountUpdateRequest(
                base.accountId(), base.accountStatus(),
                base.openYear(), base.openMonth(), base.openDay(),
                base.creditLimit(),
                base.expirationYear(), base.expirationMonth(), base.expirationDay(),
                base.cashCreditLimit(),
                base.reissueYear(), base.reissueMonth(), base.reissueDay(),
                base.currentBalance(), base.currentCycleCredit(), base.accountGroupId(),
                base.currentCycleDebit(), base.customerId(),
                base.ssnPart1(), base.ssnPart2(), base.ssnPart3(),
                base.dobYear(), base.dobMonth(), base.dobDay(),
                base.ficoScore(),
                base.firstName(), base.middleName(), base.lastName(),
                base.addressLine1(), base.stateCode(), base.addressLine2(),
                base.zipCode(), base.city(), base.countryCode(),
                base.phone1Area(), base.phone1Prefix(), base.phone1Line(),
                base.governmentIssuedId(),
                base.phone2Area(), base.phone2Prefix(), base.phone2Line(),
                base.eftAccountId(), base.primaryCardHolderIndicator(),
                version);
    }

    private static AccountUpdateRequest validRequest() {
        return req("00000000001", "Y", "1990", "01", "01", "123", "700", "TX");
    }

    /**
     * Rebuilds {@link #validRequest()} with a caller-supplied (potentially arbitrary/tampered)
     * {@code customerId} body value, so a test can confirm the service ignores it and echoes the
     * customer derived from the account cross-reference instead. The matching version (0L) keeps the
     * before/after compare passing so the commit/redisplay path runs.
     */
    private static AccountUpdateRequest validRequestWithCustomerId(String customerId) {
        AccountUpdateRequest base = validRequest();
        return new AccountUpdateRequest(
                base.accountId(), base.accountStatus(),
                base.openYear(), base.openMonth(), base.openDay(),
                base.creditLimit(),
                base.expirationYear(), base.expirationMonth(), base.expirationDay(),
                base.cashCreditLimit(),
                base.reissueYear(), base.reissueMonth(), base.reissueDay(),
                base.currentBalance(), base.currentCycleCredit(), base.accountGroupId(),
                base.currentCycleDebit(), customerId,
                base.ssnPart1(), base.ssnPart2(), base.ssnPart3(),
                base.dobYear(), base.dobMonth(), base.dobDay(),
                base.ficoScore(),
                base.firstName(), base.middleName(), base.lastName(),
                base.addressLine1(), base.stateCode(), base.addressLine2(),
                base.zipCode(), base.city(), base.countryCode(),
                base.phone1Area(), base.phone1Prefix(), base.phone1Line(),
                base.governmentIssuedId(),
                base.phone2Area(), base.phone2Prefix(), base.phone2Line(),
                base.eftAccountId(), base.primaryCardHolderIndicator(),
                base.version());
    }

    private void stubReads() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum("4111111111111111");
        xref.setXrefAcctId(ACCT_ID);
        xref.setXrefCustId(CUST_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
        // Persisted account at @Version 0L; validRequest() echoes the same 0L so the before/after
        // optimistic-lock compare passes and the edit/commit path runs. Every successful update
        // advances the ACCTDAT+CUSTDAT aggregate version by exactly one (auto-increment when an
        // account column changed, or a forced increment for a customer-only update), so the echoed
        // version is deterministically preVersion + 1 = 1L - computed by the service rather than read
        // back, which is why it is independent of the mocked EntityManager/repository here.
        Account account = new Account();
        account.setVersion(0L);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        // The customer is resolved through the account cross-reference (XREF-CUST-ID = CUST_ID); the
        // response echoes THIS derived customer id, never an arbitrary request body value.
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
    }

    /**
     * Read-chain stub for a CUSTOMER-ONLY update: the persisted account already holds exactly the
     * account-column values that {@link #validRequest()} carries (status, the three scale-2 money
     * amounts, the three assembled dates, and the group id), so the set-if-different
     * {@code applyAccountChanges} touches no account column and the account is NOT dirty. Only the
     * customer fields differ. This is the path the QA report exercised, where the account version
     * must still advance via a forced increment so a later stale submit is caught.
     */
    private void stubReadsCustomerOnly() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum("4111111111111111");
        xref.setXrefAcctId(ACCT_ID);
        xref.setXrefCustId(CUST_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
        Account account = new Account();
        account.setVersion(0L);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("250.00"));
        account.setAcctCreditLimit(new BigDecimal("1000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("500.00"));
        account.setAcctCurrCycCredit(new BigDecimal("10.00"));
        account.setAcctCurrCycDebit(new BigDecimal("5.00"));
        account.setAcctOpenDate("2000-01-01");
        account.setAcctExpiraionDate("2030-12-31");
        account.setAcctReissueDate("2025-01-01");
        account.setAcctGroupId("GRP1");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
    }

    private void stubDatesAcceptable() {
        when(dateValidationService.validateDate(anyString(), anyString()))
                .thenReturn(new DateValidationResult(true, 0, 0, "OK", "", "YYYY-MM-DD"));
    }

    // ---- Happy path: dual save + echoed state, including the persisted @Version ----

    @Test
    @DisplayName("a fully valid request (matching version) commits the dual save and echoes the persisted state")
    void successfulUpdateCommitsDualSaveAndEchoesState() {
        stubReads();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("TX")).thenReturn(true);
        when(validationLookupService.isValidStateZip("TX", "75001")).thenReturn(true);

        AccountUpdateResponse resp = service().updateAccount(validRequest());

        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(customerRepository).saveAndFlush(any(Customer.class));
        assertThat(resp.infoMessage()).isEqualTo("Changes committed to database");
        assertThat(resp.errorMessage()).isNull();
        assertThat(resp.accountId()).isEqualTo("00000000001");
        // money echoed from the persisted account after applyAccountChanges normalized scale-2
        assertThat(resp.creditLimit()).isEqualByComparingTo("1000.00");
        assertThat(resp.currentBalance()).isEqualByComparingTo("250.00");
        // the committed @Version (preVersion + 1) is echoed so the client can submit a follow-up
        // update without re-reading; preVersion was 0L, so the committed value is 1L
        assertThat(resp.version()).isEqualTo(1L);
        // the customer id echoed is the one derived via the cross-reference (CUST_ID = 100)
        assertThat(resp.customerId()).isEqualTo("100");
    }

    // ---- Data contract: arbitrary body customerId is ignored (Issue 2) ----

    @Test
    @DisplayName("an arbitrary request customerId is ignored; the response echoes the account's true linked customer")
    void arbitraryCustomerIdIgnoredDerivedCustomerEchoed() {
        // QA Issue 2: the update previously echoed request.customerId() verbatim, so a tampered body
        // value leaked back to the client, breaking data-contract trust. COACTUPC derives the customer
        // from the account relationship (XREF-CUST-ID). The service must ignore the bogus "999999999"
        // and echo the derived customer (CUST_ID = 100), exactly as the account view emits it.
        stubReads();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("TX")).thenReturn(true);
        when(validationLookupService.isValidStateZip("TX", "75001")).thenReturn(true);

        AccountUpdateResponse resp = service().updateAccount(validRequestWithCustomerId("999999999"));

        assertThat(resp.customerId()).isEqualTo("100");
    }

    // ---- Single-increment mechanism selection (Issue 1 regression: customer-only advance, no +2) ----

    @Test
    @DisplayName("a customer-only update forces ONE account version increment so the aggregate lock stays effective")
    void customerOnlyUpdateForcesSingleAccountVersionIncrement() {
        // QA Issue 1: a customer-only update left account.version static, letting a later stale submit
        // slip past the before/after guard (lost update). COACTUPC always rewrites the account record,
        // so the account version must advance even when no account column changed. Here every account
        // column already matches the request, so the account is not dirty and would not auto-increment;
        // the service must request a forced increment to move it forward by exactly one.
        stubReadsCustomerOnly();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("TX")).thenReturn(true);
        when(validationLookupService.isValidStateZip("TX", "75001")).thenReturn(true);

        AccountUpdateResponse resp = service().updateAccount(validRequest());

        // The forced increment is requested exactly because the account is not dirty.
        verify(entityManager).lock(any(Account.class), eq(LockModeType.OPTIMISTIC_FORCE_INCREMENT));
        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(customerRepository).saveAndFlush(any(Customer.class));
        // The echoed version is the committed value preVersion + 1 = 1L, computed deterministically so
        // it does not depend on the forced increment's commit-time application.
        assertThat(resp.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a dirty-account update does NOT force an extra increment (guards against the +2 double bump)")
    void dirtyAccountUpdateDoesNotForceExtraIncrement() {
        // Regression guard: when an account column changed, Hibernate's @Version auto-increment supplies
        // the single increment. Requesting a forced increment on top would double-bump the version (the
        // +2 defect) and leave the echoed version inconsistent with the committed value. The persisted
        // account here (stubReads) has empty columns, so validRequest() dirties it and NO force lock
        // must be requested.
        stubReads();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("TX")).thenReturn(true);
        when(validationLookupService.isValidStateZip("TX", "75001")).thenReturn(true);

        AccountUpdateResponse resp = service().updateAccount(validRequest());

        verify(entityManager, never()).lock(any(), any(LockModeType.class));
        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(customerRepository).saveAndFlush(any(Customer.class));
        // Still preVersion + 1 = 1L (the auto-increment supplies the one bump).
        assertThat(resp.version()).isEqualTo(1L);
    }

    // ---- Concurrency: cross-request stale version (explicit before/after compare) ----

    @Test
    @DisplayName("a stale echoed version is rejected before any write (cross-request lost-update prevention)")
    void staleVersionRejectedBeforeAnyWrite() {
        // F-2 parity (COACTUPC 9700-CHECK-CHANGE-IN-REC): the persisted account is at version 0, but
        // the client echoes a stale version 99 (the value it read before another actor's update). The
        // before/after compare must reject the stale dual update (-> ConcurrencyException -> HTTP 409)
        // before EITHER master is written, preventing the silent lost update the QA report observed.
        stubReads();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("TX")).thenReturn(true);
        when(validationLookupService.isValidStateZip("TX", "75001")).thenReturn(true);

        assertThatThrownBy(() -> service().updateAccount(validRequestWithVersion(99L)))
                .isInstanceOf(ConcurrencyException.class)
                .hasMessage("Record changed by some one else. Please review");
        verify(accountRepository, never()).saveAndFlush(any());
        verify(customerRepository, never()).saveAndFlush(any());
    }

    // ---- Concurrency via @Version flush (in-transaction race window, matching submitted version) ----

    @Test
    @DisplayName("an optimistic-lock failure on flush is translated to ConcurrencyException")
    void concurrentModificationOnFlushThrowsConcurrencyException() {
        stubReads();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("TX")).thenReturn(true);
        when(validationLookupService.isValidStateZip("TX", "75001")).thenReturn(true);
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));

        assertThatThrownBy(() -> service().updateAccount(validRequest()))
                .isInstanceOf(ConcurrencyException.class)
                .hasMessage("Record changed by some one else. Please review");
        verify(customerRepository, never()).saveAndFlush(any());
    }

    // ---- MINOR: exact COBOL DOB future-date message ----

    @Test
    @DisplayName("a future date of birth is rejected with the exact COBOL message")
    void dobInFutureRejectedWithExactCobolMessage() {
        stubReads();
        stubDatesAcceptable();

        assertThatThrownBy(() -> service().updateAccount(
                req("00000000001", "Y", "2999", "12", "31", "123", "700", "TX")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Date of Birth:cannot be in the future ");
        verify(accountRepository, never()).saveAndFlush(any());
    }

    // ---- Read-chain failures ----

    @Test
    @DisplayName("a missing cross-reference throws RecordNotFoundException")
    void crossReferenceNotFoundThrows() {
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());
        assertThatThrownBy(() -> service().updateAccount(validRequest()))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("a missing account throws RecordNotFoundException")
    void accountNotFoundThrows() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCustId(CUST_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().updateAccount(validRequest()))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("a missing customer throws RecordNotFoundException")
    void customerNotFoundThrows() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCustId(CUST_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(new Account()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().updateAccount(validRequest()))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // ---- Field-edit cascade (first-error-wins) ----

    @Test
    @DisplayName("a non-numeric account id is rejected before any read")
    void nonNumericAccountIdRejected() {
        assertThatThrownBy(() -> service().updateAccount(
                req("ABCDEFGHIJK", "Y", "1990", "01", "01", "123", "700", "TX")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account Filter must be a non-zero 11 digit number");
        verify(cardCrossReferenceRepository, never()).findByXrefAcctId(any());
    }

    @Test
    @DisplayName("a blank account status is the first edit to fail")
    void blankAccountStatusRejectedFirst() {
        stubReads();
        assertThatThrownBy(() -> service().updateAccount(
                req("00000000001", "", "1990", "01", "01", "123", "700", "TX")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account Status must be supplied.");
    }

    @Test
    @DisplayName("an invalid SSN area part is rejected with the exact COBOL message")
    void invalidSsnPart1Rejected() {
        stubReads();
        stubDatesAcceptable();
        assertThatThrownBy(() -> service().updateAccount(
                req("00000000001", "Y", "1990", "01", "01", "666", "700", "TX")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
    }

    @Test
    @DisplayName("a FICO score out of range is rejected with the exact COBOL message")
    void invalidFicoRejected() {
        stubReads();
        stubDatesAcceptable();
        assertThatThrownBy(() -> service().updateAccount(
                req("00000000001", "Y", "1990", "01", "01", "123", "200", "TX")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("FICO Score: should be between 300 and 850");
    }

    @Test
    @DisplayName("an unknown state code is rejected with the exact COBOL message")
    void invalidStateCodeRejected() {
        stubReads();
        stubDatesAcceptable();
        when(validationLookupService.isValidStateCode("ZZ")).thenReturn(false);
        assertThatThrownBy(() -> service().updateAccount(
                req("00000000001", "Y", "1990", "01", "01", "123", "700", "ZZ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("State: is not a valid state code");
    }
}
