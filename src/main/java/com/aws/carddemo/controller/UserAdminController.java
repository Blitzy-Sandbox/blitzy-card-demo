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
package com.aws.carddemo.controller;

import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.service.UserAddRequest;
import com.aws.carddemo.service.UserAddResult;
import com.aws.carddemo.service.UserAddService;
import com.aws.carddemo.service.UserDeleteRequest;
import com.aws.carddemo.service.UserDeleteResult;
import com.aws.carddemo.service.UserDeleteService;
import com.aws.carddemo.service.UserListRequest;
import com.aws.carddemo.service.UserListResponse;
import com.aws.carddemo.service.UserListService;
import com.aws.carddemo.service.UserUpdateRequest;
import com.aws.carddemo.service.UserUpdateResult;
import com.aws.carddemo.service.UserUpdateService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller exposing the CardDemo administrative user-CRUD endpoints — the
 * Java migration of the FOUR CICS BMS screens and FOUR COBOL programs that
 * collectively own the application-user lifecycle:
 *
 * <ul>
 *   <li>{@code app/bms/COUSR00.bms} + {@code app/cbl/COUSR00C.cbl}
 *       (TRANID {@code CU00}, 695 lines) — user list (10 rows/page, admin-only)</li>
 *   <li>{@code app/bms/COUSR01.bms} + {@code app/cbl/COUSR01C.cbl}
 *       (TRANID {@code CU01}, 299 lines) — user add (BCrypt-on-insert)</li>
 *   <li>{@code app/bms/COUSR02.bms} + {@code app/cbl/COUSR02C.cbl}
 *       (TRANID {@code CU02}, 414 lines) — user update (optimistic locking +
 *       password rehash on non-empty {@code newPassword})</li>
 *   <li>{@code app/bms/COUSR03.bms} + {@code app/cbl/COUSR03C.cbl}
 *       (TRANID {@code CU03}, 359 lines) — user delete (self-delete prevention,
 *       Java-migration addition)</li>
 * </ul>
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>All endpoints are admin-only. Non-admin authenticated callers receive
 * HTTP 403; unauthenticated callers receive HTTP 401. State-changing requests
 * ({@code POST}, {@code PUT}, {@code DELETE}) require a CSRF token; missing
 * tokens produce HTTP 403 per the Spring Security default filter chain.
 *
 * <ul>
 *   <li>{@code GET /api/users} — list users (10 rows/page); query parameters
 *       {@code page} (zero-based, default 0) and optional {@code userType}
 *       filter. Returns a {@link UserListJsonResponse} carrying a list of
 *       {@link UserSummary} rows. <strong>Per AAP §0.10.5 the response never
 *       includes the BCrypt hash field</strong> — the controller projects
 *       {@link SecurityUser} entities into the safer {@link UserSummary}
 *       record which has no {@code password} or {@code passwordHash}
 *       attribute.</li>
 *   <li>{@code POST /api/users} — add a new user. Body is a JSON-serialised
 *       {@link UserAddRequest} (carrying a plaintext {@code password}; the
 *       service hashes it via BCrypt before persisting). Returns HTTP 201
 *       on success or HTTP 400 / 409 on a service-driven validation reject
 *       (duplicate user ID maps to 409; all other validation rejects map to
 *       400). The response body is a {@link UserAddJsonResponse} that
 *       <strong>never echoes the password field</strong>.</li>
 *   <li>{@code PUT /api/users/{userId}} — update an existing user. Body is a
 *       JSON-serialised {@link UserUpdateRequest}. Returns HTTP 200 on
 *       success, 400 on validation reject, 404 on user-not-found, 409 on
 *       optimistic-lock conflict (JPA {@code @Version} mismatch — preserves
 *       the COBOL {@code COUSR02C} before-image/after-image semantics).
 *       <strong>Empty or null {@code newPassword} preserves the existing
 *       BCrypt hash unchanged</strong> — a deliberate Java-migration
 *       divergence from the COBOL plaintext-vs-plaintext comparison.</li>
 *   <li>{@code DELETE /api/users/{userId}} — delete an existing user. Returns
 *       HTTP 204 on success. Self-delete (the authenticated admin targets
 *       their own user ID) is rejected with HTTP 409 — a Java-migration
 *       hardening that has no COBOL equivalent.</li>
 * </ul>
 *
 * <h2>Authorisation</h2>
 *
 * <p>Each endpoint is annotated with
 * {@code @PreAuthorize("hasRole('ADMIN')")}; the production {@code SecurityConfig}
 * (subsequent migration step) is expected to wire {@code @EnableMethodSecurity}
 * and a {@code SecurityFilterChain} that requires authentication for all
 * {@code /api/users/**} paths. For tests the same wiring is established by an
 * inline {@code @TestConfiguration} (see {@code UserAdminControllerTest}).
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>PCI / credential containment (AAP §0.10.5).</b> The
 *       {@link SecurityUser} entity has a public {@code getPassword()}
 *       accessor that Jackson would serialise into JSON if the entity were
 *       returned directly. The controller therefore projects every entity
 *       into the {@link UserSummary} record before returning, and the
 *       response wrappers ({@link UserAddJsonResponse},
 *       {@link UserUpdateJsonResponse}, {@link UserDeleteJsonResponse}) all
 *       deliberately omit any password/hash field. Slice tests assert
 *       {@code jsonPath("$.password").doesNotExist()} and
 *       {@code jsonPath("$.passwordHash").doesNotExist()} on every response.</li>
 *   <li><b>Self-delete prevention.</b> The controller reads the authenticated
 *       principal's name via {@link Authentication#getName()} and forwards it
 *       on the {@link UserDeleteRequest} so the service can compare it
 *       against the target user ID before any database access. This is the
 *       Java-migration hardening documented in {@link UserDeleteRequest}'s
 *       "Java Migration: Self-Delete Prevention" section.</li>
 *   <li><b>Service-layer reject-message mirrors.</b> Service constants are
 *       package-private and cannot be referenced directly from the
 *       controller package. The relevant literals are duplicated here so the
 *       HTTP-status mapping can dispatch on them. The matching test
 *       {@code UserAdminControllerTest} asserts every mapping against the
 *       same literals so drift surfaces loudly.</li>
 * </ul>
 *
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 */
@RestController
@RequestMapping("/api/users")
public class UserAdminController {

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The Mxx_* constants on UserAddService, UserUpdateService, and
    // UserDeleteService are package-private (no modifier on the
    // `static final String` declarations), so they cannot be referenced from
    // a sibling package. The controller duplicates the literals here so the
    // HTTP-status mapping can dispatch on them. UserAdminControllerTest
    // asserts every mapped status against the SAME literals — drift will
    // therefore fail the controller test and the service test together,
    // surfacing the issue loudly.
    //
    // Source:
    //   com.aws.carddemo.service.UserListService.MSG_NOT_AUTHORIZED
    //   com.aws.carddemo.service.UserAddService.MSG_USER_ID_ALREADY_EXISTS
    //   com.aws.carddemo.service.UserUpdateService.MSG_USER_NOT_FOUND
    //   com.aws.carddemo.service.UserDeleteService.MSG_USER_NOT_FOUND
    //   com.aws.carddemo.service.UserDeleteService.MSG_CANNOT_DELETE_SELF
    // ------------------------------------------------------------------------

    /** UserListService reject when caller user-type is not "A"; HTTP 403. */
    static final String MSG_NOT_AUTHORIZED =
            "You are not authorized to access this menu. Try again ...";

    /** UserAddService reject when the user-ID is a duplicate primary key; HTTP 409. */
    static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    /** UserUpdateService / UserDeleteService reject when the target user is absent; HTTP 404. */
    static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** UserDeleteService reject when the operator tries to delete their own record; HTTP 409. */
    static final String MSG_CANNOT_DELETE_SELF = "Cannot delete your own user record";

    /** HTTP 409 message produced when JPA optimistic locking fires on update. */
    static final String MSG_OPTIMISTIC_LOCK_CONFLICT =
            "User record was modified by another session; please reload and retry";

    /** HTTP 500 message for unexpected service-layer exceptions; never echoes the underlying cause. */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // COBOL parity constants
    // ------------------------------------------------------------------------

    /**
     * COBOL caller user-type code for admin
     * ({@code CDEMO-USRTYP-ADMIN} per {@code COCOM01Y}).
     * The {@code @PreAuthorize} method-security gate has already verified the
     * caller is an admin before any handler method runs, so the controller
     * always populates {@link UserListRequest#setCallerUserType(String)} with
     * this constant — defence-in-depth in case the service is reached via
     * another path that bypasses method security.
     */
    static final String CALLER_USER_TYPE_ADMIN = "A";

    /**
     * Fixed page size for the list endpoint — matches the COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 10} constant (10 rows/page) preserved
     * in {@link UserListService#PAGE_SIZE}. The Java migration keeps the same
     * page size to maintain operator-experience parity.
     */
    static final int PAGE_SIZE = 10;

    // ------------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------------

    private final UserListService userListService;
    private final UserAddService userAddService;
    private final UserUpdateService userUpdateService;
    private final UserDeleteService userDeleteService;

    /**
     * Constructs the controller with constructor-injected service collaborators.
     *
     * @param userListService   the user-list service (TRANID {@code CU00})
     * @param userAddService    the user-add service (TRANID {@code CU01})
     * @param userUpdateService the user-update service (TRANID {@code CU02})
     * @param userDeleteService the user-delete service (TRANID {@code CU03})
     */
    public UserAdminController(UserListService userListService,
                               UserAddService userAddService,
                               UserUpdateService userUpdateService,
                               UserDeleteService userDeleteService) {
        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
    }

    // ========================================================================
    // GET /api/users — list users (COUSR00C / TRANID CU00)
    // ========================================================================

    /**
     * Lists application users, 10 per page. Admin-only.
     *
     * @param page           zero-based page index (default {@code 0})
     * @param userTypeFilter optional user-type filter ({@code "A"} or {@code "U"});
     *                       {@code null} means no filter (return all users)
     * @return HTTP 200 with a {@link UserListJsonResponse} carrying the page of
     *         {@link UserSummary} rows on success; HTTP 403 (mapped from the
     *         service-layer authorisation reject) if the service nonetheless
     *         rejects (defence-in-depth)
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserListJsonResponse> listUsers(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "userType", required = false) String userTypeFilter) {

        UserListRequest req = new UserListRequest();
        req.setCallerUserType(CALLER_USER_TYPE_ADMIN);
        req.setPage(page);
        req.setUserTypeFilter(userTypeFilter);

        UserListResponse result = userListService.listUsers(req);

        if (!result.isSuccess()) {
            // Defence-in-depth: @PreAuthorize already verified admin, so the
            // service should not reject — but if it does, surface as 403 (the
            // status that Spring Security uses for "authenticated but lacking
            // privilege") and echo the service's reject message in the body.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new UserListJsonResponse(
                            false,
                            List.of(),
                            page,
                            PAGE_SIZE,
                            false,
                            false,
                            result.getMessage()));
        }

        // Project the SecurityUser entities into UserSummary rows so the
        // BCrypt hash field on SecurityUser.password is NEVER serialised to
        // the HTTP response (AAP §0.10.5).
        List<UserSummary> content = result.getUsers().stream()
                .map(UserAdminController::toSummary)
                .collect(Collectors.toUnmodifiableList());

        return ResponseEntity.ok(new UserListJsonResponse(
                true,
                content,
                page,
                PAGE_SIZE,
                result.isHasNext(),
                result.isHasPrevious(),
                null));
    }

    // ========================================================================
    // POST /api/users — add a new user (COUSR01C / TRANID CU01)
    // ========================================================================

    /**
     * Adds a new application user. Admin-only. Plaintext password on the
     * request body is hashed by the service via BCrypt before persisting.
     *
     * @param request the JSON-serialised {@link UserAddRequest}
     * @return HTTP 201 on success; HTTP 400 on a validation reject (empty
     *         fields, invalid user type); HTTP 409 on duplicate user ID
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserAddJsonResponse> addUser(@RequestBody UserAddRequest request) {

        UserAddResult result = userAddService.addUser(request);

        String requestUserId = (request == null) ? null : request.getUserId();
        String requestUserType = (request == null) ? null : request.getUserType();

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new UserAddJsonResponse(
                            true,
                            requestUserId,
                            requestUserType,
                            result.getMessage()));
        }

        // Failure path — map the COBOL-equivalent reject message to HTTP status.
        String msg = result.getMessage();
        HttpStatus status;
        if (MSG_USER_ID_ALREADY_EXISTS.equals(msg)) {
            // COBOL COUSR01C: WRITE-USER-SEC-FILE → DFHRESP(DUPKEY) →
            // 'User ID already exist...'. Maps to HTTP 409 Conflict.
            status = HttpStatus.CONFLICT;
        } else {
            // Empty firstName / lastName / userId / password / userType, or
            // invalid userType — all surface as HTTP 400.
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(new UserAddJsonResponse(
                        false,
                        requestUserId,
                        requestUserType,
                        msg));
    }

    // ========================================================================
    // PUT /api/users/{userId} — update an existing user (COUSR02C / TRANID CU02)
    // ========================================================================

    /**
     * Updates an existing application user. Admin-only.
     *
     * <p>Empty or {@code null} {@code newPassword} on the request body
     * preserves the existing BCrypt hash unchanged (deliberate
     * Java-migration divergence — see
     * {@link UserUpdateRequest}'s "Java Migration: newPassword Semantics"
     * section).
     *
     * <p>The path variable {@code userId} is authoritative; if the request
     * body carries a different {@code userId} the path value overrides so
     * the service sees a consistent view.
     *
     * @param userId  the path-variable user ID
     * @param request the JSON-serialised {@link UserUpdateRequest}
     * @return HTTP 200 on success; HTTP 400 on a validation reject;
     *         HTTP 404 on user-not-found; HTTP 409 on optimistic-lock
     *         conflict (JPA {@code @Version} mismatch — preserves the COBOL
     *         {@code COUSR02C} before-image/after-image semantics)
     */
    @PutMapping(path = "/{userId}",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserUpdateJsonResponse> updateUser(
            @PathVariable("userId") String userId,
            @RequestBody UserUpdateRequest request) {

        // Honour the path variable as the authoritative target — protects
        // against a client that posts a body whose `userId` differs from the
        // URL. The mutable setter is exactly the field-binding contract
        // documented on UserUpdateRequest.
        if (request != null) {
            request.setUserId(userId);
        }

        try {
            UserUpdateResult result = userUpdateService.updateUser(request);

            Long requestVersion = (request == null) ? null : request.getVersion();

            if (result.isSuccess()) {
                return ResponseEntity.ok(new UserUpdateJsonResponse(
                        true,
                        userId,
                        requestVersion,
                        result.getMessage()));
            }

            // Failure path — map the reject message to HTTP status.
            String msg = result.getMessage();
            HttpStatus status;
            if (MSG_USER_NOT_FOUND.equals(msg)) {
                // COBOL COUSR02C: READ-USER-SEC-FILE → NOTFND →
                // 'User ID NOT found...'. Maps to HTTP 404 Not Found.
                status = HttpStatus.NOT_FOUND;
            } else {
                // Empty fields or invalid userType — HTTP 400.
                status = HttpStatus.BAD_REQUEST;
            }
            return ResponseEntity.status(status)
                    .body(new UserUpdateJsonResponse(
                            false,
                            userId,
                            requestVersion,
                            msg));
        } catch (OptimisticLockingFailureException ex) {
            // JPA @Version mismatch — another session updated the row between
            // our load and our save. Preserves the COBOL COUSR02C
            // before-image / after-image comparison semantics, which on the
            // mainframe surfaced via the {@code REWRITE} returning a record
            // not found / out of sequence after a successful {@code READ FOR
            // UPDATE}. Maps to HTTP 409 Conflict per REST convention.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new UserUpdateJsonResponse(
                            false,
                            userId,
                            (request == null) ? null : request.getVersion(),
                            MSG_OPTIMISTIC_LOCK_CONFLICT));
        }
    }

    // ========================================================================
    // DELETE /api/users/{userId} — delete a user (COUSR03C / TRANID CU03)
    // ========================================================================

    /**
     * Deletes an application user. Admin-only. Refuses self-delete.
     *
     * <p>The authenticated principal's name (via
     * {@link Authentication#getName()}) is propagated onto the
     * {@link UserDeleteRequest} so the service can enforce the self-delete
     * guard before any database access — see {@link UserDeleteRequest}'s
     * "Java Migration: Self-Delete Prevention" section.
     *
     * @param userId        the path-variable target user ID
     * @param authentication the Spring Security {@link Authentication}
     *                       carrying the operator's identity; never
     *                       {@code null} in a properly-secured request
     *                       because {@code @PreAuthorize} fires first
     * @return HTTP 204 (No Content) on success; HTTP 400 on empty input
     *         (defensive); HTTP 404 on user-not-found; HTTP 409 on
     *         self-delete reject
     */
    @DeleteMapping(path = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserDeleteJsonResponse> deleteUser(
            @PathVariable("userId") String userId,
            Authentication authentication) {

        // Resolve operator's identity from the SecurityContext. The
        // @PreAuthorize gate has already verified the caller is authenticated
        // and holds ROLE_ADMIN, so `authentication` is non-null in practice.
        // The defensive null-guard documents the invariant and prevents an
        // NPE if a misconfigured filter chain ever forwards an anonymous
        // request to this method.
        String currentUserId = (authentication == null) ? "" : authentication.getName();

        UserDeleteRequest req = new UserDeleteRequest(userId, currentUserId);
        UserDeleteResult result = userDeleteService.deleteUser(req);

        if (result.isSuccess()) {
            // REST convention for a successful DELETE is HTTP 204 No Content
            // (no body). The {@code COUSR03C} mainframe equivalent emitted a
            // 'User <id> has been deleted ...' success message to the BMS
            // map; in the REST migration the success message is implicit in
            // the 2xx status code so the response carries no body.
            return ResponseEntity.noContent().build();
        }

        // Failure path — map the reject message to HTTP status.
        String msg = result.getMessage();
        HttpStatus status;
        if (MSG_CANNOT_DELETE_SELF.equals(msg)) {
            // Java-migration self-delete prevention. Maps to HTTP 409
            // Conflict — semantically "the resource state forbids this
            // operation" rather than "the request is malformed" (HTTP 400)
            // or "the target does not exist" (HTTP 404).
            status = HttpStatus.CONFLICT;
        } else if (MSG_USER_NOT_FOUND.equals(msg)) {
            // COBOL COUSR03C: READ-USER-SEC-FILE → NOTFND →
            // 'User ID NOT found...'. Maps to HTTP 404 Not Found.
            status = HttpStatus.NOT_FOUND;
        } else {
            // Empty userId — HTTP 400.
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(new UserDeleteJsonResponse(false, userId, msg));
    }

    // ========================================================================
    // @ExceptionHandler — unexpected service failures
    // ========================================================================

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer (e.g., a {@code DataAccessException} when the {@code USRSEC}
     * table is unreachable). Returns HTTP 500 with a sanitised body — the
     * underlying exception detail must never leak into the HTTP response
     * (AAP §0.10.5 applied to error paths).
     *
     * <p>{@link OptimisticLockingFailureException} is handled explicitly in
     * {@link #updateUser(String, UserUpdateRequest)} (mapped to HTTP 409) so
     * it never reaches this generic handler. {@link AccessDeniedException}
     * (and its Spring Security 6.1+ subtype {@code AuthorizationDeniedException}
     * thrown by {@code @PreAuthorize}) is RE-THROWN so Spring Security's
     * {@code ExceptionTranslationFilter} can map it to HTTP 403 with the
     * conventional access-denied semantics — otherwise this handler would
     * incorrectly swallow the security exception and return HTTP 500.
     * All other {@link RuntimeException}s fall through to the generic 500.
     *
     * @param ex the caught exception; not echoed in the response (except
     *           when re-thrown for Spring Security's filter to handle).
     *           Production deployments would log this exception via an
     *           injected {@code Logger}.
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorJsonResponse> handleServiceFailure(RuntimeException ex) {
        // Re-throw security exceptions so Spring Security's filter chain can
        // produce the appropriate 403 response. AccessDeniedException covers
        // both the legacy class and the Spring Security 6.1+
        // AuthorizationDeniedException (which extends AccessDeniedException).
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        // Defensive null-guard documents that this handler does NOT
        // dereference ex.getMessage() / ex.toString() — preventing accidental
        // disclosure of database error strings, stack-trace fragments, or
        // internal class names in the HTTP response body.
        if (ex == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
    }

    // ========================================================================
    // Private mapping helpers
    // ========================================================================

    /**
     * Projects a {@link SecurityUser} entity into a {@link UserSummary}
     * record. The projection deliberately drops the BCrypt hash field so the
     * controller response never carries a credential (AAP §0.10.5).
     *
     * @param user the entity loaded by the repository; never {@code null}
     * @return a fresh {@link UserSummary} carrying only the safe-to-expose
     *         fields ({@code userId}, {@code firstName}, {@code lastName},
     *         {@code userType}, {@code version})
     */
    private static UserSummary toSummary(SecurityUser user) {
        return new UserSummary(
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType(),
                user.getVersion());
    }

    // ========================================================================
    // Response DTOs — inner records (no password/passwordHash fields, ever)
    // ========================================================================

    /**
     * Safe-to-expose projection of a {@link SecurityUser} entity. Deliberately
     * has NO {@code password} or {@code passwordHash} field; the BCrypt hash
     * stored on the entity is never serialised in any HTTP response (AAP
     * §0.10.5 "No plaintext credentials in any configuration file" — applied
     * defensively to credential hashes as well).
     *
     * @param userId    the 8-character user identifier (COBOL {@code SEC-USR-ID PIC X(08)})
     * @param firstName the 20-character first name (COBOL {@code SEC-USR-FNAME PIC X(20)})
     * @param lastName  the 20-character last name (COBOL {@code SEC-USR-LNAME PIC X(20)})
     * @param userType  the 1-character user type (COBOL {@code SEC-USR-TYPE PIC X(01)}):
     *                  {@code "U"} or {@code "A"}
     * @param version   the JPA {@code @Version} counter (Java-migration addition;
     *                  used by the update endpoint to detect concurrent edits)
     */
    public static record UserSummary(
            String userId,
            String firstName,
            String lastName,
            String userType,
            Long version) {
    }

    /**
     * Wire-format response for {@code GET /api/users}. Carries the paged list
     * of {@link UserSummary} rows plus the page-navigation flags.
     *
     * @param success     {@code true} on a happy path; {@code false} on the
     *                    defence-in-depth service-authorisation reject branch
     * @param content     the page of user summaries (empty list on failure)
     * @param pageNumber  the zero-based page index that was requested
     * @param pageSize    the page size (always {@link #PAGE_SIZE} = 10)
     * @param hasNext     {@code true} when there is at least one record
     *                    beyond the end of the current page
     * @param hasPrevious {@code true} when there is at least one record
     *                    before the start of the current page
     * @param message     reject message when {@code success = false};
     *                    {@code null} on success
     */
    public static record UserListJsonResponse(
            boolean success,
            List<UserSummary> content,
            int pageNumber,
            int pageSize,
            boolean hasNext,
            boolean hasPrevious,
            String message) {
    }

    /**
     * Wire-format response for {@code POST /api/users}. Reflects the user
     * identifier and user type from the request (so the client can confirm
     * the persisted entity) plus the service's success or reject message.
     *
     * @param success  {@code true} on a happy path; {@code false} on a reject
     * @param userId   the user-ID that was added or attempted (echoed from request)
     * @param userType the user-type that was added or attempted (echoed from request)
     * @param message  the service's success or reject message
     */
    public static record UserAddJsonResponse(
            boolean success,
            String userId,
            String userType,
            String message) {
    }

    /**
     * Wire-format response for {@code PUT /api/users/{userId}}. Reflects the
     * user identifier and the version counter from the request plus the
     * service's success or reject message.
     *
     * @param success {@code true} on a happy path; {@code false} on a reject
     * @param userId  the user-ID that was updated (path-variable value)
     * @param version the JPA {@code @Version} counter from the request body
     *                (echoed for client diagnostics — the persisted value
     *                will be one higher on the next read)
     * @param message the service's success or reject message
     */
    public static record UserUpdateJsonResponse(
            boolean success,
            String userId,
            Long version,
            String message) {
    }

    /**
     * Wire-format response for {@code DELETE /api/users/{userId}}. Carried
     * only on the failure path; the success path returns HTTP 204 No Content
     * with no body.
     *
     * @param success {@code true} on a happy path; {@code false} on a reject
     * @param userId  the user-ID that was targeted (path-variable value)
     * @param message the service's reject message
     */
    public static record UserDeleteJsonResponse(
            boolean success,
            String userId,
            String message) {
    }

    /**
     * Wire-format response for unexpected service-layer failures. Returned by
     * the {@link #handleServiceFailure(RuntimeException)} exception handler.
     *
     * @param success always {@code false}
     * @param message a sanitised generic message ({@link #MSG_INTERNAL_ERROR});
     *                NEVER includes the underlying exception's message or
     *                stack-trace fragment
     */
    public static record ErrorJsonResponse(boolean success, String message) {
    }
}
