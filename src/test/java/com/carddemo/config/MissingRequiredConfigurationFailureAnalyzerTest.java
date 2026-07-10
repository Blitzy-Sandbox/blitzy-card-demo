package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Unit test for {@link MissingRequiredConfigurationFailureAnalyzer} and the message contract of
 * {@link MissingRequiredConfigurationException} (Issue&nbsp;6).
 *
 * <p>Verifies that a missing-configuration failure is rendered into a clean, actionable Spring Boot
 * failure report: a description that enumerates every missing variable with its description, and an
 * action that steers the operator to set them all and points to the {@code local} profile /
 * {@code .env.example} for non-production runs.</p>
 */
@DisplayName("MissingRequiredConfigurationFailureAnalyzer — clean fail-fast report (Issue 6)")
class MissingRequiredConfigurationFailureAnalyzerTest {

    private final MissingRequiredConfigurationFailureAnalyzer analyzer =
            new MissingRequiredConfigurationFailureAnalyzer();

    private static Map<String, String> twoMissing() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("DB_URL", "PostgreSQL JDBC URL (binds spring.datasource.url)");
        m.put("JWT_SECRET", "JWT signing secret (binds carddemo.security.jwt.secret)");
        return m;
    }

    @Test
    @DisplayName("analysis description names the profile and lists every missing variable + description")
    void descriptionEnumeratesEveryMissingVariable() {
        final MissingRequiredConfigurationException cause =
                new MissingRequiredConfigurationException("prod", twoMissing());

        final FailureAnalysis analysis = analyzer.analyze(cause, cause);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("'prod' profile")
                .contains("DB_URL")
                .contains("PostgreSQL JDBC URL")
                .contains("JWT_SECRET")
                .contains("JWT signing secret");
        assertThat(analysis.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("prod action tells the operator to set all vars, load .env.example, and use local")
    void prodActionGivesConcreteRemediation() {
        final MissingRequiredConfigurationException cause =
                new MissingRequiredConfigurationException("prod", twoMissing());

        final FailureAnalysis analysis = analyzer.analyze(cause, cause);

        assertThat(analysis.getAction())
                .contains("environment variable")
                .contains("restart")
                .contains("local")
                .contains(".env.example");
    }

    @Test
    @DisplayName("local action gives remediation without the prod-only guidance")
    void localActionGivesConcreteRemediation() {
        final MissingRequiredConfigurationException cause =
                new MissingRequiredConfigurationException("local",
                        java.util.Map.of("JWT_SECRET",
                                "JWT signing secret (binds carddemo.security.jwt.secret)"));

        final FailureAnalysis analysis = analyzer.analyze(cause, cause);

        assertThat(analysis.getDescription()).contains("'local' profile").contains("JWT_SECRET");
        assertThat(analysis.getAction())
                .contains("restart")
                .contains(".env.example")
                // prod-only guidance (running prod locally) must NOT appear for a local failure.
                .doesNotContain("--spring.profiles.active=local");
    }

    @Test
    @DisplayName("exception preserves profile + missing entries as an unmodifiable, order-stable map")
    void exceptionExposesMissingEntries() {
        final MissingRequiredConfigurationException cause =
                new MissingRequiredConfigurationException("prod", twoMissing());

        assertThat(cause.getProfile()).isEqualTo("prod");
        assertThat(cause.getMissingProperties())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("DB_URL",
                                "PostgreSQL JDBC URL (binds spring.datasource.url)"),
                        org.assertj.core.data.MapEntry.entry("JWT_SECRET",
                                "JWT signing secret (binds carddemo.security.jwt.secret)"));
        assertThat(cause.getMissingPropertyNames()).containsExactly("DB_URL", "JWT_SECRET");
    }
}
