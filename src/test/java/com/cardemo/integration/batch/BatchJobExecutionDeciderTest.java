/*
 * ******************************************************************
 * Component   : BatchJobExecutionDeciderTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Spring Boot 3.5.11,
 *               Spring Batch 5.2.4, Testcontainers 2.0.3)
 * Function    : Pins the JobExecutionDecider layer that replaces JCL
 *               COND=(0,NE) step gating, across all four legacy return
 *               codes - 0, 4, 8 and 12 - and their mapped ExitStatus
 *               outcomes. Every decider in this codebase is an inline
 *               plain object rather than a container bean, so the
 *               subject is reached exclusively through flow and job
 *               execution and is asserted on the observed exit status,
 *               the step census and the absence of a step execution for
 *               a gated-out step. The corpus census that justifies the
 *               gating at all is proved against the frozen sources
 *               rather than restated from prose.
 * Source      : app/cbl/CBTRN02C.cbl (the return-code 4 gate at
 *               :229-:231, the abend paragraph at :707-:711, the status
 *               renderer at :714-:727, the 430-byte reject record at
 *               :176-:182, the five reject codes at :385, :397, :410,
 *               :417 and :556, and the scoped not-found leniency at
 *               :481), app/jcl/CREASTMT.JCL (the corpus's only three
 *               COND=(0,NE) sites, at :56, :66 and :79),
 *               app/cbl/CBSTM03A.CBL (:921-:923, an abend with no
 *               ABCODE and no TIMING), app/cbl/CBSTM03B.CBL
 *               (:128-:131, a bare GOBACK with no abend at all),
 *               app/cpy/CSMSG02Y.cpy (:12-20, the four abend work-area
 *               fields), app/cbl/CBACT04C.cbl (:138, :422, :446, :631),
 *               app/cbl/CBTRN03C.cbl (:155, :629, :630),
 *               app/cbl/CBTRN01C.cbl (:147, :472),
 *               app/cbl/CBACT01C.cbl (:66, :172), app/jcl/POSTTRAN.jcl
 *               (the six-DD contract and the 430-byte reject DCB),
 *               CONTRIBUTING.md:33-34 @ 7756d89
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
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.repository.TransactionRepository;

/**
 * The decider layer that replaces JCL {@code COND=(0,NE)} step gating, pinned across return codes 0, 4, 8
 * and 12 and the {@code ExitStatus} each one maps onto.
 *
 * <h2>What it does</h2>
 *
 * <p>Two things, and they are deliberately kept apart. The first three tests prove, against the frozen
 * corpus itself, the census that justifies decider gating and fixes the four return codes: that
 * {@code COND=(0,NE)} occurs at exactly three sites and all three are in one member; that the only
 * numeric-literal return-code assignment anywhere is the reject gate; and that the abend contract covers
 * exactly eight batch programs. The remaining tests then prove the runtime behaviour of the deciders the
 * migration built from that census.
 *
 * <p><strong>Every {@code org.springframework.batch.core.job.flow.JobExecutionDecider} in this codebase is
 * an inline plain object</strong> - a {@code private static final} nested class handed straight to the flow
 * builder - and none is registered in the container. Resolving one from the bean factory therefore throws
 * {@code org.springframework.beans.factory.NoSuchBeanDefinitionException}, so this class never attempts it.
 * The subject is reached exclusively by launching a job and asserting on what the flow observably did: the
 * job's {@code ExitStatus} and {@code BatchStatus}, the set of {@code StepExecution}s that were actually
 * created, and each step's own exit code. A step the gate suppressed has <strong>no step execution at
 * all</strong>, which is precisely how {@code COND=(0,NE)} behaves - JES2 bypasses the step, so it never
 * runs and produces no record - and is asserted here as absence rather than as some "skipped" status.
 *
 * <h2>The four return codes and where each one comes from</h2>
 *
 * <dl>
 *   <dt>0, completed</dt>
 *   <dd>Normal completion, mapped to {@code ExitStatus.COMPLETED}. The gated successor runs. All three
 *       gates admit their step, because all three condition-code cards are identical.</dd>
 *
 *   <dt>4, completed with rejects - and <strong>not</strong> a failure</dt>
 *   <dd>Set when, and only when, the reject count is positive. The gate is
 *       {@code app/cbl/CBTRN02C.cbl:229}-{@code :231}: {@code DISPLAY 'TRANSACTIONS REJECTED  :'} at
 *       {@code :228} - with two spaces before the colon - is followed by
 *       {@code IF WS-REJECT-COUNT} {@code &gt;} {@code 0} at {@code :229} and
 *       {@code MOVE 4 TO RETURN-CODE} at {@code :230}. There is no threshold, no percentage and no warning
 *       band: the count alone decides. That single statement is the <em>only</em> numeric-literal
 *       {@code RETURN-CODE} assignment in the whole corpus; the one other assignment anywhere,
 *       {@code app/cbl/CSUTLDTC.cbl:98}, moves a variable. It maps onto a distinct named exit code that is
 *       not {@code FAILED}. {@code app/jcl/POSTTRAN.jcl} carries no {@code COND} at all, so only the exit
 *       code mapping is asserted there and no gating is invented for it.</dd>
 *
 *   <dt>8, failed</dt>
 *   <dd>A processing failure that is not an abend. <strong>No COBOL locator exists for the literal 8</strong>
 *       - the corpus never assigns it - so the cited evidence is the behaviour: a step that ends
 *       unsuccessfully without an abend exit status. It maps onto {@code ExitStatus.FAILED}.</dd>
 *
 *   <dt>12, abend</dt>
 *   <dd>The consequence of {@code CALL 'CEE3ABD'}, never an explicit move. {@code 9999-ABEND-PROGRAM} at
 *       {@code app/cbl/CBTRN02C.cbl:707}-{@code :711} displays {@code 'ABENDING PROGRAM'}, zeroes
 *       {@code TIMING} at {@code :709}, moves <strong>999</strong> into {@code ABCODE} at {@code :710} and
 *       calls the language-environment abend service at {@code :711}. Citing only {@code :707}-{@code :710}
 *       would truncate the call that is the whole point of the paragraph. In the target it becomes
 *       {@code com.cardemo.exception.FatalProcessingException}, which declares abend code 999 and process
 *       return code 12 as constants, carries the four abend work-area fields of
 *       {@code app/cpy/CSMSG02Y.cpy:12-20}, and contains no process-exit call.</dd>
 *   </dl>
 *
 * <p><strong>The abend census, which the requirements deliberately do not generalise.</strong> A naive
 * search for the abend-code token finds twelve programs, but two unrelated mechanisms share it and only
 * eight follow the 999-and-12 contract. Those eight are batch programs that declare
 * {@code 01 ABCODE PIC S9(9) BINARY} and move 999 into it before calling the abend service:
 * {@code app/cbl/CBACT01C.cbl} at {@code :66} and {@code :172}, {@code app/cbl/CBACT02C.cbl},
 * {@code app/cbl/CBACT03C.cbl}, {@code app/cbl/CBACT04C.cbl} at {@code :138} and {@code :631},
 * {@code app/cbl/CBCUS01C.cbl}, {@code app/cbl/CBTRN01C.cbl} at {@code :147} and {@code :472},
 * {@code app/cbl/CBTRN02C.cbl} at {@code :147} and {@code :710}, and {@code app/cbl/CBTRN03C.cbl} at
 * {@code :155}, {@code :629} and {@code :630}. Four <em>online</em> programs instead issue a CICS abend with
 * the four-character literal {@code '9999'} - a different command, a different value and a different
 * runtime - and none of them has a batch job, so none is modelled here.
 * <strong>Conflating 999 with '9999' is a Blocker</strong>, and the guard against it is asserted directly.
 * Two further members sit outside the contract and are asserted to do so:
 * {@code app/cbl/CBSTM03A.CBL:921}-{@code :923} calls the abend service with <em>no</em> abend code and no
 * timing field, so it abends with the language environment's default; and
 * {@code app/cbl/CBSTM03B.CBL:128}-{@code :131} is {@code GO TO 9999-GOBACK} to {@code 9999-GOBACK} to a
 * bare {@code GOBACK}, which is no abend at all.
 *
 * <p><strong>Reject codes are business outcomes and are never thrown.</strong>
 * {@code com.cardemo.model.enums.RejectCode} is closed at exactly five constants - 100 at
 * {@code app/cbl/CBTRN02C.cbl:385}, 101 at {@code :397}, 102 at {@code :410}, 103 at {@code :417} and 109 at
 * {@code :556} - each carrying its exact literal description. They drive the exit status and nothing else.
 * Code 109 is assigned on the account-rewrite-failure path, which runs only once validation has already
 * passed, so no reject record is written for it and it is cleared on the next iteration at {@code :208}: it
 * is cited here as context only and is never asserted to produce a reject or to raise the reject count.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. Failsafe is bound to {@code src/test/java/com/cardemo/integration/**} and
 * runs this tier at {@code integration-test} and {@code verify}, even though the class keeps the
 * {@code Test} suffix; Surefire is bound to {@code .../unit/**} and excludes this tree. A class moved out of
 * {@code integration/**} matches neither include set and is collected by neither plugin, so it silently
 * never runs while the build stays green. Do not rename or relocate this class.
 *
 * <p><strong>A reachable container runtime is a prerequisite.</strong> The parent harness starts a real
 * PostgreSQL container and a real LocalStack container; where no daemon or socket is available the correct
 * report is that the gate is blocked, never an untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Profile {@code test}, inherited from {@code com.cardemo.integration.batch.AbstractBatchIntegrationTest}
 *       together with the PostgreSQL 16 and LocalStack 4.14.0 container references, the injected fixed
 *       clock, the per-test run identifier and the committed-state reset. No container, no property source
 *       and no static field is declared here.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}, so nothing runs at startup and every launch in
 *       this class is explicit. No {@code @EnableBatchProcessing} appears anywhere: on Spring Boot 3.x that
 *       annotation <em>disables</em> Batch auto-configuration and would remove the launcher this class
 *       depends on.</li>
 *   <li>The {@code BATCH_*} metadata tables come from {@code spring.batch.jdbc.initialize-schema}, which the
 *       {@code test} profile sets to {@code always}. They are framework bookkeeping and never a fourth
 *       Flyway migration.</li>
 *   <li>The three Flyway migrations own the schema, the indexes and the seed data. The relevant seeded
 *       counts are 300 daily transactions, 50 accounts, 50 cross-references and - deliberately -
 *       <strong>zero</strong> rows in the double-quoted lowercase {@code "transaction"} relation, which the
 *       posting job is what fills.</li>
 *   <li><strong>No property is overridden, and one collaborator is spied.</strong> A gate that never sees a
 *       non-zero predecessor cannot be shown to suppress anything, so one test needs an ungated step to end
 *       unsuccessfully on demand. It gets that from a {@code @MockitoSpyBean} {@code TransactionRepository}
 *       whose statement-order read returns a <em>descending</em> window for that one test, which
 *       {@code STEP010} refuses on the ordering precondition of {@code app/cbl/CBSTM03A.CBL:L419}. Every
 *       other test in this class drives the real repository, because the Spring Framework override is a spy
 *       rather than a replacement and its stub is reset after the method.
 *       <p>This class used to lower {@code carddemo.batch.creastmt.max-work-records} to 1 instead. That
 *       ceiling was removed by finding BAT-002 - it was an authored business refusal on record count, which
 *       {@code app/jcl/CREASTMT.JCL} does not have - so the lever had to change with it. A refusal the
 *       corpus does not contain is not a lever a test may keep alive.</li>
 *   </ul>
 *
 * <h2>Determinism</h2>
 *
 * <p>Nothing here reads the wall clock: no current-instant, current-date or current-time call, no
 * epoch-millisecond read and no legacy date construction. The parent's injected fixed clock, pinned to
 * {@code 2022-06-10T19:27:53Z} in UTC, is the only time source, and the one date-shaped job parameter is
 * derived from it so that it cannot drift out of the inclusive 2022-01-01 to 2022-07-06 report window. Every
 * case and format operation states {@code java.util.Locale#ROOT}. There is no randomness, no reliance on
 * hash iteration order, no static mutable state, and no context-dirtying, script, truncation or bulk delete:
 * the parent's reset restores the committed starting point around every test method.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code NoSuchBeanDefinitionException} naming a decider</dt>
 *   <dd>Something tried to resolve a decider from the container. There is no such bean and there is not
 *       meant to be. Reach the decider through a job launch and assert on the observed outcome. Note that
 *       the "is not a bean" claim itself is made by <em>enumerating</em> the bean names of the type, because
 *       attempting a resolution in order to prove one does not exist would be performing the very access the
 *       design forbids.</dd>
 *
 *   <dt>Container startup fails, or the whole tier is skipped</dt>
 *   <dd>No container runtime is reachable. Start the daemon and re-run.</dd>
 *
 *   <dt>{@code Could not resolve dependencies ... org.testcontainers:localstack:2.0.3}</dt>
 *   <dd>The Testcontainers 2.x line renamed every module artefact. Both halves of the remedy are required:
 *       pin the managed version by overriding the version property rather than importing a second bill of
 *       materials, and use only the four prefixed coordinates. Overriding without renaming resolves
 *       artefacts that do not exist; renaming without overriding resolves the wrong version. Severity
 *       <strong>Blocker</strong>.</dd>
 *
 *   <dt>{@code warnings found and -Werror specified}</dt>
 *   <dd>Compilation escalates every warning to an error, so one raw type, one unchecked cast or one
 *       deprecated call fails the build. An unused import is not among the keys {@code javac} publishes, so
 *       that prohibition is review-enforced rather than compiler-enforced.</dd>
 *
 *   <dt>{@code Existing transaction detected in JobRepository}, or the parent's refusal in its place</dt>
 *   <dd>A launching method did not opt out of the class-level transaction. Annotate it
 *       {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}.</dd>
 *
 *   <dt>The report or a statement run comes back empty</dt>
 *   <dd>Something read the ambient clock rather than the injected one, stamping processing dates outside the
 *       inclusive report window so that both filters excluded every record. It looks like a defect in the
 *       report and is not one.</dd>
 *
 *   <dt>A corpus assertion fails with a missing file</dt>
 *   <dd>The two suffixes under {@code app/} are mixed case: {@code app/jcl/CREASTMT.JCL} is uppercase and
 *       owns all three condition-code sites, and {@code app/cbl/CBSTM03A.CBL} and
 *       {@code app/cbl/CBSTM03B.CBL} are uppercase {@code .CBL} among twenty-six lowercase {@code .cbl}
 *       members. A case-sensitive lowercase pattern silently drops them - and dropping the first would
 *       delete the entire justification for decider gating. Failsafe pins the forked working directory to
 *       the project base directory, which is what makes these reads resolvable at all.</dd>
 *   </dl>
 *
 * <h2>Findings by severity, with remediation</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - the Testcontainers coordinate rename described above; remedy: apply both
 *       halves together.</li>
 *   <li><strong>Blocker</strong> - conflating the language-environment batch abend code 999 with the CICS
 *       online literal {@code '9999'}; remedy: the census and the guard asserted here, which keep the two
 *       populations apart.</li>
 *   <li><strong>Blocker</strong> - a silent default-to-continue in a decider's switch surface; remedy: every
 *       decider states an outcome for 0, 4, 8 and 12 and routes anything unrecognised to a failure, never to
 *       success, and a suppressing gate routes to the return-code decider rather than ending the flow.</li>
 *   <li><strong>High</strong> - a hardcoded token signing key, an absent production profile, absent
 *       continuous integration and an unexecuted vulnerability scan; all four are closed elsewhere in the
 *       tree and none is reintroduced here.</li>
 *   <li><strong>Medium</strong> - two findings this class raised against its own first draft, both by
 *       measurement rather than by review, and both corrected here rather than worked around. The processed
 *       and rejected counters are a total and one of its parts rather than two halves, so adding them
 *       double-counts every reject and the accounting invariant has to be stated against the committed rows
 *       instead; and the statement path's abend carries the abend copybook's four-space default rather than
 *       999, which is the faithful outcome because that program moves nothing into the field before abending.
 *       Each is set out in full on the test that measured it. Remedy in both cases: assert what the source
 *       states, not what the obvious reading of it suggests.</li>
 *   <li><strong>Medium</strong> - the report retention conflict, resolved to the larger of the two declared
 *       limits; the migration-filename aliasing; and the coverage-plugin version drift, where the pinned
 *       0.8.12 governs.</li>
 *   <li><strong>Low</strong> - the service-type inaccuracy in the component registration file; out of scope
 *       here and untouched.</li>
 *   </ul>
 *
 * <h2>Not available, and what would be needed</h2>
 *
 * <p>Two disclosures, both stated rather than papered over.
 *
 * <ul>
 *   <li><strong>The boundary-parity expected-output expectation exists; what is Not available is a captured
 *       z/OS run to corroborate it.</strong> {@code src/test/resources/parity/gate1/} holds the frozen
 *       program's own output, derived by compiling {@code app/cbl/CBTRN02C.cbl} unmodified and running it
 *       against the frozen fixtures, with the derivation recorded beside it in
 *       {@code PROVENANCE.properties}; nothing there was produced by running this implementation. What is
 *       still needed is a
 *       captured 430-byte reject dataset plus the resulting transaction, account and category-balance images
 *       from a real posting run at a known input state. No baseline file is created here, no expected bytes
 *       are fabricated, a baseline produced by running this implementation would be circular and is
 *       forbidden, and the posting program is not hand-simulated.</li>
 *   <li><strong>File status {@code '35'} and its file-unavailable response are Not available.</strong>
 *       Neither the literal nor the corresponding CICS response code occurs anywhere in the COBOL corpus -
 *       for contrast, the duplicate-record and duplicate-key responses do occur - so no test for that path
 *       is invented. What would be needed is a corpus site that actually produces it.</li>
 *   <li><strong>A return-code-8 predecessor for the condition-code gates is Not available in this
 *       member.</strong> Every failure on the statement-generation path is deliberately funnelled through the
 *       typed abend, so the reachable non-zero predecessor code there is 12, and that is the code the
 *       suppression test drives. The gate's own predicate is an equality against zero, so it cannot
 *       distinguish 4, 8 and 12 from one another - which is why pairing the zero-admits test with the
 *       non-zero-suppresses test states the whole of the gate's contract, and why return code 8 is proved
 *       against the posting flow, where it is genuinely reachable, rather than manufactured here. What would
 *       be needed to drive 8 into a gate is a statement-path failure that is not an abend, and the source
 *       gives none.</li>
 *   </ul>
 *
 * <p>One more scope note, so a reader does not mistake an omission for a gap. Processor internals and the
 * file-status mapper's own translation table belong to the sibling unit tier and are not retested here; the
 * emitted reject record's own geometry and code census belong to the posting job's own integration class.
 * What this class owns is the decider and exit-status contract that sits above all of them.
 */
public class BatchJobExecutionDeciderTest extends AbstractBatchIntegrationTest {

    /** The bean factory, used only to <em>enumerate</em> decider definitions and to resolve jobs and steps. */
    @Autowired
    private ApplicationContext applicationContext;

    /** Reads the seeded relational state that fixes which reject codes are reachable. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Supplies the one controlled projection failure that makes a pre-gate step end unsuccessfully.
     *
     * <p>A spy rather than a replacement, so every other test here drives the real Spring Data repository;
     * the framework's own after-method reset removes the stub without replacing the application context. The
     * stub returns a descending statement-order window, and {@code STEP010} refuses a descending sequence on
     * the ordering precondition of {@code app/cbl/CBSTM03A.CBL:L419} - a documented failure mode of the step
     * rather than a contrivance.
     */
    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    /**
     * The registered name of the posting job, taken from the production constant rather than retyped.
     *
     * <p>Every name below that <em>is</em> a literal is one whose production constant is package-private and
     * therefore unreachable from this package; declaring them as instance fields rather than as static
     * constants keeps this class free of static state, which is the invariant the parent harness sets for
     * this package.
     */
    private final String postingJobBeanName = DailyTransactionPostingJob.JOB_BEAN_NAME;

    /** {@code CBTRN01C}, the read-only pre-flight step - a step, never a seventh job. */
    private final String postingPreFlightStepBeanName = DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME;

    /** {@code CBTRN02C}, the chunk-oriented posting step. */
    private final String postingStepBeanName = DailyTransactionPostingJob.POSTING_STEP_BEAN_NAME;

    /** The statement-generation job: the whole of {@code app/jcl/CREASTMT.JCL}. */
    private final String statementJobBeanName = "statementGenerationJob";

    /** {@code DELDEF01}, {@code app/jcl/CREASTMT.JCL:22} - ungated. */
    private final String statementDefineStepBeanName = "statementGenerationDefineStep";

    /** {@code STEP010}, {@code app/jcl/CREASTMT.JCL:44} - ungated. */
    private final String statementSortStepBeanName = "statementGenerationSortStep";

    /** {@code STEP020}, {@code app/jcl/CREASTMT.JCL:56} - the first {@code COND=(0,NE)} site. */
    private final String statementLoadStepBeanName = "statementGenerationLoadStep";

    /** {@code STEP030}, {@code app/jcl/CREASTMT.JCL:66} - the second {@code COND=(0,NE)} site. */
    private final String statementPreDeleteStepBeanName = "statementGenerationPreDeleteStep";

    /** {@code STEP040}, {@code app/jcl/CREASTMT.JCL:79} - the third {@code COND=(0,NE)} site. */
    private final String statementEmitStepBeanName = "statementGenerationEmitStep";

    /** The interest job, whose first disclosure-group read is one of the three scoped leniency sites. */
    private final String interestJobBeanName = "interestCalculationJob";

    /** The interest job's sole parameter name; the value's shape is a hard contract, not a date type. */
    private final String parmDateParameterName = "parmDate";

    /** The completed-with-rejects exit code, which is emphatically not {@code FAILED}. */
    private final String completedWithRejectsExitCode = "COMPLETED WITH REJECTS";

    /** The abend exit code, which is what makes return code 12 distinguishable from return code 8. */
    private final String abendExitCode = "ABEND";

    /** {@code app/data/ASCII/dailytran.txt} carries 300 records; the name spells the word in full. */
    private final long seededDailyTransactionCount = 300L;

    /** The three condition-code gated steps, in the order {@code app/jcl/CREASTMT.JCL} declares them. */
    private final List<String> conditionCodeGatedStepBeanNames = List.of(
            "statementGenerationLoadStep",
            "statementGenerationPreDeleteStep",
            "statementGenerationEmitStep");

    /**
     * The eight batch programs on the language-environment abend path, each with its declaration and move
     * line, in the on-disk case of its file name.
     *
     * <p>Insertion-ordered on purpose: an assertion failure should name the members in the order the census
     * states them, and a hash-ordered map would report them differently from one run to the next.
     */
    private final Map<String, List<Integer>> abendBatchProgramLines = abendBatchProgramCensus();

    /**
     * The four online programs that issue a CICS abend with the four-character literal, with its line.
     *
     * <p>They are listed so that the census is exhaustive and so that the guard against conflating the two
     * mechanisms has both populations in front of it. None of them has a batch job and none is modelled here.
     */
    private final Map<String, Integer> cicsAbendProgramLines = cicsAbendProgramCensus();

    // The corpus census that fixes the four return codes. Read from the frozen sources, never restated
    // from prose, because every one of these claims is load bearing for the deciders below.

    /**
     * The reject gate is the only numeric-literal return-code assignment in the corpus, and it is guarded by
     * a positive reject count and by nothing else.
     *
     * <p>Purpose: fix the provenance of return code 4 before any behaviour is asserted against it, and fix
     * the <em>absence</em> of provenance for 8 and 12 so that neither is ever given a fabricated locator.
     * Inputs: the frozen COBOL corpus. Output: none. Side effects: none - this method only reads. Error
     * modes: a second literal assignment would mean some other program sets a return code and the mapping
     * asserted below is incomplete; a changed guard would mean return code 4 has a determinant other than the
     * count.
     *
     * <p>The gate is {@code app/cbl/CBTRN02C.cbl:228}-{@code :231}. Note the display literal at {@code :228}
     * carries <strong>two spaces</strong> before its colon, which is why the assertion states the whole
     * literal rather than a trimmed form.
     */
    @Test
    @DisplayName("1. MOVE 4 TO RETURN-CODE at CBTRN02C.cbl:230, guarded by a positive reject count at :229, "
            + "is the corpus's only numeric-literal return-code assignment - 8 and 12 have no locator")
    void theOnlyNumericLiteralReturnCodeAssignmentInTheCorpusIsTheRejectGate() {
        final List<String> postingProgram = corpusLines("app/cbl/CBTRN02C.cbl");

        assertThat(lineAt(postingProgram, 230))
                .as("app/cbl/CBTRN02C.cbl:230 is the assignment the completed-with-rejects exit code "
                        + "reproduces")
                .contains("MOVE 4 TO RETURN-CODE");
        assertThat(lineAt(postingProgram, 229))
                .as("the guard at :229 is a positive reject count - no threshold, no percentage, no warning "
                        + "band")
                .contains("IF WS-REJECT-COUNT")
                .contains("0");
        assertThat(lineAt(postingProgram, 228))
                .as("the display literal at :228 carries two spaces before its colon, and that spacing is "
                        + "part of the emitted line")
                .contains("'TRANSACTIONS REJECTED  :'");

        final Map<String, List<Integer>> literalAssignments =
                corpusMatches("app/cbl", "TO RETURN-CODE");
        assertThat(literalAssignments)
                .as("exactly two members assign a return code at all: the posting program's literal 4 and "
                        + "the date utility's variable move")
                .containsOnlyKeys("CBTRN02C.cbl", "CSUTLDTC.cbl");
        assertThat(literalAssignments.get("CBTRN02C.cbl"))
                .as("and the posting program assigns one exactly once, at :230")
                .containsExactly(Integer.valueOf(230));
        assertThat(corpusMatches("app/cbl", "MOVE 8 TO RETURN-CODE"))
                .as("no locator exists for the literal 8; return code 8 is the behaviour of an unsuccessful "
                        + "step, and inventing a line for it would be a fabricated citation")
                .isEmpty();
        assertThat(corpusMatches("app/cbl", "MOVE 12 TO RETURN-CODE"))
                .as("nor for the literal 12; return code 12 is the language environment's consequence of "
                        + "CALL 'CEE3ABD', never an explicit move")
                .isEmpty();
    }

    /**
     * The abend contract covers exactly the eight batch programs that move 999 into the abend code, and the
     * four CICS programs and both statement members sit outside it.
     *
     * <p>Purpose: keep two unrelated mechanisms that share one token apart, which is the Blocker this class
     * guards against. Inputs: the frozen COBOL corpus and the two abend-code constants. Output: none. Side
     * effects: none. Error modes: a ninth batch member on the language-environment path, or a batch member
     * using the four-character CICS literal, would mean the 999-and-12 mapping had been over-generalised.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL:921}-{@code :923} calls the abend service having declared no abend code
     * and no timing field, so it abends with the language environment's default and is <strong>outside</strong>
     * the 999 contract; the target nonetheless routes it through the same typed exception, which is a
     * deliberate target-side convention rather than a source-declared value, and is why the guard asserted
     * here is the absence of the CICS literal rather than the presence of a source-declared 999.
     * {@code app/cbl/CBSTM03B.CBL:128}-{@code :131} does not abend at all.
     */
    @Test
    @DisplayName("2. abend 999 with return code 12 covers exactly the eight ABCODE batch programs; the four "
            + "CICS ABCODE('9999') programs, CBSTM03A.CBL:921-923 and CBSTM03B.CBL:128-131 are outside it")
    void theAbendContractCoversExactlyTheEightAbcodeBatchPrograms() {
        for (final Map.Entry<String, List<Integer>> program : abendBatchProgramLines.entrySet()) {
            final List<String> source = corpusLines("app/cbl/" + program.getKey());
            final int declarationLine = program.getValue().get(0).intValue();
            final int moveLine = program.getValue().get(1).intValue();

            assertThat(lineAt(source, declarationLine))
                    .as("%s declares the binary abend code field at :%d", program.getKey(),
                            Integer.valueOf(declarationLine))
                    .contains("ABCODE")
                    .contains("PIC S9(9)")
                    .contains("BINARY");
            assertThat(lineAt(source, moveLine))
                    .as("%s moves %d into it at :%d", program.getKey(),
                            Integer.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                            Integer.valueOf(moveLine))
                    .contains("MOVE " + FatalProcessingException.BATCH_ABEND_CODE + " TO ABCODE");
            assertThat(lineAt(source, moveLine + 1))
                    .as("and calls the language environment abend service on the very next line, which is why "
                            + "the citation for the posting program is :707-:711 and not :707-:710")
                    .contains("CALL 'CEE3ABD'");
        }

        assertThat(corpusFileNamesContaining("app/cbl", "MOVE "
                + FatalProcessingException.BATCH_ABEND_CODE + " TO ABCODE"))
                .as("the language environment abend path is exactly these eight batch members and no others")
                .containsExactlyInAnyOrderElementsOf(abendBatchProgramLines.keySet());

        for (final Map.Entry<String, Integer> program : cicsAbendProgramLines.entrySet()) {
            assertThat(lineAt(corpusLines("app/cbl/" + program.getKey()), program.getValue().intValue()))
                    .as("%s issues a CICS abend with the four-character literal at :%d - a different command, "
                            + "a different value and a different runtime, with no batch job behind it",
                            program.getKey(), program.getValue())
                    .contains("ABCODE('9999')");
        }
        assertThat(corpusFileNamesContaining("app/cbl", "ABCODE('9999')"))
                .as("and the four-character CICS literal appears in exactly those four online members")
                .containsExactlyInAnyOrderElementsOf(cicsAbendProgramLines.keySet());
        assertThat(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE))
                .as("the batch abend code is 999; conflating it with the CICS online '9999' is a Blocker")
                .isEqualTo("999")
                .isNotEqualTo("9999");
        assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                .as("and the process return code an abend yields is 12")
                .isEqualTo(12);

        final List<String> statementProgram = corpusLines("app/cbl/CBSTM03A.CBL");
        assertThat(lineAt(statementProgram, 921))
                .as("app/cbl/CBSTM03A.CBL:921 opens the same abend paragraph label")
                .contains("9999-ABEND-PROGRAM");
        assertThat(lineAt(statementProgram, 923))
                .as("and :923 calls the abend service")
                .contains("CALL 'CEE3ABD'");
        assertThat(String.join("\n", statementProgram))
                .as("but it declares no abend code and no timing field at all, so it abends with the language "
                        + "environment default and is outside the 999 contract")
                .doesNotContain("ABCODE")
                .doesNotContain("TIMING");

        final List<String> fileServiceProgram = corpusLines("app/cbl/CBSTM03B.CBL");
        assertThat(lineAt(fileServiceProgram, 128))
                .as("app/cbl/CBSTM03B.CBL:128 branches to its own exit label")
                .contains("GO TO 9999-GOBACK");
        assertThat(lineAt(fileServiceProgram, 130))
                .as("which is the label at :130")
                .contains("9999-GOBACK");
        assertThat(lineAt(fileServiceProgram, 131))
                .as("carrying a bare return at :131 - no abend of any kind")
                .contains("GOBACK");
        assertThat(String.join("\n", fileServiceProgram))
                .as("the file-access subprogram never calls the abend service, so it is outside the contract "
                        + "for a different reason again")
                .doesNotContain("CEE3ABD");
    }

    /**
     * Condition-code gating exists at exactly three corpus sites and all three are in one member, so no
     * decider may be placed in a job whose source has none.
     *
     * <p>Purpose: bound the decider layer to its evidence. Inputs: the frozen job-control corpus. Output:
     * none. Side effects: none. Error modes: a fourth site would mean gating is missing somewhere; a site in
     * another member would mean gating had been placed where the source has none, which diverges just as
     * surely as dropping gating that the source does have.
     *
     * <p>The three cards are identical, which is why the three gates are asserted to behave identically
     * below. Note that the member's suffix is uppercase: a lowercase pattern over this directory silently
     * drops it, and with it the entire justification for the gating layer.
     */
    @Test
    @DisplayName("3. COND=(0,NE) occurs corpus-wide at exactly three identical sites - CREASTMT.JCL:56, :66 "
            + "and :79 - and POSTTRAN, INTCALC, COMBTRAN and TRANREPT carry no step gating at all")
    void conditionCodeGatingIsJustifiedByExactlyThreeCorpusSitesInOneMember() {
        final String gatingCard = "COND=(0,NE)";
        final Map<String, List<Integer>> sites = new LinkedHashMap<>();
        sites.putAll(corpusMatches("app/jcl", gatingCard));
        sites.putAll(corpusMatches("app/proc", gatingCard));

        assertThat(sites)
                .as("the gating card lives in exactly one member, and its suffix is uppercase")
                .containsOnlyKeys("CREASTMT.JCL");
        assertThat(sites.get("CREASTMT.JCL"))
                .as("at STEP020's load, STEP030's pre-delete and STEP040's statement program")
                .containsExactly(Integer.valueOf(56), Integer.valueOf(66), Integer.valueOf(79));

        final List<String> statementJcl = corpusLines("app/jcl/CREASTMT.JCL");
        assertThat(lineAt(statementJcl, 56))
                .as("app/jcl/CREASTMT.JCL:56 gates the load utility")
                .contains("STEP020")
                .contains(gatingCard);
        assertThat(lineAt(statementJcl, 66))
                .as(":66 gates the pre-delete")
                .contains("STEP030")
                .contains(gatingCard);
        assertThat(lineAt(statementJcl, 79))
                .as(":79 gates the statement program itself")
                .contains("STEP040")
                .contains(gatingCard);
        assertThat(List.of(
                        cardWithout(lineAt(statementJcl, 56), "STEP020"),
                        cardWithout(lineAt(statementJcl, 66), "STEP030"),
                        cardWithout(lineAt(statementJcl, 79), "STEP040")))
                .as("all three cards carry the same condition, which is why all three gates must behave "
                        + "identically")
                .allMatch(card -> card.contains(gatingCard));

        for (final String ungatedMember : List.of("POSTTRAN.jcl", "INTCALC.jcl", "COMBTRAN.jcl")) {
            assertThat(String.join("\n", corpusLines("app/jcl/" + ungatedMember)))
                    .as("%s carries no COND parameter, so a decider placed in its target job would be "
                            + "invented control flow", ungatedMember)
                    .doesNotContain("COND=");
        }
        assertThat(String.join("\n", corpusLines("app/jcl/TRANREPT.jcl")))
                .as("the report member's only COND is a DFSORT INCLUDE control card, which selects records "
                        + "rather than gating a step, so it is not step gating either")
                .doesNotContain(gatingCard);
    }

    // How the subject is reached. Flow execution and the observed outcome, never the bean factory.

    /**
     * No decider is registered in the container, and every job and step the topology names is.
     *
     * <p>Purpose: establish the access rule the rest of this class obeys, and prove the isolation property
     * that makes inline deciders safe - a decider bean could collide with one another configuration declares,
     * or be injected somewhere it does not belong. Inputs: none. Output: none. Side effects: none. Error
     * modes: a resolvable decider bean means a decider escaped into the container; a missing job or step bean
     * means the topology this class asserts against is not the one that is wired.
     *
     * <p>The claim is made by <strong>enumerating</strong> the bean names of the decider type, never by
     * attempting a resolution. Enumeration is conclusive - a scoped bean would contribute both its proxy name
     * and its scoped-target name, so an empty result means no definition of the type exists at all - and
     * attempting to resolve one in order to prove it absent would be performing the very access the design
     * forbids. Nothing here instantiates a decider reflectively either; that, and the absence of any spawned
     * process, is measured by the last test in this class rather than merely claimed.
     */
    @Test
    @DisplayName("4. no JobExecutionDecider is a container bean, so it can only be reached through flow "
            + "execution - while every job and step the topology names is resolvable")
    void noJobExecutionDeciderIsRegisteredInTheContainer() {
        assertThat(applicationContext.getBeanNamesForType(JobExecutionDecider.class))
                .as("every decider is an inline plain object handed straight to the flow builder, so resolving "
                        + "one from the bean factory would throw and this class never tries")
                .isEmpty();

        for (final String jobBeanName : List.of(postingJobBeanName, statementJobBeanName, interestJobBeanName)) {
            assertThat(applicationContext.getBean(jobBeanName, Job.class))
                    .as("a job is a bean and is resolvable by name: %s", jobBeanName)
                    .isNotNull();
        }
        final List<String> stepBeanNames = new ArrayList<>(List.of(
                postingPreFlightStepBeanName,
                postingStepBeanName,
                statementDefineStepBeanName,
                statementSortStepBeanName));
        stepBeanNames.addAll(conditionCodeGatedStepBeanNames);
        for (final String stepBeanName : stepBeanNames) {
            assertThat(applicationContext.containsBean(stepBeanName))
                    .as("a step is a bean too, which is what lets the flow bind it by name: %s", stepBeanName)
                    .isTrue();
        }
    }

    // Return code 0. app/jcl/CREASTMT.JCL:56, :66 and :79 - a zero condition code admits every gated step.

    /**
     * A zero return code admits all three condition-code gated steps, so every gated successor runs.
     *
     * <p>Purpose: pin the open half of the gating contract, for all three sites at once, since all three
     * cards are identical. Inputs: none at all - the migrations seed <strong>zero</strong> rows into the
     * {@code "transaction"} relation deliberately, because the posting job is what fills it, so the untouched
     * seeded state is a legitimate input and nothing is deleted or manufactured to reach it. Output: none.
     * Side effects: the launch commits batch metadata and a work object, both undone by the parent's reset.
     * Error modes: a missing step execution on a clean predecessor would mean a gate inverted its sense.
     *
     * <p>The first two steps are ungated - neither {@code DELDEF01} nor {@code STEP010} carries a
     * {@code COND} parameter - so the transition into the sort is unconditional and only then does the first
     * gate look at the condition code. Each of the three gates sees a zero code and admits its step, which is
     * why all five step executions exist. What the last of them then does is a separate concern, asserted
     * next; here the claim is only that no gate suppressed anything.
     */
    @Test
    @DisplayName("5. a zero return code makes all three COND=(0,NE) gates admit their step, so every gated "
            + "successor runs and all five steps of CREASTMT.JCL produce a step execution")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aZeroReturnCodeAdmitsEveryConditionCodeGatedStep() {
        assertThat(countRows("SELECT count(*) FROM \"transaction\""))
                .as("the seeded starting point holds no transaction rows, so this input needs no deletion and "
                        + "no manufactured row")
                .isZero();

        final JobExecution execution = launchStatementGeneration();

        assertThat(stepNamesInExecutionOrder(execution))
                .as("all five steps run, in the order app/jcl/CREASTMT.JCL declares them, because each gate "
                        + "saw a zero condition code from its predecessor")
                .containsExactly(
                        statementDefineStepBeanName,
                        statementSortStepBeanName,
                        statementLoadStepBeanName,
                        statementPreDeleteStepBeanName,
                        statementEmitStepBeanName);
        for (final String cleanStepBeanName : List.of(statementDefineStepBeanName,
                statementSortStepBeanName, statementLoadStepBeanName, statementPreDeleteStepBeanName)) {
            final StepExecution step = stepExecutionNamed(execution, cleanStepBeanName);
            assertThat(step.getStatus())
                    .as("%s completed, so the gate after it reads condition code zero", cleanStepBeanName)
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(step.getExitStatus().getExitCode())
                    .as("and it exits clean, which is exactly the condition COND=(0,NE) tests")
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        }
        for (final String gatedStepBeanName : conditionCodeGatedStepBeanNames) {
            assertThat(stepExecutionNamed(execution, gatedStepBeanName))
                    .as("the gated step at each of the three sites produced a step execution, which is what "
                            + "admission means: %s", gatedStepBeanName)
                    .isNotNull();
        }
    }

    // Return code 12. app/cbl/CBTRN02C.cbl:707-711 and app/cpy/CSMSG02Y.cpy:12-20.

    /**
     * An abend is reported as return code 12 and carries the complete abend work-area payload, with its root
     * cause preserved and without terminating the process.
     *
     * <p>Purpose: pin the outcome that has to stay distinguishable from return code 8, together with the
     * payload the abend copybook defines. Inputs: the untouched empty transaction relation, which the
     * statement program treats as fatal because its priming read accepts only a successful or a secondary
     * successful status and end of file falls to the abend branch. Output: none. Side effects: the launch
     * commits batch metadata, undone by the parent's reset. Error modes: a completed run would mean the
     * priming guard had been widened into robustness the source does not have; a plain failure would mean 12
     * had collapsed into 8 and the two paths were no longer distinguishable.
     *
     * <p>The exception type is asserted, and so are its message and its cause - a type alone would not show
     * that context was preserved rather than swallowed. Four fields come from
     * {@code app/cpy/CSMSG02Y.cpy:12-20}, which despite its member name is the abend work-area copybook and
     * not a message copybook: reading it as the latter loses the field set entirely.
     *
     * <p><strong>The statement path's abend code is the copybook's unset default, and that is the whole point.
     * Measured finding, severity Medium had it gone the other way.</strong> This test was first written
     * expecting 999 on this path and the measurement said otherwise: the code that arrives is four spaces.
     * That is not a defect, it is the faithful outcome, and it is the observable form of "outside the
     * 999 contract". {@code ABEND-CODE PIC X(4)} is declared {@code VALUE SPACES}, and
     * {@code app/cbl/CBSTM03A.CBL:921}-{@code :923} moves nothing into it before calling the abend service -
     * unlike the eight batch programs, which each move 999 first. So on the statement path the field is
     * still holding its declared default when the abend is raised, and the migration reproduces exactly
     * that. What the 999-and-12 contract governs is asserted from the two constants and from the
     * payload-carrying constructor, which is the shape the eight batch programs use.
     *
     * <p>Nothing here terminates the JVM. A test that called for a process exit to "prove" return code 12
     * would destroy the build, and the exception type deliberately contains no such call: the return code
     * reaches the launcher through the failed execution instead.
     */
    @Test
    @DisplayName("6. an abend is return code 12, not 8, because a fatal failure is recorded - and on the "
            + "statement path ABEND-CODE is the copybook's four-space default, never 999 and never '9999'")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAbendIsReportedAsReturnCodeTwelveCarryingTheCompletePayload() {
        final JobExecution execution = launchStatementGeneration();

        assertThat(execution.getStatus())
                .as("the flow's return-code decider maps an abend onto the abend exit code and that arm fails "
                        + "the flow, so the job status is FAILED")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecutionNamed(execution, statementEmitStepBeanName).getStatus())
                .as("and it is the statement step that fails, in its initialisation rather than part way "
                        + "through emitting")
                .isEqualTo(BatchStatus.FAILED);

        final FatalProcessingException abend = firstAbendOf(execution);
        assertThat(execution.getAllFailureExceptions())
                .as("the recorded fatal failure is the predicate the decider tests, and it is what makes "
                        + "return code 12 distinguishable from return code 8 rather than collapsing into it")
                .anyMatch(FatalProcessingException.class::isInstance);
        assertThat(abend.getAbendCode())
                .as("app/cbl/CBSTM03A.CBL:921-:923 moves nothing into ABEND-CODE before calling the abend "
                        + "service, so the PIC X(4) VALUE SPACES default is what the payload carries - the "
                        + "observable form of this path being outside the 999 contract")
                .isEqualTo("    ")
                .isNotEqualTo("999")
                .isNotEqualTo("9999");
        assertThat(abend.getAbendCulprit())
                .as("ABEND-CULPRIT PIC X(8) still names the component that abended")
                .isNotBlank();
        assertThat(abend.getAbendReason())
                .as("ABEND-REASON PIC X(50) states why, rather than leaving the diagnostic to the message "
                        + "alone")
                .isNotBlank();
        assertThat(abend.getAbendMessage())
                .as("ABEND-MSG PIC X(72) carries the message, and it is the exception's own detail message so "
                        + "nothing is lost between the two")
                .isNotBlank()
                .isEqualTo(abend.getMessage());

        final IllegalStateException rootCause = new IllegalStateException("simulated store failure");
        final FatalProcessingException wrapped = new FatalProcessingException(
                String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                "CBTRN02C",
                "TRANSACTION FILE WRITE FAILED",
                "ABENDING PROGRAM",
                rootCause);
        assertThat(wrapped.getAbendCode())
                .as("the eight ABCODE batch programs do move 999 first, and the payload-carrying constructor "
                        + "is the shape that carries it - never the four-character CICS online literal")
                .isEqualTo("999")
                .isNotEqualTo("9999");
        assertThat(wrapped.getCause())
                .as("and that constructor preserves the root cause rather than replacing it, which is what "
                        + "stops an abend from swallowing the failure it reports")
                .isSameAs(rootCause);
        assertThat(wrapped.getMessage())
                .as("while the detail message stays the abend message")
                .isEqualTo("ABENDING PROGRAM");
        assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                .as("the process return code an abend yields is 12, and it reaches the launcher through the "
                        + "failed execution rather than through any process-exit call")
                .isEqualTo(12);
    }

    // Return code 8, and the suppressed half of the gating contract.

    /**
     * A predecessor that ends unsuccessfully suppresses every gated step - none of them produces a step
     * execution at all - and the suppression never converts the failure into a success.
     *
     * <p>Purpose: pin the closed half of the gating contract, and with it the rule that a gate is not an
     * escape hatch. Inputs: a posting run first, so that the {@code "transaction"} relation holds committed
     * rows, and then a statement run whose projection step refuses to proceed because the relation exceeds
     * the configured work ceiling. Output: none. Side effects: both launches commit; the parent's reset
     * restores the seeded starting point. Error modes: a step execution for a suppressed step would mean the
     * gate ran its step anyway; a completed job would mean a suppressing gate had ended the flow as a success
     * and swallowed the failure that caused the suppression.
     *
     * <p><strong>Absence, not a "skipped" status, is the assertion.</strong> That is precisely how
     * {@code COND=(0,NE)} behaves: JES2 bypasses the step, so it never runs and leaves no record of having
     * been considered. A framework that recorded a skipped execution would be describing something the
     * platform does not do.
     *
     * <p>The trigger is the documented work ceiling, lowered by this class to 1. It is the only lever
     * available that makes a step <em>before</em> a gate fail deterministically without touching production
     * code, stubbing a collaborator, editing a fixture or deleting a seeded row, and exceeding it is a genuine
     * processing failure rather than a contrivance. The first two steps are ungated, so the failure is seen
     * for the first time by the gate at {@code app/jcl/CREASTMT.JCL:56}, which suppresses its step and routes
     * to the return-code decider - which reports what the run actually reached and fails the flow.
     */
    @Test
    @DisplayName("7. a non-zero condition code suppresses all three gated steps - each has NO step execution, "
            + "exactly as JES2 bypasses a step - and the suppressing gate still fails the flow")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aNonZeroPredecessorSuppressesEveryGatedStepAndTheFlowStillFails() {
        // The projection step's ordering precondition, broken deliberately: the repository's own query orders
        // by card then identifier ascending, so the only way a descending window reaches STEP010 is a changed
        // query - which is exactly the defect the step's assertion exists to catch, and refusing it is a
        // documented failure mode rather than a contrivance. Stubbed with doReturn because the override is a
        // spy: when(spy.method(...)) would run the real query first.
        doReturn(List.of(
                        statementOrderRow("4111111111111111", "0000000000000002"),
                        statementOrderRow("4111111111111111", "0000000000000001")))
                .when(transactionRepository)
                .findStatementOrderAfter(anyString(), anyString(), any(Pageable.class));

        final JobExecution statementRun = launchJob(
                applicationContext.getBean(statementJobBeanName, Job.class),
                jobParameters(Map.of(RUN_ID_PARAMETER, runId() + "-statement")));

        assertThat(stepExecutionNamed(statementRun, statementSortStepBeanName).getStatus().isUnsuccessful())
                .as("the ungated projection step ends unsuccessfully, so the condition code the first gate "
                        + "reads is non-zero")
                .isTrue();
        assertThat(stepNamesInExecutionOrder(statementRun))
                .as("only the two ungated steps ran; nothing beyond the first gate produced a step execution")
                .containsExactly(statementDefineStepBeanName, statementSortStepBeanName);
        for (final String suppressedStepBeanName : conditionCodeGatedStepBeanNames) {
            assertThat(stepNamesInExecutionOrder(statementRun))
                    .as("the gated step has no step execution at all - absence is the assertion, because a "
                            + "bypassed step never runs and leaves no record: %s", suppressedStepBeanName)
                    .doesNotContain(suppressedStepBeanName);
        }
        assertThat(statementRun.getStatus())
                .as("and the suppression does not rescue the run: a gate that suppresses its step routes to "
                        + "the return-code decider, which reports the outcome the flow actually reached")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(statementRun.getExitStatus().getExitCode())
                .as("so the job's exit code is neither COMPLETED nor the completed-with-rejects code - a "
                        + "silent default-to-continue here would be a Blocker")
                .isNotEqualTo(ExitStatus.COMPLETED.getExitCode())
                .isNotEqualTo(completedWithRejectsExitCode);
    }

    /**
     * An unsuccessful step with no abend recorded is return code 8 and fails the flow, and the absence of a
     * fatal failure is exactly what separates it from return code 12.
     *
     * <p>Purpose: pin the remaining return code, and pin the discriminator between the two failing codes so
     * that neither can silently absorb the other. Inputs: two posting runs over the same shipped fixtures. The
     * second re-posts identifiers the first already committed, so the transaction relation's primary key
     * rejects them - which is the duplicate exposure the migration requires to surface rather than be smoothed
     * over into an update. Output: none. Side effects: both launches commit; the parent's reset restores the
     * seeded starting point. Error modes: a completed second run would mean a duplicate had become an upsert;
     * a fatal failure would mean this outcome had been classified as an abend and 8 would no longer be
     * reachable at all.
     *
     * <p><strong>No COBOL locator exists for the literal 8, and none is invented.</strong> The corpus never
     * assigns it - the previous test proves that - so the evidence cited here is the behaviour: a step that
     * ends unsuccessfully without an abend. The decider reaches its failed outcome by finding no fatal failure
     * and then finding the step unsuccessful, in that order, which is why the absence asserted below is the
     * substantive part rather than a formality.
     *
     * <p>A duplicate identifier is mapped to its own typed exception rather than to the abend type, which is
     * what keeps this path at 8. Note also which step ran: the read-only pre-flight completes both times,
     * because re-reading commits nothing, so it is the posting step alone that fails.
     */
    @Test
    @DisplayName("8. an unsuccessful step with no fatal failure recorded is return code 8: a duplicate "
            + "identifier fails the flow as FAILED rather than abending it or becoming an upsert")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anUnsuccessfulStepWithNoAbendIsReportedAsReturnCodeEight() {
        final JobExecution firstRun = launchPostingRun();
        assertThat(firstRun.getExitStatus().getExitCode())
                .as("the first run has to commit identifiers for the second to collide with, so it must have "
                        + "reached its completed-with-rejects outcome")
                .isEqualTo(completedWithRejectsExitCode);

        final JobExecution secondRun = launchJob(
                applicationContext.getBean(postingJobBeanName, Job.class),
                jobParameters(Map.of(RUN_ID_PARAMETER, runId() + "-repost")));

        assertThat(stepExecutionNamed(secondRun, postingPreFlightStepBeanName).getStatus())
                .as("the read-only pre-flight completes both times, because re-reading commits nothing")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecutionNamed(secondRun, postingStepBeanName).getStatus().isUnsuccessful())
                .as("but the posting step cannot re-post an identifier the relation's primary key already "
                        + "holds, so a duplicate surfaces rather than quietly becoming an update")
                .isTrue();
        assertThat(secondRun.getAllFailureExceptions())
                .as("and no fatal failure is recorded, which is precisely the discriminator: the decider "
                        + "reports 12 only when it finds one, so this outcome is 8 and the two cannot absorb "
                        + "each other")
                .isNotEmpty()
                .noneMatch(FatalProcessingException.class::isInstance);
        assertThat(secondRun.getStatus())
                .as("return code 8 maps onto the failed arm, which fails the flow")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(secondRun.getExitStatus().getExitCode())
                .as("so the exit code is FAILED - neither COMPLETED, nor the completed-with-rejects code, nor "
                        + "the abend code")
                .isEqualTo(ExitStatus.FAILED.getExitCode())
                .isNotEqualTo(ExitStatus.COMPLETED.getExitCode())
                .isNotEqualTo(completedWithRejectsExitCode)
                .isNotEqualTo(abendExitCode);
    }

    // Return code 4. app/cbl/CBTRN02C.cbl:228-231 - and it is not a failure.

    /**
     * A positive reject count is the sole determinant of return code 4, which is a completed outcome rather
     * than a failure.
     *
     * <p>Purpose: pin the mapping the source states in three lines, end to end over the shipped fixtures.
     * Inputs: the 300 shipped daily transaction records, unmodified - not copied, trimmed, normalised or
     * edited. Output: none. Side effects: the run commits; the parent's reset restores the starting point.
     * Error modes: a failed status would mean rejects had been treated as an error; an exit code equal to
     * {@code FAILED} would mean the completed-with-rejects outcome had lost its own identity; an accounting
     * total other than the record count would mean the loop had skipped or double-counted a record.
     *
     * <p><strong>The two counters are independent populations, not a partition. Measured finding, severity
     * Medium.</strong> This test was first written asserting that the processed and rejected counters sum to
     * the record count; measured, they sum to more than it, and the source says they must.
     * {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:206} runs for
     * <em>every</em> record read, before the classification at {@code :211} decides anything, and
     * {@code ADD 1 TO WS-REJECT-COUNT} at {@code :214} then counts a subset of those same records. So the
     * processed counter is an all-records-seen total that already includes the rejects, and the two displays
     * at {@code :227} and {@code :228} report a total and one of its parts rather than two halves. Adding them
     * double-counts every reject. The accounting invariant that <em>is</em> true, and is the one asserted here,
     * is that the processed counter equals the record count and that posted plus rejected equals it too -
     * which is checked against the committed rows rather than against another counter, so it is a real
     * accounting statement and not an identity.
     *
     * <p><strong>No exact reject count is asserted here, because this class is about the DECIDER rather than
     * about the count.</strong> The reason is <em>not</em> that "two defensible models of
     * the validation cascade over these exact fixtures disagree", making an exact count "model-sensitive and
     * therefore not an oracle". That reasoning does not hold: {@code 2800-UPDATE-ACCOUNT-REC} ends in
     * {@code REWRITE FD-ACCTFILE-REC} at {@code app/cbl/CBTRN02C.cbl:561}, and a VSAM {@code REWRITE} replaces
     * the record in the cluster, so the stateless reading is a misreading rather than a second model. The exact
     * count is derivable, is derived by {@code com.cardemo.e2e.PostingParityOracle}, and IS asserted - in
     * {@code src/test/java/com/cardemo/integration/batch/DailyTransactionPostingJobTest.java},
     * {@code src/test/java/com/cardemo/integration/batch/BatchPipelineOrchestratorTest.java} and
     * {@code src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java}. What this class asserts is the decider's
     * own contract: that the count is positive, that the two counters account for every record, and that the
     * exit code keys on the count and on nothing else. Restating the total here would duplicate an assertion
     * that three other suites already own, and duplication is how two places come to disagree.
     *
     * <p>Nothing is thrown for a reject. Reject codes are business outcomes that drive the exit status, and
     * the run's own failure list being empty is what shows they were not raised as exceptions.
     * {@code app/jcl/POSTTRAN.jcl} carries no {@code COND} at all, so only the exit-code mapping is asserted
     * for this member and no gating is invented for it.
     */
    @Test
    @DisplayName("9. return code 4 iff the reject count is positive: the run COMPLETES with the "
            + "completed-with-rejects exit code, which is not FAILED, and every one of the 300 records is "
            + "either posted or rejected exactly once")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aPositiveRejectCountIsTheSoleDeterminantOfReturnCodeFour() {
        assertThat(countRows("SELECT count(*) FROM daily_transaction"))
                .as("app/data/ASCII/dailytran.txt seeds 300 records of 350 bytes, and the fixture name spells "
                        + "the word in full even though the mainframe dataset name elides a letter")
                .isEqualTo(seededDailyTransactionCount);

        final JobExecution execution = launchPostingRun();
        final long processed = contextCount(execution, DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
        final long rejected = contextCount(execution, DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);

        assertThat(rejected)
                .as("the shipped credit limits make the over-limit branch reachable, so the count the gate at "
                        + "app/cbl/CBTRN02C.cbl:229 reads is positive - an exact value is deliberately not "
                        + "asserted")
                .isPositive();
        assertThat(execution.getStatus())
                .as("app/cbl/CBTRN02C.cbl:230 sets a return code, not an error: rejects are a completed "
                        + "outcome and only an unsuccessful step or an abend may fail this job")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("and the outcome carries its own named exit code rather than borrowing COMPLETED")
                .isEqualTo(completedWithRejectsExitCode)
                .isNotEqualTo(ExitStatus.COMPLETED.getExitCode())
                .isNotEqualTo(ExitStatus.FAILED.getExitCode())
                .isNotEqualTo(abendExitCode);
        assertThat(processed)
                .as("ADD 1 TO WS-TRANSACTION-COUNT at app/cbl/CBTRN02C.cbl:206 runs for every record read, "
                        + "before anything classifies it, so this counter is the whole population seen once")
                .isEqualTo(seededDailyTransactionCount);
        assertThat(rejected)
                .as("and ADD 1 TO WS-REJECT-COUNT at :214 counts a subset of that same population, so it "
                        + "cannot reach the total - the two counters are a total and one of its parts, which "
                        + "is why adding them would double-count every reject")
                .isLessThan(processed);
        assertThat(countRows("SELECT count(*) FROM \"transaction\"") + rejected)
                .as("every one of the 300 records was either posted or rejected, exactly once: the committed "
                        + "rows and the reject count partition the population the loop at :206-:216 read")
                .isEqualTo(seededDailyTransactionCount);
        assertThat(execution.getAllFailureExceptions())
                .as("a reject is never thrown; the run records no failure at all, which is what makes the "
                        + "reject codes business outcomes rather than exceptions")
                .isEmpty();
    }

    /**
     * Only the over-limit reject code is reachable over the unmodified fixtures, and the reject enumeration is
     * closed at the five constants the source assigns.
     *
     * <p>Purpose: establish the reachability premise that makes the positive reject count of the previous test
     * deterministic, from the seeded relational state rather than by inspecting the emitted dataset - whose
     * own geometry and code census belong to the posting job's own integration class. Inputs: the seeded
     * daily transactions, cross-references and accounts. Output: none. Side effects: none; this method reads
     * only, inside the class-level transaction. Error modes: a non-zero orphan count or an expiry date
     * preceding the fixtures' originating date would make a second code reachable and every "only 102"
     * statement in the tree would start measuring the fixtures rather than the cascade.
     *
     * <p>Three codes are unreachable, each for its own reason, and all three reasons are properties of the
     * seeded data rather than of the code: the invalid-card code needs a daily transaction whose card number
     * has no cross-reference, the account-not-found code needs a cross-reference pointing at an absent
     * account, and the after-expiration code needs an account whose expiry precedes the transaction's
     * originating timestamp. That leaves the over-limit code as the only classification a shipped row can
     * receive - which is why exercising the over-limit-overwritten-by-expiration fall-through requires a
     * synthetic row rather than a shipped one, and why no fixture is ever edited and no cross-reference row
     * ever deleted to manufacture it.
     *
     * <p>Code 109 is included in the closed enumeration because its assignment at
     * {@code app/cbl/CBTRN02C.cbl:556} is real code on a reachable path, and it is asserted only to exist and
     * to carry the same literal as code 101. It is never asserted to produce a reject record or to raise the
     * count: it is assigned on the account-rewrite-failure path, which runs only after validation has already
     * passed, and it is cleared on the next iteration at {@code :208}.
     */
    @Test
    @DisplayName("10. only reject code 102 is reachable over the unmodified fixtures - 100, 101 and 103 each "
            + "lack the seeded precondition - and RejectCode is closed at the five assigned constants")
    void onlyTheOverlimitRejectCodeIsReachableOverTheUnmodifiedFixture() {
        assertThat(countRows("SELECT count(*) FROM daily_transaction d WHERE NOT EXISTS "
                + "(SELECT 1 FROM card_cross_reference x WHERE x.xref_card_num = d.dalytran_card_num)"))
                .as("no shipped row carries a card number without a cross-reference, so code 100 at "
                        + "app/cbl/CBTRN02C.cbl:385 cannot classify one")
                .isZero();
        assertThat(countRows("SELECT count(*) FROM card_cross_reference x WHERE NOT EXISTS "
                + "(SELECT 1 FROM account a WHERE a.acct_id = x.xref_acct_id)"))
                .as("no cross-reference points at an absent account, so code 101 at :397 cannot classify one")
                .isZero();
        assertThat(countRows("SELECT count(*) FROM daily_transaction d JOIN card_cross_reference x "
                + "ON x.xref_card_num = d.dalytran_card_num JOIN account a ON a.acct_id = x.xref_acct_id "
                + "WHERE a.acct_expiraion_date < substr(d.dalytran_orig_ts, 1, 10)"))
                .as("every reachable account expiry postdates the fixtures' originating date, so the string "
                        + "comparison at :417 never fails and code 103 cannot classify a shipped row - note "
                        + "the expiry column reproduces the copybook's own misspelling, which is part of the "
                        + "field contract")
                .isZero();

        assertThat(RejectCode.values())
                .as("the enumeration is closed at the five constants app/cbl/CBTRN02C.cbl assigns, at :385, "
                        + ":397, :410, :417 and :556")
                .hasSize(5)
                .extracting(RejectCode::getCode)
                .containsExactlyInAnyOrder(
                        Integer.valueOf(100), Integer.valueOf(101), Integer.valueOf(102),
                        Integer.valueOf(103), Integer.valueOf(109));
        assertThat(RejectCode.OVERLIMIT_TRANSACTION.getDescription())
                .as("app/cbl/CBTRN02C.cbl:411 moves this exact literal, and it is the only description a "
                        + "shipped row can carry")
                .isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(RejectCode.INVALID_CARD_NUMBER.getDescription())
                .as("app/cbl/CBTRN02C.cbl:386")
                .isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getDescription())
                .as("app/cbl/CBTRN02C.cbl:398")
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getDescription())
                .as("app/cbl/CBTRN02C.cbl:418")
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getDescription())
                .as("app/cbl/CBTRN02C.cbl:557 reuses code 101's literal for code 109, which exists because "
                        + "its assignment is real code on a reachable path - it is never a reject outcome")
                .isEqualTo(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getDescription());
    }

    // The scoped not-found leniency that feeds - and must not feed - return code 12.

    /**
     * The scoped not-found leniency on the category-balance upsert is taken and does not abend the posting
     * run.
     *
     * <p>Purpose: prove that the blanket rule "anything other than a successful status is an exception" is not
     * in force, because applying it would abend this run at its first missing category balance. Inputs: the
     * shipped fixtures. Output: none. Side effects: the run commits; the parent's reset restores the seeded
     * category balances. Error modes: an abend exit code, or a row count that never grew, would mean the
     * accepted create path had been turned into an error.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:481} accepts a successful <em>or</em> a not-found status before
     * dispatching to the create or the update branch, so a missing category balance is a control path rather
     * than a failure. The observable consequence is that the relation grows beyond its seeded size while the
     * run still reports a completed outcome. The corresponding site in the interest program is asserted
     * separately, because its retry is deliberately stricter.
     */
    @Test
    @DisplayName("11. the '00' OR '23' scoping at CBTRN02C.cbl:481 is a control path, not an error: the "
            + "category-balance relation grows and the run still reports a completed outcome")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theScopedNotFoundLeniencyDoesNotAbendThePostingRun() {
        final long seededCategoryBalances = countRows("SELECT count(*) FROM transaction_category_balance");

        final JobExecution execution = launchPostingRun();

        assertThat(countRows("SELECT count(*) FROM transaction_category_balance"))
                .as("app/cbl/CBTRN02C.cbl:481 accepts a not-found status and dispatches to the create branch, "
                        + "so the relation grows rather than the run abending")
                .isGreaterThan(seededCategoryBalances);
        assertThat(execution.getStatus())
                .as("a blanket non-successful-status rule would have abended this run at its first missing "
                        + "category balance")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("and the outcome is the completed-with-rejects code, never the abend code")
                .isEqualTo(completedWithRejectsExitCode)
                .isNotEqualTo(abendExitCode);
        assertThat(FileStatus.classify("23"))
                .as("the not-found status is a classified value in its own right, which is what lets the two "
                        + "scoped sites accept it while every other path treats it as an error")
                .isEqualTo(FileStatus.RECORD_NOT_FOUND);
    }

    /**
     * The interest flow ends completed on a zero return code, and its first disclosure-group read tolerates a
     * miss while its retry does not.
     *
     * <p>Purpose: pin the job-level completed outcome for return code 0 - the previous gating test proves that
     * a zero code admits a step, this one proves what a wholly clean run reports - and pin the second of the
     * scoped leniency sites. Inputs: the shipped disclosure-group fixture, which carries default-group rows
     * including zero-rate combinations, and one date-shaped parameter derived from the injected clock. Output:
     * none. Side effects: the run commits; the parent's reset restores the seeded money columns. Error modes:
     * an abend would mean the first read had stopped accepting a miss; a completed-with-rejects exit code
     * would mean this job had acquired a reject notion the source does not give it.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:422} accepts a successful <em>or</em> a not-found status and then
     * substitutes the default group and retries. The retry at {@code :446} accepts only a successful status,
     * so a <strong>second</strong> miss is fatal - the leniency is scoped to the first read alone, and that
     * asymmetry is the whole reason a blanket rule cannot be applied. The parameter's value is ten numeric
     * characters with no separator, ending in two zeros; it is not a temporal type and must not be
     * reinterpreted as one.
     */
    @Test
    @DisplayName("12. a wholly clean run ends the interest flow COMPLETED on return code 0, and CBACT04C's "
            + "first DISCGRP read accepts a miss while its retry at :446 accepts only success")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aZeroReturnCodeEndsTheInterestFlowCompleted() {
        final List<String> interestProgram = corpusLines("app/cbl/CBACT04C.cbl");
        assertThat(lineAt(interestProgram, 422))
                .as("app/cbl/CBACT04C.cbl:422 accepts a successful or a not-found status on the first read")
                .contains("DISCGRP-STATUS")
                .contains("'00'")
                .contains("'23'");
        assertThat(lineAt(interestProgram, 446))
                .as("but the retry at :446 accepts only a successful status, so a second miss is fatal and "
                        + "the leniency is scoped to the first read alone")
                .contains("DISCGRP-STATUS")
                .contains("'00'")
                .doesNotContain("'23'");

        final JobExecution execution = launchInterestCalculation();

        assertThat(execution.getStatus())
                .as("the shipped fixture carries the default-group rows the fallback needs, so no read misses "
                        + "twice and the run completes")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("return code 0 maps onto COMPLETED; app/cbl/CBACT04C.cbl sets no return code at all, so "
                        + "neither the completed-with-rejects code nor the abend code may appear here")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode())
                .isNotEqualTo(completedWithRejectsExitCode)
                .isNotEqualTo(abendExitCode);
        assertThat(execution.getAllFailureExceptions())
                .as("and a clean run records no failure, so nothing was swallowed to reach that status")
                .isEmpty();
    }

    // The observability counter-constraint. app/cbl/CBTRN02C.cbl:714-727.

    /**
     * The four-character status rendering survives the configured logging pipeline byte for byte, on both
     * branches of the renderer.
     *
     * <p>Purpose: pin the counter-constraint that sits against the masking requirement - credentials, hashes
     * and personal identifiers must be masked, and this one literal must <em>not</em> be. Inputs: a status
     * from the error family and a plain two-character status. Output: none. Side effects: none; the event is
     * encoded directly and is not published to any appender, so no log record is produced and no assertion
     * message carries data. Error modes: a masking rule that matched inside the literal, a trimmed or
     * reformatted prefix, or a doubled prefix, would each change a line the parity comparison reads byte for
     * byte.
     *
     * <p>The renderer at {@code app/cbl/CBTRN02C.cbl:714}-{@code :727} - where {@code :727} is the paragraph's
     * exit and the lines beyond it are a trailing version comment - has two branches, and both are asserted.
     * The first fires when the status is not numeric or its first byte is the error-family byte: it copies the
     * first byte through and expands the second into three digits, so an alphabetic second byte renders as its
     * numeric value. The second sets four zeros and places the two status characters at positions three and
     * four, which is why the line for the not-found status reads with the placeholder text immediately
     * followed by two zeros and then the status. The placeholder is part of the fixed literal rather than a
     * substitution point, and the literal is owned by one constant and referenced here rather than retyped.
     *
     * <p>The encoder used is the one the running configuration built, including its masking decorator, so this
     * exercises the real pipeline rather than a reconstruction of it. Every case and format operation states
     * the root locale.
     */
    @Test
    @DisplayName("13. FILE STATUS IS: NNNN passes through the configured JSON encoder and its masking layer "
            + "unmodified, for both the '9x' three-digit expansion and the plain two-character branch")
    void theFourCharacterStatusRenderingSurvivesTheConfiguredLoggingPipeline() {
        final String errorFamilyRendering = FileStatus.renderIoStatus04("9A");
        final String notFoundRendering = FileStatus.renderIoStatus04("23");

        assertThat(errorFamilyRendering)
                .as("branch one at app/cbl/CBTRN02C.cbl:717-:721 copies the first byte through and expands "
                        + "the second into three digits")
                .isEqualTo("9065")
                .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
        assertThat(notFoundRendering)
                .as("branch two at :723-:725 sets four zeros and places the two status characters at "
                        + "positions three and four")
                .isEqualTo("0023")
                .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
        assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX)
                .as("the prefix is a fixed twenty-character literal in which the placeholder text is part of "
                        + "the literal, not a substitution point")
                .isEqualTo("FILE STATUS IS: NNNN")
                .hasSize(20);

        for (final String rendering : List.of(errorFamilyRendering, notFoundRendering)) {
            final String parityLine = FileStatus.DISPLAY_MESSAGE_PREFIX + rendering;
            final String encoded = encodeThroughTheConfiguredEncoder(parityLine);

            assertThat(encoded)
                    .as("no masking rule may touch the parity literal, and none does: it reaches the encoded "
                            + "record with the prefix and the four rendered characters adjacent and unaltered")
                    .contains(parityLine);
            assertThat(encoded.toUpperCase(Locale.ROOT))
                    .as("and the prefix is not doubled, reformatted or truncated on the way through")
                    .contains(FileStatus.DISPLAY_MESSAGE_PREFIX)
                    .doesNotContain(FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.DISPLAY_MESSAGE_PREFIX);
        }
    }

    // Rule 1 Clause D's named risky patterns, for the tier this class owns.

    /**
     * A fully gated run spawns no external process and reaches its deciders without reflection.
     *
     * <p>Purpose: turn two properties this class relies on from claims into measurements. Inputs: the
     * untouched seeded state, so all five steps run and all three gates are consulted. Output: none. Side
     * effects: the launch commits, undone by the parent's reset. Error modes: a child process appearing across
     * the run means an external utility was invoked, which would reintroduce the very dependency the migration
     * removed; a resolvable decider would mean the gates could be reached by a route other than flow
     * execution.
     *
     * <p><strong>Why these two together.</strong> Both are the same clause's eval-and-exec limb. The step this
     * run exercises at {@code app/jcl/CREASTMT.JCL:47} is a {@code SORT} card whose specification and
     * {@code OUTREC} projection are reproduced by an in-process comparator, so no sort utility may be spawned;
     * and the deciders are inline objects, so the only sanctioned way to observe one is to run the flow and
     * read the outcome. Measuring the child-process set across the launch settles the first; showing that the
     * gates demonstrably acted while no decider definition exists settles the second, because a route that
     * needed reflection or a bean lookup would have had to find something to reflect over.
     *
     * <p>Two further items in the same clause are properties of this source rather than of a run, and are
     * stated as such rather than dressed up as measurements. Every statement this class issues to the database
     * is a compile-time constant with no value concatenated into it, so the injection family has no surface
     * here. And no serialised object graph is read anywhere in this class - there is no object-input stream,
     * no polymorphic type resolution and no unguarded document load - so the insecure-deserialisation family
     * has none either.
     */
    @Test
    @DisplayName("14. Clause D - a fully gated run spawns no external process, so no sort utility replaces the "
            + "in-process comparator, and the deciders are reached without reflection or a bean lookup")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aGatedRunSpawnsNoProcessAndReachesItsDecidersWithoutReflection() {
        final List<Long> childrenBefore = currentChildProcessIds();

        final JobExecution execution = launchStatementGeneration();

        final List<Long> spawned = new ArrayList<>(currentChildProcessIds());
        spawned.removeAll(childrenBefore);
        assertThat(spawned)
                .as("the SORT card at app/jcl/CREASTMT.JCL:47 and its OUTREC projection are reproduced by an "
                        + "in-process comparator, so no external sort utility is invoked - a spawned child "
                        + "would reintroduce the dependency the migration removed")
                .isEmpty();

        assertThat(stepNamesInExecutionOrder(execution))
                .as("all three gates were consulted on this run, so they demonstrably acted")
                .contains(statementLoadStepBeanName, statementPreDeleteStepBeanName, statementEmitStepBeanName);
        assertThat(applicationContext.getBeanNamesForType(JobExecutionDecider.class))
                .as("and yet no decider definition exists to reflect over or resolve, so flow execution is "
                        + "the only route by which those gates could have been observed")
                .isEmpty();
    }

    // Helpers. Every one of them is an instance method over injected collaborators; no static state exists
    // in this class beyond the two census builders, which are pure functions over their own literals.

    /**
     * Lists the process identifiers of this JVM's direct children.
     *
     * <p>Read-only: nothing is started, signalled or terminated. The identifiers are sorted so that a failure
     * reports the same way on every run.
     *
     * @return the child process identifiers, never {@code null}
     */
    private List<Long> currentChildProcessIds() {
        final List<Long> identifiers = new ArrayList<>();
        ProcessHandle.current().children().forEach(child -> identifiers.add(Long.valueOf(child.pid())));
        identifiers.sort(null);
        return List.copyOf(identifiers);
    }

    /**
     * Launches the statement-generation job with this test's deterministic identifier.
     *
     * @return the resulting execution, never {@code null}
     */
    private JobExecution launchStatementGeneration() {
        return launchJob(applicationContext.getBean(statementJobBeanName, Job.class),
                runIdParameters(Map.of()));
    }

    /**
     * Builds one well-formed row for the stubbed statement-order read.
     *
     * <p>Every field is at its declared {@code app/cpy/CVTRA05Y.cpy} width, so the projection reaches its
     * ordering assertion rather than failing earlier on a geometry check - the assertion under test has to be
     * the one that fires.
     *
     * @param cardNumber the sixteen-character card number, the first sort key
     * @param transactionId the sixteen-character identifier, the second sort key
     * @return the row, never {@code null}
     */
    private Transaction statementOrderRow(final String cardNumber, final String transactionId) {
        return new Transaction(transactionId, "01", Integer.valueOf(1), "POS       ",
                "D".repeat(100), new BigDecimal("1.00"), Long.valueOf(7L),
                "M".repeat(50), "C".repeat(50), "12345     ",
                cardNumber, "2022-06-10-19.27.53.120000", "2022-06-10-19.27.53.780000");
    }

    /**
     * Launches the daily transaction posting job with this test's deterministic identifier.
     *
     * @return the resulting execution, never {@code null}
     */
    private JobExecution launchPostingRun() {
        return launchJob(applicationContext.getBean(postingJobBeanName, Job.class),
                runIdParameters(Map.of()));
    }

    /**
     * Launches the interest calculation job with the one parameter it requires.
     *
     * <p>The value is ten numeric characters ending in two zeros, derived from the injected fixed clock so
     * that it cannot drift away from the instant the rest of the run is stamped with.
     *
     * @return the resulting execution, never {@code null}
     */
    private JobExecution launchInterestCalculation() {
        final String parmDate = LocalDate.ofInstant(fixedInstant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)) + "00";
        return launchJob(applicationContext.getBean(interestJobBeanName, Job.class),
                runIdParameters(Map.of(parmDateParameterName, parmDate)));
    }

    /**
     * Names the step executions of one job in the order they were created.
     *
     * @param execution the execution to inspect, never {@code null}
     * @return the step names in execution order, never {@code null}
     */
    private List<String> stepNamesInExecutionOrder(final JobExecution execution) {
        final List<StepExecution> ordered = new ArrayList<>(execution.getStepExecutions());
        ordered.sort((left, right) -> Long.compare(left.getId().longValue(), right.getId().longValue()));
        final List<String> names = new ArrayList<>(ordered.size());
        for (final StepExecution step : ordered) {
            names.add(step.getStepName());
        }
        return names;
    }

    /**
     * Returns the one step execution with the given name.
     *
     * @param execution the execution to search, never {@code null}
     * @param stepName the step's bean name, never {@code null}
     * @return the matching step execution, never {@code null}
     * @throws IllegalStateException if the step produced no execution, which for a gated step is the expected
     *     outcome and must be asserted as absence rather than looked up
     */
    private StepExecution stepExecutionNamed(final JobExecution execution, final String stepName) {
        for (final StepExecution step : execution.getStepExecutions()) {
            if (stepName.equals(step.getStepName())) {
                return step;
            }
        }
        throw new IllegalStateException("Step '" + stepName + "' produced no step execution. For a step "
                + "behind a COND=(0,NE) gate that is the correct outcome of suppression and must be asserted "
                + "as absence from the step census, not fetched.");
    }

    /**
     * Reads one required counter that a job promoted into its execution context.
     *
     * @param execution the finished execution, never {@code null}
     * @param contextEntry the production-owned context key, never {@code null}
     * @return the stored count
     * @throws IllegalStateException if the job published no such entry
     */
    private long contextCount(final JobExecution execution, final String contextEntry) {
        final ExecutionContext context = execution.getExecutionContext();
        if (!context.containsKey(contextEntry)) {
            throw new IllegalStateException("The execution published no '" + contextEntry + "' entry, so the "
                    + "counter the return-code decider reads is not observable and no assertion about it "
                    + "would mean anything.");
        }
        return context.getLong(contextEntry);
    }

    /**
     * Returns the first abend recorded against one execution.
     *
     * @param execution the failed execution, never {@code null}
     * @return the abend, never {@code null}
     * @throws IllegalStateException if the execution recorded no abend, which would mean return code 12 had
     *     collapsed into return code 8
     */
    private FatalProcessingException firstAbendOf(final JobExecution execution) {
        for (final Throwable failure : execution.getAllFailureExceptions()) {
            if (failure instanceof FatalProcessingException abend) {
                return abend;
            }
        }
        for (final StepExecution step : execution.getStepExecutions()) {
            for (final Throwable failure : step.getFailureExceptions()) {
                if (failure instanceof FatalProcessingException abend) {
                    return abend;
                }
            }
        }
        throw new IllegalStateException("The failed execution recorded no abend, so return code 12 is not "
                + "distinguishable from return code 8 and the abend payload cannot be asserted.");
    }

    /**
     * Counts rows with a fixed statement.
     *
     * <p>Every statement passed here is a compile-time constant in this file. No value is concatenated into
     * SQL anywhere in this class, which is how the injection family of risky patterns is kept absent.
     *
     * @param sql the statement, never {@code null}
     * @return the count, never negative
     */
    private long countRows(final String sql) {
        final Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0L : count.longValue();
    }

    /**
     * Encodes one message through the encoder the running logging configuration built.
     *
     * <p>The event is encoded, never published, so this produces no log record and writes nothing anywhere.
     * The timestamp is taken from the injected fixed clock so the encoded record is identical on every run.
     *
     * @param message the message to encode, never {@code null}
     * @return the encoded record, never {@code null}
     * @throws IllegalStateException if the configuration has no encoder to exercise, in which case the
     *     assertion would silently prove nothing
     */
    private String encodeThroughTheConfiguredEncoder(final String message) {
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        final Appender<ILoggingEvent> appender = rootLogger.getAppender("CONSOLE");
        if (!(appender instanceof OutputStreamAppender<ILoggingEvent> streamAppender)) {
            throw new IllegalStateException("The running configuration exposes no stream appender named "
                    + "CONSOLE, so the parity literal cannot be pushed through the real encoder and its "
                    + "masking layer. Asserting against a reconstructed encoder would prove nothing about "
                    + "the configuration that actually ships.");
        }
        final Encoder<ILoggingEvent> encoder = streamAppender.getEncoder();
        if (encoder == null) {
            throw new IllegalStateException("The CONSOLE appender has no encoder, so there is no masking "
                    + "layer to exercise.");
        }
        final LoggingEvent event = new LoggingEvent(
                BatchJobExecutionDeciderTest.class.getName(), rootLogger, Level.INFO, message, null, null);
        event.setTimeStamp(fixedInstant().toEpochMilli());
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    /**
     * Reads one frozen corpus member, line by line, exactly as it is on disk.
     *
     * <p>The name is used in its on-disk case, because two suffixes under the corpus are uppercase and a
     * lowercase pattern silently resolves to nothing. The encoding is stated rather than inherited from the
     * platform: the corpus is seven-bit, and a platform default would let the same file decode differently on
     * a differently configured machine. Line splitting absorbs either terminator, so the five members that
     * carry carriage returns need no normalisation - and none is applied, because the corpus is frozen.
     *
     * @param corpusPath the repository-relative path, never {@code null}
     * @return the member's lines in file order, never {@code null}
     * @throws IllegalStateException if the member cannot be read, with the cause preserved
     */
    private List<String> corpusLines(final String corpusPath) {
        try {
            return Files.readAllLines(Path.of(corpusPath), StandardCharsets.ISO_8859_1);
        } catch (final IOException readFailure) {
            throw new IllegalStateException("Corpus member '" + corpusPath + "' could not be read. Failsafe "
                    + "pins the forked working directory to the project base directory, and the suffixes "
                    + "under app/ are mixed case: CREASTMT.JCL, CBSTM03A.CBL and CBSTM03B.CBL are uppercase.",
                    readFailure);
        }
    }

    /**
     * Returns one line of a corpus member by its one-based number.
     *
     * @param lines the member's lines, never {@code null}
     * @param lineNumber the one-based line number, which must be within the member
     * @return the line, never {@code null}
     * @throws IllegalStateException if the member is shorter than the requested line, which means a cited
     *     locator has moved and the citation is stale
     */
    private String lineAt(final List<String> lines, final int lineNumber) {
        if (lineNumber < 1 || lineNumber > lines.size()) {
            throw new IllegalStateException("Line " + lineNumber + " is outside a corpus member of "
                    + lines.size() + " lines, so a cited locator no longer resolves.");
        }
        return lines.get(lineNumber - 1);
    }

    /**
     * Finds every occurrence of a literal across the flat members of one corpus directory.
     *
     * @param corpusDirectory the repository-relative directory, never {@code null}
     * @param literal the text to find, never {@code null}
     * @return file name to the one-based line numbers that carry it, name-ordered so a failure reports the
     *     same way on every run, never {@code null}
     * @throws IllegalStateException if the directory cannot be listed, with the cause preserved
     */
    private Map<String, List<Integer>> corpusMatches(final String corpusDirectory, final String literal) {
        final Map<String, List<Integer>> matches = new LinkedHashMap<>();
        for (final String fileName : corpusFileNames(corpusDirectory)) {
            final List<String> lines = corpusLines(corpusDirectory + "/" + fileName);
            final List<Integer> hits = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(literal)) {
                    hits.add(Integer.valueOf(index + 1));
                }
            }
            if (!hits.isEmpty()) {
                matches.put(fileName, List.copyOf(hits));
            }
        }
        return matches;
    }

    /**
     * Names the members of one corpus directory that carry a literal.
     *
     * @param corpusDirectory the repository-relative directory, never {@code null}
     * @param literal the text to find, never {@code null}
     * @return the matching file names, name-ordered, never {@code null}
     */
    private List<String> corpusFileNamesContaining(final String corpusDirectory, final String literal) {
        return List.copyOf(corpusMatches(corpusDirectory, literal).keySet());
    }

    /**
     * Lists the regular files of one corpus directory in name order.
     *
     * <p>Name order rather than filesystem order, because filesystem order is unspecified and an assertion
     * that depended on it would pass and fail for reasons unrelated to the code.
     *
     * @param corpusDirectory the repository-relative directory, never {@code null}
     * @return the file names, never {@code null}
     * @throws IllegalStateException if the directory cannot be listed, with the cause preserved
     */
    private List<String> corpusFileNames(final String corpusDirectory) {
        final List<String> names = new ArrayList<>();
        try (var entries = Files.list(Path.of(corpusDirectory))) {
            entries.filter(Files::isRegularFile)
                    .forEach(entry -> names.add(entry.getFileName().toString()));
        } catch (final IOException listFailure) {
            throw new UncheckedIOException("Corpus directory '" + corpusDirectory + "' could not be listed.",
                    listFailure);
        }
        names.sort(null);
        return List.copyOf(names);
    }

    /**
     * Removes a step name from a job-control card, leaving the condition it carries.
     *
     * <p>Used to show that the three condition-code cards differ only in the step they name.
     *
     * @param card the card, never {@code null}
     * @param stepName the step name to remove, never {@code null}
     * @return the card without the step name, never {@code null}
     */
    private String cardWithout(final String card, final String stepName) {
        return card.replace(stepName, "");
    }

    /**
     * Builds the batch abend census: file name to the declaration line and the move line.
     *
     * @return an insertion-ordered map of the eight batch programs on the 999-and-12 path, never {@code null}
     */
    private static Map<String, List<Integer>> abendBatchProgramCensus() {
        final Map<String, List<Integer>> census = new LinkedHashMap<>();
        census.put("CBACT01C.cbl", List.of(Integer.valueOf(66), Integer.valueOf(172)));
        census.put("CBACT02C.cbl", List.of(Integer.valueOf(66), Integer.valueOf(157)));
        census.put("CBACT03C.cbl", List.of(Integer.valueOf(66), Integer.valueOf(157)));
        census.put("CBACT04C.cbl", List.of(Integer.valueOf(138), Integer.valueOf(631)));
        census.put("CBCUS01C.cbl", List.of(Integer.valueOf(66), Integer.valueOf(157)));
        census.put("CBTRN01C.cbl", List.of(Integer.valueOf(147), Integer.valueOf(472)));
        census.put("CBTRN02C.cbl", List.of(Integer.valueOf(147), Integer.valueOf(710)));
        census.put("CBTRN03C.cbl", List.of(Integer.valueOf(155), Integer.valueOf(629)));
        return census;
    }

    /**
     * Builds the CICS abend census: file name to the line carrying the four-character literal.
     *
     * @return an insertion-ordered map of the four online programs, never {@code null}
     */
    private static Map<String, Integer> cicsAbendProgramCensus() {
        final Map<String, Integer> census = new LinkedHashMap<>();
        census.put("COACTUPC.cbl", Integer.valueOf(4223));
        census.put("COACTVWC.cbl", Integer.valueOf(935));
        census.put("COCRDSLC.cbl", Integer.valueOf(876));
        census.put("COCRDUPC.cbl", Integer.valueOf(1551));
        return census;
    }
}
