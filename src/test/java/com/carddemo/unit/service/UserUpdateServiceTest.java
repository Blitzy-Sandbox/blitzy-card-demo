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
package com.carddemo.unit.service;

import java.util.Optional;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserUpdateService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserUpdateService} verifying the CP4 credential behavior:
 * when a password is supplied it is upper-cased (using {@link java.util.Locale#ROOT})
 * before BCrypt hashing — matching {@code AuthService} verification — and when the
 * password is blank the existing hash is preserved (the deliberate optional-password
 * modernization of {@code COUSR02C}, recorded in {@code DECISION_LOG.md}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService — upper-cased password on change, hash preserved when blank")
class UserUpdateServiceTest {

    private static final String USER_ID = "USER0001";
    private static final String RAW_PWD = "newPass1";
    private static final String NORM_PWD = "NEWPASS1";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private UserUpdateService userUpdateService;
    private String existingHash;
    private User existingUser;

    @BeforeEach
    void setUp() {
        passwordEncoder = spy(new BCryptPasswordEncoder());
        userUpdateService = new UserUpdateService(userRepository, passwordEncoder);
        // Seed the pre-existing hash with a separate encoder so the spy records
        // only the encode invocations made by the service under test.
        existingHash = new BCryptPasswordEncoder().encode("OLDPW000");
        existingUser = new User(USER_ID, "Old", "Name", existingHash, "U");
    }

    @Test
    @DisplayName("supplied password is upper-cased before hashing")
    void upperCasesSuppliedPassword() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userUpdateService.updateUser(USER_ID,
                new UserDto.UpdateRequest(USER_ID, "First", "Last", RAW_PWD, "U"));

        verify(passwordEncoder).encode(NORM_PWD);
        String newHash = existingUser.getPassword();
        assertThat(newHash).isNotEqualTo(existingHash);
        assertThat(passwordEncoder.matches(NORM_PWD, newHash)).isTrue();
        assertThat(passwordEncoder.matches(RAW_PWD, newHash)).isFalse();
    }

    @Test
    @DisplayName("blank password preserves the existing hash and does not hash")
    void preservesHashWhenPasswordBlank() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userUpdateService.updateUser(USER_ID,
                new UserDto.UpdateRequest(USER_ID, "First", "Last", "", "U"));

        verify(passwordEncoder, never()).encode(anyString());
        assertThat(existingUser.getPassword()).isEqualTo(existingHash);
        // Non-credential fields are still updated.
        assertThat(existingUser.getFirstName()).isEqualTo("First");
        assertThat(existingUser.getLastName()).isEqualTo("Last");
    }

    @Test
    @DisplayName("missing user record raises RecordNotFoundException without hashing")
    void rejectsMissingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userUpdateService.updateUser(USER_ID,
                new UserDto.UpdateRequest(USER_ID, "First", "Last", RAW_PWD, "U")))
                .isInstanceOf(RecordNotFoundException.class);

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("blank first name is rejected before any lookup")
    void rejectsBlankFirstName() {
        assertThatThrownBy(() -> userUpdateService.updateUser(USER_ID,
                new UserDto.UpdateRequest(USER_ID, "", "Last", RAW_PWD, "U")))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).findById(anyString());
    }
}
