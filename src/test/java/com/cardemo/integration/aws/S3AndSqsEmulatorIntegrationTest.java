/*
 * ******************************************************************
 * Program     : S3AndSqsEmulatorIntegrationTest
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Testcontainers LocalStack + PostgreSQL)
 * Function    : Exercises the two mainframe integration constructs against a real
 *               emulator - the GDG generation substrate (versioned S3 bucket, byte
 *               exact 430-byte records) and the DEFINE TDQUEUE(JOBS) bridge (SQS FIFO).
 * Source      : app/jcl/DEFGDGB.jcl, app/jcl/DALYREJS.jcl, app/jcl/REPTFILE.jcl
 *               (the seven GDG bases now three S3 buckets) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD DEFINE TDQUEUE(JOBS) RECORDSIZE(80)
 *               RECORDFORMAT(FIXED) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L176-L182 (REJECT-RECORD, 350 + 80) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.integration.batch.AbstractBatchIntegrationTest;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Template;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.messaging.Message;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;

/**
 * Integration test for the S3 and SQS boundary against a real LocalStack emulator.
 *
 * <h2>What it does</h2>
 *
 * <p>Two mainframe constructs were replaced by cloud services, and both replacements have a property that only
 * a real emulator can demonstrate.
 *
 * <p>The first is <strong>record geometry at the object-store boundary</strong>. A GDG generation was a
 * fixed-length dataset; an S3 object is a byte stream. The 430-byte reject record survives an in-memory unit
 * test trivially, but what matters for parity is the length of the object that actually lands in the bucket
 * after the SDK has encoded, streamed and stored it. This test writes through the production
 * {@link RejectWriter} and then reads the object <em>back out</em> of LocalStack to measure it, which is the
 * only way to prove that no transport-layer re-encoding moved a record boundary.
 *
 * <p>The second is <strong>bucket versioning</strong>, which is the mechanism standing in for GDG relative
 * generation references. {@code (+1)} becomes a new object under a monotonically increasing key and
 * {@code (0)} becomes the greatest existing key, with object versioning underneath. If versioning is not
 * actually enabled on the output bucket, the substitution silently loses history, and nothing in the
 * application code would report it.
 *
 * <p>The third area is the <strong>{@code DEFINE TDQUEUE(JOBS)} bridge</strong>. The queue is FIFO, which the
 * legacy transient data queue effectively was, and FIFO queues impose requirements a standard queue does not -
 * a message group identifier and either explicit or content-based deduplication. A send that would be rejected
 * by a real FIFO queue succeeds against a mock, so this is asserted against the emulator and a round trip is
 * completed rather than just a send.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>This is a Failsafe test. Run the tier with {@code ./mvnw -B -ntp verify}, or this class alone with
 * {@code ./mvnw -B -ntp verify -Dit.test=S3AndSqsEmulatorIntegrationTest -DskipUTs=true}.
 *
 * <p><strong>A container runtime is required</strong>, and this class needs two containers: PostgreSQL 16
 * because it extends {@link AbstractBatchIntegrationTest} which loads the full application context, and
 * LocalStack 4.14.0 for S3 and SQS. Both are started by the base class, which also provisions the three
 * buckets, enables versioning on the output bucket and creates the FIFO queue - so this class asserts against
 * that provisioning rather than repeating it.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every endpoint is the LocalStack container's own address, injected by the base class through
 * {@code @DynamicPropertySource}. <strong>No live AWS endpoint and no real credential is reachable from this
 * test</strong>: the credentials are the emulator's well-known placeholders, and the binding guard in
 * {@code AwsConfig} refuses any endpoint host outside its emulator allowlist, so a misconfiguration fails the
 * context rather than silently escaping to a real account.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>{@code Could not find a valid Docker environment} - no container runtime. Start one; a green build
 *       must not be obtainable by having no daemon.</li>
 *   <li>A failure in the object-length test means a record boundary moved in transit. Check the writer's
 *       charset is byte-transparent and that nothing wrapped the stream in a text encoder.</li>
 *   <li>A versioning failure means the provisioning step no longer enables it, which quietly breaks the GDG
 *       generation substitute.</li>
 *   <li>An SQS send failure mentioning a group identifier means the FIFO requirement regressed; a standard
 *       queue would have accepted the same call, which is why this is asserted against the emulator.</li>
 *   </ul>
 */
@DisplayName("S3 and SQS against LocalStack - GDG generations and the TDQUEUE(JOBS) bridge")
class S3AndSqsEmulatorIntegrationTest extends AbstractBatchIntegrationTest {

    /** {@code LRECL=430} from {@code app/jcl/POSTTRAN.jcl}. */
    private static final int DECLARED_LRECL = 430;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Template s3Template;

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private FileStatusMapper fileStatusMapper;

    /**
     * The context's own metric registrar.
     *
     * <p>Injected rather than constructed on a throwaway registry: {@code MetricsConfig} is the single
     * registrar of the four named counters, and a second instance built here would register a second set on a
     * registry nothing scrapes - which would make the counter assertions of the unit tier meaningless if they
     * were ever moved here. Wiring the real one also keeps this harness honest about what production does.
     */
    @Autowired
    private MetricsConfig metricsConfig;

    @org.springframework.beans.factory.annotation.Value("${carddemo.aws.s3.batch-input-bucket}")
    private String inputBucket;

    @org.springframework.beans.factory.annotation.Value("${carddemo.aws.s3.batch-output-bucket}")
    private String outputBucket;

    @org.springframework.beans.factory.annotation.Value("${carddemo.aws.s3.statements-bucket}")
    private String statementsBucket;

    /**
     * The generation prefix of {@code app/jcl/DALYREJS.jcl}, bound from the same key the writer binds.
     *
     * <p>The writer takes it as a constructor argument with no inline default, so a harness that invented a
     * literal here could pass while the configured value was wrong. Binding the key proves the two agree.
     */
    @org.springframework.beans.factory.annotation.Value("${carddemo.aws.s3.gdg-prefixes.daly-rejs}")
    private String rejectGdgPrefix;

    @org.springframework.beans.factory.annotation.Value("${carddemo.aws.sqs.report-queue}")
    private String reportQueue;

    private static DailyTransaction dailyTransaction(final String id) {
        return new DailyTransaction(1L, id, "01", 5, "System", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT NAME", "MERCHANT CITY", "12345",
                "4111111111111111", "2022-06-10-19.27.53.000000", "2022-06-10-19.27.53.000000");
    }

    @Nested
    @DisplayName("the object-store substrate replacing the GDG bases")
    class ObjectStoreSubstrate {

        @Test
        @DisplayName("all three buckets exist, one per catalogued output family")
        void allThreeBucketsExist() {
            for (final String bucket : new String[] {inputBucket, outputBucket, statementsBucket}) {
                assertThat(bucket).isNotBlank();
                assertThat(s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))
                        .as("bucket %s must exist before any generation can be written", bucket)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("the output bucket is versioned, which is what stands in for a GDG generation")
        void theOutputBucketIsVersioned() {
            final BucketVersioningStatus status = s3Client
                    .getBucketVersioning(GetBucketVersioningRequest.builder().bucket(outputBucket).build())
                    .status();

            assertThat(status)
                    .as("a relative generation reference is replaced by a key plus object versioning; "
                            + "without versioning the substitution silently loses history")
                    .isEqualTo(BucketVersioningStatus.ENABLED);
        }
    }

    @Nested
    @DisplayName("byte-exact record geometry across the S3 boundary")
    class RecordGeometryAcrossTheBoundary {

        @Test
        @DisplayName("a reject record written through the production writer lands as exactly 430 bytes")
        void aRejectRecordLandsAsExactly430Bytes() {
            final StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
            final RejectWriter writer = new RejectWriter(s3Template, metricsConfig,
                    fileStatusMapper, outputBucket, rejectGdgPrefix, stepExecution);

            writer.writeReject(dailyTransaction("0000000000000001"), RejectCode.INVALID_CARD_NUMBER);

            final String objectKey = stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);
            assertThat(objectKey).as("the writer must publish the key it wrote").isNotBlank();

            final byte[] stored = readObject(objectKey);
            assertThat(stored)
                    .as("the length that matters for parity is the length of the object that actually "
                            + "landed, after the SDK encoded and streamed it")
                    .hasSize(DECLARED_LRECL);
        }

        @Test
        @DisplayName("the stored trailer still reads reason-then-description after the round trip")
        void theStoredTrailerSurvivesTheRoundTrip() {
            final StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
            final RejectWriter writer = new RejectWriter(s3Template, metricsConfig,
                    fileStatusMapper, outputBucket, rejectGdgPrefix, stepExecution);

            writer.writeReject(dailyTransaction("0000000000000042"),
                    RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);

            final String objectKey = stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);
            final String record = new String(readObject(objectKey), StandardCharsets.ISO_8859_1);

            assertThat(record).hasSize(DECLARED_LRECL);
            assertThat(record.substring(0, 16))
                    .as("TRAN-ID occupies the first sixteen bytes")
                    .isEqualTo("0000000000000042");
            assertThat(record.substring(350, 354))
                    .as("REJECT-FAIL-REASON is PIC 9(04) at offset 351")
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.toFailReasonField());
            assertThat(record.substring(354).strip())
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getDescription());
        }

        @Test
        @DisplayName("two writes produce two distinct, order-preserving object versions")
        void twoWritesProduceTwoDistinctKeys() {
            final StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
            final RejectWriter writer = new RejectWriter(s3Template, metricsConfig,
                    fileStatusMapper, outputBucket, rejectGdgPrefix, stepExecution);

            writer.writeReject(dailyTransaction("0000000000000001"), RejectCode.INVALID_CARD_NUMBER);
            final String firstKey = stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);
            writer.writeReject(dailyTransaction("0000000000000002"), RejectCode.OVERLIMIT_TRANSACTION);
            final String secondKey = stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);

            assertThat(firstKey).isNotEqualTo(secondKey);
            assertThat(firstKey.compareTo(secondKey))
                    .as("'the current generation' resolves as the lexicographically greatest key, so write "
                            + "order and key order must agree")
                    .isNegative();
            assertThat(readObject(firstKey)).hasSize(DECLARED_LRECL);
            assertThat(readObject(secondKey)).hasSize(DECLARED_LRECL);
        }

        @Test
        @DisplayName("the written object is discoverable by its generation prefix")
        void theWrittenObjectIsDiscoverableByPrefix() {
            final StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
            final RejectWriter writer = new RejectWriter(s3Template, metricsConfig,
                    fileStatusMapper, outputBucket, rejectGdgPrefix, stepExecution);

            writer.writeReject(dailyTransaction("0000000000000001"), RejectCode.INVALID_CARD_NUMBER);

            final String prefix = stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_GENERATION_PREFIX_CONTEXT_KEY);
            // Taken from the injected configuration rather than restated. The generation prefix is
            // "gdg/dalyrejs" - every GDG generation is namespaced under gdg/ in the output bucket, which is
            // what keeps the seven generation bases from colliding with the statements and report objects that
            // share that bucket - and a literal here would duplicate a configured value and start failing for
            // a reason that has nothing to do with the writer.
            assertThat(prefix).startsWith(rejectGdgPrefix + "/");

            assertThat(s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                            .bucket(outputBucket).prefix(prefix).build()).versions())
                    .as("a later step locates the generation by listing this prefix")
                    .isNotEmpty();
        }

        private byte[] readObject(final String objectKey) {
            try (InputStream stream = s3Template.download(outputBucket, objectKey).getInputStream()) {
                return stream.readAllBytes();
            } catch (final java.io.IOException cause) {
                throw new IllegalStateException(
                        "Failed to read back the object written to '" + objectKey + "'.", cause);
            }
        }
    }

    @Nested
    @DisplayName("the FIFO queue replacing DEFINE TDQUEUE(JOBS)")
    class ReportJobQueue {

        @Test
        @DisplayName("a job-submission message round-trips through the real FIFO queue")
        void aJobSubmissionMessageRoundTrips() {
            final String body = "{\"reportName\":\"MONTHLY\",\"startDate\":\"2022-06-01\","
                    + "\"endDate\":\"2022-06-10\"}";

            sqsTemplate.send(builder -> builder
                    .queue(reportQueue)
                    .payload(body)
                    .header("message-group-id", "carddemo-report-jobs"));

            final Optional<Message<?>> received = sqsTemplate.receive(builder -> builder
                    .queue(reportQueue)
                    .pollTimeout(Duration.ofSeconds(10)));

            assertThat(received)
                    .as("the EXEC CICS WRITEQ TD replacement must actually deliver; a FIFO queue rejects a "
                            + "send a mock would have accepted, which is why this runs against the emulator")
                    .isPresent();
            assertThat(received.orElseThrow().getPayload().toString())
                    .contains("MONTHLY")
                    .contains("2022-06-01")
                    .contains("2022-06-10");
        }

        @Test
        @DisplayName("the configured queue name is the FIFO one the compose topology provisions")
        void theConfiguredQueueIsFifo() {
            assertThat(reportQueue)
                    .as("a FIFO queue name must end in .fifo, and ordering is what the transient data "
                            + "queue guaranteed")
                    .endsWith(".fifo");
        }
    }
}
