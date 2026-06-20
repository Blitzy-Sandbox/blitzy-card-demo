package com.carddemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * before/after record-image comparison ({@code 9700-CHECK-CHANGE-IN-REC}) is reproduced entirely
 * server-side by the JPA {@code @Version} optimistic lock that fires on flush &mdash; the stateless
 * REST contract carries <strong>no</strong> version token (CP3 schema constraint). These tests pin
 * that contract: the request/response records expose no {@code version} component (compile-time
 * proof), concurrency is signalled by a flush-time {@link ObjectOptimisticLockingFailureException}
 * translated to {@link ConcurrencyException}, and the date-of-birth future-date edit emits the exact
 * COBOL message {@code "Date of Birth:cannot be in the future "}.</p>
 */
@DisplayName("AccountUpdateService - COACTUPC validation, dual-save rollback, @Version concurrency (no DTO version)")
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

    private AccountUpdateService service() {
        return new AccountUpdateService(accountRepository, customerRepository,
                cardCrossReferenceRepository, dateValidationService, validationLookupService);
    }

    /**
     * A fully valid update request (no {@code version} component &mdash; the CP3 contract change).
     * Callers override the few fields they wish to make invalid.
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
                "Y");
    }

    private static AccountUpdateRequest validRequest() {
        return req("00000000001", "Y", "1990", "01", "01", "123", "700", "TX");
    }

    private void stubReads() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum("4111111111111111");
        xref.setXrefAcctId(ACCT_ID);
        xref.setXrefCustId(CUST_ID);
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(new Account()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(new Customer()));
    }

    private void stubDatesAcceptable() {
        when(dateValidationService.validateDate(anyString(), anyString()))
                .thenReturn(new DateValidationResult(true, 0, 0, "OK", "", "YYYY-MM-DD"));
    }

    // ---- Happy path: dual save + echoed state, no version token ----

    @Test
    @DisplayName("a fully valid request commits the dual save and echoes the persisted state")
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
    }

    // ---- Concurrency via @Version flush (no request precheck) ----

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
