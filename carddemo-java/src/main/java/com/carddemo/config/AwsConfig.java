package com.carddemo.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/**
 * AWS SDK v2 client configuration for CardDemo: the synchronous {@link S3Client} (GDG / batch
 * file staging and statement/report output), the asynchronous {@link SqsAsyncClient} (the CICS
 * TDQ report bridge, FIFO), and the synchronous {@link SnsClient} (notification fan-out), plus a
 * typed {@link CardDemoAwsProperties} holder for the CardDemo resource names. Clients target
 * LocalStack for the {@code local}/{@code test} profiles (path-style S3, {@code :4566} endpoint)
 * and real AWS regional endpoints in the base profile via environment-supplied settings.
 *
 * <p>Lineage (reference only): derived from {@code app/jcl/DEFGDGB.jcl} at source commit
 * {@code 27d6c6f}; no COBOL/JCL is copied. Rationale is recorded in {@code DECISION_LOG.md}.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsConfig.CardDemoAwsProperties.class)
public class AwsConfig {

    /**
     * Synchronous Amazon S3 client for GDG / batch file staging and statement/report output.
     * Path-style access is enabled so LocalStack bucket addressing resolves without virtual-host
     * DNS; the endpoint is overridden only when {@code spring.cloud.aws.endpoint} is set
     * (LocalStack), otherwise the real AWS regional endpoint is used.
     */
    @Bean
    S3Client s3Client(
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
            @Value("${spring.cloud.aws.endpoint:}") String endpoint,
            @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}") boolean pathStyleAccess) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Asynchronous Amazon SQS client backing the report-submission FIFO queue (the CICS TDQ
     * replacement) that {@code @SqsListener} consumers read from and the report service publishes
     * to. By design this is the only SQS bean; no synchronous {@code SqsClient} is defined.
     */
    @Bean
    SqsAsyncClient sqsAsyncClient(
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
            @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Synchronous Amazon SNS client used for notification fan-out. The endpoint is overridden only
     * when {@code spring.cloud.aws.endpoint} is set (LocalStack), otherwise the real AWS regional
     * endpoint is used.
     */
    @Bean
    SnsClient snsClient(
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
            @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Returns {@code true} when an explicit AWS endpoint override is configured (LocalStack),
     * keeping the optional {@code endpointOverride} application DRY across the client beans.
     */
    private boolean hasEndpoint(String endpoint) {
        return StringUtils.hasText(endpoint);
    }

    /**
     * Typed, constructor-bound holder for the CardDemo AWS resource names, centralizing the S3
     * bucket, SQS queue, and SNS topic identifiers that batch jobs and services inject rather than
     * scattering {@code @Value} strings. Values are supplied by {@code application*.yml} (relaxed
     * binding maps, for example, {@code carddemo.aws.s3.bucket-input} to {@code s3().bucketInput()}).
     */
    @ConfigurationProperties("carddemo.aws")
    public record CardDemoAwsProperties(S3 s3, Sqs sqs, Sns sns) {

        /** S3 bucket names for batch input, batch output, and generated statements. */
        public record S3(String bucketInput, String bucketOutput, String bucketStatements) {
        }

        /** SQS FIFO queue name for the report-submission bridge (CICS TDQ replacement). */
        public record Sqs(String reportQueue) {
        }

        /** SNS topic name for notification fan-out. */
        public record Sns(String notificationsTopic) {
        }
    }
}
