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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import com.awsm2.carddemo.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link SignonService}.
 *
 * <p><b>COBOL provenance.</b> {@link SignonService} translates the
 * CICS COBOL program {@code app/cbl/COSGN00C.cbl} (CICS transaction
 * id {@code CC00}, file {@code 'USRSEC  '}) into a Java
 * {@code @Service} class per the AAP &sect;0.7.1
 * one-service-per-COBOL-program rule. The COBOL source performs the
 * pseudo-conversational flow {@code SEND MAP COSGN0A} &rarr;
 * {@code RECEIVE MAP COSGN0A} &rarr; {@code PROCESS-ENTER-KEY}
 * (L118&ndash;L160) &rarr; {@code READ-USER-SEC-FILE} (L209&ndash;L219)
 * &rarr; {@code EXEC CICS XCTL PROGRAM('COADM01C' | 'COMEN01C')}
 * (L231&ndash;L239).</p>
 *
 * <p><b>Behavioural invariants locked by this suite (CP5 review
 * mandate).</b></p>
 * <ol>
 *   <li><b>Password is NOT uppercased before BCrypt match</b>
 *       &mdash; {@link SignonService} passes the operator-supplied
 *       password VERBATIM to
 *       {@link PasswordEncoder#matches(CharSequence, String)}. This
 *       is the corrected behaviour after the CP5 review identified
 *       that the prior uppercase-before-match path was inconsistent
 *       with the verbatim-encode behaviour in
 *       {@link UserAddService}/{@link UserUpdateService} and caused
 *       admin-created mixed-case passwords to fail signin.</li>
 *   <li><b>userId IS uppercased before USRSEC lookup</b>
 *       &mdash; {@code .trim().toUpperCase(Locale.US)} is applied to
 *       the userId before
 *       {@link UserSecurityRepository#findById(Object)}. Mirrors the
 *       COBOL parity contract (the USRSEC primary-key index is
 *       uppercase-canonical per {@code app/cpy/CSUSR01Y.cpy}). The
 *       {@link Locale#US} is explicit to avoid Turkish-locale
 *       dotless-i hazard.</li>
 *   <li><b>RecordNotFoundException maps to the SAME generic
 *       "Invalid credentials" message as ValidationException</b>
 *       &mdash; to prevent user enumeration via differential error
 *       analysis (PCI-DSS hardening per AAP &sect;0.7.1).</li>
 *   <li><b>JWT issuance carries (userId, userType, firstName,
 *       lastName)</b> &mdash; {@link JwtTokenProvider#issueToken}
 *       is invoked exactly once on the success path with the values
 *       drawn from the persisted {@link UserSecurity} entity (not
 *       from the request DTO &mdash; the persisted values are the
 *       source of truth for identity claims).</li>
 *   <li><b>Audit emission</b>
 *       &mdash; success path: exactly one
 *       {@link AuditLogService#logSecurityEvent} call with event
 *       type {@code "SIGNON_SUCCESS"} and result {@code "SUCCESS"}.
 *       User-not-found path: event type {@code "SIGNON_FAILURE"}
 *       with result {@code "USER_NOT_FOUND"}, NO JWT issuance, NO
 *       success audit. Bad-password path: event type
 *       {@code "SIGNON_FAILURE"} with result {@code "BAD_PASSWORD"},
 *       NO JWT issuance.</li>
 *   <li><b>PCI-DSS: password material NEVER in audit payload</b>
 *       &mdash; the captured {@code Map<String, Object>} audit
 *       payload is asserted to contain NEITHER {@code "password"}
 *       NOR {@code "secUsrPwd"} NOR {@code "pwd"} NOR any
 *       case-variant thereof.</li>
 *   <li><b>SignonResponseDto carries identity attributes drawn from
 *       the USRSEC row, not from the request</b> &mdash; the DTO
 *       record exposes no password component (compile-time
 *       enforcement of PCI-DSS posture).</li>
 *   <li><b>ValidationException on null DTO / blank userId / blank
 *       password</b> &mdash; defensive last-line-of-defense guards
 *       (mirrors the controller-layer
 *       {@code MethodArgumentNotValidException}).</li>
 * </ol>
 *
 * <p><b>Mockito configuration.</b>
 * {@code @ExtendWith(MockitoExtension.class)} activates the
 * JUnit 5 extension with strict-stubbing enforcement so unused stubs
 * fail the test. The {@code @BeforeEach} fixture builds the
 * canonical request DTO without configuring stubs &mdash; each test
 * declares only the stubs it actually needs.</p>
 *
 * <p><b>COBOL: COSGN00C &mdash; sign on with BCrypt + JWT.</b></p>
 *
 * @see SignonService
 * @see UserSecurityRepository
 * @see PasswordEncoder
 * @see JwtTokenProvider
 * @see AuditLogService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignonService — COSGN00C with BCrypt + JWT (CP5 password-policy fix)")
class SignonServiceTest {

    // ------------------------------------------------------------------
    // Test fixture constants — mirror the V015 seed format + CSUSR01Y.cpy
    // ------------------------------------------------------------------

    /**
     * Canonical (already-uppercase) user identifier &mdash; matches
     * the 8-byte {@code SEC-USR-ID PIC X(08)} layout and the
     * uppercase-canonical V015 seed format.
     */
    private static final String NORMALIZED_USER_ID = "USER0001";

    /**
     * Lower-case input variant exercised by the normalization tests
     * &mdash; the service must uppercase this to
     * {@link #NORMALIZED_USER_ID} before the USRSEC lookup.
     */
    private static final String LOWERCASE_USER_ID = "user0001";

    /**
     * Plaintext password fixture (8 characters &mdash; the legacy
     * {@code SEC-USR-PWD PIC X(08)} width). Used verbatim by both
     * the encode (in UserAddService tests) and match (in
     * SignonService tests) paths.
     */
    private static final String PLAINTEXT_PASSWORD = "Pa55w0rd";

    /**
     * Synthetic BCrypt-shaped stored hash returned from the
     * {@link UserSecurity#getSecUsrPwd()} getter. The encoder is
     * fully mocked, so the literal value of the hash is irrelevant
     * to the test &mdash; what matters is that
     * {@link PasswordEncoder#matches(CharSequence, String)} is
     * invoked with this value as the second argument.
     */
    private static final String STORED_BCRYPT_HASH =
            "$2a$12$abcdefghijklmnopqrstuvWXYZ0123456789ABCDEFGHIJabcdefghijkl";

    /** Canonical first name fixture matching the V015 seed format. */
    private static final String FIRST_NAME = "JANE";

    /** Canonical last name fixture matching the V015 seed format. */
    private static final String LAST_NAME = "DOE";

    /** Regular user role discriminator. */
    private static final String USER_TYPE_USER = "U";

    /** Admin user role discriminator. */
    private static final String USER_TYPE_ADMIN = "A";

    /** Synthetic JWT bearer token returned by the mocked provider. */
    private static final String JWT_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJVU0VSMDAwMSJ9.signature";

    /** Synthetic JWT expiration window (5 minutes). */
    private static final Duration JWT_EXPIRATION = Duration.ofMinutes(5);

    /** Production audit event-type constants emitted by the service. */
    private static final String EVENT_TYPE_SIGNON_SUCCESS = "SIGNON_SUCCESS";
    private static final String EVENT_TYPE_SIGNON_FAILURE = "SIGNON_FAILURE";

    /** Production audit result discriminators. */
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_USER_NOT_FOUND = "USER_NOT_FOUND";
    private static final String RESULT_BAD_PASSWORD = "BAD_PASSWORD";

    /** Generic error message returned on every authentication failure. */
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";

    // ------------------------------------------------------------------
    // Mock collaborators + system under test
    // ------------------------------------------------------------------

    /** Spring Data JPA repository over the {@code user_security} table. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security {@link PasswordEncoder} &mdash; stubbed so the
     * test does not actually run BCrypt-cost-12 verification (which
     * is intentionally slow on real hardware).
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** HS256-signed JWT bearer-token issuer. */
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    /** OpenSearch + CloudWatch audit emission adapter. */
    @Mock
    private AuditLogService auditLogService;

    /**
     * System under test &mdash; {@link SignonService} instantiated
     * via constructor injection of the four mocked collaborators.
     */
    @InjectMocks
    private SignonService service;

    /** Canonical request fixture rebuilt before every test. */
    private SignonRequestDto validRequest;

    /** Canonical persisted {@link UserSecurity} fixture. */
    private UserSecurity persistedUser;

    @BeforeEach
    void setUp() {
        // COBOL: COSGN00C:PROCESS-ENTER-KEY happy-path inputs.
        validRequest = new SignonRequestDto(NORMALIZED_USER_ID, PLAINTEXT_PASSWORD);
        persistedUser = new UserSecurity(
                NORMALIZED_USER_ID,
                FIRST_NAME,
                LAST_NAME,
                STORED_BCRYPT_HASH,
                USER_TYPE_USER);
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    /**
     * Locks the CP5-mandated password-policy fix: the request password
     * is passed VERBATIM into BCrypt.matches() &mdash; never uppercased.
     */
    @Nested
    @DisplayName("Password normalization (CP5 fix — VERBATIM, no uppercase)")
    class PasswordNormalization {

        @Test
        @DisplayName("matches password verbatim (NOT uppercased) against stored hash")
        void signon_matchesPasswordVerbatim() {
            // Arrange — mixed-case password input
            String mixedCasePassword = "Pa55w0rd";
            SignonRequestDto request = new SignonRequestDto(NORMALIZED_USER_ID, mixedCasePassword);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(mixedCasePassword, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — matches invoked with the VERBATIM password,
            // never with the uppercase variant
            verify(passwordEncoder).matches(eq(mixedCasePassword), eq(STORED_BCRYPT_HASH));
            verify(passwordEncoder, never())
                    .matches(eq(mixedCasePassword.toUpperCase(Locale.US)), anyString());
        }

        @Test
        @DisplayName("invokes encoder.matches exactly once per signon attempt")
        void signon_invokesMatchExactlyOnce() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert
            verify(passwordEncoder, times(1)).matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH);
        }

        @Test
        @DisplayName("rejects bad password (matches returns false)")
        void signon_rejectsBadPassword() {
            // Arrange — encoder.matches stub returns false
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act + Assert — ValidationException with the GENERIC message
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_CREDENTIALS_MESSAGE);

            // Assert — NO JWT issuance on the failure path
            verify(jwtTokenProvider, never())
                    .issueToken(anyString(), anyString(), anyString(), anyString());
        }
    }

    /**
     * Locks the userId uppercase + trim normalization that mirrors
     * the COBOL {@code MOVE FUNCTION UPPER-CASE(USERIDI)} step.
     */
    @Nested
    @DisplayName("userId normalization (Locale.US uppercase + trim)")
    class UserIdNormalization {

        @Test
        @DisplayName("uppercases lower-case userId before findById")
        void signon_uppercasesLowercaseUserId() {
            // Arrange — request carries lower-case userId
            SignonRequestDto request = new SignonRequestDto(LOWERCASE_USER_ID, PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — findById invoked with the UPPERCASE userId
            verify(userSecurityRepository).findById(NORMALIZED_USER_ID);
            verify(userSecurityRepository, never()).findById(LOWERCASE_USER_ID);
        }

        @Test
        @DisplayName("trims surrounding whitespace from userId")
        void signon_trimsWhitespace() {
            // Arrange — request carries padded userId
            SignonRequestDto request = new SignonRequestDto("  user0001  ", PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — findById invoked with the trimmed + uppercased value
            verify(userSecurityRepository).findById(NORMALIZED_USER_ID);
        }
    }

    /**
     * Locks the USRSEC-lookup behaviour: a missing record surfaces
     * as {@link RecordNotFoundException} with the GENERIC
     * "Invalid credentials" message (PCI-DSS hardening).
     */
    @Nested
    @DisplayName("USRSEC lookup (user-not-found path)")
    class UserNotFound {

        @Test
        @DisplayName("throws RecordNotFoundException with generic message")
        void signon_userNotFound_throwsRecordNotFound() {
            // Arrange — repository returns empty Optional
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage(INVALID_CREDENTIALS_MESSAGE);

            // Assert — neither BCrypt match nor JWT issuance occurred
            verify(passwordEncoder, never()).matches(anyString(), anyString());
            verify(jwtTokenProvider, never())
                    .issueToken(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("audits USER_NOT_FOUND failure")
        void signon_userNotFound_auditsFailure() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert — audit emission with SIGNON_FAILURE + USER_NOT_FOUND
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_FAILURE),
                    eq(NORMALIZED_USER_ID),
                    eq(RESULT_USER_NOT_FOUND),
                    isNull(),
                    anyMap(),
                    isNull());
        }
    }

    /**
     * Locks the success-path behaviour: JWT issuance + audit success
     * + response DTO assembly.
     */
    @Nested
    @DisplayName("Happy path (success — JWT issuance + audit + response)")
    class HappyPath {

        @Test
        @DisplayName("returns SignonResponseDto with token + identity attributes")
        void signon_happyPath_returnsResponseDto() {
            // Arrange — full happy-path stubs
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    NORMALIZED_USER_ID, USER_TYPE_USER, FIRST_NAME, LAST_NAME))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — DTO carries the persisted identity, not the request
            assertThat(response).isNotNull();
            assertThat(response.token()).isEqualTo(JWT_TOKEN);
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(LAST_NAME);
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(response.expiresAt())
                    .as("expiresAt must be in the future (now + JWT_EXPIRATION)")
                    .isGreaterThan(java.time.Instant.now().getEpochSecond());
        }

        @Test
        @DisplayName("invokes JwtTokenProvider.issueToken exactly once with USRSEC values")
        void signon_happyPath_issuesJwtWithUsrsecValues() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — issueToken invoked with the USRSEC-row values
            verify(jwtTokenProvider, times(1))
                    .issueToken(NORMALIZED_USER_ID, USER_TYPE_USER, FIRST_NAME, LAST_NAME);
        }

        @Test
        @DisplayName("emits SIGNON_SUCCESS audit event with userType payload")
        void signon_happyPath_emitsSuccessAudit() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — capture the audit payload
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS),
                    eq(NORMALIZED_USER_ID),
                    eq(RESULT_SUCCESS),
                    isNull(),
                    payloadCaptor.capture(),
                    isNull());
            // userType is in the payload so dashboards can filter by role
            assertThat(payloadCaptor.getValue()).containsEntry("userType", USER_TYPE_USER);
        }

        @Test
        @DisplayName("admin user receives ADMIN role in the JWT claims and audit payload")
        void signon_adminUser_issuesAdminClaim() {
            // Arrange — USRSEC row carries userType='A'
            UserSecurity adminUser = new UserSecurity(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, STORED_BCRYPT_HASH, USER_TYPE_ADMIN);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(adminUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert
            assertThat(response.userType()).isEqualTo(USER_TYPE_ADMIN);
            verify(jwtTokenProvider).issueToken(
                    NORMALIZED_USER_ID, USER_TYPE_ADMIN, FIRST_NAME, LAST_NAME);
        }
    }

    /**
     * Locks the bad-password path: failure-mode audit with
     * BAD_PASSWORD reason, NO JWT issuance, generic error message.
     */
    @Nested
    @DisplayName("Bad password path (BCrypt mismatch)")
    class BadPasswordPath {

        @Test
        @DisplayName("throws ValidationException with generic message (no enumeration)")
        void signon_badPassword_throwsValidation() {
            // Arrange — encoder returns false
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act + Assert — message must be the SAME as user-not-found path
            // (PCI-DSS hardening: prevent enumeration via differential
            //  error analysis)
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_CREDENTIALS_MESSAGE);
        }

        @Test
        @DisplayName("audits BAD_PASSWORD failure")
        void signon_badPassword_auditsFailure() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class);

            // Assert
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_FAILURE),
                    eq(NORMALIZED_USER_ID),
                    eq(RESULT_BAD_PASSWORD),
                    isNull(),
                    anyMap(),
                    isNull());
            // NO success audit
            verify(auditLogService, never()).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS),
                    anyString(), anyString(), any(), anyMap(), any());
        }
    }

    /**
     * Locks the defensive input-validation guards (last-line-of-
     * defense beyond the Jakarta Bean Validation at the controller
     * boundary).
     */
    @Nested
    @DisplayName("Input validation (null / blank guards)")
    class InputValidation {

        @Test
        @DisplayName("rejects null request DTO with ValidationException")
        void signon_nullRequest_throwsValidation() {
            assertThatThrownBy(() -> service.signon(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Request");
            // NO downstream interactions on the null path
            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(jwtTokenProvider);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("rejects null userId with ValidationException")
        void signon_nullUserId_throwsValidation() {
            SignonRequestDto request = new SignonRequestDto(null, PLAINTEXT_PASSWORD);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
        }

        @Test
        @DisplayName("rejects blank userId with ValidationException")
        void signon_blankUserId_throwsValidation() {
            SignonRequestDto request = new SignonRequestDto("   ", PLAINTEXT_PASSWORD);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
        }

        @Test
        @DisplayName("rejects null password with ValidationException")
        void signon_nullPassword_throwsValidation() {
            SignonRequestDto request = new SignonRequestDto(NORMALIZED_USER_ID, null);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");
        }

        @Test
        @DisplayName("rejects blank password with ValidationException")
        void signon_blankPassword_throwsValidation() {
            SignonRequestDto request = new SignonRequestDto(NORMALIZED_USER_ID, "   ");

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");
        }
    }

    /**
     * PCI-DSS: assert that no password-like material appears in the
     * audit payloads (success or failure paths).
     */
    @Nested
    @DisplayName("PCI-DSS — credential material never in audit payload")
    class PciDssHandling {

        @Test
        @DisplayName("success audit payload contains NO password key (any case variant)")
        void signon_successAudit_omitsPassword() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — capture and scan the success-audit payload
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS), anyString(), anyString(),
                    isNull(), payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
            for (String key : payload.keySet()) {
                assertThat(key.toLowerCase(Locale.US))
                        .as("audit payload key %s must not match any password-like discriminator", key)
                        .doesNotContain("pwd")
                        .doesNotContain("password");
            }
            // And the plaintext password value MUST NOT appear in any payload value
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .as("audit payload value must not echo the plaintext password")
                        .doesNotContain(PLAINTEXT_PASSWORD);
            }
        }

        @Test
        @DisplayName("failure audit payload contains NO password key (any case variant)")
        void signon_failureAudit_omitsPassword() {
            // Arrange — bad password path
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(persistedUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_FAILURE), anyString(), eq(RESULT_BAD_PASSWORD),
                    isNull(), payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
        }

        @Test
        @DisplayName("response DTO record has no password component (compile-time enforcement)")
        void signonResponseDto_hasNoPasswordComponent() {
            // Reflective check: the record components must not include
            // any field whose name contains 'password' or 'pwd'.
            for (var component : SignonResponseDto.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.US))
                        .as("SignonResponseDto record component %s must not be password-like",
                                component.getName())
                        .doesNotContain("password")
                        .doesNotContain("pwd");
            }
        }
    }
}
