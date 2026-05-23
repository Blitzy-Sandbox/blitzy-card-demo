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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;

/**
 * JPA / Spring Data / HikariCP DataSource configuration with AWS Secrets
 * Manager rotation support.
 *
 * <p>Per AAP &sect;0.6.4 ("AWS Secrets Manager Dynamic Rotation Without
 * Restart") and AAP &sect;0.7.1 ("Use {@code @Transactional} with proper
 * isolation levels for transactional integrity"), this configuration class
 * provides the foundation for the entire JPA stack:</p>
 *
 * <ul>
 *   <li><b>{@link EnableJpaRepositories}</b> &mdash; activates Spring Data
 *       JPA repository scanning rooted at
 *       {@code com.awsm2.carddemo.repository}. This replaces COBOL
 *       VSAM/CICS file access in 11 source clusters (Account, Card,
 *       Customer, CardCrossReference, Transaction,
 *       TransactionCategoryBalance, DisclosureGroup, TransactionType,
 *       TransactionCategory, UserSecurity, DailyTransaction) per
 *       AAP &sect;0.6.2.</li>
 *   <li><b>{@link RefreshScope @RefreshScope} {@link DataSourceProperties}
 *       and HikariCP {@link DataSource}</b> &mdash; the connection pool
 *       beans are placed in Spring's refresh scope so that a Secrets
 *       Manager rotation event ({@link org.springframework.cloud.context.refresh.ContextRefresher#refresh()})
 *       destroys and re-instantiates them with the rotated credentials,
 *       <strong>without</strong> restarting the JVM or the application
 *       context. Existing connections drain via the HikariCP
 *       {@code maxLifetime} / {@code idleTimeout} settings.</li>
 *   <li><b>HikariCP {@code maxLifetime}</b> tuned to a value lower than
 *       the Secrets Manager rotation window so that stale credentials do
 *       not linger in long-lived pool connections after rotation. The
 *       default 30-minute {@code max-lifetime} configured in
 *       {@code application.yml} (under {@code spring.datasource.hikari.*})
 *       safely fits within the typical 60-minute Secrets Manager rotation
 *       interval declared in
 *       {@code infrastructure/terraform/secrets.tf}.</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.6.2)</h2>
 * <p>Replaces: every VSAM Access Method Services {@code OPEN} /
 * {@code CLOSE} invocation in the COBOL programs ({@code app/cbl/CBACT01C.cbl},
 * {@code CBACT02C.cbl}, {@code CBACT03C.cbl}, {@code CBCUS01C.cbl},
 * {@code CBTRN01C.cbl}, {@code CBTRN02C.cbl}, {@code CBTRN03C.cbl},
 * {@code CBACT04C.cbl}, {@code CBSTM03A.CBL}, {@code CBSTM03B.CBL}, and
 * every CICS online program from {@code COACTVWC} through {@code COUSR03C}).
 * The VSAM {@code OPEN/CLOSE} and {@code READ/WRITE/REWRITE} verbs are
 * replaced by Spring Data JPA repository methods backed by RDS PostgreSQL
 * Multi-AZ.</p>
 *
 * <h2>Schema validation discipline (AAP &sect;0.6.2)</h2>
 * <p>{@code spring.jpa.hibernate.ddl-auto} is set to {@code validate} in
 * every application profile (see {@code application.yml} line 148). This
 * means:</p>
 * <ul>
 *   <li>At startup, Hibernate validates that every {@code @Entity}
 *       column maps to an existing column with a compatible JDBC type in
 *       the database.</li>
 *   <li>Hibernate NEVER auto-generates or modifies DDL &mdash; schema
 *       changes happen exclusively via Flyway migrations under
 *       {@code src/main/resources/db/migration/V*.sql}.</li>
 *   <li>A schema mismatch (entity field renamed, missing column, type
 *       drift) fails the application context startup loudly with a
 *       {@code HibernateException}, preventing a silent data-corruption
 *       deployment.</li>
 * </ul>
 *
 * <h2>Transaction management</h2>
 * <p>Spring Boot 3.x auto-configures a {@link org.springframework.orm.jpa.JpaTransactionManager}
 * from the {@link DataSource} bean. The
 * {@code @EnableTransactionManagement} annotation on
 * {@code CardDemoApplication} explicitly activates declarative
 * {@code @Transactional} support across all service layers. This config
 * intentionally does NOT redefine the transaction manager &mdash; the
 * Spring Boot default is correct and aligned with AAP &sect;0.7.1.</p>
 *
 * @see com.awsm2.carddemo.config.SecretsManagerConfig
 * @see <a href="https://docs.awspring.io/spring-cloud-aws/docs/3.2.1/reference/html/index.html#secrets-manager-integration">
 *      Spring Cloud AWS — Secrets Manager Integration</a>
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.awsm2.carddemo.repository")
public class JpaConfig {

    private static final Logger LOG = LoggerFactory.getLogger(JpaConfig.class);

    /**
     * Builds the {@link DataSourceProperties} bean from
     * {@code spring.datasource.*} entries in the active Spring profile.
     *
     * <p>Annotated {@link RefreshScope @RefreshScope} so the bean is
     * destroyed and re-instantiated on a {@code RefreshEvent} (typically
     * triggered by {@code SecretsManagerConfig.pollRotationEvents()}
     * after the rotation Lambda updates the RDS secret). The fresh
     * instance reads the rotated {@code username} and {@code password}
     * from the now-updated Spring Environment.</p>
     *
     * <p><b>Replaces:</b> {@code USRSEC} plaintext credentials embedded in
     * mainframe {@code PARM} datasets (AAP &sect;0.6.4).</p>
     *
     * @return a properties bean whose JDBC URL / username / password are
     *         resolved from {@code spring.datasource.*} after any active
     *         Secrets Manager imports
     */
    @Bean
    @Primary
    @RefreshScope
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        // Replaces: PARM-supplied JDBC credentials and the mainframe
        // RACF authentication of batch jobs against USRSEC.
        return new DataSourceProperties();
    }

    /**
     * Builds the primary application {@link DataSource} bean — a HikariCP
     * connection pool that connects to RDS PostgreSQL Multi-AZ (production)
     * or local PostgreSQL (Docker Compose) or H2 (test).
     *
     * <p>Annotated {@link RefreshScope @RefreshScope}: on a Secrets Manager
     * rotation event the pool is gracefully closed and recreated with the
     * rotated credentials. Open transactions hold connections checked out
     * of the old pool until they complete; new transactions check out
     * connections from the new pool. The HikariCP
     * {@code max-lifetime} (default 30 minutes in
     * {@code application.yml}) limits how long any individual connection
     * with old credentials can persist after rotation.</p>
     *
     * <p>Sourcing the {@link DataSource} from
     * {@link DataSourceProperties#initializeDataSourceBuilder()} ensures
     * full Spring Boot property binding (driver class, URL, username,
     * password, hikari sub-properties) is applied uniformly across
     * profiles. The driver class is auto-detected from the URL when not
     * explicitly set.</p>
     *
     * <h3>Spring Boot 3.1+ {@link JdbcConnectionDetails} interoperability</h3>
     * <p>This method ALSO consumes an {@link ObjectProvider} of
     * {@link JdbcConnectionDetails}. When present in the application
     * context (typically supplied by a Testcontainers
     * {@code @ServiceConnection} on a {@code PostgreSQLContainer}, by
     * Spring Cloud AWS RDS auto-detection, or by any other
     * {@code ConnectionDetailsFactory}), the {@code JdbcConnectionDetails}
     * bean takes precedence over the
     * {@link DataSourceProperties}-driven values. This mirrors the
     * behavior of Spring Boot's own
     * {@code DataSourceConfiguration.Hikari} auto-config &mdash; which
     * we cannot rely on here because we deliberately overrode that
     * auto-config with the {@code @Primary @RefreshScope} bean above
     * (AAP &sect;0.6.4 rotation requirement).</p>
     *
     * <p>This explicit consumer is necessary because Spring Boot's
     * companion {@code HikariJdbcConnectionDetailsBeanPostProcessor}
     * does NOT process {@code @RefreshScope} HikariDataSource beans when
     * they are eagerly resolved during {@code jobRegistryBeanPostProcessor}
     * initialization (a known Spring Batch + Spring Cloud Context
     * interaction). Without this fallback path, integration tests using
     * {@code @ServiceConnection} would connect Hibernate to a different
     * container than Flyway, producing spurious "missing table" errors
     * after migrations ran successfully.</p>
     *
     * @param props the refresh-scoped properties bean produced above
     * @param connectionDetailsProvider an {@link ObjectProvider} of
     *        {@link JdbcConnectionDetails}; when present its values
     *        (URL, username, password, driver class) override the
     *        DataSourceProperties values. When absent (production
     *        default), the DataSourceProperties path is used.
     * @return a fully-configured HikariCP {@link DataSource}
     */
    @Bean
    @Primary
    @RefreshScope
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties props,
                                 ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider) {
        // Replaces: VSAM OPEN / CLOSE handles in COBOL batch programs and
        // the per-CICS-region connection pool to VSAM. Backing store for
        // every @Repository in com.awsm2.carddemo.repository.
        JdbcConnectionDetails details = connectionDetailsProvider.getIfAvailable();
        HikariDataSource ds;
        if (details != null) {
            // Spring Boot 3.1+ JdbcConnectionDetails path (Testcontainers
            // @ServiceConnection, Spring Cloud AWS RDS detection, etc.).
            // Takes precedence over yml-derived DataSourceProperties so
            // that integration tests connect to the SAME container that
            // Flyway and other ConnectionDetails-consuming beans use.
            LOG.debug("Building refresh-scoped HikariCP DataSource from JdbcConnectionDetails url={}",
                    details.getJdbcUrl());
            ds = (HikariDataSource) DataSourceBuilder.create()
                    .type(HikariDataSource.class)
                    .driverClassName(details.getDriverClassName())
                    .url(details.getJdbcUrl())
                    .username(details.getUsername())
                    .password(details.getPassword())
                    .build();
        } else {
            // Production path: build from DataSourceProperties (which
            // sources URL/credentials from spring.datasource.* in the
            // active application*.yml, including any AWS Secrets Manager
            // imports resolved via spring.config.import).
            LOG.debug("Building refresh-scoped HikariCP DataSource for url={}", props.getUrl());
            ds = props.initializeDataSourceBuilder()
                    .type(HikariDataSource.class)
                    .build();
        }
        // Pool name simplifies log analysis and CloudWatch dashboard
        // configuration; intentionally not configurable per environment
        // so dashboards work uniformly across local/dev/prod.
        if (ds.getPoolName() == null || ds.getPoolName().isBlank()
                || ds.getPoolName().startsWith("HikariPool-")) {
            ds.setPoolName("carddemo-pool");
        }
        return ds;
    }
}
