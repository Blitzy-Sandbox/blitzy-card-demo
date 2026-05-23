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
import com.awsm2.carddemo.dto.UserDeleteDto;
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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link UserDeleteService}.
 *
 * <p><b>COBOL provenance.</b> {@link UserDeleteService} translates the
 * CICS COBOL program {@code app/cbl/COUSR03C.cbl} (CICS transaction id
 * {@code CU03}, file {@code 'USRSEC  '}) into a Java {@code @Service}
 * class per the AAP &sect;0.7.1 one-service-per-COBOL-program rule
 * ("Isolate each COBOL program's logic in its own dedicated Java
 * service class"). These tests verify the eight core behavioural
 * invariants surfaced by the source program:</p>
 *
 * <ol>
 *   <li><b>userId trim + uppercase normalization</b> &mdash; matches
 *       the upper-case keying of {@code USRSEC} VSAM records. The
 *       service applies {@code String.toUpperCase(Locale.US)} after
 *       {@code String.trim()}; {@code Locale.US} is explicit and
 *       deterministic to avoid the Turkish-locale dotless-i hazard.</li>
 *   <li><b>userId presence and length validation</b> &mdash; mirrors
 *       the COBOL {@code PROCESS-ENTER-KEY} guard at line 145 of
 *       {@code app/cbl/COUSR03C.cbl} that emits
 *       <em>"User ID can NOT be empty..."</em> on a blank
 *       {@code USRIDIN}. The Java target additionally enforces an
 *       8-character upper bound matching the
 *       {@code SEC-USR-ID PIC X(08)} layout in {@code CSUSR01Y.cpy}
 *       (the COBOL BMS map physically truncates input at 8 chars; the
 *       REST surface has no such limit and must defend
 *       explicitly).</li>
 *   <li><b>Y/N confirmation flag</b> &mdash; replaces the COBOL
 *       {@code EVALUATE EIBAID} dispatch in {@code MAIN-PARA}
 *       (lines 108&ndash;130 of {@code COUSR03C.cbl}): {@code PF5}
 *       drives the delete branch ({@code Y}), {@code PF4} cancels
 *       ({@code N}), and any other AID key triggers the
 *       {@code CCDA-MSG-INVALID-KEY} feedback (any other value &rarr;
 *       {@link ValidationException}).</li>
 *   <li><b>RecordNotFoundException on missing record</b> &mdash;
 *       mirrors the COBOL {@code DFHRESP(NOTFND)} branch of
 *       {@code READ-USER-SEC-FILE} (line 287 of {@code COUSR03C.cbl})
 *       that emits <em>"User ID NOT found..."</em>. The Java target
 *       translates this to the typed
 *       {@link RecordNotFoundException} (HTTP 404 via
 *       {@code GlobalExceptionHandler}).</li>
 *   <li><b>delete invocation cardinality</b> &mdash; verifies that
 *       {@link UserSecurityRepository#delete(Object)} is invoked
 *       exactly once when {@code confirm = "Y"} (replacing the COBOL
 *       {@code EXEC CICS DELETE DATASET('USRSEC')} at lines
 *       307&ndash;311 of {@code COUSR03C.cbl}), and is never invoked
 *       when {@code confirm = "N"} or when {@code confirm} is
 *       invalid. The non-Y paths must leave the database
 *       untouched.</li>
 *   <li><b>Audit emission on success</b> &mdash; verifies that
 *       {@link AuditLogService#logSecurityEvent(String, String,
 *       String, String, java.util.Map, String)} is invoked exactly
 *       once after a successful delete, with the event type
 *       containing the substring {@code "DELETE"} (case-insensitive
 *       &mdash; the production constant is {@code "user.deleted"})
 *       per the PCI-DSS / SOX audit requirements of AAP &sect;0.6.6.
 *       Cancelled and invalid paths must emit no audit event.</li>
 *   <li><b>PCI-DSS: password NEVER in audit payload</b> &mdash; the
 *       central PCI-DSS rule from AAP &sect;0.6.6 and &sect;0.7.1
 *       ("no credential material in application logs"). The captured
 *       audit-payload {@code Map<String, Object>} is asserted to
 *       contain NEITHER {@code "password"} NOR {@code "secUsrPwd"}
 *       NOR {@code "pwd"} NOR any case-variant thereof. Even though
 *       the production code stores a BCrypt hash (not plaintext) per
 *       the security upgrade in AAP &sect;0.1.1, the rule still
 *       applies &mdash; the hash is credential material and must not
 *       appear in OpenSearch indexes.</li>
 *   <li><b>Response DTO does not leak credentials</b> &mdash; the
 *       returned {@link UserDeleteDto} carries only the four
 *       non-sensitive display fields ({@code userId},
 *       {@code firstName}, {@code lastName}, {@code userType}) plus
 *       the operator-input {@code confirm} flag. The BCrypt hash
 *       held on the persisted {@link UserSecurity#getSecUsrPwd()} is
 *       intentionally NOT copied into the response payload (the
 *       {@link UserDeleteDto} record has no {@code password}
 *       component at the type level &mdash; a structural
 *       guarantee).</li>
 * </ol>
 *
 * <p><b>Test taxonomy.</b> The {@link Nested} groups below mirror the
 * agent-prompt-specified behavioural taxonomy:</p>
 *
 * <ul>
 *   <li>{@link ConfirmationFlag} &mdash; Y &rarr; delete, N &rarr;
 *       cancel, anything else &rarr; {@link ValidationException}.
 *       Includes case-insensitive normalization ({@code "y"},
 *       {@code "n"}), whitespace trimming, and the null /
 *       missing-request envelope checks.</li>
 *   <li>{@link UserIdNormalization} &mdash; uppercase + trim
 *       normalization of {@code userId} before the
 *       {@link UserSecurityRepository#findById(Object)} lookup, plus
 *       the blank / null / over-length guard.</li>
 *   <li>{@link RecordNotFound} &mdash;
 *       {@link UserSecurityRepository#findById(Object)} returning
 *       {@link Optional#empty()} surfaces as
 *       {@link RecordNotFoundException}, and on that path NO delete
 *       and NO audit event must occur.</li>
 *   <li>{@link AuditLogging} &mdash;
 *       {@link AuditLogService#logSecurityEvent} is invoked exactly
 *       once on success and never on cancellation / validation
 *       failure. The captured event type, target userId, and result
 *       are asserted on the success path.</li>
 *   <li>{@link PiiHandling} &mdash; PCI-DSS sentinel checks: the
 *       captured audit payload must contain neither the user's BCrypt
 *       hash nor any key that looks like {@code "password"},
 *       {@code "pwd"}, or {@code "secUsrPwd"}; and the returned DTO
 *       must omit the password field structurally.</li>
 *   <li>{@link ResponseShape} &mdash; verifies the returned
 *       {@link UserDeleteDto} fields are populated correctly from
 *       the loaded {@link UserSecurity} entity on the Y path, and
 *       reflect the cancellation echo on the N path.</li>
 * </ul>
 *
 * <p><b>Mockito configuration.</b>
 * {@code @ExtendWith(MockitoExtension.class)} activates the Mockito
 * JUnit 5 extension, providing strict stubbing enforcement that fails
 * tests on unused stubs (so the suite stays clean as it evolves).
 * Each test sets up only the stubs it actually needs &mdash; the
 * {@code @BeforeEach} fixture sets up only the in-memory
 * {@link UserSecurity} entity, with no Mockito {@code when(...)}
 * calls, to avoid cross-test stub pollution.</p>
 *
 * <p><b>COBOL: COUSR03C &mdash; USER DELETE.</b></p>
 *
 * @see UserDeleteService
 * @see UserSecurityRepository
 * @see AuditLogService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService — COUSR03C Y/N confirmation")
class UserDeleteServiceTest {

    // ------------------------------------------------------------------
    // Test fixture constants
    //
    // These constants intentionally mirror the COBOL CSUSR01Y.cpy /
    // COUSR03C.cbl source-of-truth values so that the tests double as
    // documentation of the cross-reference between the COBOL source
    // and the Java target.
    // ------------------------------------------------------------------

    /**
     * Canonical user identifier used across the success-path tests.
     * 8 characters &mdash; the exact width of {@code SEC-USR-ID
     * PIC X(08)} in {@code app/cpy/CSUSR01Y.cpy}:L18 and the
     * {@code sec_usr_id VARCHAR(8) PRIMARY KEY} column declared by
     * Flyway migration {@code V010__create_user_security.sql}. Matches
     * one of the V015 default seeds.
     */
    private static final String NORMALIZED_USER_ID = "USER0001";

    /**
     * Lower-cased variant of {@link #NORMALIZED_USER_ID} used to
     * exercise the upper-case normalization path in the service. The
     * service applies {@code .trim().toUpperCase(Locale.US)} before
     * the {@link UserSecurityRepository#findById(Object)} lookup, so
     * passing this value MUST result in a {@code findById} call keyed
     * by {@link #NORMALIZED_USER_ID}.
     */
    private static final String LOWERCASE_USER_ID = "user0001";

    /**
     * Confirmation flag &mdash; proceed with the delete. Replaces the
     * COBOL {@code WHEN DFHPF5 PERFORM DELETE-USER-INFO} dispatch at
     * line 121 of {@code app/cbl/COUSR03C.cbl}.
     */
    private static final String CONFIRM_YES = "Y";

    /**
     * Confirmation flag &mdash; cancel without deleting. Replaces the
     * COBOL {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} dispatch
     * at line 119 of {@code app/cbl/COUSR03C.cbl}.
     */
    private static final String CONFIRM_NO = "N";

    /**
     * Synthetic BCrypt-like hash placed on the test fixture's
     * {@code secUsrPwd} field so the PII discipline tests can prove
     * that even a non-null credential value does not leak into the
     * audit payload or the response DTO. The literal is NOT a real
     * BCrypt hash &mdash; the persistence layer is fully mocked, so
     * any 60-character payload suffices to verify the absence
     * assertions.
     */
    private static final String FAKE_BCRYPT_HASH =
            "$2b$12$abcdefghijklmnopqrstuvWXYZ0123456789ABCDEFGHIJabcdefghijkl";

    /**
     * Mock Spring Data JPA repository replacing CICS access to the
     * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} dataset (per the AAP
     * &sect;0.6.2 VSAM &rarr; RDS migration). The service under test
     * receives this mock via Mockito's {@link InjectMocks @InjectMocks}
     * constructor wiring; tests stub {@link UserSecurityRepository#findById(Object)}
     * with {@code Optional.of(existingUser)} for the happy path and
     * {@link Optional#empty()} for the
     * {@link RecordNotFoundException} path, and verify
     * {@link UserSecurityRepository#delete(Object)} invocation
     * cardinality (once on {@code confirm = "Y"}, never on {@code "N"}
     * or invalid input).
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mock OpenSearch + CloudWatch audit adapter replacing the COBOL
     * source's distributed {@code DISPLAY} statements (per AAP
     * &sect;0.6.6 audit consolidation). Tests use
     * {@link ArgumentCaptor} on
     * {@link AuditLogService#logSecurityEvent(String, String, String,
     * String, Map, String)} to assert (a) the event type contains
     * {@code "DELETE"} on the success path, (b) the captured
     * {@code Map<String, Object>} payload does NOT contain any
     * password-like key (the PCI-DSS rule), and (c) no audit event is
     * emitted when the operator cancels with {@code N} or supplies an
     * invalid confirm value.
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * System under test &mdash; the Java service that replaces the
     * COBOL/CICS program {@code app/cbl/COUSR03C.cbl}.
     * {@link InjectMocks @InjectMocks} instantiates this service via
     * its constructor, automatically supplying the two
     * {@link Mock @Mock}-annotated collaborators above (the only
     * constructor declared on {@link UserDeleteService} takes exactly
     * those two arguments). No reflective field injection is performed
     * because the service uses constructor injection only per the AAP
     * &sect;0.7.1 layered-architecture rule.
     */
    @InjectMocks
    private UserDeleteService service;

    /**
     * In-memory {@link UserSecurity} fixture re-built before every
     * test. Tests stub
     * {@link UserSecurityRepository#findById(Object)} with
     * {@code Optional.of(existingUser)} on the success path; the PII
     * discipline tests then assert that this entity's
     * {@link UserSecurity#getSecUsrPwd()} (set to a synthetic BCrypt
     * hash) does NOT leak into the captured audit payload or the
     * returned {@link UserDeleteDto}.
     *
     * <p>Re-built per test (not stored as a static constant) so
     * mutations made by one test (if any) cannot bleed across to
     * another.</p>
     */
    private UserSecurity existingUser;

    /**
     * Test-fixture setup &mdash; constructs a fresh
     * {@link UserSecurity} fixture before every test. No Mockito
     * {@code when(...)} stubs are configured here; per-test stubs are
     * declared inside the individual test methods to keep the strict
     * Mockito stubbing contract (unused stubs would fail under
     * {@code MockitoExtension}).
     *
     * <p>The fixture deliberately mirrors a typical {@code V015}
     * seed user (8-char alphanumeric ID, role discriminator
     * {@code 'U'}) and additionally sets a synthetic BCrypt-like
     * hash on {@code secUsrPwd} so the PII discipline tests can prove
     * that the production code never leaks the credential field
     * &mdash; not into the audit payload, not into the response DTO,
     * and not into the SLF4J log statements.</p>
     */
    @BeforeEach
    void setUp() {
        // COBOL: COUSR03C:READ-USER-SEC-FILE — the existingUser shape
        // mirrors a row that a successful EXEC CICS READ DATASET('USRSEC')
        // would have populated into the SEC-USER-DATA layout
        // (CSUSR01Y.cpy:L17–L23).
        existingUser = new UserSecurity();
        existingUser.setSecUsrId(NORMALIZED_USER_ID);
        existingUser.setSecUsrFname("JANE");
        existingUser.setSecUsrLname("DOE");
        // Set the credential field so the PiiHandling tests can prove
        // that the BCrypt hash never leaves the persistence layer.
        // In production the value would be a real BCrypt(strength=12)
        // hash assigned by UserAddService / UserUpdateService.
        existingUser.setSecUsrPwd(FAKE_BCRYPT_HASH);
        existingUser.setSecUsrType("U");
    }

    // ==================================================================
    // @Nested test groups — one per behavioural cluster
    // ==================================================================

    /**
     * Tests for the binary Y/N confirmation flag. Replaces the COBOL
     * {@code EVALUATE EIBAID} dispatch in
     * {@code COUSR03C.cbl:MAIN-PARA} (lines 108&ndash;130):
     * {@code DFHPF5} &rarr; delete, {@code DFHPF4} &rarr; clear/cancel,
     * any other AID key &rarr; {@code CCDA-MSG-INVALID-KEY}.
     */
    @Nested
    @DisplayName("ConfirmationFlag — Y deletes, N cancels, other throws")
    class ConfirmationFlag {

        /**
         * On {@code confirm = "Y"}, the service MUST issue exactly
         * one call to {@link UserSecurityRepository#delete(Object)},
         * passing the entity returned by {@code findById}. Replaces
         * COBOL: COUSR03C:DELETE-USER-SEC-FILE (lines 305&ndash;336)
         * &mdash; {@code EXEC CICS DELETE DATASET('USRSEC')}.
         */
        @Test
        @DisplayName("confirm='Y' invokes delete exactly once on the loaded entity")
        void deleteUser_confirmY_deletesRecord() {
            // COBOL: COUSR03C:READ-USER-SEC-FILE — repository returns
            // the user; service proceeds to DELETE-USER-SEC-FILE.
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            // COBOL: COUSR03C:DELETE-USER-SEC-FILE — verify the
            // DELETE verb was issued exactly once with the loaded
            // entity (Spring Data delete(Object) signature).
            verify(userSecurityRepository, times(1)).delete(existingUser);
            // findById must be invoked exactly once with the normalized id.
            verify(userSecurityRepository, times(1)).findById(NORMALIZED_USER_ID);

            // Response carries the operator's confirmation flag echoed
            // back as 'Y' so the caller can render a success message
            // — see Step 7 in UserDeleteService.deleteUser javadoc.
            assertThat(response).isNotNull();
            assertThat(response.confirm()).isEqualTo(CONFIRM_YES);
        }

        /**
         * On {@code confirm = "N"}, the service MUST cancel
         * gracefully: NO {@code findById}, NO {@code delete}, NO
         * audit event. Replaces COBOL: COUSR03C:MAIN-PARA L119
         * &mdash; {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN}.
         */
        @Test
        @DisplayName("confirm='N' never invokes delete and emits no audit event")
        void deleteUser_confirmN_doesNotDelete() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_NO);

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            // COBOL: COUSR03C:MAIN-PARA L119 — graceful cancellation.
            // The repository is never touched on the cancellation path.
            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
            verify(userSecurityRepository, never()).findById(anyString());
            verifyNoInteractions(auditLogService);

            assertThat(response).isNotNull();
            assertThat(response.confirm()).isEqualTo(CONFIRM_NO);
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
        }

        /**
         * On {@code confirm = "X"} (or any value other than Y/N
         * after trim+uppercase), the service MUST throw
         * {@link ValidationException} (HTTP 400). Replaces COBOL:
         * COUSR03C:MAIN-PARA L126&ndash;L129 &mdash;
         * {@code WHEN OTHER MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE}.
         */
        @Test
        @DisplayName("confirm='X' throws ValidationException with Y/N diagnostic")
        void deleteUser_confirmInvalid_throwsValidation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, "X");

            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Y/N");

            // COBOL: WHEN OTHER — the service must reject before any
            // repository or audit interaction.
            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * On {@code confirm = null} (operator pressed ENTER without
         * setting the flag), the service MUST throw
         * {@link ValidationException}. Replaces the implicit COBOL
         * AID-key-required precondition in
         * {@code COUSR03C.cbl:MAIN-PARA}.
         */
        @Test
        @DisplayName("confirm=null throws ValidationException")
        void deleteUser_confirmNull_throwsValidation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, null);

            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class);

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * On a {@code null} request body, the service MUST throw
         * {@link ValidationException}. The REST equivalent of a
         * missing BMS map {@code RECEIVE}: cannot determine operator
         * intent.
         */
        @Test
        @DisplayName("request=null throws ValidationException")
        void deleteUser_nullRequest_throwsValidation() {
            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, null))
                    .isInstanceOf(ValidationException.class);

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * Lower-case {@code "y"} must normalize to {@code "Y"} via
         * {@code String.trim().toUpperCase(Locale.US)} (the service
         * uses {@code Locale.US} explicitly so the test is
         * locale-independent).
         */
        @Test
        @DisplayName("confirm='y' (lower-case) deletes after Locale.US normalization")
        void deleteUser_confirmLowercaseY_deletesRecord() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null,
                    "y".toLowerCase(Locale.US));

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            verify(userSecurityRepository, times(1)).delete(existingUser);
            assertThat(response.confirm()).isEqualTo(CONFIRM_YES);
        }

        /**
         * Lower-case {@code "n"} must normalize to {@code "N"} and
         * cancel without touching the repository.
         */
        @Test
        @DisplayName("confirm='n' (lower-case) cancels after Locale.US normalization")
        void deleteUser_confirmLowercaseN_doesNotDelete() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null,
                    "n".toLowerCase(Locale.US));

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
            verifyNoInteractions(auditLogService);
            assertThat(response.confirm()).isEqualTo(CONFIRM_NO);
        }

        /**
         * Whitespace-padded {@code " Y "} must trim to {@code "Y"}
         * and proceed with the delete. Mirrors the COBOL
         * AID-key-comparison tolerance for the {@code USRIDIN}
         * field which carries trailing blanks at length 8.
         */
        @Test
        @DisplayName("confirm=' Y ' trims to 'Y' and deletes")
        void deleteUser_confirmTrimmedY_deletesRecord() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, " Y ");

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            verify(userSecurityRepository, times(1)).delete(existingUser);
            assertThat(response.confirm()).isEqualTo(CONFIRM_YES);
        }
    }

    /**
     * Tests for {@code userId} normalization. The service applies
     * {@code String.trim().toUpperCase(Locale.US)} before the
     * {@link UserSecurityRepository#findById(Object)} lookup, matching
     * the uppercase keying of VSAM {@code USRSEC} records (the COBOL
     * sign-on path applies {@code FUNCTION UPPER-CASE} at
     * {@code COSGN00C.cbl}:L132 to mirror this contract).
     */
    @Nested
    @DisplayName("UserIdNormalization — trim + Locale.US uppercase")
    class UserIdNormalization {

        /**
         * Lower-case input MUST be uppercased before the repository
         * lookup so that the always-uppercase {@code SEC-USR-ID}
         * primary key matches. Replaces COBOL:
         * COUSR03C:DELETE-USER-INFO (line 189) where
         * {@code MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID} relies on
         * the upstream {@code USRIDIN} field already being uppercase
         * (the COBOL BMS map enforces this physically via the
         * {@code ATTRB=(UNPROT,FSET,IC)} attributes).
         */
        @Test
        @DisplayName("lower-case userId is uppercased before findById")
        void deleteUser_userId_uppercasedBeforeLookup() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(LOWERCASE_USER_ID, request);

            // findById must be invoked with the uppercase normalized id.
            verify(userSecurityRepository, times(1))
                    .findById(eq(NORMALIZED_USER_ID));
            // It must NOT be invoked with the raw lower-case id.
            verify(userSecurityRepository, never())
                    .findById(eq(LOWERCASE_USER_ID));
        }

        /**
         * Surrounding whitespace MUST be trimmed before the
         * uppercase fold. Verifies the
         * {@code String.trim().toUpperCase(Locale.US)} chain.
         */
        @Test
        @DisplayName("whitespace-padded userId is trimmed before findById")
        void deleteUser_userId_trimmedBeforeLookup() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser("  user0001  ", request);

            verify(userSecurityRepository, times(1))
                    .findById(eq(NORMALIZED_USER_ID));
        }

        /**
         * {@code userId = null} MUST throw {@link ValidationException}.
         * Replaces COBOL: COUSR03C:PROCESS-ENTER-KEY (L145&ndash;L150)
         * &mdash; {@code WHEN USRIDINI = SPACES OR LOW-VALUES
         * MOVE 'User ID can NOT be empty...' TO WS-MESSAGE}.
         */
        @Test
        @DisplayName("userId=null throws ValidationException")
        void deleteUser_userIdNull_throwsValidation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser(null, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * Blank {@code userId} (only whitespace) MUST throw
         * {@link ValidationException}. Mirrors the COBOL
         * {@code SPACES OR LOW-VALUES} test on {@code USRIDINI}.
         */
        @Test
        @DisplayName("userId='   ' (blank) throws ValidationException")
        void deleteUser_userIdBlank_throwsValidation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser("   ", request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("empty");

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * Empty {@code userId} (zero-length string) MUST throw
         * {@link ValidationException}. {@code String.isBlank()} also
         * matches the empty string.
         */
        @Test
        @DisplayName("userId='' (empty) throws ValidationException")
        void deleteUser_userIdEmpty_throwsValidation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser("", request))
                    .isInstanceOf(ValidationException.class);

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * {@code userId} longer than 8 characters MUST throw
         * {@link ValidationException}. The COBOL source does not
         * check this explicitly because the BMS field
         * {@code USRIDIN} has {@code LENGTH=8} which physically
         * truncates terminal input; the REST surface has no such
         * limit and must defend explicitly.
         */
        @Test
        @DisplayName("userId longer than 8 chars throws ValidationException")
        void deleteUser_userIdTooLong_throwsValidation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser("USER00012", request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("8");

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(auditLogService);
        }

        /**
         * {@code userId} of exactly 8 characters MUST be accepted.
         * Boundary test for the {@code SEC-USR-ID PIC X(08)} layout.
         */
        @Test
        @DisplayName("userId of exactly 8 chars is accepted")
        void deleteUser_userIdAtMaxLength_accepted() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            UserDeleteDto response = service.deleteUser(
                    NORMALIZED_USER_ID, request);

            verify(userSecurityRepository, times(1)).delete(existingUser);
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
        }
    }

    /**
     * Tests for the {@code findById}-returns-empty path. Replaces
     * COBOL: COUSR03C:READ-USER-SEC-FILE L287&ndash;L292
     * ({@code WHEN DFHRESP(NOTFND) MOVE 'User ID NOT found...' TO
     * WS-MESSAGE}). The Java target translates this to
     * {@link RecordNotFoundException} (HTTP 404).
     */
    @Nested
    @DisplayName("RecordNotFound — findById empty → RecordNotFoundException")
    class RecordNotFound {

        /**
         * On {@code findById} returning {@link Optional#empty()}, the
         * service MUST throw {@link RecordNotFoundException}.
         * Replaces COBOL: COUSR03C:READ-USER-SEC-FILE L287&ndash;L292
         * (the {@code DFHRESP(NOTFND)} branch).
         */
        @Test
        @DisplayName("findById empty throws RecordNotFoundException")
        void deleteUser_userNotFound_throwsRecordNotFound() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        /**
         * The {@link RecordNotFoundException} message MUST contain
         * the COBOL diagnostic text <em>"User ID NOT found..."</em>
         * (verbatim from {@code COUSR03C.cbl}:L289) so downstream
         * consumers (UI translations, support runbooks) can
         * recognise the legacy message form.
         */
        @Test
        @DisplayName("RecordNotFoundException message contains 'User ID NOT found'")
        void deleteUser_userNotFound_messageMatchesCobolDiagnostic() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("User ID NOT found");
        }

        /**
         * When {@code findById} returns empty, the service MUST NOT
         * invoke {@link UserSecurityRepository#delete(Object)} and
         * MUST NOT emit an audit event. The orElseThrow chain in the
         * production code raises {@link RecordNotFoundException}
         * before either side-effect can execute.
         */
        @Test
        @DisplayName("RecordNotFound path performs no delete and emits no audit")
        void deleteUser_recordNotFound_doesNotDeleteOrAudit() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.empty());

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(userSecurityRepository, never())
                    .delete(any(UserSecurity.class));
            verifyNoInteractions(auditLogService);
        }
    }

    /**
     * Tests for audit emission. Per AAP &sect;0.6.6 (Cross-Cutting:
     * Audit, Observability, and PCI-DSS), every successful delete
     * MUST emit a security audit event through
     * {@link AuditLogService#logSecurityEvent(String, String, String,
     * String, Map, String)}, and every NON-success path MUST NOT.
     */
    @Nested
    @DisplayName("AuditLogging — logSecurityEvent invoked once on Y, never otherwise")
    class AuditLogging {

        /**
         * On a successful delete, {@link AuditLogService#logSecurityEvent}
         * MUST be invoked exactly once. The event type must contain
         * the substring {@code "DELETE"} (case-insensitive) to satisfy
         * the agent-prompt requirement "eventType containing 'DELETE'"
         * &mdash; the production constant is {@code "user.deleted"}
         * which contains {@code "delete"} case-insensitively.
         */
        @Test
        @DisplayName("logSecurityEvent invoked once with DELETE-bearing event type")
        void deleteUser_emitsAuditEvent_onSuccess() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            // Capture the event type so we can assert it contains "DELETE".
            ArgumentCaptor<String> eventTypeCaptor =
                    ArgumentCaptor.forClass(String.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    eventTypeCaptor.capture(),
                    anyString(),
                    anyString(),
                    any(),
                    anyMap(),
                    any());

            String capturedEventType = eventTypeCaptor.getValue();
            assertThat(capturedEventType).isNotNull();
            // The agent prompt mandates "eventType containing 'DELETE'".
            // The production constant is "user.deleted" — assert
            // case-insensitive containment for robustness.
            assertThat(capturedEventType.toUpperCase(Locale.US))
                    .contains("DELETE");
        }

        /**
         * The captured target {@code userId} on the audit event must
         * match the normalized identifier (uppercase, 8 chars). This
         * is the AAP &sect;0.7.2 verbatim-preservation rule applied
         * to operator codes: the COBOL audit trail records the user
         * ID exactly as it appears in {@code SEC-USR-ID} after
         * uppercase keying.
         */
        @Test
        @DisplayName("audit event carries the normalized userId")
        void deleteUser_emitsAuditEvent_capturesNormalizedUserId() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            // Pass a lower-case input to prove the audit event carries
            // the UPPERCASE normalized identifier (matches SEC-USR-ID
            // VSAM keying contract).
            service.deleteUser(LOWERCASE_USER_ID, request);

            ArgumentCaptor<String> userIdCaptor =
                    ArgumentCaptor.forClass(String.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    userIdCaptor.capture(),
                    anyString(),
                    any(),
                    anyMap(),
                    any());

            assertThat(userIdCaptor.getValue())
                    .isEqualTo(NORMALIZED_USER_ID);
        }

        /**
         * The captured {@code result} field on the audit event must
         * carry the {@code SUCCESS} sentinel on the happy path.
         * Mirrors the AuditLogService contract: result is a metric
         * tag that drives failure-rate alarms in CloudWatch.
         */
        @Test
        @DisplayName("audit event carries result='SUCCESS' on the happy path")
        void deleteUser_emitsAuditEvent_capturesSuccessResult() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            ArgumentCaptor<String> resultCaptor =
                    ArgumentCaptor.forClass(String.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    anyString(),
                    resultCaptor.capture(),
                    any(),
                    anyMap(),
                    any());

            assertThat(resultCaptor.getValue()).isEqualTo("SUCCESS");
        }

        /**
         * On {@code confirm = "N"}, NO audit event must be emitted.
         * The cancellation is not a security-significant event;
         * recording it would inflate dashboards and false-positive
         * compliance alarms.
         */
        @Test
        @DisplayName("confirm='N' emits no audit event")
        void deleteUser_confirmN_emitsNoAuditEvent() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_NO);

            service.deleteUser(NORMALIZED_USER_ID, request);

            verifyNoInteractions(auditLogService);
        }

        /**
         * On {@code confirm = "X"} (or any other invalid value),
         * NO audit event must be emitted. The service throws
         * {@link ValidationException} before reaching the audit
         * invocation point.
         */
        @Test
        @DisplayName("confirm='X' (invalid) emits no audit event")
        void deleteUser_confirmInvalid_emitsNoAuditEvent() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, "X");

            assertThatThrownBy(() ->
                    service.deleteUser(NORMALIZED_USER_ID, request))
                    .isInstanceOf(ValidationException.class);

            verifyNoInteractions(auditLogService);
        }

        /**
         * The audit payload must contain the deleted user's
         * {@code userType} ({@code "A"} or {@code "U"}). Compliance
         * dashboards need to distinguish admin-account deletions
         * (high-risk) from regular-user deletions (low-risk).
         */
        @Test
        @DisplayName("audit payload carries userType for compliance dashboards")
        void deleteUser_auditPayload_carriesUserType() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    payloadCaptor.capture(),
                    any());

            Map<String, Object> captured = payloadCaptor.getValue();
            assertThat(captured).isNotNull();
            // The userType key carries the role discriminator (A or U)
            // — see UserDeleteService line 605 auditPayload.put("userType", ...).
            assertThat(captured).containsKey("userType");
            assertThat(captured.get("userType")).isEqualTo("U");
        }
    }

    /**
     * PCI-DSS / SOX PII discipline tests (AAP &sect;0.6.6,
     * &sect;0.7.1). The central rule: <b>no credential material
     * appears in application logs, audit indexes, or HTTP
     * responses.</b> The BCrypt hash stored on
     * {@link UserSecurity#getSecUsrPwd()} is credential material and
     * must NEVER leak through the audit payload or the response DTO.
     */
    @Nested
    @DisplayName("PiiHandling — password NEVER in audit payload or response")
    class PiiHandling {

        /**
         * The captured audit-payload {@code Map} must NOT contain a
         * key named {@code "password"} (case-insensitive). This is
         * the central PCI-DSS sentinel.
         */
        @Test
        @DisplayName("audit payload contains no 'password' key (any case)")
        void deleteUser_auditPayload_doesNotContainPassword() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    payloadCaptor.capture(),
                    any());

            Map<String, Object> captured = payloadCaptor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.keySet())
                    .noneMatch(k -> k.toLowerCase(Locale.US)
                            .contains("password"));
        }

        /**
         * The captured audit-payload {@code Map} must NOT contain a
         * key named {@code "pwd"} (case-insensitive). PCI-DSS rule
         * — covers the abbreviated key variants common in legacy
         * COBOL field names (e.g., {@code SEC-USR-PWD}).
         */
        @Test
        @DisplayName("audit payload contains no 'pwd' key (any case)")
        void deleteUser_auditPayload_doesNotContainPwd() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    payloadCaptor.capture(),
                    any());

            Map<String, Object> captured = payloadCaptor.getValue();
            assertThat(captured).isNotNull();
            // Defensive: also reject 'pwd', 'passwd', 'PWD' etc.
            assertThat(captured.keySet())
                    .noneMatch(k -> k.equalsIgnoreCase("pwd")
                            || k.equalsIgnoreCase("passwd"));
        }

        /**
         * The captured audit-payload {@code Map} must NOT contain a
         * key named {@code "secUsrPwd"} (the COBOL-field-name
         * variant). Belt-and-suspenders verification against
         * accidental field-name copy-paste.
         */
        @Test
        @DisplayName("audit payload contains no 'secUsrPwd' key (any case)")
        void deleteUser_auditPayload_doesNotContainSecUsrPwd() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    payloadCaptor.capture(),
                    any());

            Map<String, Object> captured = payloadCaptor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.keySet())
                    .noneMatch(k -> k.equalsIgnoreCase("secUsrPwd"));
        }

        /**
         * No VALUE in the captured audit-payload {@code Map} may
         * contain the fixture BCrypt-like hash. Even though Java's
         * default {@link Object#hashCode()}/{@link Object#equals(Object)}
         * mean a stray serialization could land the hash in an
         * unexpected key, this defensive scan catches any case where
         * the hash string accidentally bleeds through.
         */
        @Test
        @DisplayName("audit payload values contain no BCrypt hash string")
        void deleteUser_auditPayload_valuesContainNoBcryptHash() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            service.deleteUser(NORMALIZED_USER_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);

            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    payloadCaptor.capture(),
                    any());

            Map<String, Object> captured = payloadCaptor.getValue();
            assertThat(captured).isNotNull();
            // No value should equal the fixture hash; no value should
            // even contain it as a substring (defense in depth).
            for (Object value : captured.values()) {
                if (value != null) {
                    String s = value.toString();
                    assertThat(s).doesNotContain(FAKE_BCRYPT_HASH);
                    // Also reject the BCrypt format prefix.
                    assertThat(s).doesNotContain("$2b$");
                    assertThat(s).doesNotContain("$2a$");
                    assertThat(s).doesNotContain("$2y$");
                }
            }
        }

        /**
         * The returned {@link UserDeleteDto} must NOT carry the
         * BCrypt hash, the COBOL plaintext password, or anything that
         * resembles credential material. Verified structurally
         * (the DTO record has no {@code password} component) and
         * behaviourally (no field returns the hash).
         */
        @Test
        @DisplayName("response DTO carries no password field or BCrypt-like value")
        void deleteUser_responseDto_doesNotContainPassword() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            // Structural — none of the five record components carries
            // the BCrypt hash (the four display fields + confirm).
            assertThat(response.userId()).isNotEqualTo(FAKE_BCRYPT_HASH);
            assertThat(response.firstName()).isNotEqualTo(FAKE_BCRYPT_HASH);
            assertThat(response.lastName()).isNotEqualTo(FAKE_BCRYPT_HASH);
            assertThat(response.userType()).isNotEqualTo(FAKE_BCRYPT_HASH);
            assertThat(response.confirm()).isNotEqualTo(FAKE_BCRYPT_HASH);

            // toString() must not contain the hash either — Java's
            // default record toString concatenates field values and
            // a stray BCrypt hash would surface there.
            assertThat(response.toString()).doesNotContain(FAKE_BCRYPT_HASH);
            assertThat(response.toString()).doesNotContain("$2b$");
        }

        /**
         * Structural assertion: the {@link UserDeleteDto} record has
         * NO {@code password} accessor at the Java class level. This
         * proves the absence of the field at the type system rather
         * than at runtime, and catches any future careless addition
         * of a password component to the DTO record.
         */
        @Test
        @DisplayName("UserDeleteDto record declares no 'password' accessor")
        void deleteUser_responseDto_hasNoPasswordAccessor() {
            // Reflectively introspect the record components — if any
            // component is named with a password-like token, fail.
            java.lang.reflect.RecordComponent[] components =
                    UserDeleteDto.class.getRecordComponents();
            assertThat(components).isNotNull();
            for (java.lang.reflect.RecordComponent component : components) {
                String name = component.getName().toLowerCase(Locale.US);
                assertThat(name).doesNotContain("password");
                assertThat(name).doesNotContain("pwd");
                assertThat(name).doesNotContain("secret");
            }
        }
    }

    /**
     * Tests for the shape and content of the returned
     * {@link UserDeleteDto} envelope on both the success path and
     * the cancellation path. Mirrors the COBOL
     * {@code COUSR3AO} symbolic-map population in
     * {@code COUSR03C.cbl:DELETE-USER-SEC-FILE} (lines 315&ndash;322).
     */
    @Nested
    @DisplayName("ResponseShape — DTO fields reflect the operation outcome")
    class ResponseShape {

        /**
         * On {@code confirm = "Y"}, the returned DTO must carry the
         * deleted user's four display fields ({@code userId},
         * {@code firstName}, {@code lastName}, {@code userType})
         * plus {@code confirm = "Y"}. Replaces the COBOL
         * {@code STRING 'User ' SEC-USR-ID ' has been deleted ...'}
         * post-delete message at lines 318&ndash;321 of
         * {@code COUSR03C.cbl} &mdash; the Java response gives the
         * caller enough context to render the same message.
         */
        @Test
        @DisplayName("confirm='Y' response carries the four display fields")
        void deleteUser_confirmY_returnsDtoWithDeletedDetails() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.firstName()).isEqualTo("JANE");
            assertThat(response.lastName()).isEqualTo("DOE");
            assertThat(response.userType()).isEqualTo("U");
            assertThat(response.confirm()).isEqualTo(CONFIRM_YES);
        }

        /**
         * On {@code confirm = "N"}, the returned DTO must echo the
         * {@code userId} and the cancellation flag ({@code "N"}).
         * The display fields are NOT populated because the
         * cancellation path does NOT issue a {@code findById}
         * lookup; their values may legitimately be {@code null} (the
         * service does not invent data on a cancellation).
         */
        @Test
        @DisplayName("confirm='N' response echoes userId and N flag")
        void deleteUser_confirmN_returnsDtoWithCancellation() {
            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_NO);

            UserDeleteDto response = service.deleteUser(NORMALIZED_USER_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.confirm()).isEqualTo(CONFIRM_NO);
            // The cancellation path does NOT load the user record, so
            // display fields legitimately remain null. This is the
            // observable contract — the caller renders an "operation
            // cancelled" message without needing the display fields.
            assertThat(response.firstName()).isNull();
            assertThat(response.lastName()).isNull();
            assertThat(response.userType()).isNull();
        }

        /**
         * On the success path, the returned {@code userId} must be
         * the UPPERCASE normalized identifier regardless of the
         * lower-case input. Reinforces the
         * {@link UserIdNormalization} group's contract at the
         * response-shape level.
         */
        @Test
        @DisplayName("response userId is uppercase normalized even for lower-case input")
        void deleteUser_responseDtoUserId_isUppercaseNormalized() {
            when(userSecurityRepository.findById(NORMALIZED_USER_ID))
                    .thenReturn(Optional.of(existingUser));

            UserDeleteDto request = new UserDeleteDto(
                    null, null, null, null, CONFIRM_YES);

            UserDeleteDto response = service.deleteUser(LOWERCASE_USER_ID, request);

            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.userId()).isNotEqualTo(LOWERCASE_USER_ID);
        }
    }
}
