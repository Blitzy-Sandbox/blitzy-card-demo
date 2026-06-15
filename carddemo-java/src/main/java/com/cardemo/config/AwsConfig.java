package com.cardemo.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.awspring.cloud.autoconfigure.AwsAsyncClientCustomizer;
import io.awspring.cloud.autoconfigure.AwsSyncClientCustomizer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

/**
 * AWS infrastructure {@code @Configuration} for the greenfield Java 25 LTS + Spring Boot 3.5.15
 * migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <p>This {@code @Configuration} is component-scanned by {@code CardDemoApplication} (base package
 * {@code com.cardemo}, decision <strong>D-006</strong> &mdash; deliberately <em>not</em>
 * {@code com.carddemo}), which delegates all cross-cutting configuration to the {@code config}
 * package. Its role in the authoritative target tree is &quot;S3, SQS, SNS clients&quot;
 * (tech-spec L339); the underlying client dependencies are the Spring Cloud AWS 3.3.0 starters
 * {@code spring-cloud-aws-starter-s3 / -sqs / -sns} (tech-spec L734&ndash;L736).</p>
 *
 * <h2>Key insight &mdash; binding &amp; decoupling, not client construction</h2>
 * <p>Spring Cloud AWS 3.3.0 <strong>auto-configures</strong> the AWS SDK v2 clients and the Spring
 * abstractions over them ({@code S3Client} + {@code S3Template}, {@code SqsTemplate} +
 * {@code SqsAsyncClient}, {@code SnsTemplate} + {@code SnsClient}) directly from the
 * {@code spring.cloud.aws.*} properties. Consequently the <em>only</em> hand-written artifact this
 * class needs is a strongly-typed binder for the application-owned resource <em>names</em> (the S3
 * buckets, the SQS FIFO queue, and the SNS topic). That binder &mdash; {@link AwsResourceProperties}
 * &mdash; lets the consumers ({@code service/report/ReportSubmissionService} and the Spring Batch
 * readers/writers, authored by other agents) resolve every resource name from configuration instead
 * of from magic strings scattered through the code. Keeping every endpoint, credential, and region
 * in externalized properties (never in this Java file) is what simultaneously satisfies the
 * &quot;zero live AWS / LocalStack-verifiable / no hardcoded secrets&quot; mandates (AAP
 * &sect;0.7.2, &sect;0.7.7).</p>
 *
 * <h2>Technology substitutions (AAP &sect;0.1.2 / Minimal Change Clause &sect;0.7.1 &mdash;
 * documented at the point of change)</h2>
 * <dl>
 *   <dt>GDG generations &rarr; versioned S3 objects (decision <strong>D-003</strong>)</dt>
 *   <dd>The legacy sequential PS staging datasets and the six Generation Data Group bases defined by
 *       {@code app/jcl/DEFGDGB.jcl} ({@code TRANSACT.BKUP}, {@code TRANSACT.DALY}, {@code TRANREPT},
 *       {@code TCATBALF.BKUP}, {@code SYSTRAN}, {@code TRANSACT.COMBINED}, each {@code LIMIT(5)})
 *       collapse into three S3 buckets whose generation numbering is realized through S3 object
 *       versioning rather than separate datasets (tech-spec L22, transformation rule L87). The
 *       bucket names are bound below; the staging/read/write logic lives in the batch layer.</dd>
 *
 *   <dt>CICS TDQ {@code WRITEQ} &rarr; AWS SQS FIFO (decision <strong>D-004</strong>)</dt>
 *   <dd>The sole online&rarr;batch bridge {@code app/cbl/CORPT00C.cbl} (paragraph
 *       {@code WIRTE-JOBSUB-TDQ}, which issues {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} to submit a
 *       report job to JES) becomes an SQS publish to the FIFO queue {@code carddemo-report-jobs.fifo},
 *       consumed by a Spring Batch report job (tech-spec L30, L631, L1071; AAP &sect;0.6.3). The
 *       {@code .fifo} suffix is mandatory and preserves the point-to-point, ordered delivery of the
 *       transient-data queue. The queue name is bound below; the publish logic lives in
 *       {@code ReportSubmissionService}.</dd>
 *
 *   <dt>CICS notification messaging &rarr; AWS SNS</dt>
 *   <dd>Notification fan-out is published to the SNS topic {@code carddemo-notifications}. The topic
 *       name is bound below; the publish logic lives in the report/notification layer.</dd>
 * </dl>
 *
 * <h2>Migration provenance (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>This is a brand-new greenfield file. It has <strong>no 1:1 COBOL source</strong>; it is a pure
 * technology-substitution component in the net-new cloud-integration layer. Behaviour for the wider
 * application is translated from the frozen AWS CardDemo COBOL baseline at commit SHA
 * {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this repository, and traceability
 * to the legacy baseline is by that commit SHA only (AAP &sect;0.7.2 &mdash; Preservation
 * Requirements).</p>
 *
 * <h2>Secret &amp; endpoint policy (AAP &sect;0.7.2 / &sect;0.7.7)</h2>
 * <p>This file contains <strong>no</strong> AWS credentials, <strong>no</strong> endpoint URLs,
 * and <strong>no</strong> region literals. The LocalStack endpoint override
 * ({@code spring.cloud.aws.endpoint}), the static region ({@code spring.cloud.aws.region.static}),
 * the credentials ({@code spring.cloud.aws.credentials.*}), and S3 path-style access
 * ({@code spring.cloud.aws.s3.path-style-access-enabled}) are all declared exclusively in the
 * externalized profile documents: {@code application-local.yml} (host / docker-compose) and
 * {@code application-test.yml} (Testcontainers LocalStack, whose mapped endpoint is injected at
 * runtime via {@code @DynamicPropertySource}). Spring Cloud AWS 3.3.0 reads those properties
 * automatically, so the AWS endpoint can only ever resolve to LocalStack &mdash; never a live AWS
 * account &mdash; with zero live credentials required.</p>
 *
 * <h2>Separation of concerns (boundaries this class does not cross)</h2>
 * <ul>
 *   <li>It does <strong>not</strong> hand-build {@code S3Client}, {@code S3Template},
 *       {@code SqsTemplate}, {@code SqsAsyncClient}, {@code SnsTemplate}, or {@code SnsClient} beans
 *       &mdash; Spring Cloud AWS auto-configuration already provides them from
 *       {@code spring.cloud.aws.*}. Declaring them here would duplicate auto-configuration and risk
 *       embedding endpoint/region literals (Minimal Change Clause, AAP &sect;0.7.1).</li>
 *   <li>It does <strong>not</strong> declare any AWS endpoint, region, or credential &mdash; those
 *       live only in {@code application*.yml} / environment variables (see secret policy above).</li>
 *   <li>It does <strong>not</strong> provision AWS resources (buckets / queue / topic). Provisioning
 *       is owned by {@code localstack-init/init-aws.sh} for local/compose runs and by the
 *       integration tests' {@code @BeforeAll}/{@code @AfterAll} for the {@code test} profile
 *       (AAP &sect;0.7.7 &mdash; &quot;tests create and destroy their own resources&quot;).</li>
 *   <li>It does <strong>not</strong> contain any publish/consume/staging business logic &mdash; that
 *       lives in {@code service/report/ReportSubmissionService} and the {@code batch/**} readers and
 *       writers, which <em>consume</em> the names bound here.</li>
 * </ul>
 *
 * @see AwsResourceProperties
 * @see org.springframework.context.annotation.Configuration
 * @see org.springframework.boot.context.properties.EnableConfigurationProperties
 */
@Configuration
@EnableConfigurationProperties(AwsConfig.AwsResourceProperties.class)
public class AwsConfig {

    /*
     * Client construction is delegated to auto-configuration; this class hand-builds NO client bean
     * and contributes ONLY the two property-driven timeout customizers below.
     *
     * Spring Cloud AWS 3.3.0 (spring-cloud-aws-starter-s3 / -sqs / -sns, tech-spec L734-L736)
     * auto-configures every client and template the application needs:
     *   - S3Client + S3Template
     *   - SqsTemplate + SqsAsyncClient (the SQS listener-CONTAINER infrastructure is auto-configured
     *     by the starter, but NO application @SqsListener is defined anywhere in this repository: the
     *     sole online->batch report bridge is PUBLISH-ONLY, faithful to CORPT00C's WRITEQ('JOBS')
     *     followed by out-of-band JES pickup; see DECISION_LOG.md D-012 and ReportSubmissionService)
     *   - SnsTemplate + SnsClient
     * All of them are built from the spring.cloud.aws.* properties (endpoint / region.static /
     * credentials.* / s3.path-style-access-enabled) declared in application-local.yml and
     * application-test.yml. Per the Minimal Change Clause (AAP S0.7.1) NO client bean is hand-built
     * here: doing so would duplicate the auto-configuration and would force an endpoint/region
     * literal into Java, violating the zero-hardcoded-endpoint / zero-live-AWS mandates
     * (AAP S0.7.2 / S0.7.7).
     *
     * The ONLY beans this class contributes are the two AWS SDK v2 client customizers below. They
     * pin EXPLICIT, AUDITABLE api-call timeout bounds (apiCallTimeout / apiCallAttemptTimeout) onto
     * every auto-configured client so an outbound AWS call fast-fails within a known ceiling instead
     * of depending on implicit SDK defaults (resilience hardening; technology-substitution
     * documented at the point of change per AAP S0.7.1, decision D-013). They are exactly the
     * property-driven customizer this design always anticipated: every input is read from
     * carddemo.aws.timeout.* (bound by AwsResourceProperties below) - never from literals - and they
     * ADD the timeouts onto the EXISTING auto-configured override (preserving Spring Cloud AWS's
     * user-agent and any other defaults via toBuilder()), never replacing a client.
     */

    /**
     * Customizer applied to every auto-configured AWS <strong>synchronous</strong> SDK client
     * builder ({@code S3Client}, {@code SnsClient}).
     *
     * <p>Spring Cloud AWS collects all {@link AwsSyncClientCustomizer} beans and runs them via
     * {@code AwsClientBuilderConfigurer.configureSyncClient(...)} <em>after</em> it has installed its
     * own {@link ClientOverrideConfiguration} (which carries the framework user-agent). This bean
     * therefore reads that existing override and ADDS the explicit api-call timeout bounds on top,
     * preserving the user-agent rather than clobbering it (decision <strong>D-013</strong>; AAP
     * &sect;0.7.1 Minimal Change Clause &mdash; resilience hardening with no behavioural change to
     * the migrated business logic).</p>
     *
     * <p>The customizer callback hands back an {@link AwsSyncClientBuilder} reference, whose static
     * type only exposes the synchronous HTTP-client setters &mdash; the {@code overrideConfiguration}
     * getter/setter live on {@link SdkClientBuilder}, which the <em>concrete</em> runtime builder
     * ({@code S3ClientBuilder}, {@code SnsClientBuilder}) implements via {@code AwsClientBuilder} but
     * {@code AwsSyncClientBuilder} itself does not extend. A narrowing interface cast to
     * {@link SdkClientBuilder} therefore exposes the override accessors; it is always safe because
     * Spring Cloud AWS only ever passes real AWS service builders here.</p>
     *
     * @param properties the bound {@link AwsResourceProperties}; supplies the
     *                   {@code carddemo.aws.timeout.*} bounds (never {@code null})
     * @return a customizer that pins {@code apiCallTimeout}/{@code apiCallAttemptTimeout} on sync
     *         client builders
     */
    @Bean
    AwsSyncClientCustomizer cardDemoAwsSyncClientTimeoutCustomizer(final AwsResourceProperties properties) {
        final AwsResourceProperties.Timeout timeout = properties.getTimeout();
        return builder -> {
            final SdkClientBuilder<?, ?> clientBuilder = (SdkClientBuilder<?, ?>) builder;
            clientBuilder.overrideConfiguration(
                    withApiCallTimeouts(clientBuilder.overrideConfiguration(), timeout));
        };
    }

    /**
     * Customizer applied to every auto-configured AWS <strong>asynchronous</strong> SDK client
     * builder ({@code SqsAsyncClient} &mdash; the client backing the online&rarr;batch report
     * bridge's FIFO publish).
     *
     * <p>Spring Cloud AWS collects all {@link AwsAsyncClientCustomizer} beans and runs them via
     * {@code AwsClientBuilderConfigurer.configureAsyncClient(...)}, mirroring the sync path above.
     * The same additive, user-agent-preserving strategy applies (decision <strong>D-013</strong>;
     * AAP &sect;0.7.1). The same narrowing cast to {@link SdkClientBuilder} is required because
     * {@link AwsAsyncClientBuilder} exposes only the asynchronous HTTP-client setters statically,
     * while the {@code overrideConfiguration} accessors are declared on {@link SdkClientBuilder} and
     * implemented by the concrete runtime builder ({@code SqsAsyncClientBuilder}).</p>
     *
     * @param properties the bound {@link AwsResourceProperties}; supplies the
     *                   {@code carddemo.aws.timeout.*} bounds (never {@code null})
     * @return a customizer that pins {@code apiCallTimeout}/{@code apiCallAttemptTimeout} on async
     *         client builders
     */
    @Bean
    AwsAsyncClientCustomizer cardDemoAwsAsyncClientTimeoutCustomizer(final AwsResourceProperties properties) {
        final AwsResourceProperties.Timeout timeout = properties.getTimeout();
        return builder -> {
            final SdkClientBuilder<?, ?> clientBuilder = (SdkClientBuilder<?, ?>) builder;
            clientBuilder.overrideConfiguration(
                    withApiCallTimeouts(clientBuilder.overrideConfiguration(), timeout));
        };
    }

    /**
     * Additively layers the configured api-call timeout bounds onto an existing
     * {@link ClientOverrideConfiguration}, returning the merged configuration.
     *
     * <p>This helper is deliberately <em>generics-free</em>: it operates purely on the concrete
     * {@link ClientOverrideConfiguration} value rather than on the AWS builder type, so a single
     * implementation serves both the synchronous and asynchronous customizer beans above. Each bean
     * narrows its builder to {@link SdkClientBuilder} (the type that declares the
     * {@code overrideConfiguration()} getter and {@code overrideConfiguration(ClientOverrideConfiguration)}
     * setter &mdash; both non-generic in their signatures), reads the existing override, hands it here,
     * and writes the merged result back. Factoring the merge at the override-configuration level keeps
     * the shared logic free of the F-bounded builder type parameters
     * ({@code B extends SdkClientBuilder<B,C>}) that would otherwise force per-builder generics.</p>
     *
     * <p>Any pre-existing override installed by Spring Cloud AWS (e.g. the framework user-agent) is
     * copied via {@link ClientOverrideConfiguration#toBuilder()} before the two explicit timeouts are
     * applied &mdash; so the timeouts are <em>added</em>, never substituted for the framework defaults
     * (decision <strong>D-013</strong>; AAP &sect;0.7.1).</p>
     *
     * @param existing the override already present on the builder (may be {@code null} if none)
     * @param timeout  the bound {@code carddemo.aws.timeout.*} values (never {@code null})
     * @return a new {@link ClientOverrideConfiguration} carrying the prior settings plus the two
     *         explicit api-call timeout bounds
     */
    private static ClientOverrideConfiguration withApiCallTimeouts(
            final ClientOverrideConfiguration existing,
            final AwsResourceProperties.Timeout timeout) {
        final ClientOverrideConfiguration.Builder overrideBuilder =
                (existing != null) ? existing.toBuilder() : ClientOverrideConfiguration.builder();
        return overrideBuilder
                .apiCallTimeout(timeout.getApiCall())
                .apiCallAttemptTimeout(timeout.getApiCallAttempt())
                .build();
    }

    /**
     * Strongly-typed binder for the application-owned AWS resource <em>names</em> &mdash; the S3
     * buckets, the SQS FIFO queue, and the SNS topic &mdash; bound from the {@code carddemo.aws.*}
     * configuration tree.
     *
     * <p>This is deliberately the only stateful artifact in {@link AwsConfig}: it decouples
     * consumers ({@code ReportSubmissionService}, the Spring Batch readers/writers) from magic
     * strings by exposing each resource name through a getter. The property keys and defaults are
     * identical across {@code application-local.yml} and {@code application-test.yml} (a single
     * {@code @ConfigurationProperties} bean binds across both profiles), and match the names
     * provisioned by {@code localstack-init/init-aws.sh} verbatim &mdash; that triad is the
     * single source of truth for the resource-name contract.</p>
     *
     * <h3>Binding contract (Spring relaxed binding: kebab-case YAML &rarr; camelCase fields)</h3>
     * <ul>
     *   <li>{@code carddemo.aws.s3.batch-input-bucket}   &rarr; {@code s3.batchInputBucket}
     *       (default {@code carddemo-batch-input})</li>
     *   <li>{@code carddemo.aws.s3.batch-output-bucket}  &rarr; {@code s3.batchOutputBucket}
     *       (default {@code carddemo-batch-output})</li>
     *   <li>{@code carddemo.aws.s3.statements-bucket}    &rarr; {@code s3.statementsBucket}
     *       (default {@code carddemo-statements})</li>
     *   <li>{@code carddemo.aws.sqs.report-jobs-queue}   &rarr; {@code sqs.reportJobsQueue}
     *       (default {@code carddemo-report-jobs.fifo})</li>
     *   <li>{@code carddemo.aws.sns.notifications-topic} &rarr; {@code sns.notificationsTopic}
     *       (default {@code carddemo-notifications})</li>
     * </ul>
     *
     * <p>Every field carries a default equal to the contract name so the application context loads
     * with a valid resource-name set even under a profile that does not declare {@code carddemo.aws.*}
     * (the profile-agnostic {@code application.yml} intentionally does not). The {@code local} and
     * {@code test} profiles override each value through a {@code ${ENV_VAR:contract-name}} indirection
     * that resolves to the same default, keeping host, docker-compose, and Testcontainers runs in
     * lock-step. Each name is annotated {@link NotBlank} (Jakarta Bean Validation, tech-spec L732) and
     * the class is {@link Validated}, so a blank/misconfigured name fails fast at startup rather than
     * surfacing as an obscure AWS 404 at first use.</p>
     */
    @Validated
    @ConfigurationProperties(prefix = "carddemo.aws")
    public static class AwsResourceProperties {

        /** S3 bucket names (GDG generations &rarr; versioned S3 objects, decision D-003). */
        @Valid
        private final S3 s3 = new S3();

        /** SQS queue name (CICS TDQ {@code WRITEQ} &rarr; SQS FIFO, decision D-004). */
        @Valid
        private final Sqs sqs = new Sqs();

        /** SNS topic name (CICS notification messaging &rarr; SNS fan-out). */
        @Valid
        private final Sns sns = new Sns();

        /**
         * Explicit AWS SDK api-call timeout bounds (resilience hardening, decision
         * <strong>D-013</strong>) applied to every auto-configured client by the customizers in
         * {@link AwsConfig}.
         */
        @Valid
        private final Timeout timeout = new Timeout();

        /**
         * Returns the S3 resource-name group.
         *
         * @return the immutable {@link S3} holder (never {@code null}); Spring binds individual
         *         bucket names into it via the leaf setters
         */
        public S3 getS3() {
            return s3;
        }

        /**
         * Returns the SQS resource-name group.
         *
         * @return the immutable {@link Sqs} holder (never {@code null}); Spring binds the queue name
         *         into it via the leaf setter
         */
        public Sqs getSqs() {
            return sqs;
        }

        /**
         * Returns the SNS resource-name group.
         *
         * @return the immutable {@link Sns} holder (never {@code null}); Spring binds the topic name
         *         into it via the leaf setter
         */
        public Sns getSns() {
            return sns;
        }

        /**
         * Returns the AWS api-call timeout group.
         *
         * @return the immutable {@link Timeout} holder (never {@code null}); Spring binds the
         *         {@code carddemo.aws.timeout.*} durations into it via the leaf setters
         */
        public Timeout getTimeout() {
            return timeout;
        }

        /**
         * S3 bucket names that replace the legacy sequential PS staging datasets and GDG generations
         * (decision <strong>D-003</strong>). Generation numbering ({@code DEFGDGB.jcl LIMIT(5)}) is
         * realized through S3 object versioning, so the six legacy GDG bases collapse into the three
         * buckets below.
         */
        public static class S3 {

            /**
             * Batch file staging <em>input</em> bucket (replaces sequential PS datasets / GDG input
             * generations). Binds {@code carddemo.aws.s3.batch-input-bucket}.
             */
            @NotBlank
            private String batchInputBucket = "carddemo-batch-input";

            /**
             * Batch file staging <em>output</em> bucket (replaces GDG output generations). Binds
             * {@code carddemo.aws.s3.batch-output-bucket}.
             */
            @NotBlank
            private String batchOutputBucket = "carddemo-batch-output";

            /**
             * Generated-statement output bucket ({@code CBSTM03A}/{@code CBSTM03B} &rarr;
             * {@code StatementGenerationJob}). Binds {@code carddemo.aws.s3.statements-bucket}.
             */
            @NotBlank
            private String statementsBucket = "carddemo-statements";

            /**
             * Returns the batch input bucket name.
             *
             * @return the batch-input bucket name (default {@code carddemo-batch-input})
             */
            public String getBatchInputBucket() {
                return batchInputBucket;
            }

            /**
             * Sets the batch input bucket name (invoked by Spring's configuration binder).
             *
             * @param batchInputBucket the batch-input bucket name; must not be blank
             */
            public void setBatchInputBucket(final String batchInputBucket) {
                this.batchInputBucket = batchInputBucket;
            }

            /**
             * Returns the batch output bucket name.
             *
             * @return the batch-output bucket name (default {@code carddemo-batch-output})
             */
            public String getBatchOutputBucket() {
                return batchOutputBucket;
            }

            /**
             * Sets the batch output bucket name (invoked by Spring's configuration binder).
             *
             * @param batchOutputBucket the batch-output bucket name; must not be blank
             */
            public void setBatchOutputBucket(final String batchOutputBucket) {
                this.batchOutputBucket = batchOutputBucket;
            }

            /**
             * Returns the statements bucket name.
             *
             * @return the statements bucket name (default {@code carddemo-statements})
             */
            public String getStatementsBucket() {
                return statementsBucket;
            }

            /**
             * Sets the statements bucket name (invoked by Spring's configuration binder).
             *
             * @param statementsBucket the statements bucket name; must not be blank
             */
            public void setStatementsBucket(final String statementsBucket) {
                this.statementsBucket = statementsBucket;
            }
        }

        /**
         * SQS queue name that replaces the single CICS Transient Data Queue online&rarr;batch bridge
         * (decision <strong>D-004</strong>). {@code CORPT00C}'s {@code WRITEQ TD QUEUE('JOBS')} maps
         * to an SQS publish on the FIFO queue below; the mandatory {@code .fifo} suffix preserves the
         * ordered, point-to-point delivery semantics of the transient-data queue.
         */
        public static class Sqs {

            /**
             * FIFO report-jobs queue (online&rarr;batch report-submission trigger). Binds
             * {@code carddemo.aws.sqs.report-jobs-queue}. The {@code .fifo} suffix is mandatory and
             * must not be dropped.
             */
            @NotBlank
            private String reportJobsQueue = "carddemo-report-jobs.fifo";

            /**
             * Returns the report-jobs FIFO queue name.
             *
             * @return the report-jobs queue name (default {@code carddemo-report-jobs.fifo})
             */
            public String getReportJobsQueue() {
                return reportJobsQueue;
            }

            /**
             * Sets the report-jobs FIFO queue name (invoked by Spring's configuration binder).
             *
             * @param reportJobsQueue the report-jobs queue name; must not be blank and must retain
             *                        the {@code .fifo} suffix
             */
            public void setReportJobsQueue(final String reportJobsQueue) {
                this.reportJobsQueue = reportJobsQueue;
            }
        }

        /**
         * SNS topic name that replaces CICS notification messaging with topic fan-out.
         */
        public static class Sns {

            /**
             * Notifications topic. Binds {@code carddemo.aws.sns.notifications-topic}.
             */
            @NotBlank
            private String notificationsTopic = "carddemo-notifications";

            /**
             * Returns the notifications topic name.
             *
             * @return the notifications topic name (default {@code carddemo-notifications})
             */
            public String getNotificationsTopic() {
                return notificationsTopic;
            }

            /**
             * Sets the notifications topic name (invoked by Spring's configuration binder).
             *
             * @param notificationsTopic the notifications topic name; must not be blank
             */
            public void setNotificationsTopic(final String notificationsTopic) {
                this.notificationsTopic = notificationsTopic;
            }
        }

        /**
         * Explicit AWS SDK v2 api-call timeout bounds (decision <strong>D-013</strong>) that the
         * {@link AwsConfig} customizers pin onto every auto-configured client.
         *
         * <p>These give the integration tier an <em>auditable</em> upper bound on outbound AWS calls
         * rather than depending on the SDK's implicit HTTP-client defaults. A connection-refused
         * endpoint (e.g. LocalStack down) still fails fast immediately; these bounds additionally cap
         * the worst case where an endpoint accepts the socket but never responds, so no call can hang
         * indefinitely (AAP &sect;0.7.1 &mdash; resilience hardening, behaviour-preserving). Both
         * values bind from {@code carddemo.aws.timeout.*} in {@code application-local.yml} and
         * {@code application-test.yml}; the in-code defaults below are deliberately generous so the
         * LocalStack-backed integration tests' small-object S3/SQS/SNS operations never trip them.</p>
         *
         * <p>Spring's relaxed {@link Duration} binding accepts unit suffixes ({@code 60s}, {@code
         * 20s}, {@code 500ms}) directly from YAML.</p>
         */
        public static class Timeout {

            /**
             * Overall per-call ceiling spanning all retry attempts &rarr; AWS SDK
             * {@code apiCallTimeout}. Binds {@code carddemo.aws.timeout.api-call}.
             */
            @NotNull
            private Duration apiCall = Duration.ofSeconds(60);

            /**
             * Ceiling for a single attempt within a call (before a retry) &rarr; AWS SDK
             * {@code apiCallAttemptTimeout}. Binds {@code carddemo.aws.timeout.api-call-attempt}.
             */
            @NotNull
            private Duration apiCallAttempt = Duration.ofSeconds(20);

            /**
             * Returns the overall api-call timeout.
             *
             * @return the per-call timeout across all attempts (default 60s)
             */
            public Duration getApiCall() {
                return apiCall;
            }

            /**
             * Sets the overall api-call timeout (invoked by Spring's configuration binder).
             *
             * @param apiCall the per-call timeout across all attempts; must not be {@code null}
             */
            public void setApiCall(final Duration apiCall) {
                this.apiCall = apiCall;
            }

            /**
             * Returns the per-attempt api-call timeout.
             *
             * @return the single-attempt timeout (default 20s)
             */
            public Duration getApiCallAttempt() {
                return apiCallAttempt;
            }

            /**
             * Sets the per-attempt api-call timeout (invoked by Spring's configuration binder).
             *
             * @param apiCallAttempt the single-attempt timeout; must not be {@code null}
             */
            public void setApiCallAttempt(final Duration apiCallAttempt) {
                this.apiCallAttempt = apiCallAttempt;
            }
        }
    }
}
