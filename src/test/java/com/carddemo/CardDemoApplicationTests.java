package com.carddemo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.Counter;

import com.carddemo.controller.AuthController;
import com.carddemo.controller.GlobalExceptionHandler;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.repository.AccountRepository;
import com.carddemo.service.SignonService;

/**
 * Root Spring Boot context-load test for the migrated AWS CardDemo system
 * (COBOL/CICS/VSAM/JCL &rarr; Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.11).
 *
 * <p>This is the direct-child test of {@link com.carddemo.CardDemoApplication}: a full
 * {@link SpringBootTest @SpringBootTest} that boots the <em>entire</em> application context under the
 * {@code test} profile and proves every bean-producing layer &mdash; {@code config}, {@code repository},
 * {@code service}, {@code controller}, {@code batch}, and {@code observability} &mdash; wires together
 * against the real infrastructure it requires. A PostgreSQL&nbsp;16 Testcontainer runs the real Flyway
 * migrations ({@code V1__schema.sql} &rarr; {@code V2__indexes.sql} &rarr; {@code V3__seed_data.sql}) and
 * Hibernate {@code validate}s the {@code com.carddemo.entity.*} mappings against that schema, while a
 * LocalStack Testcontainer (S3/SQS/SNS) lets Spring&nbsp;Cloud&nbsp;AWS auto-configuration resolve with
 * <strong>zero live AWS</strong>. The {@code entity} and {@code dto} types are not Spring beans; their
 * correctness is proven indirectly here (entities via the successful {@code EntityManagerFactory}/repository
 * wiring plus Hibernate {@code validate}) and directly in their own per-layer test subpackages. The suite
 * also guards the migration invariant that <strong>no Spring Batch job auto-runs</strong> at startup.
 *
 * <p>The design rationale &mdash; why real Testcontainers instead of mocks (Gate&nbsp;1/4/5 spirit), and why
 * the report {@code @SqsListener} lets its FIFO queue be auto-created via
 * {@code spring.cloud.aws.sqs.queue-not-found-strategy=create} rather than relying on a
 * {@code listener.auto-startup} flag that does not exist in Spring&nbsp;Cloud&nbsp;AWS&nbsp;3.3.0 &mdash; is
 * recorded in {@code docs/decision-log.md} per the Explainability rule, not duplicated in these comments.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("CardDemoApplication — full application-context load across all layers (test profile)")
class CardDemoApplicationTests {

    /**
     * Real PostgreSQL&nbsp;16 database for the context load. Flyway applies the production migrations and
     * Hibernate validates the entity mappings against the resulting schema, so this is deliberately a real
     * container and never a mock (Gate&nbsp;1/4). The tag is pinned (no {@code latest}). This is the
     * non-deprecated {@code org.testcontainers.postgresql.PostgreSQLContainer} &mdash; a concrete,
     * non-generic type in Testcontainers&nbsp;2.x &mdash; so no type parameter is required and the
     * {@code -Xlint:all} build stays warning-free (Gate&nbsp;2).
     */
    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    /**
     * Community LocalStack (never {@code localstack-pro}, which would require a {@code LOCALSTACK_AUTH_TOKEN})
     * providing S3, SQS, and SNS so Spring&nbsp;Cloud&nbsp;AWS auto-configuration wires the mandatory
     * {@code S3Template}/{@code SqsTemplate} dependencies with zero live AWS (AAP&nbsp;&sect;0.7.7). This is the
     * non-deprecated {@code org.testcontainers.localstack.LocalStackContainer}; services are enabled through its
     * {@code withServices(String...)} overload (the legacy {@code LocalStackContainer.Service} enum is
     * deprecated in Testcontainers&nbsp;2.x and would break the zero-warning build). The tag is pinned (no
     * {@code latest}).
     */
    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4"))
                    .withServices("s3", "sqs", "sns");

    /**
     * Injects the volatile Testcontainers coordinates into the Spring {@code Environment} at context-build
     * time. {@code application-test.yml} intentionally omits the datasource URL and the AWS endpoint (both are
     * container-assigned and only known at runtime), so they are supplied here alongside the static region /
     * dummy-credential / path-style values that keep the AWS SDK v2 clients off the ambient credential chain.
     *
     * <p>No {@code spring.cloud.aws.sqs.listener.auto-startup} property is set: it does not exist in
     * Spring&nbsp;Cloud&nbsp;AWS&nbsp;3.3.0. The report {@code @SqsListener} on {@code ReportJobLauncher} starts,
     * and {@code queue-not-found-strategy=create} (from {@code application-test.yml}) makes it auto-create the
     * {@code carddemo-report-jobs.fifo} queue on this throwaway LocalStack, so context startup is deterministic
     * and never depends on pre-existing LocalStack state.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
        // PostgreSQL 16 — Flyway (V1 -> V2 -> V3) then Hibernate validate run inside this container.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Spring Cloud AWS -> Testcontainers LocalStack (zero live AWS).
        registry.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
        registry.add("spring.cloud.aws.credentials.access-key", () -> "test");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "test");
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> Boolean.TRUE);
    }

    /** The fully refreshed application context under test. */
    @Autowired
    private ApplicationContext context;

    /** Spring Batch metadata explorer used to prove that no job instance was created at startup. */
    @Autowired
    private JobExplorer jobExplorer;

    /**
     * Primary assertion: the whole {@link SpringBootTest @SpringBootTest} context initializes. If any bean is
     * missing, Flyway fails, Hibernate {@code validate} detects a schema/mapping mismatch, or Spring&nbsp;Cloud
     * AWS auto-configuration errors, context refresh throws and this test fails &mdash; which is the point.
     */
    @Test
    @DisplayName("contextLoads: the full application context initializes")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /**
     * Proves each bean-producing layer initialized by resolving one representative bean from each. Type-unique
     * beans are resolved by type; the {@code batch} {@link Job} beans and the observability {@link Counter} are
     * resolved by name because several beans of those types exist in the context.
     */
    @Test
    @DisplayName("allLayersWired: a representative bean from every bean-producing layer is present")
    void allLayersWired() {
        // config layer — BCrypt PasswordEncoder from SecurityConfig.
        assertThat(context.getBeanNamesForType(PasswordEncoder.class)).isNotEmpty();

        // repository layer — proves Spring Data JPA scanning + EntityManagerFactory, hence that the entity
        // mappings loaded and Hibernate `validate` passed against the Flyway schema.
        assertThat(context.getBeanNamesForType(AccountRepository.class)).isNotEmpty();

        // service layer.
        assertThat(context.getBeanNamesForType(SignonService.class)).isNotEmpty();

        // controller layer — a @RestController plus the @RestControllerAdvice error handler.
        assertThat(context.getBeanNamesForType(AuthController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(GlobalExceptionHandler.class)).isNotEmpty();

        // observability layer — the MDC correlation-id filter and the posted-transactions counter.
        assertThat(context.getBeanNamesForType(CorrelationIdFilter.class)).isNotEmpty();
        assertThat(context.getBean("transactionsPostedCounter", Counter.class)).isNotNull();

        // batch layer — representative pipeline jobs (resolved by name; many Job beans exist).
        assertThat(context.getBean("postTransactionJob", Job.class)).isNotNull();
        assertThat(context.getBean("transactionReportJob", Job.class)).isNotNull();
    }

    /**
     * Guards the invariant that {@code spring.batch.job.enabled=false} held: no batch job is launched merely
     * because the context booted (jobs run on demand and via the SQS FIFO report trigger). An empty
     * {@link JobExplorer#getJobNames()} means no {@code BATCH_JOB_INSTANCE} row was created during startup.
     */
    @Test
    @DisplayName("noBatchJobAutoRanAtStartup: no Spring Batch job executed during context refresh")
    void noBatchJobAutoRanAtStartup() {
        assertThat(jobExplorer.getJobNames()).isEmpty();
    }
}
