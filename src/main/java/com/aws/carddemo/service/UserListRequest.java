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
 * Mutable POJO request — the Java replacement for the {@code COUSR0AI} BMS-mapped
 * input record carrying paging navigation, the optional user-type filter, and the
 * caller's user-type code from {@code app/bms/COUSR00.bms} as read by
 * {@code app/cbl/COUSR00C.cbl} (TRANID {@code CU00}, the admin-only user-list
 * dispatcher).
 *
 * <h2>COBOL Provenance — COUSR00C.cbl</h2>
 *
 * <p>The {@code RECEIVE-USRLST-SCREEN} paragraph (lines ~600–630) populates the
 * {@code COUSR0AI} input map with the operator's most recent keystroke. Three
 * COBOL working-storage fields combine to express the equivalent of this DTO:
 *
 * <ul>
 *   <li>{@code CDEMO-CU00-PAGE-NUM PIC 9(08)} — the current page index used by
 *       {@code PROCESS-PAGE-FORWARD} (line 309) and
 *       {@code PROCESS-PAGE-BACKWARD} (lines 367–369). The COBOL workflow
 *       increments and decrements this counter implicitly via PF7/PF8
 *       keystrokes; the Java migration accepts the target page index as an
 *       explicit field on the request DTO so the REST controller layer can
 *       resolve "next" / "previous" semantics declaratively.</li>
 *   <li>{@code USRIDINI OF COUSR0AI PIC X(08)} — the optional user-type filter
 *       carried as the starting browse key. The COBOL workflow uses this field
 *       to STARTBR the USRSEC VSAM file at a specific user-ID prefix; the Java
 *       migration repurposes the field for declarative user-type filtering
 *       (one of {@code "A"} for admins or {@code "U"} for regular users), which
 *       maps onto the {@code UserSecurityRepository.findByUserType(...)}
 *       query method.</li>
 *   <li>{@code CDEMO-USRTYP-ADMIN} 88-level test on {@code CARDDEMO-COMMAREA}
 *       (COCOM01Y copybook) — the caller's authenticated user-type, gating
 *       the admin-only authorisation check (see {@link UserListService}).
 *       Although the COBOL source does not contain an explicit user-type
 *       check inside {@code COUSR00C} (the program is reachable only from
 *       {@code COADM01C} which is itself admin-only), the Java migration
 *       tightens this implicit contract by re-verifying the user-type
 *       inside the dispatcher itself, so the service is safe to invoke from
 *       any caller without relying on the upstream menu's enforcement.</li>
 * </ul>
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code callerUserType} — {@code "A"} (admin, COBOL
 *       {@code CDEMO-USRTYP-ADMIN}) or {@code "U"} (regular user, COBOL
 *       {@code CDEMO-USRTYP-USER}); single-character per
 *       {@code SEC-USR-TYPE PIC X(01)} in {@code app/cpy/CSUSR01Y.cpy}. Any
 *       non-{@code "A"} value rejects the request with the
 *       {@link UserListService#MSG_NOT_AUTHORIZED} message.</li>
 *   <li>{@code page} — zero-based page index. Page size is fixed at 10 rows
 *       per page (COBOL {@code WS-MAX-SCREEN-LINES VALUE 10}, see
 *       {@link UserListService#PAGE_SIZE}).</li>
 *   <li>{@code userTypeFilter} — optional, filters results to a specific
 *       user-type. {@code null} or empty means "no filter" (the production
 *       service falls back to the unfiltered {@code findAll(Pageable)} query).
 *       Valid values: {@code "A"} (admins only) or {@code "U"} (regular users
 *       only); any other value is forwarded to
 *       {@link com.aws.carddemo.repository.UserSecurityRepository#findByUserType(String, org.springframework.data.domain.Pageable)}
 *       which will return an empty page for unknown types.</li>
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
 * {@code UserListServiceTest.Authorization} and
 * {@code UserListServiceTest.Pagination}.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This dispatcher-DTO lives alongside {@link UserListService} in
 * {@code com.aws.carddemo.service} rather than under a {@code dto/user}
 * subpackage. The user-list dispatcher's request/response shape is tightly
 * coupled to the service contract (no controller-layer adapter exists for the
 * user-management flow yet — the BMS-to-REST migration is staged for a later
 * phase). Keeping the DTO in the service package avoids a premature
 * {@code dto/user} subpackage with only two members and matches the convention
 * established by {@link MainMenuRequest} / {@link AdminMenuRequest}.
 *
 * @see UserListService
 * @see UserListResponse
 */
public class UserListRequest {

    /**
     * Caller's user-type code — {@code "A"} for admin users (COBOL
     * {@code CDEMO-USRTYP-ADMIN}) or {@code "U"} for regular users (COBOL
     * {@code CDEMO-USRTYP-USER}). Matches the {@code SEC-USR-TYPE PIC X(01)}
     * field from {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>The user-list dispatcher ({@link UserListService}, this service)
     * enforces an admin-only authorisation check on this field: any non-
     * {@code "A"} value short-circuits the request to a failure response
     * carrying the {@link UserListService#MSG_NOT_AUTHORIZED} reject message.
     * The check happens BEFORE any database access so the repository is never
     * queried on a rejected call (verifiable via Mockito
     * {@code verify(repo, never()).findAll(...)}).
     *
     * <p>Note: the COBOL source does not contain an explicit user-type test
     * inside {@code COUSR00C} because the program is only reachable from the
     * admin menu {@code COADM01C}. The Java migration tightens this implicit
     * contract by re-verifying the user-type inside the dispatcher itself.
     * This is a documented Java-migration addition (AAP §0.10.2: "All
     * deviations from literal COBOL logic must be documented with the original
     * COBOL paragraph name and reason for divergence"). Reason: the REST
     * controller layer is reachable independently of the menu flow, so
     * defence-in-depth requires the service to re-enforce the admin-only
     * contract.
     */
    private String callerUserType;

    /**
     * Zero-based page index. Page size is fixed at 10 rows per page (COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 10}, materialised in
     * {@link UserListService#PAGE_SIZE}).
     *
     * <p>The COBOL workflow uses {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} for an
     * unbounded 8-digit page counter; the Java migration carries the index as
     * a plain {@code int} (zero-based per Spring Data conventions) so that
     * {@link org.springframework.data.domain.PageRequest#of(int, int)} can
     * receive it directly. Tests in {@code UserListServiceTest.Pagination}
     * drive this field with {@code 0} (first page) and would drive
     * non-negative values for higher pages.
     */
    private int page;

    /**
     * Optional user-type filter — narrows the result set to a specific user
     * type when non-{@code null} and non-empty. Valid values: {@code "A"} for
     * admins, {@code "U"} for regular users. {@code null} or empty means "no
     * filter" (the production service falls back to the unfiltered
     * {@code findAll(Pageable)} query path).
     *
     * <p>Unknown values (e.g., {@code "X"}) are forwarded to
     * {@link com.aws.carddemo.repository.UserSecurityRepository#findByUserType(String, org.springframework.data.domain.Pageable)}
     * verbatim; the repository returns an empty page for unknown types,
     * which the service surfaces as a successful response with an empty user
     * list (matching the convention in
     * {@link UserListResponse#success(java.util.List, boolean, boolean)}).
     *
     * <p>This field is the Java-migration equivalent of the COBOL
     * {@code USRIDINI OF COUSR0AI PIC X(08)} starting-browse key, repurposed
     * for declarative user-type filtering (the original starting-browse
     * semantic mapped to VSAM STARTBR, which has no idiomatic Spring Data
     * equivalent for a non-key-prefixed scan).
     */
    private String userTypeFilter;

    /**
     * No-args constructor — required for Spring MVC {@code @ModelAttribute}
     * binding (the controller layer reflectively instantiates this DTO and
     * populates fields via the setters below). Tests in
     * {@code UserListServiceTest} also use this constructor followed by
     * setter calls to populate the test fixture incrementally.
     */
    public UserListRequest() {
        // Intentionally empty — Spring MVC and tests populate via setters.
    }

    /**
     * @return the caller's user-type code ({@code "A"} or {@code "U"}); may be
     *         {@code null} if the request was constructed without a user-type
     *         (in which case the production service rejects with
     *         {@link UserListService#MSG_NOT_AUTHORIZED} because the
     *         admin-only check requires an explicit {@code "A"}).
     */
    public String getCallerUserType() {
        return callerUserType;
    }

    /**
     * Sets the caller's user-type code.
     *
     * @param callerUserType {@code "A"} (admin) or {@code "U"} (regular user);
     *                       any other value is treated by the production
     *                       service as "not admin" and rejected with the
     *                       {@link UserListService#MSG_NOT_AUTHORIZED}
     *                       message. The authentication service is
     *                       responsible for producing only {@code "U"} or
     *                       {@code "A"} (defence in depth — this service
     *                       re-verifies regardless).
     */
    public void setCallerUserType(String callerUserType) {
        this.callerUserType = callerUserType;
    }

    /**
     * @return the zero-based page index; never negative for well-formed
     *         requests, but the production service does not validate the
     *         lower bound because {@link org.springframework.data.domain.PageRequest}
     *         itself rejects negative values with
     *         {@link IllegalArgumentException}.
     */
    public int getPage() {
        return page;
    }

    /**
     * Sets the zero-based page index.
     *
     * @param page the page index to retrieve (0 = first page). Page size is
     *             fixed at 10 (see {@link UserListService#PAGE_SIZE}).
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * @return the optional user-type filter, or {@code null} when no filter is
     *         applied (the production service then falls back to the
     *         unfiltered {@code findAll} query path).
     */
    public String getUserTypeFilter() {
        return userTypeFilter;
    }

    /**
     * Sets the optional user-type filter.
     *
     * @param userTypeFilter {@code "A"} (admins only), {@code "U"} (regular
     *                       users only), or {@code null} / empty for no
     *                       filter; unknown values are forwarded to the
     *                       repository query verbatim and yield an empty
     *                       result set.
     */
    public void setUserTypeFilter(String userTypeFilter) {
        this.userTypeFilter = userTypeFilter;
    }
}
