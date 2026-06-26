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

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserAddService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAddService} verifying the CP4 credential-normalization
 * fix: the supplied password is upper-cased (using {@link java.util.Locale#ROOT})
 * before being BCrypt-hashed, so a value created here authenticates through
 * {@code AuthService}, which also upper-cases before {@code matches}.
 *
 * <p>The encoder is a {@code spy} over a real {@link BCryptPasswordEncoder}: the
 * real hashing lets the stored value be matched, while the spy lets the exact
 * upper-cased argument passed to {@code encode} be verified.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService — password upper-cased before BCrypt hashing")
class UserAddServiceTest {

    private static final String USER_ID = "USER0001";
    private static final String RAW_PWD = "myPass12";
    private static final String NORM_PWD = "MYPASS12";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private UserAddService userAddService;

    @BeforeEach
    void setUp() {
        passwordEncoder = spy(new BCryptPasswordEncoder());
        userAddService = new UserAddService(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("stored hash is produced from the UPPER-CASED password")
    void storesUpperCasedPasswordHash() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        UserDto.UserSummary summary = userAddService.addUser(
                new UserDto.CreateRequest("First", "Last", USER_ID, RAW_PWD, "U"));

        verify(passwordEncoder).encode(NORM_PWD);
        verify(userRepository).save(userCaptor.capture());

        String storedHash = userCaptor.getValue().getPassword();
        // The upper-cased password matches; the original mixed-case value does not.
        assertThat(passwordEncoder.matches(NORM_PWD, storedHash)).isTrue();
        assertThat(passwordEncoder.matches(RAW_PWD, storedHash)).isFalse();

        assertThat(summary.userId()).isEqualTo(USER_ID);
        assertThat(summary.userType()).isEqualTo("U");
    }

    @Test
    @DisplayName("duplicate user id is rejected before any password hashing")
    void rejectsDuplicateBeforeHashing() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> userAddService.addUser(
                new UserDto.CreateRequest("First", "Last", USER_ID, RAW_PWD, "U")))
                .isInstanceOf(DuplicateRecordException.class);

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
