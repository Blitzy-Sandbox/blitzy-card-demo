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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * AWS SDK for Java v2 client bean configuration per AAP &sect;0.4.1 and &sect;0.5.1.
 *
 * <p>This {@code @Configuration} class is the foundational AWS SDK wiring for the
 * CardDemo Spring Boot service. It declares one {@code @Bean} per AWS service used
 * anywhere in the {@code adapter/}, {@code config/}, and {@code glue/} packages,
 * plus shared {@link Region} and {@link AwsCredentialsProvider} beans consumed by
 * sibling configurations (notably {@code OpenSearchConfig} for SigV4 signing and
 * {@code SecretsManagerConfig} for rotation-notification polling).</p>
 *
 * <p><b>Replaces:</b> COBOL system services that interacted with mainframe
 * resource managers (VSAM Access Method Services, JES, RACF, SDSF, ICSF) &mdash;
 * now AWS SDK v2 service clients pointing to managed AWS services (S3,
 * Step Functions, Secrets Manager, CloudWatch, KMS, Glue, SQS, SNS).</p>
 *
 * <h2>Profile behavior</h2>
 * <ul>
 *   <li><b>local</b> ({@code spring.profiles.active=local}):
 *     <ul>
 *       <li>Endpoint override: {@code http://localhost:4566} (LocalStack) &mdash;
 *           sourced from {@code spring.cloud.aws.endpoint} in
 *           {@code application-local.yml}</li>
 *       <li>Credentials: {@link StaticCredentialsProvider} with {@code test} /
 *           {@code test} (LocalStack accepts any non-empty pair) &mdash; sourced
 *           from {@code spring.cloud.aws.credentials.access-key} and
 *           {@code spring.cloud.aws.credentials.secret-key}</li>
 *       <li>Region: {@code us-east-1} (LocalStack ignores region but the SDK
 *           requires one for signing)</li>
 *       <li>S3 path-style addressing enabled
 *           ({@code spring.cloud.aws.s3.path-style-access-enabled: true})</li>
 *     </ul>
 *   </li>
 *   <li><b>dev / prod</b> ({@code spring.profiles.active=dev|prod}):
 *     <ul>
 *       <li>No endpoint override (real AWS endpoints &mdash; the SDK resolves
 *           per-service endpoints from the configured {@link Region})</li>
 *       <li>Credentials: {@link DefaultCredentialsProvider} chain &mdash; uses
 *           ECS task role in ECS Fargate (via
 *           {@code AWS_CONTAINER_CREDENTIALS_RELATIVE_URI}), IAM instance
 *           profile on EC2, environment variables, or the shared credentials
 *           file in that order</li>
 *       <li>Region: sourced from the {@code AWS_REGION} environment variable
 *           per AAP &sect;0.7.2 required-env-vars list</li>
 *       <li>S3 path-style addressing disabled (virtual-host style is
 *           recommended for real AWS S3 for performance)</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>SDK version</h2>
 * <p>Per AAP &sect;0.7.1, only AWS SDK v2 ({@code software.amazon.awssdk.*})
 * is used. The deprecated v1 ({@code com.amazonaws.*}) is NEVER referenced in
 * this codebase. SDK v2 clients are thread-safe and intended to be shared
 * across the application; each {@code @Bean} below produces exactly one
 * singleton instance per AWS service.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Every client bean declares {@code destroyMethod = "close"} so that Spring
 * invokes the client's {@link AutoCloseable#close()} during context shutdown,
 * releasing the underlying Netty / Apache HTTP connection pool. This is
 * critical for graceful ECS task termination &mdash; without explicit close,
 * the JVM can hang waiting for HTTP client threads to exit.</p>
 *
 * <h2>No business logic</h2>
 * <p>This class is pure infrastructure wiring per AAP &sect;0.7.1 &mdash; every
 * method is a builder invocation; no AWS API calls are made at bean-construction
 * time. Connection establishment is deferred until the first request issued
 * by an adapter or configuration that consumes one of these beans.</p>
 *
 * @see com.awsm2.carddemo.adapter.S3OutputService
 * @see com.awsm2.carddemo.adapter.StepFunctionsOrchestrator
 * @see com.awsm2.carddemo.adapter.SecretsManagerService
 * @see com.awsm2.carddemo.config.CloudWatchConfig
 * @see com.awsm2.carddemo.config.OpenSearchConfig
 * @see com.awsm2.carddemo.config.SecretsManagerConfig
 * @see com.awsm2.carddemo.glue.GlueETLConfig
 */
@Configuration
public class AwsSdkConfig {

    // -----------------------------------------------------------------------
    // Configuration properties (injected from application*.yml)
    // -----------------------------------------------------------------------

    /**
     * AWS region for all SDK clients. Sourced from
     * {@code spring.cloud.aws.region.static} (the Spring Cloud AWS standard
     * property), which itself defaults to the {@code AWS_REGION} environment
     * variable per AAP &sect;0.7.2 required-env-vars. Falls back to
     * {@code us-east-1} only as a last resort &mdash; in production the
     * region MUST be explicitly set via {@code AWS_REGION}.
     */
    @Value("${spring.cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String regionString;

    /**
     * Optional endpoint override (used by the {@code local} profile to redirect
     * every SDK call to {@code http://localhost:4566} where LocalStack listens).
     * Empty in {@code dev} / {@code prod} so the SDK performs default
     * per-service endpoint resolution against real AWS endpoints.
     */
    @Value("${spring.cloud.aws.endpoint:}")
    private String endpointOverride;

    /**
     * Static access key for the {@code local} profile (LocalStack accepts
     * {@code test} / {@code test} or any non-empty pair). Empty in
     * {@code dev} / {@code prod}, where {@link DefaultCredentialsProvider}
     * resolves credentials from the ECS task role chain instead.
     */
    @Value("${spring.cloud.aws.credentials.access-key:}")
    private String accessKey;

    /**
     * Static secret key for the {@code local} profile. See {@link #accessKey}
     * for rationale.
     */
    @Value("${spring.cloud.aws.credentials.secret-key:}")
    private String secretKey;

    /**
     * S3 path-style addressing flag. Must be {@code true} for LocalStack
     * (which does not support virtual-host style by default). For real AWS S3,
     * leave at {@code false} (the default) so the client uses virtual-host
     * style, which is recommended for performance and required for certain
     * features (Transfer Acceleration). Toggled per profile.
     */
    @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}")
    private boolean s3PathStyleAccessEnabled;

    // -----------------------------------------------------------------------
    // Shared building blocks: Region, CredentialsProvider, endpoint helper
    // -----------------------------------------------------------------------

    /**
     * Exposes the AWS {@link Region} as a Spring bean for downstream consumers
     * (e.g., {@code OpenSearchConfig} signing with SigV4 needs the configured
     * region). Each SDK-client builder below also calls {@link #awsRegion()}
     * directly to ensure a consistent region across all clients.
     *
     * <p>Replaces: mainframe {@code SYS1.PARMLIB} region / LPAR designation
     * that scoped which physical machine a workload ran on.</p>
     *
     * @return the configured AWS {@link Region} (e.g., {@code us-east-1})
     */
    @Bean
    public Region awsRegion() {
        // COBOL replacement: SYS1.PARMLIB region / LPAR designation
        return Region.of(regionString);
    }

    /**
     * Provides the singleton {@link AwsCredentialsProvider} used by every AWS
     * SDK client in this configuration plus any other config that needs to sign
     * AWS requests (OpenSearch SigV4, Secrets Manager rotation polling, etc.).
     *
     * <p>Selection logic:</p>
     * <ul>
     *   <li>If both {@code spring.cloud.aws.credentials.access-key} and
     *       {@code spring.cloud.aws.credentials.secret-key} are non-blank
     *       (typically the {@code local} profile pointing at LocalStack),
     *       a {@link StaticCredentialsProvider} carrying those literal values
     *       is returned. LocalStack accepts {@code test}/{@code test} or any
     *       non-empty pair and signs no real requests with them.</li>
     *   <li>Otherwise (typically {@code dev} / {@code prod} on ECS Fargate),
     *       {@link DefaultCredentialsProvider#create()} is returned. The
     *       default provider resolves credentials in this order:
     *       <ol>
     *         <li>System properties ({@code aws.accessKeyId},
     *             {@code aws.secretAccessKey})</li>
     *         <li>Environment variables ({@code AWS_ACCESS_KEY_ID},
     *             {@code AWS_SECRET_ACCESS_KEY})</li>
     *         <li>Web-identity token from environment
     *             ({@code AWS_WEB_IDENTITY_TOKEN_FILE} &mdash; used by IRSA)</li>
     *         <li>Shared profile file ({@code ~/.aws/credentials})</li>
     *         <li>ECS task role
     *             ({@code AWS_CONTAINER_CREDENTIALS_RELATIVE_URI}) &mdash;
     *             the normal path on ECS Fargate</li>
     *         <li>IAM instance profile (EC2)</li>
     *       </ol>
     *       The provider caches the resolved credentials and refreshes them
     *       automatically before expiry, so ECS task-role rotations require
     *       no Spring context refresh.</li>
     * </ul>
     *
     * <p>Replaces: RACF identity propagation and {@code USRSEC} VSAM file
     * credentials that authenticated mainframe users and batch jobs.</p>
     *
     * @return the configured credentials provider (singleton scope)
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        // COBOL replacement: RACF identity + USRSEC credentials
        if (accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    /**
     * Parses {@link #endpointOverride} into a {@link URI} or returns
     * {@code null} when no override is configured. Centralised so every
     * client builder applies the same logic, guaranteeing uniform behaviour
     * across all SDK calls per profile.
     *
     * @return the parsed override URI, or {@code null} if absent or blank
     */
    private URI endpointOverrideUri() {
        if (endpointOverride == null || endpointOverride.isBlank()) {
            return null;
        }
        return URI.create(endpointOverride);
    }

    // -----------------------------------------------------------------------
    // Service clients
    // -----------------------------------------------------------------------

    /**
     * Creates the singleton {@link S3Client} used by
     * {@code adapter/S3OutputService} and any other component that performs
     * S3 object reads/writes.
     *
     * <p><b>Replaces:</b> COBOL sequential file {@code WRITE} statements that
     * produced the {@code DALYREJS}, {@code SYSTRAN}, {@code TRANREPT}, and
     * {@code STMTFILE} GDG outputs, plus {@code IEBGENER} copy operations
     * (per AAP &sect;0.4.1). S3 objects produced by adapters are encrypted at
     * rest with SSE-KMS using the customer-managed key referenced by
     * {@code carddemo.kms.key-arn} (AAP &sect;0.6.6).</p>
     *
     * <p>Path-style addressing is toggled by
     * {@code spring.cloud.aws.s3.path-style-access-enabled}: enabled for
     * LocalStack, disabled (virtual-host style) for real AWS S3.</p>
     *
     * @return the configured singleton S3 client
     */
    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        // COBOL replacement: WRITE TO DALYREJS / SYSTRAN / TRANREPT GDG generations
        // and IEBGENER copy operations per AAP §0.4.1
        var builder = S3Client.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3PathStyleAccessEnabled)
                        .build());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link SfnClient} used by
     * {@code adapter/StepFunctionsOrchestrator} to start executions of the
     * Step Functions state machines defined under
     * {@code src/main/resources/stepfunctions/}.
     *
     * <p><b>Replaces:</b> JES2 + JCL job-stream submission. The end-of-day
     * batch pipeline (POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr;
     * Parallel(CREASTMT, TRANREPT)) is now executed by the
     * {@code eod-batch-pipeline.asl.json} state machine and provisioning
     * flows by {@code file-provisioning.asl.json} (per AAP &sect;0.6.3).
     * The {@code StartExecution} API is invoked by
     * {@code adapter/StepFunctionsOrchestrator} on behalf of online services
     * such as {@code ReportSubmissionService}.</p>
     *
     * @return the configured singleton Step Functions client
     */
    @Bean(destroyMethod = "close")
    public SfnClient sfnClient() {
        // COBOL replacement: JES2 + JCL job stream submission per AAP §0.6.3
        var builder = SfnClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link SecretsManagerClient} used by
     * {@code adapter/SecretsManagerService} for explicit, runtime secret
     * fetches.
     *
     * <p>Spring Cloud AWS handles declarative imports via
     * {@code spring.config.import: aws-secretsmanager:...} separately &mdash;
     * those values become Spring {@code Environment} properties at startup.
     * This client is used for runtime secret fetches that are not pre-loaded
     * as Spring properties, such as the JWT signing key looked up by
     * {@code JwtTokenProvider} on each token issuance.</p>
     *
     * <p><b>Replaces:</b> file-based credentials embedded in mainframe
     * {@code PARM} datasets and {@code USRSEC} VSAM (per AAP &sect;0.6.4).</p>
     *
     * @return the configured singleton Secrets Manager client
     */
    @Bean(destroyMethod = "close")
    public SecretsManagerClient secretsManagerClient() {
        // COBOL replacement: file-based credentials in mainframe PARM datasets
        // per AAP §0.6.4
        var builder = SecretsManagerClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link CloudWatchAsyncClient} used by
     * {@code config/CloudWatchConfig} to publish Micrometer metrics under
     * the {@code CardDemo} namespace.
     *
     * <p>The async client variant is chosen because metric publication is
     * inherently non-blocking and Micrometer's CloudWatch registry batches
     * datapoints asynchronously on a scheduled flush interval.</p>
     *
     * <p><b>Replaces:</b> SDSF / RMF mainframe metric collection &mdash;
     * application metrics are now exported to CloudWatch with dimensions
     * for service, environment, and instance (AAP &sect;0.6.6).</p>
     *
     * @return the configured singleton CloudWatch async client
     */
    @Bean(destroyMethod = "close")
    public CloudWatchAsyncClient cloudWatchAsyncClient() {
        // COBOL replacement: SDSF / RMF metric capture per AAP §0.6.6
        CloudWatchAsyncClientBuilder builder = CloudWatchAsyncClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link KmsClient} used by adapters that need to
     * perform explicit envelope encryption beyond bucket-level SSE-KMS (for
     * example, field-level encryption of PII attributes or signing of audit
     * payloads).
     *
     * <p>Per AAP &sect;0.6.6, KMS customer-managed keys (CMKs) encrypt all
     * data at rest (RDS, S3, ElastiCache, CloudWatch Logs) &mdash; those
     * integrations are configured at the service level (e.g., RDS parameter
     * group, S3 bucket SSE-KMS config) and do not require this client. This
     * client is therefore reserved for adapter code performing explicit
     * envelope-encryption operations.</p>
     *
     * <p><b>Replaces:</b> ICSF cryptographic services plus RACF key
     * management on z/OS (per AAP &sect;0.6.6).</p>
     *
     * @return the configured singleton KMS client
     */
    @Bean(destroyMethod = "close")
    public KmsClient kmsClient() {
        // COBOL replacement: ICSF cryptographic services + RACF key management
        // per AAP §0.6.6
        var builder = KmsClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link GlueClient} used by
     * {@code glue/GlueETLConfig} to submit Glue ETL jobs.
     *
     * <p><b>Replaces:</b> {@code IDCAMS REPRO} + {@code IEBGENER} +
     * {@code DFSORT} for bulk-load scenarios from S3 to RDS (for example,
     * seed-data loading and statement-archive ETL) per AAP &sect;0.4.1.</p>
     *
     * @return the configured singleton Glue client
     */
    @Bean(destroyMethod = "close")
    public GlueClient glueClient() {
        // COBOL replacement: IDCAMS REPRO + DFSORT in COMBTRAN-style bulk ETL
        // per AAP §0.4.1
        var builder = GlueClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link SqsClient} used by
     * {@code config/SecretsManagerConfig.pollRotationEvents()} to consume
     * Secrets Manager rotation notifications.
     *
     * <p>The rotation Lambda publishes to an SNS topic on successful
     * rotation; the SNS topic fans out to an SQS queue that this client
     * polls. On receipt of a rotation message, the listener publishes a
     * Spring {@code RefreshEvent} so that {@code @RefreshScope} beans
     * (notably the JDBC datasource HikariCP pool and the Kafka producer/
     * consumer factories) re-read the rotated credentials without a JVM
     * restart (per AAP &sect;0.6.4).</p>
     *
     * <p><b>Replaces:</b> CICS Transient Data Queues (TDQs) used for
     * infrastructure event consumption. Note: the primary replacement for
     * TDQs that carried business events is Kafka topics on MSK
     * (AAP &sect;0.6.5); SQS is used specifically here because rotation
     * notifications are infrastructural, low-volume, and naturally
     * point-to-point.</p>
     *
     * @return the configured singleton SQS client
     */
    @Bean(destroyMethod = "close")
    public SqsClient sqsClient() {
        // COBOL replacement: CICS TDQ for infrastructure event consumption
        // per AAP §0.6.4
        var builder = SqsClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }

    /**
     * Creates the singleton {@link SnsClient} used by adapters that need to
     * fan out infrastructure notifications (for example, Macie finding
     * alerts and operational alarm escalation).
     *
     * <p>This client is NOT used for core inter-service business events
     * &mdash; per AAP &sect;0.6.5 the primary inter-service event bus is
     * Apache Kafka on Amazon MSK (topics
     * {@code transaction.posted},
     * {@code account.updated}, {@code ledger.balanced},
     * {@code report.requested}). SNS is reserved for infrastructure
     * notifications that benefit from SNS's protocol fan-out (email, SMS,
     * Lambda, SQS) and that do not require Kafka's per-partition ordering
     * guarantees.</p>
     *
     * <p><b>Replaces:</b> the mainframe {@code TSO SEND} command (and
     * automated equivalents through NetView / AOC) used for operator
     * notifications.</p>
     *
     * @return the configured singleton SNS client
     */
    @Bean(destroyMethod = "close")
    public SnsClient snsClient() {
        // COBOL replacement: TSO SEND command for operator notifications
        var builder = SnsClient.builder()
                .region(awsRegion())
                .credentialsProvider(awsCredentialsProvider());

        URI override = endpointOverrideUri();
        if (override != null) {
            builder.endpointOverride(override);
        }
        return builder.build();
    }
}
