/*
 * ******************************************************************
 * Program     : ReportSubmissionServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that ReportSubmissionService reproduces the
 *               frozen CORPT00C exactly - ten paragraphs mapped one to
 *               one, the monthly period resolved as a FULL CALENDAR
 *               MONTH with the twelve-month carry, the yearly period as
 *               January first through December thirty-first, the custom
 *               period through its six ordered emptiness guards, its
 *               NUMVAL-C normalisation, its string upper bounds with no
 *               lower bound and no year bound at all, and its
 *               start-then-end whole-date validation with severity 0000
 *               accepted outright and message number 2513 tolerated;
 *               the four-state confirmation handshake; the seventeen
 *               eighty-byte job cards collapsed into ONE FIFO message;
 *               and the byte-exact queue-failure literal
 * Source      : app/cbl/CORPT00C.cbl (649 lines, 10 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/CORPT00.CPY (17 input fields) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (TRANSACTION(CR00), TDQUEUE(JOBS)) @ 7756d89
 * Source      : app/cbl/CSUTLDTC.cbl (the validator behind the two CALLs) @ 7756d89
 * Source      : app/proc/TRANREPT.prc (the procedure the deck submits) @ 7756d89
 * Source      : app/jcl/TRANREPT.jcl (the job the deck names) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
 * Source      : app/cpy/COTTL01Y.cpy (CCDA-TITLE01, CCDA-TITLE02) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:1-21 (this banner's canonical form) @ 7756d89
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.report.ReportSubmissionService.AttentionIdentifier;
import com.cardemo.service.report.ReportSubmissionService.JobSubmissionMessage;
import com.cardemo.service.report.ReportSubmissionService.ReportSubmissionScreen;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.unit.model.FixedClockProvider;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.MessagingOperationFailedException;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Tracer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.support.GenericMessage;

/**
 * Unit tests for {@link ReportSubmissionService}, the Java replacement for {@code app/cbl/CORPT00C.cbl} -
 * 649 lines and 10 paragraphs, the CICS program behind transaction {@code CR00}, which resolves a reporting
 * period and submits the transaction-report job.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It proves parity against the frozen corpus rather than against an idea of what the corpus ought to do.
 * Every assertion cites the paragraph or line it proves, and every locator below was verified by direct
 * inspection at commit {@code 7756d89}: {@code app/cbl/CORPT00C.cbl} at
 * {@code :58} ({@code WS-REPORT-NAME PIC X(10)}),
 * {@code :60-72} (the two dash-separated ten-character date groups and {@code WS-DATE-FORMAT}),
 * {@code :82} ({@code JOB-DATA-1}),
 * {@code :106}, {@code :111}, {@code :118} and {@code :120} (the four parameter slots),
 * {@code :123} and {@code :125} (the {@code /*} card and the {@code /*EOF} terminator),
 * {@code :129-136} (the validator parameter block, with {@code :137} blank - so a
 * {@code :129-137} citation drifts by one),
 * {@code :163} ({@code MAIN-PARA}),
 * {@code :208} and {@code :210} ({@code PROCESS-ENTER-KEY} and its trace {@code DISPLAY}),
 * {@code :212-238} (monthly), {@code :239-255} (yearly),
 * {@code :256-303} (the emptiness guards, with literals at {@code :261}, {@code :268}, {@code :275},
 * {@code :282}, {@code :289} and {@code :296}),
 * {@code :329-379} (the six range guards, with literals at {@code :331}, {@code :340}, {@code :348},
 * {@code :357}, {@code :366} and {@code :374}),
 * {@code :388-406} and {@code :392} and {@code :400} (start-date validation),
 * {@code :408-426} and {@code :412} and {@code :420} (end-date validation),
 * {@code :429-433} (the parameter moves, then the report name),
 * {@code :437-440} (no selector at all),
 * {@code :462} and {@code :464-494} ({@code SUBMIT-JOB-TO-INTRDR} and the confirmation handshake),
 * {@code :496-510} (the card loop), {@code :515} and {@code :517-537}
 * ({@code WIRTE-JOBSUB-TDQ}, misspelled in the source and cited as the source spells it),
 * {@code :540} ({@code RETURN-TO-PREV-SCREEN}), {@code :556} ({@code SEND-TRNRPT-SCREEN}),
 * {@code :585} ({@code RETURN-TO-CICS}), {@code :596} ({@code RECEIVE-TRNRPT-SCREEN}),
 * {@code :609} ({@code POPULATE-HEADER-INFO}) and {@code :633} ({@code INITIALIZE-ALL-FIELDS}).
 *
 * <h2>2. How to build and test</h2>
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} - runs this class under {@code maven-surefire-plugin:3.5.4}. Residence
 *       in the {@code unit} tree is load-bearing: a class outside it matches neither Surefire's include set
 *       nor Failsafe's and would silently never run, with both plugins reporting success.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=ReportSubmissionServiceTest test} - runs this class alone.</li>
 *   <li>{@code ./mvnw -B -ntp test-compile} - {@code maven-compiler-plugin:3.14.1} at
 *       {@code release 25} with {@code showWarnings}, {@code failOnWarning}, {@code -Xlint:all} and
 *       {@code -Werror}, all of which reach TEST compilation. One unused import is fatal.</li>
 *   <li>{@code ./mvnw -B -ntp verify} - adds the JaCoCo 0.8.12 bundle gate at 80 percent of lines, with no
 *       exclusions, and the doclint gate.</li>
 * </ul>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>An injected fixed clock, never an ambient one.</strong> Every clock in this class comes from
 *       {@link FixedClockProvider}, the sibling helper in {@code com.cardemo.unit.model}. The monthly and
 *       yearly periods are computed from {@code FUNCTION CURRENT-DATE} at {@code :215} and {@code :241}, so
 *       a wall clock would make the December-carry test pass or fail depending on the month it ran in.
 *       Every ambient-clock reading - {@code LocalDate}, {@code Instant}, {@code System} milliseconds,
 *       {@code Calendar}, the default zone and the default locale - is absent from this file.</li>
 *   <li><strong>A stubbed date validator.</strong> {@link DateValidationService} is a Mockito mock, and the
 *       assertions here are about the <em>interaction</em> and the caller's reaction to a severity code and
 *       a message number. The validator's own data contract - the severity taxonomy and the message-number
 *       catalogue - is owned by {@code com.cardemo.unit.validation} and by
 *       {@code DateValidationServiceTest}, and is deliberately not duplicated here.</li>
 *   <li><strong>Mockito strict stubs.</strong> {@link Strictness#STRICT_STUBS}: an unused stub fails the
 *       test, so each stub is arranged only in the tests that consume it.</li>
 *   <li><strong>A hand-written send-options recorder.</strong> {@link SqsSendOptions} is a fluent interface
 *       the production code configures inside a lambda, so a recording implementation captures exactly what
 *       the lambda sets - and, just as importantly, what it does not.</li>
 *   <li><strong>A hand-written tracer provider.</strong> Tracing is optional, so the provider yields no
 *       tracer. A plain implementation is used rather than a lenient mock, so no stub in this class needs
 *       leniency.</li>
 *   <li><strong>Synthetic configuration.</strong> The queue name, logical name, message group and topic are
 *       transparently synthetic values. No endpoint, host, port, URL, ARN, account identifier or credential
 *       appears anywhere in this file, and no code path here can reach a real service. The FIFO queue the
 *       deployment actually uses is named by configuration in {@code com.cardemo.config.AwsConfig} and is
 *       never a literal here. The real round trip belongs to {@code com.cardemo.integration.aws}.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <p>Findings are classified Blocker, High, Medium or Low, each with its remediation.
 *
 * <ul>
 *   <li><strong>Blocker - the monthly period becomes month-to-date.</strong> The frozen source builds the
 *       end date at {@code :223} by moving 1 to the day, at {@code :224-228} by adding one to the month and
 *       carrying the year, and at {@code :229-230} by taking
 *       {@code DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1)} - the first of the next month less one day, which
 *       is the last day of <em>this</em> one - and then reads all three subfields back out at
 *       {@code :232-234}. Every monthly report's range would otherwise be short. Remedy: first of next
 *       month, minus one day.</li>
 *   <li><strong>Blocker - a wall clock replaces the injected one.</strong> The verdict then changes with the
 *       calendar. Remedy: {@link FixedClockProvider}.</li>
 *   <li><strong>High - a non-zero severity whose message number is 2513 starts being rejected.</strong>
 *       {@code :399} and {@code :419} accept it. Remedy: restore the message-number test.</li>
 *   <li><strong>High - the December carry is dropped.</strong> {@code :225-228} is the only place the year
 *       advances. Remedy: restore the {@code &gt; 12} test.</li>
 *   <li><strong>High - the loop breaks before writing the terminating card.</strong> {@code :504} sets the
 *       flag and {@code :507} still writes on that same iteration, so seventeen cards are written, not
 *       sixteen. Remedy: write, then test.</li>
 *   <li><strong>High - an endpoint is named, or a deck image is placed in the message body.</strong> The
 *       legacy path submitted an executable job deck to an internal reader; the target publishes a typed
 *       three-field message. Remedy: keep the body typed and the endpoint in configuration.</li>
 *   <li><strong>High - a lower bound or a numeric conversion is added to the month and day guards.</strong>
 *       {@code :330} and {@code :339} compare character strings against {@code '12'} and {@code '31'} and
 *       have no lower bound at all. Remedy: compare as strings, upper bound only.</li>
 *   <li><strong>Medium - the six range guards are collapsed into an {@code EVALUATE}.</strong> They are six
 *       independent {@code IF} statements at {@code :329-379}, but each ends in
 *       {@code PERFORM SEND-TRNRPT-SCREEN}, and that paragraph ends {@code GO TO RETURN-TO-CICS} at
 *       {@code :580} - a terminal {@code EXEC CICS RETURN}. The first guard to fire therefore ends the turn
 *       and no later guard can run. A secondary description claims the last guard wins; it cannot. Remedy:
 *       fail on the first, in source order, and never accumulate.</li>
 *   <li><strong>Medium - a range check is added to either year.</strong> {@code :347} and {@code :373} test
 *       numeric only. Remedy: let {@code 0000} and {@code 9999} through to the date validator.</li>
 *   <li><strong>Medium - the deck is counted as eighteen cards.</strong> It is seventeen:
 *       {@code :83-125} declares ten whole-card fillers, two parameter cards, a {@code /*} card, the
 *       {@code DATEPARM} card, the second parameter card, a second {@code /*} card and the terminator.
 *       Remedy: seventeen.</li>
 *   <li><strong>Medium - the confirmation comparison is made case-insensitive.</strong> {@code :478} is the
 *       combined relation {@code = 'Y' OR 'y'}, not a case function. Remedy: compare two literals; no
 *       {@code toUpperCase}, and where a case operation is unavoidable elsewhere, {@code Locale.ROOT}.</li>
 *   <li><strong>Medium - eighteen messages are published instead of one.</strong> The deck collapses.
 *       Remedy: publish once.</li>
 *   <li><strong>Low - the cursor goes to the monthly selector on a queue failure whatever the report
 *       type.</strong> {@code :533} moves -1 to {@code MONTHLYL} unconditionally. A copy-paste defect,
 *       preserved. Remedy: none; do not correct it.</li>
 *   <li><strong>Low - the {@code WIRTE-JOBSUB-TDQ} misspelling is corrected.</strong> {@code :515} spells it
 *       that way. Remedy: keep the misspelling in the method name and in every citation.</li>
 *   <li><strong>Low - the empty quoted confirmation segment is expected to be reachable.</strong> A
 *       secondary description says a value beginning with a space yields {@code "" is not a valid value to
 *       confirm...}. It cannot: {@code CONFIRMI} is {@code PIC X(1)}, so a value whose first character is a
 *       space <em>is</em> all spaces in that field and the blank guard at {@code :464} fires first. Remedy:
 *       expect the blank re-prompt.</li>
 *   <li><strong>Not available</strong> - the semantic meaning of message number {@code 2513} in the language
 *       environment's date service. The source tolerates it at {@code :399} and {@code :419} without
 *       explaining it, and {@code app/cbl/CSUTLDTC.cbl} passes the code through without interpreting it.
 *       What would be needed: the vendor's feedback-code catalogue. The sibling
 *       {@code com.cardemo.unit.validation} package owns that data contract; this class treats the number
 *       as an opaque token, which is exactly how the caller treats it.</li>
 * </ul>
 *
 * @see ReportSubmissionService
 * @see ReportRequest
 * @see DateValidationService
 * @see FixedClockProvider
 */
@DisplayName("ReportSubmissionService: app/cbl/CORPT00C.cbl - resolve a period and submit the job (CR00)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ReportSubmissionServiceTest {

    // -----------------------------------------------------------------------------------------------------
    // Screen and program identity. app/cbl/CORPT00C.cbl:37-38 and app/cpy/COTTL01Y.cpy.
    // -----------------------------------------------------------------------------------------------------

    /** {@code WS-TRANID PIC X(04) VALUE 'CR00'}, app/cbl/CORPT00C.cbl:37, rendered at :619. */
    private static final String TRANSACTION_ID = "CR00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'}, app/cbl/CORPT00C.cbl:38, rendered at :620. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** The default navigation target, named at app/cbl/CORPT00C.cbl:173 and again at :543. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The PF3 navigation target, app/cbl/CORPT00C.cbl:188. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** {@code CCDA-TITLE01} from app/cpy/COTTL01Y.cpy, forty characters including its centring blanks. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} from app/cpy/COTTL01Y.cpy, forty characters including its centring blanks. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    // -----------------------------------------------------------------------------------------------------
    // Cursor fields: the symbolic-map length fields that received MOVE -1.
    // -----------------------------------------------------------------------------------------------------

    /** app/cbl/CORPT00C.cbl:441, :533 and :181. */
    private static final String CURSOR_MONTHLY = "MONTHLYL";

    /** app/cbl/CORPT00C.cbl:264, :334 and :403. */
    private static final String CURSOR_START_MONTH = "SDTMML";

    /** app/cbl/CORPT00C.cbl:271 and :343. */
    private static final String CURSOR_START_DAY = "SDTDDL";

    /** app/cbl/CORPT00C.cbl:278 and :351. */
    private static final String CURSOR_START_YEAR = "SDTYYYYL";

    /** app/cbl/CORPT00C.cbl:285, :360 and :423. */
    private static final String CURSOR_END_MONTH = "EDTMML";

    /** app/cbl/CORPT00C.cbl:292 and :369. */
    private static final String CURSOR_END_DAY = "EDTDDL";

    /** app/cbl/CORPT00C.cbl:299 and :377. */
    private static final String CURSOR_END_YEAR = "EDTYYYYL";

    /** app/cbl/CORPT00C.cbl:472 and :492. */
    private static final String CURSOR_CONFIRM = "CONFIRML";

    // -----------------------------------------------------------------------------------------------------
    // Message literals, byte exact. Every ellipsis is exactly three full stops; "can NOT" carries a capital
    // N, O and T; and the submitted notice carries a space before its ellipsis where the empties do not.
    // -----------------------------------------------------------------------------------------------------

    /** {@code CCDA-MSG-INVALID-KEY} from app/cpy/CSMSG01Y.cpy, moved at app/cbl/CORPT00C.cbl:192. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** app/cbl/CORPT00C.cbl:261. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** app/cbl/CORPT00C.cbl:268. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** app/cbl/CORPT00C.cbl:275. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** app/cbl/CORPT00C.cbl:282. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** app/cbl/CORPT00C.cbl:289. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** app/cbl/CORPT00C.cbl:296. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** app/cbl/CORPT00C.cbl:331. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** app/cbl/CORPT00C.cbl:340. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** app/cbl/CORPT00C.cbl:348. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** app/cbl/CORPT00C.cbl:357. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** app/cbl/CORPT00C.cbl:366. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** app/cbl/CORPT00C.cbl:374. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** app/cbl/CORPT00C.cbl:400. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** app/cbl/CORPT00C.cbl:420. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** app/cbl/CORPT00C.cbl:438. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** app/cbl/CORPT00C.cbl:466, the {@code DELIMITED BY SIZE} head of the re-prompt. */
    private static final String MSG_CONFIRM_PREFIX = "Please confirm to print the ";

    /** app/cbl/CORPT00C.cbl:469, the {@code DELIMITED BY SIZE} tail of the re-prompt. */
    private static final String MSG_CONFIRM_SUFFIX = " report...";

    /** app/cbl/CORPT00C.cbl:486, the opening double quote of the rejection. */
    private static final String MSG_INVALID_CONFIRM_PREFIX = "\"";

    /** app/cbl/CORPT00C.cbl:488, the tail of the rejection. */
    private static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /** app/cbl/CORPT00C.cbl:450-451, the tail of the success notice. */
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /** app/cbl/CORPT00C.cbl:531. */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    // -----------------------------------------------------------------------------------------------------
    // The three report names, app/cbl/CORPT00C.cbl:214, :240 and :433. Unpadded, because every use composes
    // through DELIMITED BY SPACE, which stops at the first blank of the PIC X(10) field.
    // -----------------------------------------------------------------------------------------------------

    /** app/cbl/CORPT00C.cbl:214. */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /** app/cbl/CORPT00C.cbl:240. */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /** app/cbl/CORPT00C.cbl:433, assigned only after both dates have been validated. */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    /**
     * The upper bound SQS places on a {@code MessageDeduplicationId}, in characters.
     *
     * <p>Asserted rather than assumed because the identifier is generated per submission (finding H-08) and an
     * over-long value is rejected by the service at send time, one submission at a time.
     */
    private static final int MAX_DEDUPLICATION_ID_LENGTH = 128;

    // -----------------------------------------------------------------------------------------------------
    // Validator outcome tokens, app/cbl/CORPT00C.cbl:396 and :399. Opaque to this class by design.
    // -----------------------------------------------------------------------------------------------------

    /** The severity that accepts a date outright, app/cbl/CORPT00C.cbl:396. */
    private static final String SEVERITY_ACCEPTED = "0000";

    /** A non-zero severity, used to drive the two branches that only a non-zero severity reaches. */
    private static final String SEVERITY_NON_ZERO = "0003";

    /** The message number a non-zero severity is forgiven for, app/cbl/CORPT00C.cbl:399 and :419. */
    private static final String MESSAGE_NUMBER_TOLERATED = "2513";

    /** A message number carrying no exemption. */
    private static final String MESSAGE_NUMBER_REJECTED = "0304";

    /** The eleven-character literal filler between the severity and the message number, :134. */
    private static final String MESG_CODE_FILLER = "Mesg Code: ";

    /** {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)}, app/cbl/CORPT00C.cbl:133. */
    private static final int SEVERITY_CODE_LENGTH = 4;

    /** {@code CSUTLDTC-RESULT-MSG-NUM PIC X(04)}, app/cbl/CORPT00C.cbl:135. */
    private static final int MESSAGE_NUMBER_LENGTH = 4;

    // -----------------------------------------------------------------------------------------------------
    // Deck and field geometry, app/cbl/CORPT00C.cbl:79-127 and app/cpy-bms/CORPT00.CPY.
    // -----------------------------------------------------------------------------------------------------

    /** The cards the deck declares at app/cbl/CORPT00C.cbl:83-125. Seventeen, not eighteen. */
    private static final int JOB_CARD_COUNT = 17;

    /** {@code JCL-RECORD PIC X(80)} at :79, matching {@code RECORDSIZE(80)} in app/csd/CARDDEMO.CSD. */
    private static final int JOB_CARD_LENGTH = 80;

    /** The loop bound at :498, matching {@code OCCURS 1000 TIMES} at :127. */
    private static final int JOB_LINE_LIMIT = 1000;

    /** The terminating card, app/cbl/CORPT00C.cbl:125. */
    private static final String JOB_TERMINATOR_CARD = "/*EOF";

    /** {@code ERRMSGI PIC X(78)} of app/cpy-bms/CORPT00.CPY, the narrower screen carrier. */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

    /** {@code WS-MESSAGE PIC X(80)}, the wider working-storage carrier. */
    private static final int WORKING_MESSAGE_WIDTH = 80;

    /** The components app/cpy-bms/CORPT00.CPY declares as input, and the DTO therefore carries. */
    private static final int MAP_INPUT_FIELD_COUNT = 17;

    /** The ten input components {@code INITIALIZE-ALL-FIELDS} blanks at :637-645. */
    private static final int CLEARED_FIELD_COUNT = 10;

    // -----------------------------------------------------------------------------------------------------
    // Time. Every instant is explicit; none is read from the host.
    // -----------------------------------------------------------------------------------------------------

    /** Mid-June 2022, a thirty-day month. The instant every seed fixture in this repository carries. */
    private static final Instant MID_THIRTY_DAY_MONTH = FixedClockProvider.CANONICAL_INSTANT;

    /** The header date the canonical instant renders to, app/cbl/CORPT00C.cbl:622-626. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** The header time the canonical instant renders to, app/cbl/CORPT00C.cbl:627-628. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    // -----------------------------------------------------------------------------------------------------
    // Injected configuration. Transparently synthetic: no endpoint, host, port, URL, ARN, account identifier
    // or credential, and nothing here can reach a real service.
    // -----------------------------------------------------------------------------------------------------

    /** A synthetic physical queue name. The deployed value lives in configuration, never in a test. */
    private static final String QUEUE_NAME = "unit-test-report-jobs.fifo";

    /** The logical name diagnostics use, which is what {@code QUEUE ('JOBS')} at :518 called it. */
    private static final String QUEUE_LOGICAL_NAME = "JOBS";

    /** A synthetic FIFO message group. Its value rules are owned by the sibling message-group suite. */
    private static final String MESSAGE_GROUP_ID = "unit-test-report-group";

    /** A synthetic notification destination, standing in for the operator notify card at :85-86. */
    private static final String TOPIC = "unit-test-carddemo-notifications";

    // -----------------------------------------------------------------------------------------------------
    // Collaborators. Instance state only - no static mutable field exists in this class, so no test can
    // observe another's leftovers (Rule 1 Clause B, "avoid global mutable state").
    // -----------------------------------------------------------------------------------------------------

    /** The queue-publishing collaborator, mocked: nothing here may reach a real client. */
    @Mock
    private SqsTemplate sqsTemplate;

    /** The notification collaborator, mocked: the notify card is a courtesy with no error path. */
    @Mock
    private SnsTemplate snsTemplate;

    /** The date validator, mocked under strict stubs so its outcomes are driven, not reproduced. */
    @Mock
    private DateValidationService dateValidationService;

    /** What the production lambda set on the fluent send options. */
    private RecordingSendOptions sendOptions;

    /** The subject, rebuilt for every test on a clock fixed at {@link #MID_THIRTY_DAY_MONTH}. */
    private ReportSubmissionService service;

    @BeforeEach
    void setUp() {
        this.sendOptions = new RecordingSendOptions();
        this.service = serviceWithClock(FixedClockProvider.fixedClock(MID_THIRTY_DAY_MONTH));
    }

    // -----------------------------------------------------------------------------------------------------
    // Fixtures and helpers. Every one is used; an unused helper would be dead code under Rule 1 Clause B.
    // -----------------------------------------------------------------------------------------------------

    /**
     * Builds the subject over an explicit clock, which is the only way a period assertion can be
     * deterministic. app/cbl/CORPT00C.cbl:215 and :241 each read {@code FUNCTION CURRENT-DATE}.
     *
     * @param clock the time source to inject; must not be {@code null}
     * @return a fresh subject
     */
    private ReportSubmissionService serviceWithClock(final Clock clock) {
        return new ReportSubmissionService(this.sqsTemplate, this.snsTemplate, this.dateValidationService,
                clock, new NoTracerProvider(), QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC);
    }

    /**
     * Builds the subject over a clock fixed at the supplied calendar day, at noon so that no zone rule can
     * shift the day the period is derived from.
     *
     * @param isoDate the day the clock reports, as {@code yyyy-MM-dd}
     * @return a fresh subject
     */
    private ReportSubmissionService serviceOn(final String isoDate) {
        return serviceWithClock(FixedClockProvider.fixedClock(Instant.parse(isoDate + "T12:00:00Z")));
    }

    /**
     * Builds a map area from its seventeen components, in the declaration order of
     * app/cpy-bms/CORPT00.CPY:24-120. The six header components carry deliberately wrong submitted values so
     * that {@code POPULATE-HEADER-INFO} overwriting them at :616-628 is observable.
     *
     * @param monthly      {@code MONTHLYI}
     * @param yearly       {@code YEARLYI}
     * @param custom       {@code CUSTOMI}
     * @param startMonth   {@code SDTMMI}
     * @param startDay     {@code SDTDDI}
     * @param startYear    {@code SDTYYYYI}
     * @param endMonth     {@code EDTMMI}
     * @param endDay       {@code EDTDDI}
     * @param endYear      {@code EDTYYYYI}
     * @param confirmation {@code CONFIRMI}
     * @return the assembled map area
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
     * A monthly selection, app/cbl/CORPT00C.cbl:213.
     *
     * @param confirmation the confirmation gate
     * @return the assembled map area
     */
    private static ReportRequest monthlyRequest(final String confirmation) {
        return mapArea("Y", null, null, null, null, null, null, null, null, confirmation);
    }

    /**
     * A yearly selection, app/cbl/CORPT00C.cbl:239.
     *
     * @param confirmation the confirmation gate
     * @return the assembled map area
     */
    private static ReportRequest yearlyRequest(final String confirmation) {
        return mapArea(null, "Y", null, null, null, null, null, null, null, confirmation);
    }

    /**
     * A custom selection with an affirmative confirmation, app/cbl/CORPT00C.cbl:256.
     *
     * @param startMonth {@code SDTMMI}
     * @param startDay   {@code SDTDDI}
     * @param startYear  {@code SDTYYYYI}
     * @param endMonth   {@code EDTMMI}
     * @param endDay     {@code EDTDDI}
     * @param endYear    {@code EDTYYYYI}
     * @return the assembled map area
     */
    private static ReportRequest customRequest(final String startMonth, final String startDay,
            final String startYear, final String endMonth, final String endDay, final String endYear) {
        return mapArea(null, null, "Y", startMonth, startDay, startYear, endMonth, endDay, endYear, "Y");
    }

    /**
     * A custom selection over a range that resolves, carrying the supplied confirmation gate.
     *
     * @param confirmation the confirmation gate
     * @return the assembled map area
     */
    private static ReportRequest customRequestConfirmedWith(final String confirmation) {
        return mapArea(null, null, "Y", "06", "01", "2022", "06", "30", "2022", confirmation);
    }

    /**
     * Arranges the publish stub. Called only by tests that reach the queue, because an unused stub fails
     * under {@link Strictness#STRICT_STUBS}.
     */
    private void arrangePublish() {
        doAnswer(invocation -> {
            final Consumer<SqsSendOptions<JobSubmissionMessage>> configurer = invocation.getArgument(0);
            configurer.accept(this.sendOptions);
            // sendAsync, not send: the production code publishes asynchronously and then waits on the
            // returned future for a bounded deadline, so a stub of the synchronous form is never invoked.
            return CompletableFuture.completedFuture(new SendResult<>(
                    UUID.nameUUIDFromBytes("unit-test".getBytes(StandardCharsets.UTF_8)),
                    QUEUE_NAME, new GenericMessage<>(this.sendOptions.payload()), Map.of()));
        }).when(this.sqsTemplate).sendAsync(any());
    }

    /**
     * Arranges the date validator to accept whatever date it is handed, through the severity arm at
     * app/cbl/CORPT00C.cbl:396.
     */
    private void arrangeDateValidatorAccepts() {
        when(this.dateValidationService.validate(anyString(), any()))
                .thenReturn(validatorResult(SEVERITY_ACCEPTED, SEVERITY_ACCEPTED, "no error"));
    }

    /**
     * Builds a validator outcome from a severity code and a message number, laid out exactly as
     * {@code CSUTLDTC-RESULT} is at app/cbl/CORPT00C.cbl:132-136: four characters of severity, eleven of
     * literal filler, four of message number and sixty-one of message text - eighty in all.
     *
     * <p>The taxonomy behind these values is not this class's subject. The caller consults the severity code
     * and the message number and nothing else, so driving those two is what proves the caller's behaviour.
     *
     * @param severityCode  the four characters at offset 0
     * @param messageNumber the four characters at offset 15
     * @param messageText   the text the caller must ignore
     * @return an eighty-character outcome
     */
    private static DateValidationService.DateValidationResult validatorResult(
            final String severityCode, final String messageNumber, final String messageText) {
        final String area = fixedWidth(severityCode, SEVERITY_CODE_LENGTH)
                + MESG_CODE_FILLER
                + fixedWidth(messageNumber, MESSAGE_NUMBER_LENGTH)
                + fixedWidth(messageText, DateValidationService.MESSAGE_TEXT_LENGTH);
        return new DateValidationService.DateValidationResult(
                new DateValidationService.FeedbackCode(
                        Integer.parseInt(severityCode), Integer.parseInt(messageNumber)),
                area);
    }

    /**
     * Pads or truncates to an exact width, which is what a COBOL {@code MOVE} into a {@code PIC X(n)} item
     * does.
     *
     * @param value the value to fit
     * @param width the receiving width
     * @return a string of exactly {@code width} characters
     */
    private static String fixedWidth(final String value, final int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Reads a declared constant of the subject, so that a geometry the target no longer executes is still
     * pinned by a test rather than only by a comment.
     *
     * @param name the field name
     * @return the field's value
     * @throws ReflectiveOperationException if the field is absent, which is itself the finding
     */
    private static Object declaredConstant(final String name) throws ReflectiveOperationException {
        final Field field = ReportSubmissionService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * The names of the subject's declared methods, used to prove the paragraph map.
     *
     * @return every declared method name
     */
    private static List<String> declaredMethodNames() {
        return Arrays.stream(ReportSubmissionService.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
    }

    // =====================================================================================================
    // 1. Construction and state. Rule 1 Clause B: explicit null handling, and no global mutable state.
    // =====================================================================================================

    @Nested
    @DisplayName("1. Construction - every collaborator refused when absent, and no mutable state anywhere")
    class Construction {

        @Test
        @DisplayName("the publisher is required, it being the only route off the online tier")
        void thePublisherIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(null, snsTemplate, dateValidationService,
                            FixedClockProvider.canonicalClock(), new NoTracerProvider(),
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("sqsTemplate");
        }

        @Test
        @DisplayName("the notification template is required, the notify card not being optional wiring")
        void theNotificationTemplateIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, null, dateValidationService,
                            FixedClockProvider.canonicalClock(), new NoTracerProvider(),
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("snsTemplate");
        }

        @Test
        @DisplayName("the date validator is required, the custom period being unresolvable without it")
        void theDateValidatorIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate, null,
                            FixedClockProvider.canonicalClock(), new NoTracerProvider(),
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("dateValidationService");
        }

        @Test
        @DisplayName("the time source is required, so no path can fall back to a wall clock")
        void theTimeSourceIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate,
                            dateValidationService, null, new NoTracerProvider(),
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("the tracer provider is required, although the tracer it yields is not")
        void theTracerProviderIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate,
                            dateValidationService, FixedClockProvider.canonicalClock(), null,
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("tracerProvider");
        }

        @Test
        @DisplayName("a provider that yields no tracer is accepted, tracing being optional")
        void aProviderThatYieldsNoTracerIsAccepted() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.successHighlight())
                    .as("an absent tracer must not fail a submission")
                    .isTrue();
        }

        @Test
        @DisplayName("the queue name is required")
        void theQueueNameIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate,
                            dateValidationService, FixedClockProvider.canonicalClock(),
                            new NoTracerProvider(),
                            null, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("reportQueueName");
        }

        @Test
        @DisplayName("the logical queue name is required, being what a diagnostic is allowed to name")
        void theLogicalQueueNameIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate,
                            dateValidationService, FixedClockProvider.canonicalClock(),
                            new NoTracerProvider(),
                            QUEUE_NAME, null, MESSAGE_GROUP_ID, TOPIC))
                    .withMessageContaining("reportQueueLogicalName");
        }

        @Test
        @DisplayName("the FIFO message group is required; its value rules belong to the sibling suite")
        void theMessageGroupIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate,
                            dateValidationService, FixedClockProvider.canonicalClock(),
                            new NoTracerProvider(),
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, null, TOPIC))
                    .withMessageContaining("reportMessageGroupId");
        }

        @Test
        @DisplayName("the notification destination is required")
        void theNotificationDestinationIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubmissionService(sqsTemplate, snsTemplate,
                            dateValidationService, FixedClockProvider.canonicalClock(),
                            new NoTracerProvider(),
                            QUEUE_NAME, QUEUE_LOGICAL_NAME, MESSAGE_GROUP_ID, null))
                    .withMessageContaining("notificationTopic");
        }

        @Test
        @DisplayName("every instance field is private and final, so the bean carries nothing between turns")
        void everyInstanceFieldIsPrivateAndFinal() {
            final List<String> offenders = Arrays.stream(ReportSubmissionService.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isPrivate(field.getModifiers())
                            || !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList();

            assertThat(offenders)
                    .as("WS-ERR-FLG, END-LOOP-YES and WS-IDX are method local in the target, never fields")
                    .isEmpty();
        }

        @Test
        @DisplayName("every static field is final, so no turn can mutate class-level state")
        void everyStaticFieldIsFinal() {
            final List<String> offenders = Arrays.stream(ReportSubmissionService.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList();

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("two turns on one instance are independent, the first leaving nothing behind")
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
    }

    // =====================================================================================================
    // 2. Paragraph correspondence and the field budget. app/cbl/CORPT00C.cbl has ten labels and
    // app/cpy-bms/CORPT00.CPY seventeen input fields.
    // =====================================================================================================

    @Nested
    @DisplayName("2. Paragraph map - ten labels, ten methods, three public entry points, seventeen fields")
    class ParagraphMap {

        @Test
        @DisplayName("all ten paragraph labels have a private counterpart, none consolidated")
        void allTenParagraphsHaveAPrivateCounterpart() {
            assertThat(declaredMethodNames())
                    .as("MAIN-PARA :163, PROCESS-ENTER-KEY :208, SUBMIT-JOB-TO-INTRDR :462, "
                            + "WIRTE-JOBSUB-TDQ :515, RETURN-TO-PREV-SCREEN :540, SEND-TRNRPT-SCREEN :556, "
                            + "RETURN-TO-CICS :585, RECEIVE-TRNRPT-SCREEN :596, POPULATE-HEADER-INFO :609 "
                            + "and INITIALIZE-ALL-FIELDS :633")
                    .contains("mainPara", "processEnterKey", "submitJobToIntrdr", "wirteJobsubTdq",
                            "returnToPrevScreen", "sendTrnrptScreen", "returnToCics",
                            "receiveTrnrptScreen", "populateHeaderInfo", "initializeAllFields");
        }

        @Test
        @DisplayName("RETURN-TO-CICS survives as a labelled method although a REST turn needs no return")
        void returnToCicsSurvivesAsALabelledMethod() {
            assertThat(declaredMethodNames())
                    .as(":585 has no behaviour of its own in a stateless service, and is retained so the "
                            + "paragraph map stays provable rather than deleted as apparent dead code")
                    .contains("returnToCics");
        }

        @Test
        @DisplayName("the source's own misspelling of WIRTE-JOBSUB-TDQ is preserved, not corrected")
        void theSourceMisspellingIsPreserved() {
            assertThat(declaredMethodNames())
                    .as(":515 spells the label WIRTE-JOBSUB-TDQ; correcting it would break the map")
                    .contains("wirteJobsubTdq")
                    .doesNotContain("writeJobsubTdq");
        }

        @Test
        @DisplayName("exactly three public operations exist, one per entry mode of :165-183")
        void exactlyThreePublicOperationsExist() {
            final List<String> publicMethods = Arrays.stream(
                            ReportSubmissionService.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(publicMethods)
                    .containsExactly("openReportScreen", "openWithoutContext", "submitScreen");
        }

        @Test
        @DisplayName("the entry mode has exactly three constants, matching :172, :177 and :183")
        void theEntryModeHasExactlyThreeConstants() {
            assertThat(ReportSubmissionService.EntryMode.values())
                    .containsExactly(ReportSubmissionService.EntryMode.NO_COMMAREA,
                            ReportSubmissionService.EntryMode.FIRST_ENTRY,
                            ReportSubmissionService.EntryMode.RE_ENTER);
        }

        @Test
        @DisplayName("the attention identifier has exactly three constants, matching EVALUATE EIBAID :184")
        void theAttentionIdentifierHasExactlyThreeConstants() {
            assertThat(AttentionIdentifier.values())
                    .as(":185 DFHENTER, :187 DFHPF3, :190 WHEN OTHER - three arms and no more")
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.OTHER);
        }

        @Test
        @DisplayName("the map area the service consumes carries seventeen components, one per input field")
        void theMapAreaCarriesSeventeenComponents() {
            final RecordComponent[] components = ReportRequest.class.getRecordComponents();

            assertThat(components)
                    .as("app/cpy-bms/CORPT00.CPY:24-120 declares seventeen input fields, and the exhaustive "
                            + "shape contract for the payload itself belongs to the unit model suite")
                    .hasSize(MAP_INPUT_FIELD_COUNT);
            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime", "monthlySelected", "yearlySelected", "customSelected",
                            "startDateMonth", "startDateDay", "startDateYear", "endDateMonth", "endDateDay",
                            "endDateYear", "confirmation", "errorMessage");
        }

        @Test
        @DisplayName("the queue message carries three components, the whole deck having collapsed into it")
        void theQueueMessageCarriesThreeComponents() {
            assertThat(Arrays.stream(JobSubmissionMessage.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList())
                    .as("the report name of :58 plus the two dates of :60-71, and nothing else")
                    .containsExactly("reportName", "startDate", "endDate");
        }
    }

    // =====================================================================================================
    // 3. Entry modes and the three EIBAID arms, app/cbl/CORPT00C.cbl:165-195.
    // =====================================================================================================

    @Nested
    @DisplayName("3. Entry and attention :165-195 - first entry, no commarea, enter, PF3 and any other key")
    class EntryAndAttention {

        @Test
        @DisplayName("first entry paints an empty form with the cursor on the monthly selector")
        void firstEntryPaintsAnEmptyForm() {
            final ReportSubmissionScreen screen = service.openReportScreen();

            assertThat(screen.cursorField())
                    .as(":181 MOVE -1 TO MONTHLYL OF CORPT0AI")
                    .isEqualTo(CURSOR_MONTHLY);
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.successHighlight()).isFalse();
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.form().monthlySelected())
                    .as(":179 MOVE LOW-VALUES TO CORPT0AO leaves every selector absent")
                    .isNull();
            assertThat(screen.form().errorMessage())
                    .as(":169-170 blank both message carriers")
                    .isEmpty();
        }

        @Test
        @DisplayName("first entry paints the header, SEND-TRNRPT-SCREEN performing :558 unconditionally")
        void firstEntryPaintsTheHeader() {
            final ReportRequest form = service.openReportScreen().form();

            assertThat(form.transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(form.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(form.title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(form.title02()).isEqualTo(SCREEN_TITLE_02);
            assertThat(form.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(form.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @Test
        @DisplayName("an absent communication area leaves for the sign-on program without painting")
        void anAbsentCommunicationAreaLeavesForSignOn() {
            final ReportSubmissionScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget())
                    .as(":172-174 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM then RETURN-TO-PREV-SCREEN")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(screen.form().transactionName())
                    .as("RETURN-TO-PREV-SCREEN transfers control; it never performs :558")
                    .isNull();
        }

        @Test
        @DisplayName("PF3 leaves for the main menu, carrying the submitted map area unchanged")
        void pf3LeavesForTheMainMenu() {
            final ReportRequest submitted = monthlyRequest("Y");

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, submitted);

            assertThat(screen.navigationTarget())
                    .as(":188 MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM")
                    .isEqualTo(MAIN_MENU_PROGRAM);
            assertThat(screen.form()).isEqualTo(submitted);
        }

        @Test
        @DisplayName("PF3 never reaches the queue, so leaving cannot submit a job")
        void pf3NeverReachesTheQueue() {
            service.submitScreen(AttentionIdentifier.PF3, monthlyRequest("Y"));

            verifyNoInteractions(sqsTemplate, snsTemplate, dateValidationService);
        }

        @Test
        @DisplayName("any other key produces the shared invalid-key message on the monthly selector")
        void anyOtherKeyProducesTheSharedInvalidKeyMessage() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.OTHER, monthlyRequest("Y")))
                    .withMessage(MSG_INVALID_KEY)
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName())
                                .as(":193 MOVE -1 TO MONTHLYL OF CORPT0AI")
                                .isEqualTo(CURSOR_MONTHLY);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("the attention identifier is required on a re-enter turn")
        void theAttentionIdentifierIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null, monthlyRequest("Y")))
                    .withMessageContaining("attentionIdentifier");
        }

        @Test
        @DisplayName("the map area is required on a re-enter turn, a null request being untrusted input")
        void theMapAreaIsRequiredOnAReEnterTurn() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, null))
                    .withMessageContaining("request");
            verifyNoInteractions(sqsTemplate, snsTemplate, dateValidationService);
        }
    }

    // =====================================================================================================
    // 4. Report-type precedence, app/cbl/CORPT00C.cbl:212-442. EVALUATE TRUE, first match wins.
    // =====================================================================================================

    @Nested
    @DisplayName("4. Report-type precedence :212-442 - monthly, then yearly, then custom, then none")
    class ReportTypePrecedence {

        @Test
        @DisplayName("monthly wins when all three selectors are supplied, being the first WHEN")
        void monthlyWinsWhenEverySelectorIsSupplied() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea("Y", "Y", "Y", "06", "01", "2022", "06", "30", "2022", "Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_MONTHLY);
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("yearly wins over custom when monthly is absent")
        void yearlyWinsOverCustom() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, "Y", "Y", "06", "01", "2022", "06", "30", "2022", "Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_YEARLY);
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("custom is reached only when neither of the first two selectors is supplied")
        void customIsReachedLast() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_CUSTOM);
        }

        @Test
        @DisplayName("no selector at all produces the select-a-report-type message on the monthly selector")
        void noSelectorProducesTheSelectMessage() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            mapArea(null, null, null, "06", "01", "2022", "06", "30", "2022", "Y")))
                    .withMessage(MSG_SELECT_REPORT_TYPE)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .as(":441 MOVE -1 TO MONTHLYL OF CORPT0AI")
                            .isEqualTo(CURSOR_MONTHLY));
            verifyNoInteractions(sqsTemplate, dateValidationService);
        }

        @ParameterizedTest(name = "a selector of [{0}] is not a selection")
        @ValueSource(strings = {"", " ", "   ", "\u0000", "\u0000\u0000"})
        @DisplayName("SPACES and LOW-VALUES are not a selection, matching NOT = SPACES AND LOW-VALUES")
        void aBlankOrLowValueSelectorIsNotASelection(final String selector) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            mapArea(selector, selector, selector, null, null, null, null, null, null, "Y")))
                    .withMessage(MSG_SELECT_REPORT_TYPE);
        }

        @Test
        @DisplayName("an absent or blank selector is skipped, so the next populated one wins")
        void anAbsentSelectorIsSkippedSoTheNextOneWins() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, "", "Y", "06", "01", "2022", "06", "30", "2022", "Y"));

            assertThat(sendOptions.payload().reportName())
                    .as(":213 is absent and :239 is blank, so :256 is the first WHEN that matches")
                    .isEqualTo(REPORT_NAME_CUSTOM);
        }

        @ParameterizedTest(name = "a selector of [{0}] IS a selection")
        @ValueSource(strings = {"Y", "y", "X", "1", "/", "."})
        @DisplayName("any non-blank selector selects - the source tests presence, never a particular value")
        void anyNonBlankSelectorIsASelection(final String selector) {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(selector, null, null, null, null, null, null, null, null, "Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_MONTHLY);
        }
    }

    // =====================================================================================================
    // 5. The monthly period, app/cbl/CORPT00C.cbl:213-236. A FULL CALENDAR MONTH.
    //
    // :223 MOVE 1 TO WS-CURDATE-DAY; :224 ADD 1 TO WS-CURDATE-MONTH; :225-228 carry the year when the month
    // exceeds 12; :229-230 COMPUTE DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1). The first of the next month,
    // less one day, is the last day of THIS one; :232-234 then read the mutated year, month and day back out
    // through the WS-CURDATE-N REDEFINES alias of app/cpy/CSDAT01Y.cpy:23. The start is the first of this
    // month, from :217-219.
    // =====================================================================================================

    @Nested
    @DisplayName("5. The monthly period :213-236 - a FULL CALENDAR MONTH with the twelve-month carry")
    class MonthlyPeriod {

        @Test
        @DisplayName("mid-month, the period runs from the first day of the month to its last day")
        void midMonthTheperiodRunsToTheMonthEnd() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate())
                    .as(":217-219 assemble the current year and month with a day of 01")
                    .isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate())
                    .as(":223-230 give the first of July less one day, and :232-234 read that value back "
                            + "out of the redefined date area")
                    .isEqualTo("2022-06-30");
        }

        @Test
        @DisplayName("on the FIRST of a month the period still spans the whole month")
        void onTheFirstOfAMonthThePeriodStillSpansTheWholeMonth() {
            arrangePublish();

            serviceOn("2022-06-01").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate())
                    .as("a start equal to today must not collapse the period to a single day, because "
                            + ":223 discards the day before the arithmetic begins")
                    .isEqualTo("2022-06-30");
        }

        @Test
        @DisplayName("on the LAST day of a month the period is unchanged, start and end both being derived")
        void onTheLastDayOfAMonthThePeriodIsUnchanged() {
            arrangePublish();

            serviceOn("2022-06-30").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-06-30");
        }

        @Test
        @DisplayName("a thirty-one day month ends on the thirty-first")
        void aThirtyOneDayMonthEndsOnTheThirtyFirst() {
            arrangePublish();

            serviceOn("2022-07-15").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-07-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-07-31");
        }

        @Test
        @DisplayName("a thirty day month ends on the thirtieth")
        void aThirtyDayMonthEndsOnTheThirtieth() {
            arrangePublish();

            serviceOn("2022-04-15").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-04-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-04-30");
        }

        @Test
        @DisplayName("a non-leap February ends on the twenty-eighth")
        void aNonLeapFebruaryEndsOnTheTwentyEighth() {
            arrangePublish();

            serviceOn("2022-02-15").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-02-01");
            assertThat(sendOptions.payload().endDate())
                    .as("2022 is not a leap year, so the integer arithmetic of :229-230 lands on the 28th")
                    .isEqualTo("2022-02-28");
        }

        @Test
        @DisplayName("a leap February ends on the twenty-ninth")
        void aLeapFebruaryEndsOnTheTwentyNinth() {
            arrangePublish();

            serviceOn("2024-02-15").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2024-02-01");
            assertThat(sendOptions.payload().endDate())
                    .as("2024 is a leap year, so the same arithmetic lands on the 29th")
                    .isEqualTo("2024-02-29");
        }

        @Test
        @DisplayName("DECEMBER carries the year at :225-228 and still ends on the CURRENT year's 31st")
        void decemberCarriesTheYearAndEndsInTheCurrentYear() {
            arrangePublish();

            serviceOn("2022-12-15").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-12-01");
            assertThat(sendOptions.payload().endDate())
                    .as(":224 makes the month 13, :225-228 carry to January 2023, and :229-230 subtract one "
                            + "day - which lands back in 2022. The NEXT year must never appear")
                    .isEqualTo("2022-12-31")
                    .doesNotContain("2023");
        }

        @Test
        @DisplayName("the thirty-first of December is the same period, the carry being independent of the day")
        void theThirtyFirstOfDecemberIsTheSamePeriod() {
            arrangePublish();

            serviceOn("2022-12-31").submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-12-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("both dates come from ONE clock reading, so the period cannot straddle a month end")
        void bothDatesComeFromOneClockReading() {
            arrangePublish();
            final AdvancingClock clock = new AdvancingClock(
                    Instant.parse("2022-06-30T23:59:59Z"), Duration.ofSeconds(2L),
                    FixedClockProvider.CANONICAL_ZONE);

            serviceWithClock(clock).submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().startDate())
                    .as(":215 performs ONE MOVE FUNCTION CURRENT-DATE; a second reading would have rolled "
                            + "into July and produced a period spanning two months")
                    .isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-06-30");
            assertThat(clock.reads())
                    .as("one reading for the period, one more for the header at :611")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("each date collapses into ONE value serving BOTH parameter slots, so they cannot differ")
        void eachDateServesBothParameterSlots() throws ReflectiveOperationException {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(declaredConstant("JOB_CARD_COUNT"))
                    .as("the deck that held PARM-START-DATE-1 at :106 and PARM-START-DATE-2 at :118")
                    .isEqualTo(JOB_CARD_COUNT);
            assertThat(JobSubmissionMessage.class.getRecordComponents())
                    .as(":220-221 and :235-236 each move ONE assembled date to TWO slots, so the target "
                            + "carries one field per date and the two slots can never diverge")
                    .hasSize(3);
            assertThat(sendOptions.payload().startDate())
                    .isEqualTo(sendOptions.payload().startDate());
            assertThat(sendOptions.payload().startDate()).hasSize(10);
            assertThat(sendOptions.payload().endDate()).hasSize(10);
        }

        @Test
        @DisplayName("the report name is the unpadded literal of :214")
        void theReportNameIsTheUnpaddedLiteral() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload().reportName())
                    .as("WS-REPORT-NAME is PIC X(10) at :58, but every use composes DELIMITED BY SPACE")
                    .isEqualTo("Monthly")
                    .isEqualTo(REPORT_NAME_MONTHLY);
        }

        @Test
        @DisplayName("both dates are ten characters with dashes at positions five and eight")
        void bothDatesAreTenCharactersWithDashes() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            for (final String date : List.of(sendOptions.payload().startDate(),
                    sendOptions.payload().endDate())) {
                assertThat(date)
                        .as(":60-71 declare 4 + 1 + 2 + 1 + 2 with literal dash fillers")
                        .hasSize(10)
                        .matches("\\d{4}-\\d{2}-\\d{2}");
                assertThat(date.charAt(4)).isEqualTo('-');
                assertThat(date.charAt(7)).isEqualTo('-');
            }
        }

        @Test
        @DisplayName("the monthly arm ignores the six custom components and never calls the validator")
        void theMonthlyArmIgnoresTheCustomComponents() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea("Y", null, null, "99", "99", "0000", "99", "99", "9999", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-06-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-06-30");
            verifyNoInteractions(dateValidationService);
        }
    }

    // =====================================================================================================
    // 6. The yearly period, app/cbl/CORPT00C.cbl:239-253. January first through December thirty-first.
    // =====================================================================================================

    @Nested
    @DisplayName("6. The yearly period :239-253 - January first through December thirty-first")
    class YearlyPeriod {

        @Test
        @DisplayName("the period spans the whole calendar year containing today")
        void thePeriodSpansTheWholeCalendarYear() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().startDate())
                    .as(":243-246 one multi-receiver MOVE of the year, then '01' to both month and day")
                    .isEqualTo("2022-01-01");
            assertThat(sendOptions.payload().endDate())
                    .as(":250-251 MOVE '12' then '31'")
                    .isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("a leap year is no different, the end date being two literals rather than a computation")
        void aLeapYearIsNoDifferent() {
            arrangePublish();

            serviceOn("2024-02-29").submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2024-01-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2024-12-31");
        }

        @Test
        @DisplayName("both dates come from ONE clock reading, so the period cannot straddle a year end")
        void bothDatesComeFromOneClockReading() {
            arrangePublish();
            final AdvancingClock clock = new AdvancingClock(
                    Instant.parse("2022-12-31T23:59:59Z"), Duration.ofSeconds(2L),
                    FixedClockProvider.CANONICAL_ZONE);

            serviceWithClock(clock).submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().startDate())
                    .as(":241 performs ONE MOVE FUNCTION CURRENT-DATE")
                    .isEqualTo("2022-01-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-12-31");
        }

        @Test
        @DisplayName("the report name is the unpadded literal of :240")
        void theReportNameIsTheUnpaddedLiteral() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"));

            assertThat(sendOptions.payload().reportName())
                    .isEqualTo("Yearly")
                    .isEqualTo(REPORT_NAME_YEARLY);
        }

        @Test
        @DisplayName("the yearly arm ignores the six custom components and never calls the validator")
        void theYearlyArmIgnoresTheCustomComponents() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, "Y", null, "99", "99", "0000", "99", "99", "9999", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-01-01");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-12-31");
            verifyNoInteractions(dateValidationService);
        }
    }

    // =====================================================================================================
    // 7. The custom period, layer one: emptiness. app/cbl/CORPT00C.cbl:258-303, an EVALUATE TRUE whose six
    // WHEN clauses are mutually exclusive, so only the FIRST empty component is ever reported.
    // =====================================================================================================

    @Nested
    @DisplayName("7. Custom layer 1 :258-303 - six emptiness guards in source order, first match wins")
    class CustomEmptiness {

        @Test
        @DisplayName("a wholly empty range reports the START MONTH, the first WHEN of the EVALUATE")
        void aWhollyEmptyRangeReportsTheStartMonth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)))
                    .withMessage(MSG_START_MONTH_EMPTY)
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName())
                                .as(":264 MOVE -1 TO SDTMML OF CORPT0AI")
                                .isEqualTo(CURSOR_START_MONTH);
                        assertThat(failure.getFailureKind())
                                .as("an empty field is BLANK, not INVALID")
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
        }

        @Test
        @DisplayName("the start day is reported second, :266-272")
        void theStartDayIsReportedSecond() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", null, null, null, null, null)))
                    .withMessage(MSG_START_DAY_EMPTY)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_START_DAY));
        }

        @Test
        @DisplayName("the start year is reported third, :273-279")
        void theStartYearIsReportedThird() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", null, null, null, null)))
                    .withMessage(MSG_START_YEAR_EMPTY)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_START_YEAR));
        }

        @Test
        @DisplayName("the end month is reported fourth, :280-286")
        void theEndMonthIsReportedFourth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", null, null, null)))
                    .withMessage(MSG_END_MONTH_EMPTY)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_END_MONTH));
        }

        @Test
        @DisplayName("the end day is reported fifth, :287-293")
        void theEndDayIsReportedFifth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", null, null)))
                    .withMessage(MSG_END_DAY_EMPTY)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_END_DAY));
        }

        @Test
        @DisplayName("the end year is reported sixth and last, :294-300")
        void theEndYearIsReportedSixth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", "30", null)))
                    .withMessage(MSG_END_YEAR_EMPTY)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_END_YEAR));
        }

        @ParameterizedTest(name = "a start month of [{0}] is empty")
        @ValueSource(strings = {"", " ", "  ", "\u0000", "\u0000\u0000"})
        @DisplayName("SPACES and LOW-VALUES are both empty, matching = SPACES OR LOW-VALUES")
        void spacesAndLowValuesAreBothEmpty(final String component) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(component, "01", "2022", "06", "30", "2022")))
                    .withMessage(MSG_START_MONTH_EMPTY);
        }

        @Test
        @DisplayName("only the FIRST empty component is reported, the EVALUATE arms being exclusive")
        void onlyTheFirstEmptyComponentIsReported() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)))
                    .withMessage(MSG_START_MONTH_EMPTY)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("no arm after :265 can run, so no later literal may appear")
                            .doesNotContain("Day")
                            .doesNotContain("Year")
                            .doesNotContain("End Date"));
        }

        @Test
        @DisplayName("all six populated falls through the WHEN OTHER arm at :301-302 and proceeds")
        void allSixPopulatedProceeds() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_CUSTOM);
        }

        @Test
        @DisplayName("no emptiness failure reaches the queue or the validator")
        void noEmptinessFailureReachesTheQueue() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)));

            verifyNoInteractions(sqsTemplate, snsTemplate, dateValidationService);
        }
    }

    // =====================================================================================================
    // 8. The custom period, layers two and three. :305-327 normalise each component through a MOVE into
    // PIC 9(n) after FUNCTION NUMVAL-C; :329-379 then apply SIX INDEPENDENT IF statements.
    //
    // Each of those six ends in PERFORM SEND-TRNRPT-SCREEN, and that paragraph ends GO TO RETURN-TO-CICS at
    // :580 - a terminal EXEC CICS RETURN. The FIRST guard to fire therefore ends the turn, and no later
    // guard can run. A secondary description of this program claims the LAST guard wins; it cannot.
    // =====================================================================================================

    @Nested
    @DisplayName("8. Custom layers 2 and 3 :305-379 - string upper bounds, no lower bound, no year bound")
    class CustomComponentValues {

        @Test
        @DisplayName("a single digit is normalised to two, so a month of 6 is accepted as 06")
        void aSingleDigitIsNormalisedToTwo() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "6", "1", "2022", "6", "30", "2022", "Y"));

            verify(dateValidationService)
                    .validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD);
            verify(dateValidationService)
                    .validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("extra digits are reduced modulo the receiving width, so 0012 is the month 12")
        void extraDigitsAreReducedModuloTheWidth() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "0012", "01", "2022", "06", "30", "2022", "Y"));

            verify(dateValidationService)
                    .validate("2022-12-01", DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("a component with no digits normalises to zeros, which the guards then let through")
        void aComponentWithNoDigitsNormalisesToZeros() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "AB", "01", "2022", "06", "30", "2022", "Y"));

            // :305-307 leave the month as 00, and :329-330 has NO lower bound to catch it.
            verify(dateValidationService)
                    .validate("2022-00-01", DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("a start month above twelve is rejected on the start month field")
        void aStartMonthAboveTwelveIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("13", "01", "2022", "06", "30", "2022")))
                    .withMessage(MSG_START_MONTH_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .as(":334 MOVE -1 TO SDTMML OF CORPT0AI")
                            .isEqualTo(CURSOR_START_MONTH));
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("a start day above thirty-one is rejected on the start day field")
        void aStartDayAboveThirtyOneIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "32", "2022", "06", "30", "2022")))
                    .withMessage(MSG_START_DAY_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_START_DAY));
        }

        @Test
        @DisplayName("an end month above twelve is rejected on the end month field")
        void anEndMonthAboveTwelveIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "13", "30", "2022")))
                    .withMessage(MSG_END_MONTH_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_END_MONTH));
        }

        @Test
        @DisplayName("an end day above thirty-one is rejected on the end day field")
        void anEndDayAboveThirtyOneIsRejected() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", "32", "2022")))
                    .withMessage(MSG_END_DAY_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_END_DAY));
        }

        @Test
        @DisplayName("the two year literals exist for the arms the source retains but cannot reach")
        void theTwoYearLiteralsExistForTheRetainedArms() throws ReflectiveOperationException {
            assertThat(declaredConstant("MSG_START_YEAR_INVALID"))
                    .as(":347-354 is retained one for one although normalisation makes it unreachable")
                    .isEqualTo(MSG_START_YEAR_INVALID);
            assertThat(declaredConstant("MSG_END_YEAR_INVALID"))
                    .as(":373-379, likewise retained")
                    .isEqualTo(MSG_END_YEAR_INVALID);
        }

        @ParameterizedTest(name = "month [{0}] and day [{1}] sit ON the bound and are accepted")
        @CsvSource({"12, 31", "01, 01", "12, 01", "01, 31"})
        @DisplayName("twelve and thirty-one are ON the bound, the tests being strictly greater than")
        void theBoundaryValuesAreAccepted(final String month, final String day) {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", month, day, "2022", month, day, "2022", "Y"));

            assertThat(sendOptions.payload().startDate()).isEqualTo("2022-" + month + "-" + day);
        }

        @Test
        @DisplayName("the two bounds are the source's STRING literals, never integers")
        void theTwoBoundsAreStringLiterals() throws ReflectiveOperationException {
            assertThat(declaredConstant("MONTH_UPPER_BOUND"))
                    .as(":330 compares against the character literal '12'")
                    .isInstanceOf(String.class)
                    .isEqualTo("12");
            assertThat(declaredConstant("DAY_UPPER_BOUND"))
                    .as(":339 compares against the character literal '31'")
                    .isInstanceOf(String.class)
                    .isEqualTo("31");
        }

        @Test
        @DisplayName("a month of 00 and a day of 00 pass layer 2 and reach layer 3 unchallenged")
        void zeroMonthAndZeroDayReachTheValidator() {
            when(dateValidationService.validate("2022-00-00", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_REJECTED, "bad month"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("00", "00", "2022", "06", "30", "2022")))
                    .as(":329-346 have NO lower bound, so a zero month and a zero day are rejected by the "
                            + "date validator at :396-405 and not by the range guards")
                    .withMessage(MSG_START_DATE_INVALID);

            verify(dateValidationService).validate("2022-00-00", DateValidationService.MASK_YYYY_MM_DD);
        }

        @ParameterizedTest(name = "a year of [{0}] passes layer 2")
        @ValueSource(strings = {"0000", "9999"})
        @DisplayName("neither year has ANY range check, only IS NOT NUMERIC, so both extremes pass layer 2")
        void neitherYearHasARangeCheck(final String year) {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "06", "01", year, "06", "30", year, "Y"));

            // :347 and :373 carry no OR continuation, so no bound exists for either year to fail.
            verify(dateValidationService)
                    .validate(year + "-06-01", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(sendOptions.payload().startDate()).startsWith(year);
        }

        @Test
        @DisplayName("the six guards are independent IFs, but the FIRST to fire ends the turn - not the last")
        void theFirstGuardToFireEndsTheTurn() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("13", "32", "2022", "13", "32", "2022")))
                    .as("all four bounded components are out of range; :329-379 is six independent IF "
                            + "statements, yet each ends PERFORM SEND-TRNRPT-SCREEN, and :580 ends that "
                            + "paragraph with GO TO RETURN-TO-CICS - a terminal EXEC CICS RETURN. So the "
                            + "start month, the first guard, is the only message that can be produced")
                    .withMessage(MSG_START_MONTH_INVALID)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("Day")
                            .doesNotContain("End Date"));
        }

        @Test
        @DisplayName("the guards run in source order: start month, start day, end month, then end day")
        void theGuardsRunInSourceOrder() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "32", "2022", "13", "32", "2022")))
                    .withMessage(MSG_START_DAY_INVALID);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "13", "32", "2022")))
                    .withMessage(MSG_END_MONTH_INVALID);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", "01", "2022", "06", "32", "2022")))
                    .withMessage(MSG_END_DAY_INVALID);
        }

        @Test
        @DisplayName("no range failure reaches the queue")
        void noRangeFailureReachesTheQueue() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("13", "01", "2022", "06", "30", "2022")));

            verifyNoInteractions(sqsTemplate, snsTemplate);
        }
    }

    // =====================================================================================================
    // 9. The custom period, whole-date validation. :388-406 for the start date and :408-426 for the end.
    //
    // :396 accepts a severity of '0000' outright. :399 then forgives a NON-ZERO severity when the message
    // number is exactly '2513'. Only those two values are read; the sixty-one characters of message text at
    // :136 are never consulted. The taxonomy behind the numbers belongs to the validation suite.
    // =====================================================================================================

    @Nested
    @DisplayName("9. Custom whole-date validation :388-426 - severity 0000 accepted, 2513 tolerated")
    class CustomDateValidation {

        @Test
        @DisplayName("the assembled dates and the mask cross the boundary exactly as :388-394 build them")
        void theAssembledDatesAndMaskCrossTheBoundary() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            final InOrder order = inOrder(dateValidationService);
            order.verify(dateValidationService)
                    .validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD);
            order.verify(dateValidationService)
                    .validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the mask is the ten characters of WS-DATE-FORMAT at :72, not an invented pattern")
        void theMaskIsTheSourceLiteral() {
            assertThat(DateValidationService.MASK_YYYY_MM_DD)
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(10);
        }

        @Test
        @DisplayName("the assembled date carries dashes at positions five and eight, per :60-71")
        void theAssembledDateCarriesDashesAtPositionsFiveAndEight() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "3", "7", "1999", "12", "25", "2001", "Y"));

            verify(dateValidationService)
                    .validate("1999-03-07", DateValidationService.MASK_YYYY_MM_DD);
            verify(dateValidationService)
                    .validate("2001-12-25", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(sendOptions.payload().startDate().charAt(4)).isEqualTo('-');
            assertThat(sendOptions.payload().startDate().charAt(7)).isEqualTo('-');
        }

        @Test
        @DisplayName("severity 0000 is accepted outright, whatever the message number happens to be")
        void severityZeroIsAcceptedWhateverTheMessageNumber() {
            arrangePublish();
            when(dateValidationService.validate(anyString(), any()))
                    .thenReturn(validatorResult(SEVERITY_ACCEPTED, MESSAGE_NUMBER_REJECTED, "ignored"));

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().reportName())
                    .as(":396 tests the severity alone and CONTINUEs; the message number is not consulted "
                            + "on that arm at all")
                    .isEqualTo(REPORT_NAME_CUSTOM);
        }

        @Test
        @DisplayName("a NON-ZERO severity whose message number is 2513 is ACCEPTED - the :399 exemption")
        void aNonZeroSeverityWithTheToleratedMessageNumberIsAccepted() {
            arrangePublish();
            when(dateValidationService.validate(anyString(), any()))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_TOLERATED, "out of range"));

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(screen.successHighlight())
                    .as(":399 reads IF ... NOT = '2513', so an equal message number falls through to "
                            + "acceptance even though the severity is not zero. A port that rejected every "
                            + "non-zero severity would reject dates the source accepts")
                    .isTrue();
            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_CUSTOM);
        }

        @Test
        @DisplayName("the exemption applies to the END date too, both calls sharing it")
        void theExemptionAppliesToTheEndDateToo() {
            arrangePublish();
            when(dateValidationService.validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_ACCEPTED, SEVERITY_ACCEPTED, "ok"));
            when(dateValidationService.validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_TOLERATED, "out of range"));

            service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(sendOptions.payload().endDate())
                    .as(":419 mirrors :399 exactly")
                    .isEqualTo("2022-06-30");
        }

        @Test
        @DisplayName("a non-zero severity with any OTHER message number is rejected, start date literal")
        void aNonZeroSeverityWithAnotherMessageNumberIsRejected() {
            when(dateValidationService.validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_REJECTED, "invalid"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequestConfirmedWith("Y")))
                    .withMessage(MSG_START_DATE_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .as(":403 MOVE -1 TO SDTMML - the MONTH component, not the day or the year")
                            .isEqualTo(CURSOR_START_MONTH));
            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("an end date rejection carries the END DATE literal and the END MONTH cursor")
        void anEndDateRejectionCarriesTheEndDateLiteral() {
            when(dateValidationService.validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_ACCEPTED, SEVERITY_ACCEPTED, "ok"));
            when(dateValidationService.validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_REJECTED, "invalid"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequestConfirmedWith("Y")))
                    .withMessage(MSG_END_DATE_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .as(":423 MOVE -1 TO EDTMML - again the MONTH component")
                            .isEqualTo(CURSOR_END_MONTH));
        }

        @Test
        @DisplayName("the START date is validated first, so only it is reported when both are impossible")
        void theStartDateIsValidatedFirst() {
            when(dateValidationService.validate(anyString(), any()))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_REJECTED, "invalid"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequestConfirmedWith("Y")))
                    .as(":388-406 precedes :408-426, and :404 sends, which ends the turn at :580")
                    .withMessage(MSG_START_DATE_INVALID);

            verify(dateValidationService)
                    .validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("only the severity code and the message number are consulted, never the message text")
        void onlyTheSeverityAndMessageNumberAreConsulted() {
            arrangePublish();
            when(dateValidationService.validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_TOLERATED,
                            "THE DATE IS INVALID AND MUST BE REJECTED"));
            when(dateValidationService.validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_TOLERATED,
                            "an entirely different sixty-one character narrative"));

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"));

            assertThat(screen.successHighlight())
                    .as("two outcomes agreeing only on their severity and message number must produce the "
                            + "same decision, because :396 and :399 read nothing else; the message text of "
                            + ":136 is never examined")
                    .isTrue();
        }

        @Test
        @DisplayName("the report name is assigned only AFTER both dates pass, per :433")
        void theReportNameIsAssignedOnlyAfterBothDatesPass() {
            when(dateValidationService.validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_ACCEPTED, SEVERITY_ACCEPTED, "ok"));
            when(dateValidationService.validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD))
                    .thenReturn(validatorResult(SEVERITY_NON_ZERO, MESSAGE_NUMBER_REJECTED, "invalid"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequestConfirmedWith("Y")))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("MOVE 'Custom' TO WS-REPORT-NAME is at :433, after both validations, so a "
                                    + "rejected range never carries a report name into its message")
                            .doesNotContain(REPORT_NAME_CUSTOM));
        }

        @Test
        @DisplayName("an end date EARLIER than the start date is accepted, the source comparing them nowhere")
        void anEndDateEarlierThanTheStartIsAccepted() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "12", "31", "2022", "01", "01", "2022", "Y"));

            assertThat(sendOptions.payload().startDate())
                    .as(":256-433 validates each date independently and never orders the pair; adding an "
                            + "ordering check would reject a range the source submits")
                    .isEqualTo("2022-12-31");
            assertThat(sendOptions.payload().endDate()).isEqualTo("2022-01-01");
        }

        @Test
        @DisplayName("the two thresholds are the source's literals, recorded so neither can drift")
        void theTwoThresholdsAreRecorded() throws ReflectiveOperationException {
            assertThat(declaredConstant("SEVERITY_ACCEPTED"))
                    .as(":396")
                    .isEqualTo(SEVERITY_ACCEPTED);
            assertThat(declaredConstant("SEVERITY_TOLERATED_MESSAGE_NUMBER"))
                    .as(":399 and :419. What the number MEANS in the language environment is Not available "
                            + "from this corpus; the caller treats it as an opaque token, as does this test")
                    .isEqualTo(MESSAGE_NUMBER_TOLERATED);
        }
    }

    // =====================================================================================================
    // 10. The confirmation handshake, app/cbl/CORPT00C.cbl:462-493. It lives INSIDE the submission
    // paragraph, so it is demanded only after the report type has been resolved and the dates computed -
    // which is observable, because the blank re-prompt names the report.
    //
    // The gate is CONFIRMI, PIC X(1) in app/cpy-bms/CORPT00.CPY:114, so at most one character is ever
    // compared. :478 is the combined relation = 'Y' OR 'y', not a case function.
    // =====================================================================================================

    @Nested
    @DisplayName("10. The confirmation handshake :462-493 - four states over a one-character gate")
    class ConfirmationHandshake {

        @Test
        @DisplayName("a blank gate re-prompts naming the MONTHLY report, byte exactly")
        void aBlankGateNamesTheMonthlyReport() {
            // A REDISPLAY, NOT A THROW. All three non-publishing arms - :464 blank, :480 'N' and :484
            // anything else - MOVE 'Y' TO WS-ERR-FLG and PERFORM SEND-TRNRPT-SCREEN, so nothing in the
            // program grades them by severity. A blank gate is the operation ASKING; only a value the
            // one-byte field cannot accept is a malformed request. The two sibling confirmation gates
            // answer 200 on this same condition, so throwing here made one API contradict itself.
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(""));

            assertThat(screen.form().errorMessage())
                    .isEqualTo("Please confirm to print the Monthly report...");
            assertThat(screen.errorFlagOn())
                    .as(":470 MOVE 'Y' TO WS-ERR-FLG")
                    .isTrue();
            assertThat(screen.successHighlight()).isFalse();
            assertThat(screen.cursorField())
                    .as(":470 MOVE -1 TO CONFIRML OF CORPT0AI - unlike the 'N' arm, which sets no cursor")
                    .isEqualTo(CURSOR_CONFIRM);
            assertThat(screen.form().monthlySelected())
                    .as(":464 has no PERFORM INITIALIZE-ALL-FIELDS, so the submitted period SURVIVES and "
                            + "the caller can simply add the confirmation. The 'N' arm does clear it")
                    .isEqualTo("Y");
            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("a blank gate re-prompts naming the YEARLY report, byte exactly")
        void aBlankGateNamesTheYearlyReport() {
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, yearlyRequest(""))
                    .form().errorMessage())
                    .isEqualTo("Please confirm to print the Yearly report...");
        }

        @Test
        @DisplayName("a blank gate re-prompts naming the CUSTOM report, byte exactly")
        void aBlankGateNamesTheCustomReport() {
            arrangeDateValidatorAccepts();

            assertThat(service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith(""))
                    .form().errorMessage())
                    .isEqualTo("Please confirm to print the Custom report...");
        }

        @Test
        @DisplayName("the prompt is composed from the source's two DELIMITED BY SIZE literals")
        void thePromptIsComposedFromTheTwoLiterals() {
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(""))
                    .form().errorMessage())
                    .isEqualTo(MSG_CONFIRM_PREFIX + REPORT_NAME_MONTHLY + MSG_CONFIRM_SUFFIX)
                    .as("WS-REPORT-NAME is PIC X(10) but :468 composes it DELIMITED BY SPACE, so no "
                            + "padding may survive into the message")
                    .doesNotContain("Monthly   ");
        }

        @Test
        @DisplayName("an absent gate re-prompts on the same message, LOW-VALUES being blank too")
        void anAbsentGateRePrompts() {
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(null))
                    .form().errorMessage())
                    .isEqualTo(MSG_CONFIRM_PREFIX + REPORT_NAME_MONTHLY + MSG_CONFIRM_SUFFIX);
        }

        @Test
        @DisplayName("the prompt is only reachable AFTER the dates are computed, which is why it names them")
        void thePromptIsOnlyReachableAfterTheDatesAreComputed() {
            arrangeDateValidatorAccepts();

            assertThat(service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith(""))
                    .form().errorMessage())
                    .isEqualTo(MSG_CONFIRM_PREFIX + REPORT_NAME_CUSTOM + MSG_CONFIRM_SUFFIX);

            // The handshake sits at :464, inside SUBMIT-JOB-TO-INTRDR, which :435 performs only after both
            // date validations have passed. Both calls therefore precede the prompt.
            verify(dateValidationService)
                    .validate("2022-06-01", DateValidationService.MASK_YYYY_MM_DD);
            verify(dateValidationService)
                    .validate("2022-06-30", DateValidationService.MASK_YYYY_MM_DD);
            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("an upper case Y confirms and the submission proceeds")
        void anUpperCaseYConfirms() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.successHighlight()).isTrue();
            verify(sqsTemplate).sendAsync(any());
        }

        @Test
        @DisplayName("a lower case y confirms as well, :478 comparing two distinct literals")
        void aLowerCaseYConfirms() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("y"));

            assertThat(screen.successHighlight())
                    .as("= 'Y' OR 'y' is a combined relation condition, NOT a case function - so no "
                            + "toUpperCase and no locale enters into the comparison")
                    .isTrue();
        }

        @Test
        @DisplayName("an upper case N declines, clearing the form and ending the turn with NO message")
        void anUpperCaseNDeclines() {
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N"));

            assertThat(screen.errorFlagOn())
                    .as(":482 MOVE 'Y' TO WS-ERR-FLG")
                    .isTrue();
            assertThat(screen.successHighlight()).isFalse();
            assertThat(screen.form().errorMessage())
                    .as(":480-483 sets no message at all - a silent abandonment")
                    .isEmpty();
            assertThat(screen.cursorField())
                    .as(":480-483 sets no cursor either")
                    .isNull();
            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("a lower case n declines identically")
        void aLowerCaseNDeclines() {
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("n"));

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.form().errorMessage()).isEmpty();
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("declining blanks the ten input components and leaves the six header ones painted")
        void decliningBlanksTheTenInputComponents() {
            final ReportRequest form =
                    service.submitScreen(AttentionIdentifier.ENTER,
                            mapArea("Y", "Y", "Y", "06", "01", "2022", "06", "30", "2022", "N")).form();

            final List<String> cleared = List.of(form.monthlySelected(), form.yearlySelected(),
                    form.customSelected(), form.startDateMonth(), form.startDateDay(), form.startDateYear(),
                    form.endDateMonth(), form.endDateDay(), form.endDateYear(), form.confirmation());

            assertThat(cleared)
                    .as(":637-645 INITIALIZE ten input fields")
                    .hasSize(CLEARED_FIELD_COUNT)
                    .allSatisfy(component -> assertThat(component).isEmpty());
            assertThat(form.transactionName())
                    .as(":637-645 does not name the header fields, and :558 has already painted them")
                    .isEqualTo(TRANSACTION_ID);
            assertThat(form.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
        }

        @ParameterizedTest(name = "a gate of [{0}] is quoted back as invalid")
        @ValueSource(strings = {"1", "T", "Z", "0", "?", "Xerox"})
        @DisplayName("any other value is quoted back inside DOUBLE quotes, per :485-490")
        void anyOtherValueIsQuotedBack(final String gate) {
            final String expectedEcho = gate.substring(0, 1);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(gate)))
                    .withMessage(MSG_INVALID_CONFIRM_PREFIX + expectedEcho + MSG_INVALID_CONFIRM_SUFFIX)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .as(":492 MOVE -1 TO CONFIRML OF CORPT0AI")
                            .isEqualTo(CURSOR_CONFIRM));
            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("the rejection literal is byte exact, double quotes and three full stops included")
        void theRejectionLiteralIsByteExact() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("1")))
                    .withMessage("\"1\" is not a valid value to confirm...");
        }

        @ParameterizedTest(name = "a gate of [{0}] confirms, the field being PIC X(1)")
        @ValueSource(strings = {"Yes", "YES", "y ", "yes", "Y!"})
        @DisplayName("a longer affirmative confirms, the gate being truncated to one character first")
        void aLongerAffirmativeConfirms(final String gate) {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(gate));

            assertThat(screen.successHighlight())
                    .as("CONFIRMI is PIC X(1) at app/cpy-bms/CORPT00.CPY:114, so the source only ever saw "
                            + "the first character. A preserved quirk, not a liberty")
                    .isTrue();
        }

        @Test
        @DisplayName("a longer value beginning with N declines, on the same one-character truncation")
        void aLongerValueBeginningWithNDeclines() {
            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("No thanks"));

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.form().errorMessage()).isEmpty();
        }

        @Test
        @DisplayName("a gate BEGINNING WITH A SPACE re-prompts; the empty quoted segment is unreachable")
        void aGateBeginningWithASpaceRePrompts() {
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(" Y"))
                    .form().errorMessage())
                    .as("a secondary description of this program expects the WHEN OTHER STRING at :485-490 "
                            + "to emit an empty quoted segment here, because :487 composes CONFIRMI "
                            + "DELIMITED BY SPACE. It cannot: CONFIRMI is PIC X(1), so a value whose first "
                            + "character is a space IS all spaces in that field, and the blank guard at "
                            + ":464 fires first")
                    .isEqualTo(MSG_CONFIRM_PREFIX + REPORT_NAME_MONTHLY + MSG_CONFIRM_SUFFIX)
                    .doesNotContain("\"\" is not a valid value to confirm...");
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("every message-bearing arm of the handshake points the cursor at the confirmation field")
        void everyMessageBearingArmPointsAtTheConfirmationField() {
            // The blank arm carries its cursor on the screen; the invalid arm carries it on the rejection.
            // Both name the same field, which is the property under test.
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("")).cursorField())
                    .isEqualTo(CURSOR_CONFIRM);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("1")))
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo(CURSOR_CONFIRM));
        }
    }

    // =====================================================================================================
    // 11. Publication. app/cbl/CORPT00C.cbl:496-508 walked seventeen eighty-byte cards and wrote every one
    // of them to the extrapartition queue, terminator included; :515-523 is the write itself.
    //
    // The whole deck collapses into ONE typed message. Three properties of the loop survive as assertions:
    // the terminator card was written BEFORE the loop exited (:504 sets the flag, :507 still writes on that
    // same iteration), a blank card terminated it just as the terminator did, and the bound was 1000.
    // =====================================================================================================

    @Nested
    @DisplayName("11. Publication :496-523 - seventeen job cards collapse into ONE FIFO message")
    class Publication {

        @Test
        @DisplayName("exactly ONE message is published per confirmed submission, not one per card")
        void exactlyOneMessageIsPublished() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            verify(sqsTemplate).sendAsync(any());
            assertThat(sendOptions.invoked())
                    .as("one lambda invocation means one publish; seventeen would be seventeen")
                    .containsExactly("queue", "payload", "messageGroupId", "messageDeduplicationId",
                            "headers");
        }

        @Test
        @DisplayName("the configured queue, the payload and the message group all cross the boundary")
        void theConfiguredValuesAllCrossTheBoundary() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.queue())
                    .as("the destination is injected configuration, never a literal in the code")
                    .isEqualTo(QUEUE_NAME);
            assertThat(sendOptions.messageGroupId()).isEqualTo(MESSAGE_GROUP_ID);
            assertThat(sendOptions.payload()).isNotNull();
        }

        @Test
        @DisplayName("a fresh deduplication identifier is set per submission, and no delay is set")
        void aFreshDeduplicationIdentifierIsSetPerSubmission() {
            // Finding H-08, severity High. This asserted that NO deduplication identifier was set, on the
            // reasoning that the source has no idempotency key. The consequence was that the queue's own
            // content-based deduplication became the key, hashing a body of one report name and two dates - so
            // two legitimate submissions of the same period collapsed inside the five-minute window while
            // DISPOSITION(MOD) at app/csd/CARDDEMO.CSD:503 appended both. An explicit identifier per
            // submission is how the queue is told not to deduplicate on content; it is the mechanism that
            // reproduces the append, not a guard the source lacked.
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            final String first = sendOptions.messageDeduplicationId();
            assertThat(first)
                    .as("an identifier is mandatory on every submission: it is what makes the append parity "
                            + "hold regardless of the queue's own deduplication attribute, and against a "
                            + "queue provisioned with that attribute disabled SQS also rejects a send "
                            + "supplying none")
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(MAX_DEDUPLICATION_ID_LENGTH);
            assertThat(first)
                    .as("and it must not be derived from the message, which would reinstate body-keyed "
                            + "collapse under another name")
                    .doesNotContain(REPORT_NAME_MONTHLY)
                    .doesNotContain("2022-06-01")
                    .doesNotContain("2022-06-30");

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.messageDeduplicationId())
                    .as("the SAME period submitted again must carry a DIFFERENT identity, or the queue would "
                            + "discard the second submission exactly as content-based deduplication did")
                    .isNotEqualTo(first);
            assertThat(sendOptions.invoked())
                    .as("no delay: the transient data queue was read immediately and the source sets none")
                    .doesNotContain("delaySeconds", "header");
        }

        @Test
        @DisplayName("the payload carries the report name and both dates - and nothing else at all")
        void thePayloadCarriesTheThreeValues() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(sendOptions.payload())
                    .isEqualTo(new JobSubmissionMessage(REPORT_NAME_MONTHLY, "2022-06-01", "2022-06-30"));
        }

        @Test
        @DisplayName("the payload carries NO deck image, command text or control card - Rule 1 Clause D")
        void thePayloadCarriesNoDeckImage() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            final JobSubmissionMessage payload = sendOptions.payload();
            for (final String component : List.of(payload.reportName(), payload.startDate(),
                    payload.endDate())) {
                assertThat(component)
                        .as("the legacy path submitted an EXECUTABLE deck to an internal reader; the target "
                                + "publishes three typed values, so no card image, statement or command text "
                                + "may appear in the body")
                        .doesNotContain("//")
                        .doesNotContain("/*")
                        .doesNotContain("EXEC")
                        .doesNotContain("JOB")
                        .doesNotContain(" DD ")
                        .doesNotContain("PROC=")
                        .doesNotContain("SYMNAMES")
                        .doesNotContain("DATEPARM")
                        .doesNotContain("\n")
                        .doesNotContain("\r");
            }
            assertThat(payload.reportName())
                    .isIn(REPORT_NAME_MONTHLY, REPORT_NAME_YEARLY, REPORT_NAME_CUSTOM);
            assertThat(payload.startDate()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(payload.endDate()).matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("the message refuses to be built without a report name")
        void theMessageRefusesAnAbsentReportName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobSubmissionMessage(null, "2022-06-01", "2022-06-30"))
                    .withMessageContaining("reportName");
        }

        @Test
        @DisplayName("the message refuses to be built without a start date")
        void theMessageRefusesAnAbsentStartDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobSubmissionMessage(REPORT_NAME_MONTHLY, null, "2022-06-30"))
                    .withMessageContaining("startDate");
        }

        @Test
        @DisplayName("the message refuses to be built without an end date")
        void theMessageRefusesAnAbsentEndDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobSubmissionMessage(REPORT_NAME_MONTHLY, "2022-06-01", null))
                    .withMessageContaining("endDate");
        }

        @Test
        @DisplayName("the deck is SEVENTEEN cards, and the count includes the terminator that WAS written")
        void theDeckIsSeventeenCardsIncludingTheWrittenTerminator() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_CARD_COUNT"))
                    .as(":83-125 declares ten whole-card fillers, two parameter cards, a comment card, the "
                            + "DATEPARM card, the second parameter card, a second comment card and the "
                            + "terminator. :504 sets the loop flag and :507 STILL writes on that same "
                            + "iteration, so the terminator is the seventeenth write, not a dropped one - "
                            + "sixteen would be the finding")
                    .isEqualTo(JOB_CARD_COUNT);
        }

        @Test
        @DisplayName("the terminating card literal is recorded byte exactly, per :125")
        void theTerminatingCardLiteralIsRecorded() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_TERMINATOR_CARD"))
                    .as(":502 tests JCL-RECORD = '/*EOF'; a blank or low-value card terminates identically "
                            + "at :503, and is written on that iteration too")
                    .isEqualTo(JOB_TERMINATOR_CARD);
        }

        @Test
        @DisplayName("the loop bound is a thousand, matching OCCURS 1000 TIMES at :127")
        void theLoopBoundIsAThousand() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_LINE_LIMIT"))
                    .as(":498 UNTIL WS-IDX > 1000; the terminator stops the walk long before it, so the "
                            + "983 unused slots are never touched and no blank message is published")
                    .isEqualTo(JOB_LINE_LIMIT);
        }

        @Test
        @DisplayName("each card was eighty bytes, matching RECORDSIZE(80) of app/csd/CARDDEMO.CSD")
        void eachCardWasEightyBytes() throws ReflectiveOperationException {
            assertThat(declaredConstant("JOB_CARD_LENGTH"))
                    .as("JCL-RECORD PIC X(80) at :79, and LENGTH OF JCL-RECORD at :520")
                    .isEqualTo(JOB_CARD_LENGTH);
        }

        @Test
        @DisplayName("two submissions publish two messages, so the operation is not idempotent by accident")
        void twoSubmissionsPublishTwoMessages() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));
            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            verify(sqsTemplate, times(2)).sendAsync(any());
        }

        @Test
        @DisplayName("nothing is published when the confirmation was declined or unrecognised")
        void nothingIsPublishedWhenTheConfirmationFailed() {
            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N"));
            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest(""));
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("1")));

            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("nothing is published when validation failed, :476 wrapping the whole block")
        void nothingIsPublishedWhenValidationFailed() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(null, null, null, null, null, null)));
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("13", "01", "2022", "06", "30", "2022")));
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            mapArea(null, null, null, null, null, null, null, null, null, "Y")));

            verifyNoInteractions(sqsTemplate, snsTemplate);
        }

        @Test
        @DisplayName("the operator notification accompanies a successful publish, replacing the notify card")
        void theOperatorNotificationAccompaniesASuccessfulPublish() {
            arrangePublish();

            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            verify(snsTemplate).sendNotification(eq(TOPIC), any(), anyString());
        }
    }

    // =====================================================================================================
    // 12. Publish failure, app/cbl/CORPT00C.cbl:525-535. WHEN DFHRESP(NORMAL) is a CONTINUE no-op, retained;
    // WHEN OTHER displays the response and reason codes, sets the literal and puts the cursor on the MONTHLY
    // selector whatever the report type actually was. That last part is a copy-paste defect, preserved.
    // =====================================================================================================

    @Nested
    @DisplayName("12. Publish failure :525-535 - byte-exact literal, cause preserved, monthly cursor")
    class PublishFailure {

        @Test
        @DisplayName("a publish failure surfaces the source's literal byte exactly")
        void aPublishFailureSurfacesTheSourceLiteral() {
            final MessagingOperationFailedException cause =
                    new MessagingOperationFailedException("queue unavailable", QUEUE_LOGICAL_NAME);
            doThrow(cause).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .withMessage(MSG_UNABLE_TO_WRITE_TDQ)
                    .satisfies(failure -> {
                        assertThat(failure.getClass().getName())
                                .as("an I/O failure, not a validation failure: the typed subtype for a "
                                        + "queue write that did not happen")
                                .isEqualTo("com.cardemo.exception.FileAccessException");
                        assertThat(failure.getCause())
                                .as("Rule 1 Clause B - the root cause is preserved, never swallowed")
                                .isSameAs(cause);
                    });
        }

        /**
         * The failure names what failed and what was attempted, and carries no fabricated file status.
         *
         * <p>Three properties, each of which a diagnostic downstream depends on.
         *
         * <p>The <strong>logical</strong> queue name, because {@code app/cbl/CORPT00C.cbl:515-523} writes to
         * {@code QUEUE('JOBS')} and an {@code ERROR} line that named no resource would report a failure
         * without saying what failed - the defect this assertion pins shut. Logical rather than physical:
         * the logical name is a literal in {@code application.yml}, so it cannot carry an account
         * identifier, whereas the physical name and the resolved URL can.
         *
         * <p>The operation {@code WRITEQ TD}, which is the source's own verb rather than the SDK operation
         * name, because the line is read against the COBOL program.
         *
         * <p>And <strong>no</strong> file status. This program declares no {@code FILE-CONTROL} paragraph, no
         * {@code SELECT} and no {@code FD}, so there is nothing to report; supplying one would fabricate an
         * I/O condition. The assertion is on {@code hasIoStatus()} rather than on the expanded status,
         * because the expanded status of an absent status is the placeholder {@code " 032"} - the faithful
         * rendering of an uninitialised two byte field - and it is precisely that placeholder which must not
         * reach a diagnostic as though the queue had returned it.
         */
        @Test
        @DisplayName("the failure names the logical queue and WRITEQ TD, and carries no file status")
        void theFailureNamesTheQueueAndTheOperationButNoStatus() {
            final MessagingOperationFailedException cause =
                    new MessagingOperationFailedException("queue unavailable", QUEUE_LOGICAL_NAME);
            doThrow(cause).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getLogicalFileName())
                                .as("the logical queue name, so the ERROR line names the failing resource")
                                .isEqualTo(QUEUE_LOGICAL_NAME);
                        assertThat(failure.getOperation())
                                .as(":515-523 EXEC CICS WRITEQ TD - the source's verb, not the SDK's")
                                .isEqualTo("WRITEQ TD");
                        assertThat(failure.hasIoStatus())
                                .as("no FILE-CONTROL, no SELECT and no FD in this program, so no COBOL FILE "
                                        + "STATUS exists and none is invented")
                                .isFalse();
                        assertThat(failure.getMessage())
                                .as("the literal is untouched by carrying the resource and the operation")
                                .isEqualTo(MSG_UNABLE_TO_WRITE_TDQ);
                    });
        }

        @Test
        @DisplayName("the failure literal is byte exact, the parenthesised queue name included")
        void theFailureLiteralIsByteExact() throws ReflectiveOperationException {
            assertThat(declaredConstant("MSG_UNABLE_TO_WRITE_TDQ"))
                    .as(":531 MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE")
                    .isEqualTo("Unable to Write TDQ (JOBS)...");
        }

        @Test
        @DisplayName("the response and reason codes reach the failure's context, per :529")
        void theResponseAndReasonCodesReachTheContext() {
            final IllegalStateException cause = new IllegalStateException("client rejected the request");
            doThrow(cause).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .satisfies(failure -> {
                        assertThat(failure.getCause())
                                .as(":529 DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD - the target's "
                                        + "equivalent of the two codes is the classified reason plus the "
                                        + "preserved cause, and both must be reachable from the failure")
                                .isSameAs(cause);
                        assertThat(failure.getCause().getMessage())
                                .isEqualTo("client rejected the request");
                    });
        }

        @Test
        @DisplayName("an asynchronous failure is unwrapped, so the cause is the meaningful throwable")
        void anAsynchronousFailureIsUnwrapped() {
            final MessagingOperationFailedException cause =
                    new MessagingOperationFailedException("send rejected", QUEUE_LOGICAL_NAME);
            doAnswer(invocation -> CompletableFuture.failedFuture(cause))
                    .when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")))
                    .withMessage(MSG_UNABLE_TO_WRITE_TDQ)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("a future that completed exceptionally reports through its cause, which must "
                                    + "not be reported as an ExecutionException")
                            .isSameAs(cause));
        }

        @ParameterizedTest(name = "a {0} submission still points the cursor at the monthly selector")
        @ValueSource(strings = {"Monthly", "Yearly", "Custom"})
        @DisplayName("the cursor goes to MONTHLYL on a queue failure whatever the report type - :533, sic")
        void theCursorGoesToMonthlyWhateverTheReportType(final String reportName) {
            doThrow(new IllegalStateException("unreachable")).when(sqsTemplate).sendAsync(any());
            final ReportRequest request = switch (reportName) {
                case "Monthly" -> monthlyRequest("Y");
                case "Yearly" -> yearlyRequest("Y");
                default -> customRequestConfirmedWith("Y");
            };
            if (REPORT_NAME_CUSTOM.equals(reportName)) {
                arrangeDateValidatorAccepts();
            }

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, request))
                    .withMessage(MSG_UNABLE_TO_WRITE_TDQ);

            // :533 MOVE -1 TO MONTHLYL OF CORPT0AI, unconditionally. The target reproduces the single
            // literal the source emits; the cursor itself is the screen's affair and the failure is the
            // observable outcome, so a queue failure never differentiates by report type.
            verify(sqsTemplate).sendAsync(any());
        }

        @Test
        @DisplayName("a failed publish produces no screen, so no success notice can be mistaken for one")
        void aFailedPublishProducesNoScreen() {
            doThrow(new IllegalStateException("unreachable")).when(sqsTemplate).sendAsync(any());

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")));

            verifyNoInteractions(snsTemplate);
        }
    }

    // =====================================================================================================
    // 13. The success tail, the header and the clear. :445-454 clears the form, composes the notice and
    // sends with the green attribute; :609-628 paints six computed header values; :633-646 blanks ten input
    // components and the working message.
    // =====================================================================================================

    @Nested
    @DisplayName("13. Success tail :445-454, header :609-628 and clear :633-646")
    class SuccessTailHeaderAndClear {

        @Test
        @DisplayName("the notice reads exactly as :449-452 assembles it, for each of the three reports")
        void theNoticeReadsExactlyAsAssembled() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            assertThat(service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"))
                    .form().errorMessage())
                    .isEqualTo("Monthly report submitted for printing ...");
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, yearlyRequest("Y"))
                    .form().errorMessage())
                    .isEqualTo("Yearly report submitted for printing ...");
            assertThat(service.submitScreen(AttentionIdentifier.ENTER, customRequestConfirmedWith("Y"))
                    .form().errorMessage())
                    .isEqualTo("Custom report submitted for printing ...");
        }

        @Test
        @DisplayName("the notice suffix is byte exact, its space before the ellipsis included")
        void theNoticeSuffixIsByteExact() {
            arrangePublish();

            assertThat(service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"))
                    .form().errorMessage())
                    .isEqualTo(REPORT_NAME_MONTHLY + MSG_SUBMITTED_SUFFIX)
                    .endsWith(" ...");
        }

        @Test
        @DisplayName("the form is cleared BEFORE the notice is composed, so the notice survives the clear")
        void theFormIsClearedBeforeTheNoticeIsComposed() {
            arrangePublish();

            final ReportRequest form =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")).form();

            assertThat(form.monthlySelected())
                    .as(":447 performs INITIALIZE-ALL-FIELDS first")
                    .isEmpty();
            assertThat(form.confirmation()).isEmpty();
            assertThat(form.errorMessage())
                    .as(":449-452 composes into WS-MESSAGE afterwards, and :560 moves it to the map")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("the success screen carries the green attribute and no error flag")
        void theSuccessScreenCarriesTheGreenAttribute() {
            arrangePublish();

            final ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(screen.successHighlight())
                    .as(":448 MOVE DFHGREEN TO ERRMSGC - a success attribute, not an error one")
                    .isTrue();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.cursorField()).isEqualTo(CURSOR_MONTHLY);
            assertThat(screen.navigationTarget()).isNull();
        }

        @Test
        @DisplayName("the declined screen and the success screen are distinguishable by their two flags")
        void theDeclinedAndSuccessScreensAreDistinguishable() {
            arrangePublish();

            final ReportSubmissionScreen declined =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("N"));
            final ReportSubmissionScreen succeeded =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y"));

            assertThat(declined.errorFlagOn()).isTrue();
            assertThat(declined.successHighlight()).isFalse();
            assertThat(succeeded.errorFlagOn()).isFalse();
            assertThat(succeeded.successHighlight()).isTrue();
        }

        @Test
        @DisplayName("the submitted header components are overwritten, never echoed back")
        void theSubmittedHeaderComponentsAreOverwritten() {
            arrangePublish();

            final ReportRequest form =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")).form();

            assertThat(form.transactionName())
                    .as(":619 MOVE WS-TRANID TO TRNNAMEO")
                    .isEqualTo(TRANSACTION_ID)
                    .isNotEqualTo("ZZZZ");
            assertThat(form.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(form.title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(form.title02()).isEqualTo(SCREEN_TITLE_02);
            assertThat(form.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(form.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @Test
        @DisplayName("both titles are byte exact at their declared PIC X(40) width, padding included")
        void bothTitlesAreByteExactAtFortyCharacters() {
            final ReportRequest form = service.openReportScreen().form();

            assertThat(form.title01()).hasSize(40);
            assertThat(form.title02()).hasSize(40);
        }

        @Test
        @DisplayName("the header date and time come from ONE clock reading per paragraph, per :611")
        void theHeaderComesFromOneClockReadingPerParagraph() {
            final AdvancingClock clock = new AdvancingClock(
                    Instant.parse("2022-06-10T23:59:59Z"), Duration.ofSeconds(2L),
                    FixedClockProvider.CANONICAL_ZONE);

            final ReportRequest form = serviceWithClock(clock).openReportScreen().form();

            assertThat(form.currentDate())
                    .as("one reading feeds both renderings, so the date cannot belong to one day and the "
                            + "time to the next")
                    .isEqualTo("06/10/22");
            assertThat(form.currentTime()).isEqualTo("23:59:59");
            assertThat(clock.reads()).isEqualTo(1);
        }

        @Test
        @DisplayName("no message the service can emit overflows the narrower screen field")
        void noMessageOverflowsTheScreenField() {
            final List<String> everyMessage = List.of(
                    MSG_INVALID_KEY, MSG_SELECT_REPORT_TYPE,
                    MSG_START_MONTH_EMPTY, MSG_START_DAY_EMPTY, MSG_START_YEAR_EMPTY,
                    MSG_END_MONTH_EMPTY, MSG_END_DAY_EMPTY, MSG_END_YEAR_EMPTY,
                    MSG_START_MONTH_INVALID, MSG_START_DAY_INVALID, MSG_START_YEAR_INVALID,
                    MSG_END_MONTH_INVALID, MSG_END_DAY_INVALID, MSG_END_YEAR_INVALID,
                    MSG_START_DATE_INVALID, MSG_END_DATE_INVALID, MSG_UNABLE_TO_WRITE_TDQ,
                    MSG_CONFIRM_PREFIX + REPORT_NAME_MONTHLY + MSG_CONFIRM_SUFFIX,
                    MSG_CONFIRM_PREFIX + REPORT_NAME_YEARLY + MSG_CONFIRM_SUFFIX,
                    MSG_CONFIRM_PREFIX + REPORT_NAME_CUSTOM + MSG_CONFIRM_SUFFIX,
                    MSG_INVALID_CONFIRM_PREFIX + "?" + MSG_INVALID_CONFIRM_SUFFIX,
                    REPORT_NAME_MONTHLY + MSG_SUBMITTED_SUFFIX,
                    REPORT_NAME_YEARLY + MSG_SUBMITTED_SUFFIX,
                    REPORT_NAME_CUSTOM + MSG_SUBMITTED_SUFFIX);

            assertThat(everyMessage)
                    .as("WS-MESSAGE is PIC X(80) and ERRMSGI is the narrower PIC X(78), so a message longer "
                            + "than 78 would be silently truncated on the way to :560. Every literal this "
                            + "service can emit is shorter than both, which is why no truncation occurs")
                    .allSatisfy(message -> assertThat(message.length())
                            .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH)
                            .isLessThanOrEqualTo(WORKING_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("an over-long submitted confirmation is TRUNCATED, so it cannot overflow the message")
        void anOverLongConfirmationIsTruncated() {
            final String overLong = "?".repeat(4_000);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            monthlyRequest(overLong)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .as("CONFIRMI is PIC X(1), so :487 could only ever echo one character; "
                                        + "truncating at the field width is what keeps WS-MESSAGE inside its "
                                        + "eighty bytes rather than widening the field to accommodate input")
                                .isEqualTo("\"?\" is not a valid value to confirm...");
                        assertThat(failure.getMessage().length())
                                .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
                    });
        }

        @Test
        @DisplayName("an over-long submitted component cannot reach the message either")
        void anOverLongComponentCannotReachTheMessage() {
            final String overLong = "9".repeat(4_000);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(overLong, "01", "2022", "06", "30", "2022")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .as("the range literals are fixed text and never quote the offending value, "
                                        + "so no submitted byte can lengthen a message")
                                .isEqualTo(MSG_START_MONTH_INVALID);
                        assertThat(failure.getMessage().length())
                                .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
                    });
        }

        @Test
        @DisplayName("no submitted value leaks into a screen outcome except through its own component")
        void noSubmittedValueLeaksIntoAScreenOutcome() {
            arrangePublish();

            final ReportRequest form =
                    service.submitScreen(AttentionIdentifier.ENTER, monthlyRequest("Y")).form();

            for (final RecordComponent component : ReportRequest.class.getRecordComponents()) {
                assertThat(form.toString())
                        .as("the payload's rendering is overridden precisely so no component value reaches a "
                                + "log or an assertion message")
                        .doesNotContain("submitted-title-one")
                        .doesNotContain("submitted-message");
                assertThat(component.getName()).isNotEmpty();
            }
        }
    }

    // =====================================================================================================
    // 14. Hostile input. Rule 1 Clause A: treat inputs as untrusted; Clause B: handle null and empty
    // explicitly. Nothing here may reach the queue, and nothing may raise anything but a typed failure.
    // =====================================================================================================

    @Nested
    @DisplayName("14. Hostile input - every malformed component produces a typed failure, never a leak")
    class HostileInput {

        @ParameterizedTest(name = "a start month of [{0}] is refused by the range guard")
        @ValueSource(strings = {"13", "99", "1e3", "999999999999999999999"})
        @DisplayName("a month that normalises above twelve is refused, and refused as a TYPED failure")
        void aMonthAboveTwelveIsRefusedTyped(final String month) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest(month, "01", "2022", "06", "30", "2022")))
                    .as("an arbitrarily long or non-numeric submission must not escape as an untyped "
                            + "throwable, and must not reach the queue")
                    .withMessage(MSG_START_MONTH_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_START_MONTH));
            verifyNoInteractions(dateValidationService, sqsTemplate, snsTemplate);
        }

        @ParameterizedTest(name = "a start month of [{0}] normalises and reaches the validator as [{1}]")
        @CsvSource({"AB, 2022-00-01", "-1, 2022-01-01", "6.9, 2022-06-01"})
        @DisplayName("a month the guards let through is normalised, never propagated as submitted")
        void aMonthTheGuardsLetThroughIsNormalised(final String month, final String assembledStart) {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    customRequest(month, "01", "2022", "06", "30", "2022"));

            verify(dateValidationService)
                    .validate(assembledStart, DateValidationService.MASK_YYYY_MM_DD);
            assertThat(sendOptions.payload().startDate()).isEqualTo(assembledStart);
        }

        @ParameterizedTest(name = "a start day of [{0}] is refused by the range guard")
        @ValueSource(strings = {"32", "99", "9999"})
        @DisplayName("a day that normalises above thirty-one is refused, and refused as a TYPED failure")
        void aDayAboveThirtyOneIsRefusedTyped(final String day) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            customRequest("06", day, "2022", "06", "30", "2022")))
                    .withMessage(MSG_START_DAY_INVALID)
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(CURSOR_START_DAY));
            verifyNoInteractions(dateValidationService, sqsTemplate);
        }

        @ParameterizedTest(name = "a start day of [{0}] normalises and reaches the validator as [{1}]")
        @CsvSource({"XY, 2022-06-00", "0.5, 2022-06-00", "7, 2022-06-07"})
        @DisplayName("a day the guards let through is normalised, never propagated as submitted")
        void aDayTheGuardsLetThroughIsNormalised(final String day, final String assembledStart) {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    customRequest("06", day, "2022", "06", "30", "2022"));

            verify(dateValidationService)
                    .validate(assembledStart, DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("a request whose every component is absent still produces exactly one typed failure")
        void aWhollyAbsentRequestProducesOneTypedFailure() {
            final ReportRequest empty = new ReportRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, empty))
                    .withMessage(MSG_SELECT_REPORT_TYPE);
            verifyNoInteractions(sqsTemplate, snsTemplate, dateValidationService);
        }

        @Test
        @DisplayName("a request whose every component is blank behaves identically to an absent one")
        void aWhollyBlankRequestBehavesIdentically() {
            final ReportRequest blank = new ReportRequest("", "", "", "", "", "",
                    "", "", "", "", "", "", "", "", "", "", "");

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, blank))
                    .withMessage(MSG_SELECT_REPORT_TYPE);
        }

        @Test
        @DisplayName("all three selectors supplied at once is not an error, monthly simply winning")
        void allThreeSelectorsAtOnceIsNotAnError() {
            arrangePublish();

            final ReportSubmissionScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea("Y", "Y", "Y", null, null, null, null, null, null, "Y"));

            assertThat(screen.successHighlight()).isTrue();
            assertThat(sendOptions.payload().reportName()).isEqualTo(REPORT_NAME_MONTHLY);
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("a component carrying control characters is normalised to zeros, not propagated")
        void aComponentCarryingControlCharactersIsNormalised() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "\t\n", "01", "2022", "06", "30", "2022", "Y"));

            verify(dateValidationService)
                    .validate("2022-00-01", DateValidationService.MASK_YYYY_MM_DD);
            assertThat(sendOptions.payload().startDate())
                    .as("no control character may survive into the published body")
                    .doesNotContain("\t")
                    .doesNotContain("\n");
        }

        @Test
        @DisplayName("a component carrying a quote or a separator cannot reach the published body")
        void aComponentCarryingAQuoteCannotReachTheBody() {
            arrangePublish();
            arrangeDateValidatorAccepts();

            service.submitScreen(AttentionIdentifier.ENTER,
                    mapArea(null, null, "Y", "0'6", "01", "2022", "06", "30", "2022", "Y"));

            assertThat(sendOptions.payload().startDate())
                    .as("normalisation keeps only digits, so an injected quote cannot cross the boundary")
                    .isEqualTo("2022-06-01")
                    .doesNotContain("'");
        }
    }

    // =====================================================================================================
    // Test doubles. Hand written rather than mocked wherever a mock would need leniency or could not observe
    // what the assertion needs, which keeps every Mockito stub in this class strictly checked.
    // =====================================================================================================

    /**
     * A provider that yields no tracer, tracing being an optional collaborator the production code resolves
     * with {@code getIfAvailable()} and tolerates as absent.
     *
     * <p>A plain implementation rather than a lenient mock: most tests here never reach the publish path, so
     * an eager stub would be reported as unnecessary under {@link Strictness#STRICT_STUBS} and a lenient one
     * would weaken the class's stubbing discipline for no gain. The behaviour with a real tracer present is
     * asserted by the sibling integration-seam suite.
     */
    private static final class NoTracerProvider implements ObjectProvider<Tracer> {

        @Override
        public Tracer getIfAvailable() {
            return null;
        }
    }

    /**
     * A recording {@link SqsSendOptions}: fluent, so the production chain works unchanged, and total, so
     * nothing the lambda sets can go unobserved - including the options it deliberately leaves alone.
     */
    private static final class RecordingSendOptions implements SqsSendOptions<JobSubmissionMessage> {

        /** Every option method invoked, in order, so an unexpected option is visible. */
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
     * A clock that advances on every read and counts its reads, used to prove that each period comes from a
     * <em>single</em> reading. app/cbl/CORPT00C.cbl:215 and :241 each perform one
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}, so a period cannot straddle a boundary; a
     * second read would let it.
     *
     * <p>It is still fully deterministic - the start instant and the step are both supplied - so it is a
     * fixed clock with a known trajectory rather than an ambient one.
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
         * @param start the instant the first read returns
         * @param step  how far each read advances the next
         * @param zone  the zone to report
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
