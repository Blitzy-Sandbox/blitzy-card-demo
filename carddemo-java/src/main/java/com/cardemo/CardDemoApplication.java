package com.cardemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Spring Boot bootstrap entry point for the greenfield <strong>Java 25 LTS + Spring Boot 3.5.15</strong>
 * migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <p>This class carries the single {@code main(String[])} that launches the entire migrated
 * application. It is intentionally tiny but architecturally pivotal: its {@link SpringBootApplication}
 * annotation is a composed meta-annotation ({@code @Configuration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan}) and &mdash; because the class lives directly in the base package
 * {@code com.cardemo} &mdash; it becomes the <strong>root of component scanning</strong> for the whole
 * application. Every sibling subpackage ({@code config}, {@code model}, {@code repository},
 * {@code service}, {@code controller}, {@code batch}, {@code exception}, {@code observability}) is
 * auto-discovered from here, so no bean is orphaned. The single annotation therefore bootstraps the
 * layered architecture (Controller &rarr; Service &rarr; Repository &rarr; Entity) together with the
 * Spring Batch job suite and the Spring Security filter chain.</p>
 *
 * <h2>Migration provenance (AAP &sect;0.7.2)</h2>
 * <p>This is a brand-new file with <strong>no COBOL source equivalent</strong> &mdash; it is pure
 * Spring Boot scaffolding that has no analogue in the legacy estate. Behaviour for the rest of the
 * application is translated from the frozen AWS CardDemo COBOL baseline at commit SHA
 * {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this repository, and traceability to
 * the legacy baseline is by that commit SHA only.</p>
 *
 * <h2>Base-package decision D-006 (non-negotiable)</h2>
 * <p>The package declaration is exactly {@code com.cardemo} (and deliberately <em>not</em>
 * {@code com.carddemo}). This matches {@code <groupId>com.cardemo</groupId>} in {@code pom.xml}, the
 * {@code spring.application.name=carddemo} and {@code logging.level.com.cardemo} wiring in
 * {@code application.yml}, and the logger base in {@code logback-spring.xml}. The authoritative
 * blueprint is internally inconsistent (it uses both {@code com.cardemo} and {@code com.carddemo});
 * decision <strong>D-006</strong> resolves a single base package &mdash; {@code com.cardemo} &mdash;
 * applied uniformly across the target (recorded in {@code DECISION_LOG.md}). Because this class sits in
 * that package, the default {@code @ComponentScan} base package is correct as-is, so no
 * {@code scanBasePackages} override is declared; narrowing or changing it would orphan every
 * subpackage bean.</p>
 *
 * <h2>Auto-configuration policy (Spring Boot 3.x specifics)</h2>
 * <p>Per the Minimal Change Clause (AAP &sect;0.7.1) this class is the canonical minimal bootstrap
 * &mdash; {@link SpringBootApplication} plus {@code main()} &mdash; and delegates <em>all</em>
 * cross-cutting configuration to the component-scanned {@code config/} and {@code observability/}
 * classes. The following enabling annotations are <strong>deliberately omitted</strong>:</p>
 * <dl>
 *   <dt>{@code @EnableBatchProcessing} &mdash; intentionally ABSENT</dt>
 *   <dd>In Spring Boot 3.x, declaring {@code @EnableBatchProcessing} <strong>disables</strong> Boot's
 *       batch auto-configuration (the opposite of the intent). Spring Batch infrastructure is
 *       auto-configured by {@code spring-boot-starter-batch}; job-on-startup behaviour is driven by the
 *       {@code spring.batch.job.*} properties and the {@code config/BatchConfig} beans.</dd>
 *   <dt>{@code @EnableJpaRepositories} &mdash; intentionally ABSENT</dt>
 *   <dd>{@code spring-boot-starter-data-jpa} already auto-configures JPA repository scanning for the
 *       {@code com.cardemo} base package; declaring it manually would risk narrowing or duplicating the
 *       scan configuration.</dd>
 *   <dt>{@code @ComponentScan} / {@code scanBasePackages} override &mdash; intentionally ABSENT</dt>
 *   <dd>The meta-annotation's default scan (rooted at this class's package) already covers every
 *       subpackage; a narrower or different value would orphan beans.</dd>
 *   <dt>{@code @EnableScheduling} / {@code @EnableAsync} &mdash; intentionally ABSENT</dt>
 *   <dd>No speculative enablement (Minimal Change Clause). Spring Cloud AWS S3/SQS/SNS clients and the
 *       SQS listener-container infrastructure are auto-configured via the AWS starters and
 *       {@code config/AwsConfig} &mdash; no application {@code @SqsListener} is defined, because the sole
 *       online&rarr;batch report bridge is publish-only (see {@code DECISION_LOG.md} D-012); the Spring
 *       Security filter chain and the BCrypt {@code PasswordEncoder} are defined in
 *       {@code config/SecurityConfig}.</dd>
 *   <dt>{@code UserDetailsServiceAutoConfiguration} &mdash; explicitly EXCLUDED</dt>
 *   <dd>Because {@code config/SecurityConfig} deliberately defines no {@code UserDetailsService},
 *       {@code AuthenticationManager} or {@code AuthenticationProvider} bean (it stays decoupled from
 *       the repository/entity layers), Spring Boot would otherwise auto-configure an
 *       {@code InMemoryUserDetailsManager} with a random default user and print
 *       &quot;Using generated security password: &hellip;&quot; to the logs on <em>every</em> boot.
 *       That default user is never consulted &mdash; HTTP Basic and form login are disabled in
 *       {@code SecurityConfig} and authentication is the custom token flow in
 *       {@code service/auth/AuthenticationService} &mdash; so its only observable effect is a
 *       credential string written to the logs, a log-hygiene / production-readiness defect that
 *       conflicts with the &quot;no credentials in logs&quot; posture of AAP &sect;0.7.2. Excluding
 *       {@link UserDetailsServiceAutoConfiguration} suppresses the default user (and therefore the
 *       logged password) at the single most isolated point, honouring the Minimal Change Clause
 *       (AAP &sect;0.7.1); no behaviour changes because the in-memory user was already unused.
 *       (Resolves QA checkpoint FINAL&nbsp;5 finding&nbsp;F-1.)</dd>
 * </dl>
 *
 * <h2>Startup ordering</h2>
 * <p>The Flyway schema migrations run on application startup in strict order &mdash;
 * {@code V1__create_schema} &rarr; {@code V2__create_indexes} &rarr; {@code V3__seed_data} &mdash;
 * <em>before</em> any {@code @Service} or Spring Batch job executes. That ordering is enforced by
 * Spring Boot's Flyway auto-configuration reading {@code spring.flyway.*} from {@code application.yml};
 * it is <em>not</em> orchestrated by this class.</p>
 *
 * @see SpringApplication
 * @see SpringBootApplication
 * @see UserDetailsServiceAutoConfiguration
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
public class CardDemoApplication {

    /**
     * Launches the CardDemo Spring Boot application.
     *
     * <p>Delegates to {@link SpringApplication#run(Class, String...)} with this class as the primary
     * configuration source. The active profile ({@code local} or {@code test}) and every externalized
     * setting (datasource URL/credentials, AWS/LocalStack endpoints and region, Flyway, Actuator) are
     * supplied at runtime via environment variables and the {@code application*.yml} profile documents
     * &mdash; never hardcoded here (AAP &sect;0.7.2 secret policy).</p>
     *
     * @param args standard command-line arguments forwarded verbatim to {@link SpringApplication}
     *             (for example {@code --spring.profiles.active=local}); must not be {@code null}.
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
