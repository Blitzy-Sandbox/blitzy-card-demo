/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Report-Submission SQS FIFO Integration Test
 *  (CICS Transient Data Queue 'JOBS'  ->  AWS SQS FIFO bridge)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield parity test with NO COBOL source equivalent. It validates
 *  the single online->batch bridge of the AWS CardDemo estate. On the mainframe,
 *  the online program app/cbl/CORPT00C.cbl (CICS transaction CR00, BMS map
 *  CORPT0A) built a JCL deck and wrote each line to the CICS Transient Data Queue
 *  'JOBS' via EXEC CICS WRITEQ TD QUEUE('JOBS') — paragraph WIRTE-JOBSUB-TDQ
 *  (CORPT00C.cbl L515-L535), invoked from SUBMIT-JOB-TO-INTRDR (L462) — which
 *  triggered JES batch submission. This is the SOLE online<->batch coupling in
 *  the system (AAP §0.6.3).
 *
 *  TECHNOLOGY SUBSTITUTION UNDER TEST (decision D-004, Minimal Change Clause):
 *      CICS TDQ WRITEQ('JOBS') / JES submission  ->  a SINGLE AWS SQS FIFO publish
 *      onto carddemo-report-jobs.fifo, performed by
 *      com.cardemo.service.report.ReportSubmissionService
 *      (tech-spec L30, L631; AAP §0.4.1 / §0.6.3 / §0.7.7).
 *  The JCL deck is a mainframe artifact and is NOT reproduced; the downstream
 *  Spring Batch report job is parameterized solely by the published message
 *  (reportType + start/end date).
 *
 *  The COBOL/JCL sources are read-only reference and are NEVER copied into this
 *  repository; traceability to the frozen legacy baseline is by commit SHA
 *  27d6c6f only. Base package is com.cardemo (decision D-006 — deliberately NOT
 *  com.carddemo), matching <groupId>com.cardemo</groupId> in carddemo-java/pom.xml.
 * ============================================================================
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.dto.ReportRequest;
import com.cardemo.model.dto.ReportSubmissionResponse;
import com.cardemo.service.report.ReportSubmissionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * End-to-end LocalStack integration test for the CardDemo <strong>online&rarr;batch report-submission
 * bridge</strong> &mdash; the migration of CICS {@code WRITEQ TD QUEUE('JOBS')} to a single AWS
 * <strong>SQS&nbsp;FIFO</strong> publish (decision&nbsp;<strong>D-004</strong>).
 *
 * <h2>What this test proves</h2>
 * <p>It exercises the <em>real</em> production service
 * {@link com.cardemo.service.report.ReportSubmissionService} (wired by the full Spring context loaded
 * by {@link AbstractLocalStackIntegrationTest}) and verifies, against a real (LocalStack) SQS&nbsp;FIFO
 * queue, that:</p>
 * <ol>
 *   <li>a confirmed ({@code confirm = "Y"}) submission publishes <strong>exactly one</strong> message
 *       to {@code carddemo-report-jobs.fifo}, whose JSON payload carries the canonical
 *       {@code reportType} and the derived inclusive date range, and whose FIFO
 *       {@code MessageGroupId} is the fixed {@code "report-jobs"} group with a non-blank, unique
 *       {@code MessageDeduplicationId}; and</li>
 *   <li>a cancelled ({@code confirm = "N"}) submission publishes <strong>nothing</strong> and reports
 *       {@code submitted() == false}.</li>
 * </ol>
 *
 * <h2>Why YEARLY</h2>
 * <p>The <strong>YEARLY</strong> report type is selected deliberately: its date range is computed from
 * the current year ({@code Jan 1 .. Dec 31}) and needs <em>no</em> user-supplied dates, so the test is
 * fully deterministic and never flakes on date input (AAP &mdash; determinism requirement).</p>
 *
 * <h2>Self-managed resource lifecycle (AAP §0.7.7)</h2>
 * <p>The FIFO queue is created in {@link #createReportJobsFifoQueue()} ({@code @BeforeAll}) and deleted
 * in {@link #deleteReportJobsFifoQueue()} ({@code @AfterAll}); {@link #drainQueue()}
 * ({@code @AfterEach}) clears any residual messages so the &quot;exactly one message&quot; assertion is
 * reliable regardless of test order. No pre-existing state is assumed and reruns are idempotent (a
 * fresh {@link java.util.UUID} {@code MessageDeduplicationId} per submission defeats FIFO content
 * dedup). The shared LocalStack container is owned by the base class and is intentionally left running
 * (reaped by the Testcontainers <em>Ryuk</em> sidecar at JVM exit).</p>
 *
 * <h2>ZERO live AWS / zero live credentials (AAP §0.7.2 / §0.7.7)</h2>
 * <p>Every AWS interaction targets the inherited LocalStack community Testcontainer. The production
 * service publishes through the auto-configured Spring Cloud AWS {@code SqsTemplate} (pointed at
 * LocalStack via the base class {@code @DynamicPropertySource}); this test verifies independently with
 * the inherited synchronous {@link AbstractLocalStackIntegrationTest#newSqsClient()} factory, which uses
 * LocalStack's dummy {@code test}/{@code test} keys. No real AWS endpoints or secrets are involved.</p>
 *
 * <h2>Contract fidelity (no hardcoded resource names)</h2>
 * <p>The queue name is resolved from
 * {@link AbstractLocalStackIntegrationTest#awsResourceProperties awsResourceProperties} (bound from
 * {@code carddemo.aws.sqs.report-jobs-queue}), never from a magic string, and is asserted to equal the
 * contract value {@code carddemo-report-jobs.fifo} so the test and the production service resolve the
 * same queue. The FIFO {@code MessageGroupId} {@code "report-jobs"} and the unique
 * {@code MessageDeduplicationId} match the {@code ReportSubmissionService} publish contract and AAP
 * §0.7.7.</p>
 *
 * <h2>Execution</h2>
 * <p>The mandatory {@code IT} suffix and {@code com.cardemo.integration} package route this class to the
 * {@code maven-failsafe-plugin} (run via {@code mvn verify -Pintegration}); the
 * {@code maven-surefire-plugin} explicitly excludes it. Lifecycle methods are non-static, relying on the
 * {@code @TestInstance(PER_CLASS)} the base class declares (inherited by this subclass), which also lets
 * {@code @BeforeAll} read the autowired {@code awsResourceProperties}. This subclass deliberately adds
 * <strong>no</strong> Spring context annotations ({@code @SpringBootTest} / {@code @ActiveProfiles} /
 * {@code @DynamicPropertySource} / {@code @TestPropertySource}) so the resolved configuration stays
 * identical to its siblings and the cached {@code ApplicationContext} is shared across the AWS IT suite.</p>
 *
 * @see com.cardemo.service.report.ReportSubmissionService
 * @see com.cardemo.config.AwsConfig.AwsResourceProperties
 * @see AbstractLocalStackIntegrationTest
 */
@DisplayName("ReportSubmissionService -> SQS FIFO (CICS TDQ 'JOBS' bridge) integration")
public class ReportSubmissionSqsIT extends AbstractLocalStackIntegrationTest {

    /**
     * The contract queue name (FIFO). Asserted against the value resolved from
     * {@code awsResourceProperties} so a drifting configuration fails the test loudly rather than
     * silently exercising the wrong queue. The {@code .fifo} suffix is mandatory.
     */
    private static final String EXPECTED_QUEUE_NAME = "carddemo-report-jobs.fifo";

    /**
     * The fixed FIFO {@code MessageGroupId} the service publishes under
     * ({@code ReportSubmissionService.SQS_MESSAGE_GROUP_ID}); a single group preserves the strict,
     * point-to-point ordering the legacy CICS TDQ {@code JOBS} provided.
     */
    private static final String EXPECTED_MESSAGE_GROUP_ID = "report-jobs";

    /** Canonical machine report-type token carried in the SQS payload for a yearly report. */
    private static final String REPORT_TYPE_YEARLY = "YEARLY";

    /**
     * The real production service under test (the {@code CORPT00C} translation), injected from the full
     * Spring context loaded by the base class. Its {@code SqsTemplate} is auto-configured by Spring
     * Cloud AWS and points at the inherited LocalStack container.
     */
    @Autowired
    private ReportSubmissionService reportSubmissionService;

    /**
     * Independent, synchronous SQS verification client (created from the inherited
     * {@link AbstractLocalStackIntegrationTest#newSqsClient()} factory). Deliberately decoupled from the
     * production beans so the test cleanly separates &quot;exercise production code&quot; from
     * &quot;verify the result&quot;. Created in {@code @BeforeAll}, closed in {@code @AfterAll}.
     */
    private SqsClient sqs;

    /** URL of the {@code carddemo-report-jobs.fifo} queue created in {@code @BeforeAll}. */
    private String queueUrl;

    /**
     * Test-side JSON reader for the message body. The {@link JavaTimeModule} is registered so a
     * {@link LocalDate} serialized as an ISO-8601 string deserializes cleanly; the array form is also
     * handled defensively by {@link #parseLocalDate(JsonNode)}.
     */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // -------------------------------------------------------------------------------------------------
    // Lifecycle — self-managed FIFO queue (AAP §0.7.7: tests create and destroy their own resources).
    // -------------------------------------------------------------------------------------------------

    /**
     * Provisions the {@code carddemo-report-jobs.fifo} queue on the shared LocalStack container before
     * any test runs. Non-static (the base declares {@code @TestInstance(PER_CLASS)}) so it can read the
     * autowired {@link AbstractLocalStackIntegrationTest#awsResourceProperties}.
     *
     * <p>The queue name is taken from configuration (never hardcoded) and asserted to equal the contract
     * value, locking the resource-name contract. Creation is idempotent: {@code FIFO_QUEUE=true} is
     * mandatory for a {@code .fifo} queue, and {@code CONTENT_BASED_DEDUPLICATION=false} because the
     * service supplies an explicit {@code MessageDeduplicationId} per publish.</p>
     */
    @BeforeAll
    void createReportJobsFifoQueue() {
        sqs = newSqsClient();

        // Resolve the queue name from the SAME bean the production service uses — contract fidelity.
        String queueName = awsResourceProperties.getSqs().getReportJobsQueue();
        assertThat(queueName)
                .as("FIFO report-jobs queue name must come from awsResourceProperties "
                        + "(carddemo.aws.sqs.report-jobs-queue), not a hardcoded literal")
                .isEqualTo(EXPECTED_QUEUE_NAME);

        // Create the FIFO queue (idempotent: createQueue with identical attributes returns the existing
        // URL). This realizes the technology substitution: CICS TDQ 'JOBS' -> SQS FIFO (decision D-004).
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName(queueName)
                        .attributes(Map.of(
                                QueueAttributeName.FIFO_QUEUE, "true",
                                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                        .build())
                .queueUrl();
    }

    // -------------------------------------------------------------------------------------------------
    // Test 1 — confirm "Y" publishes exactly one correct message (the WRITEQ('JOBS') replacement).
    // -------------------------------------------------------------------------------------------------

    /**
     * Verifies that a confirmed YEARLY submission publishes exactly one well-formed message to the FIFO
     * queue, with the correct payload and FIFO system attributes.
     */
    @Test
    @DisplayName("submitReport(confirm=\"Y\") publishes exactly one correct message to the FIFO queue")
    void confirmYesPublishesExactlyOneMessage() {
        // ---- Arrange: a deterministic YEARLY report (dates derived from the current year). ----
        // ReportRequest models the three BMS report-type flags; YEARLY needs no user-entered dates.
        ReportRequest request = new ReportRequest();
        request.setYearly("Y");
        request.setConfirm("Y");

        // ---- Act: exercise the REAL production service (TDQ WRITEQ('JOBS') -> SQS publish). ----
        // SqsTemplate.send(...) is synchronous, so the message is enqueued by the time this returns.
        ReportSubmissionResponse result = reportSubmissionService.submitReport(request);

        // ---- Assert: service-level contract (docs/api-contracts.md §5.7). ----
        assertThat(result).as("submission result").isNotNull();
        assertThat(result.status()).as("YEARLY + confirm 'Y' must submit").isEqualTo("SUBMITTED");
        assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
        assertThat(result.reportType()).isEqualTo(REPORT_TYPE_YEARLY);

        // ---- Assert: exactly one message landed on the queue. ----
        // SQS receipt is eventually consistent, so long-poll within an Awaitility window. Accumulate
        // across polls: the lone message becomes invisible for the default 30s visibility timeout once
        // received (>> the 20s window), so the accumulated size settles at exactly one.
        List<Message> received = new ArrayList<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    received.addAll(receiveMessages(10, 10));
                    assertThat(received)
                            .as("exactly one report-submission message on %s", EXPECTED_QUEUE_NAME)
                            .hasSize(1);
                });

        Message message = received.get(0);

        // ---- Assert: JSON payload parity (reportType + the YEARLY inclusive range). ----
        int currentYear = Year.now().getValue();
        JsonNode payload = parseBody(message.body());
        assertThat(payload.path("reportType").asText())
                .as("payload reportType").isEqualTo(REPORT_TYPE_YEARLY);
        assertThat(parseLocalDate(payload.path("startDate")))
                .as("payload startDate (Jan 1 of current year)")
                .isEqualTo(LocalDate.of(currentYear, 1, 1));
        assertThat(parseLocalDate(payload.path("endDate")))
                .as("payload endDate (Dec 31 of current year)")
                .isEqualTo(LocalDate.of(currentYear, 12, 31));

        // ---- Assert: FIFO system attributes (fixed group id + unique, non-blank dedup id). ----
        // For a FIFO queue, MessageGroupId and MessageDeduplicationId are SQS SYSTEM attributes; they
        // are requested explicitly in receiveMessages(...) and read back from Message.attributes().
        Map<MessageSystemAttributeName, String> systemAttributes = message.attributes();
        assertThat(systemAttributes.get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                .as("FIFO MessageGroupId preserves TDQ-style ordering")
                .isEqualTo(EXPECTED_MESSAGE_GROUP_ID);
        assertThat(systemAttributes.get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID))
                .as("FIFO MessageDeduplicationId is the returned jobId (api-contracts §5.7)")
                .isEqualTo(result.jobId());

        // Delete what we consumed so the shared queue is clean for the next test (the @AfterEach drain
        // cannot see it for the 30s visibility timeout, so delete it here explicitly).
        deleteAll(received);
    }

    // -------------------------------------------------------------------------------------------------
    // Test 2 — cancel path: confirm "N" publishes nothing.
    // -------------------------------------------------------------------------------------------------

    /**
     * Verifies the cancel path: a {@code confirm = "N"} submission silently cancels &mdash; it returns
     * {@code status() == "CANCELLED"} with a {@code null} {@code jobId} and publishes no message (the
     * COBOL {@code 'N'} branch cleared the screen and never wrote to the {@code JOBS} TDQ).
     */
    @Test
    @DisplayName("submitReport(confirm=\"N\") cancels silently and publishes nothing")
    void confirmNoPublishesNothing() {
        // ---- Arrange: a YEARLY report, but the operator declines confirmation. ----
        ReportRequest request = new ReportRequest();
        request.setYearly("Y");
        request.setConfirm("N");

        // ---- Act. ----
        ReportSubmissionResponse result = reportSubmissionService.submitReport(request);

        // ---- Assert: cancelled, nothing submitted (docs/api-contracts.md §5.7 cancel path). ----
        assertThat(result).as("submission result").isNotNull();
        assertThat(result.status()).as("confirm 'N' must NOT submit").isEqualTo("CANCELLED");
        assertThat(result.jobId()).as("cancelled submission carries no job id").isNull();

        // ---- Assert: zero messages were published. A short long-poll is sufficient — nothing was sent,
        // and @AfterEach drained the queue after the previous test, so the queue is known-empty. ----
        List<Message> messages = receiveMessages(10, 3);
        assertThat(messages).as("cancel path publishes no message").isEmpty();
    }

    // -------------------------------------------------------------------------------------------------
    // Per-test teardown + suite teardown.
    // -------------------------------------------------------------------------------------------------

    /**
     * Drains the shared queue after each test so the next test starts from a known-empty state. Loops
     * receive+delete until two consecutive empty (short) long-polls confirm the queue is clear, making
     * the &quot;exactly one&quot; / &quot;zero&quot; assertions reliable irrespective of test order.
     */
    @AfterEach
    void drainQueue() {
        int consecutiveEmptyPolls = 0;
        while (consecutiveEmptyPolls < 2) {
            List<Message> batch = receiveMessages(10, 1);
            if (batch.isEmpty()) {
                consecutiveEmptyPolls++;
            } else {
                deleteAll(batch);
                consecutiveEmptyPolls = 0;
            }
        }
    }

    /**
     * Tears down ONLY this test's queue and closes the verification client (self-managed lifecycle, AAP
     * §0.7.7). The shared LocalStack container is owned by the base class and is intentionally NOT
     * stopped here.
     */
    @AfterAll
    void deleteReportJobsFifoQueue() {
        if (sqs != null) {
            if (queueUrl != null) {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            }
            sqs.close();
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------------

    /**
     * Receives up to {@code maxMessages} from the FIFO queue using SQS long polling, explicitly
     * requesting the FIFO {@code MessageGroupId}/{@code MessageDeduplicationId} system attributes (plus
     * any custom message attributes for diagnostics).
     *
     * @param maxMessages the maximum number of messages to fetch in one call (1&ndash;10)
     * @param waitSeconds the long-poll wait time in seconds (0&ndash;20)
     * @return the received messages (possibly empty); never {@code null}
     */
    private List<Message> receiveMessages(int maxMessages, int waitSeconds) {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(maxMessages)
                        .waitTimeSeconds(waitSeconds)
                        // FIFO group + dedup ids are SQS SYSTEM attributes — request them so they are
                        // populated in Message.attributes().
                        .messageSystemAttributeNames(
                                MessageSystemAttributeName.MESSAGE_GROUP_ID,
                                MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)
                        // Surface any custom message attributes too (diagnostics only).
                        .messageAttributeNames("All")
                        .build())
                .messages();
    }

    /**
     * Deletes every supplied message from the queue by its receipt handle.
     *
     * @param messages the messages to delete (may be empty)
     */
    private void deleteAll(List<Message> messages) {
        for (Message message : messages) {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }

    /**
     * Parses an SQS message body as a JSON tree, failing the test with a clear message if the body is
     * not valid JSON.
     *
     * @param body the raw message body
     * @return the parsed JSON tree
     */
    private JsonNode parseBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse SQS message body as JSON: " + body, ex);
        }
    }

    /**
     * Converts a JSON node into a {@link LocalDate}, accepting both forms a Jackson producer may emit
     * for a {@code java.time.LocalDate}: an ISO-8601 string ({@code "2026-01-01"}) or a
     * {@code [year, month, day]} array (when {@code WRITE_DATES_AS_TIMESTAMPS} is enabled). This keeps
     * the assertion robust regardless of the producer's Jackson configuration.
     *
     * @param node the JSON node holding the date
     * @return the parsed {@link LocalDate}
     */
    private static LocalDate parseLocalDate(JsonNode node) {
        if (node.isArray() && node.size() >= 3) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        return LocalDate.parse(node.asText());
    }
}
