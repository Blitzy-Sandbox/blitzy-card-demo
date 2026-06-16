package com.carddemo.unit.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.auth.AuthenticationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AuthenticationService} focused on the CP3 Observability finding: every
 * authentication outcome must increment {@code carddemo.auth.attempts} with a bounded
 * {@code outcome} tag, without ever tagging or logging plaintext credentials.
 *
 * <p>Traceability (REFERENCE-ONLY; source commit {@code 27d6c6f}): the three outcomes mirror the
 * {@code COSGN00C} {@code READ-USER-SEC-FILE} response handling &mdash; {@code WHEN 13} (not found),
 * the failed password compare (wrong password), and {@code WHEN 0} with a matching password
 * (success). The repository and {@link PasswordEncoder} are mocked; the {@code MeterRegistry} is a
 * real {@link SimpleMeterRegistry} so the recorded counter values can be asserted directly.</p>
 */
@DisplayName("AuthenticationService - carddemo.auth.attempts outcome metrics")
class AuthenticationServiceTest {

    private static final String STORED_HASH = "$2a$10$storedhashvalueplaceholderxxxxxxxxxxxxxxxxxxxxxxxxx";

    private UserSecurityRepository userSecurityRepository;
    private PasswordEncoder passwordEncoder;
    private SimpleMeterRegistry registry;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        userSecurityRepository = mock(UserSecurityRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        registry = new SimpleMeterRegistry();
        service = new AuthenticationService(userSecurityRepository, passwordEncoder, registry);
    }

    @Test
    @DisplayName("success increments outcome=success")
    void success_incrementsSuccessOutcome() {
        UserSecurity user = user("ADMIN", STORED_HASH, UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("ADMIN")).thenReturn(Optional.of(user));
        // The service upper-cases the entered password before comparing.
        when(passwordEncoder.matches("PASSWORD", STORED_HASH)).thenReturn(true);

        UserSecurity result = service.authenticate("admin", "password");

        assertThat(result).isSameAs(user);
        assertThat(outcomeCount(MetricsConfig.OUTCOME_SUCCESS)).isEqualTo(1.0);
        assertThat(outcomeCount(MetricsConfig.OUTCOME_NOT_FOUND)).isZero();
        assertThat(outcomeCount(MetricsConfig.OUTCOME_WRONG_PASSWORD)).isZero();
    }

    @Test
    @DisplayName("unknown user increments outcome=not_found")
    void notFound_incrementsNotFoundOutcome() {
        when(userSecurityRepository.findBySecUsrId("GHOST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("ghost", "password"))
                .isInstanceOf(RecordNotFoundException.class);

        assertThat(outcomeCount(MetricsConfig.OUTCOME_NOT_FOUND)).isEqualTo(1.0);
        assertThat(outcomeCount(MetricsConfig.OUTCOME_SUCCESS)).isZero();
    }

    @Test
    @DisplayName("wrong password increments outcome=wrong_password")
    void wrongPassword_incrementsWrongPasswordOutcome() {
        UserSecurity user = user("ADMIN", STORED_HASH, UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("ADMIN")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WRONG", STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.authenticate("admin", "wrong"))
                .isInstanceOf(ValidationException.class);

        assertThat(outcomeCount(MetricsConfig.OUTCOME_WRONG_PASSWORD)).isEqualTo(1.0);
        assertThat(outcomeCount(MetricsConfig.OUTCOME_SUCCESS)).isZero();
    }

    private double outcomeCount(String outcome) {
        return registry.counter(MetricsConfig.AUTH_ATTEMPTS, MetricsConfig.TAG_OUTCOME, outcome)
                .count();
    }

    private static UserSecurity user(String id, String passwordHash, UserType type) {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(id);
        user.setSecUsrPwd(passwordHash);
        user.setSecUsrType(type);
        return user;
    }
}
