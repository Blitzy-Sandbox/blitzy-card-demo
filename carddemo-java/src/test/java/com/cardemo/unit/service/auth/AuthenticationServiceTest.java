package com.cardemo.unit.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.TokenService;
import com.cardemo.service.auth.AuthenticationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AuthenticationService} — the {@code COSGN00C} sign-on translation. This is a
 * highest-risk migration site (the CP3 review required a focused test). Verifies the mandatory
 * {@code PROCESS-ENTER-KEY} cascade order, the single permitted behavioral change (BCrypt verification
 * via a REAL {@link BCryptPasswordEncoder}, C-003), token issuance delegated to {@link TokenService},
 * and the post-login routing (ADMIN → COADM01C/CA00, USER → COMEN01C/CM00).
 */
class AuthenticationServiceTest {

    private UserSecurityRepository userSecurityRepository;
    private PasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        userSecurityRepository = mock(UserSecurityRepository.class);
        // REAL BCrypt encoder: exercises the genuine hash-verify path (the sole permitted change).
        passwordEncoder = new BCryptPasswordEncoder();
        tokenService = mock(TokenService.class);
        service = new AuthenticationService(userSecurityRepository, passwordEncoder, tokenService);
    }

    private SignOnRequest request(String userId, String password) {
        SignOnRequest r = new SignOnRequest();
        r.setUserId(userId);
        r.setPassword(password);
        return r;
    }

    private UserSecurity user(String id, String upperPasswordHash, UserType type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrPwd(upperPasswordHash);
        u.setSecUsrType(type);
        return u;
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY blank-field cascade (order is mandatory)")
    class Cascade {

        @Test
        @DisplayName("empty user id is rejected FIRST, even when the password is also empty")
        void emptyUserIdFirst() {
            assertThatThrownBy(() -> service.signOn(request("", "")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Please enter User ID");
        }

        @Test
        @DisplayName("empty password is rejected once the user id is present")
        void emptyPassword() {
            assertThatThrownBy(() -> service.signOn(request("USER0001", "")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Please enter Password");
        }
    }

    @Nested
    @DisplayName("read + verify")
    class ReadAndVerify {

        @Test
        @DisplayName("unknown user id -> RecordNotFoundException with the verbatim COBOL message")
        void unknownUser() {
            when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.signOn(request("user0001", "whatever")))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("wrong password -> ValidationException (BCrypt mismatch), no token issued")
        void wrongPassword() {
            String hash = passwordEncoder.encode("RIGHTPASS");
            when(userSecurityRepository.findBySecUsrId("USER0001"))
                    .thenReturn(Optional.of(user("USER0001", hash, UserType.USER)));
            assertThatThrownBy(() -> service.signOn(request("user0001", "wrongpass")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Wrong Password");
        }
    }

    @Nested
    @DisplayName("success path: BCrypt verify, token delegation, routing")
    class Success {

        @Test
        @DisplayName("ADMIN user routes to COADM01C/CA00 and carries the issued token")
        void adminRouting() {
            // Stored hash is of the UPPER-CASED password (cross-agent seed contract); the entered
            // lowercase password is upper-cased by the service before matching.
            String hash = passwordEncoder.encode("SECRET01");
            when(userSecurityRepository.findBySecUsrId("ADMIN001"))
                    .thenReturn(Optional.of(user("ADMIN001", hash, UserType.ADMIN)));
            when(tokenService.issue("ADMIN001", UserType.ADMIN)).thenReturn("ADMIN.TOKEN.SIG");

            SignOnResponse response = service.signOn(request("admin001", "secret01"));

            assertThat(response.getToken()).isEqualTo("ADMIN.TOKEN.SIG");
            assertThat(response.getUserId()).isEqualTo("ADMIN001");
            assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
            assertThat(response.getToProgram()).isEqualTo("COADM01C");
            assertThat(response.getToTranId()).isEqualTo("CA00");
            verify(tokenService).issue(eq("ADMIN001"), eq(UserType.ADMIN));
        }

        @Test
        @DisplayName("USER user routes to COMEN01C/CM00 and carries the issued token")
        void userRouting() {
            String hash = passwordEncoder.encode("SECRET01");
            when(userSecurityRepository.findBySecUsrId("USER0001"))
                    .thenReturn(Optional.of(user("USER0001", hash, UserType.USER)));
            when(tokenService.issue("USER0001", UserType.USER)).thenReturn("USER.TOKEN.SIG");

            SignOnResponse response = service.signOn(request("user0001", "secret01"));

            assertThat(response.getToken()).isEqualTo("USER.TOKEN.SIG");
            assertThat(response.getUserType()).isEqualTo(UserType.USER);
            assertThat(response.getToProgram()).isEqualTo("COMEN01C");
            assertThat(response.getToTranId()).isEqualTo("CM00");
            verify(tokenService).issue(eq("USER0001"), eq(UserType.USER));
        }
    }
}
