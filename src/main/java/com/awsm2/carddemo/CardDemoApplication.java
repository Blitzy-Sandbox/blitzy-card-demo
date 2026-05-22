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
package com.awsm2.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring Boot 3.x entry point for the AWS-native Java port of the AWS Mainframe
 * Modernization CardDemo COBOL application.
 *
 * <p>Replaces the CICS region startup ({@code CICSAWSA}) and JES batch scheduler
 * that previously hosted the 18 online programs ({@code app/cbl/CO*.cbl}) and
 * 10 batch programs ({@code app/cbl/CB*.cbl} + {@code CSUTLDTC.cbl}) of the source
 * CardDemo mainframe application. This class is the single JVM entry point that
 * bootstraps the entire Spring application context, applies the active Spring
 * profile ({@code local}, {@code dev}, {@code prod}), and starts the embedded
 * servlet container that fronts every REST endpoint defined under
 * {@code com.awsm2.carddemo.controller}.</p>
 *
 * <h2>Package scanning root</h2>
 * <p>This class is located directly under {@code com.awsm2.carddemo}, which is
 * the {@code scanBasePackages} root for the entire application. Spring auto-
 * discovers every {@code @Service}, {@code @RestController}, {@code @Repository},
 * {@code @Component}, {@code @Configuration}, {@code @RestControllerAdvice}, and
 * {@code @KafkaListener} bean defined in the 13 sibling subpackages
 * ({@code controller}, {@code service}, {@code repository}, {@code domain},
 * {@code dto}, {@code adapter}, {@code batch}, {@code glue}, {@code exception},
 * {@code config}, {@code validation}, {@code security}, {@code util}).
 * The package choice is non-negotiable &mdash; moving this class outside
 * {@code com.awsm2.carddemo} would silently break component discovery for the
 * entire application.</p>
 *
 * <h2>Application-wide capabilities enabled here</h2>
 * <ul>
 *   <li>{@link SpringBootApplication} &mdash; auto-configuration, component
 *       scanning, and {@code @Configuration} semantics rooted at this package.</li>
 *   <li>{@link ConfigurationPropertiesScan} &mdash; discovers every
 *       {@code @ConfigurationProperties} bean across the
 *       {@code com.awsm2.carddemo} tree, including (but not limited to) the
 *       configuration types in {@code com.awsm2.carddemo.config}.</li>
 *   <li>{@link EnableCaching} &mdash; activates the {@code @Cacheable} cache-aside
 *       pattern backed by Amazon ElastiCache Redis (AAP &sect;0.7.1:
 *       <em>"ElastiCache (Redis) used for account balance caching &mdash;
 *       cache-aside pattern with TTL aligned to transaction frequency"</em>).</li>
 *   <li>{@link EnableAsync} &mdash; activates {@code @Async} non-blocking
 *       execution for {@code KafkaEventPublisher} and {@code AuditLogService}
 *       (CloudTrail + OpenSearch fan-out, AAP &sect;0.6.6).</li>
 *   <li>{@link EnableTransactionManagement} &mdash; explicitly activates
 *       declarative {@code @Transactional(rollbackFor = Exception.class)}
 *       support across all service layers, replacing the COBOL CICS
 *       {@code SYNCPOINT} / {@code SYNCPOINT ROLLBACK} semantics observed in
 *       {@code app/cbl/COACTUPC.cbl} (AAP &sect;0.7.1). Spring Boot 3.x auto-
 *       configures this when {@code spring-tx} is on the classpath, but the
 *       explicit annotation is retained here for self-documentation and
 *       auditability.</li>
 * </ul>
 *
 * <h2>Capabilities deliberately NOT enabled here</h2>
 * <p>Per AAP &sect;0.7.3 (Minimal Change Clause), the following annotations are
 * intentionally placed on dedicated {@code @Configuration} classes under
 * {@code com.awsm2.carddemo.config} rather than on this main class, keeping
 * configuration responsibilities clearly delineated:</p>
 * <ul>
 *   <li>{@code @EnableBatchProcessing} &mdash; belongs on {@code BatchConfig}.</li>
 *   <li>{@code @EnableKafka} &mdash; belongs on {@code KafkaConfig}.</li>
 *   <li>{@code @EnableWebSecurity} &mdash; belongs on {@code SecurityConfig}.</li>
 *   <li>{@code @OpenAPIDefinition} &mdash; belongs on {@code OpenApiConfig}.</li>
 * </ul>
 *
 * <h2>JPA repositories and scheduling enabled here (CP3)</h2>
 * <ul>
 *   <li>{@link EnableJpaRepositories} &mdash; activates Spring Data JPA
 *       repository discovery for the {@code com.awsm2.carddemo.repository}
 *       package (AAP &sect;0.4.1, &sect;0.6.2 &mdash; VSAM &rarr; RDS
 *       PostgreSQL migration via Spring Data JPA). The {@code JpaConfig}
 *       class still declares its own {@code @EnableJpaRepositories} for
 *       local self-documentation, but having the annotation here ensures
 *       repository scanning works even before {@code JpaConfig} loads.</li>
 *   <li>{@link EnableScheduling} &mdash; activates Spring's task
 *       scheduling infrastructure required by
 *       {@code SecretsManagerConfig.SecretsRotationListener#pollOnce()}
 *       (AAP &sect;0.6.4 &mdash; Secrets Manager dynamic rotation
 *       without restart, driven by an SQS-poll {@link
 *       org.springframework.scheduling.annotation.Scheduled} method).
 *       Scheduling is enabled application-wide so any future
 *       {@code @Scheduled} methods (e.g., periodic OpenSearch index
 *       rotation, CloudWatch metric flushes) work without further
 *       configuration.</li>
 * </ul>
 *
 * <h2>Provenance &mdash; COBOL entry points replaced</h2>
 * <p>The original CardDemo mainframe application begins execution at the CICS
 * signon program {@code COSGN00C} (transaction id {@code CC00}), which
 * authenticates the operator against the {@code USRSEC} VSAM cluster and routes
 * authenticated users to the appropriate menu controller
 * ({@code COMEN01C.cbl} for regular users, {@code COADM01C.cbl} for admin
 * users). In the Java target the JVM-level entry point is this class; the
 * functional signon/menu flow lives in {@code AuthController} +
 * {@code SignonService} ({@code COSGN00C}) and {@code MenuController} +
 * {@code MenuService} ({@code COMEN01C}, {@code COADM01C}). The COBOL source
 * files remain on disk under {@code app/cbl/} as the authoritative reference
 * (AAP &sect;0.2.2 &mdash; frozen, never edited).</p>
 *
 * <h2>External configuration</h2>
 * <p>This class deliberately performs zero in-code configuration. Profiles,
 * AWS Secrets Manager + Parameter Store bindings, Actuator endpoints, RDS
 * Multi-AZ DataSource, MSK Kafka producers and consumers, ElastiCache Redis
 * connection, OpenSearch REST client, and Step Functions / AWS Batch clients
 * are all wired through {@code application.yml} +
 * {@code application-{profile}.yml} plus the dedicated {@code @Configuration}
 * classes under {@code com.awsm2.carddemo.config}. AWS Secrets Manager dynamic
 * secret rotation without Spring Boot restart is handled by
 * {@code SecretsManagerConfig} + {@code @RefreshScope} beans per AAP
 * &sect;0.6.4.</p>
 *
 * <h2>Operational constraints honored by this file</h2>
 * <ul>
 *   <li>No hardcoded credentials (AAP &sect;0.7.1) &mdash; this file has none.</li>
 *   <li>No AWS SDK calls inline (AAP &sect;0.7.1) &mdash; AWS SDK is isolated
 *       to {@code com.awsm2.carddemo.adapter} and {@code com.awsm2.carddemo.config}.</li>
 *   <li>No business logic (AAP &sect;0.7.1, Minimal Change Clause) &mdash;
 *       this file is pure infrastructure.</li>
 *   <li>Jakarta EE only &mdash; no {@code javax.*} imports (AAP &sect;0.5.1).</li>
 *   <li>AWS SDK v2 only &mdash; no {@code com.amazonaws.*} imports anywhere in
 *       the application (AAP &sect;0.5.1).</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-boot/docs/3.3.x/reference/html/using.html#using.using-the-springbootapplication-annotation">
 *      Spring Boot &mdash; Using the &#64;SpringBootApplication Annotation</a>
 * @see com.awsm2.carddemo.config.AwsSdkConfig
 * @see com.awsm2.carddemo.config.RedisConfig
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.awsm2.carddemo.repository")
@EnableTransactionManagement
public class CardDemoApplication {

    /**
     * Package-private no-arg constructor.
     *
     * <p>The {@code @SpringBootApplication} composite annotation includes
     * {@code @Configuration} with the default {@code proxyBeanMethods = true},
     * which directs Spring to generate a CGLIB subclass of this class at
     * runtime so that {@code @Bean} factory methods (if any are added later)
     * can be intercepted for singleton semantics. CGLIB subclassing requires
     * the parent class to be non-{@code final} and to expose a constructor
     * that is accessible to the generated subclass (i.e., at minimum
     * package-private). For this reason the constructor is left at
     * package-private visibility rather than {@code private}.</p>
     *
     * <p>The class itself is intentionally NOT declared {@code final} for the
     * same reason &mdash; Spring's {@code ConfigurationClassParser} actively
     * rejects {@code final} {@code @Configuration} classes with
     * {@code Configuration problem: @Configuration class '...' may not be
     * final.} Marking the class {@code final} would prevent the Spring
     * application context from starting at all.</p>
     *
     * <p>The JVM does not need to invoke this constructor to execute
     * {@link #main(String[])}, but the Spring container may invoke it during
     * context refresh; therefore it is non-{@code private}.</p>
     */
    CardDemoApplication() {
        // Package-private &mdash; required to allow Spring's CGLIB-enhanced
        // subclass to invoke super() during @Configuration proxying. Not
        // intended for direct application code use.
    }

    /**
     * Application launcher; boots the Spring context and starts the embedded
     * servlet container.
     *
     * <p>Delegates entirely to {@link SpringApplication#run(Class, String...)},
     * which performs the standard Spring Boot bootstrap sequence:</p>
     * <ol>
     *   <li>Selects the active profile from {@code spring.profiles.active}
     *       (system property or environment variable {@code SPRING_PROFILES_ACTIVE}).</li>
     *   <li>Loads {@code application.yml} plus the matching
     *       {@code application-{profile}.yml} overlay.</li>
     *   <li>Imports external configuration from AWS Secrets Manager and AWS
     *       Systems Manager Parameter Store via
     *       {@code spring.config.import=aws-secretsmanager:} and
     *       {@code spring.config.import=aws-parameterstore:} (Spring Cloud AWS
     *       3.x) where declared per profile, satisfying the PCI-DSS rule that
     *       all credentials are fetched at runtime (AAP &sect;0.7.1).</li>
     *   <li>Runs auto-configuration, applying {@code @SpringBootApplication}
     *       defaults plus any imports declared in
     *       {@code META-INF/spring.factories}.</li>
     *   <li>Discovers every Spring-managed bean under
     *       {@code com.awsm2.carddemo} via {@code @ComponentScan} (implicit in
     *       {@code @SpringBootApplication}).</li>
     *   <li>Discovers every {@code @ConfigurationProperties} bean via
     *       {@code @ConfigurationPropertiesScan}.</li>
     *   <li>Initializes the embedded Tomcat servlet container fronting Spring
     *       MVC controllers and registers Spring Boot Actuator endpoints
     *       (per AAP &sect;0.7.1: {@code /actuator/health},
     *       {@code /actuator/health/liveness},
     *       {@code /actuator/health/readiness}, {@code /actuator/metrics}).</li>
     * </ol>
     *
     * <p>The JVM standard {@code main} signature is used so the resulting
     * Spring Boot fat-jar runs unchanged in three deployment modes:</p>
     * <ul>
     *   <li>{@code java -jar target/carddemo-0.1.0.jar} &mdash; local execution.</li>
     *   <li>{@code mvn spring-boot:run} &mdash; developer iteration.</li>
     *   <li>{@code docker run} of the multi-stage image declared in
     *       {@code Dockerfile} &mdash; production ECS Fargate task launch.</li>
     * </ul>
     *
     * @param args runtime arguments forwarded verbatim to
     *             {@link SpringApplication#run(Class, String...)}; Spring Boot
     *             interprets {@code --key=value} pairs as command-line property
     *             sources that override {@code application.yml} settings.
     */
    public static void main(final String[] args) {
        // Replaces: CICS region CICSAWSA startup + JES batch initiator launch.
        // COBOL: COSGN00C is the first program executed when a CICS terminal
        //        user starts the CardDemo transaction; in the Java target the
        //        JVM-level entry is this main() and the functional signon flow
        //        lives in AuthController + SignonService.
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
