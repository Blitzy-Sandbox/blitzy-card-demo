package com.cardemo.unit.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.account.AccountViewService;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fast, fully-mocked unit tests for {@link AccountViewService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program {@code app/cbl/COACTVWC.cbl}
 * (transaction {@code CAVW}, BMS mapset {@code COACTVW}). The service resolves a single account by
 * id and returns the joined account + customer view the legacy 3270 screen displayed.
 *
 * <h2>What these tests pin down (COACTVWC behavioral parity &mdash; AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li><strong>{@code 2210-EDIT-ACCOUNT} key edit.</strong> A blank/empty/null key is rejected
 *       with the verbatim {@code COACTVWC} literal {@code "Account number not provided"}
 *       ({@code COACTVWC.cbl} L122) and a non-numeric or all-zero key with
 *       {@code "Account number must be a non zero 11 digit number"} ({@code COACTVWC.cbl}
 *       L126/L128); both surface on field {@code "Account Number"}. The edit runs <em>before</em>
 *       any read, so no repository is touched on a validation failure.</li>
 *   <li><strong>{@code 9000-READ-ACCT} three-step keyed read.</strong> The reads happen in the exact
 *       order cross-reference &rarr; account &rarr; customer ({@code 9200}/{@code 9300}/{@code 9400}),
 *       each short-circuiting on its own not-found, and the customer id is taken <em>from the
 *       cross-reference</em> ({@code XREF-CUST-ID}), never from the account.</li>
 *   <li><strong>{@code 1200-SETUP-SCREEN-VARS} screen assembly.</strong> Every mapped field is
 *       populated: ids zero-padded to their picture widths ({@code PIC 9(11)} / {@code PIC 9(09)}),
 *       the 9-digit SSN dash-formatted to {@code XXX-XX-XXXX}, the FICO score rendered as text, the
 *       ZIP truncated to five characters, {@code CUST-ADDR-LINE-3} mapped to {@code city}, and the
 *       account {@code @Version} carried as the optimistic-lock token.</li>
 * </ul>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>Every monetary amount is a {@link BigDecimal} and is asserted with
 * {@code isEqualByComparingTo} (which uses {@link BigDecimal#compareTo(BigDecimal)}), <strong>never</strong>
 * the scale-sensitive {@link BigDecimal#equals(Object)}. There is no {@code float}/{@code double}
 * anywhere in this class. {@link DecimalFidelity#viewAccount_moneyComparedByValueNotScale()} proves
 * the comparison is value-based by feeding a scale-3 entity amount and matching a scale-2 expectation.</p>
 *
 * <h2>Test mechanics</h2>
 * <p>These tests are millisecond, in-memory and pure-mock: no Spring context, no Testcontainers, no
 * database, no network and no filesystem. The three repositories are {@link Mock @Mock}s injected
 * into the service via {@link InjectMocks @InjectMocks} (constructor injection). Mockito runs under
 * its default {@code STRICT_STUBS} strictness via {@link MockitoExtension}, so each test stubs only
 * what it exercises (validation and short-circuit cases stub nothing downstream).</p>
 *
 * <p><strong>Traceability.</strong> Reproduces behavior from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}; the COBOL is read-only reference and is never copied into this repository.</p>
 *
 * @see AccountViewService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService (COACTVWC / CAVW) — behavioral-parity unit tests")
class AccountViewServiceTest {

    /** Account key fixture: input {@code "00000000001"} parses to {@code 1L} (decoded from acctdata). */
    private static final Long ACCT_ID = 1L;
    /** Customer key fixture: customer {@code 1} owns account {@code 1} (cardxref xrefCustId/xrefAcctId). */
    private static final Long CUST_ID = 1L;
    /** A DISTINCT customer id used to prove the customer is resolved from the xref, not the account. */
    private static final Long DISTINCT_CUST_ID = 777L;
    /** The 11-character account identifier as received on the request path ({@code ACCTSID PIC X(11)}). */
    private static final String ACCOUNT_ID_INPUT = "00000000001";
    /** The account id rendered onto the DTO: {@code padId(1, 11)} ({@code ACCT-ID PIC 9(11)}). */
    private static final String EXPECTED_ACCOUNT_ID = "00000000001";
    /** The customer id rendered onto the DTO: {@code padId(1, 9)} ({@code CUST-ID PIC 9(09)}). */
    private static final String EXPECTED_CUSTOMER_ID = "000000001";
    /** The committed optimistic-lock token of the fetched account ({@code @Version}). */
    private static final long ENTITY_VERSION = 7L;

    /** Verbatim {@code COACTVWC} L122 "not supplied" prompt the service emits for a blank key. */
    private static final String MSG_NOT_PROVIDED = "Account number not provided";
    /** Verbatim {@code COACTVWC} L126/L128 prompt the service emits for a non-numeric or zero key. */
    private static final String MSG_NON_ZERO_11 = "Account number must be a non zero 11 digit number";
    /** The field name the service tags onto both edit rejections ({@code ACCTSID}). */
    private static final String FIELD_ACCOUNT_NUMBER = "Account Number";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @InjectMocks
    private AccountViewService service;

    /** The account "master record" returned by {@code accountRepository.findById} on the happy path. */
    private Account account;
    /** The customer "master record" returned by {@code customerRepository.findById} on the happy path. */
    private Customer customer;
    /** The cross-reference row returned by {@code cardCrossReferenceRepository.findByXrefAcctId}. */
    private CardCrossReference xref;

    @BeforeEach
    void setUp() {
        // Build fresh POJO fixtures only — NO stubbing here, so STRICT_STUBS never flags a test that
        // does not exercise a given collaborator (validation / short-circuit cases stub nothing).
        account = buildAccount();
        customer = buildCustomer();
        xref = buildXref();
    }

    // =================================================================================================
    // Fixture builders. Canonical golden values are decoded from app/data/ASCII/{acctdata,custdata,
    // cardxref}.txt (customer 1 = "Immanuel Kessler", account 1). The mocks fully control them, so the
    // tests stay deterministic and sub-millisecond. Each builder returns a fresh, fully-populated entity
    // so an individual test can tweak exactly one field.
    // =================================================================================================

    /**
     * The fetched {@link Account} master record. All five money fields are scale&nbsp;2
     * ({@code S9(10)V99}); the three dates are {@link LocalDate} (the entity already holds parsed
     * dates); {@code addressZip} is set to a DISTINCT value so the happy-path test can prove the DTO
     * {@code zipCode} comes from the <em>customer</em> record, not the account.
     */
    private Account buildAccount() {
        Account a = new Account();
        a.setAcctId(ACCT_ID);
        a.setAcctActiveStatus("Y");
        a.setAcctCurrBal(new BigDecimal("1234.56"));
        a.setAcctCreditLimit(new BigDecimal("5000.00"));
        a.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        a.setAcctCurrCycCredit(new BigDecimal("300.00"));
        a.setAcctCurrCycDebit(new BigDecimal("150.00"));
        a.setAcctOpenDate(LocalDate.of(2014, 11, 20));
        a.setAcctExpirationDate(LocalDate.of(2025, 5, 20));
        a.setAcctReissueDate(LocalDate.of(2025, 5, 20));
        a.setAcctAddrZip("99999");
        a.setAcctGroupId("GROUP01");
        a.setVersion(ENTITY_VERSION);
        return a;
    }

    /**
     * The fetched {@link Customer} master record. SSN is the raw 9-digit {@link Long} {@code 20973888}
     * (a deliberate leading-zero case: {@code %09d} &rarr; {@code "020973888"} &rarr; dash-formatted
     * {@code "020-97-3888"}), the FICO score is an {@link Integer}, and the date of birth is a
     * {@link LocalDate}. {@code addressLine3} ({@code "Altenwerthshire"}) becomes the DTO {@code city}.
     */
    private Customer buildCustomer() {
        Customer c = new Customer();
        c.setCustId(CUST_ID);
        c.setCustFirstName("Immanuel");
        c.setCustMiddleName("Madeline");
        c.setCustLastName("Kessler");
        c.setCustAddrLine1("618 Deshaun Route");
        c.setCustAddrLine2("Apt. 802");
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
        c.setCustFicoCreditScore(274);
        return c;
    }

    /** The cross-reference row mapping account {@code 1} to customer {@code 1} (CXACAIX alternate index). */
    private CardCrossReference buildXref() {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum("0500024453765740");
        x.setXrefCustId(CUST_ID);
        x.setXrefAcctId(ACCT_ID);
        return x;
    }

    // =============================================================================================
    // Group A — Happy path & full DTO assembly (COACTVWC 1200-SETUP-SCREEN-VARS).
    // =============================================================================================

    @Nested
    @DisplayName("1200-SETUP-SCREEN-VARS — happy path & full DTO assembly")
    class HappyPathAndDtoAssembly {

        @Test
        @DisplayName("returns a fully-populated DTO mapping every account & customer field")
        void viewAccount_returnsFullyPopulatedDto() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            AccountDto dto = service.viewAccount(ACCOUNT_ID_INPUT);

            assertThat(dto).isNotNull();

            // --- ids: zero-padded to the COBOL picture widths (ACCT-ID 9(11) / CUST-ID 9(09)) ---
            assertThat(dto.getAccountId()).isEqualTo(EXPECTED_ACCOUNT_ID);
            assertThat(dto.getCustomerId()).isEqualTo(EXPECTED_CUSTOMER_ID);

            // --- account scalar fields ---
            assertThat(dto.getAccountStatus()).isEqualTo("Y");
            assertThat(dto.getAccountGroupId()).isEqualTo("GROUP01");

            // --- dates carried through as LocalDate (entity already holds parsed dates) ---
            assertThat(dto.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
            assertThat(dto.getExpirationDate()).isEqualTo(LocalDate.of(2025, 5, 20));
            assertThat(dto.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));

            // --- money: compareTo semantics (never equals); pass-through preserves scale 2 ---
            assertThat(dto.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(dto.getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
            assertThat(dto.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("2000.00"));
            assertThat(dto.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(dto.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("150.00"));
            // scale is preserved at 2 because the service passes the scale-2 entity value straight
            // through (S9(10)V99 -> BigDecimal scale 2); this locks the decimal-precision contract.
            assertThat(dto.getCurrentBalance().scale()).isEqualTo(2);
            assertThat(dto.getCreditLimit().scale()).isEqualTo(2);
            assertThat(dto.getCashCreditLimit().scale()).isEqualTo(2);
            assertThat(dto.getCurrentCycleCredit().scale()).isEqualTo(2);
            assertThat(dto.getCurrentCycleDebit().scale()).isEqualTo(2);

            // --- customer demographics ---
            assertThat(dto.getFirstName()).isEqualTo("Immanuel");
            assertThat(dto.getMiddleName()).isEqualTo("Madeline");
            assertThat(dto.getLastName()).isEqualTo("Kessler");
            assertThat(dto.getAddressLine1()).isEqualTo("618 Deshaun Route");
            assertThat(dto.getAddressLine2()).isEqualTo("Apt. 802");
            // CUST-ADDR-LINE-3 maps to the DTO city (ACSCITYO), exactly as COACTVWC moved it.
            assertThat(dto.getCity()).isEqualTo("Altenwerthshire");
            assertThat(dto.getState()).isEqualTo("NC");
            assertThat(dto.getCountryCode()).isEqualTo("USA");
            // zipCode comes from the CUSTOMER record (truncated to 5), not the account's "99999".
            assertThat(dto.getZipCode()).isEqualTo("12546");
            assertThat(dto.getPhoneNumber1()).isEqualTo("(908)119-8310");
            assertThat(dto.getPhoneNumber2()).isEqualTo("(373)693-8684");
            // SSN: stored 9-digit Long 20973888 -> dash-formatted XXX-XX-XXXX (leading-zero case).
            assertThat(dto.getSsn()).isEqualTo("020-97-3888");
            assertThat(dto.getGovernmentIssuedId()).isEqualTo("00000000000049368437");
            assertThat(dto.getDateOfBirth()).isEqualTo(LocalDate.of(1961, 6, 8));
            assertThat(dto.getEftAccountId()).isEqualTo("0053581756");
            assertThat(dto.getPrimaryCardHolderIndicator()).isEqualTo("Y");
            // FICO: numeric Integer 274 rendered as text "274".
            assertThat(dto.getFicoScore()).isEqualTo("274");

            // --- optimistic-lock token carried from the account @Version (AAP §0.7.5) ---
            assertThat(dto.getVersion()).isEqualTo(ENTITY_VERSION);
        }

        @Test
        @DisplayName("resolves the customer from the cross-reference (XREF-CUST-ID), not the account")
        void viewAccount_usesCustomerIdFromCrossReference_notAccount() {
            // The xref points at a DISTINCT customer id (777), different from the account id (1).
            xref.setXrefCustId(DISTINCT_CUST_ID);
            Customer other = buildCustomer();
            other.setCustId(DISTINCT_CUST_ID);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(DISTINCT_CUST_ID)).thenReturn(Optional.of(other));

            AccountDto dto = service.viewAccount(ACCOUNT_ID_INPUT);

            // The customer master is read with the id taken FROM THE XREF (777), never the account id.
            verify(customerRepository).findById(DISTINCT_CUST_ID);
            assertThat(dto.getCustomerId()).isEqualTo("000000777");
        }

        @Test
        @DisplayName("a null SSN maps to a null DTO ssn without throwing (formatSsn null-guard)")
        void viewAccount_ssnNull_mapsToNullAndDoesNotThrow() {
            customer.setCustSsn(null);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            AccountDto dto = service.viewAccount(ACCOUNT_ID_INPUT);

            assertThat(dto.getSsn()).isNull();
        }

        @Test
        @DisplayName("a null entity date is carried through as a null DTO date (no parsing, no throw)")
        void viewAccount_nullEntityDate_mapsToNull() {
            account.setAcctOpenDate(null);
            customer.setCustDobYyyyMmDd(null);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            AccountDto dto = service.viewAccount(ACCOUNT_ID_INPUT);

            assertThat(dto.getOpenDate()).isNull();
            assertThat(dto.getDateOfBirth()).isNull();
            // a non-null sibling date still maps, proving only the null field becomes null.
            assertThat(dto.getExpirationDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        }
    }

    // =============================================================================================
    // Group A (decimal fidelity) — money compared by value, not scale (AAP §0.7.3).
    // =============================================================================================

    @Nested
    @DisplayName("decimal fidelity — BigDecimal compared by value, never by scale-sensitive equals")
    class DecimalFidelity {

        @Test
        @DisplayName("a scale-3 entity balance still matches a scale-2 expectation (compareTo, not equals)")
        void viewAccount_moneyComparedByValueNotScale() {
            // Entity balance stored at scale 3; the service carries it through unchanged. The
            // assertion uses isEqualByComparingTo (compareTo), so "1234.560" equals "1234.56" by
            // value despite the differing scale — exactly the rule the equals() pitfall would break.
            account.setAcctCurrBal(new BigDecimal("1234.560"));
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            AccountDto dto = service.viewAccount(ACCOUNT_ID_INPUT);

            assertThat(dto.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(dto.getCurrentBalance()).isEqualByComparingTo("1234.560");
        }
    }

    // =============================================================================================
    // Group B — Read-chain ordering & not-found (COACTVWC 9000-READ-ACCT). The three reads happen in
    // the exact order xref -> account -> customer, each short-circuiting on its own NOTFND. Each step
    // raises RecordNotFoundException carrying the verbatim COACTVWC client-safe prompt.
    // =============================================================================================

    @Nested
    @DisplayName("9000-READ-ACCT — three-step keyed read ordering & RecordNotFoundException")
    class ReadChainOrderingAndNotFound {

        @Test
        @DisplayName("empty cross-reference -> RecordNotFound (xref); account & customer never read")
        void viewAccount_crossReferenceNotFound_throwsRecordNotFound() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

            Throwable thrown = catchThrowable(() -> service.viewAccount(ACCOUNT_ID_INPUT));

            assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
            RecordNotFoundException rnf = (RecordNotFoundException) thrown;
            assertThat(rnf.getEntityType()).isEqualTo("CardCrossReference");
            assertThat(rnf.getKey()).isEqualTo("1");
            assertThat(rnf.getClientSafeMessage())
                    .isEqualTo("Did not find this account in account card xref file");
            assertThat(rnf.getMessage()).isEqualTo("CardCrossReference not found for key: 1");
            // The xref is checked FIRST and short-circuits: neither downstream master is read.
            verifyNoInteractions(accountRepository, customerRepository);
        }

        @Test
        @DisplayName("account master absent -> RecordNotFound (account); customer never read")
        void viewAccount_accountNotFound_throwsRecordNotFound() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> service.viewAccount(ACCOUNT_ID_INPUT));

            assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
            RecordNotFoundException rnf = (RecordNotFoundException) thrown;
            assertThat(rnf.getEntityType()).isEqualTo("Account");
            assertThat(rnf.getKey()).isEqualTo("1");
            assertThat(rnf.getClientSafeMessage())
                    .isEqualTo("Did not find this account in account master file");
            // The account is checked BEFORE the customer: the customer master is never read.
            verifyNoInteractions(customerRepository);
        }

        @Test
        @DisplayName("customer master absent -> RecordNotFound keyed by the xref customer id")
        void viewAccount_customerNotFound_throwsRecordNotFound() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> service.viewAccount(ACCOUNT_ID_INPUT));

            assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
            RecordNotFoundException rnf = (RecordNotFoundException) thrown;
            assertThat(rnf.getEntityType()).isEqualTo("Customer");
            // The key is the customer id resolved FROM THE XREF, not the account id.
            assertThat(rnf.getKey()).isEqualTo(String.valueOf(CUST_ID));
            assertThat(rnf.getClientSafeMessage())
                    .isEqualTo("Did not find associated customer in master file");
        }
    }

    // =============================================================================================
    // Group C — Account-key validation (COACTVWC 2210-EDIT-ACCOUNT). The edit runs BEFORE any read,
    // so no repository is touched on a validation failure (parity with editing inputs before
    // 9000-READ-ACCT). Messages are the verbatim COACTVWC literals (AAP §0.7.2).
    // =============================================================================================

    @Nested
    @DisplayName("2210-EDIT-ACCOUNT — account-key edit before any read (verbatim COACTVWC messages)")
    class AccountKeyValidation {

        @ParameterizedTest(name = "blank/empty/null key [{0}] -> \"Account number not provided\"")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "        "})
        void viewAccount_blankId_throwsNotProvided(String input) {
            Throwable thrown = catchThrowable(() -> service.viewAccount(input));

            assertThat(thrown).isInstanceOf(ValidationException.class);
            ValidationException ve = (ValidationException) thrown;
            assertThat(ve.getMessage()).isEqualTo(MSG_NOT_PROVIDED);
            assertThat(ve.getFieldName()).isEqualTo(FIELD_ACCOUNT_NUMBER);
            assertThat(ve.getValidationErrors()).containsExactly(MSG_NOT_PROVIDED);
            // 2210-EDIT-ACCOUNT runs before 9000-READ-ACCT: no repository is touched.
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository, customerRepository);
        }

        @ParameterizedTest(name = "non-numeric/zero/over-width key [{0}] -> \"...non zero 11 digit number\"")
        // "123456789012" (12 digits) is the QA INFO over-width case: it is all-digits and non-zero, so it
        // previously slipped past this edit, parsed to a Long and returned a misleading 404 from the keyed
        // read. The length > 11 guard now rejects it here with the same verbatim literal (a 400).
        @ValueSource(strings = {"abc", "ABC123", "12A45", "00000000000", "0", "-1", "123456789012"})
        void viewAccount_nonNumericOrZeroId_throwsElevenDigitNonZero(String input) {
            Throwable thrown = catchThrowable(() -> service.viewAccount(input));

            assertThat(thrown).isInstanceOf(ValidationException.class);
            ValidationException ve = (ValidationException) thrown;
            assertThat(ve.getMessage()).isEqualTo(MSG_NON_ZERO_11);
            assertThat(ve.getFieldName()).isEqualTo(FIELD_ACCOUNT_NUMBER);
            assertThat(ve.getValidationErrors()).containsExactly(MSG_NON_ZERO_11);
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository, customerRepository);
        }
    }

    // =============================================================================================
    // Group D — No mutation / read-only (COACTVWC is a pure inquiry: only READs, never a REWRITE;
    // the service is @Transactional(readOnly = true)).
    // =============================================================================================

    @Nested
    @DisplayName("read-only inquiry — only READs, never a save/saveAll on any repository")
    class ReadOnlyNoMutation {

        @Test
        @DisplayName("a successful view performs only reads — no save/saveAll on any repository")
        void viewAccount_performsOnlyReads_noWrites() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

            service.viewAccount(ACCOUNT_ID_INPUT);

            // No persistence mutation occurs anywhere — the inquiry is strictly read-only.
            verify(accountRepository, never()).save(any());
            verify(accountRepository, never()).saveAll(any());
            verify(customerRepository, never()).save(any());
            verify(customerRepository, never()).saveAll(any());
            verify(cardCrossReferenceRepository, never()).save(any());
            verify(cardCrossReferenceRepository, never()).saveAll(any());
        }
    }
}
