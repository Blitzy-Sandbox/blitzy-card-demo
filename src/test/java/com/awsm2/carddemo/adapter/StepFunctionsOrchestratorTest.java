/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.adapter;

import com.awsm2.carddemo.exception.CardDemoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StepFunctionsOrchestrator}, the AWS Step Functions
 * adapter that replaces mainframe JCL job-stream sequencing.
 *
 * <p>// Replaces: JCL job stream sequencing (POSTTRAN &rarr; INTCALC &rarr;
 * COMBTRAN &rarr; CREASTMT/TRANREPT)</p>
 * <p>// Backs AAP &sect;0.6.3: JCL &rarr; Step Functions orchestration</p>
 *
 * <p>The production {@link StepFunctionsOrchestrator} is the SOLE class in the
 * Java CardDemo target that invokes the AWS SDK v2 {@link SfnClient}
 * (per AAP &sect;0.7.1 &mdash; "Isolate all AWS service integrations in
 * dedicated adapter classes &mdash; never inline AWS SDK calls in business
 * logic"). It is invoked from two places:</p>
 *
 * <ol>
 *   <li>{@code KafkaEventConsumer.onReportRequested(...)} &mdash; the
 *       online-to-batch bridge that replaces the source CICS TDQ JOBS queue
 *       (the sole online-to-batch bridge in the source environment per
 *       {@code CORPT00C WIRTE-JOBSUB-TDQ}, AAP &sect;0.1.1).</li>
 *   <li>Operational batch triggers (cron, scheduled CloudWatch event) that
 *       call {@link StepFunctionsOrchestrator#startEodPipeline(String)} to
 *       run the full end-of-day pipeline.</li>
 * </ol>
 *
 * <h2>Test coverage matrix</h2>
 * <p>The tests cover the four behavioural contracts of the adapter:</p>
 * <ol>
 *   <li><strong>Happy path execution kickoff</strong> (Phase 6) &mdash;
 *       {@link StepFunctionsOrchestrator#startExecution(String, String)}
 *       returns the SDK-supplied execution ARN, constructs the request
 *       with the verbatim ARN and input payload, defaults null
 *       {@code inputJson} to the empty JSON object {@code "{}"}, and
 *       generates a Step-Functions-legal execution name of the form
 *       {@code carddemo-{epoch-millis}-{8 lowercase hex}}.</li>
 *   <li><strong>Input validation</strong> (Phase 7 &amp; 9.3/9.4) &mdash;
 *       null, empty, and blank {@code stateMachineArn} and
 *       {@code executionArn} arguments throw
 *       {@link IllegalArgumentException} <em>before</em> any SDK call is
 *       made (verified via {@code verify(sfnClient, never()).*}).</li>
 *   <li><strong>SDK exception propagation</strong> (Phase 8) &mdash;
 *       {@link SfnException} thrown by the SDK propagates unchanged to
 *       the caller (it is NOT wrapped in {@code CardDemoException}, per
 *       AAP &sect;0.7.1 &mdash; AWS SDK exceptions are already typed and
 *       carry their own {@code awsErrorDetails().errorCode()}).</li>
 *   <li><strong>Convenience wrappers</strong> (Phases 10 &amp; 11)
 *       &mdash; {@code startEodPipeline} formats the {@code batchRunDate}
 *       as a one-field JSON object and delegates to
 *       {@code startExecution} against the {@code eodPipelineArn};
 *       {@code startReportPipeline} forwards the supplied JSON verbatim
 *       to {@code startExecution} against the {@code reportPipelineArn};
 *       both throw {@link CardDemoException} with the verbatim reason
 *       code {@code "CONFIG_ERROR"} when their respective state-machine
 *       ARNs are null or blank (per AAP &sect;0.7.2 verbatim reason-code
 *       propagation).</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>This is a pure Mockito unit test &mdash; no Spring application context
 * is bootstrapped. {@link MockitoExtension} (Mockito 5.x strict-stubbing
 * by default) wires {@link #sfnClient} into the production
 * {@link StepFunctionsOrchestrator} via direct constructor invocation in
 * {@link #setUp()}; {@code @InjectMocks} is intentionally NOT used because
 * the production class's {@code @Value}-injected fields
 * ({@code eodPipelineArn}, {@code reportPipelineArn}) require explicit
 * field-level injection via
 * {@link ReflectionTestUtils#setField(Object, String, Object)} to set
 * up the canonical happy-path ARN strings AND to mutate them mid-test
 * (Tests 10.2, 10.3, 11.2) for the CONFIG_ERROR scenarios.</p>
 *
 * <h2>Compliance constraints honoured</h2>
 * <ul>
 *   <li><strong>AWS SDK v2 only</strong> &mdash; every AWS type is imported
 *       from {@code software.amazon.awssdk.*}; this file contains zero
 *       {@code com.amazonaws.*} imports (AAP &sect;0.5.1).</li>
 *   <li><strong>No real AWS credentials</strong> &mdash; the production
 *       class's {@code sfnClient} dependency is mocked via Mockito; no
 *       AWS API call is ever made.</li>
 *   <li><strong>LocalStack-convention ARN strings</strong> &mdash; the
 *       test ARNs use the LocalStack-convention account ID
 *       {@code 000000000000} (per AAP &sect;0.7.2 LocalStack testing
 *       guidance); no production AWS account references appear anywhere
 *       in this file.</li>
 *   <li><strong>No real card data, SSNs, or PII</strong> &mdash; the only
 *       string payloads in this test file are state-machine ARNs,
 *       execution ARNs, and report-request JSON shapes; no PCI-DSS-sensitive
 *       data is present (AAP &sect;0.6.6).</li>
 *   <li><strong>JUnit 5 idioms only</strong> &mdash; {@code @Test},
 *       {@code @DisplayName}, {@code @BeforeEach}, {@code @ExtendWith},
 *       {@code assertThrows}, {@code assertEquals}, {@code assertNotNull},
 *       {@code assertTrue}. No JUnit 4 ({@code @Before},
 *       {@code @Test(expected=...)}).</li>
 *   <li><strong>Mockito strict-stubbing</strong> &mdash; the default
 *       behaviour under {@link MockitoExtension} in Mockito 5.x. Tests
 *       that take the early-exit / no-SDK-call code path (Phase 7 input
 *       validation, Tests 9.3/9.4, Tests 10.2/10.3, Test 11.2)
 *       deliberately do NOT stub {@link #sfnClient} so that unused stubs
 *       cannot mask validation regressions.</li>
 * </ul>
 *
 * @see StepFunctionsOrchestrator
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepFunctionsOrchestrator \u2014 AWS Step Functions adapter unit tests")
class StepFunctionsOrchestratorTest {

    // -------------------------------------------------------------------------
    // Mocks and System Under Test
    // -------------------------------------------------------------------------

    /**
     * The mocked AWS SDK v2 Step Functions client. Mockito strict-stubbing
     * (enabled by default via {@link MockitoExtension} in Mockito 5.x)
     * fails the test if a stub set via {@code when(...)} is never invoked,
     * catching over-mocking that would otherwise hide real bugs.
     *
     * <p>The mock is automatically reset by Mockito between test methods
     * (per the {@link MockitoExtension} lifecycle), so each test starts
     * with a clean collaborator instance.</p>
     */
    @Mock
    private SfnClient sfnClient;

    /**
     * The production {@link StepFunctionsOrchestrator} instance under test.
     * Instantiated fresh inside {@link #setUp()} via direct constructor
     * invocation so that the {@code @Value}-injected ARN fields can be
     * subsequently set via {@link ReflectionTestUtils#setField}.
     *
     * <p>{@code @InjectMocks} is deliberately NOT used here because the
     * production class uses field-level {@code @Value} injection for the
     * two state-machine ARNs and Mockito would not populate those fields.
     * The canonical Spring Boot 3.x pattern for adapter unit tests with
     * {@code @Value}-on-field configuration is direct construction +
     * {@code ReflectionTestUtils.setField} per the JUnit 5 / Spring
     * Test 6.x convention.</p>
     */
    private StepFunctionsOrchestrator orchestrator;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    /**
     * LocalStack-convention state-machine ARN under the dummy test account
     * ID {@code 000000000000}. This is the canonical end-of-day pipeline
     * ARN ({@code eod-batch-pipeline}) as documented in AAP &sect;0.4.1
     * and provisioned by {@code infrastructure/terraform/stepfunctions.tf}
     * in production. Used by Tests 6.1&ndash;6.4, 7.x, 8.1, and the EOD
     * pipeline tests in Phase 10.
     */
    private static final String VALID_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-eod";

    /**
     * Prefix matching the execution-ARN shape returned by the AWS Step
     * Functions service for the canonical {@link #VALID_ARN} state machine.
     * Step Functions execution ARNs always have the form
     * {@code arn:aws:states:{region}:{account}:execution:{state-machine}:{execution-name}}
     * so this prefix is concatenated with a representative execution name
     * to build the stubbed response value.
     */
    private static final String EXECUTION_ARN_PREFIX =
            "arn:aws:states:us-east-1:000000000000:execution:carddemo-eod:";

    /**
     * LocalStack-convention state-machine ARN for the report pipeline used
     * by the {@code startReportPipeline} convenience method tests in
     * Phase 11. Mirrors the production target documented in AAP &sect;0.4.1
     * (state-machine {@code carddemo-report}, populated by Terraform).
     */
    private static final String VALID_REPORT_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-report";

    /**
     * Sample execution ARN used to seed the stubbed
     * {@link StartExecutionResponse} returned by the mocked
     * {@link SfnClient#startExecution(StartExecutionRequest)} call. The
     * suffix mirrors the format documented in the production class:
     * {@code carddemo-{epoch-millis}-{short-uuid}}.
     */
    private static final String SAMPLE_EXECUTION_ARN =
            EXECUTION_ARN_PREFIX + "carddemo-1234567890-abcd1234";

    /**
     * Sample state-machine input payload used in Tests 6.1, 6.2, and 6.4.
     * Represents the canonical batch-date wrapper a caller would supply
     * to {@code startExecution} directly (the convenience method
     * {@code startEodPipeline} produces a similar JSON shape).
     */
    private static final String SAMPLE_INPUT_JSON = "{\"date\":\"2025-05-20\"}";

    /**
     * Initializes the {@link StepFunctionsOrchestrator} under test before
     * each test method. Steps:
     *
     * <ol>
     *   <li>Construct the orchestrator with the {@code @Mock SfnClient}
     *       via the production single-arg constructor (mirroring the
     *       Spring-driven runtime wiring where the {@code SfnClient} bean
     *       is supplied by {@code AwsSdkConfig}).</li>
     *   <li>Inject the canonical happy-path state-machine ARNs into the
     *       two {@code @Value}-bound private fields
     *       ({@code eodPipelineArn} and {@code reportPipelineArn}) using
     *       {@link ReflectionTestUtils#setField(Object, String, Object)}.
     *       This mirrors how Spring property resolution populates the
     *       fields at application startup.</li>
     * </ol>
     *
     * <p>Tests 10.2, 10.3, and 11.2 subsequently mutate these fields back
     * to {@code ""} or {@code null} to verify the {@code "CONFIG_ERROR"}
     * branch of the convenience methods.</p>
     */
    @BeforeEach
    void setUp() {
        // Step 1 \u2014 construct the production class via its single-arg
        // constructor (matches the @Service bean wiring under
        // AwsSdkConfig.sfnClient() and StepFunctionsOrchestrator's
        // documented constructor signature).
        orchestrator = new StepFunctionsOrchestrator(sfnClient);

        // Step 2 \u2014 populate the @Value-injected ARN fields with
        // canonical happy-path values. ReflectionTestUtils.setField uses
        // reflection so the fields' final-or-not status and visibility
        // are irrelevant; the same approach is canonical for adapter
        // unit tests across the CardDemo test suite (see
        // KafkaEventPublisherTest.setUp).
        ReflectionTestUtils.setField(orchestrator, "eodPipelineArn", VALID_ARN);
        ReflectionTestUtils.setField(orchestrator, "reportPipelineArn", VALID_REPORT_ARN);
    }

    // =========================================================================
    // Phase 6 \u2014 startExecution happy path tests
    // =========================================================================

    /**
     * Test 6.1: Verifies that {@code startExecution} returns the verbatim
     * execution ARN returned by the AWS Step Functions service. This is
     * the canonical happy-path scenario: a caller supplies a valid state
     * machine ARN and an input payload, and receives back the execution
     * ARN suitable for subsequent {@link
     * StepFunctionsOrchestrator#getExecutionStatus(String)} polling.
     */
    @Test
    @DisplayName("startExecution returns the SFN-supplied execution ARN for valid inputs")
    void startExecution_withValidInputs_returnsExecutionArn() {
        // Given: the SDK returns a successful StartExecutionResponse with
        // a populated executionArn and a non-null startDate (the latter
        // models the realistic SFN response shape).
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with the canonical happy-path ARN
        // and input payload.
        String executionArn = orchestrator.startExecution(VALID_ARN, SAMPLE_INPUT_JSON);

        // Then: the execution ARN is propagated verbatim from the SDK
        // response, and begins with the canonical
        // arn:aws:states:...:execution:carddemo-eod: prefix.
        assertNotNull(executionArn,
                "startExecution must return a non-null execution ARN");
        assertEquals(SAMPLE_EXECUTION_ARN, executionArn,
                "startExecution must propagate the SDK executionArn() verbatim");
        assertTrue(executionArn.startsWith(EXECUTION_ARN_PREFIX),
                "Execution ARN must follow the SFN execution-ARN shape");
    }

    /**
     * Test 6.2: Verifies that the {@link StartExecutionRequest} passed to
     * the SDK carries the supplied state-machine ARN and input payload
     * unchanged. Uses {@link ArgumentCaptor} to inspect the request,
     * guarding against any future refactor that might silently mutate
     * the ARN or input before invoking the SDK.
     */
    @Test
    @DisplayName("startExecution constructs the SFN request with the verbatim ARN and input")
    void startExecution_constructsRequestWithCorrectArnAndInput() {
        // Given: a stubbed SDK response so the call completes successfully.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with a known ARN and input.
        orchestrator.startExecution(VALID_ARN, SAMPLE_INPUT_JSON);

        // Then: the captured StartExecutionRequest carries:
        //   * the supplied stateMachineArn unchanged, and
        //   * the supplied input string unchanged.
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());

        StartExecutionRequest captured = captor.getValue();
        assertEquals(VALID_ARN, captured.stateMachineArn(),
                "stateMachineArn must be propagated verbatim to the SDK request");
        assertEquals(SAMPLE_INPUT_JSON, captured.input(),
                "inputJson must be propagated verbatim to the SDK request");
    }

    /**
     * Test 6.3: Verifies that a null {@code inputJson} argument is
     * defaulted to the empty JSON object {@code "{}"} before the SDK
     * call. Step Functions accepts a syntactically valid JSON payload
     * in {@link StartExecutionRequest#input()}; passing {@code null}
     * directly to the SDK would yield an
     * {@code InvalidExecutionInputException} from the service. The
     * adapter therefore substitutes {@code "{}"} for null per its
     * documented contract.
     */
    @Test
    @DisplayName("startExecution defaults a null inputJson to the empty JSON object")
    void startExecution_withNullInput_defaultsToEmptyJsonObject() {
        // Given: a stubbed SDK response so the call completes successfully.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with null inputJson.
        orchestrator.startExecution(VALID_ARN, null);

        // Then: the captured request carries the empty JSON object "{}"
        // (the documented default per the production class's
        // EMPTY_JSON_INPUT constant).
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());
        assertEquals("{}", captor.getValue().input(),
                "null inputJson must default to the empty JSON object");
    }

    /**
     * Test 6.4: Verifies that the execution-name generated by the
     * adapter follows the documented format
     * {@code carddemo-{epoch-millis}-{8 lowercase hex chars}}.
     *
     * <p>The production class builds the name as:
     * <pre>
     *     "carddemo-" + System.currentTimeMillis() + "-" +
     *         UUID.randomUUID().toString().substring(0, 8)
     * </pre>
     * The first 8 characters of {@link UUID#randomUUID()} are always
     * lowercase hexadecimal (the random-UUID algorithm emits canonical
     * 8-4-4-4-12 lowercase hex). The regex therefore asserts the exact
     * format: {@code ^carddemo-\d+-[a-f0-9]{8}$}.</p>
     *
     * <p>This name format respects Step Functions' execution-name
     * constraints (1&ndash;80 characters, character set
     * {@code [a-zA-Z0-9-_]}) and provides:</p>
     * <ul>
     *   <li>a sortable timestamp prefix for operator log correlation,</li>
     *   <li>a UUID-derived suffix for collision-free uniqueness even
     *       under same-millisecond concurrency.</li>
     * </ul>
     */
    @Test
    @DisplayName("startExecution generates an execution name in the canonical carddemo-<millis>-<8hex> format")
    void startExecution_generatesExecutionNameInExpectedFormat() {
        // Given: a stubbed SDK response.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with the empty-JSON input shortcut.
        orchestrator.startExecution(VALID_ARN, "{}");

        // Then: the captured request's name matches the documented
        // format carddemo-<epoch-millis>-<8 lowercase hex>.
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());

        String name = captor.getValue().name();
        assertNotNull(name, "execution name must not be null");
        assertTrue(name.matches("^carddemo-\\d+-[a-f0-9]{8}$"),
                "execution name must follow the carddemo-<millis>-<8hex> format, "
                        + "but was: " + name);
        // Also verify the 80-character SFN execution-name limit is honoured.
        assertTrue(name.length() <= 80,
                "execution name must be <= 80 characters per the SFN service limit");
    }

    // =========================================================================
    // Phase 7 \u2014 startExecution input validation tests
    // =========================================================================

    /**
     * Test 7.1: A null {@code stateMachineArn} must throw
     * {@link IllegalArgumentException} <em>before</em> any SDK call is
     * issued. The {@code verify(sfnClient, never())} assertion guards
     * against silent regressions where a future refactor might rely on
     * the SDK to reject a null ARN; we want the adapter itself to
     * fail-fast.
     */
    @Test
    @DisplayName("startExecution throws IllegalArgumentException when stateMachineArn is null")
    void startExecution_withNullStateMachineArn_throwsIllegalArgument() {
        // When + Then: the adapter rejects null with IllegalArgumentException
        // before any SDK call.
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.startExecution(null, "{}"),
                "null stateMachineArn must throw IllegalArgumentException");

        // And: the SDK is never invoked.
        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Test 7.2: An empty {@code stateMachineArn} ({@code ""}) is treated
     * the same as null per {@link String#isBlank()} semantics.
     */
    @Test
    @DisplayName("startExecution throws IllegalArgumentException when stateMachineArn is empty")
    void startExecution_withEmptyStateMachineArn_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.startExecution("", "{}"),
                "empty stateMachineArn must throw IllegalArgumentException");

        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Test 7.3: A blank (whitespace-only) {@code stateMachineArn} is
     * treated the same as null and empty &mdash; the adapter uses
     * {@link String#isBlank()} which returns {@code true} for any string
     * composed entirely of whitespace characters.
     */
    @Test
    @DisplayName("startExecution throws IllegalArgumentException when stateMachineArn is whitespace-only")
    void startExecution_withBlankStateMachineArn_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.startExecution("   ", "{}"),
                "whitespace-only stateMachineArn must throw IllegalArgumentException");

        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    // =========================================================================
    // Phase 8 \u2014 startExecution exception propagation
    // =========================================================================

    /**
     * Test 8.1: Verifies that {@link SfnException} thrown by the SDK
     * propagates unchanged through the adapter &mdash; it is NOT wrapped
     * in {@code CardDemoException}. Per AAP &sect;0.7.1 this is the
     * documented behaviour for the AWS adapter layer: SDK exceptions are
     * already typed and carry their own {@code awsErrorDetails().errorCode()}
     * (e.g., {@code "InvalidArn"}, {@code "StateMachineDoesNotExist"},
     * {@code "ExecutionLimitExceeded"}). The
     * {@code @ControllerAdvice GlobalExceptionHandler} inspects these
     * codes to render the appropriate HTTP response to the caller.
     */
    @Test
    @DisplayName("startExecution propagates SfnException without wrapping")
    void startExecution_whenSfnException_propagatesAsIs() {
        // Given: the SDK throws an SfnException (simulating any of the
        // documented Step Functions service errors).
        SfnException simulated = (SfnException) SfnException.builder()
                .message("simulated SFN service error")
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(simulated);

        // When + Then: the adapter does NOT wrap the exception; it
        // propagates through to the caller untouched.
        SfnException thrown = assertThrows(SfnException.class,
                () -> orchestrator.startExecution(VALID_ARN, "{}"),
                "SfnException must propagate from the adapter unchanged");
        assertEquals("simulated SFN service error", thrown.getMessage(),
                "the propagated exception must carry the original SDK message");
    }

    // =========================================================================
    // Phase 9 \u2014 getExecutionStatus tests
    // =========================================================================

    /**
     * Test 9.1: Verifies that {@code getExecutionStatus} returns the
     * {@link ExecutionStatus} enum value from the SDK response unchanged.
     * The canonical successful-execution scenario.
     */
    @Test
    @DisplayName("getExecutionStatus returns SUCCEEDED for a valid execution ARN")
    void getExecutionStatus_withValidArn_returnsStatus() {
        // Given: the SDK returns a DescribeExecutionResponse with the
        // SUCCEEDED status.
        DescribeExecutionResponse mockResponse = DescribeExecutionResponse.builder()
                .status(ExecutionStatus.SUCCEEDED)
                .build();
        when(sfnClient.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with a representative execution ARN.
        ExecutionStatus status = orchestrator.getExecutionStatus(
                EXECUTION_ARN_PREFIX + "exec-1");

        // Then: the SDK status is returned unchanged.
        assertEquals(ExecutionStatus.SUCCEEDED, status,
                "getExecutionStatus must propagate the SDK status unchanged");
    }

    /**
     * Test 9.2: Verifies that the {@link DescribeExecutionRequest}
     * passed to the SDK carries the supplied execution ARN unchanged.
     */
    @Test
    @DisplayName("getExecutionStatus constructs the SDK request with the verbatim execution ARN")
    void getExecutionStatus_constructsRequestWithCorrectArn() {
        // Given: a stubbed SDK response.
        DescribeExecutionResponse mockResponse = DescribeExecutionResponse.builder()
                .status(ExecutionStatus.SUCCEEDED)
                .build();
        when(sfnClient.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(mockResponse);

        String executionArn = EXECUTION_ARN_PREFIX + "exec-1";

        // When: the adapter is invoked.
        orchestrator.getExecutionStatus(executionArn);

        // Then: the captured DescribeExecutionRequest carries the
        // verbatim execution ARN.
        ArgumentCaptor<DescribeExecutionRequest> captor =
                ArgumentCaptor.forClass(DescribeExecutionRequest.class);
        verify(sfnClient).describeExecution(captor.capture());
        assertEquals(executionArn, captor.getValue().executionArn(),
                "executionArn must be propagated verbatim to the SDK request");
    }

    /**
     * Test 9.3: A null {@code executionArn} must throw
     * {@link IllegalArgumentException} before any SDK call.
     */
    @Test
    @DisplayName("getExecutionStatus throws IllegalArgumentException when executionArn is null")
    void getExecutionStatus_withNullArn_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.getExecutionStatus(null),
                "null executionArn must throw IllegalArgumentException");

        verify(sfnClient, never()).describeExecution(any(DescribeExecutionRequest.class));
    }

    /**
     * Test 9.4: A blank (whitespace-only) {@code executionArn} must
     * throw {@link IllegalArgumentException} before any SDK call.
     */
    @Test
    @DisplayName("getExecutionStatus throws IllegalArgumentException when executionArn is whitespace-only")
    void getExecutionStatus_withBlankArn_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.getExecutionStatus("   "),
                "whitespace-only executionArn must throw IllegalArgumentException");

        verify(sfnClient, never()).describeExecution(any(DescribeExecutionRequest.class));
    }

    /**
     * Test 9.5: Verifies that the {@code RUNNING} status is propagated
     * unchanged &mdash; covers the steady-state polling case where the
     * Step Functions execution has not yet completed.
     */
    @Test
    @DisplayName("getExecutionStatus returns RUNNING when the execution is in-flight")
    void getExecutionStatus_returnsRunningStatus() {
        // Given: the SDK reports RUNNING.
        DescribeExecutionResponse mockResponse = DescribeExecutionResponse.builder()
                .status(ExecutionStatus.RUNNING)
                .build();
        when(sfnClient.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is polled.
        ExecutionStatus status = orchestrator.getExecutionStatus(
                EXECUTION_ARN_PREFIX + "exec-running");

        // Then: RUNNING is returned unchanged.
        assertEquals(ExecutionStatus.RUNNING, status,
                "getExecutionStatus must propagate RUNNING unchanged");
    }

    /**
     * Test 9.6: Verifies that the {@code FAILED} terminal status is
     * propagated unchanged &mdash; covers the failure-mode polling case
     * where a Step Functions Task state errored out (e.g., an
     * underlying AWS Batch job exited non-zero, an upstream IAM
     * permission was missing, or a Lambda step threw an unhandled
     * exception). The caller (typically the operations dashboard or
     * a callback Lambda) uses this status to trigger remediation.
     */
    @Test
    @DisplayName("getExecutionStatus returns FAILED when the execution has terminally failed")
    void getExecutionStatus_returnsFailedStatus() {
        // Given: the SDK reports FAILED.
        DescribeExecutionResponse mockResponse = DescribeExecutionResponse.builder()
                .status(ExecutionStatus.FAILED)
                .build();
        when(sfnClient.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is polled.
        ExecutionStatus status = orchestrator.getExecutionStatus(
                EXECUTION_ARN_PREFIX + "exec-failed");

        // Then: FAILED is returned unchanged.
        assertEquals(ExecutionStatus.FAILED, status,
                "getExecutionStatus must propagate FAILED unchanged");
    }

    /**
     * Test 9.7: Verifies that {@link SfnException} thrown by the SDK
     * during {@code describeExecution} propagates unchanged through the
     * adapter, mirroring the {@code startExecution} contract verified in
     * Test 8.1. Per AAP &sect;0.7.1, SDK exceptions are already typed and
     * carry their own {@code awsErrorDetails().errorCode()} (e.g.,
     * {@code "ExecutionDoesNotExist"}); the adapter must not wrap them in
     * {@code CardDemoException}.
     */
    @Test
    @DisplayName("getExecutionStatus propagates SfnException without wrapping")
    void getExecutionStatus_whenSfnException_propagatesAsIs() {
        // Given: the SDK throws an SfnException (simulating, e.g.,
        // ExecutionDoesNotExistException).
        SfnException simulated = (SfnException) SfnException.builder()
                .message("simulated describeExecution failure")
                .build();
        when(sfnClient.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(simulated);

        // When + Then: the adapter does NOT wrap the exception; it
        // propagates through to the caller untouched.
        SfnException thrown = assertThrows(SfnException.class,
                () -> orchestrator.getExecutionStatus(
                        EXECUTION_ARN_PREFIX + "nonexistent"),
                "SfnException from describeExecution must propagate unchanged");
        assertEquals("simulated describeExecution failure", thrown.getMessage(),
                "the propagated exception must carry the original SDK message");
    }

    // =========================================================================
    // Phase 10 \u2014 startEodPipeline convenience method tests
    // =========================================================================

    /**
     * Test 10.1: Verifies that {@code startEodPipeline} delegates to
     * {@code startExecution} using the configured {@code eodPipelineArn}
     * and a JSON-encoded {@code batchRunDate} input. This is the
     * happy-path convenience entry point invoked by the scheduled
     * end-of-day pipeline trigger.
     *
     * <p>Replaces: full nightly JCL chain &mdash;
     * {@code POSTTRAN.jcl} &rarr; {@code INTCALC.jcl} &rarr;
     * {@code COMBTRAN.jcl} &rarr; {@code CREASTMT.JCL}/{@code TRANREPT.jcl}
     * per AAP &sect;0.6.3.</p>
     */
    @Test
    @DisplayName("startEodPipeline submits the EOD state machine with a {batchRunDate} JSON input")
    void startEodPipeline_withValidBatchRunDate_invokesStartExecution() {
        // Given: a stubbed successful SFN response.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with an ISO-8601 batch run date.
        String executionArn = orchestrator.startEodPipeline("2025-05-20");

        // Then: the returned execution ARN is non-null and matches the
        // SDK-supplied value.
        assertNotNull(executionArn,
                "startEodPipeline must return a non-null execution ARN");
        assertEquals(SAMPLE_EXECUTION_ARN, executionArn,
                "startEodPipeline must propagate the SDK executionArn() verbatim");

        // And: the captured request targets the configured eodPipelineArn
        // with the expected one-field JSON payload.
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());

        StartExecutionRequest captured = captor.getValue();
        assertEquals(VALID_ARN, captured.stateMachineArn(),
                "startEodPipeline must use the configured eodPipelineArn");
        assertEquals("{\"batchRunDate\":\"2025-05-20\"}", captured.input(),
                "startEodPipeline must format input as {\"batchRunDate\":\"<date>\"}");
    }

    /**
     * Test 10.2: Verifies that {@code startEodPipeline} throws
     * {@link CardDemoException} with the verbatim reason code
     * {@code "CONFIG_ERROR"} when the configured
     * {@code carddemo.stepfunctions.eod-pipeline-arn} is the empty
     * string (typically the case in developer / local profiles where
     * the EOD pipeline is not provisioned).
     *
     * <p>Per AAP &sect;0.7.2 the reason code is propagated verbatim by
     * the {@code GlobalExceptionHandler} into the JSON API response
     * envelope's {@code code} field, so downstream consumers receive an
     * unambiguous configuration signal.</p>
     */
    @Test
    @DisplayName("startEodPipeline throws CardDemoException(CONFIG_ERROR) when eod-pipeline-arn is blank")
    void startEodPipeline_whenEodArnIsBlank_throwsConfigError() {
        // Given: the EOD ARN is mutated back to the blank/empty default
        // (mirrors a Spring profile where the ARN was not provisioned).
        ReflectionTestUtils.setField(orchestrator, "eodPipelineArn", "");

        // When + Then: the adapter throws CardDemoException with the
        // verbatim reason code "CONFIG_ERROR".
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> orchestrator.startEodPipeline("2025-05-20"),
                "blank eod-pipeline-arn must throw CardDemoException");
        assertEquals("CONFIG_ERROR", ex.getReasonCode(),
                "the reason code must be \"CONFIG_ERROR\" verbatim per AAP \u00a70.7.2");

        // And: the SDK is never invoked (fail-fast on configuration).
        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Test 10.3: Verifies that a {@code null} (rather than blank)
     * {@code eodPipelineArn} also triggers the {@code "CONFIG_ERROR"}
     * branch &mdash; the production class uses
     * {@code (arn == null || arn.isBlank())} to detect both
     * conditions uniformly.
     */
    @Test
    @DisplayName("startEodPipeline throws CardDemoException(CONFIG_ERROR) when eod-pipeline-arn is null")
    void startEodPipeline_whenEodArnIsNull_throwsConfigError() {
        // Given: the EOD ARN is explicitly set to null.
        ReflectionTestUtils.setField(orchestrator, "eodPipelineArn", null);

        // When + Then: same reason-code contract as Test 10.2.
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> orchestrator.startEodPipeline("2025-05-20"),
                "null eod-pipeline-arn must throw CardDemoException");
        assertEquals("CONFIG_ERROR", ex.getReasonCode(),
                "the reason code must be \"CONFIG_ERROR\" verbatim per AAP \u00a70.7.2");

        // And: the SDK is never invoked.
        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Test 10.4: Verifies that a {@code null} {@code batchRunDate}
     * argument is safely treated as an empty string in the JSON
     * payload &mdash; the production class uses the defensive idiom
     * {@code String safeDate = (batchRunDate == null) ? "" : batchRunDate;}
     * so that the resulting state-machine input is syntactically valid
     * JSON ({@code {"batchRunDate":""}}) even when no date is supplied.
     * This is the "safe default" branch covered by the
     * {@code safeDate} ternary in the production source.
     */
    @Test
    @DisplayName("startEodPipeline tolerates a null batchRunDate by emitting an empty string in the JSON payload")
    void startEodPipeline_withNullBatchRunDate_usesEmptyStringInJson() {
        // Given: a stubbed successful SFN response.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: the adapter is invoked with a null batchRunDate.
        String executionArn = orchestrator.startEodPipeline(null);

        // Then: the call still succeeds (no NullPointerException).
        assertNotNull(executionArn,
                "startEodPipeline must tolerate a null batchRunDate");

        // And: the captured request carries the empty-string batchRunDate
        // in a syntactically valid JSON envelope.
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());
        assertEquals("{\"batchRunDate\":\"\"}", captor.getValue().input(),
                "null batchRunDate must serialise as an empty string in the JSON payload");
    }

    // =========================================================================
    // Phase 11 \u2014 startReportPipeline convenience method tests
    // =========================================================================

    /**
     * Test 11.1: Verifies that {@code startReportPipeline} delegates to
     * {@code startExecution} using the configured {@code reportPipelineArn}
     * and forwards the report-request JSON payload unchanged.
     *
     * <p>Replaces: {@code CORPT00C} &rarr; CICS TDQ {@code 'JOBS'} &rarr;
     * JES {@code TRANREPT.jcl} submission (AAP &sect;0.1.1 sole
     * online-to-batch bridge). The MSK topic {@code report.requested}
     * carries the JSON message; {@code KafkaEventConsumer} subscribes
     * and invokes this method to start the pipeline.</p>
     */
    @Test
    @DisplayName("startReportPipeline submits the report state machine with the verbatim JSON input")
    void startReportPipeline_withValidJson_invokesStartExecution() {
        // Given: a stubbed successful SFN response.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        String reportRequest = "{\"reportType\":\"MONTHLY\"}";

        // When: the adapter is invoked with a representative report JSON.
        String executionArn = orchestrator.startReportPipeline(reportRequest);

        // Then: the returned execution ARN is non-null.
        assertNotNull(executionArn,
                "startReportPipeline must return a non-null execution ARN");
        assertEquals(SAMPLE_EXECUTION_ARN, executionArn,
                "startReportPipeline must propagate the SDK executionArn() verbatim");

        // And: the captured request targets the configured
        // reportPipelineArn with the verbatim JSON payload.
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());

        StartExecutionRequest captured = captor.getValue();
        assertEquals(VALID_REPORT_ARN, captured.stateMachineArn(),
                "startReportPipeline must use the configured reportPipelineArn");
        assertEquals(reportRequest, captured.input(),
                "startReportPipeline must forward the JSON payload verbatim");
    }

    /**
     * Test 11.2: Verifies that {@code startReportPipeline} throws
     * {@link CardDemoException} with reason code
     * {@code "CONFIG_ERROR"} when the configured
     * {@code carddemo.stepfunctions.report-pipeline-arn} is blank.
     *
     * <p>Mirrors the {@code "CONFIG_ERROR"} contract of
     * {@link #startEodPipeline_whenEodArnIsBlank_throwsConfigError()}
     * but for the report pipeline ARN.</p>
     */
    @Test
    @DisplayName("startReportPipeline throws CardDemoException(CONFIG_ERROR) when report-pipeline-arn is blank")
    void startReportPipeline_whenReportArnIsBlank_throwsConfigError() {
        // Given: the report ARN is mutated to the blank/empty default.
        ReflectionTestUtils.setField(orchestrator, "reportPipelineArn", "");

        // When + Then: CardDemoException is thrown with the verbatim
        // "CONFIG_ERROR" reason code per AAP \u00a70.7.2.
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> orchestrator.startReportPipeline("{\"reportType\":\"MONTHLY\"}"),
                "blank report-pipeline-arn must throw CardDemoException");
        assertEquals("CONFIG_ERROR", ex.getReasonCode(),
                "the reason code must be \"CONFIG_ERROR\" verbatim per AAP \u00a70.7.2");

        // And: the SDK is never invoked (fail-fast on configuration).
        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Test 11.3: Verifies that a {@code null} (rather than blank)
     * {@code reportPipelineArn} also triggers the {@code "CONFIG_ERROR"}
     * branch &mdash; symmetric with Test 10.3 for the EOD pipeline. The
     * production class's {@code (arn == null || arn.isBlank())} guard
     * treats both conditions identically.
     */
    @Test
    @DisplayName("startReportPipeline throws CardDemoException(CONFIG_ERROR) when report-pipeline-arn is null")
    void startReportPipeline_whenReportArnIsNull_throwsConfigError() {
        // Given: the report ARN is explicitly set to null.
        ReflectionTestUtils.setField(orchestrator, "reportPipelineArn", null);

        // When + Then: same reason-code contract as Test 11.2.
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> orchestrator.startReportPipeline("{\"reportType\":\"MONTHLY\"}"),
                "null report-pipeline-arn must throw CardDemoException");
        assertEquals("CONFIG_ERROR", ex.getReasonCode(),
                "the reason code must be \"CONFIG_ERROR\" verbatim per AAP \u00a70.7.2");

        // And: the SDK is never invoked.
        verify(sfnClient, never()).startExecution(any(StartExecutionRequest.class));
    }

    // =========================================================================
    // Phase 12 \u2014 Constructor + JSON-escape edge case tests (auxiliary coverage)
    // =========================================================================

    /**
     * Test 12.1: Verifies that the production constructor rejects a
     * {@code null} {@link SfnClient} with a {@link NullPointerException}
     * &mdash; this is the canonical fail-fast guard documented in the
     * production class's JavaDoc and prevents a corrupted bean from
     * leaking into the Spring application context. The guard surfaces
     * configuration errors immediately at bean instantiation rather
     * than as a confusing NPE on the first {@code sfnClient.startExecution}
     * call.
     */
    @Test
    @DisplayName("Constructor rejects a null SfnClient with NullPointerException")
    void constructor_withNullSfnClient_throwsNullPointerException() {
        // When + Then: passing a null client triggers the documented
        // fail-fast guard inside the constructor body.
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new StepFunctionsOrchestrator(null),
                "constructor must reject a null SfnClient with NullPointerException");
        assertNotNull(ex.getMessage(),
                "NullPointerException must carry a descriptive message");
        assertTrue(ex.getMessage().contains("sfnClient"),
                "NPE message must identify the offending parameter ('sfnClient'), but was: "
                        + ex.getMessage());
    }

    /**
     * Test 12.2: Verifies that the production class's internal JSON
     * escaping logic correctly escapes a double-quote character
     * (and a backslash) supplied as part of {@code batchRunDate}.
     *
     * <p>While the canonical {@code batchRunDate} format is ISO-8601
     * ({@code YYYY-MM-DD}) and never contains special JSON characters,
     * the production class defensively escapes the value before
     * embedding it into the state-machine input JSON envelope. This
     * test exercises the {@code case '"'} and {@code case '\\\\'}
     * branches of the internal {@code escapeJsonString} helper by
     * supplying a deliberately malformed input. The resulting input
     * payload must remain syntactically valid JSON.</p>
     *
     * <p>Although the {@code escapeJsonString} helper is private, the
     * test exercises it indirectly through the public
     * {@link StepFunctionsOrchestrator#startEodPipeline(String)} entry
     * point &mdash; the canonical pattern for unit-testing private
     * helpers without exposing them to API surface area.</p>
     */
    @Test
    @DisplayName("startEodPipeline JSON-escapes special characters in the batchRunDate value")
    void startEodPipeline_withSpecialCharsInBatchRunDate_escapesProperly() {
        // Given: a stubbed successful SFN response.
        StartExecutionResponse mockResponse = StartExecutionResponse.builder()
                .executionArn(SAMPLE_EXECUTION_ARN)
                .startDate(Instant.now())
                .build();
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(mockResponse);

        // When: a batchRunDate containing both a double-quote and a
        // backslash is supplied (a pathological input that would
        // produce malformed JSON if the helper did not escape).
        orchestrator.startEodPipeline("2025\"\\05-20");

        // Then: the captured input is syntactically valid JSON in which
        // the double-quote is escaped as \" and the backslash is
        // escaped as \\.
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());

        String capturedInput = captor.getValue().input();
        // Expected payload: {"batchRunDate":"2025\"\\05-20"} where the
        // backslash-quote escapes the embedded " and the backslash-
        // backslash escapes the embedded \.
        assertEquals("{\"batchRunDate\":\"2025\\\"\\\\05-20\"}", capturedInput,
                "startEodPipeline must escape \" and \\ characters inside batchRunDate");
    }
}
