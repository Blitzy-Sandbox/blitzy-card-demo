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
 * AWS SDK v2 client configuration replacing the CardDemo mainframe I/O substrates: S3 for
 * GDG/batch file staging, SQS FIFO for the CICS TDQ report bridge, and SNS for notifications.
 * Resource lineage references {@code app/jcl/DEFGDGB.jcl} at source commit {@code 27d6c6f}
 * (REFERENCE ONLY; COBOL/JCL is not copied). Clients target LocalStack for the {@code local} and
 * {@code test} profiles (endpoint + path-style overrides) and real AWS regional endpoints in the
 * base profile, with connection settings resolved from the environment via {@code spring.cloud.aws.*}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsConfig.CardDemoAwsProperties.class)
public class AwsConfig {

    /**
     * Returns {@code true} when an explicit AWS endpoint override is configured (LocalStack for
     * local/test). When blank, the SDK resolves the standard regional endpoint (real AWS).
     *
     * @param endpoint the configured {@code spring.cloud.aws.endpoint} value (may be blank)
     * @return whether an endpoint override should be applied to the client builders
     */
    private boolean hasEndpoint(String endpoint) {
        return StringUtils.hasText(endpoint);
    }

    /**
     * Synchronous {@link S3Client} for batch staging, statement, report, and rejection objects.
     * Path-style access is enabled for LocalStack to avoid virtual-host bucket DNS that does not
     * resolve locally.
     *
     * @param region          AWS region identifier
     * @param accessKey       AWS access key (non-secret LocalStack dummy by default)
     * @param secretKey       AWS secret key (non-secret LocalStack dummy by default)
     * @param endpoint        optional endpoint override (LocalStack {@code :4566}); blank for real AWS
     * @param pathStyleAccess whether S3 path-style addressing is enabled (true for LocalStack)
     * @return a configured synchronous S3 client
     */
    @Bean
    S3Client s3Client(@Value("${spring.cloud.aws.region.static:us-east-1}") String region,
                      @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
                      @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
                      @Value("${spring.cloud.aws.endpoint:}") String endpoint,
                      @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}") boolean pathStyleAccess) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build());
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Asynchronous {@link SqsAsyncClient} backing the report-submission queue (CICS TDQ
     * replacement). The asynchronous variant is mandated so the higher-level {@code @SqsListener}
     * container factory and {@code SqsTemplate} consume this client; there is no synchronous
     * {@code SqsClient} bean.
     *
     * @param region    AWS region identifier
     * @param accessKey AWS access key (non-secret LocalStack dummy by default)
     * @param secretKey AWS secret key (non-secret LocalStack dummy by default)
     * @param endpoint  optional endpoint override (LocalStack {@code :4566}); blank for real AWS
     * @return a configured asynchronous SQS client
     */
    @Bean
    SqsAsyncClient sqsAsyncClient(@Value("${spring.cloud.aws.region.static:us-east-1}") String region,
                                  @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
                                  @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
                                  @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Synchronous {@link SnsClient} for notification fan-out.
     *
     * @param region    AWS region identifier
     * @param accessKey AWS access key (non-secret LocalStack dummy by default)
     * @param secretKey AWS secret key (non-secret LocalStack dummy by default)
     * @param endpoint  optional endpoint override (LocalStack {@code :4566}); blank for real AWS
     * @return a configured synchronous SNS client
     */
    @Bean
    SnsClient snsClient(@Value("${spring.cloud.aws.region.static:us-east-1}") String region,
                        @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
                        @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
                        @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Typed, constructor-bound holder for the CardDemo AWS resource names, centralizing the values
     * defined in {@code application*.yml}, {@code docker-compose.yml}, and
     * {@code localstack-init/init-aws.sh}. Relaxed binding maps kebab-case keys (for example
     * {@code carddemo.aws.s3.bucket-input}) to the record accessors.
     *
     * @param s3  S3 bucket names
     * @param sqs SQS queue names
     * @param sns SNS topic names
     */
    @ConfigurationProperties("carddemo.aws")
    public record CardDemoAwsProperties(S3 s3, Sqs sqs, Sns sns) {

        /**
         * S3 bucket names for batch input, batch output, and statement objects.
         *
         * @param bucketInput      batch input bucket (for example {@code carddemo-batch-input})
         * @param bucketOutput     batch output bucket (for example {@code carddemo-batch-output})
         * @param bucketStatements statement bucket (for example {@code carddemo-statements})
         */
        public record S3(String bucketInput, String bucketOutput, String bucketStatements) {
        }

        /**
         * SQS queue names.
         *
         * @param reportQueue report-submission FIFO queue (for example {@code carddemo-report-jobs.fifo})
         */
        public record Sqs(String reportQueue) {
        }

        /**
         * SNS topic names.
         *
         * @param notificationsTopic notification topic (for example {@code carddemo-notifications})
         */
        public record Sns(String notificationsTopic) {
        }
    }
}
