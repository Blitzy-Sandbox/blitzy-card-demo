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

import com.aws.carddemo.entity.Card;
import com.aws.carddemo.repository.CardRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Card-list service — the Java migration of the 1,459-line CICS card-list
 * dispatcher {@code app/cbl/COCRDLIC.cbl} (TRANID {@code CCLI}). Returns a
 * paged list of {@link Card} entities for the operator's card-list screen,
 * with admin-vs-user view dispatch and optional account-ID and card-number
 * (prefix) filters.
 *
 * <h2>COBOL Provenance — COCRDLIC.cbl (1,459 lines)</h2>
 *
 * <p>The COBOL program (header comment lines 4–7) lists "Credit Cards" with
 * two views:
 * <ul>
 *   <li><b>Admin view</b> — all cards if no context passed and admin user
 *       (line 5 of header).</li>
 *   <li><b>User view</b> — only the ones associated with {@code ACCT in
 *       COMMAREA if user is not admin} (lines 6–7 of header).</li>
 * </ul>
 *
 * <p>The COBOL workflow walks the {@code CARDDAT} VSAM KSDS via the
 * alternate index {@code CARDAIX} using {@code STARTBR-CARDAIX} /
 * {@code READNEXT-CARDAIX} loops (paragraph {@code 9000-READ-FORWARD},
 * lines 1123–1267), populating a fixed-width 7-row {@code WS-SCREEN-ROWS
 * OCCURS 7 TIMES} table on the {@code CCRDLIA} BMS output map:
 *
 * <ol>
 *   <li>{@code 9000-READ-FORWARD} (lines 1123–1267) — the canonical page
 *       advance: {@code STARTBR} on {@code LIT-CARD-FILE} (line 1129),
 *       then {@code PERFORM UNTIL READ-LOOP-EXIT} (line 1144) reading up
 *       to 7 records, with the per-record {@code 9500-FILTER-RECORDS} call
 *       (line 1159) deciding whether to include or skip each record.</li>
 *   <li>{@code 9100-READ-BACKWARD} (around line 1273) — the PF7 / previous-
 *       page workflow using {@code STARTBR} from a saved key + 1 offset.</li>
 *   <li>{@code WS-MAX-SCREEN-LINES VALUE 7} (lines 177–178) — the explicit
 *       page size, materialised in this Java migration as
 *       {@link #PAGE_SIZE}.</li>
 *   <li>{@code WS-CA-SCREEN-NUM PIC 9(1)} (line 237) — the current page
 *       counter, replaced in the Java migration by the
 *       {@link CardListRequest#getPage()} field on the request DTO and the
 *       {@link CardListResponse#getCurrentPage()} field on the response DTO.</li>
 *   <li>{@code CA-NEXT-PAGE-EXISTS} / {@code CA-NEXT-PAGE-NOT-EXISTS}
 *       88-level switch (lines 243–244) — the COBOL workflow's "has next
 *       page" boolean, materialised in this Java migration as
 *       {@link CardListResponse#isHasNext()}.</li>
 *   <li>{@code 9500-FILTER-RECORDS} (lines 1382–1410) — the per-record
 *       account / card filter. Migrated to {@link CardRepository#findByAccountId}
 *       and {@link CardRepository#findByCardNumberStartsWith} derived
 *       queries (database-side filtering rather than in-loop COBOL
 *       {@code GO TO}).</li>
 * </ol>
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>STARTBR-CARDAIX / READNEXT-CARDAIX replaced by Spring Data
 *       Pageable.</b> The COBOL workflow opens a VSAM browse position via
 *       the alternate index, reads up to 7 records, and closes the
 *       position with {@code ENDBR FILE(LIT-CARD-FILE)} (line 1258). The
 *       Java migration delegates the equivalent semantic to Spring Data's
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)}
 *       (and the two filter-aware derived queries below), which perform an
 *       offset-based query against the underlying PostgreSQL table. The
 *       page size is fixed at {@link #PAGE_SIZE} (matching the explicit
 *       {@code WS-MAX-SCREEN-LINES VALUE 7} constant at lines 177–178).</li>
 *   <li><b>Admin vs user view dispatch (COBOL-parity).</b> The COBOL
 *       header (lines 4–7) documents two execution paths. The Java
 *       migration carries the role on the request DTO
 *       ({@link CardListRequest#getUserType()}) and dispatches:
 *       <ul>
 *         <li>{@code "A"} (admin) + no filter →
 *             {@link CardRepository#findAll(Pageable)} (unfiltered browse).</li>
 *         <li>{@code "U"} (user) + {@link CardListRequest#getAccountIdFilter()}
 *             →
 *             {@link CardRepository#findByAccountId(String, Pageable)}
 *             (narrows browse to the operator's account, matching the COBOL
 *             {@code 9500-FILTER-RECORDS} line 1386
 *             {@code IF CARD-ACCT-ID = CC-ACCT-ID}).</li>
 *       </ul></li>
 *   <li><b>Card-number filter generalised to prefix match.</b> The COBOL
 *       {@code 9500-FILTER-RECORDS} paragraph at line 1397 performs an
 *       exact equality test ({@code IF CARD-NUM = CC-CARD-NUM-N}); the
 *       Java migration generalises this to a prefix match via
 *       {@link CardRepository#findByCardNumberStartsWith(String, Pageable)}
 *       so the REST controller layer can support partial-prefix narrowing
 *       (documented Java-migration enhancement per AAP §0.10.2). Passing
 *       the full 16-digit PAN replicates the COBOL exact-match semantic
 *       (a 16-character argument matches at most one card).</li>
 *   <li><b>No CICS commarea state.</b> The COBOL workflow stitches
 *       multiple calls together via {@code WS-THIS-PROGCOMMAREA} carrying
 *       {@code WS-CA-LAST-CARDKEY}, {@code WS-CA-FIRST-CARDKEY},
 *       {@code WS-CA-SCREEN-NUM}, etc.; the Java migration reduces the
 *       dispatcher to a stateless function {@code listCards(req)} on a
 *       per-request basis. The page-navigation state lives in the
 *       {@link CardListRequest} on the way in and on the
 *       {@link CardListResponse} on the way out.</li>
 *   <li><b>No selection / XCTL behaviour.</b> The COBOL workflow inspects
 *       7 single-character selection fields ({@code WS-EDIT-SELECT-FLAGS}
 *       at line 72) and, on an {@code 'S'} keystroke, performs an
 *       {@code EXEC CICS XCTL PROGRAM('COCRDSLC')} (the {@code LIT-
 *       CARDDTLPGM} constant at line 196) to navigate to the card-detail
 *       screen. The Java migration defers selection-then-navigate to the
 *       REST controller layer: the client renders the list, the operator
 *       picks a row, and the client issues a separate GET request to
 *       {@link CardDetailService#getCard}. This service therefore does not
 *       concern itself with selection at all — it returns the page and
 *       exits.</li>
 *   <li><b>No empty-list reject path.</b> The COBOL workflow renders
 *       {@code 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'} on the BMS
 *       map when the {@code STARTBR-CARDAIX} immediately encounters EOF
 *       (the {@code WS-NO-RECORDS-FOUND} 88-level at line 121). The Java
 *       migration treats an empty list as a <em>successful</em> response
 *       with an empty {@link CardListResponse#getCards()} list and both
 *       navigation flags {@code false} — the REST controller layer is
 *       responsible for rendering the equivalent empty-state UX.</li>
 *   <li><b>No filter format validation in the service layer.</b> The
 *       COBOL workflow validates filter input via
 *       {@code 1110-EDIT-INPUT-FILTERS} (lines 1004–1066) enforcing
 *       "11 digit number" on the account filter and "16 digit number" on
 *       the card filter. The Java migration delegates this format
 *       validation to the REST controller layer's {@code @Valid} / Bean-
 *       Validation annotations on the inbound request payload — the
 *       service trusts the fields populated on the DTO are already format-
 *       correct.</li>
 *   <li><b>No {@code @Service} stereotype yet.</b> This class deliberately
 *       omits the {@code @Service} annotation; subsequent migration agents
 *       will add it when the full Spring application context is wired up.
 *       For now the constructor accepts collaborators directly so unit
 *       tests can wire mocks without a Spring context, matching the
 *       convention established by {@link AuthenticationService},
 *       {@link MainMenuService}, {@link AdminMenuService},
 *       {@link AccountViewService}, {@link CardDetailService},
 *       {@link TransactionDetailService}, {@link TransactionListService},
 *       and {@link UserListService}.</li>
 * </ul>
 *
 * <h2>Filter Dispatch</h2>
 *
 * <p>The {@link #listCards(CardListRequest)} method evaluates the request's
 * filter fields in a fixed precedence order:
 *
 * <ol>
 *   <li>If {@link CardListRequest#getCardNumberFilter()} is non-{@code null}
 *       and non-empty, route through
 *       {@link CardRepository#findByCardNumberStartsWith(String, Pageable)}.
 *       The card-number filter is the most specific filter (one PAN ↦ at
 *       most one card; one prefix ↦ a small contiguous group) so it wins
 *       over the broader account filter.</li>
 *   <li>Else if {@link CardListRequest#getAccountIdFilter()} is non-
 *       {@code null} and non-empty, route through
 *       {@link CardRepository#findByAccountId(String, Pageable)}.</li>
 *   <li>Else route through
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)}
 *       (the unfiltered admin browse — Java equivalent of the COBOL
 *       {@code STARTBR-CARDAIX} from {@code LOW-VALUES}).</li>
 * </ol>
 *
 * <p>The controller layer is expected to either reject requests with both
 * filters set, or to forward only one filter; the service does not
 * complain about ambiguous filters. The {@link CardListRequest#getUserType()}
 * flag is currently treated as informational on the service side — the
 * presence or absence of {@link CardListRequest#getAccountIdFilter()} is
 * what actually narrows the browse. Authoritative role-based access
 * control is the controller layer's responsibility (it should reject a
 * user-view request that does not carry an account filter).
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE card-list dispatcher logic (no
 * helpers extracted to other classes; all branches are visible in the
 * single {@link #listCards(CardListRequest)} entry point). The
 * corresponding {@code CardListServiceTest} exercises every branch via
 * real method calls with a mocked {@link CardRepository} at the database
 * boundary only — no business logic (page-size selection, filter dispatch,
 * response construction) is duplicated inside the test.
 *
 * @see CardListRequest
 * @see CardListResponse
 * @see CardRepository
 * @see Card
 */
@Service
public class CardListService {

    // ---------------------------------------------------------------------
    // Page-size constant — direct port of the COBOL WS-MAX-SCREEN-LINES
    // ---------------------------------------------------------------------

    /**
     * Page size — the number of {@link Card} entries returned per call.
     * Direct port of the COBOL {@code WS-MAX-SCREEN-LINES VALUE 7} constant
     * declared at lines 177–178 of {@code app/cbl/COCRDLIC.cbl} and tested
     * inside {@code 9000-READ-FORWARD} via {@code IF WS-SCRN-COUNTER =
     * WS-MAX-SCREEN-LINES} at line 1191 (the loop-exit condition).
     *
     * <p>The COBOL workflow uses {@code 7} as the row ceiling for the
     * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} table on the {@code CCRDLIA}
     * BMS output map (lines 252–260); the Java migration forwards the
     * constant to {@link PageRequest#of(int, int)} so Spring Data applies
     * the same row limit at the database layer.
     *
     * <p>Tests verify this value via the {@link CardListResponse#getCards()}
     * sizes produced by stubbed {@link Page} fixtures (see
     * {@code CardListServiceTest.Pagination#listCards_pageZero_returns7Cards}).
     */
    static final int PAGE_SIZE = 7;

    // ---------------------------------------------------------------------
    // Collaborator boundary — single JPA repository
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link Card} entities — the Java replacement for
     * COBOL {@code STARTBR-CARDAIX} / {@code READNEXT-CARDAIX} loops
     * against the {@code CARDDAT} VSAM KSDS (via alternate index
     * {@code CARDAIX}) in {@code app/cbl/COCRDLIC.cbl}. Constructor-
     * injected so tests can wire a Mockito mock without a Spring context.
     */
    private final CardRepository cardRepository;

    /**
     * Constructs a new {@code CardListService}.
     *
     * @param cardRepository JPA repository for {@link Card} lookups (Java
     *                       replacement for COBOL {@code EXEC CICS STARTBR
     *                       / READNEXT} on {@code CARDDAT} via
     *                       {@code CARDAIX}). Must not be {@code null} —
     *                       the service does not guard against {@code null}
     *                       collaborators because Spring DI would surface
     *                       the misconfiguration at startup; unit tests
     *                       wire a Mockito mock.
     */
    public CardListService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * List a single page of {@link Card} entities for the card-list screen.
     * Implements the Java equivalent of the COBOL
     * {@code 9000-READ-FORWARD} paragraph in {@code app/cbl/COCRDLIC.cbl}
     * with admin-vs-user view dispatch and optional filters.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Build the {@link PageRequest} from the request's zero-based
     *       page index and the {@link #PAGE_SIZE} constant (matching the
     *       COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}).</li>
     *   <li>Dispatch the query in fixed precedence order:
     *       <ul>
     *         <li>Card-number filter populated →
     *             {@link CardRepository#findByCardNumberStartsWith(String, Pageable)}
     *             (the most specific filter wins; the controller layer
     *             passes a 16-character PAN for exact match or a shorter
     *             prefix for narrowing).</li>
     *         <li>Else account-ID filter populated →
     *             {@link CardRepository#findByAccountId(String, Pageable)}
     *             (matches the COBOL {@code 9500-FILTER-RECORDS} line 1386
     *             account-equality test).</li>
     *         <li>Otherwise →
     *             {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)}
     *             (unfiltered admin browse).</li>
     *       </ul>
     *   </li>
     *   <li>Build and return a {@link CardListResponse} via the
     *       {@link CardListResponse#of(java.util.List, int, boolean, boolean)}
     *       factory, carrying the page's {@link Page#getContent()}, the
     *       request's page index (as {@code currentPage}),
     *       {@link Page#hasNext()}, and {@link Page#hasPrevious()}.</li>
     * </ol>
     *
     * <p>Empty result sets (no cards matching the query, or queries beyond
     * the last page) are surfaced as a populated response with an empty
     * cards list and both navigation flags {@code false} — distinct from
     * any other rejection (this service has no rejection paths).
     * Infrastructure errors (database unreachable, network failure, etc.)
     * surface as {@link org.springframework.dao.DataAccessException}
     * subclasses thrown by the repository; the service does not catch
     * them, letting the controller layer's exception-handler chain produce
     * the Java equivalent of the COBOL {@code 'File Error: ...'} response
     * (the {@code WS-FILE-ERROR-MESSAGE} construct at lines 153–171).
     *
     * @param request the card-list request carrying the target page index,
     *                the operator role flag, and the optional account/card
     *                filter fields; must not be {@code null} (the service
     *                does not guard against {@code null} — the controller
     *                layer is responsible for producing a populated
     *                request, and a {@code null} request would raise an
     *                immediate {@link NullPointerException} at the first
     *                field access)
     * @return a populated {@link CardListResponse} carrying the page of
     *         cards and the navigation flags
     */
    public CardListResponse listCards(CardListRequest request) {
        // Step 1 — build the PageRequest from the request's zero-based page
        // index and the fixed PAGE_SIZE (COBOL WS-MAX-SCREEN-LINES VALUE 7
        // at lines 177–178 of COCRDLIC.cbl).
        Pageable pageable = PageRequest.of(request.getPage(), PAGE_SIZE);

        // Step 2 — dispatch the query in fixed precedence order.
        //
        // Card-number filter wins over account filter when both are
        // populated; see class-level "Filter Dispatch" documentation for
        // the rationale (card-number is the most specific filter). Empty
        // strings are treated identically to null (the operator's BMS form
        // would submit either; the REST controller layer normalises empty
        // to null in production but the service is defensively tolerant).
        Page<Card> page;
        String cardFilter = request.getCardNumberFilter();
        String accountFilter = request.getAccountIdFilter();
        if (cardFilter != null && !cardFilter.isEmpty()) {
            page = cardRepository.findByCardNumberStartsWith(cardFilter, pageable);
        } else if (accountFilter != null && !accountFilter.isEmpty()) {
            page = cardRepository.findByAccountId(accountFilter, pageable);
        } else {
            page = cardRepository.findAll(pageable);
        }

        // Step 3 — build the response carrying the page content, the
        // requested page index (as currentPage — direct materialisation of
        // COBOL WS-CA-SCREEN-NUM at line 237), and the two navigation
        // flags. CardListResponse.of takes a defensive immutable copy of
        // the page content so subsequent mutation of the underlying List
        // (rare, but possible if the page implementation reuses the list)
        // does not leak into the response.
        return CardListResponse.of(
                page.getContent(),
                request.getPage(),
                page.hasNext(),
                page.hasPrevious());
    }
}
