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
//     (TestFixtures.Transactions.SAMPLE_TRANSACTION_ID,
//     TRAN_TYPE_PURCHASE, TRAN_CAT_REGULAR_SALES, TestFixtures.Cards.
//     SAMPLE_CARD_NUMBER_01). Per AAP §0.5.5 every literal that appears
//     in more than one test class must come from TestFixtures so the
//     baseline/input/*.txt fixtures and the in-memory test data agree.
//
//   * Transaction — the JPA entity populated by the COTRN01C migration's
//     single-key TRANSACT read. The `standardTransaction()` helper builds
//     a fully-populated instance using a real BigDecimal monetary value
//     (per AAP §0.10.3) so each test can stub the repository with a
//     realistic return value.
//
//   * TransactionRepository — the Spring Data JPA repository boundary the
//     production service calls. Mocked at the JPA-repository boundary per
//     AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * TransactionDetailService — the production class under test. No
//     explicit import because it shares this test's package
//     (`com.aws.carddemo.service`); Java resolves simple-named references
//     via package membership.
//
//   * TransactionDetailResponse — the result DTO returned by every code
//     path of TransactionDetailService.getTransaction(...). Same package;
//     resolved without an import.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
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
//   * @Nested — groups happy-path, reject-path, and financial-precision
//     tests into the three semantic sections (HappyPath, RejectPaths,
//     FinancialPrecision) that match this test class's
//     exports.members_exposed schema entries.
//   * @Test — single-execution test marker (no parameter source).
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
//     standardTransaction() helper to construct the TRAN-AMT value at
//     scale 2 per COBOL PIC S9(09)V99 — never float/double — and in the
//     FinancialPrecision @Nested class to verify scale-2 preservation on
//     the read-only lookup path.
//   * Optional — repository lookup return values; Optional.of(...) for
//     happy-path scenarios, Optional.empty() for the NOTFND-reject
//     scenario mirroring COBOL DFHRESP(NOTFND) on the TRANSACT read.
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionDetailService}, the migrated Java
 * equivalent of the 330-line CICS program
 * {@code app/cbl/COTRN01C.cbl} (TRANID {@code CT01}). Read-only
 * single-key transaction-detail lookup.
 *
 * <h2>COBOL Provenance — COTRN01C.cbl</h2>
 *
 * <p>The {@code READ-TRANSACT-FILE} paragraph (lines 267–296) orchestrates
 * the single-key read against the {@code TRANSACT} VSAM KSDS:
 *
 * <ol>
 *   <li>{@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)} by the
 *       16-character {@code TRAN-ID} primary key (lines 269–278).</li>
 *   <li>{@code WHEN DFHRESP(NORMAL)} — record found, the field-mapping
 *       block at lines 176–192 copies every {@code TRAN-*} field into
 *       the {@code COTRN1AO} BMS map (replaced in the Java migration by
 *       {@link TransactionDetailResponse#success(Transaction)}).</li>
 *   <li>{@code WHEN DFHRESP(NOTFND)} — reject with
 *       {@code "Transaction ID NOT found..."} (line 285).</li>
 *   <li>{@code WHEN OTHER} — I/O error, reject with
 *       {@code "Unable to lookup Transaction..."} (line 292). This branch
 *       maps to infrastructure failures
 *       ({@link org.springframework.dao.DataAccessException}) and is
 *       handled by the controller-layer exception handler rather than
 *       producing a {@link TransactionDetailResponse#failure(String)}
 *       value.</li>
 * </ol>
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the entire decision tree (both
 * COBOL-mandated branches plus the financial-precision invariant the
 * read path must preserve):
 *
 * <table border="1">
 *   <caption>Transaction-detail test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path (TRANSACT lookup succeeds)</td>
 *       <td>{@link HappyPath#getTransaction_existingTransaction_returnsPopulatedDetail()}</td></tr>
 *   <tr><td>NOTFND reject ({@code DFHRESP(NOTFND)})</td>
 *       <td>{@link RejectPaths#getTransaction_nonexistent_rejectsWithNotFoundMessage()}</td></tr>
 *   <tr><td>BigDecimal scale-2 preservation on read path</td>
 *       <td>{@link FinancialPrecision#getTransaction_preservesScale2()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link TransactionDetailService}; the
 * single mock is the JPA repository boundary
 * ({@link TransactionRepository}). No business logic — field-mapping,
 * reject-message selection, BigDecimal handling — is reimplemented inside
 * test bodies. Assertions reference only observable outputs (the returned
 * {@link TransactionDetailResponse} fields).
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>The {@link Transaction#getAmount()} field on the fixture is a
 * {@link BigDecimal} constructed from a string literal (e.g.,
 * {@code new BigDecimal("50.00")}) at scale 2 — never a double-literal,
 * never via a numeric promotion. The happy-path assertion verifies BOTH
 * the value and the scale of the returned BigDecimal (per AAP §0.10.3
 * "BigDecimal rounding mode set to HALF_EVEN ... matching COBOL PICTURE
 * clause precision"). The dedicated
 * {@link FinancialPrecision#getTransaction_preservesScale2()} test
 * doubles down on the scale invariant with a different amount value
 * ({@code "1234.56"}) so the assertion is not inadvertently coupled to
 * the {@code "50.00"} fixture.
 *
 * @see TransactionDetailService
 * @see TransactionDetailResponse
 * @see Transaction
 * @see TransactionRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService — COTRN01C.cbl migration parity")
final class TransactionDetailServiceTest {

    /**
     * Mocked JPA repository for {@link Transaction} lookups. The
     * production
     * {@link TransactionDetailService#getTransaction(String)} calls
     * {@code transactionRepository.findById(transactionId)} as the single
     * stage of the read-only workflow (COBOL §READ-TRANSACT-FILE).
     *
     * <p>This is the only Mockito mock the test maintains — the COBOL
     * program has a single I/O boundary (the {@code TRANSACT} VSAM file),
     * so the Java migration has a single repository collaborator, and
     * the test mocks exactly that one boundary. Per AAP §0.10.1 ("Mocks
     * limited to external boundaries: file I/O, downstream service
     * calls, database").
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * System under test. Instantiated fresh per {@code @Test} via
     * {@link #setUp()} because the {@code @Mock}-injected field is reset
     * by {@link MockitoExtension} between methods, and any captured
     * constructor reference would point at the stale prior mock.
     */
    private TransactionDetailService service;

    /**
     * Constructor-injects the freshly created Mockito mock into a new
     * {@link TransactionDetailService} instance before every {@code @Test}
     * method. The one-argument constructor signature documented by the
     * agent prompt is verified implicitly: if the production service ever
     * changes its constructor signature, this line will fail to compile
     * and the entire test class will be flagged at build time.
     */
    @BeforeEach
    void setUp() {
        service = new TransactionDetailService(transactionRepository);
    }

    // =========================================================================
    // HAPPY PATH — single-key TRANSACT lookup succeeds, all fields populated
    // =========================================================================

    /**
     * Happy-path tests for
     * {@link TransactionDetailService#getTransaction(String)}.
     *
     * <p>Verifies the success branch: the repository lookup returns a
     * populated transaction, the response is marked successful, the
     * COBOL-equivalent {@code TRAN-*} fields are surfaced via the
     * response, and the BigDecimal amount is carried at scale 2 with
     * the exact value the fixture defines.
     */
    @Nested
    @DisplayName("Happy path — read-only lookup")
    class HappyPath {

        /**
         * The canonical happy-path test: the repository returns a fully
         * populated {@link Transaction} for the sample transaction ID
         * (COBOL {@code DFHRESP(NORMAL)} branch). The assertion block
         * verifies that the response carries:
         *
         * <ul>
         *   <li>{@code isSuccess()} → {@code true} (the success envelope flag)</li>
         *   <li>{@code getTransactionId()} → the fixture
         *       {@link TestFixtures.Transactions#SAMPLE_TRANSACTION_ID}
         *       (TRAN-ID primary key preserved across the response
         *       round-trip)</li>
         *   <li>{@code getCardNumber()} → the fixture
         *       {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01}
         *       (TRAN-CARD-NUM preserved across the response round-trip)</li>
         *   <li>{@code getAmount()} → {@code "50.00"} with scale 2
         *       (AAP §0.10.3 financial-precision: both value and scale
         *       must match the COBOL {@code PIC S9(09)V99} field)</li>
         * </ul>
         *
         * <p>The assertion uses AssertJ's
         * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(String)}
         * for value equality (avoids scale-strict {@code equals()}
         * surprises) and chains {@code .satisfies(b -> assertThat(b.scale()).isEqualTo(2))}
         * to also verify the scale invariant — the two checks together
         * cover the AAP §0.10.3 "value AND scale" requirement.
         */
        @Test
        @DisplayName("getTransaction(existing) returns populated detail")
        void getTransaction_existingTransaction_returnsPopulatedDetail() {
            // Arrange — build a fully populated Transaction for the
            // canonical sample transaction ID and stub the repository to
            // return it on findById(...).
            Transaction existing = standardTransaction();
            when(transactionRepository.findById(TestFixtures.Transactions.SAMPLE_TRANSACTION_ID))
                    .thenReturn(Optional.of(existing));

            // Act
            TransactionDetailResponse response = service.getTransaction(
                    TestFixtures.Transactions.SAMPLE_TRANSACTION_ID);

            // Assert — outcome envelope: success branch sets isSuccess()
            // to true and leaves getMessage() null (the failure-only field).
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isNull();

            // Transaction-key + card-cross-reference identifiers preserved
            // verbatim from the entity (no transformation in the read
            // path).
            assertThat(response.getTransactionId())
                    .isEqualTo(TestFixtures.Transactions.SAMPLE_TRANSACTION_ID);
            assertThat(response.getCardNumber())
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Reference codes (type + category) preserved verbatim.
            assertThat(response.getTransactionTypeCode())
                    .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
            assertThat(response.getTransactionCategoryCode())
                    .isEqualTo(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES);

            // Free-form description preserved verbatim.
            assertThat(response.getDescription()).isEqualTo("TEST PURCHASE");

            // Merchant fields preserved verbatim.
            assertThat(response.getMerchantId()).isEqualTo("123456789");
            assertThat(response.getMerchantName()).isEqualTo("TEST MERCHANT");

            // Monetary field — BigDecimal scale-2 assertion per AAP §0.10.3.
            // Both value and scale must match — isEqualByComparingTo
            // alone would accept any scale, so we add an explicit
            // .satisfies() check on .scale() (banker's rounding HALF_EVEN
            // at scale 2 per COBOL PIC S9(09)V99). The read path performs
            // no arithmetic; the scale carried on the entity must survive
            // the success-factory copy verbatim.
            assertThat(response.getAmount())
                    .isEqualByComparingTo("50.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }
    }

    // =========================================================================
    // REJECT PATHS — NOTFND mirrors COBOL DFHRESP(NOTFND)
    // =========================================================================

    /**
     * Reject-path tests for
     * {@link TransactionDetailService#getTransaction(String)}.
     *
     * <p>Verifies the single reject branch the COBOL workflow produces
     * as a business-logic outcome ({@code DFHRESP(NOTFND)}). The COBOL
     * {@code WHEN OTHER} branch is an infrastructure-failure path
     * surfaced via {@link org.springframework.dao.DataAccessException}
     * propagation, not a {@link TransactionDetailResponse#failure(String)}
     * value — therefore not covered as a reject-path assertion in this
     * class (controller-layer exception handlers cover that branch in
     * the IT suite).
     */
    @Nested
    @DisplayName("Reject paths")
    class RejectPaths {

        /**
         * NOTFND reject — the canonical
         * {@code DFHRESP(NOTFND)} branch from
         * {@code READ-TRANSACT-FILE} (COTRN01C.cbl lines 283–288):
         *
         * <pre>
         *   WHEN DFHRESP(NOTFND)
         *     MOVE 'Y'                              TO WS-ERR-FLG
         *     MOVE 'Transaction ID NOT found...'    TO WS-MESSAGE
         *     MOVE -1                               TO TRNIDINL OF COTRN1AI
         *     PERFORM SEND-TRNVIEW-SCREEN
         * </pre>
         *
         * <p>The Java migration maps this to a
         * {@link TransactionDetailResponse#failure(String)} value carrying
         * the verbatim COBOL message. The assertion uses
         * {@code containsIgnoringCase("not found")} to anchor on the
         * discriminating phrase ("not found") rather than the exact
         * COBOL literal — this lets the message text be normalised
         * across the codebase (e.g., "Transaction ID NOT found..." in
         * one place vs. "Transaction ID Not Found..." in another)
         * without breaking the test's intent.
         *
         * <p>Uses a deliberately out-of-fixture transaction ID
         * ({@code "9999999999999999"}) so the test cannot accidentally
         * collide with any real fixture row.
         */
        @Test
        @DisplayName("getTransaction rejects when transaction does not exist")
        void getTransaction_nonexistent_rejectsWithNotFoundMessage() {
            // Arrange — repository returns Optional.empty() (NOTFND).
            when(transactionRepository.findById("9999999999999999"))
                    .thenReturn(Optional.empty());

            // Act
            TransactionDetailResponse response = service.getTransaction("9999999999999999");

            // Assert — reject outcome with COBOL-equivalent message text.
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage())
                    .as("Verbatim COBOL message 'Transaction ID NOT found...'")
                    .containsIgnoringCase("not found");
        }
    }

    // =========================================================================
    // FINANCIAL PRECISION — BigDecimal scale-2 preservation on the read path
    // =========================================================================

    /**
     * Financial-precision tests for
     * {@link TransactionDetailService#getTransaction(String)}.
     *
     * <p>Verifies AAP §0.10.3 ("BigDecimal rounding mode set to HALF_EVEN
     * — banker's rounding — matching COBOL PICTURE clause precision";
     * "All monetary fields: BigDecimal with scale derived from COBOL
     * PICTURE clause (e.g., PIC 9(7)V99 → scale 2)"). The TRAN-AMT field
     * is {@code PIC S9(09)V99} so the scale must be 2 on every code path
     * that touches a monetary value — including the read-only lookup
     * path, where no arithmetic is performed but the scale invariant
     * must still be preserved by the entity-to-DTO mapping.
     */
    @Nested
    @DisplayName("Financial precision — scale 2")
    class FinancialPrecision {

        /**
         * The dedicated scale-preservation test: independently of the
         * happy-path fixture's {@code "50.00"} amount, this test sets a
         * different amount ({@code "1234.56"}) on the entity and asserts
         * that the response carries scale-2 in {@code getAmount().scale()}.
         *
         * <p>The {@code any()} argument matcher is used because the test
         * is incidental about which transaction-ID was passed to the
         * repository — the only invariant under test here is the
         * BigDecimal scale-preservation contract on the response side of
         * the mapping. Using {@code any()} keeps the test focused on
         * the scale invariant and does not double up on the happy-path
         * test's transaction-ID-preservation assertion.
         */
        @Test
        @DisplayName("getTransaction preserves BigDecimal scale 2 in returned amount")
        void getTransaction_preservesScale2() {
            // Arrange — set a scale-2 amount distinct from the happy-path
            // fixture so the assertion cannot pass by accident due to a
            // fixture coupling. The chosen value "1234.56" carries
            // exactly 2 fractional digits and is well within the COBOL
            // PIC S9(09)V99 numeric range.
            Transaction txn = standardTransaction();
            txn.setAmount(new BigDecimal("1234.56"));
            when(transactionRepository.findById(any())).thenReturn(Optional.of(txn));

            // Act
            TransactionDetailResponse response = service.getTransaction(
                    TestFixtures.Transactions.SAMPLE_TRANSACTION_ID);

            // Assert — scale invariant preserved verbatim from the entity
            // to the response. Per AAP §0.10.3: the read path does no
            // arithmetic, so whatever scale the entity carries the
            // response must carry. The expected value and scale are
            // both checked: isEqualByComparingTo on the value avoids a
            // scale-strict equals() surprise, and the explicit scale
            // check enforces the COBOL PIC S9(09)V99 contract.
            assertThat(response.getAmount())
                    .isEqualByComparingTo("1234.56")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }
    }

    // =========================================================================
    // Fixture helper — builds a fully populated Transaction
    // =========================================================================

    /**
     * Builds a fully populated {@link Transaction} fixture for use across
     * the {@link HappyPath}, {@link RejectPaths}, and
     * {@link FinancialPrecision} nested test classes.
     *
     * <p>Every field uses a {@link TestFixtures} constant or a deliberately
     * synthetic literal — no PII, no real account numbers, no
     * float/double monetary values. The amount is constructed as
     * {@code new BigDecimal("50.00")} (string-literal-driven) so scale 2
     * is preserved exactly, matching COBOL {@code PIC S9(09)V99}.
     *
     * <p>The transaction-source (TRAN-SOURCE), origin/process timestamps
     * (TRAN-ORIG-TS / TRAN-PROC-TS), merchant-city, and merchant-ZIP are
     * left unset because no assertion in this test class depends on
     * them. Leaving them {@code null} mirrors the COBOL behaviour where
     * a {@code SPACES}-initialised record produces empty BMS output
     * fields; leaving them populated would create spurious assertion
     * coupling.
     *
     * @return a populated {@link Transaction} ready for repository stubbing
     */
    private static Transaction standardTransaction() {
        Transaction t = new Transaction();
        t.setTransactionId(TestFixtures.Transactions.SAMPLE_TRANSACTION_ID);
        t.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        t.setTransactionTypeCode(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
        t.setTransactionCategoryCode(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES);
        t.setAmount(new BigDecimal("50.00"));
        t.setDescription("TEST PURCHASE");
        t.setMerchantId("123456789");
        t.setMerchantName("TEST MERCHANT");
        return t;
    }
    // ============================================================
    // Nested test class — Authorization Contract
    // ============================================================

    /**
     * Documents and asserts the authorization-contract layer for
     * {@link TransactionDetailService} — the service that replaces COBOL program
     * {@code COTRN01C} (which reads user-owned transaction details).
     *
     * <h2>COBOL Authorization Model</h2>
     *
     * <p>In the original CICS/COBOL implementation, authorization was
     * gated by the CICS BMS sign-on flow: {@code COSGN00C} validated
     * the user's credentials and only after success could the user
     * navigate via the main menu (or admin menu for admin-only flows)
     * to this program's screen. The COBOL program itself performed no
     * caller-authorization check — it trusted the upstream CICS session.
     *
     * <h2>Java Migration — Layer of Responsibility</h2>
     *
     * <p>Per AAP §0.10.2 (Minimal Change Clause), the Java migration
     * preserves this contract. {@link TransactionDetailService} does NOT perform a
     * service-level caller-authorization check; instead:
     * <ul>
     *   <li>The REST controller (e.g., the Spring MVC controller
     *       that fronts this service) MUST enforce Spring Security
     *       {@code @PreAuthorize} or {@code @PostAuthorize}
     *       annotations at the HTTP boundary (the modern equivalent
     *       of the CICS BMS sign-on gate).</li>
     *   <li>The service layer trusts that the caller has passed the
     *       upstream authentication check; this matches the COBOL
     *       contract precisely.</li>
     * </ul>
     *
     * <p>These tests assert that contract is preserved structurally.
     *
     * @see com.aws.carddemo.service.UserListService for the contrasting
     *      pattern where the COBOL program does perform an admin-only
     *      check and the Java migration mirrors it via {@code callerUserType}
     */
    @Nested
    @DisplayName("Authorization contract — controller-layer responsibility (AAP §0.10.2)")
    class AuthorizationContract {

        /**
         * Verify {@link TransactionDetailService} method signatures carry NO
         * {@code callerUserType}-style parameter — proving the
         * authorization is the controller's responsibility per the
         * COBOL COTRN01C trust-upstream contract.
         */
        @Test
        @DisplayName("methodSignatures_carryNoCallerIdentity_perCobolContract")
        void methodSignatures_carryNoCallerIdentity_perCobolContract() {
            java.lang.reflect.Method[] methods = TransactionDetailService.class.getDeclaredMethods();
            boolean hasCallerUserTypeParam = false;
            for (java.lang.reflect.Method m : methods) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                for (java.lang.reflect.Parameter p : m.getParameters()) {
                    if (p.getName().toLowerCase().contains("callerusertype")
                            || p.getName().toLowerCase().contains("calleruser")) {
                        hasCallerUserTypeParam = true;
                    }
                }
            }
            assertThat(hasCallerUserTypeParam)
                    .as("TransactionDetailService must NOT accept callerUserType — "
                            + "authorization is the controller's responsibility "
                            + "per the COBOL COTRN01C contract")
                    .isFalse();
        }
    }
}
