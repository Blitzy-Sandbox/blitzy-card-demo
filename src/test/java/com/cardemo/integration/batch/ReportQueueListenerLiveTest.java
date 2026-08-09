/*
 ******************************************************************************
 * Program     : ReportQueueListenerLiveTest
 * Application : CardDemo
 * Type        : Java integration test (JUnit 5, Failsafe tier)
 * Function    : Exercises the report-queue consumer that replaces the JES2
 *               internal reader THROUGH A LIVE FIFO QUEUE: a message published
 *               to the queue must be delivered to the registered listener
 *               container, bound from JSON, acknowledged, and turned into a
 *               real execution of the TRANREPT job. A duplicate submission must
 *               run the report once, and a body that cannot be bound must
 *               launch nothing while leaving its message group free to carry
 *               the next submission.
 * Source      : app/csd/CARDDEMO.CSD:L499-L505 @ 7756d89 - DEFINE TDQUEUE(JOBS)
 *               TYPE(EXTRA) DDNAME(INREADER) RECORDSIZE(80) RECORDFORMAT(FIXED),
 *               the extrapartition queue this consumer replaces
 * Source      : app/cbl/CORPT00C.cbl:L498-L537 @ 7756d89 - the submission loop
 *               and the EXEC CICS WRITEQ TD that fed the internal reader
 * Source      : app/proc/TRANREPT.prc:L41 @ 7756d89 - PARM-START-DATE and
 *               PARM-END-DATE, the two values a submission carries
 ******************************************************************************
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
 ******************************************************************************
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.config.BatchConfig;
import com.cardemo.service.report.ReportSubmissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.listener.AbstractMessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * The live-consumer assertions for the queue bridge that replaces the JES2 internal reader.
 *
 * <h2>What it does, and why the rest of the tier cannot</h2>
 *
 * <p>{@code com.cardemo.unit.config.BatchConfigTest} drives
 * {@link BatchConfig.ReportJobQueueListener#drainReportJobQueue(String, java.util.Map,
 * io.awspring.cloud.sqs.listener.Visibility)} directly with a mocked
 * launcher. That proves the method's own logic and nothing about the transport: it cannot show that the
 * {@code @SqsListener} is actually bound to a queue, that the framework hands the raw body to a
 * {@code String} parameter and the deduplication identifier to the headers map, that the message is
 * acknowledged rather than redelivered forever, or that the launch reaches a real job. Every deployed profile
 * depends on all four.
 *
 * <p>The shared batch and cloud harness cannot show them either, and deliberately so:
 * {@code application-test.yml} sets {@code carddemo.batch.report-queue-listener.enabled: false} because a FIFO
 * message is delivered to exactly one reader and three producer-side classes assert what they published by
 * reading the queue themselves. With a consumer in the same context those classes read an empty queue.
 *
 * <p>This class is therefore the one listener-enabled context in the tree, and it takes a queue of its own so
 * that turning the consumer on cannot disturb anything else:
 *
 * <ul>
 *   <li>{@link IsolatedLiveReportQueue} creates {@value #LIVE_REPORT_QUEUE} in the emulator and rebinds
 *       {@code carddemo.aws.sqs.report-queue} to it before the context refreshes, so the consumer polls a
 *       queue no other class publishes to. It is an {@code ApplicationContextInitializer} rather than a
 *       {@code @DynamicPropertySource} because the parent already registers that property from the container:
 *       an initializer's property source is added at the front of the environment, which makes the override
 *       deterministic instead of dependent on the order two registration methods happen to run in.</li>
 *   <li>{@code @DirtiesContext} closes this context when the class finishes. Spring otherwise caches a context
 *       for the life of the JVM, and a cached context here would leave a polling consumer running for the rest
 *       of the invocation - which is exactly the second-reader problem the isolated queue avoids, reintroduced
 *       by the back door.</li>
 * </ul>
 *
 * <h2>What each test proves</h2>
 *
 * <dl>
 *   <dt>Binding</dt>
 *   <dd>The registry reports a running container under the listener's declared identifier, subscribed to the
 *       isolated queue. A listener annotation that failed to resolve its queue name expression would leave no
 *       container at all, and every later assertion would then time out with a much less specific message.</dd>
 *   <dt>Conversion, acknowledgement and a real launch</dt>
 *   <dd>A well-formed submission produces a {@code TRANREPT} execution carrying the message's own period and
 *       the deduplication identifier as its {@code submissionId}, and the execution completes. The message is
 *       gone from the queue afterwards, which is what acknowledgement means here.</dd>
 *   <dt>Duplicate submission</dt>
 *   <dd>The same body published twice under one deduplication identifier runs the report once. That is the
 *       end-to-end exactly-once property the mainframe pair had, and it holds through two independent
 *       mechanisms: the queue discards the duplicate inside its deduplication interval, and the identifier is
 *       an identifying job parameter, so even a delivered duplicate would name the same job instance and be
 *       refused rather than run again.</dd>
 *   <dt>A body that cannot be bound</dt>
 *   <dd>Launches nothing, and does not stall its message group: a well-formed submission published behind it
 *       in the same group still runs. A FIFO group is ordered, so a consumer that returned the unbindable
 *       message would make every later submission behind it wait one visibility window per redelivery, for no
 *       possibility of a different outcome. {@code localstack-init/init-aws.sh} does provision a dead-letter
 *       target with a {@code RedrivePolicy}, and it bounds the failures the consumer cannot classify rather
 *       than replacing this decision: a body that can never bind is dropped on its first delivery instead of
 *       being left to exhaust the redrive count. That is why the production listener consumes it, and this is
 *       the assertion that the consequence actually holds.</dd>
 * </dl>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run with {@code ./mvnw -B clean verify}. A reachable Docker daemon is a hard prerequisite: the parent
 * starts {@code postgres:16} and a LocalStack container and applies the three Flyway migrations. Compile alone
 * with {@code ./mvnw -q test-compile}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Two values of its own, both applied by {@link IsolatedLiveReportQueue}:
 * {@code carddemo.aws.sqs.report-queue} rebound to {@value #LIVE_REPORT_QUEUE}, and
 * {@code carddemo.batch.report-queue-listener.enabled} set to {@code true}. Everything else is inherited.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>An await times out with no execution</dt>
 *   <dd>Either the container is not subscribed - the binding test names that directly - or the report job
 *       refused the parameters. The listener logs a refusal at ERROR naming the submission, so read the
 *       captured output before changing an assertion.</dd>
 *   <dt>A later class reads an empty report queue</dt>
 *   <dd>This context outlived the class. {@code @DirtiesContext} is what prevents it and must stay.</dd>
 *   <dt>{@code QueueDoesNotExist}</dt>
 *   <dd>The initializer could not reach the emulator. It fails loudly with the endpoint it tried, because a
 *       silently absent queue would turn every test here into a timeout.</dd>
 * </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be. One instance per test method and no mutable {@code static} state;
 * the only {@code static} member is the queue name, which is an immutable literal.
 */
@DisplayName("The report-queue consumer, driven through a live isolated FIFO queue")
@ContextConfiguration(initializers = ReportQueueListenerLiveTest.IsolatedLiveReportQueue.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportQueueListenerLiveTest extends AbstractBatchIntegrationTest {

    /**
     * The queue this class alone reads, kept separate from the one the producer-side classes share.
     *
     * <p>The {@code .fifo} suffix is required by the queue service for a first-in-first-out queue and is what
     * makes a deduplication identifier available at all, which is the property the consumer's idempotency key
     * depends on.
     */
    static final String LIVE_REPORT_QUEUE = "carddemo-report-jobs-live.fifo";

    /**
     * The logical name the physical queue name must be derived from.
     *
     * <p>Derived here rather than written out twice, because {@code AwsConfig} aborts startup unless the
     * physical name is exactly this value suffixed {@code .fifo}.
     */
    static final String LIVE_REPORT_QUEUE_LOGICAL_NAME =
            LIVE_REPORT_QUEUE.substring(0, LIVE_REPORT_QUEUE.length() - ".fifo".length());

    /** Name of the property source the initializer contributes, kept distinct from every framework source. */
    private static final String ISOLATION_PROPERTY_SOURCE = "carddemoIsolatedReportQueue";

    /** The job name the report job registers under, from {@code carddemo.batch.tranrept.name}. */
    private static final String REPORT_JOB_NAME = "TRANREPT";

    /** The identifying job parameter the consumer derives from the deduplication identifier. */
    private static final String SUBMISSION_ID_PARAMETER = "submissionId";

    /** The container identifier the listener annotation declares, and the registry's key for it. */
    private static final String REPORT_LISTENER_CONTAINER_ID = "carddemoReportJobsListener";

    /** The report name the submission body carries. */
    private static final String REPORT_NAME_PARAMETER = "reportName";

    /** The reporting period start, inside the window {@code app/proc/TRANREPT.prc} filters on. */
    private static final String PERIOD_START = "2022-01-01";

    /** The reporting period end, inclusive, matching the procedure's own {@code INCLUDE COND}. */
    private static final String PERIOD_END = "2022-07-06";

    /** How long a delivery, a launch and a completed run are allowed to take before the await gives up. */
    private static final Duration LAUNCH_DEADLINE = Duration.ofSeconds(90L);

    /** How long to keep watching after a delivery that must launch nothing, before concluding it did not. */
    private static final Duration SETTLE_WINDOW = Duration.ofSeconds(20L);

    /** Interval between metadata polls while awaiting an execution. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250L);

    /** Deadline applied to each individual queue call, so a hung call fails rather than hanging the suite. */
    private static final Duration QUEUE_CALL_DEADLINE = Duration.ofSeconds(20L);

    /** The production-configured queue client, used to publish exactly the messages each test needs. */
    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    /**
     * The container's launcher, used by exactly one test to complete a job instance before it is submitted.
     *
     * <p>The parent's launch helper cannot serve there: it stamps an additional identifying run identifier,
     * and Spring Batch derives a job instance from the identifying parameters, so the seeded instance would
     * not be the one the consumer names.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /** The report job the consumer launches, injected so one test can complete an instance of it first. */
    @Autowired
    @Qualifier("transactionReportJob")
    private Job transactionReportJob;

    /** The container registry, so listener binding is asserted rather than inferred from a timeout. */
    @Autowired
    private MessageListenerContainerRegistry listenerContainerRegistry;

    /** Reads the committed batch metadata, which is where a launch on a listener thread becomes observable. */
    @Autowired
    private JobExplorer jobExplorer;

    /** The queue name the context actually resolved, so the isolation itself is asserted. */
    @Value("${carddemo.aws.sqs.report-queue}")
    private String resolvedReportQueue;

    /**
     * Binds a body the same way the consumer does, so a publish can carry an authentic envelope code.
     *
     * <p>The context's own mapper is injected rather than a fresh one, because the code is computed over the
     * bound record's canonical form and a mapper configured differently could bind the same bytes to
     * different field values.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The application signing key the envelope purpose key is derived from.
     *
     * <p>Read from the same property the consumer reads, so the code this class attaches is the code that
     * consumer computes. Naming a literal here instead would make every test in this class pass or fail on
     * whether two copies of a key happened to agree.
     */
    @Value("${carddemo.security.jwt.signing-key}")
    private String envelopeSigningKey;

    /**
     * The clock the consumer measures a submission's validity window against.
     *
     * <p>Injected from the context rather than read from the host, so that the code this class issues is dated
     * by the same instant the consumer will judge it by. Finding SEC-002 gave the code a window; a publisher
     * dating that window from a different clock than the verifier reads would be testing the skew allowance
     * rather than the contract.
     */
    @Autowired
    private Clock clock;

    @Test
    @DisplayName("the consumer is bound and running against the isolated queue, not the shared one")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theConsumerIsBoundToTheIsolatedQueue() {
        assertThat(resolvedReportQueue)
                .as("the initializer's override must win over the parent's registration, or this class would "
                        + "consume the queue three producer-side classes assert against")
                .isEqualTo(LIVE_REPORT_QUEUE);

        final List<String> registeredIds = listenerContainerRegistry.getListenerContainers().stream()
                .map(MessageListenerContainer::getId)
                .toList();
        final MessageListenerContainer<?> reportContainer =
                listenerContainerRegistry.getContainerById(REPORT_LISTENER_CONTAINER_ID);
        assertThat(reportContainer)
                .as("the @SqsListener that replaces the internal reader of app/csd/CARDDEMO.CSD "
                        + "DEFINE TDQUEUE(JOBS) must be registered under its declared identifier; the "
                        + "identifiers present were %s", registeredIds)
                .isNotNull();
        assertThat(reportContainer.isRunning())
                .as("a registered but stopped container would leave every submission sitting in the queue")
                .isTrue();

        // The subscription itself, read off the container rather than inferred from a successful delivery, so
        // a container bound to the wrong queue fails here with the two names rather than as a timeout.
        assertThat(subscribedQueues(reportContainer))
                .as("the consumer must poll the isolated queue and nothing else")
                .containsExactly(LIVE_REPORT_QUEUE);
    }

    @Test
    @DisplayName("a published submission is bound, acknowledged and turned into a real TRANREPT execution")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aPublishedSubmissionLaunchesTheRealReportJob() {
        final String submissionId = runId() + "-single";

        publish(submissionBody("Monthly", PERIOD_START, PERIOD_END), submissionId, runId());

        final JobExecution launched = awaitExecution(submissionId, LAUNCH_DEADLINE);
        assertThat(launched.getJobParameters().getString(
                        TransactionReportProcessor.START_DATE_JOB_PARAMETER))
                .as("app/proc/TRANREPT.prc:L41 PARM-START-DATE, taken from the message rather than defaulted")
                .isEqualTo(PERIOD_START);
        assertThat(launched.getJobParameters().getString(
                        TransactionReportProcessor.END_DATE_JOB_PARAMETER))
                .isEqualTo(PERIOD_END);
        assertThat(launched.getJobParameters().getString(REPORT_NAME_PARAMETER))
                .as("the reporting period the operator chose survives the transport")
                .isEqualTo("Monthly");
        assertThat(launched.getJobParameters().getParameters().get(SUBMISSION_ID_PARAMETER).isIdentifying())
                .as("a non-identifying submission identifier would make every redelivery a new instance")
                .isTrue();
        assertThat(launched.getStatus())
                .as("a submission that reaches the consumer has to run to completion, not merely start")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(remainingMessages())
                .as("the message must be acknowledged; an unacknowledged FIFO message is redelivered "
                        + "forever and blocks its group")
                .isZero();
    }

    @Test
    @DisplayName("a duplicate submission runs the report once, not twice")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDuplicateSubmissionRunsTheReportOnce() {
        final String submissionId = runId() + "-duplicate";
        final String body = submissionBody("Yearly", PERIOD_START, PERIOD_END);

        publish(body, submissionId, runId());
        final JobExecution first = awaitExecution(submissionId, LAUNCH_DEADLINE);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Republished under the same deduplication identifier, which is precisely what a redelivery or a
        // repeated operator submission looks like on the wire.
        publish(body, submissionId, runId());
        sleepFor(SETTLE_WINDOW);

        assertThat(executionsFor(submissionId))
                .as("one submission is one job instance: the deduplication identifier is carried as an "
                        + "identifying parameter, so a second delivery cannot become a second run")
                .hasSize(1);
        assertThat(remainingMessages())
                .as("whichever of the two deliveries reached the consumer was acknowledged")
                .isZero();
    }

    @Test
    @DisplayName("a delivery naming an already completed submission starts nothing and is still acknowledged")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDeliveryNamingACompletedSubmissionStartsNothing() throws Exception {
        // The duplicate test above proves the outcome; this one proves the LISTENER's own arm reaches it.
        // Two mechanisms guarantee exactly-once there and the queue's own deduplication interval is the one
        // that fires first, so the consumer's JobInstanceAlreadyCompleteException branch is never entered.
        // Running the instance first, with exactly the parameter set the consumer will build, puts the
        // consumer in front of a completed instance on its very first delivery - which is what a redelivery
        // after the deduplication interval looks like, and what an operator resubmitting an old period does.
        final String submissionId = runId() + "-already-complete";

        // Launched through the container's own launcher rather than the parent's helper: the helper stamps an
        // extra identifying run identifier, and that extra parameter would make this a DIFFERENT job instance
        // from the one the consumer names, which is precisely the collision this test needs to create.
        // Uniqueness across tests is still guaranteed, because the submission identifier is derived from the
        // test's own name.
        final JobExecution seeded = jobLauncher.run(transactionReportJob, new JobParametersBuilder()
                .addString(TransactionReportProcessor.START_DATE_JOB_PARAMETER, PERIOD_START)
                .addString(TransactionReportProcessor.END_DATE_JOB_PARAMETER, PERIOD_END)
                .addString(REPORT_NAME_PARAMETER, "Monthly")
                .addString(SUBMISSION_ID_PARAMETER, submissionId)
                .toJobParameters());
        assertThat(seeded.getStatus())
                .as("the instance has to be complete before the delivery, or this asserts the wrong branch")
                .isEqualTo(BatchStatus.COMPLETED);

        publish(submissionBody("Monthly", PERIOD_START, PERIOD_END), submissionId, runId());
        sleepFor(SETTLE_WINDOW);

        assertThat(executionsFor(submissionId))
                .as("the consumer must recognise a completed submission and start no second execution; a "
                        + "second row here would mean the report ran twice for one operator request")
                .hasSize(1);
        assertThat(remainingMessages())
                .as("and it must still acknowledge the delivery: an unacknowledged FIFO message is "
                        + "redelivered forever and blocks every later submission in its group")
                .isZero();
    }

    @Test
    @DisplayName("a body that cannot be bound launches nothing and does not stall its message group")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aMalformedSubmissionLaunchesNothingAndDoesNotStallItsGroup() {
        final String malformedId = runId() + "-malformed";
        final String recoveredId = runId() + "-recovered";
        final String group = runId();

        // Truncated JSON: the strict mapper the base profile pins cannot bind it, which is the failure the
        // production listener consumes rather than returns.
        publish("{\"reportName\":\"Monthly\",\"startDate\":", malformedId, group);
        publish(submissionBody("Monthly", PERIOD_START, PERIOD_END), recoveredId, group);

        final JobExecution recovered = awaitExecution(recoveredId, LAUNCH_DEADLINE);
        assertThat(recovered.getStatus())
                .as("a FIFO group is ordered, so the unbindable message had to be consumed for this one to "
                        + "be delivered without first waiting out its redrive count")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(executionsFor(malformedId))
                .as("nothing may be launched for a body the consumer could not bind")
                .isEmpty();
        assertThat(remainingMessages())
                .as("both messages are acknowledged, the unbindable one included")
                .isZero();
    }

    @Test
    @DisplayName("an inverted window is launched like any other submission and does not stall its group")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anInvertedWindowRunsAndDoesNotStallItsGroup() {
        // FINDING B-15, severity MAJOR. This is the exact delivery that used to stop the report tier. The
        // online tier is faithful to app/cbl/CORPT00C.cbl, which compares no bound against the other, so
        // POST /api/reports answered 202 for an inverted window; the batch tier then refused it with a
        // ValidationException that reached this listener as neither a binding failure nor a
        // JobParametersInvalidException, so the delivery was returned to the queue. On a FIFO queue with one
        // message group that is not a retry, it is a stop: the message came back every fifteen minutes and
        // every later submission waited behind it, with no dead-letter target to divert into. Recovery took
        // an sqs purge-queue, which discards legitimate queued work as well.
        //
        // The fix is agreement with the source rather than a better error: an inverted window selects nothing
        // - app/proc/TRANREPT.prc:45-46 INCLUDE COND cannot be satisfied by any record when the bounds cross
        // - so the run completes and writes a report carrying its closing block and no detail line.
        final String invertedId = runId() + "-inverted";
        final String followerId = runId() + "-follower";
        final String group = runId();

        publish(submissionBody("Custom", PERIOD_END, PERIOD_START), invertedId, group);
        publish(submissionBody("Monthly", PERIOD_START, PERIOD_END), followerId, group);

        final JobExecution inverted = awaitExecution(invertedId, LAUNCH_DEADLINE);
        assertThat(inverted.getStatus())
                .as("the inverted window is a selection outcome and not an invalid request, so the launch "
                        + "reaches a terminal success exactly as an ordered window does")
                .isEqualTo(BatchStatus.COMPLETED);

        final JobExecution follower = awaitExecution(followerId, LAUNCH_DEADLINE);
        assertThat(follower.getStatus())
                .as("and the submission behind it in the same ordered group runs too. This is the assertion "
                        + "the defect failed: a message the consumer could never acknowledge held the group, "
                        + "so a perfectly valid Monthly submission was never processed at all")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(remainingMessages())
                .as("both deliveries are acknowledged, so nothing is left to be redelivered forever")
                .isZero();
    }

    /**
     * Returns the queues a registered container is subscribed to.
     *
     * <p>The subscription is exposed on the container implementation rather than on the interface, so this
     * narrows and states why: an implementation that did not expose it would make the assertion vacuous, and
     * silently passing on an unknown implementation is exactly what an empty list would do.
     *
     * @param container the registered container
     * @return the queue names, sorted for a stable failure message
     */
    private static List<String> subscribedQueues(final MessageListenerContainer<?> container) {
        assertThat(container)
                .as("the subscription is readable only on the framework's own container implementation")
                .isInstanceOf(AbstractMessageListenerContainer.class);
        return ((AbstractMessageListenerContainer<?, ?, ?>) container).getQueueNames().stream()
                .sorted()
                .toList();
    }

    /**
     * Builds a submission body in exactly the shape the producer publishes.
     *
     * @param reportName the reporting period name
     * @param startDate the inclusive period start
     * @param endDate the inclusive period end
     * @return the JSON body
     */
    private static String submissionBody(final String reportName, final String startDate,
            final String endDate) {

        return String.format(Locale.ROOT,
                "{\"reportName\":\"%s\",\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                reportName, startDate, endDate);
    }

    /**
     * Publishes one message to the isolated queue with an explicit deduplication identifier and group.
     *
     * <p>The identifier is explicit rather than content-derived because the queue is provisioned with
     * content-based deduplication off - the same contract the shared queue carries - and because these tests
     * need to choose when two publishes are one submission and when they are two.
     *
     * @param body the message body
     * @param deduplicationId the deduplication identifier, which the consumer adopts as its idempotency key
     * @param groupId the message group, which orders delivery
     */
    private void publish(final String body, final String deduplicationId, final String groupId) {
        final String queueUrl = awaitQueueCall(
                sqsAsyncClient.getQueueUrl(request -> request.queueName(LIVE_REPORT_QUEUE)),
                "resolve " + LIVE_REPORT_QUEUE).queueUrl();

        final SendMessageRequest.Builder request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .messageGroupId(groupId)
                .messageDeduplicationId(deduplicationId);

        // The consumer AUTHENTICATES before it acts: it binds the body and then verifies an envelope code
        // against a key derived from the application signing key, and a delivery that fails that check is
        // discarded without launching anything. A publish from this class must therefore carry the code, or
        // every launch assertion below would time out for a reason that has nothing to do with the listener
        // binding, the deduplication contract or the FIFO group - which is exactly what happened when this
        // class was written against a consumer that did not yet verify.
        //
        // The code is attached only when the body BINDS. That is not a convenience: the malformed-body
        // scenario publishes bytes that are not a submission at all, and such a message cannot be signed
        // because there is nothing to compute a canonical form over. It is also the right wire behaviour for
        // that test, whose subject is a body discarded at the binding step - one step BEFORE verification -
        // so signing it would move the failure it asserts.
        envelopeCodeFor(body, deduplicationId).ifPresent(code -> request.messageAttributes(Map.of(
                ReportSubmissionService.JobSubmissionEnvelope.SIGNATURE_HEADER,
                MessageAttributeValue.builder().dataType("String").stringValue(code).build())));

        awaitQueueCall(sqsAsyncClient.sendMessage(request.build()),
                "publish a submission to " + LIVE_REPORT_QUEUE);
    }

    /**
     * Renders the envelope code for a body, when the body is a submission at all.
     *
     * <p>Finding SEC-002. The code binds the deduplication identifier the publish will carry, so it must be
     * rendered for that identifier and not for the body alone - which is why the identifier is a parameter
     * here rather than something this method could be written without.
     *
     * @param body the message body about to be published; never {@code null}
     * @param deduplicationId the deduplication identifier the same publish will set; never {@code null}
     * @return the code the consumer will recompute, or empty when the body does not bind to a submission
     */
    private Optional<String> envelopeCodeFor(final String body, final String deduplicationId) {
        try {
            return Optional.of(new ReportSubmissionService.JobSubmissionEnvelope(this.envelopeSigningKey)
                    .sign(this.objectMapper.readValue(body,
                            ReportSubmissionService.JobSubmissionMessage.class),
                            deduplicationId, this.clock.instant()));
        } catch (final JsonProcessingException | IllegalArgumentException notASubmission) {
            // Deliberately swallowed and deliberately not logged as a failure: reaching here is the
            // malformed-body scenario doing what it exists to do. The exception is named so that a reader can
            // see WHICH failures are treated as "not a submission" rather than inferring it from a bare catch.
            return Optional.empty();
        }
    }

    /**
     * Returns the number of messages the isolated queue still holds, visible or in flight.
     *
     * @return the count
     */
    private long remainingMessages() {
        final String queueUrl = awaitQueueCall(
                sqsAsyncClient.getQueueUrl(request -> request.queueName(LIVE_REPORT_QUEUE)),
                "resolve " + LIVE_REPORT_QUEUE).queueUrl();

        final var attributes = awaitQueueCall(sqsAsyncClient.getQueueAttributes(request -> request
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)),
                "read the depth of " + LIVE_REPORT_QUEUE).attributes();

        return Long.parseLong(attributes.getOrDefault(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"))
                + Long.parseLong(attributes.getOrDefault(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0"));
    }

    /**
     * Waits for one completed report execution carrying a given submission identifier.
     *
     * <p>Polls the committed metadata rather than instrumenting the listener, because the launch happens on a
     * consumer thread and the metadata store is the only place the two threads meet. The wait covers the whole
     * run, not just the launch, so a job that started and then failed is reported as a failed status rather
     * than as a timeout.
     *
     * @param submissionId the identifying parameter value to match
     * @param deadline how long to wait
     * @return the matching execution
     */
    private JobExecution awaitExecution(final String submissionId, final Duration deadline) {
        final long expiry = System.nanoTime() + deadline.toNanos();
        List<JobExecution> matches = List.of();
        while (System.nanoTime() < expiry) {
            matches = executionsFor(submissionId);
            if (!matches.isEmpty() && !matches.get(0).isRunning()) {
                return matches.get(0);
            }
            sleepFor(POLL_INTERVAL);
        }
        throw new AssertionError("No completed " + REPORT_JOB_NAME + " execution carrying "
                + SUBMISSION_ID_PARAMETER + " '" + submissionId + "' appeared within "
                + deadline.toSeconds() + " seconds. Executions seen for that identifier: " + matches
                + ". All " + REPORT_JOB_NAME + " executions present: " + allReportExecutions()
                + ". A consumer that never received the message leaves this list empty; read the captured "
                + "output for a binding or a parameter-refusal message before changing the deadline.");
    }

    /**
     * Returns every report execution carrying a given submission identifier.
     *
     * @param submissionId the identifying parameter value to match
     * @return the matching executions
     */
    private List<JobExecution> executionsFor(final String submissionId) {
        return allReportExecutions().stream()
                .filter(execution -> submissionId.equals(
                        execution.getJobParameters().getString(SUBMISSION_ID_PARAMETER)))
                .toList();
    }

    /**
     * Returns every recorded execution of the report job.
     *
     * @return the executions
     */
    private List<JobExecution> allReportExecutions() {
        final List<JobExecution> executions = new ArrayList<>();
        for (final JobInstance instance
                : jobExplorer.getJobInstances(REPORT_JOB_NAME, 0, Integer.MAX_VALUE)) {
            executions.addAll(jobExplorer.getJobExecutions(instance));
        }
        return List.copyOf(executions);
    }

    /**
     * Sleeps for a bounded interval, restoring the interrupt flag if the wait is cut short.
     *
     * @param interval how long to sleep
     */
    private static void sleepFor(final Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a queue delivery.", interrupted);
        }
    }

    /**
     * Completes one asynchronous queue call on the calling thread within a bounded deadline.
     *
     * @param <T> the call's result type
     * @param pending the call in flight
     * @param description what the call was attempting, for the failure message
     * @return the result
     */
    private static <T> T awaitQueueCall(final CompletableFuture<T> pending, final String description) {
        try {
            return pending.get(QUEUE_CALL_DEADLINE.toSeconds(), TimeUnit.SECONDS);
        } catch (final TimeoutException deadlineExceeded) {
            throw new IllegalStateException("Exceeded the " + QUEUE_CALL_DEADLINE.toSeconds()
                    + "-second deadline while attempting to " + description + ".", deadlineExceeded);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while attempting to " + description + ".",
                    interrupted);
        } catch (final ExecutionException failed) {
            throw new IllegalStateException("Failed while attempting to " + description + ".", failed);
        }
    }

    /**
     * Creates the isolated queue and rebinds the consumer to it, before the context refreshes.
     *
     * <p>An initializer rather than a {@code @DynamicPropertySource} method, and the distinction is
     * load-bearing: the parent already registers {@code carddemo.aws.sqs.report-queue} from the container, and
     * two registration methods writing the same key resolve in whatever order they are discovered in. An
     * initializer's property source is added at the front of the environment, so the override is deterministic.
     *
     * <p>The queue is created here rather than in a {@code @BeforeAll} for the same ordering reason: the
     * listener container is started during refresh and resolves its queue immediately, so a queue created
     * after refresh would already have been looked for and not found.
     */
    static final class IsolatedLiveReportQueue
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        /** Public no-argument constructor, which is how the test framework instantiates an initializer. */
        IsolatedLiveReportQueue() {
            // Stateless.
        }

        /**
         * {@inheritDoc}
         *
         * @param applicationContext the context being prepared
         */
        @Override
        public void initialize(final ConfigurableApplicationContext applicationContext) {
            final Environment environment = applicationContext.getEnvironment();
            final String endpoint = required(environment, "spring.cloud.aws.sqs.endpoint");
            final String region = required(environment, "spring.cloud.aws.region.static");
            final String accessKey = required(environment, "spring.cloud.aws.credentials.access-key");
            final String secretKey = required(environment, "spring.cloud.aws.credentials.secret-key");

            createIsolatedQueue(endpoint, region, accessKey, secretKey);

            // Added to the front of the environment by hand rather than through TestPropertyValues, and the
            // difference is not cosmetic. TestPropertyValues.applyTo writes into a source it names "test", and
            // a source of that name already exists in a @SpringBootTest environment - positioned AFTER
            // "Dynamic Test Properties". Its merge-in-place branch therefore leaves the parent's registration
            // ahead of the override, which was measured: the resolved value stayed carddemo-report-jobs.fifo
            // and this class silently consumed the shared queue. Adding a source of its own puts the override
            // at position zero, where it beats both.
            applicationContext.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource(ISOLATION_PROPERTY_SOURCE, Map.of(
                            "carddemo.aws.sqs.report-queue", LIVE_REPORT_QUEUE,
                            // AwsConfig aborts the refresh unless the physical name is the logical name plus
                            // the .fifo suffix, because localstack-init/init-aws.sh derives one from the
                            // other. Rebinding one without the other fails startup, which is the guard doing
                            // its job rather than an obstacle to work around.
                            "carddemo.aws.sqs.report-queue-logical-name", LIVE_REPORT_QUEUE_LOGICAL_NAME,
                            "carddemo.batch.report-queue-listener.enabled", "true")));
        }

        /**
         * Creates the isolated first-in-first-out queue, idempotently.
         *
         * <p>Content-based deduplication is off, matching the shared queue's contract, so a publisher must
         * supply a deduplication identifier and the consumer has one to adopt as its idempotency key.
         *
         * @param endpoint the emulator endpoint the parent registered from the container
         * @param region the emulator region
         * @param accessKey the emulator access key
         * @param secretKey the emulator secret key
         */
        private static void createIsolatedQueue(final String endpoint, final String region,
                final String accessKey, final String secretKey) {

            try (SqsClient sqs = SqsClient.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build()) {

                sqs.createQueue(request -> request
                        .queueName(LIVE_REPORT_QUEUE)
                        .attributes(Map.of(
                                QueueAttributeName.FIFO_QUEUE, "true",
                                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false")));
            } catch (final QueueNameExistsException alreadyProvisioned) {
                // Idempotent by design: a previous class in the same invocation may have created it, and
                // re-creating an existing queue with identical attributes is a no-op rather than a fault.
                assertThat(alreadyProvisioned.getMessage()).isNotBlank();
            } catch (final RuntimeException unreachable) {
                throw new IllegalStateException("The isolated report queue " + LIVE_REPORT_QUEUE
                        + " could not be created at " + endpoint + ". Without it the consumer binds to "
                        + "nothing and every test in this class times out with a much less specific "
                        + "message, so this fails here instead.", unreachable);
            }
        }

        /**
         * Reads a property the parent must already have registered, refusing an absent one.
         *
         * @param environment the environment being prepared
         * @param key the property name
         * @return the value
         */
        private static String required(final Environment environment, final String key) {
            final String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Property '" + key + "' is not set at initializer time. The "
                        + "parent harness registers it from its container, so an absent value means this "
                        + "initializer is running before that registration rather than after it.");
            }
            return value;
        }
    }
}
