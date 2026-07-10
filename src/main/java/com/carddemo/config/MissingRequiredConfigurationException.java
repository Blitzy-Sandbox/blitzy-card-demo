package com.carddemo.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bootstrap-time failure raised when the {@code prod} Spring profile is active but one or more
 * <strong>required</strong> configuration values (supplied only as bare {@code ${ENV_VAR}}
 * placeholders with no default in {@code application-prod.yml}) are absent from the environment.
 *
 * <p><strong>Why this exists (Issue&nbsp;6 remediation).</strong> The production profile deliberately
 * externalizes every credential and connection coordinate as a bare environment placeholder with no
 * fallback, so a missing value <em>must</em> abort startup rather than silently substitute an
 * insecure default (AAP&nbsp;&sect;0.8.1, &sect;0.8.7). Left to Spring's stock placeholder machinery,
 * that abort surfaces as a cryptic, single-variable failure buried inside an unrelated
 * auto-configuration condition (for example
 * {@code IllegalStateException: Error processing condition on ...otlpTracingConnectionDetails} caused
 * by {@code PlaceholderResolutionException: Could not resolve placeholder 'OTLP_ENDPOINT'}). An
 * operator fixing that one variable then hits the next missing one on the following boot — a
 * whack-a-mole loop. This exception is thrown eagerly by
 * {@link RequiredProductionPropertiesEnvironmentPostProcessor} <em>before</em> any datasource,
 * tracing, or AWS wiring is touched, and it enumerates <em>every</em> missing variable at once so the
 * whole configuration gap is fixed in a single pass.
 *
 * <p>This is a pure data carrier: it holds the ordered map of missing environment-variable names to
 * their human-readable descriptions. Companion {@link MissingRequiredConfigurationFailureAnalyzer}
 * renders it into a clean "APPLICATION FAILED TO START" report (description + action) instead of a
 * stack trace; the {@linkplain #getMessage() message} is itself fully self-contained so the failure
 * is legible even if the analyzer is not engaged.
 *
 * <p>It is intentionally a plain {@link RuntimeException} and <em>not</em> a
 * {@code com.carddemo.exception.CardDemoException}: the latter models errors at the REST boundary
 * (it carries an HTTP status and an optional COBOL {@code FILE STATUS} code), whereas this condition
 * happens while the environment is being prepared — the application context never starts and nothing
 * is ever served over HTTP.
 */
public class MissingRequiredConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the active Spring profile whose required configuration was found incomplete. */
    private final String profile;

    /** Ordered {@code ENV_VAR -> human-readable description} map of every value found to be missing. */
    private final transient Map<String, String> missingProperties;

    /**
     * Creates the exception from the active profile and the ordered map of missing environment
     * variables.
     *
     * @param profile           the active Spring profile that requires the configuration (e.g.
     *                          {@code prod} or {@code local}); must not be {@code null}
     * @param missingProperties ordered map of missing {@code ENV_VAR} names to their descriptions;
     *                          must not be {@code null} or empty
     */
    public MissingRequiredConfigurationException(final String profile,
            final Map<String, String> missingProperties) {
        super(buildMessage(profile, missingProperties));
        this.profile = profile;
        this.missingProperties = new LinkedHashMap<>(missingProperties);
    }

    /**
     * Returns the active Spring profile whose required configuration was incomplete.
     *
     * @return the profile name (e.g. {@code prod} or {@code local})
     */
    public String getProfile() {
        return profile;
    }

    /**
     * Returns the ordered, unmodifiable map of missing {@code ENV_VAR} names to their human-readable
     * descriptions, for programmatic inspection (e.g. by the failure analyzer or tests).
     *
     * @return an unmodifiable view of the missing-property map
     */
    public Map<String, String> getMissingProperties() {
        return Collections.unmodifiableMap(missingProperties);
    }

    /**
     * Returns just the missing {@code ENV_VAR} names, in declaration order.
     *
     * @return an unmodifiable list of the missing environment-variable names
     */
    public List<String> getMissingPropertyNames() {
        return Collections.unmodifiableList(new ArrayList<>(missingProperties.keySet()));
    }

    /**
     * Builds a fully self-contained, multi-line, human-readable message enumerating every missing
     * required variable with its description and concrete remediation guidance.
     *
     * @param profile the active Spring profile requiring the configuration
     * @param missing ordered map of missing {@code ENV_VAR} names to descriptions
     * @return the formatted message
     */
    private static String buildMessage(final String profile, final Map<String, String> missing) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("Cannot start CardDemo with the '")
          .append(profile)
          .append("' profile: ")
          .append(missing.size())
          .append(missing.size() == 1 ? " required configuration value is" : " required configuration values are")
          .append(" missing from the environment. This profile intentionally provides NO fallback for")
          .append(" the value(s) below, so each MUST be supplied via an environment variable (or a")
          .append(" secrets vault / .env file) before startup:")
          .append(System.lineSeparator());
        for (final Map.Entry<String, String> e : missing.entrySet()) {
            sb.append("  - ")
              .append(e.getKey())
              .append(": ")
              .append(e.getValue())
              .append(System.lineSeparator());
        }
        sb.append("Set every variable listed above (all at once) and restart. The repository ships a")
          .append(" secret-free .env.example with safe development defaults — copy it to .env and load")
          .append(" it (see docs/onboarding.md).");
        return sb.toString();
    }
}
