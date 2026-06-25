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
import com.carddemo.service.UserDeleteService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link UserDeleteService}, the Java
 * realization of the CICS pseudo-conversational user-delete program
 * {@code COUSR03C} (transaction {@code CU03}) @ {@code 27d6c6f} (AAP
 * &sect;0.4.1.1).
 *
 * <p>The legacy program performed a keyed {@code READ ... UPDATE} of the
 * {@code USRSEC} record (paragraph {@code READ-USER-SEC-FILE}) followed by an
 * {@code EXEC CICS DELETE} (paragraph {@code DELETE-USER-SEC-FILE}); the Java
 * service collapses that read-then-delete into a single transactional
 * <em>fetch-then-delete</em>. The user's display fields are captured into the
 * returned {@link UserDto.DeleteResponse} <strong>before</strong> the record is
 * removed, and the password is structurally never exposed on that 4-component
 * response record.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong>
 * {@code @SpringBootTest}, {@code MockMvc}, Testcontainers, or live database, and
 * touches no AWS or network resource. The single collaborator,
 * {@link UserRepository}, is Mockito-mocked and injected via
 * {@link InjectMocks}, so the complete delete contract is asserted in isolation.
 * {@link MockitoExtension} runs with strict stubbing, so each test stubs only the
 * interactions it exercises.</p>
 *
 * <p>Exception detail messages are asserted <strong>verbatim</strong> because
 * they form part of the observable, byte-equivalent behavior guarded by
 * Gate&nbsp;1 and Gate&nbsp;4. The literals are reproduced exactly from the
 * compiled {@link UserDeleteService} (themselves byte-exact copies of the
 * {@code COUSR03C} {@code 'User ID can NOT be empty...'} and
 * {@code 'User ID NOT found...'} working-storage literals @ {@code 27d6c6f}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService — COUSR03C (CU03) fetch-then-delete @ 27d6c6f")
class UserDeleteServiceTest {

    /** The 8-character {@code SEC-USR-ID} key of the user under test ({@code USRIDIN X(8)}). */
    private static final String USER_ID = "USER0001";

    /** Display first name ({@code SEC-USR-FNAME} / {@code FNAME X(20)}). */
    private static final String FIRST_NAME = "John";

    /** Display last name ({@code SEC-USR-LNAME} / {@code LNAME X(20)}). */
    private static final String LAST_NAME = "Doe";

    /** Role discriminator ({@code SEC-USR-TYPE} / {@code USRTYPE X(1)}): {@code U} = regular user. */
    private static final String USER_TYPE = "U";

    /**
     * A synthetic, non-secret BCrypt-shaped value used only to populate the
     * fetched {@link User} so the tests can demonstrate that the credential is
     * never surfaced on the {@link UserDto.DeleteResponse}. It is not a real
     * credential and matches no live account (constraint C-003 widens the legacy
     * {@code SEC-USR-PWD X(08)} to a BCrypt hash).
     */
    private static final String SYNTHETIC_BCRYPT_HASH =
            "$2a$10$0123456789012345678901uORnabcDEFghiJKLmnoPQRstuVWxyZ012";

    /** Byte-exact {@code COUSR03C} empty-identifier message @ {@code 27d6c6f}. */
    private static final String USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /** Byte-exact {@code COUSR03C} record-not-found message @ {@code 27d6c6f}. */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDeleteService userDeleteService;

    /**
     * Builds a fully populated security user matching the {@code SEC-USER-DATA}
     * layout, including a (synthetic) password to prove it is never exposed.
     *
     * @return a populated {@link User} keyed by {@link #USER_ID}
     */
    private static User sampleUser() {
        return new User(USER_ID, FIRST_NAME, LAST_NAME, SYNTHETIC_BCRYPT_HASH, USER_TYPE);
    }

    // -----------------------------------------------------------------
    // Phase 1 — successful delete (COUSR03C DELETE-USER-INFO happy path)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("deleteUser: returns the deleted user's four display fields (no password)")
    void deleteUserReturnsDisplayFieldsOfDeletedUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser()));

        UserDto.DeleteResponse response = userDeleteService.deleteUser(USER_ID);

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo(FIRST_NAME);
        assertThat(response.lastName()).isEqualTo(LAST_NAME);
        assertThat(response.userType()).isEqualTo(USER_TYPE);
    }

    @Test
    @DisplayName("deleteUser: fetches the record BEFORE deleting it, deleting the fetched entity")
    void deleteUserFetchesBeforeDeleting() {
        User fetched = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(fetched));

        userDeleteService.deleteUser(USER_ID);

        // Reproduces the COBOL READ-USER-SEC-FILE -> DELETE-USER-SEC-FILE ordering.
        InOrder inOrder = inOrder(userRepository);
        inOrder.verify(userRepository).findById(USER_ID);
        inOrder.verify(userRepository).delete(fetched);
        inOrder.verifyNoMoreInteractions();
        // The service deletes by entity (delete(User)), never by identifier.
        verify(userRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("DeleteResponse: exposes exactly userId/firstName/lastName/userType — no password accessor")
    void deleteResponseExposesOnlyDisplayFieldsAndNeverThePassword() {
        RecordComponent[] components = UserDto.DeleteResponse.class.getRecordComponents();

        assertThat(components)
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "firstName", "lastName", "userType")
                .doesNotContain("password");
    }

    // -----------------------------------------------------------------
    // Phase 2 — validation / not-found (no delete; verbatim messages)
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "userId=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("deleteUser: null/empty/blank userId -> ValidationException, no repository access")
    void deleteUserRejectsBlankUserId(String blankUserId) {
        assertThatThrownBy(() -> userDeleteService.deleteUser(blankUserId))
                .isInstanceOf(ValidationException.class)
                .hasMessage(USER_ID_EMPTY_MESSAGE);

        // The empty-identifier guard short-circuits before any VSAM-equivalent access.
        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("deleteUser: unknown userId -> RecordNotFoundException, nothing is deleted")
    void deleteUserRejectsUnknownUserId() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDeleteService.deleteUser(USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(USER_NOT_FOUND_MESSAGE);

        verify(userRepository).findById(USER_ID);
        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository, never()).deleteById(anyString());
    }
}
