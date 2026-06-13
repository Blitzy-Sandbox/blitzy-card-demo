package com.cardemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fast, fully-mocked unit tests for {@link AccountUpdateService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program {@code app/cbl/COACTUPC.cbl}
 * (transaction {@code CAUP}). At 4,236 COBOL lines {@code COACTUPC} is the most complex program in
 * the AWS CardDemo estate and the <strong>sole {@code SYNCPOINT ROLLBACK} site</strong>; its
 * behavioral parity (AAP &sect;0.7.2) is the core of the whole migration.
 *
 * <h2>Technology substitutions exercised here (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>COBOL {@code SYNCPOINT ROLLBACK} (dual {@code ACCTDAT}+{@code CUSTDAT} {@code REWRITE})
 *       &rarr; Spring {@code @Transactional(rollbackFor = Exception.class)} &mdash; asserted
 *       reflectively (Group&nbsp;G); the real DB rollback is an integration concern and is out of
 *       scope here (see prompt &sect;8).</li>
 *   <li>Read-update before/after image comparison ({@code DATA-WAS-CHANGED-BEFORE-UPDATE}) &rarr;
 *       JPA {@code @Version} optimistic locking &rarr;
 *       {@link com.cardemo.exception.ConcurrentModificationException} (HTTP&nbsp;409) &mdash; both
 *       the client-supplied stale-version pre-check and the persistence-provider
 *       {@link ObjectOptimisticLockingFailureException} catch are covered (Group&nbsp;G).</li>
 *   <li>{@code CALL 'CSUTLDTC'} / {@code CEEDAYS} date edits &rarr; {@link DateValidationService}
 *       and {@code CSLKPCDY} NANPA/state/ZIP lookups &rarr; {@link ValidationLookupService}
 *       &mdash; both are <em>mocked</em> collaborators here (their exhaustive parity suites live in
 *       {@code unit/validation/}).</li>
 * </ul>
 *
 * <p>These tests are millisecond, in-memory and pure-mock: no Spring context, no Testcontainers, no
 * database, no network and no filesystem. Mockito runs under its default {@code STRICT_STUBS}
 * strictness via {@link MockitoExtension}. All monetary fields are {@link BigDecimal} (scale&nbsp;2)
 * and are asserted with {@code isEqualByComparingTo} (never the scale-sensitive {@code equals};
 * AAP&nbsp;&sect;0.7.3).</p>
 *
 * <p><strong>Traceability.</strong> Reproduces behavior from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}; the COBOL is read-only reference and is never copied into this repository.</p>
 *
 * @see AccountUpdateService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService (COACTUPC / CAUP) — behavioral-parity unit tests")
class AccountUpdateServiceTest {

    /** Account key fixture: input {@code "00000000001"} parses to {@code 1L} (decoded from acctdata). */
    private static final Long ACCT_ID = 1L;
    /** Customer key fixture: customer {@code 1} owns account {@code 1} (cardxref xrefCustId/xrefAcctId). */
    private static final Long CUST_ID = 1L;
    /** The 11-digit account identifier as received on the request path ({@code ACCT-ID PIC 9(11)}). */
    private static final String ACCOUNT_ID_INPUT = "00000000001";
    /** The 9-digit customer identifier carried by the DTO ({@code CUST-ID PIC 9(09)}). */
    private static final String CUSTOMER_ID_INPUT = "000000001";
    /** The committed optimistic-lock token of the fetched account ({@code @Version}). */
    private static final long ENTITY_VERSION = 7L;

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private DateValidationService dateValidationService;
    @Mock
    private ValidationLookupService validationLookupService;

    @InjectMocks
    private AccountUpdateService accountUpdateService;

    /** The managed account "old image" returned by {@code findById} (mutated in place on write). */
    private Account account;
    /** The managed customer "old image" returned by {@code findById} (mutated in place on write). */
    private Customer customer;

    @BeforeEach
    void setUp() {
        account = existingAccount();
        customer = existingCustomer();
    }

    // =================================================================================================
    // Fixture builders (canonical golden values decoded from app/data/ASCII/*; the mocks fully control
    // them so the tests stay deterministic and sub-millisecond).
    // =================================================================================================

    /**
     * The fetched {@link Account} "old image". {@code currentBalance} is deliberately stored at
     * scale&nbsp;3 ({@code "1234.560"}) so the no-change test proves the service compares money with
     * {@code BigDecimal.compareTo} rather than the scale-sensitive {@code equals}.
     */
    private Account existingAccount() {
        Account a = new Account();
        a.setAcctId(ACCT_ID);
        a.setAcctActiveStatus("Y");
        a.setAcctCreditLimit(new BigDecimal("5000.00"));
        a.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        a.setAcctCurrBal(new BigDecimal("1234.560"));
        a.setAcctCurrCycCredit(new BigDecimal("300.00"));
        a.setAcctCurrCycDebit(new BigDecimal("150.00"));
        a.setAcctOpenDate(LocalDate.of(2014, 11, 20));
        a.setAcctExpirationDate(LocalDate.of(2025, 5, 20));
        a.setAcctReissueDate(LocalDate.of(2025, 5, 20));
        a.setAcctGroupId("GROUP01");
        a.setVersion(ENTITY_VERSION);
        return a;
    }

    /**
     * The fetched {@link Customer} "old image". The FICO score is {@code 750} (a value inside the
     * {@code 1275-EDIT-FICO-SCORE} 300..850 band); the raw acctdata fixture value {@code 274} is NOT
     * used as the valid baseline because {@code 274 < 300} fails that edit (a parity fact exercised
     * directly in {@link EditFicoScore}). SSN is the raw 9-digit {@link Long} {@code 20973888}
     * ({@code %09d} &rarr; {@code "020973888"}).
     */
    private Customer existingCustomer() {
        Customer c = new Customer();
        c.setCustId(CUST_ID);
        c.setCustFirstName("Immanuel");
        c.setCustMiddleName("Madeline");
        c.setCustLastName("Kessler");
        c.setCustAddrLine1("618 Deshaun Route");
        c.setCustAddrLine2("Apt 802");
        c.setCustAddrLine3("Altenwerthshire");
        c.setCustAddrStateCd("NC");
        c.setCustAddrCountryCd("USA");
        c.setCustAddrZip("12546");
        c.setCustPhoneNum1("(908)119-8310");
        c.setCustPhoneNum2("(373)693-8684");
        c.setCustSsn(20973888L);
        c.setCustGovtIssuedId("00000000000049368437");
        c.setCustDobYyyyMmDd(LocalDate.of(1961, 6, 8));
        c.setCustEftAccountId("0053581756");
        c.setCustPriCardHolderInd("Y");
        c.setCustFicoCreditScore(750);
        return c;
    }

    /**
     * A fully-valid {@link AccountDto} that passes every {@code 1200-EDIT-MAP-INPUTS} field edit and
     * whose values EQUAL {@link #existingAccount()}/{@link #existingCustomer()} across all
     * change-detection ({@code 1205-COMPARE-OLD-NEW}) fields &mdash; so on its own it is a "no change"
     * request. Individual tests mutate exactly one aspect to exercise a single rule.
     */
    private AccountDto validRequest() {
        AccountDto r = new AccountDto();
        r.setAccountId(ACCOUNT_ID_INPUT);
        r.setCustomerId(CUSTOMER_ID_INPUT);
        r.setAccountStatus("Y");
        r.setOpenDate(LocalDate.of(2014, 11, 20));
        r.setCreditLimit(new BigDecimal("5000.00"));
        r.setExpirationDate(LocalDate.of(2025, 5, 20));
        r.setCashCreditLimit(new BigDecimal("2000.00"));
        r.setReissueDate(LocalDate.of(2025, 5, 20));
        r.setCurrentBalance(new BigDecimal("1234.56"));
        r.setCurrentCycleCredit(new BigDecimal("300.00"));
        r.setCurrentCycleDebit(new BigDecimal("150.00"));
        r.setAccountGroupId("GROUP01");
        r.setSsn("020-97-3888");
        r.setDateOfBirth(LocalDate.of(1961, 6, 8));
        r.setFicoScore("750");
        r.setFirstName("Immanuel");
        r.setMiddleName("Madeline");
        r.setLastName("Kessler");
        r.setAddressLine1("618 Deshaun Route");
        r.setAddressLine2("Apt 802");
        r.setCity("Altenwerthshire");
        r.setState("NC");
        r.setZipCode("12546");
        r.setCountryCode("USA");
        r.setPhoneNumber1("(908)119-8310");
        r.setPhoneNumber2("(373)693-8684");
        r.setGovernmentIssuedId("00000000000049368437");
        r.setEftAccountId("0053581756");
        r.setPrimaryCardHolderIndicator("Y");
        return r;
    }

    // =================================================================================================
    // Stub helpers.
    // =================================================================================================

    /** Stubs both keyed reads ({@code READ ACCTDAT/CUSTDAT UPDATE} &rarr; {@code findById}) as found. */
    private void givenRecordsFound() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
    }

    /**
     * Makes every delegated validator report success. These are declared {@code lenient()} because the
     * field-edit cascade consults them <em>conditionally</em> (for example {@code isValidStateCode} runs
     * only after the State alpha-edit passes, {@code isValidStateZip} only when both State and Zip
     * passed, and {@code isValidAreaCode} only for a non-blank phone area), so a given test legitimately
     * exercises only a subset &mdash; the genuine justification for {@code lenient()} under
     * {@code STRICT_STUBS} (prompt &sect;10).
     */
    private void givenValidatorsPass() {
        lenient().when(dateValidationService.validateCcyymmdd(anyString(), anyString()))
                .thenReturn(DateValidationResult.ofValid());
        lenient().when(dateValidationService.validateDateOfBirth(anyString(), anyString()))
                .thenReturn(DateValidationResult.ofValid());
        lenient().when(validationLookupService.isValidStateCode(anyString())).thenReturn(true);
        lenient().when(validationLookupService.isValidAreaCode(anyString())).thenReturn(true);
        lenient().when(validationLookupService.isValidStateZip(anyString(), anyString())).thenReturn(true);
    }

    /** Casts a caught throwable to {@link ValidationException} for fluent inspection of its error list. */
    private static ValidationException asValidationException(Throwable t) {
        assertThat(t).isInstanceOf(ValidationException.class);
        return (ValidationException) t;
    }

    /**
     * Invokes {@code updateAccount} expecting the field-edit cascade to fail, and returns the accumulated
     * {@link ValidationException#getValidationErrors() error list}. Records and validators are stubbed
     * here so the call reaches the cascade. Used by the per-edit-paragraph tests, every one of which
     * arranges at least one failing field (directly or via a sentinel) so a {@link ValidationException}
     * is guaranteed.
     */
    private List<String> errorsFor(AccountDto request) {
        givenRecordsFound();
        givenValidatorsPass();
        Throwable thrown = catchThrowable(() -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request));
        return asValidationException(thrown).getValidationErrors();
    }

    // =================================================================================================
    // GROUP A — account-key validation (1210-EDIT-ACCOUNT) and the null-body guard. These fail BEFORE
    // any repository/collaborator interaction, so no I/O occurs.
    // =================================================================================================

    @Nested
    @DisplayName("Group A — account-key validation (1210-EDIT-ACCOUNT)")
    class AccountKeyValidation {

        @ParameterizedTest(name = "blank accountId [{0}] -> \"Account Number must be supplied.\"")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "           "})
        @DisplayName("blank / spaces account id -> ValidationException on field 'Account Number', no I/O")
        void blankAccountIdRejected(String blankId) {
            assertThatThrownBy(() -> accountUpdateService.updateAccount(blankId, validRequest()))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> {
                        ValidationException ve = asValidationException(ex);
                        assertThat(ve.getFieldName()).isEqualTo("Account Number");
                        assertThat(ve.getValidationErrors())
                                .containsExactly("Account Number must be supplied.");
                    });
            verifyNoInteractions(accountRepository, customerRepository,
                    dateValidationService, validationLookupService);
        }

        @ParameterizedTest(name = "invalid accountId [{0}] -> \"...11 digit Non-Zero Number\"")
        @ValueSource(strings = {"abc", "00000000000", "12A45", "-1", "0"})
        @DisplayName("non-numeric or all-zero account id -> 11-digit-non-zero message, no I/O")
        void nonNumericOrZeroAccountIdRejected(String badId) {
            assertThatThrownBy(() -> accountUpdateService.updateAccount(badId, validRequest()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Account Number if supplied must be a 11 digit Non-Zero Number");
            verifyNoInteractions(accountRepository, customerRepository,
                    dateValidationService, validationLookupService);
        }

        @Test
        @DisplayName("null request body -> ValidationException on field 'Account', no I/O")
        void nullRequestRejected() {
            assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, null))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> {
                        ValidationException ve = asValidationException(ex);
                        assertThat(ve.getFieldName()).isEqualTo("Account");
                        assertThat(ve.getValidationErrors())
                                .containsExactly("Account details must be supplied.");
                    });
            verifyNoInteractions(accountRepository, customerRepository,
                    dateValidationService, validationLookupService);
        }
    }

    // =================================================================================================
    // GROUP B — the marquee test: the 1200-EDIT-MAP-INPUTS cascade accumulates one message per failed
    // field, IN COBOL FIELD ORDER, and raises them together in a SINGLE ValidationException
    // (accumulate-then-throw-once, NOT fail-fast). No write occurs when validation fails.
    // =================================================================================================

    @Nested
    @DisplayName("Group B — COACTUPC 24-field cascade accumulates errors in order and throws once")
    class ValidationCascadeOrdering {

        @Test
        @DisplayName("five fields fail -> ONE ValidationException carrying all five, in cascade order, no write")
        void accumulatesAllFailuresInCascadeOrder() {
            givenRecordsFound();
            givenValidatorsPass();
            AccountDto request = validRequest();
            request.setAccountStatus("X");      // #1  1220-EDIT-YESNO
            request.setFicoScore("999");        // #12 1275-EDIT-FICO-SCORE (out of range)
            request.setFirstName("John1");      // #13 1225-EDIT-ALPHA-REQD (non-alpha)
            request.setAddressLine1("");        // #16 1215-EDIT-MANDATORY (blank)
            request.setZipCode("ABCDE");        // #18 1245-EDIT-NUM-REQD (non-numeric)

            Throwable thrown = catchThrowable(
                    () -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request));

            ValidationException ve = asValidationException(thrown);
            List<String> errors = ve.getValidationErrors();
            // accumulate-then-throw-once: every violated field is present, exactly once, in cascade order.
            assertThat(errors).containsExactly(
                    "Account Status must be Y or N.",
                    "FICO Score: should be between 300 and 850",
                    "First Name can have alphabets only.",
                    "Address Line 1 must be supplied.",
                    "Zip must be all numeric.");
            // No write happens when validation fails (the dual REWRITE is never reached).
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("relative order is preserved as a subsequence even amid other passing fields")
        void preservesRelativeOrderAsSubsequence() {
            givenRecordsFound();
            givenValidatorsPass();
            AccountDto request = validRequest();
            request.setAccountStatus("X");   // #1
            request.setFicoScore("999");     // #12
            request.setZipCode("ABCDE");     // #18

            Throwable thrown = catchThrowable(
                    () -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request));

            assertThat(asValidationException(thrown).getValidationErrors())
                    .containsSubsequence(
                            "Account Status must be Y or N.",
                            "FICO Score: should be between 300 and 850",
                            "Zip must be all numeric.");
        }

        @Test
        @DisplayName("a fully-valid request accumulates ZERO errors and proceeds past validation to the write")
        void fullyValidRequestProducesNoErrors() {
            givenRecordsFound();
            givenValidatorsPass();
            when(accountRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            AccountDto request = validRequest();
            request.setCreditLimit(new BigDecimal("6000.00")); // one valid change so the cascade is reached

            // No ValidationException is thrown; the cascade passed and the write was performed.
            AccountDto result = accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request);

            assertThat(result).isNotNull();
            verify(accountRepository).saveAndFlush(any(Account.class));
            verify(customerRepository).saveAndFlush(any(Customer.class));
        }
    }

    // =================================================================================================
    // GROUP C — per-edit-paragraph rules (1215..1280), one rule each. Each failing field contributes
    // exactly one message; "valid value passes" sub-cases pair the field-under-test with a guaranteed
    // sentinel failure (Account Status = "Q" -> 1220) so the call stays on the accumulate-then-throw
    // path WITHOUT reaching the dual write, and assert the field-under-test produced no message.
    // Every cascade test stubs BOTH reads as found (so the no-change short-circuit is passed) and
    // mutates at least one field (so 1205-COMPARE-OLD-NEW reports a change and the cascade is reached).
    // =================================================================================================

    /** The sentinel failure injected to keep "valid value passes" tests on the throw path (1220-EDIT-YESNO). */
    private static final String SENTINEL_MSG = "Account Status must be Y or N.";

    @Nested
    @DisplayName("Group C.1 — 1220-EDIT-YESNO (Account Status #1, Primary Card Holder #24)")
    class EditYesNo {

        @Test
        @DisplayName("Account Status not Y/N -> \"Account Status must be Y or N.\"")
        void accountStatusMustBeYorN() {
            AccountDto request = validRequest();
            request.setAccountStatus("Q");
            assertThat(errorsFor(request)).containsExactly("Account Status must be Y or N.");
        }

        @Test
        @DisplayName("Account Status blank -> \"Account Status must be supplied.\"")
        void accountStatusBlankMustBeSupplied() {
            AccountDto request = validRequest();
            request.setAccountStatus("");
            assertThat(errorsFor(request)).containsExactly("Account Status must be supplied.");
        }

        @Test
        @DisplayName("Primary Card Holder not Y/N -> \"Primary Card Holder must be Y or N.\"")
        void primaryCardHolderMustBeYorN() {
            AccountDto request = validRequest();
            request.setPrimaryCardHolderIndicator("Q");
            assertThat(errorsFor(request)).containsExactly("Primary Card Holder must be Y or N.");
        }

        @ParameterizedTest(name = "Account Status [{0}] is accepted (no YESNO error)")
        @ValueSource(strings = {"Y", "N"})
        @DisplayName("Account Status Y and N are accepted")
        void yesNoAcceptsYandN(String status) {
            AccountDto request = validRequest();
            request.setAccountStatus(status);
            request.setFirstName("John1"); // sentinel: guarantees a failure so we stay on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains("First Name can have alphabets only.");
            assertThat(errors).doesNotContain("Account Status must be Y or N.",
                    "Account Status must be supplied.");
        }
    }

    @Nested
    @DisplayName("Group C.2 — 1215-EDIT-MANDATORY (Address Line 1 #16)")
    class EditMandatory {

        @Test
        @DisplayName("Address Line 1 blank -> \"Address Line 1 must be supplied.\"")
        void addressLine1Mandatory() {
            AccountDto request = validRequest();
            request.setAddressLine1("");
            assertThat(errorsFor(request)).containsExactly("Address Line 1 must be supplied.");
        }
    }

    @Nested
    @DisplayName("Group C.3 — 1225-EDIT-ALPHA-REQD (First/Last/City/Country #13,15,19,20)")
    class EditAlphaRequired {

        @Test
        @DisplayName("First Name with a digit -> \"First Name can have alphabets only.\"")
        void firstNameNonAlphaRejected() {
            AccountDto request = validRequest();
            request.setFirstName("John1");
            assertThat(errorsFor(request)).containsExactly("First Name can have alphabets only.");
        }

        @Test
        @DisplayName("First Name blank -> \"First Name must be supplied.\" (required)")
        void firstNameBlankRejected() {
            AccountDto request = validRequest();
            request.setFirstName("");
            assertThat(errorsFor(request)).containsExactly("First Name must be supplied.");
        }

        @Test
        @DisplayName("Last Name with a digit -> \"Last Name can have alphabets only.\"")
        void lastNameNonAlphaRejected() {
            AccountDto request = validRequest();
            request.setLastName("Kessler9");
            assertThat(errorsFor(request)).containsExactly("Last Name can have alphabets only.");
        }

        @Test
        @DisplayName("City with a digit -> \"City can have alphabets only.\"")
        void cityNonAlphaRejected() {
            AccountDto request = validRequest();
            request.setCity("Town1");
            assertThat(errorsFor(request)).containsExactly("City can have alphabets only.");
        }

        @Test
        @DisplayName("Country with a digit -> \"Country can have alphabets only.\"")
        void countryNonAlphaRejected() {
            AccountDto request = validRequest();
            request.setCountryCode("US1");
            assertThat(errorsFor(request)).containsExactly("Country can have alphabets only.");
        }
    }

    @Nested
    @DisplayName("Group C.4 — 1235-EDIT-ALPHA-OPT (Middle Name #14)")
    class EditAlphaOptional {

        @Test
        @DisplayName("Middle Name blank is VALID (optional) — no Middle Name error")
        void middleNameBlankIsValid() {
            AccountDto request = validRequest();
            request.setMiddleName("");
            request.setFirstName("John1"); // sentinel keeps the call on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains("First Name can have alphabets only.");
            assertThat(errors).doesNotContain("Middle Name can have alphabets only.");
        }

        @Test
        @DisplayName("Middle Name with a digit -> \"Middle Name can have alphabets only.\"")
        void middleNameNonAlphaRejected() {
            AccountDto request = validRequest();
            request.setMiddleName("Mad3line");
            assertThat(errorsFor(request)).containsExactly("Middle Name can have alphabets only.");
        }
    }

    @Nested
    @DisplayName("Group C.5 — 1245-EDIT-NUM-REQD (Zip #18 len 5, EFT Account Id #23 len 10)")
    class EditNumericRequired {

        @ParameterizedTest(name = "Zip [{0}] -> \"Zip must be all numeric.\"")
        @ValueSource(strings = {"ABCDE", "123", "1234567", "12A45"})
        @DisplayName("Zip not exactly 5 digits -> \"Zip must be all numeric.\"")
        void zipNotFiveDigitsRejected(String zip) {
            AccountDto request = validRequest();
            request.setZipCode(zip);
            assertThat(errorsFor(request)).containsExactly("Zip must be all numeric.");
        }

        @Test
        @DisplayName("Zip blank -> \"Zip must be supplied.\"")
        void zipBlankRejected() {
            AccountDto request = validRequest();
            request.setZipCode("");
            assertThat(errorsFor(request)).containsExactly("Zip must be supplied.");
        }

        @Test
        @DisplayName("Zip all zeros -> \"Zip must not be zero.\"")
        void zipAllZerosRejected() {
            AccountDto request = validRequest();
            request.setZipCode("00000");
            assertThat(errorsFor(request)).containsExactly("Zip must not be zero.");
        }

        @ParameterizedTest(name = "EFT Account Id [{0}] -> \"EFT Account Id must be all numeric.\"")
        @ValueSource(strings = {"ABC1234567", "123", "123456789012"})
        @DisplayName("EFT Account Id not exactly 10 digits -> \"EFT Account Id must be all numeric.\"")
        void eftNotTenDigitsRejected(String eft) {
            AccountDto request = validRequest();
            request.setEftAccountId(eft);
            assertThat(errorsFor(request)).containsExactly("EFT Account Id must be all numeric.");
        }

        @Test
        @DisplayName("EFT Account Id all zeros -> \"EFT Account Id must not be zero.\"")
        void eftAllZerosRejected() {
            AccountDto request = validRequest();
            request.setEftAccountId("0000000000");
            assertThat(errorsFor(request)).containsExactly("EFT Account Id must not be zero.");
        }
    }

    @Nested
    @DisplayName("Group C.6 — 1250-EDIT-SIGNED-9V2 (currency #3,5,7,8,9)")
    class EditSignedCurrency {

        // NOTE (parity adaptation): the COBOL TEST-NUMVAL-C "X is not valid" branch is unreachable from a
        // typed BigDecimal DTO (Jackson rejects a non-numeric body at deserialization), so the shipped
        // editSigned9v2 only fails on a null amount with "<label> must be supplied." We therefore assert the
        // reachable null branch (and that a clean amount parses to scale 2) rather than the prompt's "12.3X".

        @Test
        @DisplayName("Credit Limit null -> \"Credit Limit must be supplied.\"")
        void creditLimitNullRejected() {
            AccountDto request = validRequest();
            request.setCreditLimit(null);
            assertThat(errorsFor(request)).containsExactly("Credit Limit must be supplied.");
        }

        @Test
        @DisplayName("Current Balance null -> \"Current Balance must be supplied.\"")
        void currentBalanceNullRejected() {
            AccountDto request = validRequest();
            request.setCurrentBalance(null);
            assertThat(errorsFor(request)).containsExactly("Current Balance must be supplied.");
        }

        @Test
        @DisplayName("a clean scale-2 amount passes the currency edit (no currency error)")
        void validCurrencyAccepted() {
            AccountDto request = validRequest();
            request.setCreditLimit(new BigDecimal("6000.00")); // valid change
            request.setFirstName("John1");                      // sentinel keeps us on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains("First Name can have alphabets only.");
            assertThat(errors).doesNotContain("Credit Limit must be supplied.", "Credit Limit is not valid");
            // the controlled amount is a scale-2 BigDecimal (decimal-fidelity, AAP §0.7.3).
            assertThat(request.getCreditLimit()).isEqualByComparingTo("6000.00");
            assertThat(request.getCreditLimit().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Group C.7 — 1265-EDIT-US-SSN (SSN #10)")
    class EditUsSsn {

        @ParameterizedTest(name = "SSN part-1 [{0}] -> canonical INVALID-SSN-PART1 message")
        @CsvSource({
            "'666-12-3456', 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999'",
            "'900-12-3456', 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999'",
            "'999-12-3456', 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999'"
        })
        @DisplayName("SSN part-1 in {666, 900..999} -> INVALID-SSN-PART1 message")
        void ssnPart1ForbiddenValuesRejected(String ssn, String expected) {
            AccountDto request = validRequest();
            request.setSsn(ssn);
            assertThat(errorsFor(request)).containsExactly(expected);
        }

        @Test
        @DisplayName("SSN part-1 all zeros -> \"SSN: First 3 chars must not be zero.\" (1245 fires before the 88-level rule)")
        void ssnPart1AllZerosRejected() {
            AccountDto request = validRequest();
            request.setSsn("000-12-3456");
            assertThat(errorsFor(request)).containsExactly("SSN: First 3 chars must not be zero.");
        }

        @Test
        @DisplayName("a valid SSN passes (no SSN error)")
        void validSsnAccepted() {
            AccountDto request = validRequest();
            request.setSsn("123-45-6789"); // valid change
            request.setFirstName("John1");  // sentinel keeps us on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains("First Name can have alphabets only.");
            assertThat(errors).noneMatch(e -> e.startsWith("SSN"));
        }
    }

    @Nested
    @DisplayName("Group C.8 — 1270-EDIT-US-STATE-CD (State #17)")
    class EditStateCode {

        @Test
        @DisplayName("State with a digit fails the alpha edit FIRST -> lookup is never consulted")
        void stateNonAlphaRejectedBeforeLookup() {
            AccountDto request = validRequest();
            request.setState("N1");
            assertThat(errorsFor(request)).containsExactly("State can have alphabets only.");
            verify(validationLookupService, never()).isValidStateCode(anyString());
        }

        @Test
        @DisplayName("alpha-valid but unknown state delegates to the lookup -> \"State: is not a valid state code\"")
        void stateInvalidCodeRejectedViaLookup() {
            givenRecordsFound();
            givenValidatorsPass();
            when(validationLookupService.isValidStateCode("ZZ")).thenReturn(false);
            AccountDto request = validRequest();
            request.setState("ZZ"); // alphabetic, but not a real US state code
            Throwable thrown = catchThrowable(
                    () -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request));
            assertThat(asValidationException(thrown).getValidationErrors())
                    .containsExactly("State: is not a valid state code");
            verify(validationLookupService).isValidStateCode("ZZ"); // delegation actually happened
        }

        @Test
        @DisplayName("a known state code is accepted via the lookup (no State error)")
        void validStateAccepted() {
            AccountDto request = validRequest();
            request.setState("NC");       // matches entity; lookup returns true (lenient)
            request.setFirstName("John1"); // sentinel keeps us on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains("First Name can have alphabets only.");
            assertThat(errors).noneMatch(e -> e.startsWith("State"));
            verify(validationLookupService).isValidStateCode("NC");
        }
    }

    @Nested
    @DisplayName("Group C.9 — 1245 then 1275-EDIT-FICO-SCORE (FICO #12)")
    class EditFicoScore {

        @ParameterizedTest(name = "FICO [{0}] -> \"FICO Score: should be between 300 and 850\"")
        @ValueSource(strings = {"299", "851", "274"})
        @DisplayName("FICO out of 300..850 -> range message (274 is the raw acctdata value, < 300)")
        void ficoOutOfRangeRejected(String fico) {
            AccountDto request = validRequest();
            request.setFicoScore(fico);
            assertThat(errorsFor(request)).containsExactly("FICO Score: should be between 300 and 850");
        }

        @Test
        @DisplayName("non-numeric FICO fails the 1245 numeric edit BEFORE the range check")
        void ficoNonNumericRejectedBeforeRange() {
            AccountDto request = validRequest();
            request.setFicoScore("AAA");
            assertThat(errorsFor(request)).containsExactly("FICO Score must be all numeric.");
        }

        @ParameterizedTest(name = "FICO boundary [{0}] is accepted (no range error)")
        @ValueSource(strings = {"300", "850"})
        @DisplayName("FICO boundary values 300 and 850 are accepted")
        void ficoBoundaryAccepted(String fico) {
            AccountDto request = validRequest();
            request.setFicoScore(fico);            // valid boundary
            request.setAccountStatus("Q");          // sentinel keeps us on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains(SENTINEL_MSG);
            assertThat(errors).doesNotContain("FICO Score: should be between 300 and 850");
        }
    }

    @Nested
    @DisplayName("Group C.10 — 1260-EDIT-US-PHONE-NUM (Phone 1 #21, Phone 2 #22)")
    class EditUsPhone {

        @Test
        @DisplayName("an all-blank phone is VALID (the field is optional)")
        void phoneAllBlankIsValid() {
            AccountDto request = validRequest();
            request.setPhoneNumber1("");   // optional -> no Phone Number 1 error
            request.setAccountStatus("Q");  // sentinel keeps us on the throw path
            List<String> errors = errorsFor(request);
            assertThat(errors).contains(SENTINEL_MSG);
            assertThat(errors).noneMatch(e -> e.startsWith("Phone Number 1:"));
        }

        @ParameterizedTest(name = "phone [{0}] -> [{1}]")
        @CsvSource({
            "'12', 'Phone Number 1: Area code must be A 3 digit number.'",
            "'(000)555-0100', 'Phone Number 1: Area code cannot be zero'",
            "'(908)11', 'Phone Number 1: Prefix code must be A 3 digit number.'",
            "'(908)000-0100', 'Phone Number 1: Prefix code cannot be zero'",
            "'(908)555-12', 'Phone Number 1: Line number code must be A 4 digit number.'",
            "'(908)555-0000', 'Phone Number 1: Line number code cannot be zero'"
        })
        @DisplayName("structural phone failures (area/prefix/line) return the first failing component message")
        void phoneStructuralFailures(String phone, String expected) {
            AccountDto request = validRequest();
            request.setPhoneNumber1(phone);
            assertThat(errorsFor(request)).containsExactly(expected);
        }

        @Test
        @DisplayName("a structurally-valid but non-NANPA area code delegates to the lookup -> non-NANPA message")
        void phoneAreaNonNanpaRejected() {
            givenRecordsFound();
            givenValidatorsPass();
            when(validationLookupService.isValidAreaCode("200")).thenReturn(false);
            AccountDto request = validRequest();
            request.setPhoneNumber1("(200)555-0100"); // well-formed, but the area is not NANPA-valid
            Throwable thrown = catchThrowable(
                    () -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request));
            assertThat(asValidationException(thrown).getValidationErrors())
                    .containsExactly("Phone Number 1: Not valid North America general purpose area code");
            verify(validationLookupService).isValidAreaCode("200"); // delegation actually happened
        }
    }

    @Nested
    @DisplayName("Group C.11 — 1280-EDIT-US-STATE-ZIP-CD cross-field combo")
    class EditStateZipCombo {

        @Test
        @DisplayName("the combo is SKIPPED when State failed its per-field edit (isValidStateZip never called)")
        void comboSkippedWhenStateInvalid() {
            AccountDto request = validRequest();
            request.setState("N1"); // #17 fails the alpha edit -> stateValid=false -> combo (1280) skipped
            assertThat(errorsFor(request)).containsExactly("State can have alphabets only.");
            verify(validationLookupService, never()).isValidStateZip(anyString(), anyString());
        }

        @Test
        @DisplayName("both fields individually valid but combo rejected -> \"Invalid zip code for state\"")
        void comboFailsWhenLookupRejects() {
            givenRecordsFound();
            givenValidatorsPass();
            when(validationLookupService.isValidStateZip("NC", "99999")).thenReturn(false);
            AccountDto request = validRequest();
            request.setZipCode("99999"); // valid 5-digit change; State stays "NC" (valid)
            Throwable thrown = catchThrowable(
                    () -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request));
            assertThat(asValidationException(thrown).getValidationErrors())
                    .containsExactly("Invalid zip code for state");
            verify(validationLookupService).isValidStateZip("NC", "99999"); // combo actually ran
        }
    }

    // =================================================================================================
    // GROUP C.12 — field-width guards (QA F5). COACTUPC relied on the fixed-width BMS PIC fields so an
    // over-length value could not occur; the REST contract has no such bound, so an over-length string
    // would otherwise reach the VARCHAR column and surface as a DataIntegrityViolationException 500.
    // editMaxLength (validated on the TRIMMED value, since trimToNull is what gets persisted) adds one
    // message per over-length field into the SAME accumulate-then-throw cascade, so the value is a 400.
    // Each over-length field is also a detected change (1205-COMPARE-OLD-NEW), so the cascade is reached.
    // =================================================================================================

    @Nested
    @DisplayName("Group C.12 — editMaxLength field-width guards (QA F5)")
    class EditMaxLength {

        @Test
        @DisplayName("First Name > 25 (CUST-FIRST-NAME PIC X(25)) -> \"First Name can NOT be longer than 25 characters.\"")
        void firstNameTooLong() {
            AccountDto request = validRequest();
            request.setFirstName("A".repeat(26)); // all-alpha so only the length edit fails
            assertThat(errorsFor(request))
                    .containsExactly("First Name can NOT be longer than 25 characters.");
        }

        @Test
        @DisplayName("Address Line 1 > 50 (CUST-ADDR-LINE-1 PIC X(50)) -> \"Address Line 1 can NOT be longer than 50 characters.\"")
        void addressLine1TooLong() {
            AccountDto request = validRequest();
            request.setAddressLine1("A".repeat(51));
            assertThat(errorsFor(request))
                    .containsExactly("Address Line 1 can NOT be longer than 50 characters.");
        }

        @Test
        @DisplayName("Account Group Id > 10 (ACCT-GROUP-ID PIC X(10)) -> \"Account Group Id can NOT be longer than 10 characters.\"")
        void accountGroupIdTooLong() {
            AccountDto request = validRequest();
            request.setAccountGroupId("A".repeat(11)); // account-level persisted field, not in the BMS cascade
            assertThat(errorsFor(request))
                    .containsExactly("Account Group Id can NOT be longer than 10 characters.");
        }

        @Test
        @DisplayName("Government Issued Id > 20 (CUST-GOVT-ISSUED-ID PIC X(20)) -> \"Government Issued Id can NOT be longer than 20 characters.\"")
        void governmentIssuedIdTooLong() {
            AccountDto request = validRequest();
            request.setGovernmentIssuedId("0".repeat(21));
            assertThat(errorsFor(request))
                    .containsExactly("Government Issued Id can NOT be longer than 20 characters.");
        }

        @Test
        @DisplayName("boundary First Name (exactly 25) contributes NO length error (paired with the 1220 sentinel)")
        void boundaryFirstNameAccepted() {
            AccountDto request = validRequest();
            request.setFirstName("A".repeat(25)); // exactly at the PIC X(25) bound -> inclusive, valid
            request.setAccountStatus("Q");         // sentinel (1220) keeps the call on the throw path
            // Only the sentinel message is present: the 25-char first name produced no length error.
            assertThat(errorsFor(request)).containsExactly(SENTINEL_MSG);
        }
    }

    // =================================================================================================
    // GROUP D — record-not-found paths (READ ACCTDAT/CUSTDAT UPDATE -> findById). These fail before the
    // field-edit cascade; the account is read first, then the customer, so an absent account never reads
    // the customer. FILE STATUS '23' (NOTFND) -> RecordNotFoundException (HTTP 404).
    // =================================================================================================

    @Nested
    @DisplayName("Group D — not-found (account read first, then customer)")
    class RecordNotFound {

        @Test
        @DisplayName("absent account -> RecordNotFoundException(\"Account\", <original id>); customer never touched")
        void accountNotFound() {
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, validRequest()))
                    .isInstanceOf(RecordNotFoundException.class)
                    .satisfies(ex -> {
                        RecordNotFoundException rnf = (RecordNotFoundException) ex;
                        assertThat(rnf.getEntityType()).isEqualTo("Account");
                        assertThat(rnf.getKey()).isEqualTo(ACCOUNT_ID_INPUT);
                    });
            // The customer is read only AFTER the account is found; here it must never be touched.
            verify(customerRepository, never()).findById(any());
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("account present but absent customer -> RecordNotFoundException(\"Customer\", <custKey>)")
        void customerNotFound() {
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, validRequest()))
                    .isInstanceOf(RecordNotFoundException.class)
                    .satisfies(ex -> {
                        RecordNotFoundException rnf = (RecordNotFoundException) ex;
                        assertThat(rnf.getEntityType()).isEqualTo("Customer");
                        assertThat(rnf.getKey()).isEqualTo(String.valueOf(CUST_ID));
                    });
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).saveAndFlush(any());
        }
    }

    // =================================================================================================
    // GROUP E — no-change short-circuit (1205-COMPARE-OLD-NEW -> NO-CHANGES-DETECTED). When every
    // submitted value equals the fetched record, COBOL performs NO update. The service runs change
    // detection BEFORE the field-edit cascade, so on a no-change request neither validator is consulted
    // and neither dataset is rewritten. Money is compared with BigDecimal.compareTo (NOT the
    // scale-sensitive equals): the request balance "1234.56" equals the entity balance "1234.560".
    // =================================================================================================

    @Nested
    @DisplayName("Group E — no-change short-circuit (1205-COMPARE-OLD-NEW)")
    class NoChangeShortCircuit {

        @Test
        @DisplayName("identical values (balance differs only in scale) -> NO write, NO validation, DTO reflects record")
        void noChangeShortCircuitsWithoutWriting() {
            givenRecordsFound();
            AccountDto request = validRequest(); // every change-detection field equals the fetched record

            AccountDto result = accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request);

            // COBOL "no update performed": neither ACCTDAT nor CUSTDAT is rewritten.
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).saveAndFlush(any());
            // Change detection precedes validation, so on the no-change path no validator is consulted.
            verifyNoInteractions(dateValidationService, validationLookupService);
            // The redisplayed DTO reflects the unchanged record.
            assertThat(result).isNotNull();
            assertThat(result.getAccountId()).isEqualTo(ACCOUNT_ID_INPUT);
            assertThat(result.getAccountStatus()).isEqualTo("Y");
            assertThat(result.getSsn()).isEqualTo("020-97-3888");
            assertThat(result.getFicoScore()).isEqualTo("750");
            // Money: compareTo equality proves the "1234.56" request matched the "1234.560" record.
            assertThat(result.getCurrentBalance()).isEqualByComparingTo("1234.56");
        }
    }

    // =================================================================================================
    // GROUP F — successful dual write (happy path). A validated, changed request rewrites BOTH ACCTDAT
    // and CUSTDAT inside the one @Transactional unit (the COACTUPC dual REWRITE). Money is normalized to
    // scale 2 on the managed entity (decimal fidelity, AAP §0.7.3) and the redisplayed DTO re-reflects
    // the committed values (COACTUPC redisplay).
    // =================================================================================================

    @Nested
    @DisplayName("Group F — successful dual write (ACCTDAT + CUSTDAT REWRITE)")
    class SuccessfulDualWrite {

        @Test
        @DisplayName("valid change -> BOTH datasets saved; mutation lands at scale 2; DTO re-reflects state")
        void dualWriteOnValidChange() {
            givenRecordsFound();
            givenValidatorsPass();
            when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
            when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
            AccountDto request = validRequest();
            request.setCreditLimit(new BigDecimal("6000.00")); // a single real change vs the "5000.00" record

            AccountDto result = accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request);

            // Both datasets are rewritten in the one transaction (sole SYNCPOINT ROLLBACK site).
            ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).saveAndFlush(acctCaptor.capture());
            verify(customerRepository).saveAndFlush(any(Customer.class));
            // The validated mutation landed on the managed account at scale 2 (BigDecimal, never float/double).
            Account saved = acctCaptor.getValue();
            assertThat(saved.getAcctCreditLimit()).isEqualByComparingTo("6000.00");
            assertThat(saved.getAcctCreditLimit().scale()).isEqualTo(2);
            // The redisplayed DTO re-reflects the committed state (money scale-2, SSN dash-formatted,
            // date as LocalDate, FICO as String).
            assertThat(result.getCreditLimit()).isEqualByComparingTo("6000.00");
            assertThat(result.getSsn()).isEqualTo("020-97-3888");
            assertThat(result.getDateOfBirth()).isEqualTo(LocalDate.of(1961, 6, 8));
            assertThat(result.getFicoScore()).isEqualTo("750");
        }
    }

    // =================================================================================================
    // GROUP G — optimistic-concurrency -> com.cardemo.exception.ConcurrentModificationException (HTTP 409).
    // Two distinct guards: (G1) the persistence-provider ObjectOptimisticLockingFailureException thrown by
    // the account REWRITE is caught and rethrown as the typed 409; (G2) a stale client-supplied @Version
    // token is rejected BEFORE any write. Also asserts (reflectively) that updateAccount carries the
    // @Transactional(rollbackFor = Exception.class) that substitutes COACTUPC's SYNCPOINT ROLLBACK.
    // NOTE: the explicit import com.cardemo.exception.ConcurrentModificationException defeats the
    // java.util.ConcurrentModificationException name clash.
    // =================================================================================================

    @Nested
    @DisplayName("Group G — optimistic locking -> ConcurrentModificationException (409)")
    class OptimisticLocking {

        @Test
        @DisplayName("provider ObjectOptimisticLockingFailureException on save -> 409; customer never written")
        void optimisticLockOnSaveMappedToConflict() {
            givenRecordsFound();
            givenValidatorsPass();
            when(accountRepository.saveAndFlush(any(Account.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));
            AccountDto request = validRequest();
            request.setCreditLimit(new BigDecimal("6000.00")); // a real change so the write is reached

            assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("was modified by another user")
                    .satisfies(ex -> assertThat(((ConcurrentModificationException) ex).getEntityType())
                            .isEqualTo("Account"));
            // Account REWRITE failed first; the customer REWRITE must never run (rollback intent).
            verify(customerRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("stale client @Version token -> 409 BEFORE any write (neither dataset rewritten)")
        void staleClientVersionRejectedBeforeWrite() {
            givenRecordsFound();
            givenValidatorsPass();
            AccountDto request = validRequest();
            request.setCreditLimit(new BigDecimal("6000.00")); // a real change so the version pre-check runs
            request.setVersion(5L); // client echoes an older token than the fetched account (version 7)

            assertThatThrownBy(() -> accountUpdateService.updateAccount(ACCOUNT_ID_INPUT, request))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("was modified by another user")
                    .satisfies(ex -> assertThat(((ConcurrentModificationException) ex).getEntityType())
                            .isEqualTo("Account"));
            // The stale token is detected before PHASE 4, so no REWRITE occurs.
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("updateAccount is @Transactional(rollbackFor = Exception.class) — COACTUPC SYNCPOINT ROLLBACK parity")
        void updateAccountIsTransactionalWithRollbackForException() throws NoSuchMethodException {
            Transactional tx = AccountUpdateService.class
                    .getDeclaredMethod("updateAccount", String.class, AccountDto.class)
                    .getAnnotation(Transactional.class);
            assertThat(tx)
                    .as("updateAccount must be @Transactional to reproduce the dual-REWRITE SYNCPOINT ROLLBACK")
                    .isNotNull();
            assertThat(tx.rollbackFor()).contains(Exception.class);
        }
    }

}
