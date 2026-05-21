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

import com.aws.carddemo.entity.Transaction;
import com.aws.carddemo.repository.TransactionRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Transaction-list service — the Java migration of the CICS transaction-list
 * dispatcher {@code app/cbl/COTRN00C.cbl} (TRANID {@code CT00}, 699 lines).
 * Returns a paged list of {@link Transaction} entities for the operator's
 * transaction-list screen, with optional account-ID and card-number filters.
 *
 * <h2>COBOL Provenance — COTRN00C.cbl (699 lines)</h2>
 *
 * <p>The COBOL workflow walks the {@code TRANSACT} VSAM KSDS via
 * {@code STARTBR} / {@code READNEXT} / {@code READPREV} loops, populating a
 * fixed-width 10-row {@code TRAN-REC OCCURS 10 TIMES} table on the
 * {@code COTRN0AO} BMS output map:
 *
 * <ol>
 *   <li>{@code PROCESS-PAGE-FORWARD} (lines 279–328) — the canonical page
 *       advance: {@code STARTBR-TRANSACT-FILE} (line 281), then
 *       {@code PERFORM UNTIL WS-IDX >= 11 OR TRANSACT-EOF OR ERR-FLG-ON}
 *       (line 297) reading up to 10 records into the table via
 *       {@code POPULATE-TRAN-DATA} (lines 379–390).</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} (lines 333–376) — the PF7 / previous
 *       page workflow using {@code READPREV} instead of {@code READNEXT}.</li>
 *   <li>{@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10}
 *       (lines 290, 344) — the implicit page size of 10 rows, materialised
 *       in this Java migration as {@link #PAGE_SIZE}.</li>
 *   <li>{@code CDEMO-CT00-PAGE-NUM PIC 9(08)} (in
 *       {@code app/cpy/COCOM01Y.cpy}) — the current page counter, replaced
 *       in the Java migration by the
 *       {@link TransactionListRequest#getPage()} field on the request DTO
 *       and the {@link TransactionListResponse#getCurrentPage()} field on
 *       the response DTO.</li>
 *   <li>{@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch (lines
 *       67–68) — the COBOL workflow's "has next page" boolean, materialised
 *       in this Java migration as
 *       {@link TransactionListResponse#isHasNext()}.</li>
 * </ol>
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>STARTBR / READNEXT replaced by Spring Data Pageable.</b> The
 *       COBOL workflow opens a VSAM browse position, reads up to 10
 *       records, and closes the position. The Java migration delegates the
 *       equivalent semantic to Spring Data's
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)},
 *       which performs an offset-based query against the underlying
 *       PostgreSQL table. The page size is fixed at {@link #PAGE_SIZE}
 *       (matching the {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
 *       WS-IDX > 10} loop bound in {@code PROCESS-PAGE-FORWARD} /
 *       {@code PROCESS-PAGE-BACKWARD}).</li>
 *   <li><b>Account and card filters added (documented Java-migration
 *       additions).</b> The COBOL workflow has no explicit account-key or
 *       card-key filter — the operator could only navigate by
 *       {@code TRAN-ID} via the {@code TRNIDINI} starting-browse key. The
 *       Java migration adds two declarative filters ({@code accountIdFilter}
 *       and {@code cardNumberFilter} on
 *       {@link TransactionListRequest}) that route the query through
 *       {@link TransactionRepository#findByAccountId(String, Pageable)} or
 *       {@link TransactionRepository#findByCardNumber(String, Pageable)}
 *       instead of the unfiltered {@code findAll(Pageable)}. Documented
 *       divergence per AAP §0.10.2 ("All deviations from literal COBOL
 *       logic must be documented with the original COBOL paragraph name
 *       and reason for divergence"). Reason: the REST controller layer
 *       exposes these as URL query parameters, which is the natural REST
 *       equivalent of the 3270 workflow's manual {@code CARDDAT → TRANSACT}
 *       alternate-index walk.</li>
 *   <li><b>Page-navigation flags promoted to the response DTO.</b> The
 *       COBOL workflow maintained a {@code NEXT-PAGE-YES} /
 *       {@code NEXT-PAGE-NO} 88-level switch (lines 67–68) and derived
 *       "has previous page" from the page-number counter
 *       ({@code CDEMO-CT00-PAGE-NUM > 1} at line 245). The Java migration
 *       surfaces both as explicit boolean fields on
 *       {@link TransactionListResponse}
 *       ({@link TransactionListResponse#isHasNext()} and
 *       {@link TransactionListResponse#isHasPrevious()}), populated from
 *       {@link Page#hasNext()} and {@link Page#hasPrevious()} respectively.
 *       This lets the REST controller layer render PF7/PF8-equivalent
 *       navigation affordances without re-querying the repository.</li>
 *   <li><b>No CICS commarea state.</b> The COBOL workflow stitches
 *       multiple calls together via {@code CARDDAT → TRANSACT-COMMAREA};
 *       the Java migration reduces the dispatcher to a stateless function
 *       {@code listTransactions(req)} on a per-request basis. The page-
 *       navigation state lives in the {@link TransactionListRequest} on the
 *       way in and on the {@link TransactionListResponse} on the way out.</li>
 *   <li><b>No selection / XCTL behaviour.</b> The COBOL
 *       {@code PROCESS-ENTER-KEY} paragraph (lines 146–229) inspects the
 *       {@code SEL0001I} – {@code SEL0010I} 1-character selection fields
 *       and, on an {@code 'S'} keystroke, performs an
 *       {@code EXEC CICS XCTL PROGRAM('COTRN01C')} to navigate to the
 *       transaction-detail screen (lines 186–195). The Java migration
 *       defers selection-then-navigate to the REST controller layer: the
 *       client renders the list, the operator picks a row, and the client
 *       issues a separate GET request to
 *       {@link TransactionDetailService#getTransaction(String)}. This
 *       service therefore does not concern itself with selection at all —
 *       it returns the page and exits.</li>
 *   <li><b>No empty-list reject path.</b> The COBOL workflow renders
 *       {@code "List is empty..."} on the BMS map when the
 *       {@code STARTBR-TRANSACT-FILE} immediately encounters EOF. The Java
 *       migration treats an empty list as a <em>successful</em> response
 *       with an empty {@link TransactionListResponse#getTransactions()}
 *       list and both navigation flags {@code false} — the REST controller
 *       layer is responsible for rendering the equivalent empty-state UX.</li>
 *   <li><b>No {@code @Service} stereotype yet.</b> This class deliberately
 *       omits the {@code @Service} annotation; subsequent migration agents
 *       will add it when the full Spring application context is wired up.
 *       For now the constructor accepts collaborators directly so unit
 *       tests can wire mocks without a Spring context, matching the
 *       convention established by {@link AuthenticationService},
 *       {@link MainMenuService}, {@link AdminMenuService},
 *       {@link AccountViewService}, {@link CardDetailService},
 *       {@link TransactionDetailService}, and {@link UserListService}.</li>
 * </ul>
 *
 * <h2>Filter Dispatch</h2>
 *
 * <p>The {@link #listTransactions(TransactionListRequest)} method evaluates
 * the request's filter fields in a fixed precedence order:
 *
 * <ol>
 *   <li>If {@link TransactionListRequest#getAccountIdFilter()} is non-
 *       {@code null} and non-empty, route through
 *       {@link TransactionRepository#findByAccountId(String, Pageable)}.</li>
 *   <li>Else if {@link TransactionListRequest#getCardNumberFilter()} is
 *       non-{@code null} and non-empty, route through
 *       {@link TransactionRepository#findByCardNumber(String, Pageable)}.</li>
 *   <li>Else route through
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)}
 *       (the unfiltered browse — Java equivalent of the COBOL
 *       {@code STARTBR} from {@code LOW-VALUES}).</li>
 * </ol>
 *
 * <p>The account filter takes precedence over the card filter when both
 * are populated because the account filter is the broader filter (one
 * account → many cards → many transactions); choosing card filter would
 * silently narrow the result set in a way the operator may not expect. The
 * controller layer is expected to either reject requests with both filters
 * set, or to forward only one filter; the service does not complain about
 * ambiguous filters.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE transaction-list dispatcher logic (no
 * helpers extracted to other classes; all branches are visible in the
 * single {@link #listTransactions(TransactionListRequest)} entry point).
 * The corresponding {@code TransactionListServiceTest} exercises every
 * branch via real method calls with a mocked {@link TransactionRepository}
 * at the database boundary only — no business logic (page-size selection,
 * filter dispatch, response construction) is duplicated inside the test.
 *
 * @see TransactionListRequest
 * @see TransactionListResponse
 * @see TransactionRepository
 * @see Transaction
 */
public class TransactionListService {

    // ---------------------------------------------------------------------
    // Page-size constant — direct port of the COBOL PERFORM bound
    // ---------------------------------------------------------------------

    /**
     * Page size — the number of {@link Transaction} entries returned per
     * call. Direct port of the COBOL {@code PERFORM VARYING WS-IDX FROM 1
     * BY 1 UNTIL WS-IDX > 10} loop bound from {@code app/cbl/COTRN00C.cbl}
     * lines 290 ({@code PROCESS-PAGE-FORWARD} reset loop) and 297
     * ({@code PROCESS-PAGE-FORWARD} read loop). The COBOL workflow uses
     * {@code 10} as the index ceiling for the {@code TRAN-REC OCCURS 10
     * TIMES} table on the {@code COTRN0AO} BMS output map; the Java
     * migration forwards the constant to {@link PageRequest#of(int, int)}
     * so Spring Data applies the same row limit at the database layer.
     *
     * <p>Tests verify this value end-to-end via
     * {@code ArgumentCaptor<Pageable>} on the
     * {@link TransactionRepository#findAll(Pageable)} call (see
     * {@code TransactionListServiceTest.Pagination#listTransactions_pageZero_returns10Transactions}).
     */
    static final int PAGE_SIZE = 10;

    // ---------------------------------------------------------------------
    // Collaborator boundary — single JPA repository
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link Transaction} entities — the Java replacement
     * for COBOL {@code STARTBR / READNEXT / READPREV} loops against the
     * {@code TRANSACT} VSAM KSDS in {@code app/cbl/COTRN00C.cbl}.
     * Constructor-injected so tests can wire a Mockito mock without a
     * Spring context.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Constructs a new {@code TransactionListService}.
     *
     * @param transactionRepository JPA repository for {@link Transaction}
     *                              lookups (Java replacement for COBOL
     *                              {@code EXEC CICS STARTBR / READNEXT /
     *                              READPREV} on {@code TRANSACT}). Must not
     *                              be {@code null} — the service does not
     *                              guard against {@code null} collaborators
     *                              because Spring DI would surface the
     *                              misconfiguration at startup; unit tests
     *                              wire a Mockito mock.
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * List a single page of {@link Transaction} entities for the
     * transaction-list screen. Implements the Java equivalent of the COBOL
     * {@code PROCESS-PAGE-FORWARD} paragraph in
     * {@code app/cbl/COTRN00C.cbl}.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Build the {@link PageRequest} from the request's zero-based
     *       page index and the {@link #PAGE_SIZE} constant (matching the
     *       COBOL {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
     *       WS-IDX > 10}).</li>
     *   <li>Dispatch the query in fixed precedence order:
     *       <ul>
     *         <li>When the request carries a non-{@code null} /
     *             non-empty {@link TransactionListRequest#getAccountIdFilter()},
     *             route through
     *             {@link TransactionRepository#findByAccountId(String, Pageable)}.</li>
     *         <li>Else when the request carries a non-{@code null} /
     *             non-empty {@link TransactionListRequest#getCardNumberFilter()},
     *             route through
     *             {@link TransactionRepository#findByCardNumber(String, Pageable)}.</li>
     *         <li>Otherwise, route through
     *             {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)}.</li>
     *       </ul>
     *   </li>
     *   <li>Build and return a {@link TransactionListResponse} via the
     *       {@link TransactionListResponse#of(java.util.List, int, boolean, boolean)}
     *       factory, carrying the page's {@link Page#getContent()}, the
     *       request's page index (as {@code currentPage}),
     *       {@link Page#hasNext()}, and {@link Page#hasPrevious()}.</li>
     * </ol>
     *
     * <p>Empty result sets (no transactions matching the query, or queries
     * beyond the last page) are surfaced as a populated response with an
     * empty transactions list and both navigation flags {@code false} —
     * distinct from any other rejection (this service has no rejection
     * paths). Infrastructure errors (database unreachable, network failure,
     * etc.) surface as {@link org.springframework.dao.DataAccessException}
     * subclasses thrown by the repository; the service does not catch them,
     * letting the controller layer's exception-handler chain produce the
     * Java equivalent of the COBOL {@code 'File Error: ...'} response.
     *
     * @param request the transaction-list request carrying the target page
     *                index and the optional account/card filter fields;
     *                must not be {@code null} (the service does not guard
     *                against {@code null} — the controller layer is
     *                responsible for producing a populated request, and a
     *                {@code null} request would raise an immediate
     *                {@link NullPointerException} at the first field access)
     * @return a populated {@link TransactionListResponse} carrying the page
     *         of transactions and the navigation flags
     */
    public TransactionListResponse listTransactions(TransactionListRequest request) {
        // Step 1 — build the PageRequest from the request's zero-based page
        // index and the fixed PAGE_SIZE (COBOL PERFORM VARYING WS-IDX FROM
        // 1 BY 1 UNTIL WS-IDX > 10 loop bound).
        Pageable pageable = PageRequest.of(request.getPage(), PAGE_SIZE);

        // Step 2 — dispatch the query in fixed precedence order.
        //
        // Account-ID filter wins over card-number filter when both are
        // populated; see class-level "Filter Dispatch" documentation for
        // the rationale. Empty strings are treated identically to null
        // (the operator's BMS form would submit either; the REST
        // controller layer normalises empty to null in production but the
        // service is defensively tolerant).
        Page<Transaction> page;
        String accountFilter = request.getAccountIdFilter();
        String cardFilter = request.getCardNumberFilter();
        if (accountFilter != null && !accountFilter.isEmpty()) {
            page = transactionRepository.findByAccountId(accountFilter, pageable);
        } else if (cardFilter != null && !cardFilter.isEmpty()) {
            page = transactionRepository.findByCardNumber(cardFilter, pageable);
        } else {
            page = transactionRepository.findAll(pageable);
        }

        // Step 3 — build the response carrying the page content, the
        // requested page index (as currentPage — direct materialisation of
        // COBOL CDEMO-CT00-PAGE-NUM), and the two navigation flags.
        // TransactionListResponse.of takes a defensive immutable copy of
        // the page content so subsequent mutation of the underlying List
        // (rare, but possible if the page implementation reuses the list)
        // does not leak into the response.
        return TransactionListResponse.of(
                page.getContent(),
                request.getPage(),
                page.hasNext(),
                page.hasPrevious());
    }
}
