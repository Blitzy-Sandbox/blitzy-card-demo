package com.carddemo.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Records the {@value MetricsConfig#AUTH_ATTEMPTS} counter for every sign-on attempt, tagged by
 * outcome ({@link MetricsConfig#OUTCOME_SUCCESS} / {@link MetricsConfig#OUTCOME_FAILURE}), as
 * required by the Observability rule (AAP &sect;0.7.1: {@code carddemo.auth.attempts}, outcome-tagged).
 *
 * <p><strong>Why an aspect.</strong> {@code AuthenticationService} (the Java translation of
 * {@code COSGN00C}) has a fixed two-collaborator contract &mdash; {@code UserSecurityRepository} and
 * {@code PasswordEncoder} &mdash; and must not take a {@code MeterRegistry} dependency. This aspect is
 * the approved metrics collaborator: it wraps {@code AuthenticationService.authenticate(..)} from the
 * outside, leaving the service's constructor and authentication logic untouched. A normal return is a
 * {@code success}; any thrown exception (blank input, user-not-found, wrong password, or an
 * infrastructure failure) is a {@code failure}. The counter tags are a closed, low-cardinality set, so
 * no unbounded label growth is introduced.</p>
 *
 * <p>The decision to instrument via AOP (rather than altering the service contract) is recorded in
 * {@code DECISION_LOG.md}; no rationale is embedded beyond this structural description.</p>
 */
@Aspect
@Component
public class AuthenticationMetricsAspect {

    /** Pointcut: any invocation of {@code AuthenticationService.authenticate(..)}. */
    private static final String AUTHENTICATE_POINTCUT =
            "execution(* com.carddemo.service.auth.AuthenticationService.authenticate(..))";

    /** Registry used to resolve and increment the outcome-tagged authentication counter. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the aspect.
     *
     * @param meterRegistry the Micrometer registry that backs {@value MetricsConfig#AUTH_ATTEMPTS}
     */
    public AuthenticationMetricsAspect(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments {@value MetricsConfig#AUTH_ATTEMPTS} with {@code outcome=success} when an
     * authentication call returns normally.
     */
    @AfterReturning(AUTHENTICATE_POINTCUT)
    public void recordSuccessfulAttempt() {
        MetricsConfig.authAttempts(meterRegistry, MetricsConfig.OUTCOME_SUCCESS).increment();
    }

    /**
     * Increments {@value MetricsConfig#AUTH_ATTEMPTS} with {@code outcome=failure} when an
     * authentication call throws. The exception is not consumed; advice ordering lets it propagate
     * unchanged to the caller.
     */
    @AfterThrowing(AUTHENTICATE_POINTCUT)
    public void recordFailedAttempt() {
        MetricsConfig.authAttempts(meterRegistry, MetricsConfig.OUTCOME_FAILURE).increment();
    }
}
