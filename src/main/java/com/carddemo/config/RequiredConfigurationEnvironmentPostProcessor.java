package com.carddemo.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fail-fast guard that validates the presence of the <strong>required</strong> configuration values
 * a given Spring profile has no safe default for, the instant that profile is active and
 * <em>before</em> any datasource, tracing, security, or AWS wiring is evaluated (Issues&nbsp;4 and
 * &nbsp;6 remediation).
 *
 * <p><strong>Problem it solves.</strong> Both the production and local-acceptance profiles bind
 * certain values to bare {@code ${ENV_VAR}} placeholders with no default, on purpose, so a missing
 * value aborts startup instead of silently using an insecure fallback (AAP&nbsp;&sect;0.8.1,
 * &sect;0.8.7). Left to Spring's stock placeholder machinery, that abort is produced lazily by
 * whichever bean or auto-configuration first dereferences a placeholder — for {@code prod} it
 * typically surfaces as {@code IllegalStateException: Error processing condition on
 * ...otlpTracingConnectionDetails} wrapping {@code Could not resolve placeholder 'OTLP_ENDPOINT'},
 * and for {@code local} as a {@code jwtService} {@code BeanCreationException} wrapping
 * {@code Could not resolve placeholder 'JWT_SECRET'}. Those messages (a) name only the <em>first</em>
 * missing variable, (b) bury it in an unrelated bean/condition stack trace, and (c) force the operator
 * into a fix-one-restart-hit-the-next loop.
 *
 * <p><strong>Per-profile required sets.</strong>
 * <ul>
 *   <li><strong>prod</strong> — the full set of externalized coordinates: {@code DB_URL},
 *       {@code DB_USERNAME}, {@code DB_PASSWORD}, {@code AWS_REGION}, {@code AWS_ENDPOINT},
 *       {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}, {@code OTLP_ENDPOINT}, and
 *       {@code JWT_SECRET} (the bare {@code ${ENV_VAR}} placeholders in {@code application-prod.yml}).</li>
 *   <li><strong>local</strong> — only {@code JWT_SECRET}. The local profile intentionally supplies
 *       safe development defaults for the infrastructure coordinates (DB host/port/name/credentials,
 *       AWS/LocalStack endpoint and dummy credentials, OTLP endpoint), so those are <em>not</em>
 *       required; the signing secret is the one value with no default (Issue&nbsp;4).</li>
 * </ul>
 * The {@code ${ENV_VAR:default}} operational knobs (pool sizes, timeouts, sample rate, log levels,
 * logical resource names) are never validated here because their absence is not an error.
 *
 * <p><strong>Behaviour.</strong> Registered via {@code META-INF/spring.factories} and ordered to run
 * immediately after {@link ConfigDataEnvironmentPostProcessor} (so active profiles and all property
 * sources are populated), this processor:
 * <ul>
 *   <li>is a complete no-op for every profile other than {@code prod}/{@code local} — the
 *       {@code test} profile (used by the entire unit/integration suite) and the default profile are
 *       untouched;</li>
 *   <li>looks up each required value by its raw environment-variable name via
 *       {@link ConfigurableEnvironment#getProperty(String)}, which returns {@code null} for an absent
 *       variable <em>without</em> triggering placeholder resolution (so it never throws the cryptic
 *       error itself);</li>
 *   <li>aggregates <em>all</em> missing (absent or blank) variables and, if any are missing, throws a
 *       single {@link MissingRequiredConfigurationException} that names the profile and enumerates
 *       every missing variable with a description and remediation guidance.</li>
 * </ul>
 * The thrown exception is rendered as a clean "APPLICATION FAILED TO START" report by
 * {@link MissingRequiredConfigurationFailureAnalyzer}.
 */
public class RequiredConfigurationEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /** The production profile — requires the full externalized-coordinate set. */
    static final String PROD_PROFILE = "prod";

    /** The local-acceptance profile — requires only the JWT signing secret. */
    static final String LOCAL_PROFILE = "local";

    /**
     * Ordered {@code ENV_VAR -> description} map required by the {@code prod} profile. Insertion order
     * is preserved (datasource, then AWS, then tracing, then security) so the failure report reads
     * coherently. These are exactly the bare, default-less {@code ${ENV_VAR}} placeholders in
     * {@code application-prod.yml}.
     */
    private static final Map<String, String> REQUIRED_PROD_PROPERTIES = buildProdRequiredProperties();

    /**
     * Ordered {@code ENV_VAR -> description} map required by the {@code local} profile — only the JWT
     * signing secret, the single local value with no safe default (Issue&nbsp;4).
     */
    private static final Map<String, String> REQUIRED_LOCAL_PROPERTIES =
            Collections.singletonMap("JWT_SECRET", jwtSecretDescription());

    private final Log logger;

    /**
     * Constructed reflectively by Spring Boot's environment-post-processor loader, which supplies a
     * {@link DeferredLogFactory} so log output produced this early is buffered and replayed once the
     * logging system is fully initialized.
     *
     * @param logFactory the deferred log factory provided by the bootstrap infrastructure
     */
    public RequiredConfigurationEnvironmentPostProcessor(final DeferredLogFactory logFactory) {
        this.logger = logFactory.getLog(getClass());
    }

    private static String jwtSecretDescription() {
        return "JWT signing secret, at least 32 bytes for HS256 (binds carddemo.security.jwt.secret)";
    }

    /**
     * Builds the immutable, ordered prod required-property map.
     *
     * @return the required {@code ENV_VAR -> description} map for prod
     */
    private static Map<String, String> buildProdRequiredProperties() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("DB_URL",
                "PostgreSQL JDBC URL, e.g. jdbc:postgresql://db-host:5432/carddemo "
                        + "(binds spring.datasource.url)");
        m.put("DB_USERNAME", "PostgreSQL username (binds spring.datasource.username)");
        m.put("DB_PASSWORD", "PostgreSQL password (binds spring.datasource.password)");
        m.put("AWS_REGION", "AWS region, e.g. us-east-1 (binds spring.cloud.aws.region.static)");
        m.put("AWS_ENDPOINT",
                "AWS S3/SQS/SNS service endpoint URL (binds spring.cloud.aws.endpoint)");
        m.put("AWS_ACCESS_KEY_ID",
                "AWS access key id (binds spring.cloud.aws.credentials.access-key)");
        m.put("AWS_SECRET_ACCESS_KEY",
                "AWS secret access key (binds spring.cloud.aws.credentials.secret-key)");
        m.put("OTLP_ENDPOINT",
                "OpenTelemetry OTLP collector endpoint for trace export "
                        + "(binds management.otlp.tracing.endpoint)");
        m.put("JWT_SECRET", jwtSecretDescription());
        return Collections.unmodifiableMap(m);
    }

    /**
     * Validates the required configuration for the active profile. Runs only when {@code prod} or
     * {@code local} is active; every other profile (including {@code test}) is a no-op.
     *
     * @param environment the fully-populated environment (profiles and property sources resolved)
     * @param application the current Spring application
     * @throws MissingRequiredConfigurationException if the active profile requires configuration that
     *                                               is absent or blank
     */
    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
            final SpringApplication application) {
        // Resolve the applicable required set from the active profile. prod takes precedence over
        // local if (unusually) both are active, because prod is the stricter contract.
        final String profile;
        final Map<String, String> required;
        if (environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            profile = PROD_PROFILE;
            required = REQUIRED_PROD_PROPERTIES;
        } else if (environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE))) {
            profile = LOCAL_PROFILE;
            required = REQUIRED_LOCAL_PROPERTIES;
        } else {
            // test / default / any other profile: nothing to validate.
            return;
        }

        final Map<String, String> missing = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : required.entrySet()) {
            // Look up by the RAW env-var name: returns null when absent without triggering the
            // strict placeholder resolution that would otherwise throw the cryptic downstream error.
            final String value = environment.getProperty(entry.getKey());
            if (!StringUtils.hasText(value)) {
                missing.put(entry.getKey(), entry.getValue());
            }
        }

        if (!missing.isEmpty()) {
            final MissingRequiredConfigurationException failure =
                    new MissingRequiredConfigurationException(profile, missing);
            // Emit the full, human-readable list once via the deferred logger too, so the cause is
            // captured in the application log even where the FailureAnalyzer console report is not
            // retained.
            logger.error(failure.getMessage());
            throw failure;
        }

        logger.info("Configuration for the '" + profile + "' profile validated: all "
                + required.size() + " required environment variable(s) present.");
    }

    /**
     * Runs immediately after {@link ConfigDataEnvironmentPostProcessor} so the active profiles and
     * every property source (including {@code application-{profile}.yml}) are already loaded, but
     * still well before the application context — and therefore any datasource/AWS/tracing/security
     * wiring — is created.
     *
     * @return the order value, one step after config-data processing
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
