package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Fast, container-free unit test for {@link RequiredConfigurationEnvironmentPostProcessor}, the
 * per-profile fail-fast configuration guard added for Issues&nbsp;4 (local {@code JWT_SECRET}) and
 * &nbsp;6 (prod full externalized set).
 *
 * <p>The processor replaces the stock, cryptic, single-variable placeholder-resolution failure (which
 * surfaces buried inside an unrelated bean/auto-configuration condition and names only the first
 * missing variable) with a single up-front report that enumerates <em>every</em> missing required
 * environment variable for the active profile. These tests drive the processor directly against a
 * hand-built, hermetic {@link StandardEnvironment}, so they exercise the exact guard logic with no
 * Spring Boot bootstrap, database, Docker, or AWS involvement.</p>
 *
 * <h2>Contract pinned</h2>
 * <ul>
 *   <li><strong>prod</strong>: requires the full nine-variable externalized set; reports ALL missing
 *       at once.</li>
 *   <li><strong>local</strong>: requires only {@code JWT_SECRET}; the infrastructure coordinates have
 *       safe local defaults and are NOT required.</li>
 *   <li><strong>test / default</strong>: complete no-op — protecting the entire test suite (which runs
 *       under the {@code test} profile) and default boots.</li>
 *   <li>blank values are treated as missing; ordering runs immediately after config-data processing.</li>
 * </ul>
 */
@DisplayName("RequiredConfigurationEnvironmentPostProcessor — per-profile fail-fast guard (Issues 4 & 6)")
class RequiredConfigurationEnvironmentPostProcessorTest {

    /** Resolves the deferred log immediately to a real commons-logging {@link Log} for the test. */
    private static final DeferredLogFactory LOG_FACTORY = new DeferredLogFactory() {
        @Override
        public Log getLog(final Supplier<Log> destination) {
            return destination.get();
        }
    };

    private final RequiredConfigurationEnvironmentPostProcessor processor =
            new RequiredConfigurationEnvironmentPostProcessor(LOG_FACTORY);

    private final SpringApplication application = new SpringApplication();

    /** The nine bare {@code ${ENV_VAR}} coordinates the prod profile requires, all with valid values. */
    private static Map<String, Object> allProdRequiredPresent() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("DB_URL", "jdbc:postgresql://db-host:5432/carddemo");
        m.put("DB_USERNAME", "carddemo");
        m.put("DB_PASSWORD", "s3cr3t");
        m.put("AWS_REGION", "us-east-1");
        m.put("AWS_ENDPOINT", "http://localhost:4566");
        m.put("AWS_ACCESS_KEY_ID", "test");
        m.put("AWS_SECRET_ACCESS_KEY", "test");
        m.put("OTLP_ENDPOINT", "http://localhost:4317");
        m.put("JWT_SECRET", "0123456789012345678901234567890123456789");
        return m;
    }

    private static StandardEnvironment environmentWith(final String[] activeProfiles,
            final Map<String, Object> properties) {
        final StandardEnvironment env = new StandardEnvironment();
        // Make the environment hermetic. A StandardEnvironment reads the real OS environment and JVM
        // system properties by default; the Maven Surefire/Failsafe plugins inject JWT_SECRET (and the
        // CI host may export DB_*/AWS_* vars) into the test JVM, which would otherwise make a variable
        // this test intends to be "missing" appear present. Dropping both ambient sources leaves the
        // test-controlled MapPropertySource as the sole source of the required values.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        if (activeProfiles.length > 0) {
            env.setActiveProfiles(activeProfiles);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test-required-props", properties));
        return env;
    }

    // ---------------------------------------------------------------------------------------------
    // prod profile (Issue 6) — full externalized set
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("prod active + all required present => no exception")
    void prodWithAllRequiredPresentDoesNotThrow() {
        final StandardEnvironment env = environmentWith(new String[] {"prod"}, allProdRequiredPresent());

        assertThatCode(() -> processor.postProcessEnvironment(env, application))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod active + none present => throws (profile=prod) listing ALL nine at once")
    void prodWithNonePresentThrowsListingAllNine() {
        final StandardEnvironment env = environmentWith(new String[] {"prod"}, Map.of());

        assertThatExceptionOfType(MissingRequiredConfigurationException.class)
                .isThrownBy(() -> processor.postProcessEnvironment(env, application))
                .satisfies(ex -> {
                    assertThat(ex.getProfile()).isEqualTo("prod");
                    assertThat(ex.getMissingPropertyNames())
                            .containsExactly("DB_URL", "DB_USERNAME", "DB_PASSWORD", "AWS_REGION",
                                    "AWS_ENDPOINT", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
                                    "OTLP_ENDPOINT", "JWT_SECRET");
                    assertThat(ex.getMessage())
                            .contains("'prod' profile")
                            .contains("DB_URL")
                            .contains("JWT_SECRET")
                            .contains("OTLP_ENDPOINT")
                            .contains(".env.example");
                });
    }

    @Test
    @DisplayName("prod active + one absent => reports exactly that one, not the ones present")
    void prodWithSingleMissingReportsOnlyThatOne() {
        final Map<String, Object> props = allProdRequiredPresent();
        props.remove("JWT_SECRET");
        final StandardEnvironment env = environmentWith(new String[] {"prod"}, props);

        assertThatExceptionOfType(MissingRequiredConfigurationException.class)
                .isThrownBy(() -> processor.postProcessEnvironment(env, application))
                .satisfies(ex -> assertThat(ex.getMissingPropertyNames()).containsExactly("JWT_SECRET"));
    }

    @Test
    @DisplayName("prod active + blank value => treated as missing")
    void prodWithBlankValueTreatedAsMissing() {
        final Map<String, Object> props = allProdRequiredPresent();
        props.put("DB_PASSWORD", "   ");
        final StandardEnvironment env = environmentWith(new String[] {"prod"}, props);

        assertThatExceptionOfType(MissingRequiredConfigurationException.class)
                .isThrownBy(() -> processor.postProcessEnvironment(env, application))
                .satisfies(ex -> assertThat(ex.getMissingPropertyNames()).containsExactly("DB_PASSWORD"));
    }

    @Test
    @DisplayName("prod among several active profiles => still validated")
    void prodAmongMultipleActiveProfilesIsValidated() {
        final StandardEnvironment env = environmentWith(new String[] {"observability", "prod"}, Map.of());

        assertThatExceptionOfType(MissingRequiredConfigurationException.class)
                .isThrownBy(() -> processor.postProcessEnvironment(env, application))
                .satisfies(ex -> assertThat(ex.getProfile()).isEqualTo("prod"));
    }

    // ---------------------------------------------------------------------------------------------
    // local profile (Issue 4) — only JWT_SECRET is required
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("local active + JWT_SECRET present => no exception (no infra vars required)")
    void localWithJwtSecretPresentDoesNotThrow() {
        final StandardEnvironment env = environmentWith(new String[] {"local"},
                Map.of("JWT_SECRET", "0123456789012345678901234567890123456789"));

        assertThatCode(() -> processor.postProcessEnvironment(env, application))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local active + JWT_SECRET missing => throws (profile=local) listing exactly JWT_SECRET")
    void localWithoutJwtSecretThrows() {
        final StandardEnvironment env = environmentWith(new String[] {"local"}, Map.of());

        assertThatExceptionOfType(MissingRequiredConfigurationException.class)
                .isThrownBy(() -> processor.postProcessEnvironment(env, application))
                .satisfies(ex -> {
                    assertThat(ex.getProfile()).isEqualTo("local");
                    assertThat(ex.getMissingPropertyNames()).containsExactly("JWT_SECRET");
                });
    }

    @Test
    @DisplayName("local active + JWT_SECRET present but DB/AWS absent => still no exception")
    void localDoesNotRequireInfrastructureCoordinates() {
        // Only JWT_SECRET is supplied; DB_*/AWS_*/OTLP_* are deliberately absent. The local profile
        // supplies safe defaults for those, so their absence must NOT abort startup (Issue 4 scope).
        final StandardEnvironment env = environmentWith(new String[] {"local"},
                Map.of("JWT_SECRET", "0123456789012345678901234567890123456789"));

        assertThatCode(() -> processor.postProcessEnvironment(env, application))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------
    // test / default profiles — complete no-op (protects the whole test suite)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("test profile + nothing set => complete no-op (test suite unaffected)")
    void testProfileIsNoOp() {
        final StandardEnvironment env = environmentWith(new String[] {"test"}, Map.of());

        assertThatCode(() -> processor.postProcessEnvironment(env, application))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("default profile (none active) + nothing set => complete no-op")
    void defaultProfileIsNoOp() {
        final StandardEnvironment env = environmentWith(new String[] {}, Map.of());

        assertThatCode(() -> processor.postProcessEnvironment(env, application))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("runs one step after ConfigDataEnvironmentPostProcessor")
    void ordersAfterConfigData() {
        assertThat(processor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }
}
