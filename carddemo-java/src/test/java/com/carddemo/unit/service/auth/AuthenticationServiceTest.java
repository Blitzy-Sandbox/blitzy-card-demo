package com.carddemo.unit.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.auth.AuthenticationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Isolated, JVM-only JUnit 5 + Mockito unit tests for {@link AuthenticationService}, the Java
 * translation of the CardDemo CICS sign-on program {@code COSGN00C} (USRSEC record from copybook
 * {@code CSUSR01Y}; source commit {@code 27d6c6f}, REFERENCE ONLY &mdash; the COBOL is not copied).
 *
 * <p>The tests pin behavioral parity with the {@code PROCESS-ENTER-KEY} blank-field edits and the
 * {@code READ-USER-SEC-FILE} lookup-then-verify flow: the user-id-before-password edit order, the
 * dual {@code FUNCTION UPPER-CASE} normalization, the lookup-before-compare order, and the three
 * COBOL outcomes (success / wrong password / user not found). The two collaborators are mocked, so
 * there is no Spring context, database, or AWS dependency. {@link MockitoExtension} runs in its
 * default {@code STRICT_STUBS} strictness, so each test stubs only the collaborators its path
 * reaches.</p>
 */
@DisplayName("AuthenticationService - COSGN00C sign-on parity (lookup then BCrypt verify)")
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    /** USRSEC repository collaborator (re-platforms {@code EXEC CICS READ DATASET(USRSEC)}); mocked. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** BCrypt password encoder collaborator (constraint C-003 verification); mocked interface. */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Service under test; the two {@code @Mock}s are wired by constructor injection. */
    @InjectMocks
    private AuthenticationService authenticationService;

    /** Raw user id exactly as a client submits it on the COSGN00 map (lower-case, untrimmed). */
    private static final String RAW_USER_ID = "admin";

    /** Raw password exactly as submitted (lower-case); proves upper-casing before the compare. */
    private static final String RAW_PASSWORD = "password";

    /** Expected USRSEC key after {@code trim()} + upper-case of {@link #RAW_USER_ID}. */
    private static final String NORMALIZED_USER_ID = "ADMIN";

    /** Expected password after upper-case of {@link #RAW_PASSWORD} (COBOL {@code FUNCTION UPPER-CASE}). */
    private static final String NORMALIZED_PASSWORD = "PASSWORD";

    /**
     * Test-local BCrypt hash of the normalized password, generated at class load so no real or
     * hardcoded secret appears. The encoder is mocked, so its {@code matches(...)} result is stubbed
     * regardless of this value; generating it locally documents intent and honors constraint C-003.
     */
    private static final String STORED_HASH = new BCryptPasswordEncoder().encode(NORMALIZED_PASSWORD);

    /** ADMIN-typed USRSEC fixture reused by the success, wrong-password, and normalization tests. */
    private UserSecurity adminFixture;

    @BeforeEach
    void setUp() {
        adminFixture = userFixture(UserType.ADMIN);
    }

    /**
     * Builds a USRSEC fixture keyed by {@link #NORMALIZED_USER_ID} with the locally generated
     * {@link #STORED_HASH} and the supplied authorization type, using the entity's no-arg
     * constructor and setters.
     *
     * @param type the authorization type to store on the fixture
     * @return a populated {@link UserSecurity} fixture
     */
    private static UserSecurity userFixture(UserType type) {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(NORMALIZED_USER_ID);
        user.setSecUsrPwd(STORED_HASH);
        user.setSecUsrType(type);
        return user;
    }

    @Test
    @DisplayName("Valid admin credentials return the USRSEC principal; lookup precedes the password compare")
    void authenticateWithValidAdminCredentialsReturnsPrincipal() {
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.of(adminFixture));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, STORED_HASH)).thenReturn(true);

        UserSecurity result = authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD);

        assertThat(result).isSameAs(adminFixture);
        assertThat(result.getSecUsrType()).isEqualTo(UserType.ADMIN);
        verify(userSecurityRepository).findBySecUsrId(NORMALIZED_USER_ID);
        verify(passwordEncoder).matches(NORMALIZED_PASSWORD, STORED_HASH);
    }

    @Test
    @DisplayName("Valid standard-user credentials return the principal carrying USER type (type-agnostic success)")
    void authenticateWithValidUserCredentialsReturnsUserTypedPrincipal() {
        UserSecurity userPrincipal = userFixture(UserType.USER);
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.of(userPrincipal));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, STORED_HASH)).thenReturn(true);

        UserSecurity result = authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD);

        assertThat(result).isSameAs(userPrincipal);
        assertThat(result.getSecUsrType()).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("Blank or null user id is rejected with 'Please enter User ID ...' before any collaborator call")
    void authenticateWithBlankUserIdThrowsValidationException() {
        assertThatThrownBy(() -> authenticationService.authenticate("   ", RAW_PASSWORD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter User ID");
        assertThatThrownBy(() -> authenticationService.authenticate("", RAW_PASSWORD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter User ID");
        assertThatThrownBy(() -> authenticationService.authenticate(null, RAW_PASSWORD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter User ID");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Blank or null password is rejected with 'Please enter Password ...' before any collaborator call")
    void authenticateWithBlankPasswordThrowsValidationException() {
        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, "   "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter Password");
        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, ""))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter Password");
        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter Password");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Unknown user id throws RecordNotFoundException ('User not found ...') without consulting the encoder")
    void authenticateWithUnknownUserThrowsRecordNotFoundException() {
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found");

        verify(userSecurityRepository).findBySecUsrId(NORMALIZED_USER_ID);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("Wrong password (user found, BCrypt mismatch) throws ValidationException ('Wrong Password ...')")
    void authenticateWithWrongPasswordThrowsValidationException() {
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.of(adminFixture));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Wrong Password");

        verify(userSecurityRepository).findBySecUsrId(NORMALIZED_USER_ID);
        verify(passwordEncoder).matches(NORMALIZED_PASSWORD, STORED_HASH);
    }

    @Test
    @DisplayName("User id is trimmed and upper-cased and the password is upper-cased (not trimmed) before lookup and compare")
    void authenticateNormalizesInputsBeforeLookupAndCompare() {
        when(userSecurityRepository.findBySecUsrId(anyString())).thenReturn(Optional.of(adminFixture));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        authenticationService.authenticate("  admin  ", " password ");

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.captor();
        verify(userSecurityRepository).findBySecUsrId(userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo("ADMIN");

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.captor();
        verify(passwordEncoder).matches(passwordCaptor.capture(), eq(STORED_HASH));
        assertThat(passwordCaptor.getValue()).isEqualTo(" PASSWORD ");
    }
}
