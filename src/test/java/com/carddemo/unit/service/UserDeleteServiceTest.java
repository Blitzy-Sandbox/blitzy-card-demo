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

import java.util.Optional;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserDeleteService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link UserDeleteService}, the
 * transactional fetch-then-delete administrative operation (the Java realization
 * of the COBOL {@code COUSR03C} delete program @ {@code 27d6c6f}).
 *
 * <p>The tests lock the empty-id and not-found guards, the password-free delete
 * response captured before the row is removed, and the translation of a
 * persistence {@code DataAccessException} into a {@link FileAccessException}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService - COUSR03C fetch-then-delete @ 27d6c6f")
class UserDeleteServiceTest {

    private static final String USER_ID = "USER0001";

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDeleteService service;

    private User user() {
        User user = new User();
        user.setUserId(USER_ID);
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setUserType("U");
        user.setPassword("PLAINTEXT-SECRET");
        return user;
    }

    @Test
    @DisplayName("deleteUser: a null user id raises the empty-id message and never touches the repository")
    void deleteUserNullIdRaisesEmptyMessage() {
        assertThatThrownBy(() -> service.deleteUser(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User ID can NOT be empty...");

        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("deleteUser: an unknown user id raises the not-found message")
    void deleteUserUnknownIdRaisesNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser(USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("User ID NOT found...");
    }

    @Test
    @DisplayName("deleteUser: an existing user is deleted and a password-free response is returned")
    void deleteUserExistingDeletesAndReturnsResponse() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));

        UserDto.DeleteResponse response = service.deleteUser(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo("Ada");
        assertThat(response.lastName()).isEqualTo("Lovelace");
        assertThat(response.userType()).isEqualTo("U");
        verify(userRepository).delete(any(User.class));
    }

    @Test
    @DisplayName("deleteUser: a persistence failure is translated to a FileAccessException")
    void deleteUserPersistenceFailureMapsToFileAccess() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        doThrow(new DataIntegrityViolationException("row locked"))
                .when(userRepository).delete(any(User.class));

        assertThatThrownBy(() -> service.deleteUser(USER_ID))
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Unable to Update User...");
    }
}
