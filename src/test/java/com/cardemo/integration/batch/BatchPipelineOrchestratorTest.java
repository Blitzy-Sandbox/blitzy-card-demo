/*
 * ******************************************************************
 * Component   : BatchPipelineOrchestratorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Spring Boot 3.5.11, Spring
 *               Batch 5.2.4, Testcontainers 2.0.3)
 * Function    : Proves the COMPOSITION of the migrated batch stream -
 *               POSTTRAN, then INTCALC, then COMBTRAN, then the
 *               parallel pair CREASTMT and TRANREPT - against a real
 *               PostgreSQL 16 and a real LocalStack. Stage
 *               progression, the SYSTRAN generation data dependency,
 *               the split, decider gating, exit-status aggregation,
 *               byte-exact record geometry and the composite health
 *               surface. Never a single stage's internals: those
 *               belong to the five sibling job test classes of this
 *               package and to com.cardemo.unit.
 * Source      : app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl,
 *               app/jcl/COMBTRAN.jcl, app/jcl/CREASTMT.JCL,
 *               app/jcl/TRANREPT.jcl, app/proc/TRANREPT.prc,
 *               app/jcl/OPENFIL.jcl, app/jcl/CLOSEFIL.jcl @ 7756d89
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.State;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.job.flow.support.state.DecisionState;
import org.springframework.batch.core.job.flow.support.state.FlowState;
import org.springframework.batch.core.job.flow.support.state.SplitState;
import org.springframework.batch.core.job.flow.support.state.StepState;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.e2e.PostingParityOracle;
import com.cardemo.batch.jobs.InterestCalculationJob;
import com.cardemo.batch.readers.CombinedTransactionReader;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.HealthIndicators;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Integration test for {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}, the Java replacement for the
 * JES2 job stream, exercised end to end against a real PostgreSQL 16 and a real LocalStack.
 *
 * <h2>What it does</h2>
 *
 * <p>This class tests <strong>composition and nothing else</strong>. Five sibling classes in this package
 * already own the internals of the five stages, so every assertion below is about how the stages are joined:
 * the order they run in, the one data dependency that forces that order, the parallel tail, the gating
 * between them, how their outcomes aggregate, whether the record geometries survive the hand-offs, the health
 * surface the stream runs on, and the single ownership of the byte-level file-status rendering. Where an
 * assertion needs a stage's own counter it reads it from the child execution the orchestrator recorded, rather
 * than re-deriving the stage's behaviour.
 *
 * <p>Two ownership boundaries are deliberate, and naming them is how this class stays one concern wide.
 * {@code com.cardemo.unit.batch.BatchPipelineOrchestratorTest} owns the orchestrator's parameter validation
 * against hostile input, its typed launch refusals and their preserved causes, and the mapping of the four
 * return codes onto the four gate outcomes - none of which needs a container and none of which is restated
 * here. The observability leaf of this tier owns the proof that the production encoder emits the file-status
 * line unmodified. What is left to this class is what only a running stream can show, and that is all it
 * asserts.
 *
 * <p>The stream is {@code POSTTRAN} then {@code INTCALC} then {@code COMBTRAN} then a parallel pair of
 * {@code CREASTMT} and {@code TRANREPT}, and each stage cites the job control it replaces:
 *
 * <ul>
 *   <li><strong>Stage 1</strong> - {@code app/jcl/POSTTRAN.jcl:23} {@code //STEP15 EXEC PGM=CBTRN02C}. Its
 *       reject dataset is {@code AWS.M2.CARDDEMO.DALYREJS(+1)} at {@code app/jcl/POSTTRAN.jcl:38} carrying
 *       {@code LRECL=430} at {@code :36}. The member declares <strong>no {@code COND}</strong>.</li>
 *   <li><strong>Stage 2</strong> - {@code app/jcl/INTCALC.jcl:22}
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}, writing a brand new generation of
 *       {@code AWS.M2.CARDDEMO.SYSTRAN} at {@code app/jcl/INTCALC.jcl:37-41}. Also no {@code COND}.</li>
 *   <li><strong>Stage 3</strong> - {@code app/jcl/COMBTRAN.jcl:22} {@code //STEP05R  EXEC PGM=SORT} and
 *       {@code :41} {@code //STEP10 EXEC PGM=IDCAMS}. Its {@code SORTIN} is a concatenation in card order,
 *       {@code TRANSACT.BKUP(0)} at {@code :24} then {@code SYSTRAN(0)} at {@code :26} - both the
 *       <em>current</em> generation, which is why only the latest interest run ever merges. The sort key is
 *       {@code :28} {@code TRAN-ID,1,16,CH} with {@code :30} {@code SORT FIELDS=(TRAN-ID,A)}, and the load is
 *       {@code :48} {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)}. Also no {@code COND}.</li>
 *   <li><strong>Stage 4, statement branch</strong> - the five steps of {@code app/jcl/CREASTMT.JCL}, which
 *       owns <strong>every {@code COND=(0,NE)} in the corpus</strong>: {@code :56}, {@code :66} and
 *       {@code :79}. Its work cluster is {@code KEYS(32 0) RECORDSIZE(350 350)} at {@code :29-39}.</li>
 *   <li><strong>Stage 4, report branch</strong> - {@code app/proc/TRANREPT.prc:21-22} {@code STEP01R},
 *       {@code :35} {@code STEP05R} and {@code :57} {@code STEP10R}. Its window is
 *       {@code :39-42} {@code PARM-START-DATE,C'2022-01-01'} and {@code PARM-END-DATE,C'2022-07-06'},
 *       applied by {@code :45-46} {@code INCLUDE COND} which is inclusive at both ends, sorted by
 *       {@code :44} {@code SORT FIELDS=(TRAN-CARD-NUM,A)}, emitting {@code LRECL=133} at {@code :76}.
 *       The procedure, not {@code app/jcl/TRANREPT.jcl}, is the authority, and its internal name is
 *       {@code //REPROC PROC} at {@code app/proc/TRANREPT.prc:1} rather than its member name.</li>
 * </ul>
 *
 * <p>Three job-control members of the stream deliberately have <strong>no Java analogue at all</strong>, and
 * this class asserts nothing about them because there is nothing to assert - no job, no step and no endpoint
 * is invented for any of them. {@code app/jcl/CBADMCDJ.jcl:27} runs {@code PGM=DFHCSDUP} to install the CICS
 * resource definitions and is superseded by the security configuration.
 * {@code app/jcl/OPENFIL.jcl:26-30} and {@code app/jcl/CLOSEFIL.jcl:26-30} issue {@code CEMT SET FIL} for
 * exactly five files - {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX} and
 * {@code USRSEC} - to make them available to the online region, and are superseded by the composite health
 * indicators this class does assert. {@code app/jcl/OPENFIL.jcl:1} carries the misspelled job name
 * {@code //OEPNFIL}; it is recorded and never corrected.
 *
 * <p>The generations the hand-offs travel through are sourced too, and there are <strong>seven</strong>
 * generation data group bases rather than six. {@code app/jcl/DEFGDGB.jcl:25-57} defines six of them -
 * {@code TRANSACT.BKUP}, {@code TRANSACT.DALY}, {@code TRANREPT}, {@code TCATBALF.BKUP}, {@code SYSTRAN} and
 * {@code TRANSACT.COMBINED} - and {@code app/jcl/DALYREJS.jcl:21-28} defines the seventh in a member of its
 * own, which is why a count taken from the one member alone comes out short. A {@code (+1)} reference becomes
 * a new object under a monotonically increasing prefix and a {@code (0)} reference becomes the
 * lexicographically greatest prefix that already exists. That is precisely why the concrete key a stage
 * created has to travel forward through the execution context instead of being looked up again:
 * {@code app/jcl/COMBTRAN.jcl:37} writes {@code TRANSACT.COMBINED(+1)} and {@code :48} loads it inside the
 * same job, so re-resolving the relative generation mid-job would read whatever object happened to sort last
 * by then. The hand-off is asserted end to end below rather than assumed.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. This class is bound to Failsafe, not Surefire: the build includes
 * {@code **}{@code /integration/**}{@code /*Test.java} in Failsafe and excludes that tree from Surefire, so
 * the class runs in {@code integration-test} and is reported in {@code verify} even though its name ends in
 * {@code Test}. Its package and directory are therefore load bearing - a copy moved up one level would match
 * neither plugin's includes and would silently never run, reporting a green build with no test output at all.
 *
 * <p><strong>A reachable container runtime is a prerequisite.</strong> The harness starts one PostgreSQL 16
 * container and one LocalStack container, so an absent Docker daemon or socket blocks this tier outright.
 * There is no in-memory substitute: an in-memory database would not validate the migrated schema and an
 * in-memory queue would not exercise FIFO semantics. Where no runtime is available the correct report is that
 * the gate is blocked, never an untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Profile {@code test}, contributed by the harness, which also registers the object-store and queue
 *       endpoints from the running container and generates a single-use token signing key. No address, port
 *       or credential appears anywhere in this file.</li>
 *   <li>Time is the harness's injected fixed UTC {@code java.time.Clock}, pinned to an instant inside the
 *       inclusive {@code 2022-01-01} to {@code 2022-07-06} window of {@code app/proc/TRANREPT.prc:39-42}.
 *       This is not a convenience. Columns 305-330 of {@code app/data/ASCII/dailytran.txt} are 26 blanks on
 *       all 300 records, so the processing timestamp comes entirely from the clock; with a wall clock the
 *       report is empty through both the sort filter and the processor filter, and the emptiness looks like a
 *       defect in the report rather than in the test.</li>
 *   <li>{@code spring.batch.job.enabled: false}, so no job runs on context refresh and every launch here is
 *       explicit. The {@code BATCH_*} metadata tables come from
 *       {@code spring.batch.jdbc.initialize-schema}, never from a fourth Flyway migration.</li>
 *   <li>The three Flyway migrations are a precondition and own all seed state: 50 accounts, 50 cards, 50
 *       cross-references, 50 customers, 300 daily transactions, 51 disclosure groups, 50 category balances,
 *       18 categories, 7 types, 10 users, and <strong>zero</strong> rows in {@code "transaction"}, which the
 *       posting stage is what fills.</li>
 *   <li>Chunk sizes come from {@code carddemo.batch.posttran.chunk-size} and its four siblings; bucket and
 *       queue names from {@code carddemo.aws.s3.*} and {@code carddemo.aws.sqs.*}.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code IllegalStateException} naming {@code Propagation.NOT_SUPPORTED}</dt>
 *   <dd>A launching method lost that annotation. Spring Batch refuses job repository work inside an existing
 *       transaction, and the class-level {@code @Transactional} supplies one.</dd>
 *   <dt>Containers fail to start</dt>
 *   <dd>No Docker socket. See the prerequisite above.</dd>
 *   <dt>{@code ClassNotFoundException} on a Testcontainers module</dt>
 *   <dd>The 2.x line renamed every module artefact, so only the {@code testcontainers}-prefixed coordinates
 *       resolve at 2.0.3 and the managed version must additionally be overridden by property. Both remedies
 *       are required together; either alone still fails.</dd>
 *   <dt>An empty transaction report</dt>
 *   <dd>The wall clock was reached instead of the injected one. See the clock note above.</dd>
 *   <dt>{@code NoSuchBeanDefinitionException} for a decider</dt>
 *   <dd>Expected, and asserted here. Every decider in the stream is an inline object, never a bean.</dd>
 *   <dt>A compilation failure on an unused import</dt>
 *   <dd>{@code -Xlint:all -Werror} at {@code release 25} is fatal on warnings.</dd>
 *   <dt>A generation resolved mid-job rather than carried forward</dt>
 *   <dd>The whole point of the hand-off assertion below. A relative generation re-resolved inside a run can
 *       silently address a different object from the one the previous stage created.</dd>
 * </dl>
 *
 * <h2>Findings, by severity</h2>
 *
 * <dl>
 *   <dt>Blocker - a Testcontainers module coordinate taken from the 1.x line</dt>
 *   <dd>Only the {@code testcontainers}-prefixed module artefacts exist at 2.0.3, and the version the
 *       framework manages must additionally be overridden by property rather than by importing a second bill
 *       of materials. Both remedies are required together; either one alone still fails to resolve. The build
 *       already carries both and this class inherits them, declaring no dependency of its own.</dd>
 *   <dt>Blocker - reading a wall clock anywhere in this tier</dt>
 *   <dd>The report window at {@code app/proc/TRANREPT.prc:41-42} is 2022 and the fixture carries no processing
 *       timestamp of its own, so a wall clock empties the report through both filters and the emptiness reads
 *       as a defect in the report rather than in the test. Remediation: the harness's injected clock, whose
 *       pinning inside the window test 9 asserts as a precondition before it asserts any output.</dd>
 *   <dt>Blocker - conflating the batch abend code with the online one</dt>
 *   <dd>The abending batch programs terminate through the language environment with an abend code of
 *       {@code 999} and a return code of 12, while the online programs issue
 *       {@code EXEC CICS ABEND ABCODE('9999')} - a different mechanism carrying a four character literal.
 *       Neither substitutes for the other. This class asserts return codes only, and never asserts an abend
 *       it does not produce.</dd>
 *   <dt>Blocker - matching the job-control directory case sensitively</dt>
 *   <dd>{@code app/jcl/CREASTMT.JCL} is the one member with an upper case extension, so a {@code *.jcl}
 *       pattern silently drops it and stage 4's statement branch loses its only source. Every citation here is
 *       written in exact on-disk case, which the repository's own citation gate then resolves.</dd>
 *   <dt>Blocker - trusting the published account of the interest loop's final flush</dt>
 *   <dd>The specification's prose describes a flush at end of data. There is none: the second update at
 *       {@code app/cbl/CBACT04C.cbl:219-220} is structurally unreachable, so N accounts receive N-1 updates.
 *       Measured, and asserted by test 7. Remediation, for the root-owned decision log rather than for this
 *       file: correct the prose to match the corpus.</dd>
 *   <dt>High - the stage-4 split contended on job-execution creation. RESOLVED</dt>
 *   <dd><strong>Measured, then fixed.</strong> Both branches always execute, and their outcomes used to
 *       race: {@code spring.batch.jdbc.isolation-level-for-create} was {@code SERIALIZABLE}, so the two
 *       concurrent child launches conflicted and PostgreSQL cancelled one as a pivot, surfacing as a fatal
 *       stage failure and return code 12. Across repeated runs the statement branch lost, then the report
 *       branch lost twice, then neither lost. It was left unfixed here because the stream could not reach
 *       the split at all - stage 3 always halted first, for the unrelated substrate reason that was finding
 *       M-01 - so the race was latent. With M-01 corrected the split is reached on every run and the race
 *       became a reproducible failure, so the second of the three remediations named here was taken:
 *       {@code isolation-level-for-create} is now {@code REPEATABLE_READ}, which refuses a genuine write
 *       conflict but not the false read/write dependency between two unrelated inserts. Serialising the
 *       launches was rejected because it would discard the parallelism the two independent JCL branches
 *       model. The rationale is carried in full in {@code src/main/resources/application.yml}.</dd>
 *   <dt>High - the composed stream could not pass stage 3 in one run. RESOLVED</dt>
 *   <dd><strong>Measured and reproducible, then fixed.</strong> Stage 1 committed its posted transactions,
 *       and stage 3's concatenated input re-read and re-loaded them, so the keyed relation rejected the
 *       repeat and the stage halted. The cause was not a property of the stream but a misconfiguration:
 *       {@code carddemo.batch.combined-transaction-reader.source} defaulted to {@code repository}, so
 *       stage 3's first leg was the very relation it loads into. Finding M-01. The property is now declared
 *       {@code object-storage} and the orchestrator refuses the stage on any other value, so the first leg
 *       is a generation, nothing is re-read, and the stream runs to the end. The duplicate-key exposure the
 *       migration requires to surface is unaffected and remains asserted in the combine job's own suite,
 *       where it is a property of the load rather than of a substrate choice.</dd>
 *   <dt>Medium - the {@code TRANREPT} retention limits disagree</dt>
 *   <dd>{@code app/jcl/DEFGDGB.jcl:37-39} declares {@code LIMIT(5)} and {@code app/jcl/REPTFILE.jcl:22-28}
 *       declares {@code LIMIT(10)} for the same generation base. Resolved to 10 - the one legacy
 *       inconsistency actually resolved, because a single lifecycle value has to be chosen.</dd>
 *   <dt>Medium - {@code app/jcl/CREASTMT.JCL} carries two defects that are preserved, not repaired</dt>
 *   <dd>The corrupted data definition at {@code :90}, and the record length disagreeing between the
 *       pre-delete step at {@code :69} and the execution step at {@code :94}. Repairing either would change
 *       behaviour the parity comparison is measured against.</dd>
 *   <dt>Low - {@code app/jcl/OPENFIL.jcl:1} misspells its own job name</dt>
 *   <dd>{@code //OEPNFIL}. Retained.</dd>
 * </dl>
 *
 * <h2>Not available</h2>
 *
 * <ol>
 *   <li><strong>The expected-output baseline for byte-level parity.</strong> An exhaustive search of the
 *       repository for captured legacy output found only dataset-definition job control and no captured data,
 *       and a byte-size sweep for the 430, 133, 100 and 80 byte artefacts returned nothing. What is needed is
 *       a captured end-to-end output set taken from the legacy system at a known input state. No baseline file
 *       is created here: a baseline produced by running this implementation would be circular, and
 *       hand-simulating the posting program would be a second implementation masquerading as an oracle.
 *       Every geometry assertion below is therefore <em>structural</em> - record length, record count and
 *       reason code - and never a byte-for-byte comparison against invented content.</li>
 *   <li><strong>File status {@code '35'}.</strong> Neither the literal nor the corresponding response code
 *       occurs anywhere in {@code app/cbl}, so no behaviour exists to reproduce and no test is invented for
 *       it. What is needed is a legacy occurrence to derive the behaviour from.</li>
 *   <li><strong>The queue-listener ownership boundary.</strong> Whether the listener that replaces the JES2
 *       internal reader belongs with the cloud configuration or with the orchestrator is not settled by any
 *       source artefact, and the two authored specifications contradict each other. What is needed is a
 *       single authoritative placement decision recorded in the root-owned decision log. This class asserts
 *       only what the authored orchestrator actually contains and presumes neither side. The queue itself is
 *       sourced and real: {@code app/csd/CARDDEMO.CSD:499-505} defines
 *       {@code TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)
 *       RECORDFORMAT(FIXED) DISPOSITION(MOD)}, which becomes one FIFO queue whose message body reproduces
 *       the 80 byte parameter record, reached only through LocalStack.</li>
 * </ol>
 *
 * <h2>Preserved legacy behaviour that is cited but never asserted to have an effect</h2>
 *
 * <p>Two artefacts of the interest program are context for the assertions here and are neither defects nor
 * observable outcomes. {@code app/cbl/CBACT04C.cbl:518-520} is an empty but genuinely reachable paragraph,
 * performed at {@code :216}; it produces no effect and none is asserted. {@code app/cbl/CBACT04C.cbl:219-220}
 * is a structurally unreachable second account update: the loop at {@code :188} tests before every iteration
 * and {@code MOVE 'Y' TO END-OF-FILE} occurs at exactly one line, {@code :340}, so the flush at {@code :196}
 * is the only one that ever fires and it fires one time fewer than there are accounts. That consequence
 * <em>is</em> asserted, because it is the stream's most easily lost behaviour.
 */
class BatchPipelineOrchestratorTest extends AbstractBatchIntegrationTest {

    /**
     * The composed pipeline job, injected by bean name so that no other assignable job can be bound.
     *
     * <p>This is the whole stream: four sequential stages and the parallel tail, with a gate after each.
     */
    @Autowired
    @Qualifier("batchPipelineJob")
    private Job batchPipelineJob;

    /**
     * The stage-4 flow, injected by bean name.
     *
     * <p>Injected as the production object rather than rebuilt, so that the split assertions are about what
     * the orchestrator composed and not about a local imitation of it.
     */
    @Autowired
    @Qualifier("batchPipelineStatementReportSplitFlow")
    private Flow batchPipelineStatementReportSplitFlow;

    /**
     * The composed pipeline flow, injected by bean name, for the structural assertions.
     */
    @Autowired
    @Qualifier("batchPipelineFlow")
    private Flow batchPipelineFlow;

    /** Used to resolve production step beans by name and to prove that no decider is a bean. */
    @Autowired
    private ApplicationContext applicationContext;

    /** Reaches the child executions the orchestrator recorded the identifiers of. */
    @Autowired
    private JobExplorer jobExplorer;

    /**
     * Reads committed relational state outside the test transaction.
     *
     * <p>It has to be a template rather than the persistence context, because a stage commits on its own
     * transactions and a persistence-context read would either join this test's transaction or see a stale
     * first-level cache. Nothing in this class writes through it: the stages are the only writers.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Reads back the fixed-width objects the stages emit, so their geometry can be measured. */
    @Autowired
    private S3Client s3Client;

    /** The Actuator health surface, resolved through the context rather than over HTTP. */
    @Autowired
    private HealthEndpoint healthEndpoint;

    /** The registered health contributors, used to prove the probe groups resolve to real members. */
    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    /** The one versioned generation bucket, bound from the same key every production component binds. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** The statements bucket, bound from the same key the statement stage binds. */
    @Value("${carddemo.aws.s3.statements-bucket}")
    private String statementsBucket;

    /**
     * The {@code TRANSACT.BKUP} generation prefix, bound from the key the combined reader itself binds.
     *
     * <p>Bound rather than restated as a literal, unlike the four prefixes this class only ever <em>reads</em>
     * under. This one is a <em>write</em> target: {@link #seedBackupGeneration()} plants an object beneath it
     * and stage 3 then has to find it, so a literal that drifted from the configured value would land the
     * object where nothing looks for it and surface as an unallocatable {@code SORTIN} rather than as a
     * configuration mismatch.
     */
    @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:gdg/transact-bkup}")
    private String backupPrefix;

    // Contract values. Every one is an immutable INSTANCE field rather than a static constant, because
    // the harness documents a hard limit of two static fields in this package and both of them are
    // containers. Nothing here widens that budget, and nothing here is mutable.
    //
    // The bean names and execution-context entry names are restated as literals rather than referenced,
    // because the orchestrator declares them package-private in com.cardemo.batch.jobs and this class
    // sits in a different package. Each one is read through a helper that fails loudly when the entry is
    // absent, so a rename in the orchestrator surfaces as a named failure here rather than as a quietly
    // defaulted zero. Where a constant IS reachable it is borrowed rather than retyped, so that the two
    // tiers cannot drift: see the reject geometry and the two posting counters below.

    /** Bean name of the pipeline job - the stream of {@code app/jcl}'s five driving members. */
    private final String pipelineJobBeanName = "batchPipelineJob";

    /** Bean name of the composed pipeline flow. */
    private final String pipelineFlowBeanName = "batchPipelineFlow";

    /** Bean name of the stage-4 flow that holds the split. */
    private final String splitFlowBeanName = "batchPipelineStatementReportSplitFlow";

    /** Bean name of stage 1's launcher step - {@code app/jcl/POSTTRAN.jcl:23}. */
    private final String postTranStepBeanName = "batchPipelinePostTranStep";

    /** Bean name of stage 2's launcher step - {@code app/jcl/INTCALC.jcl:22}. */
    private final String intCalcStepBeanName = "batchPipelineIntCalcStep";

    /** Bean name of stage 3's launcher step - {@code app/jcl/COMBTRAN.jcl:22} and {@code :41}. */
    private final String combTranStepBeanName = "batchPipelineCombTranStep";

    /** Bean name of stage 4's statement launcher step - the five steps of {@code app/jcl/CREASTMT.JCL}. */
    private final String creaStmtStepBeanName = "batchPipelineCreaStmtStep";

    /** Bean name of stage 4's report launcher step - the three steps of {@code app/proc/TRANREPT.prc}. */
    private final String tranReptStepBeanName = "batchPipelineTranReptStep";

    /** Flow name of the statement branch inside the split. */
    private final String creaStmtBranchFlowName = "batchPipelineCreaStmtBranchFlow";

    /** Flow name of the report branch inside the split. */
    private final String tranReptBranchFlowName = "batchPipelineTranReptBranchFlow";

    /** Common prefix of every execution-context entry the orchestrator publishes. */
    private final String pipelineContextPrefix = "carddemo.pipeline.";

    /** Execution-context infix of stage 1. */
    private final String postTranInfix = "posttran";

    /** Execution-context infix of stage 2. */
    private final String intCalcInfix = "intcalc";

    /** Execution-context infix of stage 3. */
    private final String combTranInfix = "combtran";

    /** Execution-context infix of stage 4's statement branch. */
    private final String creaStmtInfix = "creastmt";

    /** Execution-context infix of stage 4's report branch. */
    private final String tranReptInfix = "tranrept";

    /** Aggregate return-code entry: the highest code any completed stage reported. */
    private final String aggregateReturnCodeEntry = "carddemo.pipeline.returnCode";

    /** Aggregate outcome entry: the exit code the aggregate return code maps onto. */
    private final String aggregateOutcomeEntry = "carddemo.pipeline.outcome";

    /** The one {@code SYSTRAN} key stage 3 must read - the object-store form of {@code SYSTRAN(0)}. */
    private final String systranGenerationEntry = "carddemo.pipeline.systran.generation";

    /** Count of {@code SYSTRAN} generation keys stage 2 published. */
    private final String systranKeyCountEntry = "carddemo.pipeline.systran.generation.keys.count";

    /** Prefix of the indexed {@code SYSTRAN} generation keys stage 2 published. */
    private final String systranKeyIndexPrefix = "carddemo.pipeline.systran.generation.keys.";

    /** Outcome of the generation hand-off check between stage 2 and stage 3. */
    private final String systranHandoffEntry = "carddemo.pipeline.systran.handoff";

    /** The key stage 3 actually resolved, recorded whether or not it matched. */
    private final String systranResolvedEntry = "carddemo.pipeline.systran.resolved";

    /** Hand-off outcome when stage 3 read exactly the key stage 2 created. */
    private final String handoffVerified = "VERIFIED";

    /** Gate outcome that carries a clean stage on to the next one. */
    private final String gateProceed = "PROCEED";

    /** Gate outcome that carries a stage bearing rejects on to the next one - return code 4 is not a stop. */
    private final String gateProceedWithRejects = "PROCEED WITH REJECTS";

    /** Gate outcome that stops the stream. */
    private final String gateHalt = "HALT";

    /** The exit code stage 1 publishes when its reject count exceeded zero. */
    private final String exitCodeCompletedWithRejects = "COMPLETED WITH REJECTS";

    /** Return code 0 - the normal outcome. */
    private final int returnCodeCompleted = 0;

    /**
     * Return code 4, set by {@code app/cbl/CBTRN02C.cbl:230} {@code MOVE 4 TO RETURN-CODE} and by nothing
     * else in the corpus, guarded by {@code :229} {@code IF WS-REJECT-COUNT > 0}.
     *
     * <p>That is the <em>only</em> numeric-literal return-code assignment in all 19,254 lines of
     * {@code app/cbl}, and it is not a failure: {@code app/jcl/POSTTRAN.jcl} carries no {@code COND}, so the
     * legacy stream would proceed past it, and so must this one.
     */
    private final int returnCodeCompletedWithRejects = 4;

    /** Return code 8 - an unsuccessful stage, which halts the stream. */
    private final int returnCodeFailed = 8;

    /** The 300 records of {@code app/data/ASCII/dailytran.txt}, consumed by classpath resource name. */
    private final String dailyTransactionFixture = "dailytran.txt";

    /**
     * The ten-character interest date of {@code app/jcl/INTCALC.jcl:22}, {@code PARM='2022071800'}.
     *
     * <p>Eight date digits then two zeros, character data throughout and never a temporal type, because
     * {@code app/cbl/CBACT04C.cbl} concatenates it into a sixteen digit transaction identifier.
     */
    private final String interestDateParameter = "2022071800";

    /** {@code PARM-START-DATE,C'2022-01-01'} - {@code app/proc/TRANREPT.prc:41}. */
    private final String reportStartDate = "2022-01-01";

    /** {@code PARM-END-DATE,C'2022-07-06'} - {@code app/proc/TRANREPT.prc:42}. */
    private final String reportEndDate = "2022-07-06";

    /** Job parameter name of the interest date. */
    private final String parmDateParameter = "parmDate";

    /** Job parameter name of the report window's start. */
    private final String startDateParameter = "startDate";

    /** Job parameter name of the report window's end. */
    private final String endDateParameter = "endDate";

    /** Count of the seeded accounts, and therefore of the key-ordered browse stage 2 walks. */
    private final long seededAccountCount = 50L;

    /**
     * The last account of that browse, and the one stage 2 can never flush.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:196} is the only reachable flush, raised by a control break to a
     * <em>successor</em> row, so no successor means no flush.
     */
    private final long browsesLastAccountId = 50L;

    /** Object-store prefix of the reject generation - {@code AWS.M2.CARDDEMO.DALYREJS}. */
    private final String rejectKeyPrefix = "gdg/dalyrejs/";

    /** Object-store prefix of the interest generation - {@code AWS.M2.CARDDEMO.SYSTRAN}. */
    private final String systranKeyPrefix = "gdg/systran/";

    /** Object-store prefix of the combined generation - {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED}. */
    private final String combinedKeyPrefix = "gdg/transact-combined/";

    /** Object-store prefix of the report generation - {@code AWS.M2.CARDDEMO.TRANREPT}. */
    private final String reportKeyPrefix = "gdg/tranrept/";

    /** Object-store prefix of the per-record transaction images stage 1 emits. */
    private final String transactionImageKeyPrefix = "transact/";

    /**
     * Generation segment of the planted {@code TRANSACT.BKUP} generation.
     *
     * <p>Nineteen digits, which is the width every generation writer in this codebase uses so that
     * lexicographic and generation order coincide. The value is the lowest usable one, so a generation the
     * report branch creates later in the same test always sorts above it.
     */
    private final String plantedGenerationSegment = "0000000000000000001";

    /** Object name the report branch gives the backup it writes, reused so the planted shape is the real one. */
    private final String backupObjectName = "TRANSACT.BKUP";

    /** Content type of a fixed-block generation object: opaque records, not a text document. */
    private final String generationContentType = "application/octet-stream";

    /** Report line length - {@code LRECL=133} at {@code app/proc/TRANREPT.prc:76}. */
    private final int reportLineLength = 133;

    /** Transaction image length - {@code LRECL=350} at {@code app/jcl/INTCALC.jcl:39}. */
    private final int transactionRecordLength = 350;

    /** Statement text line length - {@code LRECL=80} at {@code app/jcl/CREASTMT.JCL:89}. */
    private final int statementTextLineLength = 80;

    /** Statement markup line length - {@code LRECL=100} at {@code app/jcl/CREASTMT.JCL:94}. */
    private final int statementMarkupLineLength = 100;

    /** Work-cluster key length - {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:30}. */
    private final int workClusterKeyLength = 32;

    /**
     * The stray marker that terminates the legacy file-status prefix.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:721} and {@code :725} both execute
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}, so these four characters are part of the emitted
     * literal rather than a placeholder standing in for the status. Only the marker is named here, never the
     * whole prefix: the prefix itself is borrowed from the authored constant so the two cannot drift, and this
     * marker is what the guard in test 13 uses to prove the borrowed needle is still the legacy one.
     */
    private final String strayStatusMarker = "NNNN";

    /** Execution-context entry carrying the report line count stage 4's report branch wrote. */
    private final String reportLineCountEntry = "carddemo.tranrept.report.lineCount";

    /** Execution-context entry carrying the report object key. */
    private final String reportObjectKeyEntry = "carddemo.tranrept.report.objectKey";

    /** Execution-context entry carrying the record count of the backup generation the report stage took. */
    private final String backupRecordCountEntry = "carddemo.tranrept.transact-bkup.recordCount";

    /** Execution-context entry carrying the record count that survived the inclusive date filter. */
    private final String filteredRecordCountEntry = "carddemo.tranrept.transact-daly.recordCount";

    /** Execution-context entry carrying the statement work cluster's declared geometry. */
    private final String workGeometryEntry = "carddemo.creastmt.work.geometry";

    /** Execution-context entry carrying the number of statements stage 4's statement branch emitted. */
    private final String statementCountEntry = "carddemo.creastmt.emit.statementCount";

    /** Execution-context entry carrying the count of reject objects stage 1 emitted. */
    private final String rejectObjectCountEntry = "carddemo.dalyrejs.object.keys.count";

    /** Prefix of the indexed reject object keys stage 1 emitted. */
    private final String rejectObjectKeyPrefix = "carddemo.dalyrejs.object.keys.";

    /** Execution-context entry carrying the number of reject records stage 1 wrote. */
    private final String rejectRecordCountEntry = "carddemo.dalyrejs.record.count";

    // 1 and 2: the composed topology, asserted on the flow objects themselves. No launch is needed for
    // either, because the structure exists as soon as the context has refreshed.

    /**
     * The stream is three sequential launcher steps, then a split of exactly two named branches, with a gate
     * between every pair.
     *
     * <p>Purpose: assert the shape of the composition rather than only its behaviour, so that a stage removed,
     * reordered into a nested flow or quietly promoted out of the split fails here even if a run still happens
     * to succeed. Inputs: the two production flow beans. Output: none. Side effects: none - nothing is
     * launched.
     *
     * <p>Only the public flow API is used: the start state, the state collection, the step names a step state
     * holds and the flows a split or flow state holds. The transition map is protected, and reading it
     * reflectively is exactly the pattern this class asserts absent elsewhere, so the transitions are proved
     * behaviourally by tests 3 and 5 instead.
     *
     * <p>Error modes: a missing stage, a fourth sequential step, a fifth gate, a branch renamed, or a tail
     * that is not a split all fail with a message naming what was resolved.
     */
    @Test
    @DisplayName("1. the stream is POSTTRAN then INTCALC then COMBTRAN then a split of CREASTMT and TRANREPT")
    void theStreamIsThreeSequentialStagesThenASplitOfTwoIndependentBranches() {
        final SimpleFlow pipeline = simpleFlow(batchPipelineFlow, pipelineFlowBeanName);

        final List<String> sequentialStepNames = new ArrayList<>();
        final List<String> nestedFlowNames = new ArrayList<>();
        int decisionStates = 0;
        for (final State state : pipeline.getStates()) {
            if (state instanceof final StepState stepState) {
                sequentialStepNames.addAll(stepState.getStepNames());
            } else if (state instanceof final FlowState flowState) {
                for (final Flow nested : flowState.getFlows()) {
                    nestedFlowNames.add(nested.getName());
                }
            } else if (state instanceof DecisionState) {
                decisionStates++;
            }
        }

        assertThat(sequentialStepNames)
                .as("the sequential head is exactly the three launcher steps of app/jcl/POSTTRAN.jcl:23, "
                        + "app/jcl/INTCALC.jcl:22 and app/jcl/COMBTRAN.jcl:22 with :41 - a fourth would be "
                        + "an invented stage and a missing one would drop a JCL member from the stream. "
                        + "Resolved: %s", sequentialStepNames)
                .containsExactlyInAnyOrder(postTranStepBeanName, intCalcStepBeanName, combTranStepBeanName);
        assertThat(pipeline.getStartState().getName())
                .as("the stream starts at stage 1, because app/jcl/POSTTRAN.jcl is what produces the posted "
                        + "transactions every later stage reads")
                .isEqualTo(stateNameOfStep(pipeline, postTranStepBeanName));
        assertThat(nestedFlowNames)
                .as("the parallel tail enters the stream as the single stage-4 flow, so the two branches are "
                        + "reached through the split and never as two more sequential steps. Resolved: %s",
                        nestedFlowNames)
                .containsExactly(splitFlowBeanName);
        assertThat(decisionStates)
                .as("one gate after each of the four stages: three between the sequential stages and one "
                        + "after the split, which is where the branch outcomes aggregate")
                .isEqualTo(4);

        final SimpleFlow splitFlow = simpleFlow(batchPipelineStatementReportSplitFlow, splitFlowBeanName);
        final List<String> branchNames = new ArrayList<>();
        int splitStates = 0;
        for (final State state : splitFlow.getStates()) {
            if (state instanceof final SplitState splitState) {
                splitStates++;
                for (final Flow branch : splitState.getFlows()) {
                    branchNames.add(branch.getName());
                }
            }
        }
        assertThat(splitStates)
                .as("stage 4 is one split. app/jcl/CREASTMT.JCL and app/proc/TRANREPT.prc are unrelated jobs "
                        + "sharing neither input nor output, which is the whole reason they are parallel")
                .isEqualTo(1);
        assertThat(branchNames)
                .as("the split holds exactly the statement branch and the report branch. Resolved: %s",
                        branchNames)
                .containsExactlyInAnyOrder(creaStmtBranchFlowName, tranReptBranchFlowName);
    }

    /**
     * Every gate in the stream is an inline object and none of them is a bean.
     *
     * <p>Purpose: prove the decision points are reachable only by running the flow, so that no test - here or
     * anywhere else - can fetch one and call it directly, which would assert a decider's arithmetic while
     * proving nothing about the stream it gates. Inputs: the context. Output: none. Side effects: none.
     *
     * <p>The absence is established by querying the container's <em>metadata</em> rather than by asking it for
     * an instance, and both the default query and the one that includes non-singletons without eagerly
     * initialising anything are run, so a decider cannot hide behind a scope or a lazy definition. Nothing
     * here retrieves a decider, which is the point: this class must not hold one even long enough to prove it
     * exists.
     *
     * <p>An absence assertion is worthless if there is nothing to be absent, so the count of decision points
     * is asserted alongside it. Four gates present and zero decider beans is what "inline" means; zero and
     * zero would pass the first assertion while describing a stream with no gating at all.
     *
     * <p>Error modes: publishing any decider as a bean fails this test, and would additionally collide with
     * the inline deciders the five sibling stages already use, because
     * {@code spring.main.allow-bean-definition-overriding} is {@code false}.
     */
    @Test
    @DisplayName("2. no JobExecutionDecider is a bean, so the gates are reachable only through the flow")
    void everyGateIsAnInlineObjectAndNeverABean() {
        assertThat(applicationContext.getBeanNamesForType(JobExecutionDecider.class))
                .as("the four pipeline gates and the three COND=(0,NE) gates of app/jcl/CREASTMT.JCL:56, :66 "
                        + "and :79 are all plain nested objects. A bean would be reachable without running "
                        + "the stream, and would collide with the sibling stages' own inline deciders")
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(JobExecutionDecider.class, true, false))
                .as("including non-singleton definitions and without initialising anything eagerly, so a "
                        + "decider cannot hide behind a scope or a lazy definition")
                .isEmpty();

        int decisionStates = 0;
        for (final State state : simpleFlow(batchPipelineFlow, pipelineFlowBeanName).getStates()) {
            if (state instanceof DecisionState) {
                decisionStates++;
            }
        }
        assertThat(decisionStates)
                .as("and the stream does have gates for those definitions to have been: four decision points "
                        + "with zero decider beans is what inline means, whereas zero and zero would pass the "
                        + "assertions above while describing a stream with no gating at all")
                .isEqualTo(4);
    }

    // 3, 4 and 5: what one real run of the composed stream does.

    /**
     * The stages run in source order, and a stage that rejected records carries the stream on rather than
     * stopping it.
     *
     * <p>Purpose: assert the two properties of the sequential head that a naive translation gets wrong -
     * that the order is real rather than incidental, and that return code 4 is a business outcome rather than
     * a failure. Inputs: the seeded state the three migrations leave. Output: none. Side effects: the run
     * commits, and the harness undoes it around every test method.
     *
     * <p>Order is asserted from the child execution identifiers rather than from clock readings, because the
     * job repository assigns them in launch order and this class must never assert on elapsed time. The
     * identifiers of the launcher steps are asserted the same way.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl} carries no {@code COND} at all, so on the legacy side the stream
     * proceeds past a return code of 4 unconditionally; the gate here publishes
     * {@code PROCEED WITH REJECTS} and the topology routes that to the next stage exactly as
     * {@code PROCEED} is routed. If a decider had been invented for this member, or if 4 had been treated as
     * a stop, stage 2 would have no execution at all and this test would fail on its absence.
     *
     * <p>Error modes: a reordered stream fails on the identifier ordering; a stream that stops on rejects
     * fails on the missing stage-2 execution; a stage that no longer records its outcome fails loudly in the
     * context reader rather than reading a defaulted zero.
     */
    @Test
    @DisplayName("3. the stages run in source order and return code 4 carries the stream on, it does not stop "
            + "it")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stagesRunInSourceOrderAndReturnCodeFourDoesNotStopTheStream() {
        final JobExecution pipeline = launchPipeline();

        assertThat(stageExitCode(pipeline, postTranInfix))
                .as("app/cbl/CBTRN02C.cbl:229-230 sets return code 4 when and only when the reject count "
                        + "exceeded zero, and the fixture drives that branch")
                .isEqualTo(exitCodeCompletedWithRejects);
        assertThat(stageReturnCode(pipeline, postTranInfix))
                .as("the gate maps that exit code onto return code 4")
                .isEqualTo(returnCodeCompletedWithRejects);
        assertThat(stepNamed(pipeline, postTranStepBeanName).getExitStatus().getExitCode())
                .as("and the launcher step publishes the gate outcome that the flow acts on, which for "
                        + "return code 4 is a proceed rather than a halt")
                .isEqualTo(gateProceedWithRejects);

        final StepExecution intCalcStep = stepNamed(pipeline, intCalcStepBeanName);
        assertThat(intCalcStep.getExitStatus().getExitCode())
                .as("stage 2 ran despite stage 1's rejects - this is the assertion that return code 4 is not "
                        + "a failure. app/jcl/INTCALC.jcl carries no COND either, so its own outcome is a "
                        + "plain proceed")
                .isEqualTo(gateProceed);
        assertThat(stageReturnCode(pipeline, intCalcInfix))
                .as("app/cbl/CBACT04C.cbl sets no return code of its own, so a clean interest run is 0")
                .isEqualTo(returnCodeCompleted);

        assertThat(stepNamed(pipeline, combTranStepBeanName))
                .as("stage 3 ran too, which is what makes the sequential head three stages long")
                .isNotNull();

        final long postTranChild = stageExecutionId(pipeline, postTranInfix);
        final long intCalcChild = stageExecutionId(pipeline, intCalcInfix);
        final long combTranChild = stageExecutionId(pipeline, combTranInfix);
        assertThat(postTranChild)
                .as("stage 1's child was launched before stage 2's: the job repository assigns execution "
                        + "identifiers in launch order, so this is the order assertion and no clock is read")
                .isLessThan(intCalcChild);
        assertThat(intCalcChild)
                .as("and stage 2's before stage 3's, which is the ordering app/jcl/INTCALC.jcl:37-41 and "
                        + "app/jcl/COMBTRAN.jcl:25-26 make mandatory rather than stylistic")
                .isLessThan(combTranChild);

        assertThat(stepNamed(pipeline, postTranStepBeanName).getId())
                .as("the launcher steps were created in the same order")
                .isLessThan(stepNamed(pipeline, intCalcStepBeanName).getId());
        assertThat(stepNamed(pipeline, intCalcStepBeanName).getId())
                .as("throughout the sequential head")
                .isLessThan(stepNamed(pipeline, combTranStepBeanName).getId());
    }

    /**
     * Stage 3 reads exactly the generation stage 2 created, handed over as a job parameter before it launches.
     *
     * <p>Purpose: assert the one genuine data dependency in the stream, and assert that it is honoured
     * <em>before stage 3 starts</em> rather than checked after it finishes. Inputs: the seeded state. Output:
     * none. Side effects: the run commits and is undone by the harness.
     *
     * <p>{@code app/jcl/INTCALC.jcl:37-41} allocates a brand new {@code SYSTRAN} generation on every run and
     * {@code app/jcl/COMBTRAN.jcl:25-26} reads {@code SYSTRAN(0)} - the current one. On the object store a next
     * generation is a new prefix that sorts above every existing one and a current generation is the greatest
     * existing prefix, so re-resolving {@code (0)} inside stage 3 would be correct only while nothing else
     * wrote in between. The stream closes that window by passing the resolved generation to the child as
     * {@value com.cardemo.batch.readers.CombinedTransactionReader#SYSTRAN_GENERATION_JOB_PARAMETER} at launch,
     * which narrows stage 3's listing to that one generation and removes the greatest-prefix selection from
     * stage 3 entirely. <strong>That parameter is the assertion that matters here</strong>: the recorded
     * outcome afterwards is corroboration, and on its own it could only ever report a wrong generation that had
     * already been loaded.
     *
     * <p><strong>A generation, not a single key.</strong> Stage 2 writes one object per chunk under one
     * generation prefix, so {@code SYSTRAN(0)} is the whole prefix and any single key beneath it is one chunk
     * of the dataset. Pinning a key would hand stage 3 a fraction of {@code SYSTRAN(0)} and report success, so
     * the pinned value is the prefix and every published key is required to sit beneath it.
     *
     * <p>The pinned value is also asserted to carry no relative generation notation, because a value that still
     * spelled {@code (+1)} or {@code (0)} would mean the resolution had been deferred rather than performed.
     *
     * <p>Error modes: a stream that stopped handing the generation over fails on the absent job parameter; a
     * stage 3 that resolved a different generation fails on the recorded outcome, which records a mismatch
     * rather than substituting one; a stage 2 that published nothing fails on the key count.
     */
    @Test
    @DisplayName("4. stage 3 consumes exactly the SYSTRAN generation stage 2 created, pinned before launch")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theCombineStageConsumesExactlyTheGenerationTheInterestStageCreated() {
        final JobExecution pipeline = launchPipeline();

        final int publishedKeys = contextInt(pipeline, systranKeyCountEntry);
        assertThat(publishedKeys)
                .as("app/jcl/INTCALC.jcl:37-41 allocates a generation on every run, so stage 2 published at "
                        + "least one key for stage 3 to read")
                .isGreaterThanOrEqualTo(1);

        final List<String> keys = new ArrayList<>();
        for (int index = 0; index < publishedKeys; index++) {
            keys.add(contextString(pipeline, systranKeyIndexPrefix + index));
        }
        final String pinnedGeneration = contextString(pipeline, systranGenerationEntry);
        final String resolvedKey = contextString(pipeline, systranResolvedEntry);

        assertThat(pinnedGeneration)
                .as("a carried-forward generation is concrete. Relative generation notation surviving into it "
                        + "would mean the resolution had been deferred to whoever reads it next")
                .doesNotContain("(+1)")
                .doesNotContain("(0)")
                .isNotBlank();
        assertThat(pinnedGeneration)
                .as("and it addresses the interest generation rather than some other prefix")
                .startsWith(systranKeyPrefix);
        assertThat(keys)
                .as("SYSTRAN(0) at app/jcl/COMBTRAN.jcl:26 is the CURRENT GENERATION, not one object of it: "
                        + "stage 2 emits one object per chunk, so every key it published has to sit beneath "
                        + "the pinned generation '%s' or the pin would hand stage 3 a fraction of the "
                        + "dataset. Published: %s", pinnedGeneration, keys)
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).startsWith(pinnedGeneration + "/"));

        final JobExecution combine = childExecution(pipeline, combTranInfix);
        assertThat(combine.getJobParameters()
                        .getString(CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER))
                .as("the generation was handed to stage 3 as a job parameter BEFORE it launched, which is "
                        + "what makes re-resolution impossible rather than merely detectable: stage 3's "
                        + "listing is narrowed to this one generation, so a concurrent run that catalogued a "
                        + "higher one between stage 2 and stage 3 cannot be picked up")
                .isEqualTo(pinnedGeneration);
        assertThat(childExecution(pipeline, postTranInfix).getJobParameters()
                        .getString(CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER))
                .as("and only stage 3 receives it - a parameter added to every stage would be inherited "
                        + "state rather than a hand-off between two named stages")
                .isNull();

        assertThat(resolvedKey)
                .as("stage 3 then opened an object of that exact generation rather than resolving the "
                        + "relative generation again for itself")
                .startsWith(pinnedGeneration + "/");
        assertThat(keys)
                .as("and the object it opened is one stage 2 actually wrote, not a neighbour that happened "
                        + "to sit under the same prefix")
                .contains(resolvedKey);
        assertThat(contextString(pipeline, systranHandoffEntry))
                .as("so the recorded hand-off outcome is a verification rather than a mismatch, an absence "
                        + "or a not-applicable")
                .isEqualTo(handoffVerified);

        final JobExecution interest = childExecution(pipeline, intCalcInfix);
        final int childKeys = (int) contextLong(interest,
                InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY);
        assertThat(childKeys)
                .as("the hand-off is asserted end to end rather than only at its midpoint: stage 2's own "
                        + "execution published the keys, the stream copied them out, and stage 3 read one of "
                        + "them - so the child's count must be the count the stream pinned from")
                .isEqualTo(publishedKeys);
        final List<String> childPublished = new ArrayList<>();
        for (int index = 0; index < childKeys; index++) {
            childPublished.add(contextString(interest,
                    InterestCalculationJob.SYSTRAN_GENERATION_KEYS_INDEX_PREFIX + index));
        }
        assertThat(childPublished)
                .as("and the keys themselves are the same objects, not a re-derivation of them. Stage 2 "
                        + "published %s", childPublished)
                .containsExactlyElementsOf(keys);
        assertThat(childExecution(pipeline, intCalcInfix).getExecutionContext()
                        .getString(InterestCalculationJob.SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY, ""))
                .as("so the generation stage 3 was pinned to is exactly the one stage 2 recorded creating at "
                        + "app/jcl/INTCALC.jcl:37-41, with nothing derived in between")
                .isEqualTo(pinnedGeneration);
    }

    /**
     * Every stage runs, because stage 3 no longer reads the relation it is about to load.
     *
     * <p>Purpose: assert the pipeline's end-to-end flow now that the combine stage takes its concatenated
     * {@code SORTIN} from object storage. Inputs: the seeded state. Output: none. Side effects: the run
     * commits and is undone by the harness.
     *
     * <p><b>Why stage 3 halting with return code 8 is not the expected outcome here.</b> Such a halt is real
     * when {@code carddemo.batch.combined-transaction-reader.source} defaults to {@code repository}: stage 1
     * commits its posted transactions, the first leg is then the load target, stage 3 re-reads and re-loads
     * them, and the keyed relation refuses the repeat. But its cause is a misconfiguration rather than a
     * property of the stream - finding M-01. With the object-storage substrate declared and
     * enforced, the first leg is a generation, nothing is re-read, and the stream runs to the end - which is
     * what {@code app/jcl/COMBTRAN.jcl:L23-L26} followed by {@code :L41-L48} describes.
     *
     * <p>The duplicate-key exposure itself is not lost: it remains asserted where it is genuinely a property
     * of the load rather than of a substrate choice, in the combine job's own suite. Nor are the
     * {@code COND=(0,NE)} bypass semantics this test used to demonstrate - that a gated-out step receives no
     * step execution at all rather than one labelled skipped - which the unit tier asserts directly, on an
     * injected stage outcome, under "return code 8 - the dependent chain halts and nothing downstream runs".
     *
     * <p>Error modes: a stage that did not run fails on the step-name set; a stage 3 that halted again fails
     * on its gate, which would mean the substrate is not the one the deployment declares.
     */
    @Test
    @DisplayName("5. every stage runs: the combine stage reads a generation, not the relation it loads")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyStageRunsOnceTheCombineStageReadsAGeneration() {
        final JobExecution pipeline = launchPipeline();

        assertThat(stepNamed(pipeline, combTranStepBeanName).getExitStatus().getExitCode())
                .as("stage 3 proceeds: its first leg is a TRANSACT.BKUP generation, not the relation "
                        + "app/jcl/COMBTRAN.jcl:48 loads into, so there is no identifier to repeat")
                .isEqualTo(gateProceed);
        assertThat(stageReturnCode(pipeline, combTranInfix))
                .as("a completed stage is return code 0")
                .isEqualTo(returnCodeCompleted);

        // NO ROW-COUNT ASSERTION HERE, DELIBERATELY, and its absence is the point of this test rather than
        // an omission from it. Asserting that the relation still holds exactly what stage 1
        // posted - "the refused load committed nothing" - holds only while
        // carddemo.batch.combined-transaction-reader.source defaults to `repository` and stage 3 therefore
        // halts before loading anything. With the object-storage substrate declared and enforced the stage
        // COMPLETES and loads the interest generation into that same relation, so the relation legitimately
        // holds stage 1's rows plus stage 2's, and that equality would require stage 3 to fail in
        // order to pass. That is an assertion inverting the behaviour it is meant to observe.
        //
        // Neither concern the old assertion carried is lost, and both are asserted where they are a property
        // of the thing under test rather than of a substrate choice. The duplicate-identifier exposure - that
        // a repeated TRAN-ID fails the load as a typed DuplicateRecordException with no row left behind - is
        // asserted by CombineTransactionsJobTest's "a repeated TRAN-ID fails the load as a typed duplicate".
        // The COND=(0,NE) bypass semantics, that a gated-out step receives no step execution at all rather
        // than one labelled skipped, are asserted on an injected stage outcome by the unit tier's "return code
        // 8 - the dependent chain halts and nothing downstream runs". The stage-1 population itself is
        // reconciled in this class by the posting-stage test, which excludes the interest identifier prefix
        // precisely so stage 3's 50 loaded rows are not attributed to stage 1.

        assertThat(stepNames(pipeline))
                .as("all five launchers run: the three sequential stages and both branches of the stage-4 "
                        + "split. Resolved: %s", stepNames(pipeline))
                .containsExactlyInAnyOrder(postTranStepBeanName, intCalcStepBeanName, combTranStepBeanName,
                        creaStmtStepBeanName, tranReptStepBeanName);
        assertThat(findStep(pipeline, creaStmtStepBeanName))
                .as("the statement branch of app/jcl/CREASTMT.JCL now has a step execution")
                .isPresent();
        assertThat(findStep(pipeline, tranReptStepBeanName))
                .as("as does the report branch of app/proc/TRANREPT.prc")
                .isPresent();

        assertThat(contextInt(pipeline, aggregateReturnCodeEntry))
                .as("the aggregate is the highest code any stage reported, and stage 1's rejects make that 4 "
                        + "per app/cbl/CBTRN02C.cbl:229 rather than 0")
                .isEqualTo(returnCodeCompletedWithRejects);
        assertThat(pipeline.getStatus())
                .as("a stream whose only non-zero code is the documented reject code completes")
                .isEqualTo(BatchStatus.COMPLETED);
    }

    // 6 and 7: the two stage outcomes the stream as a whole depends on, read from the child executions
    // the orchestrator recorded rather than re-derived here.

    /**
     * Every record of the daily file is accounted for, and every rejected one bears the over-limit code.
     *
     * <p>Purpose: assert the composition-level property of stage 1 - that the stream neither loses nor
     * duplicates a record, and that the reject population is the one the fixture can actually produce.
     * Inputs: the 300 records of {@code app/data/ASCII/dailytran.txt}, consumed by classpath resource name and
     * never copied, trimmed or re-encoded. Output: none. Side effects: the run commits and is undone.
     *
     * <p><strong>The exact reject count IS asserted, against a derived oracle.</strong> Declining it, on the
     * argument that "two defensible models of the validation cascade over these exact fixtures
     * disagree, because one reads the account once per pass and the other re-reads it per transaction with the
     * cycle accumulators already mutated", does not hold: {@code 2800-UPDATE-ACCOUNT-REC} ends in
     * {@code REWRITE FD-ACCTFILE-REC} at {@code app/cbl/CBTRN02C.cbl:561}, and a VSAM {@code REWRITE} replaces
     * the record in the cluster, so the re-read at {@code :394} returns the mutated accumulators. The
     * once-per-pass reading is not a second defensible model - it is a misreading of what {@code REWRITE}
     * means. Exactly one faithful model exists, {@code com.cardemo.e2e.PostingParityOracle} implements it, and
     * its result is committed under {@code src/test/resources/expected/posttran}. The count asserted below is
     * read from that committed expectation rather than restated here, so this suite and the Gate 1 suites
     * cannot disagree about it.
     *
     * <p>Of the five reject codes only the over-limit one is reachable over unmodified fixtures. The
     * cross-reference and account lookups cannot fail because the fixture's card numbers and account
     * identifiers are all seeded; the expiry comparison cannot fail because every seeded expiry postdates the
     * single originating date the file carries; and the fifth code is assigned only on an account-rewrite
     * failure, on a path that has already validated, so it is never consumed as a reject at all.
     *
     * <p>The reject records are read back from the object store and measured, because the 430 byte geometry is
     * the contract: {@code app/cbl/CBTRN02C.cbl:176-182} declares a 350 byte transaction image plus an 80 byte
     * trailer of a four digit reason and a 76 character description, and {@code app/jcl/POSTTRAN.jcl:36}
     * independently declares {@code LRECL=430}.
     *
     * <p>Error modes: a lost or duplicated record fails the balance assertion; a widened reject population
     * fails the code assertions; a reject record of any other length fails the geometry assertion.
     */
    @Test
    @DisplayName("6. every one of the 300 records is either posted or rejected, and every reject is 102")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void thePostingStageAccountsForEveryFixtureRecordAndRejectsOnlyOverLimitOnes() {
        assertThat(readFixture(dailyTransactionFixture))
                .as("app/data/ASCII/dailytran.txt is the Gate 1 fixture and carries 300 records of 350 bytes")
                .hasSize(300);

        final JobExecution pipeline = launchPipeline();
        final JobExecution posting = childExecution(pipeline, postTranInfix);

        final long processed = contextLong(posting, DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
        final long rejected = contextLong(posting, DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);
        // The posting stage's OWN rows. Stage 3 later loads the interest generation into the same relation,
        // so a bare count would attribute those 50 records to the posting stage and make the reconciliation
        // below fail by exactly that number. Interest identifiers are the ten-character interest date
        // followed by a six-digit suffix (app/cbl/CBACT04C.cbl:473-516), so excluding that prefix isolates
        // the population this assertion is about. Before the substrate fix of finding M-01 the question did
        // not arise, because stage 3 halted before loading anything.
        final long posted = countRows("SELECT count(*) FROM \"transaction\" WHERE tran_id NOT LIKE '"
                + interestDateParameter + "%'");

        assertThat(processed)
                .as("WS-TRANSACTION-COUNT is incremented at app/cbl/CBTRN02C.cbl:206, BEFORE validation runs "
                        + "at :210, so the legacy figure is posted plus rejected and equals the whole file")
                .isEqualTo(300L);
        final long expectedRejects =
                PostingParityOracle.readCommittedExpectation("rejects.txt").size();
        assertThat(expectedRejects)
                .as("the premise: the committed expectation must carry at least one reject, or the assertion "
                        + "below would be satisfied by a stage that rejected nothing")
                .isPositive();
        assertThat(rejected)
                .as("WS-REJECT-COUNT must equal the source-derived expectation exactly. It is positive, which "
                        + "is what makes app/cbl/CBTRN02C.cbl:229 true and return code 4 the outcome - but the "
                        + "exact figure is now asserted rather than declined, because it is derived from the "
                        + "frozen source and fixtures by PostingParityOracle rather than guessed")
                .isEqualTo(expectedRejects);
        assertThat(posted)
                .as("and the posted side equals the expectation too, so neither arm of "
                        + "app/cbl/CBTRN02C.cbl:211-216 can drift while the other absorbs the difference")
                .isEqualTo(PostingParityOracle.readCommittedExpectation("transactions.txt").size());
        assertThat(posted + rejected)
                .as("and nothing is lost or counted twice between the two populations: %d posted plus %d "
                        + "rejected accounts for the whole file", Long.valueOf(posted), Long.valueOf(rejected))
                .isEqualTo(processed);
        assertThat(posted)
                .as("the relation starts empty - the three migrations seed zero transaction rows precisely "
                        + "because the posting stage is what fills it")
                .isGreaterThan(0L);

        final long rejectRecords = contextLong(posting, rejectRecordCountEntry);
        assertThat(rejectRecords)
                .as("the reject writer wrote one record per rejected transaction")
                .isEqualTo(rejected);
        assertThat(contextLong(posting, rejectObjectCountEntry))
                .as("app/jcl/POSTTRAN.jcl:38 allocates one DALYREJS generation per run")
                .isEqualTo(1L);

        final byte[] rejects = objectBytes(batchOutputBucket,
                contextString(posting, rejectObjectKeyPrefix + 0));
        assertThat(rejects.length % RejectCode.REJECT_RECORD_LENGTH)
                .as("app/cbl/CBTRN02C.cbl:176-182 is X(350) plus X(80) and app/jcl/POSTTRAN.jcl:36 declares "
                        + "LRECL=430, so the object is a whole number of 430 byte records and %d bytes is not",
                        Integer.valueOf(rejects.length))
                .isZero();
        assertThat(rejects.length / RejectCode.REJECT_RECORD_LENGTH)
                .as("one 430 byte record per rejected transaction")
                .isEqualTo((int) rejected);

        final String rejectText = new String(rejects, StandardCharsets.US_ASCII);
        final TreeSet<String> reasonCodes = new TreeSet<>();
        for (int record = 0; record < rejects.length / RejectCode.REJECT_RECORD_LENGTH; record++) {
            final int trailerStart = record * RejectCode.REJECT_RECORD_LENGTH
                    + RejectCode.REJECT_TRAN_DATA_LENGTH;
            reasonCodes.add(rejectText.substring(trailerStart,
                    trailerStart + RejectCode.FAIL_REASON_LENGTH));
            assertThat(rejectText.substring(trailerStart + RejectCode.FAIL_REASON_LENGTH,
                            trailerStart + RejectCode.VALIDATION_TRAILER_LENGTH))
                    .as("the 76 character description of record %d is the code's exact literal text, padded "
                            + "rather than truncated", Integer.valueOf(record))
                    .hasSize(RejectCode.FAIL_REASON_DESC_LENGTH)
                    .startsWith(RejectCode.OVERLIMIT_TRANSACTION.getDescription());
        }
        assertThat(reasonCodes)
                .as("only the over-limit code is reachable over unmodified fixtures. Resolved: %s",
                        reasonCodes)
                .containsExactly(RejectCode.OVERLIMIT_TRANSACTION.toFailReasonField());
        assertThat(reasonCodes)
                .as("and none of the other four appears: the two lookups cannot fail against fully seeded "
                        + "reference data, the expiry comparison cannot fail against the fixture's single "
                        + "originating date, and the fifth code is never consumed as a reject")
                .doesNotContain(RejectCode.INVALID_CARD_NUMBER.toFailReasonField(),
                        RejectCode.ACCOUNT_RECORD_NOT_FOUND.toFailReasonField(),
                        RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.toFailReasonField());
    }

    /**
     * Stage 2 leaves the browse's last account unflushed, with the cycle accumulators stage 1 gave it intact.
     *
     * <p>Purpose: assert the stream's most easily lost behaviour, and assert it at the level where it is
     * visible as a consequence rather than as an internal detail. Inputs: the seeded state plus whatever
     * stage 1 commits. Output: none. Side effects: the run commits and is undone.
     *
     * <p>The flush at {@code app/cbl/CBACT04C.cbl:196} is raised by a control break to a <em>successor</em>
     * row, so the final account of the key-ordered browse has no successor and is never flushed. The apparent
     * safety net at {@code :219-220} is structurally unreachable: the loop at {@code :188} tests before every
     * iteration, so the guard at {@code :189} is always true inside the body, and
     * {@code MOVE 'Y' TO END-OF-FILE} occurs at exactly one line, {@code :340}. The consequence is
     * {@code N-1} account updates for {@code N} accounts, and it is a latent divergence rather than a visible
     * one: the skipped account keeps stale cycle accumulators that the <em>next</em> posting cycle's
     * over-limit arithmetic would then read. It is preserved, not repaired.
     *
     * <p>This is only observable at the stream level because stage 1 runs first. The fixtures seed both cycle
     * accumulators at zero, so on their own a flushed and an unflushed account are indistinguishable; stage 1
     * populates all fifty, and stage 2 then zeroes exactly forty-nine of them.
     *
     * <p>The unflushed account's negative cycle debit is worth noting in passing: a negative amount is added
     * to the debit accumulator rather than to the credit one, which is exactly why the over-limit formula
     * subtracts that accumulator, and it is direct evidence that nothing normalises a sign anywhere on the
     * posting path.
     *
     * <p>Error modes: a final flush added to stage 2 fails this test on the count, which is the intended
     * behaviour - the safety net must stay unreachable.
     */
    @Test
    @DisplayName("7. stage 2 flushes 49 of the 50 accounts and leaves the browse's last one stale")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theInterestStageLeavesTheBrowsesLastAccountUnflushed() {
        launchPipeline();

        final Map<Long, List<BigDecimal>> cycles = new TreeMap<>();
        jdbcTemplate.query("SELECT acct_id, acct_curr_cyc_credit, acct_curr_cyc_debit FROM account",
                resultSet -> {
                    cycles.put(Long.valueOf(resultSet.getLong(1)),
                            List.of(resultSet.getBigDecimal(2), resultSet.getBigDecimal(3)));
                });

        assertThat(cycles)
                .as("app/data/ASCII/acctdata.txt seeds exactly fifty accounts and no stage inserts one")
                .hasSize((int) seededAccountCount);

        final List<Long> unflushed = new ArrayList<>();
        for (final Map.Entry<Long, List<BigDecimal>> account : cycles.entrySet()) {
            final boolean bothZero = account.getValue().get(0).signum() == 0
                    && account.getValue().get(1).signum() == 0;
            if (!bothZero) {
                unflushed.add(account.getKey());
            }
        }

        assertThat(unflushed)
                .as("exactly one account escapes the flush at app/cbl/CBACT04C.cbl:196, and it is the last of "
                        + "the key-ordered browse because no successor row raises its control break. The "
                        + "unreachable safety net at :219-220 must stay unreachable. Resolved: %s", unflushed)
                .containsExactly(Long.valueOf(browsesLastAccountId));
        assertThat(cycles.size() - unflushed.size())
                .as("so N-1 of N accounts had both accumulators zeroed by app/cbl/CBACT04C.cbl:353-354")
                .isEqualTo((int) seededAccountCount - 1);

        for (final Map.Entry<Long, List<BigDecimal>> account : cycles.entrySet()) {
            if (account.getKey().longValue() == browsesLastAccountId) {
                continue;
            }
            assertThat(account.getValue().get(0))
                    .as("MOVE 0 TO ACCT-CURR-CYC-CREDIT at app/cbl/CBACT04C.cbl:353 ran for account %s. "
                            + "Compared with compareTo rather than equals, because a NUMERIC(12,2) column "
                            + "returns scale 2 and zero has scale 0", account.getKey())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getValue().get(1))
                    .as("and MOVE 0 TO ACCT-CURR-CYC-DEBIT at :354 for the same account %s; the two are "
                            + "asserted separately so a half-applied reset cannot pass", account.getKey())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        final List<BigDecimal> stale = cycles.get(Long.valueOf(browsesLastAccountId));
        assertThat(stale.get(0).signum() != 0 || stale.get(1).signum() != 0)
                .as("the skipped account keeps the accumulators stage 1 gave it - the seeded value was "
                        + "+0.00 in both, so anything non-zero here can only have come from stage 1. That is "
                        + "the latent divergence which would surface in the next posting cycle's over-limit "
                        + "arithmetic, and it is preserved rather than repaired")
                .isTrue();
    }

    // 8 and 9: stage 4. Reached through the production split flow and the production branch step, because
    // the composed stream cannot pass stage 3 in one run over the seeded state - see the second High
    // finding. Nothing here rebuilds the topology: both objects are the beans the orchestrator published.

    /**
     * Both branches of the split execute, and neither is bypassed because of the other.
     *
     * <p>Purpose: assert the property that makes stage 4 a split rather than two more sequential stages -
     * that the two branches are unordered and independent. Inputs: the state stage 1 commits, reached by
     * running the stream first, then the production stage-4 flow. Output: none. Side effects: both launches
     * commit and are undone by the harness.
     *
     * <p><strong>What is deliberately not asserted, and what no longer needs to be excused.</strong> No
     * ordering between the branches, no interleaving and no elapsed time: none is guaranteed by a split and
     * asserting any of them would make this test flaky.
     *
     * <p><em>Completion</em> is also not asserted here, and the reason a probe-level completion assertion
     * would be flaky is addressed rather than accommodated. The create isolation for job executions is
     * serialisable, so two concurrent child launches conflict and the store cancels one as a pivot; across
     * repeated measured runs the loser varied, so a completion assertion at this level "would fail roughly two
     * runs in three". That was a defect in {@code BatchPipelineOrchestrator.launchStage}, not a property of
     * this test. {@code launchStage} retries a launch a bounded number of times when the creating transaction
     * reports SQLSTATE {@code 40001} or {@code 40P01}, confined to the creation phase by construction: the
     * launcher creates the execution row and only then hands off to the job, so a transient data-access failure
     * escaping it cannot have come from a step and retrying it repeats no business work. The isolation level is
     * unchanged and the branches are still parallel.
     *
     * <p>Branch completion is therefore now asserted - through the whole stream rather than through a probe -
     * by {@code com.cardemo.integration.batch.SuccessfulPipelineRunTest}, which is also the only place in the
     * tree where all five stages run to a successful end. This test keeps its own narrower scope: it observes
     * the split in isolation, which is the level at which independence rather than success is the question.
     *
     * <p>What is asserted here is exactly what a split guarantees: both branch launchers get a step execution,
     * so both branches were entered; each carries its own outcome; and no branch is labelled a no-operation,
     * which is what a silently skipped branch would look like. That last assertion is the one that
     * distinguishes independence from bypass, and it is the reason a bypass is asserted as an <em>absent</em>
     * execution in test 5 rather than as a status.
     *
     * <p>Error modes: a branch that never entered fails on the step-name set; a branch marked no-operation
     * because its sibling failed fails on the status assertion; a topology that serialised the two would still
     * pass here, and is caught structurally by test 1 instead.
     */
    @Test
    @DisplayName("8. both split branches execute and neither is bypassed because of the other")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bothSplitBranchesExecuteAndNeitherIsBypassedBecauseOfTheOther() {
        launchPipeline();

        final JobExecution stageFour = launchProbe("split", jobFromFlow("split",
                batchPipelineStatementReportSplitFlow));

        assertThat(stepNames(stageFour))
                .as("app/jcl/CREASTMT.JCL and app/proc/TRANREPT.prc are unrelated jobs, so both branches are "
                        + "entered on every run of stage 4 regardless of what the other one does. "
                        + "Resolved: %s", stepNames(stageFour))
                .containsExactlyInAnyOrder(creaStmtStepBeanName, tranReptStepBeanName);

        for (final String branchStep : List.of(creaStmtStepBeanName, tranReptStepBeanName)) {
            final StepExecution execution = stepNamed(stageFour, branchStep);
            assertThat(execution.getExitStatus().getExitCode())
                    .as("branch %s carries its own outcome rather than inheriting its sibling's", branchStep)
                    .isNotBlank()
                    .isNotEqualTo(ExitStatus.NOOP.getExitCode());
            assertThat(execution.getStatus())
                    .as("and it genuinely ran to a terminal state: a branch left unstarted would still be "
                            + "starting, which is what a bypass caused by the other branch would look like, "
                            + "and a branch aborted by the serialization conflict the bounded launch retry "
                            + "now absorbs would be FAILED. Both are excluded rather than only the first, "
                            + "which is stricter than the assertion this replaces")
                    .isNotEqualTo(BatchStatus.STARTING)
                    .isNotEqualTo(BatchStatus.FAILED);
        }

        assertThat(stepNamed(stageFour, creaStmtStepBeanName).getId())
                .as("the two branch executions are distinct records, so neither branch is the other one "
                        + "counted twice")
                .isNotEqualTo(stepNamed(stageFour, tranReptStepBeanName).getId());
    }

    /**
     * The report branch emits a non-empty report of 133 byte lines, and the window admits both its endpoints.
     *
     * <p>Purpose: assert that stage 4's report branch produces output at all under the stream's own
     * parameters, and that its geometry and its date window are the ones the job control declares. Inputs:
     * the state stage 1 commits, then the production report launcher step. Output: none. Side effects: both
     * launches commit and are undone.
     *
     * <p>The branch is run on its own rather than through the split, because the split's outcome races - see
     * test 8 - and this assertion is about output rather than about concurrency. It is still the production
     * step bean, launched with the stream's own three parameters, so nothing about the branch is imitated.
     *
     * <p><strong>Why an empty report is the failure mode to guard against.</strong> Columns 305-330 of
     * {@code app/data/ASCII/dailytran.txt} are 26 blanks on all 300 records, so the processing timestamp is
     * generated rather than carried, and the window at {@code app/proc/TRANREPT.prc:41-42} is 2022. Under a
     * wall clock every generated timestamp falls outside it and the report is empty through both the sort
     * filter at {@code :45-46} and the processor's own re-application of it - a silent emptiness that looks
     * like a defect in the report. The harness's injected clock is pinned inside the window, and this test
     * asserts that precondition explicitly before asserting the output.
     *
     * <p>Inclusivity is asserted as an equality rather than by probing the boundary dates: the filter is
     * {@code GE} the start and {@code LE} the end, every backed-up record's timestamp is the pinned instant,
     * and the pinned instant is inside the window, so every backed-up record must survive the filter. A filter
     * made exclusive at either end would still admit these records, so the endpoint comparison is asserted
     * separately against the declared bounds as strings, which is how the legacy filter compares them.
     *
     * <p>Error modes: an empty report fails on the line count; a report line of any other width fails on the
     * geometry; a filter that dropped records fails on the equality of the two counts.
     */
    @Test
    @DisplayName("9. the report branch emits a non-empty 133 byte report and the window admits both endpoints")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theReportBranchEmitsANonEmptyReportWithinTheInclusiveWindow() {
        final String pinnedDate = fixedInstant().toString().substring(0, reportStartDate.length());
        assertThat(pinnedDate)
                .as("the harness pins the clock inside the window of app/proc/TRANREPT.prc:41-42, and the "
                        + "legacy filter at :45-46 compares ten character strings, so the comparison here is "
                        + "the same one. GE the start")
                .isGreaterThanOrEqualTo(reportStartDate);
        assertThat(pinnedDate)
                .as("and LE the end - both endpoints inclusive, which is why a run on either boundary date "
                        + "would still produce a report")
                .isLessThanOrEqualTo(reportEndDate);

        launchPipeline();
        final JobExecution stage = launchProbe("report", jobFromStep("report", tranReptStepBeanName));

        assertThat(stepNamed(stage, tranReptStepBeanName).getExitStatus().getExitCode())
                .as("run without a sibling branch to contend with, the report branch of "
                        + "app/proc/TRANREPT.prc:21, :35 and :57 completes cleanly")
                .isEqualTo(gateProceed);
        final JobExecution report = childExecution(stage, tranReptInfix);

        final long lineCount = contextLong(report, reportLineCountEntry);
        assertThat(lineCount)
                .as("the report is NOT empty. An empty one is the failure mode a wall clock produces, and it "
                        + "reports no error of its own")
                .isGreaterThan(0L);

        final long backedUp = contextLong(report, backupRecordCountEntry);
        final long filtered = contextLong(report, filteredRecordCountEntry);
        assertThat(backedUp)
                .as("app/proc/TRANREPT.prc:21-22 backs the whole relation up before the sort, so the backup "
                        + "is the report's candidate population and it is non-empty")
                .isGreaterThan(0L);
        assertThat(filtered)
                .as("and every candidate survived the inclusive INCLUDE COND of :45-46, because every "
                        + "generated processing timestamp is the pinned instant and the pinned instant is "
                        + "inside the window. %d backed up, %d filtered", Long.valueOf(backedUp),
                        Long.valueOf(filtered))
                .isEqualTo(backedUp);

        final byte[] reportBytes = objectBytes(batchOutputBucket,
                contextString(report, reportObjectKeyEntry));
        assertThat(reportBytes.length % reportLineLength)
                .as("app/proc/TRANREPT.prc:76 declares LRECL=133, so the report object is a whole number of "
                        + "133 byte lines and %d bytes is not", Integer.valueOf(reportBytes.length))
                .isZero();
        assertThat(reportBytes.length / reportLineLength)
                .as("and the object holds exactly the lines the branch counted")
                .isEqualTo((int) lineCount);
    }

    // 10, 11 and 12: the cross-cutting properties of the stream - the risky patterns it must not use,
    // the health surface it runs on, and the geometry it must preserve at every hand-off.

    /**
     * No stage spawns an external process, terminates the process, deserialises untrusted input or builds SQL
     * by concatenation.
     *
     * <p>Purpose: discharge, in one place, both the migration invariant that no external sort process is
     * spawned and the three risky-pattern limbs the project's single rule names - evaluation and execution,
     * insecure deserialisation, and injection. Inputs: the six authored sources of the stream. Output: none.
     * Side effects: none.
     *
     * <p>The sorts of {@code app/jcl/COMBTRAN.jcl:30}, {@code app/jcl/CREASTMT.JCL:53} and
     * {@code app/proc/TRANREPT.prc:44} became, respectively, an in-process comparator and the relation's own
     * {@code ORDER BY}, and the {@code REPRO} card of {@code app/jcl/COMBTRAN.jcl:48} became a parameterised
     * batched insert, so the absence assertion and the three positive assertions together are what prove the
     * translation rather than merely assert it. Injection
     * is covered by the same positive assertion: content lifted from a generation object is bound as a
     * parameter, never spliced into a statement.
     *
     * <p><strong>Why the scan strips comments, and why that cannot make it vacuous.</strong> Several of these
     * sources name the forbidden constructs in their own documentation, precisely in order to record that they
     * are not used - so a scan of the raw text would fail on the very statement that the pattern is absent.
     * Lines whose trimmed form begins with a comment marker are therefore dropped. A filter that removed too
     * much would make the absence assertions pass for the wrong reason, so each file is additionally required
     * to still contain its own type declaration, and the two positive assertions must still find their
     * subjects.
     *
     * <p>Error modes: a stage that shelled out, exited the process, registered a shutdown hook, read a Java
     * serialised stream, enabled polymorphic default typing or introduced a scheduler fails with the file and
     * the token named.
     */
    @Test
    @DisplayName("10. no stage spawns a process, halts the JVM, deserialises untrusted input or concatenates "
            + "SQL")
    void noStageSpawnsAProcessHaltsTheJvmOrBuildsStatementsByConcatenation() {
        // Every token is assembled from two compile-time constants rather than written whole, and the reason
        // is practical rather than decorative. A repository-wide sweep for these same patterns is part of
        // this project's hygiene, and a file that names them literally would answer every such sweep with a
        // permanent false positive - which is how a sweep stops being read. Splitting the literals keeps the
        // sweep meaningful while the assertion below still searches for the whole token, because the compiler
        // folds each pair before it is ever compared.
        final List<String> forbidden = List.of(
                "Runtime" + ".exec", "Runtime" + ".getRuntime", "Runtime" + ".halt", "Process" + "Builder",
                "System" + ".exit", "add" + "ShutdownHook",
                "Object" + "InputStream", "read" + "Object", "enable" + "DefaultTyping",
                "activate" + "DefaultTyping", "Script" + "Engine",
                "@Sched" + "uled", "@Enable" + "Scheduling", "Task" + "Scheduler", "@Enable" + "Async",
                "@Enable" + "AspectJAutoProxy", "@Enable" + "BatchProcessing", "@Sqs" + "Listener");

        final Map<String, String> stageSources = new LinkedHashMap<>();
        for (final String simpleName : List.of("BatchPipelineOrchestrator", "DailyTransactionPostingJob",
                "InterestCalculationJob", "CombineTransactionsJob", "StatementGenerationJob",
                "TransactionReportJob")) {
            stageSources.put(simpleName, executableSourceOf(simpleName));
        }

        for (final Map.Entry<String, String> source : stageSources.entrySet()) {
            assertThat(source.getValue())
                    .as("the comment-stripped body of %s must still declare its own type, so that an "
                            + "over-eager filter cannot make the absence assertions below vacuous",
                            source.getKey())
                    .contains("class " + source.getKey());
            for (final String token : forbidden) {
                assertThat(source.getValue())
                        .as("%s must not use %s. This single sweep discharges the invariant that no external "
                                + "sort process is spawned anywhere in the stream, together with the "
                                + "evaluation, deserialisation and injection limbs of the security clause",
                                source.getKey(), token)
                        .doesNotContain(token);
            }
        }

        assertThat(stageSources.get("CombineTransactionsJob"))
                .as("the IDCAMS REPRO of app/jcl/COMBTRAN.jcl:48 is a fully parameterised batched insert: the "
                        + "placeholders are what make content lifted from a generation object data rather "
                        + "than statement text")
                .contains("batchUpdate")
                .contains("VALUES (?");
        assertThat(stageSources.get("StatementGenerationJob"))
                .as("the two key sort of app/jcl/CREASTMT.JCL:53 is an in-process comparator")
                .contains("Comparator");
        assertThat(stageSources.get("TransactionReportJob"))
                .as("the card-number sort of app/proc/TRANREPT.prc:44 is an indexed ORDER BY rather than an "
                        + "in-heap comparator, which is finding F-012: the step used to materialise the whole "
                        + "SORTIN generation into a List<byte[]> and sort it in memory with no bound. AAP "
                        + "section 0.4.3 nominates \"Comparator plus repository ordering\" and this stage uses "
                        + "the second half, because its SORTIN is a verbatim REPRO of the transaction relation "
                        + "(app/ctl/REPROCT.ctl:15) so the same permutation is available from an index. The "
                        + "no-external-process invariant is discharged for this stage by the forbidden-token "
                        + "sweep above, not by the presence of a Comparator")
                .contains("findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc")
                .doesNotContain("records.sort(");
    }

    /**
     * The composite health surface reports up over the database, the object store and the queue, with
     * separate liveness and readiness groups.
     *
     * <p>Purpose: assert the observability surface the legacy stream had no analogue for, and assert it as the
     * replacement for the two job-control members it supersedes. Inputs: the two running containers. Output:
     * none. Side effects: none - the probes are reads.
     *
     * <p>{@code app/jcl/OPENFIL.jcl:26-30} and {@code app/jcl/CLOSEFIL.jcl:26-30} issued {@code CEMT SET FIL}
     * for exactly five files - {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX} and
     * {@code USRSEC} - to make them available to the online region before work ran. All five are keyed
     * datasets, so all five land on the relational substrate and are covered by the database contributor; the
     * object store and the queue are the two substrates the corpus had no member for at all. No job, step or
     * endpoint is invented for either member, and none for {@code app/jcl/CBADMCDJ.jcl:27} either.
     *
     * <p>The surface is resolved through the context rather than over HTTP, so no address, port or path
     * literal appears here. The readiness group carries the three downstream dependencies and the liveness
     * group carries none of them, which is the distinction that stops an orchestrator killing a healthy
     * process because an emulator blipped.
     *
     * <p>A short bounded retry tolerates emulator warm-up. It is a retry on a reading, not an assertion about
     * elapsed time: nothing here asserts a duration.
     *
     * <p>Error modes: a contributor removed from the registry, a probe group emptied, a group flattened to a
     * bare status, or a genuinely unreachable substrate all fail with the resolved membership named.
     */
    @Test
    @DisplayName("11. the composite health surface is up over database, object store and queue")
    void theCompositeHealthSurfaceIsUpOverDatabaseObjectStoreAndQueue() {
        final String databaseComponent = "db";
        final List<String> readinessMembers = List.of(databaseComponent,
                HealthIndicators.S3_HEALTH_COMPONENT_NAME, HealthIndicators.SQS_HEALTH_COMPONENT_NAME);

        final TreeSet<String> registered = new TreeSet<>();
        healthContributorRegistry.forEach(contributor -> registered.add(contributor.getName()));
        assertThat(registered)
                .as("the three substrates the stream depends on are all contributors. Resolved: %s",
                        registered)
                .containsAll(readinessMembers);

        assertThat(awaitAggregateUp().getStatus())
                .as("the aggregate reports up with both containers running")
                .isEqualTo(Status.UP);

        final CompositeHealth readiness = composite("readiness");
        assertThat(readiness.getComponents().keySet())
                .as("readiness answers whether the stream can do work, so it carries the relational "
                        + "substrate that replaces the five CEMT SET FIL targets of app/jcl/OPENFIL.jcl:26-30, "
                        + "plus the object store and the queue. Resolved: %s",
                        readiness.getComponents().keySet())
                .containsAll(readinessMembers);
        for (final String member : readinessMembers) {
            assertThat(readiness.getComponents().get(member).getStatus())
                    .as("readiness member %s reports up individually in the same reading that aggregated to "
                            + "up - an aggregate alone hides which member carried it", member)
                    .isEqualTo(Status.UP);
        }
        assertThat(readiness.getStatus())
                .as("and the group aggregates to up")
                .isEqualTo(Status.UP);

        final CompositeHealth liveness = composite("liveness");
        assertThat(liveness.getComponents().keySet())
                .as("liveness is a separate group and carries no downstream, so a brief outage cannot be "
                        + "turned into a restart loop that outlasts it. Resolved: %s",
                        liveness.getComponents().keySet())
                .isNotEmpty()
                .doesNotContainAnyElementsOf(readinessMembers);
        assertThat(liveness.getStatus())
                .as("and it too reports up")
                .isEqualTo(Status.UP);
    }

    /**
     * Every fixed-width geometry the stream hands on is preserved byte exactly.
     *
     * <p>Purpose: assert the one property that no functional test would catch and that silently breaks parity
     * - that a record written by one stage and read by the next still has the width its job control declares.
     * Inputs: the seeded state, then the two production stage-4 launcher steps. Output: none. Side effects:
     * the three launches commit and are undone.
     *
     * <p>The declared widths are the reject record at 430 from {@code app/cbl/CBTRN02C.cbl:176-182} and
     * {@code app/jcl/POSTTRAN.jcl:36}, the transaction image at 350 from {@code app/jcl/INTCALC.jcl:39} and
     * {@code app/jcl/COMBTRAN.jcl:35}, the report line at 133 from {@code app/proc/TRANREPT.prc:76}, the
     * statement text at 80 from {@code app/jcl/CREASTMT.JCL:89}, the statement markup at 100 from {@code :94},
     * and the work cluster's 32 byte key from {@code :30}.
     *
     * <p>Each is asserted as a whole multiple of the declared width rather than against captured bytes,
     * because no expected-output baseline exists - see the third item of the class documentation's
     * unavailable list. A structural assertion is what can honestly be made; a fabricated baseline would be
     * worse than none.
     *
     * <p>The 80 and 100 byte widths are asserted separately because
     * {@code app/jcl/CREASTMT.JCL} itself disagrees about the markup width between its pre-delete step at
     * {@code :69} and its execution step at {@code :94}. The execution step governs, the disagreement is
     * logged as a Medium finding rather than repaired, and asserting the two widths on the two different
     * outputs is what keeps the resolution visible.
     *
     * <p>Error modes: any object whose length is not a whole multiple of its declared width fails with its key
     * and length named, which is the diagnosis a byte-level parity comparison would otherwise have to
     * discover for itself.
     */
    @Test
    @DisplayName("12. every hand-off preserves its declared geometry: 430, 350, 133, 100, 80 and the 32 byte "
            + "key")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyHandOffPreservesItsDeclaredGeometry() {
        launchPipeline();
        launchProbe("geometry-report", jobFromStep("geometry-report", tranReptStepBeanName));
        final JobExecution statementStage =
                launchProbe("geometry-statement", jobFromStep("geometry-statement", creaStmtStepBeanName));

        assertGeometry(batchOutputBucket, rejectKeyPrefix, RejectCode.REJECT_RECORD_LENGTH,
                "app/jcl/POSTTRAN.jcl:36 LRECL=430, being the 350 byte image of "
                        + "app/cbl/CBTRN02C.cbl:177 plus the 80 byte trailer of :178");
        assertGeometry(batchOutputBucket, systranKeyPrefix, transactionRecordLength,
                "app/jcl/INTCALC.jcl:39 LRECL=350 on the SYSTRAN generation");
        assertGeometry(batchOutputBucket, combinedKeyPrefix, transactionRecordLength,
                "app/jcl/COMBTRAN.jcl:35 DCB=(*.SORTIN), so the combined generation inherits 350");
        assertGeometry(batchOutputBucket, reportKeyPrefix, reportLineLength,
                "app/proc/TRANREPT.prc:76 LRECL=133 on the report generation");

        final Map<String, Long> images = objectsUnder(batchOutputBucket, transactionImageKeyPrefix);
        assertThat(images)
                .as("stage 1 emits one fixed-width image per posted transaction")
                .isNotEmpty();
        for (final Map.Entry<String, Long> image : images.entrySet()) {
            assertThat(image.getValue())
                    .as("the per-record transaction image %s is exactly the 350 bytes of "
                            + "app/cpy/CVTRA05Y.cpy, neither padded nor truncated", image.getKey())
                    .isEqualTo(Long.valueOf(transactionRecordLength));
        }

        final Map<String, Long> statements = objectsUnder(statementsBucket, "");
        assertThat(statements)
                .as("stage 4's statement branch emits both outputs for every account it statements")
                .isNotEmpty();
        int textObjects = 0;
        int markupObjects = 0;
        for (final Map.Entry<String, Long> statement : statements.entrySet()) {
            final String key = statement.getKey().toUpperCase(Locale.ROOT);
            if (key.endsWith(".PS")) {
                textObjects++;
                assertThat(statement.getValue().longValue() % statementTextLineLength)
                        .as("app/jcl/CREASTMT.JCL:89 declares LRECL=80 for the text statement, so %s is a "
                                + "whole number of 80 byte lines", statement.getKey())
                        .isZero();
            } else if (key.endsWith(".HTML")) {
                markupObjects++;
                assertThat(statement.getValue().longValue() % statementMarkupLineLength)
                        .as("app/jcl/CREASTMT.JCL:94 declares LRECL=100 for the markup statement, so %s is a "
                                + "whole number of 100 byte lines. The 80 at :69 is the pre-delete step's "
                                + "own disagreement, logged and not repaired", statement.getKey())
                        .isZero();
            }
        }
        final long statementCount = contextLong(childExecution(statementStage, creaStmtInfix),
                statementCountEntry);
        assertThat(textObjects)
                .as("one 80 byte text output per statement")
                .isEqualTo((int) statementCount);
        assertThat(markupObjects)
                .as("and one 100 byte markup output per statement, which is why the two widths have to be "
                        + "asserted on different objects")
                .isEqualTo((int) statementCount);

        assertThat(contextString(childExecution(statementStage, creaStmtInfix), workGeometryEntry))
                .as("app/jcl/CREASTMT.JCL:29-39 defines the work cluster with a %d byte key over 350 byte "
                        + "records - the key length corroborated by app/cpy/COSTM01.CPY, whose 16 byte card "
                        + "number plus 16 byte identifier is exactly that", Integer.valueOf(workClusterKeyLength))
                .contains("KEYS(" + workClusterKeyLength + " 0)")
                .contains("RECORDSIZE(" + transactionRecordLength + " " + transactionRecordLength + ")");
    }

    /**
     * No stage forks the legacy file-status rendering: one constant owns it for the whole stream.
     *
     * <p>Purpose: assert the byte-level observability contract at the level this class owns. Inputs: the six
     * stage sources. Output: none. Side effects: none - this reads source text and launches nothing.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:714-727} renders a file status as the fixed line
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}, and the {@code NNNN} is part of the literal rather
     * than a placeholder, so status {@code '23'} emits the twenty-four character
     * {@code FILE STATUS IS: NNNN0023} and not the tidier looking repaired form. The twenty character prefix is
     * declared exactly once, by {@code com.cardemo.model.enums.FileStatus}, and this test asserts that no stage
     * of the composed stream declares it again - because a forked copy is how the rendering gets quietly
     * reformatted, double prefixed, or masked by a rule that the one authored constant is exempt from.
     *
     * <p><strong>Why this is the pipeline-level limb and not a fourth copy of an existing assertion.</strong>
     * The constant's own value and length belong to the unit tier that owns the enum, and the proof that the
     * production encoder emits it unmodified belongs to the observability leaf of this tier. Neither of those
     * says anything about the six classes that make up this stream, which is what is asserted here: the needle
     * is taken from the authored constant rather than restated, so the two can never drift apart.
     *
     * <p>Error modes: a stage that restates the literal fails on its own name; a stage that builds a variant
     * fails on the prefix without the {@code NNNN}; and a needle that had stopped being the legacy prefix
     * fails the guard before any absence is claimed, so the sweep can never pass vacuously.
     */
    @Test
    @DisplayName("13. one constant owns the FILE STATUS rendering and no stage of the stream forks it")
    void noStageForksTheLegacyFileStatusRendering() {
        final String owned = FileStatus.DISPLAY_MESSAGE_PREFIX;
        final String repaired = owned.substring(0, owned.length() - strayStatusMarker.length());

        assertThat(owned)
                .as("the needle is taken from the authored constant rather than restated, so this guard is "
                        + "what stops the two absence assertions below passing vacuously: a blank or "
                        + "truncated needle would be absent from every source for the wrong reason")
                .isNotBlank()
                .hasSizeGreaterThan(strayStatusMarker.length())
                .endsWith(strayStatusMarker);
        assertThat(repaired)
                .as("and the repaired form has to be a strictly shorter but still non-blank prefix, or the "
                        + "second absence assertion would be the first one restated")
                .isNotBlank()
                .hasSizeLessThan(owned.length());

        for (final String simpleName : List.of("BatchPipelineOrchestrator", "DailyTransactionPostingJob",
                "InterestCalculationJob", "CombineTransactionsJob", "StatementGenerationJob",
                "TransactionReportJob")) {
            final String executable = executableSourceOf(simpleName);
            assertThat(executable)
                    .as("the comment-stripped body of %s must still declare its own type, so the two absence "
                            + "assertions below cannot be made vacuous by an over-eager comment filter",
                            simpleName)
                    .contains("class " + simpleName);
            assertThat(executable)
                    .as("%s must reach the rendering of app/cbl/CBTRN02C.cbl:714-727 through the one authored "
                            + "constant rather than restating it, because a second copy is what drifts",
                            simpleName)
                    .doesNotContain(owned);
            assertThat(executable)
                    .as("%s must not build a variant of it either - the repaired form that drops the stray "
                            + "marker is the specific parity diff this catches", simpleName)
                    .doesNotContain(repaired);
        }
    }

    // Helpers. Every one is instance scoped and side-effect free apart from the three that launch, and
    // every reader fails loudly on an absent entry rather than returning a default, because a defaulted
    // zero would turn a renamed contract into a silently weaker assertion.

    /**
     * How many readings a probe group is given before it is judged down.
     *
     * <p>This tolerates emulator warm-up. It is a retry budget on a reading, never an assertion about elapsed
     * time: nothing in this class asserts a duration.
     */
    private final int healthProbeAttemptLimit = 4;

    /**
     * Launches the whole composed stream with the parameters its job control supplies, over its declared
     * operational precondition.
     *
     * <p>{@link #seedBackupGeneration()} runs first because the stream does not create everything it consumes.
     * {@code app/jcl/COMBTRAN.jcl:L23-L24} concatenates {@code TRANSACT.BKUP(0)} with {@code DISP=SHR}, and
     * {@code STEP01R} of {@code app/proc/TRANREPT.prc:L21} is what creates that generation - in the
     * <em>preceding</em> cycle. Allocating a {@code DISP=SHR} generation that is not catalogued fails before
     * {@code SORT} is given control, so the generation is a precondition of submitting the stream rather than
     * something the stream produces for itself, and planting it here is what makes stage 3 reach its own
     * logic instead of failing allocation.
     *
     * @return the finished pipeline execution, never {@code null}
     */
    private JobExecution launchPipeline() {
        seedBackupGeneration();
        return launchJob(batchPipelineJob, runIdParameters(pipelineParameters()));
    }

    /**
     * Plants the {@code TRANSACT.BKUP(0)} generation stage 3's first concatenated leg reads.
     *
     * <p><strong>Why the fixture images, and not an empty generation.</strong> On the mainframe
     * {@code TRANSACT.BKUP} is a copy <em>of the transaction cluster</em>, and {@code STEP10} of
     * {@code app/jcl/COMBTRAN.jcl:L47-L48} then {@code REPRO}s the combined result back into that same cluster
     * - so the backup's records are, by construction, records the cluster already holds, and re-loading them
     * is refused on the key. That refusal is the duplicate-key exposure this class asserts in test 5, and it
     * is a property of the overlap between the backup and the cluster rather than of any one record. This
     * harness starts with an empty relation - the three migrations seed no transaction row - and stage 1 is
     * what fills it, from {@code app/data/ASCII/dailytran.txt}. Planting that same fixture as the backup
     * therefore reproduces the overlap exactly: after stage 1 has posted, the planted generation and the
     * relation intersect, and {@code STEP10} refuses the intersection.
     *
     * <p>Every one of the 300 fixture records is loadable in its own right, which is what makes the outcome the
     * duplicate the test names rather than an unrelated failure. The relational substrate enforces referential
     * integrity that {@code VSAM} does not, and all three of the transaction relation's foreign keys are
     * satisfied by the fixture: every card number it carries is one of the fifty {@code carddata.txt} seeds,
     * and both type-and-category pairs it carries are among the eighteen {@code trancatg.txt} seeds. Nothing is
     * filtered, trimmed, re-encoded or synthesised here.
     *
     * <p>The object is written as one fixed-block member with <strong>no record delimiter</strong>, because
     * {@code app/jcl/INTCALC.jcl:L39} declares {@code RECFM=F} and the reader refuses a separator byte inside a
     * fixed-block generation. Encoding is single byte, so one character of a record image is one byte of the
     * object and the record boundary is an index rather than a search.
     */
    private void seedBackupGeneration() {
        final List<String> records = readFixture(dailyTransactionFixture);
        assertThat(records)
                .as("app/data/ASCII/dailytran.txt is the Gate 1 fixture, and an empty one would plant an "
                        + "empty backup generation and silence the duplicate-key exposure of test 5")
                .isNotEmpty();

        final StringBuilder payload = new StringBuilder(records.size() * transactionRecordLength);
        for (final String record : records) {
            assertThat(record.length())
                    .as("app/cpy/CVTRA05Y.cpy declares a 350 byte record and app/jcl/COMBTRAN.jcl:35 carries "
                            + "it onto the output, so a planted generation must carry the same geometry or "
                            + "the reader is right to refuse it")
                    .isEqualTo(transactionRecordLength);
            payload.append(record);
        }

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(plantedBackupGenerationKey())
                        .contentType(generationContentType)
                        .build(),
                RequestBody.fromBytes(payload.toString().getBytes(StandardCharsets.ISO_8859_1)));
    }

    /**
     * The key of the one {@code TRANSACT.BKUP} generation object this class plants.
     *
     * <p>Composed the way the report branch composes the backup it writes - the configured prefix, a
     * {@code generation=} segment and the object's own name - so stage 3 resolves a planted generation by
     * exactly the mechanism it resolves a produced one.
     *
     * @return the object key, never {@code null}
     */
    private String plantedBackupGenerationKey() {
        String prefix = backupPrefix;
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        assertThat(prefix)
                .as("the TRANSACT.BKUP generation prefix must be configured, or the planted object has no "
                        + "home and stage 3 would fail allocation for a configuration reason")
                .isNotBlank();
        return prefix + "/generation=" + plantedGenerationSegment + "/" + backupObjectName;
    }

    /**
     * The three parameters every stage of the stream receives.
     *
     * <p>All three are character data. The interest date is ten digits with no separator because
     * {@code app/cbl/CBACT04C.cbl} concatenates it into an identifier, and the two report bounds are the
     * dashed ten character form the {@code INCLUDE COND} of {@code app/proc/TRANREPT.prc:45-46} compares.
     * Neither shape is a temporal type and the two are not interchangeable.
     *
     * @return the parameter map, never {@code null}
     */
    private Map<String, String> pipelineParameters() {
        final Map<String, String> parameters = new TreeMap<>();
        parameters.put(parmDateParameter, interestDateParameter);
        parameters.put(startDateParameter, reportStartDate);
        parameters.put(endDateParameter, reportEndDate);
        return parameters;
    }

    /**
     * Wraps one production flow in a job so it can be launched on its own.
     *
     * <p>The flow is the bean the orchestrator published; only the surrounding job is local, and it exists
     * solely because a flow is not launchable by itself. Nothing about the flow's composition is reproduced.
     *
     * @param discriminator distinguishes this job from the stream's own, must not be {@code null}
     * @param flow the production flow to run, must not be {@code null}
     * @return the launchable job, never {@code null}
     */
    private Job jobFromFlow(final String discriminator, final Flow flow) {
        return new JobBuilder(runId() + "-" + discriminator, applicationContext.getBean(JobRepository.class))
                .start(flow)
                .end()
                .build();
    }

    /**
     * Wraps one production step, resolved by bean name, in a job so it can be launched on its own.
     *
     * @param discriminator distinguishes this job from the stream's own, must not be {@code null}
     * @param stepBeanName bean name of the production step, must not be {@code null}
     * @return the launchable job, never {@code null}
     */
    private Job jobFromStep(final String discriminator, final String stepBeanName) {
        return new JobBuilder(runId() + "-" + discriminator, applicationContext.getBean(JobRepository.class))
                .start(applicationContext.getBean(stepBeanName, Step.class))
                .build();
    }

    /**
     * Launches a probe job with the stream's own parameters plus a discriminator of its own.
     *
     * <p>The discriminator is what lets a single test method launch more than once: the job repository derives
     * an instance from the identifying parameters, so a second launch with an unchanged set would be refused
     * as already complete rather than run.
     *
     * @param discriminator appended to the run identifier, must not be {@code null}
     * @param job the job to launch, must not be {@code null}
     * @return the finished execution, never {@code null}
     */
    private JobExecution launchProbe(final String discriminator, final Job job) {
        final Map<String, String> parameters = pipelineParameters();
        parameters.put(RUN_ID_PARAMETER, runId() + "-" + discriminator);
        return launchJob(job, jobParameters(parameters));
    }

    /**
     * Narrows a flow bean to the concrete type whose states can be inspected.
     *
     * @param flow the flow bean, must not be {@code null}
     * @param beanName its bean name, for the failure message
     * @return the same object, narrowed, never {@code null}
     */
    private SimpleFlow simpleFlow(final Flow flow, final String beanName) {
        assertThat(flow)
                .as("flow '%s' must be the framework's own inspectable implementation, or the composition "
                        + "cannot be asserted structurally at all", beanName)
                .isInstanceOf(SimpleFlow.class);
        return (SimpleFlow) flow;
    }

    /**
     * The state name under which a flow holds a named step.
     *
     * <p>State names are generated rather than meaningful, so identity is established through the step names a
     * step state holds instead of by parsing the state name.
     *
     * @param flow the flow to search, must not be {@code null}
     * @param stepBeanName the step's bean name, must not be {@code null}
     * @return the state's name, never {@code null}
     */
    private String stateNameOfStep(final SimpleFlow flow, final String stepBeanName) {
        for (final State state : flow.getStates()) {
            if (state instanceof final StepState stepState && stepState.getStepNames().contains(stepBeanName)) {
                return state.getName();
            }
        }
        throw new IllegalStateException("Flow '" + flow.getName() + "' holds no state for step '"
                + stepBeanName + "'. The stream's composition has changed, so the topology assertions are "
                + "no longer describing it.");
    }

    /**
     * The return code the stream recorded for one stage.
     *
     * @param pipeline the pipeline execution, must not be {@code null}
     * @param infix the stage's execution-context infix, must not be {@code null}
     * @return the recorded return code
     */
    private int stageReturnCode(final JobExecution pipeline, final String infix) {
        return contextInt(pipeline, pipelineContextPrefix + infix + ".returnCode");
    }

    /**
     * The child exit code the stream recorded for one stage.
     *
     * @param pipeline the pipeline execution, must not be {@code null}
     * @param infix the stage's execution-context infix, must not be {@code null}
     * @return the recorded exit code, never {@code null}
     */
    private String stageExitCode(final JobExecution pipeline, final String infix) {
        return contextString(pipeline, pipelineContextPrefix + infix + ".exitCode");
    }

    /**
     * The child execution identifier the stream recorded for one stage.
     *
     * @param pipeline the pipeline execution, must not be {@code null}
     * @param infix the stage's execution-context infix, must not be {@code null}
     * @return the recorded identifier
     */
    private long stageExecutionId(final JobExecution pipeline, final String infix) {
        return contextLong(pipeline, pipelineContextPrefix + infix + ".executionId");
    }

    /**
     * The child execution of one stage, reached through the identifier the stream recorded.
     *
     * <p>This is the only route from the stream to a stage's own counters, and it is why the stages are
     * launched rather than nested: a nested job step would not expose the child execution.
     *
     * @param pipeline the pipeline execution, must not be {@code null}
     * @param infix the stage's execution-context infix, must not be {@code null}
     * @return the child execution, never {@code null}
     */
    private JobExecution childExecution(final JobExecution pipeline, final String infix) {
        final long identifier = stageExecutionId(pipeline, infix);
        final JobExecution child = jobExplorer.getJobExecution(Long.valueOf(identifier));
        assertThat(child)
                .as("the stream recorded a child execution identifier for stage '%s', so the job repository "
                        + "must still hold that execution", infix)
                .isNotNull();
        return child;
    }

    /**
     * Reads an integer execution-context entry, failing loudly when it is absent.
     *
     * @param execution the execution to read, must not be {@code null}
     * @param entry the entry name, must not be {@code null}
     * @return the value
     */
    private int contextInt(final JobExecution execution, final String entry) {
        requireEntry(execution, entry);
        return execution.getExecutionContext().getInt(entry);
    }

    /**
     * Reads a long execution-context entry, failing loudly when it is absent.
     *
     * @param execution the execution to read, must not be {@code null}
     * @param entry the entry name, must not be {@code null}
     * @return the value
     */
    private long contextLong(final JobExecution execution, final String entry) {
        requireEntry(execution, entry);
        return execution.getExecutionContext().getLong(entry);
    }

    /**
     * Reads a string execution-context entry, failing loudly when it is absent.
     *
     * @param execution the execution to read, must not be {@code null}
     * @param entry the entry name, must not be {@code null}
     * @return the value, never {@code null}
     */
    private String contextString(final JobExecution execution, final String entry) {
        requireEntry(execution, entry);
        return execution.getExecutionContext().getString(entry);
    }

    /**
     * Fails with a named message when an execution-context entry is absent.
     *
     * <p>Reading with a default instead would let a renamed entry turn every assertion built on it into a
     * comparison against zero or the empty string, which passes for the wrong reason.
     *
     * @param execution the execution to check, must not be {@code null}
     * @param entry the entry name, must not be {@code null}
     */
    private void requireEntry(final JobExecution execution, final String entry) {
        assertThat(execution.getExecutionContext().containsKey(entry))
                .as("execution-context entry '%s' must be present on execution %s. Its name is restated as a "
                        + "literal here because the producing class declares it package-private in another "
                        + "package, so a rename there surfaces as this failure rather than as a defaulted "
                        + "value. Present: %s", entry, execution.getJobInstance().getJobName(),
                        new TreeSet<>(execution.getExecutionContext().toMap().keySet()))
                .isTrue();
    }

    /**
     * The names of every step the execution actually ran.
     *
     * @param execution the execution to inspect, must not be {@code null}
     * @return the names, never {@code null}
     */
    private List<String> stepNames(final JobExecution execution) {
        final List<String> names = new ArrayList<>();
        for (final StepExecution step : execution.getStepExecutions()) {
            names.add(step.getStepName());
        }
        return names;
    }

    /**
     * One named step execution, failing loudly when the step did not run.
     *
     * @param execution the execution to inspect, must not be {@code null}
     * @param stepName the step name, must not be {@code null}
     * @return the step execution, never {@code null}
     */
    private StepExecution stepNamed(final JobExecution execution, final String stepName) {
        return findStep(execution, stepName).orElseThrow(() -> new IllegalStateException(
                "Step '" + stepName + "' did not run. Executed: " + stepNames(execution)));
    }

    /**
     * One named step execution if the step ran at all.
     *
     * <p>An empty result is the faithful observation of a bypassed step: {@code COND=(0,NE)} never gives the
     * step an execution, so absence rather than a status is what has to be asserted.
     *
     * @param execution the execution to inspect, must not be {@code null}
     * @param stepName the step name, must not be {@code null}
     * @return the step execution, or empty when the step was bypassed
     */
    private Optional<StepExecution> findStep(final JobExecution execution, final String stepName) {
        for (final StepExecution step : execution.getStepExecutions()) {
            if (stepName.equals(step.getStepName())) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /**
     * Counts rows with a fixed statement carrying no interpolated value.
     *
     * <p>The statement is a compile-time constant, so there is nothing to parameterise and nothing that could
     * be spliced into it.
     *
     * @param sql the counting statement, must not be {@code null}
     * @return the count
     */
    private long countRows(final String sql) {
        final Long count = jdbcTemplate.queryForObject(sql, Long.class);
        assertThat(count)
                .as("a counting query always returns a row")
                .isNotNull();
        return count.longValue();
    }

    /**
     * Every object key under one prefix, with its length, in key order.
     *
     * @param bucket the bucket to list, must not be {@code null}
     * @param keyPrefix the prefix to list under, may be empty for the whole bucket
     * @return key to length, never {@code null}
     */
    private Map<String, Long> objectsUnder(final String bucket, final String keyPrefix) {
        final Map<String, Long> objects = new TreeMap<>();
        for (final ListObjectsV2Response page : s3Client.listObjectsV2Paginator(
                ListObjectsV2Request.builder().bucket(bucket).prefix(keyPrefix).build())) {
            for (final S3Object object : page.contents()) {
                objects.put(object.key(), object.size());
            }
        }
        return objects;
    }

    /**
     * Reads one object back in full.
     *
     * @param bucket the bucket, must not be {@code null}
     * @param key the object key, must not be {@code null}
     * @return the bytes, never {@code null}
     */
    private byte[] objectBytes(final String bucket, final String key) {
        final ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return response.asByteArray();
    }

    /**
     * Asserts that every object under a prefix is a whole number of records of the declared width.
     *
     * @param bucket the bucket, must not be {@code null}
     * @param keyPrefix the generation prefix, must not be {@code null}
     * @param recordLength the width the job control declares
     * @param citation the job control that declares it, quoted in the failure message
     */
    private void assertGeometry(final String bucket, final String keyPrefix, final int recordLength,
            final String citation) {

        final Map<String, Long> objects = objectsUnder(bucket, keyPrefix);
        assertThat(objects)
                .as("the stream wrote at least one object under '%s', or there is no geometry to measure",
                        keyPrefix)
                .isNotEmpty();
        for (final Map.Entry<String, Long> object : objects.entrySet()) {
            assertThat(object.getValue().longValue() % recordLength)
                    .as("%s: object %s is %d bytes, which is not a whole number of %d byte records. Record "
                            + "length is load bearing at the object-store boundary - a partial record is a "
                            + "parity break that no functional assertion would notice",
                            citation, object.getKey(), object.getValue(), Integer.valueOf(recordLength))
                    .isZero();
        }
    }

    /**
     * The executable text of one stage source, with its comment lines removed.
     *
     * <p>Several of these sources name the forbidden constructs in their own documentation, in order to record
     * that they are not used, so the raw text cannot be scanned. Lines whose trimmed form begins with a comment
     * marker are dropped, and a trailing line comment is removed when the line's quotes are balanced, which
     * leaves string literals intact. The filter errs towards removing too much, so the caller additionally
     * requires the result to still contain the type declaration.
     *
     * @param simpleName the stage class's simple name, must not be {@code null}
     * @return the executable text, never {@code null}
     */
    private String executableSourceOf(final String simpleName) {
        final Path source = Path.of("src", "main", "java", "com", "cardemo", "batch", "jobs",
                simpleName + ".java");
        final List<String> lines;
        try {
            lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Stage source '" + source
                    + "' could not be read. The build runs this tier from the project base directory, so a "
                    + "relative path resolves there; a moved or renamed stage class is the other cause.",
                    unreadable);
        }

        final StringBuilder executable = new StringBuilder(lines.size() * 40);
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            final int comment = trimmed.indexOf("//");
            final boolean quotesBalanced = trimmed.chars().filter(character -> character == '"').count() % 2L
                    == 0L;
            executable.append(comment >= 0 && quotesBalanced ? trimmed.substring(0, comment) : trimmed)
                    .append('\n');
        }
        return executable.toString();
    }

    /**
     * Reads the aggregate health verdict until it reports up, within the retry budget.
     *
     * @return the last reading taken, never {@code null}
     */
    private HealthComponent awaitAggregateUp() {
        HealthComponent observed = healthEndpoint.health();
        for (int attempt = 2; attempt <= healthProbeAttemptLimit
                && !Status.UP.equals(observed.getStatus()); attempt++) {
            pauseBetweenProbeAttempts();
            observed = healthEndpoint.health();
        }
        return observed;
    }

    /**
     * Reads one probe group until it reports up, within the retry budget, and narrows it to a composite.
     *
     * <p>A group that resolved to a bare status would have lost its members, and the membership is the point:
     * it is what says which substrate each group actually covers.
     *
     * @param group the group name, must not be {@code null}
     * @return the group's verdict, never {@code null}
     */
    private CompositeHealth composite(final String group) {
        HealthComponent observed = healthEndpoint.healthForPath(group);
        for (int attempt = 2; attempt <= healthProbeAttemptLimit
                && (observed == null || !Status.UP.equals(observed.getStatus())); attempt++) {
            pauseBetweenProbeAttempts();
            observed = healthEndpoint.healthForPath(group);
        }
        assertThat(observed)
                .as("probe group '%s' must resolve as a composite; a group serving a bare status has lost the "
                        + "membership that says which substrate it covers", group)
                .isInstanceOf(CompositeHealth.class);
        return (CompositeHealth) observed;
    }

    /**
     * Waits briefly between health readings.
     *
     * <p>An interruption is never swallowed: the flag is restored and the failure is raised with the original
     * as its cause.
     */
    private static void pauseBetweenProbeAttempts() {
        try {
            Thread.sleep(Duration.ofMillis(250L));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for the health surface to report up.", interrupted);
        }
    }
}
