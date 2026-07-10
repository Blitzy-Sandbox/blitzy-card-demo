package com.carddemo.config;

import java.util.Map;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a {@link MissingRequiredConfigurationException} into a clean, actionable Spring Boot
 * "APPLICATION FAILED TO START" report (a <em>Description</em> plus an <em>Action</em>) instead of a
 * raw stack trace (Issue&nbsp;6 remediation).
 *
 * <p>Registered via {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}. When
 * {@link RequiredProductionPropertiesEnvironmentPostProcessor} aborts a {@code prod} boot because
 * required environment variables are missing, Spring Boot's diagnostics infrastructure routes the
 * exception here and prints the returned analysis, giving the operator the complete list of missing
 * variables and a concrete next step at a glance — no scrolling through an unrelated
 * auto-configuration condition trace.
 */
public class MissingRequiredConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<MissingRequiredConfigurationException> {

    /**
     * Builds the failure analysis from the missing-property map carried by the exception.
     *
     * @param rootFailure the original failure (unused; the typed cause carries all detail)
     * @param cause       the typed missing-configuration exception
     * @return a {@link FailureAnalysis} with a human-readable description and remediation action
     */
    @Override
    protected FailureAnalysis analyze(final Throwable rootFailure,
            final MissingRequiredConfigurationException cause) {
        final Map<String, String> missing = cause.getMissingProperties();
        final String profile = cause.getProfile();

        final StringBuilder description = new StringBuilder(384);
        description.append("The application was started with the '")
                   .append(profile)
                   .append("' profile, but ")
                   .append(missing.size())
                   .append(missing.size() == 1 ? " required configuration value is"
                           : " required configuration values are")
                   .append(" missing. This profile provides NO fallback for the value(s) below")
                   .append(" (AAP \u00a70.8.1), so each MUST be supplied via an environment variable")
                   .append(" (or a secrets vault / .env file):")
                   .append(System.lineSeparator());
        for (final Map.Entry<String, String> e : missing.entrySet()) {
            description.append(System.lineSeparator())
                       .append("    - ")
                       .append(e.getKey())
                       .append(": ")
                       .append(e.getValue());
        }

        final StringBuilder action = new StringBuilder(384);
        action.append("Set every environment variable listed above (all of them, not one at a time) ")
              .append("and restart the application. The repository ships a secret-free .env.example ")
              .append("with safe development defaults: copy it to .env and load it (see ")
              .append("docs/onboarding.md), e.g. `cp .env.example .env && export $(grep -v '^#' .env ")
              .append("| xargs)`.");
        if (RequiredConfigurationEnvironmentPostProcessor.PROD_PROFILE.equals(profile)) {
            action.append(" In production, supply these from your environment or secrets vault rather ")
                  .append("than a committed file; for local development use --spring.profiles.active=")
                  .append("local, which defaults the infrastructure coordinates against LocalStack ")
                  .append("and a local PostgreSQL.");
        }

        return new FailureAnalysis(description.toString(), action.toString(), cause);
    }
}
