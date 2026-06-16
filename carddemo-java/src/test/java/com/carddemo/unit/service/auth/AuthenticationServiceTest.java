package com.carddemo.unit.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link com.carddemo.service.auth.AuthenticationService} (COBOL COSGN00C
 * parity; source SHA 27d6c6f). Pure JVM Mockito test; rationale lives in DECISION_LOG.md.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    /** Raw (lower-case) entered user id; its upper-cased form proves COBOL FUNCTION UPPER-CASE. */
    private static final String RAW_USER_ID = "admin";

    /** Raw (lower-case) entered password; its upper-cased form proves COBOL FUNCTION UPPER-CASE. */
    private static final String RAW_PASSWORD = "password";

    /** Expected normalized (trimmed + upper-cased) user id used as the USRSEC key. */
    private static final String NORMALIZED_USER_ID = "ADMIN";

    /** Expected normalized (upper-cased) password passed to the encoder. */
    private static final String NORMALIZED_PASSWORD = "PASSWORD";

    /**
     * Test-local BCrypt hash of the normalized password. Generated locally (never a hardcoded
     * secret); because {@code passwordEncoder} is mocked, the hash value only has to be a stable,
     * non-null stored credential for the fixture.
     */
    private static final String STORED_HASH = new BCryptPasswordEncoder().encode(NORMALIZED_PASSWORD);

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthenticationService authenticationService;

    /**
     * Builds a USRSEC fixture (no-arg constructor + setters) keyed by the normalized user id and
     * carrying the test-local BCrypt hash and the requested authorization type.
     *
     * @param type the user authorization type to surface on the principal
     * @return a populated {@link UserSecurity} fixture
     */
    private static UserSecurity userFixture(UserType type) {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(NORMALIZED_USER_ID);
        user.setSecUsrFname("TEST");
        user.setSecUsrLname("USER");
        user.setSecUsrPwd(STORED_HASH);
        user.setSecUsrType(type);
        return user;
    }

    // ----- success: lookup then BCrypt compare -> returns the authenticated principal -----

    @ParameterizedTest
    @EnumSource(UserType.class)
    @DisplayName("valid credentials -> returns the authenticated USRSEC principal (COSGN00C WHEN 0 match)")
    void returnsPrincipalWhenCredentialsValid(UserType type) {
        UserSecurity fixture = userFixture(type);
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.of(fixture));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, fixture.getSecUsrPwd())).thenReturn(true);

        UserSecurity result = authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD);

        assertThat(result).isSameAs(fixture);
        assertThat(result.getSecUsrType()).isEqualTo(type);
        // Ordering parity: the record is read first, then the password is verified.
        verify(userSecurityRepository).findBySecUsrId(NORMALIZED_USER_ID);
        verify(passwordEncoder).matches(NORMALIZED_PASSWORD, fixture.getSecUsrPwd());
    }

    // ----- blank input edits (EVALUATE TRUE), short-circuit before any collaborator call -----

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank or null User ID -> ValidationException, no lookup or compare (COSGN00C 'Please enter User ID')")
    void rejectsBlankUserId(String blankUserId) {
        assertThatThrownBy(() -> authenticationService.authenticate(blankUserId, RAW_PASSWORD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter User ID");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank or null Password -> ValidationException, no lookup or compare (COSGN00C 'Please enter Password')")
    void rejectsBlankPassword(String blankPassword) {
        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, blankPassword))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter Password");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    // ----- user not found (COBOL WHEN 13): encoder must not be consulted -----

    @Test
    @DisplayName("unknown User ID -> RecordNotFoundException; password encoder never consulted (COSGN00C WHEN 13)")
    void throwsNotFoundWhenUserMissing() {
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found");

        verify(userSecurityRepository).findBySecUsrId(NORMALIZED_USER_ID);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    // ----- wrong password (COBOL WHEN 0, SEC-USR-PWD mismatch) -----

    @Test
    @DisplayName("wrong password on an existing user -> ValidationException (COSGN00C 'Wrong Password')")
    void throwsWrongPasswordWhenMismatch() {
        UserSecurity fixture = userFixture(UserType.USER);
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.of(fixture));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, fixture.getSecUsrPwd())).thenReturn(false);

        assertThatThrownBy(() -> authenticationService.authenticate(RAW_USER_ID, RAW_PASSWORD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Wrong Password");

        verify(userSecurityRepository).findBySecUsrId(NORMALIZED_USER_ID);
        verify(passwordEncoder).matches(NORMALIZED_PASSWORD, fixture.getSecUsrPwd());
    }

    // ----- normalization parity: trim + dual FUNCTION UPPER-CASE before lookup/compare -----

    @Test
    @DisplayName("trims and upper-cases the User ID and upper-cases the Password before lookup/compare (FUNCTION UPPER-CASE)")
    void normalizesCredentialsBeforeLookupAndCompare() {
        UserSecurity fixture = userFixture(UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId(NORMALIZED_USER_ID)).thenReturn(Optional.of(fixture));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, fixture.getSecUsrPwd())).thenReturn(true);

        authenticationService.authenticate("  admin  ", "password");

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(userSecurityRepository).findBySecUsrId(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(NORMALIZED_USER_ID);

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(passwordCaptor.capture(), eq(fixture.getSecUsrPwd()));
        assertThat(passwordCaptor.getValue()).isEqualTo(NORMALIZED_PASSWORD);
    }
}
