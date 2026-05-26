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
import com.awsm2.carddemo.dto.UserUpdateDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link UserUpdateService}.
 *
 * <p><b>COBOL provenance.</b> {@link UserUpdateService} translates
 * {@code app/cbl/COUSR02C.cbl} (CICS transaction id {@code CU02},
 * file {@code 'USRSEC  '}) per AAP &sect;0.7.1's
 * one-service-per-COBOL-program rule. The source program performs
 * {@code SEND MAP COUSR2A} &rarr; {@code RECEIVE MAP COUSR2A} &rarr;
 * {@code READ-USER-SEC-FILE} (L320&ndash;L353) &rarr;
 * {@code UPDATE-USER-INFO} (L179&ndash;L234) &rarr;
 * {@code UPDATE-USER-SEC-FILE} (L358&ndash;L390) which
 * {@code EXEC CICS REWRITE DATASET('USRSEC') FROM(SEC-USER-DATA)}.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Partial update semantics</b> &mdash; null/omitted fields
 *       in {@link UserUpdateDto} preserve the existing value;
 *       only explicitly-supplied non-blank fields are applied.</li>
 *   <li><b>Password re-hash policy (CP5 review)</b> &mdash; when
 *       a password is supplied, it is encoded VERBATIM via
 *       {@code passwordEncoder.encode(request.password())} &mdash;
 *       NEVER uppercased. Matches the verbatim-encode contract in
 *       {@link UserAddService} and the verbatim-match contract in
 *       {@link SignonService} (the cross-service password policy
 *       that the CP5 review mandated).</li>
 *   <li><b>userId uppercase + trim normalization</b> &mdash;
 *       {@code .trim().toUpperCase(Locale.US)} applied before
 *       {@link UserSecurityRepository#findById(Object)}.</li>
 *   <li><b>RecordNotFoundException on missing user</b> &mdash;
 *       maps to HTTP 404 via {@code GlobalExceptionHandler}.</li>
 *   <li><b>userType allowlist {{A, U}}</b> &mdash;
 *       {@link ValidationException} on any other value.</li>
 *   <li><b>Blank value rejection</b> &mdash; whitespace-only fields
 *       are rejected (a NULL means "keep existing" but a BLANK is
 *       an explicit-erase attempt and must fail).</li>
 *   <li><b>Audit emission</b> &mdash; exactly one
 *       {@link AuditLogService#logSecurityEvent} call on success,
 *       with {@code passwordChanged} flag in payload and NO
 *       password material.</li>
 *   <li><b>Response DTO carries {@code password=null}</b> &mdash;
 *       BCrypt hash never crosses the wire.</li>
 * </ol>
 *
 * <p><b>COBOL: COUSR02C &mdash; USER UPDATE with BCrypt re-hash.</b></p>
 *
 * @see UserUpdateService
 * @see UserAddServiceTest
 * @see SignonServiceTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService — COUSR02C with BCrypt re-hash (CP5 password-policy fix)")
class UserUpdateServiceTest {

    // ------------------------------------------------------------------
    // Test fixture constants
    // ------------------------------------------------------------------

    private static final String NORMALIZED_USER_ID = "USER0001";
    private static final String LOWERCASE_USER_ID = "user0001";
    private static final String ORIGINAL_FIRST_NAME = "JANE";
    private static final String ORIGINAL_LAST_NAME = "DOE";
    private static final String ORIGINAL_PWD_HASH =
            "$2a$12$originalAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String NEW_FIRST_NAME = "JANEY";
    private static final String NEW_LAST_NAME = "SMITH";
    private static final String NEW_PASSWORD = "NewPa55";
    private static final String NEW_BCRYPT_HASH =
            "$2a$12$newAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String USER_TYPE_USER = "U";
    private static final String USER_TYPE_ADMIN = "A";
    private static final String INVALID_USER_TYPE = "X";

    private static final String AUDIT_EVENT_TYPE = "USER_UPDATE";
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";

    // ------------------------------------------------------------------
    // Mock collaborators + system under test
    // ------------------------------------------------------------------

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserUpdateService service;

    /** Canonical existing-user fixture rebuilt before every test. */
    private UserSecurity existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new UserSecurity(
                NORMALIZED_USER_ID,
                ORIGINAL_FIRST_NAME,
                ORIGINAL_LAST_NAME,
                ORIGINAL_PWD_HASH,
                USER_TYPE_USER);
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("Password re-hash (CP5 fix — VERBATIM, no uppercase)")
    class PasswordReHash {

        @Test
        @DisplayName("hashes new password verbatim (NOT uppercased) when supplied")
        void updateUser_hashesPasswordVerbatim() {
            // Arrange — mixed-case new password
            String mixedCase = "NewPa55";
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, mixedCase, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(mixedCase)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.updateUser(NORMALIZED_USER_ID, request);

            // Assert — encode invoked with VERBATIM password
            verify(passwordEncoder).encode(eq(mixedCase));
            verify(passwordEncoder, never())
                    .encode(eq(mixedCase.toUpperCase(Locale.US)));
        }

        @Test
        @DisplayName("invokes encoder exactly once when password supplied")
        void updateUser_invokesEncoderOnce() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, NEW_PASSWORD, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            verify(passwordEncoder, times(1)).encode(NEW_PASSWORD);
        }

        @Test
        @DisplayName("persists BCrypt hash (never plaintext) on entity")
        void updateUser_persistsHashNotPlaintext() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, NEW_PASSWORD, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getSecUsrPwd())
                    .as("persisted secUsrPwd must be the BCrypt hash")
                    .isEqualTo(NEW_BCRYPT_HASH)
                    .isNotEqualTo(NEW_PASSWORD);
        }

        @Test
        @DisplayName("preserves existing hash when password omitted (null)")
        void updateUser_preservesExistingHashWhenPasswordNull() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            // Assert — encoder NEVER invoked when password is null
            verify(passwordEncoder, never()).encode(anyString());
            // Persisted entity still has the original hash
            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getSecUsrPwd()).isEqualTo(ORIGINAL_PWD_HASH);
        }

        @Test
        @DisplayName("rejects blank (whitespace-only) password with ValidationException")
        void updateUser_rejectsBlankPassword() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, "   ", null);

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");

            // Assert — no encoder call, no save
            verify(passwordEncoder, never()).encode(anyString());
            verify(userSecurityRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("userId normalization (Locale.US uppercase + trim)")
    class UserIdNormalization {

        @Test
        @DisplayName("uppercases lower-case path userId before findById")
        void updateUser_uppercasesLowercasePathId() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(LOWERCASE_USER_ID, request);

            verify(userSecurityRepository).findById(NORMALIZED_USER_ID);
            verify(userSecurityRepository, never()).findById(LOWERCASE_USER_ID);
        }

        @Test
        @DisplayName("trims whitespace from path userId before findById")
        void updateUser_trimsWhitespace() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser("  user0001  ", request);

            verify(userSecurityRepository).findById(NORMALIZED_USER_ID);
        }
    }

    @Nested
    @DisplayName("Partial update semantics (null preserves; explicit value updates)")
    class PartialUpdate {

        @Test
        @DisplayName("updates only firstName when only firstName supplied")
        void updateUser_partialFirstNameOnly() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(captor.capture());
            UserSecurity persisted = captor.getValue();
            assertThat(persisted.getSecUsrFname()).isEqualTo(NEW_FIRST_NAME);
            assertThat(persisted.getSecUsrLname()).isEqualTo(ORIGINAL_LAST_NAME);
            assertThat(persisted.getSecUsrType()).isEqualTo(USER_TYPE_USER);
            assertThat(persisted.getSecUsrPwd()).isEqualTo(ORIGINAL_PWD_HASH);
        }

        @Test
        @DisplayName("updates only userType when only userType supplied")
        void updateUser_partialUserTypeOnly() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, null, USER_TYPE_ADMIN);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(captor.capture());
            UserSecurity persisted = captor.getValue();
            assertThat(persisted.getSecUsrType()).isEqualTo(USER_TYPE_ADMIN);
            assertThat(persisted.getSecUsrFname()).isEqualTo(ORIGINAL_FIRST_NAME);
        }

        @Test
        @DisplayName("updates all four mutable fields when all supplied")
        void updateUser_fullUpdate() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, NEW_LAST_NAME, NEW_PASSWORD, USER_TYPE_ADMIN);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(captor.capture());
            UserSecurity persisted = captor.getValue();
            assertThat(persisted.getSecUsrFname()).isEqualTo(NEW_FIRST_NAME);
            assertThat(persisted.getSecUsrLname()).isEqualTo(NEW_LAST_NAME);
            assertThat(persisted.getSecUsrType()).isEqualTo(USER_TYPE_ADMIN);
            assertThat(persisted.getSecUsrPwd()).isEqualTo(NEW_BCRYPT_HASH);
        }
    }

    @Nested
    @DisplayName("Validation cascade (blank fields, invalid userType)")
    class Validation {

        @Test
        @DisplayName("rejects blank firstName with ValidationException")
        void updateUser_rejectsBlankFirstName() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, "   ", null, null, null);

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("First Name");
            verify(userSecurityRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects blank lastName with ValidationException")
        void updateUser_rejectsBlankLastName() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, "   ", null, null);

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Last Name");
            verify(userSecurityRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects blank userType with ValidationException")
        void updateUser_rejectsBlankUserType() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, null, "   ");

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User Type");
            verify(userSecurityRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects invalid userType X with ValidationException")
        void updateUser_rejectsInvalidUserType() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, null, INVALID_USER_TYPE);

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User Type");
        }

        @Test
        @DisplayName("rejects null request body with ValidationException")
        void updateUser_rejectsNullRequest() {
            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, null))
                    .isInstanceOf(ValidationException.class);
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("rejects blank path userId with ValidationException")
        void updateUser_rejectsBlankPathUserId() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);

            assertThatThrownBy(() -> service.updateUser("   ", request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("rejects path userId longer than 8 characters")
        void updateUser_rejectsOverlongPathUserId() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);

            assertThatThrownBy(() -> service.updateUser("VERYLONGID9", request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("maximum length");
        }
    }

    @Nested
    @DisplayName("User-not-found path")
    class UserNotFound {

        @Test
        @DisplayName("throws RecordNotFoundException when user does not exist")
        void updateUser_userNotFound_throws() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(userSecurityRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(anyString());
            verifyNoInteractions(auditLogService);
        }
    }

    @Nested
    @DisplayName("Audit emission (PCI-DSS — passwordChanged flag, no password material)")
    class AuditEmission {

        @Test
        @DisplayName("emits USER_UPDATE audit with passwordChanged=true when password supplied")
        void updateUser_emitsAuditWithPasswordChangedTrue() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, NEW_PASSWORD, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    eq(AUDIT_EVENT_TYPE),
                    eq(NORMALIZED_USER_ID),
                    eq(AUDIT_RESULT_SUCCESS),
                    isNull(),
                    payloadCaptor.capture(),
                    isNull());
            assertThat(payloadCaptor.getValue()).containsEntry("passwordChanged", true);
        }

        @Test
        @DisplayName("emits USER_UPDATE audit with passwordChanged=false when password omitted")
        void updateUser_emitsAuditWithPasswordChangedFalse() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(), anyString(), anyString(), isNull(),
                    payloadCaptor.capture(), isNull());
            assertThat(payloadCaptor.getValue()).containsEntry("passwordChanged", false);
        }

        @Test
        @DisplayName("audit payload contains NO password material (any case variant)")
        void updateUser_auditOmitsPasswordMaterial() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, NEW_PASSWORD, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(), anyString(), anyString(), isNull(),
                    payloadCaptor.capture(), isNull());
            Map<String, Object> payload = payloadCaptor.getValue();
            // Key-side: forbid any literal password-value key. The
            // intentional 'passwordChanged' boolean flag (Step 9 of
            // UserUpdateService) is allowed — it carries no credential
            // material, only the rotation indicator that security
            // dashboards filter on.
            assertThat(payload).doesNotContainKey("password");
            assertThat(payload).doesNotContainKey("secUsrPwd");
            assertThat(payload).doesNotContainKey("pwd");
            for (String key : payload.keySet()) {
                String lower = key.toLowerCase(Locale.US);
                if ("passwordchanged".equals(lower)) {
                    // Allow the boolean-flag key — it is not a value key.
                    continue;
                }
                assertThat(lower)
                        .as("audit payload key %s must not be password-like", key)
                        .doesNotContain("pwd")
                        .doesNotContain("password");
            }
            // Value-side check: plaintext / hash must not appear
            for (Object value : payload.values()) {
                assertThat(String.valueOf(value))
                        .doesNotContain(NEW_PASSWORD)
                        .doesNotContain(NEW_BCRYPT_HASH)
                        .doesNotContain(ORIGINAL_PWD_HASH);
            }
        }

        @Test
        @DisplayName("no audit emission on user-not-found path")
        void updateUser_noAuditOnUserNotFound() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, NEW_FIRST_NAME, null, null, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(RecordNotFoundException.class);

            verifyNoInteractions(auditLogService);
        }
    }

    @Nested
    @DisplayName("Response DTO (PCI-DSS — password component is null)")
    class ResponseShape {

        @Test
        @DisplayName("response DTO has password=null (BCrypt hash never returned)")
        void updateUser_responseHasNullPassword() {
            UserUpdateDto request = new UserUpdateDto(
                    NORMALIZED_USER_ID, null, null, NEW_PASSWORD, null);
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateDto response = service.updateUser(NORMALIZED_USER_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.password())
                    .as("PCI-DSS: password component MUST be null in response")
                    .isNull();
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
        }
    }
}
