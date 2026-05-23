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

import java.util.Collections;
import java.util.List;

/**
 * Result DTO for {@link TransactionListService#listTransactions(TransactionListRequest)}
 * — the Java replacement for the {@code COTRN0AO} BMS-mapped output record
 * emitted by {@code app/cbl/COTRN00C.cbl} (TRANID {@code CT00}, the
 * transaction-list dispatcher). Encodes a paged result carrying:
 * <ul>
 *   <li>The current page of {@link Transaction} entities (up to
 *       {@link TransactionListService#PAGE_SIZE} = 10 rows, mirroring the
 *       fixed-width 10-row {@code TRAN-REC OCCURS 10 TIMES} table on the
 *       {@code COTRN0AO} BMS output map).</li>
 *   <li>The zero-based current page index (replaces COBOL
 *       {@code CDEMO-CT00-PAGE-NUM}).</li>
 *   <li>Two boolean page-navigation flags ({@link #hasNext},
 *       {@link #hasPrevious}) that replace the COBOL
 *       {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch plus
 *       the implicit "{@code CDEMO-CT00-PAGE-NUM > 1}" comparison.</li>
 * </ul>
 *
 * <h2>COBOL Provenance — COTRN00C.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL
 * {@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD} paragraphs
 * (lines 279–376) that populate the 10-occurrence {@code TRAN-REC OCCURS 10
 * TIMES} table on the {@code COTRN0AO} BMS output map after a STARTBR /
 * READNEXT / READPREV loop against the {@code TRANSACT} VSAM KSDS:
 *
 * <ul>
 *   <li>The {@code TRAN-ID}, {@code TRAN-AMT}, {@code TRAN-CARD-NUM},
 *       {@code TRAN-TYPE-CD}, {@code TRAN-CAT-CD}, etc. fields from each
 *       {@code TRANSACT} record are mapped into the 10 fields of the
 *       {@code COTRN0AO} map via the {@code POPULATE-TRAN-DATA} paragraph
 *       (lines 379–390). The Java migration replaces this fixed-width
 *       10-row table with a {@link List}&lt;{@link Transaction}&gt; carrying
 *       up to 10 entities.</li>
 *   <li>The {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch
 *       (line 67–68) is set after the READNEXT loop completes — {@code YES}
 *       if there is a non-EOF record beyond the current page, {@code NO}
 *       otherwise (lines 309–319). The Java migration surfaces this as
 *       {@link #hasNext}, derived from
 *       {@link org.springframework.data.domain.Page#hasNext()}.</li>
 *   <li>The Java migration adds an equivalent {@link #hasPrevious} flag
 *       (derived from
 *       {@link org.springframework.data.domain.Page#hasPrevious()}) so the
 *       REST controller layer can render PF7-equivalent navigation
 *       affordances without re-querying the repository. The COBOL workflow
 *       inferred "has previous page" from the page-number counter
 *       ({@code CDEMO-CT00-PAGE-NUM > 1} at line 245).</li>
 *   <li>The {@link #currentPage} field is the direct Java materialisation
 *       of the COBOL {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} counter. The
 *       Java migration exposes it as part of the response so the REST
 *       controller layer can render the current-page-number affordance
 *       without re-deriving it from the request.</li>
 * </ul>
 *
 * <h2>No reject path</h2>
 *
 * <p>Unlike {@link UserListResponse} (which carries a {@code success}
 * boolean and a reject {@code message} for the admin-only authorisation
 * branch), this response has no failure outcome. The transaction-list
 * dispatcher is reachable by any authenticated user (no admin-only gate),
 * and an empty result set is a <em>successful</em> response with an empty
 * {@link #transactions} list and both navigation flags {@code false}
 * (matching the COBOL convention where an empty list returns the
 * {@code "List is empty..."} text on the BMS map but the program still
 * exits via the normal {@code RETURN} path, not via an error branch).
 *
 * <h2>Construction Contract — Factory Method Only</h2>
 *
 * <p>Construction goes exclusively through the
 * {@link #of(List, int, boolean, boolean)} static factory method so that
 * the invariant between {@link #transactions}, {@link #currentPage},
 * {@link #hasNext}, and {@link #hasPrevious} is preserved: the
 * {@code transactions} list is defensively copied (immutable view) so the
 * caller cannot mutate the response state after construction.
 *
 * <p>The no-args constructor is preserved for Spring MVC view-model binding
 * (if the REST controller layer ever needs to serialise this object directly
 * via Jackson), but production code paths inside {@link TransactionListService}
 * always invoke the factory method.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link TransactionListService} and
 * {@link TransactionListRequest} in {@code com.aws.carddemo.service} (rather
 * than under a dedicated {@code dto/transaction} subpackage). The dispatch
 * pair is tightly coupled to the service contract and there is no
 * controller-layer adapter yet that would justify a separate package
 * boundary. This matches the convention established by
 * {@link MainMenuResponse} / {@link AdminMenuResponse} /
 * {@link AccountViewResponse} / {@link CardDetailResponse} /
 * {@link TransactionDetailResponse} / {@link UserListResponse}.
 *
 * @see TransactionListService
 * @see TransactionListRequest
 * @see Transaction
 */
public class TransactionListResponse {

    /**
     * The single page of {@link Transaction} entities produced by the
     * repository query. The list always carries the
     * {@link TransactionListService#PAGE_SIZE} count or fewer (the final
     * page is partial; an empty repository or a query beyond the last page
     * returns zero rows). The Java migration replaces the fixed-width
     * 10-row {@code TRAN-REC OCCURS 10 TIMES} table from the COBOL
     * {@code COTRN0AO} output map with this variable-length list.
     *
     * <p>Tests assert via {@link #getTransactions()} ({@code .hasSize(n)},
     * {@code .isEmpty()}).
     */
    private List<Transaction> transactions;

    /**
     * Zero-based current page index. Direct Java materialisation of the
     * COBOL {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} counter from
     * {@code app/cpy/COCOM01Y.cpy} as used by {@code COTRN00C.cbl}.
     *
     * <p>Tests assert via {@link #getCurrentPage()}
     * ({@code .isEqualTo(0)} for first page, {@code .isEqualTo(9)} for the
     * last-page boundary case).
     */
    private int currentPage;

    /**
     * {@code true} when there is at least one additional record beyond the
     * end of {@link #transactions} (the operator's PF8 — next-page —
     * affordance should be enabled); {@code false} on the final page.
     *
     * <p>Derived from {@link org.springframework.data.domain.Page#hasNext()}
     * in {@link TransactionListService}. The Java migration replaces the
     * COBOL {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch
     * (lines 67–68, set at lines 313 and 315 of
     * {@code PROCESS-PAGE-FORWARD}).
     */
    private boolean hasNext;

    /**
     * {@code true} when there is at least one record before the start of
     * {@link #transactions} (the operator's PF7 — previous-page —
     * affordance should be enabled); {@code false} on the first page.
     *
     * <p>Derived from
     * {@link org.springframework.data.domain.Page#hasPrevious()} in
     * {@link TransactionListService}. The Java migration adds this flag
     * explicitly; the COBOL workflow inferred the equivalent from
     * {@code CDEMO-CT00-PAGE-NUM > 1} at line 245 of
     * {@code PROCESS-PF7-KEY}.
     */
    private boolean hasPrevious;

    /**
     * No-args constructor — preserved for Spring MVC view-model binding (if
     * the REST controller layer ever needs to serialise this object directly
     * via Jackson) and for any test that needs to construct a custom
     * response shape without the factory method. Production code paths
     * inside {@link TransactionListService} always invoke
     * {@link #of(List, int, boolean, boolean)} so the invariants between
     * {@link #transactions}, {@link #currentPage}, {@link #hasNext}, and
     * {@link #hasPrevious} are preserved.
     */
    public TransactionListResponse() {
        // Intentionally empty — factory method below populates fields.
    }

    /**
     * Builds a populated response carrying the supplied page of transactions,
     * the current page index, and the two navigation flags.
     *
     * @param transactions the list of {@link Transaction} entities from the
     *                     current page; must not be {@code null} (use an
     *                     empty list for a zero-row page). A defensive
     *                     immutable copy is taken to insulate the caller
     *                     from subsequent mutation of the source list
     *                     (a Spring Data {@code Page#getContent()} return
     *                     value, for example, can be mutated by some
     *                     {@link org.springframework.data.domain.Page}
     *                     implementations).
     * @param currentPage  zero-based current page index (the
     *                     {@link TransactionListRequest#getPage()} value)
     * @param hasNext      {@code true} when there is at least one record
     *                     beyond the end of the current page; derived from
     *                     {@link org.springframework.data.domain.Page#hasNext()}
     * @param hasPrevious  {@code true} when there is at least one record
     *                     before the start of the current page; derived
     *                     from
     *                     {@link org.springframework.data.domain.Page#hasPrevious()}
     * @return a fresh {@code TransactionListResponse} with a defensive
     *         immutable copy of the supplied transactions list, the
     *         supplied page index, and the supplied navigation flags
     */
    public static TransactionListResponse of(
            List<Transaction> transactions,
            int currentPage,
            boolean hasNext,
            boolean hasPrevious) {
        TransactionListResponse r = new TransactionListResponse();
        // Defensive immutable copy — the caller may continue to mutate the
        // source list (for example the Spring Data Page#getContent() return
        // value); taking a copy here preserves the response's immutability
        // contract. List.copyOf rejects nulls inside the list (NPE on first
        // null element), so any caller passing a list with embedded nulls
        // gets a fail-fast signal rather than a silent corrupt response.
        r.transactions = (transactions == null)
                ? Collections.emptyList()
                : List.copyOf(transactions);
        r.currentPage = currentPage;
        r.hasNext = hasNext;
        r.hasPrevious = hasPrevious;
        return r;
    }

    /**
     * @return the list of transactions on the current page (never
     *         {@code null} — empty for empty result sets). The returned
     *         list is immutable (constructed via {@link List#copyOf(java.util.Collection)}).
     */
    public List<Transaction> getTransactions() {
        return transactions;
    }

    /**
     * Sets the transactions list — provided for completeness; production
     * code paths use {@link #of(List, int, boolean, boolean)} factory.
     *
     * @param transactions the new transactions list
     */
    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    /**
     * @return the zero-based current page index (the
     *         {@link TransactionListRequest#getPage()} value that produced
     *         this response).
     */
    public int getCurrentPage() {
        return currentPage;
    }

    /**
     * Sets the current page index — provided for completeness; production
     * code paths use {@link #of(List, int, boolean, boolean)} factory.
     *
     * @param currentPage the new current page index
     */
    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    /**
     * @return {@code true} when there is at least one record beyond the end
     *         of the current page; {@code false} on the final page.
     */
    public boolean isHasNext() {
        return hasNext;
    }

    /**
     * Sets the hasNext flag — provided for completeness; production code
     * paths use {@link #of(List, int, boolean, boolean)} factory.
     *
     * @param hasNext the new hasNext flag
     */
    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }

    /**
     * @return {@code true} when there is at least one record before the
     *         start of the current page; {@code false} on the first page.
     */
    public boolean isHasPrevious() {
        return hasPrevious;
    }

    /**
     * Sets the hasPrevious flag — provided for completeness; production
     * code paths use {@link #of(List, int, boolean, boolean)} factory.
     *
     * @param hasPrevious the new hasPrevious flag
     */
    public void setHasPrevious(boolean hasPrevious) {
        this.hasPrevious = hasPrevious;
    }
}
