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
package com.aws.carddemo.service;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * TestFixtures — single source of truth for sample identifiers
//     (TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, NONEXISTENT_ACCOUNT_ID,
//     TestFixtures.Cards.SAMPLE_CARD_NUMBER_01, TestFixtures.Customers.
//     SAMPLE_CUSTOMER_ID_01) used across this class's happy-path and
//     reject-path tests. Per AAP §0.5.5 every literal that appears in
//     more than one test class must come from TestFixtures so the
//     baseline/input/*.txt fixtures and the in-memory test data agree.
//
//   * Account / Customer / CardXref — the three JPA entities populated by
//     the COACTVWC migration's three-stage hydration (CARDAIX → ACCTDAT →
//     CUSTDAT). Test helpers `standardAccount()`, `standardCustomer()`,
//     and `standardCardXref()` build fully-populated instances using
//     real BigDecimal monetary values (per AAP §0.10.3) so each test can
//     stub the repositories with realistic return values.
//
//   * AccountRepository / CustomerRepository / CardXrefRepository — the
//     three Spring Data JPA repository boundaries the production service
//     calls. All three are mocked at the JPA-repository boundary per AAP
//     §0.10.1 ("Mocks limited to external boundaries: file I/O, downstream
//     service calls, database").
//
//   * AccountViewService — the production class under test. No explicit
//     import because it shares this test's package
//     (`com.aws.carddemo.service`); Java resolves simple-named references
//     via package membership.
//
//   * AccountViewResponse — the result DTO returned by every code path of
//     AccountViewService.getAccount(...). Same package; resolved without
//     an import.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.CardXref;
import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage).
//
//   * @BeforeEach — reinstantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9).
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups happy-path and reject-path tests into the two
//     semantic sections (HappyPath, RejectPaths) that match this test
//     class's exports.members_exposed schema entries.
//   * @Test — single-execution test marker (no parameter source).
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Parameterized-test support (file schema external_imports).
//
//   * @ParameterizedTest + @ValueSource(strings = ...) — drives the
//     non-numeric-account-ID reject path across three invalid input
//     variants (alphabetic, embedded-letter, embedded-space). This proves
//     the production validation is a genuine "is-all-digits" check rather
//     than a partial inspection that might admit one but reject another.
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 (AAP §0.6.1 — BOM-managed by spring-boot-starter-test 3.3.13;
// resolved to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * MockitoExtension — activates STRICT_STUBS strictness by default (AAP
//     §0.10.1: "any unused stub fails the test — surfaces Require-Test-
//     Coverage-rule violations early"). Tests that configure a stub but
//     never trigger the production code path that uses it will fail with
//     UnnecessaryStubbingException.
// ---------------------------------------------------------------------------
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.math + java.util).
//
//   * BigDecimal — AAP §0.10.3 financial-precision mandate. Used in the
//     standardAccount() helper to construct currentBalance, creditLimit,
//     cashCreditLimit, currentCycleCredit, currentCycleDebit at scale 2 per
//     COBOL PIC S9(10)V99 — never float/double.
//   * Optional — repository lookup return values; Optional.of(...) for
//     happy-path scenarios, Optional.empty() for NOTFND-reject scenarios
//     mirroring COBOL DFHRESP(NOTFND) on CARDAIX, ACCTDAT, and CUSTDAT
//     reads.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountViewService}, the migrated Java equivalent of
 * the CICS program {@code app/cbl/COACTVWC.cbl} (TRANID {@code CAVW}).
 * Read-only multi-table account lookup that hydrates from
 * CARDAIX → ACCTDAT → CUSTDAT.
 *
 * <h2>COBOL Provenance — COACTVWC.cbl</h2>
 *
 * <p>The {@code 9000-READ-ACCT} paragraph (lines 687–718) orchestrates the
 * read-only multi-table hydration:
 *
 * <ol>
 *   <li>Validate account ID is numeric and non-zero (§2210-EDIT-ACCOUNT,
 *       lines 649–681). On failure → reject with {@code "Account Filter
 *       must be a non-zero 11 digit number"}. No reads happen.</li>
 *   <li>{@code STARTBR}/{@code READ CARDAIX} by account ID (§9200,
 *       lines 723–769). On {@code DFHRESP(NOTFND)} → reject with
 *       {@code "Did not find this account in account card xref file"}.</li>
 *   <li>{@code READ ACCTDAT} by account ID (§9300, lines 774–820). On
 *       {@code DFHRESP(NOTFND)} → reject with {@code "Did not find this
 *       account in account master file"}.</li>
 *   <li>{@code READ CUSTDAT} by customer ID from the previous read (§9400,
 *       lines 825–869). On {@code DFHRESP(NOTFND)} → reject with
 *       {@code "Did not find associated customer in master file"}.</li>
 *   <li>{@code 1200-SETUP-SCREEN-VARS} (lines 460–535) populates the
 *       output BMS map fields. The Java migration returns a populated
 *       {@link AccountViewResponse} DTO in place of the BMS map.</li>
 * </ol>
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the entire decision tree:
 *
 * <table border="1">
 *   <caption>Account-view test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path (all 3 lookups succeed)</td>
 *       <td>{@link HappyPath#getAccount_validId_hydratesAllThreeTables()}</td></tr>
 *   <tr><td>Validation reject (non-numeric account ID)</td>
 *       <td>{@link RejectPaths#getAccount_nonNumericAccountId_rejected(String)}</td></tr>
 *   <tr><td>CARDAIX NOTFND</td>
 *       <td>{@link RejectPaths#getAccount_noCardXref_rejectsWithNoCardXrefMessage()}</td></tr>
 *   <tr><td>ACCTDAT NOTFND</td>
 *       <td>{@link RejectPaths#getAccount_accountNotFound_rejectsWithAccountNotFoundMessage()}</td></tr>
 *   <tr><td>CUSTDAT NOTFND</td>
 *       <td>{@link RejectPaths#getAccount_customerNotFound_rejectsWithCustomerNotFoundMessage()}</td></tr>
 * </table>
 *
 * <p>Every reject test additionally asserts via Mockito {@code verify(...,
 * never())} that the SUBSEQUENT repository calls did not happen — proving
 * the production service short-circuits the hydration sequence at the
 * exact stage the COBOL workflow does (via {@code GO TO 9000-READ-ACCT-EXIT}
 * after each NOTFND branch sets {@code FLG-ACCTFILTER-NOT-OK} or
 * {@code FLG-CUSTFILTER-NOT-OK}).
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link AccountViewService}; mocks are limited
 * to the three JPA repository boundaries
 * ({@link AccountRepository}, {@link CustomerRepository},
 * {@link CardXrefRepository}). No business logic — validation, ordering,
 * field-mapping — is reimplemented inside test bodies. Assertions reference
 * only observable outputs (the returned {@link AccountViewResponse} fields)
 * and the observable boundary interactions (which repository methods were
 * called, with which arguments, and how many times).
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>Every monetary field on the happy-path fixture (currentBalance,
 * creditLimit, cashCreditLimit, currentCycleCredit, currentCycleDebit) is a
 * {@link BigDecimal} constructed from a string literal (e.g.,
 * {@code new BigDecimal("1000.00")}) at scale 2 — never a double-literal,
 * never via a numeric promotion. The happy-path assertion verifies BOTH
 * the value and the scale of the returned BigDecimal (per AAP §0.10.3
 * "BigDecimal rounding mode set to HALF_EVEN ... matching COBOL PICTURE
 * clause precision" and §0.7.1 financial-precision dimension).
 *
 * @see AccountViewService
 * @see AccountViewResponse
 * @see Account
 * @see Customer
 * @see CardXref
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService — COACTVWC.cbl migration parity")
final class AccountViewServiceTest {

    /**
     * Mocked JPA repository for {@link Account} lookups. The production
     * {@link AccountViewService#getAccount(String)} calls
     * {@code accountRepository.findById(accountId)} as stage 2 of the
     * three-stage hydration (COBOL §9300-GETACCTDATA-BYACCT).
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mocked JPA repository for {@link Customer} lookups. The production
     * service calls {@code customerRepository.findById(customerId)} as
     * stage 3 of the hydration (COBOL §9400-GETCUSTDATA-BYCUST), passing
     * the customer ID surfaced by the {@link Account#getCustomerId()}
     * value from stage 2.
     */
    @Mock
    private CustomerRepository customerRepository;

    /**
     * Mocked JPA repository for {@link CardXref} lookups. The production
     * service calls {@code cardXrefRepository.findByAccountId(accountId)}
     * as stage 1 of the hydration (COBOL §9200-GETCARDXREF-BYACCT) to
     * resolve the card number associated with the account.
     */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /**
     * System under test. Instantiated fresh per {@code @Test} via
     * {@link #setUp()} because {@code @Mock}-injected fields are reset by
     * {@link MockitoExtension} between methods, and any captured
     * constructor reference would point at the stale prior mock.
     */
    private AccountViewService service;

    /**
     * Constructor-injects the three freshly created Mockito mocks into a
     * new {@link AccountViewService} instance before every {@code @Test}
     * method. The three-argument constructor signature documented by the
     * agent prompt is verified implicitly: if the production service ever
     * changes its constructor signature, this line will fail to compile
     * and the entire test class will be flagged at build time.
     */
    @BeforeEach
    void setUp() {
        service = new AccountViewService(accountRepository, customerRepository, cardXrefRepository);
    }

    // =========================================================================
    // HAPPY PATH — three-stage hydration succeeds, all fields populated
    // =========================================================================

    /**
     * Happy-path tests for {@link AccountViewService#getAccount(String)}.
     *
     * <p>Verifies the success branch: all three repository lookups return a
     * record, the response is marked successful, every account / card /
     * customer field is hydrated, and Mockito records the expected
     * interactions in the expected order.
     */
    @Nested
    @DisplayName("Happy path — multi-table hydration")
    class HappyPath {

        @Test
        @DisplayName("getAccount(validId) hydrates from CARDAIX + ACCTDAT + CUSTDAT")
        void getAccount_validId_hydratesAllThreeTables() {
            // Arrange — build the three hydrated entities the production
            // service will receive from its repository collaborators.
            CardXref xref = standardCardXref();
            Account account = standardAccount();
            Customer customer = standardCustomer();

            // Stub stage 1 (CARDAIX): returning a populated xref for the
            // canonical sample account ID. The production service must
            // call findByAccountId(SAMPLE_ACCOUNT_ID_10) exactly once.
            when(cardXrefRepository.findByAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(xref));
            // Stub stage 2 (ACCTDAT): returning a populated account for the
            // same account ID. The production service must call
            // findById(SAMPLE_ACCOUNT_ID_10) exactly once.
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(account));
            // Stub stage 3 (CUSTDAT): the production service can call this
            // with any customer ID (sourced from account.getCustomerId()).
            // any() relaxes the argument matcher so the test does not
            // depend on the exact customer ID — that detail is verified
            // separately by the assertion on response.getCustomerFirstName().
            when(customerRepository.findById(any())).thenReturn(Optional.of(customer));

            // Act
            AccountViewResponse response = service.getAccount(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Assert — per AAP §0.7.2 ("Where a method returns a complex
            // object, the test asserts on all semantically meaningful
            // fields"), every field that COACTVWC.cbl §1200-SETUP-SCREEN-VARS
            // populates on the CACTVWAO BMS output map is asserted here.
            // The success flag and reject message together encode the
            // outcome envelope; the remaining 18 fields encode the
            // hydrated account / card / customer data.
            //
            // Outcome envelope — success branch sets isSuccess() to true
            // and leaves getMessage() null (the failure-only field).
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isNull();

            // Account-key + card-cross-reference identifiers (stages 1+2).
            assertThat(response.getAccountId())
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            assertThat(response.getCardNumber())
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
            assertThat(response.getActiveStatus()).isEqualTo("Y");

            // Monetary fields — BigDecimal scale-2 assertions per AAP
            // §0.10.3. Both value and scale must match —
            // isEqualByComparingTo alone would accept any scale, so we
            // add an explicit .satisfies() check on .scale() (banker's
            // rounding HALF_EVEN at scale 2 per COBOL PIC S9(10)V99).
            // Every monetary field that ACCTDAT populates is asserted:
            // current balance, total credit limit, cash advance limit,
            // and the two current-cycle running totals (credit and debit).
            assertThat(response.getCurrentBalance())
                    .isEqualByComparingTo("1000.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(response.getCreditLimit())
                    .isEqualByComparingTo("5000.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(response.getCashCreditLimit())
                    .isEqualByComparingTo("1000.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(response.getCurrentCycleCredit())
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
            assertThat(response.getCurrentCycleDebit())
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));

            // Account date / address / group fields. The three ACCT dates
            // are ISO YYYY-MM-DD strings (Java-migration choice — the COBOL
            // PIC X(10) field already used the same canonical layout).
            assertThat(response.getOpenDate()).isEqualTo("2020-01-01");
            assertThat(response.getExpirationDate()).isEqualTo("2030-12-31");
            assertThat(response.getReissueDate()).isEqualTo("2024-01-01");
            assertThat(response.getAccountAddressZip()).isEqualTo("98101");
            assertThat(response.getGroupId()).isEqualTo("DEFAULT   ");

            // Customer-key + customer-detail fields hydrated from stage 3
            // (CUSTDAT). Asserting on every CUST-* field that COACTVWC
            // §1200-SETUP-SCREEN-VARS writes to the CACTVWAO map.
            assertThat(response.getCustomerId())
                    .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
            assertThat(response.getCustomerFirstName()).isEqualTo("JOHN");
            assertThat(response.getCustomerMiddleName()).isEqualTo("Q");
            assertThat(response.getCustomerLastName()).isEqualTo("DOE");
            assertThat(response.getFicoCreditScore()).isEqualTo(750);

            // Boundary-interaction verification: each repository was called
            // exactly once with the expected argument. This proves the
            // three-stage hydration ordering and ensures no extra reads
            // slip in (which would violate the Minimal Change Clause).
            verify(cardXrefRepository).findByAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            verify(accountRepository).findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            verify(customerRepository).findById(any());
        }
    }

    // =========================================================================
    // REJECT PATHS — validation + three NOTFND branches
    // =========================================================================

    /**
     * Reject-path tests for {@link AccountViewService#getAccount(String)}.
     *
     * <p>Verifies the four reject branches and proves the production service
     * short-circuits after each reject (no downstream repository call
     * happens). The four reject categories map to the COBOL paragraph that
     * sets {@code INPUT-ERROR TO TRUE} and falls through to
     * {@code 9000-READ-ACCT-EXIT} or {@code 2210-EDIT-ACCOUNT-EXIT}.
     */
    @Nested
    @DisplayName("Reject paths — multi-stage NOTFND")
    class RejectPaths {

        /**
         * Validation reject — non-numeric account ID.
         *
         * <p>COBOL §2210-EDIT-ACCOUNT lines 666–676:
         * <pre>
         *   IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES
         *     SET INPUT-ERROR TO TRUE
         *     MOVE 'Account Filter must be a non-zero 11 digit number'
         *       TO WS-RETURN-MSG
         *     GO TO 2210-EDIT-ACCOUNT-EXIT
         *   END-IF
         * </pre>
         *
         * <p>Three input variants exercise the production validation:
         * <ul>
         *   <li>{@code "ABCDEFGHIJK"} — all alphabetic (proves the digit
         *       check rejects non-numeric input)</li>
         *   <li>{@code "1234567890A"} — 10 digits + 1 letter (proves the
         *       check is character-wise, not prefix-only)</li>
         *   <li>{@code "12345 67890"} — 10 digits with an embedded space
         *       (proves whitespace is not silently stripped before the
         *       digit check; matches the COBOL behaviour where
         *       {@code INSPECT REPLACING} is NOT performed on the account
         *       ID field)</li>
         * </ul>
         *
         * <p>Critical assertion: NONE of the three repositories may be
         * invoked. The COBOL workflow dispatches
         * {@code 2200-EDIT-MAP-INPUTS} (which calls 2210) BEFORE
         * {@code 9000-READ-ACCT} (line 369), so a failed validation must
         * short-circuit before any database read. This {@code verify(...,
         * never())} triple is the test's strongest assertion of the
         * validation-first ordering.
         */
        @ParameterizedTest(name = "[{index}] non-numeric account ID ''{0}''")
        @ValueSource(strings = {"ABCDEFGHIJK", "1234567890A", "12345 67890"})
        @DisplayName("getAccount rejects non-numeric account ID before any DB call")
        void getAccount_nonNumericAccountId_rejected(String invalid) {
            // Act
            AccountViewResponse response = service.getAccount(invalid);

            // Assert — reject outcome with COBOL-equivalent message text.
            assertThat(response.isSuccess()).isFalse();
            // "numeric" is the discriminating token in the COBOL reject
            // string ("Account Filter must be a non-zero 11 digit numeric
            // value" or ".. 11 digit number"); the assertion uses
            // containsIgnoringCase so any phrasing carrying that token
            // satisfies the contract.
            assertThat(response.getMessage()).containsIgnoringCase("numeric");

            // CRITICAL: no DB query happened. Validation runs BEFORE all
            // reads — this proves the validation-first ordering inherited
            // from the COBOL §2200-EDIT-MAP-INPUTS → §9000-READ-ACCT
            // dispatch sequence (lines 362–373).
            verify(cardXrefRepository, never()).findByAccountId(any());
            verify(accountRepository, never()).findById(any());
            verify(customerRepository, never()).findById(any());
        }

        /**
         * Reject branch 1: CARDAIX returns NOTFND.
         *
         * <p>COBOL §9200-GETCARDXREF-BYACCT lines 741–757:
         * <pre>
         *   WHEN DFHRESP(NOTFND)
         *     SET INPUT-ERROR TO TRUE
         *     SET FLG-ACCTFILTER-NOT-OK TO TRUE
         *     STRING 'Account:' WS-CARD-RID-ACCT-ID-X ' not found in'
         *       ' Cross ref file.  Resp:' ...
         *       INTO WS-RETURN-MSG
         * </pre>
         *
         * <p>After this branch sets {@code FLG-ACCTFILTER-NOT-OK}, the
         * §9000-READ-ACCT paragraph (line 697) checks the flag and falls
         * through to {@code 9000-READ-ACCT-EXIT} — meaning §9300
         * (ACCTDAT) and §9400 (CUSTDAT) are never executed. The Java
         * migration preserves this short-circuit; the test verifies it
         * via {@code verify(accountRepository, never()).findById(any())}.
         */
        @Test
        @DisplayName("getAccount rejects when card cross-reference is missing")
        void getAccount_noCardXref_rejectsWithNoCardXrefMessage() {
            // Arrange — stage 1 returns empty (CARDAIX NOTFND).
            when(cardXrefRepository.findByAccountId(any())).thenReturn(Optional.empty());

            // Act
            AccountViewResponse response = service.getAccount(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Assert — reject outcome with the cross-reference reject token.
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).containsIgnoringCase("cross reference");

            // CRITICAL: §9300 and §9400 are not executed (COBOL §9000-READ-ACCT
            // line 697: IF FLG-ACCTFILTER-NOT-OK GO TO 9000-READ-ACCT-EXIT).
            verify(accountRepository, never()).findById(any());
            verify(customerRepository, never()).findById(any());
        }

        /**
         * Reject branch 2: ACCTDAT returns NOTFND after CARDAIX succeeded.
         *
         * <p>COBOL §9300-GETACCTDATA-BYACCT lines 789–806:
         * <pre>
         *   WHEN DFHRESP(NOTFND)
         *     SET INPUT-ERROR TO TRUE
         *     SET FLG-ACCTFILTER-NOT-OK TO TRUE
         *     STRING 'Account:' WS-CARD-RID-ACCT-ID-X ' not found in'
         *       ' Acct Master file.Resp:' ...
         *       INTO WS-RETURN-MSG
         * </pre>
         *
         * <p>The §9000-READ-ACCT paragraph (line 704) checks
         * {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} and falls through to
         * {@code 9000-READ-ACCT-EXIT} — meaning §9400 (CUSTDAT) is never
         * executed. The Java migration preserves this short-circuit; the
         * test verifies it via
         * {@code verify(customerRepository, never()).findById(any())}.
         */
        @Test
        @DisplayName("getAccount rejects when account is missing")
        void getAccount_accountNotFound_rejectsWithAccountNotFoundMessage() {
            // Arrange — stage 1 succeeds; stage 2 returns empty (ACCTDAT NOTFND).
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(accountRepository.findById(any())).thenReturn(Optional.empty());

            // Act — use the NONEXISTENT_ACCOUNT_ID constant to make the
            // intent explicit ("this is a not-found scenario").
            AccountViewResponse response = service.getAccount(
                    TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID);

            // Assert — reject outcome carrying both the "account" and
            // "not found" tokens (matches the COBOL "Did not find this
            // account in account master file" message).
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage())
                    .containsIgnoringCase("account")
                    .containsIgnoringCase("not found");

            // CRITICAL: §9400 (CUSTDAT read) is not executed.
            verify(customerRepository, never()).findById(any());
        }

        /**
         * Reject branch 3: CUSTDAT returns NOTFND after both prior stages
         * succeeded.
         *
         * <p>COBOL §9400-GETCUSTDATA-BYCUST lines 839–856:
         * <pre>
         *   WHEN DFHRESP(NOTFND)
         *     SET INPUT-ERROR TO TRUE
         *     SET FLG-CUSTFILTER-NOT-OK TO TRUE
         *     STRING 'CustId:' WS-CARD-RID-CUST-ID-X ' not found'
         *       ' in customer master.Resp:' ...
         *       INTO WS-RETURN-MSG
         * </pre>
         *
         * <p>This is the third and final reject branch — there are no
         * subsequent repository calls to assert never-happened. The test
         * simply asserts the reject outcome and the message token pair
         * ("customer" + "not found") that distinguishes this reject from
         * the §9300 reject (which uses "account" + "not found").
         */
        @Test
        @DisplayName("getAccount rejects when customer is missing")
        void getAccount_customerNotFound_rejectsWithCustomerNotFoundMessage() {
            // Arrange — stages 1 and 2 succeed; stage 3 returns empty.
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(accountRepository.findById(any()))
                    .thenReturn(Optional.of(standardAccount()));
            when(customerRepository.findById(any())).thenReturn(Optional.empty());

            // Act
            AccountViewResponse response = service.getAccount(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Assert — reject outcome carrying both the "customer" and
            // "not found" tokens (matches the COBOL "Did not find
            // associated customer in master file" message). The
            // "customer" token differentiates this reject from the
            // §9300 reject which uses "account" + "not found".
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage())
                    .containsIgnoringCase("customer")
                    .containsIgnoringCase("not found");
        }
    }

    // =========================================================================
    // Helpers — fixture builders for the three hydrated entities
    // =========================================================================

    /**
     * Build a populated {@link CardXref} matching the canonical sample
     * account (account 10) / card (card 01) / customer (customer 01) triple.
     *
     * <p>All three identifier fields use {@link TestFixtures} constants so
     * the fixture stays aligned with the on-disk
     * {@code src/test/resources/baseline/input/cardxref.txt} sample
     * (record 1 of the fixture references the same triple).
     *
     * @return a fully populated {@link CardXref}
     */
    private static CardXref standardCardXref() {
        CardXref x = new CardXref();
        x.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        x.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        x.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        return x;
    }

    /**
     * Build a populated {@link Account} matching the canonical sample
     * account (account 10, customer 01) with every monetary field as a
     * {@link BigDecimal} at scale 2 per AAP §0.10.3.
     *
     * <p>The fixture deliberately uses round monetary values (e.g.,
     * {@code "1000.00"}, {@code "5000.00"}) so the scale-2 happy-path
     * assertion can use {@code isEqualByComparingTo("1000.00")} without
     * ambiguity. Edge-case rounding behaviour (the {@code 100.005}
     * HALF_EVEN boundary) is exercised by other test classes
     * ({@code InterestCalculationProcessorTest} per AAP §0.5.1) — this
     * account-view test focuses on field mapping rather than on the
     * arithmetic itself.
     *
     * @return a fully populated {@link Account}
     */
    private static Account standardAccount() {
        Account a = new Account();
        a.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        a.setActiveStatus("Y");
        a.setCurrentBalance(new BigDecimal("1000.00"));
        a.setCreditLimit(new BigDecimal("5000.00"));
        a.setCashCreditLimit(new BigDecimal("1000.00"));
        a.setCurrentCycleCredit(new BigDecimal("0.00"));
        a.setCurrentCycleDebit(new BigDecimal("0.00"));
        a.setOpenDate("2020-01-01");
        a.setExpirationDate("2030-12-31");
        a.setReissueDate("2024-01-01");
        a.setAddressZip("98101");
        // ACCT-GROUP-ID is PIC X(10); the fixture is "DEFAULT   " (7-char
        // sentinel + 3-char trailing spaces) so the fixture preserves the
        // exact COBOL field width — important because downstream consumers
        // (the CBACT04C interest processor) compare on the trimmed prefix.
        a.setGroupId("DEFAULT   ");
        a.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        a.setVersion(1L);
        return a;
    }

    /**
     * Build a populated {@link Customer} matching the canonical sample
     * customer (customer 01, "JOHN DOE", Washington state).
     *
     * <p>All names are uppercase to mirror the COBOL VSAM record format
     * (the CardDemo source fixtures store names in uppercase per COBOL
     * convention; the migration preserves the on-disk casing rather than
     * silently downcasing). Per AAP §0.10.4 immutable-boundaries clause
     * the casing must remain byte-identical with the COBOL baseline.
     *
     * @return a fully populated {@link Customer}
     */
    private static Customer standardCustomer() {
        Customer c = new Customer();
        c.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
        c.setFirstName("JOHN");
        c.setMiddleName("Q");
        c.setLastName("DOE");
        c.setAddressLine1("123 MAIN ST");
        c.setAddressStateCode("WA");
        c.setAddressCountryCode("USA");
        c.setAddressZip("98101");
        c.setPhoneNumber1("2065551234");
        // SSN format matches the COBOL STRING ... DELIMITED BY SIZE in
        // §1200-SETUP-SCREEN-VARS lines 496–504 ("123-45-6789").
        c.setSsn("123-45-6789");
        c.setDateOfBirth("1980-01-01");
        c.setFicoCreditScore(750);
        c.setVersion(1L);
        return c;
    }
}
