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
 * Result DTO for {@link UserDeleteService#deleteUser(UserDeleteRequest)} — the Java
 * replacement for the {@code COUSR3AO} BMS-mapped output record's {@code ERRMSGO}
 * message field emitted by {@code app/cbl/COUSR03C.cbl} (TRANID {@code CU03}, the
 * admin-only user-delete dispatcher). Encodes either a <em>success</em> outcome
 * carrying the {@code 'User {id} has been deleted ...'} confirmation, or a
 * <em>failure</em> outcome carrying one of the four COBOL-equivalent reject messages.
 *
 * <h2>COBOL Provenance — COUSR03C.cbl</h2>
 *
 * <p>The COBOL workflow writes one of the following messages to
 * {@code ERRMSGO OF COUSR3AO} via {@code SEND-USRDEL-SCREEN} (lines 213–225)
 * depending on the outcome of {@code DELETE-USER-INFO} (lines 174–192):
 *
 * <ul>
 *   <li><b>Success</b> — {@code DELETE-USER-SEC-FILE} returns
 *       {@code DFHRESP(NORMAL)} (line 314):
 *       {@code STRING 'User ' SEC-USR-ID ' has been deleted ...'}
 *       (lines 318–321). The {@link #success(String)} factory returns a
 *       success outcome carrying that exact message.</li>
 *   <li><b>Empty user ID</b> — {@code USRIDINI = SPACES OR LOW-VALUES} in
 *       {@code DELETE-USER-INFO} (lines 177–182): {@code 'User ID can NOT
 *       be empty...'}.</li>
 *   <li><b>User not found</b> — {@code READ-USER-SEC-FILE} or
 *       {@code DELETE-USER-SEC-FILE} returns {@code DFHRESP(NOTFND)}
 *       (lines 287–292 / 323–328): {@code 'User ID NOT found...'}. The
 *       Java migration collapses both branches into a single reject path
 *       because the JPA repository's {@code Optional.empty()} response is
 *       identical regardless of whether the row was missing at lookup
 *       time or had been concurrently deleted (the COBOL "rare race"
 *       semantic).</li>
 *   <li><b>Self-delete</b> — Java-migration addition (no COBOL equivalent
 *       — see {@link UserDeleteRequest} class-level note for rationale).
 *       The {@link UserDeleteService} rejects with the self-delete guard
 *       message before any database access.</li>
 * </ul>
 *
 * <p>I/O errors (the COBOL {@code WHEN OTHER} branches at lines 293–299 and
 * 329–335) surface in the Java migration as
 * {@link org.springframework.dao.DataAccessException} subclasses propagated
 * from the repository; the service does not catch them, letting the
 * controller layer's exception-handler chain produce the Java equivalent of
 * the COBOL {@code 'Unable to lookup User...'} / {@code 'Unable to Update
 * User...'} response. This mirrors the convention used by
 * {@link TransactionDetailService} and {@link UserListService}.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied {@code 'User {id} has been deleted ...'} message.</li>
 *   <li>{@link #failure(String)} returns {@code success = false} with one
 *       of the four COBOL-equivalent reject messages.</li>
 * </ul>
 *
 * <p>The constructor is private; no production or test code instantiates
 * this class directly. The {@code success} and {@code message} fields are
 * {@code final} (set once at construction) so the result is effectively
 * immutable after the factory returns it.
 *
 * @see UserDeleteService
 * @see UserDeleteRequest
 */
public final class UserDeleteResult {

    /**
     * Whether the delete operation succeeded ({@code true}) or was rejected
     * ({@code false}). Drives the controller's HTTP status code mapping:
     * success → 200 OK; failure → 400 Bad Request (with the rejection message
     * in the response body) for empty / self-delete / not-found scenarios.
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
    private UserDeleteResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by {@link UserDeleteService#deleteUser(UserDeleteRequest)}
     * after {@code DELETE-USER-SEC-FILE} returns {@code DFHRESP(NORMAL)};
     * the canonical message format is {@code 'User {id} has been deleted ...'}
     * (mirroring the COBOL {@code STRING} construct at lines 318–321).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static UserDeleteResult success(String message) {
        return new UserDeleteResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link UserDeleteService#deleteUser(UserDeleteRequest)}
     * for all four reject branches (empty user ID, self-delete, user not
     * found, and rare-race not-found on delete).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link UserDeleteService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static UserDeleteResult failure(String message) {
        return new UserDeleteResult(false, message);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any of
     *         the four reject outcomes (empty user ID, self-delete, user not
     *         found, rare-race not-found on delete)
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
