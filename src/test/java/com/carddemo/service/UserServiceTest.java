package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 * Pure, fast Mockito unit tests for {@link UserService}, the consolidated
 * user-administration service (online transactions {@code CU00}&ndash;{@code CU03})
 * migrated from the legacy CICS programs {@code COUSR00C}, {@code COUSR01C},
 * {@code COUSR02C} and {@code COUSR03C} (frozen COBOL reference at source commit
 * SHA {@code 27d6c6f}, read-only &mdash; not copied into this repository).
 *
 * <p>The two collaborators are supplied as Mockito mocks and the service is
 * constructor-injected via {@link InjectMocks}, so this suite bootstraps no
 * Spring context and touches no database, Testcontainers, Docker, or live AWS.
 * Every public method and branch is exercised, feeding the JaCoCo line-coverage
 * gate (Gate&nbsp;8).</p>
 *
 * <h2>Behavioural-parity assertions (Gate&nbsp;5)</h2>
 * <ul>
 *   <li><b>{@code COUSR01C} {@code PROCESS-ENTER-KEY} {@code EVALUATE TRUE}</b> &mdash;
 *       the mandatory-field edits are asserted in their exact COBOL order
 *       (first name, last name, user id, password, user type), each carrying the
 *       verbatim COBOL message literal, and the first-violated field is proven to
 *       win when several fields are blank.</li>
 *   <li><b>Duplicate / not-found branches</b> &mdash; the
 *       {@code DFHRESP(DUPKEY)}/{@code DUPREC} add path maps to
 *       {@link DuplicateResourceException} ("User ID already exist...") and the
 *       {@code DFHRESP(NOTFND)} read path maps to
 *       {@link ResourceNotFoundException} ("User ID NOT found...").</li>
 *   <li><b>{@code STRING ... DELIMITED BY SPACE}</b> &mdash; the add/update/delete
 *       confirmation messages are asserted verbatim, including the trimmed user id.</li>
 * </ul>
 *
 * <h2>Password handling (Constraint C-003 / Decision Log D-002)</h2>
 * <p>Create and update are proven to BCrypt-hash the plaintext password through
 * the injected {@link PasswordEncoder} (never storing the plaintext), and the
 * response DTOs ({@link UserResponse}, {@link UserListItem}) are proven &mdash;
 * reflectively, over their record components &mdash; to carry no password
 * component or value.</p>
 *
 * <p>Only {@link String}, {@code int} and {@code long} values are used; no
 * {@code float}/{@code double} appears anywhere, consistent with the migration's
 * decimal-fidelity constraints.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — COBOL COUSR00C/01C/02C/03C user-admin parity (SHA 27d6c6f)")
class UserServiceTest {

    // ------------------------------------------------------------------
    // Verbatim COBOL message literals (must match UserService constants).
    // ------------------------------------------------------------------

    /** Verbatim {@code COUSR01C} empty first-name message. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Verbatim {@code COUSR01C} empty last-name message. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Verbatim {@code COUSR01C} empty user-id message. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Verbatim {@code COUSR01C} empty password message. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Verbatim {@code COUSR01C} empty user-type message. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** Verbatim {@code COUSR01C} duplicate-key message. */
    private static final String MSG_DUPLICATE = "User ID already exist...";

    /** Verbatim {@code COUSR02C}/{@code COUSR03C} not-found message. */
    private static final String MSG_NOT_FOUND = "User ID NOT found...";

    /** A representative eight-character user id used across the CRUD tests. */
    private static final String USER_ID = "USER0001";

    /** Plaintext password supplied on create; must never be persisted or exposed. */
    private static final String PLAINTEXT_PASSWORD = "plain";

    /**
     * The (obviously fake) BCrypt-shaped hash the mock encoder returns for
     * {@link #PLAINTEXT_PASSWORD}. This is a test placeholder, never a real
     * credential, and is used to prove the stored value is the ENCODED result.
     */
    private static final String ENCODED_PASSWORD = "$2a$hash";

    // ------------------------------------------------------------------
    // Collaborators (mocks) and the class under test (constructor-injected).
    // ------------------------------------------------------------------

    /** Mocked repository over the {@code user_security} table. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Mocked Spring Security {@link PasswordEncoder} (library type, deliberately mocked). */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** The real service under test, with the two mocks injected via its single constructor. */
    @InjectMocks
    private UserService service;

    // ------------------------------------------------------------------
    // Fixture builders.
    // ------------------------------------------------------------------

    /**
     * Builds a {@link UserSecurity} entity fixture.
     *
     * @param id      the user id ({@code SEC-USR-ID})
     * @param fname   the first name ({@code SEC-USR-FNAME})
     * @param lname   the last name ({@code SEC-USR-LNAME})
     * @param pwdHash the stored BCrypt hash ({@code SEC-USR-PWD})
     * @param type    the user type ({@code SEC-USR-TYPE})
     * @return a populated {@link UserSecurity}
     */
    private static UserSecurity user(String id, String fname, String lname, String pwdHash, String type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrFname(fname);
        u.setSecUsrLname(lname);
        u.setSecUsrPwd(pwdHash);
        u.setSecUsrType(type);
        return u;
    }

    /**
     * Builds a fully valid {@link UserCreateRequest} (all mandatory fields
     * non-blank) whose id is {@link #USER_ID} and whose password is
     * {@link #PLAINTEXT_PASSWORD}. The record component order is
     * {@code (userId, firstName, lastName, password, userType)}.
     *
     * @return a valid create request
     */
    private static UserCreateRequest validCreateRequest() {
        return new UserCreateRequest(USER_ID, "John", "Doe", PLAINTEXT_PASSWORD, "U");
    }

    /**
     * Builds a valid {@link UserUpdateRequest} that supplies a new password (so
     * the service re-encodes it). Record component order is
     * {@code (firstName, lastName, password, userType)}.
     *
     * @return an update request carrying a new plaintext password
     */
    private static UserUpdateRequest updateRequestWithPassword() {
        return new UserUpdateRequest("Jane", "Roe", "newpass", "A");
    }

    /**
     * Builds a valid {@link UserUpdateRequest} that omits the password
     * ({@code null}), so the existing stored hash must be retained.
     *
     * @return an update request with no password change
     */
    private static UserUpdateRequest updateRequestNoPassword() {
        return new UserUpdateRequest("Jane", "Roe", null, "A");
    }

    /**
     * Wraps a list of entities in a Spring Data {@link Page} using a concrete
     * {@link PageImpl}, mirroring what {@code findAll(Pageable)} returns.
     *
     * @param content   the rows on this page
     * @param pageIndex the zero-based Spring page index
     * @param size      the page size
     * @param total     the total number of elements across all pages
     * @return a {@link Page} of {@link UserSecurity}
     */
    private static Page<UserSecurity> pageOf(List<UserSecurity> content, int pageIndex, int size, long total) {
        return new PageImpl<>(content, PageRequest.of(pageIndex, size), total);
    }

    /**
     * Reflectively asserts that a response {@code record} exposes no password:
     * no record component is named like a password, and no component value
     * equals any of the supplied secret strings (plaintext or hash).
     *
     * @param dto     the response record to inspect (for example
     *                {@link UserResponse} or {@link UserListItem})
     * @param secrets the sensitive values that must never appear in the response
     */
    private static void assertNoPasswordExposed(Object dto, String... secrets) {
        assertThat(dto).isNotNull();
        assertThat(dto.getClass().isRecord())
                .as("no-password guard only applies to response records")
                .isTrue();
        for (RecordComponent component : dto.getClass().getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .as("response component '%s' must not expose a password field", component.getName())
                    .doesNotContain("pwd")
                    .doesNotContain("pass");
            Object value = readComponent(dto, component);
            for (String secret : secrets) {
                assertThat(value)
                        .as("response component '%s' must never carry the password", component.getName())
                        .isNotEqualTo(secret);
            }
        }
    }

    /**
     * Invokes a record component accessor, translating reflective failures into
     * an {@link AssertionError} so the calling test fails cleanly.
     *
     * @param dto       the record instance
     * @param component the component whose accessor is invoked
     * @return the component value
     */
    private static Object readComponent(Object dto, RecordComponent component) {
        try {
            return component.getAccessor().invoke(dto);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read record component " + component.getName(), e);
        }
    }

    // ------------------------------------------------------------------
    // createUser — COUSR01C (CU01): field edits, duplicate, BCrypt, no leak.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] first blank mandatory field -> \"{1}\"")
    @MethodSource("emptyFieldCases")
    @DisplayName("createUser applies the COUSR01C empty-field edits in order; the first blank field wins")
    void createUser_emptyFields_throwInOrder(UserCreateRequest request, String expectedMessage) {
        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(expectedMessage);

        // Parity: the field edits run before any persistence or hashing, so a
        // rejected request touches neither the repository nor the encoder.
        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("createUser rejects a null request with the first mandatory-field edit before any field access (M5)")
    void createUser_nullRequest_throwsValidation() {
        // M5 (review finding): a null request body must be a typed HTTP-400
        // validation error, never an unhandled NullPointerException / HTTP 500.
        assertThatThrownBy(() -> service.createUser(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("createUser throws DuplicateResourceException (409) with the verbatim message on a pre-existing id")
    void createUser_duplicate_throwsDuplicate() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(validCreateRequest()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage(MSG_DUPLICATE);

        // The duplicate branch fires before hashing or persistence.
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("createUser BCrypt-encodes the password, persists only the hash, and returns a password-free confirmation")
    void createUser_success_encodesPasswordAndReturnsNoPassword() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
        // Enrollment hashes the UPPER-CASED plaintext so logon (which upper-cases before
        // BCrypt.matches) succeeds — COBOL COSGN00C case-insensitive compare, Decision Log D-002.
        when(passwordEncoder.encode(PLAINTEXT_PASSWORD.toUpperCase(Locale.ROOT))).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(returnsFirstArg());

        UserResponse response = service.createUser(validCreateRequest());

        // The persisted entity carries the BCrypt hash, never the plaintext (C-003).
        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity persisted = captor.getValue();
        assertThat(persisted.getSecUsrPwd()).isEqualTo(ENCODED_PASSWORD);
        assertThat(persisted.getSecUsrPwd()).isNotEqualTo(PLAINTEXT_PASSWORD);
        assertThat(persisted.getSecUsrId()).isEqualTo(USER_ID);
        verify(passwordEncoder).encode(PLAINTEXT_PASSWORD.toUpperCase(Locale.ROOT));

        // The confirmation reproduces the COUSR01C STRING statement verbatim.
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.lastName()).isEqualTo("Doe");
        assertThat(response.userType()).isEqualTo("U");
        assertThat(response.message()).isEqualTo("User " + USER_ID + " has been added ...");
        assertNoPasswordExposed(response, PLAINTEXT_PASSWORD, ENCODED_PASSWORD);
    }

    @Test
    @DisplayName("createUser trims the user id in the confirmation message (COBOL DELIMITED BY SPACE)")
    void createUser_success_trimsUserIdInMessage() {
        String paddedId = "USER01  ";
        when(userSecurityRepository.existsById(paddedId)).thenReturn(false);
        when(passwordEncoder.encode(PLAINTEXT_PASSWORD.toUpperCase(Locale.ROOT))).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(returnsFirstArg());

        UserResponse response = service.createUser(
                new UserCreateRequest(paddedId, "John", "Doe", PLAINTEXT_PASSWORD, "U"));

        assertThat(response.message()).isEqualTo("User USER01 has been added ...");
    }

    // ------------------------------------------------------------------
    // getUser — single-record read (COUSR02C/COUSR03C confirmation fetch).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getUser returns a password-free response with no confirmation message for an existing user")
    void getUser_found_returnsResponseNoPassword() {
        UserSecurity stored = user(USER_ID, "John", "Doe", ENCODED_PASSWORD, "U");
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(stored));

        UserResponse response = service.getUser(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.lastName()).isEqualTo("Doe");
        assertThat(response.userType()).isEqualTo("U");
        assertThat(response.message()).isNull();
        assertNoPasswordExposed(response, ENCODED_PASSWORD);
    }

    @Test
    @DisplayName("getUser throws ResourceNotFoundException (404) with the verbatim message when the id is unknown")
    void getUser_missing_throwsResourceNotFound() {
        when(userSecurityRepository.findById("NOSUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser("NOSUCH"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // updateUser — COUSR02C (CU02): read-modify-write, optional password.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateUser throws ResourceNotFoundException (404) and never persists when the id does not exist")
    void updateUser_missing_throwsResourceNotFound() {
        when(userSecurityRepository.findById("NOSUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUser("NOSUCH", updateRequestWithPassword()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND);

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("updateUser rejects a null request before the existence read, with the first mandatory-field edit (M5)")
    void updateUser_nullRequest_throwsValidation() {
        // M5 (review finding): a null request body is rejected as a typed HTTP-400
        // before any field access and before the existence read, so neither the
        // repository nor the encoder is touched.
        assertThatThrownBy(() -> service.updateUser(USER_ID, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser rejects a blank first name with the verbatim COUSR02C edit message")
    void updateUser_blankFirstName_throwsValidation() {
        when(userSecurityRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "Old", "Name", ENCODED_PASSWORD, "U")));

        UserUpdateRequest request = new UserUpdateRequest("   ", "Roe", "newpass", "A");

        assertThatThrownBy(() -> service.updateUser(USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("updateUser re-encodes and stores only the hash when a new password is supplied")
    void updateUser_success_encodesWhenPasswordProvided() {
        when(userSecurityRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "Old", "Name", "$2a$old", "U")));
        when(passwordEncoder.encode("newpass".toUpperCase(Locale.ROOT))).thenReturn("$2a$newhash");
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(returnsFirstArg());

        UserResponse response = service.updateUser(USER_ID, updateRequestWithPassword());

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity persisted = captor.getValue();
        assertThat(persisted.getSecUsrFname()).isEqualTo("Jane");
        assertThat(persisted.getSecUsrLname()).isEqualTo("Roe");
        assertThat(persisted.getSecUsrType()).isEqualTo("A");
        assertThat(persisted.getSecUsrPwd()).isEqualTo("$2a$newhash");
        assertThat(persisted.getSecUsrPwd()).isNotEqualTo("newpass");
        verify(passwordEncoder).encode("newpass".toUpperCase(Locale.ROOT));

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.message()).isEqualTo("User " + USER_ID + " has been updated ...");
        assertNoPasswordExposed(response, "newpass", "$2a$newhash", "$2a$old");
    }

    @Test
    @DisplayName("updateUser retains the stored hash and never calls the encoder when no password is supplied")
    void updateUser_noPassword_retainsExistingHash() {
        when(userSecurityRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "Old", "Name", "$2a$old", "U")));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(returnsFirstArg());

        UserResponse response = service.updateUser(USER_ID, updateRequestNoPassword());

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity persisted = captor.getValue();
        assertThat(persisted.getSecUsrPwd()).isEqualTo("$2a$old");
        assertThat(persisted.getSecUsrFname()).isEqualTo("Jane");
        assertThat(persisted.getSecUsrLname()).isEqualTo("Roe");
        assertThat(persisted.getSecUsrType()).isEqualTo("A");

        verifyNoInteractions(passwordEncoder);
        assertThat(response.message()).isEqualTo("User " + USER_ID + " has been updated ...");
        assertNoPasswordExposed(response, "$2a$old");
    }

    // ------------------------------------------------------------------
    // deleteUser — COUSR03C (CU03): read-then-delete, confirmation.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleteUser throws ResourceNotFoundException (404) and never deletes when the id is unknown")
    void deleteUser_missing_throwsResourceNotFound() {
        when(userSecurityRepository.findById("NOSUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser("NOSUCH"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND);

        verify(userSecurityRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("deleteUser removes the record by id and returns the verbatim deletion confirmation")
    void deleteUser_success_deletesAndConfirms() {
        when(userSecurityRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "John", "Doe", ENCODED_PASSWORD, "U")));

        UserResponse response = service.deleteUser(USER_ID);

        verify(userSecurityRepository).deleteById(USER_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.lastName()).isEqualTo("Doe");
        assertThat(response.userType()).isEqualTo("U");
        assertThat(response.message()).isEqualTo("User " + USER_ID + " has been deleted ...");
        assertNoPasswordExposed(response, ENCODED_PASSWORD);
    }

    // ------------------------------------------------------------------
    // listUsers — COUSR00C (CU00): 10-row pagination, no password in rows.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listUsers requests a 10-row page (COUSR00C browse), echoes the filter, and exposes no passwords")
    void listUsers_pageSizeIsTen() {
        List<UserSecurity> rows = List.of(
                user("USER0001", "John", "Doe", "$2a$h1", "U"),
                user("USER0002", "Jane", "Roe", "$2a$h2", "A"));
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(pageOf(rows, 0, 10, 2));

        UserListResponse response = service.listUsers("USER", 0);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userSecurityRepository).findAll(pageableCaptor.capture());
        Pageable requested = pageableCaptor.getValue();
        assertThat(requested.getPageSize()).isEqualTo(10);
        assertThat(requested.getPageNumber()).isZero();

        assertThat(response.userIdFilter()).isEqualTo("USER");
        assertThat(response.page().pageSize()).isEqualTo(10);
        assertThat(response.page().content()).hasSize(2);
        response.page().content()
                .forEach(item -> assertNoPasswordExposed(item, "$2a$h1", "$2a$h2"));
    }

    @Test
    @DisplayName("listUsers computes one-based, multi-page metadata for a middle page")
    void listUsers_multiPageMetadata_isComputedCorrectly() {
        List<UserSecurity> rows = List.of(
                user("USER0011", "Amy", "Ng", "$2a$h1", "U"),
                user("USER0012", "Bob", "Lo", "$2a$h2", "U"));
        // Spring page index 1 (the second page), size 10, 25 total rows.
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(pageOf(rows, 1, 10, 25));

        UserListResponse response = service.listUsers(null, 1);

        PageResponse<UserListItem> page = response.page();
        assertThat(page.pageNumber()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.first()).isFalse();
        assertThat(page.last()).isFalse();
        assertThat(response.userIdFilter()).isNull();
    }

    // ------------------------------------------------------------------
    // Parameterized data: COUSR01C empty-field EVALUATE-TRUE order. Each case
    // supplies progressively more valid fields, mixing null / "" / "   " to
    // exercise the null-or-blank guard and prove the first-violated field wins.
    // Record component order is (userId, firstName, lastName, password, userType).
    // ------------------------------------------------------------------

    private static Stream<Arguments> emptyFieldCases() {
        return Stream.of(
                // Every field blank -> the first edit (first name) wins.
                Arguments.of(new UserCreateRequest(null, null, null, null, null), MSG_FIRST_NAME_EMPTY),
                // First name supplied, last name blank ("   ") -> last-name edit.
                Arguments.of(new UserCreateRequest(USER_ID, "John", "   ", "plain", "U"), MSG_LAST_NAME_EMPTY),
                // First + last supplied, user id blank ("") -> user-id edit.
                Arguments.of(new UserCreateRequest("", "John", "Doe", "plain", "U"), MSG_USER_ID_EMPTY),
                // First + last + id supplied, password blank ("") -> password edit.
                Arguments.of(new UserCreateRequest(USER_ID, "John", "Doe", "", "U"), MSG_PASSWORD_EMPTY),
                // First + last + id + password supplied, user type null -> user-type edit.
                Arguments.of(new UserCreateRequest(USER_ID, "John", "Doe", "plain", null), MSG_USER_TYPE_EMPTY));
    }
}
