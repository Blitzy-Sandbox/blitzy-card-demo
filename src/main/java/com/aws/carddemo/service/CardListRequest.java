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
 * Mutable POJO request — the Java replacement for the {@code CCRDLIA} BMS-mapped
 * input record carrying paging navigation, the admin-vs-user view flag, and the
 * optional account/card filter keys from {@code app/bms/COCRDLI.bms} as read by
 * {@code app/cbl/COCRDLIC.cbl} (TRANID {@code CCLI}, the card-list dispatcher).
 *
 * <h2>COBOL Provenance — COCRDLIC.cbl (1,459 lines)</h2>
 *
 * <p>The COCRDLIC commarea (defined in {@code WS-THIS-PROGCOMMAREA}, lines
 * 229–248) combines several COBOL fields that this DTO consolidates:
 *
 * <ul>
 *   <li>{@code WS-CA-SCREEN-NUM PIC 9(1)} (line 237) — the current page
 *       counter (1-based in COBOL, zero-based here per Spring Data
 *       convention). The COBOL workflow increments / decrements this
 *       counter implicitly via PF7 / PF8 keystrokes; the Java migration
 *       accepts the target page index as an explicit field on the request
 *       DTO so the REST controller layer can resolve "next" / "previous"
 *       semantics declaratively.</li>
 *   <li>{@code CDEMO-USRTYP-USER} / admin flag (from {@code COCOM01Y.cpy})
 *       set at lines 320, 388, 466, 522, 550 — the operator's role. The
 *       {@code 0000-MAIN} paragraph routes admin operators to the
 *       unfiltered card browse and non-admin operators to a browse narrowed
 *       to their own account (lines 4-7 of the program header documentation
 *       describe the two paths). The Java migration carries this as
 *       {@link #userType} on the request DTO with values
 *       {@code "A"} (admin) or {@code "U"} (user).</li>
 *   <li>{@code CC-ACCT-ID} carried on the operator's CICS commarea — used
 *       at line 1386 of {@code 9500-FILTER-RECORDS} as the COBOL
 *       account-key filter equality test ({@code IF CARD-ACCT-ID =
 *       CC-ACCT-ID}). Surfaced here as {@link #accountIdFilter} so a user-
 *       view request scopes the browse to a single account.</li>
 *   <li>{@code CC-CARD-NUM-N} carried on the operator's CICS commarea —
 *       used at line 1397 of {@code 9500-FILTER-RECORDS} as the COBOL
 *       card-number filter equality test ({@code IF CARD-NUM = CC-CARD-NUM
 *       -N}). Surfaced here as {@link #cardNumberFilter} so the operator
 *       can narrow a long card list to a specific PAN or PAN prefix.</li>
 * </ul>
 *
 * <h2>Filter Validation — Java-migration delegation</h2>
 *
 * <p>The COBOL workflow validates filter input via the
 * {@code 1110-EDIT-INPUT-FILTERS} paragraph (lines 1004–1066) which
 * enforces "11 digit number" on the account filter (line 1022) and "16
 * digit number" on the card filter (line 1058), setting
 * {@code FLG-ACCTFILTER-NOT-OK} / {@code FLG-CARDFILTER-NOT-OK} on
 * malformed input. The Java migration delegates this format validation to
 * the REST controller layer's {@code @Valid} / Bean-Validation
 * annotations on the inbound request payload — the service trusts the
 * fields populated on the DTO are already format-correct so {@code null}
 * or empty means "no filter" and any non-empty value is forwarded
 * verbatim to the repository.
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
 * {@code CardListServiceTest.Pagination} and
 * {@code CardListServiceTest.Filters}.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This dispatcher-DTO lives alongside {@link CardListService} in
 * {@code com.aws.carddemo.service} rather than under a {@code dto/card}
 * subpackage. The card-list dispatcher's request/response shape is tightly
 * coupled to the service contract (no controller-layer adapter exists for
 * the card-list flow yet — the BMS-to-REST migration is staged for a
 * later phase). Keeping the DTO in the service package avoids a premature
 * {@code dto/card} subpackage with only two members and matches the
 * convention established by {@link TransactionListRequest},
 * {@link UserListRequest}, {@link MainMenuRequest},
 * {@link AdminMenuRequest}.
 *
 * @see CardListService
 * @see CardListResponse
 */
public class CardListRequest {

    /**
     * Zero-based page index. Page size is fixed at 7 rows per page (COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 7} at line 177–178 of
     * {@code app/cbl/COCRDLIC.cbl}, materialised in
     * {@link CardListService#PAGE_SIZE}).
     *
     * <p>The COBOL workflow uses {@code WS-CA-SCREEN-NUM PIC 9(1)} for a
     * 1-digit screen counter (1-based; supports up to 9 pages, after which
     * the COBOL workflow's behaviour would be undefined — but the small
     * fixture sizes never approach that bound). The Java migration carries
     * the index as a plain {@code int} (zero-based per Spring Data
     * conventions) so that
     * {@link org.springframework.data.domain.PageRequest#of(int, int)} can
     * receive it directly. Tests in {@code CardListServiceTest.Pagination}
     * drive this field with {@code 0} (first page) and {@code 7} (the
     * last partial page for a 50-row fixture set, since
     * {@code Math.ceil(50/7) = 8} pages, zero-based last index is 7).
     */
    private int page;

    /**
     * Operator role flag — {@code "A"} for admin (browse all cards across
     * all accounts; the COBOL {@code 0000-MAIN} paragraph routes here when
     * {@code CDEMO-USRTYP-ADMIN}) or {@code "U"} for user (browse only
     * cards owned by the operator's account; the COBOL workflow forces an
     * account-key filter for non-admin operators).
     *
     * <p>The {@link CardListService} dispatches the query based on this
     * flag:
     * <ul>
     *   <li>{@code "A"} + no filters → unfiltered
     *       {@link com.aws.carddemo.repository.CardRepository#findAll(org.springframework.data.domain.Pageable)}
     *       (admin browse).</li>
     *   <li>{@code "U"} + populated {@link #accountIdFilter} →
     *       {@link com.aws.carddemo.repository.CardRepository#findByAccountId(String, org.springframework.data.domain.Pageable)}
     *       narrowing the browse to one account.</li>
     *   <li>{@code "A"} or {@code "U"} + populated {@link #cardNumberFilter}
     *       →
     *       {@link com.aws.carddemo.repository.CardRepository#findByCardNumberStartsWith(String, org.springframework.data.domain.Pageable)}
     *       narrowing the browse to a card-number prefix.</li>
     * </ul>
     *
     * <p>COBOL provenance: {@code SET CDEMO-USRTYP-USER TO TRUE} at lines
     * 320, 388, 466, 522, 550 of {@code COCRDLIC.cbl}. The two-letter
     * encoding ({@code "A"} / {@code "U"}) mirrors the COBOL {@code SEC-
     * USR-TYPE PIC X(01)} field in {@code app/cpy/CSUSR01Y.cpy}.
     */
    private String userType;

    /**
     * Optional account ID filter — narrows the result set to cards whose
     * {@code CARD-ACCT-ID} matches the supplied 11-character zero-padded
     * account identifier. {@code null} or empty means "no account filter".
     *
     * <p>COBOL provenance: the {@code 9500-FILTER-RECORDS} paragraph (line
     * 1382) at line 1386 performs an equality test {@code IF CARD-ACCT-ID =
     * CC-ACCT-ID}; the Java migration delegates the equivalent semantic to
     * the {@link com.aws.carddemo.repository.CardRepository#findByAccountId(String, org.springframework.data.domain.Pageable)}
     * derived query.
     *
     * <p>Sample literal: {@code "00000000010"} (eleven digits, zero-padded
     * per COBOL {@code CARD-ACCT-ID PIC 9(11)} from
     * {@code app/cpy/CVACT02Y.cpy}).
     */
    private String accountIdFilter;

    /**
     * Optional card number filter — narrows the result set to cards whose
     * {@code CARD-NUM} starts with the supplied 16-character PAN (or PAN
     * prefix). {@code null} or empty means "no card filter".
     *
     * <p>COBOL provenance: the {@code 9500-FILTER-RECORDS} paragraph (line
     * 1382) at line 1397 performs an equality test {@code IF CARD-NUM =
     * CC-CARD-NUM-N} on the exact 16-digit PAN. The Java migration
     * generalises this to a prefix match via
     * {@link com.aws.carddemo.repository.CardRepository#findByCardNumberStartsWith(String, org.springframework.data.domain.Pageable)}
     * so the REST controller layer can support both exact lookups and
     * partial-prefix narrowing (documented Java-migration enhancement per
     * AAP §0.10.2). Passing the full 16-digit PAN matches a single card
     * and replicates the COBOL semantic exactly.
     *
     * <p>Sample literal: {@code "4111111111111101"} (sixteen-digit Visa
     * test PAN from {@code app/cpy/CVACT02Y.cpy} {@code CARD-NUM PIC X(16)}).
     *
     * <h3>Filter precedence when both are populated</h3>
     *
     * <p>The production {@link CardListService} evaluates
     * {@link #cardNumberFilter} <em>before</em> {@link #accountIdFilter} so
     * a card-number prefix is the most specific filter and wins over the
     * broader account filter. The REST controller layer is responsible for
     * either rejecting requests with both filters set, or for choosing
     * which filter to forward to the service — the service itself does not
     * complain about ambiguous filters.
     */
    private String cardNumberFilter;

    /**
     * No-args constructor — required for Spring MVC {@code @ModelAttribute}
     * binding (the controller layer reflectively instantiates this DTO and
     * populates fields via the setters below). Tests in
     * {@code CardListServiceTest} also use this constructor followed by
     * setter calls to populate the test fixture incrementally.
     */
    public CardListRequest() {
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
     *             fixed at 7 (see {@link CardListService#PAGE_SIZE}).
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * @return the operator role flag ({@code "A"} = admin, {@code "U"} =
     *         user); {@code null} is permitted at the service boundary and
     *         is treated identically to {@code "U"} (user-scope browse).
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Sets the operator role flag.
     *
     * @param userType {@code "A"} (admin browse — all cards) or {@code "U"}
     *                 (user browse — only cards for the operator's
     *                 {@link #accountIdFilter}); {@code null} is treated
     *                 identically to {@code "U"}.
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * @return the optional account ID filter, or {@code null} when no
     *         account filter is applied. Empty string is treated identically
     *         to {@code null} by the production {@link CardListService}.
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
     * @return the optional card number filter (or prefix), or {@code null}
     *         when no card filter is applied. Empty string is treated
     *         identically to {@code null} by the production
     *         {@link CardListService}.
     */
    public String getCardNumberFilter() {
        return cardNumberFilter;
    }

    /**
     * Sets the optional card number filter.
     *
     * @param cardNumberFilter 16-character Visa-format PAN, or a shorter
     *                         prefix for partial-match narrowing (e.g.
     *                         {@code "4111111111111101"} for an exact
     *                         match, or {@code "4111"} for a prefix
     *                         narrowing); {@code null} or empty means
     *                         "no card filter".
     */
    public void setCardNumberFilter(String cardNumberFilter) {
        this.cardNumberFilter = cardNumberFilter;
    }
}
