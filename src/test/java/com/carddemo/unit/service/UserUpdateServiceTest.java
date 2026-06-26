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
import java.util.Optional;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserUpdateService;

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
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link UserUpdateService}, the Java
 * realization of the CICS pseudo-conversational user-update program
 * {@code COUSR02C} (transaction {@code CU02}) @ {@code 27d6c6f} (AAP
 * &sect;0.4.1.1).
 *
 * <p>The legacy program validated the editable fields (paragraph
 * {@code UPDATE-USER-INFO}) in a fixed short-circuit order, performed a keyed
 * {@code READ ... UPDATE} of the {@code USRSEC} record (paragraph
 * {@code READ-USER-SEC-FILE}), applied the changed fields, and rewrote the
 * record (paragraph {@code UPDATE-USER-SEC-FILE}, {@code REWRITE}). The Java
 * service collapses that read-then-rewrite into a single transactional
 * <em>validate-fetch-apply-save</em>.</p>
 *
 * <p><strong>Conditional BCrypt (constraint C-003).</strong> The legacy
 * mandatory {@code 'Password can NOT be empty...'} edit is deliberately relaxed:
 * the password is optional on {@link UserDto.UpdateRequest}. When a non-blank
 * password is supplied it is re-encoded through the injected
 * {@link PasswordEncoder}; when it is {@code null} or blank the stored BCrypt
 * hash is preserved unchanged and {@link PasswordEncoder#encode(CharSequence)}
 * is never invoked. Both directions are asserted here.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong>
 * {@code @SpringBootTest}, {@code MockMvc}, Testcontainers, or live database, and
 * touches no AWS or network resource. The collaborators
 * {@link UserRepository} and {@link PasswordEncoder} are Mockito-mocked and
 * injected via {@link InjectMocks}, so the complete update contract is asserted
 * in isolation. {@link MockitoExtension} runs with strict stubbing, so each test
 * stubs only the interactions it exercises.</p>
 *
 * <p>Because {@link User} carries no {@code @Version} attribute there is no
 * optimistic-locking branch (unlike the account and card update services), so no
 * concurrency-conflict scenario is exercised. Exception detail messages are
 * asserted <strong>verbatim</strong> because they form part of the observable,
 * byte-equivalent behavior guarded by Gate&nbsp;1 and Gate&nbsp;4; the literals
 * are reproduced exactly from the compiled {@link UserUpdateService} (themselves
 * byte-exact copies of the {@code COUSR02C} working-storage literals @
 * {@code 27d6c6f}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService — COUSR02C (CU02) validate-fetch-apply-save @ 27d6c6f")
class UserUpdateServiceTest {

    /** The 8-character {@code SEC-USR-ID} key of the user under test ({@code USRIDIN X(8)}). */
    private static final String USER_ID = "USER0001";

    /** Original first name on the stored record ({@code SEC-USR-FNAME} / {@code FNAME X(20)}). */
    private static final String ORIGINAL_FIRST_NAME = "John";

    /** Original last name on the stored record ({@code SEC-USR-LNAME} / {@code LNAME X(20)}). */
    private static final String ORIGINAL_LAST_NAME = "Doe";

    /** Original role discriminator on the stored record ({@code SEC-USR-TYPE} / {@code USRTYPE X(1)}): {@code U} = regular user. */
    private static final String ORIGINAL_USER_TYPE = "U";

    /** Updated first name carried by the request. */
    private static final String UPDATED_FIRST_NAME = "Jane";

    /** Updated last name carried by the request. */
    private static final String UPDATED_LAST_NAME = "Smith";

    /** Updated role discriminator carried by the request ({@code A} = admin). */
    private static final String UPDATED_USER_TYPE = "A";

    /**
     * Synthetic, non-secret BCrypt-shaped placeholder representing the hash
     * already stored on the fetched record. It is an obvious fake (it matches no
     * real BCrypt regex) and no live credential; constraint C-003 widens the
     * legacy {@code SEC-USR-PWD X(08)} to a BCrypt hash.
     */
    private static final String OLD_PASSWORD_HASH = "$2a$oldhash";

    /** The plaintext password supplied on the wire when the credential is being changed. */
    private static final String NEW_PASSWORD_PLAINTEXT = "newpass";

    /**
     * The supplied password after the service normalizes it to upper case
     * ({@code request.password().toUpperCase(Locale.ROOT)}) immediately before BCrypt
     * encoding, mirroring the legacy {@code FUNCTION UPPER-CASE} treatment of credentials.
     * The mocked encoder is stubbed and verified against this normalized value.
     */
    private static final String NORMALIZED_NEW_PASSWORD = "NEWPASS";

    /**
     * Synthetic, non-secret BCrypt-shaped placeholder returned by the mocked
     * {@link PasswordEncoder} for {@link #NEW_PASSWORD_PLAINTEXT}. An obvious
     * fake that matches no real BCrypt regex and no live credential.
     */
    private static final String NEW_PASSWORD_HASH = "$2a$newhash";

    /** Byte-exact {@code COUSR02C} empty-identifier message @ {@code 27d6c6f}. */
    private static final String USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /** Byte-exact {@code COUSR02C} empty-first-name message @ {@code 27d6c6f}. */
    private static final String FIRST_NAME_EMPTY_MESSAGE = "First Name can NOT be empty...";

    /** Byte-exact {@code COUSR02C} empty-last-name message @ {@code 27d6c6f}. */
    private static final String LAST_NAME_EMPTY_MESSAGE = "Last Name can NOT be empty...";

    /** Byte-exact {@code COUSR02C} empty-user-type message @ {@code 27d6c6f}. */
    private static final String USER_TYPE_EMPTY_MESSAGE = "User Type can NOT be empty...";

    /** Byte-exact {@code COUSR02C} record-not-found message @ {@code 27d6c6f}. */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserUpdateService userUpdateService;

    /**
     * Builds the stored security user the {@code READ-USER-SEC-FILE} step would
     * have fetched: the original field values plus the existing BCrypt hash.
     *
     * @return a populated {@link User} keyed by {@link #USER_ID}
     */
    private static User storedUser() {
        return new User(USER_ID, ORIGINAL_FIRST_NAME, ORIGINAL_LAST_NAME, OLD_PASSWORD_HASH, ORIGINAL_USER_TYPE);
    }

    /**
     * Builds an update request that changes the name and type and carries the
     * supplied password value (which may be {@code null} or blank to model "no
     * password change").
     *
     * @param password the password value to place on the request
     * @return a populated {@link UserDto.UpdateRequest}
     */
    private static UserDto.UpdateRequest updateRequest(String password) {
        return new UserDto.UpdateRequest(
                USER_ID, UPDATED_FIRST_NAME, UPDATED_LAST_NAME, password, UPDATED_USER_TYPE);
    }

    // -----------------------------------------------------------------
    // Phase 1 — update WITH a new password (conditional BCrypt: encode IS called)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updateUser: a supplied password is BCrypt-encoded and the new hash is persisted")
    void updateUserWithNewPasswordEncodesAndPersistsNewHash() {
        User stored = storedUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(passwordEncoder.encode(NORMALIZED_NEW_PASSWORD)).thenReturn(NEW_PASSWORD_HASH);
        // The service mutates the fetched entity in place and saves it; returning
        // that same reference lets the summary reflect the applied changes.
        when(userRepository.save(any(User.class))).thenReturn(stored);

        UserDto.UserSummary summary =
                userUpdateService.updateUser(USER_ID, updateRequest(NEW_PASSWORD_PLAINTEXT));

        // The credential change is applied through the encoder, never stored raw.
        verify(passwordEncoder).encode(NORMALIZED_NEW_PASSWORD);

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        User saved = savedCaptor.getValue();
        assertThat(saved.getPassword()).isEqualTo(NEW_PASSWORD_HASH);
        assertThat(saved.getFirstName()).isEqualTo(UPDATED_FIRST_NAME);
        assertThat(saved.getLastName()).isEqualTo(UPDATED_LAST_NAME);
        assertThat(saved.getUserType()).isEqualTo(UPDATED_USER_TYPE);

        // The returned summary echoes the updated display fields and never the password.
        assertThat(summary).isNotNull();
        assertThat(summary.userId()).isEqualTo(USER_ID);
        assertThat(summary.firstName()).isEqualTo(UPDATED_FIRST_NAME);
        assertThat(summary.lastName()).isEqualTo(UPDATED_LAST_NAME);
        assertThat(summary.userType()).isEqualTo(UPDATED_USER_TYPE);
    }

    @Test
    @DisplayName("updateUser: applies READ -> encode -> REWRITE in the COUSR02C order")
    void updateUserAppliesReadEncodeSaveInOrder() {
        User stored = storedUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(passwordEncoder.encode(NORMALIZED_NEW_PASSWORD)).thenReturn(NEW_PASSWORD_HASH);
        when(userRepository.save(any(User.class))).thenReturn(stored);

        userUpdateService.updateUser(USER_ID, updateRequest(NEW_PASSWORD_PLAINTEXT));

        // READ-USER-SEC-FILE -> apply/encode -> UPDATE-USER-SEC-FILE (REWRITE).
        InOrder ordered = inOrder(userRepository, passwordEncoder);
        ordered.verify(userRepository).findById(USER_ID);
        ordered.verify(passwordEncoder).encode(NORMALIZED_NEW_PASSWORD);
        ordered.verify(userRepository).save(stored);
        ordered.verifyNoMoreInteractions();
    }

    // -----------------------------------------------------------------
    // Phase 2 — update WITHOUT a password (conditional BCrypt: encode is NOT called)
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "password=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("updateUser: a null/blank password leaves the stored hash untouched and never calls encode")
    void updateUserWithoutPasswordPreservesExistingHash(String blankPassword) {
        User stored = storedUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(userRepository.save(any(User.class))).thenReturn(stored);

        UserDto.UserSummary summary =
                userUpdateService.updateUser(USER_ID, updateRequest(blankPassword));

        // No re-hash occurs when the password is absent.
        verify(passwordEncoder, never()).encode(any());

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        User saved = savedCaptor.getValue();
        assertThat(saved.getPassword()).isEqualTo(OLD_PASSWORD_HASH);
        // The other editable fields are still applied.
        assertThat(saved.getFirstName()).isEqualTo(UPDATED_FIRST_NAME);
        assertThat(saved.getLastName()).isEqualTo(UPDATED_LAST_NAME);
        assertThat(saved.getUserType()).isEqualTo(UPDATED_USER_TYPE);

        assertThat(summary.userId()).isEqualTo(USER_ID);
        assertThat(summary.firstName()).isEqualTo(UPDATED_FIRST_NAME);
        assertThat(summary.lastName()).isEqualTo(UPDATED_LAST_NAME);
        assertThat(summary.userType()).isEqualTo(UPDATED_USER_TYPE);
    }

    // -----------------------------------------------------------------
    // Phase 3 — validation / not-found (verbatim messages; nothing persisted)
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "userId=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("updateUser: null/empty/blank userId -> ValidationException, no repository or encoder access")
    void updateUserRejectsBlankUserId(String blankUserId) {
        assertThatThrownBy(() -> userUpdateService.updateUser(blankUserId, updateRequest(NEW_PASSWORD_PLAINTEXT)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(USER_ID_EMPTY_MESSAGE);

        // The empty-identifier guard short-circuits before any VSAM-equivalent access.
        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser: blank first name -> ValidationException, no repository or encoder access")
    void updateUserRejectsBlankFirstName() {
        UserDto.UpdateRequest request = new UserDto.UpdateRequest(
                USER_ID, "", UPDATED_LAST_NAME, NEW_PASSWORD_PLAINTEXT, UPDATED_USER_TYPE);

        assertThatThrownBy(() -> userUpdateService.updateUser(USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(FIRST_NAME_EMPTY_MESSAGE);

        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser: blank last name -> ValidationException, no repository or encoder access")
    void updateUserRejectsBlankLastName() {
        UserDto.UpdateRequest request = new UserDto.UpdateRequest(
                USER_ID, UPDATED_FIRST_NAME, "", NEW_PASSWORD_PLAINTEXT, UPDATED_USER_TYPE);

        assertThatThrownBy(() -> userUpdateService.updateUser(USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(LAST_NAME_EMPTY_MESSAGE);

        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser: blank user type -> ValidationException, no repository or encoder access")
    void updateUserRejectsBlankUserType() {
        UserDto.UpdateRequest request = new UserDto.UpdateRequest(
                USER_ID, UPDATED_FIRST_NAME, UPDATED_LAST_NAME, NEW_PASSWORD_PLAINTEXT, "");

        assertThatThrownBy(() -> userUpdateService.updateUser(USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(USER_TYPE_EMPTY_MESSAGE);

        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser: unknown userId -> RecordNotFoundException, nothing is persisted")
    void updateUserRejectsUnknownUserId() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userUpdateService.updateUser(USER_ID, updateRequest(NEW_PASSWORD_PLAINTEXT)))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(USER_NOT_FOUND_MESSAGE);

        verify(userRepository).findById(USER_ID);
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    // -----------------------------------------------------------------
    // Contract — UserSummary structurally never exposes the password
    // -----------------------------------------------------------------

    @Test
    @DisplayName("UserSummary: exposes exactly userId/firstName/lastName/userType — no password accessor")
    void userSummaryExposesOnlyDisplayFieldsAndNeverThePassword() {
        RecordComponent[] components = UserDto.UserSummary.class.getRecordComponents();

        assertThat(components)
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "firstName", "lastName", "userType")
                .doesNotContain("password");
    }
}
