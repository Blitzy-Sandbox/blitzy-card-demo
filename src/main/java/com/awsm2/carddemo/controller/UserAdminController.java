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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
 * User administration REST controller &mdash; admin-only CRUD on operator
 * security records.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> this controller replaces
 * four CICS COBOL programs &mdash; all admin-gated &mdash; operating on
 * the {@code USRSEC} VSAM KSDS cluster
 * ({@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}, RECLN=80, KEYLEN=8). The
 * 80-byte record layout is defined in {@code app/cpy/CSUSR01Y.cpy}
 * ({@code SEC-USER-DATA}) and is mapped to the {@code UserSecurity}
 * JPA entity backing the {@code user_security} table (Flyway
 * {@code V010__create_user_security.sql}):
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl} (CICS transaction id {@code CU00})
 *       &mdash; <b>User List</b>; paginated keyed browse across
 *       {@code USRSEC} rendered via {@code app/bms/COUSR00.bms} (symbolic
 *       map {@code app/cpy-bms/COUSR00.CPY}). The Java target delegates
 *       to {@link UserListService#listUsers(String, int)} which paginates
 *       at exactly 10 rows per page to preserve byte-for-byte parity with
 *       the COBOL {@code USER-REC OCCURS 10 TIMES} working-storage array
 *       and the 10-row {@code COUSR0A} BMS map (AAP &sect;0.7.1
 *       Minimal Change Clause).</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (CICS transaction id {@code CU01})
 *       &mdash; <b>User Add</b>; replaces {@code WRITE-USER-SEC-FILE}
 *       paragraph with {@link UserAddService#addUser(UserAddDto)} which
 *       BCrypt-hashes the plaintext password (strength 12) before saving
 *       &mdash; a deliberate security upgrade from the source COBOL's
 *       plaintext {@code SEC-USR-PWD PIC X(08)} storage per AAP
 *       &sect;0.1.1 and PCI-DSS requirements (AAP &sect;0.6.6). The BMS
 *       mapset is {@code app/bms/COUSR01.bms} with symbolic map
 *       {@code app/cpy-bms/COUSR01.CPY}.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} (CICS transaction id {@code CU02})
 *       &mdash; <b>User Update</b>; replaces {@code UPDATE-USER-SEC-FILE}
 *       paragraph with
 *       {@link UserUpdateService#updateUser(String, UserUpdateDto)}.
 *       When the request body carries a non-blank password, the service
 *       re-hashes with BCrypt; when omitted or blank, the existing hash
 *       is retained (modern REST partial-update convention documented in
 *       {@link UserUpdateDto}). The BMS mapset is
 *       {@code app/bms/COUSR02.bms} with symbolic map
 *       {@code app/cpy-bms/COUSR02.CPY}.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} (CICS transaction id {@code CU03})
 *       &mdash; <b>User Delete</b>; replaces {@code DELETE-USER-SEC-FILE}
 *       paragraph with
 *       {@link UserDeleteService#deleteUser(String, UserDeleteDto)}.
 *       The single-shot REST {@code DELETE} collapses the COBOL two-step
 *       pseudo-conversational flow (read &rarr; confirm) by requiring the
 *       caller to supply a binary {@code Y}/{@code N} confirmation flag
 *       in {@link UserDeleteDto#confirm()}; a value other than
 *       {@code "Y"} aborts the delete. The BMS mapset is
 *       {@code app/bms/COUSR03.bms} with symbolic map
 *       {@code app/cpy-bms/COUSR03.CPY}.</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/admin/users?search={q}&page={n}} &mdash;
 *       paginated user list (10 rows per page) with optional search
 *       filter; replaces CICS {@code COUSR00C} / {@code CU00}. Returns
 *       HTTP {@code 200 OK} carrying a {@link UserListDto} envelope.</li>
 *   <li>{@code POST /api/admin/users} &mdash; create new user with
 *       BCrypt-hashed password (security upgrade); replaces CICS
 *       {@code COUSR01C} / {@code CU01}. Returns HTTP
 *       {@code 201 Created}.</li>
 *   <li>{@code PUT /api/admin/users/{id}} &mdash; partial-update of an
 *       existing user with optional password rotation; replaces CICS
 *       {@code COUSR02C} / {@code CU02}. Returns HTTP {@code 200 OK}.</li>
 *   <li>{@code DELETE /api/admin/users/{id}} &mdash; delete after
 *       explicit {@code confirm="Y"} flag; replaces CICS
 *       {@code COUSR03C} / {@code CU03}. Returns HTTP {@code 200 OK}
 *       with the {@link com.awsm2.carddemo.dto.ApiResponse} envelope
 *       carrying a confirmation message (deliberately not {@code 204
 *       No Content} so the standard envelope is consistent across all
 *       endpoints).</li>
 * </ul>
 *
 * <p><b>Authorization (defense-in-depth, AAP &sect;0.3.4):</b> the
 * class-level {@code @PreAuthorize("hasRole('ADMIN')")} annotation
 * ensures every method on this controller is gated by the {@code ADMIN}
 * role. This is the second of two enforcement points; the first is the
 * URL-level matcher {@code requestMatchers("/api/admin/**").hasRole("ADMIN")}
 * declared in {@code com.awsm2.carddemo.config.SecurityConfig}. Either
 * gate alone is sufficient to reject a non-admin caller (HTTP
 * {@code 403 Forbidden} via {@code GlobalExceptionHandler}) and an
 * unauthenticated caller (HTTP {@code 401 Unauthorized}); having both
 * prevents misconfiguration regressions. This mirrors the COBOL
 * admin-only routing through {@code COADM01C}.</p>
 *
 * <p><b>Security upgrade from COBOL source (AAP &sect;0.1.1 +
 * &sect;0.7.1):</b> the COBOL source stored passwords verbatim in
 * {@code SEC-USR-PWD PIC X(08)} &mdash; an 8-byte plaintext field in the
 * 80-byte {@code SEC-USER-DATA} record. The Java target stores a
 * 60-character BCrypt hash (strength 12) in
 * {@code user_security.sec_usr_pwd VARCHAR(60)} via Spring Security 6's
 * {@code BCryptPasswordEncoder}. This is the only deliberate behavioral
 * deviation from the source COBOL and is justified by the PCI-DSS
 * compliance posture mandated in AAP &sect;0.6.6.</p>
 *
 * <p><b>PCI-DSS logging discipline (AAP &sect;0.6.6):</b> the class
 * {@link Logger} emits only non-sensitive identifiers (userId, userType,
 * page number, search filter). Plaintext passwords and BCrypt hashes
 * <b>NEVER</b> appear in any log statement; the DTO {@code toString()}
 * overrides additionally redact the password component as defense in
 * depth.</p>
 *
 * <p><b>Validation (AAP &sect;0.3.3 layered architecture):</b> per-method
 * Jakarta Bean Validation annotations on {@code @RequestParam} and
 * {@code @PathVariable} arguments are activated by the class-level
 * {@link Validated} marker. Request-body validation cascades from
 * {@link Valid @Valid} on the {@code @RequestBody} parameter. Validation
 * failures surface as HTTP {@code 400 Bad Request} via
 * {@code GlobalExceptionHandler}.</p>
 *
 * <p><b>Layered architecture compliance (AAP &sect;0.3.3):</b> this is a
 * thin Spring MVC fa&ccedil;ade that performs no business logic. All
 * USRSEC mutations, validation cascades, password hashing, audit
 * emission, and error mapping are delegated to the four injected
 * {@code @Service} collaborators. Constructor injection is used
 * exclusively (AAP &sect;0.7.1 "Dependency injection for loose
 * coupling"; no {@code @Autowired} field injection).</p>
 *
 * <p><b>Path vs body ID consistency check (IDOR mitigation):</b>
 * {@code PUT} and {@code DELETE} endpoints verify that the {@code id}
 * path variable equals the {@code userId} field carried in the request
 * body. A mismatch surfaces as {@link ValidationException} with reason
 * code {@code "USER_ID_MISMATCH"} (HTTP {@code 400 Bad Request} via
 * {@code GlobalExceptionHandler}). This prevents IDOR-style confusion
 * where a malicious client could {@code PUT} to one URL with a body
 * claiming a different user ID, and mirrors the analogous checks in
 * {@code AccountController} and {@code CardController}.</p>
 *
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see UserListDto
 * @see UserAddDto
 * @see UserUpdateDto
 * @see UserDeleteDto
 * @see ApiResponse
 */
@RestController
@RequestMapping("/api/admin/users")
@Validated
@Tag(
        name = "User Administration",
        description = "Admin user CRUD on the USRSEC store (COBOL "
                + "CSUSR01Y.cpy / app/cbl/COUSR00C..COUSR03C; CICS "
                + "transactions CU00 / CU01 / CU02 / CU03). "
                + "ADMIN role required for every endpoint."
)
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    /**
     * SLF4J logger for this controller. Routed through Logback +
     * {@code logstash-logback-encoder} to CloudWatch Logs per AAP
     * &sect;0.6.6 observability. PII / PCI-DSS hygiene (AAP
     * &sect;0.6.6 + &sect;0.7.1): log statements emit only the
     * non-sensitive identifiers (userId, userType, page index,
     * search term); the plaintext password from {@link UserAddDto}
     * and {@link UserUpdateDto} and the persisted BCrypt hash NEVER
     * appear in any log line.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserAdminController.class);

    /**
     * Default page index used by the {@link #listUsers(String, int)}
     * endpoint when the caller omits the {@code page} query
     * parameter. The value is documented here as a constant rather
     * than literally on the {@code defaultValue} attribute so the
     * Javadoc surfaces the intent: "start the browse at the
     * beginning" replaces the COBOL {@code STARTBR ... GTEQ
     * SPACES} default-positioning semantic in
     * {@code app/cbl/COUSR00C.cbl}.
     */
    private static final String DEFAULT_PAGE_INDEX = "0";

    /**
     * Maximum length of a {@code SEC-USR-ID} value &mdash; matches
     * both the COBOL declaration {@code SEC-USR-ID PIC X(08)} in
     * {@code app/cpy/CSUSR01Y.cpy} and the V010 column definition
     * {@code sec_usr_id VARCHAR(8)}. Used by the {@link Size}
     * constraints on {@code @RequestParam} and {@code @PathVariable}
     * arguments below.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Uppercase alphanumeric pattern (with optional embedded spaces)
     * matching the legacy {@code SEC-USR-ID PIC X(08)} VSAM key
     * format. The space is permitted because COBOL {@code MOVE} into
     * a fixed-width {@code PIC X(08)} field left-justifies and pads
     * the trailing positions with spaces, so legacy operator-typed
     * identifiers of length less than 8 may include embedded padding
     * characters when the field is read back from VSAM.
     */
    private static final String USER_ID_PATTERN = "^[A-Z0-9 ]{1,8}$";

    // ------------------------------------------------------------------
    // Collaborators (constructor-injected)
    //
    // AAP §0.7.1: constructor injection ONLY. No @Autowired field
    // injection — it bypasses immutability and complicates testing.
    // All four services are mandatory; no optional collaborators.
    // ------------------------------------------------------------------

    /**
     * User-list service collaborator &mdash; encapsulates the paginated
     * {@code USRSEC} browse originally implemented by the
     * {@code COUSR00C.cbl} pagination paragraphs
     * ({@code PROCESS-ENTER-KEY}, {@code PROCESS-PF7-KEY},
     * {@code PROCESS-PF8-KEY}).
     */
    private final UserListService userListService;

    /**
     * User-add service collaborator &mdash; encapsulates the BCrypt
     * password hashing and {@code USRSEC} write originally implemented
     * by the {@code COUSR01C.cbl:WRITE-USER-SEC-FILE} paragraph;
     * {@code DUPKEY}/{@code DUPREC} on duplicate user ID
     * (FILE STATUS '22') is mapped to HTTP {@code 409 Conflict}.
     */
    private final UserAddService userAddService;

    /**
     * User-update service collaborator &mdash; encapsulates the
     * optional BCrypt password re-hash and {@code USRSEC} rewrite
     * originally implemented by the
     * {@code COUSR02C.cbl:UPDATE-USER-SEC-FILE} paragraph;
     * {@code NOTFND} (FILE STATUS '23') is mapped to HTTP
     * {@code 404 Not Found}.
     */
    private final UserUpdateService userUpdateService;

    /**
     * User-delete service collaborator &mdash; encapsulates the
     * confirmation-then-delete flow originally implemented by the
     * {@code COUSR03C.cbl:DELETE-USER-SEC-FILE} paragraph; a
     * {@code confirm} value other than {@code "Y"} aborts the delete
     * without removing the row (mirrors the COBOL
     * {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} branch).
     */
    private final UserDeleteService userDeleteService;

    /**
     * Single constructor used by Spring's IoC container.
     *
     * <p>Constructor injection (rather than {@code @Autowired} field
     * injection) is mandated by AAP &sect;0.7.1 because it permits
     * the four collaborator fields to be declared {@code final}
     * (guaranteeing immutability after construction), makes the
     * dependency graph explicit at compile time (no hidden runtime
     * reflection requirements), and enables trivial unit testing with
     * hand-rolled stubs or Mockito mocks without bringing up a Spring
     * context (AAP &sect;0.7.2 testing approach).</p>
     *
     * @param userListService   the {@link UserListService} collaborator
     *                          backing {@code GET /api/admin/users}
     * @param userAddService    the {@link UserAddService} collaborator
     *                          backing {@code POST /api/admin/users}
     * @param userUpdateService the {@link UserUpdateService} collaborator
     *                          backing {@code PUT /api/admin/users/{id}}
     * @param userDeleteService the {@link UserDeleteService} collaborator
     *                          backing {@code DELETE /api/admin/users/{id}}
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

    // ==================================================================
    // GET /api/admin/users
    // ==================================================================

    /**
     * Returns a paginated list of users with optional search filter.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COUSR00C.cbl:PROCESS-ENTER-KEY} which browsed the
     * {@code USRSEC} VSAM KSDS in 10-row pages (per BMS
     * {@code COUSR00.bms} {@code OCCURS 10}) with optional search
     * filter and PF7/PF8 paging keys. The Java target collapses the
     * paginated browse to a single REST call with a {@code page}
     * query parameter.</p>
     *
     * <p>Page size is fixed at 10 rows by {@link UserListService}
     * verbatim per AAP &sect;0.7.1 Minimal Change Clause (the COBOL
     * declaration {@code USER-REC OCCURS 10 TIMES} at
     * {@code COUSR00C.cbl}:L56-L64 is a literal carry-over, not a
     * REST convention).</p>
     *
     * @param search optional search term applied to the user-ID prefix;
     *               must not exceed 8 characters (COBOL
     *               {@code SEC-USR-ID} length). May be {@code null}
     *               or empty to list all users.
     * @param page   0-based page index; defaults to {@code 0} when
     *               omitted; must be non-negative
     * @return {@link ResponseEntity} with HTTP {@code 200 OK} carrying
     *         the {@link UserListDto} wrapped in {@link ApiResponse}.
     *         HTTP {@code 400 Bad Request} on validation failure
     *         (search exceeds 8 chars, page is negative); HTTP
     *         {@code 401 Unauthorized} if unauthenticated; HTTP
     *         {@code 403 Forbidden} if the caller lacks the
     *         {@code ADMIN} role.
     */
    @GetMapping
    @Operation(
            summary = "List users (paginated, admin-only)",
            description = "Returns a paginated user list (page size 10) "
                    + "with an optional search filter. Replaces CICS "
                    + "COUSR00C / Tran-ID CU00 (user list). The PAGE_SIZE "
                    + "of 10 is a verbatim carry-over from the COBOL "
                    + "USER-REC OCCURS 10 TIMES working-storage array."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User list returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (search too long or "
                            + "page negative)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller is not an admin "
                            + "(ADMIN role required)")
    })
    public ResponseEntity<ApiResponse<UserListDto>> listUsers(
            @RequestParam(name = "search", required = false)
            @Size(max = USER_ID_MAX_LENGTH,
                    message = "search filter cannot exceed 8 characters "
                            + "(COBOL SEC-USR-ID length)")
            @Parameter(
                    description = "Optional search term applied to the user-ID "
                            + "prefix; max 8 characters",
                    example = "ADMIN")
            String search,

            @RequestParam(name = "page", required = false,
                    defaultValue = DEFAULT_PAGE_INDEX)
            @Min(value = 0, message = "page must be >= 0")
            @Parameter(
                    description = "0-based page index; defaults to 0 when "
                            + "omitted",
                    example = "0")
            int page) {

        // COBOL: COUSR00C / Tran-ID CU00 -- PROCESS-ENTER-KEY paginated
        //   browse. Page size is fixed at 10 by UserListService per AAP
        //   §0.7.1 (verbatim carry-over of USER-REC OCCURS 10 TIMES).
        // PCI-DSS: log only the non-sensitive page index and search term;
        //   the listing itself never contains password hashes (UserRow
        //   intentionally excludes SEC-USR-PWD).
        LOG.debug("User list requested: search={} page={}", search, page);

        UserListDto userList = userListService.listUsers(search, page);
        return ResponseEntity.ok(ApiResponse.success(userList));
    }

    // ==================================================================
    // POST /api/admin/users
    // ==================================================================

    /**
     * Creates a new user with a BCrypt-hashed password.
     *
     * <p><b>COBOL provenance:</b> Replaces the
     * {@code COUSR01C.cbl:WRITE-USER-SEC-FILE} paragraph which:</p>
     * <ol>
     *   <li>Validated user ID, first/last name (20 chars), password
     *       (8 chars), and {@code SEC-USR-TYPE} (A or U) in the
     *       {@code PROCESS-ENTER-KEY} EVALUATE cascade.</li>
     *   <li>Issued {@code EXEC CICS WRITE FILE('USRSEC')
     *       FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)}; on
     *       {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}
     *       (FILE STATUS '22') the message
     *       {@code 'User ID already exist...'} was displayed.</li>
     * </ol>
     *
     * <p>The Java target additionally BCrypt-hashes the plaintext
     * password (strength 12) before persistence &mdash; a deliberate
     * security upgrade per AAP &sect;0.1.1 (the legacy
     * {@code USRSEC} file stored plaintext credentials, which is
     * incompatible with PCI-DSS requirements per AAP &sect;0.6.6).
     * On duplicate user ID, {@link UserAddService} throws
     * {@code DuplicateRecordException} which {@code GlobalExceptionHandler}
     * surfaces as HTTP {@code 409 Conflict}.</p>
     *
     * <p><b>PCI-DSS logging discipline:</b> the password component of
     * {@link UserAddDto} is <b>never</b> emitted to logs &mdash; the
     * DTO's {@code toString()} override redacts the password field,
     * and the log statement below references only {@code userId} and
     * {@code userType}.</p>
     *
     * @param request the validated {@link UserAddDto} carrying user ID,
     *                first/last name, plaintext password (to be hashed
     *                by the service), and user type
     * @return {@link ResponseEntity} with HTTP {@code 201 Created} and
     *         the saved {@link UserAddDto} (password component nulled
     *         by the service before return) wrapped in
     *         {@link ApiResponse}. HTTP {@code 400 Bad Request} on
     *         validation failure (missing field, invalid userType);
     *         HTTP {@code 401 Unauthorized} if unauthenticated;
     *         HTTP {@code 403 Forbidden} if the caller lacks ADMIN;
     *         HTTP {@code 409 Conflict} on duplicate user ID
     */
    @PostMapping
    @Operation(
            summary = "Add a new user (admin-only)",
            description = "Creates a new USRSEC entry with BCrypt-hashed "
                    + "password (security upgrade from COBOL plaintext "
                    + "storage per AAP §0.1.1). userType must be 'A' "
                    + "(admin) or 'U' (user). Replaces CICS COUSR01C / "
                    + "Tran-ID CU01 (user add)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "User created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed (userId, password, "
                            + "or userType invalid)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller is not an admin "
                            + "(ADMIN role required)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "User ID already exists "
                            + "(replaces COBOL FILE STATUS '22' DUPKEY)")
    })
    public ResponseEntity<ApiResponse<UserAddDto>> addUser(
            @Valid @RequestBody UserAddDto request) {

        // COBOL: COUSR01C / Tran-ID CU01 -- WRITE-USER-SEC-FILE
        //   (delegates to UserAddService which BCrypts the plaintext
        //    password before save per AAP §0.1.1; duplicates surface
        //    as DuplicateRecordException -> HTTP 409).
        // PCI-DSS: do NOT log the password. UserAddDto.toString() redacts
        //   the password field; only userId and userType are emitted.
        LOG.info("User add requested: userId={} userType={}",
                request.userId(), request.userType());

        UserAddDto created = userAddService.addUser(request);

        LOG.info("User added successfully: userId={}", created.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created,
                        "User created successfully"));
    }

    // ==================================================================
    // PUT /api/admin/users/{id}
    // ==================================================================

    /**
     * Updates user attributes with optional password rotation.
     *
     * <p><b>COBOL provenance:</b> Replaces the
     * {@code COUSR02C.cbl:UPDATE-USER-SEC-FILE} paragraph which:</p>
     * <ol>
     *   <li>{@code READ USRSEC} keyed on {@code SEC-USR-ID}; on
     *       {@code DFHRESP(NOTFND)} (FILE STATUS '23') the message
     *       {@code 'User ID NOT found...'} was displayed.</li>
     *   <li>{@code REWRITE} with updated first/last name, user type,
     *       and (always) password &mdash; the COBOL source required
     *       the {@code PASSWD} field to be non-empty on every
     *       update.</li>
     * </ol>
     *
     * <p>The Java target relaxes the "password always required"
     * constraint to support the industry-standard REST
     * "leave blank to keep existing" pattern per
     * {@link UserUpdateDto} documentation; when the {@code password}
     * field is non-blank, the service re-hashes with BCrypt
     * (strength 12) and stores the new hash, otherwise the existing
     * hash is preserved.</p>
     *
     * <p>On {@code NOTFND}, {@link UserUpdateService} throws
     * {@code RecordNotFoundException} which {@code GlobalExceptionHandler}
     * surfaces as HTTP {@code 404 Not Found}.</p>
     *
     * @param id      the 8-character user ID from the URL path; must
     *                be 1-8 uppercase alphanumeric characters per the
     *                COBOL {@code SEC-USR-ID PIC X(08)} contract
     * @param request the validated {@link UserUpdateDto} carrying the
     *                updated first/last name, user type, and optional
     *                new password
     * @return {@link ResponseEntity} with HTTP {@code 200 OK} and the
     *         updated {@link UserUpdateDto} (password component nulled
     *         by the service before return) wrapped in
     *         {@link ApiResponse}. HTTP {@code 400 Bad Request} on
     *         validation failure; HTTP {@code 401 Unauthorized} if
     *         unauthenticated; HTTP {@code 403 Forbidden} if the
     *         caller lacks ADMIN; HTTP {@code 404 Not Found} if no
     *         user exists with the supplied ID
     */
    @PutMapping("/{id}")
    @Operation(
            summary = "Update user details (admin-only)",
            description = "Partial-update of a USRSEC entry. If a new "
                    + "password is provided, it is BCrypt-re-hashed; "
                    + "otherwise the existing hash is preserved. "
                    + "Replaces CICS COUSR02C / Tran-ID CU02 (user update)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller is not an admin "
                            + "(ADMIN role required)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User not found "
                            + "(replaces COBOL FILE STATUS '23' NOTFND)")
    })
    public ResponseEntity<ApiResponse<UserUpdateDto>> updateUser(
            @PathVariable("id")
            @NotBlank(message = "userId is required")
            @Size(min = 1, max = USER_ID_MAX_LENGTH,
                    message = "userId must be 1-8 characters")
            @Pattern(regexp = USER_ID_PATTERN,
                    message = "userId must be uppercase alphanumeric "
                            + "(COBOL SEC-USR-ID format)")
            @Parameter(
                    description = "8-character User ID "
                            + "(COBOL SEC-USR-ID PIC X(08))",
                    example = "ADMIN001")
            String id,

            @Valid @RequestBody UserUpdateDto request) {

        // IDOR mitigation — path/body consistency check.
        //   Reject the request before any service invocation if the
        //   path id does not match the body userId, mirroring the
        //   pattern in AccountController and CardController. Without
        //   this guard, a caller could PUT /api/admin/users/USER0001
        //   with a body claiming { "userId": "USER0002", ... } and
        //   confuse downstream auditing.
        if (!Objects.equals(id, request.userId())) {
            LOG.warn("User update rejected: path userId differs from body");
            throw new ValidationException(
                    "USER_ID_MISMATCH",
                    "Path user ID must match request body userId");
        }
        // COBOL: COUSR02C / Tran-ID CU02 -- UPDATE-USER-SEC-FILE
        //   (delegates to UserUpdateService which optionally re-BCrypts
        //    the password when non-blank per AAP §0.1.1; missing rows
        //    surface as RecordNotFoundException -> HTTP 404).
        // PCI-DSS: do NOT log password (DTO toString redacts).
        LOG.info("User update requested for userId={}", id);

        UserUpdateDto updated = userUpdateService.updateUser(id, request);

        LOG.info("User updated successfully: userId={}", id);
        return ResponseEntity.ok(ApiResponse.success(updated,
                "User updated successfully"));
    }

    // ==================================================================
    // DELETE /api/admin/users/{id}
    // ==================================================================

    /**
     * Deletes a user after explicit confirmation.
     *
     * <p><b>COBOL provenance:</b> Replaces the
     * {@code COUSR03C.cbl:DELETE-USER-SEC-FILE} paragraph which:</p>
     * <ol>
     *   <li>Performed {@code READ USRSEC} keyed on
     *       {@code SEC-USR-ID}; on {@code DFHRESP(NOTFND)} the
     *       message {@code 'User ID NOT found...'} was displayed.</li>
     *   <li>Displayed the user record and prompted the operator to
     *       press {@code PF5} (delete) or {@code PF4} (cancel) per
     *       {@code app/bms/COUSR03.bms} (BMS map {@code COUSR3A},
     *       {@code CONFIRMI PIC X(01)}).</li>
     *   <li>On {@code PF5}, performed
     *       {@code EXEC CICS DELETE FILE('USRSEC')}; on {@code PF4},
     *       cleared the screen without deleting.</li>
     * </ol>
     *
     * <p>The Java target collapses this two-step pseudo-conversational
     * flow into a single REST {@code DELETE} call by requiring the
     * caller to supply a binary {@code Y}/{@code N} confirmation flag
     * in {@link UserDeleteDto#confirm()}:</p>
     * <ul>
     *   <li>{@code "Y"} &mdash; proceed with the delete (replaces
     *       {@code WHEN DFHPF5 PERFORM DELETE-USER-INFO}).</li>
     *   <li>{@code "N"} &mdash; abandon the operation gracefully
     *       (replaces {@code WHEN DFHPF4 PERFORM
     *       CLEAR-CURRENT-SCREEN}). No row is removed.</li>
     *   <li>Any other value or {@code null} &mdash; rejected by
     *       {@link UserDeleteService} via
     *       {@code ValidationException} (HTTP {@code 400 Bad
     *       Request}).</li>
     * </ul>
     *
     * <p><b>HTTP status note:</b> HTTP {@code 200 OK} with the standard
     * {@link ApiResponse} envelope (rather than {@code 204 No Content})
     * is intentional per AAP &sect;0.3.4 so the response shape is
     * consistent across every endpoint &mdash; clients can rely on the
     * envelope's {@code code}/{@code message}/{@code timestamp} fields
     * for downstream telemetry and operator messaging.</p>
     *
     * @param id      the 8-character user ID from the URL path; must
     *                be 1-8 uppercase alphanumeric characters per the
     *                COBOL {@code SEC-USR-ID PIC X(08)} contract
     * @param request the validated {@link UserDeleteDto} carrying the
     *                confirmation flag and (optionally) echoed display
     *                fields
     * @return {@link ResponseEntity} with HTTP {@code 200 OK} and the
     *         {@link UserDeleteDto} (echoing the deleted user's
     *         identity) wrapped in {@link ApiResponse}. HTTP
     *         {@code 400 Bad Request} on validation failure (missing
     *         or invalid {@code confirm}); HTTP {@code 401
     *         Unauthorized} if unauthenticated; HTTP {@code 403
     *         Forbidden} if the caller lacks ADMIN; HTTP {@code 404
     *         Not Found} if no user exists with the supplied ID
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a user with confirmation (admin-only)",
            description = "Deletes a USRSEC entry after explicit "
                    + "confirmation flag = 'Y' in the request body. "
                    + "Confirmation flag 'N' aborts the delete gracefully "
                    + "(no row removed). Replaces CICS COUSR03C / "
                    + "Tran-ID CU03 (user delete)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User deleted successfully "
                            + "(or confirmation pending on 'N')"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Confirmation flag missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller is not an admin "
                            + "(ADMIN role required)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User not found "
                            + "(replaces COBOL FILE STATUS '23' NOTFND)")
    })
    public ResponseEntity<ApiResponse<UserDeleteDto>> deleteUser(
            @PathVariable("id")
            @NotBlank(message = "userId is required")
            @Size(min = 1, max = USER_ID_MAX_LENGTH,
                    message = "userId must be 1-8 characters")
            @Pattern(regexp = USER_ID_PATTERN,
                    message = "userId must be uppercase alphanumeric "
                            + "(COBOL SEC-USR-ID format)")
            @Parameter(
                    description = "8-character User ID "
                            + "(COBOL SEC-USR-ID PIC X(08))",
                    example = "USER0001")
            String id,

            @Valid @RequestBody UserDeleteDto request) {

        // IDOR mitigation — path/body consistency check.
        //   Same defense-in-depth pattern as updateUser above:
        //   a DELETE on /api/admin/users/USER0001 with body claiming
        //   { "userId": "USER0002", ... } is rejected with
        //   USER_ID_MISMATCH before any service invocation.
        if (!Objects.equals(id, request.userId())) {
            LOG.warn("User delete rejected: path userId differs from body");
            throw new ValidationException(
                    "USER_ID_MISMATCH",
                    "Path user ID must match request body userId");
        }
        // COBOL: COUSR03C / Tran-ID CU03 -- DELETE-USER-SEC-FILE
        //   (delegates to UserDeleteService which validates confirm='Y'
        //    and performs the actual delete; missing rows surface as
        //    RecordNotFoundException -> HTTP 404; invalid confirm
        //    surfaces as ValidationException -> HTTP 400).
        LOG.info("User delete requested for userId={} confirm={}",
                id, request.confirm());

        UserDeleteDto deleted = userDeleteService.deleteUser(id, request);

        LOG.info("User deleted successfully: userId={}", id);
        return ResponseEntity.ok(ApiResponse.success(deleted,
                "User deleted successfully"));
    }
}
