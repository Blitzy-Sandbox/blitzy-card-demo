package com.carddemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.observability.AuthenticationMetricsAspect;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.auth.AuthenticationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AuthenticationMetricsAspect}.
 *
 * <p>Verifies the Observability requirement (AAP &sect;0.7.1) that {@value MetricsConfig#AUTH_ATTEMPTS}
 * is incremented for every sign-on attempt, tagged {@code outcome=success} on a normal return and
 * {@code outcome=failure} on any thrown exception &mdash; without altering the
 * {@link AuthenticationService} two-collaborator contract. The aspect advice is exercised both
 * directly and through an AspectJ proxy wrapping a real {@link AuthenticationService} (with mocked
 * repository and encoder), proving the pointcut matches {@code authenticate(..)} and that thrown
 * exceptions still propagate unchanged.</p>
 */
@DisplayName("AuthenticationMetricsAspect - carddemo.auth.attempts success/failure instrumentation")
@ExtendWith(MockitoExtension.class)
class AuthenticationMetricsAspectTest {

    private static final String STORED_HASH = "$2a$10$storedHashValuePlaceholderForBcrypt00000000000000000000";

    @Mock
    private UserSecurityRepository userSecurityRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private SimpleMeterRegistry registry;
    private AuthenticationMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new AuthenticationMetricsAspect(registry);
    }

    private double successCount() {
        // get-or-create (returns 0.0 when never incremented) avoids a null Search result
        return MetricsConfig.authAttempts(registry, MetricsConfig.OUTCOME_SUCCESS).count();
    }

    private double failureCount() {
        return MetricsConfig.authAttempts(registry, MetricsConfig.OUTCOME_FAILURE).count();
    }

    private AuthenticationService proxiedService() {
        AuthenticationService target =
                new AuthenticationService(userSecurityRepository, passwordEncoder);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static UserSecurity admin() {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId("ADMIN");
        u.setSecUsrPwd(STORED_HASH);
        return u;
    }

    // ---- Direct advice behavior ----

    @Test
    @DisplayName("the success advice increments only the success-tagged counter")
    void successAdviceIncrementsSuccess() {
        aspect.recordSuccessfulAttempt();
        assertThat(successCount()).isEqualTo(1.0);
        assertThat(failureCount()).isZero();
    }

    @Test
    @DisplayName("the failure advice increments only the failure-tagged counter")
    void failureAdviceIncrementsFailure() {
        aspect.recordFailedAttempt();
        assertThat(failureCount()).isEqualTo(1.0);
        assertThat(successCount()).isZero();
    }

    // ---- Proxy-wired behavior (pointcut + propagation) ----

    @Test
    @DisplayName("a successful authentication through the proxy increments the success counter")
    void successfulAuthenticationIncrementsSuccessCounter() {
        when(userSecurityRepository.findBySecUsrId("ADMIN")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("PASS", STORED_HASH)).thenReturn(true);

        UserSecurity principal = proxiedService().authenticate("admin", "pass");

        assertThat(principal.getSecUsrId()).isEqualTo("ADMIN");
        assertThat(successCount()).isEqualTo(1.0);
        assertThat(failureCount()).isZero();
    }

    @Test
    @DisplayName("a blank user id increments the failure counter and still throws ValidationException")
    void blankUserIdIncrementsFailureCounter() {
        assertThatThrownBy(() -> proxiedService().authenticate("", "pass"))
                .isInstanceOf(ValidationException.class);
        assertThat(failureCount()).isEqualTo(1.0);
        assertThat(successCount()).isZero();
    }

    @Test
    @DisplayName("an unknown user increments the failure counter and still throws RecordNotFoundException")
    void userNotFoundIncrementsFailureCounter() {
        when(userSecurityRepository.findBySecUsrId("ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> proxiedService().authenticate("admin", "pass"))
                .isInstanceOf(RecordNotFoundException.class);
        assertThat(failureCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a wrong password increments the failure counter and still throws ValidationException")
    void wrongPasswordIncrementsFailureCounter() {
        when(userSecurityRepository.findBySecUsrId("ADMIN")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("PASS", STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> proxiedService().authenticate("admin", "pass"))
                .isInstanceOf(ValidationException.class);
        assertThat(failureCount()).isEqualTo(1.0);
    }
}
