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
 * Result DTO for {@link UserAddService#addUser(UserAddRequest)} — the Java
 * replacement for the {@code COUSR1AO} BMS-mapped output record's
 * {@code ERRMSGO} message field emitted by {@code app/cbl/COUSR01C.cbl}
 * (TRANID {@code CU01}, the admin-only user-add dispatcher). Encodes either a
 * <em>success</em> outcome carrying the
 * {@code 'User {id} has been added ...'} confirmation, or a <em>failure</em>
 * outcome carrying one of the seven COBOL-equivalent reject messages.
 *
 * <h2>COBOL Provenance — COUSR01C.cbl</h2>
 *
 * <p>The COBOL workflow writes one of the following messages to
 * {@code ERRMSGO OF COUSR1AO} via {@code SEND-USRADD-SCREEN} (lines 184–196)
 * depending on the outcome of {@code PROCESS-ENTER-KEY} (lines 115–160) plus
 * {@code WRITE-USER-SEC-FILE} (lines 238–274):
 *
 * <ul>
 *   <li><b>Success</b> — {@code WRITE-USER-SEC-FILE} returns
 *       {@code DFHRESP(NORMAL)} (line 251):
 *       {@code STRING 'User ' SEC-USR-ID ' has been added ...'}
 *       (lines 255–258). The {@link #success(String)} factory returns a
 *       success outcome carrying that exact message.</li>
 *   <li><b>Duplicate user ID</b> — {@code WRITE-USER-SEC-FILE} returns
 *       {@code DFHRESP(DUPKEY)} or {@code DFHRESP(DUPREC)} (lines 260–266):
 *       {@code 'User ID already exist...'}. The Java migration short-circuits
 *       this via a pre-write {@code findById} so the {@code Optional.of(existing)}
 *       branch yields this reject before attempting any save.</li>
 *   <li><b>First name empty</b> — {@code FNAMEI = SPACES OR LOW-VALUES} in
 *       {@code PROCESS-ENTER-KEY} (line 118): {@code 'First Name can NOT
 *       be empty...'}.</li>
 *   <li><b>Last name empty</b> — {@code LNAMEI = SPACES OR LOW-VALUES} in
 *       {@code PROCESS-ENTER-KEY} (line 124): {@code 'Last Name can NOT be
 *       empty...'}.</li>
 *   <li><b>User ID empty</b> — {@code USERIDI = SPACES OR LOW-VALUES} in
 *       {@code PROCESS-ENTER-KEY} (line 130): {@code 'User ID can NOT be
 *       empty...'}.</li>
 *   <li><b>Password empty</b> — {@code PASSWDI = SPACES OR LOW-VALUES} in
 *       {@code PROCESS-ENTER-KEY} (line 136): {@code 'Password can NOT be
 *       empty...'}.</li>
 *   <li><b>User type empty</b> — {@code USRTYPEI = SPACES OR LOW-VALUES} in
 *       {@code PROCESS-ENTER-KEY} (line 142): {@code 'User Type can NOT be
 *       empty...'}.</li>
 *   <li><b>Invalid user type</b> — Java-migration addition (no COBOL
 *       equivalent — see {@link UserAddService} class-level note for
 *       rationale). Rejects when {@code userType} is not {@code "U"} or
 *       {@code "A"}.</li>
 * </ul>
 *
 * <p>I/O errors (the COBOL {@code WHEN OTHER} branch at lines 267–273
 * {@code 'Unable to Add User...'}) surface in the Java migration as
 * {@link org.springframework.dao.DataAccessException} subclasses propagated
 * from the repository; the service does not catch them, letting the
 * controller layer's exception-handler chain produce the Java equivalent of
 * the COBOL {@code 'Unable to Add User...'} response.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied {@code 'User {id} has been added ...'} message.</li>
 *   <li>{@link #failure(String)} returns {@code success = false} with one
 *       of the seven COBOL-equivalent (or Java-migration-added) reject
 *       messages.</li>
 * </ul>
 *
 * <p>The constructor is private; no production or test code instantiates
 * this class directly. The {@code success} and {@code message} fields are
 * {@code final} (set once at construction) so the result is effectively
 * immutable after the factory returns it.
 *
 * @see UserAddService
 * @see UserAddRequest
 */
public final class UserAddResult {

    /**
     * Whether the add operation succeeded ({@code true}) or was rejected
     * ({@code false}). Drives the controller's HTTP status code mapping:
     * success → 201 Created; validation failure → 400 Bad Request; duplicate
     * → 409 Conflict (with the rejection message in the response body).
     */
    private final boolean success;

    /**
     * Human-readable outcome message — one of the COBOL-equivalent strings
     * enumerated in the class-level COBOL Provenance section. Never
     * {@code null} (both factory methods require a non-{@code null} message).
     */
    private final String message;

    /**
     * Private constructor enforcing the factory-method-only construction
     * contract. Both fields are populated exactly once.
     *
     * @param success outcome flag
     * @param message outcome message
     */
    private UserAddResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by {@link UserAddService#addUser(UserAddRequest)} after
     * {@code WRITE-USER-SEC-FILE} returns {@code DFHRESP(NORMAL)}; the
     * canonical message format is {@code 'User {id} has been added ...'}
     * (mirroring the COBOL {@code STRING} construct at lines 255–258).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static UserAddResult success(String message) {
        return new UserAddResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link UserAddService#addUser(UserAddRequest)} for all
     * seven reject branches (first-name empty, last-name empty, user-id
     * empty, password empty, user-type empty, invalid user type, and
     * duplicate user id).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link UserAddService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static UserAddResult failure(String message) {
        return new UserAddResult(false, message);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any of
     *         the seven reject outcomes (first-name empty, last-name empty,
     *         user-id empty, password empty, user-type empty, invalid user
     *         type, duplicate user id)
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return the human-readable outcome message (success confirmation or
     *         rejection reason); never {@code null}
     */
    public String getMessage() {
        return message;
    }
}
