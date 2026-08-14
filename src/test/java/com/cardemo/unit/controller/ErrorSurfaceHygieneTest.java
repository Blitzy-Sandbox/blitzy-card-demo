/*
 * ******************************************************************
 * Program     : ErrorSurfaceHygieneTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (error-surface contract)
 * Function    : Prove that a failure response from AccountController carries
 *               a stable public code and never a record key, a constraint
 *               name or a relation name, and that a failed report submission
 *               names no queue endpoint in its diagnostic.
 * Source      : app/cbl/COACTVWC.cbl:L747-L757, :L796-L805, :L846-L856
 *                 (three distinct not-found sentences, one per link)
 *               app/cbl/COACTUPC.cbl:L513-L514 (the fourth sentence)
 *               app/cbl/CORPT00C.cbl:L515-L537 (WIRTE-JOBSUB-TDQ and its
 *                 RESP/REAS diagnostic at :L529)
 *               @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.controller.AccountController;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.report.ReportSubmissionService.JobSubmissionMessage;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.MessagingOperationFailedException;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * What a failure tells the caller, and what it tells only the log.
 *
 * <h2>Why a 404 that varies is a problem</h2>
 * {@code app/cbl/COACTVWC.cbl} walks three datasets and composes a different sentence for each one that
 * finds nothing - {@code ' not found in'} at {@code :L750} and {@code :L799}, {@code ' not found'} at
 * {@code :L849} - and {@code app/cbl/COACTUPC.cbl:L513-L514} adds a fourth. On a 3270 all four went to the
 * operator who had just typed the key, and the distinction was a convenience. Over HTTP they go to whoever
 * asked, and the distinction becomes an oracle: a caller that can tell "no such account" from "no such
 * cross-reference row" from "no such customer" can map the schema and probe which identifiers exist in which
 * relation, one request at a time. The same is true of a constraint name, which additionally moves with every
 * migration and therefore cannot be part of a contract at all.
 *
 * <p>So the body carries one fixed detail and one stable code, and the log carries which link fired. These
 * tests assert both halves: that the response is uniform, and that the diagnosis is not lost.
 *
 * <h2>Why an endpoint is not a reason code</h2>
 * {@code MessagingOperationFailedException.getEndpoint()} answers a queue URL, which spells out the region,
 * the account number and the queue name. Rendering it into the diagnostic that reproduces
 * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code app/cbl/CORPT00C.cbl:L529} publishes the
 * deployment's topology. The reason code is therefore a closed symbolic value, and the queue is named by the
 * logical name this application chose for itself.
 *
 * <h2>How to run</h2>
 * {@code ./mvnw -B -ntp -Dtest=ErrorSurfaceHygieneTest test}. No container and no HTTP: the exception
 * handlers are invoked directly, which is what makes the body assertable field by field.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("error-surface hygiene - stable public codes out, detail to the log only")
class ErrorSurfaceHygieneTest {

    /** The account key a caller supplied and that was not found. */
    private static final String ACCOUNT_KEY = "00000000077";

    /** The relation name the throwing site names, which the body must not repeat. */
    private static final String RECORD_TYPE = "cardCrossReference";

    /** A constraint name of the kind {@code V1__create_schema.sql} declares. */
    private static final String CONSTRAINT_NAME = "fk_acct_customer_xref";

    /** The relation the constraint guards. */
    private static final String RELATION = "card_xref";

    /** The property the response is expected to carry instead of any of the above. */
    private static final String CODE_PROPERTY = "code";

    /** The wire form of the not-found code. */
    private static final String NOT_FOUND_CODE = "ACCOUNT_RECORD_NOT_FOUND";

    /** The wire form of the refused-write code. */
    private static final String WRITE_REFUSED_CODE = "ACCOUNT_WRITE_REFUSED";

    /** A queue URL of exactly the shape the publisher reports, region and account number included. */
    private static final String QUEUE_ENDPOINT =
            "https://sqs.eu-west-2.amazonaws.com/123456789012/carddemo-report-jobs.fifo";

    /** The logical queue name, which this application chose and publishes in its own configuration. */
    private static final String QUEUE_LOGICAL_NAME = "JOBS";

    @Mock
    private AccountViewService accountViewService;

    @Mock
    private AccountUpdateService accountUpdateService;

    @Mock
    private SqsTemplate sqsTemplate;

    @Mock
    private DateValidationService dateValidationService;

    /** The captured events of whichever class the test drives. */
    private ListAppender<ILoggingEvent> logEvents;

    /** The logger the appender is attached to. */
    private Logger capturedLogger;

    @AfterEach
    void tearDown() {
        if (this.capturedLogger != null) {
            this.capturedLogger.detachAppender(this.logEvents);
            this.logEvents.stop();
            this.capturedLogger.setLevel(null);
        }
    }

    /**
     * Attaches an in-memory appender to one class's logger at {@link Level#TRACE}.
     *
     * @param type the class whose logger to capture; must not be {@code null}
     */
    private void captureLogsOf(final Class<?> type) {
        this.capturedLogger = (Logger) LoggerFactory.getLogger(type);
        this.logEvents = new ListAppender<>();
        this.logEvents.start();
        this.capturedLogger.addAppender(this.logEvents);
        this.capturedLogger.setLevel(Level.TRACE);
    }

    /**
     * Renders every captured event as one searchable string.
     *
     * @return the concatenated log output, never {@code null}
     */
    private String capturedLogText() {
        final StringBuilder text = new StringBuilder(512);
        for (final ILoggingEvent event : this.logEvents.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getArgumentArray() != null) {
                for (final Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append('\n').append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Renders a problem detail as one searchable string: the detail, the title and every property.
     *
     * @param problem the body to render; must not be {@code null}
     * @return the concatenated body text, never {@code null}
     */
    private static String bodyText(final ProblemDetail problem) {
        final StringBuilder text = new StringBuilder(256);
        text.append(problem.getTitle()).append('\n').append(problem.getDetail()).append('\n');
        final Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            properties.forEach((name, value) -> text.append(name).append('=').append(value).append('\n'));
        }
        return text.toString();
    }

    /** The two handlers the finding named, driven directly. */
    @Nested
    @DisplayName("AccountController - the 404 and 409 bodies")
    final class ProblemBodies {

        private AccountController controller() {
            return new AccountController(accountViewService, accountUpdateService);
        }

        @Test
        @DisplayName("a 404 carries a stable code and neither the record key nor the relation")
        void notFoundCarriesACodeAndNoInternals() {
            captureLogsOf(AccountController.class);

            final ResponseEntity<ProblemDetail> response = controller().handleRecordNotFound(
                    new RecordNotFoundException("Account " + ACCOUNT_KEY + " not found in cross reference",
                            RECORD_TYPE, ACCOUNT_KEY));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            final ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties())
                    .as("the stable code is what a caller branches on")
                    .containsEntry(CODE_PROPERTY, NOT_FOUND_CODE);
            assertThat(bodyText(problem))
                    .as("no key, no relation and no thrown message may reach the caller")
                    .doesNotContain(ACCOUNT_KEY)
                    .doesNotContain(RECORD_TYPE)
                    .doesNotContain("cross reference");
            assertThat(problem.getProperties())
                    .doesNotContainKey("recordKey")
                    .doesNotContainKey("recordType");
        }

        @Test
        @DisplayName("the 404 body is identical whichever link of the chain found nothing")
        void everyLinkProducesTheSameBody() {
            // This is the substance of the finding: a body that varies with the link is an enumeration oracle.
            captureLogsOf(AccountController.class);
            final AccountController controller = controller();

            final String fromCrossReference = bodyText(controller.handleRecordNotFound(
                    new RecordNotFoundException("Account " + ACCOUNT_KEY + " not found in cross reference",
                            "cardCrossReference", ACCOUNT_KEY)).getBody());
            final String fromAccountMaster = bodyText(controller.handleRecordNotFound(
                    new RecordNotFoundException("Account " + ACCOUNT_KEY + " not found in account master",
                            "account", ACCOUNT_KEY)).getBody());
            final String fromCustomerMaster = bodyText(controller.handleRecordNotFound(
                    new RecordNotFoundException("Customer 000000042 not found", "customer",
                            "000000042")).getBody());

            assertThat(fromAccountMaster).isEqualTo(fromCrossReference);
            assertThat(fromCustomerMaster).isEqualTo(fromCrossReference);
        }

        @Test
        @DisplayName("the link that found nothing is still recorded, in the log")
        void theLinkIsStillDiagnosable() {
            captureLogsOf(AccountController.class);

            controller().handleRecordNotFound(new RecordNotFoundException("not found", RECORD_TYPE,
                    ACCOUNT_KEY));

            final String logged = capturedLogText();
            assertThat(logged)
                    .as("an operator must still be able to tell which link fired")
                    .contains(RECORD_TYPE)
                    .contains(NOT_FOUND_CODE);
            // The key IS logged, and only logged. The log is the operator channel: it is where the record
            // type, the key and the status mapper's composed diagnostic all go now that none of them is
            // relayed to the caller, and the correlation identifier in the response body is what ties a
            // reported failure to this entry. What the remediation removed is the ENUMERATION surface - a
            // caller who asks for an account that does not exist learns nothing about which link failed or
            // which key was resolved server side - so the absence is asserted on the body, not here.
            assertThat(controller().handleRecordNotFound(new RecordNotFoundException("not found",
                    RECORD_TYPE, ACCOUNT_KEY)).getBody())
                    .isNotNull()
                    .satisfies(body -> assertThat(String.valueOf(body.getProperties()) + body.getDetail())
                            .as("no response body may carry the record type or the key")
                            .doesNotContain(RECORD_TYPE)
                            .doesNotContain(ACCOUNT_KEY));
        }

        @Test
        @DisplayName("a 409 carries a stable code and neither the constraint name nor the relation")
        void integrityFailureCarriesACodeAndNoInternals() {
            captureLogsOf(AccountController.class);

            final ResponseEntity<ProblemDetail> response = controller().handleDataIntegrity(
                    new DataIntegrityException("write refused", CONSTRAINT_NAME, RELATION));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            final ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).containsEntry(CODE_PROPERTY, WRITE_REFUSED_CODE);
            assertThat(bodyText(problem))
                    .as("a constraint name describes the schema and moves with every migration")
                    .doesNotContain(CONSTRAINT_NAME)
                    .doesNotContain(RELATION);
            assertThat(problem.getProperties())
                    .doesNotContainKey("constraintName")
                    .doesNotContainKey("relation");
        }

        @Test
        @DisplayName("the constraint and relation are still recorded, in the log")
        void theConstraintIsStillDiagnosable() {
            captureLogsOf(AccountController.class);

            controller().handleDataIntegrity(
                    new DataIntegrityException("write refused", CONSTRAINT_NAME, RELATION));

            assertThat(capturedLogText())
                    .contains(CONSTRAINT_NAME)
                    .contains(RELATION)
                    .contains(WRITE_REFUSED_CODE);
        }
    }

    /** The report-submission diagnostic of {@code WIRTE-JOBSUB-TDQ}. */
    @Nested
    @DisplayName("ReportSubmissionService - the RESP/REAS diagnostic of :L529")
    final class SubmissionDiagnostic {

        /** {@code MSG_UNABLE_TO_WRITE_TDQ} at {@code app/cbl/CORPT00C.cbl:L531}, byte for byte. */
        private static final String UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

        private ReportSubmissionService service() {
            return new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class), dateValidationService,
                    Clock.fixed(Instant.parse("2024-03-15T09:41:07Z"), ZoneOffset.UTC), noTracer(),
                    "carddemo-report-jobs.fifo", QUEUE_LOGICAL_NAME, "carddemo-report-jobs",
                    "carddemo-notifications", "error-surface-test-envelope-key-0123456789");
        }

        /**
         * A provider that resolves to no tracer, which is the shape the service must tolerate: tracing is an
         * optional collaborator, and a publish still has to happen when none is registered.
         *
         * @return a provider yielding {@code null}
         */
        @SuppressWarnings("unchecked")
        private static ObjectProvider<Tracer> noTracer() {
            final ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }


        /**
         * A monthly submission with the confirmation asserted, which is the one shape that reaches the
         * publish at {@code :L507}.
         *
         * @return the request, never {@code null}
         */
        private static ReportRequest confirmedMonthlyRequest() {
            return new ReportRequest("CR00", null, null, null, null, null,
                    "Y", null, null, null, null, null, null, null, null, "Y", null);
        }

        @BeforeEach
        void stubTheFailingPublish() {
            // sendAsync, not send. The service publishes asynchronously and then waits on the returned
            // future for a bounded deadline, so a stub of the synchronous overload is never invoked and the
            // unstubbed asynchronous one answers null - which the wait dereferences, turning this test into
            // an assertion about a NullPointerException instead of about a failed publish.
            //
            // The future is completed exceptionally rather than the call throwing, because that is how an
            // asynchronous publisher reports failure: the service unwraps the ExecutionException and
            // classifies the CAUSE, which is the path that must be exercised to reach the 'unavailable'
            // reason. A stub that threw synchronously would land in the catch(RuntimeException) arm instead
            // and classify the same exception by a different route.
            //
            // ArgumentMatchers.<T>any() is fully generic, so the Consumer<SqsSendOptions<T>> parameter is
            // matched without an unchecked cast - which matters because the build runs -Xlint:all -Werror.
            when(sqsTemplate.<JobSubmissionMessage>sendAsync(
                    ArgumentMatchers.<Consumer<SqsSendOptions<JobSubmissionMessage>>>any()))
                    .thenReturn(CompletableFuture.failedFuture(
                            new MessagingOperationFailedException("send failed", QUEUE_ENDPOINT)));
        }

        @Test
        @DisplayName("the reason code is a closed symbolic value, never the queue endpoint")
        void theReasonCodeIsSymbolic() {
            captureLogsOf(ReportSubmissionService.class);

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service().submitScreen(
                            ReportSubmissionService.AttentionIdentifier.ENTER, confirmedMonthlyRequest()))
                    .withMessage(UNABLE_TO_WRITE_TDQ);

            final String logged = capturedLogText();
            assertThat(logged).as("the diagnostic is emitted, so these assertions are not vacuous")
                    .contains("RESP:");
            assertThat(logged)
                    .as("an endpoint spells out the region, the account number and the queue name")
                    .doesNotContain(QUEUE_ENDPOINT)
                    .doesNotContain("sqs.eu-west-2.amazonaws.com")
                    .doesNotContain("123456789012")
                    .doesNotContain("amazonaws");
            // What survives is a symbolic reason from a closed vocabulary - unavailable, timeout,
            // interrupted, error - plus the failing type's class name and the LOGICAL queue name, which is a
            // literal in application.yml and therefore provably free of an account identifier. The RESP slot
            // may not carry MessagingOperationFailedException.getEndpoint(), a resolved queue URL that
            // spells out the region and the owning account number on a channel an operator reads.
            //
            // 'unavailable' specifically: the publisher reported a messaging failure, which is the one reason
            // that tells an operator the substrate is reachable but refused the write. A class name is a
            // compile-time symbol and cannot carry an endpoint, an account identifier or a credential.
            assertThat(logged)
                    .as("what survives is a symbolic reason, the failing type and the logical queue name")
                    .contains("RESP:unavailable")
                    .contains(MessagingOperationFailedException.class.getSimpleName())
                    .contains(QUEUE_LOGICAL_NAME);
        }

        @Test
        @DisplayName("the screen literal of :L531 is unchanged, byte for byte")
        void theScreenLiteralIsUnchanged() {
            // The reason code is a diagnostic; the message the caller sees is a parity contract.
            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service().submitScreen(
                            ReportSubmissionService.AttentionIdentifier.ENTER, confirmedMonthlyRequest()))
                    .withMessage(UNABLE_TO_WRITE_TDQ);
        }
    }
}
