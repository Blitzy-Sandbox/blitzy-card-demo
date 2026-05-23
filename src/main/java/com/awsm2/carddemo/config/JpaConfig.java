/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;

/**
 * Spring Data JPA configuration for CardDemo per AAP &sect;0.6.4
 * (<em>"AWS Secrets Manager Dynamic Rotation Without Restart"</em>) and
 * AAP &sect;0.4.1 (<em>JPA repository scanning</em>).
 *
 * <h2>Architectural Role</h2>
 *
 * <p>This is the single foundational {@code @Configuration} class for the
 * entire JPA stack of the CardDemo application. It performs three
 * orthogonal responsibilities, each minimised to the smallest possible
 * surface so that profile overlays in {@code application.yml} and AWS
 * Secrets Manager retain authoritative control over runtime behaviour
 * (AAP &sect;0.7.1 Minimal Change Clause):</p>
 *
 * <ol>
 *   <li><b>Activates Spring Data JPA repository scanning</b> via
 *       {@link EnableJpaRepositories @EnableJpaRepositories} rooted at
 *       {@code com.awsm2.carddemo.repository}. This single declaration
 *       discovers all 11 {@code JpaRepository} interfaces
 *       ({@code AccountRepository}, {@code CardRepository},
 *       {@code CustomerRepository}, {@code CardCrossReferenceRepository},
 *       {@code TransactionRepository},
 *       {@code TransactionCategoryBalanceRepository},
 *       {@code DisclosureGroupRepository},
 *       {@code TransactionTypeRepository},
 *       {@code TransactionCategoryRepository},
 *       {@code UserSecurityRepository},
 *       {@code DailyTransactionRepository}) that collectively replace
 *       every VSAM file SELECT/ASSIGN clause in the source COBOL programs
 *       per AAP &sect;0.6.2 (VSAM &rarr; RDS migration).</li>
 *   <li><b>Declares a {@link RefreshScope @RefreshScope}-aware
 *       {@link DataSourceProperties} bean</b>. The properties are bound
 *       from {@code spring.datasource.*} via
 *       {@link ConfigurationProperties @ConfigurationProperties}; when
 *       AWS Secrets Manager rotates the RDS master credentials and
 *       triggers a Spring {@code RefreshEvent} (via the SNS &rarr; SQS
 *       poller in {@link SecretsManagerConfig}), this bean is destroyed
 *       and re-instantiated with the rotated property values.</li>
 *   <li><b>Declares the primary application
 *       {@link DataSource} bean</b> as a {@link RefreshScope @RefreshScope}
 *       HikariCP connection pool. Hikari-specific tuning properties
 *       (maximum-pool-size, minimum-idle, connection-timeout,
 *       <strong>max-lifetime</strong>) are bound from
 *       {@code spring.datasource.hikari.*} via the second
 *       {@code @ConfigurationProperties} on the bean.</li>
 * </ol>
 *
 * <h2>Replaces (AAP &sect;0.6.2 &amp; &sect;0.6.4)</h2>
 *
 * <p>This {@code @Configuration} class replaces the following mainframe
 * constructs:</p>
 * <ul>
 *   <li>VSAM file {@code SELECT}/{@code ASSIGN} clauses in COBOL
 *       Environment Divisions (e.g.,
 *       {@code SELECT TRANSACT-FILE ASSIGN TO TRANSACT} in
 *       {@code app/cbl/CBTRN02C.cbl}) &mdash; now JPA repositories.</li>
 *   <li>VSAM cluster file allocation in JCL DD statements (e.g., the
 *       {@code TRANSACT DD} in {@code app/jcl/POSTTRAN.jcl}) &mdash;
 *       now RDS PostgreSQL Multi-AZ accessed via HikariCP connection
 *       pool.</li>
 *   <li>CICS File Control Table (FCT) entries that mapped logical file
 *       names to VSAM clusters at CICS region startup &mdash; now Spring
 *       Boot {@code DataSource} auto-wiring at application context
 *       refresh time.</li>
 *   <li>Plaintext credentials stored in mainframe {@code PARM} datasets
 *       and the {@code USRSEC} VSAM cluster &mdash; now AWS Secrets
 *       Manager managed secrets with automatic refresh propagation via
 *       {@code @RefreshScope}.</li>
 *   <li>The mainframe operational pattern of recycling the entire CICS
 *       region (operator-issued {@code CEMT PERFORM SHUT IMMEDIATE} +
 *       restart) to pick up rotated credentials &mdash; now in-place
 *       {@code ContextRefresher.refresh()} with zero ECS task restarts
 *       and zero dropped connections beyond the HikariCP
 *       {@code maxLifetime} pool drain.</li>
 * </ul>
 *
 * <h2>Dynamic Credential Rotation Without Restart (AAP &sect;0.6.4)</h2>
 *
 * <p>Both factory methods are annotated {@link RefreshScope @RefreshScope}
 * so that when AWS Secrets Manager rotates the RDS credentials and the
 * rotation Lambda publishes a notification to the dedicated SNS topic,
 * the following propagation chain executes <em>without</em> restarting
 * the Spring Boot container:</p>
 *
 * <ol>
 *   <li>The Secrets Manager rotation Lambda (Terraform-managed in
 *       {@code infrastructure/terraform/secrets.tf}) generates new
 *       credentials and updates the secret version.</li>
 *   <li>The Lambda publishes a rotation notification to the SNS topic
 *       subscribed by the application's SQS queue.</li>
 *   <li>{@link SecretsManagerConfig} polls the SQS queue on its
 *       configured cadence and invokes
 *       {@code ContextRefresher.refresh()}.</li>
 *   <li>{@code ContextRefresher.refresh()} re-binds the Spring
 *       {@code Environment} from Secrets Manager (already auto-imported
 *       via {@code spring.config.import: aws-secretsmanager:...}).</li>
 *   <li>All {@code @RefreshScope} beans &mdash; including the two beans
 *       defined here &mdash; are <strong>destroyed</strong>. They are
 *       <strong>re-instantiated lazily</strong> on next access with the
 *       newly rotated credentials picked up via
 *       {@link DataSourceProperties}.</li>
 *   <li>The previous HikariCP pool's existing connections continue
 *       serving in-flight transactions until they either complete and
 *       return to the (now-discarded) pool, or until they exceed the
 *       configured {@code maxLifetime} and are evicted.</li>
 * </ol>
 *
 * <h2>Critical: HikariCP {@code max-lifetime} &lt; Rotation Interval</h2>
 *
 * <p>Per {@code application-prod.yml} (and {@code application-dev.yml}),
 * HikariCP {@code max-lifetime} is set to <strong>1 800 000 ms = 30
 * minutes</strong>. This value MUST be less than the AWS Secrets Manager
 * rotation interval (typically 60-1 440 minutes in production) so that
 * any pool connection opened with the previous credentials is recycled
 * out of the pool well before the rotated credentials become the only
 * valid ones. Setting {@code max-lifetime} <em>greater</em> than the
 * rotation interval would cause in-pool connections to fail with
 * "password authentication failed" the moment the previous credentials
 * are deactivated, defeating the zero-restart rotation goal.</p>
 *
 * <h2>Schema Validation Discipline (AAP &sect;0.6.2)</h2>
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto: validate} is set in every
 * active profile (see {@code application.yml}). This means:</p>
 * <ul>
 *   <li>At startup, Hibernate validates every {@code @Entity} column
 *       against an existing column with a compatible JDBC type in the
 *       target database.</li>
 *   <li>Hibernate <em>never</em> auto-generates or modifies DDL &mdash;
 *       schema changes happen <em>exclusively</em> via Flyway migrations
 *       under {@code src/main/resources/db/migration/V*.sql}.</li>
 *   <li>A schema mismatch (renamed entity field, missing column, type
 *       drift) fails application-context startup loudly with a
 *       {@code HibernateException}, preventing silent data-corruption
 *       deployments.</li>
 * </ul>
 *
 * <h2>Transaction Management</h2>
 *
 * <p>Spring Boot 3.x auto-configures a
 * {@code JpaTransactionManager} from the {@link Primary @Primary}
 * {@link DataSource} bean defined here. The
 * {@code @EnableTransactionManagement} annotation lives on
 * {@code CardDemoApplication} (the main entry point) so declarative
 * {@code @Transactional} support is active application-wide; it is
 * <em>not</em> declared here, in keeping with the minimal-change
 * discipline of placing each Spring infrastructure capability in
 * exactly one place (AAP &sect;0.7.3).</p>
 *
 * <h2>EntityManagerFactory and PlatformTransactionManager</h2>
 *
 * <p>{@code EntityManagerFactory} and {@code PlatformTransactionManager}
 * are <strong>not</strong> declared in this class. Spring Boot
 * auto-configuration (via {@code HibernateJpaAutoConfiguration} and
 * {@code JpaBaseConfiguration}) creates them automatically from the
 * {@code @Primary} {@link DataSource} declared here. Per AAP
 * &sect;0.7.1 (Minimal Change Clause), no manual override is performed
 * &mdash; the defaults are correct and aligned with the AAP target
 * stack.</p>
 *
 * <h2>Entity Scanning</h2>
 *
 * <p>{@code @EntityScan} is not declared here. Spring Boot defaults to
 * scanning the package of {@code CardDemoApplication}
 * ({@code com.awsm2.carddemo}) and all its subpackages, which includes
 * {@code com.awsm2.carddemo.domain} where every {@code @Entity} lives.
 * No override is required.</p>
 *
 * <h2>Property Sources (Source of Truth)</h2>
 *
 * <p>All configurable values are sourced from {@code application.yml}
 * and its profile overlays &mdash; never hardcoded here (AAP
 * &sect;0.7.1). The principal property keys read by this configuration
 * (and the auto-configured beans downstream of it) are:</p>
 * <ul>
 *   <li>{@code spring.datasource.url} &mdash; JDBC URL (from Secrets
 *       Manager via {@code spring.config.import} in
 *       {@code application-prod.yml}).</li>
 *   <li>{@code spring.datasource.username} / {@code .password} &mdash;
 *       sourced from Secrets Manager.</li>
 *   <li>{@code spring.datasource.driver-class-name} &mdash;
 *       {@code org.postgresql.Driver} (or the H2 driver for the
 *       {@code local} / {@code test} profiles).</li>
 *   <li>{@code spring.datasource.hikari.maximum-pool-size} /
 *       {@code .minimum-idle} / {@code .connection-timeout} /
 *       {@code .max-lifetime} / {@code .idle-timeout} /
 *       {@code .pool-name} &mdash; HikariCP tuning per profile.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} = {@code validate}
 *       (constant across profiles).</li>
 *   <li>{@code spring.jpa.open-in-view} = {@code false} (OSIV is an
 *       anti-pattern; the persistence context closes at the service
 *       boundary).</li>
 *   <li>{@code spring.jpa.properties.hibernate.dialect} =
 *       {@code org.hibernate.dialect.PostgreSQLDialect}.</li>
 *   <li>{@code spring.jpa.properties.hibernate.jdbc.time_zone} =
 *       {@code UTC} (all {@code LocalDateTime} / {@code Instant} fields
 *       are normalised to UTC at the JDBC boundary, preventing
 *       cross-timezone bugs in financial calculations per AAP
 *       &sect;0.6.1).</li>
 *   <li>{@code spring.jpa.properties.hibernate.jdbc.batch_size} = 25
 *       (batched inserts/updates for {@code saveAll(...)} calls).</li>
 *   <li>{@code spring.cloud.refresh.refreshable} = {@code true} in
 *       {@code dev} / {@code prod} profiles (gate that allows
 *       {@code @RefreshScope} beans to actually be refreshable).</li>
 * </ul>
 *
 * <h2>Operational Constraints Honoured by This File</h2>
 * <ul>
 *   <li><b>No business logic</b> (AAP &sect;0.7.1, Minimal Change
 *       Clause) &mdash; this class is pure infrastructure wiring; no
 *       transactional, financial, or domain logic appears
 *       anywhere.</li>
 *   <li><b>No hardcoded credentials</b> (AAP &sect;0.7.1) &mdash; every
 *       property value resolves at runtime from {@code application.yml}
 *       and the AWS Secrets Manager / Parameter Store imports declared
 *       in profile overlays.</li>
 *   <li><b>AWS SDK v2 only</b> (AAP &sect;0.5.1) &mdash; no
 *       {@code com.amazonaws.*} (v1) imports. (No AWS SDK is used
 *       directly here &mdash; Secrets Manager integration is via
 *       declarative {@code spring.config.import} loading.)</li>
 *   <li><b>JDBC {@code javax.sql} package retained</b> &mdash;
 *       {@link DataSource} is the standard JDBC API and is part of the
 *       JDK, NOT a Jakarta EE artifact. Only Java EE / Jakarta EE
 *       packages ({@code javax.servlet}, {@code javax.persistence},
 *       {@code javax.validation}) were migrated to {@code jakarta.*}
 *       in Spring Boot 3.x; the JDBC API ({@code java.sql} /
 *       {@code javax.sql}) remains where it has always been.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.config.SecretsManagerConfig
 * @see <a href="https://docs.spring.io/spring-data/jpa/reference/jpa/getting-started.html">
 *      Spring Data JPA Reference &mdash; Getting Started</a>
 * @see <a href="https://docs.awspring.io/spring-cloud-aws/docs/3.2.1/reference/html/index.html#secrets-manager-integration">
 *      Spring Cloud AWS &mdash; Secrets Manager Integration</a>
 * @see <a href="https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html#refresh-scope">
 *      Spring Cloud Commons &mdash; Refresh Scope</a>
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.awsm2.carddemo.repository")
public class JpaConfig {

    /**
     * Binds {@code spring.datasource.*} properties (URL, username,
     * password, driver class name, etc.) into a refresh-scoped
     * {@link DataSourceProperties} holder.
     *
     * <p>The bean is annotated:</p>
     * <ul>
     *   <li>{@link Bean @Bean} &mdash; registers the factory method
     *       output as a Spring-managed bean.</li>
     *   <li>{@link Primary @Primary} &mdash; promotes this bean as the
     *       default {@link DataSourceProperties} so that any
     *       Spring Boot auto-configuration looking for one (notably
     *       {@code DataSourceAutoConfiguration}) discovers this bean
     *       before any potential duplicate.</li>
     *   <li>{@link RefreshScope @RefreshScope} &mdash; the bean is
     *       destroyed and re-instantiated whenever
     *       {@code ContextRefresher.refresh()} is invoked by
     *       {@link SecretsManagerConfig} in response to an AWS Secrets
     *       Manager rotation event (AAP &sect;0.6.4). Re-instantiation
     *       re-reads the (now-rotated) {@code spring.datasource.*}
     *       property values from the Spring {@code Environment}, which
     *       has itself been refreshed from Secrets Manager.</li>
     *   <li>{@link ConfigurationProperties @ConfigurationProperties}
     *       with prefix {@code spring.datasource} &mdash; activates
     *       Spring Boot's standard property-binding machinery so that
     *       {@code spring.datasource.url}, {@code .username},
     *       {@code .password}, and {@code .driver-class-name} are
     *       bound onto the returned {@link DataSourceProperties}
     *       instance.</li>
     * </ul>
     *
     * <p><b>Replaces:</b> the COBOL pattern of opening a VSAM cluster
     * with explicit DD-name parameters in JCL (e.g., {@code //TRANSACT
     * DD DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS,DISP=SHR}) &mdash; now
     * declarative Spring Boot property binding sourced from AWS
     * Secrets Manager.</p>
     *
     * @return a {@link DataSourceProperties} whose JDBC URL, username,
     *         password, and driver-class-name are resolved from
     *         {@code spring.datasource.*} after any active Secrets
     *         Manager imports are applied
     */
    @Bean
    @Primary
    @RefreshScope
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        // Replaces: PARM-supplied JDBC credentials and the mainframe
        // RACF authentication of batch jobs against USRSEC, plus the
        // CICS region's FCT-mapped DD-name to VSAM-cluster bindings.
        // The empty DataSourceProperties instance is fully initialised
        // by Spring Boot's @ConfigurationProperties binder before the
        // bean becomes available for injection.
        return new DataSourceProperties();
    }

    /**
     * Builds the primary application {@link DataSource} as a HikariCP
     * connection pool, sourced from the refresh-scoped
     * {@link DataSourceProperties} bean above.
     *
     * <p>The bean is annotated:</p>
     * <ul>
     *   <li>{@link Bean @Bean} &mdash; registers the factory method
     *       output as a Spring-managed bean.</li>
     *   <li>{@link Primary @Primary} &mdash; ensures that Spring Boot's
     *       auto-configured {@code EntityManagerFactory},
     *       {@code JpaTransactionManager}, Flyway, Spring Batch
     *       infrastructure, and any other {@link DataSource} consumer
     *       discovers this bean as the default. Without
     *       {@code @Primary}, any auto-configured fallback
     *       {@link DataSource} (e.g., from a misconfigured H2 starter)
     *       could win the autowire resolution and break the application
     *       in production.</li>
     *   <li>{@link RefreshScope @RefreshScope} &mdash; the bean (and
     *       the HikariCP pool it wraps) is destroyed and re-instantiated
     *       on every {@code ContextRefresher.refresh()} invocation,
     *       which is triggered by the SQS rotation poller in
     *       {@link SecretsManagerConfig} after AWS Secrets Manager
     *       rotates the RDS credentials (AAP &sect;0.6.4). New
     *       transactions check out connections from the new pool with
     *       the rotated credentials; existing connections in the old
     *       pool continue serving their current transactions until they
     *       either complete or exceed the configured
     *       {@code maxLifetime}.</li>
     *   <li>{@link ConfigurationProperties @ConfigurationProperties}
     *       with prefix {@code spring.datasource.hikari} &mdash; binds
     *       HikariCP-specific tuning properties onto the constructed
     *       {@link HikariDataSource}. The key properties bound are:
     *       {@code maximum-pool-size}, {@code minimum-idle},
     *       {@code connection-timeout},
     *       <strong>{@code max-lifetime}</strong>,
     *       {@code idle-timeout}, {@code keepalive-time},
     *       {@code pool-name}, {@code auto-commit}, and
     *       {@code connection-test-query}.</li>
     * </ul>
     *
     * <p><b>CRITICAL constraint (AAP &sect;0.6.4):</b>
     * {@code spring.datasource.hikari.max-lifetime} <em>must</em> be
     * less than the AWS Secrets Manager rotation interval. The default
     * configured value in {@code application-prod.yml} is
     * {@code 1 800 000 ms = 30 minutes}, well under the typical
     * production rotation window of 60-1 440 minutes. Reducing
     * {@code max-lifetime} below 30 minutes is acceptable and would
     * narrow the propagation lag for rotated credentials; <em>raising
     * it above</em> the rotation interval would cause pooled
     * connections with stale credentials to fail authentication once
     * the previous credentials are deactivated.</p>
     *
     * <p><b>Replaces:</b> the CICS File Control Table (FCT) entry that
     * opened a VSAM cluster at CICS region startup. The Java target
     * replaces the per-region "open at startup" pattern with a managed
     * connection pool that:</p>
     * <ul>
     *   <li>Pre-allocates {@code minimum-idle} connections at startup
     *       to amortise connection-establishment cost.</li>
     *   <li>Grows up to {@code maximum-pool-size} connections under
     *       load.</li>
     *   <li>Retires connections older than {@code max-lifetime} or idle
     *       longer than {@code idle-timeout} to bound resource
     *       consumption.</li>
     *   <li>Tests connections with {@code connection-test-query} (e.g.,
     *       {@code SELECT 1}) before checkout to catch dead connections
     *       proactively.</li>
     * </ul>
     *
     * @param properties the refresh-scoped {@link DataSourceProperties}
     *                   produced by {@link #dataSourceProperties()};
     *                   provides the JDBC URL, username, password, and
     *                   driver class for the underlying HikariCP pool
     * @return a fully-configured {@link HikariDataSource} (returned as
     *         the {@link DataSource} interface so downstream consumers
     *         depend on the standard JDBC API rather than the concrete
     *         HikariCP implementation)
     */
    @Bean
    @Primary
    @RefreshScope
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        // Replaces: CICS FCT (File Control Table) entry for VSAM cluster
        // open at CICS region startup. The HikariCP pool is now the
        // backing store for every @Repository in
        // com.awsm2.carddemo.repository — i.e., it replaces the per-CICS-
        // region open-handle map for every VSAM cluster previously
        // accessed by the 28 COBOL programs.
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
