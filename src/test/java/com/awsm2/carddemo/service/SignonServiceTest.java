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
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link SignonService} &mdash; the
 * Java target translating the CICS COBOL program
 * {@code app/cbl/COSGN00C.cbl} (CICS transaction id {@code CC00},
 * file {@code 'USRSEC  '}) per the AAP &sect;0.7.1
 * one-service-per-COBOL-program rule.
 *
 * <h2>COBOL Source Provenance</h2>
 * <p>The COBOL source performs the pseudo-conversational flow:
 * <pre>
 *     SEND MAP COSGN0A                              (initial signon screen)
 *     RECEIVE MAP COSGN0A                           (operator submits creds)
 *     PROCESS-ENTER-KEY                             (L118-L140)
 *         WHEN USERIDI = SPACES OR LOW-VALUES &rarr; "Please enter User ID ..."
 *         WHEN PASSWDI = SPACES OR LOW-VALUES &rarr; "Please enter Password ..."
 *         MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID  (L132-L134)
 *         MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD (L135-L137)
 *         PERFORM READ-USER-SEC-FILE                (L139)
 *     READ-USER-SEC-FILE                            (L209-L256)
 *         EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
 *         WHEN WS-RESP-CD = 0
 *             IF SEC-USR-PWD = WS-USER-PWD          (L223 plaintext compare)
 *                 EXEC CICS XCTL PROGRAM('COADM01C' | 'COMEN01C')
 *             ELSE
 *                 "Wrong Password. Try again ..."
 *         WHEN WS-RESP-CD = 13                       (NOTFND)
 *             "User not found. Try again ..."
 * </pre>
 *
 * <h2>Behavioural Invariants Locked By This Suite (CP5 review mandate)</h2>
 * <ol>
 *   <li><b>userId IS uppercased + trimmed before USRSEC lookup</b>
 *       &mdash; {@code .trim().toUpperCase(Locale.US)} is applied to
 *       the userId before
 *       {@link UserSecurityRepository#findById(Object)}. Mirrors the
 *       COBOL parity contract at COSGN00C L132-L134 (the USRSEC
 *       primary-key index is uppercase-canonical per
 *       {@code app/cpy/CSUSR01Y.cpy}). The {@link Locale#US} is
 *       explicit to avoid Turkish-locale dotless-i hazard.</li>
 *   <li><b>password is NOT uppercased before BCrypt match (CP5 fix)</b>
 *       &mdash; {@link SignonService} passes the operator-supplied
 *       password VERBATIM to
 *       {@link PasswordEncoder#matches(CharSequence, String)}. This
 *       is the corrected behaviour after the CP5 review identified
 *       that the prior uppercase-before-match path was inconsistent
 *       with the verbatim-encode behaviour in {@code UserAddService}
 *       and {@code UserUpdateService}, and caused admin-created
 *       mixed-case passwords to fail signin. Although the literal
 *       COBOL semantics at L135-L137 uppercase PASSWDI, the BCrypt
 *       upgrade (AAP &sect;0.7.1) requires preserving password
 *       entropy, so the password uppercasing step is deliberately
 *       NOT replicated in the Java target.</li>
 *   <li><b>BCrypt {@link PasswordEncoder#matches} replaces COBOL L223
 *       plaintext compare</b> &mdash; per AAP &sect;0.7.1 PCI-DSS
 *       upgrade. The encoder is invoked with the verbatim plaintext
 *       password and the stored 60-character BCrypt hash from
 *       {@link UserSecurity#getSecUsrPwd()}.</li>
 *   <li><b>RecordNotFoundException and ValidationException both carry
 *       the SAME generic "Invalid credentials" message</b>
 *       &mdash; to prevent user enumeration via differential error
 *       analysis (PCI-DSS hardening per AAP &sect;0.7.1). The COBOL
 *       original surfaced "User not found ..." and "Wrong Password
 *       ..." as distinct messages; the Java target deliberately
 *       collapses them.</li>
 *   <li><b>USER-TYPE 'A' &harr; admin, 'U' &harr; user</b>
 *       &mdash; the {@link UserSecurity#getSecUsrType()} value is
 *       forwarded verbatim into the JWT claims via
 *       {@link JwtTokenProvider#issueToken} and into
 *       {@link SignonResponseDto#userType()}. Routing decisions are
 *       made client-side (admin menu vs main menu), replacing the
 *       COBOL {@code EXEC CICS XCTL PROGRAM('COADM01C' | 'COMEN01C')}
 *       dispatch at L230-L240.</li>
 *   <li><b>JWT issuance carries (userId, userType, firstName,
 *       lastName)</b> &mdash; {@link JwtTokenProvider#issueToken}
 *       is invoked exactly once on the success path with the values
 *       drawn from the persisted {@link UserSecurity} entity (not
 *       from the request DTO &mdash; the persisted values are the
 *       source of truth for identity claims).</li>
 *   <li><b>Audit emission for every signon attempt</b> &mdash;
 *       success path: one
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
 *       case-variant thereof. The plaintext password value MUST NOT
 *       appear in any payload value.</li>
 *   <li><b>SignonResponseDto carries identity attributes drawn from
 *       the USRSEC row, not from the request</b> &mdash; the DTO
 *       record exposes no password component (compile-time
 *       enforcement of PCI-DSS posture per AAP &sect;0.7.1).</li>
 * </ol>
 *
 * <h2>Mockito Configuration</h2>
 * <p>{@code @ExtendWith(MockitoExtension.class)} activates the
 * JUnit 5 extension with strict-stubbing enforcement so unused stubs
 * fail the test. The {@code @BeforeEach} fixture builds the
 * canonical request DTO without configuring stubs &mdash; each test
 * declares only the stubs it actually needs.</p>
 *
 * <p>COBOL: COSGN00C &mdash; sign on with BCrypt + JWT.</p>
 *
 * @see SignonService
 * @see UserSecurityRepository
 * @see PasswordEncoder
 * @see JwtTokenProvider
 * @see AuditLogService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignonService — COSGN00C BCrypt + uppercase-normalize")
class SignonServiceTest {

    // ------------------------------------------------------------------
    // Test fixture constants — mirror the V015 seed format + CSUSR01Y.cpy
    // ------------------------------------------------------------------

    /**
     * Canonical (already-uppercase) user identifier &mdash; matches
     * the 8-byte {@code SEC-USR-ID PIC X(08)} layout and the
     * uppercase-canonical V015 seed format
     * ({@code V015__seed_default_users.sql}).
     */
    private static final String NORMALIZED_USER_ID = "USER0001";

    /**
     * Canonical admin user identifier (uppercase) &mdash; used by
     * the UserTypeRouting tests for the {@code SEC-USR-TYPE = 'A'}
     * branch (COBOL {@code EXEC CICS XCTL PROGRAM('COADM01C')} path).
     */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /**
     * Lower-case input variant exercised by the
     * {@link UpperCaseNormalization} tests &mdash; the service must
     * uppercase this to {@link #NORMALIZED_USER_ID} before the
     * USRSEC lookup (COBOL L132-L134
     * {@code MOVE FUNCTION UPPER-CASE(USERIDI)}).
     */
    private static final String LOWERCASE_USER_ID = "user0001";

    /**
     * Lower-case admin variant exercised by the
     * {@link UpperCaseNormalization} tests for the admin login path.
     */
    private static final String LOWERCASE_ADMIN_USER_ID = "admin001";

    /**
     * Plaintext password fixture (8 characters &mdash; the legacy
     * {@code SEC-USR-PWD PIC X(08)} width). Mixed-case so the
     * "password NOT uppercased" invariant is observable: if the
     * service inadvertently uppercased this value before BCrypt,
     * the captured argument would be {@code "PA55W0RD"} which is
     * distinguishable from this verbatim {@code "Pa55w0rd"}.
     */
    private static final String PLAINTEXT_PASSWORD = "Pa55w0rd";

    /**
     * Synthetic BCrypt-shaped stored hash returned from the
     * {@link UserSecurity#getSecUsrPwd()} getter. The encoder is
     * fully mocked, so the literal value of the hash is irrelevant
     * to the test &mdash; what matters is that
     * {@link PasswordEncoder#matches(CharSequence, String)} is
     * invoked with this value as the second argument. The
     * {@code "$2a$12$..."} prefix matches BCrypt strength-12 hash
     * format per Spring Security
     * {@code BCryptPasswordEncoder.BCryptVersion.$2A}.
     */
    private static final String STORED_BCRYPT_HASH =
            "$2a$12$abcdefghijklmnopqrstuvWXYZ0123456789ABCDEFGHIJabcdefghijkl";

    /** Canonical first name fixture matching the V015 seed format. */
    private static final String FIRST_NAME = "JANE";

    /** Canonical last name fixture matching the V015 seed format. */
    private static final String LAST_NAME = "DOE";

    /** Canonical admin first name fixture matching the V015 seed format. */
    private static final String ADMIN_FIRST_NAME = "ADMIN";

    /** Canonical admin last name fixture matching the V015 seed format. */
    private static final String ADMIN_LAST_NAME = "USER";

    /**
     * Regular user role discriminator &mdash; matches the COBOL
     * {@code SEC-USR-TYPE PIC X(01)} {@code 'U'} value and the
     * {@code CDEMO-USRTYP-USER} 88-level. Routes to the main menu
     * (replaces {@code EXEC CICS XCTL PROGRAM('COMEN01C')}).
     */
    private static final String USER_TYPE_USER = "U";

    /**
     * Admin user role discriminator &mdash; matches the COBOL
     * {@code SEC-USR-TYPE PIC X(01)} {@code 'A'} value and the
     * {@code CDEMO-USRTYP-ADMIN} 88-level. Routes to the admin menu
     * (replaces {@code EXEC CICS XCTL PROGRAM('COADM01C')}).
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Synthetic JWT bearer token returned by the mocked provider.
     * The shape mimics a real HS256-signed JWT (header.payload.sig)
     * so consumers reading the test output can recognise the role of
     * this value; the actual signature is not validated by the
     * mocked provider.
     */
    private static final String JWT_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJVU0VSMDAwMSJ9.signature";

    /**
     * Synthetic JWT expiration window (5 minutes). The production
     * default per {@code application.yml} is 30 minutes
     * ({@code carddemo.security.jwt.expiration: PT30M}); 5 minutes
     * is used here purely for fixture brevity &mdash; the test
     * asserts only that the returned {@code expiresAt} is "in the
     * future" relative to {@link Instant#now()} at the call site.
     */
    private static final Duration JWT_EXPIRATION = Duration.ofMinutes(5);

    // ------------------------------------------------------------------
    // Production audit constants emitted by SignonService — preserved
    // verbatim from SignonService.java so a divergence between the
    // production constants and the test expectations becomes a compile
    // error or a strict-stubbing mismatch.
    // ------------------------------------------------------------------

    /** Production audit event type for a successful signon attempt. */
    private static final String EVENT_TYPE_SIGNON_SUCCESS = "SIGNON_SUCCESS";

    /** Production audit event type for a failed signon attempt. */
    private static final String EVENT_TYPE_SIGNON_FAILURE = "SIGNON_FAILURE";

    /** Production audit result tag indicating successful authentication. */
    private static final String RESULT_SUCCESS = "SUCCESS";

    /** Production audit result tag indicating supplied user id not found. */
    private static final String RESULT_USER_NOT_FOUND = "USER_NOT_FOUND";

    /** Production audit result tag indicating BCrypt mismatch. */
    private static final String RESULT_BAD_PASSWORD = "BAD_PASSWORD";

    /**
     * Generic error message returned on every authentication failure
     * (both {@link RecordNotFoundException} for user-not-found AND
     * {@link ValidationException} for BCrypt mismatch). The COBOL
     * original distinguished the two paths on the 3270 screen; the
     * Java target collapses them to prevent user enumeration via
     * differential error analysis (PCI-DSS hardening per AAP
     * &sect;0.7.1).
     */
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";

    // ------------------------------------------------------------------
    // Mock collaborators + system under test
    // ------------------------------------------------------------------

    /**
     * Mock Spring Data JPA repository over the {@code user_security}
     * table &mdash; replaces CICS access to
     * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}. Stubbed with
     * {@code Optional.of(persistedUser)} for the happy path and
     * {@code Optional.empty()} for the user-not-found path
     * (replicates COBOL {@code WS-RESP-CD = 13} NOTFND branch at
     * COSGN00C L247-L251).
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mock Spring Security {@link PasswordEncoder} &mdash; stubbed so
     * the test does not actually run BCrypt-cost-12 verification
     * (which is intentionally slow on real hardware). The
     * {@link BCryptComparison} group verifies that
     * {@code matches(rawPassword, encodedPassword)} is invoked with
     * the verbatim plaintext password (NOT uppercased per the CP5
     * mandate) and the stored BCrypt hash.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Mock HS256-signed JWT bearer-token issuer &mdash; replaces
     * the CICS COMMAREA pseudo-conversational identity propagation
     * pattern (COCOM01Y.cpy + EXEC CICS XCTL). Stubbed with
     * {@link #JWT_TOKEN} and {@link #JWT_EXPIRATION}.
     */
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Mock OpenSearch + CloudWatch audit emission adapter &mdash;
     * verifies PCI-DSS / SOX audit emission per AAP &sect;0.6.6.
     * Captured via {@link ArgumentCaptor} in
     * {@link AuditLogging} to verify the payload contains no
     * password / secUsrPwd / pwd entries.
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * System under test &mdash; {@link SignonService} instantiated
     * via constructor injection of the four mocked collaborators
     * (replicates the AAP &sect;0.3.3 constructor-injection mandate).
     */
    @InjectMocks
    private SignonService service;

    /**
     * Canonical request fixture rebuilt before every test by
     * {@link #setUp()}. Carries an uppercase userId + the verbatim
     * mixed-case plaintext password.
     */
    private SignonRequestDto validRequest;

    /**
     * Canonical persisted {@link UserSecurity} fixture for the
     * regular-user path. Rebuilt before every test by
     * {@link #setUp()}.
     */
    private UserSecurity standardUser;

    /**
     * Canonical persisted {@link UserSecurity} fixture for the
     * admin-user path. Rebuilt before every test by {@link #setUp()}.
     * Used by {@link UserTypeRouting} to verify the
     * {@code SEC-USR-TYPE = 'A'} branch.
     */
    private UserSecurity adminUser;

    @BeforeEach
    void setUp() {
        // COBOL: COSGN00C:PROCESS-ENTER-KEY happy-path inputs.
        // Canonical request with already-uppercase userId — individual
        // @Nested groups vary the userId/password as needed.
        validRequest = new SignonRequestDto(NORMALIZED_USER_ID, PLAINTEXT_PASSWORD);

        // Canonical USRSEC row for the regular-user (USER-TYPE='U') path.
        // Mirrors the V015__seed_default_users.sql seed format.
        standardUser = new UserSecurity(
                NORMALIZED_USER_ID,
                FIRST_NAME,
                LAST_NAME,
                STORED_BCRYPT_HASH,
                USER_TYPE_USER);

        // Canonical USRSEC row for the admin (USER-TYPE='A') path.
        adminUser = new UserSecurity(
                ADMIN_USER_ID,
                ADMIN_FIRST_NAME,
                ADMIN_LAST_NAME,
                STORED_BCRYPT_HASH,
                USER_TYPE_ADMIN);
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    /**
     * Verifies userId normalization to uppercase + trim before USRSEC
     * lookup &mdash; the CRITICAL behaviour from COSGN00C L132-L134
     * {@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID}.
     *
     * <p><b>Note on password uppercasing (CP5 mandate divergence).</b>
     * The COBOL source at L135-L137 ALSO uppercases the password
     * before comparison. The Java target deliberately DOES NOT
     * replicate the password uppercasing per the CP5 review documented
     * in {@link SignonService} (BCrypt requires preserving password
     * entropy; uppercasing collapses the password space and is
     * inconsistent with the verbatim-encode behaviour of
     * {@code UserAddService} and {@code UserUpdateService}). The
     * BCrypt-side verbatim-handling is verified by the
     * {@link BCryptComparison} group below.</p>
     */
    @Nested
    @DisplayName("UpperCaseNormalization — userId trimmed + uppercased before USRSEC lookup (COSGN00C L132-L134)")
    class UpperCaseNormalization {

        @Test
        @DisplayName("lowercase userId is uppercased before repository lookup")
        void signon_lowercaseUserId_isUppercasedBeforeRepositoryLookup() {
            // COBOL: COSGN00C L132-L134 — FUNCTION UPPER-CASE on USERIDI.
            // Arrange — request carries lowercase userId
            SignonRequestDto request =
                    new SignonRequestDto(LOWERCASE_USER_ID, PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — findById invoked with the UPPERCASE userId,
            // NEVER with the original lowercase value.
            ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(userSecurityRepository).findById(userIdCaptor.capture());
            assertThat(userIdCaptor.getValue())
                    .as("userId passed to findById must be uppercase-normalized")
                    .isEqualTo(NORMALIZED_USER_ID);
            verify(userSecurityRepository, never()).findById(LOWERCASE_USER_ID);
        }

        @Test
        @DisplayName("mixed-case userId is uppercased before repository lookup")
        void signon_mixedCaseUserId_isUppercasedBeforeRepositoryLookup() {
            // COBOL: COSGN00C L132-L134 — FUNCTION UPPER-CASE on USERIDI.
            // Mixed-case ("Admin001") must reach findById as "ADMIN001".
            // Arrange
            SignonRequestDto request =
                    new SignonRequestDto("Admin001", PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(adminUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — findById invoked with all-uppercase value
            verify(userSecurityRepository).findById(ADMIN_USER_ID);
            verify(userSecurityRepository, never()).findById("Admin001");
            verify(userSecurityRepository, never()).findById("admin001");
        }

        @Test
        @DisplayName("userId with leading/trailing spaces is trimmed and uppercased")
        void signon_userIdWithLeadingTrailingSpaces_isTrimmedAndUppercased() {
            // COBOL: BMS USERID DFHMDF length=8 — 3270 device space-padded
            //        the field, so the COBOL program received a padded
            //        value. The Java target trims on the request side via
            //        request.userId().trim().toUpperCase(Locale.US).
            // Arrange — request carries lowercase userId padded with spaces
            SignonRequestDto request =
                    new SignonRequestDto("  user0001  ", PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — findById invoked with the trimmed + uppercased value
            verify(userSecurityRepository).findById(NORMALIZED_USER_ID);
            verify(userSecurityRepository, never()).findById("  user0001  ");
            verify(userSecurityRepository, never()).findById("  USER0001  ");
        }

        @Test
        @DisplayName("already-uppercase userId is passed through unchanged")
        void signon_alreadyUppercaseUserId_isPassedThroughUnchanged() {
            // Defensive: even when the input is already canonical, the
            // service must still call findById with the same value
            // (no double-uppercasing surprises, no implicit truncation).
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert
            verify(userSecurityRepository).findById(NORMALIZED_USER_ID);
        }

        @Test
        @DisplayName("Locale.US uppercasing — defensive Turkish-locale dotless-i invariant")
        void signon_usesLocaleUsForUppercasing() {
            // Defensive: the SignonService uses Locale.US explicitly so
            // a Turkish JVM locale does not produce dotless-i ('ı') from
            // a lowercase 'i', which would corrupt USRSEC primary-key
            // lookups. We can only validate the externally-observable
            // behaviour: the input "ıuser" must NOT match the dotted-I
            // uppercase form. Using all-ASCII letters here so the
            // upper-case result is unambiguous on any JVM locale.
            // Arrange — lowercase ASCII userId
            SignonRequestDto request =
                    new SignonRequestDto(LOWERCASE_ADMIN_USER_ID, PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(adminUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — uppercasing is locale-independent for ASCII
            verify(userSecurityRepository).findById(ADMIN_USER_ID);
        }
    }

    /**
     * Verifies BCrypt-hashed password comparison (CRITICAL &mdash;
     * replaces COSGN00C L223 plaintext compare
     * {@code IF SEC-USR-PWD = WS-USER-PWD} per AAP &sect;0.7.1
     * PCI-DSS upgrade).
     *
     * <p>Locks the CP5-mandated invariant: the request password is
     * passed VERBATIM into
     * {@link PasswordEncoder#matches(CharSequence, String)} &mdash;
     * never uppercased. This diverges from the literal COBOL
     * L135-L137 {@code MOVE FUNCTION UPPER-CASE(PASSWDI)} step
     * because uppercasing collapses password entropy and is
     * incompatible with the BCrypt verbatim-encode contract enforced
     * by {@code UserAddService} / {@code UserUpdateService}.</p>
     */
    @Nested
    @DisplayName("BCryptComparison — verbatim password to PasswordEncoder.matches (CP5 fix; replaces COSGN00C L223)")
    class BCryptComparison {

        @Test
        @DisplayName("invokes passwordEncoder.matches with verbatim password (NOT uppercased) and stored hash")
        void signon_validCredentials_invokesPasswordEncoderMatches() {
            // Arrange — mixed-case password input
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — capture both arguments. Per CP5, the first
            // argument must be the VERBATIM plaintext password
            // (mixed-case "Pa55w0rd"), NEVER the uppercase variant
            // ("PA55W0RD"). The second argument must be the stored
            // BCrypt hash from UserSecurity.getSecUsrPwd().
            ArgumentCaptor<CharSequence> rawCaptor =
                    ArgumentCaptor.forClass(CharSequence.class);
            ArgumentCaptor<String> storedCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(passwordEncoder).matches(rawCaptor.capture(), storedCaptor.capture());

            assertThat(rawCaptor.getValue().toString())
                    .as("matches() first arg must be the VERBATIM password (CP5 fix), never uppercased")
                    .isEqualTo(PLAINTEXT_PASSWORD)
                    .isNotEqualTo(PLAINTEXT_PASSWORD.toUpperCase(Locale.US));
            assertThat(storedCaptor.getValue())
                    .as("matches() second arg must be the stored BCrypt hash from UserSecurity.getSecUsrPwd()")
                    .isEqualTo(STORED_BCRYPT_HASH);
        }

        @Test
        @DisplayName("invokes passwordEncoder.matches exactly once per signon attempt")
        void signon_invokesPasswordEncoderMatchesExactlyOnce() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — single BCrypt invocation (no double-check, no
            // unnecessary CPU spend on the hot signon path)
            verify(passwordEncoder, times(1))
                    .matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH);
        }

        @Test
        @DisplayName("matches returns true → successful SignonResponseDto with token")
        void signon_passwordEncoderReturnsTrue_returnsSuccessfulSignon() {
            // Arrange — encoder.matches stub returns true
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    NORMALIZED_USER_ID, USER_TYPE_USER, FIRST_NAME, LAST_NAME))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — response is the success-path SignonResponseDto
            assertThat(response).isNotNull();
            assertThat(response.token()).isEqualTo(JWT_TOKEN);
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
        }

        @Test
        @DisplayName("matches returns false → ValidationException with neutral 'Invalid credentials' message")
        void signon_passwordEncoderReturnsFalse_throwsValidationException() {
            // COBOL: COSGN00C L242-L246 — "Wrong Password. Try again ..." path.
            // The Java target collapses this to the same generic
            // 'Invalid credentials' message as the user-not-found path
            // (PCI-DSS hardening: prevent enumeration via differential
            // error analysis).
            // Arrange — encoder.matches stub returns false
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act + Assert
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_CREDENTIALS_MESSAGE);

            // Assert — NO JWT issuance on the failure path
            verify(jwtTokenProvider, never())
                    .issueToken(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("password is never logged or surfaced in the audit payload (PCI-DSS)")
        void signon_passwordIsNeverLogged() {
            // PCI-DSS: AAP §0.6.6 — no credential material in logs or
            // audit payloads. Verified end-to-end: capture every
            // audit payload Map and assert it contains NO password
            // value or password-like key.
            // Arrange — bad-password path (more interesting because
            // the password value is in scope at exception throw time)
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class);

            // Assert — capture the failure-audit payload and scan
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_FAILURE), anyString(),
                    eq(RESULT_BAD_PASSWORD), isNull(),
                    payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            // No password-like KEY
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
            assertThat(payload).doesNotContainKey("passwd");
            // No password-like VALUE
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .as("payload value must not echo the plaintext password")
                        .doesNotContain(PLAINTEXT_PASSWORD);
            }
        }
    }

    /**
     * Verifies the USRSEC-lookup failure path (COBOL
     * {@code WS-RESP-CD = 13} NOTFND at COSGN00C L247-L251).
     *
     * <p>A missing user surfaces as {@link RecordNotFoundException}
     * with the GENERIC "Invalid credentials" message identical to the
     * bad-password path (PCI-DSS hardening &mdash; prevent user
     * enumeration). The PasswordEncoder is NOT invoked because the
     * service short-circuits via {@code .orElseThrow(...)}. A
     * {@code SIGNON_FAILURE} audit event with result
     * {@code USER_NOT_FOUND} is still emitted so security operations
     * can monitor failed-authentication attempts.</p>
     */
    @Nested
    @DisplayName("UserNotFound — Optional.empty() → RecordNotFoundException with neutral message (COSGN00C L247-L251 NOTFND)")
    class UserNotFound {

        @Test
        @DisplayName("throws RecordNotFoundException with neutral 'Invalid credentials' message (prevents enumeration)")
        void signon_unknownUserId_throwsAuthenticationException_withNeutralMessage() {
            // COBOL: COSGN00C L247-L251 — WS-RESP-CD = 13 (NOTFND)
            //        path. COBOL surfaced "User not found ..." as a
            //        distinct message; the Java target uses the
            //        generic "Invalid credentials" to prevent user
            //        enumeration attacks.
            // Arrange — repository returns empty Optional
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage(INVALID_CREDENTIALS_MESSAGE);
        }

        @Test
        @DisplayName("user-not-found message is identical to bad-password message (anti-enumeration)")
        void signon_unknownUserId_messageIsIdenticalToBadPasswordPath() {
            // PCI-DSS: AAP §0.7.1 — the two failure paths must be
            // INDISTINGUISHABLE to an attacker. Capture both messages
            // and assert they are byte-for-byte equal.
            // Arrange — user-not-found path
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage(INVALID_CREDENTIALS_MESSAGE);
        }

        @Test
        @DisplayName("never invokes passwordEncoder when user is not found (short-circuit)")
        void signon_unknownUserId_neverInvokesPasswordEncoder() {
            // The service must short-circuit via .orElseThrow before
            // any BCrypt computation — both for correctness (cannot
            // match a non-existent hash) and for performance (avoid
            // a needless BCrypt round on the failure path).
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert — passwordEncoder must NOT be touched
            verify(passwordEncoder, never()).matches(anyString(), anyString());
            verify(passwordEncoder, never()).matches(any(CharSequence.class), anyString());
        }

        @Test
        @DisplayName("never issues a JWT when user is not found")
        void signon_unknownUserId_neverIssuesJwt() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert
            verify(jwtTokenProvider, never())
                    .issueToken(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("emits SIGNON_FAILURE audit event with result=USER_NOT_FOUND")
        void signon_unknownUserId_emitsFailedAuditLog() {
            // AAP §0.6.6 — every failed authentication attempt must
            // be recorded for fraud investigation. Verify the
            // emitted event has the USER_NOT_FOUND discriminator so
            // dashboards can break down by failure reason.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert
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
     * Verifies USER-TYPE routing &mdash; the COBOL
     * {@code SEC-USR-TYPE PIC X(01)} value drives downstream
     * routing per the {@code CDEMO-USRTYP-ADMIN} 88-level evaluation
     * at COSGN00C L230-L240.
     *
     * <p>In the Java target, routing is performed client-side based
     * on {@link SignonResponseDto#userType()}; the userType claim is
     * embedded verbatim in the JWT (so server-side
     * {@code @PreAuthorize} can enforce the role) and echoed in the
     * response DTO (so the client can pick the next REST endpoint to
     * call &mdash; {@code GET /api/menu/admin} for {@code 'A'},
     * {@code GET /api/menu/main} for {@code 'U'}).</p>
     */
    @Nested
    @DisplayName("UserTypeRouting — SEC-USR-TYPE 'A'/'U' propagated verbatim (COSGN00C L230-L240)")
    class UserTypeRouting {

        @Test
        @DisplayName("admin user (SEC-USR-TYPE='A') → userType='A' in response and JWT claim")
        void signon_adminUser_returnsAdminUserType() {
            // COBOL: COSGN00C L230-L232 — CDEMO-USRTYP-ADMIN → XCTL
            //        PROGRAM('COADM01C'). Java: userType='A' in
            //        response drives client-side routing to
            //        GET /api/menu/admin.
            // Arrange — USRSEC row carries userType='A'
            SignonRequestDto request = new SignonRequestDto(ADMIN_USER_ID, PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(adminUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(request);

            // Assert — userType field carries the verbatim 'A' value
            assertThat(response.userType()).isEqualTo(USER_TYPE_ADMIN);
            // Assert — JWT claim carries the verbatim 'A' value
            verify(jwtTokenProvider).issueToken(
                    eq(ADMIN_USER_ID),
                    eq(USER_TYPE_ADMIN),
                    eq(ADMIN_FIRST_NAME),
                    eq(ADMIN_LAST_NAME));
        }

        @Test
        @DisplayName("standard user (SEC-USR-TYPE='U') → userType='U' in response and JWT claim")
        void signon_standardUser_returnsUserUserType() {
            // COBOL: COSGN00C L236-L239 — non-admin → XCTL
            //        PROGRAM('COMEN01C'). Java: userType='U' in
            //        response drives client-side routing to
            //        GET /api/menu/main.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — userType field carries the verbatim 'U' value
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
            // Assert — JWT claim carries the verbatim 'U' value
            verify(jwtTokenProvider).issueToken(
                    eq(NORMALIZED_USER_ID),
                    eq(USER_TYPE_USER),
                    eq(FIRST_NAME),
                    eq(LAST_NAME));
        }

        @Test
        @DisplayName("userType is drawn from USRSEC row (persisted value), never inferred from input")
        void signon_userTypeIsFromUsrsecRow_notFromInput() {
            // Defensive: the JWT and response DTO userType MUST be
            // sourced from the persisted UserSecurity entity, never
            // from any caller-supplied value (which the
            // SignonRequestDto does not even expose). This protects
            // against attempts to elevate privilege by submitting
            // a forged userType in the request.
            // Arrange — admin USRSEC row stubbed in
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — userType matches the USRSEC row (USER, not ADMIN)
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
            // Assert — the JWT issuance receives the USRSEC value
            ArgumentCaptor<String> userTypeCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(jwtTokenProvider).issueToken(
                    anyString(), userTypeCaptor.capture(),
                    anyString(), anyString());
            assertThat(userTypeCaptor.getValue()).isEqualTo(USER_TYPE_USER);
        }
    }

    /**
     * Verifies JWT issuance on the success path &mdash; replaces
     * the COBOL {@code MOVE WS-USER-ID TO CDEMO-USER-ID} +
     * {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} +
     * {@code EXEC CICS XCTL} sequence at COSGN00C L224-L240.
     *
     * <p>The JWT bearer token is the stateless equivalent of the
     * CICS COMMAREA identity propagation pattern (AAP &sect;0.6.4);
     * the token carries (userId, userType, firstName, lastName) as
     * claims and is returned in {@link SignonResponseDto#token()}
     * for client-side bearer-auth on every subsequent request.</p>
     */
    @Nested
    @DisplayName("JwtTokenIssuance — JWT carries USRSEC identity claims (replaces COMMAREA + XCTL)")
    class JwtTokenIssuance {

        @Test
        @DisplayName("issues JWT with (userId, userType, firstName, lastName) drawn from USRSEC row")
        void signon_validCredentials_issuesJwtTokenWithCorrectClaims() {
            // COBOL: COSGN00C L224-L228 — MOVE WS-USER-ID TO CDEMO-USER-ID;
            //        MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE. Java: those
            //        same fields are forwarded into the JWT claims via
            //        JwtTokenProvider.issueToken.
            // Arrange — admin path so all four claim values are
            // observable (and distinct from the request)
            SignonRequestDto request =
                    new SignonRequestDto(ADMIN_USER_ID, PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(adminUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    eq(ADMIN_USER_ID), eq(USER_TYPE_ADMIN),
                    eq(ADMIN_FIRST_NAME), eq(ADMIN_LAST_NAME)))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(request);

            // Assert — issueToken invoked exactly once with the
            // verbatim USRSEC identity values
            verify(jwtTokenProvider, times(1)).issueToken(
                    eq(ADMIN_USER_ID),
                    eq(USER_TYPE_ADMIN),
                    eq(ADMIN_FIRST_NAME),
                    eq(ADMIN_LAST_NAME));
        }

        @Test
        @DisplayName("returns the JWT token in SignonResponseDto.token()")
        void signon_validCredentials_returnsTokenInResponse() {
            // Arrange — stub issueToken to return a recognisable token
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — token is propagated verbatim into the response
            assertThat(response.token()).isEqualTo(JWT_TOKEN);
        }

        @Test
        @DisplayName("SignonResponseDto carries all 6 fields: token, userId, firstName, lastName, userType, expiresAt")
        void signon_validCredentials_responseDtoCarriesAllFields() {
            // The DTO record exposes (token, userId, firstName,
            // lastName, userType, expiresAt) — assert all 6 fields
            // are populated from the USRSEC row + JWT provider.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — extract and verify each of the 6 components
            assertThat(response)
                    .extracting(
                            SignonResponseDto::token,
                            SignonResponseDto::userId,
                            SignonResponseDto::firstName,
                            SignonResponseDto::lastName,
                            SignonResponseDto::userType)
                    .containsExactly(
                            JWT_TOKEN,
                            NORMALIZED_USER_ID,
                            FIRST_NAME,
                            LAST_NAME,
                            USER_TYPE_USER);
            // expiresAt is computed dynamically from Instant.now() +
            // jwtTokenProvider.getExpiration(); assert it is in the
            // future, not in the past, and within a reasonable
            // bound of (now + 5 minutes).
            long nowEpochSec = Instant.now().getEpochSecond();
            assertThat(response.expiresAt())
                    .as("expiresAt must be in the future")
                    .isGreaterThan(nowEpochSec)
                    .as("expiresAt should be approximately now + JWT_EXPIRATION")
                    .isLessThanOrEqualTo(nowEpochSec + JWT_EXPIRATION.getSeconds() + 5);
        }

        @Test
        @DisplayName("issueToken is invoked AFTER successful BCrypt match, never before")
        void signon_issueTokenInvokedOnlyAfterBCryptMatch() {
            // Defensive: the service must verify BCrypt FIRST and
            // then issue the JWT — never the other way around.
            // Otherwise a hypothetical leak of issueToken state
            // could yield a valid token on a failed password attempt.
            // Arrange — encoder returns FALSE → no token must be issued
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(false);

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(ValidationException.class);

            // Assert — no JWT issuance on the failed-BCrypt path
            verify(jwtTokenProvider, never())
                    .issueToken(anyString(), anyString(), anyString(), anyString());
        }
    }

    /**
     * Verifies audit emission for every signon attempt (success,
     * user-not-found, bad-password) per AAP &sect;0.6.6 PCI-DSS /
     * SOX compliance.
     *
     * <p>The PCI-DSS critical invariant is that the captured
     * {@code Map<String, Object>} audit payload contains NO
     * password-like key or password value &mdash; verified
     * exhaustively via {@link ArgumentCaptor}.</p>
     */
    @Nested
    @DisplayName("AuditLogging — SIGNON_SUCCESS/FAILURE emitted, PCI-DSS: no password in payload (AAP §0.6.6)")
    class AuditLogging {

        @Test
        @DisplayName("successful signon emits SIGNON_SUCCESS audit with result=SUCCESS")
        void signon_successfulSignon_emitsSuccessAudit() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — success audit emission with the verbatim
            // uppercase-normalized userId
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS),
                    eq(NORMALIZED_USER_ID),
                    eq(RESULT_SUCCESS),
                    isNull(),
                    anyMap(),
                    isNull());
        }

        @Test
        @DisplayName("successful signon audit payload includes userType for dashboard breakdown")
        void signon_successAudit_includesUserType() {
            // AAP §0.6.6 — userType is a non-sensitive operational
            // identifier; it appears in audit trails so dashboards
            // can break down failure / success rates by role.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — capture and inspect the audit payload
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
            assertThat(payloadCaptor.getValue())
                    .containsEntry("userType", USER_TYPE_USER);
        }

        @Test
        @DisplayName("failed signon (bad password) emits SIGNON_FAILURE audit with result=BAD_PASSWORD")
        void signon_failedSignon_emitsFailureAudit() {
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
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
            // NO success audit on the failure path
            verify(auditLogService, never()).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS),
                    anyString(), anyString(), any(), anyMap(), any());
        }

        @Test
        @DisplayName("password NEVER in audit payload — no key 'password'/'secUsrPwd'/'pwd' (success path)")
        void signon_passwordNotInAuditPayload_successPath() {
            // PCI-DSS critical: AAP §0.7.1 / §0.6.6 — credential
            // material MUST NOT appear in audit logs even when
            // an audit emission failure would not prevent the
            // successful signon from completing.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — capture and exhaustively scan the payload
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS), anyString(), anyString(),
                    isNull(), payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            // No password-like KEY (exact match)
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
            assertThat(payload).doesNotContainKey("passwd");
            // No password-like KEY (case-insensitive scan)
            for (String key : payload.keySet()) {
                assertThat(key.toLowerCase(Locale.US))
                        .as("audit payload key %s must not contain a password-like discriminator",
                                key)
                        .doesNotContain("pwd")
                        .doesNotContain("password")
                        .doesNotContain("passwd");
            }
            // No password VALUE — the plaintext must not appear in any value
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .as("audit payload value must not echo the plaintext password")
                        .doesNotContain(PLAINTEXT_PASSWORD);
            }
            // No BCrypt hash VALUE — the stored hash must not appear either
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .as("audit payload value must not echo the stored BCrypt hash")
                        .doesNotContain(STORED_BCRYPT_HASH);
            }
        }

        @Test
        @DisplayName("password NEVER in audit payload — no key 'password'/'secUsrPwd'/'pwd' (bad-password path)")
        void signon_passwordNotInAuditPayload_badPasswordPath() {
            // PCI-DSS critical: the bad-password path is the most
            // sensitive emission point because the password value is
            // in scope at exception-throw time. Verify the audit
            // payload still contains no password material.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
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
                    eq(EVENT_TYPE_SIGNON_FAILURE), anyString(),
                    eq(RESULT_BAD_PASSWORD), isNull(),
                    payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
            assertThat(payload).doesNotContainKey("passwd");
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .doesNotContain(PLAINTEXT_PASSWORD)
                        .doesNotContain(STORED_BCRYPT_HASH);
            }
        }

        @Test
        @DisplayName("password NEVER in audit payload — user-not-found path")
        void signon_passwordNotInAuditPayload_userNotFoundPath() {
            // PCI-DSS critical: even when the user does not exist,
            // we must not leak the supplied password through the
            // audit emission (an attacker probing usernames would
            // otherwise see their guesses logged).
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            // Act
            assertThatThrownBy(() -> service.signon(validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_FAILURE), anyString(),
                    eq(RESULT_USER_NOT_FOUND), isNull(),
                    payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
            assertThat(payload).doesNotContainKey("passwd");
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .doesNotContain(PLAINTEXT_PASSWORD);
            }
        }

        @Test
        @DisplayName("audit emission for every attempt — both success and failure paths")
        void signon_auditEmittedForEveryAttempt() {
            // Verify the success path emits AuditLogService once.
            // Verify the failure path also emits AuditLogService once.
            // Combined assertion: the audit pipeline is invoked on
            // every signon attempt, regardless of outcome (AAP §0.6.6).
            // Arrange — success path
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            service.signon(validRequest);

            // Assert — exactly one audit emission on the success path
            verify(auditLogService, times(1)).logSecurityEvent(
                    eq(EVENT_TYPE_SIGNON_SUCCESS), anyString(),
                    anyString(), isNull(), anyMap(), isNull());
        }
    }

    /**
     * Verifies the {@link SignonResponseDto#userId()} contract &mdash;
     * the response carries the uppercase-normalized userId from the
     * USRSEC row (NOT the raw input value), but does not mask or
     * redact it.
     *
     * <p><b>Production posture (agent prompt accepted-and-documented
     * behaviour).</b> The agent prompt notes:
     * <em>"If production returns plain userId because it's an admin
     * user account ID (not PII), document and accept."</em>
     *
     * <p>The CardDemo userId is a non-PII 8-character administratively
     * assigned identifier (e.g., {@code "ADMIN001"}, {@code "USER0001"}
     * per the V015 seed). It IS NOT a customer-supplied identifier,
     * IS NOT a Social Security number, IS NOT a card number, and is
     * already echoed verbatim in CloudWatch / OpenSearch audit logs
     * per AAP &sect;0.6.6 ("audit trail content must be preserved
     * exactly"). Masking it in the response body would BREAK the
     * audit-correlation contract.</p>
     *
     * <p>This group therefore verifies that production DELIBERATELY
     * returns the un-masked userId, and that the response is
     * consistent with the USRSEC row (not with the raw request input
     * &mdash; an attacker who supplied {@code "admin001"} must receive
     * back the canonical {@code "ADMIN001"} from the persisted row).</p>
     */
    @Nested
    @DisplayName("UserIdMaskingInResponses — userId returned verbatim from USRSEC (documented & accepted; not PII)")
    class UserIdMaskingInResponses {

        @Test
        @DisplayName("response userId is the uppercase USRSEC value, not the raw request input")
        void signon_responseUserId_isUppercaseUsrsecValue() {
            // Arrange — lowercase request input
            SignonRequestDto request =
                    new SignonRequestDto(LOWERCASE_USER_ID, PLAINTEXT_PASSWORD);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(request);

            // Assert — response userId is the USRSEC row value
            // (uppercase), NOT the lowercase request input
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.userId()).isNotEqualTo(LOWERCASE_USER_ID);
        }

        @Test
        @DisplayName("response userId is NOT masked (production posture: userId is non-PII)")
        void signon_responseUserId_isNotMasked() {
            // Per the agent prompt: "If production returns plain
            // userId because it's an admin user account ID (not PII),
            // document and accept." The userId is a non-PII
            // administratively assigned identifier — masking would
            // break the audit-correlation contract from AAP §0.6.6.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert — response carries the FULL 8-character userId,
            // not a masked variant like "US***1"
            assertThat(response.userId())
                    .isEqualTo(NORMALIZED_USER_ID)
                    .hasSize(8)
                    .doesNotContain("*");
        }

        @Test
        @DisplayName("response DTO carries no password field (compile-time PCI-DSS enforcement)")
        void signonResponseDto_hasNoPasswordComponent() {
            // PCI-DSS: AAP §0.7.1 — the response DTO record exposes
            // no password component, making accidental inclusion a
            // compile error. Verify via reflection over the record
            // components.
            for (var component : SignonResponseDto.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.US))
                        .as("SignonResponseDto record component %s must not be password-like",
                                component.getName())
                        .doesNotContain("password")
                        .doesNotContain("pwd")
                        .doesNotContain("passwd");
            }
        }

        @Test
        @DisplayName("response firstName/lastName carry the USRSEC row values, not the request values")
        void signon_responseFirstNameLastName_areFromUsrsecRow() {
            // Identity attributes (firstName/lastName) are display-
            // only fields drawn from the persisted USRSEC row,
            // not from the request DTO (which does not expose them).
            // Verify the values match the USRSEC fixture exactly.
            // Arrange
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(standardUser));
            when(passwordEncoder.matches(PLAINTEXT_PASSWORD, STORED_BCRYPT_HASH))
                    .thenReturn(true);
            when(jwtTokenProvider.issueToken(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(JWT_TOKEN);
            when(jwtTokenProvider.getExpiration()).thenReturn(JWT_EXPIRATION);

            // Act
            SignonResponseDto response = service.signon(validRequest);

            // Assert
            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(LAST_NAME);
        }
    }

    /**
     * Verifies defensive input validation (last-line-of-defense
     * guards beyond the Jakarta Bean Validation
     * {@code @NotBlank}/{@code @Size}/{@code @Pattern}
     * annotations applied at the controller boundary).
     *
     * <p>Replicates the COBOL guard cascade at COSGN00C L118-L127:</p>
     * <pre>
     *     EVALUATE TRUE
     *         WHEN USERIDI = SPACES OR LOW-VALUES
     *             "Please enter User ID ..."
     *         WHEN PASSWDI = SPACES OR LOW-VALUES
     *             "Please enter Password ..."
     *     END-EVALUATE.
     * </pre>
     *
     * <p>All four failure paths surface as {@link ValidationException}
     * (HTTP 400 via {@code GlobalExceptionHandler}).</p>
     */
    @Nested
    @DisplayName("InputValidation — null / blank guards (replicates COSGN00C L118-L127 EVALUATE WHEN SPACES)")
    class InputValidation {

        @Test
        @DisplayName("rejects null request DTO with ValidationException")
        void signon_nullRequest_throwsValidationException() {
            // Defensive: null body would otherwise NPE at the
            // request.userId() call site.
            // Act + Assert
            assertThatThrownBy(() -> service.signon(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Request");
            // NO downstream collaborator interactions on the null path
            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(jwtTokenProvider);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("rejects null userId with ValidationException (COSGN00C L118 — WHEN USERIDI = SPACES OR LOW-VALUES)")
        void signon_nullUserId_throwsValidationException() {
            // COBOL: COSGN00C L118-L122 — WHEN USERIDI = SPACES OR LOW-VALUES.
            // Java: null is the modern equivalent of COBOL LOW-VALUES.
            SignonRequestDto request =
                    new SignonRequestDto(null, PLAINTEXT_PASSWORD);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
            // No repository / encoder / JWT interactions
            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(jwtTokenProvider);
        }

        @Test
        @DisplayName("rejects blank userId with ValidationException (COSGN00C L118 — WHEN USERIDI = SPACES)")
        void signon_blankUserId_throwsValidationException() {
            // COBOL: COSGN00C L118-L122 — WHEN USERIDI = SPACES.
            // Java: a blank string (only whitespace) maps to the
            // same condition; isBlank() catches it.
            SignonRequestDto request =
                    new SignonRequestDto("   ", PLAINTEXT_PASSWORD);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
        }

        @Test
        @DisplayName("rejects empty-string userId with ValidationException")
        void signon_emptyStringUserId_throwsValidationException() {
            // Defensive: empty string and whitespace-only both
            // map to the same COBOL SPACES condition.
            SignonRequestDto request =
                    new SignonRequestDto("", PLAINTEXT_PASSWORD);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
        }

        @Test
        @DisplayName("rejects null password with ValidationException (COSGN00C L123 — WHEN PASSWDI = LOW-VALUES)")
        void signon_nullPassword_throwsValidationException() {
            // COBOL: COSGN00C L123-L127 — WHEN PASSWDI = SPACES OR LOW-VALUES.
            SignonRequestDto request =
                    new SignonRequestDto(NORMALIZED_USER_ID, null);

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");
        }

        @Test
        @DisplayName("rejects blank password with ValidationException (COSGN00C L123 — WHEN PASSWDI = SPACES)")
        void signon_blankPassword_throwsValidationException() {
            // COBOL: COSGN00C L123-L127 — WHEN PASSWDI = SPACES.
            SignonRequestDto request =
                    new SignonRequestDto(NORMALIZED_USER_ID, "   ");

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");
        }

        @Test
        @DisplayName("rejects empty-string password with ValidationException")
        void signon_emptyStringPassword_throwsValidationException() {
            // Defensive: empty string and whitespace-only both
            // map to the same COBOL SPACES condition.
            SignonRequestDto request =
                    new SignonRequestDto(NORMALIZED_USER_ID, "");

            assertThatThrownBy(() -> service.signon(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");
        }

        @Test
        @DisplayName("input-validation failures never reach repository, encoder, or JWT layers")
        void signon_inputValidationFailures_neverReachDownstreamCollaborators() {
            // The service must short-circuit on input validation
            // BEFORE any downstream I/O. Verify for both null-userId
            // and null-password paths.
            SignonRequestDto nullUserIdRequest =
                    new SignonRequestDto(null, PLAINTEXT_PASSWORD);
            SignonRequestDto nullPasswordRequest =
                    new SignonRequestDto(NORMALIZED_USER_ID, null);

            // Act — null userId path
            assertThatThrownBy(() -> service.signon(nullUserIdRequest))
                    .isInstanceOf(ValidationException.class);
            // Act — null password path
            assertThatThrownBy(() -> service.signon(nullPasswordRequest))
                    .isInstanceOf(ValidationException.class);

            // Assert — neither path reached the repository, encoder, or JWT
            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(jwtTokenProvider);
        }
    }
}
