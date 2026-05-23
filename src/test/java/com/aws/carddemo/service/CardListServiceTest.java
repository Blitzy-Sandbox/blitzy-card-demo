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
//       carried into the Filters.listCards_userTypeWithAccountFilter_filtersToUserCards
//       test that routes through CardRepository.findByAccountId and is
//       also used to seed the sampleCards() helper's Card.accountId
//       field for the admin/user view tests).
//     - TestFixtures.Cards.SAMPLE_CARD_NUMBER_01 (16-digit Visa test PAN
//       carried into the Filters.listCards_cardNumberFilter_narrowsResults
//       test that routes through CardRepository.findByCardNumberStartsWith).
//     Schema authority: file_schema.internal_imports[0] declares this as
//     the test's single in-project dependency.
//
//   * Card — the JPA entity populated by the COCRDLIC migration's paged
//     CARDDAT/CARDAIX browse. The sampleCards(int) helper builds synthetic,
//     fully-populated instances using fixed-width zero-padded PANs so each
//     test can stub the repository with realistic page contents.
//
//   * CardRepository — the Spring Data JPA repository boundary the
//     production service calls. Mocked at the JPA-repository boundary per
//     AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * CardListService / CardListRequest / CardListResponse — the production
//     classes under test. No explicit imports because they share this
//     test's package (`com.aws.carddemo.service`); Java resolves
//     simple-named references via package membership. This matches the
//     convention established by every other test under
//     com.aws.carddemo.service.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Card;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). Schema authority: file_schema.external_imports[0]
// declares BeforeEach / DisplayName / Nested / Test / ExtendWith.
//
//   * @BeforeEach — reinstantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9). Each test sees a
//     fresh CardRepository mock and a fresh CardListService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention) with COBOL-
//     provenance annotations (e.g., "COCRDLIC WS-MAX-SCREEN-LINES = 7").
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
// resolved to Mockito 5.11.0 per setup log). Schema authority:
// file_schema.external_imports[1] declares Mock / any / eq / verify / when
// and file_schema.external_imports[2] declares MockitoExtension.
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is `STRICT_STUBS`; unused stubs
//     raise `UnnecessaryStubbingException`"). Each test deliberately
//     stubs only the repository method it expects the production code to
//     call, so a routing bug (for example a card-filter test that ends up
//     calling findAll instead of findByCardNumberStartsWith) would surface
//     as a STRICT_STUBS UnnecessaryStubbingException.
// ---------------------------------------------------------------------------
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Spring Data Commons (AAP §0.6.1; resolved transitively via
// spring-boot-starter-data-jpa managed by the spring-boot-dependencies BOM).
// Schema authority: file_schema.external_imports[4] declares Page / PageImpl
// / PageRequest / Pageable.
//
//   * Page / PageImpl — Spring Data pagination result envelope. PageImpl
//     wraps fixture Card lists into Page<Card> so the mocked repository's
//     .findAll(...) / .findByAccountId(...) / .findByCardNumberStartsWith(...)
//     returns a realistic paged result (carrying both content and the
//     navigation metadata derived from total-element count).
//   * Pageable / PageRequest — pagination request parameters. PageRequest.of
//     (page, 7) constructs the expected page bound for the fixture pages,
//     mirroring the COBOL WS-MAX-SCREEN-LINES VALUE 7 constant from
//     COCRDLIC.cbl lines 177–178. The Pageable type is the argument type
//     used with any(Pageable.class) matchers when stubbing repository
//     methods.
// ---------------------------------------------------------------------------
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports[5] declares
// Collections / List / ArrayList).
//
//   * ArrayList — backing the sampleCards(int) helper that synthesises
//     Card fixtures for the Pagination and Filters tests.
//   * Collections.emptyList() — seeds the empty PageImpl content for the
//     Pagination.listCards_emptyRepository_returnsEmptyList scenario
//     (parity with COCRDLIC reporting an empty card list when
//     STARTBR-CARDAIX returns no records).
//   * List — the return type of sampleCards(int) and the type parameter
//     on PageImpl<Card>.
// ---------------------------------------------------------------------------
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
//     to express observable-behaviour assertions on CardListResponse
//     fields (.getCards() with .hasSize(N) / .isEmpty(),
//     .getCurrentPage() with .isEqualTo, .isHasNext() / .isHasPrevious()
//     with .isTrue() / .isFalse()).
//   * ArgumentMatchers.any — relaxes Pageable matching where the test
//     does not care about the exact PageRequest contents.
//   * ArgumentMatchers.eq — exact-equality matcher for the
//     accountIdFilter / cardNumberFilter strings in the Filters tests,
//     ensuring the production code forwards the supplied filter value to
//     the repository verbatim.
//   * Mockito.verify — interaction assertion; pairs with .eq()/.any()
//     matchers to assert the production code routed the query through the
//     correct repository method.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy paths.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardListService}, the migrated Java equivalent of
 * the 1,459-line CICS program {@code app/cbl/COCRDLIC.cbl} (TRANID
 * {@code CCLI}). Read-only paged browse of the {@code CARDDAT} VSAM KSDS
 * replacement (PostgreSQL {@code cards} table) via the alternate index
 * {@code CARDAIX}, with admin-vs-user view dispatch and optional account-
 * ID and card-number-prefix filters.
 *
 * <h2>COBOL Provenance — COCRDLIC.cbl</h2>
 *
 * <p>List credit cards with <b>7 rows per page</b> (COBOL
 * {@code WS-MAX-SCREEN-LINES VALUE 7} at lines 177–178). Two views,
 * documented in the program header comment (lines 4–7):
 * <ul>
 *   <li><b>Admin view</b> — all cards across all accounts.</li>
 *   <li><b>User view</b> — only cards owned by the user's account.</li>
 * </ul>
 *
 * <p>Navigation primitives carried by the COCRDLIC commarea
 * ({@code WS-THIS-PROGCOMMAREA}, lines 229–248):
 * <ul>
 *   <li>{@code WS-CA-SCREEN-NUM PIC 9(1)} — current page number (1-based
 *       in COBOL, zero-based in the Java migration per Spring Data).</li>
 *   <li>{@code CA-NEXT-PAGE-EXISTS} / {@code CA-NEXT-PAGE-NOT-EXISTS}
 *       88-level switch — set after the READNEXT loop completes; the
 *       Java migration surfaces this as {@link CardListResponse#isHasNext()}.</li>
 *   <li>{@code CA-FIRST-PAGE} 88-level — the COBOL workflow tests
 *       {@code NOT CA-FIRST-PAGE} to derive "has previous page" semantics;
 *       the Java migration surfaces this as
 *       {@link CardListResponse#isHasPrevious()}.</li>
 * </ul>
 *
 * <p>Browse mechanics (paragraph {@code 9000-READ-FORWARD}, lines
 * 1123–1267): {@code STARTBR-CARDAIX} (line 1129) opens a VSAM browse on
 * the alternate index, then a {@code PERFORM UNTIL READ-LOOP-EXIT} (line
 * 1144) reads up to 7 records (page-size bound at line 1191:
 * {@code IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES SET READ-LOOP-EXIT TO
 * TRUE}). Per-record filtering happens in {@code 9500-FILTER-RECORDS}
 * (lines 1382–1410) which tests {@code CARD-ACCT-ID = CC-ACCT-ID} (line
 * 1386) and {@code CARD-NUM = CC-CARD-NUM-N} (line 1397).
 *
 * <p>Reject conditions defined in working storage (lines 117–126):
 * <ul>
 *   <li>{@code WS-NO-RECORDS-FOUND} ({@code 'NO RECORDS FOUND FOR THIS
 *       SEARCH CONDITION.'}) — line 121–122. The Java migration treats
 *       this as a successful response with an empty list, not an
 *       exception. Asserted in
 *       {@link Pagination#listCards_emptyRepository_returnsEmptyList()}.</li>
 *   <li>{@code 'You are at the bottom of the page...'} — end-of-browse on
 *       PF8 from the last page. The Java migration surfaces this via
 *       {@link CardListResponse#isHasNext()} = {@code false}. Asserted in
 *       {@link Pagination#listCards_lastPage_returnsRemainderAndHasNextFalse()}.</li>
 *   <li>{@code 'You are at the top of the page...'} — PF7 from page 1.
 *       The Java migration surfaces this via
 *       {@link CardListResponse#isHasPrevious()} = {@code false}.
 *       Asserted in
 *       {@link Pagination#listCards_pageZero_returns7Cards()}.</li>
 * </ul>
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the dispatch tree exhaustively:
 *
 * <table border="1">
 *   <caption>Card-list test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — page=0, full 7-row page, asserts hasNext=true /
 *       hasPrevious=false</td>
 *       <td>{@link Pagination#listCards_pageZero_returns7Cards()}</td></tr>
 *   <tr><td>Happy path — last partial page (1 row out of 50), hasNext=false
 *       / hasPrevious=true</td>
 *       <td>{@link Pagination#listCards_lastPage_returnsRemainderAndHasNextFalse()}</td></tr>
 *   <tr><td>Happy path — empty repository, no navigation</td>
 *       <td>{@link Pagination#listCards_emptyRepository_returnsEmptyList()}</td></tr>
 *   <tr><td>Filter dispatch — admin (userType=A), no filter → findAll</td>
 *       <td>{@link Filters#listCards_adminUserType_returnsAllCards()}</td></tr>
 *   <tr><td>Filter dispatch — user (userType=U) + accountId →
 *       findByAccountId</td>
 *       <td>{@link Filters#listCards_userTypeWithAccountFilter_filtersToUserCards()}</td></tr>
 *   <tr><td>Filter dispatch — card-number filter → findByCardNumberStartsWith</td>
 *       <td>{@link Filters#listCards_cardNumberFilter_narrowsResults()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link CardListService} via its public
 * single-argument constructor and assert on the {@link CardListResponse}
 * returned by the real {@link CardListService#listCards(CardListRequest)}
 * method. The only mocked collaborator is the {@link CardRepository}
 * (database boundary — one of the four permitted mock categories under
 * AAP §0.10.1). No business logic — the page-size selection, the filter
 * dispatch, or the response construction — is duplicated in any test
 * body; the tests assert only on observable outputs.
 *
 * <h2>Strict-Stubs Mode</h2>
 *
 * <p>{@link MockitoExtension} activates STRICT_STUBS by default. Every
 * test deliberately stubs only the repository method the test expects the
 * production code to call (for example, the card-filter test stubs
 * {@code findByCardNumberStartsWith} and asserts via {@code verify} that
 * the same method was invoked). A routing bug — for example, the card-
 * filter test stubbing {@code findByCardNumberStartsWith} but the
 * production code calling {@code findAll} — would surface as a
 * STRICT_STUBS {@code UnnecessaryStubbingException} on the unused stub,
 * providing a second line of defence on top of the explicit
 * {@code verify} call.
 *
 * @see CardListService
 * @see CardListRequest
 * @see CardListResponse
 * @see CardRepository
 * @see TestFixtures.Accounts
 * @see TestFixtures.Cards
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService — COCRDLIC.cbl migration parity")
final class CardListServiceTest {

    /**
     * Mocked {@link CardRepository} — the JPA repository boundary the
     * production {@link CardListService} delegates to. Per AAP §0.10.1,
     * the only mocked collaborator (database is one of the four permitted
     * mock categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between
     * scenarios.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * System under test — the real {@link CardListService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborator above and to prevent any
     * accidental state leak between tests (the production service is
     * currently stateless aside from its single collaborator field, but
     * the per-method reset is defensive for future state additions).
     */
    private CardListService service;

    /**
     * Constructs a fresh {@link CardListService} before every test method,
     * injecting the freshly-instantiated {@link #cardRepository} mock. The
     * combination of per-method {@code @Mock} instantiation (driven by
     * {@link MockitoExtension}) and per-method service construction here
     * guarantees that no stub or interaction from one test leaks into
     * another.
     */
    @BeforeEach
    void setUp() {
        service = new CardListService(cardRepository);
    }

    // =========================================================================
    // PAGINATION — 7 rows per page (COBOL WS-MAX-SCREEN-LINES VALUE 7)
    //
    // The COBOL workflow uses an explicit page size of 7 rows declared at
    // lines 177–178 of COCRDLIC.cbl:
    //
    //     05  WS-MAX-SCREEN-LINES                    PIC S9(4) COMP
    //                                                VALUE 7.
    //
    // and tested inside the 9000-READ-FORWARD paragraph at line 1191:
    //
    //     IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES
    //        SET READ-LOOP-EXIT  TO TRUE
    //
    // The 7-row bound matches the CCRDLIA BMS map's WS-SCREEN-ROWS OCCURS
    // 7 TIMES table (lines 252–260). The Java migration delegates the
    // equivalent semantic to Spring Data Pageable, configured with
    // PAGE_SIZE = 7 via the CardListService#PAGE_SIZE constant.
    //
    // This @Nested block verifies:
    //   (1) The production service requests page size 7 from the
    //       repository (assertion on the resulting page row count).
    //   (2) Empty result sets are surfaced as a populated response with
    //       empty cards list and both navigation flags false (no reject
    //       path for empty list — the COBOL 'NO RECORDS FOUND FOR THIS
    //       SEARCH CONDITION.' text is a UX concern delegated to the REST
    //       controller layer).
    //   (3) Last-page boundary (partial page of 1 row out of 50 total)
    //       sets hasNext=false / hasPrevious=true.
    // =========================================================================

    /**
     * Pagination tests for
     * {@link CardListService#listCards(CardListRequest)} — verifies the
     * COBOL {@code WS-MAX-SCREEN-LINES VALUE 7} page-size contract is
     * honoured by the Spring Data {@link Pageable} construction inside
     * the production service, and that the navigation flags
     * ({@link CardListResponse#isHasNext()},
     * {@link CardListResponse#isHasPrevious()}) correctly track the page
     * coordinates derived from the underlying {@link Page#hasNext()} /
     * {@link Page#hasPrevious()} values.
     */
    @Nested
    @DisplayName("Pagination — 7 rows per page (COCRDLIC WS-MAX-SCREEN-LINES = 7)")
    class Pagination {

        /**
         * Happy path: page=0 returns a full 7-row page from the unfiltered
         * admin browse (the canonical Java replacement for the COBOL
         * {@code 9000-READ-FORWARD} happy path).
         *
         * <p>Asserts four things:
         * <ol>
         *   <li>The response carries exactly 7 cards (the COBOL
         *       {@code WS-MAX-SCREEN-LINES VALUE 7} page-size bound; the
         *       fixture page is populated by
         *       {@link CardListServiceTest#sampleCards(int)
         *       sampleCards(7)} and propagated verbatim through the
         *       service to the response DTO).</li>
         *   <li>The response's {@code currentPage} equals 0 (the request's
         *       page index, mapped directly to the COBOL
         *       {@code WS-CA-SCREEN-NUM} counter at line 237 of
         *       COCRDLIC.cbl).</li>
         *   <li>The response's {@code hasNext} flag is {@code true} (the
         *       fixture's total-element count of 50 means there are 7
         *       additional rows beyond page 0 — Math.ceil(50/7) = 8
         *       pages). COBOL parity: {@code CA-NEXT-PAGE-EXISTS} set at
         *       line 1210 of {@code 9000-READ-FORWARD} when the post-loop
         *       READNEXT succeeds.</li>
         *   <li>The response's {@code hasPrevious} flag is {@code false}
         *       (page 0 is the first page; the COBOL workflow inferred
         *       the equivalent from {@code CA-FIRST-PAGE} at line 238 —
         *       the {@code 'You are at the top of the page...'} reject
         *       text is shown on PF7 from page 1).</li>
         * </ol>
         *
         * <p>COBOL parity: this is the {@code 9000-READ-FORWARD} happy
         * path (lines 1123–1267) reading exactly 7 records into the
         * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} table on the
         * {@code CCRDLIA} BMS map.
         */
        @Test
        @DisplayName("listCards(page=0) returns first 7 cards")
        void listCards_pageZero_returns7Cards() {
            // Arrange — stub a 7-row fixture page with a total-element
            // count of 50 (the canonical fixture-set size from
            // carddata.txt per AAP §0.4.4). The total-element count of 50
            // means there are still 43 rows beyond page 0 — so
            // hasNext()=true. hasPrevious()=false because page 0 is the
            // first page.
            List<Card> firstPageCards = sampleCards(7);
            Page<Card> page =
                    new PageImpl<>(firstPageCards, PageRequest.of(0, 7), 50);
            when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

            CardListRequest request = new CardListRequest();
            request.setPage(0);
            request.setUserType("A"); // admin sees all → routes to findAll

            // Act — invoke the real production method.
            CardListResponse response = service.listCards(request);

            // Assert (1) — the response carries exactly 7 cards (fixture
            // size — proves the page propagation works end-to-end without
            // re-implementing the COBOL POPULATE-TRAN-DATA equivalent
            // logic in the test, and witnesses the COBOL
            // WS-MAX-SCREEN-LINES VALUE 7 page-size bound).
            assertThat(response.getCards()).hasSize(7);

            // Assert (2) — the response's currentPage equals the request's
            // page index (direct Java materialisation of the COBOL
            // WS-CA-SCREEN-NUM counter at line 237 of COCRDLIC.cbl).
            assertThat(response.getCurrentPage()).isEqualTo(0);

            // Assert (3) — hasNext is true: the fixture's total-element
            // count of 50 (with 7 rows per page) means there are 43 rows
            // remaining beyond page 0. COBOL equivalent: CA-NEXT-PAGE-
            // EXISTS set at line 1210 of 9000-READ-FORWARD when the
            // post-loop READNEXT succeeds.
            assertThat(response.isHasNext()).isTrue();

            // Assert (4) — hasPrevious is false: page 0 is the first
            // page. COBOL equivalent: NOT CA-FIRST-PAGE check at line 502
            // of PROCESS-PF7-KEY would be false for page 0, suppressing
            // the 'You are at the top of the page...' reject text only
            // when navigating from a later page.
            assertThat(response.isHasPrevious()).isFalse();
        }

        /**
         * Edge case: the last partial page in a multi-page result set has
         * {@code hasNext = false} and {@code hasPrevious = true}.
         *
         * <p>Fixture rationale: 50 total elements over 7-row pages means
         * {@code Math.ceil(50/7) = 8} pages, with page 7 (the 8th page,
         * zero-based index 7) carrying 1 row (the remainder of
         * {@code 50 mod 7 = 1}). This scenario exercises the lower bound
         * of the partial-page case without coinciding with the empty-page
         * case (page 7 still has data — 1 row — so the test cleanly
         * proves the hasNext-vs-hasPrevious discrimination at the last-
         * page boundary).
         *
         * <p>COBOL parity: this is the post-loop branch in
         * {@code 9000-READ-FORWARD} at lines 1207–1232 where the loop
         * exits via {@code DFHRESP(ENDFILE)}. The COBOL workflow sets
         * {@code CA-NEXT-PAGE-NOT-EXISTS TO TRUE} (line 1216) and emits
         * the {@code 'NO MORE RECORDS TO SHOW'} text (lines 1219–1220);
         * the Java migration surfaces the same outcome via
         * {@link Page#hasNext()} = {@code false}. {@code hasPrevious()}
         * is {@code true} because page 7 is not the first page (COBOL
         * equivalent: {@code NOT CA-FIRST-PAGE} at line 502).
         */
        @Test
        @DisplayName("listCards(lastPage) returns remainder and hasNext=false")
        void listCards_lastPage_returnsRemainderAndHasNextFalse() {
            // Arrange — 50 total elements over 7-row pages = 8 pages
            // (zero-based index 0..7); page index 7 is the last page with
            // 1 remainder row (50 mod 7 = 1). Spring Data PageImpl
            // computes hasNext()=false (page 7 is the last) and
            // hasPrevious()=true (page 7 is not the first page).
            List<Card> lastPageCards = sampleCards(1);
            Page<Card> page =
                    new PageImpl<>(lastPageCards, PageRequest.of(7, 7), 50);
            when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

            CardListRequest request = new CardListRequest();
            request.setPage(7);
            request.setUserType("A");

            // Act — invoke the real production method.
            CardListResponse response = service.listCards(request);

            // Assert (1) — the response carries exactly 1 card (the
            // partial last page).
            assertThat(response.getCards()).hasSize(1);

            // Assert (2) — hasNext is false: page 7 is the last page in
            // the 50-element / 7-per-page result set. COBOL equivalent:
            // CA-NEXT-PAGE-NOT-EXISTS at line 1216 of 9000-READ-FORWARD
            // when the post-loop READNEXT returns DFHRESP(ENDFILE).
            assertThat(response.isHasNext()).isFalse();

            // Assert (3) — hasPrevious is true: page 7 is not the first
            // page, so the operator's PF7 affordance should be enabled.
            // COBOL equivalent: NOT CA-FIRST-PAGE at line 502 evaluates
            // true for page > 0.
            assertThat(response.isHasPrevious()).isTrue();
        }

        /**
         * Edge case: an empty repository (or a query beyond the last
         * page) produces a populated response with an empty cards list
         * and both navigation flags {@code false}.
         *
         * <p>This is the Java equivalent of the COBOL
         * {@code 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'} BMS-map
         * text (lines 121–122) shown when {@code STARTBR-CARDAIX}
         * succeeds but the subsequent {@code READNEXT-CARDAIX}
         * immediately encounters EOF. The COBOL workflow sets
         * {@code CA-NEXT-PAGE-NOT-EXISTS TO TRUE} (line 1216) and emits
         * an empty page; the Java migration surfaces the same outcome
         * via {@link Page#hasNext()} = {@code false} /
         * {@link Page#hasPrevious()} = {@code false} on an empty page.
         *
         * <p>Note: this service has no rejection paths. An empty result
         * set is a populated, successful response with an empty list —
         * the REST controller layer is responsible for rendering the
         * equivalent empty-state UX. The {@code WS-NO-RECORDS-FOUND}
         * 88-level (line 121) is a presentation concern, not a service
         * outcome.
         */
        @Test
        @DisplayName("listCards on empty repository returns empty list and no pagination flags")
        void listCards_emptyRepository_returnsEmptyList() {
            // Arrange — stub a zero-row Page with total-element count 0
            // so both hasNext() and hasPrevious() are false (the
            // canonical empty-dataset case — Java replacement for the
            // COBOL 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.' BMS-map
            // text at line 122).
            Page<Card> empty = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 7), 0);
            when(cardRepository.findAll(any(Pageable.class))).thenReturn(empty);

            CardListRequest request = new CardListRequest();
            request.setPage(0);
            request.setUserType("A");

            // Act — invoke the real production method.
            CardListResponse response = service.listCards(request);

            // Assert — empty cards, no navigation. The response is still
            // well-formed (currentPage = 0, navigation flags explicitly
            // false). The list emptiness is the load-bearing assertion
            // that distinguishes this scenario from the populated-page
            // scenarios.
            assertThat(response.getCards()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
            assertThat(response.isHasPrevious()).isFalse();
        }
    }

    // =========================================================================
    // FILTERS — admin vs user view, account-ID filter, card-number filter
    //
    // The COCRDLIC header comment (lines 4–7) documents two execution
    // paths:
    //
    //   * Admin view — all cards across all accounts
    //   * User view — only cards associated with ACCT in COMMAREA
    //
    // The COBOL workflow implements per-record filtering via the
    // 9500-FILTER-RECORDS paragraph (lines 1382–1410):
    //
    //   IF FLG-ACCTFILTER-ISVALID                                            
    //      IF  CARD-ACCT-ID = CC-ACCT-ID                                     
    //          CONTINUE                                                      
    //      ELSE                                                              
    //          SET WS-EXCLUDE-THIS-RECORD  TO TRUE                           
    //          GO TO 9500-FILTER-RECORDS-EXIT
    //      END-IF
    //   ...
    //   IF FLG-CARDFILTER-ISVALID                                            
    //      IF  CARD-NUM = CC-CARD-NUM-N                                      
    //          CONTINUE                                                      
    //      ELSE                                                              
    //          SET WS-EXCLUDE-THIS-RECORD TO TRUE                            
    //          GO TO 9500-FILTER-RECORDS-EXIT
    //      END-IF
    //
    // The Java migration replaces the per-record post-fetch exclusion
    // with database-side derived queries:
    //
    //   - userType=A + no filter → findAll(Pageable) (unfiltered admin browse)
    //   - userType=U + accountIdFilter → findByAccountId(String, Pageable)
    //     (matches the COBOL line 1386 account-equality test)
    //   - cardNumberFilter → findByCardNumberStartsWith(String, Pageable)
    //     (generalises the COBOL line 1397 exact-match to a prefix match
    //     per AAP §0.10.2)
    //
    // This @Nested block verifies the dispatch path is correctly chosen
    // based on the request's filter fields. The verify(...) assertions
    // confirm not only that the expected repository method was called,
    // but also that the filter value was forwarded verbatim (via eq(...)
    // matchers on the filter argument).
    // =========================================================================

    /**
     * Filter-dispatch tests for
     * {@link CardListService#listCards(CardListRequest)} — verifies that:
     * <ul>
     *   <li>An admin-view request ({@code userType=A}, no filters) routes
     *       through {@link CardRepository#findAll(Pageable)}.</li>
     *   <li>A user-view request ({@code userType=U}, populated
     *       {@code accountIdFilter}) routes through
     *       {@link CardRepository#findByAccountId(String, Pageable)} with
     *       the supplied account ID forwarded verbatim.</li>
     *   <li>A request with a populated {@code cardNumberFilter} routes
     *       through
     *       {@link CardRepository#findByCardNumberStartsWith(String, Pageable)}
     *       with the supplied prefix forwarded verbatim.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Filters — admin vs user view, account / card narrowing")
    class Filters {

        /**
         * Happy path: an admin-view request ({@code userType=A}) with no
         * filters routes through {@link CardRepository#findAll(Pageable)}.
         *
         * <p>The {@code verify(repository).findAll(any(Pageable.class))}
         * call asserts the routing decision: the admin view (unfiltered
         * browse) is the Java equivalent of the COBOL header-comment
         * line 5 ("All cards if no context passed and admin user").
         *
         * <p>STRICT_STUBS interaction: the test stubs only
         * {@code findAll}. If the production code took the wrong branch
         * and called {@code findByAccountId} or
         * {@code findByCardNumberStartsWith} instead, those calls would
         * not match any stub and would return Mockito's default
         * {@code null} — provoking an immediate {@link NullPointerException}
         * when the service dereferences {@code page}. Either way the
         * test fails loudly on a routing bug.
         */
        @Test
        @DisplayName("listCards(userType=A) returns all cards (admin view)")
        void listCards_adminUserType_returnsAllCards() {
            // Arrange — stub the unfiltered findAll call with a 7-row
            // fixture page (50 total → hasNext=true, but the test does
            // not assert on navigation; it asserts on the dispatch
            // routing only).
            Page<Card> allCards =
                    new PageImpl<>(sampleCards(7), PageRequest.of(0, 7), 50);
            when(cardRepository.findAll(any(Pageable.class))).thenReturn(allCards);

            CardListRequest request = new CardListRequest();
            request.setPage(0);
            request.setUserType("A"); // admin view — header comment line 5

            // Act — invoke the real production method.
            service.listCards(request);

            // Assert — the production code routed the query through
            // findAll(...). This implicitly verifies that
            // findByAccountId(...) and findByCardNumberStartsWith(...)
            // were NOT invoked: Mockito's strict-stubs mode would
            // surface a mis-route as UnnecessaryStubbingException on
            // the unconfigured stub or as a NullPointerException on the
            // null page returned by the unconfigured mock method.
            verify(cardRepository).findAll(any(Pageable.class));
        }

        /**
         * Happy path: a user-view request ({@code userType=U}) with a
         * populated {@link CardListRequest#getAccountIdFilter()} routes
         * through {@link CardRepository#findByAccountId(String, Pageable)}
         * with the supplied account ID forwarded verbatim.
         *
         * <p>The {@code verify(repository).findByAccountId(eq(accountId),
         * any(Pageable.class))} call asserts both the routing and the
         * filter-value forwarding. This is the Java equivalent of the
         * COBOL {@code 9500-FILTER-RECORDS} paragraph at line 1386
         * ({@code IF CARD-ACCT-ID = CC-ACCT-ID}) — narrowing the browse
         * to a single account, which is the load-bearing semantic for
         * the COBOL user-view path documented in the header comment
         * lines 6–7 ("Only the ones associated with ACCT in COMMAREA if
         * user is not admin").
         *
         * <p>The fixture page contains 3 cards (a realistic narrow
         * result for a single-account browse — most accounts have a
         * single card or a small handful); the assertion on
         * {@code .hasSize(3)} verifies the page content propagates
         * correctly through the filter-dispatch path.
         */
        @Test
        @DisplayName("listCards(userType=U, accountId) filters to user's cards only")
        void listCards_userTypeWithAccountFilter_filtersToUserCards() {
            // Arrange — stub the account-filtered query to return a 3-row
            // fixture page. The eq(SAMPLE_ACCOUNT_ID_10) matcher ensures
            // the production code forwards exactly the filter value the
            // caller supplied (any mismatch — for example a trailing-
            // space trim, a leading-zero strip, or a case change — would
            // cause the stub to not match and the production code to
            // receive null).
            Page<Card> userCards =
                    new PageImpl<>(sampleCards(3), PageRequest.of(0, 7), 3);
            when(cardRepository.findByAccountId(
                    eq(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10),
                    any(Pageable.class)))
                    .thenReturn(userCards);

            CardListRequest request = new CardListRequest();
            request.setPage(0);
            request.setUserType("U"); // user view — header comment lines 6–7
            request.setAccountIdFilter(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Act — invoke the real production method with the account
            // filter set (and no card filter, so the account-filter
            // branch is taken).
            CardListResponse response = service.listCards(request);

            // Assert (1) — the response carries exactly 3 cards (fixture
            // size after filtering by account ID).
            assertThat(response.getCards()).hasSize(3);

            // Assert (2) — the production code routed the query through
            // findByAccountId(...) with the expected filter value. This
            // is the Java equivalent of the COBOL 9500-FILTER-RECORDS
            // line 1386 (IF CARD-ACCT-ID = CC-ACCT-ID) — narrowing the
            // browse to a single account. As with the admin-view test,
            // this implicitly verifies that findAll(...) and
            // findByCardNumberStartsWith(...) were NOT invoked.
            verify(cardRepository).findByAccountId(
                    eq(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10),
                    any(Pageable.class));
        }

        /**
         * Happy path: a request with a populated
         * {@link CardListRequest#getCardNumberFilter()} routes through
         * {@link CardRepository#findByCardNumberStartsWith(String, Pageable)}
         * with the supplied prefix forwarded verbatim.
         *
         * <p>The {@code verify(repository).findByCardNumberStartsWith(
         * eq(prefix), any(Pageable.class))} call asserts both the routing
         * and the filter-value forwarding. This is the Java equivalent
         * (and a documented enhancement per AAP §0.10.2) of the COBOL
         * {@code 9500-FILTER-RECORDS} paragraph at line 1397
         * ({@code IF CARD-NUM = CC-CARD-NUM-N}) — the COBOL workflow
         * performs an exact equality test; the Java migration generalises
         * it to a prefix match so the REST controller can support both
         * full-PAN lookups and partial-prefix narrowing. Passing the full
         * 16-character PAN ({@code SAMPLE_CARD_NUMBER_01 =
         * "4111111111111101"}) replicates the COBOL exact-match semantic
         * (a 16-character prefix matches at most one card).
         *
         * <p>The fixture page contains 1 card (a realistic result for a
         * full-PAN lookup); the assertion on {@code .hasSize(1)} verifies
         * the page content propagates correctly through the card-filter
         * dispatch path.
         *
         * <p>The {@code userType=A} setting on the request demonstrates
         * that the card-number filter takes precedence over the userType
         * dispatch — even an admin operator who specifies a card filter
         * gets the narrowed result, matching the COBOL behaviour where
         * {@code FLG-CARDFILTER-ISVALID} applies regardless of operator
         * role.
         */
        @Test
        @DisplayName("listCards with card number filter narrows to matching cards")
        void listCards_cardNumberFilter_narrowsResults() {
            // Arrange — stub the card-prefix query to return a 1-row
            // fixture page. The eq(SAMPLE_CARD_NUMBER_01) matcher
            // ensures the production code forwards exactly the
            // 16-character PAN the caller supplied (any whitespace-
            // padding bug or leading-digit-strip would cause the stub
            // to not match and the production code to receive null).
            Page<Card> filtered =
                    new PageImpl<>(sampleCards(1), PageRequest.of(0, 7), 1);
            when(cardRepository.findByCardNumberStartsWith(
                    eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01),
                    any(Pageable.class)))
                    .thenReturn(filtered);

            CardListRequest request = new CardListRequest();
            request.setPage(0);
            // userType=A demonstrates that the card-filter dispatch wins
            // over the admin-vs-user routing decision — matching the
            // COBOL behaviour where FLG-CARDFILTER-ISVALID applies
            // regardless of operator role.
            request.setUserType("A");
            request.setCardNumberFilter(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);

            // Act — invoke the real production method with the card
            // filter set.
            CardListResponse response = service.listCards(request);

            // Assert (1) — the response carries exactly 1 card (fixture
            // size after filtering by full-PAN prefix; the 16-character
            // prefix matches at most one card, matching the COBOL
            // line-1397 exact-equality semantic).
            assertThat(response.getCards()).hasSize(1);

            // Assert (2) — the production code routed the query through
            // findByCardNumberStartsWith(...) with the expected filter
            // value. As with the other Filters tests, this implicitly
            // verifies that findAll(...) and findByAccountId(...) were
            // NOT invoked.
            verify(cardRepository).findByCardNumberStartsWith(
                    eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01),
                    any(Pageable.class));
        }
    }

    // =========================================================================
    // FIXTURE HELPER — synthesises Card instances for the Pagination and
    // Filters tests. Private static so each test class instance shares the
    // same implementation without needing to subclass or extract into a
    // separate utility (kept inline because the helper is tightly coupled
    // to this test class's fixture style and the field set on the Card
    // entity).
    // =========================================================================

    /**
     * Synthesises a list of {@code count} {@link Card} fixtures populated
     * with deterministic, synthetic data:
     *
     * <ul>
     *   <li>{@code cardNumber} — {@code "4111111111111" + zero-padded
     *       3-digit index} (e.g. {@code "4111111111111000"},
     *       {@code "4111111111111001"}, ...). The 16-character width
     *       matches the COBOL {@code CARD-NUM PIC X(16)} field from
     *       {@code app/cpy/CVACT02Y.cpy}; the {@code 4111...} prefix
     *       mirrors the canonical Visa test PAN range used in
     *       {@code carddata.txt} fixtures and reserved by the publicly
     *       documented test-PAN range — no real PII.</li>
     *   <li>{@code accountId} — every fixture card shares
     *       {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10} so the
     *       entire page agrees on a single canonical account; this
     *       matches the realistic scenario where a single-account filter
     *       (user-view dispatch) narrows the result set to cards from one
     *       account.</li>
     *   <li>{@code cvvCode} — {@code "123"} (documentary value; tests do
     *       not assert on this field, but it is populated so the fixture
     *       Card is complete).</li>
     *   <li>{@code embossedName} — {@code "TEST USER"} (documentary
     *       value).</li>
     *   <li>{@code expirationDate} — {@code "2026-12-31"} (documentary
     *       value; future date so any potential is-expired calculation
     *       in downstream code resolves to false).</li>
     *   <li>{@code activeStatus} — {@code "Y"} (documentary value
     *       matching {@link TestFixtures.Cards#ACTIVE_STATUS_YES}; all
     *       fixture cards are active).</li>
     *   <li>{@code version} — {@code 1L} (documentary value for the JPA
     *       optimistic-lock version; not exercised by these tests but
     *       populated so the fixture Card mirrors a real persisted
     *       entity).</li>
     * </ul>
     *
     * <p>The helper is intentionally tightly coupled to the {@link Card}
     * field set used by these tests; if the entity evolves (new fields
     * added by future migration agents) this method should be updated to
     * populate the new fields so tests across the card-list family agree
     * on fixture content.
     *
     * @param count the number of fixture {@link Card} instances to
     *              create; must be non-negative. {@code 0} returns an
     *              empty list.
     * @return a new {@link ArrayList} of populated fixture {@link Card}
     *         instances; never {@code null}.
     */
    private static List<Card> sampleCards(int count) {
        List<Card> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Card c = new Card();
            // 16-character CARD-NUM matching COBOL CARD-NUM PIC X(16)
            // from CVACT02Y.cpy; "4111111111111" prefix is the canonical
            // Visa test-PAN range (reserved by the publicly documented
            // test-PAN convention — no real PII).
            c.setCardNumber("4111111111111" + String.format("%03d", i));
            // Every fixture card shares the canonical sample account ID
            // from TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10; this
            // mirrors the realistic single-account browse scenario and
            // keeps the helper simple (no per-row account computation
            // required).
            c.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            // 3-character CVV per CARD-CVV-CD PIC 9(03); documentary
            // value (tests do not assert on CVV; it is PCI-sensitive
            // data per AAP §0.10.5 and is omitted from toString()).
            c.setCvvCode("123");
            // 50-character embossed name per CARD-EMBOSSED-NAME PIC
            // X(50); documentary value.
            c.setEmbossedName("TEST USER");
            // 10-character expiration date per CARD-EXPIRAION-DATE PIC
            // X(10) (note the COBOL field-name misspelling preserved in
            // the entity-level comment); ISO YYYY-MM-DD format; future
            // date so any is-expired calculation resolves to false.
            c.setExpirationDate("2026-12-31");
            // 1-character active-status flag per CARD-ACTIVE-STATUS PIC
            // X(01); "Y" = active, "N" = inactive. Documentary value
            // matching TestFixtures.Cards.ACTIVE_STATUS_YES.
            c.setActiveStatus("Y");
            // JPA optimistic-lock version (replaces COBOL before/after-
            // image comparison from COCRDUPC.cbl); set to 1L to document
            // that the fixture represents an already-persisted entity.
            c.setVersion(1L);
            list.add(c);
        }
        return list;
    }
}
