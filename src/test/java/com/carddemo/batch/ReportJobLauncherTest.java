package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.test.MetaDataInstanceFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.carddemo.exception.FileProcessingException;
import com.carddemo.observability.CorrelationIdFilter;

/**
 * Pure unit test for {@link ReportJobLauncher}, the SQS FIFO&nbsp;&rarr;&nbsp;Spring Batch launcher
 * that is the receive side of the migrated {@code CORPT00C} TDQ&nbsp;&rarr;&nbsp;JES report bridge
 * (source referenced read-only at commit SHA {@code 27d6c6f}; send side is
 * {@code com.carddemo.service.ReportService}).
 *
 * <p>The suite exercises the launcher in isolation with a mocked {@link JobLauncher} and a mocked
 * {@code transactionReportJob} {@link Job}, and a <em>real</em> {@link ObjectMapper} so the JSON
 * message-contract parsing is genuinely tested. It pins:</p>
 * <ul>
 *   <li>the message&nbsp;&rarr;&nbsp;{@link JobParameters} mapping (window, {@code jobId} and the
 *       correlation id under the {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} constant key,
 *       never a re-typed literal);</li>
 *   <li>the identifying/non-identifying flags that drive {@code jobId} deduplication;</li>
 *   <li>the legacy JCL date defaults and the {@link UUID} fallbacks for a partial message;</li>
 *   <li>that every checked {@code JobLauncher.run} failure and a malformed body surface as a typed
 *       {@link FileProcessingException} (so SQS redelivery / DLQ applies) rather than being swallowed
 *       or escaping raw.</li>
 * </ul>
 *
 * <p>No Spring context, database, or network is used, so the suite is fast and compiles warning-free
 * under {@code -Xlint:all} (Gate&nbsp;2), contributing to Gate&nbsp;8 coverage.</p>
 *
 * @see ReportJobLauncher
 * @see CorrelationIdFilter
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportJobLauncher — SQS FIFO report request → transactionReportJob launch")
class ReportJobLauncherTest {

    /** The shared correlation-id job-parameter / MDC key ({@code "correlationId"}). */
    private static final String CORRELATION_KEY = CorrelationIdFilter.CORRELATION_ID_MDC_KEY;

    /** Legacy JCL {@code PARM-START-DATE} default applied when the message omits the window start. */
    private static final String DEFAULT_START_DATE = "2022-01-01";

    /** Legacy JCL {@code PARM-END-DATE} default applied when the message omits the window end. */
    private static final String DEFAULT_END_DATE = "2022-07-06";

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job transactionReportJob;

    private ReportJobLauncher launcher;

    @BeforeEach
    void setUp() {
        // A real mapper so the JSON message contract is genuinely parsed (records bind by component name).
        launcher = new ReportJobLauncher(jobLauncher, transactionReportJob, new ObjectMapper());
    }

    /**
     * A fully populated, valid report-request message body.
     *
     * @return a valid JSON report-request payload
     */
    private static String validBody() {
        return """
                {"jobId":"job-123","reportName":"Monthly","startDate":"2023-03-01",
                 "endDate":"2023-03-31","correlationId":"corr-xyz"}
                """;
    }

    /**
     * Captures the {@link JobParameters} passed to the single {@code jobLauncher.run} invocation.
     *
     * @return the captured job parameters
     */
    private JobParameters captureLaunchedParameters() throws Exception {
        final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionReportJob), captor.capture());
        return captor.getValue();
    }

    // =====================================================================
    // 1) Happy path — message → job parameters mapping + launch
    // =====================================================================

    @Nested
    @DisplayName("launch — maps the message to job parameters and runs transactionReportJob")
    class Launch {

        @Test
        @DisplayName("maps jobId, window and correlationId (under the constant key) onto the parameters")
        void mapsMessageOntoJobParameters() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenReturn(MetaDataInstanceFactory.createJobExecution());

            launcher.onReportRequest(validBody());

            final JobParameters params = captureLaunchedParameters();
            assertThat(params.getString("startDate")).isEqualTo("2023-03-01");
            assertThat(params.getString("endDate")).isEqualTo("2023-03-31");
            assertThat(params.getString("jobId")).isEqualTo("job-123");
            // The correlation id MUST be keyed by the shared constant, not a hardcoded "correlationId".
            assertThat(params.getString(CORRELATION_KEY)).isEqualTo("corr-xyz");
        }

        @Test
        @DisplayName("window + jobId are identifying; correlationId is non-identifying (dedup contract)")
        void identifyingFlagsSupportJobIdDeduplication() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenReturn(MetaDataInstanceFactory.createJobExecution());

            launcher.onReportRequest(validBody());

            final JobParameters params = captureLaunchedParameters();
            assertThat(params.getParameter("startDate").isIdentifying()).isTrue();
            assertThat(params.getParameter("endDate").isIdentifying()).isTrue();
            assertThat(params.getParameter("jobId").isIdentifying()).isTrue();
            // Non-identifying so a freshly minted correlation id cannot defeat jobId dedup.
            assertThat(params.getParameter(CORRELATION_KEY).isIdentifying()).isFalse();
        }

        @Test
        @DisplayName("applies the legacy JCL date defaults when the window is absent")
        void appliesJclDateDefaultsWhenWindowAbsent() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenReturn(MetaDataInstanceFactory.createJobExecution());

            launcher.onReportRequest("""
                    {"jobId":"job-1","reportName":"Custom","correlationId":"c1"}
                    """);

            final JobParameters params = captureLaunchedParameters();
            assertThat(params.getString("startDate")).isEqualTo(DEFAULT_START_DATE);
            assertThat(params.getString("endDate")).isEqualTo(DEFAULT_END_DATE);
        }

        @Test
        @DisplayName("generates a UUID correlation id when the message carries none")
        void generatesCorrelationIdWhenAbsent() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenReturn(MetaDataInstanceFactory.createJobExecution());

            launcher.onReportRequest("""
                    {"jobId":"job-1","reportName":"Monthly","startDate":"2022-01-01","endDate":"2022-07-06"}
                    """);

            final String correlationId = captureLaunchedParameters().getString(CORRELATION_KEY);
            assertThat(correlationId).isNotNull().isNotBlank();
            // Must be a syntactically valid UUID (throws if not).
            assertThat(UUID.fromString(correlationId)).isNotNull();
        }

        @Test
        @DisplayName("generates a UUID jobId when the message carries none")
        void generatesJobIdWhenAbsent() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenReturn(MetaDataInstanceFactory.createJobExecution());

            launcher.onReportRequest("""
                    {"reportName":"Monthly","startDate":"2022-01-01","endDate":"2022-07-06","correlationId":"c1"}
                    """);

            assertThat(captureLaunchedParameters().getString("jobId")).isNotBlank();
        }

        @Test
        @DisplayName("ignores unknown JSON fields so the contract can evolve additively (Gate 5)")
        void ignoresUnknownJsonFields() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenReturn(MetaDataInstanceFactory.createJobExecution());

            launcher.onReportRequest("""
                    {"jobId":"job-1","reportName":"Monthly","startDate":"2022-01-01","endDate":"2022-07-06",
                     "correlationId":"c1","futureField":"ignored","anotherNumber":42}
                    """);

            verify(jobLauncher).run(eq(transactionReportJob), any(JobParameters.class));
        }
    }

    // =====================================================================
    // 2) Launch failures — every checked JobLauncher.run failure → FileProcessingException
    // =====================================================================

    @Nested
    @DisplayName("launch failures — surface as FileProcessingException (never swallowed, never System.exit)")
    class LaunchFailures {

        @Test
        @DisplayName("JobExecutionAlreadyRunningException (duplicate delivery) → FileProcessingException")
        void alreadyRunningSurfacesAsFileProcessingException() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenThrow(new JobExecutionAlreadyRunningException("already running"));

            assertThatThrownBy(() -> launcher.onReportRequest(validBody()))
                    .isInstanceOf(FileProcessingException.class)
                    .hasCauseInstanceOf(JobExecutionAlreadyRunningException.class);
        }

        @Test
        @DisplayName("JobInstanceAlreadyCompleteException (dedup) → FileProcessingException")
        void alreadyCompleteSurfacesAsFileProcessingException() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

            assertThatThrownBy(() -> launcher.onReportRequest(validBody()))
                    .isInstanceOf(FileProcessingException.class)
                    .hasCauseInstanceOf(JobInstanceAlreadyCompleteException.class);
        }

        @Test
        @DisplayName("JobRestartException → FileProcessingException")
        void restartFailureSurfacesAsFileProcessingException() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenThrow(new JobRestartException("cannot restart"));

            assertThatThrownBy(() -> launcher.onReportRequest(validBody()))
                    .isInstanceOf(FileProcessingException.class)
                    .hasCauseInstanceOf(JobRestartException.class);
        }

        @Test
        @DisplayName("JobParametersInvalidException → FileProcessingException")
        void invalidParametersSurfaceAsFileProcessingException() throws Exception {
            when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                    .thenThrow(new JobParametersInvalidException("invalid parameters"));

            assertThatThrownBy(() -> launcher.onReportRequest(validBody()))
                    .isInstanceOf(FileProcessingException.class)
                    .hasCauseInstanceOf(JobParametersInvalidException.class);
        }
    }

    // =====================================================================
    // 3) Malformed messages — parse failures → FileProcessingException, no launch
    // =====================================================================

    @Nested
    @DisplayName("malformed messages — rejected as FileProcessingException without launching")
    class MalformedMessages {

        @Test
        @DisplayName("a non-JSON body is wrapped (Jackson cause preserved) and no job is launched")
        void rejectsNonJsonBody() {
            // Production catches JsonProcessingException and rethrows it as the cause of a
            // FileProcessingException, so the malformed-body diagnostic is preserved for the DLQ.
            assertThatThrownBy(() -> launcher.onReportRequest("this is not json"))
                    .isInstanceOf(FileProcessingException.class)
                    .hasCauseInstanceOf(JsonProcessingException.class);
            verifyNoInteractions(jobLauncher);
        }

        @Test
        @DisplayName("a JSON null literal is rejected with no cause and no job is launched")
        void rejectsJsonNullLiteral() {
            // A literal JSON null parses cleanly to a null value, so production throws a
            // FileProcessingException with NO wrapped cause — distinct from the malformed-body branch.
            assertThatThrownBy(() -> launcher.onReportRequest("null"))
                    .isInstanceOf(FileProcessingException.class)
                    .hasNoCause();
            verifyNoInteractions(jobLauncher);
        }
    }
}
