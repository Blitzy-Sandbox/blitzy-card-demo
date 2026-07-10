package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration contract test for the production profile ({@code application-prod.yml}) proving the
 * migration's <strong>zero-live-AWS / LocalStack-only</strong> guarantee (AAP&nbsp;G7,
 * &sect;0.3.2, &sect;0.7.7) at the boundary where it is easiest to regress: the Spring Cloud AWS
 * endpoint and credential bindings.
 *
 * <p><strong>What is under test.</strong> The prod profile binds the AWS endpoint and credentials
 * to <em>bare</em> environment placeholders with <strong>no default</strong>
 * ({@code spring.cloud.aws.endpoint: ${AWS_ENDPOINT}}, {@code credentials.access-key:
 * ${AWS_ACCESS_KEY_ID}}, {@code credentials.secret-key: ${AWS_SECRET_ACCESS_KEY}}). An earlier
 * revision used empty defaults ({@code ${AWS_ENDPOINT:}}), which silently resolved to an empty
 * override and let the AWS SDK fall back to the {@code DefaultCredentialsProvider} chain and a live
 * AWS endpoint if ambient credentials/region existed — a direct violation of the zero-live-AWS
 * requirement. These tests lock in the fail-fast behaviour so that regression cannot reintroduce an
 * empty default.</p>
 *
 * <p><strong>How it is tested.</strong> The real {@code application.yml} + {@code application-prod.yml}
 * are loaded through Spring Boot's config-data machinery (via
 * {@link ConfigDataApplicationContextInitializer}) with the {@code prod} profile active, exactly as
 * a production boot would. Crucially, {@link PropertyPlaceholderAutoConfiguration} is registered so
 * the context uses the <em>same strict</em> {@code PropertySourcesPlaceholderConfigurer}
 * ({@code ignoreUnresolvablePlaceholders=false}) that a real {@code SpringApplication.run(...)} boot
 * installs — a bare {@link ApplicationContextRunner} would otherwise fall back to the framework's
 * <em>lenient</em> default value resolver ({@code Environment#resolvePlaceholders}), which leaves an
 * unresolved {@code ${AWS_ENDPOINT}} as a literal instead of failing, masking the very regression
 * under test. A minimal holder bean injects only {@code ${spring.cloud.aws.endpoint}}, so context
 * refresh forces strict resolution of that single placeholder and nothing else — the other prod
 * placeholders (DB, JWT, region, credentials) remain unresolved literals that no bean consumes,
 * keeping the test fast, offline, and free of unrelated failures. No live AWS, Docker, LocalStack,
 * or database is touched.</p>
 */
@DisplayName("application-prod.yml — AWS endpoint/credentials fail-fast (no live-AWS fallback, AAP G7)")
class ProdProfileConfigTest {

    /**
     * Loads the base + prod config data with the {@code prod} profile active, installs the strict
     * {@link PropertyPlaceholderAutoConfiguration} (so unresolved placeholders fail fast exactly as
     * in a real boot), and registers only the {@link AwsEndpointHolder}, whose single {@code @Value}
     * forces resolution of {@code spring.cloud.aws.endpoint} during refresh.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=prod")
            .withUserConfiguration(AwsEndpointHolder.class);

    @Test
    @DisplayName("prod FAILS FAST when AWS_ENDPOINT is unset (no empty default => no live-AWS fallback)")
    void prodFailsFastWhenAwsEndpointMissing() {
        // AWS_ENDPOINT is deliberately NOT provided. Because the prod profile binds
        // spring.cloud.aws.endpoint to a bare ${AWS_ENDPOINT} with no default, context refresh must
        // fail with an unresolved-placeholder error naming AWS_ENDPOINT — never silently resolve to
        // an empty override that would permit a live-AWS endpoint. The placeholder name surfaces in
        // the root PlaceholderResolutionException (wrapped by a BeanCreationException), so the whole
        // failure stack trace is asserted rather than only the top-level message.
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("Could not resolve placeholder")
                .hasStackTraceContaining("AWS_ENDPOINT"));
    }

    @Test
    @DisplayName("prod resolves spring.cloud.aws.endpoint from AWS_ENDPOINT when supplied (LocalStack)")
    void prodResolvesAwsEndpointWhenSupplied() {
        // When the approved LocalStack endpoint is supplied, the placeholder resolves and the
        // context starts cleanly — proving the binding works and only the empty default was removed.
        runner.withPropertyValues("AWS_ENDPOINT=http://localhost:4566")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AwsEndpointHolder.class).endpoint)
                            .isEqualTo("http://localhost:4566");
                });
    }

    /**
     * Minimal holder whose single {@code @Value} forces resolution of the AWS endpoint placeholder
     * (and nothing else) during context refresh.
     */
    @Configuration(proxyBeanMethods = false)
    static class AwsEndpointHolder {

        /** The resolved framework AWS endpoint override ({@code spring.cloud.aws.endpoint}). */
        @Value("${spring.cloud.aws.endpoint}")
        String endpoint;
    }
}
