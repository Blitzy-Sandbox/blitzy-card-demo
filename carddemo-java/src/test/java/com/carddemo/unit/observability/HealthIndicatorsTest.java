package com.carddemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.observability.HealthIndicators;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

/**
 * Unit tests for {@link HealthIndicators}. Verifies the UP / DOWN / UNKNOWN
 * outcomes of the {@code postgres}, {@code s3}, and {@code sqs} actuator
 * indicators using a fully-typed {@link ObjectProvider} fake (so there is no
 * unchecked generic-mock warning under {@code -Werror}) and Mockito mocks for
 * the JDBC and AWS clients. Each probe must always return a {@link Health} and
 * never propagate an exception.
 */
class HealthIndicatorsTest {

    private static final String BUCKET = "carddemo-batch-input";
    private static final String QUEUE = "carddemo-report-jobs.fifo";

    private final HealthIndicators indicators = new HealthIndicators();

    /**
     * Minimal, fully-typed {@link ObjectProvider} returning a fixed instance
     * (or {@code null} to model an absent bean). Only {@code getIfAvailable()}
     * is exercised by the indicators; the other accessors are present solely to
     * satisfy the interface and are never invoked.
     */
    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }

    // ------------------------------------------------------------------
    // postgresHealthIndicator
    // ------------------------------------------------------------------

    @Test
    @DisplayName("postgres UP reports product name and version on a valid connection")
    void postgresUp() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("16.14");

        Health health = indicators.postgresHealthIndicator(new FixedProvider<>(ds)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("database", "PostgreSQL");
        assertThat(health.getDetails()).containsEntry("version", "16.14");
    }

    @Test
    @DisplayName("postgres DOWN when the connection fails validation")
    void postgresDownOnInvalid() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);

        Health health = indicators.postgresHealthIndicator(new FixedProvider<>(ds)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("database", "connection validation failed");
    }

    @Test
    @DisplayName("postgres DOWN when getConnection throws SQLException")
    void postgresDownOnSqlException() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));

        Health health = indicators.postgresHealthIndicator(new FixedProvider<>(ds)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("postgres UNKNOWN when no DataSource bean is available")
    void postgresUnknownWhenAbsent() {
        Health health = indicators.postgresHealthIndicator(new FixedProvider<>(null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("datasource", "not configured");
    }

    // ------------------------------------------------------------------
    // s3HealthIndicator
    // ------------------------------------------------------------------

    @Test
    @DisplayName("s3 UP when HeadBucket succeeds")
    void s3Up() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        Health health = indicators.s3HealthIndicator(new FixedProvider<>(s3), BUCKET).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", BUCKET);
    }

    @Test
    @DisplayName("s3 DOWN when HeadBucket raises an SDK exception")
    void s3Down() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.builder().message("unreachable").build());

        Health health = indicators.s3HealthIndicator(new FixedProvider<>(s3), BUCKET).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("bucket", BUCKET);
    }

    @Test
    @DisplayName("s3 UNKNOWN when no S3Client bean is available")
    void s3UnknownWhenAbsent() {
        Health health = indicators.s3HealthIndicator(new FixedProvider<>(null), BUCKET).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("s3", "not configured");
    }

    // ------------------------------------------------------------------
    // sqsHealthIndicator
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sqs UP reports queue name and resolved URL")
    void sqsUp() {
        SqsAsyncClient sqs = mock(SqsAsyncClient.class);
        String url = "http://localhost:4566/000000000000/" + QUEUE;
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueueUrlResponse.builder().queueUrl(url).build()));

        Health health = indicators.sqsHealthIndicator(new FixedProvider<>(sqs), QUEUE).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("queue", QUEUE);
        assertThat(health.getDetails()).containsEntry("queueUrl", url);
    }

    @Test
    @DisplayName("sqs DOWN when GetQueueUrl future completes exceptionally")
    void sqsDown() {
        SqsAsyncClient sqs = mock(SqsAsyncClient.class);
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("queue missing")));

        Health health = indicators.sqsHealthIndicator(new FixedProvider<>(sqs), QUEUE).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("queue", QUEUE);
    }

    @Test
    @DisplayName("sqs UNKNOWN when no SqsAsyncClient bean is available")
    void sqsUnknownWhenAbsent() {
        Health health = indicators.sqsHealthIndicator(new FixedProvider<>(null), QUEUE).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("sqs", "not configured");
    }

    @Test
    @DisplayName("all indicator beans are wired and return a non-null Health")
    void indicatorBeansAreUsable() {
        HealthIndicator pg = indicators.postgresHealthIndicator(new FixedProvider<>(null));
        HealthIndicator s3 = indicators.s3HealthIndicator(new FixedProvider<>(null), BUCKET);
        HealthIndicator sqs = indicators.sqsHealthIndicator(new FixedProvider<>(null), QUEUE);
        assertThat(pg.health()).isNotNull();
        assertThat(s3.health()).isNotNull();
        assertThat(sqs.health()).isNotNull();
    }
}
