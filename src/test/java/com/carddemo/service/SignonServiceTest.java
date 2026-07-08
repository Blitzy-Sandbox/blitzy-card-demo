package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserSecurityRepository;

/**
 * Pure, fast Mockito unit tests for {@link SignonService}, the sign-on / authentication service
 * migrated from the legacy CICS program {@code COSGN00C} (CICS transaction {@code CC00}; frozen
 * COBOL reference at source commit SHA {@code 27d6c6f}, read-only &mdash; not copied into this
 * repository).
 *
 * <p>All three collaborators &mdash; the {@link UserSecurityRepository} over the migrated
 * {@code USRSEC} store, the Spring Security {@link PasswordEncoder}, and the {@link JwtService}
 * that replaces the CICS {@code COMMAREA} &mdash; are supplied as Mockito mocks, and the service is
 * created through constructor injection ({@code @InjectMocks}). The suite therefore loads
 * <strong>no</strong> Spring context and touches no database, Testcontainers, Docker, or live AWS,
 * satisfying the folder-wide "no Spring context, no real DB" convention while feeding the JaCoCo
 * line-coverage gate (Gate&nbsp;8). It is a security-critical suite covering the BCrypt credential
 * handling introduced by Constraint&nbsp;C-003 / Decision&nbsp;Log&nbsp;D-002.</p>
 *
 * <h2>Behavioural parity assertions ({@code COSGN00C} &rarr; {@link SignonService#authenticate})</h2>
 * <ul>
 *   <li><b>{@code PROCESS-ENTER-KEY} mandatory-field edits</b> &mdash; a blank user id or password
 *       raises {@link ValidationException} (HTTP&nbsp;{@code 400}) carrying the verbatim legacy
 *       prompts {@code 'Please enter User ID ...'} / {@code 'Please enter Password ...'}
 *       (COBOL&nbsp;L120 / L125), and no collaborator is touched.</li>
 *   <li><b>{@code READ-USER-SEC-FILE} {@code EVALUATE WS-RESP-CD}</b> &mdash; {@code WHEN 13}
 *       (not found) and the {@code WHEN 0} password-mismatch branch both surface as the Spring
 *       Security {@link BadCredentialsException} (HTTP&nbsp;{@code 401}) carrying the verbatim
 *       {@code 'User not found. Try again ...'} (COBOL&nbsp;L249) and
 *       {@code 'Wrong Password. Try again ...'} (COBOL&nbsp;L242) messages respectively.</li>
 *   <li><b>Dual {@code FUNCTION UPPER-CASE}</b> (COBOL&nbsp;L132 / L135) &mdash; the service
 *       upper-cases both the user id and the password before the keyed read and the BCrypt compare;
 *       this is proven with {@link ArgumentCaptor}s over
 *       {@link UserSecurityRepository#findById(Object)} and
 *       {@link PasswordEncoder#matches(CharSequence, String)}.</li>
 *   <li><b>{@code WHEN 0} success routing</b> &mdash; {@code SEC-USR-TYPE = 'A'}
 *       ({@code CDEMO-USRTYP-ADMIN} &rarr; {@code COADM01C}) maps to {@link SignonResponse#ROLE_ADMIN},
 *       every other type ({@code COMEN01C}) maps to {@link SignonResponse#ROLE_USER}, and the issued
 *       JWT is the {@link JwtService#generateToken(String, String)} result.</li>
 * </ul>
 *
 * <h2>Security invariants exercised</h2>
 * <ul>
 *   <li>The comparison path is <strong>always</strong> {@link PasswordEncoder#matches} against the
 *       stored hash &mdash; never a direct string equality on the plaintext.</li>
 *   <li>The {@link SignonResponse} carries <strong>no</strong> password component (asserted
 *       reflectively over the record's components) and neither the raw password nor the stored hash
 *       leaks into its rendered representation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignonService — COBOL COSGN00C signon parity + BCrypt credential handling (SHA 27d6c6f)")
class SignonServiceTest {

    /** Canonical (already upper-cased) user id used across the success and parity scenarios. */
    private static final String USER_ID = "USER0001";

    /** First name stored on the fixture user; echoed into a successful {@link SignonResponse}. */
    private static final String FIRST_NAME = "John";

    /** Last name stored on the fixture user; echoed into a successful {@link SignonResponse}. */
    private static final String LAST_NAME = "Doe";

    /**
     * Obviously-fake stand-in for a stored BCrypt hash. Its contents are irrelevant because the
     * {@link PasswordEncoder} is mocked; using a clearly non-secret placeholder keeps real
     * credential patterns out of the source tree.
     */
    private static final String STORED_HASH = "ENCODED-BCRYPT-HASH-PLACEHOLDER";

    /** Fake issued token returned by the mocked {@link JwtService}; not a real JWT. */
    private static final String ISSUED_TOKEN = "issued.jwt.token";

    /** Legacy {@code SEC-USR-TYPE} administrator code ({@code CDEMO-USRTYP-ADMIN VALUE 'A'}). */
    private static final String TYPE_ADMIN = "A";

    /** Legacy {@code SEC-USR-TYPE} regular-user code ({@code CDEMO-USRTYP-USER VALUE 'U'}). */
    private static final String TYPE_USER = "U";

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private SignonService service;

    /**
     * Builds a {@link UserSecurity} fixture mirroring a single {@code SEC-USER-DATA} record
     * (copybook {@code CSUSR01Y}). The first and last names are fixed constants; only the id, the
     * stored (already-hashed) password, and the {@code SEC-USR-TYPE} vary per scenario.
     *
     * @param userId       the user id ({@code SEC-USR-ID})
     * @param hashedPwd    the stored BCrypt hash ({@code SEC-USR-PWD}, widened per C-003)
     * @param secUsrType   the role code ({@code SEC-USR-TYPE}: {@code 'A'} admin, else regular)
     * @return a populated {@link UserSecurity}
     */
    private static UserSecurity user(String userId, String hashedPwd, String secUsrType) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(userId);
        u.setSecUsrPwd(hashedPwd);
        u.setSecUsrFname(FIRST_NAME);
        u.setSecUsrLname(LAST_NAME);
        u.setSecUsrType(secUsrType);
        return u;
    }

    /**
     * Asserts that {@link SignonResponse} exposes no password/secret component at all &mdash; a
     * compile-time-ish structural guarantee that the response can never carry the plaintext or the
     * stored hash.
     */
    private static void assertResponseHasNoPasswordComponent() {
        boolean hasPasswordComponent = Arrays.stream(SignonResponse.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().toLowerCase(Locale.ROOT).contains("pass"));
        assertThat(hasPasswordComponent)
                .as("SignonResponse must not expose any password component")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // PROCESS-ENTER-KEY mandatory-field edits (COBOL L117-L130) -> 400
    // ------------------------------------------------------------------

    @Test
    @DisplayName("blank user id -> ValidationException(400) 'Please enter User ID ...' and no collaborator is touched")
    void authenticate_blankUserId_throwsValidationException() {
        assertThatThrownBy(() -> service.authenticate(new SignonRequest("", "pw")))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(SignonService.MSG_ENTER_USERID);

        verifyNoInteractions(userSecurityRepository, passwordEncoder, jwtService);
    }

    @Test
    @DisplayName("blank password -> ValidationException(400) 'Please enter Password ...' and no collaborator is touched")
    void authenticate_blankPassword_throwsValidationException() {
        assertThatThrownBy(() -> service.authenticate(new SignonRequest(USER_ID, "")))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(SignonService.MSG_ENTER_PASSWORD);

        verifyNoInteractions(userSecurityRepository, passwordEncoder, jwtService);
    }

    // ------------------------------------------------------------------
    // READ-USER-SEC-FILE EVALUATE WS-RESP-CD (COBOL L221-L257) -> 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown user (WHEN 13) -> BadCredentialsException(401) 'User not found. Try again ...'")
    void authenticate_unknownUser_throwsBadCredentials() {
        when(userSecurityRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(new SignonRequest(USER_ID, "PASSWORD")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(SignonService.MSG_USER_NOT_FOUND);

        // The password is never verified and no token is issued once the user is absent.
        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    @DisplayName("wrong password (WHEN 0, mismatch) -> BadCredentialsException(401) 'Wrong Password. Try again ...'")
    void authenticate_wrongPassword_throwsBadCredentials() {
        when(userSecurityRepository.findById(anyString()))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, TYPE_USER)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.authenticate(new SignonRequest(USER_ID, "WRONGPW")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(SignonService.MSG_WRONG_PASSWORD);

        // A failed compare must never issue a token.
        verifyNoInteractions(jwtService);
    }

    // ------------------------------------------------------------------
    // WHEN 0 success routing (COBOL L222-L239): SEC-USR-TYPE -> role + JWT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success, SEC-USR-TYPE 'A' -> role ADMIN, JWT from jwtService, no password exposed")
    void authenticate_success_adminRole() {
        when(userSecurityRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, TYPE_ADMIN)));
        when(passwordEncoder.matches("PASSWORD", STORED_HASH)).thenReturn(true);
        when(jwtService.generateToken(eq(USER_ID), eq(SignonResponse.ROLE_ADMIN)))
                .thenReturn(ISSUED_TOKEN);

        SignonResponse response = service.authenticate(new SignonRequest(USER_ID, "PASSWORD"));

        assertThat(response.token()).isEqualTo(ISSUED_TOKEN);
        assertThat(response.tokenType()).isEqualTo(SignonResponse.BEARER);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo(FIRST_NAME);
        assertThat(response.lastName()).isEqualTo(LAST_NAME);
        assertThat(response.role()).isEqualTo(SignonResponse.ROLE_ADMIN);
        assertResponseHasNoPasswordComponent();
    }

    @Test
    @DisplayName("success, SEC-USR-TYPE 'U' -> role USER")
    void authenticate_success_userRole() {
        when(userSecurityRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, TYPE_USER)));
        when(passwordEncoder.matches("PASSWORD", STORED_HASH)).thenReturn(true);
        when(jwtService.generateToken(eq(USER_ID), eq(SignonResponse.ROLE_USER)))
                .thenReturn(ISSUED_TOKEN);

        SignonResponse response = service.authenticate(new SignonRequest(USER_ID, "PASSWORD"));

        assertThat(response.role()).isEqualTo(SignonResponse.ROLE_USER);
        assertThat(response.token()).isEqualTo(ISSUED_TOKEN);
    }

    // ------------------------------------------------------------------
    // Dual FUNCTION UPPER-CASE parity (COBOL L132 / L135)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("credentials are upper-cased (Locale.ROOT) before the keyed read and the BCrypt compare")
    void authenticate_upperCasesCredentials() {
        when(userSecurityRepository.findById(anyString()))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, TYPE_USER)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        // Lower-case input; the service must upper-case both fields before using them.
        service.authenticate(new SignonRequest("user0001", "secret"));

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(userSecurityRepository).findById(idCaptor.capture());
        assertThat(idCaptor.getValue())
                .as("user id must be upper-cased before the USRSEC keyed read")
                .isEqualTo("USER0001");

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(rawCaptor.capture(), any());
        assertThat(rawCaptor.getValue())
                .as("presented password must be upper-cased before the BCrypt compare")
                .isEqualTo("SECRET");
    }

    // ------------------------------------------------------------------
    // BCrypt-only comparison + no-secret-leak invariants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("comparison always goes through PasswordEncoder.matches against the stored hash; no secret leaks into the response")
    void authenticate_neverPlaintextCompare() {
        when(userSecurityRepository.findById(anyString()))
                .thenReturn(Optional.of(user(USER_ID, STORED_HASH, TYPE_USER)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtService.generateToken(anyString(), anyString())).thenReturn(ISSUED_TOKEN);

        SignonResponse response = service.authenticate(new SignonRequest(USER_ID, "SECRET"));

        // The stored hash is only ever handed to the encoder as the second argument; the service
        // performs no direct string equality against it.
        ArgumentCaptor<String> storedCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(any(), storedCaptor.capture());
        assertThat(storedCaptor.getValue()).isEqualTo(STORED_HASH);

        // The response never carries the raw password or the stored hash.
        assertResponseHasNoPasswordComponent();
        assertThat(response.token()).isEqualTo(ISSUED_TOKEN);
        assertThat(response.toString())
                .doesNotContain(STORED_HASH)
                .doesNotContain("SECRET");
    }
}
