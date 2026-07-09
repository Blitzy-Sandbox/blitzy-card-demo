package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.dto.AccountViewResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;

/**
 * Pure, fast Mockito unit test for {@link AccountViewService}, the account-view
 * service (online transaction {@code CAVW}) migrated from the COBOL/CICS program
 * {@code COACTVWC} ({@code app/cbl/COACTVWC.cbl}, frozen reference commit SHA
 * {@code 27d6c6f} - read-only, not copied into this repository).
 *
 * <p>All three collaborators ({@link AccountRepository},
 * {@link CustomerRepository}, {@link CrossReferenceService}) are Mockito mocks
 * and the service is created through constructor injection, so the suite loads
 * <strong>no</strong> Spring context and touches no database, Testcontainers,
 * Docker, or live AWS. Every branch of
 * {@link AccountViewService#viewAccount(Long)} is exercised, feeding the JaCoCo
 * line-coverage gate (Gate&nbsp;8).</p>
 *
 * <h2>Behavioural-parity anchors (vs. {@code COACTVWC} @ SHA {@code 27d6c6f})</h2>
 * <p>The tests reproduce, in order, the COBOL {@code 9000-READ-ACCT} driver and
 * its guarded paragraph reads (source lines 649&ndash;870):</p>
 * <ol>
 *   <li>{@code 2210-EDIT-ACCOUNT} - the non-zero, 11-digit numeric id edit
 *       (the {@code SEARCHED-ACCT-ZEROES} / {@code SEARCHED-ACCT-NOT-NUMERIC}
 *       88-levels, which share one message).</li>
 *   <li>{@code 9200-GETCARDXREF-BYACCT} - the cross-reference navigation whose
 *       {@code DFHRESP(NOTFND)} branch ({@code DID-NOT-FIND-ACCT-IN-CARDXREF})
 *       is delegated to {@link CrossReferenceService#resolveCustomerId(Long)}.</li>
 *   <li>{@code 9300-GETACCTDATA-BYACCT} - the keyed account-master read
 *       ({@code DID-NOT-FIND-ACCT-IN-ACCTDAT}).</li>
 *   <li>{@code 9400-GETCUSTDATA-BYCUST} - the keyed customer-master read
 *       ({@code DID-NOT-FIND-CUST-IN-CUSTDAT}).</li>
 * </ol>
 * <p>The four verbatim operator messages are declared here as literals (rather
 * than referencing the production {@code AccountViewService.MSG_*} constants) so
 * the suite fails if the externally visible text ever drifts from the legacy
 * contract (Gate&nbsp;5, byte-for-byte message parity).</p>
 *
 * <h2>Decimal and PII fidelity</h2>
 * <ul>
 *   <li><strong>Money (AAP&nbsp;&sect;0.8.2):</strong> the five
 *       {@code PIC S9(10)V99} fields are asserted with {@code isEqualByComparingTo}
 *       (value equality independent of representation) <em>and</em>
 *       {@code scale() == 2}; signed and unnormalized inputs prove the scale-2
 *       guarantee. No {@code float}/{@code double} appears anywhere in the suite.</li>
 *   <li><strong>City quirk:</strong> the screen city ({@code ACSCITY}) is record
 *       address line&nbsp;3, so {@link AccountViewResponse#city()} must equal the
 *       customer's {@code custAddrLine3}.</li>
 *   <li><strong>SSN quirk:</strong> the service renders {@code CUST-SSN PIC 9(09)}
 *       via {@code String.format("%09d", ...)}, then the {@link AccountViewResponse}
 *       canonical constructor masks all but the last four digits (decision log
 *       D-023). Both the nine-digit zero-padding and the masking are asserted.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService - COBOL COACTVWC / CAVW account view (SHA 27d6c6f)")
class AccountViewServiceTest {

    /**
     * Verbatim id-edit message, shared by the {@code SEARCHED-ACCT-ZEROES} and
     * {@code SEARCHED-ACCT-NOT-NUMERIC} 88-levels of {@code COACTVWC}
     * (source lines 125-128). Declared as a literal for contract-drift detection.
     */
    private static final String MSG_ACCT_INVALID =
            "Account number must be a non zero 11 digit number";

    /**
     * Verbatim not-found message for the {@code 9200-GETCARDXREF-BYACCT}
     * {@code DFHRESP(NOTFND)} branch ({@code DID-NOT-FIND-ACCT-IN-CARDXREF},
     * source line 130), raised by {@link CrossReferenceService}.
     */
    private static final String MSG_XREF_NOT_FOUND =
            "Did not find this account in account card xref file";

    /**
     * Verbatim not-found message for the {@code 9300-GETACCTDATA-BYACCT}
     * {@code DFHRESP(NOTFND)} branch ({@code DID-NOT-FIND-ACCT-IN-ACCTDAT},
     * source line 132).
     */
    private static final String MSG_ACCT_NOT_FOUND =
            "Did not find this account in account master file";

    /**
     * Verbatim not-found message for the {@code 9400-GETCUSTDATA-BYCUST}
     * {@code DFHRESP(NOTFND)} branch ({@code DID-NOT-FIND-CUST-IN-CUSTDAT},
     * source line 134).
     */
    private static final String MSG_CUST_NOT_FOUND =
            "Did not find associated customer in master file";

    /** Representative strictly-positive, 11-digit account id ({@code ACCT-ID PIC 9(11)}). */
    private static final Long ACCT_ID = 12345678901L;

    /** The 9-digit customer id ({@code CUST-ID PIC 9(09)}) the cross-reference resolves to. */
    private static final Long CUST_ID = 987654321L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @InjectMocks
    private AccountViewService service;

    // ---------------------------------------------------------------------
    // 2210-EDIT-ACCOUNT - id edit
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("2210-EDIT-ACCOUNT: null, zero, and negative ids throw ValidationException and touch no collaborator")
    void viewAccount_nonPositiveId_throwsValidation() {
        // Null id (COBOL "not supplied").
        assertThatThrownBy(() -> service.viewAccount(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_INVALID);

        // Zero id (SEARCHED-ACCT-ZEROES).
        assertThatThrownBy(() -> service.viewAccount(0L))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_INVALID);

        // Negative id (fails the strictly-positive edit).
        assertThatThrownBy(() -> service.viewAccount(-7L))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_INVALID);

        // The edit rejects before any read, so no collaborator is ever consulted.
        verifyNoInteractions(crossReferenceService, accountRepository, customerRepository);
    }

    // ---------------------------------------------------------------------
    // 9200 / 9300 / 9400 - not-found branches (DFHRESP(NOTFND))
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("9200-GETCARDXREF-BYACCT: a missing cross-reference propagates ResourceNotFoundException (verbatim) and short-circuits the reads")
    void viewAccount_xrefMissing_propagatesResourceNotFound() {
        // The shared navigator raises the verbatim xref not-found message; the
        // service must propagate it unchanged (it already carries the contract text).
        when(crossReferenceService.resolveCustomerId(ACCT_ID))
                .thenThrow(new ResourceNotFoundException(MSG_XREF_NOT_FOUND));

        assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_XREF_NOT_FOUND);

        // COBOL GO TO 9000-READ-ACCT-EXIT: neither master file is read.
        verifyNoInteractions(accountRepository, customerRepository);
    }

    @Test
    @DisplayName("9300-GETACCTDATA-BYACCT: a missing account master row throws ResourceNotFoundException (verbatim) and never reads the customer")
    void viewAccount_accountMissing_throwsResourceNotFound() {
        when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);

        // COBOL GO TO 9000-READ-ACCT-EXIT: the customer master is not read.
        verifyNoInteractions(customerRepository);
    }

    @Test
    @DisplayName("9400-GETCUSTDATA-BYCUST: a missing customer master row throws ResourceNotFoundException (verbatim)")
    void viewAccount_customerMissing_throwsResourceNotFound() {
        when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(newAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewAccount(ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_CUST_NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // Happy path - full field projection
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Success: maps every account+customer field, normalizes the five money fields to scale 2, echoes @Version, and applies the city/SSN quirks")
    void viewAccount_success_mapsAllFields() {
        Account account = newAccount();
        Customer customer = newCustomer();

        when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        AccountViewResponse response = service.viewAccount(ACCT_ID);

        // ----- Identifiers rendered as fixed-width strings -----
        assertThat(response.accountId()).isEqualTo("12345678901");
        assertThat(response.customerId()).isEqualTo("987654321");
        assertThat(response.activeStatus()).isEqualTo("Y");
        assertThat(response.accountGroupId()).isEqualTo("GRP0001");

        // ----- Money: PIC S9(10)V99 -> BigDecimal, compared by value AND scale 2 -----
        // A negative balance proves signed fidelity; creditLimit is supplied at
        // scale 1 to prove the DTO normalizes it to scale 2 (never float/double).
        assertThat(response.currentBalance()).isEqualByComparingTo("-1234567890.99");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
        assertThat(response.creditLimit()).isEqualByComparingTo("5000.50");
        assertThat(response.creditLimit().scale()).isEqualTo(2);
        assertThat(response.cashCreditLimit()).isEqualByComparingTo("1000.00");
        assertThat(response.cashCreditLimit().scale()).isEqualTo(2);
        assertThat(response.currentCycleCredit()).isEqualByComparingTo("250.75");
        assertThat(response.currentCycleCredit().scale()).isEqualTo(2);
        assertThat(response.currentCycleDebit()).isEqualByComparingTo("125.25");
        assertThat(response.currentCycleDebit().scale()).isEqualTo(2);

        // ----- Dates (X(10) -> LocalDate) -----
        assertThat(response.openDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(response.expirationDate()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(response.reissueDate()).isEqualTo(LocalDate.of(2024, 6, 1));
        assertThat(response.dateOfBirth()).isEqualTo(LocalDate.of(1815, 12, 10));

        // ----- @Version echo for the optimistic-lock round-trip (AAP 0.8.4) -----
        assertThat(response.version()).isEqualTo(7L);

        // ----- Customer string attributes -----
        assertThat(response.firstName()).isEqualTo("ADA");
        assertThat(response.middleName()).isEqualTo("B");
        assertThat(response.lastName()).isEqualTo("LOVELACE");
        assertThat(response.addressLine1()).isEqualTo("1 ANALYTICAL WAY");
        assertThat(response.addressLine2()).isEqualTo("SUITE 1843");
        assertThat(response.stateCode()).isEqualTo("LN");
        assertThat(response.countryCode()).isEqualTo("UK");
        assertThat(response.zipCode()).isEqualTo("EC1A1BB");
        assertThat(response.phoneNumber1()).isEqualTo("+44-20-0000");
        assertThat(response.phoneNumber2()).isEqualTo("+44-20-9999");
        assertThat(response.govtIssuedId()).isEqualTo("UKID-1815");
        assertThat(response.eftAccountId()).isEqualTo("EFT0000001");
        assertThat(response.primaryCardHolderIndicator()).isEqualTo("Y");
        assertThat(response.ficoScore()).isEqualTo(800);

        // ----- City quirk: screen ACSCITY = CUST-ADDR-LINE-3 -----
        assertThat(response.city()).isEqualTo("LONDON");

        // ----- SSN quirk: assert BOTH the %09d rendering and the D-023 mask -----
        // custSsn 1234 -> String.format("%09d", 1234) = "000001234" (nine-digit,
        // zero-padded), then the DTO masks all but the last four digits. The five
        // leading mask characters ("*****") prove the value was nine digits wide,
        // and the trailing "1234" is the last four of the zero-padded rendering.
        assertThat(response.ssn()).isEqualTo("*****1234");
        assertThat(response.ssn()).hasSize(9);
    }

    @Test
    @DisplayName("Success edge: a null SSN and null money survive projection as null (never the literal \"null\", no NPE)")
    void viewAccount_nullSsn_yieldsNull() {
        Account account = newAccount();
        account.setAcctCurrBal(null); // a null money field must survive normalization as null
        Customer customer = newCustomer();
        customer.setCustSsn(null); // formatSsn(null) -> null, then maskSsn(null) -> null

        when(crossReferenceService.resolveCustomerId(ACCT_ID)).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        AccountViewResponse response = service.viewAccount(ACCT_ID);

        assertThat(response.ssn()).isNull();
        assertThat(response.currentBalance()).isNull();
    }

    // ---------------------------------------------------------------------
    // Fixture builders - real entities (no entity mocking), scale-2 money
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated {@link Account} fixture. The five monetary fields
     * are seeded to exercise the DTO's scale-2 contract: {@code acctCurrBal} is
     * negative (signed fidelity) and {@code acctCreditLimit} is supplied at scale
     * 1 so the response must normalize it to scale 2.
     *
     * @return a new, fully-populated account fixture
     */
    private static Account newAccount() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("-1234567890.99"));
        account.setAcctCreditLimit(new BigDecimal("5000.5"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("250.75"));
        account.setAcctCurrCycDebit(new BigDecimal("125.25"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        account.setAcctExpirationDate(LocalDate.of(2027, 1, 31));
        account.setAcctReissueDate(LocalDate.of(2024, 6, 1));
        account.setAcctGroupId("GRP0001");
        account.setVersion(7L);
        return account;
    }

    /**
     * Builds a fully-populated {@link Customer} fixture. {@code custAddrLine3} is
     * the screen city, and {@code custSsn} is a small numeric value that forces
     * the {@code %09d} zero-padding to be visible in the masked result.
     *
     * @return a new, fully-populated customer fixture
     */
    private static Customer newCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setCustFirstName("ADA");
        customer.setCustMiddleName("B");
        customer.setCustLastName("LOVELACE");
        customer.setCustAddrLine1("1 ANALYTICAL WAY");
        customer.setCustAddrLine2("SUITE 1843");
        customer.setCustAddrLine3("LONDON"); // screen ACSCITY = CUST-ADDR-LINE-3
        customer.setCustAddrStateCd("LN");
        customer.setCustAddrCountryCd("UK");
        customer.setCustAddrZip("EC1A1BB");
        customer.setCustPhoneNum1("+44-20-0000");
        customer.setCustPhoneNum2("+44-20-9999");
        customer.setCustSsn(1234L);
        customer.setCustGovtIssuedId("UKID-1815");
        customer.setCustDobYyyyMmDd(LocalDate.of(1815, 12, 10));
        customer.setCustEftAccountId("EFT0000001");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(800);
        return customer;
    }
}
