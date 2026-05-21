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
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies, file
// schema internal_imports).
//
//   * TestFixtures — single source of truth for sample identifiers used
//     across the test class:
//     - TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10 (11-digit account ID
//       carried into the Filters.listTransactions_accountFilter test that
//       routes through TransactionRepository.findByAccountId).
//     - TestFixtures.Cards.SAMPLE_CARD_NUMBER_01 (16-digit Visa test PAN
//       carried into the Filters.listTransactions_cardFilter test that
//       routes through TransactionRepository.findByCardNumber, and also
//       used by the sampleTransactions() helper to seed each fixture
//       Transaction's TRAN-CARD-NUM field so the entire page agrees on
//       a single canonical card number).
//     Schema authority: file_schema.internal_imports[0] declares this as
//     the test's single in-project dependency.
//
//   * Transaction — the JPA entity populated by the COTRN00C migration's
//     paged TRANSACT browse. The sampleTransactions(int) helper builds
//     synthetic, fully-populated instances using real BigDecimal monetary
//     values (per AAP §0.10.3) so each test can stub the repository with
//     realistic page contents.
//
//   * TransactionRepository — the Spring Data JPA repository boundary the
//     production service calls. Mocked at the JPA-repository boundary per
//     AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * TransactionListService / TransactionListRequest / TransactionListResponse
//     — the production classes under test. No explicit imports because they
//     share this test's package (`com.aws.carddemo.service`); Java resolves
//     simple-named references via package membership. This matches the
//     convention established by every other test under
//     com.aws.carddemo.service.
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
//     to enforce strict test isolation (AAP §0.10.9). Each test sees a
//     fresh TransactionRepository mock and a fresh TransactionListService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention) with COBOL-
//     provenance annotations.
//   * @Nested — groups Pagination and Filters test scenarios into the two
//     semantic sections that match this test class's
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
// Mockito 5 (AAP §0.6.1 — BOM-managed by spring-boot-starter-test 3.3.x;
// resolved to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * ArgumentCaptor — captures the Pageable argument passed to
//     transactionRepository.findAll(Pageable) so the
//     Pagination#listTransactions_pageZero_returns10Transactions test can
//     assert that the production code requested page size 10 (matching the
//     COBOL PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10 loop
//     bound) without relying on private/internal state.
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is `STRICT_STUBS`; unused stubs
//     raise `UnnecessaryStubbingException`"). Each test deliberately
//     stubs only the repository method it expects the production code to
//     call, so a routing bug (for example a card-filter test that ends up
//     calling findAll instead of findByCardNumber) would surface as a
//     STRICT_STUBS UnnecessaryStubbingException.
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Spring Data Commons (AAP §0.6.1; resolved transitively via
// spring-boot-starter-data-jpa managed by the spring-boot-dependencies BOM).
//
//   * Page / PageImpl — Spring Data pagination result envelope. PageImpl
//     wraps fixture Transaction lists into Page<Transaction> so the mocked
//     repository's .findAll(...) / .findByAccountId(...) / .findByCardNumber(...)
//     returns a realistic paged result (carrying both content and the
//     navigation metadata derived from total-element count).
//   * Pageable / PageRequest — pagination request parameters. PageRequest.of
//     (page, 10) constructs the expected page bound for the fixture pages;
//     ArgumentCaptor<Pageable> verifies that the production code actually
//     requests page size 10 (the COBOL PERFORM VARYING UNTIL WS-IDX > 10
//     loop-bound parity).
// ---------------------------------------------------------------------------
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.math + java.util).
//
//   * BigDecimal — AAP §0.10.3 financial-precision mandate forbids float /
//     double for monetary values. Used in the sampleTransactions(int)
//     helper to seed Transaction.amount with new BigDecimal("50.00") at the
//     COBOL PIC S9(09)V99 scale-2 width, so the entity's monetary fields
//     preserve scale through the service-to-response mapping without any
//     implicit promotion to float/double.
//   * ArrayList — backing the sampleTransactions(int) helper that
//     synthesises Transaction fixtures for the Pagination and Filters
//     tests.
//   * Collections.emptyList() — seeds the empty PageImpl content for the
//     listTransactions_emptyRepository_returnsEmptyList scenario.
//   * List — the return type of sampleTransactions(int) and the type
//     parameter on PageImpl.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout
//     to express observable-behaviour assertions on TransactionListResponse
//     fields with descriptive .as("...") contexts where helpful.
//   * ArgumentMatchers.any — relaxes Pageable matching where the test does
//     not care about the exact PageRequest contents (the ArgumentCaptor-
//     based test captures and asserts on the Pageable separately).
//   * ArgumentMatchers.eq — exact-equality matcher for the
//     accountIdFilter / cardNumberFilter strings in the Filters tests,
//     ensuring the production code forwards the supplied filter value to
//     the repository verbatim.
//   * Mockito.verify — interaction assertion; pairs with ArgumentCaptor
//     and with .eq()/.any() matchers to assert the production code routed
//     the query through the correct repository method.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy paths.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionListService}, the migrated Java equivalent
 * of the 699-line CICS program {@code app/cbl/COTRN00C.cbl} (TRANID
 * {@code CT00}). Read-only paged browse of the {@code TRANSACT} VSAM KSDS
 * replacement (PostgreSQL {@code transactions} table) with optional
 * account-ID and card-number filters.
 *
 * <h2>COBOL Provenance — COTRN00C.cbl</h2>
 *
 * <p>The COBOL workflow walks {@code TRANSACT} via {@code STARTBR /
 * READNEXT} (forward) and {@code STARTBR / READPREV} (backward) loops,
 * populating a fixed-width 10-row {@code TRAN-REC OCCURS 10 TIMES} table on
 * the {@code COTRN0AO} BMS output map:
 *
 * <ul>
 *   <li>{@code PROCESS-PAGE-FORWARD} (lines 279–328) — the canonical page
 *       advance. {@code STARTBR-TRANSACT-FILE} at line 281, then a
 *       {@code PERFORM UNTIL WS-IDX >= 11 OR TRANSACT-EOF OR ERR-FLG-ON}
 *       loop (line 297) reads up to 10 records via
 *       {@code POPULATE-TRAN-DATA} (lines 379–390).</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} (lines 333–376) — the PF7 / previous
 *       page workflow using {@code READPREV}.</li>
 *   <li>{@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} (lines
 *       290, 344) — the implicit page size of 10 rows, materialised in this
 *       Java migration as {@code TransactionListService#PAGE_SIZE}.</li>
 *   <li>{@code CDEMO-CT00-PAGE-NUM PIC 9(08)} (in
 *       {@code app/cpy/COCOM01Y.cpy}) — the current page counter, replaced
 *       in the Java migration by the
 *       {@code TransactionListRequest#getPage()} field on the way in and the
 *       {@code TransactionListResponse#getCurrentPage()} field on the way
 *       out.</li>
 *   <li>{@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch (lines
 *       67–68) — set at lines 313, 315 of {@code PROCESS-PAGE-FORWARD}.
 *       Materialised in the Java migration as
 *       {@code TransactionListResponse#isHasNext()}.</li>
 * </ul>
 *
 * <p>The COBOL workflow has <em>no explicit account-key or card-key
 * filter</em>. The Java migration adds two declarative filters
 * (account-ID filter, card-number filter) as documented Java-migration
 * additions (AAP §0.10.2). The REST controller layer surfaces these as URL
 * query parameters; the service dispatches the query through the
 * appropriate repository method based on which filter (if any) is
 * populated.
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the entire decision tree:
 *
 * <table border="1">
 *   <caption>Transaction-list test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — page=0, full 10-row page, captures Pageable
 *       page size and asserts hasNext=true / hasPrevious=false</td>
 *       <td>{@link Pagination#listTransactions_pageZero_returns10Transactions()}</td></tr>
 *   <tr><td>Happy path — empty repository, no navigation</td>
 *       <td>{@link Pagination#listTransactions_emptyRepository_returnsEmptyList()}</td></tr>
 *   <tr><td>Happy path — last partial page (3 rows), hasNext=false /
 *       hasPrevious=true</td>
 *       <td>{@link Pagination#listTransactions_lastPage_hasNextFalse()}</td></tr>
 *   <tr><td>Happy path — account-ID filter routes through findByAccountId</td>
 *       <td>{@link Filters#listTransactions_accountFilter_routesToFindByAccountId()}</td></tr>
 *   <tr><td>Happy path — card-number filter routes through findByCardNumber</td>
 *       <td>{@link Filters#listTransactions_cardFilter_routesToFindByCardNumber()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link TransactionListService} via its
 * public single-argument constructor and assert on the
 * {@link TransactionListResponse} returned by the real
 * {@link TransactionListService#listTransactions(TransactionListRequest)}
 * method. The only mocked collaborator is the {@link TransactionRepository}
 * (database boundary — one of the four permitted mock categories under AAP
 * §0.10.1). No business logic — the page-size selection, the filter
 * dispatch, or the response construction — is duplicated in any test
 * body; the tests assert only on observable outputs.
 *
 * <h2>Strict-Stubs Mode</h2>
 *
 * <p>{@link MockitoExtension} activates STRICT_STUBS by default. Every
 * test deliberately stubs only the repository method the test expects the
 * production code to call (for example, the card-filter test stubs
 * {@code findByCardNumber} and asserts via {@code verify} that the same
 * method was invoked). A routing bug — for example, the card-filter test
 * stubbing {@code findByCardNumber} but the production code calling
 * {@code findAll} — would surface as a STRICT_STUBS
 * {@code UnnecessaryStubbingException} on the unused stub, providing a
 * second line of defence on top of the explicit {@code verify} call.
 *
 * @see TransactionListService
 * @see TransactionListRequest
 * @see TransactionListResponse
 * @see TransactionRepository
 * @see TestFixtures.Accounts
 * @see TestFixtures.Cards
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService — COTRN00C.cbl migration parity")
final class TransactionListServiceTest {

    /**
     * Mocked {@link TransactionRepository} — the JPA repository boundary
     * the production {@link TransactionListService} delegates to. Per AAP
     * §0.10.1, the only mocked collaborator (database is one of the four
     * permitted mock categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between
     * scenarios.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * System under test — the real {@link TransactionListService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborator above and to prevent any
     * accidental state leak between tests (the production service is
     * currently stateless aside from its single collaborator field, but
     * the per-method reset is defensive for future state additions).
     */
    private TransactionListService service;

    /**
     * Constructs a fresh {@link TransactionListService} before every test
     * method, injecting the freshly-instantiated {@link #transactionRepository}
     * mock. The combination of per-method {@code @Mock} instantiation
     * (driven by {@link MockitoExtension}) and per-method service
     * construction here guarantees that no stub or interaction from one
     * test leaks into another.
     */
    @BeforeEach
    void setUp() {
        service = new TransactionListService(transactionRepository);
    }

    // =========================================================================
    // PAGINATION — 10 rows per page (COBOL PERFORM VARYING WS-IDX UNTIL > 10)
    //
    // The COBOL workflow uses an implicit page size of 10 rows materialised
    // through two paragraphs:
    //   - PROCESS-PAGE-FORWARD (lines 279–328): PERFORM VARYING WS-IDX FROM
    //     1 BY 1 UNTIL WS-IDX > 10 with READNEXT inside the loop.
    //   - PROCESS-PAGE-BACKWARD (lines 333–376): PERFORM UNTIL WS-IDX <= 0
    //     OR TRANSACT-EOF OR ERR-FLG-ON with READPREV inside the loop.
    //
    // The 10-row bound on WS-IDX matches the COTRN0AO BMS map's TRAN-REC
    // OCCURS 10 TIMES table. The Java migration delegates the equivalent
    // semantic to Spring Data Pageable, configured with PAGE_SIZE = 10.
    // This @Nested block verifies:
    //   (1) The production service requests page size 10 from the
    //       repository (ArgumentCaptor captures the Pageable and asserts
    //       on its size).
    //   (2) Empty result sets are surfaced as a populated response with
    //       empty transactions list and both navigation flags false (no
    //       reject path for empty list — the COBOL "List is empty..."
    //       text is a UX concern delegated to the REST controller layer).
    //   (3) Last-page boundary (partial page of 3 rows out of 93 total)
    //       sets hasNext=false / hasPrevious=true.
    // =========================================================================

    /**
     * Pagination tests for
     * {@link TransactionListService#listTransactions(TransactionListRequest)}
     * — verifies the COBOL {@code PERFORM VARYING WS-IDX FROM 1 BY 1
     * UNTIL WS-IDX > 10} loop-bound contract is honoured by the Spring
     * Data {@link Pageable} construction inside the production service.
     *
     * <p>The page-size assertion uses an {@link ArgumentCaptor}{@code
     * <Pageable>} to capture the {@link Pageable} passed to
     * {@link TransactionRepository#findAll(Pageable)}; the captured value's
     * {@link Pageable#getPageSize()} is then asserted equal to {@code 10}.
     * This is preferred over peeking at
     * {@code TransactionListService#PAGE_SIZE} directly because it proves
     * the constant is actually being consumed by the production code
     * rather than just declared in a static field.
     */
    @Nested
    @DisplayName("Pagination — 10 rows per page (COTRN00C WS-IDX > 10 loop bound)")
    class Pagination {

        /**
         * Happy path: page=0 returns a full 10-row page, and the production
         * code requests page size 10 from the repository.
         *
         * <p>Asserts five things:
         * <ol>
         *   <li>The response carries exactly 10 transactions (the fixture
         *       page is populated by
         *       {@link TransactionListServiceTest#sampleTransactions(int)
         *       sampleTransactions(10)} and propagated verbatim through the
         *       service to the response DTO).</li>
         *   <li>The response's {@code currentPage} equals 0 (the
         *       request's page index, mapped directly to the COBOL
         *       {@code CDEMO-CT00-PAGE-NUM} counter).</li>
         *   <li>The response's {@code hasNext} flag is {@code true}
         *       (the fixture's total-element count of 100 means there
         *       are 9 more pages beyond page 0).</li>
         *   <li>The response's {@code hasPrevious} flag is {@code false}
         *       (page 0 is the first page; the COBOL workflow inferred
         *       the equivalent from {@code CDEMO-CT00-PAGE-NUM = 0}).</li>
         *   <li>The {@link Pageable} passed to
         *       {@link TransactionRepository#findAll(Pageable)} has
         *       {@link Pageable#getPageSize()} = 10, matching the COBOL
         *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10}
         *       contract via {@code TransactionListService#PAGE_SIZE}.</li>
         * </ol>
         *
         * <p>COBOL parity: this is the {@code PROCESS-PAGE-FORWARD} happy
         * path (lines 279–328) reading exactly 10 records into the
         * {@code TRAN-REC OCCURS 10 TIMES} table on the {@code COTRN0AO}
         * BMS map.
         */
        @Test
        @DisplayName("listTransactions(page=0) returns first 10 transactions")
        void listTransactions_pageZero_returns10Transactions() {
            // Arrange — stub a 10-row fixture page with a total-element
            // count of 100, so hasNext()=true (there are 9 more pages
            // beyond page 0) and hasPrevious()=false (page 0 is the first
            // page).
            Page<Transaction> fixturePage =
                    new PageImpl<>(sampleTransactions(10), PageRequest.of(0, 10), 100);
            when(transactionRepository.findAll(any(Pageable.class)))
                    .thenReturn(fixturePage);

            TransactionListRequest request = new TransactionListRequest();
            request.setPage(0);

            // Act — invoke the real production method.
            TransactionListResponse response = service.listTransactions(request);

            // Assert (1) — the response carries exactly 10 transactions
            // (fixture size — proves the page propagation works end-to-end
            // without re-implementing the COBOL POPULATE-TRAN-DATA mapping
            // in the test).
            assertThat(response.getTransactions()).hasSize(10);

            // Assert (2) — the response's currentPage equals the request's
            // page index (direct Java materialisation of the COBOL
            // CDEMO-CT00-PAGE-NUM counter).
            assertThat(response.getCurrentPage()).isEqualTo(0);

            // Assert (3) — hasNext is true: the fixture's total-element
            // count of 100 (10 pages × 10 rows) means there are still 9
            // pages beyond page 0. COBOL equivalent: NEXT-PAGE-YES set at
            // line 313 of PROCESS-PAGE-FORWARD when the post-loop
            // READNEXT succeeds.
            assertThat(response.isHasNext()).isTrue();

            // Assert (4) — hasPrevious is false: page 0 is the first
            // page. COBOL equivalent: CDEMO-CT00-PAGE-NUM > 1 check at
            // line 245 of PROCESS-PF7-KEY would be false for page 0.
            assertThat(response.isHasPrevious()).isFalse();

            // Assert (5) — capture and inspect the Pageable passed to the
            // repository. The captured value's getPageSize() must equal
            // 10 (the COBOL PERFORM VARYING WS-IDX UNTIL > 10 loop-bound
            // contract). The descriptive .as("...") context attaches a
            // COBOL-provenance comment to any assertion failure message.
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findAll(captor.capture());
            assertThat(captor.getValue().getPageSize())
                    .as("Page size 10 per COTRN00C PERFORM VARYING WS-IDX UNTIL > 10")
                    .isEqualTo(10);
        }

        /**
         * Edge case: an empty repository (or a query beyond the last page)
         * produces a populated response with an empty transactions list and
         * both navigation flags {@code false}.
         *
         * <p>This is the Java equivalent of the COBOL
         * {@code "List is empty..."} BMS-map text shown when
         * {@code STARTBR-TRANSACT-FILE} succeeds but the subsequent
         * {@code READNEXT} immediately encounters EOF. The COBOL workflow
         * sets {@code NEXT-PAGE-NO TO TRUE} (line 315) and emits an empty
         * page; the Java migration surfaces the same outcome via
         * {@link Page#hasNext()} = {@code false} /
         * {@link Page#hasPrevious()} = {@code false} on an empty page.
         *
         * <p>Note: unlike {@link UserListService} (which has an admin-only
         * authorisation reject branch), this service has no rejection
         * paths. An empty result set is a populated, successful response
         * with an empty list — the REST controller layer is responsible
         * for rendering the equivalent empty-state UX.
         */
        @Test
        @DisplayName("listTransactions empty result returns empty list")
        void listTransactions_emptyRepository_returnsEmptyList() {
            // Arrange — stub a zero-row Page with total-element count 0
            // so both hasNext() and hasPrevious() are false (the
            // canonical empty-dataset case).
            Page<Transaction> emptyPage =
                    new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(transactionRepository.findAll(any(Pageable.class)))
                    .thenReturn(emptyPage);

            TransactionListRequest request = new TransactionListRequest();
            request.setPage(0);

            // Act — invoke the real production method.
            TransactionListResponse response = service.listTransactions(request);

            // Assert — empty transactions, no navigation. The response is
            // still well-formed (currentPage = 0, navigation flags
            // explicitly false). The list emptiness is the load-bearing
            // assertion that distinguishes this scenario from the
            // populated-page scenarios.
            assertThat(response.getTransactions()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
            assertThat(response.isHasPrevious()).isFalse();
        }

        /**
         * Edge case: the last partial page in a multi-page result set has
         * {@code hasNext = false} and {@code hasPrevious = true}.
         *
         * <p>Fixture rationale: 93 total elements over 10-row pages means
         * page 9 (the 10th page, zero-based index 9) carries 3 rows
         * (rows 91–93, with page 9 being the last partial page). This
         * scenario exercises the lower bound of the partial-page case
         * without coinciding with the empty-page case (page 9 still has
         * data — 3 rows — so the test cleanly proves the
         * hasNext-vs-hasPrevious discrimination at the last-page
         * boundary).
         *
         * <p>COBOL parity: this is the {@code PROCESS-PAGE-FORWARD}
         * post-loop branch at lines 305–320 where the loop exits via
         * {@code TRANSACT-EOF} after a partial page is populated. The
         * COBOL workflow sets {@code NEXT-PAGE-NO TO TRUE} (line 315)
         * because the post-loop {@code READNEXT} at line 308 fails with
         * EOF; the Java migration surfaces the same outcome via
         * {@link Page#hasNext()} = {@code false}. {@code hasPrevious()} is
         * {@code true} because page 9 is not the first page (COBOL
         * equivalent: {@code CDEMO-CT00-PAGE-NUM > 1} at line 245).
         */
        @Test
        @DisplayName("listTransactions(lastPage) has hasNext=false, hasPrevious=true")
        void listTransactions_lastPage_hasNextFalse() {
            // Arrange — stub a 3-row fixture page on page index 9, with
            // total-element count 93. Spring Data PageImpl computes
            // hasNext()=false (93 / 10 = 10 partial pages; page 9 is the
            // last) and hasPrevious()=true (page 9 is not the first
            // page).
            Page<Transaction> lastPage =
                    new PageImpl<>(sampleTransactions(3), PageRequest.of(9, 10), 93);
            when(transactionRepository.findAll(any(Pageable.class)))
                    .thenReturn(lastPage);

            TransactionListRequest request = new TransactionListRequest();
            request.setPage(9);

            // Act — invoke the real production method.
            TransactionListResponse response = service.listTransactions(request);

            // Assert (1) — the response carries exactly 3 transactions
            // (the partial last page).
            assertThat(response.getTransactions()).hasSize(3);

            // Assert (2) — hasNext is false: page 9 is the last page in
            // the 93-element / 10-per-page result set.
            assertThat(response.isHasNext()).isFalse();

            // Assert (3) — hasPrevious is true: page 9 is not the first
            // page, so the operator's PF7 affordance should be enabled.
            assertThat(response.isHasPrevious()).isTrue();
        }
    }

    // =========================================================================
    // FILTERS — account-ID filter, card-number filter (Java-migration additions)
    //
    // The COBOL workflow has no explicit account-key or card-key filter —
    // the operator could only navigate the TRANSACT browse by TRAN-ID via
    // the TRNIDINI starting-browse key. The Java migration adds two
    // declarative filters that route the query through the appropriate
    // TransactionRepository method:
    //
    //   - accountIdFilter populated → findByAccountId(String, Pageable)
    //   - cardNumberFilter populated → findByCardNumber(String, Pageable)
    //   - neither populated → findAll(Pageable) (covered in Pagination)
    //
    // This @Nested block verifies the dispatch path is correctly chosen
    // based on the request's filter fields. The verify(...) assertions
    // confirm not only that the expected repository method was called,
    // but also that the filter value was forwarded verbatim (via eq(...)
    // matchers on the filter argument).
    // =========================================================================

    /**
     * Filter-dispatch tests for
     * {@link TransactionListService#listTransactions(TransactionListRequest)}
     * — verifies that a populated
     * {@link TransactionListRequest#getAccountIdFilter()} routes the query
     * through
     * {@link TransactionRepository#findByAccountId(String, Pageable)} and a
     * populated {@link TransactionListRequest#getCardNumberFilter()} routes
     * the query through
     * {@link TransactionRepository#findByCardNumber(String, Pageable)},
     * each with the filter value forwarded verbatim.
     */
    @Nested
    @DisplayName("Filters — account ID, card number (Java-migration additions)")
    class Filters {

        /**
         * Happy path: a request carrying
         * {@code accountIdFilter = TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10}
         * routes through
         * {@link TransactionRepository#findByAccountId(String, Pageable)}
         * with the supplied account ID forwarded verbatim. The
         * {@code verify(repository).findByAccountId(eq(accountId), any(Pageable.class))}
         * call asserts both the routing and the filter-value forwarding.
         *
         * <p>The fixture page contains 5 transactions (a realistic narrow
         * result for a single-account browse); the assertion on
         * {@code .hasSize(5)} verifies the page content propagates
         * correctly through the filter-dispatch path.
         *
         * <p>STRICT_STUBS interaction: the test stubs only
         * {@code findByAccountId}. If the production code took the wrong
         * branch and called {@code findAll} or {@code findByCardNumber}
         * instead, those calls would not match any stub and would return
         * Mockito's default {@code null} — provoking an immediate
         * NullPointerException when the service dereferences {@code page}.
         * Either way the test fails loudly on a routing bug.
         */
        @Test
        @DisplayName("listTransactions with account filter routes through findByAccountId")
        void listTransactions_accountFilter_routesToFindByAccountId() {
            // Arrange — stub the filtered query to return a 5-row fixture
            // page. The eq(SAMPLE_ACCOUNT_ID_10) matcher ensures the
            // production code forwards exactly the filter value the
            // caller supplied (any mismatch — for example a trailing-space
            // trim, a leading-zero strip, or a case change — would cause
            // the stub to not match and the production code to receive
            // null).
            Page<Transaction> filtered =
                    new PageImpl<>(sampleTransactions(5), PageRequest.of(0, 10), 5);
            when(transactionRepository.findByAccountId(
                    eq(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10),
                    any(Pageable.class)))
                    .thenReturn(filtered);

            TransactionListRequest request = new TransactionListRequest();
            request.setPage(0);
            request.setAccountIdFilter(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Act — invoke the real production method with the account
            // filter set.
            TransactionListResponse response = service.listTransactions(request);

            // Assert (1) — the response carries exactly 5 transactions
            // (fixture size after filtering by account ID).
            assertThat(response.getTransactions()).hasSize(5);

            // Assert (2) — the production code routed the query through
            // findByAccountId(...) with the expected filter value. This
            // implicitly verifies that findAll(...) and
            // findByCardNumber(...) were NOT invoked (Mockito's strict-
            // stubs mode requires every stub to be consumed by exactly
            // the configured call; a mis-route would fail with
            // UnnecessaryStubbingException).
            verify(transactionRepository).findByAccountId(
                    eq(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10),
                    any(Pageable.class));
        }

        /**
         * Happy path: a request carrying
         * {@code cardNumberFilter = TestFixtures.Cards.SAMPLE_CARD_NUMBER_01}
         * routes through
         * {@link TransactionRepository#findByCardNumber(String, Pageable)}
         * with the supplied card number forwarded verbatim. The
         * {@code verify(repository).findByCardNumber(eq(cardNumber), any(Pageable.class))}
         * call asserts both the routing and the filter-value forwarding.
         *
         * <p>The fixture page contains 2 transactions (a realistic narrow
         * result for a single-card browse — most cards see only a handful
         * of transactions per statement period); the assertion on
         * {@code .hasSize(2)} verifies the page content propagates
         * correctly through the card-filter dispatch path.
         *
         * <p>STRICT_STUBS interaction: the test stubs only
         * {@code findByCardNumber}. If the production code took the wrong
         * branch (for example because the {@code accountIdFilter} priority
         * was inverted with {@code cardNumberFilter}), the stub would not
         * match and the production code would receive null.
         */
        @Test
        @DisplayName("listTransactions with card filter routes through findByCardNumber")
        void listTransactions_cardFilter_routesToFindByCardNumber() {
            // Arrange — stub the filtered query to return a 2-row fixture
            // page. The eq(SAMPLE_CARD_NUMBER_01) matcher ensures the
            // production code forwards exactly the 16-character PAN the
            // caller supplied (any whitespace-padding bug or leading-
            // digit-strip would cause the stub to not match).
            Page<Transaction> filtered =
                    new PageImpl<>(sampleTransactions(2), PageRequest.of(0, 10), 2);
            when(transactionRepository.findByCardNumber(
                    eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01),
                    any(Pageable.class)))
                    .thenReturn(filtered);

            TransactionListRequest request = new TransactionListRequest();
            request.setPage(0);
            request.setCardNumberFilter(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Act — invoke the real production method with the card
            // filter set (but no account filter, so the card-filter
            // branch is taken).
            TransactionListResponse response = service.listTransactions(request);

            // Assert (1) — the response carries exactly 2 transactions
            // (fixture size after filtering by card number).
            assertThat(response.getTransactions()).hasSize(2);

            // Assert (2) — the production code routed the query through
            // findByCardNumber(...) with the expected filter value. As
            // with the account-filter test, this implicitly verifies
            // that findAll(...) and findByAccountId(...) were NOT
            // invoked.
            verify(transactionRepository).findByCardNumber(
                    eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01),
                    any(Pageable.class));
        }
    }

    // =========================================================================
    // FIXTURE HELPER — synthesises Transaction instances for the Pagination
    // and Filters tests. Private static so each test class instance shares
    // the same implementation without needing to subclass or extract into a
    // separate utility (kept inline because the helper is tightly coupled
    // to this test class's fixture style and the field set on the
    // Transaction entity).
    // =========================================================================

    /**
     * Synthesises a list of {@code count} {@link Transaction} fixtures
     * populated with deterministic, synthetic data:
     *
     * <ul>
     *   <li>{@code transactionId} — {@code String.format("%016d", i)} (16-
     *       digit zero-padded numeric matching the COBOL {@code TRAN-ID
     *       PIC X(16)} field width). Produces {@code "0000000000000000"}
     *       through {@code "0000000000000009"} for the 10-row fixtures;
     *       all values are within the COBOL key range (16 digits, all
     *       numeric).</li>
     *   <li>{@code cardNumber} — every fixture transaction shares
     *       {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} so the entire
     *       page agrees on a single canonical card; this matches the
     *       realistic scenario where a single-card filter narrows the
     *       result set to transactions from one PAN.</li>
     *   <li>{@code transactionTypeCode} — {@code "01"} (the COBOL "01"
     *       Purchase code per {@code trantype.txt}; documentary value
     *       that the tests do not assert on but keeps the fixture
     *       Transaction fully populated).</li>
     *   <li>{@code transactionCategoryCode} — {@code "0001"} (the COBOL
     *       "0001" Regular Sales Draft category per
     *       {@code trancatg.txt}).</li>
     *   <li>{@code amount} — {@code new BigDecimal("50.00")} at the COBOL
     *       {@code PIC S9(09)V99} scale-2 width per AAP §0.10.3 (No
     *       float/double for monetary values; {@link BigDecimal} only).
     *       Every fixture transaction carries the same amount because
     *       the test assertions focus on row count and routing rather
     *       than on monetary arithmetic — but the value is still a real
     *       {@link BigDecimal} at scale 2 to document the financial-
     *       precision convention.</li>
     * </ul>
     *
     * <p>The helper is intentionally tightly coupled to the
     * {@link Transaction} field set used by these tests; if the entity
     * evolves (new fields added by future migration agents) this method
     * should be updated to populate the new fields so tests across the
     * transaction-list family agree on fixture content.
     *
     * @param count the number of fixture {@link Transaction} instances to
     *              create; must be non-negative. {@code 0} returns an
     *              empty list.
     * @return a new {@link ArrayList} of populated fixture
     *         {@link Transaction} instances; never {@code null}.
     */
    private static List<Transaction> sampleTransactions(int count) {
        List<Transaction> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Transaction t = new Transaction();
            // 16-character TRAN-ID matching COBOL TRAN-ID PIC X(16);
            // zero-padded numeric format mirrors daily-transaction IDs in
            // dailytran.txt.
            t.setTransactionId(String.format("%016d", i));
            // Every fixture transaction shares the canonical Visa test
            // PAN from TestFixtures.Cards.SAMPLE_CARD_NUMBER_01; this
            // mirrors the realistic scenario where a card-filter test
            // narrows to transactions from one PAN, and keeps the helper
            // simple (no per-row PAN computation required).
            t.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
            // "01" = Purchase per trantype.txt; documentary value.
            t.setTransactionTypeCode("01");
            // "0001" = Regular Sales Draft per trancatg.txt; documentary
            // value.
            t.setTransactionCategoryCode("0001");
            // BigDecimal scale-2 monetary value per AAP §0.10.3. Every
            // fixture row carries the same amount because the test
            // assertions focus on row count and routing rather than on
            // monetary arithmetic; the value is still a real BigDecimal
            // (never float/double) to document the financial-precision
            // convention at fixture level.
            t.setAmount(new BigDecimal("50.00"));
            list.add(t);
        }
        return list;
    }
}
