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
import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.exception.DuplicateRecordException;
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

import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link UserAddService}.
 *
 * <p><b>COBOL provenance.</b> {@link UserAddService} translates the
 * CICS COBOL program {@code app/cbl/COUSR01C.cbl} (CICS transaction id
 * {@code CU01}, file {@code 'USRSEC  '}) into a Java {@code @Service}
 * class per the AAP &sect;0.7.1 one-service-per-COBOL-program rule
 * ("Isolate each COBOL program's logic in its own dedicated Java
 * service class"). The COBOL source performs the pseudo-conversational
 * flow {@code SEND MAP COUSR1A} &rarr; {@code RECEIVE MAP COUSR1A}
 * &rarr; {@code PROCESS-ENTER-KEY} (lines 115&ndash;160) &rarr;
 * {@code WRITE-USER-SEC-FILE} (lines 238&ndash;274). These tests
 * verify the seven core behavioural invariants surfaced by the source
 * program:</p>
 *
 * <ol>
 *   <li><b>BCrypt password hashing before save</b> &mdash; the
 *       AAP-mandated security upgrade from the legacy COBOL plaintext
 *       storage in {@code SEC-USR-PWD PIC X(08)} (AAP &sect;0.1.1 /
 *       &sect;0.7.1). The service calls
 *       {@link PasswordEncoder#encode(CharSequence)} exactly once with
 *       the operator-supplied password and persists only the resulting
 *       60-character BCrypt hash &mdash; never the plaintext.</li>
 *   <li><b>userId uppercase normalization</b> &mdash; matches the
 *       upper-case keying of {@code USRSEC} VSAM records. The service
 *       applies {@code String.trim().toUpperCase(Locale.US)} before
 *       both the {@code existsById} pre-check and the {@code save};
 *       {@code Locale.US} is explicit and deterministic to avoid the
 *       Turkish-locale dotless-i hazard that would silently corrupt
 *       the primary-key index in PostgreSQL.</li>
 *   <li><b>userType uppercase normalization + domain check</b>
 *       &mdash; the BMS map literal "(A=Admin, U=User)" at line 150
 *       of {@code app/bms/COUSR01.bms} documents the allowed values.
 *       The service applies the same trim+uppercase fold to the
 *       {@code userType} field and validates against the
 *       {@code {"A","U"}} allowlist, throwing
 *       {@link ValidationException} on any other value (HTTP 400 via
 *       {@code GlobalExceptionHandler}).</li>
 *   <li><b>Field-presence validation cascade</b> &mdash; replicates
 *       the COBOL {@code EVALUATE TRUE} first-match-wins cascade in
 *       {@code PROCESS-ENTER-KEY} (lines 115&ndash;151) which checks
 *       FNAME &rarr; LNAME &rarr; USERID &rarr; PASSWD &rarr; USRTYPE
 *       for {@code SPACES OR LOW-VALUES} in that order and exits at
 *       the first failure with a specific error message. Each blank
 *       field throws {@link ValidationException}.</li>
 *   <li><b>existsById-based DUPKEY detection</b> &mdash; mirrors the
 *       COBOL {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}
 *       branch of {@code WRITE-USER-SEC-FILE} (lines 260&ndash;266)
 *       that emits <em>"User ID already exist..."</em>. The Java
 *       target translates this to the typed
 *       {@link DuplicateRecordException} (HTTP 409 via
 *       {@code GlobalExceptionHandler}). The pre-check via
 *       {@link UserSecurityRepository#existsById(Object)} runs before
 *       {@code save} so no audit event or write is emitted on the
 *       duplicate path.</li>
 *   <li><b>Audit emission on success</b> &mdash; verifies that
 *       {@link AuditLogService#logSecurityEvent(String, String,
 *       String, String, Map, String)} is invoked exactly once after
 *       a successful user creation, with the event type containing
 *       the substring {@code "ADD"} (case-insensitive &mdash; the
 *       production constant is {@code "USER_ADD"}) per the
 *       PCI-DSS / SOX audit requirements of AAP &sect;0.6.6.
 *       Duplicate and validation paths emit no audit event.</li>
 *   <li><b>PCI-DSS: password NEVER in audit payload or response</b>
 *       &mdash; the central PCI-DSS rule from AAP &sect;0.6.6 and
 *       &sect;0.7.1 ("no credential material in application logs").
 *       The captured audit-payload {@code Map<String, Object>} is
 *       asserted to contain NEITHER {@code "password"} NOR
 *       {@code "secUsrPwd"} NOR {@code "pwd"} NOR any case-variant
 *       thereof. Even though the production code stores a BCrypt
 *       hash (not plaintext) per the security upgrade in AAP
 *       &sect;0.1.1, the rule still applies &mdash; the hash is
 *       credential material and must not appear in OpenSearch
 *       indexes. The returned {@link UserAddDto} carries
 *       {@code password=null} so the BCrypt hash never crosses the
 *       wire.</li>
 * </ol>
 *
 * <p><b>Test taxonomy.</b> The {@link Nested} groups below mirror the
 * agent-prompt-specified behavioural taxonomy (BCryptHashing,
 * UserIdNormalization, DuplicateUserId, FieldValidation, PiiHandling,
 * AuditLogging) with a few additional groups (UserTypeValidation,
 * ResponseShape) that document boundary cases observed during
 * Phase 1 discovery of the production {@link UserAddService} code
 * (e.g., the {@code userType} domain check at line 406 of the
 * service).</p>
 *
 * <p><b>Production behavior anchored by these tests.</b> The
 * production {@link UserAddService} (see
 * {@code src/main/java/com/awsm2/carddemo/service/UserAddService.java})
 * documents and implements the following exact contract that this
 * test suite locks in:</p>
 * <ul>
 *   <li>{@code passwordEncoder.encode(request.password())} is invoked
 *       <em>verbatim</em> on the operator-supplied password
 *       &mdash; the password is NOT uppercased before encoding (the
 *       COBOL signon path in {@code COSGN00C.cbl} also performs a
 *       case-sensitive password compare, so verbatim passing matches
 *       legacy parity).</li>
 *   <li>{@code userId} length validation is enforced by Jakarta
 *       Bean Validation on the {@link UserAddDto} record's
 *       {@code @Size(max = 8)} annotation at the controller boundary
 *       &mdash; the service relies on Spring's
 *       {@code MethodArgumentNotValidException} handler in
 *       {@code GlobalExceptionHandler} to reject over-length input
 *       before the service is invoked. Direct service calls with
 *       over-length identifiers are processed without a length
 *       check (the database layer's VARCHAR(8) constraint provides
 *       the final defense). This test suite verifies the service's
 *       runtime behavior, not the controller-layer Bean Validation.</li>
 *   <li>{@link DuplicateRecordException} is raised via the
 *       {@code existsById} pre-check pathway. The constructor used by
 *       the service is the {@code (reasonCode, message)} two-arg form
 *       passing {@code "UserSecurity"} as the reason code (an
 *       entity-name discriminator) and the human-readable diagnostic
 *       as the message &mdash; see line 427 of
 *       {@code UserAddService.java}.</li>
 * </ul>
 *
 * <p><b>Mockito configuration.</b>
 * {@code @ExtendWith(MockitoExtension.class)} activates the Mockito
 * JUnit 5 extension, providing strict stubbing enforcement that fails
 * tests on unused stubs (so the suite stays clean as it evolves).
 * Each test sets up only the stubs it actually needs. The
 * {@code @BeforeEach} fixture builds the canonical
 * {@link UserAddDto} fixtures, with no Mockito {@code when(...)} calls,
 * to avoid cross-test stub pollution.</p>
 *
 * <p><b>COBOL: COUSR01C &mdash; USER ADD with BCrypt password hash.</b></p>
 *
 * @see UserAddService
 * @see UserSecurityRepository
 * @see AuditLogService
 * @see PasswordEncoder
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService — COUSR01C with BCrypt password hash")
class UserAddServiceTest {

    // ------------------------------------------------------------------
    // Test fixture constants
    //
    // These constants intentionally mirror the COBOL CSUSR01Y.cpy /
    // COUSR01C.cbl source-of-truth values so that the tests double as
    // documentation of the cross-reference between the COBOL source
    // and the Java target.
    // ------------------------------------------------------------------

    /**
     * Canonical user identifier used across the success-path tests
     * (after uppercase normalization). 8 characters &mdash; the exact
     * width of {@code SEC-USR-ID PIC X(08)} in
     * {@code app/cpy/CSUSR01Y.cpy}:L18 and the
     * {@code sec_usr_id VARCHAR(8) PRIMARY KEY} column declared by
     * Flyway migration {@code V010__create_user_security.sql}.
     */
    private static final String NORMALIZED_USER_ID = "USER0001";

    /**
     * Lower-cased variant of {@link #NORMALIZED_USER_ID} used to
     * exercise the upper-case normalization path in the service. The
     * service applies {@code .trim().toUpperCase(Locale.US)} before
     * the {@link UserSecurityRepository#existsById(Object)} pre-check
     * and {@code save}, so passing this value MUST result in the
     * persisted {@link UserSecurity} carrying {@link #NORMALIZED_USER_ID}.
     */
    private static final String LOWERCASE_USER_ID = "user0001";

    /**
     * Canonical first name fixture &mdash; matches a typical
     * {@code V015} seed-data row and fits within the
     * {@code SEC-USR-FNAME PIC X(20)} layout in {@code CSUSR01Y.cpy}.
     */
    private static final String FIRST_NAME = "JANE";

    /**
     * Canonical last name fixture &mdash; matches a typical
     * {@code V015} seed-data row and fits within the
     * {@code SEC-USR-LNAME PIC X(20)} layout in {@code CSUSR01Y.cpy}.
     */
    private static final String LAST_NAME = "DOE";

    /**
     * Canonical plaintext password fixture, exactly 8 characters
     * to fit the legacy {@code SEC-USR-PWD PIC X(08)} layout.
     * Used to verify that the service passes this value verbatim to
     * {@link PasswordEncoder#encode(CharSequence)} &mdash; the BCrypt
     * security upgrade does NOT change the case-sensitive password
     * comparison semantics inherited from COBOL {@code COSGN00C}.
     */
    private static final String PLAINTEXT_PASSWORD = "Pa55w0rd";

    /**
     * Synthetic BCrypt-shaped hash returned by the mocked
     * {@link PasswordEncoder#encode(CharSequence)} stub. The
     * literal is not a real BCrypt hash &mdash; the encoder is fully
     * mocked, so any 60-character {@code $2a$}-prefixed string
     * suffices to verify (a) the saved
     * {@link UserSecurity#getSecUsrPwd()} equals this value (proving
     * the hash, not the plaintext, is persisted), and (b) the audit
     * payload and response DTO contain no occurrence of this string
     * (proving the credential material does not leak).
     */
    private static final String BCRYPT_HASH =
            "$2a$12$abcdefghijklmnopqrstuvWXYZ0123456789ABCDEFGHIJabcdefghijkl";

    /**
     * Role discriminator value for a regular user. Matches the COBOL
     * {@code SEC-USR-TYPE PIC X(01)} domain {@code "U"} documented at
     * line 150 of {@code app/bms/COUSR01.bms} ({@code "(A=Admin,
     * U=User)"}). Defense-in-depth enforced by the V010 CHECK
     * constraint {@code chk_user_security_type IN ('A', 'U')}.
     */
    private static final String USER_TYPE_USER = "U";

    /**
     * Role discriminator value for an admin user. Matches the COBOL
     * {@code SEC-USR-TYPE PIC X(01)} domain {@code "A"} documented at
     * line 150 of {@code app/bms/COUSR01.bms}.
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Lower-case variant of {@link #USER_TYPE_USER} used to exercise
     * the {@link Locale#US} uppercase normalization in the service.
     * Passing {@code "u"} MUST result in the persisted
     * {@link UserSecurity#getSecUsrType()} being {@code "U"}.
     */
    private static final String LOWERCASE_USER_TYPE = "u";

    /**
     * Invalid {@code userType} value used by the
     * {@link UserTypeValidation} group to verify the domain check
     * (the service's {@code VALID_USER_TYPES} allowlist enforces
     * {@code {"A","U"}}). Per AAP &sect;0.4.1, the rejection surfaces
     * as a {@link ValidationException} (HTTP 400).
     */
    private static final String INVALID_USER_TYPE = "X";

    /**
     * Production audit event-type constant emitted by
     * {@code UserAddService} on the success path. Used by the
     * {@link AuditLogging} group to verify the event-type discriminator.
     * The agent-prompt requires the captured event type to contain
     * the substring {@code "CREATE"} OR {@code "ADD"} &mdash; the
     * production constant {@code "USER_ADD"} contains {@code "ADD"}.
     */
    private static final String AUDIT_EVENT_TYPE = "USER_ADD";

    /**
     * Production audit-result constant emitted on the success path.
     * Preserved as a metric tag on the
     * {@code carddemo.audit.security} Micrometer counter so
     * failure-rate alarms can filter {@code result=SUCCESS} from
     * {@code result=FAILURE} (AAP &sect;0.6.6 observability).
     */
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";

    // ==================================================================
    // Mock collaborators and the system under test
    //
    // The three @Mock fields below are wired into the @InjectMocks
    // UserAddService via constructor injection. The service declares
    // exactly one public constructor (UserAddService(repository, encoder,
    // auditLogService)), so Mockito's @InjectMocks unambiguously
    // resolves the order.
    // ==================================================================

    /**
     * Mock Spring Data JPA repository replacing CICS access to the
     * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} dataset (per the AAP
     * &sect;0.6.2 VSAM &rarr; RDS migration). The service under test
     * receives this mock via Mockito's {@link InjectMocks @InjectMocks}
     * constructor wiring; tests stub
     * {@link UserSecurityRepository#existsById(Object)} with
     * {@code true} for the duplicate path and {@code false} for the
     * happy path, and verify {@link UserSecurityRepository#save(Object)}
     * invocation cardinality (once on the happy path, never on the
     * duplicate or validation paths). The captured {@link UserSecurity}
     * argument is asserted to carry the normalized {@code secUsrId},
     * the trimmed first/last names, the BCrypt hash on
     * {@code secUsrPwd} (NEVER the plaintext), and the normalized
     * {@code secUsrType}.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mock Spring Security password encoder. Stubbed via
     * {@code when(passwordEncoder.encode(...)).thenReturn(BCRYPT_HASH)}
     * to verify (a) the service invokes {@code encode} exactly once
     * with the operator-supplied password value (passed verbatim
     * &mdash; not uppercased &mdash; per AAP &sect;0.7.1 and the
     * BCrypt-security-upgrade clause in AAP &sect;0.1.1), and (b)
     * the saved {@link UserSecurity#getSecUsrPwd()} equals the
     * returned hash (never the plaintext). Mocking the
     * {@link PasswordEncoder} interface (not the
     * {@code BCryptPasswordEncoder} bean) keeps the unit test fast
     * and isolated from BCrypt cost-factor-12 computation overhead.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Mock OpenSearch + CloudWatch audit adapter replacing the COBOL
     * source's distributed {@code DISPLAY} statements (per AAP
     * &sect;0.6.6 audit consolidation). Tests use
     * {@link ArgumentCaptor} on
     * {@link AuditLogService#logSecurityEvent(String, String, String,
     * String, Map, String)} to assert (a) the event type contains
     * {@code "ADD"} on the success path, (b) the captured
     * {@code Map<String, Object>} payload does NOT contain any
     * password-like key (the PCI-DSS rule), and (c) no audit event is
     * emitted on the duplicate or validation paths.
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * System under test &mdash; the Java service that replaces the
     * COBOL/CICS program {@code app/cbl/COUSR01C.cbl}.
     * {@link InjectMocks @InjectMocks} instantiates this service via
     * its constructor, automatically supplying the three
     * {@link Mock @Mock}-annotated collaborators above (the only
     * constructor declared on {@link UserAddService} takes exactly
     * those three arguments). No reflective field injection is
     * performed because the service uses constructor injection only
     * per the AAP &sect;0.7.1 layered-architecture rule.
     */
    @InjectMocks
    private UserAddService service;

    /**
     * Canonical request DTO fixture rebuilt before every test. Tests
     * use this as the "happy path" baseline and construct
     * deliberately invalid variants in the failure-path tests (e.g.,
     * blank firstName, invalid userType). Re-built per test (not
     * stored as a static constant) so any mutation cannot bleed
     * across tests.
     */
    private UserAddDto validRequest;

    /**
     * Test-fixture setup &mdash; constructs a fresh {@link UserAddDto}
     * fixture before every test. No Mockito {@code when(...)} stubs
     * are configured here; per-test stubs are declared inside the
     * individual test methods to keep the strict Mockito stubbing
     * contract (unused stubs would fail under
     * {@code MockitoExtension}).
     *
     * <p>The fixture deliberately mirrors a valid operator-supplied
     * input set:
     * <ul>
     *   <li>{@code userId = "USER0001"} &mdash; 8-char alphanumeric,
     *       already-uppercase; matches the V015 seed format.</li>
     *   <li>{@code firstName = "JANE"}, {@code lastName = "DOE"}
     *       &mdash; fit the
     *       {@code SEC-USR-FNAME}/{@code SEC-USR-LNAME PIC X(20)}
     *       layouts in {@code CSUSR01Y.cpy}.</li>
     *   <li>{@code password = "Pa55w0rd"} &mdash; exactly 8
     *       characters (the legacy {@code PIC X(08)} width), used
     *       verbatim by the encode stub.</li>
     *   <li>{@code userType = "U"} &mdash; regular user (role
     *       discriminator from {@code SEC-USR-TYPE PIC X(01)}).</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // COBOL: COUSR01C:PROCESS-ENTER-KEY happy-path inputs.
        // Mirrors the operator-supplied values after the RECEIVE MAP
        // COUSR1A populates COUSR1AI (the symbolic-map input copy of
        // app/cpy-bms/COUSR01.CPY).
        validRequest = new UserAddDto(
                NORMALIZED_USER_ID,
                FIRST_NAME,
                LAST_NAME,
                PLAINTEXT_PASSWORD,
                USER_TYPE_USER);
    }

    // ==================================================================
    // @Nested test groups
    //
    // Each group exercises one behavioral cluster of the
    // COBOL-to-Java translation. Grouping mirrors the AAP §0.7.1
    // refactor-discipline practice of organizing tests by behavior
    // (not by method shape), so a reviewer reading "BCryptHashing"
    // can see every test that proves the PCI-DSS upgrade is honored
    // without scrolling through the entire file.
    // ==================================================================

    /**
     * Tests for the BCrypt password-hashing behavior introduced by
     * AAP &sect;0.7.1 ("Plaintext password storage in the source
     * USRSEC file [...] must be upgraded to BCrypt hashing").
     *
     * <p>This group verifies the central security guarantee of the
     * user-administration suite: the operator-supplied plaintext
     * password is hashed by the injected
     * {@link PasswordEncoder} BEFORE the entity is persisted, and
     * the BCrypt hash &mdash; not the plaintext &mdash; is the value
     * stored on {@link UserSecurity#getSecUsrPwd()}.</p>
     *
     * <p>The verbatim-encoding clause is critical: the production
     * code calls {@code passwordEncoder.encode(request.password())}
     * &mdash; NOT
     * {@code passwordEncoder.encode(request.password().toUpperCase(...))}
     * &mdash; because uppercasing a password before hashing would
     * silently collapse the password space (e.g., {@code "Pa55w0rd"}
     * and {@code "pa55w0rd"} would hash to the same value), which
     * is unacceptable for any modern authentication scheme.</p>
     */
    @Nested
    @DisplayName("BCrypt password hashing (AAP §0.7.1 PCI-DSS upgrade)")
    class BCryptHashing {

        /**
         * Verifies that the {@link PasswordEncoder#encode(CharSequence)}
         * result &mdash; the BCrypt hash &mdash; is the value
         * persisted on the {@link UserSecurity} entity, NOT the
         * plaintext value supplied in the request DTO.
         *
         * <p>COBOL: COUSR01C:WRITE-USER-SEC-FILE &mdash; the legacy
         * program performed {@code MOVE PASSWDI OF COUSR1AI TO
         * SEC-USR-PWD} (plaintext). The Java translation inserts an
         * encoding step before the equivalent
         * {@code user.setSecUsrPwd(...)} call so the persisted column
         * value is a 60-character BCrypt hash.</p>
         */
        @Test
        @DisplayName("hashes password via PasswordEncoder before save")
        void addUser_hashesPasswordBeforeSave() {
            // Arrange — happy-path stubs
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert — capture the persisted entity and verify the
            // BCrypt hash (not the plaintext) is on the secUsrPwd field
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            UserSecurity persisted = userCaptor.getValue();
            assertThat(persisted.getSecUsrPwd())
                    .as("Persisted password must be the BCrypt hash, never the plaintext")
                    .isEqualTo(BCRYPT_HASH)
                    .isNotEqualTo(PLAINTEXT_PASSWORD);
        }

        /**
         * Verifies that the password supplied in the request DTO is
         * passed VERBATIM to {@link PasswordEncoder#encode(CharSequence)}
         * &mdash; not uppercased, not trimmed, not transformed.
         *
         * <p>This is a critical deviation from the COBOL signon
         * program ({@code COSGN00C}), which uppercased the password
         * before comparison. The BCrypt security upgrade introduces
         * case-sensitive password comparison; uppercasing the
         * plaintext before hashing would collapse the password space
         * (mixed-case passwords would hash to the same value as
         * their uppercase variants), which violates the security
         * upgrade's purpose.</p>
         */
        @Test
        @DisplayName("passes password verbatim to encoder (NOT uppercased)")
        void addUser_passesPasswordVerbatimToEncoder() {
            // Arrange — mixed-case password fixture
            String mixedCasePassword = "Pa55w0rd";
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, mixedCasePassword, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(mixedCasePassword)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert — encoder invoked with the EXACT password value,
            // never with the uppercase variant
            verify(passwordEncoder).encode(eq(mixedCasePassword));
            verify(passwordEncoder, never()).encode(eq(mixedCasePassword.toUpperCase(Locale.US)));
        }

        /**
         * Verifies that {@link PasswordEncoder#encode(CharSequence)}
         * is invoked exactly once per add operation. BCrypt at
         * strength 12 is intentionally expensive (~250 ms per
         * invocation on modern hardware); duplicate calls would
         * double the latency of every user-creation request.
         */
        @Test
        @DisplayName("invokes encoder exactly once per add operation")
        void addUser_invokesEncoderExactlyOnce() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            verify(passwordEncoder, times(1)).encode(PLAINTEXT_PASSWORD);
        }

        /**
         * Defense-in-depth assertion that the plaintext password
         * value supplied in the request DTO is NEVER stored on the
         * persisted {@link UserSecurity} entity, even if it
         * accidentally bypasses the encoder. This guards against a
         * regression where a refactor might inadvertently use
         * {@code request.password()} instead of
         * {@code hashedPassword} in the {@code setSecUsrPwd(...)}
         * call.
         */
        @Test
        @DisplayName("never persists plaintext password on entity")
        void addUser_neverPersistsPlaintextPassword() {
            // Arrange — encoder returns a hash that is recognizably
            // distinct from the plaintext
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert — the persisted secUsrPwd never carries the
            // operator-supplied plaintext value
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getSecUsrPwd())
                    .as("secUsrPwd must NEVER equal the plaintext password")
                    .isNotEqualTo(PLAINTEXT_PASSWORD);
        }
    }



    /**
     * Tests for {@code userId} and {@code userType} normalization
     * applied by {@link UserAddService} before
     * {@link UserSecurityRepository#existsById(Object)} and
     * {@link UserSecurityRepository#save(Object)}.
     *
     * <p>COBOL parity: the legacy COUSR01C program assumed the
     * operator typed uppercase values into the 3270 terminal (3270
     * terminals often had CAPS LOCK enabled by default). The Java
     * target enforces uppercase normalization explicitly via
     * {@code .trim().toUpperCase(Locale.US)} so that mixed-case
     * REST inputs (e.g., {@code "user0001"} from a JSON client) map
     * to the same primary-key index as the legacy uppercase
     * values.</p>
     *
     * <p>{@link Locale#US} is critical: under Turkish locales, the
     * uppercase of {@code 'i'} is {@code 'İ'} (with diacritic), not
     * {@code 'I'}; using the default locale could silently corrupt
     * the primary-key index for any deployment whose JVM default
     * locale is non-ASCII.</p>
     */
    @Nested
    @DisplayName("userId and userType normalization (Locale.US uppercase + trim)")
    class UserIdNormalization {

        /**
         * Lower-case userId input MUST be uppercased before save.
         * Mirrors the COBOL convention {@code MOVE FUNCTION
         * UPPER-CASE(USERIDI) TO SEC-USR-ID}.
         */
        @Test
        @DisplayName("uppercases lower-case userId before save")
        void addUser_uppercasesLowerCaseUserId() {
            // Arrange — request carries lower-case userId
            UserAddDto request = new UserAddDto(
                    LOWERCASE_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert — persisted entity carries the uppercase userId
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getSecUsrId()).isEqualTo(NORMALIZED_USER_ID);
        }

        /**
         * The duplicate pre-check via {@code existsById} MUST use
         * the NORMALIZED (uppercase) userId, not the operator-typed
         * value. Otherwise a lower-case input like {@code "user0001"}
         * could bypass the duplicate-check against an existing
         * {@code "USER0001"} row and crash later on the
         * primary-key constraint instead of returning a clean 409.
         */
        @Test
        @DisplayName("uses normalized userId for existsById pre-check")
        void addUser_existsByIdUsesNormalizedUserId() {
            // Arrange — request carries lower-case userId
            UserAddDto request = new UserAddDto(
                    LOWERCASE_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert — existsById invoked with the UPPERCASE userId
            verify(userSecurityRepository).existsById(NORMALIZED_USER_ID);
            verify(userSecurityRepository, never()).existsById(LOWERCASE_USER_ID);
        }

        /**
         * Whitespace around the operator-supplied userId MUST be
         * trimmed before normalization. A 3270 terminal would have
         * padded fixed-width fields with trailing spaces; a REST
         * client may post {@code " USER0001 "} from a careless UI
         * binding. Both must reduce to {@code "USER0001"}.
         */
        @Test
        @DisplayName("trims surrounding whitespace from userId before save")
        void addUser_trimsWhitespaceFromUserId() {
            // Arrange — request carries padded userId
            UserAddDto request = new UserAddDto(
                    "  USER0001  ", FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert — persisted entity carries the trimmed userId
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getSecUsrId()).isEqualTo(NORMALIZED_USER_ID);
        }

        /**
         * Lower-case userType {@code "u"} MUST be uppercased before
         * the domain check and save, otherwise it would fall outside
         * the {@code VALID_USER_TYPES = {"A","U"}} allowlist and
         * trigger a spurious {@link ValidationException}.
         */
        @Test
        @DisplayName("uppercases lower-case userType before save")
        void addUser_uppercasesLowerCaseUserType() {
            // Arrange
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, LOWERCASE_USER_TYPE);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert — persisted entity carries the uppercase userType
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getSecUsrType()).isEqualTo(USER_TYPE_USER);
        }

        /**
         * First and last name MUST be trimmed (but NOT uppercased)
         * before save. The {@code SEC-USR-FNAME PIC X(20)} layout
         * accepts mixed-case names; the COBOL program preserved the
         * operator's casing as typed. Java trim removes incidental
         * leading/trailing whitespace.
         */
        @Test
        @DisplayName("trims firstName and lastName before save (case preserved)")
        void addUser_trimsFirstAndLastNamesBeforeSave() {
            // Arrange — names with surrounding whitespace
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "  Jane  ", "  Doe  ", PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert — names are trimmed but case is preserved
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            UserSecurity persisted = userCaptor.getValue();
            assertThat(persisted.getSecUsrFname()).isEqualTo("Jane");
            assertThat(persisted.getSecUsrLname()).isEqualTo("Doe");
        }
    }


    /**
     * Tests for the {@code userType} domain check enforced by the
     * service's {@code VALID_USER_TYPES = {"A","U"}} allowlist.
     *
     * <p>The COBOL source ({@code COUSR01C}) did NOT validate the
     * userType value range &mdash; it relied on operator
     * discipline (and the BMS map literal {@code "(A=Admin,
     * U=User)"}). The Java target enforces the allowlist
     * defensively at the service layer (in addition to the
     * {@code @Pattern(regexp = "^[AU]$")} Bean Validation
     * constraint on {@link UserAddDto#userType()}) so that direct
     * service invocations (programmatic callers, batch importers,
     * tests) cannot bypass the V010 {@code chk_user_security_type}
     * CHECK constraint and crash later on a foreign-key/Constraint
     * violation.</p>
     */
    @Nested
    @DisplayName("userType domain validation ('A' or 'U' only)")
    class UserTypeValidation {

        /**
         * Invalid userType (not in {A, U}) MUST throw
         * {@link ValidationException}. Any single character outside
         * the allowlist is rejected; we use {@code "X"} as a
         * representative invalid value.
         */
        @Test
        @DisplayName("rejects userType not in {A, U} with ValidationException")
        void addUser_rejectsInvalidUserType() {
            // Arrange — request with userType "X"
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, INVALID_USER_TYPE);

            // Act / Assert — domain check throws ValidationException
            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User Type");
        }

        /**
         * The userType domain check fires AFTER field-non-blank
         * validation but BEFORE the duplicate pre-check and BEFORE
         * any persistence work. An invalid userType MUST NOT cause
         * an existsById, encode, save, or audit invocation.
         */
        @Test
        @DisplayName("invalid userType performs no repository, encoder, or audit work")
        void addUser_invalidUserType_noSideEffects() {
            // Arrange
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, INVALID_USER_TYPE);

            // Act
            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class);

            // Assert — none of the collaborators were touched
            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(auditLogService);
        }

        /**
         * The admin userType {@code "A"} MUST succeed on the happy
         * path. This proves the allowlist accepts both members of
         * the {A, U} domain (not just {@code "U"}).
         */
        @Test
        @DisplayName("accepts userType 'A' (admin) on happy path")
        void addUser_acceptsAdminUserType() {
            // Arrange — request with userType "A"
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_ADMIN);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(request);

            // Assert
            assertThat(response.userType()).isEqualTo(USER_TYPE_ADMIN);
            ArgumentCaptor<UserSecurity> userCaptor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(userSecurityRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getSecUsrType()).isEqualTo(USER_TYPE_ADMIN);
        }

        /**
         * The regular userType {@code "U"} MUST succeed on the
         * happy path. Mirrors the admin-userType test above for
         * completeness.
         */
        @Test
        @DisplayName("accepts userType 'U' (regular user) on happy path")
        void addUser_acceptsRegularUserType() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(validRequest);

            // Assert
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
        }

        /**
         * Lower-case userType characters {@code 'a'} or {@code 'u'}
         * MUST be normalized to uppercase BEFORE the allowlist
         * check. Otherwise a perfectly valid {@code "a"} input
         * would be rejected as if it were outside the allowlist.
         */
        @Test
        @DisplayName("normalizes lower-case userType before allowlist check")
        void addUser_normalizesLowerCaseUserTypeBeforeAllowlistCheck() {
            // Arrange — request with lower-case userType "a"
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, "a");
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act — must NOT throw ValidationException despite
            // lower-case input
            UserAddDto response = service.addUser(request);

            // Assert — normalized to uppercase 'A' on entity and response
            assertThat(response.userType()).isEqualTo(USER_TYPE_ADMIN);
        }
    }


    /**
     * Tests for the duplicate-user detection path.
     *
     * <p>COBOL: COUSR01C:WRITE-USER-SEC-FILE
     * (lines 240&ndash;266 of {@code app/cbl/COUSR01C.cbl}). The
     * legacy program performed {@code EXEC CICS WRITE DATASET}
     * and inspected {@code DFHRESP(NORMAL)},
     * {@code DFHRESP(DUPKEY)}, and {@code DFHRESP(DUPREC)} on the
     * outcome. On a duplicate key the program emitted
     * {@code "User ID already exist..."} to {@code ERRMSGO} and
     * re-sent the map.</p>
     *
     * <p>The Java target performs an {@code existsById} pre-check
     * BEFORE encoding the password and BEFORE save so that:
     * <ol>
     *   <li>The 409 response carries a descriptive message
     *       without relying on PostgreSQL's unique-constraint
     *       violation to surface a generic
     *       {@code DataIntegrityViolationException}.</li>
     *   <li>The expensive BCrypt computation is skipped on the
     *       duplicate path (saves ~250 ms of CPU per duplicate
     *       attempt &mdash; a non-trivial DoS-mitigation benefit
     *       on the user-administration endpoint).</li>
     *   <li>The duplicate path emits NO audit event &mdash; only
     *       successful adds are audited as {@code USER_ADD}.</li>
     * </ol>
     * </p>
     */
    @Nested
    @DisplayName("Duplicate userId detection (VSAM DUPKEY → HTTP 409)")
    class DuplicateUserId {

        /**
         * existsById returning {@code true} MUST cause the service
         * to throw {@link DuplicateRecordException}. The exception
         * is the canonical bridge from COBOL FILE STATUS {@code "22"}
         * (DUPKEY) to the HTTP 409 (Conflict) response shape
         * configured in {@code GlobalExceptionHandler}.
         */
        @Test
        @DisplayName("throws DuplicateRecordException when userId already exists")
        void addUser_duplicateUserId_throwsDuplicateRecord() {
            // Arrange — existsById returns true (duplicate detected)
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(true);

            // Act / Assert
            assertThatThrownBy(() -> service.addUser(validRequest))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessageContaining(NORMALIZED_USER_ID);
        }

        /**
         * The duplicate path MUST NOT invoke
         * {@link UserSecurityRepository#save(Object)}. Replicates
         * the COBOL semantics: if the WRITE returns DUPKEY, the
         * record is NOT persisted.
         */
        @Test
        @DisplayName("duplicate path never invokes save")
        void addUser_duplicateUserId_neverInvokesSave() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(true);

            // Act
            assertThatThrownBy(() -> service.addUser(validRequest))
                    .isInstanceOf(DuplicateRecordException.class);

            // Assert
            verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        }

        /**
         * The duplicate path MUST NOT invoke the
         * {@link PasswordEncoder}. BCrypt at strength 12 is
         * intentionally expensive; skipping the encode on the
         * duplicate path saves ~250 ms of CPU per attempt and
         * provides a small DoS-mitigation benefit.
         */
        @Test
        @DisplayName("duplicate path never invokes password encoder")
        void addUser_duplicateUserId_neverInvokesEncoder() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(true);

            // Act
            assertThatThrownBy(() -> service.addUser(validRequest))
                    .isInstanceOf(DuplicateRecordException.class);

            // Assert
            verifyNoInteractions(passwordEncoder);
        }

        /**
         * The duplicate path MUST NOT emit a {@code USER_ADD}
         * audit event. Only successful user creations are recorded
         * with {@code result=SUCCESS}; failed attempts (duplicates,
         * validation errors) are logged at WARN level but NOT
         * indexed into the security event stream.
         */
        @Test
        @DisplayName("duplicate path never emits audit event")
        void addUser_duplicateUserId_neverEmitsAudit() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(true);

            // Act
            assertThatThrownBy(() -> service.addUser(validRequest))
                    .isInstanceOf(DuplicateRecordException.class);

            // Assert
            verifyNoInteractions(auditLogService);
        }

        /**
         * The duplicate pre-check MUST use the NORMALIZED userId,
         * not the operator-typed value. This guards against a
         * lower-case input bypassing the duplicate check against
         * an existing uppercase row.
         */
        @Test
        @DisplayName("duplicate pre-check uses normalized (uppercase) userId")
        void addUser_duplicateUserId_preCheckUsesNormalizedId() {
            // Arrange — request with lower-case userId
            UserAddDto request = new UserAddDto(
                    LOWERCASE_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(true);

            // Act
            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(DuplicateRecordException.class);

            // Assert — existsById was called with the UPPERCASE userId,
            // never with the lower-case operator input
            verify(userSecurityRepository).existsById(NORMALIZED_USER_ID);
            verify(userSecurityRepository, never()).existsById(LOWERCASE_USER_ID);
        }

        /**
         * The thrown {@link DuplicateRecordException} carries the
         * reason code {@code "DUPLICATE_USER"} (the 2-arg
         * {@code (reasonCode, message)} constructor used by
         * {@code UserAddService}). Downstream consumers
         * (GlobalExceptionHandler, error envelope formatters) rely
         * on this reason code to discriminate domain-specific
         * duplicate causes.
         */
        @Test
        @DisplayName("DuplicateRecordException carries 'DUPLICATE_USER' reason code")
        void addUser_duplicateUserId_carriesReasonCode() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(true);

            // Act / Assert
            assertThatThrownBy(() -> service.addUser(validRequest))
                    .isInstanceOf(DuplicateRecordException.class)
                    .extracting(t -> ((DuplicateRecordException) t).getReasonCode())
                    .isEqualTo("DUPLICATE_USER");
        }
    }


    /**
     * Tests for the 5-field non-blank validation cascade.
     *
     * <p>COBOL: COUSR01C:PROCESS-ENTER-KEY (lines 115&ndash;151 of
     * {@code app/cbl/COUSR01C.cbl}). The legacy program uses
     * {@code EVALUATE TRUE} with {@code WHEN <field> = SPACES OR
     * LOW-VALUES} clauses; the first failing field emits its
     * specific error message and re-sends the map. This is
     * first-error-wins semantic: a single failure exits the
     * cascade.</p>
     *
     * <p>The Java target preserves this semantic via the private
     * {@code requireNonBlank} helper invoked in COBOL field order:
     * FNAME &rarr; LNAME &rarr; USERID &rarr; PASSWD &rarr;
     * USRTYPE. Each invocation throws {@link ValidationException}
     * on failure with a message containing the field name (e.g.,
     * {@code "First Name must not be empty"}).</p>
     */
    @Nested
    @DisplayName("5-field non-blank validation cascade (first-error-wins)")
    class FieldValidation {

        /**
         * Blank {@code firstName} MUST throw
         * {@link ValidationException} with a message containing
         * {@code "First Name"}. The field appears FIRST in the
         * COBOL cascade so a blank firstName surfaces first even
         * if other fields are also blank.
         */
        @Test
        @DisplayName("blank firstName throws ValidationException")
        void addUser_blankFirstName_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "", LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("First Name");
        }

        /**
         * Whitespace-only firstName is treated identically to blank.
         * The {@code requireNonBlank} helper applies
         * {@link String#trim()} before the empty check, so a value
         * of {@code "   "} is rejected.
         */
        @Test
        @DisplayName("whitespace-only firstName throws ValidationException")
        void addUser_whitespaceOnlyFirstName_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "   ", LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("First Name");
        }

        /**
         * Null firstName (record component is nullable in the Java
         * runtime even if {@code @NotBlank}) MUST also throw
         * {@link ValidationException}. The service layer accepts
         * direct programmatic invocations that bypass Bean
         * Validation.
         */
        @Test
        @DisplayName("null firstName throws ValidationException")
        void addUser_nullFirstName_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, null, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("First Name");
        }

        /**
         * Blank {@code lastName} MUST throw
         * {@link ValidationException} with a message containing
         * {@code "Last Name"}. With firstName supplied, the
         * cascade advances to the lastName check.
         */
        @Test
        @DisplayName("blank lastName throws ValidationException")
        void addUser_blankLastName_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, "", PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Last Name");
        }

        /**
         * Blank {@code userId} MUST throw
         * {@link ValidationException} with a message containing
         * {@code "User ID"}.
         */
        @Test
        @DisplayName("blank userId throws ValidationException")
        void addUser_blankUserId_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    "", FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User ID");
        }

        /**
         * Blank {@code password} MUST throw
         * {@link ValidationException} with a message containing
         * {@code "Password"}.
         */
        @Test
        @DisplayName("blank password throws ValidationException")
        void addUser_blankPassword_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, "", USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Password");
        }

        /**
         * Blank {@code userType} MUST throw
         * {@link ValidationException} with a message containing
         * {@code "User Type"}.
         */
        @Test
        @DisplayName("blank userType throws ValidationException")
        void addUser_blankUserType_throwsValidation() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, "");

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("User Type");
        }

        /**
         * COBOL first-error-wins semantic: if multiple fields are
         * blank, the FIRST field in the cascade order
         * (FNAME &rarr; LNAME &rarr; USERID &rarr; PASSWD &rarr;
         * USRTYPE) MUST be reported. Here both firstName and
         * lastName are blank but the exception MUST identify
         * firstName.
         */
        @Test
        @DisplayName("first-error-wins: blank firstName beats blank lastName")
        void addUser_firstErrorWins_blankFirstNameOverBlankLastName() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "", "", PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("First Name");
        }

        /**
         * Validation failures occur BEFORE any collaborator
         * interaction. The duplicate pre-check, encode, save, and
         * audit MUST NOT be invoked when a validation error
         * surfaces. This is the most important invariant of the
         * validation cascade because it ensures no side effects
         * leak through on a failed request.
         */
        @Test
        @DisplayName("validation failure performs no repository, encoder, or audit work")
        void addUser_validationFailure_noSideEffects() {
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "", LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);

            assertThatThrownBy(() -> service.addUser(request))
                    .isInstanceOf(ValidationException.class);

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(auditLogService);
        }

        /**
         * Defensive null guard: passing a {@code null} request to
         * {@link UserAddService#addUser(UserAddDto)} MUST throw
         * {@link NullPointerException} (via the explicit
         * {@code Objects.requireNonNull} at the method head).
         * Controllers normally enforce non-null bodies via
         * {@code @Valid @RequestBody}, but the service boundary
         * remains safe under direct invocation.
         */
        @Test
        @DisplayName("null request throws NullPointerException (defensive guard)")
        void addUser_nullRequest_throwsNpe() {
            assertThatThrownBy(() -> service.addUser(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request");
        }
    }


    /**
     * Tests for PCI-DSS PII / credential handling. The central
     * rule is: <b>the password value (plaintext OR BCrypt hash)
     * NEVER appears outside the encoder &mdash; not in the audit
     * payload, not in the response DTO, not in any visible side
     * channel.</b>
     *
     * <p>This group verifies the AAP &sect;0.6.6 PCI-DSS audit
     * rule and the AAP &sect;0.7.1 "no plaintext card / account
     * data in logs" requirement (extended to password material
     * by analogy).</p>
     */
    @Nested
    @DisplayName("PII / credential handling (PCI-DSS)")
    class PiiHandling {

        /**
         * The captured audit payload {@code Map} MUST NOT contain
         * any key whose name matches a password-like token
         * (case-insensitive). The defense-in-depth check is
         * deliberately broad: it rejects {@code "password"},
         * {@code "secUsrPwd"}, {@code "pwd"}, and any other
         * accidental key name a future refactor might introduce.
         */
        @Test
        @DisplayName("audit payload does NOT contain any password-like key")
        void addUser_auditPayloadHasNoPasswordKey() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert — capture the payload Map and verify NO key
            // contains "password" or "pwd" (case-insensitive)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(),     // eventType
                    anyString(),     // userId
                    anyString(),     // result
                    any(),           // sourceIp (null)
                    payloadCaptor.capture(),
                    any());          // correlationId (null)

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).isNotNull();
            assertThat(payload.keySet())
                    .as("Audit payload keys must NOT contain any password-like token")
                    .noneMatch(k -> {
                        String lower = k.toLowerCase(Locale.US);
                        return lower.contains("password") || lower.contains("pwd");
                    });
        }

        /**
         * The captured audit payload {@code Map} MUST NOT contain
         * the plaintext password value anywhere &mdash; not as a
         * value, not as a key. This guards against a misnamed key
         * (e.g., {@code "credential"}) that would slip past the
         * key-name check above.
         */
        @Test
        @DisplayName("audit payload does NOT contain plaintext password as any value")
        void addUser_auditPayloadHasNoPlaintextPasswordValue() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert — payload values do not include the plaintext
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(), anyString(), anyString(),
                    any(), payloadCaptor.capture(), any());

            assertThat(payloadCaptor.getValue().values())
                    .as("Audit payload values must NOT contain plaintext password")
                    .noneMatch(v -> PLAINTEXT_PASSWORD.equals(v));
        }

        /**
         * The captured audit payload {@code Map} MUST NOT contain
         * the BCrypt hash anywhere. The hash is a credential
         * artifact and is treated as equally sensitive as the
         * plaintext for the purposes of audit emission.
         */
        @Test
        @DisplayName("audit payload does NOT contain BCrypt hash as any value")
        void addUser_auditPayloadHasNoBcryptHashValue() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(), anyString(), anyString(),
                    any(), payloadCaptor.capture(), any());

            assertThat(payloadCaptor.getValue().values())
                    .as("Audit payload values must NOT contain BCrypt hash")
                    .noneMatch(v -> BCRYPT_HASH.equals(v));
        }

        /**
         * The response DTO returned by
         * {@link UserAddService#addUser(UserAddDto)} MUST carry
         * {@code password = null}. The BCrypt hash never crosses
         * the wire, and the operator-supplied plaintext is
         * discarded after the encode call.
         */
        @Test
        @DisplayName("response DTO has password == null")
        void addUser_response_passwordIsNull() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(validRequest);

            // Assert
            assertThat(response.password())
                    .as("Response DTO password component must be null (PCI-DSS)")
                    .isNull();
        }

        /**
         * Belt-and-braces assertion: the response DTO password
         * component is neither the plaintext NOR the BCrypt hash.
         * If a future refactor accidentally populated the field
         * with either value, this test would fail.
         */
        @Test
        @DisplayName("response DTO password is neither plaintext nor hash")
        void addUser_response_passwordIsNotPlaintextOrHash() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(validRequest);

            // Assert
            assertThat(response.password())
                    .as("Response DTO password must NOT be the plaintext")
                    .isNotEqualTo(PLAINTEXT_PASSWORD);
            assertThat(response.password())
                    .as("Response DTO password must NOT be the BCrypt hash")
                    .isNotEqualTo(BCRYPT_HASH);
        }

        /**
         * The {@link UserAddDto#toString()} method MUST always
         * render the password as {@code "********"} regardless of
         * the actual field value. The redacting {@code toString()}
         * is the last line of defense against accidental
         * credential leakage in logs, exception messages, and
         * collection-toString() output.
         *
         * <p>This test exercises the DTO independently to prove the
         * redaction works; the service-side test above proves the
         * field is null in production. Together they guarantee
         * that even a future refactor that populated the password
         * field would still not leak it via toString().</p>
         */
        @Test
        @DisplayName("UserAddDto.toString() redacts password as '********'")
        void userAddDto_toString_redactsPassword() {
            // Arrange — a DTO with a plaintext password
            UserAddDto dto = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);

            // Act
            String rendered = dto.toString();

            // Assert
            assertThat(rendered).contains("password=********");
            assertThat(rendered)
                    .as("toString() must NOT leak the plaintext password")
                    .doesNotContain(PLAINTEXT_PASSWORD);
        }

        /**
         * The {@link UserAddDto} record DOES define a {@code password}
         * record component (the field is needed on the request
         * direction). However, the toString() override redacts it,
         * and the service nulls it out on the response. This
         * reflection-based assertion documents the contract: the
         * record component exists, but the production code
         * guarantees its value is null on the outbound path.
         */
        @Test
        @DisplayName("UserAddDto declares 'password' component (request-only by convention)")
        void userAddDto_recordHasPasswordComponent() {
            RecordComponent[] components = UserAddDto.class.getRecordComponents();
            assertThat(components)
                    .as("UserAddDto must declare a 'password' record component")
                    .extracting(RecordComponent::getName)
                    .contains("password");
        }
    }


    /**
     * Tests for the audit-event emission contract.
     *
     * <p>Replaces COBOL distributed {@code DISPLAY} audit
     * statements with structured OpenSearch security-event
     * indexing (AAP &sect;0.6.6). Every successful user creation
     * MUST emit exactly one {@code logSecurityEvent} invocation
     * with the canonical USER_ADD discriminator, the normalized
     * userId, the SUCCESS result code, and a payload containing
     * the operational identifiers (userId + userType).</p>
     */
    @Nested
    @DisplayName("Audit event emission (AAP §0.6.6)")
    class AuditLogging {

        /**
         * Successful user creation MUST emit exactly one audit
         * event. Duplicate or zero invocations are both
         * regressions: missing the audit fails PCI-DSS; duplicate
         * inflates the {@code carddemo.audit.security}
         * Micrometer counter.
         */
        @Test
        @DisplayName("emits exactly one audit event on success")
        void addUser_emitsExactlyOneAuditEvent() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            verify(auditLogService, times(1)).logSecurityEvent(
                    anyString(), anyString(), anyString(),
                    any(), anyMap(), any());
        }

        /**
         * The captured {@code eventType} MUST be {@code "USER_ADD"}.
         * The agent-prompt requires the value to contain
         * {@code "ADD"} OR {@code "CREATE"} (case-insensitive);
         * the production constant is {@code "USER_ADD"} which
         * satisfies the {@code "ADD"} branch.
         */
        @Test
        @DisplayName("audit eventType is USER_ADD (contains 'ADD')")
        void addUser_auditEventTypeContainsAdd() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logSecurityEvent(
                    eventTypeCaptor.capture(),
                    anyString(), anyString(),
                    any(), anyMap(), any());

            String eventType = eventTypeCaptor.getValue();
            assertThat(eventType).isEqualTo(AUDIT_EVENT_TYPE);
            assertThat(eventType.toUpperCase(Locale.US))
                    .as("Event type must contain 'ADD' or 'CREATE' per AAP §0.6.6")
                    .satisfiesAnyOf(
                            s -> assertThat(s).contains("ADD"),
                            s -> assertThat(s).contains("CREATE"));
        }

        /**
         * The captured {@code userId} argument MUST be the
         * NORMALIZED uppercase userId so the audit row keys
         * consistently with the persistence layer.
         */
        @Test
        @DisplayName("audit event carries normalized userId")
        void addUser_auditEventCarriesNormalizedUserId() {
            // Arrange — lower-case input to verify normalization
            // propagates into the audit event
            UserAddDto request = new UserAddDto(
                    LOWERCASE_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(request);

            // Assert
            ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(),
                    userIdCaptor.capture(),
                    anyString(), any(), anyMap(), any());

            assertThat(userIdCaptor.getValue()).isEqualTo(NORMALIZED_USER_ID);
        }

        /**
         * The captured {@code result} argument MUST be
         * {@code "SUCCESS"}. The Micrometer counter dimension
         * {@code result} is used by ops dashboards to compute
         * failure rates; any deviation from the canonical
         * {@code "SUCCESS"} label breaks the alarm.
         */
        @Test
        @DisplayName("audit event carries result='SUCCESS' on happy path")
        void addUser_auditEventCarriesSuccessResult() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(), anyString(),
                    resultCaptor.capture(),
                    any(), anyMap(), any());

            assertThat(resultCaptor.getValue()).isEqualTo(AUDIT_RESULT_SUCCESS);
        }

        /**
         * The captured payload MUST contain the {@code userId}
         * and {@code userType} entries (the operational
         * identifiers safe to record). Other entries (firstName,
         * lastName, etc.) are intentionally omitted because they
         * may be PII subject to different retention policies.
         */
        @Test
        @DisplayName("audit payload contains userId and userType entries")
        void addUser_auditPayloadContainsUserIdAndType() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSecurityEvent(
                    anyString(), anyString(), anyString(),
                    any(), payloadCaptor.capture(), any());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsKey("userId");
            assertThat(payload.get("userId")).isEqualTo(NORMALIZED_USER_ID);
            assertThat(payload).containsKey("userType");
            assertThat(payload.get("userType")).isEqualTo(USER_TYPE_USER);
        }

        /**
         * The captured {@code sourceIp} and {@code correlationId}
         * arguments MUST both be {@code null}. The service does
         * NOT have access to the HTTP request context (that is a
         * controller concern). Passing {@code null} signals to the
         * {@code AuditLogService} that those fields should be
         * derived from MDC or omitted entirely.
         */
        @Test
        @DisplayName("audit event passes null sourceIp and correlationId")
        void addUser_auditEventNullSourceIpAndCorrelationId() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert — verify the call with eq(null) on positions
            // 4 (sourceIp) and 6 (correlationId)
            verify(auditLogService).logSecurityEvent(
                    eq(AUDIT_EVENT_TYPE),
                    eq(NORMALIZED_USER_ID),
                    eq(AUDIT_RESULT_SUCCESS),
                    eq((String) null),
                    anyMap(),
                    eq((String) null));
        }

        /**
         * Defense-in-depth: the audit event MUST be emitted AT
         * LEAST once per successful add. This is a weaker check
         * than the {@code times(1)} above and exists primarily as
         * documentation of the AAP &sect;0.6.6 "every user
         * creation must be audited" rule.
         */
        @Test
        @DisplayName("audit event emitted at least once per successful add")
        void addUser_auditEventEmittedAtLeastOnce() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            verify(auditLogService, atLeastOnce()).logSecurityEvent(
                    anyString(), anyString(), anyString(),
                    any(), anyMap(), any());
        }
    }


    /**
     * Tests for the shape and content of the response DTO returned
     * by {@link UserAddService#addUser(UserAddDto)}.
     *
     * <p>The response carries the persisted entity's fields back
     * to the caller (matching the BMS map's display-on-success
     * behavior in COBOL) but with the password component redacted
     * to {@code null} per AAP &sect;0.6.6 PCI-DSS rules.</p>
     */
    @Nested
    @DisplayName("Response DTO shape and content")
    class ResponseShape {

        /**
         * The response {@code userId} MUST equal the normalized
         * userId stored on the entity. This guarantees the caller
         * sees the canonical primary-key value, which is what
         * subsequent operations (look-up, update, delete) require.
         */
        @Test
        @DisplayName("response userId equals normalized userId")
        void addUser_response_userIdIsNormalized() {
            // Arrange
            UserAddDto request = new UserAddDto(
                    LOWERCASE_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(request);

            // Assert
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
        }

        /**
         * The response {@code firstName} MUST equal the trimmed
         * value from the request. Trimming removes incidental
         * leading/trailing whitespace; case is preserved.
         */
        @Test
        @DisplayName("response firstName equals trimmed request firstName")
        void addUser_response_firstNameIsTrimmed() {
            // Arrange
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "  Jane  ", "Doe", PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(request);

            // Assert
            assertThat(response.firstName()).isEqualTo("Jane");
        }

        /**
         * The response {@code lastName} MUST equal the trimmed
         * value from the request.
         */
        @Test
        @DisplayName("response lastName equals trimmed request lastName")
        void addUser_response_lastNameIsTrimmed() {
            // Arrange
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, "Jane", "  Doe  ", PLAINTEXT_PASSWORD, USER_TYPE_USER);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(request);

            // Assert
            assertThat(response.lastName()).isEqualTo("Doe");
        }

        /**
         * The response {@code userType} MUST equal the normalized
         * uppercase userType from the entity.
         */
        @Test
        @DisplayName("response userType equals normalized uppercase userType")
        void addUser_response_userTypeIsUppercase() {
            // Arrange
            UserAddDto request = new UserAddDto(
                    NORMALIZED_USER_ID, FIRST_NAME, LAST_NAME, PLAINTEXT_PASSWORD, LOWERCASE_USER_TYPE);
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(request);

            // Assert
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
        }

        /**
         * The response DTO MUST NOT be {@code null}. This is a
         * trivial sanity assertion that complements all the
         * field-level checks above.
         */
        @Test
        @DisplayName("response DTO is not null on happy path")
        void addUser_response_notNull() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(validRequest);

            // Assert
            assertThat(response).isNotNull();
        }

        /**
         * Complete response-shape assertion: every component of
         * the response DTO carries the expected value. This is
         * the canonical "happy path" end-to-end assertion that a
         * reviewer might use as the entry point into this test
         * class.
         */
        @Test
        @DisplayName("response DTO carries all expected fields on happy path")
        void addUser_response_completeShape() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            UserAddDto response = service.addUser(validRequest);

            // Assert — every field has its expected value
            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(LAST_NAME);
            assertThat(response.password()).isNull();
            assertThat(response.userType()).isEqualTo(USER_TYPE_USER);
        }

        /**
         * The {@link UserSecurityRepository#save(Object)} method
         * MUST be invoked exactly once per successful add (the
         * COBOL parity for {@code EXEC CICS WRITE DATASET}).
         */
        @Test
        @DisplayName("save invoked exactly once on happy path")
        void addUser_saveInvokedExactlyOnce() {
            // Arrange
            when(userSecurityRepository.existsById(NORMALIZED_USER_ID)).thenReturn(false);
            when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(BCRYPT_HASH);
            when(userSecurityRepository.save(any(UserSecurity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addUser(validRequest);

            // Assert
            verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
        }
    }
}

