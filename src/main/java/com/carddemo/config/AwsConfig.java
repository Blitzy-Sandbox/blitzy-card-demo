package com.carddemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AWS assembly configuration for the CardDemo migration.
 *
 * <p>This class owns exactly one responsibility: binding the <em>logical</em> AWS
 * resource names used by the migrated workload as a single, type-safe, immutable
 * configuration object ({@link CardDemoAwsProperties}). It deliberately does
 * <strong>not</strong> create any AWS SDK clients or Spring Cloud AWS templates &mdash;
 * those are provided by Spring Cloud AWS 3.3.0 auto-configuration and are consumed
 * directly by the service, batch, and observability layers.</p>
 *
 * <h2>Legacy mainframe I/O mapped onto AWS</h2>
 * <ul>
 *   <li><strong>GDG generations &rarr; versioned S3 objects.</strong> The batch pipeline's
 *       generation-data-group datasets &mdash; for example the rejected-transactions
 *       generation {@code AWS.M2.CARDDEMO.DALYREJS(+1)} written by {@code POSTTRAN} /
 *       {@code CBTRN02C}, and the statement outputs {@code AWS.M2.CARDDEMO.STATEMNT.HTML} /
 *       {@code .PS} written by {@code CREASTMT} / {@code CBSTM03A} &mdash; become versioned
 *       objects in the S3 buckets bound below.</li>
 *   <li><strong>CICS TDQ &rarr; JES report bridge &rarr; SQS FIFO queue.</strong> The online
 *       reporting program {@code CORPT00C} submits a batch job by writing JCL to an
 *       extra-partition Transient Data Queue ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}).
 *       That order-preserving, fire-and-forget bridge becomes the SQS FIFO queue bound below,
 *       which triggers the equivalent Spring Batch report launch.</li>
 * </ul>
 *
 * <h2>Where the runtime wiring lives (not here)</h2>
 * <p>The AWS endpoint override, region, credentials, and S3 path-style access are supplied
 * entirely through the {@code spring.cloud.aws.*} properties in the profile-scoped
 * {@code application-local.yml} / {@code application-test.yml} resources, which point every
 * interaction at LocalStack. No endpoint URL, region, ARN, or credential is hardcoded in this
 * file (zero live AWS; {@code LOCALSTACK_AUTH_TOKEN} is environment-supplied only).</p>
 *
 * <p>Rationale and alternatives for the &quot;bind logical names here, let the framework build
 * the clients&quot; decision are recorded in {@code docs/decision-log.md} (design decision&nbsp;#6),
 * not in code comments. Source traceability anchor: commit SHA {@code 27d6c6f}.</p>
 *
 * @see CardDemoAwsProperties
 */
@Configuration
@EnableConfigurationProperties(AwsConfig.CardDemoAwsProperties.class)
public class AwsConfig {

    /*
     * Intentionally no @Bean methods are declared in this class.
     *
     * S3Client, S3Template, SqsAsyncClient, SqsTemplate, SnsClient, and SnsTemplate are all
     * auto-configured by Spring Cloud AWS 3.3.0 from the spring.cloud.aws.* properties and are
     * injected directly by their consumers:
     *   - service.ReportService          -> SqsTemplate (report-queue launch)
     *   - batch.* writers                -> S3Template  (bucket staging; injected directly to
     *                                       avoid a batch -> config dependency cycle)
     *   - observability.HealthIndicators -> ObjectProvider<S3Client> / ObjectProvider<SqsAsyncClient>
     *
     * Re-declaring any of those beans here would create duplicate/conflicting definitions and
     * break LocalStack endpoint resolution, so they are deliberately omitted. Endpoint, region,
     * and credentials are YAML-driven (LocalStack) and never appear in code.
     */

    /**
     * Type-safe binding of the logical AWS resource names under the {@code carddemo.aws}
     * configuration prefix.
     *
     * <p>Component names use camelCase and bind to the kebab-case YAML keys through Spring's
     * relaxed binding (for example {@code input-bucket} &harr; {@code inputBucket}). The concrete
     * values are defined in the resource profiles; this record is the single source of truth for
     * the logical-resource contract and is available for injection wherever type-safe access is
     * preferred over ad-hoc {@code @Value} lookups.</p>
     *
     * <p>Region is intentionally absent: it is owned by the framework property
     * {@code spring.cloud.aws.region.static} and must not be duplicated here.</p>
     *
     * @param s3  the S3 bucket names that back the migrated GDG / sequential batch datasets
     * @param sqs the SQS FIFO queue that backs the migrated CICS TDQ report bridge
     */
    @ConfigurationProperties(prefix = "carddemo.aws")
    public record CardDemoAwsProperties(S3 s3, Sqs sqs) {

        /**
         * Logical S3 bucket names (legacy GDG generations &rarr; versioned S3 objects).
         *
         * @param inputBucket      staging bucket for batch <em>input</em> datasets such as the
         *                         daily-transaction file consumed by {@code POSTTRAN} /
         *                         {@code CBTRN02C}; YAML key {@code carddemo.aws.s3.input-bucket}
         *                         (logical name {@code carddemo-batch-input})
         * @param outputBucket     staging bucket for batch <em>output</em> datasets such as the
         *                         rejected-transactions GDG {@code AWS.M2.CARDDEMO.DALYREJS(+1)};
         *                         YAML key {@code carddemo.aws.s3.output-bucket} (logical name
         *                         {@code carddemo-batch-output})
         * @param statementsBucket bucket for generated statements
         *                         ({@code AWS.M2.CARDDEMO.STATEMNT.HTML} / {@code .PS} produced by
         *                         {@code CREASTMT} / {@code CBSTM03A}); YAML key
         *                         {@code carddemo.aws.s3.statements-bucket} (logical name
         *                         {@code carddemo-statements})
         */
        public record S3(String inputBucket, String outputBucket, String statementsBucket) {
        }

        /**
         * Logical SQS FIFO queue name (legacy CICS TDQ &rarr; JES report bridge &rarr; SQS FIFO).
         *
         * @param reportQueue the FIFO queue that receives report-generation requests &mdash; the
         *                    modern equivalent of {@code CORPT00C}'s
         *                    {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} &mdash; and triggers the
         *                    Spring Batch report job; YAML key
         *                    {@code carddemo.aws.sqs.report-queue} (logical name
         *                    {@code carddemo-report-jobs.fifo})
         */
        public record Sqs(String reportQueue) {
        }
    }
}
