package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

/**
 * Unit tests for {@link HealthIndicators}.
 *
 * <p>Traceability (REFERENCE-ONLY; no COBOL equivalent; source commit {@code 27d6c6f}): verifies
 * the PostgreSQL, S3, and SQS Actuator health indicators report UP on success, DOWN on failure,
 * and UNKNOWN when the dependency bean is absent. The defensive {@link ObjectProvider} injection is
 * supplied by a clean single-method anonymous implementation (no Mockito generic casts), and the
 * non-generic clients are mocked directly.</p>
 */
@DisplayName("HealthIndicators - PostgreSQL/S3/SQS Actuator health")
class HealthIndicatorsTest {

    private final HealthIndicators healthIndicators = new HealthIndicators();

    /** Clean, warning-free {@link ObjectProvider} whose {@code getIfAvailable()} returns the value. */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<T>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    @Nested
    @DisplayName("PostgreSQL indicator")
    class Postgres {

        @Test
        @DisplayName("UNKNOWN when no DataSource is configured")
        void unknownWhenAbsent() {
            HealthIndicator indicator = healthIndicators.postgresHealthIndicator(providerOf(null));

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        }

        @Test
        @DisplayName("UP with product/version when the connection validates")
        void upWhenValid() throws Exception {
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(metaData.getDatabaseProductVersion()).thenReturn("16.2");
            Connection connection = mock(Connection.class);
            when(connection.isValid(2)).thenReturn(true);
            when(connection.getMetaData()).thenReturn(metaData);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);

            Health health = healthIndicators.postgresHealthIndicator(providerOf(dataSource)).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("database", "PostgreSQL");
            assertThat(health.getDetails()).containsEntry("version", "16.2");
        }

        @Test
        @DisplayName("DOWN when connection validation fails")
        void downWhenInvalid() throws Exception {
            Connection connection = mock(Connection.class);
            when(connection.isValid(2)).thenReturn(false);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);

            Health health = healthIndicators.postgresHealthIndicator(providerOf(dataSource)).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("DOWN on SQLException")
        void downOnSqlException() throws Exception {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

            Health health = healthIndicators.postgresHealthIndicator(providerOf(dataSource)).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Nested
    @DisplayName("S3 indicator")
    class S3 {

        @Test
        @DisplayName("UNKNOWN when no S3Client is configured")
        void unknownWhenAbsent() {
            HealthIndicator indicator =
                    healthIndicators.s3HealthIndicator(providerOf(null), "carddemo-batch-input");

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        }

        @Test
        @DisplayName("UP with the bucket name on a successful HeadBucket")
        void upOnHeadBucket() {
            S3Client s3Client = mock(S3Client.class);

            Health health = healthIndicators
                    .s3HealthIndicator(providerOf(s3Client), "carddemo-batch-input").health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("bucket", "carddemo-batch-input");
        }

        @Test
        @DisplayName("DOWN on SdkException")
        void downOnSdkException() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(SdkException.create("simulated S3 failure", null));

            Health health = healthIndicators
                    .s3HealthIndicator(providerOf(s3Client), "carddemo-batch-input").health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("bucket", "carddemo-batch-input");
        }
    }

    @Nested
    @DisplayName("SQS indicator")
    class Sqs {

        @Test
        @DisplayName("UNKNOWN when no SqsAsyncClient is configured")
        void unknownWhenAbsent() {
            HealthIndicator indicator =
                    healthIndicators.sqsHealthIndicator(providerOf(null), "carddemo-report-jobs.fifo");

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        }

        @Test
        @DisplayName("UP with queue name and URL when the queue URL resolves")
        void upWhenQueueResolves() {
            GetQueueUrlResponse response = GetQueueUrlResponse.builder()
                    .queueUrl("http://localhost:4566/000000000000/carddemo-report-jobs.fifo")
                    .build();
            SqsAsyncClient sqsClient = mock(SqsAsyncClient.class);
            when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(response));

            Health health = healthIndicators
                    .sqsHealthIndicator(providerOf(sqsClient), "carddemo-report-jobs.fifo").health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("queue", "carddemo-report-jobs.fifo");
        }

        @Test
        @DisplayName("DOWN when the queue-URL future fails")
        void downWhenFutureFails() {
            SqsAsyncClient sqsClient = mock(SqsAsyncClient.class);
            when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("queue missing")));

            Health health = healthIndicators
                    .sqsHealthIndicator(providerOf(sqsClient), "carddemo-report-jobs.fifo").health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }
    }
}
