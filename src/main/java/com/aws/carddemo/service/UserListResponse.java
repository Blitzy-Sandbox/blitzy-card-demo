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

import java.util.Collections;
import java.util.List;

/**
 * Result DTO for {@link UserListService#listUsers(UserListRequest)} — the Java
 * replacement for the {@code COUSR0AO} BMS-mapped output record emitted by
 * {@code app/cbl/COUSR00C.cbl} (TRANID {@code CU00}, the admin-only user-list
 * dispatcher). Encodes either a <em>success</em> outcome carrying a single page
 * of {@link SecurityUser} entities and the page-navigation flags ready for the
 * REST controller layer to serialise, or a <em>failure</em> outcome carrying
 * the COBOL-equivalent reject message (the admin-only authorisation reject
 * path).
 *
 * <h2>COBOL Provenance — COUSR00C.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL
 * {@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD} paragraphs
 * (lines 282–379) that populate the 10-occurrence {@code USER-REC OCCURS 10
 * TIMES} table on the {@code COUSR0AO} BMS output map after a STARTBR /
 * READNEXT loop against the {@code USRSEC} VSAM KSDS:
 *
 * <ul>
 *   <li>The {@code SEC-USR-ID PIC X(08)} / {@code SEC-USR-FNAME PIC X(20)} /
 *       {@code SEC-USR-LNAME PIC X(20)} / {@code SEC-USR-TYPE PIC X(01)}
 *       fields from each {@code USRSEC} record are mapped into the 10
 *       {@code USRID0nI} / {@code FNAME0nI} / {@code LNAME0nI} /
 *       {@code UTYPE0nI} fields of the {@code COUSR0AO} map via the
 *       {@code POPULATE-USER-DATA} paragraph (lines 384–441). The Java
 *       migration replaces this fixed-width 10-row table with a
 *       {@link List}&lt;{@link SecurityUser}&gt; carrying up to 10 entities.</li>
 *   <li>The {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch
 *       (line 73) is set after the READNEXT loop completes — {@code YES} if
 *       there is a non-EOF record beyond the current page, {@code NO}
 *       otherwise (lines 312–322). The Java migration surfaces this as
 *       {@link #hasNext}, derived from
 *       {@link org.springframework.data.domain.Page#hasNext()}.</li>
 *   <li>The Java migration adds an equivalent {@link #hasPrevious} flag
 *       (derived from {@link org.springframework.data.domain.Page#hasPrevious()})
 *       so the REST controller layer can render PF7-equivalent navigation
 *       affordances without re-querying the repository. The COBOL workflow
 *       inferred "has previous page" from the page-number counter
 *       ({@code CDEMO-CU00-PAGE-NUM > 1}, lines 248, 366); the Java
 *       migration delegates this derivation to Spring Data.</li>
 * </ul>
 *
 * <p>The failure outcome corresponds to the admin-only authorisation
 * reject branch added by the Java migration (see {@link UserListService}
 * "Authorization Check" section). The COBOL source does not contain an
 * explicit user-type test inside {@code COUSR00C} because the program is
 * only reachable from {@code COADM01C}; the Java migration tightens this
 * implicit contract by re-verifying the user-type inside the service, so
 * non-admin callers are rejected with the
 * {@link UserListService#MSG_NOT_AUTHORIZED} message.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods ({@link #success(List, boolean, boolean)} or
 * {@link #failure(String)}) so that the invariant between {@link #success},
 * {@link #users}, {@link #message}, {@link #hasNext}, and {@link #hasPrevious}
 * is preserved:
 * <ul>
 *   <li><b>success path:</b> {@link #success} = {@code true},
 *       {@link #users} = supplied list (defensive copy),
 *       {@link #message} = {@code null},
 *       {@link #hasNext} and {@link #hasPrevious} = supplied flags.</li>
 *   <li><b>failure path:</b> {@link #success} = {@code false},
 *       {@link #users} = empty immutable list (never {@code null} so callers
 *       can iterate without null-checks),
 *       {@link #message} = supplied reject message,
 *       {@link #hasNext} = {@link #hasPrevious} = {@code false}.</li>
 * </ul>
 *
 * <p>The no-args constructor is preserved for Spring MVC view-model binding
 * (if the REST controller layer ever needs to serialise this object directly
 * via Jackson), but production code paths inside {@link UserListService}
 * always invoke a factory method.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link UserListService} and
 * {@link UserListRequest} in {@code com.aws.carddemo.service} (rather than
 * under a dedicated {@code dto/user} subpackage). The dispatch pair is
 * tightly coupled to the service contract and there is no controller-layer
 * adapter yet that would justify a separate package boundary. This matches
 * the convention established by {@link MainMenuResponse} /
 * {@link AdminMenuResponse} / {@link AccountViewResponse} /
 * {@link CardDetailResponse} / {@link TransactionDetailResponse}.
 *
 * @see UserListService
 * @see UserListRequest
 * @see SecurityUser
 */
public class UserListResponse {

    /**
     * {@code true} when the request was authorised and the repository returned
     * a (possibly empty) page; {@code false} for the admin-only authorisation
     * reject branch.
     *
     * <p>Tests assert via {@link #isSuccess()} ({@code .isTrue()} for happy
     * paths, {@code .isFalse()} for the non-admin reject path).
     *
     * <p>Note: an authorised query that returns zero rows ({@code page.isEmpty()})
     * is still considered a <em>success</em> — the REST controller will render
     * an empty list to the operator. Per the COBOL convention this is the
     * "list is empty" terminal-page state, distinct from the
     * {@code You are not authorized...} reject (which is a hard failure).
     */
    private boolean success;

    /**
     * The single page of {@link SecurityUser} entities produced by the
     * repository query when {@link #success} is {@code true}; an empty
     * immutable list when {@link #success} is {@code false}.
     *
     * <p>The list always carries the {@link UserListService#PAGE_SIZE} count
     * or fewer (the final page is partial; an empty repository or a query
     * beyond the last page returns zero rows). The Java migration replaces
     * the fixed-width 10-row {@code USER-REC OCCURS 10 TIMES} table from the
     * COBOL {@code COUSR0AO} output map with this variable-length list.
     */
    private List<SecurityUser> users;

    /**
     * The reject message produced by the admin-only authorisation check when
     * {@link #success} is {@code false}; {@code null} when {@link #success}
     * is {@code true}.
     *
     * <p>Per {@link UserListService} message constants, the only failure
     * message currently produced is
     * {@link UserListService#MSG_NOT_AUTHORIZED}.
     */
    private String message;

    /**
     * {@code true} when there is at least one additional record beyond the
     * end of {@link #users} (the operator's PF8 — next-page — affordance
     * should be enabled); {@code false} on the final page or on a failure
     * response.
     *
     * <p>Derived from {@link org.springframework.data.domain.Page#hasNext()}
     * in {@link UserListService}. The Java migration replaces the COBOL
     * {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO} 88-level switch (line
     * 73, set at lines 313 and 315 of {@code PROCESS-PAGE-FORWARD}).
     */
    private boolean hasNext;

    /**
     * {@code true} when there is at least one record before the start of
     * {@link #users} (the operator's PF7 — previous-page — affordance
     * should be enabled); {@code false} on the first page or on a failure
     * response.
     *
     * <p>Derived from {@link org.springframework.data.domain.Page#hasPrevious()}
     * in {@link UserListService}. The Java migration adds this flag
     * explicitly; the COBOL workflow inferred the equivalent from
     * {@code CDEMO-CU00-PAGE-NUM > 1} (lines 248, 366).
     */
    private boolean hasPrevious;

    /**
     * No-args constructor — preserved for Spring MVC view-model binding (if
     * the REST controller layer ever needs to serialise this object directly
     * via Jackson) and for any test that needs to construct a custom
     * response shape without the factory methods. Production code paths
     * inside {@link UserListService} always invoke
     * {@link #success(List, boolean, boolean)} or {@link #failure(String)}
     * so the invariants between {@link #users}, {@link #message},
     * {@link #hasNext}, and {@link #hasPrevious} are preserved.
     */
    public UserListResponse() {
        // Intentionally empty — factory methods below populate fields.
    }

    /**
     * Builds a success response carrying the supplied page of users and
     * page-navigation flags.
     *
     * @param users       the list of {@link SecurityUser} entities from the
     *                    current page; must not be {@code null} (use an empty
     *                    list for a zero-row page). A defensive immutable
     *                    copy is taken to insulate the caller from
     *                    subsequent mutation of the source list.
     * @param hasNext     {@code true} when there is at least one record
     *                    beyond the end of the current page; derived from
     *                    {@link org.springframework.data.domain.Page#hasNext()}
     * @param hasPrevious {@code true} when there is at least one record
     *                    before the start of the current page; derived from
     *                    {@link org.springframework.data.domain.Page#hasPrevious()}
     * @return a fresh {@code UserListResponse} with {@code success = true},
     *         {@code users} = defensive copy of the supplied list,
     *         {@code message = null},
     *         {@code hasNext} and {@code hasPrevious} = supplied flags
     */
    public static UserListResponse success(List<SecurityUser> users, boolean hasNext, boolean hasPrevious) {
        UserListResponse r = new UserListResponse();
        r.success = true;
        // Defensive immutable copy — the caller may continue to mutate the
        // source list (e.g., the Spring Data Page#getContent() return value);
        // taking a copy here preserves the response's immutability contract.
        r.users = (users == null) ? Collections.emptyList() : List.copyOf(users);
        r.message = null;
        r.hasNext = hasNext;
        r.hasPrevious = hasPrevious;
        return r;
    }

    /**
     * Builds a failure response carrying the supplied reject message. The
     * {@code users} list is set to an empty immutable list (never
     * {@code null}) so callers can iterate without explicit null-checks;
     * {@code hasNext} and {@code hasPrevious} are both {@code false} because
     * no navigation is meaningful on a rejected request.
     *
     * @param message the reject message verbatim (matches
     *                {@link UserListService#MSG_NOT_AUTHORIZED} for the
     *                admin-only authorisation reject branch); must not be
     *                {@code null}
     * @return a fresh {@code UserListResponse} with {@code success = false},
     *         {@code users = []} (empty immutable list),
     *         {@code message = message}, {@code hasNext = false},
     *         {@code hasPrevious = false}
     */
    public static UserListResponse failure(String message) {
        UserListResponse r = new UserListResponse();
        r.success = false;
        r.users = Collections.emptyList();
        r.message = message;
        r.hasNext = false;
        r.hasPrevious = false;
        return r;
    }

    /**
     * @return {@code true} when the request was authorised and the repository
     *         returned a (possibly empty) page; {@code false} for the
     *         non-admin reject path.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Sets the success flag — provided for completeness; production code
     * paths use {@link #success(List, boolean, boolean)} /
     * {@link #failure(String)} factories.
     *
     * @param success the new success flag
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * @return the list of users on the current page (never {@code null} —
     *         empty for failure responses or for empty result sets).
     */
    public List<SecurityUser> getUsers() {
        return users;
    }

    /**
     * Sets the user list — provided for completeness; production code paths
     * use {@link #success(List, boolean, boolean)} factory.
     *
     * @param users the new user list
     */
    public void setUsers(List<SecurityUser> users) {
        this.users = users;
    }

    /**
     * @return the reject message when {@link #isSuccess()} is {@code false};
     *         {@code null} otherwise.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the reject message — provided for completeness; production code
     * paths use {@link #failure(String)} factory.
     *
     * @param message the new reject message
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * @return {@code true} when there is at least one record beyond the end
     *         of the current page; {@code false} on the final page or on a
     *         failure response.
     */
    public boolean isHasNext() {
        return hasNext;
    }

    /**
     * Sets the hasNext flag — provided for completeness; production code
     * paths use {@link #success(List, boolean, boolean)} factory.
     *
     * @param hasNext the new hasNext flag
     */
    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }

    /**
     * @return {@code true} when there is at least one record before the start
     *         of the current page; {@code false} on the first page or on a
     *         failure response.
     */
    public boolean isHasPrevious() {
        return hasPrevious;
    }

    /**
     * Sets the hasPrevious flag — provided for completeness; production code
     * paths use {@link #success(List, boolean, boolean)} factory.
     *
     * @param hasPrevious the new hasPrevious flag
     */
    public void setHasPrevious(boolean hasPrevious) {
        this.hasPrevious = hasPrevious;
    }
}
