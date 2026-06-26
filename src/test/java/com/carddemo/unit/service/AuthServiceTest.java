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

import com.carddemo.dto.AuthDto;
import com.carddemo.entity.User;
import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.AuthService;
import com.carddemo.service.JwtTokenService;

import io.micrometer.core.instrument.Counter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService} covering the two CP4 security findings:
 * password normalization parity with sign-on, and the generic credential-failure
 * detail that defeats remote user enumeration.
 *
 * <p>A real {@link BCryptPasswordEncoder} is used so the upper-case normalization
 * is verified end-to-end: a password stored as {@code encode(UPPER(pwd))} must
 * authenticate when the same mixed-case value is submitted, because sign-on
 * upper-cases before {@code matches}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — normalization parity & enumeration-safe 401 detail")
class AuthServiceTest {

    private static final String USER_ID_INPUT = "user0001";
    private static final String USER_ID_NORM = "USER0001";
    private static final String RAW_PWD = "myPass12";
    private static final String NORM_PWD = "MYPASS12";
    private static final String GENERIC_DETAIL = "Invalid signon credentials. Try again ...";

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtTokenService jwtTokenService;
    @Mock
    private Counter authAttemptsCounter;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenService, authAttemptsCounter);
    }

    private User storedUser() {
        // Hash is produced from the UPPER-CASED password, matching the add/update flows.
        return new User(USER_ID_NORM, "First", "Last", passwordEncoder.encode(NORM_PWD), "U");
    }

    @Test
    @DisplayName("mixed-case password authenticates because sign-on upper-cases before matches")
    void mixedCasePasswordAuthenticates() {
        when(userRepository.findById(USER_ID_NORM)).thenReturn(Optional.of(storedUser()));
        when(jwtTokenService.generateToken(USER_ID_NORM, "U")).thenReturn("jwt-token");

        AuthDto.SigninResponse response =
                authService.signin(new AuthDto.SigninRequest(USER_ID_INPUT, RAW_PWD));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(USER_ID_NORM);
        assertThat(response.userType()).isEqualTo("U");
        verify(authAttemptsCounter).increment();
    }

    @Test
    @DisplayName("wrong password yields the generic detail (never 'Wrong Password')")
    void wrongPasswordIsGeneric() {
        when(userRepository.findById(USER_ID_NORM)).thenReturn(Optional.of(storedUser()));

        assertThatThrownBy(() -> authService.signin(new AuthDto.SigninRequest(USER_ID_INPUT, "WrongPwd")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage(GENERIC_DETAIL)
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain("Wrong Password");
    }

    @Test
    @DisplayName("unknown user yields the generic detail (never 'User not found')")
    void unknownUserIsGeneric() {
        when(userRepository.findById(USER_ID_NORM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.signin(new AuthDto.SigninRequest(USER_ID_INPUT, RAW_PWD)))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage(GENERIC_DETAIL)
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain("not found");
    }

    @Test
    @DisplayName("lookup failure yields the generic detail and preserves the cause")
    void lookupFailureIsGeneric() {
        when(userRepository.findById(USER_ID_NORM))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThatThrownBy(() -> authService.signin(new AuthDto.SigninRequest(USER_ID_INPUT, RAW_PWD)))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage(GENERIC_DETAIL)
                .hasCauseInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("not-found and wrong-password details are byte-identical (no enumeration signal)")
    void notFoundAndWrongPasswordAreIndistinguishable() {
        // Wrong password
        when(userRepository.findById(USER_ID_NORM)).thenReturn(Optional.of(storedUser()));
        String wrongPasswordDetail = catchDetail(USER_ID_INPUT, "WrongPwd");

        // Unknown user
        when(userRepository.findById(USER_ID_NORM)).thenReturn(Optional.empty());
        String unknownUserDetail = catchDetail(USER_ID_INPUT, RAW_PWD);

        assertThat(unknownUserDetail).isEqualTo(wrongPasswordDetail).isEqualTo(GENERIC_DETAIL);
    }

    private String catchDetail(String userId, String password) {
        try {
            authService.signin(new AuthDto.SigninRequest(userId, password));
            throw new AssertionError("Expected AuthenticationFailedException");
        } catch (AuthenticationFailedException ex) {
            return ex.getMessage();
        }
    }

    @Test
    @DisplayName("blank user id is rejected before any lookup (ValidationException)")
    void blankUserIdRejected() {
        assertThatThrownBy(() -> authService.signin(new AuthDto.SigninRequest("", RAW_PWD)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("blank password is rejected before any lookup (ValidationException)")
    void blankPasswordRejected() {
        assertThatThrownBy(() -> authService.signin(new AuthDto.SigninRequest(USER_ID_INPUT, "")))
                .isInstanceOf(ValidationException.class);
    }
}
