/*
 * ******************************************************************
 * Program     : ReportSubmissionServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that ReportSubmissionService reproduces CORPT00C
 *               exactly - ten paragraphs mapped one to one, three entry
 *               modes and three attention identifiers, the monthly
 *               period resolved as a FULL CALENDAR MONTH with the
 *               twelve-month roll, the yearly period as January first
 *               through December thirty-first, the custom period through
 *               its six ordered emptiness guards, its NUMVAL-like
 *               normalisation, its string upper bounds and its
 *               start-then-end date validation with severity 0000
 *               accepted outright and message number 2513 tolerated, the
 *               four-state confirmation handshake, the seventeen
 *               eighty-byte job cards collapsed into one FIFO message,
 *               and the byte-exact queue-failure literal
 * Source      : app/cbl/CORPT00C.cbl (649 lines, 10 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/CORPT00.CPY (17 input fields) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
 * Source      : app/cpy/COTTL01Y.cpy (CCDA-TITLE01, CCDA-TITLE02) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (TRANSACTION(CR00), TDQUEUE(JOBS)) @ 7756d89
 * Source      : app/cbl/CSUTLDTC.cbl (the date validator behind :392) @ 7756d89
 * Source      : app/proc/TRANREPT.prc (the job the deck submits) @ 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService.AttentionIdentifier;
import com.cardemo.service.report.ReportSubmissionService.JobSubmissionMessage;
import com.cardemo.service.report.ReportSubmissionService.ReportSubmissionScreen;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.MessagingOperationFailedException;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Tracer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.support.GenericMessage;

/**
 * Unit tests for {@code com.cardemo.service.report.ReportSubmissionService}, the Java replacement for
 * {@code app/cbl/CORPT00C.cbl} - 649 lines and 10 paragraphs, the CICS program behind transaction
 * {@code CR00}, which resolves a reporting period and submits the transaction-report job.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves parity against the frozen source rather than against an idea of what the source ought to do.
 * Every assertion cites the paragraph or line it proves, and the citations were verified by direct
 * inspection at commit {@code 7756d89}. Six groups of behaviour carry the weight.
 *
 * <ul>
 *   <li><strong>The monthly period is a FULL CALENDAR MONTH, not month-to-date.</strong> This is the single
 *       most easily got-wrong value on this path, and the frozen source settles it: {@code :223} does
 *       {@code MOVE 1 TO WS-CURDATE-DAY}, {@code :224-228} adds one to the month with the twelve-month roll,
 *       and {@code :229-230} takes {@code DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1)} - the first of the next
 *       month less one day, which is the last day of <em>this</em> one. The end date is therefore the month
 *       end and never today. Asserted across a mid-month day, a month end, both February lengths and
 *       December, so the roll is proved rather than assumed.</li>
 *   <li><strong>The custom period's five stages, in order.</strong> Six emptiness guards at
 *       {@code :259-300} in their source order; a NUMVAL-like normalisation at {@code :305-327} that drops
 *       non-digits, stops at a decimal point and reduces modulo the receiving width; six range checks at
 *       {@code :329-379} performed as <em>string</em> comparisons against {@code '12'} and {@code '31'} with
 *       <em>no bound at all</em> on either year; assembly to {@code YYYY-MM-DD}; then start-then-end
 *       validation.</li>
 *   <li><strong>Message number 2513 is tolerated.</strong> {@code :399} accepts a non-zero severity when the
 *       message number is {@code '2513'}. This suite proves it with the <em>real</em>
 *       {@link DateValidationService}, for which a date before the Lillian day zero of 15 October 1582
 *       genuinely reports severity {@code 0003} and message number {@code 2513} - so a custom range starting
 *       in the year 1000 is <em>accepted</em>, exactly as the source accepts it.</li>
 *   <li><strong>The four-state confirmation handshake.</strong> {@code :464-493}: blank re-prompts naming the
 *       report, {@code 'Y'} or {@code 'y'} publishes, {@code 'N'} or {@code 'n'} clears the form and ends the
 *       turn <em>with no message and no cursor</em>, and anything else quotes the offending value back. The
 *       gate is the {@code PIC X(1)} map field, so it is truncated to one character first - which means
 *       {@code "YES"} confirms, a quirk preserved and asserted.</li>
 *   <li><strong>Seventeen job cards become one FIFO message.</strong> {@code :498-508} wrote seventeen
 *       eighty-byte cards including the {@code /*EOF} terminator, which is written <em>before</em> the loop
 *       exits. One typed message replaces the deck, published exactly once, carrying the queue name, the
 *       payload and the message group from configuration - and no deduplication identifier.</li>
 *   <li><strong>The queue-failure literal.</strong> {@code :528-534} produces
 *       {@code Unable to Write TDQ (JOBS)...} byte for byte, with the cause preserved and <em>no</em>
 *       fabricated file status: the program declares no {@code FILE-CONTROL}, no {@code SELECT} and no
 *       {@code FD}, so there is no I/O status to render.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} - runs this class under {@code maven-surefire-plugin:3.5.4}. Residence
 *       in the {@code unit} tree is load-bearing: a class outside it matches neither Surefire's nor
 *       Failsafe's include set and would silently never run.</li>
 *   <li>{@code ./mvnw -B -ntp test-compile} - {@code -Xlint:all -Werror} with {@code failOnWarning} reaches
 *       test compilation, so one unused import is fatal.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=ReportSubmissionServiceTest test} - runs this class alone.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>A real date validator.</strong> {@link DateValidationService} is a pure function over an
 *       injected clock, and its outcomes are the behaviour under test, so the real one is used rather than a
 *       double. That is what makes the {@code '2513'} exemption provable instead of merely stubbed: no
 *       fabricated eighty-character result area appears anywhere in this file.</li>
 *   <li><strong>A hand-written send-options recorder.</strong> {@link SqsSendOptions} is a fluent interface,
 *       and the production code configures it inside a lambda the template invokes. A recording
 *       implementation captures exactly what the lambda sets - and, just as importantly, what it does
 *       <em>not</em> set - which a return-value assertion could not see.</li>
 *   <li><strong>Fixed and advancing clocks.</strong> A clock fixed at {@code 2022-06-10T19:27:53Z} for the
 *       deterministic cases, and an advancing clock that counts its reads for the two tests that prove each
 *       period is derived from a <em>single</em> reading and cannot straddle a boundary.</li>
 *   <li><strong>Mockito strict stubs.</strong> An unused stub fails the test, so the publish stub is
 *       arranged only in the tests that reach the queue.</li>
 *   <li><strong>Synthetic configuration.</strong> The queue name, logical name and message group are
 *       injected values; the literals here are transparently synthetic and no endpoint, credential or
 *       account identifier appears anywhere in this file.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The monthly end date becomes today.</strong> Someone implemented month-to-date. The frozen
 *       source computes the month end; the report would silently cover a short period. Remedy: restore
 *       {@code first-of-next-month minus one day}. Severity: <strong>High</strong>.</li>
 *   <li><strong>A date before 1582 starts being rejected.</strong> The {@code '2513'} exemption was dropped.
 *       Remedy: restore the message-number test at {@code :399}. Severity: <strong>High</strong>.</li>
 *   <li><strong>The year gains an upper bound.</strong> {@code :347-354} and {@code :373-379} test numeric
 *       only - no bound of any kind - so a year of {@code 0000} must reach the date validator and be
 *       rejected <em>there</em>, with the date literal rather than the year literal. Remedy: remove the
 *       bound. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>The confirmation stops accepting a longer value.</strong> The gate is a {@code PIC X(1)}
 *       field, so the source only ever saw one character and {@code "YES"} confirmed. Remedy: truncate
 *       before comparing. Severity: <strong>Low</strong>, but it is behaviour.</li>
 *   <li><strong>Two messages are published, or none.</strong> The deck's seventeen cards collapse into one
 *       message; the loop's terminator card is not a second message. Remedy: publish once. Severity:
 *       <strong>Medium</strong>.</li>
 *   <li><strong>The declined arm grows a message or a cursor.</strong> {@code :480-483} sets neither.
 *       Remedy: leave both absent. Severity: <strong>Low</strong>.</li>
 *   </ul>
 */
@DisplayName("ReportSubmissionService: app/cbl/CORPT00C.cbl - resolve a period and submit the job (CR00)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ReportSubmissionServiceTest {

    // ----------------------------------------------------------------------------------------------------
    // Screen and program identity, app/cbl/CORPT00C.cbl:56-57 and app/cpy/COTTL01Y.cpy:18-22.
    // ----------------------------------------------------------------------------------------------------

    /** {@code WS-TRANID PIC X(04) VALUE 'CR00'}, bound to this program by the CSD. */
    private static final String TRANSACTION_ID = "CR00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'}. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** The {@code EIBCALEN = 0} destination at {@code :172-174}, and the blank default at {@code :542}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The {@code WHEN DFHPF3} destination at {@code :187-189}. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** {@code CCDA-TITLE01 PIC X(40)}, forty characters including its padding. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)}, forty characters including its padding. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    // ----------------------------------------------------------------------------------------------------
    // Cursor fields: the symbolic-map length fields that received MOVE -1.
    // ----------------------------------------------------------------------------------------------------

    /** {@code MONTHLYL}, the shared landing cursor for every non-field failure. */
    private static final String CURSOR_MONTHLY = "MONTHLYL";

    /** {@code SDTMML}. */
    private static final String CURSOR_START_MONTH = "SDTMML";

    /** {@code SDTDDL}. */
    private static final String CURSOR_START_DAY = "SDTDDL";

    /** {@code SDTYYYYL}. */
    private static final String CURSOR_START_YEAR = "SDTYYYYL";

    /** {@code EDTMML}. */
    private static final String CURSOR_END_MONTH = "EDTMML";

    /** {@code EDTDDL}. */
    private static final String CURSOR_END_DAY = "EDTDDL";

    /** {@code EDTYYYYL}. */
    private static final String CURSOR_END_YEAR = "EDTYYYYL";

    /** {@code CONFIRML}. */
    private static final String CURSOR_CONFIRM = "CONFIRML";

    // ----------------------------------------------------------------------------------------------------
    // Literals. Byte exact: every ellipsis exactly three periods, "can NOT" with capital N O T, and the
    // submitted notice carrying a space before its ellipsis where the empties do not.
    // ----------------------------------------------------------------------------------------------------

    /** {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}, used at {@code :190-194}. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** {@code :260-261}. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** {@code :267-268}. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** {@code :274-275}. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** {@code :281-282}. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** {@code :288-289}. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** {@code :295-296}. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** {@code :330-331}. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** {@code :339-340}. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** {@code :348-349}. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** {@code :356-357}. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** {@code :365-366}. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** {@code :374-375}. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** {@code :400-401}, raised by the assembled-date validation rather than a component check. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** {@code :420-421}. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** {@code :437-442}, the {@code WHEN OTHER} arm with no selector supplied. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** {@code :465-470}, assembled around the report name. */
    private static final String MSG_CONFIRM_PREFIX = "Please confirm to print the ";

    /** {@code :465-470}. */
    private static final String MSG_CONFIRM_SUFFIX = " report...";

    /**
     * {@code :485-490}, which quotes the offending value back. Note that this constant <em>opens</em> with
     * the closing double quote of the quoted value, exactly as the source's third {@code STRING} fragment
     * does, so a caller appends it directly after the value and adds no quote of its own.
     */
    private static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /** {@code :449-452}, assembled around the report name with {@code DELIMITED BY SPACE}. */
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /** {@code :531-532}. */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    // ----------------------------------------------------------------------------------------------------
    // The three report names, app/cbl/CORPT00C.cbl:214, :240 and :433. Unpadded, because DELIMITED BY SPACE
    // stops at the first blank of the PIC X(10) field.
    // ----------------------------------------------------------------------------------------------------

    /** {@code MOVE 'Monthly' TO WS-REPORT-NAME} at {@code :214}. */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /** {@code MOVE 'Yearly' TO WS-REPORT-NAME} at {@code :240}. */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /** {@code MOVE 'Custom' TO WS-REPORT-NAME} at {@code :433}. */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    // ----------------------------------------------------------------------------------------------------
    // Time. The instant every seed fixture carries.
    // ----------------------------------------------------------------------------------------------------

    /** The reference instant: the tenth of June 2022, mid-month, so the month end is distinguishable. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** {@code MM/DD/YY} as {@code :622-626} assembles it. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** {@code HH:MM:SS} as {@code :627-628} assembles it. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    // ----------------------------------------------------------------------------------------------------
    // Injected configuration. Transparently synthetic: no endpoint, credential or account identifier.
    // ----------------------------------------------------------------------------------------------------

    /** The physical queue name, {@code carddemo.aws.sqs.report-queue}. */
    private static final String QUEUE_NAME = "unit-test-report-jobs.fifo";

    /** The logical name, standing in for {@code DEFINE TDQUEUE(JOBS)} in diagnostics. */
    private static final String QUEUE_LOGICAL_NAME = "JOBS";

    /** The FIFO message group, {@code carddemo.aws.sqs.report-message-group-id}. */
    private static final String MESSAGE_GROUP_ID = "unit-test-report-group";

    /**
     * The notification topic, {@code carddemo.aws.sns.notification-topic}.
     *
     * <p>The operator notification is the counterpart of the {@code NOTIFY} continuation on the job card
     * that {@code app/cbl/CORPT00C.cbl} carries among its eighty-byte card images, so the topic is addressed
     * by name and never carries a report parameter.
     */
    private static final String TOPIC = "unit-test-carddemo-notifications";

    /**
     * The expanded status a failure carries when the throwing site had no file status at all. This program
     * declares no {@code FILE-CONTROL}, no {@code SELECT} and no {@code FD}, so there is none to render, and
     * this is the faithful rendering of an uninitialised two-byte status field.
     */
    private static final String NO_FILE_STATUS = " 032";

    // ----------------------------------------------------------------------------------------------------
    // Collaborators.
    // ----------------------------------------------------------------------------------------------------

    @Mock
    private SqsTemplate sqsTemplate;

    /** Real, not stubbed: its outcomes are the behaviour under test. */
    private DateValidationService dateValidationService;

    /** Captures exactly what the production lambda sets on the send options, and what it does not. */
    private RecordingSendOptions sendOptions;

    private ReportSubmissionService service;

    /** Assembles the bean over the publisher double, a real date validator and the fixed clock. */
    @BeforeEach
    void setUp() {
        final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        this.dateValidationService = new DateValidationService(clock);
        this.sendOptions = new RecordingSendOptions();
        this.service = new ReportSubmissionService(this.sqsTemplate, mock(SnsTemplate.class),
                this.dateValidationService, clock,
                noTracer(), QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC);
    }

    // -----------------------------------------------------------------------------------------------------
    // Fixtures and helpers. Every helper is used; an unused one would be dead code under Rule 1 Clause B.
    // -----------------------------------------------------------------------------------------------------

    /**
     * Builds the service over an alternative clock, for the period and header tests that need one.
     *
     * @param clock the time source; must not be {@code null}
     * @return a service reading that clock
     */
    private ReportSubmissionService serviceWithClock(final Clock clock) {
        return new ReportSubmissionService(this.sqsTemplate, mock(SnsTemplate.class),
                new DateValidationService(clock), clock,
                noTracer(), QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC);
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
        // lenient() rather than when(): this class runs under STRICT_STUBS, and tracing is an OPTIONAL
        // collaborator that only the publish path resolves (ReportSubmissionService:2396). Most tests here
        // construct the service to exercise validation, period derivation or message-group rules and never
        // reach that line, so an eager stub would be reported as unnecessary and fail the test that set it.
        // Leniency is scoped to this one stub, so every other stub in the class stays strictly checked; the
        // service's actual use of the provider is asserted by ReportSubmissionServiceIntegrationSeamTest,
        // which supplies a real tracer instead of withholding one.
        lenient().when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }


    /**
     * Builds a seventeen-component map area. The six header components carry values that differ from what
     * the service computes, so any assertion on a returned header proves they were overwritten rather than
     * echoed.
     *
     * @param monthly      the monthly selector; may be {@code null}
     * @param yearly       the yearly selector; may be {@code null}
     * @param custom       the custom selector; may be {@code null}
     * @param startMonth   the start month component; may be {@code null}
     * @param startDay     the start day component; may be {@code null}
     * @param startYear    the start year component; may be {@code null}
     * @param endMonth     the end month component; may be {@code null}
     * @param endDay       the end day component; may be {@code null}
     * @param endYear      the end year component; may be {@code null}
     * @param confirmation the confirmation gate; may be {@code null}
     * @return the map area
     */
    private static ReportRequest mapArea(final String monthly, final String yearly, final String custom,
            final String startMonth, final String startDay, final String startYear,
            final String endMonth, final String endDay, final String endYear, final String confirmation) {
        return new ReportRequest("ZZZZ", "submitted-title-one", "01/01/00", "ZZZZZZZZ",
                "submitted-title-two", "00:00:00",
                monthly, yearly, custom,
                startMonth, startDay, startYear, endMonth, endDay, endYear,
                confirmation, "submitted-message");
    }

    /**
     * A monthly request with the given confirmation gate.
     *
     * @param confirmation the gate; may be {@code null}
     * @return the map area
     */
    private static ReportRequest monthlyRequest(final String confirmation) {
        return mapArea("Y", null, null, null, null, null, null, null, null, confirmation);
    }

    /**
     * A yearly request with the given confirmation gate.
     *
     * @param confirmation the gate; may be {@code null}
     * @return the map area
     */
    private static ReportRequest yearlyRequest(final String confirmation) {
        return mapArea(null, "Y", null, null, null, null, null, null, null, confirmation);
    }

    /**
     * A custom request over the six date components, confirmed.
     *
     * @param startMonth the start month component; may be {@code null}
     * @param startDay   the start day component; may be {@code null}
     * @param startYear  the start year component; may be {@code null}
     * @param endMonth   the end month component; may be {@code null}
     * @param endDay     the end day component; may be {@code null}
     * @param endYear    the end year component; may be {@code null}
     * @return the map area
     */
    private static ReportRequest customRequest(final String startMonth, final String startDay,
            final String startYear, final String endMonth, final String endDay, final String endYear) {
        return mapArea(null, null, "Y", startMonth, startDay, startYear, endMonth, endDay, endYear, "Y");
    }

    /**
     * A custom request over a range the real validator accepts outright, with the given gate.
     *
     * @param confirmation the gate; may be {@code null}
     * @return the map area
     */
    private static ReportRequest customRequestConfirmedWith(final String confirmation) {
        return mapArea(null, null, "Y", "06", "01", "2022", "06", "30", "2022", confirmation);
    }

    /**
     * Arranges the publisher to invoke the production lambda against the recorder and report success.
     *
     * <p>Installed only in the tests that reach the queue, because an unused stub fails under strict stubs -
     * which makes the absence of this call in a test an assertion in its own right.
     */
    private void arrangePublish() {
        doAnswer(invocation -> {
            final Consumer<SqsSendOptions<JobSubmissionMessage>> configurer = invocation.getArgument(0);
            configurer.accept(this.sendOptions);
            // sendAsync, not send: the service publishes asynchronously and then waits on the returned future
            // for a bounded deadline (ReportSubmissionService:2340-2346), so a stub of the synchronous form is
            // never invoked and the unstubbed asynchronous one answers null, which the wait dereferences.
            return CompletableFuture.completedFuture(new SendResult<>(
                    UUID.nameUUIDFromBytes("unit-test".getBytes(StandardCharsets.UTF_8)),
                    QUEUE_NAME, new GenericMessage<>(this.sendOptions.payload()), Map.of()));
        }).when(this.sqsTemplate).sendAsync(any());
    }

    /**
     * Reads a private static constant off the service, so a declared value is asserted against the
     * production declaration rather than a copy of it.
     *
     * @param name the field name; must not be {@code null}
     * @return the declared value
     * @throws ReflectiveOperationException if the field is absent, which is itself the finding
     */
    private static Object declaredConstant(final String name) throws ReflectiveOperationException {
        final Field field = ReportSubmissionService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    // =====================================================================================================
    // 1. Construction
    // =====================================================================================================

    /**
     * Constructor injection only, with every collaborator and configuration value refused when absent.
     *
     * <p>Nine arguments, and each arm below withholds exactly one of them while supplying the other eight,
     * so the message it asserts can only have come from the argument under test. The publish path and the
     * notification path each contribute a template, and the notification topic is required for the same
     * reason the queue name is: a set-but-absent destination is a runtime failure on the first submission
     * rather than at startup, which is the outcome fail-fast construction exists to prevent.
     */
    @Nested
    @DisplayName("1. Construction - nine dependencies, each refused when absent")
    class Construction {

        /** The clock every arm supplies, so that only the withheld argument can be the cause. */
        private Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        @Test
        @DisplayName("the publisher is required")
        void thePublisherIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(null, mock(SnsTemplate.class),
                            dateValidationService, fixedClock(), noTracer(), QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("sqsTemplate must not be null");
        }

        @Test
        @DisplayName("the notification template is required, because the operator notify is not optional")
        void theNotificationTemplateIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, null,
                            dateValidationService, fixedClock(), noTracer(), QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("snsTemplate must not be null");
        }

        @Test
        @DisplayName("the date validator is required, because the custom period cannot resolve without it")
        void theDateValidatorIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class), null,
                            fixedClock(), noTracer(), QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("dateValidationService must not be null");
        }

        @Test
        @DisplayName("the time source is required, so no path can reach a wall clock")
        void theTimeSourceIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                            dateValidationService, null, noTracer(), QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("the tracer provider is required, although the tracer it yields is not")
        void theTracerProviderIsRequired() {
            // An absent provider and a provider that yields no tracer are different facts. The service
            // tolerates the second - noTracer() is what every other arm supplies - and refuses the first,
            // because a null provider would fail on the first span rather than at startup.
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                            dateValidationService, fixedClock(), null, QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("tracerProvider must not be null");
        }

        @Test
        @DisplayName("the queue name is required")
        void theQueueNameIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                            dateValidationService, fixedClock(), noTracer(), null,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("reportQueueName must not be null");
        }

        @Test
        @DisplayName("the logical queue name is required, being what diagnostics name")
        void theLogicalQueueNameIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                            dateValidationService, fixedClock(), noTracer(), QUEUE_NAME, null,
                            MESSAGE_GROUP_ID, TOPIC))
                    .withMessage("reportQueueLogicalName must not be null");
        }

        @Test
        @DisplayName("the FIFO message group is required")
        void theMessageGroupIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                            dateValidationService, fixedClock(), noTracer(), QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, null, TOPIC))
                    .withMessage("reportMessageGroupId must not be null");
        }

        @Test
        @DisplayName("the notification topic is required, being what the notify is addressed to")
        void theNotificationTopicIsRequired() {
            // The template carries no default destination, so the topic name is the whole address. A null
            // one would publish nowhere and be discovered on the first accepted submission.
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                            dateValidationService, fixedClock(), noTracer(), QUEUE_NAME,
                            QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, null))
                    .withMessage("notificationTopic must not be null");
        }

        @Test
        @DisplayName("every instance field is private and final, so the bean holds no state across turns")
        void everyInstanceFieldIsPrivateAndFinal() {
            final List<Field> mutable = Arrays.stream(ReportSubmissionService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers())
                            || !Modifier.isPrivate(field.getModifiers()))
                    .toList();
            assertThat(mutable)
                    .as("the pseudo-conversational WS-ERR-FLG and WS-MESSAGE became method-local values, so "
                            + "no turn can leave residue for the next")
                    .isEmpty();
        }
    }

    // =====================================================================================================
    // 2. Paragraph correspondence
    // =====================================================================================================

    /**
     * Ten paragraph labels, ten private methods. {@code app/cbl/CORPT00C.cbl} declares
     * {@code MAIN-PARA} at :163, {@code PROCESS-ENTER-KEY} at :208, {@code SUBMIT-JOB-TO-INTRDR} at :462,
     * {@code WIRTE-JOBSUB-TDQ} at :515 - the source's own misspelling, preserved - {@code RETURN-TO-PREV-SCREEN}
     * at :540, {@code SEND-TRNRPT-SCREEN} at :556, {@code RETURN-TO-CICS} at :585,
     * {@code RECEIVE-TRNRPT-SCREEN} at :596, {@code POPULATE-HEADER-INFO} at :609 and
     * {@code INITIALIZE-ALL-FIELDS} at :633. Ten and not eleven: {@code app/cpy/CSSTRPFY.cpy} is not among
     * this program's {@code COPY} members, so the key cascade is inline in {@code MAIN-PARA}.
     */
    @Nested
    @DisplayName("2. Paragraph correspondence - ten labels, ten private methods, three public entry points")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("all ten paragraphs have a private counterpart")
        void allTenParagraphsHaveAPrivateCounterpart() {
            final List<String> expected = List.of("mainPara", "processEnterKey", "submitJobToIntrdr",
                    "wirteJobsubTdq", "returnToPrevScreen", "sendTrnrptScreen", "returnToCics",
                    "receiveTrnrptScreen", "populateHeaderInfo", "initializeAllFields");
            final List<String> declared = Arrays.stream(ReportSubmissionService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPrivate(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .toList();
            assertThat(declared).containsAll(expected);
        }

        @Test
        @DisplayName("the source's own misspelling of WIRTE-JOBSUB-TDQ is preserved, not corrected")
        void theSourceMisspellingIsPreserved() {
            assertThat(Arrays.stream(ReportSubmissionService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .as("app/cbl/CORPT00C.cbl:515 spells it WIRTE; renaming it would break the paragraph map "
                            + "the scope-coverage gate reads")
                    .contains("wirteJobsubTdq")
                    .doesNotContain("writeJobsubTdq");
        }

        @Test
        @DisplayName("exactly three public operations exist, one per entry mode")
        void exactlyThreePublicOperationsExist() {
            final List<String> publicMethods = Arrays.stream(ReportSubmissionService.class
                            .getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(publicMethods)
                    .containsExactly("openReportScreen", "openWithoutContext", "submitScreen");
        }

        @Test
        @DisplayName("the attention identifier has exactly three constants, matching EVALUATE EIBAID")
        void theAttentionIdentifierHasExactlyThreeConstants() {
            assertThat(AttentionIdentifier.values())
                    .as("app/cbl/CORPT00C.cbl:184-195 has three arms and no more")
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.OTHER);
        }
    }

    // =====================================================================================================
    // 3. Entry modes and the attention identifier - app/cbl/CORPT00C.cbl:165-195
    // =====================================================================================================

    /**
     * The three entry modes of {@code MAIN-PARA} and the three arms of {@code EVALUATE EIBAID}. Nothing in
     * this group reaches the queue, which is why no publish stub is arranged: an unused stub would fail the
     * test, so its absence is itself the assertion.
     */
    @Nested
    @DisplayName("3. Entry modes and EIBAID :165-195 - first entry, no commarea, and the three key arms")
    class EntryModeAndAttentionIdentifier {

        @Test
        @DisplayName("first entry paints an empty form with the cursor on the monthly selector")
        void firstEntryPaintsAnEmptyForm() {
            final ReportSubmissionScreen screen = service.openReportScreen();

            assertThat(screen.form().monthlySelected())
                    .as(":179 MOVE LOW-VALUES TO CORPT0AO - the whole map area is cleared")
                    .isNull();
            assertThat(screen.form().yearlySelected()).isNull();
            assertThat(screen.form().customSelected()).isNull();
            assertThat(screen.form().confirmation()).isNull();
            assertThat(screen.cursorField())
                    .as(":180 MOVE -1 TO MONTHLYL")
                    .isEqualTo(CURSOR_MONTHLY);
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.successHighlight()).isFalse();
            assertThat(screen.navigationTarget())
                    .as("the form is painted, not left")
                    .isNull();
            assertThat(screen.form().errorMessage())
                    .as(":169-170 MOVE SPACES TO WS-MESSAGE and to ERRMSGO")
                    .isEmpty();
        }

        @Test
        @DisplayName("first entry populates the six header fields, because SEND-TRNRPT-SCREEN performs :558")
        void firstEntryPopulatesTheHeader() {
            final ReportSubmissionScreen screen = service.openReportScreen();

            assertThat(screen.form().transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(screen.form().programName()).isEqualTo(PROGRAM_NAME);
            assertThat(screen.form().title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(screen.form().title02()).isEqualTo(SCREEN_TITLE_02);
            assertThat(screen.form().currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(screen.form().currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @Test
        @DisplayName("an absent communication area leaves for the sign-on program without painting anything")
        void anAbsentCommunicationAreaLeavesForSignOn() {
            final ReportSubmissionScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget())
                    .as(":172-174 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(screen.form().transactionName())
                    .as("RETURN-TO-PREV-SCREEN does not perform POPULATE-HEADER-INFO, so nothing is painted")
                    .isNull();
            assertThat(screen.cursorField()).isNull();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.successHighlight()).isFalse();
        }

        @Test
        @DisplayName("PF3 leaves for the main menu, carrying the submitted map area unchanged")
        void pf3LeavesForTheMainMenu() {
            final ReportRequest submitted = monthlyRequest("Y");

            final ReportSubmissionScreen screen = service.submitScreen(AttentionIdentifier.PF3, submitted);

            assertThat(screen.navigationTarget())
                    .as(":187-189 MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM")
                    .isEqualTo(MAIN_MENU_PROGRAM);
            assertThat(screen.form())
                    .as("RECEIVE-TRNRPT-SCREEN :598-603 reads the map and examines nothing")
                    .isEqualTo(submitted);
            assertThat(screen.cursorField()).isNull();
        }

        @Test
        @DisplayName("PF3 never reaches the queue, so leaving cannot submit a job")
        void pf3NeverReachesTheQueue() {
            service.submitScreen(AttentionIdentifier.PF3, monthlyRequest("Y"));

            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("any other key produces the shared invalid-key message on the monthly selector")
        void anyOtherKeyProducesTheSharedInvalidKeyMessage() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.OTHER, monthlyRequest("Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_INVALID_KEY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_MONTHLY);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("the attention identifier is required")
        void theAttentionIdentifierIsRequired() {
            final ReportRequest request = monthlyRequest("Y");
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null, request))
                    .withMessage("attentionIdentifier must not be null");
        }

        @Test
        @DisplayName("the map area is required on a re-enter turn")
        void theMapAreaIsRequiredOnAReEnterTurn() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, null))
                    .withMessage("request must not be null");
        }
    }

    // =====================================================================================================
    // 4. Report-type precedence - app/cbl/CORPT00C.cbl:212-442
    // =====================================================================================================

    /**
     * {@code EVALUATE TRUE} with three selector arms and a {@code WHEN OTHER}. First match wins, so a form
     * with several selectors set resolves the earliest, and the later ones are never evaluated.
     */
    @Nested
    @DisplayName("4. Report-type precedence :212-442 - monthly, then yearly, then custom, then none")
    class ReportTypePrecedence {

        @Test
        @DisplayName("monthly wins when every selector is supplied")
        void monthlyWinsWhenEverySelectorIsSupplied() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea("Y", "Y", "Y", "99", "99", "9999", "99", "99", "9999", "Y"));

            assertThat(sendOptions.payload().reportName())
                    .as("the monthly arm at :213 is evaluated first, so the invalid custom components below "
                            + "it are never reached")
                    .isEqualTo(REPORT_NAME_MONTHLY);
        }

        @Test
        @DisplayName("yearly wins over custom when monthly is absent")
        void yearlyWinsOverCustom() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, "Y", "Y", "99", "99", "9999", "99", "99", "9999", "Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_YEARLY);
        }

        @Test
        @DisplayName("custom is reached only when neither of the first two is supplied")
        void customIsReachedLast() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_CUSTOM);
        }

        @Test
        @DisplayName("no selector at all produces the select-a-report-type message")
        void noSelectorProducesTheSelectMessage() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            mapArea(null, null, null, null, null, null, null, null, null, "Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_SELECT_REPORT_TYPE);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_MONTHLY);
                    });
            verifyNoInteractions(sqsTemplate);
        }

        @ParameterizedTest(name = "a selector of [{0}] counts as not supplied")
        @DisplayName("a blank, empty or low-value selector is not a selection")
        @ValueSource(strings = {"", " ", "  ", "\u0000", "\u0000\u0000"})
        void aBlankOrLowValueSelectorIsNotASelection(final String selector) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            mapArea(selector, null, null, null, null, null, null, null, null, "Y")))
                    .withMessage(MSG_SELECT_REPORT_TYPE);
        }

        @ParameterizedTest(name = "a selector of [{0}] counts as supplied")
        @DisplayName("any non-blank selector is a selection - the source tests presence, never a value")
        @ValueSource(strings = {"Y", "y", "X", "1", "-"})
        void anyNonBlankSelectorIsASelection(final String selector) {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(selector, null, null, null, null, null, null, null, null, "Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_MONTHLY);
        }
    }

    // =====================================================================================================
    // 5. The monthly period - app/cbl/CORPT00C.cbl:213-236
    // =====================================================================================================

    /**
     * <strong>A full calendar month, not month-to-date.</strong> {@code :223} sets the day to one,
     * {@code :224-228} adds one to the month with the twelve-month roll, and {@code :229-230} subtracts one
     * day from the resulting integer date. The end is the month end.
     */
    @Nested
    @DisplayName("5. The monthly period :213-236 - a FULL CALENDAR MONTH with the twelve-month roll")
    class MonthlyPeriod {

        @ParameterizedTest(name = "on {0} the period is {1} through {2}")
        @DisplayName("the period spans the whole calendar month containing today")
        @CsvSource({
            "2022-06-10T19:27:53Z, 2022-06-01, 2022-06-30",
            "2022-06-01T00:00:00Z, 2022-06-01, 2022-06-30",
            "2022-06-30T23:59:59Z, 2022-06-01, 2022-06-30",
            "2022-01-31T12:00:00Z, 2022-01-01, 2022-01-31",
            "2024-02-05T12:00:00Z, 2024-02-01, 2024-02-29",
            "2023-02-05T12:00:00Z, 2023-02-01, 2023-02-28",
            "2022-12-15T12:00:00Z, 2022-12-01, 2022-12-31",
            "2022-11-15T12:00:00Z, 2022-11-01, 2022-11-30"})
        void thePeriodSpansTheWholeCalendarMonth(final String instant, final String expectedStart,
                final String expectedEnd) {
            arrangePublish();
            final ReportSubmissionService onDate =
                    serviceWithClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

            onDate.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo(expectedStart);
            assertThat(sendOptions.payload().endDate()).isEqualTo(expectedEnd);
        }

        @Test
        @DisplayName("the end date is the month end and NOT today, which is the whole point of :229-230")
        void theEndDateIsTheMonthEndAndNotToday() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().endDate())
                    .as("month-to-date would report the tenth; the source reports the thirtieth")
                    .isEqualTo("2022-06-30")
                    .isNotEqualTo("2022-06-10");
        }

        @Test
        @DisplayName("December rolls the month to January of the next year before subtracting a day")
        void decemberRollsToJanuaryBeforeSubtracting() {
            arrangePublish();
            final ReportSubmissionService inDecember = serviceWithClock(
                    Clock.fixed(Instant.parse("2022-12-31T23:00:00Z"), ZoneOffset.UTC));

            inDecember.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-12-01");
            assertThat(sendOptions.payload().endDate())
                    .as(":224-228 ADD 1 TO WS-CURDATE-MONTH with the twelve-month roll, then :229-230 less "
                            + "one day - so the year does not advance in the emitted value")
                    .isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("both dates come from ONE clock reading, so the period cannot straddle a month end")
        void bothDatesComeFromOneClockReading() {
            arrangePublish();
            // Starting on the last day of June and advancing a day per read: a second read would land in
            // July and the end date would become the thirty-first.
            final AdvancingClock advancing = new AdvancingClock(Instant.parse("2022-06-30T12:00:00Z"),
                    Duration.ofDays(1L), ZoneOffset.UTC);
            final ReportSubmissionService onAdvancingClock = serviceWithClock(advancing);

            onAdvancingClock.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate())
                    .as(":215 MOVE FUNCTION CURRENT-DATE happens once and both dates derive from it")
                    .isEqualTo("2022-06-30");
            assertThat(advancing.reads())
                    .as("one read for the period and one for the screen header, and no more")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the report name is the unpadded literal of :214")
        void theReportNameIsTheUnpaddedLiteral() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().reportName())
                    .isEqualTo(REPORT_NAME_MONTHLY)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("both dates are ten-character dashed values, matching PARM-START-DATE-1 PIC X(10)")
        void bothDatesAreTenCharacterDashedValues() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).hasSize(10).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(sendOptions.payload().endDate()).hasSize(10).matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("the monthly arm ignores the six custom date components entirely")
        void theMonthlyArmIgnoresTheCustomComponents() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea("Y", null, null, "01", "01", "1999", "12", "31", "1999", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-06-30");
        }
    }

    // =====================================================================================================
    // 6. The yearly period - app/cbl/CORPT00C.cbl:239-253
    // =====================================================================================================

    /** January first through December thirty-first of the current year, with no month arithmetic at all. */
    @Nested
    @DisplayName("6. The yearly period :239-253 - January first through December thirty-first")
    class YearlyPeriod {

        @ParameterizedTest(name = "on {0} the period is {1} through {2}")
        @DisplayName("the period spans the whole calendar year containing today")
        @CsvSource({
            "2022-06-10T19:27:53Z, 2022-01-01, 2022-12-31",
            "2022-01-01T00:00:00Z, 2022-01-01, 2022-12-31",
            "2022-12-31T23:59:59Z, 2022-01-01, 2022-12-31",
            "2024-02-29T12:00:00Z, 2024-01-01, 2024-12-31"})
        void thePeriodSpansTheWholeCalendarYear(final String instant, final String expectedStart,
                final String expectedEnd) {
            arrangePublish();
            final ReportSubmissionService onDate =
                    serviceWithClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

            onDate.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo(expectedStart);
            assertThat(sendOptions.payload().endDate()).isEqualTo(expectedEnd);
        }

        @Test
        @DisplayName("both dates come from ONE clock reading, so the period cannot straddle a year end")
        void bothDatesComeFromOneClockReading() {
            arrangePublish();
            final AdvancingClock advancing = new AdvancingClock(Instant.parse("2022-12-31T12:00:00Z"),
                    Duration.ofDays(1L), ZoneOffset.UTC);
            final ReportSubmissionService onAdvancingClock = serviceWithClock(advancing);

            onAdvancingClock.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-01-01");
            assertThat(sendOptions.payload().endDate())
                    .as(":241 reads the clock once; a second read would have moved the year to 2023")
                    .isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("the report name is the unpadded literal of :240")
        void theReportNameIsTheUnpaddedLiteral() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_YEARLY);
        }

        @Test
        @DisplayName("the yearly arm ignores the six custom date components entirely")
        void theYearlyArmIgnoresTheCustomComponents() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, "Y", null, "99", "99", "9999", "99", "99", "9999", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-01-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-12-31");
        }
    }

    // =====================================================================================================
    // 7. Custom range, stage 1: the six emptiness guards - app/cbl/CORPT00C.cbl:258-303
    // =====================================================================================================

    /**
     * {@code EVALUATE TRUE} with six arms in a fixed order and a {@code WHEN OTHER} that does nothing. First
     * match wins, so a form empty in several components reports only the earliest.
     */
    @Nested
    @DisplayName("7. Custom stage 1 :258-303 - six emptiness guards in source order, first match wins")
    class CustomRangeSupplied {

        @Test
        @DisplayName("a wholly empty range reports the START MONTH, the first arm")
        void aWhollyEmptyRangeReportsTheStartMonth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_START_MONTH_EMPTY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_MONTH);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
        }

        @Test
        @DisplayName("the start day is reported second")
        void theStartDayIsReportedSecond() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", null, null, null, null, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_START_DAY_EMPTY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_DAY);
                    });
        }

        @Test
        @DisplayName("the start year is reported third")
        void theStartYearIsReportedThird() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", null, null, null, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_START_YEAR_EMPTY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_YEAR);
                    });
        }

        @Test
        @DisplayName("the end month is reported fourth")
        void theEndMonthIsReportedFourth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", null, null, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_END_MONTH_EMPTY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_END_MONTH);
                    });
        }

        @Test
        @DisplayName("the end day is reported fifth")
        void theEndDayIsReportedFifth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", null, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_END_DAY_EMPTY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_END_DAY);
                    });
        }

        @Test
        @DisplayName("the end year is reported sixth and last")
        void theEndYearIsReportedSixth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", "30", null)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_END_YEAR_EMPTY);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_END_YEAR);
                    });
        }

        @ParameterizedTest(name = "a component of [{0}] counts as empty")
        @DisplayName("SPACES, LOW-VALUES and an absent value are all empty; nothing else is")
        @ValueSource(strings = {"", " ", "  ", "\u0000", "\u0000\u0000"})
        void spacesLowValuesAndAbsentAreAllEmpty(final String component) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(component, "01", "2022", "06", "30", "2022")))
                    .withMessage(MSG_START_MONTH_EMPTY);
        }

        @Test
        @DisplayName("all six populated falls through the WHEN OTHER arm and proceeds")
        void allSixPopulatedProceeds() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_CUSTOM);
        }

        @Test
        @DisplayName("no emptiness failure reaches the queue")
        void noEmptinessFailureReachesTheQueue() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)));

            verifyNoInteractions(sqsTemplate);
        }
    }

    // =====================================================================================================
    // 8. Custom range, stage 2: NUMVAL-like normalisation - app/cbl/CORPT00C.cbl:305-327
    // =====================================================================================================

    /**
     * Each component is moved through a {@code PIC 9(n)} working item, which drops non-digits, discards
     * everything from a decimal point onward, reduces modulo the receiving width and zero-pads on the left.
     * Observed through the published payload rather than by reflection, so the assertion is on behaviour.
     */
    @Nested
    @DisplayName("8. Custom stage 2 :305-327 - the MOVE through PIC 9(n) that normalises each component")
    class CustomRangeNormalisation {

        @ParameterizedTest(name = "start {0}/{1}/{2} normalises into {3}")
        @DisplayName("a single digit is zero-padded, extra digits are truncated on the left")
        @CsvSource({
            "6,     1,    2022,  2022-06-01",
            "06,    01,   2022,  2022-06-01",
            "006,   001,  2022,  2022-06-01",
            "106,   101,  12022, 2022-06-01",
            "6.9,   1.9,  2022,  2022-06-01",
            "0x6,   0-1,  2/022, 2022-06-01"})
        void componentsAreNormalisedThroughTheReceivingWidth(final String startMonth, final String startDay,
                final String startYear, final String expectedStart) {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", startMonth, startDay, startYear, "06", "30", "2022", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo(expectedStart);
        }

        @Test
        @DisplayName("the decimal point stops the scan, so the fractional digits never contribute")
        void theDecimalPointStopsTheScan() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "1.9", "2.9", "2022.9", "06", "30", "2022", "Y"));

            assertThat(sendOptions.payload().startDate())
                    .as("the receiving item has no decimal places, so everything after the point is dropped")
                    .isEqualTo("2022-01-02");
        }

        @Test
        @DisplayName("the end components normalise on the same rules")
        void theEndComponentsNormaliseOnTheSameRules() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "06", "01", "2022", "6", "3", "22022", "Y"));

            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-06-03");
        }

        @Test
        @DisplayName("a component with no digits at all normalises to zeros and is caught by the validator")
        void aComponentWithNoDigitsNormalisesToZeros() {
            // "xx" is not blank, so stage 1 passes it; stage 2 turns it into "00"; stage 3 accepts "00"
            // because it is numeric and not greater than "12"; the assembled date is what finally fails.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("xx", "01", "2022", "06", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .as("month zero is a date failure, not a component failure - the component "
                                        + "check has no lower bound")
                                .isEqualTo(MSG_START_DATE_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_MONTH);
                    });
        }
    }

    // =====================================================================================================
    // 9. Custom range, stage 3: the string upper bounds - app/cbl/CORPT00C.cbl:329-379
    // =====================================================================================================

    /**
     * Six checks, each a numeric test plus - for the four month and day components only - a
     * <em>string</em> comparison against {@code '12'} or {@code '31'}. Neither year has any bound at all,
     * which is behaviour rather than an oversight.
     */
    @Nested
    @DisplayName("9. Custom stage 3 :329-379 - string bounds on months and days, NO bound on either year")
    class CustomRangeValues {

        @Test
        @DisplayName("a start month above twelve is rejected on the month field")
        void aStartMonthAboveTwelveIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("13", "01", "2022", "06", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_START_MONTH_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_MONTH);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
        }

        @Test
        @DisplayName("a start day above thirty-one is rejected on the day field")
        void aStartDayAboveThirtyOneIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "32", "2022", "06", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_START_DAY_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_DAY);
                    });
        }

        @Test
        @DisplayName("an end month above twelve is rejected on the end month field")
        void anEndMonthAboveTwelveIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "13", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_END_MONTH_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_END_MONTH);
                    });
        }

        @Test
        @DisplayName("an end day above thirty-one is rejected on the end day field")
        void anEndDayAboveThirtyOneIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", "32", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_END_DAY_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_END_DAY);
                    });
        }

        @ParameterizedTest(name = "month {0} is within bound")
        @DisplayName("twelve and thirty-one are ON the bound and therefore accepted, not rejected")
        @ValueSource(strings = {"01", "06", "12"})
        void theBoundsAreInclusive(final String month) {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", month, "01", "2022", "12", "31", "2022", "Y"));

            assertThat(sendOptions.payload().startDate()).endsWith("-" + month + "-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("the start month is checked before the start day, which is checked before the end month")
        void theSixChecksRunInSourceOrder() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("13", "32", "2022", "13", "32", "2022")))
                    .as("every one of the four is out of range, and only the first is reported")
                    .withMessage(MSG_START_MONTH_INVALID);
        }

        @Test
        @DisplayName("neither year has an upper bound, so a year of 0000 passes stage 3 and fails at the date")
        void neitherYearHasAnUpperBound() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "0000", "06", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .as(":347-354 tests numeric only - so this failure comes from the date "
                                        + "validator and carries the DATE literal, not the YEAR literal")
                                .isEqualTo(MSG_START_DATE_INVALID)
                                .isNotEqualTo(MSG_START_YEAR_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_START_MONTH);
                    });
        }

        @Test
        @DisplayName("a four-digit year far in the future is accepted, there being no bound to fail")
        void aFarFutureYearIsAccepted() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "01", "01", "9999", "12", "31", "9999", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("9999-01-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("9999-12-31");
        }

        @Test
        @DisplayName("the year-invalid literals exist for the arms the source retains but cannot reach")
        void theYearInvalidLiteralsExistForTheRetainedArms() throws ReflectiveOperationException {
            // :347-354 and :373-379 are unreachable after stage 2 normalisation, which always yields four
            // digits. They are retained one for one because the source evaluates them, and their literals
            // must still be the source's own - which is what these two assertions pin.
            assertThat(declaredConstant("MSG_START_YEAR_INVALID")).isEqualTo(MSG_START_YEAR_INVALID);
            assertThat(declaredConstant("MSG_END_YEAR_INVALID")).isEqualTo(MSG_END_YEAR_INVALID);
        }
    }

    // =====================================================================================================
    // 10. Custom range, stages 4 and 5: assembly and validation - app/cbl/CORPT00C.cbl:381-426
    // =====================================================================================================

    /**
     * Assembly to {@code YYYY-MM-DD} then two validator calls, start first. Severity {@code '0000'} is
     * accepted outright at {@code :396-397}; any other severity is accepted anyway when the message number
     * is {@code '2513'} at {@code :399}; everything else is rejected.
     */
    @Nested
    @DisplayName("10. Custom stages 4 and 5 :381-426 - assembly, then start-then-end validation")
    class AssembledDateValidation {

        @Test
        @DisplayName("a valid range is accepted with severity 0000")
        void aValidRangeIsAccepted() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-06-30");
        }

        @Test
        @DisplayName("a start date that does not exist is rejected with the START DATE literal")
        void aNonExistentStartDateIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("02", "30", "2022", "06", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_START_DATE_INVALID);
                        assertThat(failure.getFieldName())
                                .as(":404 MOVE -1 TO SDTMML - the cursor lands on the month component")
                                .isEqualTo(CURSOR_START_MONTH);
                    });
        }

        @Test
        @DisplayName("an end date that does not exist is rejected with the END DATE literal")
        void aNonExistentEndDateIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "02", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_END_DATE_INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_END_MONTH);
                    });
        }

        @Test
        @DisplayName("the START date is validated first, so only it is reported when both are impossible")
        void theStartDateIsValidatedFirst() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("02", "30", "2022", "02", "31", "2022")))
                    .as(":388-406 precedes :408-426, and only one message is ever produced")
                    .withMessage(MSG_START_DATE_INVALID);
        }

        @Test
        @DisplayName("MESSAGE NUMBER 2513 IS TOLERATED - a date before 15 October 1582 is accepted")
        void messageNumber2513IsTolerated() {
            arrangePublish();

            // The real validator reports severity 0003 and message number 2513 for any date before the
            // Lillian day zero. :399 tests the message number and falls through to acceptance, so this
            // range submits successfully - which is the whole point of the exemption.
            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "01", "01", "1000", "01", "31", "1000", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("1000-01-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("1000-01-31");
        }

        @Test
        @DisplayName("the tolerated number applies to the END date too, both calls sharing the exemption")
        void theToleratedNumberAppliesToTheEndDateToo() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "10", "15", "1582", "10", "14", "1582", "Y"));

            assertThat(sendOptions.payload().startDate())
                    .as("15 October 1582 is the first representable date and validates outright")
                    .isEqualTo("1582-10-15");
            assertThat(sendOptions.payload().endDate())
                    .as("14 October 1582 is the day before it and is tolerated under 2513")
                    .isEqualTo("1582-10-14");
        }

        @Test
        @DisplayName("the real validator genuinely reports 2513 for that range, so the exemption is exercised")
        void theRealValidatorGenuinelyReports2513() throws ReflectiveOperationException {
            final DateValidationService.DateValidationResult tolerated =
                    dateValidationService.validate("1000-01-01", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(tolerated.severityCode())
                    .as("a non-zero severity, so :396-397 does not accept it")
                    .isNotEqualTo(declaredConstant("SEVERITY_ACCEPTED"));
            assertThat(tolerated.messageNumber())
                    .as("but the message number :399 singles out, so the range is accepted anyway")
                    .isEqualTo(declaredConstant("SEVERITY_TOLERATED_MESSAGE_NUMBER"));
        }

        @Test
        @DisplayName("a rejected date is one whose message number is anything else")
        void aRejectedDateCarriesAnotherMessageNumber() throws ReflectiveOperationException {
            final DateValidationService.DateValidationResult rejected =
                    dateValidationService.validate("2022-02-30", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(rejected.severityCode()).isNotEqualTo(declaredConstant("SEVERITY_ACCEPTED"));
            assertThat(rejected.messageNumber())
                    .isNotEqualTo(declaredConstant("SEVERITY_TOLERATED_MESSAGE_NUMBER"));
        }

        @Test
        @DisplayName("the assembled date is year-dash-month-dash-day, exactly ten characters")
        void theAssembledDateIsTenCharacters() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().startDate())
                    .as(":381-383 the components are concatenated with dashes, 4 + 1 + 2 + 1 + 2")
                    .hasSize(10)
                    .isEqualTo("2022-06-01");
        }

        @Test
        @DisplayName("no date failure reaches the queue")
        void noDateFailureReachesTheQueue() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("02", "30", "2022", "06", "30", "2022")));

            verifyNoInteractions(sqsTemplate);
        }
    }

    // =====================================================================================================
    // 11. The confirmation handshake - app/cbl/CORPT00C.cbl:464-493
    // =====================================================================================================

    /**
     * Four states over a {@code PIC X(1)} gate. Blank re-prompts naming the report; {@code 'Y'} or
     * {@code 'y'} publishes; {@code 'N'} or {@code 'n'} clears the form and ends the turn with no message and
     * no cursor; anything else quotes the offending value back.
     */
    @Nested
    @DisplayName("11. The confirmation handshake :464-493 - four states over a one-character gate")
    class ConfirmationHandshake {

        @ParameterizedTest(name = "a gate of [{0}] re-prompts")
        @DisplayName("a blank gate re-prompts, naming the resolved report in the message")
        @ValueSource(strings = {"", " ", "\u0000"})
        void aBlankGateRePrompts(final String gate) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(gate)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .isEqualTo(MSG_CONFIRM_PREFIX + REPORT_NAME_MONTHLY + MSG_CONFIRM_SUFFIX);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_CONFIRM);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("an absent gate re-prompts on the same message")
        void anAbsentGateRePrompts() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(null)))
                    .withMessage(MSG_CONFIRM_PREFIX + REPORT_NAME_MONTHLY + MSG_CONFIRM_SUFFIX);
        }

        @Test
        @DisplayName("the re-prompt names the resolved report, so the custom arm reads Custom")
        void theRePromptNamesTheResolvedReport() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequestConfirmedWith(" ")))
                    .withMessage(MSG_CONFIRM_PREFIX + REPORT_NAME_CUSTOM + MSG_CONFIRM_SUFFIX);
        }

        @ParameterizedTest(name = "a gate of [{0}] confirms")
        @DisplayName("upper and lower case Y both confirm, compared as two literals rather than case-folded")
        @ValueSource(strings = {"Y", "y"})
        void bothCasesOfYesConfirm(final String gate) {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(gate));

            assertThat(sendOptions.payload()).isNotNull();
        }

        @ParameterizedTest(name = "a gate of [{0}] declines")
        @DisplayName("upper and lower case N both decline, clearing the form and ending the turn")
        @ValueSource(strings = {"N", "n"})
        void bothCasesOfNoDecline(final String gate) {
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(gate));

            assertThat(screen.errorFlagOn())
                    .as(":481 MOVE 'Y' TO WS-ERR-FLG")
                    .isTrue();
            assertThat(screen.successHighlight()).isFalse();
            assertThat(screen.cursorField())
                    .as(":480-483 sets no cursor at all, unlike every other failing arm")
                    .isNull();
            assertThat(screen.form().errorMessage())
                    .as(":646 INITIALIZE WS-MESSAGE - the cleared form carries a blank message area")
                    .isEmpty();
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("declining clears all nine input fields but leaves the header painted")
        void decliningClearsTheInputFieldsButPaintsTheHeader() {
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N"));

            assertThat(screen.form().monthlySelected()).isEmpty();
            assertThat(screen.form().yearlySelected()).isEmpty();
            assertThat(screen.form().customSelected()).isEmpty();
            assertThat(screen.form().startDateMonth()).isEmpty();
            assertThat(screen.form().startDateDay()).isEmpty();
            assertThat(screen.form().startDateYear()).isEmpty();
            assertThat(screen.form().endDateMonth()).isEmpty();
            assertThat(screen.form().endDateDay()).isEmpty();
            assertThat(screen.form().endDateYear()).isEmpty();
            assertThat(screen.form().confirmation()).isEmpty();
            assertThat(screen.form().transactionName())
                    .as("SEND-TRNRPT-SCREEN performs POPULATE-HEADER-INFO before the send")
                    .isEqualTo(TRANSACTION_ID);
        }

        @ParameterizedTest(name = "a gate of [{0}] is unrecognised")
        @DisplayName("any other single character is quoted back as an invalid confirmation value")
        @ValueSource(strings = {"X", "1", "-", "Z"})
        void anyOtherCharacterIsQuotedBack(final String gate) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(gate)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .isEqualTo("\"" + gate + MSG_INVALID_CONFIRM_SUFFIX);
                        assertThat(failure.getFieldName()).isEqualTo(CURSOR_CONFIRM);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("the gate is truncated to the PIC X(1) width, so YES confirms - a preserved quirk")
        void theGateIsTruncatedToOneCharacter() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("YES"));

            assertThat(sendOptions.payload())
                    .as("CONFIRMI is PIC X(1), so the source only ever compared one character")
                    .isNotNull();
        }

        @Test
        @DisplayName("the quoted value in the invalid message is the TRUNCATED gate, not the whole field")
        void theQuotedValueIsTheTruncatedGate() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            monthlyRequest("XYZ")))
                    .withMessage("\"X" + MSG_INVALID_CONFIRM_SUFFIX);
        }

        @Test
        @DisplayName("a longer value beginning with N declines, on the same truncation")
        void aLongerValueBeginningWithNoDeclines() {
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("NO"));

            assertThat(screen.errorFlagOn()).isTrue();
            verifyNoInteractions(sqsTemplate);
        }
    }

    // =====================================================================================================
    // 12. Queue publication - app/cbl/CORPT00C.cbl:496-523
    // =====================================================================================================

    /**
     * Seventeen eighty-byte job cards, written one at a time by a loop bounded at a thousand, collapse into
     * one typed message published once.
     */
    @Nested
    @DisplayName("12. Queue publication :496-523 - seventeen job cards collapse into ONE FIFO message")
    class QueuePublication {

        @Test
        @DisplayName("exactly one message is published per confirmed submission")
        void exactlyOneMessageIsPublished() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            verify(sqsTemplate).sendAsync(any());
        }

        @Test
        @DisplayName("the configured queue, payload and message group all cross the boundary")
        void theConfiguredValuesAllCrossTheBoundary() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.queue()).isEqualTo(QUEUE_NAME);
            assertThat(sendOptions.messageGroupId())
                    .as("a FIFO queue requires a group, and the value is configuration rather than a literal")
                    .isEqualTo(MESSAGE_GROUP_ID);
            assertThat(sendOptions.payload()).isNotNull();
        }

        @Test
        @DisplayName("the four send options are set and nothing else is")
        void onlyTheFourSendOptionsAreSet() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            // Four, not three. The fourth is the trace-context propagation header set the publish carries so
            // that a message leaving this process can be joined to the log records and the span it came from,
            // which is the outbound half of what CorrelationIdFilter does at the request boundary. An earlier
            // revision of this assertion predated that header and named only the first three.
            assertThat(sendOptions.invoked())
                    .containsExactly("queue", "payload", "messageGroupId", "headers");
            assertThat(sendOptions.messageDeduplicationId())
                    .as("no deduplication identifier is set: two identical submissions are two distinct "
                            + "job requests, exactly as two WRITEQ TD calls were")
                    .isNull();
        }

        @Test
        @DisplayName("the payload carries the report name and both parameter dates and nothing else")
        void thePayloadCarriesTheThreeValues() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            final JobSubmissionMessage published = sendOptions.payload();
            assertThat(published.reportName()).isEqualTo(REPORT_NAME_MONTHLY);
            assertThat(published.startDate()).isEqualTo("2022-06-01");
            assertThat(published.endDate()).isEqualTo("2022-06-30");
            assertThat(JobSubmissionMessage.class.getRecordComponents())
                    .as("each date was injected twice across the deck and needs one field here")
                    .hasSize(3);
        }

        @Test
        @DisplayName("the message refuses to be built without a report name")
        void theMessageRefusesAnAbsentReportName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobSubmissionMessage(null, "2022-06-01", "2022-06-30"))
                    .withMessage("reportName must not be null");
        }

        @Test
        @DisplayName("the message refuses to be built without a start date")
        void theMessageRefusesAnAbsentStartDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobSubmissionMessage(REPORT_NAME_MONTHLY, null, "2022-06-30"))
                    .withMessage("startDate must not be null");
        }

        @Test
        @DisplayName("the message refuses to be built without an end date")
        void theMessageRefusesAnAbsentEndDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobSubmissionMessage(REPORT_NAME_MONTHLY, "2022-06-01", null))
                    .withMessage("endDate must not be null");
        }

        @Test
        @DisplayName("two submissions publish two messages, so the operation is not idempotent by accident")
        void twoSubmissionsPublishTwoMessages() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));
            service.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            verify(sqsTemplate, org.mockito.Mockito.times(2)).sendAsync(any());
            assertThat(sendOptions.payload().reportName())
                    .as("the recorder holds the most recent payload, which is the yearly one")
                    .isEqualTo(REPORT_NAME_YEARLY);
        }
    }

    // =====================================================================================================
    // 13. Queue failure - app/cbl/CORPT00C.cbl:528-534
    // =====================================================================================================

    /**
     * The {@code WHEN OTHER} arm of the write. The literal is byte exact, the cause is preserved, and no
     * file status is fabricated: the program declares no {@code FILE-CONTROL}, no {@code SELECT} and no
     * {@code FD}.
     */
    @Nested
    @DisplayName("13. Queue failure :528-534 - byte-exact literal, cause preserved, no fabricated status")
    class QueueFailure {

        @Test
        @DisplayName("a publish failure becomes an I/O failure carrying the source literal")
        void aPublishFailureBecomesAnIoFailure() {
            final RuntimeException unavailable = new IllegalStateException("queue unavailable");
            doThrow(unavailable).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_UNABLE_TO_WRITE_TDQ);
                        assertThat(failure.getCause()).isSameAs(unavailable);
                    });
        }

        @Test
        @DisplayName("no file status is fabricated, because the program declares no file at all")
        void noFileStatusIsFabricated() {
            doThrow(new IllegalStateException("queue unavailable")).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getExpandedStatus())
                                .as("the two-argument form is used deliberately; the four-argument form "
                                        + "would render a FILE STATUS this program does not have")
                                .isEqualTo(NO_FILE_STATUS);
                        assertThat(failure.getLogicalFileName()).isNull();
                        assertThat(failure.getOperation()).isNull();
                    });
        }

        @Test
        @DisplayName("a messaging failure carrying an endpoint is reported with that endpoint as the reason")
        void aMessagingFailureCarriesItsEndpoint() {
            final MessagingOperationFailedException withEndpoint =
                    new MessagingOperationFailedException("send failed", QUEUE_NAME);
            doThrow(withEndpoint).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MSG_UNABLE_TO_WRITE_TDQ);
                        assertThat(failure.getCause()).isSameAs(withEndpoint);
                    });
            assertThat(withEndpoint.getEndpoint())
                    .as("the endpoint is the closest analogue of RESP2 the publisher offers")
                    .isEqualTo(QUEUE_NAME);
        }

        @Test
        @DisplayName("the failure literal is byte exact, parenthesised queue name included")
        void theFailureLiteralIsByteExact() {
            assertThat(MSG_UNABLE_TO_WRITE_TDQ).isEqualTo("Unable to Write TDQ (JOBS)...");
        }

        @Test
        @DisplayName("a failed publish produces no screen, so no success notice can be mistaken for one")
        void aFailedPublishProducesNoScreen() {
            doThrow(new IllegalStateException("queue unavailable")).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequestConfirmedWith("Y")));
        }
    }

    // =====================================================================================================
    // 14. The success tail - app/cbl/CORPT00C.cbl:445-454
    // =====================================================================================================

    /**
     * The clear precedes the message, and the message is composed from the report name with
     * {@code DELIMITED BY SPACE} - so the unpadded literal is the correct operand. The attribute is
     * {@code DFHGREEN}, the one place this program distinguishes a success notice from an error.
     */
    @Nested
    @DisplayName("14. The success tail :445-454 - clear, then compose, with the green attribute")
    class SuccessTail {

        @Test
        @DisplayName("the notice names the report and reads exactly as :449-452 assembles it")
        void theNoticeNamesTheReport() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.form().errorMessage())
                    .isEqualTo(REPORT_NAME_MONTHLY + MSG_SUBMITTED_SUFFIX)
                    .isEqualTo("Monthly report submitted for printing ...");
        }

        @ParameterizedTest(name = "the {0} report reports its own name")
        @DisplayName("each of the three report names appears in its own notice")
        @CsvSource({"Monthly", "Yearly", "Custom"})
        void eachReportNameAppearsInItsOwnNotice(final String reportName) {
            arrangePublish();
            final ReportRequest request = switch (reportName) {
                case "Monthly" -> monthlyRequest("Y");
                case "Yearly" -> yearlyRequest("Y");
                default -> customRequestConfirmedWith("Y");
            };

            final ReportSubmissionScreen screen = service.submitScreen(AttentionIdentifier.ENTER, request);

            assertThat(screen.form().errorMessage()).isEqualTo(reportName + MSG_SUBMITTED_SUFFIX);
        }

        @Test
        @DisplayName("the form is cleared before the notice is composed, so the notice survives the clear")
        void theFormIsClearedBeforeTheNoticeIsComposed() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(screen.form().customSelected()).isEmpty();
            assertThat(screen.form().startDateMonth()).isEmpty();
            assertThat(screen.form().endDateYear()).isEmpty();
            assertThat(screen.form().confirmation()).isEmpty();
            assertThat(screen.form().errorMessage())
                    .as(":447 clears first and :449-452 composes second, from WS-REPORT-NAME which the "
                            + "clear does not touch")
                    .isEqualTo(REPORT_NAME_CUSTOM + MSG_SUBMITTED_SUFFIX);
        }

        @Test
        @DisplayName("the success screen carries the green attribute and no error flag")
        void theSuccessScreenCarriesTheGreenAttribute() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.successHighlight())
                    .as(":448 MOVE DFHGREEN TO ERRMSGC")
                    .isTrue();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.cursorField())
                    .as(":453 MOVE -1 TO MONTHLYL")
                    .isEqualTo(CURSOR_MONTHLY);
            assertThat(screen.navigationTarget())
                    .as("the screen is repainted, not left")
                    .isNull();
        }

        @Test
        @DisplayName("the declined screen and the success screen are distinguishable by their two flags")
        void theDeclinedAndSuccessScreensAreDistinguishable() {
            arrangePublish();

            final ReportSubmissionScreen success =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));
            final ReportSubmissionScreen declined =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N"));

            assertThat(success.successHighlight()).isTrue();
            assertThat(success.errorFlagOn()).isFalse();
            assertThat(declined.successHighlight()).isFalse();
            assertThat(declined.errorFlagOn()).isTrue();
        }

        @Test
        @DisplayName("the map area is required on a screen outcome")
        void theMapAreaIsRequiredOnAScreenOutcome() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionScreen(null, false, false, null, null))
                    .withMessage("form must not be null");
        }

        @Test
        @DisplayName("both cursor and navigation target are legitimately absent, so neither is required")
        void bothCursorAndNavigationTargetMayBeAbsent() {
            final ReportSubmissionScreen bare = new ReportSubmissionScreen(monthlyRequest("Y"), true, false,
                    null, null);

            assertThat(bare.cursorField()).isNull();
            assertThat(bare.navigationTarget()).isNull();
        }
    }

    // =====================================================================================================
    // 15. POPULATE-HEADER-INFO and INITIALIZE-ALL-FIELDS - app/cbl/CORPT00C.cbl:609-647
    // =====================================================================================================

    /**
     * Six header values computed rather than echoed, over a clock read once per paragraph, and a clear that
     * touches ten fields and leaves the six header fields and the message field alone.
     */
    @Nested
    @DisplayName("15. Header and clear :609-647 - six computed values, ten cleared fields")
    class ScreenPresentation {

        @Test
        @DisplayName("the submitted header components are overwritten, never echoed back")
        void theSubmittedHeaderComponentsAreOverwritten() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.form().transactionName()).isEqualTo(TRANSACTION_ID).isNotEqualTo("ZZZZ");
            assertThat(screen.form().programName()).isEqualTo(PROGRAM_NAME).isNotEqualTo("ZZZZZZZZ");
            assertThat(screen.form().title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(screen.form().title02()).isEqualTo(SCREEN_TITLE_02);
            assertThat(screen.form().currentDate()).isEqualTo(EXPECTED_HEADER_DATE).isNotEqualTo("01/01/00");
            assertThat(screen.form().currentTime()).isEqualTo(EXPECTED_HEADER_TIME).isNotEqualTo("00:00:00");
        }

        @Test
        @DisplayName("both titles are byte exact at their declared PIC X(40) width, padding included")
        void bothTitlesAreByteExactAtFortyCharacters() {
            assertThat(SCREEN_TITLE_01).hasSize(40).contains("AWS Mainframe Modernization");
            assertThat(SCREEN_TITLE_02).hasSize(40).contains("CardDemo");
        }

        @Test
        @DisplayName("the header date and time come from ONE clock reading per paragraph")
        void theHeaderComesFromOneClockReadingPerParagraph() {
            // Only the header is rendered on this path - the invalid-key arm raises before any period is
            // resolved - so exactly one read must occur.
            final AdvancingClock advancing = new AdvancingClock(Instant.parse("2022-06-10T23:59:59Z"),
                    Duration.ofHours(1L), ZoneOffset.UTC);
            final ReportSubmissionService onAdvancingClock = serviceWithClock(advancing);

            final ReportSubmissionScreen screen = onAdvancingClock.openReportScreen();

            assertThat(advancing.reads())
                    .as(":611 MOVE FUNCTION CURRENT-DATE happens once per paragraph")
                    .isEqualTo(1);
            assertThat(screen.form().currentDate()).isEqualTo("06/10/22");
            assertThat(screen.form().currentTime())
                    .as("a second read would have moved the time past midnight and the date to the eleventh")
                    .isEqualTo("23:59:59");
        }

        @Test
        @DisplayName("the clear leaves the six header fields untouched, they being absent from :637-645")
        void theClearLeavesTheHeaderFieldsUntouched() {
            final ReportSubmissionScreen declined =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N"));

            assertThat(declined.form().transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(declined.form().currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(declined.form().title01()).isEqualTo(SCREEN_TITLE_01);
        }

        @Test
        @DisplayName("the map area has seventeen components, matching app/cpy-bms/CORPT00.CPY")
        void theMapAreaHasSeventeenComponents() {
            assertThat(ReportRequest.class.getRecordComponents())
                    .as("CORPT00.CPY declares seventeen input fields, and the map area carries all of them")
                    .hasSize(17);
        }

        @Test
        @DisplayName("no submitted value leaks into a screen outcome except through its own field")
        void noSubmittedValueLeaksIntoAScreenOutcome() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.form().errorMessage())
                    .as("the submitted message field is replaced by the notice, never concatenated with it")
                    .doesNotContain("submitted-message");
        }
    }

    // =====================================================================================================
    // 16. Deck geometry and thresholds - app/cbl/CORPT00C.cbl:80-127 and :496-508
    // =====================================================================================================

    /**
     * The constants the collapsed deck must still record: seventeen cards of eighty bytes, a loop bound of a
     * thousand matching {@code OCCURS 1000 TIMES}, the {@code /*EOF} terminator, and the two thresholds the
     * date validation turns on.
     */
    @Nested
    @DisplayName("16. Deck geometry :80-127 and :496-508 - 17 cards, 80 bytes, bound 1000, terminator")
    class DeckGeometry {

        @Test
        @DisplayName("seventeen job cards are recorded, being the deck the message replaces")
        void seventeenJobCardsAreRecorded() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_CARD_COUNT")).isEqualTo(17);
        }

        @Test
        @DisplayName("each card was eighty bytes, matching the TDQUEUE RECORDSIZE(80) of the CSD")
        void eachCardWasEightyBytes() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_CARD_LENGTH")).isEqualTo(80);
        }

        @Test
        @DisplayName("the loop bound is a thousand, matching OCCURS 1000 TIMES at :127")
        void theLoopBoundIsAThousand() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_LINE_LIMIT")).isEqualTo(1000);
        }

        @Test
        @DisplayName("the terminator card is recorded byte exact")
        void theTerminatorCardIsRecordedByteExact() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_TERMINATOR_CARD")).isEqualTo("/*EOF");
        }

        @Test
        @DisplayName("the accepted severity is four zeros and the tolerated message number is 2513")
        void theTwoDateThresholdsAreRecorded() throws ReflectiveOperationException {
            assertThat(declaredConstant("SEVERITY_ACCEPTED")).isEqualTo("0000");
            assertThat(declaredConstant("SEVERITY_TOLERATED_MESSAGE_NUMBER")).isEqualTo("2513");
        }

        @Test
        @DisplayName("the two range bounds are the source's string literals, not integers")
        void theTwoRangeBoundsAreStringLiterals() throws ReflectiveOperationException {
            assertThat(declaredConstant("MONTH_UPPER_BOUND"))
                    .as(":330 compares SDTMMI > '12' as text, which is why the normalisation must zero-pad")
                    .isEqualTo("12");
            assertThat(declaredConstant("DAY_UPPER_BOUND")).isEqualTo("31");
        }

        @Test
        @DisplayName("the confirmation gate is one character wide, matching CONFIRMI PIC X(1)")
        void theConfirmationGateIsOneCharacterWide() throws ReflectiveOperationException {
            assertThat(declaredConstant("CONFIRMATION_WIDTH")).isEqualTo(1);
        }

        @Test
        @DisplayName("the component widths are two for months and days and four for years")
        void theComponentWidthsAreTwoAndFour() throws ReflectiveOperationException {
            assertThat(declaredConstant("MONTH_DAY_WIDTH")).isEqualTo(2);
            assertThat(declaredConstant("YEAR_WIDTH")).isEqualTo(4);
        }

        @Test
        @DisplayName("every static field is final, so no turn can mutate class-level state")
        void everyStaticFieldIsFinal() {
            final List<Field> mutableStatics = Arrays.stream(ReportSubmissionService.class
                            .getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList();
            assertThat(mutableStatics).isEmpty();
        }

        @Test
        @DisplayName("two turns on one instance are independent, the bean holding nothing between them")
        void twoTurnsOnOneInstanceAreIndependent() {
            arrangePublish();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)));

            final ReportSubmissionScreen second =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(second.form().errorMessage())
                    .as("the second turn must behave as though the first never happened")
                    .isEqualTo(REPORT_NAME_MONTHLY + MSG_SUBMITTED_SUFFIX);
        }

        @Test
        @DisplayName("the declined arm returns an outcome rather than raising, so the two are not conflated")
        void theDeclinedArmReturnsRatherThanRaising() {
            final Optional<ReportSubmissionScreen> declined = Optional.of(
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N")));

            assertThat(declined)
                    .as(":480-483 ends the turn with a send; the other three arms raise or fall through")
                    .isPresent();
        }
    }

    /**
     * A recording {@link SqsSendOptions}: fluent, so the production chain works, and total, so nothing the
     * lambda sets can go unobserved.
     */
    private static final class RecordingSendOptions implements SqsSendOptions<JobSubmissionMessage> {

        /** Every method name invoked, in order, so an unexpected option is visible. */
        private final List<String> invoked = new ArrayList<>();

        /** The queue the lambda selected. */
        private String queue;

        /** The payload the lambda set. */
        private JobSubmissionMessage payload;

        /** The FIFO message group the lambda set. */
        private String messageGroupId;

        /** The deduplication identifier, which the production lambda deliberately never sets. */
        private String messageDeduplicationId;

        @Override
        public SqsSendOptions<JobSubmissionMessage> queue(final String queueName) {
            this.invoked.add("queue");
            this.queue = queueName;
            return this;
        }

        @Override
        public SqsSendOptions<JobSubmissionMessage> payload(final JobSubmissionMessage messagePayload) {
            this.invoked.add("payload");
            this.payload = messagePayload;
            return this;
        }

        @Override
        public SqsSendOptions<JobSubmissionMessage> header(final String name, final Object value) {
            this.invoked.add("header");
            return this;
        }

        @Override
        public SqsSendOptions<JobSubmissionMessage> headers(final Map<String, Object> headersToAdd) {
            this.invoked.add("headers");
            return this;
        }

        @Override
        public SqsSendOptions<JobSubmissionMessage> delaySeconds(final Integer seconds) {
            this.invoked.add("delaySeconds");
            return this;
        }

        @Override
        public SqsSendOptions<JobSubmissionMessage> messageGroupId(final String groupId) {
            this.invoked.add("messageGroupId");
            this.messageGroupId = groupId;
            return this;
        }

        @Override
        public SqsSendOptions<JobSubmissionMessage> messageDeduplicationId(final String deduplicationId) {
            this.invoked.add("messageDeduplicationId");
            this.messageDeduplicationId = deduplicationId;
            return this;
        }

        /**
         * Returns the queue the lambda selected.
         *
         * @return the queue name, or {@code null} when none was set
         */
        String queue() {
            return this.queue;
        }

        /**
         * Returns the payload the lambda set.
         *
         * @return the payload, or {@code null} when none was set
         */
        JobSubmissionMessage payload() {
            return this.payload;
        }

        /**
         * Returns the FIFO message group the lambda set.
         *
         * @return the group, or {@code null} when none was set
         */
        String messageGroupId() {
            return this.messageGroupId;
        }

        /**
         * Returns the deduplication identifier the lambda set.
         *
         * @return the identifier, which is expected to stay {@code null}
         */
        String messageDeduplicationId() {
            return this.messageDeduplicationId;
        }

        /**
         * Returns the option methods invoked, in order.
         *
         * @return the invocation names
         */
        List<String> invoked() {
            return List.copyOf(this.invoked);
        }
    }

    /**
     * A clock that advances on every read and counts its reads, used to prove that each period is derived
     * from a <em>single</em> reading. {@code :215} and {@code :241} each perform one
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}, so a period cannot straddle a boundary; a
     * second read would let it.
     */
    private static final class AdvancingClock extends Clock {

        /** The zone this instance reports. */
        private final ZoneId zone;

        /** How far each read advances the next. */
        private final Duration step;

        /** The instant the next read will return. */
        private Instant next;

        /** How many reads have occurred. */
        private int reads;

        /**
         * Creates an advancing clock.
         *
         * @param start the instant the first read returns; must not be {@code null}
         * @param step  how far each read advances the next; must not be {@code null}
         * @param zone  the zone to report; must not be {@code null}
         */
        AdvancingClock(final Instant start, final Duration step, final ZoneId zone) {
            this.next = start;
            this.step = step;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return this.zone;
        }

        @Override
        public Clock withZone(final ZoneId target) {
            return new AdvancingClock(this.next, this.step, target);
        }

        @Override
        public Instant instant() {
            this.reads++;
            final Instant current = this.next;
            this.next = this.next.plus(this.step);
            return current;
        }

        /**
         * Returns how many times the clock was read.
         *
         * @return the read count
         */
        int reads() {
            return this.reads;
        }
    }
}
