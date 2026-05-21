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

import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.repository.UserSecurityRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * User-list service — the Java migration of the CICS user-list dispatcher
 * {@code app/cbl/COUSR00C.cbl} (TRANID {@code CU00}). Returns a paged list of
 * {@link SecurityUser} entities for the admin-only user-management screen,
 * with optional user-type filtering and admin-only authorisation enforcement.
 *
 * <h2>COBOL Provenance — COUSR00C.cbl (695 lines)</h2>
 *
 * <p>The COBOL workflow walks the {@code USRSEC} VSAM KSDS via
 * {@code STARTBR} / {@code READNEXT} loops, populating a fixed-width
 * 10-row {@code USER-REC OCCURS 10 TIMES} table on the {@code COUSR0AO} BMS
 * output map:
 *
 * <ol>
 *   <li>{@code PROCESS-PAGE-FORWARD} (lines 282–331) — the canonical page
 *       advance: {@code STARTBR-USER-SEC-FILE} (line 284), then
 *       {@code PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR ERR-FLG-ON}
 *       (line 300) reading up to 10 records into the table via
 *       {@code POPULATE-USER-DATA} (lines 384–441).</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} (lines 336–379) — the PF7 / previous
 *       page workflow using {@code READPREV} instead of {@code READNEXT}.</li>
 *   <li>{@code WS-MAX-SCREEN-LINES VALUE 10} (working-storage) — the page
 *       size, materialised in this Java migration as {@link #PAGE_SIZE}.</li>
 *   <li>{@code CDEMO-CU00-PAGE-NUM PIC 9(08)} (working-storage) — the
 *       current page counter, replaced in the Java migration by
 *       {@link Pageable#getPageNumber()}.</li>
 * </ol>
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>Admin-only authorisation re-verification.</b> The COBOL source does
 *       not contain an explicit user-type test inside {@code COUSR00C} because
 *       the program is only reachable from {@code COADM01C} (the admin menu),
 *       which is itself protected by the sign-on flow's
 *       {@code CDEMO-USRTYP-ADMIN} branch. The Java migration tightens this
 *       implicit contract by re-verifying the user-type inside the dispatcher
 *       itself, so the service is safe to invoke from any caller without
 *       relying on the upstream menu's enforcement. This is a documented
 *       Java-migration addition (AAP §0.10.2: "All deviations from literal
 *       COBOL logic must be documented with the original COBOL paragraph
 *       name and reason for divergence"). Reason: the REST controller layer
 *       is reachable independently of the menu flow, so defence-in-depth
 *       requires the service to re-enforce the admin-only contract. The
 *       authorisation check happens BEFORE any database access (the
 *       repository is never queried on a rejected call) so the rejection
 *       cost is minimal.</li>
 *   <li><b>STARTBR / READNEXT replaced by Spring Data Pageable.</b> The
 *       COBOL workflow opens a VSAM browse position, reads up to 10 records,
 *       and closes the position. The Java migration delegates the equivalent
 *       semantic to Spring Data's
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)},
 *       which performs an offset-based query against the underlying
 *       PostgreSQL table. The page size is fixed at {@link #PAGE_SIZE}
 *       (matching the COBOL {@code WS-MAX-SCREEN-LINES VALUE 10}).</li>
 *   <li><b>Optional user-type filter.</b> The COBOL {@code USRIDINI OF
 *       COUSR0AI PIC X(08)} starting-browse key implemented a user-ID prefix
 *       filter that the operator could use to narrow the browse position.
 *       The Java migration repurposes that input field for declarative
 *       user-type filtering (one of {@code "A"} or {@code "U"}), which
 *       routes the query through
 *       {@link UserSecurityRepository#findByUserType(String, Pageable)}
 *       instead of {@link UserSecurityRepository#findAll(Pageable)}. The
 *       starting-browse-by-prefix idiom has no clean Spring Data equivalent
 *       for a non-key-prefixed scan, so the migration substitutes the
 *       user-type filter which is the natural derived-query operation.
 *       Documented divergence (AAP §0.10.2).</li>
 *   <li><b>Page-navigation flags promoted to the response DTO.</b> The COBOL
 *       workflow maintained a {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO}
 *       88-level switch (line 73) and derived "has previous page" from the
 *       page-number counter ({@code CDEMO-CU00-PAGE-NUM > 1}). The Java
 *       migration surfaces both as explicit boolean fields on
 *       {@link UserListResponse} ({@link UserListResponse#isHasNext()} and
 *       {@link UserListResponse#isHasPrevious()}), populated from
 *       {@link Page#hasNext()} and {@link Page#hasPrevious()} respectively.
 *       This lets the REST controller layer render PF7/PF8-equivalent
 *       navigation affordances without re-querying the repository.</li>
 *   <li><b>No CICS commarea state.</b> The COBOL workflow stitches multiple
 *       calls together via {@code CARDDEMO-COMMAREA}; the Java migration
 *       reduces the dispatcher to a stateless function
 *       {@code listUsers(req)} on a per-request basis. The page-navigation
 *       state lives in the {@link UserListRequest} on the way in and on the
 *       {@link UserListResponse} on the way out.</li>
 *   <li><b>No {@code @Service} stereotype yet.</b> This class deliberately
 *       omits the {@code @Service} annotation; subsequent migration agents
 *       will add it when the full Spring application context is wired up.
 *       For now the constructor accepts collaborators directly so unit
 *       tests can wire mocks without a Spring context, matching the
 *       convention established by {@link AuthenticationService},
 *       {@link MainMenuService}, {@link AdminMenuService},
 *       {@link AccountViewService}, {@link CardDetailService}, and
 *       {@link TransactionDetailService}.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE user-list dispatcher logic (no helpers
 * extracted to other classes; all branches are visible in the single
 * {@link #listUsers(UserListRequest)} entry point). The corresponding
 * {@code UserListServiceTest} exercises every branch via real method calls
 * with a mocked {@link UserSecurityRepository} at the database boundary
 * only — no business logic (admin check, page-size selection, filter
 * dispatch, response construction) is duplicated inside the test.
 *
 * @see UserListRequest
 * @see UserListResponse
 * @see UserSecurityRepository
 * @see SecurityUser
 */
public class UserListService {

    // ---------------------------------------------------------------------
    // Authorisation message — Java-migration addition
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when the caller's user-type is not {@code "A"}
     * (the COBOL {@code CDEMO-USRTYP-ADMIN} value). The Java migration adds
     * this admin-only check inside the dispatcher itself — see the class-level
     * "Admin-only authorisation re-verification" note for the rationale.
     *
     * <p>The literal message text is the Java-migration equivalent of the
     * COBOL admin-only reject message found in companion programs (for
     * example {@code AdminMenuService.MSG_NOT_AUTHORIZED}); using a shared
     * canonical phrasing keeps downstream UX consistent. The message
     * deliberately anchors on the lower-case phrase {@code "not authorized"}
     * so the corresponding test assertion ({@code containsIgnoringCase("not
     * authorized")}) is stable across copy edits.
     */
    static final String MSG_NOT_AUTHORIZED =
            "You are not authorized to access this menu...";

    // ---------------------------------------------------------------------
    // Page-size constant — direct port of COBOL WS-MAX-SCREEN-LINES VALUE 10
    // ---------------------------------------------------------------------

    /**
     * Page size — the number of {@link SecurityUser} entries returned per
     * call. Direct port of COBOL {@code WS-MAX-SCREEN-LINES VALUE 10} from
     * {@code app/cbl/COUSR00C.cbl} working-storage. The COBOL workflow uses
     * this constant as the loop bound for the {@code POPULATE-USER-DATA}
     * dispatch (lines 384–441 dispatch by {@code WS-IDX} 1 through 10); the
     * Java migration forwards the constant to
     * {@link PageRequest#of(int, int)} so Spring Data applies the same row
     * limit at the database layer.
     */
    static final int PAGE_SIZE = 10;

    /**
     * Admin user-type code — COBOL {@code CDEMO-USRTYP-ADMIN} 88-level value
     * ({@code "A"}). The dispatcher's admin-only check compares
     * {@link UserListRequest#getCallerUserType()} against this constant.
     */
    static final String USER_TYPE_ADMIN = "A";

    // ---------------------------------------------------------------------
    // Collaborator boundary — single JPA repository
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link SecurityUser} entities — the Java replacement
     * for COBOL {@code STARTBR / READNEXT} loops against the {@code USRSEC}
     * VSAM KSDS in {@code app/cbl/COUSR00C.cbl}. Constructor-injected so
     * tests can wire a Mockito mock without a Spring context.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructs a new {@code UserListService}.
     *
     * @param userSecurityRepository JPA repository for {@link SecurityUser}
     *                               lookups (Java replacement for COBOL
     *                               {@code EXEC CICS STARTBR / READNEXT}
     *                               on {@code USRSEC}). Must not be
     *                               {@code null} — the service does not
     *                               guard against {@code null} collaborators
     *                               because Spring DI would surface the
     *                               misconfiguration at startup; unit tests
     *                               wire a Mockito mock.
     */
    public UserListService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * List a single page of {@link SecurityUser} entities for the admin-only
     * user-management screen. Implements the Java equivalent of the COBOL
     * {@code PROCESS-PAGE-FORWARD} paragraph in {@code app/cbl/COUSR00C.cbl}.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Admin-only authorisation check — if
     *       {@link UserListRequest#getCallerUserType()} is not
     *       {@link #USER_TYPE_ADMIN} ({@code "A"}), reject with
     *       {@link #MSG_NOT_AUTHORIZED}. The check happens BEFORE any
     *       database access (no repository query is invoked on a rejected
     *       call), matching the COBOL convention where the admin gate is
     *       upstream of the {@code USRSEC} browse.</li>
     *   <li>Build the {@link PageRequest} from the request's zero-based page
     *       index and the {@link #PAGE_SIZE} constant (matching the COBOL
     *       {@code WS-MAX-SCREEN-LINES VALUE 10}).</li>
     *   <li>Dispatch the query:
     *       <ul>
     *         <li>When the request carries a non-{@code null} / non-empty
     *             {@link UserListRequest#getUserTypeFilter()}, route through
     *             {@link UserSecurityRepository#findByUserType(String, Pageable)}.</li>
     *         <li>Otherwise, route through
     *             {@link UserSecurityRepository#findAll(Pageable)}.</li>
     *       </ul>
     *   </li>
     *   <li>Build and return a {@link UserListResponse#success(java.util.List, boolean, boolean)}
     *       carrying the page's {@link Page#getContent()},
     *       {@link Page#hasNext()}, and {@link Page#hasPrevious()}.</li>
     * </ol>
     *
     * <p>Empty result sets (no users matching the query, or queries beyond
     * the last page) are surfaced as a <em>successful</em> response with an
     * empty user list and both navigation flags {@code false} — distinct
     * from the admin-only authorisation reject, which is a hard failure with
     * {@link UserListResponse#isSuccess()} = {@code false}.
     *
     * <p>Infrastructure errors (database unreachable, network failure, etc.)
     * surface as {@link org.springframework.dao.DataAccessException}
     * subclasses thrown by the repository; the service does not catch them,
     * letting the controller layer's exception-handler chain produce the
     * Java equivalent of the COBOL {@code 'File Error: ...'} response.
     *
     * @param request the user-list request carrying the caller's user-type
     *                code, the target page index, and the optional user-type
     *                filter; must not be {@code null} (the service does not
     *                guard against {@code null} — the controller layer is
     *                responsible for producing a populated request, and a
     *                {@code null} request would raise an immediate
     *                {@link NullPointerException} at the first field access)
     * @return a populated {@link UserListResponse} encoding either success
     *         (with a page of users and the navigation flags) or failure
     *         (with the admin-only reject message)
     */
    public UserListResponse listUsers(UserListRequest request) {
        // Step 1 — admin-only authorisation check (Java-migration addition;
        // see class-level "Admin-only authorisation re-verification" note).
        // Performed BEFORE any database access so the repository is never
        // queried on a rejected call.
        if (!USER_TYPE_ADMIN.equals(request.getCallerUserType())) {
            return UserListResponse.failure(MSG_NOT_AUTHORIZED);
        }

        // Step 2 — build the PageRequest from the request's zero-based page
        // index and the fixed PAGE_SIZE (COBOL WS-MAX-SCREEN-LINES VALUE 10).
        Pageable pageable = PageRequest.of(request.getPage(), PAGE_SIZE);

        // Step 3 — dispatch the query. The presence of a non-empty
        // userTypeFilter routes through the derived findByUserType query;
        // a null or empty filter falls through to the unfiltered findAll
        // (the inherited JpaRepository method).
        String filter = request.getUserTypeFilter();
        Page<SecurityUser> page;
        if (filter != null && !filter.isEmpty()) {
            page = userSecurityRepository.findByUserType(filter, pageable);
        } else {
            page = userSecurityRepository.findAll(pageable);
        }

        // Step 4 — build the success response carrying the page content and
        // the navigation flags. UserListResponse.success defensively copies
        // the page content so subsequent mutations of the underlying List
        // (rare, but possible if the page implementation reuses the list)
        // do not leak into the response.
        return UserListResponse.success(
                page.getContent(),
                page.hasNext(),
                page.hasPrevious());
    }
}
