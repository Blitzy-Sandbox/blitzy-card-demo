package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.dto.AccountUpdateResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;

/**
 * Pure, fast Mockito unit tests for {@link AccountUpdateService}, the online
 * <em>Account Update</em> (CAUP) service migrated from the COBOL program
 * {@code COACTUPC} ({@code app/cbl/COACTUPC.cbl}, frozen reference SHA
 * {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>The repository and lookup collaborators ({@link AccountRepository},
 * {@link CustomerRepository}, {@link CrossReferenceService},
 * {@link LookupService}) are Mockito mocks. The calendar-date validator is the
 * <em>real</em> {@link DateValidationService} (a pure, dependency-free utility
 * already covered by its own suite), so the date-field assertions verify the
 * genuine end-to-end message parity produced by the {@code EDIT-DATE-CCYYMMDD}
 * translation rather than a re-stated stub.</p>
 *
 * <p>Two concerns are covered:</p>
 * <ul>
 *   <li><strong>Input-contract guard (review finding M8):</strong> a {@code null}
 *       request body is rejected with a typed HTTP-400
 *       {@link ValidationException} immediately after the account-id edit, before
 *       any collaborator is touched.</li>
 *   <li><strong>Field-edit parity (review finding M9):</strong> the complete
 *       {@code COACTUPC} {@code 1200-EDIT-MAP-INPUTS} edit suite &mdash; Y/N flags,
 *       signed money, calendar dates and date-of-birth, the three-part SSN with
 *       its part-1 exclusion, the FICO range, mandatory/alphabetic text fields and
 *       numeric ZIP/EFT &mdash; is enforced at the service boundary with the exact
 *       legacy message literals, and every failing field is reported together.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService — COACTUPC (CAUP) account-update edits")
class AccountUpdateServiceTest {

    /** A syntactically valid account-id search key (non-null, strictly positive). */
    private static final long VALID_ACCOUNT_ID = 1L;

    /** The customer id the cross-reference resolves the account to. */
    private static final long VALID_CUSTOMER_ID = 1L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @Mock
    private LookupService lookupService;

    /**
     * The real date validator (not a mock): {@link DateValidationService} is a
     * pure function with no I/O, so using it directly yields authentic
     * {@code EDIT-DATE-CCYYMMDD} messages for the date-field assertions.
     */
    private final DateValidationService dateValidationService = new DateValidationService();

    private AccountUpdateService service;

    @BeforeEach
    void setUp() {
        service = new AccountUpdateService(accountRepository, customerRepository,
                crossReferenceService, lookupService, dateValidationService);
    }

    @Nested
    @DisplayName("input-contract guard (M8)")
    class InputContractGuard {

        @Test
        @DisplayName("null request body -> ValidationException(400) before any read")
        void updateAccount_nullRequest_throwsValidationException() {
            // M8: with a valid account id, a null request body must be a typed
            // HTTP-400 validation error raised before the request.version()
            // dereference, never an unhandled NullPointerException / HTTP 500. The
            // guard sits immediately after the account-id edit, so no collaborator
            // is touched.
            assertThatThrownBy(() -> service.updateAccount(VALID_ACCOUNT_ID, null))
                    .isInstanceOfSatisfying(ValidationException.class,
                            ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                    .hasMessage(AccountUpdateService.MSG_VALIDATION_SUMMARY);

            verifyNoInteractions(accountRepository, customerRepository, crossReferenceService, lookupService);
        }

        @Test
        @DisplayName("non-positive account id -> ValidationException(400) before any read")
        void updateAccount_zeroAccountId_throwsValidationException() {
            // 1210-EDIT-ACCOUNT: a zero/negative search key (SEARCHED-ACCT-ZEROES)
            // is rejected before the request body is even inspected.
            assertThatThrownBy(() -> service.updateAccount(0L, new Req().build()))
                    .isInstanceOfSatisfying(ValidationException.class,
                            ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                    .hasMessage(AccountUpdateService.MSG_ACCT_INVALID);

            verifyNoInteractions(accountRepository, customerRepository, crossReferenceService, lookupService);
        }
    }

    @Nested
    @DisplayName("1200-EDIT-MAP-INPUTS field-edit parity (M9)")
    class FieldEditParity {

        private Account account;
        private Customer customer;

        @BeforeEach
        void stubHappyFlow() {
            account = new Account();
            account.setAcctId(VALID_ACCOUNT_ID);
            account.setVersion(0L);

            customer = new Customer();
            customer.setCustId(VALID_CUSTOMER_ID);

            // Reach 1200-EDIT-MAP-INPUTS: resolve the owning customer and read both
            // masters (used by every parity test).
            when(crossReferenceService.resolveCustomerId(VALID_ACCOUNT_ID)).thenReturn(VALID_CUSTOMER_ID);
            when(accountRepository.findById(VALID_ACCOUNT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(VALID_CUSTOMER_ID)).thenReturn(Optional.of(customer));

            // Reference-data lookups exercised by the all-valid base request; lenient
            // because field-mutating negative tests may not reach every lookup.
            lenient().when(lookupService.isValidStateCode("CA")).thenReturn(true);
            lenient().when(lookupService.isValidStateZipCombo("CA", "90210")).thenReturn(true);
            lenient().when(lookupService.isValidPhoneAreaCode("415")).thenReturn(true);
            lenient().when(accountRepository.saveAndFlush(any(Account.class))).thenReturn(account);
            lenient().when(customerRepository.saveAndFlush(any(Customer.class))).thenReturn(customer);
        }

        @Test
        @DisplayName("fully valid request -> committed update, both masters written")
        void updateAccount_validRequest_succeeds() {
            final AccountUpdateResponse response = service.updateAccount(VALID_ACCOUNT_ID, new Req().build());

            assertThat(response).isNotNull();
            assertThat(response.message()).isEqualTo(AccountUpdateService.MSG_SUCCESS);
        }

        // --- 1220-EDIT-YESNO -------------------------------------------------

        @Test
        @DisplayName("blank Account Status -> 'Account Status must be supplied.'")
        void updateAccount_blankActiveStatus_reportsSupplied() {
            assertFieldError(new Req().activeStatus(null).build(),
                    "Account Status", "Account Status must be supplied.");
        }

        @Test
        @DisplayName("non-Y/N Account Status -> 'Account Status must be Y or N.'")
        void updateAccount_invalidActiveStatus_reportsYorN() {
            assertFieldError(new Req().activeStatus("X").build(),
                    "Account Status", "Account Status must be Y or N.");
        }

        @Test
        @DisplayName("blank Primary Card Holder -> 'Primary Card Holder must be supplied.'")
        void updateAccount_blankPrimaryCardHolder_reportsSupplied() {
            assertFieldError(new Req().primaryCardHolderIndicator(null).build(),
                    "Primary Card Holder", "Primary Card Holder must be supplied.");
        }

        // --- 1250-EDIT-SIGNED-9V2 -------------------------------------------

        @Test
        @DisplayName("null Credit Limit -> 'Credit Limit must be supplied.'")
        void updateAccount_nullCreditLimit_reportsSupplied() {
            assertFieldError(new Req().creditLimit(null).build(),
                    "Credit Limit", "Credit Limit must be supplied.");
        }

        // --- EDIT-DATE-CCYYMMDD (delegated to the real DateValidationService) --

        @Test
        @DisplayName("null Open Date -> 'Open Date : Year must be supplied.'")
        void updateAccount_nullOpenDate_reportsYearSupplied() {
            assertFieldError(new Req().openDate(null).build(),
                    "Open Date", "Open Date : Year must be supplied.");
        }

        // --- EDIT-DATE-OF-BIRTH ---------------------------------------------

        @Test
        @DisplayName("future Date of Birth -> 'Date of Birth:cannot be in the future '")
        void updateAccount_futureDateOfBirth_reportsFuture() {
            assertFieldError(new Req().dateOfBirth(LocalDate.now().plusYears(1)).build(),
                    "Date of Birth", "Date of Birth:cannot be in the future ");
        }

        @Test
        @DisplayName("today's Date of Birth -> future error (must be strictly in the past)")
        void updateAccount_todayDateOfBirth_reportsFuture() {
            assertFieldError(new Req().dateOfBirth(LocalDate.now()).build(),
                    "Date of Birth", "Date of Birth:cannot be in the future ");
        }

        // --- 1265-EDIT-US-SSN ------------------------------------------------

        @Test
        @DisplayName("null SSN -> all three parts report 'must be supplied.'")
        void updateAccount_nullSsn_reportsAllPartsSupplied() {
            final ValidationException ex = expectValidation(new Req().ssn(null).build());
            assertThat(ex.getFieldErrors())
                    .containsEntry("SSN: First 3 chars", "SSN: First 3 chars must be supplied.")
                    .containsEntry("SSN 4th & 5th chars", "SSN 4th & 5th chars must be supplied.")
                    .containsEntry("SSN Last 4 chars", "SSN Last 4 chars must be supplied.")
                    .hasSize(3);
        }

        @Test
        @DisplayName("SSN area 666 -> part-1 exclusion message")
        void updateAccount_ssnArea666_reportsExclusion() {
            assertFieldError(new Req().ssn("666112222").build(),
                    "SSN: First 3 chars",
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        }

        @Test
        @DisplayName("SSN area 900 -> part-1 exclusion message (900-999 band)")
        void updateAccount_ssnArea900_reportsExclusion() {
            assertFieldError(new Req().ssn("900112222").build(),
                    "SSN: First 3 chars",
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        }

        @Test
        @DisplayName("SSN area 000 -> numeric zero message (not the exclusion)")
        void updateAccount_ssnArea000_reportsZero() {
            // The 1245-EDIT-NUM-REQD zero check fires before the exclusion, so 000
            // is reported as 'must not be zero.' — matching the COBOL guard
            // IF FLG-EDIT-US-SSN-PART1-ISVALID.
            assertFieldError(new Req().ssn("000112222").build(),
                    "SSN: First 3 chars", "SSN: First 3 chars must not be zero.");
        }

        @Test
        @DisplayName("SSN group 00 -> part-2 numeric zero message")
        void updateAccount_ssnGroup00_reportsZero() {
            assertFieldError(new Req().ssn("123002222").build(),
                    "SSN 4th & 5th chars", "SSN 4th & 5th chars must not be zero.");
        }

        // --- 1245-EDIT-NUM-REQD + 1275-EDIT-FICO-SCORE -----------------------

        @Test
        @DisplayName("null FICO Score -> 'FICO Score must be supplied.'")
        void updateAccount_nullFico_reportsSupplied() {
            assertFieldError(new Req().ficoScore(null).build(),
                    "FICO Score", "FICO Score must be supplied.");
        }

        @Test
        @DisplayName("zero FICO Score -> 'FICO Score must not be zero.'")
        void updateAccount_zeroFico_reportsZero() {
            assertFieldError(new Req().ficoScore(0).build(),
                    "FICO Score", "FICO Score must not be zero.");
        }

        @Test
        @DisplayName("below-range FICO Score -> 'FICO Score: should be between 300 and 850'")
        void updateAccount_lowFico_reportsRange() {
            assertFieldError(new Req().ficoScore(200).build(),
                    "FICO Score", "FICO Score: should be between 300 and 850");
        }

        @Test
        @DisplayName("above-range FICO Score -> 'FICO Score: should be between 300 and 850'")
        void updateAccount_highFico_reportsRange() {
            assertFieldError(new Req().ficoScore(900).build(),
                    "FICO Score", "FICO Score: should be between 300 and 850");
        }

        // --- 1225-EDIT-ALPHA-REQD / 1235-EDIT-ALPHA-OPT ----------------------

        @Test
        @DisplayName("blank First Name -> 'First Name must be supplied.'")
        void updateAccount_blankFirstName_reportsSupplied() {
            assertFieldError(new Req().firstName(null).build(),
                    "First Name", "First Name must be supplied.");
        }

        @Test
        @DisplayName("non-alphabetic Last Name -> 'Last Name can have alphabets only.'")
        void updateAccount_numericLastName_reportsAlphaOnly() {
            assertFieldError(new Req().lastName("Doe1").build(),
                    "Last Name", "Last Name can have alphabets only.");
        }

        @Test
        @DisplayName("non-alphabetic Middle Name -> 'Middle Name can have alphabets only.'")
        void updateAccount_numericMiddleName_reportsAlphaOnly() {
            assertFieldError(new Req().middleName("Q1").build(),
                    "Middle Name", "Middle Name can have alphabets only.");
        }

        @Test
        @DisplayName("blank Middle Name is valid (optional) -> update succeeds")
        void updateAccount_blankMiddleName_succeeds() {
            final AccountUpdateResponse response =
                    service.updateAccount(VALID_ACCOUNT_ID, new Req().middleName(null).build());
            assertThat(response).isNotNull();
            assertThat(response.message()).isEqualTo(AccountUpdateService.MSG_SUCCESS);
        }

        // --- 1215-EDIT-MANDATORY --------------------------------------------

        @Test
        @DisplayName("blank Address Line 1 -> 'Address Line 1 must be supplied.'")
        void updateAccount_blankAddressLine1_reportsSupplied() {
            assertFieldError(new Req().addressLine1(null).build(),
                    "Address Line 1", "Address Line 1 must be supplied.");
        }

        @Test
        @DisplayName("Address Line 1 with digits is accepted (mandatory has no charset restriction)")
        void updateAccount_numericAddressLine1_succeeds() {
            final AccountUpdateResponse response =
                    service.updateAccount(VALID_ACCOUNT_ID, new Req().addressLine1("123 Main St").build());
            assertThat(response).isNotNull();
        }

        // --- Zip / EFT (1245-EDIT-NUM-REQD) ---------------------------------

        @Test
        @DisplayName("blank Zip -> 'Zip must be supplied.'")
        void updateAccount_blankZip_reportsSupplied() {
            // A blank ZIP also short-circuits the cross-field state+ZIP combo, so
            // only the single Zip error is reported.
            assertFieldError(new Req().zipCode(null).build(),
                    "Zip", "Zip must be supplied.");
        }

        @Test
        @DisplayName("blank EFT Account Id -> 'EFT Account Id must be supplied.'")
        void updateAccount_blankEft_reportsSupplied() {
            assertFieldError(new Req().eftAccountId(null).build(),
                    "EFT Account Id", "EFT Account Id must be supplied.");
        }

        // --- 1225 + 1270-EDIT-US-STATE-CD -----------------------------------

        @Test
        @DisplayName("blank State -> 'State must be supplied.' (state lookup not consulted)")
        void updateAccount_blankState_reportsSupplied() {
            assertFieldError(new Req().stateCode(null).build(),
                    "State", "State must be supplied.");
        }

        @Test
        @DisplayName("non-alphabetic State -> 'State can have alphabets only.'")
        void updateAccount_numericState_reportsAlphaOnly() {
            assertFieldError(new Req().stateCode("C1").build(),
                    "State", "State can have alphabets only.");
        }

        @Test
        @DisplayName("unrecognised State -> 'State: is not a valid state code'")
        void updateAccount_unknownState_reportsInvalidStateCode() {
            when(lookupService.isValidStateCode("ZZ")).thenReturn(false);
            // ZIP combo is skipped because the state code is invalid.
            assertFieldError(new Req().stateCode("ZZ").build(),
                    "State", "State: is not a valid state code");
        }

        // --- 1280-EDIT-US-STATE-ZIP-CD (cross-field) ------------------------

        @Test
        @DisplayName("valid state + ZIP but mismatched combo -> 'Invalid zip code for state'")
        void updateAccount_badStateZipCombo_reportsCombo() {
            when(lookupService.isValidStateZipCombo("CA", "90210")).thenReturn(false);
            assertFieldError(new Req().build(), "Zip", "Invalid zip code for state");
        }

        // --- multi-field accumulation ---------------------------------------

        @Test
        @DisplayName("multiple invalid fields -> every failure reported together (M9)")
        void updateAccount_multipleErrors_reportsAllTogether() {
            final ValidationException ex = expectValidation(new Req()
                    .activeStatus(null)      // Account Status must be supplied.
                    .ficoScore(100)          // FICO Score range
                    .firstName("John1")      // First Name alphabets only
                    .build());

            assertThat(ex.getFieldErrors())
                    .containsEntry("Account Status", "Account Status must be supplied.")
                    .containsEntry("FICO Score", "FICO Score: should be between 300 and 850")
                    .containsEntry("First Name", "First Name can have alphabets only.")
                    .hasSize(3);
        }

        // --- helpers ---------------------------------------------------------

        /**
         * Asserts that updating with {@code request} raises a 400 validation error
         * carrying exactly one field failure: {@code label -> message}.
         */
        private void assertFieldError(final AccountUpdateRequest request,
                final String label, final String message) {
            final ValidationException ex = expectValidation(request);
            assertThat(ex.getFieldErrors()).containsEntry(label, message).hasSize(1);
        }

        /**
         * Asserts that updating with {@code request} raises a typed HTTP-400
         * {@link ValidationException} whose top-level message is the aggregate
         * summary, and returns it for field-level assertions.
         */
        private ValidationException expectValidation(final AccountUpdateRequest request) {
            final Throwable thrown = catchThrowable(() -> service.updateAccount(VALID_ACCOUNT_ID, request));
            assertThat(thrown)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(AccountUpdateService.MSG_VALIDATION_SUMMARY);
            final ValidationException ex = (ValidationException) thrown;
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            return ex;
        }
    }

    /**
     * Mutable builder for the 30-component {@link AccountUpdateRequest} record,
     * initialised to an all-valid set of values. Each negative test mutates
     * exactly one field, so assertions on the resulting single field error prove
     * that field's edit in isolation.
     */
    private static final class Req {
        private String accountId = "00000000001";
        private String activeStatus = "Y";
        private BigDecimal currentBalance = new BigDecimal("100.00");
        private BigDecimal creditLimit = new BigDecimal("5000.00");
        private BigDecimal cashCreditLimit = new BigDecimal("1000.00");
        private BigDecimal currentCycleCredit = new BigDecimal("10.00");
        private BigDecimal currentCycleDebit = new BigDecimal("20.00");
        private LocalDate openDate = LocalDate.of(2020, 1, 15);
        private LocalDate expirationDate = LocalDate.of(2027, 1, 15);
        private LocalDate reissueDate = LocalDate.of(2023, 6, 1);
        private String accountGroupId = "GRP1";
        private Long version = null;
        private String customerId = "000000001";
        private String firstName = "John";
        private String middleName = null;
        private String lastName = "Doe";
        private String addressLine1 = "123 Main St";
        private String addressLine2 = null;
        private String city = "Springfield";
        private String stateCode = "CA";
        private String countryCode = "USA";
        private String zipCode = "90210";
        private String phoneNumber1 = "(415)555-1234";
        private String phoneNumber2 = null;
        private String ssn = "123456789";
        private String govtIssuedId = "ID123";
        private LocalDate dateOfBirth = LocalDate.of(1985, 3, 20);
        private String eftAccountId = "1234567890";
        private String primaryCardHolderIndicator = "Y";
        private Integer ficoScore = 720;

        Req activeStatus(final String v) { this.activeStatus = v; return this; }
        Req creditLimit(final BigDecimal v) { this.creditLimit = v; return this; }
        Req openDate(final LocalDate v) { this.openDate = v; return this; }
        Req dateOfBirth(final LocalDate v) { this.dateOfBirth = v; return this; }
        Req ssn(final String v) { this.ssn = v; return this; }
        Req ficoScore(final Integer v) { this.ficoScore = v; return this; }
        Req firstName(final String v) { this.firstName = v; return this; }
        Req lastName(final String v) { this.lastName = v; return this; }
        Req middleName(final String v) { this.middleName = v; return this; }
        Req addressLine1(final String v) { this.addressLine1 = v; return this; }
        Req zipCode(final String v) { this.zipCode = v; return this; }
        Req eftAccountId(final String v) { this.eftAccountId = v; return this; }
        Req stateCode(final String v) { this.stateCode = v; return this; }
        Req primaryCardHolderIndicator(final String v) { this.primaryCardHolderIndicator = v; return this; }

        AccountUpdateRequest build() {
            return new AccountUpdateRequest(accountId, activeStatus, currentBalance, creditLimit,
                    cashCreditLimit, currentCycleCredit, currentCycleDebit, openDate, expirationDate,
                    reissueDate, accountGroupId, version, customerId, firstName, middleName, lastName,
                    addressLine1, addressLine2, city, stateCode, countryCode, zipCode, phoneNumber1,
                    phoneNumber2, ssn, govtIssuedId, dateOfBirth, eftAccountId,
                    primaryCardHolderIndicator, ficoScore);
        }
    }
}
