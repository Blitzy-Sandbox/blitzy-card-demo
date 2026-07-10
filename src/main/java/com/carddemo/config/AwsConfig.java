package com.carddemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

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
     * Exactly ONE @Bean is declared here: a customized SqsTemplate (see below). Every other AWS
     * client/template is left to Spring Cloud AWS 3.3.0 auto-configuration and injected directly
     * by its consumer:
     *   - batch.* writers                -> S3Template  (bucket staging; injected directly to
     *                                       avoid a batch -> config dependency cycle)
     *   - observability.HealthIndicators -> ObjectProvider<S3Client> / ObjectProvider<SqsAsyncClient>
     *
     * S3Client, S3Template, SqsAsyncClient, SnsClient, and SnsTemplate are NOT re-declared here:
     * doing so would create duplicate/conflicting definitions and break LocalStack endpoint
     * resolution. Endpoint, region, and credentials remain YAML-driven (LocalStack) and never
     * appear in code. The SqsAsyncClient injected into the SqsTemplate bean below is itself the
     * auto-configured, LocalStack-pointed client — this class only re-wraps it in a template whose
     * default converter is adjusted.
     */

    /**
     * Customized {@link SqsTemplate} for the online report-launch producer
     * ({@code service.ReportService} &rarr; {@code batch.ReportJobLauncher} over the FIFO queue
     * {@code carddemo-report-jobs.fifo}).
     *
     * <p><strong>Why this bean exists (message-contract fix).</strong> The Spring Cloud AWS
     * auto-configured {@code SqsTemplate} stamps a payload-<em>type</em> header
     * ({@code JavaType=...ReportService$ReportJobMessage}, alongside
     * {@code contentType=application/json}) on every outbound message. The batch consumer
     * {@code ReportJobLauncher} deliberately receives the message as a raw JSON {@code String} and
     * parses it itself (so it can apply {@code @JsonIgnoreProperties} tolerance and typed
     * error handling). With the type header present, the consumer's default
     * {@code SqsMessagingMessageConverter} first materializes the body into the typed record and
     * then, to satisfy the {@code String} parameter, renders it via {@code toString()} &mdash;
     * yielding {@code "ReportJobMessage[jobId=...]"}, which is <em>not</em> JSON and fails the
     * manual parse on every delivery, so the report job never launches and the correlation id
     * never reaches the batch logs.</p>
     *
     * <p>This template disables that header via
     * {@link io.awspring.cloud.sqs.support.converter.AbstractMessagingMessageConverter#doNotSendPayloadTypeHeader()},
     * so the producer sends the record as raw JSON with no type header. The consumer then receives
     * the exact JSON string it expects and parses it into its tolerant request record. Only the
     * outbound serialization is affected; the auto-configured {@link SqsAsyncClient} (and its
     * LocalStack endpoint/region/credentials) is reused unchanged. Because the auto-configured
     * {@code SqsTemplate} is {@code @ConditionalOnMissingBean}, declaring this bean makes Spring
     * Cloud AWS back off and use this one. Full rationale and the considered alternatives (typed
     * listener parameter; unified shared DTO) are recorded in {@code docs/decision-log.md}, not
     * inline, per the Explainability rule.</p>
     *
     * @param sqsAsyncClient       the auto-configured, LocalStack-pointed async SQS client
     *                             (never {@code null})
     * @param objectMapperProvider provider for the application {@link ObjectMapper}; when present it
     *                             is applied to the converter so date/JSON serialization matches the
     *                             rest of the application exactly (mirrors the auto-configuration)
     * @return a {@link SqsTemplate} whose default converter does not emit the payload-type header
     */
    @Bean
    SqsTemplate sqsTemplate(final SqsAsyncClient sqsAsyncClient,
                            final ObjectProvider<ObjectMapper> objectMapperProvider) {
        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configureDefaultConverter(converter -> {
                    // Reuse the application ObjectMapper (e.g. JavaTimeModule) when available so
                    // outbound JSON is byte-identical to what the auto-configuration would produce,
                    // except for the omitted payload-type header.
                    objectMapperProvider.ifAvailable(converter::setObjectMapper);
                    // F-1 fix: never stamp the JavaType payload-type header, so the consumer's
                    // String parameter receives raw JSON rather than a toString()-rendered record.
                    converter.doNotSendPayloadTypeHeader();
                })
                .build();
    }

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
