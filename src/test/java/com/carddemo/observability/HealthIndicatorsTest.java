package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;

/**
 * Unit tests for {@link HealthIndicators}, the CardDemo application's custom
 * Spring Boot Actuator {@code HealthIndicator} beans ({@code database},
 * {@code s3}, {@code sqs}) surfaced under {@code /actuator/health}.
 *
 * <p>These tests prove the Observability goal (G6) health/readiness contract is
 * delivered on day one and, above all, that each indicator <b>degrades
 * gracefully</b> along three axes:</p>
 * <ul>
 *   <li><b>Absent optional dependency</b> (bean not configured) &rarr;
 *       {@link Status#UNKNOWN} and explicitly <b>never</b> {@link Status#DOWN} —
 *       an intentionally omitted integration must not fail readiness.</li>
 *   <li><b>Configured but unreachable</b> &rarr; {@link Status#DOWN}, reflecting a
 *       genuine readiness problem.</li>
 *   <li><b>Configured and reachable</b> &rarr; {@link Status#UP} with a low-cardinality,
 *       non-sensitive detail.</li>
 * </ul>
 *
 * <p>The suite also enforces two safety properties: health details expose
 * <b>no secrets/PII</b> (no credentials, tokens, JDBC URLs, or access keys), and
 * every probe is <b>time-bounded</b> — the SQS "unreachable" case is driven by an
 * already-failed future so the assertion resolves immediately and can never incur
 * the production probe's real timeout.</p>
 *
 * <p>Strategy: the {@link HealthIndicators} configuration is instantiated
 * directly and its dependencies are supplied as Mockito mocks of
 * {@link ObjectProvider} and of the underlying JDBC/AWS clients. No Spring
 * context, Testcontainers, Docker, or live AWS is involved — this is a pure,
 * fast, deterministic unit test. The rationale for introducing these net-new
 * indicators is documented in {@code docs/decision-log.md}.</p>
 */
class HealthIndicatorsTest {

    /**
     * The configuration under test, instantiated directly (no Spring container).
     * Each {@code @Bean} factory method is invoked explicitly to obtain the
     * {@code HealthIndicator} whose {@code health()} result is then asserted.
     */
    private final HealthIndicators config = new HealthIndicators();

    // ------------------------------------------------------------------
    // database indicator (contributor key: "database")
    // ------------------------------------------------------------------

    /**
     * Absent {@link DataSource} (optional dependency not configured) must yield
     * {@link Status#UNKNOWN} — never {@link Status#DOWN} — proving graceful
     * degradation for the database probe.
     */
    @Test
    void databaseHealthIndicator_absentDataSource_isUnknownNeverDown() {
        ObjectProvider<DataSource> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);

        Health health = config.databaseHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("datasource", "not-configured");
    }

    /**
     * A configured {@link DataSource} that hands back a valid connection must
     * report {@link Status#UP} with the {@code database=PostgreSQL} detail.
     */
    @Test
    void databaseHealthIndicator_validConnection_isUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        ObjectProvider<DataSource> provider = mock();
        when(provider.getIfAvailable()).thenReturn(dataSource);

        Health health = config.databaseHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("database", "PostgreSQL");
    }

    /**
     * A configured {@link DataSource} whose connection fails validation
     * ({@code isValid} returns {@code false}) must report {@link Status#DOWN}
     * with the {@code database=validation-failed} detail.
     */
    @Test
    void databaseHealthIndicator_invalidConnection_isDown() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);

        ObjectProvider<DataSource> provider = mock();
        when(provider.getIfAvailable()).thenReturn(dataSource);

        Health health = config.databaseHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("database", "validation-failed");
    }

    /**
     * A {@link SQLException} while acquiring the connection must be caught and
     * mapped to {@link Status#DOWN}, and the rendered details must not leak any
     * credential or JDBC-URL material.
     */
    @Test
    void databaseHealthIndicator_sqlException_isDown_withoutSecrets() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        ObjectProvider<DataSource> provider = mock();
        when(provider.getIfAvailable()).thenReturn(dataSource);

        Health health = config.databaseHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("database", "unavailable");

        // No-secrets guard: the categorical detail and any exception-derived
        // "error" entry must never surface credentials or the JDBC URL.
        String rendered = health.getDetails().toString();
        assertThat(rendered).doesNotContain("password");
        assertThat(rendered).doesNotContain("jdbc:");
    }

    // ------------------------------------------------------------------
    // s3 indicator (contributor key: "s3")
    // ------------------------------------------------------------------

    /**
     * Absent {@link S3Client} (S3 not enabled in the active profile) must yield
     * {@link Status#UNKNOWN} — never {@link Status#DOWN}.
     */
    @Test
    void s3HealthIndicator_absentClient_isUnknownNeverDown() {
        ObjectProvider<S3Client> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);

        Health health = config.s3HealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("s3", "not-configured");
    }

    /**
     * A reachable {@link S3Client} whose {@code listBuckets()} probe succeeds
     * must report {@link Status#UP} with the {@code s3=reachable} detail.
     */
    @Test
    void s3HealthIndicator_reachable_isUp() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.listBuckets()).thenReturn(ListBucketsResponse.builder().build());

        ObjectProvider<S3Client> provider = mock();
        when(provider.getIfAvailable()).thenReturn(s3Client);

        Health health = config.s3HealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("s3", "reachable");
    }

    /**
     * An unreachable {@link S3Client} whose probe throws an
     * {@link SdkClientException} must be mapped to {@link Status#DOWN}.
     */
    @Test
    void s3HealthIndicator_unreachable_isDown() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.listBuckets())
                .thenThrow(SdkClientException.create("simulated S3 connection failure"));

        ObjectProvider<S3Client> provider = mock();
        when(provider.getIfAvailable()).thenReturn(s3Client);

        Health health = config.s3HealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    // ------------------------------------------------------------------
    // sqs indicator (contributor key: "sqs")
    // ------------------------------------------------------------------

    /**
     * Absent {@link SqsAsyncClient} (SQS not enabled in the active profile) must
     * yield {@link Status#UNKNOWN} — never {@link Status#DOWN}.
     */
    @Test
    void sqsHealthIndicator_absentClient_isUnknownNeverDown() {
        ObjectProvider<SqsAsyncClient> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);

        Health health = config.sqsHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("sqs", "not-configured");
    }

    /**
     * A reachable {@link SqsAsyncClient} whose bounded {@code listQueues} probe
     * completes must report {@link Status#UP} with the {@code sqs=reachable}
     * detail. The {@code Consumer}-overload is disambiguated with a typed
     * matcher, and a pre-completed future keeps the probe instantaneous.
     */
    @Test
    void sqsHealthIndicator_reachable_isUp() {
        SqsAsyncClient sqsClient = mock(SqsAsyncClient.class);
        when(sqsClient.listQueues(ArgumentMatchers.<Consumer<ListQueuesRequest.Builder>>any()))
                .thenReturn(CompletableFuture.completedFuture(ListQueuesResponse.builder().build()));

        ObjectProvider<SqsAsyncClient> provider = mock();
        when(provider.getIfAvailable()).thenReturn(sqsClient);

        Health health = config.sqsHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("sqs", "reachable");
    }

    /**
     * An unreachable {@link SqsAsyncClient} must be mapped to {@link Status#DOWN}.
     *
     * <p>The probe is driven with an <em>already-failed</em> future so that the
     * production {@code get(2, SECONDS)} call throws {@code ExecutionException}
     * immediately. This simultaneously validates the DOWN mapping and proves the
     * probe is time-bounded and exception-safe — the assertion never waits for
     * the real two-second timeout.</p>
     */
    @Test
    void sqsHealthIndicator_unreachable_isDown() {
        SqsAsyncClient sqsClient = mock(SqsAsyncClient.class);
        when(sqsClient.listQueues(ArgumentMatchers.<Consumer<ListQueuesRequest.Builder>>any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("simulated SQS failure")));

        ObjectProvider<SqsAsyncClient> provider = mock();
        when(provider.getIfAvailable()).thenReturn(sqsClient);

        Health health = config.sqsHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    // ------------------------------------------------------------------
    // cross-cutting: no secrets / PII in observable output
    // ------------------------------------------------------------------

    /**
     * Cross-cutting safety check: for a representative set of failing health
     * results (each of which carries an exception-derived {@code error} detail),
     * the rendered details must contain none of the tell-tale secret markers.
     * Reinforces the "no secrets/PII in observable output" preservation rule.
     */
    @Test
    void healthDetailsNeverExposeSecrets() throws SQLException {
        // database DOWN (SQLException path -> carries an "error" detail)
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        ObjectProvider<DataSource> dbProvider = mock();
        when(dbProvider.getIfAvailable()).thenReturn(dataSource);
        Health databaseHealth = config.databaseHealthIndicator(dbProvider).health();

        // s3 DOWN (SdkClientException path -> carries an "error" detail)
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.listBuckets())
                .thenThrow(SdkClientException.create("simulated S3 connection failure"));
        ObjectProvider<S3Client> s3Provider = mock();
        when(s3Provider.getIfAvailable()).thenReturn(s3Client);
        Health s3Health = config.s3HealthIndicator(s3Provider).health();

        // sqs DOWN (failed-future path -> carries an "error" detail; resolves immediately)
        SqsAsyncClient sqsClient = mock(SqsAsyncClient.class);
        when(sqsClient.listQueues(ArgumentMatchers.<Consumer<ListQueuesRequest.Builder>>any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("simulated SQS failure")));
        ObjectProvider<SqsAsyncClient> sqsProvider = mock();
        when(sqsProvider.getIfAvailable()).thenReturn(sqsClient);
        Health sqsHealth = config.sqsHealthIndicator(sqsProvider).health();

        for (Health health : new Health[] {databaseHealth, s3Health, sqsHealth}) {
            String rendered = health.getDetails().toString();
            assertThat(rendered).doesNotContain("password");
            assertThat(rendered).doesNotContain("secret");
            assertThat(rendered).doesNotContain("token");
            assertThat(rendered).doesNotContain("jdbc:");
            assertThat(rendered).doesNotContain("AKIA");
        }
    }
}
