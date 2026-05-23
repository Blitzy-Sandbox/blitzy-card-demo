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
 * Immutable request DTO for {@link UserDeleteService#deleteUser(UserDeleteRequest)} —
 * the Java replacement for the {@code COUSR3AI} BMS-mapped input record carrying the
 * target user identifier and the operator's session identity from
 * {@code app/bms/COUSR03.bms} as read by {@code app/cbl/COUSR03C.cbl} (TRANID
 * {@code CU03}, the admin-only user-delete dispatcher).
 *
 * <h2>COBOL Provenance — COUSR03C.cbl</h2>
 *
 * <p>The COBOL {@code RECEIVE-USRDEL-SCREEN} paragraph (lines 230–238) populates the
 * {@code COUSR3AI} input map with the operator's keystroke. The {@code DELETE-USER-INFO}
 * paragraph (lines 174–192) then consults two pieces of state to perform the deletion:
 *
 * <ul>
 *   <li>{@code USRIDINI OF COUSR3AI PIC X(08)} — the target user identifier
 *       (line 189: {@code MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID}) keyed against
 *       the {@code USRSEC} VSAM file via {@code READ-USER-SEC-FILE} (line 190)
 *       and removed via {@code DELETE-USER-SEC-FILE} (line 191). This is the
 *       {@link #userId} field on this DTO.</li>
 *   <li>The operator's identity carried in {@code CARDDEMO-COMMAREA}
 *       ({@code COCOM01Y} copybook). The COBOL source does not consult this
 *       value during the delete workflow itself (the admin gate is performed
 *       upstream by {@code COADM01C}), but the Java migration carries it on
 *       this DTO so the service can enforce the self-delete guard (see
 *       <em>Java Migration: Self-Delete Prevention</em> below). This is the
 *       {@link #currentUserId} field on this DTO.</li>
 * </ul>
 *
 * <h2>Java Migration: Self-Delete Prevention</h2>
 *
 * <p>The COBOL original does not explicitly check for self-delete (an admin
 * deleting their own account) because the operational risk in the mainframe
 * environment was mitigated by separate physical sign-on and operations
 * controls. The Java migration tightens this implicit contract by surfacing
 * the operator's identity ({@link #currentUserId}) on the request DTO so the
 * production {@link UserDeleteService} can reject self-delete attempts before
 * any database access. This is a documented Java-migration addition (AAP
 * §0.10.2: "All deviations from literal COBOL logic must be documented with
 * the original COBOL paragraph name and reason for divergence"). Reason: the
 * REST controller layer is reachable independently of the admin menu flow, so
 * defence-in-depth requires the service to enforce the self-delete guard
 * directly.
 *
 * <h2>Immutability</h2>
 *
 * <p>Both fields are {@code final} and populated only via the two-argument
 * constructor. There are no setters because the request is conceptually a
 * value object — once the controller layer constructs it, the service treats
 * it as read-only. Per AAP §0.10.10 style consistency: this matches the
 * positional-constructor convention used by every other request DTO in this
 * package that carries a small, fixed set of fields.
 *
 * <h2>No Validation</h2>
 *
 * <p>This class deliberately performs no field validation in the constructor.
 * Per the established convention used by {@link AdminMenuRequest} /
 * {@link MainMenuRequest} / {@link UserListRequest}, validation of the
 * payload (empty {@code userId}, self-delete) is performed by the
 * {@link UserDeleteService} so the reject paths emit the COBOL-equivalent
 * reject messages rather than {@link IllegalArgumentException}. Carrying
 * validation in the service also keeps it visible to the test suite and
 * countable for JaCoCo coverage purposes (AAP §0.7.1).
 *
 * @see UserDeleteService
 * @see UserDeleteResult
 */
public final class UserDeleteRequest {

    /**
     * Target user identifier — the 8-character {@code SEC-USR-ID} primary key
     * of the record to delete from the {@code USRSEC} VSAM file replacement
     * (PostgreSQL {@code security_users} table). Originates from
     * {@code USRIDINI OF COUSR3AI PIC X(08)} in the COBOL {@code COUSR03C}
     * dispatcher. May be {@code null} or empty when the operator presses
     * PF5 without entering a user ID; the service interprets that as the
     * {@code 'User ID can NOT be empty...'} reject.
     */
    private final String userId;

    /**
     * Operator's authenticated user identifier — the {@code SEC-USR-ID} of
     * the admin user currently signed in. Used solely by the self-delete
     * guard (Java-migration addition; no COBOL equivalent). Should match
     * the {@code SEC-USR-ID} that the authentication service produced at
     * sign-on time; the controller layer is responsible for populating
     * this from the authenticated session.
     */
    private final String currentUserId;

    /**
     * Constructs an immutable {@code UserDeleteRequest}.
     *
     * @param userId        the 8-character target user identifier to delete from
     *                      {@code USRSEC}. {@code null} or empty values produce the
     *                      {@code 'User ID can NOT be empty...'} reject; non-empty
     *                      values that do not match an existing record produce the
     *                      {@code 'User ID NOT found...'} reject.
     * @param currentUserId the 8-character user identifier of the authenticated
     *                      operator. When equal to {@code userId} (case-sensitive),
     *                      the service rejects the request with the self-delete
     *                      guard message.
     */
    public UserDeleteRequest(String userId, String currentUserId) {
        this.userId = userId;
        this.currentUserId = currentUserId;
    }

    /**
     * @return the 8-character target user identifier; may be {@code null} or
     *         empty (the service interprets either as the
     *         {@code 'User ID can NOT be empty...'} reject).
     */
    public String getUserId() {
        return userId;
    }

    /**
     * @return the 8-character authenticated operator identifier carried from
     *         the controller's session context (Java-migration addition; used
     *         solely by the self-delete guard).
     */
    public String getCurrentUserId() {
        return currentUserId;
    }
}
