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
package com.aws.carddemo.service;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for the regular-user and admin-user
//     identifiers (TestFixtures.Users.REGULAR_USER_ID, ADMIN_USER_ID), the
//     fixture plaintext password (TestFixtures.Users.TEST_PASSWORD_PLAINTEXT),
//     and the deterministic clock instant (TestFixtures.Dates.FIXED_CLOCK_INSTANT
//     = "2024-01-15T00:00:00Z") that the AAP §0.4.2 Blueprint A mandates for
//     every authentication test in this class.
//
//   * SecurityUser — JPA entity replacement for the USRSEC VSAM record
//     described by app/cpy/CSUSR01Y.cpy. The COBOL fields SEC-USR-ID,
//     SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD, and SEC-USR-TYPE become Java
//     fields on this entity; the Java migration adds a `locked` boolean for
//     the account-lockout reject path (AAP §0.10 Java-migration addition) and
//     a JPA `@Version` Long for optimistic locking. Tests instantiate this
//     entity directly via setters (no @InjectMocks needed — SecurityUser is a
//     plain data carrier).
//
//   * UserSecurityRepository — Spring Data JPA repository whose `findById(id)`
//     replaces COBOL `EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)`.
//     Mocked at the JPA-repository boundary per AAP §0.10.1 ("Mocks limited to
//     external boundaries: file I/O, downstream service calls, database").
//
//   * AuthenticationRequest, AuthenticationResult, UserSession — authentication
//     DTOs under com.aws.carddemo.dto.auth. The DTO subtree is excluded from
//     the JaCoCo `<rule>` coverage gate per AAP §0.7.1 ("DTOs are data carriers
//     — excluded from coverage rules") so the service-layer coverage rule
//     applies cleanly to the service implementation only.
// ---------------------------------------------------------------------------
import com.aws.carddemo.dto.auth.AuthenticationRequest;
import com.aws.carddemo.dto.auth.AuthenticationResult;
import com.aws.carddemo.dto.auth.UserSession;
import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only, never
// JUnit 4 / Vintage).
//
//   * @BeforeEach — reinstantiates the system under test before every method;
//     paired with Mockito's default per-method @Mock instantiation to enforce
//     strict test isolation (AAP §0.10.9).
//   * @DisplayName — human-readable scenario names on the outer class and on
//     each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups happy-path, reject-path, and security-check tests into
//     three semantic sections that match the AAP §0.5.2 Blueprint A structure
//     and align with the exports.members_exposed schema (HappyPath, RejectPaths,
//     SecurityChecks).
//   * @Test — single-execution test marker (no parameter source).
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Parameterized-test support (AAP §0.6.1).
//
//   * @ParameterizedTest + @ValueSource(strings = ...) — drives empty-user-ID
//     and empty-password reject paths across three whitespace variants (empty
//     string, 3 spaces, 8 spaces = COBOL PIC X(08) field width). This proves
//     that the production validation is genuinely a "is-blank" check rather
//     than a fragile reference-equality comparison.
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 (AAP §0.6.1 — BOM-managed by spring-boot-starter-test 3.3.13;
// resolved to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method, guaranteeing
//     test isolation.
//   * MockitoExtension — activates STRICT_STUBS strictness by default (AAP
//     §0.10.1: "any unused stub fails the test — surfaces Require-Test-Coverage-
//     rule violations early"). Tests that configure a stub but never trigger
//     the production code path that uses it will fail with
//     UnnecessaryStubbingException — catching premature mocking that would
//     otherwise mask Require-Test-Coverage-rule violations.
//   * ArgumentMatchers.any — relaxes stub argument matching to "any value of
//     the declared type"; used where the production code's exact argument is
//     not part of the test's behavioural contract (e.g., the locked-user test
//     does not assert on which user ID was looked up — only that any lookup
//     yields the locked user).
//   * Mockito.when — fluent stubbing DSL; reads as
//     `when(<method-call>).thenReturn(<canned-value>)`.
// ---------------------------------------------------------------------------
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Spring Security crypto (AAP §0.10.5 — BCrypt verification is MANDATORY for
// the Java migration; the COBOL plaintext compare is REPLACED, never
// preserved).
//
//   * BCryptPasswordEncoder — the real (not mocked) implementation. Per AAP
//     §0.10.1 ("Real (not mocked) BCrypt password encoder used to hash the
//     fixture password and verify password-matching semantics"). Strength 10
//     is the Spring Security default and is fast enough for unit tests
//     (~80 ms / hash; each test creates one or two hashes — well under the
//     AAP §0.7.2 "< 60 s total unit-suite wall-clock" target).
//   * PasswordEncoder — the Spring Security interface; the encoder is held by
//     the test as the interface type (not the concrete BCryptPasswordEncoder
//     type) so the production AuthenticationService can accept any
//     PasswordEncoder implementation — proves the service is correctly
//     parameterised on the abstraction, not the concrete class.
// ---------------------------------------------------------------------------
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// ---------------------------------------------------------------------------
// Java standard library (AAP §0.6.1 — JDK 17 runtime).
//
//   * Clock — abstraction over the system clock injected into the production
//     AuthenticationService so tests can replace the wall-clock with a fixed
//     instant. Per AAP §0.4.2 Blueprint A: "Clock (fixed at 2024-01-15T00:00:00Z
//     for deterministic session timestamps)."
//   * Instant — parsed from TestFixtures.Dates.FIXED_CLOCK_INSTANT to seed the
//     fixed clock.
//   * LocalDateTime — the type of UserSession.getLoginTime(); asserted against
//     LocalDateTime.of(2024, 1, 15, 0, 0, 0) in the timestamp-determinism test.
//   * ZoneOffset — UTC offset used by Clock.fixed(...); guarantees that the
//     test does not depend on the JVM's default time zone.
//   * Optional — wraps repository return values: Optional.of(user) for happy /
//     locked-user scenarios; Optional.empty() for the unknown-user NOTFND
//     parity with COBOL DFHRESP(NOTFND).
// ---------------------------------------------------------------------------
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL and Mockito DSL (AAP §0.6.2 import
// transformation rules: "Use static imports for assertion helpers" and "Use
// static imports for Mockito DSL").
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthenticationService}, the migrated Java equivalent of the
 * CICS sign-on program {@code app/cbl/COSGN00C.cbl} (TRANID {@code CC00}). The service
 * replaces COBOL plaintext password comparison with BCrypt verification per AAP §0.10.5.
 *
 * <h2>COBOL Provenance — COSGN00C.cbl</h2>
 *
 * <p>The {@code PROCESS-ENTER-KEY} (lines ~108–140) and {@code READ-USER-SEC-FILE}
 * (lines ~209–257) paragraphs together implement the canonical sign-on workflow:
 *
 * <ol>
 *   <li>Validate {@code USERIDI OF COSGN0AI} is non-empty
 *       <ul><li>{@code SPACES OR LOW-VALUES} → {@code 'Please enter User ID ...'}</li></ul>
 *   </li>
 *   <li>Validate {@code PASSWDI OF COSGN0AI} is non-empty
 *       <ul><li>{@code SPACES OR LOW-VALUES} → {@code 'Please enter Password ...'}</li></ul>
 *   </li>
 *   <li>{@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}
 *       <ul>
 *         <li>{@code WS-RESP-CD = 13} (NOTFND) → {@code 'User not found. Try again ...'}</li>
 *         <li>{@code WS-RESP-CD = WHEN OTHER} (I/O error) → {@code 'Unable to verify the User ...'}</li>
 *       </ul>
 *   </li>
 *   <li>Compare {@code SEC-USR-PWD = WS-USER-PWD}
 *       <ul><li>Mismatch → {@code 'Wrong Password. Try again ...'}</li></ul>
 *   </li>
 *   <li>Dispatch by {@code SEC-USR-TYPE}
 *       <ul>
 *         <li>{@code 'A'} (admin) → {@code XCTL PROGRAM('COADM01C')} — admin menu</li>
 *         <li>otherwise (regular) → {@code XCTL PROGRAM('COMEN01C')} — main menu</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>Java Migration: BCrypt Verification (AAP §0.10.5)</h2>
 *
 * <p>The COBOL plaintext compare {@code IF SEC-USR-PWD = WS-USER-PWD} is replaced by
 * {@code passwordEncoder.matches(plaintext, hashedFromDb)} per AAP §0.10.5 ("No
 * plaintext credentials in any configuration file"). Tests use a real
 * {@link BCryptPasswordEncoder} at strength 10 (the Spring Security default; ~80 ms
 * per hash on commodity hardware — fast enough that the entire 14-test class
 * comfortably stays under the AAP §0.7.2 "< 60 s total unit-suite" wall-clock target).
 *
 * <h2>Java Migration: Account Locking (Industry-standard addition)</h2>
 *
 * <p>The Java migration adds account lockout on persistent failed attempts (industry
 * standard hardening absent from the COBOL source). The production service rejects
 * authentication for locked accounts with {@code 'Account is locked. Contact
 * administrator ...'} BEFORE attempting the BCrypt verify — preventing CPU exhaustion
 * via repeated password attempts against locked accounts. See
 * {@link RejectPaths#authenticate_lockedUser_rejectsWithAccountLockedMessage()}.
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link AuthenticationService}; mocks are limited to
 * the {@link UserSecurityRepository} (JPA boundary). The {@link BCryptPasswordEncoder}
 * is REAL — never mocked — because verifying real BCrypt semantics is the entire
 * point of the security tests in this class. The {@link Clock} is replaced with a
 * fixed instant rather than mocked, again because {@link Clock#fixed(Instant, ZoneOffset)}
 * is a real, deterministic implementation that does not require Mockito.
 *
 * <p>No business logic (password matching, role dispatch, validation cascade) is
 * reimplemented in test bodies — assertions reference only observable outputs (the
 * returned {@link AuthenticationResult} fields {@link AuthenticationResult#isSuccess()},
 * {@link AuthenticationResult#getMessage()}, {@link AuthenticationResult#getSession()},
 * and the {@link UserSession} fields it carries).
 *
 * <h2>Test Categories (AAP §0.5.2 Blueprint A)</h2>
 *
 * <ul>
 *   <li><b>{@link HappyPath}</b> — valid regular user, valid admin user, session
 *       login-time stamping from the fixed clock, welcome-message composition.</li>
 *   <li><b>{@link RejectPaths}</b> — empty user ID, empty password, unknown user,
 *       wrong password (BCrypt mismatch), locked user, invalid user type.</li>
 *   <li><b>{@link SecurityChecks}</b> — proves the production service uses real
 *       BCrypt verify (not string compare) and does not expose the password hash
 *       on the returned {@link UserSession}.</li>
 * </ul>
 *
 * @see AuthenticationService
 * @see SecurityUser
 * @see UserSecurityRepository
 * @see TestFixtures.Users
 * @see TestFixtures.Dates#FIXED_CLOCK_INSTANT
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService — COSGN00C.cbl migration parity")
final class AuthenticationServiceTest {

    /**
     * Mocked JPA repository boundary. The production
     * {@link AuthenticationService#authenticate(AuthenticationRequest)} method calls
     * {@code userSecurityRepository.findById(userId)} — the Java replacement for COBOL
     * {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}. The mock allows
     * each test to control the lookup outcome (found / not-found / locked / invalid-type)
     * without standing up a real database.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Real {@link BCryptPasswordEncoder} at strength 10 (Spring Security default).
     *
     * <p>NEVER mocked. The whole point of the security tests in this class is to prove
     * that the production {@link AuthenticationService} calls {@code matches(plaintext,
     * hashedFromDb)} on a real {@link PasswordEncoder} rather than performing a
     * (broken) string compare against the stored hash. Mocking the encoder would
     * defeat the test.
     *
     * <p>Strength 10 produces hashes in roughly 80 ms on commodity hardware; this
     * class's 14 tests cumulatively create well under one second of BCrypt work,
     * comfortably under the AAP §0.7.2 wall-clock budget.
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * Fixed clock at {@code 2024-01-15T00:00:00Z} (UTC).
     *
     * <p>Per AAP §0.4.2 Blueprint A: "Clock (fixed at 2024-01-15T00:00:00Z for
     * deterministic session timestamps)." The clock is injected into the production
     * {@link AuthenticationService} so {@code clock.instant()} returns this exact
     * value on every invocation, allowing the timestamp-determinism test
     * ({@link HappyPath#authenticate_validRequest_stampsLoginTimeFromInjectedClock()})
     * to assert an exact {@link LocalDateTime}.
     */
    private final Clock fixedClock = Clock.fixed(
            Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
            ZoneOffset.UTC);

    /**
     * System under test. Instantiated fresh per {@code @Test} via {@link #setUp()}
     * because {@code @Mock}-injected fields are reset by {@link MockitoExtension}
     * between methods, and any captured constructor reference would point at the
     * stale prior mock.
     */
    private AuthenticationService service;

    /**
     * Constructor-injects the freshly created Mockito mock, the real BCrypt encoder,
     * and the fixed clock into a new {@link AuthenticationService} instance before
     * every {@code @Test} method. The three-argument constructor signature documented
     * by AAP §0.4.2 Blueprint A is verified implicitly: if the production service
     * ever changes its constructor signature, this line will fail to compile and the
     * entire test class will be flagged at build time.
     */
    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userSecurityRepository, passwordEncoder, fixedClock);
    }

    // =========================================================================
    // HAPPY PATH — BCrypt verify succeeds, role dispatched, session stamped
    // =========================================================================

    /**
     * Happy-path tests for {@link AuthenticationService#authenticate(AuthenticationRequest)}.
     *
     * <p>Each test in this group asserts a different facet of the successful sign-on
     * flow:
     * <ul>
     *   <li>regular user → {@code MAIN_MENU} route (COBOL {@code XCTL COMEN01C} parity)</li>
     *   <li>admin user → {@code ADMIN_MENU} route (COBOL {@code XCTL COADM01C} parity)</li>
     *   <li>session login-time deterministically stamped from the injected {@link Clock}</li>
     *   <li>welcome message composed from the user's first and last name (COBOL
     *       {@code 'Welcome <FIRST> <LAST>'} parity)</li>
     * </ul>
     */
    @Nested
    @DisplayName("Happy path — BCrypt verify")
    class HappyPath {

        @Test
        @DisplayName("authenticate(validUser, validPassword) returns user session")
        void authenticate_validUserValidPassword_returnsUserSession() {
            // Arrange — hash the fixture plaintext with the REAL encoder, attach the
            // hash to a fresh SecurityUser, stub the repository to return that user
            // for the canonical regular-user ID.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            when(userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID))
                    .thenReturn(Optional.of(user));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act — call the real production service through its public API.
            AuthenticationResult result = service.authenticate(request);

            // Assert — observable behaviour of the production class:
            //   1. Result advertises success.
            //   2. Session is populated (not null) — the test would catch a regression
            //      that returned a "successful" result without attaching a session.
            //   3. Session carries the looked-up user ID, the regular-user type ('U'),
            //      and the next-route hint MAIN_MENU (COBOL XCTL COMEN01C parity).
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getSession()).isNotNull();
            assertThat(result.getSession().getUserId()).isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
            assertThat(result.getSession().getUserType()).isEqualTo("U");
            assertThat(result.getSession().getNextRoute()).isEqualTo("MAIN_MENU");
        }

        @Test
        @DisplayName("authenticate(adminUser, validPassword) returns admin session")
        void authenticate_adminUserValidPassword_returnsAdminSession() {
            // Arrange — same shape as the regular-user happy path but with SEC-USR-TYPE
            // = 'A' (admin). The COBOL CDEMO-USRTYP-ADMIN 88-level test drives XCTL
            // PROGRAM('COADM01C') instead of COMEN01C; the Java equivalent surfaces
            // the same routing decision as the session's nextRoute.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = adminUser(hashedPassword);
            when(userSecurityRepository.findById(TestFixtures.Users.ADMIN_USER_ID))
                    .thenReturn(Optional.of(user));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.ADMIN_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — success, admin user type, admin route.
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getSession()).isNotNull();
            assertThat(result.getSession().getUserType()).isEqualTo("A");
            assertThat(result.getSession().getNextRoute()).isEqualTo("ADMIN_MENU");
        }

        @Test
        @DisplayName("authenticate stamps session login time from fixed Clock")
        void authenticate_validRequest_stampsLoginTimeFromInjectedClock() {
            // Arrange — the test uses any() for the repository lookup because the
            // specific user ID matched by the stub is not part of the behaviour being
            // verified; the test instead asserts that the SESSION'S LOGIN TIME equals
            // the fixed-clock instant, proving the production service consults the
            // injected Clock rather than wall-clock time.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(user));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — login time equals the fixed-clock instant exactly. If the
            // production code calls LocalDateTime.now() (wall-clock) instead of
            // LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), this
            // assertion fails because the wall-clock value is the test-execution
            // moment, not 2024-01-15T00:00:00Z.
            assertThat(result.getSession().getLoginTime())
                    .as("Session timestamp from fixed Clock %s",
                            TestFixtures.Dates.FIXED_CLOCK_INSTANT)
                    .isEqualTo(LocalDateTime.of(2024, 1, 15, 0, 0, 0));
        }

        @Test
        @DisplayName("authenticate returns welcome message with user name")
        void authenticate_validRequest_returnsWelcomeMessage() {
            // Arrange — the fixture user gets first name JOHN and last name DOE so
            // the welcome message assertion can verify name interpolation; the
            // assertion is case-insensitive ("containsIgnoringCase") so the
            // production code can format the message with any casing.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            user.setFirstName("JOHN");
            user.setLastName("DOE");
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(user));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — the message contains the literal "welcome" (COBOL message
            // prefix parity) and the interpolated first name "john". The
            // case-insensitive assertion tolerates "Welcome JOHN" or "WELCOME John"
            // — any production formatting that preserves the two semantic tokens
            // passes.
            assertThat(result.getMessage())
                    .as("Welcome message per COBOL 'Welcome <FIRST> <LAST>'")
                    .containsIgnoringCase("welcome")
                    .containsIgnoringCase("john");
        }
    }

    // =========================================================================
    // REJECT PATHS — validation failures and credential failures
    // =========================================================================

    /**
     * Reject-path tests for {@link AuthenticationService#authenticate(AuthenticationRequest)}.
     *
     * <p>Each test asserts a specific reject path mapped from the COBOL workflow:
     * <ul>
     *   <li>empty user ID — COBOL {@code 'Please enter User ID ...'}</li>
     *   <li>empty password — COBOL {@code 'Please enter Password ...'}</li>
     *   <li>unknown user — COBOL {@code WS-RESP-CD = 13} → {@code 'User not found. Try again ...'}</li>
     *   <li>wrong password — COBOL {@code SEC-USR-PWD ≠ WS-USER-PWD} → {@code 'Wrong Password. Try again ...'}</li>
     *   <li>locked user — Java-migration addition → {@code 'Account is locked. Contact administrator ...'}</li>
     *   <li>invalid user type — neither {@code 'U'} nor {@code 'A'} → {@code 'User type not valid ...'}</li>
     * </ul>
     */
    @Nested
    @DisplayName("Reject paths — validation and credential failures")
    class RejectPaths {

        /**
         * Parameterized over three whitespace variants:
         * <ul>
         *   <li>{@code ""} — empty string</li>
         *   <li>{@code "   "} — 3 spaces (sub-field-width whitespace)</li>
         *   <li>{@code "        "} — 8 spaces (exactly the COBOL {@code PIC X(08)}
         *       field width — proves the production validator does not accept a
         *       "fully padded space-fill" value as a valid user ID).</li>
         * </ul>
         */
        @ParameterizedTest(name = "[{index}] empty user ID ''{0}''")
        @ValueSource(strings = {"", "   ", "        "})
        @DisplayName("authenticate rejects empty user ID")
        void authenticate_emptyUserId_rejectsWithUserIdEmptyMessage(String emptyUserId) {
            // Arrange — the production code should short-circuit on the first
            // validation step (empty user ID) WITHOUT calling the repository. The
            // repository mock is NOT stubbed for this test; if the production code
            // ever decides to consult it before validating, Mockito strict-stubs
            // mode allows the call (the mock returns null by default for unstubbed
            // methods) — but the subsequent NullPointerException would surface the
            // ordering regression loudly.
            AuthenticationRequest request = new AuthenticationRequest(
                    emptyUserId, TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — failure with the user-ID-empty COBOL message verbatim
            // (case-insensitive containment to tolerate "Please enter User ID ...",
            // "Please Enter User Id ...", etc.).
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Please enter User ID ...'")
                    .containsIgnoringCase("user id");
        }

        /**
         * Parameterized over three whitespace variants matching the empty-user-ID
         * test. The valid user ID and the empty password ensure the production
         * validator reaches the password-empty branch (rather than short-circuiting
         * on the user-ID-empty branch).
         */
        @ParameterizedTest(name = "[{index}] empty password ''{0}''")
        @ValueSource(strings = {"", "   ", "        "})
        @DisplayName("authenticate rejects empty password")
        void authenticate_emptyPassword_rejectsWithPasswordEmptyMessage(String emptyPassword) {
            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID, emptyPassword);

            AuthenticationResult result = service.authenticate(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Please enter Password ...'")
                    .containsIgnoringCase("password");
        }

        @Test
        @DisplayName("authenticate rejects unknown user (NOTFND parity)")
        void authenticate_unknownUser_rejectsWithUserNotFoundMessage() {
            // Arrange — the repository returns Optional.empty() for a user ID that
            // does not exist in USRSEC. This is the Java equivalent of COBOL
            // WS-RESP-CD = 13 (DFHRESP NOTFND) on EXEC CICS READ.
            String unknownUserId = "NOSUCH01";
            when(userSecurityRepository.findById(unknownUserId)).thenReturn(Optional.empty());

            AuthenticationRequest request = new AuthenticationRequest(
                    unknownUserId, TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — failure with the COBOL NOTFND message.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User not found. Try again ...'")
                    .containsIgnoringCase("not found");
        }

        @Test
        @DisplayName("authenticate rejects wrong password (BCrypt mismatch)")
        void authenticate_passwordMismatch_rejectsWithWrongPasswordMessage() {
            // Arrange — the persisted hash matches the fixture plaintext, but the
            // request's password is a DIFFERENT plaintext. BCrypt.matches must
            // return false, triggering the wrong-password branch.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            when(userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID))
                    .thenReturn(Optional.of(user));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID, "WRONGPWD");

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — failure with the COBOL wrong-password message.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Wrong Password. Try again ...'")
                    .containsIgnoringCase("wrong password");
        }

        @Test
        @DisplayName("authenticate rejects locked user (Java-migration addition)")
        void authenticate_lockedUser_rejectsWithAccountLockedMessage() {
            // Arrange — the user record has SEC-USR-LOCKED = true (a Java-migration
            // addition; the COBOL SEC-USER-DATA layout has no locked column). The
            // production code MUST reject the authentication BEFORE attempting the
            // BCrypt verify — preventing CPU exhaustion via repeated attempts
            // against locked accounts.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser lockedUser = regularUser(hashedPassword);
            lockedUser.setLocked(true);
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(lockedUser));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — failure carrying the account-locked message. The token "locked"
            // is asserted case-insensitively so the production code may use any
            // user-facing phrasing ("Account is locked", "Locked", etc.).
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Account-locked reject message (Java-migration addition)")
                    .containsIgnoringCase("locked");
        }

        @Test
        @DisplayName("authenticate rejects invalid user type (not U or A)")
        void authenticate_invalidUserType_rejectsWithInvalidTypeMessage() {
            // Arrange — SEC-USR-TYPE = 'X', which is neither the regular-user 'U'
            // nor the admin 'A'. The COBOL workflow's CDEMO-USRTYP-ADMIN 88-level
            // would fall through to the implicit-regular-user branch in COSGN00C
            // (lines 230-240), so this is a SAFETY-NET case for the Java migration:
            // when an unknown user type slips into the database, the migrated code
            // refuses to dispatch the session rather than silently routing as a
            // regular user.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            user.setUserType("X"); // invalid type
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(user));

            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — failure with a message that mentions both "type" and "valid"
            // (so the production code can phrase it as "User type not valid",
            // "Invalid user type", etc.).
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Invalid user type reject message")
                    .containsIgnoringCase("type")
                    .containsIgnoringCase("valid");
        }
    }

    // =========================================================================
    // SECURITY CHECKS — proves BCrypt verify is real (not plaintext compare)
    // =========================================================================

    /**
     * Security tests that prove the production
     * {@link AuthenticationService#authenticate(AuthenticationRequest)} method
     * uses REAL BCrypt verification and does not leak the password hash on the
     * returned session.
     *
     * <p>These tests are the practical enforcement of AAP §0.10.5 ("No plaintext
     * credentials in any configuration file") and AAP §0.10.1 ("Tests MUST NOT
     * reimplement any business or calculation logic inside test bodies"). They
     * cannot be replaced by mocks: a mocked {@link PasswordEncoder} could trivially
     * be configured to return any value, defeating the assurance that the
     * production code calls real BCrypt verification.
     */
    @Nested
    @DisplayName("Security — BCrypt verification path")
    class SecurityChecks {

        @Test
        @DisplayName("authenticate(hashAsPassword) must NOT match — proves real BCrypt verify")
        void authenticate_plaintextDoesNotMatchHash() {
            // Arrange — the persisted password is a BCrypt hash; authentication MUST
            // use BCrypt.matches(plaintext, hash) rather than a string compare. To
            // prove this, we send the HASH ITSELF as the request password. A naive
            // implementation that did `if (storedHash.equals(requestPassword))` would
            // succeed; the correct BCrypt.matches(hash, hash) implementation fails
            // because BCrypt extracts the salt from the stored hash and re-hashes
            // the input — the input was the literal "$2a$10$..." string, not the
            // original plaintext, so the re-hash does not match.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(user));

            // Try plaintext directly as if it were the hash — a regression to string
            // compare would authenticate successfully here.
            AuthenticationRequest request = new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    hashedPassword);

            // Act
            AuthenticationResult result = service.authenticate(request);

            // Assert — authentication MUST fail.
            assertThat(result.isSuccess())
                    .as("Sending the BCrypt hash as password must NOT authenticate "
                            + "— proves real BCrypt verify is used (not string compare)")
                    .isFalse();
        }

        @Test
        @DisplayName("authenticate session does not expose hashed or plaintext password")
        void authenticate_returnedSession_doesNotExposePassword() {
            // Arrange — successful authentication so a UserSession is created.
            String hashedPassword = passwordEncoder.encode(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            SecurityUser user = regularUser(hashedPassword);
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(user));

            // Act
            AuthenticationResult result = service.authenticate(new AuthenticationRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT));

            // Assert — the session must not carry either the hash or the plaintext
            // password in its observable string representation. Tests that print the
            // session to logs (for debugging or audit) must not leak credentials
            // (AAP §0.10.5 "No financial data written to logs at any level" —
            // applied here to credentials, not financial data).
            assertThat(result.getSession()).isNotNull();
            assertThat(result.getSession().toString())
                    .as("UserSession.toString() must not contain hashed or plaintext password")
                    .doesNotContain(hashedPassword)
                    .doesNotContain(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        }
    }

    // =========================================================================
    // HELPER FACTORIES — build SecurityUser fixtures used across the @Nested
    // groups. Kept at the outer-class level so all three @Nested groups can
    // reuse them without duplication. The helpers are package-private (default
    // access) because @Nested classes in JUnit 5 can access private static
    // members of the enclosing class — but `static` package-private is the
    // most defensive idiom against future test-class refactors.
    // =========================================================================

    /**
     * Builds a {@link SecurityUser} fixture for a REGULAR user (SEC-USR-TYPE = 'U').
     *
     * @param password the BCrypt-hashed password to attach (callers should obtain
     *                 this from {@code passwordEncoder.encode(...)} so the production
     *                 BCrypt.matches verification has a real hash to test against)
     * @return a fresh SecurityUser with userId = {@link TestFixtures.Users#REGULAR_USER_ID},
     *         names "TEST"/"USER", the supplied password, userType "U", locked = false,
     *         version = 1L
     */
    private static SecurityUser regularUser(String password) {
        SecurityUser u = new SecurityUser();
        u.setUserId(TestFixtures.Users.REGULAR_USER_ID);
        u.setFirstName("TEST");
        u.setLastName("USER");
        u.setPassword(password);
        u.setUserType("U");
        u.setLocked(false);
        u.setVersion(1L);
        return u;
    }

    /**
     * Builds a {@link SecurityUser} fixture for an ADMIN user (SEC-USR-TYPE = 'A').
     *
     * @param password the BCrypt-hashed password to attach
     * @return a fresh SecurityUser with userId = {@link TestFixtures.Users#ADMIN_USER_ID},
     *         names "ADMIN"/"USER", the supplied password, userType "A", locked = false,
     *         version = 1L
     */
    private static SecurityUser adminUser(String password) {
        SecurityUser u = new SecurityUser();
        u.setUserId(TestFixtures.Users.ADMIN_USER_ID);
        u.setFirstName("ADMIN");
        u.setLastName("USER");
        u.setPassword(password);
        u.setUserType("A");
        u.setLocked(false);
        u.setVersion(1L);
        return u;
    }
}
