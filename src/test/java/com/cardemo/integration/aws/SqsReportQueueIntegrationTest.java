/*
 ******************************************************************
 * Program     : SqsReportQueueIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 AWS integration test (Failsafe tier)
 * Function    : Verifies the FIFO queue carddemo-report-jobs and the typed report message replacing the
 *               17-card embedded JCL deck.
 * Source      : app/csd/CARDDEMO.CSD:499-505 @ 7756d89 (DEFINE TDQUEUE(JOBS) RECORDSIZE(80)
 *               RECORDFORMAT(FIXED) DISPOSITION(MOD))
 * Source      : app/csd/CARDDEMO.CSD:409-410 @ 7756d89 (DEFINE TRANSACTION(CR00) PROGRAM(CORPT00C))
 * Source      : app/cbl/CORPT00C.cbl:79-127 @ 7756d89 (17-card job deck; /*EOF at :125)
 * Source      : app/cbl/CORPT00C.cbl:496-508 @ 7756d89 (submission loop; terminator written at :507)
 * Source      : app/cbl/CORPT00C.cbl:515-535 @ 7756d89 (WIRTE-JOBSUB-TDQ, WRITEQ TD :517-523, RESP/REAS
 *               :529, failure literal :531)
 * Source      : app/cbl/CORPT00C.cbl:212-255 @ 7756d89 (monthly = FULL calendar month; yearly = Jan 1 to
 *               Dec 31)
 * Replaces    : EXEC CICS WRITEQ TD QUEUE('JOBS') and the JES2 internal reader
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.report.ReportSubmissionService.AttentionIdentifier;
import com.cardemo.service.report.ReportSubmissionService.JobSubmissionMessage;
import com.cardemo.service.report.ReportSubmissionService.ReportSubmissionScreen;
import com.cardemo.service.shared.DateValidationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.micrometer.tracing.Tracer;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDeletedRecentlyException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Verifies the first-in-first-out queue that replaces the CICS extrapartition transient data queue, and the
 * typed report message that replaces the seventeen-card job deck embedded in the report submission program.
 *
 * <h2>1. What it does</h2>
 *
 * <p>One legacy construct is under test here and only one: {@code DEFINE TDQUEUE(JOBS)} at
 * {@code app/csd/CARDDEMO.CSD:499-505}, together with the program that writes to it,
 * {@code DEFINE TRANSACTION(CR00) ... PROGRAM(CORPT00C)} at {@code app/csd/CARDDEMO.CSD:409-410}. Sibling
 * classes in this package own the other cloud surfaces - buckets, generation-key geometry, correlation
 * propagation, and health and metrics - and nothing here overlaps them.
 *
 * <p>The queue definition reads, verbatim at the anchor commit:
 *
 * <pre>
 * DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)
 * DESCRIPTION(SUBMIT JOBS FROM CICS)
 *        TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)
 *        OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)
 *        RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)
 * </pre>
 *
 * <p>Two operands in that definition decide the whole design of the target, and each is asserted below.
 *
 * <dl>
 *   <dt>{@code DISPOSITION(MOD)} is why the queue is FIFO and not standard</dt>
 *   <dd>The legacy queue was a strictly sequential append to a single stream, written one card at a time by
 *       the loop at {@code app/cbl/CORPT00C.cbl:496-508}. A standard queue reproduces the transport and
 *       loses the ordering. So the target queue must declare itself first-in-first-out, and ordering
 *       <em>within one message group</em> must be demonstrable rather than assumed - which is what
 *       {@link TheFifoQueueContract#threeMessagesUnderOneGroupArriveInSendOrder()} does with three messages
 *       in a known order.</dd>
 *   <dt>{@code RECORDSIZE(80) RECORDFORMAT(FIXED)} fixes the message's CONTENT, not its length</dt>
 *   <dd>Transformation rule 10 of the plan states that the eighty-byte fixed record <em>becomes</em> a typed
 *       JSON message. <strong>This class therefore deliberately does NOT assert that the message body is
 *       eighty bytes long, and the omission is not a gap.</strong> A JSON object carrying three named
 *       fields is not an eighty-byte card and could not be without reintroducing the very fixed-width
 *       framing the migration removed at this boundary. What is asserted instead is <em>content
 *       equivalence</em>: every value the three variable cards carried is reconstructible from the typed
 *       message, and each reconstruction is exactly eighty bytes. See
 *       {@link TheTypedReportMessage#theTypedMessageCarriesEverythingTheEightyByteCardsCarried()}.</dd>
 * </dl>
 *
 * <p>Six properties of the frozen source shape the assertions, and each was read directly rather than taken
 * from a secondary description.
 *
 * <ol>
 *   <li><strong>The deck is SEVENTEEN cards, not eighteen.</strong> {@code app/cbl/CORPT00C.cbl:79-127}:
 *       {@code 05 JCL-RECORD PIC X(80)} at {@code :79}, {@code 01 JOB-DATA.} at {@code :81},
 *       {@code 02 JOB-DATA-1.} at {@code :82}, and inside it fourteen plain {@code FILLER PIC X(80)} cards
 *       plus three group items - {@code FILLER-1} at {@code :103}, {@code FILLER-2} at {@code :108} and
 *       {@code FILLER-3} at {@code :117} - each group also totalling eighty bytes. Fourteen plus three is
 *       seventeen, so the deck is 1,360 bytes. {@code 02 JOB-DATA-2 REDEFINES JOB-DATA-1.} at {@code :126}
 *       overlays it with {@code 05 JOB-LINES OCCURS 1000 TIMES PIC X(80).} at {@code :127}.
 *       The count is read off {@code :79-127} directly, which is where a secondary description is easy to
 *       miscount, and the production service encodes seventeen.</li>
 *   <li><strong>Each date was injected TWICE, and collapses to ONE field.</strong>
 *       {@code PARM-START-DATE-1 PIC X(10)} at {@code :106} sits on the sort-symbol card and
 *       {@code PARM-START-DATE-2 PIC X(10)} at {@code :118} on the date-parameter card;
 *       {@code PARM-END-DATE-1} at {@code :111} and {@code PARM-END-DATE-2} at {@code :120} likewise. Every
 *       branch moves each date to its <em>pair</em> of targets - {@code :220-221}, {@code :235-236},
 *       {@code :247-248}, {@code :252-253}, {@code :429-430} and {@code :431-432} - so four injection points
 *       carry two values. The typed message carries each value once and the consumer derives both uses from
 *       it, which is asserted by
 *       {@link TheTypedReportMessage#eachDateAppearsOnceThoughTheDeckInjectedItTwice()}.</li>
 *   <li><strong>The terminating card IS written before the loop exits.</strong> {@code :496} sets the
 *       loop flag off; {@code :498-499} is
 *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 1000 OR END-LOOP-YES OR ERR-FLG-ON};
 *       {@code :501} moves the card into the record; {@code :502-504} sets the flag when the card is
 *       {@code /*EOF} or blank or low values - and then {@code :507} performs the write
 *       <strong>in that same iteration</strong>, because {@code PERFORM VARYING ... UNTIL} re-tests only at
 *       the top of the next one. A naive reading gets this backwards and concludes sixteen cards are
 *       written. Seventeen are, and the seventeenth is the terminator. The collapse to one typed message
 *       must therefore lose nothing, which is what the content-equivalence assertions establish.</li>
 *   <li><strong>The paragraph name is misspelt and the misspelling is preserved.</strong>
 *       {@code app/cbl/CORPT00C.cbl:515} declares {@code WIRTE-JOBSUB-TDQ.} with the {@code RI}
 *       transposed, and {@code :507} performs it under the same spelling. It is never corrected in a
 *       citation, because a citation has to name the label the source actually declares; a tidied label looks
 *       better and no longer resolves.</li>
 *   <li><strong>The write spans {@code :517-523}, and the failure arm has two byte-exact contracts.</strong>
 *       The verb is {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)
 *       LENGTH(LENGTH OF JCL-RECORD) RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC.} - six lines, not four.
 *       {@code :525-535} then evaluates the response. On anything but a normal response, {@code :529} is
 *       {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}, and COBOL concatenates {@code DISPLAY}
 *       operands with <strong>no separator</strong>, so the emitted text reads
 *       {@code RESP:}<em>resp</em>{@code REAS:}<em>reas</em>; {@code :531} moves the literal
 *       {@code Unable to Write TDQ (JOBS)...} - three trailing periods, parenthesised queue name - into the
 *       message; and {@code :533} parks the cursor on the <em>monthly selector</em>, not on the
 *       confirmation field the user was last in. There are exactly two {@code DISPLAY} statements in the
 *       whole program, {@code :210} and {@code :529}, so that one warning line is the entire legacy
 *       instrumentation of this path. Both contracts are asserted with exact equality rather than a
 *       containment check by {@link TheDeterministicGroupIdAndTheFailurePath}.</li>
 *   <li><strong>The monthly range is the FULL current calendar month.</strong>
 *       {@code app/cbl/CORPT00C.cbl:212-238}: {@code :217-219} set the start to the current year, the
 *       current month and the literal {@code '01'}; {@code :223} moves 1 into the day field, discarding
 *       today's day; {@code :224} adds one to the month; {@code :225-228} roll the year when the month
 *       passes twelve; {@code :229-230} compute
 *       {@code FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)}, the first of the
 *       <em>next</em> month less one day, which is the <strong>last day of the current month</strong>; and
 *       {@code :232-234} read the already-mutated fields back out. A month-to-date reading of the same
 *       paragraph would agree with the source on exactly one day per month and diverge silently on the rest,
 *       which is why the end date is derived as the last day of the start date's month - exactly what the
 *       production service does. Asserted for a 31-day month, a 30-day month, a 28-day February, a 29-day leap
 *       February and December - the only branch that increments the year - all off the pinned clock, in
 *       {@link ThePeriodResolution}.</li>
 * </ol>
 *
 * <p>Two deliberate divergences from the source are recorded here rather than left for a reviewer to notice.
 * First, the legacy definition carries {@code ERROROPTION(IGNORE)}, so a failed queue write was ignored by
 * the transaction monitor; <strong>the Java side deliberately does not ignore it</strong>. A failed publish
 * raises a typed exception carrying the source's own literal and the underlying failure as its cause, which
 * is what Rule 1 Clause B requires and what {@code ERROROPTION(IGNORE)} would forbid. Second, the consumer
 * side - an inbound listener replacing the JES2 internal reader, mapping the body onto batch job parameters
 * - belongs to the batch integration leaf and is owned elsewhere. <strong>Nothing here asserts batch
 * execution</strong>, and nothing here imports from that package or from the persistence leaf, in either
 * direction. Publication is the whole subject.
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build and run everything with {@code ./mvnw -B -ntp clean verify}. <strong>This class is collected by
 * Failsafe, never by Surefire.</strong> The root build binds {@code maven-failsafe-plugin} to
 * {@code **}{@code /integration/}{@code **}{@code /*Test.java} and the matching end-to-end tree at
 * {@code integration-test} and {@code verify}, even though these classes keep the {@code Test} suffix, while
 * {@code maven-surefire-plugin} includes {@code **}{@code /*Test.java} but <em>excludes</em> both trees. A
 * class moved to {@code com/cardemo/integration}, to {@code com/cardemo}, to {@code com} or to the test
 * source root matches <strong>neither</strong> include set and is therefore collected by neither plugin: it
 * silently never runs, both plugins report success and the build stays green. That is the worst failure mode
 * available here, so this file's path and name must not be changed and no sub-package may be introduced
 * beneath it. After any build, confirm the Failsafe report names this class; a Surefire report naming it, or
 * neither naming it, means the class has been mis-located and must be moved back.
 *
 * <p><strong>A reachable container runtime is a hard prerequisite.</strong> The harness starts a
 * PostgreSQL 16 container and a LocalStack container eagerly, and there is no in-memory substitute: an
 * in-memory queue would not exercise first-in-first-out ordering or content-based deduplication, which are
 * the two properties this class exists to prove. Where no daemon or socket is reachable the correct report
 * is that the gate is <em>blocked</em>, never an untested pass. The prerequisites are a working daemon with
 * both images available plus host {@code java}, {@code javac} and Maven on the path, which is what lets
 * {@code ./mvnw} run directly.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>The {@code test} profile is active, contributed by the harness. Every address, port and credential
 * comes from the running containers at a precedence above every profile file, so <strong>no endpoint, host,
 * port, connection string or credential appears in this source</strong> - the emulator is referred to only
 * as the endpoint the container library injects. Container images are pinned by the harness, never floating.
 *
 * <ul>
 *   <li><strong>Time is pinned.</strong> The harness publishes a fixed clock as the context's primary time
 *       source and exposes the same instance to subclasses. This class depends on that more than any other
 *       in the package, because the report message carries dates and the monthly range depends on how long
 *       the current month is. The wall clock is never read: a month-length case is exercised by building a
 *       clock fixed at one of the harness's probe instants.</li>
 *   <li><strong>The queue is named by configuration, not by a literal.</strong> The physical name comes from
 *       {@code carddemo.aws.sqs.report-queue}, which indirects to an environment variable and carries the
 *       suffix the queue service requires; the logical name comes from
 *       {@code carddemo.aws.sqs.report-queue-logical-name} and is {@code carddemo-report-jobs}; and the
 *       message group comes from {@code carddemo.aws.sqs.report-message-group-id}. All three keys were read
 *       from the configuration that binds them rather than guessed, and <strong>no divergence was
 *       found</strong> between them and the migration's stated contract.</li>
 *   <li><strong>Deduplication is per-submission and explicit; content-based deduplication is not the
 *       production choice.</strong> Finding H-08, severity High. This paragraph previously said the reverse -
 *       that startup required content-based deduplication and that the publisher deliberately set no
 *       identifier, because the source has no idempotency key. The conclusion did not follow from the
 *       premise: a report body is one report name and two dates, so hashing it collapses two legitimate
 *       submissions of the same period, while {@code DEFINE TDQUEUE(JOBS) ... DISPOSITION(MOD)} appended
 *       both. The publishing service therefore mints a fresh {@code MessageDeduplicationId} per submission,
 *       which is how the queue is told <em>not</em> to key on the body, and an explicit identifier takes
 *       precedence over the hash regardless of the queue's own attribute. Startup reads that attribute and
 *       reports it but does not refuse to start over it, because it is mutable and the send path does not
 *       depend on it; provisioning is what sets it, and this class asserts the provisioned value.</li>
 *   <li><strong>Every queue this class publishes to for delivery assertions is its own.</strong> Each such
 *       test provisions a first-in-first-out queue whose name is scoped to this class and to the test's
 *       role, and the harness deletes it afterwards. That is not merely tidiness: content-based
 *       deduplication is keyed on the body for five minutes, so two tests that legitimately produce the
 *       same period would suppress each other on a shared queue, and a rerun inside that window would
 *       suppress itself. Deleting the queue clears that state, which is what makes this class pass alone,
 *       in any order and twice in succession. The one test that exercises the injected production bean
 *       against the shared application queue asserts the publisher's own outcome rather than delivery, for
 *       exactly the same reason.</li>
 *   <li><strong>The developer provisioning script is not a precondition.</strong>
 *       {@code localstack-init/init-aws.sh} provisions the compose topology, is owned elsewhere, and is
 *       neither read nor written here. This tier self-provisions.</li>
 * </ul>
 *
 * <p><strong>Least privilege.</strong> Every call reaches the emulator container and nothing else. There is
 * no live account, no live credential and no live endpoint on any path from here, and none could be added
 * without failing the application's own startup guard. A live endpoint or credential reaching a code path
 * in this tier is forbidden outright. No secret, token, signing key, password or personally
 * identifiable value appears in this file, in its comments or in any assertion message.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code Could not find a valid Docker environment}</dt>
 *   <dd>No reachable container runtime. Start one; do not skip the class. A green build obtained by having
 *       no daemon is worse than a red one.</dd>
 *   <dt>A dependency under {@code org.testcontainers} fails to resolve</dt>
 *   <dd>The container-library 2.0.3 coordinate trap, the build hazard of this migration most likely to
 *       stop a build outright. Only the prefixed module coordinates exist at that release -
 *       {@code testcontainers}, {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. The bare {@code postgresql}, {@code localstack} and
 *       {@code junit-jupiter} identifiers under that group <strong>do not exist</strong> there and fail
 *       resolution outright. Compounding it, the framework parent already imports that library's bill of
 *       materials at a 1.x version, so importing a second one yields ordering-dependent resolution that may
 *       silently select 1.x. The remedy is two-part and <em>both</em> parts are required: override the
 *       managed version through the version property rather than importing a second bill of materials, and
 *       use only prefixed module coordinates. Overriding without renaming resolves artefacts that do not
 *       exist; renaming without overriding resolves the wrong version. The root build already does both.
 *       It is owned elsewhere, so the remediation for a regression is to restore those two settings there -
 *       <strong>never</strong> to add a dependency from this tier.</dd>
 *   <dt>The build fails on a warning that looks harmless</dt>
 *   <dd>The compiler runs at release 25 with {@code -Xlint:all}, {@code -Werror} and fail-on-warning, so a
 *       single unused import, raw type, unchecked cast or deprecation fails the build. That is deliberate
 *       and must not be relaxed.</dd>
 *   <dt>A queue cannot be created and the failure names recent deletion</dt>
 *   <dd>A first-in-first-out queue name cannot always be reused immediately after the queue is deleted.
 *       {@link #provisionScopedFifoQueue(String)} handles that boundary explicitly, with a bounded number
 *       of attempts and a bounded pause between them, and fails with a message naming the cause when the
 *       budget is exhausted. It is never an open-ended sleep and never an unbounded poll.</dd>
 *   <dt>An ordering assertion fails, or a message never arrives</dt>
 *   <dd>Two causes, and they look alike. A non-deterministic message group identifier places each
 *       submission in its own group, which forfeits ordering between submissions and does so
 *       unreproducibly: <strong>High</strong>, and
 *       {@link TheDeterministicGroupIdAndTheFailurePath#theMessageGroupIdIsDeterministicAcrossSubmissions()}
 *       exists to catch it. Otherwise the message was deduplicated because an identical body was published
 *       to the same queue inside the deduplication window; provision a queue for the test rather than
 *       sharing one.</dd>
 *   <dt>Context startup aborts naming the token signing key</dt>
 *   <dd>The cause is not in this file. The base configuration maps
 *       the signing key to an environment variable with no default so that nothing can boot with a key an
 *       attacker already knows, and the {@code test} profile - owned elsewhere - supplies no test value, so
 *       the context refresh aborts before any test runs. The fix belongs with the owner of that file: a
 *       recognisably non-production test value in the {@code test} profile. Until it lands the harness
 *       registers one itself. <strong>It is reported here, never patched from here.</strong></dd>
 * </dl>
 *
 * <h2>Information that is Not available</h2>
 *
 * <p>Rule 1 Clause F requires missing information to be stated plainly rather than filled with an
 * invention. Three things are missing.
 *
 * <ol>
 *   <li><strong>The boundary-parity expected-output baseline is Not available.</strong> An exhaustive
 *       search across expected, baseline, golden, system-output and per-dataset name patterns returned only
 *       dataset <em>definition</em> job control and zero captured data. <em>What is needed:</em> a captured
 *       430-byte reject dataset together with the resulting transaction, account and category-balance
 *       images from a real posting execution at a known input state. Until those exist,
 *       <strong>this class creates no baseline file and fabricates no expected bytes</strong>. A baseline
 *       produced by running the Java implementation and then asserting against it is circular and is
 *       forbidden: it would prove only that the code agrees with itself.</li>
 *   <li><strong>A file-unavailable exercise is Not available.</strong> A census across the COBOL corpus
 *       found the file status for an unavailable file zero times and the corresponding not-open response
 *       code zero times, so the legacy corpus never takes that path. No test for it is fabricated here.
 *       <em>What is needed:</em> a legacy program that handles that status, or a stated requirement that
 *       the Java tier introduce the path as new behaviour.</li>
 *   <li><strong>A source locator for the CICS transaction-identifier field is Not available.</strong>
 *       A repository-wide search for it returns zero
 *       occurrences; the complete exchange-interface-block census in the corpus is the communication-area
 *       length field and the attention-identifier field only. It is supplied by the transaction monitor
 *       rather than by this corpus, so <strong>no {@code app/...} line reference for it may be
 *       fabricated</strong>. <em>What is needed:</em> nothing from this repository - the citation belongs to
 *       the vendor's own copybook, which is not part of it. The correlation identifier that replaces it as
 *       the thread of request identity is owned by a sibling class in this package.</li>
 * </ol>
 *
 * <p>One further finding is recorded because a reader of this file will meet it. <strong>Medium:</strong> a
 * prior record cites a coverage-plugin release later than the pinned one. The pinned coordinate governs and
 * the divergence is recorded rather than resolved unilaterally; the root build honours the pin and advances
 * only the bytecode reader it loads.
 */
@DisplayName("SQS FIFO report queue - the DEFINE TDQUEUE(JOBS) bridge and the typed report message")
class SqsReportQueueIntegrationTest extends AbstractAwsIntegrationTest {

    /**
     * Sole constructor, invoked by the test framework.
     *
     * <p>Declared and empty rather than defaulted, for the reason the harness declares its own: this class
     * has nested children and every collaborator arrives by injection, so a constructor that did work would
     * run before the context had supplied anything. Making it explicit also keeps the documented-surface
     * requirement of Rule 1 Clause E satisfied without an undocumented implicit member.
     */
    SqsReportQueueIntegrationTest() {
        // Intentionally empty; every collaborator is injected and every per-test resource is created in a
        // test method or in the lifecycle hook below.
    }

    // =================================================================================================
    // Injected collaborators. Every one is a bean the application context builds; none is constructed
    // here, no client is built here, and no endpoint, region or credential is supplied from here.
    // Rule 1 Clause B: avoid global mutable state, prefer dependency injection. There is deliberately
    // NO static field of any kind in this class - the container lifecycles and the pinned clock belong
    // to the harness, which owns the one documented exception to that rule for the whole package.
    // =================================================================================================

    /**
     * The production publisher under test: the bean that replaces the write to the transient data queue.
     *
     * <p>It publishes and does nothing else. It never runs, schedules or invokes a batch job, never
     * constructs job-control text and never spawns a process, so no assertion here claims otherwise.
     */
    @Autowired
    private ReportSubmissionService reportSubmissionService;

    /**
     * The notification publisher the report service also uses, injected only so that a second instance of
     * that service can be assembled with a different clock or a different target queue.
     */
    @Autowired
    private SnsTemplate snsTemplate;

    /** The date validator the report service needs to resolve a custom period. */
    @Autowired
    private DateValidationService dateValidationService;

    /**
     * The optional tracer the report service wraps its publish in.
     *
     * <p>A provider rather than the tracer itself, matching the service's own constructor: tracing is
     * infrastructure the publish must work without.
     */
    @Autowired
    private ObjectProvider<Tracer> tracerProvider;

    /**
     * The context's JSON mapper, used to read a received body back into the typed message and to render a
     * body for the queue-level tests.
     *
     * <p>Injected rather than constructed so that the body this class reads is interpreted by the same
     * mapper that produced it. <strong>Polymorphic default typing is not enabled anywhere</strong>, which
     * {@link HostileInputAtTheQueueBoundary#aTypeHintInAnUntrustedBodyCannotInstantiateAnArbitraryType()}
     * proves behaviourally rather than by inspecting a setting.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The deterministic message group, bound from {@code carddemo.aws.sqs.report-message-group-id}.
     *
     * <p>Bound rather than retyped: a literal here could agree with this class and disagree with the
     * configuration, which is the failure the binding exists to catch.
     */
    @Value("${carddemo.aws.sqs.report-message-group-id}")
    private String reportMessageGroupId;

    // =================================================================================================
    // Bounded budgets and legacy literals, as instance fields. They are final and immutable, and they are
    // instance rather than static because this class permits itself no static field. Every wait below is
    // bounded; nothing polls indefinitely and nothing busy-spins.
    // =================================================================================================

    /** Deadline for one service call. Generous enough for a cold container, short enough to fail visibly. */
    private final Duration callDeadline = Duration.ofSeconds(20L);

    /** Long-poll window used when a message is expected. Four attempts of this is the whole budget. */
    private final Duration receiveWait = Duration.ofSeconds(2L);

    /** Poll window used when NO message is expected, so that a negative assertion is still cheap. */
    private final Duration emptyReceiveWait = Duration.ofSeconds(1L);

    /** Visibility window applied to a received message. Every received message is deleted immediately. */
    private final Duration visibilityTimeout = Duration.ofSeconds(30L);

    /** Attempts allowed when collecting an expected number of messages. Bounds the receive loop. */
    private final int receiveAttempts = 4;

    /** Largest batch one receive call may return, which is the service maximum. */
    private final int maxReceiveBatch = 10;

    /** Attempts allowed when a queue name cannot be reused immediately after deletion. */
    private final int queueCreateAttempts = 3;

    /** Pause between those attempts. Bounded, and taken only on the specific recent-deletion failure. */
    private final Duration queueCreateBackoff = Duration.ofSeconds(2L);

    /** The suffix the queue service requires on a first-in-first-out queue name. */
    private final String fifoSuffix = ".fifo";

    /** The logical queue name the migration fixes, {@code app/csd/CARDDEMO.CSD:499}. */
    private final String logicalQueueNameContract = "carddemo-report-jobs";

    /** {@code MOVE 'Monthly' TO WS-REPORT-NAME}, {@code app/cbl/CORPT00C.cbl:214}. Mixed case, as written. */
    private final String reportNameMonthly = "Monthly";

    /** {@code MOVE 'Yearly' TO WS-REPORT-NAME}, {@code app/cbl/CORPT00C.cbl:240}. */
    private final String reportNameYearly = "Yearly";

    /** {@code MOVE 'Custom' TO WS-REPORT-NAME}, {@code app/cbl/CORPT00C.cbl:433}. */
    private final String reportNameCustom = "Custom";

    /**
     * {@code MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE}, {@code app/cbl/CORPT00C.cbl:531}.
     *
     * <p>Byte for byte, three trailing periods and the parenthesised queue name included. Altering it in any
     * way breaks parity, so it is asserted with exact equality and never with a containment check.
     */
    private final String unableToWriteTdq = "Unable to Write TDQ (JOBS)...";

    /** The affirmative confirmation, {@code WHEN 'Y' OR 'y'} at {@code app/cbl/CORPT00C.cbl:478}. */
    private final String confirmYes = "Y";

    /** The one-character selector value a set selector carries on the symbolic map. */
    private final String selectorSet = "S";

    // =================================================================================================
    // Per-test hygiene. The shared application queue is drained on the way out so that this class leaves
    // it as it found it; it is NEVER deleted, because the harness provisions it for the whole hierarchy
    // and removing it would break the live context along with every later test.
    // =================================================================================================

    /**
     * Drains the shared application queue after each test, bounded, and fails if anything survives the drain.
     *
     * <p>Only one test publishes to that queue, and it asserts the publisher's own outcome rather than
     * delivery, so nothing here depends on the drain having found anything on the first attempt. It exists so
     * that a message this class published cannot be observed by a sibling class that counts what is on the
     * queue.
     *
     * <p><strong>Side effects.</strong> Deletes messages from the shared queue. This runs before the
     * harness's own cleanup, which is what removes the queues this class created; the shared queue itself is
     * never deleted.
     *
     * <p><strong>Error modes.</strong> A service failure propagates with its cause intact rather than being
     * swallowed; there is no empty catch block anywhere in this class. A drain that <em>exhausts its budget
     * while messages are still arriving</em> now <strong>fails the test</strong>, reporting the queue name,
     * the budget spent, the total removed and the identifier and group of everything the final look found.
     * Returning quietly was the earlier behaviour and it was wrong in the one case that matters: a drain
     * whose budget is too small for what the test published is cross-test contamination waiting to happen,
     * it surfaces later as an inexplicable count in a sibling class, and the evidence needed to diagnose it -
     * which queue, which message, which group - exists only here. The budget is deliberately not raised to
     * paper over it; the failure message carries the numbers so the decision is made on evidence.
     *
     * <p>Note what the failure does <em>not</em> mean: the surviving messages are removed by the same call
     * that observes them, so the queue is left clean either way and the next test is unaffected. The failure
     * reports that this class's own budget was inadequate, which is a defect in this class and is stated as
     * such rather than as a leak that persists.
     *
     * @throws IllegalStateException if the bounded budget is spent while messages are still arriving
     */
    @AfterEach
    void drainTheSharedReportQueue() {
        final String queueUrl = queueUrlOf(reportQueueName());
        int removed = 0;
        for (int attempt = 0; attempt < this.receiveAttempts; attempt++) {
            final List<Message> drained =
                    receiveAndDelete(queueUrl, this.maxReceiveBatch, this.emptyReceiveWait);
            if (drained.isEmpty()) {
                return;
            }
            removed += drained.size();
        }

        // Budget spent and the previous attempt still returned messages. One more bounded look decides
        // whether the queue is now empty - in which case the last batch simply landed on the final attempt
        // and there is nothing wrong - or whether something is still there and must be reported.
        final List<Message> surviving =
                receiveAndDelete(queueUrl, this.maxReceiveBatch, this.emptyReceiveWait);
        if (surviving.isEmpty()) {
            return;
        }

        final String identifiers = surviving.stream()
                .map(message -> message.messageId() + " (group "
                        + message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID) + ')')
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("The shared report queue '" + reportQueueName() + "' still held "
                + surviving.size() + " message(s) once the bounded drain budget of " + this.receiveAttempts
                + " attempts was spent; " + (removed + surviving.size())
                + " were removed in total, the surviving ones included, so the next test does start clean. "
                + "The budget was nonetheless insufficient for what this test published, which is the defect: "
                + "raise it deliberately or publish less, rather than leaving the outcome to timing. "
                + "Removed on the final look: " + identifiers + '.');
    }

    // =================================================================================================
    // Queue helpers. Every one goes through the injected client; none builds a client, an endpoint or a
    // credential, and every wait is bounded.
    // =================================================================================================

    /**
     * Completes one asynchronous service call on the calling thread within a bounded deadline.
     *
     * <p>The original service exception is rethrown where it is a runtime exception, so a caller can still
     * catch a specific already-exists or recently-deleted type; anything else is wrapped with context and its
     * cause preserved. Rule 1 Clause B forbids swallowing and requires the root cause to survive, and both
     * paths here do that. An expired or interrupted call is cancelled rather than abandoned: an uncancelled
     * future keeps a connection and a callback alive for work nobody is waiting for.
     *
     * @param <T> the response type
     * @param pending the call in flight; must not be {@code null}
     * @param description what was being attempted, phrased to complete "Failed to ..."
     * @return the response
     * @throws IllegalStateException if the call exceeded its deadline, was interrupted, or failed with a
     *     cause that is not a runtime exception
     */
    private <T> T awaitCall(final CompletableFuture<T> pending, final String description) {
        try {
            return pending.get(this.callDeadline.toSeconds(), TimeUnit.SECONDS);
        } catch (final TimeoutException deadlineExceeded) {
            pending.cancel(true);
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Exceeded the %d-second deadline while attempting to %s.",
                    this.callDeadline.toSeconds(), description), deadlineExceeded);
        } catch (final InterruptedException interrupted) {
            pending.cancel(true);
            // Restore the flag before leaving so the interruption is reported rather than absorbed.
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while attempting to " + description + ".", interrupted);
        } catch (final ExecutionException completedExceptionally) {
            final Throwable cause = completedExceptionally.getCause() == null
                    ? completedExceptionally
                    : completedExceptionally.getCause();
            if (cause instanceof RuntimeException serviceFailure) {
                throw serviceFailure;
            }
            throw new IllegalStateException("Failed to " + description + ".", cause);
        }
    }

    /**
     * Resolves a queue's URL from its name.
     *
     * @param queueName the physical queue name, suffix included
     * @return the queue URL, never blank
     * @throws IllegalStateException if the call exceeded its deadline or was interrupted
     */
    private String queueUrlOf(final String queueName) {
        return awaitCall(sqsAsyncClient().getQueueUrl(builder -> builder.queueName(queueName)),
                "resolve the URL of the queue named " + queueName).queueUrl();
    }

    /**
     * Provisions a first-in-first-out queue scoped to this class and to one test's role.
     *
     * <p>The name comes from {@link #scopedResourceName(String)}, so it is unique per concrete class and
     * stable across runs, and the queue is created through the harness helper so that the harness's own
     * cleanup removes it. Creation is idempotent: an already-existing queue is accepted and its URL
     * resolved, which is the cloud equivalent of the legacy guard
     * {@code IF LASTCC=12 THEN SET MAXCC=0} that follows every
     * {@code DEFINE GENERATIONDATAGROUP} in {@code app/jcl/DEFGDGB.jcl}.
     *
     * <p><strong>The recent-deletion boundary is handled explicitly.</strong> A first-in-first-out queue
     * name cannot always be reused straight after the queue is deleted, and this class deletes its queues
     * after every test, so a rerun can land inside that window. Rather than sleeping blindly before every
     * creation, this retries only on that specific failure, a bounded number of times, with a bounded pause,
     * and then fails with a message naming the cause. It is never an unbounded wait.
     *
     * @param role a short lower-case role that distinguishes this test's queue from its siblings'
     * @return the queue URL
     * @throws IllegalStateException if the queue could not be provisioned within the attempt budget
     */
    private String provisionScopedFifoQueue(final String role) {
        final String queueName = scopedResourceName(role) + this.fifoSuffix;
        RuntimeException recentDeletion = null;
        for (int attempt = 1; attempt <= this.queueCreateAttempts; attempt++) {
            try {
                return createFifoQueue(queueName);
            } catch (final QueueDeletedRecentlyException deletedRecently) {
                recentDeletion = deletedRecently;
                pauseFor(this.queueCreateBackoff);
            }
        }
        throw new IllegalStateException(String.format(Locale.ROOT,
                "Could not provision the FIFO queue '%s' within %d attempts spaced %d seconds apart, because "
                        + "the name was still inside the reuse window that follows a deletion. The budget is "
                        + "bounded deliberately; extend it rather than waiting indefinitely.",
                queueName, this.queueCreateAttempts, this.queueCreateBackoff.toSeconds()), recentDeletion);
    }

    /**
     * Waits out a bounded pause, restoring the interrupt flag rather than absorbing an interruption.
     *
     * @param pause how long to wait; always one of this class's bounded budgets
     * @throws IllegalStateException if the thread was interrupted while waiting
     */
    private void pauseFor(final Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting out the FIFO queue name reuse window.", interrupted);
        }
    }

    /**
     * Receives up to a batch of messages and deletes every one it received.
     *
     * <p>Deletion is immediate and unconditional. On a first-in-first-out queue an undeleted message blocks
     * its whole group until its visibility window expires, so leaving one in flight would make a later
     * receive in the same test return nothing for reasons that look like a missing message.
     *
     * <p>The two system attributes this class asserts on are requested explicitly by name rather than
     * relying on a default, so the assertion cannot pass or fail on a library default changing.
     *
     * @param queueUrl the queue to read
     * @param maxMessages the largest batch to ask for
     * @param wait the bounded long-poll window
     * @return the messages received, in the order the service returned them, possibly empty
     */
    private List<Message> receiveAndDelete(final String queueUrl, final int maxMessages,
            final Duration wait) {

        final List<Message> received = awaitCall(sqsAsyncClient().receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(maxMessages)
                        .waitTimeSeconds((int) wait.toSeconds())
                        .visibilityTimeout((int) this.visibilityTimeout.toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID,
                                MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)
                        .build()),
                "receive from the queue at " + queueUrl).messages();

        for (final Message message : received) {
            awaitCall(sqsAsyncClient().deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build()),
                    "delete a received message from the queue at " + queueUrl);
        }
        return received;
    }

    /**
     * Collects up to the expected number of messages within a bounded attempt budget.
     *
     * <p>A single receive call may legitimately return fewer messages than asked for even when more are
     * available, so the collection is retried; the budget is fixed, so the worst case is
     * {@link #receiveAttempts} times {@link #receiveWait} and no more. It returns what it found rather than
     * failing, so the caller's own assertion produces the diagnosis.
     *
     * @param queueUrl the queue to read
     * @param expected how many messages the caller expects
     * @return the messages received, in service order, at most {@code expected} of them
     */
    private List<Message> receiveUpTo(final String queueUrl, final int expected) {
        final List<Message> collected = new ArrayList<>(expected);
        for (int attempt = 0; attempt < this.receiveAttempts && collected.size() < expected; attempt++) {
            collected.addAll(receiveAndDelete(queueUrl, expected - collected.size(), this.receiveWait));
        }
        return collected;
    }

    /**
     * Reads one delivered body back into the typed message the publisher sent.
     *
     * @param body the received message body
     * @return the deserialised message
     * @throws IllegalStateException if the body is not the typed message, with the parse failure preserved
     *     as the cause
     */
    private JobSubmissionMessage jobSubmissionMessageFrom(final String body) {
        try {
            return this.objectMapper.readValue(body, JobSubmissionMessage.class);
        } catch (final JsonProcessingException malformed) {
            throw new IllegalStateException(
                    "A body on the report queue was not the typed job submission message.", malformed);
        }
    }

    /**
     * Renders a typed message as the body the production publisher would have produced.
     *
     * @param message the message to render
     * @return the JSON body
     * @throws IllegalStateException if the message could not be rendered, with the cause preserved
     */
    private String bodyOf(final JobSubmissionMessage message) {
        try {
            return this.objectMapper.writeValueAsString(message);
        } catch (final JsonProcessingException unrenderable) {
            throw new IllegalStateException(
                    "The typed job submission message could not be rendered as a body.", unrenderable);
        }
    }

    /**
     * Sends one body to a queue under an explicit message group and an explicit deduplication identifier.
     *
     * <p>An identifier is <strong>required</strong>, not optional: the queues this class provisions carry
     * {@code ContentBasedDeduplication=false}, exactly as the production queue does since finding H-08, and SQS
     * rejects a send to such a queue that supplies none. That is the same contract the production publisher
     * meets by minting one per submission, so the mechanism under test here is the mechanism production uses.
     *
     * <p>The identifier is a caller-chosen token rather than a generated one, because the interesting cases are
     * precisely the ones where the caller controls whether two sends share an identity: two submissions differ,
     * and a transport retry does not.
     *
     * @param queueUrl the queue to publish to
     * @param body the body to publish
     * @param messageGroupId the group every message in the test shares
     * @param deduplicationId the identity of this send; equal values are collapsed by the queue
     */
    private void sendUnderGroup(final String queueUrl, final String body, final String messageGroupId,
            final String deduplicationId) {

        awaitCall(sqsAsyncClient().sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .messageGroupId(messageGroupId)
                        .messageDeduplicationId(deduplicationId)
                        .build()),
                "publish a body to the queue at " + queueUrl);
    }

    // =================================================================================================
    // Service and form helpers. A second instance of the production service is assembled from the same
    // injected collaborators when a test needs a different clock or its own target queue. That is still
    // dependency injection: no client is built, no endpoint is named and no credential is supplied.
    // =================================================================================================

    /**
     * Assembles the production report service with a pinned clock and an explicit target queue.
     *
     * <p>Two tests need this and neither could work without it. A month-length case needs a clock fixed at
     * one of the harness's probe instants, because the monthly range depends on how long the current month
     * is and the wall clock is never read. And a delivery assertion needs its own queue, because
     * content-based deduplication is keyed on the body for five minutes and two tests that legitimately
     * produce the same period would otherwise suppress each other.
     *
     * @param pinnedClock the time source, always a fixed clock and never the wall clock
     * @param targetQueueName the physical queue name to publish to, suffix included
     * @return a service instance sharing every other collaborator with the injected bean
     */
    private ReportSubmissionService serviceOn(final Clock pinnedClock, final String targetQueueName) {
        return new ReportSubmissionService(sqsTemplate(), this.snsTemplate, this.dateValidationService,
                pinnedClock, this.tracerProvider, targetQueueName, reportQueueLogicalName(),
                this.reportMessageGroupId, notificationTopic());
    }

    /**
     * Builds a submitted map area, {@code app/cpy-bms/CORPT00.CPY}'s seventeen input fields.
     *
     * <p>The six header fields are left absent because the send paragraph repopulates them at
     * {@code app/cbl/CORPT00C.cbl:558}, and the message field is left absent because the program blanks it
     * on entry at {@code :169-170}. Passing them as absent is therefore the accurate representation of what
     * the screen submits, and it also exercises the null handling the production code has to have.
     *
     * @param monthly the monthly selector, or {@code null} when unset
     * @param yearly the yearly selector, or {@code null} when unset
     * @param custom the custom selector, or {@code null} when unset
     * @param startMonth the custom start month component, or {@code null}
     * @param startDay the custom start day component, or {@code null}
     * @param startYear the custom start year component, or {@code null}
     * @param endMonth the custom end month component, or {@code null}
     * @param endDay the custom end day component, or {@code null}
     * @param endYear the custom end year component, or {@code null}
     * @param confirmation the four-state confirmation gate, or {@code null} for the blank state
     * @return the map area as the screen would have submitted it
     */
    private ReportRequest form(final String monthly, final String yearly, final String custom,
            final String startMonth, final String startDay, final String startYear,
            final String endMonth, final String endDay, final String endYear,
            final String confirmation) {

        return new ReportRequest(null, null, null, null, null, null,
                monthly, yearly, custom,
                startMonth, startDay, startYear, endMonth, endDay, endYear,
                confirmation, null);
    }

    /**
     * A confirmed monthly submission: the arm at {@code app/cbl/CORPT00C.cbl:213-238}.
     *
     * @return the map area with the monthly selector set and the confirmation affirmative
     */
    private ReportRequest confirmedMonthlyForm() {
        return form(this.selectorSet, null, null, null, null, null, null, null, null, this.confirmYes);
    }

    /**
     * A confirmed yearly submission: the arm at {@code app/cbl/CORPT00C.cbl:239-255}.
     *
     * @return the map area with the yearly selector set and the confirmation affirmative
     */
    private ReportRequest confirmedYearlyForm() {
        return form(null, this.selectorSet, null, null, null, null, null, null, null, this.confirmYes);
    }

    /**
     * A confirmed custom submission over six discrete components.
     *
     * <p>The component order is the source's own validation order at {@code app/cbl/CORPT00C.cbl:259-300} -
     * month, day, year for the start date and then the same three for the end date - and the components are
     * never merged into date objects before the service sees them, because the source validates them
     * separately and reports on whichever fails first.
     *
     * @param startMonth the start month component
     * @param startDay the start day component
     * @param startYear the start year component
     * @param endMonth the end month component
     * @param endDay the end day component
     * @param endYear the end year component
     * @return the map area with the custom selector set and the confirmation affirmative
     */
    private ReportRequest confirmedCustomForm(final String startMonth, final String startDay,
            final String startYear, final String endMonth, final String endDay, final String endYear) {

        return form(null, null, this.selectorSet, startMonth, startDay, startYear,
                endMonth, endDay, endYear, this.confirmYes);
    }

    /**
     * Submits a form through a service instance and reads back the single message it published.
     *
     * <p>It asserts the publisher's own success outcome first - the source composes the green notice only on
     * the path that reached the write - and then asserts that exactly one message was delivered. One, not
     * one or more: the seventeen cards collapse to a single typed message, so a second message would mean
     * the collapse had not happened.
     *
     * @param service the service instance to submit through
     * @param submitted the map area to submit
     * @param queueUrl the queue that service publishes to
     * @return the typed message that was delivered
     */
    private JobSubmissionMessage submitAndReceiveOne(final ReportSubmissionService service,
            final ReportRequest submitted, final String queueUrl) {

        final ReportSubmissionScreen screen = service.submitScreen(AttentionIdentifier.ENTER, submitted);
        assertThat(screen.errorFlagOn())
                .as("a submission that reached the publish raises no error flag; the source composes the "
                        + "green notice at app/cbl/CORPT00C.cbl:448-452 only on that path")
                .isFalse();
        assertThat(screen.successHighlight())
                .as("MOVE DFHGREEN TO ERRMSGC at app/cbl/CORPT00C.cbl:448 marks the notice a success and "
                        + "not an error, which is the one place the program distinguishes the two")
                .isTrue();

        final List<Message> delivered = receiveUpTo(queueUrl, 2);
        assertThat(delivered)
                .as("the seventeen eighty-byte cards of app/cbl/CORPT00C.cbl:79-127 collapse to exactly one "
                        + "typed message, so one message is delivered and never two")
                .hasSize(1);
        return jobSubmissionMessageFrom(delivered.get(0).body());
    }

    /**
     * Asserts the monthly range is the full calendar month at one pinned instant.
     *
     * <p>The expectation is stated twice on purpose: once as the literal dates a reader can check against a
     * calendar, and once derived from the instant, so that a transcription slip in the literal cannot pass.
     *
     * @param probe the instant to fix the clock at; one of the harness's probe constants
     * @param expectedStart the first day of that instant's month, as ten dashed characters
     * @param expectedEnd the last day of that instant's month, as ten dashed characters
     */
    private void assertFullCalendarMonthAt(final Instant probe, final String expectedStart,
            final String expectedEnd) {

        final String queueUrl = provisionScopedFifoQueue("month-" + expectedStart);
        final JobSubmissionMessage published = submitAndReceiveOne(
                serviceOn(clockFixedAt(probe), queueNameOf(queueUrl)), confirmedMonthlyForm(), queueUrl);

        final LocalDate firstOfMonth = LocalDate.ofInstant(probe, ZoneOffset.UTC).withDayOfMonth(1);

        assertThat(published.reportName())
                .as("MOVE 'Monthly' TO WS-REPORT-NAME at app/cbl/CORPT00C.cbl:214, in that exact mixed case")
                .isEqualTo(this.reportNameMonthly);
        assertThat(published.startDate())
                .as("app/cbl/CORPT00C.cbl:217-219 set the start to the current year and month with the "
                        + "literal '01'")
                .isEqualTo(expectedStart)
                .isEqualTo(firstOfMonth.toString());
        assertThat(published.endDate())
                .as("app/cbl/CORPT00C.cbl:223-234 force the day to 1, add a month, roll the year past twelve, "
                        + "subtract a day and read the mutated fields back, which lands on the LAST DAY of "
                        + "the CURRENT month")
                .isEqualTo(expectedEnd)
                .isEqualTo(firstOfMonth.plusMonths(1L).minusDays(1L).toString());
        assertThat(LocalDate.parse(published.endDate()).getDayOfMonth())
                .as("the end date is the month's last day, so its day of month equals the month's length")
                .isEqualTo(firstOfMonth.lengthOfMonth());
    }

    /**
     * Derives a queue's physical name from its URL.
     *
     * <p>The name is the last path segment. It is taken from the URL the service returned rather than
     * rebuilt from a literal, so no address, host or port is written here and the value provably matches the
     * queue that was created.
     *
     * @param queueUrl the queue URL
     * @return the physical queue name, suffix included
     */
    private String queueNameOf(final String queueUrl) {
        return queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
    }

    /**
     * A physical queue name that is scoped to this class and is never created.
     *
     * <p>Aiming a service instance at it is how the failure path is provoked. The application configures the
     * missing-queue strategy to fail rather than to create, so the publish cannot provision its way out of
     * the problem - which is itself part of what the failure tests establish. Nothing creates this queue and
     * nothing has to clean it up.
     *
     * @return a queue name that does not exist
     */
    private String absentQueueName() {
        return scopedResourceName("absent") + this.fifoSuffix;
    }

    /**
     * A C0 control character, the kind that terminates a log line or drives a terminal.
     *
     * <p>Returned from a code point rather than written as a source escape. A source escape for a control
     * character is translated before the file is even tokenised, which makes it easy to misread and easy to
     * mangle; a code point cannot be mistaken for anything else and keeps this file pure ASCII.
     *
     * @return the character at code point one
     */
    private char controlCharacter() {
        return (char) 0x0001;
    }

    /**
     * The right-to-left override, which reorders how text after it is displayed.
     *
     * <p>It sits above the control range, so unlike {@link #controlCharacter()} it is not escaped by a JSON
     * encoder and travels as itself. That is correct behaviour rather than a gap: it is data the caller
     * supplied, and silently stripping it would corrupt a value.
     *
     * @return the right-to-left override character
     */
    private char rightToLeftOverride() {
        return (char) 0x202E;
    }

    // =================================================================================================
    // 1. The queue contract. DISPOSITION(MOD) is why the replacement is FIFO rather than standard, and
    //    ordering within one group is the property that has to be demonstrated rather than assumed.
    // =================================================================================================

    /** The first-in-first-out contract that stands in for {@code DEFINE TDQUEUE(JOBS)}. */
    @Nested
    @DisplayName("the FIFO contract replacing DEFINE TDQUEUE(JOBS) at app/csd/CARDDEMO.CSD:499-505")
    class TheFifoQueueContract {

        /**
         * Sole constructor, invoked by the test framework.
         *
         * <p>Declared and empty. This group covers the FIFO attributes of the report queue, message
         * ordering within one group, and deduplication, and it holds no state of its own: every collaborator it uses
         * is injected into the enclosing instance, and every helper it calls belongs to that instance too.
         */
        TheFifoQueueContract() {
            // Intentionally empty; this group holds no state.
        }

        /**
         * The configured queue reports both attributes the migration relies on, and they differ.
         *
         * <p>The first-in-first-out attribute is what reproduces {@code DISPOSITION(MOD)}. The deduplication
         * attribute must be <strong>off</strong>: finding H-08, severity High. The body of a report message is
         * the report name and two dates, so content-based deduplication - which hashes the body - collapses two
         * legitimate submissions of the same period inside its five-minute window. The transient data queue
         * appended every write and {@code app/cbl/CORPT00C.cbl:L515-L523} carries no idempotency key at all, so
         * collapsing them is a behaviour change and an invisible one: the second submission is accepted, logged
         * as published, and then discarded by the queue. The publisher supplies an explicit
         * {@code MessageDeduplicationId} per submission instead, which the attribute being off is what makes
         * mandatory.
         */
        @Test
        @DisplayName("the configured report queue is FIFO with content-based deduplication OFF")
        void theConfiguredReportQueueIsFifoWithoutContentBasedDeduplication() {
            final Map<QueueAttributeName, String> attributes = awaitCall(
                    sqsAsyncClient().getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrlOf(reportQueueName()))
                            .attributeNames(QueueAttributeName.FIFO_QUEUE,
                                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION)
                            .build()),
                    "read the attributes of the configured report queue").attributes();

            assertThat(attributes.get(QueueAttributeName.FIFO_QUEUE))
                    .as("DISPOSITION(MOD) at app/csd/CARDDEMO.CSD:503 is a strictly sequential append to one "
                            + "stream, so the replacement must be a FIFO queue and not a standard one")
                    .isEqualTo("true");
            // The service omits the attribute when it is off, and omission is how it says false, so both
            // renderings are accepted; what must never appear is "true".
            assertThat(attributes.get(QueueAttributeName.CONTENT_BASED_DEDUPLICATION))
                    .as("body-keyed deduplication would collapse two legitimate submissions of the same "
                            + "period, which DISPOSITION(MOD) delivered twice; the publisher supplies an "
                            + "explicit MessageDeduplicationId instead (finding H-08)")
                    .isIn(null, "false");
        }

        /**
         * The physical name is the documented logical name plus the suffix the service requires.
         *
         * <p>Both values are read from configuration rather than retyped, so this catches a configuration
         * that drifted apart rather than merely restating a literal twice.
         */
        @Test
        @DisplayName("the physical queue name is the logical name plus the required suffix")
        void thePhysicalQueueNameIsTheLogicalNamePlusTheRequiredSuffix() {
            assertThat(reportQueueLogicalName())
                    .as("carddemo.aws.sqs.report-queue-logical-name is fixed by the migration and is the name "
                            + "the documentation and every log line use")
                    .isEqualTo(logicalQueueNameContract);
            assertThat(reportQueueName())
                    .as("carddemo.aws.sqs.report-queue is the logical name plus the suffix the queue service "
                            + "requires on a FIFO queue; a mismatch fails the application's own startup check")
                    .isEqualTo(reportQueueLogicalName() + fifoSuffix);
        }

        /**
         * Three messages sent in a known order under one group arrive in exactly that order.
         *
         * <p>This is the assertion the whole choice of a first-in-first-out queue exists for. The legacy loop
         * at {@code app/cbl/CORPT00C.cbl:498-508} appended one card at a time to a queue declared
         * {@code DISPOSITION(MOD)}, so order was a property of the transport. A standard queue would deliver
         * these three in any order and the test would pass intermittently, which is the failure mode the
         * explicit ordering assertion removes.
         *
         * <p>Each send carries its own deduplication identifier, so nothing can be suppressed here whatever the
         * bodies are; the deduplication contract itself is the subject of the next test.
         */
        @Test
        @DisplayName("three messages sent in order under one group id are received in exactly that order")
        void threeMessagesUnderOneGroupArriveInSendOrder() {
            final String queueUrl = provisionScopedFifoQueue("ordering");
            final List<JobSubmissionMessage> sent = List.of(
                    new JobSubmissionMessage(reportNameMonthly, "2022-01-01", "2022-01-31"),
                    new JobSubmissionMessage(reportNameMonthly, "2022-02-01", "2022-02-28"),
                    new JobSubmissionMessage(reportNameMonthly, "2022-03-01", "2022-03-31"));

            for (int index = 0; index < sent.size(); index++) {
                sendUnderGroup(queueUrl, bodyOf(sent.get(index)), reportMessageGroupId,
                        "ordering-" + index);
            }

            final List<Message> delivered = receiveUpTo(queueUrl, sent.size());
            assertThat(delivered)
                    .as("all three messages must be delivered before their order can be judged")
                    .hasSize(sent.size());

            final List<JobSubmissionMessage> received = new ArrayList<>(delivered.size());
            for (final Message message : delivered) {
                received.add(jobSubmissionMessageFrom(message.body()));
            }
            assertThat(received)
                    .as("within one message group the queue preserves send order, which is what "
                            + "DISPOSITION(MOD) and the card-by-card write at app/cbl/CORPT00C.cbl:507 bought")
                    .containsExactlyElementsOf(sent);
        }

        /**
         * Two identical submissions are both delivered, and a transport retry of one is not.
         *
         * <p><strong>Finding H-08, severity High. This test asserted the opposite and locked the defect in
         * place.</strong> It previously sent the same body twice, received one message, and recorded that as
         * correct behaviour on the reasoning that content-based deduplication is keyed on the body. The legacy
         * contract is the reverse: {@code DEFINE TDQUEUE(JOBS) ... DISPOSITION(MOD)} appends, and
         * {@code app/cbl/CORPT00C.cbl:L515-L523} writes unconditionally, so an operator who re-submitted the
         * same period got two entries in the reader. A test that asserts suppression is worse than no test,
         * because it makes the loss look intended.
         *
         * <p>Both halves are still needed and neither is sufficient alone. Two identical submissions carrying
         * <em>distinct</em> deduplication identifiers - which is what the publisher mints per submission -
         * must both arrive, proving no body-keyed collapse remains. And a repeat carrying the <em>same</em>
         * identifier, which is what a transport-level retry of one submission looks like on the wire, must
         * arrive once, proving the queue still collapses the one duplicate that would be an artefact of this
         * implementation rather than of the caller's intent.
         *
         * <p>They are one test rather than two on purpose: the second half is only meaningful in sequence with
         * the first, on the same queue, inside the same deduplication window.
         */
        @Test
        @DisplayName("two identical submissions are both delivered; a retried identifier is delivered once")
        void identicalSubmissionsAreBothDeliveredAndARetriedIdentifierIsNot() {
            final String queueUrl = provisionScopedFifoQueue("dedup");
            final JobSubmissionMessage submission =
                    new JobSubmissionMessage(reportNameYearly, "2022-01-01", "2022-12-31");

            sendUnderGroup(queueUrl, bodyOf(submission), reportMessageGroupId, "submission-one");
            sendUnderGroup(queueUrl, bodyOf(submission), reportMessageGroupId, "submission-two");

            final List<Message> bothSubmissions = receiveUpTo(queueUrl, 2);
            assertThat(bothSubmissions)
                    .as("the same period submitted twice must be delivered twice, because DISPOSITION(MOD) at "
                            + "app/csd/CARDDEMO.CSD:503 appends and app/cbl/CORPT00C.cbl:515 writes with no "
                            + "idempotency key; body-keyed collapse would lose the second in silence")
                    .hasSize(2);
            assertThat(bothSubmissions)
                    .allSatisfy(message ->
                            assertThat(jobSubmissionMessageFrom(message.body())).isEqualTo(submission));

            // The same identifier again: this is a transport retry of one submission, not a second submission.
            sendUnderGroup(queueUrl, bodyOf(submission), reportMessageGroupId, "submission-two");

            final List<Message> afterRetry = receiveUpTo(queueUrl, 1);
            assertThat(afterRetry)
                    .as("a repeat carrying the identifier already used is the shape of a transport retry, and "
                            + "the queue collapses it - so the client's bounded retry strategy cannot turn one "
                            + "submission into two")
                    .isEmpty();
        }
    }

    // =================================================================================================
    // 2. The typed message. Content equivalence with the eighty-byte cards, and proof that the deck did
    //    not survive the translation in any form.
    // =================================================================================================

    /** The typed message that replaces the seventeen fixed-width job cards. */
    @Nested
    @DisplayName("the typed report message replacing the 17-card deck at app/cbl/CORPT00C.cbl:79-127")
    class TheTypedReportMessage {

        /**
         * Sole constructor, invoked by the test framework.
         *
         * <p>Declared and empty. This group covers the content of the typed message that replaces the seventeen fixed-
         * width job cards, and it holds no state of its own: every collaborator it uses is injected into the enclosing
         * instance, and every helper it calls belongs to that instance too.
         */
        TheTypedReportMessage() {
            // Intentionally empty; this group holds no state.
        }

        /**
         * Every value the three variable cards carried is reconstructible from the typed message.
         *
         * <p><strong>The body is deliberately not asserted to be eighty bytes.</strong> Transformation rule
         * 10 makes the eighty-byte fixed record <em>become</em> a typed JSON message, so a length assertion
         * on the body would assert the opposite of the requirement. What is asserted is that the three
         * eighty-byte cards can each be rebuilt from the message's fields and that each rebuild is exactly
         * eighty bytes - which is the content equivalence the rule actually calls for.
         *
         * <p>The three cards, from {@code app/cbl/CORPT00C.cbl:103-121}:
         *
         * <ul>
         *   <li>{@code :103-107} is {@code FILLER PIC X(18) VALUE "PARM-START-DATE,C'"} then
         *       {@code PARM-START-DATE-1 PIC X(10)} then {@code FILLER PIC X(52)} - eighty bytes.</li>
         *   <li>{@code :108-112} is {@code FILLER PIC X(16) VALUE "PARM-END-DATE,C'"} then
         *       {@code PARM-END-DATE-1 PIC X(10)} then {@code FILLER PIC X(54)} - eighty bytes.</li>
         *   <li>{@code :117-121} is {@code PARM-START-DATE-2 PIC X(10)}, one space,
         *       {@code PARM-END-DATE-2 PIC X(10)} and {@code FILLER PIC X(59)} - eighty bytes, and the one
         *       card whose whole content is the two dates.</li>
         * </ul>
         *
         * <p>That all three rebuild from two fields is simultaneously the evidence for the collapse: one
         * start-date field feeds two cards and one end-date field feeds two cards.
         */
        @Test
        @DisplayName("the typed message carries everything the eighty-byte cards carried")
        void theTypedMessageCarriesEverythingTheEightyByteCardsCarried() {
            final String queueUrl = provisionScopedFifoQueue("content");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)), confirmedMonthlyForm(), queueUrl);

            assertThat(published.reportName()).isEqualTo(reportNameMonthly);
            assertThat(published.startDate())
                    .as("PARM-START-DATE is PIC X(10), so the dashed form is exactly ten characters")
                    .hasSize(10);
            assertThat(published.endDate()).hasSize(10);

            final byte[] symnamesStartCard = fixedWidthBytes(
                    "PARM-START-DATE,C'" + published.startDate() + "'", JOB_SUBMISSION_RECORD_LENGTH);
            final byte[] symnamesEndCard = fixedWidthBytes(
                    "PARM-END-DATE,C'" + published.endDate() + "'", JOB_SUBMISSION_RECORD_LENGTH);
            final byte[] dateParmCard = fixedWidthBytes(
                    published.startDate() + " " + published.endDate(), JOB_SUBMISSION_RECORD_LENGTH);

            assertFixedWidth(symnamesStartCard, JOB_SUBMISSION_RECORD_LENGTH, "app/csd/CARDDEMO.CSD:502");
            assertFixedWidth(symnamesEndCard, JOB_SUBMISSION_RECORD_LENGTH, "app/csd/CARDDEMO.CSD:502");
            assertFixedWidth(dateParmCard, JOB_SUBMISSION_RECORD_LENGTH, "app/csd/CARDDEMO.CSD:502");

            assertThat(new String(dateParmCard, StandardCharsets.ISO_8859_1))
                    .as("FILLER-3 at app/cbl/CORPT00C.cbl:117-121 is start date, one space, end date, then "
                            + "fifty-nine blanks, and the typed message carries both dates it needs")
                    .isEqualTo(fixedWidth(published.startDate() + " " + published.endDate(),
                            JOB_SUBMISSION_RECORD_LENGTH));

            final int bodyLength = bodyOf(published).getBytes(StandardCharsets.UTF_8).length;
            assertThat(bodyLength)
                    .as("the body is a typed JSON message and NOT an eighty-byte card. Its length is stated "
                            + "here only to make that explicit; no assertion anywhere in this class requires "
                            + "the body to be any particular length")
                    .isNotEqualTo(JOB_SUBMISSION_RECORD_LENGTH);
        }

        /**
         * The message carries each date once although the deck injected each one twice.
         *
         * <p>Four injection points across the deck - {@code :106}, {@code :111}, {@code :118} and
         * {@code :120} - carry two values, because every branch moves each date to a <em>pair</em> of
         * targets. The typed message declares one field per date and the consumer derives both uses from it,
         * so the record has exactly three components and no more.
         */
        @Test
        @DisplayName("each date appears once in the typed message though the deck injected it twice")
        void eachDateAppearsOnceThoughTheDeckInjectedItTwice() {
            final RecordComponent[] components = JobSubmissionMessage.class.getRecordComponents();

            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .as("three components: the report name of app/cbl/CORPT00C.cbl:58 plus one field per "
                            + "date, so the four injection points at :106, :111, :118 and :120 collapse to "
                            + "two fields")
                    .containsExactly("reportName", "startDate", "endDate");
            assertThat(Arrays.stream(components).map(RecordComponent::getType).toList())
                    .as("all three are ten-character fixed-width text in the source, validated as text by a "
                            + "service that reports a severity code rather than by a parser, so re-deriving "
                            + "them as temporal types would discard information")
                    .containsExactly(String.class, String.class, String.class);
        }

        /**
         * No job-control text reaches the queue and no job deck reaches the file system.
         *
         * <p>The seventeen cards collapse into one typed message. Building the deck as text and publishing
         * it, or writing it to disk and handing it to a process, is forbidden: it
         * would reproduce the mainframe's submission mechanism rather than replace it, and it is the failure
         * this test is here to exclude. The publisher spawns no process and evaluates no script, which is the
         * same prohibition that discharges the migration invariant that no external sort process is spawned.
         *
         * <p>The negative file check names the artefacts a submission would have produced. Failsafe pins the
         * forked working directory to the project base directory, so a relative check is well defined.
         */
        @Test
        @DisplayName("no job-control text reaches the queue and no job deck reaches the file system")
        void noJobControlTextAndNoJobDeckAreProduced() {
            final String queueUrl = provisionScopedFifoQueue("cards");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)), confirmedMonthlyForm(), queueUrl);

            assertThat(bodyOf(published))
                    .as("none of the seventeen card images survives into the message: not the job card at "
                            + "app/cbl/CORPT00C.cbl:83-86, not the library card at :89, not the step card at "
                            + ":93, not the sort-symbol cards at :99-112, not the date-parameter cards at "
                            + ":115-121 and not the terminator at :124-125")
                    .doesNotContain("//", "/*", "JOB ", "JCLLIB", "EXEC", "PROC=", "SYMNAMES", "DATEPARM",
                            "NOTIFY", "TRNRPT00", "EOF");

            for (final String deckArtefact : List.of("TRNRPT00", "TRNRPT00.jcl", "JOBS", "INREADER",
                    "jobsub.jcl", "SYSUT1")) {
                assertThat(Files.exists(Path.of(deckArtefact)))
                        .as("a job deck named '%s' must not be written anywhere: the cards collapse to a "
                                + "typed message, and writing a deck or spawning a submitter would "
                                + "reintroduce the mechanism the migration replaced", deckArtefact)
                        .isFalse();
            }
        }

        /**
         * The injected production bean publishes to the configured queue and reports the source's notice.
         *
         * <p>This is the one test that exercises the injected bean against the <em>shared</em> application
         * queue, and it deliberately asserts the publisher's own outcome rather than delivery. The reason is
         * content-based deduplication: the shared queue is never deleted, so a body this test published
         * would suppress an identical body for five minutes, and the class is required to pass twice in
         * succession. The publisher's outcome is unaffected by that, because a deduplicated send still
         * succeeds - and it is a complete assertion on its own, since the method raises rather than returning
         * when the publish fails.
         *
         * <p>The success tail at {@code app/cbl/CORPT00C.cbl:445-456} has a statement order that matters:
         * {@code PERFORM INITIALIZE-ALL-FIELDS} at {@code :447} comes <strong>first</strong> and blanks the
         * message, and only then is the green notice composed at {@code :448-452}. Reversing the two would
         * wipe the notice, so the cleared form and the populated notice are asserted together.
         */
        @Test
        @DisplayName("the injected production bean publishes to the configured queue and clears the form")
        void theInjectedProductionBeanPublishesToTheConfiguredQueue() {
            final ReportSubmissionScreen screen =
                    reportSubmissionService.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyForm());

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.successHighlight())
                    .as("MOVE DFHGREEN TO ERRMSGC at app/cbl/CORPT00C.cbl:448")
                    .isTrue();
            assertThat(screen.form().errorMessage())
                    .as("app/cbl/CORPT00C.cbl:449-452 composes the report name then ' report submitted for "
                            + "printing ...', with the leading space and the space before the three dots both "
                            + "part of the literal")
                    .isEqualTo(reportNameMonthly + " report submitted for printing ...");
            assertThat(screen.form().monthlySelected())
                    .as("INITIALIZE-ALL-FIELDS at app/cbl/CORPT00C.cbl:637 blanks the monthly selector, and "
                            + "it runs at :447 BEFORE the notice is composed")
                    .isEmpty();
            assertThat(screen.cursorField())
                    .as("MOVE -1 TO MONTHLYL at app/cbl/CORPT00C.cbl:453 returns the cursor to the monthly "
                            + "selector")
                    .isEqualTo("MONTHLYL");
            assertThat(screen.navigationTarget())
                    .as("the screen is redisplayed rather than left, so no program is transferred to")
                    .isNull();
        }
    }

    // =================================================================================================
    // 3. The reporting period. Three arms, first match wins, and a monthly range that is the FULL
    //    calendar month, taken from :229-230 rather than from any secondary description.
    // =================================================================================================

    /** The three reporting periods the screen offers, and the order in which they are decided. */
    @Nested
    @DisplayName("the reporting period at app/cbl/CORPT00C.cbl:212-436")
    class ThePeriodResolution {

        /**
         * Sole constructor, invoked by the test framework.
         *
         * <p>Declared and empty. This group covers the three reporting periods and the order in which their selectors
         * are decided, and it holds no state of its own: every collaborator it uses is injected into the enclosing
         * instance, and every helper it calls belongs to that instance too.
         */
        ThePeriodResolution() {
            // Intentionally empty; this group holds no state.
        }

        /**
         * Monthly is the full current calendar month, measured over the real queue boundary.
         *
         * <p>The pinned clock sits on the tenth of a thirty-day month, so the start and the end of the
         * period are distinguishable from one another and from today on this instant, which is what makes
         * the range assertion below decisive rather than coincidental.
         */
        @Test
        @DisplayName("monthly runs from the first day of the current month to its last day")
        void monthlyIsTheFullCalendarMonth() {
            final String queueUrl = provisionScopedFifoQueue("monthly-context");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)), confirmedMonthlyForm(), queueUrl);

            assertThat(published.reportName()).isEqualTo(reportNameMonthly);
            assertThat(published.startDate())
                    .as("app/cbl/CORPT00C.cbl:217-219 assemble the current year and month with a day of 01")
                    .isEqualTo(FIXED_DATE.withDayOfMonth(1).toString());
            assertThat(published.endDate())
                    .as("app/cbl/CORPT00C.cbl:229-230 computes the first of the NEXT month less one day, "
                            + "and :232-234 read that value back out of the redefined date area, so the "
                            + "period ends on the last day of THIS month")
                    .isEqualTo(FIXED_DATE.withDayOfMonth(1).plusMonths(1L).minusDays(1L).toString());
            assertThat(LocalDate.parse(published.endDate()).getDayOfMonth())
                    .as("the end date is the month's last day, so its day of month equals the month length")
                    .isEqualTo(FIXED_DATE.lengthOfMonth());
        }

        /** A thirty-one day month, where the last day is the thirty-first. */
        @Test
        @DisplayName("the monthly range spans a whole 31-day month")
        void theMonthlyRangeSpansAThirtyOneDayMonth() {
            assertFullCalendarMonthAt(PROBE_INSTANT_31_DAY_MONTH, "2022-07-01", "2022-07-31");
        }

        /** A thirty-day month, where an off-by-one in the arithmetic would land on the thirty-first. */
        @Test
        @DisplayName("the monthly range spans a whole 30-day month")
        void theMonthlyRangeSpansAThirtyDayMonth() {
            assertFullCalendarMonthAt(PROBE_INSTANT_30_DAY_MONTH, "2022-06-01", "2022-06-30");
        }

        /** A common-year February, the shortest month there is. */
        @Test
        @DisplayName("the monthly range spans a whole 28-day February")
        void theMonthlyRangeSpansATwentyEightDayFebruary() {
            assertFullCalendarMonthAt(PROBE_INSTANT_28_DAY_MONTH, "2023-02-01", "2023-02-28");
        }

        /**
         * A leap-year February.
         *
         * <p>Neither the source nor the target holds a table of month lengths or a leap-year test: the source
         * delegates to the intrinsic date functions and the target to the date library. This case and the
         * previous one are correct for the same reason and neither is special-cased, which is exactly what
         * makes them worth asserting separately.
         */
        @Test
        @DisplayName("the monthly range spans a whole 29-day leap February")
        void theMonthlyRangeSpansATwentyNineDayLeapFebruary() {
            assertFullCalendarMonthAt(PROBE_INSTANT_29_DAY_LEAP_MONTH, "2024-02-01", "2024-02-29");
        }

        /**
         * December, the only month whose arithmetic crosses a year boundary.
         *
         * <p>{@code app/cbl/CORPT00C.cbl:225-228} is the branch that adds one to the year when the month
         * passes twelve, and December is the only input that reaches it. A target that added a month without
         * rolling the year would produce a start date in December and an end date in the previous January,
         * and every other month would still pass.
         */
        @Test
        @DisplayName("the monthly range spans December, the only path that rolls the year")
        void theMonthlyRangeSpansDecemberWhereTheYearRolls() {
            assertFullCalendarMonthAt(PROBE_INSTANT_DECEMBER_YEAR_ROLL, "2022-12-01", "2022-12-31");
        }

        /**
         * Yearly runs from the first of January to the thirty-first of December.
         *
         * <p>{@code app/cbl/CORPT00C.cbl:243-244} set both years in one statement, {@code :245-246} move
         * {@code '01'} into both the start month and the start day, and {@code :250-251} move {@code '12'}
         * and {@code '31'}. Nothing consults the submitted range, which is why {@code :255} can submit
         * unconditionally.
         */
        @Test
        @DisplayName("yearly runs from 1 January to 31 December of the current year")
        void yearlyRunsFromTheFirstOfJanuaryToTheThirtyFirstOfDecember() {
            final String queueUrl = provisionScopedFifoQueue("yearly");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)), confirmedYearlyForm(), queueUrl);

            assertThat(published.reportName())
                    .as("MOVE 'Yearly' TO WS-REPORT-NAME at app/cbl/CORPT00C.cbl:240, in that exact mixed "
                            + "case")
                    .isEqualTo(reportNameYearly);
            assertThat(published.startDate()).isEqualTo(FIXED_DATE.getYear() + "-01-01");
            assertThat(published.endDate()).isEqualTo(FIXED_DATE.getYear() + "-12-31");
        }

        /**
         * A custom range is assembled with dash separators into ten characters.
         *
         * <p>The two parameter fields are declared at {@code app/cbl/CORPT00C.cbl:60-65} and {@code :66-71}
         * as {@code X(04)} then a literal dash then {@code X(02)} then a literal dash then {@code X(02)}, so
         * the assembled value is always ten characters with separators at the fifth and eighth positions.
         * <strong>A compact undashed form is a High-severity divergence</strong>: the validator is called with
         * an explicit dashed format string at {@code :72}, so an undashed value would be rejected by the very
         * service that is supposed to accept it.
         */
        @Test
        @DisplayName("a custom range is assembled with dash separators into ten characters")
        void theCustomRangeIsAssembledWithDashSeparators() {
            final String queueUrl = provisionScopedFifoQueue("custom");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)),
                    confirmedCustomForm("08", "29", "1997", "09", "30", "1997"), queueUrl);

            assertThat(published.reportName())
                    .as("MOVE 'Custom' TO WS-REPORT-NAME at app/cbl/CORPT00C.cbl:433")
                    .isEqualTo(reportNameCustom);
            assertThat(published.startDate()).isEqualTo("1997-08-29");
            assertThat(published.endDate()).isEqualTo("1997-09-30");
            assertThat(published.startDate().charAt(4))
                    .as("the separator sits at the fifth position, per the X(04) '-' X(02) '-' X(02) "
                            + "declaration at app/cbl/CORPT00C.cbl:60-65; never a compact undashed form")
                    .isEqualTo('-');
            assertThat(published.startDate().charAt(7)).isEqualTo('-');
        }

        /**
         * The selector cascade is first-match-wins, so monthly beats yearly and yearly beats custom.
         *
         * <p>{@code EVALUATE TRUE} at {@code app/cbl/CORPT00C.cbl:212} evaluates its conditions in written
         * order and takes the first that holds: monthly at {@code :213}, yearly at {@code :239}, custom at
         * {@code :256}. This is also why the three selectors are three independent one-character fields
         * rather than an enumeration - <strong>the source has no enumeration here</strong>, and an
         * enumeration could not represent two selectors at once, which this screen can. A submission with
         * every selector set and a deliberately invalid custom range therefore resolves as monthly and never
         * reaches the custom validation at all.
         */
        @Test
        @DisplayName("monthly wins over yearly and custom when more than one selector is set")
        void monthlyWinsOverYearlyAndCustom() {
            final String queueUrl = provisionScopedFifoQueue("precedence-monthly");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)),
                    form(selectorSet, selectorSet, selectorSet, "99", "99", "9999", "99", "99", "9999",
                            confirmYes),
                    queueUrl);

            assertThat(published.reportName())
                    .as("the monthly arm at app/cbl/CORPT00C.cbl:213 is written first, so it wins and the "
                            + "invalid custom components are never examined")
                    .isEqualTo(reportNameMonthly);
            assertThat(published.startDate()).isEqualTo(FIXED_DATE.withDayOfMonth(1).toString());
        }

        /** With the monthly selector clear, yearly still beats custom. */
        @Test
        @DisplayName("yearly wins over custom when both of those selectors are set")
        void yearlyWinsOverCustom() {
            final String queueUrl = provisionScopedFifoQueue("precedence-yearly");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)),
                    form(null, selectorSet, selectorSet, "99", "99", "9999", "99", "99", "9999", confirmYes),
                    queueUrl);

            assertThat(published.reportName())
                    .as("the yearly arm at app/cbl/CORPT00C.cbl:239 precedes the custom arm at :256")
                    .isEqualTo(reportNameYearly);
            assertThat(published.endDate()).isEqualTo(FIXED_DATE.getYear() + "-12-31");
        }

        /**
         * Monthly submits unconditionally while an invalid custom range publishes nothing.
         *
         * <p>The asymmetry is behaviour, not tidiness. {@code app/cbl/CORPT00C.cbl:238} and {@code :255}
         * perform the submission unconditionally, because neither arm consults the submitted range - both
         * derive their dates from the clock and neither can fail. {@code :434-436} performs it inside
         * {@code IF NOT ERR-FLG-ON}, so a rejected custom range never reaches the queue.
         *
         * <p>The month component {@code '13'} fails the unguarded comparison against the literal
         * {@code '12'} at {@code :329-337}. Note what that comparison is <em>not</em>: it is alphanumeric,
         * it has no lower bound so {@code '00'} passes, and there is no year range anywhere - all three
         * absences are preserved rather than repaired.
         */
        @Test
        @DisplayName("monthly always publishes while an invalid custom range publishes nothing")
        void monthlyAlwaysPublishesWhileAnInvalidCustomRangePublishesNothing() {
            final String queueUrl = provisionScopedFifoQueue("asymmetry");
            final ReportSubmissionService service = serviceOn(clock(), queueNameOf(queueUrl));

            final Throwable rejected = catchThrowable(() -> service.submitScreen(AttentionIdentifier.ENTER,
                    confirmedCustomForm("13", "01", "1997", "12", "31", "1997")));

            assertThat(rejected)
                    .as("app/cbl/CORPT00C.cbl:329-337 compares the month against the literal '12' with no "
                            + "guard, so '13' is rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Month...");
            assertThat(((ValidationException) rejected).getFieldName())
                    .as("MOVE -1 TO SDTMML at app/cbl/CORPT00C.cbl:335 parks the cursor on the start month")
                    .isEqualTo("SDTMML");
            assertThat(receiveAndDelete(queueUrl, maxReceiveBatch, emptyReceiveWait))
                    .as("the custom submission at app/cbl/CORPT00C.cbl:434-436 is guarded by IF NOT "
                            + "ERR-FLG-ON, so a rejected range publishes nothing at all")
                    .isEmpty();

            final JobSubmissionMessage published =
                    submitAndReceiveOne(service, confirmedMonthlyForm(), queueUrl);
            assertThat(published.reportName())
                    .as("the monthly arm submits unconditionally at app/cbl/CORPT00C.cbl:238, on the same "
                            + "service instance that had just rejected a custom range")
                    .isEqualTo(reportNameMonthly);
        }

        /**
         * A rejected request carries exactly one message, never an accumulated list.
         *
         * <p>Validation is first-failure-wins and every send site in the source is a terminal exit:
         * {@code SEND-TRNRPT-SCREEN} at {@code app/cbl/CORPT00C.cbl:556} ends its final {@code END-IF} at
         * {@code :578} and then takes an unconditional {@code GO TO RETURN-TO-CICS.} at {@code :580}, and
         * {@code RETURN-TO-CICS.} at {@code :585} issues the return at {@code :587-591}. All twenty-two
         * performs of that paragraph therefore end the turn. <strong>Accumulating a multi-error list is a
         * High-severity divergence.</strong>
         *
         * <p>The submission below has two independent failures - a blank start month and a blank end year -
         * and the source's blank-check order at {@code :258-303} is start month, start day, start year, end
         * month, end day, end year. So the start month's message is the only one that can be produced, and
         * the end year's message must be absent rather than appended.
         */
        @Test
        @DisplayName("exactly one error message is reported per rejected request, first failure winning")
        void exactlyOneErrorMessageIsReportedPerRejectedRequest() {
            final String queueUrl = provisionScopedFifoQueue("first-failure");
            final ReportSubmissionService service = serviceOn(clock(), queueNameOf(queueUrl));

            final Throwable rejected = catchThrowable(() -> service.submitScreen(AttentionIdentifier.ENTER,
                    confirmedCustomForm(null, "01", "1997", "12", "31", null)));

            assertThat(rejected).isInstanceOf(ValidationException.class);
            assertThat(rejected.getMessage())
                    .as("the blank check order at app/cbl/CORPT00C.cbl:259 is the start month, so its message "
                            + "is the one and only outcome")
                    .isEqualTo("Start Date - Month can NOT be empty...");
            assertThat(rejected.getMessage())
                    .as("the blank end year at app/cbl/CORPT00C.cbl:294-300 is a real second failure and is "
                            + "deliberately NOT reported: :580 makes every send a terminal exit, so no second "
                            + "message can be reached and no list may be accumulated")
                    .doesNotContain("End Date");
            assertThat(((ValidationException) rejected).getFieldName()).isEqualTo("SDTMML");
            assertThat(receiveAndDelete(queueUrl, maxReceiveBatch, emptyReceiveWait)).isEmpty();
        }
    }

    // =================================================================================================
    // 4. The message group and the failure path. A deterministic group is what makes ordering
    //    reproducible, and the failure literal is a byte-exact contract.
    // =================================================================================================

    /** The deterministic message group, and the arm at {@code app/cbl/CORPT00C.cbl:525-535}. */
    @Nested
    @DisplayName("the deterministic message group and the failure path at app/cbl/CORPT00C.cbl:515-535")
    class TheDeterministicGroupIdAndTheFailurePath {

        /**
         * Sole constructor, invoked by the test framework.
         *
         * <p>Declared and empty. This group covers the deterministic message group and the byte-exact publish-failure
         * contract, and it holds no state of its own: every collaborator it uses is injected into the enclosing
         * instance, and every helper it calls belongs to that instance too.
         */
        TheDeterministicGroupIdAndTheFailurePath() {
            // Intentionally empty; this group holds no state.
        }

        /**
         * Two submissions carry the same message group, and it is the configured literal.
         *
         * <p>{@code DISPOSITION(MOD)} at {@code app/csd/CARDDEMO.CSD:503} was a strict append to a single
         * stream, so one fixed group reproduces its ordering exactly. <strong>A generated identifier is a
         * High-severity divergence</strong>, not a stylistic choice: it places each submission in its own
         * group, which forfeits ordering <em>between</em> submissions and does so unreproducibly, so the
         * ordering test above would pass or fail depending on timing.
         *
         * <p>Determinism is asserted three ways. The two submissions agree with each other, which rules out a
         * per-call random or clock-derived value. Both equal the configured property, which rules out a value
         * derived from the request. And the value is the documented logical queue name, which is what a
         * reader of the configuration would expect to find.
         *
         * <p>The two submissions use different periods so that content-based deduplication cannot suppress
         * the second one - which would otherwise look exactly like a group identifier that had changed.
         */
        @Test
        @DisplayName("the message group id is deterministic across submissions and is the configured value")
        void theMessageGroupIdIsDeterministicAcrossSubmissions() {
            final String queueUrl = provisionScopedFifoQueue("groupid");
            final ReportSubmissionService service = serviceOn(clock(), queueNameOf(queueUrl));

            service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyForm());
            service.submitScreen(AttentionIdentifier.ENTER, confirmedYearlyForm());

            final List<Message> delivered = receiveUpTo(queueUrl, 2);
            assertThat(delivered)
                    .as("two submissions with different periods produce two distinct bodies, so neither can "
                            + "be deduplicated away")
                    .hasSize(2);

            final String firstGroup = delivered.get(0).attributes().get(
                    MessageSystemAttributeName.MESSAGE_GROUP_ID);
            final String secondGroup = delivered.get(1).attributes().get(
                    MessageSystemAttributeName.MESSAGE_GROUP_ID);

            assertThat(firstGroup)
                    .as("the group is fixed by configuration, not generated per call, so two submissions land "
                            + "in the same group and their order is therefore guaranteed")
                    .isEqualTo(secondGroup)
                    .isEqualTo(reportMessageGroupId)
                    .isEqualTo(logicalQueueNameContract);

            assertThat(delivered.get(0).attributes().get(
                    MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID))
                    .as("the publisher supplies no deduplication identifier; the queue derives one from the "
                            + "body, which is the content-based mechanism the migration chose because the "
                            + "eighty-byte record of app/csd/CARDDEMO.CSD:502 had no field for one")
                    .isNotBlank();

            assertThat(jobSubmissionMessageFrom(delivered.get(0).body()).reportName())
                    .as("order within the group is send order, so the monthly submission arrives first")
                    .isEqualTo(reportNameMonthly);
            assertThat(jobSubmissionMessageFrom(delivered.get(1).body()).reportName())
                    .isEqualTo(reportNameYearly);
        }

        /**
         * A failed publish raises the source's own literal, byte for byte, with its cause preserved.
         *
         * <p>The failure is provoked by aiming a service instance at a queue that was never created. The
         * application configures the missing-queue strategy to fail rather than to create, so the publish
         * cannot silently provision its way out of the problem - which is itself the behaviour under test.
         *
         * <p><strong>The literal is asserted with exact equality and never with a containment check.</strong>
         * {@code app/cbl/CORPT00C.cbl:531} moves {@code Unable to Write TDQ (JOBS)...} into the message, with
         * three trailing periods and the queue name in parentheses. Altering it in any way breaks parity,
         * and a containment check would not notice a fourth period or a missing parenthesis.
         *
         * <p><strong>What the failure carries, and what it deliberately does not.</strong> It names the
         * logical queue and the operation {@code WRITEQ TD}, because the source states both at
         * {@code app/cbl/CORPT00C.cbl:515-523} - {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} - and because an
         * {@code ERROR} diagnostic that omitted them would report a failure without saying what failed, which
         * Rule 1 Clause A rules out. It carries <strong>no file status</strong>, and that absence is asserted
         * rather than assumed: the source declares no file control entry, no {@code SELECT} and no {@code FD}
         * for the transient data queue, so a status would fabricate an I/O condition that does not exist.
         * {@code hasIoStatus()} reporting {@code false} is what keeps the {@code FILE STATUS IS: NNNN}
         * rendering off this path, since the expanded status of an absent status is the placeholder
         * {@code " 032"} rather than nothing at all.
         *
         * <p>A third property is asserted for the same reason: the failure is not swallowed. The legacy
         * definition carries {@code ERROROPTION(IGNORE)} at {@code app/csd/CARDDEMO.CSD:501}, so the
         * transaction monitor ignored a failed write, and <strong>the Java side deliberately does
         * not</strong>. That divergence is labelled rather than absorbed, and Rule 1 Clause B is what
         * requires it.
         */
        @Test
        @DisplayName("a failed publish raises 'Unable to Write TDQ (JOBS)...' with its cause preserved")
        void aFailedPublishRaisesTheSourceLiteralWithItsCausePreserved() {
            final ReportSubmissionService service = serviceOn(clock(), absentQueueName());

            final Throwable thrown = catchThrowable(
                    () -> service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyForm()));

            assertThat(thrown)
                    .as("the publish failure surfaces as a typed I/O failure rather than being ignored, "
                            + "which is a deliberate divergence from ERROROPTION(IGNORE)")
                    .isInstanceOf(FileAccessException.class);
            assertThat(thrown.getMessage())
                    .as("app/cbl/CORPT00C.cbl:531, byte for byte")
                    .isEqualTo(unableToWriteTdq);
            assertThat(unableToWriteTdq)
                    .as("the constant this class compares against is itself checked for shape, so a typo in "
                            + "it cannot make the assertion above vacuous")
                    .endsWith("...")
                    .contains("(JOBS)")
                    .doesNotContain("....");
            assertThat(thrown.getCause())
                    .as("Rule 1 Clause B: nothing is swallowed and the root cause survives, so an operator "
                            + "sees the source's message and a developer still sees why it happened")
                    .isNotNull();
            assertThat(((FileAccessException) thrown).getLogicalFileName())
                    .as("the failing resource is named, and named LOGICALLY: app/cbl/CORPT00C.cbl:515-523 "
                            + "writes to QUEUE('JOBS'), so an operator diagnostic that left the slot empty "
                            + "would report a failure without saying what failed. The logical name is a "
                            + "literal in application.yml and therefore carries no account identifier, "
                            + "unlike the physical name or the resolved URL")
                    .isEqualTo(reportQueueLogicalName());
            assertThat(((FileAccessException) thrown).getOperation())
                    .as("the source's own verb, EXEC CICS WRITEQ TD, rather than the SDK operation name: the "
                            + "line is reconciled against the COBOL program")
                    .isEqualTo("WRITEQ TD");
            assertThat(((FileAccessException) thrown).hasIoStatus())
                    .as("the source declares no file control entry, no SELECT and no FD for the transient "
                            + "data queue, so there is no COBOL FILE STATUS to carry and none is fabricated. "
                            + "This is what keeps the FILE STATUS IS: NNNN rendering - and the ' 032' "
                            + "placeholder that stands for an absent status - off this path entirely")
                    .isFalse();
        }

        /**
         * The failure warning keeps the legacy prefixes, concatenated with no separator.
         *
         * <p>{@code app/cbl/CORPT00C.cbl:529} is {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}, and
         * COBOL concatenates {@code DISPLAY} operands with <strong>no separator at all</strong>, so the
         * emitted text reads {@code RESP:}<em>resp</em>{@code REAS:}<em>reas</em>. That shape is preserved so
         * an operator who knew the legacy line recognises this one. It is the only instrumentation the source
         * has on this path: there are exactly two {@code DISPLAY} statements in the whole program, the trace
         * marker at {@code :210} and this one.
         *
         * <p>What occupies the two slots has changed, and that is the labelled part of the divergence: the
         * first carries a symbolic reason from a closed vocabulary and the second the failing exception's
         * class name, because the legacy response codes have no counterpart. Neither slot carries an
         * endpoint, an account identifier or the throwable's own message.
         *
         * <p>The appender is attached to the publisher's logger for the duration of this test and detached in
         * a {@code finally} block, so no global logging state survives the test and a failure cannot leave a
         * capture attached for the next one.
         */
        @Test
        @DisplayName("the failure warning carries the RESP: and REAS: prefixes with no separator")
        void theFailureWarningCarriesTheRespAndReasPrefixes() {
            final Logger publisherLogger = (Logger) LoggerFactory.getLogger(ReportSubmissionService.class);
            final ListAppender<ILoggingEvent> captured = new ListAppender<>();
            captured.setContext(publisherLogger.getLoggerContext());
            captured.start();
            publisherLogger.addAppender(captured);

            final List<String> warnings;
            try {
                final ReportSubmissionService service = serviceOn(clock(), absentQueueName());
                assertThat(catchThrowable(() -> service.submitScreen(
                        AttentionIdentifier.ENTER, confirmedMonthlyForm())))
                        .isInstanceOf(FileAccessException.class);

                warnings = captured.list.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("RESP:"))
                        .toList();
            } finally {
                publisherLogger.detachAppender(captured);
                captured.stop();
            }

            assertThat(warnings)
                    .as("one warning per failed publish, matching the single DISPLAY at "
                            + "app/cbl/CORPT00C.cbl:529")
                    .hasSize(1);

            final String warning = warnings.get(0);
            assertThat(warning)
                    .as("both legacy prefixes survive, and the logical queue name is named so the line is "
                            + "useful across environments without disclosing a physical name or an endpoint")
                    .contains("REAS:")
                    .contains(reportQueueLogicalName());
            assertThat(warning)
                    .as("COBOL DISPLAY concatenates its operands with no separator, so nothing sits between "
                            + "the RESP: prefix and its value")
                    .doesNotStartWith("RESP: ");
            assertThat(warning.substring(0, warning.indexOf("REAS:")))
                    .as("and nothing sits between the first value and the REAS: prefix either")
                    .doesNotEndWith(" ");
        }
    }

    // =================================================================================================
    // 5. Hostile input at the queue boundary. Rule 1 Clause A: treat inputs as untrusted. Every outcome
    //    below is a single deterministic one - a clean typed rejection whose message and cause are
    //    asserted, or a value neutralised by the boundary - and never a silent acceptance.
    // =================================================================================================

    /** Untrusted input at the publish boundary, and the confirmation gate that stands in front of it. */
    @Nested
    @DisplayName("hostile input at the queue boundary")
    class HostileInputAtTheQueueBoundary {

        /**
         * Sole constructor, invoked by the test framework.
         *
         * <p>Declared and empty. This group covers untrusted input at the publish boundary, and the confirmation gate
         * that stands in front of it, and it holds no state of its own: every collaborator it uses is injected into
         * the enclosing instance, and every helper it calls belongs to that instance too.
         */
        HostileInputAtTheQueueBoundary() {
            // Intentionally empty; this group holds no state.
        }

        /**
         * Hostile text survives the queue round trip intact and never as a raw control byte.
         *
         * <p>The typed JSON boundary is what makes this safe, and it is worth being precise about why. A
         * carriage return, a line feed and a C0 control character are all escaped by the JSON encoder, so the
         * body that reaches the queue contains no raw control byte and cannot terminate a log line or drive a
         * terminal. A right-to-left override is above the control range and travels as itself, which is
         * correct: it is data, and silently stripping it would corrupt a value the caller supplied.
         *
         * <p>An over-long value is accepted at this boundary and that is not a silent acceptance: there is no
         * length contract on a JSON string field to violate, the value round-trips exactly, and the
         * <em>production</em> boundary that does have length contracts - the six date components - is the
         * subject of the next test.
         */
        @Test
        @DisplayName("hostile text round-trips through the queue with no raw control byte in the body")
        void hostileTextRoundTripsThroughTheQueueWithNoRawControlByte() {
            final String queueUrl = provisionScopedFifoQueue("hostile");
            // Built from explicit code points rather than from source escapes, so that what the test sends is
            // unambiguous to a reader and cannot be altered by how the file itself is encoded.
            final String hostile = "Monthly\r\n" + controlCharacter() + rightToLeftOverride()
                    + "X".repeat(500);
            final JobSubmissionMessage message =
                    new JobSubmissionMessage(hostile, "2022-01-01", "2022-01-31");
            final String body = bodyOf(message);

            for (int index = 0; index < body.length(); index++) {
                assertThat(body.charAt(index))
                        .as("the JSON encoder escapes every control character, so the body that reaches the "
                                + "queue carries none of them raw and cannot forge a log line")
                        .isGreaterThanOrEqualTo(' ');
            }

            sendUnderGroup(queueUrl, body, reportMessageGroupId, "hostile-round-trip");
            final List<Message> delivered = receiveUpTo(queueUrl, 1);

            assertThat(delivered).hasSize(1);
            assertThat(jobSubmissionMessageFrom(delivered.get(0).body()))
                    .as("the round trip is exact: nothing is trimmed, stripped, normalised or re-encoded, so "
                            + "a value the caller supplied is neither corrupted nor silently altered")
                    .isEqualTo(message);
        }

        /**
         * Hostile content in a date component cannot reach the assembled date.
         *
         * <p>The normalisation at {@code app/cbl/CORPT00C.cbl:305-327} reproduces a move into an unsigned
         * {@code PIC 9(n)} item: it keeps digits only, discards everything from a decimal point onwards, and
         * applies the field's modulus, so the result is always exactly two or four digits. Two security
         * properties follow, and neither was designed in - both fall out of reproducing the source faithfully.
         * A control character, a line break or an override character in a component is discarded, so it can
         * never reach the assembled date and therefore never reach a log line or a job parameter. And an
         * arbitrarily long component is reduced by the modulus rather than overflowing, however long it is.
         *
         * <p>Note the legacy quirk this preserves: the source applies a <em>currency-aware</em> numeric
         * conversion to date components. That is odd and it is kept, because parity is the contract.
         */
        @Test
        @DisplayName("hostile content in a date component is discarded before the date is assembled")
        void hostileContentInADateComponentCannotReachTheAssembledDate() {
            final String queueUrl = provisionScopedFifoQueue("component");
            final JobSubmissionMessage published = submitAndReceiveOne(
                    serviceOn(clock(), queueNameOf(queueUrl)),
                    form(null, null, selectorSet,
                            "0\r\n8", rightToLeftOverride() + "29", "199" + controlCharacter() + "7",
                            "999999999999999912", "31", "1997",
                            confirmYes),
                    queueUrl);

            assertThat(published.startDate())
                    .as("the line break and the override character are discarded by the digits-only "
                            + "normalisation at app/cbl/CORPT00C.cbl:305-316, leaving 08 and 29 and 1997")
                    .isEqualTo("1997-08-29");
            assertThat(published.endDate())
                    .as("an eighteen-digit month is reduced by the PIC 9(2) modulus at "
                            + "app/cbl/CORPT00C.cbl:317-319 rather than overflowing, leaving 12")
                    .isEqualTo("1997-12-31");
            assertThat(published.startDate()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(published.endDate()).matches("\\d{4}-\\d{2}-\\d{2}");
        }

        /**
         * An empty body and a null field are both refused, each with its cause preserved.
         *
         * <p>Rule 1 Clause B requires null and empty cases to be handled explicitly. Three cases are checked
         * because they fail at three different points: an empty body fails in the parser, a null field in a
         * body fails in the record's own constructor during deserialisation, and a null field passed directly
         * fails in that constructor immediately. None of the three is swallowed and none produces a partially
         * populated message, which the source could not express either - the parameter fields were
         * fixed-width areas that always held something.
         */
        @Test
        @DisplayName("an empty body and a null field are refused with their cause preserved")
        void anEmptyBodyAndANullFieldAreRefusedWithTheirCausePreserved() {
            final Throwable emptyBody = catchThrowable(() -> jobSubmissionMessageFrom(""));
            assertThat(emptyBody)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was not the typed job submission message");
            assertThat(emptyBody.getCause())
                    .as("the parse failure is preserved as the cause rather than discarded")
                    .isNotNull();

            final Throwable nullInBody = catchThrowable(() -> jobSubmissionMessageFrom(
                    "{\"reportName\":null,\"startDate\":\"2022-01-01\",\"endDate\":\"2022-01-31\"}"));
            assertThat(nullInBody).isInstanceOf(IllegalStateException.class);
            assertThat(nullInBody.getCause()).isNotNull();

            final Throwable nullDirect = catchThrowable(
                    () -> new JobSubmissionMessage(null, "2022-01-01", "2022-01-31"));
            assertThat(nullDirect)
                    .as("the record's own constructor refuses an incompletely populated message at the one "
                            + "point where the omission would otherwise reach the queue")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("reportName must not be null");
        }

        /**
         * A type hint in an untrusted body is data at best and refused at worst; it is never a type.
         *
         * <p>Insecure deserialisation is one of the three risky patterns Rule 1 Clause D names, and it is
         * directly relevant here because the message is JSON. <strong>Polymorphic default typing is not
         * enabled anywhere</strong>, and this asserts that behaviourally rather than by reading a setting -
         * a setting can be correct on the mapper this test inspects and wrong on the one the queue uses.
         *
         * <p>Two shapes are checked. A class name carried as a string value arrives as that string and
         * nothing is instantiated from it. A two-element array in the value position - the exact shape
         * default typing would read as a type followed by a value - is refused outright, because a
         * {@code String} component cannot be populated from an array. With default typing enabled the second
         * shape would construct an instance of whatever the first element named, so its refusal is the
         * discriminating observation.
         */
        @Test
        @DisplayName("a type hint in an untrusted body is treated as data and never instantiates a type")
        void aTypeHintInAnUntrustedBodyCannotInstantiateAnArbitraryType() {
            final String typeNameAsText = "java.io.File";

            final JobSubmissionMessage asData = jobSubmissionMessageFrom(
                    "{\"reportName\":\"" + typeNameAsText
                            + "\",\"startDate\":\"2022-01-01\",\"endDate\":\"2022-01-31\"}");
            assertThat(asData)
                    .as("the body deserialises to the declared type and to nothing else")
                    .isExactlyInstanceOf(JobSubmissionMessage.class);
            assertThat(asData.reportName())
                    .as("a class name in a value position is text, and is carried as text")
                    .isEqualTo(typeNameAsText);

            final Throwable asTypeHint = catchThrowable(() -> jobSubmissionMessageFrom(
                    "{\"reportName\":[\"" + typeNameAsText
                            + "\",\"anything\"],\"startDate\":\"2022-01-01\",\"endDate\":\"2022-01-31\"}"));
            assertThat(asTypeHint)
                    .as("the array shape default typing would read as a type-and-value pair is refused, "
                            + "which is what proves default typing is off rather than merely configured off")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(asTypeHint.getCause()).isNotNull();
        }

        /**
         * A blank confirmation asks for confirmation and publishes nothing.
         *
         * <p>State one of four at {@code app/cbl/CORPT00C.cbl:464-474}. The message is composed from the
         * resolved report name, so the period is resolved before the gate is reached, and the cursor goes to
         * the confirmation field. Nothing is published, because the whole submission block at
         * {@code :476-510} sits inside {@code IF NOT ERR-FLG-ON}.
         */
        @Test
        @DisplayName("a blank confirmation asks for confirmation and publishes nothing")
        void aBlankConfirmationAsksForConfirmationAndPublishesNothing() {
            final String queueUrl = provisionScopedFifoQueue("confirm-blank");
            final ReportSubmissionService service = serviceOn(clock(), queueNameOf(queueUrl));

            final Throwable rejected = catchThrowable(() -> service.submitScreen(AttentionIdentifier.ENTER,
                    form(selectorSet, null, null, null, null, null, null, null, null, null)));

            assertThat(rejected)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Please confirm to print the " + reportNameMonthly + " report...");
            assertThat(((ValidationException) rejected).getFieldName())
                    .as("MOVE -1 TO CONFIRML at app/cbl/CORPT00C.cbl:472")
                    .isEqualTo("CONFIRML");
            assertThat(receiveAndDelete(queueUrl, maxReceiveBatch, emptyReceiveWait)).isEmpty();
        }

        /**
         * An unrecognised confirmation quotes the offending value and publishes nothing.
         *
         * <p>State four of four at {@code app/cbl/CORPT00C.cbl:484-493}, which composes a quoted echo of the
         * submitted character. Only {@code 'Y'} and {@code 'y'} are accepted and only {@code 'N'} and
         * {@code 'n'} decline, compared against those four literals rather than by case folding - so no
         * locale enters into it, and a locale-sensitive upper-casing of a dotless letter cannot change the
         * outcome.
         *
         * <p>A printable character is used deliberately. The quoting contract is what is under test, and
         * echoing a control character into an assertion message would prove nothing extra while making the
         * failure output unreadable; the neutralisation of control characters is asserted in the two tests
         * above, at the boundaries where it actually happens.
         */
        @Test
        @DisplayName("an unrecognised confirmation quotes the offending value and publishes nothing")
        void anUnrecognisedConfirmationQuotesTheOffendingValueAndPublishesNothing() {
            final String queueUrl = provisionScopedFifoQueue("confirm-invalid");
            final ReportSubmissionService service = serviceOn(clock(), queueNameOf(queueUrl));

            final Throwable rejected = catchThrowable(() -> service.submitScreen(AttentionIdentifier.ENTER,
                    form(selectorSet, null, null, null, null, null, null, null, null, "Z")));

            assertThat(rejected)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("\"Z\" is not a valid value to confirm...");
            assertThat(((ValidationException) rejected).getFieldName()).isEqualTo("CONFIRML");
            assertThat(receiveAndDelete(queueUrl, maxReceiveBatch, emptyReceiveWait)).isEmpty();
        }

        /**
         * A declined confirmation clears the form, sets no message and sets no cursor.
         *
         * <p>State three of four at {@code app/cbl/CORPT00C.cbl:480-483}, and a preserved quirk worth
         * asserting on its own: the arm performs {@code INITIALIZE-ALL-FIELDS}, raises the error flag and
         * sends, and it sets <strong>no message and no cursor</strong>. The result is a cleared form carrying
         * a blank message. <strong>No "cancelled" notice may be invented</strong>, and no cursor field may be
         * supplied - the blank message is the behaviour, not an omission in the translation. Nothing is
         * published.
         */
        @Test
        @DisplayName("a declined confirmation clears the form with a blank message and no cursor")
        void aDeclinedConfirmationClearsTheFormWithABlankMessageAndNoCursor() {
            final String queueUrl = provisionScopedFifoQueue("confirm-declined");
            final ReportSubmissionService service = serviceOn(clock(), queueNameOf(queueUrl));

            final ReportSubmissionScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    form(selectorSet, null, null, null, null, null, null, null, null, "N"));

            assertThat(screen.errorFlagOn())
                    .as("MOVE 'Y' TO WS-ERR-FLG at app/cbl/CORPT00C.cbl:482")
                    .isTrue();
            assertThat(screen.successHighlight()).isFalse();
            assertThat(screen.form().errorMessage())
                    .as("the arm sets no message at all, so the cleared form carries a blank one; inventing a "
                            + "cancellation notice would be a behaviour change")
                    .isEmpty();
            assertThat(screen.cursorField())
                    .as("the arm sets no cursor either, unlike the other three confirmation states")
                    .isNull();
            assertThat(screen.form().monthlySelected())
                    .as("PERFORM INITIALIZE-ALL-FIELDS at app/cbl/CORPT00C.cbl:481 blanks every selector")
                    .isEmpty();
            assertThat(receiveAndDelete(queueUrl, maxReceiveBatch, emptyReceiveWait))
                    .as("a declined confirmation never reaches the submission block at "
                            + "app/cbl/CORPT00C.cbl:496-508")
                    .isEmpty();
        }
    }
}
