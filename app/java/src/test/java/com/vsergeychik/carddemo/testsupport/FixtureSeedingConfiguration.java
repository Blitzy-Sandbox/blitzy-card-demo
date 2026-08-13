package com.vsergeychik.carddemo.testsupport;

import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Opt-in wiring that binds the {@code test} profile's fixture inventory and seeds it.
 *
 * <h2>Why it is opted into rather than scanned</h2>
 * <p>This class is in {@code com.vsergeychik.carddemo.testsupport}, which
 * {@code CardDemoApplication}'s component scan deliberately does not cover, and it is compiled to
 * {@code target/test-classes}, which the shipped artifact does not contain. Both are load-bearing:
 * {@code CardDemoApplicationTest} asserts that no bean in the started context comes from the test tree
 * and that no bean comes from this package, because a bean that exists in a developer's run and not in
 * production is a graph nobody has asserted on. So nothing here joins a context unless a caller names
 * it - a test through {@code withUserConfiguration(...)} or {@code @Import}, or
 * {@link FixtureSeededApplication} for a hand-started JVM.
 *
 * <p>It is equally deliberate that this does <em>not</em> seed every test-profile context. The suite's
 * 27,900-odd assertions run against data each case declares and owns for its own duration - that is what
 * lets one case seed an expired card, another an empty dataset and another a short record - and pushing
 * a shared 900-record baseline underneath all of them would change outcomes that have nothing to do
 * with the fixtures. What this exists for is the case the review found: a JVM started on the profile by
 * hand had the twenty-seven dataset bindings and no relations behind them.
 *
 * <h2>What it contributes</h2>
 * <ul>
 *   <li>{@link FixtureInventory}, bound strictly from {@code carddemo.test.fixtures} so a misspelled
 *       key fails the bind;</li>
 *   <li>{@link FixtureSeeder}, over the profile's own backend and the injected dataset code page;</li>
 *   <li>an {@link org.springframework.boot.ApplicationRunner} that seeds once the context is up, so a
 *       hand-started JVM answers a request against populated relations rather than absent ones.</li>
 * </ul>
 *
 * <p>The whole configuration is {@code @Profile("test")}: seeding is only ever meaningful against the
 * throwaway in-memory backend that profile declares, and a deployment's datasets are the deployment's
 * to provide.
 */
@Configuration(proxyBeanMethods = false)
@Profile(FixtureSeedingConfiguration.TEST_PROFILE)
@EnableConfigurationProperties(FixtureInventory.class)
public class FixtureSeedingConfiguration {

    /** The one profile under which seeding is meaningful. */
    public static final String TEST_PROFILE = "test";

    /**
     * The seeder over the profile's backend.
     *
     * @param jdbcTemplate   the profile's in-memory backend
     * @param catalogue      the shipped dataset catalogue
     * @param inventory      the strictly bound fixture inventory
     * @param resourceLoader resolves the declared fixture resources
     * @param datasetCharset the code page the fixtures are stored in - {@code US-ASCII} under this
     *                       profile, taken from the one charset bean the whole data layer injects
     * @param transactionManager the module's single transaction manager, which the seeding boundary is
     *                           opened on - the pool disables auto-commit, so seeding with no boundary
     *                           would insert every record and have it rolled back
     * @return the seeder
     */
    @Bean
    public FixtureSeeder carddemoFixtureSeeder(JdbcTemplate jdbcTemplate, DatasetBindings catalogue,
            FixtureInventory inventory, ResourceLoader resourceLoader,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            PlatformTransactionManager transactionManager) {
        return new FixtureSeeder(jdbcTemplate, catalogue, inventory, resourceLoader, datasetCharset,
                transactionManager);
    }

    /**
     * Seeds the declared inventory once the context is up.
     *
     * <p>An {@code ApplicationRunner} rather than an {@code @PostConstruct} or a
     * {@code ContextRefreshedEvent} listener, for one reason: the datasource, the catalogue and the
     * charset bean must all be fully initialised before a statement is issued, and a runner is the first
     * point at which the container guarantees that. It runs before the web server begins accepting
     * requests, so a hand-started JVM has no window in which a request could reach an unseeded relation.
     *
     * @param seeder the seeder to run
     * @return the runner
     */
    @Bean
    public org.springframework.boot.ApplicationRunner carddemoFixtureSeedingRunner(FixtureSeeder seeder) {
        return arguments -> seeder.seedAll();
    }
}
