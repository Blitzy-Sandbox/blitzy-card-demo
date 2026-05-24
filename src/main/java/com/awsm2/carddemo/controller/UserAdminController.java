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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.dto.UserDeleteDto;
import com.awsm2.carddemo.dto.UserListDto;
import com.awsm2.carddemo.dto.UserUpdateDto;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.service.UserAddService;
import com.awsm2.carddemo.service.UserDeleteService;
import com.awsm2.carddemo.service.UserListService;
import com.awsm2.carddemo.service.UserUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * User administration REST controller &mdash; CRUD on operator
 * security records.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> this controller replaces
 * four CICS COBOL programs operating on the {@code USRSEC} VSAM KSDS
 * cluster (record layout {@code app/cpy/CSUSR01Y.cpy} CARD-USER-RECORD,
 * 80-byte) keyed on {@code SEC-USR-ID PIC X(8)}:</p>
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl} (CICS transaction id {@code CU00})
 *       &mdash; User list with pagination; rendered via
 *       {@code app/bms/COUSR00.bms}. The Java target uses
 *       {@link UserListService#listUsers(String, int)}.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (CICS transaction id {@code CU01})
 *       &mdash; User add with BCrypt password hashing (security
 *       upgrade from COBOL plaintext storage per AAP &sect;0.7.1);
 *       rendered via {@code app/bms/COUSR01.bms}.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} (CICS transaction id {@code CU02})
 *       &mdash; User update with optional password rotation (re-hash
 *       on change); rendered via {@code app/bms/COUSR02.bms}.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} (CICS transaction id {@code CU03})
 *       &mdash; User delete with confirmation; rendered via
 *       {@code app/bms/COUSR03.bms}.</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/admin/users?search={q}&page={n}} &mdash;
 *       paginated user list with optional search; replaces CICS
 *       {@code COUSR00C} / {@code CU00}.</li>
 *   <li>{@code POST /api/admin/users} &mdash; create new user with
 *       BCrypt-hashed password; replaces CICS {@code COUSR01C} /
 *       {@code CU01}. Returns HTTP 201 Created.</li>
 *   <li>{@code PUT /api/admin/users/{id}} &mdash; update user
 *       attributes (name, role, optional password); replaces CICS
 *       {@code COUSR02C} / {@code CU02}.</li>
 *   <li>{@code DELETE /api/admin/users/{id}} &mdash; delete user with
 *       confirmation; replaces CICS {@code COUSR03C} / {@code CU03}.</li>
 * </ul>
 *
 * <p><b>Authorization (admin-only):</b> ALL endpoints are gated by
 * {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} at the
 * method level &mdash; defense in depth alongside the URL-level
 * matcher {@code /api/admin/**} declared in
 * {@code SecurityConfig#securityFilterChain}. Both gates must hold
 * for an endpoint to execute; non-admin callers receive HTTP 403
 * Forbidden translated by {@code GlobalExceptionHandler}.</p>
 *
 * <p><b>Security upgrades from COBOL source (AAP &sect;0.1.1 inferred):</b></p>
 * <ul>
 *   <li><b>Plaintext &rarr; BCrypt:</b> the COBOL source stored
 *       {@code SEC-USR-PWD PIC X(08)} as plaintext in the
 *       {@code USRSEC} VSAM KSDS. The Java target stores the BCrypt
 *       hash of the password (strength 12) per AAP &sect;0.7.1 and
 *       the V015 Flyway seed migration. Password rotation re-hashes
 *       on update.</li>
 *   <li><b>Password normalization (CP5 review):</b> passwords are
 *       hashed verbatim (no uppercasing) to preserve entropy. User
 *       IDs are normalized (trimmed + uppercased) for case-insensitive
 *       lookup, but passwords retain mixed-case characters and special
 *       symbols.</li>
 * </ul>
 *
 * <p><b>Path vs body ID consistency check:</b> {@code PUT} and
 * {@code DELETE} endpoints verify that the {@code id} path variable
 * equals the {@code userId} field carried in the request body.
 * Mismatches throw {@link ValidationException} (HTTP 400) to prevent
 * IDOR-style confusion where a client could PUT to one URL with a
 * body claiming a different user ID.</p>
 *
 * <p><b>Layered architecture compliance:</b> thin Spring MVC fa&ccedil;ade;
 * delegates to {@link UserListService}, {@link UserAddService},
 * {@link UserUpdateService}, and {@link UserDeleteService} via
 * constructor injection.</p>
 *
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see UserListDto
 * @see UserAddDto
 * @see UserUpdateDto
 * @see UserDeleteDto
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User Administration",
        description = "User CRUD (admin-only). Replaces CICS COUSR00C "
                + "(Tran-ID CU00, list), COUSR01C (CU01, add), COUSR02C "
                + "(CU02, update), and COUSR03C (CU03, delete).")
public class UserAdminController {

    /**
     * SLF4J facade for structured JSON logging. Per AAP &sect;0.7.2.
     * PCI-DSS / security discipline (AAP &sect;0.6.6): only the
     * non-sensitive user ID is logged. Passwords and BCrypt hashes
     * are NEVER logged (the DTOs' {@code toString()} methods redact
     * the password field).
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserAdminController.class);

    /**
     * User list service collaborator &mdash; encapsulates the
     * paginated {@code USRSEC} browse originally implemented by the
     * {@code COUSR00C.cbl:PROCESS-ENTER-KEY} +
     * {@code PROCESS-PF7} / {@code PROCESS-PF8} paginating paragraphs.
     */
    private final UserListService userListService;

    /**
     * User add service collaborator &mdash; encapsulates the BCrypt
     * password hashing and {@code USRSEC} write originally implemented
     * by the {@code COUSR01C.cbl:WRITE-USER-SEC-FILE} paragraph
     * (DUPKEY on duplicate user ID).
     */
    private final UserAddService userAddService;

    /**
     * User update service collaborator &mdash; encapsulates the
     * optional password re-hash and {@code USRSEC} rewrite originally
     * implemented by the {@code COUSR02C.cbl:UPDATE-USER-SEC-FILE}
     * paragraph.
     */
    private final UserUpdateService userUpdateService;

    /**
     * User delete service collaborator &mdash; encapsulates the
     * confirmation-then-delete flow originally implemented by the
     * {@code COUSR03C.cbl:DELETE-USER-SEC-FILE} paragraph.
     */
    private final UserDeleteService userDeleteService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * @param userListService   the {@link UserListService} collaborator
     * @param userAddService    the {@link UserAddService} collaborator
     * @param userUpdateService the {@link UserUpdateService} collaborator
     * @param userDeleteService the {@link UserDeleteService} collaborator
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
     * Returns a paginated user list with optional search filter.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COUSR00C.cbl:PROCESS-ENTER-KEY} which browsed the
     * {@code USRSEC} VSAM KSDS in 10-row pages (per BMS
     * {@code COUSR00.bms} {@code OCCURS 10}) with optional search.</p>
     *
     * @param search optional search term (typically a user-ID prefix)
     * @param page   0-based page index; defaults to 0
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link UserListDto} wrapped in {@link ApiResponse}
     */
    @GetMapping
    @Operation(
            summary = "List users (paginated, 10 rows per page)",
            description = "Returns a paginated user list with optional "
                    + "search filter. ADMIN role only. Replaces CICS "
                    + "COUSR00C / Tran-ID CU00 (user list)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User list returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User is not an admin (ADMIN role required)")
    })
    public ResponseEntity<ApiResponse<UserListDto>> listUsers(
            @Parameter(description = "Optional search term (user ID prefix)")
            @RequestParam(value = "search", required = false) String search,
            @Parameter(description = "0-based page index")
            @RequestParam(value = "page", defaultValue = "0") int page) {
        // COBOL: COUSR00C / Tran-ID CU00 -- PROCESS-ENTER-KEY paginated
        //   browse (delegates to UserListService per AAP §0.4.1).
        LOG.debug("User list requested: search={}, page={}", search, page);
        UserListDto userList = userListService.listUsers(search, page);
        return ResponseEntity.ok(ApiResponse.success(userList));
    }

    /**
     * Creates a new user with BCrypt-hashed password.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COUSR01C.cbl:WRITE-USER-SEC-FILE} which:</p>
     * <ol>
     *   <li>Validated user ID (8 chars), first/last name (20 chars),
     *       password (8 chars), and {@code SEC-USR-TYPE} (A or U).</li>
     *   <li>{@code WRITE USRSEC}; on {@code DFHRESP(DUPKEY)} or
     *       {@code DFHRESP(DUPREC)}: returned "User ID already exists".</li>
     * </ol>
     *
     * <p>The Java target additionally BCrypt-hashes the password before
     * persisting (security upgrade per AAP &sect;0.7.1).</p>
     *
     * @param request the validated {@link UserAddDto} carrying user ID,
     *                first/last name, plaintext password (to be hashed),
     *                and user type
     * @return {@link ResponseEntity} with HTTP 201 and the saved
     *         {@link UserAddDto} (password redacted in DTO
     *         {@code toString()}) wrapped in {@link ApiResponse}.
     *         HTTP 400 on validation failure; HTTP 409 on duplicate
     *         user ID
     */
    @PostMapping
    @Operation(
            summary = "Create new user",
            description = "Creates a new user with BCrypt-hashed password "
                    + "(security upgrade from COBOL plaintext storage). "
                    + "ADMIN role only. Replaces CICS COUSR01C / Tran-ID CU01 "
                    + "(user add)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "User created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User is not an admin (ADMIN role required)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Duplicate user ID")
    })
    public ResponseEntity<ApiResponse<UserAddDto>> addUser(
            @Valid @RequestBody UserAddDto request) {
        // COBOL: COUSR01C / Tran-ID CU01 -- WRITE-USER-SEC-FILE
        //   (delegates to UserAddService which BCrypts the password
        //    before save per AAP §0.7.1).
        // PCI-DSS: do NOT log password. UserAddDto.toString() redacts
        // the password field; only the user ID metadata is emitted.
        LOG.debug("User add requested for userId={}", request.userId());
        UserAddDto saved = userAddService.addUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(saved, "User created successfully"));
    }

    /**
     * Updates user attributes (with optional password rotation).
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COUSR02C.cbl:UPDATE-USER-SEC-FILE} which:</p>
     * <ol>
     *   <li>{@code READ USRSEC} keyed on {@code SEC-USR-ID}.</li>
     *   <li>{@code REWRITE} with updated first/last name, user type,
     *       and (if changed) password.</li>
     * </ol>
     *
     * <p>The Java target additionally BCrypt-hashes the new password if
     * the {@code password} field is non-blank (security upgrade).
     * Empty/null password means "no password change".</p>
     *
     * <p><b>Path vs body ID consistency check</b> as documented at the
     * class level.</p>
     *
     * @param id      the 8-character user ID from the URL path
     * @param request the validated {@link UserUpdateDto} carrying the
     *                user ID (must equal {@code id}), updated names,
     *                user type, and optional new password
     * @return {@link ResponseEntity} with HTTP 200 and the updated
     *         {@link UserUpdateDto} wrapped in {@link ApiResponse}.
     *         HTTP 400 on validation failure; HTTP 404 if the user
     *         does not exist
     */
    @PutMapping("/{id}")
    @Operation(
            summary = "Update user attributes",
            description = "Updates user attributes with optional password "
                    + "rotation (re-hashed with BCrypt if changed). ADMIN role "
                    + "only. Replaces CICS COUSR02C / Tran-ID CU02 (user update)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (path/body mismatch, invalid field)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User is not an admin (ADMIN role required)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User not found")
    })
    public ResponseEntity<ApiResponse<UserUpdateDto>> updateUser(
            @PathVariable("id") String id,
            @Valid @RequestBody UserUpdateDto request) {
        // COBOL: COUSR02C / Tran-ID CU02 -- UPDATE-USER-SEC-FILE
        //   (delegates to UserUpdateService which optionally re-BCrypts
        //    the password per AAP §0.7.1).
        //
        // Path/body consistency check — prevents IDOR confusion.
        if (!Objects.equals(id, request.userId())) {
            LOG.warn("User update rejected: path userId differs from body");
            throw new ValidationException(
                    "USER_ID_MISMATCH",
                    "Path user ID must match request body userId");
        }
        // PCI-DSS: do NOT log password (DTO toString redacts).
        LOG.debug("User update requested for userId={}", id);
        UserUpdateDto updated = userUpdateService.updateUser(id, request);
        return ResponseEntity.ok(ApiResponse.success(updated,
                "User updated successfully"));
    }

    /**
     * Deletes a user with confirmation.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COUSR03C.cbl:DELETE-USER-SEC-FILE} which:</p>
     * <ol>
     *   <li>{@code READ USRSEC} keyed on {@code SEC-USR-ID}.</li>
     *   <li>Displayed the user record and requested
     *       {@code CONFIRMI PIC X(1)} = Y on
     *       {@code app/bms/COUSR03.bms}.</li>
     *   <li>On Y &rarr; {@code DELETE USRSEC}.</li>
     * </ol>
     *
     * <p>The Java target requires the {@code confirm="Y"} field in the
     * {@link UserDeleteDto} body to initiate deletion; anything else
     * results in HTTP 400 from the service layer.</p>
     *
     * <p><b>Path vs body ID consistency check</b> as documented at the
     * class level.</p>
     *
     * <p><b>HTTP method choice:</b> {@code DELETE} is the correct verb;
     * the request body carries the confirmation token (a non-standard
     * pattern in pure REST, but justified by the COBOL UX parity
     * requirement &mdash; the original {@code COUSR03C} required a
     * confirmation step).</p>
     *
     * @param id      the 8-character user ID from the URL path
     * @param request the validated {@link UserDeleteDto} carrying the
     *                user ID (must equal {@code id}) and confirmation
     *                token
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link UserDeleteDto} (echoing the deleted user's
     *         identity) wrapped in {@link ApiResponse}. HTTP 400 on
     *         validation failure (including missing confirmation);
     *         HTTP 404 if the user does not exist
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete user (with confirmation)",
            description = "Deletes the user with the supplied ID after "
                    + "confirmation (confirm field must be 'Y'). ADMIN role "
                    + "only. Replaces CICS COUSR03C / Tran-ID CU03 (user delete)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (path/body mismatch, no confirmation)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User is not an admin (ADMIN role required)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User not found")
    })
    public ResponseEntity<ApiResponse<UserDeleteDto>> deleteUser(
            @PathVariable("id") String id,
            @Valid @RequestBody UserDeleteDto request) {
        // COBOL: COUSR03C / Tran-ID CU03 -- DELETE-USER-SEC-FILE
        //   (delegates to UserDeleteService which validates confirm=Y
        //    and performs the actual delete per AAP §0.4.1).
        //
        // Path/body consistency check — prevents IDOR confusion.
        if (!Objects.equals(id, request.userId())) {
            LOG.warn("User delete rejected: path userId differs from body");
            throw new ValidationException(
                    "USER_ID_MISMATCH",
                    "Path user ID must match request body userId");
        }
        LOG.debug("User delete requested for userId={}", id);
        UserDeleteDto deleted = userDeleteService.deleteUser(id, request);
        return ResponseEntity.ok(ApiResponse.success(deleted,
                "User deleted successfully"));
    }
}
