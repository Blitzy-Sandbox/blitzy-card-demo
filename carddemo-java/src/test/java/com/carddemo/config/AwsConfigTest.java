package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.config.AwsConfig.CardDemoAwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Unit tests for {@link AwsConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; lineage {@code app/jcl/DEFGDGB.jcl}; source commit
 * {@code 27d6c6f}): verifies the synchronous S3 and SNS client factory beans build successfully
 * both with an explicit endpoint override (the LocalStack path that drives
 * {@code hasEndpoint == true}) and without one (the real-AWS path that drives
 * {@code hasEndpoint == false}), and that the typed {@link CardDemoAwsProperties} holder exposes
 * its nested resource-name accessors. Clients are built with explicit region and static test
 * credentials so no network or AWS metadata lookup occurs, and each client is closed promptly.</p>
 *
 * <p>The asynchronous {@code sqsAsyncClient} bean is intentionally exercised by the LocalStack
 * integration tests rather than here: constructing it eagerly initializes the Netty NIO transport,
 * which emits a {@code sun.misc.Unsafe} deprecation warning on Java 25 that would violate the
 * zero-warning build gate. Its body is structurally identical to {@code snsClient} (region,
 * credentials, and the same {@code hasEndpoint} override branch), which is covered above.</p>
 */
@DisplayName("AwsConfig - S3/SQS/SNS client beans and properties")
class AwsConfigTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String ENDPOINT = "http://localhost:4566";

    private final AwsConfig awsConfig = new AwsConfig();

    @Nested
    @DisplayName("S3 client")
    class S3 {

        @Test
        @DisplayName("Builds with a LocalStack endpoint and path-style access")
        void buildsWithEndpoint() {
            try (S3Client client =
                    awsConfig.s3Client(REGION, ACCESS_KEY, SECRET_KEY, ENDPOINT, true)) {
                assertThat(client).isNotNull();
            }
        }

        @Test
        @DisplayName("Builds without an endpoint override")
        void buildsWithoutEndpoint() {
            try (S3Client client =
                    awsConfig.s3Client(REGION, ACCESS_KEY, SECRET_KEY, "", false)) {
                assertThat(client).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("SNS client")
    class Sns {

        @Test
        @DisplayName("Builds with a LocalStack endpoint")
        void buildsWithEndpoint() {
            try (SnsClient client =
                    awsConfig.snsClient(REGION, ACCESS_KEY, SECRET_KEY, ENDPOINT)) {
                assertThat(client).isNotNull();
            }
        }

        @Test
        @DisplayName("Builds without an endpoint override")
        void buildsWithoutEndpoint() {
            try (SnsClient client =
                    awsConfig.snsClient(REGION, ACCESS_KEY, SECRET_KEY, "")) {
                assertThat(client).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("CardDemoAwsProperties holder")
    class Properties {

        @Test
        @DisplayName("Exposes nested S3/SQS/SNS resource names")
        void exposesNestedNames() {
            CardDemoAwsProperties props = new CardDemoAwsProperties(
                    new CardDemoAwsProperties.S3(
                            "carddemo-batch-input", "carddemo-batch-output", "carddemo-statements"),
                    new CardDemoAwsProperties.Sqs("carddemo-report-jobs.fifo"),
                    new CardDemoAwsProperties.Sns("carddemo-notifications"));

            assertThat(props.s3().bucketInput()).isEqualTo("carddemo-batch-input");
            assertThat(props.s3().bucketOutput()).isEqualTo("carddemo-batch-output");
            assertThat(props.s3().bucketStatements()).isEqualTo("carddemo-statements");
            assertThat(props.sqs().reportQueue()).isEqualTo("carddemo-report-jobs.fifo");
            assertThat(props.sns().notificationsTopic()).isEqualTo("carddemo-notifications");
        }
    }
}
