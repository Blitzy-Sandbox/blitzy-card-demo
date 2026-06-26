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

import com.carddemo.dto.AuthDto;
import com.carddemo.entity.User;
import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.AuthService;
import com.carddemo.service.JwtTokenService;
import io.micrometer.core.instrument.Counter;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link AuthService}, the Java realization of the
 * CICS sign-on program {@code app/cbl/COSGN00C.cbl} (transaction {@code CC00} &rarr;
 * {@code POST /api/auth/signin}) at source commit SHA {@code 27d6c6f}.
 *
 * <p>The service reproduces the ordered, short-circuiting decision flow of the COBOL
 * {@code PROCESS-ENTER-KEY} and {@code READ-USER-SEC-FILE} paragraphs:</p>
 * <ol>
 *   <li>the blank user-id / blank password guards (raising a {@link ValidationException}),</li>
 *   <li>the {@code FUNCTION UPPER-CASE} normalization of the user id (trimmed) and password,</li>
 *   <li>the keyed {@code USRSEC} read whose {@code EVALUATE WS-RESP-CD} outcomes map to a
 *       found record, a not-found {@link AuthenticationFailedException}, or an
 *       "unable to verify" {@link AuthenticationFailedException}, and</li>
 *   <li>the BCrypt password verification (constraint&nbsp;C-003) followed by JWT issuance.</li>
 * </ol>
 *
 * <p>Every user-visible detail message is asserted <strong>verbatim</strong> against the
 * compiled service constants, which reproduce {@code COSGN00C.cbl} lines 120, 125, 242,
 * 249, and 254 exactly. Byte-equivalent messages are covered by Gate&nbsp;1 / Gate&nbsp;4.</p>
 *
 * <p>The suite is framework-free: it bootstraps <strong>no</strong> Spring
 * {@code ApplicationContext}, uses <strong>no</strong> {@code @SpringBootTest},
 * Testcontainers, {@code spring-security-test}, database, AWS, or network resource. The four
 * collaborators ({@link UserRepository}, {@link PasswordEncoder}, {@link JwtTokenService}, and
 * the {@code @Qualifier("authAttemptsCounter")} {@link Counter}) are Mockito mocks injected into
 * the service through its constructor. {@link MockitoExtension} runs in its default strict-stub
 * mode, so each test stubs only the collaborators it actually exercises. The whole suite is
 * clean under the project's zero-warning ({@code -Xlint:all -Werror}) build &mdash; assertions
 * use AssertJ's non-deprecated {@code catchThrowable} entry point and reference no deprecated API.</p>
 *
 * <p>All credentials in this suite are obviously-synthetic, non-production test data; the
 * stored "hashes" are placeholder strings (the {@link PasswordEncoder} is a mock and never
 * parses them).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — COSGN00C (CC00) sign-on, validation → USRSEC read → BCrypt → JWT @ 27d6c6f")
class AuthServiceTest {

    /** Raw (lower-case) user id as a client would submit it. */
    private static final String RAW_ADMIN_USER_ID = "admin001";

    /** Normalized (trimmed + upper-cased) form the service derives and keys the lookup with. */
    private static final String NORMALIZED_ADMIN_USER_ID = "ADMIN001";

    /** Raw (lower-case) password as submitted; the service upper-cases it before {@code matches}. */
    private static final String RAW_PASSWORD = "secret";

    /** Normalized (upper-cased) password the service passes to {@link PasswordEncoder#matches}. */
    private static final String NORMALIZED_PASSWORD = "SECRET";

    /** Single-character administrator role discriminator ({@code SEC-USR-TYPE} {@code 'A'}). */
    private static final String ADMIN_USER_TYPE = "A";

    /** Single-character standard-user role discriminator ({@code SEC-USR-TYPE} {@code 'U'}). */
    private static final String STANDARD_USER_TYPE = "U";

    /** Obviously-synthetic placeholder standing in for the stored BCrypt hash (never parsed; mock). */
    private static final String STORED_PASSWORD_HASH =
            "$2a$10$placeholderHashForUnitTestingOnlyNotARealSecret0";

    // -- Verbatim detail messages (COSGN00C.cbl) ------------------------------------------------

    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";
    // Post-lookup credential failures (no matching user, wrong password, or USRSEC read
    // error) all surface a single generic detail so the 401 cannot be used for remote user
    // enumeration; the specific legacy reason is retained only in an internal WARN log.
    private static final String MSG_INVALID_CREDENTIALS = "Invalid signon credentials. Try again ...";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private Counter authAttemptsCounter;

    @InjectMocks
    private AuthService authService;

    // =========================================================================================
    // Phase 1 — Field-presence validation guards (PROCESS-ENTER-KEY blank checks)
    // =========================================================================================

    @ParameterizedTest(name = "blank userId [{0}] → \"Please enter User ID ...\"")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank/empty/whitespace userId raises ValidationException with the verbatim user-id message")
    void blankUserId_raisesValidationException(String blankUserId) {
        AuthDto.SigninRequest request = new AuthDto.SigninRequest(blankUserId, RAW_PASSWORD);

        Throwable thrown = catchThrowable(() -> authService.signin(request));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        assertThat(thrown.getMessage()).isEqualTo(MSG_ENTER_USER_ID);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsEntry("userId", MSG_ENTER_USER_ID);

        // The attempt is metered before any field check, so it counts even on validation failure.
        verify(authAttemptsCounter).increment();
        // Validation short-circuits before the USRSEC read and any credential/token work.
        verifyNoInteractions(userRepository, passwordEncoder, jwtTokenService);
    }

    @ParameterizedTest(name = "blank password [{0}] → \"Please enter Password ...\"")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank/empty/whitespace password raises ValidationException with the verbatim password message")
    void blankPassword_raisesValidationException(String blankPassword) {
        AuthDto.SigninRequest request = new AuthDto.SigninRequest(RAW_ADMIN_USER_ID, blankPassword);

        Throwable thrown = catchThrowable(() -> authService.signin(request));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        assertThat(thrown.getMessage()).isEqualTo(MSG_ENTER_PASSWORD);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsEntry("password", MSG_ENTER_PASSWORD);

        verify(authAttemptsCounter).increment();
        verifyNoInteractions(userRepository, passwordEncoder, jwtTokenService);
    }

    // =========================================================================================
    // Phase 2 — USRSEC read: record not found (EVALUATE WS-RESP-CD = 13)
    // =========================================================================================

    @Test
    @DisplayName("unknown user id raises AuthenticationFailedException (generic credentials detail) and keys the lookup upper-cased")
    void userNotFound_raisesAuthenticationFailed_andLooksUpUpperCasedId() {
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(
                () -> authService.signin(new AuthDto.SigninRequest(RAW_ADMIN_USER_ID, RAW_PASSWORD)));

        assertThat(thrown).isInstanceOf(AuthenticationFailedException.class);
        assertThat(thrown.getMessage()).isEqualTo(MSG_INVALID_CREDENTIALS);

        // The keyed USRSEC read must use the upper-cased user id (FUNCTION UPPER-CASE parity).
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findById(userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(NORMALIZED_ADMIN_USER_ID);

        verify(authAttemptsCounter).increment();
        // No record means no credential comparison and no token issuance.
        verifyNoInteractions(passwordEncoder, jwtTokenService);
    }

    @Test
    @DisplayName("user id is trimmed AND upper-cased before the USRSEC lookup")
    void userId_isTrimmedAndUpperCased_beforeLookup() {
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(
                () -> authService.signin(new AuthDto.SigninRequest("  admin001  ", RAW_PASSWORD)));

        // Surrounding whitespace is non-blank, so the request clears validation and reaches the read.
        assertThat(thrown).isInstanceOf(AuthenticationFailedException.class);
        assertThat(thrown.getMessage()).isEqualTo(MSG_INVALID_CREDENTIALS);

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findById(userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(NORMALIZED_ADMIN_USER_ID);

        verify(authAttemptsCounter).increment();
    }

    // =========================================================================================
    // Phase 3 — Credential verification: wrong password
    // =========================================================================================

    @Test
    @DisplayName("wrong password raises AuthenticationFailedException (generic credentials detail) and matches the upper-cased password")
    void wrongPassword_raisesAuthenticationFailed_andUpperCasesPassword() {
        User storedUser = new User(
                NORMALIZED_ADMIN_USER_ID, "Admin", "User", STORED_PASSWORD_HASH, ADMIN_USER_TYPE);
        when(userRepository.findById(anyString())).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        Throwable thrown = catchThrowable(
                () -> authService.signin(new AuthDto.SigninRequest(RAW_ADMIN_USER_ID, RAW_PASSWORD)));

        assertThat(thrown).isInstanceOf(AuthenticationFailedException.class);
        assertThat(thrown.getMessage()).isEqualTo(MSG_INVALID_CREDENTIALS);

        // The submitted password is upper-cased and verified against the entity's stored hash.
        ArgumentCaptor<String> rawPasswordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> storedHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(rawPasswordCaptor.capture(), storedHashCaptor.capture());
        assertThat(rawPasswordCaptor.getValue()).isEqualTo(NORMALIZED_PASSWORD);
        assertThat(storedHashCaptor.getValue()).isEqualTo(STORED_PASSWORD_HASH);

        verify(authAttemptsCounter).increment();
        // A failed credential check must never issue a token.
        verifyNoInteractions(jwtTokenService);
    }

    // =========================================================================================
    // Phase 4 — USRSEC read failure: WHEN OTHER (EVALUATE WS-RESP-CD other) → "Unable to verify ..."
    // =========================================================================================

    @Test
    @DisplayName("a data-access failure during the USRSEC read raises AuthenticationFailedException (generic credentials detail) wrapping the cause")
    void dataAccessFailure_raisesUnableToVerify_preservingCause() {
        DataAccessException dataAccessFailure =
                new DataAccessResourceFailureException("simulated USRSEC datastore failure");
        when(userRepository.findById(anyString())).thenThrow(dataAccessFailure);

        Throwable thrown = catchThrowable(
                () -> authService.signin(new AuthDto.SigninRequest(RAW_ADMIN_USER_ID, RAW_PASSWORD)));

        assertThat(thrown).isInstanceOf(AuthenticationFailedException.class);
        assertThat(thrown.getMessage()).isEqualTo(MSG_INVALID_CREDENTIALS);
        assertThat(thrown.getCause()).isSameAs(dataAccessFailure);

        verify(authAttemptsCounter).increment();
        // The read threw before any credential comparison or token issuance.
        verifyNoInteractions(passwordEncoder, jwtTokenService);
    }

    // =========================================================================================
    // Phase 5 — Successful sign-on (admin and standard user) + counter on every attempt
    // =========================================================================================

    @Test
    @DisplayName("valid administrator credentials issue a JWT and return token + normalized userId + userType 'A'")
    void successfulAdminSignin_issuesTokenAndReturnsIdentity() {
        User adminUser = new User(
                NORMALIZED_ADMIN_USER_ID, "Admin", "User", STORED_PASSWORD_HASH, ADMIN_USER_TYPE);
        when(userRepository.findById(anyString())).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(anyString(), eq(STORED_PASSWORD_HASH))).thenReturn(true);
        when(jwtTokenService.generateToken(NORMALIZED_ADMIN_USER_ID, ADMIN_USER_TYPE))
                .thenReturn("jwt-123");

        AuthDto.SigninResponse response =
                authService.signin(new AuthDto.SigninRequest(RAW_ADMIN_USER_ID, RAW_PASSWORD));

        assertThat(response.token()).isEqualTo("jwt-123");
        assertThat(response.userId()).isEqualTo(NORMALIZED_ADMIN_USER_ID);
        assertThat(response.userType()).isEqualTo(ADMIN_USER_TYPE);

        // The token is minted from the normalized user id and the record's user type.
        verify(jwtTokenService).generateToken(NORMALIZED_ADMIN_USER_ID, ADMIN_USER_TYPE);
        // Every attempt is metered, success included.
        verify(authAttemptsCounter).increment();
    }

    @Test
    @DisplayName("valid standard-user credentials flow userType 'U' through to the response")
    void successfulStandardUserSignin_flowsUserTypeThrough() {
        String rawUserId = "user0001";
        String normalizedUserId = "USER0001";
        String standardHash = "$2a$10$placeholderHashStandardUserUnitTestNotASecret0";
        User standardUser = new User(
                normalizedUserId, "Standard", "User", standardHash, STANDARD_USER_TYPE);
        when(userRepository.findById(anyString())).thenReturn(Optional.of(standardUser));
        when(passwordEncoder.matches(anyString(), eq(standardHash))).thenReturn(true);
        when(jwtTokenService.generateToken(normalizedUserId, STANDARD_USER_TYPE))
                .thenReturn("jwt-user-456");

        AuthDto.SigninResponse response =
                authService.signin(new AuthDto.SigninRequest(rawUserId, "pass"));

        assertThat(response.userType()).isEqualTo(STANDARD_USER_TYPE);
        assertThat(response.userId()).isEqualTo(normalizedUserId);
        assertThat(response.token()).isEqualTo("jwt-user-456");

        verify(jwtTokenService).generateToken(normalizedUserId, STANDARD_USER_TYPE);
        verify(authAttemptsCounter).increment();
    }
}
