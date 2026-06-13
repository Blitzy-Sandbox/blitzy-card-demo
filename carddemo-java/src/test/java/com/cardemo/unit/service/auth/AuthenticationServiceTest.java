package com.cardemo.unit.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.TokenService;
import com.cardemo.service.auth.AuthenticationService;

/**
 * Fast, fully-mocked unit test for {@link AuthenticationService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS sign-on program
 * <strong>{@code app/cbl/COSGN00C.cbl}</strong> (PROGRAM-ID {@code COSGN00C}, CICS transaction
 * <strong>{@code CC00}</strong>, "Signon Screen for the CardDemo Application"). It exercises the two
 * paragraphs that make up the authentication action &mdash; {@code PROCESS-ENTER-KEY}
 * (COSGN00C&nbsp;L108-140) and {@code READ-USER-SEC-FILE} (L209-257) &mdash; and asserts that the
 * migrated service reproduces their observable behavior <em>exactly</em> (100% behavioral-parity
 * gate, AAP&nbsp;&sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>This test is the parity guardian for sign-on</h2>
 * <p>Its non-negotiables, each pinned by an assertion below:</p>
 * <ol>
 *   <li><strong>The ordered, first-error-wins four-branch cascade with verbatim COBOL messages.</strong>
 *       Each operator message is asserted byte-for-byte with {@link org.assertj.core.api.AbstractThrowableAssert#hasMessage(String)
 *       hasMessage(...)} &mdash; including the trailing three-dot ellipsis {@code " ..."} &mdash; so a
 *       reworded service message fails this test. The user-id check is proven to run strictly before the
 *       password check ({@code EVALUATE TRUE}, COSGN00C&nbsp;L117-130).</li>
 *   <li><strong>Both {@code userId} and {@code password} are upper-cased before the keyed read and the
 *       credential verify</strong> (COBOL {@code FUNCTION UPPER-CASE}, L132-136). Given lowercase inputs,
 *       the repository is queried with the upper-cased id and the encoder verifies the upper-cased
 *       password.</li>
 *   <li><strong>Password verification flows through BCrypt {@link PasswordEncoder#matches(CharSequence, String)
 *       matches(...)}, not a plaintext {@code equals}</strong> &mdash; the single permitted behavioral
 *       change (constraint C-003, AAP&nbsp;&sect;0.7.2). Where {@code COSGN00C} compared
 *       {@code IF SEC-USR-PWD = WS-USER-PWD} (L223), the migration BCrypt-verifies against the stored
 *       hash.</li>
 *   <li><strong>Post-login routing</strong>: an admin ({@code CDEMO-USRTYP-ADMIN}, {@code 'A'}) routes to
 *       {@code COADM01C}/{@code CA00}; a regular user ({@code 'U'}) routes to {@code COMEN01C}/{@code CM00}
 *       (COBOL {@code XCTL}, L230-237). The password is <em>never</em> echoed &mdash; {@link SignOnResponse}
 *       has no password field.</li>
 * </ol>
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring, no I/O</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: no {@code @SpringBootTest}, no Spring
 * context, no Testcontainers, no database, no real I/O (AAP&nbsp;&sect;0.5.1 &mdash; only JUnit&nbsp;5 +
 * Mockito + AssertJ from {@code spring-boot-starter-test}; no new dependency). The class runs under
 * {@link MockitoExtension} (default {@code STRICT_STUBS}), so the pure-validation tests stub
 * <em>nothing</em> and assert {@link org.mockito.Mockito#verifyNoInteractions(Object...)
 * verifyNoInteractions} to prove the early throw, while every other test stubs <em>only</em> what it uses
 * (no {@code lenient()}).</p>
 *
 * <p>The system under test is wired by explicit constructor injection in {@link #setUp()} &mdash; the real
 * constructor is {@code AuthenticationService(UserSecurityRepository, PasswordEncoder, TokenService)}.
 * The token-issuance collaborator {@link TokenService} (the CICS COMMAREA&nbsp;&rarr;&nbsp;stateless-token
 * substitution, AAP&nbsp;&sect;0.1.2) is a {@code @Mock}, so token signing internals are out of scope here
 * and the issued token is a controlled stub value; this test asserts only that a non-blank token is carried
 * through and never contains the credential, deliberately not coupling to the token's byte format.</p>
 *
 * <p><strong>Traceability.</strong> Behavior derived from the frozen COBOL baseline at commit SHA
 * {@code 27d6c6f}. The COBOL source is read-only reference material and is never copied into this
 * repository &mdash; only its observable behavior is asserted.</p>
 *
 * @see AuthenticationService
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.dto.SignOnResponse
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService (COSGN00C sign-on) — parity unit tests")
class AuthenticationServiceTest {

    // ---------------------------------------------------------------------
    // Verbatim COBOL operator messages (COSGN00C). Declared here independently
    // of the service constants so the assertions enforce the exact external
    // contract — including the trailing " ..." (space-dot-dot-dot). A deviation
    // in the service MUST fail this test (AAP §0.7.2 parity-enforcement).
    // ---------------------------------------------------------------------

    /** COSGN00C L120 — {@code WHEN USERIDI = SPACES OR LOW-VALUES}. */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** COSGN00C L125 — {@code WHEN PASSWDI = SPACES OR LOW-VALUES}. */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /** COSGN00C L249 — {@code READ-USER-SEC-FILE WHEN 13} (DFHRESP NOTFND). */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** COSGN00C L242 — {@code WHEN 0} + {@code SEC-USR-PWD NOT = WS-USER-PWD}. */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    // ---------------------------------------------------------------------
    // Canonical golden test users — sourced from the USRSEC seed in
    // app/jcl/DUSRSECJ.jcl (80-byte CSUSR01Y layout): "ADMIN001...PASSWORDA"
    // (type 'A' -> ADMIN) and "USER0001...PASSWORDU" (type 'U' -> USER).
    // ---------------------------------------------------------------------

    /** Golden admin user id ({@code SEC-USR-TYPE 'A'} -> {@link UserType#ADMIN}). */
    private static final String ADMIN_ID = "ADMIN001";

    /** Golden regular user id ({@code SEC-USR-TYPE 'U'} -> {@link UserType#USER}). */
    private static final String USER_ID = "USER0001";

    /** The 8-character seed password ({@code SEC-USR-PWD}) common to the golden users. */
    private static final String RAW_PASSWORD = "PASSWORD";

    /**
     * A realistic BCrypt-looking stored hash that is deliberately DISTINCT from {@link #RAW_PASSWORD}.
     * Its only role is to prove (C-003) that verification cannot pass via a plaintext {@code equals}:
     * the success tests only pass because the {@link PasswordEncoder} mock is stubbed to accept this hash.
     */
    private static final String STORED_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * Controlled stub token returned by the mocked {@link TokenService}. It is non-blank and contains no
     * credential; the test asserts only those two properties, never the token's internal format.
     */
    private static final String STUB_TOKEN = "header.payload.signature";

    /** Keyed access to {@code USRSEC} (the sign-on read) — mocked, no database. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** BCrypt verifier (C-003) — mocked so the verify path is asserted via Mockito, not real hashing. */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Stateless-token issuer (COMMAREA -> token substitution) — mocked; token internals out of scope. */
    @Mock
    private TokenService tokenService;

    /** System under test, wired by explicit constructor injection in {@link #setUp()}. */
    private AuthenticationService service;

    /**
     * Constructs the service with the three Mockito mocks via its real constructor signature
     * {@code AuthenticationService(UserSecurityRepository, PasswordEncoder, TokenService)}. Explicit
     * construction is preferred over {@code @InjectMocks} so the wiring is unambiguous and stable.
     */
    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userSecurityRepository, passwordEncoder, tokenService);
    }

    // ---------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------

    /**
     * Builds a {@link SignOnRequest} carrying the supplied (possibly null/blank) credentials directly,
     * bypassing the controller-layer Bean Validation so the service's own cascade is the thing under test.
     *
     * @param userId   the entered user id ({@code USERIDI}); may be {@code null}
     * @param password the entered password ({@code PASSWDI}); may be {@code null}
     * @return a populated request
     */
    private static SignOnRequest request(String userId, String password) {
        SignOnRequest r = new SignOnRequest();
        r.setUserId(userId);
        r.setPassword(password);
        return r;
    }

    /**
     * Builds a {@link UserSecurity} as the {@code USRSEC} record the repository would return. The entity
     * performs no hashing; the supplied {@code hash} is stored verbatim as {@code SEC-USR-PWD}. Name fields
     * are placeholders (they do not participate in the sign-on flow).
     *
     * @param id   the user id ({@code SEC-USR-ID})
     * @param hash the stored BCrypt hash ({@code SEC-USR-PWD})
     * @param type the user type ({@code SEC-USR-TYPE})
     * @return a populated user-security record
     */
    private static UserSecurity user(String id, String hash, UserType type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrFname("FNAME");
        u.setSecUsrLname("LNAME");
        u.setSecUsrPwd(hash);
        u.setSecUsrType(type);
        return u;
    }

    // =====================================================================
    // Phase B — PROCESS-ENTER-KEY blank-field cascade (stub NOTHING).
    // =====================================================================

    @ParameterizedTest(name = "userId=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("PROCESS-ENTER-KEY: blank User ID -> ValidationException 'Please enter User ID ...'")
    void blankUserIdRejectedFirst(String blankUserId) {
        // COSGN00C L120: WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES.
        assertThatThrownBy(() -> service.signOn(request(blankUserId, RAW_PASSWORD)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ENTER_USER_ID);
        // The guard throws before any read or verify (the COBOL ERR-FLG-ON re-display path).
        verifyNoInteractions(userSecurityRepository, passwordEncoder, tokenService);
    }

    @ParameterizedTest(name = "password=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("PROCESS-ENTER-KEY: blank Password -> ValidationException 'Please enter Password ...'")
    void blankPasswordRejected(String blankPassword) {
        // userId is non-blank so the cascade reaches the second WHEN (COSGN00C L125).
        assertThatThrownBy(() -> service.signOn(request(USER_ID, blankPassword)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ENTER_PASSWORD);
        verifyNoInteractions(userSecurityRepository, passwordEncoder, tokenService);
    }

    @Test
    @DisplayName("PROCESS-ENTER-KEY EVALUATE TRUE order: both blank -> User ID message wins (first-error-wins)")
    void blankUserIdWinsOverBlankPassword() {
        // Proves the COBOL EVALUATE TRUE evaluation order (user id strictly before password):
        // with BOTH fields blank, the User ID message — not the Password message — is raised.
        assertThatThrownBy(() -> service.signOn(request("", "")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ENTER_USER_ID);
        verifyNoInteractions(userSecurityRepository, passwordEncoder, tokenService);
    }

    // =====================================================================
    // Phase C — READ-USER-SEC-FILE outcomes.
    // =====================================================================

    @Test
    @DisplayName("READ-USER-SEC-FILE WHEN 13 -> RecordNotFoundException 'User not found. Try again ...'")
    void unknownUserRaisesRecordNotFound() {
        // COSGN00C L247-249: keyed read misses (DFHRESP NOTFND). "NOSUCH" is already upper-case.
        when(userSecurityRepository.findBySecUsrId("NOSUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.signOn(request("NOSUCH", RAW_PASSWORD)))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_NOT_FOUND);

        // No record -> the BCrypt verify is never reached.
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("READ-USER-SEC-FILE WHEN 0 + password mismatch -> ValidationException 'Wrong Password. Try again ...'")
    void wrongPasswordRaisesValidation() {
        // Record found (WHEN 0), but BCrypt verification fails (COSGN00C L223 IF SEC-USR-PWD NOT = WS-USER-PWD).
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, UserType.USER)));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.signOn(request(USER_ID, RAW_PASSWORD)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_WRONG_PASSWORD);
    }

    // =====================================================================
    // Phase D — success path + post-login routing (COSGN00C L222-240 XCTL).
    // =====================================================================

    @Test
    @DisplayName("Success ADMIN ('A') -> XCTL COADM01C / tranid CA00, token carried")
    void adminSignOnRoutesToAdminMenu() {
        when(userSecurityRepository.findBySecUsrId(ADMIN_ID))
                .thenReturn(Optional.of(user(ADMIN_ID, STORED_HASH, UserType.ADMIN)));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);
        when(tokenService.issue(ADMIN_ID, UserType.ADMIN)).thenReturn(STUB_TOKEN);

        SignOnResponse response = service.signOn(request(ADMIN_ID, RAW_PASSWORD));

        // IF CDEMO-USRTYP-ADMIN -> XCTL PROGRAM('COADM01C') (COSGN00C L230-232).
        assertThat(response.getToProgram()).isEqualTo("COADM01C");
        assertThat(response.getToTranId()).isEqualTo("CA00");
        assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(response.getUserId()).isEqualTo(ADMIN_ID);
        assertThat(response.getToken()).isNotBlank();
    }

    @Test
    @DisplayName("Success USER ('U') -> XCTL COMEN01C / tranid CM00, token carried")
    void regularSignOnRoutesToMainMenu() {
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, UserType.USER)));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);
        when(tokenService.issue(USER_ID, UserType.USER)).thenReturn(STUB_TOKEN);

        SignOnResponse response = service.signOn(request(USER_ID, RAW_PASSWORD));

        // ELSE -> XCTL PROGRAM('COMEN01C') (COSGN00C L236-237).
        assertThat(response.getToProgram()).isEqualTo("COMEN01C");
        assertThat(response.getToTranId()).isEqualTo("CM00");
        assertThat(response.getUserType()).isEqualTo(UserType.USER);
        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getToken()).isNotBlank();
    }

    // =====================================================================
    // Phase E — parity-critical behaviors.
    // =====================================================================

    @Test
    @DisplayName("FUNCTION UPPER-CASE parity: lowercase inputs upper-cased BEFORE lookup and BCrypt verify")
    void inputsUpperCasedBeforeLookupAndVerify() {
        // Stubs use the UPPER-CASED key/password; the request supplies LOWERCASE inputs.
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, UserType.USER)));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);
        when(tokenService.issue(USER_ID, UserType.USER)).thenReturn(STUB_TOKEN);

        SignOnResponse response = service.signOn(request("user0001", "password"));

        assertThat(response.getUserId()).isEqualTo(USER_ID);
        // Proof: the repo was queried with the UPPER-CASED id, and the encoder verified the
        // UPPER-CASED password (COBOL FUNCTION UPPER-CASE, COSGN00C L132-136 + RIDFLD WS-USER-ID L215).
        verify(userSecurityRepository).findBySecUsrId("USER0001");
        verify(passwordEncoder).matches("PASSWORD", STORED_HASH);
    }

    @Test
    @DisplayName("C-003: credential verified via BCrypt matches(), never a plaintext equals")
    void verificationFlowsThroughBcryptNotPlaintext() {
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, UserType.USER)));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);
        when(tokenService.issue(USER_ID, UserType.USER)).thenReturn(STUB_TOKEN);

        SignOnResponse response = service.signOn(request(USER_ID, RAW_PASSWORD));

        assertThat(response).isNotNull();
        // C-003: single permitted behavioral change — plaintext compare -> BCrypt verify.
        verify(passwordEncoder).matches(RAW_PASSWORD, STORED_HASH);
        // The stored hash is NOT the raw password, so a plaintext equals would NOT have matched:
        // the test only passes because verification flows through the BCrypt encoder.
        assertThat(STORED_HASH).isNotEqualTo(RAW_PASSWORD);
    }

    @Test
    @DisplayName("Password never echoed: token is non-blank and carries no credential; response has no password field")
    void passwordNeverEchoedInResponse() {
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, UserType.USER)));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);
        when(tokenService.issue(USER_ID, UserType.USER)).thenReturn(STUB_TOKEN);

        SignOnResponse response = service.signOn(request(USER_ID, RAW_PASSWORD));

        // SignOnResponse exposes NO password field (structural, compile-time guarantee). The conveyed
        // fields must never leak the credential or its hash.
        assertThat(response.getToken()).isNotBlank().doesNotContain(RAW_PASSWORD);
        assertThat(response.getUserId()).doesNotContain(RAW_PASSWORD);
        assertThat(response.getToProgram()).doesNotContain(RAW_PASSWORD);
        assertThat(response.getToTranId()).doesNotContain(RAW_PASSWORD);
    }

    // =====================================================================
    // Phase F — infrastructure-error propagation (READ-USER-SEC-FILE WHEN OTHER).
    // =====================================================================

    @Test
    @DisplayName("READ-USER-SEC-FILE WHEN OTHER: a data-access failure propagates (maps to HTTP 500, not a domain exception)")
    void dataAccessFailurePropagates() {
        // COSGN00C WHEN OTHER ('Unable to verify the User ...') maps to an infrastructure failure:
        // a Spring DataAccessException propagates to the framework's 500 fallback rather than being
        // converted to a typed domain exception, so no verbatim message is asserted here.
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenThrow(new DataAccessResourceFailureException("simulated USRSEC read failure"));

        assertThatThrownBy(() -> service.signOn(request(USER_ID, RAW_PASSWORD)))
                .isInstanceOf(DataAccessException.class);

        // The failure occurs at the read, before any credential verification.
        verify(passwordEncoder, never()).matches(any(), any());
    }
}
