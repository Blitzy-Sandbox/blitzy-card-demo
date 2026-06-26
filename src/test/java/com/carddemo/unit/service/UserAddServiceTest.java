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
package com.carddemo.unit.service;

import java.lang.reflect.RecordComponent;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserAddService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link UserAddService}, the Java
 * realization of the CICS pseudo-conversational user-add program
 * {@code COUSR01C} (transaction {@code CU01}, "Add a new Regular/Admin user to
 * USRSEC file") @ {@code 27d6c6f} (AAP &sect;0.4.1.1).
 *
 * <p>The legacy program validated the screen fields in {@code PROCESS-ENTER-KEY}
 * via an {@code EVALUATE TRUE} short-circuit (first name, last name, user id,
 * password, user type) and then issued an {@code EXEC CICS WRITE} of
 * {@code SEC-USER-DATA} keyed on {@code SEC-USR-ID} (paragraph
 * {@code WRITE-USER-SEC-FILE}). The Java service collapses those two paragraphs
 * into a single transactional {@link UserAddService#addUser(UserDto.CreateRequest)}
 * call: the ordered empty-field cascade, a duplicate-key guard, BCrypt password
 * hashing (constraint C-003), and the keyed insert.</p>
 *
 * <p>The suite is framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong>
 * {@code @SpringBootTest}, {@code MockMvc}, Testcontainers, or live database, and
 * touches no AWS or network resource. The two collaborators,
 * {@link UserRepository} and {@link PasswordEncoder}, are Mockito-mocked and
 * injected via {@link InjectMocks} through the service's constructor, so the
 * complete add contract is asserted in isolation. {@link MockitoExtension} runs
 * with strict stubbing, so each test stubs only the interactions it exercises.</p>
 *
 * <p>Exception detail messages are asserted <strong>verbatim</strong>; they
 * form part of the observable, byte-equivalent behavior covered by
 * Gate&nbsp;1 and Gate&nbsp;4. The five ordered empty-field literals, the
 * duplicate literal, and the add-failure literal are reproduced exactly from the
 * compiled {@link UserAddService} (themselves byte-exact copies of the
 * {@code COUSR01C} {@code PROCESS-ENTER-KEY} / {@code WRITE-USER-SEC-FILE}
 * working-storage literals @ {@code 27d6c6f}).</p>
 *
 * <p>The credential-safety guarantees are asserted explicitly: the raw password
 * is BCrypt-encoded <em>before</em> persistence and only the encoded hash is ever
 * written (proved with an {@link ArgumentCaptor}), and the returned
 * {@link UserDto.UserSummary} structurally carries no password component (proved
 * by reflection over the record).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService — COUSR01C (CU01) add-user @ 27d6c6f")
class UserAddServiceTest {

    /** Display first name ({@code SEC-USR-FNAME} / {@code FNAME X(20)}). */
    private static final String FIRST_NAME = "John";

    /** Display last name ({@code SEC-USR-LNAME} / {@code LNAME X(20)}). */
    private static final String LAST_NAME = "Doe";

    /** The 8-character {@code SEC-USR-ID} primary key ({@code USERID X(8)}). */
    private static final String USER_ID = "USER0009";

    /**
     * The plaintext credential supplied on the wire ({@code PASSWD X(8)}). It is
     * a synthetic, non-secret value used to assert it is BCrypt-encoded
     * before persistence and never stored or returned in the clear.
     */
    private static final String RAW_PASSWORD = "rawpass";

    /**
     * The submitted password after the service normalizes it to upper case
     * ({@code request.password().toUpperCase(Locale.ROOT)}) immediately before BCrypt
     * encoding, mirroring the legacy {@code FUNCTION UPPER-CASE} treatment of credentials.
     * The mocked encoder is stubbed and verified against this normalized value.
     */
    private static final String NORMALIZED_PASSWORD = "RAWPASS";

    /**
     * The (fake) BCrypt-shaped hash the mocked {@link PasswordEncoder} returns for
     * {@link #RAW_PASSWORD}. Not a real credential and matching no live account;
     * constraint C-003 widens the legacy {@code SEC-USR-PWD X(08)} to a BCrypt
     * hash stored in {@code VARCHAR(60)}.
     */
    private static final String ENCODED_PASSWORD = "$2a$bcrypthash";

    /** Role discriminator ({@code SEC-USR-TYPE} / {@code USRTYPE X(1)}): {@code U} = regular user. */
    private static final String USER_TYPE = "U";

    /** Byte-exact {@code COUSR01C} empty first-name message @ {@code 27d6c6f}. */
    private static final String FIRST_NAME_EMPTY_MESSAGE = "First Name can NOT be empty...";

    /** Byte-exact {@code COUSR01C} empty last-name message @ {@code 27d6c6f}. */
    private static final String LAST_NAME_EMPTY_MESSAGE = "Last Name can NOT be empty...";

    /** Byte-exact {@code COUSR01C} empty user-id message @ {@code 27d6c6f}. */
    private static final String USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /** Byte-exact {@code COUSR01C} empty password message @ {@code 27d6c6f}. */
    private static final String PASSWORD_EMPTY_MESSAGE = "Password can NOT be empty...";

    /** Byte-exact {@code COUSR01C} empty user-type message @ {@code 27d6c6f}. */
    private static final String USER_TYPE_EMPTY_MESSAGE = "User Type can NOT be empty...";

    /** Byte-exact {@code COUSR01C} {@code DUPKEY}/{@code DUPREC} message @ {@code 27d6c6f}. */
    private static final String USER_ALREADY_EXISTS_MESSAGE = "User ID already exist...";

    /** Byte-exact {@code COUSR01C} {@code WHEN OTHER} add-failure message @ {@code 27d6c6f}. */
    private static final String ADD_FAILURE_MESSAGE = "Unable to Add User...";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserAddService userAddService;

    /**
     * Builds a fully populated, valid {@link UserDto.CreateRequest} matching the
     * COUSR01 field contract ({@code FNAME}, {@code LNAME}, {@code USERID},
     * {@code PASSWD}, {@code USRTYPE}).
     *
     * @return a valid create request keyed by {@link #USER_ID}
     */
    private static UserDto.CreateRequest validRequest() {
        return new UserDto.CreateRequest(FIRST_NAME, LAST_NAME, USER_ID, RAW_PASSWORD, USER_TYPE);
    }

    /**
     * Asserts that {@code addUser} rejects the supplied request with a
     * {@link ValidationException} whose detail message is the byte-exact legacy
     * literal and whose field-error map carries exactly the single
     * {@code field -> message} entry the service builds, and that the rejection
     * short-circuits before any password hashing or persistence.
     *
     * @param request the create request expected to be rejected (may be
     *                {@code null} to exercise the null-request branch)
     * @param field   the logical field name expected in the single-entry
     *                field-error map
     * @param message the byte-exact legacy message expected on the exception
     */
    private void assertEmptyFieldRejected(UserDto.CreateRequest request, String field, String message) {
        Throwable thrown = catchThrowable(() -> userAddService.addUser(request));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(message);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsExactly(entry(field, message));

        // The ordered validation cascade short-circuits before any VSAM-equivalent
        // write or credential hashing, exactly as the COBOL EVALUATE TRUE did.
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    // -----------------------------------------------------------------
    // Phase 1 — successful add + BCrypt encoding (credential safety)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("addUser: a valid request returns a UserSummary carrying userId/firstName/lastName/userType")
    void addUserReturnsUserSummaryWithDisplayFields() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(NORMALIZED_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        UserDto.UserSummary summary = userAddService.addUser(validRequest());

        assertThat(summary).isNotNull();
        assertThat(summary.userId()).isEqualTo(USER_ID);
        assertThat(summary.firstName()).isEqualTo(FIRST_NAME);
        assertThat(summary.lastName()).isEqualTo(LAST_NAME);
        assertThat(summary.userType()).isEqualTo(USER_TYPE);
    }

    @Test
    @DisplayName("addUser: persists the BCrypt hash and NEVER the plaintext password (ArgumentCaptor)")
    void addUserPersistsBcryptHashNeverPlaintext() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(NORMALIZED_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        userAddService.addUser(validRequest());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User persisted = captor.getValue();

        // The stored credential is the ENCODED hash, never the raw plaintext.
        assertThat(persisted.getPassword()).isEqualTo(ENCODED_PASSWORD);
        assertThat(persisted.getPassword()).isNotEqualTo(RAW_PASSWORD);
        // The remaining fields are carried through to the SEC-USER-DATA record verbatim.
        assertThat(persisted.getUserId()).isEqualTo(USER_ID);
        assertThat(persisted.getFirstName()).isEqualTo(FIRST_NAME);
        assertThat(persisted.getLastName()).isEqualTo(LAST_NAME);
        assertThat(persisted.getUserType()).isEqualTo(USER_TYPE);
    }

    @Test
    @DisplayName("addUser: checks for duplicates, then hashes, then writes — in that order (encode BEFORE save)")
    void addUserChecksDuplicateThenEncodesThenSaves() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(NORMALIZED_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        userAddService.addUser(validRequest());

        // Reproduces the COUSR01C order: duplicate guard -> hash credential -> WRITE.
        // Encoding MUST precede persistence so a plaintext credential is never written.
        InOrder inOrder = inOrder(userRepository, passwordEncoder);
        inOrder.verify(userRepository).existsById(USER_ID);
        inOrder.verify(passwordEncoder).encode(NORMALIZED_PASSWORD);
        inOrder.verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("UserSummary: exposes exactly userId/firstName/lastName/userType — no password accessor")
    void userSummaryNeverExposesPassword() {
        RecordComponent[] components = UserDto.UserSummary.class.getRecordComponents();

        assertThat(components)
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "firstName", "lastName", "userType")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("addUser: trims the user id (DELIMITED BY SPACE parity) before lookup, write, and response")
    void addUserTrimsUserId() {
        UserDto.CreateRequest padded =
                new UserDto.CreateRequest(FIRST_NAME, LAST_NAME, "  " + USER_ID + "  ", RAW_PASSWORD, USER_TYPE);
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(NORMALIZED_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        UserDto.UserSummary summary = userAddService.addUser(padded);

        // The trimmed key is used for the duplicate lookup ...
        verify(userRepository).existsById(USER_ID);
        // ... for the persisted record ...
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        // ... and for the response summary.
        assertThat(summary.userId()).isEqualTo(USER_ID);
    }

    // -----------------------------------------------------------------
    // Phase 2 — ordered empty-field validation (PROCESS-ENTER-KEY parity)
    //
    // Each test leaves the target field blank with every PRECEDING field
    // valid and every FOLLOWING field also blank, proving the EVALUATE TRUE
    // short-circuit stops at the first absent field in the legacy order.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("addUser: a blank first name is rejected FIRST with the COUSR01C first-name message")
    void addUserRejectsBlankFirstNameFirst() {
        assertEmptyFieldRejected(
                new UserDto.CreateRequest("", "", "", "", ""),
                "firstName",
                FIRST_NAME_EMPTY_MESSAGE);
    }

    @Test
    @DisplayName("addUser: with a valid first name, a blank last name is rejected SECOND")
    void addUserRejectsBlankLastNameSecond() {
        assertEmptyFieldRejected(
                new UserDto.CreateRequest(FIRST_NAME, "", "", "", ""),
                "lastName",
                LAST_NAME_EMPTY_MESSAGE);
    }

    @Test
    @DisplayName("addUser: with valid first and last names, a blank user id is rejected THIRD")
    void addUserRejectsBlankUserIdThird() {
        assertEmptyFieldRejected(
                new UserDto.CreateRequest(FIRST_NAME, LAST_NAME, "", "", ""),
                "userId",
                USER_ID_EMPTY_MESSAGE);
    }

    @Test
    @DisplayName("addUser: with valid names and user id, a blank password is rejected FOURTH")
    void addUserRejectsBlankPasswordFourth() {
        assertEmptyFieldRejected(
                new UserDto.CreateRequest(FIRST_NAME, LAST_NAME, USER_ID, "", ""),
                "password",
                PASSWORD_EMPTY_MESSAGE);
    }

    @Test
    @DisplayName("addUser: with the first four fields valid, a blank user type is rejected FIFTH (last)")
    void addUserRejectsBlankUserTypeFifth() {
        assertEmptyFieldRejected(
                new UserDto.CreateRequest(FIRST_NAME, LAST_NAME, USER_ID, RAW_PASSWORD, ""),
                "userType",
                USER_TYPE_EMPTY_MESSAGE);
    }

    @Test
    @DisplayName("addUser: a null request is treated as a missing first name (first-field semantics, no NPE)")
    void addUserRejectsNullRequestAsMissingFirstName() {
        assertEmptyFieldRejected(null, "firstName", FIRST_NAME_EMPTY_MESSAGE);
    }

    @ParameterizedTest(name = "firstName=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("addUser: null/empty/blank first name -> ValidationException, no hashing or persistence")
    void addUserRejectsNullEmptyOrBlankFirstName(String blankFirstName) {
        assertEmptyFieldRejected(
                new UserDto.CreateRequest(blankFirstName, LAST_NAME, USER_ID, RAW_PASSWORD, USER_TYPE),
                "firstName",
                FIRST_NAME_EMPTY_MESSAGE);
    }

    // -----------------------------------------------------------------
    // Phase 3 — duplicate user id (WRITE-USER-SEC-FILE DUPKEY/DUPREC parity)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("addUser: an existing user id is rejected as a duplicate; nothing is hashed or written")
    void addUserRejectsDuplicateUserId() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);

        Throwable thrown = catchThrowable(() -> userAddService.addUser(validRequest()));

        assertThat(thrown)
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage(USER_ALREADY_EXISTS_MESSAGE);

        // The duplicate guard fires after validation but BEFORE hashing/persisting.
        verify(userRepository).existsById(USER_ID);
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any(User.class));
    }

    // -----------------------------------------------------------------
    // Phase 4 — persistence-failure translation (WRITE-USER-SEC-FILE statuses)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("addUser: a generic data-access failure on save is translated to FileAccessException ('Unable to Add User...')")
    void addUserTranslatesDataAccessFailureToFileAccessException() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(NORMALIZED_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        DataAccessResourceFailureException dataAccessFailure =
                new DataAccessResourceFailureException("simulated data-store connection failure");
        when(userRepository.save(any(User.class))).thenThrow(dataAccessFailure);

        assertThatThrownBy(() -> userAddService.addUser(validRequest()))
                .isInstanceOf(FileAccessException.class)
                .hasMessage(ADD_FAILURE_MESSAGE)
                .hasCause(dataAccessFailure);

        // The credential was still hashed before the failing write (plaintext never
        // reaches the store, even on the failure path).
        verify(passwordEncoder).encode(NORMALIZED_PASSWORD);
    }

    @Test
    @DisplayName("addUser: a DataIntegrityViolationException on save surfaces as a duplicate (DUPKEY/DUPREC parity)")
    void addUserTranslatesIntegrityViolationToDuplicate() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(NORMALIZED_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        DataIntegrityViolationException integrityViolation =
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_pkey\"");
        when(userRepository.save(any(User.class))).thenThrow(integrityViolation);

        // A unique-key race that slips past the existsById pre-check is still mapped
        // to the same byte-exact duplicate message the COBOL WRITE produced.
        assertThatThrownBy(() -> userAddService.addUser(validRequest()))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage(USER_ALREADY_EXISTS_MESSAGE)
                .hasCause(integrityViolation);
    }
}
