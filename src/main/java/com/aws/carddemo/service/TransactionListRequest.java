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

/**
 * Mutable POJO request — the Java replacement for the {@code COTRN0AI} BMS-mapped
 * input record carrying paging navigation and the optional account/card filter
 * keys from {@code app/bms/COTRN00.bms} as read by {@code app/cbl/COTRN00C.cbl}
 * (TRANID {@code CT00}, the transaction-list dispatcher).
 *
 * <h2>COBOL Provenance — COTRN00C.cbl</h2>
 *
 * <p>The {@code RECEIVE-TRNLST-SCREEN} paragraph populates the {@code COTRN0AI}
 * input map with the operator's most recent keystroke. Two COBOL working-storage
 * fields combine to express the equivalent of this DTO:
 *
 * <ul>
 *   <li>{@code CDEMO-CT00-PAGE-NUM PIC 9(08)} — the current page counter used
 *       by {@code PROCESS-PAGE-FORWARD} (line 306) and
 *       {@code PROCESS-PAGE-BACKWARD} (lines 363–366). The COBOL workflow
 *       increments and decrements this counter implicitly via PF7/PF8
 *       keystrokes; the Java migration accepts the target page index as an
 *       explicit field on the request DTO so the REST controller layer can
 *       resolve "next" / "previous" semantics declaratively.</li>
 *   <li>{@code TRNIDINI OF COTRN0AI PIC X(16)} — the starting browse key
 *       carried into {@code PROCESS-ENTER-KEY} (lines 206–219). The COBOL
 *       workflow uses this field as a {@code STARTBR} starting key on the
 *       {@code TRANSACT} VSAM file; the Java migration repurposes the
 *       request DTO to carry two explicit filter fields
 *       ({@link #accountIdFilter} and {@link #cardNumberFilter}) instead of
 *       a single starting-key field.</li>
 * </ul>
 *
 * <h2>Java-Migration Additions — account and card filters</h2>
 *
 * <p>The Java migration introduces two declarative filters
 * ({@link #accountIdFilter}, {@link #cardNumberFilter}) as documented
 * Java-migration additions (AAP §0.10.2). The COBOL source workflow has no
 * explicit account-key or card-key filter — the operator could only navigate
 * by {@code TRAN-ID} via the {@code TRNIDINI} starting-browse key. The Java
 * migration adds these filters because:
 * <ul>
 *   <li>The REST controller layer exposes account and card filters as URL
 *       query parameters; these map naturally onto the
 *       {@link com.aws.carddemo.repository.TransactionRepository#findByAccountId(String, org.springframework.data.domain.Pageable)}
 *       and
 *       {@link com.aws.carddemo.repository.TransactionRepository#findByCardNumber(String, org.springframework.data.domain.Pageable)}
 *       query methods.</li>
 *   <li>The 3270 workflow required operators to navigate manually from the
 *       {@code CARDDAT} alternate-index to {@code TRANSACT} when researching
 *       transactions for a specific account — the Java migration provides
 *       that same navigation declaratively.</li>
 *   <li>Filters are mutually optional: a request may carry neither filter
 *       (unfiltered browse), only {@link #accountIdFilter}, only
 *       {@link #cardNumberFilter}, or both. The production
 *       {@link TransactionListService#listTransactions(TransactionListRequest)}
 *       method dispatches the query based on which filter (if any) is
 *       populated — see that class's "Filter dispatch" section.</li>
 * </ul>
 *
 * <h2>Mutable vs Immutable</h2>
 *
 * <p>This DTO is a mutable POJO with explicit getters and setters (rather than
 * the Java {@code record} idiom used by the authentication DTOs at
 * {@code com.aws.carddemo.dto.auth}). The setter-based shape allows the
 * controller layer to populate the request incrementally from Spring MVC
 * {@code @ModelAttribute} binding, matching Spring's default Java-Bean form
 * binding without requiring a custom converter. Tests construct via the
 * no-args constructor and call setters — see
 * {@code TransactionListServiceTest.Pagination} and
 * {@code TransactionListServiceTest.Filters}.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This dispatcher-DTO lives alongside {@link TransactionListService} in
 * {@code com.aws.carddemo.service} rather than under a {@code dto/transaction}
 * subpackage. The transaction-list dispatcher's request/response shape is
 * tightly coupled to the service contract (no controller-layer adapter exists
 * for the transaction-list flow yet — the BMS-to-REST migration is staged for
 * a later phase). Keeping the DTO in the service package avoids a premature
 * {@code dto/transaction} subpackage with only two members and matches the
 * convention established by {@link MainMenuRequest} / {@link AdminMenuRequest}
 * / {@link UserListRequest}.
 *
 * @see TransactionListService
 * @see TransactionListResponse
 */
public class TransactionListRequest {

    /**
     * Zero-based page index. Page size is fixed at 10 rows per page (COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 10}, materialised in
     * {@link TransactionListService#PAGE_SIZE}).
     *
     * <p>The COBOL workflow uses {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} for an
     * unbounded 8-digit page counter; the Java migration carries the index as
     * a plain {@code int} (zero-based per Spring Data conventions) so that
     * {@link org.springframework.data.domain.PageRequest#of(int, int)} can
     * receive it directly. Tests in {@code TransactionListServiceTest.Pagination}
     * drive this field with {@code 0} (first page) and {@code 9} (a late
     * page for the last-page boundary case).
     */
    private int page;

    /**
     * Optional account ID filter — narrows the result set to transactions
     * whose underlying card belongs to the supplied 11-character zero-padded
     * account identifier. {@code null} or empty means "no account filter"
     * (the production service falls back to the unfiltered
     * {@code findAll(Pageable)} query path, or to the
     * {@link #cardNumberFilter}-driven path if that filter is populated).
     *
     * <p>This field is a Java-migration addition (AAP §0.10.2): the COBOL
     * source workflow has no explicit account-key filter — the operator
     * could only navigate by {@code TRAN-ID} via the {@code TRNIDINI}
     * starting-browse key. The Java migration adds this filter because the
     * REST controller layer surfaces a {@code ?account=NNNN...} query
     * parameter that maps onto the
     * {@link com.aws.carddemo.repository.TransactionRepository#findByAccountId(String, org.springframework.data.domain.Pageable)}
     * query method.
     *
     * <p>Sample literal: {@code "00000000010"} (eleven digits, zero-padded
     * per COBOL {@code CARD-ACCT-ID PIC 9(11)} from
     * {@code app/cpy/CVACT02Y.cpy}).
     */
    private String accountIdFilter;

    /**
     * Optional card number filter — narrows the result set to transactions
     * whose {@code TRAN-CARD-NUM} matches the supplied 16-character PAN.
     * {@code null} or empty means "no card filter" (the production service
     * falls back to the unfiltered {@code findAll(Pageable)} query path, or
     * to the {@link #accountIdFilter}-driven path if that filter is
     * populated).
     *
     * <p>This field is a Java-migration addition (AAP §0.10.2): the COBOL
     * source workflow has no explicit card-key filter. The Java migration
     * adds this filter because the REST controller layer surfaces a
     * {@code ?card=NNNN...} query parameter that maps onto the
     * {@link com.aws.carddemo.repository.TransactionRepository#findByCardNumber(String, org.springframework.data.domain.Pageable)}
     * query method.
     *
     * <p>Sample literal: {@code "4111111111111101"} (sixteen-digit Visa test
     * PAN from {@code app/cpy/CVTRA05Y.cpy} {@code TRAN-CARD-NUM PIC X(16)}).
     *
     * <h3>Filter precedence when both are populated</h3>
     *
     * <p>The production {@link TransactionListService} evaluates
     * {@link #accountIdFilter} <em>before</em> {@link #cardNumberFilter}; if
     * both are populated, the account filter wins and the card filter is
     * effectively ignored. The REST controller layer is responsible for
     * either rejecting requests with both filters set, or for choosing
     * which filter to forward to the service — the service itself does not
     * complain about ambiguous filters.
     */
    private String cardNumberFilter;

    /**
     * No-args constructor — required for Spring MVC {@code @ModelAttribute}
     * binding (the controller layer reflectively instantiates this DTO and
     * populates fields via the setters below). Tests in
     * {@code TransactionListServiceTest} also use this constructor followed
     * by setter calls to populate the test fixture incrementally.
     */
    public TransactionListRequest() {
        // Intentionally empty — Spring MVC and tests populate via setters.
    }

    /**
     * @return the zero-based page index; never negative for well-formed
     *         requests, but the production service does not validate the
     *         lower bound because
     *         {@link org.springframework.data.domain.PageRequest} itself
     *         rejects negative values with {@link IllegalArgumentException}.
     */
    public int getPage() {
        return page;
    }

    /**
     * Sets the zero-based page index.
     *
     * @param page the page index to retrieve (0 = first page). Page size is
     *             fixed at 10 (see {@link TransactionListService#PAGE_SIZE}).
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * @return the optional account ID filter, or {@code null} when no
     *         account filter is applied. Empty string is treated identically
     *         to {@code null} by the production
     *         {@link TransactionListService}.
     */
    public String getAccountIdFilter() {
        return accountIdFilter;
    }

    /**
     * Sets the optional account ID filter.
     *
     * @param accountIdFilter 11-character zero-padded account identifier
     *                        (e.g. {@code "00000000010"}); {@code null} or
     *                        empty means "no account filter" and falls back
     *                        to the unfiltered query path.
     */
    public void setAccountIdFilter(String accountIdFilter) {
        this.accountIdFilter = accountIdFilter;
    }

    /**
     * @return the optional card number filter, or {@code null} when no card
     *         filter is applied. Empty string is treated identically to
     *         {@code null} by the production {@link TransactionListService}.
     */
    public String getCardNumberFilter() {
        return cardNumberFilter;
    }

    /**
     * Sets the optional card number filter.
     *
     * @param cardNumberFilter 16-character Visa-format PAN (e.g.
     *                         {@code "4111111111111101"}); {@code null} or
     *                         empty means "no card filter" and falls back
     *                         to the unfiltered query path (or to the
     *                         account-filter path if
     *                         {@link #accountIdFilter} is populated).
     */
    public void setCardNumberFilter(String cardNumberFilter) {
        this.cardNumberFilter = cardNumberFilter;
    }
}
