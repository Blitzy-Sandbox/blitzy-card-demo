package com.carddemo.controller;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.util.UriComponentsBuilder;

import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserListResponse;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.service.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Headless REST controller for CardDemo <strong>user administration</strong> &mdash; the
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5.11 migration of the four legacy CICS user-admin
 * programs (frozen COBOL reference at source commit SHA {@code 27d6c6f}, read-only, not copied
 * into this repository):
 *
 * <table>
 *   <caption>Legacy program &rarr; REST endpoint mapping</caption>
 *   <tr><th>Txn</th><th>Program</th><th>Function</th><th>Endpoint</th></tr>
 *   <tr><td>{@code CU00}</td><td>{@code COUSR00C}</td><td>List Users (10 rows/page, PF7/PF8)</td>
 *       <td>{@code GET /api/users}</td></tr>
 *   <tr><td>{@code CU01}</td><td>{@code COUSR01C}</td><td>Add User</td>
 *       <td>{@code POST /api/users}</td></tr>
 *   <tr><td>{@code CU02}</td><td>{@code COUSR02C}</td><td>Update User</td>
 *       <td>{@code PUT /api/users/{userId}}</td></tr>
 *   <tr><td>{@code CU03}</td><td>{@code COUSR03C}</td><td>View / Delete User</td>
 *       <td>{@code GET /api/users/{userId}} + {@code DELETE /api/users/{userId}}</td></tr>
 * </table>
 *
 * <h2>Admin-only surface</h2>
 * <p>All four legacy transactions are reachable only from the admin menu ({@code COADM01C},
 * transaction {@code CA00}) and return to it, so the entire controller is administrator-only. This
 * is enforced by the class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}, which gates
 * every endpoint; a non-administrator caller receives HTTP&nbsp;403. Method security is activated by
 * {@code @EnableMethodSecurity} in the sibling {@code config.SecurityConfig} (which additionally
 * locks {@code /api/users/**} to {@code ROLE_ADMIN} at the URL level as defense-in-depth); this
 * controller depends on that configuration but does not define it.</p>
 *
 * <h2>Thin delegation</h2>
 * <p>This controller is a thin, stateless adapter: every persistence concern, the BCrypt password
 * hashing (Constraint&nbsp;C-003 / Decision&nbsp;Log&nbsp;D-002), and all duplicate / not-found /
 * empty-field detection live in {@link UserService}. The controller only binds and validates the
 * HTTP contract, delegates, and maps the result to a status code. It never catches exceptions:
 * {@code GlobalExceptionHandler} translates the typed service exceptions to HTTP status
 * ({@code DuplicateResourceException}&nbsp;&rarr;&nbsp;409, {@code ResourceNotFoundException}&nbsp;
 * &rarr;&nbsp;404, {@code ValidationException} and Jakarta constraint violations&nbsp;&rarr;&nbsp;
 * 400, access denied&nbsp;&rarr;&nbsp;403).</p>
 *
 * <h2>Security &amp; observability</h2>
 * <p><strong>No credential ever leaves or is logged.</strong> The response DTOs
 * ({@link UserResponse}) carry no password field, and the request DTOs redact the password in their
 * {@code toString()}. This controller logs only the operation and the non-sensitive user id at
 * {@code INFO}; it never logs a request body, password, or hash. Each log line carries the MDC
 * {@code correlationId} published by {@code observability.CorrelationIdFilter}. The controller holds
 * no conversational ({@code COMMAREA}) state &mdash; it is fully stateless and thread-safe (its only
 * field is the injected, immutable service).</p>
 *
 * @see UserService
 * @see com.carddemo.controller.GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/users")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    /** Structured logger; every line carries the MDC {@code correlationId} (Observability rule). */
    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    /**
     * Base path of this controller, reused to build the {@code Location} header of a created user.
     * Kept in sync with the class-level {@code @RequestMapping("/api/users")}.
     */
    private static final String BASE_PATH = "/api/users";

    /**
     * Maximum length of the {@code SEC-USR-ID} key ({@code PIC X(08)} in copybook
     * {@code CSUSR01Y}); enforced on the user-id request parameter and path variable so the REST
     * contract stays width-compatible with the mainframe record layout.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /** The consolidated user-administration service that owns all business logic and persistence. */
    private final UserService userService;

    /**
     * Creates the controller with its collaborator injected by the container.
     *
     * <p>Constructor injection keeps the single dependency {@code final} and makes the controller
     * trivially unit-testable with a Mockito-mocked {@link UserService}.</p>
     *
     * @param userService the user-administration service; must not be {@code null}
     */
    public UserController(final UserService userService) {
        this.userService = userService;
    }

    /**
     * Lists application users one page at a time (legacy {@code COUSR00C}, transaction
     * {@code CU00}).
     *
     * <p>Reproduces the ten-rows-per-screen browse of the {@code COUSR00} map with stateless
     * pagination: the PF8 ("forward") / PF7 ("backward") scroll keys become the {@code page} query
     * parameter, and the optional user-id search field ({@code USRIDIN}) becomes the {@code userId}
     * filter, which the service echoes back to the caller. The service fixes the page size at ten.</p>
     *
     * <h3>Page-index bridging</h3>
     * <p>The REST {@code page} parameter is <strong>one-based</strong> (the first page is {@code 1},
     * mirroring the legacy {@code PAGENUM} display and constrained by {@code @Min(1)} with a default
     * of {@code 1}), whereas {@link UserService#listUsers(String, int)} expects the
     * <strong>zero-based</strong> Spring Data page index it passes straight to
     * {@code PageRequest.of(...)}. This method therefore delegates with {@code page - 1} so that
     * {@code ?page=1} returns the first page; passing the value through unchanged would leave the
     * first page unreachable (a behavioral-parity regression). The response envelope carries the
     * one-based page number back to the caller.</p>
     *
     * @param userId optional user-id search filter (legacy {@code USRIDIN}); {@code null}/absent for
     *               the unfiltered list; at most eight characters
     * @param page   the one-based page number to retrieve; defaults to {@code 1} and must be between
     *               {@code 1} and {@code 1000000} inclusive. The upper bound is an input-robustness
     *               guard: without it, the zero-based offset computed downstream
     *               ({@code (page - 1) * pageSize}) can exceed {@link Integer#MAX_VALUE} for absurdly
     *               large pages, which Spring Data rejects with an
     *               {@code InvalidDataAccessApiUsageException}. Bounding the parameter turns that into
     *               a clean {@code 400 VALIDATION_ERROR} instead of a {@code 500}, while one million
     *               pages addresses far more rows than any CardDemo data set holds.
     * @return HTTP&nbsp;200 with a {@link UserListResponse} wrapping the echoed filter and a page of
     *         at most ten rows (no password is ever included)
     */
    @GetMapping
    public ResponseEntity<UserListResponse> listUsers(
            @RequestParam(required = false) @Size(max = USER_ID_MAX_LENGTH) final String userId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) final int page) {
        log.info("Listing users: page={}, filtered={}", page, userId != null);
        // Bridge the one-based REST page number to the service's zero-based Spring page index.
        final UserListResponse response = userService.listUsers(userId, page - 1);
        return ResponseEntity.ok(response);
    }

    /**
     * Reads a single user's details (the record fetched behind the {@code COUSR03C} view screen,
     * transaction {@code CU03}).
     *
     * <p>Returns the user's non-sensitive attributes (id, first name, last name, type); never a
     * password. A missing key surfaces the legacy {@code DFHRESP(NOTFND)} branch as a
     * {@code ResourceNotFoundException}, which {@code GlobalExceptionHandler} maps to HTTP&nbsp;404.</p>
     *
     * @param userId the eight-character user id to read ({@code SEC-USR-ID})
     * @return HTTP&nbsp;200 with the located {@link UserResponse}
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(
            @PathVariable @NotBlank @Size(max = USER_ID_MAX_LENGTH) final String userId) {
        log.info("Fetching user {}", userId);
        final UserResponse response = userService.getUser(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Adds a new user (legacy {@code COUSR01C}, transaction {@code CU01}).
     *
     * <p>The request body is validated against the {@link UserCreateRequest} field edits migrated
     * from the {@code COUSR01} map (all fields mandatory; user type restricted to {@code 'A'} or
     * {@code 'U'}); a constraint failure yields HTTP&nbsp;400. The service BCrypt-hashes the supplied
     * plaintext password before persistence (Constraint&nbsp;C-003 / Decision&nbsp;Log&nbsp;D-002) and
     * rejects a pre-existing id with a {@code DuplicateResourceException}, mapped to HTTP&nbsp;409.
     * On success a {@code Location} header pointing at the new resource is returned alongside the
     * confirmation body.</p>
     *
     * <p>Only the new user's id is logged &mdash; never the request body or the password.</p>
     *
     * @param request the create request (first/last name, user id, plaintext password, user type)
     * @return HTTP&nbsp;201 with a {@code Location} header of {@code /api/users/{userId}} and a
     *         {@link UserResponse} confirmation body carrying the "has been added" message
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody final UserCreateRequest request) {
        log.info("Creating user {}", request.userId());
        final UserResponse created = userService.createUser(request);
        final URI location = UriComponentsBuilder.fromPath(BASE_PATH)
                .path("/{userId}")
                .buildAndExpand(created.userId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Updates an existing user (legacy {@code COUSR02C}, transaction {@code CU02}).
     *
     * <p>The user id addresses the resource as a path variable and is never taken from the body. The
     * service reads the record first &mdash; a missing key yields a {@code ResourceNotFoundException}
     * (HTTP&nbsp;404) &mdash; then applies the first name, last name, and user type. The password is
     * optional on update: a {@code null}/blank value leaves the stored BCrypt hash untouched, while a
     * supplied value is re-hashed by the service. Field-edit failures yield HTTP&nbsp;400.</p>
     *
     * @param userId  the eight-character user id being updated ({@code SEC-USR-ID}, path variable)
     * @param request the update request (first/last name, optional password, user type)
     * @return HTTP&nbsp;200 with a {@link UserResponse} confirmation body carrying the "has been
     *         updated" message
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable @NotBlank @Size(max = USER_ID_MAX_LENGTH) final String userId,
            @Valid @RequestBody final UserUpdateRequest request) {
        log.info("Updating user {}", userId);
        final UserResponse response = userService.updateUser(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Deletes an existing user (legacy {@code COUSR03C} PF5 delete, transaction {@code CU03}).
     *
     * <p>The service reads the record first so a missing key surfaces the {@code DFHRESP(NOTFND)}
     * branch as a {@code ResourceNotFoundException} (HTTP&nbsp;404); the record is then removed by
     * primary key. The confirmation body carries the deleted user's non-sensitive attributes (never a
     * password) and the "has been deleted" message, mirroring the CU03 delete acknowledgement.</p>
     *
     * @param userId the eight-character user id to delete ({@code SEC-USR-ID})
     * @return HTTP&nbsp;200 with a {@link UserResponse} confirmation body carrying the "has been
     *         deleted" message
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<UserResponse> deleteUser(
            @PathVariable @NotBlank @Size(max = USER_ID_MAX_LENGTH) final String userId) {
        log.info("Deleting user {}", userId);
        final UserResponse response = userService.deleteUser(userId);
        return ResponseEntity.ok(response);
    }
}
