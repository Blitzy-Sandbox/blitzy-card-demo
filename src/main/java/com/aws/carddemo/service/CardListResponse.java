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

import java.util.Collections;
import java.util.List;

/**
 * Result DTO for {@link CardListService#listCards(CardListRequest)} — the
 * Java replacement for the {@code CCRDLIA} BMS-mapped output record emitted
 * by {@code app/cbl/COCRDLIC.cbl} (TRANID {@code CCLI}, the card-list
 * dispatcher, 1,459 lines). Encodes a paged result carrying:
 * <ul>
 *   <li>The current page of {@link Card} entities (up to
 *       {@link CardListService#PAGE_SIZE} = 7 rows, mirroring the fixed-
 *       width 7-row {@code WS-SCREEN-ROWS OCCURS 7 TIMES} table on the
 *       {@code CCRDLIA} BMS output map at lines 252–260 of
 *       {@code COCRDLIC.cbl}).</li>
 *   <li>The zero-based current page index (replaces COBOL
 *       {@code WS-CA-SCREEN-NUM}).</li>
 *   <li>Two boolean page-navigation flags ({@link #hasNext},
 *       {@link #hasPrevious}) that replace the COBOL
 *       {@code CA-NEXT-PAGE-EXISTS} / {@code CA-NEXT-PAGE-NOT-EXISTS}
 *       88-level switch (lines 243–244) plus the implicit
 *       "{@code CA-FIRST-PAGE}" comparison (line 238).</li>
 * </ul>
 *
 * <h2>COBOL Provenance — COCRDLIC.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL {@code 9000-READ-FORWARD}
 * paragraph (lines 1123–1267) that populates the 7-occurrence
 * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} table after a {@code STARTBR-
 * CARDAIX} / {@code READNEXT-CARDAIX} loop against the {@code CARDDAT}
 * VSAM KSDS:
 *
 * <ul>
 *   <li>The {@code CARD-NUM}, {@code CARD-ACCT-ID}, {@code CARD-ACTIVE-
 *       STATUS} fields from each {@code CARDDAT} record are mapped into
 *       the 7 fields of the {@code CCRDLIA} map at lines 1165–1171
 *       ({@code WS-ROW-CARD-NUM}, {@code WS-ROW-ACCTNO},
 *       {@code WS-ROW-CARD-STATUS}). The Java migration replaces this
 *       fixed-width 7-row table with a {@link List}&lt;{@link Card}&gt;
 *       carrying up to 7 entities.</li>
 *   <li>The {@code CA-NEXT-PAGE-EXISTS} / {@code CA-NEXT-PAGE-NOT-EXISTS}
 *       88-level switch (lines 243–244) is set after the READNEXT loop
 *       completes — {@code EXISTS} (line 1210) if there is a non-EOF
 *       record beyond the current page, {@code NOT-EXISTS} (line 1216)
 *       otherwise. The Java migration surfaces this as {@link #hasNext},
 *       derived from
 *       {@link org.springframework.data.domain.Page#hasNext()}.</li>
 *   <li>The Java migration adds an equivalent {@link #hasPrevious} flag
 *       (derived from
 *       {@link org.springframework.data.domain.Page#hasPrevious()}) so the
 *       REST controller layer can render PF7-equivalent navigation
 *       affordances without re-querying the repository. The COBOL workflow
 *       inferred "has previous page" from {@code NOT CA-FIRST-PAGE}
 *       (line 502).</li>
 *   <li>The {@link #currentPage} field is the direct Java materialisation
 *       of the COBOL {@code WS-CA-SCREEN-NUM PIC 9(1)} counter (line 237).
 *       The Java migration exposes it as part of the response so the REST
 *       controller layer can render the current-page-number affordance
 *       without re-deriving it from the request.</li>
 * </ul>
 *
 * <h2>No reject path</h2>
 *
 * <p>Unlike {@link UserListResponse} (which carries a {@code success}
 * boolean and a reject {@code message} for the admin-only authorisation
 * branch), this response has no failure outcome. The card-list dispatcher
 * is reachable by both admin and user operators (with the admin/user view
 * dispatched on {@link CardListRequest#getUserType()}), and an empty
 * result set is a <em>successful</em> response with an empty
 * {@link #cards} list and both navigation flags {@code false} (matching
 * the COBOL convention where an empty list returns the
 * {@code 'NO RECORDS FOUND FOR THIS SEARCH CONDITION'} text — defined at
 * line 122 — on the BMS map but the program still exits via the normal
 * {@code RETURN} path, not via an error branch).
 *
 * <h2>Construction Contract — Factory Method Only</h2>
 *
 * <p>Construction goes exclusively through the
 * {@link #of(List, int, boolean, boolean)} static factory method so that
 * the invariant between {@link #cards}, {@link #currentPage},
 * {@link #hasNext}, and {@link #hasPrevious} is preserved: the
 * {@code cards} list is defensively copied (immutable view) so the caller
 * cannot mutate the response state after construction.
 *
 * <p>The no-args constructor is preserved for Spring MVC view-model binding
 * (if the REST controller layer ever needs to serialise this object directly
 * via Jackson), but production code paths inside {@link CardListService}
 * always invoke the factory method.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link CardListService} and
 * {@link CardListRequest} in {@code com.aws.carddemo.service} (rather than
 * under a dedicated {@code dto/card} subpackage). The dispatch pair is
 * tightly coupled to the service contract and there is no controller-layer
 * adapter yet that would justify a separate package boundary. This matches
 * the convention established by {@link TransactionListResponse},
 * {@link UserListResponse}, {@link MainMenuResponse},
 * {@link AdminMenuResponse}, {@link AccountViewResponse},
 * {@link CardDetailResponse}, {@link TransactionDetailResponse}.
 *
 * @see CardListService
 * @see CardListRequest
 * @see Card
 */
public class CardListResponse {

    /**
     * The single page of {@link Card} entities produced by the repository
     * query. The list always carries the {@link CardListService#PAGE_SIZE}
     * count or fewer (the final page is partial; an empty repository or a
     * query beyond the last page returns zero rows). The Java migration
     * replaces the fixed-width 7-row {@code WS-SCREEN-ROWS OCCURS 7 TIMES}
     * table from the COBOL {@code CCRDLIA} output map (lines 252–260 of
     * {@code COCRDLIC.cbl}) with this variable-length list.
     *
     * <p>Tests assert via {@link #getCards()} ({@code .hasSize(n)},
     * {@code .isEmpty()}).
     */
    private List<Card> cards;

    /**
     * Zero-based current page index. Direct Java materialisation of the
     * COBOL {@code WS-CA-SCREEN-NUM PIC 9(1)} counter from line 237 of
     * {@code COCRDLIC.cbl}.
     *
     * <p>Tests assert via {@link #getCurrentPage()}
     * ({@code .isEqualTo(0)} for first page, {@code .isEqualTo(7)} for the
     * last-page boundary case on a 50-record fixture set).
     */
    private int currentPage;

    /**
     * {@code true} when there is at least one additional record beyond the
     * end of {@link #cards} (the operator's PF8 — next-page — affordance
     * should be enabled); {@code false} on the final page.
     *
     * <p>Derived from {@link org.springframework.data.domain.Page#hasNext()}
     * in {@link CardListService}. The Java migration replaces the COBOL
     * {@code CA-NEXT-PAGE-EXISTS} / {@code CA-NEXT-PAGE-NOT-EXISTS}
     * 88-level switch (lines 243–244, set at lines 1210 and 1216 of
     * {@code 9000-READ-FORWARD}).
     */
    private boolean hasNext;

    /**
     * {@code true} when there is at least one record before the start of
     * {@link #cards} (the operator's PF7 — previous-page — affordance
     * should be enabled); {@code false} on the first page.
     *
     * <p>Derived from
     * {@link org.springframework.data.domain.Page#hasPrevious()} in
     * {@link CardListService}. The Java migration adds this flag
     * explicitly; the COBOL workflow inferred the equivalent from
     * {@code NOT CA-FIRST-PAGE} at line 502 of {@code PROCESS-PF7-KEY}.
     */
    private boolean hasPrevious;

    /**
     * No-args constructor — preserved for Spring MVC view-model binding (if
     * the REST controller layer ever needs to serialise this object directly
     * via Jackson) and for any test that needs to construct a custom
     * response shape without the factory method. Production code paths
     * inside {@link CardListService} always invoke
     * {@link #of(List, int, boolean, boolean)} so the invariants between
     * {@link #cards}, {@link #currentPage}, {@link #hasNext}, and
     * {@link #hasPrevious} are preserved.
     */
    public CardListResponse() {
        // Intentionally empty — factory method below populates fields.
    }

    /**
     * Builds a populated response carrying the supplied page of cards, the
     * current page index, and the two navigation flags.
     *
     * @param cards        the list of {@link Card} entities from the current
     *                     page; must not be {@code null} (use an empty list
     *                     for a zero-row page). A defensive immutable copy
     *                     is taken to insulate the caller from subsequent
     *                     mutation of the source list (a Spring Data
     *                     {@code Page#getContent()} return value, for
     *                     example, can be mutated by some
     *                     {@link org.springframework.data.domain.Page}
     *                     implementations).
     * @param currentPage  zero-based current page index (the
     *                     {@link CardListRequest#getPage()} value)
     * @param hasNext      {@code true} when there is at least one record
     *                     beyond the end of the current page; derived from
     *                     {@link org.springframework.data.domain.Page#hasNext()}
     * @param hasPrevious  {@code true} when there is at least one record
     *                     before the start of the current page; derived
     *                     from
     *                     {@link org.springframework.data.domain.Page#hasPrevious()}
     * @return a fresh {@code CardListResponse} with a defensive immutable
     *         copy of the supplied cards list, the supplied page index, and
     *         the supplied navigation flags
     */
    public static CardListResponse of(
            List<Card> cards,
            int currentPage,
            boolean hasNext,
            boolean hasPrevious) {
        CardListResponse r = new CardListResponse();
        // Defensive immutable copy — the caller may continue to mutate the
        // source list (for example the Spring Data Page#getContent() return
        // value); taking a copy here preserves the response's immutability
        // contract. List.copyOf rejects nulls inside the list (NPE on first
        // null element), so any caller passing a list with embedded nulls
        // gets a fail-fast signal rather than a silent corrupt response.
        r.cards = (cards == null)
                ? Collections.emptyList()
                : List.copyOf(cards);
        r.currentPage = currentPage;
        r.hasNext = hasNext;
        r.hasPrevious = hasPrevious;
        return r;
    }

    /**
     * @return the list of cards on the current page (never {@code null} —
     *         empty for empty result sets). The returned list is immutable
     *         (constructed via {@link List#copyOf(java.util.Collection)}).
     */
    public List<Card> getCards() {
        return cards;
    }

    /**
     * Sets the cards list — provided for completeness; production code
     * paths use {@link #of(List, int, boolean, boolean)} factory.
     *
     * @param cards the new cards list
     */
    public void setCards(List<Card> cards) {
        this.cards = cards;
    }

    /**
     * @return the zero-based current page index (the
     *         {@link CardListRequest#getPage()} value that produced this
     *         response).
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
     * @return {@code true} when there is at least one record beyond the
     *         end of the current page; {@code false} on the final page.
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
