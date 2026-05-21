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
package com.aws.carddemo.repository;

import com.aws.carddemo.entity.SecurityUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link SecurityUser} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}
 * from {@code app/cbl/COSGN00C.cbl} {@code READ-USER-SEC-FILE} paragraph and
 * the STARTBR / READNEXT loop in {@code app/cbl/COUSR00C.cbl}
 * {@code PROCESS-PAGE-FORWARD} paragraph.
 *
 * <h2>COBOL Provenance — Single-Key Read</h2>
 *
 * <p>The original COBOL {@code READ-USER-SEC-FILE} paragraph (COSGN00C.cbl
 * lines 209–257) consults the {@code USRSEC} VSAM KSDS file by the 8-character
 * {@code SEC-USR-ID} primary key. The CICS response code {@code WS-RESP-CD}
 * carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = 0} (DFHRESP NORMAL) — record found, dispatch authentication</li>
 *   <li>{@code WS-RESP-CD = 13} (DFHRESP NOTFND) — user not found, reject sign-on</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error, fail with "Unable to verify..."</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: it returns
 * {@code Optional.of(user)} for the NORMAL case and {@code Optional.empty()} for
 * the NOTFND case. I/O errors surface as
 * {@link org.springframework.dao.DataAccessException} subclasses and propagate
 * up to the caller (the production
 * {@link com.aws.carddemo.service.AuthenticationService} translates them into
 * the "Unable to verify..." reject message in production code).
 *
 * <h2>COBOL Provenance — Paged Browse (COUSR00C.cbl)</h2>
 *
 * <p>The user-list dispatcher {@code COUSR00C.cbl} (TRANID {@code CU00}) walks
 * the {@code USRSEC} KSDS via {@code STARTBR} + {@code READNEXT} loops:
 *
 * <ul>
 *   <li>{@code STARTBR-USER-SEC-FILE} establishes a browse position at the
 *       requested starting key (line 284, called from
 *       {@code PROCESS-PAGE-FORWARD}).</li>
 *   <li>{@code READNEXT-USER-SEC-FILE} reads up to 10 records into the
 *       {@code USER-REC OCCURS 10 TIMES} table (line 293, the
 *       {@code WS-MAX-SCREEN-LINES VALUE 10} loop bound).</li>
 *   <li>{@code ENDBR-USER-SEC-FILE} closes the browse position (line 325).</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is the inherited
 * {@link #findAll(Pageable)} (for the unfiltered browse) and the custom
 * {@link #findByUserType(String, Pageable)} (for the user-type-filtered
 * browse). Both honour the {@link Pageable#getPageSize()} parameter so the
 * production service ({@link com.aws.carddemo.service.UserListService}) can
 * enforce the COBOL 10-rows-per-page contract.
 *
 * <h2>Custom Query — findByUserType</h2>
 *
 * <p>{@link #findByUserType(String, Pageable)} is a Spring Data derived query
 * method: Spring Data parses the method name {@code findBy + UserType} and
 * generates the equivalent of
 * {@code SELECT u FROM SecurityUser u WHERE u.userType = :userType}
 * with pagination support. No explicit {@code @Query} annotation is required.
 *
 * <p>This method backs the user-type filter feature of
 * {@link com.aws.carddemo.service.UserListService#listUsers(com.aws.carddemo.service.UserListRequest)}.
 * When the request carries a non-{@code null} / non-empty
 * {@code userTypeFilter}, the service routes the query to this method
 * instead of {@link #findAll(Pageable)}; the COBOL equivalent would be a
 * {@code STARTBR} keyed on the user-type prefix combined with skip-on-mismatch
 * filtering, but the Spring Data idiom replaces that with a single derived
 * query.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong> created
 * to satisfy {@link com.aws.carddemo.service.AuthenticationService} and
 * {@link com.aws.carddemo.service.UserListService} compilation and the
 * authentication / user-list test suites. Subsequent migration agents
 * (REFACTOR flavor) will add a {@code @Repository} stereotype annotation (if
 * the project policy requires explicit stereotype marking — Spring Data infers
 * the bean from the {@code JpaRepository} extension and a stereotype is not
 * strictly necessary) and additional custom-query methods as the
 * {@code COUSR01C} (add), {@code COUSR02C} (update), and {@code COUSR03C}
 * (delete) migrations land.
 *
 * @see com.aws.carddemo.service.AuthenticationService
 * @see com.aws.carddemo.service.UserListService
 * @see SecurityUser
 */
public interface UserSecurityRepository extends JpaRepository<SecurityUser, String> {

    /**
     * Returns a paged result of {@link SecurityUser} entities matching the
     * supplied {@code userType} code. Spring Data derives the query from the
     * method name: the generated implementation is equivalent to
     * {@code SELECT u FROM SecurityUser u WHERE u.userType = :userType} with
     * pagination applied via the {@link Pageable} parameter.
     *
     * <p>This method backs the user-type filter feature of
     * {@link com.aws.carddemo.service.UserListService#listUsers(com.aws.carddemo.service.UserListRequest)}.
     * The Java migration uses this query in place of the COBOL
     * {@code STARTBR / READNEXT} loop with prefix-filtering implied in the
     * {@code COUSR00C.cbl} {@code USRIDINI OF COUSR0AI} starting-browse key
     * (although the COBOL workflow does not perform an explicit user-type
     * filter — it uses the user-ID prefix; the Java migration repurposes the
     * field for declarative user-type filtering, which is the natural
     * Spring Data equivalent).
     *
     * <p>Unknown user-type values (anything other than {@code "A"} or
     * {@code "U"}) yield an empty {@link Page}; the production service
     * surfaces that as a successful response with an empty user list.
     *
     * @param userType the {@code SEC-USR-TYPE PIC X(01)} value to filter on —
     *                 {@code "A"} for admins, {@code "U"} for regular users.
     *                 Must not be {@code null} (the production service guards
     *                 against {@code null} before calling this method).
     * @param pageable the page request carrying the zero-based page index and
     *                 the page size (fixed at 10 in the production service
     *                 per the COBOL {@code WS-MAX-SCREEN-LINES VALUE 10}).
     * @return a {@link Page} containing zero or more matching
     *         {@link SecurityUser} entities plus the page-navigation
     *         metadata ({@link Page#hasNext()} / {@link Page#hasPrevious()})
     *         that the production service forwards to
     *         {@link com.aws.carddemo.service.UserListResponse}.
     */
    Page<SecurityUser> findByUserType(String userType, Pageable pageable);
}
