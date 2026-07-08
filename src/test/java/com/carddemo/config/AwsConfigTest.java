package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Fast, dependency-free unit test for {@link AwsConfig}.
 *
 * <p>The test exercises the two responsibilities of the AWS assembly configuration
 * <em>in isolation</em>, without a full Spring Boot context, a database, a network,
 * or LocalStack:</p>
 * <ol>
 *   <li><strong>Type-safe binding of the logical AWS resource names.</strong> The nested
 *       {@code @ConfigurationProperties(prefix = "carddemo.aws")} record
 *       {@link AwsConfig.CardDemoAwsProperties} must bind every logical resource name to a
 *       non-null value through Spring's relaxed (kebab-case &harr; camelCase) binding: the three
 *       S3 buckets {@code carddemo-batch-input} / {@code carddemo-batch-output} /
 *       {@code carddemo-statements} and the SQS FIFO queue {@code carddemo-report-jobs.fifo}.
 *       These fixed logical names are the single source of truth for the migrated batch/report
 *       plumbing, so the test asserts the exact values and fails loudly on any drift.</li>
 *   <li><strong>No hand-rolled AWS client beans.</strong> {@link AwsConfig} deliberately declares
 *       no {@code S3Client}, {@code SqsAsyncClient}, or {@code SnsClient} bean &mdash; those are
 *       supplied by Spring Cloud AWS 3.3.0 auto-configuration from the {@code spring.cloud.aws.*}
 *       properties, not by this class (AAP &sect;0.5.1 / &sect;0.7.7).</li>
 * </ol>
 *
 * <h2>Legacy anchors under test (source read-only @ SHA {@code 27d6c6f})</h2>
 * <ul>
 *   <li>{@code app/jcl/POSTTRAN.jcl}: the daily-transaction input dataset
 *       {@code AWS.M2.CARDDEMO.DALYTRAN.PS} and the rejected-transactions GDG generation
 *       {@code AWS.M2.CARDDEMO.DALYREJS(+1)} become versioned S3 objects in
 *       {@code carddemo-batch-input} / {@code carddemo-batch-output}.</li>
 *   <li>{@code app/jcl/CREASTMT.JCL}: the statement outputs {@code AWS.M2.CARDDEMO.STATEMNT.PS}
 *       and {@code .HTML} become versioned S3 objects in {@code carddemo-statements}.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl} paragraph {@code WIRTE-JOBSUB-TDQ}
 *       ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}): the extra-partition Transient Data Queue
 *       &rarr; JES report bridge becomes the SQS FIFO queue {@code carddemo-report-jobs.fifo}.</li>
 * </ul>
 *
 * <h2>Why {@link ApplicationContextRunner}</h2>
 * <p>The runner (from {@code spring-boot-test}) builds a minimal application context containing
 * only what is explicitly registered &mdash; here just {@link AwsConfig} and the
 * {@code @EnableConfigurationProperties} bean it enables. It intentionally does <em>not</em> load
 * the Spring Cloud AWS auto-configurations, which is precisely what makes the client-bean-absence
 * assertion meaningful and keeps the suite fast and offline. The design rationale for the
 * &quot;bind logical names here, let the framework build the clients&quot; decision lives in
 * {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("AwsConfig — logical AWS resource-name binding and absence of hand-rolled client beans")
class AwsConfigTest {

    /**
     * The canonical {@code carddemo.aws.*} property values, expressed with kebab-case keys to
     * prove Spring's relaxed binding (for example {@code input-bucket} &harr; {@code inputBucket}).
     * Shared by both tests so the binding setup can never diverge between them.
     */
    private static final String[] LOGICAL_RESOURCE_PROPERTIES = {
            "carddemo.aws.s3.input-bucket=carddemo-batch-input",
            "carddemo.aws.s3.output-bucket=carddemo-batch-output",
            "carddemo.aws.s3.statements-bucket=carddemo-statements",
            "carddemo.aws.sqs.report-queue=carddemo-report-jobs.fifo"
    };

    /**
     * A minimal context runner that registers <strong>only</strong> {@link AwsConfig}. No
     * auto-configuration, database, or network is involved, so every run is fast and hermetic.
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(AwsConfig.class);

    @Test
    @DisplayName("CardDemoAwsProperties binds all logical resource names non-null with the fixed values")
    void bindsLogicalResourceNamesNonNull() {
        runner.withPropertyValues(LOGICAL_RESOURCE_PROPERTIES).run(context -> {
            // @EnableConfigurationProperties registers exactly one CardDemoAwsProperties bean.
            assertThat(context).hasSingleBean(AwsConfig.CardDemoAwsProperties.class);

            AwsConfig.CardDemoAwsProperties props =
                    context.getBean(AwsConfig.CardDemoAwsProperties.class);

            // The nested S3 record must be instantiated and carry the three fixed bucket names.
            assertThat(props.s3()).isNotNull();
            assertThat(props.s3().inputBucket()).isEqualTo("carddemo-batch-input");
            assertThat(props.s3().outputBucket()).isEqualTo("carddemo-batch-output");
            assertThat(props.s3().statementsBucket()).isEqualTo("carddemo-statements");

            // The nested SQS record must be instantiated and carry the fixed FIFO queue name.
            assertThat(props.sqs()).isNotNull();
            assertThat(props.sqs().reportQueue()).isEqualTo("carddemo-report-jobs.fifo");
        });
    }

    @Test
    @DisplayName("AwsConfig declares no S3Client, SqsAsyncClient, or SnsClient bean of its own")
    void declaresNoAwsClientBeans() {
        // Bind the same valid properties so context startup succeeds, then assert the negative.
        runner.withPropertyValues(LOGICAL_RESOURCE_PROPERTIES).run(context -> {
            // The runner registers ONLY AwsConfig (plus its @EnableConfigurationProperties bean)
            // and deliberately omits the Spring Cloud AWS auto-configurations. Therefore the
            // absence of these SDK v2 clients proves AwsConfig itself hand-defines none of them:
            // the running application obtains S3/SQS/SNS clients purely from Spring Cloud AWS 3.3.0
            // auto-configuration driven by spring.cloud.aws.* (AAP §0.5.1 / §0.7.7).
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(SqsAsyncClient.class);
            assertThat(context).doesNotHaveBean(SnsClient.class);
        });
    }
}
