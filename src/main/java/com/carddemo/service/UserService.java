package com.carddemo.service;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserListItem;
import com.carddemo.dto.UserListResponse;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.DuplicateResourceException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserSecurityRepository;

/**
 * Application service consolidating the CardDemo user-administration CRUD flow
 * over the file-based {@code USRSEC} security store (JPA entity
 * {@link UserSecurity}, table {@code user_security}).
 *
 * <p>This single cohesive {@code @Service} is the Java&nbsp;25 / Spring&nbsp;Boot
 * migration of the four legacy CICS user-admin programs (frozen COBOL reference
 * at source commit SHA {@code 27d6c6f}), each of which is now a public method:</p>
 * <table>
 *   <caption>COBOL program &rarr; service method mapping</caption>
 *   <tr><th>Txn</th><th>Program</th><th>Function</th><th>Method</th></tr>
 *   <tr><td>{@code CU00}</td><td>{@code COUSR00C}</td><td>List Users</td><td>{@link #listUsers(String, int)}</td></tr>
 *   <tr><td>{@code CU01}</td><td>{@code COUSR01C}</td><td>Add User</td><td>{@link #createUser(UserCreateRequest)}</td></tr>
 *   <tr><td>{@code CU02}</td><td>{@code COUSR02C}</td><td>Update User</td><td>{@link #updateUser(String, UserUpdateRequest)}</td></tr>
 *   <tr><td>{@code CU03}</td><td>{@code COUSR03C}</td><td>Delete User</td><td>{@link #deleteUser(String)}</td></tr>
 * </table>
 *
 * <p>The single-record read behind the update/delete confirmation screens is
 * exposed as {@link #getUser(String)}.</p>
 *
 * <h2>Behavioural parity (G3)</h2>
 * <p>The empty-field edits reproduce the {@code EVALUATE TRUE} branch order of
 * {@code COUSR01C} / {@code COUSR02C} exactly, surfacing the verbatim COBOL
 * message literals ({@code 'First Name can NOT be empty...'}, etc.). The
 * add/update/delete confirmation strings reproduce the COBOL {@code STRING}
 * statements ({@code 'User ' + SEC-USR-ID + ' has been added ...'} and its
 * update/delete variants), with the user id trimmed to mirror
 * {@code DELIMITED BY SPACE}. The duplicate-key branch
 * ({@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)}) and the not-found branch
 * ({@code DFHRESP(NOTFND)} / {@code INVALID KEY}) map to typed exceptions
 * carrying the original literals.</p>
 *
 * <h2>Password handling (Constraint C-003 / Decision Log D-002)</h2>
 * <p>The legacy {@code SEC-USR-PWD} plaintext password ({@code PIC X(08)}) is
 * upgraded to a BCrypt hash. Hashing is delegated entirely to the injected
 * {@link PasswordEncoder} bean (a {@code BCryptPasswordEncoder} wired in
 * {@code SecurityConfig}); this service never stores, returns, or logs a
 * plaintext password or its hash. The response DTOs ({@link UserResponse},
 * {@link UserListItem}) deliberately carry no password field, and that is kept
 * so by the {@link #toResponse(UserSecurity, String)} /
 * {@link #toListItem(UserSecurity)} mappers.</p>
 *
 * <h2>Transactions and statelessness</h2>
 * <p>Reads run under {@code @Transactional(readOnly = true)}; the mutating
 * operations run under {@code @Transactional(rollbackFor = Exception.class)} so
 * a validation or persistence failure rolls the unit of work back, preserving
 * the all-or-nothing semantics of the original CICS logical unit of work. The
 * service holds no conversational ({@code COMMAREA}) state and is fully
 * thread-safe: its only fields are the injected, immutable collaborators.</p>
 *
 * <p>Structured logging is emitted via SLF4J; the per-request/per-batch
 * {@code correlationId} is supplied by {@code observability.CorrelationIdFilter}
 * through the MDC and requires no action here. Log lines carry only
 * non-sensitive identifiers (never credentials).</p>
 */
@Service
public class UserService {

    /** SLF4J logger; the MDC {@code correlationId} is injected by the correlation filter. */
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * Page size for the user list, fixed at ten rows to mirror the legacy
     * {@code COUSR00C} browse ({@code USER-REC OCCURS 10 TIMES}) and the
     * {@code UserListResponse} contract.
     */
    private static final int PAGE_SIZE = 10;

    /** Verbatim {@code COUSR01C}/{@code COUSR02C} empty first-name message. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Verbatim {@code COUSR01C}/{@code COUSR02C} empty last-name message. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Verbatim {@code COUSR01C} empty user-id message. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Verbatim {@code COUSR01C} empty password message. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Verbatim {@code COUSR01C}/{@code COUSR02C} empty user-type message. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** Verbatim {@code COUSR01C} duplicate-key message ({@code DFHRESP(DUPKEY)}/{@code DUPREC}). */
    private static final String MSG_DUPLICATE = "User ID already exist...";

    /** Verbatim {@code COUSR02C}/{@code COUSR03C} not-found message ({@code DFHRESP(NOTFND)}). */
    private static final String MSG_NOT_FOUND = "User ID NOT found...";

    /** Repository over the {@code user_security} table (replaces keyed {@code USRSEC} access). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt password encoder wired by {@code SecurityConfig} (Constraint C-003 / Decision Log D-002). */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its collaborators injected by the container.
     *
     * <p>Constructor injection is used so the service is trivially unit-testable
     * with Mockito mocks without bootstrapping a Spring context, and so both
     * collaborators can be {@code final}.</p>
     *
     * @param userSecurityRepository the {@link UserSecurity} repository; must not be {@code null}
     * @param passwordEncoder        the BCrypt {@link PasswordEncoder}; must not be {@code null}
     */
    public UserService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Lists application users one page at a time (legacy {@code COUSR00C},
     * transaction {@code CU00}).
     *
     * <p>The legacy browse walked {@code USRSEC} in {@code SEC-USR-ID} order ten
     * rows per screen; this maps to {@code findAll(PageRequest.of(page, 10))}.
     * The zero-based Spring page index is converted to the one-based page number
     * carried by {@link PageResponse} (the first page is {@code 1}), preserving
     * the {@code PAGENUM} display semantics. No password is ever projected into
     * the returned rows.</p>
     *
     * @param userIdFilter the optional user-id search filter echoed back to the
     *                     caller (legacy {@code USRIDIN}); may be {@code null}
     * @param page         the zero-based page index requested (Spring convention)
     * @return a {@link UserListResponse} wrapping the echoed filter and a
     *         {@link PageResponse} of {@link UserListItem} rows (page size 10)
     */
    @Transactional(readOnly = true)
    public UserListResponse listUsers(String userIdFilter, int page) {
        Page<UserSecurity> resultPage = userSecurityRepository.findAll(PageRequest.of(page, PAGE_SIZE));
        List<UserListItem> items = resultPage.getContent().stream()
                .map(this::toListItem)
                .toList();
        PageResponse<UserListItem> pageResponse = PageResponse.of(
                items,
                resultPage.getNumber() + 1,
                resultPage.getSize(),
                resultPage.getTotalElements());
        log.debug("Listed users: page={} size={} totalElements={}",
                resultPage.getNumber(), resultPage.getSize(), resultPage.getTotalElements());
        return new UserListResponse(userIdFilter, pageResponse);
    }

    /**
     * Reads a single user by id (the record fetched behind the update/delete
     * confirmation screens of {@code COUSR02C} / {@code COUSR03C}).
     *
     * <p>A missing key reproduces the COBOL {@code DFHRESP(NOTFND)} branch by
     * raising a {@link ResourceNotFoundException} carrying the verbatim
     * {@code 'User ID NOT found...'} literal. The returned {@link UserResponse}
     * carries no confirmation {@code message} (a plain read) and never any
     * password.</p>
     *
     * @param userId the eight-character user id ({@code SEC-USR-ID})
     * @return the {@link UserResponse} view of the located user
     * @throws ResourceNotFoundException (HTTP&nbsp;404) if no user has that id
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(String userId) {
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
        return toResponse(user, null);
    }

    /**
     * Creates a new user (legacy {@code COUSR01C}, transaction {@code CU01}).
     *
     * <p>The mandatory-field edits are applied in the exact {@code EVALUATE TRUE}
     * order of {@code COUSR01C} &mdash; first name, last name, user id, password,
     * user type &mdash; each raising a {@link ValidationException} with the
     * verbatim COBOL literal on the first blank field. A pre-existing key
     * reproduces the {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} branch as a
     * {@link DuplicateResourceException}. The supplied plaintext password is
     * BCrypt-hashed via the injected {@link PasswordEncoder} before persistence
     * (C-003 / D-002); the plaintext is never stored or logged.</p>
     *
     * @param req the create request (fields map to the {@code COUSR01} symbolic map)
     * @return a {@link UserResponse} whose {@code message} is
     *         {@code "User " + userId(trimmed) + " has been added ..."}
     * @throws ValidationException        (HTTP&nbsp;400) on the first blank mandatory field
     * @throws DuplicateResourceException (HTTP&nbsp;409) if the user id already exists
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse createUser(UserCreateRequest req) {
        // Input-contract guard: a null request body is a broken contract, surfaced
        // as the typed HTTP-400 first-mandatory-field edit (COBOL field order)
        // rather than an unhandled NullPointerException / HTTP 500 below.
        if (req == null) {
            throw new ValidationException(MSG_FIRST_NAME_EMPTY);
        }
        requireNonBlank(req.firstName(), MSG_FIRST_NAME_EMPTY);
        requireNonBlank(req.lastName(), MSG_LAST_NAME_EMPTY);
        requireNonBlank(req.userId(), MSG_USER_ID_EMPTY);
        requireNonBlank(req.password(), MSG_PASSWORD_EMPTY);
        requireNonBlank(req.userType(), MSG_USER_TYPE_EMPTY);

        if (userSecurityRepository.existsById(req.userId())) {
            throw new DuplicateResourceException(MSG_DUPLICATE);
        }

        UserSecurity user = new UserSecurity();
        user.setSecUsrId(req.userId());
        user.setSecUsrFname(req.firstName());
        user.setSecUsrLname(req.lastName());
        // Hash the UPPER-CASED plaintext so the stored BCrypt hash matches the password
        // SignonService verifies at logon (it upper-cases the presented password before
        // BCrypt.matches to preserve the COBOL COSGN00C FUNCTION UPPER-CASE case-insensitive
        // compare; Decision Log D-002). Locale.ROOT avoids locale-sensitive case folding.
        user.setSecUsrPwd(passwordEncoder.encode(req.password().toUpperCase(Locale.ROOT)));
        user.setSecUsrType(req.userType());
        UserSecurity saved = userSecurityRepository.save(user);

        String trimmedId = req.userId().trim();
        log.info("User {} has been added", trimmedId);
        return toResponse(saved, "User " + trimmedId + " has been added ...");
    }

    /**
     * Updates an existing user (legacy {@code COUSR02C}, transaction {@code CU02}).
     *
     * <p>The user id is addressed as a path variable (never a body field), so it
     * is passed as {@code userId}. The user is read first, reproducing the
     * {@code DFHRESP(NOTFND)} branch as a {@link ResourceNotFoundException} when
     * absent. First name, last name, and user type are then validated non-blank
     * in {@code COUSR02C} order. The password is optional on update (Constraint
     * C-003): a {@code null}/blank value leaves the stored BCrypt hash untouched,
     * while a supplied value is re-hashed via the injected {@link PasswordEncoder}
     * before persistence. No plaintext or hash is ever logged.</p>
     *
     * @param userId the eight-character user id being updated ({@code SEC-USR-ID}, path variable)
     * @param req    the update request (first/last name, optional password, user type)
     * @return a {@link UserResponse} whose {@code message} is
     *         {@code "User " + userId(trimmed) + " has been updated ..."}
     * @throws ResourceNotFoundException (HTTP&nbsp;404) if the user id does not exist
     * @throws ValidationException       (HTTP&nbsp;400) on the first blank mandatory field
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse updateUser(String userId, UserUpdateRequest req) {
        // Input-contract guard: reject a null request body up front with the typed
        // HTTP-400 first-mandatory-field edit, before any field access (and before
        // the existence read), rather than an unhandled NullPointerException / 500.
        if (req == null) {
            throw new ValidationException(MSG_FIRST_NAME_EMPTY);
        }

        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));

        requireNonBlank(req.firstName(), MSG_FIRST_NAME_EMPTY);
        requireNonBlank(req.lastName(), MSG_LAST_NAME_EMPTY);
        requireNonBlank(req.userType(), MSG_USER_TYPE_EMPTY);

        user.setSecUsrFname(req.firstName());
        user.setSecUsrLname(req.lastName());
        user.setSecUsrType(req.userType());
        if (req.password() != null && !req.password().isBlank()) {
            // Upper-case before hashing for logon parity (see createUser / Decision Log D-002).
            user.setSecUsrPwd(passwordEncoder.encode(req.password().toUpperCase(Locale.ROOT)));
        }
        UserSecurity saved = userSecurityRepository.save(user);

        String trimmedId = userId.trim();
        log.info("User {} has been updated", trimmedId);
        return toResponse(saved, "User " + trimmedId + " has been updated ...");
    }

    /**
     * Deletes an existing user (legacy {@code COUSR03C}, transaction {@code CU03}).
     *
     * <p>The user is read first so a missing key reproduces the
     * {@code DFHRESP(NOTFND)} branch as a {@link ResourceNotFoundException}; the
     * record is then removed by primary key. The returned confirmation carries
     * the deleted user's non-sensitive attributes (never a password) and the
     * verbatim {@code ' has been deleted ...'} success message.</p>
     *
     * @param userId the eight-character user id to delete ({@code SEC-USR-ID})
     * @return a {@link UserResponse} whose {@code message} is
     *         {@code "User " + userId(trimmed) + " has been deleted ..."}
     * @throws ResourceNotFoundException (HTTP&nbsp;404) if the user id does not exist
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse deleteUser(String userId) {
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));

        userSecurityRepository.deleteById(userId);

        String trimmedId = userId.trim();
        log.info("User {} has been deleted", trimmedId);
        return new UserResponse(
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType(),
                "User " + trimmedId + " has been deleted ...");
    }

    /**
     * Guards a mandatory field, reproducing a single COBOL empty-field
     * {@code EVALUATE} branch: raises a {@link ValidationException} carrying the
     * supplied verbatim literal when {@code value} is {@code null} or blank
     * (mirroring {@code = SPACES OR LOW-VALUES}).
     *
     * @param value   the field value to check
     * @param message the verbatim COBOL message to surface when the field is blank
     * @throws ValidationException (HTTP&nbsp;400) if {@code value} is {@code null} or blank
     */
    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(message);
        }
    }

    /**
     * Maps a {@link UserSecurity} entity to a {@link UserListItem} projection.
     * The password field is deliberately excluded and must remain excluded.
     *
     * @param user the source entity
     * @return the list-row projection (id, first name, last name, type)
     */
    private UserListItem toListItem(UserSecurity user) {
        return new UserListItem(
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType());
    }

    /**
     * Maps a {@link UserSecurity} entity to a {@link UserResponse}, attaching an
     * optional confirmation message. The password field is deliberately excluded
     * and must remain excluded.
     *
     * @param user    the source entity
     * @param message the optional confirmation/status message; {@code null} for a plain read
     * @return the response view (id, first name, last name, type, message)
     */
    private UserResponse toResponse(UserSecurity user, String message) {
        return new UserResponse(
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType(),
                message);
    }
}
