/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;

/**
 * Spring MVC REST controller providing CRUD operations for user security
 * management, replacing CICS BMS screens COUSR00 through COUSR03 from the
 * CardDemo mainframe application.
 *
 * <p>This controller is a thin delegation layer — all business logic, field
 * validation, BCrypt password hashing, duplicate detection, change detection,
 * and persistence operations reside in the four admin service classes. The
 * controller is responsible only for HTTP binding, request logging, and
 * response wrapping.</p>
 *
 * <p>Admin-only access is enforced by Spring Security via
 * {@code SecurityConfig.java} — this controller does NOT check roles directly.
 * All exception handling is delegated to the global {@code @ControllerAdvice}
 * handler configured in {@code WebConfig}.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>BMS Screen to REST endpoint mapping</caption>
 *   <tr><th>BMS Screen</th><th>COBOL Program</th><th>REST Endpoint</th><th>HTTP Method</th></tr>
 *   <tr><td>COUSR00 (User List)</td><td>COUSR00C.cbl (695 lines)</td>
 *       <td>/api/admin/users</td><td>GET</td></tr>
 *   <tr><td>COUSR01 (User Add)</td><td>COUSR01C.cbl (299 lines)</td>
 *       <td>/api/admin/users</td><td>POST</td></tr>
 *   <tr><td>COUSR02 (User Update)</td><td>COUSR02C.cbl (414 lines)</td>
 *       <td>/api/admin/users/{id}</td><td>GET + PUT</td></tr>
 *   <tr><td>COUSR03 (User Delete)</td><td>COUSR03C.cbl (359 lines)</td>
 *       <td>/api/admin/users/{id}</td><td>GET + DELETE</td></tr>
 * </table>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Successful user list, single user retrieval,
 *       or user update</li>
 *   <li><strong>201 Created</strong> — Successful user creation</li>
 *   <li><strong>204 No Content</strong> — Successful user deletion</li>
 *   <li><strong>400 Bad Request</strong> — Validation failures (empty fields,
 *       no-change detection) — handled by ControllerAdvice</li>
 *   <li><strong>404 Not Found</strong> — User ID not found during get, update,
 *       or delete — handled by ControllerAdvice</li>
 *   <li><strong>409 Conflict</strong> — Duplicate user ID during creation
 *       — handled by ControllerAdvice</li>
 * </ul>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>No exception handling in controller — all exceptions propagate to the
 *       global {@code @ControllerAdvice} handler for consistent error response
 *       formatting.</li>
 *   <li>No business logic in controller — field validation (FNAME, LNAME,
 *       USERID, PASSWORD, USERTYPE in COBOL order), duplicate ID checks,
 *       change detection, BCrypt encoding, and persistence are all delegated
 *       to the service layer.</li>
 *   <li>Passwords are NEVER logged or returned in responses. The
 *       {@link UserSecurityDto#getSecUsrPwd()} field uses
 *       {@code @JsonProperty(access = WRITE_ONLY)} to exclude it from
 *       serialized responses.</li>
 *   <li>Structured logging with SLF4J supports correlation ID propagation
 *       via MDC per AAP §0.7.1 observability requirements.</li>
 * </ul>
 *
 * <p>Source traceability: COUSR00C.cbl + COUSR01C.cbl + COUSR02C.cbl +
 * COUSR03C.cbl — CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see UserSecurityDto
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs all five endpoint operations at INFO level for audit trail and
     * observability, supporting the AAP §0.7.1 requirement for structured
     * logging with correlation IDs propagated via MDC.
     * Passwords are NEVER logged per security constraints.
     */
    private static final Logger logger = LoggerFactory.getLogger(UserAdminController.class);

    /**
     * User list service implementing paginated user browse.
     * Migrated from COBOL program COUSR00C.cbl (695 lines, CICS transaction CU00).
     * Provides unfiltered pagination via {@code listUsers(int)} and filtered
     * browse from a specific user ID via {@code listUsersFromId(String, int)}.
     * Returns {@code Page<UserSecurityDto>} with page size 10, matching COBOL
     * {@code WS-USER-DATA OCCURS 10}.
     */
    private final UserListService userListService;

    /**
     * User add service implementing user creation with BCrypt password hashing.
     * Migrated from COBOL program COUSR01C.cbl (299 lines, CICS transaction CU01).
     * Validates all 5 fields in exact COBOL order (FNAME → LNAME → USERID →
     * PASSWORD → USERTYPE), checks for duplicate user IDs, BCrypt-encodes the
     * password, and persists the new user record.
     */
    private final UserAddService userAddService;

    /**
     * User update service implementing user record modification with change detection.
     * Migrated from COBOL program COUSR02C.cbl (414 lines, CICS transaction CU02).
     * Provides single-user retrieval via {@code getUserForUpdate(String)} and
     * modification via {@code updateUser(String, UserSecurityDto)} with BCrypt
     * password re-hashing when the password changes. Implements the COBOL
     * change detection pattern that rejects updates with no modifications.
     */
    private final UserUpdateService userUpdateService;

    /**
     * User delete service implementing user deletion with existence verification.
     * Migrated from COBOL program COUSR03C.cbl (359 lines, CICS transaction CU03).
     * Validates the user ID, verifies existence, and performs deletion. Maps the
     * two-phase COBOL pattern of lookup-for-confirmation then explicit delete.
     */
    private final UserDeleteService userDeleteService;

    /**
     * Constructs a new UserAdminController with the required four admin service
     * dependencies.
     *
     * <p>Uses Spring constructor injection, which is the recommended dependency
     * injection pattern. When a class has a single constructor, Spring
     * automatically uses it for autowiring without requiring the
     * {@code @Autowired} annotation.</p>
     *
     * @param userListService   service for paginated user browse (COUSR00C.cbl)
     * @param userAddService    service for user creation (COUSR01C.cbl)
     * @param userUpdateService service for user update (COUSR02C.cbl)
     * @param userDeleteService service for user deletion (COUSR03C.cbl)
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

    /**
     * Lists user security records with pagination support.
     *
     * <p>Maps COBOL program COUSR00C.cbl paragraphs PROCESS-PAGE-FORWARD,
     * PROCESS-PF7-KEY (backward), and PROCESS-PF8-KEY (forward) for paginated
     * user browse with an optional starting user ID filter.</p>
     *
     * <p>When {@code startUserId} is provided (non-null, non-blank), the
     * response contains users whose IDs are lexicographically greater than or
     * equal to the specified value, mapping the COBOL COUSR00C USRIDINI field
     * (user ID filter input). Otherwise, all users are returned in ascending
     * order by user ID.</p>
     *
     * <p>The page size is 10 records per page, enforced by
     * {@link UserListService} to match the COBOL
     * {@code WS-USER-DATA OCCURS 10} layout. The {@code page} parameter maps
     * to COBOL page navigation via PF7 (backward) and PF8 (forward) keys.</p>
     *
     * @param page        zero-based page number (default 0), maps COBOL
     *                    CDEMO-CU00-PAGE-NUM
     * @param startUserId optional starting user ID filter, maps COBOL
     *                    COUSR00C USRIDINI field; null or blank means no filter
     * @return HTTP 200 with {@link Page} of {@link UserSecurityDto} containing
     *         paginated results with metadata (totalElements, totalPages,
     *         hasNext, hasPrevious)
     */
    @GetMapping
    public ResponseEntity<Page<UserSecurityDto>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String startUserId) {
        logger.info("Listing users page={} startUserId={}", page, startUserId);
        Page<UserSecurityDto> result;
        if (startUserId != null && !startUserId.isBlank()) {
            result = userListService.listUsersFromId(startUserId, page);
        } else {
            result = userListService.listUsers(page);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Retrieves a single user security record by user ID.
     *
     * <p>Maps COBOL program COUSR02C.cbl paragraph PROCESS-ENTER-KEY for user
     * retrieval, supporting both the update workflow (COUSR02 — display user
     * for editing) and the delete confirmation workflow (COUSR03 — display
     * user for deletion confirmation).</p>
     *
     * <p>Delegates to {@link UserUpdateService#getUserForUpdate(String)} which
     * reads the user record from the USRSEC repository. The returned
     * {@link UserSecurityDto} excludes the password field from serialization
     * via {@code @JsonProperty(access = WRITE_ONLY)}.</p>
     *
     * @param id the user identifier (up to 8 characters, maps COBOL
     *           SEC-USR-ID PIC X(8))
     * @return HTTP 200 with {@link UserSecurityDto} containing the user record
     *         (without password)
     * @throws com.cardemo.exception.RecordNotFoundException if the user ID is
     *         not found (mapped to HTTP 404 by ControllerAdvice)
     * @throws com.cardemo.exception.ValidationException if the user ID is
     *         invalid (mapped to HTTP 400 by ControllerAdvice)
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserSecurityDto> getUser(@PathVariable String id) {
        logger.info("Getting user {}", id);
        UserSecurityDto result = userUpdateService.getUserForUpdate(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Creates a new user security record.
     *
     * <p>Maps COBOL program COUSR01C.cbl paragraphs PROCESS-ENTER-KEY and
     * WRITE-USER-SEC-FILE for user creation. The service validates all 5
     * fields in exact COBOL order (FNAME → LNAME → USERID → PASSWORD →
     * USERTYPE), checks for duplicate user IDs, BCrypt-encodes the password,
     * and persists the new user record.</p>
     *
     * <p>The {@code @Valid} annotation triggers Jakarta Bean Validation on the
     * {@link UserSecurityDto} request body, enforcing {@code @NotBlank} and
     * {@code @Size} constraints on all fields. This replaces the COBOL BMS
     * field attribute validation from CSSETATY.cpy.</p>
     *
     * @param dto the user creation payload (validated via {@code @Valid}),
     *            containing secUsrId, secUsrFname, secUsrLname, secUsrPwd,
     *            and secUsrType fields
     * @return HTTP 201 Created with {@link UserSecurityDto} containing the
     *         persisted user record (without password)
     * @throws com.cardemo.exception.DuplicateRecordException if a user with
     *         the same user ID already exists (mapped to HTTP 409 by
     *         ControllerAdvice; COBOL message: "User ID already exist...")
     * @throws com.cardemo.exception.ValidationException if field validation
     *         fails (mapped to HTTP 400 by ControllerAdvice)
     */
    @PostMapping
    public ResponseEntity<UserSecurityDto> addUser(@Valid @RequestBody UserSecurityDto dto) {
        logger.info("Creating user {}", dto.getSecUsrId());
        UserSecurityDto result = userAddService.addUser(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Updates an existing user security record.
     *
     * <p>Maps COBOL program COUSR02C.cbl paragraphs UPDATE-USER-INFO and
     * UPDATE-USER-SEC-FILE for user modification. The service validates
     * changes, detects whether any modification has actually occurred (the
     * COBOL change detection pattern — "Please modify to update..."), and
     * persists with BCrypt password re-hashing when the password changes.</p>
     *
     * <p>The {@code @Valid} annotation triggers Jakarta Bean Validation on the
     * {@link UserSecurityDto} request body. The path variable {@code id}
     * identifies the user to update; it maps the COBOL SEC-USR-ID primary
     * key used for the REWRITE operation.</p>
     *
     * @param id  the user identifier to update (up to 8 characters, maps
     *            COBOL SEC-USR-ID PIC X(8))
     * @param dto the updated user data (validated via {@code @Valid})
     * @return HTTP 200 with {@link UserSecurityDto} containing the persisted
     *         updated values (without password)
     * @throws com.cardemo.exception.RecordNotFoundException if the user ID
     *         is not found (mapped to HTTP 404 by ControllerAdvice)
     * @throws com.cardemo.exception.ValidationException if field validation
     *         fails or no changes detected (mapped to HTTP 400 by
     *         ControllerAdvice; includes "Please modify to update..." for
     *         no-change detection)
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserSecurityDto> updateUser(@PathVariable String id,
                                                      @Valid @RequestBody UserSecurityDto dto) {
        logger.info("Updating user {}", id);
        UserSecurityDto result = userUpdateService.updateUser(id, dto);
        return ResponseEntity.ok(result);
    }

    /**
     * Deletes a user security record.
     *
     * <p>Maps COBOL program COUSR03C.cbl paragraphs DELETE-USER-INFO and
     * DELETE-USER-SEC-FILE for user deletion. The service validates the user
     * ID, verifies the record exists, and performs the deletion. This maps
     * the two-phase COBOL pattern: lookup for confirmation then explicit
     * delete.</p>
     *
     * <p>Returns HTTP 204 (No Content) on success, indicating the resource
     * has been deleted and no response body is returned.</p>
     *
     * @param id the user identifier to delete (up to 8 characters, maps
     *           COBOL SEC-USR-ID PIC X(8))
     * @return HTTP 204 No Content on successful deletion
     * @throws com.cardemo.exception.RecordNotFoundException if the user ID
     *         is not found (mapped to HTTP 404 by ControllerAdvice)
     * @throws com.cardemo.exception.ValidationException if the user ID is
     *         invalid (mapped to HTTP 400 by ControllerAdvice)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        logger.info("Deleting user {}", id);
        userDeleteService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
